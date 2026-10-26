"""Read committed sensor records; add session boundaries without changing payloads."""
from __future__ import annotations

from contextlib import contextmanager
from datetime import datetime, timezone
import csv
import io
import json
import math
from pathlib import Path
import sqlite3
from typing import Callable

DEFAULT_DATABASE = Path(r"C:\Users\29682\AppData\Local\Packages\OpenAI.Codex_2p2nqsd0c76g0\LocalCache\Local\CareMonitor\server-v0.7.0\care_monitor.db")
STALE_SECONDS = 10
METRIC_UNITS = {
    "heart_rate_bpm": "bpm", "spo2_percent": "%", "surface_temperature_c": "degC",
    "ambient_temperature_c": "degC", "accel_x_g": "g", "accel_y_g": "g", "accel_z_g": "g",
    "gyro_x_dps": "deg/s", "gyro_y_dps": "deg/s", "gyro_z_dps": "deg/s",
    "light_adc": "ADC counts", "light_percent": "%", "red_raw": "counts", "ir_raw": "counts",
}


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def timestamp(value):
    if not isinstance(value, str) or len(value) > 64:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return iso(parsed) if parsed.tzinfo is not None else None
    except (ValueError, OverflowError, OSError):
        return None


def number(value):
    try:
        return value if isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) else None
    except OverflowError:
        return None


def boolean(value):
    return value if isinstance(value, bool) else None


def age_at(value, now):
    stamp = timestamp(value)
    if stamp is None:
        return None
    age = (now - datetime.fromisoformat(stamp.replace("Z", "+00:00"))).total_seconds()
    # A future server timestamp is not evidence of a fresh reading.
    return round(age, 3) if age >= -2 else None


