# High-Accuracy Turkish Deasciification: Investigation, Audit & Next-Generation Algorithm Design

## 1. Problem Domain: What Is Turkish Deasciification?

**Deasciification** (diacritic restoration) is the inverse problem of recovering Turkish characters stripped when text is typed on ASCII keyboards, SMS/chat interfaces, legacy encodings, or cross-locale systems.

Formally, asciification is a deterministic many-to-one projection $\mathcal{A}: \Sigma_{\text{TR}}^* \to \Sigma_{\text{ASCII}}^*$:

| Turkish Characters | ASCII Projection | Linguistic & Orthographic Nuance |
| :--- | :---: | :--- |
| `ç / Ç` | `c / C` | Contrasts stops/affricates (`ac`/`aç`, `sac`/`saç`, `uc`/`üç`) |
| `ğ / Ğ` | `g / G` | Voicing/lengthening (`dag`/`dağ`, `yag`/`yağ`, `-dığı`/`-digi`) |
| `ı / i` and `I / İ` | `i` and `I` | **Four-way Turkish case asymmetry:** lowercase `ı` and `i` collapse to `i`; uppercase `I` and `İ` collapse to `I` (`ISKI` → `İSKİ`, `ILIK` → `ILIK` or `İLİK`) |
| `ö / Ö` | `o / O` | Front/back rounding contrast (`gol`/`göl`, `on`/`ön`, `olum`/`ölüm`) |
| `ş / Ş` | `s / S` | Sibilant contrast (`as`/`aş`, `sik`/`sık`/`şık`, `kis`/`kış`) |
| `ü / Ü` | `u / U` | Front/back rounding contrast (`turu`/`türü`, `uc`/`üç`, `kuru`/`kürü`) |
| `â, î, û` *(optional)* | `a, i, u` | **Circumflex minimal pairs:** `hala`/`hâlâ`, `kar`/`kâr`, `adet`/`âdet`, `hakim`/`hâkim`, `zeka`/`zekâ` |

### Why Single-Level Character Models Hit an Accuracy Wall: The 4 Regimes

Empirical testing on **2,500 clean Turkish sentences (31,355 word tokens)** using Zemberek's [`TurkishMorphology`](morphology/src/main/java/zemberek/morphology/TurkishMorphology.java) (`ignoreDiacriticsInAnalysis` + `WordGenerator`) reveals that Turkish words fall into **four distinct ambiguity regimes** under ASCII projection:

| Regime | Share of Running Words | Avg. Valid Candidates | Examples | What Information Resolves It? |
| :--- | :---: | :---: | :--- | :--- |
| **A. Deterministic Morphophonology** | **`81.08%`** (`25,424`) | **`1.00`** | `arkadaslarimizdan` → `arkadaşlarımızdan`, `havadisleri` → `havadisleri`, `turta` → `turta` | Root lexicon + Turkish vowel/consonant harmony alone (**zero ML ambiguity**) |
| **B. Morphosyntactic Homographs** | **`~11%`** | **`~2.1`** | `sakin`/`sakın`, `mudur`/`müdür`, `mandalina`/`mandalına`, `uc`/`üç`/`uç`, `on`/`ön`, `hala`/`hâlâ` | Bidirectional POS, case government, classifier syntax (`iki kilo mandalina`), punctuation (`mudur?`) |
| **C. Semantic / Collocational Homographs** | **`~7.16%`** *(B+C = `18.16%`, `5,693`)* | **`~2.1`** | `karliligi`/`kârlılığı`, `bellek turu`/`türü`, `ipe asmam`/`engeli aşmam`, `güneşin batışı`/`Avrupa'nın batısı`, `sık`/`şık`/`sik` | Lemma N-grams + wide-window (`±5` words / sentence) lexical collocations |
| **D. True OOVs, Acronyms & Foreign Spans** | **`0.76%`** (`238`, `<0.3%` after `-`/`'` split) | `0` in base dict | `süperpozisyon`, `tensörlere`, `kübitlerinin`, `TÜİK` vs `BIOS`, `Random Access Memory` | Corpus word/acronym tables + vowel-harmony-aware character model + foreign-token detector |

> [!IMPORTANT]
> **Key Empirical Insight:** Over **81% of running Turkish words have only a single morphologically valid Turkish surface**, and the **18.16% ambiguous words average only `2.11` valid candidates** (with **100.00% in-vocabulary oracle recall**). Any algorithm that guesses characters independently without constraining to valid Turkish morphology throws away free 100% precision on four-fifths of the language.

---

## 2. Audit of Zemberek's Current [`Deasciifier`](normalization/src/main/java/zemberek/normalization/deasciifier/Deasciifier.java)

Zemberek's current implementation is a port of Deniz Yüret's 2006 Emacs `turkish-mode` **Greedy Prepend Algorithm (GPA)** decision list (`13,462` substring rules across `c, g, i, o, s, u`, stored as a `236 KB` Java `ObjectInputStream` in `turkishPatternTable`).

### Live Benchmark on 2,500 Clean Sentences (`31,334` Words)

| Metric | Current `Deasciifier` Score | Diagnosis |
| :--- | :---: | :--- |
| **Ambiguous Character Accuracy (`c/g/i/o/s/u`)** | `99.06%` (`67,852 / 68,497`) | Looks high on paper, masking sentence-level failures |
| **Overall Word Accuracy** | `97.85%` (`30,661 / 31,334`) | `673` wrong words per 2,500 sentences |
| **Diacritic-Bearing Word Accuracy** | `96.74%` (`15,230 / 15,744`) | `3.26%` error rate on words that actually need restoration |
| **Exact Sentence Accuracy** | **`77.16%`** (`1,929 / 2,500`) | **Nearly 1 in 4 sentences (`22.84%`) contains at least one error** |

### Six Concrete Flaws Found in [`Deasciifier.java`](normalization/src/main/java/zemberek/normalization/deasciifier/Deasciifier.java)

1. **`24.4%` of All Errors Are Non-Existent Gibberish Words (`164 / 673` errors):**
   Because `Deasciifier` has zero lexicon or morphotactic knowledge, it routinely outputs strings that are not valid Turkish words even when Zemberek morphology has **only 1 candidate**:
   - `havadisleri` → `havadişleri`, `turta` → `türta`, `tarçın` → `tarçin`, `kızartır` → `kızartir`, `aniden` → `anıden`, `sütüyle` → `şutüyle`, `kornişe` → `kornise`, `ötelemek` → `otelemek`.
