use core::mem::size_of;
use crate::{cast_ref, cast_slice, PackError, Pod};

/// RPN condition bytecode opcodes evaluated during Byte-FST search and word generation.
pub mod cond_op {
    pub const TRUE: u8 = 0;
    pub const NOT: u8 = 1;
    pub const AND: u8 = 2;
    pub const OR: u8 = 3;
    pub const HAS_ROOT_ATTR: u8 = 4;
    pub const HAS_ANY_ROOT_ATTR: u8 = 5;
    pub const SEC_POS_IS: u8 = 6;
    pub const ROOT_PRIMARY_POS_IS: u8 = 7;
    pub const DICT_ITEM_IN_SET: u8 = 8;
    pub const ROOT_SURF_IN_MASK: u8 = 9;
    pub const HAS_TAIL: u8 = 10;
    pub const HAS_ANY_SUFFIX_SURFACE: u8 = 11;
    pub const HAS_DERIVATION: u8 = 12;
    pub const NO_SURFACE_AFTER_DERIVATION: u8 = 13;
    pub const PREV_MORPHEME_IN_SET: u8 = 14;
    pub const PREV_STATE_IN_SET: u8 = 15;
    pub const LAST_DERIV_IN_SET: u8 = 16;
    pub const CUR_GROUP_CONTAINS_ANY: u8 = 17;
    pub const PREV_GROUP_CONTAINS_STATE: u8 = 18;
    pub const PREV_GROUP_CONTAINS_MORPH: u8 = 19;
    pub const CONTAINS_MORPHEME: u8 = 20;
    pub const HAS_TAIL_SEQ: u8 = 21;
    pub const CONTAINS_MORPH_SEQ: u8 = 22;
}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct MorphFstHeader {
    pub mat_state_count: u32,
    pub arc_count: u32,
    pub morpheme_count: u32,
    pub abstract_state_count: u32,
    pub surf_count: u32,
    pub cond_prog_count: u32,
    pub cond_inst_count: u32,
    pub set_u64_count: u32,
    pub diff_item_count: u32,
    pub diff_target_count: u32,
    pub seq_bytes_len: u32,
    pub str_pool_len: u32,
    pub nom_morpheme_id: u8,
    pub pnon_morpheme_id: u8,
    pub p3sg_morpheme_id: u8,
    pub reserved_u8: u8,
    pub reserved_u32: u32,
}

const _: () = assert!(size_of::<MorphFstHeader>() == 56);
unsafe impl Pod for MorphFstHeader {}

/// Compact 4-byte (`32-bit`) materialized FST arc:
/// - bits `0..13` (`13 bits`): `target_mat_state` (`0..8,191`)
/// - bits `13..22` (`9 bits`): `surf_id` (`0` = empty surface `""`, `1..511` = non-empty suffix surface)
/// - bits `22..30` (`8 bits`): `cond_prog_idx` (`0` = unconditional `true`, `1..255` = RPN program)
/// - bit  `30` (`1 bit`): `is_informal_arc`
/// - bit  `31` (`1 bit`): `has_surface_form` (`SuffixTransition.hasSurfaceForm()`)
#[repr(transparent)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct MatFstArc(pub u32);

unsafe impl Pod for MatFstArc {}

impl MatFstArc {
    #[inline]
    pub const fn target_mat_state(self) -> u16 {
        (self.0 & 0x1FFF) as u16
    }

    #[inline]
    pub const fn surf_id(self) -> u16 {
        ((self.0 >> 13) & 0x01FF) as u16
    }

    #[inline]
    pub const fn is_epsilon(self) -> bool {
        self.surf_id() == 0
    }

    #[inline]
    pub const fn cond_prog_idx(self) -> u8 {
        ((self.0 >> 22) & 0xFF) as u8
    }

    #[inline]
    pub const fn is_informal(self) -> bool {
        ((self.0 >> 30) & 1) != 0
    }

