use alloc::string::String;
use alloc::vec::Vec;
use zemberek_core::TurkishAlphabet;
use zemberek_tok::{SpanEditLike, SpanToken, TokenType};

// Candidate Generator Source Bitflags (matching `NormV2Types`)
pub const SRC_IDENTITY: u32 = 1;
pub const SRC_PARALLEL_1TO1: u32 = 1 << 1;
pub const SRC_PARALLEL_SPLIT: u32 = 1 << 2;
pub const SRC_PARALLEL_MERGE: u32 = 1 << 3;
pub const SRC_FORMAL_MORPH: u32 = 1 << 4;
pub const SRC_INFORMAL_MORPH: u32 = 1 << 5;
pub const SRC_DEASCIIFIER: u32 = 1 << 6;
pub const SRC_SUFFIX_RULE: u32 = 1 << 7;
pub const SRC_OOV_APOSTROPHE: u32 = 1 << 8;
pub const SRC_ELONGATION: u32 = 1 << 9;
pub const SRC_SPELLCHECK: u32 = 1 << 10;
pub const SRC_CLITIC_SPLIT: u32 = 1 << 11;
pub const SRC_COMPOUND_MERGE: u32 = 1 << 12;
pub const SRC_NUMERAL_JOIN: u32 = 1 << 13;
pub const SRC_INFORMAL_EXACT: u32 = 1 << 14;
pub const SRC_APOSTROPHE_DROP: u32 = 1 << 15;
pub const SRC_SHORTHAND: u32 = 1 << 16;

pub const VALID_SINGLE_WORD_MASK: u32 = SRC_DEASCIIFIER
    | SRC_FORMAL_MORPH
    | SRC_INFORMAL_MORPH
    | SRC_INFORMAL_EXACT
    | SRC_OOV_APOSTROPHE
    | SRC_SUFFIX_RULE
    | SRC_ELONGATION
    | SRC_SHORTHAND;

// Coarse Syntactic Categories for Head Words
pub const HEAD_SYNTAX_OTHER: u8 = 0;
pub const HEAD_SYNTAX_FINITE_OR_CONVERB: u8 = 1;
pub const HEAD_SYNTAX_PRON_ADV_CONJ: u8 = 2;
pub const HEAD_SYNTAX_NOMINAL: u8 = 3;

pub const NEXT_STATE_EXISTENTIAL: u8 = 10;
pub const NEXT_STATE_CLAUSE_END: u8 = 11;

// Contextual Truecasing Bitflags (`NormalizationKnowledge.CTX_*`)
pub const CTX_SENT_TITLE_NOISY: u32 = 1 << 0;
pub const CTX_ADJ_DATE_NUM: u32 = 1 << 1;
pub const CTX_BEFORE_GAZETTEER_OR_HONORIFIC: u32 = 1 << 2;
pub const CTX_AFTER_NUMBER: u32 = 1 << 3;
pub const CTX_PROMOTE_PROPER_IN_CONTEXT: u32 = 1 << 4;

/// Browser underline category for a non-identity `SpanArc`.
#[repr(u8)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum IssueKind {
    /// Typos, suffix corruptions, missing/erroneous apostrophes, casing errors (`Red` wavy underline).
    Spelling = 1,
    /// Detached or glued `-de/-da`, `mi`, `ki`, `bir şey`, open/closed compounds (`Blue` wavy underline).
    CliticSplitMerge = 2,
    /// ASCII-stripped diacritics, informal `-yom/-cam`, chat shorthands, elongations (`Purple` wavy underline).
    DeasciifyOrColloquial = 3,
}

/// Span-level diagnostic emitted by `SpanLatticePerceptronNormalizer::check_sentence` / `check_paragraph`
/// for non-destructive browser highlighting (`CSS.highlights` & `<textarea>` mirror overlay).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SpanIssue {
    pub start_tok: usize,
    pub end_tok: usize,
    pub utf8_start: usize,
    pub utf8_end: usize,
    pub utf16_start: usize,
    pub utf16_end: usize,
    pub kind: IssueKind,
    pub raw_text: String,
    pub replacement: String,
    /// Up to 3 ranked replacement surfaces (index 0 == `replacement`).
    pub suggestions: Vec<String>,
}

