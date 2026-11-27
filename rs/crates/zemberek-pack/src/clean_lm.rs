use core::mem::size_of;
use crate::mphf::{MphfLevelDesc, MultiLevelMphfView};
use crate::{cast_ref, cast_slice, PackError, Pod};

pub const BOS_HASH: u64 = 0x517c_c1b7_2722_0a95;
pub const EOS_HASH: u64 = 0x9e37_79b9_7f4a_7c15;

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct CleanLmSectionHeader {
    pub denom_unigram: f64,
    pub unigram_bytes: u32,
    pub bigram_bytes: u32,
    pub trigram_bytes: u32,
    pub flags: u32,
    pub reserved: [u32; 2],
}

const _: () = assert!(size_of::<CleanLmSectionHeader>() == 32);
unsafe impl Pod for CleanLmSectionHeader {}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct CountMapHeader {
    pub count: u32,
    pub fp_bits: u32,
    pub level_count: u32,
    pub total_failed_u32: u32,
    pub total_seeds_bytes: u32,
    pub fp_byte_len: u32,
}

const _: () = assert!(size_of::<CountMapHeader>() == 24);
unsafe impl Pod for CountMapHeader {}

#[derive(Clone, Copy, Debug)]
enum FpStorage<'a> {
    Empty,
    Bits8(&'a [u8]),
    Bits12(&'a [u8]),
    Bits16(&'a [u16]),
}

/// Zero-copy port of `NormalizationKnowledge.MphfCountMap` (`8/12/16`-bit FP + `u32[256]` codebook).
#[derive(Clone, Copy, Debug)]
pub struct MphfCountMapView<'a> {
    mphf: Option<MultiLevelMphfView<'a>>,
    fp_bits: u32,
    fps: FpStorage<'a>,
    codes: &'a [u8],
    codebook: Option<&'a [u32; 256]>,
}

impl<'a> MphfCountMapView<'a> {
    pub fn from_bytes(bytes: &'a [u8]) -> Result<Self, PackError> {
        let hdr = cast_ref::<CountMapHeader>(bytes)?;
        let count = hdr.count as usize;
        let fp_bits = hdr.fp_bits;
        if count == 0 {
            return Ok(Self {
                mphf: None,
                fp_bits,
                fps: FpStorage::Empty,
                codes: &[],
                codebook: None,
            });
        }
        if !matches!(fp_bits, 8 | 12 | 16) {
            return Err(PackError::MalformedSection);
        }

        let level_count = hdr.level_count as usize;
        let total_failed = hdr.total_failed_u32 as usize;
        let total_seeds = hdr.total_seeds_bytes as usize;
        let fp_byte_len = hdr.fp_byte_len as usize;

        let mut cursor = size_of::<CountMapHeader>();

        let cb_end = cursor
            .checked_add(256 * 4)
            .ok_or(PackError::MalformedSection)?;
        let cb_slice = cast_slice::<u32>(&bytes[cursor..cb_end], 256)?;
        let codebook: &'a [u32; 256] = cb_slice
            .try_into()
            .map_err(|_| PackError::MalformedSection)?;
        cursor = cb_end;

        let lvls_bytes = level_count
            .checked_mul(size_of::<MphfLevelDesc>())
            .ok_or(PackError::MalformedSection)?;
        let lvls_end = cursor
            .checked_add(lvls_bytes)
            .ok_or(PackError::MalformedSection)?;
        let levels = cast_slice::<MphfLevelDesc>(&bytes[cursor..lvls_end], level_count)?;
        cursor = lvls_end;

        let failed_bytes = total_failed
            .checked_mul(4)
            .ok_or(PackError::MalformedSection)?;
        let failed_end = cursor
            .checked_add(failed_bytes)
            .ok_or(PackError::MalformedSection)?;
        let failed_pool = cast_slice::<u32>(&bytes[cursor..failed_end], total_failed)?;
        cursor = failed_end;

        let fp_end = cursor
            .checked_add(fp_byte_len)
            .ok_or(PackError::MalformedSection)?;
        if fp_end > bytes.len() {
            return Err(PackError::MalformedSection);
        }
        let fps = match fp_bits {
            16 => {
                if fp_byte_len != count.checked_mul(2).ok_or(PackError::MalformedSection)? {
                    return Err(PackError::MalformedSection);
                }
                FpStorage::Bits16(cast_slice::<u16>(&bytes[cursor..fp_end], count)?)
            }
            8 => {
                if fp_byte_len != count {
                    return Err(PackError::MalformedSection);
                }
                FpStorage::Bits8(&bytes[cursor..fp_end])
            }
            _ => {
                let expected = (count.checked_mul(3).ok_or(PackError::MalformedSection)?).div_ceil(2);
                if fp_byte_len != expected {
                    return Err(PackError::MalformedSection);
                }
                FpStorage::Bits12(&bytes[cursor..fp_end])
            }
        };
        cursor = fp_end;

        let codes_end = cursor
            .checked_add(count)
            .ok_or(PackError::MalformedSection)?;
        if codes_end > bytes.len() {
            return Err(PackError::MalformedSection);
        }
        let codes = &bytes[cursor..codes_end];
        cursor = codes_end;

        let seeds_end = cursor
            .checked_add(total_seeds)
            .ok_or(PackError::MalformedSection)?;
        if seeds_end > bytes.len() {
            return Err(PackError::MalformedSection);
        }
        let seeds_pool = &bytes[cursor..seeds_end];

        for (i, lvl) in levels.iter().enumerate() {
            if lvl.bucket_amount == 0 || lvl.key_amount == 0 {
                return Err(PackError::MalformedSection);
            }
            let s_end = (lvl.seeds_offset as usize)
                .checked_add(lvl.bucket_amount as usize)
                .ok_or(PackError::MalformedSection)?;
            if s_end > seeds_pool.len() {
                return Err(PackError::MalformedSection);
            }
            let f_end = (lvl.failed_offset as usize)
                .checked_add(lvl.failed_len as usize)
                .ok_or(PackError::MalformedSection)?;
            if f_end > failed_pool.len() {
                return Err(PackError::MalformedSection);
            }
            if i > 0 && levels[i - 1].failed_len < lvl.key_amount {
                return Err(PackError::MalformedSection);
            }
        }
        if levels.first().map_or(0, |l| l.key_amount as usize) != count {
            return Err(PackError::MalformedSection);
        }

        Ok(Self {
            mphf: Some(MultiLevelMphfView::new(levels, failed_pool, seeds_pool)),
            fp_bits,
            fps,
            codes,
            codebook: Some(codebook),
        })
    }

    #[inline]
    pub fn len(&self) -> usize {
        self.codes.len()
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        self.codes.is_empty()
    }

    #[inline]
    pub fn get(&self, mut k: u64) -> u32 {
        let (Some(mphf), Some(cb)) = (self.mphf, self.codebook) else {
            return 0;
        };
        if self.codes.is_empty() {
            return 0;
        }
        if k == 0 {
            k = 1;
        }
        let slot = mphf.get_u64(k) as usize;
        let want_fp = count_map_fingerprint(k, self.fp_bits);
        let got_fp = match self.fps {
            FpStorage::Empty => return 0,
            FpStorage::Bits16(arr) => arr[slot],
            FpStorage::Bits8(arr) => arr[slot] as u16,
            FpStorage::Bits12(arr) => get_u12_be(arr, slot),
        };
        if got_fp == want_fp {
            cb[self.codes[slot] as usize]
        } else {
            0
        }
    }
}

#[inline]
const fn count_map_fingerprint(k: u64, bits: u32) -> u16 {
    let z = (k ^ (k >> 23) ^ (k >> 47)).wrapping_mul(0x9E37_79B9_7F4A_7C15);
    ((z >> (64 - bits)) & ((1u64 << bits) - 1)) as u16
}

#[inline]
fn get_u12_be(arr: &[u8], idx: usize) -> u16 {
    let byte_idx = (idx * 3) >> 1;
    if (idx & 1) == 0 {
        (((arr[byte_idx] as u16) << 4) | (((arr[byte_idx + 1] & 0xF0) as u16) >> 4)) & 0x0FFF
    } else {
        ((((arr[byte_idx] & 0x0F) as u16) << 8) | (arr[byte_idx + 1] as u16)) & 0x0FFF
    }
}

/// Bit-exact port of `CleanWordLm.mix64`.
#[inline]
pub const fn mix64(mut z: u64) -> u64 {
    z = (z ^ (z >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
    z ^ (z >> 31)
}

/// Bit-exact port of `CleanWordLm.wordHash(String)` over UTF-16 code units (`charAt(i)`).
#[inline]
pub fn word_hash(s: &str) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    if s.is_ascii() {
        for &b in s.as_bytes() {
            h ^= b as u64;
            h = h.wrapping_mul(0x0100_0000_01b3);
        }
    } else {
        for u in s.encode_utf16() {
            h ^= u as u64;
            h = h.wrapping_mul(0x0100_0000_01b3);
        }
    }
    h = mix64(h);
    if h == 0 || h == BOS_HASH || h == EOS_HASH {
        h ^= 0x27d4_eb2f_1656_67c5;
    }
    h
}

/// Bit-exact port of `CleanWordLm.bigramHash`.
#[inline]
pub const fn bigram_hash(h1: u64, h2: u64) -> u64 {
    let z = mix64(h1 ^ 0x9E37_79B9_7F4A_7C15) ^ mix64(h2.wrapping_mul(0xbf58_476d_1ce4_e5b9));
    if z == 0 { 1 } else { z }
}

/// Bit-exact port of `CleanWordLm.trigramHash`.
#[inline]
pub const fn trigram_hash(h0: u64, h1: u64, h2: u64) -> u64 {
    let mut z = mix64(h0.wrapping_mul(0x94d0_49bb_1331_11eb));
    z = mix64(z ^ h1);
    z = mix64(z ^ h2.wrapping_mul(0xbf58_476d_1ce4_e5b9));
    if z == 0 { 1 } else { z }
}

/// Pure `#![no_std]` IEEE-754 natural logarithm (`< 1e-16` relative error across all normal `f64 > 0`).
#[inline]
pub fn ln_f64(x: f64) -> f64 {
    if x <= 0.0 {
        return f64::NEG_INFINITY;
    }
    if x.is_nan() || x.is_infinite() {
        return x;
    }
    let mut val = x;
    let mut exp_adjust = 0i32;
    let mut bits = val.to_bits();
    let mut exp_raw = ((bits >> 52) & 0x7FF) as i32;
    if exp_raw == 0 {
        val *= 4503599627370496.0; // 2^52
        exp_adjust = -52;
        bits = val.to_bits();
        exp_raw = ((bits >> 52) & 0x7FF) as i32;
    }
    let mut e = exp_raw - 1023 + exp_adjust;
    let mant_bits = bits & 0x000F_FFFF_FFFF_FFFF;
    let mut m = f64::from_bits((0x3FFu64 << 52) | mant_bits);
    if m > core::f64::consts::SQRT_2 {
        m *= 0.5;
        e += 1;
    }
    let u = (m - 1.0) / (m + 1.0);
    let u2 = u * u;
    let poly = 1.0
        + u2 * ((1.0 / 3.0)
            + u2 * ((1.0 / 5.0)
                + u2 * ((1.0 / 7.0)
                    + u2 * ((1.0 / 9.0)
                        + u2 * ((1.0 / 11.0)
                            + u2 * ((1.0 / 13.0) + u2 * ((1.0 / 15.0) + u2 * (1.0 / 17.0))))))));
    let ln_m = 2.0 * u * poly;
    (e as f64) * core::f64::consts::LN_2 + ln_m
}

/// Zero-copy port of `NormalizationKnowledge.CleanWordLm` (`SECT_CLEAN_LM = 7`).
#[derive(Clone, Copy, Debug)]
pub struct CleanWordLmView<'a> {
    pub unigrams: MphfCountMapView<'a>,
    pub bigrams: MphfCountMapView<'a>,
    pub trigrams: MphfCountMapView<'a>,
    pub denom_unigram: f64,
}

impl<'a> CleanWordLmView<'a> {
    pub fn from_section_bytes(bytes: &'a [u8]) -> Result<Self, PackError> {
        let hdr = cast_ref::<CleanLmSectionHeader>(bytes)?;
        let u_len = hdr.unigram_bytes as usize;
        let b_len = hdr.bigram_bytes as usize;
        let t_len = hdr.trigram_bytes as usize;
        if !u_len.is_multiple_of(8) || !b_len.is_multiple_of(8) || !t_len.is_multiple_of(8) {
            return Err(PackError::MalformedSection);
        }
        let u_start = size_of::<CleanLmSectionHeader>();
        let b_start = u_start.checked_add(u_len).ok_or(PackError::MalformedSection)?;
        let t_start = b_start.checked_add(b_len).ok_or(PackError::MalformedSection)?;
        let end = t_start.checked_add(t_len).ok_or(PackError::MalformedSection)?;
        if end > bytes.len() || hdr.denom_unigram.is_nan() || hdr.denom_unigram <= 0.0 {
            return Err(PackError::MalformedSection);
        }
        Ok(Self {
            unigrams: MphfCountMapView::from_bytes(&bytes[u_start..b_start])?,
            bigrams: MphfCountMapView::from_bytes(&bytes[b_start..t_start])?,
            trigrams: MphfCountMapView::from_bytes(&bytes[t_start..end])?,
            denom_unigram: hdr.denom_unigram,
        })
    }

    #[inline]
    pub fn unigram_count(&self, lower_word: &str) -> u32 {
        if lower_word.is_empty() {
            0
        } else {
            self.unigrams.get(word_hash(lower_word))
        }
    }

    #[inline]
    pub fn bigram_count(&self, prev_lower: Option<&str>, cur_lower: Option<&str>) -> u32 {
        let h1 = prev_lower.map_or(BOS_HASH, word_hash);
        let h2 = cur_lower.map_or(EOS_HASH, word_hash);
        self.bigrams.get(bigram_hash(h1, h2))
    }

    #[inline]
    pub fn unigram_log_prob(&self, lower_word: Option<&str>) -> f32 {
        let h = lower_word.map_or(EOS_HASH, word_hash);
        ln_f64(self.unigram_prob_by_hash(h)) as f32
    }

    #[inline]
    pub fn bigram_log_prob(&self, prev_lower: Option<&str>, cur_lower: Option<&str>) -> f32 {
        let h1 = prev_lower.map_or(BOS_HASH, word_hash);
        let h2 = cur_lower.map_or(EOS_HASH, word_hash);
        ln_f64(self.bigram_prob_by_hash(h1, h2)) as f32
    }

    #[inline]
    pub fn trigram_log_prob(
        &self,
        prev2_lower: Option<&str>,
        prev1_lower: Option<&str>,
        cur_lower: Option<&str>,
    ) -> f32 {
        let h2 = cur_lower.map_or(EOS_HASH, word_hash);
        let Some(p1) = prev1_lower else {
            return ln_f64(self.bigram_prob_by_hash(BOS_HASH, h2)) as f32;
        };
        let h1 = word_hash(p1);
        let h0 = prev2_lower.map_or(BOS_HASH, word_hash);
        ln_f64(self.trigram_prob_by_hash(h0, h1, h2)) as f32
    }

    #[inline]
    pub fn unigram_prob_by_hash(&self, h: u64) -> f64 {
        (self.unigrams.get(h) as f64 + 0.5) / self.denom_unigram
    }

    #[inline]
    pub fn bigram_prob_by_hash(&self, h_prev: u64, h_cur: u64) -> f64 {
        let p1 = self.unigram_prob_by_hash(h_cur);
        let c_prev = self.unigrams.get(h_prev);
        if c_prev == 0 {
            return p1;
        }
        let c_prev_f = c_prev as f64;
        let lambda2 = 0.82 * (c_prev_f / (c_prev_f + 8.0));
        let c_bi = self.bigrams.get(bigram_hash(h_prev, h_cur));
        if c_bi == 0 {
            return (1.0 - lambda2) * p1;
        }
        let mle = (c_bi as f64 / c_prev_f).min(1.0);
        lambda2 * mle + (1.0 - lambda2) * p1
    }

    #[inline]
    pub fn trigram_prob_by_hash(&self, h_prev2: u64, h_prev1: u64, h_cur: u64) -> f64 {
        let p2 = self.bigram_prob_by_hash(h_prev1, h_cur);
        let c_bi_hist = self.bigrams.get(bigram_hash(h_prev2, h_prev1));
        if c_bi_hist == 0 {
            return p2;
        }
        let c_hist_f = c_bi_hist as f64;
        let lambda3 = 0.72 * (c_hist_f / (c_hist_f + 6.0));
        let c_tri = self.trigrams.get(trigram_hash(h_prev2, h_prev1, h_cur));
        if c_tri == 0 {
            return (1.0 - lambda3) * p2;
        }
        let mle = (c_tri as f64 / c_hist_f).min(1.0);
        lambda3 * mle + (1.0 - lambda3) * p2
    }
}
