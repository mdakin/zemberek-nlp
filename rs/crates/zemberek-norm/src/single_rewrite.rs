use alloc::string::String;
use alloc::vec::Vec;
use zemberek_core::TurkishAlphabet;
use zemberek_morph::{AnalyzerScratch, DynamicStemTrie};
use zemberek_pack::clean_lm::ln_f64;
use zemberek_spell::CompactStemEndingSpellChecker;
use zemberek_tok::{CasingShape, SpanToken};
use crate::candidate_gen::is_standalone_question_clitic;
use crate::knowledge::NormKnowledge;
use crate::morph_support::{
    format_proper_or_abbr_tdk, is_detached_num_suffix, is_detached_word_apostrophe_suffix,
    is_front_vowel, last_turkish_vowel, proper_noun_takes_apostrophe_tdk, strips_explicit_diacritic,
    strips_explicit_stem_diacritic, LatticeMorphSupport,
};
use crate::shorthand::is_curated_shorthand;
use crate::split_merge::{harmonize_question_clitic, QUESTION_TAILS};
use crate::types::{
    compute_weighted_edit_cost, count_diacritic_diffs, is_digits_or_punct_only, is_java_digit,
    take_first_chars, to_ascii, tr_capitalize, tr_lower, tr_upper, u16_len, SlotDrafts,
    SRC_APOSTROPHE_DROP, SRC_CLITIC_SPLIT, SRC_DEASCIIFIER, SRC_ELONGATION, SRC_FORMAL_MORPH,
    SRC_INFORMAL_MORPH, SRC_OOV_APOSTROPHE, SRC_PARALLEL_1TO1, SRC_SPELLCHECK, SRC_SUFFIX_RULE,
};

const OOV_PROPER_INFLECT_SUFS: &[&str] = &[
    "dayız", "deyiz", "tayız", "teyiz", "dayım", "deyim", "tayım", "teyim", "ndaki", "ndeki",
    "daki", "deki", "taki", "teki", "lılar", "liler", "lular", "lüler", "dır", "dir", "dur", "dür",
    "tır", "tir", "tur", "tür", "ydı", "ydi", "ydu", "ydü", "dan", "den", "tan", "ten", "da", "de",
    "ta", "te", "nın", "nin", "nun", "nün", "ın", "in", "un", "ün", "ya", "ye", "yı", "yi", "yu",
    "yü", "yla", "yle", "la", "le", "li", "lı", "lu", "lü", "lik", "lık", "luk", "lük", "ler",
    "lar", "di", "dı", "du", "dü", "ti", "tı", "tu", "tü", "a", "e", "ı", "i", "u", "ü",
];

const TDK_NO_APOSTROPHE_DERIV_PREFIXES: &[&str] = &[
    "lı", "li", "lu", "lü", "lık", "lik", "luk", "lük", "lar", "ler", "ca", "ce", "ça", "çe",
    "cı", "ci", "cu", "cü", "çı", "çi", "çu", "çü", "gil", "cığ", "ciğ", "cuğ", "cüğ", "çığ",
    "çiğ", "çuğ", "çüğ", "cug", "cüg", "cım", "cim", "cum", "cüm",
];

const HAPLOLOGY_STEMS: &[(&str, &str, &str)] = &[
    ("burda", "burda", "burada"),
    ("orda", "orda", "orada"),
    ("şurda", "surda", "şurada"),
    ("nerde", "nerde", "nerede"),
    ("nerden", "nerden", "nereden"),
    ("burdan", "burdan", "buradan"),
    ("ordan", "ordan", "oradan"),
    ("şurdan", "surdan", "şuradan"),
];

const INTERNAL_PROG_PATTERNS: &[(&str, &str)] = &[
    ("iyod", "iyord"),
    ("ıyod", "ıyord"),
    ("uyod", "uyord"),
    ("üyod", "üyord"),
    ("iod", "iyord"),
    ("ıod", "ıyord"),
    ("iyomuş", "iyormuş"),
    ("ıyomuş", "ıyormuş"),
    ("uyomuş", "uyormuş"),
    ("üyomuş", "üyormuş"),
    ("iyomus", "iyormuş"),
    ("iomuş", "iyormuş"),
    ("iomus", "iyormuş"),
    ("iyosa", "iyorsa"),
    ("ıyosa", "ıyorsa"),
    ("uyosa", "uyorsa"),
    ("üyosa", "üyorsa"),
];

const NEG_OPT_ENDINGS: &[&str] = &[
    "miyim", "mıyım", "muyum", "müyüm", "miyiz", "mıyız", "muyuz", "müyüz",
];

const HYPOCORISTIC_ENDINGS: &[&str] = &[
    "cım", "cim", "cum", "cüm", "çım", "çim", "çum", "çüm", "cm",
];

fn is_common_unit(s: &str) -> bool {
    matches!(
        s,
        "gb" | "mb"
            | "kb"
            | "tb"
            | "tl"
            | "usd"
            | "eur"
            | "mhz"
            | "ghz"
            | "mah"
            | "kg"
            | "gr"
            | "mg"
            | "cm"
            | "mm"
            | "km"
            | "ml"
            | "lt"
            | "dk"
            | "sn"
            | "saat"
            | "gün"
            | "ay"
            | "yıl"
            | "adet"
    )
}

