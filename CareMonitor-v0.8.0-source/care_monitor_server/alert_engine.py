"""Transactional alert episodes and synthetic-label SHADOW inference.

No function commits its caller's SQLite transaction. Numeric alerts are OFF until
an operator supplies an explicitly enabled policy; model outputs never create
care alerts. Only the two pinned JSON trees are loaded (never pickle/joblib).
"""
from __future__ import annotations

import copy
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sqlite3
import struct
from datetime import datetime, timezone
from typing import Any


ENGINE_VERSION = "care-monitor-alerts-v1"
MODEL_DIRECTORY = Path(__file__).resolve().parent / "models"
MODEL_SPECS = {
    "heart_rate": {
        "file": "heart_rate_tree.json",
        "sha256": "87fe9e9adc2b4040c6aa2f25912492c26680c549954de6e3c3ab25004cbf9da2",
        "feature": "Heart Rate (bpm)",
        "metric": "heart_rate_bpm",
    },
    "oxygen": {
        "file": "oxygen_tree.json",
        "sha256": "81a7207f33a978b97ece247d0ce7e522089490570dc4385bb65d27779654b0b9",
        "feature": "SpO2 Level (%)",
        "metric": "spo2_percent",
    },
}
METRICS = {
    "heart_rate_bpm": {"unit": "bpm", "range": (1, 300), "high": "heart_rate_high", "low": "heart_rate_low"},
    "spo2_percent": {"unit": "%", "range": (1, 100), "low": "oxygen_low"},
    # These are sensor operating/input bounds, NOT healthy-person thresholds.
    "surface_temp_c": {"unit": "°C surface", "range": (-70, 380), "high": "surface_temp_high", "low": "surface_temp_low"},
    "ambient_temp_c": {"unit": "°C ambient", "range": (-40, 125), "high": "ambient_temp_high", "low": "ambient_temp_low"},
}
UNSUPPORTED = {
    "oxygen_high": "No high-SpO2 health rule is supplied; values above 100 percent are invalid input.",
    "core_body_temperature": "GY906 object readings are surface temperature, not validated core body temperature.",
    "blood_pressure": "No measured blood-pressure input is available.",
    "fall": "Approximately 1 Hz snapshots cannot run the offline whole-trial/high-frequency SisFall model.",
    "wear_state": "No validated wear-state measurement or labelled detector is available.",
    "dehydration": "No hydration measurement or validated labelled detector is available.",
}
DEFAULT_POLICY = {
    "enabled": False,
    "operator_acknowledged": False,
    "version": "disabled-v1",
    "label": "Disabled until an operator reviews and enables a policy",
    "freshness_seconds": 30,
    "future_tolerance_seconds": 5,
    "max_gap_seconds": 3,
    "shadow_retention_rows": 10000,
    "quality_alerts": False,
    "quality_persistence_seconds": 3,
    "quality_recovery_seconds": 3,
    "quality_min_samples": 3,
    "rules": [],
}
_capability_policy = copy.deepcopy(DEFAULT_POLICY)
_policy_error: str | None = None


def _finite(value: Any) -> bool:
    try:
        return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)
    except (OverflowError, TypeError):
        return False


def _utc(value: Any) -> datetime | None:
    if not isinstance(value, str) or len(value) > 80:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return parsed.astimezone(timezone.utc) if parsed.tzinfo is not None else None
    except (ValueError, OverflowError):
        return None


def _iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _canonical(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False)


