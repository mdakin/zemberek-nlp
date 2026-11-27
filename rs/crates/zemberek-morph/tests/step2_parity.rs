use std::collections::BTreeSet;
use std::fs;
use std::path::PathBuf;
use zemberek_core::{PrimaryPos, RootAttribute, SecondaryPos};
use zemberek_morph::{DynamicStemTrie, LexiconGraph, StemMatch, StemSource};
use zemberek_pack::{ZmbkPack, SECT_ENTRY_BRIDGE, SECT_LEXICON, SECT_STEM_TRIE};

#[repr(align(64))]
struct AlignedBuf(#[allow(dead_code)] [u8; 64]);

fn read_aligned(path: &PathBuf) -> Vec<u8> {
    let raw = fs::read(path).expect("read file");
    let blocks = raw.len().div_ceil(64) + 1;
    let mut backing: Vec<AlignedBuf> = (0..blocks).map(|_| AlignedBuf([0u8; 64])).collect();
    let ptr = backing.as_mut_ptr() as *mut u8;
    let cap_bytes = backing.capacity() * 64;
    let len = raw.len();
    std::mem::forget(backing);
    let mut aligned = unsafe { Vec::from_raw_parts(ptr, len, cap_bytes) };
    aligned.copy_from_slice(&raw);
    aligned
}

struct BinReader<'a> {
    bytes: &'a [u8],
    pos: usize,
}

impl<'a> BinReader<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, pos: 0 }
    }

    fn u8(&mut self) -> u8 {
        let v = self.bytes[self.pos];
        self.pos += 1;
        v
    }

    fn u16_be(&mut self) -> u16 {
        let v = u16::from_be_bytes(self.bytes[self.pos..self.pos + 2].try_into().unwrap());
        self.pos += 2;
        v
    }

    fn i32_be(&mut self) -> i32 {
        let v = i32::from_be_bytes(self.bytes[self.pos..self.pos + 4].try_into().unwrap());
        self.pos += 4;
        v
    }

    fn u32_be(&mut self) -> u32 {
        self.i32_be() as u32
    }

    fn str(&mut self) -> &'a str {
        let len = self.u16_be() as usize;
        let s = std::str::from_utf8(&self.bytes[self.pos..self.pos + len]).unwrap();
        self.pos += len;
        s
    }
}

#[test]
fn step2_full_lexicon_and_50k_stem_prefix_parity() {
    let manifest_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let zmbk_path = manifest_dir.join("../../testdata/zemberek_step2.zmbk");
    let parity_path = manifest_dir.join("../../testdata/step2_parity.bin");

    let zmbk_bytes = read_aligned(&zmbk_path);
    let pack = ZmbkPack::parse(&zmbk_bytes, true).expect("parse zemberek_step2.zmbk with CRC32");

    // 1. Verify combined binary size budget <= 1.95 MiB
    let (lex_sec, _) = pack.section_bytes(SECT_LEXICON).unwrap();
    let (trie_sec, _) = pack.section_bytes(SECT_STEM_TRIE).unwrap();
    let (bridge_sec, _) = pack.section_bytes(SECT_ENTRY_BRIDGE).unwrap();
    let combined_bytes = lex_sec.byte_len + trie_sec.byte_len + bridge_sec.byte_len;
    let max_budget_bytes: u64 = (1.95 * 1024.0 * 1024.0) as u64;
    assert!(
        combined_bytes <= max_budget_bytes,
        "Combined Step 2 section size {combined_bytes} B exceeds 1.95 MiB ({max_budget_bytes} B)"
    );

    let graph = LexiconGraph::from_pack(&pack).expect("map LexiconGraph");
    let fixture = fs::read(&parity_path).expect("read step2_parity.bin");
    let mut r = BinReader::new(&fixture);

    // 2. Verify 100.0% parity across all 68,444 DictionaryItems
    let item_count = r.u32_be() as usize;
    assert_eq!(graph.item_count(), item_count);
    assert_eq!(item_count, 68_444);

    for id in 0..item_count {
        let exp_lemma = r.str();
        let exp_root = r.str();
        let exp_norm_lemma = r.str();
        let exp_id_str = r.str();
        let exp_ppos = r.u8();
        let exp_spos = r.u8();
        let exp_attrs = r.u32_be();
        let exp_index = r.u8();
        let exp_spec = r.u8();
        let exp_ref = r.i32_be();

        let item = graph.item(id as u32);
        assert_eq!(item.lemma(), exp_lemma, "lemma mismatch at id={id}");
        assert_eq!(item.root(), exp_root, "root mismatch at id={id}");
        assert_eq!(
            item.normalized_lemma(),
            exp_norm_lemma,
            "normalized_lemma mismatch at id={id}"
        );
        assert_eq!(item.id_string(), exp_id_str, "id_string mismatch at id={id}");
        assert_eq!(item.primary_pos() as u8, exp_ppos);
        assert_eq!(item.secondary_pos() as u8, exp_spos);
        assert_eq!(item.root_attrs().0, exp_attrs);
        assert_eq!(item.homonym_index(), exp_index);
        assert_eq!(item.special_root_id(), exp_spec);
        let got_ref = item.reference_item_id().map_or(-1, |v| v as i32);
        assert_eq!(got_ref, exp_ref, "referenceItem mismatch at id={id}");
    }

    // 3. Verify 100.0% getPrefixMatches parity across all 50,000 corpus words (exact & ASCII-tolerant!)
    let word_count = r.u32_be() as usize;
    assert_eq!(word_count, 50_000);

    let mut matches: Vec<StemMatch> = Vec::with_capacity(16);
    for w_idx in 0..word_count {
        let word = r.str();
        for ascii_tol in [false, true] {
            let exp_count = r.u16_be() as usize;
            let mut exp_list: Vec<(&str, u32, u16, u32)> = Vec::with_capacity(exp_count);
            for _ in 0..exp_count {
                let surf = r.str();
                let item_id = r.u32_be();
                let abs_state = r.u16_be();
                let phono = r.u32_be();
                exp_list.push((surf, item_id, abs_state, phono));
            }

            graph.find_prefix_matches(word, ascii_tol, None, &mut matches);
            assert_eq!(
                matches.len(),
                exp_list.len(),
                "match count mismatch at word #{w_idx} {word:?} (ascii_tol={ascii_tol})"
            );

            if !ascii_tol {
                // Exact mode: order is deterministic (prefix length ASC, item registration order ASC)
                for (m, &(e_surf, e_item, e_abs, e_phono)) in matches.iter().zip(exp_list.iter()) {
                    let ms = graph.bridge.mat_state(m.target.mat_entry_state());
                    assert_eq!(
                        (
                            m.canonical_surface.as_str(),
                            m.target.item_id(),
                            ms.abstract_state_id,
                            ms.phono_bits
                        ),
                        (e_surf, e_item, e_abs, e_phono),
                        "exact match detail mismatch for {word:?}"
                    );
                }
            } else {
                // ASCII-tolerant mode: Java `asciiKeys` uses `HashSet<String>` iteration order within
                // the same prefix character length, so verify exact set equality + prefix ordering!
                for w in matches.windows(2) {
                    assert!(w[0].consumed_chars <= w[1].consumed_chars);
                }
                let got_set: BTreeSet<(&str, u32, u16, u32)> = matches
                    .iter()
                    .map(|m| {
                        let ms = graph.bridge.mat_state(m.target.mat_entry_state());
                        (
                            m.canonical_surface.as_str(),
                            m.target.item_id(),
                            ms.abstract_state_id,
                            ms.phono_bits,
                        )
                    })
                    .collect();
                let exp_set: BTreeSet<(&str, u32, u16, u32)> = exp_list.into_iter().collect();
                assert_eq!(
                    got_set, exp_set,
                    "ascii_tolerant match set mismatch for {word:?}"
                );
            }
        }
    }
    assert_eq!(r.pos, fixture.len());
}

