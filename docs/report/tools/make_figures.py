#!/usr/bin/env python3
"""Rebuild quantitative report figures from checked-in evidence snapshots."""
import json
from pathlib import Path
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

REPORT = Path(__file__).resolve().parents[1]
meta = json.loads((REPORT / "evidence/model-metadata.json").read_text())
plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 10,
                     "axes.spines.top": False, "axes.spines.right": False,
                     "pdf.fonttype": 42, "savefig.bbox": "tight"})
navy, teal, grey = "#16334A", "#087E83", "#718492"
classes = meta["classes"]
fig, axs = plt.subplots(1, 2, figsize=(8.3, 2.7), layout="constrained")
counts = [meta["label_counts"][c] for c in classes]
axs[0].bar(classes, counts, color=[navy, grey, teal], width=.58)
axs[0].set_ylabel("Synthetic rows")
axs[0].set_title("Educational class distribution", loc="left", weight="bold", fontsize=10)
axs[0].set_ylim(0, 26500)
for i, n in enumerate(counts): axs[0].text(i, n+600, f"{n:,}", ha="center", fontsize=9)
sizes = meta["split"]["rows"]
axs[1].bar(["Train", "Validation", "Test"], list(sizes.values()), color=[navy, grey, teal], width=.58)
axs[1].set_title("Partitions after tuple grouping", loc="left", weight="bold", fontsize=10)
axs[1].set_ylim(0, 43000)
for i, n in enumerate(sizes.values()): axs[1].text(i, n+900, f"{n:,}", ha="center", fontsize=9)
fig.savefig(REPORT / "figures/dataset_summary.pdf")
plt.close(fig)

fig, axs = plt.subplots(1, 2, figsize=(8.3, 3.5), layout="constrained")
for ax, key, title in zip(axs, ["logistic_regression", "random_forest"],
                          ["Logistic regression", "Selected Random Forest"]):
    a = np.array(meta["metrics"][key]["test"]["confusion_matrix"])
    ax.imshow(a, cmap="Blues", vmin=0, vmax=4600)
    ax.set_xticks(range(3), classes, fontsize=9)
    ax.set_yticks(range(3), classes, fontsize=9)
    ax.set_xlabel("Predicted educational label")
    ax.set_ylabel("True educational label")
    ax.set_title(title, fontsize=10, weight="bold", pad=10)
    for i in range(3):
        for j in range(3):
            ax.text(j, i, f"{a[i,j]:,}", ha="center", va="center", fontsize=11,
                    color="white" if a[i,j] > 2300 else navy)
    ax.spines[["top", "right", "bottom", "left"]].set_visible(False)
fig.savefig(REPORT / "figures/confusion_comparison.pdf")
plt.close(fig)

record = json.loads((REPORT / "evidence/idempotency-before-after.json").read_text())
fig, ax = plt.subplots(figsize=(7.0, 2.7), layout="constrained")
labels = ["Initial server\n6757c0a", "Current server\n5c650c1"]
stored = [record["baseline"]["stored_rows"], record["final"]["stored_rows"]]
ax.barh(labels, stored, color=[grey, teal], height=.5)
ax.axvline(20, color=navy, linestyle="--", linewidth=1.2)
ax.set_xlim(0, 48)
ax.set_xlabel("Stored rows after 20 distinct samples, each delivered twice")
for i,n in enumerate(stored): ax.text(n+.6, i, str(n), va="center", weight="bold")
ax.text(20.6, .50, "20 expected rows", fontsize=9, color=navy)
ax.invert_yaxis()
fig.savefig(REPORT / "figures/idempotency_improvement.pdf")
plt.close(fig)
print("Generated 3 vector figures from recorded evidence")
