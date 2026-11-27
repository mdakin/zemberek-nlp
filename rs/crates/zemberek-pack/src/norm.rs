use core::mem::size_of;
use crate::clean_lm::ln_f64;
use crate::mphf::QuantizedWeightTableView;
use crate::{cast_ref, cast_slice, PackError, Pod};

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct NormKnowledgeHeader {
    pub p1_count: u32,
    pub p2_count: u32,
    pub p3_count: u32,
    pub target_count: u32,
    pub suf_bucket_count: u32,
    pub suf_rule_count: u32,
    pub casing_count: u32,
    pub skel_entry_count: u32,
    pub skel_target_count: u32,
    pub str_pool_len: u32,
    pub reserved: [u32; 6],
}

const _: () = assert!(size_of::<NormKnowledgeHeader>() == 64);
unsafe impl Pod for NormKnowledgeHeader {}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct PackedRewriteEntry {
    pub key_off_len: u32,
    pub total_count: u32,
    pub keep_count: u32,
    pub target_start_and_count: u32,
}

const _: () = assert!(size_of::<PackedRewriteEntry>() == 16);
unsafe impl Pod for PackedRewriteEntry {}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct PackedMergeEntry {
    pub key_off_len: u32,
    pub cooccur_count: u32,
    pub target_start_and_count: u32,
}

const _: () = assert!(size_of::<PackedMergeEntry>() == 12);
unsafe impl Pod for PackedMergeEntry {}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct PackedTargetCount {
    pub norm_off_len: u32,
    pub count: u32,
}

const _: () = assert!(size_of::<PackedTargetCount>() == 8);
unsafe impl Pod for PackedTargetCount {}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct PackedSuffixBucket {
    pub last_char: u32,
    pub rule_start_and_count: u32,
}

const _: () = assert!(size_of::<PackedSuffixBucket>() == 8);
unsafe impl Pod for PackedSuffixBucket {}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct PackedSuffixRule {
    pub raw_off_len: u32,
    pub norm_off_len: u32,
    pub support: u32,
    pub precision: f32,
}

const _: () = assert!(size_of::<PackedSuffixRule>() == 16);
unsafe impl Pod for PackedSuffixRule {}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct PackedCasingEntry {
    pub key_off_len: u32,
    pub lower: u32,
    pub title: u32,
    pub upper: u32,
    pub mixed: u32,
    pub apostrophe_stem: u32,
    pub best_mixed_off_len: u32,
}

const _: () = assert!(size_of::<PackedCasingEntry>() == 28);
unsafe impl Pod for PackedCasingEntry {}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct PackedSkeletonEntry {
    pub skel_off_len: u32,
    pub target_start_and_count: u32,
}

const _: () = assert!(size_of::<PackedSkeletonEntry>() == 8);
unsafe impl Pod for PackedSkeletonEntry {}

#[derive(Clone, Copy, Debug)]
pub struct RewriteEntryView<'a> {
    pub total_count: u32,
    pub keep_count: u32,
    targets: &'a [PackedTargetCount],
    str_pool: &'a str,
}

impl<'a> RewriteEntryView<'a> {
    #[inline]
    pub fn keep_prob(&self) -> f32 {
        (self.keep_count as f32 + 1.5) / (self.total_count as f32 + 3.0)
    }

    #[inline]
    pub fn len(&self) -> usize {
        self.targets.len()
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        self.targets.is_empty()
    }

    #[inline]
    pub fn target(&self, idx: usize) -> (&'a str, u32) {
        let t = self.targets[idx];
        (slice_off_len(self.str_pool, t.norm_off_len), t.count)
    }

    #[inline]
    pub fn prob_of(&self, norm_lower: &str) -> f32 {
        for t in self.targets {
            if slice_off_len(self.str_pool, t.norm_off_len) == norm_lower {
                return (t.count as f32) / (self.total_count as f32 + 3.0);
            }
        }
        0.0
    }
}

