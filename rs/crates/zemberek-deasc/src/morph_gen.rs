use alloc::string::String;
use alloc::vec;
use alloc::vec::Vec;
use zemberek_core::{PrimaryPos, RootAttribute, SecondaryPos, TurkishAlphabet};
use zemberek_morph::{
    AnalysisItem, AnalyzerScratch, DynamicStemTrie, LexiconGraph, RuleBasedAnalyzer,
    SingleAnalysis, WordGenerator,
};
use zemberek_pack::morph_fst::MorphFstView;
use crate::candidate::{add_if_surface_absent, Candidate};
use crate::codec::{
    detect_casing_chars, has_letter_elongation, has_word_final_double_letter, high_harmonic_vowel,
    is_camel_case_identifier, is_compatible_with_input, is_front_vowel, is_java_digit,
    is_rounded_vowel, is_turkish_vowel, restore_numeral_apostrophe, spoken_turkish_number_stem,
    squeeze_elongations, to_ascii_lower, u16_len, vowel_index, Casing, DeascToken,
};
use crate::priors::LexiconPriors;

const PROTOTYPE_STEMS: [[&str; 3]; 8] = [
    ["oda", "dal", "at"],      // a
    ["kale", "bel", "et"],     // e
    ["kapı", "kıl", "kış"],    // ı
    ["kedi", "dil", "iş"],     // i
    ["koro", "kol", "not"],    // o
    ["banliyö", "göl", "çöp"], // ö
    ["kutu", "kul", "kuş"],    // u
    ["ütü", "gül", "üç"],      // ü
];

const GLUED_CLITICS: &[&str] = &[
    "misiniz", "musunuz", "miyiz", "muyuz", "miyim", "muyum", "misin", "musun", "midir", "mudur",
    "miydi", "muydu", "mi", "mu", "da", "de", "ta", "te",
];

#[derive(Clone, Copy)]
struct MorphemeMetaLookup {
    case_str: [Option<&'static str>; 256],
    poss_str: [Option<&'static str>; 256],
    is_a3pl: [bool; 256],
    is_neg_imp_without: [bool; 256],
    ques_id: u8,
    p3sg_id: u8,
}

impl MorphemeMetaLookup {
    fn from_fst(fst: &MorphFstView<'_>) -> Self {
        let mut m = Self {
            case_str: [None; 256],
            poss_str: [None; 256],
            is_a3pl: [false; 256],
            is_neg_imp_without: [false; 256],
            ques_id: 255,
            p3sg_id: fst.header.p3sg_morpheme_id,
        };
        let count = fst.header.morpheme_count as usize;
        for id in 0..count.min(256) {
            let s = fst.morpheme_id_str(id as u8);
            match s {
                "Acc" => m.case_str[id] = Some("Acc"),
                "Dat" => m.case_str[id] = Some("Dat"),
                "Loc" => m.case_str[id] = Some("Loc"),
                "Abl" => m.case_str[id] = Some("Abl"),
                "Gen" => m.case_str[id] = Some("Gen"),
                "Ins" => m.case_str[id] = Some("Ins"),
                "Equ" => m.case_str[id] = Some("Equ"),
                "P1sg" => m.poss_str[id] = Some("P1sg"),
                "P2sg" => m.poss_str[id] = Some("P2sg"),
                "P3sg" => m.poss_str[id] = Some("P3sg"),
                "P1pl" => m.poss_str[id] = Some("P1pl"),
                "P2pl" => m.poss_str[id] = Some("P2pl"),
                "P3pl" => m.poss_str[id] = Some("P3pl"),
                "A3pl" => m.is_a3pl[id] = true,
                "Neg" | "Imp" | "Without" => m.is_neg_imp_without[id] = true,
                "Ques" => m.ques_id = id as u8,
                _ => {}
            }
        }
        m
    }
}

/// Exact port of `MorphologicalCandidateGenerator`.
#[derive(Clone, Copy)]
pub struct MorphologicalCandidateGenerator<'a> {
    pub graph: LexiconGraph<'a>,
    pub fst: MorphFstView<'a>,
    pub formal_generator_allows_informal: bool,
    meta: MorphemeMetaLookup,
}

impl<'a> MorphologicalCandidateGenerator<'a> {
    pub fn new(
        graph: LexiconGraph<'a>,
        fst: MorphFstView<'a>,
        formal_generator_allows_informal: bool,
    ) -> Self {
        let meta = MorphemeMetaLookup::from_fst(&fst);
        Self {
            graph,
            fst,
            formal_generator_allows_informal,
            meta,
        }
    }

