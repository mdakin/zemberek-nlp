#!/usr/bin/env python3
"""
generate_compact_adjudication_gist.py

Generates a compact, highly readable markdown analysis of all 1,373 adjudicated sentences
(3,570 ambiguous tokens) across UD Turkish-BOUN and UD Turkish-IMST.

Outputs:
1. data/ambiguity/adjudication_logs/INDEX.md: Executive summary, statistics, and guide.
2. data/ambiguity/adjudication_logs/UD_BOUN_ADJUDICATIONS.md: All 684 BOUN sentences with token evaluations.
3. data/ambiguity/adjudication_logs/UD_IMST_ADJUDICATIONS.md: All 689 IMST sentences with token evaluations.
"""

import json
from pathlib import Path
from collections import Counter


def format_sentence_block(s, num, dataset_tokens):
    tb = s["treebank"]
    sid = s["sentence_id"]
    cat = s["category"]
    text = s["text"]

    lines = []
    lines.append(f"### {num}. `{sid}` ({cat})")
    lines.append(f"> *\"{text}\"*\n")

    for t in s["tokens"]:
        idx = t["index"]
        surf = t["surface"]
        best = t["best_choice"]
        ge = t["golden_eval"]
        oe = t["old_eval"]
        ne = t["new_eval"]
        g_key = t["gold_zemberek_key"]
        o_key = t["old_zemberek_key"]
        n_key = t["new_zemberek_key"]
        reason = t["reason"]

        c_info = dataset_tokens.get((tb, sid, idx), {})
        g_conll = c_info.get("gold_conllu", "")
        o_conll = c_info.get("old_conllu", "")
        n_conll = c_info.get("new_conllu", "")
        g_conll = c_info.get("gold_conllu", "N/A")
        o_conll = c_info.get("old_conllu", "N/A")
        n_conll = c_info.get("new_conllu", "N/A")

        status = f"Gold: {ge} | Old: {oe} | New: {ne}"
        lines.append(f"- **#{idx} `{surf}`** → **Best: `{best}`** ({status})")

        if o_key == g_key and n_key != g_key:
            lines.append(f"  - *Gold & Old:* `{g_key}`")
            lines.append(f"  - *New Model:*  `{n_key}`")
        elif o_key == n_key and g_key != n_key:
            lines.append(f"  - *Gold Treebank:* `{g_key}`")
            lines.append(f"  - *Both Models:*   `{n_key}`")
        elif o_key == g_key and n_key == g_key:
            lines.append(f"  - *All Three Agree:* `{n_key}`")
        lines.append(f"  - *Gold CoNLL-U:* `{g_conll}` *(Mapped: `{g_key}`)*")
        if o_key == n_key:
            lines.append(f"  - *Both Models:*  `{n_key}` *(CoNLL-U: `{n_conll}`)*")
        if o_key == n_key and o_conll == n_conll:
            lines.append(f"  - *Both Models:* `{n_conll}` *(Key: `{n_key}`)*")
        else:
            lines.append(f"  - *Gold:* `{g_key}`")
            lines.append(f"  - *Old:*  `{o_key}`")
            lines.append(f"  - *New:*  `{n_key}`")
            lines.append(f"  - *Old Zemberek:* `{o_key}` *(CoNLL-U: `{o_conll}`)*")
            lines.append(f"  - *New Zemberek:* `{n_key}` *(CoNLL-U: `{n_conll}`)*")

            lines.append(f"  - *Old Model:* `{o_conll}` *(Key: `{o_key}`)*")
            lines.append(f"  - *New Model:* `{n_conll}` *(Key: `{n_key}`)*")
        lines.append(f"  - *Reason:* {reason}")

    lines.append("")
    return "\n".join(lines)


