use alloc::string::String;
use alloc::vec::Vec;
use crate::data::LEXER_ABBREVIATIONS;
use crate::detokenizer::TurkishDetokenizer;
use crate::emoji::{is_emoji_base_char, is_emoji_modifier, is_regional_indicator};

/// Exact token types matching `zemberek.tokenization.Token.Type` ordinals `0..=21`.
#[repr(u8)]
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum TokenType {
    SpaceTab = 0,
    NewLine = 1,
    Word = 2,
    WordAlphanumerical = 3,
    WordWithSymbol = 4,
    Abbreviation = 5,
    AbbreviationWithDots = 6,
    Punctuation = 7,
    RomanNumeral = 8,
    Number = 9,
    PercentNumeral = 10,
    Time = 11,
    Date = 12,
    URL = 13,
    Email = 14,
    HashTag = 15,
    Mention = 16,
    MetaTag = 17,
    Emoji = 18,
    Emoticon = 19,
    UnknownWord = 20,
    Unknown = 21,
}

impl TokenType {
    #[inline]
    pub const fn bit(self) -> u32 {
        1u32 << (self as u8)
    }

    #[inline]
    pub const fn name(self) -> &'static str {
        match self {
            Self::SpaceTab => "SpaceTab",
            Self::NewLine => "NewLine",
            Self::Word => "Word",
            Self::WordAlphanumerical => "WordAlphanumerical",
            Self::WordWithSymbol => "WordWithSymbol",
            Self::Abbreviation => "Abbreviation",
            Self::AbbreviationWithDots => "AbbreviationWithDots",
            Self::Punctuation => "Punctuation",
            Self::RomanNumeral => "RomanNumeral",
            Self::Number => "Number",
            Self::PercentNumeral => "PercentNumeral",
            Self::Time => "Time",
            Self::Date => "Date",
            Self::URL => "URL",
            Self::Email => "Email",
            Self::HashTag => "HashTag",
            Self::Mention => "Mention",
            Self::MetaTag => "MetaTag",
            Self::Emoji => "Emoji",
            Self::Emoticon => "Emoticon",
            Self::UnknownWord => "UnknownWord",
            Self::Unknown => "Unknown",
        }
    }

    #[inline]
    pub const fn is_numeral(self) -> bool {
        matches!(self, Self::Number | Self::RomanNumeral | Self::PercentNumeral)
    }

    #[inline]
    pub const fn is_whitespace(self) -> bool {
        matches!(self, Self::SpaceTab | Self::NewLine)
    }

    #[inline]
    pub const fn is_web_related(self) -> bool {
        matches!(
            self,
            Self::HashTag | Self::Mention | Self::URL | Self::MetaTag | Self::Email
        )
    }

    #[inline]
    pub const fn is_emoji(self) -> bool {
        matches!(self, Self::Emoji | Self::Emoticon)
    }

    #[inline]
    pub const fn is_unidentified(self) -> bool {
        matches!(self, Self::Unknown | Self::UnknownWord)
    }

    #[inline]
    pub const fn is_word(self) -> bool {
        matches!(self, Self::Word | Self::Abbreviation)
    }

    #[inline]
    pub const fn is_word_like(self) -> bool {
        matches!(
            self,
            Self::Word | Self::WordWithSymbol | Self::WordAlphanumerical | Self::UnknownWord
        )
    }
}

/// Zero-copy token span carrying both UTF-8 byte offsets `[utf8_start, utf8_end)` and
/// UTF-16 code-unit offsets `[utf16_start, utf16_end)` for direct browser DOM `Range` mapping.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct TokenSpan {
    pub token_type: TokenType,
    pub utf8_start: usize,
    pub utf8_end: usize,
    pub utf16_start: usize,
    pub utf16_end: usize,
}

impl TokenSpan {
    #[inline]
    pub fn text<'a>(&self, source: &'a str) -> &'a str {
        &source[self.utf8_start..self.utf8_end]
    }

    /// Inclusive UTF-16 stop index matching `zemberek.tokenization.Token.getEnd()`.
    #[inline]
    pub fn java_end_inclusive(&self) -> usize {
        self.utf16_end.saturating_sub(1)
    }
}

/// Owned token view matching `zemberek.tokenization.Token`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Token<'a> {
    pub content: &'a str,
    pub token_type: TokenType,
    pub utf8_start: usize,
    pub utf8_end: usize,
    pub utf16_start: usize,
    pub utf16_end: usize,
}

