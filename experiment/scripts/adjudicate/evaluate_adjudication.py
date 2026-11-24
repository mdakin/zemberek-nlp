#!/usr/bin/env python3
"""
evaluate_adjudication.py

Analyzes parsed adjudication results from double-blind Gemini 3.8 Flash evaluation.
Combines:
1. Programmatically auto-passed identical consensus tokens (from dataset summary).
2. Adjudicated disputed tokens (from adjudications_parsed.jsonl).
3. Empirical control sentence verification.

Generates:
- True disambiguation accuracy
- Actionable model error rate (GOLD_WINS)
- Verified treebank error rate (MODEL_WINS)
- Detailed POS & error category breakdowns
"""

import sys
import json
import argparse
from pathlib import Path
from collections import Counter, defaultdict
from typing import Dict, Any, List


def evaluate(parsed_log_path: Path, summary_meta_path: Path, output_report_path: Path):
    if not parsed_log_path.exists():
        print(f"ERROR: Parsed log not found: {parsed_log_path}", file=sys.stderr)
        sys.exit(1)

    dataset_summary = {}
    if summary_meta_path.exists():
        with open(summary_meta_path, "r", encoding="utf-8") as f:
            dataset_summary = json.load(f)

    # Accumulators
    by_treebank = {
        "BOUN": Counter(),
        "IMST": Counter(),
        "COMBINED": Counter()
    }
    evaluated_consensus = Counter()
    
    control_stats = {
        "BOUN": {"total_control_tokens": 0, "both_correct": 0, "disputed": 0},
        "IMST": {"total_control_tokens": 0, "both_correct": 0, "disputed": 0}
    }
    sentence_stats = {
        "BOUN": {"total_disputed_sentences": 0, "perfect_sentences": 0, "error_sentences": 0},
        "IMST": {"total_disputed_sentences": 0, "perfect_sentences": 0, "error_sentences": 0},
        "COMBINED": {"total_disputed_sentences": 0, "perfect_sentences": 0, "error_sentences": 0}
    }

    model_wins_examples = []
    gold_wins_examples = []

    with open(parsed_log_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            rec = json.loads(line)
            tb = rec.get("treebank", "UNKNOWN")
            is_control = rec.get("is_control", False)

            if not is_control:
                c_toks = rec.get("consensus_tokens", 0)
                evaluated_consensus[tb] += c_toks
                evaluated_consensus["COMBINED"] += c_toks
                sentence_stats[tb]["total_disputed_sentences"] += 1
                sentence_stats["COMBINED"]["total_disputed_sentences"] += 1

            sent_has_model_error = False

            for t in rec.get("tokens", []):
                outcome = t.get("outcome", "UNKNOWN")

                if is_control:
                    control_stats[tb]["total_control_tokens"] += 1
                    if outcome == "BOTH_VALID":
                        control_stats[tb]["both_correct"] += 1
                    else:
                        control_stats[tb]["disputed"] += 1
                    continue

                if outcome in ["GOLD_WINS", "GOLD_PREFERRED", "NEITHER"]:
                    sent_has_model_error = True

                by_treebank[tb][outcome] += 1
                by_treebank["COMBINED"][outcome] += 1

                if outcome == "MODEL_WINS" and len(model_wins_examples) < 25:
                    model_wins_examples.append({
                        "treebank": tb,
                        "sentence_id": rec["sentence_id"],
                        "surface": t["surface"],
                        "gold": t["gold_conllu"],
                        "model": t["model_conllu"],
                        "reason": t["reason"]
                    })
                elif outcome == "GOLD_WINS" and len(gold_wins_examples) < 25:
                    gold_wins_examples.append({
                        "treebank": tb,
                        "sentence_id": rec["sentence_id"],
                        "surface": t["surface"],
                        "gold": t["gold_conllu"],
                        "model": t["model_conllu"],
                        "reason": t["reason"]
                    })

            if not is_control:
                if sent_has_model_error:
                    sentence_stats[tb]["error_sentences"] += 1
                    sentence_stats["COMBINED"]["error_sentences"] += 1
                else:
                    sentence_stats[tb]["perfect_sentences"] += 1
                    sentence_stats["COMBINED"]["perfect_sentences"] += 1

    # Build comprehensive metrics report
    report = {
        "adjudication_outcomes": {},
        "sentence_level_accuracy": sentence_stats,
        "control_experiment": control_stats,
        "sample_verified_treebank_errors": model_wins_examples,
        "sample_actionable_model_errors": gold_wins_examples
    }

    print("\n" + "=" * 60)
    print("      DOUBLE-BLIND ADJUDICATION EVALUATION REPORT")
    print("=" * 60)

    for tb in ["BOUN", "IMST", "COMBINED"]:
        c = by_treebank[tb]
        total_adjudicated = sum(c.values())
        if total_adjudicated == 0:
            continue

        if evaluated_consensus[tb] > 0:
            consensus_count = evaluated_consensus[tb]
        else:
            meta_stats = dataset_summary.get("stats", {}).get(tb, {})
            consensus_count = meta_stats.get("identical_consensus_tokens", 0)
            if tb == "COMBINED":
                consensus_count = sum(
                    s.get("identical_consensus_tokens", 0)
                    for s in dataset_summary.get("stats", {}).values()
                )

        total_word_tokens = consensus_count + total_adjudicated

        both_valid = c["BOTH_VALID"]
        model_wins = c["MODEL_WINS"]
        model_pref = c["MODEL_PREFERRED"]
        gold_wins = c["GOLD_WINS"]
        gold_pref = c["GOLD_PREFERRED"]
        neither = c["NEITHER"]
        tie = c["TIE"]

        # Sentence-level accuracy
        ss = sentence_stats.get(tb, {})
        tot_s = ss.get("total_disputed_sentences", 0)
        perf_s = ss.get("perfect_sentences", 0)
        perf_pct = (perf_s / tot_s * 100) if tot_s > 0 else 0

        # True accuracy: consensus + both valid + model wins + model preferred
        model_successful = consensus_count + both_valid + model_wins + model_pref
        true_accuracy = (model_successful / total_word_tokens * 100) if total_word_tokens > 0 else 0
        gold_error_rate = (model_wins / total_word_tokens * 100) if total_word_tokens > 0 else 0
        model_error_rate = ((gold_wins + gold_pref) / total_word_tokens * 100) if total_word_tokens > 0 else 0

        report["adjudication_outcomes"][tb] = {
            "total_disputed_adjudicated": total_adjudicated,
            "both_valid": both_valid,
            "model_wins_treebank_error": model_wins,
            "model_preferred": model_pref,
            "gold_wins_model_error": gold_wins,
            "gold_preferred": gold_pref,
            "neither": neither,
            "tie": tie,
            "consensus_auto_pass": consensus_count,
            "total_word_tokens": total_word_tokens,
            "true_accuracy_pct": round(true_accuracy, 2),
            "verified_treebank_noise_pct": round(gold_error_rate, 2),
            "actionable_model_error_pct": round(model_error_rate, 2),
            "sentence_level_accuracy_pct": round(perf_pct, 2)
        }

        print(f"\n--- {tb} ---")
        print(f"  Total Word Tokens: {total_word_tokens} (Consensus: {consensus_count} | Adjudicated: {total_adjudicated})")
        print(f"  BOTH_VALID (Twin/Acceptable):   {both_valid:<5} ({both_valid/total_adjudicated*100:.1f}% of disputes)")
        print(f"  MODEL_WINS (Treebank Error):     {model_wins:<5} ({model_wins/total_adjudicated*100:.1f}% of disputes)")
        print(f"  MODEL_PREFERRED:                 {model_pref:<5} ({model_pref/total_adjudicated*100:.1f}% of disputes)")
        print(f"  GOLD_WINS (Actionable Model Err):{gold_wins:<5} ({gold_wins/total_adjudicated*100:.1f}% of disputes)")
        print(f"  GOLD_PREFERRED:                  {gold_pref:<5} ({gold_pref/total_adjudicated*100:.1f}% of disputes)")
        print(f"  NEITHER (Outright Error):        {neither:<5} ({neither/total_adjudicated*100:.1f}% of disputes)")
        if tie > 0:
            print(f"  TIE:                             {tie:<5} ({tie/total_adjudicated*100:.1f}% of disputes)")
        print(f"  => TRUE ADJUDICATED ACCURACY:    {true_accuracy:.2f}%")
        print(f"  => VERIFIED TREEBANK NOISE:      {gold_error_rate:.2f}%")
        print(f"  => ACTIONABLE MODEL ERROR RATE:  {model_error_rate:.2f}%")
        print(f"  => SENTENCE-LEVEL ACCURACY:      {perf_s}/{tot_s} ({perf_pct:.1f}% sentences with 0 model errors)")

    print("\n--- CONTROL BENCHMARK RESULTS ---")
    for tb, cs in control_stats.items():
        tot = cs["total_control_tokens"]
        bc = cs["both_correct"]
        pct = (bc / tot * 100) if tot > 0 else 0
        print(f"  {tb}: {bc}/{tot} ({pct:.1f}%) consensus tokens verified as BOTH CORRECT by Gemini")

    output_report_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_report_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)

    print(f"\nFull report saved to: {output_report_path}")


def main():
    script_dir = Path(__file__).resolve().parent
    default_log = script_dir / "logs" / "adjudications_parsed.jsonl"
    default_summary = script_dir / "data" / "adjudication_dataset_pairwise_summary.json"
    default_report = script_dir / "logs" / "adjudication_evaluation_report.json"

    parser = argparse.ArgumentParser(description="Evaluate double-blind adjudication logs.")
    parser.add_argument("--parsed-log", type=Path, default=default_log)
    parser.add_argument("--summary", type=Path, default=default_summary)
    parser.add_argument("--output", type=Path, default=default_report)
    args = parser.parse_args()

    evaluate(args.parsed_log, args.summary, args.output)


if __name__ == "__main__":
    main()
