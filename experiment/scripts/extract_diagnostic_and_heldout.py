#!/usr/bin/env python3
"""
Extract 5,000 diagnostic error-mining candidate sentences and 2,500 held-out golden test candidate
sentences from hard_uncertainty_candidates.jsonl.

Strict Requirements:
1. Strict exclusion of all previous 20k, 10k batch-3, 2,214 contrastive, and 1k golden test sentences.
2. Complete mutual exclusivity between the 5,000 diagnostic set and 2,500 held-out golden test set.
3. Creation of a permanent held-out manifest (PERMANENT_HELDOUT_TEST_SET_MANIFEST.json) ensuring the
   2,500 held-out sentences can never be included in any future training dataset.
4. Stratified proportional sampling across all 13 domains.
"""

import os
import sys
import json
import random
from collections import defaultdict, Counter

DISTILLED_DIR = os.path.expanduser("~/data/turkish/distilled")
SRC_CANDIDATES = os.path.join(DISTILLED_DIR, "hard_uncertainty_candidates.jsonl")

# 1. Collect all forbidden sentence IDs and exact texts from previous batches
forbidden_ids = set()
forbidden_texts = set()

exclude_files = [
    os.path.join(DISTILLED_DIR, "distilled_20k_annotated.jsonl"),
    os.path.join(DISTILLED_DIR, "distilled_batch3_10k_candidates.jsonl"),
    os.path.join(DISTILLED_DIR, "distilled_batch3_10k_annotated.jsonl"),
    os.path.join(DISTILLED_DIR, "golden_test_1000_candidates.jsonl"),
    os.path.join(DISTILLED_DIR, "test_heldout_250_candidates.jsonl"),
    os.path.join(DISTILLED_DIR, "test_heldout_100_candidates.jsonl"),
    os.path.join(DISTILLED_DIR, "random_natural_250_candidates.jsonl"),
    "data/ambiguity/train_combined_30k.jsonl",
    "data/ambiguity/contrastive_annotated_canonical.jsonl",
]

for fpath in exclude_files:
    if os.path.exists(fpath):
        with open(fpath, "r", encoding="utf-8") as f:
            for line in f:
                try:
                    d = json.loads(line)
                    if "sentence_id" in d:
                        forbidden_ids.add(d["sentence_id"])
                    if "text" in d:
                        forbidden_texts.add(d["text"].strip())
                except Exception:
                    pass

print(f"[INIT] Loaded {len(forbidden_ids)} forbidden IDs and {len(forbidden_texts)} forbidden texts.")

# 2. Stratified Quotas (Proportional to available 53,721 candidates)
QUOTA_5K = {
    "kadinlar-klubu": 1125,
    "subtitle-tr-10M-sentences": 1050,
    "star-gazete": 485,
    "tbmm": 440,
    "yargitay-karar": 385,
    "cnn-turk": 310,
    "vikipedi": 230,
    "subtitle-tr-1M-sentences": 220,
    "ntv-750k-sentences": 200,
    "ntvmsnbc": 190,
    "dunya": 190,
    "hukuki-net": 110,
    "radikal": 65,
}
assert sum(QUOTA_5K.values()) == 5000, f"QUOTA_5K sum must be 5000, got {sum(QUOTA_5K.values())}"

QUOTA_2500 = {
    "kadinlar-klubu": 563,
    "subtitle-tr-10M-sentences": 525,
    "star-gazete": 242,
    "tbmm": 220,
    "yargitay-karar": 193,
    "cnn-turk": 155,
    "vikipedi": 115,
    "subtitle-tr-1M-sentences": 110,
    "ntv-750k-sentences": 100,
    "ntvmsnbc": 95,
    "dunya": 95,
    "hukuki-net": 55,
    "radikal": 32,
}
assert sum(QUOTA_2500.values()) == 2500, f"QUOTA_2500 sum must be 2500, got {sum(QUOTA_2500.values())}"

# 3. Read candidates grouped by domain
print(f"[SCAN] Scanning candidate pool from {SRC_CANDIDATES}...")
candidates_by_domain = defaultdict(list)

with open(SRC_CANDIDATES, "r", encoding="utf-8") as f:
    for line in f:
        d = json.loads(line)
        sid = d["sentence_id"]
        txt = d["text"].strip()
        if sid in forbidden_ids or txt in forbidden_texts:
            continue
        dom = d.get("domain", "unknown")
        candidates_by_domain[dom].append((line, d))

print(f"[SCAN] Total available candidates across all domains: {sum(len(v) for v in candidates_by_domain.values())}")

# 4. Extract 2,500 Held-Out Golden Test Candidates FIRST
# (Extracting held-out first guarantees priority reservation)
print(f"\n[SAMPLE] Extracting 2,500 Held-Out Golden Test Candidates...")
selected_heldout_lines = []
selected_heldout_records = []
selected_heldout_ids = set()
selected_heldout_texts = set()

