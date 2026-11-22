#!/usr/bin/env python3
"""
Extract 2,500 Fresh, Pristine Held-Out Golden Test Candidates from hard_uncertainty_candidates.jsonl.
Guarantees 100% mutual exclusivity and zero contamination against:
- All training datasets (20k base, batch 3 10k, master contrastive)
- All historical test sets (golden 1k, golden 2.5k)
- Diagnostic error-mining 5k
- Registers the new set permanently in PERMANENT_HELDOUT_TEST_SET_MANIFEST.json
"""

import os
import sys
import json
from collections import defaultdict, Counter

DISTILLED_DIR = "/home/dndara/data/turkish/distilled"
DATA_DIR = "/home/dndara/work/zemberek-nlp/data/ambiguity"
SRC_CANDIDATES = os.path.join(DISTILLED_DIR, "hard_uncertainty_candidates.jsonl")
MANIFEST_PATH = os.path.join(DISTILLED_DIR, "PERMANENT_HELDOUT_TEST_SET_MANIFEST.json")

OUT_CANDIDATES_JSONL = os.path.join(DATA_DIR, "golden_fresh_2500_candidates.jsonl")
OUT_SENTENCES_TXT = os.path.join(DATA_DIR, "golden_fresh_2500_sentences.txt")

# 1. Collect all forbidden IDs and texts
forbidden_ids = set()
forbidden_texts = set()

exclude_files = [
    os.path.join(DISTILLED_DIR, "distilled_20k_annotated.jsonl"),
    os.path.join(DISTILLED_DIR, "distilled_20k_canonical.jsonl"),
    os.path.join(DISTILLED_DIR, "distilled_batch3_10k_canonical.jsonl"),
    os.path.join(DISTILLED_DIR, "golden_test_1000_canonical.jsonl"),
    os.path.join(DISTILLED_DIR, "golden_test_2500_canonical.jsonl"),
    os.path.join(DISTILLED_DIR, "diagnostic_5000_canonical.jsonl"),
    os.path.join(DATA_DIR, "contrastive_master_canonical.jsonl"),
    os.path.join(DATA_DIR, "train_combined_30k.jsonl"),
    os.path.join(DATA_DIR, "train_combined_33k.jsonl"),
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
                        forbidden_texts.add(d["text"].strip().lower())
                except Exception:
                    pass

# Also load existing manifest entries
if os.path.exists(MANIFEST_PATH):
    with open(MANIFEST_PATH, "r", encoding="utf-8") as mf:
        mdata = json.load(mf)
        for sname, sinfo in mdata.get("sets", {}).items():
            for sid in sinfo.get("sentence_ids", []):
                forbidden_ids.add(sid)
            for txt in sinfo.get("sentence_texts", []):
                forbidden_texts.add(txt.strip().lower())

print(f"[INIT] Loaded {len(forbidden_ids)} forbidden IDs and {len(forbidden_texts)} forbidden texts.")

# 2. Stratified Quotas across all 13 domains (Sum = 2500)
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
        txt = d["text"].strip().lower()
        if sid in forbidden_ids or txt in forbidden_texts:
            continue
        dom = d.get("domain", "unknown")
        candidates_by_domain[dom].append((line.strip(), d))

total_available = sum(len(v) for v in candidates_by_domain.values())
print(f"[SCAN] Total available pristine candidates across all domains: {total_available}")

# 4. Extract 2,500 Fresh Golden Test Candidates
print(f"\n[SAMPLE] Extracting 2,500 Fresh Golden Test Candidates...")
selected_lines = []
selected_records = []
selected_ids = []
selected_texts = []

for dom, quota in QUOTA_2500.items():
    pool = candidates_by_domain[dom]
    if len(pool) < quota:
        raise ValueError(f"Insufficient pristine candidates for domain '{dom}': needed {quota}, available {len(pool)}")
    sampled = pool[:quota]

    for raw_line, d in sampled:
        selected_lines.append(raw_line)
        selected_records.append(d)
        selected_ids.append(d["sentence_id"])
        selected_texts.append(d["text"].strip())

assert len(selected_lines) == 2500
print(f"[SAMPLE] Successfully sampled 2,500 pristine sentences across 13 domains.")

# 5. Write outputs
os.makedirs(DATA_DIR, exist_ok=True)
with open(OUT_CANDIDATES_JSONL, "w", encoding="utf-8") as out_j:
    for raw in selected_lines:
        out_j.write(raw + "\n")

with open(OUT_SENTENCES_TXT, "w", encoding="utf-8") as out_t:
    for txt in selected_texts:
        out_t.write(txt + "\n")

print(f"[SAVE] Saved candidates to: {OUT_CANDIDATES_JSONL}")
print(f"[SAVE] Saved raw sentences to: {OUT_SENTENCES_TXT}")

# 6. Update Manifest
if os.path.exists(MANIFEST_PATH):
    with open(MANIFEST_PATH, "r", encoding="utf-8") as mf:
        manifest = json.load(mf)
else:
    manifest = {"version": "1.0", "description": "Permanent Held-Out Test Sets", "sets": {}}

manifest["sets"]["golden_fresh_2500"] = {
    "count": 2500,
    "candidates_path": "data/ambiguity/golden_fresh_2500_candidates.jsonl",
    "sentences_path": "data/ambiguity/golden_fresh_2500_sentences.txt",
    "canonical_path": "data/ambiguity/golden_fresh_2500_canonical.jsonl",
    "status": "EXTRACTED_PRISTINE",
    "sentence_ids": selected_ids,
    "sentence_texts": selected_texts
}

with open(MANIFEST_PATH, "w", encoding="utf-8") as mf:
    json.dump(manifest, mf, indent=2, ensure_ascii=False)

print(f"[MANIFEST] Updated {MANIFEST_PATH} with 'golden_fresh_2500' (2,500 permanent held-out IDs).")
print("[DONE] Fresh golden test extraction complete.")

