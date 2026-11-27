#![no_std]

pub mod clean_lm;
pub mod deasc;
pub mod dict;
pub mod ending_trie;
pub mod morph_fst;
pub mod mphf;
pub mod norm;
pub mod stem_trie;

use core::mem::{align_of, size_of};

pub const ZMBK_MAGIC: [u8; 4] = *b"ZMBK";
pub const ZMBK_VERSION: u16 = 1;
pub const ZMBK_ENDIAN_TAG: u16 = 0x0102;
pub const ZMBK_ALIGNMENT: usize = 64;

pub const SECT_LEXICON: u32 = 1;
pub const SECT_QWT_MPHF: u32 = 2;
pub const SECT_STEM_TRIE: u32 = 3;
pub const SECT_ENTRY_BRIDGE: u32 = 4;
pub const SECT_MORPH_FST: u32 = 5;
pub const SECT_AMBIG_QWT: u32 = 6;
pub const SECT_CLEAN_LM: u32 = 7;
pub const SECT_ENDING_TRIE: u32 = 8;
pub const SECT_DEASC: u32 = 9;
pub const SECT_NORM_KNOWLEDGE: u32 = 10;
pub const SECT_NORM_PERCEPTRON: u32 = 11;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PackError {
    UnalignedBase,
    BufferTooSmall,
    InvalidMagic,
    UnsupportedVersion,
    EndianMismatch,
    TruncatedFile,
    SectionOutOfBounds,
    SectionUnaligned,
    ChecksumMismatch,
    SectionNotFound,
    MalformedSection,
    InvalidUtf8Pool,
}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct ZmbkHeader {
    pub magic: [u8; 4],
    pub version: u16,
    pub endian_tag: u16,
    pub section_count: u32,
    pub total_bytes: u32,
    pub flags: u32,
    pub reserved: [u8; 44],
}

const _: () = assert!(size_of::<ZmbkHeader>() == 64);

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct ZmbkSectionEntry {
    pub kind: u32,
    pub flags: u32,
    pub offset: u64,
    pub byte_len: u64,
    pub elem_count: u32,
    pub crc32: u32,
}

const _: () = assert!(size_of::<ZmbkSectionEntry>() == 32);

/// Zero-copy view over an in-memory 64-byte-aligned `.zmbk` packfile.
#[derive(Clone, Copy, Debug)]
pub struct ZmbkPack<'a> {
    bytes: &'a [u8],
    header: &'a ZmbkHeader,
    sections: &'a [ZmbkSectionEntry],
}

impl<'a> ZmbkPack<'a> {
    /// Maps a `.zmbk` byte slice in `O(sections)` with zero heap allocations.
    /// If `verify_crc` is true, validates IEEE CRC32 checksums of every section.
    pub fn parse(bytes: &'a [u8], verify_crc: bool) -> Result<Self, PackError> {
        if !(bytes.as_ptr() as usize).is_multiple_of(align_of::<u64>()) {
            return Err(PackError::UnalignedBase);
        }
        if bytes.len() < size_of::<ZmbkHeader>() {
            return Err(PackError::BufferTooSmall);
        }

        let header = cast_ref::<ZmbkHeader>(&bytes[..size_of::<ZmbkHeader>()])?;
        if header.magic != ZMBK_MAGIC {
            return Err(PackError::InvalidMagic);
        }
        if header.endian_tag != ZMBK_ENDIAN_TAG {
            return Err(PackError::EndianMismatch);
        }
        if header.version != ZMBK_VERSION {
            return Err(PackError::UnsupportedVersion);
        }
        let total = header.total_bytes as usize;
        if total > bytes.len() {
            return Err(PackError::TruncatedFile);
        }

        let sec_count = header.section_count as usize;
        let dir_start = size_of::<ZmbkHeader>();
        let dir_bytes = sec_count
            .checked_mul(size_of::<ZmbkSectionEntry>())
            .ok_or(PackError::BufferTooSmall)?;
        let dir_end = dir_start
            .checked_add(dir_bytes)
            .ok_or(PackError::BufferTooSmall)?;
        if dir_end > total {
            return Err(PackError::TruncatedFile);
        }

        let sections = cast_slice::<ZmbkSectionEntry>(&bytes[dir_start..dir_end], sec_count)?;
        for s in sections {
            let off = s.offset as usize;
            let len = s.byte_len as usize;
            if !off.is_multiple_of(ZMBK_ALIGNMENT) {
                return Err(PackError::SectionUnaligned);
            }
            let end = off.checked_add(len).ok_or(PackError::SectionOutOfBounds)?;
            if off < dir_end || end > total {
                return Err(PackError::SectionOutOfBounds);
            }
            if verify_crc && crc32_ieee(&bytes[off..end]) != s.crc32 {
                return Err(PackError::ChecksumMismatch);
            }
        }

        Ok(Self {
            bytes: &bytes[..total],
            header,
            sections,
        })
    }

