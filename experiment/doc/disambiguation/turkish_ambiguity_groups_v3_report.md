# Turkish Morphological Ambiguity Taxonomy & Corpus Profile Report (`ZAS-v3`)

## 1. Executive Summary & Corpus Scope

| Metric | Value |
| :--- | :--- |
| **Timestamp** | `2026-11-08T00:21:17.783619278Z` |
| **Refined Corpus Directory** | `/home/dndara/data/turkish/refined` (30 files) |
| **Max Sentences / File** | `50,000` |
| **Sentences Profiled** | **1,440,573** (78399 sents/sec) |
| **Total Words Profiled (Non-Punctuation)** | **17,059,288** (928397 words/sec) |
| **Unambiguous Words (`K = 1`)** | 6,384,443 (37.43%) |
| **Ambiguous Words (`K >= 2`)** | **10,674,845 (62.57%)** |
| **Mean Candidate Analyses per Ambiguous Word** | **3.17** |
| **Unique Canonical Meso-Signatures (`ZAS-v3`)** | **42,055** |
| **Certain Context Anchor Coverage (`<S>` + `U:` + `C:`)** | **62.05%** of all neighbor positions (without disambiguator!) |

## 2. Tier 1: Breakdown by the 7 Macro-Mechanisms of Turkish Ambiguity

| Macro-Mechanism | Ambiguous Words | Share of Ambiguous (%) | Share of All Words (%) | Unique Signatures | Description |
| :--- | ---: | ---: | ---: | ---: | :--- |
| **`M1_INFLECTIONAL_SYNCRETISM`** | 1,558,372 | **14.60%** | 9.14% | 1,725 | Same DictionaryItem & derivational chain; pure suffix homophony (-I, -In, -lArI) |
| **`M2_DERIVATIONAL_SHIFT`** | 910,883 | **8.53%** | 5.34% | 1,653 | Same DictionaryItem; different IG count or derived POS (Participle/Infinitive/Zero) |
| **`M3_LEXICAL_POS_POLYSEMY`** | 1,971,802 | **18.47%** | 11.56% | 1,407 | Different DictionaryItems, same lemma & stem, different POS (Adj/Noun, Det/Pron, Adv/Noun) |
| **`M4_PROPER_VS_COMMON`** | 2,208,150 | **20.69%** | 12.94% | 12,159 | ProperNoun/Abbreviation vs. Common Noun/Adj/Verb collision |
| **`M5a_VOICE_LEXICALIZED`** | 133,692 | **1.25%** | 0.78% | 2,476 | Stem-boundary split with identical final IG (belir+Caus vs belirt) — F15/F17 blind |
| **`M5b_STEM_SUFFIX_COLLISION`** | 3,081,791 | **28.87%** | 18.07% | 20,622 | Stem-boundary split with different final IGs (koy+un vs koyun, yaş+a+ma vs yaşa+ma) |
| **`M6a_HOMONYM_DIFF_POS`** | 465,699 | **4.36%** | 2.73% | 251 | Same-length homonymous roots or phonological alternations across different POS (yüz, var, bin) |
| **`M6b_ORTHO_OR_COMPOUND_TWIN`** | 71,354 | **0.67%** | 0.42% | 925 | Circumflex twins (kar/kâr), CompoundP3sg twins (zeytinyağı), or vowel-drop twins |
| **`M7_HYBRID_MULTI_LOCUS`** | 273,102 | **2.56%** | 1.60% | 837 | Multi-locus ambiguity combining root/POS collision and inflectional/derivational syncretism |

## 3. Tier 3: `FastPerceptronAmbiguityResolver` Feature Observability Breakdown

| Observability Class | Ambiguous Words | Share (%) | Unique Signatures | Architectural Meaning for `FastPerceptronAmbiguityResolver` |
| :--- | ---: | ---: | ---: | :--- |
| **`FULL_MORPHO_OBSERVABLE`** | 8,907,298 | **83.44%** | 12,329 | U_lastIG == K: F15/F17 unlexicalized transitions can distinguish all candidates |
| **`PARTIAL_MORPHO_OBSERVABLE`** | 1,226,931 | **11.49%** | 15,523 | 1 < U_lastIG < K: F15/F17 narrow candidates; lexical/IG-count features break ties |
| **`LEXICAL_ONLY_OBSERVABLE`** | 540,616 | **5.06%** | 14,203 | U_lastIG == 1: All candidates share the exact same lastGroup; F15/F17 are 100% blind |

## 4. Tier 4: Neighbor Context Anchor Certainty (Without Any Disambiguator)

| Neighbor Anchor Type | Neighbor Positions | Share (%) | Meaning |
| :--- | ---: | ---: | :--- |
| **Sentence Boundary (`<S>` / `</S>`)** | 1,771,678 | 8.30% | 100% deterministic sentence start/end boundary |
| **Exact Unambiguous Word (`U:`, `K = 1`)** | 7,202,606 | 33.74% | Neighbor has only 1 morphological parse |
| **Consensus Unambiguous (`C:`, `K >= 2`)** | 4,273,543 | **20.02%** | Neighbor has `>= 2` parses, but **100% agree** on final POS/case! |
| **POS-Ambiguous Neighbor (`A:`, `K >= 2`)** | 8,101,863 | 37.95% | Neighbor candidates disagree on final POS |

## 5. Cumulative Zipfian Coverage Across Canonical Meso-Signatures

| Top $N$ Signatures | Cumulative Ambiguous Words | Cumulative Share of Ambiguous (%) |
| ---: | ---: | ---: |
| **Top 5** | 1,283,957 | **12.03%** |
| **Top 10** | 1,892,729 | **17.73%** |
| **Top 25** | 3,098,350 | **29.02%** |
| **Top 50** | 4,165,382 | **39.02%** |
| **Top 100** | 5,248,805 | **49.17%** |
| **Top 250** | 6,851,495 | **64.18%** |
| **Top 500** | 8,018,193 | **75.11%** |
| **Top 1,000** | 9,010,831 | **84.41%** |
| **All 42,055 Signatures** | **10,674,845** | **100.00%** |

## 6. Top 150 Canonical Ambiguity Signatures (`ZAS-v3` Summary Table)

