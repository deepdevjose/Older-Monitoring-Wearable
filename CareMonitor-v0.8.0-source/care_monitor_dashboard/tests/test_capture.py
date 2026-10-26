"""All capture writes use isolated temporary databases, never a live receiver."""
from datetime import datetime, timedelta, timezone
import csv
import io
import json
import sqlite3
import time

from fastapi.testclient import TestClient
import pytest

from care_monitor_dashboard.app import create_app
from care_monitor_dashboard.capture import CaptureStore
from care_monitor_dashboard.store import Store

NOW = datetime(2026, 10, 23, 6, tzinfo=timezone.utc)
ORIGIN = "http://127.0.0.1:8001"


def payload(at=NOW):
    return {"phone_received_at": at.isoformat(), "patient_id": "PRIVATE-PATIENT",
            "api_key": "PRIVATE-KEY", "max30102": {"online": True, "status": "ok",
            "finger_detected": True, "heart_rate_valid": True, "spo2_valid": True,
            "heart_rate_bpm": 76, "spo2_percent": 98},
            "mpu6050": {"online": True, "accel_g": {"x": -0.2, "y": 0.3, "z": 1.0}}}


def append(db, value=None, at=NOW, sensor_at=None, device="elder-demo-01"):
    with sqlite3.connect(db) as conn:
        cur = conn.execute("""INSERT INTO cm_readings
            (device_id,patient_id,received_at,sensor_timestamp,uptime_ms,payload_json)
            VALUES (?,?,?,?,?,?)""", (device, "PRIVATE-PATIENT", at.isoformat(),
            sensor_at, 5000, json.dumps(value if value is not None else payload(at))))
        return cur.lastrowid


@pytest.fixture
def env(tmp_path):
    db = tmp_path / "capture.db"
    with sqlite3.connect(db) as conn:
        conn.execute("""CREATE TABLE cm_readings (id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id TEXT, patient_id TEXT, received_at TEXT, sensor_timestamp TEXT,
            uptime_ms INTEGER, payload_json TEXT)""")
        conn.execute("CREATE TABLE cm_credentials (secret TEXT)")
        conn.execute("INSERT INTO cm_credentials VALUES ('PRIVATE-CREDENTIAL')")
    append(db)
    current = [NOW]
    app = create_app(db, clock=lambda: current[0], capture_worker=False)
    with TestClient(app, base_url=ORIGIN, client=("127.0.0.1", 41000)) as client:
        headers = {"Origin": ORIGIN, "X-CSRF-Token": client.get("/api/bootstrap").json()["csrf_token"]}
        yield db, app, client, headers, current


def start(env, **kwargs):
    return env[2].post("/api/captures", json={"device_id": "elder-demo-01", **kwargs}, headers=env[3])


def stop(env, recording_id):
    return env[2].post(f"/api/captures/{recording_id}/stop", headers=env[3])


def export(env, recording_id):
    return env[2].get(f"/api/captures/{recording_id}/export.csv")


def test_exact_boundary_same_frame_snapshot_csv_and_original_rows_untouched(env):
    db, app, client, _, current = env
    with sqlite3.connect(db) as conn:
        original = conn.execute("SELECT * FROM cm_readings").fetchall()
    capture = start(env, name="=HYPERLINK(1)").json()
    assert capture["start_after_id"] == 1 and capture["valid_count"] == 0
    assert export(env, capture["id"]).status_code == 409
    first = append(db)
    other = append(db, device="other")
    next_payload = payload()
    next_payload["max30102"]["heart_rate_bpm"] = 82
    next_payload["mpu6050"]["accel_g"]["x"] = 0.8
    second = append(db, next_payload)
    stopped = stop(env, capture["id"]).json()
    assert stopped["scanned_count"] == stopped["valid_count"] == 2
    assert stopped["skipped_count"] == 0 and stopped["end_at_id"] == second
    append(db)
    current[0] += timedelta(days=1)
    response = export(env, capture["id"])
    records = list(csv.DictReader(io.StringIO(response.content.decode("utf-8-sig"))))
    assert [int(row["record_id"]) for row in records] == [first, second]
    assert int(records[1]["heart_rate_bpm_value"]) == 82
    assert float(records[1]["accel_x_g_value"]) == 0.8
    assert records[0]["heart_rate_bpm_value"] == records[0]["heart_rate_bpm_raw"] == "76"
    assert records[0]["heart_rate_bpm_unit"] == "bpm" and records[0]["spo2_percent_unit"] == "%"
    assert records[0]["accel_x_g_unit"] == "g"
    assert records[0]["valid_at_receipt"] == "True"
    assert records[0]["recording_name"].startswith("'=")
    assert stop(env, capture["id"]).json() == stopped
    assert app.state.captures.get(capture["id"]) == stopped
    assert all(secret not in response.text for secret in ("PRIVATE", "api_key", "patient_id", "microphone", "payload_json"))
    with sqlite3.connect(db) as conn:
        assert conn.execute("SELECT * FROM cm_readings WHERE id=1").fetchall() == original
        assert conn.execute("SELECT secret FROM cm_credentials").fetchone()[0] == "PRIVATE-CREDENTIAL"


