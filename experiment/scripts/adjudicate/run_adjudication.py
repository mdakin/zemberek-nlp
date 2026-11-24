#!/usr/bin/env python3
"""
run_adjudication.py

Runs double-blind linguistic adjudication comparing Pristine Gold CoNLL-U trees
against Zemberek model predictions using Gemini 3.8 Flash.

Supports:
1. Gemini Batch Mode (default): Submits batches to Google Gemini Batch API
   (batchGenerateContent), polling asynchronously with 50% discount and high throughput.
2. Realtime Streaming Mode (--mode realtime): Concurrent streaming workers for quick dry-runs.
3. Configurable thinking budget (default: 1024 tokens).
4. Pure JSON mode (responseMimeType="application/json") for minimal parsing.
5. Deterministic truth table derivation and secret double-blind un-shuffling.
"""

import os
import sys
import time
import json
import argparse
import threading
import requests
from pathlib import Path
from typing import Dict, Any, List, Optional
import concurrent.futures

SYSTEM_INSTRUCTION = (
    "You are an expert Turkish computational linguist independently adjudicating morphological analyses in sentence context.\n"
    "For each target token in the given Turkish sentence, independently evaluate Candidate 1 and Candidate 2.\n"
    "Note: Candidates may use different representation formats (e.g. feature attribute sets or morpheme derivation analyses); evaluate the underlying linguistic correctness regardless of formatting style.\n\n"
    "Evaluation values:\n"
    "- 'CORRECT': accurate POS, lemma, and inflectional morphology in this sentence context.\n"
    "- 'VALID_ALT': linguistically acceptable alternative analysis in context.\n"
    "- 'FLAWED': incorrect POS, wrong lemma, wrong morphemes, or ungrammatical in context.\n\n"
    "Output strictly JSON:\n"
    "{\n"
    "  \"<token_index>\": {\n"
    "    \"eval_1\": \"CORRECT\" | \"FLAWED\" | \"VALID_ALT\",\n"
    "    \"eval_2\": \"CORRECT\" | \"FLAWED\" | \"VALID_ALT\",\n"
    "    \"reason\": \"<under 15 words>\"\n"
    "  }\n"
    "}"
)


def format_prompt(item: Dict[str, Any]) -> str:
    lines = [
        f'Sentence: "{item["text"]}"\n',
        "Tokens to evaluate (candidates may use different representation formats):"
    ]
    for t in item["tokens"]:
        lines.append(f'\n[Token {t["index"]}] "{t["surface"]}"')
        lines.append(f'  Candidate 1: {t["cand1"]}')
        lines.append(f'  Candidate 2: {t["cand2"]}')

    lines.append(
        '\nEvaluate each candidate in this sentence context as: CORRECT, FLAWED, or VALID_ALT.\n'
        'Output strictly JSON conforming to the requested schema.'
    )
    return "\n".join(lines)


def parse_judgments(raw_text: Optional[str]) -> Dict[str, Any]:
    if not raw_text:
        return {}
    text = raw_text.strip().removeprefix("```json").removeprefix("```").removesuffix("```").strip()
    try:
        data = json.loads(text)
        if isinstance(data, dict):
            if "judgments" in data and isinstance(data["judgments"], dict):
                return data["judgments"]
            if "tokens" in data and isinstance(data["tokens"], dict):
                return data["tokens"]
            return data
    except Exception:
        pass
    return {}


def derive_outcome(gold_eval: str, model_eval: str) -> str:
    """Computes programmatic adjudication outcome without LLM bias."""
    g = gold_eval.upper()
    m = model_eval.upper()
    if g == "CORRECT" and m == "CORRECT":
        return "BOTH_VALID"
    elif g == "CORRECT" and m == "FLAWED":
        return "GOLD_WINS"
    elif g == "FLAWED" and m == "CORRECT":
        return "MODEL_WINS"
    elif g == "FLAWED" and m == "FLAWED":
        return "NEITHER"
    elif g == "VALID_ALT" and m == "CORRECT":
        return "MODEL_PREFERRED"
    elif g == "CORRECT" and m == "VALID_ALT":
        return "GOLD_PREFERRED"
    elif g == "VALID_ALT" and m == "VALID_ALT":
        return "BOTH_VALID"
    else:
        return "TIE"


