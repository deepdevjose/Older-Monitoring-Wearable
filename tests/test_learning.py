"""Isolated end-to-end learning records: model output, persistence, access and provenance."""
import csv
import hashlib
import json
import time
from pathlib import Path
import uuid

import pytest
from fastapi.testclient import TestClient

from caremonitor.api import create_app
from caremonitor.config import Settings
from caremonitor.learning import DATASET_SHA256, EXAMPLES


@pytest.fixture
def workspace(tmp_path):
    settings = Settings(data_dir=tmp_path, enforce_https=False, scheduler_interval_seconds=3600)
    settings.initialize_key()
    app = create_app(settings)
    with TestClient(app) as client:
        users = []
        for name, role in [("older", "elder"), ("caregiver", "caregiver"), ("unrelated", "elder")]:
            response = client.post("/api/v1/auth/register", json={"email": name+"@example.test",
                "password": "Fictional learning account 3748", "display_name": name,
                "role": role, "educational_ack": True})
            assert response.status_code == 201
            data = response.json()
            users.append((data["user"]["id"], {"Authorization": "Bearer "+data["access_token"]}))
        yield client, app, users


def submission(index=0):
    return {"sample_id": str(uuid.uuid4()), "source": "synthetic", "features": EXAMPLES[index]["features"].copy()}


def test_examples_are_traceable_to_csv_and_original_labels():
    path = Path(__file__).resolve().parents[1]/"datasets/synthetic_health.csv"
    assert hashlib.sha256(path.read_bytes()).hexdigest() == DATASET_SHA256
    with path.open(newline="") as source:
        rows = list(csv.DictReader(source))
    for example in EXAMPLES:
        row = rows[example["csv_row"]-1]
        assert example["features"] == {"heart_rate_bpm": float(row["Heart Rate (bpm)"]),
            "spo2_percent": float(row["SpO2 Level (%)"]), "body_temp_c": float(row["Body Temperature (°C)"]), "sample_age_seconds": 0.0}
        count = sum(row[key] == "ABNORMAL" for key in ["Heart Rate Alert", "SpO2 Level Alert", "Temperature Alert"])
        assert example["label"] == ["Normal", "Warning", "Critical"][min(count, 2)]


def test_model_results_are_encrypted_persisted_and_separate_from_device_data(workspace):
    client, app, users = workspace
    patient, headers = users[0]
    path = f"/api/v1/patients/{patient}/learning/readings"
    before = client.get(path, headers=headers)
    assert before.status_code == 200 and before.json()["items"] == []
    assert before.json()["can_add"] is True
    for index in range(3):
        response = client.post(path, headers=headers, json=submission(index))
        assert response.status_code == 201, response.text
        result = response.json()
        assert result["classification"] == EXAMPLES[index]["label"]
        assert 0 <= result["confidence"] <= 1
        assert result["source"] == "synthetic" and result["educational_only"] is True
        assert result["model_id"] == "synthetic-health-v1"
    items = client.get(path, headers=headers).json()["items"]
    assert len(items) == 3
    with app.state.db.connection() as conn:
        assert conn.execute("SELECT COUNT(*) FROM readings").fetchone()[0] == 0
        assert conn.execute("SELECT COUNT(*) FROM alerts").fetchone()[0] == 0
        encrypted = conn.execute("SELECT detail FROM learning_records LIMIT 1").fetchone()[0]
        assert "heart_rate_bpm" not in encrypted and "classification" not in encrypted


def test_idempotent_retry_and_conflicting_sample_are_not_duplicated(workspace):
    client, _, users = workspace
    patient, headers = users[0]
    path = f"/api/v1/patients/{patient}/learning/readings"
    body = submission()
    first = client.post(path, headers=headers, json=body)
    again = client.post(path, headers=headers, json=body)
    assert first.status_code == again.status_code == 201
    assert first.json() == again.json()
    body["features"]["heart_rate_bpm"] = 80.0
    assert client.post(path, headers=headers, json=body).status_code == 409
    assert len(client.get(path, headers=headers).json()["items"]) == 1


