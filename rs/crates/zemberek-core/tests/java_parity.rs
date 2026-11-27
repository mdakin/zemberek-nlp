use std::fs;
use std::path::PathBuf;
use zemberek_core::{
    compute_morphemic_attributes, PhonoMask, PronunciationGuesser, TurkishAlphabet, TurkishNumbers,
    TurkishNumeralEndingMachine, TurkishSyllableExtractor,
};

fn unesc(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut it = s.chars();
    while let Some(c) = it.next() {
        if c == '\\' {
            match it.next() {
                Some('t') => out.push('\t'),
                Some('n') => out.push('\n'),
                Some('r') => out.push('\r'),
                Some('\\') => out.push('\\'),
                Some(other) => {
                    out.push('\\');
                    out.push(other);
                }
                None => out.push('\\'),
            }
        } else {
            out.push(c);
        }
    }
    out
}

#[test]
fn java_parity_core_suite() {
    let manifest_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let tsv_path = manifest_dir.join("../../testdata/step1_core_parity.tsv");
    let content = fs::read_to_string(&tsv_path).expect("read step1_core_parity.tsv");

    let mut alpha_checked = 0usize;
    let mut pron_checked = 0usize;
    let mut num_checked = 0usize;

    for (line_no, raw_line) in content.lines().enumerate() {
        let cols: Vec<&str> = raw_line.split('\t').collect();
        match cols[0] {
            "ALPHA" => {
                assert_eq!(cols.len(), 15, "bad ALPHA cols at line {}", line_no + 1);
                let w = unesc(cols[1]);
                let exp_lo = unesc(cols[2]);
                let exp_up = unesc(cols[3]);
                let exp_cap = unesc(cols[4]);
                let exp_asc = unesc(cols[5]);
                let exp_circ = unesc(cols[6]);
                let exp_apos = unesc(cols[7]);
                let exp_strip = unesc(cols[8]);
                let exp_fgn = unesc(cols[9]);
                let exp_phono0: u32 = cols[10].parse().unwrap();
                let sfx = unesc(cols[11]);
                let exp_phono1: u32 = cols[12].parse().unwrap();
                let exp_syl_def = unesc(cols[13]);
                let exp_syl_strict = unesc(cols[14]);

                let got_lo = TurkishAlphabet::to_lower_tr(&w);
                assert_eq!(got_lo, exp_lo, "to_lower_tr mismatch for {w:?}");
                assert_eq!(
                    TurkishAlphabet::to_upper_tr(&w),
                    exp_up,
                    "to_upper_tr mismatch for {w:?}"
                );
                assert_eq!(
                    TurkishAlphabet::capitalize_tr(&w),
                    exp_cap,
                    "capitalize_tr mismatch for {w:?}"
                );
                assert_eq!(
                    TurkishAlphabet::to_ascii(&w),
                    exp_asc,
                    "to_ascii mismatch for {w:?}"
                );
                assert_eq!(
                    TurkishAlphabet::normalize_circumflex(&w),
                    exp_circ,
                    "normalize_circumflex mismatch for {w:?}"
                );
                assert_eq!(
                    TurkishAlphabet::normalize_apostrophe(&w),
                    exp_apos,
                    "normalize_apostrophe mismatch for {w:?}"
                );
                assert_eq!(
                    TurkishAlphabet::strip_apostrophes(&w),
                    exp_strip,
                    "strip_apostrophes mismatch for {w:?}"
                );
                assert_eq!(
                    TurkishAlphabet::foreign_diacritics_to_turkish(&w),
                    exp_fgn,
                    "foreign_diacritics_to_turkish mismatch for {w:?}"
                );

                let p0 = compute_morphemic_attributes(&got_lo, PhonoMask::EMPTY);
                assert_eq!(p0.0, exp_phono0, "phono0 mismatch for {got_lo:?}");
                let p1 = compute_morphemic_attributes(&sfx, p0);
                assert_eq!(
                    p1.0, exp_phono1,
                    "phono1 mismatch for {got_lo:?} + {sfx:?}"
                );

                let syl_def = TurkishSyllableExtractor::DEFAULT
                    .get_syllables(&got_lo)
                    .join("-");
                let syl_strict = TurkishSyllableExtractor::STRICT
                    .get_syllables(&got_lo)
                    .join("-");
                assert_eq!(syl_def, exp_syl_def, "DEFAULT syllables for {got_lo:?}");
                assert_eq!(
                    syl_strict, exp_syl_strict,
                    "STRICT syllables for {got_lo:?}"
                );
                alpha_checked += 1;
            }
            "PRON" => {
                let a = unesc(cols[1]);
                let exp_g1 = unesc(cols[2]);
                let exp_g2 = unesc(cols[3]);
                let exp_g3 = unesc(cols[4]);
                assert_eq!(
                    PronunciationGuesser::guess_for_abbreviation(&a),
                    exp_g1,
                    "guess_for_abbreviation({a:?})"
                );
                assert_eq!(
                    PronunciationGuesser::to_turkish_letter_pronunciations(&a),
                    exp_g2,
                    "to_turkish_letter_pronunciations({a:?})"
                );
                assert_eq!(
                    PronunciationGuesser::replace_english_specific_chars(&a),
                    exp_g3,
                    "replace_english_specific_chars({a:?})"
                );
                pron_checked += 1;
            }
            "NUMEND" => {
                let ns = unesc(cols[1]);
                let exp_end = unesc(cols[2]);
                let exp_conv = unesc(cols[3]);
                assert_eq!(
                    TurkishNumeralEndingMachine::find(&ns),
                    exp_end,
                    "NumeralEndingMachine({ns:?})"
                );
                if exp_conv != "<NA>" {
                    assert_eq!(
                        TurkishNumbers::convert_number_to_string(&ns).unwrap(),
                        exp_conv,
                        "convert_number_to_string({ns:?})"
                    );
                }
                num_checked += 1;
            }
            "NUMLONG" => {
                let lv: i64 = cols[1].parse().unwrap();
                let exp_text = unesc(cols[2]);
                let exp_back: i64 = cols[3].parse().unwrap();
                let got_text = TurkishNumbers::convert_to_string(lv).unwrap();
                assert_eq!(got_text, exp_text, "convert_to_string({lv})");
                if lv >= 0 {
                    assert_eq!(
                        TurkishNumbers::convert_to_number(&got_text),
                        exp_back,
                        "convert_to_number({got_text:?})"
                    );
                }
                num_checked += 1;
            }
            "NUMORD" => {
                let oi = unesc(cols[1]);
                let exp_ord = unesc(cols[2]);
                assert_eq!(
                    TurkishNumbers::convert_ordinal_number_string(&oi).unwrap(),
                    exp_ord,
                    "convert_ordinal_number_string({oi:?})"
                );
                num_checked += 1;
            }
            "ROMAN" => {
                let ri = unesc(cols[1]);
                let exp_dec: i32 = cols[2].parse().unwrap();
                assert_eq!(
                    TurkishNumbers::roman_to_decimal(&ri),
                    exp_dec,
                    "roman_to_decimal({ri:?})"
                );
                num_checked += 1;
            }
            other => panic!("Unknown record type: {other}"),
        }
    }

    // Exhaustively verify all 1..=3999 Roman numerals round-trip cleanly
    const ROMAN_PARTS: &[(i32, &str)] = &[
        (1000, "M"),
        (900, "CM"),
        (500, "D"),
        (400, "CD"),
        (100, "C"),
        (90, "XC"),
        (50, "L"),
        (40, "XL"),
        (10, "X"),
        (9, "IX"),
        (5, "V"),
        (4, "IV"),
        (1, "I"),
    ];
    for n in 1..=3999 {
        let mut rem = n;
        let mut r = String::new();
        for &(val, sym) in ROMAN_PARTS {
            while rem >= val {
                r.push_str(sym);
                rem -= val;
            }
        }
        assert_eq!(TurkishNumbers::roman_to_decimal(&r), n);
        assert_eq!(TurkishNumbers::roman_to_decimal(&r.to_ascii_lowercase()), n);
    }

    assert!(alpha_checked >= 2000);
    assert!(pron_checked >= 500);
    assert!(num_checked >= 70);
}

