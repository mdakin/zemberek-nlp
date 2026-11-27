use alloc::string::String;
use alloc::vec::Vec;
use crate::alphabet::TurkishAlphabet;

/// Exact port of `zemberek.core.turkish.hyphenation.TurkishSyllableExtractor`.
#[derive(Clone, Copy, Debug)]
pub struct TurkishSyllableExtractor {
    pub strict: bool,
}

impl TurkishSyllableExtractor {
    pub const STRICT: Self = Self { strict: true };
    pub const DEFAULT: Self = Self { strict: false };

    /// Returns character-offset boundaries of syllables (empty if unparseable).
    pub fn syllable_boundaries(self, s: &str) -> Vec<usize> {
        let chars: Vec<char> = s.chars().collect();
        let size = chars.len();
        if size == 0 {
            return Vec::new();
        }
        let mut rev_boundaries = Vec::with_capacity(size);
        let mut last_index = size;
        while last_index > 0 {
            let Some(letter_count) = self.letter_count_for_last_syllable(&chars, last_index)
            else {
                return Vec::new();
            };
            last_index -= letter_count;
            rev_boundaries.push(last_index);
        }
        rev_boundaries.reverse();
        rev_boundaries
    }

    pub fn get_syllables(self, s: &str) -> Vec<String> {
        let chars: Vec<(usize, char)> = s.char_indices().collect();
        let size = chars.len();
        if size == 0 {
            return Vec::new();
        }
        let only_chars: Vec<char> = chars.iter().map(|&(_, c)| c).collect();
        let mut rev_boundaries = Vec::with_capacity(size);
        let mut last_index = size;
        while last_index > 0 {
            let Some(letter_count) = self.letter_count_for_last_syllable(&only_chars, last_index)
            else {
                return Vec::new();
            };
            last_index -= letter_count;
            rev_boundaries.push(last_index);
        }
        rev_boundaries.reverse();

        let mut out = Vec::with_capacity(rev_boundaries.len());
        for i in 0..rev_boundaries.len() {
            let start_byte = chars[rev_boundaries[i]].0;
            let end_byte = if i + 1 < rev_boundaries.len() {
                chars[rev_boundaries[i + 1]].0
            } else {
                s.len()
            };
            out.push(String::from(&s[start_byte..end_byte]));
        }
        out
    }

    #[inline]
    pub fn is_parseable(self, s: &str) -> bool {
        !s.is_empty() && !self.syllable_boundaries(s).is_empty()
    }

    fn letter_count_for_last_syllable(self, chr: &[char], end_index: usize) -> Option<usize> {
        if end_index == 0 {
            return None;
        }
        let is_v = |i: usize| TurkishAlphabet::is_vowel(chr[i]);
        if is_v(end_index - 1) {
            if end_index == 1 {
                return Some(1);
            }
            if is_v(end_index - 2) {
                return Some(1);
            }
            if end_index == 2 {
                return Some(2);
            }
            if !is_v(end_index - 3) && end_index == 3 {
                return Some(3);
            }
            Some(2)
        } else {
            if end_index == 1 {
                return None;
            }
            if is_v(end_index - 2) {
                if end_index == 2 || is_v(end_index - 3) {
                    return Some(2);
                }
                if end_index == 3 || is_v(end_index - 4) {
                    return Some(3);
                }
                if end_index == 4 {
                    return None;
                }
                Some(3)
            } else {
                if self.strict {
                    return None;
                }
                if end_index == 2 || !is_v(end_index - 3) {
                    return None;
                }
                if end_index > 3 && !is_v(end_index - 4) {
                    return Some(4);
                }
                Some(3)
            }
        }
    }
}
