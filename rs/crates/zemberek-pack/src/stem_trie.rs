use core::mem::size_of;
use crate::{cast_ref, cast_slice, PackError, Pod};

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct StemTrieHeader {
    pub node_count: u32,
    pub bitvec_u64_count: u32,
    pub terminal_count: u32,
    pub overflow_target_count: u32,
    pub max_stem_char_len: u32,
    pub root_child_count: u32,
}

const _: () = assert!(size_of::<StemTrieHeader>() == 24);
unsafe impl Pod for StemTrieHeader {}

/// Compact 6-byte (`48-bit`) BFS-ordered Radix Trie node (`#[repr(C)]`, 2-byte aligned):
/// - bits `0..19` (`19 bits`): `label_offset` into `SECT_LEXICON` `utf8_pool` (`0..524,287`)
/// - bits `19..24` (`5 bits`): `label_len` in UTF-8 bytes (`0..31`)
/// - bit  `24` (`1 bit`): `has_targets`
/// - bits `25..42` (`17 bits`): `first_child_idx` (`0..131,071`)
/// - bits `42..48` (`6 bits`): `child_count` (`0..63`)
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct BfsRadixNode {
    pub u0: u16,
    pub u1: u16,
    pub u2: u16,
}

const _: () = assert!(size_of::<BfsRadixNode>() == 6);
unsafe impl Pod for BfsRadixNode {}

impl BfsRadixNode {
    #[inline]
    const fn bits48(self) -> u64 {
        (self.u0 as u64) | ((self.u1 as u64) << 16) | ((self.u2 as u64) << 32)
    }

    #[inline]
    pub const fn label_offset(self) -> usize {
        (self.bits48() & 0x0007_FFFF) as usize
    }

    #[inline]
    pub const fn label_len(self) -> usize {
        ((self.bits48() >> 19) & 0x1F) as usize
    }

    #[inline]
    pub const fn has_targets(self) -> bool {
        ((self.bits48() >> 24) & 1) != 0
    }

    #[inline]
    pub const fn first_child_idx(self) -> usize {
        ((self.bits48() >> 25) & 0x0001_FFFF) as usize
    }

    #[inline]
    pub const fn child_count(self) -> usize {
        ((self.bits48() >> 42) & 0x3F) as usize
    }

    #[inline]
    pub fn first_byte(self, utf8_pool: &str) -> u8 {
        utf8_pool.as_bytes()[self.label_offset()]
    }
}

/// Bit-packed 4-byte stem transition target (`#[repr(transparent)]`):
/// - bits `0..17` (`17 bits`): `item_id` (`0..68,443`)
/// - bits `17..29` (`12 bits`): `mat_entry_state` (`0..4,095`)
/// - bits `29..32` (`3 bits`): `root_surface_id` (`0 = other, 1 = "de", 2 = "di", 3 = "ye", 4 = "yi"`;
///   `7` in `terminal_targets` encodes `(count << 24) | overflow_offset` for multi-target stems)
#[repr(transparent)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PackedStemTarget(pub u32);

unsafe impl Pod for PackedStemTarget {}

impl PackedStemTarget {
    pub const MULTI_SENTINEL: u32 = 7;

    #[inline]
    pub const fn new(item_id: u32, mat_entry_state: u16, root_surface_id: u8) -> Self {
        Self(
            (item_id & 0x0001_FFFF)
                | (((mat_entry_state as u32) & 0x0FFF) << 17)
                | (((root_surface_id as u32) & 0x07) << 29),
        )
    }

    #[inline]
    pub const fn item_id(self) -> u32 {
        self.0 & 0x0001_FFFF
    }

    #[inline]
    pub const fn mat_entry_state(self) -> u16 {
        ((self.0 >> 17) & 0x0FFF) as u16
    }

    #[inline]
    pub const fn root_surface_id(self) -> u8 {
        ((self.0 >> 29) & 0x07) as u8
    }

