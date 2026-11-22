#!/usr/bin/env python3
"""
Evaluate 100 Sentences with Gemini 3.8 Flash using a Thinking Budget of 500
and Enriched Dual Candidate Representation (Oflazer style + Zemberek native key).
Compares selections directly against the original zero-thinking budget annotations.
"""

import os
import sys
import json
import time
import requests
import concurrent.futures
from typing import Dict, Any, List, Optional, Tuple

SYSTEM_INSTRUCTION = (
    "You are an expert Turkish computational linguist specialized in morphological disambiguation. "
    "Select the single most accurate morphological analysis ID for each ambiguous word in the given sentence context. "
    "Return only valid JSON mapping token index string to candidate ID integer. Example: {\"0\": 3, \"1\": 0}"
)

def format_enriched_prompt(record: Dict[str, Any], prune_noise: bool = True) -> str:
    """Builds prompt containing both Oflazer style and Zemberek native analysis."""
    sentence = record["text"]
    ambiguous_tokens = [t for t in record["tokens"] if t.get("is_ambiguous", False)]

    lines = [
        f'Sentence: "{sentence}"',
        "Select correct candidate ID for each ambiguous word:"
    ]

    for t in ambiguous_tokens:
        cands = t.get("candidates", [])
        if prune_noise and cands:
            top_score = max(c.get("score", 0.0) for c in cands)
            pruned = [c for c in cands if c.get("score", 0.0) >= top_score - 15.0]
            if pruned:
                cands = pruned

        cands_str = "  ".join(
            f"[{c['id']}] {c.get('oflazer_style', '')} (Zemberek: {c.get('zemberek_key', '')})"
            for c in cands
        )
        lines.append(f"- #{t['index']} \"{t['surface']}\": {cands_str}")

    lines.append('Return JSON: {"<index>": <chosen_id>}')
    return "\n".join(lines)


def call_gemini(
    api_key: str,
    prompt: str,
    model: str = "gemini-3.8-flash",
    thinking_budget: int = 500,
    max_retries: int = 5
) -> Tuple[Optional[str], Dict[str, Any]]:
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api_key}"
    payload = {
        "system_instruction": {"parts": [{"text": SYSTEM_INSTRUCTION}]},
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {
            "temperature": 0.0,
            "responseMimeType": "application/json",
            "thinkingConfig": {"thinkingBudget": thinking_budget}
        }
    }

    for attempt in range(max_retries):
        try:
            resp = requests.post(url, json=payload, timeout=30)
            if resp.status_code == 200:
                data = resp.json()
                usage = data.get("usageMetadata", {})
                candidates = data.get("candidates", [])
                if candidates:
                    parts = candidates[0].get("content", {}).get("parts", [])
                    if parts:
                        return parts[0].get("text", "").strip(), usage
            elif resp.status_code == 429:
                time.sleep((2 ** attempt) * 2.0)
            else:
                time.sleep(2 ** attempt)
        except Exception as e:
            time.sleep(2 ** attempt)
    return None, {}


def parse_json(text: Optional[str]) -> Optional[Dict[str, int]]:
    if not text:
        return None
    cleaned = text.strip()
    if cleaned.startswith("```json"):
        cleaned = cleaned[7:]
    elif cleaned.startswith("```"):
        cleaned = cleaned[3:]
    if cleaned.endswith("```"):
        cleaned = cleaned[:-3]
    cleaned = cleaned.strip()

    try:
        raw = json.loads(cleaned)
        res = {}
        for k, v in raw.items():
            try:
                res[int(k)] = int(v)
            except (ValueError, TypeError):
                continue
        return res
    except Exception:
        return None


