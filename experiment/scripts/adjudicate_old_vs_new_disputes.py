#!/usr/bin/env python3
"""
adjudicate_old_vs_new_disputes.py

Extracts up to 200 balanced sentences from UD BOUN and UD IMST test sets where:
  - The New Zemberek Model (Fast Perceptron + 33k) failed according to the test data.
  - The Old Zemberek Model (Perceptron + model-compressed) agreed with the test data.

Packages both raw Zemberek SingleAnalysis keys and converted CoNLL-U representations
for both models alongside the original test data, and queries Gemini 3.8 Flash (with
thinking budget) to adjudicate which model is linguistically correct in context.
"""

import os
import sys
import json
import time
import argparse
import requests
import concurrent.futures
from pathlib import Path
from typing import Dict, Any, List, Optional
from collections import Counter


SYSTEM_INSTRUCTION = (
    "You are an expert Turkish computational linguist adjudicating morphological disambiguation disputes.\n"
    "You are evaluating sentences from Turkish treebanks (BOUN and IMST) where the legacy 2018 Zemberek model "
    "agreed with the treebank ground truth, but the new 2026 Zemberek distilled model made a different selection.\n"
    "For each disputed token in the sentence, carefully examine:\n"
    "1. The sentence context and syntactic structure.\n"
    "2. The Original Test Data solution (both raw CoNLL-U tags and mapped Zemberek key).\n"
    "3. The Old Zemberek solution (both raw Zemberek key and converted CoNLL-U line).\n"
    "4. The New Zemberek solution (both raw Zemberek key and converted CoNLL-U line).\n"
    "5. All available dictionary candidates from Zemberek.\n\n"
    "Determine which solution is linguistically correct in this sentence context:\n"
    "- 'NEW_ZEMBEREK': The New Model's analysis is correct; the test data and old model represent an old tagger artifact, "
    "a non-standard treebank convention (e.g. tagging existential 'var' as Noun, pronoun 'kimse' as Noun, or postposition 'ile' as Conjunction), "
    "or an outright treebank error.\n"
    "- 'OLD_ZEMBEREK': The Old Model and test data are correct; the New Model made an actual parsing error.\n"
    "- 'BOTH_VALID': Both analyses are linguistically acceptable alternatives (e.g. valid twin analyses, lexicalized vs derived, aspectual nuance).\n"
    "- 'NEITHER': Neither analysis is correct.\n\n"
    "In the given Turkish sentence, evaluate the candidate morphological analyses for each disputed token in context.\n"
    "For each disputed token in the sentence:\n"
    "1. Inspect the full sentence context, syntactic relations, and governing words.\n"
    "2. Compare Candidate A (Treebank ground truth), Candidate B (Baseline model), and Candidate C (New model).\n"
    "3. Determine which analysis is linguistically correct in this sentence context:\n"
    "   - 'CANDIDATE_C': Candidate C's analysis is correct.\n"
    "   - 'CANDIDATE_B': Candidate B's analysis is correct.\n"
    "   - 'CANDIDATE_A': Candidate A's analysis is correct.\n"
    "   - 'BOTH_VALID': Candidates are linguistically acceptable alternatives in context.\n"
    "   - 'NEITHER': None of the candidate analyses are correct in context.\n\n"
    "Return strictly JSON mapping the token index string to a judgment object:\n"
    "{\"<token_index>\": {\"verdict\": \"NEW_ZEMBEREK\"|\"OLD_ZEMBEREK\"|\"BOTH_VALID\"|\"NEITHER\", \"reason\": \"<concise explanation, max 15 words>\"}}"
    "{\"<token_index>\": {\"verdict\": \"CANDIDATE_C\"|\"CANDIDATE_B\"|\"CANDIDATE_A\"|\"BOTH_VALID\"|\"NEITHER\", \"reason\": \"<concise explanation, max 15 words>\"}}"
)


