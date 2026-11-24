#!/usr/bin/env python3
"""
Evaluates pruning, feature template selection, and compression options on trained
Zemberek FastPerceptron ambiguity models.

Usage:
  python3 experiment/scripts/evaluate_compression_pruning.py \
      --input data/ambiguity/model-distilled-30k.txt \
      --test-set ~/data/turkish/distilled/golden_test_1000_canonical.jsonl \
      --strip-prefix-2 \
      --threshold 0.5 \
      --output /tmp/model-compact.bin
"""

import argparse
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path


def get_classpath() -> str:
    root = Path(__file__).resolve().parent.parent.parent
    cp_file = Path("/tmp/mvn_cp.txt")
    if not cp_file.exists():
        subprocess.run(
            ["mvn", "dependency:build-classpath", "-pl", "experiment", f"-Dmdep.outputFile={cp_file}", "-q"],
            cwd=str(root),
            check=True
        )
    ext_cp = cp_file.read_text().strip()
    return f"{root}/experiment/target/classes:{root}/morphology/target/classes:{root}/core/target/classes:{ext_cp}"


def filter_features(input_txt: str, strip_p2: bool, threshold: float):
    kept = []
    total = 0
    with open(input_txt, "r", encoding="utf-8") as f:
        for line in f:
            parts = line.strip().split(" ", 1)
            if len(parts) == 2:
                total += 1
                try:
                    w = float(parts[0])
                    feat = parts[1]
                    if strip_p2 and feat.startswith("2:"):
                        continue
                    if abs(w) <= threshold:
                        continue
                    kept.append((w, feat))
                except ValueError:
                    pass
    return kept, total


def compress_weights_to_bin(features, out_bin: str, cp: str):
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", suffix=".txt", delete=False) as f:
        temp_txt = f.name
        for w, feat in features:
            f.write(f"{w:.3f} {feat}\n")

    compress_src = f"""
    import zemberek.core.data.Weights;
    import java.nio.file.Paths;
    public class RunCompress {{
        public static void main(String[] args) throws Exception {{
            Weights w = Weights.loadFromFile(Paths.get("{temp_txt}"));
            w.compress().serialize(Paths.get("{out_bin}"));
        }}
    }}
    """
    with tempfile.TemporaryDirectory() as tmpdir:
        src_path = os.path.join(tmpdir, "RunCompress.java")
        Path(src_path).write_text(compress_src)
        subprocess.run(["javac", "-cp", cp, "-d", tmpdir, src_path], check=True)
        subprocess.run(["java", "-cp", f"{tmpdir}:{cp}", "RunCompress"], check=True)

    try:
        os.remove(temp_txt)
    except OSError:
        pass


def evaluate_model(model_bin: str, test_set: str, cp: str) -> dict:
    with tempfile.NamedTemporaryFile(suffix=".json", delete=False) as f:
        eval_json = f.name

    cmd = [
        "java", "-cp", cp,
        "zemberek.morphology.ambiguity.dataset.EvaluateAmbiguityModel",
        "-a", test_set,
        "-m", model_bin,
        "-o", eval_json
    ]
    subprocess.run(cmd, capture_output=True, check=True)

    with open(eval_json, "r", encoding="utf-8") as f:
        ev = json.load(f)

    try:
        os.remove(eval_json)
    except OSError:
        pass

    return {
        "ambiguous_accuracy": ev.get("trained_ambiguity_accuracy", 0.0) * 100,
        "overall_accuracy": ev.get("trained_overall_accuracy", 0.0) * 100,
        "sentence_exact_match": ev.get("trained_sentence_exact_match_rate", 0.0) * 100,
        "ambiguous_matches": ev.get("trained_ambiguous_matches", 0),
        "total_ambiguous": ev.get("ambiguous_tokens", 0),
    }


def main():
    parser = argparse.ArgumentParser(description="Evaluate FastPerceptron model compression and pruning options.")
    parser.add_argument("--input", "-i", default="data/ambiguity/model-distilled-30k.txt", help="Input uncompressed weights text file")
    parser.add_argument("--test-set", "-t", default=os.path.expanduser("~/data/turkish/distilled/golden_test_1000_canonical.jsonl"), help="Test dataset (JSONL)")
    parser.add_argument("--threshold", "-th", type=float, default=0.5, help="Absolute weight threshold for pruning (|w| > th)")
    parser.add_argument("--strip-prefix-2", action="store_true", help="Omit skip-bigram feature template 2:r1-ig1-r3-ig3")
    parser.add_argument("--output", "-o", default="/tmp/model-compact.bin", help="Output .bin model path")
    args = parser.parse_args()

    cp = get_classpath()
    print(f"Reading features from {args.input}...")
    filtered, total = filter_features(args.input, args.strip_prefix_2, args.threshold)
    print(f"Kept {len(filtered):,d} / {total:,d} features ({len(filtered)/total*100:.1f}%)")

    print(f"Compressing into {args.output} via LossyIntLookup...")
    compress_weights_to_bin(filtered, args.output, cp)
    bin_size = os.path.getsize(args.output)
    print(f"Model binary size: {bin_size:,d} bytes ({bin_size/(1024*1024):.2f} MB)")

    print(f"Evaluating on {args.test_set}...")
    results = evaluate_model(args.output, args.test_set, cp)
    print(f"Ambiguity Accuracy:     {results['ambiguous_accuracy']:.2f}% ({results['ambiguous_matches']}/{results['total_ambiguous']})")
    print(f"Overall Token Accuracy: {results['overall_accuracy']:.2f}%")
    print(f"Sentence Exact Match:   {results['sentence_exact_match']:.2f}%")


if __name__ == "__main__":
    main()