fn is_uppercase_unit(s: &str) -> bool {
    matches!(
        s,
        "gb" | "mb" | "kb" | "tb" | "tl" | "usd" | "eur" | "mhz" | "ghz" | "hd" | "fps"
    )
}

fn is_real_agentive_cim_word(s: &str) -> bool {
    matches!(
        s,
        "öğrencim"
            | "takipçim"
            | "izleyicim"
            | "dinleyicim"
            | "okuyucum"
            | "yardımcım"
            | "temsilcim"
            | "sunucum"
            | "oyuncum"
            | "yöneticim"
            | "eğitimcim"
            | "işçim"
            | "bekçim"
            | "aşçım"
            | "avcım"
            | "yolcum"
            | "komşum"
            | "ciğim"
            | "cığım"
            | "cuğum"
            | "cüğüm"
            | "çiğim"
            | "çığım"
            | "çuğum"
            | "çüğüm"
    )
}

#[inline]
fn truecase_slot<'a>(
    slot: &SlotDrafts,
    tok: &SpanToken,
    norm_lower: &str,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) -> String {
    knowledge.truecase(
        norm_lower,
        tok.shape,
        &tok.surface,
        slot.cap_here,
        slot.sentence_all_caps,
        morph,
        0,
        dynamic,
        scratch,
    )
}

