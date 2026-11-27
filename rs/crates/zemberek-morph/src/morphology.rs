use alloc::borrow::Cow;
use alloc::string::String;
use alloc::vec::Vec;
use zemberek_core::{PrimaryPos, RootAttribute, SecondaryPos, TurkishAlphabet};
use zemberek_pack::morph_fst::MorphFstView;
use zemberek_pack::{PackError, ZmbkPack, SECT_MORPH_FST};
use zemberek_tok::{Token, TokenType, TurkishTokenizer};
use crate::analysis::{SingleAnalysis, WordAnalysis};
use crate::analyzer::{AnalyzerScratch, RuleBasedAnalyzer};
use crate::dynamic::DynamicStemTrie;
use crate::generator::{InformalAnalysisConverter, WordGenerator};
use crate::lexicon::LexiconGraph;
use crate::unidentified::UnidentifiedTokenAnalyzer;

#[derive(Clone, Copy, Debug)]
pub struct MorphologyConfig {
    pub use_informal: bool,
    pub ignore_diacritics: bool,
    pub use_unidentified_token_analyzer: bool,
}

impl Default for MorphologyConfig {
    fn default() -> Self {
        Self {
            use_informal: false,
            ignore_diacritics: false,
            use_unidentified_token_analyzer: true,
        }
    }
}

/// Complete zero-copy port of `zemberek.morphology.TurkishMorphology`.
#[derive(Clone, Copy, Debug)]
pub struct TurkishMorphology<'a> {
    pub graph: LexiconGraph<'a>,
    pub fst: MorphFstView<'a>,
    pub config: MorphologyConfig,
}

impl<'a> TurkishMorphology<'a> {
    pub fn from_pack(pack: &ZmbkPack<'a>, config: MorphologyConfig) -> Result<Self, PackError> {
        let graph = LexiconGraph::from_pack(pack)?;
        let (_, fst_bytes) = pack.section_bytes(SECT_MORPH_FST)?;
        let fst = MorphFstView::from_section_bytes(fst_bytes)?;
        Ok(Self { graph, fst, config })
    }