def _validate_policy(raw: Any) -> dict:
    if not isinstance(raw, dict):
        raise ValueError("Policy must be an object")
    allowed = set(DEFAULT_POLICY)
    if set(raw) - allowed:
        raise ValueError("Unknown policy setting")
    policy = {**copy.deepcopy(DEFAULT_POLICY), **copy.deepcopy(raw)}
    for field in ("enabled", "operator_acknowledged", "quality_alerts"):
        if not isinstance(policy[field], bool):
            raise ValueError("Policy booleans must be true or false")
    if policy["enabled"] and not policy["operator_acknowledged"]:
        raise ValueError("Enabled rules require explicit operator_acknowledged=true")
    if not isinstance(policy["version"], str) or not re.fullmatch(r"[A-Za-z0-9._-]{1,80}", policy["version"]):
        raise ValueError("Policy version must be a short version identifier")
    if not isinstance(policy["label"], str) or not 1 <= len(policy["label"]) <= 240:
        raise ValueError("Policy label is required")
    for key, low, high in (
        ("freshness_seconds", 1, 300), ("future_tolerance_seconds", 0, 30),
        ("max_gap_seconds", 0.1, 60), ("quality_persistence_seconds", 0, 3600),
        ("quality_recovery_seconds", 0, 3600),
    ):
        if not _finite(policy[key]) or not low <= policy[key] <= high:
            raise ValueError("Policy duration is invalid: " + key)
    for key, low, high in (("shadow_retention_rows", 2, 100000), ("quality_min_samples", 2, 3600)):
        if type(policy[key]) is not int or not low <= policy[key] <= high:
            raise ValueError("Policy count is invalid: " + key)
    if not isinstance(policy["rules"], list) or len(policy["rules"]) > 8:
        raise ValueError("At most eight supported rules may be configured")
    seen = set()
    for rule in policy["rules"]:
        fields = {"id", "enabled", "metric", "direction", "trigger", "recover", "persistence_seconds", "recovery_seconds", "min_samples", "severity"}
        if not isinstance(rule, dict) or set(rule) != fields:
            raise ValueError("Each rule must supply all documented rule fields")
        metric, direction = rule["metric"], rule["direction"]
        if metric not in METRICS or direction not in ("high", "low") or direction not in METRICS[metric]:
            raise ValueError("Rule metric/direction is not supported")
        if rule["id"] != METRICS[metric][direction] or rule["id"] in seen:
            raise ValueError("Rule id must match its metric/direction and be unique")
        seen.add(rule["id"])
        if type(rule["enabled"]) is not bool or rule["severity"] not in ("info", "warning", "urgent"):
            raise ValueError("Invalid rule enablement/severity")
        low, high = METRICS[metric]["range"]
        if any(not _finite(rule[k]) or not low <= rule[k] <= high for k in ("trigger", "recover")):
            raise ValueError("Threshold must lie inside the metric's physical input bounds")
        if (direction == "high" and rule["recover"] >= rule["trigger"]) or (direction == "low" and rule["recover"] <= rule["trigger"]):
            raise ValueError("A separate recovery threshold is required for hysteresis")
        for key in ("persistence_seconds", "recovery_seconds"):
            if not _finite(rule[key]) or not 0 <= rule[key] <= 3600:
                raise ValueError("Invalid rule persistence duration")
        if type(rule["min_samples"]) is not int or not 2 <= rule["min_samples"] <= 3600:
            raise ValueError("At least two real consecutive samples are required")
    return policy


def _load_policy() -> tuple[dict, str | None]:
    path = os.environ.get("CM_ALERT_RULES_FILE", "").strip()
    if not path:
        return copy.deepcopy(DEFAULT_POLICY), None
    try:
        data = Path(path).read_bytes()
        if len(data) > 65536:
            raise ValueError("Policy exceeds size limit")
        return _validate_policy(json.loads(data)), None
    except (OSError, ValueError, TypeError):
        # Do not include an environment path or file content in public errors.
        return copy.deepcopy(DEFAULT_POLICY), "Policy could not be validated; numeric rules are disabled."


