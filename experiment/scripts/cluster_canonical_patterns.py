#!/usr/bin/env python3
"""
cluster_canonical_patterns.py - Stage 2: Canonical Pattern Clustering & Contrastive Synthesis

Processes the 6,387 profiled error tokens from Stage 1 and partitions them into exactly
36 canonical Master Pattern Bundles across the 6 Macro-Axes.
For each bundle, samples 3 diverse Error Exemplars and 2 Positive Control Exemplars
from the golden benchmark evaluation files.

Outputs:
  - data/ambiguity/stage2_canonical_patterns.json
  - data/ambiguity/stage2_canonical_patterns_summary.json
"""

import json
import os
from collections import defaultdict, Counter

INPUT_TOKENS_JSONL = "data/ambiguity/error_profiled_tokens.jsonl"
BENCHMARK_FILES = [
    'data/ambiguity/precision_test_golden1000_th20k_vs_33k.json',
    'data/ambiguity/precision_test_golden2500_th20k_vs_33k.json',
    'data/ambiguity/precision_test_goldenfresh2500_th20k_vs_33k.json'
]
OUT_CANONICAL_JSON = "data/ambiguity/stage2_canonical_patterns.json"
OUT_SUMMARY_JSON = "data/ambiguity/stage2_canonical_patterns_summary.json"

