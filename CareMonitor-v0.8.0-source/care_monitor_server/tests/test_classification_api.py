"""Candidate-only endpoint/security checks using temporary SQLite databases."""
from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import shutil
import time

from fastapi.testclient import TestClient
import pytest

from care_monitor_server.app import Limits, MAX_BODY_BYTES, create_app
from care_monitor_server.classification import CandidateClassifier, FEATURES
from care_monitor_server.database import connect, transaction
from care_monitor_server.security import issue, revoke

ARTIFACTS = Path(__file__).resolve().parents[2] / "evidence" / "ml"


@pytest.fixture(autouse=True)
def isolated_environment(monkeypatch):
    for name in ("CM_ALERT_RULES_FILE", "CM_CLASSIFICATION_MODEL_MANIFEST", "CM_CLASSIFICATION_MANIFEST_SHA256"):
        monkeypatch.delenv(name, raising=False)


@pytest.fixture
def model_pack(tmp_path):
    directory = tmp_path / "models"
    shutil.copytree(ARTIFACTS, directory)
    path = directory / "manifest.json"
    return path, hashlib.sha256(path.read_bytes()).hexdigest()


@pytest.fixture
def classifier_client(db, model_pack):
    path, digest = model_pack
    with TestClient(create_app(db, classification_manifest=path, classification_manifest_sha256=digest),
                    base_url="https://testserver") as client:
        assert client.app.state.classifier.status == "ready"
        yield client


def payload(task="heart_rate", source="synthetic_dataset"):
    values = {
        "heart_rate": {"Heart Rate (bpm)": 76},
        "oxygen": {"SpO2 Level (%)": 98},
        "blood_pressure": {"Systolic Blood Pressure (mmHg)": 120, "Diastolic Blood Pressure (mmHg)": 80},
        "body_temperature": {"Body Temperature (°C)": 37},
    }
    return {"device_id": "elder-demo-01", "task": task, "features": values[task], "input_source": source}


@pytest.mark.parametrize("task", list(FEATURES))
def test_real_export_binary_probability_and_provenance(classifier_client, headers, db, model_pack, task):
    response = classifier_client.post("/ml/classify", headers=headers, json=payload(task))
    assert response.status_code == 200
    result = response.json()
    assert result["classification"] == "NORMAL"
    assert 0 <= result["confidence"] <= 1
    assert result["confidence"] == result["class_probabilities"][result["classification"]]
    assert sum(result["class_probabilities"].values()) == pytest.approx(1)
    assert set(result["class_probabilities"]) == {"NORMAL", "ABNORMAL"}
    assert result["model"]["manifest_sha256"] == model_pack[1]
    assert len(result["model"]["dataset_sha256"]) == 64
    assert result["model"]["estimator"] == "DecisionTreeClassifier"
    assert result["three_class_supported"] is False and result["clinical_validation"] is False
    assert result["confidence_calibrated"] is False and result["care_alert_created"] is False
    assert "not medical certainty" in result["confidence_semantics"]
    assert "no-store" in response.headers["cache-control"]
    with connect(db) as conn:
        assert conn.execute("SELECT COUNT(*) FROM cm_readings").fetchone()[0] == 0
        assert conn.execute("SELECT COUNT(*) FROM cm_alerts").fetchone()[0] == 0
        assert json.loads(conn.execute("SELECT policy_json FROM cm_alert_config").fetchone()[0])["enabled"] is False


def test_abnormal_is_binary_not_warning_or_critical(classifier_client, headers):
    body = payload()
    body["features"]["Heart Rate (bpm)"] = 120
    result = classifier_client.post("/ml/classify", headers=headers, json=body)
    assert result.status_code == 200 and result.json()["classification"] == "ABNORMAL"


def test_missing_configuration_is_explicitly_unavailable(client, headers):
    result = client.post("/ml/classify", headers=headers, json=payload())
    assert result.status_code == 503
    assert result.json()["status"] == "disabled"
    assert result.json()["classification"] is result.json()["confidence"] is None


def test_configuration_is_explicit_and_bad_pin_does_not_expose_path(db, headers, model_pack, monkeypatch):
    path, digest = model_pack
    monkeypatch.setenv("CM_CLASSIFICATION_MODEL_MANIFEST", str(path))
    monkeypatch.setenv("CM_CLASSIFICATION_MANIFEST_SHA256", digest)
    with TestClient(create_app(db), base_url="https://testserver") as app:
        assert app.post("/ml/classify", headers=headers, json=payload()).status_code == 200
    with TestClient(create_app(db, classification_manifest_sha256="0" * 64), base_url="https://testserver") as app:
        result = app.post("/ml/classify", headers=headers, json=payload())
        assert result.status_code == 503 and result.json()["status"] == "unavailable"
        assert str(path) not in result.text and "Traceback" not in result.text