pub fn add_suffix_and_haplology_candidates<'a>(
    slot: &mut SlotDrafts,
    tok: &SpanToken,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) {
    let low = tok.lower.as_str();
    if u16_len(low) < 4 || tok.contains_digit() {
        return;
    }

    // 1. Haplology (`burda -> burada`, `nerden -> nereden`)
    for &(bad, bad_asc, good) in HAPLOLOGY_STEMS {
        let matched_tail = if let Some(rest) = low.strip_prefix(bad) {
            Some(rest)
        } else if tok.ascii.starts_with(bad_asc) {
            let bad_chars = bad.chars().count();
            let cut_b = low.char_indices().nth(bad_chars).map_or(low.len(), |(b, _)| b);
            Some(&low[cut_b..])
        } else {
            None
        };
        if let Some(tail) = matched_tail {
            let mut probe = String::with_capacity(good.len() + tail.len());
            probe.push_str(good);
            probe.push_str(tail);
            if let Some(realized) = morph.realize_valid_word_or_null(&probe, dynamic, scratch) {
                let surf = truecase_slot(slot, tok, &realized, knowledge, morph, dynamic, scratch);
                let cost = compute_weighted_edit_cost(low, &realized);
                let diffs = count_diacritic_diffs(low, &realized);
                slot.add_rule(surf, realized, SRC_SUFFIX_RULE, 0.92, cost, diffs);
            }
        }
    }

    // 2. Foreign x -> ks and w -> v adaptation
    if u16_len(low) >= 5
        && (low.contains('x') || low.contains('w'))
        && !is_mid_sentence_title(slot, tok)
    {
        let adapted = low.replace('x', "ks").replace('w', "v");
        if let Some(realized) = morph.realize_valid_word_or_null(&adapted, dynamic, scratch) {
            let surf = truecase_slot(slot, tok, &realized, knowledge, morph, dynamic, scratch);
            let cost = compute_weighted_edit_cost(low, &realized);
            let diffs = count_diacritic_diffs(&adapted, &realized);
            slot.add_rule(surf, realized, SRC_SUFFIX_RULE, 0.88, cost, diffs);
        }
    }

    // 3. Internal progressive (`geliyomuş -> geliyormuş`)
    if u16_len(low) >= 6 {
        if let Some(o_byte) = low.find('o') {
            if u16_len(&low[..o_byte]) >= 2 {
                if let Some(prog_cand) = expand_internal_informal_progressive(low) {
                    if let Some(realized) =
                        morph.realize_valid_word_or_null(&prog_cand, dynamic, scratch)
                    {
                        if !strips_explicit_stem_diacritic(low, &realized) {
                            let surf = truecase_slot(
                                slot, tok, &realized, knowledge, morph, dynamic, scratch,
                            );
                            let cost = compute_weighted_edit_cost(low, &realized);
                            let diffs = count_diacritic_diffs(&prog_cand, &realized);
                            slot.add_rule(
                                surf,
                                realized,
                                SRC_SUFFIX_RULE | SRC_INFORMAL_MORPH,
                                0.90,
                                cost,
                                diffs,
                            );
                        }
                    }
                }
            }
        }
    }

    // 4. Soft-ğ dropped double high vowels (`oynadıım -> oynadığım`)
    let chars: Vec<(usize, char)> = low.char_indices().collect();
    if chars.len() >= 5 {
        for k in 2..(chars.len() - 2) {
            let v = chars[k].1;
            if v == chars[k + 1].1 && matches!(v, 'ı' | 'i' | 'u' | 'ü') {
                let cut_b = chars[k + 1].0;
                let mut g_cand = String::with_capacity(low.len() + 2);
                g_cand.push_str(&low[..cut_b]);
                g_cand.push('ğ');
                g_cand.push_str(&low[cut_b..]);
                if let Some(realized) = morph.realize_valid_word_or_null(&g_cand, dynamic, scratch)
                {
                    if realized.contains('ğ') && !strips_explicit_stem_diacritic(low, &realized) {
                        let surf =
                            truecase_slot(slot, tok, &realized, knowledge, morph, dynamic, scratch);
                        let cost = compute_weighted_edit_cost(low, &realized);
                        let diffs = count_diacritic_diffs(&g_cand, &realized);
                        slot.add_rule(
                            surf,
                            realized,
                            SRC_SUFFIX_RULE | SRC_INFORMAL_MORPH,
                            0.88,
                            cost,
                            diffs,
                        );
                    }
                }
                break;
            }
        }
    }

    // 5. Spoken 1sg/1pl negative optative & progressive (`almiyim -> almayayım`)
    if chars.len() >= 6 {
        for &ending in NEG_OPT_ENDINGS {
            if let Some(v_stem) = low.strip_suffix(ending) {
                let v_stem_chars = u16_len(v_stem);
                if v_stem_chars >= 2 && morph.is_verb_root_candidate(v_stem, dynamic, scratch) {
                    let formal_tails: &[&str] = if ending.ends_with('z') {
                        &["mayalım", "meyelim"]
                    } else {
                        &["mayayım", "meyeyim", "mıyorum", "miyorum", "muyorum", "müyorum"]
                    };
                    for &ft in formal_tails {
                        let mut probe = String::with_capacity(v_stem.len() + ft.len());
                        probe.push_str(v_stem);
                        probe.push_str(ft);
                        if let Some(realized) =
                            morph.realize_valid_word_or_null(&probe, dynamic, scratch)
                        {
                            if !strips_explicit_stem_diacritic(low, &realized) {
                                let stem_check_len = v_stem_chars.min(u16_len(&realized));
                                let surf = truecase_slot(
                                    slot, tok, &realized, knowledge, morph, dynamic, scratch,
                                );
                                let cost = compute_weighted_edit_cost(low, &realized);
                                let diffs = count_diacritic_diffs(
                                    v_stem,
                                    take_first_chars(&realized, stem_check_len),
                                );
                                slot.add_rule(
                                    surf,
                                    realized,
                                    SRC_SUFFIX_RULE | SRC_INFORMAL_MORPH,
                                    if ft.ends_with("yorum") { 0.80 } else { 0.90 },
                                    cost,
                                    diffs,
                                );
                            }
                        }
                    }
                }
                break;
            }
        }
    }

    // 6. Hypocoristic diminutives (`annecim -> anneciğim`)
    if chars.len() >= 5 {
        if let Some((stem, hyp)) = expand_hypocoristic(low, knowledge, morph, dynamic, scratch) {
            let force_title = morph.is_strict_proper_or_abbr_only(&stem, dynamic, scratch)
                || knowledge.is_known_proper_or_acronym_stem(&stem);
            let surf = if force_title && !slot.sentence_all_caps {
                tr_capitalize(&hyp)
            } else {
                truecase_slot(slot, tok, &hyp, knowledge, morph, dynamic, scratch)
            };
            let cost = compute_weighted_edit_cost(low, &hyp);
            let diffs = count_diacritic_diffs(low, &hyp);
            slot.add_rule(surf, hyp, SRC_SUFFIX_RULE, 0.88, cost, diffs);
        }
    }

    // 7. Mined suffix rules
    let last_ch = chars.last().unwrap().1;
    let mut added = 0usize;
    for sr in knowledge.view.suffix_rules_for_last_char(last_ch) {
        let sr_chars = u16_len(sr.raw_suffix);
        if chars.len() <= sr_chars + 1 || !low.ends_with(sr.raw_suffix) {
            continue;
        }
        let stem = &low[..low.len() - sr.raw_suffix.len()];
        let mut probe = String::with_capacity(stem.len() + sr.norm_suffix.len());
        probe.push_str(stem);
        probe.push_str(sr.norm_suffix);
        if let Some(realized) = morph.realize_valid_word_or_null(&probe, dynamic, scratch) {
            if !strips_explicit_stem_diacritic(low, &realized) {
                let surf = truecase_slot(slot, tok, &realized, knowledge, morph, dynamic, scratch);
                let log_cnt = ln_f64(1.0 + (sr.support as f64)) as f32;
                let cost = compute_weighted_edit_cost(low, &realized);
                let diffs = count_diacritic_diffs(low, &realized);
                slot.add(
                    surf,
                    realized,
                    SRC_SUFFIX_RULE,
                    0.0,
                    log_cnt,
                    sr.precision,
                    cost,
                    diffs,
                );
                added += 1;
                if added >= 4 {
                    break;
                }
            }
        }
    }
}

fn expand_internal_informal_progressive(low: &str) -> Option<String> {
    for &(pat, rep) in INTERNAL_PROG_PATTERNS {
        if let Some(idx) = low.rfind(pat) {
            if u16_len(&low[..idx]) >= 2 {
                let mut out = String::with_capacity(low.len() + 2);
                out.push_str(&low[..idx]);
                out.push_str(rep);
                out.push_str(&low[idx + pat.len()..]);
                return Some(out);
            }
        }
    }
    None
}

