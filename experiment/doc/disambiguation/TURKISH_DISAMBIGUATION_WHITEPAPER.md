# Whitepaper: Data-Centric Turkish Morphological Disambiguation in Zemberek-NLP

**Date:** November 8, 2026  
**Repository:** `zemberek-nlp` (`experiment/docs/model`)  

---

## Executive Summary

In Turkish, **nearly two out of every three words (`64.2%`) are morphologically ambiguous**—a single surface word can simultaneously represent multiple parts of speech, case markings, possessive endings, or derivational paths. Resolving this ambiguity accurately and at high speed (`>100,000` words/sec on CPU) is foundational for Turkish NLP.

This whitepaper summarizes our end-to-end effort to modernize Zemberek's `FastPerceptronAmbiguityResolver` through **data-centric engineering**:
1. **Refining a 17.7 GB raw Turkish corpus** into `127.6 million` 100%-parseable sentences (`1.28 billion` words).
2. **Mapping the complete taxonomy of Turkish ambiguity** across `820 million` ambiguous token occurrences.
3. **Selecting diversity-optimized 1,000-sentence slices** (`7–15` words/sentence) with strict train/test firewalling.
4. **Designing a convention-aligned LLM annotation protocol (`GSLA-v2.4`)** that reached **`99.0%` cross-model agreement** between `gemini-3.8-flash` and `gemini-4-argon`.
5. **Validating against an independent `Argon HIGH` Convention-Only test set** to rule out prompt bias.

