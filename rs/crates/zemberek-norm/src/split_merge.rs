use alloc::string::String;
use alloc::vec::Vec;
use core::cmp::Ordering;
use zemberek_core::{SecondaryPos, TurkishAlphabet};
use zemberek_morph::{AnalyzerScratch, DynamicStemTrie};
use zemberek_pack::clean_lm::ln_f64;
use zemberek_tok::{CasingShape, SpanToken, TokenType};
use crate::candidate_gen::SpanCandidateGenerator;
use crate::knowledge::NormKnowledge;
use crate::morph_support::{
    format_proper_or_abbr_tdk, is_detached_num_suffix, is_detached_word_apostrophe_suffix,
    is_front_vowel, last_turkish_vowel, strips_explicit_stem_diacritic, LatticeMorphSupport,
};
use crate::single_rewrite::build_ordinal_inflected;
use crate::types::{
    count_diacritic_diffs, has_ascii_ambiguous_chars, has_explicit_turkish_diacritics,
    is_all_digits, is_java_digit, slice_chars, take_first_chars, to_ascii, tr_capitalize, tr_lower,
    tr_upper, u16_len, SlotDrafts, SpanArc, CTX_SENT_TITLE_NOISY, HEAD_SYNTAX_FINITE_OR_CONVERB,
    SRC_APOSTROPHE_DROP, SRC_CLITIC_SPLIT, SRC_COMPOUND_MERGE, SRC_DEASCIIFIER, SRC_INFORMAL_MORPH,
    SRC_NUMERAL_JOIN, SRC_OOV_APOSTROPHE, SRC_PARALLEL_MERGE,
};

pub const QUESTION_TAILS: &[&str] = &[
    "mı", "mi", "mu", "mü", "mıyım", "miyim", "muyum", "müyüm", "mısın", "misin", "musun",
    "müsün", "mıyız", "miyiz", "muyuz", "müyüz", "mısınız", "misiniz", "musunuz", "müsünüz",
    "mıydı", "miydi", "muydu", "müydü", "mıydım", "miydim", "muydum", "müydüm", "mıydın",
    "miydin", "muydun", "müydün", "mıydık", "miydik", "muyduk", "müydük", "mıydınız", "miydiniz",
    "muydunuz", "müydünüz", "mıymış", "miymiş", "muymuş", "müymüş",
];

pub fn is_question_tail(s: &str) -> bool {
    QUESTION_TAILS.contains(&s)
}

pub fn is_loc_or_abl_particle(s: &str) -> bool {
    matches!(s, "de" | "da" | "te" | "ta" | "den" | "dan" | "ten" | "tan")
}

fn is_locative_clitic_tail(s: &str) -> bool {
    matches!(s, "de" | "da" | "te" | "ta")
}

fn is_lexical_ki_word(s: &str) -> bool {
    matches!(
        s,
        "sanki"
            | "oysaki"
            | "mademki"
            | "belki"
            | "halbuki"
            | "çünkü"
            | "meğerki"
            | "illaki"
            | "benimki"
            | "seninki"
            | "onunki"
            | "bizimki"
            | "sizinki"
            | "onlarınki"
            | "dünkü"
            | "bugünkü"
            | "yarınki"
            | "akşamki"
            | "sabahki"
            | "önceki"
            | "sonraki"
            | "öteki"
            | "beriki"
            | "karşıki"
            | "yukarıki"
            | "aşağıki"
    )
}

fn is_non_splittable_loc_adverb(s: &str) -> bool {
    matches!(s, "işte" | "elbette" | "adeta" | "birlikte")
}

fn is_blocked_locative_merge(s: &str) -> bool {
    matches!(s, "yada" | "bide" | "birde" | "hemde")
}

const CLOSED_COMPOUND_PREFIXES: &[&str] = &[
    "hiçbir", "birkaç", "birçok", "herhangi", "birbiri", "vazgeç", "başvur", "öngör", "varsay",
];

fn is_detached_proper_nonloc_suffix(s: &str) -> bool {
    matches!(
        s,
        "ya" | "ye"
            | "a"
            | "e"
            | "yı"
            | "yi"
            | "yu"
            | "yü"
            | "nın"
            | "nin"
            | "nun"
            | "nün"
            | "ın"
            | "in"
            | "un"
            | "ün"
            | "yla"
            | "yle"
            | "la"
            | "le"
            | "lı"
            | "li"
            | "lu"
            | "lü"
            | "lık"
            | "lik"
            | "luk"
            | "lük"
            | "lar"
            | "ler"
            | "ları"
            | "leri"
            | "ların"
            | "lerin"
            | "lisini"
            | "lısını"
            | "lusunu"
            | "lüsünü"
            | "cılar"
            | "ciler"
            | "cular"
            | "cüler"
            | "çılar"
            | "çiler"
            | "çular"
            | "çüler"
    )
}

fn is_split_copula_tail(s: &str) -> bool {
    matches!(
        s,
        "dı" | "di"
            | "du"
            | "dü"
            | "tı"
            | "ti"
            | "tu"
            | "tü"
            | "dım"
            | "dim"
            | "dum"
            | "düm"
            | "tım"
            | "tim"
            | "tum"
            | "tüm"
            | "dın"
            | "din"
            | "dun"
            | "dün"
            | "tın"
            | "tin"
            | "tun"
            | "tün"
            | "dık"
            | "dik"
            | "duk"
            | "dük"
            | "tık"
            | "tik"
            | "tuk"
            | "tük"
            | "mış"
            | "miş"
            | "muş"
            | "müş"
            | "ydı"
            | "ydi"
            | "ydu"
            | "ydü"
            | "sa"
            | "se"
            | "ysa"
            | "yse"
    )
}

const SPLIT_HEAD_PROPER_SUFS: &[&str] = &[
    "nın", "nin", "nun", "nün", "ın", "in", "un", "ün", "ya", "ye", "yı", "yi", "yu", "yü", "da",
    "de", "ta", "te", "dan", "den", "tan", "ten", "yla", "yle", "la", "le", "a", "e", "ı", "i",
    "u", "ü",
];

const SEY_PREFIXES: &[&str] = &[
    "hiçbirşey",
    "hicbirsey",
    "hiçbişey",
    "hicbisey",
    "herşey",
    "hersey",
    "birşey",
    "birsey",
    "bişey",
    "bisey",
    "bişi",
    "bisi",
];

const BIR_YER_DAHA_PREFIXES: &[&str] = &[
    "biryer", "biyer", "bıyer", "birdaha", "bidaha", "bıdaha",
];

const PARTICIPLE_SUFFIXES: &[&str] = &[
    "dığı", "diği", "duğu", "düğü", "tığı", "tiği", "tuğu", "tüğü", "dığım", "diğim", "duğum",
    "düğüm", "tığım", "tiğim", "tuğum", "tüğüm", "dığın", "diğin", "duğun", "düğün", "tığın",
    "tiğin", "tuğun", "tüğün", "dığımız", "diğimiz", "duğumuz", "düğümüz", "tığımız", "tiğimiz",
    "tuğumuz", "tüğümüz", "dığınız", "diğiniz", "duğunuz", "düğünüz", "tığınız", "tiğiniz",
    "tuğunuz", "tüğünüz", "an", "en", "acak", "ecek", "acağı", "eceği", "mış", "miş", "muş", "müş",
];

fn is_quantified_count_noun(s: &str) -> bool {
    matches!(s, "gün" | "ay" | "yıl" | "kez" | "kere" | "defa")
}

fn is_count_modifier(s: &str) -> bool {
    matches!(
        s,
        "bir"
            | "iki"
            | "üç"
            | "dört"
            | "beş"
            | "altı"
            | "yedi"
            | "sekiz"
            | "dokuz"
            | "on"
            | "yirmi"
            | "otuz"
            | "kırk"
            | "elli"
            | "yüz"
            | "bin"
            | "kaç"
            | "birkaç"
            | "her"
    )
}

