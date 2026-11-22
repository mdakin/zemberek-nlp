# Turkish Normalization v2.3 — CPU & Allocation Profiling Report

**Date**: `2026-11-15`  
**Target Codebase**: `zemberek.normalization.v2` & `zemberek.normalization.deasciifier.latticedeasciifier`  
**Runtime / Profiler**: OpenJDK `25.0.4.1` LTS (`Temurin-25.0.4.1+1`), JDK Flight Recorder (`JFR`, `1 ms` `jdk.ExecutionSample` + `jdk.ObjectAllocationSample` with `-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints`), single-threaded (`1T`) execution.

---

## 1. Executive Summary

Single-threaded CPU, allocation, and nanosecond stage profiling across four workloads (`test_slice_01` heavy noisy, `nasty_5k` social media, `synth_asciified_1k` pure ASCII, and `synth_clean_1k` clean formal prose — both **warm-cache** and **cold-cache**) revealed three major surprises:

1. **Candidate lattice generation (`SpanCandidateGenerator.buildLattice`) dominates 81%–86% of warm runtime and 94%–95% of cold runtime** across *all* `v2.3` models (`GBDT`, `Perceptron`, and `Hybrid`). Statistical decoding itself is already very fast (`106–130 µs/sent` for `TwoStageGBDT`, `124 µs/sent` for `Perceptron`, `2.5 µs/sent` for `reconstructText`).
2. **Half of all warm CPU (`45.9%–52.4%`, including `49.4%` on 100% clean formal Turkish!) comes from a single call (`latticeDeasciifier.deasciifyKnownWords` at `SpanCandidateGenerator.java:523`)** due to a `1,024`-entry cache-cap bug in `LatticeDeasciifier.java:74` (whose Javadoc specifies `32,768`), uncached morphological regeneration on already-diacriticized words, and an over-eager `oovAsciiWords > 0` sentence trigger that runs whole-sentence deasciification whenever a clean sentence contains a single English brand/name.
3. **Nearly a quarter of all cold-cache CPU (`23.5%`) comes from 2 lines (`SpanCandidateGenerator.java:2403-2404`)** that concatenate *every* adjacent word pair in every sentence (`t1 + t2`) and invoke three morphological analyzers *before* checking the cheap closed-compound acceptance predicate 19 lines below.
4. **`KeepGatekeeper` currently *slows down* warm `GBDT` by ~7%** (`608 µs/sent` vs `567 µs/sent` on `synth_clean_1k`; `25.5s` vs `23.7s` on `6x test_slice_01`) because `3.2x` positive class weighting + `gateSkipThreshold = 0.035f` skips only `6.9%–10.2%` of mutable tokens while costing `~38–41 µs/sent` (`5.2%–7.2%` CPU).

Applying the localized, zero-accuracy-drift fixes cataloged in **Section 4 (`P1`–`P6`)** is projected to yield a **~1.8x–2.2x single-thread warm speedup** (`~15K → ~28K–33K tok/s` on noisy text; `~24K → ~45K+ tok/s` on clean formal text), a **~30%+ cold-cache speedup**, and **~3.5s faster server startup** (`~7.5s → ~4.0s`).

---

## 2. Single-Thread Stage Breakdown (Warm vs. Cold)

### 2.1 Warm-Cache Stage Timings (`µs / sentence`)

Measured after 2 full C2 JIT + cache warmup passes over `test_slice_01` (`5,000` sentences) and `synth_clean_1k` (`1,000` sentences), plus one dataset-specific cache warm pass:

| Pipeline Stage (`1-Thread`, Warm) | `test_slice_01` (`5K` heavy, `64.5K` tok) | `nasty_5k` (`5K` social, `62.0K` tok) | `synth_asciified_1k` (`1K` ASCII, `14.0K` tok) | `synth_clean_1k` (`1K` clean, `13.8K` tok) |
| :--- | ---: | ---: | ---: | ---: |
| `NormV2Types.spanTokenize` | `12.0 µs` | `10.0 µs` | `9.9 µs` | `11.1 µs` |
| **`buildLattice` (Full, no Gate)** | **`800.4 µs`** | **`1,631.1 µs`** | **`2,008.3 µs`** | **`529.1 µs`** |
| `buildLattice` (with `KeepGatekeeper`) | `616.4 µs` | `820.1 µs` | `874.7 µs` | `444.9 µs` |
| ↳ *of which `shouldGateSkip` callback* | *`40.7 µs`* | *`36.1 µs`* | *`37.4 µs`* | *`37.5 µs`* |
| ↳ *Gatekeeper mutable-token skip rate* | *`10.2%` (`5,759 / 56,230`)* | *`6.9%` (`3,883 / 56,095`)* | *`5.3%` (`644 / 12,256`)* | *`8.4%` (`1,011 / 12,005`)* |
| `TwoStageGBDT.decodeSpanDp` (Full lat) | `130.6 µs` | `186.1 µs` | `192.6 µs` | `91.0 µs` |
| `TwoStageGBDT.decodeSpanDp` (Gated lat) | `106.1 µs` | `153.1 µs` | `159.3 µs` | `71.6 µs` |
| `Perceptron.decodeViterbi` (`B = 4`) | `124.2 µs` | `165.6 µs` | `167.8 µs` | `99.4 µs` |
| `Hybrid`: `populateLatticeGbdtScores` | `96.8 µs` | `142.6 µs` | `146.2 µs` | `65.5 µs` |
| `Hybrid`: `populateGbdt` + `decodeViterbi` | `198.8 µs` | `281.6 µs` | `286.1 µs` | `144.7 µs` |
| `NormV2Types.reconstructText` | `2.6 µs` | `2.5 µs` | `2.8 µs` | `2.2 µs` |

### 2.2 Clean End-to-End Single-Thread Throughput (Warm, Uninstrumented)

| Normalizer Engine (`1-Thread`, Warm) | `test_slice_01` (`µs/sent` \| `tok/s`) | `nasty_5k` (`µs/sent` \| `tok/s`) | `synth_asciified_1k` (`µs/sent` \| `tok/s`) | `synth_clean_1k` (`µs/sent` \| `tok/s`) |
| :--- | ---: | ---: | ---: | ---: |
| `Zemberek HEAD` (`TurkishSentenceNormalizer`) | `1,107.7 µs` \| `11,645` | `1,656.9 µs` \| `7,483` | `1,728.2 µs` \| `8,105` | `679.1 µs` \| `20,254` |
| **`TwoStageGBDT Full`** | `849.1 µs` \| `15,191` | `1,479.6 µs` \| `8,380` | `1,730.1 µs` \| `8,096` | **`566.8 µs` \| `24,266`** |
| **`TwoStageGBDT + Gate`** | `840.4 µs` \| `15,349` | `1,453.8 µs` \| `8,528` | `1,596.5 µs` \| `8,774` | `608.2 µs` \| `22,615` |
| **`SpanLatticePerceptron`** | `852.2 µs` \| `15,136` | **`1,445.6 µs` \| `8,577`** | **`1,445.1 µs` \| `9,693`** | `576.2 µs` \| `23,870` |
| **`Hybrid (OOF-GBDT + Perceptron)`** | `968.5 µs` \| `13,319` | `1,530.4 µs` \| `8,102` | `1,542.5 µs` \| `9,081` | `671.3 µs` \| `20,488` |

*(Note: Across a longer 6-pass `30,000`-sentence JFR run on `test_slice_01`, `GBDT Full` completed in `23.70s` vs `25.50s` for `GBDT + Gate`, confirming that `KeepGatekeeper` currently incurs a net ~7% slowdown once caches are warm.)*

### 2.3 Cold-Cache vs. Warm-Cache Comparison (`GBDT + Gate`, `1-Thread`)

| Dataset | Cold Runtime Caches (`tok/s`) | Truly Cold (`morphology.invalidateCache()` + fresh informal caches) | Warm Caches (`tok/s`) | Cold → Warm Gap |
| :--- | ---: | ---: | ---: | :---: |
| `test_slice_01` (`5,000` sents) | `2,317.1 µs/sent` (`5,567 tok/s`) | **`2,321.6 µs/sent`** (`5,556 tok/s`: `build=2,185.7 µs [94.1%]`, `decode=135.8 µs [5.9%]`) | `804.2 µs/sent` (`16,040 tok/s`) | **`2.89x`** |
| `synth_clean_1k` (`1,000` sents) | `1,949.9 µs/sent` (`7,054 tok/s`) | **`1,871.9 µs/sent`** (`7,348 tok/s`: `build=1,776.9 µs [94.9%]`, `decode=95.0 µs [5.1%]`) | `526.3 µs/sent` (`26,133 tok/s`) | **`3.56x`** |

