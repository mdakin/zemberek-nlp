#![no_std]
#![forbid(unsafe_code)]

extern crate alloc;

pub mod alphabet;
pub mod enums;
pub mod numbers;
pub mod phonology;
pub mod pronunciation;
pub mod syllable;

pub use alphabet::{TurkicLetter, TurkishAlphabet};
pub use enums::{
    PhonoMask, PhoneticAttribute, PrimaryPos, RootAttrMask, RootAttribute, SecondaryPos,
};
pub use numbers::{TurkishNumbers, TurkishNumeralEndingMachine};
pub use phonology::compute_morphemic_attributes;
pub use pronunciation::PronunciationGuesser;
pub use syllable::TurkishSyllableExtractor;
