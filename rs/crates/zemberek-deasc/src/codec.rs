use alloc::string::String;
use alloc::vec::Vec;
use zemberek_core::TurkishAlphabet;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Casing {
    Lower = 0,
    Title = 1,
    Upper = 2,
    Mixed = 3,
}

/// Character-accurate (`u16` UTF-16 offset + UTF-8 byte offset) token span in an input text.
#[derive(Clone, Debug)]
pub struct DeascToken {
    /// UTF-16 code unit start index in `sentence_u16`.
    pub start_u16: usize,
    /// UTF-16 code unit end index in `sentence_u16`.
    pub end_u16: usize,
    /// UTF-8 byte start index in `text`.
    pub start_utf8: usize,
    /// UTF-8 byte end index in `text`.
    pub end_utf8: usize,
    pub original: String,
    pub ascii_lower: String,
    pub casing: Casing,
    /// UTF-16 code unit index of first apostrophe in `original` (`None` if `< 0` in Java).
    pub apostrophe_index: Option<usize>,
    pub numeral_apostrophe: bool,
    pub immutable: bool,
    pub sentence_start: bool,
    pub right_punct: char,
    pub has_pinned_turkish: bool,
}

impl DeascToken {
    #[inline]
    pub fn has_apostrophe(&self) -> bool {
        let orig_u16_len = u16_len(&self.original);
        self.apostrophe_index
            .is_some_and(|ap| ap > 0 && ap + 1 < orig_u16_len)
    }

    #[inline]
    pub fn is_plain_cacheable_word(&self) -> bool {
        !self.immutable
            && !self.has_pinned_turkish
            && !self.numeral_apostrophe
            && self.apostrophe_index.is_none()
            && (self.casing == Casing::Lower || self.casing == Casing::Title)
    }

    pub fn without_dotless_i_pin(&self) -> Self {
        let mut copy = self.clone();
        copy.has_pinned_turkish = contains_non_dotless_turkish_diacritic(&self.original);
        copy
    }
}

pub struct SqueezedWord {
    pub squeezed_ascii: String,
    pub run_starts: Vec<usize>,
    pub run_ends: Vec<usize>,
}

impl SqueezedWord {
    pub fn project_to_original_length(&self, restored_squeezed_lower: &str) -> String {
        let chars: Vec<char> = restored_squeezed_lower.chars().collect();
        if chars.len() != self.run_starts.len() {
            return String::from(restored_squeezed_lower);
        }
        let orig_len = *self.run_ends.last().unwrap_or(&0);
        let mut out = String::with_capacity(orig_len * 2);
        for (i, &ch) in chars.iter().enumerate() {
            for _ in self.run_starts[i]..self.run_ends[i] {
                out.push(ch);
            }
        }
        out
    }
}

#[inline]
pub fn u16_len(s: &str) -> usize {
    if s.is_ascii() {
        s.len()
    } else {
        s.chars().map(char::len_utf16).sum()
    }
}

#[inline]
pub fn is_codec_apostrophe(c: char) -> bool {
    matches!(c, '\'' | '’' | '‘' | '`')
}

#[inline]
pub fn is_pinned_turkish_diacritic(c: char) -> bool {
    TurkishAlphabet::is_turkish_specific(c)
}

#[inline]
pub fn is_non_dotless_turkish_diacritic(c: char) -> bool {
    is_pinned_turkish_diacritic(c) && c != 'ı'
}

#[inline]
pub fn is_codec_letter(c: char) -> bool {
    c.is_ascii_alphabetic() || is_pinned_turkish_diacritic(c)
}

#[inline]
pub fn is_java_digit(c: char) -> bool {
    c.is_ascii_digit() || (!c.is_ascii() && c.is_numeric() && matches_unicode_nd(c))
}

#[inline]
fn matches_unicode_nd(c: char) -> bool {
    let u = c as u32;
    (0x0660..=0x0669).contains(&u) || (0x06F0..=0x06F9).contains(&u)
}

#[inline]
pub fn contains_pinned_turkish(s: &str) -> bool {
    s.chars().any(is_pinned_turkish_diacritic)
}