#[derive(Clone, Copy, Debug)]
pub struct MergeEntryView<'a> {
    pub cooccur_count: u32,
    targets: &'a [PackedTargetCount],
    str_pool: &'a str,
}

impl<'a> MergeEntryView<'a> {
    #[inline]
    pub fn len(&self) -> usize {
        self.targets.len()
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        self.targets.is_empty()
    }

    #[inline]
    pub fn total_merged_count(&self) -> u32 {
        let mut s = 0u32;
        for t in self.targets {
            s = s.saturating_add(t.count);
        }
        s
    }

    #[inline]
    pub fn target(&self, idx: usize) -> (&'a str, u32, f32) {
        let t = self.targets[idx];
        let denom = self.cooccur_count.max(self.total_merged_count()) as f32 + 3.0;
        (
            slice_off_len(self.str_pool, t.norm_off_len),
            t.count,
            (t.count as f32) / denom,
        )
    }
}

#[derive(Clone, Copy, Debug, Default)]
pub struct CasingCountsView<'a> {
    pub lower: u32,
    pub title: u32,
    pub upper: u32,
    pub mixed: u32,
    pub apostrophe_stem: u32,
    pub best_mixed_surface: Option<&'a str>,
}

impl<'a> CasingCountsView<'a> {
    #[inline]
    pub fn total(&self) -> u32 {
        self.lower + self.title + self.upper + self.mixed
    }
}

#[derive(Clone, Copy, Debug)]
pub struct SuffixRuleView<'a> {
    pub raw_suffix: &'a str,
    pub norm_suffix: &'a str,
    pub support: u32,
    pub precision: f32,
}

/// Zero-copy view over `SECT_NORM_KNOWLEDGE = 10` (`NormalizationKnowledge` excluding `CleanWordLm`,
/// which is shared from `SECT_CLEAN_LM = 7`).
#[derive(Clone, Copy, Debug)]
pub struct NormKnowledgeView<'a> {
    phono_codebook: &'a [u32; 256],
    p1_entries: &'a [PackedRewriteEntry],
    p2_entries: &'a [PackedMergeEntry],
    p3_entries: &'a [PackedMergeEntry],
    targets: &'a [PackedTargetCount],
    suf_buckets: &'a [PackedSuffixBucket],
    suf_rules: &'a [PackedSuffixRule],
    casing_entries: &'a [PackedCasingEntry],
    skel_entries: &'a [PackedSkeletonEntry],
    skel_targets: &'a [u32],
    phono_q4: &'a [u8; 65536],
    phono_q3: &'a [u8; 65536],
    str_pool: &'a str,
}

impl<'a> NormKnowledgeView<'a> {
    pub fn from_section_bytes(bytes: &'a [u8]) -> Result<Self, PackError> {
        let hdr = cast_ref::<NormKnowledgeHeader>(bytes)?;
        let p1_n = hdr.p1_count as usize;
        let p2_n = hdr.p2_count as usize;
        let p3_n = hdr.p3_count as usize;
        let tgt_n = hdr.target_count as usize;
        let sb_n = hdr.suf_bucket_count as usize;
        let sr_n = hdr.suf_rule_count as usize;
        let cas_n = hdr.casing_count as usize;
        let sk_n = hdr.skel_entry_count as usize;
        let skt_n = hdr.skel_target_count as usize;
        let pool_len = hdr.str_pool_len as usize;

        let mut cursor = size_of::<NormKnowledgeHeader>();
        let mut take = |len: usize| -> Result<&'a [u8], PackError> {
            let end = cursor.checked_add(len).ok_or(PackError::MalformedSection)?;
            if end > bytes.len() {
                return Err(PackError::MalformedSection);
            }
            let s = &bytes[cursor..end];
            cursor = end;
            Ok(s)
        };

        let cb_slice = cast_slice::<u32>(take(256 * 4)?, 256)?;
        let phono_codebook: &'a [u32; 256] =
            cb_slice.try_into().map_err(|_| PackError::MalformedSection)?;

