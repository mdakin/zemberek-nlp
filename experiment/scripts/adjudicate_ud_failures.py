#!/usr/bin/env python3
"""
adjudicate_ud_failures.py

Extracts sentences from evaluation reports where Zemberek disagreed with UD ground truth,
submits them concurrently to Gemini to adjudicate who is actually right,
requests ultra-concise explanations (max 10-15 words),
and aggregates the results into a detailed linguistic report.

Usage:
  python3 experiment/scripts/adjudicate_ud_failures.py \
    -e evaluation_head_boun_canonical.json \
    -s data/ambiguity/sources/UD_Turkish-BOUN/boun_test_canonical.jsonl \
    -o data/ambiguity/boun_test_adjudication_results.jsonl \
    -r data/ambiguity/boun_test_adjudication_summary.json \
    -c 16
"""

import os
import sys
import json
import time
import argparse
import threading
import requests
import concurrent.futures
from typing import Dict, Any, List, Optional, Set
from collections import Counter


SYSTEM_INSTRUCTION = (
    "You are an expert Turkish computational linguist evaluating morphological disambiguation "
    "disagreements between Universal Dependencies (UD) and Zemberek NLP.\n"
    "In the given Turkish sentence, evaluate each disputed word where UD and Zemberek disagreed.\n"
    "For each token index, decide:\n"
    "1. 'verdict':\n"
    "   - 'ZEMBEREK': Zemberek's analysis is correct (UD is wrong / bad alignment / convention flaw).\n"
    "   - 'UD': UD's analysis is correct (Zemberek made an error).\n"
    "   - 'BOTH': Both are linguistically valid alternative analyses (e.g. lexicalized vs derived, "
    "lexicon twin, conjunction vs postposition, verbal noun vs noun).\n"
    "   - 'NEITHER': Neither is correct.\n"
    "2. 'reason': A VERY SHORT explanation (under 12 words) why.\n"
    "Return JSON mapping token index string to judgment object: "
    "{\"<index>\": {\"verdict\": \"ZEMBEREK\"|\"UD\"|\"BOTH\"|\"NEITHER\", \"reason\": \"<very short reason>\"}}"
)


