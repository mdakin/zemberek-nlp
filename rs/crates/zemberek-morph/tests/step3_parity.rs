use std::fs;
use std::path::PathBuf;
use std::time::Instant;
use zemberek_morph::{
    AnalyzerScratch, CaseType, DynamicStemTrie, MorphologyConfig, TurkishMorphology,
    WordAnalysisSurfaceFormatter,
};
use zemberek_pack::{ZmbkPack, SECT_MORPH_FST};

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

fn fnv1a64_update(mut hash: u64, s: &str) -> u64 {
    for &b in s.as_bytes() {
        hash ^= b as u64;
        hash = hash.wrapping_mul(0x100000001b3);
    }
    hash ^= 0xFF;
    hash = hash.wrapping_mul(0x100000001b3);
    hash
}

struct BinReader<'a> {
    bytes: &'a [u8],
    pos: usize,
}

impl<'a> BinReader<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, pos: 0 }
    }

    fn u8(&mut self) -> u8 {
        let v = self.bytes[self.pos];
        self.pos += 1;
        v
    }

    fn bool(&mut self) -> bool {
        self.u8() != 0
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

    fn u64_be(&mut self) -> u64 {
        let v = u64::from_be_bytes(self.bytes[self.pos..self.pos + 8].try_into().unwrap());
        self.pos += 8;
        v
    }

    fn str(&mut self) -> &'a str {
        let len = self.u16_be() as usize;
        let s = std::str::from_utf8(&self.bytes[self.pos..self.pos + len]).unwrap();
        self.pos += len;
        s
    }
}

fn case_from_ordinal(ord: u8) -> CaseType {
    match ord {
        0 => CaseType::DefaultCase,
        1 => CaseType::LowerCase,
        2 => CaseType::UpperCase,
        3 => CaseType::TitleCase,
        4 => CaseType::UpperCaseRootLowerCaseEnding,
        _ => CaseType::MixedCase,
    }
}

