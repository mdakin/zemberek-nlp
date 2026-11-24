#!/usr/bin/env python3
"""
profile_error_patterns.py - Stage 1: Enriched Contextual Pattern Profiler for Turkish Ambiguity Errors

Processes the 3,583 extracted error sentences (6,387 errors) and extracts an enriched 5-tuple
context vector for each error instance:
  < Morphology, MicroContext(w_{i-1}, w_{i+1}), LeftAnchor, RightAnchor, Orthography >

Outputs:
  - data/ambiguity/error_profiled_tokens.jsonl
  - data/ambiguity/stage1_pattern_clusters.json
"""

import json
import os
import re
from collections import defaultdict, Counter

INPUT_JSONL = "data/ambiguity/golden_unresolved_error_sentences.jsonl"
OUT_TOKENS_JSONL = "data/ambiguity/error_profiled_tokens.jsonl"
OUT_CLUSTERS_JSON = "data/ambiguity/stage1_pattern_clusters.json"

# Circumflex pairs (vowel normalization)
CIRCUMFLEX_MAP = {
    'â': 'a', 'î': 'i', 'û': 'u',
    'Â': 'A', 'Î': 'I', 'Û': 'U'
}

def normalize_circumflex(text):
    for c, repl in CIRCUMFLEX_MAP.items():
        text = text.replace(c, repl)
    return text.lower()

# Known dictionary / normalization twins
LEXICON_TWIN_PAIRS = {
    frozenset(['tahminî', 'tahmin']),
    frozenset(['resmî', 'resmi']),
    frozenset(['tarihî', 'tarihi']),
    frozenset(['ilmî', 'ilmi']),
    frozenset(['millî', 'milli']),
    frozenset(['dinî', 'dini']),
    frozenset(['yüzde', 'yüz']),
    frozenset(['üzeri', 'üzere']),
}

# Function word category disputes
FUNCTION_WORD_DISPUTES = {
    frozenset(['ile:Conj', 'ile:Postp']),
    frozenset(['ise:Adv', 'imek:Verb']),
    frozenset(['değil:Verb', 'değil:Conj']),
    frozenset(['gibi:Postp', 'gibi:Conj']),
    frozenset(['diye:Postp', 'demek:Verb']),
    frozenset(['yani:Conj', 'yani:Adv']),
    frozenset(['önce:Postp', 'önce:Adv']),
    frozenset(['sonra:Postp', 'sonra:Adv']),
    frozenset(['birlikte:Adv', 'birlikte:Postp']),
}

