use core::mem::size_of;
use crate::{cast_ref, cast_slice, PackError, Pod};

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct LexiconSectionHeader {
    pub item_count: u32,
    pub utf8_pool_len: u32,
    /// Number of sparse `ExplicitLemmaEntry` records (0 in Step 0 mini pack).
    pub explicit_lemma_count: u32,
    /// Number of sparse `RefEntry` records (0 in Step 0 mini pack).
    pub ref_count: u32,
}

const _: () = assert!(size_of::<LexiconSectionHeader>() == 16);
unsafe impl Pod for LexiconSectionHeader {}

/// Lemma reconstruction mode stored in `PackedDictItem::index_and_flags` bits `2..5`.
#[repr(u8)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LemmaMode {
    /// `lemma == root` (direct zero-copy slice).
    ExactRoot = 0,
    /// `lemma == format!("{root}mak")`.
    VerbAppendMak = 1,
    /// `lemma == format!("{root}mek")`.
    VerbAppendMek = 2,
    /// `lemma == TurkishAlphabet::capitalize_tr(root)`.
    TrCapitalizeRoot = 3,
    /// `lemma == TurkishAlphabet::to_upper_tr(root)`.
    TrUpperRoot = 4,
    /// `lemma` stored as a separate slice in `explicit_lemmas`.
    ExplicitPool = 5,
}

impl LemmaMode {
    #[inline]
    pub const fn from_u8(v: u8) -> Self {
        match v {
            1 => Self::VerbAppendMak,
            2 => Self::VerbAppendMek,
            3 => Self::TrCapitalizeRoot,
            4 => Self::TrUpperRoot,
            5 => Self::ExplicitPool,
            _ => Self::ExactRoot,
        }
    }
}

/// Zero-copy dictionary item (`12 bytes`, `#[repr(C)]`).
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PackedDictItem {
    /// `(byte_offset << 8) | (utf8_byte_len & 0xFF)` of `item.root` in `utf8_pool`.
    pub lemma_offset_len: u32,
    /// Bitmask of `RootAttribute` ordinals (`0..30`).
    pub root_attrs: u32,
    /// `PrimaryPos` ordinal (`0..13`).
    pub primary_pos: u8,
    /// `SecondaryPos` ordinal (`0..31`).
    pub secondary_pos: u8,
    /// `0` = regular, `1..=255` = 1-based special root ID for `DictionaryItemIs*` checks.
    pub special_root_id: u8,
    /// Bits `0..2` = homonym `index` (`0..3`), bits `2..5` = `LemmaMode`, bit `7` = `has_reference`.
    pub index_and_flags: u8,
}

const _: () = assert!(size_of::<PackedDictItem>() == 12);
unsafe impl Pod for PackedDictItem {}

impl PackedDictItem {
    #[inline]
    pub fn has_root_attr(&self, attr_ordinal: u8) -> bool {
        (self.root_attrs & (1u32 << attr_ordinal)) != 0
    }

    #[inline]
    pub fn homonym_index(&self) -> u8 {
        self.index_and_flags & 0x03
    }

    #[inline]
    pub fn lemma_mode(&self) -> LemmaMode {
        LemmaMode::from_u8((self.index_and_flags >> 2) & 0x07)
    }

    #[inline]
    pub fn has_reference(&self) -> bool {
        (self.index_and_flags & 0x80) != 0
    }
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ExplicitLemmaEntry {
    pub item_id: u32,
    pub lemma_offset_len: u32,
}

const _: () = assert!(size_of::<ExplicitLemmaEntry>() == 8);
unsafe impl Pod for ExplicitLemmaEntry {}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct RefEntry {
    pub item_id: u32,
    pub ref_item_id: u32,
}

const _: () = assert!(size_of::<RefEntry>() == 8);
unsafe impl Pod for RefEntry {}

/// Zero-copy view over a `SECT_LEXICON` payload.
#[derive(Clone, Copy, Debug)]
pub struct LexiconView<'a> {
    items: &'a [PackedDictItem],
    explicit_lemmas: &'a [ExplicitLemmaEntry],
    refs: &'a [RefEntry],
    utf8_pool: &'a str,
}

