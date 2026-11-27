use alloc::string::String;
use alloc::vec::Vec;
use zemberek_core::TurkishAlphabet;
use zemberek_morph::{AnalyzerScratch, DynamicStemTrie};
use zemberek_tok::{CasingShape, SpanToken};
use crate::knowledge::NormKnowledge;
use crate::morph_support::{strips_explicit_stem_diacritic, LatticeMorphSupport};
use crate::single_rewrite::{squeeze_runs, squeeze_trailing_run};
use crate::types::{
    compute_weighted_edit_cost, count_diacritic_diffs, drop_last_chars, tr_capitalize, tr_lower,
    tr_upper, u16_len, SlotDrafts, SRC_CLITIC_SPLIT, SRC_ELONGATION, SRC_FORMAL_MORPH,
    SRC_SHORTHAND,
};

const COMPOSITIONAL_SHORTHAND_STEMS: &[(&str, &[&str])] = &[
    ("tskr", &["teşekkür"]),
    ("tşkr", &["teşekkür"]),
    ("tsk", &["teşekkür"]),
    ("tşk", &["teşekkür"]),
    ("slm", &["selam"]),
    ("mrhb", &["merhaba"]),
    ("mrb", &["merhaba"]),
    ("msj", &["mesaj"]),
    ("cvp", &["cevap", "cevab"]),
    ("tmm", &["tamam"]),
    ("knka", &["kanka"]),
    ("knk", &["kanka"]),
    ("cnm", &["canım"]),
    ("krdş", &["kardeş"]),
    ("krds", &["kardeş"]),
    ("arkdş", &["arkadaş"]),
    ("arkds", &["arkadaş"]),
    ("tlfn", &["telefon"]),
    ("tlf", &["telefon"]),
    ("tbrk", &["tebrik"]),
    ("prblm", &["problem"]),
    ("prgrm", &["program"]),
    ("kmpny", &["kampanya"]),
    ("blm", &["bölüm"]),
    ("fyt", &["fiyat"]),
    ("rsm", &["resim", "resm"]),
    ("yrdm", &["yardım"]),
    ("zmn", &["zaman"]),
    ("insn", &["insan"]),
];