def initialise(conn: sqlite3.Connection, policy: dict | None = None) -> None:
    """Create owned tables and freeze a validated policy in this database.

    ``policy`` is an optional explicit operator/test configuration. Production
    callers normally use CM_ALERT_RULES_FILE and restart after a policy change.
    Invalid production file configuration fails closed, visible in capabilities.
    """
    global _capability_policy, _policy_error
    selected, error = (_validate_policy(policy), None) if policy is not None else _load_policy()
    for statement in (
        """CREATE TABLE IF NOT EXISTS cm_alert_revision (singleton INTEGER PRIMARY KEY CHECK(singleton=1), value INTEGER NOT NULL)""",
        """CREATE TABLE IF NOT EXISTS cm_alerts (
            id INTEGER PRIMARY KEY AUTOINCREMENT, device_id TEXT NOT NULL, patient_id TEXT NOT NULL,
            type TEXT NOT NULL, severity TEXT NOT NULL, source TEXT NOT NULL,
            status TEXT NOT NULL CHECK(status IN ('active','acknowledged','resolved')),
            occurred_at TEXT NOT NULL, updated_at TEXT NOT NULL, acknowledged_at TEXT, resolved_at TEXT,
            evidence_json TEXT NOT NULL, reading TEXT NOT NULL, rule_version TEXT, model_version TEXT,
            revision INTEGER NOT NULL UNIQUE)""",
        """CREATE INDEX IF NOT EXISTS cm_alerts_device_revision ON cm_alerts(device_id,revision)""",
        """CREATE UNIQUE INDEX IF NOT EXISTS cm_alerts_open_episode ON cm_alerts(device_id,type) WHERE status != 'resolved'""",
        """CREATE TABLE IF NOT EXISTS cm_alert_state (device_id TEXT PRIMARY KEY, patient_id TEXT NOT NULL, state_json TEXT NOT NULL)""",
        """CREATE TABLE IF NOT EXISTS cm_alert_config (singleton INTEGER PRIMARY KEY CHECK(singleton=1), policy_json TEXT NOT NULL, error TEXT)""",
        """CREATE TABLE IF NOT EXISTS cm_shadow_predictions (
            id INTEGER PRIMARY KEY AUTOINCREMENT, reading_id INTEGER NOT NULL, device_id TEXT NOT NULL,
            patient_id TEXT NOT NULL, sample_at TEXT, received_at TEXT NOT NULL, task TEXT NOT NULL,
            status TEXT NOT NULL, label TEXT, reason TEXT, model_version TEXT NOT NULL,
            model_sha256 TEXT NOT NULL, clinical_validation INTEGER NOT NULL DEFAULT 0 CHECK(clinical_validation=0))""",
        """CREATE INDEX IF NOT EXISTS cm_shadow_device ON cm_shadow_predictions(device_id,id)""",
    ):
        conn.execute(statement)
    conn.execute("INSERT OR IGNORE INTO cm_alert_revision(singleton,value) VALUES(1,0)")
    existing = conn.execute("SELECT policy_json,error FROM cm_alert_config WHERE singleton=1").fetchone()
    # Admin operations may initialise the same database without the server's
    # environment file. They must not silently disable its active policy.
    if existing and policy is None and not os.environ.get("CM_ALERT_RULES_FILE", "").strip():
        try:
            selected, error = _validate_policy(json.loads(existing[0])), existing[1]
        except (ValueError, TypeError, KeyError):
            # Candidate migrations must not keep executing a persisted rule
            # that the current capabilities explicitly reject (e.g. high SpO2).
            selected = copy.deepcopy(DEFAULT_POLICY)
            error = "Stored policy is no longer supported; numeric rules are disabled."
            conn.execute("UPDATE cm_alert_config SET policy_json=?,error=? WHERE singleton=1",
                         (_canonical(selected), error))
    else:
        conn.execute("INSERT OR REPLACE INTO cm_alert_config(singleton,policy_json,error) VALUES(1,?,?)", (_canonical(selected), error))
    _capability_policy, _policy_error = copy.deepcopy(selected), error


def _policy(conn: sqlite3.Connection) -> dict:
    row = conn.execute("SELECT policy_json FROM cm_alert_config WHERE singleton=1").fetchone()
    return json.loads(row[0]) if row else copy.deepcopy(DEFAULT_POLICY)


