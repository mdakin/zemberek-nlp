use alloc::borrow::Cow;
use alloc::string::String;
use alloc::vec::Vec;
use zemberek_core::{PrimaryPos, RootAttrMask, RootAttribute, SecondaryPos, TurkishAlphabet};
use zemberek_pack::morph_fst::MorphFstView;
use crate::lexicon::LexiconGraph;

/// Represents either a static lexicon item (`Static(item_id)`) or a runtime/OOV item.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum AnalysisItem {
    Static(u32),
    Runtime {
        lemma: String,
        root: String,
        primary_pos: PrimaryPos,
        secondary_pos: SecondaryPos,
        root_attrs: RootAttrMask,
        homonym_index: u8,
    },
    Unknown,
}

impl AnalysisItem {
    pub fn primary_pos(&self, graph: &LexiconGraph<'_>) -> PrimaryPos {
        match self {
            Self::Static(id) => graph.item(*id).primary_pos(),
            Self::Runtime { primary_pos, .. } => *primary_pos,
            Self::Unknown => PrimaryPos::Unknown,
        }
    }

    pub fn secondary_pos(&self, graph: &LexiconGraph<'_>) -> SecondaryPos {
        match self {
            Self::Static(id) => graph.item(*id).secondary_pos(),
            Self::Runtime { secondary_pos, .. } => *secondary_pos,
            Self::Unknown => SecondaryPos::UnknownSec,
        }
    }

    pub fn root_attrs(&self, graph: &LexiconGraph<'_>) -> RootAttrMask {
        match self {
            Self::Static(id) => graph.item(*id).root_attrs(),
            Self::Runtime { root_attrs, .. } => *root_attrs,
            Self::Unknown => RootAttrMask::EMPTY,
        }
    }

    pub fn homonym_index(&self, graph: &LexiconGraph<'_>) -> u8 {
        match self {
            Self::Static(id) => graph.item(*id).homonym_index(),
            Self::Runtime { homonym_index, .. } => *homonym_index,
            Self::Unknown => 0,
        }
    }

    pub fn lemma<'a>(&'a self, graph: &LexiconGraph<'a>) -> Cow<'a, str> {
        match self {
            Self::Static(id) => graph.item(*id).lemma(),
            Self::Runtime { lemma, .. } => Cow::Borrowed(lemma),
            Self::Unknown => Cow::Borrowed("UNK"),
        }
    }

    pub fn root<'a>(&'a self, graph: &LexiconGraph<'a>) -> &'a str {
        match self {
            Self::Static(id) => graph.item(*id).root(),
            Self::Runtime { root, .. } => root,
            Self::Unknown => "UNK",
        }
    }

    pub fn normalized_lemma<'a>(&'a self, graph: &LexiconGraph<'a>) -> Cow<'a, str> {
        match self {
            Self::Static(id) => graph.item(*id).normalized_lemma(),
            Self::Runtime {
                lemma, primary_pos, ..
            } => {
                if *primary_pos == PrimaryPos::Verb {
                    let cut = lemma.char_indices().rev().nth(2).map_or(0, |(idx, _)| idx);
                    Cow::Borrowed(&lemma[..cut])
                } else {
                    Cow::Borrowed(lemma)
                }
            }
            Self::Unknown => Cow::Borrowed("UNK"),
        }
    }

