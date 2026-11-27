use alloc::string::String;
use alloc::vec::Vec;

/// Exact port of `zemberek.morphology.analysis.tr.TurkishNumeralEndingMachine`.
pub struct TurkishNumeralEndingMachine;

impl TurkishNumeralEndingMachine {
    const ONES: [&'static str; 10] = [
        "sıfır", "bir", "iki", "üç", "dört", "beş", "altı", "yedi", "sekiz", "dokuz",
    ];
    const TENS: [&'static str; 10] = [
        "", "on", "yirmi", "otuz", "kırk", "elli", "altmış", "yetmiş", "seksen", "doksan",
    ];
    /// Zero-count chain matching `ROOT -> SIFIR -> YUZ -> BIN_1..3 -> MILYON_1..3 -> MILYAR_1..3`.
    const ZERO_CHAIN: [&'static str; 11] = [
        "sıfır", "yüz", "bin", "bin", "bin", "milyon", "milyon", "milyon", "milyar", "milyar",
        "milyar",
    ];

    /// Finds the Turkish lemma of the last pronounced word of `num_str`.
    pub fn find(num_str: &str) -> &'static str {
        // `zero_depth`: None = ROOT, Some(0..10) = index in ZERO_CHAIN
        let mut zero_depth: Option<usize> = None;
        for b in num_str.bytes().rev() {
            if !b.is_ascii_digit() {
                return match zero_depth {
                    Some(_) => "sıfır",
                    None => "",
                };
            }
            let k = (b - b'0') as usize;
            match zero_depth {
                None => {
                    if k == 0 {
                        zero_depth = Some(0);
                    } else {
                        return Self::ONES[k];
                    }
                }
                Some(depth) => {
                    if k > 0 {
                        if depth == 0 {
                            return Self::TENS[k];
                        }
                        return Self::ZERO_CHAIN[depth];
                    } else {
                        let next_depth = depth + 1;
                        if next_depth >= Self::ZERO_CHAIN.len() {
                            return "";
                        }
                        zero_depth = Some(next_depth);
                    }
                }
            }
        }
        match zero_depth {
            Some(depth) => Self::ZERO_CHAIN[depth],
            None => "",
        }
    }
}

/// Exact port of `zemberek.morphology.analysis.tr.TurkishNumbers` and `TurkishTextToNumberConverter`.
pub struct TurkishNumbers;

impl TurkishNumbers {
    pub const MAX_NUMBER: i64 = 999_999_999_999_999_999;
    pub const MIN_NUMBER: i64 = -999_999_999_999_999_999;

    const SINGLE_DIGITS: [&'static str; 10] = [
        "", "bir", "iki", "üç", "dört", "beş", "altı", "yedi", "sekiz", "dokuz",
    ];
    const TENS: [&'static str; 10] = [
        "", "on", "yirmi", "otuz", "kırk", "elli", "altmış", "yetmiş", "seksen", "doksan",
    ];
    const THOUSANDS: [&'static str; 6] = ["", "bin", "milyon", "milyar", "trilyon", "katrilyon"];

    #[inline]
    pub fn single_word_number_value(word: &str) -> Option<i64> {
        match word {
            "sıfır" => Some(0),
            "bir" => Some(1),
            "iki" => Some(2),
            "üç" => Some(3),
            "dört" => Some(4),
            "beş" => Some(5),
            "altı" => Some(6),
            "yedi" => Some(7),
            "sekiz" => Some(8),
            "dokuz" => Some(9),
            "on" => Some(10),
            "yirmi" => Some(20),
            "otuz" => Some(30),
            "kırk" => Some(40),
            "elli" => Some(50),
            "altmış" | "atmış" => Some(60),
            "yetmiş" => Some(70),
            "seksen" => Some(80),
            "doksan" => Some(90),
            "yüz" => Some(100),
            "bin" => Some(1_000),
            "milyon" => Some(1_000_000),
            "milyar" => Some(1_000_000_000),
            "trilyon" => Some(1_000_000_000_000),
            "katrilyon" => Some(1_000_000_000_000_000),
            _ => None,
        }
    }

    /// Exact match for `/tr/turkish-ordinal-numbers.txt` (including `milyonun -> milyonuncu`).
    #[inline]
    pub fn get_ordinal(word: &str) -> Option<&'static str> {
        match word {
            "sıfır" => Some("sıfırıncı"),
            "bir" => Some("birinci"),
            "iki" => Some("ikinci"),
            "üç" => Some("üçüncü"),
            "dört" => Some("dördüncü"),
            "beş" => Some("beşinci"),
            "altı" => Some("altıncı"),
            "yedi" => Some("yedinci"),
            "sekiz" => Some("sekizinci"),
            "dokuz" => Some("dokuzuncu"),
            "on" => Some("onuncu"),
            "yirmi" => Some("yirminci"),
            "otuz" => Some("otuzuncu"),
            "kırk" => Some("kırkıncı"),
            "elli" => Some("ellinci"),
            "altmış" => Some("altmışıncı"),
            "yetmiş" => Some("yetmişinci"),
            "seksen" => Some("sekseninci"),
            "doksan" => Some("doksanıncı"),
            "yüz" => Some("yüzüncü"),
            "bin" => Some("bininci"),
            "milyonun" => Some("milyonuncu"),
            "milyar" => Some("milyarıncı"),
            "trilyon" => Some("trilyonuncu"),
            "katrilyon" => Some("katrilyonuncu"),
            _ => None,
        }
    }