def _model(task: str) -> tuple[dict | None, str | None]:
    """Verify bytes on every call so replacing a model cannot bypass the pin."""
    spec = MODEL_SPECS[task]
    try:
        data = (MODEL_DIRECTORY / spec["file"]).read_bytes()
        if len(data) > 65536 or hashlib.sha256(data).hexdigest() != spec["sha256"]:
            raise ValueError("Model digest mismatch")
        model = json.loads(data)
        if (model.get("format") != "care-monitor-offline-tree-v1" or model.get("synthetic_only") is not True
                or model.get("features") != [spec["feature"]] or model.get("comparison_input_type") != "float32"
                or model.get("classes") != ["ABNORMAL", "NORMAL"]):
            raise ValueError("Unsupported model metadata")
        bounds = model["supported_training_ranges"][spec["feature"]]
        if not isinstance(bounds, list) or len(bounds) != 2 or not all(_finite(n) for n in bounds) or bounds[0] >= bounds[1]:
            raise ValueError("Invalid model range")
        nodes = model["nodes"]
        if not isinstance(nodes, list) or not 1 <= len(nodes) <= 1024:
            raise ValueError("Invalid model tree")
        visited = set()

        def visit(index: int) -> None:
            if type(index) is not int or not 0 <= index < len(nodes) or index in visited:
                raise ValueError("Invalid/cyclic model tree")
            visited.add(index)
            node = nodes[index]
            if not isinstance(node, dict) or node.get("predicted_class") not in model["classes"]:
                raise ValueError("Invalid model node")
            left, right = node.get("left"), node.get("right")
            if left == right:
                if left != -1:
                    raise ValueError("Invalid leaf")
            else:
                if node.get("feature") != 0 or not _finite(node.get("threshold")):
                    raise ValueError("Invalid split")
                visit(left)
                visit(right)

        visit(0)
        if len(visited) != len(nodes):
            raise ValueError("Unreachable model node")
        return model, None
    except (OSError, ValueError, KeyError, TypeError, OverflowError, RecursionError):
        return None, "Pinned JSON model unavailable or failed integrity/schema validation"


def capabilities(conn: sqlite3.Connection | None = None) -> dict:
    selected, error = _capability_policy, _policy_error
    if conn is not None:
        row = conn.execute("SELECT policy_json,error FROM cm_alert_config WHERE singleton=1").fetchone()
        if row:
            selected, error = json.loads(row[0]), row[1]
    return {
        "engine_version": ENGINE_VERSION,
        "rule_policy": {"enabled": selected["enabled"], "version": selected["version"],
                        "label": selected["label"], "error": error},
        "shadow_ml": {
            "mode": "shadow_only", "care_alerts_enabled": False, "clinical_validation": False,
            "models": {task: {"available": _model(task)[0] is not None, "version": "synthetic-json-tree-v1",
                              "sha256": spec["sha256"]} for task, spec in MODEL_SPECS.items()},
        },
        "supported_rule_metrics": {key: value["unit"] for key, value in METRICS.items()},
        "unsupported": copy.deepcopy(UNSUPPORTED),
        "stream_gap_notice": "Gaps reset persistence counters; absent uploads require separate server liveness monitoring.",
    }