impl<'a> Token<'a> {
    #[inline]
    pub fn java_start(&self) -> usize {
        self.utf16_start
    }

    #[inline]
    pub fn java_end(&self) -> usize {
        self.utf16_end.saturating_sub(1)
    }
}

/// Zero-regex single-pass maximal-munch tokenizer matching `zemberek.tokenization.TurkishTokenizer`.
#[derive(Clone, Copy, Debug)]
pub struct TurkishTokenizer {
    accepted_bits: u32,
}

pub struct TurkishTokenizerBuilder {
    accepted_bits: u32,
}

impl TurkishTokenizerBuilder {
    pub const fn new() -> Self {
        Self {
            accepted_bits: u32::MAX,
        }
    }

    pub fn accept_all(mut self) -> Self {
        self.accepted_bits = u32::MAX;
        self
    }

    pub fn ignore_all(mut self) -> Self {
        self.accepted_bits = 0;
        self
    }

    pub fn accept_types(mut self, types: &[TokenType]) -> Self {
        for &t in types {
            self.accepted_bits |= t.bit();
        }
        self
    }

    pub fn ignore_types(mut self, types: &[TokenType]) -> Self {
        for &t in types {
            self.accepted_bits &= !t.bit();
        }
        self
    }

    pub const fn build(self) -> TurkishTokenizer {
        TurkishTokenizer {
            accepted_bits: self.accepted_bits,
        }
    }
}

impl Default for TurkishTokenizerBuilder {
    fn default() -> Self {
        Self::new()
    }
}

impl TurkishTokenizer {
    pub const ALL: Self = Self {
        accepted_bits: u32::MAX,
    };

    pub const DEFAULT: Self = Self {
        accepted_bits: !(TokenType::SpaceTab.bit() | TokenType::NewLine.bit()),
    };

    #[inline]
    pub const fn builder() -> TurkishTokenizerBuilder {
        TurkishTokenizerBuilder::new()
    }

    #[inline]
    pub const fn is_type_accepted(self, t: TokenType) -> bool {
        (self.accepted_bits & t.bit()) != 0
    }

    #[inline]
    pub const fn is_type_ignored(self, t: TokenType) -> bool {
        !self.is_type_accepted(t)
    }

    #[inline]
    pub fn is_lexer_abbreviation(word_with_dot: &str) -> bool {
        LEXER_ABBREVIATIONS.binary_search(&word_with_dot).is_ok()
    }

    /// Scans `input` into zero-copy `TokenSpan`s without heap-allocating per-token strings.
    pub fn tokenize_spans(self, input: &str) -> Vec<TokenSpan> {
        let mut out = Vec::with_capacity(input.len() / 5 + 4);
        self.tokenize_spans_into(input, &mut out);
        out
    }

    pub fn tokenize_spans_into(self, input: &str, out: &mut Vec<TokenSpan>) {
        out.clear();
        let n = input.len();
        let mut pos = 0usize;
        let mut utf16_pos = 0usize;
        let mut deferred: Option<(TokenType, usize, usize, usize, usize)> = None;

        while pos < n || deferred.is_some() {
            let (raw_type, start, end, u16_start, u16_end) = if let Some(d) = deferred.take() {
                d
            } else {
                let (ty, next_pos) = match_raw_lexer_token(input, pos);
                let u16_len = utf16_len_of(&input[pos..next_pos]);
                let s = pos;
                let us = utf16_pos;
                pos = next_pos;
                utf16_pos += u16_len;
                (ty, s, next_pos, us, utf16_pos)
            };

            if (raw_type == TokenType::SpaceTab && self.is_type_ignored(TokenType::SpaceTab))
                || (raw_type == TokenType::NewLine && self.is_type_ignored(TokenType::NewLine))
            {
                continue;
            }

            if raw_type == TokenType::Word && input.as_bytes().get(pos) == Some(&b'.') {
                let (next2_ty, next2_end) = match_raw_lexer_token(input, pos);
                let next2_u16_len = utf16_len_of(&input[pos..next2_end]);
                let next2_start = pos;
                let next2_u16_start = utf16_pos;
                pos = next2_end;
                utf16_pos += next2_u16_len;

                // Single-char Punctuation '.' check (next2Start == next2Stop in ANTLR)
                if next2_ty == TokenType::Punctuation && next2_end - next2_start == 1 {
                    let abbrev = &input[start..next2_end];
                    if Self::is_lexer_abbreviation(abbrev) {
                        if !self.is_type_ignored(TokenType::Abbreviation) {
                            out.push(TokenSpan {
                                token_type: TokenType::Abbreviation,
                                utf8_start: start,
                                utf8_end: next2_end,
                                utf16_start: u16_start,
                                utf16_end: utf16_pos,
                            });
                        }
                        continue;
                    }
                }
                deferred = Some((
                    next2_ty,
                    next2_start,
                    next2_end,
                    next2_u16_start,
                    utf16_pos,
                ));
            }

            if self.is_type_ignored(raw_type) {
                continue;
            }

            out.push(TokenSpan {
                token_type: raw_type,
                utf8_start: start,
                utf8_end: end,
                utf16_start: u16_start,
                utf16_end: u16_end,
            });
        }
    }

