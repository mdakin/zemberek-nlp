#![no_std]
#![forbid(unsafe_code)]

extern crate alloc;

pub mod candidate;
pub mod codec;
pub mod harmony;
pub mod hash;
pub mod morph_gen;
pub mod priors;
pub mod scorer;

use alloc::borrow::Cow;
use alloc::collections::BTreeMap;
use alloc::string::String;
use alloc::vec;
use alloc::vec::Vec;
use core::cell::RefCell;
use zemberek_core::{PrimaryPos, SecondaryPos};
use zemberek_morph::{AnalyzerScratch, DynamicStemTrie, LexiconGraph};
use zemberek_pack::deasc::{ConfusionSetView, DeascModelView};
use zemberek_pack::morph_fst::MorphFstView;
use zemberek_pack::{PackError, ZmbkPack, SECT_DEASC, SECT_MORPH_FST};
use crate::candidate::{add_if_surface_absent, Candidate};
use crate::codec::{
    apply_casing_and_pins, contains_dotless_i, contains_non_dotless_turkish_diacritic,
    contains_pinned_turkish, is_codec_apostrophe, is_compatible_with_input, strip_circumflex,
    to_turkish_lower, tokenize_with_u16, u16_len, Casing, DeascToken,
};
use crate::harmony::CharacterHarmonyModel;
use crate::morph_gen::MorphologicalCandidateGenerator;
use crate::priors::LexiconPriors;
use crate::scorer::{
    score_candidate_in_sequence, score_transition, score_trigram_only, score_unary,
    FEATURE_BUFFER_SIZE,
};

/// Controls how circumflex vowels (`â`, `î`, `û`) are emitted during deasciification.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub enum CircumflexMode {
    /// Never inserts new circumflexes; preserves any circumflexes already present in the input.
    None,
    /// Inserts circumflexes only on meaning-disambiguating minimal pairs (default).
    #[default]
    DisambiguationOnly,
    /// Allows circumflexes on any lexicon/corpus entry supported by the model.
    Full,
}

pub const DEFAULT_BEAM_WIDTH: usize = 4;
const MAX_PLAIN_TOKEN_CACHE: usize = 1024;

#[derive(Clone)]
struct CachedPlainToken {
    base_morph: Vec<Candidate>,
    resolved_candidates: Option<Vec<Candidate>>,
    empirical_log_priors: Vec<f32>,
}

struct TokenLattice {
    candidates: Vec<Candidate>,
    empirical_log_priors: Vec<f32>,
    char_relative_scores: Vec<f32>,
    unary_scores: Vec<f32>,
}

impl TokenLattice {
    #[inline]
    fn is_ambiguous(&self) -> bool {
        self.candidates.len() >= 2
    }
}

#[derive(Clone, Copy)]
struct BeamNode {
    prev1_col: Option<usize>,
    prev1_ci: usize,
    cur_col: Option<usize>,
    cur_ci: usize,
    score: f32,
    parent: Option<usize>,
}

/// Exact zero-copy port of `zemberek.normalization.deasciifier.latticedeasciifier.LatticeDeasciifier`.
pub struct LatticeDeasciifier<'a> {
    priors: LexiconPriors<'a>,
    char_model: CharacterHarmonyModel<'a>,
    morph_gen: MorphologicalCandidateGenerator<'a>,
    beam_width: usize,
    circumflex_mode: CircumflexMode,
    plain_cache: RefCell<BTreeMap<String, CachedPlainToken>>,
}

impl<'a> LatticeDeasciifier<'a> {
    /// Builds a self-contained `LatticeDeasciifier` from a `.zmbk` pack (`formal_generator_allows_informal = true`,
    /// matching `LatticeDeasciifier.createWithDefaults()`).
    pub fn from_pack(pack: &ZmbkPack<'a>) -> Result<Self, PackError> {
        Self::from_pack_with_options(
            pack,
            DEFAULT_BEAM_WIDTH,
            CircumflexMode::DisambiguationOnly,
            true,
        )
    }

