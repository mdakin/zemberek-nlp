#!/usr/bin/env python3
"""
train_and_evaluate_41k_golden.py

Trains model-distilled-41k.bin on the canonicalized 41k dataset (train_combined_41k.zemberek.txt)
and evaluates it against model-distilled-33k-th20k.bin across all three Golden benchmarks:
1. Golden 1,000 Benchmark (1,000 sentences, 11,325 ambiguous)
2. Golden 2,500 Held-Out (2,500 sentences, 27,799 ambiguous)
3. Golden Fresh 2,500 Pristine (2,500 sentences, 28,173 ambiguous)
"""

import os
import sys
import json
import time
import subprocess
import argparse
from pathlib import Path

REPO_ROOT = "/home/dndara/work/zemberek-nlp"
TRAIN_TXT = os.path.join(REPO_ROOT, "data/ambiguity/train_combined_41k.zemberek.txt")
MODEL_41K_BIN = os.path.join(REPO_ROOT, "data/ambiguity/model-distilled-41k.bin")
MODEL_41K_TXT = os.path.join(REPO_ROOT, "data/ambiguity/model-distilled-41k.txt")
MODEL_33K_TH20K_BIN = os.path.join(REPO_ROOT, "data/ambiguity/model-distilled-33k-th20k.bin")

GOLDEN_DIR = os.path.join(REPO_ROOT, "data/ambiguity/golden")
BENCH_DIR = os.path.join(GOLDEN_DIR, "benchmark_results")

TEST_SUITE = [
    {
        "key": "golden_1000",
        "name": "Golden 1,000 Benchmark",
        "file": os.path.join(GOLDEN_DIR, "golden_test_1000.jsonl"),
        "out_json": os.path.join(BENCH_DIR, "golden_1000_41k_vs_33k_th20k.json"),
    },
    {
        "key": "golden_2500",
        "name": "Golden 2,500 Held-Out",
        "file": os.path.join(GOLDEN_DIR, "golden_test_2500.jsonl"),
        "out_json": os.path.join(BENCH_DIR, "golden_2500_41k_vs_33k_th20k.json"),
    },
    {
        "key": "golden_fresh_2500",
        "name": "Golden Fresh 2,500 Pristine",
        "file": os.path.join(GOLDEN_DIR, "golden_fresh_2500.jsonl"),
        "out_json": os.path.join(BENCH_DIR, "golden_fresh_2500_41k_vs_33k_th20k.json"),
    }
]


def get_classpath() -> str:
    cp_file = "/tmp/full_mvn_cp.txt"
    if not os.path.exists(cp_file):
        print("[INFO] Generating Maven classpath...")
        subprocess.run(
            "mvn dependency:build-classpath -Dmdep.outputFile=/tmp/full_mvn_cp.txt -q",
            cwd=REPO_ROOT, shell=True, check=True
        )
    with open(cp_file) as f:
        cp = f.read().strip()
    return f"experiment/target/classes:apps/target/classes:morphology/target/classes:core/target/classes:tokenization/target/classes:lm/target/classes:{cp}"


def train_41k_model(cp: str):
    print("\n" + "=" * 80)
    print("  PHASE 1: Retraining model-distilled-41k.bin (Averaged Perceptron, 7 iterations)")
    print("=" * 80 + "\n")
    print(f"Training corpus: {TRAIN_TXT}")
    print(f"Target binary:   {MODEL_41K_BIN}")
    print(f"Target text:     {MODEL_41K_TXT}")

    cmd = [
        "java", "-Xmx8g", "-cp", cp,
        "zemberek.morphology.ambiguity.dataset.TrainFastAmbiguityModel",
        "--train", TRAIN_TXT,
        "--output", MODEL_41K_BIN,
        "--iterations", "7",
        "--pruneWeight", "0.05",
        "--exportText"
    ]
    t0 = time.time()
    subprocess.run(cmd, cwd=REPO_ROOT, check=True)
    dt = time.time() - t0
    print(f"\n[DONE] Model training completed in {dt:.1f}s ({dt/60:.2f} min).")


def evaluate_models(cp: str):
    print("\n" + "=" * 80)
    print("  PHASE 2: Evaluating model-distilled-41k.bin vs model-distilled-33k-th20k.bin")
    print("=" * 80 + "\n")

    os.makedirs(BENCH_DIR, exist_ok=True)
    results = []

    for task in TEST_SUITE:
        print(f"\n--- Evaluating on: {task['name']} ---")
        cmd = [
            "java", "-Xmx8g", "-cp", cp,
            "zemberek.morphology.ambiguity.dataset.EvaluateAmbiguityModel",
            "-a", task["file"],
            "-m", MODEL_41K_BIN,
            "-m2", MODEL_33K_TH20K_BIN,
            "-o", task["out_json"],
            "-b", "-1"
        ]
        t0 = time.time()
        subprocess.run(cmd, cwd=REPO_ROOT, check=True)
        dt = time.time() - t0
        print(f"Evaluation finished in {dt:.1f}s -> {task['out_json']}")

        with open(task["out_json"], "r", encoding="utf-8") as f:
            data = json.load(f)

        results.append({
            "key": task["key"],
            "name": task["name"],
            "data": data
        })

    return results


