use core::mem::size_of;
use crate::mphf::QuantizedWeightTableView;
use crate::{cast_ref, cast_slice, PackError, Pod};

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct DeascSectionHeader {
    pub foreign_count: u32,
    pub common_conf_count: u32,
    pub proper_conf_count: u32,
    pub conf_target_count: u32,
    pub str_pool_len: u32,
    pub lemma_qwt_bytes: u32,
    pub char_qwt_bytes: u32,
    pub sent_qwt_bytes: u32,
    pub reserved: [u32; 4],
}

const _: () = assert!(size_of::<DeascSectionHeader>() == 48);
unsafe impl Pod for DeascSectionHeader {}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct PackedConfusionEntry {
    pub key_off_len: u32,
    pub target_start: u32,
    pub total_count: u16,
    pub target_count: u8,
    pub _pad: u8,
}

const _: () = assert!(size_of::<PackedConfusionEntry>() == 12);
unsafe impl Pod for PackedConfusionEntry {}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct PackedConfusionTarget {
    pub surf_off_len: u32,
    pub log_prob: f32,
}

const _: () = assert!(size_of::<PackedConfusionTarget>() == 8);
unsafe impl Pod for PackedConfusionTarget {}

#[derive(Clone, Copy, Debug)]
pub struct ConfusionSetView<'a> {
    targets: &'a [PackedConfusionTarget],
    str_pool: &'a str,
    pub total_count: u16,
}

impl<'a> ConfusionSetView<'a> {
    #[inline]
    pub fn len(&self) -> usize {
        self.targets.len()
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        self.targets.is_empty()
    }

    #[inline]
    pub fn surface(&self, idx: usize) -> &'a str {
        slice_off_len(self.str_pool, self.targets[idx].surf_off_len)
    }

    #[inline]
    pub fn log_prob(&self, idx: usize) -> f32 {
        self.targets[idx].log_prob
    }

    #[inline]
    pub fn log_prob_of(&self, lower_surface: &str, unseen_default: f32) -> f32 {
        for t in self.targets {
            if slice_off_len(self.str_pool, t.surf_off_len) == lower_surface {
                return t.log_prob;
            }
        }
        unseen_default
    }
}

/// Zero-copy view over `SECT_DEASC = 9` (`DeasciifierModel`: `LexiconPriors` + `CharacterHarmonyModel` + `sentenceWeights`).
#[derive(Clone, Copy, Debug)]
pub struct DeascModelView<'a> {
    pub lemma_log_counts: QuantizedWeightTableView<'a>,
    pub char_weights: QuantizedWeightTableView<'a>,
    pub sent_weights: QuantizedWeightTableView<'a>,
    foreign_off_lens: &'a [u32],
    common_confs: &'a [PackedConfusionEntry],
    proper_confs: &'a [PackedConfusionEntry],
    conf_targets: &'a [PackedConfusionTarget],
    str_pool: &'a str,
}

impl<'a> DeascModelView<'a> {
    pub fn from_section_bytes(bytes: &'a [u8]) -> Result<Self, PackError> {
        let hdr = cast_ref::<DeascSectionHeader>(bytes)?;
        let foreign_count = hdr.foreign_count as usize;
        let common_count = hdr.common_conf_count as usize;
        let proper_count = hdr.proper_conf_count as usize;
        let target_count = hdr.conf_target_count as usize;
        let pool_len = hdr.str_pool_len as usize;
        let lemma_len = hdr.lemma_qwt_bytes as usize;
        let char_len = hdr.char_qwt_bytes as usize;
        let sent_len = hdr.sent_qwt_bytes as usize;

        if !lemma_len.is_multiple_of(8)
            || !char_len.is_multiple_of(8)
            || !sent_len.is_multiple_of(8)
        {
            return Err(PackError::MalformedSection);
        }

        let mut cursor = size_of::<DeascSectionHeader>();
        let mut take = |len: usize| -> Result<&'a [u8], PackError> {
            let end = cursor.checked_add(len).ok_or(PackError::MalformedSection)?;
            if end > bytes.len() {
                return Err(PackError::MalformedSection);
            }
            let s = &bytes[cursor..end];
            cursor = end;
            Ok(s)
        };

        let lemma_log_counts = QuantizedWeightTableView::from_section_bytes(take(lemma_len)?)?;
        let char_weights = QuantizedWeightTableView::from_section_bytes(take(char_len)?)?;
        let sent_weights = QuantizedWeightTableView::from_section_bytes(take(sent_len)?)?;