def main():
    parsed_path = Path("data/ambiguity/adjudication_logs/adjudications_parsed.jsonl")
    with open(parsed_path, "r", encoding="utf-8") as f:
        parsed = [json.loads(line) for line in f]

    dataset_tokens = {}
    with open("data/ambiguity/adjudication_dataset_full.jsonl", "r", encoding="utf-8") as f:
        for line in f:
            item = json.loads(line)
            tb = item["treebank"]
            sid = item["sentence_id"]
            for t in item["tokens"]:
                dataset_tokens[(tb, sid, t["index"])] = {
                    "gold_conllu": t.get("gold_conllu", ""),
                    "old_conllu": t.get("old_conllu", ""),
                    "new_conllu": t.get("new_conllu", "")
                }

    boun_sents = [s for s in parsed if s["treebank"] == "BOUN"]
    imst_sents = [s for s in parsed if s["treebank"] == "IMST"]

    print(f"Loaded {len(parsed)} sentences: {len(boun_sents)} BOUN + {len(imst_sents)} IMST.")

    out_dir = Path("data/ambiguity/adjudication_logs/gist_full_analysis")
    out_dir.mkdir(parents=True, exist_ok=True)

    # 1. Format BOUN
    boun_lines = [
        "# UD Turkish-BOUN: Full Adjudication Analysis (684 Sentences)",
        "",
        f"**Treebank:** UD Turkish-BOUN (v2.14)  ",
        f"**Adjudicated Sentences:** {len(boun_sents)} (584 Error Sentences + 100 Control Sentences)  ",
        f"**Total Tokens:** {sum(len(s['tokens']) for s in boun_sents)} ambiguous tokens  ",
        "**Adjudicator:** Gemini 3.8 Flash (`thinkingBudget: 512`, `temperature: 0.0`)  ",
        "",
        "---",
        ""
    ]
    for i, s in enumerate(boun_sents, 1):
        boun_lines.append(format_sentence_block(s, i))
        boun_lines.append(format_sentence_block(s, i, dataset_tokens))

    boun_file = out_dir / "UD_BOUN_ADJUDICATIONS.md"
    with open(boun_file, "w", encoding="utf-8") as f:
        f.write("\n".join(boun_lines))
    print(f"Wrote {boun_file} ({len(boun_file.read_bytes())} bytes)")

    # 2. Format IMST
    imst_lines = [
        "# UD Turkish-IMST: Full Adjudication Analysis (689 Sentences)",
        "",
        f"**Treebank:** UD Turkish-IMST (v2.14)  ",
        f"**Adjudicated Sentences:** {len(imst_sents)} (589 Error Sentences + 100 Control Sentences)  ",
        f"**Total Tokens:** {sum(len(s['tokens']) for s in imst_sents)} ambiguous tokens  ",
        "**Adjudicator:** Gemini 3.8 Flash (`thinkingBudget: 512`, `temperature: 0.0`)  ",
        "",
        "---",
        ""
    ]
    for i, s in enumerate(imst_sents, 1):
        imst_lines.append(format_sentence_block(s, i))
        imst_lines.append(format_sentence_block(s, i, dataset_tokens))

    imst_file = out_dir / "UD_IMST_ADJUDICATIONS.md"
    with open(imst_file, "w", encoding="utf-8") as f:
        f.write("\n".join(imst_lines))
    print(f"Wrote {imst_file} ({len(imst_file.read_bytes())} bytes)")

    # 3. Format Index / Overview
    # Aggregate statistics
    token_counts = Counter()
    best_counts = Counter()
    gold_counts = Counter()
    old_counts = Counter()
    new_counts = Counter()

    for s in parsed:
        for t in s["tokens"]:
            token_counts["total"] += 1
            best_counts[t["best_choice"]] += 1
            gold_counts[t["golden_eval"]] += 1
            old_counts[t["old_eval"]] += 1
            new_counts[t["new_eval"]] += 1

    index_lines = [
        "# Complete Adjudication Analysis: 1,373 Turkish Treebank Sentences",
        "",
        "**Date:** September 24, 2026  ",
        "**Scope:** 100% Census of all sentences where the New Model differed from the Universal Dependencies ground truth, plus a 200-sentence stratified control sample.  ",
        "**Total Evaluated:** 1,373 sentences (3,570 ambiguous tokens) across UD Turkish-BOUN and UD Turkish-IMST.  ",
        "**Adjudication Engine:** Gemini 3.8 Flash (`thinkingBudget: 512`, `temperature: 0.0`) evaluating Golden, Old Zemberek, and New Zemberek independently.  ",
        "",
        "---",
        "",
        "## 1. Executive Statistics Across All 3,570 Evaluated Tokens",
        "",
        "| Evaluation Metric | Golden Treebank Ground Truth | Old Zemberek (`model-compressed`) | New Zemberek (`model-distilled-33k`) |",
        "| :--- | :---: | :---: | :---: |",
        f"| **`CORRECT` (Strictly Valid)** | {gold_counts['CORRECT']} ({gold_counts['CORRECT']/token_counts['total']*100:.1f}%) | {old_counts['CORRECT']} ({old_counts['CORRECT']/token_counts['total']*100:.1f}%) | **{new_counts['CORRECT']} ({new_counts['CORRECT']/token_counts['total']*100:.1f}%)** |",
        f"| **`VALID_ALT` (Acceptable Twin)** | {gold_counts['VALID_ALT']} ({gold_counts['VALID_ALT']/token_counts['total']*100:.1f}%) | {old_counts['VALID_ALT']} ({old_counts['VALID_ALT']/token_counts['total']*100:.1f}%) | {new_counts['VALID_ALT']} ({new_counts['VALID_ALT']/token_counts['total']*100:.1f}%) |",
        f"| **Total Valid (Lenient)** | {gold_counts['CORRECT']+gold_counts['VALID_ALT']} ({(gold_counts['CORRECT']+gold_counts['VALID_ALT'])/token_counts['total']*100:.1f}%) | {old_counts['CORRECT']+old_counts['VALID_ALT']} ({(old_counts['CORRECT']+old_counts['VALID_ALT'])/token_counts['total']*100:.1f}%) | **{new_counts['CORRECT']+new_counts['VALID_ALT']} ({(new_counts['CORRECT']+new_counts['VALID_ALT'])/token_counts['total']*100:.1f}%)** |",
        f"| **`FLAWED` (Erroneous / Archaic)** | {gold_counts['FLAWED']} ({gold_counts['FLAWED']/token_counts['total']*100:.1f}%) | {old_counts['FLAWED']} ({old_counts['FLAWED']/token_counts['total']*100:.1f}%) | **{new_counts['FLAWED']} ({new_counts['FLAWED']/token_counts['total']*100:.1f}%)** |",
        "",
        "### Best Choice Breakdown:",
        f"- **`NEW` is Uniquely Best:** {best_counts['NEW']} tokens ({best_counts['NEW']/token_counts['total']*100:.1f}%)",
        f"- **`BOTH_EQUAL` / Valid Alternatives:** {best_counts['BOTH_EQUAL']} tokens ({best_counts['BOTH_EQUAL']/token_counts['total']*100:.1f}%)",
        f"- **`OLD` is Uniquely Best (Actionable New Errors):** {best_counts['OLD']} tokens ({best_counts['OLD']/token_counts['total']*100:.1f}%)",
        f"- **`GOLDEN` is Uniquely Best:** {best_counts['GOLDEN']} tokens ({best_counts['GOLDEN']/token_counts['total']*100:.1f}%)",
        f"- **`NEITHER`:** {best_counts['NEITHER']} tokens ({best_counts['NEITHER']/token_counts['total']*100:.1f}%)",
        "",
        "---",
        "",
        "## 2. Full Sentence Adjudication Files in this Gist",
        "",
        "This gist contains the complete, token-level analysis for all 1,373 sentences:",
        "1. **`UD_BOUN_ADJUDICATIONS.md`**: All 684 sentences (1,877 ambiguous tokens) from UD Turkish-BOUN.",
        "2. **`UD_IMST_ADJUDICATIONS.md`**: All 689 sentences (1,693 ambiguous tokens) from UD Turkish-IMST.",
        "",
        "Each sentence entry includes:",
        "- Full Turkish sentence context.",
        "- Target ambiguous token surface and index.",
        "- Status evaluations: `Gold`, `Old`, and `New` verdicts (`CORRECT`, `VALID_ALT`, `FLAWED`).",
        "- Raw Zemberek morphological breakdown and CoNLL-U projection.",
        "- Concise linguistic reasoning explaining why the selection is correct or flawed in context.",
        "",
        "---",
        "",
        "## 3. Related Links & Reports",
        "- **Executive Benchmark & Normalization Report:** [Report Gist (467333...)](https://gist.github.com/mdakin/46733360a14cdff5b3b5b27a046baf4c)",
        "- **Test Plan & Methodology:** [Test Plan Gist (f0851bc...)](https://gist.github.com/mdakin/f0851bcbeb433ada3312eb0eaa655de9)",
        "- **Replication Package Quickstart:** [Replication Guide Gist (d9b36e...)](https://gist.github.com/mdakin/d9b36e9eb1df3907a19c883f17fb0bbe)"
    ]

    index_file = out_dir / "INDEX.md"
    with open(index_file, "w", encoding="utf-8") as f:
        f.write("\n".join(index_lines))
    print(f"Wrote {index_file} ({len(index_file.read_bytes())} bytes)")


if __name__ == "__main__":
    main()

