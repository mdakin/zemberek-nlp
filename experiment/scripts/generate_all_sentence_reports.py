#!/usr/bin/env python3
"""
generate_all_sentence_reports.py

Generates complete, verbatim per-sentence adjudication reports for both
UD Turkish-BOUN and UD Turkish-IMST datasets using:
1. LLM adjudication verdicts and concise explanations
2. Zemberek full-sentence parse in both Zemberek format and Oflazer format
3. Verbatim original CoNLL-U format with full metadata and dependency annotations

Outputs:
  - BOUN_SENTENCE_ADJUDICATIONS.md
  - IMST_SENTENCE_ADJUDICATIONS.md
"""

import os
import sys
import json
from typing import Dict, Any, List, Tuple


def get_conllu_blocks(path: str) -> Dict[str, str]:
    """
    Parses a CoNLL-U file and returns a mapping from sentence text to the verbatim CoNLL-U block.
    Also returns index-based list for fallback mapping.
    """
    text_to_block = {}
    curr_lines = []
    curr_text = None

    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line_str = line.strip()
            if not line_str:
                if curr_lines:
                    block = "".join(curr_lines).strip()
                    if curr_text:
                        text_to_block[curr_text] = block
                    curr_lines = []
                    curr_text = None
                continue
            curr_lines.append(line)
            if line_str.startswith("# text = "):
                curr_text = line_str[9:].strip()

        if curr_lines:
            block = "".join(curr_lines).strip()
            if curr_text:
                text_to_block[curr_text] = block

    return text_to_block


def build_markdown_report(
    jsonl_adjudicated_path: str,
    eval_json_path: str,
    source_canonical_jsonl_path: str,
    conllu_path: str,
    summary_path: str,
    dataset_name: str,
    out_md_path: str
) -> None:
    # 1. Load adjudicated results
    with open(jsonl_adjudicated_path, "r", encoding="utf-8") as f:
        adjudicated_records = [json.loads(line) for line in f if line.strip()]

    # Sort deterministically by sentence_id
    adjudicated_records.sort(key=lambda x: x["sentence_id"])

    # 2. Load summary
    with open(summary_path, "r", encoding="utf-8") as f:
        summary = json.load(f)
    vb = summary.get("verdict_breakdown", {})

    # 3. Load eval json (contains Zemberek's full sentence parse in trained_selected_key)
    with open(eval_json_path, "r", encoding="utf-8") as f:
        eval_data = json.load(f)
    eval_by_sid = {s["sentence_id"]: s for s in eval_data.get("sentences", [])}

    # 4. Load source canonical jsonl (contains candidates with oflazer_style)
    source_by_sid = {}
    with open(source_canonical_jsonl_path, "r", encoding="utf-8") as f:
        for line in f:
            if line.strip():
                s = json.loads(line)
                source_by_sid[s["sentence_id"]] = s

    # 5. Load CoNLL-U blocks
    conllu_blocks = get_conllu_blocks(conllu_path)

    tot_sents = len(adjudicated_records)
    tot_tokens = sum(len(r["adjudicated_tokens"]) for r in adjudicated_records)

    with open(out_md_path, "w", encoding="utf-8") as out:
        out.write(f"# Detailed Sentence-by-Sentence Adjudication & Morphological Cross-Check Report\n")
        out.write(f"## Dataset: {dataset_name}\n\n")
        out.write(f"**Total Sentences with Disagreements:** {tot_sents}  \n")
        out.write(f"**Total Disputed Tokens Evaluated:** {tot_tokens}  \n")
        out.write(f"**Adjudication Model:** Gemini 3.8 Flash (Temperature 0.0)  \n")
        out.write(f"**Cross-Check Data Included:** Full Zemberek Parse (Zemberek format + Oflazer format) and Verbatim CoNLL-U Gold.  \n\n")

        out.write("### Summary of Verdicts\n")
        out.write("| Verdict | Description | Count | Percentage |\n")
        out.write("|:---|:---|:---:|:---:|\n")
        out.write(f"| **ZEMBEREK** | Zemberek was correct (UD/alignment flawed) | {vb.get('ZEMBEREK_WAS_RIGHT', {}).get('count', 0)} | {vb.get('ZEMBEREK_WAS_RIGHT', {}).get('percentage_of_disputed', 0.0):.1f}% |\n")
        out.write(f"| **BOTH** | Both are valid linguistic alternatives / twins | {vb.get('BOTH_VALID_TWINS', {}).get('count', 0)} | {vb.get('BOTH_VALID_TWINS', {}).get('percentage_of_disputed', 0.0):.1f}% |\n")
        out.write(f"| **UD** | UD was correct (Genuine Zemberek error) | {vb.get('UD_WAS_RIGHT', {}).get('count', 0)} | {vb.get('UD_WAS_RIGHT', {}).get('percentage_of_disputed', 0.0):.1f}% |\n")
        out.write(f"| **NEITHER** | Neither analysis was correct | {vb.get('NEITHER_VALID', {}).get('count', 0)} | {vb.get('NEITHER_VALID', {}).get('percentage_of_disputed', 0.0):.1f}% |\n\n")
        out.write("---\n\n")
        out.write("## Sentence-by-Sentence Reports\n\n")

        for idx, rec in enumerate(adjudicated_records, 1):
            sid = rec["sentence_id"]
            text = rec["text"]
            disputed_tokens = rec["adjudicated_tokens"]

            out.write(f"### {idx}. Sentence `{sid}`\n\n")
            out.write(f"> **\"{text}\"**\n\n")

            # 1. Disputed Tokens & Adjudication
            out.write("#### Disputed Tokens & Adjudication:\n")
            for t in disputed_tokens:
                t_idx = t["index"]
                surf = t["surface"].strip()
                ud_k = t["ud_key"].strip()
                zm_k = t["zemberek_key"].strip()
                verdict = t["verdict"]
                reason = t["reason"].strip()

                if verdict == "ZEMBEREK":
                    v_display = "🟢 **ZEMBEREK (Correct)**"
                elif verdict == "BOTH":
                    v_display = "🟡 **BOTH (Valid Twin)**"
                elif verdict == "UD":
                    v_display = "🔴 **UD (Zemberek Error)**"
                elif verdict == "NEITHER":
                    v_display = "⚪ **NEITHER**"
                else:
                    v_display = f"**{verdict}**"

                out.write(f"- **Token #{t_idx}: `{surf}`** — {v_display}\n")
                out.write(f"  - **UD Gold:** `{ud_k}`\n")
                out.write(f"  - **Zemberek:** `{zm_k}`\n")
                out.write(f"  - **Explanation:** *{reason}*\n")
            out.write("\n")

            # 2. Zemberek Full Sentence Parse (Every token in Zemberek format + Oflazer format)
            eval_sent = eval_by_sid.get(sid, {})
            source_sent = source_by_sid.get(sid, {})
            eval_tokens = eval_sent.get("tokens", [])
            source_tokens = source_sent.get("tokens", [])

            out.write("#### Zemberek Full Sentence Parse:\n")
            for e_tok in eval_tokens:
                t_idx = e_tok["index"]
                surf = e_tok["surface"]
                z_parse = e_tok.get("trained_selected_key", "")

                # Resolve oflazer style
                oflazer = "NONE"
                if t_idx < len(source_tokens):
                    s_tok = source_tokens[t_idx]
                    for c in s_tok.get("candidates", []):
                        if c.get("zemberek_key") == z_parse:
                            oflazer = c.get("oflazer_style", "NONE")
                            break
                    if oflazer == "NONE" and s_tok.get("candidates"):
                        oflazer = s_tok["candidates"][0].get("oflazer_style", "NONE")

                out.write(f"- `#{t_idx}` **{surf}**: `{z_parse}`  \n  *Oflazer:* `{oflazer}`\n")
            out.write("\n")

            # 3. Verbatim Original CoNLL-U Block
            conllu_block = conllu_blocks.get(text, "")
            if conllu_block:
                out.write("#### Original CoNLL-U Format:\n")
                out.write("```conllu\n")
                out.write(conllu_block + "\n")
                out.write("```\n\n")

            out.write("---\n\n")

    print(f"Generated {out_md_path} ({tot_sents} sentences, {tot_tokens} disputed tokens).")


