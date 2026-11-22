#!/usr/bin/env python3
"""
Stage 5 Batch Disambiguation Engine using Google Gemini Batch API (batchGenerateContent).
- Processes harvested contrastive sentences (e.g. 8,000 sentences from Stage 4).
- Uses lean prompt representation with thinkingBudget=500 and 50% Batch API cost discount.
- Implements sliding window batch submission, registry tracking, checkpointing, and auto-resume.
"""

import os
import sys
import json
import time
import argparse
import requests
from typing import Dict, Any, List, Optional, Set, Tuple

SYSTEM_INSTRUCTION = (
    'Select the most accurate morphological analysis ID for each ambiguous word in the sentence context. '
    'Return only valid JSON mapping token index string to candidate ID integer: {"<index>": <id>}'
)

PROTECTED_FILES = [
    os.path.realpath("/home/dndara/data/turkish/distilled/distilled_10k_annotated.jsonl"),
    os.path.realpath("/home/dndara/data/turkish/distilled/distilled_20k_annotated.jsonl"),
    os.path.realpath("/home/dndara/data/turkish/distilled/distilled_batch2_10k_annotated.jsonl"),
    os.path.realpath("/home/dndara/data/turkish/distilled/distilled_batch3_10k_annotated.jsonl"),
    os.path.realpath("/home/dndara/data/turkish/distilled/golden_test_1000_annotated.jsonl"),
    os.path.realpath("/home/dndara/data/turkish/distilled/golden_test_1000_canonical.jsonl"),
    os.path.realpath("/home/dndara/data/turkish/distilled/golden_test_2500_annotated.jsonl"),
    os.path.realpath("/home/dndara/data/turkish/distilled/golden_test_2500_canonical.jsonl"),
    os.path.realpath("data/ambiguity/train_combined_30k.jsonl"),
    os.path.realpath("data/ambiguity/train_combined_33k.jsonl"),
    os.path.realpath("data/ambiguity/train_combined_33k_th20k.jsonl"),
]


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


def build_batch_request(
    records: List[Dict[str, Any]],
    batch_display_name: str,
    thinking_budget: int = 500
) -> Dict[str, Any]:
    requests_list = []
    for r in records:
        prompt = format_lean_prompt(r)
        req_item = {
            "request": {
                "systemInstruction": {"parts": [{"text": SYSTEM_INSTRUCTION}]},
                "contents": [{"parts": [{"text": prompt}]}],
                "generationConfig": {
                    "temperature": 0.0,
                    "responseMimeType": "application/json",
                    "thinkingConfig": {"thinkingBudget": thinking_budget}
                }
            }
        }
        requests_list.append(req_item)

    return {
        "batch": {
            "displayName": batch_display_name,
            "inputConfig": {
                "requests": {
                    "requests": requests_list
                }
            }
        }
    }


def submit_batch(
    api_key: str,
    payload: Dict[str, Any],
    model: str = "gemini-3.8-flash",
    max_retries: int = 5
) -> Dict[str, Any]:
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:batchGenerateContent?key={api_key}"
    for attempt in range(max_retries):
        try:
            resp = requests.post(url, json=payload, timeout=60)
            if resp.status_code == 200:
                return resp.json()
            elif resp.status_code == 429:
                wait_time = (2 ** attempt) * 2.0 + 1.0
                print(f"    [Rate limit 429] Backing off for {wait_time:.1f}s...")
                time.sleep(wait_time)
            else:
                raise RuntimeError(f"Failed to submit batch (HTTP {resp.status_code}): {resp.text}")
        except requests.exceptions.RequestException as e:
            if attempt == max_retries - 1:
                raise
            time.sleep(2 ** attempt)
    raise RuntimeError("Max retries exceeded when submitting batch.")


def get_batch_status(
    api_key: str,
    batch_name: str,
    max_retries: int = 3
) -> Dict[str, Any]:
    url = f"https://generativelanguage.googleapis.com/v1beta/{batch_name}?key={api_key}"
    for attempt in range(max_retries):
        try:
            resp = requests.get(url, timeout=30)
            if resp.status_code == 200:
                return resp.json()
            elif resp.status_code == 429:
                time.sleep((2 ** attempt) * 2.0)
            else:
                raise RuntimeError(f"Failed to get batch status for {batch_name} (HTTP {resp.status_code}): {resp.text}")
        except requests.exceptions.RequestException:
            if attempt == max_retries - 1:
                raise
            time.sleep(2 ** attempt)
    raise RuntimeError(f"Max retries exceeded getting status for {batch_name}")


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
        data = json.loads(cleaned)
        return {str(k): int(v) for k, v in data.items()}
    except Exception:
        return None


