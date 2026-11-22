# Turkish Normalization Data & Benchmark Repository

Organized publicly available benchmarks, external lexicons, and multi-domain noisy Turkish corpora for Zemberek `normalization` development and evaluation.

## Directory Layout

```text
~/data/turkish/normalization/
├── README.md                  # This index & anti-leakage rules
├── manifest.json              # Exact file sizes, line/token counts, and SHA-256 hashes
├── scripts/
│   ├── download_and_prepare.py
│   ├── download_queries.py
│   └── MineNastySentences.java
├── benchmarks/                # Standard external & controlled synthetic evaluation benchmarks
│   ├── multilexnorm/          # W-NUT 2021 MultiLexNorm (tur + tur_deu + normEval.py)
│   ├── gecturk_boun_clitics/  # BOUN -de/-da clitic orthographic error split (M2 format)
│   ├── mukayese_trspell10/    # Mukayese TRSPELL-10 isolated word spellcheck benchmark
│   └── synthetic_1k/          # 3x1,000 controlled leak-free sets (synth_asciified_1k, synth_typo_1k, synth_clean_1k)
├── lexicons_external/         # External reference normalization lexicons (eminesener)
├── corpora_raw/               # Untouched downloaded source archives/CSVs/JSONLs
├── corpora_plain/             # UTF-8 1-utterance-per-line plain text extracted from corpora_raw/
│   └── local_existing_links/  # Relative symlinks to pre-existing noisy/forum files in ~/data/turkish/
└── custom_benchmark/          # Workspace reserved for Zemberek-NormBench v1 (dev/test)
```

## Strict Anti-Leakage Policy

> **Do NOT mine lexicon rules, n-gram counts, or heuristics from held-out test splits:**
> - `benchmarks/multilexnorm/tur/test.norm` (`143` tweets — blind external ERR anchor)
> - `benchmarks/multilexnorm/tur_deu/test.norm` (`229` tweets — foreign-token preservation test)
> - `benchmarks/gecturk_boun_clitics/boun_source_test.txt` & `boun_target_test.txt`
> - `benchmarks/mukayese_trspell10/test.csv` (`10,000` word pairs)
> - `benchmarks/synthetic_1k/synth_{asciified,typo,clean}_1k.tsv` (`3 x 1,000` multi-domain sentences)

## Standard & Controlled Synthetic Benchmarks (`benchmarks/`)

| Benchmark | Path | Source / License | Primary Use |
| :--- | :--- | :--- | :--- |
| W-NUT 2021 `MultiLexNorm` (`tur`) | `benchmarks/multilexnorm/tur/{train,test}.norm` | `robvanderg/multilexnorm` (Çolakoğlu et al., 2019) | End-to-end `ERR` (`--lowercase` & truecased) |
| W-NUT 2021 `MultiLexNorm` (`tur_deu`) | `benchmarks/multilexnorm/tur_deu/{train,test}.norm` | `robvanderg/multilexnorm` | Code-switched foreign-word preservation test |
| GECTurk / BOUN `-de/-da` Clitics | `benchmarks/gecturk_boun_clitics/boun_*` | `GGLAB-KU/gecturk` (Arıkan et al., 2019) | Dedicated `-de/-da` clitic vs. locative sub-benchmark |
| Mukayese `TRSPELL-10` | `benchmarks/mukayese_trspell10/test.csv` | `alisafaya/mukayese` v0.0.1 (CC / MIT) | Context-free `TurkishSpellChecker` regression |
| `Synth Asciified (1K)` | `benchmarks/synthetic_1k/synth_asciified_1k.{tsv,jsonl}` | `SyntheticBenchmarkBuilder.java` (`4` domains) | Pure 100%-asciified sentence restoration (`5,914` asciified words) |
| `Synth Typo d=1 (1K)` | `benchmarks/synthetic_1k/synth_typo_1k.{tsv,jsonl}` | `SyntheticBenchmarkBuilder.java` (`4` domains) | Isolated Q-keyboard distance-1 non-word typo recovery (`1,646` typos) |
| `Synth Clean (1K)` | `benchmarks/synthetic_1k/synth_clean_1k.{tsv,jsonl}` | `SyntheticBenchmarkBuilder.java` (`4` domains) | Clean multi-domain `CleanFPR` & exact cased preservation (`0` errors) |

## Extracted Public Noisy Corpora (`corpora_plain/`)