fn expand_hypocoristic<'a>(
    low: &str,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) -> Option<(String, String)> {
    if is_real_agentive_cim_word(low) || knowledge.clean_lm.unigram_count(low) >= 25 {
        return None;
    }
    let low_chars = u16_len(low);
    for &end in HYPOCORISTIC_ENDINGS {
        let end_chars = u16_len(end);
        if !low.ends_with(end) || low_chars < end_chars + 3 {
            continue;
        }
        let raw_stem = &low[..low.len() - end.len()];
        let stem = morph
            .realize_valid_word_or_null(raw_stem, dynamic, scratch)
            .unwrap_or_else(|| String::from(raw_stem));
        let last_v = last_turkish_vowel(&stem);
        if last_v == '\0' {
            return None;
        }
        let last_ch = stem.chars().next_back().unwrap_or('a');
        let cons = if TurkishAlphabet::is_voiceless_consonant(last_ch) {
            'ç'
        } else {
            'c'
        };
        let suf_tail = match last_v {
            'e' | 'i' => "iğim",
            'ö' | 'ü' => "üğüm",
            'o' | 'u' => "uğum",
            _ => "ığım",
        };
        let mut hyp = String::with_capacity(stem.len() + 8);
        hyp.push_str(&stem);
        hyp.push(cons);
        hyp.push_str(suf_tail);
        return Some((stem, hyp));
    }
    None
}

// =========================================================================
// TDK Proper Nouns, Acronyms, Numerals & Apostrophe Removal
// =========================================================================

pub fn add_proper_acronym_and_numeral_candidates<'a>(
    slot: &mut SlotDrafts,
    tok: &SpanToken,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) {
    let low = tok.lower.as_str();
    if let Some(ap_byte) = low.find('\'') {
        if ap_byte > 0 && ap_byte + 1 < low.len() {
            add_apostrophe_cleanup_candidates(
                slot,
                tok,
                &low[..ap_byte],
                &low[ap_byte + 1..],
                knowledge,
                morph,
                dynamic,
                scratch,
            );
        }
        return;
    }
    if tok.contains_digit() {
        add_alphanumeric_token_candidates(slot, tok, low, morph, dynamic, scratch);
        return;
    }
    add_lexicon_proper_and_abbr_candidates(slot, tok, low, morph, dynamic, scratch);
    add_oov_proper_or_acronym_candidates(slot, tok, low, knowledge, morph, dynamic, scratch);
}

#[allow(clippy::too_many_arguments)]
fn add_apostrophe_cleanup_candidates<'a>(
    slot: &mut SlotDrafts,
    tok: &SpanToken,
    stem: &str,
    tail: &str,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) {
    if is_digits_or_punct_only(stem) {
        return;
    }
    let mut joined = String::with_capacity(stem.len() + tail.len());
    joined.push_str(stem);
    joined.push_str(tail);
    let is_acronym = knowledge.is_known_acronym_stem(stem);

    let wa = morph.strict_morph.analyze_word(&joined, dynamic, scratch);
    for a in &wa.analyses {
        if !a.is_known_lexical(&morph.strict_morph.graph) {
            continue;
        }
        let spos = a.item.secondary_pos(&morph.strict_morph.graph);
        if spos == zemberek_core::SecondaryPos::ProperNoun
            && !proper_noun_takes_apostrophe_tdk(a, &morph.strict_morph.graph, &morph.strict_morph.fst)
            && !is_acronym
            && !(tok.shape == CasingShape::Upper && !slot.sentence_all_caps)
        {
            if let Some(fmt) = format_proper_or_abbr_tdk(
                a,
                None,
                &morph.strict_morph.graph,
                &morph.strict_morph.fst,
            ) {
                if !fmt.contains('\'') {
                    let fl = tr_lower(&fmt);
                    let diffs = count_diacritic_diffs(&joined, &fl);
                    slot.add_rule(
                        fmt,
                        fl,
                        SRC_APOSTROPHE_DROP | SRC_FORMAL_MORPH,
                        0.92,
                        0.15,
                        diffs,
                    );
                }
            }
        }
    }

    if is_acronym {
        return;
    }
    if morph.has_regular_strict_analysis(&joined, dynamic, scratch) {
        let common_lower = is_predominantly_lower_stem(stem, 5, knowledge);
        if common_lower || tok.shape == CasingShape::Lower {
            let surf = if slot.cap_here {
                tr_capitalize(&joined)
            } else {
                joined.clone()
            };
            slot.add_rule(
                surf,
                joined,
                SRC_APOSTROPHE_DROP | SRC_FORMAL_MORPH,
                if common_lower { 0.85 } else { 0.55 },
                0.20,
                0,
            );
        }
    } else if TDK_NO_APOSTROPHE_DERIV_PREFIXES
        .iter()
        .any(|&p| tail.starts_with(p))
    {
        let assim = assimilate_derivational_tail(stem, tail);
        let mut cand_low = String::with_capacity(stem.len() + assim.len());
        cand_low.push_str(stem);
        cand_low.push_str(&assim);
        let keep_lower_unless_start = is_predominantly_lower_stem(stem, 4, knowledge)
            || (tok.shape == CasingShape::Lower
                && !knowledge.is_known_proper_or_acronym_stem(stem));
        let surf = if keep_lower_unless_start {
            if slot.cap_here {
                tr_capitalize(&cand_low)
            } else {
                cand_low.clone()
            }
        } else {
            tr_capitalize(&cand_low)
        };
        let diffs = count_diacritic_diffs(&joined, &cand_low);
        slot.add_rule(surf, cand_low, SRC_APOSTROPHE_DROP, 0.86, 0.18, diffs);
    }
}

fn is_predominantly_lower_stem(stem: &str, min_lower: u32, knowledge: &NormKnowledge<'_>) -> bool {
    let Some(cc) = knowledge.lookup_casing(stem) else {
        return false;
    };
    cc.lower >= min_lower && cc.lower > (cc.title + cc.upper + cc.apostrophe_stem) * 2
}

