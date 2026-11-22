#!/usr/bin/env python3
"""
Analyzes UNKNOWN (OOV / unparseable) tokens in Zemberek ambiguity datasets.
Computes sentence-level and token-level frequencies, domain breakdowns,
and top UNKNOWN surface forms.
"""

import sys
import json
import argparse
from collections import Counter, defaultdict
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


def analyze_dataset(file_path: Path):
    if not file_path.exists():
        print(f"Error: File not found: {file_path}", file=sys.stderr)
        return

    print("=" * 80)
    print(f"ANALYZING UNKNOWN TOKENS IN: {file_path.name}")
    print(f"Path: {file_path}")
    print("=" * 80)

    total_sentences = 0
    sentences_with_unknown = 0
    sentence_unknown_counts = Counter()

    total_tokens = 0
    total_ambiguous_tokens = 0
    ambiguous_with_unknown = 0

    total_unknown_tokens = 0
    unknown_token_freq = Counter()
    domain_sentence_total = Counter()
    domain_sentence_with_unk = Counter()
    domain_unk_token_total = Counter()

    # Detailed categorizations
    punct_symbols_unk = Counter()
    lexical_words_unk = Counter()

    with open(file_path, "r", encoding="utf-8") as f:
        for line_num, line in enumerate(f, start=1):
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
            domain_sentence_total[domain] += 1

            tokens = record.get("tokens", [])
            sentence_unk_in_this_sent = 0

            for t in tokens:
                total_tokens += 1
                is_amb = t.get("is_ambiguous", False)
                if is_amb:
                    total_ambiguous_tokens += 1

                t_is_unk = is_unknown_token(t)

                if is_amb and t_is_unk:
                    ambiguous_with_unknown += 1

                if t_is_unk:
                    sentence_unk_in_this_sent += 1
                    total_unknown_tokens += 1
                    surf = t.get("surface", "").strip()
                    unknown_token_freq[surf] += 1
                    domain_unk_token_total[domain] += 1

                    if not any(c.isalnum() for c in surf):
                        punct_symbols_unk[surf] += 1
                    else:
                        lexical_words_unk[surf] += 1

            sentence_unknown_counts[sentence_unk_in_this_sent] += 1
            if sentence_unk_in_this_sent > 0:
                sentences_with_unknown += 1
                domain_sentence_with_unk[domain] += 1

    # Print Summary Statistics
    sent_unk_pct = (sentences_with_unknown / total_sentences * 100.0) if total_sentences else 0.0
    tok_unk_pct = (total_unknown_tokens / total_tokens * 100.0) if total_tokens else 0.0

    print(f"\n1. OVERALL TOTALS:")
    print(f"  • Total Sentences:                   {total_sentences:,}")
    print(f"  • Sentences with ≥1 UNKNOWN token:   {sentences_with_unknown:,} ({sent_unk_pct:.2f}%)")
    print(f"  • Sentences 100% Clean (0 UNKNOWN):  {total_sentences - sentences_with_unknown:,} ({100.0 - sent_unk_pct:.2f}%)")
    print(f"  • Total Tokens Scanned:              {total_tokens:,}")
    print(f"  • Total Ambiguous Tokens:            {total_ambiguous_tokens:,}")
    print(f"  • Total UNKNOWN Tokens:              {total_unknown_tokens:,} ({tok_unk_pct:.2f}% of all tokens)")
    print(f"  • Ambiguous Tokens that are UNKNOWN: {ambiguous_with_unknown:,} (Expected: 0)")

    print(f"\n2. DISTRIBUTION OF UNKNOWNS PER SENTENCE:")
    for count in sorted(sentence_unknown_counts.keys()):
        freq = sentence_unknown_counts[count]
        pct = (freq / total_sentences * 100.0) if total_sentences else 0.0
        label = f"{count} UNK tokens" if count > 0 else "0 UNK tokens (Clean)"
        print(f"  • {label:24s}: {freq:6,d} sentences ({pct:5.2f}%)")

    print(f"\n3. UNKNOWN TOKEN TYPE BREAKDOWN:")
    total_punct_unk = sum(punct_symbols_unk.values())
    total_word_unk = sum(lexical_words_unk.values())
    punct_pct = (total_punct_unk / total_unknown_tokens * 100.0) if total_unknown_tokens else 0.0
    word_pct = (total_word_unk / total_unknown_tokens * 100.0) if total_unknown_tokens else 0.0
    print(f"  • Isolated Punctuation/Symbols:      {total_punct_unk:6,d} ({punct_pct:5.2f}%) across {len(punct_symbols_unk)} distinct symbols")
    print(f"  • Lexical Words / Typos / Slang:     {total_word_unk:6,d} ({word_pct:5.2f}%) across {len(lexical_words_unk)} distinct words")

    print(f"\n4. BREAKDOWN BY DOMAIN (Ordered by UNKNOWN sentence %):")
    print(f"  {'Domain':28s} | {'Total Sents':11s} | {'Sents w/ UNK':13s} | {'UNK Sent %':10s} | {'UNK Tokens':10s}")
    print(f"  {'-'*28}-+-{'-'*11}-+-{'-'*13}-+-{'-'*10}-+-{'-'*10}")
    
    sorted_domains = sorted(
        domain_sentence_total.keys(),
        key=lambda d: (domain_sentence_with_unk[d] / domain_sentence_total[d]) if domain_sentence_total[d] else 0,
        reverse=True,
    )
    for dom in sorted_domains:
        tot_s = domain_sentence_total[dom]
        unk_s = domain_sentence_with_unk[dom]
        pct_s = (unk_s / tot_s * 100.0) if tot_s else 0.0
        unk_t = domain_unk_token_total[dom]
        print(f"  {dom:28s} | {tot_s:11,d} | {unk_s:13,d} | {pct_s:9.2f}% | {unk_t:10,d}")

    print(f"\n5. TOP 40 MOST FREQUENT UNKNOWN SURFACE FORMS:")
    print(f"  {'Rank':4s}  {'Surface Token':24s}  {'Count':8s}  {'Type / Note'}")
    print(f"  {'-'*4}  {'-'*24}  {'-'*8}  {'-'*35}")
    for rank, (surf, cnt) in enumerate(unknown_token_freq.most_common(40), start=1):
        if not any(c.isalnum() for c in surf):
            note = "Punctuation artifact"
        elif surf.lower() in ("bi", "diilim", "nolcak", "nolur", "hic", "yoksa"):
            note = "Colloquial / spoken Turkish"
        elif any(surf.endswith(s) for s in ("de", "da", "ki", "mi", "mu", "mü", "mı")):
            note = "Fused clitic / missing space"
        else:
            note = "Lexical typo / OOV name"
        print(f"  {rank:4d}. {repr(surf):24s}  {cnt:8,d}  {note}")

    print("\n" + "=" * 80)


def main():
    parser = argparse.ArgumentParser(description="Analyze UNKNOWN tokens in ambiguity datasets.")
    parser.add_argument(
        "file",
        nargs="?",
        default="data/ambiguity/train_combined_33k.jsonl",
        help="Path to JSONL dataset to inspect (default: train_combined_33k.jsonl)",
    )
    args = parser.parse_args()
    analyze_dataset(Path(args.file))


if __name__ == "__main__":
    main()