MASTER_PATTERNS_META = {
    # Axis 1: Case & Agreement Syncretism
    "P01_CASE_ACC_TO_P3SG_PARTICIPLE": {
        "axis": "AXIS_1_CASE_SYNCRETISM",
        "name": "Accusative Direct Object in Subordinate Nominalization vs Compound Head",
        "direction": "GT:Noun+Acc -> PRED:Noun+P3sg",
        "desc": "Noun direct object preceding an embedded participle head (-DIK/-AcAK). Model falsely binds noun to a preceding genitive as a compound head."
    },
    "P02_CASE_P3SG_TO_ACC_GENITIVE": {
        "axis": "AXIS_1_CASE_SYNCRETISM",
        "name": "Genitive Possessive Head vs Direct Object",
        "direction": "GT:Noun+P3sg -> PRED:Noun+Acc",
        "desc": "Noun compound head governed by an overt preceding Genitive NP. Model falsely predicts bare accusative direct object."
    },
    "P03_CASE_ACC_TO_P3SG_BARE_TRANSITIVE": {
        "axis": "AXIS_1_CASE_SYNCRETISM",
        "name": "Matrix Clause Transitive Direct Object vs Bare Possessive",
        "direction": "GT:Noun+Acc -> PRED:Noun+P3sg",
        "desc": "Definite direct object in matrix transitive clause lacking an overt owner. Model falsely predicts 3rd person possessive."
    },
    "P04_CASE_P2SG_TO_GEN": {
        "axis": "AXIS_1_CASE_SYNCRETISM",
        "name": "2nd Person Possessive (-in) vs Genitive Case Specifier",
        "direction": "GT:Noun+P2sg -> PRED:Noun+Gen",
        "desc": "Noun carrying 2nd person possessive suffix -in (senin evin). Model misinterprets as genitive case specifier."
    },
    "P05_CASE_GEN_TO_P2SG": {
        "axis": "AXIS_1_CASE_SYNCRETISM",
        "name": "Genitive Case Specifier (-in) vs 2nd Person Possessive",
        "direction": "GT:Noun+Gen -> PRED:Noun+P2sg",
        "desc": "Genitive case modifier (evin kapısı). Model misinterprets as 2nd person possessive."
    },
    "P06_CASE_PLURAL_POSSESSIVE_AMBIGUITY": {
        "axis": "AXIS_1_CASE_SYNCRETISM",
        "name": "Plural / Possessive Portmanteau Ambiguity (-ları / -leri)",
        "direction": "GT:Noun+lar:A3pl+ı:P3sg <--> Noun+A3sg+ları:P3pl <--> Acc",
        "desc": "Complex syncretism between plural noun + singular possessive, singular noun + plural possessive, and plural accusative."
    },

    # Axis 2: Category Functional Shift
    "P07_SHIFT_ADJ_TO_ADV_PREVERBAL": {
        "axis": "AXIS_2_CATEGORY_FUNCTIONAL_SHIFT",
        "name": "Pre-verbal Modifier: Adverb vs Adjective",
        "direction": "GT:Adv -> PRED:Adj",
        "desc": "Zero-derived modifier modifying an adjacent verb (iyi konuştu). Model defaults to adjectival base form."
    },
    "P08_SHIFT_ADV_TO_ADJ_PRENOMINAL": {
        "axis": "AXIS_2_CATEGORY_FUNCTIONAL_SHIFT",
        "name": "Pre-nominal Modifier: Attributive Adjective vs Adverb",
        "direction": "GT:Adj -> PRED:Adv",
        "desc": "Modifier preceding a noun head (iyi insan). Model misinterprets as adverbial modifier."
    },
    "P09_SHIFT_NOUN_TO_ADJ_ATTRIBUTIVE": {
        "axis": "AXIS_2_CATEGORY_FUNCTIONAL_SHIFT",
        "name": "Attributive Classifying Noun vs Adjective",
        "direction": "GT:Adj -> PRED:Noun",
        "desc": "Zero-derived relational word modifying a noun (ana cadde, genç adam). Model defaults to noun root."
    },
    "P10_SHIFT_ADJ_TO_NOUN_SUBSTANTIVIZED": {
        "axis": "AXIS_2_CATEGORY_FUNCTIONAL_SHIFT",
        "name": "Substantivized / Elliptical Adjective as Argument Noun",
        "direction": "GT:Noun -> PRED:Adj",
        "desc": "Adjective functioning as head noun / argument in ellipsis (gençler, yaşlılar). Model defaults to adjective."
    },
    "P11_SHIFT_TEMPORAL_NOUN_VS_ADV": {
        "axis": "AXIS_2_CATEGORY_FUNCTIONAL_SHIFT",
        "name": "Temporal / Spatial Noun vs Adverbial Adjunct",
        "direction": "GT:Noun <--> PRED:Adv",
        "desc": "Time words (sabah, gece, akşam, sonra, önce) alternating between bare temporal noun and adverbial adjunct."
    },

    # Axis 3: Clausal Derivations
    "P12_CLAUSAL_PARTICIPLE_TO_FINITE_PRENOMINAL": {
        "axis": "AXIS_3_CLAUSAL_DERIVATIONS",
        "name": "Pre-nominal Participle Modifier vs Finite Matrix Verb",
        "direction": "GT:Verb|Part->Adj -> PRED:Verb+Finite",
        "desc": "Participle ending in -miş, -ecek, or -an modifying a noun. Model misinterprets as finite main clause predicate."
    },
    "P13_CLAUSAL_FINITE_TO_PARTICIPLE_FINAL": {
        "axis": "AXIS_3_CLAUSAL_DERIVATIONS",
        "name": "Sentence-Final Finite Verb vs Participle",
        "direction": "GT:Verb+Finite -> PRED:Verb|Part->Adj",
        "desc": "Main matrix verb in sentence-final position. Model misinterprets as non-finite participle."
    },
    "P14_CLAUSAL_VERBAL_NOUN_TO_IMP": {
        "axis": "AXIS_3_CLAUSAL_DERIVATIONS",
        "name": "Action Nominalization (-ma/-me) vs Negative Imperative",
        "direction": "GT:Verb|Inf2->Noun -> PRED:Verb+Neg+Imp",
        "desc": "Verbal noun expressing action (konuşma süresi). Model falsely predicts negative command (konuşma!)."
    },
    "P15_CLAUSAL_IMP_TO_VERBAL_NOUN": {
        "axis": "AXIS_3_CLAUSAL_DERIVATIONS",
        "name": "Negative Imperative (-ma/-me) vs Action Nominalization",
        "direction": "GT:Verb+Neg+Imp -> PRED:Verb|Inf2->Noun",
        "desc": "Negative imperative command. Model falsely predicts verbal noun."
    },

    # Axis 4: Deictic & Specifier Shifts
    "P16_SPEC_PRON_TO_DET_BEFORE_NON_NOUN": {
        "axis": "AXIS_4_DEICTIC_SPECIFIER_SHIFTS",
        "name": "Demonstrative Pronoun before Non-Noun vs Determiner",
        "direction": "GT:Pron,Demons -> PRED:Det",
        "desc": "Demonstrative (bu, şu, o) preceding a verb, adverb, or postposition (bu çok ayrı). Model blindly tags as determiner."
    },
    "P17_SPEC_PRON_TO_DET_WITH_COMMA": {
        "axis": "AXIS_4_DEICTIC_SPECIFIER_SHIFTS",
        "name": "Demonstrative Subject Pronoun with Comma",
        "direction": "GT:Pron,Demons -> PRED:Det",
        "desc": "Demonstrative followed by a comma (Bu, önemlidir). Model ignores comma boundary and predicts determiner."
    },
    "P18_SPEC_DET_TO_PRON_BEFORE_NOUN": {
        "axis": "AXIS_4_DEICTIC_SPECIFIER_SHIFTS",
        "name": "Determiner modifying Noun Head vs Pronoun",
        "direction": "GT:Det -> PRED:Pron,Demons",
        "desc": "Determiner modifying an overt noun head (bu karar). Model fails to bind and predicts pronoun."
    },
    "P19_SPEC_CARDINAL_NUM_TO_INDEF_DET": {
        "axis": "AXIS_4_DEICTIC_SPECIFIER_SHIFTS",
        "name": "Cardinal Number 'bir' vs Indefinite Determiner",
        "direction": "GT:Num,Card -> PRED:Det",
        "desc": "Word 'bir' functioning as strict mathematical quantity (bir kişi geldi). Model predicts indefinite article."
    },
    "P20_SPEC_INDEF_DET_TO_CARDINAL_NUM": {
        "axis": "AXIS_4_DEICTIC_SPECIFIER_SHIFTS",
        "name": "Indefinite Article 'bir' vs Cardinal Number",
        "direction": "GT:Det -> PRED:Num,Card",
        "desc": "Word 'bir' functioning as indefinite non-referential determiner (güzel bir gün). Model predicts cardinal number."
    },
    "P21_SPEC_PRON_PERS_VS_DEMONS": {
        "axis": "AXIS_4_DEICTIC_SPECIFIER_SHIFTS",
        "name": "3rd Person Personal Pronoun vs Demonstrative (o / onlar)",
        "direction": "GT:Pron,Pers <--> PRED:Pron,Demons",
        "desc": "Pronoun 'o / onlar / onu / onda' referring to human agent vs inanimate entity/event."
    },

    # Axis 5: Root Homonymy & Voice Derivation
    "P22_ROOT_PROPER_VS_COMMON_NOUN": {
        "axis": "AXIS_5_ROOT_HOMONYMY_AND_VOICE",
        "name": "Proper Noun vs Common Noun Capitalization Collision",
        "direction": "GT:ProperNoun <--> PRED:CommonNoun",
        "desc": "Capitalized words colliding between named entities and common vocabulary (Kanun, Ay, Dünya, Ocak, Mart, Banka)."
    },
    "P23_ROOT_VOICE_DERIVATION_VS_LEXICAL_VERB": {
        "axis": "AXIS_5_ROOT_HOMONYMY_AND_VOICE",
        "name": "Voice Derivation (Causative/Passive) vs Lexicalized Verb Root",
        "direction": "GT:Verb (Base+Caus/Pass) <--> PRED:Verb (LexicalRoot)",
        "desc": "Causative/Passive morphs alternating with lexicalized dictionary entries (belirtmek/belirmek, azaltmak/azalmak, öldürmek/ölmek)."
    },
    "P24_ROOT_NOUN_VS_VERB_HOMONYMY": {
        "axis": "AXIS_5_ROOT_HOMONYMY_AND_VOICE",
        "name": "Monosyllabic Noun vs Verb Root Homonymy",
        "direction": "GT:Noun <--> PRED:Verb",
        "desc": "Ambiguity on monosyllabic roots with identical spelling (kat, yol, düş, iç, yaz, bin)."
    },
    "P25_ROOT_ADJ_VS_VERB_HOMONYMY": {
        "axis": "AXIS_5_ROOT_HOMONYMY_AND_VOICE",
        "name": "Adjective vs Verb Root Homonymy",
        "direction": "GT:Adj <--> PRED:Verb",
        "desc": "Roots colliding between adjective and verb meanings (aç, sık, kuru, düz)."
    },
    "P26_ROOT_ADV_VS_NOUN_HOMONYMY": {
        "axis": "AXIS_5_ROOT_HOMONYMY_AND_VOICE",
        "name": "Spatial Directional Root: Adverb vs Noun",
        "direction": "GT:Adv <--> PRED:Noun",
        "desc": "Spatial direction terms (içeri, dışarı, yukarı, aşağı) alternating between adverbial modifier and case-marked noun."
    },
    "P27_ROOT_ADJ_VS_NOUN_DIFFLEMMA": {
        "axis": "AXIS_5_ROOT_HOMONYMY_AND_VOICE",
        "name": "Distinct Dictionary Root Homonymy: Adjective vs Noun",
        "direction": "GT:Adj <--> PRED:Noun",
        "desc": "Collisions between separate adjective and noun dictionary entries."
    },
    "P28_ROOT_NUM_VS_NOUN_HOMONYMY": {
        "axis": "AXIS_5_ROOT_HOMONYMY_AND_VOICE",
        "name": "Numeral vs Noun Root Homonymy",
        "direction": "GT:Num <--> PRED:Noun",
        "desc": "Numeral roots colliding with noun homophones (yüz, bin, kırk)."
    },

    # Axis 6: Lexicon & Normalization Artifacts
    "P29_ARTIFACT_CIRCUMFLEX_TWIN": {
        "axis": "AXIS_6_LEXICON_ARTIFACTS",
        "name": "Circumflex / Diacritic Orthographic Twins",
        "direction": "GT:AccentedLemma <--> PRED:UnaccentedLemma",
        "desc": "Unaccented web text forcing blind guessing between accented and unaccented dictionary items (hâl/hal, bekâr/bekar, âdet/adet)."
    },
    "P30_ARTIFACT_DICTIONARY_NISBA_TWIN": {
        "axis": "AXIS_6_LEXICON_ARTIFACTS",
        "name": "Dictionary Nisba Adjective vs Possessive Noun",
        "direction": "GT:Adj <--> PRED:Noun+P3sg",
        "desc": "Dictionary item ending in -î competing with noun + 3rd person possessive -i (tahminî vs tahmin+i)."
    },
    "P31_ARTIFACT_FIXED_NOUN_YUZDE": {
        "axis": "AXIS_6_LEXICON_ARTIFACTS",
        "name": "Fixed Lexicalized Noun 'yüzde' vs Inflection 'yüz+de'",
        "direction": "GT:yüzde:Noun <--> PRED:yüz:Num+de:Loc",
        "desc": "Dictionary entry 'yüzde:Noun' directly competing with productive derivation 'yüz:Num|Zero->Noun+de:Loc'."
    },
    "P32_ARTIFACT_FUNCTION_WORD_CONVENTION": {
        "axis": "AXIS_6_LEXICON_ARTIFACTS",
        "name": "Closed-Class Function Word POS Convention Disputes",
        "direction": "GT:Conj <--> PRED:Postp / Adv",
        "desc": "Discrepancies in theoretical POS classification on function words (ile, ise, değil, yani, önce, sonra)."
    },
    "P33_ARTIFACT_POSTPOSITION_CASE_FRAME": {
        "axis": "AXIS_6_LEXICON_ARTIFACTS",
        "name": "Postposition Subcategorization Case-Frame Conflict",
        "direction": "GT:Postp[PCNom] <--> PRED:Postp[PCDat]",
        "desc": "Secondary subcategorization disagreement on postpositions (kadar, için)."
    },

    # Tail Patterns
    "P34_CASE_RELATIONAL_SPATIAL_NOUN_LOCATIVE": {
        "axis": "TAIL_PATTERNS",
        "name": "Relational Spatial Noun Locative/Dative Suffixation",
        "direction": "GT:Noun+nde:Loc <--> PRED:Noun+P3sg+nde:Loc",
        "desc": "Inherent relational nouns (üzerinde, altında, içinde) alternating between bare and possessive-governed locative."
    },
    "P35_FUNCTION_ADV_VS_CONJ": {
        "axis": "TAIL_PATTERNS",
        "name": "Discourse Adverb vs Coordinating Conjunction",
        "direction": "GT:Adv <--> PRED:Conj",
        "desc": "Connectives alternating between sentence adverb and conjunction (ancak, ne...ne, gerçi)."
    },
    "P36_INTERJECTION_VS_CONTENT_WORD": {
        "axis": "TAIL_PATTERNS",
        "name": "Interjection vs Content Word Collision",
        "direction": "GT:Interj <--> PRED:ContentWord",
        "desc": "Discourse markers and conversational interjections colliding with nouns or adverbs (hayır, tamam, evet, yazık)."
    }
}

