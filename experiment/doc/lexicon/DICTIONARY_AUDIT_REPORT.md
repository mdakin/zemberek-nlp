# Zemberek-NLP Dictionary Audit & Update Plan

This report documents the findings from the initial diagnostic pass over Zemberek-NLP's core Turkish dictionaries and outlines a two-track approach for fixing structural/multi-POS integrity bugs and adding missing vocabulary from the last ~7 years.

## Scope & Current Dictionary Sizes

- `morphology/src/main/resources/tr/master-dictionary.dict` (**28,876 entries**) — Main Turkish Language Association (TDK) dictionary.
- `morphology/src/main/resources/tr/non-tdk.dict` (**3,467 entries**) — Common Turkish words, loanwords, and compounds not present in the standard TDK dictionary.
- `morphology/src/main/resources/tr/abbreviations.dict` (**1,478 entries**) — Turkish and international abbreviations and acronyms.
- `morphology/src/main/java/zemberek/morphology/lexicon/tr/TurkishDictionaryLoader.java` — Parser and morphophonemic attribute inference engine.
- `morphology/src/main/resources/tr/lexicon.bin` — Serialized binary lexicon loaded at runtime by `RootLexicon.getDefault()`.

Because Zemberek’s morphological analyzer, spell checker, deasciifier, and disambiguator all rely directly on the lexicon, small dictionary bugs or missing stems ripple through the entire pipeline.

---

## Track 1: Dictionary Integrity & Multi-POS Consistency Audit

We can write a deterministic validation script/test that parses the raw `.dict` files and cross-checks them against `TurkishDictionaryLoader` rules. Below are the **6 specific integrity issues** identified in the current dictionary files:

### 1. Multi-POS Attribute Desynchronization (224 words affected!)
In Turkish, many words have multiple Primary POS entries (e.g., `Noun` + `Adj`, or `Adj` + `Adv`). However, in `master-dictionary.dict` and `non-tdk.dict`, **224 words** have morphophonemic attributes (`NoVoicing`, `Voicing`, `InverseHarmony`, `LastVowelDrop`, `Doubling`) on one POS entry, **but missing on the other POS entry**.

- **Why this is a bug**: In Turkish morphotactics, `Adjective` and `Adverb` roots can take nominal case/possessive suffixes via `Zero→Noun` derivation. If `bayat` has `[A:NoVoicing]` on `Noun` (`bayat`), but **not** on `bayat [P:Adj]`, then `TurkishDictionaryLoader.inferMorphemicAttributes` automatically assigns `Voicing` to `bayat [P:Adj]`! As a result, Zemberek falsely accepts `*bayadı`, `*cenabedi`, `*cennedi`, `*dehşedi`, `*despodu`, `*dinamidi`, `*etnografiği` via the `Adj|Zero→Noun` path, or `*bolu / *boller` mismatch on `bol [A:InverseHarmony]` vs `bol [P:Adj]`.
- **Examples found**:
  - **Missing `NoVoicing` on `Adj`**: `bayat`, `bilgiç`, `buklet`, `bürokrat`, `cenabet`, `cennet`, `dehşet`, `despot`, `dinamit`, `etnografik`, `fizyokrat`, `gelgeç`, `girift`, `gudubet`, `hakperest`, `hayalperest`, `hazırlop`, `hodpesent`, etc.
  - **Missing `InverseHarmony` on `Adj` or `Noun`**: `aval`, `biyomedikal`, `bol`, `dolikosefal`, `federal`, `feodal`, `global`, `gramatikal`, `hemhâl`, `hidrosefal`, etc.
  - **Missing `Voicing` on `Adj`**: `genç`, `güç` (single-syllable words default to `NoVoicing` unless `A:Voicing` is explicitly set—so `genç [P:Adj]` currently inflects as `*gençi` instead of `genci`!).
  - **Missing `LastVowelDrop` on `Adj`**: `hapis`, `hasut`, `hayır`.

### 2. Duplicate Metadata Keys Silently Overwriting Each Other (22 entries)
In `TurkishDictionaryLoader.readMetadata`, metadata chunks separated by `;` are put into an `EnumMap<MetaDataId, String>`. If a line has `[A:Ext; A:NoVoicing]` instead of `[A:Ext, NoVoicing]`, **the second `A:` silently overwrites the first `A:`**!

