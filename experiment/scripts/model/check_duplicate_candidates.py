#!/usr/bin/env python3
""" Audits candidate slice JSONL files for duplicate zemberek_key candidates
caused by Zemberek DictionaryItem Index:1/Index:2 homographs with different
morphophonemic RootAttributes (e.g., Voicing vs NoVoicing, LastVowelDrop, InverseHarmony).
"""
import glob
import json
import os
from collections import Counter, defaultdict


def main():
    slice_files = sorted(
        glob.glob("/home/dndara/data/turkish/annotated_slices/*/candidates/*.jsonl")
    )
    print(f"Scanning {len(slice_files)} candidate slice files...\n")

    total_sentences = 0
    total_tokens = 0
    ambig_tokens = 0
    tokens_with_dup_keys = 0
    false_ambig_tokens = 0  # Only "ambiguous" because of duplicate zemberek_key!

    lemma_dup_counts = Counter()
    false_ambig_surfaces = Counter()
    sample_records = defaultdict(list)

    for sf in slice_files:
        with open(sf, encoding="utf-8") as fp:
            for line in fp:
                total_sentences += 1
                row = json.loads(line)
                for tok in row["tokens"]:
                    total_tokens += 1
                    cands = tok.get("candidates", [])
                    if len(cands) > 1:
                        ambig_tokens += 1
                        keys = [c["zemberek_key"] for c in cands]
                        unique_keys = set(keys)
                        if len(keys) != len(unique_keys):
                            tokens_with_dup_keys += 1
                            if len(unique_keys) == 1:
                                false_ambig_tokens += 1
                                false_ambig_surfaces[tok["surface"]] += 1
                            for k, cnt in Counter(keys).items():
                                if cnt > 1:
                                    lemma_tag = k.split("]")[0] + "]"
                                    lemma_dup_counts[lemma_tag] += 1
                                    if len(sample_records[lemma_tag]) < 2:
                                        sample_records[lemma_tag].append(
                                            (
                                                os.path.basename(sf),
                                                row["sentence_id"],
                                                tok["index"],
                                                tok["surface"],
                                                cands,
                                            )
                                        )

    print("=== SUMMARY ACROSS ALL 110 SLICES ===")
    print(f"Total sentences              : {total_sentences:,}")
    print(f"Total tokens                 : {total_tokens:,}")
    print(f"Marked ambiguous tokens      : {ambig_tokens:,}")
    print(
        f"Tokens with duplicate keys   : {tokens_with_dup_keys:,} "
        f"({tokens_with_dup_keys / ambig_tokens * 100:.2f}% of ambiguous tokens)"
    )
    print(
        f"False-ambiguous tokens       : {false_ambig_tokens:,} "
        f"(100% identical candidates; only 1 unique zemberek_key!)"
    )

    print("\n=== TOP 25 LEMMAS CAUSING DUPLICATE CANDIDATES ===")
    for lemma_tag, count in lemma_dup_counts.most_common(25):
        ex_list = sample_records[lemma_tag]
        ex_surf = ex_list[0][3] if ex_list else ""
        print(f"  {lemma_tag:<22} : {count:>6,} occurrences  (e.g. '{ex_surf}')")

    print("\n=== TOP 20 FALSE-AMBIGUOUS SURFACES (ONLY 1 UNIQUE ANALYSIS) ===")
    for surf, count in false_ambig_surfaces.most_common(20):
        print(f"  {surf:<20} : {count:>5,}")

    print("\n=== DETAILED EXAMPLES FROM test_slice_001 (First 100 Sentences) ===")
    with open(
        "/home/dndara/data/turkish/annotated_slices/test/candidates/test_slice_001_candidates.jsonl",
        encoding="utf-8",
    ) as fp:
        for idx, line in enumerate(fp):
            if idx >= 100:
                break
            row = json.loads(line)
            for tok in row["tokens"]:
                cands = tok.get("candidates", [])
                keys = [c["zemberek_key"] for c in cands]
                if len(cands) > 1 and len(keys) != len(set(keys)):
                    print(
                        f"\nSentence {row['sentence_id']} | Token #{tok['index']} '{tok['surface']}' "
                        f"(unique keys: {len(set(keys))}/{len(keys)}):"
                    )
                    for c in cands:
                        print(f"  [{c['id']}] zemberek_key={c['zemberek_key']} | ig_key={c['ig_key']}")


if __name__ == "__main__":
    main()