| Rank | Count | Share % | Cum % | Mechanism | Observability | Regime | $H(\text{lemma})$ | Unique Lemmas | Canonical Meso-Signature (`ZAS-v3`) | Top Surface Words |
| ---: | ---: | ---: | ---: | :--- | :--- | :--- | ---: | ---: | :--- | :--- |
| 1 | 342,921 | 3.21% | 3.21% | `M3_LEXICAL_POS_POLYSEMY` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `Adj \|\| Adv \|\| Det \|\| Num,Card` | bir (342920), bîr (1) |
| 2 | 288,597 | 2.70% | 5.92% | `M1_INFLECTIONAL_SYNCRETISM` | `FULL_MORPHO` | `OPEN_PARADIGM` | 8.72 | 2,000 | `Noun+A3sg+Acc \|\| Noun+A3sg+P3sg` | kararı (7075), sonucu (5118), yolu (3751), adı (3545), işi (3504) |
| 3 | 236,172 | 2.21% | 8.13% | `M3_LEXICAL_POS_POLYSEMY` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `Det \|\| Pron,Demons+A3sg` | bu (236170), b.u (2) |
| 4 | 211,228 | 1.98% | 10.11% | `M3_LEXICAL_POS_POLYSEMY` | `FULL_MORPHO` | `OPEN_PARADIGM` | 7.31 | 765 | `Adj \|\| Noun+A3sg` | bütün (11756), sosyal (8613), kamu (5883), üst (4488), spor (4436) |
| 5 | 205,039 | 1.92% | 12.03% | `M4_PROPER_VS_COMMON` | `FULL_MORPHO` | `OPEN_PARADIGM` | 7.50 | 957 | `Noun+A3sg \|\| Noun,Prop+A3sg` | dünya (9592), ülke (5817), araç (4810), mücadele (4560), vergi (4464) |
| 6 | 135,565 | 1.27% | 13.30% | `M3_LEXICAL_POS_POLYSEMY` | `FULL_MORPHO` | `OPEN_PARADIGM` | 3.44 | 30 | `Adj \|\| Adv \|\| Noun+A3sg` | ilk (30043), iyi (25607), tek (13980), güzel (13034), artık (12486) |
| 7 | 135,449 | 1.27% | 14.57% | `M1_INFLECTIONAL_SYNCRETISM` | `FULL_MORPHO` | `OPEN_PARADIGM` | 8.83 | 2,000 | `Noun+A3pl+Acc \|\| Noun+A3pl+P3pl \|\| Noun+A3pl+P3sg \|\| Noun+A3sg+P3pl` | ekipleri (2459), işleri (2217), hizmetleri (1653), fiyatları (1619), ürünleri (1554) |
| 8 | 121,549 | 1.14% | 15.71% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-2)\|Adj->Zero+Noun+A3sg+Gen \|\| STEM(-2)\|Adj->Zero+Noun+A3sg+P2sg \|\| STEM(-2)\|Noun+A3sg+Gen \|\| STEM(-2)\|Noun+A3sg+P2sg \|\| STEM(-2)\|Verb+Imp+A2pl \|\| STEM(0)\|Postp,PCGen \|\| STEM(0)\|Postp,PCNom` | için (121549) |
| 9 | 111,361 | 1.04% | 16.75% | `M6a_HOMONYM_DIFF_POS` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `Conj \|\| Noun+A3sg \|\| Verb+Imp+A2sg` | de (111361) |
| 10 | 104,848 | 0.98% | 17.73% | `M3_LEXICAL_POS_POLYSEMY` | `FULL_MORPHO` | `OPEN_PARADIGM` | 4.33 | 118 | `Adj \|\| Adv` | böyle (17174), nasıl (16236), biraz (9251), hemen (6840), pek (6273) |
| 11 | 104,611 | 0.98% | 18.71% | `M2_DERIVATIONAL_SHIFT` | `FULL_MORPHO` | `OPEN_PARADIGM` | 5.57 | 750 | `Verb->PastPart+Adj+P3sg \|\| Verb->PastPart+Noun+A3sg+Acc \|\| Verb->PastPart+Noun+A3sg+P3sg` | olduğu (27420), yaptığı (10044), ettiği (5016), verdiği (4424), aldığı (3957) |
| 12 | 100,879 | 0.95% | 19.66% | `M2_DERIVATIONAL_SHIFT` | `FULL_MORPHO` | `OPEN_PARADIGM` | 8.30 | 2,000 | `Noun+A3pl \|\| Noun+A3sg->Zero+Verb+Pres+A3pl` | arkadaşlar (5988), şeyler (4561), kişiler (2218), bilgiler (2068), vatandaşlar (1430) |
| 13 | 100,283 | 0.94% | 20.60% | `M4_PROPER_VS_COMMON` | `FULL_MORPHO` | `OPEN_PARADIGM` | 4.29 | 115 | `Adj \|\| Noun+A3sg \|\| Noun,Prop+A3sg` | büyük (30980), küçük (7514), çocuk (6586), teknik (5475), gerçek (4970) |
| 14 | 96,940 | 0.91% | 21.50% | `M1_INFLECTIONAL_SYNCRETISM` | `FULL_MORPHO` | `OPEN_PARADIGM` | 8.45 | 2,000 | `Noun+A3sg+Gen \|\| Noun+A3sg+P2sg` | işin (3346), devletin (2431), hükmün (1877), olayın (1828), hükümetin (1817) |
| 15 | 94,382 | 0.88% | 22.39% | `M3_LEXICAL_POS_POLYSEMY` | `FULL_MORPHO` | `CLOSED_LEX` | 2.35 | 50 | `Adv \|\| Noun+A3sg` | en (52164), hiç (16365), tekrar (7182), dakika (4326), acaba (3086) |
| 16 | 90,786 | 0.85% | 23.24% | `M3_LEXICAL_POS_POLYSEMY` | `FULL_MORPHO` | `CLOSED_LEX` | 1.14 | 4 | `Adv \|\| Noun,Time+A3sg` | daha (65389), şimdi (12961), bugün (12395), sabahleyin (41) |
| 17 | 83,871 | 0.79% | 24.02% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-1)\|Noun+A3sg+Dat \|\| STEM(-1)\|Verb+Opt+A3sg \|\| STEM(0)\|Conj \|\| STEM(0)\|Postp,PCNom` | ile (83871) |
| 18 | 79,407 | 0.74% | 24.77% | `M3_LEXICAL_POS_POLYSEMY` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `Adj \|\| Adv \|\| Det \|\| Postp,PCAbl` | çok (79407) |
| 19 | 77,268 | 0.72% | 25.49% | `M6a_HOMONYM_DIFF_POS` | `FULL_MORPHO` | `CLOSED_LEX` | 2.35 | 26 | `Adj \|\| Noun+A3sg \|\| Verb+Imp+A2sg` | var (44869), eski (7879), gerek (6285), iç (4927), ek (3299) |
| 20 | 66,801 | 0.63% | 26.12% | `M2_DERIVATIONAL_SHIFT` | `FULL_MORPHO` | `OPEN_PARADIGM` | 7.14 | 875 | `Verb+Neg+Imp+A2sg \|\| Verb->Inf2+Noun+A3sg` | çalışma (6431), uygulama (2475), düzenleme (2199), olma (2195), etme (2021) |
| 21 | 66,291 | 0.62% | 26.74% | `M1_INFLECTIONAL_SYNCRETISM` | `FULL_MORPHO` | `OPEN_PARADIGM` | 8.59 | 2,000 | `Noun+A3sg+P2sg+Acc \|\| Noun+A3sg+P3sg+Acc` | hayatını (4011), yerini (1677), adını (1572), kararını (1133), görevini (1126) |
| 22 | 62,622 | 0.59% | 27.32% | `M1_INFLECTIONAL_SYNCRETISM` | `FULL_MORPHO` | `OPEN_PARADIGM` | 8.78 | 2,000 | `Noun+A3pl+Gen \|\| Noun+A3pl+P2sg` | kişilerin (2102), vatandaşların (1238), öğrencilerin (1126), olayların (785), tarafların (737) |
| 23 | 60,992 | 0.57% | 27.90% | `M1_INFLECTIONAL_SYNCRETISM` | `FULL_MORPHO` | `OPEN_PARADIGM` | 8.42 | 1,920 | `Noun+A3sg+P2sg+Dat \|\| Noun+A3sg+P3sg+Dat` | başına (4375), anlamına (1735), yanına (1323), eline (1146), aleyhine (926) |
| 24 | 60,305 | 0.56% | 28.46% | `M1_INFLECTIONAL_SYNCRETISM` | `FULL_MORPHO` | `OPEN_PARADIGM` | 7.64 | 1,426 | `Noun+A3sg+P2sg+Loc \|\| Noun+A3sg+P3sg+Loc` | başında (3202), durumunda (2952), sonucunda (2321), oranında (2257), yönünde (1763) |
| 25 | 60,183 | 0.56% | 29.02% | `M1_INFLECTIONAL_SYNCRETISM` | `FULL_MORPHO` | `OPEN_PARADIGM` | 4.47 | 636 | `Verb->PastPart+Noun+A3sg+P2sg+Acc \|\| Verb->PastPart+Noun+A3sg+P3sg+Acc` | olduğunu (27447), gerektiğini (4406), ettiğini (2436), geldiğini (1572), yaptığını (1461) |
| 26 | 53,302 | 0.50% | 29.52% | `M1_INFLECTIONAL_SYNCRETISM` | `FULL_MORPHO` | `OPEN_PARADIGM` | 6.11 | 2,000 | `Noun,Prop+A3sg+Gen \|\| Noun,Prop+A3sg+P2sg` | erdoğan'ın (1542), israil'in (1220), istanbul'un (986), beşiktaş'ın (786), atatürk'ün (739) |
| 27 | 52,766 | 0.49% | 30.02% | `M3_LEXICAL_POS_POLYSEMY` | `FULL_MORPHO` | `CLOSED_LEX` | 1.08 | 4 | `Adj \|\| Det` | her (40499), birçok (6630), birkaç (4731), birtakım (906) |
| 28 | 52,347 | 0.49% | 30.51% | `M1_INFLECTIONAL_SYNCRETISM` | `FULL_MORPHO` | `OPEN_PARADIGM` | 7.37 | 1,488 | `Noun+A3sg+Gen \|\| Noun+A3sg+P2sg+Gen` | kişinin (5951), davanın (4171), maddenin (1609), bölgenin (1151), konunun (1128) |
| 29 | 49,691 | 0.47% | 30.97% | `M1_INFLECTIONAL_SYNCRETISM` | `FULL_MORPHO` | `OPEN_PARADIGM` | 8.60 | 1,846 | `Noun+A3sg+P2sg+Gen \|\| Noun+A3sg+P3sg+Gen` | kararının (2286), sisteminin (878), halkının (840), sektörünün (756), oranının (731) |
| 30 | 48,463 | 0.45% | 31.43% | `M6a_HOMONYM_DIFF_POS` | `FULL_MORPHO` | `OPEN_PARADIGM` | 4.49 | 85 | `Noun+A3sg \|\| Verb+Imp+A2sg` | yol (7882), il (5821), soru (5588), et (3259), kan (2799) |
| 31 | 48,393 | 0.45% | 31.88% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 2 | `STEM(-1)\|Adj->Acquire+Verb+Imp+A2sg \|\| STEM(0)\|Verb->PresPart+Adj` | olan (48390), yücelen (3) |
| 32 | 48,247 | 0.45% | 32.33% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-1)\|Noun+A3sg+Dat \|\| STEM(0)\|Adj \|\| STEM(0)\|Conj` | ama (48242), âmâ (5) |
| 33 | 47,186 | 0.44% | 32.78% | `M4_PROPER_VS_COMMON` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `Noun+A3sg \|\| Noun,Prop+A3sg \|\| Postp,PCDat \|\| Postp,PCGen \|\| Postp,PCNom` | kadar (47186) |
| 34 | 45,333 | 0.42% | 33.20% | `M4_PROPER_VS_COMMON` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-1)\|Noun,Abbrv+A3sg+Acc \|\| STEM(0)\|Postp,PCGen \|\| STEM(0)\|Postp,PCNom` | gibi (45333) |
| 35 | 43,822 | 0.41% | 33.61% | `M3_LEXICAL_POS_POLYSEMY` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `Adj \|\| Det \|\| Interj \|\| Pron,Demons+A3sg \|\| Pron,Pers+A3sg` | o (43760), o. (62) |
| 36 | 43,602 | 0.41% | 34.02% | `M2_DERIVATIONAL_SHIFT` | `FULL_MORPHO` | `OPEN_PARADIGM` | 5.77 | 697 | `Verb+Fut+A3sg \|\| Verb->FutPart+Adj` | olacak (11743), edecek (3561), yapacak (2386), başlayacak (1286), sağlayacak (1236) |
| 37 | 43,120 | 0.40% | 34.42% | `M4_PROPER_VS_COMMON` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `Adj \|\| Adv \|\| Conj \|\| Interj \|\| Noun+A3sg \|\| Noun,Abbrv+A3sg \|\| Pron,Ques+A3sg` | ne (43119), n.e. (1) |
| 38 | 41,951 | 0.39% | 34.82% | `M7_HYBRID_MULTI_LOCUS` | `FULL_MORPHO` | `OPEN_PARADIGM` | 5.92 | 346 | `Adj->Zero+Noun+A3sg+Acc \|\| Adj->Zero+Noun+A3sg+P3sg \|\| Noun+A3sg+Acc \|\| Noun+A3sg+P3sg` | gereği (4201), dışı (3463), kartı (2608), içi (1954), eşi (1802) |
| 39 | 41,505 | 0.39% | 35.20% | `M3_LEXICAL_POS_POLYSEMY` | `FULL_MORPHO` | `OPEN_PARADIGM` | 5.92 | 819 | `Num,Dist \|\| Num,Ord` | 2. (4867), 3. (3788), 1. (3742), 4. (2453), 5. (1719) |
| 40 | 41,424 | 0.39% | 35.59% | `M3_LEXICAL_POS_POLYSEMY` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `Adv \|\| Noun,Time+A3sg \|\| Postp,PCAbl` | sonra (41424) |
| 41 | 40,824 | 0.38% | 35.98% | `M1_INFLECTIONAL_SYNCRETISM` | `FULL_MORPHO` | `OPEN_PARADIGM` | 5.77 | 2,000 | `Noun,Prop+A3sg+Gen \|\| Noun,Prop+A3sg+P2sg+Gen` | türkiye'nin (10664), rusya'nın (977), avrupa'nın (931), fenerbahçe'nin (702), obama'nın (572) |
| 42 | 40,454 | 0.38% | 36.35% | `M3_LEXICAL_POS_POLYSEMY` | `FULL_MORPHO` | `CLOSED_LEX` | 1.14 | 7 | `Adv \|\| Conj` | ancak (24465), hem (14791), hele (1031), amma (100), kah (29) |
| 43 | 39,587 | 0.37% | 36.73% | `M1_INFLECTIONAL_SYNCRETISM` | `FULL_MORPHO` | `OPEN_PARADIGM` | 8.84 | 2,000 | `Noun+A3pl+P2sg+Acc \|\| Noun+A3pl+P3pl+Acc \|\| Noun+A3pl+P3sg+Acc \|\| Noun+A3sg+P3pl+Acc` | ifadelerini (1321), sorularını (910), sözlerini (835), hizmetlerini (683), sonuçlarını (517) |
| 44 | 38,143 | 0.36% | 37.08% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-1)\|Noun+A3sg+Dat \|\| STEM(-2)\|Verb+Cond+A3sg \|\| STEM(0)\|Adv` | ise (38143) |
| 45 | 37,549 | 0.35% | 37.43% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 2.30 | 39 | `STEM(-1)\|Noun+A3sg+Acc \|\| STEM(-1)\|Noun+A3sg+P3sg \|\| STEM(0)\|Adj` | aynı (21190), tarihi (4929), merkezi (4166), aksi (1271), dini (878) |
| 46 | 35,169 | 0.33% | 37.76% | `M4_PROPER_VS_COMMON` | `PARTIAL_MORPHO` | `CLOSED_LEX` | 0.93 | 7 | `STEM(-2)\|Noun+A3sg->With+Adj \|\| STEM(-2)\|Noun,Prop+A3sg->With+Adj \|\| STEM(0)\|Adj` | ilgili (26975), değerli (7450), özürlü (269), sevimli (259), dalgalı (135) |
| 47 | 34,479 | 0.32% | 38.09% | `M2_DERIVATIONAL_SHIFT` | `FULL_MORPHO` | `OPEN_PARADIGM` | 6.97 | 744 | `Verb+Neg+Opt+A3sg \|\| Verb->Inf2+Noun+A3sg+Dat` | yapmaya (2189), etmeye (2063), olmaya (1737), almaya (1671), vermeye (1263) |
| 48 | 34,030 | 0.32% | 38.41% | `M1_INFLECTIONAL_SYNCRETISM` | `FULL_MORPHO` | `OPEN_PARADIGM` | 8.99 | 2,000 | `Noun+A3pl+P2sg+Gen \|\| Noun+A3pl+P3pl+Gen \|\| Noun+A3pl+P3sg+Gen \|\| Noun+A3sg+P3pl+Gen` | itirazlarının (710), milletvekillerinin (564), işlemlerinin (513), fiyatlarının (497), ekiplerinin (497) |
| 49 | 34,008 | 0.32% | 38.72% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-1)\|Verb+Opt+A3sg \|\| STEM(0)\|Postp,PCDat` | göre (34008) |
| 50 | 31,637 | 0.30% | 39.02% | `M3_LEXICAL_POS_POLYSEMY` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `Noun+A3sg \|\| Pron,Pers+A1sg` | ben (31637) |
| 51 | 30,601 | 0.29% | 39.31% | `M6a_HOMONYM_DIFF_POS` | `FULL_MORPHO` | `CLOSED_LEX` | 1.06 | 3 | `Noun,Time+A3sg \|\| Verb+Imp+A2sg` | yıl (22272), an (6363), yaz (1964), a.n (1), ân (1) |
| 52 | 30,307 | 0.28% | 39.59% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-2)\|Verb->Pass+Verb+Imp+A2sg \|\| STEM(0)\|Conj \|\| STEM(0)\|Verb+Neg+Pres+A3sg` | değil (30307) |
| 53 | 28,995 | 0.27% | 39.86% | `M1_INFLECTIONAL_SYNCRETISM` | `FULL_MORPHO` | `OPEN_PARADIGM` | 8.68 | 1,809 | `Noun+A3pl+P2sg+Dat \|\| Noun+A3pl+P3pl+Dat \|\| Noun+A3pl+P3sg+Dat \|\| Noun+A3sg+P3pl+Dat` | sözlerine (985), verilerine (755), hükümlerine (728), kurallarına (489), sahiplerine (439) |
| 54 | 28,976 | 0.27% | 40.13% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-1)\|Noun+A3sg+Acc \|\| STEM(-1)\|Noun+A3sg+P3sg \|\| STEM(0)\|Adj \|\| STEM(0)\|Adv` | yeni (28976) |
| 55 | 27,218 | 0.25% | 40.39% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `OPEN_PARADIGM` | 3.71 | 86 | `STEM(-1)\|Noun+A3sg+Dat \|\| STEM(0)\|Noun+A3sg` | dava (8821), sıra (4329), kere (1917), marka (1845), sigara (1745) |
| 56 | 27,140 | 0.25% | 40.64% | `M2_DERIVATIONAL_SHIFT` | `FULL_MORPHO` | `OPEN_PARADIGM` | 6.12 | 566 | `Verb+Narr+A3sg \|\| Verb->NarrPart+Adj` | olmuş (3721), yapmış (2439), gelmiş (1868), vermiş (1618), çıkmış (1165) |
| 57 | 26,867 | 0.25% | 40.90% | `M2_DERIVATIONAL_SHIFT` | `FULL_MORPHO` | `OPEN_PARADIGM` | 5.75 | 475 | `Verb+Aor+A3sg \|\| Verb->AorPart+Adj` | gerekir (5919), alır (1887), verir (1511), yapar (1487), gösterir (817) |
| 58 | 26,793 | 0.25% | 41.15% | `M4_PROPER_VS_COMMON` | `FULL_MORPHO` | `OPEN_PARADIGM` | 5.40 | 364 | `Noun+A3sg+Acc \|\| Noun+A3sg+P3sg \|\| Noun,Prop+A3sg+Acc \|\| Noun,Prop+A3sg+P3sg` | sahibi (4686), meclisi (2248), vekili (1982), reddi (1523), değeri (1465) |
| 59 | 25,613 | 0.24% | 41.39% | `M1_INFLECTIONAL_SYNCRETISM` | `FULL_MORPHO` | `OPEN_PARADIGM` | 4.08 | 252 | `Noun+A3sg \|\| Noun+A3sg+P3sg` | milletvekili (7908), büyükşehir (4011), cumhurbaşkanı (3953), işyeri (766), zamanaşımı (647) |
| 60 | 25,507 | 0.24% | 41.62% | `M3_LEXICAL_POS_POLYSEMY` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `Adj \|\| Noun+A3sg \|\| Num` | son (25507) |
| 61 | 25,428 | 0.24% | 41.86% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 0.37 | 3 | `STEM(-5)\|Noun+A3sg+P2sg+Abl \|\| STEM(-5)\|Noun+A3sg+P3sg+Abl \|\| STEM(0)\|Adv` | tarafından (23607), bakımından (1818), helalinden (3) |
| 62 | 24,946 | 0.23% | 42.10% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-2)\|Adj->AsIf+Adj \|\| STEM(-2)\|Adj->Ly+Adv \|\| STEM(-2)\|Noun+A3sg+Equ \|\| STEM(0)\|Adv \|\| STEM(0)\|Noun,Time+A3sg \|\| STEM(0)\|Postp,PCAbl` | önce (24946) |
| 63 | 24,705 | 0.23% | 42.33% | `M6a_HOMONYM_DIFF_POS` | `FULL_MORPHO` | `CLOSED_LEX` | 2.24 | 45 | `Noun+A3sg->Zero+Verb+Past+A3sg \|\| Verb+Past+A3sg` | etti (14473), kaldı (4188), düştü (1695), buldu (1343), gönderdi (852) |
| 64 | 24,617 | 0.23% | 42.56% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-2)\|Noun+A3sg+Loc \|\| STEM(-2)\|Num,Card->Zero+Noun+A3sg+Loc \|\| STEM(0)\|Noun+A3sg` | yüzde (24617) |
| 65 | 24,092 | 0.23% | 42.78% | `M4_PROPER_VS_COMMON` | `FULL_MORPHO` | `OPEN_PARADIGM` | 6.16 | 439 | `Noun+A3sg+Loc \|\| Noun,Prop+A3sg+Loc` | ülkede (2918), süreçte (1216), düzeyde (910), mücadelede (712), sahada (694) |
| 66 | 23,556 | 0.22% | 43.01% | `M4_PROPER_VS_COMMON` | `FULL_MORPHO` | `CLOSED_LEX` | 1.56 | 4 | `STEM(-1)\|Noun,Prop+A3sg+Acc \|\| STEM(-1)\|Noun,Prop+A3sg+P3sg \|\| STEM(-2)\|Noun+A3sg->With+Adj \|\| STEM(0)\|Adj` | sayılı (9212), milli (7040), belli (5570), millî (1709), damalı (25) |
| 67 | 23,547 | 0.22% | 43.23% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `OPEN_PARADIGM` | 6.65 | 405 | `STEM(-2)\|Noun+A3pl \|\| STEM(-2)\|Noun+A3sg->Zero+Verb+Pres+A3pl \|\| STEM(0)\|Verb+Aor+A3sg \|\| STEM(0)\|Verb->AorPart+Adj` | işler (1914), başlar (1228), sonuçlar (1193), şartlar (762), yollar (564) |
| 68 | 23,527 | 0.22% | 43.45% | `M3_LEXICAL_POS_POLYSEMY` | `PARTIAL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `Adj \|\| Adj#ALT \|\| Adv \|\| Conj \|\| Noun+A3sg` | yok (23527) |
| 69 | 23,520 | 0.22% | 43.67% | `M1_INFLECTIONAL_SYNCRETISM` | `FULL_MORPHO` | `OPEN_PARADIGM` | 8.15 | 1,406 | `Noun+A3pl+P2sg+Loc \|\| Noun+A3pl+P3pl+Loc \|\| Noun+A3pl+P3sg+Loc \|\| Noun+A3sg+P3pl+Loc` | saatlerinde (1836), sıralarında (493), ellerinde (492), bölgelerinde (455), tarihlerinde (424) |
| 70 | 22,536 | 0.21% | 43.88% | `M6a_HOMONYM_DIFF_POS` | `FULL_MORPHO` | `CLOSED_LEX` | 1.06 | 14 | `Adj \|\| Verb+Imp+A2sg` | genel (17208), kaç (4285), dik (471), ulu (248), üvey (144) |
| 71 | 21,391 | 0.20% | 44.08% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-2)\|Verb+Opt+A3sg \|\| STEM(0)\|Postp,PCNom` | diye (21391) |
| 72 | 21,099 | 0.20% | 44.28% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `OPEN_PARADIGM` | 3.85 | 52 | `STEM(-1)\|Noun+A3sg+P1sg \|\| STEM(0)\|Noun+A3sg` | adam (4781), bilim (3189), seçim (2763), bölüm (1596), öğretim (1575) |
| 73 | 20,437 | 0.19% | 44.47% | `M4_PROPER_VS_COMMON` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-1)\|Noun+A3sg+P1sg \|\| STEM(-1)\|Noun,Prop+A3sg+P1sg \|\| STEM(0)\|Interj \|\| STEM(0)\|Noun+A3sg` | devam (20435), devâm (2) |
| 74 | 20,329 | 0.19% | 44.66% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-1)\|Verb+Aor+A3sg \|\| STEM(0)\|Noun+A3sg \|\| STEM(0)\|Verb+Imp+A2sg` | yer (20329) |
| 75 | 20,232 | 0.19% | 44.85% | `M7_HYBRID_MULTI_LOCUS` | `FULL_MORPHO` | `OPEN_PARADIGM` | 3.17 | 144 | `Adj->Zero+Noun+A3sg+P2sg+Loc \|\| Adj->Zero+Noun+A3sg+P3sg+Loc \|\| Noun+A3sg+P2sg+Loc \|\| Noun+A3sg+P3sg+Loc` | dışında (6733), zorunda (3984), önünde (3570), civarında (1948), çapında (624) |
| 76 | 20,197 | 0.19% | 45.04% | `M4_PROPER_VS_COMMON` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `Noun+A3sg \|\| Noun,Abbrv+A3sg \|\| Ques+Pres+A3sg` | mi (20191), m.i. (5), m.i (1) |
| 77 | 19,927 | 0.19% | 45.22% | `M4_PROPER_VS_COMMON` | `FULL_MORPHO` | `OPEN_PARADIGM` | 3.55 | 41 | `Noun+A3sg \|\| Noun,Abbrv+A3sg` | abd (5645), ab (2710), tıp (1748), ytl (1732), tv (1302) |
| 78 | 19,697 | 0.18% | 45.41% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-2)\|Adj,Prop->Zero+Noun+A3sg+Dat \|\| STEM(-2)\|Noun,Prop+A3sg+Dat \|\| STEM(0)\|Noun,Prop+A3sg` | türkiye (19697) |
| 79 | 19,682 | 0.18% | 45.59% | `M3_LEXICAL_POS_POLYSEMY` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 2 | `Conj \|\| Interj` | ya (19662), y.a. (9), bre (7), yâ (2), y.a (2) |
| 80 | 19,617 | 0.18% | 45.78% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-4)\|Adj->Zero+Noun+A3sg+P2sg+Loc \|\| STEM(-4)\|Adj->Zero+Noun+A3sg+P3sg+Loc \|\| STEM(-4)\|Noun+A3sg+P2sg+Loc \|\| STEM(-4)\|Noun+A3sg+P3sg+Loc \|\| STEM(0)\|Adv` | içinde (19617) |
| 81 | 19,554 | 0.18% | 45.96% | `M6a_HOMONYM_DIFF_POS` | `FULL_MORPHO` | `CLOSED_LEX` | 0.10 | 2 | `Num,Card \|\| Verb+Imp+A2sg` | bin (19307), kırk (247) |
| 82 | 19,242 | 0.18% | 46.14% | `M2_DERIVATIONAL_SHIFT` | `FULL_MORPHO` | `OPEN_PARADIGM` | 6.01 | 479 | `Verb+Narr+A3sg+Cop \|\| Verb->NarrPart+Adj->Zero+Verb+Pres+A3sg+Cop` | olmuştur (2600), etmiştir (1738), gelmiştir (985), almıştır (915), istemiştir (778) |
| 83 | 19,206 | 0.18% | 46.32% | `M1_INFLECTIONAL_SYNCRETISM` | `FULL_MORPHO` | `OPEN_PARADIGM` | 8.17 | 1,366 | `Noun+A3sg+P2sg+Abl \|\| Noun+A3sg+P3sg+Abl` | yönünden (1387), başından (1005), hesabından (588), suçundan (570), görevinden (342) |
| 84 | 19,084 | 0.18% | 46.50% | `M4_PROPER_VS_COMMON` | `FULL_MORPHO` | `CLOSED_LEX` | 0.97 | 3 | `Adv \|\| Conj \|\| Noun,Prop+A3sg` | yani (12840), belki (6075), meğer (164), yâni (5) |
| 85 | 19,082 | 0.18% | 46.68% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 2.95 | 22 | `STEM(-2)\|Noun+A3sg+P1sg \|\| STEM(-2)\|Noun+A3sg->Zero+Verb+Pres+A1sg \|\| STEM(0)\|Noun+A3sg` | yatırım (3906), tarım (3347), çözüm (3076), kasım (2581), adım (2540) |
| 86 | 18,933 | 0.18% | 46.85% | `M3_LEXICAL_POS_POLYSEMY` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `Adj \|\| Det \|\| Pron,Demons+A3sg` | şu (18933) |
| 87 | 18,915 | 0.18% | 47.03% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-3)\|Noun+A3sg->Acquire+Verb+Imp+A2sg \|\| STEM(-4)\|Verb->Pass+Verb->PresPart+Adj \|\| STEM(0)\|Verb+Imp+A2sg` | yapılan (18915) |
| 88 | 18,802 | 0.18% | 47.21% | `M3_LEXICAL_POS_POLYSEMY` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `Adj \|\| Det \|\| Noun+A3sg` | tüm (18801), tüm. (1) |
| 89 | 18,588 | 0.17% | 47.38% | `M4_PROPER_VS_COMMON` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-2)\|Adj->AsIf+Adj \|\| STEM(-2)\|Adj->Ly+Adv \|\| STEM(-2)\|Noun,Prop+A3sg+Equ \|\| STEM(0)\|Adv` | sadece (18583), sâdece (5) |
| 90 | 18,545 | 0.17% | 47.56% | `M2_DERIVATIONAL_SHIFT` | `FULL_MORPHO` | `OPEN_PARADIGM` | 5.71 | 385 | `Verb->PastPart+Adj+P1sg \|\| Verb->PastPart+Noun+A3sg+P1sg` | olduğum (2081), aldığım (1603), istediğim (1393), yaptığım (1133), ettiğim (896) |
| 91 | 17,996 | 0.17% | 47.72% | `M5a_VOICE_LEXICALIZED` | `LEXICAL_ONLY` | `CLOSED_LEX` | 1.53 | 21 | `STEM(-2)\|Verb->Pass+Verb->PresPart+Adj \|\| STEM(0)\|Verb->PresPart+Adj` | bulunan (10652), alınan (5682), atılan (828), yayılan (250), bozulan (146) |
| 92 | 17,969 | 0.17% | 47.89% | `M2_DERIVATIONAL_SHIFT` | `FULL_MORPHO` | `OPEN_PARADIGM` | 4.92 | 473 | `Verb->Able+Verb+Aor+A3sg \|\| Verb->Able+Verb->AorPart+Adj` | olabilir (7227), edebilir (953), yapabilir (645), alabilir (574), gelebilir (544) |
| 93 | 17,592 | 0.16% | 48.06% | `M5b_STEM_SUFFIX_COLLISION` | `PARTIAL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-1)\|Noun+A3sg+P2sg \|\| STEM(-2)\|Noun+A3sg+Gen \|\| STEM(-2)\|Noun+A3sg+P2sg \|\| STEM(-2)\|Verb+Imp+A2pl \|\| STEM(0)\|Adj` | sayın (17592) |
| 94 | 17,579 | 0.16% | 48.22% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `OPEN_PARADIGM` | 3.49 | 31 | `STEM(-2)\|Noun+A3sg->With+Adj \|\| STEM(0)\|Adj` | davalı (4290), sorumlu (2158), yetkili (2125), yerli (2067), adli (2038) |
| 95 | 17,448 | 0.16% | 48.38% | `M4_PROPER_VS_COMMON` | `FULL_MORPHO` | `OPEN_PARADIGM` | 5.58 | 173 | `Adj \|\| Noun,Prop+A3sg` | yerel (2830), nato (902), muhteşem (836), dürüst (705), görsel (615) |
| 96 | 17,019 | 0.16% | 48.54% | `M2_DERIVATIONAL_SHIFT` | `FULL_MORPHO` | `OPEN_PARADIGM` | 4.58 | 252 | `Verb->Pass+Verb+Fut+A3sg \|\| Verb->Pass+Verb->FutPart+Adj` | yapılacak (5462), edilecek (1913), verilecek (1597), açılacak (636), kurulacak (495) |
| 97 | 16,957 | 0.16% | 48.70% | `M4_PROPER_VS_COMMON` | `FULL_MORPHO` | `CLOSED_LEX` | 0.36 | 4 | `STEM(-1)\|Adj->Zero+Noun+A3sg+P3sg+Loc \|\| STEM(-1)\|Noun+A3sg+P3sg+Loc \|\| STEM(0)\|Noun,Prop+A3sg+P2sg+Loc \|\| STEM(0)\|Noun,Prop+A3sg+P3sg+Loc` | arasında (15799), arkasında (1151), tavasında (4), mavisinde (3) |
| 98 | 16,845 | 0.16% | 48.86% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 1.52 | 11 | `STEM(-4)\|Noun+A3sg+P3sg+Ins \|\| STEM(0)\|Adv` | nedeniyle (11874), yoluyla (1652), sebebiyle (1597), itibarıyla (1092), eliyle (337) |
| 99 | 16,540 | 0.15% | 49.02% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-2)\|Noun+A3sg+Ins \|\| STEM(0)\|Adj \|\| STEM(0)\|Adv \|\| STEM(0)\|Postp,PCAbl` | fazla (16540) |
| 100 | 16,428 | 0.15% | 49.17% | `M3_LEXICAL_POS_POLYSEMY` | `FULL_MORPHO` | `OPEN_PARADIGM` | 2.96 | 43 | `Interj \|\| Noun+A3sg` | dikkat (5685), teslim (4507), merhaba (1596), kamera (844), kurban (835) |
| 101 | 16,339 | 0.15% | 49.32% | `M5b_STEM_SUFFIX_COLLISION` | `PARTIAL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-1)\|Verb+Aor+A3sg \|\| STEM(-1)\|Verb->AorPart+Adj \|\| STEM(-2)\|Verb+Aor+A3sg \|\| STEM(-2)\|Verb->AorPart+Adj \|\| STEM(0)\|Noun+A3sg \|\| STEM(0)\|Verb+Imp+A2sg` | karar (16339) |
| 102 | 16,215 | 0.15% | 49.47% | `M3_LEXICAL_POS_POLYSEMY` | `FULL_MORPHO` | `CLOSED_LEX` | 1.26 | 3 | `Adj \|\| Adv \|\| Interj \|\| Noun+A3sg` | geri (10688), hayır (3071), rahat (2455), hâyır (1) |
| 103 | 16,119 | 0.15% | 49.63% | `M7_HYBRID_MULTI_LOCUS` | `PARTIAL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `Noun+A3sg+P1sg \|\| Noun+A3sg->Zero+Verb+Pres+A1sg \|\| Pron,Pers+A1sg+Gen \|\| Pron,Pers+A1sg->Zero+Verb+Pres+A1sg` | benim (16119) |
| 104 | 15,977 | 0.15% | 49.78% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `OPEN_PARADIGM` | 3.63 | 54 | `STEM(-1)\|Verb->Pass+Verb->PresPart+Adj \|\| STEM(0)\|Noun+A3sg->Acquire+Verb+Imp+A2sg` | verilen (5694), görülen (1540), gösterilen (1142), getirilen (1116), öngörülen (916) |
| 105 | 15,945 | 0.15% | 49.92% | `M3_LEXICAL_POS_POLYSEMY` | `FULL_MORPHO` | `OPEN_PARADIGM` | 5.26 | 296 | `Adj->Zero+Noun+A3sg+Loc \|\| Noun+A3sg+Loc` | yukarıda (2686), önde (1275), geride (1232), aşağıda (954), ileride (825) |
| 106 | 15,837 | 0.15% | 50.07% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `OPEN_PARADIGM` | 2.86 | 28 | `STEM(-2)\|Verb+Neg+Imp+A2sg \|\| STEM(-2)\|Verb->Inf2+Noun+A3sg \|\| STEM(0)\|Noun+A3sg` | savunma (4223), araştırma (3965), açıklama (3475), görüşme (1265), açma (737) |
| 107 | 15,823 | 0.15% | 50.22% | `M3_LEXICAL_POS_POLYSEMY` | `FULL_MORPHO` | `OPEN_PARADIGM` | 6.19 | 364 | `Adj->Zero+Noun+A3sg+Dat \|\| Noun+A3sg+Dat` | karşıya (1692), geriye (1220), açığa (709), çocuğa (676), toprağa (668) |
| 108 | 15,694 | 0.15% | 50.37% | `M7_HYBRID_MULTI_LOCUS` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `Adj \|\| Adv \|\| Noun+A3sg \|\| Noun+A3sg+Dat \|\| Postp,PCDat` | karşı (15694) |
| 109 | 15,509 | 0.15% | 50.51% | `M4_PROPER_VS_COMMON` | `FULL_MORPHO` | `OPEN_PARADIGM` | 6.33 | 359 | `Noun+A3pl+Acc \|\| Noun+A3pl+P3pl \|\| Noun+A3pl+P3sg \|\| Noun+A3sg+P3pl \|\| Noun,Prop+A3pl+Acc \|\| Noun,Prop+A3pl+P3pl \|\| Noun,Prop+A3pl+P3sg \|\| Noun,Prop+A3sg+P3pl` | kuvvetleri (1249), ülkeleri (957), araçları (810), kaynakları (765), belgeleri (525) |
| 110 | 15,368 | 0.14% | 50.66% | `M7_HYBRID_MULTI_LOCUS` | `FULL_MORPHO` | `OPEN_PARADIGM` | 6.24 | 401 | `Adj->Zero+Noun+A3pl+Acc \|\| Adj->Zero+Noun+A3pl+P3pl \|\| Adj->Zero+Noun+A3pl+P3sg \|\| Adj->Zero+Noun+A3sg+P3pl \|\| Noun+A3pl+Acc \|\| Noun+A3pl+P3pl \|\| Noun+A3pl+P3sg \|\| Noun+A3sg+P3pl` | insanları (2261), üyeleri (1461), memurları (595), yakınları (576), kartları (470) |
| 111 | 15,257 | 0.14% | 50.80% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-1)\|Pron,Demons+A3sg+Acc \|\| STEM(0)\|Noun+A3sg+Acc \|\| STEM(0)\|Noun+A3sg+P3sg` | bunu (15257) |
| 112 | 15,201 | 0.14% | 50.94% | `M3_LEXICAL_POS_POLYSEMY` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `Adj \|\| Postp,PCAbl` | başka (15201) |
| 113 | 15,129 | 0.14% | 51.08% | `M1_INFLECTIONAL_SYNCRETISM` | `FULL_MORPHO` | `CLOSED_LEX` | 0.96 | 11 | `Noun,Time+A3sg+P2sg+Loc \|\| Noun,Time+A3sg+P3sg+Loc` | yılında (11750), döneminde (2774), sezonunda (515), akşamında (22), baharında (20) |
| 114 | 14,928 | 0.14% | 51.22% | `M3_LEXICAL_POS_POLYSEMY` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `Noun+A3sg \|\| Pron,Pers+A1pl` | biz (14928) |
| 115 | 14,873 | 0.14% | 51.36% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-1)\|Pron,Demons+A3sg+Gen \|\| STEM(0)\|Noun+A3sg+Gen \|\| STEM(0)\|Noun+A3sg+P2sg` | bunun (14873) |
| 116 | 14,857 | 0.14% | 51.50% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-1)\|Verb+Opt+A3sg \|\| STEM(0)\|Adv \|\| STEM(0)\|Conj \|\| STEM(0)\|Verb+Imp+A2sg` | bile (14857) |
| 117 | 14,806 | 0.14% | 51.64% | `M2_DERIVATIONAL_SHIFT` | `FULL_MORPHO` | `OPEN_PARADIGM` | 5.59 | 368 | `Verb->PastPart+Adj+P1pl \|\| Verb->PastPart+Noun+A3sg+P1pl` | geçtiğimiz (2494), olduğumuz (1251), yaptığımız (1244), aldığımız (785), ettiğimiz (650) |
| 118 | 14,801 | 0.14% | 51.78% | `M4_PROPER_VS_COMMON` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-2)\|Noun,Abbrv+A3sg->Zero+Verb+Past+A3sg \|\| STEM(0)\|Pron,Reflex+A3sg+P3sg` | kendi (14801) |
| 119 | 14,719 | 0.14% | 51.92% | `M2_DERIVATIONAL_SHIFT` | `FULL_MORPHO` | `OPEN_PARADIGM` | 6.83 | 940 | `Noun+A3sg+P1sg \|\| Noun+A3sg->Zero+Verb+Pres+A1sg` | arkadaşım (1740), oğlum (1223), kızım (1202), ihtiyacım (726), işim (455) |
| 120 | 14,580 | 0.14% | 52.05% | `M2_DERIVATIONAL_SHIFT` | `FULL_MORPHO` | `OPEN_PARADIGM` | 5.96 | 372 | `Verb+Past+A1pl \|\| Verb->PastPart+Adj \|\| Verb->PastPart+Noun+A3sg` | yaptık (1410), olduk (904), verdik (864), gördük (816), geldik (700) |
| 121 | 14,324 | 0.13% | 52.19% | `M5b_STEM_SUFFIX_COLLISION` | `PARTIAL_MORPHO` | `CLOSED_LEX` | 2.28 | 8 | `STEM(-2)\|Adj->Zero+Noun+A3sg->With+Adj \|\| STEM(-2)\|Noun+A3sg->With+Adj \|\| STEM(0)\|Adj` | gerekli (5038), çeşitli (4039), geçerli (2298), toplu (1775), saklı (510) |
| 122 | 14,222 | 0.13% | 52.32% | `M4_PROPER_VS_COMMON` | `FULL_MORPHO` | `OPEN_PARADIGM` | 5.99 | 462 | `Noun+A3sg+Dat \|\| Noun,Prop+A3sg+Dat` | dünyaya (1763), ülkeye (1175), bankaya (1103), sahaya (562), ayağa (557) |
| 123 | 14,084 | 0.13% | 52.45% | `M1_INFLECTIONAL_SYNCRETISM` | `FULL_MORPHO` | `OPEN_PARADIGM` | 8.93 | 1,573 | `Noun+A3pl+P2sg+Abl \|\| Noun+A3pl+P3pl+Abl \|\| Noun+A3pl+P3sg+Abl \|\| Noun+A3sg+P3pl+Abl` | saatlerinden (291), ellerinden (279), takımlarından (240), yerlerinden (237), isimlerinden (219) |
| 124 | 13,805 | 0.13% | 52.58% | `M4_PROPER_VS_COMMON` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-1)\|Verb->PresPart+Adj \|\| STEM(-2)\|Noun,Abbrv+A3sg+Abl \|\| STEM(0)\|Noun+A3sg+P2sg` | eden (13805) |
| 125 | 13,756 | 0.13% | 52.71% | `M2_DERIVATIONAL_SHIFT` | `FULL_MORPHO` | `OPEN_PARADIGM` | 5.11 | 251 | `Verb->Pass+Verb+Narr+A3sg \|\| Verb->Pass+Verb->NarrPart+Adj` | edilmiş (2578), yapılmış (2241), verilmiş (1719), yazılmış (398), görülmüş (372) |
| 126 | 13,744 | 0.13% | 52.84% | `M2_DERIVATIONAL_SHIFT` | `FULL_MORPHO` | `OPEN_PARADIGM` | 5.91 | 400 | `Verb+Prog2+A3sg+Cop \|\| Verb->Inf1+Noun+A3sg+Loc->Zero+Verb+Pres+A3sg+Cop` | etmektedir (1630), gerekmektedir (1555), almaktadır (781), olmaktadır (697), gelmektedir (521) |
| 127 | 13,733 | 0.13% | 52.97% | `M4_PROPER_VS_COMMON` | `FULL_MORPHO` | `OPEN_PARADIGM` | 4.56 | 131 | `Noun+A3sg+P3sg \|\| Noun,Prop+A3sg+P3sg` | bankası (2549), annesi (1126), belgesi (1076), vergisi (1062), ilgisi (856) |
| 128 | 13,677 | 0.13% | 53.10% | `M4_PROPER_VS_COMMON` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-2)\|Noun+A3sg+Dat \|\| STEM(-2)\|Noun+A3sg+P2sg+Dat \|\| STEM(-2)\|Noun+A3sg+P3sg+Dat \|\| STEM(-3)\|Noun,Prop+A3sg+P2sg+Dat \|\| STEM(-3)\|Noun,Prop+A3sg+P3sg+Dat \|\| STEM(0)\|Adv` | üzerine (13677) |
| 129 | 13,615 | 0.13% | 53.23% | `M4_PROPER_VS_COMMON` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-1)\|Noun,Prop+A3sg+P2sg+Dat \|\| STEM(0)\|Noun+A3sg+Dat \|\| STEM(0)\|Pron,Pers+A1sg+Dat \|\| STEM(0)\|Verb+Opt+A3sg` | bana (13615) |
| 130 | 13,481 | 0.13% | 53.35% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-1)\|Adj->Zero+Noun+A3sg+P2sg+Acc \|\| STEM(0)\|Noun+A3sg+Acc \|\| STEM(0)\|Noun+A3sg+P3sg` | başkanı (13481) |
| 131 | 13,322 | 0.12% | 53.48% | `M2_DERIVATIONAL_SHIFT` | `FULL_MORPHO` | `OPEN_PARADIGM` | 5.91 | 415 | `Verb->PastPart+Adj+P3pl \|\| Verb->PastPart+Noun+A3pl+Acc \|\| Verb->PastPart+Noun+A3pl+P3pl \|\| Verb->PastPart+Noun+A3pl+P3sg \|\| Verb->PastPart+Noun+A3sg+P3pl` | yaptıkları (1707), oldukları (1572), ettikleri (801), aldıkları (758), verdikleri (624) |
| 132 | 13,252 | 0.12% | 53.60% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 0.06 | 2 | `STEM(-1)\|Noun+A3sg+P2sg \|\| STEM(0)\|Postp,PCDat` | ilişkin (13167), hitaben (85) |
| 133 | 13,157 | 0.12% | 53.72% | `M3_LEXICAL_POS_POLYSEMY` | `LEXICAL_ONLY` | `OPEN_PARADIGM` | 4.88 | 233 | `Adj->Ness+Noun+A3sg \|\| Noun+A3sg->Ness+Noun+A3sg` | değişiklik (3141), askerlik (1391), üyelik (885), insanlık (771), eşlik (638) |
| 134 | 13,143 | 0.12% | 53.85% | `M2_DERIVATIONAL_SHIFT` | `FULL_MORPHO` | `OPEN_PARADIGM` | 6.22 | 492 | `Verb->Inf2+Noun+A3sg+Abl \|\| Verb->WithoutHavingDoneSo+Adv` | olmadan (2208), almadan (590), vermeden (506), etmeden (502), yapmadan (501) |
| 135 | 13,142 | 0.12% | 53.97% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-1)\|Noun+A3sg+P2sg \|\| STEM(-2)\|Verb->PresPart+Adj \|\| STEM(-3)\|Noun+A3sg->Acquire+Verb+Imp+A2sg \|\| STEM(0)\|Adj` | gelen (13142) |
| 136 | 13,094 | 0.12% | 54.09% | `M4_PROPER_VS_COMMON` | `PARTIAL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-2)\|Adj->Zero+Noun+A3sg+Loc \|\| STEM(-2)\|Noun+A3sg+Loc \|\| STEM(-2)\|Noun,Prop+A3sg+Loc \|\| STEM(-5)\|Adj->Ness+Noun+A3sg+Loc \|\| STEM(-5)\|Num,Card->Ness+Noun+A3sg+Loc \|\| STEM(-5)\|Num,Card->Zero+Noun+A3sg->Ness+Noun+A3sg+Loc \|\| STEM(0)\|Adv \|\| STEM(0)\|Postp,PCIns` | birlikte (13094) |
| 137 | 12,966 | 0.12% | 54.21% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 0.16 | 2 | `STEM(-5)\|Adj->Zero+Noun+A3sg+P2sg+Abl \|\| STEM(-5)\|Adj->Zero+Noun+A3sg+P3sg+Abl \|\| STEM(-5)\|Noun+A3sg+P2sg+Abl \|\| STEM(-5)\|Noun+A3sg+P3sg+Abl \|\| STEM(0)\|Adv` | ardından (12664), önünden (302) |
| 138 | 12,860 | 0.12% | 54.33% | `M4_PROPER_VS_COMMON` | `PARTIAL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-4)\|Noun+A3sg+P2sg+Loc \|\| STEM(-4)\|Noun+A3sg+P3sg+Loc \|\| STEM(-4)\|Noun,Prop+A3sg+P2sg+Loc \|\| STEM(-4)\|Noun,Prop+A3sg+P2sg+Loc#ALT \|\| STEM(-4)\|Noun,Prop+A3sg+P3sg+Loc \|\| STEM(-4)\|Noun,Prop+A3sg+P3sg+Loc#ALT \|\| STEM(0)\|Adv` | hakkında (12860) |
| 139 | 12,618 | 0.12% | 54.45% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-1)\|Adj->Zero+Noun+A3sg+Acc \|\| STEM(-1)\|Adj->Zero+Noun+A3sg+P3sg \|\| STEM(-1)\|Noun+A3sg+Acc \|\| STEM(-1)\|Noun+A3sg+P3sg \|\| STEM(0)\|Adj \|\| STEM(0)\|Det` | bazı (12607), bâzı (11) |
| 140 | 12,560 | 0.12% | 54.57% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 0.08 | 2 | `STEM(-2)\|Verb->PresPart+Adj \|\| STEM(0)\|Adj` | geçen (12437), esen (123) |
| 141 | 12,417 | 0.12% | 54.69% | `M2_DERIVATIONAL_SHIFT` | `FULL_MORPHO` | `OPEN_PARADIGM` | 5.54 | 278 | `Verb->Pass+Verb+Aor+A3sg \|\| Verb->Pass+Verb->AorPart+Adj` | edilir (1652), yapılır (1487), verilir (1039), sayılır (694), görülür (552) |
| 142 | 12,369 | 0.12% | 54.80% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 1.75 | 10 | `STEM(-2)\|Noun+A3sg->With+Adj \|\| STEM(0)\|Adj \|\| STEM(0)\|Noun+A3sg` | yazılı (7404), dolu (2018), sözlü (1529), sevgili (1068), sütlü (105) |
| 143 | 12,297 | 0.12% | 54.92% | `M5b_STEM_SUFFIX_COLLISION` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-1)\|Noun+A3sg+Dat \|\| STEM(-2)\|Adj->AsIf+Adj \|\| STEM(-2)\|Adj->Ly+Adv \|\| STEM(0)\|Adv` | ayrıca (12297) |
| 144 | 12,131 | 0.11% | 55.03% | `M4_PROPER_VS_COMMON` | `PARTIAL_MORPHO` | `OPEN_PARADIGM` | 5.81 | 334 | `Noun+A3pl \|\| Noun+A3sg->Zero+Verb+Pres+A3pl \|\| Noun,Prop+A3pl \|\| Noun,Prop+A3sg->Zero+Verb+Pres+A3pl` | ülkeler (1637), şikayetler (1202), araçlar (916), anneler (532), kuvvetler (369) |
| 145 | 12,121 | 0.11% | 55.15% | `M7_HYBRID_MULTI_LOCUS` | `FULL_MORPHO` | `OPEN_PARADIGM` | 5.04 | 336 | `Adj->Zero+Noun+A3pl+Gen \|\| Adj->Zero+Noun+A3pl+P2sg \|\| Noun+A3pl+Gen \|\| Noun+A3pl+P2sg` | insanların (3890), kadınların (1329), askerlerin (528), hayvanların (420), üyelerin (382) |
| 146 | 12,041 | 0.11% | 55.26% | `M3_LEXICAL_POS_POLYSEMY` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `Adj \|\| Noun+A3sg \|\| Postp,PCDat` | doğru (12041) |
| 147 | 12,030 | 0.11% | 55.37% | `M2_DERIVATIONAL_SHIFT` | `FULL_MORPHO` | `OPEN_PARADIGM` | 6.29 | 479 | `Verb+Narr+Past+A3sg \|\| Verb->NarrPart+Adj->Zero+Verb+Past+A3sg` | olmuştu (1109), etmişti (789), demişti (588), gelmişti (568), söylemişti (562) |
| 148 | 12,012 | 0.11% | 55.48% | `M4_PROPER_VS_COMMON` | `FULL_MORPHO` | `CLOSED_LEX` | 0.00 | 1 | `STEM(-1)\|Noun,Prop+A3sg+Acc \|\| STEM(-1)\|Noun,Prop+A3sg+P3sg \|\| STEM(0)\|Conj` | çünkü (12012) |
| 149 | 11,964 | 0.11% | 55.60% | `M3_LEXICAL_POS_POLYSEMY` | `FULL_MORPHO` | `CLOSED_LEX` | 1.79 | 47 | `Adj->Zero+Noun+A3sg+P3sg+Loc \|\| Noun+A3sg+P3sg+Loc` | içerisinde (7060), karşısında (3271), yarısında (751), gerisinde (236), kuzeydoğusunda (82) |
| 150 | 11,657 | 0.11% | 55.70% | `M4_PROPER_VS_COMMON` | `FULL_MORPHO` | `CLOSED_LEX` | 0.02 | 2 | `STEM(-2)\|Verb+Aor+A3sg \|\| STEM(-2)\|Verb->AorPart+Adj \|\| STEM(0)\|Adj \|\| STEM(0)\|Noun+A3sg \|\| STEM(0)\|Noun,Prop+A3sg` | olur (11635), çapar (22) |

