# Double-Blind Morphological Adjudication Pipeline

This directory contains the self-contained double-blind adjudication system comparing Universal Dependencies pristine gold treebanks (BOUN and IMST) against Zemberek disambiguation models using Gemini 3.8 Flash.

## Directory Structure

```
experiment/scripts/adjudicate/
├── data/
│   ├── gold/
│   │   ├── tr_boun-ud-test.conllu          # Pristine human-annotated test gold
│   │   └── tr_imst-ud-test.conllu          # Pristine human-annotated test gold
│   ├── predicted/
│   │   ├── boun_predicted_distilled-33k-th20k.conllu  # Zemberek model predictions
│   │   └── imst_predicted_distilled-33k-th20k.conllu  # Zemberek model predictions
│   ├── adjudication_dataset_pairwise.jsonl            # Compiled double-blind disputes + controls
│   └── adjudication_dataset_pairwise_summary.json     # Split and token statistics
├── logs/
│   ├── raw_requests_responses.jsonl                   # Streaming raw prompts & Gemini responses
│   ├── adjudications_parsed.jsonl                     # Un-shuffled judgments & outcomes
│   └── adjudication_evaluation_report.json            # Final accuracy & error report
├── prepare_pairwise_dataset.py                        # Dataset compiler (pure CoNLL-U)
├── run_adjudication.py                                # Double-blind Gemini 3.8 Flash runner
└── evaluate_adjudication.py                           # Accuracy & error analysis
```

## Methodology & Invariants

1. **Zero Heuristic Mapping:**
   - Evaluates directly on pristine `.conllu` files. Old intermediate `*_canonical.jsonl` files are completely discarded.
2. **Punctuation Filter:**
   - Punctuation (`UPOS=PUNCT`) tokens are non-ambiguous morphology and skipped.
3. **Consensus Auto-Pass:**
   - Tokens where Gold and Model match 1:1 in Lemma, UPOS, and FEATS are automatically logged as `BOTH_VALID` (0 API calls).
4. **Double-Blind 50/50 Shuffling:**
   - Disputed words are randomly assigned to Candidate 1 or Candidate 2 via a seeded coin flip.
   - Secret key `gold_cand_id` (1 or 2) is used exclusively during post-processing to un-shuffle verdicts.
5. **No `best_candidate` Bias:**
   - The LLM independently grades `eval_1` and `eval_2` as `CORRECT`, `FLAWED`, or `VALID_ALT`.
   - Comparative outcomes are programmatically determined via a deterministic truth table.

## Commands

### 1. Compile Pairwise Dataset
```bash
python3 experiment/scripts/adjudicate/prepare_pairwise_dataset.py
```

### 2. Run LLM Adjudication (Gemini 3.8 Flash)
```bash
python3 experiment/scripts/adjudicate/run_adjudication.py --concurrency 10
```

### 3. Evaluate & Generate Final Report
```bash
python3 experiment/scripts/adjudicate/evaluate_adjudication.py
```
