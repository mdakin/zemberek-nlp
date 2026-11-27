use alloc::string::String;
use alloc::vec;
use alloc::vec::Vec;
use core::cmp::Ordering;
use zemberek_core::TurkishAlphabet;
use zemberek_pack::mphf::QuantizedWeightTableView;
use crate::candidate::Candidate;
use crate::codec::{
    is_codec_apostrophe, is_codec_letter, is_java_digit, is_pinned_turkish_diacritic,
    to_ascii_lower_char, to_turkish_lower_char, DeascToken,
};
use crate::hash::{hash_of1, hash_of2, hash_of3, hash_prefix, hash_suffix};

const NUM_HARMONY_STATES: usize = 5;
const CONTEXT_RADIUS: usize = 6;

const OPTS_C: &[char] = &['c', 'ç'];
const OPTS_G: &[char] = &['g', 'ğ'];
const OPTS_I: &[char] = &['i', 'ı'];
const OPTS_O: &[char] = &['o', 'ö'];
const OPTS_S: &[char] = &['s', 'ş'];
const OPTS_U: &[char] = &['u', 'ü'];

pub struct ScoredSurface {
    pub surface: String,
    pub score: f32,
}

#[derive(Clone, Copy)]
pub struct CharacterHarmonyModel<'a> {
    pub weights: QuantizedWeightTableView<'a>,
}

impl<'a> CharacterHarmonyModel<'a> {
    #[inline]
    pub const fn new(weights: QuantizedWeightTableView<'a>) -> Self {
        Self { weights }
    }

    #[inline]
    fn sum_weights(&self, feats: &[u64]) -> f32 {
        let mut s = 0.0f32;
        for &k in feats {
            s += self.weights.get(k);
        }
        s
    }

    pub fn score_candidate_differences(
        &self,
        sentence_u16: &[u16],
        token: &DeascToken,
        candidates: &[Candidate],
    ) -> Vec<f32> {
        let k = candidates.len();
        let mut relative_scores = vec![0.0f32; k];
        if k <= 1 {
            return relative_scores;
        }

        let word_ascii_bytes = token.ascii_lower.as_bytes();
        let word_len = word_ascii_bytes.len();
        let mut cand_chars: Vec<Vec<char>> = Vec::with_capacity(k);
        for c in candidates {
            let cv: Vec<char> = c.surface.chars().collect();
            if cv.len() != word_len {
                return relative_scores;
            }
            cand_chars.push(cv);
        }

        let mut harmony_state = vec![0i32; k];
        let mut raw_scores = vec![0.0f32; k];
        let mut feat_buf = [0u64; 24];
        let word_prefix4 = hash_prefix(&token.ascii_lower, 4) as i64;
        let word_suffix4 = hash_suffix(&token.ascii_lower, 4) as i64;

        for pos in 0..word_len {
            let first_char = cand_chars[0][pos];
            let first_h = harmony_state[0];
            let mut all_identical = true;
            for ci in 1..k {
                if cand_chars[ci][pos] != first_char || harmony_state[ci] != first_h {
                    all_identical = false;
                    break;
                }
            }
            if all_identical {
                let next_h = advance_harmony(first_h, first_char);
                harmony_state.fill(next_h);
                continue;
            }

            let abs_pos = (token.start_u16 + pos) as isize;
            let context_window = encode_surrounding_window(sentence_u16, abs_pos);
            let surrounding_vowels = pack_surrounding_ascii_vowels(word_ascii_bytes, pos);

            for ci in 0..k {
                let ch = cand_chars[ci][pos];
                let prev_h = harmony_state[ci];
                let next_h = advance_harmony(prev_h, ch);

                let mut m = extract_emission_features(
                    context_window,
                    word_prefix4,
                    word_suffix4,
                    surrounding_vowels,
                    pos,
                    word_len,
                    ch,
                    &mut feat_buf,
                );
                m = append_transition_features(prev_h, ch, next_h, pos, word_len, &mut feat_buf, m);
                raw_scores[ci] += self.sum_weights(&feat_buf[..m]);
                harmony_state[ci] = next_h;
            }
        }

        let mut best = f32::NEG_INFINITY;
        for &s in &raw_scores {
            if s > best {
                best = s;
            }
        }
        for ci in 0..k {
            relative_scores[ci] = (raw_scores[ci] - best).clamp(-6.0, 0.0);
        }
        relative_scores
    }