    #[inline]
    pub const fn is_multi_pointer(self) -> bool {
        (self.0 >> 29) == Self::MULTI_SENTINEL
    }
}

/// Zero-copy view over `SECT_STEM_TRIE`.
#[derive(Clone, Copy, Debug)]
pub struct StemTrieView<'a> {
    nodes: &'a [BfsRadixNode],
    terminal_bits: &'a [u64],
    terminal_rank_base: &'a [u32],
    terminal_targets: &'a [PackedStemTarget],
    overflow_targets: &'a [PackedStemTarget],
    max_stem_char_len: usize,
    root_child_count: usize,
}

impl<'a> StemTrieView<'a> {
    pub fn from_section_bytes(bytes: &'a [u8], utf8_pool: &str) -> Result<Self, PackError> {
        let hdr = cast_ref::<StemTrieHeader>(bytes)?;
        let node_count = hdr.node_count as usize;
        let bv_len = hdr.bitvec_u64_count as usize;
        let term_count = hdr.terminal_count as usize;
        let ovf_count = hdr.overflow_target_count as usize;

        let nodes_start = size_of::<StemTrieHeader>();
        let nodes_bytes = node_count
            .checked_mul(size_of::<BfsRadixNode>())
            .ok_or(PackError::MalformedSection)?;
        let nodes_padded = (nodes_bytes + 7) & !7;
        let bv_start = nodes_start
            .checked_add(nodes_padded)
            .ok_or(PackError::MalformedSection)?;
        let bv_bytes = bv_len
            .checked_mul(size_of::<u64>())
            .ok_or(PackError::MalformedSection)?;
        let rank_start = bv_start
            .checked_add(bv_bytes)
            .ok_or(PackError::MalformedSection)?;
        let rank_bytes = bv_len
            .checked_mul(size_of::<u32>())
            .ok_or(PackError::MalformedSection)?;
        let term_start = rank_start
            .checked_add(rank_bytes)
            .ok_or(PackError::MalformedSection)?;
        let term_bytes = term_count
            .checked_mul(size_of::<PackedStemTarget>())
            .ok_or(PackError::MalformedSection)?;
        let ovf_start = term_start
            .checked_add(term_bytes)
            .ok_or(PackError::MalformedSection)?;
        let ovf_bytes = ovf_count
            .checked_mul(size_of::<PackedStemTarget>())
            .ok_or(PackError::MalformedSection)?;
        let end = ovf_start
            .checked_add(ovf_bytes)
            .ok_or(PackError::MalformedSection)?;
        if end > bytes.len() || node_count == 0 {
            return Err(PackError::MalformedSection);
        }

        let nodes =
            cast_slice::<BfsRadixNode>(&bytes[nodes_start..nodes_start + nodes_bytes], node_count)?;
        let terminal_bits = cast_slice::<u64>(&bytes[bv_start..rank_start], bv_len)?;
        let terminal_rank_base = cast_slice::<u32>(&bytes[rank_start..term_start], bv_len)?;
        let terminal_targets =
            cast_slice::<PackedStemTarget>(&bytes[term_start..ovf_start], term_count)?;
        let overflow_targets = cast_slice::<PackedStemTarget>(&bytes[ovf_start..end], ovf_count)?;

        let root_cc = hdr.root_child_count as usize;
        for (idx, n) in nodes.iter().enumerate() {
            if idx > 0 {
                let lo = n.label_offset();
                let le = lo
                    .checked_add(n.label_len())
                    .ok_or(PackError::MalformedSection)?;
                if le > utf8_pool.len()
                    || !utf8_pool.is_char_boundary(lo)
                    || !utf8_pool.is_char_boundary(le)
                {
                    return Err(PackError::InvalidUtf8Pool);
                }
            }
            let fc = n.first_child_idx();
            let cc = if idx == 0 { root_cc } else { n.child_count() };
            if fc.checked_add(cc).ok_or(PackError::MalformedSection)? > node_count {
                return Err(PackError::MalformedSection);
            }
        }

        Ok(Self {
            nodes,
            terminal_bits,
            terminal_rank_base,
            terminal_targets,
            overflow_targets,
            max_stem_char_len: hdr.max_stem_char_len as usize,
            root_child_count: root_cc,
        })
    }

