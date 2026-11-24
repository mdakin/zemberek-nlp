#!/usr/bin/env python3
"""
induce_llm_taxonomy.py - Stage 3: LLM Syntactic Rule Induction & Corpus Harvesting Filters

Processes the 36 Canonical Master Pattern Bundles from Stage 2 and uses the Gemini Batch API
(batchGenerateContent) to induce:
  1. Formal Syntactic Disambiguation Rules (failure analysis, discriminator axioms, syntactic anchors).
  2. Machine-Executable Corpus Harvesting Filters (token specs, regexes, contrastive positive/counter arms, quotas).

Outputs:
  - data/ambiguity/stage3_batch_registry.json
  - data/ambiguity/stage3_induced_rules_and_filters.json
  - data/ambiguity/stage3_induced_rules_and_filters_summary.json
"""

import os
import sys
import json
import time
import re
import argparse
import requests
from typing import Dict, Any, List, Optional
from collections import Counter

INPUT_PATTERNS_JSON = "data/ambiguity/stage2_canonical_patterns.json"
BENCHMARK_ERROR_SENTS = "data/ambiguity/golden_unresolved_error_sentences.jsonl"
OUT_RULES_JSON = "data/ambiguity/stage3_induced_rules_and_filters.json"
OUT_SUMMARY_JSON = "data/ambiguity/stage3_induced_rules_and_filters_summary.json"
DEFAULT_REGISTRY_JSON = "data/ambiguity/stage3_batch_registry.json"

TOTAL_HARVESTING_BUDGET = 20000

SYSTEM_INSTRUCTION = (
    "You are a distinguished Professor of Turkish Theoretical Syntax and Computational Morphology.\n"
    "You are analyzing persistent morphological disambiguation errors in Zemberek NLP's Perceptron tagger.\n\n"
    "You will be given a Canonical Ambiguity Pattern bundle containing:\n"
    "1. Pattern Metadata: ID, canonical name, macro-axis, error directionality, empirical error volume, and top affected words.\n"
    "2. 3 Diverse Error Exemplars: Sentences where the model failed, showing the surface word, ground truth parse (GT), "
    "model's incorrect prediction (PRED), and extracted syntactic anchors.\n"
    "3. 2 Positive Control Exemplars: Empirical benchmark sentences showing how the same lexical/syntactic structure is correctly parsed in context.\n\n"
    "Your task is to induce two structured components:\n"
    "COMPONENT 1: FORMAL SYNTACTIC DISAMBIGUATION RULES\n"
    "- failure_analysis: Concise linguistic explanation of why the Perceptron's 3-gram window failed on this construction.\n"
    "- syntactic_discriminator_axiom: An authoritative, formal grammatical rule that deterministically resolves this ambiguity.\n"
    "- governing_syntactic_anchors: List of grammatical triggers that govern the selection (e.g. ['Embedded Participle Head', 'Left Genitive Specifier', 'Matrix Predicate Valency']).\n"
    "- lookback_window: Recommended lookback token distance to find governing triggers.\n"
    "- lookahead_window: Recommended lookahead token distance to find governing triggers.\n\n"
    "COMPONENT 2: MACHINE-EXECUTABLE CORPUS HARVESTING SPECIFICATION\n"
    "- filter_id: Unique filter ID string (e.g. 'FILTER_P01_CASE_ACC_PARTICIPLE').\n"
    "- target_pos: Primary POS of the ambiguous token ('Noun', 'Verb', 'Adj', 'Adv', 'Pron', 'Det', 'Conj', 'Postp').\n"
    "- target_suffix_regex: Python regex matching the surface suffix of the ambiguous word (e.g. '(?:ı|i|u|ü|nı|ni|nu|nü)$').\n"
    "- seed_lemmas: List of representative lemmas affected by this pattern.\n"
    "- left_context_regex: Python regex matching the required or optional preceding token context (or empty string if none).\n"
    "- right_context_regex: Python regex matching the required or optional following token context (or empty string if none).\n"
    "- positive_arm: Object with 'description', 'regex' (a complete sequence regex matching sentences representing the correct ground-truth syntactic frame), and 'expected_reading'.\n"
    "- counter_arm: Object with 'description', 'regex' (a complete sequence regex matching sentences representing the alternative/competing frame), and 'expected_reading'.\n"
    "- harvesting_quota: Integer quota allocated to this pattern out of a 20,000 sentence corpus mining target.\n\n"
    "Return strictly valid JSON conforming to this schema:\n"
    "{\n"
    "  \"pattern_id\": \"<pattern_id>\",\n"
    "  \"linguistic_rules\": {\n"
    "    \"failure_analysis\": \"<explanation>\",\n"
    "    \"syntactic_discriminator_axiom\": \"<axiom>\",\n"
    "    \"governing_syntactic_anchors\": [\"<anchor1>\", \"<anchor2>\"],\n"
    "    \"lookback_window\": 2,\n"
    "    \"lookahead_window\": 2\n"
    "  },\n"
    "  \"harvesting_filter\": {\n"
    "    \"filter_id\": \"<filter_id>\",\n"
    "    \"target_pos\": \"<pos>\",\n"
    "    \"target_suffix_regex\": \"<regex>\",\n"
    "    \"seed_lemmas\": [\"<lemma1>\", \"<lemma2>\"],\n"
    "    \"left_context_regex\": \"<regex>\",\n"
    "    \"right_context_regex\": \"<regex>\",\n"
    "    \"positive_arm\": {\n"
    "      \"description\": \"<desc>\",\n"
    "      \"regex\": \"<regex>\",\n"
    "      \"expected_reading\": \"<reading>\"\n"
    "    },\n"
    "    \"counter_arm\": {\n"
    "      \"description\": \"<desc>\",\n"
    "      \"regex\": \"<regex>\",\n"
    "      \"expected_reading\": \"<reading>\"\n"
    "    },\n"
    "    \"harvesting_quota\": <int>\n"
    "  }\n"
    "}"
)