#[test]
fn dynamic_stem_trie_user_words_voicing_and_ascii_tolerance() {
    let manifest_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let zmbk_path = manifest_dir.join("../../testdata/zemberek_step2.zmbk");
    let zmbk_bytes = read_aligned(&zmbk_path);
    let pack = ZmbkPack::parse(&zmbk_bytes, false).unwrap();
    let graph = LexiconGraph::from_pack(&pack).unwrap();

    let mut dyn_trie = DynamicStemTrie::new(graph.item_count() as u32);

    // 1. Multi-syllable noun ending in stop consonant -> automatic Voicing (`kankitap` + `kankitab`)
    let noun_id = dyn_trie
        .add_user_word("kankitap", &graph.bridge)
        .expect("add kankitap");
    let noun_item = dyn_trie.get_item(noun_id).unwrap();
    assert_eq!(noun_item.primary_pos, PrimaryPos::Noun);
    assert!(noun_item.root_attrs.contains(RootAttribute::Voicing));

    let mut hits = Vec::new();
    graph.find_prefix_matches("kankitaba", false, Some(&dyn_trie), &mut hits);
    assert!(hits
        .iter()
        .any(|m| m.source == StemSource::Dynamic && m.canonical_surface == "kankitab"));

    // 2. Verb ending with vowel -> ProgressiveVowelDrop (`tweetlemek` -> `tweetle` + `tweetl`)
    let verb_id = dyn_trie
        .add_user_word("tweetlemek", &graph.bridge)
        .expect("add tweetlemek");
    let verb_item = dyn_trie.get_item(verb_id).unwrap();
    assert_eq!(verb_item.primary_pos, PrimaryPos::Verb);
    assert!(verb_item
        .root_attrs
        .contains(RootAttribute::ProgressiveVowelDrop));

    graph.find_prefix_matches("tweetliyor", false, Some(&dyn_trie), &mut hits);
    assert!(hits
        .iter()
        .any(|m| m.source == StemSource::Dynamic && m.canonical_surface == "tweetl"));

    // 3. Proper noun with Turkish diacritics -> ASCII-tolerant matching + removal
    let prop_id = dyn_trie
        .add_user_word("Çiçekdağlı", &graph.bridge)
        .expect("add Çiçekdağlı");
    let prop_item = dyn_trie.get_item(prop_id).unwrap();
    assert_eq!(prop_item.secondary_pos, SecondaryPos::ProperNoun);

    graph.find_prefix_matches("cicekdagliya", true, Some(&dyn_trie), &mut hits);
    assert!(hits.iter().any(|m| {
        m.source == StemSource::Dynamic
            && m.canonical_surface == "çiçekdağlı"
            && m.consumed_input_bytes == "cicekdagli".len()
    }));

    assert!(dyn_trie.remove_item(prop_id));
    graph.find_prefix_matches("cicekdagliya", true, Some(&dyn_trie), &mut hits);
    assert!(!hits.iter().any(|m| m.target.item_id() == prop_id));
}
