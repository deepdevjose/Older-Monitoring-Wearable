"""Isolated SQLite tests: quality gates, durable episodes, and shadow isolation."""
from __future__ import annotations

import copy
from datetime import datetime, timedelta, timezone
import hashlib
import json
import sqlite3

import pytest

from care_monitor_server import alert_engine as engine


START = datetime(2026, 10, 22, 2, 0, tzinfo=timezone.utc)


def stamp(second=0):
    return (START + timedelta(seconds=second)).isoformat().replace("+00:00", "Z")


def snapshot(second=0, bpm=76, oxygen=98):
    return {
        "device_id": "elder-demo-01", "timestamp": stamp(second), "phone_received_at": stamp(second),
        "boot_nonce": "test-boot", "sample_id": second,
        "max30102": {"status": "ok", "online": True, "finger_detected": True,
                     "heart_rate_valid": True, "spo2_valid": True,
                     "heart_rate_bpm": bpm, "spo2_percent": oxygen, "ir_raw": 90000},
        "gy906": {"online": True, "object_temp_c": 33.5, "ambient_temp_c": 24.1},
        "mpu6050": {"online": True, "accel_g": {"x": 5, "y": 6, "z": 7}},
    }


def policy(quality=False):
    return {
        **copy.deepcopy(engine.DEFAULT_POLICY), "enabled": True, "operator_acknowledged": True,
        "version": "test-demo-v1", "label": "TEST ONLY; unvalidated demonstration thresholds",
        "quality_alerts": quality, "quality_persistence_seconds": 2, "quality_recovery_seconds": 2,
        "rules": [{"id": "heart_rate_high", "enabled": True, "metric": "heart_rate_bpm", "direction": "high",
                   "trigger": 100, "recover": 95, "persistence_seconds": 2, "recovery_seconds": 2,
                   "min_samples": 3, "severity": "warning"}],
    }


@pytest.fixture
def db(monkeypatch):
    monkeypatch.delenv("CM_ALERT_RULES_FILE", raising=False)
    conn = sqlite3.connect(":memory:")
    conn.row_factory = sqlite3.Row
    engine.initialise(conn)
    conn.commit()
    yield conn
    conn.close()


def run(db, second=0, bpm=76, oxygen=98, data=None, receive=None):
    return engine.process_snapshot(db, second + 1, "elder-demo-01", "test-patient", data if data is not None else snapshot(second, bpm, oxygen), receive or stamp(second))


def alerts(db, **kw):
    return engine.list_alerts(db, "elder-demo-01", **kw)["alerts"]


def trigger(db, start=0):
    for i in range(start, start + 3):
        run(db, i, bpm=120)
    return alerts(db)[-1]


def test_default_rules_off_but_shadow_records_abnormal(db):
    for i in range(5):
        result = run(db, i, bpm=120, oxygen=90)
    assert alerts(db) == []
    assert result["shadow"]["heart_rate"]["label"] == "ABNORMAL"
    assert result["shadow"]["oxygen"]["label"] == "ABNORMAL"
    assert result["shadow"]["heart_rate"]["clinical_validation"] is False
    assert result["assessment"]["rules_enabled"] is False
    assert db.execute("SELECT count(*) FROM cm_shadow_predictions").fetchone()[0] == 10
    assert db.execute("SELECT max(clinical_validation) FROM cm_shadow_predictions").fetchone()[0] == 0


@pytest.mark.parametrize("field,value", [
    ("online", False), ("status", "no_finger"), ("status", "read_error"), ("status", None),
    ("status", "warming_up"), ("status", "unstable"), ("status", "waiting"),
    ("finger_detected", False), ("finger_detected", None), ("finger_detected", "true"),
    ("heart_rate_valid", False), ("heart_rate_valid", None), ("heart_rate_valid", 1),
    ("heart_rate_bpm", None), ("heart_rate_bpm", True), ("heart_rate_bpm", "76"),
    ("heart_rate_bpm", float("nan")), ("heart_rate_bpm", float("inf")),
    ("heart_rate_bpm", -1), ("heart_rate_bpm", 3280),
])
def test_invalid_optical_values_never_become_normal_or_trigger(db, field, value):
    engine.initialise(db, policy())
    for i in range(4):
        data = snapshot(i, bpm=120)
        data["max30102"][field] = value
        result = run(db, i, data=data)
    assert result["shadow"]["heart_rate"]["label"] is None
    assert result["shadow"]["heart_rate"]["status"] == "unavailable"
    assert result["assessment"]["status"] == "unavailable"
    assert alerts(db) == []


