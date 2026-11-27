use alloc::string::{String, ToString};
use alloc::vec::Vec;
use zemberek_core::{
    compute_morphemic_attributes, PhonoMask, PrimaryPos, PronunciationGuesser, RootAttrMask,
    RootAttribute, SecondaryPos, TurkishAlphabet, TurkishNumbers, TurkishNumeralEndingMachine,
};
use zemberek_pack::stem_trie::root_slot;
use zemberek_tok::{Token, TokenType};
use crate::analysis::{AnalysisItem, SingleAnalysis};
use crate::analyzer::{AnalyzerScratch, RuleBasedAnalyzer, TempStemCandidate};
use crate::condition_vm::RootCondCtx;
use crate::dynamic::DynamicStemTrie;

/// Exact stateless port of `zemberek.morphology.analysis.UnidentifiedTokenAnalyzer`.
pub struct UnidentifiedTokenAnalyzer;

impl UnidentifiedTokenAnalyzer {
    pub fn analyze(
        analyzer: &RuleBasedAnalyzer<'_>,
        token: &Token<'_>,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Vec<SingleAnalysis> {
        let spos = guess_secondary_pos(token.token_type);
        let word = token.content;

        if spos == SecondaryPos::None {
            if word.contains('?') {
                return Vec::new();
            }
            if TurkishAlphabet::contains_digit(word) {
                let num_res = Self::try_numeral(analyzer, word, dynamic, scratch);
                if !num_res.is_empty() {
                    return num_res;
                }
            }
            let guessed = if word.contains('.') {
                SecondaryPos::Abbreviation
            } else {
                SecondaryPos::ProperNoun
            };
            return Self::analyze_word(analyzer, word, guessed, dynamic, scratch);
        }

        if spos == SecondaryPos::RomanNumeral {
            return Self::for_roman_numeral(analyzer, word, dynamic, scratch);
        }
        if spos == SecondaryPos::Date || spos == SecondaryPos::Clock {
            return Self::try_numeral(analyzer, word, dynamic, scratch);
        }
        if matches!(
            spos,
            SecondaryPos::HashTag | SecondaryPos::Email | SecondaryPos::Url | SecondaryPos::Mention
        ) {
            return Self::analyze_word(analyzer, word, spos, dynamic, scratch);
        }

        // Abbreviation / Emoticon / Emoji direct Noun analysis
        let normalized: String = word
            .chars()
            .filter(|&c| TurkishAlphabet::is_dictionary_letter(c))
            .collect();
        Self::run_with_temp_noun_item(
            analyzer,
            word,
            word,
            word,
            &normalized,
            spos,
            dynamic,
            scratch,
        )
    }

