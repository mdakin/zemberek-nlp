#![no_std]
#![forbid(unsafe_code)]

extern crate alloc;

pub mod detokenizer;
mod emoji;
pub mod lexer;
pub mod sentence;
pub mod span_token;

mod data {
    include!(concat!(env!("OUT_DIR"), "/generated_tok_data.rs"));
}

pub use detokenizer::TurkishDetokenizer;
pub use lexer::{Token, TokenSpan, TokenType, TurkishTokenizer, TurkishTokenizerBuilder};
pub use sentence::{SentenceSpan, TurkishSentenceExtractor};
pub use span_token::{reconstruct_text, span_tokenize, CasingShape, SpanEditLike, SpanToken};
