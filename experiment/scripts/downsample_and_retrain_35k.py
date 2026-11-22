#!/usr/bin/env python3
"""
downsample_and_retrain_35k.py

Tests the hypothesis:
"Injecting 8k targeted sentences was too heavy and skewed priors.
Downsampling to ~2k balanced sentences provides targeted contrastive signal
without drowning out the base natural distribution."

Pipeline:
1. Downsamples Stage 5 from 8,000 to 2,000 sentences using balanced stratified sampling across 36 patterns.
2. Merges with train_combined_33k_canonical.jsonl (32,694 sentences) -> train_combined_35k.jsonl (~34,694 sentences).
3. Serializes to train_combined_35k.zemberek.txt.
4. Trains model-distilled-35k.bin via TrainFastAmbiguityModel (7 iterations, pruneWeight 0.05).
5. Evaluates model-distilled-35k.bin against model-distilled-33k-th20k.bin and model-distilled-41k.bin
   on all three Golden benchmarks (6,000 sentences).
"""

import os
import sys
import json
import time
import random
import subprocess
from collections import defaultdict
from typing import Dict, List, Set, Tuple, Any

REPO_ROOT = "/home/dndara/work/zemberek-nlp"
DATA_DIR = os.path.join(REPO_ROOT, "data/ambiguity")
GOLDEN_DIR = os.path.join(DATA_DIR, "golden")
BENCH_DIR = os.path.join(GOLDEN_DIR, "benchmark_results")

STAGE5_CANONICAL = os.path.join(DATA_DIR, "stage5_annotated_canonical_8k.jsonl")
BASE_33K_CANONICAL = os.path.join(DATA_DIR, "train_combined_33k_canonical.jsonl")

OUT_35K_JSONL = os.path.join(DATA_DIR, "train_combined_35k.jsonl")
OUT_35K_TXT = os.path.join(DATA_DIR, "train_combined_35k.zemberek.txt")
OUT_35K_BIN = os.path.join(DATA_DIR, "model-distilled-35k.bin")
OUT_35K_TXT_WEIGHTS = os.path.join(DATA_DIR, "model-distilled-35k.txt")

MODEL_33K_TH20K_BIN = os.path.join(DATA_DIR, "model-distilled-33k-th20k.bin")
MODEL_41K_BIN = os.path.join(DATA_DIR, "model-distilled-41k.bin")

MANIFEST_PATH = "/home/dndara/data/turkish/distilled/PERMANENT_HELDOUT_TEST_SET_MANIFEST.json"
TEST_FILES = [
    os.path.join(DATA_DIR, "golden_test_1000_thinking500.jsonl"),
    os.path.join(DATA_DIR, "golden_test_2500_thinking500.jsonl"),
    os.path.join(DATA_DIR, "golden_fresh_2500_canonical.jsonl"),
    os.path.join(GOLDEN_DIR, "golden_test_1000.jsonl"),
    os.path.join(GOLDEN_DIR, "golden_test_2500.jsonl"),
    os.path.join(GOLDEN_DIR, "golden_fresh_2500.jsonl"),
]

TARGET_SAMPLE_SIZE = 2000


def get_classpath() -> str:
    cp_file = "/tmp/full_mvn_cp.txt"
    if not os.path.exists(cp_file):
        subprocess.run(
            "mvn dependency:build-classpath -Dmdep.outputFile=/tmp/full_mvn_cp.txt -q",
            cwd=REPO_ROOT, shell=True, check=True
        )
    with open(cp_file) as f:
        cp = f.read().strip()
    return f"experiment/target/classes:apps/target/classes:morphology/target/classes:core/target/classes:tokenization/target/classes:lm/target/classes:{cp}"


