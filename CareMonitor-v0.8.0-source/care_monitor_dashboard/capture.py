"""Durable, additive capture of same-frame primary measurements.

The receiving server's original rows are never changed. Eligibility is evaluated
at receipt time, persisted once, and does not change when the CSV is downloaded.
"""
from __future__ import annotations

import csv
from datetime import datetime
import io
import json

from .store import Store, csv_safe, iso, number, timestamp

POLICY_VERSION = 1
MAX_BATCH = 1000
MAX_ACTIVE = 64
MAX_EXPORT = 20000
PRIMARY_UNITS = {"heart_rate_bpm": "bpm", "spo2_percent": "%",
                 "accel_x_g": "g", "accel_y_g": "g", "accel_z_g": "g"}


def parsed_time(value):
    stamp = timestamp(value)
    return datetime.fromisoformat(stamp.replace("Z", "+00:00")) if stamp else None


def evaluate(row, recording, evaluated_at):
    """Return one allow-listed immutable sample, or one stable rejection reason."""
    try:
        data = json.loads(row["payload_json"])
    except (ValueError, TypeError, RecursionError):
        return None, "invalid_payload"
    if not isinstance(data, dict):
        return None, "invalid_payload"
    received = parsed_time(row["received_at"])
    if received is None:
        return None, "unknown_receipt_time"
    if received > evaluated_at:
        return None, "future_receipt_time"
    supplied = [("device", row["sensor_timestamp"]), ("phone", data.get("phone_received_at"))]
    stamps = []
    for source, value in supplied:
        if value is not None:
            stamp = parsed_time(value)
            if stamp is None:
                return None, "unknown_measurement_time"
            stamps.append((source, stamp))
    if not stamps:
        return None, "unknown_measurement_time"
    if any(stamp > received for _, stamp in stamps):
        return None, "future_measurement_time"
    if any((received - stamp).total_seconds() > 10 for _, stamp in stamps):
        return None, "stale_at_receipt"
    start, stop = parsed_time(recording["started_at"]), parsed_time(recording["stopped_at"])
    if received < start or any(stamp < start for _, stamp in stamps):
        return None, "before_recording"
    if stop and (received > stop or any(stamp > stop for _, stamp in stamps)):
        return None, "after_recording"
    optical = data.get("max30102")
    motion = data.get("mpu6050")
    if not isinstance(optical, dict):
        return None, "optical_missing"
    status = optical.get("status")
    if not isinstance(status, str) or status not in {"ok", "partial"}:
        return None, status if isinstance(status, str) and status in {"no_finger", "warming_up", "read_error", "offline", "waiting", "buffering", "initializing"} else "optical_not_ready"
    if optical.get("online") is False:
        return None, "optical_offline"
    if optical.get("finger_detected") is not True:
        return None, "finger_not_detected"
    if optical.get("heart_rate_valid") is not True:
        return None, "heart_rate_invalid"
    if optical.get("spo2_valid") is not True:
        return None, "spo2_invalid"
    for age_field, limit_ms in (("sample_age_ms", 500), ("result_age_ms", 2500)):
        if age_field in optical:
            age = number(optical[age_field])
            if age is None or not 0 <= age < limit_ms:
                return None, "stale_or_invalid_optical_age"
    heart, oxygen = number(optical.get("heart_rate_bpm")), number(optical.get("spo2_percent"))
    if heart is None or not 1 <= heart <= 300:
        return None, "heart_rate_outside_range"
    if oxygen is None or not 1 <= oxygen <= 100:
        return None, "spo2_outside_range"
    if not isinstance(motion, dict) or motion.get("online") is not True:
        return None, "movement_offline_or_missing"
    axes = motion.get("accel_g")
    if not isinstance(axes, dict) or any(number(axes.get(axis)) is None for axis in "xyz"):
        return None, "acceleration_missing_or_non_numeric"
    chosen_source, chosen_time = stamps[0]
    sample = {
        "record_id": row["id"], "device_id": row["device_id"],
        "server_received_at": iso(received), "sensor_timestamp": timestamp(row["sensor_timestamp"]),
        "phone_received_at": timestamp(data.get("phone_received_at")),
        "measurement_timestamp": iso(chosen_time), "measurement_time_source": chosen_source,
        "uptime_ms": number(row["uptime_ms"]), "optical_status": status,
        "finger_detected": True, "heart_rate_valid": True, "spo2_valid": True,
        "optical_online": optical.get("online") if isinstance(optical.get("online"), bool) else None,
        "movement_online": True, "heart_rate_bpm": heart, "spo2_percent": oxygen,
        "sample_age_ms": number(optical.get("sample_age_ms")),
        "result_age_ms": number(optical.get("result_age_ms")),
        **{f"accel_{axis}_g": axes[axis] for axis in "xyz"},
        "eligibility_policy_version": POLICY_VERSION, "valid_at_receipt": True,
        "eligibility_checked_at": iso(evaluated_at),
    }
    return sample, None


