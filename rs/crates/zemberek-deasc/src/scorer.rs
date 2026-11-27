use alloc::vec::Vec;
use zemberek_core::{PrimaryPos, SecondaryPos};
use zemberek_pack::mphf::QuantizedWeightTableView;
use crate::candidate::Candidate;
use crate::codec::{u16_len, Casing, DeascToken};
use crate::hash::{hash_of1, hash_of2, hash_of3, hash_prefix, hash_str, hash_suffix, java_math_round};
use crate::priors::LexiconPriors;

pub const FEATURE_BUFFER_SIZE: usize = 48;

const FEAT_UNIGRAM_SURFACE: i32 = 501;
const FEAT_UNIGRAM_LEMMA: i32 = 502;
const FEAT_RIGHT_PUNCT_LEMMA: i32 = 503;
const FEAT_RIGHT_PUNCT_MORPH: i32 = 504;
const FEAT_SENT_END_PUNCT_MORPH: i32 = 505;
const FEAT_CASING_PROPER_POS: i32 = 506;
const FEAT_CHAR_SCORE_BUCKET: i32 = 507;

const FEAT_PREV_SUFFIX_LEMMA: i32 = 510;
const FEAT_NEXT_SUFFIX_LEMMA: i32 = 511;
const FEAT_PREV_ASCII_LEMMA: i32 = 512;
const FEAT_PREV_ASCII_SURFACE: i32 = 513;
const FEAT_PREV_PREFIX_LEMMA: i32 = 514;
const FEAT_NEXT_ASCII_LEMMA: i32 = 515;
const FEAT_NEXT_ASCII_SURFACE: i32 = 516;
const FEAT_NEXT_PREFIX_LEMMA: i32 = 517;
const FEAT_SANDWICH_ASCII_SURFACE: i32 = 518;
const FEAT_WIDE_ASCII_STEM_LEMMA: i32 = 519;

const FEAT_BIGRAM_SURFACE: i32 = 520;
const FEAT_BIGRAM_LEMMA: i32 = 521;
const FEAT_PREV_MORPH_CUR_LEMMA: i32 = 522;
const FEAT_PREV_LEMMA_CUR_MORPH: i32 = 523;
const FEAT_TRIGRAM_LEMMA: i32 = 524;
const FEAT_TRIGRAM_MORPH_LEMMA: i32 = 525;
const FEAT_PREV_CASE_CUR_LEMMA: i32 = 526;
const FEAT_PREV_POS_CUR_POS: i32 = 527;
const FEAT_PREV_HARMONY_SURFACE: i32 = 528;
const FEAT_PREV_HARMONY_QUESTION: i32 = 529;
const FEAT_PREV_POS_NUM_CUR_NUM: i32 = 530;

const FEAT_WIDE_CONTENT_LEMMA: i32 = 541;
const FEAT_WIDE_DIRECTIONAL_LEMMA: i32 = 542;

#[inline]
fn sum_qwt(weights: &QuantizedWeightTableView<'_>, feats: &[u64]) -> f32 {
    let mut s = 0.0f32;
    for &k in feats {
        s += weights.get(k);
    }
    s
}

#[allow(clippy::too_many_arguments)]
pub fn score_unary(
    token_index: usize,
    tokens: &[DeascToken],
    sentence_end_punct: char,
    sentence_capitalized: bool,
    cand: &Candidate,
    empirical_surface_log_prob: f32,
    char_relative_score: f32,
    priors: &LexiconPriors<'_>,
    weights: &QuantizedWeightTableView<'_>,
    feat_buf: &mut [u64; FEATURE_BUFFER_SIZE],
) -> f32 {
    let det = compute_deterministic_prior(
        token_index,
        tokens,
        sentence_capitalized,
        cand,
        empirical_surface_log_prob,
        char_relative_score,
        priors,
    );
    let m = extract_unary_features(
        token_index,
        tokens,
        sentence_end_punct,
        sentence_capitalized,
        cand,
        char_relative_score,
        feat_buf,
    );
    det + sum_qwt(weights, &feat_buf[..m])
}

