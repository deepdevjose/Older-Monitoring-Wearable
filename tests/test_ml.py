from pathlib import Path
import shutil

import pytest

from server.caremonitor.ml import ModelService
from tools.train_models import derive_target, split_groups


@pytest.fixture(scope="module")
def service():
    return ModelService()


def features(**overrides):
    return {"heart_rate_bpm": 80, "spo2_percent": 98, "body_temp_c": 36.8, "sample_age_seconds": 1, **overrides}


def test_predict_is_explicitly_educational(service):
    result = service.predict(features())
    assert result["classification"] in {"Normal", "Warning", "Critical"}
    assert 0 <= result["confidence"] <= 1
    assert result["educational_only"] is True
    assert result["source"] == "synthetic"
    assert any("not a probability" in limitation for limitation in result["limitations"])


@pytest.mark.parametrize("source", ["real", "device", "live", "", None])
def test_real_sources_cannot_reach_model(service, source):
    with pytest.raises(ValueError, match="synthetic samples only"):
        service.predict(features(), source=source)


@pytest.mark.parametrize("overrides", [
    {"heart_rate_bpm": None}, {"heart_rate_bpm": True}, {"heart_rate_bpm": "80"},
    {"spo2_percent": float("nan")}, {"body_temp_c": float("inf")},
    {"sample_age_seconds": 10.001}, {"sample_age_seconds": -1},
    {"heart_rate_bpm": 300}, {"body_temp_c": 30}, {"spo2_percent": 101},
])
def test_invalid_or_stale_samples_rejected(service, overrides):
    with pytest.raises(ValueError):
        service.predict(features(**overrides))


def test_missing_and_leaking_features_rejected(service):
    row = features()
    row.pop("body_temp_c")
    with pytest.raises(ValueError):
        service.predict(row)
    with pytest.raises(ValueError):
        service.predict({**features(), "Predicted Disease": "Healthy"})


def test_modified_artifact_rejected_before_deserialization(service, tmp_path):
    shutil.copytree(service.model_dir, tmp_path / "model")
    artifact = tmp_path / "model" / (service.metadata["selected_model"] + ".joblib")
    artifact.write_bytes(artifact.read_bytes() + b"tampered")
    with pytest.raises(ValueError, match="integrity"):
        ModelService(tmp_path / "model")


def test_label_mapping_and_group_leakage():
    import pandas as pd
    import numpy as np
    # Plenty of independently repeated tuples for stratified group partitions.
    x = pd.DataFrame({"a": np.repeat(np.arange(90), 2)})
    flags = pd.DataFrame({
        "Heart Rate Alert": np.where(x.a % 3 > 0, "ABNORMAL", "NORMAL"),
        "SpO2 Level Alert": np.where(x.a % 3 == 2, "ABNORMAL", "NORMAL"),
        "Temperature Alert": "NORMAL",
    })
    y = derive_target(flags)
    assert list(y.iloc[[0, 2, 4]]) == ["Normal", "Warning", "Critical"]
    masks, _, groups = split_groups(x, y)
    assert groups == 90
    partitions = [set(x.loc[mask, "a"]) for mask in masks.values()]
    assert not partitions[0] & partitions[1]
    assert not partitions[0] & partitions[2]
    assert not partitions[1] & partitions[2]
    y.iloc[1] = "Critical"
    with pytest.raises(ValueError, match="conflicting"):
        split_groups(x, y)
