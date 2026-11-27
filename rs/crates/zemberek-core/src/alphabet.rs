use alloc::string::String;

const FL_DEFINED: u8 = 1 << 0;
const FL_VOWEL: u8 = 1 << 1;
const FL_FRONTAL: u8 = 1 << 2;
const FL_ROUNDED: u8 = 1 << 3;
const FL_VOICELESS: u8 = 1 << 4;
const FL_CONTINUANT: u8 = 1 << 5;

/// Turkish letter phonetic properties, matching `zemberek.core.turkish.TurkicLetter`.
#[derive(Clone, Copy, Debug)]
pub struct TurkicLetter {
    pub char_value: char,
    flags: u8,
}

impl PartialEq for TurkicLetter {
    #[inline]
    fn eq(&self, other: &Self) -> bool {
        self.char_value == other.char_value
    }
}
impl Eq for TurkicLetter {}

impl TurkicLetter {
    pub const UNDEFINED: Self = Self {
        char_value: '\0',
        flags: 0,
    };

    const fn new(char_value: char, flags: u8) -> Self {
        Self {
            char_value,
            flags: flags | FL_DEFINED,
        }
    }

    #[inline]
    pub const fn is_defined(self) -> bool {
        (self.flags & FL_DEFINED) != 0
    }

    #[inline]
    pub const fn is_vowel(self) -> bool {
        (self.flags & FL_VOWEL) != 0
    }

    #[inline]
    pub const fn is_consonant(self) -> bool {
        !self.is_vowel()
    }

    #[inline]
    pub const fn is_frontal(self) -> bool {
        (self.flags & FL_FRONTAL) != 0
    }

    #[inline]
    pub const fn is_rounded(self) -> bool {
        (self.flags & FL_ROUNDED) != 0
    }

    #[inline]
    pub const fn is_voiceless(self) -> bool {
        (self.flags & FL_VOICELESS) != 0
    }

    #[inline]
    pub const fn is_continuant(self) -> bool {
        (self.flags & FL_CONTINUANT) != 0
    }

    #[inline]
    pub const fn is_stop_consonant(self) -> bool {
        self.is_voiceless() && !self.is_continuant()
    }
}

/// Const O(1) lookup table over codepoints `0..0x180` covering all ASCII, Latin-1, and Turkish
/// Extended-A (`ğ, Ğ, ı, İ, ş, Ş`) letters.
const LETTER_TABLE_LEN: usize = 0x180;

const fn build_letter_table() -> [TurkicLetter; LETTER_TABLE_LEN] {
    let mut table = [TurkicLetter::UNDEFINED; LETTER_TABLE_LEN];
    let defs: &[(char, char, u8)] = &[
        // Vowels
        ('a', 'A', FL_VOWEL),
        ('e', 'E', FL_VOWEL | FL_FRONTAL),
        ('ı', 'I', FL_VOWEL),
        ('i', 'İ', FL_VOWEL | FL_FRONTAL),
        ('o', 'O', FL_VOWEL | FL_ROUNDED),
        ('ö', 'Ö', FL_VOWEL | FL_FRONTAL | FL_ROUNDED),
        ('u', 'U', FL_VOWEL | FL_ROUNDED),
        ('ü', 'Ü', FL_VOWEL | FL_FRONTAL | FL_ROUNDED),
        // Circumflexed vowels
        ('â', 'Â', FL_VOWEL),
        ('î', 'Î', FL_VOWEL | FL_FRONTAL),
        ('û', 'Û', FL_VOWEL | FL_FRONTAL | FL_ROUNDED),
        // Consonants
        ('b', 'B', 0),
        ('c', 'C', 0),
        ('ç', 'Ç', FL_VOICELESS),
        ('d', 'D', 0),
        ('f', 'F', FL_CONTINUANT | FL_VOICELESS),
        ('g', 'G', 0),
        ('ğ', 'Ğ', FL_CONTINUANT),
        ('h', 'H', FL_CONTINUANT | FL_VOICELESS),
        ('j', 'J', FL_CONTINUANT),
        ('k', 'K', FL_VOICELESS),
        ('l', 'L', FL_CONTINUANT),
        ('m', 'M', FL_CONTINUANT),
        ('n', 'N', FL_CONTINUANT),
        ('p', 'P', FL_VOICELESS),
        ('r', 'R', FL_CONTINUANT),
        ('s', 'S', FL_CONTINUANT | FL_VOICELESS),
        ('ş', 'Ş', FL_CONTINUANT | FL_VOICELESS),
        ('t', 'T', FL_VOICELESS),
        ('v', 'V', FL_CONTINUANT),
        ('y', 'Y', FL_CONTINUANT),
        ('z', 'Z', FL_CONTINUANT),
        ('q', 'Q', 0),
        ('w', 'W', 0),
        ('x', 'X', 0),
    ];
    let mut i = 0;
    while i < defs.len() {
        let (lo, hi, flags) = defs[i];
        table[lo as usize] = TurkicLetter::new(lo, flags);
        table[hi as usize] = TurkicLetter::new(hi, flags);
        i += 1;
    }
    table
}

