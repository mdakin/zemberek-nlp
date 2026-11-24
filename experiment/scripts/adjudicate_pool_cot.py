#!/usr/bin/env python3
"""
adjudicate_pool_cot.py

Performs targeted Chain-of-Thought (CoT) adjudication on disputed and high-uncertainty
sentences using Gemini 3.8 Flash with thinking budget (e.g. 512 tokens).
Maintains strict sentence order and keeps existing labels if an ambiguous token is not explicitly updated.
"""

import os
import sys
import json
import time
import argparse
import requests
import concurrent.futures
from typing import Dict, Any, List, Tuple


def load_env(filepath: str = ".env") -> None:
    if os.path.exists(filepath):
        with open(filepath, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    os.environ[k.strip()] = v.strip().strip("'").strip('"')


load_env()
load_env(".env.local")

SYSTEM_INSTRUCTION = (
    "You are a world-class Turkish computational linguist specialized in morphological disambiguation.\n"
    "Select the single most accurate morphological analysis ID for each ambiguous word in the given sentence.\n"
    "Carefully evaluate syntactic structure, transitivity, and semantic role using reasoning:\n"
    "1. Accusative (+Acc) vs. 3rd Person Possessive (+P3sg/+P3pl) on -i/-ı/-u/-ü / -leri:\n"
    "   - Accusative: direct object governed by a transitive verb (e.g. 'Kitabı okudu', 'Sindirimi kolaylaştırır').\n"
    "   - Possessive: genitive-possessive construction, definite noun compound (e.g. 'onun kitabı', 'göz rengi').\n"
    "2. Verbal Noun (-me/-ma Inf2→Noun) vs. Negative Imperative (+Neg+Imp):\n"
    "   - Verbal noun: acts as nominal complement, subject, or takes case/possessive suffixes (e.g. 'asmaları gerek', 'yüzme bilmek').\n"
    "   - Negative imperative: direct command addressed to 2nd person (e.g. 'sakın gitme!', 'bana yüz verme!').\n"
    "3. Aorist Verb (+Aor) vs. Noun/Adjective on -r/-ar/-er:\n"
    "   - Aorist verb: acts as finite predicate or relative participle (e.g. 'her gün okur', 'kolaylaştırır').\n"
    "   - Noun/Adj: lexical noun or modifier (e.g. 'okur yazar', 'gelir tablosu').\n"
    "4. Postposition (+PCAbl, +PCDat, +PCNom) vs. Adverb/Noun:\n"
    "   - 'yana': requires Ablative complement ('-den yana') when postposition; otherwise Dative noun ('bu yana').\n"
    "   - 'önce' / 'sonra': postposition with Ablative ('senden önce'), temporal adverb when standalone ('önce o geldi').\n"
    "5. Proper Noun (+Prop) vs. Common Noun sentence-initially: only choose Proper if the word is an actual named entity in context.\n"
    "Return only valid JSON mapping token index string to chosen candidate ID integer. Example: {\"0\": 3, \"1\": 0}"
)


def format_prompt(rec: Dict[str, Any]) -> str:
    lines = [f'Sentence: "{rec["text"]}"', "Select correct candidate ID for each ambiguous word:"]
    for t in rec.get("tokens", []):
        if t.get("is_ambiguous"):
            cands = t.get("candidates", [])
            cand_items = []
            for c in cands:
                cid = c.get('id', 0)
                ofl = c.get('oflazer_style', '').strip()
                zk = c.get('zemberek_key', '').strip()
                if ofl and zk:
                    cand_items.append(f"[{cid}] {ofl} (Zemberek: {zk})")
                elif zk:
                    cand_items.append(f"[{cid}] {zk}")
                else:
                    cand_items.append(f"[{cid}] {ofl}")
            cands_str = "  ".join(cand_items)
            lines.append(f"- #{t['index']} \"{t['surface']}\": {cands_str}")
    lines.append('Return JSON: {"<index>": <chosen_id>}')
    return "\n".join(lines)


class AnnotatedResult:
    def __init__(self, index: int, record: Dict[str, Any], prompt_tokens: int, cand_tokens: int, thought_tokens: int, changes: int):
        self.index = index
        self.record = record
        self.prompt_tokens = prompt_tokens
        self.cand_tokens = cand_tokens
        self.thought_tokens = thought_tokens
        self.changes = changes


def annotate_one(
    item: Tuple[int, str],
    api_key: str,
    model: str,
    thinking_budget: int
) -> AnnotatedResult:
    orig_idx, line = item
    rec = json.loads(line)
    prompt = format_prompt(rec)
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api_key}"

    gen_config: Dict[str, Any] = {
        "temperature": 0.0,
        "responseMimeType": "application/json"
    }
    if thinking_budget > 0:
        gen_config["thinkingConfig"] = {"thinkingBudget": thinking_budget}

    payload = {
        "system_instruction": {"parts": [{"text": SYSTEM_INSTRUCTION}]},
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": gen_config
    }

    prompt_t, cand_t, thought_t = 0, 0, 0
    changes = 0

    for attempt in range(5):
        try:
            resp = requests.post(url, json=payload, timeout=60)
            if resp.status_code == 200:
                resp_json = resp.json()
                usage = resp_json.get("usageMetadata", {})
                prompt_t = usage.get("promptTokenCount", 0)
                cand_t = usage.get("candidatesTokenCount", 0)
                thought_t = usage.get("thoughtsTokenCount", 0)

                candidates = resp_json.get("candidates", [])
                if not candidates:
                    time.sleep(1.0 + attempt)
                    continue

                parts = candidates[0].get("content", {}).get("parts", [])
                if not parts:
                    time.sleep(1.0 + attempt)
                    continue

                text = parts[0].get("text", "").strip()
                if text.startswith("```json"):
                    text = text[7:]
                if text.startswith("```"):
                    text = text[3:]
                if text.endswith("```"):
                    text = text[:-3]
                sel_map = json.loads(text.strip())

                for t in rec.get("tokens", []):
                    if t.get("is_ambiguous"):
                        idx_str = str(t["index"])
                        valid_ids = [c["id"] for c in t.get("candidates", [])]
                        chosen = sel_map.get(idx_str, sel_map.get(int(idx_str))) if isinstance(sel_map, dict) else None
                        old_id = t.get("selected_candidate_id")
                        if chosen is not None and int(chosen) in valid_ids:
                            new_id = int(chosen)
                            if old_id is not None and old_id != new_id:
                                changes += 1
                            t["selected_candidate_id"] = new_id
                            t["selection_reasoning"] = f"Reasoning CoT Adjudication (Budget={thinking_budget})"
                return AnnotatedResult(orig_idx, rec, prompt_t, cand_t, thought_t, changes)
            elif resp.status_code == 429:
                time.sleep(2.0 * (attempt + 1))
            else:
                time.sleep(1.5 * (attempt + 1))
        except Exception:
            time.sleep(1.5 * (attempt + 1))

    # If all attempts failed, preserve existing record without changes
    return AnnotatedResult(orig_idx, rec, prompt_t, cand_t, thought_t, changes)


