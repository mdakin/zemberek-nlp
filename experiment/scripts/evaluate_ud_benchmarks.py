#!/usr/bin/env python3
"""
Automated benchmark runner for evaluating Zemberek models on Universal Dependencies
(BOUN and IMST) using native ExportConllu and official conll18_ud_eval.py.
"""

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Dict, Any, List, Optional


PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
EVAL_SCRIPT = PROJECT_ROOT / "experiment" / "scripts" / "conll18_ud_eval.py"

DATASETS = {
    "BOUN": PROJECT_ROOT / "data" / "ambiguity" / "sources" / "UD_Turkish-BOUN" / "tr_boun-ud-test.conllu",
    "IMST": PROJECT_ROOT / "data" / "ambiguity" / "sources" / "UD_Turkish-IMST" / "tr_imst-ud-test.conllu",
}


def run_export(conllu_path: Path, model_path: Optional[Path], output_path: Path, is_legacy: bool = False) -> Dict[str, Any]:
    """Runs the Java ExportConllu tool via Maven exec."""
    args = [f"--conllu", str(conllu_path), f"--output", str(output_path)]
    if is_legacy:
        args.append("--legacy")
    if model_path:
        args.extend([f"--model", str(model_path)])

    args_str = " ".join(args)
    cmd = [
        "mvn", "-q", "exec:java", "-pl", "experiment",
        "-Dexec.mainClass=zemberek.morphology.ambiguity.dataset.ExportConllu",
        f"-Dexec.args={args_str}"
    ]

    print(f"Running ExportConllu ({'Legacy' if is_legacy else 'Fast'}) -> {output_path.name} ...")
    res = subprocess.run(cmd, cwd=PROJECT_ROOT, capture_output=True, text=True)
    if res.returncode != 0:
        print(f"Error running ExportConllu:\n{res.stderr}\n{res.stdout}", file=sys.stderr)
        sys.exit(1)

    diag_path = Path(str(output_path) + ".diagnostics.json")
    if diag_path.exists():
        with open(diag_path, "r", encoding="utf-8") as f:
            return json.load(f)
    return {}


def compute_joint_upos_ufeats(gold_path: Path, pred_path: Path) -> float:
    """Computes exact-match Joint UPOS + UFeats accuracy (independent of language-proprietary XPOS)."""
    with open(gold_path, "r", encoding="utf-8") as f:
        gold = [l.strip().split("\t") for l in f if l.strip() and not l.startswith("#") and "-" not in l.split("\t")[0]]
    with open(pred_path, "r", encoding="utf-8") as f:
        pred = [l.strip().split("\t") for l in f if l.strip() and not l.startswith("#") and "-" not in l.split("\t")[0]]
    if len(gold) != len(pred) or len(gold) == 0:
        return 0.0
    match = sum(1 for g, p in zip(gold, pred) if g[3] == p[3] and g[5] == p[5])
    return (match / len(gold)) * 100.0


def run_conll18_eval(gold_path: Path, pred_path: Path) -> Dict[str, float]:
    """Runs conll18_ud_eval.py and parses the F1 scores."""
    cmd = [sys.executable, str(EVAL_SCRIPT), "-v", str(gold_path), str(pred_path)]
    res = subprocess.run(cmd, cwd=PROJECT_ROOT, capture_output=True, text=True)
    if res.returncode != 0:
        print(f"Error running conll18_ud_eval.py:\n{res.stderr}\n{res.stdout}", file=sys.stderr)
        sys.exit(1)

    metrics = {}
    for line in res.stdout.splitlines():
        parts = [p.strip() for p in line.split("|")]
        if len(parts) >= 4 and parts[0] in ["Tokens", "Sentences", "Words", "UPOS", "XPOS", "UFeats", "AllTags", "Lemmas", "UAS", "LAS", "CLAS", "MLAS", "BLEX"]:
            try:
                metrics[parts[0]] = float(parts[3])
                metrics[parts[0] + "_Precision"] = float(parts[1])
                metrics[parts[0] + "_Recall"] = float(parts[2])
            except ValueError:
                pass
    metrics["UPOS+Feats"] = compute_joint_upos_ufeats(gold_path, pred_path)
    return metrics


