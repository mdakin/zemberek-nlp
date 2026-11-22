#!/usr/bin/env python3
"""
prepare_native_comparison_dataset.py

Prepares a double-blind cross-format morphological adjudication dataset comparing
standard CoNLL-U annotations against native morpheme-chain analyses.

Formats compared:
- [Format: CoNLL-U]: LEMMA=... | UPOS=... | FEATS=...
- [Format: Morpheme-Chain]: [item:POS] root:POS+affixes | deriv:POS+affixes

Double-blind properties:
- Neither 'UD' nor 'Zemberek' is ever mentioned in candidate strings or prompts.
- Only format descriptors ([Format: CoNLL-U] vs [Format: Morpheme-Chain]) are indicated.
- 50/50 randomized coin-flip assigns Gold and Model to Candidate 1 and Candidate 2.
- Secret metadata `gold_cand_id` (1 or 2) is retained strictly for un-shuffling after evaluation.
"""

import sys
import json
import random
import argparse
from pathlib import Path
from typing import Dict, Any, List, Optional


def parse_conllu(path: Path) -> List[Dict[str, Any]]:
    """Parses a CoNLL-U file into structured sentences and word tokens, including MISC Native."""
    sentences = []
    curr_tokens = []
    sent_id = None
    text = None
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                if curr_tokens:
                    sentences.append({"sent_id": sent_id, "text": text, "tokens": curr_tokens})
                    curr_tokens = []
                    sent_id = None
                    text = None
                continue
            if line.startswith("# sent_id ="):
                sent_id = line.split("=", 1)[1].strip()
            elif line.startswith("# text ="):
                text = line.split("=", 1)[1].strip()
            elif not line.startswith("#"):
                parts = line.split("\t")
                # Exclude Multi-Word Token (MWT) ranges (e.g. 1-2) and empty nodes (e.g. 1.1)
                if "-" not in parts[0] and "." not in parts[0]:
                    misc = parts[9] if len(parts) > 9 else "_"
                    native_str = ""
                    # Robust extraction of Native morpheme chain without splitting on morpheme group delimiter '|'
                    if misc.startswith("Native="):
                        native_str = misc[len("Native="):].replace("§", " ")
                    elif "Native=" in misc:
                        for p in misc.split("|"):
                            if p.startswith("Native="):
                                native_str = p[len("Native="):].replace("§", " ")
                                break

                    curr_tokens.append({
                        "id": int(parts[0]),
                        "form": parts[1],
                        "lemma": parts[2],
                        "upos": parts[3],
                        "feats": parts[5],
                        "native": native_str
                    })
        if curr_tokens:
            sentences.append({"sent_id": sent_id, "text": text, "tokens": curr_tokens})
    return sentences


def canonical_repr(lemma: str, upos: str, feats: str) -> str:
    """Produces canonical representation with alphabetically sorted features and stripped XPOS."""
    if not feats or feats == "_":
        sorted_feats = "_"
    else:
        sorted_feats = "|".join(sorted(feats.split("|")))
    return f"LEMMA={lemma} | UPOS={upos} | FEATS={sorted_feats}"


def is_punctuation_token(upos: str, form: str) -> bool:
    """Checks if a token is punctuation (non-ambiguous morphology)."""
    if upos == "PUNCT":
        return True
    return all(not c.isalnum() for c in form)


