#!/usr/bin/env python3
"""
Filter Training Dataset to Retain Only 100% Clean Sentences (Zero UNKNOWN Tokens).

Removes any sentence where at least one token is UNKNOWN (OOV, unparseable, or missing candidates).
Outputs:
1. Filtered JSONL dataset
2. Native Zemberek Perceptron training text (.zemberek.txt)

Usage:
  python3 experiment/scripts/filter_clean_no_unknown_sentences.py \
      --input data/ambiguity/train_combined_33k.jsonl \
      --output-jsonl data/ambiguity/train_combined_33k_clean_no_unk.jsonl \
      --output-txt data/ambiguity/train_combined_33k_clean_no_unk.zemberek.txt
"""

import os
import sys
import json
import time
import argparse
from collections import Counter
from pathlib import Path


def is_unknown_candidate(cand: dict) -> bool:
    key = cand.get("zemberek_key", "")
    pos = cand.get("pos", "")
    lemma = cand.get("lemma", "")
    return "Unknown" in key or "UNK" in key or pos == "Unk" or lemma == "UNK"


def is_unknown_token(token: dict) -> bool:
    cands = token.get("candidates", [])
    if not cands:
        return True
    return any(is_unknown_candidate(c) for c in cands)


def sentence_has_unknown(record: dict) -> bool:
    tokens = record.get("tokens", [])
    if not tokens:
        return True
    return any(is_unknown_token(t) for t in tokens)


def filter_dataset(input_jsonl_path: str, output_jsonl_path: str, output_txt_path: str = None):
    input_path = Path(input_jsonl_path)
    if not input_path.exists():
        print(f"Error: Input file does not exist: {input_path}", file=sys.stderr)
        sys.exit(1)

    out_jsonl_path = Path(output_jsonl_path)
    out_jsonl_path.parent.mkdir(parents=True, exist_ok=True)

    out_txt_file = None
    if output_txt_path:
        out_txt_path = Path(output_txt_path)
        out_txt_path.parent.mkdir(parents=True, exist_ok=True)
        out_txt_file = open(out_txt_path, "w", encoding="utf-8")

    total_sentences = 0
    clean_sentences = 0
    discarded_sentences = 0

    clean_total_tokens = 0
    clean_ambiguous_tokens = 0

    domain_counts_total = Counter()
    domain_counts_clean = Counter()
    domain_counts_discarded = Counter()

    t0 = time.time()
    print("=" * 80)
    print(f"FILTERING CLEAN SENTENCES (ZERO UNKNOWN TOKENS)")
    print(f"Input:       {input_path}")
    print(f"Out JSONL:   {out_jsonl_path}")
    if output_txt_path:
        print(f"Out TXT:     {output_txt_path}")
    print("=" * 80)

    with open(input_path, "r", encoding="utf-8") as in_f, \
         open(out_jsonl_path, "w", encoding="utf-8") as out_jsonl_f:

        for line_num, line in enumerate(in_f, start=1):
            line = line.strip()
            if not line:
                continue

            try:
                record = json.loads(line)
            except Exception as e:
                print(f"Warning: JSON decode error at line {line_num}: {e}")
                continue

            total_sentences += 1
            domain = record.get("domain", "unknown")
            domain_counts_total[domain] += 1

            if sentence_has_unknown(record):
                discarded_sentences += 1
                domain_counts_discarded[domain] += 1
                continue

            # Sentence is 100% clean
            clean_sentences += 1
            domain_counts_clean[domain] += 1
            out_jsonl_f.write(line + "\n")

            tokens = record.get("tokens", [])
            sentence_text = record.get("text", "").strip()

            if out_txt_file:
                out_txt_file.write(f"S:{sentence_text}\n")

            for token in tokens:
                clean_total_tokens += 1
                is_amb = token.get("is_ambiguous", False)
                if is_amb:
                    clean_ambiguous_tokens += 1

                if out_txt_file:
                    surface = token.get("surface", "")
                    out_txt_file.write(f"{surface}\n")
                    candidates = token.get("candidates", [])
                    selected_id = token.get("selected_candidate_id", 0)
                    ambiguous = token.get("is_ambiguous", len(candidates) > 1)

                    for c in candidates:
                        key = c.get("zemberek_key", "")
                        if not key:
                            continue
                        cid = c.get("id", 0)
                        star = "*" if (ambiguous and cid == selected_id) else ""
                        out_txt_file.write(f"{key}{star}\n")

    if out_txt_file:
        out_txt_file.close()

    elapsed = time.time() - t0
    clean_pct = (clean_sentences / total_sentences * 100.0) if total_sentences else 0.0
    discarded_pct = (discarded_sentences / total_sentences * 100.0) if total_sentences else 0.0

    print("\nRESULTS SUMMARY:")
    print(f"  • Total Input Sentences:       {total_sentences:,}")
    print(f"  • Clean Sentences Retained:    {clean_sentences:,} ({clean_pct:.2f}%)")
    print(f"  • Discarded (≥1 UNK token):     {discarded_sentences:,} ({discarded_pct:.2f}%)")
    print(f"  • Clean Dataset Total Tokens:  {clean_total_tokens:,}")
    print(f"  • Clean Dataset Ambiguous:     {clean_ambiguous_tokens:,} ({clean_ambiguous_tokens / clean_total_tokens * 100.0:.1f}%)")
    print(f"  • Elapsed Time:                {elapsed:.2f}s")

    print("\nDOMAIN BREAKDOWN:")
    print(f"  {'Domain':<30} | {'Total':>8} | {'Clean':>8} | {'Clean %':>8} | {'Discarded':>10}")
    print("  " + "-" * 74)
    for dom, tot in domain_counts_total.most_common():
        cln = domain_counts_clean.get(dom, 0)
        dsc = domain_counts_discarded.get(dom, 0)
        pct = (cln / tot * 100.0) if tot else 0.0
        print(f"  {dom:<30} | {tot:>8,} | {cln:>8,} | {pct:>7.1f}% | {dsc:>10,}")

    jsonl_size_mb = out_jsonl_path.stat().st_size / (1024 * 1024)
    print(f"\n[OUTPUT] JSONL saved: {out_jsonl_path} ({jsonl_size_mb:.1f} MB)")
    if output_txt_path:
        txt_size_mb = Path(output_txt_path).stat().st_size / (1024 * 1024)
        print(f"[OUTPUT] TXT saved:   {output_txt_path} ({txt_size_mb:.1f} MB)")


def main():
    parser = argparse.ArgumentParser(
        description="Filter training set to keep only sentences with 0 UNKNOWN tokens."
    )
    parser.add_argument(
        "-i", "--input",
        default="data/ambiguity/train_combined_33k.jsonl",
        help="Input JSONL training set (default: data/ambiguity/train_combined_33k.jsonl)"
    )
    parser.add_argument(
        "--output-jsonl",
        default="data/ambiguity/train_combined_33k_clean_no_unk.jsonl",
        help="Output clean JSONL file (default: data/ambiguity/train_combined_33k_clean_no_unk.jsonl)"
    )
    parser.add_argument(
        "--output-txt",
        default="data/ambiguity/train_combined_33k_clean_no_unk.zemberek.txt",
        help="Output clean Zemberek training .txt file (default: data/ambiguity/train_combined_33k_clean_no_unk.zemberek.txt)"
    )

    args = parser.parse_args()
    filter_dataset(args.input, args.output_jsonl, args.output_txt)


if __name__ == "__main__":
    main()