#[inline]
pub fn contains_non_dotless_turkish_diacritic(s: &str) -> bool {
    s.chars().any(is_non_dotless_turkish_diacritic)
}

#[inline]
pub fn contains_dotless_i(s: &str) -> bool {
    s.contains('ı')
}

#[inline]
pub fn to_turkish_lower_char(c: char) -> char {
    TurkishAlphabet::to_lower_char(c)
}

pub fn to_turkish_lower(s: &str) -> String {
    s.chars().map(to_turkish_lower_char).collect()
}

#[inline]
pub fn to_turkish_upper_char(c: char) -> char {
    TurkishAlphabet::to_upper_char(c)
}

#[inline]
pub fn to_ascii_lower_char(c: char) -> char {
    if c.is_ascii_lowercase() {
        return c;
    }
    let asc = TurkishAlphabet::get_ascii_equal(c);
    if asc.is_ascii_lowercase() {
        return asc;
    }
    if asc.is_ascii_uppercase() {
        return ((asc as u8) + 32) as char;
    }
    if is_codec_apostrophe(asc) {
        '\''
    } else {
        TurkishAlphabet::to_lower_char(asc)
    }
}

pub fn to_ascii_lower(s: &str) -> String {
    s.chars().map(to_ascii_lower_char).collect()
}

#[inline]
pub fn strip_circumflex_char(c: char) -> char {
    match c {
        'â' => 'a',
        'Â' => 'A',
        'î' => 'i',
        'Î' => 'I',
        'û' => 'u',
        'Û' => 'U',
        _ => c,
    }
}

pub fn strip_circumflex(s: &str) -> String {
    if !TurkishAlphabet::contains_circumflex(s) {
        return String::from(s);
    }
    s.chars().map(strip_circumflex_char).collect()
}

#[inline]
pub fn has_ambiguous_ascii(ascii_lower: &str) -> bool {
    ascii_lower
        .bytes()
        .any(|b| matches!(b, b'a' | b'c' | b'g' | b'i' | b'o' | b's' | b'u'))
}

#[inline]
pub fn has_word_final_double_letter(ascii_lower: &str) -> bool {
    let b = ascii_lower.as_bytes();
    if b.len() < 5 {
        return false;
    }
    let last = b[b.len() - 1];
    last.is_ascii_lowercase() && last == b[b.len() - 2]
}

#[inline]
pub const fn vowel_index(c: char) -> Option<usize> {
    match c {
        'a' | 'â' => Some(0),
        'e' => Some(1),
        'ı' => Some(2),
        'i' | 'î' => Some(3),
        'o' => Some(4),
        'ö' => Some(5),
        'u' => Some(6),
        'ü' | 'û' => Some(7),
        _ => None,
    }
}

#[inline]
pub const fn is_turkish_vowel(c: char) -> bool {
    vowel_index(c).is_some()
}

#[inline]
pub const fn is_front_vowel(c: char) -> bool {
    matches!(c, 'e' | 'i' | 'î' | 'ö' | 'ü' | 'û')
}

#[inline]
pub const fn is_rounded_vowel(c: char) -> bool {
    matches!(c, 'o' | 'ö' | 'u' | 'ü' | 'û')
}

#[inline]
pub const fn high_harmonic_vowel(front: bool, rounded: bool) -> char {
    match (front, rounded) {
        (true, true) => 'ü',
        (true, false) => 'i',
        (false, true) => 'u',
        (false, false) => 'ı',
    }
}

pub fn is_compatible_with_input(
    input_original: &str,
    candidate_lower: &str,
    pin_dotless_i: bool,
) -> bool {
    let mut it_o = input_original.chars();
    let mut it_c = candidate_lower.chars();
    loop {
        match (it_o.next(), it_c.next()) {
            (None, None) => return true,
            (Some(_), None) | (None, Some(_)) => return false,
            (Some(orig), Some(cand)) => {
                if is_codec_apostrophe(orig) && is_codec_apostrophe(cand) {
                    continue;
                }
                let is_pin = if pin_dotless_i {
                    is_pinned_turkish_diacritic(orig)
                } else {
                    is_non_dotless_turkish_diacritic(orig)
                };
                if is_pin {
                    if to_turkish_lower_char(orig) != cand {
                        return false;
                    }
                } else if to_ascii_lower_char(orig) != to_ascii_lower_char(cand) {
                    return false;
                }
            }
        }
    }
}