def compute_quotas(patterns: List[Dict[str, Any]], total_budget: int = TOTAL_HARVESTING_BUDGET) -> Dict[str, int]:
    total_errors = sum(p["error_count"] for p in patterns)
    quotas = {}
    for p in patterns:
        q = max(50, int(round(p["error_count"] / total_errors * total_budget)))
        quotas[p["pattern_id"]] = q
    diff = total_budget - sum(quotas.values())
    if "P06_CASE_PLURAL_POSSESSIVE_AMBIGUITY" in quotas:
        quotas["P06_CASE_PLURAL_POSSESSIVE_AMBIGUITY"] += diff
    return quotas


def format_pattern_prompt(pattern: Dict[str, Any], target_quota: int) -> str:
    pid = pattern["pattern_id"]
    name = pattern["canonical_name"]
    axis = pattern["macro_axis"]
    direction = pattern["direction"]
    desc = pattern["description"]
    errors = pattern["error_count"]
    pct = pattern["share_pct"]
    top_words = ", ".join(f"{w} ({c})" for w, c in pattern.get("top_words", [])[:8])

    lines = [
        f"### CANONICAL PATTERN: {pid}",
        f"Canonical Name: {name}",
        f"Macro-Axis: {axis}",
        f"Error Directionality: {direction}",
        f"Description: {desc}",
        f"Empirical Volume: {errors} errors ({pct:.2f}% of all errors across 6,000 benchmark sentences)",
        f"Top Affected Words: {top_words}",
        f"Assigned Target Harvesting Quota: {target_quota} sentences (out of 20,000 total)",
        "\n### ERROR EXEMPLARS (Model Failures):"
    ]

    for idx, ex in enumerate(pattern.get("error_exemplars", []), 1):
        surf = ex.get("surface")
        gt = ex.get("gt_analysis")
        pred = ex.get("pred_analysis")
        sent = ex.get("sentence")
        r_anc = ex.get("right_anchor") or {}
        l_anc = ex.get("left_anchor") or {}
        lines.append(f"Exemplar {idx}:")
        lines.append(f"  Sentence: \"{sent}\"")
        lines.append(f"  Target Word: \"{surf}\"")
        lines.append(f"  Ground Truth (GT): {gt}")
        lines.append(f"  Model Prediction (PRED): {pred}")
        lines.append(f"  Syntactic Anchors: Left Genitive={l_anc.get('found', False)}, Right Predicate={r_anc.get('type', 'N/A')} (lemma: {r_anc.get('lemma', 'N/A')})")

    lines.append("\n### CONTRASTIVE POSITIVE CONTROLS (Model Successes):")
    for idx, pos in enumerate(pattern.get("positive_controls", []), 1):
        surf = pos.get("surface")
        corr = pos.get("correct_key")
        sent = pos.get("sentence")
        lines.append(f"Positive Control {idx}:")
        lines.append(f"  Sentence: \"{sent}\"")
        lines.append(f"  Target Word: \"{surf}\"")
        lines.append(f"  Correct Ground Truth: {corr}")

    lines.append(
        "\nTASK: Analyze the failure mode of the Perceptron, formulate the authoritative Syntactic Discriminator Axiom, "
        "and construct the complete machine-executable corpus harvesting filter with contrastive positive/counter arms.\n"
        "Output strictly valid JSON matching the requested schema."
    )

    return "\n".join(lines)