/// Classify `source_mask` and span shape into one of the 3 browser underline colors.
pub fn classify_issue_kind(arc: &SpanArc) -> IssueKind {
    let m = arc.source_mask;
    if arc.word_count >= 2
        || (arc.end_idx - arc.start_idx) >= 2
        || (m & (SRC_CLITIC_SPLIT | SRC_PARALLEL_SPLIT | SRC_PARALLEL_MERGE | SRC_COMPOUND_MERGE | SRC_NUMERAL_JOIN)) != 0
    {
        return IssueKind::CliticSplitMerge;
    }
    if (m & (SRC_DEASCIIFIER | SRC_INFORMAL_MORPH | SRC_INFORMAL_EXACT | SRC_SHORTHAND | SRC_ELONGATION)) != 0
        && (m & SRC_SPELLCHECK) == 0
    {
        return IssueKind::DeasciifyOrColloquial;
    }
    if arc.diacritic_diffs > 0
        && !arc.apostrophe_added
        && !arc.apostrophe_removed
        && (m & (SRC_SPELLCHECK | SRC_SUFFIX_RULE | SRC_OOV_APOSTROPHE | SRC_APOSTROPHE_DROP)) == 0
    {
        return IssueKind::DeasciifyOrColloquial;
    }
    IssueKind::Spelling
}

#[derive(Clone, Debug)]
pub struct SpanArc {
    pub start_idx: usize,
    pub end_idx: usize,
    pub norm_surface: String,
    pub norm_lower: String,
    pub first_word_lower: String,
    pub last_word_lower: String,
    pub word_count: usize,
    pub source_mask: u32,
    pub first_word_morph_tag: i16,
    pub coarse_morph_tag: i16,
    pub head_syntax_class: u8,
    pub verb_person: u8,
    pub is_regular_morph: bool,
    pub is_proper_or_abbr: bool,
    pub parallel_prob: f32,
    pub parallel_log_count: f32,
    pub raw_parallel_total: u32,
    pub suffix_precision: f32,
    pub edit_cost: f32,
    pub diacritic_diffs: u32,
    pub case_changed: bool,
    pub apostrophe_added: bool,
    pub apostrophe_removed: bool,
    pub strips_explicit_diacritic: bool,
    pub char_phonotactic_score: f32,
    pub internal_lm_bonus: f32,
    pub verb_polarity: i8,
    pub raw_neg_verb_cue: bool,
    pub raw_clean_count: u32,
    pub raw_char_phono: f32,
    pub unary_clean_gain: f32,
    pub next_lex_state: u8,
}

impl SpanArc {
    pub fn plain(
        start_idx: usize,
        end_idx: usize,
        norm_surface: String,
        norm_lower: String,
        source_mask: u32,
        coarse_morph_tag: i16,
    ) -> Self {
        Self::new(
            start_idx,
            end_idx,
            norm_surface,
            norm_lower,
            source_mask,
            coarse_morph_tag,
            coarse_morph_tag,
            HEAD_SYNTAX_OTHER,
            0,
            true,
            false,
            1.0,
            0.0,
            0.0,
            0.0,
            0,
            false,
            false,
            false,
            false,
            0.0,
            0.0,
        )
    }