fn compute_deterministic_prior(
    token_index: usize,
    tokens: &[DeascToken],
    sentence_capitalized: bool,
    cand: &Candidate,
    empirical_surface_log_prob: f32,
    char_relative_score: f32,
    priors: &LexiconPriors<'_>,
) -> f32 {
    let token = &tokens[token_index];
    let mut s = 0.75 * empirical_surface_log_prob + 0.25 * char_relative_score;

    if !cand.oov_fallback && empirical_surface_log_prob == 0.0 && priors.lemma_prior_size() > 0 {
        let lemma_log_count = priors.get_lemma_log_count(cand);
        s += if lemma_log_count > 0.0 {
            0.14 * (lemma_log_count - 4.0)
        } else {
            -0.28
        };
    }

    if cand.oov_fallback {
        s -= 1.60;
    } else {
        s -= 0.18 * (cand.morpheme_group_count.saturating_sub(1) as f32);
        if cand.informal {
            s -= 0.35;
        }
        if cand.secondary_pos == SecondaryPos::Abbreviation && token.casing != Casing::Upper {
            s -= 2.2;
        }
        if token.casing == Casing::Title && token.has_apostrophe() {
            s += if cand.secondary_pos == SecondaryPos::ProperNoun {
                1.25
            } else {
                -0.35
            };
        } else if !token.sentence_start && token.casing == Casing::Title {
            let bare_proper = cand.secondary_pos == SecondaryPos::ProperNoun
                && cand.case_morpheme == "Nom"
                && cand.possessive_morpheme == "Pnon";
            if bare_proper {
                s += 0.65;
            }
        } else if token.casing == Casing::Lower && cand.secondary_pos == SecondaryPos::ProperNoun {
            s -= if sentence_capitalized && !token.sentence_start {
                1.25
            } else {
                0.45
            };
        }

        if cand.pos == PrimaryPos::Duplicator {
            let reduplicated = (token_index > 0
                && tokens[token_index - 1].ascii_lower == token.ascii_lower)
                || (token_index + 1 < tokens.len()
                    && tokens[token_index + 1].ascii_lower == token.ascii_lower);
            if reduplicated {
                s += 1.6;
            }
        }

        if cand.pos == PrimaryPos::Noun
            && cand.last_group_pos == PrimaryPos::Verb
            && cand.possessive_morpheme != "Pnon"
            && (cand.surface.ends_with("sın")
                || cand.surface.ends_with("sin")
                || cand.surface.ends_with("sun")
                || cand.surface.ends_with("sün"))
        {
            s -= 1.6;
        }
    }

    if LexiconPriors::is_taboo_surface(&cand.surface) && token.original != cand.surface {
        s -= 4.5;
    }
    s
}

