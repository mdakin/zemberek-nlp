use alloc::string::String;
use alloc::vec::Vec;
use zemberek_core::TurkishAlphabet;
use crate::lexer::{TokenType, TurkishTokenizer};
use crate::sentence::is_java_digit;

/// Casing shape classification matching `zemberek.normalization.v2.NormV2Types.CasingShape`.
#[repr(u8)]
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum CasingShape {
    Lower = 0,
    Title = 1,
    Upper = 2,
    Mixed = 3,
    NonAlpha = 4,
}

/// Token representation for sentence lattices (`NormV2Types.SpanToken`) with both UTF-8 and UTF-16 spans.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SpanToken {
    pub index: usize,
    pub surface: String,
    pub lower: String,
    pub ascii: String,
    pub token_type: TokenType,
    pub shape: CasingShape,
    pub utf8_start: usize,
    pub utf8_end: usize,
    pub utf16_start: usize,
    pub utf16_end: usize,
    pub adjacent_to_prev: bool,
}

impl SpanToken {
    #[inline]
    pub const fn is_immutable(&self) -> bool {
        matches!(
            self.token_type,
            TokenType::Punctuation
                | TokenType::Emoji
                | TokenType::Emoticon
                | TokenType::URL
                | TokenType::Email
                | TokenType::HashTag
                | TokenType::Mention
                | TokenType::Time
                | TokenType::Date
                | TokenType::PercentNumeral
                | TokenType::RomanNumeral
        )
    }

    #[inline]
    pub fn contains_digit(&self) -> bool {
        self.surface.chars().any(is_java_digit)
    }

    #[inline]
    pub fn has_upper_ascii_ambiguous_i(&self) -> bool {
        self.shape == CasingShape::Upper
            && self.surface.contains('I')
            && !has_upper_turkish_diacritics(&self.surface)
    }

    /// Zero-regex port of `NormV2Types.SpanToken.isRandomLaugh()` (`^(?:[asjkdfghwqe]{5,}|(?:ha){2,}h?|...)$`).
    pub fn is_random_laugh(&self) -> bool {
        if self.surface.chars().count() < 4 || !self.ascii.is_ascii() {
            return false;
        }
        let b = self.ascii.as_bytes();
        if b.len() >= 5
            && b.iter().all(|&c| {
                matches!(
                    c,
                    b'a' | b's' | b'j' | b'k' | b'd' | b'f' | b'g' | b'h' | b'w' | b'q' | b'e'
                )
            })
        {
            return true;
        }
        for &(c1, c2, trail) in &[
            (b'h', b'a', b'h'),
            (b'h', b'e', b'h'),
            (b'h', b'i', b'h'),
            (b'h', b'o', b'h'),
            (b's', b'j', b's'),
            (b'j', b's', b'j'),
            (b'a', b'h', b'a'),
        ] {
            if matches_alternating_laugh(b, c1, c2, trail) {
                return true;
            }
        }
        false
    }
}

fn matches_alternating_laugh(b: &[u8], c1: u8, c2: u8, trail: u8) -> bool {
    if b.len() < 4 {
        return false;
    }
    let pairs = b.len() / 2;
    if pairs < 2 {
        return false;
    }
    for i in 0..pairs {
        if b[2 * i] != c1 || b[2 * i + 1] != c2 {
            return false;
        }
    }
    b.len().is_multiple_of(2) || b[b.len() - 1] == trail
}

#[inline]
pub fn has_upper_turkish_diacritics(s: &str) -> bool {
    s.chars()
        .any(|c| matches!(c, 'İ' | 'Ç' | 'Ğ' | 'Ö' | 'Ş' | 'Ü'))
}

#[inline]
pub fn tr_lower_norm(s: &str) -> String {
    if s.is_empty() {
        return String::new();
    }
    if s.contains('’') {
        let mut replaced = String::with_capacity(s.len());
        for c in s.chars() {
            replaced.push(if c == '’' { '\'' } else { c });
        }
        TurkishAlphabet::to_lower_tr(&replaced)
    } else {
        TurkishAlphabet::to_lower_tr(s)
    }
}

