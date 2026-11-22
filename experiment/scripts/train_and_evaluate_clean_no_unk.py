#!/usr/bin/env python3
"""
Train FastPerceptron model on 100% Clean (Zero UNKNOWN) 33K dataset,
and run comparative evaluation against baseline models on all golden benchmarks.
"""

import os
import sys
import json
import time
import subprocess
from pathlib import Path

REPO_ROOT = "/home/dndara/work/zemberek-nlp"
DATA_DIR = os.path.join(REPO_ROOT, "data/ambiguity")

# Training texts (already generated)
TRAIN_TXT_CLEAN_33K = os.path.join(DATA_DIR, "train_combined_33k_clean_no_unk.zemberek.txt")
TRAIN_TXT_CLEAN_TH20K = os.path.join(DATA_DIR, "train_combined_33k_th20k_clean_no_unk.zemberek.txt")

# New model output binaries
MODEL_CLEAN_33K_BIN = os.path.join(DATA_DIR, "model-distilled-33k-clean-no-unk.bin")
MODEL_CLEAN_TH20K_BIN = os.path.join(DATA_DIR, "model-distilled-33k-th20k-clean-no-unk.bin")

# Baseline models
MODEL_BASE_33K_BIN = os.path.join(DATA_DIR, "model-distilled-33k.bin")
MODEL_BASE_TH20K_BIN = os.path.join(DATA_DIR, "model-distilled-33k-th20k.bin")

# Evaluation test sets
GOLDEN_FRESH_2500 = os.path.join(DATA_DIR, "golden_fresh_2500_canonical.jsonl")
GOLDEN_1000 = os.path.join(DATA_DIR, "golden_test_1000_thinking500.jsonl")
GOLDEN_2500 = os.path.join(DATA_DIR, "golden_test_2500_thinking500.jsonl")


def get_classpath():
    cp_file = "/tmp/full_mvn_cp.txt"
    if not os.path.exists(cp_file):
        subprocess.run(
            "mvn dependency:build-classpath -Dmdep.outputFile=/tmp/full_mvn_cp.txt -q",
            shell=True, check=True, cwd=REPO_ROOT
        )
    with open(cp_file) as f:
        cp = f.read().strip()
    return f"experiment/target/classes:apps/target/classes:morphology/target/classes:core/target/classes:tokenization/target/classes:lm/target/classes:{cp}"


def train_model(train_txt: str, output_bin: str, label: str):
    print("\n" + "=" * 80)
    print(f"  TRAINING MODEL: {label}")
    print(f"  Input:  {train_txt}")
    print(f"  Output: {output_bin}")
    print("=" * 80)

    cp = get_classpath()
    cmd = [
        "java", "-cp", cp,
        "zemberek.morphology.ambiguity.dataset.TrainFastAmbiguityModel",
        "--train", train_txt,
        "--output", output_bin,
        "--iterations", "5",
        "--pruneWeight", "0.05",
        "--exportText"
    ]
    t0 = time.time()
    subprocess.run(cmd, cwd=REPO_ROOT, check=True)
    dt = time.time() - t0
    bin_size_mb = os.path.getsize(output_bin) / (1024 * 1024)
    print(f"[DONE] Trained in {dt:.1f}s ({dt/60:.2f} min) -> {output_bin} ({bin_size_mb:.2f} MB)")


def evaluate_pair(model_clean: str, model_baseline: str, pair_label: str, out_prefix: str):
    print("\n" + "=" * 80)
    print(f"  EVALUATING: {pair_label}")
    print(f"  Clean Model:    {os.path.basename(model_clean)}")
    print(f"  Baseline Model: {os.path.basename(model_baseline)}")
    print("=" * 80)

    cp = get_classpath()
    eval_tasks = [
        ("Golden Fresh 2500 (Held-Out)", GOLDEN_FRESH_2500, f"{out_prefix}_fresh2500.json"),
        ("Golden 1000 (Adjudicated)", GOLDEN_1000, f"{out_prefix}_golden1000.json"),
        ("Golden 2500 (Adjudicated)", GOLDEN_2500, f"{out_prefix}_golden2500.json"),
    ]

    results = []
    for label, test_path, out_json in eval_tasks:
        print(f"\n--- Benchmark: {label} ---")
        cmd = [
            "java", "-cp", cp,
            "zemberek.morphology.ambiguity.dataset.EvaluateAmbiguityModel",
            "-a", test_path,
            "-m", model_clean,
            "-m2", model_baseline,
            "-o", out_json,
            "-b", "-1"
        ]
        subprocess.run(cmd, cwd=REPO_ROOT, check=True)

        with open(out_json, "r") as f:
            d = json.load(f)
        results.append((label, d))

    print("\n" + "#" * 80)
    print(f"  EXECUTIVE COMPARISON: {pair_label}")
    print("#" * 80)

    for label, d in results:
        clean_amb = d['trained_ambiguity_accuracy'] * 100
        clean_ovr = d['trained_overall_accuracy'] * 100
        clean_em = d['trained_sentence_exact_match_rate'] * 100

        base_amb = d['model2_ambiguity_accuracy'] * 100
        base_ovr = d['model2_overall_accuracy'] * 100
        base_em = d['model2_sentence_exact_match_rate'] * 100

        diff_amb = clean_amb - base_amb
        diff_ovr = clean_ovr - base_ovr
        diff_em = clean_em - base_em

        print(f"\nBenchmark Dataset: {label}")
        print(f"  Clean (No UNK) Model:  Ambiguity: {clean_amb:6.2f}% | Overall: {clean_ovr:6.2f}% | ExactMatch: {clean_em:6.2f}%")
        print(f"  Baseline (With UNK):   Ambiguity: {base_amb:6.2f}% | Overall: {base_ovr:6.2f}% | ExactMatch: {base_em:6.2f}%")
        print(f"  Delta (Clean vs Base): Ambiguity: {diff_amb:+6.2f}% | Overall: {diff_ovr:+6.2f}% | ExactMatch: {diff_em:+6.2f}%")


def main():
    t_start = time.time()

    # Step 1: Train Clean Model 1 (33K standard clean)
    train_model(TRAIN_TXT_CLEAN_33K, MODEL_CLEAN_33K_BIN, "33K Standard Clean (No UNKs)")

    # Step 2: Evaluate Clean Model 1 vs Baseline 33K
    evaluate_pair(
        MODEL_CLEAN_33K_BIN,
        MODEL_BASE_33K_BIN,
        "33K Standard: Clean (No UNK) vs Original Baseline (With UNK)",
        os.path.join(DATA_DIR, "eval_clean33k_vs_base33k")
    )

    # Step 3: Train Clean Model 2 (33K th20k clean)
    train_model(TRAIN_TXT_CLEAN_TH20K, MODEL_CLEAN_TH20K_BIN, "33K th20k Clean (Thinking-500 + No UNKs)")

    # Step 4: Evaluate Clean Model 2 vs Baseline th20k
    evaluate_pair(
        MODEL_CLEAN_TH20K_BIN,
        MODEL_BASE_TH20K_BIN,
        "33K th20k: Clean (No UNK) vs Original Baseline (With UNK)",
        os.path.join(DATA_DIR, "eval_cleanth20k_vs_baseth20k")
    )

    total_time = time.time() - t_start
    print("\n" + "#" * 80)
    print(f"  ALL TRAININGS & BENCHMARKS COMPLETED IN {total_time/60:.2f} min")
    print("#" * 80 + "\n")


if __name__ == "__main__":
    main()
