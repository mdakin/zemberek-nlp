use std::fs;
use std::path::PathBuf;
use std::time::Instant;
use zemberek_morph::{AnalyzerScratch, DynamicStemTrie};
use zemberek_norm::{IssueKind, SpanLatticePerceptronNormalizer};
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

#[test]
fn step7_java_parity_and_diagnostics() {
    let manifest_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let testdata_dir = manifest_dir.join("../../testdata");
    let aligned = load_aligned(&testdata_dir.join("zemberek_step2.zmbk"));
    let parity_bytes =
        fs::read(testdata_dir.join("step7_parity.bin")).expect("missing testdata/step7_parity.bin");

    let pack = ZmbkPack::parse(&aligned, true).expect("valid ZmbkPack");
    let mut norm =
        SpanLatticePerceptronNormalizer::from_pack(&pack, None).expect("from_pack normalizer");
    let mut scratch = AnalyzerScratch::default();

    let mut cur = Cursor::new(&parity_bytes);
    let total_sents = cur.read_i32_be() as usize;
    assert!(
        total_sents >= 1000,
        "Expected >= 1,000 noisy parity sentences, got {total_sents}"
    );

    struct ExpectedArc<'a> {
        start_idx: usize,
        end_idx: usize,
        norm_surface: &'a str,
    }

    struct Case<'a> {
        raw: &'a str,
        exp_norm: &'a str,
        exp_arcs: Vec<ExpectedArc<'a>>,
    }

    let mut cases = Vec::with_capacity(total_sents);
    for _ in 0..total_sents {
        let raw = cur.read_str();
        let exp_norm = cur.read_str();
        let arc_cnt = cur.read_u16_be() as usize;
        let mut exp_arcs = Vec::with_capacity(arc_cnt);
        for _ in 0..arc_cnt {
            exp_arcs.push(ExpectedArc {
                start_idx: cur.read_u16_be() as usize,
                end_idx: cur.read_u16_be() as usize,
                norm_surface: cur.read_str(),
            });
        }
        cases.push(Case {
            raw,
            exp_norm,
            exp_arcs,
        });
    }
    assert_eq!(cur.pos, parity_bytes.len(), "consumed entire step7_parity.bin");

    let mut text_mismatches = 0usize;
    let mut arc_mismatches = 0usize;
    let mut total_arcs = 0usize;

    for (idx, c) in cases.iter().enumerate() {
        let lat = norm.generator.build_lattice(c.raw, None, &mut scratch);
        let got_arcs = norm.decode_lattice(&lat, None, &mut scratch);
        let got_norm = norm.normalize(c.raw, None, &mut scratch);
        total_arcs += got_arcs.len();

        if got_norm != c.exp_norm {
            if text_mismatches < 15 {
                eprintln!(
                    "TEXT MISMATCH #{idx}:\n  raw: {:?}\n  got: {:?}\n  exp: {:?}",
                    c.raw, got_norm, c.exp_norm
                );
            }
            text_mismatches += 1;
        }

        let same_arcs = got_arcs.len() == c.exp_arcs.len()
            && got_arcs.iter().zip(c.exp_arcs.iter()).all(|(ga, ea)| {
                ga.start_idx == ea.start_idx
                    && ga.end_idx == ea.end_idx
                    && ga.norm_surface == ea.norm_surface
            });
        if !same_arcs {
            if arc_mismatches < 10 {
                let got_fmt: Vec<_> = got_arcs
                    .iter()
                    .map(|a| (a.start_idx, a.end_idx, a.norm_surface.as_str()))
                    .collect();
                let exp_fmt: Vec<_> = c
                    .exp_arcs
                    .iter()
                    .map(|a| (a.start_idx, a.end_idx, a.norm_surface))
                    .collect();
                eprintln!(
                    "ARC MISMATCH #{idx}:\n  raw: {:?}\n  got: {:?}\n  exp: {:?}",
                    c.raw, got_fmt, exp_fmt
                );
            }
            arc_mismatches += 1;
        }
    }

    assert_eq!(
        text_mismatches, 0,
        "Expected 100.0% normalized string parity across {total_sents} sentences"
    );
    assert_eq!(
        arc_mismatches, 0,
        "Expected 100.0% chosen SpanArc sequence parity across {total_sents} sentences"
    );

    // Verify `check_paragraph` browser diagnostics (`SpanIssue` categories + surrogate-pair emoji UTF-16 offsets!)
    let para = "Merhaba 😀 bugun kutuphaneye gidicem gelecekmisiniz? Okudm kitablar cok guzel!";
    let para_u16: Vec<u16> = para.encode_utf16().collect();
    let issues = norm.check_paragraph(para, None, &mut scratch);
    assert!(issues.len() >= 5, "expected multiple issues, got {issues:?}");

    let mut saw_deasc_or_colloq = false;
    let mut saw_clitic = false;
    let mut saw_spelling = false;
    for iss in &issues {
        assert_eq!(&para[iss.utf8_start..iss.utf8_end], iss.raw_text);
        let sliced_from_u16 =
            String::from_utf16(&para_u16[iss.utf16_start..iss.utf16_end]).unwrap();
        assert_eq!(
            sliced_from_u16, iss.raw_text,
            "UTF-16 span slice must match raw_text even after surrogate-pair emoji!"
        );
        assert_eq!(iss.suggestions.first(), Some(&iss.replacement));
        match iss.kind {
            IssueKind::DeasciifyOrColloquial => saw_deasc_or_colloq = true,
            IssueKind::CliticSplitMerge => saw_clitic = true,
            IssueKind::Spelling => saw_spelling = true,
        }
    }
    assert!(saw_deasc_or_colloq, "expected DeasciifyOrColloquial issue");
    assert!(saw_clitic, "expected CliticSplitMerge issue");
    assert!(saw_spelling, "expected Spelling issue");

    // Verify DynamicStemTrie custom user-dictionary words (common + proper) in normalizer & diagnostics
    let mut dyn_trie =
        DynamicStemTrie::new(norm.generator.morph.strict_morph.graph.lexicon.len() as u32);
    dyn_trie.add_user_word("Gökbörü", &norm.generator.morph.strict_morph.graph.bridge);
    dyn_trie.add_user_word("kankitap", &norm.generator.morph.strict_morph.graph.bridge);
    norm.rebuild_dynamic_caches(Some(&dyn_trie));
    let dyn_norm_proper = norm.normalize(
        "Bugun Gökbörü'de toplanti yapicaz.",
        Some(&dyn_trie),
        &mut scratch,
    );
    assert_eq!(dyn_norm_proper, "Bugün Gökbörü'de toplantı yapacağız.");
    let dyn_norm_common = norm.normalize(
        "Bugun kankitabimizda toplanti yapicaz.",
        Some(&dyn_trie),
        &mut scratch,
    );
    assert_eq!(dyn_norm_common, "Bugün kankitabımızda toplantı yapacağız.");
    let clean_dyn_issues = norm.check_paragraph(
        "Gökbörü'de kankitabımız hazır.",
        Some(&dyn_trie),
        &mut scratch,
    );
    assert!(
        clean_dyn_issues.is_empty(),
        "Custom user words must produce 0 false-positive issues, got {clean_dyn_issues:?}"
    );

    // Warm single-sentence throughput benchmark
    let t0 = Instant::now();
    let mut sink = 0usize;
    for c in cases.iter().take(300) {
        sink += norm.normalize(c.raw, None, &mut scratch).len();
    }
    let us_per_sent = t0.elapsed().as_secs_f64() * 1e6 / 300.0;

    println!(
        "Step 7 SpanLatticePerceptronNormalizer Parity PASSED: {total_sents}/{total_sents} sentences & {total_arcs}/{total_arcs} SpanArcs (100.000% Java parity) | Diagnostics={:?} kinds verified (emoji UTF-16 exact) | {:.1} us/sent (sink={sink})",
        issues.len(),
        us_per_sent
    );
}