def _metric_values(snapshot: dict) -> dict:
    result = {}
    optical = snapshot.get("max30102")
    optical = optical if isinstance(optical, dict) else {}
    # Optional on older firmware; a supplied null/malformed/stale age cannot
    # make an earlier valid estimate current again.
    ages_fresh = all(field not in optical or (_finite(optical[field]) and 0 <= optical[field] < limit)
                     for field, limit in (("sample_age_ms", 500), ("result_age_ms", 2500)))
    for key, flag in (("heart_rate_bpm", "heart_rate_valid"), ("spo2_percent", "spo2_valid")):
        valid = (optical.get("online") is not False and optical.get("status") in ("ok", "partial")
                 and optical.get("finger_detected") is True and optical.get(flag) is True and ages_fresh)
        value = optical.get(key)
        low, high = METRICS[key]["range"]
        reason = None
        if not valid:
            reason = "Valid per-channel optical estimate, completed window, finger contact and fresh optional ages are required"
        elif not _finite(value) or not low <= value <= high:
            reason = "Missing, non-finite or physically unsupported processed estimate"
        result[key] = {"status": "available" if reason is None else "unavailable", "value": value if reason is None else None,
                       "unit": METRICS[key]["unit"], "reason": reason}
    temperature = snapshot.get("gy906")
    temperature = temperature if isinstance(temperature, dict) else {}
    for key, field in (("surface_temp_c", "object_temp_c"), ("ambient_temp_c", "ambient_temp_c")):
        value = temperature.get(field)
        module_valid = (temperature.get("online") is True and temperature.get("status", "ok") in ("ok", "sampled"))
        low, high = METRICS[key]["range"]
        valid = module_valid and _finite(value) and low <= value <= high
        result[key] = {"status": "available" if valid else "unavailable", "value": value if valid else None,
                       "unit": METRICS[key]["unit"], "reason": None if valid else "Valid online GY906 temperature in supported sensor bounds required"}
    return result


def _gate(snapshot: Any, received_at: str, state: dict, policy: dict) -> tuple[datetime | None, str | None, str | None]:
    now = _utc(received_at)
    if now is None:
        raise ValueError("received_at must be a timezone-aware ISO timestamp")
    if not isinstance(snapshot, dict):
        return None, "invalid_snapshot", None
    times = []
    for field in ("timestamp", "phone_received_at"):
        if field in snapshot:
            parsed = _utc(snapshot[field])
            if parsed is None:
                return None, "invalid_" + field, None
            times.append(parsed)
    if not times:
        return None, "missing_sample_timestamp", None
    # The older supplied timestamp prevents queued observations looking current.
    sample_at = min(times)
    if any((now - stamp).total_seconds() > policy["freshness_seconds"] for stamp in times):
        return sample_at, "stale_sample", None
    if any((stamp - now).total_seconds() > policy["future_tolerance_seconds"] for stamp in times):
        return sample_at, "future_sample", None
    identity = None
    if "sample_id" in snapshot:
        sample_id, boot = snapshot["sample_id"], snapshot.get("boot_nonce", "")
        if not isinstance(sample_id, (str, int)) or isinstance(sample_id, bool) or not isinstance(boot, (str, int)) or isinstance(boot, bool):
            return sample_at, "invalid_sample_identity", None
        identity = str(boot) + ":" + str(sample_id)
        if len(identity) > 200:
            return sample_at, "invalid_sample_identity", None
        if identity in state.get("sample_ids", []):
            return sample_at, "duplicate_sample", identity
    previous = _utc(state.get("last_sample_at"))
    if previous and sample_at <= previous:
        return sample_at, "duplicate_sample" if sample_at == previous else "out_of_order_sample", identity
    return sample_at, None, identity


def _revision(conn: sqlite3.Connection) -> int:
    conn.execute("UPDATE cm_alert_revision SET value=value+1 WHERE singleton=1")
    return conn.execute("SELECT value FROM cm_alert_revision WHERE singleton=1").fetchone()[0]


def _dicts(cursor: sqlite3.Cursor) -> list[dict]:
    names = [entry[0] for entry in cursor.description]
    return [dict(zip(names, row)) for row in cursor.fetchall()]


def _alert(row: dict) -> dict:
    result = dict(row)
    result["evidence"] = json.loads(result.pop("evidence_json"))
    return result


def _active(conn: sqlite3.Connection, device_id: str, kind: str) -> dict | None:
    rows = _dicts(conn.execute("SELECT * FROM cm_alerts WHERE device_id=? AND type=? AND status!='resolved'", (device_id, kind)))
    return rows[0] if rows else None