def load_heldout_blacklist() -> Tuple[Set[str], Set[str]]:
    heldout_ids = set()
    heldout_texts = set()

    if os.path.exists(MANIFEST_PATH):
        with open(MANIFEST_PATH, "r", encoding="utf-8") as f:
            mdata = json.load(f)
            for sname, sinfo in mdata.get("sets", {}).items():
                for sid in sinfo.get("sentence_ids", []):
                    heldout_ids.add(sid)
                for txt in sinfo.get("sentence_texts", []):
                    heldout_texts.add(txt.strip().lower())

    for tf in TEST_FILES:
        if os.path.exists(tf):
            with open(tf, "r", encoding="utf-8") as f:
                for line in f:
                    if not line.strip():
                        continue
                    rec = json.loads(line)
                    txt = rec.get("text", "").strip()
                    if txt:
                        heldout_texts.add(txt.lower())

    return heldout_ids, heldout_texts


def sample_balanced_stage5(stage5_path: str, target_total: int, heldout_texts: Set[str], base_texts: Set[str]) -> List[Dict[str, Any]]:
    print(f"\n[PHASE 1] Loading and filtering Stage 5 sentences...")
    by_pattern = defaultdict(list)
    leaks = 0
    dups = 0
    total_read = 0

    with open(stage5_path, "r", encoding="utf-8") as f:
        for line in f:
            total_read += 1
            rec = json.loads(line)
            txt = rec.get("text", "").strip()
            clean_txt = txt.lower()

            if clean_txt in heldout_texts:
                leaks += 1
                continue
            if clean_txt in base_texts:
                dups += 1
                continue

            pid = rec.get("pattern_id", "P_UNKNOWN")
            by_pattern[pid].append(rec)

    print(f"  Stage 5 read: {total_read:,} sentences")
    print(f"  Leaks blocked: {leaks}, Duplicates blocked: {dups}")
    print(f"  Available valid sentences across {len(by_pattern)} patterns: {sum(len(v) for v in by_pattern.values()):,}")

    # Stratified downsampling
    rng = random.Random(42)
    for pid in by_pattern:
        rng.shuffle(by_pattern[pid])

    # Dynamic allocation so that smaller patterns keep their examples and larger ones are capped
    selected = []
    num_patterns = len(by_pattern)
    target_per_pattern = target_total // num_patterns

    # First pass: take up to target_per_pattern from each
    remaining_budget = target_total
    pool = {pid: list(recs) for pid, recs in by_pattern.items()}

    # Iterative round-robin allocation to reach exactly target_total
    while remaining_budget > 0:
        allocated_this_round = 0
        for pid in sorted(pool.keys()):
            if pool[pid] and remaining_budget > 0:
                selected.append(pool[pid].pop(0))
                remaining_budget -= 1
                allocated_this_round += 1
        if allocated_this_round == 0:
            break

    print(f"  Downsampled to exactly: {len(selected):,} sentences")
    
    # Print pattern distribution sample
    p_counts = defaultdict(int)
    for s in selected:
        p_counts[s.get("pattern_id")] += 1
    print(f"  Min sentences per pattern: {min(p_counts.values())}, Max per pattern: {max(p_counts.values())}, Median: {sorted(p_counts.values())[len(p_counts)//2]}")

    return selected