        let p1_entries = cast_slice::<PackedRewriteEntry>(
            take(p1_n.checked_mul(16).ok_or(PackError::MalformedSection)?)?,
            p1_n,
        )?;
        let p2_entries = cast_slice::<PackedMergeEntry>(
            take(p2_n.checked_mul(12).ok_or(PackError::MalformedSection)?)?,
            p2_n,
        )?;
        let p3_entries = cast_slice::<PackedMergeEntry>(
            take(p3_n.checked_mul(12).ok_or(PackError::MalformedSection)?)?,
            p3_n,
        )?;
        let targets = cast_slice::<PackedTargetCount>(
            take(tgt_n.checked_mul(8).ok_or(PackError::MalformedSection)?)?,
            tgt_n,
        )?;
        let suf_buckets = cast_slice::<PackedSuffixBucket>(
            take(sb_n.checked_mul(8).ok_or(PackError::MalformedSection)?)?,
            sb_n,
        )?;
        let suf_rules = cast_slice::<PackedSuffixRule>(
            take(sr_n.checked_mul(16).ok_or(PackError::MalformedSection)?)?,
            sr_n,
        )?;
        let casing_entries = cast_slice::<PackedCasingEntry>(
            take(cas_n.checked_mul(28).ok_or(PackError::MalformedSection)?)?,
            cas_n,
        )?;
        let skel_entries = cast_slice::<PackedSkeletonEntry>(
            take(sk_n.checked_mul(8).ok_or(PackError::MalformedSection)?)?,
            sk_n,
        )?;
        let skel_targets = cast_slice::<u32>(
            take(skt_n.checked_mul(4).ok_or(PackError::MalformedSection)?)?,
            skt_n,
        )?;

        let phono_q4: &'a [u8; 65536] = take(65536)?
            .try_into()
            .map_err(|_| PackError::MalformedSection)?;
        let phono_q3: &'a [u8; 65536] = take(65536)?
            .try_into()
            .map_err(|_| PackError::MalformedSection)?;
        let str_pool =
            core::str::from_utf8(take(pool_len)?).map_err(|_| PackError::InvalidUtf8Pool)?;

        for e in p1_entries {
            check_off_len(str_pool, e.key_off_len)?;
            check_span(e.target_start_and_count, tgt_n, 24)?;
        }
        for e in p2_entries {
            check_off_len(str_pool, e.key_off_len)?;
            check_span(e.target_start_and_count, tgt_n, 24)?;
        }
        for e in p3_entries {
            check_off_len(str_pool, e.key_off_len)?;
            check_span(e.target_start_and_count, tgt_n, 24)?;
        }
        for t in targets {
            check_off_len(str_pool, t.norm_off_len)?;
        }
        for b in suf_buckets {
            check_span(b.rule_start_and_count, sr_n, 16)?;
        }
        for r in suf_rules {
            check_off_len(str_pool, r.raw_off_len)?;
            check_off_len(str_pool, r.norm_off_len)?;
        }
        for c in casing_entries {
            check_off_len(str_pool, c.key_off_len)?;
            if c.best_mixed_off_len != 0 {
                check_off_len(str_pool, c.best_mixed_off_len)?;
            }
        }
        for s in skel_entries {
            check_off_len(str_pool, s.skel_off_len)?;
            check_span(s.target_start_and_count, skt_n, 24)?;
        }
        for &ol in skel_targets {
            check_off_len(str_pool, ol)?;
        }

