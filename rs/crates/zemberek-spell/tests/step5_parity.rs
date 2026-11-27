use std::fs;
use std::path::PathBuf;
use std::time::Instant;
use zemberek_morph::{
    AnalyzerScratch, DynamicStemTrie, MorphologyConfig, TurkishMorphology,
};
use zemberek_pack::clean_lm::CleanWordLmView;
use zemberek_pack::{ZmbkPack, SECT_CLEAN_LM, SECT_ENDING_TRIE};
use zemberek_spell::CompactStemEndingSpellChecker;

#[repr(align(64))]
#[derive(Clone, Copy)]
#[allow(dead_code)]
struct AlignedBuf([u8; 64]);

fn load_aligned(path: &PathBuf) -> Vec<u8> {
    let raw = fs::read(path).unwrap_or_else(|e| panic!("failed to read {path:?}: {e}"));
    let blocks = raw.len().div_ceil(64) + 1;
    let mut backing = vec![AlignedBuf([0u8; 64]); blocks];
    let byte_slice = unsafe {
        std::slice::from_raw_parts_mut(backing.as_mut_ptr() as *mut u8, blocks * 64)
    };
    byte_slice[..raw.len()].copy_from_slice(&raw);
    let ptr = backing.as_mut_ptr() as *mut u8;
    let cap = blocks * 64;
    let len = raw.len();
    std::mem::forget(backing);
    unsafe { Vec::from_raw_parts(ptr, len, cap) }
}

struct BinReader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> BinReader<'a> {
    fn new(buf: &'a [u8]) -> Self {
        Self { buf, pos: 0 }
    }

    fn read_u8(&mut self) -> u8 {
        let v = self.buf[self.pos];
        self.pos += 1;
        v
    }

    fn read_bool(&mut self) -> bool {
        self.read_u8() != 0
    }

    fn read_u16_be(&mut self) -> u16 {
        let v = u16::from_be_bytes([self.buf[self.pos], self.buf[self.pos + 1]]);
        self.pos += 2;
        v
    }

    fn read_u32_be(&mut self) -> u32 {
        let v = u32::from_be_bytes([
            self.buf[self.pos],
            self.buf[self.pos + 1],
            self.buf[self.pos + 2],
            self.buf[self.pos + 3],
        ]);
        self.pos += 4;
        v
    }

    fn read_f32_be(&mut self) -> f32 {
        f32::from_bits(self.read_u32_be())
    }

    fn read_str(&mut self) -> &'a str {
        let len = self.read_u16_be() as usize;
        let s = std::str::from_utf8(&self.buf[self.pos..self.pos + len]).unwrap();
        self.pos += len;
        s
    }

    fn read_opt_str(&mut self) -> Option<&'a str> {
        if self.read_bool() {
            Some(self.read_str())
        } else {
            None
        }
    }
}