**Headline Result:** Trained on just **`10,000` curated sentences (`10` slices)**, our new [`model_10k_argon_v24.bin`](file:///home/dndara/data/turkish/annotated_slices/argon_medium_v24/models/model_10k_argon_v24.bin) (**`2.16 MB`**) outperforms the legacy `33,000`-sentence [`model-distilled-33k-th20k.bin`](file:///home/dndara/work/zemberek-nlp/morphology/src/main/resources/tr/ambiguity/model-distilled-33k-th20k.bin) (**`6.10 MB`**) across **every metric on both test sets**—achieving higher accuracy with **3.3x fewer training sentences** and **65% smaller model size**.

---

## 1. Stage 1: Corpus Refinement (`17.7 GB` → `9.9 GB`)

We began with `30` raw Turkish text corpora totaling **`17.68 GB`** (`312.3 million` sentences, `2.75 billion` tokens) across four major domains:
- **News & Journalism (`38.4%`)**: `radikal`, `iha`, `star`, `dunya`, `cnn-turk`, `haberturk`, `ntv`, etc.
- **Subtitles & Spoken Dialogue (`30.9%`)**: `open-subtitles-tr`.
- **Web Forums & Community Q&A (`18.6%`)**: `wowturkey`, `kizlarsoruyor`, `hukuki.net`, `memurlar.net`, `shiftdelete`.
- **Parliamentary, Legal & Encyclopedic (`11.7%`)**: `tbmm-tutanak`, `yargitay-karar`, `vikipedi`.

Using [`RefineCorpus.java`](file:///home/dndara/work/zemberek-nlp/experiment/src/main/java/zemberek/morphology/ambiguity/model/RefineCorpus.java) (`16` threads, `2.1M` words/sec), we filtered out telegraphic fragments (`< 5` words), run-on lines (`> 30` words), and any sentence containing even a single unparseable token (typos, ASCII-fied Turkish without diacritics, or OCR noise).

| Metric | Raw Clean Corpus | Refined Corpus (`100%` Parseable) |
| :--- | ---: | ---: |
| **Total Disk Size** | `17.68 GB` | **`9.87 GB`** |
| **Total Sentences** | `312,342,828` | **`127,645,345` (`40.9%` kept)** |
| **Total Words (Non-Punctuation)** | `2,285,357,272` | **`1,277,763,410`** |
| **Zemberek Parse Coverage** | `97.66%` | **`100.00%`** |
| **Ambiguous Words (`>= 2` parses)** | — | **`819,786,514` (`64.16%`)** |
| **Sentences with `>= 1` Ambiguous Word** | — | **`127,396,732` (`99.81%`)** |

---

## 2. Stage 2: Empirical Taxonomy of Turkish Ambiguity (`ZAS-v3`)

Before sampling sentences, we analyzed all `819.8 million` ambiguous words in the refined corpus to understand *what* makes Turkish words ambiguous and *how* `FastPerceptronAmbiguityResolver` sees them.

We grouped every ambiguous word into `42,055` canonical morphological signatures and `9` high-level **Macro-Mechanisms**:
1. **`M1` — Inflectional Syncretism**: Same stem and POS, different inflectional suffixes (e.g., `-I` as Accusative `+Acc` vs. 3rd-person possessive `+P3sg`, or `-lArI` 4-way plural/possessive syncretism).
2. **`M2` — Cross-POS Conversion**: Same root, different part of speech (`Noun` vs. `Adj` vs. `Adv`, e.g., *güzel*, *doğru*, *kadın*).
3. **`M3` — Productive vs. Lexicalized Derivation**: Competing derivational chains (`|Zero→Verb`, participles `-dIğI`/`-An` vs. finite tenses, or lexicalized verbs like `[belirtmek:Verb]` vs. causative `[belirmek:Verb|t:Caus]`).
4. **`M4` — Stem Boundary & Homonymy**: Different root lemmas producing the exact same surface word (e.g., `bu:Det` vs. `bu:Pron`, `yaş:Noun` vs. `yaş:Adj`, `almak:Verb` vs. `al:Adj`).
5. **`M5` — Proper Noun vs. Common Word**: Capitalized or mid-sentence proper names colliding with common nouns/adjectives (e.g., *Güneşli*, *Kaya*, *Ordu*).
6. **`M6`–`M7` — Multi-Locus & Hybrid Collisions**: Words combining stem homonymy with suffix syncretism simultaneously.

Crucially, we also audited `FastPerceptronAmbiguityResolver`'s feature extractor and discovered that **some candidate pairs share identical inflectional group tags (`rIg` / `lastGroup`)** and differ *only* in their root lemma (`LEXICAL_ONLY_OBSERVABLE`), while ~0.98% of ambiguous tokens had **duplicate `zemberek_key` strings** caused by homonymous dictionary entries (`Index:1`/`Index:2` in `master-dictionary.dict`).

---

## 3. Stage 3: Smart Sentence Selection (`SCSS-v1`)

Randomly sampling sentences from a natural corpus fails for two reasons:
- **Zipf Starvation**: A handful of closed-class words (`bir`, `bu`, `de`, `için`, `ile`) account for over a third of all ambiguous tokens, starving thousands of rarer morphological patterns at `1K–10K` scale.
- **Length Noise**: Very long sentences (`20–30` words) dilute LLM attention across nested clauses while providing zero extra architectural benefit to `FastPerceptronAmbiguityResolver`, whose feature window only inspects a **4-token local neighborhood (`[w_{t-2}, w_{t-1}, w_t, w_{t+1}]`)**.

We designed **Stratified Curriculum Submodular Slicing (`SCSS-v1`)** in [`SelectDisambiguationDataset.java`](file:///home/dndara/work/zemberek-nlp/experiment/src/main/java/zemberek/morphology/ambiguity/model/SelectDisambiguationDataset.java):
1. **`7–15` Word Sweet Spot**: Every selected sentence has `7–15` words (`35%` compact `7–9` words, `40%` standard `10–12` words, `25%` extended `13–15` words), clean Turkish orthography, no brackets/emojis, and at least one unambiguous (`K=1`) anchor word.
2. **`10,000`-Sentence Held-Out Test Pool (`10 slices x 1,000`)**: Each test slice combines `700` natural-distribution sentences across all 29 domains and `300` diagnostic challenge sentences covering all 9 ambiguity mechanisms.
3. **Strict 4-Gram Shingle Firewall**: Any training candidate sharing $\ge 2$ four-word shingles (or $\ge 50\%$ overlap) with *any* test sentence is permanently blocked.
4. **`100,000`-Sentence Training Pool (`100 slices x 1,000`)**: Each slice is balanced on its own while using a submodular novelty objective to prioritize unseen `(Signature, Lemma, ContextFrame)` combinations and cap closed-class repetition (`bir`, `bu`, `de`).

---

## 4. Stage 4: Convention-Aligned LLM Annotation (`GSLA-v2.4`)

To label our slices using frontier LLMs (`gemini-4-argon` and `gemini-3.8-flash`), we built [`SliceCandidateGenerator.java`](file:///home/dndara/work/zemberek-nlp/experiment/src/main/java/zemberek/morphology/ambiguity/model/SliceCandidateGenerator.java) (which exports all `110,000` sentences and `790,255` ambiguous tokens to `ZCS-v2.0` JSONL in `8.4 seconds`) and [`annotate_slices_batch.py`](file:///home/dndara/work/zemberek-nlp/experiment/scripts/model/annotate_slices_batch.py).

### What We Learned About Prompt Engineering for Morphological Disambiguation
In early experiments, we noticed that **over-prescribing textbook grammar heuristics** (such as *"Before a noun, pick `:Adj`"* or *"Before a verb, pick `:Adv`"*) actually **hurt LLM accuracy**:
- In Turkish bare noun compounds (*belirtisiz isim tamlaması* like *"otel yatırımları"*, *"cazibe merkezi"*, *"kadın hakları"*), the modifier stands immediately before a noun+`P3sg`, yet grammatically it is a **`:Noun`**, not an `:Adj`.
- Frontier reasoning models already understand Turkish syntax natively. What they *cannot* know without instruction are **Zemberek's internal representation conventions and tie-breaks**.

We iteratively refined the system instruction (`GSLA-v2.1` → `GSLA-v2.4`) by:
1. **Deduplicating Identical Candidate Strings**: Filtering out duplicate `zemberek_key` candidates caused by twin dictionary entries (`yok`, `hal`, `hak`, `kayıt`, etc.) before prompting.
2. **Pairing Every *"Prefer"* With Its *"Unless"* Exception**:
   - Explaining Zemberek's tag notation (`|Zero→Noun`, `|Zero→Verb`, postposition complement tags `PCNom`/`PCDat`/`PCAbl`/`PCIns`).
   - Distinguishing bare noun compounds (`[Noun+A3sg] [Noun+P3sg]`) from adjectival modifiers (`:Adj`).
   - Enforcing double-possessive blocking inside subordinate clauses (`X+Gen Y+Acc V+P3sg`).
   - Specifying Zemberek's representation tie-breaks (`+A3sg+ları:P3pl` over `+lar:A3pl+ı:P3pl`; direct lexicalized root over multi-step `|Caus`/`|Pass` derivation when identical in meaning; `:Time` over `:Noun` for temporal nouns unless bearing spatial case/possessive).

**Cross-Model Verification:** When we tested `GSLA-v2.4` on `test_slice_001` (`100` sentences, `637` ambiguous tokens), `gemini-3.8-flash` (`MEDIUM`) and `gemini-4-argon` (`MEDIUM`) reached **`99.06%` (`631 / 637`) exact agreement** (and `100%` agreement on the 12 previously disputed diagnostic sentences).

---

## 5. Stage 5: Eliminating Evaluation Bias With Dual Test Sets

A critical methodological risk in LLM-distilled NLP is **shared prompt/model bias**: if the training set and test set are annotated by the same model (`Argon MEDIUM`) using the same prompt (`GSLA-v2.4`), a trained model might appear artificially accurate simply by learning the prompt's stylistic quirks.

To eliminate this bias, we created **two independent gold annotations** for the 1,000-sentence held-out benchmark (`test_slice_001`, `11,582` total tokens, `6,587` ambiguous tokens):

1. **Test Set 1 — `Argon HIGH` + `Convention-Only` (`GSLA-Test-Conv-v1`)**:
   - Annotated by our strongest reasoning configuration (**`gemini-4-argon` `HIGH`**, averaging `1,255` thought tokens/sentence) using a minimal **Convention-Only** prompt that states *only* Zemberek's notation and tie-break conventions, leaving all grammatical reasoning to `Argon HIGH`.
2. **Test Set 2 — `Argon MEDIUM` + `GSLA-v2.4` Full Prompt**:
   - Annotated using the standard `Argon MEDIUM` + `GSLA-v2.4` production pipeline used for training slices.

**Test Set Comparison:** Across all `6,587` ambiguous tokens in `test_slice_001`, the two test sets agreed on **`6,471` tokens (`98.24%` Consensus Tokens)** and differed on only **`116` tokens (`1.76%` Disputed Tokens)**.

---

## 6. Stage 6: Scaling Results (`1K` → `2K` → `5K` → `8K` → `10K` vs. `33K`)

We annotated `10` training slices (`train_slice_001`..`010`, `10,000` sentences, `123,118` tokens) using `Argon MEDIUM` (`GSLA-v2.4`), strictly isolated from `test_slice_001`. At each cumulative milestone (`1K`, `2K`, `5K`, `8K`, `10K`), we trained a `FastPerceptronAmbiguityResolver` model (`10` epochs) and evaluated it against both test sets alongside Zemberek's 33,000-sentence baseline (`model-distilled-33k-th20k.bin`).

### 6.1 Accuracy on Both Test Sets (`1,000` Sentences / `6,587` Ambiguous Tokens)

| Model | Train Sentences | Model Size / Features | **Consensus Tokens (`6,471` tokens)** | **Test 1: `Argon HIGH Conv` (Ambig / Sentence Exact)** | **Test 2: `Argon MED v2.4` (Ambig / Sentence Exact)** |
| :--- | ---: | ---: | :---: | :---: | :---: |
| **[`model_1k_argon_v24.bin`](file:///home/dndara/data/turkish/annotated_slices/argon_medium_v24/models/model_1k_argon_v24.bin)** | `1,000` | `397 KB` / `48,339` | `87.03%` (`5,632`) | `86.18%` / `41.90%` | `86.46%` / `43.50%` |
| **[`model_2k_argon_v24.bin`](file:///home/dndara/data/turkish/annotated_slices/argon_medium_v24/models/model_2k_argon_v24.bin)** | `2,000` | `666 KB` / `81,158` | `88.87%` (`5,751`) | `87.95%` / `46.70%` | `88.30%` / `48.20%` |
| **[`model_5k_argon_v24.bin`](file:///home/dndara/data/turkish/annotated_slices/argon_medium_v24/models/model_5k_argon_v24.bin)** | `5,000` | `1.30 MB` / `161,471` | `91.73%` (`5,936`) | `90.69%` / `56.20%` | `91.21%` / `58.40%` |
| **[`model_8k_argon_v24.bin`](file:///home/dndara/data/turkish/annotated_slices/argon_medium_v24/models/model_8k_argon_v24.bin)** | `8,000` | `1.90 MB` / `228,046` | `92.54%` (`5,988`) | `91.47%` / `59.40%` | `92.03%` / `61.50%` |
| **[`model_10k_argon_v24.bin`](file:///home/dndara/data/turkish/annotated_slices/argon_medium_v24/models/model_10k_argon_v24.bin)** | **`10,000`** | **`2.16 MB` / `270,226`** | **`92.83%` (`6,007`)** | **`91.71%` / `59.70%`** | **`92.36%` / `62.50%`** |
| **[`model-distilled-33k-th20k.bin`](file:///home/dndara/work/zemberek-nlp/morphology/src/main/resources/tr/ambiguity/model-distilled-33k-th20k.bin)** | `33,000` | `6.10 MB` / `~680,000` | `92.47%` (`5,984`) | `91.68%` / `59.10%` | `91.68%` / `59.00%` |

### 6.2 Why `model-distilled-33k-th20k.bin` Scored Identically (`91.68%`) on Both Test Sets
Decomposing `test_slice_001` into the `6,471` Consensus Tokens and `116` Disputed Tokens explained why the 33K baseline scored `6,039 / 6,587` (`91.68%`) on both test sets:
- On the **`6,471` Consensus Tokens**, the 33K model got `5,984` right (`92.47%`).
- On the **`116` Disputed Tokens**, because the 33K model was trained on an earlier prompt generation, it had **zero preference** between the two test sets—splitting `55` (`MED`) vs. `55` (`HIGH`) vs. `6` (`Neither`), yielding `5,984 + 55 = 6,039` on both benchmarks.
- By contrast, our new `model_10k_argon_v24.bin` achieved **`6,007 / 6,471` (`92.83%`) on the Consensus Tokens** (`+23` tokens ahead of the 33K model) and beat the 33K model even on the independent `Argon HIGH` Convention-Only benchmark (`6,041` vs. `6,039` ambiguous tokens; `59.70%` vs. `59.10%` exact sentence match).

---

## 7. Key Takeaways

1. **Curated Submodular Slices Beat Raw Volume (`10K > 33K`)**: By restricting sentences to the `7–15` word window that matches the Perceptron's 4-token context horizon and selecting sentences via submodular novelty (`SCSS-v1`), `10,000` sentences (`2.16 MB` model) outperform `33,000` sentences (`6.10 MB` model).
2. **Teach the LLM Conventions, Not Textbook Grammar**: Frontier reasoning models (`gemini-4-argon`) already know Turkish syntax; over-constraining them with naive POS positional rules causes errors on bare noun compounds. Giving the LLM clean Zemberek convention tie-breaks (`GSLA-v2.4`) drives cross-model agreement above `99%`.
3. **Dual-Prompt Evaluation Separates True Generalization from Prompt Bias**: Splitting test evaluation into an independent `Argon HIGH` Convention-Only test set and `6,471` Consensus Tokens proves that the accuracy gains from `1K` to `10K` reflect genuine morphological generalization rather than prompt overfitting.

---

## 8. Reference Index of Project Documents & Tools

- **Corpus Refinement**: [`CORPUS_ANALYSIS_OVERVIEW.md`](file:///home/dndara/work/zemberek-nlp/experiment/docs/model/CORPUS_ANALYSIS_OVERVIEW.md) | [`RefineCorpus.java`](file:///home/dndara/work/zemberek-nlp/experiment/src/main/java/zemberek/morphology/ambiguity/model/RefineCorpus.java)
- **Ambiguity Taxonomy (`ZAS-v3`)**: [`TURKISH_AMBIGUITY_TAXONOMY_PROPOSAL.md`](file:///home/dndara/work/zemberek-nlp/experiment/docs/model/TURKISH_AMBIGUITY_TAXONOMY_PROPOSAL.md) | [`turkish_ambiguity_groups_v3_report.md`](file:///home/dndara/work/zemberek-nlp/experiment/docs/model/turkish_ambiguity_groups_v3_report.md)
- **Dataset Selection (`SCSS-v1`)**: [`SENTENCE_SELECTION_STRATEGY.md`](file:///home/dndara/work/zemberek-nlp/experiment/docs/model/SENTENCE_SELECTION_STRATEGY.md) | [`sentence_selection_report.md`](file:///home/dndara/work/zemberek-nlp/experiment/docs/model/sentence_selection_report.md) | [`SelectDisambiguationDataset.java`](file:///home/dndara/work/zemberek-nlp/experiment/src/main/java/zemberek/morphology/ambiguity/model/SelectDisambiguationDataset.java)
- **LLM Annotation & Prompts (`GSLA-v2.4`)**: [`GOLDEN_SET_LLM_ANNOTATION_STRATEGY.md`](file:///home/dndara/work/zemberek-nlp/experiment/docs/model/GOLDEN_SET_LLM_ANNOTATION_STRATEGY.md) | [`SliceCandidateGenerator.java`](file:///home/dndara/work/zemberek-nlp/experiment/src/main/java/zemberek/morphology/ambiguity/model/SliceCandidateGenerator.java) | [`annotate_slices_batch.py`](file:///home/dndara/work/zemberek-nlp/experiment/scripts/model/annotate_slices_batch.py)
- **Model Training & Evaluation**: [`TrainFastAmbiguityModel.java`](file:///home/dndara/work/zemberek-nlp/experiment/src/main/java/zemberek/morphology/ambiguity/dataset/TrainFastAmbiguityModel.java) | [`EvaluateAmbiguityModel.java`](file:///home/dndara/work/zemberek-nlp/experiment/src/main/java/zemberek/morphology/ambiguity/dataset/EvaluateAmbiguityModel.java)