    pub fn tokenize<'a>(self, input: &'a str) -> Vec<Token<'a>> {
        self.tokenize_spans(input)
            .into_iter()
            .map(|sp| Token {
                content: &input[sp.utf8_start..sp.utf8_end],
                token_type: sp.token_type,
                utf8_start: sp.utf8_start,
                utf8_end: sp.utf8_end,
                utf16_start: sp.utf16_start,
                utf16_end: sp.utf16_end,
            })
            .collect()
    }

    pub fn tokenize_to_strings(self, input: &str) -> Vec<&str> {
        self.tokenize_spans(input)
            .into_iter()
            .map(|sp| &input[sp.utf8_start..sp.utf8_end])
            .collect()
    }

    pub fn detokenize(&self, tokens: &[&str]) -> String {
        TurkishDetokenizer::detokenize(tokens)
    }

    pub fn detokenize_tokens(&self, tokens: &[Token<'_>]) -> String {
        TurkishDetokenizer::detokenize_tokens(tokens)
    }
}

#[inline]
fn utf16_len_of(s: &str) -> usize {
    if s.is_ascii() {
        s.len()
    } else {
        s.chars().map(char::len_utf16).sum()
    }
}

#[inline]
fn next_char_at(s: &str, pos: usize) -> Option<(char, usize)> {
    let b = *s.as_bytes().get(pos)?;
    if b < 0x80 {
        Some((b as char, pos + 1))
    } else {
        let ch = s[pos..].chars().next()?;
        Some((ch, pos + ch.len_utf8()))
    }
}

#[inline]
fn is_tr_lower(c: char) -> bool {
    c.is_ascii_lowercase()
        || matches!(
            c,
            'ç' | 'ğ' | 'ı' | 'ö' | 'ş' | 'ü' | 'â' | 'î' | 'û'
        )
}

#[inline]
fn is_tr_upper(c: char) -> bool {
    c.is_ascii_uppercase()
        || matches!(
            c,
            'Ç' | 'Ğ' | 'İ' | 'Ö' | 'Ş' | 'Ü' | 'Â' | 'Î' | 'Û'
        )
}

#[inline]
fn is_tr_letter(c: char) -> bool {
    is_tr_lower(c) || is_tr_upper(c)
}

#[inline]
fn is_tr_alnum(c: char) -> bool {
    c.is_ascii_digit() || is_tr_letter(c)
}

#[inline]
fn is_tr_alnum_underscore(c: char) -> bool {
    c.is_ascii_digit() || c == '_' || is_tr_letter(c)
}

#[inline]
fn is_lexer_apostrophe(c: char) -> bool {
    c == '\'' || c == '’'
}

#[inline]
fn is_double_quote(c: char) -> bool {
    matches!(c, '"' | '”' | '“' | '»' | '«')
}

#[inline]
fn is_url_char(c: char) -> bool {
    is_tr_alnum(c)
        || matches!(
            c,
            '-' | '_'
                | '/'
                | '?'
                | '&'
                | '+'
                | ';'
                | '='
                | '['
                | ']'
                | '%'
                | '#'
                | '~'
                | ':'
        )
}

fn scan_digits(bytes: &[u8], mut p: usize) -> Option<usize> {
    let start = p;
    while p < bytes.len() && bytes[p].is_ascii_digit() {
        p += 1;
    }
    if p > start {
        Some(p)
    } else {
        None
    }
}

fn match_exp(bytes: &[u8], p: usize) -> Option<usize> {
    let b = *bytes.get(p)?;
    if b != b'e' && b != b'E' {
        return None;
    }
    let mut cur = p + 1;
    if matches!(bytes.get(cur), Some(b'+' | b'-')) {
        cur += 1;
    }
    scan_digits(bytes, cur)
}

