#!/usr/bin/env python3
"""
prepare_full_adjudication_dataset.py

Builds the complete dataset of sentences to adjudicate:
1. ALL sentences where the New Model has >= 1 error according to Gold (1,173 sentences: 584 BOUN + 589 IMST).
2. A balanced control sample of 200 sentences where the New Model is 100% correct:
   - 100 sentences where New is Correct and Old is Wrong (50 BOUN + 50 IMST).
   - 100 sentences where Both New and Old are Correct (50 BOUN + 50 IMST).

For each sentence, extracts full context, CoNLL-U projections, raw Zemberek SingleAnalysis keys,
and dictionary candidates for each target ambiguous token.
For each sentence, extracts full context, 100% exact CoNLL-U projections (via dynamic token alignment),
raw Zemberek SingleAnalysis keys, and dictionary candidates for each target ambiguous token.
"""

import os
import sys
import json
import gzip
import random
from pathlib import Path
from typing import Dict, Any, List, Optional
from typing import Dict, Any, List, Optional, Tuple
from collections import Counter

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))
from experiment.scripts.align_conllu_to_zemberek import parse_conllu, align_tokens_dynamic

def parse_conllu_sentences(path: Path) -> List[Dict[str, Any]]:

def load_canonical_sentences(path: Path) -> List[Dict[str, Any]]:
    sentences = []
    curr_sent_id = None
    curr_text = None
    curr_tokens = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                if curr_sent_id is not None:
                    sentences.append({
                        "sent_id": curr_sent_id,
                        "text": curr_text,
                        "tokens": curr_tokens
                    })
                curr_sent_id = None
                curr_text = None
                curr_tokens = []
                continue
            if line.startswith("#"):
                if line.startswith("# sent_id"):
                    curr_sent_id = line.split("=", 1)[1].strip()
                elif line.startswith("# text"):
                    curr_text = line.split("=", 1)[1].strip()
                continue
            parts = line.split("\t")
            curr_tokens.append(parts)
        if curr_sent_id is not None:
            sentences.append({
                "sent_id": curr_sent_id,
                "text": curr_text,
                "tokens": curr_tokens
            })
            sentences.append(json.loads(line))
    return sentences


def load_canonical_candidates(path: Path) -> List[Dict[int, List[Dict[str, Any]]]]:
    candidates_by_sent = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            s = json.loads(line)
            tokens_cands = {}
            for t in s.get("tokens", []):
                tokens_cands[t["index"]] = t.get("candidates", [])
            candidates_by_sent.append(tokens_cands)
    return candidates_by_sent


def find_matching_conllu_token(conllu_tokens: List[List[str]], surface: str) -> Optional[List[str]]:
    # 1. Exact match on form
    for parts in conllu_tokens:
        if "-" not in parts[0] and parts[1] == surface:
            return parts
    # 2. Case-insensitive match
    for parts in conllu_tokens:
        if "-" not in parts[0] and parts[1].lower() == surface.lower():
            return parts
    # 3. Substring match for MWT heads
    for parts in conllu_tokens:
        if "-" not in parts[0] and (parts[1] in surface or surface in parts[1]):
            return parts
    return None


def sample_stratified_control(
    sentence_indices: List[int],
    sents_data: List[Dict[str, Any]],
    target_count: int,
    seed: int = 42
) -> List[int]:
    rng = random.Random(seed)
    # Group by primary surface form of ambiguous tokens
    surface_map = {}
    for idx in sentence_indices:
        s = sents_data[idx]
        surfs = [t["surface"].lower() for t in s["tokens"] if t["is_ambiguous"]]
        key = surfs[0] if surfs else "unknown"
        if key not in surface_map:
            surface_map[key] = []
        surface_map[key].append(idx)

    selected = []
    # Pick round-robin across surfaces to ensure linguistic diversity
    keys = sorted(surface_map.keys())
    rng.shuffle(keys)
    while len(selected) < target_count and keys:
        next_keys = []
        for k in keys:
            if surface_map[k]:
                chosen = surface_map[k].pop(0)
                selected.append(chosen)
                if len(selected) >= target_count:
                    break
                if surface_map[k]:
                    next_keys.append(k)
        keys = next_keys

    # Remainder fallback
    if len(selected) < target_count:
        rem = [idx for idx in sentence_indices if idx not in selected]
        rng.shuffle(rem)
        selected.extend(rem[:target_count - len(selected)])

    return sorted(selected)