static LETTER_TABLE: [TurkicLetter; LETTER_TABLE_LEN] = build_letter_table();

/// Stateless Turkish alphabet, casing, voicing, harmony, and diacritic utilities
/// matching `zemberek.core.turkish.TurkishAlphabet` and `zemberek.core.turkish.Turkish`.
pub struct TurkishAlphabet;

impl TurkishAlphabet {
    pub const LOWERCASE_LETTERS: &'static str = "abcçdefgğhıijklmnoöprsştuüvyzxwqâîû";
    pub const UPPERCASE_LETTERS: &'static str = "ABCÇDEFGĞHIİJKLMNOÖPRSŞTUÜVYZXWQÂÎÛ";

    #[inline]
    pub fn get_letter(c: char) -> TurkicLetter {
        let idx = c as usize;
        if idx < LETTER_TABLE_LEN {
            LETTER_TABLE[idx]
        } else {
            TurkicLetter::UNDEFINED
        }
    }

    #[inline]
    pub fn is_dictionary_letter(c: char) -> bool {
        Self::get_letter(c).is_defined()
    }

    #[inline]
    pub fn is_vowel(c: char) -> bool {
        Self::get_letter(c).is_vowel()
    }

    #[inline]
    pub fn is_stop_consonant(c: char) -> bool {
        matches!(c, 'ç' | 'k' | 'p' | 't' | 'Ç' | 'K' | 'P' | 'T')
    }

    #[inline]
    pub fn is_voiceless_consonant(c: char) -> bool {
        matches!(
            c,
            'ç' | 'f'
                | 'h'
                | 'k'
                | 'p'
                | 's'
                | 'ş'
                | 't'
                | 'Ç'
                | 'F'
                | 'H'
                | 'K'
                | 'P'
                | 'S'
                | 'Ş'
                | 'T'
        )
    }

    #[inline]
    pub fn is_turkish_specific(c: char) -> bool {
        matches!(
            c,
            'ç' | 'Ç'
                | 'ğ'
                | 'Ğ'
                | 'ı'
                | 'İ'
                | 'ö'
                | 'Ö'
                | 'ş'
                | 'Ş'
                | 'ü'
                | 'Ü'
                | 'â'
                | 'î'
                | 'û'
                | 'Â'
                | 'Î'
                | 'Û'
        )
    }

    #[inline]
    pub fn is_circumflex(c: char) -> bool {
        matches!(c, 'â' | 'î' | 'û' | 'Â' | 'Î' | 'Û')
    }

    #[inline]
    pub fn is_apostrophe(c: char) -> bool {
        matches!(c, '\u{2032}' | '´' | '`' | '’' | '‘' | '\'')
    }

    #[inline]
    pub fn is_java_whitespace(c: char) -> bool {
        match c {
            '\t' | '\n' | '\u{000B}' | '\u{000C}' | '\r' | '\u{001C}'..='\u{001F}' => true,
            '\u{00A0}' | '\u{2007}' | '\u{202F}' | '\u{FEFF}' => false,
            _ => c.is_whitespace(),
        }
    }

    /// Exact match for `Turkish.toLower(char)`.
    #[inline]
    pub fn to_lower_char(c: char) -> char {
        match c {
            'I' => 'ı',
            'İ' => 'i',
            'A'..='Z' => ((c as u8) + 32) as char,
            'Ç' => 'ç',
            'Ğ' => 'ğ',
            'Ö' => 'ö',
            'Ş' => 'ş',
            'Ü' => 'ü',
            'Â' => 'â',
            'Î' => 'î',
            'Û' => 'û',
            _ => {
                let mut it = c.to_lowercase();
                let first = it.next().unwrap_or(c);
                if it.next().is_none() {
                    first
                } else {
                    c
                }
            }
        }
    }

