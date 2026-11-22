#!/usr/bin/env python3
"""
Master Pipeline for Batch 2 (10K-20K) Thinking-500 Re-Adjudication & Model Retraining:
1. Re-annotates Batch 2 (10,000 sentences) via Gemini 3.8 Flash Batch API (ThinkingBudget=500 + Zemberek native keys).
2. Merges Thinking-500 Batch 1 (10K) + Thinking-500 Batch 2 (10K) + Batch 3 (10K) + Master Contrastive (2.7K)
   with strict anti-leakage checks into train_combined_33k_th20k.jsonl.
3. Converts corpus to Zemberek format and trains model-distilled-33k-th20k.bin.
4. Runs multi-way benchmark evaluations against golden_test_1000, golden_test_2500, and golden_fresh_2500.
"""

import os
import sys
import json
import time
import subprocess

REPO_ROOT = "/home/dndara/work/zemberek-nlp"
DISTILLED_DIR = "/home/dndara/data/turkish/distilled"
DATA_DIR = os.path.join(REPO_ROOT, "data/ambiguity")

# Batch 2 inputs & outputs
BATCH2_CANDIDATES = os.path.join(DISTILLED_DIR, "batch2_10k_candidates.jsonl")
BATCH2_THINKING_OUT = os.path.join(DATA_DIR, "distilled_batch2_10k_thinking500_annotated.jsonl")
BATCH2_REGISTRY = os.path.join(DATA_DIR, "batch_registry_batch2_10k_thinking500.json")
BATCH2_SUMMARY = os.path.join(DATA_DIR, "distilled_batch2_10k_thinking500_summary.json")

# Training sources for 33k th20k
FILE_THINKING_BATCH1 = os.path.join(DATA_DIR, "distilled_10k_thinking500_annotated.jsonl")
FILE_BATCH3_10K = os.path.join(DISTILLED_DIR, "distilled_batch3_10k_canonical.jsonl")
FILE_CONTRASTIVE_MASTER = os.path.join(DATA_DIR, "contrastive_master_canonical.jsonl")

# Outputs
OUT_COMBINED_JSONL = os.path.join(DATA_DIR, "train_combined_33k_th20k.jsonl")
OUT_COMBINED_TXT = os.path.join(DATA_DIR, "train_combined_33k_th20k.zemberek.txt")
NEW_MODEL_BIN = os.path.join(DATA_DIR, "model-distilled-33k-th20k.bin")

# Reference models & test sets
MODEL_TH10K_BIN = os.path.join(DATA_DIR, "model-distilled-33k-th10k.bin")
MODEL_33K_ZERO_BIN = os.path.join(DATA_DIR, "model-distilled-33k.bin")

GOLDEN_1000_TEST = os.path.join(DATA_DIR, "golden_test_1000_thinking500.jsonl")
GOLDEN_2500_TEST = os.path.join(DATA_DIR, "golden_test_2500_thinking500.jsonl")
GOLDEN_FRESH_2500_TEST = os.path.join(DATA_DIR, "golden_fresh_2500_canonical.jsonl")

EVAL_1000_OUT = os.path.join(DATA_DIR, "benchmark_golden1000_th20k_vs_th10k.json")
EVAL_2500_OUT = os.path.join(DATA_DIR, "benchmark_golden2500_th20k_vs_th10k.json")
EVAL_FRESH2500_OUT = os.path.join(DATA_DIR, "benchmark_goldenfresh2500_th20k_vs_th10k.json")

MANIFEST_PATH = os.path.join(DISTILLED_DIR, "PERMANENT_HELDOUT_TEST_SET_MANIFEST.json")


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


def phase1_adjudicate_batch2():
    log_phase("PHASE 1: Adjudicating Batch 2 (10,000 sentences) with Gemini 3.8 Flash (Thinking=500)")
    cmd = [
        "python3", "experiment/scripts/run_batch_evaluation_10k.py",
        "--input", BATCH2_CANDIDATES,
        "--output", BATCH2_THINKING_OUT,
        "--registry", BATCH2_REGISTRY,
        "--summary", BATCH2_SUMMARY,
        "--batch-size", "500",
        "--thinking-budget", "500",
        "--poll-interval", "15"
    ]
    t0 = time.time()
    subprocess.run(cmd, cwd=REPO_ROOT, check=True)
    dt = time.time() - t0
    print(f"\n[DONE] Batch 2 Adjudication completed in {dt:.1f}s ({dt/60:.2f} min).")