def build_sentence_item(
    treebank: str,
    s_idx: int,
    sent_data: Dict[str, Any],
    gold_conll: Dict[str, Any],
    old_conll: Dict[str, Any],
    new_conll: Dict[str, Any],
    candidates_map: Dict[int, List[Dict[str, Any]]],
    canon_sent: Dict[str, Any],
    gold_conll_sent: List[Dict[str, Any]],
    old_conll_sent: List[Dict[str, Any]],
    new_conll_sent: List[Dict[str, Any]],
    category: str
) -> Optional[Dict[str, Any]]:
    tokens_to_evaluate = []

    # Build dynamic alignment between canonical Zemberek tokens and CoNLL-U tokens
    pairs_g = align_tokens_dynamic(canon_sent["tokens"], gold_conll_sent)
    pairs_o = align_tokens_dynamic(canon_sent["tokens"], old_conll_sent)
    pairs_n = align_tokens_dynamic(canon_sent["tokens"], new_conll_sent)

    aligned_by_idx = {}
    for (zt, gt), (_, ot), (_, nt) in zip(pairs_g, pairs_o, pairs_n):
        if zt:
            aligned_by_idx[zt["index"]] = (zt, gt, ot, nt)

    for t in sent_data["tokens"]:
        if not t["is_ambiguous"]:
            continue

        new_ok = t["trained_matches_gemini"]
        old_ok = t["default_matches_gemini"]

        # Determine token stratum
        if new_ok and old_ok:
            stratum = "BOTH_AGREE_GOLD"
        elif not new_ok and old_ok:
            stratum = "OLD_RIGHT_NEW_WRONG"
        elif new_ok and not old_ok:
            stratum = "NEW_RIGHT_OLD_WRONG"
        else:
            stratum = "BOTH_WRONG"

        # Determine if this token should be evaluated:
        # In NEW_ERROR sentences: evaluate all tokens with errors or disputes
        # In CONTROL_NEW_OK_OLD_ERR sentences: evaluate tokens where Old != Gold
        # In CONTROL_BOTH_OK sentences: evaluate ambiguous tokens
        should_eval = False
        if category == "NEW_ERROR":
            if not new_ok or not old_ok:
                should_eval = True
        elif category == "CONTROL_NEW_OK_OLD_ERR":
            if not old_ok:
                should_eval = True
        elif category == "CONTROL_BOTH_OK":
            should_eval = True

        if not should_eval:
            continue

        surf = t["surface"]
        g_tok = find_matching_conllu_token(gold_conll["tokens"], surf)
        o_tok = find_matching_conllu_token(old_conll["tokens"], surf)
        n_tok = find_matching_conllu_token(new_conll["tokens"], surf)
        t_idx = t["index"]

        g_str = f"LEMMA={g_tok[2]} | UPOS={g_tok[3]} | FEATS={g_tok[5]}" if g_tok else "N/A"
        o_str = f"LEMMA={o_tok[2]} | UPOS={o_tok[3]} | FEATS={o_tok[5]}" if o_tok else "N/A"
        n_str = f"LEMMA={n_tok[2]} | UPOS={n_tok[3]} | FEATS={n_tok[5]}" if n_tok else "N/A"
        zt, gt, ot, nt = aligned_by_idx.get(t_idx, (None, None, None, None))

        cands = candidates_map.get(t["index"], [])
        # 1. Exact gold CoNLL-U representation
        c_reason = zt.get("selection_reasoning", "") if zt else ""
        if "Aligned from UD gold annotation: " in c_reason:
            g_str = c_reason.replace("Aligned from UD gold annotation: ", "").replace(", ", " | ")
        elif gt:
            g_p = gt["parts"][0]
            g_str = f"LEMMA={g_p[2]} | UPOS={g_p[3]} | FEATS={g_p[5]}"
        else:
            g_str = "N/A"

        # 2. Old Model CoNLL-U representation
        if ot:
            o_p = ot["parts"][0]
            o_str = f"LEMMA={o_p[2]} | UPOS={o_p[3]} | FEATS={o_p[5]}"
        else:
            o_str = "N/A"

        # 3. New Model CoNLL-U representation
        if nt:
            n_p = nt["parts"][0]
            n_str = f"LEMMA={n_p[2]} | UPOS={n_p[3]} | FEATS={n_p[5]}"
        else:
            n_str = "N/A"

        cands = zt.get("candidates", []) if zt else []

        tokens_to_evaluate.append({
            "index": t["index"],
            "index": t_idx,
            "surface": surf,
            "stratum": stratum,
            "gold_conllu": g_str,
            "gold_zemberek_key": t["gemini_selected_key"],
            "old_conllu": o_str,
            "old_zemberek_key": t["default_selected_key"],
            "new_conllu": n_str,
            "new_zemberek_key": t["trained_selected_key"],
            "candidates": cands
        })

    if not tokens_to_evaluate:
        return None

    return {
        "treebank": treebank,
        "sentence_id": sent_data["sentence_id"],
        "sentence_idx": s_idx,
        "text": sent_data["text"],
        "category": category,
        "tokens": tokens_to_evaluate
    }