fn add_alphanumeric_token_candidates<'a>(
    slot: &mut SlotDrafts,
    _tok: &SpanToken,
    low: &str,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) {
    let mut d_end_byte = 0usize;
    for (b, c) in low.char_indices() {
        if is_java_digit(c) {
            d_end_byte = b + c.len_utf8();
        } else {
            break;
        }
    }
    if d_end_byte > 0 && d_end_byte < low.len() {
        let num_part = &low[..d_end_byte];
        let rest = &low[d_end_byte..];
        if let Some(after_dot) = rest.strip_prefix('.') {
            if !after_dot.is_empty() {
                if let Some(ord) = build_ordinal_inflected(num_part, after_dot) {
                    slot.add_rule(ord.clone(), ord, SRC_OOV_APOSTROPHE, 0.90, 0.2, 0);
                }
                return;
            }
        }
        if is_common_unit(rest) {
            let unit_norm = if is_uppercase_unit(rest) {
                tr_upper(rest)
            } else if rest == "mah" {
                String::from("mAh")
            } else {
                String::from(rest)
            };
            let mut split_unit = String::with_capacity(num_part.len() + 1 + unit_norm.len());
            split_unit.push_str(num_part);
            split_unit.push(' ');
            split_unit.push_str(&unit_norm);
            let split_low = tr_lower(&split_unit);
            slot.add_rule(split_unit, split_low, SRC_CLITIC_SPLIT, 0.90, 0.2, 0);
            return;
        }
        if is_detached_num_suffix(rest) {
            let mut joined = String::with_capacity(num_part.len() + 1 + rest.len());
            joined.push_str(num_part);
            joined.push('\'');
            joined.push_str(rest);
            slot.add_rule(joined.clone(), joined, SRC_OOV_APOSTROPHE, 0.88, 0.2, 0);
            return;
        }
        if rest.len() >= 2 {
            let b0 = rest.as_bytes()[0];
            if matches!(b0, b's' | b'g' | b'k' | b'd')
                && is_detached_word_apostrophe_suffix(&rest[1..])
            {
                let mut joined_surf = String::with_capacity(num_part.len() + rest.len() + 1);
                joined_surf.push_str(num_part);
                joined_surf.push(if b0 == b's' {
                    's'
                } else {
                    (b0 - 32) as char
                });
                joined_surf.push('\'');
                joined_surf.push_str(&rest[1..]);
                let joined_low = tr_lower(&joined_surf);
                slot.add_rule(joined_surf, joined_low, SRC_OOV_APOSTROPHE, 0.86, 0.2, 0);
                return;
            }
        }
        if u16_len(rest) >= 2 {
            if let Some(real_word) = morph.realize_valid_word_or_null(rest, dynamic, scratch) {
                let mut split_nw = String::with_capacity(num_part.len() + 1 + real_word.len());
                split_nw.push_str(num_part);
                split_nw.push(' ');
                split_nw.push_str(&real_word);
                let diffs = count_diacritic_diffs(rest, &real_word);
                slot.add_rule(split_nw.clone(), split_nw, SRC_CLITIC_SPLIT, 0.85, 0.2, diffs);
            }
        }
    } else if d_end_byte == 0 {
        // Alphanumeric brand stems with glued case suffixes (`tv8e -> TV8'e`)
        let mut last_digit_char_idx: Option<(usize, usize)> = None;
        let mut total_chars = 0usize;
        for (b, c) in low.char_indices() {
            if is_java_digit(c) {
                last_digit_char_idx = Some((total_chars, b + c.len_utf8()));
            }
            total_chars += c.len_utf16();
        }
        if let Some((d_ch_idx, cut_b)) = last_digit_char_idx {
            if (1..=5).contains(&d_ch_idx) && d_ch_idx + 1 < total_chars {
                let stem = &low[..cut_b];
                let suf = &low[cut_b..];
                if is_detached_num_suffix(suf) {
                    let mut surf = tr_upper(stem);
                    surf.push('\'');
                    surf.push_str(suf);
                    let mut norm_low = String::with_capacity(stem.len() + 1 + suf.len());
                    norm_low.push_str(stem);
                    norm_low.push('\'');
                    norm_low.push_str(suf);
                    slot.add_rule(surf, norm_low, SRC_OOV_APOSTROPHE, 0.86, 0.2, 0);
                }
            }
        }
    }
}

fn add_lexicon_proper_and_abbr_candidates<'a>(
    slot: &mut SlotDrafts,
    tok: &SpanToken,
    low: &str,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) {
    let wa = morph.strict_morph.analyze_word(low, dynamic, scratch);
    for a in &wa.analyses {
        if !a.is_known_lexical(&morph.strict_morph.graph)
            || !a.morphemes.iter().skip(1).any(|m| !m.surface.is_empty())
        {
            continue;
        }
        let spos = a.item.secondary_pos(&morph.strict_morph.graph);
        if matches!(
            spos,
            zemberek_core::SecondaryPos::ProperNoun | zemberek_core::SecondaryPos::Abbreviation
        ) {
            if let Some(formatted) = format_proper_or_abbr_tdk(
                a,
                None,
                &morph.strict_morph.graph,
                &morph.strict_morph.fst,
            ) {
                if formatted != tok.surface {
                    let fl = tr_lower(&formatted);
                    let diffs = count_diacritic_diffs(low, &fl);
                    slot.add_rule(formatted, fl, SRC_OOV_APOSTROPHE, 0.85, 0.15, diffs);
                }
            }
        }
    }
}

