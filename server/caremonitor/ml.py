"""Strictly synthetic educational classification; never a live alert detector."""
from __future__ import annotations

import hashlib
import hmac
import io
import json
import math
from numbers import Real
from pathlib import Path

FEATURES = ("heart_rate_bpm", "spo2_percent", "body_temp_c")


class ModelService:
    """Load only locally generated, hash-verified artifacts from the configured directory.

    Joblib is executable serialization. Hashes detect accidental substitution; they
    are not signatures. Protect both this directory and the metadata from writes by
    untrusted users. Never load a downloaded model through this interface.
    """

    def __init__(self, model_dir: Path | str | None = None):
        self.model_dir = Path(model_dir) if model_dir else Path(__file__).resolve().parents[2] / "models/synthetic-health-v1"
        self.metadata = json.loads((self.model_dir / "metadata.json").read_text(encoding="utf-8"))
        if self.metadata.get("schema_version") != 1 or self.metadata.get("allowed_source") != "synthetic" or self.metadata.get("educational_only") is not True:
            raise ValueError("Refusing model without synthetic-only manifest")
        if tuple(self.metadata.get("feature_names", [])) != FEATURES:
            raise ValueError("Unexpected model features")
        selected = self.metadata.get("selected_model")
        if selected not in {"logistic_regression", "random_forest"}:
            raise ValueError("Unexpected model artifact")
        artifact = self.model_dir / (selected + ".joblib")
        if artifact.stat().st_size > 64 * 1024 * 1024:
            raise ValueError("Model artifact exceeds size limit")
        expected = self.metadata.get("artifact_sha256", {}).get(artifact.name, "")
        artifact_bytes = artifact.read_bytes()
        actual = hashlib.sha256(artifact_bytes).hexdigest()
        if not isinstance(expected, str) or not hmac.compare_digest(actual, expected):
            raise ValueError("Model artifact integrity check failed")
        import joblib
        import sklearn
        if sklearn.__version__ != self.metadata.get("versions", {}).get("sklearn"):
            raise ValueError("Model/runtime scikit-learn version mismatch; retrain locally")
        # Deserialize the exact verified bytes rather than reopening the path.
        self.model = joblib.load(io.BytesIO(artifact_bytes))

    def predict(self, features: dict, source: str = "synthetic") -> dict:
        if source != "synthetic":
            raise ValueError("This educational model accepts synthetic samples only")
        if not isinstance(features, dict) or set(features) != {*FEATURES, "sample_age_seconds"}:
            raise ValueError("Exactly heart_rate_bpm, spo2_percent, body_temp_c and sample_age_seconds are required")
        for name, value in features.items():
            if isinstance(value, bool) or not isinstance(value, Real) or not math.isfinite(float(value)):
                raise ValueError(f"{name} must be a finite numeric value")
        age = features["sample_age_seconds"]
        if not 0 <= age <= self.metadata["max_sample_age_seconds"]:
            raise ValueError("Synthetic sample is stale or has invalid age")
        for name in FEATURES:
            low, high = self.metadata["feature_ranges"][name]
            if not low <= features[name] <= high:
                raise ValueError(f"{name} outside the synthetic training support")
        import pandas as pd
        row = pd.DataFrame([{name: float(features[name]) for name in FEATURES}], columns=list(FEATURES))
        probabilities = self.model.predict_proba(row)[0]
        index = int(probabilities.argmax())
        return {
            "classification": str(self.model.classes_[index]),
            "confidence": round(float(probabilities[index]), 6),
            "model_id": self.metadata["model_id"],
            "source": "synthetic", "educational_only": True,
            "limitations": self.metadata["limitations"],
        }