| File | Domain / Source | Lines | Whitespace Tokens | Size (MB) |
| :--- | :--- | ---: | ---: | ---: |
| `complaints_sikayetler.txt` | `corpora_plain/complaints_sikayetler.txt` | 3,336 | 218,603 | 1.7 |
| `eksisozluk_all.txt` | `corpora_plain/eksisozluk_all.txt` | 164,068 | 12,147,496 | 93.0 |
| `forums_modern_2020s.txt` | `corpora_plain/forums_modern_2020s.txt` | 986,674 | 34,433,422 | 253.3 |
| `reviews_beyazperde.txt` | `corpora_plain/reviews_beyazperde.txt` | 44,547 | 2,582,715 | 19.5 |
| `reviews_trendyol_2025.txt` | `corpora_plain/reviews_trendyol_2025.txt` | 582,601 | 5,112,654 | 36.4 |
| `reviews_winvoker.txt` | `corpora_plain/reviews_winvoker.txt` | 489,644 | 9,662,265 | 71.5 |
| `tweets_offenseval2020.txt` | `corpora_plain/tweets_offenseval2020.txt` | 34,792 | 547,400 | 4.0 |
| `tweets_yankihue.txt` | `corpora_plain/tweets_yankihue.txt` | 11,108 | 127,081 | 0.9 |
| **Total (Newly Downloaded Public Plain Text)** | **8 corpora** | **2,316,770** | **64,831,636** | **480.4** |

## Linked Pre-Existing Local Corpora (`corpora_plain/local_existing_links/`)

- `corpora_plain/local_existing_links/kadinlar-klubu.txt` -> `~/data/turkish/noisy/kadinlar-klubu.txt` (2033.2 MB)
- `corpora_plain/local_existing_links/www.kizlarsoruyor.com.txt` -> `~/data/turkish/clean/www.kizlarsoruyor.com.txt` (1245.8 MB)
- `corpora_plain/local_existing_links/www.tvyorumlari.com.txt` -> `~/data/turkish/clean/www.tvyorumlari.com.txt` (6.3 MB)
- `corpora_plain/local_existing_links/www.sikayetim.com.txt` -> `~/data/turkish/clean/www.sikayetim.com.txt` (7.3 MB)
- `corpora_plain/local_existing_links/www.nurturia.com.tr.txt` -> `~/data/turkish/clean/www.nurturia.com.tr.txt` (56.6 MB)
- `corpora_plain/local_existing_links/forum.shiftdelete.net.txt` -> `~/data/turkish/clean/forum.shiftdelete.net.txt` (182.1 MB)
- `corpora_plain/local_existing_links/forum.memurlar.net.txt` -> `~/data/turkish/clean/forum.memurlar.net.txt` (204.5 MB)
- `corpora_plain/local_existing_links/wowturkey.com.txt` -> `~/data/turkish/clean/wowturkey.com.txt` (1400.5 MB)
- `corpora_plain/local_existing_links/unparsed_words.tsv` -> `~/data/turkish/unparsed_words.tsv` (154.8 MB)

## Search Query Datasets (`queries/`)

| File | Source / Nature | Lines | Whitespace Tokens | Size (MB) |
| :--- | :--- | ---: | ---: | ---: |
| `bing_turkey_queries_unique.txt` | Microsoft `BingCoronavirusQuerySet` (`Country=Turkey`, 2020–2021 real web queries) | 112 | 291 | 0.00 |
| `ecommerce_customer_queries.txt` | `kdrhnn/turkce-eticaret-niyet-analizi` (noisy Turkish e-commerce customer queries) | 400 | 2,072 | 0.01 |
| `eksisozluk_topic_titles.txt` | Unique Ekşi Sözlük topic titles (`title`/`topic` — lowercase noun-phrase/entity queries) | 18,060 | 76,040 | 0.58 |
| `forum_search_thread_slugs.txt` | Unique `ForumSohbetleri` thread slugs (ASCII-degraded product/troubleshooting queries) | 104,578 | 574,358 | 3.66 |
| `mkqa_google_tr_clean_10k.txt` | Apple `MKQA` (`tr`, 10,000 human-translated Google Search queries — Clean-Query Control) | 10,000 | 71,662 | 0.49 |
| **Total (`queries/plain/`)** | **5 query sources** | **133,150** | **724,423** | **4.75** |

## Auxiliary Spelling Dictionary (`lexicons_external/asimokby_spelling_dict/`)

- `base_turkish_misspellings.csv` & `expanded_turkish_misspellings.csv` (`asimokby/Turkish-Spelling-Dictionary`, wrong→correct word pairs incl. numeral/apostrophe variants)

## Curated Hard Candidate Pool (`custom_benchmark/nasty_5k/`)

5,000 unique, deduplicated, 5–15 lexical-word Turkish sentences mined deterministically by `scripts/MineNastySentences.java` (`mean_word_count = 11.035`, `mean_noisy_ratio = 52.49%`) with strict anti-leakage exclusions and multi-phenomenon coverage (`ascii_partial`, `clitic_or_split_merge`, `informal_verb`, `numeral_or_apostrophe`, `typo_recoverable`, `ascii_full`, `contraction_or_slang`, `elongation`). See `custom_benchmark/nasty_5k/README.md` for full breakdown.

- `custom_benchmark/nasty_5k/nasty_5000_sentences.txt` (`5,000` lines, raw sentences ready for LLM pre-annotation)
- `custom_benchmark/nasty_5k/nasty_5000_candidates.jsonl` (`5,000` 1-to-1 aligned metadata JSON lines)
- `custom_benchmark/nasty_5k/nasty_5000_summary.json` & `custom_benchmark/nasty_5k/README.md`
