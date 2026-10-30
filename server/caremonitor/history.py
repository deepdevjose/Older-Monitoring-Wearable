"""Encrypted metric rollups; invalid/missing observations never become zeroes.

The chart uses UTC-aligned hourly means, weighted by the number of valid
observations. Boundary hours are read exactly so a rolling range does not
include samples outside its stated interval. Legacy indexing is incremental
and its remaining coverage is explicitly reported rather than concealed.
"""
from datetime import datetime, timezone
import math
from time import time as current_time

METRICS = {
    "heart_rate_bpm": ("Heart rate", "bpm"),
    "spo2_percent": ("Estimated oxygen", "%"),
    "surface_temp_c": ("Surface temperature", "°C"),
    "ambient_temp_c": ("Ambient temperature", "°C"),
    "light_adc": ("Light / contact", "ADC"),
    "acceleration_g": ("Movement", "g"),
}
PERIODS = {"day": (86400, 3600), "week": (7*86400, 6*3600), "month": (30*86400, 86400)}
BACKFILL_LIMIT = 10000
EDGE_LIMIT = 20000


def stamp(seconds):
    return datetime.fromtimestamp(seconds, timezone.utc).isoformat().replace("+00:00", "Z")


def number(value, minimum, maximum):
    return value if type(value) in (int, float) and math.isfinite(value) and minimum <= value <= maximum else None


def metric_values(payload):
    """Mirror the live-card validity flags, without assigning clinical meaning."""
    optical = payload.get("max30102", {})
    optical_ok = (optical.get("status") == "ok" and optical.get("online") is not False
                  and optical.get("finger_detected") is not False)
    temperature = payload.get("gy906", {})
    temperature_ok = temperature.get("online") is True and temperature.get("status", "ok") == "ok"
    motion = payload.get("mpu6050", {})
    vector = motion.get("accel_g", {})
    xyz = [number(vector.get(axis), -100, 100) for axis in ("x", "y", "z")]
    acceleration = math.sqrt(sum(value*value for value in xyz)) if motion.get("online") is True and all(value is not None for value in xyz) else None
    return {
        "heart_rate_bpm": number(optical.get("heart_rate_bpm"), 1, 400) if optical_ok and optical.get("heart_rate_valid") is True else None,
        "spo2_percent": number(optical.get("spo2_percent"), 0, 100) if optical_ok and optical.get("spo2_valid") is True else None,
        "surface_temp_c": number(temperature.get("object_temp_c"), -70, 380) if temperature_ok else None,
        "ambient_temp_c": number(temperature.get("ambient_temp_c"), -70, 380) if temperature_ok else None,
        "light_adc": number(payload.get("photoresistor", {}).get("adc_raw"), 0, 4095),
        "acceleration_g": acceleration,
    }


def empty_stat():
    return {"count": 0, "sum": 0.0, "min": None, "max": None, "latest": None, "latest_at": None, "invalid": 0}


def add(stat, value, captured):
    if value is None:
        stat["invalid"] += 1
        return
    stat["count"] += 1
    stat["sum"] += value
    stat["min"] = value if stat["min"] is None else min(stat["min"], value)
    stat["max"] = value if stat["max"] is None else max(stat["max"], value)
    if stat["latest_at"] is None or captured >= stat["latest_at"]:
        stat["latest_at"], stat["latest"] = captured, value


def merge(target, other):
    target["count"] += other["count"]
    target["sum"] += other["sum"]
    target["invalid"] += other["invalid"]
    if not other["count"]:
        return
    target["min"] = other["min"] if target["min"] is None else min(target["min"], other["min"])
    target["max"] = other["max"] if target["max"] is None else max(target["max"], other["max"])
    if target["latest_at"] is None or other["latest_at"] >= target["latest_at"]:
        target["latest_at"], target["latest"] = other["latest_at"], other["latest"]


def aad(patient, source, hour):
    return f"metric-history:{patient}:{source}:{hour}"


def index_reading(conn, db, row, envelope=None):
    """Idempotent and inside the reading's transaction; all six metrics at once."""
    if conn.execute("SELECT 1 FROM metric_history_indexed WHERE reading_id=?", (row["id"],)).fetchone():
        return
    hour = row["captured_at"] // 3600 * 3600
    key = (row["elder_id"], row["source"], hour)
    previous = conn.execute("SELECT detail FROM metric_history_rollups WHERE elder_id=? AND source=? AND hour_start=?", key).fetchone()
    detail = db.vault.open(previous[0], aad(*key)) if previous else {metric: empty_stat() for metric in METRICS}
    if envelope is None:
        envelope = db.vault.open(row["envelope"], f"reading:{row['id']}")
    for metric, value in metric_values(envelope["payload"]).items():
        add(detail[metric], value, row["captured_at"])
    conn.execute("INSERT INTO metric_history_rollups(elder_id,source,hour_start,detail) VALUES(?,?,?,?) ON CONFLICT(elder_id,source,hour_start) DO UPDATE SET detail=excluded.detail", (*key, db.vault.seal(detail, aad(*key))))
    conn.execute("INSERT INTO metric_history_indexed(reading_id) VALUES(?)", (row["id"],))