    #[inline]
    pub const fn has_surface_form(self) -> bool {
        (self.0 >> 31) != 0
    }
}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct PackedMorpheme {
    /// `(byte_offset << 8) | len` into `SECT_MORPH_FST`'s `str_pool`.
    pub id_off_len: u32,
    /// `PrimaryPos` ordinal, or `0xFF` if `morpheme.pos == null`.
    pub pos: u8,
    /// `bit 0` = `derivational`, `bit 1` = `informal`.
    pub flags: u8,
    /// Formal `morpheme_id` (`0` if `mappedMorpheme == null`).
    pub mapped_morpheme_id: u8,
    pub reserved: u8,
}

const _: () = assert!(size_of::<PackedMorpheme>() == 8);
unsafe impl Pod for PackedMorpheme {}

impl PackedMorpheme {
    #[inline]
    pub const fn is_derivational(self) -> bool {
        (self.flags & 1) != 0
    }

    #[inline]
    pub const fn is_informal(self) -> bool {
        (self.flags & 2) != 0
    }

    #[inline]
    pub const fn has_pos(self) -> bool {
        self.pos != 0xFF
    }
}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct PackedAbsState {
    pub morpheme_id: u8,
    /// `bit 0` = `terminal`, `bit 1` = `derivative`, `bit 2` = `pos_root`.
    pub flags: u8,
    pub reserved: u16,
}

const _: () = assert!(size_of::<PackedAbsState>() == 4);
unsafe impl Pod for PackedAbsState {}

impl PackedAbsState {
    #[inline]
    pub const fn is_terminal(self) -> bool {
        (self.flags & 1) != 0
    }

    #[inline]
    pub const fn is_derivative(self) -> bool {
        (self.flags & 2) != 0
    }

    #[inline]
    pub const fn is_pos_root(self) -> bool {
        (self.flags & 4) != 0
    }
}

/// Sparse reverse stem table entry for `WordGenerator` (`differentStemItems`).
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct PackedDiffItem {
    pub item_id: u32,
    /// `(count << 24) | start_idx` into `diff_targets`.
    pub start_and_count: u32,
}

const _: () = assert!(size_of::<PackedDiffItem>() == 8);
unsafe impl Pod for PackedDiffItem {}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct PackedDiffTarget {
    pub packed_stem_target: u32,
    /// `(offset << 8) | len` into `SECT_LEXICON`'s `utf8_pool`.
    pub surf_off_len: u32,
}

const _: () = assert!(size_of::<PackedDiffTarget>() == 8);
unsafe impl Pod for PackedDiffTarget {}

/// Zero-copy view over `SECT_MORPH_FST`.
#[derive(Clone, Copy, Debug)]
pub struct MorphFstView<'a> {
    pub header: &'a MorphFstHeader,
    pub set_words: &'a [u64],
    pub state_arc_offsets: &'a [u32],
    pub arcs: &'a [MatFstArc],
    pub cond_prog_offsets: &'a [u32],
    pub cond_insts: &'a [u32],
    pub surf_off_lens: &'a [u32],
    pub morphemes: &'a [PackedMorpheme],
    pub abstract_states: &'a [PackedAbsState],
    pub diff_items: &'a [PackedDiffItem],
    pub diff_targets: &'a [PackedDiffTarget],
    pub seq_bytes: &'a [u8],
    pub str_pool: &'a str,
}

impl<'a> MorphFstView<'a> {
    pub fn from_section_bytes(bytes: &'a [u8]) -> Result<Self, PackError> {
        let hdr = cast_ref::<MorphFstHeader>(bytes)?;
        let mut cursor = size_of::<MorphFstHeader>();

        let mut take = |elem_size: usize, count: usize| -> Result<&'a [u8], PackError> {
            let len = count
                .checked_mul(elem_size)
                .ok_or(PackError::MalformedSection)?;
            let end = cursor.checked_add(len).ok_or(PackError::MalformedSection)?;
            if end > bytes.len() {
                return Err(PackError::MalformedSection);
            }
            let slice = &bytes[cursor..end];
            cursor = end;
            Ok(slice)
        };