def build_batch_request(
    patterns: List[Dict[str, Any]],
    quotas: Dict[str, int],
    batch_display_name: str,
    thinking_budget: int = 1024
) -> Dict[str, Any]:
    requests_list = []
    for p in patterns:
        pid = p["pattern_id"]
        q = quotas.get(pid, 200)
        prompt = format_pattern_prompt(p, q)
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


def submit_batch(api_key: str, payload: Dict[str, Any], model: str, max_retries: int = 5) -> Dict[str, Any]:
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:batchGenerateContent?key={api_key}"
    for attempt in range(max_retries):
        try:
            resp = requests.post(url, json=payload, timeout=90)
            if resp.status_code == 200:
                return resp.json()
            elif resp.status_code == 429:
                wait_time = (2 ** attempt) * 3.0 + 2.0
                print(f"  [Rate Limit 429] Backing off {wait_time:.1f}s...")
                time.sleep(wait_time)
            else:
                raise RuntimeError(f"Failed to submit batch (HTTP {resp.status_code}): {resp.text}")
        except requests.exceptions.RequestException as e:
            if attempt == max_retries - 1:
                raise
            time.sleep((2 ** attempt) * 2.0)
    raise RuntimeError("Max retries exceeded submitting batch.")


def get_batch_status(api_key: str, batch_name: str, max_retries: int = 4) -> Dict[str, Any]:
    url = f"https://generativelanguage.googleapis.com/v1beta/{batch_name}?key={api_key}"
    for attempt in range(max_retries):
        try:
            resp = requests.get(url, timeout=45)
            if resp.status_code == 200:
                return resp.json()
            elif resp.status_code == 429:
                time.sleep((2 ** attempt) * 3.0)
            else:
                raise RuntimeError(f"Failed to get batch status for {batch_name} (HTTP {resp.status_code}): {resp.text}")
        except requests.exceptions.RequestException:
            if attempt == max_retries - 1:
                raise
            time.sleep((2 ** attempt) * 2.0)
    raise RuntimeError(f"Max retries exceeded getting status for {batch_name}")


def clean_and_parse_json(text: str) -> Optional[Dict[str, Any]]:
    if not text:
        return None
    s = text.strip()
    if s.startswith("```json"):
        s = s[7:]
    elif s.startswith("```"):
        s = s[3:]
    if s.endswith("```"):
        s = s[:-3]
    s = s.strip()
    try:
        return json.loads(s)
    except Exception:
        # Try extracting JSON object substring
        m = re.search(r"(\{.*\})", s, re.DOTALL)
        if m:
            try:
                return json.loads(m.group(1))
            except Exception:
                pass
    return None


