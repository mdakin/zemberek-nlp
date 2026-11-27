use alloc::string::String;
use alloc::vec::Vec;
use zemberek_core::TurkishAlphabet;
use crate::data::{SEGMENTER_ABBREVIATIONS, SENTENCE_BOUNDARY_WEIGHTS};

/// Half-open sentence span carrying both UTF-8 byte bounds `[utf8_start, utf8_end)` and
/// UTF-16 code-unit bounds `[utf16_start, utf16_end)`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SentenceSpan {
    pub utf8_start: usize,
    pub utf8_end: usize,
    pub utf16_start: usize,
    pub utf16_end: usize,
}

impl SentenceSpan {
    #[inline]
    pub fn text<'a>(&self, source: &'a str) -> &'a str {
        &source[self.utf8_start..self.utf8_end]
    }
}

/// Exact port of `zemberek.tokenization.TurkishSentenceExtractor` + `PerceptronSegmenter`.
#[derive(Clone, Copy, Debug)]
pub struct TurkishSentenceExtractor {
    pub do_not_split_in_double_quotes: bool,
}

impl TurkishSentenceExtractor {
    pub const DEFAULT: Self = Self {
        do_not_split_in_double_quotes: false,
    };

    pub const DO_NOT_SPLIT_IN_DOUBLE_QUOTES: Self = Self {
        do_not_split_in_double_quotes: true,
    };

    #[inline]
    fn weight_of(feature: &str) -> f32 {
        match SENTENCE_BOUNDARY_WEIGHTS.binary_search_by_key(&feature, |&(k, _)| k) {
            Ok(idx) => f32::from_bits(SENTENCE_BOUNDARY_WEIGHTS[idx].1),
            Err(_) => 0.0,
        }
    }

    #[inline]
    fn is_segmenter_abbrev(s: &str) -> bool {
        SEGMENTER_ABBREVIATIONS.binary_search(&s).is_ok()
    }

    #[inline]
    fn is_boundary_char(c: char) -> bool {
        matches!(c, '.' | '!' | '?' | '…')
    }

    #[inline]
    fn is_double_quote_char(c: char) -> bool {
        matches!(c, '"' | '”' | '“' | '»' | '«')
    }

    #[inline]
    fn potential_web_site(s: &str) -> bool {
        const WEB_WORDS: &[&str] = &[
            "http:", ".html", "www", ".tr", ".edu", ".com", ".net", ".gov", ".org", "@",
        ];
        WEB_WORDS.iter().any(|w| s.contains(w))
    }

    #[inline]
    fn meta_char(letter: char) -> char {
        if letter.is_uppercase() {
            if TurkishAlphabet::is_vowel(letter) {
                'V'
            } else {
                'C'
            }
        } else if letter.is_lowercase() {
            if TurkishAlphabet::is_vowel(letter) {
                'v'
            } else {
                'c'
            }
        } else if letter.is_numeric() && is_java_digit(letter) {
            'd'
        } else if TurkishAlphabet::is_java_whitespace(letter) {
            ' '
        } else if Self::is_boundary_char(letter) {
            letter
        } else {
            '-'
        }
    }

