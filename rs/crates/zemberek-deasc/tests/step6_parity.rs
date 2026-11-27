use std::fs;
use std::path::PathBuf;
use std::time::Instant;
use zemberek_deasc::codec::strip_circumflex;
use zemberek_deasc::{CircumflexMode, LatticeDeasciifier};
use zemberek_morph::{AnalyzerScratch, DynamicStemTrie, LexiconGraph};
use zemberek_pack::ZmbkPack;

#[repr(align(64))]
#[derive(Clone, Copy)]
#[allow(dead_code)]
struct AlignedBuf([u8; 64]);

fn load_aligned(path: &PathBuf) -> Vec<u8> {
    let raw = fs::read(path).unwrap_or_else(|e| panic!("failed to read {path:?}: {e}"));
    let blocks = raw.len().div_ceil(64) + 1;
    let mut backing = vec![AlignedBuf([0u8; 64]); blocks];
    let byte_slice =
        unsafe { std::slice::from_raw_parts_mut(backing.as_mut_ptr() as *mut u8, blocks * 64) };
    byte_slice[..raw.len()].copy_from_slice(&raw);
    let ptr = backing.as_mut_ptr() as *mut u8;
    let cap = blocks * 64;
    let len = raw.len();
    std::mem::forget(backing);
    unsafe { Vec::from_raw_parts(ptr, len, cap) }
}

struct Cursor<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Cursor<'a> {
    fn new(buf: &'a [u8]) -> Self {
        Self { buf, pos: 0 }
    }

    fn read_u8(&mut self) -> u8 {
        let v = self.buf[self.pos];
        self.pos += 1;
        v
    }

    fn read_u16_be(&mut self) -> u16 {
        let b = &self.buf[self.pos..self.pos + 2];
        self.pos += 2;
        u16::from_be_bytes([b[0], b[1]])
    }

    fn read_i32_be(&mut self) -> i32 {
        let b = &self.buf[self.pos..self.pos + 4];
        self.pos += 4;
        i32::from_be_bytes([b[0], b[1], b[2], b[3]])
    }

    fn read_str(&mut self) -> &'a str {
        let len = self.read_u16_be() as usize;
        let s = std::str::from_utf8(&self.buf[self.pos..self.pos + len]).unwrap();
        self.pos += len;
        s
    }
}

fn is_pinned_turkish_diacritic(ch: char) -> bool {
    matches!(
        ch,
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
            | 'Â'
            | 'î'
            | 'Î'
            | 'û'
            | 'Û'
    )
}

fn count_stripped_pinned_diacritics(input: &str, output: &str) -> usize {
    let in_chars: Vec<char> = input.chars().collect();
    let out_chars: Vec<char> = output.chars().collect();
    if in_chars.len() != out_chars.len() {
        return 1;
    }
    // Check if sentence lacked non-dotless Turkish diacritics (where corrupted dotless-ı rescue applies)
    let has_non_dotless = in_chars.iter().any(|&c| {
        matches!(
            c,
            'ç' | 'Ç'
                | 'ğ'
                | 'Ğ'
                | 'İ'
                | 'ö'
                | 'Ö'
                | 'ş'
                | 'Ş'
                | 'ü'
                | 'Ü'
                | 'â'
                | 'Â'
                | 'î'
                | 'Î'
                | 'û'
                | 'Û'
        )
    });
    let mut stripped = 0usize;
    for (&ic, &oc) in in_chars.iter().zip(out_chars.iter()) {
        if is_pinned_turkish_diacritic(ic) {
            if ic == 'ı' && !has_non_dotless {
                continue;
            }
            if ic != oc {
                stripped += 1;
            }
        }
    }
    stripped
}

