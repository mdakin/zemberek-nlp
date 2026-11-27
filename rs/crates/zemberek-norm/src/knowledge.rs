use alloc::string::String;
use alloc::vec::Vec;
use zemberek_core::SecondaryPos;
use zemberek_morph::{AnalyzerScratch, DynamicStemTrie};
use zemberek_pack::clean_lm::CleanWordLmView;
use zemberek_pack::norm::{CasingCountsView, NormKnowledgeView};
use zemberek_tok::CasingShape;
use crate::morph_support::{
    analysis_has_ending, proper_noun_takes_apostrophe_tdk, LatticeMorphSupport,
};
use crate::types::{
    contains_digit, has_upper_turkish_diacritics, is_digits_or_punct_only, is_java_digit,
    is_personal_initial, slice_chars, tr_capitalize, tr_lower, tr_upper, u16_len, CTX_ADJ_DATE_NUM,
    CTX_AFTER_NUMBER, CTX_BEFORE_GAZETTEER_OR_HONORIFIC, CTX_PROMOTE_PROPER_IN_CONTEXT,
    CTX_SENT_TITLE_NOISY,
};

fn is_tdk_month_or_day(s: &str) -> bool {
    matches!(
        s,
        "ocak"
            | "şubat"
            | "mart"
            | "nisan"
            | "mayıs"
            | "haziran"
            | "temmuz"
            | "ağustos"
            | "eylül"
            | "ekim"
            | "kasım"
            | "aralık"
            | "pazartesi"
            | "salı"
            | "çarşamba"
            | "perşembe"
            | "cuma"
            | "cumartesi"
            | "pazar"
    )
}

fn is_lowercase_headline_bias_stopword(s: &str) -> bool {
    matches!(
        s,
        "pro"
            | "max"
            | "plus"
            | "mini"
            | "ultra"
            | "air"
            | "ban"
            | "old"
            | "new"
            | "free"
            | "full"
            | "two"
            | "top"
            | "big"
            | "good"
            | "bad"
            | "come"
            | "get"
            | "set"
            | "run"
            | "aka"
            | "prof"
            | "vs"
            | "vb"
            | "edp"
            | "edt"
            | "angel"
            | "price"
            | "grey"
            | "express"
            | "number"
            | "super"
            | "rootlu"
            | "demokrat"
            | "bankası"
            | "üniversitesi"
            | "müdürlüğü"
            | "başkanı"
            | "başkanlığı"
            | "bakanlığı"
            | "belediyesi"
            | "paşa"
            | "reis"
            | "makbule"
            | "menteşe"
            | "çat"
            | "ikra"
    )
}

fn is_lowercase_apostrophe_slang_stem(s: &str) -> bool {
    matches!(
        s,
        "entry"
            | "pp"
            | "dm"
            | "pm"
            | "sub"
            | "nick"
            | "post"
            | "story"
            | "tweet"
            | "fav"
            | "rt"
            | "mod"
            | "ban"
            | "bug"
            | "lag"
            | "ping"
            | "fps"
            | "btc-e"
            | "recession"
            | "spree"
    )
}

fn is_canonical_upper_acronym_stem(s: &str) -> bool {
    matches!(
        s,
        "seo" | "php" | "mp3" | "mp4" | "llm" | "html" | "sql" | "smm" | "asp" | "iddk"
    )
}

fn is_tdk_holiday_modifier(s: &str) -> bool {
    matches!(
        s,
        "sevgililer" | "anneler" | "babalar" | "öğretmenler" | "kadınlar"
    )
}

