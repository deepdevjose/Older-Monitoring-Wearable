#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
python3 tools/count_words.py
latexmk -pdf -interaction=nonstopmode -halt-on-error -outdir=build IndiCare_Report.tex
cp -- build/IndiCare_Report.pdf IndiCare_Report.pdf