pub fn curated_chat_shorthand(key: &str) -> Option<&'static str> {
    match key {
        "slm" => Some("selam"),
        "slmlar" | "slmlr" => Some("selamlar"),
        "mrb" | "mrhb" => Some("merhaba"),
        "mrblar" | "mrblr" | "mrhblr" => Some("merhabalar"),
        "nbr" => Some("ne haber"),
        "hg" => Some("hoş geldin"),
        "hb" => Some("hoş bulduk"),
        "kib" => Some("kendine iyi bak"),
        "aeo" => Some("Allah'a emanet ol"),
        "grsrz" | "grşrz" | "grşz" | "grsz" | "görşrz" => Some("görüşürüz"),
        "tsk" | "tşk" | "tskr" | "tşkr" => Some("teşekkür"),
        "tskler" | "tşkler" | "tsklr" | "tşklr" | "tskrler" | "tşkrler" | "tskrlr" | "tşkrlr" => {
            Some("teşekkürler")
        }
        "ltfn" | "ltn" | "lütfn" | "lutfn" => Some("lütfen"),
        "saol" | "sagol" => Some("sağ ol"),
        "saolun" | "sagolun" => Some("sağ olun"),
        "saolasın" | "sagolasin" => Some("sağ olasın"),
        "eyw" | "eyv" | "eyvlh" | "eywllh" => Some("eyvallah"),
        "inş" | "ins" | "inşllh" | "insllh" => Some("inşallah"),
        "mşllh" | "msllh" => Some("maşallah"),
        "tbrk" => Some("tebrik"),
        "tbrkler" | "tbrklr" => Some("tebrikler"),
        "syglr" => Some("saygılar"),
        "svglr" | "svgler" => Some("sevgiler"),
        "nsl" => Some("nasıl"),
        "nslsn" | "nslsın" | "naslsn" => Some("nasılsın"),
        "nslsnz" | "naslsnz" => Some("nasılsınız"),
        "tmm" => Some("tamam"),
        "tmmdır" | "tmmdir" | "tmmdr" => Some("tamamdır"),
        "evt" => Some("evet"),
        "hyr" => Some("hayır"),
        "hyrls" => Some("hayırlısı"),
        "cnm" => Some("canım"),
        "cnmsın" | "cnmsin" => Some("canımsın"),
        "knk" | "knka" => Some("kanka"),
        "knkm" => Some("kankam"),
        "krdş" | "krds" => Some("kardeş"),
        "krdşm" | "krdsm" => Some("kardeşim"),
        "krdşler" | "krdslr" => Some("kardeşler"),
        "arkdş" | "arkds" => Some("arkadaş"),
        "arkdşm" | "arkdsm" => Some("arkadaşım"),
        "arkdslr" | "arkdşlr" | "arkadaslr" | "arkadaşlr" => Some("arkadaşlar"),
        "msj" => Some("mesaj"),
        "msjlar" | "msjlr" => Some("mesajlar"),
        "cvp" => Some("cevap"),
        "cvplar" | "cvplr" => Some("cevaplar"),
        "tlf" | "tlfn" => Some("telefon"),
        "yrdm" => Some("yardım"),
        "blmyrm" | "bilmyrm" => Some("bilmiyorum"),
        "blmyrsn" => Some("bilmiyorsun"),
        "svyrm" | "sevyrm" => Some("seviyorum"),
        "svmyrm" => Some("sevmiyorum"),
        "istyrm" | "istym" => Some("istiyorum"),
        "istmyrm" => Some("istemiyorum"),
        "glyrm" => Some("geliyorum"),
        "gdyrm" => Some("gidiyorum"),
        "bklm" | "bakalm" => Some("bakalım"),
        "glcm" | "glcem" => Some("geleceğim"),
        "glmycm" => Some("gelmeyeceğim"),
        "knşlm" | "knslm" => Some("konuşalım"),
        "ozldm" | "özldm" => Some("özledim"),
        "anlmdm" => Some("anlamadım"),
        "anlmyrm" => Some("anlamıyorum"),
        "unttm" => Some("unuttum"),
        "dşnyrm" | "dsnyrm" => Some("düşünüyorum"),
        "yrn" => Some("yarın"),
        "zmn" => Some("zaman"),
        "gzl" => Some("güzel"),
        "kdr" => Some("kadar"),
        "ztn" => Some("zaten"),
        "glb" => Some("galiba"),
        "hrlde" | "heralde" => Some("herhalde"),
        "fln" | "felan" => Some("falan"),
        "deil" | "deıl" | "dgl" | "dğl" => Some("değil"),
        "dglm" | "dğlm" | "deilim" => Some("değilim"),
        "ii" => Some("iyi"),
        "çk" => Some("çok"),
        "bi" | "bı" => Some("bir"),
        "bn" => Some("ben"),
        "bnm" => Some("benim"),
        "snn" => Some("senin"),
        "bna" => Some("bana"),
        "sna" => Some("sana"),
        "bni" => Some("beni"),
        "sni" => Some("seni"),
        "bnde" => Some("ben de"),
        "snde" => Some("sen de"),
        _ => None,
    }
}

#[inline]
pub fn is_curated_shorthand(lower: &str) -> bool {
    curated_chat_shorthand(lower).is_some()
}