impl<'a> LexiconView<'a> {
    pub fn from_section_bytes(bytes: &'a [u8]) -> Result<Self, PackError> {
        let hdr = cast_ref::<LexiconSectionHeader>(bytes)?;
        let item_count = hdr.item_count as usize;
        let pool_len = hdr.utf8_pool_len as usize;
        let exp_count = hdr.explicit_lemma_count as usize;
        let ref_count = hdr.ref_count as usize;

        let items_start = size_of::<LexiconSectionHeader>();
        let items_bytes = item_count
            .checked_mul(size_of::<PackedDictItem>())
            .ok_or(PackError::MalformedSection)?;
        let exp_start = items_start
            .checked_add(items_bytes)
            .ok_or(PackError::MalformedSection)?;
        let exp_bytes = exp_count
            .checked_mul(size_of::<ExplicitLemmaEntry>())
            .ok_or(PackError::MalformedSection)?;
        let refs_start = exp_start
            .checked_add(exp_bytes)
            .ok_or(PackError::MalformedSection)?;
        let refs_bytes = ref_count
            .checked_mul(size_of::<RefEntry>())
            .ok_or(PackError::MalformedSection)?;
        let pool_start = refs_start
            .checked_add(refs_bytes)
            .ok_or(PackError::MalformedSection)?;
        let pool_end = pool_start
            .checked_add(pool_len)
            .ok_or(PackError::MalformedSection)?;
        if pool_end > bytes.len() {
            return Err(PackError::MalformedSection);
        }

        let items = cast_slice::<PackedDictItem>(&bytes[items_start..exp_start], item_count)?;
        let explicit_lemmas =
            cast_slice::<ExplicitLemmaEntry>(&bytes[exp_start..refs_start], exp_count)?;
        let refs = cast_slice::<RefEntry>(&bytes[refs_start..pool_start], ref_count)?;
        let utf8_pool = core::str::from_utf8(&bytes[pool_start..pool_end])
            .map_err(|_| PackError::InvalidUtf8Pool)?;

        let check_slice = |off_len: u32| -> Result<(), PackError> {
            let off = (off_len >> 8) as usize;
            let len = (off_len & 0xFF) as usize;
            let end = off.checked_add(len).ok_or(PackError::MalformedSection)?;
            if end > utf8_pool.len()
                || !utf8_pool.is_char_boundary(off)
                || !utf8_pool.is_char_boundary(end)
            {
                return Err(PackError::InvalidUtf8Pool);
            }
            Ok(())
        };

        for item in items {
            check_slice(item.lemma_offset_len)?;
        }
        for exp in explicit_lemmas {
            if (exp.item_id as usize) >= item_count {
                return Err(PackError::MalformedSection);
            }
            check_slice(exp.lemma_offset_len)?;
        }
        for r in refs {
            if (r.item_id as usize) >= item_count || (r.ref_item_id as usize) >= item_count {
                return Err(PackError::MalformedSection);
            }
        }

        Ok(Self {
            items,
            explicit_lemmas,
            refs,
            utf8_pool,
        })
    }

    #[inline]
    pub fn len(&self) -> usize {
        self.items.len()
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        self.items.is_empty()
    }

    #[inline]
    pub fn items(&self) -> &'a [PackedDictItem] {
        self.items
    }

    #[inline]
    pub fn item(&self, idx: usize) -> &'a PackedDictItem {
        &self.items[idx]
    }

    #[inline]
    pub fn utf8_pool(&self) -> &'a str {
        self.utf8_pool
    }

    #[inline]
    pub fn slice_pool(&self, off_len: u32) -> &'a str {
        let off = (off_len >> 8) as usize;
        let len = (off_len & 0xFF) as usize;
        &self.utf8_pool[off..off + len]
    }

    /// Returns `item.root` directly as a zero-copy `&str` slice.
    #[inline]
    pub fn root(&self, idx: usize) -> &'a str {
        self.slice_pool(self.items[idx].lemma_offset_len)
    }

    ///Backward-compatible Step 0 helper: returns direct pool slice (`ExactRoot` or `ExplicitPool`).
    #[inline]
    pub fn lemma(&self, idx: usize) -> &'a str {
        let item = &self.items[idx];
        if item.lemma_mode() == LemmaMode::ExplicitPool {
            if let Ok(pos) = self
                .explicit_lemmas
                .binary_search_by_key(&(idx as u32), |e| e.item_id)
            {
                return self.slice_pool(self.explicit_lemmas[pos].lemma_offset_len);
            }
        }
        self.slice_pool(item.lemma_offset_len)
    }

    #[inline]
    pub fn explicit_lemma_slice(&self, idx: usize) -> Option<&'a str> {
        let pos = self
            .explicit_lemmas
            .binary_search_by_key(&(idx as u32), |e| e.item_id)
            .ok()?;
        Some(self.slice_pool(self.explicit_lemmas[pos].lemma_offset_len))
    }

    #[inline]
    pub fn reference_item_id(&self, idx: usize) -> Option<u32> {
        if !self.items[idx].has_reference() {
            return None;
        }
        let pos = self
            .refs
            .binary_search_by_key(&(idx as u32), |r| r.item_id)
            .ok()?;
        Some(self.refs[pos].ref_item_id)
    }
}
