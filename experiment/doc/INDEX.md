# Zemberek-NLP Research & Engineering Documentation Index (`experiment/doc`)

Central index of recent technical reports, architectural audits, error catalogs, benchmark specifications, and design blueprints created during November 2026 (`2026-11-08` – `2026-11-15`).

---

## Quick Lookup ("Which Document Should I Read?")

| If you want to understand... | Read this first |
| :--- | :--- |
| **Data-driven `v2` Turkish normalizers (`75K` multi-regime corpus, accuracy & single-thread speed benchmarks)** | [normalization/DATA_DRIVEN_TURKISH_NORMALIZATION_V2.md](normalization/DATA_DRIVEN_TURKISH_NORMALIZATION_V2.md) |
| **Mathematical & algorithmic deep dive into `SpanLatticePerceptron`, `TwoStageGBDT`, and `4-Fold OOF Hybrid`** | [normalization/TURKISH_NORMALIZATION_V2_METHODS_DEEP_DIVE.md](normalization/TURKISH_NORMALIZATION_V2_METHODS_DEEP_DIVE.md) |
| **JDK 25 JFR CPU/allocation profiling of `v2.3` normalizers & quick-win speedup catalog (`P1`–`P6`)** | [normalization/NORMALIZATION_V2_PERFORMANCE_PROFILING_REPORT.md](normalization/NORMALIZATION_V2_PERFORMANCE_PROFILING_REPORT.md) |
| **100% error catalog & Lattice-vs-Ranker severity analysis (`S1`–`S4`) of `SpanLatticePerceptronNormalizer` on `test_slice_01`** | [normalization/SPAN_LATTICE_PERCEPTRON_ERROR_CATALOG_REPORT.md](normalization/SPAN_LATTICE_PERCEPTRON_ERROR_CATALOG_REPORT.md) |
| **Unicode emoji support across `tokenization`, `morphology`, and `normalization` (ANTLR `4.7.2` rules, UTF-16 offsets & guards)** | [tokenization/EMOJI_SUPPORT_IN_ZEMBEREK.md](tokenization/EMOJI_SUPPORT_IN_ZEMBEREK.md) |
| **Raw/plain noisy corpora profile, profanity strategy & 20K–50K LLM-cleaned dataset blueprint** | [normalization/NOISY_CORPUS_MINING_AND_TRAINING_DATA_REPORT.md](normalization/NOISY_CORPUS_MINING_AND_TRAINING_DATA_REPORT.md) |
| **Current normalization errors, semantic severity (`S1`–`S4`), and PRs `#370`–`#376` benchmark results** | [normalization/NASTY_5K_ERROR_CATALOG_REPORT.md](normalization/NASTY_5K_ERROR_CATALOG_REPORT.md) |
| **Simplifying & refactoring `normalization` (God-class split, `core`/`morphology` reuse, Java 21 idioms)** | [normalization/NORMALIZATION_SIMPLIFICATION_AUDIT.md](normalization/NORMALIZATION_SIMPLIFICATION_AUDIT.md) |
| **End-to-end normalization benchmark progression (`0.18.0` -> `master` -> PRs `#345`–`#367`)** | [normalization/ZEMBEREK_NORMALIZATION_IMPROVEMENTS_REPORT.md](normalization/ZEMBEREK_NORMALIZATION_IMPROVEMENTS_REPORT.md) |
| **Code audit of merged/closed normalization & memory PRs (`#345`–`#365`)** | [normalization/RECENT_PRS_AUDIT_345_TO_364.md](normalization/RECENT_PRS_AUDIT_345_TO_364.md) |
| **How `TurkishSentenceNormalizer` & `TurkishSpellChecker` work internally + original roadmap** | [normalization/TURKISH_NORMALIZATION_INVESTIGATION_AND_ROADMAP.md](normalization/TURKISH_NORMALIZATION_INVESTIGATION_AND_ROADMAP.md) |
| **First-principles redesign of Turkish normalization beyond greedy cascades & 2-gram LMs** | [normalization/TURKISH_NORMALIZATION_FROM_SCRATCH_REPORT.md](normalization/TURKISH_NORMALIZATION_FROM_SCRATCH_REPORT.md) |
| **Where noisy corpora, external lexicons, and `nasty_5k` benchmark files live on disk** | [normalization/TURKISH_NORMALIZATION_DATA_README.md](normalization/TURKISH_NORMALIZATION_DATA_README.md) & [normalization/NASTY_5K_BENCHMARK_README.md](normalization/NASTY_5K_BENCHMARK_README.md) |
| **How `LatticeDeasciifier` (PR `#345`) & `CircumflexMode` (PR `#346`) are designed and trained** | [deasciifier/LATTICE_DEASCIIFIER_ARCHITECTURE_AND_TRAINING.md](deasciifier/LATTICE_DEASCIIFIER_ARCHITECTURE_AND_TRAINING.md) & [deasciifier/TURKISH_CIRCUMFLEX_HANDLING_AND_LEXICON_NOTES.md](deasciifier/TURKISH_CIRCUMFLEX_HANDLING_AND_LEXICON_NOTES.md) |
| **Turkish morphotactics edge cases (`96` wiki questions audited against `0.18.0`)** | [morphology/MORPHOLOGY_NOTES_ANSWERS.md](morphology/MORPHOLOGY_NOTES_ANSWERS.md) |
| **Open Zemberek GitHub issues triage (already-fixed, valid bugs, feature requests)** | [morphology/GITHUB_ISSUES_REPORT.md](morphology/GITHUB_ISSUES_REPORT.md) |
| **Disambiguator heap/speed profiling (`FastPerceptronAmbiguityResolver`, PR `#359`)** | [disambiguation/DISAMBIGUATOR_MEMORY_AND_PERFORMANCE_FINDINGS.md](disambiguation/DISAMBIGUATOR_MEMORY_AND_PERFORMANCE_FINDINGS.md) |
| **Data-centric morphological disambiguation pipeline (corpus refinement, `ZAS-v3` taxonomy, `SCSS-v1` selection, `GSLA-v2.1` LLM labeling)** | [disambiguation/TURKISH_DISAMBIGUATION_WHITEPAPER.md](disambiguation/TURKISH_DISAMBIGUATION_WHITEPAPER.md) |
| **Zemberek lexicon audit, PR `#386` review, 20-file resource map, and corpus-driven modernization roadmap** | [lexicon/ZEMBEREK_LEXICON_MODERNIZATION_REPORT.md](lexicon/ZEMBEREK_LEXICON_MODERNIZATION_REPORT.md) & [lexicon/PR386_DICTIONARY_AUDIT_REVIEW.md](lexicon/PR386_DICTIONARY_AUDIT_REVIEW.md) |

