use alloc::collections::BTreeMap;
use alloc::string::String;
use alloc::vec::Vec;
use core::cell::RefCell;
use core::cmp::Ordering;
use zemberek_core::{PrimaryPos, RootAttribute, SecondaryPos, TurkishAlphabet};
use zemberek_morph::{
    AnalyzerScratch, DynamicStemTrie, InformalAnalysisConverter, LexiconGraph, MorphologyConfig,
    RuleBasedAnalyzer, SingleAnalysis, TurkishMorphology, WordGenerator,
};
use zemberek_pack::clean_lm::CleanWordLmView;
use zemberek_pack::morph_fst::MorphFstView;
use crate::types::{
    compute_weighted_edit_cost, count_diacritic_diffs, slice_chars, take_first_chars, to_ascii,
    tr_capitalize, tr_lower, u16_len, HEAD_SYNTAX_FINITE_OR_CONVERB, HEAD_SYNTAX_NOMINAL,
    HEAD_SYNTAX_OTHER, HEAD_SYNTAX_PRON_ADV_CONJ, SRC_FORMAL_MORPH, SRC_INFORMAL_EXACT,
    SRC_INFORMAL_MORPH,
};

const MAX_CACHE_ENTRIES: usize = 4096;

const NEG_VERB_ASCII_INFIXES: &[&str] = &[
    "miyor", "muyor", "mayacak", "meyecek", "micak", "micek", "mucak", "mucek",
];

const NEG_VERB_ASCII_SUFFIXES: &[&str] = &[
    "miyo", "muyo", "miyolar", "muyolar", "mazlar", "mezler", "mazsin", "mezsin",
];

pub fn is_detached_num_suffix(s: &str) -> bool {
    matches!(
        s,
        "da" | "de"
            | "ta"
            | "te"
            | "dan"
            | "den"
            | "tan"
            | "ten"
            | "a"
            | "e"
            | "ya"
            | "ye"
            | "ı"
            | "i"
            | "u"
            | "ü"
            | "yı"
            | "yi"
            | "yu"
            | "yü"
            | "ın"
            | "in"
            | "un"
            | "ün"
            | "nın"
            | "nin"
            | "nun"
            | "nün"
            | "la"
            | "le"
            | "yla"
            | "yle"
            | "ar"
            | "er"
            | "şar"
            | "şer"
            | "ıncı"
            | "inci"
            | "uncu"
            | "üncü"
            | "ncı"
            | "nci"
            | "ncu"
            | "ncü"
            | "sini"
            | "sını"
            | "sunu"
            | "sünü"
            | "ini"
            | "ını"
            | "unu"
            | "ünü"
            | "si"
            | "sı"
            | "su"
            | "sü"
            | "nde"
            | "nda"
            | "nden"
            | "ndan"
            | "lı"
            | "li"
            | "lu"
            | "lü"
            | "lık"
            | "lik"
            | "luk"
            | "lük"
            | "lılar"
            | "liler"
            | "lular"
            | "lüler"
            | "lar"
            | "ler"
            | "dı"
            | "di"
            | "du"
            | "dü"
            | "tı"
            | "ti"
            | "tu"
            | "tü"
            | "dır"
            | "dir"
            | "dur"
            | "dür"
            | "tır"
            | "tir"
            | "tur"
            | "tür"
    )
}

pub fn is_detached_word_apostrophe_suffix(s: &str) -> bool {
    matches!(
        s,
        "da" | "de"
            | "ta"
            | "te"
            | "dan"
            | "den"
            | "tan"
            | "ten"
            | "a"
            | "e"
            | "ya"
            | "ye"
            | "ı"
            | "i"
            | "u"
            | "ü"
            | "yı"
            | "yi"
            | "yu"
            | "yü"
            | "ın"
            | "in"
            | "un"
            | "ün"
            | "nın"
            | "nin"
            | "nun"
            | "nün"
            | "ini"
            | "ını"
            | "unu"
            | "ünü"
            | "si"
            | "sı"
            | "su"
            | "sü"
            | "nde"
            | "nda"
            | "nden"
            | "ndan"
            | "la"
            | "le"
            | "yla"
            | "yle"
            | "lı"
            | "li"
            | "lu"
            | "lü"
            | "lık"
            | "lik"
            | "luk"
            | "lük"
            | "lılar"
            | "liler"
            | "lular"
            | "lüler"
            | "lar"
            | "ler"
            | "dı"
            | "di"
            | "du"
            | "dü"
            | "tı"
            | "ti"
            | "tu"
            | "tü"
    )
}