    /// Exact match for `Turkish.toUpper(char)`.
    #[inline]
    pub fn to_upper_char(c: char) -> char {
        match c {
            'i' => 'İ',
            'ı' => 'I',
            'a'..='z' => ((c as u8) - 32) as char,
            'ç' => 'Ç',
            'ğ' => 'Ğ',
            'ö' => 'Ö',
            'ş' => 'Ş',
            'ü' => 'Ü',
            'â' => 'Â',
            'î' => 'Î',
            'û' => 'Û',
            _ => {
                let mut it = c.to_uppercase();
                let first = it.next().unwrap_or(c);
                if it.next().is_none() {
                    first
                } else {
                    c
                }
            }
        }
    }

    /// Turkish-locale string lowercasing (`String.toLowerCase(Locale.of("tr"))`), including `I\u{0307} -> i`.
    pub fn to_lower_tr(s: &str) -> String {
        let mut out = String::with_capacity(s.len());
        let mut chars = s.chars().peekable();
        while let Some(c) = chars.next() {
            if c == 'I' {
                if chars.peek() == Some(&'\u{0307}') {
                    chars.next();
                    out.push('i');
                } else {
                    out.push('ı');
                }
            } else if c == 'İ' {
                out.push('i');
            } else if c.is_ascii_uppercase() {
                out.push(((c as u8) + 32) as char);
            } else if c.is_ascii() {
                out.push(c);
            } else {
                for lc in c.to_lowercase() {
                    out.push(lc);
                }
            }
        }
        out
    }

    /// Turkish-locale string uppercasing (`String.toUpperCase(Locale.of("tr"))`).
    pub fn to_upper_tr(s: &str) -> String {
        let mut out = String::with_capacity(s.len() + 4);
        for c in s.chars() {
            match c {
                'i' => out.push('İ'),
                'ı' => out.push('I'),
                'a'..='z' => out.push(((c as u8) - 32) as char),
                _ if c.is_ascii() => out.push(c),
                _ => {
                    for uc in c.to_uppercase() {
                        out.push(uc);
                    }
                }
            }
        }
        out
    }

    /// Matches `Turkish.capitalize(word)`: uppercases first character with Turkish locale and lowercases the rest.
    pub fn capitalize_tr(word: &str) -> String {
        let mut chars = word.chars();
        let Some(first) = chars.next() else {
            return String::new();
        };
        let rest = chars.as_str();
        let mut out = String::with_capacity(word.len() + 2);
        out.push(Self::to_upper_char(first));
        out.push_str(&Self::to_lower_tr(rest));
        out
    }

    /// Matches `NormV2Types.trCapitalize(s)`: uppercases first character with Turkish locale and keeps rest unchanged.
    pub fn capitalize_head_tr(word: &str) -> String {
        let mut chars = word.chars();
        let Some(first) = chars.next() else {
            return String::new();
        };
        let mut out = String::with_capacity(word.len() + 2);
        out.push(Self::to_upper_char(first));
        out.push_str(chars.as_str());
        out
    }

    #[inline]
    pub fn voice(c: char) -> char {
        match c {
            'ç' => 'c',
            'g' | 'k' => 'ğ',
            'p' => 'b',
            't' => 'd',
            'Ç' => 'C',
            'G' | 'K' => 'Ğ',
            'P' => 'B',
            'T' => 'D',
            _ => c,
        }
    }

    #[inline]
    pub fn devoice(c: char) -> char {
        match c {
            'b' => 'p',
            'c' => 'ç',
            'd' => 't',
            'g' | 'ğ' => 'k',
            'B' => 'P',
            'C' => 'Ç',
            'D' => 'T',
            'G' | 'Ğ' => 'K',
            _ => c,
        }
    }

    /// Matches `TurkishAlphabet.normalizeCircumflex(char)` (note: `'Î' -> 'İ'` per Turkish uppercase of `'i'`).
    #[inline]
    pub fn normalize_circumflex_char(c: char) -> char {
        match c {
            'â' => 'a',
            'î' => 'i',
            'û' => 'u',
            'Â' => 'A',
            'Î' => 'İ',
            'Û' => 'U',
            _ => c,
        }
    }

    pub fn normalize_circumflex(s: &str) -> String {
        if !Self::contains_circumflex(s) {
            return String::from(s);
        }
        s.chars().map(Self::normalize_circumflex_char).collect()
    }

    #[inline]
    pub fn get_ascii_equal(c: char) -> char {
        match c {
            'ç' => 'c',
            'Ç' => 'C',
            'ğ' => 'g',
            'Ğ' => 'G',
            'ı' | 'î' => 'i',
            'İ' | 'Î' => 'I',
            'ö' => 'o',
            'Ö' => 'O',
            'ş' => 's',
            'Ş' => 'S',
            'ü' | 'û' => 'u',
            'Ü' | 'Û' => 'U',
            'â' => 'a',
            'Â' => 'A',
            _ => c,
        }
    }