---

## Directory Layout

```text
experiment/doc/
├── INDEX.md                  # This master index
├── core/                     # Core collections & memory structure report (1 doc)
├── lexicon/                  # Dictionary integrity audits, PR #386 review & lexicon modernization roadmap (3 docs)
├── tokenization/             # Unicode emoji tokenization, UTF-16 offset invariants & normalizer guards (1 doc)
├── normalization/            # Data-driven v2 normalizers, simplification, scaling & error catalogs (19 docs)
├── deasciifier/              # LatticeDeasciifier architecture, circumflex handling & legacy audit (3 docs)
├── morphology/               # Morphotactics Q&A guide (Q01-Q96) & GitHub issues triage (3 docs)
└── disambiguation/           # Morphological disambiguation whitepaper, profiling, UD adjudication & selection (17 docs)
```

---

## Lexicon (`lexicon/`)

| Document | Date | Summary |
| :--- | :---: | :--- |
| [ZEMBEREK_LEXICON_MODERNIZATION_REPORT.md](lexicon/ZEMBEREK_LEXICON_MODERNIZATION_REPORT.md) | `2026-11-22` | Complete 20-file resource audit of `morphology/src/main/resources/tr/` (runtime vs offline `.dict` vs dead code), self-parse test across `68,035` items, OOV & foreign `Pr:` harmony audit over `270K` sentences, `person-names.dict` 2-tier policy, and 4-phase modernization roadmap. |
| [PR386_DICTIONARY_AUDIT_REVIEW.md](lexicon/PR386_DICTIONARY_AUDIT_REVIEW.md) | `2026-11-22` | Code, serializer/loader, and `test_slice_02_v4` normalization review of `ahmetaa/zemberek-nlp#386`, documenting 5 must-fix bugs (`bol` homograph, `DictionarySerializer` locale asymmetry, `-og` voicing leak, `semptomatik`, and 33 closed open-compounds). |
| [DICTIONARY_AUDIT_REPORT.md](lexicon/DICTIONARY_AUDIT_REPORT.md) | `2026-11-22` | Initial Track 1 & Track 2 audit plan for `master-dictionary.dict`, `non-tdk.dict`, and `abbreviations.dict`. |

