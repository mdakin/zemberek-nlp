#![no_std]
#![forbid(unsafe_code)]

extern crate alloc;

use alloc::boxed::Box;
use alloc::rc::Rc;
use alloc::string::String;
use alloc::vec::Vec;
use zemberek_core::{PrimaryPos, SecondaryPos};
use zemberek_morph::{AnalysisItem, LexiconGraph, SingleAnalysis, TurkishMorphology, WordAnalysis};
use zemberek_pack::morph_fst::MorphFstView;
use zemberek_pack::mphf::MphfI16WeightTableView;
use zemberek_pack::{PackError, ZmbkPack, SECT_AMBIG_QWT};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub enum DecodeMode {
    #[default]
    Viterbi,
    Beam(usize),
    Greedy,
}

#[derive(Clone, Debug)]
pub struct SentenceWordAnalysis {
    pub best_analysis: SingleAnalysis,
    pub word_analysis: WordAnalysis,
}

#[derive(Clone, Debug)]
pub struct SentenceAnalysis {
    pub sentence: String,
    pub word_analyses: Vec<SentenceWordAnalysis>,
    pub score: f32,
}

const F20_PREFIXES: [&str; 16] = [
    "20:0-", "20:1-", "20:2-", "20:3-", "20:4-", "20:5-", "20:6-", "20:7-", "20:8-", "20:9-",
    "20:10-", "20:11-", "20:12-", "20:13-", "20:14-", "20:15-",
];

const F22_KEYS: [&str; 16] = [
    "22:0", "22:1", "22:2", "22:3", "22:4", "22:5", "22:6", "22:7", "22:8", "22:9", "22:10",
    "22:11", "22:12", "22:13", "22:14", "22:15",
];

const POW31: [i32; 256] = {
    let mut arr = [1i32; 256];
    let mut i = 1;
    while i < 256 {
        arr[i] = arr[i - 1].wrapping_mul(31);
        i += 1;
    }
    arr
};

#[inline]
const fn const_java_hc(bytes: &[u8]) -> i32 {
    let mut h = 0i32;
    let mut i = 0;
    while i < bytes.len() {
        h = h.wrapping_mul(31).wrapping_add(bytes[i] as i32);
        i += 1;
    }
    h
}

const HC_2_COLON: i32 = const_java_hc(b"2:");
const HC_3_COLON: i32 = const_java_hc(b"3:");
const HC_9_COLON: i32 = const_java_hc(b"9:");
const HC_15_COLON: i32 = const_java_hc(b"15:");
const HC_17_COLON: i32 = const_java_hc(b"17:");

fn java_hc_and_u16_len(s: &str) -> (i32, u16) {
    let mut h: i32 = 0;
    let mut len: u16 = 0;
    if s.is_ascii() {
        for &b in s.as_bytes() {
            h = h.wrapping_mul(31).wrapping_add(b as i32);
        }
        len = s.len() as u16;
    } else {
        let mut buf = [0u16; 2];
        for ch in s.chars() {
            if (ch as u32) <= 0xFFFF {
                h = h.wrapping_mul(31).wrapping_add(ch as i32);
                len += 1;
            } else {
                for &u in ch.encode_utf16(&mut buf).iter() {
                    h = h.wrapping_mul(31).wrapping_add(u as i32);
                    len += 1;
                }
            }
        }
    }
    (h, len)
}

#[inline]
fn append_hc(h_prefix: i32, suffix_hc: i32, suffix_u16_len: u16) -> Option<i32> {
    let l = suffix_u16_len as usize;
    if l < POW31.len() {
        Some(h_prefix.wrapping_mul(POW31[l]).wrapping_add(suffix_hc))
    } else {
        None
    }
}

#[inline]
fn append_dash(h: i32) -> i32 {
    h.wrapping_mul(31).wrapping_add(b'-' as i32)
}

fn hash64_str(s: &str) -> u64 {
    let mut h = 0xCBF2_9CE4_8422_2325u64;
    for &b in s.as_bytes() {
        h ^= b as u64;
        h = h.wrapping_mul(0x0000_0100_0000_01B3);
    }
    h
}

