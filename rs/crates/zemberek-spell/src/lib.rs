#![no_std]
#![forbid(unsafe_code)]

extern crate alloc;

use alloc::string::String;
use alloc::vec;
use alloc::vec::Vec;
use core::cell::RefCell;
use core::cmp::Ordering;
use core::ops::Range;
use zemberek_core::{PhonoMask, PhoneticAttribute, PrimaryPos, TurkishAlphabet};
use zemberek_morph::{
    AnalyzerScratch, CaseType, DynamicStemTrie, LexiconGraph, SingleAnalysis, TurkishMorphology,
    WordAnalysisSurfaceFormatter,
};
use zemberek_pack::clean_lm::CleanWordLmView;
use zemberek_pack::ending_trie::EndingTrieView;
use zemberek_pack::stem_trie::PackedStemTarget;
use zemberek_pack::{PackError, ZmbkPack, SECT_ENDING_TRIE};

const FLAG_CAN_TERMINATE: u8 = 1;
const FLAG_ALLOWS_VOWEL_ENDING: u8 = 1 << 1;
const FLAG_ALLOWS_CONSONANT_ENDING: u8 = 1 << 2;
const FLAG_HAS_STEM: u8 = 1 << 3;
const FLAG_FRONT_HARMONY: u8 = 1 << 4;
const FLAG_BACK_HARMONY: u8 = 1 << 5;
const FLAG_ALLOWS_VOICED_D: u8 = 1 << 6;

const MAX_ASCII_TABLE: usize = 512;
const MORPH_CACHE_SIZE: usize = 4096;
const MORPH_CACHE_MASK: usize = MORPH_CACHE_SIZE - 1;

const INVARIANT_HARMONY_PREFIXES: &[&[u16]] = &[
    &[b'y' as u16, b'o' as u16, b'r' as u16],
    &[b'k' as u16, b'e' as u16, b'n' as u16],
    &[b'y' as u16, b'k' as u16, b'e' as u16, b'n' as u16],
    &[b'k' as u16, b'i' as u16],
    &[b'l' as u16, b'e' as u16, b'y' as u16, b'i' as u16, b'n' as u16],
    &[b'g' as u16, b'i' as u16, b'l' as u16],
    &[b'm' as u16, b't' as u16, 0x0131, b'r' as u16, b'a' as u16, b'k' as u16], // "mtırak"
    &[b'd' as u16, b'a' as u16, 0x015F], // "daş"
    &[b't' as u16, b'a' as u16, 0x015F], // "taş"
];

/// Morphologically valid, case-formatted spellcheck candidate with exact edit and diacritic costs.
#[derive(Clone, Debug, PartialEq)]
pub struct ScoredCandidate {
    pub surface: String,
    pub edit_cost: f32,
    pub diacritic_diffs: u32,
}

impl ScoredCandidate {
    #[inline]
    pub fn raw_rank_score(&self) -> f32 {
        self.edit_cost + 0.20 * (self.diacritic_diffs as f32)
    }

    #[inline]
    pub fn total_distance_cost(&self) -> f32 {
        self.edit_cost + 0.28 * (self.diacritic_diffs as f32)
    }
}

/// Fast `O(1)` BFS-ordered 1-char-per-edge CSR trie (`edge_target(e) == e + 1`) with exact 64-bit
/// remaining-length bitmasks (`rem_mask[node]`).
#[derive(Clone, Debug)]
struct FastBfsTrie {
    edge_starts: Vec<u32>,
    edge_chars: Vec<u16>,
    flags: Vec<u8>,
    rem_mask: Vec<u64>,
}

struct TempStemNode {
    children: Vec<(u16, usize)>,
    flags: u8,
}

#[inline(always)]
fn shift_rem_mask(child_mask: u64) -> u64 {
    ((child_mask & !(1u64 << 63)) << 1)
        | ((child_mask >> 62) & 1) << 63
        | (child_mask & (1u64 << 63))
}

#[inline(always)]
fn rem_matches_exact(mask: u64, rem: usize) -> bool {
    if rem >= 63 {
        (mask >> 63) != 0
    } else {
        ((mask >> rem) & 1) != 0
    }
}

#[inline(always)]
fn rem_matches_budget1(mask: u64, rem: usize) -> bool {
    if rem >= 62 {
        (mask >> 61) != 0
    } else if rem == 0 {
        (mask & 0b11) != 0
    } else {
        (mask & (0b111u64 << (rem - 1))) != 0
    }
}

impl FastBfsTrie {
    fn from_ending_view(view: &EndingTrieView<'_>) -> Self {
        let n = view.node_count();
        let edge_count = n.saturating_sub(1);
        let mut edge_starts = Vec::with_capacity(n + 1);
        let mut edge_chars = Vec::with_capacity(edge_count);
        let mut flags = Vec::with_capacity(n);

        for node in 0..n {
            let r = view.edges_range(node);
            edge_starts.push(r.start as u32);
            for e in r {
                edge_chars.push(view.edge_u16(e));
            }
            flags.push(if view.is_terminal(node) {
                FLAG_CAN_TERMINATE
            } else {
                0
            });
        }
        edge_starts.push(edge_count as u32);

        let mut rem_mask = vec![0u64; n];
        for i in (0..n).rev() {
            let mut m = if (flags[i] & FLAG_CAN_TERMINATE) != 0 {
                1u64
            } else {
                0u64
            };
            let start = edge_starts[i] as usize;
            let end = edge_starts[i + 1] as usize;
            for e in start..end {
                m |= shift_rem_mask(rem_mask[e + 1]);
            }
            rem_mask[i] = m;
        }

        Self {
            edge_starts,
            edge_chars,
            flags,
            rem_mask,
        }
    }

