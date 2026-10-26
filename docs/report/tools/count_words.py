#!/usr/bin/env python3
"""Count report prose only; figures, tables, references and appendices excluded."""
import json
import re
from pathlib import Path

REPORT = Path(__file__).resolve().parents[1]
source = (REPORT / "sections/body.tex").read_text()
chunks = re.findall(r"\\begin\{reportprose\}(.*?)\\end\{reportprose\}", source, re.S)
text = "\n".join(chunks)
text = re.sub(r"\\(?:cite|ref|label)\{[^}]*\}", "", text)
text = re.sub(r"\\(?:code|texttt|textbf|emph)\{([^}]*)\}", r"\1", text)
text = re.sub(r"\\([_%&#])", r"\1", text)
text = re.sub(r"\\[A-Za-z]+\*?", "", text)
text = text.replace("~", " ").replace("{", "").replace("}", "")
words = re.findall(r"[A-Za-z0-9]+(?:[.'/-][A-Za-z0-9]+)*", text)
count = len(words)
record = {
    "main_prose_words": count,
    "permitted_range": [2700, 3300],
    "within_range": 2700 <= count <= 3300,
    "method": "Count alphanumeric words in reportprose environments only; hyphenated/apostrophe words count once. Exclude headings, citation markers, tables, figures, title, references and appendices. Includes conclusion; no separate abstract.",
    "source": "sections/body.tex",
}
(REPORT / "evidence/wordcount.json").write_text(json.dumps(record, indent=2) + "\n")
(REPORT / "evidence/wordcount.tex").write_text(f"\\newcommand{{\\MainWordCount}}{{{count:,}}}\n")
print(json.dumps(record, indent=2))
if not record["within_range"]:
    raise SystemExit("Main prose is outside rubric range")
