#!/usr/bin/env python3
"""
Master End-to-End Pipeline:
1. Resumes and finishes 10K LLM disambiguation (batch 3).
2. Canonicalizes, merges (20K + 10K + 2.2K contrastive = 32,214 sents), and converts to Zemberek format.
3. Trains new model-distilled-30k.bin via TrainFastAmbiguityModel.
4. Disambiguates and canonicalizes Golden 1K test set.
5. Runs exact Viterbi 3-way evaluation on Golden 1K dataset.
6. Runs speed & throughput benchmark across all 3 models on JVM 25.
"""

import os
import sys
import json
import time
import subprocess

DISTILLED_DIR = "/home/dndara/data/turkish/distilled"
REPO_DATA_DIR = "/home/dndara/work/zemberek-nlp/data/ambiguity"

BATCH3_CANDIDATES = os.path.join(DISTILLED_DIR, "distilled_batch3_10k_candidates.jsonl")
BATCH3_ANNOTATED = os.path.join(DISTILLED_DIR, "distilled_batch3_10k_annotated.jsonl")
GOLDEN_CANDIDATES = os.path.join(DISTILLED_DIR, "golden_test_1000_candidates.jsonl")
GOLDEN_ANNOTATED = os.path.join(DISTILLED_DIR, "golden_test_1000_annotated.jsonl")
GOLDEN_CANONICAL = os.path.join(DISTILLED_DIR, "golden_test_1000_canonical.jsonl")
GOLDEN_SENTENCES = os.path.join(DISTILLED_DIR, "golden_test_1000_sentences.txt")

TRAIN_TXT_30K = os.path.join(REPO_DATA_DIR, "train_combined_30k.zemberek.txt")
MODEL_30K = os.path.join(REPO_DATA_DIR, "model-distilled-30k.bin")
MODEL_20K = "morphology/src/main/resources/tr/ambiguity/model-distilled-20k.bin"
EVAL_JSON = "evaluation_golden_1000_3way.json"


def get_classpath():
    cp_file = "/tmp/mvn_experiment_cp.txt"
    if not os.path.exists(cp_file):
        subprocess.run("mvn -f experiment/pom.xml dependency:build-classpath -Dmdep.outputFile=/tmp/mvn_experiment_cp.txt -q", shell=True, check=True)
    with open(cp_file) as f:
        cp = f.read().strip()
    return f"experiment/target/classes:apps/target/classes:morphology/target/classes:core/target/classes:tokenization/target/classes:lm/target/classes:morphology/target/test-classes:{cp}"


def log_step(title):
    print("\n" + "=" * 80)
    print(f"  {title}")
    print("=" * 80 + "\n")


def step1_finish_10k_disambiguation():
    log_step("STEP 1: Finishing 10K Disambiguation (Batch 3)")
    cmd = [
        "python3",
        "experiment/scripts/disambiguate_batch_fast.py",
        "-i", BATCH3_CANDIDATES,
        "-o", BATCH3_ANNOTATED,
        "-c", "14",
        "-b", "256"
    ]
    subprocess.run(cmd, check=True)


def step2_build_30k_training_set():
    log_step("STEP 2: Canonicalizing & Merging into 32K+ Dataset")
    cmd = ["python3", "experiment/scripts/build_30k_training_dataset.py"]
    subprocess.run(cmd, check=True)


def step3_train_model():
    log_step("STEP 3: Training model-distilled-30k.bin via FastPerceptronTrainer")
    cp = get_classpath()
    cmd = [
        "java",
        "-cp", cp,
        "zemberek.morphology.ambiguity.dataset.TrainFastAmbiguityModel",
        "--train", TRAIN_TXT_30K,
        "--output", MODEL_30K,
        "--iterations", "5",
        "--pruneWeight", "0.05",
        "--exportText"
    ]
    t0 = time.time()
    subprocess.run(cmd, check=True)
    print(f"\n[DONE] Model training completed in {time.time() - t0:.1f}s -> {MODEL_30K}")


def step4_disambiguate_golden_1k():
    log_step("STEP 4: Disambiguating Golden 1K Test Set via Gemini 3.8 Flash")
    cmd = [
        "python3",
        "experiment/scripts/disambiguate_batch_fast.py",
        "-i", GOLDEN_CANDIDATES,
        "-o", GOLDEN_ANNOTATED,
        "-c", "12",
        "-b", "256"
    ]
    subprocess.run(cmd, check=True)

    print("\nCanonicalizing Golden 1K...")
    cmd_can = [
        "python3",
        "experiment/scripts/canonicalize_lexicon_twins.py",
        "-i", GOLDEN_ANNOTATED,
        "-o", GOLDEN_CANONICAL
    ]
    subprocess.run(cmd_can, check=True)


def step5_evaluate_viterbi():
    log_step("STEP 5: Evaluating Exact Viterbi Accuracy on Golden 1K Dataset")
    cp = get_classpath()
    cmd = [
        "java",
        "-cp", cp,
        "zemberek.morphology.ambiguity.dataset.EvaluateAmbiguityModel",
        "-a", GOLDEN_CANONICAL,
        "-m", MODEL_30K,
        "-m2", MODEL_20K,
        "-o", EVAL_JSON,
        "-b", "-1"  # Exact Viterbi
    ]
    subprocess.run(cmd, check=True)


def step6_speed_benchmark():
    log_step("STEP 6: Speed & Throughput Benchmark on JVM 25")
    cp = get_classpath()

    # Baseline
    print("\n--- 1. Baseline Model (model-compressed) ---")
    subprocess.run([
        "java", "-cp", cp,
        "zemberek.morphology.ambiguity.dataset.BenchmarkAmbiguityResolver",
        "-c", GOLDEN_SENTENCES,
        "-it", "3", "-w", "2"
    ], check=True)

    # Current 20k
    print("\n--- 2. Current 20k Model (model-distilled-20k.bin) ---")
    subprocess.run([
        "java", "-cp", cp,
        "zemberek.morphology.ambiguity.dataset.BenchmarkAmbiguityResolver",
        "-c", GOLDEN_SENTENCES,
        "-m", MODEL_20K,
        "-it", "3", "-w", "2"
    ], check=True)

    # New 30k
    print("\n--- 3. New 30k Model (model-distilled-30k.bin) ---")
    subprocess.run([
        "java", "-cp", cp,
        "zemberek.morphology.ambiguity.dataset.BenchmarkAmbiguityResolver",
        "-c", GOLDEN_SENTENCES,
        "-m", MODEL_30K,
        "-it", "3", "-w", "2"
    ], check=True)


def main():
    start_total = time.time()
    step1_finish_10k_disambiguation()
    step2_build_30k_training_set()
    step3_train_model()
    step4_disambiguate_golden_1k()
    step5_evaluate_viterbi()
    step6_speed_benchmark()
    print(f"\n" + "#" * 80)
    print(f"# MASTER PIPELINE COMPLETED SUCCESSFULLY IN {(time.time() - start_total)/60:.1f} MINUTES!")
    print("#" * 80)


if __name__ == "__main__":
    main()