pub fn apply_casing_and_pins(
    token: &DeascToken,
    cand_lower: &str,
    foreign_stem_length: usize,
) -> String {
    if token.casing == Casing::Lower && !token.has_pinned_turkish && foreign_stem_length == 0 {
        return String::from(cand_lower);
    }
    let orig_chars: Vec<char> = token.original.chars().collect();
    let cand_chars: Vec<char> = cand_lower.chars().collect();
    if orig_chars.len() != cand_chars.len() {
        return token.original.clone();
    }
    let mut out = String::with_capacity(token.original.len() + 4);
    for i in 0..orig_chars.len() {
        let o = orig_chars[i];
        let pinned = token.has_pinned_turkish && is_pinned_turkish_diacritic(o);
        if is_codec_apostrophe(o) || !is_codec_letter(o) || pinned || i < foreign_stem_length {
            out.push(o);
        } else {
            let cl = cand_chars[i];
            if to_ascii_lower_char(o) != to_ascii_lower_char(cl) {
                out.push(o);
            } else if o.is_uppercase() {
                out.push(to_turkish_upper_char(cl));
            } else {
                out.push(cl);
            }
        }
    }
    out
}

pub fn detect_casing_chars(chars: &[char], limit_len: usize) -> Casing {
    let len = if limit_len > 0 {
        chars.len().min(limit_len)
    } else {
        chars.len()
    };
    let mut upper = 0usize;
    let mut lower = 0usize;
    let mut first_upper = false;
    let mut seen_letter = false;
    for &c in &chars[..len] {
        if !is_codec_letter(c) {
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
    if upper == 0 {
        return Casing::Lower;
    }
    if lower == 0 {
        return Casing::Upper;
    }
    if first_upper && upper == 1 {
        Casing::Title
    } else {
        Casing::Mixed
    }
}

pub fn is_camel_case_identifier(orig: &str) -> bool {
    if orig.chars().count() < 3 || contains_pinned_turkish(orig) {
        return false;
    }
    let mut seen_lower = false;
    for c in orig.chars() {
        if is_codec_apostrophe(c) {
            break;
        }
        if c.is_lowercase() {
            seen_lower = true;
        } else if c.is_uppercase() && seen_lower {
            return true;
        }
    }
    false
}

pub fn has_letter_elongation(ascii_lower: &str) -> bool {
    let b = ascii_lower.as_bytes();
    let mut run = 1usize;
    for i in 1..b.len() {
        if b[i] == b[i - 1] {
            run += 1;
            if run >= 3 {
                return true;
            }
        } else {
            run = 1;
        }
    }
    false
}

pub fn squeeze_elongations(ascii_lower: &str, max_run_len: usize) -> SqueezedWord {
    let chars: Vec<char> = ascii_lower.chars().collect();
    let n = chars.len();
    let mut squeezed = String::with_capacity(n);
    let mut starts = Vec::with_capacity(n);
    let mut ends = Vec::with_capacity(n);

    let mut i = 0usize;
    while i < n {
        let c = chars[i];
        let mut j = i + 1;
        if c.is_ascii_lowercase() {
            while j < n && chars[j] == c {
                j += 1;
            }
        }
        if j - i >= 3 {
            if max_run_len == 2 {
                squeezed.push(c);
                starts.push(i);
                ends.push(i + 1);
                squeezed.push(c);
                starts.push(i + 1);
                ends.push(j);
            } else {
                squeezed.push(c);
                starts.push(i);
                ends.push(j);
            }
        } else {
            for (k, &ch) in chars.iter().enumerate().take(j).skip(i) {
                squeezed.push(ch);
                starts.push(k);
                ends.push(k + 1);
            }
        }
        i = j;
    }

    SqueezedWord {
        squeezed_ascii: squeezed,
        run_starts: starts,
        run_ends: ends,
    }
}

pub fn restore_numeral_apostrophe(token: &DeascToken) -> String {
    let apos = token.apostrophe_index.unwrap_or(0);
    let orig_chars: Vec<char> = token.original.chars().collect();
    let ascii_chars: Vec<char> = token.ascii_lower.chars().collect();

    let num_part: String = orig_chars[..apos].iter().collect();
    let apos_ch = orig_chars[apos];
    let suffix_ascii: String = ascii_chars[apos + 1..].iter().collect();
    let spoken = spoken_turkish_number_stem(&num_part);

    let mut front = true;
    let mut rounded = false;
    for v in spoken.chars().rev() {
        if is_turkish_vowel(v) {
            front = is_front_vowel(v);
            rounded = is_rounded_vowel(v);
            break;
        }
    }

    let distributive = suffix_ascii.starts_with("sar") || suffix_ascii.starts_with("ser");
    let mut out = to_ascii_lower(&num_part);
    out.push(apos_ch);

    for (i, &c) in ascii_chars[apos + 1..].iter().enumerate() {
        let raw = orig_chars[apos + 1 + i];
        if token.has_pinned_turkish && is_pinned_turkish_diacritic(raw) {
            let tl = to_turkish_lower_char(raw);
            out.push(tl);
            if is_turkish_vowel(tl) {
                front = is_front_vowel(tl);
                rounded = is_rounded_vowel(tl);
            }
            continue;
        }
        if i == 0 && distributive && c == 's' {
            out.push('ş');
        } else if c == 'e' {
            front = true;
            rounded = false;
            out.push('e');
        } else if c == 'a' {
            front = false;
            rounded = false;
            out.push('a');
        } else if c == 'i' || c == 'u' {
            out.push(high_harmonic_vowel(front, rounded));
        } else {
            out.push(c);
        }
    }
    out
}

pub fn spoken_turkish_number_stem(raw_number: &str) -> &'static str {
    if raw_number.is_empty() {
        return "sıfır";
    }
    let last_dot = raw_number.rfind('.');
    let last_comma = raw_number.rfind(',');
    let last_sep = match (last_dot, last_comma) {
        (Some(a), Some(b)) => Some(a.max(b)),
        (Some(a), None) => Some(a),
        (None, Some(b)) => Some(b),
        (None, None) => None,
    };

    let active_digits: Vec<u8> = if let Some(sep) = last_sep {
        let tail = &raw_number[sep + 1..];
        if tail == "00" {
            let head = digits_only(&raw_number[..sep]);
            if all_zeros(&head) {
                return "sıfır";
            }
            head
        } else if tail.chars().count() == 3 && raw_number.as_bytes()[sep] == b'.' {
            digits_only(raw_number)
        } else {
            digits_only(tail)
        }
    } else {
        digits_only(raw_number)
    };

    if all_zeros(&active_digits) {
        return "sıfır";
    }
    let len = active_digits.len();
    let last = active_digits[len - 1];
    if last != b'0' {
        return match last {
            b'1' => "bir",
            b'2' => "iki",
            b'3' => "üç",
            b'4' => "dört",
            b'5' => "beş",
            b'6' => "altı",
            b'7' => "yedi",
            b'8' => "sekiz",
            b'9' => "dokuz",
            _ => "sıfır",
        };
    }

    let mut trailing_zeros = 0usize;
    for &d in active_digits.iter().rev() {
        if d == b'0' {
            trailing_zeros += 1;
        } else {
            break;
        }
    }
    if trailing_zeros == 1 && len >= 2 {
        return match active_digits[len - 2] {
            b'1' => "on",
            b'2' => "yirmi",
            b'3' => "otuz",
            b'4' => "kırk",
            b'5' => "elli",
            b'6' => "altmış",
            b'7' => "yetmiş",
            b'8' => "seksen",
            b'9' => "doksan",
            _ => "sıfır",
        };
    }
    if trailing_zeros == 2 {
        return "yüz";
    }
    if (3..=5).contains(&trailing_zeros) {
        return "bin";
    }
    if (6..=8).contains(&trailing_zeros) {
        return "milyon";
    }
    if (9..=11).contains(&trailing_zeros) {
        return "milyar";
    }
    "trilyon"
}

fn digits_only(s: &str) -> Vec<u8> {
    let mut out = Vec::with_capacity(s.len());
    for c in s.chars() {
        if c.is_ascii_digit() {
            out.push(c as u8);
        } else if is_java_digit(c) {
            let u = c as u32;
            let d = if (0x0660..=0x0669).contains(&u) {
                (u - 0x0660) as u8 + b'0'
            } else if (0x06F0..=0x06F9).contains(&u) {
                (u - 0x06F0) as u8 + b'0'
            } else {
                b'0'
            };
            out.push(d);
        }
    }
    out
}

fn all_zeros(digits: &[u8]) -> bool {
    digits.is_empty() || digits.iter().all(|&d| d == b'0')
}

struct CharPos {
    ch: char,
    utf8_start: usize,
    utf8_end: usize,
    u16_start: usize,
    u16_end: usize,
}

/// Exact port of `TurkishTextCodec.tokenize(String)`, returning `(tokens, text_u16)`.
pub fn tokenize_with_u16(text: &str) -> (Vec<DeascToken>, Vec<u16>) {
    let mut cps: Vec<CharPos> = Vec::with_capacity(text.len());
    let mut text_u16: Vec<u16> = Vec::with_capacity(text.len());
    let mut u16_buf = [0u16; 2];

    for (b_idx, ch) in text.char_indices() {
        let u_start = text_u16.len();
        let encoded = ch.encode_utf16(&mut u16_buf);
        text_u16.extend_from_slice(encoded);
        let u_end = text_u16.len();
        cps.push(CharPos {
            ch,
            utf8_start: b_idx,
            utf8_end: b_idx + ch.len_utf8(),
            u16_start: u_start,
            u16_end: u_end,
        });
    }

    let n = cps.len();
    let mut tokens = Vec::with_capacity((n / 6).max(4));
    let mut next_is_sentence_start = true;
    let mut pos = 0usize;

    while pos < n {
        while pos < n && TurkishAlphabet::is_java_whitespace(cps[pos].ch) {
            let ws = cps[pos].ch;
            if ws == '\n' || ws == '\r' {
                next_is_sentence_start = true;
            }
            pos += 1;
        }
        if pos >= n {
            break;
        }
        let chunk_start = pos;
        while pos < n && !TurkishAlphabet::is_java_whitespace(cps[pos].ch) {
            pos += 1;
        }
        let chunk_end = pos;

        if is_immutable_chunk(text, &cps, chunk_start, chunk_end) {
            let b_start = cps[chunk_start].utf8_start;
            let b_end = cps[chunk_end - 1].utf8_end;
            let orig = &text[b_start..b_end];
            tokens.push(DeascToken {
                start_u16: cps[chunk_start].u16_start,
                end_u16: cps[chunk_end - 1].u16_end,
                start_utf8: b_start,
                end_utf8: b_end,
                original: String::from(orig),
                ascii_lower: to_ascii_lower(orig),
                casing: Casing::Mixed,
                apostrophe_index: None,
                numeral_apostrophe: false,
                immutable: true,
                sentence_start: next_is_sentence_start,
                right_punct: ' ',
                has_pinned_turkish: contains_pinned_turkish(orig),
            });
            next_is_sentence_start = false;
            continue;
        }

        let mut i = chunk_start;
        while i < chunk_end {
            let c = cps[i].ch;
            if matches!(c, '.' | '!' | '?' | '…') {
                next_is_sentence_start = true;
                i += 1;
                continue;
            }
            if !is_codec_letter(c) && !is_java_digit(c) {
                i += 1;
                continue;
            }

            let tok_start = i;
            let starts_with_digit = is_java_digit(c);
            let (tok_end, apos_rel) = if starts_with_digit {
                scan_number_span(&cps, tok_start, chunk_end)
            } else {
                scan_word_span(&cps, tok_start, chunk_end)
            };
            i = tok_end;

            let right_punct = find_right_punct(&cps, tok_end, chunk_end, n);
            let tok = build_span_token(
                text,
                &cps,
                tok_start,
                tok_end,
                apos_rel,
                starts_with_digit,
                next_is_sentence_start,
                right_punct,
            );
            let looks_init_or_roman = casing_looks_like_initial_or_roman(&tok.original);
            tokens.push(tok);

            next_is_sentence_start = matches!(right_punct, '.' | '!' | '?')
                && !(looks_init_or_roman && right_punct == '.');
        }
    }

    (tokens, text_u16)
}

fn scan_number_span(cps: &[CharPos], start: usize, chunk_end: usize) -> (usize, Option<usize>) {
    let mut i = start;
    let mut apos_rel: Option<usize> = None;
    let base_u16 = cps[start].u16_start;
    while i < chunk_end {
        let ch = cps[i].ch;
        if is_java_digit(ch)
            || ((ch == '.' || ch == ',') && i + 1 < chunk_end && is_java_digit(cps[i + 1].ch))
            || (apos_rel.is_some() && is_codec_letter(ch))
        {
            i += 1;
        } else if apos_rel.is_none()
            && is_codec_apostrophe(ch)
            && i + 1 < chunk_end
            && is_codec_letter(cps[i + 1].ch)
        {
            apos_rel = Some(cps[i].u16_start - base_u16);
            i += 1;
        } else {
            break;
        }
    }
    (i, apos_rel)
}

fn scan_word_span(cps: &[CharPos], start: usize, chunk_end: usize) -> (usize, Option<usize>) {
    let mut i = start;
    let mut apos_rel: Option<usize> = None;
    let base_u16 = cps[start].u16_start;
    while i < chunk_end {
        let ch = cps[i].ch;
        if is_codec_letter(ch) || is_java_digit(ch) {
            i += 1;
        } else if is_codec_apostrophe(ch)
            && i + 1 < chunk_end
            && is_codec_letter(cps[i + 1].ch)
        {
            if apos_rel.is_none() {
                apos_rel = Some(cps[i].u16_start - base_u16);
            }
            i += 1;
        } else {
            break;
        }
    }
    (i, apos_rel)
}

#[allow(clippy::too_many_arguments)]
fn build_span_token(
    text: &str,
    cps: &[CharPos],
    tok_start: usize,
    tok_end: usize,
    apos_rel: Option<usize>,
    starts_with_digit: bool,
    sentence_start: bool,
    right_punct: char,
) -> DeascToken {
    let b_start = cps[tok_start].utf8_start;
    let b_end = cps[tok_end - 1].utf8_end;
    let u_start = cps[tok_start].u16_start;
    let u_end = cps[tok_end - 1].u16_end;
    let orig = &text[b_start..b_end];
    let numeral_apos = starts_with_digit && apos_rel.is_some_and(|a| a > 0);
    if starts_with_digit && !numeral_apos {
        return DeascToken {
            start_u16: u_start,
            end_u16: u_end,
            start_utf8: b_start,
            end_utf8: b_end,
            original: String::from(orig),
            ascii_lower: String::from(orig),
            casing: Casing::Lower,
            apostrophe_index: None,
            numeral_apostrophe: false,
            immutable: true,
            sentence_start,
            right_punct,
            has_pinned_turkish: false,
        };
    }

    let orig_chars: Vec<char> = orig.chars().collect();
    let limit = apos_rel.filter(|&a| a > 0).unwrap_or(orig_chars.len());
    let casing = detect_casing_chars(&orig_chars, limit);
    let contains_dig = orig_chars.iter().copied().any(is_java_digit);
    let immutable = is_roman_numeral(orig)
        || (apos_rel.is_none() && (is_camel_case_identifier(orig) || contains_dig));

    DeascToken {
        start_u16: u_start,
        end_u16: u_end,
        start_utf8: b_start,
        end_utf8: b_end,
        original: String::from(orig),
        ascii_lower: to_ascii_lower(orig),
        casing,
        apostrophe_index: apos_rel,
        numeral_apostrophe: numeral_apos,
        immutable,
        sentence_start,
        right_punct,
        has_pinned_turkish: contains_pinned_turkish(orig),
    }
}

fn find_right_punct(cps: &[CharPos], tok_end: usize, chunk_end: usize, total_len: usize) -> char {
    if tok_end < chunk_end {
        return cps[tok_end].ch;
    }
    let mut look = tok_end;
    while look < total_len && cps[look].ch == ' ' {
        look += 1;
    }
    if look < total_len {
        let p = cps[look].ch;
        if matches!(p, '?' | '!' | '.' | ',' | ';' | ':') {
            return p;
        }
    }
    ' '
}

fn casing_looks_like_initial_or_roman(orig: &str) -> bool {
    u16_len(orig) == 1 || is_roman_numeral(orig)
}

pub fn is_roman_numeral(orig: &str) -> bool {
    let b = orig.as_bytes();
    if b.len() < 2 || b.len() > 8 {
        return false;
    }
    if !b.iter().all(|&c| matches!(c, b'I' | b'V' | b'X')) {
        return false;
    }
    let mut x = 0usize;
    while x < b.len() && x < 3 && b[x] == b'X' {
        x += 1;
    }
    matches!(
        &b[x..],
        b"" | b"I" | b"II" | b"III" | b"IV" | b"V" | b"VI" | b"VII" | b"VIII" | b"IX"
    )
}

fn is_immutable_chunk(text: &str, cps: &[CharPos], start: usize, end: usize) -> bool {
    let mut s = start;
    let mut e = end;
    while s < e && matches!(cps[s].ch, '(' | '[' | '{' | '"' | '<' | '\'' | '“' | '‘') {
        s += 1;
    }
    while e > s
        && matches!(
            cps[e - 1].ch,
            ')' | ']' | '}' | '"' | '>' | '\'' | '”' | '’' | '.' | ',' | ';' | ':' | '!' | '?'
        )
    {
        e -= 1;
    }
    if e <= s + 1 {
        return false;
    }
    if cps[s].ch == '@' {
        return true;
    }
    let has_marker = cps[s..e]
        .iter()
        .any(|cp| matches!(cp.ch, '_' | '@' | ':' | '/' | '.'));
    if !has_marker {
        return false;
    }

    let chunk = &text[cps[s].utf8_start..cps[e - 1].utf8_end];
    if chunk.contains('_') || chunk.contains("://") {
        return true;
    }
    let lower_ascii: String = chunk.chars().map(|c| c.to_ascii_lowercase()).collect();
    if lower_ascii.starts_with("http://")
        || lower_ascii.starts_with("https://")
        || lower_ascii.starts_with("ftp://")
        || lower_ascii.starts_with("www.")
        || lower_ascii.starts_with("mailto:")
    {
        return true;
    }
    if let Some(at_idx) = chunk.find('@') {
        if at_idx > 0
            && at_idx + 1 < chunk.len()
            && chunk[at_idx + 1..].contains('.')
        {
            return true;
        }
    }
    if chunk.starts_with('/') || chunk.starts_with("./") || chunk.starts_with("../") {
        return true;
    }
    chunk.contains('.') && !chunk.ends_with('.') && matches_domain_name(chunk)
}

fn matches_domain_name(chunk: &str) -> bool {
    let host = match chunk.find('/') {
        Some(slash) => &chunk[..slash],
        None => chunk,
    };
    let mut labels = [""; 16];
    let mut count = 0usize;
    for part in host.split('.') {
        if part.is_empty() {
            return false;
        }
        if count < labels.len() {
            labels[count] = part;
        }
        count += 1;
    }
    if count < 3 {
        return false;
    }
    let check_cnt = count.min(labels.len());
    for &lbl in &labels[..check_cnt - 1] {
        if !lbl
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-')
        {
            return false;
        }
    }
    let tld = host.rsplit('.').next().unwrap_or("");
    matches!(
        tld.to_ascii_lowercase().as_str(),
        "com" | "org" | "net" | "edu" | "gov" | "tr" | "io" | "dev" | "ai" | "co" | "info" | "biz"
    )
}
