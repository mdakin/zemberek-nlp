# Turkish Disambiguation Dataset Selection Report (`SCSS-v1`)

## 1. Executive Summary & Generated Files

| Metric | Value |
| :--- | :--- |
| **Timestamp** | `2026-11-08T01:19:38.438280318Z` |
| **Source Corpus** | `/home/dndara/data/turkish/refined` (29 domains) |
| **Output Directory** | `/home/dndara/data/turkish/selected_slices` |
| **Candidate Pool Evaluated** | **549,929** clean sentences (30,114 unique `ZAS-v3` signatures) |
| **Held-Out Test Pool** | **10,000** sentences across **10 slices** (`test_slices/test_slice_001.txt` .. `test_slice_010.txt`) |
| **Master Training Pool** | **100,000** sentences across **100 slices** (`train_slices/train_slice_001.txt` .. `train_slice_100.txt`) |
| **Total Selection Wall Time** | **143.59 seconds** |

## 2. Held-Out Test Pool Profile (`10 Slices x 1,000 Sentences`)

All 10,000 test sentences and their 4-gram word shingles are strictly excluded from the 100,000 training sentences.

| Test Subset / Slice | Sentences | Total Words | Ambig Words (%) | Unique Signatures | Corpus Sig Mass Covered (%) | Unique `(Sig, Lemma)` | `LEXICAL_ONLY` Tokens (%) |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `test_slices/test_slice_001.txt` *(Single Slice)* | **1,000** | 10,012 | 6,587 (65.8%) | 2,014 | **85.00%** | 3,848 | 1,092 (16.6%) |
| `test_10k_ordered.txt` *(All 10 Slices)* | **10,000** | 101,971 | 66,750 (65.5%) | 6,581 | **96.04%** | 20,201 | 10,085 (15.1%) |
| ↳ *Natural Component (700/slice)* | **7,000** | 73,040 | 46,242 (63.3%) | 4,860 | **94.71%** | 14,240 | 3,755 (8.1%) |
| ↳ *Challenge Component (300/slice)* | **3,000** | 28,931 | 20,508 (70.9%) | 3,945 | **89.67%** | 9,875 | 6,330 (30.9%) |

### Macro-Mechanism Distribution in Test Pool: Natural vs. Challenge Components

| Macro-Mechanism | Natural Component Tokens (%) | Challenge Component Tokens (%) | Combined 10K Test Pool (%) | Diagnostic Role |
| :--- | ---: | ---: | ---: | :--- |
| **`M1_INFLECTIONAL_SYNCRETISM`** | 6,193 (13.39%) | 2,822 (**13.76%**) | 9,015 (13.51%) | Same root & derivation; suffix homophony (-I, -In, -lArI) |
| **`M2_DERIVATIONAL_SHIFT`** | 4,196 (9.07%) | 1,967 (**9.59%**) | 6,163 (9.23%) | Same root; different IG count or derived POS (Participle/Inf/Zero) |
| **`M3_LEXICAL_POS_POLYSEMY`** | 10,251 (22.17%) | 2,017 (**9.84%**) | 12,268 (18.38%) | Same lemma & stem; different lexical POS (Adj/Noun, Det/Pron) |
| **`M4_PROPER_VS_COMMON`** | 9,277 (20.06%) | 3,656 (**17.83%**) | 12,933 (19.38%) | ProperNoun/Abbreviation vs Common word |
| **`M5a_VOICE_LEXICALIZED`** | 518 (1.12%) | 1,880 (**9.17%**) | 2,398 (3.59%) | Stem-boundary split with identical final IG (F15/F17 blind) |
| **`M5b_STEM_SUFFIX_COLLISION`** | 13,239 (28.63%) | 4,470 (**21.80%**) | 17,709 (26.53%) | Stem-boundary split with different final IGs |
| **`M6a_HOMONYM_DIFF_POS`** | 1,990 (4.30%) | 1,423 (**6.94%**) | 3,413 (5.11%) | Same-length homonymous roots across different POS |
| **`M6b_ORTHO_OR_COMPOUND_TWIN`** | 293 (0.63%) | 1,170 (**5.71%**) | 1,463 (2.19%) | Circumflex twins, CompoundP3sg twins, or vowel-drop twins |
| **`M7_HYBRID_MULTI_LOCUS`** | 285 (0.62%) | 1,103 (**5.38%**) | 1,388 (2.08%) | Multi-locus ambiguity combining root/POS + inflectional syncretism |

## 3. Prefix Learning-Curve Coverage: `SCSS-v1` vs. Uniform Random Sampling

This table compares taking the first $K$ sentences of `train_100k_ordered.txt` (`Slices 1..k`) against $K$ uniformly sampled clean sentences:

