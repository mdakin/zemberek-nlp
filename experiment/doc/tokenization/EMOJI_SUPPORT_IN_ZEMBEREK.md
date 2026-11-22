# Unicode Emoji Support in Zemberek-NLP

**Date**: `2026-11-14`  
**Modules Affected**: `zemberek-tokenization`, `zemberek-morphology`, `zemberek-normalization`  
**Pull Request**: `ahmetaa/zemberek-nlp#380`

---

## 1. Background & Prior Failure Modes

Prior to this change, [Token.java](file:///home/dndara/work/zemberek-nlp/tokenization/src/main/java/zemberek/tokenization/Token.java#L100-L125) defined `Token.Type.Emoji` and `token.isEmoji()`, and [NormalizationVocabularyGenerator.java](file:///home/dndara/work/zemberek-nlp/normalization/src/main/java/zemberek/normalization/NormalizationVocabularyGenerator.java#L212) already checked `token.isEmoji()`, while ASCII emoticons (`:)`, `:D`, `<3`, `¯\_(ツ)_/¯`) were handled via `TurkishLexer.Emoticon`. However, **Unicode emojis were never wired in `TurkishLexer.g4` or `TurkishTokenizer`**, causing five concrete bugs across tokenization and normalization:

| # | Bug | Concrete Example (Before Fix) | Root Cause |
| :-: | :--- | :--- | :--- |
| **1** | **`Token.Type.Emoji` never emitted** | `"🥰"` → `[🥰 UnknownWord]` (`isEmoji() == false`) | No `Emoji` rule in [TurkishLexer.g4](file:///home/dndara/work/zemberek-nlp/tokenization/src/main/resources/tokenization/TurkishLexer.g4) and no `case TurkishLexer.Emoji:` in [TurkishTokenizer.java](file:///home/dndara/work/zemberek-nlp/tokenization/src/main/java/zemberek/tokenization/TurkishTokenizer.java). |
| **2** | **Glued words swallowed into `UnknownWord`** | `"ortaya cıkıyo😊"` → `"ortaya cıkıyo😊"` (`cıkıyo` unnormalized)<br>`"sen❤️❤️❤️bys"` → `[sen❤️❤️❤️bys UnknownWord]` | ANTLR4 maximal-munch matched glued word+emoji runs via `UnknownWordPart` over `Word`. |
| **3** | **Silent BMP emoji deletion in spellchecker** | `"tesekkur ederim☺"` → **`"teşekkür ederim"`** (`☺` silently deleted!) | `☺` (`U+263A`) and `❤` (`U+2764`) are 1 UTF-16 `char`; `"ederim☺"` entered 1-edit `TurkishSpellChecker` and matched `"ederim"`. |
| **4** | **Codepoint vs. UTF-16 `char` offset drift** | `"🥰 cok guzel"` (`🥰` = 1 codepoint, 2 `char`s) → `[cok Word 2-4]` instead of UTF-16 `3-5` | `CharStreams.fromString` emits Unicode codepoint indices, whereas all Zemberek callers (`NormalizerPreprocessor.deasciifyValidWords`, `String.substring`) index UTF-16 `char`s. |
| **5** | **Wasted morphology & beam-search CPU on non-word tokens** | `"😂😂😂"` (`UTF-16 len = 6`) or `"👨‍👩‍👧‍👦"` (`len = 11`) ran through `morphology.analyze` + `suggestForWord` | [TurkishSentenceNormalizer.java](file:///home/dndara/work/zemberek-nlp/normalization/src/main/java/zemberek/normalization/TurkishSentenceNormalizer.java) passed raw `String` text to `collectTokenCandidates` without checking `Token` type or letter presence. |

---

## 2. ANTLR `4.7.2` Unicode Property Constraints

Zemberek generates [TurkishLexer.java](file:///home/dndara/work/zemberek-nlp/tokenization/src/main/java/zemberek/tokenization/antlr/TurkishLexer.java) offline with **ANTLR `4.7.2`**, which bundles **Unicode 10.0 (ICU 60)** property tables at grammar compile time. Empirical testing against `antlr4-4.7.2-complete.jar` established three critical properties:

> [!IMPORTANT]
> In Unicode 10.0 (UTS #51 v5.0), `\p{Extended_Pictographic}` was defined solely as the **unassigned reserved pictographic ranges** (which is why it matches newer post-2017 emojis like `🥰 U+1F970`, `🫶 U+1FAF6`, `🫠 U+1FAE0`, `🩷 U+1FA77`, plus `♀/♂`), whereas already-assigned emojis (`😀 U+1F600`, `😂 U+1F602`, `👍 U+1F44D`, `🙏 U+1F64F`, `✅ U+2705`) reside in **`\p{Emoji_Presentation}`**. Conversely, `\p{Emoji}` cannot be used directly because it includes bare ASCII digits and symbols (`0–9`, `#`, `*`, `©`, `®`, `™`).

Combining the two disjoint sets `\p{Emoji_Presentation}` and `\p{Extended_Pictographic}` plus the 12 high-frequency BMP text-default symbols observed across 500k+ Turkish social/review sentences (`☀☘☝☹☺♥♻✌✔❣❤➡`) compiles with **zero ANTLR warnings** and covers both classic BMP symbols/smileys and modern Unicode 11–16+ emojis without colliding with ASCII digits or punctuation.

---

## 3. Minimal Implementation Across Modules

### 3.1 Tokenizer (`zemberek-tokenization`)

1. **Minimal `Emoji` Lexer Rules ([TurkishLexer.g4](file:///home/dndara/work/zemberek-nlp/tokenization/src/main/resources/tokenization/TurkishLexer.g4#L200-L208))**:
   ```antlr
   fragment RegionalIndicator: [\u{1F1E6}-\u{1F1FF}];
   fragment EmojiModifier: [\uFE0E\uFE0F\u{1F3FB}-\u{1F3FF}];
   fragment EmojiBase: [\p{Emoji_Presentation}\p{Extended_Pictographic}☀☘☝☹☺♥♻✌✔❣❤➡] EmojiModifier*;

   Emoji
       : RegionalIndicator RegionalIndicator
       | EmojiBase ('\u200D' EmojiBase)*
       ;
   ```
   - Matches standalone BMP & SMP emojis (`❤`, `❤️`, `☺`, `☝️`, `✔️`, `😂`, `🥰`, `🫠`, `🩷`, `🫶`), skin-tone modifiers (`👍🏽`), flag pairs (`🇹🇷`), and ZWJ sequences (`👨‍👩‍👧‍👦`, `🤦‍♀️`) as single `Type.Emoji` tokens.
   - Consecutive emojis (`"😂😂😂"`) tokenize into individual `Emoji` tokens (`["😂", "😂", "😂"]`), consistent with how `TurkishLexer` tokenizes repeated punctuation (`"!!!"`) and emoticons (`":):)"`).
2. **`UnknownWordPart` Exclusion & ANTLR4 `error(183)` Constraint ([TurkishLexer.g4](file:///home/dndara/work/zemberek-nlp/tokenization/src/main/resources/tokenization/TurkishLexer.g4#L245-L251))**:
   - ANTLR4 rejects fragment rule references inside negated sets `~(...)` (`error(183): rule reference ... is not currently supported in a set` — the same reason `PunctuationFragment` and `Apostrophe` are listed inline in `UnknownWordPart`).
   - Excludes the exact same `[\p{Emoji_Presentation}\p{Extended_Pictographic}☀☘☝☹☺♥♻✌✔❣❤➡]` character class on its own line in `UnknownWordPart`, cleanly splitting glued forms (`"etdimm🥰"` → `Word("etdimm")` + `Emoji("🥰")`, `"kötü😡ama"` → `Word("kötü")` + `Emoji("😡")` + `Word("ama")`). Variation selectors (`\uFE0E`, `\uFE0F`) and `\u200D` are intentionally *not* excluded from `UnknownWordPart`, so any unmapped symbol with a variation selector (e.g. `"▪\uFE0F"`) stays intact as a single `UnknownWord` token rather than splitting off an invisible standalone modifier token.
3. **Lexer Regeneration Command**:
   From the repository root (requires `antlr4-4.7.2-complete.jar` and 2-space indentation):
   ```bash
   java -jar ~/.m2/repository/org/antlr/antlr4/4.7.2/antlr4-4.7.2-complete.jar \
     -encoding UTF-8 -Xexact-output-dir -o /tmp/zemberek-lexer \
     tokenization/src/main/resources/tokenization/TurkishLexer.g4
   sed 's/\t/  /g' /tmp/zemberek-lexer/TurkishLexer.java \
     > tokenization/src/main/java/zemberek/tokenization/antlr/TurkishLexer.java
   cp /tmp/zemberek-lexer/TurkishLexer.tokens \
     tokenization/src/main/java/zemberek/tokenization/antlr/TurkishLexer.tokens
   cp /tmp/zemberek-lexer/TurkishLexer.tokens \
     tokenization/src/main/resources/tokenization/TurkishLexer.tokens
   ```
4. **UTF-16 `start`/`end` Offset Invariant ([TurkishTokenizer.java](file:///home/dndara/work/zemberek-nlp/tokenization/src/main/java/zemberek/tokenization/TurkishTokenizer.java#L138-L300))**:
   - Wired `case TurkishLexer.Emoji: return Type.Emoji;` in `convertType()`.
   - Updated `TokenIterator` with a single `surrogateDelta` counter (`surrogateDelta += text.length() - (stop - start + 1)` on `Emoji`, `UnknownWord`, and `Unknown`), preserving zero extra `String` allocations on BMP tokens (`SpaceTab`, `NewLine`, `Word`, `Number`, `Punctuation`, etc.).
   - Guarantees `input.substring(token.getStart(), token.getEnd() + 1).equals(token.getText())` for every token, even after supplementary emojis, abbreviation lookahead (`"🥰 Dr. Ahmet"`), or when `Type.Emoji` is ignored via `TurkishTokenizer.Builder`.

### 3.2 Morphology (`zemberek-morphology`)

- In [UnidentifiedTokenAnalyzer.java](file:///home/dndara/work/zemberek-nlp/morphology/src/main/java/zemberek/morphology/analysis/UnidentifiedTokenAnalyzer.java#L106-L108), mapped `case Emoji:` alongside `case Emoticon:` to `SecondaryPos.Emoticon` (`nounNoSuffix_S` in `TurkishMorphotactics`), requiring zero changes to `lexicon.proto` or binary lexicon serialization while ensuring `morphology.analyze("🥰")` and `morphology.analyzeAndDisambiguate("güzel 🥰")` succeed cleanly.

### 3.3 Normalization (`zemberek-normalization`)

- **Preprocessor & Deasciifier ([NormalizerPreprocessor.java](file:///home/dndara/work/zemberek-nlp/normalization/src/main/java/zemberek/normalization/NormalizerPreprocessor.java#L169-L227))**:
  - `NormalizerPreprocessor.isWord(Token)` already excludes `Type.Emoji`, so emoji tokens bypass contraction, elongation, split/merge, and `deasciifyValidWords` word rewriting while newly separated words (`"cıkıyo"` → `"çıkıyor"`, `"cok"` → `"çok"`) normalize normally.
  - Because `TurkishTokenizer` now emits exact UTF-16 `start`/`end` indices, legacy `deasciifyValidWords("🥰 cok guzel olmus", false)` no longer shifts substring indices or corrupts surrogate pairs.
  - `LatticeDeasciifier` ([TurkishTextCodec.java](file:///home/dndara/work/zemberek-nlp/normalization/src/main/java/zemberek/normalization/deasciifier/latticedeasciifier/TurkishTextCodec.java#L417-L537)) already scans UTF-16 `char` spans and stops word spans at non-letters (`"cok😍"` → `"çok😍"`).
- **Candidate Collection & Spellcheck Guards ([TurkishSentenceNormalizer.java](file:///home/dndara/work/zemberek-nlp/normalization/src/main/java/zemberek/normalization/TurkishSentenceNormalizer.java#L192-L246))**:
  - `isImmutableToken(Token)` short-circuits `Emoji`, `Emoticon`, `Punctuation`, `URL`, `Email`, `HashTag`, `Mention`, `MetaTag`, and `Unknown` to a single zero-penalty identity candidate, avoiding wasted morphology lookups and spellcheck beam searches.
  - `isSpellCheckableWord(String)` uses a character whitelist (`Character.isLetter(cp)` plus only apostrophes, hyphens, and Unicode combining marks), ensuring digits, surrogates, format characters, and any unmapped/currency symbols (`"iyi¬"`, `"iyi₹"`, `"―Osmanlİ"`) are never silently deleted by 1-edit spellchecking.

---

## 4. Empirical Benchmark Evaluation

Evaluated via [EvaluateNormalizationBenchmarks.java](file:///home/dndara/work/zemberek-nlp/experiment/scripts/normalization/EvaluateNormalizationBenchmarks.java) against `master` (`93857d38`):

| Dataset | Metric | `master` (`93857d38`) | `PR #380` | Delta |
| :--- | :--- | ---: | ---: | ---: |
| **`nasty_5k`** (`5,000` sents) | `SysEditDist` / `W-ERR` / `SentAcc` | `6,570` / `81.21%` / `39.36%` | **`6,562` / `81.23%` / `39.42%`** | **`-8` word errors (`+3` exact)** |
| **`MultiLexNorm TR`** (`143` tweets) | `SysEditDist` / `WER` / `SentAcc` | `220` / `14.04%` / `34.27%` | **`220` / `13.64%` / `34.27%`** | **`-0.40 pp` WER** |
| **`BOUN Clean Control`** (`510` sents) | `CorruptedEdits` / `SentPreserved` | `140` / `87.65%` (`447`) | **`131` / `88.24%` (`450`)** | **`-9` false edits (`+3` clean)** |
| **`GECTurk Noisy`** (`506` sents) | `SysEditDist` / `WER` | `1,285` / `13.13%` | **`1,278` / `13.06%`** | **`-7` word errors** |

---

## 5. Future Extensions (Phase 2 Roadmap)

When broader emoji edge-case coverage is needed, the minimal grammar can be extended incrementally:
1. **Keycap Sequences**: Add `[0-9#*] '\uFE0F'? '\u20E3'` to `Emoji` so `"1️⃣"` and `"#️⃣"` tokenize as `Type.Emoji` rather than `Number` + `Unknown`.
2. **Subdivision Tag Flags**: Add `[\u{1F3F4}] [\u{E0020}-\u{E007E}]+ [\u{E007F}]` (`🏴󠁧󠁢󠁥󠁮󠁧󠁿`).
3. **Emoji-Presented Legal/Punctuation Symbols**: Match `[©®™‼⁉] '\uFE0F'` as `Emoji` while keeping bare `©`/`®`/`™` in `Punctuation`.
4. **Dedicated `SecondaryPos.Emoji` (Optional)**: If downstream morphological disambiguation or tagging distinguishes graphical emojis from ASCII emoticons, add `Emoji` to `SecondaryPos` and `lexicon.proto`.
