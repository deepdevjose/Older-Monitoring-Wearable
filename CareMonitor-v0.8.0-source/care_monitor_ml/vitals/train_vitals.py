"""Train offline alert-label baselines from the supplied synthetic CSV.

No inferred alert is sent to the Android app or the live server.
"""
import argparse
import hashlib
import importlib.metadata
import json
from pathlib import Path

import joblib
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from sklearn.dummy import DummyClassifier
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix, f1_score
from sklearn.model_selection import GroupShuffleSplit
from sklearn.tree import DecisionTreeClassifier, export_text

SEED = 20261020
TASKS = {
    "heart_rate": (["Heart Rate (bpm)"], "Heart Rate Alert"),
    "oxygen": (["SpO2 Level (%)"], "SpO2 Level Alert"),
    "blood_pressure": (["Systolic Blood Pressure (mmHg)", "Diastolic Blood Pressure (mmHg)"], "Blood Pressure Alert"),
    "body_temperature": (["Body Temperature (°C)"], "Temperature Alert"),
}
LABELS = ["NORMAL", "ABNORMAL"]
FEATURES = [name for names, _ in TASKS.values() for name in names]


def scores(truth, predicted):
    return {
        "accuracy": float(accuracy_score(truth, predicted)),
        "macro_f1": float(f1_score(truth, predicted, average="macro", zero_division=0)),
        "confusion_matrix_order": LABELS,
        "confusion_matrix": confusion_matrix(truth, predicted, labels=LABELS).tolist(),
        "classification_report": classification_report(truth, predicted, labels=LABELS, output_dict=True, zero_division=0),
    }