fn canonical_dotted_abbr(s: &str) -> Option<&'static str> {
    match s {
        "vb." => Some("vb."),
        "vs." => Some("vs."),
        "dr." => Some("Dr."),
        "prof." => Some("Prof."),
        "doç." => Some("Doç."),
        "av." => Some("Av."),
        "org." => Some("Org."),
        "alb." => Some("Alb."),
        "yrd." => Some("Yrd."),
        "arş." => Some("Arş."),
        "gör." => Some("Gör."),
        "uzm." => Some("Uzm."),
        "ecz." => Some("Ecz."),
        "müh." => Some("Müh."),
        "mim." => Some("Mim."),
        "vet." => Some("Vet."),
        "hz." => Some("Hz."),
        "ltd." => Some("Ltd."),
        "tic." => Some("Tic."),
        "şti." => Some("Şti."),
        "san." => Some("San."),
        "mah." => Some("Mah."),
        "cad." => Some("Cad."),
        "sok." => Some("Sok."),
        "apt." => Some("Apt."),
        "no." => Some("No."),
        "tel." => Some("Tel."),
        _ => None,
    }
}

fn canonical_tech_token(s: &str) -> Option<&'static str> {
    match s {
        "2k" => Some("2K"),
        "4k" => Some("4K"),
        "8k" => Some("8K"),
        "i3" => Some("i3"),
        "i5" => Some("i5"),
        "i7" => Some("i7"),
        "i9" => Some("i9"),
        "ghz" => Some("GHz"),
        "mhz" => Some("MHz"),
        "mbps" => Some("Mbps"),
        "gbps" => Some("Gbps"),
        "mp3" => Some("MP3"),
        "mp4" => Some("MP4"),
        "seo" => Some("SEO"),
        "php" => Some("PHP"),
        "html" => Some("HTML"),
        "sql" => Some("SQL"),
        _ => None,
    }
}

/// Zero-copy runtime wrapper around `SECT_NORM_KNOWLEDGE` + `SECT_CLEAN_LM` implementing
/// `NormalizationKnowledge` lookups and deterministic TDK contextual truecasing.
#[derive(Clone, Copy, Debug)]
pub struct NormKnowledge<'a> {
    pub view: NormKnowledgeView<'a>,
    pub clean_lm: CleanWordLmView<'a>,
}

impl<'a> NormKnowledge<'a> {
    pub const fn new(view: NormKnowledgeView<'a>, clean_lm: CleanWordLmView<'a>) -> Self {
        Self { view, clean_lm }
    }

