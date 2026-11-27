#![no_std]
#![forbid(unsafe_code)]

extern crate alloc;

pub mod candidate_gen;
pub mod knowledge;
pub mod morph_support;
pub mod shorthand;
pub mod single_rewrite;
pub mod split_merge;
pub mod types;

use alloc::string::String;
use alloc::vec::Vec;
use core::cmp::Ordering;
use zemberek_morph::{AnalyzerScratch, DynamicStemTrie};
use zemberek_pack::mphf::QuantizedWeightTableView;
use zemberek_pack::norm::NormPerceptronView;
use zemberek_pack::{PackError, ZmbkPack, SECT_NORM_PERCEPTRON};
use zemberek_tok::{reconstruct_text, TurkishSentenceExtractor};
use crate::candidate_gen::{
    advance_lexical_history, is_standalone_question_clitic, next_lexical_word_or_null,
    SpanCandidateGenerator,
};
pub use crate::types::{
    classify_issue_kind, IssueKind, SentenceLattice, SpanArc, SpanIssue,
};
use crate::types::{
    clamp_lm, has_ascii_ambiguous_chars, has_explicit_turkish_diacritics,
    is_sentence_boundary_punct, u16_len, SRC_DEASCIIFIER, SRC_FORMAL_MORPH, SRC_INFORMAL_EXACT,
};

pub const DENSE_DIM: usize = 20;
const BEAM_WIDTH: usize = 4;

// Continuous feature indices
const F_SMOOTH_FWD2: usize = 0;
const F_SMOOTH_RIGHT2: usize = 1;
const F_SMOOTH_INTERNAL: usize = 2;
const F_SMOOTH_SPAN_LLR: usize = 3;
const F_CLEAN_FWD3: usize = 4;
const F_CLEAN_RIGHT3: usize = 5;
const F_CLEAN_SPAN_LLR: usize = 6;
const F_PARALLEL_PROB: usize = 7;
const F_PARALLEL_LOG_CNT: usize = 8;
const F_SUFFIX_PRECISION: usize = 9;
const F_EDIT_COST: usize = 10;
const F_DIACRITIC_DIFFS: usize = 11;
const F_IS_IDENTITY_LOWER: usize = 12;
const F_IS_REGULAR_MORPH: usize = 13;
const F_CHAR_PHONOTACTICS: usize = 14;
const F_RESERVED_ZERO: usize = 15;
const F_ASCII_SENT_RATIO: usize = 16;
const F_INFORMAL_EXACT_VS_STRIP: usize = 17;
const F_CLITIC_SKIP_LLR: usize = 18;
const F_QUESTION_VOWEL_HARMONY: usize = 19;

// Hashed sparse feature template IDs
const T_LEX_LOWER_PAIR: i32 = 1;
const T_LEX_SURF_PAIR: i32 = 2;
const T_ID_OOV_MORPH_SRC: i32 = 3;
const T_SRC_ASCII_DIAC: i32 = 4;
const T_RIGHT_LEX_BIGRAM: i32 = 5;
const T_LEX_PAIR_NEXT: i32 = 6;
const T_PREV_LEX_PAIR: i32 = 7;
const T_SPLIT_TAIL_HEAD_NEXT: i32 = 8;
const T_SPLIT_TAIL_QMARK: i32 = 9;
const T_MERGE_RAW_NEXT: i32 = 10;
const T_MERGE_PREV_NORM: i32 = 11;
const T_SUBJ_VERB_AGREE: i32 = 12;
const T_CASING_TRANSITION: i32 = 13;
const T_APOSTROPHE_ADD: i32 = 14;
const T_MORPH_NEXT_SHAPE: i32 = 15;
const T_SPLIT_TAIL_HEAD: i32 = 16;
const T_APOSTROPHE_DROP: i32 = 17;
const T_MERGE_SRC_PROP: i32 = 18;
const T_MERGE_MORPH_NEXT: i32 = 19;
const T_TRANS_LEX_BIGRAM: i32 = 20;
const T_TRANS_MORPH_BIGRAM: i32 = 21;
const T_TRANS_WORD_TAG: i32 = 22;
const T_TRANS_TAG_WORD: i32 = 23;
const T_POLARITY_MATCH: i32 = 24;
const T_SRC_LEN_COST_GAIN: i32 = 25;
const T_SRC_PHONO_GAIN: i32 = 26;
const T_SRC_RAW_CLEAN_ATTEST: i32 = 27;
const T_PRON_LOC_RIGHT: i32 = 28;
const T_TRANS_PRON_LOC_LEFT: i32 = 29;
const T_TRANS_QUESTION_HARMONY: i32 = 30;