    #[allow(clippy::too_many_arguments)]
    pub fn new(
        start_idx: usize,
        end_idx: usize,
        norm_surface: String,
        norm_lower: String,
        source_mask: u32,
        first_word_morph_tag: i16,
        coarse_morph_tag: i16,
        head_syntax_class: u8,
        verb_person: u8,
        is_regular_morph: bool,
        is_proper_or_abbr: bool,
        parallel_prob: f32,
        parallel_log_count: f32,
        suffix_precision: f32,
        edit_cost: f32,
        diacritic_diffs: u32,
        case_changed: bool,
        apostrophe_added: bool,
        apostrophe_removed: bool,
        strips_explicit_diacritic: bool,
        char_phonotactic_score: f32,
        internal_lm_bonus: f32,
    ) -> Self {
        let (first_word_lower, last_word_lower, word_count) = match norm_lower.find(' ') {
            None => (norm_lower.clone(), norm_lower.clone(), 1usize),
            Some(first_sp) => {
                let last_sp = norm_lower.rfind(' ').unwrap_or(first_sp);
                let wc = 1 + norm_lower.bytes().filter(|&b| b == b' ').count();
                (
                    String::from(&norm_lower[..first_sp]),
                    String::from(&norm_lower[last_sp + 1..]),
                    wc,
                )
            }
        };
        Self {
            start_idx,
            end_idx,
            norm_surface,
            norm_lower,
            first_word_lower,
            last_word_lower,
            word_count,
            source_mask,
            first_word_morph_tag,
            coarse_morph_tag,
            head_syntax_class,
            verb_person,
            is_regular_morph,
            is_proper_or_abbr,
            parallel_prob,
            parallel_log_count,
            raw_parallel_total: 0,
            suffix_precision,
            edit_cost,
            diacritic_diffs,
            case_changed,
            apostrophe_added,
            apostrophe_removed,
            strips_explicit_diacritic,
            char_phonotactic_score,
            internal_lm_bonus,
            verb_polarity: 0,
            raw_neg_verb_cue: false,
            raw_clean_count: 0,
            raw_char_phono: 0.0,
            unary_clean_gain: 0.0,
            next_lex_state: 0,
        }
    }
}

impl SpanEditLike for SpanArc {
    #[inline]
    fn start_idx(&self) -> usize {
        self.start_idx
    }
    #[inline]
    fn end_idx(&self) -> usize {
        self.end_idx
    }
    #[inline]
    fn norm_surface(&self) -> &str {
        &self.norm_surface
    }
    #[inline]
    fn word_count(&self) -> usize {
        self.word_count
    }
}

impl SpanEditLike for &SpanArc {
    #[inline]
    fn start_idx(&self) -> usize {
        self.start_idx
    }
    #[inline]
    fn end_idx(&self) -> usize {
        self.end_idx
    }
    #[inline]
    fn norm_surface(&self) -> &str {
        &self.norm_surface
    }
    #[inline]
    fn word_count(&self) -> usize {
        self.word_count
    }
}

#[derive(Clone, Debug)]
pub struct SentenceLattice {
    pub tokens: Vec<SpanToken>,
    pub outgoing: Vec<Vec<SpanArc>>,
    pub sentence_ascii_ratio: f32,
    pub overt_subject_person: u8,
    pub sentence_all_caps: bool,
    pub has_question_mark: bool,
}

#[derive(Clone, Debug)]
pub struct CandidateDraft {
    pub norm_surface: String,
    pub norm_lower: String,
    pub source_mask: u32,
    pub parallel_prob: f32,
    pub parallel_log_count: f32,
    pub suffix_precision: f32,
    pub edit_cost: f32,
    pub diacritic_diffs: u32,
}

impl CandidateDraft {
    #[inline]
    pub fn first_word_lower(&self) -> &str {
        match self.norm_lower.find(' ') {
            Some(sp) => &self.norm_lower[..sp],
            None => &self.norm_lower,
        }
    }

    #[inline]
    pub fn last_word_lower(&self) -> &str {
        match self.norm_lower.rfind(' ') {
            Some(sp) => &self.norm_lower[sp + 1..],
            None => &self.norm_lower,
        }
    }
}

/// Deterministic insertion-ordered draft collector matching `SpanCandidateGenerator.SlotDraftBuilder`.
pub struct SlotDrafts {
    pub cap_here: bool,
    pub sentence_all_caps: bool,
    pub items: Vec<CandidateDraft>,
}

impl SlotDrafts {
    pub fn new(cap_here: bool, sentence_all_caps: bool) -> Self {
        Self {
            cap_here,
            sentence_all_caps,
            items: Vec::with_capacity(10),
        }
    }