    pub fn decode_top_candidates(
        &self,
        sentence_u16: &[u16],
        token: &DeascToken,
        max_candidates: usize,
    ) -> Vec<ScoredSurface> {
        let orig_chars: Vec<char> = token.original.chars().collect();
        let ascii_chars: Vec<char> = token.ascii_lower.chars().collect();
        let len = orig_chars.len();
        if len == 0 {
            return vec![ScoredSurface {
                surface: String::new(),
                score: 0.0,
            }];
        }

        let word_ascii_bytes = token.ascii_lower.as_bytes();
        let mut dp = vec![[f32::NEG_INFINITY; NUM_HARMONY_STATES]; len + 1];
        let mut back_state = vec![[0usize; NUM_HARMONY_STATES]; len];
        let mut back_char = vec![['\0'; NUM_HARMONY_STATES]; len];
        dp[0][0] = 0.0;

        let mut feat_buf = [0u64; 24];
        let word_prefix4 = hash_prefix(&token.ascii_lower, 4) as i64;
        let word_suffix4 = hash_suffix(&token.ascii_lower, 4) as i64;

        let mut options_at: Vec<[char; 2]> = vec![['\0'; 2]; len];
        let mut opt_lens: Vec<usize> = vec![0; len];
        let mut emit_scores_at: Vec<[f32; 2]> = vec![[0.0; 2]; len];

        for pos in 0..len {
            let mut single_buf = ['\0'; 1];
            let opts = allowed_options(
                orig_chars[pos],
                ascii_chars[pos],
                token.has_pinned_turkish,
                &mut single_buf,
            );
            opt_lens[pos] = opts.len();
            for (oi, &oc) in opts.iter().enumerate() {
                options_at[pos][oi] = oc;
            }

            if opts.len() > 1 {
                let abs_pos = (token.start_u16 + pos) as isize;
                let context_window = encode_surrounding_window(sentence_u16, abs_pos);
                let surrounding_vowels = pack_surrounding_ascii_vowels(word_ascii_bytes, pos);
                for (oi, &oc) in opts.iter().enumerate() {
                    let m = extract_emission_features(
                        context_window,
                        word_prefix4,
                        word_suffix4,
                        surrounding_vowels,
                        pos,
                        len,
                        oc,
                        &mut feat_buf,
                    );
                    emit_scores_at[pos][oi] = self.sum_weights(&feat_buf[..m]);
                }
            }

            for prev_h in 0..NUM_HARMONY_STATES {
                let prev_score = dp[pos][prev_h];
                if prev_score == f32::NEG_INFINITY {
                    continue;
                }
                for (oi, &ch) in opts.iter().enumerate() {
                    let next_h = advance_harmony(prev_h as i32, ch) as usize;
                    let tm = append_transition_features(
                        prev_h as i32,
                        ch,
                        next_h as i32,
                        pos,
                        len,
                        &mut feat_buf,
                        0,
                    );
                    let cand_score =
                        prev_score + emit_scores_at[pos][oi] + self.sum_weights(&feat_buf[..tm]);
                    if cand_score > dp[pos + 1][next_h] {
                        dp[pos + 1][next_h] = cand_score;
                        back_state[pos][next_h] = prev_h;
                        back_char[pos][next_h] = ch;
                    }
                }
            }
        }

        let mut best_final_h = 0usize;
        let mut best_score = f32::NEG_INFINITY;
        for (h, &s) in dp[len].iter().enumerate() {
            if s > best_score {
                best_score = s;
                best_final_h = h;
            }
        }

        let mut best_chars = vec!['\0'; len];
        let mut cur_h = best_final_h;
        for pos in (0..len).rev() {
            best_chars[pos] = back_char[pos][cur_h];
            cur_h = back_state[pos][cur_h];
        }
        let top1: String = best_chars.iter().collect();

        let mut results = Vec::with_capacity(max_candidates.min(3));
        results.push(ScoredSurface {
            surface: top1,
            score: best_score,
        });
        if max_candidates <= 1 {
            return results;
        }

        for pos in 0..len {
            let olen = opt_lens[pos];
            if olen <= 1 {
                continue;
            }
            for oi in 0..olen {
                let alt = options_at[pos][oi];
                if alt == best_chars[pos] {
                    continue;
                }
                let mut mutated = best_chars.clone();
                mutated[pos] = alt;
                let surf: String = mutated.iter().collect();
                if results.iter().any(|r| r.surface == surf) {
                    continue;
                }
                let score = self.score_full_path(
                    &mutated,
                    &options_at,
                    &opt_lens,
                    &emit_scores_at,
                    &mut feat_buf,
                );
                results.push(ScoredSurface {
                    surface: surf,
                    score,
                });
            }
        }
        results.sort_by(|a, b| java_desc_f32_cmp(a.score, b.score));
        if results.len() > max_candidates {
            results.truncate(max_candidates);
        }
        results
    }

