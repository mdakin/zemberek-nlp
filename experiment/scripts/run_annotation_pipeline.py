#!/usr/bin/env python3
"""
Master runner for LLM disambiguation and canonicalization:
1. Disambiguates and canonicalizes the 2,500 Held-Out Golden Test set.
2. Disambiguates and canonicalizes the 5,000 Diagnostic Error-Mining set.
3. Updates the Permanent Held-Out Test Manifest.
"""

import os
import sys
import json
import time
import subprocess

DISTILLED_DIR = os.path.expanduser("~/data/turkish/distilled")

HELDOUT_CANDIDATES = os.path.join(DISTILLED_DIR, "golden_test_2500_candidates.jsonl")
HELDOUT_ANNOTATED = os.path.join(DISTILLED_DIR, "golden_test_2500_annotated.jsonl")
HELDOUT_CANONICAL = os.path.join(DISTILLED_DIR, "golden_test_2500_canonical.jsonl")

DIAG_CANDIDATES = os.path.join(DISTILLED_DIR, "diagnostic_5000_candidates.jsonl")
DIAG_ANNOTATED = os.path.join(DISTILLED_DIR, "diagnostic_5000_annotated.jsonl")
DIAG_CANONICAL = os.path.join(DISTILLED_DIR, "diagnostic_5000_canonical.jsonl")

MANIFEST_PATH = os.path.join(DISTILLED_DIR, "PERMANENT_HELDOUT_TEST_SET_MANIFEST.json")


def run_cmd(cmd, desc):
    print("\n" + "=" * 80)
    print(f"  {desc}")
    print("=" * 80 + "\n")
    t0 = time.time()
    subprocess.run(cmd, check=True)
    dt = time.time() - t0
    print(f"\n[DONE] {desc} finished in {dt:.1f}s ({dt/60:.2f} min).")


def main():
    print(f"Starting LLM Annotation Pipeline for 7,500 sentences (Gemini 3.8 Flash, Thinking=256)...")

    # 1. Disambiguate Held-Out 2,500
    cmd_heldout = [
        "python3", "experiment/scripts/disambiguate_batch_fast.py",
        "-i", HELDOUT_CANDIDATES,
        "-o", HELDOUT_ANNOTATED,
        "-c", "12",
        "-b", "256"
    ]
    run_cmd(cmd_heldout, "Phase 1: Disambiguating 2,500 Held-Out Golden Test Sentences")

    # 2. Canonicalize Held-Out 2,500
    cmd_can_heldout = [
        "python3", "experiment/scripts/canonicalize_lexicon_twins.py",
        "-i", HELDOUT_ANNOTATED,
        "-o", HELDOUT_CANONICAL
    ]
    run_cmd(cmd_can_heldout, "Phase 2: Canonicalizing 2,500 Held-Out Golden Test Sentences")

    # 3. Disambiguate Diagnostic 5,000
    cmd_diag = [
        "python3", "experiment/scripts/disambiguate_batch_fast.py",
        "-i", DIAG_CANDIDATES,
        "-o", DIAG_ANNOTATED,
        "-c", "12",
        "-b", "256"
    ]
    run_cmd(cmd_diag, "Phase 3: Disambiguating 5,000 Diagnostic Error-Mining Sentences")

    # 4. Canonicalize Diagnostic 5,000
    cmd_can_diag = [
        "python3", "experiment/scripts/canonicalize_lexicon_twins.py",
        "-i", DIAG_ANNOTATED,
        "-o", DIAG_CANONICAL
    ]
    run_cmd(cmd_can_diag, "Phase 4: Canonicalizing 5,000 Diagnostic Error-Mining Sentences")

    # 5. Update Manifest
    if os.path.exists(MANIFEST_PATH):
        with open(MANIFEST_PATH, "r", encoding="utf-8") as f:
            mdata = json.load(f)
        if "golden_test_2500" in mdata.get("sets", {}):
            mdata["sets"]["golden_test_2500"]["canonical_path"] = "golden_test_2500_canonical.jsonl"
            mdata["sets"]["golden_test_2500"]["status"] = "ANNOTATED_AND_CANONICALIZED"
        with open(MANIFEST_PATH, "w", encoding="utf-8") as f:
            json.dump(mdata, f, ensure_ascii=False, indent=2)
        print(f"\n[UPDATED] Manifest updated at: {MANIFEST_PATH}")

    print("\n" + "#" * 80)
    print("  ALL PHASES COMPLETED SUCCESSFULLY!")
    print(f"  - Held-Out Canonical:  {HELDOUT_CANONICAL}")
    print(f"  - Diagnostic Canonical: {DIAG_CANONICAL}")
    print("#" * 80 + "\n")


if __name__ == "__main__":
    main()