def process_record(row, now: datetime, stale_seconds=STALE_SECONDS, session=None):
    """An explicit allow-list: never return full payloads, patient IDs or credentials."""
    invalid_payload = False
    try:
        data = json.loads(row["payload_json"])
        if not isinstance(data, dict):
            raise ValueError("Not an object")
    except (ValueError, TypeError, RecursionError):
        data, invalid_payload = {}, True
    block = lambda key: data.get(key) if isinstance(data.get(key), dict) else {}
    optical, temp, motion, light, buzzer = (block(key) for key in ("max30102", "gy906", "mpu6050", "photoresistor", "buzzer"))
    server_age = age_at(row["received_at"], now)
    sensor_stamp = timestamp(row["sensor_timestamp"])
    phone_stamp = timestamp(data.get("phone_received_at"))
    measurement_stamp = sensor_stamp or phone_stamp
    measurement_age = age_at(measurement_stamp, now)
    phone_age = age_at(phone_stamp, now) if phone_stamp else None
    age = max(server_age, measurement_age, phone_age if phone_age is not None else measurement_age) if server_age is not None and measurement_age is not None else None
    stale = age is None or age > stale_seconds
    upload_delay = None
    if phone_stamp and timestamp(row["received_at"]):
        delay = (datetime.fromisoformat(timestamp(row["received_at"]).replace("Z", "+00:00")) - datetime.fromisoformat(phone_stamp.replace("Z", "+00:00"))).total_seconds()
        upload_delay = round(delay, 3) if delay >= -2 else None
    alignment = "not_selected"
    if session is not None:
        measurement_time = datetime.fromisoformat(measurement_stamp.replace("Z", "+00:00")) if measurement_stamp else None
        phone_time = datetime.fromisoformat(phone_stamp.replace("Z", "+00:00")) if phone_stamp else None
        # An older phone receipt cannot be relabelled as a newly started test.
        if phone_time is not None and measurement_time is not None:
            measurement_time = min(measurement_time, phone_time)
        start_time = datetime.fromisoformat(session["started_at"].replace("Z", "+00:00"))
        stop_time = datetime.fromisoformat(session["stopped_at"].replace("Z", "+00:00")) if session["stopped_at"] else None
        alignment = "unknown_time" if measurement_time is None else "before_session" if measurement_time < start_time else "after_session" if stop_time and measurement_time > stop_time else "inside_session"
    status = optical.get("status") if isinstance(optical.get("status"), str) else "unknown"
    allowed_statuses = {"ok", "partial", "poor_signal", "low_quality", "waiting", "no_finger", "warming_up", "read_error", "offline", "not_found", "initializing", "buffering"}
    safe_status = status if status in allowed_statuses else "unknown"
    # Completed windows may contain one usable channel. A warm-up flag never
    # authorizes reuse of an old estimate, even when an old valid bit remains.
    ready = status in {"ok", "partial"} and optical.get("finger_detected") is True and optical.get("online") is not False
    for age_field, limit_ms in (("sample_age_ms", 500), ("result_age_ms", 2500)):
        if age_field in optical:
            estimate_age = number(optical[age_field])
            ready = ready and estimate_age is not None and 0 <= estimate_age < limit_ms
    metrics = {}

    def add(name, raw, valid=True, reason="invalid", low=None, high=None):
        raw = number(raw)
        if raw is None:
            valid, reason = False, "missing_or_non_numeric"
        elif (low is not None and raw < low) or (high is not None and raw > high):
            valid, reason = False, "outside_physical_range"
        sensor_valid = bool(valid) and not invalid_payload
        sensor_reason = "ok" if sensor_valid else "invalid_payload" if invalid_payload else reason
        if stale:
            valid, reason = False, "stale" if age is not None else "unknown_time"
        if invalid_payload:
            valid, reason = False, "invalid_payload"
        if alignment in {"before_session", "after_session"}:
            valid, reason = False, alignment
        metrics[name] = {"value": raw if valid else None, "raw_value": raw,
                         "unit": METRIC_UNITS[name], "valid": bool(valid),
                         "sensor_valid": sensor_valid, "sensor_reason": sensor_reason,
                         "session_eligible": alignment in {"not_selected", "inside_session"},
                         "reason": "ok" if valid else reason}

    add("heart_rate_bpm", optical.get("heart_rate_bpm"), ready and optical.get("heart_rate_valid") is True,
        "sensor_not_ready_or_invalid", low=1, high=300)
    add("spo2_percent", optical.get("spo2_percent"), ready and optical.get("spo2_valid") is True,
        "sensor_not_ready_or_invalid", low=1, high=100)
    add("surface_temperature_c", temp.get("object_temp_c"), temp.get("online") is True, "sensor_offline")
    add("ambient_temperature_c", temp.get("ambient_temp_c"), temp.get("online") is True, "sensor_offline")
    for source, suffix, prefix in (("accel_g", "g", "accel"), ("gyro_dps", "dps", "gyro")):
        axes = motion.get(source) if isinstance(motion.get(source), dict) else {}
        for axis in "xyz":
            add(f"{prefix}_{axis}_{suffix}", axes.get(axis), motion.get("online") is True, "sensor_offline")
    add("light_adc", light.get("adc_raw"), light.get("online") is not False, "sensor_offline", low=0, high=4095)
    add("light_percent", light.get("percent"), light.get("online") is not False, "sensor_offline", low=0, high=100)
    for channel in ("red_raw", "ir_raw"):
        add(channel, optical.get(channel), optical.get("online") is not False and status not in {"read_error", "offline"},
            "sensor_offline_or_read_error", low=0)
    return {
        "id": row["id"], "device_id": row["device_id"],
        "server_received_at": timestamp(row["received_at"]),
        "sensor_timestamp": sensor_stamp, "phone_received_at": phone_stamp,
        "measurement_timestamp": measurement_stamp,
        "measurement_time_source": "device" if sensor_stamp else "phone" if phone_stamp else None,
        "server_age_seconds": server_age, "measurement_age_seconds": measurement_age,
        "upload_delay_seconds": upload_delay,
        "delayed_upload": upload_delay is not None and upload_delay > stale_seconds,
        "session_sample_alignment": alignment,
        "uptime_ms": number(row["uptime_ms"]), "age_seconds": age, "stale": stale,
        "quality": "invalid_payload" if invalid_payload else "unknown_time" if age is None else "stale" if stale else "fresh",
        "metrics": metrics,
        "flags": {"optical_status": safe_status, "finger_detected": boolean(optical.get("finger_detected")),
                  "heart_rate_valid": boolean(optical.get("heart_rate_valid")), "spo2_valid": boolean(optical.get("spo2_valid")),
                  "optical_online": boolean(optical.get("online")), "temperature_online": boolean(temp.get("online")),
                  "movement_online": boolean(motion.get("online")),
                  "buzzer_pin": buzzer.get("pin") if isinstance(buzzer.get("pin"), int) and not isinstance(buzzer.get("pin"), bool) and 0 <= buzzer["pin"] <= 39 else None,
                  "buzzer_active": boolean(buzzer.get("active"))},
    }