fn vowelless_suffix_expansions(raw_tail: &str) -> Option<&'static [&'static str]> {
    match raw_tail {
        "lr" => Some(&["ler", "lar"]),
        "lrd" => Some(&["lerde", "larda"]),
        "lrdn" => Some(&["lerden", "lardan"]),
        "lrm" => Some(&["lerim", "larım"]),
        "lrmz" => Some(&["lerimiz", "larımız"]),
        "lrn" => Some(&["lerin", "ların", "lerini", "larını"]),
        "m" => Some(&["im", "ım", "um", "üm", "m"]),
        "mz" => Some(&["imiz", "ımız", "umuz", "ümüz", "miz", "mız"]),
        "n" => Some(&["in", "ın", "un", "ün", "n"]),
        "nz" => Some(&["iniz", "ınız", "unuz", "ünüz", "niz", "nız"]),
        "dr" => Some(&["dir", "dır", "dur", "dür", "tir", "tır", "tur", "tür"]),
        "dn" => Some(&["den", "dan", "ten", "tan"]),
        "d" => Some(&["de", "da", "te", "ta"]),
        "sn" => Some(&["sin", "sın", "sun", "sün"]),
        _ => None,
    }
}

fn is_voweled_shorthand_suffix(s: &str) -> bool {
    matches!(
        s,
        "ler"
            | "lar"
            | "lerde"
            | "larda"
            | "lerden"
            | "lardan"
            | "leri"
            | "ları"
            | "lerin"
            | "ların"
            | "lerim"
            | "larım"
            | "lerimiz"
            | "larımız"
            | "im"
            | "ım"
            | "um"
            | "üm"
            | "imiz"
            | "ımız"
            | "umuz"
            | "ümüz"
            | "in"
            | "ın"
            | "un"
            | "ün"
            | "iniz"
            | "ınız"
            | "unuz"
            | "ünüz"
            | "dir"
            | "dır"
            | "dur"
            | "dür"
            | "tir"
            | "tır"
            | "tur"
            | "tür"
            | "da"
            | "de"
            | "ta"
            | "te"
            | "dan"
            | "den"
            | "tan"
            | "ten"
            | "a"
            | "e"
            | "ya"
            | "ye"
            | "ı"
            | "i"
            | "u"
            | "ü"
            | "yı"
            | "yi"
            | "yu"
            | "yü"
            | "la"
            | "le"
            | "yla"
            | "yle"
            | "sın"
            | "sin"
            | "sun"
            | "sün"
    )
}