@pytest.mark.parametrize("value", [None, True, "98", 0, -5, 101, float("nan"), float("inf")])
def test_oxygen_processed_estimate_physical_bounds(db, value):
    result = run(db, data=snapshot(0, oxygen=value))
    assert result["shadow"]["oxygen"]["status"] == "unavailable"
    assert result["shadow"]["oxygen"]["label"] is None


def test_hr_can_be_valid_while_oxygen_is_invalid(db):
    data = snapshot()
    data["max30102"]["spo2_valid"] = False
    result = run(db, data=data)
    assert result["shadow"]["heart_rate"]["status"] == "synthetic_label_prediction"
    assert result["shadow"]["oxygen"]["label"] is None
    assert result["assessment"]["status"] == "unavailable"


@pytest.mark.parametrize("valid_metric", ["heart_rate", "oxygen"])
def test_partial_window_uses_only_its_independently_valid_channel(db, valid_metric):
    data = snapshot()
    data["max30102"]["status"] = "partial"
    data["max30102"]["heart_rate_valid"] = valid_metric == "heart_rate"
    data["max30102"]["spo2_valid"] = valid_metric == "oxygen"
    result = run(db, data=data)
    other_metric = "oxygen" if valid_metric == "heart_rate" else "heart_rate"
    assert result["shadow"][valid_metric]["status"] == "synthetic_label_prediction"
    assert result["shadow"][other_metric]["status"] == "unavailable"
    assert result["shadow"][other_metric]["label"] is None
    assert result["assessment"]["status"] == "unavailable"
    assert alerts(db) == []


def test_warming_up_never_reuses_stale_validity_flags(db):
    data = snapshot()
    data["max30102"]["status"] = "warming_up"
    result = run(db, data=data)
    assert result["shadow"]["heart_rate"]["status"] == "unavailable"
    assert result["shadow"]["oxygen"]["status"] == "unavailable"
    assert result["shadow"]["heart_rate"]["label"] is None
    assert result["shadow"]["oxygen"]["label"] is None
    assert alerts(db) == []


@pytest.mark.parametrize("ages", [
    {"sample_age_ms": 500}, {"result_age_ms": 2500},
    {"sample_age_ms": -1}, {"result_age_ms": -1},
    {"sample_age_ms": None}, {"result_age_ms": None},
    {"sample_age_ms": True}, {"result_age_ms": False},
    {"sample_age_ms": "1"}, {"result_age_ms": "1"},
    {"sample_age_ms": float("nan")}, {"result_age_ms": float("inf")},
])
def test_supplied_invalid_or_stale_optical_ages_abstain(db, ages):
    data = snapshot()
    data["max30102"].update(ages)
    result = run(db, data=data)
    assert result["shadow"]["heart_rate"]["status"] == "unavailable"
    assert result["shadow"]["oxygen"]["status"] == "unavailable"
    assert alerts(db) == []


@pytest.mark.parametrize("ages", [
    {}, {"sample_age_ms": 0, "result_age_ms": 0},
    {"sample_age_ms": 499, "result_age_ms": 2499},
    {"sample_age_ms": 1}, {"result_age_ms": 1},
])
def test_fresh_or_legacy_absent_optical_ages_keep_valid_channels(db, ages):
    data = snapshot()
    data["max30102"].update(ages)
    result = run(db, data=data)
    assert result["shadow"]["heart_rate"]["status"] == "synthetic_label_prediction"
    assert result["shadow"]["oxygen"]["status"] == "synthetic_label_prediction"


def test_optional_online_absence_accepted_but_other_flags_required(db):
    data = snapshot()
    del data["max30102"]["online"]
    assert run(db, data=data)["shadow"]["heart_rate"]["label"] == "NORMAL"


