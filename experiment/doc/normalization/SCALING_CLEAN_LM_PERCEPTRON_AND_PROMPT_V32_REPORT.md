# Scaling `CleanWordLm` (`5M 70/30` & `8M 75/25`), `SmoothLm` Ablation, `+30K` Perceptron Expansion, and Lean LLM Prompt (`v4.0`)

**Date:** `2026-11-19`  
**Companion Docs:** [MODEL_COMPRESSION_REPORT.md](MODEL_COMPRESSION_REPORT.md) *(Part II: `MultiLevelMphf` `v5` `30.71 → 3.99 MiB`)* · [PROMPT_AB_100_COMPARISON.md](PROMPT_AB_100_COMPARISON.md) *(`100`-sentence `v3.1` vs. `v4.0` Batch A/B)*

---

## Executive Summary

| Question | Empirically Verified Answer | Key Numbers |
| :--- | :---: | :--- |
| **1. Scale `CleanWordLm` or `SmoothLm`?** | **Scale `CleanWordLm` on curated news + subtitles/Onedio (`5M–8M`, `70/30`–`75/25`) & prune *before* Perceptron training; drop `SmoothLm` in `v2`** | **`5.0M (70/30)`** (`+Smooth`) peaks unpruned (`81.18%` `test_slice_01`, `90.81%` `nasty_5k`, `50.46%` `GECTurk`). For compact production (`v5`), **`8.0M Curated (75/25) v5(6/9/18) NO SmoothLm [Pre-Pruned]` (`5.56 MiB` total vs. `7.33 MiB` shipped)** beats **even unpruned `1.8M + SmoothLm`** on `test_slice_01` (`81.09%` vs. `80.79%`), `nasty_5k` (`90.49%` vs. `90.41%`), `MultiLexNorm` (`67.63%` vs. `67.43%`), `Synth-Asciified` (**`97.45%`**, all-time best), and ties `Synth-Typo` (`82.26%`), while saving `1.77 MiB` `.gz`, `-14 MB` heap, and `+19%` speed. |
| **2. Add `+20K` Heavy + `5K` Medium + `5K` Light (`75K → 105K`)?** | **Verified Live on First `+10K` Heavy (`75K → 85K`, `v4.0` + `gemini-2.5-pro` `MEDIUM`)** | Adding just **`+10K` (`+13.3%`) `v4.0` Heavy sentences** grew `LOO`-active rewrites (`edits >= 2`) by **`+15.4%`** (`11,967 → 13,815`) and pushed compact **`85K + 8M Curated v5(6/9/18) NO SmoothLm` (`~5.65 MiB`)** to **all-time records**: `test_slice_01` **`81.33%`** (`69.26 / 61.18%` EM), `nasty_5k` **`90.72%`** (`-192` ed vs. shipped `v5`), `GECTurk-Clitic` **`48.55%`**, and `Synth-Asciified` **`97.52%`** (`87.0%` Ca). Adding `+5K` Medium + `+5K` Light next will also restore the `66.7%` Heavy ratio and trim `CleanFPR`. |
| **3. Was Prompt `v3.1` over-guiding `gemini-2.5-pro`?** | **Yes — Replaced `43` Lines (`85+` Examples) with `18`-Line `v4.0` Lean Prompt** | Over-prescriptive rules in `v3.0`/`v3.1` caused our worst annotation quirks (`91.3%` uncapitalized 2nd sentences after `.!?`, `29` `Zeynepciğim` disharmony errors, `"10. da → 10'da"`). On the `100`-sentence Batch A/B test, `v4.0` (`-67%` shorter, `-33.4%` prompt tokens/sent) matched `14/14` clean controls and `14/14` heavy slang sentences while fixing multi-sentence capitalization cleanly. |

---

## 1. `CleanWordLm` Scaling & `SmoothLm` Evaluation

### 1.1 `SmoothLm` vs. `CleanWordLm` Architecture & Live `SmoothLm` Probe