def parse_analysis(key):
    """
    Parses a Zemberek canonical key into an Enriched Morphosyntactic Tag (EMST).
    Key format: [root:POS,SecondaryPOS] root:POS+affixes|Deriv→NewPOS+affixes
    """
    if not key or key in ('BOS', 'EOS'):
        return {
            'pos': key,
            'derived_pos': key,
            'emst': key,
            'lemma': key,
            'is_verb': False,
            'is_genitive': False,
            'is_participle': False,
            'is_verbal_noun': False,
            'is_finite_verb': False
        }

    root_part = key.split(']')[0][1:] if ']' in key else key
    morph_part = key.split('] ')[1] if '] ' in key else key
    lemma = root_part.split(':')[0]
    pos = root_part.split(':')[1].split(',')[0] if ':' in root_part else 'Unknown'

    last_group = morph_part.split('→')[-1] if '→' in morph_part else morph_part
    derived_pos = last_group.split(':')[0].split('+')[0].split('|')[0] if ':' in last_group else last_group.split('+')[0]
    if not derived_pos:
        derived_pos = pos

    is_genitive = ('+Gen' in last_group or ':Gen' in last_group)
    is_participle = ('Part→' in morph_part or 'Part' in last_group)
    is_verbal_noun = ('Inf1→' in morph_part or 'Inf2→' in morph_part or 'Inf3→' in morph_part or 'Inf' in last_group)
    is_converb = ('→Adv' in morph_part)
    is_finite_verb = (derived_pos == 'Verb' and not is_participle and not is_verbal_noun and not is_converb)

    # Build EMST representation
    if derived_pos == 'Noun':
        case = 'Nom'
        for c in ['Acc', 'Dat', 'Loc', 'Abl', 'Gen', 'Ins']:
            if f'+{c}' in last_group or f':{c}' in last_group:
                case = c
                break
        num = 'Plur' if ('A3pl' in last_group or 'lar' in last_group or 'ler' in last_group) else 'Sing'
        poss = 'None'
        for p in ['P1sg', 'P2sg', 'P3sg', 'P1pl', 'P2pl', 'P3pl']:
            if p in last_group:
                poss = p
                break
        emst = f"Noun[Case={case}, Num={num}, Poss={poss}]"

    elif is_participle:
        ptype = 'Participle'
        for pt in ['PastPart', 'FutPart', 'PresPart', 'NarrPart']:
            if pt in morph_part:
                ptype = pt
                break
        poss = 'None'
        for p in ['P1sg', 'P2sg', 'P3sg', 'P1pl', 'P2pl', 'P3pl']:
            if p in last_group or p in morph_part:
                poss = p
                break
        case = 'Nom'
        for c in ['Acc', 'Dat', 'Loc', 'Abl', 'Gen', 'Ins']:
            if f'+{c}' in last_group:
                case = c
                break
        emst = f"Verb[Form=Participle, Type={ptype}, Poss={poss}, Case={case}]"

    elif is_verbal_noun:
        case = 'Nom'
        for c in ['Acc', 'Dat', 'Loc', 'Abl', 'Gen', 'Ins']:
            if f'+{c}' in last_group:
                case = c
                break
        emst = f"Verb[Form=VerbalNoun, Case={case}]"

    elif is_converb:
        emst = "Verb[Form=Converb]"

    elif is_finite_verb:
        pol = 'Neg' if ('Neg' in last_group or '+me' in last_group or '+ma' in last_group or '+mez' in last_group or '+maz' in last_group) else 'Pos'
        tense = 'Aor' if ('Aor' in last_group or '+ar' in last_group or '+er' in last_group or '+ır' in last_group or '+ir' in last_group) else 'Finite'
        if '+di' in last_group or '+dı' in last_group or 'Past' in last_group:
            tense = 'Past'
        elif '+yor' in last_group or 'Prog' in last_group:
            tense = 'Prog'
        elif '+ecek' in last_group or '+acak' in last_group or 'Fut' in last_group:
            tense = 'Fut'
        elif '+mış' in last_group or '+miş' in last_group or 'Narr' in last_group:
            tense = 'Narr'
        elif 'Imp' in last_group:
            tense = 'Imp'
        emst = f"Verb[Form=Finite, Tense={tense}, Pol={pol}]"

    elif pos == 'Pron':
        subtype = root_part.split(',')[1] if ',' in root_part else 'General'
        case = 'Nom'
        for c in ['Acc', 'Dat', 'Loc', 'Abl', 'Gen', 'Ins']:
            if f'+{c}' in last_group:
                case = c
                break
        emst = f"Pron[Type={subtype}, Case={case}]"

    elif pos == 'Det':
        emst = "Det"
    elif pos == 'Adv':
        emst = "Adv"
    elif pos == 'Adj':
        emst = "Adj"
    elif pos == 'Conj':
        emst = "Conj"
    elif pos == 'Postp':
        emst = "Postp"
    else:
        emst = pos

    return {
        'pos': pos,
        'derived_pos': derived_pos,
        'emst': emst,
        'lemma': lemma,
        'is_verb': (pos == 'Verb' or derived_pos == 'Verb'),
        'is_genitive': is_genitive,
        'is_participle': is_participle,
        'is_verbal_noun': is_verbal_noun,
        'is_finite_verb': is_finite_verb
    }