def classify_token_to_pattern(t):
    nat = t['nature']
    morph = t['morphology']
    gt_l = morph['gt_lemma']
    pr_l = morph['pred_lemma']
    gt_k = morph['gt_key']
    pr_k = morph['pred_key']
    diff = morph['affix_diff']
    root_rel = morph['root_relation']
    micro = t['micro_context']
    anchors = t['syntactic_anchors']
    left_gen = anchors['left_genitive']
    right_pred = anchors['right_predicate']
    surf_clean = t['surface'].lower().strip('.,:;!?"\'')

    # Axis 6: Artifacts
    if nat == 'LexiconArtifact':
        if 'yüzde' in gt_l or 'yüzde' in pr_l:
            return 'P31_ARTIFACT_FIXED_NOUN_YUZDE'
        if any(w in gt_l for w in ('tahmin', 'resm', 'tarih', 'ilm', 'mill', 'din')) or any(w in pr_l for w in ('tahmin', 'resm', 'tarih', 'ilm', 'mill', 'din')):
            return 'P30_ARTIFACT_DICTIONARY_NISBA_TWIN'
        if 'kadar' in gt_l:
            return 'P33_ARTIFACT_POSTPOSITION_CASE_FRAME'
        def norm(s):
            for c, r in {'â':'a','î':'i','û':'u','Â':'A','Î':'I','Û':'U'}.items():
                s = s.replace(c, r)
            return s.lower()
        if norm(gt_l) == norm(pr_l) and gt_l.lower() != pr_l.lower():
            return 'P29_ARTIFACT_CIRCUMFLEX_TWIN'
        return 'P32_ARTIFACT_FUNCTION_WORD_CONVENTION'

    gt_pos = gt_k.split(']')[0].split(':')[1].split(',')[0] if ':' in gt_k else ''
    pr_pos = pr_k.split(']')[0].split(':')[1].split(',')[0] if ':' in pr_k else ''

    # Tail: Relational Nouns
    if gt_l in ('üzeri', 'iç', 'alt', 'ön', 'yan', 'ara') and ('nde:Loc' in diff or 'ne:Dat' in diff):
        return 'P34_CASE_RELATIONAL_SPATIAL_NOUN_LOCATIVE'

    # Tail: Interjections
    if 'Interj' in gt_k or 'Interj' in pr_k:
        return 'P36_INTERJECTION_VS_CONTENT_WORD'

    # Tail: Function particles / Conj / Adv / Correlative
    if set([gt_pos, pr_pos]) in [set(['Adv', 'Conj']), set(['Pron', 'Conj']), set(['Adj', 'Conj']), set(['Adj', 'Postp']), set(['Adv', 'Postp'])]:
        return 'P35_FUNCTION_ADV_VS_CONJ'

    # Axis 5: DiffLemma (Root Homonymy & Voice)
    if root_rel == 'DiffLemma':
        if 'ProperNoun' in gt_k or 'ProperNoun' in pr_k or (gt_pos == 'Noun' and pr_pos == 'Noun' and (gt_l[0].isupper() or pr_l[0].isupper())):
            return 'P22_ROOT_PROPER_VS_COMMON_NOUN'
        if gt_pos == 'Verb' and pr_pos == 'Verb':
            return 'P23_ROOT_VOICE_DERIVATION_VS_LEXICAL_VERB'
        if set([gt_pos, pr_pos]) == set(['Noun', 'Verb']):
            return 'P24_ROOT_NOUN_VS_VERB_HOMONYMY'
        if set([gt_pos, pr_pos]) == set(['Adj', 'Verb']):
            return 'P25_ROOT_ADJ_VS_VERB_HOMONYMY'
        if set([gt_pos, pr_pos]) == set(['Adv', 'Noun']):
            return 'P26_ROOT_ADV_VS_NOUN_HOMONYMY'
        if set([gt_pos, pr_pos]) == set(['Adj', 'Noun']):
            return 'P27_ROOT_ADJ_VS_NOUN_DIFFLEMMA'
        if set([gt_pos, pr_pos]) == set(['Num', 'Noun']):
            return 'P28_ROOT_NUM_VS_NOUN_HOMONYMY'
        return 'P24_ROOT_NOUN_VS_VERB_HOMONYMY'

    # Axis 4: Specifiers & Pronouns
    if surf_clean == 'bir':
        if 'Num' in gt_k:
            return 'P19_SPEC_CARDINAL_NUM_TO_INDEF_DET'
        else:
            return 'P20_SPEC_INDEF_DET_TO_CARDINAL_NUM'

    if ('Pers' in gt_k and 'Demons' in pr_k) or ('Demons' in gt_k and 'Pers' in pr_k):
        return 'P21_SPEC_PRON_PERS_VS_DEMONS'

    if set([gt_pos, pr_pos]) in [set(['Pron', 'Det']), set(['Adj', 'Det'])]:
        if micro['has_trailing_comma']:
            return 'P17_SPEC_PRON_TO_DET_WITH_COMMA'
        if gt_pos in ('Pron', 'Adj'):
            return 'P16_SPEC_PRON_TO_DET_BEFORE_NON_NOUN'
        else:
            return 'P18_SPEC_DET_TO_PRON_BEFORE_NOUN'

    # Axis 1: Case Syncretism
    if ('P3pl' in diff and 'P3sg' in diff) or ('A3pl' in diff and 'P3pl' in diff) or ('ları' in diff) or ('leri' in diff):
        return 'P06_CASE_PLURAL_POSSESSIVE_AMBIGUITY'
    if ('Acc' in diff and 'P3' in diff) or ('P3' in diff and 'Acc' in diff):
        if 'Acc' in gt_k:
            if right_pred and 'Participle' in right_pred['type']:
                return 'P01_CASE_ACC_TO_P3SG_PARTICIPLE'
            else:
                return 'P03_CASE_ACC_TO_P3SG_BARE_TRANSITIVE'
        else:
            return 'P02_CASE_P3SG_TO_ACC_GENITIVE'
    if ('P2sg' in diff and 'Gen' in diff) or ('Gen' in diff and 'P2sg' in diff):
        if 'P2sg' in gt_k:
            return 'P04_CASE_P2SG_TO_GEN'
        else:
            return 'P05_CASE_GEN_TO_P2SG'

    # Axis 3: Clausal Derivations
    if 'Part' in gt_k or 'Part' in pr_k:
        if 'Part' in gt_k:
            return 'P12_CLAUSAL_PARTICIPLE_TO_FINITE_PRENOMINAL'
        else:
            return 'P13_CLAUSAL_FINITE_TO_PARTICIPLE_FINAL'
    if 'Inf' in diff and 'Imp' in diff:
        if 'Inf' in gt_k:
            return 'P14_CLAUSAL_VERBAL_NOUN_TO_IMP'
        else:
            return 'P15_CLAUSAL_IMP_TO_VERBAL_NOUN'

    # Axis 2: Category Functional Shift
    if set([gt_pos, pr_pos]) == set(['Adj', 'Adv']):
        if gt_pos == 'Adv':
            return 'P07_SHIFT_ADJ_TO_ADV_PREVERBAL'
        else:
            return 'P08_SHIFT_ADV_TO_ADJ_PRENOMINAL'
    if set([gt_pos, pr_pos]) == set(['Noun', 'Adj']):
        if gt_pos == 'Adj':
            return 'P09_SHIFT_NOUN_TO_ADJ_ATTRIBUTIVE'
        else:
            return 'P10_SHIFT_ADJ_TO_NOUN_SUBSTANTIVIZED'
    if set([gt_pos, pr_pos]) == set(['Noun', 'Adv']):
        return 'P11_SHIFT_TEMPORAL_NOUN_VS_ADV'

    return 'UNCLASSIFIED'