def main():
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))

    # BOUN paths
    boun_adj_jsonl = os.path.join(root, "data/ambiguity/boun_test_adjudication_results.jsonl")
    boun_eval_json = os.path.join(root, "evaluation_head_boun_canonical.json")
    boun_src_jsonl = os.path.join(root, "data/ambiguity/sources/UD_Turkish-BOUN/boun_test_canonical.jsonl")
    boun_conllu = os.path.join(root, "data/ambiguity/sources/UD_Turkish-BOUN/tr_boun-ud-test.conllu")
    boun_summary = os.path.join(root, "data/ambiguity/boun_test_adjudication_summary.json")
    boun_out_md = os.path.join(root, "BOUN_SENTENCE_ADJUDICATIONS.md")

    # IMST paths
    imst_adj_jsonl = os.path.join(root, "data/ambiguity/imst_test_adjudication_results.jsonl")
    imst_eval_json = os.path.join(root, "evaluation_head_imst_canonical.json")
    imst_src_jsonl = os.path.join(root, "data/ambiguity/sources/UD_Turkish-IMST/imst_test_canonical.jsonl")
    imst_conllu = os.path.join(root, "data/ambiguity/sources/UD_Turkish-IMST/tr_imst-ud-test.conllu")
    imst_summary = os.path.join(root, "data/ambiguity/imst_test_adjudication_summary.json")
    imst_out_md = os.path.join(root, "IMST_SENTENCE_ADJUDICATIONS.md")

    print("Building full BOUN report (with CoNLL-U and full Zemberek/Oflazer parses)...")
    build_markdown_report(
        boun_adj_jsonl,
        boun_eval_json,
        boun_src_jsonl,
        boun_conllu,
        boun_summary,
        "UD Turkish-BOUN Test Set",
        boun_out_md
    )

    print("Building full IMST report (with CoNLL-U and full Zemberek/Oflazer parses)...")
    build_markdown_report(
        imst_adj_jsonl,
        imst_eval_json,
        imst_src_jsonl,
        imst_conllu,
        imst_summary,
        "UD Turkish-IMST Test Set",
        imst_out_md
    )


if __name__ == "__main__":
    main()