    /// Builds a `LatticeDeasciifier` matching `SpanCandidateGenerator`'s dual-injected mode
    /// (`formal_generator_allows_informal = false`, `CircumflexMode::None`).
    pub fn from_pack_for_normalizer(pack: &ZmbkPack<'a>) -> Result<Self, PackError> {
        Self::from_pack_with_options(pack, DEFAULT_BEAM_WIDTH, CircumflexMode::None, false)
    }

    pub fn from_pack_with_options(
        pack: &ZmbkPack<'a>,
        beam_width: usize,
        circumflex_mode: CircumflexMode,
        formal_generator_allows_informal: bool,
    ) -> Result<Self, PackError> {
        let graph = LexiconGraph::from_pack(pack)?;
        let (_, fst_bytes) = pack.section_bytes(SECT_MORPH_FST)?;
        let fst = MorphFstView::from_section_bytes(fst_bytes)?;
        let (_, deasc_bytes) = pack.section_bytes(SECT_DEASC)?;
        let model = DeascModelView::from_section_bytes(deasc_bytes)?;

        Ok(Self {
            priors: LexiconPriors::new(model),
            char_model: CharacterHarmonyModel::new(model.char_weights),
            morph_gen: MorphologicalCandidateGenerator::new(
                graph,
                fst,
                formal_generator_allows_informal,
            ),
            beam_width: beam_width.max(1),
            circumflex_mode,
            plain_cache: RefCell::new(BTreeMap::new()),
        })
    }

    #[inline]
    pub fn circumflex_mode(&self) -> CircumflexMode {
        self.circumflex_mode
    }

    #[inline]
    pub fn set_circumflex_mode(&mut self, mode: CircumflexMode) {
        self.circumflex_mode = mode;
    }

    pub fn invalidate_cache(&self) {
        self.plain_cache.borrow_mut().clear();
    }