def test_authorized_caregiver_read_only_and_revocation(workspace):
    client, app, users = workspace
    patient, elder = users[0]
    caregiver_id, caregiver = users[1]
    _, unrelated = users[2]
    path = f"/api/v1/patients/{patient}/learning/readings"
    assert client.get(path).status_code == 401
    assert client.get(path, headers=unrelated).status_code == 404
    assert client.get(path, headers=caregiver).status_code == 404
    invitation = client.post("/api/v1/invitations", headers=elder).json()
    request = client.post("/api/v1/link-requests", headers=caregiver, json={"code": invitation["code"]}).json()
    assert client.post("/api/v1/link-requests/"+request["id"]+"/approve", headers=elder).status_code == 200
    assert client.post(path, headers=elder, json=submission()).status_code == 201
    shared = client.get(path, headers=caregiver)
    assert shared.status_code == 200 and len(shared.json()["items"]) == 1
    assert shared.json()["can_add"] is False
    assert client.post(path, headers=caregiver, json=submission()).status_code == 404
    with app.state.db.transaction() as conn:
        conn.execute("DELETE FROM memberships WHERE elder_id=? AND caregiver_id=?", (patient, caregiver_id))
    assert client.get(path, headers=caregiver).status_code == 404


@pytest.mark.parametrize("mutation", ["device", "missing", "stale", "unsupported", "bool", "extra"])
def test_invalid_samples_never_store_results(workspace, mutation):
    client, app, users = workspace
    patient, headers = users[0]
    body = submission()
    if mutation == "device": body["source"] = "device_diagnostic"
    if mutation == "missing": del body["features"]["body_temp_c"]
    if mutation == "stale": body["features"]["sample_age_seconds"] = 11.0
    if mutation == "unsupported": body["features"]["spo2_percent"] = 101.0
    if mutation == "bool": body["features"]["heart_rate_bpm"] = True
    if mutation == "extra": body["features"]["surface_temp_c"] = 36.5
    response = client.post(f"/api/v1/patients/{patient}/learning/readings", headers=headers, json=body)
    assert response.status_code == 422, response.text
    with app.state.db.connection() as conn:
        assert conn.execute("SELECT COUNT(*) FROM learning_records").fetchone()[0] == 0


def test_storage_survives_server_restart(workspace):
    client, app, users = workspace
    patient, headers = users[0]
    path = f"/api/v1/patients/{patient}/learning/readings"
    saved = client.post(path, headers=headers, json=submission()).json()
    with TestClient(create_app(app.state.db.settings)) as reopened:
        response = reopened.get(path, headers=headers)
        assert response.status_code == 200
        assert response.json()["items"] == [saved]


def test_no_numeric_nan_bypass_or_unbounded_history(workspace):
    client, app, users = workspace
    patient, headers = users[0]
    path = f"/api/v1/patients/{patient}/learning/readings"
    body = submission()
    raw = json.dumps(body).replace('68.0', 'NaN')
    assert client.post(path, headers={**headers, "Content-Type": "application/json"}, content=raw).status_code == 400
    assert client.get(path+"?limit=101", headers=headers).status_code == 422


def test_expired_exercises_are_hidden_even_before_scheduled_cleanup(workspace):
    client, app, users = workspace
    patient, headers = users[0]
    path = f"/api/v1/patients/{patient}/learning/readings"
    body = submission()
    assert client.post(path, headers=headers, json=body).status_code == 201
    with app.state.db.transaction() as conn:
        conn.execute("UPDATE learning_records SET created_at=?", (int(time.time())-31*86400,))
    assert client.get(path, headers=headers).json()["items"] == []
    assert client.post(path, headers=headers, json=body).status_code == 409


def test_latest_result_uses_insertion_order_when_exercises_share_a_second(workspace, monkeypatch):
    client, _, users = workspace
    patient, headers = users[0]
    path = f"/api/v1/patients/{patient}/learning/readings"
    bodies = [submission(index) for index in range(3)]
    # Reverse lexical UUID order deliberately: random identifiers are not time.
    record_ids = iter([uuid.UUID(hex=char*32) for char in ["f", "a", "1"]])
    now = int(time.time())
    monkeypatch.setattr("caremonitor.learning.time.time", lambda: now)
    monkeypatch.setattr("caremonitor.learning.uuid.uuid4", lambda: next(record_ids))
    saved = []
    for body in bodies:
        response = client.post(path, headers=headers, json=body)
        assert response.status_code == 201, response.text
        saved.append(response.json()["id"])
    history = client.get(path, headers=headers).json()["items"]
    assert [item["id"] for item in history] == list(reversed(saved))
    assert history[0]["classification"] == "Critical"