- **Examples found**:
  - **In `non-tdk.dict` (20 entries)**: `aktrist [A:Ext; A:NoVoicing]`, `ampirisist [A:Ext; A:NoVoicing]`, `aromaterapist [A:Ext; A:NoVoicing]`, `dadist [A:Ext; A:NoVoicing]`, `dualist [A:Ext; A:NoVoicing]`, `fizikoterapist [A:Ext; A:NoVoicing]`, `forklift [A:Ext; A:NoVoicing]`, `kollektivist [A:Ext; A:NoVoicing]`, `monetarist [A:Ext; A:NoVoicing]`, `narsisist [A:Ext; A:NoVoicing]`, `optometrist [A:Ext; A:NoVoicing]`, `ortodontist [A:Ext; A:NoVoicing]`, `pediyatrist [A:Ext; A:NoVoicing]`, `psikoterapist [A:Ext; A:NoVoicing]`, `sadomazohist [A:Ext; A:NoVoicing]`, `spiritualist [A:Ext; A:NoVoicing]`, `şovenist [A:Ext; A:NoVoicing]`, `taksonomist [A:Ext; A:NoVoicing]`, `talkşist [A:Ext; A:NoVoicing]`, `teorist [A:Ext; A:NoVoicing]` — all lose their `Ext` attribute completely.
  - **In `master-dictionary.dict` (2 entries)**: `individüalist [A:NoVoicing; A:NoVoicing]` and `nasyonalist [A:NoVoicing; A:NoVoicing]`.

### 3. Corrupted `CompoundP3sg` & `Roots:` Entries (20+ entries)
For `CompoundP3sg` entries, `TurkishDictionaryLoader` takes `Roots:x-y`, strips `-` to create a dummy stem `xy`, and attaches it to the main lemma. Several entries in `master-dictionary.dict` and `non-tdk.dict` have typos or wrong roots in `Roots:`:

- **Typos in `Roots:`**:
  - `bacabaşı [A:CompoundP3sg; Roots:bacak-baş]` → creates dummy root `bacakbaş` instead of `bacabaş`!
  - `borazancıbaşı [A:CompoundP3sg; Roots:borazan-baş]` → creates `borazanbaş` instead of `borazancıbaş`!
  - `güveyfeneri [A:CompoundP3sg; Roots:güney-fener]` → `güneyfener` instead of `güveyfener`!
  - `hasekiküpesi [A:CompoundP3sg; Roots:heseki-küpe]` → `hesekiküpe` instead of `hasekiküpe`!
  - `havvaanaeli [A:CompoundP3sg; Roots:hayvan-el]` → `hayvanel` instead of `havvaanael`!
  - `tavuskuyruğu [A:CompoundP3sg; Roots:tavuk-kuyruk]` → `tavukkuyruk` instead of `tavuskuyruk`!
  - `yanıbaşı [A:CompoundP3sg, Ext; Roots:yan-baş]` → `yanbaş` instead of `yanıbaş`!
  - `aşcıbaşı [A:CompoundP3sg, Ext; Roots:aşçı-baş]` → `aşçıbaş` mismatch with `aşcıbaşı`.
- **Truncated verbal/nominal compound roots in `non-tdk.dict`**:
  - `düşkırıklığı [Roots:düş-kırık]` (should be `düş-kırıklık`)
  - `elbirliği [Roots:el-bir]` (should be `el-birlik`)
  - `elyazması [Roots:el-yaz]` (should be `el-yazma`)
  - `güvenoylaması [Roots:güven-oyla]` (should be `güven-oylama`)
  - `sözvarlığı [Roots:söz-var]` (should be `söz-varlık`)
  - `yabanasması [Roots:yaban-as]` (should be `yaban-asma`)
- **Non-P3sg words falsely marked `CompoundP3sg`**:
  - `büyükşehir [A:CompoundP3sg; Roots:büyük-şehir]`, `ortaparmak [A:CompoundP3sg, Ext; Roots:orta-parmak]`, `acemlale`, `acıçiğdem`, `acıyonca` — these do not end in `-(s)I` (`P3sg`) at all! Marking them `CompoundP3sg` makes Zemberek generate/analyze `*büyükşehirlere` as `büyükşehir+ler+i` or drop suffixes.

### 4. Redundant / Conflicting Cross-Dictionary Duplicates (`master-dictionary` vs `non-tdk`)
- Since commit `#342` added native optional `LastVowelDrop` support, entries in `non-tdk.dict` like `avuç [A:Ext; Index:1; Ref:avuç_Noun]`, `atıf`, `fuhuş`, `kabız`, `mühür`, `omuz` (which were originally added as workarounds for non-dropping vowel variants) or duplicate `Index:1` entries in `master-dictionary.dict` (`ağız`, `emir`, `kadir`, `katil`, `kayın`, `koyun`) should be checked to see which ones are now redundant or conflicting.
- Also `müslüman [A:Ext; Ref:Müslüman_Noun_Prop]` in `non-tdk.dict` points to a non-existent `Ref` ID (logs a warning on every dictionary load).

