#!/usr/bin/env python3
"""
Next-Generation Targeted Contrastive Minimal Pairs Pipeline:
Targets all 253 empirically proven failure lemmas from the 5,000 Diagnostic Error-Mining Set.

Pipeline Phases:
1. Synthesis: Runs generate_contrastive_pairs.py on targeted_ambiguity_specs_253.json (Gemini 3.8 Flash).
2. Extraction: Runs Zemberek DisambiguationCandidateExtractor on generated sentences.
3. Disambiguation: Annotates generated sentences using Gemini 3.8 Flash (Thinking=256) to ensure intended parse.
4. Canonicalization: Standardizes lexicon twins using canonicalize_lexicon_twins.py.
5. Unified Master: Merges with existing 2,214 contrastive sentences into contrastive_master_canonical.jsonl.
"""

import os
import sys
import json
import time
import subprocess

REPO_ROOT = "."
DATA_DIR = os.path.join(REPO_ROOT, "data/ambiguity")
SPECS_JSON = os.path.join(DATA_DIR, "sources/targeted_ambiguity_specs_253.json")

NEW_SENTENCES_TXT = os.path.join(DATA_DIR, "contrastive_nextgen_sentences.txt")
NEW_CANDIDATES_JSONL = os.path.join(DATA_DIR, "contrastive_nextgen_candidates.jsonl")
NEW_ANNOTATED_JSONL = os.path.join(DATA_DIR, "contrastive_nextgen_annotated.jsonl")
NEW_CANONICAL_JSONL = os.path.join(DATA_DIR, "contrastive_nextgen_canonical.jsonl")

EXISTING_CANONICAL_JSONL = os.path.join(DATA_DIR, "contrastive_annotated_canonical.jsonl")
MASTER_CANONICAL_JSONL = os.path.join(DATA_DIR, "contrastive_master_canonical.jsonl")
MASTER_TRAIN_TXT = os.path.join(DATA_DIR, "contrastive_master.zemberek.txt")


def log_phase(title):
    print("\n" + "=" * 80)
    print(f"  {title}")
    print("=" * 80 + "\n")


def get_classpath():
    cp_file = "/tmp/full_mvn_cp.txt"
    if not os.path.exists(cp_file):
        subprocess.run("mvn dependency:build-classpath -Dmdep.outputFile=/tmp/full_mvn_cp.txt -q", shell=True, check=True)
    with open(cp_file) as f:
        cp = f.read().strip()
    return f"experiment/target/classes:apps/target/classes:morphology/target/classes:core/target/classes:tokenization/target/classes:lm/target/classes:{cp}"


def phase1_generate_sentences():
    log_phase("PHASE 1: Synthesizing Minimal Pairs for 253 Target Lemmas (Gemini 3.8 Flash)")
    cmd = [
        "python3", "experiment/scripts/generate_contrastive_pairs.py",
        "-i", SPECS_JSON,
        "-o", NEW_SENTENCES_TXT,
        "--model", "gemini-3.8-flash",
        "--batch-size", "6",
        "--concurrency", "8"
    ]
    t0 = time.time()
    subprocess.run(cmd, check=True)
    print(f"[DONE] Phase 1 completed in {time.time() - t0:.1f}s.")


def phase2_extract_candidates():
    log_phase("PHASE 2: Morphological Candidate Extraction via Zemberek FST")
    cp = get_classpath()
    cmd = [
        "java", "-cp", cp,
        "zemberek.morphology.ambiguity.dataset.DisambiguationCandidateExtractor",
        "-i", NEW_SENTENCES_TXT,
        "-o", NEW_CANDIDATES_JSONL
    ]
    t0 = time.time()
    subprocess.run(cmd, check=True)
    print(f"[DONE] Phase 2 completed in {time.time() - t0:.1f}s.")


def phase3_disambiguate():
    log_phase("PHASE 3: High-Precision Disambiguation & Ground Truth Labeling (Thinking=256)")
    cmd = [
        "python3", "experiment/scripts/disambiguate_batch_fast.py",
        "-i", NEW_CANDIDATES_JSONL,
        "-o", NEW_ANNOTATED_JSONL,
        "-c", "12",
        "-b", "256"
    ]
    t0 = time.time()
    subprocess.run(cmd, check=True)
    print(f"[DONE] Phase 3 completed in {time.time() - t0:.1f}s.")


def phase4_canonicalize():
    log_phase("PHASE 4: Lexicon Twin Canonicalization")
    cmd = [
        "python3", "experiment/scripts/canonicalize_lexicon_twins.py",
        "-i", NEW_ANNOTATED_JSONL,
        "-o", NEW_CANONICAL_JSONL
    ]
    t0 = time.time()
    subprocess.run(cmd, check=True)
    print(f"[DONE] Phase 4 completed in {time.time() - t0:.1f}s.")


def phase5_merge_master():
    log_phase("PHASE 5: Merging into Master Contrastive Corpus")
    seen_texts = set()
    master_records = []

    # 1. Existing contrastive sentences
    if os.path.exists(EXISTING_CANONICAL_JSONL):
        with open(EXISTING_CANONICAL_JSONL, "r", encoding="utf-8") as f:
            for idx, line in enumerate(f, 1):
                line = line.strip()
                if not line: continue
                rec = json.loads(line)
                txt = rec.get("text", "").strip()
                if txt and txt not in seen_texts:
                    seen_texts.add(txt)
                    rec["sentence_id"] = f"contrast_orig_{idx:05d}"
                    master_records.append(rec)
        print(f"  Loaded {len(master_records)} existing contrastive sentences.")

    # 2. Newly generated contrastive sentences
    new_added = 0
    if os.path.exists(NEW_CANONICAL_JSONL):
        with open(NEW_CANONICAL_JSONL, "r", encoding="utf-8") as f:
            for idx, line in enumerate(f, 1):
                line = line.strip()
                if not line: continue
                rec = json.loads(line)
                txt = rec.get("text", "").strip()
                if txt and txt not in seen_texts:
                    seen_texts.add(txt)
                    rec["sentence_id"] = f"contrast_nextgen_{idx:05d}"
                    master_records.append(rec)
                    new_added += 1
        print(f"  Added {new_added} newly synthesized contrastive sentences.")

    with open(MASTER_CANONICAL_JSONL, "w", encoding="utf-8") as f:
        for rec in master_records:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")

    print(f"\n[SUCCESS] Master Contrastive Corpus: {len(master_records)} total sentences.")
    print(f"          Output saved to: {MASTER_CANONICAL_JSONL}")


def main():
    print(f"Starting Next-Generation Contrastive Minimal Pairs Pipeline...")
    t_start = time.time()
    phase1_generate_sentences()
    phase2_extract_candidates()
    phase3_disambiguate()
    phase4_canonicalize()
    phase5_merge_master()
    total_time = time.time() - t_start
    print("\n" + "#" * 80)
    print(f"  PIPELINE FINISHED IN {total_time:.1f}s ({total_time/60:.2f} min)")
    print("#" * 80 + "\n")


if __name__ == "__main__":
    main()