def test_raw_counts_do_not_supply_missing_vitals(db):
    data = snapshot()
    del data["max30102"]["heart_rate_bpm"]
    del data["max30102"]["spo2_percent"]
    data["max30102"]["ir_raw"] = 76
    data["max30102"]["red_raw"] = 98
    result = run(db, data=data)
    assert all(result["shadow"][task]["label"] is None for task in engine.MODEL_SPECS)


@pytest.mark.parametrize("bpm,oxygen", [(40, 98), (200, 98), (76, 50)])
def test_physical_values_outside_training_range_are_not_normal(db, bpm, oxygen):
    result = run(db, bpm=bpm, oxygen=oxygen)
    task = "heart_rate" if bpm != 76 else "oxygen"
    assert result["shadow"][task]["status"] == "out_of_training_range"
    assert result["shadow"][task]["label"] is None


def test_single_phone_timestamp_is_acceptable(db):
    data = snapshot()
    data.pop("timestamp")
    assert run(db, data=data)["assessment"]["status"] == "available"


@pytest.mark.parametrize("data,receive,reason", [
    ({"max30102": {}}, stamp(), "missing_sample_timestamp"),
    ({"timestamp": "not-time"}, stamp(), "invalid_timestamp"),
    ({"timestamp": "2026-10-22T02:00:00"}, stamp(), "invalid_timestamp"),
    (snapshot(0), stamp(31), "stale_sample"),
    (snapshot(10), stamp(0), "future_sample"),
])
def test_timestamp_quality_gate(db, data, receive, reason):
    result = run(db, data=data, receive=receive)
    assert result["assessment"]["status"] == "unavailable"
    assert result["assessment"]["reason"] == reason
    assert result["shadow"]["heart_rate"]["label"] is None


def test_either_old_timestamp_prevents_historical_queue_current_alarm(db):
    engine.initialise(db, policy())
    for i in range(4):
        data = snapshot(i, bpm=120)
        data["phone_received_at"] = stamp(100 + i)
        result = run(db, i, data=data, receive=stamp(100 + i))
    assert result["assessment"]["reason"] == "stale_sample"
    assert alerts(db) == []


def test_duplicate_timestamp_and_identity_gate(db):
    run(db, 0)
    assert run(db, 0)["assessment"]["reason"] == "duplicate_sample"
    data = snapshot(1)
    data["sample_id"] = 0
    assert run(db, 1, data=data)["assessment"]["reason"] == "duplicate_sample"


def test_out_of_order_snapshots_do_not_move_watermark_backwards(db):
    run(db, 10)
    assert run(db, 9)["assessment"]["reason"] == "out_of_order_sample"
    assert run(db, 11)["assessment"]["status"] == "available"
    state = json.loads(db.execute("SELECT state_json FROM cm_alert_state").fetchone()[0])
    assert state["last_sample_at"] == engine._iso(START + timedelta(seconds=11))


def test_device_mismatch_gates_even_if_wrapper_prevents_it(db):
    data = snapshot()
    data["device_id"] = "different-device"
    assert run(db, data=data)["assessment"]["reason"] == "device_identity_mismatch"


def test_invalid_snapshot_is_unavailable_and_not_diagnosis(db):
    result = engine.process_snapshot(db, 1, "elder-demo-01", "test-patient", [], stamp())
    assert result["assessment"]["reason"] == "invalid_snapshot"
    assert result["assessment"]["clinical_diagnosis"] is False