    fn score_full_path(
        &self,
        path: &[char],
        options_at: &[[char; 2]],
        opt_lens: &[usize],
        emit_scores_at: &[[f32; 2]],
        feat_buf: &mut [u64; 24],
    ) -> f32 {
        let mut total = 0.0f32;
        let mut prev_h = 0i32;
        let len = path.len();
        for (pos, &ch) in path.iter().enumerate() {
            for oi in 0..opt_lens[pos] {
                if options_at[pos][oi] == ch {
                    total += emit_scores_at[pos][oi];
                    break;
                }
            }
            let next_h = advance_harmony(prev_h, ch);
            let tm = append_transition_features(prev_h, ch, next_h, pos, len, feat_buf, 0);
            total += self.sum_weights(&feat_buf[..tm]);
            prev_h = next_h;
        }
        total
    }
}

#[allow(clippy::too_many_arguments)]
fn extract_emission_features(
    context_window: u64,
    word_prefix4: i64,
    word_suffix4: i64,
    surrounding_vowels: i64,
    pos_in_word: usize,
    word_len: usize,
    candidate_char: char,
    out: &mut [u64; 24],
) -> usize {
    let l1 = (context_window & 0x1F) as i64;
    let l2 = (context_window & 0x3FF) as i64;
    let l3 = (context_window & 0x7FFF) as i64;
    let l5 = (context_window & 0x1FF_FFFF) as i64;
    let r1 = ((context_window >> 32) & 0x1F) as i64;
    let r2 = ((context_window >> 32) & 0x3FF) as i64;
    let r3 = ((context_window >> 32) & 0x7FFF) as i64;
    let r5 = ((context_window >> 32) & 0x1FF_FFFF) as i64;

    let dist_right = 6usize.min(word_len - 1 - pos_in_word) as i64;
    let dist_left = 6usize.min(pos_in_word) as i64;
    let ch = candidate_char as u32 as i64;

    out[0] = hash_of1(101, ch);
    out[1] = hash_of2(102, l1, ch);
    out[2] = hash_of2(103, l2, ch);
    out[3] = hash_of2(104, l3, ch);
    out[4] = hash_of2(105, l5, ch);
    out[5] = hash_of2(106, r1, ch);
    out[6] = hash_of2(107, r2, ch);
    out[7] = hash_of2(108, r3, ch);
    out[8] = hash_of2(109, r5, ch);
    out[9] = hash_of3(110, l1, r1, ch);
    out[10] = hash_of3(111, l2, r1, ch);
    out[11] = hash_of3(112, l1, r2, ch);
    out[12] = hash_of3(113, l2, r2, ch);
    out[13] = hash_of3(114, l3, r2, ch);
    out[14] = hash_of3(115, l2, r3, ch);
    out[15] = hash_of2(116, (dist_left << 4) | dist_right, ch);
    out[16] = hash_of3(117, word_suffix4, dist_right, ch);
    out[17] = hash_of3(118, word_prefix4, dist_left, ch);
    out[18] = hash_of3(119, surrounding_vowels, dist_right, ch);
    19
}

