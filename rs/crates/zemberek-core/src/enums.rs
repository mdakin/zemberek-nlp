use core::fmt;

/// Primary part-of-speech tags, matching `zemberek.core.turkish.PrimaryPos` ordinals `0..=13`.
#[repr(u8)]
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum PrimaryPos {
    Noun = 0,
    Adjective = 1,
    Adverb = 2,
    Conjunction = 3,
    Interjection = 4,
    Verb = 5,
    Pronoun = 6,
    Numeral = 7,
    Determiner = 8,
    PostPositive = 9,
    Question = 10,
    Duplicator = 11,
    Punctuation = 12,
    Unknown = 13,
}

impl PrimaryPos {
    pub const COUNT: usize = 14;

    #[inline]
    pub const fn from_u8(v: u8) -> Option<Self> {
        match v {
            0 => Some(Self::Noun),
            1 => Some(Self::Adjective),
            2 => Some(Self::Adverb),
            3 => Some(Self::Conjunction),
            4 => Some(Self::Interjection),
            5 => Some(Self::Verb),
            6 => Some(Self::Pronoun),
            7 => Some(Self::Numeral),
            8 => Some(Self::Determiner),
            9 => Some(Self::PostPositive),
            10 => Some(Self::Question),
            11 => Some(Self::Duplicator),
            12 => Some(Self::Punctuation),
            13 => Some(Self::Unknown),
            _ => None,
        }
    }

    #[inline]
    pub const fn short_form(self) -> &'static str {
        match self {
            Self::Noun => "Noun",
            Self::Adjective => "Adj",
            Self::Adverb => "Adv",
            Self::Conjunction => "Conj",
            Self::Interjection => "Interj",
            Self::Verb => "Verb",
            Self::Pronoun => "Pron",
            Self::Numeral => "Num",
            Self::Determiner => "Det",
            Self::PostPositive => "Postp",
            Self::Question => "Ques",
            Self::Duplicator => "Dup",
            Self::Punctuation => "Punc",
            Self::Unknown => "Unk",
        }
    }

    pub fn from_short_form(s: &str) -> Option<Self> {
        match s {
            "Noun" => Some(Self::Noun),
            "Adj" => Some(Self::Adjective),
            "Adv" => Some(Self::Adverb),
            "Conj" => Some(Self::Conjunction),
            "Interj" => Some(Self::Interjection),
            "Verb" => Some(Self::Verb),
            "Pron" => Some(Self::Pronoun),
            "Num" => Some(Self::Numeral),
            "Det" => Some(Self::Determiner),
            "Postp" => Some(Self::PostPositive),
            "Ques" => Some(Self::Question),
            "Dup" => Some(Self::Duplicator),
            "Punc" => Some(Self::Punctuation),
            "Unk" => Some(Self::Unknown),
            _ => None,
        }
    }
}

/// Secondary part-of-speech tags, matching `zemberek.core.turkish.SecondaryPos` ordinals `0..=31`.
#[repr(u8)]
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum SecondaryPos {
    UnknownSec = 0,
    DemonstrativePron = 1,
    Time = 2,
    QuantitivePron = 3,
    QuestionPron = 4,
    ProperNoun = 5,
    PersonalPron = 6,
    ReflexivePron = 7,
    None = 8,
    Ordinal = 9,
    Cardinal = 10,
    Percentage = 11,
    Ratio = 12,
    Range = 13,
    Real = 14,
    Distribution = 15,
    Clock = 16,
    Date = 17,
    Email = 18,
    Url = 19,
    Mention = 20,
    HashTag = 21,
    Emoticon = 22,
    RomanNumeral = 23,
    RegularAbbreviation = 24,
    Abbreviation = 25,
    PCDat = 26,
    PCAcc = 27,
    PCIns = 28,
    PCNom = 29,
    PCGen = 30,
    PCAbl = 31,
}

impl SecondaryPos {
    pub const COUNT: usize = 32;