def main():
    parser = argparse.ArgumentParser(description="Evaluate Zemberek models on Universal Dependencies test sets.")
    parser.add_argument("--models", nargs="*", default=["legacy_old_baseline", "fast_default_30k", "model-distilled-33k.bin"])
    args = parser.parse_args()

    args.output_dir.mkdir(parents=True, exist_ok=True)

    model_entries = []
    for m in args.models:
        if m in ("legacy_old_baseline", "legacy"):
            model_entries.append(("Old Perceptron (compressed)", None, True))
        elif m in ("fast_default_30k", "default", "baseline"):
            model_entries.append(("Fast Perceptron (default)", None, False))
        else:
            p = PROJECT_ROOT / "data" / "ambiguity" / m
            if not p.exists():
                p = Path(m)
            if not p.exists():
                print(f"Model path not found: {m}", file=sys.stderr)
                continue
            name = f"Fast Perceptron ({p.stem})"
            model_entries.append((name, p, False))

    results = {}
    diagnostics = {}

    for ds_name, gold_path in DATASETS.items():
        results[ds_name] = {}
        diagnostics[ds_name] = {}
        for m_name, m_path, is_legacy in model_entries:
            safe_name = re.sub(r'[^a-zA-Z0-9_-]', '_', m_name)
            pred_conllu = args.output_dir / f"{ds_name.lower()}_{safe_name}.conllu"

            diag = run_export(gold_path, m_path, pred_conllu, is_legacy=is_legacy)
            metrics = run_conll18_eval(gold_path, pred_conllu)

            results[ds_name][m_name] = metrics
            diagnostics[ds_name][m_name] = diag

    # Print Summary Tables
    print("\n" + "=" * 90)
    print("UNIVERSAL DEPENDENCIES OFFICIAL EVALUATION (conll18_ud_eval.py)")
    print("=" * 90)

    for ds_name in DATASETS:
        print(f"\n### Dataset: {ds_name} Test Set")
        header = f"| {'Model':<32} | {'UPOS':>8} | {'UFeats':>8} | {'UPOS+Feats':>10} | {'Lemmas':>8} | {'BLEX':>8} | {'Words':>8} |"
        sep = f"|{'-' * 34}|{'-' * 10}|{'-' * 10}|{'-' * 12}|{'-' * 10}|{'-' * 10}|{'-' * 10}|"
        print(header)
        print(sep)
        for entry in model_entries:
            m_name = entry[0]
            m = results[ds_name].get(m_name, {})
            upos = f"{m.get('UPOS', 0.0):.2f}%"
            ufeats = f"{m.get('UFeats', 0.0):.2f}%"
            joint = f"{m.get('UPOS+Feats', 0.0):.2f}%"
            lemmas = f"{m.get('Lemmas', 0.0):.2f}%"
            blex = f"{m.get('BLEX', 0.0):.2f}%"
            words = f"{m.get('Words', 0.0):.2f}%"
            print(f"| {m_name:<32} | {upos:>8} | {ufeats:>8} | {joint:>10} | {lemmas:>8} | {blex:>8} | {words:>8} |")

    # Print Diagnostics Summary
    print("\n" + "=" * 90)
    print("DIAGNOSTICS & UNMAPPED MORPHEMES REPORT")
    print("=" * 90)
    for ds_name in DATASETS:
        print(f"\n--- {ds_name} ---")
        for entry in model_entries:
            m_name = entry[0]
            d = diagnostics[ds_name].get(m_name, {})
            unmapped = d.get("unmapped_morphemes", {})
            unmapped_pos = d.get("unmapped_pos", {})
            conflicts = d.get("resolved_conflicts", {})
            unknown = d.get("unknown_analyses", 0)
            print(f"[{m_name}] Unknown tokens: {unknown}, Unmapped morphemes: {len(unmapped)}, Resolved conflicts: {sum(conflicts.values())}")
            if unmapped:
                print(f"  Unmapped details: {unmapped}")
            if unmapped_pos:
                print(f"  Unmapped POS: {unmapped_pos}")

    summary_file = args.output_dir / "ud_eval_summary.json"
    with open(summary_file, "w", encoding="utf-8") as f:
        json.dump({"metrics": results, "diagnostics": diagnostics}, f, indent=2)
    print(f"\nDetailed evaluation results written to: {summary_file}")


if __name__ == "__main__":
    main()