2. **Blind to Right-Word Context ([`turkishGetContext`](normalization/src/main/java/zemberek/normalization/deasciifier/Deasciifier.java#L158-L168)):**
   On the right side of the target character, `while (i < s.length && !space && index < buffer.length)` **terminates at the very first space**. When evaluating `sakin` in `"sakin unutma!"`, `Deasciifier` cannot see a single character of `"unutma!"` — accounting for a large portion of the **`61.7%` (`415 / 673`) valid-homograph errors** (`sakin` vs `sakın`, `mudur` vs `müdür`, `asmam` vs `aşmam`, `mandalina` vs `mandalına`). Even [`TestDeasciifier.java`](normalization/src/test/java/zemberek/normalization/deasciifier/TestDeasciifier.java#L23) hardcodes known failures (`"bellek turu"` instead of `"bellek türü"`, `"ana bellege"` instead of `"ana belleğe"`, `"BİOS"` instead of `"BIOS"`).
3. **Greedy Left-to-Right In-Place Mutation ([`deasciify`](normalization/src/main/java/zemberek/normalization/deasciifier/Deasciifier.java#L238-L250)):**
   `buffer[i]` is mutated in-place left-to-right and fed into subsequent left-context lookups. A wrong flip on the first syllable cascades into vowel-harmony violations on later syllables (`sütüyle` → `şutüyle`).
4. **Missing `'ü'`/`'Ü'` Bug & Destructive Toggling on Clean/Mixed Text ([`Deasciifier.java`](normalization/src/main/java/zemberek/normalization/deasciifier/Deasciifier.java#L38-L49), [`turkishNeedCorrection`](normalization/src/main/java/zemberek/normalization/deasciifier/Deasciifier.java#L194-L210)):**
   `turkishAsciifyTable` omits `'ü'` and `'Ü'` completely, and `return c == tr ? m : !m` **actively strips existing valid Turkish diacritics** (`ç → c`, `ş → s`, `ö → o`) whenever a partially-diacritized or clean Turkish input contains a word outside the 13,462 rules.
5. **Single-Winner Decision List Cannot Combine Evidence:**
   GPA picks a single highest-ranked substring rule; it cannot sum weak lexical, morphological, vowel-harmony, and contextual cues the way linear discriminative models (Perceptron / CRF / SVM) or neural models do.
6. **Zero Circumflex Support & Heavy Per-Character Object Allocation:**
   Cannot restore `â, î, û` (`hâlâ`, `kâr`, `zekâ`, `tezgâh`), and [`turkishMatchPattern`](normalization/src/main/java/zemberek/normalization/deasciifier/Deasciifier.java#L128-L147) allocates $O(W^2)$ `String` substrings per ambiguous character.

---

## 3. Surveying Alternative Paradigms

With **gigabytes of clean Turkish sentences** available for self-supervised training $(s \mapsto (\mathcal{A}(s), s))$, four serious architectures merit comparison:

| Dimension | 1. Current GPA Decision List | 2. Pure Character Structured Perceptron / CRF | **3. Proposed: Hybrid Lattice + Margin-Perceptron + Char-Viterbi OOV** | 4. Neural BiGRU / Small Byte-Transformer (ONNX) |
| :--- | :---: | :---: | :---: | :---: |
| **Expected Word Acc (Clean Benchmarks)** | `~97.8%` | `~98.6–98.9%` | **`~99.5–99.8%`** | `~99.5–99.8%` |
| **Expected Exact Sentence Acc** | `~77%` | `~85–88%` | **`~94–97%`** | `~94–97%` |
| **Eliminates Invalid Turkish Gibberish?** | No (`24%` of errors) | Mostly, but still hallucinates on rare stems | **Yes (100% constrained on in-vocab)** | No unless lattice-constrained |
| **Resolves POS/Syntactic Homographs (`sakın unutma`, `mudur?`)?** | Weak (no right-word context) | Partial (`±7` chars) | **Strong (full POS/IG/case Viterbi)** | Strong |
| **Resolves Wide Semantic Homographs (`şirket ... kârlılığı`, `ipe asmam`)?** | No | No | **Strong (Error-driven lemma bigrams + `±5`-word collocations)** | Strong (self-attention) |
| **Handles OOVs (`tensörlere`), Typos (`arkdaslar`) & Foreign (`BIOS`)?** | Poor (`BİOS`) | Good | **Strong (Lexicon-Dropout Harmony Char-Viterbi + foreign shield)** | Strong |
| **Java Throughput (1 CPU core)** | `~15k sent/s` | `~25k sent/s` | **`~20k–45k sent/s`** *(81% tokens skip ML!)* | `~100–400 sent/s` (30–100x slower) |
| **Runtime Dependencies & Footprint** | Pure Java (`236 KB`) | Pure Java (`~400 KB–2 MB`) | **Pure Java (`~1.0–3.5 MB` quantized!)** | Requires `onnxruntime` JNI (`~40+ MB`) |

### Why Hybrid Lattice + Compact Quantized Margin-Perceptron Fits in `~1.0–3.5 MB`

What previously inflated our rough size estimate to `10–30 MB` was shipping separate generative 3-gram `SmoothLm` tables (`15–25 MB`) and redundant dictionary tables. Neither is needed:

1. **`TurkishMorphology` (`lexicon.bin`, `1.3 MB`) is already in memory:** Zero extra bytes are needed for the `81.1%` unambiguous words.
2. **Error-Driven Sparsity Beats Generative LMs by `10x` in Size:** A generative `SmoothLm` spends ~98% of its megabytes storing probabilities for unambiguous words ($P(\text{Cumhurbaşkanı} \mid \text{Türkiye})$) that never compete in an ASCII confusion set. An **error-driven Margin-Averaged Perceptron** only allocates non-zero weights to contextual features (`word/lemma bigrams`, `POS/IG transitions`, `±5-word collocations`) that **actually separate competing ASCII homographs**!
3. **`CompactQuantizedWeights` (`3.4 bytes/feature` vs [`LossyIntLookup`](core/src/main/java/zemberek/core/compression/LossyIntLookup.java)'s `8.4 bytes/feature`):**
   Combining Zemberek's `MultiLevelMphf` (`~0.4 B/key`) with a `16-bit` fingerprint (`2.0 B/key`, `1.5e-5` zero-mean collision rate) and `int8` quantized averaged weights (`1.0 B/key` + per-bucket `fp16` scale, `<0.01%` accuracy delta vs `fp32`) packs **500,000 discriminative features into `1.7 MB`**:

| Model Profile | Sentence Perceptron Features | OOV / Typo Char-Perceptron | Exception / Acronym / Foreign Table | **Total Binary Size** | Target Use Case |
| :--- | :---: | :---: | :---: | :---: | :--- |
| **`Nano` *(Morphology-Free Standalone)*** | — | `~110k` (`380 KB`) | `~3k` (`60 KB`) | **`~440 KB`** | Zero-morphology ultra-light mode |
| **`Compact`** | `~200k` (`680 KB`) | `~85k` (`290 KB`) | `~4k` (`80 KB`) | **`~1.05 MB`** | Minimal footprint (`<0.15%` word acc trade-off) |
| **`Default` *(Recommended)*** | `~500k` (`1.70 MB`) | `~115k` (`390 KB`) | `~6k` (`120 KB`) | **`~2.2 MB`** | High accuracy, well below `6–7 MB` budget |
| **`Max` *(Under 6 MB Cap)*** | `~1.25M` (`4.25 MB`) | `~130k` (`440 KB`) | `~8k` (`160 KB`) | **`~4.85 MB`** | Deep long-tail corpus collocations |

---

## 4. Proposed Architecture: `PerceptronDeasciifier`

```mermaid
flowchart TD
    A["Input Text (Pure ASCII, Partial-ASCII, Typos, or Mixed Turkish/Foreign)"] --> B["Stage 0: Span Guard (URL/Email/Code) + 1:1 Offset Mask + Elongation/Numeral Handler"]
    B --> C["Stage 1: Candidate Lattice Builder per Token"]
    C --> C1["1a. Compact Acronym / Foreign Shield + Confusion Priors (~120 KB)"]
    C --> C2["1b. Zemberek Formal + Informal AsciiTolerantMorphology"]
    C --> C3["1c. Harmony Char-Viterbi Perceptron (~390 KB, Always Ready for OOV/Typos/Hijack Guard)"]
    C1 --> D["Merge Valid Candidates (Inject 1c Top-2 on OOV/Typos OR Obscure-Root Suspicion)"]
    C2 --> D
    C3 --> D
    D --> G["Stage 2: 2-Pass Sentence Lattice Decoder (Beam=8 Viterbi + Bidirectional Rerank)"]
    G --> H["Stage 3: Turkish-Aware Case & Elongation Projection (Exact 1:1 Input Length)"]
```

### Stage 0: Contracts, Invariants & Deterministic Pre-Handlers *(Refined in Audit Pass 2)*
1. **Strict `1-to-1` Length & Offset Invariance (`output.length() == input.length()`):**
   Unlike `TurkishSentenceNormalizer` (which splits/merges words), a deasciifier must be a pure character-level projection preserving exact string length, whitespace, punctuation, and character offsets (supporting in-place `deasciify(char[] buffer)`).
2. **Non-Destructive Partial-ASCII Constraint & The Turkish `'I' / 'i'` 4-Way Case Rule:**
   - Positions already carrying unambiguous Turkish diacritics (`ç, Ç, ğ, Ğ, ı, İ, ö, Ö, ş, Ş, ü, Ü, â, Â, î, Î, û, Û`) are **pinned** and never stripped.
   - **Critical Turkish Case Trap Fixed:** Both lowercase `'i'` (`{i, ı}`) **and uppercase `'I'` (`{I, İ}`)** are ambiguous in ASCII input (`"ISTANBUL"` → `"İSTANBUL"`, `"IGDIR"` → `"IĞDIR"`). Naive `toLowerCase(Turkish.LOCALE)` turns ASCII `'I'` into `'ı'` before analysis, which would falsely pin `'ı'`! Input normalization must fold ASCII `'I'` to ambiguous `'i'` while tracking an explicit per-character casing mask.
3. **Immutable Span Shield (URLs, Emails, `@handles`, File Paths, Code):**
   Tokens matching URL, email, `@mention`, file path, or `camelCase` identifier patterns bypass deasciification completely (`https://ornek.com/urunler` stays untouched).
4. **Expressive Elongation Squeeze & Projection (`coooook` → `çooooook`):**
   Runs of $\ge 3$ identical letters (`coooook`, `guzeeeel`, `sabirsizliiiik`) are compressed (`cok` / `coook`) with a run-length map for candidate lookup, then projected back onto every character of the original run (`çooooook`, `sabırsızlıııık`).
5. **Deterministic Numeral + Apostrophe Phonology (`3'uncu` → `3'üncü`, `100'luk` → `100'lük`):**
   Character models fail after digits because digits carry no vowel characters. A tiny deterministic Turkish number-reading helper maps the final pronounced word of any numeral (`3 → üç`, `6 → altı`, `9 → dokuz`, `10 → on`, `40 → kırk`, `100 → yüz`, `1923 → üç`) to restore suffixes after `'`/`’` (`3'üncü`, `6'ncı`, `1923'ün`) with 100% accuracy and `0 KB` model cost.
6. **Sub-Token Decomposition (Hyphens, Apostrophes & Glued Clitics):**
   - **Hyphenated compounds** (`e-postayla`, `sodyum-iyon`): restore each segment and rejoin.
   - **Apostrophe stems** (`Cukurova'daki`, `TUIK'in`, `Karadeniz'in`): match stem case-insensitively with `equalsIgnoreDiacritics` against proper nouns/acronyms (fixing the current `analyzeWordsWithApostrophe` gap on `-daki` `Adjective` derivations!) and harmonize the suffix.
   - **Unspaced clitics** (`gelecekmisin`, `burdami`): if unparseable whole, check stem + `mI/dA` split internally to recover stem/clitic vowels (`gelecekmisin`, `burdamı`) while keeping 1:1 character length.

### Stage 1: Candidate Lattice + OOV / Erroneous-Word Fallback
For each token $x_i$:
1. **Compact Exception, Acronym & Foreign-Shield Table (`~120 KB`):**
   Stores only high-value items missing from `lexicon.bin`: Turkish acronyms (`TÜİK`, `TÜBİTAK`, `ÖSYM`, `İETT`), common unlisted loanwords (`süperpozisyon`, `şarj`, `tensör`, `kübit`), and **verified ASCII-keep English words** (`BIOS`, `RAM`, `ROM`, `Windows`, `Docker`, `Read`, `Only`, `Memory`, `online`, `link`) mined from clean Turkish sentences.
2. **Formal + Informal `AsciiTolerant` Morphology (`lexicon.bin`):**
   Includes **both** `TurkishMorphotactics` and `InformalTurkishMorphotactics` (`useInformalAnalysis()`), immediately covering colloquial spoken forms (`geliyom`, `gidicem` → `gidicem`, `gorusecez` → `görüşecez`, `bakiyolar` → `bakıyolar`) alongside formal Turkish!
   - **Hot-Token Cache:** An L1 cache of the top `32k` ASCII tokens (`~90%+` token hit rate) returns precomputed candidate arrays in `O(1)`.
3. **Stage 1c — Character-Level Harmony Structured Perceptron (`~390 KB`) for Unparseable, Loanword & Erroneous Words:**
   Handles **all non-lexicon words**: typos (`arkdaslar` → `arkdaşlar`, `ogretmnler` → `öğretmnler`, `yanliz` → `yanlız`, `sarzim` → `şarzım`), hybrid slang (`pushladim` → `pushladım`, `like'ladim` → `like'ladım`), and unseen domain words (`kriyojenik`, `spektroskopik`):
   - **Exact Character-Viterbi over $k = 1 \dots L$** with state `(tag_k, vowel_harmony_class ∈ {Start, FrontUnrounded, FrontRounded, BackUnrounded, BackRounded})`, directly enforcing vowel harmony on suffixes even when the stem has a typo (`arkdaşlar`, `şarzım`)!
   - **Features:** Hashed char $n$-grams ($n=1..6$) in $[-6, +6]$ (crossing word spaces into $w_{i-1}$ and $w_{i+1}$!), word prefix/suffixes, casing pattern, and loanword disharmony cues (`-pozisyon`, `oto-`, `mikro-`, `kripto-`).
   - **Lexicon-Hijacking Guard (Important!):** Even when `TurkishMorphology` finds a candidate, if *all* morphological candidates rely on low-frequency/archaic roots or heavy derivations (`groupCount >= 3`), we **inject Stage 1c's top-1 prediction (and raw ASCII identity) into $\mathcal{C}(x_i)$** so an obscure dictionary root cannot hijack an unlisted loanword, typo, or English word!

### Stage 2: Two-Pass Sentence Margin-Perceptron Decoder
Let candidate $z_i = (w_i, a_i)$ pair surface $w_i$ with its primary analysis $a_i$ (or `OOV_CHAR` tag for Stage 1c candidates).

- **Pass 1 — Beam Search (`beam = 8`)** scores $(z_{i-2}, z_{i-1}, z_i)$ left-to-right, resolving even **consecutive ambiguous chains** like `"on uc kisi"` (`{on, ön} × {üç, uç} × {kişi, kışı}` → `Num + Num + Noun` = `"on üç kişi"`).
- **Pass 2 — Fast Bidirectional Refinement:** Fixing Pass 1's draft sequence $\hat{z}_{1..n}$, re-evaluates *only* ambiguous positions ($|\mathcal{C}(x_i)| \ge 2$, `~18%` of tokens) with full symmetric left **and** right draft analyses $\hat{z}_{i-3..i+3}$ (`<5%` compute overhead, eliminates left-to-right myopia when $i+1$ was also ambiguous!).

All features hash via 64-bit `SplitMix64` into the single `CompactQuantizedWeights` table (zero `String` allocations):
1. **Confusion Prior & Surface/Lemma Bigram-Trigram Features:** `(ascii_i, w_i)`, `(lemma_{i-1}, lemma_i)`, `(lemma_i, lemma_{i+1})`, `(w_{i-1}, w_i)`, `(w_i, w_{i+1})`. Generalizes `(ip+Dat, asmak)` vs `(engel+Acc, aşmak)` across all inflections without a separate `SmoothLm`!
2. **Morphosyntactic & Agreement Features:** `(POS, SecondaryPos, lastIG, Case, Poss, Polarity)` + neighbor transitions (`sakın:Adv + Verb_Imp_Neg`, `mudur:Ques + "?"`, `iki kilo + mandalina:Noun_Nom`, Izafet `bellek + türü:P3sg`).
3. **Wide-Window Confusion Collocations (`±5` words):** Fired *only* when $|\mathcal{C}(x_i)| \ge 2$: `(cand_lemma, context_content_lemma)` (`şirket/bütçe/döviz` → `kâr` vs `dağ/kış` → `kar`; `çamaşır/ip` → `asmak` vs `engel/sınır` → `aşmak`; `güneş` → `batış` vs `Avrupa` → `batı`).
4. **Char-Model Confidence & Casing Features:** Stage 1c character log-margin (helps pick OOV/foreign over archaic dictionary false friends), mid-sentence `TitleCase × ProperNoun` (`Bolu` vs `bölü`), `ALL_CAPS` acronym flags.
5. **Asymmetric Taboo Guard:** High loss margin ($\ell = 2.5$) + negative bias against profane homographs (`sik` vs `sık`/`şık`, `got` vs `göt`) unless explicit context strongly supports them.

---

## 5. Multi-Gigabyte Self-Supervised Training Pipeline *(With OOV & Typo Training!)*

1. **OOV-Preserving Corpus Hygiene Filter (Fixed!):**
   - **Do NOT require every word in a training sentence to be parseable by Zemberek** — doing so would discard real neologisms (`süperpozisyon`), slang (`şarzım`, `geliyom`), and mixed words (`pushladım`)!
   - Instead, validate cleanliness **at the sentence level**: accept any sentence where (a) overall Turkish diacritic density on `{c,g,i,o,s,u}` is in the natural range ($\ge 7\%$) AND (b) common sentinel words in the sentence (`için`, `değil`, `çok`, `büyük`, `göre`, `olarak`, `-dığı`, `-üyor`) are properly diacritized (confirming the author typed with a real Turkish keyboard). Every Zemberek-unparseable word inside such verified sentences becomes **gold OOV training data**!
   - Gate circumflex confusion updates (`hala/hâlâ`, `kar/kâr`) to sentences/sources that actively use circumflexes.
2. **Training Stage 1c (Character Harmony Perceptron) with 100% Lexicon Dropout + Synthetic Typos:**
   - If Stage 1c only trained on the `0.8%` unparseable words, it would starve! Instead, train Stage 1c on **100% of words** (ignoring the lexicon during its training) **plus `15%` synthetic noise**:
     - Random character deletion/transposition (`arkadaşlar` → `arkdaşlar`, `öğretmenler` → `öğretmnler`, `yalnız` → `yanlız`) before asciifying (`arkdaslar ↦ arkdaşlar`, `yanliz ↦ yanlız`).
     - Expressive vowel/consonant elongation (`çok` → `çooook`) and apostrophe omission (`Türkiye'de` → `Türkiyede`).
   - Result: Stage 1c becomes a battle-tested `~390 KB` general Turkish morphophonological deasciifier that handles unseen roots, slang, and misspelled words effortlessly.
3. **Training Stage 2 (Sentence Margin-Perceptron) via Streaming Out-of-Core Passes:**
   - **Pass 1 (Linear Scan):** Collect confusion-set counts (pruning hapax noise), verified foreign ASCII-keep words, and frequent unindexed OOV/acronym entries (`~120 KB`).
   - **Pass 2 (`2–4` Streaming Epochs):** Apply **stochastic partial-asciification** (`75%` full ASCII, `20%` random partial-ASCII word/char mix, `5%` clean identity) + **loss-augmented Margin-Perceptron** ($\tilde{z} = \arg\max_z [\mathbf{w}\cdot\boldsymbol{\phi}(z) + \gamma \ell(z, z^*)]$) into a fixed `float[1 << 23]` (`32 MB` training buffer), prune small weights ($|\bar{w}| < \tau$) to hit the exact target feature count (`200k` / `500k` / `1.25M`), and quantize to `CompactQuantizedWeights` (`~1.0–4.8 MB` on disk).

---

## 6. Evaluation Protocol (6 Benchmark Suites)

1. **Clean Formal / News / Book Benchmark:** Exact Sentence Accuracy, Ambiguous-Word Accuracy, Overall Word Accuracy, Circumflex Accuracy.
2. **Hard Homograph Contrastive Suite:** Top 200 semantic & syntactic minimal pairs (`sakin/sakın`, `mudur/müdür`, `kar/kâr`, `hala/hâlâ`, `turu/türü`, `asmak/aşmak`, `on üç kişi`, `sık/şık/sik`, `batısı/batışı`, `gölü/golü`).
3. **OOV, Informal & Erroneous/Typo Suite:** Unlisted loanwords (`süperpozisyon`, `tensörlere`), informal verbs (`gidicem`, `görüşecez`), typos (`arkdaslar`, `yanliz`, `sarzim`), elongations (`coooook guzeeel`), and numerals (`3'uncu`, `100'luk`).
4. **Mixed / Partial-ASCII Suite:** `50%` diacritized + `50%` ASCII sentences, verifying **100% non-destructive preservation** of existing Turkish characters.
5. **Code-Switched / Technical / Span-Immunity Suite:** English terms (`BIOS`, `RAM`, `Read Only Memory`), Turkish acronyms (`TÜİK`, `İETT`), URLs, emails, and `@handles`.
6. **Throughput, Model Size & Heap Benchmark:** Verifying `< 2.5 MB` default model size, `1:1` character length preservation, `tokens/sec`, and retained heap vs current [`Deasciifier`](normalization/src/main/java/zemberek/normalization/deasciifier/Deasciifier.java).

---

## 7. Deep Investigation: Extending GPA ("GPA++" & "Soft-GPA") + Small ML Helper

### 7.1 Inside Zemberek's Current `turkishPatternTable` & Mathematical Nature of GPA
Inspecting the serialized `turkishPatternTable` resource directly reveals:
- **Only `13,462` rules total** (`s: 3,198`, `i: 2,953`, `c: 2,544`, `u: 2,394`, `o: 1,621`, `g: 752`), with an **average pattern length of just `5.0` characters** (`~65 KB` of raw string data, inflated to `236 KB` solely by `java.io.ObjectOutputStream`).
- **Mathematical Equivalence ($L_\infty$ Winner-Take-All Perceptron):**
  An ordered GPA decision list $(s_1, y_1), \dots, (s_M, y_M)$ with $y_r \in \{-1, +1\}$ is **algebraically identical** to a linear dot-product classifier with exponentially decaying weights $w_r = y_r \cdot 2^{-r}$:
  $$\text{GPA}(x, i) = \text{sign}\!\left( \sum_{r : s_r \in \Phi(x, i)} y_r \cdot 2^{-r} \right)$$
  Because $2^{-r} > \sum_{k > r} 2^{-k}$, **one higher-ranked substring rule unconditionally overrides any number of agreeing stem, suffix, and vowel-harmony cues below it** ("tyranny of a single rule", e.g., `diXleri` from `dişleri`/`gidişleri` overriding `havadis` → `havadişleri`), and produces **zero calibrated confidence margin**.

### 7.2 Live Proof-of-Concept: Raw 2006 GPA + 15-Line Morphological Snubber
Even *before* retraining GPA, testing our 2,500-sentence benchmark (`31,334` words, `c/g/i/o/s/u`) shows how orthogonal GPA and morphological validation are:
- **Raw 2006 GPA:** `98.52%` word accuracy (`463` non-circumflex errors), **`83.76%`** sentence accuracy (`77.16%` when counting `210` missing circumflex words like `hâlâ`, `kâr`, `zekâ`).
- **2006 GPA + 15-Line Morphological Snubber** *(only intervenes when GPA's output word is invalid in `TurkishMorphology` while `asciiTolerant` has $\ge 1$ valid word, snapping to the closest valid surface and leaving true OOVs/typos untouched)*:
  - Jumps immediately to **`99.12%` word accuracy (`276` errors — `-40.4%` error reduction!)** and **`89.76%` sentence accuracy (`+6.0%`)** with **zero model bytes added**!

### 7.3 Five Concrete Ways to Extend GPA Itself ("GPA++")
1. **Fix Context Extraction Bugs (Right-Word Window, `'`, `?`, & Numeral Vowel Classes):**
   - [`turkishGetContext`](normalization/src/main/java/zemberek/normalization/deasciifier/Deasciifier.java#L158-L168) currently maps **apostrophes (`'`), question marks (`?`), hyphens (`-`), and digits (`0-9`) to `' '` AND terminates right-hand scanning on the first non-letter** — making GPA blind to `'nde` in `"Kulubu'nde"`, blind to `'?'` in `"mudur?"`, and blind to `"unutma"` in `"sakin unutma"`.
   - Keeping `'`, `?`, `-`, right-hand words across spaces, and mapping final digits to their Turkish vowel-harmony symbol (`3 → Ü`, `6 → I`, `9 → u`, `10 → o` so `"3'uncu"` looks like `"Ü'uncu"`!) fixes apostrophes, numerals, and short 2-word collocations natively inside GPA!
2. **Add Circumflex Decision Table (`a → â`, plus `î`/`û`) & Enforce Monotonicity:**
   - Inducing a 7th pattern table for `'a'` eliminates the `210` circumflex omissions (`tezgâh`, `güzergâh`, `zekâ`, `hâlâ`, `kâr`).
3. **`X`-Centered Outward Double-Array Trie (`5–10x` Faster, `6x` Denser):**
   - Every GPA pattern is anchored at `'X'`. Storing patterns in a flat `int[]` trie expanding outward from `'X'` (`X → right chars → '#' → left chars`) scans all matching patterns in **$O(W)$ (~20 array reads, zero `String` allocations)** instead of $O(W^2) \approx 110$ substring allocations, fitting **`80,000` GB-mined rules in `~380 KB`**.
4. **Vowel-Skeleton Wildcard Patterns (`Skip-Consonant` Harmony Rules):**
   - Adding a parallel compressed vowel-skeleton lookup (e.g., `[last_2_vowels + consonant_count + X + right_suffix]`) lets ~20 rules capture general Turkish palatal/labial harmony across *unseen* consonant clusters, OOV loanwords, and typos (`arkdaslar → arkdaşlar`).
5. **"Soft-GPA" (Replacing Winner-Take-All Ranks with `int8` Additive Perceptron Weights):**
   - Keeping the **exact same `X`-centered Double-Array Trie** (`~400 KB`) and replacing integer `rank` with **additive `int8` Margin-Perceptron weights** strictly generalizes GPA: multiple stem + harmony + right-word cues sum together, **and `|sum|` provides a free calibrated confidence margin** on every character!

### 7.4 Augmenting Soft-GPA with a Small ML Helper (`~0.9–1.5 MB` Total!)

```mermaid
flowchart LR
    A["Input char[]"] --> B["Step 1: Soft-GPA Outward Trie (~400 KB)"]
    B -->|" High |margin| & valid (~85% words) "| E["Fast-Path Accept (>100k sent/s)"]
    B -->|" Invalid in Morph (~0.6% words) "| C["Step 2: Morph Snubber (0 KB; keeps OOV/Typos if 0 parses)"]
    B -->|" Low |margin| or Homograph Stem (~15% words) "| D["Step 3: Confusion Micro-Perceptron (~500 KB - 1 MB)"]
    C --> D
    D --> E
```

This **Soft-GPA + Morph Snubber + Confusion Micro-Perceptron** architecture gives us a compelling alternative (or unification!) with the full Lattice Decoder:
- **Ultra-Small (`~0.9–1.5 MB` total)** and **Ultra-Fast (`~80k–120k sent/s`)**: `85%` of words finish in Step 1's flat `int[]` trie without ever invoking `asciiTolerantMorphology` or `WordGenerator`!
- **Works Both Standalone AND With `TurkishMorphology`**: Without `TurkishMorphology`, Step 1 + Step 3 still run in `< 1.5 MB` with zero morphology dependency (`~99.3%+` word acc) and handle all OOVs/typos natively; with `TurkishMorphology` attached, Step 2 eliminates 100% of gibberish words and supplies lemma/POS features to Step 3 to reach **`~99.6%+` word accuracy**!
- **Surgical ML Helper (`Step 3`, `~500 KB – 1.0 MB`):** Because Step 1 already deasciified the entire sentence in Pass 1, Step 3 **only wakes up on uncertain / homograph tokens (`~15%` of words)** and scores the `2–3` competing words (`sakin/sakın`, `mudur/müdür`, `karlılığı/kârlılığı`, `asmam/aşmam`, `mandalina/mandalına`) using already-deasciified bidirectional context words/stems `w_{-5..+5}`, suffix/POS shapes, `'?'`, and Soft-GPA's character score!

---

## 8. Experimental Prototype Implementation, Large-Scale Benchmarks & Insights

Both architectures were prototyped end-to-end inside `experiment/` under clean package discipline, trained on a zero-leakage multi-domain Turkish corpus, serialized to compact binary models, and benchmarked head-to-head against Zemberek's current [`Deasciifier.java`](normalization/src/main/java/zemberek/normalization/deasciifier/Deasciifier.java) across **>17,000 held-out sentences (`>230,000` words)**.

### 8.1 Prototype Package & Class Architecture (`experiment/`)

| Package / Class | Role & Key Implementation Details |
| :--- | :--- |
| **Core Infrastructure (`core/`)** | |
| [`TurkishDeasciifier.java`](experiment/src/main/java/zemberek/normalization/deasciifier/core/TurkishDeasciifier.java) | Unified `String` and zero-copy `deasciify(char[], offset, length)` contract enforcing `1:1` length invariance |
| [`LegacyGpaDeasciifier.java`](experiment/src/main/java/zemberek/normalization/deasciifier/core/LegacyGpaDeasciifier.java) | Baseline adapter wrapping Zemberek's existing 2006 GPA [`Deasciifier.java`](normalization/src/main/java/zemberek/normalization/deasciifier/Deasciifier.java) |
| [`FeatureHash.java`](experiment/src/main/java/zemberek/normalization/deasciifier/core/FeatureHash.java) | Zero-allocation 64-bit `SplitMix64` feature combinators + 16-bit collision fingerprints |
| [`CompactQuantizedWeights.java`](experiment/src/main/java/zemberek/normalization/deasciifier/core/CompactQuantizedWeights.java) | Primitive [`MultiLevelMphf`](core/src/main/java/zemberek/core/hash/MultiLevelMphf.java) + `short[]` fingerprints + `byte[]` codes + 256-entry signed 1D Lloyd-Max `float[]` codebook (`~3.42 B/feature`) + lazy `AveragedWeightBuffer` |
| [`DeasciifierTextUtils.java`](experiment/src/main/java/zemberek/normalization/deasciifier/core/DeasciifierTextUtils.java) | Exact offset tokenizer, immutable span shield (URLs, emails, `@handles`, paths, `camelCase`, Roman numerals `II..XXXIX`), 4-way Turkish `'I'/'i'` case projection, pinned diacritic mask, elongation squeeze/projection, and deterministic spoken Turkish numeral + apostrophe suffix restorer |
| [`CompactLexiconTables.java`](experiment/src/main/java/zemberek/normalization/deasciifier/core/CompactLexiconTables.java) | Compact binary foreign ASCII-keep shield (`BIOS`, `RAM`, `ISO`, `Windows`, `IKEA`), ALL-CAPS Turkish acronym table (`TÜİK`, `TÜBİTAK`, `ÖSYM`, `İETT`, `İSKİ`, `RTÜK`), taboo guard, and empirical corpus confusion priors |
| [`MorphologicalCandidateProvider.java`](experiment/src/main/java/zemberek/normalization/deasciifier/core/MorphologicalCandidateProvider.java) | Formal + informal `TurkishMorphology` candidate generator & validity snubber (`16k`-entry L1 cache), fixing Zemberek's apostrophe diacritic bug, projecting `DictionaryItem` circumflex lemmas, harmonizing foreign/acronym apostrophe stems via 24 Turkish prototype nouns, and handling glued clitics |
| **Approach B (`gpa/`)** | |
| [`OutwardContextTrie.java`](experiment/src/main/java/zemberek/normalization/deasciifier/gpa/OutwardContextTrie.java) | Flat CSR `int[]` `X`-centered outward context trie (24-bit edge targets, `int8` codebook weights, crossing spaces/punctuation/numerals) + 7 skip-consonant vowel-skeleton harmony features |
| [`SoftGpaHybridDeasciifier.java`](experiment/src/main/java/zemberek/normalization/deasciifier/gpa/SoftGpaHybridDeasciifier.java) | Dual-mode runtime supporting both `standalone(model)` (zero morphology dependency) and `withMorphology(model, morphProvider)` (0-KB Morphological Snubber + Confusion Micro-Perceptron) |
| [`SoftGpaTrainer.java`](experiment/src/main/java/zemberek/normalization/deasciifier/gpa/SoftGpaTrainer.java) | Lossless 64-bit bit-packed online Margin-Averaged Perceptron trainer (`59-bit` outward pattern keys) with synthetic typo/elongation and stochastic partial-ASCII augmentation |
| **Approach A (`lattice/`)** | |
| [`CharHarmonyViterbiModel.java`](experiment/src/main/java/zemberek/normalization/deasciifier/lattice/CharHarmonyViterbiModel.java) | 5-vowel-harmony-state character-level structured Viterbi Averaged Perceptron trained with 100% Lexicon Dropout + synthetic typos |
| [`LatticePerceptronDeasciifier.java`](experiment/src/main/java/zemberek/normalization/deasciifier/lattice/LatticePerceptronDeasciifier.java) | Candidate lattice builder (with Lexicon-Hijacking Guard) + Pass-1 `Beam=8` 2nd-order Viterbi + Pass-2 bidirectional `±5`-word lemma collocation refinement |
| [`LatticePerceptronTrainer.java`](experiment/src/main/java/zemberek/normalization/deasciifier/lattice/LatticePerceptronTrainer.java) | Streaming loss-augmented Margin-Averaged Perceptron trainer over sentence lattices |
| **Evaluation & Tests (`eval/`, `test/`)** | |
| [`CleanCorpusStreamer.java`](experiment/src/main/java/zemberek/normalization/deasciifier/eval/CleanCorpusStreamer.java) | Zero-leakage hash-filtered multi-domain corpus streamer, OOV-preserving sentence hygiene filter, and `CompactLexiconTables` miner |
| [`CuratedStressSuites.java`](experiment/src/main/java/zemberek/normalization/deasciifier/eval/CuratedStressSuites.java) | Contrastive minimal-pair homograph suite (`60` sents), OOV/Typo/Informal/Numeral suite (`20` sents), and Foreign/Acronym/Span suite (`16` sents) |
| [`DeasciifierBenchmarkRunner.java`](experiment/src/main/java/zemberek/normalization/deasciifier/eval/DeasciifierBenchmarkRunner.java) | Reproducible CLI training, serializing, reloading, and benchmarking all 4 systems across 7 suites |
| [`DeasciifierPrototypesTest.java`](experiment/src/test/java/zemberek/normalization/deasciifier/DeasciifierPrototypesTest.java) | JUnit verification of `1:1` length invariance, non-destructive partial-ASCII, numerals, elongations, foreign spans, and serialization round-trips |

---

### 8.2 Training Setup, Serialized Footprint & Throughput

- **Zero-Leakage Multi-Domain Corpus:** Before streaming `~/data/turkish/clean/`, [`CleanCorpusStreamer`](experiment/src/main/java/zemberek/normalization/deasciifier/eval/CleanCorpusStreamer.java) registered **`30,123` canonical & exact hashes** covering every sentence in `gemini_turkish_sentences_2500.txt`, all 31 files in `refined_test/` (`12,472` unique sentences), and all stress suites. It then streamed **`800,000` verified-clean sentences** stratified across **18 Turkish domains** (news, Wikipedia, parliament, legal, science, defense, tech/civil forums, lifestyle, subtitles) for lexicon/confusion mining (`11,516` confusion entries + `262` verified foreign ASCII-keep words) and trained both models' Margin-Averaged Perceptrons on **`450,000` sentences** (`~5.8M` tokens/epoch) with `14–15%` synthetic typo/elongation injection and stochastic partial-asciification.

| System | Binary Model Size | Model RAM | Requires `TurkishMorphology`? | Internal Components | Steady-State Throughput (`1` Core) |
| :--- | :---: | :---: | :---: | :--- | :---: |
| **Baseline (`Deasciifier.java`, 2006 GPA)** | `236.5 KB` (`0.231 MB`) | `~1.1 MB` *(HashMap)* | No | `13,462` string rules (`ObjectInputStream`) | `189k – 202k tok/s` |
| **Approach B — Standalone (`SoftGpaHybrid`)** | **`1,487.0 KB` (`1.452 MB`)** | **`1.44 MB`** | **No (100% Standalone)** | `83,027`-node CSR Outward Trie + `18k` Harmony + `11.5k` Confusions + `180k` Micro-ML | **`228k – 260k tok/s`** *(faster than baseline!)* |
| **Approach B — Morph-Boosted (`SoftGpaHybrid`)** | **`1,487.0 KB` (`1.452 MB`)** | **`1.44 MB`** *(+ shared Morph)* | Yes | Same `1.45 MB` file + `0 KB` Morph Snubber + Morphosyntactic Micro-ML | **`45k` (cold) → `200k tok/s` (warm)** |
| **Approach A (`LatticePerceptronDeasciifier`)** | **`2,234.1 KB` (`2.182 MB`)** | **`1.85 MB`** *(+ shared Morph)* | Yes | `110k` 5-State Harmony Char-Viterbi + `460k` Sentence Lattice Perceptron (`Beam=8` + Pass-2) | **`28k` (cold) → `148k tok/s` (warm)** |

> [!TIP]
> **Size Target Exceeded by `3x–4.5x`:** Both trained models (**`1.45 MB`** for Approach B and **`2.18 MB`** for Approach A) sit far below the `< 6–7 MB` budget while packing up to `570,000` discriminative features + `11,516` confusion priors via `CompactQuantizedWeights` (`3.42 B/feature`) and 24-bit CSR trie edges.

---

### 8.3 Head-to-Head Benchmark Results Across 7 Evaluation Suites

Metrics report both **Circumflex-Agnostic (`No^`)** and **Strict Circumflex-Exact (`Strict`, requiring exact `â/î/û`)** Exact Sentence Accuracy and Word Accuracy, plus **Diacritic-Word Accuracy (`DiacWord`)** and **Stripped Existing Turkish Characters (`Stripped`)**:

#### A. Large Held-Out Corpora (`16,692` Sentences, `~193,000` Words)

| Benchmark Suite | System | Exact Sent (`No^`) | Exact Sent (`Strict`) | Sent Error Reduction (`No^`) | Word Acc (`No^`) | Word Acc (`Strict`) | Diacritic Word Acc |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **`Gemini-2500`** *(2,500 rich diverse sentences, `31,382` words, `15,661` diacritic words)* | Baseline 2006 GPA | `83.76%` | `77.16%` | — | `98.52%` | `97.85%` | `98.07%` |
| | Approach B *(Standalone, `1.45 MB`)* | `88.08%` | `84.20%` | `-26.6%` | `98.93%` | `98.53%` | `98.42%` |
| | **Approach B *(Morph + Micro-ML, `1.45 MB`)*** | **`94.88%`** | `90.24%` | **`-68.5%`** | **`99.57%`** | `99.15%` | **`99.43%`** |
| | **Approach A *(Lattice Perceptron, `2.18 MB`)*** | `94.84%` | **`90.40%`** | `-68.2%` | `99.56%` | **`99.16%`** | `99.32%` |
| **`Refined-Test Verified-Clean`** *(11,720 multi-domain web/news/book/forum sentences, `146,152` words)* | Baseline 2006 GPA | `89.07%` | `87.27%` | — | `99.00%` | `98.81%` | `98.70%` |
| | Approach B *(Standalone, `1.45 MB`)* | `93.88%` | `91.04%` | `-44.0%` | `99.47%` | `99.20%` | `99.24%` |
| | **Approach B *(Morph + Micro-ML, `1.45 MB`)*** | `96.47%` | `93.52%` | `-67.7%` | `99.70%` | `99.43%` | `99.54%` |
| | **Approach A *(Lattice Perceptron, `2.18 MB`)*** | **`96.66%`** | **`93.68%`** | **`-69.4%`** | **`99.71%`** | **`99.45%`** | **`99.57%`** |
| **`Refined-Test Raw-All`** *(12,472 unfiltered web/forum/news sentences)* | Baseline 2006 GPA | `89.18%` | `87.48%` | — | `98.99%` | `98.81%` | `98.69%` |
| | Approach B *(Standalone, `1.45 MB`)* | `94.01%` | `91.29%` | `-44.6%` | `99.47%` | `99.21%` | `99.24%` |
| | **Approach B *(Morph + Micro-ML, `1.45 MB`)*** | `96.52%` | `93.69%` | `-67.8%` | `99.70%` | `99.44%` | `99.54%` |
| | **Approach A *(Lattice Perceptron, `2.18 MB`)*** | **`96.69%`** | **`93.83%`** | **`-69.4%`** | **`99.71%`** | **`99.45%`** | **`99.57%`** |

#### B. Per-Register Breakdown on `Refined-Test` (`11,720` Sentences Across 5 Registers, Including 4 Unseen Domains)

| Register / Domain Group | Sentences (`Words`) | Baseline Sent (`No^`) | Approach B Standalone | Approach B Morph+MicroML | Approach A Lattice+Perceptron | Winner & Margin Over Baseline |
| :--- | :---: | :---: | :---: | :---: | :---: | :--- |
| **News & Economy** (`cnn-turk`, `dunya`, `radikal`, `ntv`, `haberturk`, `iha`, ...) | `5,210` (`68,192`) | `87.91%` | `93.90%` | `96.10%` | **`96.47%`** (`99.72%` word) | **Approach A (`+8.56%` sent, `-70.8%` errors)** |
| **Science, Wiki & Defense** (`vikipedi`, `bilim.org`, `defenceturk`) | `1,251` (`16,772`) | `88.97%` | `91.93%` | `96.16%` | **`96.32%`** (`99.70%` word) | **Approach A (`+7.35%` sent, `-66.6%` errors)** |
| **Legal & Parliament** (`hukuki-net`, `yargitay-karar`, `tbmm`) | `3,098` (`40,985`) | `90.77%` | `94.71%` | `97.13%` | **`97.19%`** (`99.78%` word) | **Approach A (`+6.42%` sent, `-69.6%` errors)** |
| **Subtitles / Spoken Dialogue** (`open-subtitles`, `subtitle-tr`) | `440` (`3,235`) | `90.00%` | `94.32%` | `95.68%` | **`95.91%`** (`99.41%` word) | **Approach A (`+5.91%` sent, `-59.1%` errors)** |
| **Informal Forums & Social Q&A** *(`shiftdelete`, `memurlar`, plus unseen `kizlarsoruyor`, `sikayetim`, `tvyorumlari`, `nurturia`)* | `1,721` (`16,968`) | `89.37%` | `93.67%` | **`96.80%`** (`99.63%` word) | `96.69%` (`99.61%` word) | **Approach B (`+7.43%` sent, `-69.9%` errors)** |

#### C. Targeted Diagnostic & Adversarial Stress Suites

| Stress Suite | Baseline 2006 GPA | Approach B *(Standalone)* | Approach B *(Morph + Micro-ML)* | Approach A *(Lattice Perceptron)* | Key Phenomenon Verified |
| :--- | :---: | :---: | :---: | :---: | :--- |
| **Suite-2: Hard Homograph Minimal Pairs** *(60 contrasting sentences, 30 pairs)* | `56.67%` sent (`93.93%` word) | `73.33%` sent (`95.61%` word) | `75.00%` sent (`96.65%` word) | **`78.33%` sent (`97.28%` word)** | Approach A's lemma bigrams + `Beam=8` Viterbi resolve `Karadeniz turu`/`bellek türü`, `ipe asmam`/`engeli aşmam`, `derin göl`/`gol`, `sakın söyleme!`, `mevcut mudur?` |
| **Suite-3: OOV, Informal, Typos, Elongations & Numerals** *(20 sentences)* | `60.00%` sent (`91.57%` diac) | **`100.00%` (`20/20`)** | **`100.00%` (`20/20`)** | `95.00%` (`19/20`, `99.32%` word) | `kübitlerinin`, `tensörlere`, `gidicem`, `görüşecez`, `arkdaşlar`, `öğretmnler`, `şarzım`, `çooooook`, `3'üncü`, `6'ncı`, `1923'ün`, `ISO 12800'e`, `00.00'dan` |
| **Suite-4: Partial-ASCII Non-Destructive** *(500 half-diacritized sentences)* | `80.60%` sent (**`38` stripped!**) | `90.20%` sent (**`0` stripped**) | `96.20%` sent (**`0` stripped**) | **`97.00%` sent (`99.76%` word, `0` stripped)** | Legacy GPA actively corrupts `38` valid Turkish chars; both prototypes preserve `100%` of existing diacritics |
| **Suite-5: Foreign, Acronyms & Immutable Spans** *(16 sentences)* | `43.75%` sent (`92.31%` word) | `93.75%` sent (`99.15%` word) | **`100.00%` (`16/16` `No^`)** | **`100.00%` (`16/16` `No^`)** | Baseline corrupts `BIOS→BİOS`, `TUIK→TUİK`, `II→Iİ`, URLs; prototypes preserve `BIOS`, `RAM`, `IKEA`, `II.`, URLs, `@handles` & restore `TÜİK`, `TÜBİTAK`, `ÖSYM`, `İSKİ` |

---

### 8.4 Six Key Insights & Zemberek Findings Uncovered During Prototyping

1. **Two Latent Bugs in `TurkishMorphology` Fixed Cleanly in [`MorphologicalCandidateProvider`](experiment/src/main/java/zemberek/normalization/deasciifier/core/MorphologicalCandidateProvider.java):**
   - **Apostrophe Exact-Stem Bug ([`TurkishMorphology.java`](morphology/src/main/java/zemberek/morphology/TurkishMorphology.java#L230)):** `analyzeWordsWithApostrophe` checks `a.getStem().equals(stem)` with exact string equality even when `ignoreDiacriticsInAnalysis` is enabled — causing `asciiInformalMorphology.analyze("turkiye'nin")` and `"cukurova'daki"` to return **zero analyses** (`"türkiye" != "turkiye"`). Comparing stem lengths + `equalsIgnoreDiacritics` and regenerating suffixes restored 100% recall on apostrophe-inflected Turkish proper nouns.
   - **Circumflex Root Stripping ([`TurkishDictionaryLoader.java`](morphology/src/main/java/zemberek/morphology/lexicon/tr/TurkishDictionaryLoader.java#L387)):** Zemberek strips circumflexes when building `DictionaryItem.root` (`tezgâh → tezgah`, `kâr → kar`, `hâlâ → hala`), so `WordGenerator.generate` never emits circumflexed stems. Projecting `DictionaryItem.normalizedLemma()` circumflexes onto generated stems recovered circumflex candidates across the entire lexicon at **`0 KB`** dictionary cost, lifting strict sentence accuracy on `Gemini-2500` from `77.16%` to **`90.40%` (`+13.24%`)**.
2. **Preceding-Word Last-Vowel Harmony Solves Separate Question Clitics Across Unseen Inflections:**
   - Diagnostic error inspection caught `Çayı tazeledim, sana da koyayım mı?` failing as `koyayım mi?` because `"koyayım"` is a rare 1st-person optative inflection never observed before `"mı"` in training, AND in Pass 1 raw ASCII `"koyayim"` hides whether the final vowel is `'ı'` or `'i'`.
   - Adding a 4-way `lastTurkishVowelHarmony(prev1)` feature (`{a/ı, e/i, o/u, ö/ü}`) on Pass-1-restored `prev1` in both [`SoftGpaHybridDeasciifier`](experiment/src/main/java/zemberek/normalization/deasciifier/gpa/SoftGpaHybridDeasciifier.java) and [`LatticePerceptronDeasciifier`](experiment/src/main/java/zemberek/normalization/deasciifier/lattice/LatticePerceptronDeasciifier.java) immediately generalized `mı/mi/mu/mü` across **all** Turkish verb/noun inflections with 4 parameters.
3. **Lexicon False-Friend & Hijacking Traps (`İğdir` vs `Iğdır`, `kutur` vs `kütür`, `kübitlerinin`):**
   - Zemberek's `locations-tr.dict` lists obscure villages (`İğdir`) alongside major provinces (`Iğdır`), and `master-dictionary.dict` tags reduplicatives like `kütür` as `PrimaryPos.Duplicator` (`[P:Dup]`) alongside archaic nouns (`kutur` = diameter).
   - Furthermore, in `SoftGpaHybridDeasciifier.withMorphology`, failing to merge `CompactLexiconTables` corpus-verified unlisted loanwords (`kübitlerinin`, `tensörlere`, `süperpozisyonu`) into morphological candidates initially dropped Suite-3 from `100%` to `95%`. Merging corpus confusion surfaces + adding the `Duplicator` reduplication cue (`w_i == w_{i±1}`) + the Lexicon-Hijacking Guard brought `withMorphology` to **`100.00%`** on Suite-3 while jumping `Gemini-2500` from `93.88%` to **`94.88%`**.
4. **Why Approach A Wins on Formal/Homograph Text While Approach B Wins on Noisy/Typo Text:**
   - On `Suite-2` (Hard Homographs) and formal registers (`News`, `Science`, `Legal`), **Approach A (`LatticePerceptronDeasciifier`)** leads (`78.33%` vs `75.00%` on `Suite-2`; `96.66%` vs `96.47%` on `Refined-Test`) because `DictionaryItem` lemmas unify inflected collocations (`ipe/ipten/ipi + asmak` vs `engeli/engelleri + aşmak`, `bellek + tür+P3sg`) and `Beam=8` Viterbi decodes multi-word ambiguous chains jointly.
   - Conversely, on `Suite-3`'s `"yanlız başına yaşamaya"` (where `"yalnız"` is misspelled as `"yanlız"`), Approach A's single error (`basına` instead of `başına`) illustrates a classic structured-pipeline sensitivity: when a neighbor has a stem typo (`lemma = "yanlız"` OOV instead of `"yalnız"`), lemma-bigram features `(yalnız, baş)` miss and news-heavy corpus priors (`basına` = "to the press") can dominate. Approach B's character-anchored `Soft-GPA` score (`0.22 * gpaScore`) acts as a smooth local anchor that resists neighbor-typo perturbations (`100.00%` on Suite-3, `96.80%` vs `96.69%` on `Informal Forums`).
5. **Soft-GPA Standalone (`1.45 MB`, Zero Morphology) Beats Legacy GPA Across Every Metric Including Speed:**
   - Replacing `ObjectInputStream` substring scanning (`O(W^2)` string allocations) with the flat CSR `int[]` `OutwardContextTrie` (`O(W)` primitive array reads) made `SoftGpaHybridDeasciifier.standalone` **faster than Legacy GPA (`228k–260k tok/s` vs `121k–202k tok/s`)** while cutting `Refined-Test` sentence errors by **`44.0%` (`89.07% → 93.88%`)** without loading `TurkishMorphology` at all.
6. **Residual Error Ceiling (`~0.29%` Word Error Rate on `Refined-Test`, `~0.43%` on `Gemini-2500`):**
   - Inspecting the remaining `~0.29%` word errors shows almost zero invalid-word gibberish; nearly all remaining errors are **long-range open-world semantic homographs** (`keskin bir sızı` vs pronoun `sizi`, `toz tutan halı` vs `hali`, `azı dişinizin` vs `dışınızın`, `ince sac levhalar` vs `saç`) where both candidates are syntactically valid nouns in the exact same slot and disambiguation requires wide domain/world knowledge or a larger (`4–5 MB`) collocation table.

---

### 8.5 Final Architectural Recommendation for Zemberek Production Integration

Following the v2 audits of both prototypes (Sections 9 and 10), both architectures exceed every accuracy, speed, OOV/typo, and `< 6–7 MB` model-size goal at **near-`1 MB` footprints**, giving Zemberek two complementary production-ready options:

1. **Dual-Mode Sub-Megabyte Drop-In (`SoftGpaHybridDeasciifier` v2, `937 KB` / `0.915 MB`):**
   - Ships a **single `937 KB` resource** supporting **both** `SoftGpaHybridDeasciifier.standalone()` (`215k–315k tok/s`, **zero `TurkishMorphology` dependency** for lightweight/CLI/embedded callers) **and** `SoftGpaHybridDeasciifier.withMorphology(morphology)` (**`95.32%` `No^` / `90.76%` `Strict` on `Gemini-2500`**, **`96.70%` `No^` / `94.11%` `Strict` on `Refined-Test`**, **`80.00%` on `Suite-2` Hard Homographs**, `211.3k tok/s` warm throughput, `100%` OOV/typo/foreign pass rate).
2. **Fastest & Highest Multi-Domain Lattice Decoder (`LatticePerceptronDeasciifier` v2, `1,251 KB` / `1.221 MB`, or `1,118 KB` compact):**
   - Pure `Lattice + Perceptron` architecture (`plainLowerCache` + diff-only [`CharHarmonyViterbiModel`](experiment/src/main/java/zemberek/normalization/deasciifier/lattice/CharHarmonyViterbiModel.java) + zero-allocation `Beam = 4` Viterbi + Backward-Forward content-collocation refinement) when `TurkishMorphology` is already loaded. Achieves the **highest accuracy on `Refined-Test` (`96.84%` `No^` / `94.20%` `Strict`, `99.73%` word accuracy)**, **`97.80%` on `Suite-4` Partial-ASCII**, **`100.00%` on `Suite-3` & `Suite-5`**, and the **highest warm throughput of all systems (`300,422 tok/s` — `2.2x` faster than 2006 Legacy GPA and `1.42x` faster than Approach B v2 warm)**.

---

## 9. Deep Audit & v2 Optimization of Approach B (`Morph + Micro-ML`)

Following the initial head-to-head benchmark, we conducted a line-by-line audit of **Approach B** across [`SoftGpaHybridDeasciifier`](experiment/src/main/java/zemberek/normalization/deasciifier/gpa/SoftGpaHybridDeasciifier.java), [`OutwardContextTrie`](experiment/src/main/java/zemberek/normalization/deasciifier/gpa/OutwardContextTrie.java), [`CompactLexiconTables`](experiment/src/main/java/zemberek/normalization/deasciifier/core/CompactLexiconTables.java), [`MorphologicalCandidateProvider`](experiment/src/main/java/zemberek/normalization/deasciifier/core/MorphologicalCandidateProvider.java), [`CleanCorpusStreamer`](experiment/src/main/java/zemberek/normalization/deasciifier/eval/CleanCorpusStreamer.java), and [`SoftGpaTrainer`](experiment/src/main/java/zemberek/normalization/deasciifier/gpa/SoftGpaTrainer.java). We uncovered **9 concrete accuracy blind spots, runtime bottlenecks, and serialization redundancies**, fixed all of them with full `V1` backward compatibility, retrained on `450,000` sentences, and re-benchmarked across all 7 suites.

### 9.1 What Was Missed in v1 (Audit Findings & Targeted Fixes)

#### A. Accuracy Blind Spots & Subtle Bugs Fixed
1. **True Neighbor Lemmas, Grammatical Case & POS Missing from Micro-ML (`SoftGpaHybridDeasciifier`):**
   - *v1 gap:* `extractConfusionFeatures` only hashed `pseudoStem(prev1)` (5-char prefix truncation) instead of Zemberek's true morphological lemma, grammatical case (`Dat/Loc/Abl/Acc/Ins/Gen`), and POS — which was the exact reason Approach A beat Approach B v1 on `Suite-2` (`ipe asmam` vs `engeli aşmam`, `bellek türü` vs `Karadeniz turu`).
   - *v2 fix:* Added lightweight cached `FastMorphInfo` (`lemmaHash`, `caseHash`, `posOrdinal`) in [`MorphologicalCandidateProvider`](experiment/src/main/java/zemberek/normalization/deasciifier/core/MorphologicalCandidateProvider.java) plus features `319` (mid-sentence `TitleCase` proper noun before `İli/İlçesi`), `326–331` (`prev1`/`next1` true lemma, grammatical case, and next POS), and a fast **2nd coordinate sweep** over triggered ambiguous tokens.
2. **Inflected Root-Homograph & Stem+Suffix Collision Blind Spot in Step 2:**
   - *v1 gap:* When `CompactLexiconTables` missed a low-frequency inflected surface (`öldüğü` vs `olduğu`, `doğmaların` vs `dogmaların`, `üzüyordu` vs `uzuyordu`, `takıp` vs `takip`) AND Soft-GPA's draft was both valid (`isValidTurkishWord == true`) and confident (`minMargin >= 1.35f`), Step 3 never woke up!
   - *v2 fix:* Built a **`0 KB` startup `ambiguousAsciiRoots` index** scanning `RootLexicon` for both direct root homographs (`ol/öl`, `uz/üz`, `on/ön`, `koy/köy`) and stem+suffix boundary prefix collisions (`doğ+ma`/`dogma`, `tak+ıp`/`takip`, `mandal+ına`/`mandalina`). Step 2's unified `inspectDraftWord` now returns `STATUS_VALID_AMBIGUOUS`, waking Step 3 without adding a single byte to the model file.
3. **`TARGET_A_CIRCUMFLEX` Polluting `minMargin` on `~75%` of Turkish Words:**
   - *v1 bug:* In [`SoftGpaHybridDeasciifier.runSoftGpaOnToken`](experiment/src/main/java/zemberek/normalization/deasciifier/gpa/SoftGpaHybridDeasciifier.java), every word containing `'a'` computed `margin = Math.abs(cSc)` where `cSc` was typically `-0.6f`, artificially dragging `minMargin` below `1.35f` on `~75%` of completely unambiguous words (wasting `WordGenerator` calls and triggering noisy Step-3 evaluations).
   - *v2 fix:* Restricted `minMargin` updates on `'a'` strictly to genuinely close circumflex decisions (`cSc > -0.10f`) and added `OutwardContextTrie.canTakeTurkishACircumflex` (orthographic `k/g/l` adjacency or word-initial `â` gate).
4. **Corpus Confusion Pruning Deleting Legitimate Minority Homographs (`CleanCorpusStreamer`):**
   - *v1 bug:* `CleanCorpusStreamer.mineLexiconTables` pruned any surface with `< 2.5%` empirical share inside an ASCII bucket — silently deleting valid Turkish words overshadowed by ultra-frequent twins (`olduğu` `98.8%` deleted `öldüğü` `1.2%`; `ise` `98%` deleted `işe` `1.9%`; `sizi` deleted `sızı`; `üç` deleted `uç`; `hali` deleted `halı`)! When `CompactLexiconTables` returned `0.0` unseen prior for those words, valid words suffered an artificial `-3.5` log-probability penalty.
   - *v2 fix:* Lowered `minShare` to `0.00025` (`0.025%`) for morphologically valid words while keeping `2.5%` strict noise filtering for unparseable strings, and added a compact **`47.5 KB` `lemmaLogCounts` table** (`14,000` lemmas with circumflex-normalized fallback) so unseen inflections inherit smooth lemma priors.
5. **Biased Label-Conditioned Circumflex Gate (`SoftGpaTrainer`) & Acronym Sign Bug (`MİT`, `ÖTV`, `Altınkapı'nın`):**
   - *v1 bugs:* (a) `SoftGpaTrainer` skipped `'a'` training whenever `!sentenceHasCirc` AND downsampled negative `'a'` examples by `8x`, causing false-positive circumflexes (`kağıda → kâğıdâ`); (b) `OTV` (`ÖTV`) and `MIT` (`MİT`) were mislisted in `SEED_ALLCAPS_ONLY_FOREIGN` instead of `SEED_ALLCAPS_TURKISH_ACRONYMS`, and `runSoftGpaOnToken` assigned `+8.0f` instead of `-8.0f` to `'i'` in ALL-CAPS acronyms (turning `MİT → MIT`); (c) unlisted proper nouns with apostrophes (`Altinkapi'nin`) left the stem raw ASCII (`Altinkapı'nın`).
   - *v2 fixes:* Replaced sentence-label gating with unbiased **domain-level circumflex gating** (`radikal`, `tbmm-tutanak`, `vikipedi`, `bilim.org`) + `1:1` natural priors; moved `ÖTV`/`MİT` to Turkish acronyms and fixed the `'i'` sign polarity; harmonized apostrophe suffixes directly onto Soft-GPA's restored stem for unlisted proper nouns (`Altinkapi'nin → Altınkapı'nın`) while preserving `camelCase` (`WhatsApp'tan`).

#### B. Lossless Model Compression (`1,487.0 KB → 937.0 KB`, `-37.0%`)
6. **Canonical BFS Implicit-Edge `OutwardContextTrie` (`548.2 KB → 278.0 KB`, `-49.3%`, 100% Lossless):**
   - Because `OutwardContextTrie` is a tree rooted at the 9 target subtrees (`0..8`), ordering internal nodes in **Breadth-First Search (BFS)** order guarantees that every edge `e` targets exact node `9 + e`! Eliminating `int[] edgeTargets` cut the trie from `548.2 KB` to **`277.96 KB`** (`-304 KB` RAM) with zero approximation loss and better CPU cache locality.
7. **Front-Coded Bitmask `CompactLexiconTables` (`340.6 KB → 96.4 KB`, `-71.7%`):**
   - Sorting ASCII confusion keys lexicographically with shared prefix lengths, encoding each candidate surface as a compact varint diacritic-flip bitmask over the ASCII key (instead of repeating UTF-8 strings), and quantizing `logProb` to `1 byte` (`0.0625` resolution) shrank `LexiconTables` by **`3.5x`** (`340.6 KB → 96.37 KB`) while *increasing* stored confusion entries from `11,516` to `12,517`.

#### C. Runtime Speedups (`3.03x` Faster Warm, `+41.5%` Faster Cold)
8. **3-State `draftStatusCache` (`65,536` Direct-Mapped Entries) + Single-Lookup `inspectDraftWord`:**
   - Replaced v1's per-call `isValidTurkishWord` morphology analysis with a lock-free `65,536`-slot direct-mapped status cache (`INVALID` / `VALID_SAFE` / `VALID_AMBIGUOUS`), skipping `WordGenerator` completely on `>86%` of tokens.
9. **Zero-Allocation `ThreadLocal<Scratch>` + Orthographic `'a'` Trie Gate:**
   - Eliminated per-sentence `SoftTokenResult[]`, `float[][]`, and `boolean[][]` heap allocations in [`SoftGpaHybridDeasciifier`](experiment/src/main/java/zemberek/normalization/deasciifier/gpa/SoftGpaHybridDeasciifier.java) and skipped `>78%` of `'a'` trie walks via `canTakeTurkishACircumflex`.

---

### 9.2 Verified Benchmark Comparison: Approach B v1 (`1.45 MB`) vs Approach B v2 (`0.915 MB`) vs Approach A v1 (`2.18 MB`)

#### A. Model Size, Memory & Throughput (`Gemini-2500`, `31,382` Words, Single Thread)

| Metric | Approach B v1 (`Morph+MicroML`) | **Approach B v2 (`Morph+MicroML`)** | Approach A v1 (`LatticePerceptron`) | B-v1 → B-v2 Improvement |
| :--- | :---: | :---: | :---: | :--- |
| **Total Binary Size** | `1,486.99 KB` (`1.452 MB`) | **`936.99 KB` (`0.915 MB`)** | `2,234.06 KB` (`2.182 MB`) | **`-37.0%` (`-550 KB`; `2.38x` smaller than A-v1)** |
| — `OutwardTrie` + Harmony | `548.20 KB` | **`277.96 KB`** | *(CharViterbi: `376 KB`)* | **`-49.3%`** *(lossless BFS implicit edges)* |
| — `LexiconTables` | `340.60 KB` (`11.5k` keys) | **`96.37 KB`** (`12.5k` keys) | `340.60 KB` | **`-71.7%`** *(front-coded + bitmask surfaces)* |
| — `HelperWeights` + `LemmaPriors` | `598.19 KB` (`180k` feats) | **`562.65 KB`** (`155k` + `14k` lemmas) | `1,517.5 KB` (`460k` feats) | **`-5.9%`** *(adds `14,000` lemma priors)* |
| **In-Memory Footprint (RAM)** | `1.44 MB` | **`1.04 MB`** | `1.85 MB` | **`-27.8%` RAM** |
| **Standalone Throughput** | `227k – 255k tok/s` | **`253k – 309k tok/s`** | N/A *(requires Morph)* | **`+15% to +21%` faster** |
| **`Morph+MicroML` Cold-Cache** | `1,962.1 ms` (`15,994 tok/s`) | **`1,386.5 ms` (`22,634 tok/s`)** | `~28,000 tok/s` | **`+41.5%` faster cold** |
| **`Morph+MicroML` Warm-Cache** | `542.2 ms` (`57,878 tok/s`) | **`178.7 ms` (`175,593 tok/s`)** | `84k – 171k tok/s` | **`3.03x` faster warm!** |

#### B. Accuracy Across All 7 Held-Out Benchmark Suites (Before Approach A v2 Audit)

| Benchmark Suite | Baseline 2006 GPA | Approach B v1 (`1.45 MB`) | **Approach B v2 (`0.915 MB`)** | Approach A v1 (`2.18 MB`) | Key Takeaway |
| :--- | :---: | :---: | :---: | :---: | :--- |
| **`Gemini-2500` — Sent (`No^` / `Strict`)** | `83.76%` / `77.16%` | `94.88%` / `90.24%` | **`95.32%` / `90.76%`** | `94.88%` / `90.44%` | **B-v2 beats BOTH B-v1 (`+0.44%`/`+0.52%`) and `2.18 MB` A-v1!** |
| **`Gemini-2500` — Word (`No^` / `Strict`)** | `98.52%` / `97.85%` | `99.57%` / `99.15%` | **`99.60%` / `99.18%`** | `99.56%` / `99.16%` | **`-73.0%` word error reduction** vs Baseline (`DiacWord = 99.43%`) |
| **`Refined-Test Clean` (`11,720`) — Sent (`No^` / `Strict`)** | `89.07%` / `87.27%` | `96.47%` / `93.52%` | **`96.70%` / `94.11%`** | **`96.80%`** / `93.82%` | **B-v2 gains `+0.59%` Strict, beating A-v1 on `Strict`!** |
| **`Refined-Test Clean` (`11,720`) — Word (`No^` / `Strict`)** | `99.00%` / `98.81%` | `99.70%` / `99.43%` | **`99.72%` / `99.49%`** | **`99.73%`** / `99.46%` | Tied `99.60%` `DiacWord`; B-v2 leads Strict word accuracy (`99.49%`) |
| **`Refined-Test Raw-All` (`12,472`) — Sent (`No^` / `Strict`)** | `89.18%` / `87.48%` | `96.52%` / `93.69%` | **`96.76%` / `94.28%`** | **`96.83%`** / `93.97%` | Tied `99.72%` `No^` word acc; **`+0.31%` higher Strict sent acc** than A-v1 |
| **`Suite-2`: Hard Homograph Minimal Pairs (`60`)** | `56.67%` / `50.00%` | `75.00%` / `70.00%` | **`80.00%` / `73.33%`** | `78.33%` / `71.67%` | **`+5.00%` jump — `0.915 MB` B-v2 surpasses `2.18 MB` A-v1!** |
| **`Suite-3`: OOV, Informal, Typos & Numerals (`20`)** | `60.00%` / `60.00%` | **`100.00%` / `100.00%`** | **`100.00%` / `100.00%`** | `95.00%` / `95.00%` | Perfect `20/20` (`0` errors on stems with neighbor typos) |
| **`Suite-4`: Partial-ASCII Non-Destructive (`500`)** | `80.60%` (`38` stripped) | `96.20%` / `94.00%` | **`96.80%` / `94.00%`** | **`97.00%` / `94.60%`** | `+0.60%` over B-v1 (`99.74%` word acc), **`0` stripped diacritics** |
| **`Suite-5`: Foreign, Acronyms & Spans (`16`)** | `43.75%` / `37.50%` | **`100.00%` / `93.75%`** | **`100.00%` / `93.75%`** | **`100.00%` / `93.75%`** | Also verified `ÖTV`, `MİT`, `WhatsApp'tan`, `Altınkapı'nın` in JUnit |

> [!IMPORTANT]
> **Summary of the Approach B v2 Breakthrough:** At **`937 KB` (`0.915 MB`)**, Approach B v2 (`SoftGpaHybridDeasciifier` in `Morph + Micro-ML` mode) is **`37%` smaller** and **`3.0x` faster (warm)** than B-v1, while surpassing `2.18 MB` Approach A v1 on `Gemini-2500`, `Suite-2`, `Suite-3`, and `Refined-Test` Strict accuracy.

---

## 10. Deep Audit & v2 Optimization of Approach A (`Lattice + Perceptron` Only) & Grand Comparison

Next, we audited **Approach A (`LatticePerceptronDeasciifier` + `CharHarmonyViterbiModel` + `LatticePerceptronTrainer`) strictly on its own pure `Lattice + Perceptron` terms** (without fusing Approach B's `OutwardContextTrie` into it) to make it **simpler, faster, smaller, and more accurate**.

### 10.1 What Was Needlessly Complex, Slow, Bloated, or Missing in Approach A v1

#### A. Architectural Simplifications & `3.58x` Speedup (`84k → 300.4k tok/s` Warm)
1. **Eliminating Redundant Full Character-Viterbi DP on In-Vocabulary Words (`>92%` Fewer Full Viterbi Runs):**
   - *v1 waste:* [`LatticePerceptronDeasciifier`](experiment/src/main/java/zemberek/normalization/deasciifier/lattice/LatticePerceptronDeasciifier.java) called [`CharHarmonyViterbiModel.decodeTopCandidates`](experiment/src/main/java/zemberek/normalization/deasciifier/lattice/CharHarmonyViterbiModel.java#L276-L426) (`float[len+1][5]` full Viterbi + top-4 perturbation rescoring) on **every ambiguous or confused word** (`~22%` of all tokens), only to check binary membership `charRank0` / `charRankTop3` and discard the continuous character score!
   - *v2 simplification:* Full `decodeTopCandidates` now runs **strictly on OOVs, typos, and suspected lexicon hijacks (`< 1.5%` of tokens)**. For ambiguous in-vocabulary words (`k >= 2`), we added [`CharHarmonyViterbiModel.scoreCandidateSurfaces`](experiment/src/main/java/zemberek/normalization/deasciifier/lattice/CharHarmonyViterbiModel.java#L161-L274), a zero-allocation **diff-only candidate scorer** that skips feature extraction and MPHF lookups at every character index where candidates share the same character and running vowel-harmony state (e.g., on `"havadisleri"` vs `"havadişleri"`, only index `6` (`s`/`ş`) is scored instead of all 11 positions!), feeding continuous relative log-odds `[-6.0f, 0.0f]` directly into `computeBaseUnaryScore`.
2. **Single-Lookup `plainLowerCache` (`65,536` Entries) Fixing Both Speed AND `e`-Vowel Morphological Blindness:**
   - *v1 bug & waste:* Tokens without `{c,g,i,o,s,u,a}` (such as `"evde"`, `"elden"`, `"ekmek"`, `"her"`, `"ve"`, `"ne"`) bypassed Zemberek via `MorphCandidate.oov`, giving them `PrimaryPos.Unknown` and `caseId = "Nom"`! Because `"evde"` lacked `Loc` and `"elden"` lacked `Abl`, case-governed transition features (`522`, `523`) failed on `e`-only contexts; meanwhile every other token allocated new `ArrayList`s, `float[]`s, and `LatticeToken` wrappers.
   - *v2 fix:* Replaced per-sentence `LatticeToken[]` allocations with a `65,536`-entry `plainLowerCache` ([`LatticePerceptronDeasciifier.populateSentenceLattice`](experiment/src/main/java/zemberek/normalization/deasciifier/lattice/LatticePerceptronDeasciifier.java#L266-L369)) storing immutable `(MorphCandidate[] cands, float[] logPriors)` for plain lowercase ASCII tokens. Over **`85%` of tokens** resolve in one hash lookup (`~8 ns`, zero heap allocations), while `e`-vowel words are analyzed once and cached forever with their true lemma, POS, and grammatical case!
3. **Zero-Allocation Primitive `Beam = 4` Pass 1 + Instant Unambiguous Beam Collapse + Unified Unary Precomputation:**
   - *v1 waste:* Pass 1 used `Beam = 8` with `ArrayList<BeamHyp>` object allocations and `TimSort` (`nextBeam.sort(...)`) at every token — including completely unambiguous tokens (`K_i == 1`, `~82%` of tokens) where unary and bigram features canceled identically across all beam paths — and Pass 2 recomputed `scoreUnary` from scratch on every sweep!
   - *v2 simplification:* Replaced `BeamHyp` objects with primitive arrays inside `ThreadLocal<Scratch>` ([`runPrimitiveBeamPass1`](experiment/src/main/java/zemberek/normalization/deasciifier/lattice/LatticePerceptronDeasciifier.java#L618-L757)) at **`Beam = 4`**:
     - Unary scores are computed **once** and ONLY when `K_i >= 2`, then reused across both Pass 1 and Pass 2;
     - When `K_i == 1` and `beamSize == 1`, Pass 1 executes **zero** feature hashes or MPHF lookups;
     - After two consecutive unambiguous tokens (`K_{i-1} == 1 && K_i == 1`), all beam hypotheses share `(prev1, cur)` and collapse back to `beamSize = 1` using **only the 2 trigram features (`524`, `525`)**!
4. **Precomputed `MorphCandidate` Hashes & `CharHarmonyViterbiModel` `ThreadLocal<CharScratch>`:**
   - Precomputed `baseLemmaHash`, `caseHash`, `lastHarmony`, `isContentWord`, and `OOV_MORPH_TAG_HASH` on [`MorphCandidate`](experiment/src/main/java/zemberek/normalization/deasciifier/core/MorphologicalCandidateProvider.java#L71-L137) (eliminating millions of runtime `FeatureHash.hashString(caseId)` and `lastTurkishVowelHarmony` scans during beam search) while keeping `!isOovFallback` `morphTagHash` bit-identical to `SoftGpaHybridDeasciifier`. Added `ThreadLocal<CharScratch>` and orthographic `'a'` circumflex gating in [`CharHarmonyViterbiModel`](experiment/src/main/java/zemberek/normalization/deasciifier/lattice/CharHarmonyViterbiModel.java#L47-L148).

#### B. Model Compression & Feature Deduplication (`2,234.1 KB → 1,250.5 KB`, `-44.0%`, or `1,117.6 KB`, `-50.0%`)
5. **Removing Duplicate & Stopword-Polluted Perceptron Features (`460k → 265k` Sentence Feats, `110k → 68k` Char Feats):**
   - *v1 bugs found:*
     1. In `extractTransitionFeatures`, feature `529` hashed `(prev1.lastHarmony, 0, cur.surfaceHash)` on the `99.2%` of tokens that were **not** question clitics — an exact duplicate of feature `528` (`(prev1.lastHarmony, cur.surfaceHash)`) that wasted tens of thousands of weight slots!
     2. In `extractWideWindowFeatures`, `d = -1` and `d = +1` re-emitted `(c.lemmaHash, ctx.lemmaHash)` (`540`), duplicating bigram feature `521` while hashing every stopword (`bir`, `ve`, `bu`, `de`, `ile`) at `|d| ∈ {1..5}`!
     3. Features `503`, `504`, `505` fired with `rightPunct = 0` or `sentEndPunct = '.'` on `>90%` of words, duplicating `501` (`surfaceHash`).
   - *v2 cleanup ([`LatticePerceptronDeasciifier`](experiment/src/main/java/zemberek/normalization/deasciifier/lattice/LatticePerceptronDeasciifier.java#L888-L999)):* Gated `529` strictly on `cur.isQuestion`, gated `503/504` on `rightPunct != 0` and `505` on `?`/`!`, and restricted wide-window features (`541`, `542`) strictly to **`|d| ∈ {2..6}` content words (`ctx.isContentWord == true`)**. Combined with `CompactLexiconTables` V2 (`340.6 KB → 96.4 KB`) and pruned `CharHarmonyViterbiModel` (`376.0 KB → 226.6 KB`), total disk size dropped from **`2,234.06 KB` (`2.182 MB`) to `1,250.50 KB` (`1.221 MB`, `-44.0%`)** — and even **`1,117.64 KB` (`1.091 MB`, `-50.0%`)** at `225k` sentence features!

#### C. Targeted Accuracy Upgrades
6. **Backward-then-Forward (`Right-to-Left` Sweep 0, `Left-to-Right` Sweep 1) Pass-2 Refinement:**
   - Turkish is **head-final (SOV)**: disambiguating heads (verbs, possessed nouns, postpositions) sit to the **right** of modifiers (`ön uç kısmında`, `keskin sızı hissediyorum`). Running Pass-2 Sweep 0 **right-to-left** (`n - 1 → 0`) resolves right-hand heads first and propagates them leftward before Sweep 1 (`0 → n - 1`).
7. **OOV-Resilient Neighbor Stem/Suffix Cue (`yanlız başına` Fixed — `Suite-3` `95% → 100%`):**
   - Added [`FeatureHash.hashSuffix`](experiment/src/main/java/zemberek/normalization/deasciifier/core/FeatureHash.java#L62-L74) feature `510` (`hashSuffix(prevAscii, 3) × cur.lemmaHash`) and `511` (`cur.lemmaHash × hashSuffix(nextAscii, 2)`), so misspelled neighbor stems (`"yanlız"` sharing `"-liz"` with `"yalnız"`) still disambiguate `"başına"` vs `"basına"`, lifting `Suite-3` from `95.00%` (`19/20`) to **`100.00%` (`20/20`)**.
8. **Stem-First Apostrophe Decoding (`Altınkapı'nın`), Sentence Casing Cue (`bölü` vs `Bolu`), Possessive Zero-Copula Guard (`ısınsın` vs `işinsin`), Backoff-Only `lemmaLogCounts`, & Unbiased Circumflex Training:**
   - Decoded unlisted apostrophe stems cleanly at the apostrophe boundary before `harmonizeSuffixAfterStem` (`Altinkapi'nin → Altınkapı'nın`); added `sentIsCapitalized` to `506` (`Yirmi dört bölü altı`); penalized contrived `Noun+Possessive+ZeroCopula` verb parses (`iş+in+sin`); applied `lemmaLogCounts` strictly when `surfaceLogPrior == 0.0f` (preventing unigram double-counting); and removed oracle context leakage (`predSnapshot`) + label-conditioned `'a'` circumflex bias in [`LatticePerceptronTrainer`](experiment/src/main/java/zemberek/normalization/deasciifier/lattice/LatticePerceptronTrainer.java#L105-L260).

---

### 10.2 Grand Head-to-Head Comparison: Baseline vs Approach B (v1 & v2) vs Approach A (v1 & v2)

All trainable models were trained on the identical zero-leakage **`450,000`-sentence** stratified corpus (`800,000` sentences for lexicon/lemma mining) and evaluated across all 7 held-out suites on a single thread.

#### A. Model Size, Memory, Training Time & Inference Speed (`Gemini-2500`, `31,382` Words)

| Metric | Baseline 2006 GPA (`Deasciifier`) | Approach B v2 Standalone | **Approach B v2 (`Morph+MicroML`)** | Approach A v1 (`LatticePerceptron`) | **Approach A v2 (`LatticePerceptron`)** |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Requires `TurkishMorphology`?** | No | **No (`0` deps)** | Yes | Yes | Yes |
| **Serialized Model Size** | `~290 KB` (`patternTable`) | **`936.99 KB` (`0.915 MB`)** | **`936.99 KB` (`0.915 MB`)** | `2,234.06 KB` (`2.182 MB`) | **`1,250.50 KB` (`1.221 MB`)** *(or `1,118 KB`)* |
| — Character / Trie Model | `~290 KB` `HashMap` | `277.96 KB` (`OutwardTrie`) | `277.96 KB` (`OutwardTrie`) | `376.00 KB` (`110k` feats) | **`226.55 KB` (`68k` feats, `-39.7%`)** |
| — `CompactLexiconTables` | — | `96.37 KB` (`12.5k` keys) | `96.37 KB` (`12.5k` keys) | `340.60 KB` (`11.5k` keys) | **`96.37 KB` (`12.5k` keys, `-71.7%`)** |
| — Sentence / Helper Weights | — | *(unused in standalone)* | `515.14 KB` (`155k` feats) | `1,517.46 KB` (`460k` feats) | **`880.06 KB` (`265k` feats, `-42.0%`)** |
| — Corpus `lemmaLogCounts` | — | — | `47.52 KB` (`14k` lemmas) | — | **`47.52 KB` (`14k` lemmas)** |
| **Model RAM Footprint** | `~2.4 MB` (`HashMap` obj) | **`0.27 MB`** | **`1.04 MB`** | `1.85 MB` | **`1.13 MB` (`-38.9%` vs A-v1)** |
| **`450k`-Sent Training Time** | N/A | `223s` (shared) | `223s` | `>700s` | **`319.8s` (`>2.2x` faster than A-v1)** |
| **Cold-Cache Throughput** | `136,111 tok/s` | `214,602 tok/s` | `21,041 tok/s` (`1,491 ms`) | `~28,000 tok/s` | **`30,438 tok/s` (`1,031 ms`, `1.45x` > B-v2)** |
| **Warm-Cache Throughput** | `136k – 197k tok/s` | **`215k – 315k tok/s`** | **`211,279 tok/s` (`148.5 ms`)** | `83,990 – 171,766 tok/s` | **`300,422 tok/s` (`104.5 ms`, #1 Overall!)** |

> [!TIP]
> **Why Approach A v2 (`300.4k tok/s`) Is `1.42x` Faster Than Approach B v2 (`211.3k tok/s`) and `2.2x` Faster Than Legacy GPA When Warm:**
> Approach B must run `OutwardContextTrie` character-by-character across every token before looking up `draftStatusCache`. In Approach A v2, `plainLowerCache` maps `>85%` of tokens directly from `span.asciiLower` to their single cached `MorphCandidate` in one hash lookup (`~8 ns`), and `runPrimitiveBeamPass1` skips both character scoring and sentence feature extraction completely on unambiguous stretches!

---

#### B. Accuracy Across All 7 Held-Out Benchmark Suites

| Benchmark Suite | Baseline 2006 GPA | Approach B v2 Standalone (`937 KB`) | **Approach B v2 `Morph+MicroML` (`937 KB`)** | Approach A v1 (`2,234 KB`) | **Approach A v2 (`1,251 KB`)** |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **`Gemini-2500` — Sent (`No^` / `Strict`)** | `83.76%` / `77.16%` | `87.16%` / `83.32%` | **`95.32%` / `90.76%`** | `94.88%` / `90.44%` | **`95.08%`** / `90.28%` *(or `95.28%`/`90.44%` @ `1,118 KB`)* |
| **`Gemini-2500` — Word (`No^` / `Strict`)** | `98.52%` / `97.85%` | `98.86%` / `98.47%` | **`99.60%` / `99.18%`** | `99.56%` / `99.16%` | **`99.58%` / `99.15%`** (`DiacWord = 99.39%`) |
| **`Refined-Test Clean` (`11,720`) — Sent (`No^` / `Strict`)** | `89.07%` / `87.27%` | `93.79%` / `91.21%` | `96.70%` / `94.11%` | `96.80%` / `93.82%` | **`96.84%` / `94.20%` (#1 Both!)** |
| **`Refined-Test Clean` (`11,720`) — Word (`No^` / `Strict`)** | `99.00%` / `98.81%` | `99.46%` / `99.23%` | `99.72%` / `99.49%` | `99.73%` / `99.46%` | **`99.73%` / `99.50%` (`DiacWord = 99.61%`, #1!)** |
| **`Refined-Test Raw-All` (`12,472`) — Sent (`No^` / `Strict`)** | `89.18%` / `87.48%` | `93.92%` / `91.46%` | `96.76%` / `94.28%` | `96.83%` / `93.97%` | **`96.88%` / `94.34%` (#1 Both!)** |
| **`Suite-2`: Hard Homographs (`60` sents)** | `56.67%` / `50.00%` | `71.67%` / `61.67%` | **`80.00%` / `73.33%`** | `78.33%` / `71.67%` | **`78.33%` / `71.67%`** |
| **`Suite-3`: OOV, Typos & Numerals (`20` sents)** | `60.00%` / `60.00%` | **`100.00%` / `100.00%`** | **`100.00%` / `100.00%`** | `95.00%` / `95.00%` | **`100.00%` / `100.00%` (`+5.00%`, Tied #1!)** |
| **`Suite-4`: Partial-ASCII (`500` sents)** | `80.60%` (`38` stripped) | `88.20%` (`0` stripped) | `96.80%` / `94.00%` (`0` strp) | `97.00%` / `94.60%` | **`97.80%` / `95.00%` (`99.82%` word, #1!)** |
| **`Suite-5`: Foreign, Acronyms & Spans (`16` sents)** | `43.75%` / `37.50%` | `93.75%` / `87.50%` | **`100.00%` / `93.75%`** | **`100.00%` / `93.75%`** | **`100.00%` / `93.75%` (Tied #1!)** |

---

#### C. Per-Register Breakdown on `Refined-Test Verified-Clean` (`11,720` Sentences)

| Domain Register (Sentences) | Baseline 2006 GPA (`No^`) | Approach B v2 Standalone (`No^`) | **Approach B v2 (`Morph+MicroML`)** `Sent No^` (`Strict` / `Word No^`) | **Approach A v2 (`LatticePerceptron`)** `Sent No^` (`Strict` / `Word No^`) |
| :--- | :---: | :---: | :---: | :---: |
| **News** (`5,480`) | `87.96%` | `93.87%` | `96.81%` (**`95.31%`** / `99.74%`) | **`96.97%`** (`95.27%` / **`99.76%`**) |
| **Science & Wikipedia** (`1,251`) | `88.97%` | `91.69%` | **`96.08%`** (`94.24%` / **`99.69%`**) | `95.84%` (**`94.40%`** / `99.67%`) |
| **Legal & Parliament** (`3,098`) | `90.77%` | `94.61%` | `97.03%` (**`91.87%`** / `99.77%`) | **`97.16%`** (`91.77%` / **`99.78%`**) |
| **OpenSubtitles** (`440`) | `90.00%` | `93.64%` | `96.36%` (`94.09%` / `99.47%`) | **`96.59%` (`94.77%` / `99.51%`)** |
| **Informal Web & Forums** (`1,451`) | `89.46%` | `93.59%` | `96.21%` (`94.28%` / `99.57%`) | **`96.62%` (`94.97%` / `99.59%`)** |
| **Overall (`11,720` Verified-Clean)** | `89.07%` | `93.79%` | `96.70%` (`94.11%` / `99.72%`) | **`96.84%` (`94.20%` / `99.73%`)** |

> [!NOTE]
> **Key Takeaway from Comparing Both Improved v2 Architectures:**
> - **Approach A v2 (`1,251 KB`)** is no longer "the heavy, slower model": after cutting redundant Viterbi work, deduplicating features, caching plain lowercase lattices, and moving to a primitive `Beam = 4` + Backward-Forward Pass 2, it shrank by **`44%` (`2.18 MB → 1.22 MB`)**, sped up **`3.58x` (`300,422 tok/s` warm — fastest of all systems)**, closed its OOV/neighbor-typo gap (`Suite-3 = 100.00%`), and achieved the **highest overall accuracy on `Refined-Test` (`96.84%` `No^` / `94.20%` `Strict`) and `Suite-4` Partial-ASCII (`97.80%`)**.
> - **Approach B v2 (`937 KB`)** remains **`25%` smaller (`0.915 MB`)**, leads slightly on `Gemini-2500` (`95.32%` vs `95.08%`) and `Suite-2` (`80.00%` vs `78.33%`), and uniquely provides a **zero-morphology `standalone()` mode (`215k–315k tok/s`)** from the exact same `937 KB` binary.
