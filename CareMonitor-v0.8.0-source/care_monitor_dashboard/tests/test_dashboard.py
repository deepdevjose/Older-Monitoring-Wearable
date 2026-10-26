"""Only isolated temporary databases are changed by this test suite."""
from datetime import datetime, timedelta, timezone
import csv
import io
import json
import sqlite3

from fastapi.testclient import TestClient
import pytest

from care_monitor_dashboard.app import create_app
from care_monitor_dashboard.store import Store

NOW = datetime(2026, 10, 23, 4, 30, tzinfo=timezone.utc)
ORIGIN = "http://127.0.0.1:8001"


def valid_payload():
    return {"phone_received_at": NOW.isoformat(), "api_key": "DO-NOT-LEAK-SECRET",
            "patient_id": "PRIVATE-PATIENT", "microphone": {"raw": 999},
            "max30102": {"online": True, "status": "ok", "finger_detected": True,
                         "heart_rate_valid": True, "spo2_valid": True, "heart_rate_bpm": 76,
                         "spo2_percent": 98, "red_raw": 1200, "ir_raw": 1800},
            "gy906": {"online": True, "object_temp_c": 33.5, "ambient_temp_c": 24.2},
            "mpu6050": {"online": True, "accel_g": {"x": -0.1, "y": 0.2, "z": 1},
                        "gyro_dps": {"x": 0, "y": 0, "z": 0}},
            "photoresistor": {"adc_raw": 1600, "percent": 39.07}}


def append(db, device="elder-demo-01", payload=None, received_at=None):
    with sqlite3.connect(db) as conn:
        cur = conn.execute("""INSERT INTO cm_readings
            (device_id,patient_id,received_at,sensor_timestamp,uptime_ms,payload_json)
            VALUES (?, ?, ?, ?, ?, ?)""", (device, "PRIVATE-PATIENT", received_at or NOW.isoformat(),
                                          NOW.isoformat(), 5000, json.dumps(payload if payload is not None else valid_payload())))
        return cur.lastrowid


@pytest.fixture
def db(tmp_path):
    path = tmp_path / "sensor.db"
    with sqlite3.connect(path) as conn:
        conn.execute("""CREATE TABLE cm_readings (
            id INTEGER PRIMARY KEY AUTOINCREMENT, device_id TEXT NOT NULL,
            patient_id TEXT NOT NULL, received_at TEXT NOT NULL, sensor_timestamp TEXT,
            uptime_ms INTEGER, boot_nonce TEXT, sample_id TEXT, payload_json TEXT NOT NULL)""")
        conn.execute("CREATE TABLE cm_credentials (digest TEXT)")
        conn.execute("INSERT INTO cm_credentials VALUES ('PRIVATE-CREDENTIAL-DIGEST')")
    append(path)
    return path


@pytest.fixture
def client(db):
    with TestClient(create_app(db, clock=lambda: NOW), base_url=ORIGIN, client=("127.0.0.1", 41000)) as session:
        yield session


def mutation_headers(client):
    return {"Origin": ORIGIN, "X-CSRF-Token": client.get("/api/bootstrap").json()["csrf_token"]}


def start(client, **changes):
    body = {"name": "Calibration run", "participant_code": "P01", "device_id": "elder-demo-01",
            "firmware_version": "0.7.3", "placement": "left fingertip"}
    body.update(changes)
    return client.post("/api/sessions", json=body, headers=mutation_headers(client))


def test_reads_current_commits_without_cache_or_patient_payload(client, db):
    first = client.get("/api/snapshot")
    assert first.status_code == 200
    data = first.json()
    assert data["total_records"] == 1
    assert data["latest"]["metrics"]["heart_rate_bpm"]["value"] == 76
    append(db)
    assert client.get("/api/snapshot").json()["total_records"] == 2
    assert client.get("/api/status").json()["latest_record_id"] == 2
    for endpoint in ("/api/snapshot", "/api/records", "/api/devices", "/api/sessions"):
        text = client.get(endpoint).text
        assert all(secret not in text for secret in ("DO-NOT-LEAK", "PRIVATE-PATIENT", "PRIVATE-CREDENTIAL", "microphone", "payload_json"))
    assert first.headers["cache-control"] == "no-store"
    assert "frame-ancestors 'none'" in first.headers["content-security-policy"]