def classify_category_nature(gt_lemma, pred_lemma, gt_key, pred_key):
    """
    Classifies error as 'LexiconArtifact' vs 'StructuralGrammar'.
    """
    # 1. Circumflex / spelling duplicates
    if normalize_circumflex(gt_lemma) == normalize_circumflex(pred_lemma) and gt_lemma.lower() != pred_lemma.lower():
        return "LexiconArtifact"

    # 2. Known dictionary twin pairs
    lemma_pair = frozenset([gt_lemma.lower(), pred_lemma.lower()])
    if lemma_pair in LEXICON_TWIN_PAIRS:
        return "LexiconArtifact"

    # 3. Function word category convention disputes
    gt_short = f"{gt_lemma}:{gt_key.split(']')[0].split(':')[1].split(',')[0]}" if ':' in gt_key else gt_lemma
    pred_short = f"{pred_lemma}:{pred_key.split(']')[0].split(':')[1].split(',')[0]}" if ':' in pred_key else pred_lemma
    disp_pair = frozenset([gt_short, pred_short])
    if disp_pair in FUNCTION_WORD_DISPUTES:
        return "LexiconArtifact"

    # 4. Postposition case frame artifact (e.g. kadar PCDat vs PCNom)
    if 'Postp' in gt_key and 'Postp' in pred_key and gt_lemma == pred_lemma:
        return "LexiconArtifact"

    return "StructuralGrammar"

def extract_syntactic_anchors(tokens, target_idx):
    """
    Finds nearest Left Genitive and Right Predicate anchors.
    """
    left_genitive = None
    # Scan leftward
    for dist, k in enumerate(range(target_idx - 1, -1, -1), 1):
        tok = tokens[k]
        surf = tok['surface']
        # Stop on clause breaks: period, colon, semicolon, or coordinating conjunctions
        if surf in ('.', '!', '?', ';', ':', '...', 'ama', 'fakat', 'ancak', 'lakin', 'oysa'):
            break
        # Parse ground truth analysis
        analysis = parse_analysis(tok.get('gemini_selected_key', ''))
        if analysis['is_genitive']:
            is_plur = ('Plur' in analysis['emst'] or 'lar' in tok.get('gemini_selected_key', '') or 'ler' in tok.get('gemini_selected_key', ''))
            left_genitive = {
                'distance': dist,
                'lemma': analysis['lemma'],
                'surface': surf,
                'is_plural': is_plur
            }
            break

    right_predicate = None
    # Scan rightward
    for dist, k in enumerate(range(target_idx + 1, len(tokens)), 1):
        tok = tokens[k]
        surf = tok['surface']
        if surf in ('.', '!', '?'):
            # If hit sentence end without finding predicate, stop
            break
        analysis = parse_analysis(tok.get('gemini_selected_key', ''))
        if analysis['is_participle']:
            ptype = 'Participle+Possessive' if 'Poss=P' in analysis['emst'] else 'Participle'
            right_predicate = {
                'distance': dist,
                'type': ptype,
                'lemma': analysis['lemma'],
                'surface': surf
            }
            break
        elif analysis['is_verbal_noun']:
            right_predicate = {
                'distance': dist,
                'type': 'VerbalNoun',
                'lemma': analysis['lemma'],
                'surface': surf
            }
            break
        elif analysis['is_finite_verb']:
            right_predicate = {
                'distance': dist,
                'type': 'FiniteVerb',
                'lemma': analysis['lemma'],
                'surface': surf
            }
            break
        elif surf in ('var', 'yok', 'değil') or ('Zero→Verb' in tok.get('gemini_selected_key', '')):
            right_predicate = {
                'distance': dist,
                'type': 'NominalPredicate',
                'lemma': surf,
                'surface': surf
            }
            break

    return left_genitive, right_predicate