---

## Tokenization (`tokenization/`)

| Document | Date | Summary |
| :--- | :---: | :--- |
| [EMOJI_SUPPORT_IN_ZEMBEREK.md](tokenization/EMOJI_SUPPORT_IN_ZEMBEREK.md) | `2026-11-14` | Design and implementation reference for minimal Unicode `Emoji` support across `tokenization`, `morphology`, and `normalization`: ANTLR `4.7.2` Unicode property behavior, glued word+emoji splitting, UTF-16 `start`/`end` offset invariant, and normalizer spellcheck guards. |

---

## 1. Normalization & Spell-Checking (`normalization/`)

| Document | Date | Summary |
| :--- | :---: | :--- |
| [DATA_DRIVEN_TURKISH_NORMALIZATION_V2.md](normalization/DATA_DRIVEN_TURKISH_NORMALIZATION_V2.md) | `2026-11-15` | Data-driven `v2` Turkish normalization architecture, `80,000`-sentence Gemini Batch parallel dataset (`50K` Heavy + `15K` Medium + `10K` Low + `5K` `test_slice_01`), accuracy across 8 benchmarks (`79.63%` Heavy `W-ERR`, `89.36%` `nasty_5k` `W-ERR`, plus 3 controlled `1K` synthetic benchmarks), ablations, scaling, and single-thread latency/throughput (`13.6K–16.3K Tok/s`). |
| [TURKISH_NORMALIZATION_V2_METHODS_DEEP_DIVE.md](normalization/TURKISH_NORMALIZATION_V2_METHODS_DEEP_DIVE.md) | `2026-11-15` | Mathematical and algorithmic deep dive into the shared 10-mechanism Multi-Span Candidate DAG (incl. compositional shorthand+suffix & `>=5`-consonant Abjad index), `SpanLatticePerceptronNormalizer` (`PA-I` margin Beam Viterbi + `863K` `MPHF` features), `TwoStageGbdtNormalizer` (`440` symmetric Newton trees + exact `MEMM` span `DP`, `115.1 KB`), and `4-Fold OOF Hybrid`. |
| [NORMALIZATION_V2_PERFORMANCE_PROFILING_REPORT.md](normalization/NORMALIZATION_V2_PERFORMANCE_PROFILING_REPORT.md) | `2026-11-15` | Single-threaded JDK 25 JFR CPU, allocation, and stage-level profiling across noisy/ASCII/clean workloads (cold & warm), documenting exact `File:line` hotspots and 6 prioritized small-change / big-gain speedup opportunities (`P1`–`P6`). |
| [SPAN_LATTICE_PERCEPTRON_ERROR_CATALOG_REPORT.md](normalization/SPAN_LATTICE_PERCEPTRON_ERROR_CATALOG_REPORT.md) | `2026-11-16` | Complete `100%`-population error catalog (`2,156 / 2,156` discrepant sentences, `3,101` diff spans across `5,000` `test_slice_01` sentences) for `SpanLatticePerceptronNormalizer` (`v2.3`): 11-category / 34-sub-tag taxonomy, 4-tier semantic severity (`S1`–`S4`), `SentenceLattice` Oracle-vs-Ranker decomposition, cross-model comparison, and `v2.4` fix roadmap. |
| [NOISY_CORPUS_MINING_AND_TRAINING_DATA_REPORT.md](normalization/NOISY_CORPUS_MINING_AND_TRAINING_DATA_REPORT.md) | `2026-11-14` | Empirical audit of `~6.2 GB` raw/plain noisy corpora (`trendyol_2025`, `winvoker`, `offenseval2020`, `yankihue`, fixed `kizlarsoruyor` symlink), external lexicons (`eminesener_boun`, `asimokby`), live Unicode emoji bugs (`Token.Type.Emoji`), profanity lexicon marking & GitHub-safe obfuscation (`RootAttribute.Vulgar` + `vulgar.obf`), and `45K` multi-task dataset blueprint. |
| [NASTY_5K_ERROR_CATALOG_REPORT.md](normalization/NASTY_5K_ERROR_CATALOG_REPORT.md) | `2026-11-14` | Manual batch-by-batch error catalog of `3,463` spans across `nasty_5k` on `HEAD` (`22f2c984`), 4-tier semantic severity re-classification (`S1` Critical .. `S4` Benign), pipeline root-cause map, minimal-change fix plan (`A1`–`A6`, `B1`–`B2`), and combined evaluation of PRs `#371`–`#376` (`81.21%` W-ERR, `39.36%` SentAcc, `75.84%` exact span precision, `98.61%` BOUN clean precision). |
| [NORMALIZATION_SIMPLIFICATION_AUDIT.md](normalization/NORMALIZATION_SIMPLIFICATION_AUDIT.md) | `2026-11-14` | Module-wide readability & architecture audit (`~1,200` LOC removable): decomposing `TurkishSentenceNormalizer`, deduplicating `SingleWordSpellChecker` vs `CharacterGraphDecoder`, reusing `zemberek-core`, promoting linguistic predicates to `core`/`morphology`/`tokenization`/`lm`, and Java 21 modernizations. |
| [NASTY_5K_ERROR_TAXONOMY_SPEC.md](normalization/NASTY_5K_ERROR_TAXONOMY_SPEC.md) | `2026-11-14` | Formal specification of the 10 top-level error categories and 26 fine-grained sub-tags used to annotate `nasty_5k_error_catalog.tsv`. |
| [RECENT_PRS_AUDIT_345_TO_364.md](normalization/RECENT_PRS_AUDIT_345_TO_364.md) | `2026-11-13` | Forensic code quality and performance audit of PRs `#345`–`#364`: bug fixes in `TurkishSpellChecker` (`#354`), `ElongationNormalizer` Roman-numeral regex & `InformalContractionExpander` prefix guards (`#365`–`#367`), memory savings (`#359`–`#363`), and rationale for closing redundant PRs (`#350`, `#353`, `#357`, `#358`, `#362`, `#364`). |
| [TURKISH_NORMALIZATION_FROM_SCRATCH_REPORT.md](normalization/TURKISH_NORMALIZATION_FROM_SCRATCH_REPORT.md) | `2026-11-13` | First-principles architectural analysis of why greedy `preProcess()` cascades, lowercase orthographic destruction, closed root lexicons, and 2-gram LMs hit structural ceilings, with a blueprint for next-generation Turkish normalization. |
| [ZEMBEREK_NORMALIZATION_IMPROVEMENTS_REPORT.md](normalization/ZEMBEREK_NORMALIZATION_IMPROVEMENTS_REPORT.md) | `2026-11-13` | Multi-benchmark comparison (`0.18.0` release vs `upstream/master` vs PR stack `#346`–`#357` and `norm-data-v2`) across `nasty_5k`, `MultiLexNorm TR`, `BOUN Clean`, `GECTurk`, and `Mukayese TRSpell10`, including throughput and heap metrics. |
| [NASTY_5K_BENCHMARK_README.md](normalization/NASTY_5K_BENCHMARK_README.md) | `2026-11-13` | Dataset documentation for `nasty_5k` (`~/data/turkish/normalization/custom_benchmark/nasty_5k`): source distribution, recoverable-noise filtering gates, file formats, and LLM gold annotation protocol. |
| [TURKISH_NORMALIZATION_DATA_README.md](normalization/TURKISH_NORMALIZATION_DATA_README.md) | `2026-11-15` | Master guide to `~/data/turkish/normalization/`: strict anti-leakage policy, public benchmarks (`MultiLexNorm`, `Mukayese TRSpell10`, `GECTurk`), 3 controlled `1K` synthetic benchmarks (`synthetic_1k`), noisy corpora, and external lexicons. |
| [TURKISH_NOISY_TEXT_CORPUS_AND_BENCHMARK_ROADMAP.md](normalization/TURKISH_NOISY_TEXT_CORPUS_AND_BENCHMARK_ROADMAP.md) | `2026-11-12` | Verified inventory of Turkish noisy-text datasets and roadmap separating large unannotated noisy corpora (for mining/LM) from gold-annotated evaluation benchmarks. |
| [TURKISH_NORMALIZATION_INVESTIGATION_AND_ROADMAP.md](normalization/TURKISH_NORMALIZATION_INVESTIGATION_AND_ROADMAP.md) | `2026-11-12` | Deep dive into `zemberek-normalization` internals, academic literature review, confirmed baseline bugs (`B1`–`B4`), and phased engineering roadmap (`A1`–`A6`, `S1`–`S2`). |