#[derive(Clone, Copy, Debug)]
pub struct MorphSummary {
    pub coarse_tag: i16,
    pub syntax_class: u8,
    pub verb_person: u8,
    pub verb_polarity: i8,
    pub is_regular: bool,
    pub is_proper_or_abbr: bool,
    pub is_strict_regular_common: bool,
}

#[derive(Clone, Debug)]
pub struct CachedMorphItem {
    pub fixed_proper_surface: Option<String>,
    pub norm_lower: String,
    pub source_mask: u32,
    pub suffix_precision: f32,
    pub edit_cost: f32,
    pub diacritic_diffs: u32,
}

#[derive(Clone, Copy)]
struct MorphIdTable {
    case_code: [u8; 256],
    verb_person: [u8; 256],
    is_neg_or_unable: [bool; 256],
    a3pl_id: u8,
}

impl MorphIdTable {
    fn from_fst(fst: &MorphFstView<'_>) -> Self {
        let mut t = Self {
            case_code: [0; 256],
            verb_person: [0; 256],
            is_neg_or_unable: [false; 256],
            a3pl_id: 255,
        };
        let n = (fst.header.morpheme_count as usize).min(256);
        for id in 0..n {
            match fst.morpheme_id_str(id as u8) {
                "Nom" => t.case_code[id] = 1,
                "Acc" => t.case_code[id] = 2,
                "Dat" => t.case_code[id] = 3,
                "Loc" => t.case_code[id] = 4,
                "Abl" => t.case_code[id] = 5,
                "Gen" => t.case_code[id] = 6,
                "Ins" => t.case_code[id] = 7,
                "A1sg" => t.verb_person[id] = 1,
                "A2sg" => t.verb_person[id] = 2,
                "A3sg" => t.verb_person[id] = 3,
                "A1pl" => t.verb_person[id] = 4,
                "A2pl" => t.verb_person[id] = 5,
                "A3pl" => {
                    t.verb_person[id] = 6;
                    t.a3pl_id = id as u8;
                }
                "Neg" | "Unable" => t.is_neg_or_unable[id] = true,
                _ => {}
            }
        }
        t
    }
}

#[derive(Default)]
struct SupportCaches {
    summary: BTreeMap<String, MorphSummary>,
    realize_word: BTreeMap<String, Option<String>>,
    morph_realize: BTreeMap<String, Vec<CachedMorphItem>>,
    question_vowel: BTreeMap<String, char>,
}

/// Exact zero-copy port of `zemberek.normalization.v2.LatticeMorphologySupport`.
pub struct LatticeMorphSupport<'a> {
    pub strict_morph: TurkishMorphology<'a>,
    exact_informal_morph: TurkishMorphology<'a>,
    informal_ascii_morph: TurkishMorphology<'a>,
    strict_generator: WordGenerator<'a>,
    informal_converter: InformalAnalysisConverter<'a>,
    clean_lm: CleanWordLmView<'a>,
    ids: MorphIdTable,
    caches: RefCell<SupportCaches>,
}

impl<'a> LatticeMorphSupport<'a> {
    pub fn new(
        graph: LexiconGraph<'a>,
        fst: MorphFstView<'a>,
        clean_lm: CleanWordLmView<'a>,
    ) -> Self {
        let strict_morph = TurkishMorphology {
            graph,
            fst,
            config: MorphologyConfig {
                use_informal: false,
                ignore_diacritics: false,
                use_unidentified_token_analyzer: true,
            },
        };
        let exact_informal_morph = TurkishMorphology {
            graph,
            fst,
            config: MorphologyConfig {
                use_informal: true,
                ignore_diacritics: false,
                use_unidentified_token_analyzer: true,
            },
        };
        let informal_ascii_morph = TurkishMorphology {
            graph,
            fst,
            config: MorphologyConfig {
                use_informal: true,
                ignore_diacritics: true,
                use_unidentified_token_analyzer: true,
            },
        };
        let strict_generator = WordGenerator {
            graph,
            fst,
            allow_informal: false,
        };
        let informal_converter = InformalAnalysisConverter::new(graph, fst);
        let ids = MorphIdTable::from_fst(&fst);

        Self {
            strict_morph,
            exact_informal_morph,
            informal_ascii_morph,
            strict_generator,
            informal_converter,
            clean_lm,
            ids,
            caches: RefCell::new(SupportCaches::default()),
        }
    }