## 7. Detailed Profiles of Top 40 Ambiguity Signatures (with Context Anchor Frames)

### #1: `Adj || Adv || Det || Num,Card`

- **Occurrences**: `342,921` (**3.21%** of all ambiguous words) | **Candidates ($K$)**: `4` | **Distinct Items**: `4` | **Distinct `lastGroup`s**: `4`
- **Mechanism**: `M3_LEXICAL_POS_POLYSEMY` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `1` tracked lemmas ($H_{\text{lemma}} = 0.00$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 5.27$ bits)
- **Sentence Position Distribution**: Initial=`5.8%`, Internal=`87.7%`, Pre-Final=`6.4%`, Sentence-Final=`0.1%`
- **Top Surface Words**: `bir` (342,920), `bîr` (1)
- **Top Lemmas / Lemma Pairs**: `bir` (342,921)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[U:Adj:Adj __ U:Noun:A3sg]` (7,924), `[A:Adj/Noun __ U:Noun:A3sg]` (7,201), `[A:Adj/Adv/Noun __ U:Noun:A3sg]` (6,013), `[A:Adj/Adv __ U:Noun:A3sg]` (5,724), `[U:Adj __ U:Noun:A3sg]` (5,557), `[A:Adj/Verb __ U:Noun:A3sg]` (4,621)

### #2: `Noun+A3sg+Acc || Noun+A3sg+P3sg`

- **Occurrences**: `288,597` (**2.70%** of all ambiguous words) | **Candidates ($K$)**: `2` | **Distinct Items**: `1` | **Distinct `lastGroup`s**: `2`
- **Mechanism**: `M1_INFLECTIONAL_SYNCRETISM` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `2,000` tracked lemmas ($H_{\text{lemma}} = 8.72$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 4.51$ bits)
- **Sentence Position Distribution**: Initial=`2.7%`, Internal=`81.5%`, Pre-Final=`12.3%`, Sentence-Final=`3.5%`
- **Active Lexicon `RootAttribute`s**: Voicing (15.8%), LastVowelDrop (4.9%), Doubling (0.2%), CompoundP3sg (0.0%), InverseHarmony (4.1%), Ext (0.6%)
- **Top Surface Words**: `kararı` (7,075), `sonucu` (5,118), `yolu` (3,751), `adı` (3,545), `işi` (3,504), `sistemi` (3,354), `yanı` (3,191), `yeri` (3,032), `müdürü` (3,007), `örgütü` (2,614), `ihtiyacı` (2,319), `ücreti` (2,310)
- **Top Lemmas / Lemma Pairs**: `karar` (7,075), `sonuç` (5,118), `yol` (3,753), `ad` (3,545), `iş` (3,504), `sistem` (3,354), `yan` (3,191), `yer` (3,033), `müdür` (3,007), `örgüt` (2,614)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[U:Noun:A3sg __ U:Noun:A3sg]` (4,248), `[U:Noun:A3sg __ C:Noun]` (4,041), `[U:Noun:A3sg __ U:Conj]` (2,983), `[U:Noun:A3sg __ A:Adj/Verb]` (2,752), `[U:Noun:A3sg __ A:Conj/Noun/Postp/Verb]` (2,399), `[U:Noun:A3sg __ A:Noun/Noun,Prop]` (2,144)