#[derive(Clone, Debug)]
struct SplitHead {
    custom_surface: Option<String>,
    norm_lower: String,
    is_proper: bool,
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

// =========================================================================
// 1:2 Clitic & Open-Compound Splits
// =========================================================================

pub fn add_ambiguity_preserving_splits<'a>(
    slot: &mut SlotDrafts,
    tok: &SpanToken,
    strict_known: bool,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) {
    let low = tok.lower.as_str();
    let clen = u16_len(low);
    if clen < 3 {
        return;
    }
    if clen == 3 && !tok.contains_digit() {
        if low == "oda" {
            let surf = truecase_slot(slot, tok, "o da", knowledge, morph, dynamic, scratch);
            slot.add_rule(surf, String::from("o da"), SRC_CLITIC_SPLIT, 0.0, 0.20, 0);
        } else if low == "omu" {
            let surf = truecase_slot(slot, tok, "o mu", knowledge, morph, dynamic, scratch);
            slot.add_rule(surf, String::from("o mu"), SRC_CLITIC_SPLIT, 0.88, 0.18, 0);
        }
        return;
    }

    if let Some(ap_b) = low.find('\'') {
        if ap_b > 0 && ap_b + 1 < low.len() {
            add_apostrophe_clitic_splits(
                slot,
                tok,
                &low[..ap_b],
                &low[ap_b + 1..],
                knowledge,
                morph,
                dynamic,
                scratch,
            );
        }
        return;
    }
    if tok.contains_digit() {
        return;
    }

    add_camel_case_run_on_split(slot, tok, knowledge, morph, dynamic, scratch);

    let has_valid_single_word = strict_known || slot.has_valid_single_word_draft();
    add_locative_clitic_splits(
        slot,
        tok,
        has_valid_single_word,
        knowledge,
        morph,
        dynamic,
        scratch,
    );
    add_question_clitic_splits(slot, tok, knowledge, morph, dynamic, scratch);
    add_conjunction_ki_splits(slot, tok, knowledge, morph, dynamic, scratch);
    add_enclitic_ya_split(slot, tok, strict_known, knowledge, morph, dynamic, scratch);
    add_fixed_prefix_splits(slot, tok, strict_known, knowledge, morph, dynamic, scratch);
    add_clean_bigram_compound_splits(slot, tok, strict_known, knowledge, morph, dynamic, scratch);
    add_glued_two_word_split(slot, tok, strict_known, knowledge, morph, dynamic, scratch);
}

#[allow(clippy::too_many_arguments)]
fn add_apostrophe_clitic_splits<'a>(
    slot: &mut SlotDrafts,
    tok: &SpanToken,
    stem_low: &str,
    tail_low: &str,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) {
    let numeric_stem = is_all_digits(stem_low);
    if is_locative_clitic_tail(tail_low) {
        if !numeric_stem {
            let clitic = harmonized_de_da_clitic(stem_low);
            let mut split_low = String::with_capacity(stem_low.len() + 3);
            split_low.push_str(stem_low);
            split_low.push(' ');
            split_low.push_str(clitic);
            let surf = truecase_slot(slot, tok, &split_low, knowledge, morph, dynamic, scratch);
            slot.add_rule(
                surf,
                split_low,
                SRC_CLITIC_SPLIT | SRC_APOSTROPHE_DROP,
                0.0,
                0.24,
                0,
            );
        }
        return;
    }
    if u16_len(tail_low) == 4
        && (tail_low.ends_with("de") || tail_low.ends_with("da"))
        && is_locative_clitic_tail(&tail_low[..tail_low.len() - 2])
    {
        let mut split_low = String::with_capacity(stem_low.len() + tail_low.len() + 2);
        split_low.push_str(stem_low);
        split_low.push('\'');
        split_low.push_str(&tail_low[..tail_low.len() - 2]);
        split_low.push(' ');
        split_low.push_str(&tail_low[tail_low.len() - 2..]);
        let split_surf = if numeric_stem {
            split_low.clone()
        } else {
            truecase_slot(slot, tok, &split_low, knowledge, morph, dynamic, scratch)
        };
        slot.add_rule(split_surf, split_low, SRC_CLITIC_SPLIT, 0.90, 0.18, 0);
        return;
    }
    if is_question_tail(tail_low) && !numeric_stem {
        let qt = harmonize_question_clitic(tail_low, last_turkish_vowel(stem_low));
        let mut split_low = String::with_capacity(stem_low.len() + 1 + qt.len());
        split_low.push_str(stem_low);
        split_low.push(' ');
        split_low.push_str(&qt);
        let surf = truecase_slot(slot, tok, &split_low, knowledge, morph, dynamic, scratch);
        slot.add_rule(
            surf,
            split_low,
            SRC_CLITIC_SPLIT | SRC_APOSTROPHE_DROP,
            0.90,
            0.18,
            0,
        );
    }
}

fn add_camel_case_run_on_split<'a>(
    slot: &mut SlotDrafts,
    tok: &SpanToken,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) {
    let low = tok.lower.as_str();
    let clen = u16_len(low);
    if tok.shape != CasingShape::Mixed
        || !(6..=24).contains(&clen)
        || knowledge.is_known_brand_mixed_case(low)
    {
        return;
    }
    let Some(camel_cut) = find_single_camel_boundary(&tok.surface) else {
        return;
    };
    if camel_cut < 3 || camel_cut > clen - 3 || u16_len(&tok.surface) != clen {
        return;
    }
    let raw_w1 = slice_chars(low, 0, camel_cut);
    let raw_w2 = slice_chars(low, camel_cut, clen);
    let Some(real_w1) = realize_valid_or_attested_stem(raw_w1, knowledge, morph, dynamic, scratch)
    else {
        return;
    };
    let Some(real_w2) = realize_valid_or_attested_stem(raw_w2, knowledge, morph, dynamic, scratch)
    else {
        return;
    };
    if strips_explicit_stem_diacritic(raw_w1, &real_w1)
        || strips_explicit_stem_diacritic(raw_w2, &real_w2)
    {
        return;
    }
    let surf_w1 = if tok.surface.chars().next().is_some_and(char::is_uppercase) {
        tr_capitalize(&real_w1)
    } else {
        real_w1.clone()
    };
    let surf_w2 = knowledge.truecase(
        &real_w2,
        CasingShape::Title,
        slice_chars(&tok.surface, camel_cut, clen),
        false,
        slot.sentence_all_caps,
        morph,
        0,
        dynamic,
        scratch,
    );
    let diac_diffs = count_diacritic_diffs(raw_w1, &real_w1) + count_diacritic_diffs(raw_w2, &real_w2);
    let mut split_surf = String::with_capacity(surf_w1.len() + 1 + surf_w2.len());
    split_surf.push_str(&surf_w1);
    split_surf.push(' ');
    split_surf.push_str(&surf_w2);
    let mut split_low = String::with_capacity(real_w1.len() + 1 + real_w2.len());
    split_low.push_str(&real_w1);
    split_low.push(' ');
    split_low.push_str(&real_w2);
    slot.add_rule(split_surf, split_low, SRC_CLITIC_SPLIT, 0.90, 0.18, diac_diffs);
}

#[allow(clippy::too_many_arguments)]
fn add_locative_clitic_splits<'a>(
    slot: &mut SlotDrafts,
    tok: &SpanToken,
    has_valid_single_word: bool,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) {
    let low = tok.lower.as_str();
    let Some(raw_head) = low
        .strip_suffix("de")
        .or_else(|| low.strip_suffix("da"))
        .or_else(|| low.strip_suffix("te"))
        .or_else(|| low.strip_suffix("ta"))
    else {
        return;
    };
    if is_non_splittable_loc_adverb(low) || u16_len(raw_head) < 2 {
        return;
    }
    let unvoiced_tail = low.ends_with("te") || low.ends_with("ta");
    let query_head = query_for_ascii_upper_i(tok, raw_head);
    for sh in resolve_split_heads(&query_head, raw_head, slot, tok, knowledge, morph, dynamic, scratch) {
        let clitic = harmonized_de_da_clitic(&sh.norm_lower);
        let mut split_low = String::with_capacity(sh.norm_lower.len() + 3);
        split_low.push_str(&sh.norm_lower);
        split_low.push(' ');
        split_low.push_str(clitic);
        let split_surf =
            format_split_surface(slot, tok, &sh, clitic, &split_low, knowledge, morph, dynamic, scratch);
        let head_ms = morph.summarize_morph(&sh.norm_lower, dynamic, scratch);
        let d_diffs = count_diacritic_diffs(raw_head, &sh.norm_lower);
        let src_mask = SRC_CLITIC_SPLIT
            | if d_diffs > 0 { SRC_DEASCIIFIER } else { 0 }
            | if sh.is_proper { SRC_OOV_APOSTROPHE } else { 0 };
        let split_prec = if head_ms.syntax_class == HEAD_SYNTAX_FINITE_OR_CONVERB {
            0.92
        } else if sh.is_proper || !has_valid_single_word {
            0.80
        } else {
            0.0
        };
        slot.add_rule(
            split_surf,
            split_low,
            src_mask,
            split_prec,
            if unvoiced_tail { 0.26 } else { 0.22 },
            d_diffs,
        );
    }
}