    #[allow(clippy::too_many_arguments)]
    pub fn add(
        &mut self,
        norm_surface: String,
        norm_lower: String,
        source_mask: u32,
        parallel_prob: f32,
        parallel_log_count: f32,
        suffix_precision: f32,
        edit_cost: f32,
        diacritic_diffs: u32,
    ) {
        put_or_merge(
            &mut self.items,
            norm_surface,
            norm_lower,
            source_mask,
            parallel_prob,
            parallel_log_count,
            suffix_precision,
            edit_cost,
            diacritic_diffs,
        );
    }

    pub fn add_rule(
        &mut self,
        norm_surface: String,
        norm_lower: String,
        source_mask: u32,
        suffix_precision: f32,
        edit_cost: f32,
        diacritic_diffs: u32,
    ) {
        self.add(
            norm_surface,
            norm_lower,
            source_mask,
            0.0,
            0.0,
            suffix_precision,
            edit_cost,
            diacritic_diffs,
        );
    }

    pub fn has_low_cost_morph_draft(&self) -> bool {
        let mask = SRC_DEASCIIFIER | SRC_FORMAL_MORPH | SRC_INFORMAL_EXACT;
        self.items
            .iter()
            .any(|d| (d.source_mask & mask) != 0 && d.edit_cost <= 0.30)
    }

    pub fn has_valid_single_word_draft(&self) -> bool {
        self.items
            .iter()
            .any(|d| !d.norm_lower.contains(' ') && (d.source_mask & VALID_SINGLE_WORD_MASK) != 0)
    }
}

#[allow(clippy::too_many_arguments)]
pub fn put_or_merge(
    items: &mut Vec<CandidateDraft>,
    norm_surface: String,
    norm_lower: String,
    source_mask: u32,
    parallel_prob: f32,
    parallel_log_count: f32,
    suffix_precision: f32,
    edit_cost: f32,
    diacritic_diffs: u32,
) {
    if let Some(ex) = items.iter_mut().find(|d| d.norm_surface == norm_surface) {
        ex.source_mask |= source_mask;
        if parallel_prob > ex.parallel_prob {
            ex.parallel_prob = parallel_prob;
        }
        if parallel_log_count > ex.parallel_log_count {
            ex.parallel_log_count = parallel_log_count;
        }
        if suffix_precision > ex.suffix_precision {
            ex.suffix_precision = suffix_precision;
        }
        if edit_cost < ex.edit_cost {
            ex.edit_cost = edit_cost;
        }
        return;
    }
    items.push(CandidateDraft {
        norm_surface,
        norm_lower,
        source_mask,
        parallel_prob,
        parallel_log_count,
        suffix_precision,
        edit_cost,
        diacritic_diffs,
    });
}

// =========================================================================
// Exact UTF-16 / Char-Accurate Text & Phonology Helpers
// =========================================================================

#[inline]
pub fn tr_lower(s: &str) -> String {
    zemberek_tok::span_token::tr_lower_norm(s)
}

#[inline]
pub fn tr_upper(s: &str) -> String {
    if s.is_empty() {
        return String::new();
    }
    if s.contains('’') {
        let mut replaced = String::with_capacity(s.len());
        for c in s.chars() {
            replaced.push(if c == '’' { '\'' } else { c });
        }
        TurkishAlphabet::to_upper_tr(&replaced)
    } else {
        TurkishAlphabet::to_upper_tr(s)
    }
}

#[inline]
pub fn tr_capitalize(s: &str) -> String {
    TurkishAlphabet::capitalize_head_tr(s)
}

#[inline]
pub fn to_ascii(lower: &str) -> String {
    if lower.is_empty() {
        String::new()
    } else {
        TurkishAlphabet::to_ascii(lower)
    }
}

#[inline]
pub fn is_java_digit(c: char) -> bool {
    if c.is_ascii() {
        c.is_ascii_digit()
    } else {
        matches!(
            c as u32,
            0x0660..=0x0669
                | 0x06F0..=0x06F9
                | 0x07C0..=0x07C9
                | 0x0966..=0x096F
                | 0x09E6..=0x09EF
                | 0xFF10..=0xFF19
        )
    }
}