def test_episode_deduplicates_acknowledgement_preserves_alarm_and_recovers(db):
    engine.initialise(db, policy())
    first = trigger(db)
    assert first["status"] == "active" and first["source"] == "rule"
    assert first["model_version"] is None and first["rule_version"] == "test-demo-v1"
    for i in range(3, 30):
        run(db, i, bpm=120)
    assert len(alerts(db)) == 1
    assert alerts(db)[0]["revision"] == first["revision"]
    ack = engine.acknowledge(db, first["id"], "elder-demo-01", stamp(30))
    assert ack["status"] == "acknowledged" and ack["resolved_at"] is None
    assert engine.acknowledge(db, first["id"], "elder-demo-01", stamp(31))["revision"] == ack["revision"]
    # Below the trigger but above the recovery boundary must NOT resolve.
    for i in range(30, 34):
        run(db, i, bpm=99)
    assert alerts(db)[0]["status"] == "acknowledged"
    for i in range(34, 37):
        run(db, i, bpm=90)
    resolved = alerts(db)[0]
    assert resolved["status"] == "resolved" and resolved["acknowledged_at"] == ack["acknowledged_at"]
    assert resolved["resolved_at"] == engine._iso(START + timedelta(seconds=36))
    assert resolved["evidence"]["recovery"]["value"] == 90
    assert engine.acknowledge(db, first["id"], "elder-demo-01", stamp(37))["status"] == "resolved"
    second = trigger(db, start=37)
    assert len(alerts(db)) == 2 and second["id"] != first["id"] and second["status"] == "active"


def test_invalid_or_missing_values_never_resolve_existing_episode(db):
    engine.initialise(db, policy())
    trigger(db)
    for i in range(3, 8):
        data = snapshot(i, bpm=70)
        data["max30102"]["finger_detected"] = False
        run(db, i, data=data)
    assert alerts(db)[0]["status"] == "active"


def test_gap_resets_consecutive_trigger_and_recovery(db):
    engine.initialise(db, policy())
    run(db, 0, bpm=120)
    run(db, 1, bpm=120)
    run(db, 10, bpm=120)
    assert alerts(db) == []
    run(db, 11, bpm=120)
    run(db, 12, bpm=120)
    assert len(alerts(db)) == 1
    run(db, 13, bpm=80)
    run(db, 14, bpm=80)
    run(db, 20, bpm=80)
    assert alerts(db)[0]["status"] == "active"
    run(db, 21, bpm=80)
    run(db, 22, bpm=80)
    assert alerts(db)[0]["status"] == "resolved"


def test_fast_packet_burst_cannot_replace_elapsed_persistence(db):
    engine.initialise(db, policy())
    for i in range(20):
        data = snapshot(i / 100, bpm=120)
        data["sample_id"] = i
        run(db, i, data=data, receive=stamp(i / 100))
    assert alerts(db) == []


def test_duplicate_or_invalid_gap_resets_persistence(db):
    engine.initialise(db, policy())
    run(db, 0, bpm=120)
    run(db, 1, bpm=120)
    run(db, 1, bpm=120)
    run(db, 2, bpm=120)
    assert alerts(db) == []
    run(db, 3, bpm=120)
    run(db, 4, bpm=120)
    assert len(alerts(db)) == 1


def test_optional_quality_episode_requires_valid_recovery(db):
    engine.initialise(db, policy(quality=True))
    for i in range(5):
        data = snapshot(i)
        data["max30102"]["finger_detected"] = False
        result = run(db, i, data=data)
    assert result["assessment"]["status"] == "unavailable"
    quality = alerts(db)[0]
    assert len(alerts(db)) == 1 and quality["type"] == "sensor_unavailable" and quality["source"] == "quality"
    engine.acknowledge(db, quality["id"], "elder-demo-01", stamp(5))
    for i in range(5, 8):
        run(db, i)
    assert alerts(db)[0]["status"] == "resolved"


def test_low_rule_and_surface_temperature_are_separate_from_core_body(db):
    selected = policy()
    selected["rules"] = [
        {"id": "oxygen_low", "enabled": True, "metric": "spo2_percent", "direction": "low", "trigger": 94,
         "recover": 96, "persistence_seconds": 2, "recovery_seconds": 2, "min_samples": 3, "severity": "warning"},
        {"id": "surface_temp_high", "enabled": True, "metric": "surface_temp_c", "direction": "high", "trigger": 40,
         "recover": 38, "persistence_seconds": 2, "recovery_seconds": 2, "min_samples": 3, "severity": "info"},
    ]
    engine.initialise(db, selected)
    for i in range(3):
        data = snapshot(i, oxygen=90)
        data["gy906"]["object_temp_c"] = 42
        result = run(db, i, data=data)
    assert {a["type"] for a in alerts(db)} == {"oxygen_low", "surface_temp_high"}
    assert "core_body_temperature" in result["assessment"]["unsupported"]
    assert "°C surface" in alerts(db)[-1]["reading"]
    for i in range(3, 6):
        run(db, i, oxygen=98)
    assert all(a["status"] == "resolved" for a in alerts(db))