def metric_history(conn, db, patient, metric, period, end, source):
    duration, bucket_seconds = PERIODS[period]
    start = end - duration
    retained_after = int(current_time()) - 30*86400
    available_start = max(start, retained_after)
    # Bounded work when upgrading an older database. New ingestion is immediate.
    legacy = conn.execute("SELECT r.* FROM readings r LEFT JOIN metric_history_indexed i ON i.reading_id=r.id WHERE r.elder_id=? AND r.source=? AND r.captured_at>=? AND r.captured_at<? AND i.reading_id IS NULL ORDER BY r.id LIMIT ?", (patient, source, available_start, end, BACKFILL_LIMIT)).fetchall()
    for row in legacy:
        index_reading(conn, db, row)
    remaining = conn.execute("SELECT COUNT(*) FROM readings r LEFT JOIN metric_history_indexed i ON i.reading_id=r.id WHERE r.elder_id=? AND r.source=? AND r.captured_at>=? AND r.captured_at<? AND i.reading_id IS NULL", (patient, source, available_start, end)).fetchone()[0]
    first_bucket = start // bucket_seconds * bucket_seconds
    buckets = {at: empty_stat() for at in range(first_bucket, end, bucket_seconds)}
    full_start = ((available_start + 3599)//3600)*3600
    full_end = end//3600*3600
    for row in conn.execute("SELECT hour_start,detail FROM metric_history_rollups WHERE elder_id=? AND source=? AND hour_start>=? AND hour_start<? ORDER BY hour_start", (patient, source, full_start, full_end)):
        detail = db.vault.open(row["detail"], aad(patient, source, row["hour_start"]))
        merge(buckets[row["hour_start"]//bucket_seconds*bucket_seconds], detail[metric])
    # Read both partial edge hours exactly, including unindexed legacy rows.
    edge_count = conn.execute("SELECT COUNT(*) FROM readings WHERE elder_id=? AND source=? AND captured_at>=? AND captured_at<? AND (captured_at<? OR captured_at>=?)", (patient, source, available_start, end, full_start, full_end)).fetchone()[0]
    edges = conn.execute("SELECT * FROM readings WHERE elder_id=? AND source=? AND captured_at>=? AND captured_at<? AND (captured_at<? OR captured_at>=?) ORDER BY captured_at,id LIMIT ?", (patient, source, available_start, end, full_start, full_end, EDGE_LIMIT)).fetchall()
    for row in edges:
        value = metric_values(db.vault.open(row["envelope"], f"reading:{row['id']}")["payload"])[metric]
        add(buckets[row["captured_at"]//bucket_seconds*bucket_seconds], value, row["captured_at"])
    total = empty_stat()
    points = []
    for at, stat in buckets.items():
        merge(total, stat)
        left, right = max(start, at), min(end, at+bucket_seconds)
        points.append({"start": stamp(left), "end": stamp(right), "at": stamp((left+right)//2),
                       "value": stat["sum"]/stat["count"] if stat["count"] else None,
                       "min": stat["min"], "max": stat["max"], "count": stat["count"]})
    # Unindexed edge readings were already included exactly; subtract them from
    # the missing-index figure before describing actual response coverage.
    missing_full = conn.execute("SELECT COUNT(*) FROM readings r LEFT JOIN metric_history_indexed i ON i.reading_id=r.id WHERE r.elder_id=? AND r.source=? AND r.captured_at>=? AND r.captured_at<? AND i.reading_id IS NULL", (patient, source, full_start, full_end)).fetchone()[0]
    return {"patient_id": patient, "metric": metric, "title": METRICS[metric][0], "unit": METRICS[metric][1],
            "period": period, "source": source, "start": stamp(start), "end": stamp(end),
            "bucket_seconds": bucket_seconds, "aggregation": "mean", "points": points,
            "summary": {"count": total["count"], "average": total["sum"]/total["count"] if total["count"] else None,
                        "min": total["min"], "max": total["max"], "latest": total["latest"],
                        "latest_at": stamp(total["latest_at"]) if total["latest_at"] is not None else None},
            "coverage": {"complete": missing_full == 0 and edge_count <= EDGE_LIMIT,
                         "unindexed_readings": remaining, "omitted_readings": missing_full + max(0, edge_count-EDGE_LIMIT),
                         "invalid_readings": total["invalid"], "retained_after": stamp(retained_after)},
            "limitations": "Sensor diagnostics, not a diagnosis. Surface temperature is not core body temperature; light is uncalibrated ADC and movement is acceleration magnitude including gravity."}