def load_registry(registry_path: str) -> Dict[str, Any]:
    if os.path.exists(registry_path):
        try:
            with open(registry_path, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return {"batches": [], "completed_sentence_ids": []}


def save_registry(registry_path: str, data: Dict[str, Any]):
    os.makedirs(os.path.dirname(os.path.abspath(registry_path)), exist_ok=True)
    temp_path = registry_path + ".tmp"
    with open(temp_path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
    os.replace(temp_path, registry_path)


def main():
    load_env()
    load_env(".env.local")

    parser = argparse.ArgumentParser(description="Stage 5 Gemini Batch API Disambiguator")
    parser.add_argument("-i", "--input", default="data/ambiguity/stage4_harvested_candidates_8k.jsonl", help="Input candidates JSONL")
    parser.add_argument("-o", "--output", default="data/ambiguity/stage5_annotated_sentences_8k.jsonl", help="Output annotated JSONL")
    parser.add_argument("-r", "--registry", default="data/ambiguity/stage5_batch_registry_8k.json", help="Batch tracking registry")
    parser.add_argument("-s", "--summary", default="data/ambiguity/stage5_annotated_summary_8k.json", help="Summary report")
    parser.add_argument("-m", "--model", default="gemini-3.8-flash", help="Gemini model")
    parser.add_argument("-b", "--batch-size", type=int, default=500, help="Sentences per batch (default: 500)")
    parser.add_argument("-l", "--limit", type=int, default=0, help="Limit total sentences (0 = all)")
    parser.add_argument("--max-active-batches", type=int, default=5, help="Maximum concurrent active batches in flight")
    parser.add_argument("--thinking-budget", type=int, default=500, help="Thinking budget (default: 500)")
    parser.add_argument("--poll-interval", type=int, default=15, help="Seconds between poll cycles")
    args = parser.parse_args()

    # Strict Overwrite & Safety Protection
    target_out_path = os.path.realpath(args.output)
    target_in_path = os.path.realpath(args.input)
    if target_out_path == target_in_path:
        raise ValueError(f"FATAL: Output file matches input file ({args.output}). Overwriting input is forbidden.")
    if target_out_path in PROTECTED_FILES:
        raise ValueError(f"FATAL: Output file ({args.output}) is a protected golden/training dataset. Overwrite is blocked.")

    api_key = (os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY") or "").strip().strip("'").strip('"')
    if not api_key:
        print("[ERROR] GEMINI_API_KEY environment variable is not set.", file=sys.stderr)
        sys.exit(1)

    print(f"Loading input dataset: {args.input}")
    records = []
    with open(args.input, "r", encoding="utf-8") as f:
        for idx, line in enumerate(f):
            if args.limit > 0 and idx >= args.limit:
                break
            line = line.strip()
            if line:
                records.append(json.loads(line))

    total_records = len(records)
    print(f"Total sentences in scope: {total_records}")

    registry = load_registry(args.registry)
    completed_ids = set(registry.get("completed_sentence_ids", []))
    print(f"Already completed sentences in registry: {len(completed_ids)}")

    # Check which sentence IDs are currently in flight in active batches
    enqueued_ids = set(completed_ids)
    for b in registry.get("batches", []):
        if b.get("state") in ["BATCH_STATE_PENDING", "BATCH_STATE_RUNNING"]:
            for sid in b.get("sentence_ids", []):
                enqueued_ids.add(sid)

    # Group un-enqueued records into chunks
    unsubmitted_chunks = []
    current_chunk = []
    chunk_start_idx = 0

    for idx, r in enumerate(records):
        sid = r["sentence_id"]
        if sid in enqueued_ids:
            continue
        if not current_chunk:
            chunk_start_idx = idx
        current_chunk.append(r)
        if len(current_chunk) >= args.batch_size:
            unsubmitted_chunks.append((chunk_start_idx, current_chunk))
            current_chunk = []

    if current_chunk:
        unsubmitted_chunks.append((chunk_start_idx, current_chunk))

    print(f"Unsubmitted chunks remaining: {len(unsubmitted_chunks)} ({sum(len(c[1]) for c in unsubmitted_chunks)} sentences)")

    os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
    completed_out_records = {}
    if os.path.exists(args.output):
        with open(args.output, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    item = json.loads(line)
                    completed_out_records[item["sentence_id"]] = item

    record_lookup = {r["sentence_id"]: r for r in records}
    start_time = time.time()

    # Main Orchestration Loop (Sliding Window Submission + Polling)
    print("\nStarting batch execution loop...")
    chunk_ptr = 0

    while True:
        # 1. Identify active batches
        active_batches = [
            b for b in registry.get("batches", [])
            if b.get("state") not in ["BATCH_STATE_SUCCEEDED", "BATCH_STATE_FAILED", "BATCH_STATE_CANCELLED"]
        ]

        # 2. Fill active window with unsubmitted chunks
        while len(active_batches) < args.max_active_batches and chunk_ptr < len(unsubmitted_chunks):
            start_idx, chunk_records = unsubmitted_chunks[chunk_ptr]
            chunk_ptr += 1
            batch_name_display = f"stage5_batch_{start_idx}_{start_idx + len(chunk_records) - 1}_{int(time.time())}"
            print(f"  Submitting new batch: {batch_name_display} ({len(chunk_records)} sentences)...")
            try:
                payload = build_batch_request(chunk_records, batch_name_display, args.thinking_budget)
                submit_resp = submit_batch(api_key, payload, args.model)
                batch_id = submit_resp.get("name")
                print(f"    -> Successfully enqueued as {batch_id}")

                b_entry = {
                    "name": batch_id,
                    "displayName": batch_name_display,
                    "start_idx": start_idx,
                    "count": len(chunk_records),
                    "sentence_ids": [r["sentence_id"] for r in chunk_records],
                    "state": "BATCH_STATE_PENDING",
                    "create_time": time.time()
                }
                registry["batches"].append(b_entry)
                active_batches.append(b_entry)
                save_registry(args.registry, registry)
                time.sleep(1.0)
            except Exception as e:
                print(f"    [WARN] Error submitting chunk: {e}")
                chunk_ptr -= 1  # retry on next iteration
                break

        # 3. Poll active batches
        for b_entry in active_batches:
            batch_name = b_entry["name"]
            try:
                status_data = get_batch_status(api_key, batch_name)
                state = status_data.get("metadata", {}).get("state", "UNKNOWN")
                b_entry["state"] = state
                stats = status_data.get("metadata", {}).get("batchStats", {})
                print(f"  [{time.strftime('%X')}] {batch_name} ({b_entry.get('displayName')}): state={state} stats={stats}")

                if state == "BATCH_STATE_SUCCEEDED":
                    print(f"    -> Processing results for {batch_name}...")
                    inlined = status_data.get("response", {}).get("inlinedResponses", {}).get("inlinedResponses", [])
                    s_ids = b_entry["sentence_ids"]

                    for idx, s_id in enumerate(s_ids):
                        if idx >= len(inlined):
                            break
                        item_obj = inlined[idx]
                        resp_obj = item_obj.get("response", {})
                        cands = resp_obj.get("candidates", [{}])
                        gen_text = ""
                        if cands:
                            parts = cands[0].get("content", {}).get("parts", [{}])
                            if parts:
                                gen_text = parts[0].get("text", "")

                        parsed_selection = parse_json(gen_text)
                        orig_rec = record_lookup.get(s_id)
                        if not orig_rec:
                            continue

                        annotated_rec = json.loads(json.dumps(orig_rec))
                        usage_meta = resp_obj.get("usageMetadata")
                        if usage_meta:
                            annotated_rec["llm_usage"] = usage_meta

                        # Annotate ambiguous tokens
                        for t in annotated_rec.get("tokens", []):
                            if t.get("is_ambiguous", False):
                                t_idx_str = str(t["index"])
                                valid_ids = [c["id"] for c in t.get("candidates", [])]
                                chosen_id = parsed_selection.get(t_idx_str) if parsed_selection else None

                                if chosen_id is not None and chosen_id in valid_ids:
                                    t["selected_candidate_id"] = chosen_id
                                    t["selection_method"] = "llm_batch_thinking500"
                                else:
                                    # Fallback to top candidate if missing or invalid
                                    t["selected_candidate_id"] = valid_ids[0] if valid_ids else 0
                                    t["selection_method"] = "fallback_top"

                        completed_out_records[s_id] = annotated_rec
                        if s_id not in registry["completed_sentence_ids"]:
                            registry["completed_sentence_ids"].append(s_id)

                    save_registry(args.registry, registry)

                    # Flush completed records to output JSONL atomically preserving canonical order
                    temp_output = args.output + ".tmp"
                    with open(temp_output, "w", encoding="utf-8") as out_f:
                        for sid_obj in records:
                            s = sid_obj["sentence_id"]
                            if s in completed_out_records:
                                out_f.write(json.dumps(completed_out_records[s], ensure_ascii=False) + "\n")
                    os.replace(temp_output, args.output)
                    print(f"    -> Progress: {len(completed_out_records)}/{total_records} sentences saved to {args.output}")

                elif state in ["BATCH_STATE_FAILED", "BATCH_STATE_CANCELLED"]:
                    print(f"    [WARN] Batch {batch_name} ended with state: {state}")
                    save_registry(args.registry, registry)

            except Exception as e:
                print(f"    [WARN] Error polling {batch_name}: {e}")

        # 4. Check termination condition
        active_remaining = [
            b for b in registry.get("batches", [])
            if b.get("state") not in ["BATCH_STATE_SUCCEEDED", "BATCH_STATE_FAILED", "BATCH_STATE_CANCELLED"]
        ]
        if not active_remaining and chunk_ptr >= len(unsubmitted_chunks):
            print("\nAll batches have completed!")
            break

        time.sleep(args.poll_interval)

    # 5. Compute Comprehensive Summary
    print("\nComputing Stage 5 annotation summary...")
    total_ambig = 0
    llm_disambiguated = 0
    fallback_count = 0
    pattern_breakdown = {}

    for r in records:
        s_id = r["sentence_id"]
        rec = completed_out_records.get(s_id)
        if not rec:
            continue
        pat = rec.get("pattern_id") or rec.get("primary_pattern") or "UNKNOWN"
        if pat not in pattern_breakdown:
            pattern_breakdown[pat] = {"sentences": 0, "ambiguous_tokens": 0, "llm_disambiguated": 0, "fallback": 0}
        pattern_breakdown[pat]["sentences"] += 1

        for t in rec.get("tokens", []):
            if not t.get("is_ambiguous", False):
                continue
            total_ambig += 1
            pattern_breakdown[pat]["ambiguous_tokens"] += 1
            if t.get("selection_method") == "llm_batch_thinking500":
                llm_disambiguated += 1
                pattern_breakdown[pat]["llm_disambiguated"] += 1
            else:
                fallback_count += 1
                pattern_breakdown[pat]["fallback"] += 1

    elapsed_time = time.time() - start_time
    total_prompt_tokens = sum(r.get("llm_usage", {}).get("promptTokenCount", 0) for r in completed_out_records.values())
    total_candidates_tokens = sum(r.get("llm_usage", {}).get("candidatesTokenCount", 0) for r in completed_out_records.values())
    # Gemini 3.8 Flash Batch API pricing: $0.075 / 1M prompt tokens, $0.30 / 1M output tokens (50% off standard)
    est_cost_usd = round((total_prompt_tokens * 0.000000075) + (total_candidates_tokens * 0.0000003), 4)

    summary = {
        "model": args.model,
        "thinking_budget": args.thinking_budget,
        "batch_mode": True,
        "total_annotated_sentences": len(completed_out_records),
        "total_ambiguous_tokens": total_ambig,
        "llm_disambiguated_tokens": llm_disambiguated,
        "fallback_tokens": fallback_count,
        "llm_success_rate": round(llm_disambiguated / max(1, total_ambig) * 100.0, 2),
        "total_prompt_tokens": total_prompt_tokens,
        "total_candidates_tokens": total_candidates_tokens,
        "estimated_cost_usd": est_cost_usd,
        "total_batches": len(registry.get("batches", [])),
        "elapsed_seconds": round(elapsed_time, 1),
        "pattern_breakdown": pattern_breakdown
    }

    with open(args.summary, "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2, ensure_ascii=False)

    print(f"\n==========================================")
    print(f" Stage 5 Batch Disambiguation Complete")
    print(f"==========================================")
    print(f"  Total Sentences: {summary['total_annotated_sentences']}")
    print(f"  Total Ambiguous Tokens: {total_ambig}")
    print(f"  LLM Disambiguated: {llm_disambiguated} ({summary['llm_success_rate']}%)")
    print(f"  Fallbacks: {fallback_count}")
    print(f"  Prompt Tokens: {total_prompt_tokens:,} | Output Tokens: {total_candidates_tokens:,}")
    print(f"  Estimated Cost: ${est_cost_usd:.4f}")
    print(f"  Summary saved to: {args.summary}")
    print(f"  Annotated dataset saved to: {args.output}")


if __name__ == "__main__":
    main()