    fn convert_three_digit(n: i32, out: &mut String) {
        let hundreds = (n / 100) as usize;
        let tens = ((n / 10) % 10) as usize;
        let single = (n % 10) as usize;
        let start_len = out.len();
        if hundreds > 1 {
            out.push_str(Self::SINGLE_DIGITS[hundreds]);
            out.push(' ');
        }
        if hundreds != 0 {
            out.push_str("yüz");
        }
        if tens != 0 {
            if out.len() > start_len {
                out.push(' ');
            }
            out.push_str(Self::TENS[tens]);
        }
        if single != 0 {
            if out.len() > start_len {
                out.push(' ');
            }
            out.push_str(Self::SINGLE_DIGITS[single]);
        }
    }

    pub fn convert_to_string(input: i64) -> Option<String> {
        if input == 0 {
            return Some(String::from("sıfır"));
        }
        if !(Self::MIN_NUMBER..=Self::MAX_NUMBER).contains(&input) {
            return None;
        }
        let mut pos = input.abs();
        let mut groups: [i32; 6] = [0; 6];
        let mut group_count = 0usize;
        while pos > 0 {
            groups[group_count] = (pos % 1000) as i32;
            pos /= 1000;
            group_count += 1;
        }

        let mut out = String::with_capacity(32);
        if input < 0 {
            out.push_str("eksi");
        }
        for idx in (0..group_count).rev() {
            let uclu = groups[idx];
            if uclu == 0 {
                continue;
            }
            if !out.is_empty() {
                out.push(' ');
            }
            if uclu == 1 && idx == 1 {
                out.push_str("bin");
            } else {
                Self::convert_three_digit(uclu, &mut out);
                if !Self::THOUSANDS[idx].is_empty() {
                    out.push(' ');
                    out.push_str(Self::THOUSANDS[idx]);
                }
            }
        }
        Some(out)
    }

    pub fn convert_number_to_string(input: &str) -> Option<String> {
        let s = input.strip_prefix('+').unwrap_or(input);
        let mut out = String::new();
        let bytes = s.as_bytes();
        let mut i = 0;
        while i < bytes.len() && bytes[i] == b'0' {
            if !out.is_empty() {
                out.push(' ');
            }
            out.push_str("sıfır");
            i += 1;
        }
        let rest = &s[i..];
        if !rest.is_empty() {
            let parsed: i64 = rest.parse().ok()?;
            let converted = Self::convert_to_string(parsed)?;
            if !out.is_empty() {
                out.push(' ');
            }
            out.push_str(&converted);
        }
        Some(out)
    }

    pub fn convert_ordinal_number_string(input: &str) -> Option<String> {
        let num_part = match input.find('.') {
            Some(dot_idx) if input.ends_with('.') => &input[..dot_idx],
            _ => input,
        };
        let num: i64 = num_part.parse().ok()?;
        let text = Self::convert_to_string(num)?;
        let (prefix, last_word) = match text.rfind(' ') {
            Some(sp) => (&text[..=sp], &text[sp + 1..]),
            None => ("", text.as_str()),
        };
        let ord = Self::get_ordinal(last_word)?;
        let mut out = String::with_capacity(prefix.len() + ord.len());
        out.push_str(prefix);
        out.push_str(ord);
        Some(out)
    }