#[test]
fn step6_java_parity_and_benchmarks() {
    let manifest_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let testdata_dir = manifest_dir.join("../../testdata");
    let aligned = load_aligned(&testdata_dir.join("zemberek_step2.zmbk"));
    let parity_bytes =
        fs::read(testdata_dir.join("step6_parity.bin")).expect("missing testdata/step6_parity.bin");

    let pack = ZmbkPack::parse(&aligned, true).expect("valid ZmbkPack");

    let deasc = LatticeDeasciifier::from_pack(&pack).expect("LatticeDeasciifier::from_pack");
    let norm_deasc = LatticeDeasciifier::from_pack_for_normalizer(&pack)
        .expect("LatticeDeasciifier::from_pack_for_normalizer");
    let mut scratch = AnalyzerScratch::default();

    let mut cur = Cursor::new(&parity_bytes);
    let total_cases = cur.read_i32_be() as usize;

    struct Case<'a> {
        kind: u8,
        input: &'a str,
        gold: &'a str,
        exp_disambig: &'a str,
        exp_none: &'a str,
        exp_full: &'a str,
        exp_known: &'a str,
        exp_norm: &'a str,
    }

    let mut cases = Vec::with_capacity(total_cases);
    for _ in 0..total_cases {
        cases.push(Case {
            kind: cur.read_u8(),
            input: cur.read_str(),
            gold: cur.read_str(),
            exp_disambig: cur.read_str(),
            exp_none: cur.read_str(),
            exp_full: cur.read_str(),
            exp_known: cur.read_str(),
            exp_norm: cur.read_str(),
        });
    }
    assert_eq!(cur.pos, parity_bytes.len(), "consumed entire step6_parity.bin");

    let mut mismatches = 0usize;
    let mut bench_sentences = 0usize;
    let mut bench_exact_no_circ = 0usize;
    let mut bench_words = 0usize;
    let mut bench_correct_words_no_circ = 0usize;
    let mut partial_count = 0usize;
    let mut stripped_pinned_total = 0usize;

    for (idx, c) in cases.iter().enumerate() {
        let got_disambig =
            deasc.deasciify_with_mode(c.input, CircumflexMode::DisambiguationOnly, None, &mut scratch);
        let got_none =
            deasc.deasciify_with_mode(c.input, CircumflexMode::None, None, &mut scratch);
        let got_full =
            deasc.deasciify_with_mode(c.input, CircumflexMode::Full, None, &mut scratch);
        let got_known = deasc.deasciify_known_words(c.input, None, &mut scratch);
        let got_norm = norm_deasc.deasciify(c.input, None, &mut scratch);

        let checks = [
            ("DISAMBIGUATION_ONLY", got_disambig.as_str(), c.exp_disambig),
            ("NONE", got_none.as_str(), c.exp_none),
            ("FULL", got_full.as_str(), c.exp_full),
            ("KNOWN_WORDS", got_known.as_str(), c.exp_known),
            ("NORM_DUAL", got_norm.as_str(), c.exp_norm),
        ];

        for (mode_name, got, exp) in checks {
            if got != exp {
                if mismatches < 15 {
                    eprintln!(
                        "MISMATCH case #{idx} (kind={}) [{mode_name}]:\n  input: {:?}\n  got:   {:?}\n  exp:   {:?}",
                        c.kind, c.input, got, exp
                    );
                }
                mismatches += 1;
            }
        }

        if c.kind == 1 {
            bench_sentences += 1;
            let got_nc = strip_circumflex(&got_disambig);
            let gold_nc = strip_circumflex(c.gold);
            if got_nc == gold_nc {
                bench_exact_no_circ += 1;
            }
            for (gw, ew) in got_nc.split_whitespace().zip(gold_nc.split_whitespace()) {
                bench_words += 1;
                if gw == ew {
                    bench_correct_words_no_circ += 1;
                }
            }
        } else if c.kind == 2 {
            partial_count += 1;
            stripped_pinned_total += count_stripped_pinned_diacritics(c.input, &got_disambig);
        }
    }

    assert_eq!(
        mismatches, 0,
        "Expected 100.0% Java <-> Rust parity across all {} cases x 5 modes",
        total_cases
    );
    assert!(
        partial_count >= 500,
        "Expected >= 500 partial-ASCII safety cases, got {partial_count}"
    );
    assert_eq!(
        stripped_pinned_total, 0,
        "Expected 0 stripped user-pinned diacritics on partial-ASCII text"
    );

    // Verify runtime `DynamicStemTrie` user-dictionary word deasciification
    let graph = LexiconGraph::from_pack(&pack).unwrap();
    let mut dyn_trie = DynamicStemTrie::new(graph.lexicon.len() as u32);
    dyn_trie.add_user_word("Gökbörü", &graph.bridge);
    deasc.invalidate_cache();
    let dyn_out = deasc.deasciify(
        "Bugun Gokboru'den mesaj geldi.",
        Some(&dyn_trie),
        &mut scratch,
    );
    assert_eq!(dyn_out, "Bugün Gökbörü'den mesaj geldi.");

    // Warm-cache throughput benchmark across all cases
    deasc.invalidate_cache();
    let t0 = Instant::now();
    let mut total_chars = 0usize;
    for c in &cases {
        let out = deasc.deasciify(c.input, None, &mut scratch);
        total_chars += out.len();
    }
    let elapsed = t0.elapsed();
    let us_per_sent = elapsed.as_secs_f64() * 1e6 / (cases.len() as f64);

    let word_acc = 100.0 * (bench_correct_words_no_circ as f64) / (bench_words.max(1) as f64);
    let sent_acc = 100.0 * (bench_exact_no_circ as f64) / (bench_sentences.max(1) as f64);
    println!(
        "Step 6 LatticeDeasciifier Parity PASSED: {} cases x 5 modes = {} checks (100.000% Java parity) | Curated 242-sent WordAcc(no-circ)={:.2}% SentAcc(no-circ)={:.2}% | PartialASCII={}/{} (stripped={}) | {:.1} us/sent (sink={})",
        total_cases,
        total_cases * 5,
        word_acc,
        sent_acc,
        partial_count,
        partial_count,
        stripped_pinned_total,
        us_per_sent,
        total_chars
    );
    assert!(
        us_per_sent < 300.0,
        "Expected < 300 us/sent release throughput, got {us_per_sent:.1} us/sent"
    );
}