    #[inline]
    pub const fn from_u8(v: u8) -> Option<Self> {
        match v {
            0 => Some(Self::UnknownSec),
            1 => Some(Self::DemonstrativePron),
            2 => Some(Self::Time),
            3 => Some(Self::QuantitivePron),
            4 => Some(Self::QuestionPron),
            5 => Some(Self::ProperNoun),
            6 => Some(Self::PersonalPron),
            7 => Some(Self::ReflexivePron),
            8 => Some(Self::None),
            9 => Some(Self::Ordinal),
            10 => Some(Self::Cardinal),
            11 => Some(Self::Percentage),
            12 => Some(Self::Ratio),
            13 => Some(Self::Range),
            14 => Some(Self::Real),
            15 => Some(Self::Distribution),
            16 => Some(Self::Clock),
            17 => Some(Self::Date),
            18 => Some(Self::Email),
            19 => Some(Self::Url),
            20 => Some(Self::Mention),
            21 => Some(Self::HashTag),
            22 => Some(Self::Emoticon),
            23 => Some(Self::RomanNumeral),
            24 => Some(Self::RegularAbbreviation),
            25 => Some(Self::Abbreviation),
            26 => Some(Self::PCDat),
            27 => Some(Self::PCAcc),
            28 => Some(Self::PCIns),
            29 => Some(Self::PCNom),
            30 => Some(Self::PCGen),
            31 => Some(Self::PCAbl),
            _ => None,
        }
    }

    #[inline]
    pub const fn name(self) -> &'static str {
        match self {
            Self::UnknownSec => "UnknownSec",
            Self::DemonstrativePron => "DemonstrativePron",
            Self::Time => "Time",
            Self::QuantitivePron => "QuantitivePron",
            Self::QuestionPron => "QuestionPron",
            Self::ProperNoun => "ProperNoun",
            Self::PersonalPron => "PersonalPron",
            Self::ReflexivePron => "ReflexivePron",
            Self::None => "None",
            Self::Ordinal => "Ordinal",
            Self::Cardinal => "Cardinal",
            Self::Percentage => "Percentage",
            Self::Ratio => "Ratio",
            Self::Range => "Range",
            Self::Real => "Real",
            Self::Distribution => "Distribution",
            Self::Clock => "Clock",
            Self::Date => "Date",
            Self::Email => "Email",
            Self::Url => "Url",
            Self::Mention => "Mention",
            Self::HashTag => "HashTag",
            Self::Emoticon => "Emoticon",
            Self::RomanNumeral => "RomanNumeral",
            Self::RegularAbbreviation => "RegularAbbreviation",
            Self::Abbreviation => "Abbreviation",
            Self::PCDat => "PCDat",
            Self::PCAcc => "PCAcc",
            Self::PCIns => "PCIns",
            Self::PCNom => "PCNom",
            Self::PCGen => "PCGen",
            Self::PCAbl => "PCAbl",
        }
    }

    #[inline]
    pub const fn short_form(self) -> &'static str {
        match self {
            Self::UnknownSec => "Unk",
            Self::DemonstrativePron => "Demons",
            Self::Time => "Time",
            Self::QuantitivePron => "Quant",
            Self::QuestionPron => "Ques",
            Self::ProperNoun => "Prop",
            Self::PersonalPron => "Pers",
            Self::ReflexivePron => "Reflex",
            Self::None => "None",
            Self::Ordinal => "Ord",
            Self::Cardinal => "Card",
            Self::Percentage => "Percent",
            Self::Ratio => "Ratio",
            Self::Range => "Range",
            Self::Real => "Real",
            Self::Distribution => "Dist",
            Self::Clock => "Clock",
            Self::Date => "Date",
            Self::Email => "Email",
            Self::Url => "Url",
            Self::Mention => "Mention",
            Self::HashTag => "HashTag",
            Self::Emoticon => "Emoticon",
            Self::RomanNumeral => "RomanNumeral",
            Self::RegularAbbreviation => "RegAbbrv",
            Self::Abbreviation => "Abbrv",
            Self::PCDat => "PCDat",
            Self::PCAcc => "PCAcc",
            Self::PCIns => "PCIns",
            Self::PCNom => "PCNom",
            Self::PCGen => "PCGen",
            Self::PCAbl => "PCAbl",
        }
    }

    pub fn from_short_form(s: &str) -> Option<Self> {
        match s {
            "Unk" => Some(Self::UnknownSec),
            "Demons" => Some(Self::DemonstrativePron),
            "Time" => Some(Self::Time),
            "Quant" => Some(Self::QuantitivePron),
            "Ques" => Some(Self::QuestionPron),
            "Prop" => Some(Self::ProperNoun),
            "Pers" => Some(Self::PersonalPron),
            "Reflex" => Some(Self::ReflexivePron),
            "None" => Some(Self::None),
            "Ord" => Some(Self::Ordinal),
            "Card" => Some(Self::Cardinal),
            "Percent" => Some(Self::Percentage),
            "Ratio" => Some(Self::Ratio),
            "Range" => Some(Self::Range),
            "Real" => Some(Self::Real),
            "Dist" => Some(Self::Distribution),
            "Clock" => Some(Self::Clock),
            "Date" => Some(Self::Date),
            "Email" => Some(Self::Email),
            "Url" => Some(Self::Url),
            "Mention" => Some(Self::Mention),
            "HashTag" => Some(Self::HashTag),
            "Emoticon" => Some(Self::Emoticon),
            "RomanNumeral" => Some(Self::RomanNumeral),
            "RegAbbrv" => Some(Self::RegularAbbreviation),
            "Abbrv" => Some(Self::Abbreviation),
            "PCDat" => Some(Self::PCDat),
            "PCAcc" => Some(Self::PCAcc),
            "PCIns" => Some(Self::PCIns),
            "PCNom" => Some(Self::PCNom),
            "PCGen" => Some(Self::PCGen),
            "PCAbl" => Some(Self::PCAbl),
            _ => None,
        }
    }
}