fn extract_unary_features(
    token_index: usize,
    tokens: &[DeascToken],
    sentence_end_punct: char,
    sentence_capitalized: bool,
    cand: &Candidate,
    char_relative_score: f32,
    out: &mut [u64; FEATURE_BUFFER_SIZE],
) -> usize {
    let token = &tokens[token_index];
    let n = tokens.len();
    let mut m = 0usize;

    let surf_h = cand.surface_hash as i64;
    let lemma_h = cand.lemma_hash as i64;
    let morph_h = cand.morph_tag_hash as i64;

    out[m] = hash_of1(FEAT_UNIGRAM_SURFACE, surf_h);
    m += 1;
    out[m] = hash_of1(FEAT_UNIGRAM_LEMMA, lemma_h);
    m += 1;

    if token.right_punct != ' ' {
        let rp = token.right_punct as u32 as i64;
        out[m] = hash_of2(FEAT_RIGHT_PUNCT_LEMMA, lemma_h, rp);
        m += 1;
        out[m] = hash_of2(FEAT_RIGHT_PUNCT_MORPH, morph_h, rp);
        m += 1;
    }
    if sentence_end_punct == '?' || sentence_end_punct == '!' {
        out[m] = hash_of2(
            FEAT_SENT_END_PUNCT_MORPH,
            morph_h,
            sentence_end_punct as u32 as i64,
        );
        m += 1;
    }

    let shape_key = ((token.casing as i64) << 2)
        | if token.sentence_start { 2 } else { 0 }
        | if sentence_capitalized { 1 } else { 0 };
    out[m] = hash_of3(
        FEAT_CASING_PROPER_POS,
        shape_key,
        i64::from(cand.secondary_pos == SecondaryPos::ProperNoun),
        cand.pos as i64,
    );
    m += 1;

    let char_bucket = java_math_round(char_relative_score).clamp(-6, 0) as i64;
    out[m] = hash_of2(
        FEAT_CHAR_SCORE_BUCKET,
        char_bucket,
        i64::from(cand.oov_fallback),
    );
    m += 1;

    let mut prev_ascii_hash: u64 = 0;
    if token_index > 0 {
        let prev_ascii = &tokens[token_index - 1].ascii_lower;
        prev_ascii_hash = hash_str(prev_ascii);
        out[m] = hash_of2(FEAT_PREV_ASCII_LEMMA, prev_ascii_hash as i64, lemma_h);
        m += 1;
        out[m] = hash_of2(FEAT_PREV_ASCII_SURFACE, prev_ascii_hash as i64, surf_h);
        m += 1;
        if u16_len(prev_ascii) >= 3 {
            out[m] = hash_of2(
                FEAT_PREV_PREFIX_LEMMA,
                hash_prefix(prev_ascii, 5) as i64,
                lemma_h,
            );
            m += 1;
            out[m] = hash_of2(
                FEAT_PREV_SUFFIX_LEMMA,
                hash_suffix(prev_ascii, 3) as i64,
                lemma_h,
            );
            m += 1;
        }
    }

    let mut next_ascii_hash: u64 = 0;
    if token_index + 1 < n {
        let next_ascii = &tokens[token_index + 1].ascii_lower;
        next_ascii_hash = hash_str(next_ascii);
        out[m] = hash_of2(FEAT_NEXT_ASCII_LEMMA, lemma_h, next_ascii_hash as i64);
        m += 1;
        out[m] = hash_of2(FEAT_NEXT_ASCII_SURFACE, surf_h, next_ascii_hash as i64);
        m += 1;
        if u16_len(next_ascii) >= 3 {
            out[m] = hash_of2(
                FEAT_NEXT_PREFIX_LEMMA,
                lemma_h,
                hash_prefix(next_ascii, 5) as i64,
            );
            m += 1;
            out[m] = hash_of2(
                FEAT_NEXT_SUFFIX_LEMMA,
                lemma_h,
                hash_suffix(next_ascii, 2) as i64,
            );
            m += 1;
        }
    }

    if prev_ascii_hash != 0 && next_ascii_hash != 0 {
        out[m] = hash_of3(
            FEAT_SANDWICH_ASCII_SURFACE,
            prev_ascii_hash as i64,
            surf_h,
            next_ascii_hash as i64,
        );
        m += 1;
    }

    let w_start = token_index.saturating_sub(5);
    let w_end = (n - 1).min(token_index + 5);
    for (j, tok_j) in tokens.iter().enumerate().take(w_end + 1).skip(w_start) {
        if j + 1 >= token_index && j <= token_index + 1 {
            continue;
        }
        let ctx_ascii = &tok_j.ascii_lower;
        if u16_len(ctx_ascii) >= 4 {
            out[m] = hash_of2(
                FEAT_WIDE_ASCII_STEM_LEMMA,
                lemma_h,
                hash_prefix(ctx_ascii, 5) as i64,
            );
            m += 1;
        }
    }
    m
}

pub fn score_transition(
    prev2: &Candidate,
    prev1: &Candidate,
    cur: &Candidate,
    weights: &QuantizedWeightTableView<'_>,
    feat_buf: &mut [u64; FEATURE_BUFFER_SIZE],
) -> f32 {
    let mut m = 0usize;
    let p1_surf = prev1.surface_hash as i64;
    let p1_lemma = prev1.lemma_hash as i64;
    let p1_morph = prev1.morph_tag_hash as i64;
    let cur_surf = cur.surface_hash as i64;
    let cur_lemma = cur.lemma_hash as i64;

    feat_buf[m] = hash_of2(FEAT_BIGRAM_SURFACE, p1_surf, cur_surf);
    m += 1;
    feat_buf[m] = hash_of2(FEAT_BIGRAM_LEMMA, p1_lemma, cur_lemma);
    m += 1;
    feat_buf[m] = hash_of2(FEAT_PREV_MORPH_CUR_LEMMA, p1_morph, cur_lemma);
    m += 1;
    feat_buf[m] = hash_of2(FEAT_PREV_LEMMA_CUR_MORPH, p1_lemma, cur.morph_tag_hash as i64);
    m += 1;
    feat_buf[m] = hash_of3(
        FEAT_TRIGRAM_LEMMA,
        prev2.lemma_hash as i64,
        p1_lemma,
        cur_lemma,
    );
    m += 1;
    feat_buf[m] = hash_of3(
        FEAT_TRIGRAM_MORPH_LEMMA,
        prev2.morph_tag_hash as i64,
        p1_morph,
        cur_lemma,
    );
    m += 1;
    feat_buf[m] = hash_of2(FEAT_PREV_CASE_CUR_LEMMA, prev1.case_hash as i64, cur_lemma);
    m += 1;
    feat_buf[m] = hash_of2(
        FEAT_PREV_POS_CUR_POS,
        prev1.last_group_pos as i64,
        cur.pos as i64,
    );
    m += 1;
    feat_buf[m] = hash_of2(
        FEAT_PREV_POS_NUM_CUR_NUM,
        ((prev1.pos as i64) << 1) | i64::from(prev1.plural),
        ((cur.pos as i64) << 1) | i64::from(cur.plural),
    );
    m += 1;
    feat_buf[m] = hash_of2(
        FEAT_PREV_HARMONY_SURFACE,
        prev1.last_vowel_harmony as i64,
        cur_surf,
    );
    m += 1;
    if cur.question {
        feat_buf[m] = hash_of2(
            FEAT_PREV_HARMONY_QUESTION,
            prev1.last_vowel_harmony as i64,
            cur_surf,
        );
        m += 1;
    }
    sum_qwt(weights, &feat_buf[..m])
}

