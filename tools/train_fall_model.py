#!/usr/bin/env python3
"""Classify complete public SisFall records offline, with subject-disjoint splits."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import platform
import re
import sys
import tarfile

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
from sklearn.model_selection import GroupShuffleSplit
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

ROOT = Path(__file__).resolve().parents[1]
FS = 200
SEED = 42
FEATURES = ["magnitude_max_g", "magnitude_min_g", "magnitude_mean_g", "magnitude_std_g",
            "magnitude_p95_g", "jerk_rms_g_s", "orientation_change_deg"]
CLASSES = ["adl_record", "fall_record"]
NAME = re.compile(r"^([DF]\d{2})_((?:SA|SE)\d{2})_(R\d{2})\.txt$")
LIMITATIONS = [
    "Offline classification of a complete public-dataset record; not an online fall detector or event timestamp.",
    "Simulated falls and controlled ADLs do not establish accuracy for accidental falls or patients.",
    "SisFall ADXL345 was waist-mounted at 200 Hz; the current wearable MPU6050 snapshots near 1 Hz are incompatible.",
    "The score is uncalibrated class confidence, not probability of a person's fall, injury or emergency.",
    "Age-group fall support is limited: most older participants did not simulate falls; no elder clinical claim is supported.",
    "No real telemetry, raw patient audio, location or individual health data is accepted by this model.",
]


def extract_features(acceleration_g: np.ndarray, sample_rate_hz: int = FS) -> dict:
    """Summarize the entire record; no duration, subject, activity or labels as inputs."""
    a = np.asarray(acceleration_g, dtype=np.float64)
    if sample_rate_hz != FS or isinstance(sample_rate_hz, bool):
        raise ValueError("SisFall replay requires exactly 200 Hz")
    if a.ndim != 2 or a.shape[1] != 3 or not 2 * FS <= len(a) <= 180 * FS:
        raise ValueError("A complete record requires 2..180 seconds of three acceleration axes")
    if not np.isfinite(a).all() or (np.abs(a) > 16).any():
        raise ValueError("Acceleration must be finite and within ADXL345 +/-16 g")
    magnitude = np.linalg.norm(a, axis=1)
    jerk = np.diff(a, axis=0) * FS
    first, last = a[:FS].mean(axis=0), a[-FS:].mean(axis=0)
    denominator = np.linalg.norm(first) * np.linalg.norm(last)
    if denominator < 1e-12:
        raise ValueError("Start/end mean acceleration cannot define an orientation")
    angle = np.degrees(np.arccos(np.clip(np.dot(first, last) / denominator, -1, 1)))
    return dict(zip(FEATURES, map(float, [
        magnitude.max(), magnitude.min(), magnitude.mean(), magnitude.std(ddof=0),
        np.percentile(magnitude, 95), np.sqrt(np.mean(np.sum(jerk * jerk, axis=1))), angle,
    ])))


def read_archive(path: Path) -> pd.DataFrame:
    """Read members in memory, without extracting the tar archive to the filesystem."""
    rows = []
    excluded = []
    seen = set()
    with tarfile.open(path, mode="r|gz") as archive:
        for member in archive:
            match = NAME.fullmatch(Path(member.name).name)
            if not match or not member.isfile():
                continue
            activity, subject, trial = match.groups()
            if member.size > 8 * 1024 * 1024:
                raise ValueError("Unexpected dataset member size")
            if Path(member.name).parent.name != subject:
                # The received archive contains five D17_SE15 files in SA15.
                # Do not guess the participant or let ambiguous identity leak
                # across subject partitions. Preserve the exclusion in metadata.
                excluded.append({"record_id": Path(member.name).name,
                                 "folder_subject": Path(member.name).parent.name,
                                 "filename_subject": subject,
                                 "reason": "Conflicting participant IDs in filename and containing folder"})
                continue
            record_id = Path(member.name).name
            if record_id in seen:
                raise ValueError("Duplicate record in dataset archive")
            seen.add(record_id)
            stream = archive.extractfile(member)
            # The final of 9 columns ends with ';'. Only the first ADXL345
            # columns are parsed, so no replacement/reinterpretation of other
            # sensors is necessary. The complete record is bounded in memory.
            lines = (line for line in stream.read().splitlines() if line.strip())
            raw = np.loadtxt(lines, delimiter=",", usecols=(0, 1, 2), dtype=np.float64)
            if not np.equal(raw, np.rint(raw)).all():
                raise ValueError("Expected integer ADXL345 ADC samples")
            acceleration_g = raw / 256.0  # 2 * 16 / 2**13
            rows.append({"record_id": record_id, "subject": subject,
                         "target": "fall_record" if activity.startswith("F") else "adl_record",
                         "samples": len(raw), **extract_features(acceleration_g)})
            if len(rows) % 500 == 0:
                print(f"Read {len(rows)} public records", file=sys.stderr, flush=True)
    if len(rows) < 100 or len({row["subject"] for row in rows}) < 10:
        raise ValueError("Incomplete dataset archive")
    frame = pd.DataFrame(rows).sort_values("record_id").reset_index(drop=True)
    frame.attrs["excluded_ambiguous_records"] = excluded
    return frame


def subject_split(frame: pd.DataFrame) -> dict[str, np.ndarray]:
    first = GroupShuffleSplit(n_splits=1, test_size=0.4, random_state=SEED)
    train, rest = next(first.split(frame, groups=frame.subject))
    second = GroupShuffleSplit(n_splits=1, test_size=0.5, random_state=SEED)
    validation_rest, test_rest = next(second.split(frame.iloc[rest], groups=frame.iloc[rest].subject))
    partitions = {"train": train, "validation": rest[validation_rest], "test": rest[test_rest]}
    subjects = [set(frame.iloc[indices].subject) for indices in partitions.values()]
    if any(subjects[i] & subjects[j] for i in range(3) for j in range(i + 1, 3)):
        raise AssertionError("A subject appears in multiple partitions")
    for split, indices in partitions.items():
        if set(frame.iloc[indices].target) != set(CLASSES):
            raise ValueError(f"Partition {split} lacks one class; do not silently redraw for better metrics")
    return partitions


def evaluate(estimator, frame: pd.DataFrame, output: Path, prefix: str) -> dict:
    predicted = estimator.predict(frame[FEATURES])
    matrix = confusion_matrix(frame.target, predicted, labels=CLASSES)
    result = {"classification_report": classification_report(frame.target, predicted, labels=CLASSES,
                                                              output_dict=True, zero_division=0),
              "confusion_matrix": matrix.tolist()}
    figure, axis = plt.subplots(figsize=(6.5, 5.1))
    ConfusionMatrixDisplay(matrix, display_labels=CLASSES).plot(ax=axis, colorbar=False, cmap="Purples")
    axis.set_title("SisFall complete records · offline only\n" + prefix.replace("_", " "))
    figure.tight_layout()
    figure.savefig(output / (prefix + "_confusion.png"), dpi=150)
    plt.close(figure)
    return result


def train(archive_path: Path, output: Path) -> dict:
    frame = read_archive(archive_path)
    partitions = subject_split(frame)
    output.mkdir(parents=True, exist_ok=True)
    train_frame = frame.iloc[partitions["train"]]
    estimators = {
        "logistic_regression": Pipeline([("scale", StandardScaler()),
            ("classifier", LogisticRegression(class_weight="balanced", max_iter=2000, random_state=SEED))]),
        "random_forest": RandomForestClassifier(n_estimators=160, max_depth=12, min_samples_leaf=3,
                                                class_weight="balanced", n_jobs=1, random_state=SEED),
    }
    metrics, hashes, subgroup = {}, {}, {}
    for name, estimator in estimators.items():
        estimator.fit(train_frame[FEATURES], train_frame.target)
        metrics[name] = {}
        for split in ("validation", "test"):
            subset = frame.iloc[partitions[split]]
            metrics[name][split] = evaluate(estimator, subset, output, name + "_" + split)
        artifact = output / (name + ".joblib")
        joblib.dump(estimator, artifact, compress=3)
        hashes[artifact.name] = hashlib.sha256(artifact.read_bytes()).hexdigest()
    def ranking(name):
        report = metrics[name]["validation"]["classification_report"]
        return report["macro avg"]["f1-score"], report["fall_record"]["recall"], name == "logistic_regression"
    selected = max(estimators, key=ranking)
    test_frame = frame.iloc[partitions["test"]]
    for prefix, label in (("SA", "adult_group"), ("SE", "older_group")):
        subset = test_frame[test_frame.subject.str.startswith(prefix)]
        if len(subset):
            predicted = estimators[selected].predict(subset[FEATURES])
            subgroup[label] = {"records": len(subset), "subjects": subset.subject.nunique(),
                               "class_counts": {key: int(value) for key, value in subset.target.value_counts().items()},
                               "fall_recall_evaluable": bool(subset.target.eq("fall_record").any()),
                               "unsupported_class_metrics": [target for target in CLASSES if not subset.target.eq(target).any()],
                               "classification_report": classification_report(subset.target, predicted, labels=CLASSES,
                                                                              output_dict=True, zero_division=0)}
    split_metadata = {}
    for name, indices in partitions.items():
        subset = frame.iloc[indices]
        split_metadata[name] = {"records": len(indices), "subjects": sorted(subset.subject.unique().tolist()),
                                "subject_count": subset.subject.nunique(),
                                "class_counts": {key: int(value) for key, value in subset.target.value_counts().items()}}
    # Public anonymous record IDs and feature summaries aid reproducibility;
    # no demographics, names, raw signals or app-user samples are copied.
    fixtures = []
    for target in CLASSES:
        row = test_frame[test_frame.target.eq(target)].iloc[0]
        fixtures.append({"record_id": row.record_id, "expected_dataset_label": target,
                         "split": "test", "source": "dataset_replay", "sample_rate_hz": FS,
                         "record_complete": True, "features": {name: float(row[name]) for name in FEATURES}})
    (output / "replay_examples.json").write_text(json.dumps({"educational_only": True, "examples": fixtures},
                                                              indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    metadata = {
        "schema_version": 1, "model_id": "sisfall-v1", "educational_only": True,
        "allowed_source": "dataset_replay", "task": "complete_record_classification",
        "selected_model": selected, "feature_names": FEATURES, "classes": CLASSES,
        "sample_rate_hz": FS, "sensor": "ADXL345", "mounting": "waist", "seed": SEED,
        "adc_conversion": "acceleration_g = raw * (2*16)/(2**13) = raw/256; first three columns only",
        "feature_definition": {
            "magnitude": "Euclidean norm of raw/256 acceleration, including gravity; statistics over the entire record; std ddof=0; p95 linear quantile",
            "jerk_rms_g_s": "sqrt(mean(sum((diff(acceleration_g,axis=0)*200)**2,axis=1)))",
            "orientation_change_deg": "acos of normalized dot product of mean acceleration in first and last 200 samples, converted to degrees",
            "preprocessing": "No filtering, resampling or event localization; all features require the complete record",
        },
        "dataset": {"file": archive_path.name, "sha256": hashlib.sha256(archive_path.read_bytes()).hexdigest(),
                    "records": len(frame), "subject_count": frame.subject.nunique(),
                    "excluded_ambiguous_records": frame.attrs["excluded_ambiguous_records"],
                    "label_definition": "Filename prefix F=fall_record, D=adl_record; these label the full activity, not every sample or a precise event time",
                    "source_2017": "https://pmc.ncbi.nlm.nih.gov/articles/PMC5298771/",
                    "source_2018": "https://www.mdpi.com/1424-8220/18/4/1101"},
        "split": {"method": "GroupShuffleSplit 60/20/20 of subjects, seed42 twice, no subject overlap; no redraw based on metrics", **split_metadata},
        "selection": "Highest validation macro F1, then fall_record recall; exact tie selects simpler logistic regression. Test is not used for model selection",
        "excluded_features": ["subject ID", "trial ID", "activity code", "filename", "record duration", "sample count", "demographics", "other sensor columns"],
        "artifact_sha256": hashes, "metrics": metrics, "selected_test_subgroups": subgroup,
        "versions": {"python": platform.python_version(), "sklearn": sklearn.__version__, "numpy": np.__version__,
                     "pandas": pd.__version__, "joblib": joblib.__version__, "matplotlib": matplotlib.__version__},
        "limitations": LIMITATIONS,
    }
    (output / "metadata.json").write_text(json.dumps(metadata, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return metadata


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", "--dataset", dest="dataset", type=Path,
                        help="Optional explicit tar path; default verifies fragments and prepares .local/datasets cache")
    parser.add_argument("--output", type=Path, default=ROOT / "models/sisfall-v1")
    args = parser.parse_args()
    if args.dataset is None:
        if __package__:
            from .prepare_sisfall import prepare_archive
        else:
            from prepare_sisfall import prepare_archive
        args.dataset = prepare_archive()
    metadata = train(args.dataset, args.output)
    print(json.dumps({"selected": metadata["selected_model"], "split": metadata["split"],
                      "test": {name: {"macro_f1": result["test"]["classification_report"]["macro avg"]["f1-score"],
                                      "fall_recall": result["test"]["classification_report"]["fall_record"]["recall"]}
                               for name, result in metadata["metrics"].items()},
                      "subgroups": metadata["selected_test_subgroups"]}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
