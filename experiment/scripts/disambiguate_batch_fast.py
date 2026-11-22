#!/usr/bin/env python3
"""
High-throughput, resume-safe morphological disambiguation script using Gemini 3.8 Flash
with ultra-lean prompt and internal thinking budget.
"""

import os
import sys
import json
import time
import argparse
import threading
import requests
import concurrent.futures
from typing import Dict, Any, List, Optional, Tuple, Set


SYSTEM_INSTRUCTION = (
    'Select the most accurate morphological analysis ID for each ambiguous word in the sentence context. '
    'Return JSON: {"<index>": <id>}'
)


def load_env(filepath: str = ".env") -> None:
    if os.path.exists(filepath):
        with open(filepath, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    os.environ[k.strip()] = v.strip().strip("'").strip('"')


def format_lean_prompt(rec: Dict[str, Any]) -> str:
    lines = [f'"{rec["text"]}"']
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
    return "\n".join(lines)


def call_gemini(
    url: str,
    prompt: str,
    thinking_budget: int = 256,
    max_retries: int = 6
) -> Optional[Dict[str, int]]:
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
                sel_map = json.loads(text.strip())
                # Normalize keys to str
                return {str(k): int(v) for k, v in sel_map.items()}
            elif resp.status_code == 429:
                sleep_time = (2 ** attempt) * 2.0 + 1.0
                time.sleep(sleep_time)
            else:
                time.sleep(1.5 * (attempt + 1))
        except Exception:
            time.sleep(1.5 * (attempt + 1))
    return None


def process_dataset(
    input_path: str,
    output_path: str,
    api_key: str,
    model: str = "gemini-3.8-flash",
    thinking_budget: int = 256,
    concurrency: int = 12
) -> None:
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api_key}"

    # 1. Read existing done IDs for resume support
    done_ids: Set[str] = set()
    if os.path.exists(output_path):
        with open(output_path, "r", encoding="utf-8") as f:
            for line in f:
                try:
                    d = json.loads(line)
                    if "sentence_id" in d:
                        done_ids.add(d["sentence_id"])
                except Exception:
                    pass
        print(f"[RESUME] Found {len(done_ids)} already completed sentences in {output_path}")

    # 2. Read input records
    records_to_process = []
    with open(input_path, "r", encoding="utf-8") as f:
        for line in f:
            try:
                rec = json.loads(line)
                if rec["sentence_id"] not in done_ids:
                    records_to_process.append(rec)
            except Exception:
                pass

    total_needed = len(records_to_process)
    print(f"[START] Processing {total_needed} sentences with {concurrency} workers (model={model}, thinking_budget={thinking_budget})")

    if total_needed == 0:
        print("[DONE] All sentences already processed!")
        return

    out_file = open(output_path, "a" if done_ids else "w", encoding="utf-8")
    write_lock = threading.Lock()

    completed = 0
    fallback_count = 0
    start_time = time.time()

    def worker_task(rec: Dict[str, Any]) -> None:
        nonlocal completed, fallback_count
        amb_tokens = [t for t in rec.get("tokens", []) if t.get("is_ambiguous")]
        
        if not amb_tokens:
            with write_lock:
                out_file.write(json.dumps(rec, ensure_ascii=False) + "\n")
                out_file.flush()
                completed += 1
            return

        prompt = format_lean_prompt(rec)
        sel_map = call_gemini(url, prompt, thinking_budget=thinking_budget)

        for t in amb_tokens:
            idx_str = str(t["index"])
            valid_ids = [c["id"] for c in t.get("candidates", [])]
            chosen = sel_map.get(idx_str) if sel_map else None
            
            if chosen is not None and chosen in valid_ids:
                t["selected_candidate_id"] = chosen
                t["selection_method"] = "llm_disambiguated"
            else:
                fallback_count += 1
                # Fallback to candidate 0 (top baseline) if missing or invalid
                t["selected_candidate_id"] = valid_ids[0] if valid_ids else 0
                t["selection_method"] = "fallback_top"

        with write_lock:
            out_file.write(json.dumps(rec, ensure_ascii=False) + "\n")
            out_file.flush()
            completed += 1

            if completed % 100 == 0 or completed == total_needed:
                elapsed = max(0.1, time.time() - start_time)
                rate = completed / elapsed
                remaining = (total_needed - completed) / max(0.01, rate)
                print(f"[{completed:5d}/{total_needed:5d}] ({completed/total_needed*100:5.1f}%) | "
                      f"Speed: {rate:4.1f} sent/s | Remaining: {remaining/60:4.1f} min | Fallbacks: {fallback_count}")

    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [executor.submit(worker_task, rec) for rec in records_to_process]
        concurrent.futures.wait(futures)

    out_file.close()
    total_time = time.time() - start_time
    print(f"\n[FINISHED] Processed {total_needed} sentences in {total_time:.1f}s ({total_needed/max(0.1, total_time):.1f} sent/s).")
    print(f"[FINISHED] Output saved to {output_path}")


def main():
    load_env()
    load_env(".env.local")

    parser = argparse.ArgumentParser(description="High-throughput morphological disambiguation")
    parser.add_argument("-i", "--input", required=True, help="Input candidates JSONL file")
    parser.add_argument("-o", "--output", required=True, help="Output annotated JSONL file")
    parser.add_argument("-m", "--model", default="gemini-3.8-flash", help="Model name (default: gemini-3.8-flash)")
    parser.add_argument("-b", "--thinking-budget", type=int, default=256, help="Thinking budget (default: 256)")
    parser.add_argument("-c", "--concurrency", type=int, default=12, help="Concurrency level (default: 12)")

    args = parser.parse_args()

    api_key = os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY")
    if not api_key:
        print("[ERROR] GEMINI_API_KEY environment variable is not set.")
        sys.exit(1)

    process_dataset(
        input_path=args.input,
        output_path=args.output,
        api_key=api_key,
        model=args.model,
        thinking_budget=args.thinking_budget,
        concurrency=args.concurrency
    )


if __name__ == "__main__":
    main()

