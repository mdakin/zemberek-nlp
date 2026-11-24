# Turkish Clean Corpus (`~/data/turkish/clean`) — Full Refinement & Morphological Analysis Overview

- **Source Directory**: `~/data/turkish/clean` (`30` `.txt` files, `17.68 GB` / `17,684.82 MB`)
- **Refined Output Directory**: `~/data/turkish/refined` (`30` `.txt` files, `9.87 GB` / `9,874.00 MB`)
- **Full Auto-Generated Report**: [`corpus_refinement_report.md`](experiment/docs/model/corpus_refinement_report.md)
- **Refiner Application**: [`RefineCorpus.java`](experiment/src/main/java/zemberek/morphology/ambiguity/model/RefineCorpus.java)
- **Launcher Script**: [`run_refine_corpus.sh`](experiment/scripts/model/run_refine_corpus.sh)

---

## 1. Executive Summary of the Full Corpus Refinement (`17.91 min` on `16` Threads)

| Metric | Original Corpus (`~/data/turkish/clean`) | Refined Corpus (`~/data/turkish/refined`) |
| :--- | ---: | ---: |
| **Total Disk Size** | `17,684.82 MB` (`17.68 GB`) | **`9,874.00 MB` (`9.87 GB`, `55.8%` of original)** |
| **Raw Input Lines Read** | `284,925,590` | `127,645,345` (1 sentence/line) |
| **Total Extracted Sentences** | **`312,342,828` (100.00%)** | **`127,645,345` (40.87% kept)** |
| **Rejected: Too Short (`< 5` words)** | `150,354,421` (`48.14%`) | `0` |
| **Rejected: Too Long (`> 30` words)** | `6,274,263` (`2.01%`) | `0` |
| **Rejected: Contains `>= 1` Unparseable Word** | `28,068,799` (`8.99%`) | `0` |
| **Total Words (Non-Punctuation Tokens)** | **`2,285,357,272` (`2.29B`)** | **`1,277,763,410` (`1.28B`, `55.91%` kept)** |
| **Total Punctuation Tokens** | `461,015,166` | `206,917,563` |
| **Total Tokens (Words + Punctuation)** | **`2,746,372,438` (`2.75B`)** | **`1,484,680,973` (`1.48B`)** |
| **Words Parsed by Zemberek** | **`2,231,852,539` (`97.66%`)** | **`1,277,763,410` (`100.00%`)** |
| **Words Unparseable (`UNKNOWN` / No Parse)** | `53,504,733` (`2.34%`) | `0` (`0.00%`) |
| **Average Sentence Size (Words / Sentence)** | **`7.32 words`** (`p50=5`, `p90=16`, `p99=37`) | **`10.01 words`** (`p50=8`, `p90=18`, `p99=28`) |
| **Average Sentence Length (Chars / Sentence)** | `53.4 chars` | `73.2 chars` |
| **Average Word Size (Chars / Word)** | **`6.22 chars`** | **`6.24 chars`** |
| **16-Thread Processing Throughput** | `290,599 sentences/sec` | `2,126,262 words/sec` |

---

## 2. Morphological & Ambiguity Profile of the Refined Corpus (`1.28 Billion` Words)

| Morphological Property | Value | Significance for Disambiguator Training |
| :--- | ---: | :--- |
| **Morphologically Ambiguous Words (`>= 2` analyses)** | **`819,786,514` (`64.16%`)** | Almost 2 out of every 3 Turkish words require contextual disambiguation |
| **Unambiguous Words (`1` analysis)** | `457,976,896` (`35.84%`) | Deterministic single-parse words that anchor Viterbi transitions |
| **Highly Ambiguous Words (`>= 5` analyses)** | `109,162,555` (`8.54%`) | Dense homonym + derivational ambiguity |
| **Average Analyses per Word (All Words)** | **`2.40`** | Overall morphological branching factor |
| **Average Analyses per Ambiguous Word** | **`3.18`** | Mean candidate count when `>= 2` analyses exist |
| **Sentences with `>= 1` Ambiguous Word** | **`127,396,732` (`99.81%`)** | 99.81% of `[5, 30]`-word sentences contain disambiguation signal |
| **Fully Unambiguous Sentences (`0` ambiguous words)** | `248,613` (`0.19%`) | Only ~1 in 500 Turkish sentences of `>= 5` words is completely unambiguous |
| **Derived Words (`>= 2` Inflectional Groups)** | `211,628,788` (`16.56%`) | 1 in 6 words undergoes at least one derivational POS transition |
| **Average Inflectional Groups (IGs) per Word** | **`1.19 IGs`** | Mean derivational depth per word |
| **Dynamic/Runtime Words (Numbers, Dates, OOV Proper)** | `29,564,097` (`2.31%`) | Parsed via `UnidentifiedTokenAnalyzer` |