def validate_filter_regexes(pattern_id: str, filter_spec: Dict[str, Any]) -> List[str]:
    warnings = []
    regex_fields = [
        ("target_suffix_regex", filter_spec.get("target_suffix_regex", "")),
        ("left_context_regex", filter_spec.get("left_context_regex", "")),
        ("right_context_regex", filter_spec.get("right_context_regex", "")),
        ("positive_arm.regex", filter_spec.get("positive_arm", {}).get("regex", "")),
        ("counter_arm.regex", filter_spec.get("counter_arm", {}).get("regex", "")),
    ]
    for field_name, reg in regex_fields:
        if reg:
            try:
                re.compile(reg)
            except re.error as e:
                warnings.append(f"{pattern_id} [{field_name}]: Invalid regex '{reg}' ({e})")
    return warnings


def dry_run_on_benchmark_sentences(induced_bundles: List[Dict[str, Any]], benchmark_file: str) -> Dict[str, Any]:
    print(f"\nRunning Dry-Run Benchmark Validation against: {benchmark_file}")
    if not os.path.exists(benchmark_file):
        print(f"  Warning: Benchmark file {benchmark_file} not found, skipping dry-run.")
        return {"status": "skipped"}

    sentences = []
    with open(benchmark_file, encoding='utf-8') as f:
        for line in f:
            sentences.append(json.loads(line))

    print(f"  Loaded {len(sentences):,} benchmark error sentences.")

    dry_run_stats = {}
    for bundle in induced_bundles:
        pid = bundle["pattern_id"]
        filt = bundle.get("harvesting_filter", {})
        pos_reg_str = filt.get("positive_arm", {}).get("regex", "")
        match_count = 0
        if pos_reg_str:
            try:
                compiled = re.compile(pos_reg_str, re.IGNORECASE)
                for s in sentences:
                    txt = s.get("text", "")
                    if compiled.search(txt):
                        match_count += 1
            except re.error:
                match_count = -1

        dry_run_stats[pid] = {
            "error_volume": bundle.get("error_count", 0),
            "benchmark_matches": match_count,
            "target_harvest_quota": filt.get("harvesting_quota", 0)
        }

    return dry_run_stats


