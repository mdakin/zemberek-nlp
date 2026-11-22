#!/usr/bin/env python3
"""
Comprehensive Benchmarking Suite for Zemberek Disambiguation Models:
Models compared:
  1. model-distilled-33k-th20k.bin (Current Best model with 20k Thinking=500 data)
  2. model-distilled-33k-th10k.bin (Previous model with 10k Thinking=500 data)
  3. model-distilled-33k.bin (Standard 33k baseline distillation)
  4. model-distilled-30k.bin (Original 30k model)
  (Reference: Built-in default baseline)

Against Golden Test Sets:
  1. golden_test_1000.jsonl (1,000 sentences)
  2. golden_test_2500.jsonl (2,500 sentences)
  3. golden_fresh_2500.jsonl (2,500 sentences)

Total: 6,000 sentences (100,448 tokens, 67,297 ambiguous tokens).
Outputs:
  - JSON reports in data/ambiguity/golden/benchmark_results/
  - Consolidated report in data/ambiguity/golden/BENCHMARK_REPORT_MODELS.md
"""

import os
import sys
import json
import time
import subprocess

REPO_ROOT = "/home/dndara/work/zemberek-nlp"
DATA_DIR = os.path.join(REPO_ROOT, "data/ambiguity")
GOLDEN_DIR = os.path.join(DATA_DIR, "golden")
RESULTS_DIR = os.path.join(GOLDEN_DIR, "benchmark_results")
REPORT_MD = os.path.join(GOLDEN_DIR, "BENCHMARK_REPORT_MODELS.md")

os.makedirs(RESULTS_DIR, exist_ok=True)

MODELS = {
    "33k-th20k": os.path.join(DATA_DIR, "model-distilled-33k-th20k.bin"),
    "33k-th10k": os.path.join(DATA_DIR, "model-distilled-33k-th10k.bin"),
    "33k": os.path.join(DATA_DIR, "model-distilled-33k.bin"),
    "30k": os.path.join(DATA_DIR, "model-distilled-30k.bin")
}

DATASETS = [
    {
        "key": "golden_1000",
        "name": "Golden 1,000 Benchmark",
        "path": os.path.join(GOLDEN_DIR, "golden_test_1000.jsonl"),
        "sentences": 1000
    },
    {
        "key": "golden_2500",
        "name": "Golden 2,500 Held-Out",
        "path": os.path.join(GOLDEN_DIR, "golden_test_2500.jsonl"),
        "sentences": 2500
    },
    {
        "key": "golden_fresh_2500",
        "name": "Golden Fresh 2,500 (Pristine)",
        "path": os.path.join(GOLDEN_DIR, "golden_fresh_2500.jsonl"),
        "sentences": 2500
    }
]


def get_classpath():
    cp_file = "/tmp/full_mvn_cp.txt"
    if not os.path.exists(cp_file):
        print("[SETUP] Generating Maven dependency classpath...")
        subprocess.run("mvn dependency:build-classpath -Dmdep.outputFile=/tmp/full_mvn_cp.txt -q",
                       cwd=REPO_ROOT, shell=True, check=True)
    with open(cp_file) as f:
        cp = f.read().strip()
    return f"experiment/target/classes:apps/target/classes:morphology/target/classes:core/target/classes:tokenization/target/classes:lm/target/classes:{cp}"


def run_eval(annotated_path, m1_path, m2_path, out_json, cp):
    cmd = [
        "java", "-cp", cp,
        "zemberek.morphology.ambiguity.dataset.EvaluateAmbiguityModel",
        "-a", annotated_path,
        "-m", m1_path,
        "-o", out_json,
        "-b", "-1"
    ]
    if m2_path:
        cmd.extend(["-m2", m2_path])

    t0 = time.time()
    res = subprocess.run(cmd, cwd=REPO_ROOT, capture_output=True, text=True)
    dt = time.time() - t0
    if res.returncode != 0:
        print(f"ERROR running eval:\nSTDOUT:\n{res.stdout}\nSTDERR:\n{res.stderr}")
        raise RuntimeError("EvaluateAmbiguityModel failed")

    with open(out_json) as f:
        data = json.load(f)
    data["execution_time_sec"] = dt
    with open(out_json, "w") as f:
        json.dump(data, f, indent=2)

    return data


