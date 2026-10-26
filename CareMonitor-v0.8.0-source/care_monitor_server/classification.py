"""Opt-in binary assessment inference from hash-pinned JSON trees only.

This module never opens the production database, loads pickle/joblib, calibrates
sensor values, or creates alerts. Its confidence is a synthetic training-leaf
class proportion, not a probability that a person's health is normal/abnormal.
"""
from __future__ import annotations

import hashlib
import json
import math
import os
from pathlib import Path
import re
import struct
from typing import Any

from .security import IDENTIFIER


CLASSES = ["NORMAL", "ABNORMAL"]
FEATURES = {
    "heart_rate": ["Heart Rate (bpm)"],
    "oxygen": ["SpO2 Level (%)"],
    "blood_pressure": ["Systolic Blood Pressure (mmHg)", "Diastolic Blood Pressure (mmHg)"],
    "body_temperature": ["Body Temperature (°C)"],
}
SOURCES = {"synthetic_dataset", "external_reference", "prototype_sensor"}
MANIFEST_FORMAT = "care-monitor-assessment-probability-manifest-v1"
TREE_FORMAT = "care-monitor-assessment-probability-tree-v1"
SHA256 = re.compile(r"[0-9a-f]{64}")
MAX_MANIFEST_BYTES = 256 * 1024
MAX_TREE_BYTES = 1024 * 1024
MAX_NODES = 10001


class InvalidClassificationInput(ValueError):
    """Client input is unsupported; never substitute a normal prediction."""


def _number(value: Any) -> bool:
    try:
        return type(value) in (int, float) and math.isfinite(value)
    except (OverflowError, TypeError):
        return False