pub fn score_trigram_only(
    prev2: &Candidate,
    prev1: &Candidate,
    cur: &Candidate,
    weights: &QuantizedWeightTableView<'_>,
    feat_buf: &mut [u64; FEATURE_BUFFER_SIZE],
) -> f32 {
    feat_buf[0] = hash_of3(
        FEAT_TRIGRAM_LEMMA,
        prev2.lemma_hash as i64,
        prev1.lemma_hash as i64,
        cur.lemma_hash as i64,
    );
    feat_buf[1] = hash_of3(
        FEAT_TRIGRAM_MORPH_LEMMA,
        prev2.morph_tag_hash as i64,
        prev1.morph_tag_hash as i64,
        cur.lemma_hash as i64,
    );
    sum_qwt(weights, &feat_buf[..2])
}

#[allow(clippy::too_many_arguments)]
pub fn score_candidate_in_sequence(
    token_index: usize,
    chosen_indices: &[usize],
    columns: &[Vec<Candidate>],
    boundary: &Candidate,
    cand: &Candidate,
    unary_score: f32,
    weights: &QuantizedWeightTableView<'_>,
    feat_buf: &mut [u64; FEATURE_BUFFER_SIZE],
) -> f32 {
    let seq_len = chosen_indices.len();
    let prev2 = if token_index >= 2 {
        &columns[token_index - 2][chosen_indices[token_index - 2]]
    } else {
        boundary
    };
    let prev1 = if token_index >= 1 {
        &columns[token_index - 1][chosen_indices[token_index - 1]]
    } else {
        boundary
    };
    let mut score = unary_score + score_transition(prev2, prev1, cand, weights, feat_buf);
    if token_index + 1 < seq_len {
        let next1 = &columns[token_index + 1][chosen_indices[token_index + 1]];
        score += score_transition(prev1, cand, next1, weights, feat_buf);
        if token_index + 2 < seq_len {
            let next2 = &columns[token_index + 2][chosen_indices[token_index + 2]];
            score += score_trigram_only(cand, next1, next2, weights, feat_buf);
        }
    }

    let mut m = 0usize;
    let start = token_index.saturating_sub(6);
    let end = (seq_len - 1).min(token_index + 6);
    let cand_lemma = cand.lemma_hash as i64;
    for j in start..=end {
        let dist = (j as isize) - (token_index as isize);
        if (-1..=1).contains(&dist) {
            continue;
        }
        let ctx = &columns[j][chosen_indices[j]];
        if !ctx.content_word {
            continue;
        }
        let ctx_lemma = ctx.lemma_hash as i64;
        feat_buf[m] = hash_of2(FEAT_WIDE_CONTENT_LEMMA, cand_lemma, ctx_lemma);
        m += 1;
        if (-3..=3).contains(&dist) {
            feat_buf[m] = hash_of3(
                FEAT_WIDE_DIRECTIONAL_LEMMA,
                cand_lemma,
                ctx_lemma,
                if dist < 0 { -1 } else { 1 },
            );
            m += 1;
        }
    }
    score + sum_qwt(weights, &feat_buf[..m])
}