    fn build_stems(
        graph: &LexiconGraph<'_>,
        dynamic: Option<&DynamicStemTrie>,
        ending_root_mask: u64,
    ) -> Self {
        let mut arena = Vec::with_capacity(140_000);
        arena.push(TempStemNode {
            children: Vec::new(),
            flags: 0,
        });

        let mut char_stack: Vec<u16> = Vec::with_capacity(64);
        Self::collect_static_radix(graph, 0, &mut char_stack, &mut arena);

        if let Some(dyn_trie) = dynamic {
            for (surf, targets) in dyn_trie.stems() {
                if surf.is_empty() {
                    continue;
                }
                let mut combined_flags = 0u8;
                for &t in targets {
                    let is_punc = dyn_trie
                        .get_item(t.item_id())
                        .is_some_and(|it| it.primary_pos == PrimaryPos::Punctuation);
                    if !is_punc {
                        let phono = PhonoMask(graph.bridge.mat_state(t.mat_entry_state()).phono_bits);
                        combined_flags |= stem_flags_for_phono(phono);
                    }
                }
                if combined_flags != 0 {
                    let mut cur = 0usize;
                    for u in surf.encode_utf16() {
                        cur = get_or_add_temp_child(&mut arena, cur, u);
                    }
                    arena[cur].flags |= combined_flags;
                }
            }
        }

        let n = arena.len();
        let mut bfs_to_old = Vec::with_capacity(n);
        bfs_to_old.push(0usize);

        let mut edge_starts = Vec::with_capacity(n + 1);
        let mut edge_chars = Vec::with_capacity(n.saturating_sub(1));
        let mut flags = Vec::with_capacity(n);

        let mut running_edges = 0u32;
        let mut head = 0usize;
        while head < bfs_to_old.len() {
            edge_starts.push(running_edges);
            let old_idx = bfs_to_old[head];
            head += 1;
            let f = arena[old_idx].flags;
            let cc = arena[old_idx].children.len();
            flags.push(f);
            running_edges += cc as u32;
            for &(ch, child_old) in &arena[old_idx].children {
                edge_chars.push(ch);
                bfs_to_old.push(child_old);
            }
        }
        edge_starts.push(running_edges);

        let mut rem_mask = vec![0u64; n];
        for i in (0..n).rev() {
            let f = flags[i];
            let mut m = if (f & FLAG_CAN_TERMINATE) != 0 {
                1u64
            } else {
                0u64
            };
            if (f & FLAG_HAS_STEM) != 0
                && (f & (FLAG_ALLOWS_VOWEL_ENDING | FLAG_ALLOWS_CONSONANT_ENDING)) != 0
            {
                m |= ending_root_mask;
            }
            let start = edge_starts[i] as usize;
            let end = edge_starts[i + 1] as usize;
            for e in start..end {
                m |= shift_rem_mask(rem_mask[e + 1]);
            }
            rem_mask[i] = m;
        }

        Self {
            edge_starts,
            edge_chars,
            flags,
            rem_mask,
        }
    }

    fn collect_static_radix(
        graph: &LexiconGraph<'_>,
        radix_idx: usize,
        char_stack: &mut Vec<u16>,
        arena: &mut Vec<TempStemNode>,
    ) {
        let prev_len = char_stack.len();
        if radix_idx > 0 {
            let node = graph.stem_trie.node(radix_idx);
            let lbl = graph.stem_trie.edge_label(node, graph.lexicon.utf8_pool());
            char_stack.extend(lbl.encode_utf16());
        }

        let targets: &[PackedStemTarget] = graph.stem_trie.node_targets(radix_idx);
        if !targets.is_empty() && !char_stack.is_empty() {
            let mut combined_flags = 0u8;
            for &t in targets {
                let item = graph.lexicon.item(t.item_id() as usize);
                if PrimaryPos::from_u8(item.primary_pos) == Some(PrimaryPos::Punctuation) {
                    continue;
                }
                let phono = PhonoMask(graph.bridge.mat_state(t.mat_entry_state()).phono_bits);
                combined_flags |= stem_flags_for_phono(phono);
            }
            if combined_flags != 0 {
                let mut cur = 0usize;
                for &u in char_stack.iter() {
                    cur = get_or_add_temp_child(arena, cur, u);
                }
                arena[cur].flags |= combined_flags;
            }
        }

        let rnode = graph.stem_trie.node(radix_idx);
        let first_child = rnode.first_child_idx();
        let child_count = graph.stem_trie.children_of(radix_idx).len();
        for offset in 0..child_count {
            Self::collect_static_radix(graph, first_child + offset, char_stack, arena);
        }

        char_stack.truncate(prev_len);
    }

    #[inline(always)]
    fn edges_range(&self, node: usize) -> Range<usize> {
        (self.edge_starts[node] as usize)..(self.edge_starts[node + 1] as usize)
    }

    #[inline(always)]
    fn find_child(&self, node: usize, target_u16: u16) -> Option<usize> {
        let start = self.edge_starts[node] as usize;
        let end = self.edge_starts[node + 1] as usize;
        let slice = &self.edge_chars[start..end];
        if slice.len() <= 8 {
            for (idx, &ec) in slice.iter().enumerate() {
                if ec == target_u16 {
                    return Some(start + idx + 1);
                }
                if ec > target_u16 {
                    return None;
                }
            }
            return None;
        }
        slice
            .binary_search(&target_u16)
            .ok()
            .map(|idx| start + idx + 1)
    }
}

#[inline]
fn get_or_add_temp_child(arena: &mut Vec<TempStemNode>, node_idx: usize, ch: u16) -> usize {
    match arena[node_idx].children.binary_search_by_key(&ch, |&(c, _)| c) {
        Ok(pos) => arena[node_idx].children[pos].1,
        Err(ins) => {
            let new_idx = arena.len();
            arena.push(TempStemNode {
                children: Vec::new(),
                flags: 0,
            });
            arena[node_idx].children.insert(ins, (ch, new_idx));
            new_idx
        }
    }
}

#[inline]
fn stem_flags_for_phono(attrs: PhonoMask) -> u8 {
    let mut flags = FLAG_HAS_STEM;
    if !attrs.contains(PhoneticAttribute::CannotTerminate) {
        flags |= FLAG_CAN_TERMINATE;
    }
    if !attrs.contains(PhoneticAttribute::ExpectsConsonant)
        && !attrs.contains(PhoneticAttribute::LastLetterVowel)
    {
        flags |= FLAG_ALLOWS_VOWEL_ENDING;
    }
    if !attrs.contains(PhoneticAttribute::ExpectsVowel) {
        flags |= FLAG_ALLOWS_CONSONANT_ENDING;
    }
    if attrs.contains(PhoneticAttribute::LastVowelFrontal)
        || attrs.contains(PhoneticAttribute::HasNoVowel)
    {
        flags |= FLAG_FRONT_HARMONY;
    }
    if attrs.contains(PhoneticAttribute::LastVowelBack)
        || attrs.contains(PhoneticAttribute::HasNoVowel)
    {
        flags |= FLAG_BACK_HARMONY;
    }
    if !attrs.contains(PhoneticAttribute::LastLetterVoiceless) {
        flags |= FLAG_ALLOWS_VOICED_D;
    }
    flags
}