pub fn detect_shape(s: &str) -> CasingShape {
    let mut upper = 0usize;
    let mut lower = 0usize;
    let mut first_upper = false;
    let mut seen_letter = false;
    let mut last_apos_byte: Option<usize> = None;

    for (b_idx, c) in s.char_indices() {
        if c == '\'' || c == '’' {
            last_apos_byte = Some(b_idx);
        }
        if !c.is_alphabetic() {
            continue;
        }
        let is_up = c.is_uppercase();
        if !seen_letter {
            first_upper = is_up;
            seen_letter = true;
        }
        if is_up {
            upper += 1;
        } else {
            lower += 1;
        }
    }

    if !seen_letter {
        return CasingShape::NonAlpha;
    }
    if upper == 0 {
        return CasingShape::Lower;
    }
    if lower == 0 {
        return if upper >= 2 {
            CasingShape::Upper
        } else {
            CasingShape::Title
        };
    }
    if first_upper && upper == 1 {
        return CasingShape::Title;
    }
    // Note: in Java `Math.max(s.indexOf('\''), s.indexOf('’'))` selects whichever of `'` or `’`'s
    // FIRST occurrence has the larger index (identical to the single apostrophe index in normal words).
    let ap_byte = match (s.find('\''), s.find('’')) {
        (Some(a), Some(b)) => Some(a.max(b)),
        (Some(a), None) => Some(a),
        (None, Some(b)) => Some(b),
        (None, None) => last_apos_byte,
    };
    if let Some(ap) = ap_byte {
        if ap > 0 && first_upper {
            let mut stem_upper_after_first = 0usize;
            let mut stem_lower_after_first = 0usize;
            for c in s[..ap].chars().skip(1) {
                if !c.is_alphabetic() {
                    continue;
                }
                if c.is_uppercase() {
                    stem_upper_after_first += 1;
                } else {
                    stem_lower_after_first += 1;
                }
            }
            if stem_upper_after_first == 0 {
                return CasingShape::Title;
            }
            if stem_lower_after_first == 0 {
                return CasingShape::Upper;
            }
            return CasingShape::Mixed;
        }
    }
    CasingShape::Mixed
}

/// Exact port of `NormV2Types.spanTokenize(sentence)`.
pub fn span_tokenize(sentence: &str) -> Vec<SpanToken> {
    let raw = TurkishTokenizer::DEFAULT.tokenize_spans(sentence);
    let mut out = Vec::with_capacity(raw.len());
    let mut prev_u16_end: Option<usize> = None;

    for (i, sp) in raw.into_iter().enumerate() {
        let surf = &sentence[sp.utf8_start..sp.utf8_end];
        let low = tr_lower_norm(surf);
        let ascii = TurkishAlphabet::to_ascii(&low);
        let adjacent_to_prev = prev_u16_end.is_some_and(|pe| sp.utf16_start == pe);
        prev_u16_end = Some(sp.utf16_end);
        out.push(SpanToken {
            index: i,
            surface: String::from(surf),
            lower: low,
            ascii,
            token_type: sp.token_type,
            shape: detect_shape(surf),
            utf8_start: sp.utf8_start,
            utf8_end: sp.utf8_end,
            utf16_start: sp.utf16_start,
            utf16_end: sp.utf16_end,
            adjacent_to_prev,
        });
    }
    out
}

/// Trait allowing `reconstruct_text` to work with both Step 1 tests and Step 7 `SpanArc`s.
pub trait SpanEditLike {
    fn start_idx(&self) -> usize;
    fn end_idx(&self) -> usize;
    fn norm_surface(&self) -> &str;
    fn word_count(&self) -> usize;
}

impl SpanEditLike for (usize, usize, &str) {
    fn start_idx(&self) -> usize {
        self.0
    }
    fn end_idx(&self) -> usize {
        self.1
    }
    fn norm_surface(&self) -> &str {
        self.2
    }
    fn word_count(&self) -> usize {
        1 + self.2.bytes().filter(|&b| b == b' ').count()
    }
}

