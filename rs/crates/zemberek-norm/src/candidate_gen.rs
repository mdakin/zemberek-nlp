use alloc::string::String;
use alloc::vec;
use alloc::vec::Vec;
use zemberek_core::{PrimaryPos, TurkishAlphabet};
use zemberek_deasc::LatticeDeasciifier;
use zemberek_morph::{AnalyzerScratch, DynamicStemTrie, LexiconGraph};
use zemberek_pack::clean_lm::{ln_f64, CleanWordLmView};
use zemberek_pack::morph_fst::MorphFstView;
use zemberek_pack::norm::NormKnowledgeView;
use zemberek_pack::{
    PackError, ZmbkPack, SECT_CLEAN_LM, SECT_MORPH_FST, SECT_NORM_KNOWLEDGE,
};
use zemberek_spell::CompactStemEndingSpellChecker;
use zemberek_tok::{span_tokenize, CasingShape, SpanToken, TokenType};
use crate::knowledge::NormKnowledge;
use crate::morph_support::{encode_morph_tag, strips_explicit_diacritic, LatticeMorphSupport};
use crate::shorthand::add_shorthand_candidates;
use crate::single_rewrite::{
    add_elongation_candidates, add_guarded_spell_candidates,
    add_proper_acronym_and_numeral_candidates, add_question_clitic_diacritic_twins,
    add_suffix_and_haplology_candidates, max_repeated_letter_run,
};
use crate::split_merge::{
    add_ambiguity_preserving_splits, add_multi_token_merges, is_loc_or_abl_particle,
    is_question_tail,
};
use crate::types::{
    clamp_lm, compute_weighted_edit_cost, contains_foreign_wxq, count_diacritic_diffs,
    has_ascii_ambiguous_chars, has_explicit_turkish_diacritics, has_upper_turkish_diacritics,
    is_all_digits, is_java_digit, is_personal_initial, is_sentence_boundary_punct, put_or_merge,
    to_ascii, tr_lower, u16_len, CandidateDraft, SentenceLattice, SlotDrafts, SpanArc,
    CTX_ADJ_DATE_NUM, CTX_AFTER_NUMBER, CTX_BEFORE_GAZETTEER_OR_HONORIFIC,
    CTX_PROMOTE_PROPER_IN_CONTEXT, CTX_SENT_TITLE_NOISY, NEXT_STATE_CLAUSE_END,
    NEXT_STATE_EXISTENTIAL, SRC_DEASCIIFIER, SRC_FORMAL_MORPH, SRC_IDENTITY, SRC_PARALLEL_1TO1,
    SRC_PARALLEL_SPLIT,
};

const MAX_ARCS_PER_START: usize = 14;

fn is_xiaomi_mi_product_tail(s: &str) -> bool {
    matches!(s, "band" | "box" | "note" | "mix" | "stick" | "pad" | "tv")
}

fn is_given_name_homograph(s: &str) -> bool {
    matches!(
        s,
        "ümit"
            | "metin"
            | "lale"
            | "çağrı"
            | "zafer"
            | "savaş"
            | "barış"
            | "deniz"
            | "umut"
            | "zeki"
            | "mimar"
            | "sultan"
            | "kemal"
            | "yaşar"
    )
}

fn is_gazetteer_title_head_prefix(s: &str) -> bool {
    matches!(
        s,
        "sokak"
            | "sokağı"
            | "cadde"
            | "caddesi"
            | "bulvar"
            | "bulvarı"
            | "mahalle"
            | "mahallesi"
            | "üniversite"
            | "üniversitesi"
            | "okulu"
            | "lisesi"
            | "ilkokulu"
            | "ortaokulu"
            | "klinik"
            | "kliniği"
            | "hastanesi"
            | "kompleksi"
            | "şampiyonası"
            | "barajı"
            | "köprü"
            | "köprüsü"
            | "dağı"
            | "gölü"
            | "nehri"
            | "adaları"
            | "ceza"
            | "içmesi"
    )
}

fn is_tdk_holiday_modifier_ext(s: &str) -> bool {
    matches!(
        s,
        "sevgililer"
            | "anneler"
            | "babalar"
            | "öğretmenler"
            | "kadınlar"
            | "cumhuriyet"
            | "zafer"
            | "gençlik"
    )
}

fn is_dotted_gazetteer_or_honorific(s: &str) -> bool {
    matches!(
        s,
        "mah."
            | "cad."
            | "sok."
            | "apt."
            | "malz."
            | "san."
            | "tic."
            | "ltd."
            | "şti."
            | "bey"
            | "hanım"
    )
}

fn is_hard_end_punct(s: &str) -> bool {
    matches!(s, "." | "!" | "?" | ".." | "..." | "…")
}

fn is_soft_boundary_punct(s: &str) -> bool {
    matches!(s, ":" | ";" | "-" | "–" | "—")
}

struct SentenceProfile {
    strict_known: Vec<bool>,
    sentence_all_caps: bool,
    sentence_title_noisy: bool,
    starts_cap: bool,
    sentence_ascii_ratio: f32,
    overt_subject_person: u8,
    has_question_mark: bool,
    deasc_by_tok: Option<Vec<Option<String>>>,
}

/// Exact `SmoothLm`-free port of `zemberek.normalization.v2.SpanCandidateGenerator`.
pub struct SpanCandidateGenerator<'a> {
    pub morph: LatticeMorphSupport<'a>,
    pub deasciifier: LatticeDeasciifier<'a>,
    pub spell_checker: CompactStemEndingSpellChecker<'a>,
    pub knowledge: NormKnowledge<'a>,
}

impl<'a> SpanCandidateGenerator<'a> {
    pub fn from_pack(
        pack: &ZmbkPack<'a>,
        dynamic: Option<&DynamicStemTrie>,
    ) -> Result<Self, PackError> {
        let graph = LexiconGraph::from_pack(pack)?;
        let (_, fst_bytes) = pack.section_bytes(SECT_MORPH_FST)?;
        let fst = MorphFstView::from_section_bytes(fst_bytes)?;
        let (_, lm_bytes) = pack.section_bytes(SECT_CLEAN_LM)?;
        let clean_lm = CleanWordLmView::from_section_bytes(lm_bytes)?;
        let (_, know_bytes) = pack.section_bytes(SECT_NORM_KNOWLEDGE)?;
        let know_view = NormKnowledgeView::from_section_bytes(know_bytes)?;

        let morph = LatticeMorphSupport::new(graph, fst, clean_lm);
        let deasciifier = LatticeDeasciifier::from_pack_for_normalizer(pack)?;
        let spell_checker = CompactStemEndingSpellChecker::from_pack(pack, &graph, dynamic)?;
        let knowledge = NormKnowledge::new(know_view, clean_lm);

        Ok(Self {
            morph,
            deasciifier,
            spell_checker,
            knowledge,
        })
    }

