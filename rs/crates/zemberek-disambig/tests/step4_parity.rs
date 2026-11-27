use std::fs;
use std::path::PathBuf;
use std::time::Instant;
use zemberek_disambig::{DecodeMode, DisambigScratch, FastPerceptronDisambiguator};
use zemberek_morph::{AnalyzerScratch, MorphologyConfig, TurkishMorphology};
use zemberek_pack::{ZmbkPack, SECT_AMBIG_QWT};

#[repr(align(64))]
struct AlignedBuf(#[allow(dead_code)] [u8; 64]);

fn read_aligned(path: &PathBuf) -> Vec<u8> {
    let raw = fs::read(path).expect("read file");
    let blocks = raw.len().div_ceil(64) + 1;
    let mut backing: Vec<AlignedBuf> = (0..blocks).map(|_| AlignedBuf([0u8; 64])).collect();
    let ptr = backing.as_mut_ptr() as *mut u8;
    let cap_bytes = backing.capacity() * 64;
    let len = raw.len();
    std::mem::forget(backing);
    let mut aligned = unsafe { Vec::from_raw_parts(ptr, len, cap_bytes) };
    aligned.copy_from_slice(&raw);
    aligned
}

struct BinReader<'a> {
    bytes: &'a [u8],
    pos: usize,
}

impl<'a> BinReader<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, pos: 0 }
    }

    fn u16_be(&mut self) -> u16 {
        let v = u16::from_be_bytes(self.bytes[self.pos..self.pos + 2].try_into().unwrap());
        self.pos += 2;
        v
    }

    fn u32_be(&mut self) -> u32 {
        let v = u32::from_be_bytes(self.bytes[self.pos..self.pos + 4].try_into().unwrap());
        self.pos += 4;
        v
    }

    fn f32_be(&mut self) -> f32 {
        let v = f32::from_be_bytes(self.bytes[self.pos..self.pos + 4].try_into().unwrap());
        self.pos += 4;
        v
    }

    fn str(&mut self) -> &'a str {
        let len = self.u16_be() as usize;
        let s = std::str::from_utf8(&self.bytes[self.pos..self.pos + len]).unwrap();
        self.pos += len;
        s
    }
}

#[test]
fn step4_quantized_disambiguator_parity_and_latency() {
    let manifest_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let zmbk_path = manifest_dir.join("../../testdata/zemberek_step2.zmbk");
    let parity_path = manifest_dir.join("../../testdata/step4_parity.bin");

    let zmbk_bytes = read_aligned(&zmbk_path);
    let pack = ZmbkPack::parse(&zmbk_bytes, true).expect("parse zmbk with CRC32");

    let (qwt_sec, _) = pack.section_bytes(SECT_AMBIG_QWT).expect("SECT_AMBIG_QWT");
    let morph = TurkishMorphology::from_pack(&pack, MorphologyConfig::default()).unwrap();
    let disambig = FastPerceptronDisambiguator::from_pack(&pack).unwrap();

    let fixture = fs::read(&parity_path).expect("read step4_parity.bin");
    let mut r = BinReader::new(&fixture);

    let base_ambig_acc = r.f32_be();
    let quant_ambig_acc = r.f32_be();
    let acc_delta = (base_ambig_acc - quant_ambig_acc).abs();
    assert!(
        acc_delta < 0.0015,
        "Quantized model accuracy delta {:.4}% exceeds 0.15% gate (base={:.4}, quant={:.4})",
        acc_delta * 100.0,
        base_ambig_acc * 100.0,
        quant_ambig_acc * 100.0
    );

    let sent_count = r.u32_be() as usize;
    assert!(sent_count >= 2000, "Expected >= 2,000 benchmark sentences, got {sent_count}");

    let mut morph_scratch = AnalyzerScratch::default();
    let mut dis_scratch = DisambigScratch::new();
    let mut pre_analyzed = Vec::with_capacity(sent_count);

    let decode_modes = [
        ("Viterbi", DecodeMode::Viterbi),
        ("Beam8", DecodeMode::Beam(8)),
        ("Greedy", DecodeMode::Greedy),
    ];

    let mut checked_tokens = 0usize;
    for s_idx in 0..sent_count {
        let sent = r.str();
        let was = morph.analyze_sentence(sent, None, &mut morph_scratch);
        let exp_tok_cnt = r.u16_be() as usize;
        assert_eq!(was.len(), exp_tok_cnt, "token count mismatch at #{s_idx}");

        for &(mode_name, mode) in &decode_modes {
            let exp_score = r.f32_be();
            let mut exp_best = Vec::with_capacity(exp_tok_cnt);
            for _ in 0..exp_tok_cnt {
                exp_best.push(r.str());
            }

            let res = disambig.disambiguate(&morph, sent, was.clone(), mode, &mut dis_scratch);
            let got_best: Vec<String> = res
                .word_analyses
                .iter()
                .map(|swa| swa.best_analysis.format_long(&morph.graph, &morph.fst))
                .collect();

            assert_eq!(
                got_best, exp_best,
                "Disambiguation best-parse mismatch at sent #{s_idx} mode={mode_name} (exp_score={exp_score}, got_score={}) sent={sent:?}",
                res.score
            );
            assert!(
                (res.score - exp_score).abs() <= 1e-3,
                "Score drift at sent #{s_idx} mode={mode_name}: exp={exp_score}, got={}",
                res.score
            );
            checked_tokens += exp_tok_cnt;
        }
        pre_analyzed.push((sent, was));
    }
    assert_eq!(r.pos, fixture.len(), "unread bytes in step4_parity.bin");

    // Measure pure disambiguator latency (warm steady-state L1 cache, matching Wasm Worker runtime)
    for &(_sent, ref was) in &pre_analyzed {
        let _ = disambig.best_indices(&morph, was, DecodeMode::Viterbi, &mut dis_scratch);
    }
    let t0 = Instant::now();
    for &(_sent, ref was) in &pre_analyzed {
        let _ = disambig.best_indices(&morph, was, DecodeMode::Viterbi, &mut dis_scratch);
    }
    let elapsed_vit = t0.elapsed().as_secs_f64();
    let us_per_sent_vit = (elapsed_vit * 1_000_000.0) / (pre_analyzed.len() as f64);

    let t1 = Instant::now();
    for &(_sent, ref was) in &pre_analyzed {
        let _ = disambig.best_indices(&morph, was, DecodeMode::Beam(8), &mut dis_scratch);
    }
    let us_per_sent_beam = (t1.elapsed().as_secs_f64() * 1_000_000.0) / (pre_analyzed.len() as f64);

    eprintln!(
        "[Step 4 Parity OK] SECT_AMBIG_QWT={} B ({:.3} MiB) | base_ambig={:.2}% -> quant_ambig={:.2}% (delta={:+.3}%) | sentences={} (tokens_checked={}) | Viterbi={:.2} us/sent | Beam8={:.2} us/sent",
        qwt_sec.byte_len,
        (qwt_sec.byte_len as f64) / (1024.0 * 1024.0),
        base_ambig_acc * 100.0,
        quant_ambig_acc * 100.0,
        (quant_ambig_acc - base_ambig_acc) * 100.0,
        sent_count,
        checked_tokens,
        us_per_sent_vit,
        us_per_sent_beam
    );

    #[cfg(not(debug_assertions))]
    assert!(
        us_per_sent_beam < 100.0,
        "Disambiguation latency {us_per_sent_beam:.2} us/sent exceeds 100 us/sent gate"
    );
}
