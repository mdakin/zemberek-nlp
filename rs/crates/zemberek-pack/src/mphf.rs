use core::mem::size_of;
use crate::{cast_ref, cast_slice, PackError, Pod};

pub const HASH_MULTIPLIER: u32 = 16_777_619;
pub const INITIAL_HASH_SEED: u32 = 0x811C_9DC5;

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct QwtSectionHeader {
    pub count: u32,
    pub level_count: u32,
    pub total_failed_u32: u32,
    pub total_seeds_bytes: u32,
}

const _: () = assert!(size_of::<QwtSectionHeader>() == 16);
unsafe impl Pod for QwtSectionHeader {}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct MphfLevelDesc {
    pub key_amount: u32,
    pub bucket_amount: u32,
    pub seeds_offset: u32,
    pub failed_offset: u32,
    pub failed_len: u32,
    pub _pad: u32,
}

const _: () = assert!(size_of::<MphfLevelDesc>() == 24);
unsafe impl Pod for MphfLevelDesc {}

/// Bit-exact port of `MultiLevelMphf.hash(int d0, int d1, int seed)` in Java Zemberek.
#[inline]
pub fn mphf_hash_u64(feature_key: u64, seed: i32) -> u32 {
    let d0 = feature_key as u32;
    let d1 = (feature_key >> 32) as u32;
    let mut d: u32 = if seed > 0 {
        seed as u32
    } else {
        INITIAL_HASH_SEED
    };
    d = (d ^ d0).wrapping_mul(HASH_MULTIPLIER);
    d = (d ^ d1).wrapping_mul(HASH_MULTIPLIER);
    d & 0x7FFF_FFFF
}

/// Bit-exact port of `FeatureHash.fingerprint16(long featureKey)`.
#[inline]
pub fn fingerprint16(feature_key: u64) -> u16 {
    let fp = ((feature_key >> 48) ^ (feature_key >> 32) ^ (feature_key >> 16)) as u16;
    if fp == 0 { 1 } else { fp }
}

/// Folds a 27-bit Java `String.hashCode() & 0x07FF_FFFF` fingerprint into a non-zero `u16`.
#[inline]
pub const fn fp16_from_fp27(fp27: u32) -> u16 {
    let fp = (((fp27 >> 11) ^ fp27) & 0xFFFF) as u16;
    if fp == 0 { 1 } else { fp }
}

/// Bit-exact port of `MultiLevelMphf.hash(String..., seed)` over UTF-8 `&str` segments without allocating.
#[inline]
pub fn mphf_hash_str_parts(parts: &[&str], seed: i32) -> u32 {
    let mut d: u32 = if seed > 0 {
        seed as u32
    } else {
        INITIAL_HASH_SEED
    };
    let mut u16_buf = [0u16; 2];
    for &part in parts {
        if part.is_ascii() {
            for &b in part.as_bytes() {
                d = (d ^ (b as u32)).wrapping_mul(HASH_MULTIPLIER);
            }
        } else {
            for ch in part.chars() {
                if (ch as u32) <= 0xFFFF {
                    d = (d ^ (ch as u32)).wrapping_mul(HASH_MULTIPLIER);
                } else {
                    for &u in ch.encode_utf16(&mut u16_buf).iter() {
                        d = (d ^ (u as u32)).wrapping_mul(HASH_MULTIPLIER);
                    }
                }
            }
        }
    }
    d & 0x7FFF_FFFF
}

/// Computes `(MultiLevelMphf.hash(parts, -1), java_hashcode & 0x07FF_FFFF)` in one zero-alloc pass.
#[inline]
pub fn mphf_init_and_java_fp27(parts: &[&str]) -> (u32, u32) {
    let mut d: u32 = INITIAL_HASH_SEED;
    let mut jh: i32 = 0;
    let mut u16_buf = [0u16; 2];
    for &part in parts {
        if part.is_ascii() {
            for &b in part.as_bytes() {
                let u = b as u32;
                d = (d ^ u).wrapping_mul(HASH_MULTIPLIER);
                jh = jh.wrapping_mul(31).wrapping_add(u as i32);
            }
        } else {
            for ch in part.chars() {
                if (ch as u32) <= 0xFFFF {
                    let u = ch as u32;
                    d = (d ^ u).wrapping_mul(HASH_MULTIPLIER);
                    jh = jh.wrapping_mul(31).wrapping_add(u as i32);
                } else {
                    for &u16_unit in ch.encode_utf16(&mut u16_buf).iter() {
                        let u = u16_unit as u32;
                        d = (d ^ u).wrapping_mul(HASH_MULTIPLIER);
                        jh = jh.wrapping_mul(31).wrapping_add(u as i32);
                    }
                }
            }
        }
    }
    (d & 0x7FFF_FFFF, (jh as u32) & 0x07FF_FFFF)
}

/// Zero-copy `MultiLevelMphf` view mapped directly over aligned `.zmbk` slices.
#[derive(Clone, Copy, Debug)]
pub struct MultiLevelMphfView<'a> {
    levels: &'a [MphfLevelDesc],
    failed_pool: &'a [u32],
    seeds_pool: &'a [u8],
}