fn match_apos_and_suffix(s: &str, p: usize) -> Option<usize> {
    let (ch, after_apos) = next_char_at(s, p)?;
    if !is_lexer_apostrophe(ch) {
        return None;
    }
    let mut cur = after_apos;
    let mut count = 0usize;
    while let Some((c, next)) = next_char_at(s, cur) {
        if is_tr_letter(c) {
            cur = next;
            count += 1;
        } else {
            break;
        }
    }
    if count > 0 {
        Some(cur)
    } else {
        None
    }
}

#[inline]
fn opt_apos_and_suffix(s: &str, p: usize) -> usize {
    match_apos_and_suffix(s, p).unwrap_or(p)
}

fn match_space_tab(s: &str, pos: usize) -> Option<usize> {
    let mut cur = pos;
    while let Some((c, next)) = next_char_at(s, cur) {
        if c == ' ' || c == '\t' || c == '\u{00A0}' {
            cur = next;
        } else {
            break;
        }
    }
    (cur > pos).then_some(cur)
}

fn match_time(s: &str, pos: usize) -> Option<usize> {
    let b = s.as_bytes();
    if pos + 5 > b.len() {
        return None;
    }
    let h0 = b[pos];
    let h1 = b[pos + 1];
    let valid_hour =
        ((h0 == b'0' || h0 == b'1') && h1.is_ascii_digit()) || (h0 == b'2' && (b'0'..=b'3').contains(&h1));
    if !valid_hour {
        return None;
    }
    let sep1 = b[pos + 2];
    if sep1 != b':' && sep1 != b'.' {
        return None;
    }
    let m0 = b[pos + 3];
    let m1 = b[pos + 4];
    if !(b'0'..=b'5').contains(&m0) || !m1.is_ascii_digit() {
        return None;
    }
    let mut best = opt_apos_and_suffix(s, pos + 5);
    if pos + 8 <= b.len() {
        let sep2 = b[pos + 5];
        let s0 = b[pos + 6];
        let s1 = b[pos + 7];
        if (sep2 == b':' || sep2 == b'.') && (b'0'..=b'5').contains(&s0) && s1.is_ascii_digit() {
            let end2 = opt_apos_and_suffix(s, pos + 8);
            if end2 > best {
                best = end2;
            }
        }
    }
    Some(best)
}

fn match_date_with_sep(s: &str, pos: usize, sep: u8) -> Option<usize> {
    let b = s.as_bytes();
    let d1_end = scan_digits(b, pos)?;
    let d_len = d1_end - pos;
    if d_len == 0 || d_len > 2 {
        return None;
    }
    if d_len == 2 && b[pos] > b'3' {
        return None;
    }
    if b.get(d1_end) != Some(&sep) {
        return None;
    }
    let m_start = d1_end + 1;
    let m_end = scan_digits(b, m_start)?;
    let m_len = m_end - m_start;
    if m_len == 0 || m_len > 2 {
        return None;
    }
    if m_len == 2 && b[m_start] > b'1' {
        return None;
    }
    if b.get(m_end) != Some(&sep) {
        return None;
    }
    let y_start = m_end + 1;
    let mut best: Option<usize> = None;

    // 4-digit year: 1[7-9]\d\d | 20\d\d
    if y_start + 4 <= b.len()
        && b[y_start + 2].is_ascii_digit()
        && b[y_start + 3].is_ascii_digit()
        && ((b[y_start] == b'1' && (b'7'..=b'9').contains(&b[y_start + 1]))
            || (b[y_start] == b'2' && b[y_start + 1] == b'0'))
    {
        best = Some(opt_apos_and_suffix(s, y_start + 4));
    }

    // 2-digit year: \d\d
    if y_start + 2 <= b.len() && b[y_start].is_ascii_digit() && b[y_start + 1].is_ascii_digit() {
        let end2 = opt_apos_and_suffix(s, y_start + 2);
        best = Some(best.map_or(end2, |prev| prev.max(end2)));
    }

    best
}

fn match_date(s: &str, pos: usize) -> Option<usize> {
    match (
        match_date_with_sep(s, pos, b'.'),
        match_date_with_sep(s, pos, b'/'),
    ) {
        (Some(a), Some(b)) => Some(a.max(b)),
        (Some(a), None) => Some(a),
        (None, Some(b)) => Some(b),
        (None, None) => None,
    }
}

