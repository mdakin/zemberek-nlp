#!/usr/bin/env python3
"""
Builds training corpus with Thinking-Budget-500 Batch 1 (10K),
trains new FastPerceptron model (model-distilled-33k-th10k.bin),
and runs 3-way benchmark evaluation against model-distilled-33k.bin and baseline.
Guaranteed to NEVER overwrite any existing datasets or models.
"""

import os
import sys
import json
import time
import subprocess

REPO_ROOT = "."
DISTILLED_DIR = os.path.expanduser("~/data/turkish/distilled")
DATA_DIR = os.path.join(REPO_ROOT, "data/ambiguity")

# Input datasets
FILE_THINKING_10K = os.path.join(DATA_DIR, "distilled_10k_thinking500_annotated.jsonl")
FILE_BATCH2_10K = os.path.join(DISTILLED_DIR, "distilled_batch2_10k_annotated.jsonl")
FILE_BATCH3_10K = os.path.join(DISTILLED_DIR, "distilled_batch3_10k_canonical.jsonl")
FILE_CONTRASTIVE_MASTER = os.path.join(DATA_DIR, "contrastive_master_canonical.jsonl")

MANIFEST_PATH = os.path.join(DISTILLED_DIR, "PERMANENT_HELDOUT_TEST_SET_MANIFEST.json")

# New, dedicated output files (zero overwrite of existing files)
OUT_COMBINED_JSONL = os.path.join(DATA_DIR, "train_combined_33k_th10k.jsonl")
OUT_COMBINED_TXT = os.path.join(DATA_DIR, "train_combined_33k_th10k.zemberek.txt")
NEW_MODEL_BIN = os.path.join(DATA_DIR, "model-distilled-33k-th10k.bin")

# Reference models and test sets
EXISTING_33K_MODEL_BIN = os.path.join(DATA_DIR, "model-distilled-33k.bin")
HELDOUT_2500_CANONICAL = os.path.join(DISTILLED_DIR, "golden_test_2500_canonical.jsonl")
GOLDEN_1000_CANONICAL = os.path.join(DISTILLED_DIR, "golden_test_1000_canonical.jsonl")

EVAL_2500_OUTPUT = os.path.join(DATA_DIR, "eval_golden_2500_th10k_vs_33k.json")
EVAL_1000_OUTPUT = os.path.join(DATA_DIR, "eval_golden_1000_th10k_vs_33k.json")


def log_phase(title):
    print("\n" + "=" * 80)
    print(f"  {title}")
    print("=" * 80 + "\n")


def get_classpath():
    cp_file = "/tmp/full_mvn_cp.txt"
    if not os.path.exists(cp_file):
        subprocess.run("mvn dependency:build-classpath -Dmdep.outputFile=/tmp/full_mvn_cp.txt -q", cwd=REPO_ROOT, shell=True, check=True)
    with open(cp_file) as f:
        cp = f.read().strip()
    return f"experiment/target/classes:apps/target/classes:morphology/target/classes:core/target/classes:tokenization/target/classes:lm/target/classes:{cp}"


def phase1_merge_datasets():
    log_phase("PHASE 1: Merging Datasets with Anti-Leakage Guard into train_combined_33k_th10k.jsonl")

    # Load permanent held-out blacklist manifest
    heldout_ids = set()
    heldout_texts = set()
    if os.path.exists(MANIFEST_PATH):
        with open(MANIFEST_PATH, "r", encoding="utf-8") as mf:
            mdata = json.load(mf)
            for sname, sinfo in mdata.get("sets", {}).items():
                for sid in sinfo.get("sentence_ids", []):
                    heldout_ids.add(sid)
                for txt in sinfo.get("sentence_texts", []):
                    heldout_texts.add(txt.strip().lower())
        print(f"  [GUARD] Enforcing zero-leakage guard against {len(heldout_ids)} permanent held-out test sentences.")

    seen_ids = set()
    seen_texts = set()
    total_count = 0

    sources = [
        (FILE_THINKING_10K, "Thinking-500 Batch 1 (10K)"),
        (FILE_BATCH2_10K, "Batch 2 Annotated (10K)"),
        (FILE_BATCH3_10K, "Batch 3 Canonical (10K)"),
        (FILE_CONTRASTIVE_MASTER, "Master Contrastive Canonical (2,722 sents)")
    ]

    with open(OUT_COMBINED_JSONL, "w", encoding="utf-8") as out_f:
        for fpath, label in sources:
            count = 0
            if not os.path.exists(fpath):
                raise FileNotFoundError(f"Missing required input file: {fpath}")
            with open(fpath, "r", encoding="utf-8") as in_f:
                for line in in_f:
                    line = line.strip()
                    if not line:
                        continue
                    rec = json.loads(line)
                    sid = rec.get("sentence_id", "")
                    stext = rec.get("text", "").strip()

                    # Strict anti-leakage assertions
                    assert sid not in heldout_ids, f"CRITICAL: Held-out test sentence ID {sid} found in training input {fpath}!"
                    assert stext.lower() not in heldout_texts, f"CRITICAL: Held-out test sentence text '{stext}' found in training input {fpath}!"

                    if sid and sid in seen_ids:
                        continue
                    if stext and stext in seen_texts:
                        continue

                    if sid: seen_ids.add(sid)
                    if stext: seen_texts.add(stext)

                    out_f.write(line + "\n")
                    count += 1
                    total_count += 1
            print(f"  [SOURCE] Added {count:,} sentences from {label}")

    print(f"\n[DONE] Successfully merged {total_count:,} unique sentences into: {OUT_COMBINED_JSONL}")


