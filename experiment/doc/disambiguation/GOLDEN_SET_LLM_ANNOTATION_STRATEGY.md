# Golden Set LLM Annotation Strategy (`GSLA-v2.1`)

**Date:** November 8, 2026  
**Target Corpus:** `~/data/turkish/selected_slices/` (`10` Test Slices $\times 1,000$ + `100` Train Slices $\times 1,000$, strictly `7–15` words/sentence)  
**Java Candidate Generator:** [`SliceCandidateGenerator.java`](experiment/src/main/java/zemberek/morphology/ambiguity/model/SliceCandidateGenerator.java) ([`run_slice_candidate_generator.sh`](experiment/scripts/model/run_slice_candidate_generator.sh))  
**Python Batch Annotator:** [`annotate_slices_batch.py`](experiment/scripts/model/annotate_slices_batch.py)  
**Downstream Trainer:** [`TrainFastAmbiguityModel.java`](experiment/src/main/java/zemberek/morphology/ambiguity/dataset/TrainFastAmbiguityModel.java)

---

## 1. Investigation of Previous Pipelines & Upgraded JSON Schema (`ZCS-v2.0`)

### 1.1 Dedicated Multi-Threaded Java Exporter (`SliceCandidateGenerator.java`)
Instead of spawning the legacy single-threaded `DisambiguationCandidateExtractor` from Python per slice, we created [`SliceCandidateGenerator.java`](experiment/src/main/java/zemberek/morphology/ambiguity/model/SliceCandidateGenerator.java) in `zemberek.morphology.ambiguity.model`.
- It initializes `TurkishMorphology` once and exports **all `110` slices (`110,000` sentences, `1,344,480` tokens, `790,255` ambiguous tokens)** across 16 threads in **8.38 seconds** into `~/data/turkish/annotated_slices/{test,train}/candidates/`.

