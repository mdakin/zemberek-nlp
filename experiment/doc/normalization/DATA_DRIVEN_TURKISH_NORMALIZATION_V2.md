# Data-Driven Turkish Text Normalization (`v2`): Architecture, Dataset, & Benchmarks

**Date:** `2026-11-15`  
**Scope:** First-principles `v2` architecture, `80,000`-sentence Gemini Batch gold dataset (`50K` Heavy + `15K` Medium + `10K` Low = `75K` multi-regime train + `5K` dedicated `test_slice_01`), multi-benchmark accuracy, component ablations, data-scaling curve, and single-thread latency/throughput evaluation.  
**Algorithmic Companion:** [TURKISH_NORMALIZATION_V2_METHODS_DEEP_DIVE.md](file:///home/dndara/work/zemberek-nlp/experiment/doc/normalization/TURKISH_NORMALIZATION_V2_METHODS_DEEP_DIVE.md)

---

## Glossary of Acronyms & Evaluation Metrics

| Acronym | Full Expansion | Definition |
| :--- | :--- | :--- |
| **`GBDT`** | **Gradient Boosted Decision Trees** | Ensemble of shallow regression trees trained sequentially via second-order Newton steps on logistic loss ([`ObliviousGbdt.java`](file:///home/dndara/work/zemberek-nlp/experiment/src/main/java/zemberek/normalization/v2/ObliviousGbdt.java#L10-L53)). |
| **`Oblivious GBDT`** | **Symmetric (`CatBoost`-Style) GBDT** | Trees where every node at depth $d$ shares the exact same `(feature, threshold)` split, turning each depth-$6$ tree into a fast 64-entry decision table (`292 B/tree`, `115.1 KB` total). |
| **`PA-I`** | **Passive-Aggressive (Type-I) Max-Margin Perceptron** | Online structured margin update where step size $\eta$ scales with task loss $\mathcal{L}(\tilde{\pi}, \pi^*)$ and is clipped at $C = 0.25$ ([`SpanLatticePerceptronNormalizer.java`](file:///home/dndara/work/zemberek-nlp/experiment/src/main/java/zemberek/normalization/v2/SpanLatticePerceptronNormalizer.java#L11-L40)). |
| **`MEMM` / `DP`** | **Maximum Entropy Markov Model / Dynamic Programming** | Sequence model normalizing candidate logits via boundary-level `logSoftmax` before exact multi-span dynamic programming ([`TwoStageGbdtNormalizer.java`](file:///home/dndara/work/zemberek-nlp/experiment/src/main/java/zemberek/normalization/v2/TwoStageGbdtNormalizer.java#L29-L51)). |
| **`DAG`** | **Directed Acyclic Graph** | Single-pass Multi-Span Candidate Lattice over token boundaries $0 \dots N$ supporting `1:1` rewrites, `1:2` splits, and `2:1`/`3:1` merges simultaneously. |
| **`OOF`** | **Out-Of-Fold Cross-Fitting** | Partitioning training lattices into $K = 4$ disjoint folds so `Hybrid` perceptron training receives held-out `GBDT` predictions rather than overfit in-fold scores. |
| **`LOO`** | **Leave-One-Sentence-Out Jackknife** | Subtracting the current training sentence's `(raw -> gold)` counts from parallel lookup tables prior to feature extraction to prevent singleton memorization bias. |
| **`LLR`** | **Log-Likelihood Ratio** | Exact boundary-to-boundary generative log-probability difference $\log P(\text{candSpan}, w_{\text{next}} \mid h) - \log P(\text{rawSpan}, w_{\text{next}} \mid h)$. |
| **`MPHF`** | **Minimal Perfect Hash Function** | Zemberek's `MultiLevelMphf` + `QuantizedWeightTable`, packing sparse perceptron weights at `~3.4 bytes/feature` with zero hash-table overhead. |
| **`CSR` / `FST`** | **Compressed Sparse Row / Finite-State Transducer** | Compact array-packed trie (`CompactStemEndingSpellChecker`) and morphological transducer (`TurkishMorphology`). |
| **`OOV` / `TDK`** | **Out-Of-Vocabulary / *Türk Dil Kurumu*** | Words without strict morphological parses (`OOV`); official Turkish Language Association orthography rules (`TDK`). |
| **`W-ERR`** | **Word Error Reduction (`%`)** | Percentage of raw-input word-level Levenshtein errors eliminated relative to gold (`0%` = raw input, `100%` = gold). |
| **`SentEM-Lo / Ca`** | **Sentence Exact Match (Lowercase / Cased)** | Percentage of sentences matching reference gold identically (lowercase / exact casing). |
| **`CleanFPR`** | **Clean-Token False Positive Rate (`%`)** | Percentage of already-clean input tokens erroneously altered by the normalizer *(lower is better)*. |

---

## 1. Motivation & Multi-Regime Parallel Dataset (`80,000` Sentences)

Zemberek's legacy [`TurkishSentenceNormalizer.java`](file:///home/dndara/work/zemberek-nlp/normalization/src/main/java/zemberek/normalization/TurkishSentenceNormalizer.java) was designed under a historical constraint: **zero supervised parallel `(noisy -> clean)` sentences**. Without parallel data, it relied on:
1. **Greedy 1-best preprocessing passes** ([`NormalizerPreprocessor.java`](file:///home/dndara/work/zemberek-nlp/normalization/src/main/java/zemberek/normalization/NormalizerPreprocessor.java)): 6 sequential string-rewrite stages (`deasciifyKnownWords` → `toLowerCase` → `replaceCommon` → `normalizeElongations` → `combineNecessaryWords` → `splitNecessaryWords`) that lock in irreversible split/merge decisions before contextual decoding runs.
2. **Small hand-maintained rule lists**: `139` `candidates-manual` entries, `77` `multi-word-replacements`, and `7` hardcoded float penalties.
3. **Generative 2-gram ranking (`SmoothLm`) as the sole arbiter**: maximizing $\log P_{\text{LM}}(w_i \mid w_{i-1})$ favors *high-frequency* neighbors (`"kodu"`, `"fikrimizde"`, `"daha"`, `"yaptı"`) over *faithful* repairs (`"kod mu"`, `"fikrimizi de"`, `"haha"`, `"yaptım"`).
4. **Destructive lowercase & space-padded output**: unconditionally returning `"tamam , yarın ankara'ya gideceğim !"`, discarding casing, apostrophes on OOV proper nouns/acronyms (`AKP'li`, `Trendyol'dan`, `YouTube'da`), and natural punctuation attachment.

### 1.1 Cleaned Multi-Regime Corpus Partition (`75K` Train + `5K` Dedicated Test)

All `15` multi-regime training slices (`75,000` sentences across Heavy, Medium, and Low noise densities) plus the dedicated `test_slice_01` (`5,000` sentences — `80,000` sentences / `1,025,172` Zemberek tokens total) were normalized via the Gemini Batch API (`gemini-4-hydrogen`, `HIGH` thinking, `group_size=25`):
- **Heavy Training Pool (`full_110k/train_slice_01..10`)**: **`50,000` sentences** (`641,643` tokens, `151,570` modified = **`23.62%`** edit density).
- **Medium Training Pool (`medium_110k/train_slice_01..03`)**: **`15,000` sentences** (`193,874` tokens, `26,211` modified = **`13.52%`** edit density, `4,332` splits, `1,892` merges).
- **Low / Light Training Pool (`low_110k/train_slice_01..02`)**: **`10,000` sentences** (`125,159` tokens, `9,089` modified = **`7.26%`** edit density, `1,599` splits, `744` merges).
- **Combined `75K` Multi-Regime Training Pool**: **`75,000` sentences** (`74,997` after dropping `3` exact benchmark fingerprint overlaps; `960,676` tokens, `186,870` modified = `19.45%` overall).
- **Dedicated Unseen Heavy Test Slice (`test_slice_01`)**: **`5,000` sentences** (`64,496` tokens, `15,335` modified = `23.78%`, `2,179` splits, `839` merges), providing sub-percent statistical precision (`±0.18%–0.25%` paired 95% CI on `W-ERR`, `±0.09%` on `CleanFPR`).

Empirical token edit distribution (profiled on heavy `train_slice_01..03`, `192,532` tokens):

| Empirical Token Category | Count (`15K` Slice) | `%` of Total | `%` of Edits | Top Observed Examples (`raw -> gold`) |
| :--- | ---: | ---: | ---: | :--- |
| **`0. Unchanged Exact`** | `146,829` | **`76.26%`** | — | *(>3 out of 4 tokens in Heavy, ~86.5% in Medium, ~92.7% in Low need zero change)* |
| **`1. Pure Diacritic Restoration`** | `15,610` | `8.11%` | **`34.16%`** | `cok->çok` (634), `icin->için` (243), `guzel->güzel` (175), `bır->bir` (124) |
| **`2. Morph / Phonetic / Slang / Typo`** | `10,493` | `5.45%` | **`22.96%`** | `bi->bir` (1068), `baya->bayağı` (105), `cnm->canım` (68), `oluyo->oluyor` (51), `alıcam->alacağım` |
| **`3. Casing-Only Restoration`** | `6,644` | `3.45%` | **`14.54%`** | `tl->TL` (97), `allah->Allah` (36), `türk->Türk` (34), `android->Android`, `youtube->YouTube` |
| **`4. 1:N Splits (Clitics, Units, Open Compounds)`** | `6,574` | `3.41%` | **`14.39%`** | `bende->ben de` (211), `bişey->bir şey` (137), `şuan->şu an` (96), `yada->ya da` (93), `dimi->değil mi` (36), `16gb->16 GB` |
| **`5. N:1 Merges (Compounds & Detached Suffixes)`** | `4,422` *(slots)* | `2.30%` | **`9.68%`** | `hiç+bir->hiçbir` (111), `bir+kaç->birkaç` (55), `bir+çok->birçok` (55), `12+de->12'de`, `tl+'+ye->TL'ye` |
| **`6. Apostrophe + Case / Diacritic`** | `1,386` | `0.72%` | **`3.03%`** | `allahım->Allah'ım` (24), `istanbulda->İstanbul'da` (12), `türkiyede->Türkiye'de` (11), `akpli->AKP'li` |
| **`7. Expressive Elongation Squeeze`** | `574` | `0.30%` | **`1.26%`** | `yaaa->ya` (30), `ayyy->ay` (17), `coooook->çok` |

### 1.2 Three Structural Design Laws

1. **The Asymmetric "Do-No-Harm" Law**: `76.4%` of tokens in Heavy noise, `86.5%` in Medium noise, and `92.7%` in Low noise require zero modification. False corruptions must carry higher training loss than passive misses.
2. **Why Pure Memorization Fails in Agglutinative Turkish**: Even at `75K` parallel sentences (`97,887` unique `1:N` rewrite entries and `5,756` merge entries), a pure parallel-table normalizer reaches only **`44.36%` `W-ERR`** on `test_slice_01` (vs. **`79.37%`** for our full `v2` lattice normalizer). Productive stem+suffix transduction remains essential.
3. **Why Boundary Edits (`1:2` & `2:1`) Belong in the Sequence Decoder**: Splits and merges account for **`24.1%` of all changed tokens**, and top forms (`bende` vs. `ben de`, `oda` vs. `o da`, `yaşta` vs. `yaş da`) are morphologically valid in both readings. Only a **Multi-Span DAG** scored in full left-and-right context can disambiguate them cleanly.

---

## 2. Architecture & Zemberek Tool Integration

### 2.1 Zemberek Components Retained, Augmented, or Dropped

| Zemberek Tool | Verdict | Role in `zemberek.normalization.v2` |
| :--- | :---: | :--- |
| **`TurkishTokenizer`** | **KEEP (1-Pass)** | Tokenizes **once** into non-destructive `SpanToken`s preserving character offsets, adjacency, and casing shape. |
| **`TurkishMorphology` + `InformalTurkishMorphotactics` + `WordGenerator`** | **KEEP (Dual Exact + ASCII)** | Runs **both** `exactInformalMorphology` (`SRC_INFORMAL_EXACT`, preserving author stem vowels on `donuyodu -> donuyordu`) **and** `informalAsciiMorphology` (`ignoreDiacriticsInAnalysis()`), plus TDK-compliant `formatProperOrAbbrTDK` (`Türkçede`, `Azericeye`, `Ankara'yı`, `AKP'li`). |
| **`LatticeDeasciifier`** | **KEEP (Candidate Source)** | Proposes whole-sentence contextual diacritic restorations as `SRC_DEASCIIFIER` arcs (gated off on already-diacriticized clean prose). |
| **`SmoothLm` (`lm.2gram.slm.gz`) + Mined `CleanWordLm` (3-Gram)** | **KEEP + AUGMENT** | Combines Zemberek's `3.34 MiB` `.gz` (`4.73 MiB` raw) `SmoothLm` (`<unk>` floor `-20.0`) with a lock-free **64-bit hashed 3-gram `CleanWordLm`** mined from `1.80M` clean Turkish sentences and compressed in `knowledge.bin.gz` (`v5`) via `MphfCountMap` (`MultiLevelMphf` + 12-bit fingerprints + 8-bit count codebooks = **`2.65 MiB` `.gz` / `3.12 MiB` raw** for `1.13M` n-grams, down from `108.0 MB` `OpenLongIntMap`). |
| **`MultiLevelMphf` (`QuantizedWeightTable` + `MphfCountMap`)** | **KEEP** | Compresses `809,592` active sparse Perceptron features into **`2.55 MiB` `.gz` / `2.62 MiB` RAM** (`perceptron.bin.gz`, `~3.40 B/feature`) and shrinks total `knowledge.bin.gz` (`v5`) from **`30.71 MiB` (`v4`) → `3.99 MiB` (`4.18 MB`) `.gz`** (`-87.0%`, `< 0.30 pp` `W-ERR` delta). |
| **`CompactStemEndingSpellChecker` (CSR `FlatTrie`)** | **KEEP w/ Guards** | `9.2 MB` CSR trie with Q-keyboard edit costs; triggered only on OOV tokens without strong morphological/parallel candidates. |
| **Greedy 6-Pass `NormalizerPreprocessor` & Hardcoded Floats** | **DROP** | Replaced completely by the single-pass Multi-Span Candidate DAG and learned parameters. |

### 2.2 Module Breakdown (`experiment/src/main/java/zemberek/normalization/v2/`)

All classes include inline Javadoc explaining every acronym and feature constant (`G_*`, `R_*`, `F_*`, `T_*`, `SRC_*`):
- [`NormV2Types.java`](file:///home/dndara/work/zemberek-nlp/experiment/src/main/java/zemberek/normalization/v2/NormV2Types.java) — Non-destructive `SpanToken`, `SpanArc` (`SRC_*` bitflags & `HEAD_SYNTAX_*` classes), `SentenceLattice`, `PreparedLattice`, and punctuation-aware `reconstructText`.
- [`ParallelCorpusMiner.java`](file:///home/dndara/work/zemberek-nlp/experiment/src/main/java/zemberek/normalization/v2/ParallelCorpusMiner.java) — Clean-corpus miner (`1,800,000` clean sentences -> `108.0 MB` 3-gram `CleanWordLm`, `CharPhonotacticModel`, `CasingCounts`) + `75K` multi-regime parallel miner (`97,887` `1:N` entries, `4,992` `2:1` merges, `764` `3:1` merges, `792` suffix rules, `370,786` truecasing entries; `151.0 MB` total mined knowledge) + Leave-One-Sentence-Out (`applyLeaveOneSentenceOut`).
- [`SpanCandidateGenerator.java`](file:///home/dndara/work/zemberek-nlp/experiment/src/main/java/zemberek/normalization/v2/SpanCandidateGenerator.java) — Single-pass 10-mechanism Multi-Span Candidate DAG with lock-free morphology/realization caches (`1.76` avg arcs/token, **`98.09%` lowercase span oracle recall**, **`96.49%` truecased span oracle recall**, **`80.04%` full-sentence exact oracle** on unseen `test_slice_01`), compositional chat-shorthand + morphotactic suffix generation (`tskler -> teşekkürler`), `>=5`-consonant subsequence-verified abjad skeleton lookup (`nslsn -> nasılsın`), uppercase acronym protection (`TSK`), exact boundary-to-boundary span `LLR`, and symmetric clitic-transparency skip-bigram `LLR`.
- [`SpanLatticePerceptronNormalizer.java`](file:///home/dndara/work/zemberek-nlp/experiment/src/main/java/zemberek/normalization/v2/SpanLatticePerceptronNormalizer.java) — **Normalizer 1**: Joint Semi-Markov Cost-Augmented Margin (`PA-I`, 6 epochs) Averaged Perceptron with `19` continuous features (`F_*`) + `23` punctuation-bridging sparse lexical/morphological templates (`T_*`; `862,887` active MPHF features = **`2,862.4 KB`**).
- [`ObliviousGbdt.java`](file:///home/dndara/work/zemberek-nlp/experiment/src/main/java/zemberek/normalization/v2/ObliviousGbdt.java) & [`TwoStageGbdtNormalizer.java`](file:///home/dndara/work/zemberek-nlp/experiment/src/main/java/zemberek/normalization/v2/TwoStageGbdtNormalizer.java) — **Normalizer 2**: Pure-Java Newton histogram-boosted symmetric (`CatBoost`-style oblivious) tree cascade (`80` depth-5 `KeepGatekeeper` over `16` `G_*` features + `360` depth-6 `CandidateReranker` over `45` `R_*` features = **`115.1 KB`**), decoded via exact Semi-Markov MEMM `logSoftmax`.
- [`NormalizationV2BenchmarkRunner.java`](file:///home/dndara/work/zemberek-nlp/experiment/src/main/java/zemberek/normalization/v2/NormalizationV2BenchmarkRunner.java) — Multi-regime (`heavy` + `medium` + `low`) training, 8-benchmark accuracy evaluation (5 real-world + 3 controlled `1K` synthetic benchmarks), ablation, scaling, and single-thread latency/throughput runner ([`norm_v2_benchmark_results.json`](file:///home/dndara/work/zemberek-nlp/experiment/target/norm_v2_results/norm_v2_benchmark_results.json)).
- [`NormV2ModelStore.java`](file:///home/dndara/work/zemberek-nlp/experiment/src/main/java/zemberek/normalization/v2/NormV2ModelStore.java), [`NormalizationInspector.java`](file:///home/dndara/work/zemberek-nlp/experiment/src/main/java/zemberek/normalization/v2/NormalizationInspector.java), & [`ZemberekDemoServer.java`](file:///home/dndara/work/zemberek-nlp/experiment/src/main/java/zemberek/demo/ZemberekDemoServer.java) ([`run_demo.sh`](file:///home/dndara/work/zemberek-nlp/experiment/run_demo.sh)) — Binary model snapshot cache (`~7.5s` warm reload) and zero-dependency local interactive Web Demo (`http://127.0.0.1:8080/`) for live keystroke normalization, candidate lattice inspection, morphology, and tokenization.

---

## 3. Accuracy Benchmarks (`75K` Multi-Regime Training Pool)

### 3.1 Real-World Unseen Benchmarks (5 Datasets)

Evaluated across 5 strictly unseen real-world benchmarks: `test_slice_01` (`5,000` unseen heavy sentences), `nasty_5k` (`5,000` independent social-media sentences), `MultiLexNorm` (`143` W-NUT 2021 sentences), `GECTurk Clitics` (`507` formal clitic split/merge sentences), and `GECTurk Clean` (`510` already-clean formal sentences):

| System (`75K` Train + `1.80M` Clean) | Classifier RAM | `test_slice_01` `W-ERR` | `test_slice_01` `SentEM-Lo / Ca` | `test_slice_01` `CleanFPR` | `nasty_5k` `W-ERR` | `nasty_5k` `SentEM-Lo / Ca` | `MultiLexNorm` `W-ERR` | `GECTurk Clitics` `W-ERR` | `GECTurk Clean` `CleanFPR` |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `Identity (Do-Nothing)` | `0 KB` | `0.00%` | `22.48% / 14.32%` | `0.00%` | `0.00%` | `0.04% / 0.02%` | `0.00%` | `0.00%` | `0.00%` |
| `Zemberek Legacy (Pattern Deasc)` | `~108 MB` | `61.70%` | `46.88% / 4.28%` | `1.66%` | `80.79%` | `38.78% / 10.50%` | `58.30%` | `-2.06%` | `1.20%` |
| `Zemberek HEAD (LatticeDeasc)` | `~110 MB` | `61.82%` | `47.08% / 4.26%` | `1.68%` | `80.98%` | `39.34% / 10.68%` | `58.30%` | `-2.06%` | `1.23%` |
| `Baseline: Parallel Table Only (75K)` | *(tables)* | `44.36%` | `36.24% / 16.16%` | `0.52%` | `49.30%` | `4.40% / 0.96%` | `29.88%` | `+1.15%` | `0.43%` |
| **`Norm1: SpanLatticePerceptron (75K)`** | **`2,862.4 KB`** | **`79.22%`** | `66.04% / 56.86%` | `1.67%` | **`89.36%`** | `58.14% / 46.68%` | `65.77%` | **`+43.04%`** | `2.00%` |
| **`Norm2b: TwoStageGBDT + Gate (75K)`** | **`115.1 KB`** | `77.55%` | `64.94% / 55.80%` | **`1.32%`** | `88.91%` | `58.00% / 46.76%` | **`66.60%`** | `+26.30%` | `1.79%` |
| **`Hybrid: OOF-GBDT + Perceptron (75K)`** | **`2,808.6 KB`** | **`79.63%`** | **`67.04% / 57.36%`** | `1.53%` | **`89.36%`** | **`58.66% / 47.06%`** | `65.15%` | **`+44.34%`** | `1.68%` |

- **`test_slice_01` Multi-Span Lattice Oracle (`75K`)**: `avgArcs/tok = 1.76`, **Lowercase Span Recall = `98.09%`**, **Truecased Span Recall = `96.49%`**, **Full-Sentence Exact Oracle = `80.04%`**.
- **Key Accuracy Findings**:
  - **`Hybrid: OOF-GBDT + Perceptron`** leads overall accuracy at **`79.63%` `W-ERR`** (`+17.81 pts` over `Zemberek HEAD`, `67.04% / 57.36%` sentence exact match) on `test_slice_01` and **`89.36%` `W-ERR`** on `nasty_5k` (`58.66% / 47.06%` `SentEM`), while keeping `CleanFPR` (`1.53%`) below `Zemberek HEAD` (`1.68%`).
  - **`Norm1: SpanLatticePerceptron`** achieves `79.22%` (`test_slice_01`) and ties `Hybrid` at **`89.36%` `W-ERR`** on `nasty_5k` from a single model.
  - **`Norm2b: TwoStageGBDT + Gate`** packs into **`115.1 KB`**, delivers the **lowest false-positive rate** on `test_slice_01` (**`1.32%` `CleanFPR`**) and `MultiLexNorm` (`2.86%` vs `4.29%` on `Zemberek HEAD`), and leads cross-domain `MultiLexNorm` (**`66.60%` `W-ERR`**).

### 3.2 Controlled Synthetic Diagnostic Benchmarks (`3 x 1,000` Multi-Domain Sentences)

Evaluated on three zero-leakage multi-domain (`news_economy`, `conversational`, `science_knowledge`, `parliament_civic`) diagnostic sets under [`synthetic_1k/`](file:///home/dndara/data/turkish/normalization/benchmarks/synthetic_1k/README.md):
1. **`Synth Asciified (1K)`**: `1,000` verified sentences with 100% Turkish diacritics stripped (`5,914` asciified words / `11,969` total).
2. **`Synth Typo d=1 (1K)`**: `1,000` verified sentences with `1,646` Q-keyboard distance-1 non-word typos (`sub_kbd=606`, `swap=395`, `del=334`, `ins=311`).
3. **`Synth Clean (1K)`**: `1,000` strictly verified zero-error sentences (`11,650` words) measuring false corruption and casing/punctuation preservation.

| System (`75K` Train + `1.80M` Clean) | `Synth Asciified` `W-ERR` | `Synth Asciified` `SentEM-Lo / Ca` | `Synth Typo d=1` `W-ERR` | `Synth Typo d=1` `SentEM-Lo / Ca` | `Synth Clean` `CleanFPR` | `Synth Clean` `SentEM-Lo / Ca` |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| `Identity (Do-Nothing)` | `0.00%` | `0.00% / 0.00%` | `0.00%` | `0.00% / 0.00%` | **`0.00%`** | `100.00% / 100.00%` |
| `Zemberek Legacy (Pattern Deasc)` | `98.82%` | `93.70% / 0.00%` | `87.61%` | `81.10% / 0.00%` | `0.10%` | `98.80% / 0.00%` |
| `Zemberek HEAD (LatticeDeasc)` | **`99.14%`** | **`95.50% / 0.00%`** | **`87.61%`** | **`81.10% / 0.00%`** | `0.09%` | `98.90% / 0.00%` |
| `Baseline: Parallel Table Only (75K)` | `42.80%` | `4.50% / 4.50%` | `-0.67%` | `0.40% / 0.40%` | `0.23%` | `98.00% / 95.60%` |
| **`Norm1: SpanLatticePerceptron (75K)`** | `96.81%` | `85.60% / 84.10%` | **`78.49%`** | **`69.80% / 67.80%`** | `0.36%` | `96.90% / 94.50%` |
| **`Norm2b: TwoStageGBDT + Gate (75K)`** | **`97.79%`** | **`88.70% / 87.20%`** | `75.70%` | `64.80% / 63.10%` | **`0.26%`** | `97.60% / 95.20%` |
| **`Hybrid: OOF-GBDT + Perceptron (75K)`** | `97.43%` | `87.90% / 86.40%` | `67.31%` | `54.80% / 53.40%` | `0.27%` | **`97.70% / 95.30%`** |

- **Synthetic Stress Insights**:
  - **Pure Deasciification (`Synth Asciified 1K`)**: `TwoStageGBDT + Gate` restores **`97.79%`** of asciified words while preserving **`87.20%` full-sentence exact casing + punctuation** (`0.00%` cased EM on `Zemberek HEAD`, which lowercases and detaches punctuation).
  - **Clean Preservation (`Synth Clean 1K`)**: `TwoStageGBDT + Gate` (`0.26%` `CleanFPR`) and `Hybrid` (`0.27%` `CleanFPR`) alter fewer than 3 tokens per 1,000 on clean multi-domain prose while preserving **`95.2%–95.3%`** exact cased sentences intact.
  - **Isolated Formal Typos (`Synth Typo d=1 1K`)**: Because the ML normalizers enforce conservative OOV preservation learned from social text (avoiding false rewrites of unseen proper names/foreign terms), they trade off aggressive blind spellchecking on rare formal science/parliament OOVs (`78.49%` `Perceptron` / `75.70%` `GBDT` vs `87.61%` lowercase-only `Zemberek HEAD`), while delivering `67.80%` cased exact sentence match (`0.00%` on `Zemberek HEAD`).

---

## 4. Single-Thread Speed & Per-Sentence Latency (`75K` Multi-Regime)

Once JIT and the bounded morphological realization caches warm up, **all three `v2` ML normalizers run `15%–41%` faster single-threaded than `Zemberek HEAD` on noisy Turkish text while cutting `P95` tail latency by `33%–44%`** (because informal suffixes, shorthands, induced rules, splits, and merges resolve before edit-distance spellchecking is needed):

### A. Heavy Noisy Web/Social Workload — `test_slice_01` (`5,000` sentences, `64,496` tokens)

| System (`75K` Multi-Regime) | Cold 1T (`Tok/s`) | **Warm 1T (`Tok/s`)** | Warm 1T (`Sent/s`) | `Mean` (`ms`) | `P50` (`ms`) | **`P95` (`ms`)** |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| `Zemberek Legacy (Pattern Deasc)` | `10,513` | `11,216` | `870` | `1.149` | `0.919` | `2.814` |
| `Zemberek HEAD (LatticeDeasc)` | `10,883` | `11,550` | `895` | `1.117` | `0.885` | `2.761` |
| **`Norm1: SpanLatticePerceptron (75K)`** | `5,511` | **`15,536` (`+35%`)** | `1,204` | `0.830` | `0.780` | `1.643` |
| **`Norm2a: ObliviousGBDT Reranker (75K)`** | `5,931` | **`16,971` (`+47%`)** | **`1,316`** | **`0.760`** | **`0.718`** | **`1.494`** |
| **`Norm2b: TwoStageGBDT + Gate (75K)`** | `6,035` | **`16,266` (`+41%`)** | `1,261` | `0.793` | `0.746` | `1.550` |
| **`Hybrid: OOF-GBDT + Perceptron (75K)`** | `5,298` | **`13,639` (`+18%`)** | `1,057` | `0.946` | `0.875` | `1.862` |

### B. Social Media Workload — `nasty_5k` (`5,000` sentences, `61,993` tokens)

| System (`75K` Multi-Regime) | Cold 1T (`Tok/s`) | **Warm 1T (`Tok/s`)** | Warm 1T (`Sent/s`) | `Mean` (`ms`) | `P50` (`ms`) | **`P95` (`ms`)** |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| `Zemberek Legacy (Pattern Deasc)` | `8,588` | `9,086` | `733` | `1.364` | `1.140` | `3.035` |
| `Zemberek HEAD (LatticeDeasc)` | `7,877` | `8,396` | `677` | `1.477` | `1.276` | `3.144` |
| **`Norm1: SpanLatticePerceptron (75K)`** | `5,021` | **`11,028` (`+31%`)** | **`889`** | **`1.124`** | **`1.081`** | **`1.809`** |
| **`Norm2a: ObliviousGBDT Reranker (75K)`** | `5,095` | **`10,918` (`+30%`)** | `881` | `1.136` | `1.092` | `1.835` |
| **`Norm2b: TwoStageGBDT + Gate (75K)`** | `4,951` | **`10,819` (`+29%`)** | `873` | `1.146` | `1.095` | `1.856` |
| **`Hybrid: OOF-GBDT + Perceptron (75K)`** | `4,830` | **`9,696` (`+15%`)** | `782` | `1.279` | `1.241` | `2.044` |

### C. Clean Formal Turkish Workload — `GECTurk Clean` (`510` sentences, `11,722` tokens, `~23` tok/sent)

| System (`75K` Multi-Regime) | Cold 1T (`Tok/s`) | **Warm 1T (`Tok/s`)** | Warm 1T (`Sent/s`) | `Mean` (`ms`) | `P50` (`ms`) | `P95` (`ms`) |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| `Zemberek Legacy (Pattern Deasc)` | `17,202` | `22,592` | `983` | `1.017` | `0.846` | `2.391` |
| `Zemberek HEAD (LatticeDeasc)` | `21,887` | `22,282` | `969` | `1.031` | `0.840` | `2.396` |
| **`Norm1: SpanLatticePerceptron (75K)`** | `8,075` | `19,654` | `855` | `1.169` | `0.938` | `3.241` |
| **`Norm2a: ObliviousGBDT Reranker (75K)`** | `12,112` | **`20,493`** | **`892`** | **`1.121`** | **`0.891`** | **`3.130`** |
| **`Norm2b: TwoStageGBDT + Gate (75K)`** | **`13,461`** | `18,725` | `815` | `1.227` | `0.979` | `3.390` |
| **`Hybrid: OOF-GBDT + Perceptron (75K)`** | `12,362` | `17,022` | `741` | `1.350` | `1.086` | `3.607` |

---

## 5. Component Ablations, Data Scaling, & Production Profiles

### 5.1 Candidate-Source Ablation (`45K` `SpanLatticePerceptron`)

Even with tens of thousands of parallel sentences, **every finite-state Zemberek module contributes additive `W-ERR` gains**:

| Ablation Variant (`45K` Train) | Held-Out Heavy Oracle | Held-Out Heavy `W-ERR` | `nasty_5k` `W-ERR` | Heavy `CleanFPR` | Net Contribution |
| :--- | ---: | ---: | ---: | ---: | :--- |
| **`Full SpanLatticePerceptron`** | **`97.88%`** | **`77.64%`** | **`88.35%`** | `1.22%` | Full synergy across all 5 candidate sources |
| `- w/o Parallel Rewrite/Merge Tables` | `95.35%` | `57.91%` | `73.21%` | `1.55%` | **`+19.73 pts`** (Heavy), **`+15.14 pts`** (`nasty_5k`) |
| `- w/o Multi-Span Split/Merge Arcs` | `96.54%` | `69.04%` | `83.02%` | `0.45%` | **`+8.60 pts`** (Heavy), **`+5.33 pts`** (`nasty_5k`), **`+47.3%`** (`Clitics`) |
| `- w/o Zemberek Morph & LatticeDeasc` | `97.69%` | `75.73%` | `86.55%` | `1.25%` | **`+1.91 pts`** (Heavy), **`+1.80 pts`** (`nasty_5k`) |
| `- w/o Guarded CSR SpellChecker` | `97.11%` | `75.77%` | `86.97%` | `1.10%` | **`+1.87 pts`** (Heavy), **`+1.38 pts`** (`nasty_5k`) |
| `- w/o Induced Suffix / Hypocoristics` | `97.84%` | `77.48%` | `88.33%` | `1.24%` | **`+0.16 pts`** (Heavy), **`+0.02 pts`** (`nasty_5k`) |

### 5.2 Training Data Scaling (`2,500 -> 74,997` Sentences)

*(Rows `2.5K..45K` measured on held-out heavy `slice_04` during scaling sweeps; `50K` Heavy-Only and `75K` Multi-Regime measured on dedicated `test_slice_01`.)*

| Parallel Train ($N$) | Regime Mix | Oracle Lower | `Norm1 Perc` Heavy | `Norm2 GBDT` Heavy | `Hybrid OOF` Heavy | `Norm1 Perc` `nasty_5k` | `Norm2 GBDT` `nasty_5k` | `Hybrid OOF` `nasty_5k` |
| ---: | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **`2,500`** | `0.5H` | `97.09%` | `71.28%` | `70.60%` | `70.93%` | `84.77%` | `84.29%` | `84.43%` |
| **`5,000`** | `1H` | `97.27%` | `72.96%` | `71.57%` | `71.58%` | `85.72%` | `85.30%` | `84.85%` |
| **`10,000`** | `2H` | `97.47%` | `74.02%` | `73.41%` | `73.23%` | `86.88%` | `86.40%` | `86.03%` |
| **`15,000`** | `3H` | `97.57%` | `75.16%` | `74.13%` | `74.03%` | `87.33%` | `87.06%` | `86.68%` |
| **`25,000`** | `5H` | `97.70%` | `76.23%` | `75.22%` | `75.53%` | `87.88%` | `87.70%` | `87.38%` |
| **`35,000`** | `7H` | `97.80%` | `77.15%` | `76.00%` | `76.90%` | `88.45%` | `88.17%` | `88.31%` |
| **`44,992`** | `9H` | `97.88%` | `77.64%` | `76.44%` | `77.33%` | `88.35%` | `88.51%` | `88.62%` |
| **`49,999`** | `10H` (`test_01`) | `98.04%` | `78.61%` | `77.57%` | `78.90%` | `89.11%` | `88.75%` | `89.05%` |
| **`74,997`** | **`10H+3M+2L`** (`test_01`) | **`98.09%`** | **`79.22%`** | **`77.55%`** | **`79.63%`** | **`89.36%`** | **`88.91%`** | **`89.36%`** |

### 5.3 Recommended Production Profiles for `zemberek-normalization`

1. **Binary Resource Serialization**: Pack `MinedKnowledge` (`CleanWordLm` arrays + parallel `1:N`/`2:1`/`3:1` tables + `CasingCounts`) alongside `ObliviousGbdt` (`115.1 KB`) and `QuantizedWeightTable` (`2.80 MB`) into a single binary resource bundle for `< 300 ms` cold startup.
2. **Two Runtime Profiles Behind One API**:
   - **`Quality` (`Hybrid: OOF-GBDT + Perceptron`)**: Highest accuracy (`79.63%` Heavy `W-ERR`, `89.36%` Social `W-ERR`, `67.04%` `SentEM-Lo`, `1.53%` `CleanFPR`, `0.27%` `Synth Clean` `CleanFPR`) at **`13,639 Tok/s`** single-thread (`P95 = 1.862 ms`).
   - **`Fast / Compact` (`TwoStageGBDT + Gate` or `SpanLatticePerceptron`)**: **`16,266 / 15,536 Tok/s`** single-thread (`P95 = 1.550 / 1.643 ms`), with `TwoStageGBDT + Gate` requiring only **`115.1 KB`** of classifier weights and achieving **`1.32%` `CleanFPR`** (`0.26%` on `Synth Clean 1K`).
