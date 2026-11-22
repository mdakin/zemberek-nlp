# Turkish Circumflex (`^`) Handling & Zemberek Lexicon Notes

## 1. Why Circumflexes (`â`, `î`, `û`) Differ from Core Turkish Diacritics

A Turkish deasciifier restores the **6 mandatory letter pairs missing from an ASCII keyboard** (`c/ç`, `g/ğ`, `ı/i`, `I/İ`, `o/ö`, `s/ş`, `u/ü`). Omitting any of these breaks Turkish orthography and changes lemmas/POS downstream (`oldu`/`öldü`, `sakin`/`sakın`, `ag`/`ağ`, `mudur`/`müdür`).

By contrast, *düzeltme işareti* (`â`, `î`, `û`) conflates three very different roles:

1. **Lexical disambiguation (~15–25 minimal pairs):** Removing `^` merges distinct words:
   - Common minimal pairs: `kar`/`kâr`, `hala`/`hâlâ`, `adet`/`âdet`, `ama`/`âmâ`, `dahi`/`dâhi`, `aşık`/`âşık`, `hakim`/`hâkim`, `alem`/`âlem`, `yar`/`yâr`.
   - Nisba (`-î`) adjectives colliding with 3rd-person possessive or accusative nouns: `resmi`/`resmî`, `askeri`/`askerî`, `tarihi`/`tarihî`, `milli`/`millî`, `dini`/`dinî`, `ilmi`/`ilmî`.
2. **Palatalization / vowel-length hints on unambiguous stems:** `rüzgâr`, `hikâye`, `dükkân`, `kâğıt`, `imkân`, `mahkûm`. No competing plain word exists, so modern news and web authors omit `^` roughly 85–95% of the time.
3. **Obsolete pre-reform spellings:** Archaic `lâ`/`lû` forms (`ilâç`, `silâh`, `belâ`, `selâm`) that TDK retired decades ago.