pub fn add_shorthand_candidates<'a>(
    slot: &mut SlotDrafts,
    tok: &SpanToken,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) {
    let low = tok.lower.as_str();
    let clen = u16_len(low);
    if !(2..=16).contains(&clen) || low.contains('\'') {
        return;
    }

    let sq_trail = squeeze_trailing_run(low);
    let sq_all = squeeze_runs(low, 1);
    let base_query: &str = if u16_len(&sq_trail) >= 3 {
        &sq_trail
    } else {
        low
    };

    let mut keys: Vec<&str> = Vec::with_capacity(3);
    keys.push(low);
    if sq_trail != low {
        keys.push(&sq_trail);
        if sq_all != sq_trail {
            keys.push(&sq_all);
        }
    }

    // 1. Exact curated conversational shorthands
    for &k in &keys {
        if let Some(exact_target) = curated_chat_shorthand(k) {
            if exact_target != low {
                let morph_or_split = if exact_target.contains(' ') {
                    SRC_CLITIC_SPLIT
                } else {
                    SRC_FORMAL_MORPH
                };
                let elong_flag = if k != low { SRC_ELONGATION } else { 0 };
                emit_shorthand(
                    slot,
                    tok,
                    knowledge,
                    morph,
                    exact_target,
                    SRC_SHORTHAND | morph_or_split | elong_flag,
                    0.94,
                    dynamic,
                    scratch,
                );
            }
        }
    }

    // 2. Compositional [Shorthand Stem + Productive Morphotactic Suffix]
    for &(short_stem, expanded_stems) in COMPOSITIONAL_SHORTHAND_STEMS {
        if base_query.len() <= short_stem.len() || !base_query.starts_with(short_stem) {
            continue;
        }
        let raw_tail = &base_query[short_stem.len()..];
        let raw_tail_u16 = u16_len(raw_tail);
        if raw_tail_u16 > 10 {
            continue;
        }
        let single_voweled: [&str; 1] = [raw_tail];
        let cand_tails: &[&str] = if is_voweled_shorthand_suffix(raw_tail)
            || (raw_tail_u16 >= 2
                && is_pure_turkish_alpha(raw_tail)
                && ascii_consonant_skeleton(raw_tail, false).len() < raw_tail_u16)
        {
            &single_voweled
        } else if let Some(exps) = vowelless_suffix_expansions(raw_tail) {
            exps
        } else {
            continue;
        };

        for &expanded_stem in expanded_stems {
            let stem_prefix = if u16_len(expanded_stem) > 2 {
                drop_last_chars(expanded_stem, 1)
            } else {
                expanded_stem
            };
            for &v_tail in cand_tails {
                let realized = if expanded_stem == "merhaba"
                    && (v_tail == "lar" || v_tail == "ler")
                {
                    Some(String::from("merhabalar"))
                } else {
                    let mut joined = String::with_capacity(expanded_stem.len() + v_tail.len());
                    joined.push_str(expanded_stem);
                    joined.push_str(v_tail);
                    morph.realize_valid_word_or_null(&joined, dynamic, scratch)
                };
                if let Some(real) = realized {
                    if real.starts_with(stem_prefix)
                        && (real == "merhabalar"
                            || morph.has_regular_strict_analysis(&real, dynamic, scratch))
                        && !strips_explicit_stem_diacritic(short_stem, &real)
                    {
                        emit_shorthand(
                            slot,
                            tok,
                            knowledge,
                            morph,
                            &real,
                            SRC_SHORTHAND | SRC_FORMAL_MORPH,
                            0.90,
                            dynamic,
                            scratch,
                        );
                    }
                }
            }
        }
    }

    // 3. Dropped-vowel consonant skeleton lookup (>= 5 consonants)
    let bq_len = u16_len(base_query);
    if !knowledge.view.has_skeleton_index()
        || !is_pure_turkish_alpha(base_query)
        || !(5..=14).contains(&bq_len)
        || knowledge.is_known_acronym_stem(low)
        || morph.is_known_strict_abbreviation(low, dynamic, scratch)
        || !is_eligible_for_skeleton_lookup(base_query)
    {
        return;
    }

    let q_skel = ascii_consonant_skeleton(base_query, false);
    if q_skel.len() < 4 {
        return;
    }
    let q_skel_collapsed = squeeze_runs(&q_skel, 1);
    let use_second = q_skel_collapsed != q_skel && q_skel_collapsed.len() >= 4;

    let q_ascii = TurkishAlphabet::to_ascii(base_query);
    let q_first_ascii = q_ascii.as_bytes()[0];
    let mut emitted = 0usize;

    for sk in core::iter::once(q_skel.as_str())
        .chain(use_second.then_some(q_skel_collapsed.as_str()))
    {
        for cand in knowledge.view.lookup_skeleton_targets(sk) {
            if is_valid_skeleton_candidate(low, bq_len, base_query, &q_ascii, q_first_ascii, cand) {
                emit_shorthand(
                    slot,
                    tok,
                    knowledge,
                    morph,
                    cand,
                    SRC_SHORTHAND | SRC_FORMAL_MORPH,
                    0.80,
                    dynamic,
                    scratch,
                );
                emitted += 1;
                if emitted >= 3 {
                    return;
                }
            }
        }
    }
}

#[allow(clippy::too_many_arguments)]
fn emit_shorthand<'a>(
    slot: &mut SlotDrafts,
    tok: &SpanToken,
    knowledge: &NormKnowledge<'a>,
    morph: &LatticeMorphSupport<'a>,
    target_lower_or_cased: &str,
    src_mask: u32,
    precision: f32,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) {
    let raw_low = tok.lower.as_str();
    let norm_low = tr_lower(target_lower_or_cased);
    let surf = if target_lower_or_cased.contains(' ') {
        format_multi_word_shorthand(slot, target_lower_or_cased)
    } else {
        knowledge.truecase(
            &norm_low,
            tok.shape,
            &tok.surface,
            slot.cap_here,
            slot.sentence_all_caps,
            morph,
            0,
            dynamic,
            scratch,
        )
    };
    let edit_cost = compute_weighted_edit_cost(raw_low, &norm_low);
    let diac_diffs = count_diacritic_diffs(raw_low, &norm_low);
    slot.add_rule(surf, norm_low, src_mask, precision, edit_cost, diac_diffs);
}