def process_token_judgments(
    item: Dict[str, Any],
    judgments: Dict[str, Any]
) -> Dict[str, Any]:
    valid_evals = {"CORRECT", "FLAWED", "VALID_ALT"}
    parsed_record = {
        "sentence_id": item["sentence_id"],
        "treebank": item["treebank"],
        "text": item["text"],
        "is_control": item.get("is_control", False),
        "consensus_tokens": item.get("consensus_tokens", 0),
        "tokens": []
    }

    for t in item["tokens"]:
        t_idx_str = str(t["index"])
        j = judgments.get(t_idx_str, {})
        e1 = str(j.get("eval_1", "CORRECT")).upper().strip()
        e2 = str(j.get("eval_2", "CORRECT")).upper().strip()
        if e1 not in valid_evals:
            e1 = "CORRECT"
        if e2 not in valid_evals:
            e2 = "CORRECT"
        reason = str(j.get("reason", "")).strip()

        # Unshuffle: determine gold vs model evaluation based on secret key
        gold_cand_id = t.get("gold_cand_id", 1)
        if gold_cand_id == 1:
            gold_eval = e1
            model_eval = e2
        else:
            gold_eval = e2
            model_eval = e1

        outcome = derive_outcome(gold_eval, model_eval)

        parsed_record["tokens"].append({
            "index": t["index"],
            "surface": t["surface"],
            "gold_cand_id": gold_cand_id,
            "eval_1": e1,
            "eval_2": e2,
            "gold_eval": gold_eval,
            "model_eval": model_eval,
            "outcome": outcome,
            "reason": reason,
            "gold_conllu": t.get("gold_conllu", ""),
            "model_conllu": t.get("model_conllu", "")
        })

    return parsed_record


# --- GEMINI BATCH API ENGINE ---

def build_batch_request(
    records: List[Dict[str, Any]],
    batch_display_name: str,
    thinking_budget: int = 1024
) -> Dict[str, Any]:
    requests_list = []
    for r in records:
        prompt = format_prompt(r)
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