class Store:
    def __init__(self, db_path: Path | str, clock: Callable = utc_now, stale_seconds=STALE_SECONDS):
        self.path = Path(db_path).expanduser().resolve()
        self.clock = clock
        self.stale_seconds = stale_seconds

    @contextmanager
    def connection(self, write=False):
        if not self.path.is_file():
            raise FileNotFoundError("Existing sensor database was not found; start the receiving server first.")
        uri = self.path.as_uri() + ("?mode=rw" if write else "?mode=ro")
        conn = sqlite3.connect(uri, uri=True, timeout=5, isolation_level=None)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA busy_timeout=5000")
        try:
            conn.execute("BEGIN IMMEDIATE" if write else "BEGIN")
            yield conn
            conn.commit()
        except BaseException:
            conn.rollback()
            raise
        finally:
            conn.close()

    def initialize(self):
        with self.connection(write=True) as conn:
            if not conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='cm_readings'").fetchone():
                raise ValueError("The selected database does not contain the receiving server's cm_readings table.")
            conn.execute("""CREATE TABLE IF NOT EXISTS cm_test_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
                participant_code TEXT NOT NULL, device_id TEXT NOT NULL,
                firmware_version TEXT NOT NULL, placement TEXT NOT NULL,
                started_at TEXT NOT NULL, stopped_at TEXT,
                start_after_id INTEGER NOT NULL, end_at_id INTEGER,
                CHECK ((stopped_at IS NULL AND end_at_id IS NULL)
                    OR (stopped_at IS NOT NULL AND end_at_id IS NOT NULL)))""")
            conn.execute("""CREATE UNIQUE INDEX IF NOT EXISTS cm_test_sessions_active_device
                ON cm_test_sessions(device_id) WHERE stopped_at IS NULL""")

    @staticmethod
    def _session(conn, session_id):
        row = conn.execute("SELECT * FROM cm_test_sessions WHERE id=?", (session_id,)).fetchone()
        if row is None:
            raise LookupError("Test session not found.")
        return row

    @staticmethod
    def _where(conn, device_id=None, session_id=None, ceiling=None):
        clauses, params = [], []
        if session_id is not None:
            session = Store._session(conn, session_id)
            if device_id is not None and device_id != session["device_id"]:
                raise ValueError("Selected device does not match the session device.")
            clauses.extend(["device_id=?", "id>?"])
            params.extend([session["device_id"], session["start_after_id"]])
            if session["end_at_id"] is not None:
                clauses.append("id<=?")
                params.append(session["end_at_id"])
        elif device_id is not None:
            clauses.append("device_id=?")
            params.append(device_id)
        if ceiling is not None:
            clauses.append("id<=?")
            params.append(ceiling)
        return " AND ".join(clauses) if clauses else "1=1", params

    @staticmethod
    def _session_dict(conn, row):
        data = dict(row)
        where, params = Store._where(conn, session_id=row["id"])
        data["record_count"] = conn.execute("SELECT COUNT(*) FROM cm_readings WHERE " + where, params).fetchone()[0]
        data["status"] = "recording" if row["stopped_at"] is None else "stopped"
        return data

    def sessions(self):
        with self.connection() as conn:
            return {"sessions": [self._session_dict(conn, row) for row in conn.execute("SELECT * FROM cm_test_sessions ORDER BY id DESC").fetchall()]}

    def start_session(self, metadata):
        with self.connection(write=True) as conn:
            if not conn.execute("SELECT 1 FROM cm_readings WHERE device_id=? LIMIT 1", (metadata["device_id"],)).fetchone():
                raise ValueError("Receive a reading from this device before starting a test session.")
            if conn.execute("SELECT 1 FROM cm_test_sessions WHERE device_id=? AND stopped_at IS NULL", (metadata["device_id"],)).fetchone():
                raise ValueError("This device already has a recording test session.")
            boundary = conn.execute("SELECT COALESCE(MAX(id),0) FROM cm_readings").fetchone()[0]
            cursor = conn.execute("""INSERT INTO cm_test_sessions
                (name,participant_code,device_id,firmware_version,placement,started_at,start_after_id)
                VALUES (?,?,?,?,?,?,?)""", (*[metadata[key] for key in ("name", "participant_code", "device_id", "firmware_version", "placement")], iso(self.clock()), boundary))
            return self._session_dict(conn, self._session(conn, cursor.lastrowid))

    def stop_session(self, session_id):
        with self.connection(write=True) as conn:
            row = self._session(conn, session_id)
            if row["stopped_at"] is None:
                boundary = conn.execute("SELECT COALESCE(MAX(id),0) FROM cm_readings").fetchone()[0]
                conn.execute("UPDATE cm_test_sessions SET stopped_at=?,end_at_id=? WHERE id=?", (iso(self.clock()), boundary, session_id))
            return self._session_dict(conn, self._session(conn, session_id))

    def devices(self):
        now = self.clock()
        with self.connection() as conn:
            rows = conn.execute("""SELECT device_id,COUNT(*) AS record_count,MAX(id) AS latest_record_id
                FROM cm_readings GROUP BY device_id ORDER BY device_id""").fetchall()
            devices = []
            for row in rows:
                data = dict(row)
                last = conn.execute("SELECT received_at FROM cm_readings WHERE id=?", (row["latest_record_id"],)).fetchone()[0]
                age = age_at(last, now)
                data.update(last_received_at=timestamp(last), age_seconds=age, stale=age is None or age > self.stale_seconds)
                devices.append(data)
            return {"devices": devices}

    def status(self, device_id=None, session_id=None):
        now = self.clock()
        with self.connection() as conn:
            total = conn.execute("SELECT COUNT(*) FROM cm_readings").fetchone()[0]
            where, params = self._where(conn, device_id, session_id)
            selected = conn.execute("SELECT COUNT(*) FROM cm_readings WHERE " + where, params).fetchone()[0]
            row = conn.execute("SELECT * FROM cm_readings WHERE " + where + " ORDER BY id DESC LIMIT 1", params).fetchone()
            effective_device = device_id
            selected_session = None
            if session_id is not None:
                selected_session = self._session(conn, session_id)
                effective_device = selected_session["device_id"]
            active = conn.execute("SELECT * FROM cm_test_sessions WHERE stopped_at IS NULL" + (" AND device_id=?" if effective_device else "") + " ORDER BY id DESC LIMIT 1", (effective_device,) if effective_device else ()).fetchone()
            return {"server_now": iso(now), "total_records": total, "selected_records": selected,
                    "latest_record_id": row["id"] if row else None, "device_id": effective_device, "session_id": session_id,
                    "active_session": self._session_dict(conn, active) if active else None,
                    "latest": process_record(row, now, self.stale_seconds, selected_session) if row else None}

    def records(self, device_id=None, session_id=None, before_id=None, limit=100):
        now = self.clock()
        with self.connection() as conn:
            maximum = conn.execute("SELECT COALESCE(MAX(id),0) FROM cm_readings").fetchone()[0]
            where, params = self._where(conn, device_id, session_id, maximum)
            if before_id is not None:
                where += " AND id<?"
                params.append(before_id)
            rows = conn.execute("SELECT * FROM cm_readings WHERE " + where + " ORDER BY id DESC LIMIT ?", (*params, limit + 1)).fetchall()
            more = len(rows) > limit
            kept = rows[:limit]
            session = self._session(conn, session_id) if session_id is not None else None
            return {"records": [process_record(row, now, self.stale_seconds, session) for row in kept],
                    "next_before_id": kept[-1]["id"] if kept and more else None,
                    "has_more": more, "snapshot_max_id": maximum}

    def export(self, session_id, limit):
        """Snapshot stable ID bounds once; cap memory/CSV size even for an open session."""
        now = self.clock()
        with self.connection() as conn:
            session = self._session_dict(conn, self._session(conn, session_id))
            maximum = conn.execute("SELECT COALESCE(MAX(id),0) FROM cm_readings").fetchone()[0]
            ceiling = session["end_at_id"] if session["end_at_id"] is not None else maximum
            where, params = self._where(conn, session_id=session_id, ceiling=ceiling)
            rows = conn.execute("SELECT * FROM cm_readings WHERE " + where + " ORDER BY id LIMIT ?", (*params, limit + 1)).fetchall()
        columns = ["session_id", "session_name", "participant_code", "device_id", "firmware_version", "placement",
                   "session_started_at", "session_stopped_at", "session_start_after_id", "session_end_at_id", "export_snapshot_max_id",
                   "record_id", "server_received_at", "phone_received_at", "sensor_timestamp", "uptime_ms", "quality_at_export", "age_seconds_at_export",
                   "measurement_timestamp", "measurement_time_source", "measurement_age_seconds_at_export", "server_age_seconds_at_export",
                   "upload_delay_seconds", "delayed_upload", "session_sample_alignment"]
        flags = ["optical_status", "finger_detected", "heart_rate_valid", "spo2_valid", "optical_online", "temperature_online", "movement_online", "buzzer_pin", "buzzer_active"]
        columns += flags
        for name in METRIC_UNITS:
            columns += [name + "_raw", name + "_value", name + "_unit", name + "_valid_at_export", name + "_reason_at_export", name + "_sensor_valid", name + "_sensor_reason", name + "_session_eligible"]
        out = io.StringIO(newline="")
        writer = csv.DictWriter(out, fieldnames=columns)
        writer.writeheader()
        for row in rows[:limit]:
            record = process_record(row, now, self.stale_seconds, session)
            flat = {"session_id": session_id, "session_name": session["name"], "participant_code": session["participant_code"],
                    "device_id": session["device_id"], "firmware_version": session["firmware_version"], "placement": session["placement"],
                    "session_started_at": session["started_at"], "session_stopped_at": session["stopped_at"],
                    "session_start_after_id": session["start_after_id"], "session_end_at_id": session["end_at_id"],
                    "export_snapshot_max_id": ceiling, "record_id": record["id"], "server_received_at": record["server_received_at"],
                    "phone_received_at": record["phone_received_at"], "sensor_timestamp": record["sensor_timestamp"],
                    "uptime_ms": record["uptime_ms"], "quality_at_export": record["quality"], "age_seconds_at_export": record["age_seconds"],
                    "measurement_timestamp": record["measurement_timestamp"], "measurement_time_source": record["measurement_time_source"],
                    "measurement_age_seconds_at_export": record["measurement_age_seconds"], "server_age_seconds_at_export": record["server_age_seconds"],
                    "upload_delay_seconds": record["upload_delay_seconds"], "delayed_upload": record["delayed_upload"],
                    "session_sample_alignment": record["session_sample_alignment"], **record["flags"]}
            for name, metric in record["metrics"].items():
                flat.update({name + "_raw": metric["raw_value"], name + "_value": metric["value"],
                             name + "_unit": metric["unit"], name + "_valid_at_export": metric["valid"], name + "_reason_at_export": metric["reason"],
                             name + "_sensor_valid": metric["sensor_valid"], name + "_sensor_reason": metric["sensor_reason"], name + "_session_eligible": metric["session_eligible"]})
            writer.writerow({key: csv_safe(value) for key, value in flat.items()})
        return out.getvalue().encode("utf-8-sig"), ceiling, len(rows) > limit, min(len(rows), limit)


def csv_safe(value):
    """Keep legitimate negative numbers numeric while escaping spreadsheet formulas in text."""
    if isinstance(value, str) and value.lstrip(" \t\r\n").startswith(("=", "+", "-", "@")):
        return "'" + value
    return value