def _create(conn: sqlite3.Connection, device: str, patient: str, kind: str, severity: str, source: str,
            sample_at: str, now: str, evidence: dict, reading: str, rule_version: str) -> int:
    cursor = conn.execute("""INSERT INTO cm_alerts(device_id,patient_id,type,severity,source,status,occurred_at,updated_at,
                         evidence_json,reading,rule_version,model_version,revision) VALUES(?,?,?,?,?,'active',?,?,?,?,?,NULL,?)""",
                          (device, patient, kind, severity, source, sample_at, now, _canonical(evidence), reading, rule_version, _revision(conn)))
    return cursor.lastrowid


def _resolve(conn: sqlite3.Connection, active: dict, now: str, evidence: dict, reading: str) -> None:
    previous = json.loads(active["evidence_json"])
    previous["recovery"] = evidence
    conn.execute("UPDATE cm_alerts SET status='resolved',resolved_at=?,updated_at=?,evidence_json=?,reading=?,revision=? WHERE id=?",
                 (now, now, _canonical(previous), reading, _revision(conn), active["id"]))


def _consecutive(counters: dict, key: str, phase: str, sample_at: datetime, max_gap: float) -> dict:
    current = counters.get(key, {})
    last = _utc(current.get("last"))
    if current.get("phase") != phase or last is None or (sample_at - last).total_seconds() > max_gap or sample_at <= last:
        current = {"phase": phase, "start": _iso(sample_at), "count": 0}
    current["last"] = _iso(sample_at)
    current["count"] = min(current["count"] + 1, 100000)
    counters[key] = current
    return current


def _sustained(counter: dict, sample_at: datetime, seconds: float, minimum: int) -> bool:
    return counter["count"] >= minimum and (sample_at - _utc(counter["start"])).total_seconds() >= seconds


def _shadow(conn: sqlite3.Connection, reading_id: int, device: str, patient: str, sample_at: str | None,
            now: str, metrics: dict, gate: str | None, retention: int) -> dict:
    predictions = {}
    for task, spec in MODEL_SPECS.items():
        model, model_error = _model(task)
        metric = metrics[spec["metric"]]
        result = {"status": "unavailable", "label": None, "reason": gate or metric["reason"] or model_error,
                  "mode": "shadow_only", "clinical_validation": False, "model_version": "synthetic-json-tree-v1",
                  "model_sha256": spec["sha256"]}
        if not result["reason"] and model is not None:
            low, high = model["supported_training_ranges"][spec["feature"]]
            if not low <= metric["value"] <= high:
                result.update(status="out_of_training_range", reason="Processed estimate is outside the supplied synthetic training range")
            else:
                # Match scikit-learn float32 comparisons used during training.
                value = struct.unpack("f", struct.pack("f", metric["value"]))[0]
                node = model["nodes"][0]
                for _ in range(len(model["nodes"])):
                    if node["left"] == node["right"]:
                        result.update(status="synthetic_label_prediction", label=node["predicted_class"], reason=None)
                        break
                    node = model["nodes"][node["left"] if value <= node["threshold"] else node["right"]]
        predictions[task] = result
        conn.execute("""INSERT INTO cm_shadow_predictions(reading_id,device_id,patient_id,sample_at,received_at,task,status,label,reason,model_version,model_sha256)
                     VALUES(?,?,?,?,?,?,?,?,?,?,?)""", (reading_id, device, patient, sample_at, now, task, result["status"], result["label"], result["reason"], result["model_version"], result["model_sha256"]))
    # Global bounded retention, including invalid/duplicate observations. Never
    # retain an unbounded stream merely because inference was unavailable.
    conn.execute("DELETE FROM cm_shadow_predictions WHERE id IN (SELECT id FROM cm_shadow_predictions ORDER BY id DESC LIMIT -1 OFFSET ?)", (retention,))
    return predictions