#[inline]
pub fn contains_digit(s: &str) -> bool {
    s.chars().any(is_java_digit)
}

#[inline]
pub fn is_all_digits(s: &str) -> bool {
    !s.is_empty() && s.chars().all(is_java_digit)
}

#[inline]
pub fn is_digits_or_punct_only(s: &str) -> bool {
    !s.is_empty() && !s.chars().any(char::is_alphabetic)
}

#[inline]
pub fn is_personal_initial(s: &str) -> bool {
    let mut it = s.chars();
    let Some(c0) = it.next() else {
        return false;
    };
    let Some(c1) = it.next() else {
        return false;
    };
    it.next().is_none() && (c0 as u32) <= 0xFFFF && c1 == '.' && c0.is_alphabetic()
}

#[inline]
pub fn has_upper_turkish_diacritics(s: &str) -> bool {
    zemberek_tok::span_token::has_upper_turkish_diacritics(s)
}

#[inline]
pub fn clamp_lm(v: f32) -> f32 {
    if v.is_nan() || v.is_infinite() {
        -20.0
    } else {
        v.clamp(-20.0, 0.0)
    }
}

/// UTF-16 code-unit length (matches Java `String.length()`).
#[inline]
pub fn u16_len(s: &str) -> usize {
    if s.is_ascii() {
        s.len()
    } else {
        s.chars().map(char::len_utf16).sum()
    }
}

/// Slices `&str` by `char` indices `[start_ch..end_ch)`.
#[inline]
pub fn slice_chars(s: &str, start_ch: usize, end_ch: usize) -> &str {
    if s.is_ascii() {
        return &s[start_ch..end_ch];
    }
    let start_b = if start_ch == 0 {
        0
    } else {
        s.char_indices().nth(start_ch).map_or(s.len(), |(b, _)| b)
    };
    let end_b = if end_ch <= start_ch {
        start_b
    } else {
        s[start_b..]
            .char_indices()
            .nth(end_ch - start_ch)
            .map_or(s.len(), |(b, _)| start_b + b)
    };
    &s[start_b..end_b]
}

#[inline]
pub fn take_first_chars(s: &str, n_chars: usize) -> &str {
    slice_chars(s, 0, n_chars)
}

#[inline]
pub fn drop_last_chars(s: &str, n_drop: usize) -> &str {
    if n_drop == 0 {
        return s;
    }
    if s.is_ascii() {
        return &s[..s.len().saturating_sub(n_drop)];
    }
    let cut = s
        .char_indices()
        .rev()
        .nth(n_drop - 1)
        .map_or(0, |(idx, _)| idx);
    &s[..cut]
}

#[inline]
pub fn has_ascii_ambiguous_chars(lower: &str) -> bool {
    lower
        .bytes()
        .any(|b| matches!(b, b'c' | b'g' | b'i' | b'o' | b's' | b'u'))
}

#[inline]
pub fn has_explicit_turkish_diacritics(lower: &str) -> bool {
    lower
        .chars()
        .any(|c| matches!(c, 'ç' | 'ğ' | 'ı' | 'ö' | 'ş' | 'ü'))
}

#[inline]
pub fn contains_foreign_wxq(lower: &str) -> bool {
    lower.bytes().any(|b| matches!(b, b'w' | b'x' | b'q'))
}

#[inline]
pub fn is_sentence_boundary_punct(t: &SpanToken) -> bool {
    t.token_type == TokenType::Punctuation
        && t.surface.bytes().any(|b| matches!(b, b'.' | b'?' | b'!'))
}

pub fn count_diacritic_diffs(a: &str, b: &str) -> u32 {
    if u16_len(a) != u16_len(b) {
        return 0;
    }
    let mut diffs = 0u32;
    let mut ia = a.chars();
    let mut ib = b.chars();
    while let (Some(ca), Some(cb)) = (ia.next(), ib.next()) {
        if ca.len_utf16() != cb.len_utf16() {
            return 0;
        }
        if ca != cb && TurkishAlphabet::get_ascii_equal(ca) == TurkishAlphabet::get_ascii_equal(cb)
        {
            diffs += 1;
        }
    }
    diffs
}