fn match_number(s: &str, pos: usize) -> Option<usize> {
    let b = s.as_bytes();
    let mut best = 0usize;

    let has_sign = matches!(b.get(pos), Some(b'+' | b'-'));
    let sign_after = if has_sign { pos + 1 } else { pos };

    if let Some(int1_end) = scan_digits(b, sign_after) {
        // Alt 3: [+\-]? Integer AposAndSuffix?
        best = best.max(opt_apos_and_suffix(s, int1_end));

        // Alt 2: [+\-]? Integer Exp AposAndSuffix?
        if let Some(exp_end) = match_exp(b, int1_end) {
            best = best.max(opt_apos_and_suffix(s, exp_end));
        }

        // Alt 1: [+\-]? Integer [.,] Integer Exp? AposAndSuffix?
        if matches!(b.get(int1_end), Some(b'.' | b',')) {
            if let Some(int2_end) = scan_digits(b, int1_end + 1) {
                best = best.max(opt_apos_and_suffix(s, int2_end));
                if let Some(exp_end) = match_exp(b, int2_end) {
                    best = best.max(opt_apos_and_suffix(s, exp_end));
                }
            }
        }

        // Alt 4: [+\-]? Integer '/' Integer AposAndSuffix?
        if b.get(int1_end) == Some(&b'/') {
            if let Some(int2_end) = scan_digits(b, int1_end + 1) {
                best = best.max(opt_apos_and_suffix(s, int2_end));
            }
        }

        if !has_sign {
            // Alt 5 & 6: (Integer '.')+ Integer AposAndSuffix? and (Integer ',')+ Integer AposAndSuffix?
            for sep in *b".," {
                let mut cur = int1_end;
                while b.get(cur) == Some(&sep) {
                    if let Some(next_int_end) = scan_digits(b, cur + 1) {
                        cur = next_int_end;
                        best = best.max(opt_apos_and_suffix(s, cur));
                    } else {
                        break;
                    }
                }
            }

            // Alt 7: Integer '.'? AposAndSuffix?
            if b.get(int1_end) == Some(&b'.') {
                best = best.max(opt_apos_and_suffix(s, int1_end + 1));
            }
        }
    }

    (best > pos).then_some(best)
}

fn match_url_fragment(s: &str, pos: usize) -> Option<usize> {
    let (first, after_first) = next_char_at(s, pos)?;
    if !is_url_char(first) {
        return None;
    }
    let mut last_url_char_end = after_first;
    let mut cur = after_first;
    while let Some((c, next)) = next_char_at(s, cur) {
        if is_url_char(c) {
            last_url_char_end = next;
            cur = next;
        } else if c == '.' {
            cur = next;
        } else {
            break;
        }
    }
    Some(last_url_char_end)
}

fn match_url(s: &str, pos: usize) -> Option<usize> {
    let rest = &s[pos..];
    let mut best = 0usize;

    // Alt 1: ('http://'|'https://') URLFragment AposAndSuffix?
    for prefix in ["https://", "http://"] {
        if rest.starts_with(prefix) {
            if let Some(frag_end) = match_url_fragment(s, pos + prefix.len()) {
                best = best.max(opt_apos_and_suffix(s, frag_end));
            }
        }
    }

    // Alt 2: ('http://'|'https://')? 'www.' URLFragment AposAndSuffix?
    for prefix in ["https://www.", "http://www.", "www."] {
        if rest.starts_with(prefix) {
            if let Some(frag_end) = match_url_fragment(s, pos + prefix.len()) {
                best = best.max(opt_apos_and_suffix(s, frag_end));
            }
        }
    }

    // Alt 3: [0-9a-zA-Z_]+ ('.com'|'.org'|'.edu'|'.gov'|'.net'|'.info') ('.tr')? ('/' URLFragment)? AposAndSuffix?
    let b = s.as_bytes();
    let mut host_end = pos;
    while host_end < b.len() && (b[host_end].is_ascii_alphanumeric() || b[host_end] == b'_') {
        host_end += 1;
    }
    if host_end > pos {
        let after_host = &s[host_end..];
        for tld in [".com", ".org", ".edu", ".gov", ".net", ".info"] {
            if after_host.starts_with(tld) {
                let mut cur = host_end + tld.len();
                if s[cur..].starts_with(".tr") {
                    cur += 3;
                }
                best = best.max(opt_apos_and_suffix(s, cur));
                if b.get(cur) == Some(&b'/') {
                    if let Some(frag_end) = match_url_fragment(s, cur + 1) {
                        best = best.max(opt_apos_and_suffix(s, frag_end));
                    }
                }
                break;
            }
        }
    }

    (best > pos).then_some(best)
}