---

## 3. JFR CPU & Allocation Hotspot Summary

### 3.1 Inclusive CPU Attribution by Originating `v2` Call-Site (`jdk.ExecutionSample`)

Percentage of total main-thread CPU samples across the six JFR recordings:

| Originating Call-Site (`Class.method:line`) | Warm `GBDT+Gate` (`21.8K` smp) | Warm `GBDT Full` (`20.4K` smp) | Warm `Perceptron` (`21.7K` smp) | Warm `Hybrid` (`22.6K` smp) | Warm Clean `GBDT+Gate` (`18.7K` smp) | Cold `GBDT+Gate` (`28.7K` smp) |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| **`SpanCandidateGenerator.buildLattice` (total)** | **`84.8%`** | **`82.8%`** | **`81.1%`** | **`72.8%`** | **`85.7%`** | **`81.0%`** *(+`12.5%` `<init>`)* |
| ↳ `buildLattice:523` (`latticeDeasciifier.deasciifyKnownWords`) | **`49.9%`** | **`52.4%`** | **`51.6%`** | **`45.9%`** | **`49.4%`** | `16.9%` |
| ↳ `buildLattice:688` (`addAmbiguityPreservingSplits`) | `8.0%` | `8.2%` | `7.7%` | `7.0%` | `8.1%` | `5.8%` |
| ↳ `buildLattice:629` (`shouldGateSkip` / `KeepGatekeeper`) | `5.9%` | `0.0%` | `0.0%` | `0.0%` | `8.2%` | `2.1%` |
| ↳ `buildLattice:678` (`addProperAcronymAndNumeralCandidates`) | `4.5%` | `4.7%` | `4.5%` | `4.2%` | `7.4%` | `<1.5%` |
| ↳ `buildLattice:715` (`materializeArc`) | `2.6%` | `3.4%` | `3.1%` | `2.8%` | `2.0%` | `<1.5%` |
| ↳ `buildLattice:723` (`addMultiTokenMerges`) | `2.8%` | `2.8%` | `3.0%` | `2.6%` | `3.1%` | **`24.4%`** *(`23.5%` at `:2404`!)* |
| ↳ `buildLattice:704` (`addGuardedSpellCandidates` / CSR trie) | `<1.5%` | `1.5%` | `<1.5%` | `<1.5%` | `<1.0%` | **`14.1%`** |
| ↳ `buildLattice:658` (`addMorphologicalRealizations`) | `<1.5%` | `<1.5%` | `<1.5%` | `<1.5%` | `<1.0%` | `6.2%` |
| **`SpanCandidateGenerator.<init>:374` (`buildAbjad5Index`)** | — | — | — | — | — | **`12.5%`** *(`~3.5s` startup)* |
| **`TwoStageGbdtNormalizer.decodeSpanDp` / `populateLatticeGbdtScores`** | **`14.7%`** | **`16.7%`** | — | **`14.1%`** | **`13.7%`** | `5.8%` |
| ↳ `decodeSpanDp:448` / `populate:370` (`new float[24][6]` alloc/init) | `4.5%` | `4.5%` | — | `3.9%` | `4.0%` | `2.1%` |
| ↳ `computeSlotLmAndMaxima:824` (`computeSpanLmComparison`) | `4.4%` | `5.8%` | — | `4.5%` | `4.3%` | `<1.5%` |
| ↳ `ObliviousGbdt.predictLogit` (`360` depth-6 trees) | `3.6%` | `3.8%` | — | `3.4%` | `3.1%` | `1.4%` |
| **`SpanLatticePerceptronNormalizer.decodeViterbi`** | — | — | **`18.4%`** | **`12.6%`** | — | — |
| ↳ `decodeViterbi:692` (`extractDenseFeatures` → `computeSpanLmComparison`) | — | — | **`12.3%`** | `7.6%` | — | — |
| ↳ `decodeViterbi:683,708` (`QuantizedWeightTable.get` sparse MPHF) | — | — | `4.1%` | `2.8%` | — | — |