        Ok(Self {
            phono_codebook,
            p1_entries,
            p2_entries,
            p3_entries,
            targets,
            suf_buckets,
            suf_rules,
            casing_entries,
            skel_entries,
            skel_targets,
            phono_q4,
            phono_q3,
            str_pool,
        })
    }

    #[inline]
    pub fn lookup_p1(&self, key: &str) -> Option<RewriteEntryView<'a>> {
        let idx = self
            .p1_entries
            .binary_search_by(|e| slice_off_len(self.str_pool, e.key_off_len).cmp(key))
            .ok()?;
        let e = self.p1_entries[idx];
        let (s, c) = unpack_24_8(e.target_start_and_count);
        Some(RewriteEntryView {
            total_count: e.total_count,
            keep_count: e.keep_count,
            targets: &self.targets[s..s + c],
            str_pool: self.str_pool,
        })
    }

    #[inline]
    pub fn lookup_p2(&self, t1: &str, t2: &str) -> Option<MergeEntryView<'a>> {
        let idx = self
            .p2_entries
            .binary_search_by(|e| cmp_tab2(slice_off_len(self.str_pool, e.key_off_len), t1, t2))
            .ok()?;
        let e = self.p2_entries[idx];
        let (s, c) = unpack_24_8(e.target_start_and_count);
        Some(MergeEntryView {
            cooccur_count: e.cooccur_count,
            targets: &self.targets[s..s + c],
            str_pool: self.str_pool,
        })
    }

    #[inline]
    pub fn lookup_p3(&self, t1: &str, t2: &str, t3: &str) -> Option<MergeEntryView<'a>> {
        let idx = self
            .p3_entries
            .binary_search_by(|e| cmp_tab3(slice_off_len(self.str_pool, e.key_off_len), t1, t2, t3))
            .ok()?;
        let e = self.p3_entries[idx];
        let (s, c) = unpack_24_8(e.target_start_and_count);
        Some(MergeEntryView {
            cooccur_count: e.cooccur_count,
            targets: &self.targets[s..s + c],
            str_pool: self.str_pool,
        })
    }

    #[inline]
    pub fn suffix_rules_for_last_char(
        &self,
        last_ch: char,
    ) -> impl Iterator<Item = SuffixRuleView<'a>> + 'a {
        let code = last_ch as u32;
        let slice = match self.suf_buckets.binary_search_by_key(&code, |b| b.last_char) {
            Ok(idx) => {
                let (s, c) = unpack_16_16(self.suf_buckets[idx].rule_start_and_count);
                &self.suf_rules[s..s + c]
            }
            Err(_) => &[],
        };
        let pool = self.str_pool;
        slice.iter().map(move |r| SuffixRuleView {
            raw_suffix: slice_off_len(pool, r.raw_off_len),
            norm_suffix: slice_off_len(pool, r.norm_off_len),
            support: r.support,
            precision: r.precision,
        })
    }

    #[inline]
    pub fn lookup_casing_raw(&self, word_lower: &str) -> Option<CasingCountsView<'a>> {
        let idx = self
            .casing_entries
            .binary_search_by(|e| slice_off_len(self.str_pool, e.key_off_len).cmp(word_lower))
            .ok()?;
        let c = self.casing_entries[idx];
        let best_mixed = if c.best_mixed_off_len == 0 {
            None
        } else {
            Some(slice_off_len(self.str_pool, c.best_mixed_off_len))
        };
        Some(CasingCountsView {
            lower: c.lower,
            title: c.title,
            upper: c.upper,
            mixed: c.mixed,
            apostrophe_stem: c.apostrophe_stem,
            best_mixed_surface: best_mixed,
        })
    }

    #[inline]
    pub fn lookup_skeleton_targets(
        &self,
        skel_key: &str,
    ) -> impl Iterator<Item = &'a str> + 'a {
        let slice = match self
            .skel_entries
            .binary_search_by(|e| slice_off_len(self.str_pool, e.skel_off_len).cmp(skel_key))
        {
            Ok(idx) => {
                let (s, c) = unpack_24_8(self.skel_entries[idx].target_start_and_count);
                &self.skel_targets[s..s + c]
            }
            Err(_) => &[],
        };
        let pool = self.str_pool;
        slice.iter().map(move |&ol| slice_off_len(pool, ol))
    }

    #[inline]
    pub fn has_skeleton_index(&self) -> bool {
        !self.skel_entries.is_empty()
    }

    /// Exact port of `NormalizationKnowledge.CharPhonotacticModel.scoreWord(lowerWord)`.
    pub fn score_word_phonotactics(&self, lower_word: &str) -> f32 {
        const DEFAULT_LOG_PROB: f32 = -4.2;
        if lower_word.is_empty() {
            return DEFAULT_LOG_PROB;
        }
        let mut penalty = 0.0f32;
        let mut u16_buf = [0u16; 132];
        u16_buf[0] = b'^' as u16;
        let mut len = 1usize;
        let mut tmp = [0u16; 2];
        for ch in lower_word.chars() {
            if matches!(ch, 'q' | 'w' | 'x') {
                penalty -= 1.35;
            }
            let enc = ch.encode_utf16(&mut tmp);
            if len + enc.len() + 1 < u16_buf.len() {
                for &u in enc.iter() {
                    u16_buf[len] = u;
                    len += 1;
                }
            } else {
                // Fallback for absurdly long words (>128 UTF-16 units)
                return self.score_word_phonotactics_long(lower_word, penalty);
            }
        }
        u16_buf[len] = b'$' as u16;
        len += 1;

        if len < 4 {
            return -2.4 + penalty;
        }
        let mut sum = 0.0f32;
        let cnt = len - 3;
        for i in 0..cnt {
            let h4 = hash_u16(&u16_buf[i..i + 4]) & 0xFFFF;
            let h3 = hash_u16(&u16_buf[i..i + 3]) & 0xFFFF;
            let c4 = self.phono_codebook[self.phono_q4[h4] as usize];
            let c3 = c4.max(self.phono_codebook[self.phono_q3[h3] as usize]);
            sum += if c4 > 0 {
                ln_f64((c4 as f64 + 0.5) / (c3 as f64 + 29.0)) as f32
            } else {
                DEFAULT_LOG_PROB
            };
        }
        (sum / (cnt.max(1) as f32)) + penalty
    }

    fn score_word_phonotactics_long(&self, lower_word: &str, penalty: f32) -> f32 {
        const DEFAULT_LOG_PROB: f32 = -4.2;
        let mut win = [0u16; 4];
        win[0] = b'^' as u16;
        let mut filled = 1usize;
        let mut sum = 0.0f32;
        let mut cnt = 0usize;
        let mut tmp = [0u16; 2];

        let mut push_unit = |u: u16| {
            if filled < 4 {
                win[filled] = u;
                filled += 1;
                if filled == 4 {
                    let h4 = hash_u16(&win) & 0xFFFF;
                    let h3 = hash_u16(&win[..3]) & 0xFFFF;
                    let c4 = self.phono_codebook[self.phono_q4[h4] as usize];
                    let c3 = c4.max(self.phono_codebook[self.phono_q3[h3] as usize]);
                    sum += if c4 > 0 {
                        ln_f64((c4 as f64 + 0.5) / (c3 as f64 + 29.0)) as f32
                    } else {
                        DEFAULT_LOG_PROB
                    };
                    cnt += 1;
                }
            } else {
                win[0] = win[1];
                win[1] = win[2];
                win[2] = win[3];
                win[3] = u;
                let h4 = hash_u16(&win) & 0xFFFF;
                let h3 = hash_u16(&win[..3]) & 0xFFFF;
                let c4 = self.phono_codebook[self.phono_q4[h4] as usize];
                let c3 = c4.max(self.phono_codebook[self.phono_q3[h3] as usize]);
                sum += if c4 > 0 {
                    ln_f64((c4 as f64 + 0.5) / (c3 as f64 + 29.0)) as f32
                } else {
                    DEFAULT_LOG_PROB
                };
                cnt += 1;
            }
        };

        for ch in lower_word.chars() {
            for &u in ch.encode_utf16(&mut tmp).iter() {
                push_unit(u);
            }
        }
        push_unit(b'$' as u16);
        if cnt == 0 {
            -2.4 + penalty
        } else {
            (sum / (cnt as f32)) + penalty
        }
    }
}