def induce_pattern_signature(morph, micro, left_gen, right_pred, nature):
    """
    Induces a human-interpretable pattern signature for clustering.
    """
    if nature == "LexiconArtifact":
        return f"LEXICON_ARTIFACT | {morph['gt_lemma']} <--> {morph['pred_lemma']} ({morph['affix_diff']})"

    gt_pos = morph['gt_parsed']['derived_pos']
    pred_pos = morph['pred_parsed']['derived_pos']

    # 1. Accusative vs Possessive
    if ('Acc' in morph['affix_diff'] and 'P3' in morph['affix_diff']) or ('P3' in morph['affix_diff'] and 'Acc' in morph['affix_diff']):
        if right_pred and 'Participle' in right_pred['type']:
            return "SUBORDINATE_PARTICIPLE_OBJECT_VS_POSSESSIVE | [Noun:Acc] + [Participle]"
        elif left_gen and left_gen['is_plural'] and 'P3pl' in morph['affix_diff']:
            return "GENITIVE_PLURAL_AGREEMENT_VS_ACCUSATIVE | [Gen+Plur] ... [Noun:P3pl]"
        elif left_gen:
            return "GENITIVE_POSSESSIVE_HEAD_VS_ACCUSATIVE | [Gen] ... [Noun:P3sg]"
        else:
            return "BARE_ACCUSATIVE_OBJECT_VS_POSSESSIVE | [Noun:Acc <--> P3sg]"

    # 2. P2sg vs Genitive
    if ('P2sg' in morph['affix_diff'] and 'Gen' in morph['affix_diff']) or ('Gen' in morph['affix_diff'] and 'P2sg' in morph['affix_diff']):
        return "P2SG_POSSESSIVE_VS_GENITIVE | [Noun:P2sg <--> Gen]"

    # 3. Root Homonymy / Cross-POS Root Twins
    if morph['root_relation'] == 'DiffLemma':
        gt_l = morph['gt_lemma']
        pred_l = morph['pred_lemma']
        pos_tuple = tuple(sorted([gt_pos, pred_pos]))
        return f"ROOT_HOMONYMY_{pos_tuple[0].upper()}_{pos_tuple[1].upper()} | {gt_l} ({gt_pos}) <--> {pred_l} ({pred_pos})"

    # 4. Pronoun vs Determiner (bu, şu, o)
    if set([gt_pos, pred_pos]) == set(['Pron', 'Det']):
        if micro['has_trailing_comma']:
            return "PRONOUN_VS_DETERMINER | With Trailing Comma (Forces Pronoun)"
        elif micro['next_emst'].startswith('Verb') or micro['next_emst'].startswith('Postp'):
            return "PRONOUN_VS_DETERMINER | Before Verb/Postp (Forces Pronoun)"
        elif micro['next_emst'].startswith('Noun') or micro['next_emst'].startswith('Adj'):
            return "PRONOUN_VS_DETERMINER | Before Noun/Adj (Forces Determiner)"
        else:
            return "PRONOUN_VS_DETERMINER | General Context"

    # 5. Participle vs Finite Verb / Verbal Noun
    if morph['gt_parsed']['is_participle'] or morph['pred_parsed']['is_participle']:
        if micro['next_emst'].startswith('Noun'):
            return "PARTICIPLE_VS_FINITE_VERB | Pre-nominal Modifier (Forces Participle)"
        elif micro['position'] == 'Final' or micro['next_emst'] == 'EOS':
            return "PARTICIPLE_VS_FINITE_VERB | Sentence-Final (Forces Finite Verb)"
        else:
            return "PARTICIPLE_VS_FINITE_VERB | Medial Context"

    # 6. Verbal Noun vs Negative Imperative (-ma / -me)
    if ('Inf2' in morph['affix_diff'] and 'Imp' in morph['affix_diff']) or ('Imp' in morph['affix_diff'] and 'Inf2' in morph['affix_diff']):
        return "VERBAL_NOUN_VS_NEGATIVE_IMPERATIVE | (-ma/-me)"

    # 7. Category Conversion (Noun <-> Adj, Adj <-> Adv)
    if set([gt_pos, pred_pos]) == set(['Noun', 'Adj']):
        if micro['next_emst'].startswith('Noun'):
            return "NOUN_VS_ADJECTIVE | Pre-nominal Modifier (Attributive)"
        elif micro['position'] == 'Final' or micro['next_emst'] == 'EOS':
            return "NOUN_VS_ADJECTIVE | Predicate Position"
        else:
            return "NOUN_VS_ADJECTIVE | Argument vs Modifier"

    if set([gt_pos, pred_pos]) == set(['Adj', 'Adv']):
        if micro['next_emst'].startswith('Verb'):
            return "ADJECTIVE_VS_ADVERB | Pre-verbal Modifier (Forces Adverb)"
        elif micro['next_emst'].startswith('Noun'):
            return "ADJECTIVE_VS_ADVERB | Pre-nominal Modifier (Forces Adjective)"
        else:
            return "ADJECTIVE_VS_ADVERB | General Context"

    # 8. Intra-Noun Affix Clashes (Plural vs Singular Possessive e.g. -ları)
    if gt_pos == 'Noun' and pred_pos == 'Noun':
        return f"INTRA_NOUN_INFLECTION | {morph['affix_diff']}"

    return f"OTHER_MORPHOSYNTACTIC | {gt_pos} <--> {pred_pos} ({morph['affix_diff']})"