fn scan_tr_alnum_underscore_plus(s: &str, pos: usize) -> Option<usize> {
    let mut cur = pos;
    while let Some((c, next)) = next_char_at(s, cur) {
        if is_tr_alnum_underscore(c) {
            cur = next;
        } else {
            break;
        }
    }
    (cur > pos).then_some(cur)
}

fn match_email_domain_part(s: &str, pos: usize) -> Option<usize> {
    let mut cur = scan_tr_alnum_underscore_plus(s, pos)?;
    while s.as_bytes().get(cur) == Some(&b'-') {
        if let Some(next_end) = scan_tr_alnum_underscore_plus(s, cur + 1) {
            cur = next_end;
        } else {
            break;
        }
    }
    Some(cur)
}

fn match_email(s: &str, pos: usize) -> Option<usize> {
    let mut cur = scan_tr_alnum_underscore_plus(s, pos)?;
    while matches!(s.as_bytes().get(cur), Some(b'.' | b'-' | b'+')) {
        if let Some(next_end) = scan_tr_alnum_underscore_plus(s, cur + 1) {
            cur = next_end;
        } else {
            break;
        }
    }
    if s.as_bytes().get(cur) != Some(&b'@') {
        return None;
    }
    cur = match_email_domain_part(s, cur + 1)?;
    let mut dot_domains = 0usize;
    while s.as_bytes().get(cur) == Some(&b'.') {
        if let Some(next_dom) = match_email_domain_part(s, cur + 1) {
            cur = next_dom;
            dot_domains += 1;
        } else {
            break;
        }
    }
    if dot_domains == 0 {
        return None;
    }
    Some(opt_apos_and_suffix(s, cur))
}

fn match_emoji_base_seq(s: &str, pos: usize) -> Option<usize> {
    let (ch, mut cur) = next_char_at(s, pos)?;
    if !is_emoji_base_char(ch) {
        return None;
    }
    while let Some((m, next)) = next_char_at(s, cur) {
        if is_emoji_modifier(m) {
            cur = next;
        } else {
            break;
        }
    }
    Some(cur)
}

fn match_emoji(s: &str, pos: usize) -> Option<usize> {
    let mut best = 0usize;
    if let Some((c1, p1)) = next_char_at(s, pos) {
        if is_regional_indicator(c1) {
            if let Some((c2, p2)) = next_char_at(s, p1) {
                if is_regional_indicator(c2) {
                    best = best.max(p2);
                }
            }
        }
    }
    if let Some(mut cur) = match_emoji_base_seq(s, pos) {
        loop {
            if s[cur..].starts_with('\u{200D}') {
                let after_zwj = cur + '\u{200D}'.len_utf8();
                if let Some(next_base) = match_emoji_base_seq(s, after_zwj) {
                    cur = next_base;
                    continue;
                }
            }
            break;
        }
        best = best.max(cur);
    }
    (best > pos).then_some(best)
}

static EMOTICONS: &[&str] = &[
    "¯\\_(ツ)_/¯",
    ";\u{2011})",
    ":\u{2011}/",
    ":'(",
    ":')",
    ":-)",
    ":-]",
    ":-D",
    "8-)",
    ":-(",
    ":^)",
    "O_o",
    "o_O",
    "O_O",
    "\\o/",
    ":)",
    ":D",
    ";)",
    ":(",
    ":P",
    ":p",
    ":|",
    "=|",
    "=)",
    "=(",
    ":/",
    "<3",
];

fn match_emoticon(s: &str, pos: usize) -> Option<usize> {
    let rest = &s[pos..];
    for &emo in EMOTICONS {
        if rest.starts_with(emo) {
            return Some(pos + emo.len());
        }
    }
    None
}

fn match_roman_numeral(s: &str, pos: usize) -> Option<usize> {
    let b = s.as_bytes();
    let mut cur = pos;
    while cur < b.len() && matches!(b[cur], b'I' | b'V' | b'X') {
        cur += 1;
    }
    if cur == pos {
        return None;
    }
    let mut best = opt_apos_and_suffix(s, cur);
    if b.get(cur) == Some(&b'.') {
        best = best.max(opt_apos_and_suffix(s, cur + 1));
    }
    Some(best)
}