@pytest.mark.parametrize("change", [
    {"status": "warming_up"}, {"finger_detected": False}, {"finger_detected": "true"},
    {"online": False}, {"heart_rate_valid": False}, {"heart_rate_valid": 1},
    {"heart_rate_bpm": None}, {"heart_rate_bpm": "76"}, {"heart_rate_bpm": -1},
])
def test_optical_gate_is_strict_even_with_saved_valid_flag(client, db, change):
    payload = valid_payload()
    payload["max30102"].update(change)
    append(db, payload=payload)
    metric = client.get("/api/snapshot").json()["latest"]["metrics"]["heart_rate_bpm"]
    assert metric["value"] is None and metric["valid"] is False
    if change.get("heart_rate_bpm", "unchanged") == "unchanged":
        assert metric["raw_value"] == 76


def test_completed_partial_window_keeps_independently_valid_channel(client, db):
    payload = valid_payload()
    payload["max30102"].update(status="partial", spo2_valid=False)
    append(db, payload=payload)
    latest = client.get("/api/snapshot").json()["latest"]
    assert latest["flags"]["optical_status"] == "partial"
    assert latest["metrics"]["heart_rate_bpm"]["value"] == 76
    assert latest["metrics"]["spo2_percent"]["value"] is None
    assert latest["metrics"]["accel_z_g"]["value"] == 1
    payload["max30102"].update(heart_rate_valid=False, spo2_valid=True)
    append(db, payload=payload)
    latest = client.get("/api/snapshot").json()["latest"]
    assert latest["metrics"]["heart_rate_bpm"]["value"] is None
    assert latest["metrics"]["spo2_percent"]["value"] == 98


@pytest.mark.parametrize("change", [
    {"sample_age_ms": 500}, {"result_age_ms": 2500},
    {"sample_age_ms": None}, {"result_age_ms": "1"}, {"sample_age_ms": -1},
])
def test_explicit_optical_ages_cannot_republish_stale_results(client, db, change):
    payload = valid_payload()
    payload["max30102"].update(change)
    append(db, payload=payload)
    latest = client.get("/api/snapshot").json()["latest"]
    assert latest["metrics"]["heart_rate_bpm"]["value"] is None
    assert latest["metrics"]["spo2_percent"]["value"] is None
    assert latest["metrics"]["heart_rate_bpm"]["raw_value"] == 76
    assert latest["metrics"]["accel_x_g"]["value"] == -0.1


def test_supported_optical_bounds_and_recent_window(client, db):
    payload = valid_payload()
    payload["max30102"].update(sample_age_ms=499, result_age_ms=2499)
    append(db, payload=payload)
    assert client.get("/api/snapshot").json()["latest"]["metrics"]["heart_rate_bpm"]["value"] == 76
    payload["max30102"].update(heart_rate_bpm=301, spo2_percent=0)
    append(db, payload=payload)
    latest = client.get("/api/snapshot").json()["latest"]
    assert latest["metrics"]["heart_rate_bpm"]["sensor_reason"] == "outside_physical_range"
    assert latest["metrics"]["spo2_percent"]["value"] is None


def test_stale_future_invalid_time_and_empty_payload_never_fake_fresh_data(client, db):
    append(db, received_at=(NOW - timedelta(seconds=11)).isoformat())
    row = client.get("/api/snapshot").json()["latest"]
    assert row["stale"] and row["quality"] == "stale"
    assert row["metrics"]["heart_rate_bpm"]["value"] is None
    assert row["metrics"]["heart_rate_bpm"]["raw_value"] == 76
    assert row["flags"]["heart_rate_valid"] is True
    append(db, received_at=(NOW + timedelta(seconds=30)).isoformat())
    assert client.get("/api/snapshot").json()["latest"]["quality"] == "unknown_time"
    append(db, received_at="untrusted invalid time")
    assert client.get("/api/snapshot").json()["latest"]["server_received_at"] is None
    append(db, payload={})
    latest = client.get("/api/snapshot").json()["latest"]
    assert all(item["value"] is None for item in latest["metrics"].values())
    with sqlite3.connect(db) as conn:
        conn.execute("UPDATE cm_readings SET payload_json='{' WHERE id=(SELECT MAX(id) FROM cm_readings)")
    assert client.get("/api/snapshot").json()["latest"]["quality"] == "invalid_payload"


def test_temperature_movement_physical_range_and_nulls(client, db):
    payload = valid_payload()
    payload["gy906"]["online"] = False
    payload["mpu6050"]["online"] = False
    payload["photoresistor"]["adc_raw"] = 4096
    payload["max30102"]["spo2_percent"] = 101
    append(db, payload=payload)
    values = client.get("/api/snapshot").json()["latest"]["metrics"]
    for key in ("surface_temperature_c", "ambient_temperature_c", "accel_x_g", "gyro_z_dps", "light_adc", "spo2_percent"):
        assert values[key]["value"] is None
    assert values["surface_temperature_c"]["raw_value"] == 33.5


