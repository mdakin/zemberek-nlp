#!/usr/bin/env python3
"""
adjudicate_imst_failures.py

Specialized runner script to adjudicate UD Turkish-IMST test set disambiguation disagreements.
Extracts all sentences where Zemberek disagreed with UD-IMST ground truth,
queries Gemini 3.8 Flash for linguistic adjudication with ultra-lean explanations,
and outputs sentence-level results and an aggregated statistical summary.

Usage:
  python3 experiment/scripts/adjudicate_imst_failures.py
"""

import os
import sys
import subprocess

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "../.."))

EVAL_JSON = os.path.join(PROJECT_ROOT, "evaluation_head_imst_canonical.json")
SOURCE_JSONL = os.path.join(PROJECT_ROOT, "data/ambiguity/sources/UD_Turkish-IMST/imst_test_canonical.jsonl")
OUT_JSONL = os.path.join(PROJECT_ROOT, "data/ambiguity/imst_test_adjudication_results.jsonl")
REPORT_JSON = os.path.join(PROJECT_ROOT, "data/ambiguity/imst_test_adjudication_summary.json")
ADJUDICATE_SCRIPT = os.path.join(SCRIPT_DIR, "adjudicate_ud_failures.py")


def main():
    cmd = [
        sys.executable,
        ADJUDICATE_SCRIPT,
        "-e", EVAL_JSON,
        "-s", SOURCE_JSONL,
        "-o", OUT_JSONL,
        "-r", REPORT_JSON,
        "-c", "16",
        "-m", "gemini-3.8-flash"
    ]
    print(f"Running IMST adjudication pipeline...")
    print(f"Command: {' '.join(cmd)}\n")
    subprocess.run(cmd, check=True)


if __name__ == "__main__":
    main()