def build_correct_token_index():
    print("Building Inverted Index of correct benchmark tokens for positive controls...")
    correct_by_lemma_tag = defaultdict(list)
    correct_by_tag = defaultdict(list)
    total_indexed = 0

    for fp in BENCHMARK_FILES:
        if not os.path.exists(fp):
            continue
        with open(fp) as f:
            d = json.load(f)
        for s in d['sentences']:
            tokens = s['tokens']
            for i, tok in enumerate(tokens):
                if not tok.get('is_ambiguous') or not tok.get('trained_matches_gemini'):
                    continue
                key = tok['gemini_selected_key']
                root_part = key.split(']')[0][1:] if ']' in key else key
                lemma = root_part.split(':')[0]
                morph = key.split('] ')[1] if '] ' in key else key
                tag = morph.split(':', 1)[1] if ':' in morph else morph
                
                left_c = ' '.join(t['surface'] for t in tokens[max(0, i-3):i])
                right_c = ' '.join(t['surface'] for t in tokens[i+1:min(len(tokens), i+4)])
                
                item = {
                    'surface': tok['surface'],
                    'key': key,
                    'lemma': lemma,
                    'tag': tag,
                    'sentence': s['text'],
                    'left_context': left_c,
                    'right_context': right_c
                }
                correct_by_lemma_tag[(lemma, tag)].append(item)
                correct_by_tag[tag].append(item)
                total_indexed += 1

    print(f"Indexed {total_indexed:,} correct ambiguous tokens.")
    return correct_by_lemma_tag, correct_by_tag