#[inline]
fn mix_slot<const BITS: u32>(key: u64) -> usize {
    let mut z = (key ^ (key >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
    (z >> (64 - BITS)) as usize
}

/// Exact structural key for `AnalysisItem::Static` candidates (`<= 14` morphemes).
#[derive(Clone, Copy, PartialEq, Eq)]
struct ExactCandKey {
    item_id: u32,
    surf_flags: u8,
    morph_len: u8,
    morph_ids: [u8; 14],
}

impl ExactCandKey {
    #[inline]
    fn from_analysis(sa: &SingleAnalysis, sec_pos: SecondaryPos) -> Option<(Self, u64)> {
        let AnalysisItem::Static(item_id) = sa.item else {
            return None;
        };
        if sa.morphemes.len() > 14 {
            return None;
        }
        let stem = sa.stem();
        let has_ending = sa.morphemes.iter().skip(1).any(|m| !m.surface.is_empty());
        let is_dot = stem == "." && !has_ending;
        let starts_lt = stem.starts_with('<') || (stem.is_empty() && sa.ending().starts_with('<'));
        let case_bits = if !starts_lt && sec_pos == SecondaryPos::ProperNoun {
            let first_ch = stem
                .chars()
                .next()
                .or_else(|| sa.morphemes.iter().skip(1).find_map(|m| m.surface.chars().next()));
            match first_ch {
                Some(c) if c.is_uppercase() => 1u8,
                Some(c) if c.is_lowercase() => 2u8,
                _ => 0u8,
            }
        } else {
            0u8
        };
        let surf_flags = case_bits | (if is_dot { 4 } else { 0 }) | (if starts_lt { 8 } else { 0 });

        let mut morph_ids = [0u8; 14];
        let mut h = (item_id as u64)
            .wrapping_mul(0x9E37_79B9_7F4A_7C15)
            ^ ((surf_flags as u64) << 40);
        for (i, md) in sa.morphemes.iter().enumerate() {
            morph_ids[i] = md.morpheme_id;
            h = (h ^ (md.morpheme_id as u64)).wrapping_mul(0x0000_0100_0000_01B3);
        }
        Some((
            Self {
                item_id,
                surf_flags,
                morph_len: sa.morphemes.len() as u8,
                morph_ids,
            },
            h,
        ))
    }
}

#[derive(Debug)]
struct IgEntry {
    text: String,
    hc: i32,
    u16_len: u16,
}

#[derive(Debug)]
struct CandTemplate {
    lemma: String,
    lemma_hc: i32,
    lemma_u16_len: u16,
    igs: Vec<IgEntry>,
    r_ig: String,
    r_ig_hc: i32,
    r_ig_u16_len: u16,
    last_group: String,
    last_group_hc: i32,
    last_group_u16_len: u16,
    f2_prefix_hc: Option<i32>,
    f3_prefix_hc: Option<i32>,
    f9_prefix_hc: Option<i32>,
    f17_prefix_hc: Option<i32>,
    f15_part1_hc: Option<i32>,
    uni_non_init: f32,
    uni_init: f32,
    c10b_score: f32,
    c10c_score: f32,
    is_non_begin_verb: bool,
    is_end_or_dot: bool,
    last_group_hash: u64,
    ig_joined_hash: u64,
    r_ig_hash: u64,
    bi_state_hash: u64,
}

#[derive(Clone, Debug)]
struct CandRef {
    tpl: Rc<CandTemplate>,
    uni_score: f32,
}

impl CandRef {
    #[inline]
    fn new(tpl: Rc<CandTemplate>, is_sentence_initial: bool) -> Self {
        let uni_score = if is_sentence_initial {
            tpl.uni_init
        } else {
            tpl.uni_non_init
        };
        Self { tpl, uni_score }
    }
}

#[derive(Clone, Copy, Debug)]
struct HypNode {
    parent_hyp: u32,
    prev_cand_idx: u16,
    curr_cand_idx: u16,
    score: f32,
}

impl HypNode {
    const NONE: u32 = u32::MAX;
}

const CACHE_BITS: u32 = 16; // 65,536 slots (matches Java `TRANSITION_CACHE_BITS = 16`)
const CACHE_SIZE: usize = 1 << CACHE_BITS;
const CACHE_MASK: usize = CACHE_SIZE - 1;

const CAND_CACHE_BITS: u32 = 14; // 16,384 2-way slots
const CAND_CACHE_SIZE: usize = 1 << CAND_CACHE_BITS;
const CAND_CACHE_MASK: usize = CAND_CACHE_SIZE - 1;

const FP_BITMAP_BITS: u32 = 24; // 16,777,216 bits = 2 MiB exact 24-bit pre-filter (~98% reject rate!)
const FP_BITMAP_MASK: u32 = (1 << FP_BITMAP_BITS) - 1;
const FP_BITMAP_U64S: usize = (1 << FP_BITMAP_BITS) / 64;

/// Strictly bounded L1 candidate, transition, and DP scratch space (`0` per-word heap allocations).
pub struct DisambigScratch {
    cand_l1: Vec<Option<(ExactCandKey, Rc<CandTemplate>)>>,
    bi_keys: Vec<u64>,
    bi_vals: Vec<f32>,
    f2_keys: Vec<u64>,
    f2_vals: Vec<f32>,
    f15_keys: Vec<u64>,
    f15_vals: Vec<f32>,
    f17_keys: Vec<u64>,
    f17_vals: Vec<f32>,
    hyp_arena: Vec<HypNode>,
    current_hyps: Vec<u32>,
    next_hyps: Vec<u32>,
    bi_scores: Vec<f32>,
    f2_scores: Vec<f32>,
    best_score_grid: Vec<f32>,
    best_parent_grid: Vec<u32>,
    pp_cands_buf: Vec<CandRef>,
    p_cands_buf: Vec<CandRef>,
    cur_cands_buf: Vec<CandRef>,
}

impl Default for DisambigScratch {
    fn default() -> Self {
        Self::new()
    }
}

impl DisambigScratch {
    pub fn new() -> Self {
        Self {
            cand_l1: alloc::vec![None; CAND_CACHE_SIZE],
            bi_keys: alloc::vec![u64::MAX; CACHE_SIZE],
            bi_vals: alloc::vec![0.0; CACHE_SIZE],
            f2_keys: alloc::vec![u64::MAX; CACHE_SIZE],
            f2_vals: alloc::vec![0.0; CACHE_SIZE],
            f15_keys: alloc::vec![u64::MAX; CACHE_SIZE],
            f15_vals: alloc::vec![0.0; CACHE_SIZE],
            f17_keys: alloc::vec![u64::MAX; CACHE_SIZE],
            f17_vals: alloc::vec![0.0; CACHE_SIZE],
            hyp_arena: Vec::with_capacity(256),
            current_hyps: Vec::with_capacity(64),
            next_hyps: Vec::with_capacity(64),
            bi_scores: Vec::with_capacity(64),
            f2_scores: Vec::with_capacity(64),
            best_score_grid: Vec::with_capacity(64),
            best_parent_grid: Vec::with_capacity(64),
            pp_cands_buf: Vec::with_capacity(16),
            p_cands_buf: Vec::with_capacity(16),
            cur_cands_buf: Vec::with_capacity(16),
        }
    }
}

impl CandTemplate {
    #[allow(clippy::too_many_arguments)]
    fn build(
        lemma: String,
        igs_raw: Vec<String>,
        r_ig: String,
        ig_joined: &str,
        last_group: String,
        uni_init: f32,
        uni_non_init: f32,
        c10b_score: f32,
        c10c_score: f32,
        is_non_begin_verb: bool,
        is_end_or_dot: bool,
    ) -> Rc<Self> {
        let (lemma_hc, lemma_u16_len) = java_hc_and_u16_len(&lemma);
        let (r_ig_hc, r_ig_u16_len) = java_hc_and_u16_len(&r_ig);
        let (last_group_hc, last_group_u16_len) = java_hc_and_u16_len(&last_group);

        let mut igs = Vec::with_capacity(igs_raw.len());
        for text in igs_raw {
            let (hc, u16_len) = java_hc_and_u16_len(&text);
            igs.push(IgEntry { text, hc, u16_len });
        }

        let f2_prefix_hc = append_hc(HC_2_COLON, r_ig_hc, r_ig_u16_len).map(append_dash);
        let f3_prefix_hc = append_hc(HC_3_COLON, r_ig_hc, r_ig_u16_len).map(append_dash);
        let f9_prefix_hc = append_hc(HC_9_COLON, lemma_hc, lemma_u16_len).map(append_dash);
        let f17_prefix_hc =
            append_hc(HC_17_COLON, last_group_hc, last_group_u16_len).map(append_dash);
        let f15_part1_hc =
            append_hc(HC_15_COLON, last_group_hc, last_group_u16_len).map(append_dash);

        let last_group_hash = hash64_str(&last_group);
        let ig_joined_hash = hash64_str(ig_joined);
        let r_ig_hash = hash64_str(&r_ig);
        let bi_state_hash = (r_ig_hash << 2)
            | (if is_non_begin_verb { 2 } else { 0 })
            | (if is_end_or_dot { 1 } else { 0 });

        Rc::new(Self {
            lemma,
            lemma_hc,
            lemma_u16_len,
            igs,
            r_ig,
            r_ig_hc,
            r_ig_u16_len,
            last_group,
            last_group_hc,
            last_group_u16_len,
            f2_prefix_hc,
            f3_prefix_hc,
            f9_prefix_hc,
            f17_prefix_hc,
            f15_part1_hc,
            uni_non_init,
            uni_init,
            c10b_score,
            c10c_score,
            is_non_begin_verb,
            is_end_or_dot,
            last_group_hash,
            ig_joined_hash,
            r_ig_hash,
            bi_state_hash,
        })
    }

    fn from_special(
        tok: &str,
        r_ig_str: &str,
        is_end_or_dot: bool,
        weights: &MphfI16WeightTableView<'_>,
    ) -> Rc<Self> {
        let lemma = String::from(tok);
        let igs_raw = alloc::vec![String::from(tok)];
        let r_ig = String::from(r_ig_str);
        let last_group = String::from(tok);

        let base_uni = compute_base_uni_score(&lemma, &r_ig, &igs_raw, weights);
        let c10b_score = weights.get_str_parts(&["10b:", &lemma]);
        let c10c_score = weights.get_str_parts(&["10c:", &lemma]);

        Self::build(
            lemma,
            igs_raw,
            r_ig,
            tok,
            last_group,
            base_uni,
            base_uni,
            c10b_score,
            c10c_score,
            false,
            is_end_or_dot,
        )
    }

    fn from_analysis(
        sa: &SingleAnalysis,
        graph: &LexiconGraph<'_>,
        fst: &MorphFstView<'_>,
        weights: &MphfI16WeightTableView<'_>,
        proper_weight: f32,
        lower_proper_weight: f32,
    ) -> Rc<Self> {
        let lemma = sa.item.lemma(graph).into_owned();
        let sec_pos = sa.item.secondary_pos(graph);
        let sp = if sec_pos == SecondaryPos::None {
            ""
        } else {
            sec_pos.name()
        };

        let group_count = sa.group_count();
        let mut igs_raw = Vec::with_capacity(group_count);
        for g in 0..group_count {
            let slice = sa.group_slice(g);
            let mut s = String::with_capacity(slice.len() * 5 + sp.len());
            if g == 0 && !sp.is_empty() {
                s.push_str(sp);
            }
            for md in slice {
                s.push_str(fst.morpheme_id_str(md.morpheme_id));
            }
            igs_raw.push(s);
        }

        let ig_joined = if group_count == 1 {
            igs_raw[0].clone()
        } else {
            igs_raw.join("+")
        };
        let mut r_ig = String::with_capacity(lemma.len() + 1 + ig_joined.len());
        r_ig.push_str(&lemma);
        r_ig.push('+');
        r_ig.push_str(&ig_joined);

        let last_group = if group_count > 0 {
            igs_raw[group_count - 1].clone()
        } else {
            String::new()
        };

        let stem = sa.stem();
        let has_ending = sa.morphemes.iter().skip(1).any(|m| !m.surface.is_empty());
        let is_end_or_dot = stem == "." && !has_ending;
        let is_special =
            stem.starts_with('<') || (stem.is_empty() && sa.ending().starts_with('<'));
        let is_proper = !is_special && sec_pos == SecondaryPos::ProperNoun;
        let is_verb = !is_special && sa.item.primary_pos(graph) == PrimaryPos::Verb;

        let (first_upper_proper, first_lower_proper) = if is_proper {
            let fc_opt = stem
                .chars()
                .next()
                .or_else(|| sa.morphemes.iter().skip(1).find_map(|m| m.surface.chars().next()));
            match fc_opt {
                Some(fc) => (fc.is_uppercase(), fc.is_lowercase()),
                None => (false, false),
            }
        } else {
            (false, false)
        };

        let base_uni = compute_base_uni_score(&lemma, &r_ig, &igs_raw, weights);
        let (uni_init, uni_non_init) = if first_upper_proper {
            (
                base_uni + weights.get_str_parts(&["P:INIT_PROPER:", &lemma]),
                base_uni + proper_weight + weights.get_str_parts(&["P:PROPER:", &lemma]),
            )
        } else if first_lower_proper {
            let u = base_uni + lower_proper_weight;
            (u, u)
        } else {
            (base_uni, base_uni)
        };

        let c10b_score = weights.get_str_parts(&["10b:", &lemma]);
        let c10c_score = weights.get_str_parts(&["10c:", &lemma]);

        Self::build(
            lemma,
            igs_raw,
            r_ig,
            &ig_joined,
            last_group,
            uni_init,
            uni_non_init,
            c10b_score,
            c10c_score,
            is_verb,
            is_end_or_dot,
        )
    }
}

fn compute_base_uni_score(
    lemma: &str,
    r_ig: &str,
    igs: &[String],
    weights: &MphfI16WeightTableView<'_>,
) -> f32 {
    let f22_owned;
    let f22: &str = if igs.len() < F22_KEYS.len() {
        F22_KEYS[igs.len()]
    } else {
        f22_owned = alloc::format!("22:{}", igs.len());
        &f22_owned
    };

    let mut base = weights.get_str_parts(&["4:", r_ig])
        + weights.get_str_parts(&["10:", lemma])
        + weights.get_str_parts(&[f22]);

    for (k, ig) in igs.iter().enumerate() {
        if k < F20_PREFIXES.len() {
            base += weights.get_str_parts(&[F20_PREFIXES[k], ig]);
        } else {
            let p20 = alloc::format!("20:{k}-");
            base += weights.get_str_parts(&[&p20, ig]);
        }
    }
    base
}

/// Zero-copy port of `FastPerceptronAmbiguityResolver` backed by `MphfI16WeightTableView`.
#[derive(Clone)]
pub struct FastPerceptronDisambiguator<'a> {
    pub weights: MphfI16WeightTableView<'a>,
    fp_bitmap: Rc<[u64; FP_BITMAP_U64S]>,
    proper_weight: f32,
    lower_proper_weight: f32,
    ends_verb_weight: f32,
    begin_ctx: CandRef,
    end_ctx: CandRef,
}

impl<'a> FastPerceptronDisambiguator<'a> {
    pub fn from_pack(pack: &ZmbkPack<'a>) -> Result<Self, PackError> {
        let (_, bytes) = pack.section_bytes(SECT_AMBIG_QWT)?;
        let weights = MphfI16WeightTableView::from_section_bytes(bytes)?;
        Ok(Self::from_weights(weights))
    }

    pub fn from_weights(weights: MphfI16WeightTableView<'a>) -> Self {
        let mut bm: Box<[u64; FP_BITMAP_U64S]> =
            alloc::vec![0u64; FP_BITMAP_U64S].into_boxed_slice().try_into().unwrap();
        for &fp in weights.fingerprints() {
            let b = (fp & FP_BITMAP_MASK) as usize;
            bm[b >> 6] |= 1u64 << (b & 63);
        }

        let proper_weight = weights.get_str_parts(&["P:PROPER"]);
        let lower_proper_weight = weights.get_str_parts(&["P:LOWER_PROPER"]);
        let ends_verb_weight = weights.get_str_parts(&["P:ENDSVERB"]);
        let begin_ctx = CandRef::new(
            CandTemplate::from_special("<s>", "<s>+<s>", false, &weights),
            false,
        );
        let end_ctx = CandRef::new(
            CandTemplate::from_special("</s>", "</s>+</s>", true, &weights),
            false,
        );
        Self {
            weights,
            fp_bitmap: Rc::from(bm),
            proper_weight,
            lower_proper_weight,
            ends_verb_weight,
            begin_ctx,
            end_ctx,
        }
    }

    #[inline]
    fn fast_get_2(&self, parts: &[&str], prefix_hc: Option<i32>, suf_hc: i32, suf_len: u16) -> f32 {
        if let Some(phc) = prefix_hc {
            if let Some(hc) = append_hc(phc, suf_hc, suf_len) {
                let fp27 = (hc as u32) & 0x07FF_FFFF;
                let b = (fp27 & FP_BITMAP_MASK) as usize;
                if (self.fp_bitmap[b >> 6] & (1u64 << (b & 63))) == 0 {
                    return 0.0;
                }
                return self.weights.get_with_fp27(parts, fp27);
            }
        }
        self.weights.get_str_parts(parts)
    }

    pub fn disambiguate(
        &self,
        morph: &TurkishMorphology<'_>,
        sentence: &str,
        word_analyses: Vec<WordAnalysis>,
        mode: DecodeMode,
        scratch: &mut DisambigScratch,
    ) -> SentenceAnalysis {
        if word_analyses.is_empty() {
            return SentenceAnalysis {
                sentence: String::from(sentence),
                word_analyses: Vec::new(),
                score: 0.0,
            };
        }
        let (chosen_indices, score) = self.best_indices(morph, &word_analyses, mode, scratch);

        let mut out = Vec::with_capacity(word_analyses.len());
        for (wa, idx) in word_analyses.into_iter().zip(chosen_indices) {
            let best = if wa.analyses.is_empty() {
                SingleAnalysis::unknown(&wa.input)
            } else {
                wa.analyses[idx].clone()
            };
            out.push(SentenceWordAnalysis {
                best_analysis: best,
                word_analysis: wa,
            });
        }

        SentenceAnalysis {
            sentence: String::from(sentence),
            word_analyses: out,
            score,
        }
    }

    pub fn best_indices(
        &self,
        morph: &TurkishMorphology<'_>,
        word_analyses: &[WordAnalysis],
        mode: DecodeMode,
        scratch: &mut DisambigScratch,
    ) -> (Vec<usize>, f32) {
        if word_analyses.is_empty() {
            return (Vec::new(), 0.0);
        }
        match mode {
            DecodeMode::Greedy => self.best_path_greedy(morph, word_analyses, scratch),
            DecodeMode::Viterbi => self.best_path_dp(morph, word_analyses, None, scratch),
            DecodeMode::Beam(b) => self.best_path_dp(
                morph,
                word_analyses,
                if b > 0 { Some(b) } else { None },
                scratch,
            ),
        }
    }

    #[inline]
    fn get_or_build_cand(
        &self,
        morph: &TurkishMorphology<'_>,
        sa: &SingleAnalysis,
        is_sentence_initial: bool,
        scratch: &mut DisambigScratch,
    ) -> CandRef {
        let sec_pos = sa.item.secondary_pos(&morph.graph);
        if let Some((key, h)) = ExactCandKey::from_analysis(sa, sec_pos) {
            let slot = mix_slot::<CAND_CACHE_BITS>(h);
            if let Some((ref k1, ref tpl1)) = scratch.cand_l1[slot] {
                if *k1 == key {
                    return CandRef::new(Rc::clone(tpl1), is_sentence_initial);
                }
            }
            let slot2 = (slot + 1) & CAND_CACHE_MASK;
            if let Some((ref k2, ref tpl2)) = scratch.cand_l1[slot2] {
                if *k2 == key {
                    return CandRef::new(Rc::clone(tpl2), is_sentence_initial);
                }
            }
            let created = CandTemplate::from_analysis(
                sa,
                &morph.graph,
                &morph.fst,
                &self.weights,
                self.proper_weight,
                self.lower_proper_weight,
            );
            scratch.cand_l1[slot2] = scratch.cand_l1[slot].take();
            scratch.cand_l1[slot] = Some((key, Rc::clone(&created)));
            return CandRef::new(created, is_sentence_initial);
        }

        let created = CandTemplate::from_analysis(
            sa,
            &morph.graph,
            &morph.fst,
            &self.weights,
            self.proper_weight,
            self.lower_proper_weight,
        );
        CandRef::new(created, is_sentence_initial)
    }

    fn fill_word_candidates(
        &self,
        morph: &TurkishMorphology<'_>,
        wa: &WordAnalysis,
        is_sentence_initial: bool,
        scratch: &mut DisambigScratch,
    ) {
        scratch.cur_cands_buf.clear();
        if wa.analyses.is_empty() {
            let unk = SingleAnalysis::unknown(&wa.input);
            let c = self.get_or_build_cand(morph, &unk, is_sentence_initial, scratch);
            scratch.cur_cands_buf.push(c);
            return;
        }
        for sa in &wa.analyses {
            let c = self.get_or_build_cand(morph, sa, is_sentence_initial, scratch);
            scratch.cur_cands_buf.push(c);
        }
    }

    fn best_path_dp(
        &self,
        morph: &TurkishMorphology<'_>,
        sentence: &[WordAnalysis],
        beam_opt: Option<usize>,
        scratch: &mut DisambigScratch,
    ) -> (Vec<usize>, f32) {
        let sent_len = sentence.len();

        scratch.hyp_arena.clear();
        scratch.hyp_arena.push(HypNode {
            parent_hyp: HypNode::NONE,
            prev_cand_idx: 0,
            curr_cand_idx: 0,
            score: 0.0,
        });

        scratch.current_hyps.clear();
        scratch.current_hyps.push(0);

        scratch.pp_cands_buf.clear();
        scratch.pp_cands_buf.push(self.begin_ctx.clone());
        scratch.p_cands_buf.clear();
        scratch.p_cands_buf.push(self.begin_ctx.clone());

        for (word_idx, wa) in sentence.iter().enumerate() {
            self.fill_word_candidates(morph, wa, word_idx == 0, scratch);
            let cands = core::mem::take(&mut scratch.cur_cands_buf);
            let mut pp_vec = core::mem::take(&mut scratch.pp_cands_buf);
            let p_vec = core::mem::take(&mut scratch.p_cands_buf);

            let k_len = cands.len();
            let pp_ref: &[CandRef] = &pp_vec;
            let p_ref: &[CandRef] = &p_vec;

            if scratch.current_hyps.len() == 1 {
                let h_id = scratch.current_hyps[0];
                let h = scratch.hyp_arena[h_id as usize];
                let w1 = &pp_ref[h.prev_cand_idx as usize].tpl;
                let w2 = &p_ref[h.curr_cand_idx as usize].tpl;
                let w2_idx = h.curr_cand_idx;

                if k_len == 1 {
                    let cand = &cands[0];
                    let total_score = h.score
                        + cand.uni_score
                        + self.cached_bigram(scratch, w2, &cand.tpl)
                        + self.cached_f2(scratch, w1, &cand.tpl)
                        + self.cached_f15(scratch, w1, w2, &cand.tpl);
                    let next_id = scratch.hyp_arena.len() as u32;
                    scratch.hyp_arena.push(HypNode {
                        parent_hyp: h_id,
                        prev_cand_idx: w2_idx,
                        curr_cand_idx: 0,
                        score: total_score,
                    });
                    scratch.current_hyps[0] = next_id;
                } else {
                    scratch.next_hyps.clear();
                    for (k, cand) in cands.iter().enumerate() {
                        let total_score = h.score
                            + cand.uni_score
                            + self.cached_bigram(scratch, w2, &cand.tpl)
                            + self.cached_f2(scratch, w1, &cand.tpl)
                            + self.cached_f15(scratch, w1, w2, &cand.tpl);
                        let next_id = scratch.hyp_arena.len() as u32;
                        scratch.hyp_arena.push(HypNode {
                            parent_hyp: h_id,
                            prev_cand_idx: w2_idx,
                            curr_cand_idx: k as u16,
                            score: total_score,
                        });
                        scratch.next_hyps.push(next_id);
                    }
                    if let Some(beam_sz) = beam_opt {
                        if scratch.next_hyps.len() > beam_sz {
                            let arena_ref = &scratch.hyp_arena;
                            scratch.next_hyps.sort_by(|&a, &b| {
                                arena_ref[b as usize]
                                    .score
                                    .total_cmp(&arena_ref[a as usize].score)
                            });
                            scratch.next_hyps.truncate(beam_sz);
                        }
                    }
                    core::mem::swap(&mut scratch.current_hyps, &mut scratch.next_hyps);
                }
                pp_vec.clear();
                scratch.cur_cands_buf = pp_vec;
                scratch.pp_cands_buf = p_vec;
                scratch.p_cands_buf = cands;
                continue;
            }

            let pp_len = pp_ref.len();
            let p_len = p_ref.len();

            if p_len == 1 && k_len == 1 {
                let w2 = &p_ref[0].tpl;
                let cand = &cands[0];
                let base_score = cand.uni_score + self.cached_bigram(scratch, w2, &cand.tpl);
                let mut best_parent = scratch.current_hyps[0];
                let mut best_score = -f32::MAX;
                for hyp_i in 0..scratch.current_hyps.len() {
                    let h_id = scratch.current_hyps[hyp_i];
                    let h = scratch.hyp_arena[h_id as usize];
                    let w1 = &pp_ref[h.prev_cand_idx as usize].tpl;
                    let s = h.score
                        + base_score
                        + self.cached_f2(scratch, w1, &cand.tpl)
                        + self.cached_f15(scratch, w1, w2, &cand.tpl);
                    if s > best_score {
                        best_score = s;
                        best_parent = h_id;
                    }
                }
                let next_id = scratch.hyp_arena.len() as u32;
                scratch.hyp_arena.push(HypNode {
                    parent_hyp: best_parent,
                    prev_cand_idx: 0,
                    curr_cand_idx: 0,
                    score: best_score,
                });
                scratch.current_hyps.clear();
                scratch.current_hyps.push(next_id);
                pp_vec.clear();
                scratch.cur_cands_buf = pp_vec;
                scratch.pp_cands_buf = p_vec;
                scratch.p_cands_buf = cands;
                continue;
            }

            let pk = p_len * k_len;
            scratch.bi_scores.clear();
            scratch.bi_scores.resize(pk, 0.0);
            for j in 0..p_len {
                let p = &p_ref[j].tpl;
                if let Some(same_j) =
                    (0..j).find(|&j0| p_ref[j0].tpl.bi_state_hash == p.bi_state_hash)
                {
                    scratch
                        .bi_scores
                        .copy_within(same_j * k_len..(same_j + 1) * k_len, j * k_len);
                    continue;
                }
                for k in 0..k_len {
                    let ct = &cands[k].tpl;
                    let v = if let Some(same_k) =
                        (0..k).find(|&k0| cands[k0].tpl.bi_state_hash == ct.bi_state_hash)
                    {
                        scratch.bi_scores[j * k_len + same_k]
                    } else {
                        self.cached_bigram(scratch, p, ct)
                    };
                    scratch.bi_scores[j * k_len + k] = v;
                }
            }

            let ppk = pp_len * k_len;
            scratch.f2_scores.clear();
            scratch.f2_scores.resize(ppk, 0.0);
            for pp in 0..pp_len {
                let prev_prev = &pp_ref[pp].tpl;
                if let Some(same_pp) =
                    (0..pp).find(|&pp0| pp_ref[pp0].tpl.r_ig_hash == prev_prev.r_ig_hash)
                {
                    scratch
                        .f2_scores
                        .copy_within(same_pp * k_len..(same_pp + 1) * k_len, pp * k_len);
                    continue;
                }
                for k in 0..k_len {
                    let ct = &cands[k].tpl;
                    let v = if let Some(same_k) =
                        (0..k).find(|&k0| cands[k0].tpl.r_ig_hash == ct.r_ig_hash)
                    {
                        scratch.f2_scores[pp * k_len + same_k]
                    } else {
                        self.cached_f2(scratch, prev_prev, ct)
                    };
                    scratch.f2_scores[pp * k_len + k] = v;
                }
            }

            scratch.best_score_grid.clear();
            scratch.best_score_grid.resize(pk, -f32::MAX);
            scratch.best_parent_grid.clear();
            scratch.best_parent_grid.resize(pk, HypNode::NONE);

            for hyp_i in 0..scratch.current_hyps.len() {
                let h_id = scratch.current_hyps[hyp_i];
                let h = scratch.hyp_arena[h_id as usize];
                let prev_idx = h.curr_cand_idx as usize;
                let pp_idx = h.prev_cand_idx as usize;
                let w1 = &pp_ref[pp_idx].tpl;
                let w2 = &p_ref[prev_idx].tpl;
                let grid_off = prev_idx * k_len;
                let f2_off = pp_idx * k_len;

                for k in 0..k_len {
                    let cand = &cands[k];
                    let bi_val = scratch.bi_scores[grid_off + k];
                    let f2_val = scratch.f2_scores[f2_off + k];
                    let f15_val = self.cached_f15(scratch, w1, w2, &cand.tpl);
                    let total_score = h.score + cand.uni_score + bi_val + f2_val + f15_val;
                    let idx = grid_off + k;
                    if total_score > scratch.best_score_grid[idx] {
                        scratch.best_score_grid[idx] = total_score;
                        scratch.best_parent_grid[idx] = h_id;
                    }
                }
            }

            scratch.next_hyps.clear();
            for j in 0..p_len {
                let grid_off = j * k_len;
                for k in 0..k_len {
                    let idx = grid_off + k;
                    let parent = scratch.best_parent_grid[idx];
                    if parent != HypNode::NONE {
                        let next_id = scratch.hyp_arena.len() as u32;
                        scratch.hyp_arena.push(HypNode {
                            parent_hyp: parent,
                            prev_cand_idx: j as u16,
                            curr_cand_idx: k as u16,
                            score: scratch.best_score_grid[idx],
                        });
                        scratch.next_hyps.push(next_id);
                    }
                }
            }

            if let Some(beam_sz) = beam_opt {
                if scratch.next_hyps.len() > beam_sz {
                    let arena_ref = &scratch.hyp_arena;
                    scratch.next_hyps.sort_by(|&a, &b| {
                        arena_ref[b as usize]
                            .score
                            .total_cmp(&arena_ref[a as usize].score)
                    });
                    scratch.next_hyps.truncate(beam_sz);
                }
            }

            core::mem::swap(&mut scratch.current_hyps, &mut scratch.next_hyps);
            pp_vec.clear();
            scratch.cur_cands_buf = pp_vec;
            scratch.pp_cands_buf = p_vec;
            scratch.p_cands_buf = cands;
        }

        let pp_vec = core::mem::take(&mut scratch.pp_cands_buf);
        let p_vec = core::mem::take(&mut scratch.p_cands_buf);
        let pp_ref: &[CandRef] = &pp_vec;
        let p_ref: &[CandRef] = &p_vec;
        let end_uni = self.end_ctx.uni_score;
        let end_tpl = &self.end_ctx.tpl;

        let (best_hyp_id, best_total_score) = if scratch.current_hyps.len() == 1 {
            let h_id = scratch.current_hyps[0];
            let h = scratch.hyp_arena[h_id as usize];
            let w1 = &pp_ref[h.prev_cand_idx as usize].tpl;
            let w2 = &p_ref[h.curr_cand_idx as usize].tpl;
            let s = h.score
                + (end_uni
                    + self.cached_bigram(scratch, w2, end_tpl)
                    + self.cached_f2(scratch, w1, end_tpl)
                    + self.cached_f15(scratch, w1, w2, end_tpl));
            (h_id, s)
        } else {
            let p_len = p_ref.len();
            let pp_len = pp_ref.len();
            scratch.bi_scores.clear();
            scratch.bi_scores.resize(p_len, 0.0);
            for j in 0..p_len {
                let v = if let Some(same_j) = (0..j)
                    .find(|&j0| p_ref[j0].tpl.bi_state_hash == p_ref[j].tpl.bi_state_hash)
                {
                    scratch.bi_scores[same_j]
                } else {
                    self.cached_bigram(scratch, &p_ref[j].tpl, end_tpl)
                };
                scratch.bi_scores[j] = v;
            }
            scratch.f2_scores.clear();
            scratch.f2_scores.resize(pp_len, 0.0);
            for pp in 0..pp_len {
                let v = if let Some(same_pp) =
                    (0..pp).find(|&pp0| pp_ref[pp0].tpl.r_ig_hash == pp_ref[pp].tpl.r_ig_hash)
                {
                    scratch.f2_scores[same_pp]
                } else {
                    self.cached_f2(scratch, &pp_ref[pp].tpl, end_tpl)
                };
                scratch.f2_scores[pp] = v;
            }

            let mut best_id = scratch.current_hyps[0];
            let mut best_s = -f32::MAX;
            for i in 0..scratch.current_hyps.len() {
                let h_id = scratch.current_hyps[i];
                let h = scratch.hyp_arena[h_id as usize];
                let w1 = &pp_ref[h.prev_cand_idx as usize].tpl;
                let w2 = &p_ref[h.curr_cand_idx as usize].tpl;
                let eb = scratch.bi_scores[h.curr_cand_idx as usize];
                let ef2 = scratch.f2_scores[h.prev_cand_idx as usize];
                let ef15 = self.cached_f15(scratch, w1, w2, end_tpl);
                let s = h.score + (end_uni + eb + ef2 + ef15);
                if i == 0 || s > best_s {
                    best_s = s;
                    best_id = h_id;
                }
            }
            (best_id, best_s)
        };

        let mut chosen = alloc::vec![0usize; sent_len];
        let mut cur = best_hyp_id;
        let mut pos = sent_len;
        while cur != HypNode::NONE
            && scratch.hyp_arena[cur as usize].parent_hyp != HypNode::NONE
            && pos > 0
        {
            pos -= 1;
            chosen[pos] = scratch.hyp_arena[cur as usize].curr_cand_idx as usize;
            cur = scratch.hyp_arena[cur as usize].parent_hyp;
        }
        scratch.pp_cands_buf = pp_vec;
        scratch.p_cands_buf = p_vec;
        (chosen, best_total_score)
    }

    fn best_path_greedy(
        &self,
        morph: &TurkishMorphology<'_>,
        sentence: &[WordAnalysis],
        scratch: &mut DisambigScratch,
    ) -> (Vec<usize>, f32) {
        let mut chosen = Vec::with_capacity(sentence.len());
        let mut total_score = 0.0f32;
        let mut prev_prev: Option<CandRef> = None;
        let mut prev: Option<CandRef> = None;

        for (word_idx, wa) in sentence.iter().enumerate() {
            self.fill_word_candidates(morph, wa, word_idx == 0, scratch);
            let mut cands = core::mem::take(&mut scratch.cur_cands_buf);
            let w1 = &prev_prev.as_ref().unwrap_or(&self.begin_ctx).tpl;
            let w2 = &prev.as_ref().unwrap_or(&self.begin_ctx).tpl;

            let mut best_k = 0usize;
            let mut best_step = -f32::MAX;
            for (k, cand) in cands.iter().enumerate() {
                let step = cand.uni_score
                    + self.cached_bigram(scratch, w2, &cand.tpl)
                    + self.cached_f2(scratch, w1, &cand.tpl)
                    + self.cached_f15(scratch, w1, w2, &cand.tpl);
                if k == 0 || step > best_step {
                    best_step = step;
                    best_k = k;
                }
            }
            total_score += best_step;
            chosen.push(best_k);
            prev_prev = prev;
            prev = Some(cands[best_k].clone());
            cands.clear();
            scratch.cur_cands_buf = cands;
        }

        let w1 = &prev_prev.as_ref().unwrap_or(&self.begin_ctx).tpl;
        let w2 = &prev.as_ref().unwrap_or(&self.begin_ctx).tpl;
        let end_tpl = &self.end_ctx.tpl;
        total_score += self.end_ctx.uni_score
            + self.cached_bigram(scratch, w2, end_tpl)
            + self.cached_f2(scratch, w1, end_tpl)
            + self.cached_f15(scratch, w1, w2, end_tpl);

        (chosen, total_score)
    }

    fn cached_f17(&self, scratch: &mut DisambigScratch, w2: &CandTemplate, w3: &CandTemplate) -> f32 {
        let key = w2
            .last_group_hash
            .rotate_left(31)
            .wrapping_mul(0x9E37_79B9_7F4A_7C15)
            ^ w3.ig_joined_hash;
        let slot = mix_slot::<CACHE_BITS>(key);
        if key != u64::MAX && scratch.f17_keys[slot] == key {
            return scratch.f17_vals[slot];
        }
        let slot2 = (slot + 1) & CACHE_MASK;
        if key != u64::MAX && scratch.f17_keys[slot2] == key {
            return scratch.f17_vals[slot2];
        }
        let mut s17 = 0.0f32;
        for ig in &w3.igs {
            s17 += self.fast_get_2(
                &["17:", &w2.last_group, "-", &ig.text],
                w2.f17_prefix_hc,
                ig.hc,
                ig.u16_len,
            );
        }
        if key != u64::MAX {
            scratch.f17_keys[slot2] = scratch.f17_keys[slot];
            scratch.f17_vals[slot2] = scratch.f17_vals[slot];
            scratch.f17_keys[slot] = key;
            scratch.f17_vals[slot] = s17;
        }
        s17
    }

    fn cached_bigram(
        &self,
        scratch: &mut DisambigScratch,
        w2: &CandTemplate,
        w3: &CandTemplate,
    ) -> f32 {
        let key = w2
            .bi_state_hash
            .rotate_left(29)
            .wrapping_mul(0x9E37_79B9_7F4A_7C15)
            ^ w3.bi_state_hash;
        let slot = mix_slot::<CACHE_BITS>(key);
        if key != u64::MAX && scratch.bi_keys[slot] == key {
            return scratch.bi_vals[slot];
        }
        let slot2 = (slot + 1) & CACHE_MASK;
        if key != u64::MAX && scratch.bi_keys[slot2] == key {
            return scratch.bi_vals[slot2];
        }
        let s3 = self.fast_get_2(
            &["3:", &w2.r_ig, "-", &w3.r_ig],
            w2.f3_prefix_hc,
            w3.r_ig_hc,
            w3.r_ig_u16_len,
        );
        let s9 = self.fast_get_2(
            &["9:", &w2.lemma, "-", &w3.lemma],
            w2.f9_prefix_hc,
            w3.lemma_hc,
            w3.lemma_u16_len,
        );
        let mut score = s3 + s9 + w2.c10b_score + self.cached_f17(scratch, w2, w3);
        if w3.is_end_or_dot && w2.is_non_begin_verb {
            score += self.ends_verb_weight;
        }
        if key != u64::MAX {
            scratch.bi_keys[slot2] = scratch.bi_keys[slot];
            scratch.bi_vals[slot2] = scratch.bi_vals[slot];
            scratch.bi_keys[slot] = key;
            scratch.bi_vals[slot] = score;
        }
        score
    }

    fn cached_f2(
        &self,
        scratch: &mut DisambigScratch,
        w1: &CandTemplate,
        w3: &CandTemplate,
    ) -> f32 {
        let key = w1
            .r_ig_hash
            .rotate_left(27)
            .wrapping_mul(0x9E37_79B9_7F4A_7C15)
            ^ w3.r_ig_hash;
        let slot = mix_slot::<CACHE_BITS>(key);
        if key != u64::MAX && scratch.f2_keys[slot] == key {
            return scratch.f2_vals[slot];
        }
        let slot2 = (slot + 1) & CACHE_MASK;
        if key != u64::MAX && scratch.f2_keys[slot2] == key {
            return scratch.f2_vals[slot2];
        }
        let s2 = self.fast_get_2(
            &["2:", &w1.r_ig, "-", &w3.r_ig],
            w1.f2_prefix_hc,
            w3.r_ig_hc,
            w3.r_ig_u16_len,
        );
        let score = s2 + w1.c10c_score;
        if key != u64::MAX {
            scratch.f2_keys[slot2] = scratch.f2_keys[slot];
            scratch.f2_vals[slot2] = scratch.f2_vals[slot];
            scratch.f2_keys[slot] = key;
            scratch.f2_vals[slot] = score;
        }
        score
    }

    fn cached_f15(
        &self,
        scratch: &mut DisambigScratch,
        w1: &CandTemplate,
        w2: &CandTemplate,
        w3: &CandTemplate,
    ) -> f32 {
        let key = w1
            .last_group_hash
            .wrapping_mul(0x9E37_79B9_7F4A_7C15)
            .rotate_left(23)
            ^ w2.last_group_hash
                .wrapping_mul(0xBF58_476D_1CE4_E5B9)
                .rotate_left(13)
            ^ w3.ig_joined_hash;
        let slot = mix_slot::<CACHE_BITS>(key);
        if key != u64::MAX && scratch.f15_keys[slot] == key {
            return scratch.f15_vals[slot];
        }
        let slot2 = (slot + 1) & CACHE_MASK;
        if key != u64::MAX && scratch.f15_keys[slot2] == key {
            return scratch.f15_vals[slot2];
        }

        let prefix12_hc = w1.f15_part1_hc.and_then(|p1| {
            append_hc(p1, w2.last_group_hc, w2.last_group_u16_len).map(append_dash)
        });

        let mut score = 0.0f32;
        for ig in &w3.igs {
            score += self.fast_get_2(
                &["15:", &w1.last_group, "-", &w2.last_group, "-", &ig.text],
                prefix12_hc,
                ig.hc,
                ig.u16_len,
            );
        }
        if key != u64::MAX {
            scratch.f15_keys[slot2] = scratch.f15_keys[slot];
            scratch.f15_vals[slot2] = scratch.f15_vals[slot];
            scratch.f15_keys[slot] = key;
            scratch.f15_vals[slot] = score;
        }
        score
    }
}