def main():
    parser = argparse.ArgumentParser(description="Stage 3: LLM Syntactic Induction via Gemini Batch API")
    parser.add_argument("--model", default="gemini-3.1-pro-preview", help="Gemini model (default: gemini-3.1-pro-preview)")
    parser.add_argument("--fallback-model", default="gemini-3.8-flash", help="Fallback model if pro quota is limited")
    parser.add_argument("--thinking-budget", type=int, default=1024, help="Thinking budget tokens (default: 1024)")
    parser.add_argument("--patterns-json", default=INPUT_PATTERNS_JSON, help="Input Stage 2 patterns JSON")
    parser.add_argument("--output-json", default=OUT_RULES_JSON, help="Output Stage 3 induced rules and filters JSON")
    parser.add_argument("--output-summary", default=OUT_SUMMARY_JSON, help="Output summary JSON")
    parser.add_argument("--registry", default=DEFAULT_REGISTRY_JSON, help="Registry tracking JSON")
    parser.add_argument("--poll-interval", type=int, default=15, help="Status polling interval in seconds")
    parser.add_argument("--dry-run-validation", action="store_true", default=True, help="Validate filters against benchmark sentences")
    args = parser.parse_args()

    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        print("ERROR: GEMINI_API_KEY environment variable is missing!", file=sys.stderr)
        sys.exit(1)

    print(f"Loading Stage 2 Master Patterns from: {args.patterns_json}")
    with open(args.patterns_json, encoding='utf-8') as f:
        stage2_data = json.load(f)

    patterns = stage2_data.get("patterns", [])
    total_patterns = len(patterns)
    print(f"Loaded {total_patterns} canonical pattern bundles.")
    if total_patterns == 0:
        print("No patterns found in input file!", file=sys.stderr)
        sys.exit(1)

    # Compute quotas
    quotas = compute_quotas(patterns, TOTAL_HARVESTING_BUDGET)
    print(f"Computed quotas for all {total_patterns} patterns summing to: {sum(quotas.values()):,} sentences.")

    # Load registry
    registry = {"batches": [], "active_batch_id": None}
    if os.path.exists(args.registry):
        try:
            with open(args.registry, encoding='utf-8') as f:
                registry = json.load(f)
        except Exception:
            pass

    active_batch_id = registry.get("active_batch_id")
    batch_data = None

    if active_batch_id:
        print(f"\nFound existing active batch in registry: {active_batch_id}")
        print("Checking status...")
        try:
            batch_data = get_batch_status(api_key, active_batch_id)
            state = batch_data.get("metadata", {}).get("state", "UNKNOWN")
            print(f"  Existing batch state: {state}")
            if state in ["BATCH_STATE_FAILED", "BATCH_STATE_CANCELLED"]:
                print("  Previous batch failed or was cancelled. Submitting a new batch...")
                active_batch_id = None
        except Exception as e:
            print(f"  Error checking existing batch: {e}. Will submit a fresh batch.")
            active_batch_id = None

    if not active_batch_id:
        display_name = f"stage3_syntactic_induction_36patterns_{int(time.time())}"
        print(f"\nBuilding Batch Request Payload for {total_patterns} patterns...")
        payload = build_batch_request(patterns, quotas, display_name, args.thinking_budget)

        print(f"Submitting Batch to Gemini Batch API (Model: {args.model})...")
        try:
            submit_resp = submit_batch(api_key, payload, args.model)
        except Exception as e:
            print(f"Primary model {args.model} submission failed: {e}")
            print(f"Falling back to: {args.fallback_model}...")
            submit_resp = submit_batch(api_key, payload, args.fallback_model)

        active_batch_id = submit_resp.get("name")
        print(f"Successfully enqueued Batch Job: {active_batch_id}")

        registry["active_batch_id"] = active_batch_id
        registry["batches"].append({
            "name": active_batch_id,
            "displayName": display_name,
            "model": args.model,
            "patterns_count": total_patterns,
            "thinking_budget": args.thinking_budget,
            "create_time": time.time(),
            "state": "BATCH_STATE_PENDING"
        })
        with open(args.registry, "w", encoding='utf-8') as f:
            json.dump(registry, f, indent=2, ensure_ascii=False)

    # Polling loop
    print(f"\nPolling batch status for {active_batch_id} (interval: {args.poll_interval}s)...")
    start_time = time.time()
    while True:
        status_data = get_batch_status(api_key, active_batch_id)
        metadata = status_data.get("metadata", {})
        state = metadata.get("state", "UNKNOWN")
        stats = metadata.get("batchStats", {})
        elapsed = int(time.time() - start_time)

        print(f"  [{time.strftime('%X')} | +{elapsed}s] State: {state} | Stats: {stats}")

        if state == "BATCH_STATE_SUCCEEDED":
            print(f"\nBatch job {active_batch_id} completed successfully in {elapsed}s!")
            batch_data = status_data
            break
        elif state in ["BATCH_STATE_FAILED", "BATCH_STATE_CANCELLED"]:
            err_msg = metadata.get("error", "Unknown error")
            print(f"ERROR: Batch job {active_batch_id} ended with state: {state}. Error: {err_msg}", file=sys.stderr)
            sys.exit(1)

        time.sleep(args.poll_interval)

    # Extract results
    inlined = batch_data.get("response", {}).get("inlinedResponses", {}).get("inlinedResponses", [])
    print(f"\nProcessing {len(inlined)} inlined responses...")

    induced_bundles = []
    regex_warnings_total = []

    for idx, item in enumerate(inlined):
        if idx >= len(patterns):
            break
        orig_pattern = patterns[idx]
        pid = orig_pattern["pattern_id"]

        resp_obj = item.get("response", {})
        candidates = resp_obj.get("candidates", [{}])
        gen_text = ""
        if candidates:
            parts = candidates[0].get("content", {}).get("parts", [{}])
            if parts:
                gen_text = parts[0].get("text", "")

        parsed = clean_and_parse_json(gen_text)
        if not parsed:
            print(f"  WARNING: Failed to parse JSON for pattern #{idx+1} ({pid}). Using fallback structure.")
            parsed = {
                "pattern_id": pid,
                "linguistic_rules": {
                    "failure_analysis": "Perceptron window limitation",
                    "syntactic_discriminator_axiom": orig_pattern.get("description", ""),
                    "governing_syntactic_anchors": ["Local Context"],
                    "lookback_window": 2,
                    "lookahead_window": 2
                },
                "harvesting_filter": {
                    "filter_id": f"FILTER_{pid}",
                    "target_pos": "Noun",
                    "target_suffix_regex": "",
                    "seed_lemmas": [w for w, _ in orig_pattern.get("top_words", [])[:5]],
                    "left_context_regex": "",
                    "right_context_regex": "",
                    "positive_arm": {
                        "description": "Ground truth context",
                        "regex": "",
                        "expected_reading": orig_pattern.get("direction", "").split("->")[0].strip()
                    },
                    "counter_arm": {
                        "description": "Counter context",
                        "regex": "",
                        "expected_reading": orig_pattern.get("direction", "").split("->")[-1].strip()
                    },
                    "harvesting_quota": quotas.get(pid, 200)
                }
            }

        # Merge with original pattern metadata
        merged_bundle = {
            "pattern_id": pid,
            "canonical_name": orig_pattern["canonical_name"],
            "macro_axis": orig_pattern["macro_axis"],
            "direction": orig_pattern["direction"],
            "error_count": orig_pattern["error_count"],
            "share_pct": orig_pattern["share_pct"],
            "unique_words_count": orig_pattern.get("unique_words_count", 0),
            "top_words": orig_pattern.get("top_words", []),
            "linguistic_rules": parsed.get("linguistic_rules", {}),
            "harvesting_filter": parsed.get("harvesting_filter", {}),
            "error_exemplars": orig_pattern.get("error_exemplars", []),
            "positive_controls": orig_pattern.get("positive_controls", [])
        }

        # Ensure quota is strictly calibrated
        assigned_q = quotas.get(pid, 200)
        merged_bundle["harvesting_filter"]["harvesting_quota"] = assigned_q

        # Validate regexes
        warnings = validate_filter_regexes(pid, merged_bundle["harvesting_filter"])
        if warnings:
            regex_warnings_total.extend(warnings)

        induced_bundles.append(merged_bundle)

    print(f"Successfully processed and validated all {len(induced_bundles)} pattern bundles.")
    if regex_warnings_total:
        print(f"Encountered {len(regex_warnings_total)} regex syntax warnings:")
        for w in regex_warnings_total[:5]:
            print(f"  {w}")

    # Dry-run validation
    dry_run_results = {}
    if args.dry_run_validation:
        dry_run_results = dry_run_on_benchmark_sentences(induced_bundles, BENCHMARK_ERROR_SENTS)

    # Save output JSON
    payload_out = {
        "version": "1.0",
        "stage": "STAGE_3_SYNTACTIC_INDUCTION",
        "batch_id": active_batch_id,
        "total_patterns": len(induced_bundles),
        "total_harvesting_budget": TOTAL_HARVESTING_BUDGET,
        "patterns": induced_bundles
    }

    print(f"\nWriting induced rules and filters to: {args.output_json}")
    with open(args.output_json, "w", encoding='utf-8') as f:
        json.dump(payload_out, f, indent=2, ensure_ascii=False)

    summary_out = {
        "total_patterns": len(induced_bundles),
        "total_harvesting_budget": TOTAL_HARVESTING_BUDGET,
        "batch_id": active_batch_id,
        "axis_quota_breakdown": Counter(b["macro_axis"] for b in induced_bundles for _ in range(b["harvesting_filter"]["harvesting_quota"])),
        "dry_run_summary": {pid: stats.get("benchmark_matches", 0) for pid, stats in dry_run_results.items() if isinstance(stats, dict)}
    }
    with open(args.output_summary, "w", encoding='utf-8') as f:
        json.dump(summary_out, f, indent=2, ensure_ascii=False)
    print(f"Summary saved to: {args.output_summary}")

    print("\nStage 3 Induction Pipeline completed successfully!")


if __name__ == "__main__":
    main()
