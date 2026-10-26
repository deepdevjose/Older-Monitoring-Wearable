"""Hash-verified replay-only SisFall complete-record classification."""
from __future__ import annotations

import hashlib
import hmac
import io
import json
import math
from numbers import Real
from pathlib import Path

FEATURES = ("magnitude_max_g", "magnitude_min_g", "magnitude_mean_g", "magnitude_std_g",
            "magnitude_p95_g", "jerk_rms_g_s", "orientation_change_deg")


class FallModelService:
    """Offline educational benchmark; cannot accept current device telemetry.

    Both metadata and joblib must be generated locally and protected from untrusted
    writes. Hash verification detects substitution relative to the trusted manifest;
    it is not a digital signature or a sandbox for third-party executable models.
    """

    def __init__(self, model_dir: Path | str | None = None):
        directory = Path(model_dir) if model_dir is not None else Path(__file__).resolve().parents[2] / "models/sisfall-v1"
        self.model_dir = directory
        self.metadata = json.loads((directory / "metadata.json").read_text(encoding="utf-8"))
        if (self.metadata.get("schema_version") != 1 or self.metadata.get("educational_only") is not True
            or self.metadata.get("allowed_source") != "dataset_replay"
            or self.metadata.get("task") != "complete_record_classification"
            or self.metadata.get("sample_rate_hz") != 200 or tuple(self.metadata.get("feature_names", [])) != FEATURES):
            raise ValueError("Invalid replay-only model manifest")
        selected = self.metadata.get("selected_model")
        if selected not in {"logistic_regression", "random_forest"}:
            raise ValueError("Unexpected model artifact")
        artifact = directory / (selected + ".joblib")
        if artifact.stat().st_size > 64 * 1024 * 1024:
            raise ValueError("Model artifact exceeds size limit")
        content = artifact.read_bytes()
        expected = self.metadata.get("artifact_sha256", {}).get(artifact.name, "")
        if not isinstance(expected, str) or not hmac.compare_digest(hashlib.sha256(content).hexdigest(), expected):
            raise ValueError("Model artifact integrity check failed")
        import joblib
        import sklearn
        if sklearn.__version__ != self.metadata.get("versions", {}).get("sklearn"):
            raise ValueError("Model/runtime version mismatch; retrain locally")
        self.model = joblib.load(io.BytesIO(content))

    def predict(self, features: dict, source: str = "dataset_replay", sample_rate_hz: int = 200,
                record_complete: bool = True) -> dict:
        if source != "dataset_replay":
            raise ValueError("This model accepts public dataset replay only, never device telemetry")
        if type(sample_rate_hz) is not int or sample_rate_hz != 200:
            raise ValueError("This replay requires exactly 200 Hz")
        if record_complete is not True:
            raise ValueError("Complete-record features are required, not windows or snapshots")
        if not isinstance(features, dict) or set(features) != set(FEATURES):
            raise ValueError("Exactly the documented complete-record features are required")
        for key, value in features.items():
            if isinstance(value, bool) or not isinstance(value, Real) or not math.isfinite(float(value)):
                raise ValueError("Features must be finite numeric values")
            upper = 180 if key == "orientation_change_deg" else 32 * math.sqrt(3) * 200 if key == "jerk_rms_g_s" else 16 * math.sqrt(3)
            if not 0 <= value <= upper:
                raise ValueError("Feature exceeds the declared ADXL345 signal support")
        low, high = features["magnitude_min_g"], features["magnitude_max_g"]
        if (not low <= features["magnitude_mean_g"] <= high
            or not low <= features["magnitude_p95_g"] <= high
            or features["magnitude_std_g"] > high - low + 1e-10):
            raise ValueError("Inconsistent magnitude statistics")
        import pandas as pd
        row = pd.DataFrame([{name: float(features[name]) for name in FEATURES}], columns=list(FEATURES))
        probabilities = self.model.predict_proba(row)[0]
        index = int(probabilities.argmax())
        return {"classification": str(self.model.classes_[index]), "confidence": round(float(probabilities[index]), 6),
                "model_id": self.metadata["model_id"], "source": "dataset_replay", "educational_only": True,
                "task": "complete_record_classification", "limitations": self.metadata["limitations"]}