    pub fn separate_numbers(s: &str) -> Vec<String> {
        let mut res = Vec::new();
        let mut it = s.char_indices().peekable();
        while let Some(&(start, c)) = it.peek() {
            if c == ' ' {
                it.next();
                continue;
            }
            it.next();
            let mut end = start + c.len_utf8();
            if c.is_ascii_digit() {
                while let Some(&(idx, ch)) = it.peek() {
                    if ch.is_ascii_digit() {
                        end = idx + ch.len_utf8();
                        it.next();
                    } else {
                        break;
                    }
                }
            } else {
                while let Some(&(idx, ch)) = it.peek() {
                    if ch == ' ' || ch.is_ascii_digit() {
                        break;
                    }
                    end = idx + ch.len_utf8();
                    it.next();
                }
            }
            res.push(String::from(&s[start..end]));
        }
        res
    }

    pub fn separate_connected_numbers(input: &str) -> Vec<String> {
        let mut current = String::new();
        let mut words = Vec::new();
        let mut number_found = false;
        for c in input.chars() {
            current.push(c);
            if Self::single_word_number_value(&current).is_some() {
                words.push(current.clone());
                current.clear();
                number_found = true;
            } else {
                number_found = false;
            }
        }
        if !number_found {
            words.clear();
            words.push(String::from(input));
        }
        words
    }

    pub fn convert_to_number(text: &str) -> i64 {
        let words: Vec<&str> = text.split(' ').filter(|w| !w.is_empty()).collect();
        Self::convert_words_to_number(&words)
    }

    pub fn convert_words_to_number(words: &[&str]) -> i64 {
        #[derive(Clone, Copy, PartialEq, Eq)]
        enum St {
            Start,
            Ones1,
            Ones2,
            Ones3,
            Ones4,
            Ones5,
            Ones6,
            Tens1,
            Tens2,
            Tens3,
            Hundreds1,
            Hundreds2,
            Thousand,
            End,
            Error,
        }

        if words.is_empty() {
            return -1;
        }

        let mut total: i64 = 0;
        let mut value_to_add: i64 = 0;
        let mut previous_mil: i64 = 0;
        let mut state = St::Start;

        for &w in words {
            let Some(val) = Self::single_word_number_value(w) else {
                return -1;
            };
            state = match val {
                0 => {
                    if state == St::Start && total == 0 {
                        St::End
                    } else {
                        St::Error
                    }
                }
                1 => match state {
                    St::Start => {
                        value_to_add += 1;
                        St::Ones1
                    }
                    St::Tens1 => {
                        value_to_add += 1;
                        St::Ones3
                    }
                    St::Tens2 | St::Hundreds1 => {
                        value_to_add += 1;
                        St::Ones4
                    }
                    St::Tens3 | St::Hundreds2 | St::Thousand => {
                        value_to_add += 1;
                        St::Ones5
                    }
                    _ => St::Error,
                },
                2..=9 => match state {
                    St::Start => {
                        value_to_add += val;
                        St::Ones2
                    }
                    St::Tens1 => {
                        value_to_add += val;
                        St::Ones3
                    }
                    St::Tens2 | St::Hundreds1 => {
                        value_to_add += val;
                        St::Ones4
                    }
                    St::Tens3 | St::Hundreds2 => {
                        value_to_add += val;
                        St::Ones5
                    }
                    St::Thousand => {
                        value_to_add += val;
                        St::Ones6
                    }
                    _ => St::Error,
                },
                10 | 20 | 30 | 40 | 50 | 60 | 70 | 80 | 90 => match state {
                    St::Start => {
                        value_to_add += val;
                        St::Tens1
                    }
                    St::Hundreds1 => {
                        value_to_add += val;
                        St::Tens2
                    }
                    St::Hundreds2 | St::Thousand => {
                        value_to_add += val;
                        St::Tens3
                    }
                    _ => St::Error,
                },
                100 => match state {
                    St::Start | St::Ones2 => {
                        value_to_add = if value_to_add == 0 {
                            100
                        } else {
                            value_to_add * 100
                        };
                        St::Hundreds1
                    }
                    St::Thousand | St::Ones6 => {
                        value_to_add = if value_to_add == 0 {
                            100
                        } else {
                            value_to_add * 100
                        };
                        St::Hundreds2
                    }
                    _ => St::Error,
                },
                1_000 => match state {
                    St::Start
                    | St::Ones2
                    | St::Ones3
                    | St::Ones4
                    | St::Tens1
                    | St::Tens2
                    | St::Hundreds1 => {
                        total += if value_to_add == 0 {
                            val
                        } else {
                            value_to_add * val
                        };
                        value_to_add = 0;
                        St::Thousand
                    }
                    _ => St::Error,
                },
                1_000_000 | 1_000_000_000 | 1_000_000_000_000 | 1_000_000_000_000_000 => {
                    match state {
                        St::Ones1
                        | St::Ones2
                        | St::Ones3
                        | St::Ones4
                        | St::Tens1
                        | St::Tens2
                        | St::Hundreds1 => {
                            if previous_mil == 0 || previous_mil > val {
                                previous_mil = val;
                                total += if value_to_add == 0 {
                                    val
                                } else {
                                    value_to_add * val
                                };
                                value_to_add = 0;
                                St::Start
                            } else {
                                St::Error
                            }
                        }
                        _ => St::Error,
                    }
                }
                _ => St::Error,
            };
            if state == St::Error {
                return -1;
            }
        }
        value_to_add + total
    }