for dom, quota in QUOTA_2500.items():
    pool = candidates_by_domain[dom]
    assert len(pool) >= quota, f"Not enough candidates for {dom} (requested {quota}, have {len(pool)})"
    sampled = pool[:quota]
    candidates_by_domain[dom] = pool[quota:]

    for raw_line, d in sampled:
        selected_heldout_lines.append(raw_line)
        selected_heldout_records.append(d)
        selected_heldout_ids.add(d["sentence_id"])
        selected_heldout_texts.add(d["text"].strip())

assert len(selected_heldout_lines) == 2500
print(f"[SAMPLE] Successfully reserved 2,500 Held-Out Golden Test sentences.")

# 5. Extract 5,000 Diagnostic Error Mining Candidates
print(f"\n[SAMPLE] Extracting 5,000 Diagnostic Error-Mining Candidates...")
selected_diag_lines = []
selected_diag_records = []
selected_diag_ids = set()
selected_diag_texts = set()

for dom, quota in QUOTA_5K.items():
    pool = candidates_by_domain[dom]
    assert len(pool) >= quota, f"Not enough candidates for {dom} (requested {quota}, have {len(pool)})"
    sampled = pool[:quota]
    candidates_by_domain[dom] = pool[quota:]

    for raw_line, d in sampled:
        assert d["sentence_id"] not in selected_heldout_ids
        assert d["text"].strip() not in selected_heldout_texts
        selected_diag_lines.append(raw_line)
        selected_diag_records.append(d)
        selected_diag_ids.add(d["sentence_id"])
        selected_diag_texts.add(d["text"].strip())

assert len(selected_diag_lines) == 5000
print(f"[SAMPLE] Successfully reserved 5,000 Diagnostic Error-Mining sentences.")

# 6. Verify absolute mutual exclusivity and zero data leakage
print(f"\n[VERIFY] Verifying mutual exclusivity and zero leakage...")
assert len(selected_heldout_ids & forbidden_ids) == 0, "Leakage detected: held-out IDs overlap with forbidden!"
assert len(selected_heldout_texts & forbidden_texts) == 0, "Leakage detected: held-out texts overlap with forbidden!"
assert len(selected_diag_ids & forbidden_ids) == 0, "Leakage detected: diagnostic IDs overlap with forbidden!"
assert len(selected_diag_texts & forbidden_texts) == 0, "Leakage detected: diagnostic texts overlap with forbidden!"
assert len(selected_heldout_ids & selected_diag_ids) == 0, "Leakage detected: held-out and diagnostic IDs overlap!"
assert len(selected_heldout_texts & selected_diag_texts) == 0, "Leakage detected: held-out and diagnostic texts overlap!"
print(f"[VERIFY] Zero leakage confirmed! All sets are 100% mutually disjoint.")

# 7. Write out dataset files
heldout_jsonl = os.path.join(DISTILLED_DIR, "golden_test_2500_candidates.jsonl")
heldout_txt = os.path.join(DISTILLED_DIR, "golden_test_2500_sentences.txt")
diag_jsonl = os.path.join(DISTILLED_DIR, "diagnostic_5000_candidates.jsonl")
diag_txt = os.path.join(DISTILLED_DIR, "diagnostic_5000_sentences.txt")

with open(heldout_jsonl, "w", encoding="utf-8") as f_jsonl, open(heldout_txt, "w", encoding="utf-8") as f_txt:
    for raw_line, d in zip(selected_heldout_lines, selected_heldout_records):
        f_jsonl.write(raw_line.strip() + "\n")
        f_txt.write(d["text"].strip() + "\n")

with open(diag_jsonl, "w", encoding="utf-8") as f_jsonl, open(diag_txt, "w", encoding="utf-8") as f_txt:
    for raw_line, d in zip(selected_diag_lines, selected_diag_records):
        f_jsonl.write(raw_line.strip() + "\n")
        f_txt.write(d["text"].strip() + "\n")

print(f"[SAVE] Held-Out Golden Test (2,500 sent): {heldout_jsonl}")
print(f"[SAVE] Diagnostic Error Mining (5,000 sent): {diag_jsonl}")

# 8. Create Permanent Held-Out Manifest
# This manifest must be imported by all training scripts to guarantee this 2,500 set is NEVER touched.
manifest_path = os.path.join(DISTILLED_DIR, "PERMANENT_HELDOUT_TEST_SET_MANIFEST.json")
manifest_data = {
    "version": "1.0",
    "description": "Permanent Held-Out Test Sets for Zemberek Disambiguation Evaluation. NEVER USE FOR TRAINING.",
    "sets": {
        "golden_test_1000": {
            "count": 1000,
            "path": "golden_test_1000_canonical.jsonl"
        },
        "golden_test_2500": {
            "count": 2500,
            "candidates_path": "golden_test_2500_candidates.jsonl",
            "sentences_path": "golden_test_2500_sentences.txt",
            "sentence_ids": sorted(list(selected_heldout_ids)),
            "sentence_texts": sorted(list(selected_heldout_texts))
        }
    }
}

with open(manifest_path, "w", encoding="utf-8") as f:
    json.dump(manifest_data, f, ensure_ascii=False, indent=2)

print(f"[SAVE] Permanent Held-Out Test Manifest: {manifest_path}")
print(f"[COMPLETED] Step A candidate extraction successfully finished.")