#[test]
fn turkish_alphabet_unit_edge_cases() {
    assert_eq!(TurkishAlphabet::voice('ç'), 'c');
    assert_eq!(TurkishAlphabet::voice('k'), 'ğ');
    assert_eq!(TurkishAlphabet::voice('p'), 'b');
    assert_eq!(TurkishAlphabet::voice('t'), 'd');
    assert_eq!(TurkishAlphabet::devoice('ğ'), 'k');
    assert_eq!(TurkishAlphabet::devoice('b'), 'p');
    assert!(TurkishAlphabet::check_vowel_harmony_a("elma", "lar"));
    assert!(TurkishAlphabet::check_vowel_harmony_a("kedi", "ler"));
    assert!(!TurkishAlphabet::check_vowel_harmony_a("elma", "ler"));
    assert!(TurkishAlphabet::check_vowel_harmony_i("yoğurt", "u"));
    assert!(!TurkishAlphabet::check_vowel_harmony_i("elma", "yu"));
    assert!(TurkishAlphabet::equals_ignore_diacritics("ağaç", "agac"));
    assert!(TurkishAlphabet::starts_with_ignore_diacritics(
        "kitapçığa",
        5,
        "ciga"
    ));
    assert!(TurkishAlphabet::matches_canonical_or_ascii(
        "birkac", "birkaç", false
    ));
    assert!(!TurkishAlphabet::matches_canonical_or_ascii(
        "bırkac", "birkaç", false
    ));
    assert!(TurkishAlphabet::strips_explicit_turkish_diacritic(
        "göğüs", "gögüs"
    ));
    assert!(!TurkishAlphabet::strips_explicit_turkish_diacritic(
        "sıkıntı", "sikinti"
    ));
}
