#!/usr/bin/env python3
"""
merge_adjudicated_pool.py

Merges the adjudicated sentences from Phase 3 back into train_combined_v2.jsonl
using the exact line indices recorded in adjudication_pool_candidates.jsonl.meta.json,
producing train_combined_v3.jsonl.
"""

import sys
import json
import argparse


def main():
    parser = argparse.ArgumentParser(description="Merge CoT adjudicated sentences into training corpus.")
    parser.add_argument("-b", "--base", default="data/ambiguity/train_combined_v2.jsonl",
                        help="Path to base training dataset (v2)")
    parser.add_argument("-a", "--adjudicated", default="data/ambiguity/adjudication_pool_reasoned.jsonl",
                        help="Path to adjudicated pool file")
    parser.add_argument("-m", "--meta", default="data/ambiguity/adjudication_pool_candidates.jsonl.meta.json",
                        help="Path to metadata file containing line indices")
    parser.add_argument("-o", "--output", default="data/ambiguity/train_combined_v3.jsonl",
                        help="Output path for v3 dataset")
    args = parser.parse_args()

    print(f"[INFO] Loading metadata from: {args.meta}")
    with open(args.meta, "r", encoding="utf-8") as f:
        meta_records = json.load(f)

    print(f"[INFO] Loading adjudicated sentences from: {args.adjudicated}")
    with open(args.adjudicated, "r", encoding="utf-8") as f:
        adj_lines = [l.strip() for l in f if l.strip()]

    if len(meta_records) != len(adj_lines):
        print(f"[ERROR] Meta records count ({len(meta_records)}) != Adjudicated lines count ({len(adj_lines)})", file=sys.stderr)
        sys.exit(1)

    # Build replacement map: line_index -> adjudicated_json_line
    replacements = {}
    for meta, line in zip(meta_records, adj_lines):
        line_idx = meta["lineIndex"]
        sent_id = meta["sentenceId"]
        rec = json.loads(line)
        if rec.get("sentence_id") != sent_id:
            print(f"[WARNING] Sentence ID mismatch at line {line_idx}: meta {sent_id} vs rec {rec.get('sentence_id')}", file=sys.stderr)
        replacements[line_idx] = line

    print(f"[INFO] Total replacement entries prepared: {len(replacements)}")

    print(f"[INFO] Merging into base file: {args.base} -> {args.output}")
    total_lines = 0
    replaced_lines = 0

    with open(args.base, "r", encoding="utf-8") as in_f, open(args.output, "w", encoding="utf-8") as out_f:
        for idx, line in enumerate(in_f):
            total_lines += 1
            if idx in replacements:
                out_f.write(replacements[idx] + "\n")
                replaced_lines += 1
            else:
                out_f.write(line)

    print(f"[INFO] Merge completed successfully!")
    print(f"       Total lines written:    {total_lines}")
    print(f"       Total lines replaced:   {replaced_lines}")
    print(f"       Output file:            {args.output}")


if __name__ == "__main__":
    main()