def main():
    print(f"Reading input from: {INPUT_JSONL}")
    if not os.path.exists(INPUT_JSONL):
        print(f"Error: {INPUT_JSONL} not found.")
        return

    with open(INPUT_JSONL, encoding='utf-8') as f:
        sentences = [json.loads(line) for line in f]

    print(f"Loaded {len(sentences):,} error sentences.")

    profiled_tokens = []
    pattern_clusters = defaultdict(list)
    nature_counter = Counter()

    for sent_rec in sentences:
        tokens = sent_rec['tokens']
        sent_id = sent_rec.get('sentence_id', 'unknown')
        dataset = sent_rec.get('dataset', 'unknown')
        sent_text = sent_rec.get('text', '')

        for err in sent_rec['errors']:
            idx = err['token_index']
            surface = err['surface']
            gt_key = err['ground_truth']
            pred_key = err['predicted']

            # Parse morphology
            gt_parsed = parse_analysis(gt_key)
            pred_parsed = parse_analysis(pred_key)
            gt_lemma = gt_parsed['lemma']
            pred_lemma = pred_parsed['lemma']
            root_relation = "SameLemma" if gt_lemma == pred_lemma else "DiffLemma"
            nature = classify_category_nature(gt_lemma, pred_lemma, gt_key, pred_key)
            nature_counter[nature] += 1

            gt_aff = gt_key.split('] ')[1] if '] ' in gt_key else gt_key
            pred_aff = pred_key.split('] ')[1] if '] ' in pred_key else pred_key
            gt_sub = gt_aff.split(':', 1)[1] if ':' in gt_aff else gt_aff
            pred_sub = pred_aff.split(':', 1)[1] if ':' in pred_aff else pred_aff
            affix_diff = f"{gt_sub} <--> {pred_sub}"

            morph_battleground = {
                'root_relation': root_relation,
                'nature': nature,
                'gt_lemma': gt_lemma,
                'pred_lemma': pred_lemma,
                'gt_key': gt_key,
                'pred_key': pred_key,
                'gt_parsed': gt_parsed,
                'pred_parsed': pred_parsed,
                'affix_diff': affix_diff,
                'candidate_count': err.get('candidate_count', 2)
            }

            # Micro-context window
            prev_tok = tokens[idx - 1] if idx > 0 else None
            next_tok = tokens[idx + 1] if idx < len(tokens) - 1 else None

            prev_parsed = parse_analysis(prev_tok.get('gemini_selected_key', '')) if prev_tok else parse_analysis('BOS')
            next_parsed = parse_analysis(next_tok.get('gemini_selected_key', '')) if next_tok else parse_analysis('EOS')

            # Orthography
            has_trailing_comma = (
                surface.endswith(',') or 
                (next_tok and next_tok['surface'] == ',')
            )
            is_capitalized = (bool(surface) and surface[0].isupper())
            if idx == 0:
                pos_slot = "Initial"
            elif idx == len(tokens) - 1 or (next_tok and next_tok['surface'] in ('.', '!', '?')):
                pos_slot = "Final"
            else:
                pos_slot = "Medial"

            micro_context = {
                'prev_word': prev_tok['surface'] if prev_tok else 'BOS',
                'prev_emst': prev_parsed['emst'],
                'next_word': next_tok['surface'] if next_tok else 'EOS',
                'next_emst': next_parsed['emst'],
                'has_trailing_comma': has_trailing_comma,
                'is_capitalized': is_capitalized,
                'position': pos_slot
            }

            # Syntactic anchors
            left_gen, right_pred = extract_syntactic_anchors(tokens, idx)
            syntactic_anchors = {
                'left_genitive': left_gen,
                'right_predicate': right_pred
            }

            # Pattern induction
            pattern_sig = induce_pattern_signature(
                morph_battleground, micro_context, left_gen, right_pred, nature
            )

            token_profile = {
                'dataset': dataset,
                'sentence_id': sent_id,
                'token_index': idx,
                'surface': surface,
                'pattern_signature': pattern_sig,
                'nature': nature,
                'morphology': {
                    'root_relation': root_relation,
                    'gt_lemma': gt_lemma,
                    'pred_lemma': pred_lemma,
                    'affix_diff': affix_diff,
                    'gt_key': gt_key,
                    'pred_key': pred_key
                },
                'micro_context': micro_context,
                'syntactic_anchors': syntactic_anchors,
                'sentence_snippet': {
                    'left': ' '.join(t['surface'] for t in tokens[max(0, idx-3):idx]),
                    'target': surface,
                    'right': ' '.join(t['surface'] for t in tokens[idx+1:min(len(tokens), idx+4)]),
                    'full_text': sent_text
                }
            }

            profiled_tokens.append(token_profile)

            # Add to cluster
            pattern_clusters[pattern_sig].append({
                'surface': surface,
                'gt': gt_key,
                'pred': pred_key,
                'sentence': sent_text,
                'prev_word': micro_context['prev_word'],
                'next_word': micro_context['next_word'],
                'left_gen': left_gen,
                'right_pred': right_pred
            })

    print(f"Total profiled error tokens: {len(profiled_tokens):,}")
    print(f"Unique induced pattern signatures: {len(pattern_clusters)}")
    print(f"Breakdown by Nature: StructuralGrammar={nature_counter['StructuralGrammar']:,} ({nature_counter['StructuralGrammar']/len(profiled_tokens)*100:.1f}%), LexiconArtifact={nature_counter['LexiconArtifact']:,} ({nature_counter['LexiconArtifact']/len(profiled_tokens)*100:.1f}%)")

    # Save profiled tokens JSONL
    print(f"Writing profiled tokens to: {OUT_TOKENS_JSONL}")
    with open(OUT_TOKENS_JSONL, 'w', encoding='utf-8') as f:
        for p in profiled_tokens:
            f.write(json.dumps(p, ensure_ascii=False) + '\n')

    # Build stage 1 clusters summary
    sorted_clusters = sorted(pattern_clusters.items(), key=lambda x: len(x[1]), reverse=True)
    cluster_summary = {
        'total_tokens_profiled': len(profiled_tokens),
        'total_clusters_count': len(sorted_clusters),
        'nature_distribution': {
            'StructuralGrammar': nature_counter['StructuralGrammar'],
            'LexiconArtifact': nature_counter['LexiconArtifact']
        },
        'top_clusters': []
    }

    for sig, instances in sorted_clusters[:50]:
        words = Counter(x['surface'] for x in instances)
        cluster_summary['top_clusters'].append({
            'pattern_signature': sig,
            'count': len(instances),
            'share_pct': len(instances) / len(profiled_tokens) * 100.0,
            'top_words': words.most_common(10),
            'exemplars': instances[:3]
        })

    print(f"Writing cluster summary to: {OUT_CLUSTERS_JSON}")
    with open(OUT_CLUSTERS_JSON, 'w', encoding='utf-8') as f:
        json.dump(cluster_summary, f, ensure_ascii=False, indent=2)

    print("\n=== TOP 20 INDUCED AMBIGUITY PATTERNS ===")
    for idx, c in enumerate(cluster_summary['top_clusters'][:20], 1):
        print(f"{idx:2d}. [{c['count']:4d} errors ({c['share_pct']:5.2f}%)]: {c['pattern_signature']}")
        top_w_str = ", ".join(f"{w} ({n})" for w, n in c['top_words'][:5])
        print(f"    Top words: {top_w_str}")

if __name__ == "__main__":
    main()