def test_auth_scope_expiry_revocation_device_and_duplicate_headers(classifier_client, db, headers, token):
    assert classifier_client.post("/ml/classify", json=payload()).status_code == 401
    assert classifier_client.post("/ml/classify", headers={"X-API-Key": "bad"}, json=payload()).status_code == 401
    with connect(db) as conn, transaction(conn):
        upload_only, _ = issue(conn, label="Synthetic upload fixture", scopes={"sensor:write"})
        read_only, _ = issue(conn, label="Synthetic read fixture", scopes={"sensor:read"})
        expired, _ = issue(conn, label="Expired fixture", scopes={"sensor:read"}, now=int(time.time()) - 8 * 86400)
        revoked, _ = issue(conn, label="Revoked fixture", scopes={"sensor:read"})
        revoke(conn, revoked.split(".")[0])
    assert classifier_client.post("/ml/classify", headers={"X-API-Key": upload_only}, json=payload()).status_code == 403
    assert classifier_client.post("/ml/classify", headers={"X-API-Key": read_only}, json=payload()).status_code == 200
    for credential in (expired, revoked):
        assert classifier_client.post("/ml/classify", headers={"X-API-Key": credential}, json=payload()).status_code == 401
    assert classifier_client.post("/ml/classify", headers=headers, json={**payload(), "device_id": "another-device"}).status_code == 403
    assert classifier_client.post("/ml/classify", headers=[("X-API-Key", token), ("X-API-Key", token)], json=payload()).status_code == 401


@pytest.mark.parametrize("change", [
    {"task": "dehydration"}, {"task": []}, {"device_id": ""}, {"device_id": 3},
    {"input_source": "guess"}, {"input_source": []}, {"confidence": 1},
    {"features": {"Heart Rate (bpm)": 76, "unknown": 2}}, {"features": {}},
    {"features": [76]}, {"features": {"Heart Rate (bpm)": None}},
    {"features": {"Heart Rate (bpm)": True}}, {"features": {"Heart Rate (bpm)": "76"}},
    {"features": {"Heart Rate (bpm)": -1}}, {"features": {"Heart Rate (bpm)": 9999}},
])
def test_strict_feature_and_request_schema(classifier_client, headers, change):
    response = classifier_client.post("/ml/classify", headers=headers, json={**payload(), **change})
    assert response.status_code == 400 and "classification" not in response.json()


@pytest.mark.parametrize("field", ["device_id", "task", "features", "input_source"])
def test_missing_required_field(classifier_client, headers, field):
    body = payload()
    del body[field]
    assert classifier_client.post("/ml/classify", headers=headers, json=body).status_code == 400


@pytest.mark.parametrize("raw", [b"[]", b"null", b"bad", b'\xff',
    b'{"device_id":"elder-demo-01","device_id":"other"}',
    b'{"device_id":"elder-demo-01","task":"heart_rate","features":{"Heart Rate (bpm)":NaN},"input_source":"synthetic_dataset"}',
    b'{"device_id":"elder-demo-01","task":"heart_rate","features":{"Heart Rate (bpm)":1e400},"input_source":"synthetic_dataset"}',
])
def test_malformed_duplicate_and_nonfinite_json(classifier_client, headers, raw):
    assert classifier_client.post("/ml/classify", headers=headers, content=raw).status_code == 400


@pytest.mark.parametrize("oxygen", [0, -1, 100.01, 101, 9000])
def test_oxygen_above_100_is_invalid_not_high_health_alert(classifier_client, headers, oxygen):
    body = payload("oxygen")
    body["features"]["SpO2 Level (%)"] = oxygen
    assert classifier_client.post("/ml/classify", headers=headers, json=body).status_code == 400


@pytest.mark.parametrize("task", ["blood_pressure", "body_temperature"])
def test_no_fake_prototype_blood_pressure_or_core_temperature(classifier_client, headers, task):
    assert classifier_client.post("/ml/classify", headers=headers, json=payload(task, "prototype_sensor")).status_code == 400
    assert classifier_client.post("/ml/classify", headers=headers, json=payload(task, "external_reference")).status_code == 200
    body = payload("body_temperature")
    body["features"] = {"surface_temp_c": 37}
    assert classifier_client.post("/ml/classify", headers=headers, json=body).status_code == 400