def sample_positive_controls(error_items, correct_by_lemma_tag, correct_by_tag, max_samples=2):
    """
    Samples 2 sentences where the model was correct on the competing analysis.
    """
    pos_samples = []
    seen_sentences = set()

    for err in error_items:
        # What was the predicted (competing) key?
        pred_key = err['morphology']['pred_key']
        pred_lemma = err['morphology']['pred_lemma']
        morph = pred_key.split('] ')[1] if '] ' in pred_key else pred_key
        pred_tag = morph.split(':', 1)[1] if ':' in morph else morph

        # 1. Exact match on (pred_lemma, pred_tag)
        candidates = correct_by_lemma_tag.get((pred_lemma, pred_tag), [])
        for c in candidates:
            if c['sentence'] not in seen_sentences:
                seen_sentences.add(c['sentence'])
                pos_samples.append({
                    'surface': c['surface'],
                    'correct_key': c['key'],
                    'sentence': c['sentence'],
                    'left_context': c['left_context'],
                    'right_context': c['right_context'],
                    'match_type': 'exact_lemma_and_tag'
                })
                if len(pos_samples) >= max_samples:
                    return pos_samples

        # 2. Sibling match on pred_tag
        sibling_candidates = correct_by_tag.get(pred_tag, [])
        for c in sibling_candidates:
            if c['sentence'] not in seen_sentences:
                seen_sentences.add(c['sentence'])
                pos_samples.append({
                    'surface': c['surface'],
                    'correct_key': c['key'],
                    'sentence': c['sentence'],
                    'left_context': c['left_context'],
                    'right_context': c['right_context'],
                    'match_type': 'sibling_tag'
                })
                if len(pos_samples) >= max_samples:
                    return pos_samples

    return pos_samples