    pub fn to_ascii(s: &str) -> String {
        let mut out = String::with_capacity(s.len());
        for c in s.chars() {
            out.push(Self::get_ascii_equal(c));
        }
        out
    }

    #[inline]
    pub fn is_ascii_equal(c1: char, c2: char) -> bool {
        if c1 == c2 {
            return true;
        }
        let mapped = match c1 {
            'c' => 'ç',
            'C' => 'Ç',
            'g' => 'ğ',
            'G' => 'Ğ',
            'i' => 'ı',
            'I' => 'İ',
            'o' => 'ö',
            'O' => 'Ö',
            's' => 'ş',
            'S' => 'Ş',
            'u' => 'ü',
            'U' => 'Ü',
            'ç' => 'c',
            'Ç' => 'C',
            'ğ' => 'g',
            'Ğ' => 'G',
            'ı' => 'i',
            'İ' => 'I',
            'ö' => 'o',
            'Ö' => 'O',
            'ş' => 's',
            'Ş' => 'S',
            'ü' => 'u',
            'Ü' => 'U',
            _ => return false,
        };
        mapped == c2
    }

    #[inline]
    pub fn contains_ascii_related(s: &str) -> bool {
        s.chars().any(|c| {
            matches!(
                c,
                'c' | 'C'
                    | 'g'
                    | 'G'
                    | 'i'
                    | 'I'
                    | 'o'
                    | 'O'
                    | 's'
                    | 'S'
                    | 'u'
                    | 'U'
                    | 'ç'
                    | 'Ç'
                    | 'ğ'
                    | 'Ğ'
                    | 'ı'
                    | 'İ'
                    | 'ö'
                    | 'Ö'
                    | 'ş'
                    | 'Ş'
                    | 'ü'
                    | 'Ü'
            )
        })
    }

    pub fn foreign_diacritics_to_turkish(s: &str) -> String {
        let mut out = String::with_capacity(s.len());
        for c in s.chars() {
            let mapped = match c {
                'À'..='Å' => 'A',
                'È'..='Ë' => 'E',
                'Ì'..='Ï' => 'I',
                'Ñ' => 'N',
                'Ò'..='Õ' => 'O',
                'Ù'..='Û' => 'U',
                'à'..='å' => 'a',
                'è'..='ë' => 'e',
                'ì'..='ï' => 'i',
                'ñ' => 'n',
                'ò'..='õ' => 'o',
                'ù'..='û' => 'u',
                _ => c,
            };
            out.push(mapped);
        }
        out
    }

    pub fn normalize_apostrophe(s: &str) -> String {
        if !Self::contains_apostrophe(s) {
            return String::from(s);
        }
        let mut out = String::with_capacity(s.len());
        for c in s.chars() {
            out.push(if Self::is_apostrophe(c) { '\'' } else { c });
        }
        out
    }

    pub fn strip_apostrophes(s: &str) -> String {
        if !Self::contains_apostrophe(s) {
            return String::from(s);
        }
        let mut out = String::with_capacity(s.len());
        for c in s.chars() {
            if !Self::is_apostrophe(c) {
                out.push(c);
            }
        }
        out
    }

    pub fn normalize(input: &str) -> String {
        let lowered = Self::to_lower_tr(input);
        let mut out = String::with_capacity(lowered.len());
        for c in lowered.chars() {
            let c = if matches!(
                c,
                '`' | '\u{0091}' | '\u{0092}' | '\u{00B4}' | '\u{2018}' | '\u{2019}' | '\u{2032}'
            ) {
                '\''
            } else {
                c
            };
            if Self::is_dictionary_letter(c) || c == '.' || c == '-' {
                out.push(c);
            } else {
                out.push('?');
            }
        }
        out
    }

    #[inline]
    pub fn contains_circumflex(s: &str) -> bool {
        s.chars().any(Self::is_circumflex)
    }

    #[inline]
    pub fn contains_apostrophe(s: &str) -> bool {
        s.chars().any(Self::is_apostrophe)
    }

    #[inline]
    pub fn contains_vowel(s: &str) -> bool {
        s.chars().any(Self::is_vowel)
    }

    #[inline]
    pub fn vowel_count(s: &str) -> usize {
        s.chars().filter(|&c| Self::is_vowel(c)).count()
    }

    #[inline]
    pub fn contains_digit(s: &str) -> bool {
        s.bytes().any(|b| b.is_ascii_digit())
    }