def phase2_merge_and_convert():
    log_phase("PHASE 2: Merging Datasets with Anti-Leakage Guard into train_combined_33k_th20k.jsonl")

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

    # Also include golden_test_1000 candidates
    g1000_cand = os.path.join(DISTILLED_DIR, "golden_test_1000_candidates.jsonl")
    if os.path.exists(g1000_cand):
        with open(g1000_cand, "r", encoding="utf-8") as f:
            for line in f:
                d = json.loads(line)
                heldout_ids.add(d["sentence_id"])
                heldout_texts.add(d["text"].strip().lower())

    print(f"  [GUARD] Enforcing zero-leakage guard against {len(heldout_ids)} permanent held-out test sentences.")

    seen_ids = set()
    seen_texts = set()
    total_count = 0

    sources = [
        (FILE_THINKING_BATCH1, "Thinking-500 Batch 1 (10K)"),
        (BATCH2_THINKING_OUT, "Thinking-500 Batch 2 (10K)"),
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

    # Convert to Zemberek training format
    print("\n  Converting JSONL to Zemberek training format...")
    cmd_convert = [
        "python3", "experiment/scripts/jsonl_to_zemberek_train.py",
        "-i", OUT_COMBINED_JSONL,
        "-o", OUT_COMBINED_TXT
    ]
    t0 = time.time()
    subprocess.run(cmd_convert, cwd=REPO_ROOT, check=True)
    print(f"[DONE] Converted in {time.time() - t0:.1f}s -> {OUT_COMBINED_TXT}")


def phase3_train_model():
    log_phase("PHASE 3: Training FastPerceptron Model (model-distilled-33k-th20k.bin)")
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


def phase4_evaluate_all_golden():
    log_phase("PHASE 4: Comparative Evaluation Across All Three Golden Test Sets")
    cp = get_classpath()

    eval_tasks = [
        ("Golden 1000 (Adjudicated)", GOLDEN_1000_TEST, EVAL_1000_OUT),
        ("Golden 2500 (Adjudicated)", GOLDEN_2500_TEST, EVAL_2500_OUT),
        ("Golden Fresh 2500 (Held-Out)", GOLDEN_FRESH_2500_TEST, EVAL_FRESH2500_OUT),
    ]

    for label, test_path, out_json in eval_tasks:
        print(f"\n--- Evaluating on: {label} ---")
        cmd = [
            "java", "-cp", cp,
            "zemberek.morphology.ambiguity.dataset.EvaluateAmbiguityModel",
            "-a", test_path,
            "-m", NEW_MODEL_BIN,
            "-m2", MODEL_TH10K_BIN,
            "-o", out_json,
            "-b", "-1"
        ]
        subprocess.run(cmd, cwd=REPO_ROOT, check=True)

    print("\n" + "#" * 80)
    print("  ALL BENCHMARKS COMPLETE! PRINTING EXECUTIVE COMPARISON TABLE:")
    print("#" * 80 + "\n")

    for label, test_path, out_json in eval_tasks:
        with open(out_json, "r") as f:
            d = json.load(f)
        print(f"Dataset: {label}")
        print(f"  model-distilled-33k-th20k.bin (New 20K Thinking):")
        print(f"    Ambiguity Acc: {d['trained_ambiguity_accuracy']*100:.2f}% ({d['trained_ambiguous_matches']}/{d['ambiguous_tokens']})")
        print(f"    Overall Acc:   {d['trained_overall_accuracy']*100:.2f}% ({d['trained_overall_matches']}/{d['total_tokens']})")
        print(f"    Exact Match:   {d['trained_sentence_exact_match_rate']*100:.2f}% ({d['trained_sentence_exact_matches']}/{d['total_sentences']})")
        print(f"  model-distilled-33k-th10k.bin (Previous 10K Thinking):")
        print(f"    Ambiguity Acc: {d['model2_ambiguity_accuracy']*100:.2f}% ({d['model2_ambiguous_matches']}/{d['ambiguous_tokens']})")
        print(f"    Overall Acc:   {d['model2_overall_accuracy']*100:.2f}% ({d['model2_overall_matches']}/{d['total_tokens']})")
        print(f"    Exact Match:   {d['model2_sentence_exact_match_rate']*100:.2f}% ({d['model2_sentence_exact_matches']}/{d['total_sentences']})")
        d_amb = (d['trained_ambiguity_accuracy'] - d['model2_ambiguity_accuracy']) * 100
        d_em = (d['trained_sentence_exact_match_rate'] - d['model2_sentence_exact_match_rate']) * 100
        print(f"  Delta: Ambiguity Acc: {d_amb:+.2f}%, Exact Match: {d_em:+.2f}%\n")


def main():
    t_start = time.time()
    print("Starting Batch 2 Adjudication & th20k Model Retraining Pipeline...")

    phase1_adjudicate_batch2()
    phase2_merge_and_convert()
    phase3_train_model()
    phase4_evaluate_all_golden()

    total_time = time.time() - t_start
    print("\n" + "#" * 80)
    print(f"  PIPELINE FULLY COMPLETED IN {total_time/60:.2f} min")
    print("#" * 80 + "\n")


if __name__ == "__main__":
    main()