| Prefix Budget ($K$) | Strategy | Unique `ZAS-v3` Signatures | Corpus Ambiguity Mass Covered (%) | Unique `(Sig, Lemma)` Pairs | Unique `(Sig, ContextFrame)` Pairs | `LEXICAL_ONLY` Twins (`F15`/`F17` Blind) |
| ---: | :--- | ---: | ---: | ---: | ---: | ---: |
| **1,000 sents** | **`SCSS-v1` (Ours)** | **3,829** | **93.38%** | **6,167** | **8,042** | **1,698** |
| *(1 slices)* | *Uniform Random* | *1,687* | *84.79%* | *3,443* | *6,198* | *562* |
| **2,000 sents** | **`SCSS-v1` (Ours)** | **5,693** | **95.87%** | **10,832** | **15,613** | **3,174** |
| *(2 slices)* | *Uniform Random* | *2,551* | *89.41%* | *5,860* | *11,912* | *1,125* |
| **5,000 sents** | **`SCSS-v1` (Ours)** | **9,151** | **97.82%** | **21,931** | **37,157** | **7,365** |
| *(5 slices)* | *Uniform Random* | *4,140* | *93.58%* | *11,356* | *27,514* | *2,743* |
| **10,000 sents** | **`SCSS-v1` (Ours)** | **12,636** | **98.74%** | **35,662** | **70,647** | **13,454** |
| *(10 slices)* | *Uniform Random* | *5,876* | *95.75%* | *18,176* | *50,784* | *5,494* |
| **25,000 sents** | **`SCSS-v1` (Ours)** | **17,980** | **99.40%** | **62,355** | **160,775** | **29,214** |
| *(25 slices)* | *Uniform Random* | *8,840* | *97.66%* | *32,056* | *110,328* | *13,515* |
| **50,000 sents** | **`SCSS-v1` (Ours)** | **22,277** | **99.70%** | **88,554** | **289,801** | **51,695** |
| *(50 slices)* | *Uniform Random* | *11,938* | *98.57%* | *48,104* | *194,355* | *26,971* |
| **100,000 sents** | **`SCSS-v1` (Ours)** | **26,119** | **99.87%** | **116,974** | **499,194** | **89,515** |
| *(100 slices)* | *Uniform Random* | *15,911* | *99.19%* | *70,578* | *334,621* | *54,147* |

## 4. Individual 1,000-Sentence Training Slice Consistency Audit

Every individual `train_slice_XXX.txt` (1,000 sentences) is engineered to be a self-contained, representative training set across sentence lengths, domains, and all 9 Macro-Mechanisms:

| Slice File | Sentences | Words | Ambig Words (%) | `7-9w / 10-12w / 13-15w` (`%`) | Domains | Unique Signatures | Corpus Sig Mass Covered (%) | `M1+M2` Morpho (%) | `LEXICAL_ONLY` (%) |
| :--- | ---: | ---: | ---: | :---: | ---: | ---: | ---: | ---: | ---: |
| `train_slice_001.txt` | 1,000 | 10,574 | 8,130 (76.9%) | `35% / 40% / 25%` | 29/29 | 3,829 | **93.38%** | 17.8% | 20.9% |
| `train_slice_002.txt` | 1,000 | 10,521 | 7,765 (73.8%) | `35% / 40% / 25%` | 29/29 | 3,891 | **91.73%** | 19.7% | 19.0% |
| `train_slice_005.txt` | 1,000 | 10,585 | 7,591 (71.7%) | `35% / 40% / 25%` | 29/29 | 3,495 | **90.51%** | 21.1% | 17.3% |
| `train_slice_010.txt` | 1,000 | 10,664 | 7,479 (70.1%) | `35% / 40% / 25%` | 29/29 | 3,257 | **90.75%** | 22.1% | 15.3% |
| `train_slice_025.txt` | 1,000 | 10,627 | 7,259 (68.3%) | `35% / 40% / 25%` | 29/29 | 2,930 | **90.96%** | 21.4% | 13.2% |
| `train_slice_050.txt` | 1,000 | 10,635 | 7,223 (67.9%) | `35% / 40% / 25%` | 29/29 | 2,733 | **90.87%** | 21.7% | 11.4% |
| `train_slice_075.txt` | 1,000 | 10,685 | 7,157 (67.0%) | `35% / 40% / 25%` | 29/29 | 2,619 | **90.83%** | 21.3% | 10.5% |
| `train_slice_100.txt` | 1,000 | 10,678 | 7,078 (66.3%) | `35% / 40% / 25%` | 29/29 | 2,514 | **90.64%** | 21.2% | 9.6% |