Because publisher conventions vary wildly on (2) and (3), corpus-wide `Strict` (`^`-sensitive) scoring penalizes editorial taste rather than real errors—over 80% of the gap between `Sent(No^)` and `Sent(Str)` in [LatticeDeasciifierBenchmark.java](file:///home/dndara/work/zemberek-nlp/experiment/src/main/java/zemberek/normalization/deasciifier/benchmark/LatticeDeasciifierBenchmark.java#L464-L505) comes from `^` style variation.

---

## 2. Zemberek Lexicon & Morphology Issues

Zemberek's default dictionary resources (`TurkishDictionaryLoader.DEFAULT_DICTIONARY_RESOURCES`) contain **1,442 active circumflex entries** (`1,130` in [master-dictionary.dict](file:///home/dndara/work/zemberek-nlp/morphology/src/main/resources/tr/master-dictionary.dict), `209` in [person-names.dict](file:///home/dndara/work/zemberek-nlp/morphology/src/main/resources/tr/person-names.dict), `103` in [proper.dict](file:///home/dndara/work/zemberek-nlp/morphology/src/main/resources/tr/proper.dict)), plus 554 retired entries in [tdk-obsolete.dict](file:///home/dndara/work/zemberek-nlp/morphology/src/main/resources/tr/tdk-obsolete.dict). Several inconsistencies affect any circumflex-aware component:

- **Obsolete `lâ`/`lû` lemmas still active (427 entries):** Words already listed in [tdk-obsolete.dict](file:///home/dndara/work/zemberek-nlp/morphology/src/main/resources/tr/tdk-obsolete.dict) remain the *only* active lemma in [master-dictionary.dict](file:///home/dndara/work/zemberek-nlp/morphology/src/main/resources/tr/master-dictionary.dict)—including `belâ` ([master-dictionary.dict](file:///home/dndara/work/zemberek-nlp/morphology/src/main/resources/tr/master-dictionary.dict#L2689)), `ilâç` ([master-dictionary.dict](file:///home/dndara/work/zemberek-nlp/morphology/src/main/resources/tr/master-dictionary.dict#L10765)), and `silâh` ([master-dictionary.dict](file:///home/dndara/work/zemberek-nlp/morphology/src/main/resources/tr/master-dictionary.dict#L21341)), so analyzing `"ilaç"` or `"silah"` emits `ilâç`/`silâh` as the lemma. Recent loanwords also retain archaic circumflexes such as `klârnet` ([master-dictionary.dict](file:///home/dndara/work/zemberek-nlp/morphology/src/main/resources/tr/master-dictionary.dict#L13401)), `lûnapark` ([master-dictionary.dict](file:///home/dndara/work/zemberek-nlp/morphology/src/main/resources/tr/master-dictionary.dict#L14911)), and `transplântasyon` ([master-dictionary.dict](file:///home/dndara/work/zemberek-nlp/morphology/src/main/resources/tr/master-dictionary.dict#L24390)).
- **118 duplicate / inconsistent common-word pairs:**
  - *Unlinked duplicates:* `bekar` / `bekâr` ([master-dictionary.dict](file:///home/dndara/work/zemberek-nlp/morphology/src/main/resources/tr/master-dictionary.dict#L2676-L2677)), `ilaçlamak` ([master-dictionary.dict](file:///home/dndara/work/zemberek-nlp/morphology/src/main/resources/tr/master-dictionary.dict#L28829)) / `ilâçlamak` ([master-dictionary.dict](file:///home/dndara/work/zemberek-nlp/morphology/src/main/resources/tr/master-dictionary.dict#L10766)).
  - *Contradictory `Ref` direction:* `mecburî [P:Adj; Ref:mecburi_Adj]` points to the plain lemma, whereas `sükunet [A:Ext,NoVoicing; Ref:sükûnet]` and `mahkumiyet [A:Ext; Index:1; Ref:mahkûmiyet]` point from plain to `^`.
  - *POS-tag drift across twins:* `tesadüfi [P:Adv]` ([master-dictionary.dict](file:///home/dndara/work/zemberek-nlp/morphology/src/main/resources/tr/master-dictionary.dict#L26929)) vs. `tesadüfî` ([master-dictionary.dict](file:///home/dndara/work/zemberek-nlp/morphology/src/main/resources/tr/master-dictionary.dict#L23828), untagged, so loaded as `Noun`); `evvela [P:Adv]` ([master-dictionary.dict](file:///home/dndara/work/zemberek-nlp/morphology/src/main/resources/tr/master-dictionary.dict#L26883)) vs. `evvelâ [P:Adj]` ([master-dictionary.dict](file:///home/dndara/work/zemberek-nlp/morphology/src/main/resources/tr/master-dictionary.dict#L7420)).
  - *Missing true minimal pairs:* `katil` ([master-dictionary.dict](file:///home/dndara/work/zemberek-nlp/morphology/src/main/resources/tr/master-dictionary.dict#L12507-L12508)) and `kadir` ([master-dictionary.dict](file:///home/dndara/work/zemberek-nlp/morphology/src/main/resources/tr/master-dictionary.dict#L11688-L11690)) lack `kâtil` / `kâdir` entries.
- **Root vs. Lemma asymmetry:** `TurkishDictionaryLoader.generateRoot(...)` ([TurkishDictionaryLoader.java](file:///home/dndara/work/zemberek-nlp/morphology/src/main/java/zemberek/morphology/lexicon/tr/TurkishDictionaryLoader.java#L387)) strips circumflexes when building `DictionaryItem.root`, so `WordGenerator` never generates `â/î/û`—masking obsolete dictionary lemmas during normal generation. When [MorphologicalCandidateGenerator.java](file:///home/dndara/work/zemberek-nlp/normalization/src/main/java/zemberek/normalization/deasciifier/latticedeasciifier/MorphologicalCandidateGenerator.java#L302-L305) re-projects `^` from `item.normalizedLemma()`, all 1,442 lexicon entries (including `ilâç`, `silâh`, `belâ`) enter the lattice candidate pool.

---

## 3. Design Options for Circumflex Handling

| Mode | Behavior | Pros | Trade-offs |
| :--- | :--- | :--- | :--- |
| **`NONE`** | Restore only `{ç, ğ, ı, İ, ö, ş, ü}`; preserve any `^` already in input, never insert new `^`. | Zero editorial ambiguity; matches legacy `Deasciifier` and modern Turkish; `TurkishMorphology` still analyzes `kar`/`hala` into both lemmas. | Human-facing output does not distinguish `kâr`/`kar` or `hâlâ`/`hala`. |
| **`DISAMBIGUATION_ONLY`** | Restore `^` **only** for a small explicit whitelist of disambiguating minimal pairs; fold all other `^` during prior mining & candidate generation; preserve input `^`. | Resolves true semantic ambiguities (`kar`/`kâr`, `hala`/`hâlâ`, `adet`/`âdet`) where lattice context helps, without noisy or archaic `^` elsewhere. | Small whitelist boundary is partly editorial (e.g. whether to include `-î` nisba forms like `resmî`/`askerî`). |
| **`FULL`** (current) | Generate `^` variants for all 1,442 lexicon entries and let corpus counts/perceptron choose. | Can recover traditional TDK spellings (`rüzgâr`, `hikâye`) when corpus priors support them. | Exposes obsolete dictionary `^` (`ilâç`, `silâh`); behavior depends heavily on publisher mix in training data. |

---

## 4. Recommendation

1. **Add `CircumflexMode { NONE, DISAMBIGUATION_ONLY, FULL }` in [LatticeDeasciifier.java](file:///home/dndara/work/zemberek-nlp/normalization/src/main/java/zemberek/normalization/deasciifier/latticedeasciifier/LatticeDeasciifier.java):**
   - Use **`DISAMBIGUATION_ONLY`** with a tight ~12-lemma whitelist (`kâr`, `hâlâ`, `âdet`, `âmâ`, `dâhi`, `âşık`, `hâkim`, `âlem`, `yâr`, `resmî`, `askerî`, `millî`) for general text output, and **`NONE`** inside NLP pipelines such as [TurkishSentenceNormalizer.java](file:///home/dndara/work/zemberek-nlp/normalization/src/main/java/zemberek/normalization/TurkishSentenceNormalizer.java) (where downstream morphology already disambiguates plain `kar`/`hala`). Always preserve any `â/î/û` present in the input.
2. **Standardize evaluation on `No^` + minimal-pair checks:**
   - Use **`No^`** (`Sent(No^)` / `DiacWord`) as the single headline metric across general corpora in [LatticeDeasciifierBenchmark.java](file:///home/dndara/work/zemberek-nlp/experiment/src/main/java/zemberek/normalization/deasciifier/benchmark/LatticeDeasciifierBenchmark.java), and verify disambiguating `^` pairs on a dedicated minimal-pair suite.
3. **Clean up Zemberek's lexicon in a separate PR:**
   - Normalize obsolete `lâ`/`lû` lemmas in [master-dictionary.dict](file:///home/dndara/work/zemberek-nlp/morphology/src/main/resources/tr/master-dictionary.dict) (`ilâç` -> `ilaç`, `silâh` -> `silah`, `belâ` -> `bela`; drop archaic duplicates like `klârnet`, `lûnapark`, `transplântasyon`) while ensuring `[A:InverseHarmony]` is preserved wherever front-vowel harmony applies, and reconcile the POS/canonical `Ref` mismatches among twin entries.