### 3.2 Top Self-Time Leaf Methods (`warm_gbdt_gated.jfr` & `warm_perceptron.jfr`)

| Method | Warm `GBDT+Gate` Self % | Warm `Perceptron` Self % | Primary Caller |
| :--- | ---: | ---: | :--- |
| `java.util.HashMap.getNode` | `6.9%` | `7.4%` | `cleanOpenBigrams.get(p1 + " " + p2)`, `parallel1ToN`, `casingLexicon`, `Deasciifier` |
| `java.lang.String.length` / `equals` | `8.8%` | `9.3%` | Suffix scans (`OOV_PROPER_INFLECT_SUFS`, `QUESTION_TAILS`), map lookups |
| `RuleBasedAnalyzer.advance` + `SuffixTransition.hasSurfaceForm` + `AttributeToSurfaceCache` | `8.6%` | `8.1%` | Uncached `LatticeDeasciifier` (`analyzePlainAsciiUncached` & `buildGeneralTokenLattice`) |
| `deasciifier.QuantizedWeightTable.get` + `MultiLevelMphf.getSeed` | `4.3%` | `8.3%` | `LatticeDeasciifier` beam scoring + `Perceptron` sparse feature lookups |
| `TwoStageGbdtNormalizer.decodeSpanDp:448` | `4.5%` | — | Per-sentence `new float[24][6]` 25-object array allocation |
| `TurkishAlphabet.startsWithIgnoreDiacritics:613` | `3.5%` | `3.5%` | `informalAsciiMorphology` stem/suffix matching inside `LatticeDeasciifier` |
| `ConcurrentHashMap.get` + `tabAt` | `4.1%` | `4.5%` | `morphSummaryCache`, `plainTokenCache`, `AnalysisCache` |
| `ObliviousGbdt.predictLogit` | `4.6%` | — | `KeepGatekeeper` (`80` trees) + `CandidateReranker` (`360` trees) |
| `LmVocabulary.indexOf` / `indexOfTurkishWord` | `2.5%` | `3.6%` | Repeated `SmoothLm` string-to-ID MPHF hashing in `lmBigram` |
| `ParallelCorpusMiner$CharPhonotacticModel.scoreWord` | `1.5%` | `1.1%` | `"^" + word + "$"` String alloc + per-char `Math.log` |
| `ParallelCorpusMiner$OpenLongIntMap.get` | `1.4%` | `2.0%` | `CleanWordLm` 3-gram hash probes |
| `NormV2Types$SpanToken.isRandomLaugh:168` | `0.9%` | `1.1%` | `RANDOM_LAUGH_RE.matcher(ascii).matches()` called up to `7x`/token |

### 3.3 Top Avoidable Heap Allocation Sites (Steady-State Warm)

Excluding occasional TLAB-giant sample outliers, steady-state allocation pressure (`~270 MB / 1,000 sentences`!) is dominated by:
1. **Uncached `LatticeDeasciifier` morphology & surface generation** (`SearchPath.getCopy`, `SingleAnalysis.getMorphemes`, `RuleBasedAnalyzer.advance`, `WordGenerator.advance/search`): **~45% of steady-state allocations** — directly eliminated by `P1`.
2. **`TurkishAlphabet.toAscii` (`:98` + `:91`)**: **~6.2% of steady-state allocations** (`~510 MB` per `30K` sentences) — mostly from calling `NormV2Types.toAscii(qt)` on 44 constant strings in `QUESTION_TAILS` on every token (`P3.2`).
3. **`NormV2Types$SpanToken.isRandomLaugh:168`**: **~2.4% of steady-state allocations** (`~200 MB` of `java.util.regex.Matcher` + `int[]` per `30K` sentences) — eliminated by `P5.4`.

---

## 4. Catalog of "Small Change → Big Gain" Opportunities (`P1`–`P6`)

All opportunities below are **strictly speed/allocation optimizations** designed to preserve identical or near-identical normalization accuracy.

