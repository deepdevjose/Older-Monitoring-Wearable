"""Isolated same-task synthetic Decision Tree / Random Forest comparison.

Requires the previously recorded split to match byte-reproducible row membership.
Does not read a server database, publish artifacts, or modify deployed models.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import platform
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from sklearn.dummy import DummyClassifier
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix, f1_score, log_loss
from sklearn.model_selection import GroupShuffleSplit
from sklearn.tree import DecisionTreeClassifier

SEED = 20261020
DATASET_SHA256 = "35240b10eb5a1207d6404e1af06359706afb6796cca089a4e9c3d8b9613a7adb"
VERSION = "synthetic-vitals-dt-rf-20261026"
LABELS = ["NORMAL", "ABNORMAL"]
TASKS = {
    "heart_rate": (["Heart Rate (bpm)"], "Heart Rate Alert"),
    "oxygen": (["SpO2 Level (%)"], "SpO2 Level Alert"),
    "blood_pressure": (["Systolic Blood Pressure (mmHg)", "Diastolic Blood Pressure (mmHg)"], "Blood Pressure Alert"),
    "body_temperature": (["Body Temperature (°C)"], "Temperature Alert"),
}
FEATURES = [feature for features, _ in TASKS.values() for feature in features]
CONFIDENCE_MEANING = "Empirical training-leaf class proportion; not a calibrated clinical probability"
LIMITATIONS = [
    "Only supplied synthetic NORMAL/ABNORMAL targets are evaluated; Warning/Critical labels do not exist here.",
    "Perfect scores can reflect deterministic supplied labels rather than physiological generalisation.",
    "Exact five-vital groups are separated, but individual input values repeat across partitions.",
    "The synthetic Patient Number is a row identifier, not verified participant identity.",
    "No independent assessment hidden set, clinical validation, paired-reference training or physical-sensor test is performed.",
    "Probability values are uncalibrated model outputs; low synthetic error does not establish real-device calibration.",
    "Artifacts are an isolated assessment candidate and do not replace working v0.7.4 models or firmware.",
]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + "\n", encoding="utf-8")


def load_and_partition(csv: Path, reference: Path):
    if sha256(csv) != DATASET_SHA256:
        raise ValueError("Dataset hash differs from the audited synthetic training dataset")
    data = pd.read_csv(csv)
    required = FEATURES + [target for _, target in TASKS.values()]
    if not set(required).issubset(data.columns):
        raise ValueError("Missing required source columns")
    for feature in FEATURES:
        data[feature] = pd.to_numeric(data[feature], errors="coerce")
    for _, target in TASKS.values():
        data[target] = data[target].astype(str).str.strip().str.upper()
    valid = np.isfinite(data[FEATURES]).all(axis=1)
    for _, target in TASKS.values():
        valid &= data[target].isin(LABELS)
    clean = data.loc[valid].copy().reset_index(names="source_row")
    signatures = clean[FEATURES].apply(lambda row: hashlib.sha256(
        json.dumps(row.tolist(), separators=(",", ":")).encode()).hexdigest(), axis=1)
    groups = signatures.to_numpy()
    dev, test = next(GroupShuffleSplit(n_splits=1, test_size=0.2, random_state=SEED).split(clean, groups=groups))
    train_rel, val_rel = next(GroupShuffleSplit(n_splits=1, test_size=0.25, random_state=SEED + 1).split(
        clean.iloc[dev], groups=groups[dev]))
    sets = {"train": dev[train_rel], "validation": dev[val_rel], "test": test}
    group_sets = {name: set(groups[rows]) for name, rows in sets.items()}
    for left, right in [("train", "validation"), ("train", "test"), ("validation", "test")]:
        if group_sets[left] & group_sets[right]:
            raise ValueError("Cross-split identical predictor-vector leakage")
    membership = np.full(len(clean), "", dtype=object)
    for name, rows in sets.items():
        membership[rows] = name
    actual = pd.DataFrame({"source_row": clean["source_row"], "predictor_group_sha256": signatures, "partition": membership})
    recorded = pd.read_csv(reference)
    pd.testing.assert_frame_equal(actual, recorded, check_dtype=True, check_exact=True)
    return data, clean, sets, dev, actual, {
        "input_rows": len(data), "usable_rows": len(clean), "rejected_rows": int((~valid).sum()),
        "split_rows": {name: len(rows) for name, rows in sets.items()},
        "split_groups": {name: len(values) for name, values in group_sets.items()},
        "unique_five_vital_groups": len(set(groups)), "duplicate_five_vital_rows": int(signatures.duplicated().sum()),
        "cross_split_identical_five_vital_groups": 0,
        "recorded_split_exact_match": True, "recorded_split_sha256": sha256(reference),
        "real_subject_disjoint_validation": False,
    }


def estimator(family: str, depth: int):
    if family == "decision_tree":
        return DecisionTreeClassifier(max_depth=depth, min_samples_leaf=20, random_state=SEED)
    return RandomForestClassifier(n_estimators=100, max_depth=depth, min_samples_leaf=20,
                                  max_features=1.0, bootstrap=True, class_weight=None,
                                  random_state=SEED, n_jobs=2)


def ordered_probabilities(model, frame):
    probabilities = model.predict_proba(frame)
    return probabilities[:, [list(model.classes_).index(label) for label in LABELS]]


def evaluate(truth, predicted, probabilities=None):
    matrix = confusion_matrix(truth, predicted, labels=LABELS)
    report = classification_report(truth, predicted, labels=LABELS, output_dict=True, zero_division=0)
    result = {
        "accuracy": float(accuracy_score(truth, predicted)),
        "macro_f1": float(f1_score(truth, predicted, average="macro", zero_division=0)),
        "macro_precision": float(report["macro avg"]["precision"]),
        "macro_recall": float(report["macro avg"]["recall"]),
        "abnormal_recall": float(report["ABNORMAL"]["recall"]),
        "confusion_matrix_order": LABELS, "confusion_matrix": matrix.tolist(), "classification_report": report,
        "false_abnormal_rate": float(matrix[0, 1] / matrix[0].sum()),
        "missed_abnormal_rate": float(matrix[1, 0] / matrix[1].sum()),
        "critical_recall": None,
        "critical_recall_reason": "No Critical label exists in the supplied target",
    }
    if probabilities is not None:
        # log_loss expects probability columns in lexical label order.
        result["probability_diagnostics"] = {
            "abnormal_brier_score": float(np.mean((probabilities[:, 1] - (np.asarray(truth) == "ABNORMAL")) ** 2)),
            "log_loss": float(log_loss(truth, probabilities[:, ::-1], labels=["ABNORMAL", "NORMAL"])),
            "mean_predicted_class_probability": float(probabilities.max(axis=1).mean()),
            "calibration_performed": False,
            "interpretation": "Synthetic holdout diagnostics only; not evidence of real-device probability calibration",
        }
    return result


def export_tree(model, task: str, features: list[str], development: pd.DataFrame):
    tree = model.tree_
    indices = [list(model.classes_).index(label) for label in LABELS]
    nodes = []
    for index in range(tree.node_count):
        weights = np.asarray(tree.value[index][0], dtype=float)[indices]
        probabilities = weights / weights.sum()
        # sklearn resolves ties in estimator.classes_ order, not display order.
        prediction = str(model.classes_[int(np.argmax(tree.value[index][0]))])
        nodes.append({"left": int(tree.children_left[index]), "right": int(tree.children_right[index]),
                      "feature": int(tree.feature[index]), "threshold": float(tree.threshold[index]),
                      "predicted_class": prediction, "probabilities": probabilities.tolist(),
                      "training_samples": int(tree.n_node_samples[index])})
    return {
        "format": "care-monitor-assessment-probability-tree-v1", "model_version": VERSION,
        "task": task, "estimator": "DecisionTreeClassifier", "synthetic_only": True,
        "clinical_validation": False, "confidence_calibrated": False, "deployed": False,
        "dataset_sha256": DATASET_SHA256, "comparison_input_type": "float32",
        "features": features, "classes": LABELS,
        "supported_training_ranges": {feature: [float(development[feature].min()), float(development[feature].max())] for feature in features},
        "confidence_semantics": CONFIDENCE_MEANING,
        "output_meaning": "Agreement with supplied synthetic binary labels, not clinical normality",
        "nodes": nodes,
    }


def json_predict(model: dict, frame: pd.DataFrame):
    rows = frame[model["features"]].to_numpy(dtype=np.float32)
    predictions, probabilities = [], []
    for row in rows:
        node = model["nodes"][0]
        for _ in model["nodes"]:
            if node["left"] == node["right"]:
                predictions.append(node["predicted_class"])
                probabilities.append(node["probabilities"])
                break
            node = model["nodes"][node["left"] if row[node["feature"]] <= node["threshold"] else node["right"]]
        else:
            raise ValueError("Model traversal did not terminate")
    return np.asarray(predictions), np.asarray(probabilities)


def plot_confusions(task: str, results: dict, out: Path):
    figure, axes = plt.subplots(1, 2, figsize=(9, 3.8))
    for ax, family in zip(axes, ["decision_tree", "random_forest"]):
        matrix = np.asarray(results[family]["test"]["confusion_matrix"])
        ax.imshow(matrix, cmap="Blues")
        for row in range(2):
            for col in range(2):
                ax.text(col, row, str(matrix[row, col]), ha="center", va="center", color="white" if matrix[row, col] > matrix.max() / 2 else "#173e46")
        ax.set_xticks([0, 1], LABELS, fontsize=9)
        ax.set_yticks([0, 1], LABELS, fontsize=9)
        ax.set_xlabel("Predicted synthetic label")
        ax.set_ylabel("Supplied synthetic label")
        ax.set_title(family.replace("_", " ").title())
    figure.suptitle(task.replace("_", " ").title() + " | identical 12,002-row synthetic holdout")
    figure.tight_layout()
    figure.savefig(out / (task + "_confusion.png"), dpi=160)
    plt.close(figure)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", type=Path, required=True)
    parser.add_argument("--reference-split", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    data, clean, sets, development, membership, split = load_and_partition(args.csv, args.reference_split)
    # Copy the exact audited membership file after verifying regenerated membership.
    shutil.copyfile(args.reference_split, args.out / "split_membership.csv")
    selection_policy = "Select depth by validation macro-F1, tie favouring lower depth; select family by validation macro-F1, tie favouring interpretable Decision Tree. Refit each family's selected depth on train+validation; test never selects models."
    report = {
        "run_utc": datetime.now(timezone.utc).isoformat(), "model_version": VERSION,
        "dataset": args.csv.name, "dataset_sha256": DATASET_SHA256, "synthetic_only": True,
        "seed": SEED, **split, "split_method": "Exact previous GroupShuffleSplit membership by complete five-vital vector",
        "test_used_for_model_selection": False, "selection_policy": selection_policy,
        "same_task_two_classifier_comparison": True,
        "excluded_predictors": ["Patient Number", "Predicted Disease", "Data Accuracy (%)", "Fall Detection", "all target-label columns"],
        "limitations": LIMITATIONS, "deployed_to_working_app_or_server": False,
        "versions": {name: importlib.metadata.version(name) for name in ["scikit-learn", "numpy", "pandas", "matplotlib"]},
        "python": platform.python_version(), "command_arguments": sys.argv[1:], "tasks": {},
    }
    manifest = {
        "format": "care-monitor-assessment-probability-manifest-v1", "model_version": VERSION,
        "synthetic_only": True, "clinical_validation": False, "confidence_calibrated": False,
        "classes": LABELS, "dataset_sha256": DATASET_SHA256, "comparison_evidence": "metrics.json",
        "confidence_semantics": CONFIDENCE_MEANING, "models": {},
    }
    rows, verification = [], {}
    for task, (features, target) in TASKS.items():
        families, fitted = {}, {}
        for family, depths in [("decision_tree", [1, 2, 3]), ("random_forest", [2, 4, 8])]:
            candidates = []
            for depth in depths:
                model = estimator(family, depth).fit(clean.iloc[sets["train"]][features], clean.iloc[sets["train"]][target])
                val_result = evaluate(clean.iloc[sets["validation"]][target], model.predict(clean.iloc[sets["validation"]][features]))
                candidates.append({"max_depth": depth, "validation_accuracy": val_result["accuracy"], "validation_macro_f1": val_result["macro_f1"]})
            best = max(candidates, key=lambda item: (item["validation_macro_f1"], -item["max_depth"]))
            final = estimator(family, best["max_depth"]).fit(clean.iloc[development][features], clean.iloc[development][target])
            test_frame = clean.iloc[sets["test"]][features]
            test = evaluate(clean.iloc[sets["test"]][target], final.predict(test_frame), ordered_probabilities(final, test_frame))
            families[family] = {"validation_candidates": candidates, "selected_max_depth": best["max_depth"],
                                "selected_validation_macro_f1": best["validation_macro_f1"],
                                "parameters": final.get_params(), "final_refit_rows": len(development), "test": test}
            fitted[family] = final
            rows.append({"task": task, "classifier": family, "selected_depth": best["max_depth"],
                         "validation_macro_f1": best["validation_macro_f1"], "test_rows": len(sets["test"]),
                         **{key: test[key] for key in ["accuracy", "macro_precision", "macro_recall", "macro_f1", "abnormal_recall", "false_abnormal_rate", "missed_abnormal_rate"]}})
        chosen = max(families, key=lambda family: (families[family]["selected_validation_macro_f1"], family == "decision_tree"))
        if chosen != "decision_tree":
            raise ValueError("Random Forest won validation; do not mislabel a DT export as the selected model")
        dummy = DummyClassifier(strategy="most_frequent").fit(clean.iloc[development][features], clean.iloc[development][target])
        report["tasks"][task] = {
            "features": features, "target": target, "classes": LABELS, "selected_classifier": chosen,
            "selection_reason": "Validation macro-F1 tie; prefer the simpler interpretable Decision Tree",
            "label_counts_by_split": {name: clean.iloc[index][target].value_counts().to_dict() for name, index in sets.items()},
            "single_feature_test_values_also_in_development": {
                feature: {"test_unique": int(clean.iloc[sets["test"]][feature].nunique()),
                          "overlap_unique": len(set(clean.iloc[sets["test"]][feature]) & set(clean.iloc[development][feature]))} for feature in features},
            "classifiers": families,
            "majority_baseline_test": evaluate(clean.iloc[sets["test"]][target], dummy.predict(clean.iloc[sets["test"]][features])),
        }
        artifact = export_tree(fitted[chosen], task, features, clean.iloc[development])
        labels, probabilities = json_predict(artifact, clean)
        np.testing.assert_array_equal(labels, fitted[chosen].predict(clean[features]))
        np.testing.assert_allclose(probabilities, ordered_probabilities(fitted[chosen], clean[features]), rtol=0, atol=1e-12)
        artifact_path = args.out / (task + "_tree.json")
        write_json(artifact_path, artifact)
        manifest["models"][task] = {"file": artifact_path.name, "sha256": sha256(artifact_path),
                                    "estimator": "DecisionTreeClassifier", "features": features}
        verification[task] = {"synthetic_rows_checked": len(clean), "class_predictions_match": True,
                              "probabilities_match_atol": 1e-12, "split_match_verified_before_training": True}
        plot_confusions(task, families, args.out)
        print(json.dumps({"task": task, "selected": chosen, "dt_test_macro_f1": families["decision_tree"]["test"]["macro_f1"],
                          "rf_test_macro_f1": families["random_forest"]["test"]["macro_f1"]}), flush=True)
    write_json(args.out / "metrics.json", report)
    write_json(args.out / "manifest.json", manifest)
    write_json(args.out / "split_manifest.json", {"dataset_sha256": DATASET_SHA256, "seed": SEED, **split,
                                                "output_membership_sha256": sha256(args.out / "split_membership.csv")})
    write_json(args.out / "export_verification.json", {"passed": True, "tasks": verification,
                                                     "total_synthetic_prediction_checks": sum(v["synthetic_rows_checked"] for v in verification.values()),
                                                     "physical_sensor_testing": False, "clinical_validation": False})
    pd.DataFrame(rows).to_csv(args.out / "comparison.csv", index=False)
    text = "SYNTHETIC SAME-TASK MODEL COMPARISON\n\n" + selection_policy + "\n\n" + "\n".join(LIMITATIONS)
    text += "\n\nDataset SHA256: " + DATASET_SHA256 + "\nExact previous split verified: " + str(split["split_rows"])
    text += "\n\nConfidence is the model probability assigned to the predicted class, not probability of clinical safety."
    text += "\nDecision Tree exports use empirical leaf proportions; pure synthetic leaves may return 1.0. No probability calibration was fitted.\n"
    (args.out / "MODEL_CARD.txt").write_text(text, encoding="utf-8")
    print(json.dumps({"completed": True, "output": str(args.out), "dataset_rows": len(clean), "split_rows": split["split_rows"],
                      "export_parity_rows": 240000, "real_sensor_training": False, "working_deployment_changed": False}), flush=True)


if __name__ == "__main__":
    main()