impl<'a> MultiLevelMphfView<'a> {
    #[inline]
    pub(crate) const fn new(
        levels: &'a [MphfLevelDesc],
        failed_pool: &'a [u32],
        seeds_pool: &'a [u8],
    ) -> Self {
        Self {
            levels,
            failed_pool,
            seeds_pool,
        }
    }

    #[inline]
    pub fn size(&self) -> usize {
        self.levels.first().map_or(0, |l| l.key_amount as usize)
    }

    #[inline]
    pub fn level_count(&self) -> usize {
        self.levels.len()
    }

    /// Evaluates the minimal perfect hash slot `0..size()` for `feature_key`, matching
    /// `MultiLevelMphf.get(int k0, int k1, int initialHash)` bit-for-bit.
    #[inline]
    pub fn get_u64(&self, feature_key: u64) -> u32 {
        let initial_hash = mphf_hash_u64(feature_key, -1);
        for (i, lvl) in self.levels.iter().enumerate() {
            let bucket_idx = (initial_hash % lvl.bucket_amount) as usize;
            let seed = self.seeds_pool[lvl.seeds_offset as usize + bucket_idx] as i32;
            if seed != 0 {
                let slot = (mphf_hash_u64(feature_key, seed) % lvl.key_amount) as usize;
                if i == 0 {
                    return slot as u32;
                } else {
                    let prev = &self.levels[i - 1];
                    return self.failed_pool[prev.failed_offset as usize + slot];
                }
            }
        }
        0
    }

    /// Evaluates the minimal perfect hash slot `0..size()` for concatenated UTF-8 string parts,
    /// matching `MultiLevelMphf.get(String...)` bit-for-bit with zero heap allocations.
    #[inline]
    pub fn get_str_parts_with_init(&self, parts: &[&str], initial_hash: u32) -> u32 {
        for (i, lvl) in self.levels.iter().enumerate() {
            let bucket_idx = (initial_hash % lvl.bucket_amount) as usize;
            let seed = self.seeds_pool[lvl.seeds_offset as usize + bucket_idx] as i32;
            if seed != 0 {
                let slot = (mphf_hash_str_parts(parts, seed) % lvl.key_amount) as usize;
                if i == 0 {
                    return slot as u32;
                } else {
                    let prev = &self.levels[i - 1];
                    return self.failed_pool[prev.failed_offset as usize + slot];
                }
            }
        }
        0
    }
}

/// Zero-copy view over a `QuantizedWeightTable` (`MultiLevelMphf` + 16-bit FP + `f32[256]` codebook).
#[derive(Clone, Copy, Debug)]
pub struct QuantizedWeightTableView<'a> {
    mphf: MultiLevelMphfView<'a>,
    codebook: &'a [f32; 256],
    fingerprints: &'a [u16],
    codes: &'a [u8],
}