#[derive(Clone, Copy)]
struct BackPtr<'b> {
    arc_start: usize,
    arc_idx: usize,
    prev_arena_idx: Option<usize>,
    prev2_lex: Option<&'b str>,
    prev1_lex: Option<&'b str>,
    prev_morph_tag: i16,
    score: f32,
}

/// Zero-copy `SmoothLm`-free Joint Semi-Markov Averaged Perceptron Turkish normalizer
/// (`zemberek.normalization.v2.SpanLatticePerceptronNormalizer`).
pub struct SpanLatticePerceptronNormalizer<'a> {
    pub generator: SpanCandidateGenerator<'a>,
    pub dense_weights: &'a [f32; DENSE_DIM],
    pub sparse_weights: QuantizedWeightTableView<'a>,
}

impl<'a> SpanLatticePerceptronNormalizer<'a> {
    pub fn from_pack(
        pack: &ZmbkPack<'a>,
        dynamic: Option<&DynamicStemTrie>,
    ) -> Result<Self, PackError> {
        let generator = SpanCandidateGenerator::from_pack(pack, dynamic)?;
        let (_, perc_bytes) = pack.section_bytes(SECT_NORM_PERCEPTRON)?;
        let perc = NormPerceptronView::from_section_bytes(perc_bytes)?;
        Ok(Self {
            generator,
            dense_weights: perc.dense_weights,
            sparse_weights: perc.sparse_weights,
        })
    }

    pub fn rebuild_dynamic_caches(&mut self, dynamic: Option<&DynamicStemTrie>) {
        self.generator.rebuild_dynamic_caches(dynamic);
    }