@pytest.mark.parametrize("target,change,reason", [
    ("max30102", {"status": "no_finger"}, "no_finger"),
    ("max30102", {"status": "warming_up"}, "warming_up"),
    ("max30102", {"status": []}, "optical_not_ready"),
    ("max30102", {"status": {}}, "optical_not_ready"),
    ("max30102", {"finger_detected": 1}, "finger_not_detected"),
    ("max30102", {"heart_rate_valid": 1}, "heart_rate_invalid"),
    ("max30102", {"spo2_valid": False}, "spo2_invalid"),
    ("max30102", {"heart_rate_bpm": 0}, "heart_rate_outside_range"),
    ("max30102", {"heart_rate_bpm": 301}, "heart_rate_outside_range"),
    ("max30102", {"heart_rate_bpm": True}, "heart_rate_outside_range"),
    ("max30102", {"spo2_percent": 101}, "spo2_outside_range"),
    ("max30102", {"spo2_percent": 0}, "spo2_outside_range"),
    ("max30102", {"online": False}, "optical_offline"),
    ("max30102", {"sample_age_ms": 500}, "stale_or_invalid_optical_age"),
    ("max30102", {"result_age_ms": 2500}, "stale_or_invalid_optical_age"),
    ("max30102", {"result_age_ms": None}, "stale_or_invalid_optical_age"),
    ("max30102", {"sample_age_ms": "0"}, "stale_or_invalid_optical_age"),
    ("max30102", {"sample_age_ms": True}, "stale_or_invalid_optical_age"),
    ("max30102", {"result_age_ms": -1}, "stale_or_invalid_optical_age"),
    ("mpu6050", {"online": 1}, "movement_offline_or_missing"),
    ("mpu6050", {"accel_g": {"x": 1, "y": 2}}, "acceleration_missing_or_non_numeric"),
    ("mpu6050", {"accel_g": {"x": float("nan"), "y": 2, "z": 1}}, "acceleration_missing_or_non_numeric"),
])
def test_invalid_primary_channel_never_pairs_with_another_frame(env, target, change, reason):
    capture = start(env).json()
    bad = payload()
    bad[target].update(change)
    append(env[0], bad)
    result = stop(env, capture["id"]).json()
    assert result["valid_count"] == 0 and result["scanned_count"] == result["skipped_count"] == 1
    assert result["skip_reasons"] == {reason: 1}
    assert list(csv.DictReader(io.StringIO(export(env, capture["id"]).content.decode("utf-8-sig")))) == []


def test_partial_status_all_valid_channels_accepted_but_never_imputed(env):
    capture = start(env).json()
    good = payload()
    good["max30102"]["status"] = "partial"
    append(env[0], good)
    bad = payload()
    bad["max30102"].update(status="partial", heart_rate_valid=False)
    append(env[0], bad)
    bad = payload()
    bad["max30102"]["spo2_valid"] = False
    append(env[0], bad)
    result = stop(env, capture["id"]).json()
    assert result["valid_count"] == 1 and result["skipped_count"] == 2


@pytest.mark.parametrize("kind,expected", [
    ("old_phone", "stale_at_receipt"), ("future_phone", "future_measurement_time"),
    ("bad_phone", "unknown_measurement_time"), ("no_time", "unknown_measurement_time"),
    ("before_start", "before_recording"), ("future_receipt", "future_receipt_time"),
    ("old_device", "stale_at_receipt"), ("bad_device", "unknown_measurement_time"),
])
def test_measurement_provenance_not_just_new_database_id(env, kind, expected):
    capture = start(env).json()
    value, sensor, at = payload(), None, NOW
    if kind == "old_phone": value["phone_received_at"] = (NOW - timedelta(seconds=11)).isoformat()
    if kind == "future_phone": value["phone_received_at"] = (NOW + timedelta(seconds=1)).isoformat()
    if kind == "bad_phone": value["phone_received_at"] = "no timezone"
    if kind == "no_time": value.pop("phone_received_at")
    if kind == "before_start": value["phone_received_at"] = (NOW - timedelta(seconds=1)).isoformat()
    if kind == "future_receipt": at = NOW + timedelta(seconds=30)
    if kind == "old_device": sensor = (NOW - timedelta(seconds=11)).isoformat()
    if kind == "bad_device": sensor = "9999-12-31T23:59:59-23:59"
    append(env[0], value, at=at, sensor_at=sensor)
    result = stop(env, capture["id"]).json()
    assert result["valid_count"] == 0 and result["skip_reasons"] == {expected: 1}


def test_timezone_normalization_and_receipt_time_eligibility_survive_restart(env):
    capture = start(env).json()
    value = payload()
    value["phone_received_at"] = "2026-10-23T14:00:00+08:00"
    append(env[0], value, sensor_at=NOW.isoformat())
    env[4][0] += timedelta(hours=1)
    restarted = CaptureStore(Store(env[0], clock=lambda: env[4][0]))
    restarted.initialize()
    restarted.scan_pending()
    assert restarted.get(capture["id"])["valid_count"] == 1
    restarted.scan_pending()
    assert restarted.get(capture["id"])["valid_count"] == 1
    with sqlite3.connect(env[0]) as conn:
        assert conn.execute("SELECT COUNT(*) FROM cm_valid_recording_rows").fetchone()[0] == 1