### #3: `Det || Pron,Demons+A3sg`

- **Occurrences**: `236,172` (**2.21%** of all ambiguous words) | **Candidates ($K$)**: `2` | **Distinct Items**: `2` | **Distinct `lastGroup`s**: `2`
- **Mechanism**: `M3_LEXICAL_POS_POLYSEMY` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `1` tracked lemmas ($H_{\text{lemma}} = 0.00$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 4.93$ bits)
- **Sentence Position Distribution**: Initial=`34.7%`, Internal=`62.5%`, Pre-Final=`2.0%`, Sentence-Final=`0.8%`
- **Top Surface Words**: `bu` (236,170), `b.u` (2)
- **Top Lemmas / Lemma Pairs**: `bu` (236,172)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[<S> __ C:Noun]` (12,753), `[<S> __ A:Noun/Noun,Prop]` (9,762), `[<S> __ U:Noun:A3sg]` (7,605), `[<S> __ U:Noun:Loc]` (7,154), `[<S> __ A:Noun/Verb]` (7,026), `[<S> __ U:Noun:Ins]` (5,568)

### #4: `Adj || Noun+A3sg`

- **Occurrences**: `211,228` (**1.98%** of all ambiguous words) | **Candidates ($K$)**: `2` | **Distinct Items**: `2` | **Distinct `lastGroup`s**: `2`
- **Mechanism**: `M3_LEXICAL_POS_POLYSEMY` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `765` tracked lemmas ($H_{\text{lemma}} = 7.31$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 4.21$ bits)
- **Sentence Position Distribution**: Initial=`10.3%`, Internal=`82.5%`, Pre-Final=`5.5%`, Sentence-Final=`1.7%`
- **Active Lexicon `RootAttribute`s**: Voicing (20.7%), LastVowelDrop (1.2%), Doubling (0.1%), InverseHarmony (8.7%), Ext (0.7%)
- **Top Surface Words**: `bütün` (11,756), `sosyal` (8,613), `kamu` (5,883), `üst` (4,488), `spor` (4,436), `temel` (4,117), `mevcut` (4,065), `ortak` (3,825), `dış` (3,596), `tür` (3,507), `düşük` (3,365), `kuzey` (3,171)
- **Top Lemmas / Lemma Pairs**: `bütün` (11,756), `sosyal` (8,613), `kamu` (5,883), `üst` (4,488), `spor` (4,436), `temel` (4,117), `mevcut` (4,065), `ortak` (3,825), `dış` (3,596), `tür` (3,507)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[<S> __ U:Noun:A3sg]` (2,829), `[<S> __ C:Noun]` (2,714), `[U:Conj __ C:Noun]` (1,976), `[<S> __ A:Noun/Noun,Prop]` (1,791), `[C:Noun __ C:Noun]` (1,680), `[<S> __ A:Noun/Verb]` (1,674)

### #5: `Noun+A3sg || Noun,Prop+A3sg`