---

## 3. Key Findings & Domain Breakdown Across the 30 Corpus Files

1. **Why `48.14%` of Original Sentences Were `< 5` Words**:
   - `open-subtitles-tr-2018.txt` (`5.0 GB`, `173.4M` sentences) makes up **55.5% of all sentences** in the raw corpus, and **`67.8%` (`117.5M`) of its sentences have fewer than 5 words** (short conversational utterances like *"Evet."*, *"Ne oldu?"*, *"Tamam, geliyorum."*).
   - After applying the `[5, 30]` word filter and 100% parseability gate, `open-subtitles-tr-2018.txt` shrinks from `55.5%` of sentences to `38.8%` of sentences (`49.48M` sentences, `354.35M` words — `27.7%` of all refined words), yielding a much better balance between conversational and formal written Turkish.

2. **Four Distinct Corpus Regimes in `~/data/turkish/refined`**:
   - **News & Journalism (`~490M` refined words, `38.4%` of refined corpus)**:
     - Includes `radikal.txt` (`105.9M` words), `www.iha.com.tr.txt` (`60.5M`), `star-gazete.txt` (`60.2M`), `www.dunya.com.txt` (`50.0M`), `cnn-turk.txt` (`42.5M`), `www.haberturk.com.txt` (`38.3M`), `www.ensonhaber.com.txt` (`29.6M`), `onedio.com.txt` (`29.6M`), `dunya.txt` (`27.4M`), `www.cnnturk.com.txt` (`22.7M`), `ntvmsnbc.txt` (`15.9M`), `ntv-750k-sentences.txt` (`7.8M`).
     - Very high word parseability (`98.4%–98.9%`), `12.7–14.1` words/sentence, `6.5–6.7` chars/word, `60.4%–62.5%` word ambiguity.
   - **Subtitles & Spoken Dialogue (`~395M` refined words, `30.9%` of refined corpus)**:
     - Includes `open-subtitles-tr-2018.txt` (`354.4M` words), `open-subtitles.txt` (`29.8M`), `subtitle-tr-10M-sentences.txt` (`11.1M`).
     - Shorter sentences (`7.2–7.5` words/sentence), shorter words (`5.80–5.91` chars/word), and **highest morphological ambiguity (`67.6%–68.1%`)** due to frequent 1st/2nd person verb/possessive inflections and short homonymous stems.
   - **Web Forums & Community Q&A (`~238M` refined words, `18.6%` of refined corpus)**:
     - Includes `wowturkey.com.txt` (`107.8M` words), `www.kizlarsoruyor.com.txt` (`86.7M`), ` www.hukuki.net.txt` (`27.5M`), `forum.memurlar.net.txt` (`13.8M`), `forum.shiftdelete.net.txt` (`11.8M`), `hukuki-net.txt` (`9.2M`), `www.nurturia.com.tr.txt` (`4.0M`).
     - Unparseable filter strips out `18%–40%` of sentences containing ASCII-fied Turkish or typos, leaving clean conversational and domain-specific discussions (`9.2–11.8` words/sentence).
   - **Parliamentary, Legal & Encyclopedic (`~150M` refined words, `11.7%` of refined corpus)**:
     - Includes `tbmm-tutanak.txt` (`37.6M` words, **`99.6%` word parseability**), `tbmm.txt` (`36.5M`, `99.3%`), `yargitay-karar.txt` (`36.5M`, longest sentences at `15.8` words/sentence and longest words at `6.91` chars/word), and `vikipedi.txt` (`17.5M` words).

3. **Top Causes of Unparseable Token Rejection (`53.5M` unparsed word tokens across `28.1M` rejected sentences)**:
   - **Colloquial / diacritic-omitted spellings**: `bi` (`418,189`), `bişey` (`205,766`), `cok` (`161,915`), `tabiki` (`97,037`), `degil` (`92,925`), `hemde` (`86,162`), `icin` (`75,792`), `malesef` (`58,274`), `tesbit` (`53,445`), `sey` (`48,884`), `felan` (`48,333`), `yinede` (`45,763`), `Afedersiniz` (`42,650`), `oldugunu` (`40,877`), `stad` (`39,853`), `değilde` (`39,544`).
   - **Detached suffixes from spacing errors**: `nin` (`204,303`), `nun` (`202,825`), `nın` (`135,188`), `lik` (`40,031`).
   - **Formatting/OCR symbols**: `|` (`193,601`), `i` (`116,391`), `l` (`89,952`), `—` (`76,336`), `~` (`56,373`), `•` (`42,692`).
   - **Missing lexicon / apostrophe-derivation edge cases**: `ABD'li` (`46,992`), `Salih` (`45,327`), `AİHM` (`45,022`).