    pub fn normalize(
        &self,
        raw_sentence: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> String {
        if raw_sentence.is_empty() {
            return String::new();
        }
        let lattice = self.generator.build_lattice(raw_sentence, dynamic, scratch);
        if lattice.tokens.is_empty() {
            return String::from(raw_sentence);
        }
        let decoded = self.decode_lattice(&lattice, dynamic, scratch);
        reconstruct_text(&lattice.tokens, &decoded)
    }

    pub fn decode_lattice(
        &self,
        lattice: &SentenceLattice,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Vec<SpanArc> {
        if lattice.tokens.is_empty() {
            return Vec::new();
        }
        let (path_refs, _, _) = self.decode_viterbi_internal(lattice, dynamic, scratch);
        path_refs.into_iter().cloned().collect()
    }

    /// Inspects a single sentence and returns both the normalized text and span-level browser
    /// diagnostics (`Vec<SpanIssue>`) carrying UTF-8 and UTF-16 offsets + top-3 candidate replacements.
    pub fn check_sentence(
        &self,
        raw_sentence: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> (String, Vec<SpanIssue>) {
        if raw_sentence.is_empty() {
            return (String::new(), Vec::new());
        }
        let lattice = self.generator.build_lattice(raw_sentence, dynamic, scratch);
        if lattice.tokens.is_empty() {
            return (String::from(raw_sentence), Vec::new());
        }
        let (path_refs, histories, _) = self.decode_viterbi_internal(&lattice, dynamic, scratch);
        let normalized = reconstruct_text(&lattice.tokens, &path_refs);

        let mut issues = Vec::new();
        for (step_idx, &arc) in path_refs.iter().enumerate() {
            let first_tok = &lattice.tokens[arc.start_idx];
            let last_tok = &lattice.tokens[arc.end_idx - 1];
            let raw_slice = &raw_sentence[first_tok.utf8_start..last_tok.utf8_end];
            if arc.norm_surface == raw_slice {
                continue;
            }

            let (p2, p1, ptag) = histories[step_idx];
            let mut alts: Vec<(f32, &str)> = Vec::with_capacity(8);
            for cand_arc in &lattice.outgoing[arc.start_idx] {
                if cand_arc.end_idx == arc.end_idx {
                    let s = self.score_arc(&lattice, p2, p1, ptag, cand_arc, dynamic, scratch);
                    alts.push((s, cand_arc.norm_surface.as_str()));
                }
            }
            alts.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(Ordering::Equal));

            let mut suggestions = Vec::with_capacity(3);
            suggestions.push(arc.norm_surface.clone());
            for (_, surf) in alts {
                if suggestions.len() >= 3 {
                    break;
                }
                if surf != raw_slice && !suggestions.iter().any(|x| x == surf) {
                    suggestions.push(String::from(surf));
                }
            }

            issues.push(SpanIssue {
                start_tok: arc.start_idx,
                end_tok: arc.end_idx,
                utf8_start: first_tok.utf8_start,
                utf8_end: last_tok.utf8_end,
                utf16_start: first_tok.utf16_start,
                utf16_end: last_tok.utf16_end,
                kind: classify_issue_kind(arc),
                raw_text: String::from(raw_slice),
                replacement: arc.norm_surface.clone(),
                suggestions,
            });
        }

        (normalized, issues)
    }

    /// Segments a multi-sentence paragraph with `TurkishSentenceExtractor::DEFAULT` and returns all
    /// `SpanIssue`s with exact paragraph-relative UTF-8 and UTF-16 offsets (surrogate-pair safe).
    pub fn check_paragraph(
        &self,
        paragraph: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Vec<SpanIssue> {
        if paragraph.is_empty() {
            return Vec::new();
        }
        let sent_spans = TurkishSentenceExtractor::DEFAULT.extract_raw_spans(paragraph);
        let mut out = Vec::new();
        for sp in sent_spans {
            let sent_str = &paragraph[sp.utf8_start..sp.utf8_end];
            let (_, sent_issues) = self.check_sentence(sent_str, dynamic, scratch);
            for mut iss in sent_issues {
                iss.utf8_start += sp.utf8_start;
                iss.utf8_end += sp.utf8_start;
                iss.utf16_start += sp.utf16_start;
                iss.utf16_end += sp.utf16_start;
                out.push(iss);
            }
        }
        out
    }

    #[allow(clippy::too_many_arguments)]
    pub fn score_arc(
        &self,
        lattice: &SentenceLattice,
        prev2_lex: Option<&str>,
        prev1_lex: Option<&str>,
        prev_morph_tag: i16,
        arc: &SpanArc,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> f32 {
        let mut feat_buf = [0u64; 64];
        let mut dense_buf = [0.0f32; DENSE_DIM];
        let mut lm6_buf = [0.0f32; 6];

        let u_cnt = extract_unary_sparse_features(
            lattice,
            &self.generator,
            arc,
            &mut feat_buf,
            dynamic,
            scratch,
        );
        let mut score = dot_sparse(&self.sparse_weights, &feat_buf[..u_cnt]);
        extract_dense_features(
            lattice,
            prev2_lex,
            prev1_lex,
            arc,
            &self.generator,
            &mut lm6_buf,
            &mut dense_buf,
            dynamic,
            scratch,
        );
        score += dot_dense(self.dense_weights, &dense_buf);
        let t_cnt = extract_transition_sparse_features(
            lattice,
            &self.generator,
            prev1_lex,
            prev_morph_tag,
            arc,
            &mut feat_buf,
            dynamic,
            scratch,
        );
        score += dot_sparse(&self.sparse_weights, &feat_buf[..t_cnt]);
        score
    }

    #[allow(clippy::type_complexity)]
    fn decode_viterbi_internal<'b>(
        &self,
        lat: &'b SentenceLattice,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> (
        Vec<&'b SpanArc>,
        Vec<(Option<&'b str>, Option<&'b str>, i16)>,
        f32,
    ) {
        let n = lat.tokens.len();
        let mut arena: Vec<BackPtr<'b>> = Vec::with_capacity(n * BEAM_WIDTH + 2);
        let mut chart: Vec<Vec<usize>> = (0..=n).map(|_| Vec::with_capacity(BEAM_WIDTH + 1)).collect();

        arena.push(BackPtr {
            arc_start: 0,
            arc_idx: usize::MAX,
            prev_arena_idx: None,
            prev2_lex: None,
            prev1_lex: None,
            prev_morph_tag: -1,
            score: 0.0,
        });
        chart[0].push(0);

        let mut feat_buf = [0u64; 64];
        let mut dense_buf = [0.0f32; DENSE_DIM];
        let mut lm6_buf = [0.0f32; 6];

        for i in 0..n {
            if chart[i].is_empty() {
                continue;
            }
            let incoming_snapshot = chart[i].clone();
            for (arc_idx, arc) in lat.outgoing[i].iter().enumerate() {
                let u_cnt = extract_unary_sparse_features(
                    lat,
                    &self.generator,
                    arc,
                    &mut feat_buf,
                    dynamic,
                    scratch,
                );
                let unary_sparse_score = dot_sparse(&self.sparse_weights, &feat_buf[..u_cnt]);

                let mut best_prev_idx: Option<usize> = None;
                let mut best_total = f32::NEG_INFINITY;

                for &bp_idx in &incoming_snapshot {
                    let prev_bp = arena[bp_idx];
                    extract_dense_features(
                        lat,
                        prev_bp.prev2_lex,
                        prev_bp.prev1_lex,
                        arc,
                        &self.generator,
                        &mut lm6_buf,
                        &mut dense_buf,
                        dynamic,
                        scratch,
                    );
                    let dense_score = dot_dense(self.dense_weights, &dense_buf);
                    let t_cnt = extract_transition_sparse_features(
                        lat,
                        &self.generator,
                        prev_bp.prev1_lex,
                        prev_bp.prev_morph_tag,
                        arc,
                        &mut feat_buf,
                        dynamic,
                        scratch,
                    );
                    let trans_sparse_score = dot_sparse(&self.sparse_weights, &feat_buf[..t_cnt]);
                    let cand_total =
                        prev_bp.score + unary_sparse_score + dense_score + trans_sparse_score;
                    if cand_total > best_total {
                        best_total = cand_total;
                        best_prev_idx = Some(bp_idx);
                    }
                }

                if let Some(prev_idx) = best_prev_idx {
                    if arc.end_idx <= n {
                        let prev_bp = arena[prev_idx];
                        let next_tag = advance_lex_morph_tag(lat, prev_bp.prev_morph_tag, arc);
                        let (nx2, nx1) = advance_lexical_history(
                            lat,
                            prev_bp.prev2_lex,
                            prev_bp.prev1_lex,
                            arc,
                        );
                        let cand_bp = BackPtr {
                            arc_start: i,
                            arc_idx,
                            prev_arena_idx: Some(prev_idx),
                            prev2_lex: nx2,
                            prev1_lex: nx1,
                            prev_morph_tag: next_tag,
                            score: best_total,
                        };
                        insert_top_k(&mut chart[arc.end_idx], &mut arena, cand_bp, BEAM_WIDTH);
                    }
                }
            }
        }

        if chart[n].is_empty() {
            let mut fallback = Vec::with_capacity(n);
            let mut hist = Vec::with_capacity(n);
            for slot in lat.outgoing.iter().take(n) {
                fallback.push(&slot[0]);
                hist.push((None, None, -1));
            }
            return (fallback, hist, 0.0);
        }

        let mut best_final = chart[n][0];
        for &idx in &chart[n][1..] {
            if arena[idx].score > arena[best_final].score {
                best_final = idx;
            }
        }
        let best_score = arena[best_final].score;

        let mut path = Vec::with_capacity(n);
        let mut histories = Vec::with_capacity(n);
        let mut cur = best_final;
        while arena[cur].arc_idx != usize::MAX {
            let node = arena[cur];
            let parent = arena[node.prev_arena_idx.unwrap_or(0)];
            path.push(&lat.outgoing[node.arc_start][node.arc_idx]);
            histories.push((parent.prev2_lex, parent.prev1_lex, parent.prev_morph_tag));
            cur = node.prev_arena_idx.unwrap_or(0);
        }
        path.reverse();
        histories.reverse();
        (path, histories, best_score)
    }
}

#[inline]
pub fn advance_lex_morph_tag(lat: &SentenceLattice, prev_tag: i16, arc: &SpanArc) -> i16 {
    if arc.word_count == 1 && arc.end_idx == arc.start_idx + 1 {
        let t = &lat.tokens[arc.start_idx];
        if t.is_immutable() {
            return if is_sentence_boundary_punct(t) {
                -1
            } else {
                prev_tag
            };
        }
    }
    arc.coarse_morph_tag
}

fn insert_top_k<'b>(
    beam: &mut Vec<usize>,
    arena: &mut Vec<BackPtr<'b>>,
    item: BackPtr<'b>,
    k: usize,
) {
    for slot in beam.iter_mut() {
        let ex = arena[*slot];
        if ex.prev_morph_tag == item.prev_morph_tag
            && ex.prev1_lex == item.prev1_lex
            && ex.prev2_lex == item.prev2_lex
        {
            if item.score > ex.score {
                arena[*slot] = item;
            }
            return;
        }
    }
    if beam.len() < k {
        let idx = arena.len();
        arena.push(item);
        beam.push(idx);
        return;
    }
    let mut min_pos = 0usize;
    let mut min_score = arena[beam[0]].score;
    for (p, &a_idx) in beam.iter().enumerate().skip(1) {
        if arena[a_idx].score < min_score {
            min_score = arena[a_idx].score;
            min_pos = p;
        }
    }
    if item.score > min_score {
        arena[beam[min_pos]] = item;
    }
}

#[inline]
fn dot_sparse(table: &QuantizedWeightTableView<'_>, feats: &[u64]) -> f32 {
    let mut sum = 0.0f32;
    for &k in feats {
        sum += table.get(k);
    }
    sum
}

#[inline]
fn dot_dense(weights: &[f32; DENSE_DIM], vals: &[f32; DENSE_DIM]) -> f32 {
    let mut sum = 0.0f32;
    for i in 0..DENSE_DIM {
        sum += weights[i] * vals[i];
    }
    sum
}

// =========================================================================
// Dense & Sparse Feature Extraction (Bit-Exact with Java)
// =========================================================================

#[allow(clippy::too_many_arguments)]
fn extract_dense_features<'a>(
    lat: &SentenceLattice,
    prev2_lex: Option<&str>,
    prev1_lex: Option<&str>,
    arc: &SpanArc,
    gen: &SpanCandidateGenerator<'a>,
    lm6_buf: &mut [f32; 6],
    out: &mut [f32; DENSE_DIM],
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) {
    let next_lex = next_lexical_word_or_null(&lat.tokens, arc.end_idx);
    gen.compute_span_lm_comparison(lat, prev2_lex, prev1_lex, arc, lm6_buf);

    let fwd_smooth2 = clamp_lm(gen.lm_bigram(prev1_lex, Some(&arc.first_word_lower)));
    let right_smooth2 = clamp_lm(gen.lm_bigram(Some(&arc.last_word_lower), next_lex));
    let int_smooth = if arc.word_count >= 2 {
        clamp_lm(arc.internal_lm_bonus)
    } else {
        0.0
    };
    let clitic_skip_llr = gen.compute_clitic_transparency_llr(lat, arc);

    let raw_tok = &lat.tokens[arc.start_idx];
    let is_identity_lower =
        (arc.end_idx == arc.start_idx + 1) && arc.norm_lower == raw_tok.lower;

    out[F_SMOOTH_FWD2] = fwd_smooth2 * 0.20;
    out[F_SMOOTH_RIGHT2] = right_smooth2 * 0.20;
    out[F_SMOOTH_INTERNAL] = int_smooth * 0.20;
    out[F_SMOOTH_SPAN_LLR] = (lm6_buf[0] - lm6_buf[1]) * 0.20;
    out[F_CLEAN_FWD3] = lm6_buf[4] * 0.20;
    out[F_CLEAN_RIGHT3] = lm6_buf[5] * 0.20;
    out[F_CLEAN_SPAN_LLR] = (lm6_buf[2] - lm6_buf[3]) * 0.20;
    out[F_PARALLEL_PROB] = arc.parallel_prob;
    out[F_PARALLEL_LOG_CNT] = arc.parallel_log_count * 0.25;
    out[F_SUFFIX_PRECISION] = arc.suffix_precision;
    out[F_EDIT_COST] = arc.edit_cost;
    out[F_DIACRITIC_DIFFS] = (arc.diacritic_diffs as f32) * 0.35;
    out[F_IS_IDENTITY_LOWER] = if is_identity_lower { 1.0 } else { 0.0 };
    out[F_IS_REGULAR_MORPH] = if arc.is_regular_morph { 1.0 } else { 0.0 };
    out[F_CHAR_PHONOTACTICS] = clamp_phono(arc.char_phonotactic_score) * 0.25;
    out[F_RESERVED_ZERO] = 0.0;
    out[F_ASCII_SENT_RATIO] = if (arc.source_mask & (SRC_DEASCIIFIER | SRC_FORMAL_MORPH)) != 0
        && arc.diacritic_diffs > 0
    {
        lat.sentence_ascii_ratio
    } else {
        0.0
    };
    out[F_INFORMAL_EXACT_VS_STRIP] = if (arc.source_mask & SRC_INFORMAL_EXACT) != 0 {
        1.0
    } else if arc.strips_explicit_diacritic {
        -1.0
    } else {
        0.0
    };
    out[F_CLITIC_SKIP_LLR] = clitic_skip_llr * 0.20;
    out[F_QUESTION_VOWEL_HARMONY] =
        gen.compute_question_clitic_harmony(lat, prev1_lex, arc, dynamic, scratch) * 1.5;
}

#[inline]
fn clamp_phono(v: f32) -> f32 {
    if v.is_nan() || v.is_infinite() {
        -4.5
    } else {
        v.clamp(-6.0, 0.0)
    }
}

fn extract_unary_sparse_features<'a>(
    lat: &SentenceLattice,
    gen: &SpanCandidateGenerator<'a>,
    arc: &SpanArc,
    buf: &mut [u64; 64],
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) -> usize {
    let mut p = 0usize;
    let toks = &lat.tokens;
    let t0 = &toks[arc.start_idx];
    let span_len = arc.end_idx - arc.start_idx;
    let raw_span_buf: String;
    let raw_span_low: &str = if span_len == 1 {
        &t0.lower
    } else if span_len == 2 {
        raw_span_buf = alloc::format!("{}+{}", t0.lower, toks[arc.start_idx + 1].lower);
        &raw_span_buf
    } else {
        raw_span_buf = alloc::format!("{}+3", t0.lower);
        &raw_span_buf
    };

    let prev_raw: &str = if arc.start_idx > 0 {
        &toks[arc.start_idx - 1].lower
    } else {
        "^"
    };
    let next_lex = next_lexical_word_or_null(toks, arc.end_idx);
    let next_lex_key: &str = next_lex.unwrap_or("$");
    let is_id_low = span_len == 1 && arc.norm_lower == t0.lower;
    let ascii_bkt: i64 = if lat.sentence_ascii_ratio > 0.45 {
        2
    } else if lat.sentence_ascii_ratio > 0.10 {
        1
    } else {
        0
    };

    if arc.raw_parallel_total >= 3 {
        let raw_explicit_dotless_i = raw_span_low.contains('ı');
        let prev_pure_amb_ascii =
            !has_explicit_turkish_diacritics(prev_raw) && has_ascii_ambiguous_chars(prev_raw);
        let ctx_governed_q_twin = arc.word_count == 1
            && arc.start_idx > 0
            && !(raw_explicit_dotless_i && prev_pure_amb_ascii)
            && is_standalone_question_clitic(raw_span_low)
            && is_standalone_question_clitic(&arc.norm_lower)
            && gen.compute_question_clitic_harmony(lat, Some(prev_raw), arc, dynamic, scratch)
                != 0.0;
        if !ctx_governed_q_twin {
            buf[p] = hash2(
                T_LEX_LOWER_PAIR,
                str_hash(Some(raw_span_low)),
                str_hash(Some(&arc.norm_lower)),
            );
            p += 1;
            buf[p] = hash2(
                T_LEX_SURF_PAIR,
                str_hash(Some(&t0.surface)),
                str_hash(Some(&arc.norm_surface)),
            );
            p += 1;
        }
        buf[p] = hash3(
            T_LEX_PAIR_NEXT,
            str_hash(Some(raw_span_low)),
            str_hash(Some(&arc.norm_lower)),
            str_hash(Some(next_lex_key)),
        );
        p += 1;
        buf[p] = hash3(
            T_PREV_LEX_PAIR,
            str_hash(Some(prev_raw)),
            str_hash(Some(raw_span_low)),
            str_hash(Some(&arc.norm_lower)),
        );
        p += 1;
    }

    let slot_arcs = &lat.outgoing[arc.start_idx];
    let raw_reg_morph = slot_arcs.first().is_some_and(|a| a.is_regular_morph);
    let compact_src = (arc.source_mask & 0x1_FFFF) as i64;

    buf[p] = hash3(
        T_ID_OOV_MORPH_SRC,
        (if is_id_low { 2 } else { 0 }) | (if raw_reg_morph { 1 } else { 0 }),
        if arc.is_regular_morph { 1 } else { 0 },
        compact_src,
    );
    p += 1;
    buf[p] = hash3(
        T_SRC_ASCII_DIAC,
        compact_src,
        (ascii_bkt << 1) | (if raw_reg_morph { 1 } else { 0 }),
        if arc.diacritic_diffs > 0 { 1 } else { 0 },
    );
    p += 1;
    buf[p] = hash2(
        T_RIGHT_LEX_BIGRAM,
        str_hash(Some(&arc.last_word_lower)),
        str_hash(Some(next_lex_key)),
    );
    p += 1;

    if arc.word_count >= 2 {
        let head_state =
            (((arc.first_word_morph_tag as i64) & 0xFFFF) << 3) | ((arc.head_syntax_class as i64) & 7);
        buf[p] = hash3(
            T_SPLIT_TAIL_HEAD_NEXT,
            str_hash(Some(&arc.last_word_lower)),
            head_state,
            str_hash(Some(next_lex_key)),
        );
        p += 1;
        buf[p] = hash2(
            T_SPLIT_TAIL_HEAD,
            str_hash(Some(&arc.last_word_lower)),
            head_state,
        );
        p += 1;
        buf[p] = hash2(
            T_SPLIT_TAIL_QMARK,
            str_hash(Some(&arc.last_word_lower)),
            if lat.has_question_mark { 1 } else { 0 },
        );
        p += 1;
    }

    if span_len >= 2 {
        buf[p] = hash2(
            T_MERGE_RAW_NEXT,
            str_hash(Some(raw_span_low)),
            str_hash(Some(next_lex_key)),
        );
        p += 1;
        buf[p] = hash2(
            T_MERGE_PREV_NORM,
            str_hash(Some(prev_raw)),
            str_hash(Some(&arc.norm_lower)),
        );
        p += 1;
        buf[p] = hash2(
            T_MERGE_SRC_PROP,
            compact_src,
            if arc.is_proper_or_abbr { 1 } else { 0 },
        );
        p += 1;
        buf[p] = hash3(
            T_MERGE_MORPH_NEXT,
            (arc.coarse_morph_tag as i64) & 0xFFFF,
            compact_src,
            str_hash(Some(next_lex_key)),
        );
        p += 1;
    }

    if lat.overt_subject_person != 0 && arc.verb_person != 0 {
        buf[p] = hash3(
            T_SUBJ_VERB_AGREE,
            lat.overt_subject_person as i64,
            arc.verb_person as i64,
            if is_id_low { 1 } else { 0 },
        );
        p += 1;
    }

    if arc.case_changed
        || arc.apostrophe_added
        || arc.apostrophe_removed
        || t0.shape != zemberek_tok::CasingShape::Lower
    {
        buf[p] = hash3(
            T_CASING_TRANSITION,
            t0.shape as i64,
            if arc.case_changed { 1 } else { 0 },
            (if arc.start_idx == 0 { 2 } else { 0 }) | (if arc.is_proper_or_abbr { 1 } else { 0 }),
        );
        p += 1;
        if arc.apostrophe_added {
            buf[p] = hash2(
                T_APOSTROPHE_ADD,
                if arc.is_proper_or_abbr { 1 } else { 0 },
                compact_src,
            );
            p += 1;
        }
        if arc.apostrophe_removed {
            buf[p] = hash2(
                T_APOSTROPHE_DROP,
                if arc.is_proper_or_abbr { 1 } else { 0 },
                compact_src,
            );
            p += 1;
        }
    }

    let next_shape: i64 = toks
        .get(arc.end_idx)
        .map_or(5, |nt| nt.shape as i64);
    buf[p] = hash2(T_MORPH_NEXT_SHAPE, arc.coarse_morph_tag as i64, next_shape);
    p += 1;

    if !is_id_low && (arc.raw_neg_verb_cue || arc.verb_polarity < 0) {
        buf[p] = hash3(
            T_POLARITY_MATCH,
            if arc.raw_neg_verb_cue { 1 } else { 0 },
            (arc.verb_polarity as i64) + 2,
            compact_src,
        );
        p += 1;
    }

    if span_len == 1 {
        if !is_id_low {
            let len_bkt: i64 = match u16_len(&t0.lower) {
                8.. => 3,
                6..=7 => 2,
                5 => 1,
                _ => 0,
            };
            let cost_bkt: i64 = if arc.edit_cost <= 0.35 {
                0
            } else if arc.edit_cost <= 0.85 {
                1
            } else if arc.edit_cost <= 1.25 {
                2
            } else {
                3
            };
            let gain_bkt: i64 = if arc.unary_clean_gain >= 8.0 {
                4
            } else if arc.unary_clean_gain >= 4.0 {
                3
            } else if arc.unary_clean_gain >= 1.5 {
                2
            } else if arc.unary_clean_gain >= -0.5 {
                1
            } else {
                0
            };
            let phono_gain = arc.char_phonotactic_score - arc.raw_char_phono;
            let phono_bkt: i64 = if phono_gain >= 1.0 {
                3
            } else if phono_gain >= 0.35 {
                2
            } else if phono_gain >= -0.15 {
                1
            } else {
                0
            };
            let clean_cnt_bkt: i64 = match arc.raw_clean_count {
                80.. => 4,
                20..=79 => 3,
                5..=19 => 2,
                1..=4 => 1,
                0 => 0,
            };

            buf[p] = hash3(
                T_SRC_LEN_COST_GAIN,
                compact_src,
                (len_bkt << 3) | cost_bkt,
                gain_bkt,
            );
            p += 1;
            buf[p] = hash3(T_SRC_PHONO_GAIN, compact_src, len_bkt, phono_bkt);
            p += 1;
            buf[p] = hash3(
                T_SRC_RAW_CLEAN_ATTEST,
                compact_src,
                clean_cnt_bkt,
                t0.shape as i64,
            );
            p += 1;
        }

        let pron_code: i64 = match t0.lower.as_str() {
            "bende" => 1,
            "sende" => 2,
            "onda" | "oda" => 3,
            "bizde" => 4,
            "sizde" => 5,
            _ => 0,
        };
        if pron_code != 0 {
            buf[p] = hash3(
                T_PRON_LOC_RIGHT,
                pron_code,
                arc.word_count as i64,
                arc.next_lex_state as i64,
            );
            p += 1;
        } else if arc.word_count == 2
            && (arc.last_word_lower == "de" || arc.last_word_lower == "da")
        {
            buf[p] = hash3(
                T_PRON_LOC_RIGHT,
                7,
                arc.head_syntax_class as i64,
                arc.next_lex_state as i64,
            );
            p += 1;
        }
    }

    p
}