fn add_question_clitic_splits<'a>(
    slot: &mut SlotDrafts,
    tok: &SpanToken,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) {
    let low = tok.lower.as_str();
    let clen = u16_len(low);
    for &qt in QUESTION_TAILS {
        let qt_chars = u16_len(qt);
        if clen < qt_chars + 2 {
            continue;
        }
        let raw_head_opt = if let Some(h) = low.strip_suffix(qt) {
            Some(h)
        } else {
            let qt_asc = to_ascii(qt);
            if tok.ascii.ends_with(&qt_asc) {
                Some(take_first_chars(low, clen - qt_chars))
            } else {
                None
            }
        };
        let Some(raw_head) = raw_head_opt else {
            continue;
        };
        let query_head = query_for_ascii_upper_i(tok, raw_head);
        let q_heads = resolve_split_heads(
            &query_head,
            raw_head,
            slot,
            tok,
            knowledge,
            morph,
            dynamic,
            scratch,
        );
        if q_heads.is_empty() {
            continue;
        }
        let mut emitted_any = false;
        for sh in q_heads {
            if !sh.is_proper
                && (morph.is_bare_imperative_verb_only(&sh.norm_lower, dynamic, scratch)
                    || (qt_chars >= 5 && sh.norm_lower == "al"))
            {
                continue;
            }
            let dict_hv = morph.expected_turkish_question_vowel(&sh.norm_lower, dynamic, scratch);
            let vowel = if dict_hv != '\0' {
                dict_hv
            } else {
                last_turkish_vowel(&sh.norm_lower)
            };
            let harmonized_qt = harmonize_question_clitic(qt, vowel);
            let mut split_low = String::with_capacity(sh.norm_lower.len() + 1 + harmonized_qt.len());
            split_low.push_str(&sh.norm_lower);
            split_low.push(' ');
            split_low.push_str(&harmonized_qt);
            let split_surf = format_split_surface(
                slot,
                tok,
                &sh,
                &harmonized_qt,
                &split_low,
                knowledge,
                morph,
                dynamic,
                scratch,
            );
            let d_diffs = count_diacritic_diffs(raw_head, &sh.norm_lower);
            let src_mask = SRC_CLITIC_SPLIT
                | if d_diffs > 0 { SRC_DEASCIIFIER } else { 0 }
                | if sh.is_proper { SRC_OOV_APOSTROPHE } else { 0 };
            slot.add_rule(split_surf, split_low, src_mask, 0.88, 0.18, d_diffs);
            emitted_any = true;
        }
        if emitted_any {
            return;
        }
    }
}

fn add_conjunction_ki_splits<'a>(
    slot: &mut SlotDrafts,
    tok: &SpanToken,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) {
    let low = tok.lower.as_str();
    let clen = u16_len(low);
    if clen < 4 || is_lexical_ki_word(low) || ends_with_locative_ki(low) {
        return;
    }
    let Some(raw_head) = low.strip_suffix("ki").or_else(|| low.strip_suffix("kı")) else {
        return;
    };
    let query_head = query_for_ascii_upper_i(tok, raw_head);
    for sh in resolve_split_heads(&query_head, raw_head, slot, tok, knowledge, morph, dynamic, scratch) {
        let mut closed_probe = String::with_capacity(sh.norm_lower.len() + 2);
        closed_probe.push_str(&sh.norm_lower);
        closed_probe.push_str("ki");
        if is_lexical_ki_word(&closed_probe) {
            continue;
        }
        let mut split_low = String::with_capacity(sh.norm_lower.len() + 3);
        split_low.push_str(&sh.norm_lower);
        split_low.push_str(" ki");
        let split_surf = format_split_surface(
            slot,
            tok,
            &sh,
            "ki",
            &split_low,
            knowledge,
            morph,
            dynamic,
            scratch,
        );
        let d_diffs = count_diacritic_diffs(raw_head, &sh.norm_lower);
        let src_mask = SRC_CLITIC_SPLIT | if d_diffs > 0 { SRC_DEASCIIFIER } else { 0 };
        slot.add_rule(split_surf, split_low, src_mask, 0.82, 0.22, d_diffs);
    }
}

#[allow(clippy::too_many_arguments)]
fn add_enclitic_ya_split<'a>(
    slot: &mut SlotDrafts,
    tok: &SpanToken,
    strict_known: bool,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) {
    let low = tok.lower.as_str();
    if strict_known || u16_len(low) < 5 {
        return;
    }
    let Some(raw_head) = low.strip_suffix("ya") else {
        return;
    };
    let query_head = query_for_ascii_upper_i(tok, raw_head);
    let Some(head_real) = morph.realize_valid_word_or_null(&query_head, dynamic, scratch) else {
        return;
    };
    if strips_explicit_stem_diacritic(raw_head, &head_real)
        || morph.summarize_morph(&head_real, dynamic, scratch).syntax_class
            != HEAD_SYNTAX_FINITE_OR_CONVERB
    {
        return;
    }
    let d_diffs = count_diacritic_diffs(raw_head, &head_real);
    let mut split_low = String::with_capacity(head_real.len() + 3);
    split_low.push_str(&head_real);
    split_low.push_str(" ya");
    let surf = truecase_slot(slot, tok, &split_low, knowledge, morph, dynamic, scratch);
    slot.add_rule(
        surf,
        split_low,
        SRC_CLITIC_SPLIT | if d_diffs > 0 { SRC_DEASCIIFIER } else { 0 },
        0.88,
        0.20,
        d_diffs,
    );
}

#[allow(clippy::too_many_arguments)]
fn add_fixed_prefix_splits<'a>(
    slot: &mut SlotDrafts,
    tok: &SpanToken,
    strict_known: bool,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) {
    if strict_known {
        return;
    }
    let low = tok.lower.as_str();
    if u16_len(low) >= 5 {
        // 1. bir/her/hiçbir şey*
        for &pref in SEY_PREFIXES {
            if let Some(tail) = low.strip_prefix(pref) {
                if (pref == "bişi" || pref == "bisi") && tail.is_empty() {
                    continue;
                }
                let mut sey_probe = String::with_capacity(4 + tail.len());
                sey_probe.push_str("şey");
                sey_probe.push_str(tail);
                let sey_real = if tail.is_empty() {
                    Some(String::from("şey"))
                } else {
                    morph.realize_valid_word_or_null(&sey_probe, dynamic, scratch)
                };
                if let Some(sr) = sey_real {
                    if sr.starts_with("şey") {
                        let det = if pref.starts_with("hiç") || pref.starts_with("hic") {
                            "hiçbir"
                        } else if pref.starts_with("her") {
                            "her"
                        } else {
                            "bir"
                        };
                        let mut split_low = String::with_capacity(det.len() + 1 + sr.len());
                        split_low.push_str(det);
                        split_low.push(' ');
                        split_low.push_str(&sr);
                        let surf =
                            truecase_slot(slot, tok, &split_low, knowledge, morph, dynamic, scratch);
                        let diffs = count_diacritic_diffs(&sey_probe, &sr);
                        slot.add_rule(surf, split_low, SRC_CLITIC_SPLIT, 0.90, 0.22, diffs);
                        break;
                    }
                }
            }
        }

        // 2. bir yer / bir daha
        for &bp in BIR_YER_DAHA_PREFIXES {
            if let Some(tail) = low.strip_prefix(bp) {
                let base_head = if bp.ends_with("yer") { "yer" } else { "daha" };
                let mut probe = String::with_capacity(base_head.len() + tail.len());
                probe.push_str(base_head);
                probe.push_str(tail);
                let sec_real = if tail.is_empty() {
                    Some(String::from(base_head))
                } else {
                    morph.realize_valid_word_or_null(&probe, dynamic, scratch)
                };
                if let Some(sr) = sec_real {
                    if sr.starts_with(base_head) {
                        let mut split_low = String::with_capacity(4 + sr.len());
                        split_low.push_str("bir ");
                        split_low.push_str(&sr);
                        let surf =
                            truecase_slot(slot, tok, &split_low, knowledge, morph, dynamic, scratch);
                        let diffs = count_diacritic_diffs(&probe, &sr);
                        slot.add_rule(surf, split_low, SRC_CLITIC_SPLIT, 0.88, 0.22, diffs);
                        break;
                    }
                }
            }
        }

        // 3. nap* / nol*
        if low.starts_with("nap") || low.starts_with("nol") {
            let verb_base = if low.starts_with("nap") { "yap" } else { "ol" };
            let tail = &low[3..];
            let mut probe = String::with_capacity(verb_base.len() + tail.len());
            probe.push_str(verb_base);
            probe.push_str(tail);
            if let Some(verb_real) = morph.realize_valid_word_or_null(&probe, dynamic, scratch) {
                if verb_real.starts_with(verb_base) {
                    let mut split_low = String::with_capacity(3 + verb_real.len());
                    split_low.push_str("ne ");
                    split_low.push_str(&verb_real);
                    let surf =
                        truecase_slot(slot, tok, &split_low, knowledge, morph, dynamic, scratch);
                    let diffs = count_diacritic_diffs(&probe, &verb_real);
                    slot.add_rule(
                        surf,
                        split_low,
                        SRC_CLITIC_SPLIT | SRC_INFORMAL_MORPH,
                        0.92,
                        0.22,
                        diffs,
                    );
                }
            }
        }
    }

    if matches!(low, "acabao" | "kimo" | "nediro") {
        let head = &low[..low.len() - 1];
        let mut split_low = String::with_capacity(low.len() + 1);
        split_low.push_str(head);
        split_low.push_str(" o");
        let surf = truecase_slot(slot, tok, &split_low, knowledge, morph, dynamic, scratch);
        slot.add_rule(surf, split_low, SRC_CLITIC_SPLIT, 0.92, 0.18, 0);
    }
}

