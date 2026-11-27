#![no_std]
#![forbid(unsafe_code)]

extern crate alloc;

pub mod analysis;
pub mod analyzer;
pub mod condition_vm;
pub mod dynamic;
pub mod generator;
pub mod lexicon;
pub mod morphology;
pub mod stem_matcher;
pub mod unidentified;

pub use analysis::{AnalysisItem, CaseType, MorphemeData, SingleAnalysis, WordAnalysis, WordAnalysisSurfaceFormatter};
pub use analyzer::{AnalyzerScratch, RuleBasedAnalyzer};
pub use dynamic::{DynamicDictItem, DynamicStemTrie};
pub use generator::{GenerationResult, InformalAnalysisConverter, WordGenerator};
pub use lexicon::{DictItemRef, LexiconGraph};
pub use morphology::{MorphologyConfig, TurkishMorphology};
pub use stem_matcher::{StemMatch, StemSource};