/// Root lexicon attributes, matching `zemberek.core.turkish.RootAttribute` ordinals `0..=30`.
#[repr(u8)]
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum RootAttribute {
    AoristI = 0,
    AoristA = 1,
    ProgressiveVowelDrop = 2,
    PassiveIn = 3,
    CausativeT = 4,
    Voicing = 5,
    NoVoicing = 6,
    InverseHarmony = 7,
    Doubling = 8,
    LastVowelDrop = 9,
    CompoundP3sg = 10,
    NoSuffix = 11,
    NounConsInsertN = 12,
    NoQuote = 13,
    CompoundP3sgRoot = 14,
    Reflexive = 15,
    Reciprocal = 16,
    NonReciprocal = 17,
    Ext = 18,
    Runtime = 19,
    Dummy = 20,
    ImplicitDative = 21,
    ImplicitPlural = 22,
    ImplicitP1sg = 23,
    ImplicitP2sg = 24,
    FamilyMember = 25,
    PronunciationGuessed = 26,
    Informal = 27,
    LocaleEn = 28,
    Unknown = 29,
}

impl RootAttribute {
    pub const COUNT: usize = 30;

    #[inline]
    pub const fn bit(self) -> u32 {
        1u32 << (self as u8)
    }

    #[inline]
    pub const fn from_u8(v: u8) -> Option<Self> {
        match v {
            0 => Some(Self::AoristI),
            1 => Some(Self::AoristA),
            2 => Some(Self::ProgressiveVowelDrop),
            3 => Some(Self::PassiveIn),
            4 => Some(Self::CausativeT),
            5 => Some(Self::Voicing),
            6 => Some(Self::NoVoicing),
            7 => Some(Self::InverseHarmony),
            8 => Some(Self::Doubling),
            9 => Some(Self::LastVowelDrop),
            10 => Some(Self::CompoundP3sg),
            11 => Some(Self::NoSuffix),
            12 => Some(Self::NounConsInsertN),
            13 => Some(Self::NoQuote),
            14 => Some(Self::CompoundP3sgRoot),
            15 => Some(Self::Reflexive),
            16 => Some(Self::Reciprocal),
            17 => Some(Self::NonReciprocal),
            18 => Some(Self::Ext),
            19 => Some(Self::Runtime),
            20 => Some(Self::Dummy),
            21 => Some(Self::ImplicitDative),
            22 => Some(Self::ImplicitPlural),
            23 => Some(Self::ImplicitP1sg),
            24 => Some(Self::ImplicitP2sg),
            25 => Some(Self::FamilyMember),
            26 => Some(Self::PronunciationGuessed),
            27 => Some(Self::Informal),
            28 => Some(Self::LocaleEn),
            29 => Some(Self::Unknown),
            _ => None,
        }
    }

