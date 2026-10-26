"""Read-only experimental classification of one assigned, persisted observation.

Timestamps and channel quality are gates, never imputations. Uptime is not UTC.
Legacy firmware can omit optical age fields; that reduced coverage is explicit.
"""
from __future__ import annotations

from datetime import datetime, timezone
import json
import math

from .classification import FEATURES, InvalidClassificationInput

FRESHNESS_SECONDS = 10
FUTURE_TOLERANCE_SECONDS = 2
CHANNELS = {
    "heart_rate": ("heart_rate_bpm", "heart_rate_valid", "bpm", 1, 300),
    "oxygen": ("spo2_percent", "spo2_valid", "%", 1, 100),
}


def utc_now():
    return datetime.now(timezone.utc)


def iso(value):
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def parse_time(value):
    if not isinstance(value, str) or len(value) > 80:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return parsed.astimezone(timezone.utc) if parsed.tzinfo is not None else None
    except (ValueError, OverflowError, OSError):
        return None


def finite(value):
    try:
        return type(value) in (int, float) and math.isfinite(value)
    except OverflowError:
        return False


def payload(row):
    try:
        data = json.loads(row["payload_json"])
        return data if isinstance(data, dict) else None
    except (ValueError, TypeError, RecursionError):
        return None


def timing(row, data, now):
    """Strictly evaluate all supplied clocks, choosing the oldest observation."""
    result = {"status": "unavailable", "reason": "missing_reading", "age_seconds": None,
              "freshness_seconds": FRESHNESS_SECONDS,
              "future_tolerance_seconds": FUTURE_TOLERANCE_SECONDS}
    if row is None:
        return None, None, result
    receipt = parse_time(row["received_at"])
    if data is None:
        result["reason"] = "invalid_payload"
        return None, None, result
    # The database field is the device timestamp saved by ingest. The phone
    # timestamp is a separate observation clock, not a new upload-time clock.
    supplied = []
    if row["sensor_timestamp"] is not None:
        supplied.append(("device", row["sensor_timestamp"]))
    if "phone_received_at" in data:
        supplied.append(("phone", data["phone_received_at"]))
    stamps = [(source, parse_time(value)) for source, value in supplied]
    parsed = [(source, value) for source, value in stamps if value is not None]
    source, measurement = min(parsed, key=lambda item: item[1]) if parsed else (None, None)
    stamp = iso(measurement) if measurement is not None else None
    if receipt is None:
        result["reason"] = "invalid_receipt_time"
    elif not stamps:
        result["reason"] = "missing_measurement_time"
    elif len(parsed) != len(stamps):
        result["reason"] = "invalid_measurement_time"
    elif (receipt - now).total_seconds() > FUTURE_TOLERANCE_SECONDS:
        result["reason"] = "future_receipt_time"
    elif any((value - now).total_seconds() > FUTURE_TOLERANCE_SECONDS or
             (value - receipt).total_seconds() > FUTURE_TOLERANCE_SECONDS for _, value in parsed):
        result["reason"] = "future_measurement_time"
    else:
        age = max((now - receipt).total_seconds(), *( (now - value).total_seconds() for _, value in parsed))
        result["age_seconds"] = round(max(0.0, age), 3)
        result["status"] = "stale" if age > FRESHNESS_SECONDS else "fresh"
        result["reason"] = "stale_reading" if age > FRESHNESS_SECONDS else None
    return stamp, source, result


def reading_view(row, now):
    data = payload(row)
    stamp, source, freshness = timing(row, data, now)
    return {"id": row["id"], "received_at": row["received_at"],
            "measurement_at": stamp, "measurement_time_source": source,
            "freshness": freshness, "data": data if data is not None else {}}


def unavailable(reason, unit, coverage=None):
    return {"status": "unavailable", "reason": reason, "value": None, "unit": unit,
            "classification": None, "confidence": None, "class_probabilities": None,
            "quality_coverage": coverage}


def classify_row(device_id, row, classifier, now=None):
    now = utc_now() if now is None else now
    data = payload(row) if row is not None else None
    stamp, source, freshness = timing(row, data, now)
    results = {}
    optical = data.get("max30102") if data is not None else None
    coverage = None if not isinstance(optical, dict) else {
        "online_reported": "online" in optical,
        "sample_age_reported": "sample_age_ms" in optical,
        "result_age_reported": "result_age_ms" in optical,
    }
    common_reason = freshness["reason"]
    if common_reason is None:
        if not isinstance(optical, dict):
            common_reason = "optical_missing"
        elif "online" in optical and optical["online"] is not True:
            common_reason = "optical_offline" if optical["online"] is False else "invalid_optical_online"
        elif optical.get("status") not in ("ok", "partial"):
            common_reason = "optical_not_ready"
        elif optical.get("finger_detected") is not True:
            common_reason = "finger_not_detected"
        elif any(field in optical and (not finite(optical[field]) or not 0 <= optical[field] < limit)
                 for field, limit in (("sample_age_ms", 500), ("result_age_ms", 2500))):
            common_reason = "stale_or_invalid_optical_age"
    for task, (field, flag, unit, low, high) in CHANNELS.items():
        reason = common_reason
        value = optical.get(field) if isinstance(optical, dict) else None
        if reason is None:
            if optical.get(flag) is not True:
                reason = "channel_invalid"
            elif not finite(value):
                reason = "missing_or_non_numeric"
            elif not low <= value <= high:
                reason = "outside_physical_range"
            elif classifier.status != "ready":
                reason = "model_disabled" if classifier.status == "disabled" else "model_unavailable"
        if reason is not None:
            results[task] = unavailable(reason, unit, coverage)
            continue
        try:
            prediction = classifier.classify(device_id, task, {FEATURES[task][0]: value}, "prototype_sensor")
        except InvalidClassificationInput:
            results[task] = unavailable("outside_training_domain", unit, coverage)
        else:
            prediction.update(status="available", reason=None, value=value, unit=unit, quality_coverage=coverage)
            results[task] = prediction
    count = sum(result["status"] == "available" for result in results.values())
    return {"device_id": device_id, "reading_id": row["id"] if row is not None else None,
            "received_at": row["received_at"] if row is not None else None,
            "measurement_at": stamp, "measurement_time_source": source, "server_time": iso(now),
            "freshness": freshness,
            "status": "available" if count == len(CHANNELS) else "partial" if count else "unavailable",
            "results": results, "three_class_supported": False, "confidence_calibrated": False,
            "clinical_validation": False, "care_alert_created": False,
            "mode": "experimental_stored_reading", "synthetic_training": True}