def test_get_is_read_only_and_worker_bounded_cursor_resumes(env):
    capture = start(env).json()
    for _ in range(5): append(env[0])
    for url in ("/api/captures", f"/api/captures/{capture['id']}"):
        assert env[2].get(url).status_code == 200
    assert env[1].state.captures.get(capture["id"])["scanned_count"] == 0
    env[1].state.captures.scan_pending(batch_size=2)
    assert env[1].state.captures.get(capture["id"])["valid_count"] == 2
    assert env[1].state.captures.get(capture["id"])["scanned_through_id"] == 3
    restarted = CaptureStore(Store(env[0], clock=lambda: NOW))
    restarted.scan_pending(batch_size=2)
    assert restarted.get(capture["id"])["valid_count"] == 4
    assert stop(env, capture["id"]).json()["valid_count"] == 5
    append(env[0])
    restarted.scan_pending()
    assert restarted.get(capture["id"])["valid_count"] == 5


def test_validation_device_scope_csrf_export_limits_and_query_bounds(env):
    client, headers = env[2], env[3]
    assert client.post("/api/captures", json={"device_id": "elder-demo-01"}).status_code == 403
    assert start(env, secret="DO-NOT-ECHO").status_code == 422
    assert start(env, name="\n").status_code == 422
    assert client.post("/api/captures", json={"device_id": "unknown"}, headers=headers).status_code == 409
    capture = start(env).json()
    assert start(env).status_code == 409
    for _ in range(3): append(env[0])
    stop(env, capture["id"])
    response = client.get(f"/api/captures/{capture['id']}/export.csv?limit=2")
    assert response.headers["x-export-truncated"] == "true"
    assert response.headers["x-export-record-count"] == "2"
    assert client.get(f"/api/captures/{capture['id']}/export.csv?limit=20001").status_code == 422
    assert client.get("/api/captures/999").status_code == 404
    assert client.get("/api/captures/9999999999999999999999999").status_code == 422
    assert client.get("/api/captures?limit=101").status_code == 422
    assert client.get("/api/captures?device_id=other").json() == {"captures": []}
    assert start(env).status_code == 201


def test_lifespan_worker_captures_without_get_polling_and_stops_cleanly(env):
    app = create_app(env[0], clock=lambda: NOW)
    with TestClient(app, base_url=ORIGIN, client=("127.0.0.1", 41000)):
        capture = app.state.captures.start("elder-demo-01")
        append(env[0])
        deadline = time.monotonic() + 4
        while app.state.captures.get(capture["id"])["valid_count"] != 1 and time.monotonic() < deadline:
            time.sleep(0.05)
        assert app.state.captures.get(capture["id"])["valid_count"] == 1


def test_long_stop_is_bounded_receiver_can_commit_and_restart_finalizes_fixed_end(env):
    db, app, client, _, current = env
    capture = start(env).json()
    with sqlite3.connect(db) as conn:
        conn.executemany("""INSERT INTO cm_readings
            (device_id,patient_id,received_at,sensor_timestamp,uptime_ms,payload_json)
            VALUES (?,?,?,?,?,?)""", [("elder-demo-01", "PRIVATE-PATIENT", NOW.isoformat(),
                                    None, 5000, json.dumps(payload()))] * 2105)
    result = stop(env, capture["id"]).json()
    assert result["status"] == "finalizing" and result["end_at_id"] == 2106
    assert result["scanned_count"] == result["valid_count"] == 1000
    assert export(env, capture["id"]).status_code == 409
    assert start(env).status_code == 409
    # A second SQLite writer can commit while the capture is finalizing; the
    # stop endpoint has released its write lock instead of draining all rows.
    with sqlite3.connect(db, timeout=0) as receiver:
        receiver.execute("BEGIN IMMEDIATE")
        receiver.execute("""INSERT INTO cm_readings
            (device_id,patient_id,received_at,uptime_ms,payload_json) VALUES (?,?,?,?,?)""",
            ("elder-demo-01", "PRIVATE-PATIENT", NOW.isoformat(), 6000, json.dumps(payload())))
    restarted = CaptureStore(Store(db, clock=lambda: current[0]))
    restarted.initialize()
    restarted.scan_pending()
    partial = restarted.get(capture["id"])
    assert partial["status"] == "finalizing" and partial["valid_count"] == 2000
    append(db)
    restarted.scan_pending()
    finished = restarted.get(capture["id"])
    assert finished["status"] == "stopped" and finished["valid_count"] == 2105
    assert finished["scanned_through_id"] == finished["end_at_id"] == 2106
    assert finished["stopped_at"] == result["stopped_at"]
    assert stop(env, capture["id"]).json() == finished
    response = export(env, capture["id"])
    assert response.headers["x-export-record-count"] == "2105"
    assert response.headers["x-export-snapshot-max-id"] == "2106"
    assert start(env).status_code == 201