    /// Canonical Roman numeral parser (`1..=3999`, case-insensitive), matching `TurkishNumbers.romanToDecimal`
    /// without regex (`^(M{0,3})(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})$`).
    pub fn roman_to_decimal(s: &str) -> i32 {
        if s.is_empty() || !s.is_ascii() {
            return -1;
        }
        let b = s.as_bytes();
        let n = b.len();
        let mut i = 0usize;
        let up = |idx: usize| b[idx].to_ascii_uppercase();

        let mut total = 0i32;

        // M{0,3}
        let mut m_cnt = 0;
        while i < n && up(i) == b'M' && m_cnt < 3 {
            total += 1000;
            i += 1;
            m_cnt += 1;
        }

        // (CM|CD|D?C{0,3})
        if i + 1 < n && up(i) == b'C' && up(i + 1) == b'M' {
            total += 900;
            i += 2;
        } else if i + 1 < n && up(i) == b'C' && up(i + 1) == b'D' {
            total += 400;
            i += 2;
        } else {
            if i < n && up(i) == b'D' {
                total += 500;
                i += 1;
            }
            let mut c_cnt = 0;
            while i < n && up(i) == b'C' && c_cnt < 3 {
                total += 100;
                i += 1;
                c_cnt += 1;
            }
        }

        // (XC|XL|L?X{0,3})
        if i + 1 < n && up(i) == b'X' && up(i + 1) == b'C' {
            total += 90;
            i += 2;
        } else if i + 1 < n && up(i) == b'X' && up(i + 1) == b'L' {
            total += 40;
            i += 2;
        } else {
            if i < n && up(i) == b'L' {
                total += 50;
                i += 1;
            }
            let mut x_cnt = 0;
            while i < n && up(i) == b'X' && x_cnt < 3 {
                total += 10;
                i += 1;
                x_cnt += 1;
            }
        }

        // (IX|IV|V?I{0,3})
        if i + 1 < n && up(i) == b'I' && up(i + 1) == b'X' {
            total += 9;
            i += 2;
        } else if i + 1 < n && up(i) == b'I' && up(i + 1) == b'V' {
            total += 4;
            i += 2;
        } else {
            if i < n && up(i) == b'V' {
                total += 5;
                i += 1;
            }
            let mut i_cnt = 0;
            while i < n && up(i) == b'I' && i_cnt < 3 {
                total += 1;
                i += 1;
                i_cnt += 1;
            }
        }

        if i == n && total > 0 {
            total
        } else {
            -1
        }
    }

    #[inline]
    pub fn has_number(s: &str) -> bool {
        s.bytes().any(|b| b.is_ascii_digit())
    }

    #[inline]
    pub fn has_only_number(s: &str) -> bool {
        !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit())
    }
}