    #[inline]
    pub fn node_count(&self) -> usize {
        self.nodes.len()
    }

    #[inline]
    pub fn max_stem_char_len(&self) -> usize {
        self.max_stem_char_len
    }

    #[inline]
    pub fn node(&self, idx: usize) -> BfsRadixNode {
        self.nodes[idx]
    }

    #[inline]
    pub fn children_of(&self, node_idx: usize) -> &'a [BfsRadixNode] {
        let node = self.nodes[node_idx];
        let start = node.first_child_idx();
        let count = if node_idx == 0 {
            self.root_child_count
        } else {
            node.child_count()
        };
        &self.nodes[start..start + count]
    }

    #[inline]
    pub fn edge_label<'p>(&self, node: BfsRadixNode, utf8_pool: &'p str) -> &'p str {
        let lo = node.label_offset();
        let le = lo + node.label_len();
        &utf8_pool[lo..le]
    }

    #[inline]
    pub fn node_targets(&self, node_idx: usize) -> &'a [PackedStemTarget] {
        let n = self.nodes[node_idx];
        if !n.has_targets() {
            return &[];
        }
        let word_idx = node_idx >> 6;
        let bit_idx = node_idx & 63;
        let mask = (1u64 << bit_idx) - 1;
        let ord = (self.terminal_rank_base[word_idx] as usize)
            + ((self.terminal_bits[word_idx] & mask).count_ones() as usize);
        let entry = &self.terminal_targets[ord];
        if entry.is_multi_pointer() {
            let off = (entry.0 & 0x00FF_FFFF) as usize;
            let cnt = ((entry.0 >> 24) & 0x1F) as usize;
            &self.overflow_targets[off..off + cnt]
        } else {
            core::slice::from_ref(entry)
        }
    }
}

/// Canonical root state slots in `EntryBridgeHeader::root_state_ids`.
pub mod root_slot {
    pub const NOUN_S: usize = 0;
    pub const NOUN_PROPER_S: usize = 1;
    pub const NOUN_ABBRV_S: usize = 2;
    pub const NOUN_NO_SUFFIX_S: usize = 3;
    pub const NOUN_COMPOUND_ROOT_S: usize = 4;
    pub const NOUN_SU_ROOT_S: usize = 5;
    pub const NOUN_LAST_VOWEL_DROP_ROOT_S: usize = 6;
    pub const ADJECTIVE_ROOT_ST: usize = 7;
    pub const ADJ_LAST_VOWEL_DROP_ROOT_S: usize = 8;
    pub const VERB_ROOT_S: usize = 9;
    pub const VERB_ROOT_VOWEL_DROP_S: usize = 10;
    pub const VERB_LAST_VOWEL_DROP_MOD_ROOT_S: usize = 11;
    pub const VERB_LAST_VOWEL_DROP_UNMOD_ROOT_S: usize = 12;
    pub const V_IMPLICIT_RECIP_ROOT_S: usize = 13;
    pub const V_IMPLICIT_REFLEX_ROOT_S: usize = 14;
    pub const V_DE_YE_ROOT_S: usize = 15;
    pub const IMEK_ROOT_S: usize = 16;
    pub const N_VERB_DEGIL_S: usize = 17;
    pub const PRON_PERS_S: usize = 18;
    pub const PRON_PERS_MOD_S: usize = 19;
    pub const PRON_DEMONS_S: usize = 20;
    pub const PRON_QUANT_S: usize = 21;
    pub const PRON_QUANT_MODIFIED_S: usize = 22;
    pub const PRON_QUES_S: usize = 23;
    pub const PRON_REFLEX_S: usize = 24;
    pub const ADV_ROOT_ST: usize = 25;
    pub const ADV_FOR_VERB_DERIV_ST: usize = 26;
    pub const CONJ_ROOT_ST: usize = 27;
    pub const QUESTION_ROOT_S: usize = 28;
    pub const INTERJ_ROOT_ST: usize = 29;
    pub const PUNC_ROOT_ST: usize = 30;
    pub const DET_ROOT_ST: usize = 31;
    pub const POSTP_ROOT_ST: usize = 32;
    pub const NUMERAL_ROOT_ST: usize = 33;
    pub const DUP_ROOT_ST: usize = 34;
    pub const COUNT: usize = 35;
}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct EntryBridgeHeader {
    pub mat_state_count: u32,
    pub sorted_entry_count: u32,
    pub root_state_ids: [u16; 36],
}

