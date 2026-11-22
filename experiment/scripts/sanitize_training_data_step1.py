#!/usr/bin/env python3
"""
sanitize_training_data_step1.py

Phase 1: Deterministic Morpho-Syntactic Invariant Sanitization.
Applies 100% airtight linguistic rules to clean annotation errors (such as LLM hallucinations)
in training JSONL datasets:
  Rule 1.1: Preceding Modifier Constraint (Determiner/Numeral/Adjective/Genitive + Word -> Verbal Noun, not Neg+Imp)
  Rule 1.2: Postpositional Attachment Constraint (Word + Postposition -> Nominal, not Imperative)
  Rule 1.3: Casing & Sentence-Initial False Proper Nouns (Sentence start capitalized common nouns)
  Rule 1.4: Fixed Lexical Collocations (e.g. 'kaleme almak', 'kan basıncı', 'bu yana', 'kalp atışı')
"""

import sys
import os
import json
import argparse
import re
from typing import Dict, Any, List, Tuple

# Strict determiners that cannot modify a finite verb/imperative
DETERMINERS = {
    "bu", "şu", "her", "hiçbir", "hangi", "kaç", "birkaç", 
    "birçok", "çoğu", "bütün", "tüm", "bazı", "birtakım"
}

# Number words and digit patterns
NUMBER_WORDS = {
    "bir", "iki", "üç", "dört", "beş", "altı", "yedi", "sekiz", "dokuz", "on",
    "yirmi", "otuz", "kırk", "elli", "altmış", "yetmiş", "seksen", "doksan",
    "yüz", "bin", "milyon", "milyar", "trilyon"
}

# Strict adnominal adjectives/modifiers that cannot act as manner adverbs for an imperative verb
# Note: manner adverbs like 'hızlı', 'yavaş', 'erken', 'geç', 'iyi', 'kötü' are excluded
# because they can validly modify imperative verbs ('geç kalma!', 'hızlı koş!').
COMMON_ADJECTIVES = {
    "nakit", "peşin", "aylık", "yıllık", "günlük", "haftalık", "asgari", "kısmi",
    "toplu", "otomatik", "düzenli", "sürekli", "tek"
}

# Common postpositions that govern nominals, not finite imperative verbs
POSTPOSITIONS = {
    "için", "kadar", "sonra", "önce", "göre", "rağmen", "beri", "yana", 
    "karşın", "boyunca", "itibaren", "sırasında", "esnasında", "nedeniyle", "sebebiyle"
}

# Common Turkish words that are frequently mistagged as Proper Nouns when capitalized at sentence start.
# Note: 'can' is excluded because Can is a very common given name in Turkish.
FALSE_PROPER_NOUNS = {
    "peygamber", "kitap", "kitab", "deva", "kadar", "yarın", "üzer", 
    "üzeri", "son", "önce", "dün", "gün", "yıl", "ay", "yol", "dil", "devam"
}


def is_numeral(surface: str) -> bool:
    s = surface.lower()
    if re.match(r"^\d+([.,]\d+)?%?$", s):
        return True
    return s in NUMBER_WORDS


def has_genitive_case(token: Dict[str, Any]) -> bool:
    """Checks if the token has genitive case marking."""
    selected_id = token.get("selected_candidate_id", 0)
    for c in token.get("candidates", []):
        if c.get("id") == selected_id:
            key = c.get("zemberek_key", "") + " " + c.get("oflazer_style", "")
            if "+Gen" in key or "+nin:Gen" in key or "+nın:Gen" in key or "+in:Gen" in key or "+ın:Gen" in key:
                return True
    return False


def is_verbal_noun_candidate(cand: Dict[str, Any]) -> bool:
    key = cand.get("zemberek_key", "") + " " + cand.get("oflazer_style", "")
    return ("Inf2→Noun" in key or "Inf→Noun" in key or "+Noun" in key or 
            ":Noun" in key or ":Inf" in key or "Inf2" in key) and ("+me:Neg" not in key and "+ma:Neg" not in key)


def is_neg_imperative_candidate(cand: Dict[str, Any]) -> bool:
    key = cand.get("zemberek_key", "") + " " + cand.get("oflazer_style", "")
    return ("+me:Neg" in key or "+ma:Neg" in key or "+Neg" in key) and ("+Imp" in key or "+A2sg" in key)


def find_candidate_by_filter(candidates: List[Dict[str, Any]], filter_func) -> int:
    for c in candidates:
        if filter_func(c):
            return c.get("id", 0)
    return -1