/// Exact port of `NormV2Types.reconstructText(tokens, path)`.
pub fn reconstruct_text<A: SpanEditLike>(tokens: &[SpanToken], path: &[A]) -> String {
    if path.is_empty() {
        return String::new();
    }
    let mut sb = String::with_capacity(tokens.len() * 6);
    let mut prev_was_opening = false;
    let mut prev_attach_next = false;
    let mut in_double_quotes = false;
    let mut in_single_quotes = false;
    let mut prev_val: &str = "";
    let mut prev_type: Option<TokenType> = None;

    for arc in path {
        let val = arc.norm_surface();
        if val.is_empty() {
            continue;
        }
        let first_tok = &tokens[arc.start_idx()];
        let last_tok = &tokens[(tokens.len() - 1).min(arc.end_idx().saturating_sub(1))];
        let tok_type = if arc.word_count() == 1 {
            first_tok.token_type
        } else {
            TokenType::Word
        };
        let raw_adj = first_tok.adjacent_to_prev;

        let is_single_punct = tok_type == TokenType::Punctuation && val.chars().count() == 1;
        let mut attach_to_prev = false;
        let mut next_opening = false;
        let mut next_attach = false;

        if raw_adj
            && (val.starts_with('\'')
                || (tok_type == TokenType::Emoji && prev_type == Some(TokenType::Emoji))
                || is_emoticon_suffix(prev_type, prev_val, tok_type, val)
                || is_numeric_range_dash(prev_val, val))
        {
            attach_to_prev = true;
        } else if !raw_adj && is_emoticon_eyes_before_mouth(tokens, arc.end_idx(), val) {
            attach_to_prev = false;
        } else if is_single_punct && val == "\"" {
            attach_to_prev = in_double_quotes;
            next_opening = !in_double_quotes;
            in_double_quotes = !in_double_quotes;
        } else if is_single_punct && (val == "'" || val == "’") {
            attach_to_prev = in_single_quotes;
            next_opening = !in_single_quotes;
            in_single_quotes = !in_single_quotes;
        } else if is_single_punct && val == "/" {
            if raw_adj {
                attach_to_prev = true;
                next_attach = true;
            }
        } else if tok_type == TokenType::Punctuation && all_trailing_punct(val) {
            attach_to_prev = val != "%" || raw_adj;
            if starts_decimal_separator(is_single_punct, raw_adj, prev_val, val)
                || (val == "%" && !attach_to_prev)
            {
                next_attach = true;
            }
        } else if is_single_punct && is_opening_punct_char(val.chars().next().unwrap()) {
            next_opening = true;
        }

        if prev_attach_next && raw_adj && continues_slash_decimal_or_percent(prev_val, val) {
            attach_to_prev = true;
        }

        if sb.is_empty() || prev_was_opening || attach_to_prev {
            sb.push_str(val);
        } else {
            sb.push(' ');
            sb.push_str(val);
        }

        prev_was_opening = next_opening;
        prev_attach_next = next_attach;
        prev_val = val;
        prev_type = if prev_type == Some(TokenType::Emoticon) && attach_to_prev {
            Some(TokenType::Emoticon)
        } else {
            Some(last_tok.token_type)
        };
    }

    sb
}

const EMOTICON_MOUTHS: &str = "()dDpP/\\|oO03";
const EMOTICON_EXTRAS: &str = "()dDpP/\\|oO03><*";

fn is_emoticon_suffix(
    prev_type: Option<TokenType>,
    prev_val: &str,
    tok_type: TokenType,
    val: &str,
) -> bool {
    if prev_type == Some(TokenType::Emoticon) && tok_type == TokenType::Punctuation {
        return all_chars_in(val, EMOTICON_EXTRAS);
    }
    (prev_val == ":" || prev_val == ";") && all_chars_in(val, EMOTICON_MOUTHS)
}

fn is_emoticon_eyes_before_mouth(tokens: &[SpanToken], end_idx: usize, val: &str) -> bool {
    if val != ":" && val != ";" {
        return false;
    }
    let Some(next) = tokens.get(end_idx) else {
        return false;
    };
    next.adjacent_to_prev && all_chars_in(&next.surface, EMOTICON_MOUTHS)
}

fn is_numeric_range_dash(prev_val: &str, val: &str) -> bool {
    let mut it = val.chars();
    matches!(it.next(), Some('-'))
        && it.next().is_some_and(is_java_digit)
        && ends_with_digit(prev_val)
}

fn starts_decimal_separator(
    is_single_punct: bool,
    raw_adj: bool,
    prev_val: &str,
    val: &str,
) -> bool {
    is_single_punct && raw_adj && (val == "." || val == ",") && ends_with_digit(prev_val)
}

fn continues_slash_decimal_or_percent(prev_val: &str, val: &str) -> bool {
    if prev_val == "/" {
        return true;
    }
    (prev_val == "." || prev_val == "," || prev_val == "%")
        && val.chars().next().is_some_and(is_java_digit)
}

fn ends_with_digit(s: &str) -> bool {
    s.chars().next_back().is_some_and(is_java_digit)
}

fn all_chars_in(s: &str, allowed: &str) -> bool {
    !s.is_empty() && s.chars().all(|c| allowed.contains(c))
}

fn all_trailing_punct(s: &str) -> bool {
    !s.is_empty()
        && s.chars().all(|c| {
            matches!(
                c,
                '.' | ',' | ';' | ':' | '!' | '?' | '…' | ')' | ']' | '}' | '»' | '”' | '’' | '%'
            )
        })
}

fn is_opening_punct_char(c: char) -> bool {
    matches!(c, '(' | '[' | '{' | '«' | '“' | '‘')
}
