# Synthetic-vitals comparison retained for integration v0.8.0

This folder retains the verified same-task Decision Tree / Random Forest comparison. The original working v0.7.4 baseline is preserved. The three-class target remains unresolved; see `docs/ML_TARGET_AUDIT.txt` and the machine-readable `target_audit.json` in this folder. No retraining was required to integrate these existing binary models.

## Reproduce

Run with the existing project ML environment, supplying the original synthetic CSV and the earlier split membership file. Example from the project root in PowerShell:

```powershell
& '.\work\ml_venv\Scripts\python.exe' '.\work\integration_v080_20261026\care_monitor_ml\vitals\compare_classifiers.py' `
  --csv '.\incoming\iot_app_ml_20261020\Synthetic_patient-HealthCare-Monitoring_dataset.csv' `
  --reference-split '.\work\integration_v080_20261026\evidence\ml\split_membership.csv' `
  --out '.\work\integration_v080_20261026\evidence\ml-reproduction'
```

The dataset SHA-256 is checked before training. The original filtering, feature-vector grouping and seeds are regenerated and asserted equal to **every row** of the reference split. The output contains a copy of that exact split file and its hash. The included output split may also serve as the reference on another computer, together with a separately obtained authorised copy of the dataset.

The reproduction command deliberately uses a separate output folder so it does not overwrite the pinned model evidence. From the integration root, run the synthetic regression checks:

```powershell
& '..\ml_venv\Scripts\python.exe' -m unittest discover -s '.\care_monitor_ml\vitals' -p 'test_comparison.py' -v
```

## Experiment protocol

- Four independent binary tasks: heart-rate alert, oxygen alert, blood-pressure alert and body-temperature alert. The only classes are `NORMAL` and `ABNORMAL`.
- Each classifier receives the same task-specific features, labels and 35,993/12,005/12,002 training/validation/test rows.
- Decision Tree depths: 1, 2, 3; Random Forest depths: 2, 4, 8. Both use minimum leaf size 20 and random seed 20261020. The forest uses 100 trees, all features per split and bootstrap sampling.
- Select each family's depth by validation macro-F1, preferring lower depth on a tie. Select family by validation macro-F1, preferring the interpretable Decision Tree on a tie. Then refit the selected depth of each family on the 47,998 development rows and evaluate the held-out test once. The test does not select a model.
- Store confusion matrices, class counts, accuracy, macro precision/recall/F1, ABNORMAL recall, false-ABNORMAL and missed-ABNORMAL rates. No Critical metric is substituted for ABNORMAL; Critical metrics are explicitly unavailable.

## Observed isolated run

All eight task/classifier combinations achieved accuracy 1.000 and macro-F1 1.000 on the synthetic holdout. Validation scores tied, so Decision Trees were selected. DT depths were 2/1/3/1 and RF depths were 2/2/4/2 for heart rate, oxygen, blood pressure and temperature respectively. This is a tie in the recorded experiment, not evidence that DT had better accuracy.

The safe JSON exports matched scikit-learn classes and probabilities on all 60,000 source rows for each selected model (240,000 comparisons). Six synthetic regression tests passed, including mixed-leaf probability order, float32 boundary behavior, tied-leaf labels, probability metric ordering, rejection of a changed dataset hash and rejection of non-finite JSON values.

## Confidence and limits

Exported `probabilities` follow the explicit `classes` array `["NORMAL", "ABNORMAL"]`. The predicted label preserves scikit-learn's class-order tie handling. An endpoint should return the probability at the predicted label's index, not infer a severity from that number. DT probabilities are empirical proportions in the training leaf; RF probabilities used during comparison average tree probabilities. No probability calibration was fitted. Pure synthetic leaves can produce 1.0; that does not mean a 100% probability that a person is healthy or ill.

The supplied labels have simple relationships to measured values. Identical complete five-vital vectors do not cross partitions, but individual predictor values repeat. There are no verified participant identities, independent assessment hidden tests, real-device calibration results or clinical validation here. Dataset original provenance and permission remain unresolved. No Warning/Critical mapping is invented.

## Integration endpoint assets

`evidence/ml/manifest.json` lists the four selected JSON models and hashes. The format is `care-monitor-assessment-probability-tree-v1`, uses float32 comparisons, and supplies explicit supported training ranges. No pickle/joblib loading is needed. Invalid or unsupported inputs should be rejected or produce an unavailable state. Activating or deploying the integrated endpoint is separate from the historical training run; this ML audit did not change the original live server.