---

## 2. Deasciification & Circumflex Handling (`deasciifier/`)

| Document | Date | Summary |
| :--- | :---: | :--- |
| [LATTICE_DEASCIIFIER_ARCHITECTURE_AND_TRAINING.md](deasciifier/LATTICE_DEASCIIFIER_ARCHITECTURE_AND_TRAINING.md) | `2026-11-12` | Production Java 21 architecture of `LatticeDeasciifier` (PR `#345`): morphology-guided candidate lattice, averaged-perceptron feature templates, casing/proper-noun preservation, `TurkishSentenceNormalizer` integration, and 14-suite benchmark results. |
| [TURKISH_CIRCUMFLEX_HANDLING_AND_LEXICON_NOTES.md](deasciifier/TURKISH_CIRCUMFLEX_HANDLING_AND_LEXICON_NOTES.md) | `2026-11-12` | TDK circumflex (`â`, `î`, `û`) rules (minimal-pair disambiguation vs palatalization vs vowel length), Zemberek lexicon audit, and design of `CircumflexMode` (`DISAMBIGUATION_ONLY` default, `FULL`, `NONE` in PR `#346`). |
| [TURKISH_DEASCIIFIER_INVESTIGATION_AND_DESIGN.md](deasciifier/TURKISH_DEASCIIFIER_INVESTIGATION_AND_DESIGN.md) | `2026-11-11` | Empirical audit of Zemberek's legacy decision-list `Deasciifier` on `2,500` clean sentences (`31,334` words), taxonomy of the 4 deasciification ambiguity regimes, and algorithm design leading to `LatticeDeasciifier`. |

