#!/usr/bin/env python3
"""
Full evaluation and speed benchmark script for Golden 1K test set:
1. Disambiguates and canonicalizes golden 1K test set.
2. Evaluates Viterbi accuracy across Baseline (compressed), Current (20k), and New (30k).
3. Benchmarks throughput (words/sec) across all models.
"""

import os
import sys
import json
import time
import subprocess

DISTILLED_DIR = os.path.expanduser("~/data/turkish/distilled")
GOLDEN_CANDIDATES = os.path.join(DISTILLED_DIR, "golden_test_1000_candidates.jsonl")
GOLDEN_ANNOTATED = os.path.join(DISTILLED_DIR, "golden_test_1000_annotated.jsonl")
GOLDEN_CANONICAL = os.path.join(DISTILLED_DIR, "golden_test_1000_canonical.jsonl")

MODEL_30K = "data/ambiguity/model-distilled-30k.bin"
MODEL_20K = "morphology/src/main/resources/tr/ambiguity/model-distilled-20k.bin"
EVAL_OUTPUT = "evaluation_golden_1000_3way.json"


def run_golden_disambiguation():
    print("================================================================================")
    print("Step A: Disambiguating Golden 1K Test Set via Gemini 3.8 Flash (Thinking=256)")
    print("================================================================================")
    cmd = [
        "python3",
        "experiment/scripts/disambiguate_batch_fast.py",
        "-i", GOLDEN_CANDIDATES,
        "-o", GOLDEN_ANNOTATED,
        "-c", "12",
        "-b", "256"
    ]
    subprocess.run(cmd, check=True)

    print("\nCanonicalizing Golden 1K Lexicon Twins...")
    cmd_can = [
        "python3",
        "experiment/scripts/canonicalize_lexicon_twins.py",
        "-i", GOLDEN_ANNOTATED,
        "-o", GOLDEN_CANONICAL
    ]
    subprocess.run(cmd_can, check=True)


def run_viterbi_evaluation():
    print("\n================================================================================")
    print("Step B: Evaluating Viterbi Accuracy on Golden 1K Dataset")
    print("================================================================================")
    cmd = [
        "java",
        "-cp", "experiment/target/classes:morphology/target/classes:core/target/classes:morphology/target/test-classes:/tmp/mvn_cp.txt",
        "zemberek.morphology.ambiguity.dataset.EvaluateAmbiguityModel",
        "-a", GOLDEN_CANONICAL,
        "-m", MODEL_30K,
        "-m2", MODEL_20K,
        "-o", EVAL_OUTPUT,
        "-b", "-1"  # Exact Viterbi
    ]
    # Build classpath if needed
    cp = subprocess.check_output("cat /tmp/mvn_cp.txt 2>/dev/null || mvn -f morphology/pom.xml dependency:build-classpath -Dmdep.outputFile=/tmp/mvn_cp.txt -q && cat /tmp/mvn_cp.txt", shell=True).decode().strip()
    full_cp = f"experiment/target/classes:morphology/target/classes:core/target/classes:morphology/target/test-classes:{cp}"
    
    cmd[2] = full_cp
    subprocess.run(cmd, check=True)


def run_speed_benchmark():
    print("\n================================================================================")
    print("Step C: Speed & Throughput Benchmark (Words/sec on JVM 25)")
    print("================================================================================")
    cp = subprocess.check_output("cat /tmp/mvn_cp.txt", shell=True).decode().strip()
    full_cp = f"experiment/target/classes:morphology/target/classes:core/target/classes:morphology/target/test-classes:{cp}"

    txt_corpus = os.path.join(DISTILLED_DIR, "golden_test_1000_sentences.txt")
    
    # 1. Benchmark Baseline (model-compressed)
    print("\n--- 1. Baseline Model (model-compressed) ---")
    cmd_base = [
        "java", "-cp", full_cp,
        "zemberek.morphology.ambiguity.dataset.BenchmarkAmbiguityResolver",
        "-c", txt_corpus,
        "-it", "3",
        "-w", "2"
    ]
    subprocess.run(cmd_base, check=True)

    # 2. Benchmark Current 20k Model
    print("\n--- 2. Current 20k Model (model-distilled-20k.bin) ---")
    cmd_20k = [
        "java", "-cp", full_cp,
        "zemberek.morphology.ambiguity.dataset.BenchmarkAmbiguityResolver",
        "-c", txt_corpus,
        "-m", MODEL_20K,
        "-it", "3",
        "-w", "2"
    ]
    subprocess.run(cmd_20k, check=True)

    # 3. Benchmark New 30k Model
    print("\n--- 3. New 30k Model (model-distilled-30k.bin) ---")
    cmd_30k = [
        "java", "-cp", full_cp,
        "zemberek.morphology.ambiguity.dataset.BenchmarkAmbiguityResolver",
        "-c", txt_corpus,
        "-m", MODEL_30K,
        "-it", "3",
        "-w", "2"
    ]
    subprocess.run(cmd_30k, check=True)


def main():
    run_golden_disambiguation()
    run_viterbi_evaluation()
    run_speed_benchmark()


if __name__ == "__main__":
    main()