/// Symmetric `512 × 512` (`32 KiB`) keyboard + phonetic adjacency bitset matching `NEAR_OR_PHONETIC`.
struct NearKeyTable {
    rows: [[u64; 8]; MAX_ASCII_TABLE],
}

impl NearKeyTable {
    fn new() -> Self {
        let mut t = Self {
            rows: [[0u64; 8]; MAX_ASCII_TABLE],
        };
        let pairs: &[(char, &str)] = &[
            ('a', "s"),
            ('b', "vnp"),
            ('c', "vxçj"),
            ('ç', "öcş"),
            ('d', "sft"),
            ('e', "wri"),
            ('f', "gdv"),
            ('g', "fhğk"),
            ('ğ', "pügy"),
            ('h', "gj"),
            ('ı', "oui"),
            ('i', "şıe"),
            ('j', "hkşc"),
            ('k', "jlg"),
            ('l', "kşr"),
            ('m', "nö"),
            ('n', "bm"),
            ('o', "ıpöu"),
            ('ö', "mçoü"),
            ('p', "oğb"),
            ('r', "etl"),
            ('s', "adşz"),
            ('ş', "lisçj"),
            ('t', "ryd"),
            ('u', "yıüo"),
            ('ü', "ğuoö"),
            ('v', "cbfğ"),
            ('y', "tuğ"),
            ('z', "xs"),
        ];
        for &(c, neighbors) in pairs {
            let cu = c as usize;
            for n in neighbors.chars() {
                let nu = n as usize;
                if cu < MAX_ASCII_TABLE && nu < MAX_ASCII_TABLE {
                    t.rows[cu][nu >> 6] |= 1u64 << (nu & 63);
                    t.rows[nu][cu >> 6] |= 1u64 << (cu & 63);
                }
            }
        }
        t
    }

    #[inline(always)]
    fn is_near(&self, a: u16, b: u16) -> bool {
        let au = a as usize;
        let bu = b as usize;
        au < MAX_ASCII_TABLE
            && bu < MAX_ASCII_TABLE
            && ((self.rows[au][bu >> 6] >> (bu & 63)) & 1) != 0
    }
}

#[derive(Clone, Default)]
struct MorphCacheSlot {
    hash: u64,
    key: String,
    analyses: Vec<SingleAnalysis>,
}

/// Reusable zero-alloc internal arena + bounded `4,096`-slot morph validity ring cache.
struct SpellWorkArena {
    gen: u32,
    table: Vec<(u32, u64, u32)>, // (gen, hash, entry_idx + 1)
    mask: usize,
    entries: Vec<RawEntry>,
    u16_pool: Vec<u16>,
    utf8_buf: String,
    morph_cache: Vec<MorphCacheSlot>,
}

impl SpellWorkArena {
    fn new() -> Self {
        let cap = 512usize;
        Self {
            gen: 1,
            table: vec![(0u32, 0u64, 0u32); cap],
            mask: cap - 1,
            entries: Vec::with_capacity(128),
            u16_pool: Vec::with_capacity(1024),
            utf8_buf: String::with_capacity(64),
            morph_cache: vec![MorphCacheSlot::default(); MORPH_CACHE_SIZE],
        }
    }

    fn reset_for_word(&mut self) {
        self.entries.clear();
        self.u16_pool.clear();
        self.gen = self.gen.wrapping_add(1);
        if self.gen == 0 {
            for slot in &mut self.table {
                slot.0 = 0;
            }
            self.gen = 1;
        }
    }

    fn clear_morph_cache(&mut self) {
        for slot in &mut self.morph_cache {
            slot.hash = 0;
            slot.key.clear();
            slot.analyses.clear();
        }
    }

    fn record(
        &mut self,
        stem_s: &[u16],
        end_s: &[u16],
        edit_cost: f32,
        diacritic_diffs: u32,
    ) {
        let stem_len = stem_s.len();
        let ending_len = end_s.len();
        let total_len = stem_len + ending_len;

        let mut h: u64 = 0xcbf2_9ce4_8422_2325;
        for &u in stem_s {
            h = (h ^ (u as u64)).wrapping_mul(0x0100_0000_01b3);
        }
        for &u in end_s {
            h = (h ^ (u as u64)).wrapping_mul(0x0100_0000_01b3);
        }
        h ^= h >> 32;
        if h == 0 {
            h = 1;
        }

        let cur_gen = self.gen;
        let cand_score = edit_cost + 0.20 * (diacritic_diffs as f32);
        let mut slot = (h as usize) & self.mask;
        loop {
            let (sgen, sh, idx1) = self.table[slot];
            if sgen != cur_gen {
                break;
            }
            if sh == h {
                let e = &mut self.entries[(idx1 - 1) as usize];
                if e.u16_len as usize == total_len {
                    let ps = e.pool_off as usize;
                    let existing_u16 = &self.u16_pool[ps..ps + total_len];
                    if &existing_u16[..stem_len] == stem_s && &existing_u16[stem_len..] == end_s {
                        if cand_score < e.raw_rank_score() {
                            e.edit_cost = edit_cost;
                            e.diacritic_diffs = diacritic_diffs;
                        }
                        return;
                    }
                }
            }
            slot = (slot + 1) & self.mask;
        }

        let pool_off = self.u16_pool.len() as u32;
        self.u16_pool.extend_from_slice(stem_s);
        self.u16_pool.extend_from_slice(end_s);
        let entry_idx1 = (self.entries.len() + 1) as u32;
        self.entries.push(RawEntry {
            pool_off,
            u16_len: total_len as u16,
            diacritic_diffs,
            edit_cost,
        });
        self.table[slot] = (cur_gen, h, entry_idx1);

        if self.entries.len() * 2 >= self.table.len() {
            self.grow_table();
        }
    }

    fn grow_table(&mut self) {
        let new_cap = self.table.len() * 2;
        let new_mask = new_cap - 1;
        let cur_gen = self.gen;
        let mut new_tab = vec![(0u32, 0u64, 0u32); new_cap];
        for &(sgen, h, idx1) in &self.table {
            if sgen == cur_gen && idx1 != 0 {
                let mut s = (h as usize) & new_mask;
                while new_tab[s].0 == cur_gen {
                    s = (s + 1) & new_mask;
                }
                new_tab[s] = (cur_gen, h, idx1);
            }
        }
        self.table = new_tab;
        self.mask = new_mask;
    }
}