def load_env(filepath: str = ".env") -> None:
    for path in [filepath, ".env.local", os.path.expanduser("~/.env")]:
        if os.path.exists(path):
            with open(path, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if line and not line.startswith("#") and "=" in line:
                        k, v = line.split("=", 1)
                        if k.strip() not in os.environ:
                            os.environ[k.strip()] = v.strip().strip("'").strip('"')


def format_prompt(sentence_text: str, disputed_tokens: List[Dict[str, Any]]) -> str:
    lines = [f'Sentence: "{sentence_text}"', "Disputed words:"]
    for dt in disputed_tokens:
        idx = dt["index"]
        surf = dt["surface"]
        ud_key = dt["ud_key"]
        zem_key = dt["zemberek_key"]
        cands_str = " | ".join(f"[{c['id']}] {c.get('zemberek_key', '')}" for c in dt.get("candidates", []))
        lines.append(f'- Token #{idx} "{surf}":')
        lines.append(f'  Option A (UD): {ud_key}')
        lines.append(f'  Option B (Zemberek): {zem_key}')
        lines.append(f'  All Candidates: {cands_str}')
    lines.append('\nReturn JSON: {"<index>": {"verdict": "ZEMBEREK"|"UD"|"BOTH"|"NEITHER", "reason": "<max 12 words>"}}')
    return "\n".join(lines)


def call_gemini(
    url: str,
    prompt: str,
    max_retries: int = 6
) -> Optional[Dict[str, Dict[str, str]]]:
    payload = {
        "system_instruction": {"parts": [{"text": SYSTEM_INSTRUCTION}]},
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {
            "temperature": 0.0,
            "responseMimeType": "application/json"
        }
    }

    for attempt in range(max_retries):
        try:
            resp = requests.post(url, json=payload, timeout=30)
            if resp.status_code == 200:
                data = resp.json()
                cand_part = data.get("candidates", [{}])[0].get("content", {}).get("parts", [{}])[0]
                text = cand_part.get("text", "").strip()
                if text.startswith("```json"):
                    text = text[7:]
                if text.startswith("```"):
                    text = text[3:]
                if text.endswith("```"):
                    text = text[:-3]
                parsed = json.loads(text.strip())
                res = {}
                for k, v in parsed.items():
                    if isinstance(v, dict):
                        verdict = str(v.get("verdict", "NEITHER")).upper()
                        if verdict not in ["ZEMBEREK", "UD", "BOTH", "NEITHER"]:
                            verdict = "NEITHER"
                        reason = str(v.get("reason", "")).strip()
                        res[str(k)] = {"verdict": verdict, "reason": reason}
                    elif isinstance(v, str):
                        verdict = v.upper()
                        if verdict not in ["ZEMBEREK", "UD", "BOTH", "NEITHER"]:
                            verdict = "NEITHER"
                        res[str(k)] = {"verdict": verdict, "reason": ""}
                return res
            elif resp.status_code == 429:
                time.sleep((2 ** attempt) * 1.5 + 1.0)
            else:
                time.sleep(1.0 * (attempt + 1))
        except Exception:
            time.sleep(1.0 * (attempt + 1))
    return None


def main():
    parser = argparse.ArgumentParser(description="Adjudicate Zemberek vs UD disagreements with LLM")
    parser.add_argument("-e", "--eval-json", required=True, help="Evaluation JSON file")
    parser.add_argument("-s", "--source-jsonl", required=True, help="Source canonical JSONL file")
    parser.add_argument("-o", "--output-jsonl", required=True, help="Output JSONL for adjudicated sentences")
    parser.add_argument("-r", "--report-json", required=True, help="Output JSON for summary report")
    parser.add_argument("-c", "--concurrency", type=int, default=16, help="Concurrency limit")
    parser.add_argument("-m", "--model", default="gemini-3.8-flash", help="Gemini model name")
    args = parser.parse_args()

    load_env()
    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        print("ERROR: GEMINI_API_KEY environment variable is missing!", file=sys.stderr)
        sys.exit(1)

    url = f"https://generativelanguage.googleapis.com/v1beta/models/{args.model}:generateContent?key={api_key}"

    # 1. Load evaluation data
    print(f"Loading evaluation report from: {args.eval_json}")
    with open(args.eval_json, "r", encoding="utf-8") as f:
        eval_data = json.load(f)

    # 2. Index mismatches by sentence_id
    mismatches_by_sent: Dict[str, Dict[int, Dict[str, Any]]] = {}
    for s in eval_data.get("sentences", []):
        sid = s["sentence_id"]
        token_map = {}
        for t in s.get("tokens", []):
            if t.get("is_ambiguous") and not t.get("trained_matches_gemini"):
                token_map[t["index"]] = {
                    "ud_key": t.get("gemini_selected_key", ""),
                    "zemberek_key": t.get("trained_selected_key", "")
                }
        if token_map:
            mismatches_by_sent[sid] = token_map

    total_mismatched_sents = len(mismatches_by_sent)
    total_mismatched_tokens = sum(len(v) for v in mismatches_by_sent.values())
    print(f"Found {total_mismatched_sents} sentences with {total_mismatched_tokens} disputed tokens.")

    # 3. Read source jsonl to get candidates and prepare adjudication pool
    print(f"Reading source candidate structures from: {args.source_jsonl}")
    adjudication_pool: List[Dict[str, Any]] = []
    with open(args.source_jsonl, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            s_rec = json.loads(line)
            sid = s_rec["sentence_id"]
            if sid in mismatches_by_sent:
                disputed_map = mismatches_by_sent[sid]
                disputed_tokens = []
                for t in s_rec.get("tokens", []):
                    idx = t["index"]
                    if idx in disputed_map:
                        disputed_tokens.append({
                            "index": idx,
                            "surface": t["surface"],
                            "ud_key": disputed_map[idx]["ud_key"],
                            "zemberek_key": disputed_map[idx]["zemberek_key"],
                            "candidates": t.get("candidates", [])
                        })
                adjudication_pool.append({
                    "sentence_id": sid,
                    "text": s_rec["text"],
                    "disputed_tokens": disputed_tokens
                })

    print(f"Prepared {len(adjudication_pool)} sentences for adjudication.")

    # 4. Check existing results for resume capability
    done_sids: Set[str] = set()
    if os.path.exists(args.output_jsonl):
        with open(args.output_jsonl, "r", encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    try:
                        d = json.loads(line)
                        done_sids.add(d["sentence_id"])
                    except Exception:
                        pass
        print(f"Resuming: found {len(done_sids)} already adjudicated sentences.")

    pending_pool = [rec for rec in adjudication_pool if rec["sentence_id"] not in done_sids]
    print(f"Pending to process: {len(pending_pool)} sentences.")

    # 5. Concurrent execution
    out_dir = os.path.dirname(args.output_jsonl)
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)

    out_lock = threading.Lock()
    completed_count = len(done_sids)
    start_time = time.time()

    def process_item(rec: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        nonlocal completed_count
        prompt = format_prompt(rec["text"], rec["disputed_tokens"])
        llm_response = call_gemini(url, prompt)
        if llm_response is None:
            return None

        # Attach verdicts and reasons
        adjudicated_tokens = []
        for dt in rec["disputed_tokens"]:
            idx_str = str(dt["index"])
            judgment = llm_response.get(idx_str, {"verdict": "NEITHER", "reason": "No response from LLM"})
            adjudicated_tokens.append({
                "index": dt["index"],
                "surface": dt["surface"],
                "ud_key": dt["ud_key"],
                "zemberek_key": dt["zemberek_key"],
                "verdict": judgment.get("verdict", "NEITHER"),
                "reason": judgment.get("reason", "")
            })

        out_rec = {
            "sentence_id": rec["sentence_id"],
            "text": rec["text"],
            "adjudicated_tokens": adjudicated_tokens
        }

        with out_lock:
            with open(args.output_jsonl, "a", encoding="utf-8") as out_f:
                out_f.write(json.dumps(out_rec, ensure_ascii=False) + "\n")
            completed_count += 1
            if completed_count % 25 == 0 or completed_count == total_mismatched_sents:
                elapsed = time.time() - start_time
                rate = completed_count / max(1.0, elapsed)
                print(f"Progress: {completed_count}/{total_mismatched_sents} ({completed_count/total_mismatched_sents*100:.1f}%) | Speed: {rate:.1f} sent/s")

        return out_rec

    if pending_pool:
        print(f"Starting concurrent adjudication with {args.concurrency} workers...")
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as executor:
            futures = [executor.submit(process_item, rec) for rec in pending_pool]
            concurrent.futures.wait(futures)

    # 6. Aggregate report
    print("\nAdjudication complete. Aggregating results...")
    verdict_counts = Counter()
    zemberek_reasons = []
    ud_reasons = []
    both_reasons = []
    token_details = []

    with open(args.output_jsonl, "r", encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            rec = json.loads(line)
            for t in rec.get("adjudicated_tokens", []):
                v = t.get("verdict", "NEITHER")
                verdict_counts[v] += 1
                reason = t.get("reason", "")
                surf = t.get("surface", "")
                token_details.append(t)
                if v == "ZEMBEREK":
                    zemberek_reasons.append((surf.lower(), reason))
                elif v == "UD":
                    ud_reasons.append((surf.lower(), reason))
                elif v == "BOTH":
                    both_reasons.append((surf.lower(), reason))

    total_disputed = sum(verdict_counts.values())
    total_amb = eval_data.get("ambiguous_tokens", total_disputed)
    matched_original = eval_data.get("trained_ambiguous_matches", 0)

    # Recomputed accuracies
    # If Zemberek is judged RIGHT or BOTH, Zemberek was correct!
    zemberek_credit = verdict_counts["ZEMBEREK"] + verdict_counts["BOTH"]
    true_ambiguous_correct = matched_original + zemberek_credit
    true_ambiguity_acc = (true_ambiguous_correct / total_amb) * 100.0 if total_amb > 0 else 0.0

    total_tokens = eval_data.get("total_tokens", 0)
    orig_overall_matches = eval_data.get("trained_overall_matches", 0)
    true_overall_matches = orig_overall_matches + zemberek_credit
    true_overall_acc = (true_overall_matches / total_tokens) * 100.0 if total_tokens > 0 else 0.0

    summary_report = {
        "dataset": eval_data.get("source_file", args.eval_json),
        "total_sentences": eval_data.get("total_sentences", 0),
        "total_tokens": total_tokens,
        "total_ambiguous_tokens": total_amb,
        "originally_matched_ambiguous": matched_original,
        "reported_ambiguity_accuracy": eval_data.get("trained_ambiguity_accuracy", 0.0) * 100.0,
        "reported_overall_accuracy": eval_data.get("trained_overall_accuracy", 0.0) * 100.0,
        "disputed_tokens_evaluated": total_disputed,
        "verdict_breakdown": {
            "ZEMBEREK_WAS_RIGHT": {
                "count": verdict_counts["ZEMBEREK"],
                "percentage_of_disputed": (verdict_counts["ZEMBEREK"] / max(1, total_disputed)) * 100.0
            },
            "UD_WAS_RIGHT": {
                "count": verdict_counts["UD"],
                "percentage_of_disputed": (verdict_counts["UD"] / max(1, total_disputed)) * 100.0
            },
            "BOTH_VALID_TWINS": {
                "count": verdict_counts["BOTH"],
                "percentage_of_disputed": (verdict_counts["BOTH"] / max(1, total_disputed)) * 100.0
            },
            "NEITHER_VALID": {
                "count": verdict_counts["NEITHER"],
                "percentage_of_disputed": (verdict_counts["NEITHER"] / max(1, total_disputed)) * 100.0
            }
        },
        "adjudicated_metrics": {
            "zemberek_justified_gain_tokens": zemberek_credit,
            "true_ambiguity_matches": true_ambiguous_correct,
            "true_ambiguity_accuracy": true_ambiguity_acc,
            "true_overall_matches": true_overall_matches,
            "true_overall_accuracy": true_overall_acc
        },
        "top_reasons_ud_was_right": [
            {"word": w, "reason": r, "count": c}
            for (w, r), c in Counter(ud_reasons).most_common(15)
        ],
        "top_reasons_zemberek_was_right": [
            {"word": w, "reason": r, "count": c}
            for (w, r), c in Counter(zemberek_reasons).most_common(15)
        ],
        "top_reasons_both_valid": [
            {"word": w, "reason": r, "count": c}
            for (w, r), c in Counter(both_reasons).most_common(15)
        ]
    }

    rep_dir = os.path.dirname(args.report_json)
    if rep_dir:
        os.makedirs(rep_dir, exist_ok=True)
    with open(args.report_json, "w", encoding="utf-8") as f:
        json.dump(summary_report, f, indent=2, ensure_ascii=False)

    print("\n" + "=" * 80)
    print("                      ADJUDICATION SUMMARY REPORT                      ")
    print("=" * 80)
    print(f"Dataset:                       {summary_report['dataset']}")
    print(f"Total Sentences Evaluated:     {summary_report['total_sentences']}")
    print(f"Total Ambiguous Tokens:        {total_amb}")
    print(f"Reported (Raw) Ambiguity Acc:  {summary_report['reported_ambiguity_accuracy']:.2f}% ({matched_original}/{total_amb})")
    print(f"Reported (Raw) Overall Acc:    {summary_report['reported_overall_accuracy']:.2f}%")
    print("-" * 80)
    print(f"Total Disputed Tokens:         {total_disputed}")
    print(f"  • Zemberek was RIGHT:        {verdict_counts['ZEMBEREK']:4d} ({verdict_counts['ZEMBEREK']/total_disputed*100:.1f}%)")
    print(f"  • Both are VALID (twins):    {verdict_counts['BOTH']:4d} ({verdict_counts['BOTH']/total_disputed*100:.1f}%)")
    print(f"  • UD was RIGHT (Zem error):  {verdict_counts['UD']:4d} ({verdict_counts['UD']/total_disputed*100:.1f}%)")
    print(f"  • Neither was right:         {verdict_counts['NEITHER']:4d} ({verdict_counts['NEITHER']/total_disputed*100:.1f}%)")
    print("-" * 80)
    print(f"Zemberek Legitimate Credit:    +{zemberek_credit} tokens")
    print(f"True Real-World Ambiguity Acc: {true_ambiguity_acc:.2f}% ({true_ambiguous_correct}/{total_amb})")
    print(f"True Real-World Overall Acc:   {true_overall_acc:.2f}% ({true_overall_matches}/{total_tokens})")
    print("=" * 80)
    print(f"\nDetailed summary written to: {args.report_json}")


if __name__ == "__main__":
    main()