## 5. Sample Selected Sentences from `train_slice_001.txt` and `test_slice_001.txt`

### First 10 Sentences of `train_slice_001.txt`

1. **[ntvmsnbc.txt | 12w | 10 ambig]** `Kazada ağır yaralanan Gamze Gültepe'nin sol bacağının kesilmesi için ameliyata alındığı belirtildi.`
2. **[www.dunya.com.txt | 7w | 6 ambig]** `Kentin bir kısmındaki elektrik kesintisi de sürüyor.`
3. **[ntv-750k-sentences.txt | 15w | 14 ambig]** `Cumhurbaşkanı Erdoğan'ın, Başbakanlığı döneminde, çalışma ofisinde dinleme cihazı bulunması ile ilgili davanın 6. duruşması tamamlandı.`
4. **[star-gazete.txt | 10w | 9 ambig]** `Vücudunuzda bulunan tüm yağ depolarının temelinde fazlaca alınan gıdalar yatmaktadır.`
5. **[open-subtitles-tr-2018.txt | 7w | 6 ambig]** `Onun bizim peşimizde olduğunu ona anlatmak zorundayız.`
6. **[tbmm-tutanak.txt | 12w | 10 ambig]** `Şimdi, Sayın Canikli'ye soruyorum: Emlak vergisi 2464 sayılı Yasa'ya göre mi alınıyor?`
7. **[forum.shiftdelete.net.txt | 13w | 12 ambig]** `Biri bize emretmediği sürece ne öldürülen askerlerimizin ne de öldürülen vatandaşlarımızın hesabını soruyoruz.`
8. **[cnn-turk.txt | 8w | 7 ambig]** `Genç kızın cesedi, Adli Tıp Kurumu'nun morguna kaldırıldı.`
9. **[www.hukuki.net.txt | 11w | 10 ambig]** `İslam devleti sonunda bütün dünyayı hakimiyeti altına alacak ve İslam yapacaktır.`
10. **[www.bilim.org.txt | 7w | 6 ambig]** `Bazı açık erişimli dergilerin kuvvetli olduğu anlatılıyor.`

### First 10 Sentences of `test_slice_001.txt`

1. **[cnn-turk.txt | `NATURAL` | `M5b_STEM_SUFFIX_COLLISION` | 9w | 7 ambig]** `Otel yatırımları için yeni cazibe merkezi İkitelli ve Güneşli.`
2. **[dunya.txt | `NATURAL` | `M1_INFLECTIONAL_SYNCRETISM` | 7w | 2 ambig]** `Sanayi, üretim düşüşünün üçte ikisi kadar daralırsa.`
3. **[www.haberturk.com.txt | `CHALLENGE` | `M1_INFLECTIONAL_SYNCRETISM` | 7w | 6 ambig]** `Adreslerinde bulunamayan şüphelilerin yakalanması için çalışmalar sürüyor.`
4. **[forum.memurlar.net.txt | `NATURAL` | `M5b_STEM_SUFFIX_COLLISION` | 10w | 5 ambig]** `İran ve Rusya ile üçlü zirveyi de bunun için gerçekleştireceğiz.`
5. **[forum.shiftdelete.net.txt | `NATURAL` | `M2_DERIVATIONAL_SHIFT` | 9w | 3 ambig]** `Günlük maksimum 1 kahve, iki fincan çay içmek gerekir.`
6. **[www.haberturk.com.txt | `CHALLENGE` | `M2_DERIVATIONAL_SHIFT` | 10w | 8 ambig]** `Hedefimiz, birinciliklerimizi korumanın yanında ikinciliklerimizi ve üçüncülüklerimizi de birinciliklere taşımak.`
7. **[hukuki-net.txt | `NATURAL` | `M4_PROPER_VS_COMMON` | 15w | 9 ambig]** `Zaten nakil hattının geçtiği güzergahta, elektrik idaresince daimi irtifak hakkı tesis edilir, ve kütüğüne işlenir.`
8. **[ntv-750k-sentences.txt | `NATURAL` | `M5b_STEM_SUFFIX_COLLISION` | 14w | 8 ambig]** `Ayrı yumurta ikizi iki sorunlu çocuk ve bir de kayınbirader ile aynı evde yaşıyorlar.`
9. **[open-subtitles-tr-2018.txt | `CHALLENGE` | `M3_LEXICAL_POS_POLYSEMY` | 7w | 6 ambig]** `O halde bu yaratıkları yakalayıp öldürmek zorundasınız.`
10. **[ntvmsnbc.txt | `NATURAL` | `M5b_STEM_SUFFIX_COLLISION` | 10w | 5 ambig]** `Alt kısımda bandı biraz geniş tutarsak 1138 taban seviyemiz diyebiliriz.`