    /// Extracts untrimmed raw spans from `paragraph`, matching `TurkishSentenceExtractor.extractToSpans`.
    pub fn extract_raw_spans(self, paragraph: &str) -> Vec<SentenceSpan> {
        if paragraph.is_empty() {
            return Vec::new();
        }

        // Index codepoints with both UTF-8 and UTF-16 offsets so substring slicing is O(1).
        let mut chars: Vec<(char, usize, usize)> = Vec::with_capacity(paragraph.len());
        let mut u16_off = 0usize;
        for (b_off, ch) in paragraph.char_indices() {
            chars.push((ch, b_off, u16_off));
            u16_off += ch.len_utf16();
        }
        let total_u8 = paragraph.len();
        let total_u16 = u16_off;
        let n = chars.len();

        let quote_spans = if self.do_not_split_in_double_quotes {
            Self::double_quote_spans(&chars)
        } else {
            Vec::new()
        };

        let slice_str = |start_idx: usize, end_idx: usize| -> &str {
            if start_idx >= end_idx {
                return "";
            }
            let b_start = chars[start_idx].1;
            let b_end = if end_idx < n {
                chars[end_idx].1
            } else {
                total_u8
            };
            &paragraph[b_start..b_end]
        };

        let make_span = |start_idx: usize, end_idx: usize| -> SentenceSpan {
            let utf8_start = chars[start_idx].1;
            let utf16_start = chars[start_idx].2;
            let (utf8_end, utf16_end) = if end_idx < n {
                (chars[end_idx].1, chars[end_idx].2)
            } else {
                (total_u8, total_u16)
            };
            SentenceSpan {
                utf8_start,
                utf8_end,
                utf16_start,
                utf16_end,
            }
        };

        let mut spans = Vec::new();
        let mut begin = 0usize;

        for j in 0..n {
            let current_char = chars[j].0;
            if !Self::is_boundary_char(current_char) {
                continue;
            }
            if self.do_not_split_in_double_quotes && Self::in_span(j, &quote_spans) {
                continue;
            }

            let prev_letter = if j > 0 { chars[j - 1].0 } else { '_' };
            let next_letter = if j + 1 < n { chars[j + 1].0 } else { '_' };

            let prev_space = Self::find_backwards_space_or_char(&chars, j, ' ');
            let left_chunk = slice_str(prev_space, j);

            let prev_boundary_or_space = Self::find_backwards_space_or_char(&chars, j, '.');
            let left_chunk_until_boundary = if prev_boundary_or_space == prev_space {
                left_chunk
            } else {
                slice_str(prev_boundary_or_space, j)
            };

            let next_space = Self::find_forwards_space_or_char(&chars, j, ' ');
            let right_chunk = if j + 1 < n {
                slice_str(j + 1, next_space)
            } else {
                ""
            };
            let current_word = slice_str(prev_space, if j + 1 < n { next_space } else { j + 1 });

            // nonBoundaryCheck()
            let non_boundary = if current_char != '.' {
                next_letter == '\'' || next_letter == ')' || Self::is_boundary_char(next_letter)
            } else {
                let left_until_char_len = j - prev_boundary_or_space;
                let left_char_len = j - prev_space;
                left_until_char_len == 1
                    || (prev_letter == '.' && left_char_len == 2)
                    || (is_java_digit(prev_letter) && is_java_digit(next_letter))
                    || next_letter == '\''
                    || Self::is_boundary_char(next_letter)
                    || Self::is_segmenter_abbrev(current_word)
                    || Self::is_segmenter_abbrev(left_chunk_until_boundary)
                    || Self::potential_web_site(current_word)
            };
            if non_boundary {
                continue;
            }

            // Score perceptron features
            let mut score: f64 = 0.0;
            let mut buf = String::with_capacity(32);

            score += Self::weight_of(if prev_letter.is_uppercase() {
                "1:true"
            } else {
                "1:false"
            }) as f64;
            score += Self::weight_of(if TurkishAlphabet::is_java_whitespace(next_letter) {
                "1b:true"
            } else {
                "1b:false"
            }) as f64;

            buf.clear();
            buf.push_str("1a:");
            buf.push(prev_letter);
            score += Self::weight_of(&buf) as f64;

            buf.clear();
            buf.push_str("1b:");
            buf.push(next_letter);
            score += Self::weight_of(&buf) as f64;

            let prev_two = if j >= 2 { slice_str(j - 2, j) } else { "__" };
            buf.clear();
            buf.push_str("2p:");
            buf.push_str(prev_two);
            score += Self::weight_of(&buf) as f64;

            let next_two = if j + 3 <= n {
                slice_str(j + 1, j + 3)
            } else {
                "__"
            };
            buf.clear();
            buf.push_str("2n:");
            buf.push_str(next_two);
            score += Self::weight_of(&buf) as f64;

            if !current_word.is_empty() {
                let cw_first = current_word.chars().next().unwrap();
                score += Self::weight_of(if cw_first.is_uppercase() {
                    "7c:true"
                } else {
                    "7c:false"
                }) as f64;

                buf.clear();
                buf.push_str("9c:");
                for ch in current_word.chars() {
                    buf.push(Self::meta_char(ch));
                }
                score += Self::weight_of(&buf) as f64;
            }

            if !right_chunk.is_empty() {
                let rc_first = right_chunk.chars().next().unwrap();
                score += Self::weight_of(if rc_first.is_uppercase() {
                    "7r:true"
                } else {
                    "7r:false"
                }) as f64;

                buf.clear();
                buf.push_str("9r:");
                for ch in right_chunk.chars() {
                    buf.push(Self::meta_char(ch));
                }
                score += Self::weight_of(&buf) as f64;

                if !TurkishAlphabet::contains_vowel(right_chunk) {
                    score += Self::weight_of("rcc:true") as f64;
                }
            }

            let next_word_start = next_space + 1;
            if next_word_start < n && chars[next_word_start].0 != ' ' {
                let nw_first = chars[next_word_start].0;
                score += Self::weight_of(if nw_first.is_uppercase() {
                    "7n:true"
                } else {
                    "7n:false"
                }) as f64;
            }

            if !left_chunk.is_empty()
                && !TurkishAlphabet::contains_vowel(left_chunk)
                && !TurkishAlphabet::contains_digit(left_chunk)
            {
                score += Self::weight_of("lcc:true") as f64;
            }

            if !left_chunk.is_empty() || !right_chunk.is_empty() {
                let mut all_up = true;
                let mut all_digit = true;
                for ch in left_chunk.chars().chain(right_chunk.chars()) {
                    if !ch.is_uppercase() {
                        all_up = false;
                    }
                    if !is_java_digit(ch) {
                        all_digit = false;
                    }
                }
                if all_up {
                    score += Self::weight_of("11u:true") as f64;
                }
                if all_digit {
                    score += Self::weight_of("11d:true") as f64;
                }
            }

            if score > 0.0 {
                if j + 1 > begin {
                    spans.push(make_span(begin, j + 1));
                }
                begin = j + 1;
            }
        }

        if begin < n {
            spans.push(make_span(begin, n));
        }
        spans
    }