    pub fn deasciify(
        &self,
        text: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> String {
        self.deasciify_internal(text, true, self.circumflex_mode, dynamic, scratch)
    }

    pub fn deasciify_with_mode(
        &self,
        text: &str,
        mode: CircumflexMode,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> String {
        self.deasciify_internal(text, true, mode, dynamic, scratch)
    }

    pub fn deasciify_known_words(
        &self,
        text: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> String {
        self.deasciify_internal(text, false, self.circumflex_mode, dynamic, scratch)
    }

    pub fn deasciify_known_words_with_mode(
        &self,
        text: &str,
        mode: CircumflexMode,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> String {
        self.deasciify_internal(text, false, mode, dynamic, scratch)
    }

    fn deasciify_internal(
        &self,
        text: &str,
        allow_oov_fallback: bool,
        mode: CircumflexMode,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> String {
        if text.is_empty() {
            return String::new();
        }
        let (mut tokens, text_u16) = tokenize_with_u16(text);
        if tokens.is_empty() {
            return String::from(text);
        }

        let mut lattice = self.build_sentence_lattice(text, &text_u16, &mut tokens, dynamic, scratch);
        let chosen_indices = self.decode_sentence(&tokens, &mut lattice);

        let mut out: Option<String> = None;
        let mut cursor_utf8 = 0usize;
        for (i, tok) in tokens.iter().enumerate() {
            let chosen = &lattice[i].candidates[chosen_indices[i]];
            if tok.immutable || (!allow_oov_fallback && chosen.oov_fallback) {
                continue;
            }
            let surface = resolve_candidate_surface(chosen, mode);
            let restored = apply_casing_and_pins(tok, &surface, chosen.foreign_stem_length);
            if restored != tok.original {
                let buf = out.get_or_insert_with(|| String::with_capacity(text.len() + 16));
                buf.push_str(&text[cursor_utf8..tok.start_utf8]);
                buf.push_str(&restored);
                cursor_utf8 = tok.end_utf8;
            }
        }
        match out {
            None => String::from(text),
            Some(mut buf) => {
                buf.push_str(&text[cursor_utf8..]);
                buf
            }
        }
    }

    fn build_sentence_lattice(
        &self,
        text: &str,
        text_u16: &[u16],
        tokens: &mut [DeascToken],
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Vec<TokenLattice> {
        let n = tokens.len();
        if !contains_non_dotless_turkish_diacritic(text)
            && contains_dotless_i(text)
            && self.has_corrupted_dotless_i_token(tokens, dynamic, scratch)
        {
            for tok in tokens.iter_mut() {
                if !tok.immutable && contains_dotless_i(&tok.original) {
                    *tok = tok.without_dotless_i_pin();
                }
            }
        }

        let mut lattice = Vec::with_capacity(n);
        let use_cache = dynamic.is_none();

        for tok in tokens.iter() {
            if tok.immutable {
                let keep = Candidate::oov(&tok.ascii_lower, u16_len(&tok.original));
                lattice.push(TokenLattice {
                    candidates: vec![keep],
                    empirical_log_priors: vec![0.0],
                    char_relative_scores: vec![0.0],
                    unary_scores: Vec::new(),
                });
                continue;
            }

            if use_cache && tok.is_plain_cacheable_word() {
                let cached = self.get_or_build_plain_token(&tok.ascii_lower, scratch);
                if let Some(cands) = cached.resolved_candidates {
                    if tok.casing == Casing::Lower || tok.sentence_start {
                        let char_scores = if cands.len() >= 2 {
                            self.char_model
                                .score_candidate_differences(text_u16, tok, &cands)
                        } else {
                            vec![0.0]
                        };
                        lattice.push(TokenLattice {
                            candidates: cands,
                            empirical_log_priors: cached.empirical_log_priors,
                            char_relative_scores: char_scores,
                            unary_scores: Vec::new(),
                        });
                        continue;
                    }
                }
                lattice.push(self.build_general_token_lattice(
                    text_u16,
                    tok,
                    Some(cached.base_morph),
                    dynamic,
                    scratch,
                ));
                continue;
            }

            lattice.push(self.build_general_token_lattice(text_u16, tok, None, dynamic, scratch));
        }
        lattice
    }

    fn get_or_build_plain_token(
        &self,
        ascii_lower: &str,
        scratch: &mut AnalyzerScratch,
    ) -> CachedPlainToken {
        if let Some(existing) = self.plain_cache.borrow().get(ascii_lower) {
            return existing.clone();
        }
        let built = self.compute_plain_token(ascii_lower, scratch);
        let mut cache = self.plain_cache.borrow_mut();
        if cache.len() < MAX_PLAIN_TOKEN_CACHE && ascii_lower.len() <= 24 {
            cache.insert(String::from(ascii_lower), built.clone());
        }
        built
    }

    fn compute_plain_token(
        &self,
        ascii_lower: &str,
        scratch: &mut AnalyzerScratch,
    ) -> CachedPlainToken {
        if self.priors.is_foreign_ascii_keep(ascii_lower, Casing::Lower) {
            let keep = vec![Candidate::oov(ascii_lower, u16_len(ascii_lower))];
            return CachedPlainToken {
                base_morph: keep.clone(),
                resolved_candidates: Some(keep),
                empirical_log_priors: vec![0.0],
            };
        }

        let base_morph =
            self.morph_gen
                .analyze_plain_with_fallbacks(ascii_lower, None, None, scratch);
        let confusion = self.priors.get_confusion(ascii_lower, Casing::Lower, false);
        if (base_morph.is_empty() && confusion.is_none())
            || (!base_morph.is_empty() && confusion.is_none() && is_obscure_only_match(&base_morph))
        {
            return CachedPlainToken {
                base_morph,
                resolved_candidates: None,
                empirical_log_priors: vec![0.0],
            };
        }

        let mut merged = Vec::with_capacity(base_morph.len() + 2);
        merged.extend(base_morph.iter().cloned());
        merge_confusion_candidates(&mut merged, confusion, ascii_lower, false);
        if merged.is_empty() {
            return CachedPlainToken {
                base_morph,
                resolved_candidates: None,
                empirical_log_priors: vec![0.0],
            };
        }

        let priors = build_empirical_priors_simple(&merged, confusion);
        CachedPlainToken {
            base_morph,
            resolved_candidates: Some(merged),
            empirical_log_priors: priors,
        }
    }

    fn build_general_token_lattice(
        &self,
        text_u16: &[u16],
        token: &DeascToken,
        precomputed_morph: Option<Vec<Candidate>>,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> TokenLattice {
        let pin_dotless_i = token.has_pinned_turkish;
        let mut candidates = match precomputed_morph {
            Some(v) => v,
            None => self.morph_gen.generate(token, &self.priors, dynamic, scratch),
        };

        let confusion =
            self.priors
                .get_confusion(&token.ascii_lower, token.casing, token.sentence_start);
        merge_confusion_candidates(&mut candidates, confusion, &token.original, pin_dotless_i);

        let supplement = candidates.is_empty()
            || (confusion.is_none() && self.needs_character_supplement(token, &candidates));

        if supplement {
            if candidates.is_empty() && !token.numeral_apostrophe && token.has_apostrophe() {
                self.add_unlisted_apostrophe_candidates(
                    text_u16,
                    token,
                    &mut candidates,
                    dynamic,
                    scratch,
                );
            }

            let max_char_cands = if candidates.is_empty() { 2 } else { 1 };
            for cs in self
                .char_model
                .decode_top_candidates(text_u16, token, max_char_cands)
            {
                if is_compatible_with_input(&token.original, &cs.surface, pin_dotless_i) {
                    add_if_surface_absent(&mut candidates, Candidate::oov(&cs.surface, 0));
                }
            }

            if !token.has_apostrophe()
                && ((!token.sentence_start && token.casing == Casing::Title)
                    || token.casing == Casing::Upper)
                && is_compatible_with_input(&token.original, &token.ascii_lower, pin_dotless_i)
            {
                add_if_surface_absent(
                    &mut candidates,
                    Candidate::oov(&token.ascii_lower, u16_len(&token.original)),
                );
            }
        }

        if candidates.is_empty() {
            let fallback = if pin_dotless_i {
                to_turkish_lower(&token.original)
            } else {
                token.ascii_lower.clone()
            };
            candidates.push(Candidate::oov(&fallback, 0));
        }

        let log_priors = build_empirical_priors(&candidates, confusion, token, &self.priors);
        let char_scores = if candidates.len() >= 2 {
            self.char_model
                .score_candidate_differences(text_u16, token, &candidates)
        } else {
            vec![0.0]
        };

        TokenLattice {
            candidates,
            empirical_log_priors: log_priors,
            char_relative_scores: char_scores,
            unary_scores: Vec::new(),
        }
    }

    fn add_unlisted_apostrophe_candidates(
        &self,
        text_u16: &[u16],
        token: &DeascToken,
        out_candidates: &mut Vec<Candidate>,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) {
        let apos = token.apostrophe_index.unwrap();
        let orig_chars: Vec<char> = token.original.chars().collect();
        let ascii_chars: Vec<char> = token.ascii_lower.chars().collect();
        let stem_orig: String = orig_chars[..apos].iter().collect();
        let stem_ascii: String = ascii_chars[..apos].iter().collect();
        let suffix_ascii: String = ascii_chars[apos + 1..].iter().collect();
        let pin_stem = token.has_pinned_turkish && contains_pinned_turkish(&stem_orig);
        let stem_token = DeascToken {
            start_u16: token.start_u16,
            end_u16: token.start_u16 + apos,
            start_utf8: token.start_utf8,
            end_utf8: token.start_utf8 + stem_orig.len(),
            original: stem_orig,
            ascii_lower: stem_ascii,
            casing: token.casing,
            apostrophe_index: None,
            numeral_apostrophe: false,
            immutable: false,
            sentence_start: token.sentence_start,
            right_punct: '\'',
            has_pinned_turkish: pin_stem,
        };
        let apos_char = orig_chars[apos];
        for stem_surf in self
            .char_model
            .decode_top_candidates(text_u16, &stem_token, 2)
        {
            for harmonized in self.morph_gen.harmonize_suffix_after_stem(
                &stem_surf.surface,
                apos_char,
                &suffix_ascii,
                &stem_surf.surface,
                0,
                &token.original,
                token.has_pinned_turkish,
                false,
                dynamic,
                scratch,
            ) {
                add_if_surface_absent(out_candidates, harmonized);
            }
        }
    }

    fn decode_sentence(&self, tokens: &[DeascToken], lattice: &mut [TokenLattice]) -> Vec<usize> {
        let n = tokens.len();
        if !lattice.iter().any(TokenLattice::is_ambiguous) {
            return vec![0usize; n];
        }

        let mut feat_buf = [0u64; FEATURE_BUFFER_SIZE];
        let sent_end_punct = tokens.last().map_or(' ', |t| t.right_punct);
        let sent_capitalized = tokens.first().is_some_and(|t| t.casing == Casing::Title);
        let sent_weights = &self.priors.model.sent_weights;

        for (i, col) in lattice.iter_mut().enumerate() {
            if !col.is_ambiguous() {
                continue;
            }
            let k = col.candidates.len();
            let mut unary = Vec::with_capacity(k);
            for ci in 0..k {
                unary.push(score_unary(
                    i,
                    tokens,
                    sent_end_punct,
                    sent_capitalized,
                    &col.candidates[ci],
                    col.empirical_log_priors[ci],
                    col.char_relative_scores[ci],
                    &self.priors,
                    sent_weights,
                    &mut feat_buf,
                ));
            }
            col.unary_scores = unary;
        }

        let boundary = Candidate::boundary();
        let mut chosen = run_pass1_beam_search(
            lattice,
            &boundary,
            sent_weights,
            self.beam_width,
            &mut feat_buf,
        );
        refine_with_wide_context(lattice, &mut chosen, &boundary, sent_weights, &mut feat_buf);
        chosen
    }

    fn has_corrupted_dotless_i_token(
        &self,
        tokens: &[DeascToken],
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> bool {
        for tok in tokens {
            if tok.immutable || !contains_dotless_i(&tok.original) {
                continue;
            }
            let pinned = self
                .morph_gen
                .generate(tok, &self.priors, dynamic, scratch);
            if pinned.is_empty() || is_obscure_only_match(&pinned) {
                let unpinned_tok = tok.without_dotless_i_pin();
                let unpinned = self
                    .morph_gen
                    .generate(&unpinned_tok, &self.priors, dynamic, scratch);
                if !unpinned.is_empty() && !is_obscure_only_match(&unpinned) {
                    return true;
                }
            }
        }
        false
    }

    fn needs_character_supplement(&self, token: &DeascToken, morph_cands: &[Candidate]) -> bool {
        if token.casing == Casing::Upper
            && self
                .priors
                .get_all_caps_turkish_acronym(&token.ascii_lower)
                .is_some()
        {
            return false;
        }
        if is_obscure_only_match(morph_cands) {
            return true;
        }
        if !token.sentence_start && token.casing == Casing::Title && !token.has_apostrophe() {
            return !morph_cands
                .iter()
                .any(|c| c.secondary_pos == SecondaryPos::ProperNoun);
        }
        false
    }
}

fn run_pass1_beam_search(
    lattice: &[TokenLattice],
    boundary: &Candidate,
    sent_weights: &zemberek_pack::mphf::QuantizedWeightTableView<'_>,
    beam_width: usize,
    feat_buf: &mut [u64; FEATURE_BUFFER_SIZE],
) -> Vec<usize> {
    let n = lattice.len();
    let mut arena: Vec<BeamNode> = Vec::with_capacity(n * beam_width + 2);
    arena.push(BeamNode {
        prev1_col: None,
        prev1_ci: 0,
        cur_col: None,
        cur_ci: 0,
        score: 0.0,
        parent: None,
    });
    let mut beam: Vec<usize> = vec![0usize];

    let resolve_cand = |col_opt: Option<usize>, ci: usize| -> &Candidate {
        match col_opt {
            Some(c) => &lattice[c].candidates[ci],
            None => boundary,
        }
    };

    for i in 0..n {
        let col = &lattice[i];
        let k = col.candidates.len();

        if k == 1 && beam.len() == 1 {
            let only_idx = beam[0];
            let only = arena[only_idx];
            let new_idx = arena.len();
            arena.push(BeamNode {
                prev1_col: only.cur_col,
                prev1_ci: only.cur_ci,
                cur_col: Some(i),
                cur_ci: 0,
                score: only.score,
                parent: Some(only_idx),
            });
            beam[0] = new_idx;
            continue;
        }

        if k == 1 && i >= 1 && !lattice[i - 1].is_ambiguous() {
            let only_cand = &col.candidates[0];
            let mut best_parent_idx = beam[0];
            let mut best_score = f32::NEG_INFINITY;
            for &hyp_idx in &beam {
                let hyp = arena[hyp_idx];
                let prev2 = resolve_cand(hyp.prev1_col, hyp.prev1_ci);
                let prev1 = resolve_cand(hyp.cur_col, hyp.cur_ci);
                let s =
                    hyp.score + score_trigram_only(prev2, prev1, only_cand, sent_weights, feat_buf);
                if s > best_score {
                    best_score = s;
                    best_parent_idx = hyp_idx;
                }
            }
            let best_parent = arena[best_parent_idx];
            let new_idx = arena.len();
            arena.push(BeamNode {
                prev1_col: best_parent.cur_col,
                prev1_ci: best_parent.cur_ci,
                cur_col: Some(i),
                cur_ci: 0,
                score: best_score,
                parent: Some(best_parent_idx),
            });
            beam.clear();
            beam.push(new_idx);
            continue;
        }

        let mut next_beam: Vec<BeamNode> = Vec::with_capacity(beam_width + 1);
        for &hyp_idx in &beam {
            let hyp = arena[hyp_idx];
            let prev2 = resolve_cand(hyp.prev1_col, hyp.prev1_ci);
            let prev1 = resolve_cand(hyp.cur_col, hyp.cur_ci);
            for ci in 0..k {
                let cand = &col.candidates[ci];
                let u_score = if k == 1 { 0.0 } else { col.unary_scores[ci] };
                let t_score = score_transition(prev2, prev1, cand, sent_weights, feat_buf);
                let node = BeamNode {
                    prev1_col: hyp.cur_col,
                    prev1_ci: hyp.cur_ci,
                    cur_col: Some(i),
                    cur_ci: ci,
                    score: hyp.score + u_score + t_score,
                    parent: Some(hyp_idx),
                };
                insert_top_beam(&mut next_beam, node, beam_width);
            }
        }
        beam.clear();
        for node in next_beam {
            let idx = arena.len();
            arena.push(node);
            beam.push(idx);
        }
    }

    let mut chosen = vec![0usize; n];
    let mut cur_idx = beam[0];
    for i in (0..n).rev() {
        let node = arena[cur_idx];
        chosen[i] = node.cur_ci;
        cur_idx = node.parent.unwrap_or(0);
    }
    chosen
}

fn insert_top_beam(beam: &mut Vec<BeamNode>, candidate: BeamNode, max_beam: usize) {
    if beam.len() == max_beam && candidate.score <= beam[max_beam - 1].score {
        return;
    }
    let mut pos = beam.len();
    while pos > 0 && candidate.score > beam[pos - 1].score {
        pos -= 1;
    }
    beam.insert(pos, candidate);
    if beam.len() > max_beam {
        beam.truncate(max_beam);
    }
}

fn refine_with_wide_context(
    lattice: &[TokenLattice],
    chosen: &mut [usize],
    boundary: &Candidate,
    sent_weights: &zemberek_pack::mphf::QuantizedWeightTableView<'_>,
    feat_buf: &mut [u64; FEATURE_BUFFER_SIZE],
) {
    let n = lattice.len();
    let columns: Vec<Vec<Candidate>> = lattice.iter().map(|c| c.candidates.clone()).collect();

    for sweep in 0..2 {
        let mut changed = false;
        if sweep == 0 {
            for i in (0..n).rev() {
                changed |= refine_single_position(
                    i,
                    lattice,
                    &columns,
                    chosen,
                    boundary,
                    sent_weights,
                    feat_buf,
                );
            }
        } else {
            for i in 0..n {
                changed |= refine_single_position(
                    i,
                    lattice,
                    &columns,
                    chosen,
                    boundary,
                    sent_weights,
                    feat_buf,
                );
            }
        }
        if !changed {
            break;
        }
    }
}

fn refine_single_position(
    i: usize,
    lattice: &[TokenLattice],
    columns: &[Vec<Candidate>],
    chosen: &mut [usize],
    boundary: &Candidate,
    sent_weights: &zemberek_pack::mphf::QuantizedWeightTableView<'_>,
    feat_buf: &mut [u64; FEATURE_BUFFER_SIZE],
) -> bool {
    let col = &lattice[i];
    if !col.is_ambiguous() {
        return false;
    }
    let mut best_ci = chosen[i];
    let mut best_score = f32::NEG_INFINITY;
    for (ci, cand) in col.candidates.iter().enumerate() {
        let s = score_candidate_in_sequence(
            i,
            chosen,
            columns,
            boundary,
            cand,
            col.unary_scores[ci],
            sent_weights,
            feat_buf,
        );
        if s > best_score {
            best_score = s;
            best_ci = ci;
        }
    }
    if best_ci != chosen[i] {
        chosen[i] = best_ci;
        true
    } else {
        false
    }
}

fn merge_confusion_candidates(
    candidates: &mut Vec<Candidate>,
    confusion: Option<ConfusionSetView<'_>>,
    input_original: &str,
    pin_dotless_i: bool,
) {
    let Some(cs) = confusion else {
        return;
    };
    for idx in 0..cs.len() {
        let surf = cs.surface(idx);
        if is_compatible_with_input(input_original, surf, pin_dotless_i) {
            add_if_surface_absent(candidates, Candidate::oov(surf, 0));
        }
    }
}

fn build_empirical_priors<'a>(
    candidates: &[Candidate],
    confusion: Option<ConfusionSetView<'a>>,
    token: &DeascToken,
    priors: &LexiconPriors<'a>,
) -> Vec<f32> {
    if confusion.is_some() || !token.has_apostrophe() || token.numeral_apostrophe {
        return build_empirical_priors_simple(candidates, confusion);
    }
    let apos = token.apostrophe_index.unwrap();
    let stem_ascii: String = token.ascii_lower.chars().take(apos).collect();
    let Some(stem_conf) = priors.get_confusion(&stem_ascii, token.casing, token.sentence_start)
    else {
        return build_empirical_priors_simple(candidates, None);
    };
    let mut scores = vec![0.0f32; candidates.len()];
    for (i, c) in candidates.iter().enumerate() {
        let c_chars: Vec<char> = c.surface.chars().collect();
        if c_chars.len() > apos && is_codec_apostrophe(c_chars[apos]) {
            let c_stem: String = c_chars[..apos].iter().collect();
            scores[i] = stem_conf.log_prob_of(&c_stem, if c.oov_fallback { -6.0 } else { -4.2 });
        }
    }
    scores
}

fn build_empirical_priors_simple(
    candidates: &[Candidate],
    confusion: Option<ConfusionSetView<'_>>,
) -> Vec<f32> {
    let k = candidates.len();
    let Some(cs) = confusion else {
        return vec![0.0f32; k];
    };
    let mut out = Vec::with_capacity(k);
    for c in candidates {
        out.push(cs.log_prob_of(&c.surface, if c.oov_fallback { -6.0 } else { -4.2 }));
    }
    out
}

fn is_obscure_only_match(morph_cands: &[Candidate]) -> bool {
    !morph_cands.iter().any(|c| {
        c.morpheme_group_count <= 3
            && c.pos != PrimaryPos::Duplicator
            && c.secondary_pos != SecondaryPos::Abbreviation
    })
}

fn resolve_candidate_surface(chosen: &Candidate, mode: CircumflexMode) -> Cow<'_, str> {
    match mode {
        CircumflexMode::Full => Cow::Borrowed(&chosen.surface),
        CircumflexMode::None => Cow::Owned(strip_circumflex(&chosen.surface)),
        CircumflexMode::DisambiguationOnly => {
            if is_disambiguating_circumflex_lemma(&chosen.lemma) {
                Cow::Borrowed(&chosen.surface)
            } else {
                Cow::Owned(strip_circumflex(&chosen.surface))
            }
        }
    }
}

fn is_disambiguating_circumflex_lemma(lemma: &str) -> bool {
    matches!(
        lemma,
        "kâr"
            | "hâlâ"
            | "âdet"
            | "âmâ"
            | "dâhi"
            | "dâhice"
            | "âşık"
            | "hâkim"
            | "âlem"
            | "yâr"
            | "şûra"
            | "tâbi"
            | "vâris"
            | "resmî"
            | "askerî"
            | "millî"
            | "tarihî"
            | "dinî"
            | "ilmî"
            | "edebî"
    )
}
