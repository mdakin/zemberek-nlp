# Zemberek `nasty_5k` Error Taxonomy & Batch Cataloging Specification

## Strict Process Rules (MANDATORY)
1. **Small-Batch Reading ONLY**: Read `scratch/nasty5k_errors_for_review.txt` using `read_file` in chunks of **AT MOST 25 LINES** (`EndLine - StartLine + 1 <= 25`).
2. **NO Auto-Tagging Scripts**: You are strictly FORBIDDEN from writing any Python, bash, or Java script to classify, regex-match, or heuristically tag sentences. Every batch of <=25 sentences MUST be read directly by you (the LLM) in context (`RAW`, `ZEM`, `GOLD`, `DIFFS`) and tagged using genuine Turkish linguistic & morphological judgment, appending the tagged JSONL lines via `write_file(..., Append=true)` after reading each 25-line batch.
3. **1-to-1 Diff Span Alignment**:
   - Each line in `nasty5k_errors_for_review.txt` corresponds to the exact same line number (`err_idx`) in `nasty5k_errors_for_review.jsonl`.
   - For each sentence, emit one JSON object per line in your output shard JSONL:
     ```json
     {"err_idx": 1, "id": "nasty5k_00001", "items": [{"zem": "bittercim", "gold": "bitterciğim", "cat": "INFORMAL_MORPH_GAP", "sub": "diminutive_cIm", "sec": "", "note": "-cIm not expanded to -cIğIm"}, ...]}
     ```
   - `items` MUST have the **exact same length and order** as the `DIFFS` list shown on that line (`['<zem>' != '<gold>'] ; ...`).
   - When a single `SequenceMatcher` diff span bundles two adjacent phenomena (e.g., `['Muji bende' != 'mesajı ben de']`), choose the primary `cat` and `sub` for the most salient error in that span, put the second phenomenon's `sub` code in `"sec"` (or `""` if single phenomenon), and mention both briefly in `"note"`.

## Allowed `cat` (10 Categories) and `sub` (25 Sub-Tags)

### 1. `CLITIC_SPLIT_MISSED`
Glued clitic/conjunction was not separated into its own token (often left glued or mutated into a case suffix / single word by spellcheck):
- `clitic_de_da_missed`: Glued `de`/`da` additive clitic missed or turned into locative `-dA` / another word (e.g. `fıkrımızıde -> fikrimizde` vs `fikrimizi de`, `sende -> sende` vs `sen de`, `serilerinide -> serilerinde` vs `serilerini de`).
- `clitic_ki_missed`: Glued `ki` conjunction missed (`ağlıyoki -> ağlıyoki` vs `ağlıyor ki`, `baktimki -> baktimki` vs `baktım ki`).
- `clitic_question_missed`: Glued question particle `mı/mi/mu/mü` (including inflected `mısın/musun/mıyım/muyuz/mıydı`) missed or turned into accusative/possessive (`zengınmı -> zengini` vs `zengin mi`, `denedınmı` vs `denedin mi`, `malmın -> malın` vs `mal mısın`).

### 2. `SPLIT_MERGE_COMPOUND_ERROR`
Token boundary errors other than missed `de/da/ki/mı` clitic splits:
- `false_split`: A single valid word was erroneously split into two tokens (`karısımı -> karısı mı` vs `karışımı`, `işde -> iş de` vs `işte`, `trafikde -> trafik de` vs `trafikte`).
- `false_merge`: Two separate input tokens were erroneously merged into one token (`canım sımdı -> canımsımdı` vs `canım şimdi`, `da bi -> dabi` vs `da bir`).
- `open_or_runon_missed`: Conventional two-word/solid compound error or multi-word run-on left unsplit/unmerged (`herşey -> herşey` vs `her şey`, `hiçbirşey` vs `hiçbir şey`, `heryerde` vs `her yerde`, `iş veren` vs `işveren`, `gosterentablomaalesef` vs `gösteren tablo maalesef`, `one` vs `o ne`).

### 3. `INFORMAL_MORPH_GAP`
Productive Turkish spoken/informal suffix contractions or variants mishandled:
- `diminutive_cIm`: Spoken affectionate `-cIm/-cUm` left unexpanded instead of `-cIğIm/-cUğUm` (`bittercim -> bitterciğim`, `türkancım -> türkancığım`).
- `neg_future_contraction`: Spoken negative future `-mIycAk/-mUcAk/-mIcAk/-mIcam/-mUcam` left unnormalized or inverted to positive (`olmucaksiniz -> Olucak'sınız` vs `olmayacaksınız`, `olmicak` vs `olmayacak`).
- `pos_future_or_optative_contraction`: Spoken positive future/participle (`-cAm/-cAn/-cAk/-cAnIz/-IcAk`), optative (`-yIm/-yAlIm`, e.g. `söliyim` vs `söyleyeyim`), or progressive contraction (`-yom/-yon/-yoz` when missed or mapped to wrong tense/participle, e.g. `satcanız -> satacaksınız` vs `satacağınız`).
- `narr_2sg_or_prog_variant`: Informal 2sg narrative `-mIşIn` accepted instead of `-mIşsIn` (`etmişin` vs `etmişsin`, `kazanmışın` vs `kazanmışsın`), or unparsed informal suffix combinations (`yapıomuss`, `ıstıyosanıız`).

