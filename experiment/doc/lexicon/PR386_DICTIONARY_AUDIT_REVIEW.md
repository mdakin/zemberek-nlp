# Review of PR `#386` (*Dictionary Audit and Modern Words*) & Normalization Impact

## 1. Overall Verdict: **Strong Direction, Needs Targeted Fixes Before Merge**

[DICTIONARY_AUDIT_REPORT.md](file:///home/dndara/work/zemberek-nlp/experiment/doc/lexicon/DICTIONARY_AUDIT_REPORT.md) is well-reasoned and identifies real, long-standing structural bugs in Zemberek's dictionaries. In PR `#386` (`5bae363` + `62a6a40`, `+285` net lexicon items: `67,750 → 68,035`), **Track 1's structural fixes** — throwing `LexiconException` on duplicate `[A:...; A:...]` keys in [TurkishDictionaryLoader.java](file:///home/dndara/work/zemberek-nlp/morphology/src/main/java/zemberek/morphology/lexicon/tr/TurkishDictionaryLoader.java), fixing corrupted `CompoundP3sg` `Roots:` (`bacabaşı`, `güveyfeneri`, `tavuskuyruğu`, `düşkırıklığı`, `elbirliği`, etc.), removing false `CompoundP3sg` tags (`büyükşehir`, `ortaparmak`, `üstderi`), fixing multi-POS `NoVoicing` leaks (`paket`, `bayat`, `cennet`, `dehşet`, `somut`, `soyut`), and adding modern single-word vocabulary (`bebiş`, `tatlış`, `ghosting`, `fintek`, `özçekim`, `nörobilim`) — are genuinely valuable and directly fix errors like `"internet pakediniz" → "internet paketiniz"` and `"tatlısım" → "tatlışım"`.

However, live morphological probing and evaluation on `test_slice_02_v4` uncovered **7 concrete bugs** (including two subtle serializer/loader bugs triggered by the new entries and two linguistic/TDK regressions) that should be fixed before merging PR `#386`.

---

## 2. Critical Bugs Uncovered in PR `#386` (Must Fix)

> [!CAUTION]
> Bugs **2.1**, **2.2**, and **2.3** silently break morphological analysis and spellchecking on real Turkish words (`bollar → boklar`, `API'ye → [NO PARSE]`, `TOGG'un → [NO PARSE]`, `semptomatiği → [NO PARSE]`).

### 2.1 False Homograph Attribute Copy: `bol [P:Adj; A:InverseHarmony]` Breaks `"bollar"`, `"bolca"`, `"bolluk"`
- **Where:** `master-dictionary.dict` (`bol [P:Adj] → bol [P:Adj; A:InverseHarmony]`).
- **Root Cause:** `bol` (`Noun`, `[A:InverseHarmony]`) is the rare French loanword *bol* (punchbowl/cocktail), whereas `bol` (`Adj`, "loose / abundant") is native Turkic with **back-vowel harmony** (`bollar`, `bolca`, `bolluk` — never `*boller`, `*bolce`, `*böllük`).
- **Live Regression Caught on `test_slice_02_v4` (`gen_dv_003555`):**
  - Raw/Gold: `"mükemmeller benim için bollar biraz..."`
  - Baseline: `"bollar"` (preserved)
  - PR `#386`: **`"boklar"`** — because `bol [P:Adj; A:InverseHarmony]` made `"bollar"` unparseable (`!strictKnown`), causing the spellchecker to corrupt `"bollar"` to `"boklar"`!
- **Fix:** Revert `bol [P:Adj]` back to `bol [P:Adj]` (no `InverseHarmony`), and add `"bol"` to `intentionalExceptions` in `TurkishDictionaryLoaderTest.defaultDictionariesMultiPosPhoneticConsistencyTest` alongside `"kalp"`, `"nefis"`, and `"kadir"`.

### 2.2 `DictionarySerializer` Locale Asymmetry Corrupts `API`, `GIF`, `HIV`, `PIN`, `SIM`, `BIST` in `lexicon.bin`
- **Where:** [DictionarySerializer.java](file:///home/dndara/work/zemberek-nlp/morphology/src/main/java/zemberek/morphology/lexicon/DictionarySerializer.java#L141-L176) interacting with PR `#386`'s new uppercase ASCII-`I` acronyms (`API [Pr:api]`, `GIF [Pr:gif]`, `HIV [Pr:hiv]`, `PIN [Pr:pin]`, `SIM [Pr:sim]`, `BIST [Pr:bist]`).
- **Root Cause:**
  1. During `lexicon.bin` generation, `convertToProto()` (`L141`) calls `item.lemma.toLowerCase()` **without a `Locale`** (default `en_US`: `"API".toLowerCase()` = `"api"`). Because `item.pronunciation` (`"api"`) equals `lowercaseLemma` (`"api"`), `convertToProto()` **omits `pronunciation`** from the protobuf!
  2. At runtime, `convertToDictionaryItem()` (`L171–176`) calls `item.getLemma().toLowerCase(Turkish.LOCALE)` (`"API"` → **`"apı"`** with dotless `ı`!) and fills the empty `pronunciation` with **`"apı"`**, **`"gıf"`**, **`"hıv"`**, **`"pın"`**, **`"sım"`**, **`"bıst"`**!
  3. Furthermore, without `A:LocaleEn`, `TurkishDictionaryLoader` sets `item.root = "apı"` instead of `"api"`, so lowercase `"api"` in text never matches `API`!
- **Live Proof:**
  - `morphPr.analyze("API'ye")` → **`[NO LEXICAL PARSE]`**, while `*API'ya` parses as `[API:Noun,Abbrv] apı:Noun+A3sg+ya:Dat`!
  - On `test_slice_02_v4` (`gen_dv_003585`), `"api entegresi"` normalized to `"API entegresi"` in Baseline, but regressed to lowercase `"api entegresi"` in PR `#386`.
- **Fix (2 parts):**
  1. In [DictionarySerializer.java](file:///home/dndara/work/zemberek-nlp/morphology/src/main/java/zemberek/morphology/lexicon/DictionarySerializer.java#L141), change `String lowercaseLemma = item.lemma.toLowerCase();` to use the exact same locale as `convertToDictionaryItem`:
     ```java
     Locale locale = item.attributes.contains(RootAttribute.LocaleEn) ? Locale.ENGLISH : Turkish.LOCALE;
     String lowercaseLemma = item.lemma.toLowerCase(locale);
     ```
  2. Add `A:LocaleEn` to foreign ASCII-`I` acronyms in `abbreviations.dict` (`AI`, `API`, `BIOS`, `BIST`, `FBI`, `GIF`, `HIV`, `HTTPS`, `IBAN`, `IPO`, `IoT`, `KPI`, `PIN`, `ROI`, `SIM`, `SWIFT`, `UI`, `UN`, `WIFI`) so lowercase `"api"`, `"wifi"`, `"iban"`, `"gif"`, `"pin"`, `"sim"` match their stems cleanly.

### 2.3 `TurkishDictionaryLoader` `"-og"` Voicing Leak Breaks `TOGG [Pr:tog; P:Abbrv]` and `vlog [A:Ext]`
- **Where:** [TurkishDictionaryLoader.java](file:///home/dndara/work/zemberek-nlp/morphology/src/main/java/zemberek/morphology/lexicon/tr/TurkishDictionaryLoader.java#L524-L528).
- **Root Cause:** On `L518–519`, stop-consonant `Voicing` exempts both `ProperNoun` and `Abbreviation`, **but on `L524–527` (`if (word.endsWith("nk") || word.endsWith("og"))`), `posData.secondaryPos != SecondaryPos.Abbreviation` was omitted!**
  - Because `TOGG [Pr:tog; P:Abbrv]` has pronunciation `"tog"` (ending in `"og"`), `TurkishDictionaryLoader` silently attaches `RootAttribute.Voicing`, mutating its prevocalic stem to `"toğ"`!
  - Consequently, **`morphPr.analyze("TOGG'un")`**, **`"TOGG'u"`**, and **`"TOGG'a"`** all return **`[NO LEXICAL PARSE]`**!
  - Similarly, `vlog [A:Ext]` gets `Voicing` (`*vloğu` instead of `vlogu`).
- **Fix:** Add `&& posData.secondaryPos != SecondaryPos.Abbreviation` on `L526` of `TurkishDictionaryLoader.java`, and mark `vlog [A:Ext, NoVoicing]` in `non-tdk.dict`.

### 2.4 Erroneous `A:NoVoicing` on `semptomatik` (`master-dictionary.dict`)
- **Where:** `master-dictionary.dict` (`+semptomatik [P:Adj; A:NoVoicing]`).
- **Bug:** Every other `-ik` word added in PR `#386` (`fotonik → fotoniği`, `probiyotik → probiyotiği`, `prebiyotik`, `mikroplastik`, `transgenik`, `empatik`, `ikonik`, `narsistik`) properly voices `k → ğ`, matching TDK (`otomatik → otomatiği`, `kronik → kroniği`). With `A:NoVoicing`, Zemberek **rejects `semptomatiği`** (`[NO LEXICAL PARSE]`) and generates `*semptomatiki` / `*semptomatike`.
- **Fix:** Change to `semptomatik [P:Adj]`.

### 2.5 25 TDK Open Compounds Added as Closed Single Words in `master-dictionary.dict` (+ 8 in `non-tdk.dict`)
- **Where:**
  - `master-dictionary.dict`: `yapayzeka`, `yoğunbakım`, `büyükveri`, `bulutbilişim`, `derinöğrenme`, `makineöğrenmesi`, `veribilimi`, `kentseldönüşüm`, `sibergüvenlik`, `denizkabuğu`, `evsahipliği`, `merdivenaltı`, plus **13 `...arası` words** (`şehirlerarası`, `devletlerarası`, `bankalararası`, `disiplinlerarası`, `kurumlararası`, `kuşaklararası`, `okullararası`, `üniversitelerarası`, `kıtalararası`, `hükümetlerarası`, `gezegenlerarası`, `galaksilerarası`, `yıldızlararası`).
  - `non-tdk.dict`: `sosyalmedya`, `akıllıtelefon`, `akıllısaat`, `filtrekahve`, `dilmodeli`, `güvenlikduvarı`, `arkaalan`, `geriplan`.
- **Why This Hurts Zemberek:**
  1. **Violates TDK Orthography in `master-dictionary.dict`:** Under TDK rules, `yapay zekâ`, `yoğun bakım`, `veri bilimi`, `siber güvenlik`, `kentsel dönüşüm`, `deniz kabuğu`, `ev sahipliği`, `merdiven altı`, and **every `... arası` compound except `uluslararası`** (`şehirler arası`, `devletler arası`, `disiplinler arası`, etc.) are written **separately**.
  2. **Blocks Spellcheck & Normalization Run-On Splitting:** Because `RootLexicon.getDefault()` loads both `master-dictionary.dict` and `non-tdk.dict`, adding `yapayzeka`, `sosyalmedya`, `akıllıtelefon`, `yoğunbakım`, `filtrekahve` marks glued missing-space typos as `strictKnown = true`, preventing Zemberek's spellchecker and normalizer from splitting `"sosyalmedya" → "sosyal medya"` or `"akıllıtelefon" → "akıllı telefon"`.
- **Fix:** Remove open compounds from `master-dictionary.dict` (keep genuine closed TDK/modern words like `blokzincir`, `kriptopara`, `mikromobilite`, `mikroplastik`, `nörobilim`, `özçekim`, `biyoteknolojik`, `koronavirüs`, `yönetişim`), and remove glued typos (`sosyalmedya`, `akıllıtelefon`, `akıllısaat`, `filtrekahve`, `dilmodeli`, `güvenlikduvarı`, `arkaalan`, `geriplan`) from `non-tdk.dict` (note `espor` and `pankek` are fine).

---

## 3. Moderate Completeness Gaps vs. `DICTIONARY_AUDIT_REPORT.md`

### 3.1 `abbreviations.dict` Casing Duplicates & Pronunciation (`Pr:`) Fixes
- **10 Case-Insensitive Duplicates Created:** Track 1 #6 of `DICTIONARY_AUDIT_REPORT.md` noted that legacy entries were Titlecase (`Tübitak`, `Toki`, `Tüik`, `Oecd`), but Commit 2 appended UPPERCASE entries at the bottom **without removing the Titlecase ones**, leaving duplicate pairs: `Tübitak` + `TÜBİTAK`, `Tüik` + `TÜİK`, `Toki` + `TOKİ`, `Oecd` + `OECD`, `Müsiad` + `MÜSİAD`, `Gib` + `GİB`, `Fbı(pr=febeı)` + `FBI(pr=efbiay)`, `Hıv` + `HIV`, `Sım` + `SIM`, `Imeı` + `IMEI` (plus `Vip` + `VIP`).
- **Pronunciation Errors:**
  - `SSH [Pr:esesej]` → `j` is voiced, so Zemberek **rejects `SSH'ta` and `SSH'te`** (`[NO LEXICAL PARSE]`) and only accepts `*SSH'de`! Should be `Pr:eseseyç` (`SSH'ta`) or `Pr:sesehe` (`SSH'de`).
  - `Seo [Pr:siyo]` → confuses `SEO` (*Search Engine Optimization*, `Pr:seo` or `Pr:esiyo`) with `CEO` (`Pr:siyo`).
  - Minor typos in `Pr:`: `CTO [Pr:siyto]` (`sitio`) and `WHO [Pr:dabılyuayço]` (`dabılyueyço`).
- **Remaining Noise Tokens in `abbreviations.dict`:** Chat/interjection and inflected tokens like `Slm [P:Abbrv]`, `Iyi [P:Abbrv]`, `Hee`, `Haa`, `Woo`, `Wow`, `Mdlüğü [P:Abbrv]`, `Mhpliler [P:Abbrv]` (plus ~25 3-letter English words like `Boy`, `Bus`, `Buy`, `Can`, `Car`, `Day`, `God`, `Got`, `Gun`, `Guy`, `Her`, `Him`, `His`) remain in `abbreviations.dict`.

### 3.2 Incomplete Multi-POS Coverage (`Adverb` Entries & Broken `Ref:` Missed)
- **Missed `[P:Adv]` Siblings (`~50` loanwords/nouns):** `TurkishDictionaryLoaderTest` only checked `Noun` vs `Adjective`, missing `Adverb` entries right below lines edited in PR `#386` — e.g., `transit [P:Adv]` (missing `A:NoVoicing`), `kategorik [P:Adv]` (missing `A:NoVoicing`), `akıbet [P:Adv]`, `bereket [P:Adv]`, `direkt [P:Adv]`, `gayet [P:Adv]`, `hoyrat [P:Adv]`, `rahat [P:Adv]`, `serbest [P:Adv]`, `nihayet [P:Adv]`, `nispet [P:Adv]`, `farzımuhal [P:Adv]` (`InverseHarmony`), `ihtimal [P:Adv]` (`InverseHarmony`), `usul [P:Adv]` (`InverseHarmony`).
- **Unfixed Items Mentioned in `DICTIONARY_AUDIT_REPORT.md`:**
  - `müslüman [A:Ext; Ref:Müslüman_Noun_Prop]` in `non-tdk.dict` (`DICTIONARY_AUDIT_REPORT.md` line 62) was not fixed and still logs `W|Cannot find reference item id Müslüman_Noun_Prop` on load.
  - `hasut` (`DICTIONARY_AUDIT_REPORT.md` line 29) has erroneous `LastVowelDrop` on `hasut_Noun` (`*hasdu`) and was added to `intentionalExceptions` in the test instead of removing `LastVowelDrop` from `hasut_Noun`.

---

## 4. Empirical Impact on `v2` Normalization (`test_slice_02_v4`)

Evaluated on our latest held-out Heavy benchmark **`test_slice_02_v4_hhigh_normalized.jsonl`** (`5,000` sentences, `15,477` raw word edits) using the simplified `SpanCandidateGenerator` (`4,459` LOC) + `85K (+10K v4) + 8M Curated (75/25) v5(6/9/18) NO SmoothLm`:

| Configuration | `W-ERR` | Word Edits (`Raw → Sys`) | `SentEM-Lo` | `SentEM-Ca` | `CleanFPR` |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **1. Baseline Lexicon (`Current HEAD`)** | **`80.53%`** | `15,477 → 3,013` | **`68.10%`** | **`57.46%`** | **`1.337%`** |
| **2. PR `#386` Lexicon *(Inference Swap)*** | **`80.53%`** | `15,477 → 3,014` (`+1`) | `68.08%` | **`57.46%`** | `1.343%` |
| **3. PR `#386` Lexicon *(Full Retrain)*** | `80.42%` (`-0.11 pp`) | `15,477 → 3,031` (`+18`) | `67.94%` | `57.32%` | `1.372%` |

### Concrete Token-Level Takeaways on `test_slice_02_v4`
- **Inference Swap (`13` sentences changed out of `5,000`, `0.00 pp` `W-ERR` delta):**
  - **Wins from PR `#386`:**
    - `"tatlısım" → "tatlışım"` (`gen_dv_000245`, previously `"tatlısın"`) via new `tatlış`.
    - `"bebisimiz" → "bebişimiz"` (`gen_dv_003204`) via new `bebiş`.
    - `"ghostıng" → "ghosting"` (`gen_dv_002843`) via new `ghosting`.
    - `"internet pakediniz" → "internet paketiniz"` (`gen_dv_003574`) via `paket [P:Adj; A:NoVoicing]`.
    - `"togg" → "TOGG"` / `"bitcoinleri"` / `"avatarları"` lowercase common-noun truecasing (`bitcoin`, `avatar` no longer treated as proper-noun-only).
  - **Regressions from PR `#386` (all directly explained by Section 2 bugs):**
    - `"bollar" → "boklar"` (`gen_dv_003555`) caused by `bol [P:Adj; A:InverseHarmony]` (**Bug 2.1**).
    - `"api entegresi"` stayed lowercase `"api"` instead of `"API"` (`gen_dv_003585`) caused by `API` missing `LocaleEn` + `DictionarySerializer` bug (**Bug 2.2**).
    - `"new yorkta" → "new York da"` (`gen_dv_000587`) after removing `New [P:Abbrv]`.
- **Full Retrain (`-0.11 pp` `W-ERR`, `85` sentences changed — mostly Perceptron stochastic boundary ties `17` wins vs `25` losses):** Once Bugs **2.1–2.5** are fixed in PR `#386`, the regressions (`bollar → boklar`, `api`, run-on compound suppression) disappear while keeping all of PR `#386`'s genuine vocabulary and `NoVoicing` gains.