#[allow(clippy::too_many_arguments)]
fn extract_transition_sparse_features<'a>(
    lat: &SentenceLattice,
    gen: &SpanCandidateGenerator<'a>,
    prev1_lex: Option<&str>,
    prev_morph_tag: i16,
    arc: &SpanArc,
    buf: &mut [u64; 64],
    dynamic: Option<&DynamicStemTrie>,
    scratch: &mut AnalyzerScratch,
) -> usize {
    let mut p = 0usize;
    let prev_w: i64 = match prev1_lex {
        None => 0x1111,
        Some(s) => str_hash(Some(s)),
    };
    let prev_tag = prev_morph_tag as i64;
    let first_w_hash = str_hash(Some(&arc.first_word_lower));
    let first_tag = arc.first_word_morph_tag as i64;

    buf[p] = hash2(T_TRANS_LEX_BIGRAM, prev_w, first_w_hash);
    p += 1;
    buf[p] = hash2(T_TRANS_MORPH_BIGRAM, prev_tag, first_tag);
    p += 1;
    buf[p] = hash2(T_TRANS_WORD_TAG, prev_w, first_tag);
    p += 1;
    buf[p] = hash2(T_TRANS_TAG_WORD, prev_tag, first_w_hash);
    p += 1;

    if arc.end_idx == arc.start_idx + 1 {
        if arc.next_lex_state != 0
            && u16_len(&arc.norm_lower) <= 6
            && matches!(
                arc.first_word_lower.as_str(),
                "ben"
                    | "bende"
                    | "sen"
                    | "sende"
                    | "o"
                    | "onda"
                    | "oda"
                    | "biz"
                    | "bizde"
                    | "siz"
                    | "sizde"
            )
        {
            buf[p] = hash3(
                T_TRANS_PRON_LOC_LEFT,
                prev_tag,
                arc.word_count as i64,
                arc.next_lex_state as i64,
            );
            p += 1;
        }
        let qh = gen.compute_question_clitic_harmony(lat, prev1_lex, arc, dynamic, scratch);
        if qh != 0.0 {
            let is_id_low = arc.norm_lower == lat.tokens[arc.start_idx].lower;
            buf[p] = hash2(
                T_TRANS_QUESTION_HARMONY,
                if qh > 0.0 { 1 } else { -1 },
                if is_id_low { 1 } else { 0 },
            );
            p += 1;
        }
    }
    p
}