### 5. Redundant / Over-Generating Multi-POS Entries
- Check words that have both `Adj` and `Noun` or `Adj` and `Adv` where one is strictly derivable via `Zero` morpheme in Zemberek’s morphotactics, vs. where both are needed in the lexicon.
- Check words where `Verb` and `Noun`/`Adj` collide or where POS tags are malformed.

### 6. `abbreviations.dict` Quality & Pronunciation (`Pr:`) Audit
Currently `abbreviations.dict` has 1,478 entries with a comment at the top: `## collected from a corpus, has missing properties and mistakes [P:Abbrv]`.
- **Capitalization**: Almost all entries are Titlecase (`Akp`, `Atm`, `Bbc`, `Tübitak`) instead of standard uppercase (`AKP`, `ATM`, `BBC`, `TÜBİTAK`).
- **Noise words**: Because it was auto-extracted from a corpus years ago, it contains many 2–3 letter English words and noise tokens tagged as Turkish abbreviations: `Add`, `Age`, `Air`, `All`, `And`, `Are`, `Bad`, `Bee`, `Big`, `Boo`, `Boy`, `Bus`, `But`, `Buy`, `Can`, `Car`, `Cat`, `Cry`, `Cut`, `Dad`, `Day`, `Die`, `Dog`, `Dry`, `Eat`, `End`, `Eye`, `Far`, `Fat`, `Few`, `Fly`, `For`, `Fun`, `Get`, `God`, `Got`, `Gun`, `Guy`, `Had`, `Has`, `Hat`, `Her`, `Him`, `His`, `Hot`, `How`, etc.
- **Missing or wrong pronunciations (`Pr:`)**: E.g., `B1 [Pr:bebirr; P:Abbrv]` (typo `bebirr`), and many English-pronounced acronyms (`PDF`, `URL`, `USB`, `VIP`, `WIFI`, `CEO`, `DNA`, `FBI`, `CIA`, `NBA`, `UEFA`, `FIFA`, `NATO`, `UNESCO`, `UNICEF`) need proper `Pr:` hints so suffix vowel harmony and voicing work (`PDF'e` / `PDF'ye`, `CEO'su`, `USB'ye`).

---

## Track 2: Discovering Missing Words & Abbreviations (Last 7 Years)

To find genuinely missing words for `master-dictionary.dict`, `non-tdk.dict`, and `abbreviations.dict` without polluting the lexicon with typos or inflected forms:

### 1. Corpus-Driven OOV Mining (Using Existing Repo Data & Models)
- Scan the vocabularies already in the repo/models (such as `lm.2gram.slm.gz`, `normalization/v2/knowledge.bin.gz`, test corpora, and word frequency lists) and run every word through `TurkishMorphology.createWithDefaults()`.
- Filter for high-frequency words that either:
  - **Fail morphological analysis completely (`Unk`)**, or
  - **Only analyze via `UnidentifiedTokenAnalyzer` / over-segmented suffixes** (e.g., a root word being misanalyzed as a shorter unrelated stem + 4 suffixes).

### 2. Categorizing Missing Vocabulary (2019–2026 Turkish Usage)
- **`master-dictionary.dict` (Standard / TDK words)**:
  - Modern TDK additions & standard words missing from our snapshot (e.g., *farkındalık*, *sürdürülebilir*, *sürdürülebilirlik*, *dijitalleşmek*, *dijitalleşme*, *küreselleşmek*, *özçekim*, *yapay zekâ* components, *çevrimiçi*, *çevrimdışı*, *ekosistem*, *biyoteknoloji*, *nanoteknoloji*, *nörobilim*, *veri*, *algoritma* derivatives, etc.).