def main():
    print("Preparing full adjudication dataset...")
    print("Preparing full adjudication dataset with exact dynamic programming CoNLL-U alignment...")

    # Load CoNLL-U files
    boun_gold_conll = parse_conllu_sentences(Path("data/ambiguity/sources/UD_Turkish-BOUN/tr_boun-ud-test.conllu"))
    boun_old_conll = parse_conllu_sentences(Path("data/ambiguity/ud_eval_results/boun_Old_Perceptron__compressed_.conllu"))
    boun_new_conll = parse_conllu_sentences(Path("data/ambiguity/ud_eval_results/boun_Fast_Perceptron__model-distilled-33k_.conllu"))
    boun_gold_conll = parse_conllu(Path("data/ambiguity/sources/UD_Turkish-BOUN/tr_boun-ud-test.conllu"))
    boun_old_conll = parse_conllu(Path("data/ambiguity/ud_eval_results/boun_Old_Perceptron__compressed_.conllu"))
    boun_new_conll = parse_conllu(Path("data/ambiguity/ud_eval_results/boun_Fast_Perceptron__model-distilled-33k_.conllu"))

    imst_gold_conll = parse_conllu_sentences(Path("data/ambiguity/sources/UD_Turkish-IMST/tr_imst-ud-test.conllu"))
    imst_old_conll = parse_conllu_sentences(Path("data/ambiguity/ud_eval_results/imst_Old_Perceptron__compressed_.conllu"))
    imst_new_conll = parse_conllu_sentences(Path("data/ambiguity/ud_eval_results/imst_Fast_Perceptron__model-distilled-33k_.conllu"))
    imst_gold_conll = parse_conllu(Path("data/ambiguity/sources/UD_Turkish-IMST/tr_imst-ud-test.conllu"))
    imst_old_conll = parse_conllu(Path("data/ambiguity/ud_eval_results/imst_Old_Perceptron__compressed_.conllu"))
    imst_new_conll = parse_conllu(Path("data/ambiguity/ud_eval_results/imst_Fast_Perceptron__model-distilled-33k_.conllu"))

    # Load candidates
    boun_candidates = load_canonical_candidates(Path("data/ambiguity/sources/UD_Turkish-BOUN/boun_test_canonical.jsonl"))
    imst_candidates = load_canonical_candidates(Path("data/ambiguity/sources/UD_Turkish-IMST/imst_test_canonical.jsonl"))
    # Load canonical datasets
    boun_canon = load_canonical_sentences(Path("data/ambiguity/sources/UD_Turkish-BOUN/boun_test_canonical.jsonl"))
    imst_canon = load_canonical_sentences(Path("data/ambiguity/sources/UD_Turkish-IMST/imst_test_canonical.jsonl"))

    # Load evaluation details
    with open("data/ambiguity/eval_boun_old_vs_new.json", "r", encoding="utf-8") as f:
        boun_eval = json.load(f)
    with open("data/ambiguity/eval_imst_old_vs_new.json", "r", encoding="utf-8") as f:
        imst_eval = json.load(f)

    all_items = []
    stats = Counter()

    for tb_name, eval_data, g_conll, o_conll, n_conll, cands in [
        ("BOUN", boun_eval, boun_gold_conll, boun_old_conll, boun_new_conll, boun_candidates),
        ("IMST", imst_eval, imst_gold_conll, imst_old_conll, imst_new_conll, imst_candidates)
    for tb_name, eval_data, canon_data, g_conll, o_conll, n_conll in [
        ("BOUN", boun_eval, boun_canon, boun_gold_conll, boun_old_conll, boun_new_conll),
        ("IMST", imst_eval, imst_canon, imst_gold_conll, imst_old_conll, imst_new_conll)
    ]:
        sents = eval_data["sentences"]
        new_err_indices = []
        new_ok_old_err_indices = []
        both_ok_indices = []

        for s_idx, s in enumerate(sents):
            has_new_err = any(t["is_ambiguous"] and not t["trained_matches_gemini"] for t in s["tokens"])
            has_old_err = any(t["is_ambiguous"] and not t["default_matches_gemini"] for t in s["tokens"])
            has_ambig = any(t["is_ambiguous"] for t in s["tokens"])

            if not has_ambig:
                continue

            if has_new_err:
                new_err_indices.append(s_idx)
            elif has_old_err:
                new_ok_old_err_indices.append(s_idx)
            else:
                both_ok_indices.append(s_idx)

        # 1. All new error sentences
        for idx in new_err_indices:
            item = build_sentence_item(tb_name, idx, sents[idx], g_conll[idx], o_conll[idx], n_conll[idx], cands[idx], "NEW_ERROR")
            item = build_sentence_item(tb_name, idx, sents[idx], canon_data[idx], g_conll[idx], o_conll[idx], n_conll[idx], "NEW_ERROR")
            if item:
                all_items.append(item)
                stats[f"{tb_name}_NEW_ERROR_SENTS"] += 1
                stats[f"{tb_name}_NEW_ERROR_TOKS"] += len(item["tokens"])

        # 2. Sample 50 New OK, Old Error sentences
        sampled_new_ok_old_err = sample_stratified_control(new_ok_old_err_indices, sents, 50, seed=42)
        for idx in sampled_new_ok_old_err:
            item = build_sentence_item(tb_name, idx, sents[idx], g_conll[idx], o_conll[idx], n_conll[idx], cands[idx], "CONTROL_NEW_OK_OLD_ERR")
            item = build_sentence_item(tb_name, idx, sents[idx], canon_data[idx], g_conll[idx], o_conll[idx], n_conll[idx], "CONTROL_NEW_OK_OLD_ERR")
            if item:
                all_items.append(item)
                stats[f"{tb_name}_CONTROL_NEW_OK_OLD_ERR_SENTS"] += 1
                stats[f"{tb_name}_CONTROL_NEW_OK_OLD_ERR_TOKS"] += len(item["tokens"])

        # 3. Sample 50 Both OK sentences
        sampled_both_ok = sample_stratified_control(both_ok_indices, sents, 50, seed=43)
        for idx in sampled_both_ok:
            item = build_sentence_item(tb_name, idx, sents[idx], g_conll[idx], o_conll[idx], n_conll[idx], cands[idx], "CONTROL_BOTH_OK")
            item = build_sentence_item(tb_name, idx, sents[idx], canon_data[idx], g_conll[idx], o_conll[idx], n_conll[idx], "CONTROL_BOTH_OK")
            if item:
                all_items.append(item)
                stats[f"{tb_name}_CONTROL_BOTH_OK_SENTS"] += 1
                stats[f"{tb_name}_CONTROL_BOTH_OK_TOKS"] += len(item["tokens"])

    out_path = Path("data/ambiguity/adjudication_dataset_full.jsonl")
    with open(out_path, "w", encoding="utf-8") as f:
        for it in all_items:
            f.write(json.dumps(it, ensure_ascii=False) + "\n")

    print(f"Dataset preparation complete. Wrote {len(all_items)} sentences to {out_path}.")
    # Also write compressed gzip version
    out_gz_path = Path("data/ambiguity/adjudication_dataset_full.jsonl.gz")
    with gzip.open(out_gz_path, "wt", encoding="utf-8") as gz:
        for it in all_items:
            gz.write(json.dumps(it, ensure_ascii=False) + "\n")

    print(f"Dataset preparation complete. Wrote {len(all_items)} sentences to {out_path} and {out_gz_path}.")
    print("Dataset Breakdown:")
    for k, v in sorted(stats.items()):
        print(f"  {k}: {v}")
    total_tokens = sum(len(it["tokens"]) for it in all_items)
    print(f"Total tokens across all sentences: {total_tokens}")


if __name__ == "__main__":
    main()