def print_and_save_summary(results):
    print("\n" + "=" * 90)
    print("                FINAL 41K BENCHMARK EVALUATION SUMMARY")
    print("=" * 90)

    total_sents = sum(r["data"]["total_sentences"] for r in results)
    total_tokens = sum(r["data"]["total_tokens"] for r in results)
    total_ambig = sum(r["data"]["ambiguous_tokens"] for r in results)

    m1_ambig_matches = sum(r["data"]["trained_ambiguous_matches"] for r in results)
    m2_ambig_matches = sum(r["data"]["model2_ambiguous_matches"] for r in results)
    base_ambig_matches = sum(r["data"]["default_ambiguous_matches"] for r in results)

    m1_overall_matches = sum(r["data"]["trained_overall_matches"] for r in results)
    m2_overall_matches = sum(r["data"]["model2_overall_matches"] for r in results)

    m1_sent_matches = sum(r["data"]["trained_sentence_exact_matches"] for r in results)
    m2_sent_matches = sum(r["data"]["model2_sentence_exact_matches"] for r in results)

    print(f"\nAggregate Suite (3 Sets Combined: {total_sents:,} Sentences, {total_tokens:,} Tokens, {total_ambig:,} Ambiguous):")
    print("-" * 90)
    print(f"{'Metric':<30} {'model-41k':<20} {'model-33k-th20k':<20} {'Delta (41k - 33k)':<15}")
    print("-" * 90)

    acc1 = 100.0 * m1_ambig_matches / total_ambig
    acc2 = 100.0 * m2_ambig_matches / total_ambig
    d_acc = acc1 - acc2
    d_cnt = m1_ambig_matches - m2_ambig_matches
    sign = "+" if d_cnt >= 0 else ""
    print(f"{'Ambiguity Accuracy:':<30} {acc1:6.2f}% ({m1_ambig_matches}/{total_ambig})  {acc2:6.2f}% ({m2_ambig_matches}/{total_ambig})  {sign}{d_acc:.2f}% ({sign}{d_cnt})")

    o_acc1 = 100.0 * m1_overall_matches / total_tokens
    o_acc2 = 100.0 * m2_overall_matches / total_tokens
    d_oacc = o_acc1 - o_acc2
    print(f"{'Overall Token Accuracy:':<30} {o_acc1:6.2f}% ({m1_overall_matches}/{total_tokens})  {o_acc2:6.2f}% ({m2_overall_matches}/{total_tokens})  {sign}{d_oacc:.2f}%")

    s_acc1 = 100.0 * m1_sent_matches / total_sents
    s_acc2 = 100.0 * m2_sent_matches / total_sents
    d_sacc = s_acc1 - s_acc2
    print(f"{'Sentence Exact Match:':<30} {s_acc1:6.2f}% ({m1_sent_matches}/{total_sents})  {s_acc2:6.2f}% ({m2_sent_matches}/{total_sents})  {sign}{d_sacc:.2f}%")

    print("\n" + "=" * 90)
    print("Dataset-by-Dataset Breakdown:")
    print("-" * 90)
    for r in results:
        d = r["data"]
        a1 = d["trained_ambiguity_accuracy"] * 100.0
        a2 = d["model2_ambiguity_accuracy"] * 100.0
        diff = a1 - a2
        s = "+" if diff >= 0 else ""
        print(f"\n* {r['name']} ({d['total_sentences']:,} sents, {d['ambiguous_tokens']:,} ambig tokens):")
        print(f"  - 41k Accuracy:       {a1:.2f}% ({d['trained_ambiguous_matches']}/{d['ambiguous_tokens']})")
        print(f"  - 33k-th20k Accuracy: {a2:.2f}% ({d['model2_ambiguous_matches']}/{d['ambiguous_tokens']})")
        print(f"  - Net Advantage:      {s}{diff:.2f}% ({s}{d['trained_ambiguous_matches'] - d['model2_ambiguous_matches']} tokens)")
        print(f"  - Sentence Match:     41k: {d['trained_sentence_exact_match_rate']*100:.2f}% vs 33k-th20k: {d['model2_sentence_exact_match_rate']*100:.2f}%")

    print("\n" + "=" * 90)


def check_target_word_forensics(results):
    print("\nTarget Word Analysis (hal, yüzde, hakkında across all 3 test sets):")
    print("-" * 90)
    target_stems = ["hal", "yüzde", "hakkında"]

    for target in target_stems:
        total_target = 0
        m1_correct = 0
        m2_correct = 0
        for r in results:
            for sent in r["data"].get("sentences", []):
                for tok in sent.get("tokens", []):
                    surf = tok.get("surface", "").lower()
                    if surf == target or surf.startswith(target):
                        if tok.get("is_ambiguous"):
                            total_target += 1
                            if tok.get("trained_matches_gemini"):
                                m1_correct += 1
                            if tok.get("model2_matches_gemini"):
                                m2_correct += 1

        print(f"Word pattern '{target}*': {total_target} ambiguous tokens")
        print(f"  - 41k Correct:       {m1_correct}/{total_target} ({m1_correct/max(1, total_target)*100:.2f}%)")
        print(f"  - 33k-th20k Correct: {m2_correct}/{total_target} ({m2_correct/max(1, total_target)*100:.2f}%)")
        print(f"  - Net Difference:    {m1_correct - m2_correct:+d}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-train", action="store_true", help="Skip model retraining if .bin already exists")
    args = parser.parse_args()

    cp = get_classpath()
    if not args.skip_train or not os.path.exists(MODEL_41K_BIN):
        train_41k_model(cp)
    else:
        print(f"[INFO] Skipping training, using existing {MODEL_41K_BIN}")

    results = evaluate_models(cp)
    print_and_save_summary(results)
    check_target_word_forensics(results)


if __name__ == "__main__":
    main()
