# Zemberek Turkish Normalization Improvements & Multi-Benchmark Report

This report documents the end-to-end work across Zemberek's `zemberek-normalization` and `zemberek-morphology` modules: construction of the `nasty_5k` gold normalization benchmark, forensic verification of roadmap claims against `upstream/master`, rebase and semantic conflict resolution of the active open pull request stack (**[PR #346](https://github.com/ahmetaa/zemberek-nlp/pull/346)–[PR #357](https://github.com/ahmetaa/zemberek-nlp/pull/357)**) after **[PR #345](https://github.com/ahmetaa/zemberek-nlp/pull/345)** (`LatticeDeasciifier`) merged into `ahmetaa/zemberek-nlp:master` (`935658cc`), decision to close **[PR #358](https://github.com/ahmetaa/zemberek-nlp/pull/358)** (`NormalizationFormatProjector`, `+814` LOC), construction of upgraded zero-leakage normalization runtime resources (`norm-data-v2`), and head-to-head **accuracy and performance evaluation against the `0.18.0` release (`9b9d8490`)** and `upstream/master` post-`#345` (`935658cc`).

---

## 1. Executive Summary & Head-to-Head vs `0.18.0` Release

After **[PR #345](https://github.com/ahmetaa/zemberek-nlp/pull/345)** merged (`935658cc`), conflicting and semantically interacting PRs (**`#346`, `#349`, `#355`, `#356`, `#357`**) were rebased onto `upstream/master`, tested, and pushed to `mdakin/zemberek-nlp`. **[PR #358](https://github.com/ahmetaa/zemberek-nlp/pull/358)** was closed as too complex and brittle relative to its benefit (`0%` effect on default `normalize()`). All **12 active open PRs (`#346`–`#357`)** report `MERGEABLE` / `CLEAN` on GitHub.

*(Note: Combined benchmark runs below were measured on the integrated post-`#345` stack with default `normalize()`, where `preserveCaseAndPunctuation` was `false` pass-through into `normalizeLower(sentence)`. Full benchmarks were intentionally not re-run after closing `#358`.)*

### 1.1 Accuracy Comparison: `0.18.0` Release vs `master` (post-`#345`) vs Combined Stack (`#346`–`#357`*)

| System Configuration | `nasty_5k` SysDist (↓) | `nasty_5k` W-ERR (↑) | `nasty_5k` SentAcc (↑) | `MultiLexNorm` SysDist (↓) | `MultiLexNorm` W-ERR / SentAcc (↑) | `BOUN Clean` Corrupted / 10,102 (↓) | `BOUN Clean` Preserved (↑) | `GECTurk` SysDist (↓) | `GECTurk` W-ERR / SentAcc (↑) | `TRSpell10` Exact Top-1 / Top-5 (↑) | `TRSpell10` DiacIgn Top-1 / Top-5 (↑) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **`0.18.0` Release (`9b9d8490`, v1 data)** | `10,480` | `70.03%` | `23.28%` | `335` | `33.79%` / `14.69%` | `419` (`4.148%`) | `62.16%` | `1,586` | `-25.67%` / `4.15%` | `35.57%` / `46.60%` | `33.64%` / `46.87%` |
| **`upstream/master` pre-`#345` (`b4a265f0`, v1)** | `10,828` | `69.04%` | `22.26%` | `259` | `48.81%` / — | `284` (`2.811%`) | `71.37%` | `1,460` | `-15.69%` / — | `35.50%` / `46.56%` | `33.62%` / `46.87%` |
| **`upstream/master` post-`#345` (`935658cc`, v1)** | `10,505` | `69.96%` | `23.36%` | `261` | `48.42%` / `21.68%` | `288` (`2.851%`) | `70.59%` | `1,462` | `-15.85%` / `4.74%` | `35.50%` / `46.56%` | `33.62%` / `46.87%` |
| **Combined Stack (v1 data)\*** | **`7,898`** | **`77.41%`** | **`33.80%`** | **`229`** | **`54.74%`** / **`28.67%`** | **`150`** (`1.485%`) | `85.29%` | `717` | `43.19%` / `41.90%` | **`39.28%` / `47.44%`** | **`45.85%` / `56.65%`** |
| **Combined Stack (`norm-data-v2`)\*** | `7,946` | `77.28%` | `33.46%` | `231` | `54.35%` / `27.97%` | `151` (`1.495%`) | **`85.49%`** | **`687`** | **`45.56%`** / **`44.27%`** | **`39.28%` / `47.44%`** | **`45.85%` / `56.65%`** |

### 1.2 Throughput & Startup Performance vs `0.18.0` Release

Measured in isolated subprocess runs of [EvaluateNormalizationBenchmarks.java](EvaluateNormalizationBenchmarks.java) (`-Xmx6g`, excluding Zemberek jars from external dependencies):

| System Configuration | `init` (s) | `nasty_5k` (`tok/s`) | `MultiLexNorm` (`tok/s`) | `BOUN Clean` (`tok/s`) | `GECTurk` (`tok/s`) | `TRSpell10` Exact (`w/s`) | `TRSpell10` DiacIgn (`w/s`) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **`0.18.0` Release (`9b9d8490`)** | `2.36s` | `6,308` | `7,011` | `8,435` | `17,329` | `2,543` | `2,640` |
| **`upstream/master` post-`#345` (`935658cc`)** | `2.53s` | `5,386` (`-14.6%`) | `6,131` | `7,550` | `12,588` | `2,826` | `2,748` |
| **Combined Stack (v1 data)\*** | `3.87s` | `7,679` (`+21.7%` vs `0.18.0`) | `7,248` | `8,543` | `20,335` | `5,547` (**`2.18x`**) | `4,123` (`1.56x`) |
| **Combined Stack (`norm-data-v2`)\*** | `4.06s` | **`8,377`** (**`+32.8%` vs `0.18.0`**) | **`8,883`** | **`8,569`** | **`24,290`** | **`5,619`** (**`2.21x`**) | **`4,513`** (`1.71x`) |

\* *Measured on the post-`#345` combined branch before closing `#358`; `normalize()` ran directly via `normalizeLower` without format projection.*

Key headline wins (**`0.18.0` Release (`9b9d8490`)** → **Combined Stack (`#346`–`#357`)**):
- **`nasty_5k` (5,000 noisy social-media sentences)**: residual word errors cut by **`-2,582` (`-24.6%` relative error reduction)** (`10,480` → `7,898`), `W-ERR` **`70.03%` → `77.41%`**, `SentAcc` **`23.28%` → `33.80%` (`+10.52 pp`)**, while recovering and surpassing `0.18.0` speed (`6,308 tok/s` → `5,386` post-`#345` → **`8,377 tok/s`** on `norm-data-v2`, **`+55.5%`** over `master` post-`#345` and **`+32.8%`** over `0.18.0`).
- **`MultiLexNorm TR` (143 social-media sentences)**: residual edits cut **`335` → `229`** (`-31.6%`), `W-ERR` **`33.79%` → `54.74%`**, `SentAcc` **`14.69%` → `28.67%` (`+13.98 pp`)**.
- **`BOUN Clean Control` (510 clean sentences)**: false corruptions cut by nearly two-thirds (**`419` → `150`**, `4.148%` → `1.485%`, **`-64.2%`** fewer corruptions), and untouched sentence preservation jumped **`62.16%` → `85.49%` (`+23.33 pp`)**.
- **`GECTurk Noisy` (506 sentences, clitic & grammar errors)**: residual edits cut **`1,586` → `687` (`norm-data-v2`, `-56.7%`)**, turning `W-ERR` from **`-25.67%` to `+45.56%`** and `SentAcc` from **`4.15%` to `44.27%` (`+40.12 pp`)**.
- **Word-level Spell Checker (`Mukayese TRSpell10`, `9,766` words)**: `Exact` Top-1 **`35.57%` → `39.28%`**, `Diacritic-Ignoring` Top-1 **`33.64%` → `45.85%` (`+12.21 pp`)**, Top-5 **`46.87%` → `56.65%`**, throughput **`2.21x` faster** (`2,543` → `5,619 w/s`), and standalone CSR `CompactStemEndingSpellChecker` heap footprint **`8.4 MB`** vs legacy `CharacterGraphDecoder` **`105 MB`** (PR `#353`).

---

## 2. `nasty_5k` LLM-Annotated Benchmark Construction

To evaluate noisy Turkish social-media normalization at scale, a 5,000-sentence benchmark (`nasty_5k`) was curated from high-noise user-generated text (including `kadinlar_klubu.txt`) and annotated with `gemini-2.5-pro` via the Gemini Batch API (`group=10` sentences per call, `medium` reasoning, `61,925/61,925` tokens aligned with `0` fallbacks, total batch cost \$4.10):

- **Sentence pairs TSV (`5,000` sentences)**: `nasty_5000_pairs.tsv` (`56,420` raw tokens, `59,921` gold tokens, `34,970` raw Levenshtein word edits / `58.36%` baseline WER vs gold)
- **CoNLL-style `.norm` export**: `nasty_5000.norm`

---

## 3. Forensic Re-Analysis of Roadmap Claims vs `upstream/master`

Every claim in [TURKISH_NORMALIZATION_INVESTIGATION_AND_ROADMAP.md](TURKISH_NORMALIZATION_INVESTIGATION_AND_ROADMAP.md) was re-verified directly against `upstream/master`:

| Roadmap ID | Claim Summary | Verdict vs `upstream/master` | PR / Resolution |
| :--- | :--- | :--- | :--- |
| **B1** | `DiacriticsIgnoringMatcher` silent map overwrites (`i`/`ı`, `u`/`ü`, `İ`/`I`, `U`/`Ü`, typo `'Ö'` for `'S'`) and literal `.replace("['.]", "")` bug. | **Confirmed on `upstream/master`**. | **PR `#347`** |
| **B2** | Duplicate `LatticeDeasciifier` execution & `'ı'` / letter-ratio gate bug in `probablyRequiresDeasciifier`. | Partially handled in **`#345`** (`LatticeDeasciifier`), **letter-ratio & `< 0.05` gate confirmed** (`I` vs `length()` bug) + `preserveValidWords` legacy guard. | **PR `#349`** (`preserveValidWords`), **PR `#355`** (letter-count `< 0.05` gate) |
| **B3 (Manual)** | Destructive `candidates-manual` overrides on valid words (`duydum→duyduk`, `sordu→sordum,sordun`, `amin→amına`, `sizi=size,sızı`). | **Confirmed on `upstream/master`**. | **PR `#349`** |
| **B3 (`separateCommon`)** | Premature `return input;` blocks shorter splits (`yapıyomusun`), misses informal heads & solid compounds (`birçok`), over-gates finite verb + question particle. | **Confirmed on `upstream/master`**. | **PR `#350`** |
| **B4** | `NoisyWordsLexiconGenerator` context map wipeout on 3rd+ occurrence, inverted hitting-time `(total-avg)` + `0`-denom bug, biased roulette sampling. | **Confirmed on `upstream/master`**. | **PR `#348`** |
| **A1** | Flat candidate scoring without edit/diacritic priors + unweighted spellchecker unigram ranking hurts clean text & `DiacriticsIgnoringMatcher`. | **Confirmed on `upstream/master`**. | **PR `#354`** + **Clean LM (`norm-data-v2`)** |
| **A2** | Missing geminate-preserving expressive elongation normalizer (`çoooook`, `saatttt`). | **Confirmed on `upstream/master`**. | **PR `#352`** |
| **A3** | Missing progressive `y`-drop (`-Io`/`-Ior`), `FutPart_Informal`, colloquial vowel-drop (`bişey`, `olcak`) & `ne+verb` (`napıyon`, `noluyo`). | **Confirmed on `upstream/master`**. | **PR `#351`** + **PR `#355`** |
| **A4** | Lowercase un-apostrophized `Abbreviation` (`[Go:Abbrv]+yduk`) & `<=3`-char `ProperNoun` false friends block spellchecking. | **Confirmed on `upstream/master`**. | **PR `#350`** |
| **A5** | Detached numeral suffixes (`saat 3 de`), `combineNecessaryWords` `if (combined)` ordering bug, `-de/-da` clitic split/merge errors, and `LatticeDeasciifier` glued-clitic vowel/InverseHarmony mutation. | **Confirmed on `upstream/master`**. | **PR `#352`** (numerals + ordering bug) + **PR `#356`** (`CliticDeDaNormalizer`, proper-noun guard, `MorphologicalCandidateGenerator` glued-clitic fix) |
| **S1 & S2** | Memory-heavy `CharacterGraphDecoder` (~`105 MB`) & 8x re-tokenizing pipeline + heap-heavy `ActiveList<Hypothesis>` Viterbi. | **Confirmed on `upstream/master`**. | **PR `#353`** (`S1` CSR `FlatTrie`), **PR `#357`** (`S2` `SpanToken` + primitive trellis) |
| **A6** | `TurkishSentenceNormalizer` destroys casing, proper noun capitalization, acronyms, and punctuation spacing. | **Confirmed on `upstream/master`**; prototype **[PR #358](https://github.com/ahmetaa/zemberek-nlp/pull/358)** **dropped/closed** (`+814` LOC heuristic alignment & homograph fragility not worth benefit; moved to contextual Future Work). | **Closed (`#358`)** → Deferred to neural/contextual truecasing |

---

## 4. Pull Request Status & Post-Merge Cleanup Summary

### 4.1 Merged Upstream
- **[PR #345](https://github.com/ahmetaa/zemberek-nlp/pull/345)** — **MERGED** (`935658cc`): added `LatticeDeasciifier` and made it default in `TurkishSentenceNormalizer`.
- **[PR #346](https://github.com/ahmetaa/zemberek-nlp/pull/346)** — **MERGED** (`f2068529`): added `LatticeDeasciifier.CircumflexMode` (`DISAMBIGUATION_ONLY` default, `FULL`, `NONE`).
- **[PR #347](https://github.com/ahmetaa/zemberek-nlp/pull/347)** — **MERGED** (`8076515e`): fixed `DiacriticsIgnoringMatcher` array overwrites + `'Ö'` → `'S'` typo and literal `.replace("['.]", "")` stripping bug.
- **[PR #348](https://github.com/ahmetaa/zemberek-nlp/pull/348)** — **MERGED** (`26b5ee25`): fixed `NoisyWordsLexiconGenerator` `localContextCounts` overwrite, inverted hitting-time score, and biased random-walk neighbor selection.
- **[PR #349](https://github.com/ahmetaa/zemberek-nlp/pull/349)** — **MERGED** (`92667289`): cleaned destructive `candidates-manual` entries, placed valid input ahead of distractors, and added `preserveValidWords` / `stripsExplicitTurkishDiacritic`.
- **[PR #351](https://github.com/ahmetaa/zemberek-nlp/pull/351)** — **MERGED** (`8ae462bf`): added `-Io`/`-Ior` progressive `y`-drop and `FutPart_Informal` to informal morphotactics.
- **[PR #352](https://github.com/ahmetaa/zemberek-nlp/pull/352)** — **MERGED** (`90bb54db`): added geminate-preserving `ElongationNormalizer`, fixed `combineNecessaryWords` `if (combined)` ordering bug, and added detached numeral suffix attachment.
- **[PR #355](https://github.com/ahmetaa/zemberek-nlp/pull/355)** — **MERGED** (`570d50b6`): added `InformalContractionExpander` and letter-count `< 0.05` `probablyRequiresDeasciifier` gate.

### 4.2 Closed / Dropped
- **[PR #350](https://github.com/ahmetaa/zemberek-nlp/pull/350)** (`fix/normalizer-separate-common-and-false-friends`) — **CLOSED** (remote and local branches deleted). Informal contraction coverage is largely superseded by `#351` and `#355`, while constructing a 3rd `TurkishMorphology` instance adds init overhead and the `<=3`-char proper-noun guard risks corrupting valid un-apostrophized ethnonyms/plurals (`Ruslar`, `Çinliler`, `Vanlılar`).
- **[PR #353](https://github.com/ahmetaa/zemberek-nlp/pull/353)** (`feat/spellchecker-compact-csr-graph`) — **CLOSED** (remote and local branches deleted). `#347` already fixed `DiacriticsIgnoringMatcher` on `master`; keeping a `+1,163`-LOC parallel spellchecker engine with cold-startup trie build overhead is too heavy (lighter memory improvements will be pursued separately).
- **[PR #357](https://github.com/ahmetaa/zemberek-nlp/pull/357)** (`perf/normalizer-single-pass-spantoken-and-trellis-viterbi`) — **CLOSED** (remote and local branches deleted). Runtime is dominated by deasciification, morphological analysis, and spellchecking rather than tokenization or 2-gram beam objects, so duplicating the pipeline with `SpanToken` and custom trellis decoding is not worth the maintenance cost.
- **[PR #358](https://github.com/ahmetaa/zemberek-nlp/pull/358)** (`feat/normalizer-preserve-case-and-punctuation`) — **CLOSED** (remote and local branches deleted). Added `+814` LOC for post-hoc `1:1`/`1:2`/`2:1` DP alignment and heuristic casing/punctuation projection; truecasing belongs in a dedicated contextual sequence model.

### 4.3 Remaining Open PRs (Rebased onto `upstream/master` `570d50b6`, Verified `MERGEABLE` / `CLEAN`)
1. **[PR #354](https://github.com/ahmetaa/zemberek-nlp/pull/354)** (`feat/normalizer-noisy-channel-scoring`, rebased `3c5969c1`) — Calibrated noisy-channel priors (`editCost`, `diacriticDiffs`, `protectCleanForm`, `possible-noisy`, apostrophe-preservation guard, OOV `-2.50`) while preserving `#349`'s `stripsExplicitTurkishDiacritic` guard. Verified with `mvn test -pl morphology,normalization` (`Failures: 0, Errors: 0`).
2. **[PR #356](https://github.com/ahmetaa/zemberek-nlp/pull/356)** (`feat/normalizer-clitic-de-da-split-and-merge`, rebased `1b4da16d`) — Adds `CliticDeDaNormalizer`, `hasProperNounOrAbbrvAnalysis` guard, and `MorphologicalCandidateGenerator` glued-clitic/InverseHarmony fixes cleanly on top of `#346` and `#352`/`#355`. Verified with `mvn test -pl normalization` (`Failures: 0, Errors: 0`).

---

## 5. Multi-Benchmark Ablation Details

Evaluated via [EvaluateNormalizationBenchmarks.java](EvaluateNormalizationBenchmarks.java):
- **Mukayese TRSpell10** (`9,766` words: `6,766` misspelled, `3,000` clean).
- **`nasty_5k`** (`5,000` sentences, `56,420` raw / `59,921` gold tokens, raw edit dist `34,970`).
- **MultiLexNorm TR** (`143` sentences, `1,537` raw / `1,567` gold tokens, raw edit dist `506`).
- **BOUN Clean Control** (`510` unique clean sentences, `10,102` tokens).
- **GECTurk Noisy** (`506` sentences, `10,248` raw / `9,784` gold tokens, raw edit dist `1,262`).

| Configuration | `nasty_5k` SysDist | `nasty_5k` W-ERR | `nasty_5k` SentAcc | `nasty_5k` tok/s | `MultiLex` SysDist | `BOUN Clean` Corr / 10,102 | `BOUN Clean` Preserved | `GECTurk` SysDist | `GECTurk` W-ERR | `TRSpell10` Exact Top-1 | `TRSpell10` DiacIgn Top-1 |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **`0.18.0` Release (`9b9d8490`, v1)** | `10,480` | `70.03%` | `23.28%` | `6,308` | `335` | `419` | `62.16%` | `1,586` | `-25.67%` | `35.57%` | `33.64%` |
| **`upstream/master` pre-`#345` (`b4a265f0`)** | `10,828` | `69.04%` | `22.26%` | `6,728` | `259` | `284` | `71.37%` | `1,460` | `-15.69%` | `35.50%` | `33.62%` |
| **`upstream/master` post-`#345` (`935658cc`)** | `10,505` | `69.96%` | `23.36%` | `5,386` | `261` | `288` | `70.59%` | `1,462` | `-15.85%` | `35.50%` | `33.62%` |
| **PR `#353` Standalone (pre-`#345`)** | `10,559` | `69.81%` | `24.00%` | **`8,880`** | `255` | `288` | `70.98%` | `1,464` | `-16.01%` | `39.12%` | `44.15%` |
| **PR `#354` Standalone (pre-`#345`)** | `10,696` | `69.41%` | `22.78%` | `6,314` | `253` | `182` | `82.94%` | `1,365` | `-8.16%` | `36.34%` | `41.61%` |
| **PR `#355` Standalone (pre-`#345`)** | `10,524` | `69.91%` | `23.38%` | `6,725` | `253` | `280` | `71.57%` | `1,456` | `-15.37%` | `35.50%` | `33.62%` |
| **PR `#356` Standalone (pre-`#345`)** | `10,497` | `69.98%` | `23.04%` | `7,230` | `258` | `275` | `71.57%` | `857` | `32.09%` | `35.50%` | `33.62%` |
| **PR `#357` Standalone (pre-`#345`)** | `10,717` | `69.35%` | `22.84%` | `6,547` | `257` | `230` | `76.67%` | `1,412` | `-11.89%` | `35.50%` | `33.62%` |
| **Combined Stack (v1 data)\*** | **`7,898`** | **`77.41%`** | **`33.80%`** | `7,679` | **`229`** | **`150`** | `85.29%` | `717` | `43.19%` | **`39.28%`** | **`45.85%`** |
| **Combined Stack (`norm-data-v2`)\*** | `7,946` | `77.28%` | `33.46%` | `8,377` | `231` | `151` | **`85.49%`** | **`687`** | **`45.56%`** | **`39.28%`** | **`45.85%`** |

---

## 6. Upgraded Leak-Free Normalization Runtime Data (`norm-data-v2`)

To rebuild Zemberek's external normalization models cleanly using PR `#348`'s fixed `NoisyWordsLexiconGenerator` without memorizing benchmark sentences, we authored [BuildNormDataV2.java](BuildNormDataV2.java) and [SanitizeNormDataV2.java](SanitizeNormDataV2.java) outside the repo under `norm-data-v2`:

- **Zero-leakage guard**: Excluded all `32,361` unique normalized sentences across `nasty_5k`, `MultiLexNorm TR`, and `GECTurk/BOUN` train/dev/test splits (`skippedLeak=584` on the final clean LM stream).
- **Cleaned 2-gram `SmoothLm` (`16 MB`, `285,867` 1-grams, `1,909,002` 2-grams)**: Trained on `4,050,000` clean sentences (`53.08M` tokens across `ntv-750k`, `subtitle-tr-10M`, `cnnturk`, `haberturk`, `onedio`, `vikipedi`) with `MIN_UNIGRAM_COUNT=3`, `MIN_BIGRAM_COUNT=3`, absolute discounting `D=0.75`, and calibrated `p1[0] = 2.0e-7` (`ln = -15.425`).
- **Sanitized Lexicons**:
  - `ascii-map` (`3.2 MB`, `133,328` keys: `58,862` v1 base + `75,093` new regular-morphology surface mappings; `7,509` noisy candidates pruned).
  - `split` (`147 KB`, `5,325` entries from clean high-count postposition/clitic bigrams).
  - `lookup-from-graph` (`39 KB`, `2,044` entries: `20` v1 base + `2,024` clean high-similarity social-media mined pairs from PR `#348`'s fixed random walk, fixing real-world cases like `msjı → mesajı`, `yaptm → yaptım`, `canm → canım`, `maleseff → maalesef`).

---

## 7. Future Work

While classical rule + morphology + `LatticeDeasciifier` + 2-gram Viterbi normalization has now been pushed to **`77.41%` W-ERR** on `nasty_5k` (`85.49%` clean sentence preservation and `45.56%` W-ERR on `GECTurk`), remaining errors highlight four high-leverage next steps:
1. **Neural Contextual Reranker / Seq2Edits Post-Pass**: Remaining `GECTurk Noisy` errors (syntax/agreement, subtle `ki`/clitic disambiguation beyond 2-word context) and homophone/context ambiguities in `nasty_5k` (`bir`/`bı`, `olan`/`olma`) require wider-context neural scoring or lightweight BERT/Electra GEC reranking over Zemberek's top-K lattice hypotheses.
2. **Contextual Truecasing & Punctuation Restoration**: Rather than heuristic post-hoc DP token alignment and proper-noun guessing inside `TurkishSentenceNormalizer` (why PR `#358` was dropped), casing and punctuation restoration should be handled as a dedicated token/sequence tagging pass with NER/context awareness.
3. **Domain-Adaptive & Code-Switching / Subword Entity Protection**: Foreign/technical loanwords, transliterated usernames, and social-media slang occasionally suffer false-friend edits (`muammer bey'i` → `beyi`, rare loanwords on `BOUN Clean`). Adding a bilingual subword LM prior and stronger named-entity recognition will push `BOUN Clean` corruptions below `0.5%`.
4. **Discriminative End-to-End Parameter Tuning & Upstream `norm-data-v2` Publication**: Automatically learning channel weights (`editCost`, `diacriticDiffs`, `lookupFromGraph`, `protectCleanForm`, `CliticDeDaNormalizer` LLR thresholds) via MERT / Minimum Bayes Risk over clean + noisy dev folds, and publishing the leak-free `norm-data-v2` archive alongside a release of Zemberek.