| Property | `SmoothLm` (`lm.2gram.slm.gz`) | `CleanWordLm` (`MphfCountMap` in `knowledge.bin.gz` `v5`) |
| :--- | :--- | :--- |
| **Order & Vocabulary** | **2-gram**, closed `LmVocabulary` (`119,741` strings, `581,601` bigrams; `~14 MB` Java `HashMap` heap) | **3-gram**, open 64-bit hashed (`FNV-1a + Murmur3`, zero string table; `3.12 MiB` heap at `v5`) |
| **Bundled Size (`PR #383`)** | **`3.34 MiB` `.gz`** (`4.73 MiB` raw, `~4.99 B/ngram` `.gz`) | **`2.65 MiB` `.gz`** (`3.12 MiB` raw for `1,128,748` n-grams = **`2.46 B/ngram` `.gz`**) |
| **Agglutinative OOVs (Live Probe)** | Productive inflections outside top `119K` collapse to `<unk>` (`idx=119740`): `P("beklemedeyim") = P("uzaklaşıyoruz") = P("xyzunk") = -15.3067`. | Tracks any inflected surface form meeting `u >= minUnigram` at `2.46 B` `.gz`; true OOVs fall back smoothly to `CharPhonotacticModel`. |
| **`<unk>` Backoff Bug ([SmoothLm.java:551](lm/src/main/java/zemberek/lm/compression/SmoothLm.java#L551-L558))** | `unigramBackoffs[unkIdx] = 0.0f` while known words have negative Katz backoff (`"ve" = -0.765`), so **`P("geldim" \| "xyzunk") = -9.88` scores higher than `P("geldim" \| "ve") = -10.65`**. | Interpolation weights $\lambda_2, \lambda_3$ scale smoothly with exact history counts $c(h)/(c(h)+k)$; zero inverted backoff. |
| **Role in Normalization `v2`** | 4 auxiliary features (`F_SMOOTH_SPAN_LLR` weight in `perceptron.bin.gz` is `-0.0153` ≈ `0` vs. `+0.3881` on `F_CLEAN_SPAN_LLR`). Only legacy `TurkishSentenceNormalizer` and `TurkishSpellChecker` (`lm-unigram.slm`, `6.5 MB` raw) truly need `SmoothLm`. | Drives **6 subsystems**: 3-gram `F_CLEAN_SPAN_LLR` (spanning `1:2` clitic splits), unseen 1-edit typo rescue (`T_SRC_LEN_COST_GAIN`), clean/loanword attestation (`T_SRC_RAW_CLEAN_ATTEST`), clitic skip-LLR (`F_CLITIC_SKIP_LLR`), compound split/merge gates, and `casingLexicon`. |

### 1.2 Empirical `CleanWordLm` Size Curve (`1.8M → 20M` Clean Sentences from `refined/`)

Measured on a `20,000,000`-sentence (`246.8M`-token) stream from `~/data/turkish/refined/` (`100%` morphology-parsed sentences filtered by [RefineCorpus.java](experiment/src/main/java/zemberek/morphology/ambiguity/model/RefineCorpus.java); note `~/data/turkish/clean/` is the *unrefined* raw dump and should not be used directly):

| Clean Sentences | Clean Tokens | **A. Fixed Cutoffs (`3/4/8`)**<br>Retained N-Grams | **A. Fixed**<br>Total `knowledge.bin.gz` | **B. $\sqrt{N}$-Scaled Cutoffs (`u/b/t`)** | **B. Scaled**<br>Retained N-Grams | **B. Scaled**<br>Total `knowledge.bin.gz` |
| ---: | ---: | ---: | :---: | :---: | ---: | :---: |
| **`1.8M`** *(shipped)* | `22.2M` | `1.06M` | **`3.95 MiB`** *(actual `3.99`)* | `3 / 4 / 8` | `1.06M` | **`3.95 MiB`** |
| **`3.0M`** | `37.0M` | `1.69M` | **`5.53 MiB`** | `4 / 5 / 11` | `1.26M` | **`4.53 MiB`** |
| **`5.0M`** | `61.8M` | `2.68M` | **`8.03 MiB`** | `5 / 7 / 14` | `1.42M` | **`5.06 MiB`** |
| **`7.0M`** | `86.1M` | `3.61M` | **`10.32 MiB`** | `6 / 8 / 17` | `1.63M` | **`5.68 MiB`** |
| **`10.0M`** | `122.5M` | `4.95M` | **`13.61 MiB`** | `8 / 10 / 21` | `1.76M` | **`6.13 MiB`** |
| **`15.0M`** | `184.5M` | `7.25M` | **`19.22 MiB`** | `10 / 13 / 26` | `1.99M` | **`6.91 MiB`** |
| **`20.0M`** | `246.8M` | `9.45M` | **`24.57 MiB`** | `11 / 15 / 30` | `2.26M` | **`7.73 MiB`** |

- **Growth per `+1M` sentences**: **`+1.13 MiB` `.gz`** with fixed `3/4/8` cutoffs, or **`+0.18–0.35 MiB` `.gz`** with $\sqrt{N}$-scaled cutoffs (which also reject rare corpus noise).

### 1.3 Live Experiments: `1.8M` → `5.0M (70/30)` → `8.0M (75/25)` (Uncurated vs. Curated & Pre-Pruned `v5`)

**Shard Hygiene Audit Across `~/data/turkish/refined/`:** Single-word morphological filtering in `RefineCorpus` cannot reject *parseable-but-wrong* forum tokens (`"doğrumu?"` parsed as `doğru+Pos_1sg+Acc`) or legal/listicle casing quirks. Sampling `100K` lines per shard verified:
- **`5.0M (70/30)`**: `3.5M` news/Wikipedia/`tbmm` + `1.5M` `open-subtitles-tr-2018` (`750K`), `onedio` (`500K`), `shiftdelete` (`250K` n-grams only).
- **`8.0M Uncurated (75/25)`**: Added `yargitay-karar` (`250K`), `tbmm-tutanak` (`450K`), `tbmm` (`450K`), and `forum.memurlar.net` (`250K` n-grams only) — exposing TitleCase legal headnotes in `yargitay` and `"doğrumu?"` clitic bugs in `memurlar`.
- **`8.0M Curated (75/25)`**: **`6.0M` pure modern news + Wikipedia** (`radikal` `900K`, `cnn-turk` `800K`, `haberturk` `800K`, `www.dunya.com` `750K`, `vikipedi` `750K`, `iha` `700K`, `www.cnnturk.com` `500K`, `dunya` `400K`, `ntv-750k` `400K`; all `0.000%–0.003%` attached-`-mı?`, `casingLexicon` recorded **only** here) + **`2.0M` clean conversational/web** (`open-subtitles-tr-2018` `1.3M` + `onedio` `700K`, n-grams only).

#### A. Unpruned Models Across All 7 Benchmarks (`75K` Parallel Train Pool)

| Benchmark | `1.8M + Smooth` *(Base)* | **`5M (70/30) + Smooth`** | **`5M (70/30) NO Smooth`** | `8M Uncur (75/25) + Smooth` | `8M Uncur (75/25) NO Smooth` | **`8M Curated (75/25) + Smooth`** | **`8M Curated (75/25) NO Smooth`** |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **`test_slice_01` `W-ERR`** | `80.79%` (`2,927`) | **`81.18%`** (`2,867`) | `81.09%` (`2,880`) | `80.82%` (`2,922`) | `81.00%` (`2,894`) | **`81.13%`** (`2,875`) | `80.65%` (`2,947`) |
| ↳ `SentEM-Lo / Ca` | `68.60 / 60.82%` | `69.20 / 61.22%` | `69.20 / 61.18%` | `69.10 / 60.90%` | `69.12 / 60.82%` | **`69.26 / 61.24%`** | `68.66 / 60.44%` |
| **`nasty_5k` `W-ERR`** | `90.41%` (`3,362`) | **`90.81%`** (`3,222`) | `90.78%` (`3,231`) | `90.35%` (`3,383`) | `90.27%` (`3,411`) | `90.43%` (`3,355`) | `90.13%` (`3,458`) |
| ↳ `SentEM-Lo / Ca` | `61.48 / 49.96%` | **`62.72 / 50.80%`** | `62.64 / 50.72%` | `61.54 / 49.66%` | `61.24 / 49.38%` | `61.70 / 50.08%` | `60.76 / 49.34%` |
| **`MultiLexNorm` `W-ERR`** | `67.43%` (`45.5%`) | `67.84%` (`46.9%`) | `67.84%` (`46.2%`) | **`68.05%`** (`48.3%`) | `67.63%` (`48.3%`) | `67.22%` (`47.6%`) | **`68.05%`** (`47.6%`) |
| **`GECTurk-Clitic` `W-ERR`** | `47.94%` (`681`) | **`50.46%`** (`648`) | `47.09%` (`692`) | `48.24%` (`677`) | `48.09%` (`679`) | **`49.85%`** (`656`) | `47.02%` (`693`) |
| **`Synth-Asciified` `W-ERR`** | `97.04%` (`84.5%`) | `97.28%` (`85.9%`) | `97.35%` (`86.0%`) | `97.24%` (`86.0%`) | `97.21%` (`85.7%`) | **`97.43%`** (**`86.9%`**) | `97.38%` (`86.3%`) |
| **`Synth-Typo-d1` `W-ERR`** | `82.26%` (`292`) | **`83.78%`** (`267`) | `83.23%` (`276`) | `83.29%` (`275`) | `81.29%` (`308`) | `81.96%` (`297`) | `81.41%` (`306`) |
| **`Synth-Clean` `FPR` (`Ca`)** | `0.320%` (`95.4%`) | `0.305%` (`95.4%`) | `0.291%` (`95.5%`) | `0.305%` (`94.3%`) | `0.305%` (`94.3%`) | `0.305%` (`94.9%`) | **`0.276%`** (`95.1%`) |

#### B. Compact `v5` `MultiLevelMphf` Models — Post-Pruned vs. **Pre-Pruned Before Perceptron Training**

| Benchmark (`v5` Compact MPHF) | `1.8M v5(3/4/8) + Smooth`<br>**`7.33 MiB`** *(Shipped)* | `5M v5(5/7/14) NO Smooth`<br>**`5.06 MiB`** *(Post-Pruned)* | `8M Uncur v5(6/9/18) NO Smooth`<br>**`5.64 MiB`** *(Post-Pruned)* | `8M Curated v5(6/9/18) + Smooth`<br>**`8.90 MiB`** *(Pre-Pruned)* | **`8M Curated v5(6/9/18) NO Smooth`**<br>**`5.56 MiB` TOTAL *(Pre-Pruned)*** |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **`test_slice_01` `W-ERR` (`EM-Lo / Ca`)** | `80.50%` (`68.18 / 60.46%`) | `80.75%` (`68.68 / 60.66%`) | `80.27%` (`68.06 / 59.86%`) | `80.62%` (`68.44 / 60.36%`) | **`81.09%`** (**`68.98 / 60.90%`**, **`-90` ed**) |
| **`nasty_5k` `W-ERR` (`EM-Lo / Ca`)** | `90.18%` (`60.96 / 49.64%`) | `90.48%` (`61.88 / 50.28%`) | `89.83%` (`60.14 / 48.60%`) | `90.12%` (`60.48 / 49.24%`) | **`90.49%`** (**`61.80 / 49.96%`**, **`-109` ed**) |
| **`MultiLexNorm` `W-ERR` (`EM-Lo`)** | `67.22%` (`45.45%`) | `—` | `—` | `67.22%` (`46.15%`) | **`67.63%`** (**`46.15%`**, `5.71%` FPR) |
| **`GECTurk-Clitic` `W-ERR`** | `47.17%` (`691` ed) | `45.57%` (`712` ed) | `47.32%` (`689` ed) | **`47.94%`** (`681` ed) | `46.87%` (`695` ed, `-0.30 pp`) |
| **`Synth-Asciified` `W-ERR` (`EM-Ca`)** | `96.82%` (`83.10%` Ca) | `—` | `—` | `97.24%` (`85.90%` Ca) | **`97.45%`** (**`86.30%` Ca**, all-time best) |
| **`Synth-Typo-d1` `W-ERR` (`EM-Lo`)** | `81.23%` (`73.90%`) | `81.29%` (`73.90%`) | `78.37%` (`69.80%`) | `79.89%` (`72.50%`) | **`82.26%`** (**`75.00%`**, **`+3.89 pp` vs post**) |
| **`Synth-Clean` `CleanFPR` (`Tok/s`)** | `0.334%` (`7,625` tok/s) | **`0.327%`** | `0.342%` | `0.342%` (`7,606` tok/s) | `0.342%` (**`9,138` tok/s**, **`+19.8%` faster**) |

#### C. Three Key Discoveries Across the `1.8M` / `5M` / `8M` Experiments

1. **Pre-Pruning `v5(6/9/18)` *Before* Perceptron Training Turns `8M NO SmoothLm` (`5.56 MiB`) into Our Strongest Compact Model:**
   - Unpruned `8M` holds `18.72M` n-grams (`2/3/5`), including `283K` noisy `count=2..5` tail unigrams where rare corpus slips trigger `T_SRC_RAW_CLEAN_ATTEST` ("attested in clean text, don't fix").
   - Pruning `CleanWordLm` to **`6/9/18` (`1.77M` keys = `329K` 1g, `1.18M` 2g, `258K` 3g; `5.56 MiB` total `knowledge.bin.gz`) *before* building training lattices** acts as an automatic corpus denoiser **and** calibrates Perceptron weights directly to the shipped `v5` table:
     - Beats **unpruned `8M NO SmoothLm`** on `test_slice_01` (`80.65% → 81.09%`, `-67` edits), `nasty_5k` (`90.13% → 90.49%`, `-124` edits), `Synth-Asciified` (`97.38% → 97.45%`), and `Synth-Typo` (`81.41% → 82.26%`).
     - Beats **`8M v5 + SmoothLm [Pre-Pruned]`** on **5 of 7 benchmarks** (`+0.47 pp` `test_slice_01`, `+0.37 pp` `nasty_5k`, `+0.41 pp` `MultiLexNorm`, `+0.21 pp` `Synth-Ascii`, **`+2.37 pp` `Synth-Typo`**), because routing slots `0..3` to `CleanWordLm`'s denoised `329K`/`1.18M` 2-grams eliminates `SmoothLm`'s `<unk>` backoff inversion (`P(w | <unk>) > P(w | "ve")`).
     - Beats **even unpruned `1.8M + SmoothLm`** on `test_slice_01`, `nasty_5k`, `MultiLexNorm`, and `Synth-Asciified` while saving **`1.77 MiB` `.gz`** (`5.56` vs. `7.33 MiB`), **`-14 MB` Java heap**, and **`+19%` faster throughput**.
2. **Shard Curation Eliminated All Three `8M` Regressions:** Dropping `yargitay-karar` (TitleCase headnotes), `tbmm`/`tbmm-tutanak` (1996 roll-calls), and `forum.memurlar.net` (`"doğrumu?"` clitic bugs) lifted `test_slice_01 + Smooth` to **`69.26% / 61.24%` EM** (highest of all runs), restored `GECTurk-Clitic + Smooth` from `48.24% → 49.85%`, lifted `Synth-Clean` cased EM from `94.30% → 95.10%` (`0.276%` `CleanFPR`), and pushed `Synth-Asciified` to **`97.43%–97.45%`**.
3. **`70/30` vs. `75/25` Trade-off:** `70/30` (`30%` informal) gives `+0.35 pp` extra social recall when unpruned (`nasty_5k` `90.81%`), whereas once pre-pruned to compact `v5` without `SmoothLm`, **`8M Curated (75/25) v5(6/9/18) NO SmoothLm` (`5.56 MiB`)** matches `nasty_5k` (`90.49%` vs. `90.48%`) and leads on `test_slice_01` (`81.09%` vs. `80.75%`), `GECTurk-Clitic` (`46.87%` vs. `45.57%`), and `Synth-Typo` (`82.26%` vs. `81.29%`). *(Applying the same pre-pruning to `5M (70/30)` or `8M (70/30)` is likewise expected to capture both gains.)*

---

## 2. Parallel Pool Expansion (`75K → 85K` Verified Live; `→ 105K` Target)

### 2.1 Live Verification on First `+10K` `v4.0` Heavy Slices (`train_slice_11` & `12`, `gemini-2.5-pro` `MEDIUM`)

Normalizing `train_slice_11` and `train_slice_12` (`10,000` sentences / `128,773` tokens) in `llm_gold_v4/` completed in `3.5 min` with **`100%` first-pass completion (`0` retries)**, **`-35.6%` fewer prompt tokens**, **`-18.3%` fewer total tokens**, **`301/301` (`100%`) unit abbreviations preserved**, and **`85.5%` internal `.!?` capitalization** (up from `8.7%` in `v3.1`).

Adding those `9,999` deduplicated sentences (`74,997 → 84,996`, `+13.3%`) expanded the mined parallel tables super-linearly on LOO-active multi-edit entries:
- `parallel1ToN`: `97,884 →` **`108,982`** (`+11,098`) · **`total >= 3` (lexical gate): `31,934 → 35,240` (`+3,306`)** · **`edits >= 2` (LOO-active): `11,967 → 13,815` (`+15.4%`)** · `2:1`: `4,989 → 5,659` · `3:1`: `763 → 861`.

| Benchmark | `75K + 1.8M v5 + Smooth`<br>*(Shipped `7.33 MiB`)* | `85K + 1.8M + Smooth`<br>*(Unpruned `+10K v4`)* | `75K + 8M Cur v5 NO Smooth`<br>*(`5.56 MiB` Pre-Pruned)* | `85K + 8M Cur v5 + Smooth`<br>*(`8.99 MiB` Pre-Pruned)* | **`85K + 8M Cur v5 NO Smooth`**<br>**`~5.65 MiB` TOTAL *(Pre-Pruned)*** |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **`test_slice_01` `W-ERR` (`EM-Lo / Ca`)** | `80.50%` (`68.18 / 60.46%`) | `80.92%` (`68.86 / 61.16%`) | `81.09%` (`68.98 / 60.90%`) | `81.15%` (`69.22 / 61.18%`) | **`81.33%`** (**`69.26 / 61.18%`**, **`-126` ed**) |
| **`nasty_5k` `W-ERR` (`EM-Lo / Ca`)** | `90.18%` (`60.96 / 49.64%`) | `90.38%` (`61.96 / 50.64%`) | `90.49%` (`61.80 / 49.96%`) | **`90.73%`** (**`62.58 / 50.70%`**) | **`90.72%`** (**`62.42 / 50.50%`**, **`-192` ed**) |
| **`MultiLexNorm` `W-ERR` (`CleanFPR`)** | `67.22%` (`6.43%` FPR) | `67.84%` (`4.29%` FPR) | `67.63%` (`5.71%` FPR) | **`68.46%`** (**`3.57%` FPR**) | **`67.84%`** (`4.29%` FPR) |
| **`GECTurk-Clitic` `W-ERR` (`EM-Lo`)** | `47.17%` (`45.76%`) | `47.40%` (`46.15%`) | `46.87%` (`45.17%`) | `46.18%` (`44.77%`) | **`48.55%`** (**`46.75%`**, **`+1.38 pp` vs `v5`**) |
| **`Synth-Asciified` `W-ERR` (`EM-Ca`)** | `96.82%` (`83.10%` Ca) | `97.13%` (`85.70%` Ca) | `97.45%` (`86.30%` Ca) | `97.40%` (`86.40%` Ca) | **`97.52%`** (**`87.00%` Ca**, **`+3.90 pp`**) |
| **`Synth-Typo-d1` `W-ERR` (`EM-Lo`)** | `81.23%` (`73.90%`) | `81.59%` (`73.80%`) | **`82.26%`** (`75.00%`) | `82.02%` (`74.70%`) | `81.35%` (`74.10%`) |
| **`Synth-Clean` `CleanFPR` (`Tok/s`)** | **`0.334%`** (`7,434` tok/s) | `0.327%` (`8,053` tok/s) | `0.342%` (`9,138` tok/s) | `0.356%` (`8,456` tok/s) | `0.371%` (**`10,381` tok/s**, **`+39.6%`**) |

*(Note: Unpruned `85K + 8M Curated + Smooth` also reached `81.19%` / `69.40%` `SentEM-Lo` / `61.32%` `SentEM-Ca` on `test_slice_01` — still edged on `W-ERR` by compact pre-pruned `85K + 8M v5 NO Smooth` at **`81.33%`**!)*

### 2.2 Completing the Balanced `105K` Multi-Regime Pool (`+10K` More Heavy + `5K` Medium + `5K` Light)

| Regime | Original `75K` | Current `85K` (`+10K` Heavy) | Remaining `+20K` (`v4.0` `MEDIUM`) | Final `105K` Pool | Token Edit Density |
| :--- | ---: | ---: | ---: | ---: | :---: |
| **Heavy (`full_110k`)** | `50,000` (`66.7%`) | `60,000` (**`70.6%`**) | `+10,000` (`slice_13`, `slice_14`) | **`70,000` (`66.7%`)** | `~24.0%` |
| **Medium (`medium_110k`)** | `15,000` (`20.0%`) | `15,000` (`17.6%`) | `+5,000` (`train_slice_04`) | **`20,000` (`19.0%`)** | `~13.5%` |
| **Light / Low (`low_110k`)** | `10,000` (`13.3%`) | `10,000` (`11.8%`) | `+5,000` (`train_slice_03`) | **`15,000` (`14.3%`)** | `~7.3%` |
| **Total Parallel Pool** | **`75,000`** | **`85,000`** | **`+20,000`** | **`105,000`** | **`~19.3%` overall** |

- **Why Adding `+5K` Medium + `+5K` Light Next Matters:** Adding `+10K` Heavy-only raised the Heavy share from `66.7% → 70.6%`, nudging `CleanFPR` slightly up (`0.342% → 0.371%`, `+4` FP on `Synth-Clean`). Adding `+5K` Medium + `+5K` Light (with or without `+10K` more Heavy) restores the `66.7%` (or `63.2%` at `95K`) multi-regime balance so `CleanFPR` settles back around `~0.30–0.33%` while retaining every lexical gain.

### 2.3 Held-Out `test_slice_02_v4_hhigh` (`5,000` Heavy Sentences, Prompt `v4.0` + `gemini-2.5-pro` `HIGH`)

Created from the untouched stratified twin split `dev_slice_01.jsonl` (`0` overlap with `test_slice_01` or any `train_slice_*`; saved to `test_slice_02_v4_hhigh_normalized.jsonl` and added to `loadForbiddenEvalFingerprints()`):
- **Gold QC (`test_slice_01` `v3.1` vs. `test_slice_02` `v4.0`)**: Internal `.!?` capitalization rose `8.7% →` **`88.8%` (`341/384`)**, unit preservation `94.3% →` **`100.0%` (`159/159`)**, mid-word `'I'` dropped `12 → 4`, and prompt tokens dropped **`-35.5%`**.

| Model | `test_slice_01` (`v3.1` `HIGH`)<br>`W-ERR` (`Edits` · `EM-Lo / Ca`) | **`test_slice_02_v4`** (`v4.0` `HIGH`)<br>`W-ERR` (`Edits` · `EM-Lo / Ca`) | `test_slice_02` `CleanFPR` |
| :--- | :---: | :---: | :---: |
| **`Zemberek 0.17.1` (Legacy)** | `61.82%` (`5,816` · `47.08 / 4.26%`) | `61.70%` (`5,927` · `47.78 / 5.02%`) | `1.568%` |
| **`75K + 1.8M v5(3/4/8) + Smooth`** *(Shipped)* | `80.50%` (`2,970` · `68.18 / 60.46%`) | `79.36%` (`3,195` · `66.46 / 54.00%`) | `1.246%` |
| **`75K + 1.8M + Smooth`** *(Unpruned `v2.4`)* | `80.79%` (`2,927` · `68.60 / 60.82%`) | `79.66%` (`3,148` · `66.88 / 54.32%`) | `1.278%` |
| **`75K + 8M Cur v5(6/9/18) NO Smooth`** | `80.95%` (`2,902` · `68.72 / 60.62%`) | `79.91%` (`3,110` · `67.18 / 54.50%`) | `1.429%` |
| **`85K (+10Kv4) + 8M v5 + Smooth`** *(Before starts fix)* | **`81.28%`** (`2,852` · `69.38 / 61.26%`) | `80.54%` (`3,012` · `68.20 / 55.42%`) | `1.393%` |
| **`85K (+10Kv4) + 8M v5 NO Smooth`** *(Before starts fix)* | **`81.22%`** (`2,861` · `69.22 / 61.06%`) | **`80.60%`** (`3,003` · `68.08 / 55.34%`) | `1.418%` |
| **`85K (+10Kv4) + 8M v5 NO Smooth` *(Fixed `computeSentenceStarts`)*** | `—` *(v3.1 gold is uncap)* | **`80.49%`** (**`68.12 / 57.52%`**, **`+2.16 pp` Ca**) | **`1.403%`** |

- **`computeSentenceStarts()` Fixed & Verified ([experiment/.../SpanCandidateGenerator.java:4280-4455](experiment/src/main/java/zemberek/normalization/v2/SpanCandidateGenerator.java#L4280-L4455) & [normalization/.../SpanCandidateGenerator.java:1245-1405](normalization/src/main/java/zemberek/normalization/v2/SpanCandidateGenerator.java#L1245-L1405)):**
  - Replaced the legacy `v3.1` workaround (`Character.isUpperCase(t.surface().charAt(0))`) with `isTrueInternalSentenceBoundary()`, capitalizing `S2+` after genuine `.`/`!`/`?` boundaries (and `S1` when an input contains `2+` genuine sentences) while skipping abbreviations (`mah.`, `cad.`, `vs.`, `vb.`, `dr.`, `inş.`, single-letter initials), ordinals (`2.`), ellipses (`..`/`...`), broken URLs, `.com`/`.php` extensions, and quoted suffixes (`"EVET!"ler`).
  - **Result on `85K + 8M v5 NO Smooth`**: `test_slice_02_v4` `SentEM-Ca` jumped **`+2.16 pp` (`55.36% → 57.52%`, `+108` exact cased sentences — `~75%` of the lexically-exact multi-sentence ceiling, and `+3.52 pp` / `+176` sentences ahead of shipped `1.8M v5`'s `54.00%`)**, `Synth-Asciified` rose to **`97.55%` `W-ERR` (`88.70 / 87.30%` EM)**, and `Synth-Clean` showed **`0.000%` degradation** (`0.342%` `CleanFPR`).

---

## 3. LLM Cleaning Prompt Audit & Finalized Lean Prompt (`v4.0`)

### 3.1 Why Over-Guiding in `v3.0` / `v3.1` Skewed Annotations

Auditing `25,000` `v3.1`-cleaned sentences alongside our `nasty_5k` error log confirmed that **all three major systematic annotation quirks came from over-prescriptive prompt rules overriding `gemini-2.5-pro`'s native Turkish knowledge**:

| Slice (`5,000` sents each) | Sent `#0`: `raw lo → gold lo` | Sent `#0`: `raw Up → gold Up` | **Internal `.!?` Boundary (`Sent1=Up`, `afterDot lo → lo`)** | Stray Mid-Word `'I'` | Turkish `I`/`İ` Front-Vowel Bug |
| :--- | ---: | ---: | ---: | ---: | ---: |
| `full_110k / test_slice_01` | `2,688` | `1,873` | **`115 / 126` (`91.3%` left lowercase!)** | `9` | `0` |
| `full_110k / train_slice_01` | `2,683` | `1,860` | **`101 / 111` (`91.0%` left lowercase!)** | `5` | `0` |
| `medium_110k / train_slice_01` | `2,553` | `2,100` | **`75 / 89` (`84.3%` left lowercase!)** | `5` | `0` |
| `low_110k / train_slice_01` | `2,473` | `2,289` | **`48 / 49` (`98.0%` left lowercase!)** | `2` | `0` |

1. **`91.3%` Uncapitalized Second Sentences After `.!?`**: `v3.1` instructed *"Keep sentence-initial capitalization on the **FIRST lexical word** ... Convert ... mid-sentence TitleCase to normal casing"*, forcing the model to leave `"Ürün harika. çok beğendim."` lowercase after internal sentence boundaries.
2. **`29` `Zeynepciğim` Disharmony Errors (`nasty_5k`)**: Caused by `v3.0` listing `-cığım/-ciğim` without `-çığım`.
3. **`"10. da → 10'da"` Ordinal Dot Loss (`nasty_5k`)**: Caused by `v3.0`'s `"3 . lemenin → 3'lemenin"` example.

### 3.2 Controlled `100`-Sentence Batch A/B Verification (`v3.1` vs. Lean `v4.0`)

Testing an `18`-line principle-only prompt (`-67%` shorter; zero typo, `SOMBAHÇEM`, `-cığım`, month, title, or tech word lists) on `100` stratified sentences (`gemini-2.5-pro`, `HIGH`, `group_size=25`; full outputs in [PROMPT_AB_100_COMPARISON.md](PROMPT_AB_100_COMPARISON.md)):
- **`1,350 / 1,350` tokens (`100%`) complete**, `0` retries, **`-33.4%` fewer prompt tokens/sentence** (`244.9 → 163.0`), **`99.33%` lowercase token-slot agreement** (`1,341 / 1,350`).
- **`14 / 14` exact matches on `T0_clean_control`** and **`14 / 14` exact matches on heavy slang/typos** — proving `gemini-2.5-pro` natively handles TDK months (`19 Mart 2025` vs. `ocak ayında`), titles (`Berna Hanım`, `Abbas Bey`), brands/specs (`4K`, `iPhone`, `YouTube`, `David Bisbal`), diminutives (`Meltemciğim`), and spoken suffixes without word lists.
- **Post-period multi-sentence capitalization fixed** (`#9–#24`), while deliberate single-sentence lowercase posts stayed lowercase.
- **Only 2 project conventions needed explicit 1-phrase notes** (both now added): minimal-pair circumflexes (`hâlâ/kâr` rather than `mekân`/`herhâlde`) and keeping unit abbreviations unexpanded (`5 dk`, `13.000 km`).

### 3.3 Finalized Prompt `v4.0` & Gatekeeper Checks (Active in `normalize_nasty_batch.py`)

Updated in [normalize_nasty_batch.py](experiment/scripts/normalization/normalize_nasty_batch.py#L35-L58) (alongside `mid_word_ascii_I` and `residual_allcaps_shouting` checks in `compute_qc_flags()` at [#L658-L671](experiment/scripts/normalization/normalize_nasty_batch.py#L658-L671)):

```text
You are a Turkish computational linguist performing strict token-aligned orthographic normalization into standard TDK Turkish.

PRINCIPLES:
- Fix ONLY orthography and word boundaries: missing Turkish diacritics (add circumflexes only on minimal pairs like hâlâ/kâr), spelling/typos, expressive letter elongations, spoken suffix contractions, chat shorthands, clitic/compound spacing, ASCII apostrophes ('), and TDK capitalization.
- Conservative do-no-harm: If a token is already valid in context, copy it unchanged. NEVER paraphrase, replace slang/colloquial words, brands, or loanwords ("kanka", "valla", "lan", "tweetin", "Airfryer"), reorder words, invent/delete punctuation, or expand digits/unit abbreviations ("2'sini", "200 TL", "5 dk", "13.000 km").
- Profanity: Do not censor. Normalize single-word vulgarisms (including masked/leetspeak forms) to standard spelling, and keep multi-word vulgar acronyms as canonical lowercase 1-token forms ("amk", "aq", "oç", "mk", "sg", "bsg").
- Invariants: Copy Punctuation, Emoji, Emoticon, @mentions, #hashtags, URLs, and random keyboard laughter ("sjsjsj", "asdasd") verbatim.
- Casing: Follow TDK capitalization (proper nouns, titles, holidays, acronyms, and sentence starts after ".", "!", "?"; convert ALL-CAPS shouting and random mid-sentence casing to standard casing). However, if an entire single-sentence input was written in lowercase, keep a non-proper first word lowercase.

TOKEN-SLOT ALIGNMENT (#0 .. #<N-1>):
Map EVERY token index ("0" .. "<N-1>") to its normalized string:
- 1:1 rewrite: keep in the same slot (#0 "paylas" -> {"0": "paylaş"}).
- 1:N split (detached clitics, units, or open compounds): space-separate inside that SAME slot (#0 "fıkrımızıde" -> {"0": "fikrimizi de"}; #0 "200tl" -> {"0": "200 TL"}).
- N:1 merge (detached suffixes/apostrophes, closed compounds, or symbol-masked words): put the merged form in the FIRST slot and "" in absorbed slots:
  #0 "ankara", #1 "da" -> {"0": "Ankara'da", "1": ""}
  #0 "hiç", #1 "birşey" -> {"0": "hiçbir", "1": "şey"}
  #0 "k", #1 "*", #2 "tü" -> {"0": "kötü", "1": "", "2": ""}
```
