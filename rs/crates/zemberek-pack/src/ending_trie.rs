use core::mem::size_of;
use core::ops::Range;
use crate::{cast_ref, cast_slice, PackError, Pod};

pub const ENDING_TERM_BIT: u8 = 0x80;

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct EndingTrieHeader {
    pub node_count: u32,
    pub palette_len: u32,
    pub block_count: u32,
    pub max_ending_len: u32,
    pub reserved: [u32; 4],
}

const _: () = assert!(size_of::<EndingTrieHeader>() == 32);
unsafe impl Pod for EndingTrieHeader {}

/// Zero-copy BFS-ordered 1-char-per-edge CSR `endingTrie` (`SECT_ENDING_TRIE = 8`, `4.25 B/node`).
///
/// Because nodes `0..node_count` are numbered in BFS order with sorted outgoing `u16` characters,
/// the target node of edge `e` (`0..node_count - 1`) is simply `e + 1`, and `can_terminate` is
/// packed into bit 7 of `min_rem_and_term` (`min_rem <= 126`).
#[derive(Clone, Copy, Debug)]
pub struct EndingTrieView<'a> {
    edge_start_blocks: &'a [u32],
    palette: &'a [u16; 256],
    child_counts: &'a [u8],
    edge_char_codes: &'a [u8],
    min_rem_and_term: &'a [u8],
    max_rem: &'a [u8],
    max_ending_len: usize,
}

impl<'a> EndingTrieView<'a> {
    pub fn from_section_bytes(bytes: &'a [u8]) -> Result<Self, PackError> {
        let hdr = cast_ref::<EndingTrieHeader>(bytes)?;
        let node_count = hdr.node_count as usize;
        let palette_len = hdr.palette_len as usize;
        let block_count = hdr.block_count as usize;
        if node_count == 0
            || palette_len > 256
            || block_count != (node_count >> 4) + 1
        {
            return Err(PackError::MalformedSection);
        }
        let edge_count = node_count - 1;

        let mut cursor = size_of::<EndingTrieHeader>();

        let blk_bytes = block_count
            .checked_mul(4)
            .ok_or(PackError::MalformedSection)?;
        let blk_end = cursor
            .checked_add(blk_bytes)
            .ok_or(PackError::MalformedSection)?;
        let edge_start_blocks = cast_slice::<u32>(&bytes[cursor..blk_end], block_count)?;
        cursor = blk_end;

        let pal_end = cursor
            .checked_add(256 * 2)
            .ok_or(PackError::MalformedSection)?;
        let pal_slice = cast_slice::<u16>(&bytes[cursor..pal_end], 256)?;
        let palette: &'a [u16; 256] = pal_slice
            .try_into()
            .map_err(|_| PackError::MalformedSection)?;
        cursor = pal_end;

        let mut take_u8 = |len: usize| -> Result<&'a [u8], PackError> {
            let end = cursor.checked_add(len).ok_or(PackError::MalformedSection)?;
            if end > bytes.len() {
                return Err(PackError::MalformedSection);
            }
            let s = &bytes[cursor..end];
            cursor = end;
            Ok(s)
        };

        let child_counts = take_u8(node_count)?;
        let edge_char_codes = take_u8(edge_count)?;
        let min_rem_and_term = take_u8(node_count)?;
        let max_rem = take_u8(node_count)?;

        for &code in edge_char_codes {
            if (code as usize) >= palette_len {
                return Err(PackError::MalformedSection);
            }
        }
        let mut running_edges = 0usize;
        for (i, &cc) in child_counts.iter().enumerate() {
            if (i & 15) == 0 && (edge_start_blocks[i >> 4] as usize) != running_edges {
                return Err(PackError::MalformedSection);
            }
            running_edges = running_edges
                .checked_add(cc as usize)
                .ok_or(PackError::MalformedSection)?;
        }
        if running_edges != edge_count {
            return Err(PackError::MalformedSection);
        }

        Ok(Self {
            edge_start_blocks,
            palette,
            child_counts,
            edge_char_codes,
            min_rem_and_term,
            max_rem,
            max_ending_len: hdr.max_ending_len as usize,
        })
    }

    #[inline]
    pub fn node_count(&self) -> usize {
        self.child_counts.len()
    }

    #[inline]
    pub fn max_ending_len(&self) -> usize {
        self.max_ending_len
    }

    #[inline]
    pub fn is_terminal(&self, node: usize) -> bool {
        (self.min_rem_and_term[node] & ENDING_TERM_BIT) != 0
    }

    #[inline]
    pub fn min_rem(&self, node: usize) -> i32 {
        (self.min_rem_and_term[node] & 0x7F) as i32
    }

    #[inline]
    pub fn max_rem(&self, node: usize) -> i32 {
        self.max_rem[node] as i32
    }

    #[inline]
    pub fn edges_range(&self, node: usize) -> Range<usize> {
        let base_node = node & !15;
        let mut start = self.edge_start_blocks[node >> 4] as usize;
        for &c in &self.child_counts[base_node..node] {
            start += c as usize;
        }
        let count = self.child_counts[node] as usize;
        start..(start + count)
    }

    #[inline]
    pub fn edge_u16(&self, edge_idx: usize) -> u16 {
        self.palette[self.edge_char_codes[edge_idx] as usize]
    }

    #[inline]
    pub const fn edge_target(&self, edge_idx: usize) -> usize {
        edge_idx + 1
    }

    #[inline]
    pub fn find_child(&self, node: usize, target_u16: u16) -> Option<usize> {
        let range = self.edges_range(node);
        let count = range.end - range.start;
        if count <= 6 {
            for e in range {
                let ec = self.palette[self.edge_char_codes[e] as usize];
                if ec == target_u16 {
                    return Some(e + 1);
                }
                if ec > target_u16 {
                    return None;
                }
            }
            return None;
        }
        let mut lo = range.start;
        let mut hi = range.end - 1;
        while lo <= hi {
            let mid = (lo + hi) >> 1;
            let ec = self.palette[self.edge_char_codes[mid] as usize];
            if ec < target_u16 {
                lo = mid + 1;
            } else if ec > target_u16 {
                if mid == 0 {
                    break;
                }
                hi = mid - 1;
            } else {
                return Some(mid + 1);
            }
        }
        None
    }
}