        let ms_count = hdr.mat_state_count as usize;
        let arc_count = hdr.arc_count as usize;
        let morph_count = hdr.morpheme_count as usize;
        let abs_count = hdr.abstract_state_count as usize;
        let surf_count = hdr.surf_count as usize;
        let prog_count = hdr.cond_prog_count as usize;
        let inst_count = hdr.cond_inst_count as usize;
        let set_count = hdr.set_u64_count as usize;
        let diff_item_count = hdr.diff_item_count as usize;
        let diff_target_count = hdr.diff_target_count as usize;
        let seq_len = hdr.seq_bytes_len as usize;
        let pool_len = hdr.str_pool_len as usize;

        let set_words = cast_slice::<u64>(take(8, set_count)?, set_count)?;
        let state_arc_offsets = cast_slice::<u32>(take(4, ms_count + 1)?, ms_count + 1)?;
        let arcs = cast_slice::<MatFstArc>(take(4, arc_count)?, arc_count)?;
        let cond_prog_offsets = cast_slice::<u32>(take(4, prog_count + 1)?, prog_count + 1)?;
        let cond_insts = cast_slice::<u32>(take(4, inst_count)?, inst_count)?;
        let surf_off_lens = cast_slice::<u32>(take(4, surf_count)?, surf_count)?;
        let morphemes = cast_slice::<PackedMorpheme>(take(8, morph_count)?, morph_count)?;
        let abstract_states = cast_slice::<PackedAbsState>(take(4, abs_count)?, abs_count)?;
        let diff_items = cast_slice::<PackedDiffItem>(take(8, diff_item_count)?, diff_item_count)?;
        let diff_targets =
            cast_slice::<PackedDiffTarget>(take(8, diff_target_count)?, diff_target_count)?;
        let seq_bytes = take(1, seq_len)?;
        let str_pool_raw = take(1, pool_len)?;
        let str_pool =
            core::str::from_utf8(str_pool_raw).map_err(|_| PackError::InvalidUtf8Pool)?;

        Ok(Self {
            header: hdr,
            set_words,
            state_arc_offsets,
            arcs,
            cond_prog_offsets,
            cond_insts,
            surf_off_lens,
            morphemes,
            abstract_states,
            diff_items,
            diff_targets,
            seq_bytes,
            str_pool,
        })
    }

    #[inline]
    pub fn outgoing_arcs(&self, mat_state: u16) -> &'a [MatFstArc] {
        let s = self.state_arc_offsets[mat_state as usize] as usize;
        let e = self.state_arc_offsets[(mat_state as usize) + 1] as usize;
        &self.arcs[s..e]
    }

    #[inline]
    pub fn surface_str(&self, surf_id: u16) -> &'a str {
        if surf_id == 0 {
            return "";
        }
        let ol = self.surf_off_lens[surf_id as usize];
        let off = (ol >> 8) as usize;
        let len = (ol & 0xFF) as usize;
        &self.str_pool[off..off + len]
    }

    #[inline]
    pub fn morpheme_id_str(&self, morph_id: u8) -> &'a str {
        let ol = self.morphemes[morph_id as usize].id_off_len;
        let off = (ol >> 8) as usize;
        let len = (ol & 0xFF) as usize;
        &self.str_pool[off..off + len]
    }

    #[inline]
    pub fn cond_program(&self, prog_idx: u8) -> &'a [u32] {
        if prog_idx == 0 {
            return &[];
        }
        let s = self.cond_prog_offsets[prog_idx as usize] as usize;
        let e = self.cond_prog_offsets[(prog_idx as usize) + 1] as usize;
        &self.cond_insts[s..e]
    }

    #[inline]
    pub fn diff_targets_for_item(&self, item_id: u32) -> Option<&'a [PackedDiffTarget]> {
        let pos = self
            .diff_items
            .binary_search_by_key(&item_id, |d| d.item_id)
            .ok()?;
        let sc = self.diff_items[pos].start_and_count;
        let start = (sc & 0x00FF_FFFF) as usize;
        let count = (sc >> 24) as usize;
        Some(&self.diff_targets[start..start + count])
    }
}