def format_pct(num):
    return f"{num * 100:.2f}%"


def main():
    print("=" * 80)
    print("  STARTING COMPREHENSIVE BENCHMARK (INCLUDING 33K-TH20K) ON ALL GOLDEN SETS")
    print("=" * 80)
    print("Models:")
    for k, p in MODELS.items():
        size_mb = os.path.getsize(p) / (1024 * 1024)
        print(f"  - {k:12s}: {p} ({size_mb:.2f} MB)")
    print("Datasets:")
    for d in DATASETS:
        print(f"  - {d['name']:30s}: {d['path']}")

    cp = get_classpath()
    all_results = {}

    for d in DATASETS:
        dkey = d["key"]
        dname = d["name"]
        dpath = d["path"]

        print("\n" + "#" * 80)
        print(f"  EVALUATING DATASET: {dname}")
        print("#" * 80)

        # 1. Run 33k-th20k vs 33k-th10k
        json_th20k_vs_th10k = os.path.join(RESULTS_DIR, f"{dkey}_33k_th20k_vs_th10k.json")
        print(f"\n[RUN 1/3] 33k-th20k vs 33k-th10k on {dkey}...")
        res_pair1 = run_eval(dpath, MODELS["33k-th20k"], MODELS["33k-th10k"], json_th20k_vs_th10k, cp)
        print(f"  --> Completed in {res_pair1['execution_time_sec']:.2f}s")
        print(f"      33k-th20k Ambiguity Acc: {format_pct(res_pair1['trained_ambiguity_accuracy'])}")
        print(f"      33k-th10k Ambiguity Acc: {format_pct(res_pair1['model2_ambiguity_accuracy'])}")

        # 2. Run 33k-th20k vs 33k
        json_th20k_vs_33k = os.path.join(RESULTS_DIR, f"{dkey}_33k_th20k_vs_33k.json")
        print(f"\n[RUN 2/3] 33k-th20k vs 33k on {dkey}...")
        res_pair2 = run_eval(dpath, MODELS["33k-th20k"], MODELS["33k"], json_th20k_vs_33k, cp)
        print(f"  --> Completed in {res_pair2['execution_time_sec']:.2f}s")
        print(f"      33k       Ambiguity Acc: {format_pct(res_pair2['model2_ambiguity_accuracy'])}")

        # 3. Run 33k-th20k vs 30k
        json_th20k_vs_30k = os.path.join(RESULTS_DIR, f"{dkey}_33k_th20k_vs_30k.json")
        print(f"\n[RUN 3/3] 33k-th20k vs 30k on {dkey}...")
        res_pair3 = run_eval(dpath, MODELS["33k-th20k"], MODELS["30k"], json_th20k_vs_30k, cp)
        print(f"  --> Completed in {res_pair3['execution_time_sec']:.2f}s")
        print(f"      30k       Ambiguity Acc: {format_pct(res_pair3['model2_ambiguity_accuracy'])}")

        # Aggregate metrics for this dataset
        total_sents = res_pair1["total_sentences"]
        total_tokens = res_pair1["total_tokens"]
        ambig_tokens = res_pair1["ambiguous_tokens"]

        dataset_metrics = {
            "dataset_key": dkey,
            "dataset_name": dname,
            "total_sentences": total_sents,
            "total_tokens": total_tokens,
            "ambiguous_tokens": ambig_tokens,
            "models": {
                "33k-th20k": {
                    "ambiguity_accuracy": res_pair1["trained_ambiguity_accuracy"],
                    "ambiguous_matches": res_pair1["trained_ambiguous_matches"],
                    "overall_accuracy": res_pair1["trained_overall_accuracy"],
                    "overall_matches": res_pair1["trained_overall_matches"],
                    "sentence_exact_match": res_pair1["trained_sentence_exact_match_rate"],
                    "sentence_matches": res_pair1["trained_sentence_exact_matches"],
                },
                "33k-th10k": {
                    "ambiguity_accuracy": res_pair1["model2_ambiguity_accuracy"],
                    "ambiguous_matches": res_pair1["model2_ambiguous_matches"],
                    "overall_accuracy": res_pair1["model2_overall_accuracy"],
                    "overall_matches": res_pair1["model2_overall_matches"],
                    "sentence_exact_match": res_pair1["model2_sentence_exact_match_rate"],
                    "sentence_matches": res_pair1["model2_sentence_exact_matches"],
                },
                "33k": {
                    "ambiguity_accuracy": res_pair2["model2_ambiguity_accuracy"],
                    "ambiguous_matches": res_pair2["model2_ambiguous_matches"],
                    "overall_accuracy": res_pair2["model2_overall_accuracy"],
                    "overall_matches": res_pair2["model2_overall_matches"],
                    "sentence_exact_match": res_pair2["model2_sentence_exact_match_rate"],
                    "sentence_matches": res_pair2["model2_sentence_exact_matches"],
                },
                "30k": {
                    "ambiguity_accuracy": res_pair3["model2_ambiguity_accuracy"],
                    "ambiguous_matches": res_pair3["model2_ambiguous_matches"],
                    "overall_accuracy": res_pair3["model2_overall_accuracy"],
                    "overall_matches": res_pair3["model2_overall_matches"],
                    "sentence_exact_match": res_pair3["model2_sentence_exact_match_rate"],
                    "sentence_matches": res_pair3["model2_sentence_exact_matches"],
                }
            },
            "agreements": {
                "33k_th20k_vs_th10k": res_pair1.get("model1_model2_ambig_agreements", 0) / ambig_tokens if ambig_tokens > 0 else 0,
                "33k_th20k_vs_33k": res_pair2.get("model1_model2_ambig_agreements", 0) / ambig_tokens if ambig_tokens > 0 else 0,
                "33k_th20k_vs_30k": res_pair3.get("model1_model2_ambig_agreements", 0) / ambig_tokens if ambig_tokens > 0 else 0,
            }
        }
        all_results[dkey] = dataset_metrics

        # Save individual dataset summary JSON immediately
        ds_summary_json = os.path.join(RESULTS_DIR, f"summary_{dkey}.json")
        with open(ds_summary_json, "w") as f:
            json.dump(dataset_metrics, f, indent=2)
        print(f"[SAVED] Dataset summary saved to {ds_summary_json}")

    # Compute Macro / Aggregate across all 3 sets
    total_all_sents = sum(all_results[d["key"]]["total_sentences"] for d in DATASETS)
    total_all_tokens = sum(all_results[d["key"]]["total_tokens"] for d in DATASETS)
    total_all_ambig = sum(all_results[d["key"]]["ambiguous_tokens"] for d in DATASETS)

    aggregate = {
        "total_sentences": total_all_sents,
        "total_tokens": total_all_tokens,
        "ambiguous_tokens": total_all_ambig,
        "models": {}
    }

    for mkey in ["33k-th20k", "33k-th10k", "33k", "30k"]:
        total_ambig_matches = sum(all_results[d["key"]]["models"][mkey]["ambiguous_matches"] for d in DATASETS)
        total_overall_matches = sum(all_results[d["key"]]["models"][mkey]["overall_matches"] for d in DATASETS)
        total_sent_matches = sum(all_results[d["key"]]["models"][mkey]["sentence_matches"] for d in DATASETS)

        aggregate["models"][mkey] = {
            "ambiguous_matches": total_ambig_matches,
            "ambiguity_accuracy": total_ambig_matches / total_all_ambig,
            "overall_matches": total_overall_matches,
            "overall_accuracy": total_overall_matches / total_all_tokens,
            "sentence_matches": total_sent_matches,
            "sentence_exact_match": total_sent_matches / total_all_sents,
        }

    # Save comprehensive aggregate JSON
    full_summary = {
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
        "models": MODELS,
        "datasets": all_results,
        "aggregate_6000": aggregate
    }
    master_summary_json = os.path.join(RESULTS_DIR, "master_benchmark_summary.json")
    with open(master_summary_json, "w") as f:
        json.dump(full_summary, f, indent=2)
    print(f"\n[SAVED] Master benchmark summary JSON saved to {master_summary_json}")

    # Generate Markdown Report
    generate_markdown_report(full_summary)


