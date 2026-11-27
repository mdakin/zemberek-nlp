use std::alloc::{alloc, dealloc, Layout};
use std::fs;
use std::path::PathBuf;
use zemberek_pack::dict::LexiconView;
use zemberek_pack::mphf::QuantizedWeightTableView;
use zemberek_pack::{PackError, ZmbkPack, SECT_LEXICON, SECT_QWT_MPHF, ZMBK_ALIGNMENT};

struct AlignedBuf {
    ptr: *mut u8,
    len: usize,
    layout: Layout,
}

impl AlignedBuf {
    fn from_bytes(src: &[u8]) -> Self {
        let layout = Layout::from_size_align(src.len().max(64), ZMBK_ALIGNMENT).unwrap();
        let ptr = unsafe { alloc(layout) };
        assert!(!ptr.is_null());
        unsafe {
            std::ptr::copy_nonoverlapping(src.as_ptr(), ptr, src.len());
        }
        Self {
            ptr,
            len: src.len(),
            layout,
        }
    }

    fn as_slice(&self) -> &[u8] {
        unsafe { std::slice::from_raw_parts(self.ptr, self.len) }
    }

    fn as_mut_slice(&mut self) -> &mut [u8] {
        unsafe { std::slice::from_raw_parts_mut(self.ptr, self.len) }
    }
}

impl Drop for AlignedBuf {
    fn drop(&mut self) {
        unsafe { dealloc(self.ptr, self.layout) };
    }
}

fn testdata_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../testdata")
}

#[test]
fn zero_copy_lexicon_and_mphf_match_java_bit_for_bit() {
    let dir = testdata_dir();
    let raw = fs::read(dir.join("step0_mini.zmbk")).expect("read step0_mini.zmbk");
    let expected_tsv = fs::read_to_string(dir.join("step0_expected.tsv")).expect("read expected");

    let aligned = AlignedBuf::from_bytes(&raw);
    let pack = ZmbkPack::parse(aligned.as_slice(), true).expect("valid .zmbk pack");
    assert_eq!(pack.sections().len(), 2);

    let (_, lex_bytes) = pack.section_bytes(SECT_LEXICON).expect("lexicon section");
    let lex = LexiconView::from_section_bytes(lex_bytes).expect("valid lexicon view");

    let (_, qwt_bytes) = pack.section_bytes(SECT_QWT_MPHF).expect("qwt section");
    let qwt = QuantizedWeightTableView::from_section_bytes(qwt_bytes).expect("valid qwt view");
    assert!(
        qwt.mphf().level_count() >= 2,
        "1024-key MPHF should exercise multi-level fallback"
    );

    let mut dict_checked = 0usize;
    let mut qwt_checked = 0usize;
    let mut non_zero_hits = 0usize;

    for line in expected_tsv.lines() {
        if line.is_empty() {
            continue;
        }
        let cols: Vec<&str> = line.split('\t').collect();
        match cols[0] {
            "DICT" => {
                let idx: usize = cols[1].parse().unwrap();
                let exp_lemma = cols[2];
                let exp_attrs: u32 = cols[3].parse().unwrap();
                let exp_ppos: u8 = cols[4].parse().unwrap();
                let exp_spos: u8 = cols[5].parse().unwrap();

                assert_eq!(lex.lemma(idx), exp_lemma, "lemma mismatch at {idx}");
                let item = &lex.items()[idx];
                assert_eq!(item.root_attrs, exp_attrs, "root_attrs mismatch at {idx}");
                assert_eq!(item.primary_pos, exp_ppos, "primary_pos mismatch at {idx}");
                assert_eq!(
                    item.secondary_pos, exp_spos,
                    "secondary_pos mismatch at {idx}"
                );
                dict_checked += 1;
            }
            "QWT" => {
                let key: u64 = cols[1].parse().unwrap();
                let exp_bits: u32 = cols[2].parse().unwrap();
                let got_bits = qwt.get(key).to_bits();
                assert_eq!(
                    got_bits, exp_bits,
                    "IEEE-754 weight mismatch for key={key}: got=0x{got_bits:08x}, exp=0x{exp_bits:08x}"
                );
                if got_bits != 0 {
                    non_zero_hits += 1;
                }
                qwt_checked += 1;
            }
            other => panic!("unexpected row tag: {other}"),
        }
    }

    assert_eq!(dict_checked, lex.len());
    assert_eq!(qwt_checked, 1024 + 256);
    assert!(non_zero_hits >= 1000, "expected >=1000 non-zero MPHF hits");
}

#[test]
fn rejects_unaligned_or_corrupted_pack() {
    let raw = fs::read(testdata_dir().join("step0_mini.zmbk")).unwrap();
    let mut padded = vec![0u8; raw.len() + 128];
    let base = (padded.as_ptr() as usize).next_multiple_of(64) - (padded.as_ptr() as usize);
    // Intentionally misalign by +1 byte
    padded[base + 1..base + 1 + raw.len()].copy_from_slice(&raw);
    assert_eq!(
        ZmbkPack::parse(&padded[base + 1..base + 1 + raw.len()], false).unwrap_err(),
        PackError::UnalignedBase
    );

    // Corrupt a payload byte inside Section 1 and verify CRC32 catches it
    let mut aligned = AlignedBuf::from_bytes(&raw);
    let pack = ZmbkPack::parse(aligned.as_slice(), true).unwrap();
    let payload_pos = (pack.sections()[0].offset as usize) + 16;
    aligned.as_mut_slice()[payload_pos] ^= 0xFF;
    assert_eq!(
        ZmbkPack::parse(aligned.as_slice(), true).unwrap_err(),
        PackError::ChecksumMismatch
    );
}