#[test]
fn step5_clean_lm_and_spellchecker_java_parity() {
    let manifest_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let testdata = manifest_dir.join("../../testdata");
    let zmbk_bytes = load_aligned(&testdata.join("zemberek_step2.zmbk"));
    let parity_raw = fs::read(testdata.join("step5_parity.bin"))
        .expect("missing testdata/step5_parity.bin — run ZmbkExporter first");

    let pack = ZmbkPack::parse(&zmbk_bytes, true).expect("valid .zmbk pack");
    let (_, clean_lm_bytes) = pack
        .section_bytes(SECT_CLEAN_LM)
        .expect("SECT_CLEAN_LM present");
    let (_, ending_bytes) = pack
        .section_bytes(SECT_ENDING_TRIE)
        .expect("SECT_ENDING_TRIE present");

    assert!(
        ending_bytes.len() <= 420 * 1024,
        "SECT_ENDING_TRIE exceeded 420 KiB budget: {} B",
        ending_bytes.len()
    );

    let lm = CleanWordLmView::from_section_bytes(clean_lm_bytes).expect("valid CleanWordLmView");
    let morph = TurkishMorphology::from_pack(&pack, MorphologyConfig::default())
        .expect("valid TurkishMorphology");

    let t_build = Instant::now();
    let mut spell = CompactStemEndingSpellChecker::from_pack(&pack, &morph.graph, None)
        .expect("valid CompactStemEndingSpellChecker");
    let build_ms = t_build.elapsed().as_secs_f64() * 1000.0;

    let mut r = BinReader::new(&parity_raw);

    // 1. CleanWordLm 10,000 n-gram parity
    let lm_case_count = r.read_u32_be() as usize;
    assert!(lm_case_count >= 10_000, "expected >= 10,000 LM cases");
    for i in 0..lm_case_count {
        let w0 = r.read_opt_str();
        let w1 = r.read_opt_str();
        let w2 = r.read_opt_str();
        let exp_uni_cnt = r.read_u32_be();
        let exp_bi_cnt = r.read_u32_be();
        let exp_uni_lp = r.read_f32_be();
        let exp_bi_lp = r.read_f32_be();
        let exp_tri_lp = r.read_f32_be();

        let got_uni_cnt = w2.map_or(0, |s| lm.unigram_count(s));
        let got_bi_cnt = lm.bigram_count(w1, w2);
        let got_uni_lp = lm.unigram_log_prob(w2);
        let got_bi_lp = lm.bigram_log_prob(w1, w2);
        let got_tri_lp = lm.trigram_log_prob(w0, w1, w2);

        assert_eq!(got_uni_cnt, exp_uni_cnt, "LM unigramCount mismatch at #{i} ({w2:?})");
        assert_eq!(
            got_bi_cnt, exp_bi_cnt,
            "LM bigramCount mismatch at #{i} ({w1:?}, {w2:?})"
        );
        assert!(
            (got_uni_lp - exp_uni_lp).abs() < 1e-6,
            "LM unigramLogProb mismatch at #{i} ({w2:?}): got={got_uni_lp} exp={exp_uni_lp}"
        );
        assert!(
            (got_bi_lp - exp_bi_lp).abs() < 1e-6,
            "LM bigramLogProb mismatch at #{i} ({w1:?}, {w2:?}): got={got_bi_lp} exp={exp_bi_lp}"
        );
        assert!(
            (got_tri_lp - exp_tri_lp).abs() < 1e-6,
            "LM trigramLogProb mismatch at #{i} ({w0:?}, {w1:?}, {w2:?}): got={got_tri_lp} exp={exp_tri_lp}"
        );
    }

    // 2. TurkishSpellChecker.check(word) parity
    let mut scratch = AnalyzerScratch::default();
    let check_count = r.read_u32_be() as usize;
    for i in 0..check_count {
        let w = r.read_str();
        let exp_ok = r.read_bool();
        let got_ok = spell.check(w, &morph, None, &mut scratch);
        assert_eq!(got_ok, exp_ok, "spell.check mismatch at #{i} word={w:?}");
    }

    // 3. CompactStemEndingSpellChecker.suggestScored(word) parity across 5,000 noisy/misspelled words
    let suggest_count = r.read_u32_be() as usize;
    assert!(suggest_count >= 5_000, "expected >= 5,000 suggestScored cases");
    let mut words_and_expected = Vec::with_capacity(suggest_count);
    for _ in 0..suggest_count {
        let w = r.read_str();
        let cand_cnt = r.read_u16_be() as usize;
        let mut exp_cands = Vec::with_capacity(cand_cnt);
        for _ in 0..cand_cnt {
            let surf = r.read_str();
            let edit_cost = r.read_f32_be();
            let diac = r.read_u16_be() as u32;
            exp_cands.push((surf, edit_cost, diac));
        }
        words_and_expected.push((w, exp_cands));
    }

    let t_suggest = Instant::now();
    let mut total_cands = 0usize;
    for (i, (w, exp)) in words_and_expected.iter().enumerate() {
        let got = spell.suggest_scored(w, &morph, None, &mut scratch);
        total_cands += got.len();
        if got.len() != exp.len() {
            panic!(
                "suggest_scored len mismatch at #{i} word={w:?}:\n  got ({}): {:?}\n  exp ({}): {:?}",
                got.len(),
                got,
                exp.len(),
                exp
            );
        }
        for (j, (g, &(exp_surf, exp_cost, exp_diac))) in got.iter().zip(exp.iter()).enumerate() {
            assert_eq!(
                g.surface, exp_surf,
                "suggest_scored surface mismatch at #{i} cand #{j} word={w:?}:\n  got={got:?}\n  exp={exp:?}"
            );
            assert!(
                (g.edit_cost - exp_cost).abs() < 1e-6,
                "suggest_scored editCost mismatch at #{i} cand #{j} word={w:?}: got={} exp={exp_cost}",
                g.edit_cost
            );
            assert_eq!(
                g.diacritic_diffs, exp_diac,
                "suggest_scored diacriticDiffs mismatch at #{i} cand #{j} word={w:?}"
            );
        }
    }
    let us_per_word = (t_suggest.elapsed().as_secs_f64() * 1e6) / (suggest_count as f64);

    assert!(
        us_per_word < 200.0,
        "Uncached suggest_scored stress latency {:.2} µs/word exceeded 200 µs gate",
        us_per_word
    );

    // 4. Verify runtime DynamicStemTrie user-word spellchecking + CleanWordLm ranking
    let mut dyn_trie = DynamicStemTrie::new(morph.graph.lexicon.len() as u32);
    dyn_trie.add_user_word("Antigravity", &morph.graph.bridge);
    spell.rebuild_stems(&morph.graph, Some(&dyn_trie));
    assert!(spell.check("Antigravity'de", &morph, Some(&dyn_trie), &mut scratch));
    let dyn_sugs = spell.suggest_scored("Antigravty'de", &morph, Some(&dyn_trie), &mut scratch);
    assert!(
        dyn_sugs.iter().any(|c| c.surface == "Antigravity'de"),
        "Expected 'Antigravity\\'de' in dynamic suggestions, got: {dyn_sugs:?}"
    );
    let ranked = spell.suggest_ranked(
        "kitablar",
        Some("yeni"),
        Some("aldım"),
        &lm,
        &morph,
        None,
        &mut scratch,
    );
    assert_eq!(ranked.first().map(String::as_str), Some("kitaplar"));

    eprintln!(
        "Step 5 parity PASSED: SECT_CLEAN_LM={} B ({:.2} MiB) | SECT_ENDING_TRIE={} B ({:.1} KiB, {} nodes) | stem_trie_build={:.2} ms | lm_cases={} | checks={} | suggest_words={} (total_cands={}, {:.2} µs/word)",
        clean_lm_bytes.len(),
        clean_lm_bytes.len() as f64 / (1024.0 * 1024.0),
        ending_bytes.len(),
        ending_bytes.len() as f64 / 1024.0,
        spell.ending_trie().node_count(),
        build_ms,
        lm_case_count,
        check_count,
        suggest_count,
        total_cands,
        us_per_word
    );
}