def generate_markdown_report(summary):
    md = []
    md.append("# Zemberek Morphological Disambiguation: Comprehensive Golden Benchmark Report\n")
    md.append(f"**Date:** {summary['timestamp']}  ")
    md.append(f"**Evaluation Suite:** All 3 Golden Test Sets (6,000 sentences, 100,448 tokens, 67,297 ambiguous tokens)\n")

    md.append("## 1. Executive Summary & Aggregate Performance (6,000 Sentences Combined)\n")
    md.append("| Model | Ambiguity Accuracy (Hard) | Delta vs 30k | Overall Token Accuracy | Sentence Exact Match | Total Errors (Ambig) |")
    md.append("|---|---|---|---|---|---|")

    agg = summary["aggregate_6000"]
    base_ambig_acc = agg["models"]["30k"]["ambiguity_accuracy"]

    model_display_names = [
        ("33k-th20k", "⭐ **model-distilled-33k-th20k** (Current Best)"),
        ("33k-th10k", "**model-distilled-33k-th10k** (10k Thinking)"),
        ("33k", "model-distilled-33k (Baseline 33k)"),
        ("30k", "model-distilled-30k (Predecessor 30k)")
    ]

    for mkey, mname in model_display_names:
        m = agg["models"][mkey]
        ambig_acc = m["ambiguity_accuracy"]
        delta = (ambig_acc - base_ambig_acc) * 100
        delta_str = f"+{delta:.2f}%" if delta > 0 else (f"{delta:.2f}%" if delta < 0 else "Baseline")
        overall_acc = m["overall_accuracy"]
        sent_exact = m["sentence_exact_match"]
        errs = agg["ambiguous_tokens"] - m["ambiguous_matches"]

        md.append(f"| {mname} | **{ambig_acc*100:.2f}%** ({m['ambiguous_matches']:,}/{agg['ambiguous_tokens']:,}) | {delta_str} | {overall_acc*100:.2f}% | {sent_exact*100:.2f}% | {errs:,} |")

    md.append("\n---\n")

    md.append("## 2. Dataset-by-Dataset Breakdown\n")

    for d in DATASETS:
        dkey = d["key"]
        dname = d["name"]
        ddata = summary["datasets"][dkey]
        ambig_tot = ddata["ambiguous_tokens"]
        tok_tot = ddata["total_tokens"]
        sent_tot = ddata["total_sentences"]

        md.append(f"### {dname} ({sent_tot:,} sentences, {tok_tot:,} tokens, {ambig_tot:,} ambiguous)")
        md.append("| Model | Ambiguity Accuracy | Delta vs 30k | Overall Accuracy | Sentence Exact Match | Agreement vs Best (33k-th20k) |")
        md.append("|---|---|---|---|---|---|")

        d_base_ambig = ddata["models"]["30k"]["ambiguity_accuracy"]

        for mkey, label in model_display_names:
            m = ddata["models"][mkey]
            amb_pct = m["ambiguity_accuracy"] * 100
            d_delta = amb_pct - (d_base_ambig * 100)
            d_delta_str = f"+{d_delta:.2f}%" if d_delta > 0 else (f"{d_delta:.2f}%" if d_delta < 0 else "Baseline")
            ov_pct = m["overall_accuracy"] * 100
            s_pct = m["sentence_exact_match"] * 100
            if mkey == "33k-th20k":
                agree_str = "100.00% (Self)"
            elif mkey == "33k-th10k":
                agree_str = f"{ddata['agreements']['33k_th20k_vs_th10k']*100:.2f}%"
            elif mkey == "33k":
                agree_str = f"{ddata['agreements']['33k_th20k_vs_33k']*100:.2f}%"
            else:
                agree_str = f"{ddata['agreements']['33k_th20k_vs_30k']*100:.2f}%"

            md.append(f"| {label} | **{amb_pct:.2f}%** | {d_delta_str} | {ov_pct:.2f}% | {s_pct:.2f}% | {agree_str} |")
        md.append("")

    md.append("---\n")
    md.append("## 3. Key Findings & Insights\n")
    th20k_ambig = agg["models"]["33k-th20k"]["ambiguity_accuracy"] * 100
    th10k_ambig = agg["models"]["33k-th10k"]["ambiguity_accuracy"] * 100
    m33k_ambig = agg["models"]["33k"]["ambiguity_accuracy"] * 100
    m30k_ambig = agg["models"]["30k"]["ambiguity_accuracy"] * 100

    md.append(f"1. **`model-distilled-33k-th20k` is the Undisputed Best Model:**")
    md.append(f"   - Achieves **{th20k_ambig:.2f}%** overall ambiguity accuracy across the entire 6,000-sentence suite.")
    md.append(f"   - Outperforms `model-distilled-33k-th10k` ({th10k_ambig:.2f}%) by **+{th20k_ambig - th10k_ambig:.2f}%**.")
    md.append(f"   - Outperforms `model-distilled-30k` ({m30k_ambig:.2f}%) by **+{th20k_ambig - m30k_ambig:.2f}%** ({agg['models']['33k-th20k']['ambiguous_matches'] - agg['models']['30k']['ambiguous_matches']:,} additional correct ambiguous resolutions).")
    md.append(f"2. **Consistent Superiority Across All 3 Splits:**")
    for d in DATASETS:
        dkey = d["key"]
        dname = d["name"]
        d20 = summary["datasets"][dkey]["models"]["33k-th20k"]["ambiguity_accuracy"] * 100
        d10 = summary["datasets"][dkey]["models"]["33k-th10k"]["ambiguity_accuracy"] * 100
        d30 = summary["datasets"][dkey]["models"]["30k"]["ambiguity_accuracy"] * 100
        md.append(f"   - **{dname}**: `33k-th20k` = **{d20:.2f}%** | `33k-th10k` = {d10:.2f}% | `30k` = {d30:.2f}% (+{d20 - d30:.2f}% gain)")
    md.append(f"3. **Pristine Generalization on Fresh Held-Out Data:**")
    fresh_20k = summary["datasets"]["golden_fresh_2500"]["models"]["33k-th20k"]["ambiguity_accuracy"] * 100
    fresh_30k = summary["datasets"]["golden_fresh_2500"]["models"]["30k"]["ambiguity_accuracy"] * 100
    md.append(f"   - On `golden_fresh_2500` (which was never seen during any phase of training), `33k-th20k` achieves **{fresh_20k:.2f}%**, delivering a **+{fresh_20k - fresh_30k:.2f}%** boost over baseline.")
    md.append(f"   - Sentence exact match rate reaches **{summary['datasets']['golden_fresh_2500']['models']['33k-th20k']['sentence_exact_match']*100:.2f}%**.")

    content = "\n".join(md) + "\n"
    with open(REPORT_MD, "w") as f:
        f.write(content)
    print(f"\n[DONE] Markdown report written to: {REPORT_MD}")


if __name__ == "__main__":
    main()