    #[inline]
    pub fn analyzer(&self) -> RuleBasedAnalyzer<'a> {
        RuleBasedAnalyzer {
            graph: self.graph,
            fst: self.fst,
            allow_informal: self.config.use_informal,
            ascii_tolerant: self.config.ignore_diacritics,
        }
    }

    #[inline]
    pub fn word_generator(&self) -> WordGenerator<'a> {
        WordGenerator {
            graph: self.graph,
            fst: self.fst,
            allow_informal: self.config.use_informal,
        }
    }

    #[inline]
    pub fn informal_converter(&self) -> InformalAnalysisConverter<'a> {
        InformalAnalysisConverter::new(self.graph, self.fst)
    }

    pub fn analyze_word(
        &self,
        word: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> WordAnalysis {
        let tokens = TurkishTokenizer::DEFAULT.tokenize(word);
        if tokens.len() != 1 {
            if tokens.len() > 1 && TurkishAlphabet::contains_apostrophe(word) && !word.contains(' ')
            {
                let fake = Token {
                    content: word,
                    token_type: TokenType::WordAlphanumerical,
                    utf8_start: 0,
                    utf8_end: word.len(),
                    utf16_start: 0,
                    utf16_end: word.chars().map(char::len_utf16).sum(),
                };
                return self.analyze_token(&fake, dynamic, scratch);
            }
            return WordAnalysis {
                input: String::from(word),
                normalized_input: String::from(word),
                analyses: Vec::new(),
            };
        }
        self.analyze_token(&tokens[0], dynamic, scratch)
    }

    pub fn analyze_token(
        &self,
        token: &Token<'_>,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> WordAnalysis {
        let word = token.content;
        if word.is_empty() {
            return WordAnalysis {
                input: String::new(),
                normalized_input: String::new(),
                analyses: Vec::new(),
            };
        }

        // Fast path: 90%+ of tokens are already lowercase without dots, circumflexes, or apostrophes!
        let is_simple_clean = !word.bytes().any(|b| b.is_ascii_uppercase() || b == b'.' || b == b'\'' || b == b'`')
            && !word.chars().any(|c| {
                !c.is_ascii()
                    && (c.is_uppercase()
                        || TurkishAlphabet::normalize_circumflex_char(c) != c
                        || matches!(
                            c,
                            '\u{0091}'
                                | '\u{0092}'
                                | '\u{00B4}'
                                | '\u{2018}'
                                | '\u{2019}'
                                | '\u{2032}'
                                | '\u{0307}'
                        ))
            });

        let analyzer = self.analyzer();
        if is_simple_clean {
            let mut result =
                analyzer.analyze_with_search_input(word, word, false, dynamic, &[], scratch);
            if result.is_empty() && self.config.use_unidentified_token_analyzer {
                result = UnidentifiedTokenAnalyzer::analyze(&analyzer, token, dynamic, scratch);
            }
            if result.len() == 1 && result[0].is_unknown() {
                result.clear();
            }
            return WordAnalysis {
                input: String::from(word),
                normalized_input: String::from(word),
                analyses: result,
            };
        }

        let mut raw_normalized = normalize_preserving_circumflex(word);
        if raw_normalized.is_empty() {
            return WordAnalysis {
                input: String::new(),
                normalized_input: String::new(),
                analyses: Vec::new(),
            };
        }

        let has_circumflex = TurkishAlphabet::contains_circumflex(&raw_normalized);
        let mut s = if has_circumflex {
            TurkishAlphabet::normalize_circumflex(&raw_normalized)
        } else {
            raw_normalized.clone()
        };

        let mut result = if TurkishAlphabet::contains_apostrophe(&raw_normalized) {
            raw_normalized = TurkishAlphabet::normalize_apostrophe(&raw_normalized);
            s = if has_circumflex {
                TurkishAlphabet::normalize_apostrophe(&s)
            } else {
                raw_normalized.clone()
            };
            if raw_normalized.chars().count() > 1 {
                self.analyze_words_with_apostrophe(&raw_normalized, dynamic, scratch)
            } else {
                analyzer.analyze_with_search_input(
                    &raw_normalized,
                    &s,
                    has_circumflex,
                    dynamic,
                    &[],
                    scratch,
                )
            }
        } else {
            analyzer.analyze_with_search_input(
                &raw_normalized,
                &s,
                has_circumflex,
                dynamic,
                &[],
                scratch,
            )
        };

        if result.is_empty() && self.config.use_unidentified_token_analyzer {
            result = UnidentifiedTokenAnalyzer::analyze(&analyzer, token, dynamic, scratch);
        } else if self.config.use_unidentified_token_analyzer
            && s.find('\'').is_some_and(|idx| idx > 0)
            && word.chars().next().is_some_and(char::is_uppercase)
            && self.should_add_proper_noun_for_apostrophe(&s, &result)
        {
            let prop_analyses =
                UnidentifiedTokenAnalyzer::analyze(&analyzer, token, dynamic, scratch);
            for prop in prop_analyses {
                let spos = prop.item.secondary_pos(&self.graph);
                if (spos == SecondaryPos::ProperNoun || spos == SecondaryPos::Abbreviation)
                    && !result.iter().any(|ex| ex.equals_analysis(&prop, &self.graph))
                {
                    result.push(prop);
                }
            }
        }

        if result.len() == 1 && result[0].is_unknown() {
            result.clear();
        }

        WordAnalysis {
            input: String::from(word),
            normalized_input: s,
            analyses: result,
        }
    }

    pub fn analyze_sentence(
        &self,
        sentence: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Vec<WordAnalysis> {
        let normalized = normalize_quotes_hyphens_cow(sentence);
        let tokens = TurkishTokenizer::DEFAULT.tokenize(&normalized);
        let mut out = Vec::with_capacity(tokens.len());
        for t in &tokens {
            out.push(self.analyze_token(t, dynamic, scratch));
        }
        out
    }

    fn analyze_words_with_apostrophe(
        &self,
        word: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Vec<SingleAnalysis> {
        let Some(index) = word.rfind('\'') else {
            return Vec::new();
        };
        if index == 0 || index + 1 >= word.len() {
            return Vec::new();
        }
        let stem_raw = &word[..index];
        let stem = TurkishAlphabet::normalize_circumflex(&TurkishAlphabet::normalize(
            &stem_raw.replace('\'', ""),
        ));
        let without_quote = word.replace('\'', "");
        let parses = self.analyzer().analyze(&without_quote, dynamic, scratch);
        let p3sg_id = self.fst.header.p3sg_morpheme_id;
        parses
            .into_iter()
            .filter(|a| {
                a.item.primary_pos(&self.graph) == PrimaryPos::Noun
                    && (a.contains_morpheme(p3sg_id) || a.stem() == stem)
            })
            .collect()
    }

    fn should_add_proper_noun_for_apostrophe(
        &self,
        normalized: &str,
        result: &[SingleAnalysis],
    ) -> bool {
        let Some(index) = normalized.rfind('\'') else {
            return false;
        };
        if index == 0 || index + 1 >= normalized.len() {
            return false;
        }
        let stem = TurkishAlphabet::normalize(&normalized[..index].replace('\'', ""));
        let mut has_direct_common = false;
        for a in result {
            let spos = a.item.secondary_pos(&self.graph);
            if spos != SecondaryPos::None && spos != SecondaryPos::Time {
                return false;
            }
            if a.stem() == stem
                && !a
                    .item
                    .root_attrs(&self.graph)
                    .contains(RootAttribute::CompoundP3sg)
            {
                has_direct_common = true;
            }
        }
        has_direct_common
    }
}

fn normalize_preserving_circumflex(word: &str) -> String {
    let s = TurkishAlphabet::to_lower_tr(word);
    let mut no_dot = s.replace('.', "");
    if no_dot.is_empty() {
        no_dot = s;
    }
    let mut out = String::with_capacity(no_dot.len());
    for c in no_dot.chars() {
        let mapped = if matches!(
            c,
            '`' | '\u{0091}' | '\u{0092}' | '\u{00B4}' | '\u{2018}' | '\u{2019}' | '\u{2032}'
        ) {
            '\''
        } else {
            c
        };
        out.push(mapped);
    }
    out
}

fn normalize_quotes_hyphens_cow(input: &str) -> Cow<'_, str> {
    if !input.contains("''")
        && !input.chars().any(|c| {
            matches!(
                c,
                '\u{201C}'
                    | '\u{201D}'
                    | '\u{00BB}'
                    | '\u{00AB}'
                    | '\u{2033}'
                    | '\u{0093}'
                    | '\u{0094}'
                    | '\u{0091}'
                    | '\u{0092}'
                    | '\u{2032}'
                    | '\u{00B4}'
                    | '`'
                    | '\u{2019}'
                    | '\u{2018}'
                    | '\u{0096}'
                    | '\u{0097}'
                    | '\u{2013}'
            )
        })
    {
        return Cow::Borrowed(input);
    }
    Cow::Owned(normalize_quotes_hyphens(input))
}

/// Exact port of `TextUtil.normalizeQuotesHyphens`.
pub fn normalize_quotes_hyphens(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    let mut it = input.chars().peekable();
    while let Some(c) = it.next() {
        if c == '\'' && it.peek() == Some(&'\'') {
            it.next();
            out.push('"');
            continue;
        }
        let mapped = match c {
            '\u{201C}' | '\u{201D}' | '\u{00BB}' | '\u{00AB}' | '\u{2033}' | '\u{0093}'
            | '\u{0094}' => '"',
            '\u{0091}' | '\u{0092}' | '\u{2032}' | '\u{00B4}' | '`' | '\u{2019}' | '\u{2018}' => {
                '\''
            }
            '\u{0096}' | '\u{0097}' | '\u{2013}' => '-',
            _ => c,
        };
        out.push(mapped);
    }
    out
}