fn add_oov_proper_or_acronym_candidates<'a>(
    slot: &mut SlotDrafts,
    tok: &SpanToken,
    low: &str,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) {
    let low_chars = u16_len(low);
    if !(4..=20).contains(&low_chars) {
        return;
    }
    let regular_word = morph.has_regular_strict_analysis(low, dynamic, scratch);
    for &suf in OOV_PROPER_INFLECT_SUFS {
        let suf_chars = u16_len(suf);
        if low_chars < suf_chars + 2 || !low.ends_with(suf) {
            continue;
        }
        let stem_low = &low[..low.len() - suf.len()];
        let is_acronym = knowledge.is_known_acronym_stem(stem_low);
        let is_acro_only = suf.starts_with("li")
            || suf.starts_with("lı")
            || suf.starts_with("lu")
            || suf.starts_with("lü")
            || suf == "ler"
            || suf == "lar";
        if is_acro_only && !is_acronym {
            continue;
        }
        if tok.shape == CasingShape::Lower && is_curated_shorthand(low) {
            continue;
        }
        if regular_word && !is_acronym {
            continue;
        }
        let known_proper = is_acronym || knowledge.is_known_proper_or_acronym_stem(stem_low);
        let mid_sent_title_oov = !known_proper
            && !slot.cap_here
            && tok.shape == CasingShape::Title
            && low_chars - suf_chars >= 3
            && !morph.has_regular_strict_analysis(stem_low, dynamic, scratch);
        if known_proper || mid_sent_title_oov {
            let mut cand_low = String::with_capacity(stem_low.len() + 1 + suf.len());
            cand_low.push_str(stem_low);
            cand_low.push('\'');
            cand_low.push_str(suf);
            let cand_surf = if is_acronym {
                let mut s = tr_upper(stem_low);
                s.push('\'');
                s.push_str(suf);
                s
            } else {
                truecase_slot(slot, tok, &cand_low, knowledge, morph, dynamic, scratch)
            };
            let prec = if is_acronym {
                0.86
            } else if known_proper {
                0.80
            } else {
                0.72
            };
            slot.add_rule(cand_surf, cand_low, SRC_OOV_APOSTROPHE, prec, 0.22, 0);
            return;
        }
    }
}

fn assimilate_derivational_tail(stem: &str, tail: &str) -> String {
    if stem.is_empty() || tail.is_empty() {
        return String::from(tail);
    }
    let last_ch = stem.chars().next_back().unwrap_or('a');
    let voiceless = TurkishAlphabet::is_voiceless_consonant(last_ch);
    for &(bad_pref, v_rep, voiced_rep) in &[
        ("cug", "çuğ", "cuğ"),
        ("cüg", "çüğ", "cüğ"),
        ("cig", "çiğ", "ciğ"),
        ("cıg", "çığ", "cığ"),
    ] {
        if let Some(rest) = tail.strip_prefix(bad_pref) {
            let mut s = String::with_capacity(6 + rest.len());
            s.push_str(if voiceless { v_rep } else { voiced_rep });
            s.push_str(rest);
            return s;
        }
    }
    if voiceless {
        if let Some(rest) = tail.strip_prefix('c') {
            let mut s = String::with_capacity(2 + rest.len());
            s.push('ç');
            s.push_str(rest);
            return s;
        }
    }
    String::from(tail)
}

// =========================================================================
// Expressive Elongation, Question-Clitic Twins & Guarded Spellchecker
// =========================================================================

#[allow(clippy::too_many_arguments)]
pub fn add_elongation_candidates<'a>(
    slot: &mut SlotDrafts,
    tok: &SpanToken,
    expected_prev_q: char,
    strict_known: bool,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) {
    let low = tok.lower.as_str();
    let max_run = max_repeated_letter_run(low);
    if max_run < 3
        && is_protected_from_double_letter_squeeze(
            slot,
            tok,
            low,
            strict_known,
            knowledge,
            morph,
            dynamic,
            scratch,
        )
    {
        return;
    }
    let m_stem_q_boundary =
        max_run < 3 && is_m_stem_plus_question_clitic(tok, morph, dynamic, scratch);
    let sq1 = if m_stem_q_boundary {
        String::from(low)
    } else {
        squeeze_runs(low, 1)
    };
    let sq2 = squeeze_runs(low, 2);
    let sq_trail = squeeze_trailing_run(low);
    let sq_vow = squeeze_vowel_runs(low, 1);

    let mut unique_sq: Vec<String> = Vec::with_capacity(4);
    for cand_sq in [sq1, sq2, sq_trail, sq_vow] {
        if u16_len(&cand_sq) >= 2 && cand_sq != low && !unique_sq.contains(&cand_sq) {
            unique_sq.push(cand_sq);
        }
    }
    for sq in &unique_sq {
        if let Some(re) = knowledge.view.lookup_p1(sq) {
            for idx in 0..re.len() {
                let (tc_norm, tc_cnt) = re.target(idx);
                let surf = truecase_slot(slot, tok, tc_norm, knowledge, morph, dynamic, scratch);
                let prob = re.prob_of(tc_norm);
                let log_cnt = ln_f64(1.0 + (tc_cnt as f64)) as f32;
                let diffs = count_diacritic_diffs(sq, tc_norm);
                slot.add(
                    surf,
                    String::from(tc_norm),
                    SRC_ELONGATION | SRC_PARALLEL_1TO1,
                    prob,
                    log_cnt,
                    0.0,
                    0.35,
                    diffs,
                );
            }
        }
        if let Some(realized) = morph.realize_valid_word_or_null(sq, dynamic, scratch) {
            let disharmonic_sq_q = expected_prev_q != '\0'
                && is_standalone_question_clitic(&realized)
                && realized.chars().nth(1) != Some(expected_prev_q);
            let surf = truecase_slot(slot, tok, &realized, knowledge, morph, dynamic, scratch);
            let diffs = count_diacritic_diffs(sq, &realized);
            slot.add_rule(
                surf,
                realized,
                SRC_ELONGATION,
                if disharmonic_sq_q { 0.25 } else { 0.80 },
                0.35,
                diffs,
            );
        }
        if !is_mid_sentence_title(slot, tok) {
            add_question_clitic_diacritic_twins(
                slot,
                tok,
                sq,
                expected_prev_q,
                0.0,
                0.0,
                true,
                knowledge,
                morph,
                dynamic,
                scratch,
            );
        }
    }
}