    #[inline]
    pub const fn name(self) -> &'static str {
        match self {
            Self::AoristI => "Aorist_I",
            Self::AoristA => "Aorist_A",
            Self::ProgressiveVowelDrop => "ProgressiveVowelDrop",
            Self::PassiveIn => "Passive_In",
            Self::CausativeT => "Causative_t",
            Self::Voicing => "Voicing",
            Self::NoVoicing => "NoVoicing",
            Self::InverseHarmony => "InverseHarmony",
            Self::Doubling => "Doubling",
            Self::LastVowelDrop => "LastVowelDrop",
            Self::CompoundP3sg => "CompoundP3sg",
            Self::NoSuffix => "NoSuffix",
            Self::NounConsInsertN => "NounConsInsert_n",
            Self::NoQuote => "NoQuote",
            Self::CompoundP3sgRoot => "CompoundP3sgRoot",
            Self::Reflexive => "Reflexive",
            Self::Reciprocal => "Reciprocal",
            Self::NonReciprocal => "NonReciprocal",
            Self::Ext => "Ext",
            Self::Runtime => "Runtime",
            Self::Dummy => "Dummy",
            Self::ImplicitDative => "ImplicitDative",
            Self::ImplicitPlural => "ImplicitPlural",
            Self::ImplicitP1sg => "ImplicitP1sg",
            Self::ImplicitP2sg => "ImplicitP2sg",
            Self::FamilyMember => "FamilyMember",
            Self::PronunciationGuessed => "PronunciationGuessed",
            Self::Informal => "Informal",
            Self::LocaleEn => "LocaleEn",
            Self::Unknown => "Unknown",
        }
    }
}

/// Bitset of `RootAttribute` flags.
#[repr(transparent)]
#[derive(Clone, Copy, Default, PartialEq, Eq, Hash)]
pub struct RootAttrMask(pub u32);

impl RootAttrMask {
    pub const EMPTY: Self = Self(0);

    #[inline]
    pub const fn contains(self, attr: RootAttribute) -> bool {
        (self.0 & attr.bit()) != 0
    }

    #[inline]
    pub const fn with(self, attr: RootAttribute) -> Self {
        Self(self.0 | attr.bit())
    }

    #[inline]
    pub const fn without(self, attr: RootAttribute) -> Self {
        Self(self.0 & !attr.bit())
    }

    #[inline]
    pub fn insert(&mut self, attr: RootAttribute) {
        self.0 |= attr.bit();
    }

    #[inline]
    pub fn remove(&mut self, attr: RootAttribute) {
        self.0 &= !attr.bit();
    }
}

impl fmt::Debug for RootAttrMask {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let mut list = f.debug_list();
        for i in 0..(RootAttribute::COUNT as u8) {
            if let Some(a) = RootAttribute::from_u8(i) {
                if self.contains(a) {
                    list.entry(&a);
                }
            }
        }
        list.finish()
    }
}

