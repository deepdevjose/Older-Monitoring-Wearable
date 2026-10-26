"""Synthetic-only regression checks for probability export and split safeguards."""
import tempfile
import unittest
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.tree import DecisionTreeClassifier

from compare_classifiers import evaluate, export_tree, json_predict, load_and_partition, ordered_probabilities, write_json


class ProbabilityExportChecks(unittest.TestCase):
    def setUp(self):
        self.feature = "Heart Rate (bpm)"
        self.frame = pd.DataFrame({self.feature: [0, 0, 0, 1, 1, 1]})
        self.labels = ["NORMAL", "NORMAL", "ABNORMAL", "ABNORMAL", "ABNORMAL", "NORMAL"]
        self.model = DecisionTreeClassifier(max_depth=1, random_state=20261020).fit(self.frame, self.labels)
        self.exported = export_tree(self.model, "heart_rate", [self.feature], self.frame)

    def test_non_pure_leaf_probability_order_matches_sklearn(self):
        labels, probabilities = json_predict(self.exported, self.frame)
        np.testing.assert_array_equal(labels, self.model.predict(self.frame))
        np.testing.assert_allclose(probabilities, ordered_probabilities(self.model, self.frame), atol=1e-12, rtol=0)
        self.assertEqual(self.exported["classes"], ["NORMAL", "ABNORMAL"])
        self.assertAlmostEqual(probabilities[0, 0], 2 / 3)
        self.assertAlmostEqual(probabilities[-1, 1], 2 / 3)
        self.assertFalse(self.exported["confidence_calibrated"])

    def test_float32_boundary_traversal_matches_sklearn(self):
        boundary = self.model.tree_.threshold[0]
        frame = pd.DataFrame({self.feature: [boundary - 1e-7, boundary, boundary + 1e-8, boundary + 1e-7]})
        labels, probabilities = json_predict(self.exported, frame)
        np.testing.assert_array_equal(labels, self.model.predict(frame))
        np.testing.assert_allclose(probabilities, ordered_probabilities(self.model, frame), atol=1e-12, rtol=0)

    def test_tied_leaf_preserves_sklearn_label_order(self):
        frame = pd.DataFrame({self.feature: [0, 0, 1, 1]})
        model = DecisionTreeClassifier(min_samples_leaf=4).fit(frame, ["NORMAL", "ABNORMAL", "NORMAL", "ABNORMAL"])
        exported = export_tree(model, "heart_rate", [self.feature], frame)
        labels, probabilities = json_predict(exported, frame)
        np.testing.assert_array_equal(labels, model.predict(frame))
        self.assertTrue(all(label == "ABNORMAL" for label in labels))
        np.testing.assert_allclose(probabilities, np.full((4, 2), 0.5))

    def test_probability_metrics_use_correct_class_order(self):
        result = evaluate(["NORMAL", "ABNORMAL"], ["NORMAL", "ABNORMAL"], np.array([[0.9, 0.1], [0.1, 0.9]]))
        self.assertAlmostEqual(result["probability_diagnostics"]["log_loss"], -np.log(0.9))
        self.assertAlmostEqual(result["probability_diagnostics"]["abnormal_brier_score"], 0.01)
        self.assertIsNone(result["critical_recall"])

    def test_unknown_dataset_rejected_before_split_or_training(self):
        with tempfile.TemporaryDirectory() as folder:
            csv = Path(folder) / "changed.csv"
            csv.write_text("unapproved,changed\n1,2\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Dataset hash differs"):
                load_and_partition(csv, Path(folder) / "missing-split.csv")

    def test_nonfinite_json_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(ValueError):
                write_json(Path(folder) / "invalid.json", {"probability": float("nan")})


if __name__ == "__main__":
    unittest.main(verbosity=2)
