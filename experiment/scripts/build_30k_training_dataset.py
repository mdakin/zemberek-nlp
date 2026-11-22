#!/usr/bin/env python3
"""
Merge canonicalized 20k dataset, 10k batch-3 dataset, and contrastive dataset into
a unified 32,214-sentence training dataset for FastPerceptron training.
"""

import os
import sys
import json
import subprocess

DISTILLED_DIR = "/home/dndara/data/turkish/distilled"
REPO_DATA_DIR = "/home/dndara/work/zemberek-nlp/data/ambiguity"

FILE_20K = os.path.join(DISTILLED_DIR, "distilled_20k_canonical.jsonl")
FILE_10K_RAW = os.path.join(DISTILLED_DIR, "distilled_batch3_10k_annotated.jsonl")
FILE_10K_CANONICAL = os.path.join(DISTILLED_DIR, "distilled_batch3_10k_canonical.jsonl")
FILE_CONTRASTIVE = os.path.join(REPO_DATA_DIR, "contrastive_annotated_canonical.jsonl")

OUT_COMBINED_JSONL = os.path.join(REPO_DATA_DIR, "train_combined_30k.jsonl")
OUT_COMBINED_TXT = os.path.join(REPO_DATA_DIR, "train_combined_30k.zemberek.txt")


def step1_canonicalize_10k():
    print(f"--- Step 1: Canonicalizing 10k batch-3 ---")
    cmd = [
        "python3",
        "experiment/scripts/canonicalize_lexicon_twins.py",
        "-i", FILE_10K_RAW,
        "-o", FILE_10K_CANONICAL
    ]
    subprocess.run(cmd, check=True)


def step2_merge_datasets():
    print(f"--- Step 2: Merging datasets into {OUT_COMBINED_JSONL} ---")
    
    # Load permanent held-out blacklist manifest to guarantee zero test leakage
    manifest_path = os.path.join(DISTILLED_DIR, "PERMANENT_HELDOUT_TEST_SET_MANIFEST.json")
    heldout_ids = set()
    heldout_texts = set()
    if os.path.exists(manifest_path):
        with open(manifest_path, "r", encoding="utf-8") as mf:
            mdata = json.load(mf)
            for sname, sinfo in mdata.get("sets", {}).items():
                for sid in sinfo.get("sentence_ids", []):
                    heldout_ids.add(sid)
                for txt in sinfo.get("sentence_texts", []):
                    heldout_texts.add(txt.strip())
        print(f"  [GUARD] Enforcing zero-leakage guard against {len(heldout_ids)} permanent held-out test sentences.")

    seen_ids = set()
    total_count = 0

    with open(OUT_COMBINED_JSONL, "w", encoding="utf-8") as out_f:
        for fpath, label in [(FILE_20K, "20K Base"), (FILE_10K_CANONICAL, "10K Batch 3"), (FILE_CONTRASTIVE, "Contrastive")]:
            count = 0
            with open(fpath, "r", encoding="utf-8") as in_f:
                for line in in_f:
                    line = line.strip()
                    if not line:
                        continue
                    rec = json.loads(line)
                    sid = rec.get("sentence_id", "")
                    stext = rec.get("text", "").strip()

                    # Strict anti-leakage assertion
                    assert sid not in heldout_ids, f"CRITICAL: Held-out test sentence ID {sid} found in training input {fpath}!"
                    assert stext not in heldout_texts, f"CRITICAL: Held-out test sentence text '{stext}' found in training input {fpath}!"

                    if sid and sid in seen_ids:
                        continue
                    seen_ids.add(sid)
                    out_f.write(line + "\n")
                    count += 1
                    total_count += 1
            print(f"  Added {count} sentences from {label} ({fpath})")

    print(f"Total merged sentences: {total_count}")


def step3_convert_to_zemberek():
    print(f"--- Step 3: Converting to Zemberek training format ({OUT_COMBINED_TXT}) ---")
    cmd = [
        "python3",
        "experiment/scripts/jsonl_to_zemberek_train.py",
        "-i", OUT_COMBINED_JSONL,
        "-o", OUT_COMBINED_TXT
    ]
    subprocess.run(cmd, check=True)


def main():
    if not os.path.exists(FILE_10K_RAW):
        print(f"Error: {FILE_10K_RAW} does not exist yet.")
        sys.exit(1)

    step1_canonicalize_10k()
    step2_merge_datasets()
    step3_convert_to_zemberek()
    print("\n[SUCCESS] Unified 30K+ dataset ready for FastPerceptron training!")


if __name__ == "__main__":
    main()