    #[inline]
    pub fn lookup_casing(&self, word_lower: &str) -> Option<CasingCountsView<'a>> {
        if let Some(cc) = self.view.lookup_casing_raw(word_lower) {
            return Some(cc);
        }
        let u = self.clean_lm.unigram_count(word_lower);
        if u == 0 {
            None
        } else {
            Some(CasingCountsView {
                lower: u,
                title: 0,
                upper: 0,
                mixed: 0,
                apostrophe_stem: 0,
                best_mixed_surface: None,
            })
        }
    }

    pub fn is_known_proper_or_acronym_stem(&self, stem_lower: &str) -> bool {
        if u16_len(stem_lower) < 2 {
            return false;
        }
        let Some(cc) = self.lookup_casing(stem_lower) else {
            return false;
        };
        let prop_votes = cc.title + cc.upper + cc.mixed;
        if cc.apostrophe_stem >= 2 && (prop_votes + cc.apostrophe_stem * 2) > cc.lower * 2 {
            return true;
        }
        prop_votes >= 4 && prop_votes > cc.lower * 4
    }

    pub fn is_known_acronym_stem(&self, stem_lower: &str) -> bool {
        let len = u16_len(stem_lower);
        if !(2..=6).contains(&len) {
            return false;
        }
        let Some(cc) = self.lookup_casing(stem_lower) else {
            return false;
        };
        cc.upper >= 6 && cc.upper > (cc.lower + cc.title) * 2
    }

    pub fn is_known_brand_mixed_case(&self, word_lower: &str) -> bool {
        let Some(cc) = self.lookup_casing(word_lower) else {
            return false;
        };
        cc.best_mixed_surface.is_some() && cc.mixed >= 3 && cc.mixed > (cc.lower + cc.title)
    }

    #[allow(clippy::too_many_arguments)]
    pub fn truecase(
        &self,
        norm_lower: &str,
        raw_shape: CasingShape,
        raw_surface: &str,
        capitalize_start: bool,
        sentence_all_caps: bool,
        morph: &LatticeMorphSupport<'a>,
        casing_ctx: u32,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> String {
        if norm_lower.is_empty() {
            return String::new();
        }
        if matches!(norm_lower.find(' '), Some(sp) if sp > 0) {
            return self.truecase_multi_word_phrase(
                norm_lower,
                raw_shape,
                raw_surface,
                capitalize_start,
                sentence_all_caps,
                morph,
                casing_ctx,
                dynamic,
                scratch,
            );
        }
        self.truecase_single_word(
            norm_lower,
            raw_shape,
            raw_surface,
            capitalize_start,
            sentence_all_caps,
            morph,
            casing_ctx,
            dynamic,
            scratch,
        )
    }

    #[allow(clippy::too_many_arguments)]
    fn truecase_multi_word_phrase(
        &self,
        norm_lower: &str,
        raw_shape: CasingShape,
        raw_surface: &str,
        capitalize_start: bool,
        sentence_all_caps: bool,
        morph: &LatticeMorphSupport<'a>,
        casing_ctx: u32,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> String {
        let parts: Vec<&str> = norm_lower.split(' ').collect();
        let noisy_flag = casing_ctx & CTX_SENT_TITLE_NOISY;
        let is_two_word_holiday = parts.len() == 2
            && is_tdk_holiday_modifier(parts[0])
            && parts[1].starts_with("günü");
        let mut sb = String::with_capacity(norm_lower.len() + 2);
        for (i, &p) in parts.iter().enumerate() {
            if i > 0 {
                sb.push(' ');
            }
            let mut part_ctx = if i == 0 {
                casing_ctx & (CTX_SENT_TITLE_NOISY | CTX_ADJ_DATE_NUM | CTX_AFTER_NUMBER)
            } else if i == parts.len() - 1 {
                noisy_flag | (casing_ctx & CTX_BEFORE_GAZETTEER_OR_HONORIFIC)
            } else {
                noisy_flag
            };
            if is_two_word_holiday {
                part_ctx |= CTX_PROMOTE_PROPER_IN_CONTEXT;
            }
            sb.push_str(&self.truecase_single_word(
                p,
                if i == 0 { raw_shape } else { CasingShape::Lower },
                if i == 0 { raw_surface } else { p },
                i == 0 && capitalize_start,
                sentence_all_caps,
                morph,
                part_ctx,
                dynamic,
                scratch,
            ));
        }
        sb
    }

    #[allow(clippy::too_many_arguments)]
    fn truecase_single_word(
        &self,
        word_lower_in: &str,
        raw_shape: CasingShape,
        raw_surface: &str,
        capitalize_start: bool,
        sentence_all_caps: bool,
        morph: &LatticeMorphSupport<'a>,
        casing_ctx: u32,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> String {
        let noisy_title_sent = (casing_ctx & CTX_SENT_TITLE_NOISY) != 0;

        // 1. Canonical TDK dotted abbreviations & personal initials
        if let Some(dotted) = canonical_dotted_abbr(word_lower_in) {
            return if capitalize_start && dotted.chars().next().is_some_and(char::is_lowercase) {
                tr_capitalize(dotted)
            } else {
                String::from(dotted)
            };
        }
        if is_personal_initial(word_lower_in) {
            let upper_initial = (casing_ctx & CTX_PROMOTE_PROPER_IN_CONTEXT) != 0
                || capitalize_start
                || (!sentence_all_caps && raw_shape == CasingShape::Title);
            return if upper_initial {
                tr_upper(word_lower_in)
            } else {
                String::from(word_lower_in)
            };
        }

        // 2. Canonical tech tokens ("2K", "4K", "i7", "GHz", "SEO", "PHP") & "mAh" vs "Mah"
        if let Some(canon_tech) = canonical_tech_token(word_lower_in) {
            return String::from(canon_tech);
        }
        if word_lower_in == "mah" {
            if (casing_ctx & CTX_AFTER_NUMBER) != 0 {
                return String::from("mAh");
            }
            if !sentence_all_caps
                && !noisy_title_sent
                && (raw_shape == CasingShape::Title
                    || (casing_ctx & CTX_BEFORE_GAZETTEER_OR_HONORIFIC) != 0)
            {
                return String::from("Mah");
            }
            return String::from(if capitalize_start { "Mah" } else { "mah" });
        }

        // 3. Alphanumeric hardware/spec shapes ("4070Ti", "5600X", "550W", "300Mbps")
        if contains_digit(word_lower_in) {
            if let Some(an) =
                truecase_alphanumeric_or_null(word_lower_in, raw_surface, sentence_all_caps)
            {
                return an;
            }
        }

        // 4. Apostrophized stems ("Türkiye'de", "TR'de", "WMAracı'nı" vs slang "entry'mde")
        if let Some(ap_b) = word_lower_in.find('\'') {
            if ap_b > 0 {
                return self.truecase_apostrophized_word(
                    &word_lower_in[..ap_b],
                    &word_lower_in[ap_b..],
                    raw_shape,
                    raw_surface,
                    capitalize_start,
                    sentence_all_caps,
                    noisy_title_sent,
                    morph,
                    dynamic,
                    scratch,
                );
            }
        }

        // 5. Foreign ALL-CAPS ASCII 'I' names/brands ("DAVID" -> "David")
        let folded_buf: String;
        let word_lower: &str = if !sentence_all_caps
            && raw_shape == CasingShape::Upper
            && raw_surface.contains('I')
            && !has_upper_turkish_diacritics(raw_surface)
            && word_lower_in.contains('ı')
        {
            let cur_cc = self.lookup_casing(word_lower_in);
            if cur_cc.is_none_or(|c| c.total() < 3)
                && !analyze_lexicon_role(word_lower_in, morph, dynamic, scratch).has_any()
            {
                folded_buf = word_lower_in.replace('ı', "i");
                if self.lookup_casing(&folded_buf).is_some_and(|c| c.total() >= 4) {
                    folded_buf.as_str()
                } else {
                    word_lower_in
                }
            } else {
                word_lower_in
            }
        } else {
            word_lower_in
        };

        // 6. Contextual TDK promotion ("Sevgililer Günü", "Lale Hanım", "Ümit Özdağ")
        if !sentence_all_caps && (casing_ctx & CTX_PROMOTE_PROPER_IN_CONTEXT) != 0 {
            return tr_capitalize(word_lower);
        }

        // 7. TDK Month & Day rule
        if is_tdk_month_or_day(word_lower) {
            let date_or_gaz =
                (casing_ctx & (CTX_ADJ_DATE_NUM | CTX_BEFORE_GAZETTEER_OR_HONORIFIC)) != 0;
            let explicit_author_title =
                !sentence_all_caps && !noisy_title_sent && raw_shape == CasingShape::Title;
            return if capitalize_start || date_or_gaz || explicit_author_title {
                tr_capitalize(word_lower)
            } else {
                String::from(word_lower)
            };
        }

        // 8. Clean-corpus casing lexicon & Zemberek lexicon fallback
        let cc = self.lookup_casing(word_lower);
        if let Some(corpus_cased) =
            truecase_from_corpus_counts_or_null(word_lower, raw_shape, capitalize_start, cc)
        {
            return corpus_cased;
        }
        if let Some(lex_cased) =
            truecase_from_morph_lexicon_or_null(word_lower, cc, morph, dynamic, scratch)
        {
            return lex_cased;
        }

        // 9. Preserve explicit author CamelCase, mid-sentence TitleCase, or UPPER acronyms
        if let Some(author_cased) = preserve_author_casing_or_null(
            word_lower,
            raw_shape,
            raw_surface,
            capitalize_start,
            sentence_all_caps,
            noisy_title_sent,
            casing_ctx,
            cc,
            morph,
            dynamic,
            scratch,
        ) {
            return author_cased;
        }

        if capitalize_start {
            tr_capitalize(word_lower)
        } else {
            String::from(word_lower)
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn truecase_apostrophized_word(
        &self,
        stem_lower: &str,
        tail_lower: &str,
        raw_shape: CasingShape,
        raw_surface: &str,
        capitalize_start: bool,
        sentence_all_caps: bool,
        noisy_title_sent: bool,
        morph: &LatticeMorphSupport<'a>,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> String {
        if is_digits_or_punct_only(stem_lower) {
            return alloc::format!("{stem_lower}{tail_lower}");
        }
        if is_canonical_upper_acronym_stem(stem_lower) {
            return alloc::format!("{}{tail_lower}", tr_upper(stem_lower));
        }
        let stem_chars = u16_len(stem_lower);
        if !sentence_all_caps && !raw_surface.is_empty() {
            let raw_norm_ap = raw_surface.replace('’', "'");
            let raw_stem = match raw_norm_ap.find('\'') {
                Some(ap) if ap > 0 => &raw_norm_ap[..ap],
                _ => &raw_norm_ap,
            };
            let stem_shape = zemberek_tok::span_token::detect_shape(raw_stem);
            if stem_shape == CasingShape::Upper && (2..=6).contains(&stem_chars) {
                return alloc::format!("{}{tail_lower}", tr_upper(stem_lower));
            }
            if stem_shape == CasingShape::Mixed
                && !noisy_title_sent
                && tr_lower(raw_stem) == stem_lower
                && !analyze_lexicon_role(stem_lower, morph, dynamic, scratch).has_common
            {
                return alloc::format!("{raw_stem}{tail_lower}");
            }
        }

        let stem_counts = self.lookup_casing(stem_lower);
        if let Some(sc) = stem_counts {
            if let Some(best_mixed) = sc.best_mixed_surface {
                if sc.mixed >= 4 && sc.mixed > (sc.lower + sc.title) {
                    return alloc::format!("{best_mixed}{tail_lower}");
                }
            }
            if sc.upper >= 5
                && sc.upper > (sc.lower + sc.title) * 2
                && (2..=6).contains(&stem_chars)
            {
                return alloc::format!("{}{tail_lower}", tr_upper(stem_lower));
            }
        }
        if !sentence_all_caps && raw_shape == CasingShape::Upper && (2..=6).contains(&stem_chars) {
            return alloc::format!("{}{tail_lower}", tr_upper(stem_lower));
        }
        if !capitalize_start && raw_shape == CasingShape::Lower {
            if let Some(sc) = stem_counts {
                if sc.lower >= 15
                    && sc.lower > (sc.title + sc.upper + sc.apostrophe_stem) * 4
                {
                    return alloc::format!("{stem_lower}{tail_lower}");
                }
            }
            let raw_had_apos = raw_surface.contains('\'') || raw_surface.contains('’');
            if raw_had_apos && is_lowercase_apostrophe_slang_stem(stem_lower) {
                return alloc::format!("{stem_lower}{tail_lower}");
            }
        }
        alloc::format!("{}{tail_lower}", tr_capitalize(stem_lower))
    }
}

fn truecase_alphanumeric_or_null(
    word_lower: &str,
    raw_surface: &str,
    sentence_all_caps: bool,
) -> Option<String> {
    if let Some(canon_unit) = format_numeric_tech_spec_or_null(word_lower) {
        return Some(canon_unit);
    }
    if sentence_all_caps || raw_surface.is_empty() {
        return None;
    }
    let raw_norm_ap = raw_surface.replace('’', "'");
    if has_any_upper_letter(&raw_norm_ap) && tr_lower(&raw_norm_ap) == word_lower {
        return Some(raw_norm_ap);
    }
    if let (Some(ap_b), Some(raw_ap_b)) = (word_lower.find('\''), raw_norm_ap.find('\'')) {
        let ap_ch = u16_len(&word_lower[..ap_b]);
        let raw_ap_ch = u16_len(&raw_norm_ap[..raw_ap_b]);
        if ap_ch > 0
            && raw_ap_ch == ap_ch
            && has_any_upper_letter(&raw_norm_ap[..raw_ap_b])
            && tr_lower(&raw_norm_ap[..raw_ap_b]) == word_lower[..ap_b]
        {
            return Some(alloc::format!(
                "{}{}",
                &raw_norm_ap[..raw_ap_b],
                &word_lower[ap_b..]
            ));
        }
    }
    None
}

fn format_numeric_tech_spec_or_null(word_lower: &str) -> Option<String> {
    let mut d_end_b = 0usize;
    for (b, c) in word_lower.char_indices() {
        if is_java_digit(c) {
            d_end_b = b + c.len_utf8();
        } else {
            break;
        }
    }
    if d_end_b == 0 || d_end_b >= word_lower.len() {
        return None;
    }
    let digits = &word_lower[..d_end_b];
    let unit = match &word_lower[d_end_b..] {
        "ghz" => "GHz",
        "mhz" => "MHz",
        "khz" => "kHz",
        "hz" => "Hz",
        "mbps" => "Mbps",
        "gbps" => "Gbps",
        "kbps" => "Kbps",
        "mah" => "mAh",
        _ => return None,
    };
    Some(alloc::format!("{digits}{unit}"))
}

fn truecase_from_corpus_counts_or_null(
    word_lower: &str,
    raw_shape: CasingShape,
    capitalize_start: bool,
    cc: Option<CasingCountsView<'_>>,
) -> Option<String> {
    let cc = cc?;
    if let Some(best_mixed) = cc.best_mixed_surface {
        if cc.mixed >= 4 && cc.mixed > (cc.lower + cc.title + cc.upper) {
            return Some(String::from(best_mixed));
        }
    }
    let w_chars = u16_len(word_lower);
    if cc.upper >= 8 && cc.upper > (cc.lower + cc.title) * 2 && (2..=6).contains(&w_chars) {
        return Some(tr_upper(word_lower));
    }
    if cc.total() >= 6
        && cc.title > (cc.lower * 3)
        && cc.title >= 5
        && (capitalize_start
            || raw_shape != CasingShape::Lower
            || !is_lowercase_headline_bias_stopword(word_lower))
    {
        return Some(tr_capitalize(word_lower));
    }
    None
}

fn truecase_from_morph_lexicon_or_null<'a>(
    word_lower: &str,
    cc: Option<CasingCountsView<'_>>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) -> Option<String> {
    let w_chars = u16_len(word_lower);
    if cc.is_some_and(|c| c.total() >= 5 || c.lower >= 2) || w_chars < 2 {
        return None;
    }
    let role = analyze_lexicon_role(word_lower, morph, dynamic, scratch);
    if !role.has_common && role.only_bare_abbr && w_chars <= 5 {
        return Some(tr_upper(word_lower));
    }
    if !role.has_common && role.has_proper_no_apostrophe {
        return Some(tr_capitalize(word_lower));
    }
    None
}

#[allow(clippy::too_many_arguments)]
fn preserve_author_casing_or_null<'a>(
    word_lower: &str,
    raw_shape: CasingShape,
    raw_surface: &str,
    capitalize_start: bool,
    sentence_all_caps: bool,
    noisy_title_sent: bool,
    casing_ctx: u32,
    cc: Option<CasingCountsView<'_>>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) -> Option<String> {
    if sentence_all_caps {
        return None;
    }
    if !noisy_title_sent && raw_shape == CasingShape::Mixed && !raw_surface.is_empty() {
        if let Some(mixed) = preserve_camel_or_hyphen_case_or_null(
            word_lower,
            raw_surface,
            cc,
            morph,
            dynamic,
            scratch,
        ) {
            return Some(mixed);
        }
    }
    if raw_shape == CasingShape::Title && !capitalize_start {
        if !noisy_title_sent {
            if (casing_ctx & CTX_BEFORE_GAZETTEER_OR_HONORIFIC) != 0
                || cc.is_none_or(|c| c.lower < 25 || c.title * 5 >= c.lower)
            {
                return Some(tr_capitalize(word_lower));
            }
        } else if cc.is_none_or(|c| c.lower < 3)
            && !analyze_lexicon_role(word_lower, morph, dynamic, scratch).has_common
        {
            return Some(tr_capitalize(word_lower));
        }
    }
    let w_chars = u16_len(word_lower);
    if raw_shape == CasingShape::Upper && w_chars >= 2 {
        if w_chars <= 5 && cc.is_none_or(|c| c.lower < 15) {
            return Some(tr_upper(word_lower));
        }
        if w_chars <= 8
            && cc.is_some_and(|c| c.upper >= 4 && c.upper > (c.lower + c.title) * 2)
        {
            return Some(tr_upper(word_lower));
        }
    }
    None
}

fn preserve_camel_or_hyphen_case_or_null<'a>(
    word_lower: &str,
    raw_surface: &str,
    cc: Option<CasingCountsView<'_>>,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) -> Option<String> {
    let raw_norm = raw_surface.replace('’', "'");
    if matches!(word_lower.find('-'), Some(h) if h > 0) && tr_lower(&raw_norm) == word_lower {
        return Some(raw_norm);
    }
    if cc.is_none_or(|c| c.lower < 5)
        && !is_shift_slip_prefix(&raw_norm)
        && !analyze_lexicon_role(word_lower, morph, dynamic, scratch).has_common
    {
        let raw_low = tr_lower(&raw_norm);
        if raw_low == word_lower {
            return Some(raw_norm);
        }
        let raw_low_chars = u16_len(&raw_low);
        let word_chars = u16_len(word_lower);
        let raw_norm_chars = u16_len(&raw_norm);
        if raw_low_chars >= 4
            && word_chars > raw_low_chars
            && word_lower.starts_with(&raw_low)
            && raw_norm_chars <= word_chars
        {
            let tail = slice_chars(word_lower, raw_norm_chars, word_chars);
            return Some(alloc::format!("{raw_norm}{tail}"));
        }
    }
    None
}

struct LexiconRole {
    has_common: bool,
    only_bare_abbr: bool,
    has_proper_no_apostrophe: bool,
    has_any_proper: bool,
}

impl LexiconRole {
    #[inline]
    fn has_any(&self) -> bool {
        self.has_common || self.has_any_proper
    }
}

fn analyze_lexicon_role<'a>(
    word_lower: &str,
    morph: &LatticeMorphSupport<'a>,
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) -> LexiconRole {
    if u16_len(word_lower) < 2 {
        return LexiconRole {
            has_common: false,
            only_bare_abbr: false,
            has_proper_no_apostrophe: false,
            has_any_proper: false,
        };
    }
    let wa = morph.strict_morph.analyze_word(word_lower, dynamic, scratch);
    let graph = &morph.strict_morph.graph;
    let fst = &morph.strict_morph.fst;
    let a3pl_id = morph.a3pl_morpheme_id();

    let mut only_bare_abbr = false;
    let mut proper_no_ap = false;
    let mut any_proper = false;
    let mut has_common = false;

    for a in &wa.analyses {
        if !a.is_known_lexical(graph) {
            continue;
        }
        let spos = a.item.secondary_pos(graph);
        if spos == SecondaryPos::Abbreviation {
            any_proper = true;
            if !analysis_has_ending(a) {
                only_bare_abbr = true;
            }
        } else if spos == SecondaryPos::ProperNoun {
            any_proper = true;
            let has_end = analysis_has_ending(a);
            if !has_end
                || (!proper_noun_takes_apostrophe_tdk(a, graph, fst)
                    && !a.contains_morpheme(a3pl_id))
            {
                proper_no_ap = true;
            }
        } else {
            has_common = true;
        }
    }
    LexiconRole {
        has_common,
        only_bare_abbr,
        has_proper_no_apostrophe: proper_no_ap,
        has_any_proper: any_proper,
    }
}

fn has_any_upper_letter(s: &str) -> bool {
    s.chars().any(|c| c.is_alphabetic() && c.is_uppercase())
}

fn is_shift_slip_prefix(s: &str) -> bool {
    let mut it = s.chars();
    let (Some(c0), Some(c1), Some(c2)) = (it.next(), it.next(), it.next()) else {
        return false;
    };
    if !c0.is_uppercase() || !c1.is_uppercase() {
        return false;
    }
    if c2.is_alphabetic() && c2.is_uppercase() {
        return false;
    }
    !it.any(|c| c.is_alphabetic() && c.is_uppercase())
}