def run_batch_pipeline(
    items: List[Dict[str, Any]],
    api_key: str,
    model: str,
    batch_size: int,
    thinking_budget: int,
    max_active_batches: int,
    poll_interval: int,
    logs_dir: Path,
    raw_log_path: Path,
    parsed_log_path: Path
):
    registry_path = logs_dir / "batch_registry.json"
    registry = {"batches": [], "completed_keys": []}
    if registry_path.exists():
        try:
            with open(registry_path, "r", encoding="utf-8") as f:
                registry = json.load(f)
        except Exception:
            pass

    completed_keys = set(tuple(k) for k in registry.get("completed_keys", []))
    pending_items = [it for it in items if (it["treebank"], it["sentence_id"]) not in completed_keys]

    # Chunk pending items
    chunks = [pending_items[i:i + batch_size] for i in range(0, len(pending_items), batch_size)]
    chunk_idx = 0

    print(f"Batch Pipeline Initialized:")
    print(f"  Total pending sentences: {len(pending_items)}")
    print(f"  Batch size: {batch_size} (Total chunks: {len(chunks)})")
    print(f"  Thinking Budget: {thinking_budget}")
    print(f"  Max active batches: {max_active_batches}")

    while chunk_idx < len(chunks) or any(b["state"] in ["BATCH_STATE_RUNNING", "BATCH_STATE_PENDING"] for b in registry["batches"]):
        # 1. Enqueue new batches if capacity permits
        active_batches = [b for b in registry["batches"] if b["state"] in ["BATCH_STATE_RUNNING", "BATCH_STATE_PENDING"]]
        while len(active_batches) < max_active_batches and chunk_idx < len(chunks):
            chunk = chunks[chunk_idx]
            display_name = f"adjudicate_chunk_{chunk_idx}_{int(time.time())}"
            print(f"  Submitting batch: {display_name} ({len(chunk)} sentences)...")
            payload = build_batch_request(chunk, display_name, thinking_budget)
            submit_data = submit_batch(api_key, payload, model)
            batch_id = submit_data.get("name")
            print(f"    -> Enqueued as {batch_id}")

            batch_entry = {
                "name": batch_id,
                "displayName": display_name,
                "state": "BATCH_STATE_PENDING",
                "sentence_keys": [[r["treebank"], r["sentence_id"]] for r in chunk],
                "submitted_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "records": chunk
            }
            registry["batches"].append(batch_entry)
            active_batches.append(batch_entry)
            chunk_idx += 1

            with open(registry_path, "w", encoding="utf-8") as rf:
                json.dump(registry, rf, indent=2)

        # 2. Poll active batches
        for b_entry in active_batches:
            b_name = b_entry["name"]
            try:
                status_data = get_batch_status(api_key, b_name)
                state = status_data.get("metadata", {}).get("state", "UNKNOWN")
                b_entry["state"] = state
                stats = status_data.get("metadata", {}).get("batchStats", {})
                print(f"  [{time.strftime('%X')}] {b_name}: {state} {stats}")

                if state == "BATCH_STATE_SUCCEEDED":
                    print(f"    Processing succeeded batch: {b_name}...")
                    inlined = status_data.get("response", {}).get("inlinedResponses", {}).get("inlinedResponses", [])
                    chunk_records = b_entry.get("records", [])

                    for rec, item_obj in zip(chunk_records, inlined):
                        resp_obj = item_obj.get("response", {})
                        cands = resp_obj.get("candidates", [{}])
                        gen_text = ""
                        if cands:
                            parts = cands[0].get("content", {}).get("parts", [])
                            text_parts = [p.get("text", "") for p in parts if "text" in p and not p.get("thought", False)]
                            gen_text = "".join(text_parts).strip()

                        # Raw log
                        raw_rec = {
                            "sentence_id": rec["sentence_id"],
                            "treebank": rec["treebank"],
                            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                            "batch_id": b_name,
                            "prompt": format_prompt(rec),
                            "raw_response": gen_text
                        }
                        with open(raw_log_path, "a", encoding="utf-8") as rlf:
                            rlf.write(json.dumps(raw_rec, ensure_ascii=False) + "\n")

                        # Parsed log
                        judgments = parse_judgments(gen_text)
                        parsed_rec = process_token_judgments(rec, judgments)
                        with open(parsed_log_path, "a", encoding="utf-8") as plf:
                            plf.write(json.dumps(parsed_rec, ensure_ascii=False) + "\n")

                        key = [rec["treebank"], rec["sentence_id"]]
                        if key not in registry["completed_keys"]:
                            registry["completed_keys"].append(key)

                    print(f"    Successfully parsed and recorded {len(chunk_records)} sentences from {b_name}.")
                    with open(registry_path, "w", encoding="utf-8") as rf:
                        json.dump(registry, rf, indent=2)

                elif state in ["BATCH_STATE_FAILED", "BATCH_STATE_CANCELLED"]:
                    print(f"    ERROR: Batch {b_name} ended with state: {state}")
                    with open(registry_path, "w", encoding="utf-8") as rf:
                        json.dump(registry, rf, indent=2)

            except Exception as e:
                print(f"    Polling error for {b_name}: {e}")

        has_pending = (chunk_idx < len(chunks)) or any(
            b["state"] in ["BATCH_STATE_RUNNING", "BATCH_STATE_PENDING"]
            for b in registry["batches"]
        )
        if has_pending:
            time.sleep(poll_interval)

    print("\nAll Gemini Batch jobs completed successfully!")


# --- REALTIME STREAMING ENGINE ---

def call_gemini_realtime(api_key: str, model: str, prompt: str, thinking_budget: int = 1024, max_retries: int = 6) -> Dict[str, Any]:
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

    t0 = time.time()
    for attempt in range(max_retries):
        try:
            resp = requests.post(url, json=payload, timeout=60)
            latency_ms = int((time.time() - t0) * 1000)
            if resp.status_code == 200:
                data = resp.json()
                parts = data.get("candidates", [{}])[0].get("content", {}).get("parts", [])
                text_parts = [p.get("text", "") for p in parts if "text" in p and not p.get("thought", False)]
                text = "".join(text_parts).strip()
                return {
                    "status": "success",
                    "raw_response": text,
                    "latency_ms": latency_ms,
                    "error": None
                }
            elif resp.status_code == 429:
                time.sleep((2 ** attempt) * 2.0 + 1.0)
            else:
                time.sleep(1.0 * (attempt + 1))
        except Exception:
            time.sleep(1.5 * (attempt + 1))

    latency_ms = int((time.time() - t0) * 1000)
    return {
        "status": "failed",
        "raw_response": "",
        "latency_ms": latency_ms,
        "error": "Max retries exceeded or connection failure"
    }