    pub fn analyze_word(
        analyzer: &RuleBasedAnalyzer<'_>,
        word: &str,
        spos: SecondaryPos,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Vec<SingleAnalysis> {
        if word.contains('\'') {
            Self::try_word_with_apostrophe(analyzer, word, spos, dynamic, scratch)
        } else if spos == SecondaryPos::ProperNoun || spos == SecondaryPos::Abbreviation {
            Vec::new()
        } else {
            Self::try_without_apostrophe(analyzer, word, spos, dynamic, scratch)
        }
    }

    fn try_without_apostrophe(
        analyzer: &RuleBasedAnalyzer<'_>,
        word: &str,
        spos: SecondaryPos,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Vec<SingleAnalysis> {
        let fgn = TurkishAlphabet::foreign_diacritics_to_turkish(word);
        let normalized = TurkishAlphabet::normalize(&fgn);
        let capitalize = spos == SecondaryPos::ProperNoun || spos == SecondaryPos::Abbreviation;
        let lemma = if capitalize {
            TurkishAlphabet::capitalize_tr(&normalized)
        } else {
            normalized.clone()
        };
        let no_dots = normalized.replace('.', "");
        let pron = guess_pronunciation(&no_dots);

        if !TurkishAlphabet::contains_vowel(&pron) {
            let item = AnalysisItem::Runtime {
                lemma,
                root: normalized,
                primary_pos: PrimaryPos::Noun,
                secondary_pos: spos,
                root_attrs: RootAttrMask::EMPTY,
                homonym_index: 0,
            };
            return alloc::vec![SingleAnalysis::dummy(word, item)];
        }

        Self::run_with_temp_noun_item(
            analyzer,
            &normalized,
            &lemma,
            &normalized,
            &pron,
            spos,
            dynamic,
            scratch,
        )
    }

    fn try_word_with_apostrophe(
        analyzer: &RuleBasedAnalyzer<'_>,
        word: &str,
        spos: SecondaryPos,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Vec<SingleAnalysis> {
        let normalized = TurkishAlphabet::normalize_apostrophe(word);
        let Some(index) = normalized.rfind('\'') else {
            return Vec::new();
        };
        if index == 0 || index + 1 >= normalized.len() {
            return Vec::new();
        }
        let stem = &normalized[..index];
        let ending = &normalized[index + 1..];
        let clean_stem = stem.replace('\'', "");
        let alnum_stem = (spos == SecondaryPos::ProperNoun || spos == SecondaryPos::Abbreviation)
            && TurkishAlphabet::contains_digit(&clean_stem);
        let stem_normalized = if alnum_stem {
            TurkishAlphabet::normalize_circumflex(&TurkishAlphabet::to_lower_tr(&clean_stem))
                .replace('.', "")
        } else {
            TurkishAlphabet::normalize(&clean_stem).replace('.', "")
        };
        let ending_normalized = TurkishAlphabet::normalize(ending);
        let pron = guess_stem_pronunciation(&clean_stem, &stem_normalized, spos);
        let capitalize = spos == SecondaryPos::ProperNoun || spos == SecondaryPos::Abbreviation;
        let pron_possible = TurkishAlphabet::contains_vowel(&pron);

        let lemma = if capitalize {
            TurkishAlphabet::capitalize_tr(stem)
        } else if pron_possible {
            String::from(stem)
        } else {
            String::from(word)
        };

        if !pron_possible {
            let item = AnalysisItem::Runtime {
                lemma,
                root: stem_normalized,
                primary_pos: PrimaryPos::Noun,
                secondary_pos: spos,
                root_attrs: RootAttrMask::EMPTY,
                homonym_index: 0,
            };
            return alloc::vec![SingleAnalysis::dummy(word, item)];
        }

        let mut to_parse = String::with_capacity(stem_normalized.len() + ending_normalized.len());
        to_parse.push_str(&stem_normalized);
        to_parse.push_str(&ending_normalized);

        let parses = Self::run_with_temp_noun_item(
            analyzer,
            &to_parse,
            &lemma,
            &stem_normalized,
            &pron,
            spos,
            dynamic,
            scratch,
        );
        parses
            .into_iter()
            .filter(|a| a.stem() == stem_normalized)
            .collect()
    }

    #[allow(clippy::too_many_arguments)]
    fn run_with_temp_noun_item(
        analyzer: &RuleBasedAnalyzer<'_>,
        to_parse: &str,
        lemma: &str,
        root: &str,
        pron: &str,
        spos: SecondaryPos,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Vec<SingleAnalysis> {
        // Check if an item with exact `(lemma, Noun, spos, index=0)` already exists in static lexicon!
        let item_exists_in_static = static_lexicon_contains_noun(&analyzer.graph, lemma, spos);

        let has_circ = TurkishAlphabet::contains_circumflex(to_parse);
        let search_in_owned;
        let search_in: &str = if has_circ {
            search_in_owned = TurkishAlphabet::normalize_circumflex(to_parse);
            &search_in_owned
        } else {
            to_parse
        };

        let matches_search_pass = search_in.starts_with(root)
            || (analyzer.ascii_tolerant
                && TurkishAlphabet::contains_ascii_related(root)
                && TurkishAlphabet::to_ascii(search_in)
                    .starts_with(&TurkishAlphabet::to_ascii(root)));
        let matches_circ_pass = has_circ && !analyzer.ascii_tolerant && to_parse.starts_with(root);

        let mut temp_vec: Vec<TempStemCandidate> = Vec::new();
        if !item_exists_in_static && (matches_search_pass || matches_circ_pass) {
            let slot = match spos {
                SecondaryPos::ProperNoun
                | SecondaryPos::Email
                | SecondaryPos::Url
                | SecondaryPos::HashTag
                | SecondaryPos::Mention => root_slot::NOUN_PROPER_S,
                SecondaryPos::Abbreviation => root_slot::NOUN_ABBRV_S,
                SecondaryPos::Emoticon | SecondaryPos::RomanNumeral => root_slot::NOUN_NO_SUFFIX_S,
                _ => root_slot::NOUN_S,
            };
            let abs_state = analyzer.graph.bridge.root_abstract_state(slot);
            let phono = compute_morphemic_attributes(pron, PhonoMask::EMPTY);
            if let Some(mat_entry) = analyzer.graph.bridge.resolve_entry_state(abs_state, phono.0) {
                let attrs = RootAttrMask::EMPTY.with(RootAttribute::Runtime);
                let root_chars = root.chars().count();
                let consumed_in_search = if has_circ {
                    search_in
                        .char_indices()
                        .nth(root_chars)
                        .map_or(search_in.len(), |(b, _)| b)
                } else {
                    root.len()
                };
                temp_vec.push(TempStemCandidate {
                    item: AnalysisItem::Runtime {
                        lemma: String::from(lemma),
                        root: String::from(root),
                        primary_pos: PrimaryPos::Noun,
                        secondary_pos: spos,
                        root_attrs: attrs,
                        homonym_index: 0,
                    },
                    stem_surface: String::from(root),
                    consumed_bytes: consumed_in_search,
                    mat_entry_state: mat_entry,
                    root_ctx: RootCondCtx {
                        root_attrs: attrs.0,
                        primary_pos: PrimaryPos::Noun as u8,
                        secondary_pos: spos as u8,
                        special_root_id: 0,
                        root_surface_id: 0,
                    },
                });
            }
        }

        analyzer.analyze_with_search_input(
            to_parse, search_in, has_circ, dynamic, &temp_vec, scratch,
        )
    }

    fn for_roman_numeral(
        analyzer: &RuleBasedAnalyzer<'_>,
        content: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Vec<SingleAnalysis> {
        let (stem, ending) = match content.find('\'') {
            Some(i) => (&content[..i], &content[i + 1..]),
            None => (content, ""),
        };
        let ss = stem.strip_suffix('.').unwrap_or(stem);
        let decimal = TurkishNumbers::roman_to_decimal(ss);
        if decimal == -1 {
            return Vec::new();
        }
        let dec_str = decimal.to_string();
        let base_lemma = TurkishNumeralEndingMachine::find(&dec_str);
        let lemma_opt = if stem.ends_with('.') {
            TurkishNumbers::get_ordinal(base_lemma)
        } else {
            Some(base_lemma)
        };
        let Some(lemma) = lemma_opt.filter(|l| !l.is_empty()) else {
            return Vec::new();
        };

        let res = Self::analyze_numeral_lemma(analyzer, lemma, ending, dynamic, scratch);
        let mut out = Vec::new();
        for re in res {
            if re.item.primary_pos(&analyzer.graph) != PrimaryPos::Numeral {
                continue;
            }
            let rt_item = AnalysisItem::Runtime {
                lemma: String::from(stem),
                root: String::from(stem),
                primary_pos: PrimaryPos::Numeral,
                secondary_pos: SecondaryPos::RomanNumeral,
                root_attrs: RootAttrMask::EMPTY.with(RootAttribute::Runtime),
                homonym_index: 0,
            };
            out.push(re.copy_for(rt_item, stem));
        }
        out
    }

    fn try_numeral(
        analyzer: &RuleBasedAnalyzer<'_>,
        raw_token: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Vec<SingleAnalysis> {
        let s = TurkishAlphabet::to_lower_tr(raw_token);
        let (stem, ending) = split_numeral_stem_ending(&s);
        let spos_list = classify_numeral(stem);
        if spos_list.is_empty() {
            return Vec::new();
        }

        let lemma_opt = if let Some(ss) = stem.strip_suffix('.') {
            TurkishNumbers::get_ordinal(TurkishNumeralEndingMachine::find(ss))
        } else {
            Some(TurkishNumeralEndingMachine::find(stem))
        };
        let Some(lemma) = lemma_opt.filter(|l| !l.is_empty()) else {
            return Vec::new();
        };

        let mut results = Vec::new();
        let mut default_analyses: Option<Vec<SingleAnalysis>> = None;

        for spos in spos_list {
            let res_vec: Vec<SingleAnalysis>;
            let res_ref: &[SingleAnalysis] = if spos == SecondaryPos::Clock
                && stem.ends_with("00")
                && (4..=5).contains(&stem.chars().count())
            {
                let cut = stem
                    .char_indices()
                    .rev()
                    .nth(2)
                    .map_or(0, |(idx, _)| idx);
                let eff = TurkishNumeralEndingMachine::find(&stem[..cut]);
                res_vec = Self::analyze_numeral_lemma(analyzer, eff, ending, dynamic, scratch);
                &res_vec
            } else {
                if default_analyses.is_none() {
                    default_analyses = Some(Self::analyze_numeral_lemma(
                        analyzer, lemma, ending, dynamic, scratch,
                    ));
                }
                default_analyses.as_ref().unwrap()
            };

            for re in res_ref {
                if re.item.primary_pos(&analyzer.graph) != PrimaryPos::Numeral {
                    continue;
                }
                let rt_item = AnalysisItem::Runtime {
                    lemma: String::from(stem),
                    root: String::from(stem),
                    primary_pos: PrimaryPos::Numeral,
                    secondary_pos: spos,
                    root_attrs: RootAttrMask::EMPTY.with(RootAttribute::Runtime),
                    homonym_index: 0,
                };
                results.push(re.copy_for(rt_item, stem));
            }
        }
        results
    }

    fn analyze_numeral_lemma(
        analyzer: &RuleBasedAnalyzer<'_>,
        lemma: &str,
        ending: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Vec<SingleAnalysis> {
        let starts_vowel = ending.chars().next().is_some_and(TurkishAlphabet::is_vowel);
        let mut to_parse = String::with_capacity(lemma.len() + ending.len());
        if starts_vowel && lemma == "dört" {
            to_parse.push_str("dörd");
        } else {
            to_parse.push_str(lemma);
        }
        to_parse.push_str(ending);
        analyzer.analyze(&to_parse, dynamic, scratch)
    }
}

fn static_lexicon_contains_noun(
    graph: &crate::lexicon::LexiconGraph<'_>,
    lemma: &str,
    spos: SecondaryPos,
) -> bool {
    let root_guess = TurkishAlphabet::normalize_circumflex(&TurkishAlphabet::to_lower_tr(lemma))
        .replace(['-', '\'', '.'], "");
    let mut hits = Vec::new();
    graph.find_prefix_matches(&root_guess, false, None, &mut hits);
    for m in hits {
        if m.consumed_input_bytes == root_guess.len() {
            let item = graph.item(m.target.item_id());
            if item.primary_pos() == PrimaryPos::Noun
                && item.secondary_pos() == spos
                && item.homonym_index() == 0
                && item.lemma() == lemma
            {
                return true;
            }
        }
    }
    false
}

fn guess_secondary_pos(ty: TokenType) -> SecondaryPos {
    match ty {
        TokenType::Email => SecondaryPos::Email,
        TokenType::URL => SecondaryPos::Url,
        TokenType::HashTag => SecondaryPos::HashTag,
        TokenType::Mention => SecondaryPos::Mention,
        TokenType::Emoji | TokenType::Emoticon => SecondaryPos::Emoticon,
        TokenType::RomanNumeral => SecondaryPos::RomanNumeral,
        TokenType::Abbreviation => SecondaryPos::Abbreviation,
        TokenType::Date => SecondaryPos::Date,
        TokenType::Time => SecondaryPos::Clock,
        _ => SecondaryPos::None,
    }
}

fn guess_pronunciation(stem: &str) -> String {
    if !TurkishAlphabet::contains_vowel(stem) {
        PronunciationGuesser::to_turkish_letter_pronunciations(stem)
    } else {
        PronunciationGuesser::replace_english_specific_chars(stem)
    }
}

fn guess_stem_pronunciation(raw_stem: &str, stem_normalized: &str, spos: SecondaryPos) -> String {
    if (spos == SecondaryPos::ProperNoun || spos == SecondaryPos::Abbreviation)
        && TurkishAlphabet::contains_digit(raw_stem)
    {
        let cleaned = TurkishAlphabet::to_lower_tr(raw_stem).replace('.', "");
        return PronunciationGuesser::to_turkish_letter_pronunciations(&cleaned);
    }
    if let Some(last_dot) = raw_stem.rfind('.') {
        if last_dot + 1 < raw_stem.len() {
            let last_seg = TurkishAlphabet::normalize(&raw_stem[last_dot + 1..]);
            if !last_seg.is_empty() && !TurkishAlphabet::contains_vowel(&last_seg) {
                let prefix = TurkishAlphabet::normalize(&raw_stem[..last_dot]).replace('.', "");
                let mut out = prefix;
                out.push_str(&PronunciationGuesser::to_turkish_letter_pronunciations(
                    &last_seg,
                ));
                return out;
            }
        }
    }
    if spos == SecondaryPos::Abbreviation {
        return PronunciationGuesser::guess_for_abbreviation(stem_normalized);
    }
    guess_pronunciation(stem_normalized)
}

fn split_numeral_stem_ending(s: &str) -> (&str, &str) {
    if let Some(i) = s.find('\'') {
        return (&s[..i], &s[i + 1..]);
    }
    let mut cut = s.len();
    for (idx, c) in s.char_indices().rev() {
        if c == '.' || c.is_ascii_digit() {
            break;
        }
        cut = idx;
    }
    (&s[..cut], &s[cut..])
}

/// Zero-regex port of `UnidentifiedTokenAnalyzer.classifyNumeral`.
fn classify_numeral(stem: &str) -> Vec<SecondaryPos> {
    let mut out = Vec::with_capacity(2);
    if !stem.is_ascii() || stem.is_empty() {
        return out;
    }
    let b = stem.as_bytes();
    let without_sign = if matches!(b[0], b'+' | b'-') {
        &b[1..]
    } else {
        b
    };

    // 1. CARDINAL: ^[+\-]?\d+$
    if is_digits(without_sign) {
        out.push(SecondaryPos::Cardinal);
    }
    // 2. ORDINAL: ^[+\-]?\d+\.$
    if without_sign.ends_with(b".") && is_digits(&without_sign[..without_sign.len() - 1]) {
        out.push(SecondaryPos::Ordinal);
    }
    // 3. RANGE: ^[+\-]?\d+-\d+$
    if let Some(dash) = without_sign.iter().position(|&c| c == b'-') {
        if is_digits(&without_sign[..dash]) && is_digits(&without_sign[dash + 1..]) {
            out.push(SecondaryPos::Range);
        }
    }
    // 4. RATIO: ^[+\-]?\d+/\d+$
    if let Some(slash) = without_sign.iter().position(|&c| c == b'/') {
        if is_digits(&without_sign[..slash]) && is_digits(&without_sign[slash + 1..]) {
            out.push(SecondaryPos::Ratio);
        }
    }
    // 5. REAL: ^[+\-]?\d+[.,]\d+$
    if let Some(sep) = without_sign.iter().position(|&c| c == b'.' || c == b',') {
        if is_digits(&without_sign[..sep]) && is_digits(&without_sign[sep + 1..]) {
            out.push(SecondaryPos::Real);
        }
    }
    // 6. PERCENTAGE_BEFORE: ^[+\-]?%\d+(?:[.,]\d+)?$
    if without_sign.starts_with(b"%") {
        let after_pct = &without_sign[1..];
        if is_digits(after_pct) {
            out.push(SecondaryPos::Percentage);
        } else if let Some(sep) = after_pct.iter().position(|&c| c == b'.' || c == b',') {
            if is_digits(&after_pct[..sep]) && is_digits(&after_pct[sep + 1..]) {
                out.push(SecondaryPos::Percentage);
            }
        }
    }
    // 7. TIME: ^(?:(?:[01]?\d|2[0-3]):[0-5]\d(?::[0-5]\d)?|(?:[01]\d|2[0-3]|[1-9])\.[0-5]\d)$
    if matches_time_numeral(b) {
        out.push(SecondaryPos::Clock);
    }
    // 8. DATE: ^(?:0?[1-9]|[12]\d|3[01])([./])(?:0?[1-9]|1[0-2])\1(?:\d{4}|\d{2})$
    if matches_date_numeral(b) {
        out.push(SecondaryPos::Date);
    }
    out
}

#[inline]
fn is_digits(b: &[u8]) -> bool {
    !b.is_empty() && b.iter().all(|c| c.is_ascii_digit())
}

fn matches_time_numeral(b: &[u8]) -> bool {
    if let Some(colon) = b.iter().position(|&c| c == b':') {
        let h = &b[..colon];
        let valid_h = (h.len() == 1 && h[0].is_ascii_digit())
            || (h.len() == 2
                && (((h[0] == b'0' || h[0] == b'1') && h[1].is_ascii_digit())
                    || (h[0] == b'2' && (b'0'..=b'3').contains(&h[1]))));
        if !valid_h {
            return false;
        }
        let rest = &b[colon + 1..];
        if rest.len() == 2 {
            return (b'0'..=b'5').contains(&rest[0]) && rest[1].is_ascii_digit();
        }
        if rest.len() == 5 && rest[2] == b':' {
            return (b'0'..=b'5').contains(&rest[0])
                && rest[1].is_ascii_digit()
                && (b'0'..=b'5').contains(&rest[3])
                && rest[4].is_ascii_digit();
        }
        return false;
    }
    if let Some(dot) = b.iter().position(|&c| c == b'.') {
        let h = &b[..dot];
        let valid_h = (h.len() == 1 && (b'1'..=b'9').contains(&h[0]))
            || (h.len() == 2
                && (((h[0] == b'0' || h[0] == b'1') && h[1].is_ascii_digit())
                    || (h[0] == b'2' && (b'0'..=b'3').contains(&h[1]))));
        let rest = &b[dot + 1..];
        return valid_h
            && rest.len() == 2
            && (b'0'..=b'5').contains(&rest[0])
            && rest[1].is_ascii_digit();
    }
    false
}

fn matches_date_numeral(b: &[u8]) -> bool {
    for sep in *b"./" {
        let parts: Vec<&[u8]> = b.split(|&c| c == sep).collect();
        if parts.len() == 3 {
            let d = parts[0];
            let m = parts[1];
            let y = parts[2];
            let valid_d = (d.len() == 1 && (b'1'..=b'9').contains(&d[0]))
                || (d.len() == 2
                    && ((d[0] == b'0' && (b'1'..=b'9').contains(&d[1]))
                        || ((d[0] == b'1' || d[0] == b'2') && d[1].is_ascii_digit())
                        || (d[0] == b'3' && (d[1] == b'0' || d[1] == b'1'))));
            let valid_m = (m.len() == 1 && (b'1'..=b'9').contains(&m[0]))
                || (m.len() == 2
                    && ((m[0] == b'0' && (b'1'..=b'9').contains(&m[1]))
                        || (m[0] == b'1' && (b'0'..=b'2').contains(&m[1]))));
            let valid_y = (y.len() == 2 || y.len() == 4) && is_digits(y);
            if valid_d && valid_m && valid_y {
                return true;
            }
        }
    }
    false
}