def process_snapshot(conn: sqlite3.Connection, reading_id: int, device_id: str, patient_id: str,
                     snapshot: Any, received_at: str) -> dict:
    """Assess an already-authorised reading inside the caller's transaction.

    Only source-checked processed estimates are eligible. Client-supplied alerts
    and labels are deliberately ignored, and 1 Hz motion never becomes a fall
    prediction. Queued history can still be stored by the caller while this
    engine marks it unavailable for a CURRENT assessment.
    """
    policy = _policy(conn)
    row = conn.execute("SELECT patient_id,state_json FROM cm_alert_state WHERE device_id=?", (device_id,)).fetchone()
    if row and row[0] != patient_id:
        raise ValueError("Device patient binding differs from existing alert state")
    state = json.loads(row[1]) if row else {}
    fingerprint = hashlib.sha256(_canonical(policy).encode()).hexdigest()
    if state.get("policy_fingerprint") != fingerprint:
        state["counters"] = {}
        state["policy_fingerprint"] = fingerprint
    counters = state.setdefault("counters", {})
    sample_at, gate, identity = _gate(snapshot, received_at, state, policy)
    now = _iso(_utc(received_at))
    data = snapshot if isinstance(snapshot, dict) else {}
    metrics = _metric_values(data)
    if data.get("device_id") not in (None, device_id):
        gate = "device_identity_mismatch"
    for metric in metrics.values():
        if gate:
            metric.update(status="unavailable", value=None, reason=gate)
    predictions = _shadow(conn, reading_id, device_id, patient_id, _iso(sample_at) if sample_at else None, now,
                          metrics, gate, policy["shadow_retention_rows"])
    created, changed = [], []
    if gate:
        counters.clear()
    else:
        previous = _utc(state.get("last_sample_at"))
        if previous and (sample_at - previous).total_seconds() > policy["max_gap_seconds"]:
            counters.clear()
        state["last_sample_at"] = _iso(sample_at)
        if identity is not None:
            state["sample_ids"] = (state.get("sample_ids", []) + [identity])[-128:]
        if policy["enabled"]:
            for rule in policy["rules"]:
                if not rule["enabled"]:
                    continue
                key = rule["id"]
                metric = metrics[rule["metric"]]
                if metric["status"] != "available":
                    counters.pop(key, None)
                    continue  # An invalid value cannot silently resolve an episode.
                value = metric["value"]
                active = _active(conn, device_id, key)
                recovering = value <= rule["recover"] if rule["direction"] == "high" else value >= rule["recover"]
                triggering = value > rule["trigger"] if rule["direction"] == "high" else value < rule["trigger"]
                phase = "recovery" if active and recovering else "trigger" if not active and triggering else None
                if phase is None:
                    counters.pop(key, None)
                    continue
                counter = _consecutive(counters, key, phase, sample_at, policy["max_gap_seconds"])
                duration = rule["recovery_seconds"] if active else rule["persistence_seconds"]
                if not _sustained(counter, sample_at, duration, rule["min_samples"]):
                    continue
                evidence = {"metric": rule["metric"], "value": value, "unit": metric["unit"], "sample_at": _iso(sample_at),
                            "consecutive_since": counter["start"], "consecutive_samples": counter["count"],
                            "threshold": rule["recover"] if active else rule["trigger"], "direction": rule["direction"],
                            "persistence_seconds": duration, "policy_label": policy["label"], "rule_version": policy["version"],
                            "clinical_validation": False}
                reading = f"{value:g} {metric['unit']}"
                if active:
                    _resolve(conn, active, now, evidence, reading)
                    changed.append(active["id"])
                else:
                    alert_id = _create(conn, device_id, patient_id, key, rule["severity"], "rule", _iso(sample_at), now,
                                       evidence, reading, policy["version"])
                    created.append(alert_id)
                    changed.append(alert_id)
                counters.pop(key, None)
            if policy["quality_alerts"]:
                required = {"heart_rate_bpm", "spo2_percent"} | {r["metric"] for r in policy["rules"] if r["enabled"]}
                problems = {key: metrics[key]["reason"] for key in sorted(required) if metrics[key]["status"] != "available"}
                active = _active(conn, device_id, "sensor_unavailable")
                phase = "trigger" if problems and not active else "recovery" if not problems and active else None
                if phase is None:
                    counters.pop("sensor_unavailable", None)
                else:
                    count = _consecutive(counters, "sensor_unavailable", phase, sample_at, policy["max_gap_seconds"])
                    duration = policy["quality_persistence_seconds"] if problems else policy["quality_recovery_seconds"]
                    if _sustained(count, sample_at, duration, policy["quality_min_samples"]):
                        evidence = {"reasons": problems, "sample_at": _iso(sample_at), "consecutive_since": count["start"],
                                    "consecutive_samples": count["count"], "rule_version": policy["version"],
                                    "meaning": "Check sensor; physiological state cannot be assessed" if problems else "Processed readings available again"}
                        if active:
                            _resolve(conn, active, now, evidence, "Processed readings available again")
                            changed.append(active["id"])
                        else:
                            alert_id = _create(conn, device_id, patient_id, "sensor_unavailable", "info", "quality", _iso(sample_at),
                                               now, evidence, "Check sensor; readings unavailable", policy["version"])
                            created.append(alert_id)
                            changed.append(alert_id)
                        counters.pop("sensor_unavailable", None)
    conn.execute("INSERT OR REPLACE INTO cm_alert_state(device_id,patient_id,state_json) VALUES(?,?,?)", (device_id, patient_id, _canonical(state)))
    required_metrics = {"heart_rate_bpm", "spo2_percent"} | {r["metric"] for r in policy["rules"] if policy["enabled"] and r["enabled"]}
    available = not gate and all(metrics[key]["status"] == "available" for key in required_metrics)
    # "available" is a signal-quality fact. It is never a diagnosis of normality.
    return {"assessment": {"status": "available" if available else "unavailable", "reason": gate,
                           "metrics": metrics, "unsupported": copy.deepcopy(UNSUPPORTED),
                           "rules_enabled": policy["enabled"], "clinical_diagnosis": False},
            "shadow": predictions, "alert_created": bool(created), "alert_id": created[-1] if created else None,
            "changed_alert_ids": changed, "engine_version": ENGINE_VERSION}