def run_realtime_pipeline(
    items: List[Dict[str, Any]],
    api_key: str,
    model: str,
    concurrency: int,
    thinking_budget: int,
    raw_log_path: Path,
    parsed_log_path: Path
):
    raw_file_lock = threading.Lock()
    parsed_file_lock = threading.Lock()
    counter_lock = threading.Lock()
    completed_count = 0
    start_time = time.time()

    def process_item(item: Dict[str, Any]) -> bool:
        nonlocal completed_count
        prompt = format_prompt(item)
        res = call_gemini_realtime(api_key, model, prompt, thinking_budget)

        raw_record = {
            "sentence_id": item["sentence_id"],
            "treebank": item["treebank"],
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "latency_ms": res["latency_ms"],
            "status": res["status"],
            "prompt": prompt,
            "raw_response": res["raw_response"],
            "error": res["error"]
        }
        with raw_file_lock:
            with open(raw_log_path, "a", encoding="utf-8") as rf:
                rf.write(json.dumps(raw_record, ensure_ascii=False) + "\n")

        if res["status"] != "success":
            return False

        judgments = parse_judgments(res["raw_response"])
        parsed_record = process_token_judgments(item, judgments)

        with parsed_file_lock:
            with open(parsed_log_path, "a", encoding="utf-8") as pf:
                pf.write(json.dumps(parsed_record, ensure_ascii=False) + "\n")

        with counter_lock:
            completed_count += 1
            if completed_count % 25 == 0 or completed_count == len(items):
                elapsed = time.time() - start_time
                sps = completed_count / elapsed if elapsed > 0 else 0
                rem_s = (len(items) - completed_count) / sps if sps > 0 else 0
                print(f"Progress: {completed_count}/{len(items)} sentences ({completed_count/len(items)*100:.1f}%) | "
                      f"Speed: {sps:.2f} sent/s | Est. Remaining: {rem_s:.1f}s")

        return True

    print(f"Launching Realtime ThreadPool with {concurrency} workers (thinking budget: {thinking_budget})...")
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [executor.submit(process_item, item) for item in items]
        concurrent.futures.wait(futures)

    total_time = time.time() - start_time
    print(f"\nRealtime batch complete in {total_time:.2f}s ({completed_count} sentences processed).")


# --- MAIN CLI ---