### 4. `PHONETIC_COLLOQUIAL_LEXICAL`
Spoken phonetic reductions, assimilations, or leet/censored orthography in lexical stems:
- `soft_g_or_y_drop`: Colloquial `ğ` or `y` drop/coalescence in stems (`doru -> doğru`, `baya -> bayağı`, `böle -> böyle`, `saol -> sağ ol`, `die -> diye`, `dierlerine -> diğerlerine`, `iis -> iyisi`, `iimiş -> iyiymiş`).
- `colloquial_phonetic`: Spoken vowel epenthesis, consonant assimilation/devoicing, syncope, or phonetic spelling (`filimler -> filmler`, `fasla -> fazla`, `sora -> sonra`, `ananesini -> anneannesini`, `ipne -> ibne`, `fiatlarını -> fiyatlarını`).
- `leet_or_censored`: Non-Turkish/leet character substitutions (`w->v`, `q->k`, `x->ks`, `$->ş`, `sh->ş`, `ch->ç`, `ue->ü`) or self-censored punctuation (`war -> var`, `walla -> valla`, `bi$eye -> bir şeye`, `s.carken -> sıçarken`).

### 5. `SLANG_SHORTHAND_UNEXPANDED`
SMS/chat vowel-less abbreviations, internet shorthand, or `bide`:
- `slang_or_abbr_unexpanded`: Unexpanded or mis-corrected SMS/chat shorthand (`msjı -> Muji` vs `mesajı`, `kdr -> kadar`, `hg -> hoş geldin`, `ins -> inş` vs `inşallah`, `bide -> bir de`, `slm`, `nbr`, `aeo`, ` kib`).

### 6. `DEASCIIFIER_ERROR`
Turkish diacritic (`ç, ğ, ı, ö, ş, ü`) restoration failures:
- `under_deasciified`: Required diacritic was not restored (`dokuyorsunuz` vs `döküyorsunuz`, `baslıklarda` vs `başlıklarda`, `alınır mi` vs `alınır mı`, `keskem` vs `keşkem`).
- `over_or_wrong_deasciified`: Clean ASCII/Turkish letters wrongly converted to diacritics (`asacak -> aşacak`, `kurt -> kürt`, `suna -> şuna` when person name `Suna`, `egoşu` vs `egosu`, `gaydır` vs `gaydir`).

### 7. `APOSTROPHE_NUMERAL_ALPHANUM`
Apostrophes, numerals/units, punctuation, URLs, or casing artifacts:
- `missing_or_spaced_apostrophe`: Missing apostrophe on proper noun / acronym / loanword + suffix (`skypenizi -> skype'ınızı`, `fetöcü -> fetö'cü`, `allahım -> allah'ım`, `ankara'da`), or detached/stray apostrophe (`wc ye -> wc'ye`, `halkı ' min -> halkımın`).
- `numeral_unit_or_digit_loss`: Digit deleted (`5aydan -> aydan`, `2sıde -> side`), detached/period numeral suffix (`7 de -> 7'de`, `3 de 2sini -> 3'te 2'sini`, `3. yemenin -> 3'lemenin`), or glued number+unit (`1ay -> 1 ay`, `30usd -> 30 usd`).
- `punct_url_or_casing`: Punctuation/emoticon/URL/handle/casing tokenization artifact where words were mangled due to punctuation or dot/slash splitting.

### 8. `ELONGATION_GEMINATE_ERROR`
Repeated-letter normalization failures:
- `residual_elongation`: Expressive elongation left with 2+ repeated letters (`yaaa -> ya`, `yaa -> ya`, `maleseff -> maalesef`).
- `internal_geminate_mangled`: Accidental/expressive double consonant inside a word not reduced or mangled into wrong word (`eşşekler -> eşlekler` vs `eşekler`, `arttmiyor -> aratmıyor` vs `artmıyor`, `gönnderemiyom` vs `gönderemiyorum`, `sevıyesınnı -> seviyesinin`), or missing lexical geminate (`maalesef`, `hisset` if single-letter typo).

### 9. `SPELLCHECK_TYPO_FAILURE`
General typo / spellchecker candidate selection failures:
- `proper_noun_hijack`: Common/colloquial word or typo mapped to a capitalized/apostrophized proper noun (`canısımm -> Caniş'imi` vs `canısım`, `alakniz -> Alai'niz` vs `alakanız`, `annişimi -> Anni'cimi`).
- `wrong_1edit_candidate`: 1-edit typo mapped to the wrong valid Turkish word (`benegim -> beneğim` vs `bebeğim`, `yaptm -> yaptı` vs `yaptım`, `beziyorda -> beziyordu` vs `benziyor da`).
- `multi_edit_unrecovered`: >=2-edit typo or transposition left unnormalized or garbled (`ıhsallah` vs `inşallah`, `itrenet` vs `internet`, `ikgiki` vs `ilgili`).
- `foreign_dialect_corrupted`: Valid interjection, slang/dialect word, proper/brand term, or foreign/technical token corrupted into an unrelated Turkish word (`Haha -> daha` vs `haha`, `kızçe -> kipçe` vs `kızçe`, `orul -> örül` vs `orul`, `wrap -> arap` vs `wrap`).

### 10. `GOLD_CONVENTION_OR_DISPUTABLE`
Not a clear Zemberek error:
- `gold_convention_or_disputable`: Discrepancy due to `llm_gold` circumflex preference (`imkân`, `hâlâ` when optional), `llm_gold` semantic/person rewrite (`topragın -> toprağım`), or genuinely ambiguous input where Zemberek's normalization is valid Turkish in context (`streçim` vs `strecim`, `hadan` vs `hasan`).