def list_alerts(conn: sqlite3.Connection, device_id: str, after_revision: int = 0, limit: int = 50) -> dict:
    if type(after_revision) is not int or after_revision < 0 or type(limit) is not int or not 1 <= limit <= 200:
        raise ValueError("Invalid alert cursor or page size")
    rows = _dicts(conn.execute("SELECT * FROM cm_alerts WHERE device_id=? AND revision>? ORDER BY revision ASC LIMIT ?", (device_id, after_revision, limit + 1)))
    page = rows[:limit]
    return {"alerts": [_alert(row) for row in page], "next_revision": page[-1]["revision"] if page else after_revision,
            "has_more": len(rows) > limit}


def acknowledge(conn: sqlite3.Connection, alertid: int, deviceid: str, now: str) -> dict:
    parsed = _utc(now)
    if parsed is None:
        raise ValueError("Acknowledgement time must be timezone-aware ISO")
    rows = _dicts(conn.execute("SELECT * FROM cm_alerts WHERE id=? AND device_id=?", (alertid, deviceid)))
    if not rows:
        raise KeyError(alertid)
    active = rows[0]
    if active["status"] == "active":
        time = _iso(parsed)
        conn.execute("UPDATE cm_alerts SET status='acknowledged',acknowledged_at=?,updated_at=?,revision=? WHERE id=?",
                     (time, time, _revision(conn), alertid))
        active = _dicts(conn.execute("SELECT * FROM cm_alerts WHERE id=?", (alertid,)))[0]
    return _alert(active)