def sanitize_sentence(record: Dict[str, Any]) -> Tuple[int, List[str]]:
    """Sanitizes a single sentence record using deterministic linguistic invariants.
    Returns (num_changes, change_logs).
    """
    tokens = record.get("tokens", [])
    num_tokens = len(tokens)
    changes = 0
    change_logs = []

    for i, token in enumerate(tokens):
        if not token.get("is_ambiguous", False):
            continue

        surface = token.get("surface", "")
        surface_lower = surface.lower()
        curr_id = token.get("selected_candidate_id", 0)
        candidates = token.get("candidates", [])
        if not candidates:
            continue

        # Get current candidate info
        curr_cand = next((c for c in candidates if c.get("id") == curr_id), None)
        if not curr_cand:
            continue
        curr_key = curr_cand.get("zemberek_key", "")

        prev_tok = tokens[i - 1] if i > 0 else None
        prev_surf = prev_tok.get("surface", "").lower() if prev_tok else ""

        next_tok = tokens[i + 1] if i + 1 < num_tokens else None
        next_surf = next_tok.get("surface", "").lower() if next_tok else ""

        changed = False
        new_id = curr_id
        rule_applied = ""

        # --- Rule 1.1: Preceding Modifier Constraint (Verbal Noun vs Negative Imperative) ---
        # e.g., '3 bin YTL ödeme', 'nakit ödeme', 'bu gelişme', 'hızlı okuma'
        if is_neg_imperative_candidate(curr_cand):
            has_modifier = (
                prev_surf in DETERMINERS or
                is_numeral(prev_surf) or
                prev_surf in COMMON_ADJECTIVES or
                (prev_tok and has_genitive_case(prev_tok))
            )
            if has_modifier:
                vn_id = find_candidate_by_filter(candidates, is_verbal_noun_candidate)
                if vn_id != -1 and vn_id != curr_id:
                    new_id = vn_id
                    changed = True
                    rule_applied = f"Rule 1.1 (Preceding modifier '{prev_surf}' prevents Neg+Imp)"

        # --- Rule 1.2: Postpositional Attachment Constraint ---
        # e.g., 'ödeme için', 'gelişme sonrasında'
        if not changed and is_neg_imperative_candidate(curr_cand):
            if next_surf in POSTPOSITIONS:
                vn_id = find_candidate_by_filter(candidates, is_verbal_noun_candidate)
                if vn_id != -1 and vn_id != curr_id:
                    new_id = vn_id
                    changed = True
                    rule_applied = f"Rule 1.2 (Following postposition '{next_surf}' requires Nominal)"

        # --- Rule 1.3: Casing & Sentence-Initial False Proper Nouns ---
        # If at index 0, capitalized, and selected is Prop, but word is common noun
        if not changed and i == 0:
            is_prop = "Prop" in curr_key
            if is_prop and surface_lower in FALSE_PROPER_NOUNS:
                # Check if it has an apostrophe (e.g. Can'ın -> truly proper)
                if "'" not in surface and "’" not in surface:
                    # Find non-proper candidate (common noun or adj)
                    common_id = find_candidate_by_filter(candidates, lambda c: "Prop" not in c.get("zemberek_key", ""))
                    if common_id != -1 and common_id != curr_id:
                        new_id = common_id
                        changed = True
                        rule_applied = f"Rule 1.3 (Sentence-initial capitalization artifact for common word '{surface}')"

        # --- Rule 1.4: Fixed Lexical Collocations & Idioms ---
        # Collocation 1: 'kaleme almak' (mistagged as 'kale:Noun+me:P1sg')
        if not changed and surface_lower == "kaleme" and "kalem:Noun" not in curr_key:
            if next_surf.startswith("al") or next_surf.startswith("alın"):
                kalem_id = find_candidate_by_filter(candidates, lambda c: "kalem:Noun" in c.get("zemberek_key", ""))
                if kalem_id != -1 and kalem_id != curr_id:
                    new_id = kalem_id
                    changed = True
                    rule_applied = f"Rule 1.4 (Idiom: 'kaleme almak' requires kalem:Noun)"

        # Collocation 2: 'bu yana' / 'o zamandan bu yana' (mistagged as 'yan:Noun+a:Dat')
        if not changed and surface_lower == "yana" and "yana:Postp" not in curr_key:
            if prev_surf == "bu":
                yana_postp_id = find_candidate_by_filter(candidates, lambda c: "yana:Postp" in c.get("zemberek_key", ""))
                if yana_postp_id != -1 and yana_postp_id != curr_id:
                    new_id = yana_postp_id
                    changed = True
                    rule_applied = f"Rule 1.4 (Idiom: 'bu yana' requires yana:Postp)"

        # Collocation 3: 'kan basıncı' (mistagged as 'kanmak:Verb')
        if not changed and surface_lower == "kan" and "kan:Noun" not in curr_key:
            if next_surf.startswith("basınç"):
                kan_noun_id = find_candidate_by_filter(candidates, lambda c: "kan:Noun" in c.get("zemberek_key", ""))
                if kan_noun_id != -1 and kan_noun_id != curr_id:
                    new_id = kan_noun_id
                    changed = True
                    rule_applied = f"Rule 1.4 (Collocation: 'kan basıncı' requires kan:Noun)"

        # Collocation 4: 'kalp atışı' (mistagged as reciprocal verb 'Recip')
        if not changed and surface_lower.startswith("atış") and "Recip" in curr_key:
            if prev_surf == "kalp":
                atis_noun_id = find_candidate_by_filter(candidates, lambda c: "Inf3→Noun" in c.get("zemberek_key", "") or "atış:Noun" in c.get("zemberek_key", ""))
                if atis_noun_id != -1 and atis_noun_id != curr_id:
                    new_id = atis_noun_id
                    changed = True
                    rule_applied = f"Rule 1.4 (Collocation: 'kalp atışı' requires atış:Noun)"

        # --- Rule 1.5: Passive / Auxiliary Verb Subject Constraint ---
        # A negative imperative (e.g. 'ödeme!', 'açıklama!') can NEVER be the subject of a passive/auxiliary verb
        # such as 'yapılmak', 'edilmek', 'gerçekleşmek' (e.g. 'ödeme yapılacak', 'açıklama yapıldı').
        if not changed and is_neg_imperative_candidate(curr_cand):
            if next_surf.startswith(("yapıl", "edil", "gerçekleş", "kaydedil")):
                vn_id = find_candidate_by_filter(candidates, is_verbal_noun_candidate)
                if vn_id != -1 and vn_id != curr_id:
                    new_id = vn_id
                    changed = True
                    rule_applied = f"Rule 1.5 (Passive auxiliary '{next_surf}' requires Verbal Noun)"

        if changed:
            new_cand = next(c for c in candidates if c.get("id") == new_id)
            old_key = curr_cand.get("zemberek_key", "")
            new_key = new_cand.get("zemberek_key", "")
            token["selected_candidate_id"] = new_id
            token["sanitized_rule"] = rule_applied
            token["original_selected_id"] = curr_id
            changes += 1
            change_logs.append(
                f"  [{token['index']}] '{surface}': {old_key} -> {new_key} ({rule_applied})"
            )

    return changes, change_logs


