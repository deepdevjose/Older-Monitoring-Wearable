#!/usr/bin/env python3
"""Reproduce the educational synthetic-label experiment; never train on live data."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import platform

import joblib
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import sklearn
from sklearn.ensemble import RandomForestClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import classification_report, confusion_matrix, ConfusionMatrixDisplay
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

ROOT = Path(__file__).resolve().parents[1]
FEATURE_COLUMNS = {
    "Heart Rate (bpm)": "heart_rate_bpm",
    "SpO2 Level (%)": "spo2_percent",
    "Body Temperature (°C)": "body_temp_c",
}
LABEL_COLUMNS = ["Heart Rate Alert", "SpO2 Level Alert", "Temperature Alert"]
CLASSES = ["Normal", "Warning", "Critical"]
SEED = 42


def derive_target(frame: pd.DataFrame) -> pd.Series:
    """Composite of provided synthetic flags, with no clinical ground truth."""
    if not frame[LABEL_COLUMNS].isin(["NORMAL", "ABNORMAL"]).all().all():
        raise ValueError("Unexpected or missing source alert label")
    count = frame[LABEL_COLUMNS].eq("ABNORMAL").sum(axis=1)
    return pd.Series(np.select([count.eq(0), count.eq(1)], ["Normal", "Warning"], default="Critical"), index=frame.index)


def split_groups(x: pd.DataFrame, y: pd.Series):
    """Stratify feature-tuple groups so repeated measurements cannot leak."""
    keys = list(x.columns)
    grouped = x.assign(target=y).groupby(keys, sort=True)["target"]
    if grouped.nunique().max() != 1:
        raise ValueError("Identical feature tuples have conflicting synthetic targets")
    groups = grouped.first().reset_index()
    train, rest = train_test_split(groups, test_size=0.4, random_state=SEED, stratify=groups.target)
    validation, test = train_test_split(rest, test_size=0.5, random_state=SEED, stratify=rest.target)
    feature_index = pd.MultiIndex.from_frame(x)
    masks = {}
    for name, subset in (("train", train), ("validation", validation), ("test", test)):
        masks[name] = feature_index.isin(pd.MultiIndex.from_frame(subset[keys]))
    if not np.all(sum(masks.values()) == 1):
        raise AssertionError("Partitions must be disjoint and cover all rows")
    return masks, {name: int(mask.sum()) for name, mask in masks.items()}, len(groups)


def train(csv_path: Path, output: Path) -> dict:
    frame = pd.read_csv(csv_path)
    x = frame[list(FEATURE_COLUMNS)].rename(columns=FEATURE_COLUMNS)
    if not all(pd.api.types.is_numeric_dtype(dtype) for dtype in x.dtypes) or not np.isfinite(x.to_numpy()).all():
        raise ValueError("Features must all be finite numeric measurements")
    y = derive_target(frame)
    masks, sizes, group_count = split_groups(x, y)
    output.mkdir(parents=True, exist_ok=True)
    candidates = {
        "logistic_regression": Pipeline([
            ("scale", StandardScaler()),
            ("classifier", LogisticRegression(max_iter=2000, class_weight="balanced", random_state=SEED)),
        ]),
        "random_forest": RandomForestClassifier(n_estimators=120, max_depth=14, min_samples_leaf=2,
                                                class_weight="balanced", n_jobs=1, random_state=SEED),
    }
    metrics = {}
    hashes = {}
    for name, estimator in candidates.items():
        estimator.fit(x[masks["train"]], y[masks["train"]])
        metrics[name] = {}
        for split in ("validation", "test"):
            predicted = estimator.predict(x[masks[split]])
            report = classification_report(y[masks[split]], predicted, labels=CLASSES, output_dict=True, zero_division=0)
            matrix = confusion_matrix(y[masks[split]], predicted, labels=CLASSES)
            metrics[name][split] = {"classification_report": report, "confusion_matrix": matrix.tolist()}
            figure, axis = plt.subplots(figsize=(6.4, 5.4))
            ConfusionMatrixDisplay(matrix, display_labels=CLASSES).plot(ax=axis, colorbar=False, cmap="Blues")
            axis.set_title(f"Synthetic labels · {name}\n{split} · independent feature groups")
            figure.tight_layout()
            figure.savefig(output / f"{name}_{split}_confusion.png", dpi=150)
            plt.close(figure)
        artifact = output / f"{name}.joblib"
        joblib.dump(estimator, artifact, compress=3)
        hashes[artifact.name] = hashlib.sha256(artifact.read_bytes()).hexdigest()
    # Validation selects the model. Test is reported once, never used to select.
    def ranking(name):
        report = metrics[name]["validation"]["classification_report"]
        return report["macro avg"]["f1-score"], report["Critical"]["recall"]
    selected = max(candidates, key=ranking)
    metadata = {
        "schema_version": 1, "model_id": "synthetic-health-v1", "educational_only": True,
        "allowed_source": "synthetic", "selected_model": selected,
        "selection": "Highest validation macro F1, then Critical recall as tie-break; test never used for selection",
        "feature_names": list(x.columns), "feature_ranges": {
            col: [float(x[col].min()), float(x[col].max())] for col in x.columns},
        "classes": CLASSES, "max_sample_age_seconds": 10.0, "seed": SEED,
        "dataset": {"file": csv_path.name, "sha256": hashlib.sha256(csv_path.read_bytes()).hexdigest(),
                    "rows": len(frame), "columns": len(frame.columns), "unique_feature_groups": group_count,
                    "source_provenance": "Existing repository CSV; upstream generator, license and clinical validation not established"},
        "label_definition": "Count ABNORMAL among Heart Rate Alert, SpO2 Level Alert and Temperature Alert: 0=Normal, 1=Warning, 2 or 3=Critical. These are custom educational labels, not diagnoses.",
        "label_counts": {key: int(value) for key, value in y.value_counts().items()},
        "split": {"method": "60/20/20 stratified unique feature tuples; all repetitions stay within one split", "rows": sizes},
        "excluded_features": [col for col in frame.columns if col not in FEATURE_COLUMNS],
        "artifact_sha256": hashes,
        "versions": {"python": platform.python_version(), "sklearn": sklearn.__version__, "numpy": np.__version__,
                     "pandas": pd.__version__, "joblib": joblib.__version__},
        "metrics": metrics,
        "limitations": [
            "Synthetic-label reconstruction only; no clinical ground truth, external validation or medical certainty.",
            "Class confidence is an uncalibrated model score, not a probability of disease or emergency.",
            "Dataset body temperature is not equivalent to GY906 surface/object temperature; live telemetry is rejected.",
            "No blood pressure, dehydration, falls, wearing status or acoustic accident detection is inferred.",
            "Dataset provenance and reuse license must be established before redistribution or use beyond this local academic experiment.",
        ],
    }
    (output / "metadata.json").write_text(json.dumps(metadata, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return metadata


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=ROOT / "datasets/synthetic_health.csv")
    parser.add_argument("--output", type=Path, default=ROOT / "models/synthetic-health-v1")
    args = parser.parse_args()
    metadata = train(args.dataset, args.output)
    print(json.dumps({"selected": metadata["selected_model"], "split": metadata["split"], "test": {
        name: {"macro_f1": entry["test"]["classification_report"]["macro avg"]["f1-score"],
               "critical_recall": entry["test"]["classification_report"]["Critical"]["recall"]}
        for name, entry in metadata["metrics"].items()}}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