- **Occurrences**: `205,039` (**1.92%** of all ambiguous words) | **Candidates ($K$)**: `2` | **Distinct Items**: `2` | **Distinct `lastGroup`s**: `2`
- **Mechanism**: `M4_PROPER_VS_COMMON` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `957` tracked lemmas ($H_{\text{lemma}} = 7.50$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 4.16$ bits)
- **Sentence Position Distribution**: Initial=`11.2%`, Internal=`79.2%`, Pre-Final=`8.1%`, Sentence-Final=`1.5%`
- **Active Lexicon `RootAttribute`s**: Voicing (29.0%), LastVowelDrop (3.5%), Doubling (0.1%), InverseHarmony (5.2%), Ext (2.7%)
- **Top Surface Words**: `dünya` (9,592), `ülke` (5,817), `araç` (4,810), `mücadele` (4,560), `vergi` (4,464), `grup` (4,230), `kontrol` (4,024), `faiz` (3,713), `anne` (3,421), `şehit` (3,415), `müşteri` (3,152), `elektrik` (3,115)
- **Top Lemmas / Lemma Pairs**: `Dünya/dünya` (9,601), `Ülke/ülke` (5,817), `Araç/araç` (4,810), `Mücadele/mücadele` (4,564), `Vergi/vergi` (4,464), `Grup/grup` (4,230), `Kontrol/kontrol` (4,024), `Faiz/faiz` (3,714), `Anne/anne` (3,421), `Şehit/şehit` (3,415)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[<S> __ C:Noun]` (3,376), `[U:Num __ U:Num]` (2,696), `[<S> __ A:Noun/Noun,Prop]` (2,258), `[C:Noun __ U:Noun,Prop:A3sg]` (2,106), `[U:Noun:A3sg __ C:Noun]` (1,864), `[<S> __ U:Noun:A3sg]` (1,640)

### #6: `Adj || Adv || Noun+A3sg`

- **Occurrences**: `135,565` (**1.27%** of all ambiguous words) | **Candidates ($K$)**: `3` | **Distinct Items**: `3` | **Distinct `lastGroup`s**: `3`
- **Mechanism**: `M3_LEXICAL_POS_POLYSEMY` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `30` tracked lemmas ($H_{\text{lemma}} = 3.44$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 4.19$ bits)
- **Sentence Position Distribution**: Initial=`11.1%`, Internal=`76.9%`, Pre-Final=`9.0%`, Sentence-Final=`3.0%`
- **Active Lexicon `RootAttribute`s**: Voicing (12.3%)
- **Top Surface Words**: `ilk` (30,043), `iyi` (25,607), `tek` (13,980), `güzel` (13,034), `artık` (12,486), `tam` (9,313), `zor` (6,022), `yanlış` (5,487), `ağır` (4,622), `kolay` (3,730), `serbest` (2,969), `soğuk` (1,745)
- **Top Lemmas / Lemma Pairs**: `ilk` (30,043), `iyi` (25,608), `tek` (13,999), `güzel` (13,034), `artık` (12,486), `tam` (9,313), `zor` (6,022), `yanlış` (5,487), `ağır` (4,622), `kolay` (3,730)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[<S> __ U:Noun:A3sg]` (1,719), `[A:Adv/Noun __ U:Noun:A3sg]` (1,550), `[A:Adj/Adv/Det/Postp,PCAbl __ A:Adj/Adv/Det/Num]` (1,486), `[<S> __ A:Adj/Adv/Det/Num]` (1,155), `[A:Adv/Noun __ C:Noun]` (1,120), `[<S> __ C:Noun]` (1,095)

### #7: `Noun+A3pl+Acc || Noun+A3pl+P3pl || Noun+A3pl+P3sg || Noun+A3sg+P3pl`

- **Occurrences**: `135,449` (**1.27%** of all ambiguous words) | **Candidates ($K$)**: `4` | **Distinct Items**: `1` | **Distinct `lastGroup`s**: `4`
- **Mechanism**: `M1_INFLECTIONAL_SYNCRETISM` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `2,000` tracked lemmas ($H_{\text{lemma}} = 8.83$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 4.55$ bits)
- **Sentence Position Distribution**: Initial=`2.5%`, Internal=`83.3%`, Pre-Final=`11.3%`, Sentence-Final=`3.0%`
- **Active Lexicon `RootAttribute`s**: Voicing (9.3%), LastVowelDrop (2.4%), Doubling (0.3%), InverseHarmony (1.8%), Ext (0.2%)
- **Top Surface Words**: `ekipleri` (2,459), `işleri` (2,217), `hizmetleri` (1,653), `fiyatları` (1,619), `ürünleri` (1,554), `sorunları` (1,545), `şeyleri` (1,344), `bilgileri` (1,294), `işlemleri` (1,270), `sonuçları` (1,260), `hükümleri` (1,118), `sınırları` (1,003)
- **Top Lemmas / Lemma Pairs**: `ekip` (2,459), `iş` (2,217), `hizmet` (1,654), `fiyat` (1,619), `ürün` (1,554), `sorun` (1,545), `şey` (1,344), `bilgi` (1,294), `işlem` (1,270), `sonuç` (1,260)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[U:Noun:A3sg __ U:Conj]` (2,071), `[U:Noun:A3sg __ C:Noun]` (1,983), `[U:Noun:A3sg __ U:Noun:A3sg]` (1,976), `[U:Noun:A3sg __ A:Adv/Noun]` (1,471), `[U:Noun:A3sg __ A:Noun/Noun,Prop]` (1,382), `[U:Noun:A3sg __ A:Conj/Noun/Postp/Verb]` (1,191)

### #8: `STEM(-2)|Adj->Zero+Noun+A3sg+Gen || STEM(-2)|Adj->Zero+Noun+A3sg+P2sg || STEM(-2)|Noun+A3sg+Gen || STEM(-2)|Noun+A3sg+P2sg || STEM(-2)|Verb+Imp+A2pl || STEM(0)|Postp,PCGen || STEM(0)|Postp,PCNom`

- **Occurrences**: `121,549` (**1.14%** of all ambiguous words) | **Candidates ($K$)**: `7` | **Distinct Items**: `5` | **Distinct `lastGroup`s**: `7`
- **Mechanism**: `M5b_STEM_SUFFIX_COLLISION` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `1` tracked lemmas ($H_{\text{lemma}} = 0.00$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 6.14$ bits)
- **Sentence Position Distribution**: Initial=`0.1%`, Internal=`91.1%`, Pre-Final=`6.2%`, Sentence-Final=`2.7%`
- **Top Surface Words**: `için` (121,549)
- **Top Lemmas / Lemma Pairs**: `iç/için/içmek` (121,549)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[U:Noun:A3sg __ U:Noun:A3sg]` (3,005), `[A:Adj/Noun __ U:Noun:A3sg]` (1,915), `[U:Noun:A3sg __ A:Adj/Noun]` (1,728), `[U:Noun:A3sg __ A:Noun/Verb]` (1,450), `[C:Noun __ U:Noun:A3sg]` (1,446), `[A:Noun/Verb __ U:Noun:A3sg]` (1,438)

### #9: `Conj || Noun+A3sg || Verb+Imp+A2sg`

- **Occurrences**: `111,361` (**1.04%** of all ambiguous words) | **Candidates ($K$)**: `3` | **Distinct Items**: `3` | **Distinct `lastGroup`s**: `3`
- **Mechanism**: `M6a_HOMONYM_DIFF_POS` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `1` tracked lemmas ($H_{\text{lemma}} = 0.00$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 3.77$ bits)
- **Sentence Position Distribution**: Initial=`0.1%`, Internal=`87.1%`, Pre-Final=`11.6%`, Sentence-Final=`1.2%`
- **Top Surface Words**: `de` (111,361)
- **Top Lemmas / Lemma Pairs**: `de/demek` (111,361)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[C:Noun __ U:Noun:A3sg]` (900), `[A:Adv/Conj __ U:Noun:A3sg]` (740), `[A:Noun/Pron __ U:Noun:A3sg]` (736), `[C:Noun __ A:Adj/Noun]` (704), `[A:Noun/Pron __ A:Noun/Pron]` (549), `[C:Noun __ C:Noun]` (541)

### #10: `Adj || Adv`

- **Occurrences**: `104,848` (**0.98%** of all ambiguous words) | **Candidates ($K$)**: `2` | **Distinct Items**: `2` | **Distinct `lastGroup`s**: `2`
- **Mechanism**: `M3_LEXICAL_POS_POLYSEMY` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `118` tracked lemmas ($H_{\text{lemma}} = 4.33$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 3.89$ bits)
- **Sentence Position Distribution**: Initial=`11.0%`, Internal=`77.1%`, Pre-Final=`9.5%`, Sentence-Final=`2.4%`
- **Active Lexicon `RootAttribute`s**: Voicing (1.2%), InverseHarmony (0.0%), Ext (0.0%)
- **Top Surface Words**: `böyle` (17,174), `nasıl` (16,236), `biraz` (9,251), `hemen` (6,840), `pek` (6,273), `kötü` (6,254), `ayrı` (5,238), `ciddi` (4,497), `tabii` (3,442), `hazır` (3,081), `boş` (2,490), `sert` (2,229)
- **Top Lemmas / Lemma Pairs**: `böyle` (17,174), `nasıl` (16,236), `biraz` (9,251), `hemen` (6,840), `pek` (6,273), `kötü` (6,254), `ayrı` (5,238), `ciddi` (4,937), `tabiî` (4,550), `hazır` (3,081)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[<S> __ A:Adj/Adv/Det/Num]` (2,580), `[A:Adv/Noun __ A:Adj/Adv/Det/Num]` (838), `[<S> __ U:Conj]` (811), `[U:Conj __ A:Adj/Adv/Det/Num]` (807), `[A:Noun/Noun,Prop __ A:Adj/Adv/Det/Num]` (680), `[U:Noun:Loc __ A:Adj/Adv/Det/Num]` (675)

### #11: `Verb->PastPart+Adj+P3sg || Verb->PastPart+Noun+A3sg+Acc || Verb->PastPart+Noun+A3sg+P3sg`

- **Occurrences**: `104,611` (**0.98%** of all ambiguous words) | **Candidates ($K$)**: `3` | **Distinct Items**: `1` | **Distinct `lastGroup`s**: `3`
- **Mechanism**: `M2_DERIVATIONAL_SHIFT` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `750` tracked lemmas ($H_{\text{lemma}} = 5.57$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 4.12$ bits)
- **Sentence Position Distribution**: Initial=`1.2%`, Internal=`86.3%`, Pre-Final=`11.4%`, Sentence-Final=`1.2%`
- **Active Lexicon `RootAttribute`s**: Voicing (6.7%), LastVowelDrop (0.2%), Ext (0.1%)
- **Top Surface Words**: `olduğu` (27,420), `yaptığı` (10,044), `ettiği` (5,016), `verdiği` (4,424), `aldığı` (3,957), `gerektiği` (2,660), `geldiği` (2,014), `istediği` (1,863), `yaşadığı` (1,696), `kaldığı` (1,222), `çıktığı` (1,079), `çalıştığı` (1,073)
- **Top Lemmas / Lemma Pairs**: `olmak` (27,420), `yapmak` (10,044), `etmek` (5,016), `vermek` (4,424), `almak` (3,957), `gerekmek` (2,660), `gelmek` (2,014), `istemek` (1,863), `yaşamak` (1,696), `kalmak` (1,222)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[U:Noun:A3sg __ U:Noun:A3sg]` (914), `[C:Noun __ U:Noun:A3sg]` (851), `[U:Noun:A3sg __ A:Noun/Postp/Postp,PCGen/Verb]` (806), `[C:Noun __ C:Noun]` (779), `[A:Noun/Noun,Prop __ U:Noun:A3sg]` (747), `[U:Noun:A3sg __ C:Noun]` (707)

### #12: `Noun+A3pl || Noun+A3sg->Zero+Verb+Pres+A3pl`

- **Occurrences**: `100,879` (**0.95%** of all ambiguous words) | **Candidates ($K$)**: `2` | **Distinct Items**: `1` | **Distinct `lastGroup`s**: `2`
- **Mechanism**: `M2_DERIVATIONAL_SHIFT` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `2,000` tracked lemmas ($H_{\text{lemma}} = 8.30$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 4.05$ bits)
- **Sentence Position Distribution**: Initial=`10.0%`, Internal=`72.6%`, Pre-Final=`12.1%`, Sentence-Final=`5.3%`
- **Active Lexicon `RootAttribute`s**: Voicing (6.2%), LastVowelDrop (1.6%), Doubling (0.3%), InverseHarmony (1.8%), Ext (0.8%)
- **Top Surface Words**: `arkadaşlar` (5,988), `şeyler` (4,561), `kişiler` (2,218), `bilgiler` (2,068), `vatandaşlar` (1,430), `teşekkürler` (1,418), `öğrenciler` (1,380), `haberler` (1,345), `taraflar` (1,320), `olaylar` (1,234), `ürünler` (1,068), `işlemler` (941)
- **Top Lemmas / Lemma Pairs**: `arkadaş` (5,988), `şey` (4,561), `kişi` (2,218), `bilgi` (2,068), `vatandaş` (1,430), `teşekkür` (1,418), `öğrenci` (1,380), `haber` (1,345), `taraf` (1,320), `olay` (1,234)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[<S> __ A:Noun/Noun,Prop]` (959), `[C:Adj __ </S>]` (726), `[U:Adj:Adj __ A:Adv/Noun]` (594), `[U:Adj:Adj __ U:Noun:A3sg]` (573), `[U:Adj:Adj __ A:Noun/Noun,Prop]` (534), `[A:Adj/Noun __ U:Conj]` (529)

### #13: `Adj || Noun+A3sg || Noun,Prop+A3sg`

- **Occurrences**: `100,283` (**0.94%** of all ambiguous words) | **Candidates ($K$)**: `3` | **Distinct Items**: `3` | **Distinct `lastGroup`s**: `3`
- **Mechanism**: `M4_PROPER_VS_COMMON` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `115` tracked lemmas ($H_{\text{lemma}} = 4.29$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 4.60$ bits)
- **Sentence Position Distribution**: Initial=`9.9%`, Internal=`83.3%`, Pre-Final=`5.3%`, Sentence-Final=`1.5%`
- **Active Lexicon `RootAttribute`s**: Voicing (85.3%), LastVowelDrop (0.2%), InverseHarmony (0.5%)
- **Top Surface Words**: `büyük` (30,980), `küçük` (7,514), `çocuk` (6,586), `teknik` (5,475), `gerçek` (4,970), `genç` (4,773), `erkek` (3,598), `orta` (3,471), `güney` (2,785), `can` (2,432), `uzak` (2,420), `örnek` (2,412)
- **Top Lemmas / Lemma Pairs**: `Büyük/büyük` (30,980), `Küçük/küçük` (7,514), `Çocuk/çocuk` (6,586), `Teknik/teknik` (5,475), `Gerçek/gerçek` (4,970), `Genç/genç` (4,773), `Erkek/erkek` (3,598), `Orta/orta` (3,471), `Güney/güney` (2,785), `Can/can` (2,432)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[A:Noun/Noun,Prop __ U:Noun:A3sg]` (2,181), `[A:Adv/Noun __ U:Noun:A3sg]` (1,298), `[A:Adv/Noun __ C:Noun]` (1,249), `[<S> __ U:Noun:A3sg]` (1,247), `[<S> __ C:Noun]` (1,062), `[A:Adj/Adv/Det/Postp,PCAbl __ A:Adj/Adv/Det/Num]` (790)

### #14: `Noun+A3sg+Gen || Noun+A3sg+P2sg`

- **Occurrences**: `96,940` (**0.91%** of all ambiguous words) | **Candidates ($K$)**: `2` | **Distinct Items**: `1` | **Distinct `lastGroup`s**: `2`
- **Mechanism**: `M1_INFLECTIONAL_SYNCRETISM` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `2,000` tracked lemmas ($H_{\text{lemma}} = 8.45$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 3.98$ bits)
- **Sentence Position Distribution**: Initial=`14.1%`, Internal=`83.4%`, Pre-Final=`2.0%`, Sentence-Final=`0.4%`
- **Active Lexicon `RootAttribute`s**: Voicing (11.8%), LastVowelDrop (5.0%), Doubling (0.2%), CompoundP3sg (0.0%), InverseHarmony (2.3%), Ext (1.1%)
- **Top Surface Words**: `işin` (3,346), `devletin` (2,431), `hükmün` (1,877), `olayın` (1,828), `hükümetin` (1,817), `halkın` (1,664), `şirketin` (1,596), `şeyin` (1,465), `maçın` (1,161), `milletin` (1,155), `şehrin` (1,051), `hayatın` (1,026)
- **Top Lemmas / Lemma Pairs**: `iş` (3,351), `devlet` (2,445), `hüküm` (1,877), `hükümet` (1,869), `olay` (1,829), `halk` (1,665), `şirket` (1,602), `şey` (1,469), `maç` (1,162), `millet` (1,156)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[<S> __ C:Noun]` (1,655), `[<S> __ A:Adj/Noun]` (1,048), `[<S> __ A:Adv/Noun]` (833), `[<S> __ U:Noun:A3sg]` (804), `[A:Adj/Noun __ C:Noun]` (619), `[A:Det/Pron __ C:Noun]` (600)

### #15: `Adv || Noun+A3sg`