def test_inherited_tls_body_limit_and_credential_rate_limit(db, headers, classifier_client, model_pack):
    assert classifier_client.post("/ml/classify", headers=headers, content=b"x" * (MAX_BODY_BYTES + 1)).status_code == 413
    with TestClient(create_app(db), base_url="http://testserver") as insecure:
        assert insecure.post("/ml/classify", headers={**headers, "X-Forwarded-Proto": "https"}, json=payload()).status_code == 426
    path, digest = model_pack
    with TestClient(create_app(db, limits=Limits(credential_requests=2), classification_manifest=path,
                              classification_manifest_sha256=digest), base_url="https://testserver") as limited:
        assert limited.post("/ml/classify", headers=headers, json=payload()).status_code == 200
        assert limited.post("/ml/classify", headers=headers, json=payload()).status_code == 200
        response = limited.post("/ml/classify", headers=headers, json=payload())
        assert response.status_code == 429 and "Retry-After" in response.headers


@pytest.mark.parametrize("kind", ["bytes", "cycle", "probabilities", "label", "schema", "path", "classes"])
def test_pinned_json_artifact_integrity_and_schema(model_pack, kind):
    path, digest = model_pack
    manifest = json.loads(path.read_text(encoding="utf-8"))
    entry = manifest["models"]["heart_rate"]
    target = path.parent / entry["file"]
    tree = json.loads(target.read_text(encoding="utf-8"))
    if kind == "bytes":
        target.write_bytes(target.read_bytes() + b" ")
        assert CandidateClassifier(path, digest).status == "unavailable"
        return
    if kind == "cycle":
        tree["nodes"][0]["left"] = 0
    elif kind == "probabilities":
        tree["nodes"][0]["probabilities"] = [0.9, 0.9]
    elif kind == "label":
        tree["nodes"][0]["predicted_class"] = "Critical"
    elif kind == "schema":
        tree["comparison_input_type"] = "float64"
    elif kind == "classes":
        tree["classes"] = ["ABNORMAL", "NORMAL"]
    elif kind == "path":
        entry["file"] = "../heart_rate_tree.json"
    raw = json.dumps(tree, ensure_ascii=False).encode()
    target.write_bytes(raw)
    entry["sha256"] = hashlib.sha256(raw).hexdigest()
    manifest_bytes = json.dumps(manifest, ensure_ascii=False).encode()
    path.write_bytes(manifest_bytes)
    assert CandidateClassifier(path, hashlib.sha256(manifest_bytes).hexdigest()).status == "unavailable"


def test_candidate_rejects_high_oxygen_rule_and_keeps_default_off(db):
    from care_monitor_server import alert_engine as engine
    example = json.loads((Path(__file__).resolve().parents[1] / "rules.example.json").read_text(encoding="utf-8"))
    assert all(rule["id"] != "oxygen_high" for rule in example["rules"])
    assert example["enabled"] is False
    invalid = {**copy.deepcopy(engine.DEFAULT_POLICY), "rules": [
        {"id": "oxygen_high", "enabled": True, "metric": "spo2_percent", "direction": "high",
         "trigger": 99, "recover": 98, "persistence_seconds": 2, "recovery_seconds": 2,
         "min_samples": 2, "severity": "warning"}]}
    with connect(db) as conn:
        assert "oxygen_high" in engine.capabilities(conn)["unsupported"]
        with pytest.raises(ValueError, match="not supported"):
            engine.initialise(conn, invalid)


def test_legacy_high_oxygen_policy_cannot_survive_candidate_restart(db):
    from care_monitor_server import alert_engine as engine
    old = {**copy.deepcopy(engine.DEFAULT_POLICY), "enabled": True, "operator_acknowledged": True,
           "rules": [{"id": "oxygen_high", "enabled": True, "metric": "spo2_percent", "direction": "high",
                      "trigger": 99, "recover": 98, "persistence_seconds": 2, "recovery_seconds": 2,
                      "min_samples": 2, "severity": "warning"}]}
    with connect(db) as conn, transaction(conn):
        conn.execute("UPDATE cm_alert_config SET policy_json=? WHERE singleton=1", (json.dumps(old),))
    with TestClient(create_app(db), base_url="https://testserver"):
        with connect(db) as conn:
            capability = engine.capabilities(conn)
            assert capability["rule_policy"]["enabled"] is False
            assert "no longer supported" in capability["rule_policy"]["error"]