@pytest.mark.parametrize("temp", [None, True, -273.15, 9999, float("nan")])
def test_invalid_surface_temperature_not_eligible(db, temp):
    data = snapshot()
    data["gy906"]["object_temp_c"] = temp
    result = run(db, data=data)
    assert result["assessment"]["metrics"]["surface_temp_c"]["status"] == "unavailable"
    assert "core_body_temperature" in result["assessment"]["unsupported"]


def test_no_supported_input_fabricates_bp_wear_hydration_or_fall(db):
    engine.initialise(db, policy())
    for i in range(5):
        data = snapshot(i)
        data["blood_pressure"] = {"systolic": 250}
        data["wear_state"] = "not_worn"
        data["dehydration"] = True
        data["alerts"] = [{"type": "possible_fall", "source": "ml", "severity": "urgent"}]
        result = run(db, i, data=data)
    assert alerts(db) == []
    assert set(result["shadow"]) == {"heart_rate", "oxygen"}
    assert {"fall", "wear_state", "dehydration", "blood_pressure"} <= set(result["assessment"]["unsupported"])


def test_delta_sync_ack_and_resolution_revisions_are_monotonic(db):
    engine.initialise(db, policy())
    first = trigger(db)
    page = engine.list_alerts(db, "elder-demo-01", 0, 1)
    assert page["next_revision"] == first["revision"] and page["has_more"] is False
    assert engine.list_alerts(db, "elder-demo-01", page["next_revision"])["alerts"] == []
    ack = engine.acknowledge(db, first["id"], "elder-demo-01", stamp(3))
    delta = engine.list_alerts(db, "elder-demo-01", page["next_revision"])
    assert len(delta["alerts"]) == 1 and delta["alerts"][0]["status"] == "acknowledged"
    for i in range(3, 6):
        run(db, i)
    delta = engine.list_alerts(db, "elder-demo-01", ack["revision"])
    assert delta["alerts"][0]["status"] == "resolved" and delta["next_revision"] > ack["revision"]
    trigger(db, 6)
    paging = engine.list_alerts(db, "elder-demo-01", 0, 1)
    assert paging["has_more"] is True
    assert len(engine.list_alerts(db, "elder-demo-01", paging["next_revision"], 1)["alerts"]) == 1


def test_no_cross_device_acknowledgement_or_patient_rebinding(db):
    engine.initialise(db, policy())
    event = trigger(db)
    assert engine.list_alerts(db, "other-device")["alerts"] == []
    with pytest.raises(KeyError):
        engine.acknowledge(db, event["id"], "other-device", stamp(3))
    with pytest.raises(ValueError):
        engine.process_snapshot(db, 4, "elder-demo-01", "different-patient", snapshot(3), stamp(3))


def test_functions_leave_commit_to_caller(db):
    engine.initialise(db, policy())
    db.commit()
    trigger(db)
    assert len(alerts(db)) == 1
    db.rollback()
    assert alerts(db) == []
    assert db.execute("SELECT count(*) FROM cm_shadow_predictions").fetchone()[0] == 0


def test_episode_and_counter_survive_database_reopen(tmp_path, monkeypatch):
    monkeypatch.delenv("CM_ALERT_RULES_FILE", raising=False)
    path = tmp_path / "isolated.sqlite3"
    db = sqlite3.connect(path)
    engine.initialise(db, policy())
    run(db, 0, bpm=120)
    run(db, 1, bpm=120)
    db.commit()
    db.close()
    db = sqlite3.connect(path)
    run(db, 2, bpm=120)
    event = alerts(db)[0]
    engine.acknowledge(db, event["id"], "elder-demo-01", stamp(3))
    db.commit()
    db.close()
    db = sqlite3.connect(path)
    assert alerts(db)[0]["status"] == "acknowledged"
    assert len(alerts(db)) == 1
    db.close()