- **Occurrences**: `94,382` (**0.88%** of all ambiguous words) | **Candidates ($K$)**: `2` | **Distinct Items**: `2` | **Distinct `lastGroup`s**: `2`
- **Mechanism**: `M3_LEXICAL_POS_POLYSEMY` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `50` tracked lemmas ($H_{\text{lemma}} = 2.35$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 4.15$ bits)
- **Sentence Position Distribution**: Initial=`10.5%`, Internal=`82.3%`, Pre-Final=`5.1%`, Sentence-Final=`2.1%`
- **Active Lexicon `RootAttribute`s**: Voicing (1.2%), InverseHarmony (2.4%), Ext (2.0%)
- **Top Surface Words**: `en` (52,164), `hiç` (16,365), `tekrar` (7,182), `dakika` (4,326), `acaba` (3,086), `usul` (1,725), `garanti` (1,611), `iken` (1,572), `gayet` (1,453), `hariç` (1,138), `tıpkı` (781), `gündüz` (767)
- **Top Lemmas / Lemma Pairs**: `en` (52,166), `hiç` (16,365), `tekrar` (7,182), `dakika` (4,326), `acaba` (3,087), `usul` (1,773), `garanti` (1,611), `iken` (1,572), `gayet` (1,453), `hariç` (1,139)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[<S> __ A:Noun/Noun,Prop]` (935), `[A:Noun/Noun,Prop __ A:Adj/Noun/Noun,Prop]` (848), `[<S> __ A:Adj/Noun/Num]` (667), `[C:Noun __ A:Adj/Adv/Noun]` (634), `[C:Noun __ U:Adj:Adj]` (609), `[<S> __ A:Adj/Adv/Noun]` (606)

### #16: `Adv || Noun,Time+A3sg`

- **Occurrences**: `90,786` (**0.85%** of all ambiguous words) | **Candidates ($K$)**: `2` | **Distinct Items**: `2` | **Distinct `lastGroup`s**: `2`
- **Mechanism**: `M3_LEXICAL_POS_POLYSEMY` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `4` tracked lemmas ($H_{\text{lemma}} = 1.14$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 4.14$ bits)
- **Sentence Position Distribution**: Initial=`19.8%`, Internal=`72.8%`, Pre-Final=`6.6%`, Sentence-Final=`0.9%`
- **Top Surface Words**: `daha` (65,389), `şimdi` (12,961), `bugün` (12,395), `sabahleyin` (41)
- **Top Lemmas / Lemma Pairs**: `daha` (65,389), `şimdi` (12,961), `bugün` (12,395), `sabahleyin` (41)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[<S> __ A:Adv/Noun/Postp,PCAbl]` (1,977), `[<S> __ A:Adj/Adv/Noun/Postp,PCAbl]` (1,889), `[<S> __ U:Noun:A3sg]` (757), `[<S> __ A:Adv/Noun]` (687), `[C:Noun __ U:Conj]` (631), `[A:Adj/Adv/Det/Postp,PCAbl __ A:Adj/Adv/Noun]` (621)

### #17: `STEM(-1)|Noun+A3sg+Dat || STEM(-1)|Verb+Opt+A3sg || STEM(0)|Conj || STEM(0)|Postp,PCNom`

- **Occurrences**: `83,871` (**0.79%** of all ambiguous words) | **Candidates ($K$)**: `4` | **Distinct Items**: `4` | **Distinct `lastGroup`s**: `4`
- **Mechanism**: `M5b_STEM_SUFFIX_COLLISION` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `1` tracked lemmas ($H_{\text{lemma}} = 0.00$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 6.27$ bits)
- **Sentence Position Distribution**: Initial=`0.1%`, Internal=`91.2%`, Pre-Final=`8.0%`, Sentence-Final=`0.6%`
- **Top Surface Words**: `ile` (83,871)
- **Top Lemmas / Lemma Pairs**: `il/ile/ilmek` (83,871)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[C:Noun __ U:Noun:A3sg]` (2,404), `[C:Noun __ C:Adj]` (2,342), `[U:Noun:A3sg __ C:Adj]` (1,633), `[U:Noun:A3sg __ U:Noun:A3sg]` (1,539), `[C:Noun __ C:Noun]` (1,284), `[U:Noun,Prop:A3sg __ U:Noun,Prop:A3sg]` (1,198)

### #18: `Adj || Adv || Det || Postp,PCAbl`

- **Occurrences**: `79,407` (**0.74%** of all ambiguous words) | **Candidates ($K$)**: `4` | **Distinct Items**: `4` | **Distinct `lastGroup`s**: `4`
- **Mechanism**: `M3_LEXICAL_POS_POLYSEMY` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `1` tracked lemmas ($H_{\text{lemma}} = 0.00$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 4.25$ bits)
- **Sentence Position Distribution**: Initial=`6.8%`, Internal=`80.3%`, Pre-Final=`12.6%`, Sentence-Final=`0.3%`
- **Active Lexicon `RootAttribute`s**: Voicing (100.0%)
- **Top Surface Words**: `çok` (79,407)
- **Top Lemmas / Lemma Pairs**: `çok` (79,407)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[A:Adv/Noun __ U:Noun:A3sg]` (1,703), `[A:Adv/Noun __ U:Adj:Adj]` (821), `[<S> __ A:Adj/Adv/Noun]` (774), `[A:Adv/Noun __ A:Adj/Noun]` (736), `[C:Noun __ A:Adj/Adv/Noun]` (686), `[U:Noun:A3sg __ A:Adj/Adv/Noun]` (626)

### #19: `Adj || Noun+A3sg || Verb+Imp+A2sg`

- **Occurrences**: `77,268` (**0.72%** of all ambiguous words) | **Candidates ($K$)**: `3` | **Distinct Items**: `3` | **Distinct `lastGroup`s**: `3`
- **Mechanism**: `M6a_HOMONYM_DIFF_POS` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `26` tracked lemmas ($H_{\text{lemma}} = 2.35$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 4.62$ bits)
- **Sentence Position Distribution**: Initial=`5.5%`, Internal=`52.6%`, Pre-Final=`11.9%`, Sentence-Final=`30.0%`
- **Active Lexicon `RootAttribute`s**: Voicing (8.8%)
- **Top Surface Words**: `var` (44,869), `eski` (7,879), `gerek` (6,285), `iç` (4,927), `ek` (3,299), `sağ` (2,139), `sol` (1,765), `eş` (1,224), `taş` (1,218), `bay` (1,140), `düz` (536), `toz` (397)
- **Top Lemmas / Lemma Pairs**: `var/varmak` (44,870), `eski/eskimek` (7,879), `gerek/gerekmek` (6,285), `iç/içmek` (4,929), `ek/ekmek` (3,322), `sağ/sağmak` (2,139), `sol/solmak` (1,765), `taş/taşmak` (1,226), `eş/eşmek` (1,224), `bay/baymak` (1,140)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[C:Noun __ </S>]` (4,988), `[A:Noun/Verb __ </S>]` (4,136), `[U:Noun:A3sg __ </S>]` (3,606), `[U:Noun:P1pl __ </S>]` (1,500), `[U:Noun:P3sg __ </S>]` (1,322), `[A:Noun/Noun,Prop __ </S>]` (1,169)

### #20: `Verb+Neg+Imp+A2sg || Verb->Inf2+Noun+A3sg`

- **Occurrences**: `66,801` (**0.63%** of all ambiguous words) | **Candidates ($K$)**: `2` | **Distinct Items**: `1` | **Distinct `lastGroup`s**: `2`
- **Mechanism**: `M2_DERIVATIONAL_SHIFT` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `875` tracked lemmas ($H_{\text{lemma}} = 7.14$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 4.79$ bits)
- **Sentence Position Distribution**: Initial=`5.4%`, Internal=`82.6%`, Pre-Final=`8.8%`, Sentence-Final=`3.2%`
- **Active Lexicon `RootAttribute`s**: Voicing (4.2%), LastVowelDrop (0.3%), Ext (0.1%)
- **Top Surface Words**: `çalışma` (6,431), `uygulama` (2,475), `düzenleme` (2,199), `olma` (2,195), `etme` (2,021), `konuşma` (1,684), `alma` (1,625), `yapma` (1,543), `yargılama` (1,491), `gelişme` (1,251), `verme` (1,221), `bozma` (1,075)
- **Top Lemmas / Lemma Pairs**: `çalışmak` (6,431), `uygulamak` (2,475), `düzenlemek` (2,199), `olmak` (2,195), `etmek` (2,021), `konuşmak` (1,684), `almak` (1,625), `yapmak` (1,543), `yargılamak` (1,492), `gelişmek` (1,251)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[U:Noun:A3sg __ C:Noun]` (1,812), `[C:Noun __ C:Noun]` (1,066), `[U:Noun:A3sg __ A:Noun/Noun,Prop]` (824), `[<S> __ C:Noun]` (725), `[U:Conj __ C:Noun]` (716), `[A:Noun/Noun,Prop __ C:Noun]` (672)

### #21: `Noun+A3sg+P2sg+Acc || Noun+A3sg+P3sg+Acc`

- **Occurrences**: `66,291` (**0.62%** of all ambiguous words) | **Candidates ($K$)**: `2` | **Distinct Items**: `1` | **Distinct `lastGroup`s**: `2`
- **Mechanism**: `M1_INFLECTIONAL_SYNCRETISM` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `2,000` tracked lemmas ($H_{\text{lemma}} = 8.59$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 4.56$ bits)
- **Sentence Position Distribution**: Initial=`2.4%`, Internal=`76.9%`, Pre-Final=`20.1%`, Sentence-Final=`0.6%`
- **Active Lexicon `RootAttribute`s**: Voicing (14.4%), LastVowelDrop (5.7%), Doubling (0.5%), InverseHarmony (3.1%), Ext (0.4%)
- **Top Surface Words**: `hayatını` (4,011), `yerini` (1,677), `adını` (1,572), `kararını` (1,133), `görevini` (1,126), `kısmını` (891), `işini` (883), `yolunu` (852), `sistemini` (692), `elini` (684), `ismini` (676), `gücünü` (673)
- **Top Lemmas / Lemma Pairs**: `hayat` (4,011), `yer` (1,677), `ad` (1,572), `karar` (1,133), `görev` (1,126), `kısım` (891), `iş` (883), `yol` (852), `sistem` (692), `el` (684)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[U:Noun:A3sg __ U:Noun:A3sg]` (1,108), `[U:Noun:A3sg __ U:Verb:A3sg]` (801), `[C:Noun __ U:Noun:A3sg]` (675), `[U:Noun:A3sg __ A:Adj/Verb]` (635), `[U:Noun:A3sg __ U:Adj:Adj]` (578), `[U:Noun:A3sg __ A:Noun/Verb]` (497)

### #22: `Noun+A3pl+Gen || Noun+A3pl+P2sg`

- **Occurrences**: `62,622` (**0.59%** of all ambiguous words) | **Candidates ($K$)**: `2` | **Distinct Items**: `1` | **Distinct `lastGroup`s**: `2`
- **Mechanism**: `M1_INFLECTIONAL_SYNCRETISM` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `2,000` tracked lemmas ($H_{\text{lemma}} = 8.78$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 4.71$ bits)
- **Sentence Position Distribution**: Initial=`8.3%`, Internal=`89.8%`, Pre-Final=`1.5%`, Sentence-Final=`0.3%`
- **Active Lexicon `RootAttribute`s**: Voicing (5.9%), LastVowelDrop (1.5%), Doubling (0.2%), InverseHarmony (1.2%), Ext (0.4%)
- **Top Surface Words**: `kişilerin` (2,102), `vatandaşların` (1,238), `öğrencilerin` (1,126), `olayların` (785), `tarafların` (737), `ürünlerin` (713), `şirketlerin` (703), `sorunların` (661), `işlemlerin` (497), `ilişkilerin` (483), `firmaların` (471), `kurumların` (454)
- **Top Lemmas / Lemma Pairs**: `kişi` (2,102), `vatandaş` (1,238), `öğrenci` (1,126), `olay` (785), `taraf` (737), `ürün` (713), `şirket` (703), `sorun` (661), `işlem` (497), `ilişki` (483)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[U:Adj:Adj __ C:Noun]` (723), `[A:Adj/Noun __ C:Noun]` (669), `[<S> __ C:Noun]` (615), `[A:Adj/Verb __ C:Noun]` (501), `[A:Det/Pron __ C:Noun]` (482), `[<S> __ A:Adj/Noun]` (443)

### #23: `Noun+A3sg+P2sg+Dat || Noun+A3sg+P3sg+Dat`

- **Occurrences**: `60,992` (**0.57%** of all ambiguous words) | **Candidates ($K$)**: `2` | **Distinct Items**: `1` | **Distinct `lastGroup`s**: `2`
- **Mechanism**: `M1_INFLECTIONAL_SYNCRETISM` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `1,920` tracked lemmas ($H_{\text{lemma}} = 8.42$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 4.95$ bits)
- **Sentence Position Distribution**: Initial=`1.5%`, Internal=`82.1%`, Pre-Final=`15.6%`, Sentence-Final=`0.8%`
- **Active Lexicon `RootAttribute`s**: Voicing (12.1%), LastVowelDrop (5.3%), Doubling (0.4%), InverseHarmony (4.1%), Ext (0.3%)
- **Top Surface Words**: `başına` (4,375), `anlamına` (1,735), `yanına` (1,323), `eline` (1,146), `aleyhine` (926), `kararına` (885), `kabulüne` (884), `haberine` (852), `işine` (697), `durumuna` (677), `sonucuna` (672), `sistemine` (653)
- **Top Lemmas / Lemma Pairs**: `baş` (4,375), `anlam` (1,735), `yan` (1,323), `el` (1,146), `aleyh` (926), `karar` (885), `kabul` (884), `haber` (852), `iş` (697), `durum` (677)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[U:Noun:A3sg __ U:Noun:A3sg]` (1,495), `[U:Noun:A3sg __ A:Adj/Verb]` (723), `[A:Noun/Verb __ U:Noun:A3sg]` (643), `[U:Noun:A3sg __ A:Noun/Verb]` (635), `[C:Noun __ U:Noun:A3sg]` (620), `[U:Noun:A3sg __ U:Adj:Adj]` (592)

### #24: `Noun+A3sg+P2sg+Loc || Noun+A3sg+P3sg+Loc`

- **Occurrences**: `60,305` (**0.56%** of all ambiguous words) | **Candidates ($K$)**: `2` | **Distinct Items**: `1` | **Distinct `lastGroup`s**: `2`
- **Mechanism**: `M1_INFLECTIONAL_SYNCRETISM` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `1,426` tracked lemmas ($H_{\text{lemma}} = 7.64$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 5.11$ bits)
- **Sentence Position Distribution**: Initial=`1.2%`, Internal=`87.8%`, Pre-Final=`8.4%`, Sentence-Final=`2.6%`
- **Active Lexicon `RootAttribute`s**: Voicing (14.2%), LastVowelDrop (4.2%), Doubling (0.4%), InverseHarmony (1.7%), Ext (0.3%)
- **Top Surface Words**: `başında` (3,202), `durumunda` (2,952), `sonucunda` (2,321), `oranında` (2,257), `yönünde` (1,763), `maçında` (1,722), `elinde` (1,536), `yolunda` (1,296), `bölümünde` (1,070), `sektöründe` (940), `farkında` (818), `tarafında` (743)
- **Top Lemmas / Lemma Pairs**: `baş` (3,202), `durum` (2,952), `sonuç` (2,321), `oran` (2,257), `yön` (1,763), `maç` (1,722), `el` (1,536), `yol` (1,296), `bölüm` (1,070), `sektör` (940)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[U:Noun:A3sg __ U:Noun:A3sg]` (1,194), `[U:Noun:A3sg __ A:Adj/Noun]` (777), `[C:Noun __ U:Noun:A3sg]` (674), `[U:Noun:A3sg __ A:Noun/Verb]` (662), `[U:Noun:A3sg __ A:Adj/Verb]` (638), `[U:Noun:A3sg __ C:Noun]` (582)

### #25: `Verb->PastPart+Noun+A3sg+P2sg+Acc || Verb->PastPart+Noun+A3sg+P3sg+Acc`

- **Occurrences**: `60,183` (**0.56%** of all ambiguous words) | **Candidates ($K$)**: `2` | **Distinct Items**: `1` | **Distinct `lastGroup`s**: `2`
- **Mechanism**: `M1_INFLECTIONAL_SYNCRETISM` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `636` tracked lemmas ($H_{\text{lemma}} = 4.47$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 4.98$ bits)
- **Sentence Position Distribution**: Initial=`0.2%`, Internal=`62.4%`, Pre-Final=`36.8%`, Sentence-Final=`0.5%`
- **Active Lexicon `RootAttribute`s**: Voicing (5.8%), LastVowelDrop (0.2%), Ext (0.1%)
- **Top Surface Words**: `olduğunu` (27,447), `gerektiğini` (4,406), `ettiğini` (2,436), `geldiğini` (1,572), `yaptığını` (1,461), `istediğini` (1,319), `aldığını` (1,048), `verdiğini` (904), `başladığını` (705), `çıktığını` (590), `kaldığını` (548), `geçtiğini` (525)
- **Top Lemmas / Lemma Pairs**: `olmak` (27,447), `gerekmek` (4,406), `etmek` (2,436), `gelmek` (1,572), `yapmak` (1,461), `istemek` (1,319), `almak` (1,048), `vermek` (904), `başlamak` (705), `çıkmak` (590)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[U:Noun:A3sg __ U:Verb:A3sg]` (1,607), `[C:Noun __ U:Verb:A3sg]` (1,001), `[U:Noun:P3sg __ U:Verb:A3sg]` (789), `[U:Noun:A3sg __ U:Noun:A3sg]` (780), `[A:Noun/Verb __ U:Verb:A3sg]` (740), `[U:Noun:A3sg __ C:Verb:A3sg]` (680)

### #26: `Noun,Prop+A3sg+Gen || Noun,Prop+A3sg+P2sg`

- **Occurrences**: `53,302` (**0.50%** of all ambiguous words) | **Candidates ($K$)**: `2` | **Distinct Items**: `1` | **Distinct `lastGroup`s**: `2`
- **Mechanism**: `M1_INFLECTIONAL_SYNCRETISM` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `2,000` tracked lemmas ($H_{\text{lemma}} = 6.11$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 5.02$ bits)
- **Sentence Position Distribution**: Initial=`21.5%`, Internal=`76.5%`, Pre-Final=`1.4%`, Sentence-Final=`0.5%`
- **Active Lexicon `RootAttribute`s**: InverseHarmony (0.2%), Runtime (13.5%)
- **Top Surface Words**: `erdoğan'ın` (1,542), `israil'in` (1,220), `istanbul'un` (986), `beşiktaş'ın` (786), `atatürk'ün` (739), `iran'ın` (735), `galatasaray'ın` (558), `ırak'ın` (508), `çin'in` (478), `yunanistan'ın` (450), `allah'ın` (367), `izmir'in` (356)
- **Top Lemmas / Lemma Pairs**: `Erdoğan` (1,545), `İsrail` (1,229), `İstanbul` (1,019), `Beşiktaş` (832), `Atatürk` (751), `İran` (749), `Galatasaray` (576), `Irak` (508), `Allah` (503), `Çin` (478)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[U:Noun,Prop:A3sg __ C:Noun]` (1,243), `[<S> __ U:Noun,Prop:A3sg]` (1,197), `[<S> __ C:Noun]` (1,119), `[U:Noun,Prop:A3sg __ A:Adj/Noun]` (938), `[<S> __ A:Adj/Noun]` (790), `[<S> __ A:Noun/Noun,Prop]` (759)