/// Phonetic attributes, matching `zemberek.core.turkish.PhoneticAttribute` ordinals `0..=17`.
#[repr(u8)]
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum PhoneticAttribute {
    LastLetterVowel = 0,
    LastLetterConsonant = 1,
    LastVowelFrontal = 2,
    LastVowelBack = 3,
    LastVowelRounded = 4,
    LastVowelUnrounded = 5,
    LastLetterVoiceless = 6,
    LastLetterVoiced = 7,
    LastLetterVoicelessStop = 8,
    FirstLetterVowel = 9,
    FirstLetterConsonant = 10,
    HasNoVowel = 11,
    ExpectsVowel = 12,
    ExpectsConsonant = 13,
    ModifiedPronoun = 14,
    UnModifiedPronoun = 15,
    LastLetterDropped = 16,
    CannotTerminate = 17,
}

impl PhoneticAttribute {
    pub const COUNT: usize = 18;

    #[inline]
    pub const fn bit(self) -> u32 {
        1u32 << (self as u8)
    }

    #[inline]
    pub const fn from_u8(v: u8) -> Option<Self> {
        match v {
            0 => Some(Self::LastLetterVowel),
            1 => Some(Self::LastLetterConsonant),
            2 => Some(Self::LastVowelFrontal),
            3 => Some(Self::LastVowelBack),
            4 => Some(Self::LastVowelRounded),
            5 => Some(Self::LastVowelUnrounded),
            6 => Some(Self::LastLetterVoiceless),
            7 => Some(Self::LastLetterVoiced),
            8 => Some(Self::LastLetterVoicelessStop),
            9 => Some(Self::FirstLetterVowel),
            10 => Some(Self::FirstLetterConsonant),
            11 => Some(Self::HasNoVowel),
            12 => Some(Self::ExpectsVowel),
            13 => Some(Self::ExpectsConsonant),
            14 => Some(Self::ModifiedPronoun),
            15 => Some(Self::UnModifiedPronoun),
            16 => Some(Self::LastLetterDropped),
            17 => Some(Self::CannotTerminate),
            _ => None,
        }
    }

    #[inline]
    pub const fn short_form(self) -> &'static str {
        match self {
            Self::LastLetterVowel => "LLV",
            Self::LastLetterConsonant => "LLC",
            Self::LastVowelFrontal => "LVF",
            Self::LastVowelBack => "LVB",
            Self::LastVowelRounded => "LVR",
            Self::LastVowelUnrounded => "LVuR",
            Self::LastLetterVoiceless => "LLVless",
            Self::LastLetterVoiced => "LLVo",
            Self::LastLetterVoicelessStop => "LLVlessStop",
            Self::FirstLetterVowel => "FLV",
            Self::FirstLetterConsonant => "FLC",
            Self::HasNoVowel => "NoVow",
            Self::ExpectsVowel => "EV",
            Self::ExpectsConsonant => "EC",
            Self::ModifiedPronoun => "MP",
            Self::UnModifiedPronoun => "UMP",
            Self::LastLetterDropped => "LWD",
            Self::CannotTerminate => "CNT",
        }
    }
}

/// Bitset of `PhoneticAttribute` flags.
#[repr(transparent)]
#[derive(Clone, Copy, Default, PartialEq, Eq, Hash)]
pub struct PhonoMask(pub u32);

impl PhonoMask {
    pub const EMPTY: Self = Self(0);

    #[inline]
    pub const fn contains(self, attr: PhoneticAttribute) -> bool {
        (self.0 & attr.bit()) != 0
    }

    #[inline]
    pub const fn with(self, attr: PhoneticAttribute) -> Self {
        Self(self.0 | attr.bit())
    }

    #[inline]
    pub const fn without(self, attr: PhoneticAttribute) -> Self {
        Self(self.0 & !attr.bit())
    }

    #[inline]
    pub fn insert(&mut self, attr: PhoneticAttribute) {
        self.0 |= attr.bit();
    }

    #[inline]
    pub fn remove(&mut self, attr: PhoneticAttribute) {
        self.0 &= !attr.bit();
    }
}

impl fmt::Debug for PhonoMask {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let mut list = f.debug_list();
        for i in 0..(PhoneticAttribute::COUNT as u8) {
            if let Some(a) = PhoneticAttribute::from_u8(i) {
                if self.contains(a) {
                    list.entry(&a.short_form());
                }
            }
        }
        list.finish()
    }
}