fn append_transition_features(
    prev_harmony: i32,
    candidate_char: char,
    next_harmony: i32,
    pos_in_word: usize,
    word_len: usize,
    out: &mut [u64; 24],
    mut m: usize,
) -> usize {
    let ch = candidate_char as u32 as i64;
    out[m] = hash_of2(130, prev_harmony as i64, ch);
    m += 1;
    out[m] = hash_of3(
        131,
        prev_harmony as i64,
        next_harmony as i64,
        5usize.min(pos_in_word) as i64,
    );
    m += 1;
    if pos_in_word + 1 == word_len {
        out[m] = hash_of2(132, prev_harmony as i64, ch);
        m += 1;
    }
    m
}

fn encode_surrounding_window(sentence_u16: &[u16], center_index: isize) -> u64 {
    encode_window_side(sentence_u16, center_index - 1, -1)
        | (encode_window_side(sentence_u16, center_index + 1, 1) << 32)
}

fn encode_window_side(sentence_u16: &[u16], start_index: isize, step: isize) -> u64 {
    let n = sentence_u16.len() as isize;
    let mut packed = 0u64;
    let mut p = start_index;
    let mut hit_space = false;
    for k in 0..CONTEXT_RADIUS {
        let sym: u64 = if p < 0 || p >= n {
            27
        } else {
            let raw_u = sentence_u16[p as usize];
            p += step;
            if let Some(c) = char::from_u32(raw_u as u32) {
                if TurkishAlphabet::is_java_whitespace(c) {
                    if hit_space {
                        27
                    } else {
                        hit_space = true;
                        28
                    }
                } else {
                    encode_symbol_5bit(c)
                }
            } else {
                // High/low surrogate UTF-16 code unit: non-whitespace, non-ASCII -> 31
                31
            }
        };
        packed |= sym << (k * 5);
    }
    packed
}

#[inline]
fn encode_symbol_5bit(c: char) -> u64 {
    let a = to_ascii_lower_char(c);
    if a.is_ascii_lowercase() {
        ((a as u8) - b'a' + 1) as u64
    } else if is_codec_apostrophe(c) {
        29
    } else if is_java_digit(c) {
        30
    } else {
        31
    }
}

fn pack_surrounding_ascii_vowels(word_ascii: &[u8], pos: usize) -> i64 {
    let prev_v = find_nearest_ascii_vowel(word_ascii, pos as isize - 1, -1);
    let next_v = find_nearest_ascii_vowel(word_ascii, pos as isize + 1, 1);
    (((prev_v as u64) << 16) | (next_v as u64)) as i64
}

fn find_nearest_ascii_vowel(word_ascii: &[u8], start: isize, step: isize) -> u8 {
    let mut i = start;
    let n = word_ascii.len() as isize;
    while i >= 0 && i < n {
        let c = word_ascii[i as usize];
        if matches!(c, b'a' | b'e' | b'i' | b'o' | b'u') {
            return c;
        }
        i += step;
    }
    b'_'
}

#[inline]
const fn advance_harmony(prev_harmony: i32, ch: char) -> i32 {
    match ch {
        'a' | 'ı' => 1,
        'o' | 'u' => 2,
        'e' | 'i' | 'â' | 'î' => 3,
        'ö' | 'ü' | 'û' => 4,
        _ => prev_harmony,
    }
}

#[inline]
fn allowed_options(
    raw_in: char,
    ascii_lower: char,
    pin_turkish: bool,
    single_buf: &mut [char; 1],
) -> &[char] {
    if !is_codec_letter(raw_in) {
        single_buf[0] = raw_in;
        return single_buf;
    }
    if pin_turkish && is_pinned_turkish_diacritic(raw_in) {
        single_buf[0] = to_turkish_lower_char(raw_in);
        return single_buf;
    }
    match ascii_lower {
        'c' => OPTS_C,
        'g' => OPTS_G,
        'i' => OPTS_I,
        'o' => OPTS_O,
        's' => OPTS_S,
        'u' => OPTS_U,
        _ => {
            single_buf[0] = ascii_lower;
            single_buf
        }
    }
}

#[inline]
fn java_desc_f32_cmp(a: f32, b: f32) -> Ordering {
    if b < a {
        Ordering::Less
    } else if b > a {
        Ordering::Greater
    } else {
        Ordering::Equal
    }
}