### #27: `Adj || Det`

- **Occurrences**: `52,766` (**0.49%** of all ambiguous words) | **Candidates ($K$)**: `2` | **Distinct Items**: `2` | **Distinct `lastGroup`s**: `2`
- **Mechanism**: `M3_LEXICAL_POS_POLYSEMY` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `4` tracked lemmas ($H_{\text{lemma}} = 1.08$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 5.07$ bits)
- **Sentence Position Distribution**: Initial=`18.5%`, Internal=`80.3%`, Pre-Final=`1.1%`, Sentence-Final=`0.0%`
- **Active Lexicon `RootAttribute`s**: Voicing (21.5%)
- **Top Surface Words**: `her` (40,499), `birçok` (6,630), `birkaç` (4,731), `birtakım` (906)
- **Top Lemmas / Lemma Pairs**: `her` (40,499), `birçok` (6,630), `birkaç` (4,731), `birtakım` (906)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[<S> __ U:Noun:A3sg]` (1,183), `[<S> __ C:Noun]` (1,137), `[<S> __ U:Noun,Time:A3sg]` (1,067), `[<S> __ A:Noun/Verb]` (704), `[<S> __ U:Num]` (675), `[<S> __ U:Noun:Loc]` (584)

### #28: `Noun+A3sg+Gen || Noun+A3sg+P2sg+Gen`

- **Occurrences**: `52,347` (**0.49%** of all ambiguous words) | **Candidates ($K$)**: `2` | **Distinct Items**: `1` | **Distinct `lastGroup`s**: `2`
- **Mechanism**: `M1_INFLECTIONAL_SYNCRETISM` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `1,488` tracked lemmas ($H_{\text{lemma}} = 7.37$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 4.38$ bits)
- **Sentence Position Distribution**: Initial=`12.9%`, Internal=`85.5%`, Pre-Final=`1.4%`, Sentence-Final=`0.3%`
- **Active Lexicon `RootAttribute`s**: Ext (1.0%)
- **Top Surface Words**: `kişinin` (5,951), `davanın` (4,171), `maddenin` (1,609), `bölgenin` (1,151), `konunun` (1,128), `projenin` (1,124), `mahkemenin` (997), `paranın` (894), `firmanın` (852), `ailenin` (822), `binanın` (747), `partinin` (717)
- **Top Lemmas / Lemma Pairs**: `kişi` (5,952), `dava` (4,181), `madde` (1,615), `anayasa` (1,352), `parti` (1,247), `bölge` (1,158), `proje` (1,129), `konu` (1,128), `mahkeme` (1,039), `para` (895)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[<S> __ C:Noun]` (707), `[U:Num __ A:Adj/Noun]` (572), `[<S> __ A:Adj/Noun]` (461), `[<S> __ U:Num]` (449), `[U:Num __ C:Noun:Acc]` (412), `[<S> __ U:Noun:A3sg]` (404)

### #29: `Noun+A3sg+P2sg+Gen || Noun+A3sg+P3sg+Gen`

- **Occurrences**: `49,691` (**0.47%** of all ambiguous words) | **Candidates ($K$)**: `2` | **Distinct Items**: `1` | **Distinct `lastGroup`s**: `2`
- **Mechanism**: `M1_INFLECTIONAL_SYNCRETISM` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `1,846` tracked lemmas ($H_{\text{lemma}} = 8.60$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 5.43$ bits)
- **Sentence Position Distribution**: Initial=`1.6%`, Internal=`96.4%`, Pre-Final=`1.7%`, Sentence-Final=`0.3%`
- **Active Lexicon `RootAttribute`s**: Voicing (12.7%), LastVowelDrop (6.6%), Doubling (0.6%), InverseHarmony (1.7%), Ext (0.3%)
- **Top Surface Words**: `kararının` (2,286), `sisteminin` (878), `halkının` (840), `sektörünün` (756), `oranının` (731), `kısmının` (633), `durumunun` (621), `yönetiminin` (608), `örgütünün` (548), `hayatının` (538), `başkanının` (509), `hükümetinin` (497)
- **Top Lemmas / Lemma Pairs**: `karar` (2,286), `sistem` (878), `halk` (841), `sektör` (756), `oran` (731), `kısım` (633), `durum` (621), `yönetim` (609), `örgüt` (548), `hayat` (538)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[U:Noun:A3sg __ C:Noun]` (1,385), `[U:Noun:A3sg __ A:Adj/Noun]` (1,032), `[U:Noun:A3sg __ A:Adv/Noun]` (924), `[U:Noun:A3sg __ U:Noun:A3sg]` (711), `[U:Noun:A3sg __ A:Noun/Noun,Prop]` (707), `[U:Noun:A3sg __ U:Noun:P3sg]` (625)

### #30: `Noun+A3sg || Verb+Imp+A2sg`

- **Occurrences**: `48,463` (**0.45%** of all ambiguous words) | **Candidates ($K$)**: `2` | **Distinct Items**: `2` | **Distinct `lastGroup`s**: `2`
- **Mechanism**: `M6a_HOMONYM_DIFF_POS` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `85` tracked lemmas ($H_{\text{lemma}} = 4.49$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 4.34$ bits)
- **Sentence Position Distribution**: Initial=`7.9%`, Internal=`76.7%`, Pre-Final=`12.7%`, Sentence-Final=`2.7%`
- **Active Lexicon `RootAttribute`s**: Voicing (7.0%), LastVowelDrop (1.3%), Doubling (0.0%), Ext (0.1%)
- **Top Surface Words**: `yol` (7,882), `il` (5,821), `soru` (5,588), `et` (3,259), `kan` (2,799), `barış` (2,248), `tepki` (2,120), `dil` (1,702), `gül` (1,644), `saç` (1,226), `din` (1,160), `sürü` (1,050)
- **Top Lemmas / Lemma Pairs**: `yol/yolmak` (7,882), `il/ilmek` (5,821), `soru/sorumak` (5,588), `et/etmek` (3,277), `kan/kanmak` (2,799), `barış/barışmak` (2,248), `tepki/tepkimek` (2,120), `dil/dilmek` (1,702), `gül/gülmek` (1,644), `saç/saçmak` (1,226)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[A:Adj/Noun __ U:Noun:P3sg]` (1,457), `[<S> __ C:Noun]` (503), `[U:Noun,Prop:A3sg __ C:Noun]` (485), `[U:Noun:A3sg __ C:Noun]` (466), `[A:Adj/Noun __ C:Noun]` (425), `[A:Adj/Adv/Det/Num __ U:Noun:A3sg]` (392)

### #31: `STEM(-1)|Adj->Acquire+Verb+Imp+A2sg || STEM(0)|Verb->PresPart+Adj`

- **Occurrences**: `48,393` (**0.45%** of all ambiguous words) | **Candidates ($K$)**: `2` | **Distinct Items**: `2` | **Distinct `lastGroup`s**: `2`
- **Mechanism**: `M5b_STEM_SUFFIX_COLLISION` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `2` tracked lemmas ($H_{\text{lemma}} = 0.00$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 5.34$ bits)
- **Sentence Position Distribution**: Initial=`0.2%`, Internal=`96.4%`, Pre-Final=`2.8%`, Sentence-Final=`0.6%`
- **Top Surface Words**: `olan` (48,390), `yücelen` (3)
- **Top Lemmas / Lemma Pairs**: `o/olmak` (48,390), `yüce/yücelmek` (3)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[A:Adj/Verb __ U:Noun:A3sg]` (809), `[C:Noun __ U:Noun,Prop:A3sg]` (659), `[C:Noun __ U:Noun:A3sg]` (652), `[C:Noun __ C:Noun]` (580), `[U:Noun:A3sg __ U:Noun:A3sg]` (556), `[A:Adj/Verb __ C:Noun]` (530)

### #32: `STEM(-1)|Noun+A3sg+Dat || STEM(0)|Adj || STEM(0)|Conj`

- **Occurrences**: `48,247` (**0.45%** of all ambiguous words) | **Candidates ($K$)**: `3` | **Distinct Items**: `3` | **Distinct `lastGroup`s**: `3`
- **Mechanism**: `M5b_STEM_SUFFIX_COLLISION` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `1` tracked lemmas ($H_{\text{lemma}} = 0.00$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 4.75$ bits)
- **Sentence Position Distribution**: Initial=`30.0%`, Internal=`66.2%`, Pre-Final=`2.3%`, Sentence-Final=`1.5%`
- **Top Surface Words**: `ama` (48,242), `âmâ` (5)
- **Top Lemmas / Lemma Pairs**: `am/ama/âmâ` (48,247)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[<S> __ A:Noun/Pron]` (1,321), `[<S> __ A:Det/Pron]` (1,020), `[<S> __ A:Adv/Noun]` (868), `[<S> __ U:Noun:A3sg]` (852), `[<S> __ C:Noun]` (584), `[<S> __ A:Adj/Noun]` (530)

### #33: `Noun+A3sg || Noun,Prop+A3sg || Postp,PCDat || Postp,PCGen || Postp,PCNom`

- **Occurrences**: `47,186` (**0.44%** of all ambiguous words) | **Candidates ($K$)**: `5` | **Distinct Items**: `5` | **Distinct `lastGroup`s**: `5`
- **Mechanism**: `M4_PROPER_VS_COMMON` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `1` tracked lemmas ($H_{\text{lemma}} = 0.00$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 5.74$ bits)
- **Sentence Position Distribution**: Initial=`0.0%`, Internal=`87.0%`, Pre-Final=`10.7%`, Sentence-Final=`2.3%`
- **Top Surface Words**: `kadar` (47,186)
- **Top Lemmas / Lemma Pairs**: `Kadar/kadar` (47,186)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[A:Adj/Adv/Conj/Interj/Noun/Pron __ U:Noun:A3sg]` (664), `[U:Noun:Dat __ U:Noun:A3sg]` (484), `[U:Noun,Time:Dat __ A:Adj/Noun]` (483), `[C:Noun:Dat __ U:Noun:A3sg]` (477), `[A:Adj/Adv/Conj/Interj/Noun/Pron __ U:Adj:Adj]` (469), `[U:Noun:Dat __ A:Adj/Verb]` (468)

### #34: `STEM(-1)|Noun,Abbrv+A3sg+Acc || STEM(0)|Postp,PCGen || STEM(0)|Postp,PCNom`

- **Occurrences**: `45,333` (**0.42%** of all ambiguous words) | **Candidates ($K$)**: `3` | **Distinct Items**: `3` | **Distinct `lastGroup`s**: `3`
- **Mechanism**: `M4_PROPER_VS_COMMON` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `1` tracked lemmas ($H_{\text{lemma}} = 0.00$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 5.56$ bits)
- **Sentence Position Distribution**: Initial=`0.4%`, Internal=`82.6%`, Pre-Final=`10.8%`, Sentence-Final=`6.2%`
- **Top Surface Words**: `gibi` (45,333)
- **Top Lemmas / Lemma Pairs**: `Gib/gibi` (45,333)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[A:Adj/Noun __ U:Noun:A3sg]` (1,051), `[U:Noun:A3sg __ A:Adj/Adv/Det/Num]` (707), `[A:Adj/Noun __ C:Noun]` (671), `[A:Adj/Verb __ U:Verb:A3sg]` (579), `[A:Adj/Noun __ A:Noun/Verb]` (559), `[A:Adj/Noun __ </S>]` (509)

### #35: `Adj || Det || Interj || Pron,Demons+A3sg || Pron,Pers+A3sg`

- **Occurrences**: `43,822` (**0.41%** of all ambiguous words) | **Candidates ($K$)**: `5` | **Distinct Items**: `5` | **Distinct `lastGroup`s**: `5`
- **Mechanism**: `M3_LEXICAL_POS_POLYSEMY` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `1` tracked lemmas ($H_{\text{lemma}} = 0.00$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 4.67$ bits)
- **Sentence Position Distribution**: Initial=`29.4%`, Internal=`65.0%`, Pre-Final=`4.9%`, Sentence-Final=`0.7%`
- **Top Surface Words**: `o` (43,760), `o.` (62)
- **Top Lemmas / Lemma Pairs**: `o` (43,822)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[<S> __ C:Noun]` (1,926), `[<S> __ U:Noun,Time:A3sg]` (1,896), `[<S> __ C:Noun:Loc]` (931), `[<S> __ A:Noun/Noun,Prop]` (659), `[<S> __ U:Noun:Loc]` (645), `[<S> __ U:Conj]` (624)

### #36: `Verb+Fut+A3sg || Verb->FutPart+Adj`

- **Occurrences**: `43,602` (**0.41%** of all ambiguous words) | **Candidates ($K$)**: `2` | **Distinct Items**: `1` | **Distinct `lastGroup`s**: `2`
- **Mechanism**: `M2_DERIVATIONAL_SHIFT` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `697` tracked lemmas ($H_{\text{lemma}} = 5.77$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 5.01$ bits)
- **Sentence Position Distribution**: Initial=`0.7%`, Internal=`47.8%`, Pre-Final=`5.7%`, Sentence-Final=`45.9%`
- **Active Lexicon `RootAttribute`s**: Voicing (11.2%), LastVowelDrop (0.3%), Ext (0.1%)
- **Top Surface Words**: `olacak` (11,743), `edecek` (3,561), `yapacak` (2,386), `başlayacak` (1,286), `sağlayacak` (1,236), `gidecek` (974), `geçecek` (839), `girecek` (837), `kalacak` (815), `sürecek` (609), `getirecek` (460), `çalışacak` (400)
- **Top Lemmas / Lemma Pairs**: `olmak` (11,743), `etmek` (3,561), `yapmak` (2,386), `başlamak` (1,286), `sağlamak` (1,236), `gitmek` (974), `geçmek` (839), `girmek` (837), `kalmak` (815), `sürmek` (609)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[U:Noun:A3sg __ </S>]` (2,411), `[C:Noun __ </S>]` (1,670), `[A:Noun/Noun,Prop __ </S>]` (1,349), `[A:Noun/Verb __ </S>]` (1,299), `[U:Noun:Dat __ </S>]` (817), `[A:Interj/Noun/Noun,Prop __ </S>]` (744)

### #37: `Adj || Adv || Conj || Interj || Noun+A3sg || Noun,Abbrv+A3sg || Pron,Ques+A3sg`

- **Occurrences**: `43,120` (**0.40%** of all ambiguous words) | **Candidates ($K$)**: `7` | **Distinct Items**: `7` | **Distinct `lastGroup`s**: `7`
- **Mechanism**: `M4_PROPER_VS_COMMON` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `1` tracked lemmas ($H_{\text{lemma}} = 0.00$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 4.35$ bits)
- **Sentence Position Distribution**: Initial=`12.6%`, Internal=`72.9%`, Pre-Final=`13.2%`, Sentence-Final=`1.3%`
- **Top Surface Words**: `ne` (43,119), `n.e.` (1)
- **Top Lemmas / Lemma Pairs**: `Ne/ne` (43,120)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[<S> __ A:Noun/Noun,Prop/Postp/Postp,PCDat/Postp,PCGen]` (738), `[A:Adj/Det __ A:Noun/Noun,Prop/Postp/Postp,PCDat/Postp,PCGen]` (722), `[C:Noun __ A:Noun/Noun,Prop/Postp/Postp,PCDat/Postp,PCGen]` (695), `[<S> __ A:Adj/Noun/Verb]` (473), `[C:Noun __ U:Noun,Time:A3sg]` (405), `[<S> __ A:Adv/Interj/Noun]` (357)

### #38: `Adj->Zero+Noun+A3sg+Acc || Adj->Zero+Noun+A3sg+P3sg || Noun+A3sg+Acc || Noun+A3sg+P3sg`

- **Occurrences**: `41,951` (**0.39%** of all ambiguous words) | **Candidates ($K$)**: `4` | **Distinct Items**: `2` | **Distinct `lastGroup`s**: `4`
- **Mechanism**: `M7_HYBRID_MULTI_LOCUS` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `346` tracked lemmas ($H_{\text{lemma}} = 5.92$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 4.68$ bits)
- **Sentence Position Distribution**: Initial=`2.5%`, Internal=`82.6%`, Pre-Final=`10.8%`, Sentence-Final=`4.1%`
- **Active Lexicon `RootAttribute`s**: Voicing (33.5%), Doubling (0.0%), Ext (0.1%)
- **Top Surface Words**: `gereği` (4,201), `dışı` (3,463), `kartı` (2,608), `içi` (1,954), `eşi` (1,802), `çocuğu` (1,563), `lideri` (1,554), `bedeli` (1,433), `topu` (1,272), `memuru` (1,004), `gerçeği` (802), `örneği` (802)
- **Top Lemmas / Lemma Pairs**: `gerek` (4,201), `dış` (3,463), `kart` (2,608), `iç` (1,954), `eş` (1,802), `çocuk` (1,563), `lider` (1,554), `bedel` (1,433), `top` (1,272), `memur` (1,004)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[U:Noun:A3sg __ U:Noun:A3sg]` (913), `[U:Noun:A3sg __ C:Noun]` (837), `[U:Noun:A3sg __ U:Noun,Prop:A3sg]` (436), `[U:Noun:A3sg __ A:Noun/Noun,Prop]` (418), `[U:Noun:A3sg __ A:Noun/Verb]` (405), `[U:Noun:A3sg __ A:Adj/Noun]` (349)