fn format_multi_word_shorthand(slot: &SlotDrafts, target: &str) -> String {
    if slot.sentence_all_caps {
        return tr_upper(target);
    }
    if target.chars().next().is_some_and(char::is_uppercase) {
        return String::from(target);
    }
    if slot.cap_here {
        tr_capitalize(target)
    } else {
        String::from(target)
    }
}

fn is_pure_turkish_alpha(s: &str) -> bool {
    !s.is_empty()
        && s.chars().all(|c| {
            (c.is_ascii_lowercase() && !matches!(c, 'q' | 'w' | 'x'))
                || matches!(c, 'ç' | 'ğ' | 'ı' | 'ö' | 'ş' | 'ü')
        })
}

fn ascii_consonant_skeleton(lower: &str, drop_soft_g: bool) -> String {
    let mut sb = String::with_capacity(lower.len());
    for c in lower.chars() {
        if TurkishAlphabet::is_vowel(c) || (drop_soft_g && c == 'ğ') {
            continue;
        }
        let asc = TurkishAlphabet::get_ascii_equal(c);
        if asc.is_ascii_lowercase() {
            sb.push(asc);
        }
    }
    sb
}

fn is_eligible_for_skeleton_lookup(word: &str) -> bool {
    let mut vowels = 0usize;
    let mut consonants = 0usize;
    let mut max_cons_cluster = 0usize;
    let mut cur_cons_cluster = 0usize;
    let mut first_ch = '\0';
    for (idx, ch) in word.chars().enumerate() {
        if idx == 0 {
            first_ch = ch;
        }
        if TurkishAlphabet::is_vowel(ch) {
            vowels += 1;
            cur_cons_cluster = 0;
        } else {
            consonants += 1;
            cur_cons_cluster += 1;
            if cur_cons_cluster > max_cons_cluster {
                max_cons_cluster = cur_cons_cluster;
            }
        }
    }
    let starts_with_vowel = TurkishAlphabet::is_vowel(first_ch);
    (vowels == 0 && consonants >= 5)
        || (starts_with_vowel && vowels == 1 && consonants >= 4)
        || (vowels <= 2 && consonants >= 5 && max_cons_cluster >= 4)
        || (!starts_with_vowel && vowels == 1 && consonants >= 5 && max_cons_cluster >= 3)
}

fn is_valid_skeleton_candidate(
    raw_low: &str,
    bq_len: usize,
    base_query: &str,
    q_ascii: &str,
    q_first_ascii: u8,
    cand: &str,
) -> bool {
    let c_len = u16_len(cand);
    if cand == raw_low || c_len < bq_len + 1 || c_len > bq_len + 6 {
        return false;
    }
    let c_ascii = TurkishAlphabet::to_ascii(cand);
    if c_ascii.as_bytes().first().copied() != Some(q_first_ascii) {
        return false;
    }
    is_ordered_subsequence(q_ascii.as_bytes(), c_ascii.as_bytes())
        && !contradicts_explicit_consonant_diacritic(base_query, cand)
}

fn is_ordered_subsequence(sub: &[u8], full: &[u8]) -> bool {
    if sub.len() > full.len() {
        return false;
    }
    let mut i = 0usize;
    for &b in full {
        if i < sub.len() && sub[i] == b {
            i += 1;
        }
    }
    i == sub.len()
}

fn contradicts_explicit_consonant_diacritic(raw_low: &str, cand_low: &str) -> bool {
    for c in raw_low.chars() {
        if matches!(c, 'ç' | 'ğ' | 'ş') && !cand_low.contains(c) {
            return true;
        }
    }
    false
}

#[allow(dead_code)]
fn _keep_shape(_: CasingShape) {}