def test_additive_sessions_do_not_assign_old_rows_or_change_history(client, db):
    with sqlite3.connect(db) as conn:
        original = conn.execute("SELECT * FROM cm_readings").fetchall()
        digest = conn.execute("SELECT * FROM cm_credentials").fetchall()
    assert client.get("/api/sessions").json() == {"sessions": []}
    session = start(client).json()
    assert session["start_after_id"] == 1 and session["record_count"] == 0
    assert client.get("/api/snapshot", params={"session_id": session["id"]}).json()["latest"] is None
    with sqlite3.connect(db) as conn:
        assert conn.execute("SELECT * FROM cm_readings").fetchall() == original
        assert conn.execute("SELECT * FROM cm_credentials").fetchall() == digest


def test_session_exact_boundaries_device_scope_idempotent_stop(client, db):
    session = start(client).json()
    first = append(db)
    append(db, device="other-device")
    second = append(db)
    url = f"/api/sessions/{session['id']}/stop"
    stopped = client.post(url, headers=mutation_headers(client)).json()
    assert stopped["end_at_id"] == second and stopped["record_count"] == 2
    append(db)
    repeated = client.post(url, headers=mutation_headers(client)).json()
    assert repeated == stopped
    selected = client.get("/api/records", params={"session_id": session["id"]}).json()
    assert [row["id"] for row in selected["records"]] == [second, first]
    snapshot = client.get("/api/snapshot", params={"session_id": session["id"]}).json()
    assert snapshot["total_records"] == 5 and snapshot["selected_records"] == 2
    assert client.get("/api/snapshot", params={"session_id": session["id"], "device_id": "other-device"}).status_code == 409
    assert start(client).status_code == 201
    assert start(client).status_code == 409


def test_history_keyset_pagination_and_empty_device(client, db):
    for _ in range(4):
        append(db)
    first = client.get("/api/records", params={"limit": 2}).json()
    assert [row["id"] for row in first["records"]] == [5, 4]
    second = client.get("/api/records", params={"limit": 2, "before_id": first["next_before_id"]}).json()
    assert [row["id"] for row in second["records"]] == [3, 2]
    assert client.get("/api/snapshot", params={"device_id": "never-received"}).json()["latest"] is None
    assert client.get("/api/devices").json()["devices"][0]["record_count"] == 5
    assert client.get("/api/records?limit=501").status_code == 422
    assert client.get("/api/records?before_id=999999999999999999999999").status_code == 422
    assert client.get("/api/snapshot?session_id=999999999999999999999999").status_code == 422
    assert client.get("/api/sessions/999999999999999999999999/export.csv").status_code == 422


def test_csv_stable_bounds_units_raw_invalid_values_formula_protection(client, db):
    session = start(client, name="=HYPERLINK(1)", placement="@left").json()
    payload = valid_payload()
    payload["max30102"]["status"] = "warming_up"
    first = append(db, payload=payload)
    second = append(db)
    client.post(f"/api/sessions/{session['id']}/stop", headers=mutation_headers(client))
    append(db)
    response = client.get(f"/api/sessions/{session['id']}/export.csv?limit=1")
    assert response.status_code == 200
    assert response.headers["x-export-truncated"] == "true"
    assert int(response.headers["x-export-snapshot-max-id"]) == second
    rows = list(csv.DictReader(io.StringIO(response.content.decode("utf-8-sig"))))
    assert len(rows) == 1 and int(rows[0]["record_id"]) == first
    row = rows[0]
    assert row["session_name"].startswith("'=") and row["placement"].startswith("'@")
    assert row["heart_rate_bpm_raw"] == "76" and row["heart_rate_bpm_value"] == ""
    assert row["heart_rate_valid"] == "True" and row["heart_rate_bpm_valid_at_export"] == "False"
    assert row["heart_rate_bpm_unit"] == "bpm" and row["surface_temperature_c_unit"] == "degC"
    assert row["accel_x_g_raw"] == "-0.1"
    assert all(text not in response.text for text in ("microphone", "api_key", "PRIVATE-PATIENT", "DO-NOT-LEAK", "payload_json"))
    assert client.get(f"/api/export.csv?session_id={session['id']}").status_code == 200
    assert client.get("/api/export.csv").status_code == 422
    assert client.get(f"/api/sessions/{session['id']}/export.csv?limit=20001").status_code == 422


def test_session_metadata_validation_does_not_echo_body_or_secret(client):
    assert start(client, participant_code="Real Person").status_code == 422
    assert start(client, placement="x\nline").status_code == 422
    assert start(client, name="").status_code == 422
    assert start(client, device_id="unknown-device").status_code == 409
    response = start(client, api_key="SECRET-BODY-NOT-ECHOED")
    assert response.status_code == 422 and "SECRET-BODY" not in response.text
    assert client.post("/api/sessions", content="x" * 5000, headers={**mutation_headers(client), "Content-Type": "application/json"}).status_code == 413
    assert client.get("/api/sessions/999/export.csv").status_code == 404