/// Exact port of `CompactStemEndingSpellChecker` + `TurkishSpellChecker.check` + `CleanWordLm` ranking.
pub struct CompactStemEndingSpellChecker<'a> {
    ending_view: EndingTrieView<'a>,
    ending_trie: FastBfsTrie,
    stem_trie: FastBfsTrie,
    near_table: NearKeyTable,
    arena: RefCell<SpellWorkArena>,
}

impl<'a> CompactStemEndingSpellChecker<'a> {
    pub fn from_pack(
        pack: &ZmbkPack<'a>,
        graph: &LexiconGraph<'a>,
        dynamic: Option<&DynamicStemTrie>,
    ) -> Result<Self, PackError> {
        let (_, ending_bytes) = pack.section_bytes(SECT_ENDING_TRIE)?;
        let ending_view = EndingTrieView::from_section_bytes(ending_bytes)?;
        let ending_trie = FastBfsTrie::from_ending_view(&ending_view);
        let ending_root_mask = ending_trie.rem_mask[0];
        let stem_trie = FastBfsTrie::build_stems(graph, dynamic, ending_root_mask);
        Ok(Self {
            ending_view,
            ending_trie,
            stem_trie,
            near_table: NearKeyTable::new(),
            arena: RefCell::new(SpellWorkArena::new()),
        })
    }

    /// Rebuilds the in-RAM `StemTrie` and invalidates the bounded candidate morph cache when user
    /// words are added/removed in `DynamicStemTrie`.
    pub fn rebuild_stems(&mut self, graph: &LexiconGraph<'a>, dynamic: Option<&DynamicStemTrie>) {
        let ending_root_mask = self.ending_trie.rem_mask[0];
        self.stem_trie = FastBfsTrie::build_stems(graph, dynamic, ending_root_mask);
        self.arena.borrow_mut().clear_morph_cache();
    }