    pub fn same_id_as(&self, other: &Self, graph: &LexiconGraph<'_>) -> bool {
        match (self, other) {
            (Self::Static(a), Self::Static(b)) => a == b,
            (Self::Unknown, Self::Unknown) => true,
            _ => {
                self.primary_pos(graph) == other.primary_pos(graph)
                    && self.secondary_pos(graph) == other.secondary_pos(graph)
                    && self.homonym_index(graph) == other.homonym_index(graph)
                    && self.lemma(graph) == other.lemma(graph)
            }
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MorphemeData {
    pub morpheme_id: u8,
    pub surface: String,
}

/// Exact port of `zemberek.morphology.analysis.SingleAnalysis`.
#[derive(Clone, Debug)]
pub struct SingleAnalysis {
    pub item: AnalysisItem,
    pub morphemes: Vec<MorphemeData>,
    pub group_boundaries: Vec<u8>,
}

impl SingleAnalysis {
    pub fn unknown(input: &str) -> Self {
        Self::dummy(input, AnalysisItem::Unknown)
    }

    pub fn dummy(input: &str, item: AnalysisItem) -> Self {
        Self {
            item,
            morphemes: alloc::vec![MorphemeData {
                morpheme_id: 0, // Morpheme.UNKNOWN
                surface: String::from(input),
            }],
            group_boundaries: alloc::vec![0],
        }
    }

    #[inline]
    pub fn is_unknown(&self) -> bool {
        matches!(self.item, AnalysisItem::Unknown)
    }

    #[inline]
    pub fn is_runtime(&self, graph: &LexiconGraph<'_>) -> bool {
        self.item.root_attrs(graph).contains(RootAttribute::Runtime)
    }

    #[inline]
    pub fn is_known_lexical(&self, graph: &LexiconGraph<'_>) -> bool {
        !self.is_unknown() && !self.is_runtime(graph)
    }

    #[inline]
    pub fn stem(&self) -> &str {
        &self.morphemes[0].surface
    }

    pub fn ending(&self) -> String {
        let mut out = String::new();
        for md in self.morphemes.iter().skip(1) {
            out.push_str(&md.surface);
        }
        out
    }

    pub fn surface_form(&self) -> String {
        let mut out = String::from(self.stem());
        for md in self.morphemes.iter().skip(1) {
            out.push_str(&md.surface);
        }
        out
    }

    #[inline]
    pub fn group_count(&self) -> usize {
        self.group_boundaries.len()
    }

    pub fn group_slice(&self, group_idx: usize) -> &[MorphemeData] {
        let start = self.group_boundaries[group_idx] as usize;
        let end = if group_idx + 1 < self.group_boundaries.len() {
            self.group_boundaries[group_idx + 1] as usize
        } else {
            self.morphemes.len()
        };
        &self.morphemes[start..end]
    }

    pub fn pos(&self, fst: &MorphFstView<'_>) -> PrimaryPos {
        for md in self.group_slice(self.group_count() - 1) {
            let pm = fst.morphemes[md.morpheme_id as usize];
            if pm.has_pos() && pm.pos != (PrimaryPos::Unknown as u8) {
                if let Some(p) = PrimaryPos::from_u8(pm.pos) {
                    return p;
                }
            }
        }
        PrimaryPos::Unknown
    }

    #[inline]
    pub fn contains_morpheme(&self, morph_id: u8) -> bool {
        self.morphemes.iter().any(|m| m.morpheme_id == morph_id)
    }

    #[inline]
    pub fn contains_informal_morpheme(&self, fst: &MorphFstView<'_>) -> bool {
        self.morphemes
            .iter()
            .any(|m| fst.morphemes[m.morpheme_id as usize].is_informal())
    }

    pub fn copy_for(&self, new_item: AnalysisItem, new_stem: &str) -> Self {
        let mut morphemes = self.morphemes.clone();
        morphemes[0].surface = String::from(new_stem);
        Self {
            item: new_item,
            morphemes,
            group_boundaries: self.group_boundaries.clone(),
        }
    }

    /// Exact port of `SingleAnalysis.getStems()`.
    pub fn get_stems(&self) -> Vec<String> {
        let mut stems = Vec::with_capacity(self.group_boundaries.len());
        stems.push(String::from(self.stem()));
        if self.group_boundaries.len() > 1 {
            let mut prev_stem = group_surface_form(self.group_slice(0));
            for i in 1..self.group_boundaries.len() {
                let ig = self.group_slice(i);
                let mut stem = String::with_capacity(prev_stem.len() + ig[0].surface.len());
                stem.push_str(&prev_stem);
                stem.push_str(&ig[0].surface);
                if !stems.contains(&stem) {
                    stems.push(stem);
                }
                if i + 1 < self.group_boundaries.len() {
                    prev_stem.push_str(&group_surface_form(ig));
                }
            }
        }
        stems
    }

    /// Exact port of `SingleAnalysis.getLemmas()`.
    pub fn get_lemmas(&self, graph: &LexiconGraph<'_>) -> Vec<String> {
        let mut lemmas = Vec::with_capacity(self.group_boundaries.len());
        let item_root = self.item.root(graph);
        lemmas.push(String::from(item_root));

        if self.group_boundaries.len() > 1 {
            let mut prev_stem = group_surface_form(self.group_slice(0));
            if prev_stem != item_root && prev_stem.ends_with('ğ') {
                prev_stem.pop();
                prev_stem.push('k');
            }
            for i in 1..self.group_boundaries.len() {
                let ig = self.group_slice(i);
                let mut stem = String::with_capacity(prev_stem.len() + ig[0].surface.len());
                stem.push_str(&prev_stem);
                stem.push_str(&ig[0].surface);
                if stem.ends_with('ğ') {
                    stem.pop();
                    stem.push('k');
                }
                if !lemmas.contains(&stem) {
                    lemmas.push(stem);
                }
                if i + 1 < self.group_boundaries.len() {
                    prev_stem.push_str(&group_surface_form(ig));
                }
            }
        }
        lemmas
    }

    /// Exact port of `AnalysisFormatters.DEFAULT.format(this)` (`formatLong()`).
    pub fn format_long(&self, graph: &LexiconGraph<'_>, fst: &MorphFstView<'_>) -> String {
        self.format_default(graph, fst, true)
    }

    /// Exact port of `AnalysisFormatters.DEFAULT_LEXICAL.format(this)` (`formatLexical()`).
    pub fn format_lexical(&self, graph: &LexiconGraph<'_>, fst: &MorphFstView<'_>) -> String {
        self.format_default(graph, fst, false)
    }

    /// Exact port of `AnalysisFormatters.DEFAULT_LEXICAL_ONLY_MORPHEMES`.
    pub fn format_morphemes_lexical(&self, fst: &MorphFstView<'_>) -> String {
        let mut sb = String::with_capacity(self.morphemes.len() * 5);
        self.write_morphemes(fst, false, &mut sb);
        sb
    }

    fn format_default(
        &self,
        graph: &LexiconGraph<'_>,
        fst: &MorphFstView<'_>,
        add_surface: bool,
    ) -> String {
        let mut sb = String::with_capacity(self.morphemes.len() * 8 + 16);
        sb.push('[');
        sb.push_str(&self.item.lemma(graph));
        sb.push(':');
        sb.push_str(self.item.primary_pos(graph).short_form());
        let spos = self.item.secondary_pos(graph);
        if spos != SecondaryPos::None {
            sb.push(',');
            sb.push_str(spos.short_form());
        }
        sb.push_str("] ");
        self.write_morphemes(fst, add_surface, &mut sb);
        sb
    }

    fn write_morphemes(&self, fst: &MorphFstView<'_>, add_surface: bool, sb: &mut String) {
        if add_surface {
            sb.push_str(self.stem());
            sb.push(':');
        }
        sb.push_str(fst.morpheme_id_str(self.morphemes[0].morpheme_id));
        if self.morphemes.len() > 1
            && !fst.morphemes[self.morphemes[1].morpheme_id as usize].is_derivational()
        {
            sb.push('+');
        }
        for i in 1..self.morphemes.len() {
            let md = &self.morphemes[i];
            let pm = fst.morphemes[md.morpheme_id as usize];
            if pm.is_derivational() {
                sb.push('|');
            }
            if add_surface && !md.surface.is_empty() {
                sb.push_str(&md.surface);
                sb.push(':');
            }
            sb.push_str(fst.morpheme_id_str(md.morpheme_id));
            if pm.is_derivational() {
                sb.push('→');
            } else if i + 1 < self.morphemes.len()
                && !fst.morphemes[self.morphemes[i + 1].morpheme_id as usize].is_derivational()
            {
                sb.push('+');
            }
        }
    }

    pub fn equals_analysis(&self, other: &Self, graph: &LexiconGraph<'_>) -> bool {
        self.morphemes == other.morphemes && self.item.same_id_as(&other.item, graph)
    }
}

fn group_surface_form(slice: &[MorphemeData]) -> String {
    let mut sb = String::new();
    for m in slice {
        sb.push_str(&m.surface);
    }
    sb
}

/// Exact port of `zemberek.morphology.analysis.WordAnalysis`.
#[derive(Clone, Debug)]
pub struct WordAnalysis {
    pub input: String,
    pub normalized_input: String,
    pub analyses: Vec<SingleAnalysis>,
}

impl WordAnalysis {
    #[inline]
    pub fn is_correct(&self) -> bool {
        !self.analyses.is_empty() && !self.analyses[0].is_unknown()
    }
}

/// Case types matching `WordAnalysisSurfaceFormatter.CaseType`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CaseType {
    DefaultCase,
    LowerCase,
    UpperCase,
    TitleCase,
    UpperCaseRootLowerCaseEnding,
    MixedCase,
}

/// Exact port of `zemberek.morphology.analysis.WordAnalysisSurfaceFormatter`.
pub struct WordAnalysisSurfaceFormatter;

impl WordAnalysisSurfaceFormatter {
    pub fn format(
        analysis: &SingleAnalysis,
        graph: &LexiconGraph<'_>,
        apostrophe: Option<&str>,
    ) -> String {
        let ending = analysis.ending();
        let req_apos = Self::apostrophe_required(analysis, graph);
        if apostrophe.is_some() || req_apos {
            let apos = apostrophe.unwrap_or("'");
            let norm_lemma = analysis.item.normalized_lemma(graph);
            if !ending.is_empty() {
                let mut s = String::with_capacity(norm_lemma.len() + apos.len() + ending.len());
                s.push_str(&norm_lemma);
                s.push_str(apos);
                s.push_str(&ending);
                s
            } else {
                norm_lemma.into_owned()
            }
        } else if analysis
            .item
            .root_attrs(graph)
            .contains(RootAttribute::NoQuote)
        {
            let norm_lemma = analysis.item.normalized_lemma(graph);
            let mut s = String::with_capacity(norm_lemma.len() + ending.len());
            s.push_str(&norm_lemma);
            s.push_str(&ending);
            s
        } else {
            let mut s = String::with_capacity(analysis.stem().len() + ending.len());
            s.push_str(analysis.stem());
            s.push_str(&ending);
            s
        }
    }

    fn apostrophe_required(analysis: &SingleAnalysis, graph: &LexiconGraph<'_>) -> bool {
        let spos = analysis.item.secondary_pos(graph);
        let ppos = analysis.item.primary_pos(graph);
        let attrs = analysis.item.root_attrs(graph);
        (spos == SecondaryPos::ProperNoun && !attrs.contains(RootAttribute::NoQuote))
            || (ppos == PrimaryPos::Numeral && attrs.contains(RootAttribute::Runtime))
            || spos == SecondaryPos::Date
    }

    pub fn format_to_case(
        analysis: &SingleAnalysis,
        graph: &LexiconGraph<'_>,
        case_type: CaseType,
        apostrophe: Option<&str>,
    ) -> String {
        let locale_en = analysis
            .item
            .root_attrs(graph)
            .contains(RootAttribute::LocaleEn);
        match case_type {
            CaseType::DefaultCase => Self::format(analysis, graph, apostrophe),
            CaseType::LowerCase => {
                let base = Self::format(analysis, graph, apostrophe);
                if locale_en {
                    to_lower_en(&base)
                } else {
                    TurkishAlphabet::to_lower_tr(&base)
                }
            }
            CaseType::UpperCase => {
                let base = Self::format(analysis, graph, apostrophe);
                if locale_en {
                    to_upper_en(&base)
                } else {
                    TurkishAlphabet::to_upper_tr(&base)
                }
            }
            CaseType::TitleCase => {
                TurkishAlphabet::capitalize_tr(&Self::format(analysis, graph, apostrophe))
            }
            CaseType::UpperCaseRootLowerCaseEnding => {
                let ending = analysis.ending();
                let norm_lemma = analysis.item.normalized_lemma(graph);
                let lemma_upper = if locale_en {
                    to_upper_en(&norm_lemma)
                } else {
                    TurkishAlphabet::to_upper_tr(&norm_lemma)
                };
                if ending.is_empty() {
                    return lemma_upper;
                }
                if apostrophe.is_some() || Self::apostrophe_required(analysis, graph) {
                    let apos = apostrophe.unwrap_or("'");
                    let mut out =
                        String::with_capacity(lemma_upper.len() + apos.len() + ending.len());
                    out.push_str(&lemma_upper);
                    out.push_str(apos);
                    out.push_str(&ending);
                    out
                } else {
                    let mut out = String::with_capacity(lemma_upper.len() + ending.len());
                    out.push_str(&lemma_upper);
                    out.push_str(&ending);
                    out
                }
            }
            CaseType::MixedCase => String::new(),
        }
    }

    pub fn can_be_formatted(
        analysis: &SingleAnalysis,
        graph: &LexiconGraph<'_>,
        case_type: CaseType,
    ) -> bool {
        let spos = analysis.item.secondary_pos(graph);
        let proper = spos == SecondaryPos::ProperNoun || spos == SecondaryPos::Abbreviation;
        match case_type {
            CaseType::LowerCase => !proper,
            CaseType::UpperCase | CaseType::TitleCase | CaseType::DefaultCase => true,
            CaseType::UpperCaseRootLowerCaseEnding => proper,
            CaseType::MixedCase => false,
        }
    }

    pub fn guess_case(input: &str) -> CaseType {
        let mut first_upper = false;
        let mut lower_count = 0usize;
        let mut upper_count = 0usize;
        let mut letter_count = 0usize;

        for (i, c) in input.chars().enumerate() {
            if !c.is_alphabetic() {
                continue;
            }
            if i == 0 {
                first_upper = c.is_uppercase();
                if first_upper {
                    upper_count += 1;
                } else {
                    lower_count += 1;
                }
            } else if c.is_uppercase() {
                upper_count += 1;
            } else if c.is_lowercase() {
                lower_count += 1;
            }
            letter_count += 1;
        }
        if letter_count == 0 {
            return CaseType::DefaultCase;
        }
        if letter_count == lower_count {
            return CaseType::LowerCase;
        }
        if letter_count == upper_count {
            return CaseType::UpperCase;
        }
        if first_upper && letter_count == lower_count + 1 {
            return CaseType::TitleCase;
        }
        if input.find('\'').is_some_and(|ap| {
            ap > 0
                && ap + 1 < input.len()
                && Self::guess_case(&input[..ap]) == CaseType::UpperCase
                && Self::guess_case(&input[ap + 1..]) == CaseType::LowerCase
        }) {
            return CaseType::UpperCaseRootLowerCaseEnding;
        }
        CaseType::MixedCase
    }
}

pub(crate) fn to_lower_en(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        for lc in c.to_lowercase() {
            out.push(lc);
        }
    }
    out
}

pub(crate) fn to_upper_en(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        for uc in c.to_uppercase() {
            out.push(uc);
        }
    }
    out
}