/// Exact port of `SpanCandidateGenerator.computeWeightedEditCost(a, b)` over UTF-16 code units.
pub fn compute_weighted_edit_cost(a: &str, b: &str) -> f32 {
    if a == b {
        return 0.0;
    }
    let mut ua_stack = [0u16; 64];
    let mut ub_stack = [0u16; 64];
    let n = u16_len(a);
    let m = u16_len(b);
    if n.abs_diff(m) > 8 {
        return n.abs_diff(m) as f32;
    }
    if n <= 64 && m <= 64 {
        let mut na = 0usize;
        let mut tmp = [0u16; 2];
        for c in a.chars() {
            for &u in c.encode_utf16(&mut tmp).iter() {
                ua_stack[na] = u;
                na += 1;
            }
        }
        let mut nb = 0usize;
        for c in b.chars() {
            for &u in c.encode_utf16(&mut tmp).iter() {
                ub_stack[nb] = u;
                nb += 1;
            }
        }
        return weighted_edit_dp_u16(&ua_stack[..na], &ub_stack[..nb]);
    }
    let ua: Vec<u16> = a.encode_utf16().collect();
    let ub: Vec<u16> = b.encode_utf16().collect();
    weighted_edit_dp_u16(&ua, &ub)
}

fn weighted_edit_dp_u16(ua: &[u16], ub: &[u16]) -> f32 {
    let n = ua.len();
    let m = ub.len();
    let mut prev = [0.0f32; 65];
    let mut curr = [0.0f32; 65];
    if m >= 65 {
        let mut pv = alloc::vec![0.0f32; m + 1];
        let mut cv = alloc::vec![0.0f32; m + 1];
        for (j, slot) in pv.iter_mut().enumerate() {
            *slot = (j as f32) * 0.85;
        }
        for i in 1..=n {
            cv[0] = (i as f32) * 0.85;
            let ca = ua[i - 1];
            for j in 1..=m {
                let cb = ub[j - 1];
                let sub_cost = char_sub_cost_u16(ca, cb);
                let ins_cost = if cb == (b'\'' as u16) || cb == (b' ' as u16) {
                    0.20
                } else {
                    0.85
                };
                let del_cost = if ca == (b'\'' as u16) || (i > 1 && ca == ua[i - 2]) {
                    0.20
                } else {
                    0.85
                };
                cv[j] = (pv[j - 1] + sub_cost)
                    .min(cv[j - 1] + ins_cost)
                    .min(pv[j] + del_cost);
            }
            core::mem::swap(&mut pv, &mut cv);
        }
        return pv[m];
    }

    for (j, slot) in prev[..=m].iter_mut().enumerate() {
        *slot = (j as f32) * 0.85;
    }
    for i in 1..=n {
        curr[0] = (i as f32) * 0.85;
        let ca = ua[i - 1];
        for j in 1..=m {
            let cb = ub[j - 1];
            let sub_cost = char_sub_cost_u16(ca, cb);
            let ins_cost = if cb == (b'\'' as u16) || cb == (b' ' as u16) {
                0.20
            } else {
                0.85
            };
            let del_cost = if ca == (b'\'' as u16) || (i > 1 && ca == ua[i - 2]) {
                0.20
            } else {
                0.85
            };
            curr[j] = (prev[j - 1] + sub_cost)
                .min(curr[j - 1] + ins_cost)
                .min(prev[j] + del_cost);
        }
        core::mem::swap(&mut prev, &mut curr);
    }
    prev[m]
}

#[inline]
fn char_sub_cost_u16(ca: u16, cb: u16) -> f32 {
    if ca == cb {
        return 0.0;
    }
    if let (Some(cha), Some(chb)) = (char::from_u32(ca as u32), char::from_u32(cb as u32)) {
        if TurkishAlphabet::get_ascii_equal(cha) == TurkishAlphabet::get_ascii_equal(chb) {
            return 0.15;
        }
    }
    if cb == (b'\'' as u16) || cb == (b' ' as u16) {
        0.25
    } else {
        0.90
    }
}
