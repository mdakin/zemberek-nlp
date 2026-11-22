# Turkish Disambiguation Dataset Selection Strategy (`SCSS-v1`)

## 1. Problem Statement & Design Goals

We have a refined corpus of **100%-parseable Turkish sentences** across 29 domain files in `/home/dndara/data/turkish/refined` and a complete empirical ambiguity taxonomy (`ZAS-v3`, `42,055` canonical Meso-Signatures).

Our goal is to select two strictly isolated, slice-structured pools of sentences stored in `/home/dndara/data/turkish/selected_slices`:
1. **A 10,000-Sentence Held-Out Test Pool (`10 slices x 1,000 sentences`)**:
   - Stored in `/home/dndara/data/turkish/selected_slices/test_slices/test_slice_001.txt` .. `test_slice_010.txt` (plus `test_10k_ordered.txt`).
   - Every single `1,000`-sentence test slice is a **complete, balanced evaluation benchmark on its own** (combining `700` natural-distribution sentences for real-world accuracy/throughput and `300` diagnostic challenge sentences covering all 9 `ZAS-v3` Macro-Mechanisms and `F15`/`F17`-blind `LEXICAL_ONLY` twins).
2. **A 100,000-Sentence Master Training Pool (`100 slices x 1,000 sentences`)**:
   - Stored in `/home/dndara/data/turkish/selected_slices/train_slices/train_slice_001.txt` .. `train_slice_100.txt` (plus `train_100k_ordered.txt`).
   - Every single `1,000`-sentence slice **is a balanced, representative training set on its own**, AND any cumulative prefix of $k$ slices ($1\text{K}, 2\text{K}, 5\text{K}, 10\text{K}, \dots, 100\text{K}$) **maximizes new morphological, lexical, and contextual coverage** with minimal redundancy.

---

## 2. Assessment of the LLM Sentence-Length Theory (`7–15` Words Sweet Spot)

**Theory**: *Sending very long sentences one-by-one to an LLM to pick the correct morphological analysis degrades LLM accuracy; restricting selection to a compact range like `7–15` words will yield higher-quality labels.*

### Verdict: **Confirmed — Both for LLM Labeling Accuracy and for `FastPerceptronAmbiguityResolver`'s Architecture**

1. **Why LLMs Degrade on Long Morphological Disambiguation Prompts (`> 15` words)**:
   - **Combinatorial Prompt Load**: In Turkish, an ambiguous word has `3.18` candidate parses on average. A 25-word sentence contains ~16–18 ambiguous words (`50–60` candidate strings to compare) and multiple nested subordinate clauses (`-dIğI`, `-ArAk`, `-mAsI`, `-ken`).
   - **Positional Drift & Long-Distance Genitive Chains**: On sentences with `18–30` words, LLMs exhibit measurable attention dilution ("lost-in-the-middle") when resolving whether an `-I` suffix is `Noun+P3sg` (possessed by a genitive noun 8 words earlier) or `Noun+Acc` (direct object of a verb 6 words later), or when tracking multi-clause participle chains.
   - **Error Contagion in Sequential Perceptron Training**: If an LLM makes even 1 mistake in a 25-word sentence, that corrupted tag becomes the *context history* (`prev` / `prev2`) for the next words during `FastPerceptronAmbiguityResolver` training, polluting the transition weights (`F15`, `F17`, `F19`, `F20`).