def phase2_convert_to_zemberek():
    log_phase("PHASE 2: Converting JSONL to Zemberek Training Format")
    cmd = [
        "python3", "experiment/scripts/jsonl_to_zemberek_train.py",
        "-i", OUT_COMBINED_JSONL,
        "-o", OUT_COMBINED_TXT
    ]
    t0 = time.time()
    subprocess.run(cmd, cwd=REPO_ROOT, check=True)
    print(f"[DONE] Converted in {time.time() - t0:.1f}s -> {OUT_COMBINED_TXT}")


def phase3_train_model():
    log_phase("PHASE 3: Training FastPerceptron Model (model-distilled-33k-th10k.bin)")
    cp = get_classpath()
    cmd = [
        "java", "-cp", cp,
        "zemberek.morphology.ambiguity.dataset.TrainFastAmbiguityModel",
        "--train", OUT_COMBINED_TXT,
        "--output", NEW_MODEL_BIN,
        "--iterations", "5",
        "--pruneWeight", "0.05",
        "--exportText"
    ]
    t0 = time.time()
    subprocess.run(cmd, cwd=REPO_ROOT, check=True)
    dt = time.time() - t0
    print(f"\n[DONE] Model training finished in {dt:.1f}s ({dt/60:.2f} min) -> {NEW_MODEL_BIN}")


def phase4_evaluate_models():
    log_phase("PHASE 4: Comparative Evaluation Across Test Sets")
    cp = get_classpath()

    # 1. Evaluate on 2,500 Permanent Held-Out Golden Set
    print("\n--- 1. Evaluating on 2,500 Permanent Held-Out Golden Test Set ---")
    cmd_heldout = [
        "java", "-cp", cp,
        "zemberek.morphology.ambiguity.dataset.EvaluateAmbiguityModel",
        "-a", HELDOUT_2500_CANONICAL,
        "-m", NEW_MODEL_BIN,
        "-m2", EXISTING_33K_MODEL_BIN,
        "-o", EVAL_2500_OUTPUT,
        "-b", "-1"
    ]
    subprocess.run(cmd_heldout, cwd=REPO_ROOT, check=True)

    # 2. Evaluate on 1,000 Golden Test Set
    print("\n--- 2. Evaluating on 1,000 Golden Test Set ---")
    cmd_1000 = [
        "java", "-cp", cp,
        "zemberek.morphology.ambiguity.dataset.EvaluateAmbiguityModel",
        "-a", GOLDEN_1000_CANONICAL,
        "-m", NEW_MODEL_BIN,
        "-m2", EXISTING_33K_MODEL_BIN,
        "-o", EVAL_1000_OUTPUT,
        "-b", "-1"
    ]
    subprocess.run(cmd_1000, cwd=REPO_ROOT, check=True)


def main():
    t_start = time.time()
    print("Launching Model Creation & Benchmark Pipeline for model-distilled-33k-th10k.bin...")
    phase1_merge_datasets()
    phase2_convert_to_zemberek()
    phase3_train_model()
    phase4_evaluate_models()
    total_time = time.time() - t_start
    print("\n" + "#" * 80)
    print(f"  PIPELINE COMPLETED IN {total_time:.1f}s ({total_time/60:.2f} min)")
    print(f"  New Model: {NEW_MODEL_BIN}")
    print(f"  Eval 2500 Results: {EVAL_2500_OUTPUT}")
    print(f"  Eval 1000 Results: {EVAL_1000_OUTPUT}")
    print("#" * 80 + "\n")


if __name__ == "__main__":
    main()