def build_native_comparison_dataset(
    boun_gold: Path, boun_pred: Path,
    imst_gold: Path, imst_pred: Path,
    output_path: Path,
    control_boun: int = 50,
    control_imst: int = 25,
    seed: int = 42
):
    rng = random.Random(seed)

    treebanks = [
        ("BOUN", boun_gold, boun_pred, control_boun),
        ("IMST", imst_gold, imst_pred, control_imst),
    ]

    out_records = []
    stats = {}

    for tb_name, gold_p, pred_p, num_control in treebanks:
        gold_sents = parse_conllu(gold_p)
        pred_sents = parse_conllu(pred_p)

        tb_stats = {
            "total_sentences": len(gold_sents),
            "disputed_sentences": 0,
            "control_sentences": 0,
            "total_tokens": 0,
            "punctuation_tokens": 0,
            "identical_consensus_tokens": 0,
            "disputed_word_tokens": 0
        }

        disputed_items = []
        agreed_candidates = []

        for gs, ps in zip(gold_sents, pred_sents):
            disputed_tokens = []
            agreed_word_tokens = []

            for gt, pt in zip(gs["tokens"], ps["tokens"]):
                tb_stats["total_tokens"] += 1

                # 1. Punctuation filter
                if is_punctuation_token(gt["upos"], gt["form"]) and is_punctuation_token(pt["upos"], pt["form"]):
                    tb_stats["punctuation_tokens"] += 1
                    continue

                g_rep = canonical_repr(gt["lemma"], gt["upos"], gt["feats"])
                p_rep = canonical_repr(pt["lemma"], pt["upos"], pt["feats"])
                native_rep = pt.get("native") or p_rep

                # Formats: CoNLL-U feature string vs. Morpheme chain (no brand names)
                gold_cand_str = f"[Format: CoNLL-U] {g_rep}"
                model_cand_str = f"[Format: Morpheme-Chain] {native_rep}"

                # 2. Consensus check
                if g_rep == p_rep:
                    tb_stats["identical_consensus_tokens"] += 1
                    agreed_word_tokens.append((gt["id"], gt["form"], gold_cand_str, model_cand_str, g_rep, p_rep, native_rep))
                else:
                    tb_stats["disputed_word_tokens"] += 1
                    # 3. Double-blind coin flip (50% Gold as Cand 1, 50% Model as Cand 1)
                    if rng.random() < 0.5:
                        cand1, cand2 = gold_cand_str, model_cand_str
                        gold_cand_id = 1
                    else:
                        cand1, cand2 = model_cand_str, gold_cand_str
                        gold_cand_id = 2

                    disputed_tokens.append({
                        "index": gt["id"],
                        "surface": gt["form"],
                        "cand1": cand1,
                        "cand2": cand2,
                        "gold_cand_id": gold_cand_id,
                        "gold_conllu": g_rep,
                        "model_conllu": p_rep,
                        "native_parse": native_rep
                    })

            if disputed_tokens:
                disputed_items.append({
                    "sentence_id": gs["sent_id"],
                    "treebank": tb_name,
                    "text": gs["text"],
                    "is_control": False,
                    "consensus_tokens": len(agreed_word_tokens),
                    "tokens": disputed_tokens
                })
            elif agreed_word_tokens:
                # Agreed control sentence: select up to 2 agreed tokens for verification
                sample_tokens = []
                for tid, form, g_cand, m_cand, grep, prep, n_rep in agreed_word_tokens[:2]:
                    if rng.random() < 0.5:
                        c1, c2 = g_cand, m_cand
                        gid = 1
                    else:
                        c1, c2 = m_cand, g_cand
                        gid = 2
                    sample_tokens.append({
                        "index": tid,
                        "surface": form,
                        "cand1": c1,
                        "cand2": c2,
                        "gold_cand_id": gid,
                        "gold_conllu": grep,
                        "model_conllu": prep,
                        "native_parse": n_rep
                    })
                agreed_candidates.append({
                    "sentence_id": gs["sent_id"],
                    "treebank": tb_name,
                    "text": gs["text"],
                    "is_control": True,
                    "tokens": sample_tokens
                })

        tb_stats["disputed_sentences"] = len(disputed_items)
        out_records.extend(disputed_items)

        # Select control sentences
        rng.shuffle(agreed_candidates)
        control_selection = agreed_candidates[:num_control]
        tb_stats["control_sentences"] = len(control_selection)
        out_records.extend(control_selection)

        stats[tb_name] = tb_stats

    # Mix records across treebanks
    rng.shuffle(out_records)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        for rec in out_records:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")

    summary_path = output_path.with_name(output_path.stem + "_summary.json")
    with open(summary_path, "w", encoding="utf-8") as f:
        json.dump({
            "total_sentences_selected": len(out_records),
            "stats": stats
        }, f, indent=2)

    print("Cross-Format Double-Blind Dataset Ready:")
    print(f"  Total Sentences Selected: {len(out_records)}")
    for tb, s in stats.items():
        print(f"  {tb}:")
        print(f"    Total Sentences: {s['total_sentences']}")
        print(f"    Disputed Sentences: {s['disputed_sentences']}")
        print(f"    Control Sentences: {s['control_sentences']}")
        print(f"    Total Tokens: {s['total_tokens']}")
        print(f"    Punctuation (Skipped): {s['punctuation_tokens']} ({s['punctuation_tokens']/s['total_tokens']*100:.1f}%)")
        print(f"    Identical Consensus (Auto-Pass): {s['identical_consensus_tokens']} ({s['identical_consensus_tokens']/s['total_tokens']*100:.1f}%)")
        print(f"    Disputed Words (To Adjudicate): {s['disputed_word_tokens']} ({s['disputed_word_tokens']/s['total_tokens']*100:.1f}%)")
    print(f"  Output JSONL: {output_path}")
    print(f"  Summary JSON: {summary_path}")


def main():
    script_dir = Path(__file__).resolve().parent
    default_gold_dir = script_dir / "data" / "gold"
    default_pred_dir = script_dir / "data" / "predicted"

    parser = argparse.ArgumentParser(description="Prepare cross-format double-blind adjudication dataset directly from CoNLL-U and native morpheme parses.")
    parser.add_argument("--boun-gold", type=Path, default=default_gold_dir / "tr_boun-ud-test.conllu")
    parser.add_argument("--boun-pred", type=Path, default=default_pred_dir / "boun_predicted_distilled-33k-th20k.conllu")
    parser.add_argument("--imst-gold", type=Path, default=default_gold_dir / "tr_imst-ud-test.conllu")
    parser.add_argument("--imst-pred", type=Path, default=default_pred_dir / "imst_predicted_distilled-33k-th20k.conllu")
    parser.add_argument("--output", type=Path, default=script_dir / "data" / "adjudication_dataset_native_vs_gold.jsonl")
    parser.add_argument("--control-boun", type=int, default=50)
    parser.add_argument("--control-imst", type=int, default=25)
    args = parser.parse_args()

    build_native_comparison_dataset(
        args.boun_gold, args.boun_pred,
        args.imst_gold, args.imst_pred,
        args.output,
        args.control_boun, args.control_imst
    )


if __name__ == "__main__":
    main()