/// Zero-copy view over `SECT_NORM_PERCEPTRON = 11` (`denseWeights: [f32; 20]` + `QuantizedWeightTableView`).
#[derive(Clone, Copy, Debug)]
pub struct NormPerceptronView<'a> {
    pub dense_weights: &'a [f32; 20],
    pub sparse_weights: QuantizedWeightTableView<'a>,
}

impl<'a> NormPerceptronView<'a> {
    pub fn from_section_bytes(bytes: &'a [u8]) -> Result<Self, PackError> {
        if bytes.len() < 80 {
            return Err(PackError::MalformedSection);
        }
        let dw_slice = cast_slice::<f32>(&bytes[..80], 20)?;
        let dense_weights: &'a [f32; 20] =
            dw_slice.try_into().map_err(|_| PackError::MalformedSection)?;
        let sparse_weights = QuantizedWeightTableView::from_section_bytes(&bytes[80..])?;
        Ok(Self {
            dense_weights,
            sparse_weights,
        })
    }
}

#[inline]
fn hash_u16(units: &[u16]) -> usize {
    let mut h: u32 = 0x811c_9dc5;
    for &u in units {
        h ^= u as u32;
        h = h.wrapping_mul(0x0100_0193);
    }
    ((h ^ (h >> 16)) & 0xFFFF) as usize
}