---

## 3. Morphology & Issue Audits (`morphology/`)

| Document | Date | Summary |
| :--- | :---: | :--- |
| [MORPHOLOGY_NOTES_ANSWERS.md](morphology/MORPHOLOGY_NOTES_ANSWERS.md) | `2026-11-10` | Comprehensive linguistic commentary and live `0.18.0` code audit answering all `96` items (`Q01`–`Q96`) from Zemberek's historical morphology design notes (compounds, copula, pronouns, derivation, informal suffixes, phonetics). |
| [Morphology-Notes.md](morphology/Morphology-Notes.md) | `2026-11-10` | Original Zemberek morphology notes (`Q01`–`Q96` source prompts paired with `MORPHOLOGY_NOTES_ANSWERS.md`). |
| [GITHUB_ISSUES_REPORT.md](morphology/GITHUB_ISSUES_REPORT.md) | `2026-11-10` | Full triage of open `ahmetaa/zemberek-nlp` GitHub issues, classifying obsolete/already-fixed issues vs reproducible morphology, tokenization, lexicon, and normalization bugs. |

---

## 4. Morphological Disambiguation & Corpus Curation (`disambiguation/`)

| Document | Date | Summary |
| :--- | :---: | :--- |
| [DISAMBIGUATOR_MEMORY_AND_PERFORMANCE_FINDINGS.md](disambiguation/DISAMBIGUATOR_MEMORY_AND_PERFORMANCE_FINDINGS.md) | `2026-11-10` | Exact heap breakdown (`408 MB` retained -> lazy/bounded fixes in PRs `#359` & `#363`) and CPU hot-path profiling of `FastPerceptronAmbiguityResolver` and `AnalysisCache`. |
| [TURKISH_DISAMBIGUATION_WHITEPAPER.md](disambiguation/TURKISH_DISAMBIGUATION_WHITEPAPER.md) | `2026-11-08` | End-to-end whitepaper synthesizing the 4-stage data-centric disambiguation pipeline: corpus refinement (`17.7 GB -> 9.9 GB`), `ZAS-v3` ambiguity taxonomy, `SCSS-v1` sentence selection, and `GSLA-v2.1` LLM annotation. |
| [GOLDEN_SET_LLM_ANNOTATION_STRATEGY.md](disambiguation/GOLDEN_SET_LLM_ANNOTATION_STRATEGY.md) | `2026-11-08` | `GSLA-v2.1` LLM annotation protocol and `ZCS-v2.0` candidate JSONL schema (`SliceCandidateGenerator.java`) for high-agreement golden disambiguation labeling. |
| [SENTENCE_SELECTION_STRATEGY.md](disambiguation/SENTENCE_SELECTION_STRATEGY.md) | `2026-11-08` | Design of `SCSS-v1` stratified submodular sentence selection (`7–15` word sweet spot, rare-ambiguity boosting, and feature-context diversity). |
| [sentence_selection_report.md](disambiguation/sentence_selection_report.md) | `2026-11-08` | Empirical profile of the selected held-out test pool (`10 x 1,000` sentences) and prefix learning-curve coverage vs uniform random sampling. |
| [TURKISH_AMBIGUITY_TAXONOMY_PROPOSAL.md](disambiguation/TURKISH_AMBIGUITY_TAXONOMY_PROPOSAL.md) | `2026-11-08` | Proposal for the `ZAS-v3` multi-tier Turkish morphological ambiguity taxonomy, overcoming limitations of the legacy 2012 ambiguity group files. |
| [turkish_ambiguity_groups_v3_report.md](disambiguation/turkish_ambiguity_groups_v3_report.md) | `2026-11-08` | Full corpus-wide `ZAS-v3` ambiguity profile across the 7 macro-mechanisms, `FastPerceptronAmbiguityResolver` feature observability, and neighbor anchor certainty. |
| [CORPUS_ANALYSIS_OVERVIEW.md](disambiguation/CORPUS_ANALYSIS_OVERVIEW.md) | `2026-11-08` | High-level overview of the `1.28B`-word refined clean Turkish corpus (`~/data/turkish/clean`) across 30 source files. |
| [corpus_refinement_report.md](disambiguation/corpus_refinement_report.md) | `2026-11-08` | Detailed filtering, deduplication, throughput (`17.91 min` on 16 threads), and morphological parseability statistics from `RefineCorpus.java`. |

---

## 5. Core & Earlier Disambiguation / Adjudication Reports

Relocated cleanly into `experiment/doc/` during the November 2026 workspace cleanup:
- **Core collections audit**: [core/COLLECTIONS_REPORT.md](core/COLLECTIONS_REPORT.md)
- **Disambiguation benchmarks, compression & UD adjudication reports**: `disambiguation/{FINAL_DISAMBIGUATION_BENCHMARK_REPORT,MODEL_COMPRESSION_REPORT,ACTIVE_LEARNING_DISTILLATION_REPORT,FAST_PERCEPTRON_VITERBI_PROFILING_REPORT,UD_BENCHMARK_ADJUDICATION_REPORT,ADJUDICATION_OLD_VS_NEW_REPORT,TEST_PLAN_COMPREHENSIVE_ADJUDICATION,README_REPLICATION_PACKAGE}.md` and `experiment/scripts/adjudicate/docs/*.md`.