def main():
    script_dir = Path(__file__).resolve().parent
    default_dataset = script_dir / "data" / "adjudication_dataset_pairwise.jsonl"
    default_logs_dir = script_dir / "logs"

    parser = argparse.ArgumentParser(description="Run double-blind linguistic adjudication via Gemini 3.8 Flash.")
    parser.add_argument("--mode", type=str, default="batch", choices=["batch", "realtime"],
                        help="Execution mode: 'batch' (Gemini Batch API, default) or 'realtime' (ThreadPool)")
    parser.add_argument("--dataset", type=Path, default=default_dataset,
                        help="Path to pairwise double-blind dataset JSONL")
    parser.add_argument("--model", type=str, default="gemini-3.8-flash",
                        help="Gemini model name (default: gemini-3.8-flash)")
    parser.add_argument("--thinking-budget", type=int, default=1024,
                        help="Thinking budget in tokens (default: 1024)")
    parser.add_argument("--batch-size", type=int, default=500,
                        help="Sentences per batch in batch mode (default: 500)")
    parser.add_argument("--max-active-batches", type=int, default=4,
                        help="Max concurrent active batches in batch mode (default: 4)")
    parser.add_argument("--poll-interval", type=int, default=15,
                        help="Polling interval in seconds for batch mode (default: 15)")
    parser.add_argument("--concurrency", type=int, default=10,
                        help="Concurrent workers for realtime mode (default: 10)")
    parser.add_argument("--api-key", type=str, default=None,
                        help="Gemini API key (defaults to GEMINI_API_KEY environment variable)")
    parser.add_argument("--logs-dir", type=Path, default=default_logs_dir,
                        help="Output directory for logs")
    parser.add_argument("--limit", type=int, default=0,
                        help="Limit total number of sentences for dry-run testing (0 = full)")
    parser.add_argument("--limit-per-treebank", type=int, default=0,
                        help="Limit number of sentences per treebank (e.g. 100 BOUN + 100 IMST)")
    parser.add_argument("--num-disputed", type=int, default=0,
                        help="Number of disputed sentences to adjudicate (split evenly across treebanks)")
    parser.add_argument("--num-controls", type=int, default=0,
                        help="Number of agreed control sentences to adjudicate (split evenly across treebanks)")
    args = parser.parse_args()

    api_key = args.api_key or os.environ.get("GEMINI_API_KEY")
    if not api_key:
        print("ERROR: GEMINI_API_KEY missing! Set via --api-key or environment variable.", file=sys.stderr)
        sys.exit(1)

    args.logs_dir.mkdir(parents=True, exist_ok=True)
    raw_log_path = args.logs_dir / "raw_requests_responses.jsonl"
    parsed_log_path = args.logs_dir / "adjudications_parsed.jsonl"

    if not args.dataset.exists():
        print(f"ERROR: Dataset file not found: {args.dataset}", file=sys.stderr)
        sys.exit(1)

    # Resume support: read completed keys from parsed log
    completed_keys = set()
    if parsed_log_path.exists():
        with open(parsed_log_path, "r", encoding="utf-8") as f:
            for line in f:
                try:
                    obj = json.loads(line)
                    completed_keys.add((obj["treebank"], obj["sentence_id"]))
                except Exception:
                    pass
        if completed_keys:
            print(f"Resuming run: found {len(completed_keys)} already adjudicated sentences.")

    items = []
    with open(args.dataset, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            if (obj["treebank"], obj["sentence_id"]) not in completed_keys:
                items.append(obj)

    if args.num_disputed > 0 or args.num_controls > 0:
        boun_disp = [it for it in items if it["treebank"] == "BOUN" and not it.get("is_control", False)]
        imst_disp = [it for it in items if it["treebank"] == "IMST" and not it.get("is_control", False)]
        boun_ctrl = [it for it in items if it["treebank"] == "BOUN" and it.get("is_control", False)]
        imst_ctrl = [it for it in items if it["treebank"] == "IMST" and it.get("is_control", False)]

        selected = []
        if args.num_disputed > 0:
            half_disp = args.num_disputed // 2
            selected.extend(boun_disp[:half_disp])
            selected.extend(imst_disp[:(args.num_disputed - half_disp)])
        elif args.limit_per_treebank > 0:
            selected.extend(boun_disp[:args.limit_per_treebank])
            selected.extend(imst_disp[:args.limit_per_treebank])

        if args.num_controls > 0:
            half_ctrl = args.num_controls // 2
            selected.extend(boun_ctrl[:half_ctrl])
            selected.extend(imst_ctrl[:(args.num_controls - half_ctrl)])

        items = selected
    elif args.limit_per_treebank > 0:
        boun_items = [it for it in items if it["treebank"] == "BOUN"][:args.limit_per_treebank]
        imst_items = [it for it in items if it["treebank"] == "IMST"][:args.limit_per_treebank]
        items = boun_items + imst_items
    elif args.limit > 0:
        items = items[:args.limit]

    print(f"Adjudicator Model: {args.model}")
    print(f"Execution Mode: {args.mode.upper()}")
    print(f"Thinking Budget: {args.thinking_budget} tokens")
    print(f"Total sentences to adjudicate: {len(items)} "
          f"(Disputed: {sum(1 for it in items if not it.get('is_control', False))}, "
          f"Control: {sum(1 for it in items if it.get('is_control', False))})")

    if not items:
        print("All sentences already completed!")
        return

    if args.mode == "batch":
        run_batch_pipeline(
            items=items,
            api_key=api_key,
            model=args.model,
            batch_size=args.batch_size,
            thinking_budget=args.thinking_budget,
            max_active_batches=args.max_active_batches,
            poll_interval=args.poll_interval,
            logs_dir=args.logs_dir,
            raw_log_path=raw_log_path,
            parsed_log_path=parsed_log_path
        )
    else:
        run_realtime_pipeline(
            items=items,
            api_key=api_key,
            model=args.model,
            concurrency=args.concurrency,
            thinking_budget=args.thinking_budget,
            raw_log_path=raw_log_path,
            parsed_log_path=parsed_log_path
        )


if __name__ == "__main__":
    main()
