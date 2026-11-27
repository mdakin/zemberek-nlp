use std::collections::{BTreeMap, BTreeSet};
use std::env;
use std::fs;
use std::io::Write;
use std::path::PathBuf;

fn is_vowel(c: char) -> bool {
    matches!(
        c,
        'a' | 'e'
            | 'ı'
            | 'i'
            | 'o'
            | 'ö'
            | 'u'
            | 'ü'
            | 'â'
            | 'î'
            | 'û'
            | 'A'
            | 'E'
            | 'I'
            | 'İ'
            | 'O'
            | 'Ö'
            | 'U'
            | 'Ü'
            | 'Â'
            | 'Î'
            | 'Û'
    )
}

fn contains_vowel(s: &str) -> bool {
    s.chars().any(is_vowel)
}

fn to_lower_tr(s: &str) -> String {
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

fn java_trim(s: &str) -> &str {
    let bytes = s.as_bytes();
    let mut start = 0;
    while start < bytes.len() && bytes[start] <= 0x20 {
        start += 1;
    }
    let mut end = bytes.len();
    while end > start && bytes[end - 1] <= 0x20 {
        end -= 1;
    }
    &s[start..end]
}

fn is_lexer_split_ws(c: char) -> bool {
    matches!(
        c,
        ' ' | '\t' | '\n' | '\u{000B}' | '\u{000C}' | '\r' | '\u{00A0}'
    )
}

fn is_ascii_ws(c: char) -> bool {
    matches!(c, ' ' | '\t' | '\n' | '\u{000B}' | '\u{000C}' | '\r')
}

fn read_modified_utf8(bytes: &[u8]) -> String {
    let mut chars: Vec<u16> = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        let c = bytes[i] as u16;
        match c >> 4 {
            0..=7 => {
                i += 1;
                chars.push(c);
            }
            12 | 13 => {
                let c2 = bytes[i + 1] as u16;
                i += 2;
                chars.push(((c & 0x1F) << 6) | (c2 & 0x3F));
            }
            14 => {
                let c2 = bytes[i + 1] as u16;
                let c3 = bytes[i + 2] as u16;
                i += 3;
                chars.push(((c & 0x0F) << 12) | ((c2 & 0x3F) << 6) | (c3 & 0x3F));
            }
            _ => panic!("Invalid modified UTF-8 byte: {c:#x}"),
        }
    }
    String::from_utf16(&chars).expect("Valid UTF-16 from DataInputStream.readUTF")
}

fn main() {
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let res_dir = manifest_dir.join("../../../tokenization/src/main/resources/tokenization");
    let abbr_path = res_dir.join("abbreviations.txt");
    let model_path = res_dir.join("sentence-boundary-model.bin");

    println!("cargo:rerun-if-changed={}", abbr_path.display());
    println!("cargo:rerun-if-changed={}", model_path.display());

    let abbr_content = fs::read_to_string(&abbr_path).expect("read abbreviations.txt");

    // 1. TurkishLexer.loadAbbreviations()
    let mut lexer_abbrs = BTreeSet::new();
    for raw_line in abbr_content.lines() {
        let trimmed = java_trim(raw_line);
        if trimmed.is_empty() {
            continue;
        }
        for part in trimmed.split(is_lexer_split_ws).filter(|p| !p.is_empty()) {
            if part.ends_with('.') {
                lexer_abbrs.insert(part.to_string());
                if !contains_vowel(part) {
                    lexer_abbrs.insert(to_lower_tr(part));
                }
            }
        }
    }

    // 2. PerceptronSegmenter static initializer
    let mut seg_abbrs = BTreeSet::new();
    for raw_line in abbr_content.lines() {
        let line = java_trim(raw_line);
        if line.is_empty() {
            continue;
        }
        let no_ws: String = line.chars().filter(|&c| !is_ascii_ws(c)).collect();
        let abbr = no_ws.strip_suffix('.').unwrap_or(&no_ws).to_string();
        if abbr.is_empty() {
            continue;
        }
        let first_upper = abbr.chars().next().is_some_and(char::is_uppercase);
        if line.ends_with('.') || first_upper {
            seg_abbrs.insert(abbr.clone());
        }
        if !contains_vowel(&abbr) {
            seg_abbrs.insert(to_lower_tr(&abbr));
        }
    }

    // 3. sentence-boundary-model.bin
    let model_bytes = fs::read(&model_path).expect("read sentence-boundary-model.bin");
    assert!(model_bytes.len() >= 4);
    let count = u32::from_be_bytes(model_bytes[0..4].try_into().unwrap()) as usize;
    let mut pos = 4usize;
    let mut weights = BTreeMap::new();
    for _ in 0..count {
        let utf_len = u16::from_be_bytes(model_bytes[pos..pos + 2].try_into().unwrap()) as usize;
        pos += 2;
        let key = read_modified_utf8(&model_bytes[pos..pos + utf_len]);
        pos += utf_len;
        let val_bits = u32::from_be_bytes(model_bytes[pos..pos + 4].try_into().unwrap());
        pos += 4;
        weights.insert(key, val_bits);
    }
    assert_eq!(pos, model_bytes.len());

    let out_dir = PathBuf::from(env::var("OUT_DIR").unwrap());
    let dest = out_dir.join("generated_tok_data.rs");
    let mut f = fs::File::create(dest).unwrap();

    writeln!(f, "pub static LEXER_ABBREVIATIONS: &[&str] = &[").unwrap();
    for a in &lexer_abbrs {
        writeln!(f, "    {a:?},").unwrap();
    }
    writeln!(f, "];\n").unwrap();

    writeln!(f, "pub static SEGMENTER_ABBREVIATIONS: &[&str] = &[").unwrap();
    for a in &seg_abbrs {
        writeln!(f, "    {a:?},").unwrap();
    }
    writeln!(f, "];\n").unwrap();

    writeln!(f, "pub static SENTENCE_BOUNDARY_WEIGHTS: &[(&str, u32)] = &[").unwrap();
    for (k, bits) in &weights {
        writeln!(f, "    ({k:?}, {bits:#010x}),").unwrap();
    }
    writeln!(f, "];").unwrap();
}