#[allow(clippy::too_many_arguments)]
fn is_protected_from_double_letter_squeeze<'a>(
    slot: &SlotDrafts,
    tok: &SpanToken,
    low: &str,
    strict_known: bool,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) -> bool {
    if strict_known
        || u16_len(low) <= 3
        || morph.is_strict_proper_or_abbr_only(low, dynamic, scratch)
        || knowledge.is_known_proper_or_acronym_stem(low)
    {
        return true;
    }
    if slot.sentence_all_caps {
        return false;
    }
    tok.shape == CasingShape::Upper || (!slot.cap_here && tok.shape == CasingShape::Title)
}

#[allow(clippy::too_many_arguments)]
pub fn add_question_clitic_diacritic_twins<'a>(
    slot: &mut SlotDrafts,
    tok: &SpanToken,
    candidate_base_lower: &str,
    expected_prev_q: char,
    raw_keep_prob: f32,
    raw_keep_log_cnt: f32,
    from_elongation: bool,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) {
    if !is_standalone_question_clitic(candidate_base_lower) {
        return;
    }
    let Some(cur_v) = candidate_base_lower.chars().nth(1) else {
        return;
    };
    let same_rounding: &[char] = match cur_v {
        'i' | 'ı' => &['i', 'ı'],
        'u' | 'ü' => &['u', 'ü'],
        _ => return,
    };
    for &hv in same_rounding {
        let variant = harmonize_question_clitic(candidate_base_lower, hv);
        if !from_elongation && variant == tok.lower {
            continue;
        }
        let raw_diffs = count_diacritic_diffs(candidate_base_lower, &variant);
        let effective_diffs = raw_diffs.min(1);
        let matches_prev = expected_prev_q != '\0' && hv == expected_prev_q;
        let src_mask = SRC_FORMAL_MORPH
            | if raw_diffs > 0 { SRC_DEASCIIFIER } else { 0 }
            | if from_elongation { SRC_ELONGATION } else { 0 }
            | if matches_prev && raw_keep_prob > 0.0 {
                SRC_PARALLEL_1TO1
            } else {
                0
            };
        let par_prob = if matches_prev && !from_elongation {
            raw_keep_prob.max(0.85)
        } else {
            0.0
        };
        let par_log_cnt = if matches_prev && !from_elongation {
            raw_keep_log_cnt
        } else {
            0.0
        };
        let suf_prec = if matches_prev {
            0.92
        } else if from_elongation && expected_prev_q == '\0' {
            0.78
        } else {
            0.0
        };
        let cost = (if from_elongation { 0.32 } else { 0.0 }) + (effective_diffs as f32) * 0.08;
        let surf = truecase_slot(slot, tok, &variant, knowledge, morph, dynamic, scratch);
        slot.add(
            surf,
            variant,
            src_mask,
            par_prob,
            par_log_cnt,
            suf_prec,
            cost,
            effective_diffs,
        );
    }
}

#[allow(clippy::too_many_arguments)]
pub fn add_guarded_spell_candidates<'a>(
    slot: &mut SlotDrafts,
    tok: &SpanToken,
    protect_mid_title: bool,
    spell_checker: &CompactStemEndingSpellChecker<'a>,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) {
    let low = tok.lower.as_str();
    let cands = spell_checker.suggest_scored(low, &morph.strict_morph, dynamic, scratch);
    let raw_neg_cue = morph.has_raw_negative_verb_cue(low, dynamic, scratch);
    let squeezed1 = if is_m_stem_plus_question_clitic(tok, morph, dynamic, scratch) {
        Some(squeeze_runs(low, 1))
    } else {
        None
    };
    let mut added = 0usize;
    for sc in cands {
        let nl = tr_lower(&sc.surface);
        if nl == low || squeezed1.as_deref() == Some(nl.as_str()) {
            continue;
        }
        if protect_mid_title && sc.edit_cost > 0.35 {
            continue;
        }
        if !morph.has_regular_strict_analysis(&nl, dynamic, scratch) {
            continue;
        }
        if strips_explicit_diacritic(low, &nl) && sc.edit_cost > 0.65 {
            continue;
        }
        let mut eff_edit_cost = sc.edit_cost;
        if raw_neg_cue && morph.summarize_morph(&nl, dynamic, scratch).verb_polarity >= 0 {
            eff_edit_cost += 0.55;
        }
        let surf = truecase_slot(slot, tok, &nl, knowledge, morph, dynamic, scratch);
        slot.add_rule(
            surf,
            nl,
            SRC_SPELLCHECK,
            0.0,
            eff_edit_cost,
            sc.diacritic_diffs,
        );
        added += 1;
        if added >= 5 {
            return;
        }
    }
}