#[allow(clippy::too_many_arguments)]
fn add_clean_bigram_compound_splits<'a>(
    slot: &mut SlotDrafts,
    tok: &SpanToken,
    strict_known: bool,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) {
    let low = tok.lower.as_str();
    let char_offsets: Vec<usize> = low.char_indices().map(|(b, _)| b).collect();
    let clen = char_offsets.len();
    if !(5..=22).contains(&clen) || u16_len(low) != clen {
        return;
    }
    let closed_freq = knowledge.clean_lm.unigram_count(low);
    for &b_cut in &char_offsets[2..=(clen - 2)] {
        let w1 = &low[..b_cut];
        let w2 = &low[b_cut..];
        let bg_cnt = knowledge.clean_lm.bigram_count(Some(w1), Some(w2));
        if bg_cnt >= 10 && (!strict_known || bg_cnt >= 80 || bg_cnt > closed_freq * 3) {
            let mut split_low = String::with_capacity(low.len() + 1);
            split_low.push_str(w1);
            split_low.push(' ');
            split_low.push_str(w2);
            let surf = truecase_slot(slot, tok, &split_low, knowledge, morph, dynamic, scratch);
            let log_cnt = ln_f64(1.0 + (bg_cnt as f64)) as f32;
            slot.add(surf, split_low, SRC_CLITIC_SPLIT, 0.0, log_cnt, 0.80, 0.25, 0);
        }
    }
}

#[allow(clippy::too_many_arguments)]
fn add_glued_two_word_split<'a>(
    slot: &mut SlotDrafts,
    tok: &SpanToken,
    strict_known: bool,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) {
    let low = tok.lower.as_str();
    let low_offsets: Vec<usize> = low.char_indices().map(|(b, _)| b).collect();
    let clen = low_offsets.len();
    if strict_known
        || !(7..=24).contains(&clen)
        || u16_len(low) != clen
        || slot.has_low_cost_morph_draft()
    {
        return;
    }
    let query_full = query_for_ascii_upper_i(tok, low);
    let q_offsets: Vec<usize> = query_full.char_indices().map(|(b, _)| b).collect();
    if q_offsets.len() != clen {
        return;
    }

    let mut best_split_low: Option<String> = None;
    let mut best_diac_diffs = 0u32;
    let mut best_bigram = -100.0f32;

    for cut in 3..=(clen - 3) {
        let raw_s1 = &low[..low_offsets[cut]];
        let raw_s2 = &low[low_offsets[cut]..];
        let q_s1 = &query_full[..q_offsets[cut]];
        let q_s2 = &query_full[q_offsets[cut]..];

        let Some(w1) = resolve_glued_first_word(q_s1, raw_s1, knowledge, morph, dynamic, scratch)
        else {
            continue;
        };
        let Some(w2) =
            resolve_glued_second_word(q_s2, raw_s2, &w1, knowledge, morph, dynamic, scratch)
        else {
            continue;
        };
        let bg = knowledge.clean_lm.bigram_log_prob(Some(&w1), Some(&w2));
        if bg > best_bigram {
            best_bigram = bg;
            let mut sl = String::with_capacity(w1.len() + 1 + w2.len());
            sl.push_str(&w1);
            sl.push(' ');
            sl.push_str(&w2);
            best_split_low = Some(sl);
            best_diac_diffs =
                count_diacritic_diffs(raw_s1, &w1) + count_diacritic_diffs(raw_s2, &w2);
        }
    }
    if let Some(split_low) = best_split_low {
        if best_bigram > -11.5 {
            let surf = truecase_slot(slot, tok, &split_low, knowledge, morph, dynamic, scratch);
            slot.add_rule(
                surf,
                split_low,
                SRC_CLITIC_SPLIT | if best_diac_diffs > 0 { SRC_DEASCIIFIER } else { 0 },
                if best_bigram > -6.5 { 0.84 } else { 0.72 },
                0.28,
                best_diac_diffs,
            );
        }
    }
}

fn resolve_glued_first_word<'a>(
    query1: &str,
    raw_slice1: &str,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) -> Option<String> {
    if morph.has_regular_strict_analysis(query1, dynamic, scratch)
        || knowledge.clean_lm.unigram_count(query1) >= 20
    {
        return Some(String::from(query1));
    }
    if u16_len(query1) <= 9 && has_ascii_ambiguous_chars(query1) {
        if let Some(r1) = morph.realize_valid_word_or_null(query1, dynamic, scratch) {
            if !strips_explicit_stem_diacritic(raw_slice1, &r1)
                && knowledge.clean_lm.unigram_count(&r1) >= 40
            {
                return Some(r1);
            }
        }
    }
    None
}

fn resolve_glued_second_word<'a>(
    query2: &str,
    raw_slice2: &str,
    w1: &str,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) -> Option<String> {
    if morph.has_regular_strict_analysis(query2, dynamic, scratch)
        || knowledge.clean_lm.unigram_count(query2) >= 20
    {
        return Some(String::from(query2));
    }
    if knowledge.clean_lm.unigram_count(w1) >= 40 && has_ascii_ambiguous_chars(query2) {
        if let Some(r2) = morph.realize_valid_word_or_null(query2, dynamic, scratch) {
            if !strips_explicit_stem_diacritic(raw_slice2, &r2) {
                return Some(r2);
            }
        }
    }
    None
}

// =========================================================================
// Split-Head Resolution Helpers (`SmoothLm`-free: `lmVocab == null`)
// =========================================================================