class CaptureStore:
    def __init__(self, store: Store):
        self.store = store

    def initialize(self):
        with self.store.connection(write=True) as conn:
            conn.execute("""CREATE TABLE IF NOT EXISTS cm_valid_recordings (
                id INTEGER PRIMARY KEY AUTOINCREMENT, device_id TEXT NOT NULL,
                name TEXT NOT NULL, started_at TEXT NOT NULL, stopped_at TEXT,
                start_after_id INTEGER NOT NULL, end_at_id INTEGER,
                scanned_through_id INTEGER NOT NULL, scanned_count INTEGER NOT NULL DEFAULT 0,
                valid_count INTEGER NOT NULL DEFAULT 0, skipped_count INTEGER NOT NULL DEFAULT 0,
                skip_reasons_json TEXT NOT NULL DEFAULT '{}', policy_version INTEGER NOT NULL,
                CHECK ((stopped_at IS NULL AND end_at_id IS NULL)
                  OR (stopped_at IS NOT NULL AND end_at_id IS NOT NULL)))""")
            conn.execute("""CREATE UNIQUE INDEX IF NOT EXISTS cm_valid_recordings_active_device
                ON cm_valid_recordings(device_id) WHERE stopped_at IS NULL""")
            conn.execute("""CREATE UNIQUE INDEX IF NOT EXISTS cm_valid_recordings_inflight_device
                ON cm_valid_recordings(device_id)
                WHERE stopped_at IS NULL OR scanned_through_id < end_at_id""")
            conn.execute("""CREATE TABLE IF NOT EXISTS cm_valid_recording_rows (
                recording_id INTEGER NOT NULL REFERENCES cm_valid_recordings(id),
                reading_id INTEGER NOT NULL REFERENCES cm_readings(id),
                sample_json TEXT NOT NULL, PRIMARY KEY(recording_id, reading_id))""")

    @staticmethod
    def _recording(conn, recording_id):
        row = conn.execute("SELECT * FROM cm_valid_recordings WHERE id=?", (recording_id,)).fetchone()
        if row is None:
            raise LookupError("Valid-data recording not found.")
        return row

    @staticmethod
    def _public(row):
        data = dict(row)
        data["skip_reasons"] = json.loads(data.pop("skip_reasons_json"))
        data["status"] = ("recording" if row["stopped_at"] is None else
                          "finalizing" if row["scanned_through_id"] < row["end_at_id"] else "stopped")
        return data

    def get(self, recording_id):
        with self.store.connection() as conn:
            return self._public(self._recording(conn, recording_id))

    def list(self, device_id=None, limit=100):
        with self.store.connection() as conn:
            where = " WHERE device_id=?" if device_id else ""
            params = (device_id, limit) if device_id else (limit,)
            rows = conn.execute("SELECT * FROM cm_valid_recordings" + where + " ORDER BY id DESC LIMIT ?", params).fetchall()
            return {"captures": [self._public(row) for row in rows]}

    def start(self, device_id, name=None):
        with self.store.connection(write=True) as conn:
            if not conn.execute("SELECT 1 FROM cm_readings WHERE device_id=? LIMIT 1", (device_id,)).fetchone():
                raise ValueError("Receive a reading from this device before recording valid data.")
            if conn.execute("""SELECT 1 FROM cm_valid_recordings WHERE device_id=?
                AND (stopped_at IS NULL OR scanned_through_id < end_at_id)""", (device_id,)).fetchone():
                raise ValueError("This device already has an active valid-data recording.")
            if conn.execute("""SELECT COUNT(*) FROM cm_valid_recordings
                WHERE stopped_at IS NULL OR scanned_through_id < end_at_id""").fetchone()[0] >= MAX_ACTIVE:
                raise ValueError("Stop an existing valid-data recording before creating another.")
            boundary = conn.execute("SELECT COALESCE(MAX(id),0) FROM cm_readings").fetchone()[0]
            started = iso(self.store.clock())
            name = name or "Valid data " + started
            cur = conn.execute("""INSERT INTO cm_valid_recordings
                (device_id,name,started_at,start_after_id,scanned_through_id,policy_version)
                VALUES (?,?,?,?,?,?)""", (device_id, name, started, boundary, boundary, POLICY_VERSION))
            return self._public(self._recording(conn, cur.lastrowid))

    def _scan(self, conn, recording, ceiling, evaluated_at, batch_size):
        rows = conn.execute("""SELECT * FROM cm_readings WHERE id>? AND id<=?
            ORDER BY id LIMIT ?""", (recording["scanned_through_id"], ceiling, batch_size)).fetchall()
        reasons = json.loads(recording["skip_reasons_json"])
        scanned, valid, skipped = recording["scanned_count"], recording["valid_count"], recording["skipped_count"]
        for row in rows:
            if row["device_id"] != recording["device_id"]:
                continue
            scanned += 1
            sample, reason = evaluate(row, recording, evaluated_at)
            if sample is not None:
                conn.execute("""INSERT INTO cm_valid_recording_rows
                    (recording_id,reading_id,sample_json) VALUES (?,?,?)""",
                    (recording["id"], row["id"], json.dumps(sample, separators=(",", ":"), allow_nan=False)))
                valid += 1
            else:
                skipped += 1
                reasons[reason] = reasons.get(reason, 0) + 1
        cursor = rows[-1]["id"] if len(rows) == batch_size else ceiling
        conn.execute("""UPDATE cm_valid_recordings SET scanned_through_id=?,scanned_count=?,
            valid_count=?,skipped_count=?,skip_reasons_json=? WHERE id=?""",
            (cursor, scanned, valid, skipped, json.dumps(reasons, sort_keys=True), recording["id"]))
        return cursor

    def scan_pending(self, batch_size=MAX_BATCH):
        if not isinstance(batch_size, int) or isinstance(batch_size, bool) or not 1 <= batch_size <= MAX_BATCH:
            raise ValueError("Invalid capture processing batch size.")
        # One bounded transaction per active recording keeps receiver locks short.
        with self.store.connection() as conn:
            ids = [row[0] for row in conn.execute("""SELECT id FROM cm_valid_recordings
                WHERE stopped_at IS NULL OR scanned_through_id < end_at_id
                ORDER BY id LIMIT ?""", (MAX_ACTIVE,))]
        for recording_id in ids:
            with self.store.connection(write=True) as conn:
                recording = self._recording(conn, recording_id)
                if recording["stopped_at"] is not None and recording["scanned_through_id"] >= recording["end_at_id"]:
                    continue
                ceiling = recording["end_at_id"] if recording["end_at_id"] is not None else conn.execute("SELECT COALESCE(MAX(id),0) FROM cm_readings").fetchone()[0]
                if ceiling > recording["scanned_through_id"]:
                    self._scan(conn, recording, ceiling, self.store.clock(), batch_size)

    def stop(self, recording_id):
        # Freeze the end boundary atomically, then process at most one bounded
        # batch. Long backlogs resume through the worker without holding one
        # write transaction for the entire recording. CSV remains unavailable
        # until the persisted cursor reaches the fixed boundary.
        with self.store.connection(write=True) as conn:
            recording = self._recording(conn, recording_id)
            if recording["stopped_at"] is not None and recording["scanned_through_id"] >= recording["end_at_id"]:
                return self._public(recording)
            stopped = self.store.clock()
            if recording["stopped_at"] is None:
                ceiling = conn.execute("SELECT COALESCE(MAX(id),0) FROM cm_readings").fetchone()[0]
                conn.execute("UPDATE cm_valid_recordings SET stopped_at=?,end_at_id=? WHERE id=?",
                             (iso(stopped), ceiling, recording_id))
            recording = self._recording(conn, recording_id)
            if recording["scanned_through_id"] < recording["end_at_id"]:
                self._scan(conn, recording, recording["end_at_id"], stopped, MAX_BATCH)
                recording = self._recording(conn, recording_id)
            return self._public(recording)

    def export(self, recording_id, limit=MAX_EXPORT):
        with self.store.connection() as conn:
            recording = self._recording(conn, recording_id)
            if recording["stopped_at"] is None or recording["scanned_through_id"] < recording["end_at_id"]:
                raise ValueError("Stop recording and wait for finalization before exporting its fixed CSV.")
            rows = conn.execute("""SELECT sample_json FROM cm_valid_recording_rows
                WHERE recording_id=? ORDER BY reading_id LIMIT ?""", (recording_id, limit + 1)).fetchall()
        columns = ["recording_id", "recording_name", "recording_started_at", "recording_stopped_at",
                   "recording_start_after_id", "recording_end_at_id", "record_id", "device_id",
                   "server_received_at", "sensor_timestamp", "phone_received_at", "measurement_timestamp",
                   "measurement_time_source", "uptime_ms", "optical_status", "finger_detected", "heart_rate_valid",
                   "spo2_valid", "optical_online", "movement_online", "eligibility_policy_version",
                   "valid_at_receipt", "eligibility_checked_at", "sample_age_ms", "result_age_ms"]
        for name in PRIMARY_UNITS:
            columns += [name + "_raw", name + "_value", name + "_unit"]
        out = io.StringIO(newline="")
        writer = csv.DictWriter(out, fieldnames=columns)
        writer.writeheader()
        for row in rows[:limit]:
            sample = json.loads(row["sample_json"])
            flat = {key: value for key, value in sample.items() if key in columns}
            flat.update(recording_id=recording_id, recording_name=recording["name"],
                        recording_started_at=recording["started_at"], recording_stopped_at=recording["stopped_at"],
                        recording_start_after_id=recording["start_after_id"], recording_end_at_id=recording["end_at_id"])
            for name, unit in PRIMARY_UNITS.items():
                flat.update({name + "_raw": sample[name], name + "_value": sample[name], name + "_unit": unit})
            writer.writerow({key: csv_safe(value) for key, value in flat.items()})
        return out.getvalue().encode("utf-8-sig"), recording["end_at_id"], len(rows) > limit, min(len(rows), limit)
