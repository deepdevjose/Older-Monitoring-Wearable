"""Reproducible synthetic alert integration measurement; never targets a live DB."""
from __future__ import annotations

import argparse
import copy
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
from statistics import mean
from tempfile import TemporaryDirectory
from time import perf_counter
from unittest.mock import patch

from fastapi.testclient import TestClient

from . import alert_engine
from .app import create_app
from .database import connect, initialise, transaction
from .security import issue


def measure() -> dict:
    started_at = datetime.now(timezone.utc).isoformat()
    timings = []
    created = []
    policy = {
        **copy.deepcopy(alert_engine.DEFAULT_POLICY), "enabled": True, "operator_acknowledged": True,
        "version": "isolated-synthetic-measurement-v1",
        "label": "SYNTHETIC SOFTWARE FIXTURE ONLY; not an operational/clinical threshold",
        "rules": [{"id": "heart_rate_high", "enabled": True, "metric": "heart_rate_bpm",
                   "direction": "high", "trigger": 100, "recover": 95,
                   "persistence_seconds": 2, "recovery_seconds": 2,
                   "min_samples": 3, "severity": "warning"}],
    }
    # Explicit temporary storage and cleared rule-file configuration prevent
    # this harness from enabling a policy in the running server/database.
    with patch.dict(os.environ, {"CM_ALERT_RULES_FILE": ""}), TemporaryDirectory(prefix="caremonitor-alert-fixture-") as temporary:
        db = Path(temporary) / "synthetic-only.db"
        initialise(db)
        with connect(db) as conn, transaction(conn):
            upload, _ = issue(conn, label="Ephemeral synthetic upload",
                              device_id="assessment-fixture-01", patient_id="synthetic-patient",
                              scopes={"sensor:write"})
            review, _ = issue(conn, label="Ephemeral synthetic reviewer",
                              device_id="assessment-fixture-01", patient_id="synthetic-patient",
                              scopes={"alerts:read", "alerts:ack"})
            alert_engine.initialise(conn, policy)
        upload_headers, review_headers = {"X-API-Key": upload}, {"X-API-Key": review}
        app = create_app(db, classification_manifest="", classification_manifest_sha256="")
        start = datetime.now(timezone.utc) - timedelta(seconds=12)

        def snapshot(second, bpm):
            stamp = (start + timedelta(seconds=second)).isoformat()
            return {"device_id": "assessment-fixture-01", "timestamp": stamp, "phone_received_at": stamp,
                    "uptime_ms": second * 1000, "boot_nonce": "synthetic-fixture", "sample_id": second,
                    "max30102": {"online": True, "status": "ok", "finger_detected": True,
                                "heart_rate_valid": True, "spo2_valid": True,
                                "heart_rate_bpm": bpm, "spo2_percent": 98,
                                "sample_age_ms": 10, "result_age_ms": 100},
                    "mpu6050": {"online": True, "accel_g": {"x": 0.01, "y": 0.02, "z": 1.0}},
                    "gy906": {"online": True, "object_temp_c": 33.5, "ambient_temp_c": 24.1}}

        with TestClient(app, base_url="https://isolated-testserver") as client:
            def request(method, route, **kwargs):
                before = perf_counter()
                response = client.request(method, route, **kwargs)
                elapsed = (perf_counter() - before) * 1000
                timings.append({"operation": method + " " + route, "milliseconds": round(elapsed, 3),
                                "http_status": response.status_code})
                assert response.status_code == 200, "Synthetic API fixture failed"
                return response.json()

            generated_at = None
            trigger_request_ms = None
            for second in range(5):
                response = request("POST", "/sensor-data", headers=upload_headers, json=snapshot(second, 120))
                if response["alert_created"]:
                    created.append(response["alert_id"])
                    generated_at = perf_counter()
                    trigger_request_ms = timings[-1]["milliseconds"]
            assert len(created) == 1, "Expected one episode despite repeated abnormal samples"
            page = request("GET", "/alerts", headers=review_headers)
            generation_to_review_ms = (perf_counter() - generated_at) * 1000
            assert len(page["alerts"]) == 1
            alert = page["alerts"][0]
            assert alert["status"] == "active" and alert["id"] == created[0]
            ack = request("POST", f"/alerts/{alert['id']}/acknowledge",
                          headers=review_headers, json={"device_id": "assessment-fixture-01"})
            assert ack["alert"]["status"] == "acknowledged" and ack["alert"]["resolved_at"] is None
            same_ack = request("POST", f"/alerts/{alert['id']}/acknowledge",
                               headers=review_headers, json={"device_id": "assessment-fixture-01"})
            assert same_ack["alert"]["revision"] == ack["alert"]["revision"]
            for second in (5, 6, 7):
                request("POST", "/sensor-data", headers=upload_headers, json=snapshot(second, 76))
            final = request("GET", "/alerts", headers=review_headers)
            assert len(final["alerts"]) == 1 and final["alerts"][0]["status"] == "resolved"
        with TestClient(create_app(db, classification_manifest="", classification_manifest_sha256=""),
                        base_url="https://isolated-testserver") as restarted:
            durable = restarted.get("/alerts", headers=review_headers)
            assert durable.status_code == 200 and durable.json()["alerts"][0] == final["alerts"][0]
        with connect(db) as conn:
            readings = conn.execute("SELECT COUNT(*) FROM cm_readings").fetchone()[0]
            episodes = conn.execute("SELECT COUNT(*) FROM cm_alerts").fetchone()[0]
            assert readings == 8 and episodes == 1
        result = {
            "status": "passed", "recorded_at_utc": started_at,
            "scope": "isolated synthetic software integration; temporary SQLite and in-process ASGI TestClient",
            "physical_sensor_test": False, "android_phone_test": False, "wifi_or_push_delivery_test": False,
            "production_database_accessed": False, "live_policy_changed": False,
            "clinical_validation": False, "temporary_database_deleted_after_run": True,
            "input": {"kind": "synthetic", "snapshots": 8, "abnormal_snapshots": 5,
                      "recovery_snapshots": 3, "synthetic_timestamp_persistence_seconds": 2,
                      "wall_clock_sleep_used": False},
            "checks": {"generate": True, "store": True, "review_via_api": True,
                       "duplicate_episode_suppression": True, "acknowledge": True,
                       "duplicate_ack_idempotent": True, "ack_not_recovery": True,
                       "recover": True, "persist_across_app_restart": True},
            "stored_readings": readings, "stored_episodes": episodes, "generated_episodes": len(created),
            "final_episode_status": "resolved",
            "measurements": {
                "meaning": "Software processing/round-trip only; excludes real-time persistence waiting, radio, phone polling and notification delivery",
                "trigger_request_ms": trigger_request_ms,
                "generation_response_to_api_review_ms": round(generation_to_review_ms, 3),
                "mean_measured_request_ms": round(mean(row["milliseconds"] for row in timings), 3),
                "requests": timings,
            },
        }
    package = Path(__file__).resolve().parent
    result["source_sha256"] = {
        name: hashlib.sha256((package / name).read_bytes()).hexdigest()
        for name in ("app.py", "classification.py", "alert_engine.py", "database.py",
                     "security.py", "measure_alert_integration.py")
    }
    return result


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="evidence/backend/alert-integration.json")
    args = parser.parse_args(argv)
    root = Path(__file__).resolve().parent.parent
    output = Path(args.output)
    if not output.is_absolute():
        output = root / output
    output = output.resolve()
    if not output.is_relative_to(root):
        parser.error("Evidence output must remain inside the isolated candidate directory")
    result = measure()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": result["status"], "stored_readings": result["stored_readings"],
                      "stored_episodes": result["stored_episodes"], "output": str(output),
                      "scope": "synthetic software only; no phone/hardware/network proof"}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

