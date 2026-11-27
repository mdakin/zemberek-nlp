use alloc::string::String;
use alloc::vec::Vec;
use zemberek_core::{PrimaryPos, SecondaryPos};
use crate::codec::{strip_circumflex, u16_len};
use crate::hash::hash_str;

#[derive(Clone, Debug)]
pub struct Candidate {
    pub surface: String,
    pub lemma: String,
    pub pos: PrimaryPos,
    pub secondary_pos: SecondaryPos,
    pub last_group_pos: PrimaryPos,
    pub case_morpheme: &'static str,
    pub possessive_morpheme: &'static str,
    pub plural: bool,
    pub informal: bool,
    pub negative_or_imperative: bool,
    pub question: bool,
    pub circumflex_variant: bool,
    pub oov_fallback: bool,
    pub foreign_stem_length: usize,
    pub morpheme_group_count: usize,

    pub surface_hash: u64,
    pub lemma_hash: u64,
    pub base_lemma_hash: u64,
    pub morph_tag_hash: u64,
    pub case_hash: u64,
    pub last_vowel_harmony: i32,
    pub content_word: bool,
}

impl Candidate {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        surface: String,
        lemma: String,
        pos: PrimaryPos,
        secondary_pos: SecondaryPos,
        last_group_pos: PrimaryPos,
        case_morpheme: &'static str,
        possessive_morpheme: &'static str,
        plural: bool,
        informal: bool,
        negative_or_imperative: bool,
        question: bool,
        circumflex_variant: bool,
        oov_fallback: bool,
        foreign_stem_length: usize,
        morpheme_group_count: usize,
    ) -> Self {
        let surface_hash = hash_str(&surface);
        let lemma_hash = hash_str(&lemma);
        let stripped = strip_circumflex(&lemma);
        let base_lemma_hash = if stripped == lemma {
            lemma_hash
        } else {
            hash_str(&stripped)
        };
        let case_hash = hash_str(case_morpheme);
        let morph_tag_hash = if oov_fallback {
            hash_str("OOV")
        } else {
            let mut tag = String::with_capacity(40);
            tag.push_str(pos.short_form());
            tag.push(':');
            tag.push_str(secondary_pos.short_form());
            tag.push(':');
            tag.push_str(last_group_pos.short_form());
            tag.push(':');
            tag.push_str(case_morpheme);
            tag.push(':');
            tag.push_str(possessive_morpheme);
            if plural {
                tag.push_str(":PL");
            }
            if negative_or_imperative {
                tag.push_str(":NEG");
            }
            if question {
                tag.push_str(":Q");
            }
            hash_str(&tag)
        };
        let last_vowel_harmony = compute_last_vowel_harmony(&surface);
        let content_word = compute_is_content_word(pos, &surface, oov_fallback);

        Self {
            surface,
            lemma,
            pos,
            secondary_pos,
            last_group_pos,
            case_morpheme,
            possessive_morpheme,
            plural,
            informal,
            negative_or_imperative,
            question,
            circumflex_variant,
            oov_fallback,
            foreign_stem_length,
            morpheme_group_count,
            surface_hash,
            lemma_hash,
            base_lemma_hash,
            morph_tag_hash,
            case_hash,
            last_vowel_harmony,
            content_word,
        }
    }

    pub fn boundary() -> Self {
        Self::new(
            String::from("<s>"),
            String::from("<s>"),
            PrimaryPos::Unknown,
            SecondaryPos::None,
            PrimaryPos::Unknown,
            "Nom",
            "Pnon",
            false,
            false,
            false,
            false,
            false,
            true,
            0,
            0,
        )
    }

    pub fn oov(lower_surface: &str, foreign_stem_length: usize) -> Self {
        Self::new(
            String::from(lower_surface),
            String::from(lower_surface),
            PrimaryPos::Unknown,
            SecondaryPos::None,
            PrimaryPos::Unknown,
            "Nom",
            "Pnon",
            false,
            false,
            false,
            false,
            false,
            true,
            foreign_stem_length,
            1,
        )
    }
}

pub fn compute_last_vowel_harmony(word: &str) -> i32 {
    for ch in word.chars().rev() {
        match ch {
            'a' | 'ı' => return 1,
            'e' | 'i' | 'â' | 'î' => return 2,
            'o' | 'u' => return 3,
            'ö' | 'ü' | 'û' => return 4,
            _ => {}
        }
    }
    0
}

fn compute_is_content_word(pos: PrimaryPos, surface: &str, oov: bool) -> bool {
    let len = u16_len(surface);
    if len < 3 {
        return false;
    }
    if oov {
        return len >= 4;
    }
    matches!(
        pos,
        PrimaryPos::Noun | PrimaryPos::Verb | PrimaryPos::Adjective | PrimaryPos::Adverb
    )
}

#[inline]
pub fn add_if_surface_absent(list: &mut Vec<Candidate>, cand: Candidate) -> bool {
    if list.iter().any(|c| c.surface == cand.surface) {
        false
    } else {
        list.push(cand);
        true
    }
}
