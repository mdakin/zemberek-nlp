use crate::alphabet::TurkishAlphabet;
use crate::enums::{PhonoMask, PhoneticAttribute};

const NO_VOWEL_MASK: u32 = PhoneticAttribute::LastLetterConsonant.bit()
    | PhoneticAttribute::FirstLetterConsonant.bit()
    | PhoneticAttribute::HasNoVowel.bit();

/// Exact port of `zemberek.morphology.analysis.AttributesHelper.getMorphemicAttributes`.
#[inline]
pub fn compute_morphemic_attributes(seq: &str, predecessor: PhonoMask) -> PhonoMask {
    if seq.is_empty() {
        return predecessor;
    }

    let mut attrs = PhonoMask::EMPTY;
    let last = TurkishAlphabet::last_letter(seq);
    if TurkishAlphabet::contains_vowel(seq) {
        if last.is_vowel() {
            attrs.insert(PhoneticAttribute::LastLetterVowel);
        } else {
            attrs.insert(PhoneticAttribute::LastLetterConsonant);
        }

        let last_vowel = if last.is_vowel() {
            last
        } else {
            TurkishAlphabet::last_vowel(seq)
        };

        if last_vowel.is_frontal() {
            attrs.insert(PhoneticAttribute::LastVowelFrontal);
        } else {
            attrs.insert(PhoneticAttribute::LastVowelBack);
        }

        if last_vowel.is_rounded() {
            attrs.insert(PhoneticAttribute::LastVowelRounded);
        } else {
            attrs.insert(PhoneticAttribute::LastVowelUnrounded);
        }

        if TurkishAlphabet::first_letter(seq).is_vowel() {
            attrs.insert(PhoneticAttribute::FirstLetterVowel);
        } else {
            attrs.insert(PhoneticAttribute::FirstLetterConsonant);
        }
    } else {
        attrs = PhonoMask((predecessor.0 | NO_VOWEL_MASK)
            & !PhoneticAttribute::LastLetterVowel.bit()
            & !PhoneticAttribute::ExpectsConsonant.bit());
    }

    if last.is_voiceless() {
        attrs.insert(PhoneticAttribute::LastLetterVoiceless);
        if last.is_stop_consonant() {
            attrs.insert(PhoneticAttribute::LastLetterVoicelessStop);
        }
    } else {
        attrs.insert(PhoneticAttribute::LastLetterVoiced);
    }

    attrs
}