def parse_conllu_sentences(path: Path) -> List[Dict[str, Any]]:
    sentences = []
    curr_sent_id = None
    curr_text = None
    curr_tokens = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                if curr_sent_id is not None:
                    sentences.append({
                        "sent_id": curr_sent_id,
                        "text": curr_text,
                        "tokens": curr_tokens
                    })
                curr_sent_id = None
                curr_text = None
                curr_tokens = []
                continue
            if line.startswith("#"):
                if line.startswith("# sent_id"):
                    curr_sent_id = line.split("=", 1)[1].strip()
                elif line.startswith("# text"):
                    curr_text = line.split("=", 1)[1].strip()
                continue
            parts = line.split("\t")
            curr_tokens.append(parts)
        if curr_sent_id is not None:
            sentences.append({
                "sent_id": curr_sent_id,
                "text": curr_text,
                "tokens": curr_tokens
            })
    return sentences


def load_canonical_candidates(path: Path) -> List[Dict[int, List[Dict[str, Any]]]]:
    candidates_by_sent = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            s = json.loads(line)
            tokens_cands = {}
            for t in s.get("tokens", []):
                tokens_cands[t["index"]] = t.get("candidates", [])
            candidates_by_sent.append(tokens_cands)
    return candidates_by_sent


def extract_candidate_disputes(eval_path: Path, treebank_name: str) -> List[Dict[str, Any]]:
    with open(eval_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    candidates = []
    for s_idx, s in enumerate(data["sentences"]):
        disputes = []
        for t in s["tokens"]:
            if t["is_ambiguous"] and t["default_matches_gemini"] and not t["trained_matches_gemini"]:
                disputes.append({
                    "index": t["index"],
                    "surface": t["surface"],
                    "gold_key": t["gemini_selected_key"],
                    "old_key": t["default_selected_key"],
                    "new_key": t["trained_selected_key"]
                })
        if disputes:
            candidates.append({
                "sentence_id": s["sentence_id"],
                "sentence_idx": s_idx,
                "text": s["text"],
                "treebank": treebank_name,
                "disputes": disputes,
                "primary_surface": disputes[0]["surface"].lower()
            })
    return candidates


def sample_stratified(cands: List[Dict[str, Any]], target_count: int) -> List[Dict[str, Any]]:
    caps = {
        "var": 12, "ile": 12, "kimse": 6, "kimsenin": 4,
        "bir": 8, "bu": 6, "o": 6, "ise": 6, "değil": 6,
        "dün": 4, "yani": 4, "ya": 4, "iyi": 6, "için": 6
    }
    selected = []
    seen = Counter()
    # Pass 1: open-class / diverse vocabulary first
    for c in cands:
        surf = c["primary_surface"]
        if surf not in caps:
            if seen[surf] < 2 and len(selected) < target_count:
                selected.append(c)
                seen[surf] += 1
    # Pass 2: capped words up to their quota
    for c in cands:
        surf = c["primary_surface"]
        if surf in caps and c not in selected:
            if seen[surf] < caps[surf] and len(selected) < target_count:
                selected.append(c)
                seen[surf] += 1
    # Pass 3: fill remainder
    for c in cands:
        if c not in selected and len(selected) < target_count:
            selected.append(c)
            seen[c["primary_surface"]] += 1
    return selected


def find_matching_conllu_token(conllu_tokens: List[List[str]], surface: str) -> Optional[List[str]]:
    # 1. Exact match on form
    for parts in conllu_tokens:
        if "-" not in parts[0] and parts[1] == surface:
            return parts
    # 2. Case-insensitive match
    for parts in conllu_tokens:
        if "-" not in parts[0] and parts[1].lower() == surface.lower():
            return parts
    # 3. Substring match for MWT heads
    for parts in conllu_tokens:
        if "-" not in parts[0] and (parts[1] in surface or surface in parts[1]):
            return parts
    return None


def format_prompt(
    item: Dict[str, Any],
    gold_conll: Dict[str, Any],
    old_conll: Dict[str, Any],
    new_conll: Dict[str, Any],
    candidates_map: Dict[int, List[Dict[str, Any]]]
) -> str:
    lines = [
        f'Treebank: {item["treebank"]} | Sentence ID: {item["sentence_id"]}',
        f'Sentence: "{item["text"]}"',
        "\nDisputed Tokens:"
    ]

    for d in item["disputes"]:
        idx = d["index"]
        surf = d["surface"]

        # CoNLL-U representations
        g_tok = find_matching_conllu_token(gold_conll["tokens"], surf)
        o_tok = find_matching_conllu_token(old_conll["tokens"], surf)
        n_tok = find_matching_conllu_token(new_conll["tokens"], surf)

        g_str = f"LEMMA={g_tok[2]} | UPOS={g_tok[3]} | FEATS={g_tok[5]}" if g_tok else "N/A"
        o_str = f"LEMMA={o_tok[2]} | UPOS={o_tok[3]} | FEATS={o_tok[5]}" if o_tok else "N/A"
        n_str = f"LEMMA={n_tok[2]} | UPOS={n_tok[3]} | FEATS={n_tok[5]}" if n_tok else "N/A"

        cands = candidates_map.get(idx, [])
        cands_str = "\n".join(f"    [{c['id']}] {c.get('zemberek_key', '')}" for c in cands)

        lines.append(f'\n--- Token #{idx} "{surf}" ---')
        lines.append(f'[1] Original Test Data (UD Ground Truth):')
        lines.append(f'    - Raw CoNLL-U:        {g_str}')
        lines.append(f'    - Mapped Zemberek:    {d["gold_key"]}')
        lines.append(f'[2] Old Zemberek Solution (Perceptron Baseline):')
        lines.append(f'    - Raw Zemberek:       {d["old_key"]}')
        lines.append(f'    - Converted CoNLL-U:  {o_str}')
        lines.append(f'[3] New Zemberek Solution (Fast Perceptron 33K):')
        lines.append(f'    - Raw Zemberek:       {d["new_key"]}')
        lines.append(f'    - Converted CoNLL-U:  {n_str}')
        lines.append(f'[Available Zemberek Candidates]:\n{cands_str}')
        lines.append(f'[Candidate A]:')
        lines.append(f'    - Morphological Analysis: {d["gold_key"]}')
        lines.append(f'    - CoNLL-U Representation: {g_str}')
        lines.append(f'[Candidate B]:')
        lines.append(f'    - Morphological Analysis: {d["old_key"]}')
        lines.append(f'    - CoNLL-U Representation: {o_str}')
        lines.append(f'[Candidate C]:')
        lines.append(f'    - Morphological Analysis: {d["new_key"]}')
        lines.append(f'    - CoNLL-U Representation: {n_str}')
        if cands_str:
            lines.append(f'[Available Dictionary Analyses]:\n{cands_str}')

    lines.append(
        '\nTask: In this sentence context, which solution is linguistically correct for each token?\n'
        'Options: "NEW_ZEMBEREK" | "OLD_ZEMBEREK" | "BOTH_VALID" | "NEITHER"\n'
        'Return JSON: {"<token_index>": {"verdict": "NEW_ZEMBEREK"|"OLD_ZEMBEREK"|"BOTH_VALID"|"NEITHER", "reason": "<concise reason, max 15 words>"}}'
        '\nTask: In this sentence context, which candidate analysis is linguistically correct for each token?\n'
        'Options: "CANDIDATE_C" | "CANDIDATE_B" | "CANDIDATE_A" | "BOTH_VALID" | "NEITHER"\n'
        'Return JSON: {"<token_index>": {"verdict": "CANDIDATE_C"|"CANDIDATE_B"|"CANDIDATE_A"|"BOTH_VALID"|"NEITHER", "reason": "<concise reason, max 15 words>"}}'
    )
    return "\n".join(lines)


def call_gemini(url: str, prompt: str, max_retries: int = 6) -> Optional[Dict[str, Dict[str, str]]]:
    payload = {
        "system_instruction": {"parts": [{"text": SYSTEM_INSTRUCTION}]},
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {
            "temperature": 0.0,
            "responseMimeType": "application/json",
            "thinkingConfig": {"thinkingBudget": 512}
        }
    }

    for attempt in range(max_retries):
        try:
            resp = requests.post(url, json=payload, timeout=45)
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
                        if verdict not in ["NEW_ZEMBEREK", "OLD_ZEMBEREK", "BOTH_VALID", "NEITHER"]:
                            verdict = "NEITHER"
                        reason = str(v.get("reason", "")).strip()
                        res[str(k)] = {"verdict": verdict, "reason": reason}
                    elif isinstance(v, str):
                        verdict = v.upper()
                        if verdict not in ["NEW_ZEMBEREK", "OLD_ZEMBEREK", "BOTH_VALID", "NEITHER"]:
                            verdict = "NEITHER"
                        res[str(k)] = {"verdict": verdict, "reason": ""}
                return res
            elif resp.status_code == 429:
                time.sleep((2 ** attempt) * 2.0 + 1.0)
            else:
                time.sleep(1.0 * (attempt + 1))
        except Exception as e:
            time.sleep(1.5 * (attempt + 1))
    return None


def main():
    parser = argparse.ArgumentParser(description="Adjudicate Old vs New Zemberek disputes on BOUN and IMST.")
    parser.add_argument("--count", type=int, default=200, help="Number of sentences to evaluate (default: 200)")
    parser.add_argument("--concurrency", type=int, default=12, help="Number of concurrent workers (default: 12)")
    parser.add_argument("--output-jsonl", type=Path, default=Path("data/ambiguity/adjudication_old_vs_new_200.jsonl"))
    parser.add_argument("--output-summary", type=Path, default=Path("data/ambiguity/adjudication_old_vs_new_200_summary.json"))
    args = parser.parse_args()

    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        print("ERROR: GEMINI_API_KEY environment variable is missing!", file=sys.stderr)
        sys.exit(1)

    url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent?key={api_key}"

    print("Loading data files...")
    # CoNLL-U files
    boun_gold_conll = parse_conllu_sentences(Path("data/ambiguity/sources/UD_Turkish-BOUN/tr_boun-ud-test.conllu"))
    boun_old_conll = parse_conllu_sentences(Path("data/ambiguity/ud_eval_results/boun_Old_Perceptron__compressed_.conllu"))
    boun_new_conll = parse_conllu_sentences(Path("data/ambiguity/ud_eval_results/boun_Fast_Perceptron__model-distilled-33k_.conllu"))

    imst_gold_conll = parse_conllu_sentences(Path("data/ambiguity/sources/UD_Turkish-IMST/tr_imst-ud-test.conllu"))
    imst_old_conll = parse_conllu_sentences(Path("data/ambiguity/ud_eval_results/imst_Old_Perceptron__compressed_.conllu"))
    imst_new_conll = parse_conllu_sentences(Path("data/ambiguity/ud_eval_results/imst_Fast_Perceptron__model-distilled-33k_.conllu"))

    # Canonical candidate files
    boun_candidates = load_canonical_candidates(Path("data/ambiguity/sources/UD_Turkish-BOUN/boun_test_canonical.jsonl"))
    imst_candidates = load_canonical_candidates(Path("data/ambiguity/sources/UD_Turkish-IMST/imst_test_canonical.jsonl"))

    # Extract disputes
    boun_cands = extract_candidate_disputes(Path("data/ambiguity/eval_boun_old_vs_new.json"), "BOUN")
    imst_cands = extract_candidate_disputes(Path("data/ambiguity/eval_imst_old_vs_new.json"), "IMST")

    half = args.count // 2
    boun_sampled = sample_stratified(boun_cands, half)
    imst_sampled = sample_stratified(imst_cands, args.count - len(boun_sampled))
    sampled_sentences = boun_sampled + imst_sampled

    print(f"Sampled {len(boun_sampled)} BOUN + {len(imst_sampled)} IMST = {len(sampled_sentences)} total sentences.")
    total_tokens = sum(len(s["disputes"]) for s in sampled_sentences)
    print(f"Total disputed tokens to adjudicate: {total_tokens}")

    # Build work items
    work_items = []
    for s in sampled_sentences:
        s_idx = s["sentence_idx"]
        if s["treebank"] == "BOUN":
            g_c = boun_gold_conll[s_idx]
            o_c = boun_old_conll[s_idx]
            n_c = boun_new_conll[s_idx]
            c_m = boun_candidates[s_idx]
        else:
            g_c = imst_gold_conll[s_idx]
            o_c = imst_old_conll[s_idx]
            n_c = imst_new_conll[s_idx]
            c_m = imst_candidates[s_idx]

        prompt = format_prompt(s, g_c, o_c, n_c, c_m)
        work_items.append({
            "sentence_id": s["sentence_id"],
            "sentence_idx": s_idx,
            "treebank": s["treebank"],
            "text": s["text"],
            "disputes": s["disputes"],
            "prompt": prompt
        })

    print(f"\nSubmitting {len(work_items)} sentences to Gemini 3.8 Flash (concurrency: {args.concurrency})...")

    results = []
    completed_count = 0

    def process_item(item):
        judgment = call_gemini(url, item["prompt"])
        return item, judgment

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        futures = {executor.submit(process_item, item): item for item in work_items}
        for fut in concurrent.futures.as_completed(futures):
            item, judgment = fut.result()
            completed_count += 1
            if judgment is None:
                judgment = {}

            annotated_disputes = []
            for d in item["disputes"]:
                str_idx = str(d["index"])
                j = judgment.get(str_idx, {"verdict": "NEITHER", "reason": "No response / parse failed"})
                raw_v = str(j.get("verdict", "NEITHER")).upper().strip()
                if raw_v in ("CANDIDATE_C", "NEW_ZEMBEREK"):
                    v = "NEW_ZEMBEREK"
                elif raw_v in ("CANDIDATE_B", "OLD_ZEMBEREK", "CANDIDATE_A"):
                    v = "OLD_ZEMBEREK"
                elif raw_v in ("BOTH_VALID", "BOTH"):
                    v = "BOTH_VALID"
                else:
                    v = "NEITHER"

                annotated_disputes.append({
                    **d,
                    "verdict": j.get("verdict", "NEITHER"),
                    "verdict": v,
                    "reason": j.get("reason", "")
                })

            res_record = {
                "sentence_id": item["sentence_id"],
                "sentence_idx": item["sentence_idx"],
                "treebank": item["treebank"],
                "text": item["text"],
                "disputes": annotated_disputes
            }
            results.append(res_record)

            if completed_count % 25 == 0 or completed_count == len(work_items):
                print(f"Progress: {completed_count}/{len(work_items)} sentences adjudicated...")

    # Write output JSONL
    args.output_jsonl.parent.mkdir(parents=True, exist_ok=True)
    with open(args.output_jsonl, "w", encoding="utf-8") as f:
        for r in results:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    # Aggregate Statistics
    verdict_counter = Counter()
    treebank_verdicts = {"BOUN": Counter(), "IMST": Counter()}
    examples_by_verdict = {"NEW_ZEMBEREK": [], "OLD_ZEMBEREK": [], "BOTH_VALID": [], "NEITHER": []}

    total_tokens_evaluated = 0
    for r in results:
        tb = r["treebank"]
        for d in r["disputes"]:
            total_tokens_evaluated += 1
            v = d["verdict"]
            verdict_counter[v] += 1
            treebank_verdicts[tb][v] += 1
            if len(examples_by_verdict[v]) < 10:
                examples_by_verdict[v].append({
                    "treebank": tb,
                    "sentence_id": r["sentence_id"],
                    "text": r["text"],
                    "token": d["surface"],
                    "old_key": d["old_key"],
                    "new_key": d["new_key"],
                    "gold_key": d["gold_key"],
                    "reason": d["reason"]
                })

    summary = {
        "total_sentences": len(results),
        "total_tokens": total_tokens_evaluated,
        "overall_verdicts": dict(verdict_counter),
        "boun_verdicts": dict(treebank_verdicts["BOUN"]),
        "imst_verdicts": dict(treebank_verdicts["IMST"]),
        "rates": {k: f"{v / total_tokens_evaluated * 100.0:.2f}%" for k, v in verdict_counter.items()},
        "examples": examples_by_verdict
    }

    with open(args.output_summary, "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)

    print("\n" + "=" * 80)
    print("                    ADJUDICATION RESULTS (OLD VS NEW DISPUTES)")
    print("=" * 80)
    print(f"Total Sentences: {len(results)}")
    print(f"Total Disputed Tokens: {total_tokens_evaluated}")
    print("--------------------------------------------------------------------------------")
    print(f"{'Verdict':<20} | {'Count':>8} | {'Percentage':>10}")
    print("--------------------------------------------------------------------------------")
    for v in ["NEW_ZEMBEREK", "OLD_ZEMBEREK", "BOTH_VALID", "NEITHER"]:
        cnt = verdict_counter.get(v, 0)
        pct = (cnt / total_tokens_evaluated * 100.0) if total_tokens_evaluated else 0.0
        print(f"{v:<20} | {cnt:>8} | {pct:>9.2f}%")
    print("=" * 80)
    print(f"Results saved to: {args.output_jsonl}")
    print(f"Summary saved to: {args.output_summary}")


if __name__ == "__main__":
    main()