def process_dataset(input_file: str, output_file: str):
    print(f"[INFO] Reading from: {input_file}")
    total_sentences = 0
    total_tokens = 0
    total_ambiguous = 0
    total_changes = 0
    modified_sentences = 0

    all_logs = []

    with open(input_file, "r", encoding="utf-8") as in_f, \
         open(output_file, "w", encoding="utf-8") as out_f:

        for line_num, line in enumerate(in_f):
            line = line.strip()
            if not line:
                continue

            record = json.loads(line)
            total_sentences += 1
            tokens = record.get("tokens", [])
            total_tokens += len(tokens)
            total_ambiguous += sum(1 for t in tokens if t.get("is_ambiguous", False))

            changes, logs = sanitize_sentence(record)
            if changes > 0:
                total_changes += changes
                modified_sentences += 1
                if len(all_logs) < 25:
                    all_logs.append(f"Sentence #{record.get('sentence_id', line_num)}: \"{record.get('text', '')}\"\n" + "\n".join(logs))

            out_f.write(json.dumps(record, ensure_ascii=False) + "\n")

    print("\n==================================================================")
    print("           STEP 1 SANITIZATION SUMMARY                            ")
    print("==================================================================")
    print(f"Total Sentences Processed:  {total_sentences:,}")
    print(f"Total Tokens:               {total_tokens:,}")
    print(f"Ambiguous Tokens:           {total_ambiguous:,}")
    print(f"Total Labels Corrected:     {total_changes:,}")
    print(f"Sentences Modified:         {modified_sentences:,} ({modified_sentences/max(1, total_sentences)*100:.2f}%)")
    print("==================================================================\n")

    if all_logs:
        print("Sample Corrections:")
        for log in all_logs[:10]:
            print(log)
            print("-" * 60)

    print(f"[DONE] Cleaned dataset written to: {output_file}")


def main():
    parser = argparse.ArgumentParser(description="Sanitize training JSONL using deterministic linguistic invariants.")
    parser.add_argument("-i", "--input", required=True, help="Input raw annotated JSONL file")
    parser.add_argument("-o", "--output", required=True, help="Output sanitized JSONL file")
    args = parser.parse_args()

    process_dataset(args.input, args.output)


if __name__ == "__main__":
    main()