    #[inline]
    fn ascii_informal_analyzer(&self) -> RuleBasedAnalyzer<'a> {
        RuleBasedAnalyzer {
            graph: self.graph,
            fst: self.fst,
            allow_informal: true,
            ascii_tolerant: true,
        }
    }

    #[inline]
    fn generator_for(&self, sa: &SingleAnalysis) -> WordGenerator<'a> {
        let allow_inf =
            self.formal_generator_allows_informal || sa.contains_informal_morpheme(&self.fst);
        WordGenerator {
            graph: self.graph,
            fst: self.fst,
            allow_informal: allow_inf,
        }
    }

    pub fn generate(
        &self,
        token: &DeascToken,
        priors: &LexiconPriors<'a>,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Vec<Candidate> {
        let orig_u16_len = u16_len(&token.original);
        if token.immutable {
            return vec![Candidate::oov(&token.ascii_lower, orig_u16_len)];
        }

        if token.numeral_apostrophe && token.apostrophe_index.is_some_and(|ap| ap > 0) {
            let ap = token.apostrophe_index.unwrap();
            let restored = restore_numeral_apostrophe(token);
            if is_compatible_with_input(&token.original, &restored, token.has_pinned_turkish) {
                let stem_lemma: String = token.original.chars().take(ap).collect();
                return vec![Candidate::new(
                    restored,
                    stem_lemma,
                    PrimaryPos::Numeral,
                    SecondaryPos::None,
                    PrimaryPos::Numeral,
                    "Nom",
                    "Pnon",
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    ap,
                    1,
                )];
            }
        }

        if token.has_apostrophe() {
            return self.generate_apostrophe_word_candidates(token, priors, dynamic, scratch);
        }

        let mut out = Vec::with_capacity(4);
        if token.casing == Casing::Upper {
            if let Some(acronym) = priors.get_all_caps_turkish_acronym(&token.ascii_lower) {
                if is_compatible_with_input(&token.original, acronym, token.has_pinned_turkish) {
                    return vec![create_abbreviation_candidate(acronym)];
                }
            }
            if token.ascii_lower == "yok"
                && is_compatible_with_input(&token.original, "yök", token.has_pinned_turkish)
            {
                out.push(create_abbreviation_candidate("yök"));
            }
        }

        if priors.is_foreign_ascii_keep(&token.ascii_lower, token.casing)
            && is_compatible_with_input(
                &token.original,
                &token.ascii_lower,
                token.has_pinned_turkish,
            )
        {
            out.push(Candidate::oov(&token.ascii_lower, orig_u16_len));
            if out.len() == 1 {
                return out;
            }
        }

        let orig_for_pins = if token.has_pinned_turkish {
            Some(token.original.as_str())
        } else {
            None
        };
        let base =
            self.analyze_plain_with_fallbacks(&token.ascii_lower, orig_for_pins, dynamic, scratch);
        if !token.has_pinned_turkish && out.is_empty() && !base.is_empty() {
            return base;
        }
        for c in base {
            if !token.has_pinned_turkish
                || is_compatible_with_input(&token.original, &c.surface, true)
            {
                add_if_surface_absent(&mut out, c);
            }
        }
        out
    }

    pub fn analyze_plain_with_fallbacks(
        &self,
        ascii_lower: &str,
        original_for_pins: Option<&str>,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Vec<Candidate> {
        let base = self.analyze_plain_ascii_uncached(ascii_lower, dynamic, scratch);
        if !base.is_empty() && !is_only_proper_or_abbrev(&base) {
            return base;
        }
        let mut out = base;
        if out.is_empty() && has_letter_elongation(ascii_lower) {
            for max_run in 1..=2 {
                if !out.is_empty() {
                    break;
                }
                let squeezed = squeeze_elongations(ascii_lower, max_run);
                for mc in
                    self.analyze_plain_ascii_uncached(&squeezed.squeezed_ascii, dynamic, scratch)
                {
                    let projected = squeezed.project_to_original_length(&mc.surface);
                    if original_for_pins
                        .is_none_or(|orig| is_compatible_with_input(orig, &projected, true))
                    {
                        add_if_surface_absent(
                            &mut out,
                            copy_with_surface(&mc, projected, mc.informal, mc.question),
                        );
                    }
                }
            }
        }
        if ascii_lower.len() >= 5 {
            self.add_glued_clitic_candidates(
                ascii_lower,
                original_for_pins,
                &mut out,
                dynamic,
                scratch,
            );
        }
        if out.is_empty() && has_word_final_double_letter(ascii_lower) {
            let trimmed = &ascii_lower[..ascii_lower.len() - 1];
            for mc in self.analyze_plain_ascii_uncached(trimmed, dynamic, scratch) {
                if mc.morpheme_group_count > 2 || mc.informal {
                    continue;
                }
                let Some(last_ch) = mc.surface.chars().last() else {
                    continue;
                };
                let mut projected = mc.surface.clone();
                projected.push(last_ch);
                if original_for_pins
                    .is_none_or(|orig| is_compatible_with_input(orig, &projected, true))
                {
                    add_if_surface_absent(
                        &mut out,
                        copy_with_surface(&mc, projected, true, mc.question),
                    );
                }
            }
        }
        out
    }

    pub fn analyze_plain_ascii_uncached(
        &self,
        ascii_lower: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Vec<Candidate> {
        let analyses = self.analyze_ascii_informal(ascii_lower, dynamic, scratch);
        if analyses.is_empty() {
            return Vec::new();
        }
        let ascii_u16_len = u16_len(ascii_lower);
        let mut collector = CandidateCollector::new();
        let mut morph_ids_buf = Vec::with_capacity(16);

        for sa in &analyses {
            if sa.is_unknown() {
                continue;
            }
            let spos = sa.item.secondary_pos(&self.graph);
            if spos == SecondaryPos::Abbreviation && sa.morphemes.len() > 1 {
                continue;
            }
            if spos == SecondaryPos::ProperNoun && sa.pos(&self.fst) == PrimaryPos::Verb {
                continue;
            }
            if self.is_corrupted_dotless_proper_noun(&sa.item) {
                continue;
            }

            morph_ids_buf.clear();
            morph_ids_buf.extend(sa.morphemes.iter().map(|m| m.morpheme_id));

            for generated in
                self.generator_for(sa)
                    .generate_for_analysis_item(&sa.item, &morph_ids_buf, dynamic)
            {
                let surf = TurkishAlphabet::to_lower_tr(&generated.surface);
                if u16_len(&surf) != ascii_u16_len || to_ascii_lower(&surf) != ascii_lower {
                    continue;
                }
                collector.register(self, surf.clone(), sa, false, 0);

                if let Some(circ_surf) = self.project_circumflex_if_present(&surf, &sa.item) {
                    if to_ascii_lower(&circ_surf) == ascii_lower {
                        collector.register(self, circ_surf, sa, true, 0);
                    }
                }
            }
        }

        if collector.entries.is_empty() {
            return Vec::new();
        }
        let mut sorted: Vec<Candidate> = collector.entries.into_iter().map(|e| e.0).collect();
        sorted.sort_by(|a, b| {
            a.morpheme_group_count
                .cmp(&b.morpheme_group_count)
                .then_with(|| a.informal.cmp(&b.informal))
        });
        sorted
    }

    #[allow(clippy::too_many_arguments)]
    pub fn harmonize_suffix_after_stem(
        &self,
        chosen_stem_lower: &str,
        apostrophe_char: char,
        suffix_ascii: &str,
        pronunciation_source: &str,
        foreign_stem_len: usize,
        original_token: &str,
        pin_dotless_i: bool,
        is_upper_acronym: bool,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Vec<Candidate> {
        let mut morph_ids_buf = Vec::with_capacity(16);
        let suf_u16_len = u16_len(suffix_ascii);

        for proto in select_prototype_nouns(pronunciation_source, is_upper_acronym) {
            let proto_ascii = to_ascii_lower(proto);
            let mut probe = String::with_capacity(proto_ascii.len() + suffix_ascii.len());
            probe.push_str(&proto_ascii);
            probe.push_str(suffix_ascii);

            let analyses = self.analyze_ascii_informal(&probe, dynamic, scratch);
            let mut collector = CandidateCollector::new();
            let proto_u16_len = u16_len(proto);

            for sa in &analyses {
                if sa.is_unknown() || to_ascii_lower(sa.stem()) != proto_ascii {
                    continue;
                }
                let AnalysisItem::Static(item_id) = sa.item else {
                    continue;
                };
                morph_ids_buf.clear();
                morph_ids_buf.extend(sa.morphemes.iter().map(|m| m.morpheme_id));

                for r in self.generator_for(sa).generate(item_id, &morph_ids_buf) {
                    let surf = TurkishAlphabet::to_lower_tr(&r.surface);
                    if u16_len(&surf) == proto_u16_len + suf_u16_len && surf.starts_with(proto) {
                        let tail = &surf[proto.len()..];
                        let mut full =
                            String::with_capacity(chosen_stem_lower.len() + 4 + tail.len());
                        full.push_str(chosen_stem_lower);
                        full.push(apostrophe_char);
                        full.push_str(tail);
                        if is_compatible_with_input(original_token, &full, pin_dotless_i) {
                            collector.register(self, full, sa, false, foreign_stem_len);
                        }
                    }
                }
            }
            if !collector.entries.is_empty() {
                return collector.entries.into_iter().map(|e| e.0).collect();
            }
        }
        Vec::new()
    }

    fn generate_apostrophe_word_candidates(
        &self,
        token: &DeascToken,
        priors: &LexiconPriors<'a>,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Vec<Candidate> {
        let apos = token.apostrophe_index.unwrap();
        let orig_chars: Vec<char> = token.original.chars().collect();
        let ascii_chars: Vec<char> = token.ascii_lower.chars().collect();
        let apos_char = orig_chars[apos];
        let stem_orig: String = orig_chars[..apos].iter().collect();
        let stem_ascii: String = ascii_chars[..apos].iter().collect();
        let suffix_ascii: String = ascii_chars[apos + 1..].iter().collect();
        let stem_casing = detect_casing_chars(&orig_chars[..apos], apos);
        let pin_dotless_i = token.has_pinned_turkish;

        if stem_casing == Casing::Upper {
            if let Some(tr_acronym) = priors.get_all_caps_turkish_acronym(&stem_ascii) {
                let res = self.harmonize_suffix_after_stem(
                    tr_acronym,
                    apos_char,
                    &suffix_ascii,
                    tr_acronym,
                    0,
                    &token.original,
                    pin_dotless_i,
                    true,
                    dynamic,
                    scratch,
                );
                if !res.is_empty() {
                    return res;
                }
            }
        }

        if priors.is_foreign_ascii_keep(&stem_ascii, stem_casing) {
            let res = self.harmonize_suffix_after_stem(
                &stem_ascii,
                apos_char,
                &suffix_ascii,
                &stem_ascii,
                apos,
                &token.original,
                pin_dotless_i,
                stem_casing == Casing::Upper,
                dynamic,
                scratch,
            );
            if !res.is_empty() {
                return res;
            }
        }

        let mut without_quote_ascii = String::with_capacity(stem_ascii.len() + suffix_ascii.len());
        without_quote_ascii.push_str(&stem_ascii);
        without_quote_ascii.push_str(&suffix_ascii);
        let target_no_quote_u16_len = apos + (ascii_chars.len() - apos - 1);

        let analyses = self.analyze_ascii_informal(&without_quote_ascii, dynamic, scratch);
        let mut collector = CandidateCollector::new();
        let mut morph_ids_buf = Vec::with_capacity(16);

        for sa in &analyses {
            if sa.is_unknown() {
                continue;
            }
            let sa_stem_ascii = to_ascii_lower(sa.stem());
            if sa_stem_ascii.len() > stem_ascii.len() || !stem_ascii.starts_with(&sa_stem_ascii) {
                continue;
            }
            if sa_stem_ascii.len() < stem_ascii.len() && !sa.contains_morpheme(self.meta.p3sg_id) {
                continue;
            }
            if self.is_corrupted_dotless_proper_noun(&sa.item) {
                continue;
            }
            morph_ids_buf.clear();
            morph_ids_buf.extend(sa.morphemes.iter().map(|m| m.morpheme_id));

            for r in self
                .generator_for(sa)
                .generate_for_analysis_item(&sa.item, &morph_ids_buf, dynamic)
            {
                let surf_no_quote = TurkishAlphabet::to_lower_tr(&r.surface);
                if u16_len(&surf_no_quote) != target_no_quote_u16_len {
                    continue;
                }
                let with_apos = insert_char_at_u16(&surf_no_quote, apos, apos_char);
                if is_compatible_with_input(&token.original, &with_apos, pin_dotless_i) {
                    collector.register(self, with_apos, sa, false, 0);
                }
                if let Some(circ_no_quote) =
                    self.project_circumflex_if_present(&surf_no_quote, &sa.item)
                {
                    let circ_with_apos = insert_char_at_u16(&circ_no_quote, apos, apos_char);
                    if is_compatible_with_input(&token.original, &circ_with_apos, pin_dotless_i) {
                        collector.register(self, circ_with_apos, sa, true, 0);
                    }
                }
            }
        }
        if !collector.entries.is_empty() {
            return collector.entries.into_iter().map(|e| e.0).collect();
        }

        let stem_candidates = self.analyze_plain_ascii_uncached(&stem_ascii, dynamic, scratch);
        if !stem_candidates.is_empty() {
            let mut merged = Vec::with_capacity(stem_candidates.len());
            for sc in &stem_candidates {
                for hc in self.harmonize_suffix_after_stem(
                    &sc.surface,
                    apos_char,
                    &suffix_ascii,
                    &sc.surface,
                    0,
                    &token.original,
                    pin_dotless_i,
                    false,
                    dynamic,
                    scratch,
                ) {
                    add_if_surface_absent(&mut merged, hc);
                }
            }
            if !merged.is_empty() {
                return merged;
            }
        }

        if is_camel_case_identifier(&stem_orig) {
            return self.harmonize_suffix_after_stem(
                &stem_ascii,
                apos_char,
                &suffix_ascii,
                &stem_ascii,
                apos,
                &token.original,
                pin_dotless_i,
                false,
                dynamic,
                scratch,
            );
        }
        Vec::new()
    }

    fn analyze_ascii_informal(
        &self,
        ascii_query: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Vec<SingleAnalysis> {
        if ascii_query.is_empty() {
            return Vec::new();
        }
        let cleaned;
        let key = if ascii_query.contains('.') {
            cleaned = ascii_query.replace('.', "");
            if cleaned.is_empty() {
                ascii_query
            } else {
                cleaned.as_str()
            }
        } else {
            ascii_query
        };
        let mut res = self.ascii_informal_analyzer().analyze(key, dynamic, scratch);
        if res.len() == 1 && res[0].is_unknown() {
            res.clear();
        }
        res
    }

    fn add_glued_clitic_candidates(
        &self,
        ascii_lower: &str,
        original_for_pins: Option<&str>,
        out: &mut Vec<Candidate>,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) {
        for &clitic in GLUED_CLITICS {
            if ascii_lower.len() <= clitic.len() + 2 || !ascii_lower.ends_with(clitic) {
                continue;
            }
            let stem_ascii = &ascii_lower[..ascii_lower.len() - clitic.len()];
            let mut added = false;
            for stem_cand in self.analyze_plain_ascii_uncached(stem_ascii, dynamic, scratch) {
                let proto = select_word_prototype_noun(&stem_cand.surface);
                let front = is_front_vowel_word(proto);
                let rounded = is_rounded_vowel_word(proto);
                let restored_clitic = harmonize_clitic(clitic, front, rounded);
                let mut full =
                    String::with_capacity(stem_cand.surface.len() + restored_clitic.len());
                full.push_str(&stem_cand.surface);
                full.push_str(&restored_clitic);
                if original_for_pins
                    .is_none_or(|orig| is_compatible_with_input(orig, &full, true))
                    && add_if_surface_absent(
                        out,
                        copy_with_surface(&stem_cand, full, true, clitic.starts_with('m')),
                    )
                {
                    added = true;
                }
            }
            if added {
                return;
            }
        }
    }

    fn project_circumflex_if_present(
        &self,
        generated_lower: &str,
        item: &AnalysisItem,
    ) -> Option<String> {
        let norm_lemma = TurkishAlphabet::to_lower_tr(&item.normalized_lemma(&self.graph));
        if !TurkishAlphabet::contains_circumflex(&norm_lemma) {
            return None;
        }
        let mut gen_chars: Vec<char> = generated_lower.chars().collect();
        let mut changed = false;
        for (i, lc) in norm_lemma.chars().enumerate() {
            if i >= gen_chars.len() {
                break;
            }
            if matches!(lc, 'â' | 'î' | 'û') {
                let base = TurkishAlphabet::normalize_circumflex_char(lc);
                if gen_chars[i] == base {
                    gen_chars[i] = lc;
                    changed = true;
                }
            }
        }
        if changed {
            Some(gen_chars.into_iter().collect())
        } else {
            None
        }
    }

    fn is_corrupted_dotless_proper_noun(&self, item: &AnalysisItem) -> bool {
        if item.secondary_pos(&self.graph) != SecondaryPos::ProperNoun {
            return false;
        }
        let r = item.root(&self.graph);
        if !r.starts_with('ı') {
            return false;
        }
        !(r.starts_with("ığ")
            || r.starts_with("ıhl")
            || r.starts_with("ıld")
            || r.starts_with("ılgaz")
            || r.starts_with("ılgın")
            || r.starts_with("ılı")
            || r.starts_with("ırak")
            || r.starts_with("ıraz")
            || r.starts_with("ırg")
            || r.starts_with("ırmak")
            || r.starts_with("ısfahan")
            || r.starts_with("ısparta")
            || r.starts_with("ıstranca")
            || r.starts_with("ıstıranca")
            || r.starts_with("ışı")
            || r.starts_with("ıtr"))
    }

    fn preference_score(&self, sa: &SingleAnalysis, surface_has_circumflex: bool) -> i32 {
        let lemma_cow = sa.item.lemma(&self.graph);
        let lemma_u16_len = u16_len(&lemma_cow);
        let mut s = -(sa.group_count() as i32) * 10 + (8usize.min(lemma_u16_len) as i32) * 2;
        if sa.contains_informal_morpheme(&self.fst) {
            s -= 8;
        }
        let spos = sa.item.secondary_pos(&self.graph);
        if spos == SecondaryPos::ProperNoun {
            s -= 4;
        }
        if spos == SecondaryPos::Abbreviation {
            s -= 6;
        }
        if sa.item.root_attrs(&self.graph).contains(RootAttribute::Ext) {
            s -= 4;
        }
        let norm_lemma_low =
            TurkishAlphabet::to_lower_tr(&sa.item.normalized_lemma(&self.graph));
        let lemma_has_circ = TurkishAlphabet::contains_circumflex(&norm_lemma_low);
        if surface_has_circumflex == lemma_has_circ {
            s += 4;
        }
        s
    }

    fn build_candidate(
        &self,
        surface: String,
        sa: &SingleAnalysis,
        is_circumflex_variant: bool,
        foreign_stem_len: usize,
    ) -> Candidate {
        let ppos = sa.item.primary_pos(&self.graph);
        let spos = sa.item.secondary_pos(&self.graph);
        let mut case_id: &'static str = "Nom";
        let mut poss_id: &'static str = "Pnon";
        let mut plural = false;
        let mut neg_or_imp = false;
        let question = ppos == PrimaryPos::Question || sa.contains_morpheme(self.meta.ques_id);

        for md in &sa.morphemes {
            let mid = md.morpheme_id as usize;
            if let Some(cs) = self.meta.case_str[mid] {
                case_id = cs;
            } else if let Some(ps) = self.meta.poss_str[mid] {
                poss_id = ps;
            } else if self.meta.is_a3pl[mid] {
                plural = true;
            } else if self.meta.is_neg_imp_without[mid] {
                neg_or_imp = true;
            }
        }

        let mut lemma = TurkishAlphabet::to_lower_tr(&sa.item.lemma(&self.graph));
        if !is_circumflex_variant {
            lemma = TurkishAlphabet::normalize_circumflex(&lemma);
        }

        Candidate::new(
            surface,
            lemma,
            ppos,
            spos,
            sa.pos(&self.fst),
            case_id,
            poss_id,
            plural,
            sa.contains_informal_morpheme(&self.fst),
            neg_or_imp,
            question,
            is_circumflex_variant,
            false,
            foreign_stem_len,
            sa.group_count(),
        )
    }
}

struct CandidateCollector {
    entries: Vec<(Candidate, i32)>,
}

impl CandidateCollector {
    fn new() -> Self {
        Self {
            entries: Vec::with_capacity(4),
        }
    }

    fn register(
        &mut self,
        gen: &MorphologicalCandidateGenerator<'_>,
        surface: String,
        sa: &SingleAnalysis,
        is_circumflex_variant: bool,
        foreign_stem_len: usize,
    ) {
        let score_new = gen.preference_score(sa, is_circumflex_variant);
        if let Some(ex) = self.entries.iter_mut().find(|(c, _)| c.surface == surface) {
            if score_new >= ex.1 {
                *ex = (
                    gen.build_candidate(surface, sa, is_circumflex_variant, foreign_stem_len),
                    score_new,
                );
            }
            return;
        }
        self.entries.push((
            gen.build_candidate(surface, sa, is_circumflex_variant, foreign_stem_len),
            score_new,
        ));
    }
}

fn insert_char_at_u16(s: &str, char_idx: usize, ch: char) -> String {
    let mut out = String::with_capacity(s.len() + 4);
    for (i, c) in s.chars().enumerate() {
        if i == char_idx {
            out.push(ch);
        }
        out.push(c);
    }
    if char_idx == s.chars().count() {
        out.push(ch);
    }
    out
}

fn is_only_proper_or_abbrev(cands: &[Candidate]) -> bool {
    cands.iter().all(|c| {
        c.secondary_pos == SecondaryPos::ProperNoun
            || c.secondary_pos == SecondaryPos::Abbreviation
    })
}

fn harmonize_clitic(ascii_clitic: &str, front: bool, rounded: bool) -> String {
    if matches!(ascii_clitic, "da" | "de" | "ta" | "te") {
        let mut s = String::with_capacity(2);
        s.push(ascii_clitic.as_bytes()[0] as char);
        s.push(if front { 'e' } else { 'a' });
        return s;
    }
    let hv = high_harmonic_vowel(front, rounded);
    ascii_clitic
        .chars()
        .map(|c| if c == 'i' || c == 'u' { hv } else { c })
        .collect()
}

fn select_prototype_nouns(spoken: &str, is_upper_acronym: bool) -> Vec<&'static str> {
    if spoken.is_empty() {
        return vec!["kale"];
    }
    let chars: Vec<char> = spoken.chars().collect();
    let mut digit_start = chars.len();
    while digit_start > 0 && is_java_digit(chars[digit_start - 1]) {
        digit_start -= 1;
    }
    if digit_start < chars.len() {
        let tail: String = chars[digit_start..].iter().collect();
        let spoken_num = spoken_turkish_number_stem(&tail);
        return vec![select_word_prototype_noun(spoken_num)];
    }

    let lower = TurkishAlphabet::to_lower_tr(spoken);
    let word_proto = select_word_prototype_noun(&lower);
    if !is_upper_acronym {
        return vec![word_proto];
    }

    let last_char = lower.chars().last().unwrap_or('e');
    let letter_name_proto = if last_char == 'k' { "oda" } else { "kale" };
    if is_letter_by_letter_initialism(&lower) {
        if letter_name_proto == word_proto {
            vec![letter_name_proto]
        } else {
            vec![letter_name_proto, word_proto]
        }
    } else if word_proto == letter_name_proto {
        vec![word_proto]
    } else {
        vec![word_proto, letter_name_proto]
    }
}

fn is_letter_by_letter_initialism(lower: &str) -> bool {
    let chars: Vec<char> = lower.chars().collect();
    if chars.len() <= 2 {
        return true;
    }
    if !chars.iter().copied().any(is_turkish_vowel) {
        return true;
    }
    let n = chars.len();
    !is_turkish_vowel(chars[n - 1]) && !is_turkish_vowel(chars[n - 2])
}

fn select_word_prototype_noun(spoken: &str) -> &'static str {
    let lower = TurkishAlphabet::to_lower_tr(spoken);
    let mut v_class: Option<usize> = None;
    for ch in lower.chars().rev() {
        if let Some(vc) = vowel_index(ch) {
            v_class = Some(vc);
            break;
        }
    }
    let last_char = lower.chars().last().unwrap_or('e');
    let Some(vc) = v_class else {
        return if last_char == 'k' { "oda" } else { "kale" };
    };
    let ending_type = if is_turkish_vowel(last_char) {
        0
    } else if matches!(last_char, 'f' | 's' | 't' | 'k' | 'ç' | 'ş' | 'h' | 'p') {
        2
    } else {
        1
    };
    PROTOTYPE_STEMS[vc][ending_type]
}

fn is_front_vowel_word(s: &str) -> bool {
    for c in s.chars().rev() {
        if is_turkish_vowel(c) {
            return is_front_vowel(c);
        }
    }
    true
}

fn is_rounded_vowel_word(s: &str) -> bool {
    for c in s.chars().rev() {
        if is_turkish_vowel(c) {
            return is_rounded_vowel(c);
        }
    }
    false
}

fn create_abbreviation_candidate(lower_surface: &str) -> Candidate {
    Candidate::new(
        String::from(lower_surface),
        String::from(lower_surface),
        PrimaryPos::Noun,
        SecondaryPos::Abbreviation,
        PrimaryPos::Noun,
        "Nom",
        "Pnon",
        false,
        false,
        false,
        false,
        false,
        false,
        0,
        1,
    )
}

fn copy_with_surface(
    src: &Candidate,
    surface: String,
    informal: bool,
    question: bool,
) -> Candidate {
    Candidate::new(
        surface,
        src.lemma.clone(),
        src.pos,
        src.secondary_pos,
        src.last_group_pos,
        src.case_morpheme,
        src.possessive_morpheme,
        src.plural,
        informal,
        src.negative_or_imperative,
        question,
        src.circumflex_variant,
        false,
        0,
        src.morpheme_group_count,
    )
}
