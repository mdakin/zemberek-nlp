# Replication Package: Morphological Disambiguation Adjudication & Normalization

This package allows independent evaluation and replication of the Zemberek morphological disambiguation adjudication benchmark across Universal Dependencies (**UD Turkish-BOUN** and **UD Turkish-IMST**) using any LLM (Gemini, OpenAI GPT-4o, Anthropic Claude, or local Ollama/vLLM).

---

## 1. Package Contents

| File | Size | Description |
| :--- | :---: | :--- |
| **`data/ambiguity/adjudication_dataset_full.jsonl.gz`** (or `.jsonl`) | 516 KB (5.1 MB uncompressed) | **Pre-packaged, self-contained dataset** of 1,373 sentences (3,570 ambiguous tokens). Contains full sentence text, treebank gold CoNLL-U lines, mapped Zemberek keys, Old model keys/conll, New model keys/conll, and all candidate parses from Zemberek dictionary. *(No Java or Zemberek compilation required!)* |
| **`experiment/scripts/run_comprehensive_adjudication.py`** | ~18 KB | **Adjudication Runner** supporting multi-threading, automatic retries, resuming interrupted sessions, full raw prompt/response logging, and multi-provider support (`--provider gemini\|openai\|anthropic`). |
| **`experiment/scripts/calculate_normalized_scores.py`** | ~14 KB | **Score Normalization Calculator** that aggregates judgments across all four population strata ($S_1, S_2, S_3, S_4$) and outputs strict/lenient normalized disambiguation accuracies. |
| **`TEST_PLAN_COMPREHENSIVE_ADJUDICATION.md`** | ~9 KB | Experimental design, mathematical formulation, and prompt instructions. (Gist: https://gist.github.com/mdakin/f0851bcbeb433ada3312eb0eaa655de9) |
| **`FINAL_DISAMBIGUATION_BENCHMARK_REPORT.md`** | ~33 KB | Baseline reference results, treebank flaw audit (12.04% error rate), and 30 curated linguistic examples. (Gist: https://gist.github.com/mdakin/46733360a14cdff5b3b5b27a046baf4c) |

---

## 2. Quickstart: Running with Different LLMs

### Prerequisites
Only Python 3 and the standard `requests` library are required:
```bash
pip install requests
```

### Option A: Running with OpenAI (e.g. GPT-4o or GPT-4o-mini)
```bash
export OPENAI_API_KEY="your-openai-api-key"

python3 experiment/scripts/run_comprehensive_adjudication.py \
    --provider openai \
    --model gpt-4o \
    --logs-dir data/ambiguity/adjudication_logs_gpt4o \
    --concurrency 12
```

### Option B: Running with Anthropic Claude (e.g. Claude 3.5 Sonnet)
```bash
export ANTHROPIC_API_KEY="your-anthropic-api-key"

python3 experiment/scripts/run_comprehensive_adjudication.py \
    --provider anthropic \
    --model claude-3-5-sonnet-20241022 \
    --logs-dir data/ambiguity/adjudication_logs_claude \
    --concurrency 10
```

### Option C: Running with Google Gemini (e.g. Gemini 3.8 Flash or 2.5 Flash)
```bash
export GEMINI_API_KEY="your-gemini-api-key"

python3 experiment/scripts/run_comprehensive_adjudication.py \
    --provider gemini \
    --model gemini-3.8-flash \
    --logs-dir data/ambiguity/adjudication_logs_gemini \
    --concurrency 16
```

### Option D: Running with Local / OpenAI-Compatible Endpoints (vLLM, Ollama, DeepSeek)
```bash
python3 experiment/scripts/run_comprehensive_adjudication.py \
    --provider openai \
    --base-url http://localhost:8000/v1 \
    --api-key "dummy-key" \
    --model "deepseek-ai/DeepSeek-V2.5" \
    --logs-dir data/ambiguity/adjudication_logs_local \
    --concurrency 8
```

---

## 3. Calculating Normalized Scores

Once the runner finishes, compute the mathematically normalized scores:
```bash
python3 experiment/scripts/calculate_normalized_scores.py
```
This generates:
- `normalization_summary.json`: Detailed numeric metrics by treebank and stratum.
- `COMPREHENSIVE_NORMALIZATION_REPORT.md`: Formatted summary tables comparing Raw vs. Strict vs. Lenient scores.

