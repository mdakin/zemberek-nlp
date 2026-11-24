#!/usr/bin/env python3
"""
Extract 1,000 golden test candidate sentences and 10,000 batch-3 candidate sentences
from hard_uncertainty_candidates.jsonl, with strict exclusion of existing 20k training
and test sentences, preserving domain diversity and hardest ambiguity margin.
"""

import json
import os
import random
from collections import defaultdict, Counter

DISTILLED_DIR = os.path.expanduser("~/data/turkish/distilled")
SRC_CANDIDATES = os.path.join(DISTILLED_DIR, "hard_uncertainty_candidates.jsonl")

# 1. Collect all forbidden sentence IDs and exact text lines
forbidden_ids = set()
forbidden_texts = set()

exclude_files = [
    os.path.join(DISTILLED_DIR, "distilled_20k_annotated.jsonl"),
    os.path.join(DISTILLED_DIR, "test_heldout_250_candidates.jsonl"),
    os.path.join(DISTILLED_DIR, "test_heldout_100_candidates.jsonl"),
    os.path.join(DISTILLED_DIR, "test_100_annotated.jsonl"),
    os.path.join(DISTILLED_DIR, "random_natural_250_candidates.jsonl"),
    # Also check repo data files
    "data/ambiguity/train_combined_v2.jsonl",
]

for fpath in exclude_files:
    if os.path.exists(fpath):
        with open(fpath) as f:
            for line in f:
                try:
                    d = json.loads(line)
                    if "sentence_id" in d:
                        forbidden_ids.add(d["sentence_id"])
                    if "text" in d:
                        forbidden_texts.add(d["text"].strip())
                except Exception:
                    pass

print(f"Loaded {len(forbidden_ids)} forbidden IDs and {len(forbidden_texts)} forbidden texts.")

# 2. Target domain quotas
QUOTA_1K = {
    "kadinlar-klubu": 200,
    "radikal": 150,
    "star-gazete": 90,
    "yargitay-karar": 90,
    "tbmm": 80,
    "cnn-turk": 80,
    "vikipedi": 70,
    "hukuki-net": 60,
    "dunya": 60,
    "subtitle-tr-10M-sentences": 50,
    "ntvmsnbc": 25,
    "ntv-750k-sentences": 25,
    "subtitle-tr-1M-sentences": 20,
}
assert sum(QUOTA_1K.values()) == 1000

QUOTA_10K = {
    "kadinlar-klubu": 2500,
    "radikal": 1800,
    "star-gazete": 900,
    "yargitay-karar": 900,
    "tbmm": 800,
    "cnn-turk": 800,
    "vikipedi": 650,
    "hukuki-net": 450,
    "dunya": 450,
    "subtitle-tr-10M-sentences": 450,
    "ntvmsnbc": 100,
    "ntv-750k-sentences": 100,
    "subtitle-tr-1M-sentences": 100,
}
assert sum(QUOTA_10K.values()) == 10000

# 3. Read candidates grouped by domain
print("Scanning candidates from", SRC_CANDIDATES)
candidates_by_domain = defaultdict(list)

with open(SRC_CANDIDATES) as f:
    for line in f:
        d = json.loads(line)
        sid = d["sentence_id"]
        txt = d["text"].strip()
        if sid in forbidden_ids or txt in forbidden_texts:
            continue
        dom = d.get("domain", "unknown")
        candidates_by_domain[dom].append((line, d))

print("Available candidate pool by domain:")
for dom, items in sorted(candidates_by_domain.items(), key=lambda x: len(x[1]), reverse=True):
    print(f"  {dom:25s}: {len(items):5d}")

# 4. Extract 1,000 Golden Test Sentences
selected_1k_lines = []
selected_1k_records = []
selected_1k_ids = set()
selected_1k_texts = set()

# Use fixed seed for reproducibility
rng = random.Random(42)

for dom, quota in QUOTA_1K.items():
    pool = candidates_by_domain[dom]
    assert len(pool) >= quota, f"Not enough candidates for {dom} (requested {quota}, have {len(pool)})"
    # Take first quota (which are already sorted by lowest delta_min in hard_uncertainty_candidates)
    # or shuffle slightly with fixed seed to avoid sequential bias
    sampled = pool[:quota]
    # Remove from pool
    candidates_by_domain[dom] = pool[quota:]
    
    for raw_line, d in sampled:
        selected_1k_lines.append(raw_line)
        selected_1k_records.append(d)
        selected_1k_ids.add(d["sentence_id"])
        selected_1k_texts.add(d["text"].strip())

assert len(selected_1k_lines) == 1000
print(f"\nSuccessfully selected 1,000 golden test candidate sentences.")

# 5. Extract 10,000 Batch-3 Candidates (WITHOUT LLM DISAMBIGUATION)
selected_10k_lines = []
selected_10k_records = []
selected_10k_ids = set()
selected_10k_texts = set()

for dom, quota in QUOTA_10K.items():
    pool = candidates_by_domain[dom]
    assert len(pool) >= quota, f"Not enough candidates for {dom} (requested {quota}, have {len(pool)})"
    sampled = pool[:quota]
    candidates_by_domain[dom] = pool[quota:]
    
    for raw_line, d in sampled:
        assert d["sentence_id"] not in selected_1k_ids
        assert d["text"].strip() not in selected_1k_texts
        selected_10k_lines.append(raw_line)
        selected_10k_records.append(d)
        selected_10k_ids.add(d["sentence_id"])
        selected_10k_texts.add(d["text"].strip())

assert len(selected_10k_lines) == 10000
print(f"Successfully selected 10,000 batch-3 candidate sentences.")

# Verify zero overlaps
assert len(selected_1k_ids & forbidden_ids) == 0
assert len(selected_1k_texts & forbidden_texts) == 0
assert len(selected_10k_ids & forbidden_ids) == 0
assert len(selected_10k_texts & forbidden_texts) == 0
assert len(selected_1k_ids & selected_10k_ids) == 0
assert len(selected_1k_texts & selected_10k_texts) == 0

# 6. Save files
golden_jsonl = os.path.join(DISTILLED_DIR, "golden_test_1000_candidates.jsonl")
golden_txt = os.path.join(DISTILLED_DIR, "golden_test_1000_sentences.txt")
batch3_jsonl = os.path.join(DISTILLED_DIR, "distilled_batch3_10k_candidates.jsonl")
batch3_txt = os.path.join(DISTILLED_DIR, "distilled_batch3_10k_sentences.txt")

with open(golden_jsonl, "w", encoding="utf-8") as f_jsonl, open(golden_txt, "w", encoding="utf-8") as f_txt:
    for raw_line, d in zip(selected_1k_lines, selected_1k_records):
        f_jsonl.write(raw_line.strip() + "\n")
        f_txt.write(d["text"].strip() + "\n")

with open(batch3_jsonl, "w", encoding="utf-8") as f_jsonl, open(batch3_txt, "w", encoding="utf-8") as f_txt:
    for raw_line, d in zip(selected_10k_lines, selected_10k_records):
        f_jsonl.write(raw_line.strip() + "\n")
        f_txt.write(d["text"].strip() + "\n")

print(f"Saved golden test: {golden_jsonl} and {golden_txt}")
print(f"Saved batch3 candidates: {batch3_jsonl} and {batch3_txt}")