    pub fn invalidate_caches(&self) {
        let mut c = self.caches.borrow_mut();
        c.summary.clear();
        c.realize_word.clear();
        c.morph_realize.clear();
        c.question_vowel.clear();
    }

    #[inline]
    pub fn a3pl_morpheme_id(&self) -> u8 {
        self.ids.a3pl_id
    }

    pub fn summarize_morph(
        &self,
        word_lower: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> MorphSummary {
        if dynamic.is_none() {
            if let Some(&cached) = self.caches.borrow().summary.get(word_lower) {
                return cached;
            }
        }
        let computed = self.compute_morph_summary(word_lower, dynamic, scratch);
        if dynamic.is_none() {
            let mut c = self.caches.borrow_mut();
            if c.summary.len() >= MAX_CACHE_ENTRIES {
                c.summary.clear();
            }
            c.summary.insert(String::from(word_lower), computed);
        }
        computed
    }

    fn compute_morph_summary(
        &self,
        word_lower: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> MorphSummary {
        let ap_opt = word_lower.find('\'');
        let query_cow: String;
        let query = match ap_opt {
            Some(ap) if ap > 0 => {
                query_cow = alloc::format!("{}{}", &word_lower[..ap], &word_lower[ap + 1..]);
                query_cow.as_str()
            }
            _ => word_lower,
        };
        let has_apos = matches!(ap_opt, Some(ap) if ap > 0);

        let wa = self.strict_morph.analyze_word(query, dynamic, scratch);
        let graph = &self.strict_morph.graph;
        let fst = &self.strict_morph.fst;

        let mut best_reg: Option<&SingleAnalysis> = None;
        let mut best_any: Option<&SingleAnalysis> = None;
        let mut seen_neg_verb = false;
        let mut seen_pos_verb = false;
        let mut seen_non_verb = false;

        for a in &wa.analyses {
            if a.is_unknown() {
                continue;
            }
            if best_any.is_none() {
                best_any = Some(a);
            }
            if is_regular_word(a, graph) && !is_ext_interjection(a, graph) {
                if best_reg.is_none() {
                    best_reg = Some(a);
                }
                if a.pos(fst) == PrimaryPos::Verb {
                    let has_neg = a
                        .morphemes
                        .iter()
                        .any(|m| self.ids.is_neg_or_unable[m.morpheme_id as usize]);
                    if has_neg {
                        seen_neg_verb = true;
                    } else {
                        seen_pos_verb = true;
                    }
                } else {
                    seen_non_verb = true;
                }
            }
        }

        let Some(chosen) = best_reg.or(best_any) else {
            let clean_attested = self.clean_lm.unigram_count(word_lower) >= 8;
            return MorphSummary {
                coarse_tag: 0,
                syntax_class: if clean_attested {
                    HEAD_SYNTAX_NOMINAL
                } else {
                    HEAD_SYNTAX_OTHER
                },
                verb_person: 0,
                verb_polarity: 0,
                is_regular: clean_attested,
                is_proper_or_abbr: has_apos,
                is_strict_regular_common: false,
            };
        };

        let mut polarity = 0i8;
        if !seen_non_verb && seen_neg_verb != seen_pos_verb {
            polarity = if seen_neg_verb { -1 } else { 1 };
        }

        let root_pos = chosen.item.primary_pos(graph);
        let spos = chosen.item.secondary_pos(graph);
        let final_pos = chosen.pos(fst);
        let mut last_case_code = 0u8;
        let mut person = 0u8;
        let is_verb = final_pos == PrimaryPos::Verb;

        for md in &chosen.morphemes {
            let cc = self.ids.case_code[md.morpheme_id as usize];
            if cc != 0 {
                last_case_code = cc;
            }
            if is_verb {
                let p = self.ids.verb_person[md.morpheme_id as usize];
                if p != 0 {
                    person = p;
                }
            }
        }

        let has_end = analysis_has_ending(chosen);
        let proper_no_quote_deriv = spos == SecondaryPos::ProperNoun
            && has_end
            && !proper_noun_takes_apostrophe_tdk(chosen, graph, fst);
        let takes_apostrophe = spos == SecondaryPos::Abbreviation
            || (spos == SecondaryPos::ProperNoun && !proper_no_quote_deriv);
        let has_reg = best_reg.is_some();

        MorphSummary {
            coarse_tag: encode_morph_tag(Some(final_pos), last_case_code),
            syntax_class: classify_syntax_role(root_pos, final_pos),
            verb_person: person,
            verb_polarity: polarity,
            is_regular: has_reg || proper_no_quote_deriv,
            is_proper_or_abbr: takes_apostrophe,
            is_strict_regular_common: !has_apos && has_reg,
        }
    }

    #[inline]
    pub fn has_regular_strict_analysis(
        &self,
        word_lower: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> bool {
        !word_lower.is_empty()
            && !word_lower.contains('\'')
            && self
                .summarize_morph(word_lower, dynamic, scratch)
                .is_strict_regular_common
    }

    pub fn is_strict_proper_or_abbr_only(
        &self,
        word_lower: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> bool {
        let wa = self.strict_morph.analyze_word(word_lower, dynamic, scratch);
        let graph = &self.strict_morph.graph;
        let mut has_prop = false;
        for a in &wa.analyses {
            if !a.is_known_lexical(graph) {
                continue;
            }
            let spos = a.item.secondary_pos(graph);
            if spos == SecondaryPos::ProperNoun || spos == SecondaryPos::Abbreviation {
                if !analysis_has_ending(a) {
                    has_prop = true;
                }
            } else {
                return false;
            }
        }
        has_prop
    }

    pub fn is_bare_imperative_verb_only(
        &self,
        word_lower: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> bool {
        let wa = self.strict_morph.analyze_word(word_lower, dynamic, scratch);
        let graph = &self.strict_morph.graph;
        let mut has_bare_verb = false;
        for a in &wa.analyses {
            if !a.is_known_lexical(graph) {
                continue;
            }
            if a.item.primary_pos(graph) == PrimaryPos::Verb && !analysis_has_ending(a) {
                has_bare_verb = true;
            } else {
                return false;
            }
        }
        has_bare_verb
    }

    pub fn is_verb_root_candidate(
        &self,
        stem_lower: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> bool {
        let wa = self
            .informal_ascii_morph
            .analyze_word(stem_lower, dynamic, scratch);
        let graph = &self.strict_morph.graph;
        wa.analyses.iter().any(|a| {
            a.is_known_lexical(graph)
                && a.item.primary_pos(graph) == PrimaryPos::Verb
                && !analysis_has_ending(a)
        })
    }

    pub fn is_known_strict_abbreviation(
        &self,
        word_lower: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> bool {
        let wa = self.strict_morph.analyze_word(word_lower, dynamic, scratch);
        let graph = &self.strict_morph.graph;
        wa.analyses.iter().any(|a| {
            a.is_known_lexical(graph)
                && !analysis_has_ending(a)
                && a.item.secondary_pos(graph) == SecondaryPos::Abbreviation
        })
    }

    pub fn has_raw_negative_verb_cue(
        &self,
        raw_lower: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> bool {
        let r_chars = u16_len(raw_lower);
        if r_chars < 5 {
            return false;
        }
        let ms = self.summarize_morph(raw_lower, dynamic, scratch);
        if ms.verb_polarity != 0 {
            return ms.verb_polarity < 0;
        }
        let asc = to_ascii(raw_lower);
        if NEG_VERB_ASCII_INFIXES.iter().any(|&inf| asc.contains(inf)) {
            return true;
        }
        if NEG_VERB_ASCII_SUFFIXES.iter().any(|&suf| asc.ends_with(suf)) {
            return true;
        }
        if (asc.ends_with("marlar") || asc.ends_with("merler")) && r_chars >= 8 {
            return self.is_verb_root_candidate(
                take_first_chars(raw_lower, r_chars - 6),
                dynamic,
                scratch,
            );
        }
        false
    }

    pub fn morph_realizations(
        &self,
        low: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Vec<CachedMorphItem> {
        if dynamic.is_none() {
            if let Some(cached) = self.caches.borrow().morph_realize.get(low) {
                return cached.clone();
            }
        }
        let items = self.compute_morph_realizations(low, dynamic, scratch);
        if dynamic.is_none() {
            let mut c = self.caches.borrow_mut();
            if c.morph_realize.len() >= MAX_CACHE_ENTRIES {
                c.morph_realize.clear();
            }
            c.morph_realize.insert(String::from(low), items.clone());
        }
        items
    }

    fn compute_morph_realizations(
        &self,
        low: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Vec<CachedMorphItem> {
        let mut out = Vec::with_capacity(6);
        for nl in self.collect_exact_informal_realizations(low, low, usize::MAX, dynamic, scratch) {
            let cost = compute_weighted_edit_cost(low, &nl);
            let diffs = count_diacritic_diffs(low, &nl);
            out.push(CachedMorphItem {
                fixed_proper_surface: None,
                norm_lower: nl,
                source_mask: SRC_INFORMAL_EXACT | SRC_INFORMAL_MORPH,
                suffix_precision: 0.93,
                edit_cost: cost,
                diacritic_diffs: diffs,
            });
        }

        let mut ascii_pool = Vec::with_capacity(8);
        let graph = &self.strict_morph.graph;
        let fst = &self.strict_morph.fst;

        self.for_each_ascii_realization(low, low, dynamic, scratch, |a, nl, informal| {
            if informal {
                ascii_pool.push(CachedMorphItem {
                    fixed_proper_surface: None,
                    norm_lower: String::from(nl),
                    source_mask: SRC_INFORMAL_MORPH,
                    suffix_precision: 0.82,
                    edit_cost: compute_weighted_edit_cost(low, nl),
                    diacritic_diffs: count_diacritic_diffs(low, nl),
                });
            } else {
                let is_prop = a.item.secondary_pos(graph) == SecondaryPos::ProperNoun;
                if nl != low || is_prop {
                    let fixed_prop = if is_prop {
                        format_proper_or_abbr_tdk(a, Some(nl), graph, fst)
                    } else {
                        None
                    };
                    let final_low = match &fixed_prop {
                        Some(fp) => tr_lower(fp),
                        None => String::from(nl),
                    };
                    let cost = compute_weighted_edit_cost(low, &final_low);
                    let diffs = count_diacritic_diffs(low, &final_low);
                    ascii_pool.push(CachedMorphItem {
                        fixed_proper_surface: fixed_prop,
                        norm_lower: final_low,
                        source_mask: SRC_FORMAL_MORPH,
                        suffix_precision: 0.0,
                        edit_cost: cost,
                        diacritic_diffs: diffs,
                    });
                }
            }
        });

        if ascii_pool.len() > 1 {
            ascii_pool.sort_by(|x, y| {
                let py = self.clean_lm.unigram_log_prob(Some(&y.norm_lower));
                let px = self.clean_lm.unigram_log_prob(Some(&x.norm_lower));
                py.partial_cmp(&px).unwrap_or(Ordering::Equal)
            });
        }
        out.extend(ascii_pool.into_iter().take(6));
        out
    }

    pub fn realize_valid_word_or_null(
        &self,
        candidate_lower: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Option<String> {
        if candidate_lower.is_empty() {
            return None;
        }
        if dynamic.is_none() {
            if let Some(cached) = self.caches.borrow().realize_word.get(candidate_lower) {
                return cached.clone();
            }
        }
        let computed = self.compute_realize_valid_word_or_null(candidate_lower, dynamic, scratch);
        if dynamic.is_none() {
            let mut c = self.caches.borrow_mut();
            if c.realize_word.len() >= MAX_CACHE_ENTRIES {
                c.realize_word.clear();
            }
            c.realize_word
                .insert(String::from(candidate_lower), computed.clone());
        }
        computed
    }

    fn compute_realize_valid_word_or_null(
        &self,
        candidate_lower: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Option<String> {
        if self.has_regular_strict_analysis(candidate_lower, dynamic, scratch) {
            return Some(String::from(candidate_lower));
        }
        if let Some(exact_inf) =
            self.first_exact_informal_or_null(candidate_lower, candidate_lower, dynamic, scratch)
        {
            return Some(exact_inf);
        }

        let mut best_regular: Option<String> = None;
        let mut best_regular_lp = -1000.0f32;
        let mut fallback_proper: Option<String> = None;
        let graph = &self.strict_morph.graph;

        self.for_each_ascii_realization(
            candidate_lower,
            candidate_lower,
            dynamic,
            scratch,
            |a, nl, _informal| {
                let spos = a.item.secondary_pos(graph);
                if spos == SecondaryPos::Abbreviation {
                    return;
                }
                if spos != SecondaryPos::ProperNoun {
                    let lp = self.clean_lm.unigram_log_prob(Some(nl));
                    if best_regular.is_none() || lp > best_regular_lp {
                        best_regular = Some(String::from(nl));
                        best_regular_lp = lp;
                    }
                } else if fallback_proper.is_none() {
                    fallback_proper = Some(String::from(nl));
                }
            },
        );
        best_regular.or(fallback_proper)
    }

    pub fn first_exact_informal_or_null(
        &self,
        query: &str,
        raw_for_diac_check: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Option<String> {
        self.collect_exact_informal_realizations(query, raw_for_diac_check, 1, dynamic, scratch)
            .into_iter()
            .next()
    }

    fn collect_exact_informal_realizations(
        &self,
        query: &str,
        raw_for_diac_check: &str,
        limit: usize,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Vec<String> {
        let mut out = Vec::with_capacity(limit.min(4));
        let wa = self
            .exact_informal_morph
            .analyze_word(query, dynamic, scratch);
        let graph = &self.strict_morph.graph;
        let fst = &self.strict_morph.fst;

        for a in &wa.analyses {
            if !a.is_known_lexical(graph)
                || !a.contains_informal_morpheme(fst)
                || is_ext_interjection(a, graph)
            {
                continue;
            }
            if let Some(conv) = self.informal_converter.convert_with_dynamic(query, a, dynamic) {
                if !strips_explicit_stem_diacritic(raw_for_diac_check, &conv.surface) {
                    out.push(tr_lower(&conv.surface));
                    if out.len() >= limit {
                        break;
                    }
                }
            }
        }
        out
    }

    pub fn for_each_ascii_realization<F>(
        &self,
        query: &str,
        raw_for_diac_check: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
        mut consumer: F,
    ) where
        F: FnMut(&SingleAnalysis, &str, bool),
    {
        let wa = self
            .informal_ascii_morph
            .analyze_word(query, dynamic, scratch);
        let graph = &self.strict_morph.graph;
        let fst = &self.strict_morph.fst;
        let mut morph_ids = Vec::with_capacity(16);

        for a in &wa.analyses {
            if !a.is_known_lexical(graph) || is_ext_interjection(a, graph) {
                continue;
            }
            if a.contains_informal_morpheme(fst) {
                if let Some(conv) = self.informal_converter.convert_with_dynamic(query, a, dynamic)
                {
                    if !strips_explicit_stem_diacritic(raw_for_diac_check, &conv.surface) {
                        let low = tr_lower(&conv.surface);
                        consumer(a, &low, true);
                    }
                }
            } else if a.item.secondary_pos(graph) != SecondaryPos::Abbreviation {
                morph_ids.clear();
                morph_ids.extend(a.morphemes.iter().map(|m| m.morpheme_id));
                for r in
                    self.strict_generator
                        .generate_for_analysis_item(&a.item, &morph_ids, dynamic)
                {
                    if TurkishAlphabet::equals_ignore_diacritics(query, &r.surface) {
                        let nl = tr_lower(&r.surface);
                        if !strips_explicit_diacritic(raw_for_diac_check, &nl) {
                            consumer(a, &nl, false);
                        }
                    }
                }
            }
        }
    }

    pub fn expected_turkish_question_vowel(
        &self,
        prev_word_lower: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> char {
        if prev_word_lower.is_empty() {
            return '\0';
        }
        if let Some(ap_b) = prev_word_lower.rfind('\'') {
            if ap_b == 0 || ap_b + 1 >= prev_word_lower.len() {
                return '\0';
            }
            let suf = &prev_word_lower[ap_b + 1..];
            if !is_detached_num_suffix(suf) && !is_detached_word_apostrophe_suffix(suf) {
                return '\0';
            }
            return vowel_to_question_high_vowel(last_turkish_vowel(suf));
        }
        if dynamic.is_none() {
            if let Some(&cached) = self.caches.borrow().question_vowel.get(prev_word_lower) {
                return cached;
            }
        }
        let computed = self.compute_expected_question_vowel(prev_word_lower, dynamic, scratch);
        if dynamic.is_none() {
            let mut c = self.caches.borrow_mut();
            if c.question_vowel.len() >= MAX_CACHE_ENTRIES {
                c.question_vowel.clear();
            }
            c.question_vowel
                .insert(String::from(prev_word_lower), computed);
        }
        computed
    }

    fn compute_expected_question_vowel(
        &self,
        prev_word_lower: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> char {
        let wa = self
            .strict_morph
            .analyze_word(prev_word_lower, dynamic, scratch);
        let graph = &self.strict_morph.graph;
        let mut has_inverse = false;
        let mut has_normal = false;

        for a in &wa.analyses {
            let attrs = a.item.root_attrs(graph);
            if !is_regular_word(a, graph)
                || attrs.contains(RootAttribute::LocaleEn)
                || is_ext_interjection(a, graph)
            {
                continue;
            }
            if !analysis_has_ending(a) && attrs.contains(RootAttribute::InverseHarmony) {
                has_inverse = true;
            } else {
                has_normal = true;
            }
        }
        if has_inverse == has_normal {
            return '\0';
        }
        let mut lv = last_turkish_vowel(prev_word_lower);
        if lv == '\0' {
            return '\0';
        }
        if has_inverse {
            lv = match lv {
                'a' | 'â' => 'e',
                'ı' => 'i',
                'o' => 'ö',
                'u' | 'û' => 'ü',
                _ => lv,
            };
        }
        vowel_to_question_high_vowel(lv)
    }
}

#[inline]
pub fn encode_morph_tag(pos: Option<PrimaryPos>, case_code: u8) -> i16 {
    let p = match pos {
        Some(pp) => (pp as i16) + 1,
        None => 0,
    };
    (p << 4) | (case_code as i16)
}

fn classify_syntax_role(root_pos: PrimaryPos, final_pos: PrimaryPos) -> u8 {
    if final_pos == PrimaryPos::Verb
        || (root_pos == PrimaryPos::Verb && final_pos == PrimaryPos::Adverb)
    {
        return HEAD_SYNTAX_FINITE_OR_CONVERB;
    }
    if matches!(
        final_pos,
        PrimaryPos::Pronoun
            | PrimaryPos::Adverb
            | PrimaryPos::Conjunction
            | PrimaryPos::PostPositive
    ) {
        return HEAD_SYNTAX_PRON_ADV_CONJ;
    }
    if matches!(
        final_pos,
        PrimaryPos::Noun | PrimaryPos::Adjective | PrimaryPos::Numeral
    ) {
        return HEAD_SYNTAX_NOMINAL;
    }
    HEAD_SYNTAX_OTHER
}

#[inline]
pub fn analysis_has_ending(a: &SingleAnalysis) -> bool {
    a.morphemes.iter().skip(1).any(|m| !m.surface.is_empty())
}

#[inline]
pub fn is_regular_word(a: &SingleAnalysis, graph: &LexiconGraph<'_>) -> bool {
    let spos = a.item.secondary_pos(graph);
    a.is_known_lexical(graph)
        && spos != SecondaryPos::ProperNoun
        && spos != SecondaryPos::Abbreviation
}

#[inline]
pub fn is_ext_interjection(a: &SingleAnalysis, graph: &LexiconGraph<'_>) -> bool {
    a.item.primary_pos(graph) == PrimaryPos::Interjection
        && a.item.root_attrs(graph).contains(RootAttribute::Ext)
}

pub fn proper_noun_takes_apostrophe_tdk(
    a: &SingleAnalysis,
    graph: &LexiconGraph<'_>,
    fst: &MorphFstView<'_>,
) -> bool {
    if !analysis_has_ending(a) || a.item.root_attrs(graph).contains(RootAttribute::NoQuote) {
        return false;
    }
    for md in a.morphemes.iter().skip(1) {
        if !md.surface.is_empty() {
            let m_str = fst.morpheme_id_str(md.morpheme_id);
            let is_deriv = fst.morphemes[md.morpheme_id as usize].is_derivational();
            return m_str != "A3pl" && !is_deriv;
        }
    }
    false
}

pub fn format_proper_or_abbr_tdk(
    a: &SingleAnalysis,
    generated_lower_or_null: Option<&str>,
    graph: &LexiconGraph<'_>,
    fst: &MorphFstView<'_>,
) -> Option<String> {
    let spos = a.item.secondary_pos(graph);
    let attrs = a.item.root_attrs(graph);
    let norm_lemma = a.item.normalized_lemma(graph);
    let ending = resolve_analysis_ending(a, &norm_lemma, generated_lower_or_null);

    if spos == SecondaryPos::Abbreviation {
        let upper_lemma = if attrs.contains(RootAttribute::LocaleEn) {
            norm_lemma.to_uppercase()
        } else {
            TurkishAlphabet::to_upper_tr(&norm_lemma)
        };
        return if ending.is_empty() {
            Some(upper_lemma)
        } else {
            Some(alloc::format!("{upper_lemma}'{ending}"))
        };
    }
    if spos == SecondaryPos::ProperNoun {
        let title_lemma = tr_capitalize(&norm_lemma);
        if ending.is_empty() {
            return Some(title_lemma);
        }
        if proper_noun_takes_apostrophe_tdk(a, graph, fst) {
            return Some(alloc::format!("{title_lemma}'{ending}"));
        }
        if let Some(gen_low) = generated_lower_or_null {
            return Some(tr_capitalize(gen_low));
        }
        let stem_surf: &str = if attrs.contains(RootAttribute::NoQuote) {
            &norm_lemma
        } else {
            a.stem()
        };
        return Some(tr_capitalize(&alloc::format!("{stem_surf}{ending}")));
    }
    None
}

fn resolve_analysis_ending(
    a: &SingleAnalysis,
    norm_lemma: &str,
    generated_lower_or_null: Option<&str>,
) -> String {
    let ending = a.ending();
    if let Some(gen_low) = generated_lower_or_null {
        if !ending.is_empty() {
            let lemma_low = tr_lower(norm_lemma);
            let gen_chars = u16_len(gen_low);
            let stem_len = if gen_low.starts_with(&lemma_low) {
                u16_len(&lemma_low)
            } else {
                gen_chars.min(u16_len(a.stem()))
            };
            if stem_len < gen_chars && gen_low.chars().count() == gen_chars {
                return String::from(slice_chars(gen_low, stem_len, gen_chars));
            }
        }
    }
    ending
}

fn vowel_to_question_high_vowel(v: char) -> char {
    match v {
        'e' | 'i' | 'î' => 'i',
        'ö' | 'ü' => 'ü',
        'o' | 'u' | 'û' => 'u',
        'a' | 'ı' | 'â' => 'ı',
        _ => '\0',
    }
}

#[inline]
pub fn last_turkish_vowel(s: &str) -> char {
    TurkishAlphabet::last_vowel(s).char_value
}

#[inline]
pub fn is_front_vowel(v: char) -> bool {
    matches!(v, 'e' | 'i' | 'ö' | 'ü' | 'î')
}

#[inline]
pub fn strips_explicit_diacritic(src_lower: &str, cand_lower: &str) -> bool {
    TurkishAlphabet::strips_explicit_turkish_diacritic(src_lower, cand_lower)
}

#[inline]
pub fn strips_explicit_stem_diacritic(src_lower: &str, cand_surface: &str) -> bool {
    let cl = tr_lower(cand_surface);
    let n = 3usize
        .min(src_lower.chars().count())
        .min(cl.chars().count());
    n > 0
        && TurkishAlphabet::strips_explicit_turkish_diacritic(
            take_first_chars(src_lower, n),
            take_first_chars(&cl, n),
        )
}

#[allow(dead_code)]
fn _keep_rba(_: RuleBasedAnalyzer<'_>) {}