    pub fn rebuild_dynamic_caches(&mut self, dynamic: Option<&DynamicStemTrie>) {
        self.morph.invalidate_caches();
        self.deasciifier.invalidate_cache();
        self.spell_checker
            .rebuild_stems(&self.morph.strict_morph.graph, dynamic);
    }

    pub fn build_lattice(
        &self,
        raw_sentence: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> SentenceLattice {
        let tokens = span_tokenize(raw_sentence);
        let n = tokens.len();
        let mut outgoing: Vec<Vec<SpanArc>> = (0..n).map(|_| Vec::with_capacity(4)).collect();
        if n == 0 {
            return SentenceLattice {
                tokens,
                outgoing,
                sentence_ascii_ratio: 0.0,
                overt_subject_person: 0,
                sentence_all_caps: false,
                has_question_mark: false,
            };
        }

        let profile = self.analyze_sentence(raw_sentence, &tokens, dynamic, scratch);
        let cap_start_at = compute_sentence_starts(
            &tokens,
            profile.starts_cap,
            profile.sentence_all_caps,
            profile.sentence_title_noisy,
        );
        let casing_ctx = self.compute_token_casing_contexts(
            &tokens,
            profile.sentence_all_caps,
            profile.sentence_title_noisy,
            dynamic,
            scratch,
        );

        let punct_tag = encode_morph_tag(Some(PrimaryPos::Punctuation), 0);

        for i in 0..n {
            let tok = &tokens[i];
            let cap_here = cap_start_at[i];
            let tok_case_ctx = casing_ctx[i];

            if tok.is_immutable() {
                outgoing[i].push(SpanArc::plain(
                    i,
                    i + 1,
                    tok.surface.clone(),
                    tok.lower.clone(),
                    SRC_IDENTITY,
                    punct_tag,
                ));
                let mut merges = Vec::new();
                add_multi_token_merges(
                    self,
                    &tokens,
                    i,
                    cap_here,
                    profile.sentence_all_caps,
                    tok_case_ctx,
                    &mut merges,
                    dynamic,
                    scratch,
                );
                outgoing[i].extend(merges);
                continue;
            }

            let par_entry = self.knowledge.view.lookup_p1(&tok.lower);
            let slot = self.populate_slot_candidates(
                &tokens,
                i,
                &profile,
                cap_here,
                tok_case_ctx,
                par_entry,
                dynamic,
                scratch,
            );
            self.emit_pruned_slot_arcs(
                &tokens,
                i,
                tok,
                &slot,
                par_entry,
                &mut outgoing[i],
                dynamic,
                scratch,
            );
            let mut merges = Vec::new();
            add_multi_token_merges(
                self,
                &tokens,
                i,
                cap_here,
                profile.sentence_all_caps,
                tok_case_ctx,
                &mut merges,
                dynamic,
                scratch,
            );
            outgoing[i].extend(merges);
        }

        SentenceLattice {
            tokens,
            outgoing,
            sentence_ascii_ratio: profile.sentence_ascii_ratio,
            overt_subject_person: profile.overt_subject_person,
            sentence_all_caps: profile.sentence_all_caps,
            has_question_mark: profile.has_question_mark,
        }
    }

    fn analyze_sentence(
        &self,
        raw_sentence: &str,
        tokens: &[SpanToken],
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> SentenceProfile {
        let n = tokens.len();
        let mut alpha_words = 0usize;
        let mut all_upper_words = 0usize;
        let mut title_case_words = 0usize;
        let mut ascii_candidate_words = 0usize;
        let mut pure_ascii_words = 0usize;
        let mut explicit_diacritic_words = 0usize;
        let mut oov_ascii_words = 0usize;
        let mut first_alpha_seen = false;
        let mut first_alpha_cap = false;
        let mut has_question_mark = false;
        let mut overt_subject_person = 0u8;

        let mut strict_known = vec![false; n];
        for (i, t) in tokens.iter().enumerate() {
            if t.token_type == TokenType::Punctuation && t.surface.contains('?') {
                has_question_mark = true;
            }
            if t.is_immutable() {
                continue;
            }
            if t.shape != CasingShape::NonAlpha {
                alpha_words += 1;
                if t.shape == CasingShape::Upper {
                    all_upper_words += 1;
                } else if t.shape == CasingShape::Title {
                    title_case_words += 1;
                }
                if !first_alpha_seen {
                    first_alpha_seen = true;
                    first_alpha_cap = t.surface.chars().next().is_some_and(char::is_uppercase);
                }
            }
            let reg = self
                .morph
                .has_regular_strict_analysis(&t.lower, dynamic, scratch);
            strict_known[i] = reg;
            let explicit_diac = has_explicit_turkish_diacritics(&t.lower);
            if explicit_diac {
                explicit_diacritic_words += 1;
            }
            let upper_amb_i = t.has_upper_ascii_ambiguous_i();
            if has_ascii_ambiguous_chars(&t.lower) || upper_amb_i {
                ascii_candidate_words += 1;
                if upper_amb_i || !explicit_diac {
                    pure_ascii_words += 1;
                }
                if !reg {
                    oov_ascii_words += 1;
                }
            }
            if overt_subject_person == 0 {
                overt_subject_person = match t.lower.as_str() {
                    "ben" => 1,
                    "sen" => 2,
                    "biz" => 4,
                    "siz" => 5,
                    _ => 0,
                };
            }
        }

        let sentence_all_caps = alpha_words >= 3 && all_upper_words * 10 >= alpha_words * 8;
        let sentence_title_noisy =
            !sentence_all_caps && alpha_words >= 4 && title_case_words * 100 >= alpha_words * 45;
        let starts_cap = sentence_all_caps || first_alpha_cap;
        let sentence_ascii_ratio = if ascii_candidate_words > 0 {
            (pure_ascii_words as f32) / (ascii_candidate_words as f32)
        } else {
            0.0
        };

        let should_run_sentence_deasc = ascii_candidate_words > 0
            && (oov_ascii_words > 0
                || explicit_diacritic_words == 0
                || (pure_ascii_words >= 2 && pure_ascii_words * 2 >= alpha_words));
        let deasc_by_tok = if should_run_sentence_deasc {
            self.deasciify_sentence_tokens(
                raw_sentence,
                tokens,
                sentence_all_caps,
                pure_ascii_words > 0,
                dynamic,
                scratch,
            )
        } else {
            None
        };

        SentenceProfile {
            strict_known,
            sentence_all_caps,
            sentence_title_noisy,
            starts_cap,
            sentence_ascii_ratio,
            overt_subject_person,
            has_question_mark,
            deasc_by_tok,
        }
    }

    fn deasciify_sentence_tokens(
        &self,
        raw_sentence: &str,
        tokens: &[SpanToken],
        sentence_all_caps: bool,
        has_pure_ascii_words: bool,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Option<Vec<Option<String>>> {
        let n = tokens.len();
        let lowered_all_caps = sentence_all_caps
            && has_pure_ascii_words
            && !has_upper_turkish_diacritics(raw_sentence);
        let lowered_buf: String;
        let deasc_input: &str = if lowered_all_caps {
            lowered_buf = tr_lower(raw_sentence).replace('ı', "i");
            &lowered_buf
        } else {
            raw_sentence
        };
        let deasc_sent = self
            .deasciifier
            .deasciify_known_words(deasc_input, dynamic, scratch);
        if deasc_sent == deasc_input {
            return None;
        }
        let d_toks = span_tokenize(&deasc_sent);
        let in_toks_owned: Vec<SpanToken>;
        let in_toks: &[SpanToken] = if lowered_all_caps {
            in_toks_owned = span_tokenize(deasc_input);
            &in_toks_owned
        } else {
            tokens
        };
        if d_toks.len() != n || in_toks.len() != n {
            return None;
        }
        let mut out = vec![None; n];
        for i in 0..n {
            let d_low = &d_toks[i].lower;
            let in_low = &in_toks[i].lower;
            if d_low != in_low || self.morph.has_regular_strict_analysis(d_low, dynamic, scratch) {
                out[i] = Some(d_low.clone());
            }
        }
        Some(out)
    }

    #[allow(clippy::too_many_arguments)]
    fn populate_slot_candidates(
        &self,
        tokens: &[SpanToken],
        i: usize,
        profile: &SentenceProfile,
        cap_here: bool,
        tok_case_ctx: u32,
        par_entry: Option<zemberek_pack::norm::RewriteEntryView<'a>>,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> SlotDrafts {
        let tok = &tokens[i];
        let strict_known = profile.strict_known[i];
        let mut slot = SlotDrafts::new(cap_here, profile.sentence_all_caps);
        let expected_prev_q = self.compute_immediate_prev_question_vowel(
            tokens,
            profile.deasc_by_tok.as_deref(),
            i,
            profile.sentence_all_caps,
            dynamic,
            scratch,
        );

        let raw_keep_prob = par_entry.map_or(0.0, |pe| pe.keep_prob());
        let raw_keep_log_cnt =
            par_entry.map_or(0.0, |pe| ln_f64(1.0 + (pe.keep_count as f64)) as f32);

        // 1. Identity candidate
        let disharmonic_q = expected_prev_q != '\0'
            && is_standalone_question_clitic(&tok.lower)
            && tok.lower.chars().nth(1).is_some_and(|v| {
                v != expected_prev_q && is_same_rounding_question_vowel(v, expected_prev_q)
            });
        let id_par_prob = if disharmonic_q {
            raw_keep_prob.min(0.12)
        } else {
            raw_keep_prob
        };
        let id_par_log_cnt = if disharmonic_q {
            raw_keep_log_cnt.min(1.0)
        } else {
            raw_keep_log_cnt
        };
        let id_surf = self.knowledge.truecase(
            &tok.lower,
            tok.shape,
            &tok.surface,
            cap_here,
            profile.sentence_all_caps,
            &self.morph,
            tok_case_ctx,
            dynamic,
            scratch,
        );
        let id_src = SRC_IDENTITY
            | if strict_known { SRC_FORMAL_MORPH } else { 0 }
            | if par_entry.is_some_and(|pe| pe.keep_count > 0) {
                SRC_PARALLEL_1TO1
            } else {
                0
            };
        slot.add(
            id_surf,
            tok.lower.clone(),
            id_src,
            id_par_prob,
            id_par_log_cnt,
            0.0,
            0.0,
            0,
        );

        // 2. Parallel 1:N rewrite candidates
        if let Some(pe) = par_entry {
            let protect_upper_acro = tok.shape == CasingShape::Upper
                && !profile.sentence_all_caps
                && (self.knowledge.is_known_acronym_stem(&tok.lower)
                    || self
                        .morph
                        .is_known_strict_abbreviation(&tok.lower, dynamic, scratch));
            let protect_mid_title_phrase = !profile.sentence_all_caps
                && !profile.sentence_title_noisy
                && !cap_here
                && tok.shape == CasingShape::Title
                && i > 0
                && tokens[i - 1].shape == CasingShape::Title;

            for idx in 0..pe.len() {
                let (tc_norm, tc_cnt) = pe.target(idx);
                if tc_norm == tok.lower {
                    continue;
                }
                if (protect_upper_acro || protect_mid_title_phrase)
                    && !TurkishAlphabet::equals_ignore_diacritics(&tok.lower, tc_norm)
                {
                    continue;
                }
                let src_flag = if tc_norm.contains(' ') {
                    SRC_PARALLEL_SPLIT
                } else {
                    SRC_PARALLEL_1TO1
                };
                let surf = self.knowledge.truecase(
                    tc_norm,
                    tok.shape,
                    &tok.surface,
                    cap_here,
                    profile.sentence_all_caps,
                    &self.morph,
                    0,
                    dynamic,
                    scratch,
                );
                slot.add(
                    surf,
                    String::from(tc_norm),
                    src_flag,
                    pe.prob_of(tc_norm),
                    ln_f64(1.0 + (tc_cnt as f64)) as f32,
                    0.0,
                    compute_weighted_edit_cost(&tok.lower, tc_norm),
                    count_diacritic_diffs(&tok.lower, tc_norm),
                );
            }
        }

        if !tok.is_random_laugh() {
            // 3. Deasciifier & morphological realizations
            if let Some(ref deasc_vec) = profile.deasc_by_tok {
                if let Some(ref dl) = deasc_vec[i] {
                    if dl != &tok.lower && !strips_explicit_diacritic(&tok.lower, dl) {
                        let d_diffs = count_diacritic_diffs(&tok.lower, dl);
                        let surf = self.knowledge.truecase(
                            dl,
                            tok.shape,
                            &tok.surface,
                            cap_here,
                            profile.sentence_all_caps,
                            &self.morph,
                            0,
                            dynamic,
                            scratch,
                        );
                        slot.add_rule(
                            surf,
                            dl.clone(),
                            SRC_DEASCIIFIER,
                            0.0,
                            (d_diffs as f32) * 0.12,
                            d_diffs,
                        );
                    }
                }
            }

            let short_amb_ascii = u16_len(&tok.lower) <= 6
                && !has_explicit_turkish_diacritics(&tok.lower)
                && has_ascii_ambiguous_chars(&tok.lower);
            if (!strict_known || tok.has_upper_ascii_ambiguous_i() || short_amb_ascii)
                && u16_len(&tok.lower) >= 2
                && !tok.contains_digit()
            {
                for item in self.morph.morph_realizations(&tok.lower, dynamic, scratch) {
                    let surf = match item.fixed_proper_surface {
                        Some(fp) => fp,
                        None => self.knowledge.truecase(
                            &item.norm_lower,
                            tok.shape,
                            &tok.surface,
                            cap_here,
                            profile.sentence_all_caps,
                            &self.morph,
                            0,
                            dynamic,
                            scratch,
                        ),
                    };
                    slot.add_rule(
                        surf,
                        item.norm_lower,
                        item.source_mask,
                        item.suffix_precision,
                        item.edit_cost,
                        item.diacritic_diffs,
                    );
                }
            }

            let mid_sentence_title =
                !profile.sentence_all_caps && !cap_here && tok.shape == CasingShape::Title;
            if !mid_sentence_title {
                add_question_clitic_diacritic_twins(
                    &mut slot,
                    tok,
                    &tok.lower,
                    expected_prev_q,
                    raw_keep_prob,
                    raw_keep_log_cnt,
                    false,
                    &self.knowledge,
                    &self.morph,
                    dynamic,
                    scratch,
                );
            }

            if !strict_known {
                add_suffix_and_haplology_candidates(
                    &mut slot,
                    tok,
                    &self.knowledge,
                    &self.morph,
                    dynamic,
                    scratch,
                );
            }
            if !strict_known
                && !tok.contains_digit()
                && !(tok.shape == CasingShape::Upper && !profile.sentence_all_caps)
            {
                add_shorthand_candidates(
                    &mut slot,
                    tok,
                    &self.knowledge,
                    &self.morph,
                    dynamic,
                    scratch,
                );
            }
            add_proper_acronym_and_numeral_candidates(
                &mut slot,
                tok,
                &self.knowledge,
                &self.morph,
                dynamic,
                scratch,
            );
            if max_repeated_letter_run(&tok.lower) >= 2 {
                add_elongation_candidates(
                    &mut slot,
                    tok,
                    expected_prev_q,
                    strict_known,
                    &self.knowledge,
                    &self.morph,
                    dynamic,
                    scratch,
                );
            }
            add_ambiguity_preserving_splits(
                &mut slot,
                tok,
                strict_known,
                &self.knowledge,
                &self.morph,
                dynamic,
                scratch,
            );
            if self.should_run_spell_checker(
                &slot,
                tok,
                strict_known,
                profile.sentence_all_caps,
                par_entry,
                dynamic,
                scratch,
            ) {
                let protect_mid_title = !profile.sentence_all_caps
                    && !profile.sentence_title_noisy
                    && !cap_here
                    && tok.shape == CasingShape::Title;
                add_guarded_spell_candidates(
                    &mut slot,
                    tok,
                    protect_mid_title,
                    &self.spell_checker,
                    &self.knowledge,
                    &self.morph,
                    dynamic,
                    scratch,
                );
            }
        }

        if tok_case_ctx != 0 {
            let old_items = core::mem::take(&mut slot.items);
            let mut ctx_items = Vec::with_capacity(old_items.len());
            for d in old_items {
                let base_tc = self.knowledge.truecase(
                    &d.norm_lower,
                    tok.shape,
                    &tok.surface,
                    cap_here,
                    profile.sentence_all_caps,
                    &self.morph,
                    0,
                    dynamic,
                    scratch,
                );
                let surf = if d.norm_surface == base_tc {
                    self.knowledge.truecase(
                        &d.norm_lower,
                        tok.shape,
                        &tok.surface,
                        cap_here,
                        profile.sentence_all_caps,
                        &self.morph,
                        tok_case_ctx,
                        dynamic,
                        scratch,
                    )
                } else {
                    d.norm_surface
                };
                put_or_merge(
                    &mut ctx_items,
                    surf,
                    d.norm_lower,
                    d.source_mask,
                    d.parallel_prob,
                    d.parallel_log_count,
                    d.suffix_precision,
                    d.edit_cost,
                    d.diacritic_diffs,
                );
            }
            slot.items = ctx_items;
        }

        slot
    }

    #[allow(clippy::too_many_arguments)]
    fn should_run_spell_checker(
        &self,
        slot: &SlotDrafts,
        tok: &SpanToken,
        strict_known: bool,
        sentence_all_caps: bool,
        par_entry: Option<zemberek_pack::norm::RewriteEntryView<'a>>,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> bool {
        let low = tok.lower.as_str();
        let clen = u16_len(low);
        if strict_known
            || tok.contains_digit()
            || !(3..=18).contains(&clen)
            || par_entry.is_some_and(|pe| pe.total_count >= 8)
            || self.knowledge.is_known_acronym_stem(low)
            || contains_foreign_wxq(low)
            || slot.has_low_cost_morph_draft()
        {
            return false;
        }
        if !sentence_all_caps && tok.shape == CasingShape::Upper {
            return false;
        }
        if self
            .morph
            .is_strict_proper_or_abbr_only(low, dynamic, scratch)
            && (tok.shape != CasingShape::Lower || self.knowledge.clean_lm.unigram_count(low) >= 10)
        {
            return false;
        }
        if par_entry.is_none_or(|pe| pe.keep_count >= pe.total_count) {
            let clean_cnt = self.knowledge.clean_lm.unigram_count(low);
            if clen <= 4 && clean_cnt >= 12 {
                return false;
            }
            if clen <= 6 && !has_explicit_turkish_diacritics(low) && clean_cnt >= 35 {
                return false;
            }
        }
        true
    }

    #[allow(clippy::too_many_arguments)]
    fn emit_pruned_slot_arcs(
        &self,
        tokens: &[SpanToken],
        i: usize,
        tok: &SpanToken,
        slot: &SlotDrafts,
        par_entry: Option<zemberek_pack::norm::RewriteEntryView<'a>>,
        arcs_here: &mut Vec<SpanArc>,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) {
        let raw_par_total = par_entry.map_or(0, |pe| pe.total_count);
        let raw_neg_cue = self
            .morph
            .has_raw_negative_verb_cue(&tok.lower, dynamic, scratch);
        let next_lex = next_lexical_word_or_null(tokens, i + 1);
        let next_state =
            self.compute_next_syntactic_state(tokens, i + 1, next_lex, dynamic, scratch);
        let mut count = 0usize;
        for d in &slot.items {
            if count >= MAX_ARCS_PER_START && (d.source_mask & SRC_IDENTITY) == 0 {
                continue;
            }
            let mut arc = self.materialize_arc(i, i + 1, tok, next_lex, d, dynamic, scratch);
            arc.raw_parallel_total = raw_par_total;
            arc.raw_neg_verb_cue = raw_neg_cue;
            arc.next_lex_state = next_state;
            arcs_here.push(arc);
            count += 1;
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn materialize_arc(
        &self,
        start: usize,
        end: usize,
        raw_tok: &SpanToken,
        next_lex_lower: Option<&str>,
        d: &CandidateDraft,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> SpanArc {
        let first_w = d.first_word_lower();
        let last_w = d.last_word_lower();
        let ms_last = self.morph.summarize_morph(last_w, dynamic, scratch);
        let ms_first = if first_w == last_w {
            ms_last
        } else {
            self.morph.summarize_morph(first_w, dynamic, scratch)
        };
        let case_changed = d.norm_surface != raw_tok.surface && d.norm_lower == raw_tok.lower;
        let ap_added = !raw_tok.surface.contains('\'') && d.norm_surface.contains('\'');
        let ap_removed = raw_tok.surface.contains('\'') && !d.norm_surface.contains('\'');
        let strips_diac = strips_explicit_diacritic(&raw_tok.lower, &d.norm_lower);
        let phono = self.knowledge.view.score_word_phonotactics(last_w);
        let int_lm = if d.norm_lower.contains(' ') {
            self.compute_internal_lm(&d.norm_lower)
        } else {
            0.0
        };

        let mut arc = SpanArc::new(
            start,
            end,
            d.norm_surface.clone(),
            d.norm_lower.clone(),
            d.source_mask,
            ms_first.coarse_tag,
            ms_last.coarse_tag,
            ms_first.syntax_class,
            ms_last.verb_person,
            ms_last.is_regular && ms_first.is_regular,
            ms_last.is_proper_or_abbr || matches!(d.norm_surface.find('\''), Some(p) if p > 0),
            d.parallel_prob,
            d.parallel_log_count,
            d.suffix_precision,
            d.edit_cost,
            d.diacritic_diffs,
            case_changed,
            ap_added,
            ap_removed,
            strips_diac,
            phono,
            int_lm,
        );
        arc.verb_polarity = ms_last.verb_polarity;
        arc.raw_clean_count = self.knowledge.clean_lm.unigram_count(&raw_tok.lower);
        arc.raw_char_phono = self.knowledge.view.score_word_phonotactics(&raw_tok.lower);
        if d.norm_lower != raw_tok.lower {
            let uni_diff = self.knowledge.clean_lm.unigram_log_prob(Some(last_w))
                - self.knowledge.clean_lm.unigram_log_prob(Some(&raw_tok.lower));
            let bg_diff = match next_lex_lower {
                Some(nx) => {
                    self.knowledge.clean_lm.bigram_log_prob(Some(last_w), Some(nx))
                        - self
                            .knowledge
                            .clean_lm
                            .bigram_log_prob(Some(&raw_tok.lower), Some(nx))
                }
                None => 0.0,
            };
            arc.unary_clean_gain = uni_diff + bg_diff;
        }
        arc
    }

    #[allow(clippy::too_many_arguments)]
    pub fn build_direct_arc(
        &self,
        start: usize,
        end: usize,
        norm_surf: String,
        norm_low: String,
        source_mask: u32,
        par_prob: f32,
        par_log_cnt: f32,
        suf_prec: f32,
        edit_cost: f32,
        diac_diffs: u32,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> SpanArc {
        let sp_first = norm_low.find(' ');
        let sp_last = norm_low.rfind(' ');
        let first_w = match sp_first {
            Some(p) => &norm_low[..p],
            None => &norm_low,
        };
        let last_w = match sp_last {
            Some(p) => &norm_low[p + 1..],
            None => &norm_low,
        };
        let ms_last = self.morph.summarize_morph(last_w, dynamic, scratch);
        let ms_first = if first_w == last_w {
            ms_last
        } else {
            self.morph.summarize_morph(first_w, dynamic, scratch)
        };
        let phono = self.knowledge.view.score_word_phonotactics(last_w);
        let int_lm = if sp_first.is_some() {
            self.compute_internal_lm(&norm_low)
        } else {
            0.0
        };
        let ap_any = norm_surf.contains('\'');
        let ap_after_first = matches!(norm_surf.find('\''), Some(p) if p > 0);

        let mut arc = SpanArc::new(
            start,
            end,
            norm_surf,
            norm_low,
            source_mask,
            ms_first.coarse_tag,
            ms_last.coarse_tag,
            ms_first.syntax_class,
            ms_last.verb_person,
            ms_last.is_regular && ms_first.is_regular,
            ms_last.is_proper_or_abbr || ap_after_first,
            par_prob,
            par_log_cnt,
            suf_prec,
            edit_cost,
            diac_diffs,
            false,
            ap_any,
            false,
            false,
            phono,
            int_lm,
        );
        arc.verb_polarity = ms_last.verb_polarity;
        arc
    }

    // =========================================================================
    // Language Model (`SmoothLm`-free -> `CleanWordLm`), Clitic & Harmony Helpers
    // =========================================================================

    #[inline]
    pub fn lm_bigram(&self, prev_word_lower: Option<&str>, cur_word_lower: Option<&str>) -> f32 {
        self.knowledge
            .clean_lm
            .bigram_log_prob(prev_word_lower, cur_word_lower)
    }

    pub fn compute_internal_lm(&self, multi_word_lower: &str) -> f32 {
        let mut it = multi_word_lower.split(' ');
        let Some(mut prev) = it.next() else {
            return 0.0;
        };
        let mut s = 0.0f32;
        let mut any_next = false;
        for cur in it {
            any_next = true;
            s += self.lm_bigram(Some(prev), Some(cur));
            prev = cur;
        }
        if any_next { s } else { 0.0 }
    }

    pub fn compute_span_lm_comparison(
        &self,
        lat: &SentenceLattice,
        prev2_word: Option<&str>,
        prev_word: Option<&str>,
        arc: &SpanArc,
        out6: &mut [f32; 6],
    ) {
        let toks = &lat.tokens;
        let next_lex = next_lexical_word_or_null(toks, arc.end_idx);
        let clean_lm = &self.knowledge.clean_lm;

        let mut raw_smooth = 0.0f32;
        let mut raw_clean3 = 0.0f32;
        let mut r_prev2 = prev2_word;
        let mut r_prev1 = prev_word;
        for rt in &toks[arc.start_idx..arc.end_idx] {
            if rt.is_immutable() {
                if is_sentence_boundary_punct(rt) {
                    r_prev2 = None;
                    r_prev1 = None;
                }
                continue;
            }
            let rw = rt.lower.as_str();
            raw_smooth += clamp_lm(self.lm_bigram(r_prev1, Some(rw)));
            raw_clean3 += clean_lm.trigram_log_prob(r_prev2, r_prev1, Some(rw));
            r_prev2 = r_prev1;
            r_prev1 = Some(rw);
        }
        raw_smooth += clamp_lm(self.lm_bigram(r_prev1, next_lex));
        raw_clean3 += clean_lm.trigram_log_prob(r_prev2, r_prev1, next_lex);

        let mut cand_smooth = 0.0f32;
        let mut cand_clean3 = 0.0f32;
        let mut c_prev2 = prev2_word;
        let mut c_prev1 = prev_word;
        let clean_fwd3 = clean_lm.trigram_log_prob(c_prev2, c_prev1, Some(&arc.first_word_lower));

        if arc.word_count == 1 {
            let cw = arc.first_word_lower.as_str();
            cand_smooth += clamp_lm(self.lm_bigram(c_prev1, Some(cw)));
            cand_clean3 += clean_fwd3;
            c_prev2 = c_prev1;
            c_prev1 = Some(cw);
        } else {
            for cw in arc.norm_lower.split(' ') {
                if !cw.is_empty() {
                    cand_smooth += clamp_lm(self.lm_bigram(c_prev1, Some(cw)));
                    cand_clean3 += clean_lm.trigram_log_prob(c_prev2, c_prev1, Some(cw));
                    c_prev2 = c_prev1;
                    c_prev1 = Some(cw);
                }
            }
        }
        let clean_right3 = clean_lm.trigram_log_prob(c_prev2, c_prev1, next_lex);
        cand_smooth += clamp_lm(self.lm_bigram(c_prev1, next_lex));
        cand_clean3 += clean_right3;

        out6[0] = cand_smooth;
        out6[1] = raw_smooth;
        out6[2] = cand_clean3;
        out6[3] = raw_clean3;
        out6[4] = clean_fwd3;
        out6[5] = clean_right3;
    }

    pub fn compute_clitic_transparency_llr(&self, lat: &SentenceLattice, arc: &SpanArc) -> f32 {
        let toks = &lat.tokens;
        let next_lex = next_lexical_word_or_null(toks, arc.end_idx);
        let clean_lm = &self.knowledge.clean_lm;

        if arc.end_idx == arc.start_idx + 1 && arc.word_count == 2 {
            let lw = arc.last_word_lower.as_str();
            if lw == "de" || lw == "da" || is_question_tail(lw) {
                let raw_low = toks[arc.start_idx].lower.as_str();
                let skip_bg = clean_lm.bigram_log_prob(Some(&arc.first_word_lower), next_lex);
                let closed_bg = clean_lm.bigram_log_prob(Some(raw_low), next_lex);
                return (skip_bg - closed_bg).clamp(-12.0, 12.0);
            }
            return 0.0;
        }
        if arc.end_idx == arc.start_idx + 2 && arc.word_count == 1 && arc.start_idx + 1 < toks.len()
        {
            let t2_low = toks[arc.start_idx + 1].lower.as_str();
            if is_loc_or_abl_particle(t2_low) {
                let t1_low = toks[arc.start_idx].lower.as_str();
                let merged_bg = clean_lm.bigram_log_prob(Some(&arc.last_word_lower), next_lex);
                let skip_bg = clean_lm.bigram_log_prob(Some(t1_low), next_lex);
                return (merged_bg - skip_bg).clamp(-12.0, 12.0);
            }
        }
        0.0
    }

    pub fn compute_question_clitic_harmony(
        &self,
        lat: &SentenceLattice,
        prev1_lex: Option<&str>,
        arc: &SpanArc,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> f32 {
        if arc.end_idx == arc.start_idx + 1
            && arc.word_count == 2
            && is_question_tail(&arc.last_word_lower)
        {
            if let Some(ch1) = arc.last_word_lower.chars().nth(1) {
                let head_v = self.morph.expected_turkish_question_vowel(
                    &arc.first_word_lower,
                    dynamic,
                    scratch,
                );
                return if head_v == '\0' {
                    0.0
                } else if ch1 == head_v {
                    1.0
                } else {
                    -1.0
                };
            }
            return 0.0;
        }
        if arc.end_idx != arc.start_idx + 1
            || arc.word_count != 1
            || arc.start_idx == 0
            || !is_question_tail(&arc.norm_lower)
        {
            return 0.0;
        }
        let Some(prev_lex) = prev1_lex else {
            return 0.0;
        };
        let toks = &lat.tokens;
        let all_caps_sent = lat.sentence_all_caps;
        let cur_tok = &toks[arc.start_idx];
        if (cur_tok.shape == CasingShape::Title && !all_caps_sent)
            || is_xiaomi_mi_product_context(toks, arc.start_idx, arc.end_idx)
        {
            return 0.0;
        }
        let prev_tok = &toks[arc.start_idx - 1];
        if prev_tok.is_immutable()
            || (prev_tok.shape == CasingShape::Upper && !all_caps_sent)
            || (prev_tok.contains_digit() && !prev_lex.contains('\''))
        {
            return 0.0;
        }
        let expected_v = self
            .morph
            .expected_turkish_question_vowel(prev_lex, dynamic, scratch);
        let Some(ch1) = arc.norm_lower.chars().nth(1) else {
            return 0.0;
        };
        if expected_v == '\0' {
            0.0
        } else if ch1 == expected_v {
            1.0
        } else {
            -1.0
        }
    }

    fn compute_immediate_prev_question_vowel(
        &self,
        tokens: &[SpanToken],
        deasc_by_tok: Option<&[Option<String>]>,
        idx: usize,
        sentence_all_caps: bool,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> char {
        if idx == 0 {
            return '\0';
        }
        let cur = &tokens[idx];
        if (!sentence_all_caps && cur.shape == CasingShape::Title)
            || is_xiaomi_mi_product_context(tokens, idx, idx + 1)
        {
            return '\0';
        }
        let prev = &tokens[idx - 1];
        if prev.is_immutable() || (!sentence_all_caps && prev.shape == CasingShape::Upper) {
            return '\0';
        }
        if prev.contains_digit() && !prev.lower.contains('\'') && !prev.surface.contains('’') {
            return '\0';
        }
        let prev_query = prev.lower.as_str();
        if let Some(deasc_arr) = deasc_by_tok {
            if let Some(ref d_prev) = deasc_arr[idx - 1] {
                if d_prev != prev_query {
                    let deasc_exp = self
                        .morph
                        .expected_turkish_question_vowel(d_prev, dynamic, scratch);
                    if deasc_exp != '\0' {
                        return deasc_exp;
                    }
                }
            }
        }
        if cur.lower.contains('ı')
            && !has_explicit_turkish_diacritics(prev_query)
            && has_ascii_ambiguous_chars(prev_query)
        {
            return '\0';
        }
        self.morph
            .expected_turkish_question_vowel(prev_query, dynamic, scratch)
    }

    fn compute_next_syntactic_state(
        &self,
        tokens: &[SpanToken],
        next_idx: usize,
        next_lex_lower: Option<&str>,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> u8 {
        if next_idx >= tokens.len() {
            return NEXT_STATE_CLAUSE_END;
        }
        let nt = &tokens[next_idx];
        if matches!(nt.token_type, TokenType::Emoticon | TokenType::Emoji) {
            return NEXT_STATE_CLAUSE_END;
        }
        if nt.token_type == TokenType::Punctuation
            && (is_sentence_boundary_punct(nt) || nt.surface == ";" || nt.surface == ":")
        {
            return NEXT_STATE_CLAUSE_END;
        }
        let Some(nx) = next_lex_lower else {
            return NEXT_STATE_CLAUSE_END;
        };
        if nx == "var"
            || nx == "yok"
            || nx.starts_with("vardı")
            || nx.starts_with("yoktu")
            || nx.starts_with("mevcut")
        {
            return NEXT_STATE_EXISTENTIAL;
        }
        self.morph.summarize_morph(nx, dynamic, scratch).verb_person
    }

    fn compute_token_casing_contexts(
        &self,
        tokens: &[SpanToken],
        sentence_all_caps: bool,
        sentence_title_noisy: bool,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Vec<u32> {
        let n = tokens.len();
        let mut out = vec![0u32; n];
        let base_flags = if sentence_title_noisy {
            CTX_SENT_TITLE_NOISY
        } else {
            0
        };
        for i in 0..n {
            let mut flags = base_flags;
            let cur = &tokens[i];
            if cur.is_immutable() {
                out[i] = flags;
                continue;
            }
            let prev = if i > 0 { Some(&tokens[i - 1]) } else { None };
            let next = tokens.get(i + 1);
            let cur_low = cur.lower.as_str();

            if prev.is_some_and(|p| p.token_type == TokenType::Number || p.contains_digit()) {
                flags |= CTX_AFTER_NUMBER;
            }
            if prev.is_some_and(|p| is_calendar_day_or_year_number(&p.surface))
                || next.is_some_and(|nx| is_calendar_year_number(&nx.surface))
            {
                flags |= CTX_ADJ_DATE_NUM;
            }
            if next.is_some_and(|nx| {
                is_gazetteer_or_honorific_head(nx, sentence_all_caps, sentence_title_noisy)
            }) {
                flags |= CTX_BEFORE_GAZETTEER_OR_HONORIFIC;
            }
            if self.should_promote_proper_in_context(
                tokens,
                i,
                cur_low,
                prev,
                next,
                sentence_all_caps,
                sentence_title_noisy,
                dynamic,
                scratch,
            ) {
                flags |= CTX_PROMOTE_PROPER_IN_CONTEXT;
            }
            out[i] = flags;
        }
        out
    }

    #[allow(clippy::too_many_arguments)]
    fn should_promote_proper_in_context(
        &self,
        tokens: &[SpanToken],
        idx: usize,
        cur_low: &str,
        prev: Option<&SpanToken>,
        next: Option<&SpanToken>,
        sentence_all_caps: bool,
        sentence_title_noisy: bool,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> bool {
        if is_holiday_word_in_phrase(cur_low, prev, next) {
            return true;
        }
        let is_title_hon = cur_low == "hanım"
            || cur_low.starts_with("hanım'")
            || cur_low == "bey"
            || cur_low.starts_with("bey'");
        if is_title_hon {
            if let Some(p) = prev {
                if !p.is_immutable()
                    && ((!sentence_all_caps && !sentence_title_noisy && p.shape == CasingShape::Title)
                        || self
                            .morph
                            .is_strict_proper_or_abbr_only(&p.lower, dynamic, scratch))
                {
                    return true;
                }
            }
        }
        if is_given_name_homograph(cur_low) {
            if let Some(nx) = next {
                if !nx.is_immutable() {
                    if nx.lower == "bey" || nx.lower == "hanım" {
                        return true;
                    }
                    if !sentence_all_caps
                        && !sentence_title_noisy
                        && nx.shape == CasingShape::Title
                        && self.is_likely_proper_surname(nx, dynamic, scratch)
                    {
                        return true;
                    }
                }
            }
        }
        if is_personal_initial(cur_low) {
            let n = tokens.len();
            for t in &tokens[(idx + 1)..n.min(idx + 4)] {
                if is_personal_initial(&t.lower) {
                    if !sentence_all_caps && t.shape == CasingShape::Title {
                        return true;
                    }
                    continue;
                }
                return !sentence_all_caps
                    && !sentence_title_noisy
                    && t.shape == CasingShape::Title
                    && (self.is_likely_proper_surname(t, dynamic, scratch)
                        || u16_len(&t.lower) >= 3);
            }
        }
        false
    }

    fn is_likely_proper_surname(
        &self,
        nt: &SpanToken,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> bool {
        if matches!(nt.surface.find('\''), Some(p) if p > 0)
            || matches!(nt.surface.find('’'), Some(p) if p > 0)
        {
            return true;
        }
        self.morph
            .is_strict_proper_or_abbr_only(&nt.lower, dynamic, scratch)
            || self.knowledge.is_known_proper_or_acronym_stem(&nt.lower)
    }
}

pub fn next_lexical_word_or_null(toks: &[SpanToken], from_idx: usize) -> Option<&str> {
    for t in toks.get(from_idx..)? {
        if is_sentence_boundary_punct(t) {
            return None;
        }
        if !t.is_immutable() {
            return Some(t.lower.as_str());
        }
    }
    None
}

pub fn advance_lexical_history<'b>(
    lat: &'b SentenceLattice,
    prev2: Option<&'b str>,
    prev1: Option<&'b str>,
    arc: &'b SpanArc,
) -> (Option<&'b str>, Option<&'b str>) {
    if arc.word_count == 1 && arc.end_idx == arc.start_idx + 1 {
        let t = &lat.tokens[arc.start_idx];
        if t.is_immutable() {
            if is_sentence_boundary_punct(t) {
                return (None, None);
            }
            return (prev2, prev1);
        }
        return (prev1, Some(arc.last_word_lower.as_str()));
    }
    if arc.word_count == 1 {
        return (prev1, Some(arc.last_word_lower.as_str()));
    }
    if arc.word_count == 2 {
        return (
            Some(arc.first_word_lower.as_str()),
            Some(arc.last_word_lower.as_str()),
        );
    }
    let last_sp = arc.norm_lower.rfind(' ').unwrap_or(0);
    let prev_sp = arc.norm_lower[..last_sp]
        .rfind(' ')
        .map_or(0, |p| p + 1);
    (
        Some(&arc.norm_lower[prev_sp..last_sp]),
        Some(arc.last_word_lower.as_str()),
    )
}

pub fn is_standalone_question_clitic(word_lower: &str) -> bool {
    if u16_len(word_lower) < 2 {
        return false;
    }
    if is_question_tail(word_lower) {
        return true;
    }
    let asc = to_ascii(word_lower);
    asc == "miymis" || asc == "muymus"
}

fn is_same_rounding_question_vowel(v1: char, v2: char) -> bool {
    (matches!(v1, 'i' | 'ı') && matches!(v2, 'i' | 'ı'))
        || (matches!(v1, 'u' | 'ü') && matches!(v2, 'u' | 'ü'))
}

fn is_xiaomi_mi_product_context(toks: &[SpanToken], cur_idx: usize, next_idx: usize) -> bool {
    let Some(next_tok) = toks.get(next_idx) else {
        return false;
    };
    toks[cur_idx].lower == "mi"
        && (next_tok.contains_digit() || is_xiaomi_mi_product_tail(&next_tok.lower))
}

fn compute_sentence_starts(
    tokens: &[SpanToken],
    starts_cap: bool,
    sentence_all_caps: bool,
    sentence_title_noisy: bool,
) -> Vec<bool> {
    let n = tokens.len();
    let mut out = vec![false; n];
    let mut pending_start = starts_cap;
    let mut after_end_punct = false;
    let mut soft_boundary = false;

    for (i, t) in tokens.iter().enumerate() {
        if t.token_type == TokenType::Punctuation {
            if is_hard_end_punct(&t.surface) {
                after_end_punct = true;
            } else if is_soft_boundary_punct(&t.surface) {
                soft_boundary = true;
            }
            continue;
        }
        if matches!(t.token_type, TokenType::Emoji | TokenType::Emoticon) {
            if !pending_start {
                soft_boundary = true;
            }
            continue;
        }
        if t.is_immutable() {
            continue;
        }
        let starts_upper = t.surface.chars().next().is_some_and(char::is_uppercase);
        if pending_start {
            out[i] = true;
            pending_start = false;
        } else if after_end_punct {
            if sentence_all_caps || starts_upper {
                out[i] = true;
            }
        } else if soft_boundary && !sentence_all_caps && !sentence_title_noisy && starts_upper {
            out[i] = true;
        }
        after_end_punct = false;
        soft_boundary = false;
    }
    out
}

fn is_holiday_word_in_phrase(
    cur_low: &str,
    prev: Option<&SpanToken>,
    next: Option<&SpanToken>,
) -> bool {
    let is_head = |w: &str| w.starts_with("günü") || w.starts_with("bayramı");
    if is_tdk_holiday_modifier_ext(cur_low) {
        if let Some(nx) = next {
            return is_head(&nx.lower);
        }
    }
    is_head(cur_low) && prev.is_some_and(|p| is_tdk_holiday_modifier_ext(&p.lower))
}

fn is_calendar_day_or_year_number(s: &str) -> bool {
    let chars: Vec<char> = s.chars().collect();
    if chars.is_empty() {
        return false;
    }
    let mut start = 0usize;
    while start < chars.len() && (chars[start] == '(' || chars[start] == '[') {
        start += 1;
    }
    let mut end = start;
    while end < chars.len() && is_java_digit(chars[end]) {
        end += 1;
    }
    if end == start || end - start > 4 {
        return false;
    }
    let mut val = 0u32;
    for &ch in &chars[start..end] {
        let Some(d) = ch.to_digit(10) else {
            return false;
        };
        val = val * 10 + d;
    }
    if !((1..=31).contains(&val) || (1850..=2099).contains(&val)) {
        return false;
    }
    if end == chars.len() || (end + 1 == chars.len() && chars[end] == '.') {
        return true;
    }
    matches!(chars[end], '-' | '/') && end + 1 < chars.len() && is_java_digit(chars[end + 1])
}

fn is_calendar_year_number(s: &str) -> bool {
    if u16_len(s) < 4 {
        return false;
    }
    let mut clean = if s.ends_with(')') || s.ends_with(']') || s.ends_with('.') {
        &s[..s.len() - 1]
    } else {
        s
    };
    let ap_ch = match (clean.find('\''), clean.find('’')) {
        (Some(a), Some(b)) => Some(a.max(b)),
        (Some(a), None) => Some(a),
        (None, Some(b)) => Some(b),
        (None, None) => None,
    };
    if let Some(ap_b) = ap_ch {
        if u16_len(&clean[..ap_b]) == 4 {
            clean = &clean[..ap_b];
        }
    }
    if u16_len(clean) != 4 || !is_all_digits(clean) {
        return false;
    }
    let mut val = 0u32;
    for ch in clean.chars() {
        let Some(d) = ch.to_digit(10) else {
            return false;
        };
        val = val * 10 + d;
    }
    (1850..=2099).contains(&val)
}

fn is_gazetteer_or_honorific_head(
    nt: &SpanToken,
    sentence_all_caps: bool,
    sentence_title_noisy: bool,
) -> bool {
    let low = nt.lower.as_str();
    if is_dotted_gazetteer_or_honorific(low) {
        return true;
    }
    if !sentence_all_caps && !sentence_title_noisy && nt.shape == CasingShape::Title {
        let ap_b = match (low.find('\''), low.find('’')) {
            (Some(a), Some(b)) => Some(a.max(b)),
            (Some(a), None) => Some(a),
            (None, Some(b)) => Some(b),
            (None, None) => None,
        };
        let stem = match ap_b {
            Some(ap) if ap > 0 => &low[..ap],
            _ => low,
        };
        return stem == "mah" || is_gazetteer_title_head_prefix(stem);
    }
    false
}