#[allow(clippy::too_many_arguments)]
fn resolve_split_heads<'a>(
    query_head: &str,
    raw_head: &str,
    slot: &SlotDrafts,
    tok: &SpanToken,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) -> Vec<SplitHead> {
    let mut out: Vec<SplitHead> = Vec::with_capacity(3);
    let strict_reg = morph.has_regular_strict_analysis(query_head, dynamic, scratch);
    if strict_reg && !strips_explicit_stem_diacritic(raw_head, query_head) {
        out.push(SplitHead {
            custom_surface: None,
            norm_lower: String::from(query_head),
            is_proper: false,
        });
    }
    if !strict_reg {
        if let Some(exact_inf) = morph.first_exact_informal_or_null(query_head, raw_head, dynamic, scratch) {
            add_unique_split_head(
                &mut out,
                SplitHead {
                    custom_surface: None,
                    norm_lower: exact_inf,
                    is_proper: false,
                },
            );
        }
    }
    let is_short_pure_amb_ascii = u16_len(query_head) <= 6
        && !has_explicit_turkish_diacritics(query_head)
        && has_ascii_ambiguous_chars(query_head);
    if !strict_reg || is_short_pure_amb_ascii {
        collect_ascii_split_heads(
            query_head,
            raw_head,
            slot,
            knowledge,
            morph,
            dynamic,
            scratch,
            &mut out,
        );
    }
    if out.is_empty() && u16_len(query_head) >= 4 {
        if let Some(oov_prop) =
            find_oov_proper_split_head(query_head, slot, tok, knowledge, morph, dynamic, scratch)
        {
            add_unique_split_head(&mut out, oov_prop);
        }
    }
    if query_head.contains('x') || query_head.contains('w') {
        let adapted_q = query_head.replace('x', "ks").replace('w', "v");
        if let Some(adapted) = morph.realize_valid_word_or_null(&adapted_q, dynamic, scratch) {
            add_unique_split_head(
                &mut out,
                SplitHead {
                    custom_surface: None,
                    norm_lower: adapted,
                    is_proper: false,
                },
            );
        }
    }
    if out.is_empty()
        && u16_len(query_head) >= 3
        && (knowledge.clean_lm.unigram_count(query_head) >= 4
            || knowledge.is_known_proper_or_acronym_stem(query_head))
    {
        add_unique_split_head(
            &mut out,
            SplitHead {
                custom_surface: None,
                norm_lower: String::from(query_head),
                is_proper: false,
            },
        );
    }
    out
}

#[allow(clippy::too_many_arguments)]
fn collect_ascii_split_heads<'a>(
    query_head: &str,
    raw_head: &str,
    slot: &SlotDrafts,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
    out: &mut Vec<SplitHead>,
) {
    let mut reg_cands: Vec<String> = Vec::with_capacity(4);
    let mut best_proper: Option<SplitHead> = None;

    morph.for_each_ascii_realization(query_head, raw_head, dynamic, scratch, |a, nl, informal| {
        let spos = a.item.secondary_pos(&morph.strict_morph.graph);
        if spos == SecondaryPos::Abbreviation {
            return;
        }
        if spos != SecondaryPos::ProperNoun {
            if !reg_cands.iter().any(|x| x == nl) {
                reg_cands.push(String::from(nl));
            }
        } else if !informal && best_proper.is_none() {
            if let Some(fmt) = format_proper_or_abbr_tdk(
                a,
                Some(nl),
                &morph.strict_morph.graph,
                &morph.strict_morph.fst,
            ) {
                if !fmt.is_empty() {
                    let surf = if slot.sentence_all_caps {
                        tr_upper(&fmt)
                    } else {
                        fmt.clone()
                    };
                    best_proper = Some(SplitHead {
                        custom_surface: Some(surf),
                        norm_lower: tr_lower(&fmt),
                        is_proper: true,
                    });
                }
            }
        }
    });

    if reg_cands.len() > 1 {
        reg_cands.sort_by(|x, y| {
            let py = knowledge.clean_lm.unigram_log_prob(Some(y));
            let px = knowledge.clean_lm.unigram_log_prob(Some(x));
            py.partial_cmp(&px).unwrap_or(Ordering::Equal)
        });
    }
    let mut added_reg = 0usize;
    for rc in reg_cands {
        if add_unique_split_head(
            out,
            SplitHead {
                custom_surface: None,
                norm_lower: rc,
                is_proper: false,
            },
        ) {
            added_reg += 1;
            if added_reg >= 2 {
                break;
            }
        }
    }
    if let Some(bp) = best_proper {
        if out.len() < 3 {
            add_unique_split_head(out, bp);
        }
    }
}

fn find_oov_proper_split_head<'a>(
    query_head: &str,
    slot: &SlotDrafts,
    tok: &SpanToken,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) -> Option<SplitHead> {
    let q_chars = u16_len(query_head);
    for &suf in SPLIT_HEAD_PROPER_SUFS {
        let suf_chars = u16_len(suf);
        if q_chars < suf_chars + 2 || !query_head.ends_with(suf) {
            continue;
        }
        let stem_low = &query_head[..query_head.len() - suf.len()];
        let is_acronym = knowledge.is_known_acronym_stem(stem_low);
        if is_acronym || knowledge.is_known_proper_or_acronym_stem(stem_low) {
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
            return Some(SplitHead {
                custom_surface: Some(cand_surf),
                norm_lower: cand_low,
                is_proper: true,
            });
        }
    }
    None
}

#[allow(clippy::too_many_arguments)]
fn format_split_surface<'a>(
    slot: &SlotDrafts,
    tok: &SpanToken,
    head: &SplitHead,
    clitic_lower: &str,
    split_lower: &str,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) -> String {
    let Some(ref custom) = head.custom_surface else {
        return truecase_slot(slot, tok, split_lower, knowledge, morph, dynamic, scratch);
    };
    let tail_surf = if slot.sentence_all_caps {
        tr_upper(clitic_lower)
    } else {
        String::from(clitic_lower)
    };
    let mut s = String::with_capacity(custom.len() + 1 + tail_surf.len());
    s.push_str(custom);
    s.push(' ');
    s.push_str(&tail_surf);
    s
}

fn add_unique_split_head(list: &mut Vec<SplitHead>, cand: SplitHead) -> bool {
    if list.iter().any(|sh| sh.norm_lower == cand.norm_lower) {
        return false;
    }
    list.push(cand);
    true
}

fn realize_valid_or_attested_stem<'a>(
    raw_head: &str,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) -> Option<String> {
    if let Some(real) = morph.realize_valid_word_or_null(raw_head, dynamic, scratch) {
        return Some(real);
    }
    if u16_len(raw_head) >= 3 && knowledge.clean_lm.unigram_count(raw_head) >= 4 {
        return Some(String::from(raw_head));
    }
    None
}

// =========================================================================
// 2:1 and 3:1 Multi-Token Merges
// =========================================================================

#[allow(clippy::too_many_arguments)]
pub fn add_multi_token_merges<'a>(
    gen: &SpanCandidateGenerator<'a>,
    tokens: &[SpanToken],
    i: usize,
    cap_here: bool,
    sentence_all_caps: bool,
    tok_case_ctx: u32,
    out_arcs: &mut Vec<SpanArc>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) {
    let n = tokens.len();
    let noisy_only_ctx = tok_case_ctx & CTX_SENT_TITLE_NOISY;

    if i + 2 < n {
        add_three_token_merges(
            gen,
            tokens,
            i,
            cap_here,
            sentence_all_caps,
            noisy_only_ctx,
            out_arcs,
            dynamic,
            scratch,
        );
    }
    if i + 1 >= n {
        return;
    }

    let t1 = &tokens[i];
    let t2 = &tokens[i + 1];
    let orphans_third = is_orphaning_adjacent_third_token(tokens, i);
    let syntactically_blocked = is_syntactically_blocked_merge(tokens, i, &t1.lower, &t2.lower);

    if !orphans_third && !syntactically_blocked {
        if let Some(me2) = gen.knowledge.view.lookup_p2(&t1.lower, &t2.lower) {
            let raw_cooccur = me2.cooccur_count.max(me2.total_merged_count());
            for idx in 0..me2.len() {
                let (tc_norm, tc_cnt, merge_prob) = me2.target(idx);
                let surf = truecase_from_first(
                    gen,
                    t1,
                    tc_norm,
                    cap_here,
                    sentence_all_caps,
                    noisy_only_ctx,
                    dynamic,
                    scratch,
                );
                let mut arc = gen.build_direct_arc(
                    i,
                    i + 2,
                    surf,
                    String::from(tc_norm),
                    SRC_PARALLEL_MERGE,
                    merge_prob,
                    ln_f64(1.0 + (tc_cnt as f64)) as f32,
                    0.0,
                    0.15,
                    0,
                    dynamic,
                    scratch,
                );
                arc.raw_parallel_total = raw_cooccur;
                out_arcs.push(arc);
            }
        }
    }

    add_detached_numeral_two_token_merge(gen, i, t1, t2, out_arcs, dynamic, scratch);
    add_detached_word_two_token_merges(
        gen,
        i,
        t1,
        t2,
        cap_here,
        sentence_all_caps,
        noisy_only_ctx,
        syntactically_blocked,
        out_arcs,
        dynamic,
        scratch,
    );
}

