use alloc::string::String;
use crate::lexer::{Token, TokenType};
use crate::sentence::is_java_digit;

/// Exact port of `zemberek.tokenization.TurkishDetokenizer`.
pub struct TurkishDetokenizer;

impl TurkishDetokenizer {
    pub fn detokenize<S: AsRef<str>>(tokens: &[S]) -> String {
        if tokens.is_empty() {
            return String::new();
        }

        let mut sb = String::with_capacity(tokens.len() * 6);
        let mut prev: Option<&str> = None;
        let mut in_double_quote = false;
        let mut in_single_quote = false;
        let mut prev_attach_right = false;
        let mut prev_closed_quote_or_bracket = false;
        let mut prev_was_suffix_apostrophe = false;

        for i in 0..tokens.len() {
            let curr = tokens[i].as_ref();
            if curr.is_empty() {
                continue;
            }

            if Self::is_line_break(curr) {
                sb.push_str(curr);
                prev = Some(curr);
                prev_attach_right = true;
                prev_closed_quote_or_bracket = false;
                prev_was_suffix_apostrophe = false;
                continue;
            }

            let mut attach_to_prev =
                prev.is_none() || prev_attach_right || prev_was_suffix_apostrophe;
            let mut next_attach_right = false;
            let mut next_closed_quote_or_bracket = false;
            let mut next_was_suffix_apostrophe = false;

            if curr == "\"" {
                if in_double_quote {
                    attach_to_prev = true;
                    in_double_quote = false;
                    next_closed_quote_or_bracket = true;
                } else {
                    in_double_quote = true;
                    next_attach_right = true;
                }
            } else if Self::is_apostrophe_char(curr)
                && prev_closed_quote_or_bracket
                && Self::has_following_letter_token(tokens, i + 1)
            {
                attach_to_prev = true;
                next_was_suffix_apostrophe = true;
            } else if curr == "'" {
                if in_single_quote {
                    attach_to_prev = true;
                    in_single_quote = false;
                    next_closed_quote_or_bracket = true;
                } else {
                    in_single_quote = true;
                    next_attach_right = true;
                }
            } else if curr == "‘" {
                next_attach_right = true;
            } else if curr == "’" {
                attach_to_prev = true;
                next_closed_quote_or_bracket = true;
            } else if Self::is_opening_bracket_or_quote(curr) {
                next_attach_right = true;
            } else if Self::is_closing_bracket_or_quote(curr) {
                attach_to_prev = true;
                next_closed_quote_or_bracket = true;
            } else if Self::is_left_attaching_punctuation(curr) {
                attach_to_prev = true;
            } else if curr == "/" {
                attach_to_prev = true;
                next_attach_right = true;
            } else if Self::is_currency_symbol(curr) {
                let next = Self::peek_next_non_empty(tokens, i + 1);
                if prev.is_some_and(Self::starts_with_digit_or_percent) {
                    attach_to_prev = true;
                } else if next.is_some_and(Self::starts_with_digit) {
                    next_attach_right = true;
                }
            } else if curr == "%" {
                let next = Self::peek_next_non_empty(tokens, i + 1);
                if next.is_some_and(Self::starts_with_digit) {
                    next_attach_right = true;
                }
            } else if prev == Some("°") && Self::is_temperature_scale(curr) {
                attach_to_prev = true;
            }

            if !attach_to_prev {
                sb.push(' ');
            }
            sb.push_str(curr);

            prev = Some(curr);
            prev_attach_right = next_attach_right;
            prev_closed_quote_or_bracket = next_closed_quote_or_bracket;
            prev_was_suffix_apostrophe = next_was_suffix_apostrophe;
        }

        sb
    }

    pub fn detokenize_tokens(tokens: &[Token<'_>]) -> String {
        if tokens.is_empty() {
            return String::new();
        }
        let has_explicit_spaces = tokens.iter().any(|t| t.token_type == TokenType::SpaceTab);
        if has_explicit_spaces {
            let total_len: usize = tokens.iter().map(|t| t.content.len()).sum();
            let mut sb = String::with_capacity(total_len);
            for t in tokens {
                sb.push_str(t.content);
            }
            return sb;
        }
        let refs: alloc::vec::Vec<&str> = tokens.iter().map(|t| t.content).collect();
        Self::detokenize(&refs)
    }

    #[inline]
    fn is_line_break(s: &str) -> bool {
        matches!(s, "\n" | "\r" | "\r\n")
    }

    #[inline]
    fn is_apostrophe_char(s: &str) -> bool {
        matches!(s, "'" | "’")
    }

    #[inline]
    fn is_opening_bracket_or_quote(s: &str) -> bool {
        matches!(s, "(" | "[" | "{" | "“" | "«")
    }

    #[inline]
    fn is_closing_bracket_or_quote(s: &str) -> bool {
        matches!(s, ")" | "]" | "}" | "”" | "»")
    }

    #[inline]
    fn is_left_attaching_punctuation(s: &str) -> bool {
        matches!(
            s,
            "," | "." | "!" | "?" | ":" | ";" | "..." | "…" | "°" | "®" | "™" | "℠"
        )
    }

    #[inline]
    fn is_currency_symbol(s: &str) -> bool {
        matches!(s, "₺" | "$" | "€" | "£" | "¥")
    }

    #[inline]
    fn is_temperature_scale(s: &str) -> bool {
        matches!(s, "C" | "F" | "C." | "F.")
            || s.starts_with("C'")
            || s.starts_with("C’")
            || s.starts_with("F'")
            || s.starts_with("F’")
    }

    #[inline]
    fn starts_with_digit(s: &str) -> bool {
        s.chars().next().is_some_and(is_java_digit)
    }

    #[inline]
    fn starts_with_digit_or_percent(s: &str) -> bool {
        let mut it = s.chars();
        match it.next() {
            Some(c) if is_java_digit(c) => true,
            Some('%') => it.next().is_some_and(is_java_digit),
            _ => false,
        }
    }

    fn peek_next_non_empty<S: AsRef<str>>(tokens: &[S], start_index: usize) -> Option<&str> {
        for item in tokens.iter().skip(start_index) {
            let t = item.as_ref();
            if !t.is_empty() {
                return Some(t);
            }
        }
        None
    }

    fn has_following_letter_token<S: AsRef<str>>(tokens: &[S], start_index: usize) -> bool {
        let Some(next) = Self::peek_next_non_empty(tokens, start_index) else {
            return false;
        };
        if Self::is_line_break(next) {
            return false;
        }
        next.chars().next().is_some_and(char::is_alphabetic)
    }
}