### `P1` — Fix `LatticeDeasciifier` Cache Cap (`1,024 → 32,768`), Cache Clean Diacritic Words, and Tighten Clean-Sentence Trigger
* **Impact**: **Eliminates ~35%–45% of total warm CPU across *all* normalizers (`~1.6x–1.9x` overall speedup; `>2x` on clean formal text)**.
* **Locations**:
  * `normalization/src/main/java/zemberek/normalization/deasciifier/latticedeasciifier/LatticeDeasciifier.java` (lines `23`, `74`, `296–312`, `323`)
  * `normalization/src/main/java/zemberek/normalization/deasciifier/latticedeasciifier/TurkishTextCodec.java` (lines `44–50`)
  * `experiment/src/main/java/zemberek/normalization/v2/SpanCandidateGenerator.java` (lines `484–492`, `512–523`)
* **Root Causes**:
  1. **Cache size bug**: `LatticeDeasciifier.java:23` documents a `32,768`-entry `plainTokenCache`, but `line 74` hardcodes `private static final int MAX_PLAIN_TOKEN_CACHE = 1_024;` with no eviction (`if (plainTokenCache.size() < MAX_PLAIN_TOKEN_CACHE)` at `line 323`). After ~80 sentences, `plainTokenCache` stops accepting new words forever, forcing `analyzePlainAsciiUncached` (`40.2%` of total program CPU!) on >80% of plain words.
  2. **Uncached already-diacriticized words**: `TurkishTextCodec.Token.isPlainCacheableWord()` (`line 46`) returns `false` whenever `hasPinnedTurkish == true` (any word containing `ç, ğ, ı, ö, ş, ü`). Thus, in any mixed or clean Turkish sentence where `LatticeDeasciifier` runs, *every clean diacriticized word* bypasses `plainTokenCache` and executes `buildGeneralTokenLattice` + `WordGenerator` (`22.7%–30.2%` of total CPU!).
  3. **Single foreign/brand OOV triggers full-sentence deasciification**: In `SpanCandidateGenerator.java:512-516`:
     ```java
     boolean shouldRunSentenceDeasc =
         oovAsciiWords > 0
             || explicitDiacriticWords == 0
             || (pureAsciiWords >= 2 && pureAsciiWords * 2 >= alphaWords);
     ```
     In clean formal Turkish (`synth_clean_1k`, `GECTurk Clean`), a single English brand/name (`"Google"`, `"Essential"`, `"LED"`, `"Twitter"`) sets `oovAsciiWords > 0` and triggers `deasciifyKnownWords` across all 15–25 words of an otherwise fully diacriticized sentence.
* **Recommended Fix (~15–20 lines)**:
  1. Change `MAX_PLAIN_TOKEN_CACHE` in `LatticeDeasciifier.java:74` from `1_024` to `32_768` (and clear or use segmented eviction if full).
  2. In `LatticeDeasciifier.buildSentenceLattice`, if `tok.hasPinnedTurkish()` is `true` and `tok` has **zero** ASCII-ambiguous characters (`c, g, i, o, s, u` — or passes a cached `pinnedUnambiguousCache` check), emit a single-candidate `TokenLattice` directly without invoking `informalAsciiMorphology` + `WordGenerator`.
  3. In `SpanCandidateGenerator.java:489-491`, do not count mid-sentence `TITLE`/`UPPER` foreign tokens (`w/x/q` or non-sentence-AllCaps `UPPER` acronyms) toward `oovAsciiWords` when `explicitDiacriticWords >= 2` and `pureAsciiWords * 3 < alphaWords`.

---

### `P2` — Guard Adjacent-Word Concatenation in `addMultiTokenMerges` Before Calling `realizeValidWordOrNull`
* **Impact**: **Eliminates `23.5%` of total cold-cache CPU (`~1.3x` faster cold-cache throughput)** and prevents ~45,000 garbage bigrams from polluting `realizeWordCache`.
* **Location**: `experiment/src/main/java/zemberek/normalization/v2/SpanCandidateGenerator.java` (lines `2402–2426`).
* **Root Cause**:
  ```java
  if (!isLocOrAbl && !t2Low.equals("ki") && !t2Low.equals("kü")) {
    String concatRaw = t1.lower() + t2Low;
    String realized = realizeValidWordOrNull(concatRaw); // <-- 23.5% OF ALL COLD CPU!
  ```
  For *every* adjacent word pair `(t1, t2)` in *every* sentence (`"bugün hava" -> "bugünhava"`), `realizeValidWordOrNull` runs `strictMorphology`, `exactInformalMorphology`, and `informalAsciiMorphology`, even though lines `2423–2426` immediately discard `realized` unless:
  `isClosedPrefix || isSplitAbility || isSplitCopula || (closedFreq != null && closedFreq >= 80)`.