def main():
    print(f"Loading profiled error tokens from: {INPUT_TOKENS_JSONL}")
    with open(INPUT_TOKENS_JSONL, encoding='utf-8') as f:
        tokens = [json.loads(line) for line in f]

    total_tokens = len(tokens)
    print(f"Loaded {total_tokens:,} tokens.")

    # Inverted index for positive controls
    correct_by_lemma_tag, correct_by_tag = build_correct_token_index()

    # Partition into 36 patterns
    pattern_buckets = defaultdict(list)
    unclassified_tokens = []

    for t in tokens:
        pid = classify_token_to_pattern(t)
        if pid == 'UNCLASSIFIED':
            unclassified_tokens.append(t)
        else:
            pattern_buckets[pid].append(t)

    classified_count = total_tokens - len(unclassified_tokens)
    coverage_pct = classified_count / total_tokens * 100.0

    print(f"\nClassification Complete:")
    print(f"  Classified into 36 Master Patterns: {classified_count:,} ({coverage_pct:.2f}%)")
    print(f"  Unclassified Tail: {len(unclassified_tokens):,} ({len(unclassified_tokens)/total_tokens*100:.2f}%)")

    # Build canonical pattern bundles
    master_bundles = []
    
    # Sort patterns by error volume descending
    sorted_pids = sorted(MASTER_PATTERNS_META.keys(), key=lambda pid: len(pattern_buckets[pid]), reverse=True)

    for pid in sorted_pids:
        items = pattern_buckets[pid]
        meta = MASTER_PATTERNS_META[pid]
        count = len(items)
        share_pct = count / total_tokens * 100.0
        words = Counter(x['surface'] for x in items)

        # 3 diverse error exemplars
        error_exemplars = []
        seen_words = set()
        for x in items:
            w = x['surface']
            if w not in seen_words or len(error_exemplars) < 3:
                seen_words.add(w)
                error_exemplars.append({
                    'surface': x['surface'],
                    'gt_analysis': x['morphology']['gt_key'],
                    'pred_analysis': x['morphology']['pred_key'],
                    'sentence': x['sentence_snippet']['full_text'],
                    'left_context': x['sentence_snippet']['left'],
                    'right_context': x['sentence_snippet']['right'],
                    'right_anchor': x['syntactic_anchors']['right_predicate'],
                    'left_anchor': x['syntactic_anchors']['left_genitive']
                })
            if len(error_exemplars) >= 3:
                break

        # 2 positive control exemplars
        pos_controls = sample_positive_controls(items, correct_by_lemma_tag, correct_by_tag, max_samples=2)

        bundle = {
            'pattern_id': pid,
            'canonical_name': meta['name'],
            'macro_axis': meta['axis'],
            'direction': meta['direction'],
            'description': meta['desc'],
            'error_count': count,
            'share_pct': share_pct,
            'unique_words_count': len(words),
            'top_words': words.most_common(10),
            'error_exemplars': error_exemplars,
            'positive_controls': pos_controls
        }
        master_bundles.append(bundle)

    # Save to JSON
    output_payload = {
        'total_tokens_evaluated': total_tokens,
        'classified_tokens_count': classified_count,
        'coverage_percentage': coverage_pct,
        'unclassified_count': len(unclassified_tokens),
        'master_patterns_count': len(master_bundles),
        'patterns': master_bundles
    }

    print(f"\nWriting 36 Canonical Pattern Bundles to: {OUT_CANONICAL_JSON}")
    with open(OUT_CANONICAL_JSON, 'w', encoding='utf-8') as f:
        json.dump(output_payload, f, ensure_ascii=False, indent=2)

    # Print summary table
    print("\n=========================================================================================================")
    print(f"{'ID':<38} | {'Errors':<7} | {'Share':<6} | {'Macro-Axis':<30}")
    print("---------------------------------------------------------------------------------------------------------")
    for b in master_bundles:
        print(f"{b['pattern_id']:<38} | {b['error_count']:5d}   | {b['share_pct']:5.2f}% | {b['macro_axis']:<30}")
    print("=========================================================================================================\n")

    summary_payload = {
        'total_tokens': total_tokens,
        'classified_tokens': classified_count,
        'coverage_percentage': coverage_pct,
        'axis_breakdown': Counter(b['macro_axis'] for b in master_bundles for _ in range(b['error_count']))
    }
    with open(OUT_SUMMARY_JSON, 'w', encoding='utf-8') as f:
        json.dump(summary_payload, f, ensure_ascii=False, indent=2)
    print(f"Summary saved to: {OUT_SUMMARY_JSON}")

if __name__ == "__main__":
    main()