        let foreign_bytes = foreign_count
            .checked_mul(4)
            .ok_or(PackError::MalformedSection)?;
        let foreign_off_lens = cast_slice::<u32>(take(foreign_bytes)?, foreign_count)?;

        let common_bytes = common_count
            .checked_mul(size_of::<PackedConfusionEntry>())
            .ok_or(PackError::MalformedSection)?;
        let common_confs = cast_slice::<PackedConfusionEntry>(take(common_bytes)?, common_count)?;

        let proper_bytes = proper_count
            .checked_mul(size_of::<PackedConfusionEntry>())
            .ok_or(PackError::MalformedSection)?;
        let proper_confs = cast_slice::<PackedConfusionEntry>(take(proper_bytes)?, proper_count)?;

        let target_bytes = target_count
            .checked_mul(size_of::<PackedConfusionTarget>())
            .ok_or(PackError::MalformedSection)?;
        let conf_targets = cast_slice::<PackedConfusionTarget>(take(target_bytes)?, target_count)?;

        let pool_raw = take(pool_len)?;
        let str_pool = core::str::from_utf8(pool_raw).map_err(|_| PackError::InvalidUtf8Pool)?;

        check_off_lens(str_pool, foreign_off_lens)?;
        check_conf_entries(str_pool, common_confs, target_count)?;
        check_conf_entries(str_pool, proper_confs, target_count)?;
        for t in conf_targets {
            check_single_off_len(str_pool, t.surf_off_len)?;
        }

        Ok(Self {
            lemma_log_counts,
            char_weights,
            sent_weights,
            foreign_off_lens,
            common_confs,
            proper_confs,
            conf_targets,
            str_pool,
        })
    }

    #[inline]
    pub fn contains_foreign_keep(&self, ascii_lower: &str) -> bool {
        self.foreign_off_lens
            .binary_search_by(|&ol| slice_off_len(self.str_pool, ol).cmp(ascii_lower))
            .is_ok()
    }

    #[inline]
    pub fn lookup_common_confusion(&self, ascii_lower: &str) -> Option<ConfusionSetView<'a>> {
        self.lookup_in(self.common_confs, ascii_lower)
    }

    #[inline]
    pub fn lookup_proper_confusion(&self, ascii_lower: &str) -> Option<ConfusionSetView<'a>> {
        self.lookup_in(self.proper_confs, ascii_lower)
    }

    #[inline]
    fn lookup_in(
        &self,
        table: &'a [PackedConfusionEntry],
        ascii_lower: &str,
    ) -> Option<ConfusionSetView<'a>> {
        let idx = table
            .binary_search_by(|e| slice_off_len(self.str_pool, e.key_off_len).cmp(ascii_lower))
            .ok()?;
        let e = table[idx];
        let s = e.target_start as usize;
        let c = e.target_count as usize;
        Some(ConfusionSetView {
            targets: &self.conf_targets[s..s + c],
            str_pool: self.str_pool,
            total_count: e.total_count,
        })
    }
}

#[inline]
fn slice_off_len(pool: &str, off_len: u32) -> &str {
    let off = (off_len >> 8) as usize;
    let len = (off_len & 0xFF) as usize;
    &pool[off..off + len]
}

fn check_single_off_len(pool: &str, ol: u32) -> Result<(), PackError> {
    let off = (ol >> 8) as usize;
    let len = (ol & 0xFF) as usize;
    let end = off.checked_add(len).ok_or(PackError::InvalidUtf8Pool)?;
    if end > pool.len() || !pool.is_char_boundary(off) || !pool.is_char_boundary(end) {
        return Err(PackError::InvalidUtf8Pool);
    }
    Ok(())
}

fn check_off_lens(pool: &str, list: &[u32]) -> Result<(), PackError> {
    for &ol in list {
        check_single_off_len(pool, ol)?;
    }
    Ok(())
}

fn check_conf_entries(
    pool: &str,
    entries: &[PackedConfusionEntry],
    target_count: usize,
) -> Result<(), PackError> {
    for e in entries {
        check_single_off_len(pool, e.key_off_len)?;
        let end = (e.target_start as usize)
            .checked_add(e.target_count as usize)
            .ok_or(PackError::MalformedSection)?;
        if end > target_count {
            return Err(PackError::MalformedSection);
        }
    }
    Ok(())
}