def test_shadow_retention_is_globally_bounded_including_duplicates(db):
    selected = copy.deepcopy(engine.DEFAULT_POLICY)
    selected["shadow_retention_rows"] = 6
    engine.initialise(db, selected)
    for i in range(10):
        run(db, 0)
    assert db.execute("SELECT count(*) FROM cm_shadow_predictions").fetchone()[0] == 6
    assert db.execute("SELECT min(id) FROM cm_shadow_predictions").fetchone()[0] == 15


def test_missing_or_tampered_models_fail_unavailable_without_care_alert(db, tmp_path, monkeypatch):
    monkeypatch.setattr(engine, "MODEL_DIRECTORY", tmp_path)
    (tmp_path / "heart_rate_tree.json").write_text('{"nodes": []}', encoding="utf-8")
    result = run(db, bpm=120)
    assert result["shadow"]["heart_rate"]["status"] == "unavailable"
    assert result["shadow"]["heart_rate"]["label"] is None
    assert alerts(db) == []
    assert engine.capabilities()["shadow_ml"]["models"]["heart_rate"]["available"] is False


def test_schema_validation_rejects_cyclic_even_if_digest_were_updated(db, tmp_path, monkeypatch):
    model, _ = engine._model("heart_rate")
    model["nodes"][0]["left"] = 0
    encoded = json.dumps(model).encode()
    (tmp_path / "heart_rate_tree.json").write_bytes(encoded)
    specs = copy.deepcopy(engine.MODEL_SPECS)
    specs["heart_rate"]["sha256"] = hashlib.sha256(encoded).hexdigest()
    monkeypatch.setattr(engine, "MODEL_DIRECTORY", tmp_path)
    monkeypatch.setattr(engine, "MODEL_SPECS", specs)
    assert engine._model("heart_rate")[0] is None
    assert run(db)["shadow"]["heart_rate"]["label"] is None


@pytest.mark.parametrize("change", [
    {"enabled": True, "operator_acknowledged": False}, {"freshness_seconds": 999999},
    {"max_gap_seconds": -1}, {"shadow_retention_rows": True}, {"unknown": 1},
])
def test_policy_requires_explicit_valid_operator_config(db, change):
    selected = copy.deepcopy(engine.DEFAULT_POLICY)
    selected.update(change)
    with pytest.raises(ValueError):
        engine.initialise(db, selected)


def test_invalid_config_file_fails_closed_with_visible_error(db, tmp_path, monkeypatch):
    path = tmp_path / "invalid-rules.json"
    path.write_text('{"enabled":true}', encoding="utf-8")
    monkeypatch.setenv("CM_ALERT_RULES_FILE", str(path))
    engine.initialise(db)
    assert engine.capabilities()["rule_policy"]["enabled"] is False
    assert engine.capabilities()["rule_policy"]["error"]
    for i in range(3):
        run(db, i, bpm=120)
    assert alerts(db) == []


def test_admin_initialization_without_environment_preserves_existing_policy(db):
    engine.initialise(db, policy())
    engine.initialise(db)
    assert engine.capabilities()["rule_policy"]["enabled"] is True
    assert trigger(db)["type"] == "heart_rate_high"


def test_capabilities_bound_to_database_even_if_another_database_initializes(db):
    engine.initialise(db, policy())
    unrelated = sqlite3.connect(":memory:")
    engine.initialise(unrelated)
    assert engine.capabilities(unrelated)["rule_policy"]["enabled"] is False
    assert engine.capabilities(db)["rule_policy"]["enabled"] is True
    unrelated.close()


def test_unsupported_metric_and_no_hysteresis_are_rejected(db):
    selected = policy()
    selected["rules"][0]["metric"] = "body_temperature"
    with pytest.raises(ValueError):
        engine.initialise(db, selected)
    selected = policy()
    selected["rules"][0]["recover"] = 100
    with pytest.raises(ValueError):
        engine.initialise(db, selected)


@pytest.mark.parametrize("cursor,limit", [(-1, 50), (True, 50), (0, 0), (0, 201)])
def test_delta_cursor_bounds(db, cursor, limit):
    with pytest.raises(ValueError):
        engine.list_alerts(db, "elder-demo-01", cursor, limit)