const _: () = assert!(size_of::<EntryBridgeHeader>() == 80);
unsafe impl Pod for EntryBridgeHeader {}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct MatStateMeta {
    pub abstract_state_id: u16,
    pub flags: u16,
    pub phono_bits: u32,
}

const _: () = assert!(size_of::<MatStateMeta>() == 8);
unsafe impl Pod for MatStateMeta {}

/// Zero-copy view over `SECT_ENTRY_BRIDGE`.
#[derive(Clone, Copy, Debug)]
pub struct EntryBridgeView<'a> {
    header: &'a EntryBridgeHeader,
    mat_states: &'a [MatStateMeta],
    sorted_entry_indices: &'a [u16],
}

impl<'a> EntryBridgeView<'a> {
    pub fn from_section_bytes(bytes: &'a [u8]) -> Result<Self, PackError> {
        let hdr = cast_ref::<EntryBridgeHeader>(bytes)?;
        let mat_count = hdr.mat_state_count as usize;
        let entry_count = hdr.sorted_entry_count as usize;

        let ms_start = size_of::<EntryBridgeHeader>();
        let ms_bytes = mat_count
            .checked_mul(size_of::<MatStateMeta>())
            .ok_or(PackError::MalformedSection)?;
        let idx_start = ms_start
            .checked_add(ms_bytes)
            .ok_or(PackError::MalformedSection)?;
        let idx_bytes = entry_count
            .checked_mul(size_of::<u16>())
            .ok_or(PackError::MalformedSection)?;
        let end = idx_start
            .checked_add(idx_bytes)
            .ok_or(PackError::MalformedSection)?;
        if end > bytes.len() {
            return Err(PackError::MalformedSection);
        }

        let mat_states = cast_slice::<MatStateMeta>(&bytes[ms_start..idx_start], mat_count)?;
        let sorted_entry_indices = cast_slice::<u16>(&bytes[idx_start..end], entry_count)?;
        for &idx in sorted_entry_indices {
            if (idx as usize) >= mat_count {
                return Err(PackError::MalformedSection);
            }
        }

        Ok(Self {
            header: hdr,
            mat_states,
            sorted_entry_indices,
        })
    }

    #[inline]
    pub fn mat_state_count(&self) -> usize {
        self.mat_states.len()
    }

    #[inline]
    pub fn mat_state(&self, mat_idx: u16) -> MatStateMeta {
        self.mat_states[mat_idx as usize]
    }

    #[inline]
    pub fn root_abstract_state(&self, slot: usize) -> u16 {
        self.header.root_state_ids[slot]
    }

    /// Resolves `(abstract_state_id, phono_bits)` to its materialized FST state ID via binary search.
    pub fn resolve_entry_state(&self, abstract_state_id: u16, phono_bits: u32) -> Option<u16> {
        let pos = self
            .sorted_entry_indices
            .binary_search_by_key(&(abstract_state_id, phono_bits), |&mat_idx| {
                let m = self.mat_states[mat_idx as usize];
                (m.abstract_state_id, m.phono_bits)
            })
            .ok()?;
        Some(self.sorted_entry_indices[pos])
    }
}
