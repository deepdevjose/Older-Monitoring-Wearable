import json
from pathlib import Path
import shutil

import numpy as np
import pandas as pd
import pytest

from caremonitor.fall_ml import FallModelService, FEATURES
from tools.train_fall_model import extract_features, subject_split

ROOT = Path(__file__).resolve().parents[1]


@pytest.fixture(scope="module")
def service():
    return FallModelService()


@pytest.fixture
def replay():
    return json.loads((ROOT / "models/sisfall-v1/replay_examples.json").read_text())["examples"][0]


def test_public_replay_contract(service, replay):
    result = service.predict(replay["features"], source=replay["source"],
                             sample_rate_hz=replay["sample_rate_hz"], record_complete=replay["record_complete"])
    assert result["classification"] in {"adl_record", "fall_record"}
    assert result["task"] == "complete_record_classification"
    assert result["source"] == "dataset_replay" and result["educational_only"] is True
    assert 0 <= result["confidence"] <= 1
    assert any("not an online fall detector" in value for value in result["limitations"])


@pytest.mark.parametrize("source", ["device_diagnostic", "real", "synthetic", "live", None])
def test_device_and_unapproved_sources_rejected(service, replay, source):
    with pytest.raises(ValueError, match="dataset replay only"):
        service.predict(replay["features"], source=source)


@pytest.mark.parametrize("rate", [1, 25, 100, 201, 200.0, True, "200", None])
def test_wrong_sample_rate_rejected(service, replay, rate):
    with pytest.raises(ValueError, match="exactly 200"):
        service.predict(replay["features"], sample_rate_hz=rate)


def test_incomplete_record_and_leaking_identifiers_rejected(service, replay):
    with pytest.raises(ValueError, match="Complete-record"):
        service.predict(replay["features"], record_complete=False)
    with pytest.raises(ValueError, match="documented"):
        service.predict({**replay["features"], "subject": "SA01"})
    missing = dict(replay["features"])
    missing.pop("jerk_rms_g_s")
    with pytest.raises(ValueError, match="documented"):
        service.predict(missing)


@pytest.mark.parametrize("value", [None, True, "1", float("nan"), float("inf"), -1, 100000])
def test_invalid_features_rejected(service, replay, value):
    with pytest.raises(ValueError):
        service.predict({**replay["features"], "magnitude_max_g": value})


def test_inconsistent_statistics_rejected(service, replay):
    with pytest.raises(ValueError, match="Inconsistent"):
        service.predict({**replay["features"], "magnitude_mean_g": 20})


def test_tampered_artifact_not_deserialized(service, tmp_path):
    directory = tmp_path / "model"
    shutil.copytree(service.model_dir, directory)
    artifact = directory / (service.metadata["selected_model"] + ".joblib")
    artifact.write_bytes(artifact.read_bytes() + b"tamper")
    with pytest.raises(ValueError, match="integrity"):
        FallModelService(directory)


def test_stationary_gravity_and_orientation_features_have_correct_units():
    a = np.tile([0.0, 1.0, 0.0], (400, 1))
    stationary = extract_features(a)
    assert stationary["magnitude_mean_g"] == 1
    assert stationary["magnitude_std_g"] == 0
    assert stationary["jerk_rms_g_s"] == 0
    assert stationary["orientation_change_deg"] == 0
    a[200:] = [1, 0, 0]
    rotated = extract_features(a)
    assert rotated["orientation_change_deg"] == pytest.approx(90)
    assert rotated["jerk_rms_g_s"] == pytest.approx(np.sqrt(2 * 200**2 / 399))
    assert set(rotated) == set(FEATURES)
    with pytest.raises(ValueError):
        extract_features(a, sample_rate_hz=1)


def test_split_keeps_every_trial_from_a_subject_together():
    frame = pd.DataFrame([{"subject": f"SA{i:02d}", "target": target}
                          for i in range(1, 39) for target in ("adl_record", "fall_record") for _ in range(3)])
    partitions = subject_split(frame)
    groups = {key: set(frame.iloc[indices].subject) for key, indices in partitions.items()}
    assert not groups["train"] & groups["validation"]
    assert not groups["train"] & groups["test"]
    assert not groups["validation"] & groups["test"]
    assert set.union(*groups.values()) == set(frame.subject)
    assert sum(len(indices) for indices in partitions.values()) == len(frame)


def test_replay_examples_are_held_out_public_record_summaries(service):
    examples = json.loads((service.model_dir / "replay_examples.json").read_text())["examples"]
    held_out = set(service.metadata["split"]["test"]["subjects"])
    training = set(service.metadata["split"]["train"]["subjects"])
    assert held_out.isdisjoint(training)
    for example in examples:
        assert example["record_id"].split("_")[1] in held_out
        assert set(example["features"]) == set(FEATURES)
        assert example["split"] == "test"