fn match_abbrev_with_dots(s: &str, pos: usize) -> Option<usize> {
    let mut cur = pos;
    let mut pairs = 0usize;
    while let Some((c, after_c)) = next_char_at(s, cur) {
        if is_tr_upper(c) && s.as_bytes().get(after_c) == Some(&b'.') {
            cur = after_c + 1;
            pairs += 1;
        } else {
            break;
        }
    }
    if pairs == 0 {
        return None;
    }
    let mut best = opt_apos_and_suffix(s, cur);
    if let Some((c, after_c)) = next_char_at(s, cur) {
        if is_tr_upper(c) {
            best = best.max(opt_apos_and_suffix(s, after_c));
        }
    }
    Some(best)
}

fn scan_tr_alnum_plus(s: &str, pos: usize) -> Option<usize> {
    let mut cur = pos;
    while let Some((c, next)) = next_char_at(s, cur) {
        if is_tr_alnum(c) {
            cur = next;
        } else {
            break;
        }
    }
    (cur > pos).then_some(cur)
}

fn match_word_with_symbol(s: &str, pos: usize) -> Option<usize> {
    let first_end = scan_tr_alnum_plus(s, pos)?;
    let mut best = 0usize;

    // Alt 1: AllTurkishAlphanumerical+ ('-' AllTurkishAlphanumerical+)+ AposAndSuffix?
    let mut cur = first_end;
    let mut hyphens = 0usize;
    while s.as_bytes().get(cur) == Some(&b'-') {
        if let Some(next_end) = scan_tr_alnum_plus(s, cur + 1) {
            cur = next_end;
            hyphens += 1;
        } else {
            break;
        }
    }
    if hyphens > 0 {
        best = best.max(opt_apos_and_suffix(s, cur));
    }

    // Alt 2: AllTurkishAlphanumerical+ AposAndSuffix+
    let mut cur2 = first_end;
    let mut apos_count = 0usize;
    while let Some(next_apos_end) = match_apos_and_suffix(s, cur2) {
        cur2 = next_apos_end;
        apos_count += 1;
    }
    if apos_count > 0 {
        best = best.max(cur2);
    }

    (best > pos).then_some(best)
}

#[inline]
fn is_single_punct_char(c: char) -> bool {
    is_lexer_apostrophe(c)
        || is_double_quote(c)
        || matches!(
            c,
            '<' | '>'
                | '‘'
                | '…'
                | '='
                | '.'
                | ','
                | '!'
                | '?'
                | '%'
                | '$'
                | '&'
                | '*'
                | '+'
                | '@'
                | ':'
                | ';'
                | '®'
                | '™'
                | '©'
                | '℠'
                | '|'
                | '~'
                | '`'
                | '°'
                | '±'
                | '×'
                | '÷'
                | '§'
                | '–'
                | '—'
                | '₺'
                | '€'
                | '£'
                | '¥'
                | '\\'
                | '-'
                | '/'
                | '('
                | ')'
                | '['
                | ']'
                | '{'
                | '}'
                | '^'
        )
}

fn match_punctuation(s: &str, pos: usize) -> Option<usize> {
    let rest = &s[pos..];
    if rest.starts_with("...") || rest.starts_with("(!)") || rest.starts_with("(?)") {
        return Some(pos + 3);
    }
    let (ch, next) = next_char_at(s, pos)?;
    is_single_punct_char(ch).then_some(next)
}

#[inline]
fn is_unknown_word_part_char(c: char) -> bool {
    if matches!(c, ' ' | '\t' | '\u{00A0}' | '\n' | '\r') {
        return false;
    }
    if is_single_punct_char(c) {
        return false;
    }
    if is_emoji_base_char(c) {
        return false;
    }
    true
}

fn scan_unknown_word_part(s: &str, pos: usize) -> Option<usize> {
    let mut cur = pos;
    while let Some((c, next)) = next_char_at(s, cur) {
        if is_unknown_word_part_char(c) {
            cur = next;
        } else {
            break;
        }
    }
    (cur > pos).then_some(cur)
}

fn match_unknown_word(s: &str, pos: usize) -> Option<usize> {
    let mut cur = scan_unknown_word_part(s, pos)?;
    while let Some((ch, after_apos)) = next_char_at(s, cur) {
        if is_lexer_apostrophe(ch) {
            if let Some(next_part) = scan_unknown_word_part(s, after_apos) {
                cur = next_part;
                continue;
            }
        }
        break;
    }
    Some(cur)
}