#[allow(clippy::too_many_arguments)]
fn truecase_from_first<'a>(
    gen: &SpanCandidateGenerator<'a>,
    t1: &SpanToken,
    norm_lower: &str,
    cap_here: bool,
    sentence_all_caps: bool,
    noisy_only_ctx: u32,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) -> String {
    gen.knowledge.truecase(
        norm_lower,
        t1.shape,
        &t1.surface,
        cap_here,
        sentence_all_caps,
        &gen.morph,
        noisy_only_ctx,
        dynamic,
        scratch,
    )
}

#[allow(clippy::too_many_arguments)]
fn add_three_token_merges<'a>(
    gen: &SpanCandidateGenerator<'a>,
    tokens: &[SpanToken],
    i: usize,
    cap_here: bool,
    sentence_all_caps: bool,
    noisy_only_ctx: u32,
    out_arcs: &mut Vec<SpanArc>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) {
    let t1 = &tokens[i];
    let t2 = &tokens[i + 1];
    let t3 = &tokens[i + 2];

    if let Some(me3) = gen.knowledge.view.lookup_p3(&t1.lower, &t2.lower, &t3.lower) {
        for idx in 0..me3.len() {
            let (tc_norm, tc_cnt, merge_prob) = me3.target(idx);
            let surf = truecase_from_first(
                gen,
                t1,
                tc_norm,
                cap_here,
                sentence_all_caps,
                noisy_only_ctx,
                dynamic,
                scratch,
            );
            out_arcs.push(gen.build_direct_arc(
                i,
                i + 3,
                surf,
                String::from(tc_norm),
                SRC_PARALLEL_MERGE,
                merge_prob,
                ln_f64(1.0 + (tc_cnt as f64)) as f32,
                0.0,
                0.20,
                0,
                dynamic,
                scratch,
            ));
        }
    }

    if (t2.surface == "'" || t2.surface == "’")
        && !t1.is_immutable()
        && is_detached_word_apostrophe_suffix(&t3.lower)
    {
        let mut joined_low = String::with_capacity(t1.lower.len() + 1 + t3.lower.len());
        joined_low.push_str(&t1.lower);
        joined_low.push('\'');
        joined_low.push_str(&t3.lower);
        let joined_surf = if t1.token_type == TokenType::Number {
            let mut s = String::with_capacity(t1.surface.len() + 1 + t3.lower.len());
            s.push_str(&t1.surface);
            s.push('\'');
            s.push_str(&t3.lower);
            s
        } else {
            truecase_from_first(
                gen,
                t1,
                &joined_low,
                cap_here,
                sentence_all_caps,
                noisy_only_ctx,
                dynamic,
                scratch,
            )
        };
        out_arcs.push(gen.build_direct_arc(
            i,
            i + 3,
            joined_surf,
            joined_low,
            SRC_NUMERAL_JOIN | SRC_OOV_APOSTROPHE,
            0.0,
            0.0,
            0.92,
            0.12,
            0,
            dynamic,
            scratch,
        ));
    }

    if t2.surface == "." && is_all_digits(&t1.surface) && is_detached_num_suffix(&t3.lower) {
        if let Some(ord) = build_ordinal_inflected(&t1.surface, &t3.lower) {
            out_arcs.push(gen.build_direct_arc(
                i,
                i + 3,
                ord.clone(),
                ord,
                SRC_NUMERAL_JOIN,
                0.0,
                0.0,
                0.90,
                0.15,
                0,
                dynamic,
                scratch,
            ));
        }
    }

    if t2.surface == "$"
        && t2.adjacent_to_prev
        && t3.adjacent_to_prev
        && !t1.is_immutable()
        && !t3.is_immutable()
    {
        let mut leet_joined = String::with_capacity(t1.lower.len() + 2 + t3.lower.len());
        leet_joined.push_str(&t1.lower);
        leet_joined.push('ş');
        leet_joined.push_str(&t3.lower);
        add_dollar_leet_three_token_merge(
            gen,
            i,
            t1,
            &leet_joined,
            cap_here,
            sentence_all_caps,
            noisy_only_ctx,
            out_arcs,
            dynamic,
            scratch,
        );
    }
}

#[allow(clippy::too_many_arguments)]
fn add_dollar_leet_three_token_merge<'a>(
    gen: &SpanCandidateGenerator<'a>,
    i: usize,
    t1: &SpanToken,
    leet_joined: &str,
    cap_here: bool,
    sentence_all_caps: bool,
    noisy_only_ctx: u32,
    out_arcs: &mut Vec<SpanArc>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) {
    if let Some(real) = gen.morph.realize_valid_word_or_null(leet_joined, dynamic, scratch) {
        let surf = truecase_from_first(
            gen,
            t1,
            &real,
            cap_here,
            sentence_all_caps,
            noisy_only_ctx,
            dynamic,
            scratch,
        );
        out_arcs.push(gen.build_direct_arc(
            i,
            i + 3,
            surf,
            real,
            SRC_COMPOUND_MERGE,
            0.0,
            0.0,
            0.90,
            0.20,
            0,
            dynamic,
            scratch,
        ));
        return;
    }
    let lj_chars = u16_len(leet_joined);
    for &qt in QUESTION_TAILS {
        let qt_chars = u16_len(qt);
        if lj_chars <= qt_chars + 2 {
            continue;
        }
        let Some(head_probe) = leet_joined.strip_suffix(qt) else {
            continue;
        };
        if let Some(head_real) = gen.morph.realize_valid_word_or_null(head_probe, dynamic, scratch) {
            let clitic = harmonize_question_clitic(qt, last_turkish_vowel(&head_real));
            let mut split_low = String::with_capacity(head_real.len() + 1 + clitic.len());
            split_low.push_str(&head_real);
            split_low.push(' ');
            split_low.push_str(&clitic);
            let surf = truecase_from_first(
                gen,
                t1,
                &split_low,
                cap_here,
                sentence_all_caps,
                noisy_only_ctx,
                dynamic,
                scratch,
            );
            out_arcs.push(gen.build_direct_arc(
                i,
                i + 3,
                surf,
                split_low,
                SRC_COMPOUND_MERGE | SRC_CLITIC_SPLIT,
                0.0,
                0.0,
                0.90,
                0.20,
                0,
                dynamic,
                scratch,
            ));
            return;
        }
    }
}

fn is_orphaning_adjacent_third_token(tokens: &[SpanToken], i: usize) -> bool {
    if i + 2 >= tokens.len() {
        return false;
    }
    let sep = tokens[i + 1].surface.as_str();
    if sep != "$" && sep != "'" && sep != "’" {
        return false;
    }
    let t3 = &tokens[i + 2];
    t3.adjacent_to_prev || is_detached_word_apostrophe_suffix(&t3.lower)
}

