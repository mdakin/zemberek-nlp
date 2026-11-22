# Test Plan: Comprehensive Adjudication, Golden Treebank Audit, and Score Normalization

**Date:** September 24, 2026  
**Project:** Zemberek-NLP Morphological Disambiguation  
**Authors:** Zemberek-NLP Engineering Team  

---

## 1. Executive Summary & Design Overview

This test plan specifies the methodology for an exhaustive, token-level linguistic evaluation across the Universal Dependencies (UD) Turkish benchmarks (**UD Turkish-BOUN** and **UD Turkish-IMST**) to:
1. Adjudicate **all sentences where the New Model was flagged as having an error** according to the test set ground truth.
2. Include a **statistically balanced control sample of sentences where the New Model was 100% correct** according to the test set ground truth.
3. Explicitly evaluate the **Golden (Treebank) ground truth itself** for every evaluated token.
4. Compute **statistically normalized, real-world disambiguation accuracy scores** for Golden, Old Zemberek (`model-compressed`), and New Zemberek (`model-distilled-33k.bin`).
5. **Log all raw requests and raw responses** sent to/from the LLM for independent audit and inspection.

---

## 2. Statistical Methodology & Experimental Design

### A. Rationale for Including Sentences Where New Zemberek Was Correct
> [!IMPORTANT]
> **Including a control sample of correct sentences is essential for statistical validity.**
>
> 1. **Eliminating Survivorship / Selection Bias**: If we only evaluate sentences where the benchmark marked New Zemberek as "wrong", we can only observe *false negatives* (cases where the New Model was unfairly penalized). We would have zero measurement of *false positives* (cases where the New Model agreed with the benchmark, but the benchmark was actually flawed or both models coincided on a shared error).
> 2. **Auditing Golden Treebank Integrity**: To measure the true quality and error rate of the "Golden" treebank annotations, we must observe how often the Golden annotations hold up when models agree with them vs. when models disagree with them.
> 3. **Mathematical Score Normalization**: In stratified sampling, calculating the true population accuracy of any model requires evaluating both strata:
>    $$\text{True Accuracy} = P(\text{Correct} \mid \text{Benchmark Correct}) \cdot P(\text{Benchmark Correct}) + P(\text{Correct} \mid \text{Benchmark Wrong}) \cdot P(\text{Benchmark Wrong})$$
>    Without measuring $P(\text{Correct} \mid \text{Benchmark Correct})$ on a control sample, the overall test score cannot be formally normalized.

### B. Sample Size & Dataset Scope
Based on verified token-level error distributions from `eval_boun_old_vs_new.json` and `eval_imst_old_vs_new.json`:

| Stratum | UD BOUN (979 sents) | UD IMST (1,100 sents) | Combined Scope |
| :--- | :---: | :---: | :---: |
| **All Sentences with ≥1 New Model Error vs. Gold** | **584 sentences** (1,641 tokens) | **589 sentences** (1,512 tokens) | **1,173 sentences** (3,153 tokens) |
| **Control Sample: New Model 100% Correct vs. Gold** | **100 sentences** (236 tokens) | **100 sentences** (181 tokens) | **200 sentences** (417 tokens) |
| **Total Sentences to Adjudicate** | **684 sentences** | **689 sentences** | **1,373 sentences** (3,570 tokens) |