def test_loopback_host_origin_csrf_and_security_headers(db, client):
    with TestClient(create_app(db, clock=lambda: NOW), base_url=ORIGIN, client=("10.0.0.8", 41000)) as remote:
        assert remote.get("/api/snapshot").status_code == 403
    assert client.get("/api/snapshot", headers={"Host": "evil.test:8001"}).status_code == 403
    assert client.get("/api/snapshot", headers={"Host": "127.0.0.1:9000"}).status_code == 403
    assert client.get("/api/snapshot", headers={"Origin": "https://evil.test"}).status_code == 403
    assert client.get("/api/snapshot", headers={"Host": "127.0.0.1:8001@evil.test:8001"}).status_code == 403
    assert client.post("/api/sessions", json={}).status_code == 403
    assert client.post("/api/sessions", json={}, headers={"Origin": ORIGIN}).status_code == 403
    assert client.post("/api/sessions", json={}, headers={"X-CSRF-Token": mutation_headers(client)["X-CSRF-Token"]}).status_code == 403
    assert client.options("/api/sessions", headers={"Origin": "https://evil.test"}).status_code == 403
    health = client.get("/api/health")
    assert health.json()["service"] == "care-monitor-test-dashboard"
    assert "access-control-allow-origin" not in health.headers
    assert health.headers["x-content-type-options"] == "nosniff"


def test_missing_database_is_not_created_and_wrong_schema_not_mutated(tmp_path):
    path = tmp_path / "missing.db"
    with pytest.raises(FileNotFoundError):
        Store(path).initialize()
    assert not path.exists()
    with sqlite3.connect(path) as conn:
        conn.execute("CREATE TABLE other_data (value TEXT)")
    with pytest.raises(ValueError):
        Store(path).initialize()
    with sqlite3.connect(path) as conn:
        assert conn.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall() == [("other_data",)]


@pytest.mark.parametrize("bad_status", [[], {}, 1, True, None])
def test_malformed_nested_status_is_safe_and_does_not_expose_arbitrary_text(client, db, bad_status):
    payload = valid_payload()
    payload["max30102"]["status"] = bad_status
    payload["phone_received_at"] = "9999-12-31T23:59:59-23:59"
    append(db, payload=payload)
    for endpoint in ("/api/snapshot", "/api/records"):
        response = client.get(endpoint)
        assert response.status_code == 200
    row = client.get("/api/snapshot").json()["latest"]
    assert row["flags"]["optical_status"] == "unknown"
    assert row["phone_received_at"] is None
    assert row["metrics"]["heart_rate_bpm"]["value"] is None


def test_delayed_phone_upload_not_live_or_mislabelled_as_new_session(client, db):
    session = start(client).json()
    payload = valid_payload()
    payload["phone_received_at"] = (NOW - timedelta(days=1)).isoformat()
    append(db, payload=payload)
    row = client.get("/api/snapshot").json()["latest"]
    assert row["server_age_seconds"] == 0
    assert row["delayed_upload"] and row["upload_delay_seconds"] == 86400
    assert row["stale"] and row["metrics"]["heart_rate_bpm"]["value"] is None
    assert row["metrics"]["heart_rate_bpm"]["sensor_valid"] is True
    selected = client.get("/api/records", params={"session_id": session["id"]}).json()["records"][0]
    assert selected["session_sample_alignment"] == "before_session"
    assert selected["metrics"]["heart_rate_bpm"]["session_eligible"] is False
    exported = client.get(f"/api/sessions/{session['id']}/export.csv")
    csv_row = list(csv.DictReader(io.StringIO(exported.content.decode("utf-8-sig"))))[0]
    assert csv_row["heart_rate_bpm_raw"] == "76"
    assert csv_row["heart_rate_bpm_sensor_valid"] == "True"
    assert csv_row["heart_rate_bpm_session_eligible"] == "False"
    assert csv_row["session_sample_alignment"] == "before_session"


def test_historical_quality_is_independent_of_current_freshness(client, db):
    append(db, received_at=(NOW - timedelta(days=1)).isoformat())
    row = client.get("/api/snapshot").json()["latest"]
    metric = row["metrics"]["heart_rate_bpm"]
    assert metric["valid"] is False and metric["reason"] == "stale"
    assert metric["sensor_valid"] is True and metric["sensor_reason"] == "ok"
    assert metric["session_eligible"] is True