### 1.2 Audit of the JSONL Format: Legacy vs. Upgraded `ZCS-v2.0`
We audited the legacy `DisambiguationCandidateExtractor` JSON schema and made targeted removals and additions while preserving **100% compatibility** with [`TrainAmbiguityModel.loadDataSetFromJsonl`](experiment/src/main/java/zemberek/morphology/ambiguity/dataset/TrainAmbiguityModel.java#L169-L286):

| Level | Removed (Legacy Bloat) | Added in `ZCS-v2.0` (`SliceCandidateGenerator.java`) | Why It Matters |
| :--- | :--- | :--- | :--- |
| **Sentence** | Generic `s_000001` IDs (which collided when concatenating multiple slices) | `sentence_id`: `"test_slice_001_s0001"` (globally unique)<br>`slice_name`, `slice_index`<br>`eval_stratum`: `"NATURAL"`, `"CHALLENGE"`, or `"TRAIN"`<br>`source_domain`: e.g. `"cnn-turk.txt"`<br>`word_count`, `ambiguous_word_count` | Slices can be concatenated freely without ID collisions; test slice evaluation immediately knows `NATURAL` (700) vs. `CHALLENGE` (300) and domain provenance from `.meta.tsv`. |
| **Token** | `char_start`, `char_end` | `token_type` (`Word`, `Punctuation`, `Number`, etc.)<br>`macro_mechanism` (`M1_INFLECTIONAL_SYNCRETISM` .. `M7_HYBRID_MULTI_LOCUS`)<br>`observability` (`FULL_MORPHO_OBSERVABLE`, `PARTIAL_MORPHO_OBSERVABLE`, `LEXICAL_ONLY_OBSERVABLE`)<br>`zas_signature` (e.g., `"Noun+A3pl+Acc \|\| Noun+A3pl+P3sg..."`) | Embeds full `ZAS-v3` taxonomy diagnostics directly on every ambiguous token so model accuracy can be sliced by mechanism, observability, and signature with zero re-parsing. |
| **Candidate** | `oflazer_style`<br>`is_informal` | `zemberek_key`: `sa.formatLong()`<br>`ig_key`: exact `rIg` string used by `FastPerceptronAmbiguityResolver`<br>`last_group`: exact `lastGroup` tag<br>`derivation_count`: `sa.groupCount() - 1` | Eliminates redundant Oflazer strings and exposes the exact internal feature keys (`ig_key`, `last_group`) that `FastPerceptronAmbiguityResolver` scores. |

#### Sample `ZCS-v2.0` Record (`test_slice_001_candidates.jsonl`, Line 1):
```json
{
  "sentence_id": "test_slice_001_s0001",
  "slice_name": "test_slice_001",
  "slice_index": 1,
  "eval_stratum": "NATURAL",
  "source_domain": "cnn-turk.txt",
  "text": "Otel yatırımları için yeni cazibe merkezi İkitelli ve Güneşli.",
  "word_count": 9,
  "ambiguous_word_count": 7,
  "tokens": [
    {
      "index": 1,
      "surface": "yatırımları",
      "token_type": "Word",
      "is_ambiguous": true,
      "macro_mechanism": "M1_INFLECTIONAL_SYNCRETISM",
      "observability": "FULL_MORPHO_OBSERVABLE",
      "zas_signature": "Noun+A3pl+Acc || Noun+A3pl+P3pl || Noun+A3pl+P3sg || Noun+A3sg+P3pl",
      "candidates": [
        {
          "id": 0,
          "zemberek_key": "[yatırım:Noun] yatırım:Noun+A3sg+ları:P3pl",
          "ig_key": "yatırım+NounA3sgP3pl",
          "last_group": "NounA3sgP3pl",
          "lemma": "yatırım",
          "pos": "Noun",
          "secondary_pos": "None",
          "derivation_count": 0,
          "morphemes": ["Noun", "A3sg", "P3pl"]
        },
        {
          "id": 1,
          "zemberek_key": "[yatırım:Noun] yatırım:Noun+lar:A3pl+ı:Acc",
          "ig_key": "yatırım+NounA3plAcc",
          "last_group": "NounA3plAcc",
          "lemma": "yatırım",
          "pos": "Noun",
          "secondary_pos": "None",
          "derivation_count": 0,
          "morphemes": ["Noun", "A3pl", "Acc"]
        }
      ]
    }
  ]
}
```

---

## 2. Critical Analysis of the Prompt: Were Our Initial Guidelines Too Prescriptive?

You asked a critical question:
> *"I have questions about your prompt, it is guiding the llm a bit too much? ... can we analyze the prompt, is our guidelines correct? is it too much? too little?"*

We audited every rule in our initial 7-rule prompt against real sentences from `test_slice_001` and `train_slice_001`, and **your suspicion was 100% right**: several positional rules in the initial prompt were **over-guiding the LLM** and would introduce **Instruction-Induced Over-Regularization Errors** when using a strong model like Pro/Flash reasoning models at Medium or High thinking.

### 2.1 Where the Initial 7-Rule Prompt Guided the LLM Too Much (and Why That Hurts)

1. **Flaw #1 — The "Before Noun $\to$ `:Adj`" Heuristic Breaks Bare Noun Compounds (*Belirtisiz İsim Tamlaması*)**:
   - Our initial Rule 5 stated: *"Modifying a noun phrase -> `:Adj` ... Before noun -> `:Adj`"*.
   - Look at Sentence #1 of `test_slice_001`:
     `"Otel yatırımları için yeni cazibe merkezi İkitelli ve Güneşli."`
   - In `"Otel yatırımları"` and `"cazibe merkezi"` (or `"kadın hakları"`, `"taş köprü"`, `"gençlik merkezi"`), the first word (`otel`, `cazibe`, `kadın`) stands **immediately before a noun** (`yatırımları`, `merkezi`, `hakları`), which carries `+P3sg`.
   - In Turkish grammar, `"cazibe merkezi"` and `"kadın hakları"` are **bare noun compounds (`[Noun+A3sg] [Noun+P3sg]`)**, where the first word is a **`:Noun`**, NOT an `:Adj`!
   - Telling a strong LLM *"Before noun $\to$ `:Adj`"* pressures it to override its native knowledge of Turkish noun compounds and mislabel bare compound modifiers as `:Adj`!
2. **Flaw #2 — The "Before Verb $\to$ `:Adv`" Heuristic Breaks Pre-Verbal Unmarked Objects & Copular Complements**:
   - In Turkish SOV order, unmarked direct objects, subjects, and predicate nominals/adjectives also sit immediately before the verb (e.g., *"Başkan **genç** görünüyordu"*, *"Sonuç **doğru** olabilir"*). A strong reasoning model already analyzes full clause argument structure; giving it a naive bigram rule degrades its accuracy on edge cases.
3. **Flaw #3 — Redundant Textbook Grammar Rules Waste ~200 Input Tokens per Sentence**:
   - Explaining basic Turkish grammar (what an imperative is, what `-me/-ma` verbal nouns are, what `var/yok` means, or what `"senin"` means) to a frontier model with Medium/High thinking is unnecessary—the model already knows Turkish semantics natively.

### 2.2 What the LLM *Actually* Needs (Where Unguided Prompts Fail)
While a strong LLM knows **Turkish grammar**, it does **not** automatically know **Zemberek's internal dictionary conventions and representation tie-breaks**! Specifically, an unguided LLM has to guess on four Zemberek-specific phenomena:
1. **How to Read Zemberek's `formatLong()` Tag Notation**:
   - `[lemma:POS,SecPOS] stem:POS+sfx:Tag|deriv:Tag→NewPOS`.
   - Understanding that `|Zero→Noun` / `|Zero→Verb` marks zero-derivation when a word takes nominal or copular suffixes, and that `PCNom`, `PCDat`, `PCAbl`, `PCGen`, `PCIns` on `:Postp` denotes the case of the preceding complement.
2. **Double-Possessive Blocking in Subordinate Clauses & Bare Noun Compounds**:
   - Reminding the model that in bare noun compounds (`"cazibe merkezi"`), the first noun is `:Noun` and the head is `+P3sg`, whereas in subordinate clauses (`"X+Gen Y+Acc V|Part+P3sg"`), `X+Gen` binds to the participle `V+P3sg`, so the internal object `Y` is `+Acc`.
3. **Zemberek's `-ları/-leri` Portmanteau Convention**:
   - For 3rd-person plural possessors (*"onların ..."*), Zemberek generates both `+A3sg+ları:P3pl` and `+lar:A3pl+ı:P3pl`. Without a tie-break rule, the LLM randomly splits between the two across sentences. Specifying `"prefer +A3sg+ları:P3pl over +lar:A3pl+ı:P3pl"` makes it 100% consistent.
4. **Zemberek's Lexicalized Root vs. Multi-Step Derivation Twins**:
   - In Sentence #1 of `train_slice_001` (*"...ameliyata alındığı belirtildi."*), Zemberek generates:
     - `[0] [belirtmek:Verb] belirt:Verb|il:Pass→Verb+di:Past+A3sg`
     - `[1] [belirmek:Verb] belir:Verb|t:Caus→Verb|il:Pass→Verb+di:Past+A3sg`
   - Both mean *"was stated"*, so without a tie-breaker the LLM guesses randomly. Specifying `"when a direct lexicalized root ([belirtmek:Verb], [bildirmek:Verb], [değerlendirmek:Verb]) and a multi-step derivation (|Caus, |Acquire, |Become) have the exact same meaning in context, prefer the direct lexicalized lemma with fewer derivations"` resolves this deterministically.

### 2.3 The Refined `GSLA-v2.4` System Instruction (Every `"prefer"` Paired With Its `"unless..."` Exception + Forensic Audit Fixes)

By cross-referencing all Top 150 ambiguity signatures and Top 25 `LEXICAL_ONLY_OBSERVABLE` signatures in [`turkish_ambiguity_groups_v3_report.md`](experiment/docs/model/turkish_ambiguity_groups_v3_report.md) against `TurkishMorphotactics.java`, `master-dictionary.dict`, `non-tdk.dict`, and all 637 ambiguous tokens in the 100-sentence `test_slice_001` side-by-side evaluation, we paired every `"prefer"` rule with its explicit `"unless..."` boundary condition and eliminated lexicon/morphotactic inconsistencies (plus deduplicated identical `Index:1` candidate strings in `format_zemberek_only_prompt`):

```text
Select the single most accurate Zemberek morphological candidate ID for each ambiguous word (#index) in the Turkish sentence.
Format: [lemma:POS,SecPOS] stem:POS+sfx:Tag|deriv:Tag→NewPOS.

Zemberek Tag Meanings & Tie-Break Conventions:
1. Clauses & Compounds:
   - Bare noun compounds ("otel yatırımları", "cazibe merkezi"): prefer modifier :Noun+A3sg and head +P3sg (not +Acc), unless the first word is a separate clause argument ("[Subject] [Object+Acc]", e.g. "Polis sanıkları yakaladı" -> +Acc) or a descriptive/nisba adjective modifying the noun ("yeni yatırımları", "askerî savcı", "merkezî yönetim" -> :Adj).
   - Single-word lexicalized compounds (cemevi, cezaevi, milletvekili, yayınevi, kamuoyu, işyeri, başbakan): prefer unpossessed +A3sg+Case ("cezaevine") or plural +leri:A3pl ("Değerli milletvekilleri") over +A3sg+P3sg+Case, +ler:A3pl+i:P3sg, or +P2sg+Case, unless possessed by an explicit external possessor ("ilçenin cemevinde" -> +P3sg, "partinin milletvekilleri" -> +ler:A3pl+i:P3sg, "senin işyerinde" -> +P2sg).
   - Subordinate clauses ("X+Gen Y+ı V|Part+P3sg"): when X+Gen is the clause subject agreeing with V+P3sg, select +Acc on internal object Y, unless X+Gen directly possesses Y ("[X+Gen Y+P3sg]", e.g. "hastanın durumu ağırlaştığı") or Y+P3sg is a possessed nominative subject of an intransitive/passive participle.
   - Participles (-dık/-ecek): prefer |PastPart→Adj / |FutPart→Adj before a modified head noun ("geçtiği güzergah") and |PastPart→Noun / |FutPart→Noun for headless/complement clauses ("alındığı belirtildi", "olduğunu söyledi"), unless a participle before a noun is actually a headless complement clause preceding a separate subject or adverbial noun ("alındığı dün açıklandı" -> |PastPart→Noun).
   - Verbal nouns (-mak/-ma/-ış) & -madan/-meden: prefer |Inf1/Inf2/Inf3→Noun over +Neg+Imp or +Neg+Opt when denoting an action/clause ("evlerine dönmeyi"), unless it is a genuine negative command/wish (+Neg+Imp/+Neg+Opt), a dative-inflected noun root ([adam/anlam:Noun]+Dat), or a lexicalized noun denoting an entity/institution ([sözleşme/anlaşma/görüşme/gelişim/girişim/iletişim:Noun]). For -madan/-meden, prefer |WithoutHavingDoneSo→Adv ("without doing", e.g. "durmadan konuştu") over |Inf2→Noun+Abl, unless it is an ablative complement governed by a postposition or verb ("bölmeden önce" before [önce/sonra:Postp,PCAbl], "tartışmadan kaçındı" -> |Inf2→Noun+A3sg+Abl).
2. Zero-Derivation (|Zero→Verb, |Zero→Noun), Copula & Finite Verbs:
   - Select |Zero→Verb (copula) ONLY when a nominal/adjective is the clause predicate or takes copular -ken/-ysa (|ken:While→Adv, +sa:Cond). For finite verbs (-miştir/-mişti/-mektedir), prefer direct tense (+Narr+A3sg+Cop, +Narr+Past, +Prog2+Cop) over |Part→Adj|Zero→Verb or |Inf1→Noun+Loc|Zero→Verb, unless the word is a lexicalized/headless participial predicate taking copula.
   - For "var/yok", "değil", and "ise": prefer [var/yok:Adj] (with |Zero→Verb when suffixed) for existential "there is/isn't", [değil:Verb] for predicate negation, and [ise:Adv] for topic contrast ("Fransa ise..."), unless "var" is [varmak:Verb] ("arrive"), possessed [var/yok:Noun] ("varını yoğunu"), contrastive conjunction [değil:Conj] ("X değil Y"), or true conditional [imek:Verb]+Cond ("doğru ise").
   - When the same lemma has both direct [X:Noun]+Suffixes and [X:Adj]|Zero→Noun+Suffixes (e.g. insanlar, kadınlarda, ortadan, altına, arasında, zorundasınız), prefer direct [X:Noun] (and Num|Ness over Num|Zero→Noun|Ness), unless [X:Adj] and [X:Noun] are distinct-meaning homographs and context requires the nominalized adjective ("the [Adj] one", e.g. [yaş:Adj]|Zero→Noun "wet one" vs [yaş:Noun] "age") or the word is an uninflected adjective modifier ([alt:Adj] kısım).
3. Determiners, Pronouns, Numerals, Adverbs & Postpositions:
   - Demonstratives/quantifiers (bu, o, şu, her, birkaç, birçok, birtakım, tüm, bazı): prefer :Det when modifying a following noun phrase ("bu insanlar", "o gün") and :Pron when standing alone, unless the word is a standalone subject/object pronoun that merely precedes a predicate or another NP ("Bu utanç verici", "o beni bırakmadan", "bu çok iyi bir adım" -> :Pron; for o/onu/ona/onlar/onları/onların, prefer [o:Pron,Pers] for human/animate referents and [o:Pron,Demons] for inanimate/abstract referents as in "ondan sonra", "ne varsa onları geliştirmek"). For degree words (çok, daha, en, pek, biraz, fazla), select :Adv when modifying an adjective/adverb/verb ("çok yardımcı olacak") and :Adj before a noun. For post-nominal enumerative "falan/filan" ("analiz falan"), select :Noun+A3sg.
   - "bir", numerals & "yüzde": indefinite article "a/an" -> [bir:Det]; number "one" -> [bir:Num,Card]; "same/equal" -> [bir:Adj]; in "bir de" / "only" -> [bir:Adv]. Dotted numerals ("1.", "2.") meaning "first, second" -> :Num,Ord (not :Num,Dist). For "yüzde" in percentages ("yüzde 10"), prefer [yüz:Num,Card]|Zero→Noun+A3sg+de:Loc over [yüzde:Noun], unless inflected as a standalone noun ("yüzdesi").
   - Postpositions & relational nouns: match :Postp (PCNom/PCDat/PCAbl/PCGen/PCIns) to the preceding complement's case. For "ile", select [ile:Conj] when coordinating two parallel NPs ("X and Y") and [ile:Postp,PCNom] for instrument/manner/accompaniment ("with/by X"). For relational nouns with a 3rd-person complement ("X tarafından/nedeniyle/hakkında/içinde/üzerinde/yanında/ardından"), prefer Noun+A3sg+P3sg+Case over standalone :Adv, unless possessed by a 2nd-person singular possessor ("senin hakkında/yanında" -> +P2sg+Case) or used standalone as a sentence/temporal adverb without a complement ("Aslında ...", "Sonunda başardık" -> :Adv).
4. Voice, Lexicon & Orthographic Twins:
   - Prefer direct lexicalized lemmas ([belirtmek/bildirmek/anlatmak/üretmek/kaldırmak/düzenlemek/hoşlanmak/düşündürmek/değerlendirmek/sürdürmek/öldürmek/geliştirmek/yaratmak:Verb], [gerekli/değerli/ilgili/yeterli/sürekli:Adj], [savcı/öğrenci/yolcu/işlemci:Noun]) over multi-step derivations (|Caus, |Acquire, |Become, |With, |Agt) when both have the exact same meaning in context (for "yardımcı", select [yardımcı:Adj] when adjectival/predicative as in "çok yardımcı olacak" and [yardımcı:Noun] when an entity/title head as in "başkan yardımcısı"), unless the candidates differ in voice or meaning: specifically, distinguish middle/intransitive [bulunmak/alınmak/katılmak/ayrılmak/bozulmak/silinmek/görünmek/söylenmek/uzanmak/üzülmek/bayılmak:Verb] ("be located/present", "take offense", "attend/join", "depart", "spoil/get upset", "fade", "appear", "grumble", "lie down", "feel sad", "faint/love") from true passive [bulmak/almak/katmak/ayırmak/bozmak/silmek/görmek/söylemek:Verb]|Pass ("be found", "be taken/admitted", "be added", "be allocated", "be destroyed/tampered with", "be erased", "be seen", "be said"; specifically, select [bulmak:Verb]|Pass for passive inability "adreslerinde bulunamayan" = could not be found, or with a dative beneficiary "daireye bulunan kiracı" = found for the flat, vs [bulunmak:Verb] for locative presence "X'te bulunan" or "tavsiyede bulunmak").
   - For -ları/-leri: select +A3sg+ları:P3pl (not +lar:A3pl+ı:P3pl) whenever the possessor is 3rd-person plural ("onların/uzmanların elleri, kararları"), unless the suffix is unpossessed plural accusative (+lar:A3pl+ı:Acc, "sanıkları teşhis etti") or singular-possessor/compound plural (+lar:A3pl+ı:P3sg, "onun kararları", "otel yatırımları", "suçun eserlerinin").
   - Capitalized words (,Prop / ,Abbrv): select ,Prop or ,Abbrv ONLY when capitalized and referring to a named entity, celestial body, official organization/convention/law title ("UNESCO Dünya Mirası", "Türk Ceza Kanunu"), or real acronym; never select ,Prop or ,Abbrv for lowercase words, common words capitalized at sentence start, title-cased text ("Her Ay..."), or false abbreviation collisions ([tıp:Noun] vs [Tıp:Noun,Abbrv]).
   - Circumflex homographs & Nisba twins: [hâl:Noun] (state/condition) vs [hal:Noun] (market); [kâr:Noun] (profit) vs [kar:Noun] (snow); [adet:Noun] (unit/piece) vs [âdet:Noun] (custom/habit). When both circumflexed and plain Nisba adjective lemmas exist ([şahsî:Adj] vs [şahsi:Adj]), prefer the circumflexed lemma ([şahsî:Adj]).

Return ONLY valid JSON: {"<token_index>": <candidate_id>}
```

---

## 3. Empirical Benchmarks Across `gemini-3.8-flash` and `gemini-2.5-flash`

We ran two multi-model pilot experiments:
1. **`test_slice_001` (`GSLA-v2.2`, `gemini-2.5-flash` `LOW` vs `MEDIUM` vs `HIGH`)**:
   - `10` `NATURAL` sentences (`s0001..s0010`, `59` ambiguous tokens) — `pilot_10_v22_low_med_high.json`.
   - `10` `CHALLENGE` sentences (`s0701..s0710`, `72` ambiguous tokens) — `pilot_10_challenge_v22_low_med_high.json`.
2. **`test_slice_002` (`GSLA-v2.3` with `"unless"` clauses, `gemini-3.8-flash` `LOW` & `MEDIUM` vs `gemini-2.5-flash` `LOW` & `MEDIUM`)**:
   - `10` sentences (`test_slice_002_s0001..s0010`, `61` ambiguous tokens) — `pilot_slice2_flash_vs_teacher_v23.json`.

### 3.1 `test_slice_002` (`s0001..s0010`, `61` Ambiguous Tokens) — `gemini-3.8-flash` vs `gemini-2.5-flash` (`GSLA-v2.3`)

| Model & `thinkingLevel` | 10-Sent Parallel Wall Time | `promptTokenCount` (`10` sents) | `candidatesTokenCount` (JSON, `10` sents) | **`thoughtsTokenCount` (`10` sents)** | Total Billed Output / Sent | Agreement with `Teacher MEDIUM` | Agreement with `Teacher LOW` |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **`gemini-3.8-flash` `LOW`** | **`5.08s`** | `26,217` (`2,622`/s) | `513` (`51.3`/s) | **`0`** | **`51.3` / sent** | `58 / 61` (`95.08%`) | `57 / 61` (`93.44%`) |
| **`gemini-3.8-flash` `MEDIUM`** | **`15.46s`** | `26,217` (`2,622`/s) | `513` (`51.3`/s) | **`7,383` (`738.3`/s)** | **`789.6` / sent** | **`59 / 61` (`96.72%`)** | **`59 / 61` (`96.72%`)** |
| **`gemini-2.5-flash` `LOW`** | `69.09s` | `26,846` (`2,685`/s) | `411` (`41.1`/s) | **`0`** | **`41.1` / sent** | **`59 / 61` (`96.72%`)** | `61 / 61` (`100.00%`) |
| **`gemini-2.5-flash` `MEDIUM`** | `21.19s` | `26,846` (`2,685`/s, `cached=531`) | `411` (`41.1`/s) | **`0`** | **`41.1` / sent** | `61 / 61` (`100.00%`) | **`59 / 61` (`96.72%`)** |

**Key Empirical Findings from `test_slice_002`:**
1. **Unanimous Agreement on `57 / 61` (`93.44%`) and `59 / 61` (`96.72%`) Agreement Between `gemini-3.8-flash` `MEDIUM` and `gemini-2.5-flash`**:
   - All 4 configurations agreed unanimously on 57 of 61 ambiguous tokens, including nested 3-noun compounds (`s0006`: `Müşteri:Noun+A3sg` + `hizmet:Noun+A3pl+P3sg` + `servis:Noun+A3sg+P3sg+Acc`), duration numeral `"Bir haftadır"` (`[bir:Num,Card]`), compound vs accusative (`s0004`: `"hükümet krizini"` $\to$ `+P3sg+Acc` vs `"temas grubu"` $\to$ `+P3sg`), and direct lexicalized roots (`anlatabilmek`, `sevgiliye`, `benzetmek`).
   - Raising `gemini-3.8-flash` from `LOW` (`0` thought tokens) to `MEDIUM` (`738.3` thought tokens/sentence) increased its agreement with `gemini-2.5-flash` `MEDIUM` from `95.08%` (`58/61`) to **`96.72%` (`59/61`)**, resolving both `s0005 #10 "karşı"` (`[karşı:Noun]` in `"karşı karşıya"`) and `s0010 #0 "Ne"` (`[ne:Pron,Ques]` in `"Ne de olsa"`).
2. **Thinking Token Behavior on `gemini-3.8-flash` vs `gemini-2.5-flash`**:
   - At `thinkingLevel: "LOW"`, **both** `gemini-3.8-flash` and `gemini-2.5-flash` emit **`0` thinking tokens** (`~41–51` JSON output tokens/sentence).
   - At `thinkingLevel: "MEDIUM"`, `gemini-3.8-flash` uses **`~738` thinking tokens/sentence** (`789.6` total output tokens/sentence), whereas `gemini-2.5-flash` resolves `LOW` and `MEDIUM` with **`0` thinking tokens** (`41.1` output tokens/sentence) and only emits thinking tokens at `HIGH` (`~554` thought tokens/sentence on `NATURAL`, `~1,430` on `CHALLENGE`).

---

## 4. Prompt Caching Analysis & Theoretical Cost Table

### 4.1 Prompt Caching Mechanics on `gemini-3.8-flash` and `gemini-2.5-flash`
1. **Explicit Context Caching (`createCachedContent`)**:
   - `models/gemini-3.8-flash` supports `['generateContent', 'countTokens', 'createCachedContent', 'batchGenerateContent']`.
   - `models/gemini-2.5-flash` supports `['generateContent', 'countTokens', 'batchGenerateContent']` (no `createCachedContent`).
2. **Implicit Prompt Caching (`cachedContentTokenCount`)**:
   - Automatically active on both models whenever a request's shared prefix exceeds `2,048` tokens (`teacher_medium` automatically reported `cachedContentTokenCount: 531` on the `GSLA-v2.3` run!).

### 4.2 Theoretical Cost Table Across Options (`GSLA-v2.3`, Calibrated from Empirical `usageMetadata`)

Empirical token profile per `1,000`-sentence slice with `GSLA-v2.3` (`~2.62M–2.68M` input tokens per 1,000-sentence `test` slice, `~2.85M` input tokens per 1,000-sentence `train` slice, averaging **`~2.75M` input tokens/slice**):

| Option / Model & Thinking Config | Thought Tokens / Sent | Total Billed Output / 1K Slice | **Batch API (50% Off): 1 Slice (`1K` sents)** | **Batch API: 5 Slices (`5K` sents)** | **Batch API: 10 Slices (`10K` sents)** | **Batch API: 25 Slices (`25K` sents)** | **Batch API: 100 Slices (`100K` sents)** | *Standard Sync API (No Batch 50% Off): 1K Slice* |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **Option 1: `gemini-3.8-flash` `LOW`** (`95.1%` Teacher agreement) | **`0`** | `0.051M` (`~51`/sent) | **\$0.22 – \$0.76** | **\$1.10 – \$3.80** | **\$2.20 – \$7.60** | **\$5.50 – \$19.00** | **\$22.00 – \$76.00** | *\$0.44 – \$1.52* |
| **Option 2: `gemini-3.8-flash` `MEDIUM`** (`96.7%` Teacher agreement) | **`~738`** | `0.790M` (`~790`/sent) | **\$0.44 – \$1.87** | **\$2.20 – \$9.35** | **\$4.40 – \$18.70** | **\$11.00 – \$46.75** | **\$44.00 – \$187.00** | *\$0.88 – \$3.74* |
| **Option 3: `gemini-2.5-flash` `LOW` or `MEDIUM`** *(Best Teacher Value)* | **`0`** | **`0.041M` (`~41`/sent)** | **\$1.92 – \$3.00** | **\$9.60 – \$15.00** | **\$19.20 – \$30.00** | **\$48.00 – \$75.00** | **\$192.00 – \$300.00** | *\$3.84 – \$6.00* |
| **Option 4: `gemini-2.5-flash` `HIGH`** (`~820` thought/sent) | **`~820`** | `0.861M` (`~861`/sent) | **\$6.02 – \$7.92** | **\$30.10 – \$39.60** | **\$60.20 – \$79.20** | **\$150.50 – \$198.00** | **\$602.00 – \$792.00** | *\$12.04 – \$15.84* |