#### Control Sample Composition:
The 200-sentence control sample is stratified equally between BOUN and IMST (100 sentences each):
- **50 sentences** where **New was Correct and Old was Wrong** (measuring New's error recovery rate over Old).
- **50 sentences** where **Both New and Old were Correct** (measuring baseline Golden validity and mutual agreement stability).

---

## 3. Adjudication Prompt Architecture & Multi-Target Schema

Rather than forcing a single relative winner, the LLM independently evaluates **all three solutions** for every target token:

```text
[Sentence Text with target token highlighted]

Target Token: <index> "<surface>"
1. GOLDEN TREEBANK SOLUTION:
   - CoNLL-U: <UPOS> | <FEATS> | <LEMMA>
   - Mapped Zemberek Parse: <SingleAnalysis>
2. OLD ZEMBEREK (model-compressed):
   - Raw Zemberek: <SingleAnalysis>
   - Projected CoNLL-U: <UPOS> | <FEATS>
3. NEW ZEMBEREK (model-distilled-33k.bin):
   - Raw Zemberek: <SingleAnalysis>
   - Projected CoNLL-U: <UPOS> | <FEATS>
4. ALL CANDIDATE PARSES FROM ZEMBEREK:
   - [0] ...
   - [1] ...
```

### JSON Response Schema per Disputed/Ambiguous Token:
```json
{
  "<token_idx>": {
    "golden_eval": "CORRECT" | "FLAWED" | "VALID_ALT",
    "old_eval": "CORRECT" | "FLAWED" | "VALID_ALT",
    "new_eval": "CORRECT" | "FLAWED" | "VALID_ALT",
    "best_choice": "NEW" | "OLD" | "GOLDEN" | "BOTH_EQUAL" | "ALL_THREE_VALID" | "NEITHER",
    "reason": "<concise linguistic justification, max 20 words>"
  }
}
```

### Evaluation Criteria:
- **`golden_eval`**:
  - `CORRECT`: Standard, grammatically sound analysis in Turkish syntax.
  - `FLAWED`: Ungrammatical case/possessive assignment, obsolete tagger artifact (e.g. existential *var* as Noun, indefinite *kimse* as Noun, clitic *ile* as CCONJ), or morphological segmentation failure.
  - `VALID_ALT`: Acceptable theoretical or lexicographical variant (e.g. productive passive vs. lexicalized verb).
- **`old_eval`** & **`new_eval`**: Evaluated against standard Turkish syntax and morphological semantics in sentence context.

---

## 4. Score Normalization Mathematical Formulation

The total ambiguous tokens ($N_{ambig}$) in each treebank are divided into 4 mutually exclusive strata:
1. $S_1$ (**Both Agree with Gold**): $N_1$ tokens (BOUN: 4,459; IMST: 3,328; Combined: 7,787)
2. $S_2$ (**New Agrees with Gold, Old Disagrees**): $N_2$ tokens (BOUN: 731; IMST: 680; Combined: 1,411)
3. $S_3$ (**Old Agrees with Gold, New Disagrees**): $N_3$ tokens (BOUN: 514; IMST: 325; Combined: 839)
4. $S_4$ (**Both Disagree with Gold**): $N_4$ tokens (BOUN: 592; IMST: 744; Combined: 1,336)

Because we evaluate **100% of sentences where New had an error**, we have complete census coverage of strata $S_3$ and $S_4$.
From our control sample, we obtain empirical estimates of true accuracy for $S_1$ and $S_2$.

### Metrics to Compute:
1. **Raw Benchmark Disambiguation Accuracy**:
   $$\text{RawAcc} = \frac{N_{matches\_gold}}{N_{ambig}}$$
2. **True / Normalized Disambiguation Accuracy (Strict)**:
   $$\text{StrictAcc}_{model} = \frac{1}{N_{ambig}} \sum_{k=1}^4 N_k \cdot P(\text{model} = \text{CORRECT} \mid S_k)$$
3. **True / Normalized Disambiguation Accuracy (Lenient)**:
   $$\text{LenientAcc}_{model} = \frac{1}{N_{ambig}} \sum_{k=1}^4 N_k \cdot P(\text{model} \in \{\text{CORRECT}, \text{VALID\_ALT}\} \mid S_k)$$
4. **Golden Treebank Integrity Score**:
   $$\text{GoldIntegrity} = \frac{1}{N_{ambig}} \sum_{k=1}^4 N_k \cdot P(\text{gold} = \text{CORRECT} \mid S_k)$$
5. **Overall Token Accuracy** (including 47.6% unambiguous tokens where all systems achieve 100%):
   $$\text{OverallAcc} = \frac{N_{unambig} \cdot 1.0 + N_{ambig} \cdot \text{Acc}_{ambig}}{N_{total}}$$

---

## 5. Raw Request/Response Logging Architecture

All interactions with the LLM are logged with complete fidelity to ensure full reproducibility and transparency:
- **Directory**: `experiment/scripts/adjudicate/logs/`
- **Files**:
  1. `raw_requests_responses.jsonl`: Raw prompt and API response payloads.
  2. `adjudications_parsed.jsonl`: Structured token-level records un-shuffled with secret keys (`gold_cand_id`).
  3. `adjudication_evaluation_report.json`: Aggregated metrics, control benchmark results, and sample error categorizations.

---

## 6. Execution Pipeline & Tooling

1. **Pairwise Dataset Preparation**:
   [`experiment/scripts/adjudicate/prepare_pairwise_dataset.py`](file:///home/dndara/work/zemberek-nlp/experiment/scripts/adjudicate/prepare_pairwise_dataset.py)
   - Operates directly on pristine CoNLL-U gold trees and native model exports (0 synthetic bias).
   - Generates double-blind pairs with 50/50 randomized candidate ordering.
2. **Gemini Batch Adjudicator**:
   [`experiment/scripts/adjudicate/run_adjudication.py`](file:///home/dndara/work/zemberek-nlp/experiment/scripts/adjudicate/run_adjudication.py)
   - Gemini 3.8 Flash via Batch API (`thinkingBudget: 1024`, `temperature: 0.0`).
   - Supports `--limit-per-treebank`, chunking, automated polling, and idempotent resume.
3. **Double-Blind Evaluation**:
   [`experiment/scripts/adjudicate/evaluate_adjudication.py`](file:///home/dndara/work/zemberek-nlp/experiment/scripts/adjudicate/evaluate_adjudication.py)
   - Evaluates disputed tokens, control calibration, and true adjudicated accuracy.

---

## 7. 200-Sentence Benchmark Verification Results

Conducted across 100 BOUN + 100 IMST sentences (200 sentences total) in Gemini Batch mode:

| Metric | BOUN (100 sents) | IMST (100 sents) | Combined (200 sents) |
| :--- | :--- | :--- | :--- |
| **Total Word Tokens** | 1,046 | 773 | 1,819 |
| **Consensus (Auto-Pass)** | 605 (57.8%) | 360 (46.6%) | 965 (53.1%) |
| **Disputed Adjudicated** | 441 (42.2%) | 413 (53.4%) | 854 (46.9%) |
| **BOTH_VALID** | 56 (12.7% of disp.) | 60 (14.5% of disp.) | 116 (13.6% of disp.) |
| **MODEL_WINS (Treebank Error)** | 116 (26.3% of disp.) | 62 (15.0% of disp.) | 178 (20.8% of disp.) |
| **MODEL_PREFERRED** | 68 (15.4% of disp.) | 89 (21.5% of disp.) | 157 (18.4% of disp.) |
| **GOLD_WINS (Model Error)** | 55 (12.5% of disp.) | 68 (16.5% of disp.) | 123 (14.4% of disp.) |
| **GOLD_PREFERRED** | 135 (30.6% of disp.) | 121 (29.3% of disp.) | 256 (30.0% of disp.) |
| **NEITHER** | 8 (1.8% of disp.) | 13 (3.1% of disp.) | 21 (2.5% of disp.) |
| **True Adjudicated Accuracy** | **80.78%** | **73.87%** | **77.84%** |
| **Verified Treebank Noise** | **11.09%** | **8.02%** | **9.79%** |
| **Actionable Model Error Rate**| **18.16%** | **24.45%** | **20.84%** |
| **Control Accuracy** | **100.0%** (8/8) | **100.0%** (2/2) | **100.0%** (10/10) |


