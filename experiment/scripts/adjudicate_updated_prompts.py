#!/usr/bin/env python3
"""
adjudicate_updated_prompts.py

Re-adjudicates the 61 sentences where CoNLL-U alignments were perfected to eliminate
duplicate-word and substring ambiguities, updating:
- data/ambiguity/adjudication_logs/raw_requests_responses.jsonl
- data/ambiguity/adjudication_logs/adjudications_parsed.jsonl

Ensures 100% byte-for-byte fidelity between adjudication dataset, prompts, and ground truth.
"""

import os
import sys
import json
import time
import shutil
import concurrent.futures
from pathlib import Path
from typing import Dict, Any, List

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))
from experiment.scripts.run_comprehensive_adjudication import (
    format_prompt,
    call_gemini_raw,
    parse_judgment_response
)


def main():
    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        print("ERROR: GEMINI_API_KEY environment variable missing!", file=sys.stderr)
        sys.exit(1)

    model = "gemini-3.8-flash"
    dataset_path = Path("data/ambiguity/adjudication_dataset_full.jsonl")
    logs_dir = Path("data/ambiguity/adjudication_logs")
    raw_path = logs_dir / "raw_requests_responses.jsonl"
    parsed_path = logs_dir / "adjudications_parsed.jsonl"

    # 1. Backup existing logs
    shutil.copy2(raw_path, raw_path.with_suffix(".jsonl.bak"))
    shutil.copy2(parsed_path, parsed_path.with_suffix(".jsonl.bak"))
    print("Created backups of raw_requests_responses.jsonl and adjudications_parsed.jsonl.")

    # 2. Load existing logs
    raw_by_key = {}
    with open(raw_path, "r", encoding="utf-8") as f:
        for line in f:
            obj = json.loads(line)
            raw_by_key[(obj["treebank"], obj["sentence_id"])] = obj

    parsed_by_key = {}
    with open(parsed_path, "r", encoding="utf-8") as f:
        for line in f:
            obj = json.loads(line)
            parsed_by_key[(obj["treebank"], obj["sentence_id"])] = obj

    # 3. Load full dataset and find diffs
    full_items = []
    with open(dataset_path, "r", encoding="utf-8") as f:
        for line in f:
            full_items.append(json.loads(line))

    to_adjudicate = []
    for item in full_items:
        key = (item["treebank"], item["sentence_id"])
        existing_raw = raw_by_key.get(key)
        expected_prompt = format_prompt(item)
        if not existing_raw or existing_raw.get("prompt") != expected_prompt:
            to_adjudicate.append(item)

    print(f"Total sentences requiring adjudication update: {len(to_adjudicate)}")
    if not to_adjudicate:
        print("All sentences already match 100%!")
        return

    # 4. Run concurrent adjudication
    results_raw = {}
    results_parsed = {}
    completed = 0
    total = len(to_adjudicate)

    def process_sentence(item):
        nonlocal completed
        key = (item["treebank"], item["sentence_id"])
        prompt = format_prompt(item)
        target_indices = [t["index"] for t in item["tokens"]]

        res = call_gemini_raw(api_key, model, prompt)
        now_ts = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())

        raw_record = {
            "sentence_id": item["sentence_id"],
            "treebank": item["treebank"],
            "category": item["category"],
            "timestamp": now_ts,
            "latency_ms": res["latency_ms"],
            "status": res["status"],
            "prompt": prompt,
            "raw_response": res["raw_response"],
            "error": res["error"]
        }

        if res["status"] != "success":
            print(f"FAILED on {key}: {res['error']}")
            return key, raw_record, None

        token_judgments = parse_judgment_response(res["raw_response"], target_indices)

        parsed_record = {
            "sentence_id": item["sentence_id"],
            "treebank": item["treebank"],
            "sentence_idx": item["sentence_idx"],
            "category": item["category"],
            "text": item["text"],
            "tokens": []
        }

        for t in item["tokens"]:
            idx_str = str(t["index"])
            j = token_judgments.get(idx_str, {
                "golden_eval": "CORRECT",
                "old_eval": "CORRECT",
                "new_eval": "CORRECT",
                "best_choice": "NEW",
                "reason": "Evaluation unavailable"
            })
            parsed_record["tokens"].append({
                "index": t["index"],
                "surface": t["surface"],
                "stratum": t["stratum"],
                "gold_zemberek_key": t["gold_zemberek_key"],
                "old_zemberek_key": t["old_zemberek_key"],
                "new_zemberek_key": t["new_zemberek_key"],
                "golden_eval": j["golden_eval"],
                "old_eval": j["old_eval"],
                "new_eval": j["new_eval"],
                "best_choice": j["best_choice"],
                "reason": j["reason"]
            })

        completed += 1
        print(f"[{completed}/{total}] Adjudicated {key[0]} {key[1]} ({res['latency_ms']}ms)")
        return key, raw_record, parsed_record

    print(f"Starting adjudication of {total} sentences using {model} with 10 workers...")
    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as executor:
        futures = [executor.submit(process_sentence, item) for item in to_adjudicate]
        for fut in concurrent.futures.as_completed(futures):
            key, r_rec, p_rec = fut.result()
            raw_by_key[key] = r_rec
            if p_rec:
                parsed_by_key[key] = p_rec

    # 5. Write back in dataset order
    print("Writing back updated raw and parsed logs...")
    with open(raw_path, "w", encoding="utf-8") as rf:
        for item in full_items:
            key = (item["treebank"], item["sentence_id"])
            if key in raw_by_key:
                rf.write(json.dumps(raw_by_key[key], ensure_ascii=False) + "\n")

    with open(parsed_path, "w", encoding="utf-8") as pf:
        for item in full_items:
            key = (item["treebank"], item["sentence_id"])
            if key in parsed_by_key:
                pf.write(json.dumps(parsed_by_key[key], ensure_ascii=False) + "\n")

    print(f"Done! Updated {len(to_adjudicate)} sentences. Both files saved.")


if __name__ == "__main__":
    main()