impl<'a> QuantizedWeightTableView<'a> {
    pub fn from_section_bytes(bytes: &'a [u8]) -> Result<Self, PackError> {
        let hdr = cast_ref::<QwtSectionHeader>(bytes)?;
        let count = hdr.count as usize;
        let level_count = hdr.level_count as usize;
        let total_failed = hdr.total_failed_u32 as usize;
        let total_seeds = hdr.total_seeds_bytes as usize;

        let mut cursor = size_of::<QwtSectionHeader>();

        let cb_end = cursor.checked_add(256 * 4).ok_or(PackError::MalformedSection)?;
        let cb_slice = cast_slice::<f32>(&bytes[cursor..cb_end], 256)?;
        let codebook: &'a [f32; 256] = cb_slice.try_into().map_err(|_| PackError::MalformedSection)?;
        cursor = cb_end;

        let lvls_bytes = level_count
            .checked_mul(size_of::<MphfLevelDesc>())
            .ok_or(PackError::MalformedSection)?;
        let lvls_end = cursor.checked_add(lvls_bytes).ok_or(PackError::MalformedSection)?;
        let levels = cast_slice::<MphfLevelDesc>(&bytes[cursor..lvls_end], level_count)?;
        cursor = lvls_end;

        let failed_bytes = total_failed.checked_mul(4).ok_or(PackError::MalformedSection)?;
        let failed_end = cursor.checked_add(failed_bytes).ok_or(PackError::MalformedSection)?;
        let failed_pool = cast_slice::<u32>(&bytes[cursor..failed_end], total_failed)?;
        cursor = failed_end;

        let fp_bytes = count.checked_mul(2).ok_or(PackError::MalformedSection)?;
        let fp_end = cursor.checked_add(fp_bytes).ok_or(PackError::MalformedSection)?;
        let fingerprints = cast_slice::<u16>(&bytes[cursor..fp_end], count)?;
        cursor = fp_end;

        let codes_end = cursor.checked_add(count).ok_or(PackError::MalformedSection)?;
        if codes_end > bytes.len() {
            return Err(PackError::MalformedSection);
        }
        let codes = &bytes[cursor..codes_end];
        cursor = codes_end;

        let seeds_end = cursor.checked_add(total_seeds).ok_or(PackError::MalformedSection)?;
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
        if count > 0 && levels.first().map_or(0, |l| l.key_amount as usize) != count {
            return Err(PackError::MalformedSection);
        }

        Ok(Self {
            mphf: MultiLevelMphfView {
                levels,
                failed_pool,
                seeds_pool,
            },
            codebook,
            fingerprints,
            codes,
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
    pub fn mphf(&self) -> &MultiLevelMphfView<'a> {
        &self.mphf
    }

    #[inline]
    pub fn get(&self, feature_key: u64) -> f32 {
        if self.codes.is_empty() {
            return 0.0;
        }
        let idx = self.mphf.get_u64(feature_key) as usize;
        if self.fingerprints[idx] == fingerprint16(feature_key) {
            self.codebook[self.codes[idx] as usize]
        } else {
            0.0
        }
    }
}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct MphfI16Header {
    pub count: u32,
    pub level_count: u32,
    pub total_failed_u32: u32,
    pub total_seeds_bytes: u32,
    pub scale: f32,
    pub reserved: u32,
}

const _: () = assert!(size_of::<MphfI16Header>() == 24);
unsafe impl Pod for MphfI16Header {}

/// Zero-copy view over a `MultiLevelMphf` + 27-bit fingerprint + `i16` fixed-point weight table.
#[derive(Clone, Copy, Debug)]
pub struct MphfI16WeightTableView<'a> {
    mphf: MultiLevelMphfView<'a>,
    scale: f32,
    fingerprints: &'a [u32],
    qweights: &'a [i16],
}

impl<'a> MphfI16WeightTableView<'a> {
    pub fn from_section_bytes(bytes: &'a [u8]) -> Result<Self, PackError> {
        let hdr = cast_ref::<MphfI16Header>(bytes)?;
        let count = hdr.count as usize;
        let level_count = hdr.level_count as usize;
        let total_failed = hdr.total_failed_u32 as usize;
        let total_seeds = hdr.total_seeds_bytes as usize;
        let mut cursor = size_of::<MphfI16Header>();

        let lvls_end = cursor
            .checked_add(level_count.checked_mul(24).ok_or(PackError::MalformedSection)?)
            .ok_or(PackError::MalformedSection)?;
        let levels = cast_slice::<MphfLevelDesc>(&bytes[cursor..lvls_end], level_count)?;
        cursor = lvls_end;

        let failed_end = cursor
            .checked_add(total_failed.checked_mul(4).ok_or(PackError::MalformedSection)?)
            .ok_or(PackError::MalformedSection)?;
        let failed_pool = cast_slice::<u32>(&bytes[cursor..failed_end], total_failed)?;
        cursor = failed_end;

        let fp_end = cursor
            .checked_add(count.checked_mul(4).ok_or(PackError::MalformedSection)?)
            .ok_or(PackError::MalformedSection)?;
        let fingerprints = cast_slice::<u32>(&bytes[cursor..fp_end], count)?;
        cursor = fp_end;

        let qw_end = cursor
            .checked_add(count.checked_mul(2).ok_or(PackError::MalformedSection)?)
            .ok_or(PackError::MalformedSection)?;
        let qweights = cast_slice::<i16>(&bytes[cursor..qw_end], count)?;
        cursor = qw_end;

        let seeds_end = cursor
            .checked_add(total_seeds)
            .ok_or(PackError::MalformedSection)?;
        if seeds_end > bytes.len() {
            return Err(PackError::MalformedSection);
        }
        let seeds_pool = &bytes[cursor..seeds_end];

        Ok(Self {
            mphf: MultiLevelMphfView {
                levels,
                failed_pool,
                seeds_pool,
            },
            scale: hdr.scale,
            fingerprints,
            qweights,
        })
    }

    #[inline]
    pub fn len(&self) -> usize {
        self.qweights.len()
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        self.qweights.is_empty()
    }

    #[inline]
    pub fn fingerprints(&self) -> &'a [u32] {
        self.fingerprints
    }

    #[inline]
    pub fn get_with_fp27(&self, parts: &[&str], fp27: u32) -> f32 {
        if self.qweights.is_empty() {
            return 0.0;
        }
        let init_hash = mphf_hash_str_parts(parts, -1);
        let idx = self.mphf.get_str_parts_with_init(parts, init_hash) as usize;
        if self.fingerprints[idx] == fp27 {
            (self.qweights[idx] as f32) * self.scale
        } else {
            0.0
        }
    }

    #[inline]
    pub fn get_str_parts(&self, parts: &[&str]) -> f32 {
        if self.qweights.is_empty() {
            return 0.0;
        }
        let (init_hash, fp27) = mphf_init_and_java_fp27(parts);
        let idx = self.mphf.get_str_parts_with_init(parts, init_hash) as usize;
        if self.fingerprints[idx] == fp27 {
            (self.qweights[idx] as f32) * self.scale
        } else {
            0.0
        }
    }
}
