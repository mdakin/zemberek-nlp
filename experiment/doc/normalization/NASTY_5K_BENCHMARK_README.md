# `nasty_5k`: Curated High-Noise Recoverable Turkish Benchmark Pool

5,000 unique, deduplicated, 5–15 lexical-word Turkish sentences mined from 8 public noisy-text domains for high-density lexical normalization benchmarking and LLM pre-annotation (`temp = 0` token-aligned workflow).

## Files

- `nasty_5000_sentences.txt`: `5,000` UTF-8 raw noisy sentences (1 sentence per line).
- `nasty_5000_candidates.jsonl`: `5,000` 1-to-1 aligned JSON records (`id`, `source_corpus`, `source_file`, `text`, `word_count`, `char_len`, `noisy_word_count`, `noisy_ratio`, `nastiness_score`, `categories`, `noisy_tokens`).
- `nasty_5000_summary.json`: Histograms, means, per-domain counts, category counts, and 25 sample records.

## Selection & Quality Gates

Generated deterministically (`new Random(42)`) by [`MineNastySentences.java`](file:///home/dndara/data/turkish/normalization/scripts/MineNastySentences.java) using Zemberek `TurkishMorphology` (formal, informal, and diacritic-tolerant informal), `TurkishTokenizer`, `TurkishSentenceExtractor`, and `LanguageIdentifier`:

- **Length Window**: `5 <= word_count <= 15` lexical words, `22 <= char_len <= 145`, total tokens `<= 22`.
- **Anti-Leakage Exclusion**: Zero overlap against `15,972` normalized keys from `MultiLexNorm` (`tur`/`tur_deu` train & test), `GECTurk/BOUN` `-de/-da` splits, and `PERMANENT_HELDOUT_TEST_SET_MANIFEST.json`.
- **Hard Cleanliness Gates**: Rejects mojibake (`Ã`, `Ä`, `Å`, `\uFFFD`), URLs/mentions/HTML markup, censored `**` masks, glued forum `:smile:` codes, URL-slug `ue/oe` transliterations, shouting ALL-CAPS (`> 55%`), high digit density (`> 12%`), severe profanity/slurs, non-Turkish scripts, and foreign stopwords.
- **Recoverability & Language Gate**: Max `1` unexplained OOV word (`<= 15%`), requiring Zemberek `tr` language ID (unless 100% of tokens analyze cleanly as formal/recoverable Turkish).
- **Multi-Phenomenon Nastiness Floor**: Requires `noisy_word_count >= 3`, `noisy_ratio >= 0.30`, at least one core lexical error category (`informal_verb`, `clitic_or_split_merge`, `contraction_or_slang`, `elongation`, `typo_recoverable`, `ascii_partial`), and rejects pure `ascii_full`-only sentences.
- **Global Diversity Deduplication**: Enforces exact ASCII-skeleton uniqueness, at most `2` sentences sharing the same opening 3-word skeleton, and word-skeleton Jaccard similarity `< 0.55`.

## Dataset Statistics

- **Total sentences**: `5,000`
- **Mean word count**: `11.035` (`5–15` lexical words)
- **Mean noisy words**: `5.629` (`52.49%` mean noisy word ratio)
- **Mean nastiness score**: `18.743`

### Domain Breakdown (`counts_per_source_corpus`)

| Source Corpus | Sentences | Source Files |
| :--- | ---: | :--- |
| `kadinlar_klubu` | `1,293` | `kadinlar-klubu.txt` |
| `forums_modern_2020s` | `992` | `forums_modern_2020s.txt` |
| `reviews_ecommerce` | `742` | `reviews_trendyol_2025.txt`, `reviews_winvoker.txt`, `reviews_beyazperde.txt`, `complaints_sikayetler.txt` |
| `eksisozluk` | `592` | `eksisozluk_all.txt` |
| `classic_forums_comments` | `542` | `www.nurturia.com.tr.txt`, `www.tvyorumlari.com.txt`, `forum.memurlar.net.txt`, `forum.shiftdelete.net.txt` |
| `search_queries` | `442` | `forum_search_thread_slugs.txt`, `ecommerce_customer_queries.txt` |
| `tweets_social` | `392` | `tweets_offenseval2020.txt` |
| `kizlarsoruyor` | `5` | `www.kizlarsoruyor.com.txt` |

### Category Coverage (`category_occurrence_counts`)

| Category | Sentences Containing Category |
| :--- | ---: |
| `ascii_partial` | `2,987` |
| `clitic_or_split_merge` | `2,197` |
| `informal_verb` | `1,935` |
| `numeral_or_apostrophe` | `1,768` |
| `typo_recoverable` | `1,735` |
| `ascii_full` | `1,616` |
| `contraction_or_slang` | `1,547` |
| `elongation` | `1,266` |

## LLM Gold Normalization (`llm_gold/`)

Generated on `2026-11-13` via [`normalize_nasty_batch.py`](file:///home/dndara/work/zemberek-nlp/experiment/scripts/normalization/normalize_nasty_batch.py) using **Google Gemini Batch API** (`gemini-4-hydrogen`, `thinkingLevel: MEDIUM`, `group_size = 10` sentences/request across `500` requests in `10` batches of `50` requests):

- **Pre-tokenizer**: Stage-1 Zemberek `TurkishTokenizer` (`61,925` total tokens across `5,000` sentences).
- **Coverage & Reliability**: `61,925 / 61,925` tokens (`100%`) LLM-normalized (`0` identity fallbacks, `0` single-sentence retries), elapsed `295.9s`.
- **Edit Statistics**: `32,601` tokens modified (`52.646%`, `normEval.py` baseline LAI `47.35%`), `3,897` `1:N` splits, `462` `N:1` merge heads (`534` continuation slots).
- **Batch API Usage & Cost**: `1,400,074` input + `626,324` candidate + `1,281,121` thinking tokens (`3,307,519` total) = `\$4.10` Batch API USD (`\$0.375`/1M input, `\$1.875`/1M output+thinking).
- **Primary Outputs**:
  - `llm_gold/tokenized/nasty_5000_candidates_tokenized.jsonl`: Stage-1 Zemberek tokenized input.
  - `llm_gold/normalized/nasty_5000_normalized.jsonl`: Token-aligned JSONL with raw/norm tokens and detokenized `normalized_text`.
  - `llm_gold/exports/nasty_5000.norm`: MultiLexNorm 2-column `.norm` TSV (`66,925` lines; `normEval.py`-compatible).
  - `llm_gold/exports/nasty_5000_pairs.tsv`: Sentence-level TSV (`id`, `raw_text`, `normalized_text`, `normalized_tr_lower`).
  - `llm_gold/reports/nasty_5000_summary.json`: Run configuration, token edit counts, and token usage report.