def tree_json(model, features, training):
    tree = model.tree_
    return {
        "format": "care-monitor-offline-tree-v1",
        "synthetic_only": True,
        "deployed": False,
        "comparison_input_type": "float32",
        "features": features,
        "classes": model.classes_.tolist(),
        "supported_training_ranges": {name: [float(training[name].min()), float(training[name].max())] for name in features},
        "nodes": [{
            "left": int(tree.children_left[i]), "right": int(tree.children_right[i]),
            "feature": int(tree.feature[i]), "threshold": float(tree.threshold[i]),
            "predicted_class": str(model.classes_[int(np.argmax(tree.value[i][0]))]),
        } for i in range(tree.node_count)],
        "output_meaning": "Agreement with a supplied synthetic alert label, not clinical normality",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    data = pd.read_csv(args.csv)
    expected = FEATURES + [target for _, target in TASKS.values()]
    if not set(expected).issubset(data.columns):
        raise ValueError("Missing required source columns")
    for name in FEATURES:
        data[name] = pd.to_numeric(data[name], errors="coerce")
    for _, name in TASKS.values():
        data[name] = data[name].astype(str).str.strip().str.upper()
    valid = np.isfinite(data[FEATURES]).all(axis=1)
    for _, name in TASKS.values():
        valid &= data[name].isin(LABELS)
    clean = data.loc[valid].copy().reset_index(names="source_row")
    if len(clean) < 100:
        raise ValueError("Too few complete labeled rows")

    # Keep every identical five-vital predictor vector in one partition.
    # Patient Number is a unique synthetic row ID, not verified subject identity.
    signatures = clean[FEATURES].apply(lambda row: hashlib.sha256(
        json.dumps(row.tolist(), separators=(",", ":")).encode()).hexdigest(), axis=1)
    group_ids = signatures.to_numpy()
    dev, test = next(GroupShuffleSplit(n_splits=1, test_size=0.2, random_state=SEED).split(clean, groups=group_ids))
    train_relative, val_relative = next(GroupShuffleSplit(n_splits=1, test_size=0.25, random_state=SEED + 1).split(
        clean.iloc[dev], groups=group_ids[dev]))
    train, val = dev[train_relative], dev[val_relative]
    sets = {"train": train, "validation": val, "test": test}
    group_sets = {name: set(group_ids[rows]) for name, rows in sets.items()}
    assert not (group_sets["train"] & group_sets["validation"] or group_sets["train"] & group_sets["test"]
                or group_sets["validation"] & group_sets["test"])
    partition = np.full(len(clean), "", dtype=object)
    for name, rows in sets.items():
        partition[rows] = name
    pd.DataFrame({"source_row": clean["source_row"], "predictor_group_sha256": signatures,
                  "partition": partition}).to_csv(args.out / "split_membership.csv", index=False)

    report = {
        "dataset": args.csv.name, "sha256": hashlib.sha256(args.csv.read_bytes()).hexdigest(),
        "synthetic_only": True, "seed": SEED, "input_rows": len(data), "usable_rows": len(clean),
        "rejected_rows": int((~valid).sum()), "unique_five_vital_groups": int(signatures.nunique()),
        "duplicate_five_vital_rows": int(signatures.duplicated().sum()),
        "split_method": "60/20/20 approximate GroupShuffleSplit by exact five-vital predictor vector",
        "split_rows": {name: len(rows) for name, rows in sets.items()},
        "split_groups": {name: len(groups) for name, groups in group_sets.items()},
        "cross_split_identical_five_vital_groups": 0,
        "real_subject_disjoint_validation": False,
        "excluded_predictors": ["Patient Number", "Predicted Disease", "Data Accuracy (%)", "Fall Detection", "all alert-label columns"],
        "test_used_for_model_selection": False, "deployed_to_app_or_server": False,
        "versions": {name: importlib.metadata.version(name) for name in ["scikit-learn", "numpy", "pandas", "joblib"]},
        "tasks": {},
    }
    models = {}
    plot, axes = plt.subplots(1, 4, figsize=(13, 3.7))
    for ax, (task, (features, target)) in zip(axes, TASKS.items()):
        validation = []
        for depth in [1, 2, 3]:
            candidate = DecisionTreeClassifier(max_depth=depth, min_samples_leaf=20, random_state=SEED)
            candidate.fit(clean.iloc[train][features], clean.iloc[train][target])
            validation.append({"max_depth": depth, "macro_f1": float(f1_score(
                clean.iloc[val][target], candidate.predict(clean.iloc[val][features]), average="macro", zero_division=0))})
        best = max(validation, key=lambda item: (item["macro_f1"], -item["max_depth"]))
        final = DecisionTreeClassifier(max_depth=best["max_depth"], min_samples_leaf=20, random_state=SEED)
        final.fit(clean.iloc[dev][features], clean.iloc[dev][target])
        truth = clean.iloc[test][target]
        predicted = final.predict(clean.iloc[test][features])
        dummy = DummyClassifier(strategy="most_frequent").fit(clean.iloc[dev][features], clean.iloc[dev][target])
        result = {
            "features": features, "target": target, "validation_candidates": validation,
            "selected_max_depth": best["max_depth"], "final_refit_rows": len(dev),
            "label_counts_by_split": {name: clean.iloc[rows][target].value_counts().to_dict() for name, rows in sets.items()},
            "test": scores(truth, predicted), "majority_baseline_test": scores(truth, dummy.predict(clean.iloc[test][features])),
            "learned_rules": export_text(final, feature_names=features),
        }
        report["tasks"][task] = result
        models[task] = final
        (args.out / (task + "_tree.json")).write_text(json.dumps(tree_json(final, features, clean.iloc[dev]), indent=2), encoding="utf-8")
        matrix = np.array(result["test"]["confusion_matrix"])
        ax.imshow(matrix, cmap="Blues")
        for row in range(2):
            for col in range(2):
                ax.text(col, row, str(matrix[row, col]), ha="center", va="center", color="white" if matrix[row,col] > matrix.max()/2 else "#153d3b")
        ax.set_xticks([0,1], ["Normal", "Abnormal"], fontsize=9)
        ax.set_yticks([0,1], ["Normal", "Abnormal"], fontsize=9)
        ax.set_title(task.replace("_", " ").title(), fontsize=11)
        ax.set_xlabel("Predicted synthetic label")
        if ax is axes[0]: ax.set_ylabel("Supplied synthetic label")
    plot.suptitle("Synthetic alert-label holdout: rule reproduction only", fontsize=14)
    plot.tight_layout()
    plot.savefig(args.out / "synthetic_holdout.png", dpi=160)
    plt.close(plot)
    joblib.dump({"models": models, "seed": SEED, "synthetic_only": True, "feature_spec": TASKS}, args.out / "vitals_baseline.joblib")
    (args.out / "metrics.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({"usable_rows": len(clean), "split_rows": report["split_rows"],
        "tasks": {task: value["test"] for task, value in report["tasks"].items()}}, indent=2))


if __name__ == "__main__":
    main()
