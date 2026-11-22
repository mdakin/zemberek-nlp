#!/usr/bin/env python3
"""
canonicalize_lexicon_twins.py

Canonicalizes equivalent Zemberek lexicon parse representations (Lexicon Twins)
to prevent the perceptron model from splitting weight mass across duplicate parses,
and eliminates artificial evaluation penalties.

Supported Canonicalizations:
1. Causative roots: Derived causative verbs (bil:Verb|dir:Caus, art:Verb|tır:Caus, azal:Verb|t:Caus,
   değer:Noun|len:Verb|dir:Caus) -> Lexicalized root (bildirmek:Verb, arttırmak:Verb, azaltmak:Verb, değerlendirmek:Verb).
2. Plural-Possessive representation twins: +lar:A3pl+ı:P3pl -> +A3sg+ları:P3pl.82.10%
3. Circumflex orthography duplicates: [hâl:Noun] -> [hal:Noun].
4. Determiner duplicates: [birkaç:Adj] -> [birkaç:Det], [birçok:Adj] -> [birçok:Det].
"""

import sys
import os
import json
import argparse
from typing import Dict, Any, List, Tuple


def get_canonical_key(key: str) -> str:
    """Returns the canonical version of a zemberek_key string."""
    # Causative twins -> Lexicalized roots
    k = key.replace('[bilmek:Verb] bil:Verb|dir:Caus→Verb', '[bildirmek:Verb] bildir:Verb')
    k = k.replace('[artmak:Verb] art:Verb|tır:Caus→Verb', '[arttırmak:Verb] arttır:Verb')
    k = k.replace('[azalmak:Verb] azal:Verb|t:Caus→Verb', '[azaltmak:Verb] azalt:Verb')
    k = k.replace('[değer:Noun] değer:Noun+A3sg|len:Acquire→Verb|dir:Caus→Verb', '[değerlendirmek:Verb] değerlendir:Verb')
    k = k.replace('[karşı:Adj] karşı:Adj|laş:Become→Verb|tır:Caus→Verb', '[karşılaştırmak:Verb] karşılaştır:Verb')
    k = k.replace('[karşı:Noun] karşı:Noun+A3sg|laş:Become→Verb|tır:Caus→Verb', '[karşılaştırmak:Verb] karşılaştır:Verb')

    # Plural-Possessive representation twins
    k = k.replace('+lar:A3pl+ı:P3pl', '+A3sg+ları:P3pl')
    k = k.replace('+ler:A3pl+i:P3pl', '+A3sg+leri:P3pl')

    # Orthographic duplicates: Unify to authentic circumflex lemma [hâl:Noun]
    k = k.replace('[hal:Noun] hal:Noun', '[hâl:Noun] hal:Noun')

    # Percent twins: Unify to cardinal numeral + locative
    k = k.replace('[yüzde:Noun] yüzde:Noun+A3sg', '[yüz:Num,Card] yüz:Num|Zero→Noun+A3sg+de:Loc')
    k = k.replace('[yüz:Num,Card] yüz:Num+de:Loc', '[yüz:Num,Card] yüz:Num|Zero→Noun+A3sg+de:Loc')

    # Hakkında twins: Unify to standard 3rd person possessive locative
    k = k.replace('[hak:Noun] hakk:Noun+A3sg+ın:P2sg+da:Loc', '[hak:Noun] hakk:Noun+A3sg+ı:P3sg+nda:Loc')
    k = k.replace('[hakkında:Adv] hakkında:Adv', '[hak:Noun] hakk:Noun+A3sg+ı:P3sg+nda:Loc')

    # Determiners
    k = k.replace('[birkaç:Adj] birkaç:Adj', '[birkaç:Det] birkaç:Det')
    k = k.replace('[birçok:Adj] birçok:Adj', '[birçok:Det] birçok:Det')
    k = k.replace('[birtakım:Adj] birtakım:Adj', '[birtakım:Det] birtakım:Det')

    return k


def canonicalize_token(token: Dict[str, Any]) -> Tuple[bool, str, str]:
    if not token.get("is_ambiguous", False):
        return False, "", ""

    curr_id = token.get("selected_candidate_id", 0)
    candidates = token.get("candidates", [])
    if not candidates:
        return False, "", ""

    curr_cand = next((c for c in candidates if c.get("id") == curr_id), None)
    if not curr_cand:
        return False, "", ""

    curr_key = curr_cand.get("zemberek_key", "")
    target_key = get_canonical_key(curr_key)

    if target_key == curr_key:
        return False, "", ""

    # Check if a candidate with target_key exists
    match_cand = next((c for c in candidates if c.get("zemberek_key") == target_key), None)
    if match_cand and match_cand.get("id") != curr_id:
        new_id = match_cand.get("id")
        token["selected_candidate_id"] = new_id
        token["canonicalized_from_id"] = curr_id
        token["canonicalized_from_key"] = curr_key
        return True, curr_key, target_key

    return False, "", ""


def process_dataset(input_file: str, output_file: str):
    print(f"[INFO] Reading from: {input_file}")
    total_sentences = 0
    total_ambiguous = 0
    total_canonicalized = 0
    modified_sentences = 0

    logs = []

    with open(input_file, "r", encoding="utf-8") as in_f, \
         open(output_file, "w", encoding="utf-8") as out_f:

        for line_num, line in enumerate(in_f):
            line = line.strip()
            if not line:
                continue

            record = json.loads(line)
            total_sentences += 1
            sent_modified = False

            for t in record.get("tokens", []):
                if t.get("is_ambiguous"):
                    total_ambiguous += 1
                    changed, old_k, new_k = canonicalize_token(t)
                    if changed:
                        total_canonicalized += 1
                        sent_modified = True
                        if len(logs) < 15:
                            logs.append(f"  [{t.get('surface')}]: {old_k} -> {new_k}")

            if sent_modified:
                modified_sentences += 1

            out_f.write(json.dumps(record, ensure_ascii=False) + "\n")

    print("\n==================================================================")
    print("           LEXICON TWIN CANONICALIZATION SUMMARY                  ")
    print("==================================================================")
    print(f"Total Sentences Processed:    {total_sentences:,}")
    print(f"Total Ambiguous Tokens:       {total_ambiguous:,}")
    print(f"Tokens Canonicalized:         {total_canonicalized:,} ({total_canonicalized/max(1, total_ambiguous)*100:.2f}%)")
    print(f"Sentences Modified:           {modified_sentences:,} ({modified_sentences/max(1, total_sentences)*100:.2f}%)")
    print("==================================================================\n")

    if logs:
        print("Sample Canonicalizations:")
        for log in logs[:10]:
            print(log)
        print("-" * 60)

    print(f"[DONE] Saved canonicalized dataset to: {output_file}")


def main():
    parser = argparse.ArgumentParser(description="Canonicalize Zemberek lexicon twins in JSONL datasets.")
    parser.add_argument("-i", "--input", required=True, help="Input annotated JSONL file")
    parser.add_argument("-o", "--output", required=True, help="Output canonicalized JSONL file")
    args = parser.parse_args()

    process_dataset(args.input, args.output)


if __name__ == "__main__":
    main()