/// Evaluates all 21 `TurkishLexer.g4` rules at `pos`, returning `(TokenType, end_byte_pos)`
/// using ANTLR4's exact maximal-munch + earliest-rule tie-breaking semantics.
fn match_raw_lexer_token(s: &str, pos: usize) -> (TokenType, usize) {
    let (first_ch, first_end) = next_char_at(s, pos).expect("pos < s.len()");

    // Fast path for whitespace and newlines (disjoint from all other rules!)
    if first_ch == '\n' || first_ch == '\r' {
        return (TokenType::NewLine, first_end);
    }
    if first_ch == ' ' || first_ch == '\t' || first_ch == '\u{00A0}' {
        return (TokenType::SpaceTab, match_space_tab(s, pos).unwrap());
    }

    let mut best_type = TokenType::Unknown;
    let mut best_end = first_end;
    let mut consider = |ty: TokenType, candidate: Option<usize>| {
        if let Some(end) = candidate {
            // Strictly greater (`>`) preserves earliest-rule tie-breaking!
            if end > best_end || (end == best_end && best_type == TokenType::Unknown) {
                best_type = ty;
                best_end = end;
            }
        }
    };

    // 3. Time
    if first_ch.is_ascii_digit() {
        consider(TokenType::Time, match_time(s, pos));
        // 4. Date
        consider(TokenType::Date, match_date(s, pos));
    }

    // 5. PercentNumeral
    if first_ch == '%' {
        consider(TokenType::PercentNumeral, match_number(s, pos + 1));
    }

    // 6. Number
    if first_ch.is_ascii_digit() || first_ch == '+' || first_ch == '-' {
        consider(TokenType::Number, match_number(s, pos));
    }

    // 7. URL
    if first_ch.is_ascii_alphanumeric() || first_ch == '_' {
        consider(TokenType::URL, match_url(s, pos));
    }

    // 8. Email
    if is_tr_alnum_underscore(first_ch) {
        consider(TokenType::Email, match_email(s, pos));
    }

    // 9. HashTag
    if first_ch == '#' {
        if let Some(end_alnum) = scan_tr_alnum_underscore_plus(s, pos + 1) {
            consider(TokenType::HashTag, Some(opt_apos_and_suffix(s, end_alnum)));
        }
    }

    // 10. Mention
    if first_ch == '@' {
        if let Some(end_alnum) = scan_tr_alnum_underscore_plus(s, pos + 1) {
            consider(TokenType::Mention, Some(opt_apos_and_suffix(s, end_alnum)));
        }
    }

    // 11. MetaTag
    if first_ch == '<' {
        if let Some(end_alnum) = scan_tr_alnum_underscore_plus(s, pos + 1) {
            if s.as_bytes().get(end_alnum) == Some(&b'>') {
                consider(TokenType::MetaTag, Some(end_alnum + 1));
            }
        }
    }

    // 12. Emoji
    if first_ch as u32 >= 0x231A {
        consider(TokenType::Emoji, match_emoji(s, pos));
    }

    // 13. Emoticon
    consider(TokenType::Emoticon, match_emoticon(s, pos));

    // 14. RomanNumeral
    if matches!(first_ch, 'I' | 'V' | 'X') {
        consider(TokenType::RomanNumeral, match_roman_numeral(s, pos));
    }

    // 15. AbbreviationWithDots
    if is_tr_upper(first_ch) {
        consider(TokenType::AbbreviationWithDots, match_abbrev_with_dots(s, pos));
    }

    // 16. Word
    if is_tr_letter(first_ch) {
        let mut cur = first_end;
        while let Some((c, next)) = next_char_at(s, cur) {
            if is_tr_letter(c) {
                cur = next;
            } else {
                break;
            }
        }
        consider(TokenType::Word, Some(cur));
    }

    // 17. WordAlphanumerical & 18. WordWithSymbol
    if is_tr_alnum(first_ch) {
        consider(TokenType::WordAlphanumerical, scan_tr_alnum_plus(s, pos));
        consider(TokenType::WordWithSymbol, match_word_with_symbol(s, pos));
    }

    // 19. Punctuation
    consider(TokenType::Punctuation, match_punctuation(s, pos));

    // 20. UnknownWord
    if is_unknown_word_part_char(first_ch) {
        consider(TokenType::UnknownWord, match_unknown_word(s, pos));
    }

    (best_type, best_end)
}