def main():
    api_key = os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY")
    if not api_key:
        print("ERROR: GEMINI_API_KEY or GOOGLE_API_KEY environment variable required.", file=sys.stderr)
        sys.exit(1)

    input_file = "/home/dndara/data/turkish/distilled/distilled_10k_annotated.jsonl"
    out_jsonl = "data/ambiguity/eval_100_thinking_vs_nothinking.jsonl"
    out_summary = "data/ambiguity/eval_100_thinking_vs_nothinking_summary.json"

    print(f"Reading first 100 sentences from: {input_file}")
    records = []
    with open(input_file, "r", encoding="utf-8") as f:
        for i in range(100):
            line = f.readline()
            if not line:
                break
            records.append(json.loads(line))

    print(f"Loaded {len(records)} sentences. Starting evaluation with Gemini 3.8 Flash (thinkingBudget: 500)...")

    results = [None] * len(records)
    total_prompt_tokens = 0
    total_candidates_tokens = 0
    total_thoughts_tokens = 0

    def process_sentence(idx, rec):
        prompt = format_enriched_prompt(rec, prune_noise=True)
        raw_text, usage = call_gemini(api_key, prompt, model="gemini-3.8-flash", thinking_budget=500)
        parsed = parse_json(raw_text)
        return idx, raw_text, parsed, usage

    start_time = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
        futures = [executor.submit(process_sentence, i, rec) for i, rec in enumerate(records)]
        done_count = 0
        for fut in concurrent.futures.as_completed(futures):
            idx, raw_text, parsed, usage = fut.result()
            results[idx] = {
                "raw_text": raw_text,
                "parsed": parsed,
                "usage": usage
            }
            total_prompt_tokens += usage.get("promptTokenCount", 0)
            total_candidates_tokens += usage.get("candidatesTokenCount", 0)
            total_thoughts_tokens += usage.get("thoughtsTokenCount", 0)
            done_count += 1
            if done_count % 20 == 0 or done_count == len(records):
                elapsed = time.time() - start_time
                print(f"Progress: {done_count}/100 sentences processed ({elapsed:.1f}s, thoughts: {total_thoughts_tokens:,} tokens)")

    total_time = time.time() - start_time

    # Compare decisions
    total_ambiguous = 0
    matches = 0
    mismatches = []
    sentence_exact_matches = 0

    annotated_output = []

    for i, rec in enumerate(records):
        res = results[i]
        parsed = res["parsed"] or {}
        sent_has_mismatch = False
        sent_tokens_count = 0

        token_comparisons = []
        for t in rec["tokens"]:
            if not t.get("is_ambiguous"):
                continue

            total_ambiguous += 1
            sent_tokens_count += 1
            t_idx = t["index"]
            orig_id = t.get("selected_candidate_id")
            new_id = parsed.get(t_idx)

            cand_map = {c["id"]: c for c in t.get("candidates", [])}
            orig_cand = cand_map.get(orig_id, {})
            new_cand = cand_map.get(new_id, {})

            is_match = (orig_id == new_id)
            if is_match:
                matches += 1
            else:
                sent_has_mismatch = True
                mismatches.append({
                    "sentence_idx": i,
                    "sentence_id": rec.get("sentence_id"),
                    "sentence_text": rec["text"],
                    "token_index": t_idx,
                    "surface": t["surface"],
                    "zero_thinking": {
                        "id": orig_id,
                        "oflazer": orig_cand.get("oflazer_style"),
                        "zemberek": orig_cand.get("zemberek_key"),
                        "score": orig_cand.get("score"),
                        "rank": orig_cand.get("rank")
                    },
                    "thinking_500": {
                        "id": new_id,
                        "oflazer": new_cand.get("oflazer_style"),
                        "zemberek": new_cand.get("zemberek_key"),
                        "score": new_cand.get("score"),
                        "rank": new_cand.get("rank")
                    }
                })

            token_comparisons.append({
                "index": t_idx,
                "surface": t["surface"],
                "zero_thinking_id": orig_id,
                "thinking_500_id": new_id,
                "match": is_match
            })

        if not sent_has_mismatch:
            sentence_exact_matches += 1

        annotated_output.append({
            "sentence_id": rec.get("sentence_id"),
            "text": rec["text"],
            "usage": res["usage"],
            "raw_response": res["raw_text"],
            "comparisons": token_comparisons
        })

    # Save outputs
    os.makedirs(os.path.dirname(out_jsonl), exist_ok=True)
    with open(out_jsonl, "w", encoding="utf-8") as f:
        for item in annotated_output:
            f.write(json.dumps(item, ensure_ascii=False) + "\n")

    summary = {
        "model": "gemini-3.8-flash",
        "thinking_budget": 500,
        "total_sentences": len(records),
        "total_ambiguous_tokens": total_ambiguous,
        "token_matches": matches,
        "token_mismatches": len(mismatches),
        "token_agreement_rate": round(matches / max(1, total_ambiguous) * 100.0, 2),
        "token_disagreement_rate": round(len(mismatches) / max(1, total_ambiguous) * 100.0, 2),
        "sentence_exact_matches": sentence_exact_matches,
        "sentence_exact_match_rate": round(sentence_exact_matches / len(records) * 100.0, 2),
        "total_prompt_tokens": total_prompt_tokens,
        "total_candidates_tokens": total_candidates_tokens,
        "total_thoughts_tokens": total_thoughts_tokens,
        "total_time_seconds": round(total_time, 2),
        "mismatches": mismatches
    }

    with open(out_summary, "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)

    print("\n" + "=" * 80)
    print("EVALUATION COMPLETE")
    print("=" * 80)
    print(f"Sentences Evaluated:        {len(records)}")
    print(f"Total Ambiguous Tokens:     {total_ambiguous}")
    print(f"Matching Tokens:            {matches} ({summary['token_agreement_rate']}%)")
    print(f"Mismatched Tokens:          {len(mismatches)} ({summary['token_disagreement_rate']}%)")
    print(f"Sentence 100% Exact Match:  {sentence_exact_matches}/100 ({summary['sentence_exact_match_rate']}%)")
    print(f"Total Thinking Tokens:      {total_thoughts_tokens:,} (avg {total_thoughts_tokens/100:.1f} / sent)")
    print(f"Total Execution Time:       {total_time:.2f}s ({len(records)/total_time:.2f} sent/s)")
    print(f"Saved full JSONL to:        {out_jsonl}")
    print(f"Saved summary JSON to:      {out_summary}")
    print("=" * 80)


if __name__ == "__main__":
    main()