fn add_detached_numeral_two_token_merge<'a>(
    gen: &SpanCandidateGenerator<'a>,
    i: usize,
    t1: &SpanToken,
    t2: &SpanToken,
    out_arcs: &mut Vec<SpanArc>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) {
    if !is_detached_num_suffix(&t2.lower) {
        return;
    }
    if t1.token_type == TokenType::Number {
        let num_surf = t1.surface.as_str();
        if let Some(before_dot) = num_surf.strip_suffix('.') {
            if !before_dot.is_empty() {
                if let Some(ord) = build_ordinal_inflected(before_dot, &t2.lower) {
                    out_arcs.push(gen.build_direct_arc(
                        i,
                        i + 2,
                        ord.clone(),
                        ord,
                        SRC_NUMERAL_JOIN,
                        0.0,
                        0.0,
                        0.90,
                        0.15,
                        0,
                        dynamic,
                        scratch,
                    ));
                }
            }
        } else if is_numeric_surface(num_surf) {
            let mut joined = String::with_capacity(num_surf.len() + 1 + t2.lower.len());
            joined.push_str(num_surf);
            joined.push('\'');
            joined.push_str(&t2.lower);
            out_arcs.push(gen.build_direct_arc(
                i,
                i + 2,
                joined.clone(),
                joined,
                SRC_NUMERAL_JOIN,
                0.0,
                0.0,
                0.85,
                0.15,
                0,
                dynamic,
                scratch,
            ));
        }
        return;
    }
    if !t1.is_immutable() && t1.contains_digit() && u16_len(&t1.surface) <= 6 {
        let stem_surf = if is_letters_then_digits(&t1.lower) {
            tr_upper(&t1.lower)
        } else {
            t1.surface.clone()
        };
        let mut surf = String::with_capacity(stem_surf.len() + 1 + t2.lower.len());
        surf.push_str(&stem_surf);
        surf.push('\'');
        surf.push_str(&t2.lower);
        let mut low = String::with_capacity(t1.lower.len() + 1 + t2.lower.len());
        low.push_str(&t1.lower);
        low.push('\'');
        low.push_str(&t2.lower);
        out_arcs.push(gen.build_direct_arc(
            i,
            i + 2,
            surf,
            low,
            SRC_NUMERAL_JOIN | SRC_OOV_APOSTROPHE,
            0.0,
            0.0,
            0.84,
            0.16,
            0,
            dynamic,
            scratch,
        ));
    }
}

#[allow(clippy::too_many_arguments)]
fn add_detached_word_two_token_merges<'a>(
    gen: &SpanCandidateGenerator<'a>,
    i: usize,
    t1: &SpanToken,
    t2: &SpanToken,
    cap_here: bool,
    sentence_all_caps: bool,
    noisy_only_ctx: u32,
    syntactically_blocked: bool,
    out_arcs: &mut Vec<SpanArc>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) {
    if t1.is_immutable()
        || t2.is_immutable()
        || t2.adjacent_to_prev
        || t1.contains_digit()
        || t2.contains_digit()
    {
        return;
    }
    let t1_low = t1.lower.as_str();
    let t2_low = t2.lower.as_str();
    let t1_surf_chars = u16_len(&t1.surface);

    let is_short_upper_acro = t1.shape == CasingShape::Upper
        && !sentence_all_caps
        && (2..=5).contains(&t1_surf_chars)
        && !gen.morph.has_regular_strict_analysis(t1_low, dynamic, scratch);
    let is_known_acronym = gen.knowledge.is_known_acronym_stem(t1_low) || is_short_upper_acro;
    let is_proper_or_acronym = is_known_acronym
        || gen.knowledge.is_known_proper_or_acronym_stem(t1_low)
        || gen.morph.is_strict_proper_or_abbr_only(t1_low, dynamic, scratch)
        || (!cap_here
            && t1.shape == CasingShape::Title
            && !gen.morph.has_regular_strict_analysis(t1_low, dynamic, scratch));

    let is_loc_or_abl = is_loc_or_abl_particle(t2_low);
    let t1_chars = u16_len(t1_low);

    if is_loc_or_abl && t1_chars >= 2 {
        let stem_real = gen
            .morph
            .realize_valid_word_or_null(t1_low, dynamic, scratch)
            .unwrap_or_else(|| String::from(t1_low));
        let assim = assimilate_loc_or_abl_suffix(&stem_real, u16_len(t2_low) == 3);
        let mut cand_merged = String::with_capacity(stem_real.len() + assim.len());
        cand_merged.push_str(&stem_real);
        cand_merged.push_str(assim);
        let closed_cnt = gen.knowledge.clean_lm.unigram_count(&cand_merged);
        let is_inf_prog =
            u16_len(t2_low) == 2 && (stem_real.ends_with("mak") || stem_real.ends_with("mek"));

        if gen.morph.has_regular_strict_analysis(&cand_merged, dynamic, scratch)
            && !is_blocked_locative_merge(&cand_merged)
            && (is_inf_prog || closed_cnt >= 8)
        {
            let surf = truecase_from_first(
                gen,
                t1,
                &cand_merged,
                cap_here,
                sentence_all_caps,
                noisy_only_ctx,
                dynamic,
                scratch,
            );
            let mut raw_cat = String::with_capacity(t1_low.len() + t2_low.len());
            raw_cat.push_str(t1_low);
            raw_cat.push_str(t2_low);
            let diffs = count_diacritic_diffs(&raw_cat, &cand_merged);
            out_arcs.push(gen.build_direct_arc(
                i,
                i + 2,
                surf,
                cand_merged,
                SRC_COMPOUND_MERGE,
                0.0,
                if closed_cnt > 0 {
                    ln_f64(1.0 + (closed_cnt as f64)) as f32
                } else {
                    0.0
                },
                if is_inf_prog { 0.72 } else { 0.58 },
                0.20,
                diffs,
                dynamic,
                scratch,
            ));
        }

        if is_proper_or_acronym {
            let prop_suf = assimilate_loc_or_abl_suffix(t1_low, u16_len(t2_low) == 3);
            let mut ap_low = String::with_capacity(t1_low.len() + 1 + prop_suf.len());
            ap_low.push_str(t1_low);
            ap_low.push('\'');
            ap_low.push_str(prop_suf);
            let ap_surf = if is_known_acronym {
                let mut s = tr_upper(t1_low);
                s.push('\'');
                s.push_str(prop_suf);
                s
            } else {
                truecase_from_first(
                    gen,
                    t1,
                    &ap_low,
                    cap_here,
                    sentence_all_caps,
                    noisy_only_ctx,
                    dynamic,
                    scratch,
                )
            };
            out_arcs.push(gen.build_direct_arc(
                i,
                i + 2,
                ap_surf,
                ap_low,
                SRC_OOV_APOSTROPHE | SRC_COMPOUND_MERGE,
                0.0,
                0.0,
                0.76,
                0.18,
                0,
                dynamic,
                scratch,
            ));
        }
    }

    if matches!(t2_low, "ki" | "kü" | "kı") && t1_chars >= 3 {
        let stem_real = gen
            .morph
            .realize_valid_word_or_null(t1_low, dynamic, scratch)
            .unwrap_or_else(|| String::from(t1_low));
        for suf in ["ki", "kü"] {
            let mut ki_var = String::with_capacity(stem_real.len() + suf.len());
            ki_var.push_str(&stem_real);
            ki_var.push_str(suf);
            if is_lexical_ki_word(&ki_var)
                || gen.morph.has_regular_strict_analysis(&ki_var, dynamic, scratch)
            {
                let closed_cnt = gen.knowledge.clean_lm.unigram_count(&ki_var);
                let surf = truecase_from_first(
                    gen,
                    t1,
                    &ki_var,
                    cap_here,
                    sentence_all_caps,
                    noisy_only_ctx,
                    dynamic,
                    scratch,
                );
                let mut raw_cat = String::with_capacity(t1_low.len() + t2_low.len());
                raw_cat.push_str(t1_low);
                raw_cat.push_str(t2_low);
                let diffs = count_diacritic_diffs(&raw_cat, &ki_var);
                out_arcs.push(gen.build_direct_arc(
                    i,
                    i + 2,
                    surf,
                    ki_var,
                    SRC_COMPOUND_MERGE,
                    0.0,
                    if closed_cnt > 0 {
                        ln_f64(1.0 + (closed_cnt as f64)) as f32
                    } else {
                        0.0
                    },
                    0.78,
                    0.18,
                    diffs,
                    dynamic,
                    scratch,
                ));
                break;
            }
        }
    }

    if !is_loc_or_abl && t2_low != "ki" && t2_low != "kü" {
        if !syntactically_blocked {
            let mut concat_raw = String::with_capacity(t1_low.len() + t2_low.len());
            concat_raw.push_str(t1_low);
            concat_raw.push_str(t2_low);
            if let Some(realized) =
                gen.morph.realize_valid_word_or_null(&concat_raw, dynamic, scratch)
            {
                if !realized.contains("şey") && realized != "yada" && realized != "şuan" {
                    let is_closed_pref = CLOSED_COMPOUND_PREFIXES
                        .iter()
                        .any(|&p| realized.starts_with(p));
                    let is_split_ability = (t1_low.ends_with('a') || t1_low.ends_with('e'))
                        && (t2_low.starts_with("bil") || t2.ascii.starts_with("bil"));
                    let is_split_copula = is_split_copula_tail(t2_low)
                        && gen.morph.has_regular_strict_analysis(&realized, dynamic, scratch)
                        && (!(t2_low == "sa" || t2_low == "se")
                            || gen.morph.summarize_morph(t1_low, dynamic, scratch).syntax_class
                                == HEAD_SYNTAX_FINITE_OR_CONVERB);
                    let closed_freq = gen.knowledge.clean_lm.unigram_count(&realized);

                    if is_closed_pref || is_split_ability || is_split_copula || closed_freq >= 80 {
                        let surf = truecase_from_first(
                            gen,
                            t1,
                            &realized,
                            cap_here,
                            sentence_all_caps,
                            noisy_only_ctx,
                            dynamic,
                            scratch,
                        );
                        let diffs = count_diacritic_diffs(&concat_raw, &realized);
                        out_arcs.push(gen.build_direct_arc(
                            i,
                            i + 2,
                            surf,
                            realized,
                            SRC_COMPOUND_MERGE,
                            0.0,
                            if closed_freq > 0 {
                                ln_f64(1.0 + (closed_freq as f64)) as f32
                            } else {
                                0.0
                            },
                            0.85,
                            0.20,
                            diffs,
                            dynamic,
                            scratch,
                        ));
                    }
                }
            }
        }

        if is_proper_or_acronym && t1_chars >= 2 && is_detached_proper_nonloc_suffix(t2_low) {
            let deriv_suf = t2_low.starts_with('l') && t2_low != "la" && t2_low != "le";
            if deriv_suf && !is_known_acronym {
                let mut closed_low = String::with_capacity(t1_low.len() + t2_low.len());
                closed_low.push_str(t1_low);
                closed_low.push_str(t2_low);
                let closed_surf = if !sentence_all_caps && t1.shape == CasingShape::Mixed {
                    let mut s = String::with_capacity(t1.surface.len() + t2_low.len());
                    s.push_str(&t1.surface);
                    s.push_str(t2_low);
                    s
                } else {
                    tr_capitalize(&closed_low)
                };
                out_arcs.push(gen.build_direct_arc(
                    i,
                    i + 2,
                    closed_surf,
                    closed_low,
                    SRC_COMPOUND_MERGE,
                    0.0,
                    0.0,
                    0.78,
                    0.18,
                    0,
                    dynamic,
                    scratch,
                ));
            } else {
                let mut ap_low = String::with_capacity(t1_low.len() + 1 + t2_low.len());
                ap_low.push_str(t1_low);
                ap_low.push('\'');
                ap_low.push_str(t2_low);
                let ap_surf = if is_known_acronym {
                    let mut s = tr_upper(t1_low);
                    s.push('\'');
                    s.push_str(t2_low);
                    s
                } else {
                    truecase_from_first(
                        gen,
                        t1,
                        &ap_low,
                        cap_here,
                        sentence_all_caps,
                        noisy_only_ctx,
                        dynamic,
                        scratch,
                    )
                };
                out_arcs.push(gen.build_direct_arc(
                    i,
                    i + 2,
                    ap_surf,
                    ap_low,
                    SRC_OOV_APOSTROPHE | SRC_COMPOUND_MERGE,
                    0.0,
                    0.0,
                    if is_known_acronym { 0.84 } else { 0.78 },
                    0.18,
                    0,
                    dynamic,
                    scratch,
                ));
            }
        }
    }
}