### #39: `Num,Dist || Num,Ord`

- **Occurrences**: `41,505` (**0.39%** of all ambiguous words) | **Candidates ($K$)**: `2` | **Distinct Items**: `2` | **Distinct `lastGroup`s**: `2`
- **Mechanism**: `M3_LEXICAL_POS_POLYSEMY` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `819` tracked lemmas ($H_{\text{lemma}} = 5.92$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 5.36$ bits)
- **Sentence Position Distribution**: Initial=`35.4%`, Internal=`59.4%`, Pre-Final=`2.6%`, Sentence-Final=`2.6%`
- **Active Lexicon `RootAttribute`s**: Runtime (100.0%)
- **Top Surface Words**: `2.` (4,867), `3.` (3,788), `1.` (3,742), `4.` (2,453), `5.` (1,719), `6.` (1,569), `7.` (1,257), `8.` (1,170), `10.` (1,071), `9.` (974), `11.` (890), `13.` (821)
- **Top Lemmas / Lemma Pairs**: `2.` (4,867), `3.` (3,788), `1.` (3,742), `4.` (2,453), `5.` (1,719), `6.` (1,569), `7.` (1,257), `8.` (1,170), `10.` (1,071), `9.` (974)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[<S> __ U:Noun:Loc]` (1,868), `[<S> __ U:Noun:A3sg]` (1,730), `[<S> __ A:Noun/Noun,Prop]` (997), `[<S> __ U:Noun,Prop:A3sg]` (941), `[<S> __ C:Noun]` (934), `[U:Noun,Prop:A3sg __ U:Noun:A3sg]` (700)

### #40: `Adv || Noun,Time+A3sg || Postp,PCAbl`

- **Occurrences**: `41,424` (**0.39%** of all ambiguous words) | **Candidates ($K$)**: `3` | **Distinct Items**: `3` | **Distinct `lastGroup`s**: `3`
- **Mechanism**: `M3_LEXICAL_POS_POLYSEMY` | **Decoder Observability**: `FULL_MORPHO_OBSERVABLE`
- **Lexical Diversity**: `1` tracked lemmas ($H_{\text{lemma}} = 0.00$ bits) | **Context Diversity**: `500` tracked frames ($H_{\text{context}} = 6.02$ bits)
- **Sentence Position Distribution**: Initial=`5.1%`, Internal=`90.1%`, Pre-Final=`3.7%`, Sentence-Final=`1.2%`
- **Top Surface Words**: `sonra` (41,424)
- **Top Lemmas / Lemma Pairs**: `sonra` (41,424)
- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: `[U:Noun:Abl __ U:Noun:A3sg]` (1,214), `[U:Noun:Abl __ C:Noun]` (969), `[A:Adv/Noun __ U:Noun:A3sg]` (650), `[U:Noun:Abl __ A:Noun/Verb]` (592), `[U:Noun:Abl __ A:Noun/Noun,Prop]` (575), `[C:Noun:Abl __ U:Noun:A3sg]` (551)

## 8. Diagnostic Audit: Top 25 `LEXICAL_ONLY_OBSERVABLE` Signatures (`F15`/`F17` Blind Twins & Collisions)

These ambiguity signatures have `U_lastIG = 1` (all candidates end in the exact same `lastGroup`), meaning `FastPerceptronAmbiguityResolver`'s unlexicalized transition features (`F15`, `F17`) have **zero discriminative power** on them:

| Rank | Occurrences | Mechanism | Signature | Top Lemma Collisions | Top Surface Words |
| ---: | ---: | :--- | :--- | :--- | :--- |
| 1 | 17,996 | `M5a_VOICE_LEXICALIZED` | `STEM(-2)\|Verb->Pass+Verb->PresPart+Adj \|\| STEM(0)\|Verb->PresPart+Adj` | bulmak/bulunmak (10652), almak/alınmak (5682), atmak/atılmak (828), yaymak/yayılmak (250), bozmak/bozulmak (146) | bulunan (10652), alınan (5682), atılan (828), yayılan (250), bozulan (146) |
| 2 | 13,157 | `M3_LEXICAL_POS_POLYSEMY` | `Adj->Ness+Noun+A3sg \|\| Noun+A3sg->Ness+Noun+A3sg` | değişik (3141), asker (1391), üye (885), insan (771), eş (638) | değişiklik (3141), askerlik (1391), üyelik (885), insanlık (771), eşlik (638) |
| 3 | 10,926 | `M3_LEXICAL_POS_POLYSEMY` | `Adj->Zero+Noun+A3sg->With+Adj \|\| Noun+A3sg->With+Adj` | hayır (1474), yaş (1216), kalite (1207), zor (727), yarar (538) | hayırlı (1474), yaşlı (1216), kaliteli (1207), zorlu (727), yararlı (538) |
| 4 | 10,699 | `M4_PROPER_VS_COMMON` | `Noun+A3sg->With+Adj \|\| Noun,Prop+A3sg->With+Adj` | Bahçe/bahçe (808), Verim/verim (791), Kuvvet/kuvvet (734), Kapsam/kapsam (718), Kaynak/kaynak (698) | bahçeli (808), verimli (791), kuvvetli (734), kapsamlı (718), kaynaklı (698) |
| 5 | 8,387 | `M5a_VOICE_LEXICALIZED` | `STEM(-1)\|Verb->Caus+Verb->PresPart+Adj \|\| STEM(0)\|Verb->PresPart+Adj` | belirmek/belirtmek (4359), anlamak/anlatmak (1887), üremek/üretmek (535), yürümek/yürütmek (475), yaramak/yaratmak (406) | belirten (4359), anlatan (1887), üreten (535), yürüten (475), yaratan (406) |
| 6 | 8,332 | `M1_INFLECTIONAL_SYNCRETISM` | `Noun+A3sg+P2sg+Loc->Rel+Adj \|\| Noun+A3sg+P3sg+Loc->Rel+Adj` | yön (726), el (487), yan (357), yol (248), baş (234) | yönündeki (726), elindeki (487), yanındaki (357), yolundaki (248), başındaki (234) |
| 7 | 8,123 | `M5b_STEM_SUFFIX_COLLISION` | `STEM(-2)\|Noun+A3sg->Acquire+Verb->PresPart+Adj \|\| STEM(0)\|Verb->Pass+Verb->PresPart+Adj` | yayın/yayınlamak (912), yara/yaralamak (830), yayım/yayımlamak (747), plan/planlamak (679), yaka/yakalamak (424) | yayınlanan (912), yaralanan (830), yayımlanan (747), planlanan (677), yakalanan (424) |
| 8 | 7,014 | `M5a_VOICE_LEXICALIZED` | `STEM(-2)\|Verb->Pass+Verb->Inf2+Noun+A3sg+P3sg \|\| STEM(0)\|Verb->Inf2+Noun+A3sg+P3sg` | almak/alınmak (2484), bulmak/bulunmak (1418), bozmak/bozulmak (618), tutmak/tutulmak (443), çekilmek/çekmek (315) | alınması (2484), bulunması (1418), bozulması (618), tutulması (443), çekilmesi (315) |
| 9 | 6,615 | `M6b_ORTHO_OR_COMPOUND_TWIN` | `Verb+Prog1+A3sg \|\| Verb+Prog1+A3sg#ALT` | bilemek/bilmek (1976), sürmek/sürümek (1368), taşmak/taşımak (882), bulamak/bulmak (504), atamak/atmak (405) | biliyor (1976), sürüyor (1368), taşıyor (882), buluyor (504), atıyor (405) |
| 10 | 5,421 | `M3_LEXICAL_POS_POLYSEMY` | `Adj->Zero+Noun+A3sg+Loc->Rel+Adj \|\| Noun+A3sg+Loc->Rel+Adj` | aşağı (2725), yukarı (799), ara (276), içeri (125), üst (105) | aşağıdaki (2725), yukarıdaki (719), aradaki (276), üstteki (105), içerideki (95) |
| 11 | 4,817 | `M6b_ORTHO_OR_COMPOUND_TWIN` | `Verb+Prog1+A1sg \|\| Verb+Prog1+A1sg#ALT` | bilemek/bilmek (2408), dilemek/dilmek (1285), sormak/sorumak (492), bulamak/bulmak (254), atamak/atmak (138) | biliyorum (2408), diliyorum (1285), soruyorum (492), buluyorum (254), atıyorum (138) |
| 12 | 4,796 | `M5a_VOICE_LEXICALIZED` | `STEM(-3)\|Verb->Caus+Verb->Pass+Verb+Past+A3sg \|\| STEM(0)\|Verb->Pass+Verb+Past+A3sg` | bildirmek/bilmek (2768), kaldırmak/kalmak (961), öldürmek/ölmek (439), indirmek/inmek (134), gelişmek/geliştirmek (120) | bildirildi (2768), kaldırıldı (961), öldürüldü (439), indirildi (134), geliştirildi (120) |
| 13 | 4,760 | `M6b_ORTHO_OR_COMPOUND_TWIN` | `Noun+A3sg+Loc \|\| Noun+A3sg+Loc#ALT \|\| Noun+A3sg+Loc@CIRC` | hal/hâl (4696), kap/kâp (64) | halde (4510), hâlde (186), kapta (60), kap'ta (4) |
| 14 | 4,699 | `M3_LEXICAL_POS_POLYSEMY` | `Adj->Zero+Noun+A3sg->Without+Adj \|\| Noun+A3sg->Without+Adj` | rahat (1761), gerek (746), insan (452), eş (257), eksik (196) | rahatsız (1761), gereksiz (746), insansız (452), eşsiz (257), eksiksiz (196) |
| 15 | 4,525 | `M4_PROPER_VS_COMMON` | `STEM(-1)\|Adj->Zero+Noun+A3sg+P3sg+Loc->Rel+Adj \|\| STEM(-1)\|Noun+A3sg+P3sg+Loc->Rel+Adj \|\| STEM(0)\|Noun,Prop+A3sg+P2sg+Loc->Rel+Adj \|\| STEM(0)\|Noun,Prop+A3sg+P3sg+Loc->Rel+Adj` | Aras/ara (4255), Arkas/arka (270) | arasındaki (4255), arkasındaki (270) |
| 16 | 4,334 | `M5a_VOICE_LEXICALIZED` | `STEM(-1)\|Verb->Caus+Verb->Pass+Verb+Past+A3sg \|\| STEM(0)\|Verb->Pass+Verb+Past+A3sg` | belirmek/belirtmek (3454), kapamak/kapatmak (286), üremek/üretmek (112), çıkarmak/çıkartmak (100), anlamak/anlatmak (99) | belirtildi (3454), kapatıldı (286), üretildi (112), çıkartıldı (100), anlatıldı (99) |
| 17 | 4,320 | `M5a_VOICE_LEXICALIZED` | `STEM(-1)\|Verb->Caus+Verb->ByDoingSo+Adv \|\| STEM(0)\|Verb->ByDoingSo+Adv` | belirmek/belirtmek (3507), anlamak/anlatmak (196), üremek/üretmek (91), kapamak/kapatmak (88), yaramak/yaratmak (66) | belirterek (3507), anlatarak (196), üreterek (91), kapatarak (88), yaratarak (66) |
| 18 | 4,268 | `M7_HYBRID_MULTI_LOCUS` | `Adj->Zero+Noun+A3sg+P2sg+Loc->Rel+Adj \|\| Adj->Zero+Noun+A3sg+P3sg+Loc->Rel+Adj \|\| Noun+A3sg+P2sg+Loc->Rel+Adj \|\| Noun+A3sg+P3sg+Loc->Rel+Adj` | iç (1738), dış (1027), ön (576), kuzey (183), civar (167) | içindeki (1738), dışındaki (1027), önündeki (576), kuzeyindeki (183), civarındaki (167) |
| 19 | 4,215 | `M4_PROPER_VS_COMMON` | `Noun+A3sg+Loc->Rel+Adj \|\| Noun,Prop+A3sg+Loc->Rel+Adj` | Dünya/dünya (1031), Ülke/ülke (538), Irak/ırak (275), Grup/grup (144), Meclis/meclis (142) | dünyadaki (956), ülkedeki (538), ırak'taki (275), gruptaki (142), sokaktaki (102) |
| 20 | 4,170 | `M6b_ORTHO_OR_COMPOUND_TWIN` | `Noun+A3sg \|\| Noun+A3sg@CIRC` | adet/âdet (3563), bekar/bekâr (217), nar/nâr (104), şura/şûra (88), alem/âlem (71) | adet (3544), bekar (194), nar (104), şura (61), alem (53) |
| 21 | 4,158 | `M5b_STEM_SUFFIX_COLLISION` | `STEM(-2)\|Noun+A3sg->Acquire+Verb->PresPart+Adj \|\| STEM(-4)\|Verb->PresPart+Noun+A3sg->Acquire+Verb->PresPart+Adj \|\| STEM(0)\|Verb->Pass+Verb->PresPart+Adj` | düzen/düzenlemek/düzmek (4158) | düzenlenen (4158) |
| 22 | 4,048 | `M3_LEXICAL_POS_POLYSEMY` | `Adj->Zero+Verb+Pres+A3sg+Cop \|\| Adj->Zero+Verb+Pres+A3sg+Cop#ALT \|\| Noun+A3sg->Zero+Verb+Pres+A3sg+Cop` | yok (4048) | yoktur (4048) |
| 23 | 3,932 | `M3_LEXICAL_POS_POLYSEMY` | `Adj->Zero+Verb+Pres+A3sg+Cop \|\| Noun+A3sg->Zero+Verb+Pres+A3sg+Cop` | mevcut (652), iyi (447), zor (312), yanlış (286), ibaret (284) | mevcuttur (652), iyidir (447), zordur (312), yanlıştır (286), ibarettir (283) |
| 24 | 3,891 | `M5a_VOICE_LEXICALIZED` | `STEM(-2)\|Verb->Pass+Verb->ByDoingSo+Adv \|\| STEM(0)\|Verb->ByDoingSo+Adv` | almak/alınmak (1564), bulmak/bulunmak (334), bozmak/bozulmak (306), ayrılmak/ayırmak (242), üzmek/üzülmek (217) | alınarak (1564), bulunarak (334), bozularak (306), ayrılarak (242), üzülerek (217) |
| 25 | 3,715 | `M5b_STEM_SUFFIX_COLLISION` | `STEM(-2)\|Verb+Aor->While+Adv \|\| STEM(0)\|Noun+A3sg->Zero+Verb->While+Adv` | eder/etmek (1406), gider/gitmek (713), çıkar/çıkmak (489), gelir/gelmek (344), çeker/çekmek (215) | ederken (1406), giderken (713), çıkarken (489), gelirken (344), çekerken (215) |

## 9. Top 30 Unambiguous Word Bigrams (`K = 1` + `K = 1`)

| Rank | Unambiguous Bigram | Occurrences |
| ---: | :--- | ---: |
| 1 | `söz konusu` | 230 |
| 2 | `başbakan erdoğan` | 123 |
| 3 | `tayyip erdoğan` | 99 |
| 4 | `ifade ederek` | 86 |
| 5 | `prof. dr.` | 81 |
| 6 | `ve diğer` | 74 |
| 7 | `ifade edildi` | 71 |
| 8 | `mehmet ali` | 68 |
| 9 | `kişi öldü` | 62 |
| 10 | `meydana geldi` | 59 |
| 11 | `turkcell süper` | 57 |
| 12 | `kıbrıs rum` | 54 |
| 13 | `kültür ve` | 51 |
| 14 | `eğitim ve` | 49 |
| 15 | `şunları kaydetti` | 47 |
| 16 | `şunları söyledi` | 46 |
| 17 | `olarak da` | 45 |
| 18 | `iki kez` | 43 |
| 19 | `1 milyon` | 43 |
| 20 | `ve turizm` | 41 |
| 21 | `milyon euro` | 40 |
| 22 | `işlem gören` | 39 |
| 23 | `zaman zaman` | 39 |
| 24 | `puan artarak` | 38 |
| 25 | `kabul edildi` | 37 |
| 26 | `tahmin ediliyor` | 37 |
| 27 | `olarak kabul` | 35 |
| 28 | `doç. dr.` | 35 |
| 29 | `hiçbir zaman` | 35 |
| 30 | `ahmet necdet` | 35 |