    #[inline]
    pub fn header(&self) -> &ZmbkHeader {
        self.header
    }

    #[inline]
    pub fn sections(&self) -> &[ZmbkSectionEntry] {
        self.sections
    }

    #[inline]
    pub fn section_bytes(&self, kind: u32) -> Result<(&'a ZmbkSectionEntry, &'a [u8]), PackError> {
        for s in self.sections {
            if s.kind == kind {
                let start = s.offset as usize;
                let end = start + (s.byte_len as usize);
                return Ok((s, &self.bytes[start..end]));
            }
        }
        Err(PackError::SectionNotFound)
    }
}

/// Marker trait for plain-old-data (`#[repr(C)]`) structs safe to cast from aligned little-endian bytes.
///
/// # Safety
/// Implementors must be `#[repr(C)]`, have no padding with uninitialized-read UB, and allow any bit pattern.
pub unsafe trait Pod: Copy + 'static {}

unsafe impl Pod for u8 {}
unsafe impl Pod for u16 {}
unsafe impl Pod for i16 {}
unsafe impl Pod for u32 {}
unsafe impl Pod for u64 {}
unsafe impl Pod for i32 {}
unsafe impl Pod for f32 {}
unsafe impl Pod for ZmbkHeader {}
unsafe impl Pod for ZmbkSectionEntry {}

#[inline]
pub fn cast_ref<T: Pod>(bytes: &[u8]) -> Result<&T, PackError> {
    if bytes.len() < size_of::<T>() {
        return Err(PackError::MalformedSection);
    }
    if !(bytes.as_ptr() as usize).is_multiple_of(align_of::<T>()) {
        return Err(PackError::UnalignedBase);
    }
    // SAFETY: `T: Pod` allows any bit pattern, size and alignment were verified above,
    // and wasm32 / x86_64 targets are little-endian (verified by `endian_tag`).
    Ok(unsafe { &*(bytes.as_ptr() as *const T) })
}

#[inline]
pub fn cast_slice<T: Pod>(bytes: &[u8], count: usize) -> Result<&[T], PackError> {
    let needed = count
        .checked_mul(size_of::<T>())
        .ok_or(PackError::MalformedSection)?;
    if bytes.len() < needed {
        return Err(PackError::MalformedSection);
    }
    if count > 0 && !(bytes.as_ptr() as usize).is_multiple_of(align_of::<T>()) {
        return Err(PackError::UnalignedBase);
    }
    // SAFETY: `T: Pod` allows any bit pattern; bounds and alignment were verified above.
    Ok(unsafe { core::slice::from_raw_parts(bytes.as_ptr() as *const T, count) })
}

/// Compact table-free IEEE 802.3 CRC32 for optional `.zmbk` section verification.
pub fn crc32_ieee(data: &[u8]) -> u32 {
    let mut crc: u32 = 0xFFFF_FFFF;
    for &b in data {
        crc ^= b as u32;
        for _ in 0..8 {
            let mask = (crc & 1).wrapping_neg();
            crc = (crc >> 1) ^ (0xEDB8_8320 & mask);
        }
    }
    !crc
}