#[inline]
fn unpack_24_8(v: u32) -> (usize, usize) {
    ((v & 0x00FF_FFFF) as usize, (v >> 24) as usize)
}

#[inline]
fn unpack_16_16(v: u32) -> (usize, usize) {
    ((v & 0xFFFF) as usize, (v >> 16) as usize)
}

#[inline]
fn slice_off_len(pool: &str, off_len: u32) -> &str {
    let off = (off_len >> 8) as usize;
    let len = (off_len & 0xFF) as usize;
    &pool[off..off + len]
}

fn check_off_len(pool: &str, ol: u32) -> Result<(), PackError> {
    let off = (ol >> 8) as usize;
    let len = (ol & 0xFF) as usize;
    let end = off.checked_add(len).ok_or(PackError::InvalidUtf8Pool)?;
    if end > pool.len() || !pool.is_char_boundary(off) || !pool.is_char_boundary(end) {
        return Err(PackError::InvalidUtf8Pool);
    }
    Ok(())
}

fn check_span(packed: u32, max_len: usize, start_bits: u32) -> Result<(), PackError> {
    let mask = (1u32 << start_bits) - 1;
    let s = (packed & mask) as usize;
    let c = (packed >> start_bits) as usize;
    let end = s.checked_add(c).ok_or(PackError::MalformedSection)?;
    if end > max_len {
        return Err(PackError::MalformedSection);
    }
    Ok(())
}

fn cmp_tab2(entry_key: &str, t1: &str, t2: &str) -> core::cmp::Ordering {
    let eb = entry_key.as_bytes();
    let mut it = t1.bytes().chain(core::iter::once(b'\t')).chain(t2.bytes());
    let mut i = 0usize;
    loop {
        match (eb.get(i).copied(), it.next()) {
            (Some(a), Some(b)) => {
                if a != b {
                    return a.cmp(&b);
                }
                i += 1;
            }
            (None, None) => return core::cmp::Ordering::Equal,
            (None, Some(_)) => return core::cmp::Ordering::Less,
            (Some(_), None) => return core::cmp::Ordering::Greater,
        }
    }
}

fn cmp_tab3(entry_key: &str, t1: &str, t2: &str, t3: &str) -> core::cmp::Ordering {
    let eb = entry_key.as_bytes();
    let mut it = t1
        .bytes()
        .chain(core::iter::once(b'\t'))
        .chain(t2.bytes())
        .chain(core::iter::once(b'\t'))
        .chain(t3.bytes());
    let mut i = 0usize;
    loop {
        match (eb.get(i).copied(), it.next()) {
            (Some(a), Some(b)) => {
                if a != b {
                    return a.cmp(&b);
                }
                i += 1;
            }
            (None, None) => return core::cmp::Ordering::Equal,
            (None, Some(_)) => return core::cmp::Ordering::Less,
            (Some(_), None) => return core::cmp::Ordering::Greater,
        }
    }
}