* **Recommended Fix (~10 lines)**:
  * Precompute `Set<String> frequentClosedAscii` at `SpanCandidateGenerator` construction from `mined.cleanClosedWords` entries with `freq >= 80` (`~8,000` ASCII keys), plus static `CLOSED_COMPOUND_PREFIXES_ASCII`.
  * Evaluate `boolean canAccept = isSplitAbility || SPLIT_COPULA_TAILS.contains(t2Low) || startsWithClosedPrefixAscii(t1.ascii()) || frequentClosedAscii.contains(t1.ascii() + t2.ascii());` **before** allocating `concatRaw` or calling `realizeValidWordOrNull(concatRaw)`.
  * **Exactness**: 100% candidate-identical to current code.

---

### `P3` — Eliminate Per-Token Substring Probing & Constant `toAscii` Allocations in `addAmbiguityPreservingSplits`
* **Impact**: **Eliminates ~5%–7% warm CPU and >8% of total heap allocations** (`HashMap.getNode` #1 self-time and `TurkishAlphabet.toAscii` allocations).
* **Locations**:
  1. **`cleanOpenBigrams` substring loop (`SpanCandidateGenerator.java:1902-1928`, `1.2%–1.7%` self-CPU)**:
     * Currently slices every word of length `5..22` across all `cut = 2..len-2`, allocating `p1`, `p2`, and `p1 + " " + p2` (~27 `String`s/word) to probe `mined.cleanOpenBigrams`.
     * **Fix (~12 lines)**: Build `Map<String, OpenSplitEntry> cleanOpenByJoined` once at generator init for all `cleanOpenBigrams` entries with `bgCnt >= 10` (keyed by spaceless `w1 + w2`, keeping max-count split). Replace the `for (int cut ...)` loop with a single `cleanOpenByJoined.get(low)` lookup (`0` allocations, `O(1)`).
  2. **`QUESTION_TAILS` dynamic `toAscii` loop (`SpanCandidateGenerator.java:1735-1739`, `2.0%–2.8%` inclusive CPU + `6.2%` of all heap bytes)**:
     * Currently iterates all 44 `QUESTION_TAILS` strings on every token and calls `NormV2Types.toAscii(qt)` dynamically inside the loop. Every string in `QUESTION_TAILS` begins with `'m'` (`mı/mi/mu/mü/...`) and folds to just **11** unique ASCII suffixes (`mi, miyim, misin, miyiz, misiniz, miydi, miydim, miydin, miydik, miydiniz, miymis`).
     * **Fix (~8 lines)**: Guard the block with `if (tok.ascii().indexOf('m') >= 2)` (skips >90% of tokens in one scan) and match against a `private static final String[] QUESTION_TAILS_ASCII` array of length 11.

---

### `P4` — Recalibrate or Fast-Path `KeepGatekeeper`
* **Impact**: **Recovers `5.2%–8.2%` wasted warm CPU** (and unlocks genuine `25%–40%` clean-token skipping on cold/mixed traffic).
* **Location**: `experiment/src/main/java/zemberek/normalization/v2/TwoStageGbdtNormalizer.java` (lines `345–358`, `589–602`, `840–899`).
* **Root Causes**:
  1. Training weights positive edits by `3.2x` (`line 601`) and labels pure sentence-start capitalization changes as edits (`line 581`), while `gateSkipThreshold = 0.035f` corresponds to `< 1.1%` unweighted edit probability — so only `6.9%–10.2%` of tokens are skipped (`8.4%` even on `synth_clean_1k`).
  2. `shouldGateSkip` vetoes `cliticTail` (`de/da/te/ta/mi/ki`) and `nextMerge` (`lines 352–353`), even though `(G) addAmbiguityPreservingSplits` and `(I) addMultiTokenMerges` already execute unconditionally outside `!skipHeavy` in `buildLattice` (`lines 687, 722`).
  3. `extractGateFeatures` re-runs `hasRegularStrictAnalysis(low)` (already in `strictKnown[i]`), `charPhonotactics.scoreWord(low)`, and 4 LM string-hash lookups on every token.
* **Recommended Fix (~15 lines)**:
  * Pass `strictKnown[i]` into the gate predicate so `hasRegularStrictAnalysis` is never recomputed.
  * Add a zero-tree deterministic fast-skip before `extractGateFeatures`: if `strictKnown[i]` is `true`, `tok.shape() == LOWER` (or sentence-start `TITLE`), `!hasRepeatedLetters(low)`, `low.indexOf('\'') < 0`, `!hasAsciiAmbiguousChars(low)` (or `parEntry != null && parEntry.totalCount() >= 20 && parEntry.keepProb() >= 0.985f`), return `true` immediately in `~10 ns`.
  * Remove the redundant `G_CLITIC_TAIL_HINT` / `G_NEXT_MERGE_HINT` veto in `shouldGateSkip` (since `(G)` and `(I)` still run), and calibrate `gateSkipThreshold` on dev (`~0.08–0.10` under `3.2x` weighting, or train `Gatekeeper` only on heavy-generator edits `(C)-(F),(H)` excluding pure sentence-initial casing).

---

### `P5` — Decoder, Feature Extraction & Micro-Hotspot Cleanups
* **Combined Impact**: **~10%–14% warm CPU reduction** (`~35%–45%` faster `decodeSpanDp` / `decodeViterbi`).
* **Sub-items**:
  1. **Flatten `new float[24][6]` per-sentence allocation (`3.9%–4.5%` self-CPU)**:
     * *Where*: `TwoStageGbdtNormalizer.java:377` (`populateLatticeGbdtScores`) and `:455` (`decodeSpanDp`).
     * *Fix*: Allocating a 2D `float[24][6]` creates **25 heap objects per sentence**. Replace with a flat `float[] arcLm6 = new float[24 * 6]` (stride `6`, 1 array instead of 25) or a reusable `ThreadLocal` scratch struct.
  2. **Eliminate duplicate `lmBigram` calls & hoist beam-invariant LM features (`5.8%–10.1%` CPU)**:
     * *Where*: `SpanCandidateGenerator.computeSpanLmComparison` (`:2775–2844`), `TwoStageGbdtNormalizer.extractRankFeatures` (`:927–928`), and `SpanLatticePerceptronNormalizer.decodeViterbi` / `extractDenseFeatures` (`:690–694`, `:807–816`).
     * *Why*: Both GBDT and Perceptron compute `fwdSmooth2` (`lmBigram(prev1, firstWord)`) and `rightSmooth2` (`lmBigram(lastWord, nextLex)`) **twice per arc** — once inside `computeSpanLmComparison` and a second time immediately after it returns! Furthermore, in `Perceptron.decodeViterbi`, `rawSmooth`, `rawClean3`, `rightSmooth2`, and `computeCliticTransparencyLlr` are recomputed `beamK = 4` times per arc even when invariant to `prevBp` (or when beam states share `prev1Lex`), and `lmVocab.indexOfTurkishWord` re-hashes identical token strings repeatedly.
     * *Fix*: Store `fwdSmooth2` and `rightSmooth2` directly in `lm8Buf`, compute `rawSmooth` once per `(startIdx, endIdx, prev1Lex)`, hoist `rightSmooth2` and `cliticSkipLlr` outside the `for (int b = 0; b < incoming.size(); b++)` beam loop, and cache `int lmId` / `long cleanHash` on `SpanToken` and `SpanArc`.
  3. **Skip redundant `strictMorphology.analyze` & 80-suffix linear scan in `addProperAcronymAndNumeralCandidates` (`4.5%–7.4%` CPU)**:
     * *Where*: `SpanCandidateGenerator.java:1365` and `:1392–1398`.
     * *Fix*: Record a `boolean hasInflectedProperOrAbbr` bit inside `computeMorphSummary` (`:2633`, which already analyzes every token at `:474`), skipping line `1365` (`strictMorphology.analyze(low)` + `a.getEnding()`) on ~97% of words. Replace the 80-element `for (String suf : OOV_PROPER_INFLECT_SUFS)` `endsWith` scan with a `Set<String>` suffix check over `sufLen = 5..1`.
  4. **Precompute `SpanToken.isRandomLaugh` without `Regex` `Matcher` (`1.0%–1.3%` self-CPU + `2.4%` heap bytes)**:
     * *Where*: `NormV2Types.java:167-169`.
     * *Fix*: `tok.isRandomLaugh()` is called up to **7 times per token** in `buildLattice`, instantiating a `java.util.regex.Matcher` each time. Replace `RANDOM_LAUGH_RE` with a zero-allocation 15-line char loop computed once in `spanTokenize` and stored as `boolean isRandomLaugh` on `SpanToken`.
  5. **Avoid `"^" + lowerWord + "$"` allocation in `CharPhonotacticModel.scoreWord` (`1.5%` self-CPU)**:
     * *Where*: `ParallelCorpusMiner.java:483-495`.
     * *Fix*: Index virtual boundary chars `'^'` (`idx == 0`) and `'$'` (`idx == len + 1`) directly in `hashChars` instead of concatenating `"^" + lowerWord + "$"` on every candidate arc.

---

### `P6` — Speed Up `buildAbjad5Index` Initialization (~3.5s Faster Model Load / Server Boot)
* **Impact**: Cuts `NormV2ModelStore.loadOrBuild` / `ZemberekDemoServer` startup time from **~7.5s down to ~4.0s** (`12.5%` of total CPU in `cold_gbdt_gated.jfr`).
* **Location**: `experiment/src/main/java/zemberek/normalization/v2/SpanCandidateGenerator.java` (lines `3983–4011`).
* **Root Cause**: `buildAbjad5Index()` iterates over all ~30K+ `casingLexicon` entries and calls *three* uncached morphological checks (`isKnownStrictAbbreviation(w)`, `hasRegularStrictAnalysis(w)`, and `isStrictProperOrAbbrOnly(w)`) on every word *before* sorting by `cleanLm.unigramLogProb(w)` and taking the top `12,000`.
* **Recommended Fix (~12 lines)**: Filter by cheap `casingLexicon` + `cleanLm.unigramCount(w) >= 4` counts first, sort candidates by descending `unigramLogProb`, and run a single combined `strictMorphology.analyze(w)` check lazily until `12,000` valid words are collected (or serialize `abjad5Index` directly inside `mined_v23.bin`).

---

## 5. Summary Priority Matrix

| ID | Optimization Target | Code Touch | Primary Benefit | Expected Gain |
| :---: | :--- | :---: | :--- | :--- |
| **`P1`** | `LatticeDeasciifier` `32K` cache fix + clean-diacritic fast-path + `oovAsciiWords` trigger | ~20 LOC | Warm throughput (noisy & clean) | **~1.6x–1.9x faster overall** |
| **`P2`** | Guard `t1 + t2` closed-compound check before `realizeValidWordOrNull` | ~10 LOC | Cold-cache latency & cache hygiene | **~25%–30% faster cold 1T** |
| **`P3`** | Spaceless `cleanOpenBigrams` index + static `QUESTION_TAILS_ASCII` (`indexOf('m')` guard) | ~20 LOC | Warm CPU + `-15%` GC churn | **~6%–8% total CPU saved** |
| **`P4`** | Deterministic `strictKnown` fast-gate + calibrated `KeepGatekeeper` threshold | ~15 LOC | Eliminates Gatekeeper net slowdown | **~6%–8% warm / +15% clean** |
| **`P5`** | Flat `arcLm` buffer + deduplicated/hoisted LM calls + `MorphSummary` bit + `isRandomLaugh` field | ~45 LOC | Decoder (`GBDT` & `Perceptron`) + GC | **~10%–14% total CPU saved** |
| **`P6`** | Sort before morphology in `buildAbjad5Index` (or persist in `mined_v23.bin`) | ~12 LOC | Server / CLI startup latency | **~3.5s faster startup** |