- **`non-tdk.dict` (Common modern Turkish, tech, pandemic/health, finance, daily loanwords)**:
  - Tech/digital/daily terms: *kripto*, *blokzincir* / *blockchain*, *podcast*, *influencer*, *story*, *reels*, *tweet* / *tweetlemek*, *retweetlemek*, *hashtag*, *linklemek*, *banlamak*, *hacklemek*, *trollemek*, *spamlamak*, *scrollamak*, *formatlamak*, *resetlemek*, *güncellemek*, *indirmek*, *yüklemek*, *çevrimiçi*, *kurye*, *e-ticaret*, *fintek*, *start-up*, *unicorn*, *metaverse*, *avatar*, *piksel*, *sensör*, *drone* / *dron*, *scooter* / *skuter*, *airfryer*, *barista*, *vegan*, *vejetaryen*, *glutensiz*, *detoks*, *pandemi*, *koronavirüs*, *varyant*, *entübe*, *pcr*, *antijen*, *antikor*, *karantina*, *izolasyon*, *dezenfektan*, *mobilite*, *lojistik*, *inovasyon*, *inovatif*, *proaktif*, *interaktif*, *optimize*, *entegre*, *simüle*, *manipüle*, *provoke*, *sabote*, *adapte*, *motive*, *konsantre*, *dezenformasyon*, *manipülasyon*, *spekülasyon*, *enflasyonist*, *resesyon*, *stagflasyon*, etc.
  - Common spelling variants without `^` or with `-etmek/-olmak` that appear frequently in modern Turkish text.
- **`abbreviations.dict` (Modern Turkish & International Acronyms)**:
  - Turkish institutions, exams, regulations, finance, tech: `KVKK`, `BDDK`, `SPK`, `EPDK`, `BTK`, `RTÜK`, `YÖK`, `ÖSYM`, `YKS`, `TYT`, `AYT`, `LGS`, `KPSS`, `ALES`, `YDS`, `YÖKDİL`, `DGS`, `MSÜ`, `MEB`, `AFAD`, `TOKİ`, `TÜİK`, `TÜBİTAK`, `ASELSAN`, `ROKETSAN`, `HAVELSAN`, `TUSAŞ`, `TEKNOFEST`, `TOGG`, `İHA`, `SİHA`, `TİHA`, `KYK`, `SGK`, `İŞKUR`, `KOSGEB`, `CİMER`, `MHRS`, `EBA`, `UYAP`, `MERNİS`, `ETBİS`, `FAST`, `EFT`, `IBAN`, `POS`, `KDV`, `ÖTV`, `MTV`, `BİST`, `TL`, `USD`, `EUR`, `GBP`.
  - Tech/Everyday acronyms with proper Turkish pronunciations (`Pr:`): `AI`, `API`, `SDK`, `CPU`, `GPU`, `RAM`, `ROM`, `SSD`, `HDD`, `USB`, `HDMI`, `LCD`, `LED`, `OLED`, `WIFI`, `GPS`, `SIM`, `PIN`, `PUK`, `SMS`, `MMS`, `URL`, `HTML`, `CSS`, `JSON`, `XML`, `SQL`, `HTTP`, `HTTPS`, `VPN`, `DNS`, `IP`, `TCP`, `UDP`, `PDF`, `JPEG`, `PNG`, `GIF`, `MP3`, `MP4`, `QR`, `VR`, `AR`, `NFT`, `CEO`, `CTO`, `CFO`, `İK`, `ARGE` (`Ar-Ge`), `KOBİ`, `STK`, `AŞ`, `LTD`, `KDV`, `ÖTV`.

### 3. Regenerating `lexicon.bin` & Running Full Regression Suite
- In Zemberek, runtime `RootLexicon.getDefault()` loads the serialized protobuf `morphology/src/main/resources/tr/lexicon.bin`, which is generated from the `.dict` files.
- Any changes to `.dict` files must be followed by regenerating `lexicon.bin` and running all unit + functional tests across `morphology`, `normalization`, and `tokenization`.

---

## Proposed Step-by-Step Plan

1. **Step 1 — Full Integrity Report**: Run a comprehensive audit script on `master-dictionary.dict`, `non-tdk.dict`, and `abbreviations.dict` to produce the complete list of broken/inconsistent entries (multi-POS attribute mismatches, overwritten `[A:...; A:...]` tags, broken `CompoundP3sg` `Roots:`/`Ref:` entries, and noise in `abbreviations.dict`).
2. **Step 2 — Fix Integrity Bugs**: Fix those structural and multi-POS bugs in `master-dictionary.dict`, `non-tdk.dict`, and `abbreviations.dict` (and add a unit test that prevents them from ever regressing).
3. **Step 3 — Mine & Curate Missing Words**: Extract high-frequency unparsed/under-parsed words from corpora/models + curate modern Turkish words & abbreviations for `master-dictionary.dict`, `non-tdk.dict`, and `abbreviations.dict`, review them, and regenerate `lexicon.bin`.