    /// Extracts trimmed sentence spans from `paragraph` (matching `fromParagraph` with exact UTF-8 & UTF-16 offsets).
    pub fn from_paragraph_spans(self, paragraph: &str) -> Vec<SentenceSpan> {
        let raw = self.extract_raw_spans(paragraph);
        let mut out = Vec::with_capacity(raw.len());
        for sp in raw {
            let sub = &paragraph[sp.utf8_start..sp.utf8_end];
            let mut trim_start_u8 = 0usize;
            let mut trim_start_u16 = 0usize;
            for ch in sub.chars() {
                if ch <= ' ' {
                    trim_start_u8 += ch.len_utf8();
                    trim_start_u16 += ch.len_utf16();
                } else {
                    break;
                }
            }
            if trim_start_u8 >= sub.len() {
                continue;
            }
            let mut trim_end_u8 = 0usize;
            let mut trim_end_u16 = 0usize;
            for ch in sub[trim_start_u8..].chars().rev() {
                if ch <= ' ' {
                    trim_end_u8 += ch.len_utf8();
                    trim_end_u16 += ch.len_utf16();
                } else {
                    break;
                }
            }
            let utf8_start = sp.utf8_start + trim_start_u8;
            let utf8_end = sp.utf8_end - trim_end_u8;
            if utf8_start < utf8_end {
                out.push(SentenceSpan {
                    utf8_start,
                    utf8_end,
                    utf16_start: sp.utf16_start + trim_start_u16,
                    utf16_end: sp.utf16_end - trim_end_u16,
                });
            }
        }
        out
    }

    pub fn from_paragraph(self, paragraph: &str) -> Vec<&str> {
        self.from_paragraph_spans(paragraph)
            .into_iter()
            .map(|sp| &paragraph[sp.utf8_start..sp.utf8_end])
            .collect()
    }

    /// Extracts trimmed sentence spans across an entire multi-line document (`[\n\r]+`), preserving
    /// document-global UTF-8 and UTF-16 offsets!
    pub fn from_document_spans(self, document: &str) -> Vec<SentenceSpan> {
        let mut out = Vec::new();
        let bytes = document.as_bytes();
        let mut pos = 0usize;
        let mut u16_pos = 0usize;
        while pos <= bytes.len() {
            let line_start = pos;
            let line_u16_start = u16_pos;
            while pos < bytes.len() && bytes[pos] != b'\n' && bytes[pos] != b'\r' {
                let ch = document[pos..].chars().next().unwrap();
                pos += ch.len_utf8();
                u16_pos += ch.len_utf16();
            }
            let line = &document[line_start..pos];
            for sp in self.from_paragraph_spans(line) {
                out.push(SentenceSpan {
                    utf8_start: line_start + sp.utf8_start,
                    utf8_end: line_start + sp.utf8_end,
                    utf16_start: line_u16_start + sp.utf16_start,
                    utf16_end: line_u16_start + sp.utf16_end,
                });
            }
            if pos >= bytes.len() {
                break;
            }
            while pos < bytes.len() && (bytes[pos] == b'\n' || bytes[pos] == b'\r') {
                pos += 1;
                u16_pos += 1;
            }
        }
        out
    }

    pub fn from_document(self, document: &str) -> Vec<&str> {
        self.from_document_spans(document)
            .into_iter()
            .map(|sp| &document[sp.utf8_start..sp.utf8_end])
            .collect()
    }

    fn double_quote_spans(chars: &[(char, usize, usize)]) -> Vec<(usize, usize)> {
        let mut spans = Vec::new();
        let mut start = 0usize;
        let mut started = false;
        for (j, &(c, _, _)) in chars.iter().enumerate() {
            if Self::is_double_quote_char(c) {
                if !started {
                    start = j;
                    started = true;
                } else {
                    spans.push((start, j));
                    started = false;
                }
            }
        }
        spans
    }

    fn in_span(index: usize, spans: &[(usize, usize)]) -> bool {
        for &(s, e) in spans {
            if s > index {
                return false;
            }
            if index >= s && index < e {
                return true;
            }
        }
        false
    }

    fn find_backwards_space_or_char(
        chars: &[(char, usize, usize)],
        pos: usize,
        chr: char,
    ) -> usize {
        let mut i = pos as isize - 1;
        while i >= 0 {
            let c = chars[i as usize].0;
            if c == ' ' || c == chr {
                i += 1;
                break;
            }
            i -= 1;
        }
        if i < 0 {
            0
        } else {
            i as usize
        }
    }

    fn find_forwards_space_or_char(
        chars: &[(char, usize, usize)],
        pos: usize,
        chr: char,
    ) -> usize {
        let mut j = pos + 1;
        while j < chars.len() {
            let c = chars[j].0;
            if c == ' ' || c == chr {
                break;
            }
            j += 1;
        }
        j
    }
}

#[inline]
pub(crate) fn is_java_digit(c: char) -> bool {
    if c.is_ascii() {
        c.is_ascii_digit()
    } else {
        // Unicode Decimal_Number (`Nd`) check for BMP/common scripts
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