def main():
    parser = argparse.ArgumentParser(description="Adjudicate disputed sentences with Gemini CoT reasoning budget.")
    parser.add_argument("-i", "--input", default="data/ambiguity/adjudication_pool_candidates.jsonl",
                        help="Input candidate JSONL file")
    parser.add_argument("-o", "--output", default="data/ambiguity/adjudication_pool_reasoned.jsonl",
                        help="Output adjudicated JSONL file")
    parser.add_argument("-b", "--thinking-budget", type=int, default=512,
                        help="Thinking budget in tokens (default 512)")
    parser.add_argument("-m", "--model", default="gemini-3.8-flash",
                        help="Gemini model name (default: gemini-3.8-flash)")
    parser.add_argument("-w", "--workers", type=int, default=16,
                        help="Number of concurrent worker threads")
    args = parser.parse_args()

    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        print("[ERROR] GEMINI_API_KEY environment variable is not set.", file=sys.stderr)
        sys.exit(1)

    print(f"[INFO] Reading candidates from: {args.input}")
    with open(args.input, "r", encoding="utf-8") as f:
        lines = [l for l in f if l.strip()]

    print(f"[INFO] Sentences to adjudicate: {len(lines)}")
    print(f"[INFO] Model: {args.model} | Thinking Budget: {args.thinking_budget} | Workers: {args.workers}")

    start_time = time.time()
    total_prompt = 0
    total_cand = 0
    total_thought = 0
    total_changes = 0
    indexed_results: List[Any] = [None] * len(lines)

    items = list(enumerate(lines))

    def task(item):
        return annotate_one(item, api_key, args.model, args.thinking_budget)

    checkpoint_interval = 100
    os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
    temp_output = args.output + ".tmp"

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures = [executor.submit(task, item) for item in items]
        for idx, fut in enumerate(concurrent.futures.as_completed(futures), 1):
            res = fut.result()
            indexed_results[res.index] = res.record
            total_prompt += res.prompt_tokens
            total_cand += res.cand_tokens
            total_thought += res.thought_tokens
            total_changes += res.changes

            if idx % 50 == 0 or idx == len(lines):
                elapsed = time.time() - start_time
                rate = idx / max(0.1, elapsed)
                eta = (len(lines) - idx) / max(0.1, rate)
                print(f"  [{idx:4d}/{len(lines)}] sentences ({idx/len(lines)*100:5.1f}%) | "
                      f"elapsed: {elapsed:5.1f}s | rate: {rate:4.1f} sent/s | ETA: {eta:4.1f}s | "
                      f"tokens changed: {total_changes} | thought tokens: {total_thought:,}")

            if idx % checkpoint_interval == 0:
                with open(temp_output, "w", encoding="utf-8") as tmp_f:
                    for r in indexed_results:
                        if r is not None:
                            tmp_f.write(json.dumps(r, ensure_ascii=False) + "\n")

    with open(args.output, "w", encoding="utf-8") as out_f:
        for r in indexed_results:
            out_f.write(json.dumps(r, ensure_ascii=False) + "\n")

    if os.path.exists(temp_output):
        try:
            os.remove(temp_output)
        except OSError:
            pass

    elapsed_total = time.time() - start_time
    cost = (total_prompt / 1_000_000 * 0.75) + ((total_cand + total_thought) / 1_000_000 * 3.75)

    print("\n==================================================================")
    print("           ADJUDICATION SUMMARY                                   ")
    print("==================================================================")
    print(f"Total Sentences Adjudicated: {len(indexed_results)}")
    print(f"Total Disputed Tokens Changed: {total_changes}")
    print(f"Time Taken:                  {elapsed_total:.1f} seconds ({len(indexed_results)/max(0.1, elapsed_total):.1f} sent/sec)")
    print(f"Total Prompt Tokens:         {total_prompt:,}")
    print(f"Total Output Tokens:         {total_cand:,}")
    print(f"Total Thought Tokens:        {total_thought:,} (Avg {total_thought/max(1, len(indexed_results)):.1f} per sentence)")
    print(f"Estimated Cost:              ${cost:.4f}")
    print(f"Output File:                 {args.output}")
    print("==================================================================\n")


if __name__ == "__main__":
    main()

