#!/usr/bin/env python3
"""
Master runner for Golden Test Set Adjudication & Evaluation:
1. Re-adjudicates golden_test_1000 with Gemini 3.8 Flash (Thinking=500 + Zemberek native keys).
2. Re-adjudicates golden_test_2500 with Gemini 3.8 Flash (Thinking=500 + Zemberek native keys).
3. Adjudicates golden_fresh_2500 (pristine held-out) with Gemini 3.8 Flash (Thinking=500 + Zemberek native keys).
4. Runs 3-way comparative evaluation across model-distilled-33k-th10k.bin, model-distilled-33k.bin, and baseline.
"""

import os
import sys
import json
import time
import subprocess

REPO_ROOT = "/home/dndara/work/zemberek-nlp"
DISTILLED_DIR = "/home/dndara/data/turkish/distilled"
DATA_DIR = os.path.join(REPO_ROOT, "data/ambiguity")

SETS = [
    {
        "name": "golden_test_1000",
        "input": os.path.join(DISTILLED_DIR, "golden_test_1000_candidates.jsonl"),
        "output": os.path.join(DATA_DIR, "golden_test_1000_thinking500.jsonl"),
        "registry": os.path.join(DATA_DIR, "batch_registry_golden1000_thinking500.json"),
        "summary": os.path.join(DATA_DIR, "golden_test_1000_thinking500_summary.json"),
        "batch_size": 500,
        "eval_output": os.path.join(DATA_DIR, "benchmark_golden1000_thinking500_3way.json")
    },
    {
        "name": "golden_test_2500",
        "input": os.path.join(DISTILLED_DIR, "golden_test_2500_candidates.jsonl"),
        "output": os.path.join(DATA_DIR, "golden_test_2500_thinking500.jsonl"),
        "registry": os.path.join(DATA_DIR, "batch_registry_golden2500_thinking500.json"),
        "summary": os.path.join(DATA_DIR, "golden_test_2500_thinking500_summary.json"),
        "batch_size": 500,
        "eval_output": os.path.join(DATA_DIR, "benchmark_golden2500_thinking500_3way.json")
    },
    {
        "name": "golden_fresh_2500",
        "input": os.path.join(DATA_DIR, "golden_fresh_2500_candidates.jsonl"),
        "output": os.path.join(DATA_DIR, "golden_fresh_2500_canonical.jsonl"),
        "registry": os.path.join(DATA_DIR, "batch_registry_goldenfresh2500_thinking500.json"),
        "summary": os.path.join(DATA_DIR, "golden_fresh_2500_summary.json"),
        "batch_size": 500,
        "eval_output": os.path.join(DATA_DIR, "benchmark_goldenfresh2500_3way.json")
    }
]

NEW_MODEL_BIN = os.path.join(DATA_DIR, "model-distilled-33k-th10k.bin")
OLD_MODEL_BIN = os.path.join(DATA_DIR, "model-distilled-33k.bin")


def get_classpath():
    cp_file = "/tmp/full_mvn_cp.txt"
    if not os.path.exists(cp_file):
        subprocess.run("mvn dependency:build-classpath -Dmdep.outputFile=/tmp/full_mvn_cp.txt -q", cwd=REPO_ROOT, shell=True, check=True)
    with open(cp_file) as f:
        cp = f.read().strip()
    return f"experiment/target/classes:apps/target/classes:morphology/target/classes:core/target/classes:tokenization/target/classes:lm/target/classes:{cp}"


def run_adjudication_step(sinfo):
    print("\n" + "=" * 80)
    print(f"  ADJUDICATING: {sinfo['name']} via Gemini Batch API (ThinkingBudget=500)")
    print("=" * 80 + "\n")

    cmd = [
        "python3", "experiment/scripts/run_batch_evaluation_10k.py",
        "--input", sinfo["input"],
        "--output", sinfo["output"],
        "--registry", sinfo["registry"],
        "--summary", sinfo["summary"],
        "--batch-size", str(sinfo["batch_size"]),
        "--thinking-budget", "500",
        "--poll-interval", "15"
    ]
    t0 = time.time()
    subprocess.run(cmd, cwd=REPO_ROOT, check=True)
    dt = time.time() - t0
    print(f"[DONE] {sinfo['name']} completed in {dt:.1f}s ({dt/60:.2f} min).")


def run_benchmark_eval(sinfo, cp):
    print("\n" + "=" * 80)
    print(f"  BENCHMARKING: {sinfo['name']} (model-distilled-33k-th10k vs 33k vs baseline)")
    print("=" * 80 + "\n")

    cmd = [
        "java", "-cp", cp,
        "zemberek.morphology.ambiguity.dataset.EvaluateAmbiguityModel",
        "-a", sinfo["output"],
        "-m", NEW_MODEL_BIN,
        "-m2", OLD_MODEL_BIN,
        "-o", sinfo["eval_output"],
        "-b", "-1"
    ]
    subprocess.run(cmd, cwd=REPO_ROOT, check=True)


def main():
    t_start = time.time()
    print("Launching Comprehensive Golden Set Adjudication & Evaluation Pipeline...")

    # Phase 1: Adjudicate all three sets
    for sinfo in SETS:
        run_adjudication_step(sinfo)

    # Phase 2: Benchmark models on newly adjudicated sets
    cp = get_classpath()
    for sinfo in SETS:
        run_benchmark_eval(sinfo, cp)

    total_time = time.time() - t_start
    print("\n" + "#" * 80)
    print(f"  ALL GOLDEN SET ADJUDICATIONS & BENCHMARKS COMPLETED IN {total_time/60:.2f} min")
    print("#" * 80 + "\n")


if __name__ == "__main__":
    main()