def merge_and_serialize():
    heldout_ids, heldout_texts = load_heldout_blacklist()

    # Read base 33k
    print(f"\n[PHASE 2] Loading Base 33k Canonical dataset: {BASE_33K_CANONICAL}")
    base_sentences = []
    base_texts = set()

    with open(BASE_33K_CANONICAL, "r", encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            rec = json.loads(line)
            clean_txt = rec.get("text", "").strip().lower()
            if clean_txt in heldout_texts or clean_txt in base_texts:
                continue
            base_texts.add(clean_txt)
            base_sentences.append(rec)

    print(f"  Base sentences retained: {len(base_sentences):,}")

    # Downsample Stage 5
    sampled_stage5 = sample_balanced_stage5(STAGE5_CANONICAL, TARGET_SAMPLE_SIZE, heldout_texts, base_texts)

    # Prefix Stage 5 IDs to prevent any collisions
    for idx, rec in enumerate(sampled_stage5):
        rec["sentence_id"] = f"s5_2k_{idx:05d}"

    # Merge
    merged = base_sentences + sampled_stage5
    print(f"\n[PHASE 3] Writing merged 35k dataset to: {OUT_35K_JSONL} ({len(merged):,} sentences)")
    with open(OUT_35K_JSONL, "w", encoding="utf-8") as f:
        for rec in merged:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")

    # Serialize to Zemberek format
    print(f"  Serializing to Zemberek training format: {OUT_35K_TXT}")
    from jsonl_to_zemberek_train import convert_jsonl_to_zemberek_format
    convert_jsonl_to_zemberek_format(OUT_35K_JSONL, OUT_35K_TXT)


def train_35k_model(cp: str):
    print("\n" + "=" * 80)
    print("  PHASE 4: Training FastPerceptron Model (model-distilled-35k.bin)")
    print("=" * 80 + "\n")
    print(f"Training corpus: {OUT_35K_TXT}")
    print(f"Target binary:   {OUT_35K_BIN}")

    cmd = [
        "java", "-Xmx8g", "-cp", cp,
        "zemberek.morphology.ambiguity.dataset.TrainFastAmbiguityModel",
        "--train", OUT_35K_TXT,
        "--output", OUT_35K_BIN,
        "--iterations", "7",
        "--pruneWeight", "0.05",
        "--exportText"
    ]
    t0 = time.time()
    subprocess.run(cmd, cwd=REPO_ROOT, check=True)
    dt = time.time() - t0
    print(f"\n[DONE] Model 35k training completed in {dt:.1f}s ({dt/60:.2f} min).")


def evaluate_35k_all(cp: str):
    print("\n" + "=" * 80)
    print("  PHASE 5: Evaluating model-distilled-35k.bin vs 33k-th20k on Golden Suite")
    print("=" * 80 + "\n")

    tasks = [
        {
            "name": "Golden 1,000 Benchmark",
            "file": os.path.join(GOLDEN_DIR, "golden_test_1000.jsonl"),
            "out": os.path.join(BENCH_DIR, "golden_1000_35k_vs_33k_th20k.json")
        },
        {
            "name": "Golden 2,500 Held-Out",
            "file": os.path.join(GOLDEN_DIR, "golden_test_2500.jsonl"),
            "out": os.path.join(BENCH_DIR, "golden_2500_35k_vs_33k_th20k.json")
        },
        {
            "name": "Golden Fresh 2,500 Pristine",
            "file": os.path.join(GOLDEN_DIR, "golden_fresh_2500.jsonl"),
            "out": os.path.join(BENCH_DIR, "golden_fresh_2500_35k_vs_33k_th20k.json")
        }
    ]

    results = []
    for t in tasks:
        print(f"\n--- Evaluating: {t['name']} ---")
        cmd = [
            "java", "-Xmx8g", "-cp", cp,
            "zemberek.morphology.ambiguity.dataset.EvaluateAmbiguityModel",
            "-a", t["file"],
            "-m", OUT_35K_BIN,
            "-m2", MODEL_33K_TH20K_BIN,
            "-o", t["out"],
            "-b", "-1"
        ]
        t0 = time.time()
        subprocess.run(cmd, cwd=REPO_ROOT, check=True)
        dt = time.time() - t0
        print(f"Finished in {dt:.1f}s -> {t['out']}")

        with open(t["out"], "r", encoding="utf-8") as f:
            data = json.load(f)
        results.append({"name": t["name"], "data": data})

    return results


def print_final_comparison(results):
    print("\n" + "=" * 90)
    print("           EXPERIMENT RESULTS: 33k-th20k (Base) vs 35k (2k Downsampled) vs 41k (8k Heavy)")
    print("=" * 90)

    # Load 41k results for 3-way comparison
    files_41k = [
        os.path.join(BENCH_DIR, "golden_1000_41k_vs_33k_th20k.json"),
        os.path.join(BENCH_DIR, "golden_2500_41k_vs_33k_th20k.json"),
        os.path.join(BENCH_DIR, "golden_fresh_2500_41k_vs_33k_th20k.json")
    ]
    data_41k = []
    for f in files_41k:
        with open(f) as fp:
            data_41k.append(json.load(fp))

    total_sents = sum(r["data"]["total_sentences"] for r in results)
    total_tokens = sum(r["data"]["total_tokens"] for r in results)
    total_ambig = sum(r["data"]["ambiguous_tokens"] for r in results)

    m35_ambig = sum(r["data"]["trained_ambiguous_matches"] for r in results)
    m33_ambig = sum(r["data"]["model2_ambiguous_matches"] for r in results)
    m41_ambig = sum(d["trained_ambiguous_matches"] for d in data_41k)

    m35_sent = sum(r["data"]["trained_sentence_exact_matches"] for r in results)
    m33_sent = sum(r["data"]["model2_sentence_exact_matches"] for r in results)
    m41_sent = sum(d["trained_sentence_exact_matches"] for d in data_41k)

    print(f"\nSuite Aggregate (6,000 Sentences, 67,297 Ambiguous Tokens):")
    print("-" * 90)
    print(f"{'Metric':<30} {'model-33k-th20k':<18} {'model-35k (2k)':<18} {'model-41k (8k)':<18}")
    print("-" * 90)

    acc33 = 100.0 * m33_ambig / total_ambig
    acc35 = 100.0 * m35_ambig / total_ambig
    acc41 = 100.0 * m41_ambig / total_ambig
    print(f"{'Ambiguity Accuracy:':<30} {acc33:6.2f}% ({m33_ambig})    {acc35:6.2f}% ({m35_ambig})    {acc41:6.2f}% ({m41_ambig})")
    print(f"{'Delta vs 33k-th20k:':<30} {'Baseline':<18} {acc35 - acc33:+6.2f}% ({m35_ambig - m33_ambig:+4d})   {acc41 - acc33:+6.2f}% ({m41_ambig - m33_ambig:+4d})")

    sent33 = 100.0 * m33_sent / total_sents
    sent35 = 100.0 * m35_sent / total_sents
    sent41 = 100.0 * m41_sent / total_sents
    print(f"{'Sentence Exact Match:':<30} {sent33:6.2f}% ({m33_sent})    {sent35:6.2f}% ({m35_sent})    {sent41:6.2f}% ({m41_sent})")
    print(f"{'Delta vs 33k-th20k:':<30} {'Baseline':<18} {sent35 - sent33:+6.2f}% ({m35_sent - m33_sent:+4d})   {sent41 - sent33:+6.2f}% ({m41_sent - m33_sent:+4d})")

    print("\n" + "=" * 90)
    print("Dataset-by-Dataset Ambiguity Accuracy Breakdown:")
    print("-" * 90)
    for i in range(len(results)):
        name = results[i]["name"]
        d35 = results[i]["data"]
        d41 = data_41k[i]
        
        a33 = d35["model2_ambiguity_accuracy"] * 100.0
        a35 = d35["trained_ambiguity_accuracy"] * 100.0
        a41 = d41["trained_ambiguity_accuracy"] * 100.0

        print(f"\n* {name}:")
        print(f"  - 33k-th20k: {a33:6.2f}% ({d35['model2_ambiguous_matches']}/{d35['ambiguous_tokens']})")
        print(f"  - 35k (2k):  {a35:6.2f}% ({d35['trained_ambiguous_matches']}/{d35['ambiguous_tokens']})  [vs 33k: {a35-a33:+6.2f}%, {d35['trained_ambiguous_matches']-d35['model2_ambiguous_matches']:+d}]")
        print(f"  - 41k (8k):  {a41:6.2f}% ({d41['trained_ambiguous_matches']}/{d41['ambiguous_tokens']})  [vs 33k: {a41-a33:+6.2f}%, {d41['trained_ambiguous_matches']-d41['model2_ambiguous_matches']:+d}]")

    print("\n" + "=" * 90)


def main():
    cp = get_classpath()
    merge_and_serialize()
    train_35k_model(cp)
    results = evaluate_35k_all(cp)
    print_final_comparison(results)


if __name__ == "__main__":
    main()