// =========================================================================
// Bit-Exact 64-Bit Feature Hashing (`SpanLatticePerceptronNormalizer`)
// =========================================================================

#[inline]
fn str_hash(s: Option<&str>) -> i64 {
    let Some(s) = s else {
        return 0;
    };
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    if s.is_ascii() {
        for &b in s.as_bytes() {
            h ^= b as u64;
            h = h.wrapping_mul(0x0100_0000_01b3);
        }
    } else {
        let mut tmp = [0u16; 2];
        for c in s.chars() {
            for &u in c.encode_utf16(&mut tmp).iter() {
                h ^= u as u64;
                h = h.wrapping_mul(0x0100_0000_01b3);
            }
        }
    }
    h as i64
}

#[inline]
fn hash2(template: i32, a: i64, b: i64) -> u64 {
    let mut h = 0x9E37_79B9_7F4A_7C15u64.wrapping_mul(((template as i64) + 17) as u64);
    h = mix64(h ^ (a as u64));
    h = mix64(h ^ (b as u64));
    if h == 0 { 1 } else { h }
}

#[inline]
fn hash3(template: i32, a: i64, b: i64, c: i64) -> u64 {
    let mut h = 0x9E37_79B9_7F4A_7C15u64.wrapping_mul(((template as i64) + 31) as u64);
    h = mix64(h ^ (a as u64));
    h = mix64(h ^ (b as u64));
    h = mix64(h ^ (c as u64));
    if h == 0 { 1 } else { h }
}

#[inline]
const fn mix64(mut z: u64) -> u64 {
    z = (z ^ (z >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
    z ^ (z >> 31)
}