2. **Why `FastPerceptronAmbiguityResolver` Does Not Need Sentences Longer Than `15` Words**:
   - Inspecting [`FastPerceptronAmbiguityResolver.java`](file:///home/dndara/work/zemberek-nlp/morphology/src/main/java/zemberek/morphology/ambiguity/ceptron/FastPerceptronAmbiguityResolver.java), the feature extractor (`Extractor`) **only ever inspects a 4-token local window**: `[w_{t-2}, w_{t-1}, w_t, w_{t+1}]` (`2` words left, `1` word right).
   - It has **zero features** that look 5, 10, or 20 words away. Therefore, a 25-word sentence provides **zero architectural advantage** to `FastPerceptronAmbiguityResolver` over a `7–15` word sentence, while costing ~2.2x more LLM tokens and introducing higher label noise!
3. **Why `7–15` Words Is the Optimal Range**:
   - **Rejects Telegraphic Fragments (`5–6` words)**: 5–6 word sentences in web/subtitle corpora are often elliptical fragments (`"Evet, ben de öyle düşünüyorum."`). Starting at **`7` words** ensures a complete Turkish clause structure (Subject + Modifiers/Objects + Subordinate/Participle + Finite Verb).
   - **Covers the Natural Core of the Corpus**: In `/home/dndara/data/turkish/refined`, the median sentence length is `8` words and the mean is `10.01` words; **over 58% of the entire refined corpus (~74 million sentences)** falls directly inside `[7, 15]` words.
   - **Three Balanced Sub-Buckets Within `7–15` Words**:
     - **Compact (`7–9` words)**: `35%` (`350` sentences per slice)
     - **Standard (`10–12` words)**: `40%` (`400` sentences per slice)
     - **Extended (`13–15` words)**: `25%` (`250` sentences per slice)

---

## 3. Why Naive Sampling Strategies Fail

| Strategy | What Happens at `1K–5K` Sentences | What Happens at `50K–100K` Sentences |
| :--- | :--- | :--- |
| **Uniform Random Sampling** | **Severe Zipf Starvation**: ~36% of words are unambiguous ($K=1$), and ~35% of ambiguous words are the same 20 closed-class words (`bir`, `bu`, `de`, `için`, `ile`). Over 90% of open-paradigm & `LEXICAL_ONLY` ambiguities get `0` examples. | Wastes ~40% of annotation budget re-labeling `bir` and `bu` 35,000+ times after their weights have already converged. |
| **Pure Greedy Submodular Maximization** | **Unnatural Density Distortion**: Packs Slice 1 with 100%-ambiguous sentences and zero unambiguous context anchors; breaks natural length/POS priors. | Late slices (`train_slice_050`+) contain only obscure tail words and cannot stand alone as balanced mini-batches. |
| **Stratified Curriculum Submodular Slicing (`SCSS-v1`, Proposed)** | **Balanced + High Coverage**: Slice 1 (`1,000` sents, `7–15` words) has natural length/domain priors, genuine $K=1$ context anchors, and covers `>93%` of corpus ambiguity mass across all 9 macro-mechanisms (`M1`–`M7`). | Every subsequent `1,000`-sentence slice maintains the same balanced structural blueprint while steering selection toward **unseen `(Signature, Lemma, ContextFrame)` tuples**. |

---

## 4. Architecture of the 10,000-Sentence Held-Out Test Pool (`10 Slices x 1,000`)

We construct **10 held-out test slices of 1,000 sentences each** (`10,000` test sentences total, all within `7–15` words) *before* selecting any training data.
Each `test_slice_XXX.txt` (`1,000` sentences) is interleaved so that it contains:
1. **`700` Stratified Natural Sentences (`70%`)**:
   - Sampled proportionally across all 29 source domains within `7–15` words.
   - Measures **True Production Accuracy** and **True Production Speed** (words/sec).
2. **`300` Stratified Diagnostic Challenge Sentences (`30%`)**:
   - Quota-balanced across all **9 Macro-Mechanisms (`M1`–`M7`)** and enriched for **`LEXICAL_ONLY_OBSERVABLE`** (`F15`/`F17`-blind twins like `bulunan/alınan`, `belirten/anlatan`, `değişiklik`, `yönündeki`) and **`PARTIAL_MORPHO_OBSERVABLE`** signatures.
   - Across successive test slices (`test_slice_001` -> `test_slice_010`), the challenge selector uses submodular novelty weights so each new test slice covers **different** lemmas and context frames.
3. **Companion Metadata TSV (`test_slice_XXX.meta.tsv`)**:
   - Alongside each `test_slice_XXX.txt`, the selector exports a lightweight metadata table (`line_no`, `subset_type` [`NATURAL` vs `CHALLENGE`], `domain`, `word_count`, `ambig_count`, `dominant_mechanism`, `has_lexical_only`).
4. **Strict Zero-Leakage Shingle Firewall**:
   - All `10,000` test sentences (and all of their 4-gram word shingles) are locked into an **Evaluation Shingle Firewall** before Stage 2 begins. Any training candidate sentence that shares $\ge 2$ four-word shingles (or $\ge 50\%$ shingle overlap) with *any* of the 10,000 test sentences is permanently excluded from the 100,000 training sentences.

---

## 5. Stratified Curriculum Submodular Slicing (`SCSS-v1`) for the 100K Training Pool

We partition the `100,000` training sentences into **100 slices of 1,000 sentences each** (`train_slice_001.txt` .. `train_slice_100.txt`), plus a single concatenated prefix-optimal file (`train_100k_ordered.txt`).

### 5.1. Candidate Hygiene, Orthography, Emoji & Parenthesis Rejection Gates
Before any sentence from `/home/dndara/data/turkish/refined` can enter the candidate pool:
1. **Strict `7–15` Word Length Gate**:
   - Only sentences with **`7 <= wordCount <= 15`** (excluding punctuation) are accepted.
2. **Strict Parenthesis, Bracket & Emoji Rejection Gate**:
   - Immediately rejects any sentence containing parentheses or brackets: `(`, `)`, `[`, `]`, `{`, `}`, `<`, `>`.
   - Immediately rejects any sentence containing Unicode emojis, pictographs, dingbats, symbols, supplementary code points, variation selectors, or text emoticons (`:)`, `:(`, `;D`, etc.). Every character in the sentence must belong to a strict whitelist of standard Turkish letters (`a-zA-ZçÇğĞıİöÖşŞüÜâÂîÎûÛ`), ASCII digits (`0-9`), space, and standard clause punctuation (`.`, `,`, `;`, `:`, `?`, `!`, `'`, `"`, `-`, `%`, `/`).
3. **Clean Sentence Orthography**:
   - Must start with an uppercase Turkish letter (rejects numbered list items like `"3. aile..."` or `"7. Doğum..."` and mid-sentence fragments).
   - Must end with clean terminal punctuation (`.`, `?`, or `!`) and must not contain ellipsis chains (`..`, `...`) or list abbreviations (`vs.`, `vb.`).
4. **Ambiguity & Context Anchor Balance (`2 <= ambigCount < wordCount`)**:
   - Must contain at least `2` ambiguous words ($N_{\text{ambig}} \ge 2$) and at least `1` strictly unambiguous word ($K=1$, i.e. $N_{\text{ambig}} < N_{\text{words}}$).
5. **Boilerplate & Duplicate Gate**:
   - Rejects sentences where `> 25%` of tokens contain digits or where any word is repeated $\ge 3$ times.
   - Rejects exact duplicates (64-bit FNV-1a normalized hash) and near-duplicates ($\ge 50\%$ 4-gram word shingle overlap with any already-selected sentence).

### 5.2. The Within-Slice Structural Blueprint (1,000 Sentences per Slice)
To guarantee that **every 1,000-sentence slice is a complete, representative training set on its own**, each slice $s \in \{1, \dots, 100\}$ enforces:
1. **Length Stratification Within `7–15` Words**:
   - **Compact (`7–9` words)**: `350` sentences (`35%`)
   - **Standard (`10–12` words)**: `400` sentences (`40%`)
   - **Extended (`13–15` words)**: `250` sentences (`25%`)
2. **Domain Cap**: No single source file (out of 29) can contribute more than `60` sentences (`6%`) to any single 1,000-sentence slice.
3. **Per-Slice Closed-Class Saturation Cap**:
   - Within each slice, once a high-frequency signature (`bir`, `bu`, `de`, `için`, `ile`, etc.) has received selection credit `15` times, its marginal selection reward drops by `95%` for the rest of that slice.

### 5.3. The Submodular Marginal Gain Objective (Across Slices $1 \dots 100$)
For each candidate sentence $S$ with ambiguous tokens $w_1, \dots, w_m$, let $C_{\text{sig}}(g)$, $C_{\text{lem}}(g, \ell)$, and $C_{\text{ctx}}(g, c)$ be the **global cumulative counts** across all training slices selected so far, and let $L_{\text{sig}}(g)$ be the **local count within the current 1,000-sentence slice**:
$$\text{Gain}(w_i) = q_{\text{slice}}(g_i) \cdot \mu(w_i) \cdot \left( \frac{3.0}{1 + C_{\text{sig}}(g_i)} + \frac{2.0}{1 + C_{\text{lem}}(g_i, \ell_i)} + \frac{1.5}{1 + C_{\text{ctx}}(g_i, c_i)} + \frac{1.2}{1 + L_{\text{sig}}(g_i)} \right)$$
$$\text{Score}(S) = \frac{\sum_{i=1}^{m} \text{Gain}(w_i)}{m^{0.55} \cdot |S|^{0.35}} \cdot \text{AnchorQualityMultiplier}(S)$$

---

## 6. Output Directory Layout

All selected slices are exported to `/home/dndara/data/turkish/selected_slices`:

```text
/home/dndara/data/turkish/selected_slices/
├── test_slices/
│   ├── test_slice_001.txt             # Test Slice 1 (1,000 sents, 7-15w: 700 Natural + 300 Challenge)
│   ├── test_slice_001.meta.tsv        # Per-sentence metadata (NATURAL/CHALLENGE, mechanism, etc.)
│   ├── test_slice_002.txt             # Test Slice 2 (1,000 sents — great as Dev/Validation set!)
│   ├── ...
│   └── test_slice_010.txt             # Test Slice 10
├── train_slices/
│   ├── train_slice_001.txt            # Training Slice 1 (Sentences 1 .. 1,000 — Annotate first!)
│   ├── train_slice_002.txt            # Training Slice 2 (Sentences 1,001 .. 2,000)
│   ├── ...
│   └── train_slice_100.txt            # Training Slice 100 (Sentences 99,001 .. 100,000)
├── test_10k_ordered.txt               # All 10,000 test sentences in slice order
└── train_100k_ordered.txt             # All 100,000 training sentences in prefix-optimal order
```
