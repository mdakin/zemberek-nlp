use alloc::string::String;
use alloc::vec::Vec;
use crate::alphabet::TurkishAlphabet;
use crate::numbers::TurkishNumbers;
use crate::syllable::TurkishSyllableExtractor;

/// Exact port of `zemberek.morphology.analysis.tr.PronunciationGuesser` and `/tr/phonetics/turkish-letter-names.txt`.
pub struct PronunciationGuesser;

impl PronunciationGuesser {
    const MAX_DECIMAL_DIGITS: usize = 18;

    #[inline]
    pub const fn letter_pronunciation(c: char) -> Option<&'static str> {
        match c {
            'a' => Some("a"),
            'b' => Some("be"),
            'c' => Some("ce"),
            'ç' => Some("çe"),
            'd' => Some("de"),
            'e' => Some("e"),
            'f' => Some("fe"),
            'g' => Some("ge"),
            'ğ' => Some("yumuşakge"),
            'h' => Some("he"),
            'ı' => Some("ı"),
            'i' => Some("i"),
            'j' => Some("je"),
            'k' => Some("ke"),
            'l' => Some("le"),
            'm' => Some("me"),
            'n' => Some("ne"),
            'o' => Some("o"),
            'ö' => Some("ö"),
            'p' => Some("pe"),
            'r' => Some("re"),
            's' => Some("se"),
            'ş' => Some("şe"),
            't' => Some("te"),
            'u' => Some("u"),
            'ü' => Some("ü"),
            'v' => Some("ve"),
            'y' => Some("ye"),
            'z' => Some("ze"),
            'w' => Some("dabılyu"),
            'q' => Some("kü"),
            'x' => Some("iks"),
            _ => None,
        }
    }

    pub fn to_turkish_letter_pronunciations(w: &str) -> String {
        if TurkishAlphabet::contains_digit(w) {
            return Self::to_turkish_letter_pronunciation_with_digit(w);
        }
        let chars: Vec<char> = w.chars().collect();
        if chars.is_empty() {
            return String::new();
        }
        let mut out = String::with_capacity(w.len() * 2);
        let last_idx = chars.len() - 1;
        for (i, &c) in chars.iter().enumerate() {
            if c == '-' {
                continue;
            }
            if i == last_idx && c == 'k' {
                out.push_str("ka");
                continue;
            }
            if let Some(pron) = Self::letter_pronunciation(c) {
                out.push_str(pron);
            }
        }
        out
    }

    fn to_turkish_letter_pronunciation_with_digit(input: &str) -> String {
        let pieces = TurkishNumbers::separate_numbers(input);
        if pieces.is_empty() {
            return String::new();
        }
        let mut sb = String::with_capacity(input.len() * 2);
        let last_idx = pieces.len() - 1;
        for (i, piece) in pieces.iter().enumerate() {
            if TurkishAlphabet::contains_digit(piece) {
                let num_piece = if piece.len() > Self::MAX_DECIMAL_DIGITS {
                    &piece[piece.len() - Self::MAX_DECIMAL_DIGITS..]
                } else {
                    piece.as_str()
                };
                if let Some(s) = TurkishNumbers::convert_number_to_string(num_piece) {
                    for ch in s.chars() {
                        if ch != ' ' {
                            sb.push(ch);
                        }
                    }
                }
            } else if i < last_idx {
                let g = Self::guess_for_abbreviation(piece);
                for ch in g.chars() {
                    if ch != ' ' {
                        sb.push(ch);
                    }
                }
            } else if !TurkishAlphabet::contains_vowel(piece) {
                let g = Self::to_turkish_letter_pronunciations(piece);
                for ch in g.chars() {
                    if ch != ' ' {
                        sb.push(ch);
                    }
                }
            } else {
                let g = Self::replace_english_specific_chars(piece);
                for ch in g.chars() {
                    if ch != ' ' {
                        sb.push(ch);
                    }
                }
            }
        }
        sb
    }

    pub fn replace_english_specific_chars(w: &str) -> String {
        let mut out = String::with_capacity(w.len());
        for c in w.chars() {
            match c {
                'w' => out.push('v'),
                'q' => out.push('k'),
                'x' => out.push_str("ks"),
                '-' | '\'' => {}
                _ => out.push(c),
            }
        }
        out
    }

    pub fn guess_for_abbreviation(input: &str) -> String {
        let chars: Vec<char> = input.chars().collect();
        let syllables = TurkishSyllableExtractor::STRICT.syllable_boundaries(input);
        let first_two_cons = chars.len() > 2
            && !TurkishAlphabet::is_vowel(chars[0])
            && !TurkishAlphabet::is_vowel(chars[1]);
        if syllables.is_empty() || chars.len() < 3 || first_two_cons {
            Self::to_turkish_letter_pronunciations(input)
        } else {
            Self::replace_english_specific_chars(input)
        }
    }
}