    #[inline]
    pub fn ending_trie(&self) -> &EndingTrieView<'a> {
        &self.ending_view
    }

    /// Exact port of `TurkishSpellChecker.check(String input)`.
    pub fn check(
        &self,
        input: &str,
        morph: &TurkishMorphology<'a>,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> bool {
        let wa = morph.analyze_word(input, dynamic, scratch);
        let case_type = WordAnalysisSurfaceFormatter::guess_case(input);
        let apostrophe = get_apostrophe(input);
        for analysis in &wa.analyses {
            if analysis.is_unknown() {
                continue;
            }
            if WordAnalysisSurfaceFormatter::can_be_formatted(analysis, &morph.graph, case_type)
                && WordAnalysisSurfaceFormatter::format_to_case(
                    analysis,
                    &morph.graph,
                    case_type,
                    apostrophe,
                ) == input
            {
                return true;
            }
        }
        false
    }

    /// Exact port of `CompactStemEndingSpellChecker.suggestScored(String word)`.
    pub fn suggest_scored(
        &self,
        word: &str,
        morph: &TurkishMorphology<'a>,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Vec<ScoredCandidate> {
        let stripped = strip_standard_apostrophes(word);
        let normalized = TurkishAlphabet::normalize(&stripped);
        if normalized.is_empty() {
            return Vec::new();
        }
        let mut input_stack = [0u16; 64];
        let mut input_vec = Vec::new();
        let input_u16: &[u16] = if normalized.len() <= 64 {
            let mut n = 0usize;
            for u in normalized.encode_utf16() {
                input_stack[n] = u;
                n += 1;
            }
            &input_stack[..n]
        } else {
            input_vec.extend(normalized.encode_utf16());
            &input_vec
        };

        let mut arena = self.arena.borrow_mut();
        arena.reset_for_word();
        {
            let mut ctx = SearchCtx {
                input: input_u16,
                stem_buf: [0u16; 128],
                ending_buf: [0u16; 64],
                arena: &mut arena,
            };
            self.search_stem(0, 0, 0, 0, 0.0, 0, &mut ctx);
        }

        let SpellWorkArena {
            ref mut entries,
            ref u16_pool,
            ref mut utf8_buf,
            ref mut morph_cache,
            ..
        } = *arena;

        let raw_cmp = |a: &RawEntry, b: &RawEntry| {
            java_float_cmp(a.raw_rank_score(), b.raw_rank_score())
                .then_with(|| a.u16_slice(u16_pool).cmp(b.u16_slice(u16_pool)))
        };
        if entries.len() > 120 {
            entries.select_nth_unstable_by(119, raw_cmp);
            entries.truncate(120);
        }
        entries.sort_unstable_by(raw_cmp);

        Self::format_and_filter_candidates(
            word,
            entries,
            u16_pool,
            utf8_buf,
            morph_cache,
            morph,
            dynamic,
            scratch,
        )
    }

    /// Ranks `suggest_scored` candidates with `CleanWordLmView` (unigram, bigram, or trigram context).
    #[allow(clippy::too_many_arguments)]
    pub fn suggest_ranked(
        &self,
        word: &str,
        prev_lower: Option<&str>,
        next_lower: Option<&str>,
        lm: &CleanWordLmView<'_>,
        morph: &TurkishMorphology<'a>,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Vec<String> {
        let cands = self.suggest_scored(word, morph, dynamic, scratch);
        if cands.is_empty() {
            return Vec::new();
        }
        let input_is_lower = WordAnalysisSurfaceFormatter::guess_case(word) == CaseType::LowerCase;
        let mut scored: Vec<(f32, usize, String)> = Vec::with_capacity(cands.len());
        for (idx, c) in cands.into_iter().enumerate() {
            let low = TurkishAlphabet::to_lower_tr(&c.surface);
            let mut lm_lp = if prev_lower.is_some() {
                lm.bigram_log_prob(prev_lower, Some(&low))
            } else {
                lm.unigram_log_prob(Some(&low))
            };
            if let Some(nxt) = next_lower {
                lm_lp += lm.bigram_log_prob(Some(&low), Some(nxt));
            }
            let case_bonus = if input_is_lower && c.surface == low {
                0.15
            } else {
                0.0
            };
            let penalty = 2.4 * c.edit_cost + 0.65 * (c.diacritic_diffs as f32);
            scored.push((lm_lp + case_bonus - penalty, idx, c.surface));
        }
        scored.sort_by(|a, b| java_float_cmp(b.0, a.0).then_with(|| a.1.cmp(&b.1)));
        scored.into_iter().map(|(_, _, s)| s).collect()
    }

    #[allow(clippy::too_many_arguments)]
    fn format_and_filter_candidates(
        word: &str,
        raw_entries: &[RawEntry],
        u16_pool: &[u16],
        utf8_buf: &mut String,
        morph_cache: &mut [MorphCacheSlot],
        morph: &TurkishMorphology<'a>,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Vec<ScoredCandidate> {
        let mut case_type = WordAnalysisSurfaceFormatter::guess_case(word);
        if case_type == CaseType::MixedCase || case_type == CaseType::LowerCase {
            case_type = CaseType::DefaultCase;
        }
        let apostrophe = get_apostrophe(word);

        let mut valid_list: Vec<ScoredCandidate> = Vec::with_capacity(16);
        let mut best_valid_score = f32::INFINITY;
        let analyzer = morph.analyzer();
        let use_cache = dynamic.is_none();

        for rc in raw_entries {
            let rc_score = rc.raw_rank_score();
            if valid_list.len() >= 18 && rc_score > 0.85 && rc_score > best_valid_score + 0.50 {
                break;
            }
            utf8_buf.clear();
            let u16_s = rc.u16_slice(u16_pool);
            let mut is_pure_lower_letters = !u16_s.is_empty();
            let mut word_hash: u64 = 0xcbf2_9ce4_8422_2325;
            for ch in char::decode_utf16(u16_s.iter().copied()) {
                let c = ch.unwrap_or('\u{FFFD}');
                if !(c.is_ascii_lowercase() || matches!(c, 'ç' | 'ğ' | 'ı' | 'ö' | 'ş' | 'ü'))
                {
                    is_pure_lower_letters = false;
                }
                word_hash = (word_hash ^ (c as u64)).wrapping_mul(0x0100_0000_01b3);
                utf8_buf.push(c);
            }
            word_hash ^= word_hash >> 32;
            if word_hash == 0 {
                word_hash = 1;
            }

            let cache_idx = (word_hash as usize) & MORPH_CACHE_MASK;
            let uncached_analyses: Vec<SingleAnalysis>;
            let analyses: &[SingleAnalysis] = if use_cache
                && morph_cache[cache_idx].hash == word_hash
                && morph_cache[cache_idx].key == *utf8_buf
            {
                &morph_cache[cache_idx].analyses
            } else {
                let computed = if is_pure_lower_letters {
                    let mut res = analyzer.analyze_with_search_input(
                        utf8_buf, utf8_buf, false, dynamic, &[], scratch,
                    );
                    if res.len() == 1 && res[0].is_unknown() {
                        res.clear();
                    }
                    res
                } else {
                    morph.analyze_word(utf8_buf, dynamic, scratch).analyses
                };
                if use_cache && utf8_buf.len() <= 40 {
                    let slot = &mut morph_cache[cache_idx];
                    slot.hash = word_hash;
                    slot.key.clear();
                    slot.key.push_str(utf8_buf);
                    slot.analyses = computed;
                    &slot.analyses
                } else {
                    uncached_analyses = computed;
                    &uncached_analyses
                }
            };

            for analysis in analyses {
                if analysis.is_unknown() {
                    continue;
                }
                let formatted = WordAnalysisSurfaceFormatter::format_to_case(
                    analysis,
                    &morph.graph,
                    case_type,
                    apostrophe,
                );
                if rc_score < best_valid_score {
                    best_valid_score = rc_score;
                }
                let rc_dist = rc.total_distance_cost();
                if let Some(ex) = valid_list.iter_mut().find(|x| x.surface == formatted) {
                    if rc_dist < ex.total_distance_cost() {
                        ex.edit_cost = rc.edit_cost;
                        ex.diacritic_diffs = rc.diacritic_diffs;
                    }
                } else {
                    valid_list.push(ScoredCandidate {
                        surface: formatted,
                        edit_cost: rc.edit_cost,
                        diacritic_diffs: rc.diacritic_diffs,
                    });
                }
            }
        }
        valid_list
    }

    #[allow(clippy::too_many_arguments)]
    fn search_stem(
        &self,
        node: usize,
        stem_len: usize,
        pos: usize,
        edits: i32,
        cost: f32,
        diacritic_diffs: u32,
        ctx: &mut SearchCtx<'_, '_>,
    ) {
        let len = ctx.input.len();
        let rem = len - pos;
        let mask = self.stem_trie.rem_mask[node];
        if edits >= 1 {
            if !rem_matches_exact(mask, rem) {
                return;
            }
        } else if !rem_matches_budget1(mask, rem) {
            return;
        }

        let f = self.stem_trie.flags[node];
        if pos == len && (f & FLAG_CAN_TERMINATE) != 0 {
            ctx.record(stem_len, 0, cost, diacritic_diffs);
        }
        if (f & FLAG_HAS_STEM) != 0 {
            self.search_ending(f, stem_len, 0, 0, pos, edits, cost, diacritic_diffs, ctx);
        }

        if edits >= 1 {
            if pos < len {
                let ch = ctx.input[pos];
                let mut m_buf = [0u16; 3];
                for &mc in match_or_self(ch, &mut m_buf) {
                    if let Some(child) = self.stem_trie.find_child(node, mc) {
                        if stem_len < ctx.stem_buf.len() {
                            ctx.stem_buf[stem_len] = mc;
                            self.search_stem(
                                child,
                                stem_len + 1,
                                pos + 1,
                                1,
                                cost,
                                diacritic_diffs + u32::from(mc != ch),
                                ctx,
                            );
                        }
                    }
                }
            }
            return;
        }

        let edges = self.stem_trie.edges_range(node);
        if pos < len {
            let ch = ctx.input[pos];
            let rem_after_sub = len - (pos + 1);
            for e in edges.clone() {
                let ec = self.stem_trie.edge_chars[e];
                let child = e + 1;
                if stem_len >= ctx.stem_buf.len() {
                    continue;
                }
                ctx.stem_buf[stem_len] = ec;
                if ec == ch {
                    self.search_stem(child, stem_len + 1, pos + 1, 0, cost, diacritic_diffs, ctx);
                } else if is_diacritic_match(ch, ec) {
                    self.search_stem(
                        child,
                        stem_len + 1,
                        pos + 1,
                        0,
                        cost,
                        diacritic_diffs + 1,
                        ctx,
                    );
                } else if rem_matches_exact(self.stem_trie.rem_mask[child], rem_after_sub) {
                    self.search_stem(
                        child,
                        stem_len + 1,
                        pos + 1,
                        1,
                        cost + self.substitution_cost(ch, ec),
                        diacritic_diffs,
                        ctx,
                    );
                }
            }
            if rem_matches_exact(mask, rem_after_sub) {
                self.search_stem(
                    node,
                    stem_len,
                    pos + 1,
                    1,
                    cost + deletion_from_input_cost(ctx.input, pos),
                    diacritic_diffs,
                    ctx,
                );
            }
            if len > 2 && pos + 1 < len && ch != ctx.input[pos + 1] {
                self.explore_transposition_in_stem(
                    node,
                    stem_len,
                    ch,
                    ctx.input[pos + 1],
                    pos,
                    cost,
                    diacritic_diffs,
                    ctx,
                );
            }
        }

        for e in edges {
            let child = e + 1;
            if !rem_matches_exact(self.stem_trie.rem_mask[child], rem) {
                continue;
            }
            let ec = self.stem_trie.edge_chars[e];
            if stem_len < ctx.stem_buf.len() {
                ctx.stem_buf[stem_len] = ec;
                self.search_stem(
                    child,
                    stem_len + 1,
                    pos,
                    1,
                    cost + omitted_char_cost(ec),
                    diacritic_diffs,
                    ctx,
                );
            }
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn explore_transposition_in_stem(
        &self,
        node: usize,
        stem_len: usize,
        c0: u16,
        c1: u16,
        pos: usize,
        cost: f32,
        diacritic_diffs: u32,
        ctx: &mut SearchCtx<'_, '_>,
    ) {
        if stem_len + 1 >= ctx.stem_buf.len() {
            return;
        }
        let mut buf1 = [0u16; 3];
        let mut buf0 = [0u16; 3];
        let a1 = match_or_self(c1, &mut buf1);
        let a0 = match_or_self(c0, &mut buf0);
        for &x1 in a1 {
            let d1 = u32::from(x1 != c1);
            if let Some(mid) = self.stem_trie.find_child(node, x1) {
                ctx.stem_buf[stem_len] = x1;
                for &x0 in a0 {
                    let d0 = u32::from(x0 != c0);
                    if let Some(dst) = self.stem_trie.find_child(mid, x0) {
                        ctx.stem_buf[stem_len + 1] = x0;
                        self.search_stem(
                            dst,
                            stem_len + 2,
                            pos + 2,
                            1,
                            cost + 0.70,
                            diacritic_diffs + d1 + d0,
                            ctx,
                        );
                    }
                    let mf = self.stem_trie.flags[mid];
                    if (mf & FLAG_HAS_STEM) != 0 {
                        self.step_ending_exact(
                            mf,
                            stem_len + 1,
                            0,
                            0,
                            x0,
                            d1 + d0,
                            pos + 2,
                            1,
                            cost + 0.70,
                            diacritic_diffs,
                            ctx,
                        );
                    }
                }
            }
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn step_ending_exact(
        &self,
        stem_flags: u8,
        stem_len: usize,
        ending_node: usize,
        ending_len: usize,
        ec: u16,
        extra_diacritic: u32,
        next_pos: usize,
        edits: i32,
        cost: f32,
        diacritic_diffs: u32,
        ctx: &mut SearchCtx<'_, '_>,
    ) {
        if ending_node == 0 && !is_first_ending_char_compatible(ec, stem_flags) {
            return;
        }
        if ending_len >= ctx.ending_buf.len() {
            return;
        }
        if let Some(child) = self.ending_trie.find_child(ending_node, ec) {
            if edits >= 1
                && !rem_matches_exact(self.ending_trie.rem_mask[child], ctx.input.len() - next_pos)
            {
                return;
            }
            ctx.ending_buf[ending_len] = ec;
            self.search_ending(
                stem_flags,
                stem_len,
                child,
                ending_len + 1,
                next_pos,
                edits,
                cost,
                diacritic_diffs + extra_diacritic,
                ctx,
            );
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn search_ending(
        &self,
        stem_flags: u8,
        stem_len: usize,
        node: usize,
        ending_len: usize,
        pos: usize,
        edits: i32,
        cost: f32,
        diacritic_diffs: u32,
        ctx: &mut SearchCtx<'_, '_>,
    ) {
        if node == 0
            && (stem_flags & (FLAG_ALLOWS_VOWEL_ENDING | FLAG_ALLOWS_CONSONANT_ENDING)) == 0
        {
            return;
        }
        let len = ctx.input.len();
        let rem = len - pos;
        let mask = self.ending_trie.rem_mask[node];
        if edits >= 1 {
            if !rem_matches_exact(mask, rem) {
                return;
            }
        } else if !rem_matches_budget1(mask, rem) {
            return;
        }

        if pos == len
            && (self.ending_trie.flags[node] & FLAG_CAN_TERMINATE) != 0
            && is_ending_harmonious_with_stem(stem_flags, &ctx.ending_buf[..ending_len])
        {
            ctx.record(stem_len, ending_len, cost, diacritic_diffs);
        }

        if edits >= 1 {
            if pos < len {
                let ch = ctx.input[pos];
                let mut m_buf = [0u16; 3];
                for &mc in match_or_self(ch, &mut m_buf) {
                    self.step_ending_exact(
                        stem_flags,
                        stem_len,
                        node,
                        ending_len,
                        mc,
                        u32::from(mc != ch),
                        pos + 1,
                        1,
                        cost,
                        diacritic_diffs,
                        ctx,
                    );
                }
            }
            return;
        }

        let edges = self.ending_trie.edges_range(node);
        if pos < len {
            let ch = ctx.input[pos];
            let rem_after_sub = len - (pos + 1);
            for e in edges.clone() {
                let ec = self.ending_trie.edge_chars[e];
                if node == 0 && !is_first_ending_char_compatible(ec, stem_flags) {
                    continue;
                }
                if ending_len >= ctx.ending_buf.len() {
                    continue;
                }
                let child = e + 1;
                ctx.ending_buf[ending_len] = ec;
                if ec == ch {
                    self.search_ending(
                        stem_flags,
                        stem_len,
                        child,
                        ending_len + 1,
                        pos + 1,
                        0,
                        cost,
                        diacritic_diffs,
                        ctx,
                    );
                } else if is_diacritic_match(ch, ec) {
                    self.search_ending(
                        stem_flags,
                        stem_len,
                        child,
                        ending_len + 1,
                        pos + 1,
                        0,
                        cost,
                        diacritic_diffs + 1,
                        ctx,
                    );
                } else if rem_matches_exact(self.ending_trie.rem_mask[child], rem_after_sub) {
                    self.search_ending(
                        stem_flags,
                        stem_len,
                        child,
                        ending_len + 1,
                        pos + 1,
                        1,
                        cost + self.substitution_cost(ch, ec),
                        diacritic_diffs,
                        ctx,
                    );
                }
            }
            if node != 0 && rem_matches_exact(mask, rem_after_sub) {
                self.search_ending(
                    stem_flags,
                    stem_len,
                    node,
                    ending_len,
                    pos + 1,
                    1,
                    cost + deletion_from_input_cost(ctx.input, pos),
                    diacritic_diffs,
                    ctx,
                );
            }
            if len > 2 && pos + 1 < len && ch != ctx.input[pos + 1] {
                self.explore_transposition_in_ending(
                    node,
                    node == 0,
                    stem_flags,
                    stem_len,
                    ending_len,
                    ch,
                    ctx.input[pos + 1],
                    pos,
                    cost,
                    diacritic_diffs,
                    ctx,
                );
            }
        }

        for e in edges {
            let child = e + 1;
            if !rem_matches_exact(self.ending_trie.rem_mask[child], rem) {
                continue;
            }
            let ec = self.ending_trie.edge_chars[e];
            if node == 0 && !is_first_ending_char_compatible(ec, stem_flags) {
                continue;
            }
            if ending_len < ctx.ending_buf.len() {
                ctx.ending_buf[ending_len] = ec;
                self.search_ending(
                    stem_flags,
                    stem_len,
                    child,
                    ending_len + 1,
                    pos,
                    1,
                    cost + omitted_char_cost(ec),
                    diacritic_diffs,
                    ctx,
                );
            }
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn explore_transposition_in_ending(
        &self,
        node: usize,
        at_root: bool,
        stem_flags: u8,
        stem_len: usize,
        ending_len: usize,
        c0: u16,
        c1: u16,
        pos: usize,
        cost: f32,
        diacritic_diffs: u32,
        ctx: &mut SearchCtx<'_, '_>,
    ) {
        if ending_len + 1 >= ctx.ending_buf.len() {
            return;
        }
        let rem_after = ctx.input.len() - (pos + 2);
        let mut buf1 = [0u16; 3];
        let mut buf0 = [0u16; 3];
        let a1 = match_or_self(c1, &mut buf1);
        let a0 = match_or_self(c0, &mut buf0);
        for &x1 in a1 {
            if at_root && !is_first_ending_char_compatible(x1, stem_flags) {
                continue;
            }
            let d1 = u32::from(x1 != c1);
            if let Some(mid) = self.ending_trie.find_child(node, x1) {
                ctx.ending_buf[ending_len] = x1;
                for &x0 in a0 {
                    let d0 = u32::from(x0 != c0);
                    if let Some(dst) = self.ending_trie.find_child(mid, x0) {
                        if !rem_matches_exact(self.ending_trie.rem_mask[dst], rem_after) {
                            continue;
                        }
                        ctx.ending_buf[ending_len + 1] = x0;
                        self.search_ending(
                            stem_flags,
                            stem_len,
                            dst,
                            ending_len + 2,
                            pos + 2,
                            1,
                            cost + 0.70,
                            diacritic_diffs + d1 + d0,
                            ctx,
                        );
                    }
                }
            }
        }
    }

    #[inline(always)]
    fn substitution_cost(&self, input_char: u16, target_char: u16) -> f32 {
        if self.near_table.is_near(input_char, target_char) {
            0.55
        } else {
            1.0
        }
    }
}

#[derive(Clone, Copy)]
struct RawEntry {
    pool_off: u32,
    u16_len: u16,
    diacritic_diffs: u32,
    edit_cost: f32,
}

impl RawEntry {
    #[inline]
    fn raw_rank_score(&self) -> f32 {
        self.edit_cost + 0.20 * (self.diacritic_diffs as f32)
    }

    #[inline]
    fn total_distance_cost(&self) -> f32 {
        self.edit_cost + 0.28 * (self.diacritic_diffs as f32)
    }

    #[inline]
    fn u16_slice<'p>(&self, pool: &'p [u16]) -> &'p [u16] {
        let s = self.pool_off as usize;
        &pool[s..s + (self.u16_len as usize)]
    }
}

struct SearchCtx<'i, 'a> {
    input: &'i [u16],
    stem_buf: [u16; 128],
    ending_buf: [u16; 64],
    arena: &'a mut SpellWorkArena,
}

impl SearchCtx<'_, '_> {
    #[inline]
    fn record(&mut self, stem_len: usize, ending_len: usize, edit_cost: f32, diacritic_diffs: u32) {
        self.arena.record(
            &self.stem_buf[..stem_len],
            &self.ending_buf[..ending_len],
            edit_cost,
            diacritic_diffs,
        );
    }
}

#[inline]
fn is_vowel_u16(c: u16) -> bool {
    char::from_u32(c as u32).is_some_and(TurkishAlphabet::is_vowel)
}

#[inline]
const fn is_front_vowel_u16(c: u16) -> bool {
    matches!(c, 0x0065 | 0x0069 | 0x00F6 | 0x00FC | 0x00EE) // e, i, ö, ü, î
}

#[inline]
const fn is_back_vowel_u16(c: u16) -> bool {
    matches!(c, 0x0061 | 0x0131 | 0x006F | 0x0075 | 0x00E2 | 0x00FB) // a, ı, o, u, â, û
}

#[inline]
fn is_first_ending_char_compatible(ec: u16, stem_flags: u8) -> bool {
    if is_vowel_u16(ec) {
        if (stem_flags & FLAG_ALLOWS_VOWEL_ENDING) == 0 {
            return false;
        }
        if is_front_vowel_u16(ec) {
            return (stem_flags & FLAG_FRONT_HARMONY) != 0;
        }
        if is_back_vowel_u16(ec) {
            return (stem_flags & FLAG_BACK_HARMONY) != 0;
        }
        return true;
    }
    (stem_flags & FLAG_ALLOWS_CONSONANT_ENDING) != 0
        && (ec != (b'd' as u16) || (stem_flags & FLAG_ALLOWS_VOICED_D) != 0)
}

#[inline]
fn is_ending_harmonious_with_stem(stem_flags: u8, ending: &[u16]) -> bool {
    for &prefix in INVARIANT_HARMONY_PREFIXES {
        if ending.starts_with(prefix) {
            return true;
        }
    }
    for &c in ending {
        if is_vowel_u16(c) {
            if is_front_vowel_u16(c) {
                return (stem_flags & FLAG_FRONT_HARMONY) != 0;
            }
            if is_back_vowel_u16(c) {
                return (stem_flags & FLAG_BACK_HARMONY) != 0;
            }
            return true;
        }
    }
    true
}

#[inline]
fn match_or_self(c: u16, buf: &mut [u16; 3]) -> &[u16] {
    match c {
        0x0061 => {
            *buf = [0x0061, 0x00E2, 0];
            &buf[..2]
        }
        0x0063 => {
            *buf = [0x0063, 0x00E7, 0];
            &buf[..2]
        }
        0x0067 => {
            *buf = [0x0067, 0x011F, 0];
            &buf[..2]
        }
        0x0131 => {
            *buf = [0x0131, 0x0069, 0];
            &buf[..2]
        }
        0x0069 => {
            *buf = [0x0069, 0x0131, 0x00EE];
            &buf[..3]
        }
        0x006F => {
            *buf = [0x006F, 0x00F6, 0];
            &buf[..2]
        }
        0x0073 => {
            *buf = [0x0073, 0x015F, 0];
            &buf[..2]
        }
        0x0075 => {
            *buf = [0x0075, 0x00FC, 0x00FB];
            &buf[..3]
        }
        0x0041 => {
            *buf = [0x0041, 0x00C2, 0];
            &buf[..2]
        }
        0x0043 => {
            *buf = [0x0043, 0x00C7, 0];
            &buf[..2]
        }
        0x0047 => {
            *buf = [0x0047, 0x011E, 0];
            &buf[..2]
        }
        0x0049 => {
            *buf = [0x0049, 0x0130, 0];
            &buf[..2]
        }
        0x0130 => {
            *buf = [0x0130, 0x0049, 0x00CE];
            &buf[..3]
        }
        0x004F => {
            *buf = [0x004F, 0x00D6, 0];
            &buf[..2]
        }
        0x0053 => {
            *buf = [0x0053, 0x015E, 0];
            &buf[..2]
        }
        0x0055 => {
            *buf = [0x0055, 0x00DC, 0x00DB];
            &buf[..3]
        }
        _ => {
            buf[0] = c;
            &buf[..1]
        }
    }
}

#[inline]
fn is_diacritic_match(input_char: u16, edge_char: u16) -> bool {
    if (input_char as usize) >= MAX_ASCII_TABLE {
        return false;
    }
    match input_char {
        0x0061 => matches!(edge_char, 0x0061 | 0x00E2),
        0x0063 => matches!(edge_char, 0x0063 | 0x00E7),
        0x0067 => matches!(edge_char, 0x0067 | 0x011F),
        0x0131 => matches!(edge_char, 0x0131 | 0x0069),
        0x0069 => matches!(edge_char, 0x0069 | 0x0131 | 0x00EE),
        0x006F => matches!(edge_char, 0x006F | 0x00F6),
        0x0073 => matches!(edge_char, 0x0073 | 0x015F),
        0x0075 => matches!(edge_char, 0x0075 | 0x00FC | 0x00FB),
        0x0041 => matches!(edge_char, 0x0041 | 0x00C2),
        0x0043 => matches!(edge_char, 0x0043 | 0x00C7),
        0x0047 => matches!(edge_char, 0x0047 | 0x011E),
        0x0049 => matches!(edge_char, 0x0049 | 0x0130),
        0x0130 => matches!(edge_char, 0x0130 | 0x0049 | 0x00CE),
        0x004F => matches!(edge_char, 0x004F | 0x00D6),
        0x0053 => matches!(edge_char, 0x0053 | 0x015E),
        0x0055 => matches!(edge_char, 0x0055 | 0x00DC | 0x00DB),
        _ => {
            input_char == edge_char
                && char::from_u32(input_char as u32).is_some_and(|ch| {
                    ch.is_ascii_alphabetic()
                        || TurkishAlphabet::is_turkish_specific(ch)
                        || matches!(ch, '+' | '.' | ',' | '\'' | '-')
                })
        }
    }
}

#[inline]
fn omitted_char_cost(omitted_target_char: u16) -> f32 {
    if is_vowel_u16(omitted_target_char)
        || omitted_target_char == 0x011F
        || omitted_target_char == (b'y' as u16)
        || omitted_target_char == (b'h' as u16)
    {
        0.75
    } else {
        1.0
    }
}

#[inline]
fn deletion_from_input_cost(input: &[u16], pos: usize) -> f32 {
    let c = input[pos];
    if (pos > 0 && input[pos - 1] == c) || (pos + 1 < input.len() && input[pos + 1] == c) {
        0.65
    } else {
        1.0
    }
}

fn strip_standard_apostrophes(s: &str) -> String {
    if !s.contains('\'') && !s.contains('’') {
        return String::from(s);
    }
    s.chars().filter(|&c| c != '\'' && c != '’').collect()
}

fn get_apostrophe(input: &str) -> Option<&'static str> {
    if input.find('’').is_some_and(|idx| idx > 0) {
        return Some("’");
    }
    if input.find('\'').is_some_and(|idx| idx > 0) {
        return Some("'");
    }
    None
}

#[inline]
fn java_float_cmp(a: f32, b: f32) -> Ordering {
    if a < b {
        Ordering::Less
    } else if a > b {
        Ordering::Greater
    } else {
        let ab = a.to_bits() as i32;
        let bb = b.to_bits() as i32;
        if ab == bb || (a.is_nan() && b.is_nan()) {
            Ordering::Equal
        } else if a.is_nan() {
            Ordering::Greater
        } else if b.is_nan() {
            Ordering::Less
        } else {
            ab.cmp(&bb)
        }
    }
}