    #[inline]
    pub fn all_capital(s: &str) -> bool {
        s.chars().all(char::is_uppercase)
    }

    #[inline]
    pub fn first_letter(s: &str) -> TurkicLetter {
        s.chars()
            .next()
            .map(Self::get_letter)
            .unwrap_or(TurkicLetter::UNDEFINED)
    }

    #[inline]
    pub fn last_letter(s: &str) -> TurkicLetter {
        s.chars()
            .next_back()
            .map(Self::get_letter)
            .unwrap_or(TurkicLetter::UNDEFINED)
    }

    #[inline]
    pub fn first_vowel(s: &str) -> TurkicLetter {
        for c in s.chars() {
            let l = Self::get_letter(c);
            if l.is_vowel() {
                return l;
            }
        }
        TurkicLetter::UNDEFINED
    }

    #[inline]
    pub fn last_vowel(s: &str) -> TurkicLetter {
        for c in s.chars().rev() {
            let l = Self::get_letter(c);
            if l.is_vowel() {
                return l;
            }
        }
        TurkicLetter::UNDEFINED
    }

    #[inline]
    pub fn check_vowel_harmony_a_letters(source: TurkicLetter, target: TurkicLetter) -> bool {
        if !source.is_defined() || !target.is_defined() || !source.is_vowel() || !target.is_vowel()
        {
            return false;
        }
        source.is_frontal() == target.is_frontal()
    }

    #[inline]
    pub fn check_vowel_harmony_i_letters(source: TurkicLetter, target: TurkicLetter) -> bool {
        if !source.is_defined() || !target.is_defined() || !source.is_vowel() || !target.is_vowel()
        {
            return false;
        }
        source.is_frontal() == target.is_frontal() && source.is_rounded() == target.is_rounded()
    }

    /// Matches `TurkishAlphabet.checkVowelHarmonyA(CharSequence, CharSequence)`.
    #[inline]
    pub fn check_vowel_harmony_a(source: &str, target: &str) -> bool {
        Self::check_vowel_harmony_a_letters(Self::last_vowel(source), Self::last_vowel(target))
    }

    /// Matches `TurkishAlphabet.checkVowelHarmonyI(CharSequence, CharSequence)`.
    #[inline]
    pub fn check_vowel_harmony_i(source: &str, target: &str) -> bool {
        Self::check_vowel_harmony_i_letters(Self::last_vowel(source), Self::last_vowel(target))
    }

    pub fn equals_ignore_diacritics(s1: &str, s2: &str) -> bool {
        let mut i1 = s1.chars();
        let mut i2 = s2.chars();
        loop {
            match (i1.next(), i2.next()) {
                (Some(c1), Some(c2)) => {
                    if !Self::is_ascii_equal(c1, c2) {
                        return false;
                    }
                }
                (None, None) => return true,
                _ => return false,
            }
        }
    }

    pub fn starts_with_ignore_diacritics(s1: &str, char_offset: usize, s2: &str) -> bool {
        let mut i1 = s1.chars();
        for _ in 0..char_offset {
            if i1.next().is_none() {
                return false;
            }
        }
        for c2 in s2.chars() {
            let Some(c1) = i1.next() else {
                return false;
            };
            if !Self::is_ascii_equal(c1, c2) {
                return false;
            }
        }
        true
    }

    pub fn matches_canonical_or_ascii(input: &str, canonical: &str, prefix_match: bool) -> bool {
        let mut in_it = input.chars();
        for target in canonical.chars() {
            let Some(in_ch) = in_it.next() else {
                return false;
            };
            if in_ch != target && Self::get_ascii_equal(target) != in_ch {
                return false;
            }
        }
        prefix_match || in_it.next().is_none()
    }

    pub fn strips_explicit_turkish_diacritic(source: &str, candidate: &str) -> bool {
        let mut s_it = source.chars();
        let mut c_it = candidate.chars();
        let mut stripped_any = false;
        loop {
            match (s_it.next(), c_it.next()) {
                (Some(s), Some(c)) => {
                    let stripped = match s {
                        'ç' => c == 'c',
                        'Ç' => c == 'C',
                        'ğ' => c == 'g',
                        'Ğ' => c == 'G',
                        'ö' => c == 'o',
                        'Ö' => c == 'O',
                        'ş' => c == 's',
                        'Ş' => c == 'S',
                        'ü' => c == 'u',
                        'Ü' => c == 'U',
                        _ => false,
                    };
                    if stripped {
                        stripped_any = true;
                    }
                }
                (None, None) => return stripped_any,
                _ => return false,
            }
        }
    }
}