#[test]
fn step3_materialized_fst_10k_sentences_3modes_generator_and_speed_parity() {
    let manifest_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let zmbk_path = manifest_dir.join("../../testdata/zemberek_step2.zmbk");
    let parity_path = manifest_dir.join("../../testdata/step3_parity.bin");

    let zmbk_bytes = read_aligned(&zmbk_path);
    let pack = ZmbkPack::parse(&zmbk_bytes, true).expect("parse zemberek_step2.zmbk with CRC32");

    // 1. Verify SECT_MORPH_FST binary size gate <= 280 KiB
    let (fst_sec, _) = pack.section_bytes(SECT_MORPH_FST).expect("SECT_MORPH_FST");
    assert!(
        fst_sec.byte_len <= 280 * 1024,
        "SECT_MORPH_FST size {} B exceeds 280 KiB gate",
        fst_sec.byte_len
    );

    let morph_formal = TurkishMorphology::from_pack(&pack, MorphologyConfig::default()).unwrap();
    let morph_informal = TurkishMorphology::from_pack(
        &pack,
        MorphologyConfig {
            use_informal: true,
            ignore_diacritics: false,
            use_unidentified_token_analyzer: true,
        },
    )
    .unwrap();
    let morph_ascii_informal = TurkishMorphology::from_pack(
        &pack,
        MorphologyConfig {
            use_informal: true,
            ignore_diacritics: true,
            use_unidentified_token_analyzer: true,
        },
    )
    .unwrap();
    let modes = [morph_formal, morph_informal, morph_ascii_informal];
    let mode_names = ["Formal", "Informal", "Informal+AsciiTolerant"];

    let fixture = fs::read(&parity_path).expect("read step3_parity.bin");
    let mut r = BinReader::new(&fixture);

    // 2. Verify 100.0% parity across 10,000 sentences x 3 modes (~450,000 token analyses)
    let sent_count = r.u32_be() as usize;
    let full_detail_count = r.u32_be() as usize;
    assert_eq!(sent_count, 10_000);

    let mut sentences: Vec<&str> = Vec::with_capacity(sent_count);
    let mut scratch = AnalyzerScratch::default();
    let mut checked_tokens_total = 0usize;

    for s_idx in 0..sent_count {
        let sent = r.str();
        sentences.push(sent);
        let full_detail = s_idx < full_detail_count;

        for (m_idx, morph) in modes.iter().enumerate() {
            let exp_tok_count = r.u16_be() as usize;
            let got_was = morph.analyze_sentence(sent, None, &mut scratch);
            assert_eq!(
                got_was.len(),
                exp_tok_count,
                "token count mismatch at sent #{s_idx} mode={} sent={sent:?}",
                mode_names[m_idx]
            );
            checked_tokens_total += exp_tok_count;

            for (t_idx, wa) in got_was.iter().enumerate() {
                let exp_ans_count = r.u16_be() as usize;
                let exp_hash = r.u64_be();

                if m_idx < 2 {
                    // Formal & Exact-Informal modes: exact ordered list equality!
                    let mut got_hash = 0xcbf29ce484222325u64;
                    got_hash = fnv1a64_update(got_hash, &wa.input);
                    got_hash = fnv1a64_update(got_hash, &wa.normalized_input);
                    for sa in &wa.analyses {
                        got_hash =
                            fnv1a64_update(got_hash, &sa.format_long(&morph.graph, &morph.fst));
                        got_hash = fnv1a64_update(got_hash, &sa.get_stems().join(","));
                        got_hash =
                            fnv1a64_update(got_hash, &sa.get_lemmas(&morph.graph).join(","));
                    }

                    if full_detail {
                        let exp_in = r.str();
                        let exp_norm = r.str();
                        assert_eq!(wa.input, exp_in);
                        assert_eq!(
                            wa.normalized_input, exp_norm,
                            "norm mismatch at sent #{s_idx} tok #{t_idx} ({:?})",
                            wa.input
                        );
                        let mut exp_longs = Vec::with_capacity(exp_ans_count);
                        let mut exp_stems = Vec::with_capacity(exp_ans_count);
                        let mut exp_lemmas = Vec::with_capacity(exp_ans_count);
                        for _ in 0..exp_ans_count {
                            exp_longs.push(r.str());
                            exp_stems.push(r.str());
                            exp_lemmas.push(r.str());
                        }
                        let got_longs: Vec<String> = wa
                            .analyses
                            .iter()
                            .map(|a| a.format_long(&morph.graph, &morph.fst))
                            .collect();
                        assert_eq!(
                            got_longs, exp_longs,
                            "analysis mismatch at sent #{s_idx} mode={} tok={:?} sent={sent:?}",
                            mode_names[m_idx], wa.input
                        );
                        for (a_i, sa) in wa.analyses.iter().enumerate() {
                            assert_eq!(sa.get_stems().join(","), exp_stems[a_i]);
                            assert_eq!(sa.get_lemmas(&morph.graph).join(","), exp_lemmas[a_i]);
                        }
                    } else if wa.analyses.len() != exp_ans_count || got_hash != exp_hash {
                        let got_longs: Vec<String> = wa
                            .analyses
                            .iter()
                            .map(|a| a.format_long(&morph.graph, &morph.fst))
                            .collect();
                        panic!(
                            "Hash/count mismatch at sent #{s_idx} mode={} tok #{t_idx} {:?} (exp_cnt={exp_ans_count}, got_cnt={}, got={got_longs:?}) in sent={sent:?}",
                            mode_names[m_idx],
                            wa.input,
                            wa.analyses.len()
                        );
                    }
                } else {
                    // Mode 2 (Informal + ASCII-tolerant): Java's `asciiKeys` uses `HashSet<String>`
                    // iteration order for stems of equal prefix length, so analysis set is identical
                    // up to permutation when multiple ASCII-equivalent stems have equal length!
                    if full_detail {
                        let exp_in = r.str();
                        let exp_norm = r.str();
                        assert_eq!(wa.input, exp_in);
                        assert_eq!(wa.normalized_input, exp_norm);
                        let mut exp_tuples: Vec<(String, String, String)> =
                            Vec::with_capacity(exp_ans_count);
                        for _ in 0..exp_ans_count {
                            exp_tuples.push((
                                r.str().to_string(),
                                r.str().to_string(),
                                r.str().to_string(),
                            ));
                        }
                        let mut got_tuples: Vec<(String, String, String)> = wa
                            .analyses
                            .iter()
                            .map(|a| {
                                (
                                    a.format_long(&morph.graph, &morph.fst),
                                    a.get_stems().join(","),
                                    a.get_lemmas(&morph.graph).join(","),
                                )
                            })
                            .collect();
                        exp_tuples.sort();
                        got_tuples.sort();
                        assert_eq!(
                            got_tuples, exp_tuples,
                            "ASCII-tolerant set mismatch at sent #{s_idx} tok={:?} sent={sent:?}",
                            wa.input
                        );
                    } else {
                        assert_eq!(
                            wa.analyses.len(),
                            exp_ans_count,
                            "ASCII-tolerant analysis count mismatch at sent #{s_idx} tok #{t_idx} {:?} in sent={sent:?}",
                            wa.input
                        );
                    }
                }
            }
        }
    }

    // 3. Verify WordGenerator parity
    let gen = morph_formal.word_generator();
    let gen_case_count = r.u32_be() as usize;
    for g_idx in 0..gen_case_count {
        let item_id = r.u32_be();
        let m_len = r.u8() as usize;
        let mut morph_names = Vec::with_capacity(m_len);
        for _ in 0..m_len {
            morph_names.push(r.str());
        }
        let exp_res_cnt = r.u16_be() as usize;
        let mut exp_res = Vec::with_capacity(exp_res_cnt);
        for _ in 0..exp_res_cnt {
            exp_res.push((r.str(), r.str()));
        }
        let got_res = gen.generate_by_names(item_id, &morph_names);
        let got_pairs: Vec<(&str, String)> = got_res
            .iter()
            .map(|gr| {
                (
                    gr.surface.as_str(),
                    gr.analysis.format_long(&morph_formal.graph, &morph_formal.fst),
                )
            })
            .collect();
        let exp_pairs: Vec<(&str, String)> = exp_res
            .iter()
            .map(|&(s, a)| (s, a.to_string()))
            .collect();
        assert_eq!(
            got_pairs, exp_pairs,
            "WordGenerator mismatch at case #{g_idx} item={} morphs={morph_names:?}",
            morph_formal.graph.item(item_id).id_string()
        );
    }

    // 4. Verify InformalAnalysisConverter parity
    let conv = morph_informal.informal_converter();
    let conv_word_count = r.u32_be() as usize;
    for _ in 0..conv_word_count {
        let iw = r.str();
        let wa = morph_informal.analyze_word(iw, None, &mut scratch);
        let exp_cnt = r.u16_be() as usize;
        assert_eq!(wa.analyses.len(), exp_cnt, "informal count mismatch for {iw:?}");
        for sa in &wa.analyses {
            let exp_sa_str = r.str();
            assert_eq!(
                sa.format_long(&morph_informal.graph, &morph_informal.fst),
                exp_sa_str
            );
            let exp_has = r.bool();
            let got_conv = conv.convert(iw, sa);
            assert_eq!(got_conv.is_some(), exp_has, "conv presence mismatch on {iw:?}");
            if exp_has {
                let exp_surf = r.str();
                let exp_conv_long = r.str();
                let gc = got_conv.unwrap();
                assert_eq!(gc.surface, exp_surf, "conv surface mismatch on {iw:?}");
                assert_eq!(
                    gc.analysis
                        .format_long(&morph_informal.graph, &morph_informal.fst),
                    exp_conv_long,
                    "conv analysis mismatch on {iw:?}"
                );
            }
        }
    }

    // 5. Verify WordAnalysisSurfaceFormatter parity
    let all_cases = [
        CaseType::DefaultCase,
        CaseType::LowerCase,
        CaseType::UpperCase,
        CaseType::TitleCase,
        CaseType::UpperCaseRootLowerCaseEnding,
        CaseType::MixedCase,
    ];
    let surf_word_count = r.u32_be() as usize;
    for _ in 0..surf_word_count {
        let sw = r.str();
        let exp_guess = case_from_ordinal(r.u8());
        assert_eq!(
            WordAnalysisSurfaceFormatter::guess_case(sw),
            exp_guess,
            "guess_case mismatch on {sw:?}"
        );
        let wa = morph_formal.analyze_word(sw, None, &mut scratch);
        let exp_cnt = r.u16_be() as usize;
        assert_eq!(wa.analyses.len(), exp_cnt);
        for sa in &wa.analyses {
            let exp_f_none = r.str();
            let exp_f_sq = r.str();
            let exp_f_rq = r.str();
            assert_eq!(
                WordAnalysisSurfaceFormatter::format(sa, &morph_formal.graph, None),
                exp_f_none
            );
            assert_eq!(
                WordAnalysisSurfaceFormatter::format(sa, &morph_formal.graph, Some("'")),
                exp_f_sq
            );
            assert_eq!(
                WordAnalysisSurfaceFormatter::format(sa, &morph_formal.graph, Some("’")),
                exp_f_rq
            );
            for &ct in &all_cases {
                let exp_can = r.bool();
                let exp_fc = r.str();
                assert_eq!(
                    WordAnalysisSurfaceFormatter::can_be_formatted(sa, &morph_formal.graph, ct),
                    exp_can
                );
                assert_eq!(
                    WordAnalysisSurfaceFormatter::format_to_case(
                        sa,
                        &morph_formal.graph,
                        ct,
                        None
                    ),
                    exp_fc,
                    "format_to_case({ct:?}) mismatch on {sw:?}"
                );
            }
        }
    }
    assert_eq!(r.pos, fixture.len(), "unread trailing bytes in step3_parity.bin");

    // 6. Verify DynamicStemTrie user words through full TurkishMorphology!
    let mut dyn_trie = DynamicStemTrie::new(morph_informal.graph.item_count() as u32);
    dyn_trie
        .add_user_word("kankitap", &morph_informal.graph.bridge)
        .unwrap();
    dyn_trie
        .add_user_word("tweetlemek", &morph_informal.graph.bridge)
        .unwrap();

    let wa_noun = morph_formal.analyze_word("kankitabımızdan", Some(&dyn_trie), &mut scratch);
    assert!(
        wa_noun
            .analyses
            .iter()
            .any(|a| a.format_long(&morph_formal.graph, &morph_formal.fst)
                == "[kankitap:Noun] kankitab:Noun+A3sg+ımız:P1pl+dan:Abl"),
        "Dynamic noun voicing inflection failed: {:?}",
        wa_noun.analyses
    );

    let wa_verb = morph_informal.analyze_word("tweetlemiyo", Some(&dyn_trie), &mut scratch);
    assert!(
        wa_verb
            .analyses
            .iter()
            .any(|a| a.format_long(&morph_informal.graph, &morph_informal.fst)
                == "[tweetlemek:Verb] tweetle:Verb+m:Neg+iyo:Prog1_Informal+A3sg"),
        "Dynamic verb informal inflection failed: {:?}",
        wa_verb
            .analyses
            .iter()
            .map(|a| a.format_long(&morph_informal.graph, &morph_informal.fst))
            .collect::<Vec<_>>()
    );

    // 7. Native uncached single-core throughput benchmark (>= 150,000 tokens/sec gate!)
    let t0 = Instant::now();
    let mut formal_tokens = 0usize;
    for &sent in &sentences {
        let was = morph_formal.analyze_sentence(sent, None, &mut scratch);
        formal_tokens += was.len();
    }
    let elapsed_formal = t0.elapsed().as_secs_f64();
    let tok_per_sec_formal = (formal_tokens as f64) / elapsed_formal;

    let t1 = Instant::now();
    let mut ascii_tokens = 0usize;
    for &sent in &sentences {
        let was = morph_ascii_informal.analyze_sentence(sent, None, &mut scratch);
        ascii_tokens += was.len();
    }
    let elapsed_ascii = t1.elapsed().as_secs_f64();
    let tok_per_sec_ascii = (ascii_tokens as f64) / elapsed_ascii;

    eprintln!(
        "[Step 3 Parity OK] FST={} B ({:.1} KiB) | checked_token_analyses={} | Formal={:.0} tok/s ({:.3} s) | AsciiInformal={:.0} tok/s ({:.3} s)",
        fst_sec.byte_len,
        (fst_sec.byte_len as f64) / 1024.0,
        checked_tokens_total,
        tok_per_sec_formal,
        elapsed_formal,
        tok_per_sec_ascii,
        elapsed_ascii
    );

    #[cfg(not(debug_assertions))]
    assert!(
        tok_per_sec_formal >= 150_000.0,
        "Uncached Formal throughput {tok_per_sec_formal:.0} tok/s is below 150,000 tok/s gate"
    );
}