// =========================================================================
// Phonology & Token Shape Helpers
// =========================================================================

fn query_for_ascii_upper_i(tok: &SpanToken, raw_head: &str) -> String {
    if tok.has_upper_ascii_ambiguous_i() {
        raw_head.replace('ı', "i")
    } else {
        String::from(raw_head)
    }
}

fn ends_with_locative_ki(low: &str) -> bool {
    low.ends_with("daki") || low.ends_with("deki") || low.ends_with("taki") || low.ends_with("teki")
}

fn harmonized_de_da_clitic(stem_lower: &str) -> &'static str {
    if is_front_vowel(last_turkish_vowel(stem_lower)) {
        "de"
    } else {
        "da"
    }
}

fn assimilate_loc_or_abl_suffix(stem_lower: &str, ablative: bool) -> &'static str {
    let front = is_front_vowel(last_turkish_vowel(stem_lower));
    let voiceless = stem_lower
        .chars()
        .next_back()
        .is_some_and(TurkishAlphabet::is_voiceless_consonant);
    match (ablative, voiceless, front) {
        (false, false, true) => "de",
        (false, false, false) => "da",
        (false, true, true) => "te",
        (false, true, false) => "ta",
        (true, false, true) => "den",
        (true, false, false) => "dan",
        (true, true, true) => "ten",
        (true, true, false) => "tan",
    }
}

fn is_syntactically_blocked_merge(
    tokens: &[SpanToken],
    i: usize,
    t1_low: &str,
    t2_low: &str,
) -> bool {
    if i == 0 {
        return false;
    }
    let prev = &tokens[i - 1];
    let prev_low = prev.lower.as_str();
    if is_quantified_count_noun(t1_low)
        && (prev.token_type == TokenType::Number || is_count_modifier(prev_low))
    {
        return true;
    }
    if t1_low == "bu"
        && t2_low.starts_with("gün")
        && u16_len(prev_low) >= 5
        && PARTICIPLE_SUFFIXES.iter().any(|&s| prev_low.ends_with(s))
    {
        return true;
    }
    (t1_low == "de" || t1_low == "da")
        && t1_low == t2_low
        && (prev_low.ends_with("mak") || prev_low.ends_with("mek"))
}

pub fn harmonize_question_clitic(raw_qt: &str, stem_vowel: char) -> String {
    let hv = match stem_vowel {
        'e' | 'i' => 'i',
        'ö' | 'ü' => 'ü',
        'o' | 'u' => 'u',
        _ => 'ı',
    };
    if u16_len(raw_qt) == 2 {
        let mut s = String::with_capacity(4);
        s.push('m');
        s.push(hv);
        return s;
    }
    let asc = to_ascii(raw_qt);
    match asc.as_str() {
        "miyim" | "muyum" => alloc::format!("m{hv}y{hv}m"),
        "misin" | "musun" => alloc::format!("m{hv}s{hv}n"),
        "miyiz" | "muyuz" => alloc::format!("m{hv}y{hv}z"),
        "misiniz" | "musunuz" => alloc::format!("m{hv}s{hv}n{hv}z"),
        "miydi" | "muydu" => alloc::format!("m{hv}yd{hv}"),
        "miydim" | "muydum" => alloc::format!("m{hv}yd{hv}m"),
        "miydin" | "muydun" => alloc::format!("m{hv}yd{hv}n"),
        "miydik" | "muyduk" => alloc::format!("m{hv}yd{hv}k"),
        "miydiniz" | "muydunuz" => alloc::format!("m{hv}yd{hv}n{hv}z"),
        "miymis" | "muymus" => alloc::format!("m{hv}ym{hv}ş"),
        _ => String::from(raw_qt),
    }
}

fn find_single_camel_boundary(surface: &str) -> Option<usize> {
    let mut cut: Option<usize> = None;
    let mut prev_lower = false;
    for (idx, cur) in surface.chars().enumerate() {
        if !cur.is_alphabetic() {
            return None;
        }
        if idx > 0 && cur.is_uppercase() {
            if !prev_lower || cut.is_some() {
                return None;
            }
            cut = Some(idx);
        }
        prev_lower = cur.is_lowercase();
    }
    cut
}

fn is_numeric_surface(s: &str) -> bool {
    let Some(last) = s.chars().next_back() else {
        return false;
    };
    if !is_java_digit(last) {
        return false;
    }
    let mut seen_digit = false;
    for c in s.chars() {
        if is_java_digit(c) {
            seen_digit = true;
        } else if !matches!(c, '.' | ',' | '%' | '/' | ':') {
            return false;
        }
    }
    seen_digit
}

fn is_letters_then_digits(s: &str) -> bool {
    let chars: Vec<char> = s.chars().collect();
    if chars.len() < 2 {
        return false;
    }
    let mut i = 0usize;
    while i < chars.len() && chars[i].is_alphabetic() {
        i += 1;
    }
    if i == 0 || i > 4 || i == chars.len() {
        return false;
    }
    chars[i..].iter().copied().all(is_java_digit)
}