pub fn is_m_stem_plus_question_clitic<'a>(
    tok: &SpanToken,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) -> bool {
    let low = tok.lower.as_str();
    let low_chars = u16_len(low);
    for &qt in QUESTION_TAILS {
        let qt_chars = u16_len(qt);
        if low_chars >= qt_chars + 3 {
            let raw_head_opt = if let Some(h) = low.strip_suffix(qt) {
                Some(h)
            } else {
                let qt_asc = to_ascii(qt);
                if tok.ascii.ends_with(&qt_asc) {
                    Some(take_first_chars(low, low_chars - qt_chars))
                } else {
                    None
                }
            };
            if let Some(raw_head) = raw_head_opt {
                if raw_head.ends_with('m')
                    && (morph.has_regular_strict_analysis(raw_head, dynamic, scratch)
                        || morph
                            .realize_valid_word_or_null(raw_head, dynamic, scratch)
                            .is_some())
                {
                    return true;
                }
            }
        }
    }
    false
}

#[inline]
fn is_mid_sentence_title(slot: &SlotDrafts, tok: &SpanToken) -> bool {
    !slot.sentence_all_caps && !slot.cap_here && tok.shape == CasingShape::Title
}

pub fn build_ordinal_inflected(digits: &str, suf: &str) -> Option<String> {
    if digits.is_empty() {
        return None;
    }
    let mut last_digit = digits.as_bytes()[digits.len() - 1] as char;
    if digits.ends_with("10")
        || digits.ends_with("30")
        || digits.ends_with("40")
        || digits.ends_with("60")
        || digits.ends_with("90")
    {
        last_digit = 'X';
    }
    let ord_base = match last_digit {
        '1' | '5' | '8' => "inci",
        '2' | '7' => "nci",
        '3' | '4' => "üncü",
        '6' => "ncı",
        _ => "uncu",
    };
    if suf.starts_with("nc")
        || suf.starts_with("ınc")
        || suf.starts_with("inc")
        || suf.starts_with("unc")
        || suf.starts_with("ünc")
    {
        let mut s = String::with_capacity(digits.len() + 1 + suf.len());
        s.push_str(digits);
        s.push('\'');
        s.push_str(suf);
        return Some(s);
    }
    let lv = last_turkish_vowel(ord_base);
    let harmonized_suf = if suf == "da" || suf == "de" {
        if is_front_vowel(lv) { "de" } else { "da" }
    } else if suf == "dan" || suf == "den" {
        if is_front_vowel(lv) { "den" } else { "dan" }
    } else {
        suf
    };
    let mut s = String::with_capacity(digits.len() + 1 + ord_base.len() + harmonized_suf.len());
    s.push_str(digits);
    s.push('\'');
    s.push_str(ord_base);
    s.push_str(harmonized_suf);
    Some(s)
}

pub fn max_repeated_letter_run(s: &str) -> usize {
    let mut max_run = 1usize;
    let mut run = 1usize;
    let mut prev: Option<char> = None;
    for ch in s.chars() {
        if prev == Some(ch) && ch.is_alphabetic() {
            run += 1;
            if run > max_run {
                max_run = run;
            }
        } else {
            run = 1;
        }
        prev = Some(ch);
    }
    max_run
}

pub fn squeeze_runs(s: &str, max_run: usize) -> String {
    let mut sb = String::with_capacity(s.len());
    let mut run = 0usize;
    let mut prev = '\0';
    for (i, c) in s.chars().enumerate() {
        if i > 0 && c == prev && c.is_alphabetic() {
            run += 1;
            if run <= max_run {
                sb.push(c);
            }
        } else {
            prev = c;
            run = 1;
            sb.push(c);
        }
    }
    sb
}

pub fn squeeze_trailing_run(s: &str) -> String {
    let chars: Vec<char> = s.chars().collect();
    let n = chars.len();
    if n < 2 {
        return String::from(s);
    }
    let last = chars[n - 1];
    if !last.is_alphabetic() {
        return String::from(s);
    }
    let mut cut = n - 1;
    while cut > 0 && chars[cut - 1] == last {
        cut -= 1;
    }
    if cut < n - 1 {
        chars[..=cut].iter().collect()
    } else {
        String::from(s)
    }
}

pub fn squeeze_vowel_runs(s: &str, max_vowel_run: usize) -> String {
    let mut sb = String::with_capacity(s.len());
    let mut run = 0usize;
    let mut prev = '\0';
    for (i, c) in s.chars().enumerate() {
        if i > 0 && c == prev && matches!(c, 'a' | 'e' | 'ı' | 'i' | 'o' | 'ö' | 'u' | 'ü') {
            run += 1;
            if run <= max_vowel_run {
                sb.push(c);
            }
        } else {
            prev = c;
            run = 1;
            sb.push(c);
        }
    }
    sb
}