def _unique_object(pairs: list[tuple[str, Any]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON field")
        result[key] = value
    return result


def _reject_constant(value: str) -> None:
    raise ValueError("Non-finite JSON value")


def _read_pinned(path: Path, digest: str, limit: int) -> dict:
    if not isinstance(digest, str) or not SHA256.fullmatch(digest):
        raise ValueError("Invalid artifact digest")
    with path.open("rb") as stream:
        raw = stream.read(limit + 1)
    if len(raw) > limit or hashlib.sha256(raw).hexdigest() != digest:
        raise ValueError("Artifact integrity/size failure")
    value = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_object,
                       parse_constant=_reject_constant)
    if not isinstance(value, dict):
        raise ValueError("Artifact must be an object")
    return value


def validate_request(body: dict) -> tuple[str, str, dict[str, float], str]:
    if set(body) != {"device_id", "task", "features", "input_source"}:
        raise InvalidClassificationInput("Supply only device_id, task, features and input_source")
    device = body["device_id"]
    if not isinstance(device, str) or not IDENTIFIER.fullmatch(device):
        raise InvalidClassificationInput("A valid device_id is required")
    task = body["task"]
    if not isinstance(task, str) or task not in FEATURES:
        raise InvalidClassificationInput("Unsupported classification task")
    source = body["input_source"]
    if not isinstance(source, str) or source not in SOURCES:
        raise InvalidClassificationInput("Unsupported input_source")
    if task in {"blood_pressure", "body_temperature"} and source == "prototype_sensor":
        raise InvalidClassificationInput("The prototype does not measure validated blood pressure or body temperature")
    values = body["features"]
    if not isinstance(values, dict) or set(values) != set(FEATURES[task]):
        raise InvalidClassificationInput("Supply exactly the documented features for the task")
    if any(not _number(value) for value in values.values()):
        raise InvalidClassificationInput("Feature values must be finite numbers, not strings or booleans")
    if task == "oxygen" and not 0 < values["SpO2 Level (%)"] <= 100:
        raise InvalidClassificationInput("SpO2 must be greater than zero and at most 100 percent")
    if task in {"heart_rate", "blood_pressure"} and any(value <= 0 for value in values.values()):
        raise InvalidClassificationInput("Heart rate and blood pressure must be positive")
    return device, task, {key: float(value) for key, value in values.items()}, source


def _validate_tree(tree: dict, task: str, manifest: dict, entry: dict) -> None:
    if (tree.get("format") != TREE_FORMAT or tree.get("task") != task
            or tree.get("features") != FEATURES[task] or tree.get("classes") != CLASSES
            or tree.get("model_version") != manifest["model_version"]
            or tree.get("dataset_sha256") != manifest["dataset_sha256"]
            or tree.get("estimator") != "DecisionTreeClassifier"
            or entry.get("estimator") != "DecisionTreeClassifier"
            or tree.get("comparison_input_type") != "float32"
            or tree.get("synthetic_only") is not True
            or tree.get("clinical_validation") is not False
            or tree.get("confidence_calibrated") is not False):
        raise ValueError("Unsupported tree metadata")
    ranges = tree.get("supported_training_ranges")
    if not isinstance(ranges, dict) or set(ranges) != set(FEATURES[task]):
        raise ValueError("Missing input domain")
    for bounds in ranges.values():
        if (not isinstance(bounds, list) or len(bounds) != 2
                or not all(_number(v) for v in bounds) or bounds[0] > bounds[1]
                or max(abs(v) for v in bounds) > 1e20):
            raise ValueError("Invalid input domain")
    nodes = tree.get("nodes")
    if not isinstance(nodes, list) or not 1 <= len(nodes) <= MAX_NODES:
        raise ValueError("Invalid tree size")
    for node in nodes:
        if not isinstance(node, dict):
            raise ValueError("Invalid node")
        probs = node.get("probabilities")
        if (not isinstance(probs, list) or len(probs) != 2
                or any(not _number(p) or not 0 <= p <= 1 for p in probs)
                or not math.isclose(sum(probs), 1, rel_tol=0, abs_tol=1e-8)
                or node.get("predicted_class") not in CLASSES
                or type(node.get("training_samples")) is not int or node["training_samples"] < 1):
            raise ValueError("Invalid node probabilities")
        # Export preserves sklearn's tie resolution; class order is explicit.
        if probs[CLASSES.index(node["predicted_class"])] < max(probs) - 1e-8:
            raise ValueError("Prediction disagrees with probabilities")
        left, right, feature = node.get("left"), node.get("right"), node.get("feature")
        if any(type(v) is not int for v in (left, right, feature)) or not _number(node.get("threshold")):
            raise ValueError("Invalid node traversal")
        if left == right == -1:
            if feature != -2:
                raise ValueError("Invalid leaf")
        elif not (0 <= left < len(nodes) and 0 <= right < len(nodes) and left != right
                  and 0 <= feature < len(FEATURES[task])):
            raise ValueError("Invalid split")
    # Every node must belong to one acyclic rooted tree, not a graph/cycle.
    pending = [(0, 0)]
    visited = set()
    while pending:
        index, depth = pending.pop()
        if index in visited or depth > 128:
            raise ValueError("Cyclic or over-deep tree")
        visited.add(index)
        node = nodes[index]
        if node["left"] != -1:
            pending.extend(((node["left"], depth + 1), (node["right"], depth + 1)))
    if len(visited) != len(nodes):
        raise ValueError("Unreachable model nodes")


class CandidateClassifier:
    def __init__(self, path: Path | str | None = None, digest: str | None = None):
        selected_path = path if path is not None else os.environ.get("CM_CLASSIFICATION_MODEL_MANIFEST", "")
        selected_digest = digest if digest is not None else os.environ.get("CM_CLASSIFICATION_MANIFEST_SHA256", "")
        self.status = "disabled" if not selected_path and not selected_digest else "unavailable"
        self.manifest: dict = {}
        self.models: dict = {}
        self.manifest_sha256 = selected_digest
        if not selected_path or not selected_digest:
            return
        try:
            location = Path(selected_path).resolve(strict=True)
            manifest = _read_pinned(location, selected_digest, MAX_MANIFEST_BYTES)
            if (manifest.get("format") != MANIFEST_FORMAT or manifest.get("classes") != CLASSES
                    or manifest.get("synthetic_only") is not True
                    or manifest.get("clinical_validation") is not False
                    or manifest.get("confidence_calibrated") is not False
                    or not isinstance(manifest.get("model_version"), str)
                    or not re.fullmatch(r"[A-Za-z0-9._-]{1,120}", manifest["model_version"])
                    or not isinstance(manifest.get("dataset_sha256"), str)
                    or not SHA256.fullmatch(manifest["dataset_sha256"])
                    or not isinstance(manifest.get("models"), dict)
                    or set(manifest["models"]) != set(FEATURES)):
                raise ValueError("Unsupported manifest")
            models = {}
            for task, entry in manifest["models"].items():
                if (not isinstance(entry, dict) or entry.get("features") != FEATURES[task]
                        or not isinstance(entry.get("file"), str)
                        or not re.fullmatch(r"[A-Za-z0-9_.-]+\.json", entry["file"])):
                    raise ValueError("Invalid model entry")
                target = (location.parent / entry["file"]).resolve(strict=True)
                if target.parent != location.parent:
                    raise ValueError("Model escapes its manifest directory")
                tree = _read_pinned(target, entry.get("sha256"), MAX_TREE_BYTES)
                _validate_tree(tree, task, manifest, entry)
                models[task] = tree
            self.manifest, self.models, self.status = manifest, models, "ready"
        except (OSError, ValueError, TypeError, KeyError, OverflowError, RecursionError):
            # Never expose file paths, raw artifacts or exceptions through the API.
            self.status = "unavailable"

    def classify(self, device: str, task: str, values: dict[str, float], source: str) -> dict:
        tree = self.models[task]
        for feature, value in values.items():
            low, high = tree["supported_training_ranges"][feature]
            if not low <= value <= high:
                raise InvalidClassificationInput("Feature value is outside the supported training domain")
        vector = [struct.unpack("<f", struct.pack("<f", values[name]))[0] for name in tree["features"]]
        index = 0
        while tree["nodes"][index]["left"] != -1:
            node = tree["nodes"][index]
            index = node["left"] if vector[node["feature"]] <= node["threshold"] else node["right"]
        leaf = tree["nodes"][index]
        label = leaf["predicted_class"]
        return {
            "status": "experimental_prediction", "device_id": device, "task": task,
            "input_source": source, "classification": label,
            "confidence": leaf["probabilities"][CLASSES.index(label)],
            "class_probabilities": dict(zip(CLASSES, leaf["probabilities"])),
            "confidence_semantics": "Uncalibrated empirical training-leaf class proportion; not medical certainty",
            "confidence_calibrated": False, "three_class_supported": False,
            "clinical_validation": False, "care_alert_created": False,
            "mode": "assessment_candidate", "model": {
                "version": self.manifest["model_version"], "estimator": "DecisionTreeClassifier",
                "sha256": self.manifest["models"][task]["sha256"],
                "manifest_sha256": self.manifest_sha256,
                "dataset_sha256": self.manifest["dataset_sha256"], "synthetic_training": True,
            },
            "supported_training_ranges": tree["supported_training_ranges"],
        }
