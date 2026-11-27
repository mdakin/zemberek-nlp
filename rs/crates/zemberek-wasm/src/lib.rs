use std::alloc::{alloc, alloc_zeroed, dealloc, Layout};
use std::cell::UnsafeCell;
use zemberek_core::{SecondaryPos, TurkishAlphabet};
use zemberek_deasc::{CircumflexMode, LatticeDeasciifier};
use zemberek_disambig::{DecodeMode, DisambigScratch, FastPerceptronDisambiguator};
use zemberek_morph::{
    AnalyzerScratch, DynamicStemTrie, LexiconGraph, MorphologyConfig, TurkishMorphology,
    WordAnalysisSurfaceFormatter,
};
use zemberek_norm::{IssueKind, SpanIssue, SpanLatticePerceptronNormalizer};
use zemberek_pack::clean_lm::CleanWordLmView;
use zemberek_pack::deasc::DeascModelView;
use zemberek_pack::dict::LexiconView;
use zemberek_pack::ending_trie::EndingTrieView;
use zemberek_pack::mphf::{MphfI16WeightTableView, QuantizedWeightTableView};
use zemberek_pack::norm::{NormKnowledgeView, NormPerceptronView};
use zemberek_pack::{
    ZmbkPack, SECT_AMBIG_QWT, SECT_CLEAN_LM, SECT_DEASC, SECT_ENDING_TRIE, SECT_LEXICON,
    SECT_MORPH_FST, SECT_NORM_KNOWLEDGE, SECT_NORM_PERCEPTRON, SECT_QWT_MPHF, ZMBK_ALIGNMENT,
};
use zemberek_spell::CompactStemEndingSpellChecker;
use zemberek_tok::{span_tokenize, CasingShape, TurkishSentenceExtractor};

pub const INPUT_BUF_CAP: usize = 64 * 1024;
pub const OUTPUT_BUF_CAP: usize = 128 * 1024;

pub const CAP_MORPH: u32 = 1 << 0;
pub const CAP_SPELL: u32 = 1 << 1;
pub const CAP_DISAMBIG: u32 = 1 << 2;
pub const CAP_DEASC: u32 = 1 << 3;
pub const CAP_NORM_STUDIO: u32 = 1 << 4;

pub const CHECK_FLAG_FORCE_LITE: u32 = 1 << 0;
pub const CHECK_FLAG_BYPASS_CACHE: u32 = 1 << 1;

const LITE_WORD_CACHE_SIZE: usize = 16_384;
const LITE_WORD_CACHE_MASK: usize = LITE_WORD_CACHE_SIZE - 1;
const PARA_CACHE_SLOTS: usize = 128;
const PARA_CACHE_MASK: usize = PARA_CACHE_SLOTS - 1;
const MAX_CACHED_PARA_OUT_BYTES: usize = 2048;

struct SharedIoBuffers {
    input_ptr: UnsafeCell<*mut u8>,
    output_ptr: UnsafeCell<*mut u8>,
}

// Single-threaded Dedicated Web Worker per Wasm instance.
unsafe impl Sync for SharedIoBuffers {}

static IO_BUFS: SharedIoBuffers = SharedIoBuffers {
    input_ptr: UnsafeCell::new(core::ptr::null_mut()),
    output_ptr: UnsafeCell::new(core::ptr::null_mut()),
};

#[inline]
unsafe fn ensure_io_ptrs() -> (*mut u8, *mut u8) {
    let mut in_p = *IO_BUFS.input_ptr.get();
    if in_p.is_null() {
        let layout = Layout::from_size_align_unchecked(INPUT_BUF_CAP, ZMBK_ALIGNMENT);
        in_p = alloc_zeroed(layout);
        *IO_BUFS.input_ptr.get() = in_p;
    }
    let mut out_p = *IO_BUFS.output_ptr.get();
    if out_p.is_null() {
        let layout = Layout::from_size_align_unchecked(OUTPUT_BUF_CAP, ZMBK_ALIGNMENT);
        out_p = alloc_zeroed(layout);
        *IO_BUFS.output_ptr.get() = out_p;
    }
    (in_p, out_p)
}

#[derive(Clone, Copy)]
struct LiteWordSlot {
    hash: u64,
    valid: bool,
}

#[derive(Clone, Default)]
struct ParaCacheEntry {
    hash: u64,
    flags: u32,
    in_len: u32,
    out_len: u32,
    payload: Vec<u8>,
}

/// Strictly bounded caches (`<= 384 KiB` ceiling) guaranteeing `0 B` linear-memory growth
/// across arbitrarily long browser sessions (`100,000+` sentences).
struct BoundedWorkerCaches {
    lite_words: Vec<LiteWordSlot>,
    para_ring: Vec<ParaCacheEntry>,
}

impl BoundedWorkerCaches {
    fn new() -> Self {
        Self {
            lite_words: vec![
                LiteWordSlot {
                    hash: 0,
                    valid: false
                };
                LITE_WORD_CACHE_SIZE
            ],
            para_ring: (0..PARA_CACHE_SLOTS)
                .map(|_| ParaCacheEntry::default())
                .collect(),
        }
    }

    fn invalidate_all(&mut self) {
        for w in &mut self.lite_words {
            w.hash = 0;
        }
        for p in &mut self.para_ring {
            p.hash = 0;
            p.payload.clear();
        }
    }
}

struct FullPipeline<'a> {
    pack: ZmbkPack<'a>,
    graph: LexiconGraph<'a>,
    morph_strict: TurkishMorphology<'a>,
    morph_informal: TurkishMorphology<'a>,
    clean_lm: Option<CleanWordLmView<'a>>,
    has_ending_trie: bool,
    has_disambig: bool,
    has_deasc: bool,
    has_norm: bool,
    spell: Option<CompactStemEndingSpellChecker<'a>>,
    spell_stems_dirty: bool,
    disambig: Option<(FastPerceptronDisambiguator<'a>, DisambigScratch)>,
    deasc: Option<LatticeDeasciifier<'a>>,
    norm: Option<SpanLatticePerceptronNormalizer<'a>>,
    dynamic_trie: DynamicStemTrie,
    scratch: AnalyzerScratch,
    caches: BoundedWorkerCaches,
    capabilities: u32,
}

impl<'a> FullPipeline<'a> {
    fn ensure_spell_ready(&mut self) {
        if !self.has_ending_trie {
            return;
        }
        let dyn_opt = if self.dynamic_trie.items().is_empty() {
            None
        } else {
            Some(&self.dynamic_trie)
        };
        if self.spell.is_none() {
            if let Ok(sp) =
                CompactStemEndingSpellChecker::from_pack(&self.pack, &self.graph, dyn_opt)
            {
                self.spell = Some(sp);
                self.spell_stems_dirty = false;
            }
        } else if self.spell_stems_dirty {
            if let Some(ref mut sp) = self.spell {
                sp.rebuild_stems(&self.graph, dyn_opt);
            }
            if let Some(ref mut nm) = self.norm {
                nm.rebuild_dynamic_caches(dyn_opt);
            }
            if let Some(ref dc) = self.deasc {
                dc.invalidate_cache();
            }
            self.spell_stems_dirty = false;
        }
    }

    fn ensure_norm_ready(&mut self) {
        if !self.has_norm {
            return;
        }
        let dyn_opt = if self.dynamic_trie.items().is_empty() {
            None
        } else {
            Some(&self.dynamic_trie)
        };
        if self.norm.is_none() {
            if let Ok(nm) = SpanLatticePerceptronNormalizer::from_pack(&self.pack, dyn_opt) {
                self.norm = Some(nm);
            }
        } else if self.spell_stems_dirty {
            if let Some(ref mut nm) = self.norm {
                nm.rebuild_dynamic_caches(dyn_opt);
            }
            if let Some(ref mut sp) = self.spell {
                sp.rebuild_stems(&self.graph, dyn_opt);
            }
            if let Some(ref dc) = self.deasc {
                dc.invalidate_cache();
            }
            self.spell_stems_dirty = false;
        }
    }

    fn ensure_deasc_ready(&mut self) {
        if !self.has_deasc {
            return;
        }
        if self.deasc.is_none() {
            if let Ok(dc) = LatticeDeasciifier::from_pack(&self.pack) {
                self.deasc = Some(dc);
            }
        } else if self.spell_stems_dirty {
            if let Some(ref dc) = self.deasc {
                dc.invalidate_cache();
            }
        }
    }

    fn ensure_disambig_ready(&mut self) {
        if !self.has_disambig || self.disambig.is_some() {
            return;
        }
        if let Ok(d) = FastPerceptronDisambiguator::from_pack(&self.pack) {
            self.disambig = Some((d, DisambigScratch::new()));
        }
    }
}

struct EngineSlot {
    last_pack_alloc: Option<(*mut u8, Layout)>,
    lexicon: Option<LexiconView<'static>>,
    qwt: Option<QuantizedWeightTableView<'static>>,
    pipeline: Option<Box<FullPipeline<'static>>>,
}

struct SingletonEngine(UnsafeCell<EngineSlot>);
unsafe impl Sync for SingletonEngine {}

static ENGINE: SingletonEngine = SingletonEngine(UnsafeCell::new(EngineSlot {
    last_pack_alloc: None,
    lexicon: None,
    qwt: None,
    pipeline: None,
}));

#[inline]
fn fnv1a64(bytes: &[u8]) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for &b in bytes {
        h ^= b as u64;
        h = h.wrapping_mul(0x0000_0100_0000_01b3);
    }
    h ^= h >> 32;
    if h == 0 {
        1
    } else {
        h
    }
}

/// Allocates a 64-byte-aligned buffer in Wasm linear memory to receive the `.zmbk` packfile
/// via chunked `ReadableStream` writes (avoiding any `2×` buffer spike).
/// Automatically frees any previously allocated `.zmbk` pack when switching `Lite` <-> `Studio`.
#[no_mangle]
pub extern "C" fn zmbk_alloc_pack(byte_len: usize) -> *mut u8 {
    if byte_len == 0 {
        return core::ptr::null_mut();
    }
    let Ok(layout) = Layout::from_size_align(byte_len, ZMBK_ALIGNMENT) else {
        return core::ptr::null_mut();
    };
    unsafe {
        let slot = &mut *ENGINE.0.get();
        // Drop active zero-copy views before releasing previous pack allocation
        slot.pipeline = None;
        slot.lexicon = None;
        slot.qwt = None;
        if let Some((old_ptr, old_layout)) = slot.last_pack_alloc.take() {
            dealloc(old_ptr, old_layout);
        }
        let ptr = alloc(layout);
        if !ptr.is_null() {
            slot.last_pack_alloc = Some((ptr, layout));
        }
        ptr
    }
}

#[no_mangle]
pub extern "C" fn zmbk_input_ptr() -> *mut u8 {
    unsafe { ensure_io_ptrs().0 }
}

#[no_mangle]
pub extern "C" fn zmbk_input_cap() -> u32 {
    INPUT_BUF_CAP as u32
}

#[no_mangle]
pub extern "C" fn zmbk_output_ptr() -> *const u8 {
    unsafe { ensure_io_ptrs().1 as *const u8 }
}

#[no_mangle]
pub extern "C" fn zmbk_output_cap() -> u32 {
    OUTPUT_BUF_CAP as u32
}

#[no_mangle]
pub extern "C" fn zmbk_capabilities() -> u32 {
    unsafe {
        (*ENGINE.0.get())
            .pipeline
            .as_ref()
            .map_or(0, |p| p.capabilities)
    }
}

/// Maps the `.zmbk` packfile at `(ptr, len)` in `O(sections)` zero-copy time.
/// Supports Step 0 mini packs (`LEXICON + QWT`), Core Lite packs (`LEX + TRIE + BRIDGE + FST + LITE_LM + ENDING`),
/// and full Studio packs (`+ AMBIG_QWT + DEASC + NORM_KNOWLEDGE + NORM_PERCEPTRON`).
///
/// # Safety
/// `ptr` must point to a valid 64-byte-aligned allocation of `len` bytes inside Wasm linear memory.
#[no_mangle]
pub unsafe extern "C" fn zmbk_init(ptr: *const u8, len: usize, verify_crc: u32) -> u32 {
    if ptr.is_null() || len == 0 {
        return 100;
    }
    let bytes: &'static [u8] = core::slice::from_raw_parts(ptr, len);
    let pack = match ZmbkPack::parse(bytes, verify_crc != 0) {
        Ok(p) => p,
        Err(e) => return (e as u32) + 1,
    };

    let (_, lex_bytes) = match pack.section_bytes(SECT_LEXICON) {
        Ok(s) => s,
        Err(e) => return (e as u32) + 1,
    };
    let lexicon = match LexiconView::from_section_bytes(lex_bytes) {
        Ok(v) => v,
        Err(e) => return (e as u32) + 1,
    };

    let qwt = match pack.section_bytes(SECT_QWT_MPHF) {
        Ok((_, qb)) => match QuantizedWeightTableView::from_section_bytes(qb) {
            Ok(v) => Some(v),
            Err(e) => return (e as u32) + 1,
        },
        Err(_) => None,
    };

    let slot = &mut *ENGINE.0.get();
    slot.lexicon = Some(lexicon);
    slot.qwt = qwt;
    slot.pipeline = None;

    // Initialize full morphological/spelling/deasc/normalization pipeline if `SECT_MORPH_FST` is present
    if pack.section_bytes(SECT_MORPH_FST).is_ok() {
        let graph = match LexiconGraph::from_pack(&pack) {
            Ok(g) => g,
            Err(e) => return (e as u32) + 1,
        };
        let morph_strict = match TurkishMorphology::from_pack(&pack, MorphologyConfig::default()) {
            Ok(m) => m,
            Err(e) => return (e as u32) + 1,
        };
        let morph_informal = match TurkishMorphology::from_pack(
            &pack,
            MorphologyConfig {
                use_informal: true,
                ignore_diacritics: true,
                use_unidentified_token_analyzer: true,
            },
        ) {
            Ok(m) => m,
            Err(e) => return (e as u32) + 1,
        };
        let mut caps = CAP_MORPH;

        let clean_lm = match pack.section_bytes(SECT_CLEAN_LM) {
            Ok((_, b)) => match CleanWordLmView::from_section_bytes(b) {
                Ok(v) => Some(v),
                Err(e) => return (e as u32) + 1,
            },
            Err(_) => None,
        };

        let has_ending_trie = match pack.section_bytes(SECT_ENDING_TRIE) {
            Ok((_, b)) => match EndingTrieView::from_section_bytes(b) {
                Ok(_) => {
                    caps |= CAP_SPELL;
                    true
                }
                Err(e) => return (e as u32) + 1,
            },
            Err(_) => false,
        };

        let has_disambig = match pack.section_bytes(SECT_AMBIG_QWT) {
            Ok((_, b)) => match MphfI16WeightTableView::from_section_bytes(b) {
                Ok(_) => {
                    caps |= CAP_DISAMBIG;
                    true
                }
                Err(e) => return (e as u32) + 1,
            },
            Err(_) => false,
        };

        let has_deasc = match pack.section_bytes(SECT_DEASC) {
            Ok((_, b)) => match DeascModelView::from_section_bytes(b) {
                Ok(_) => {
                    caps |= CAP_DEASC;
                    true
                }
                Err(e) => return (e as u32) + 1,
            },
            Err(_) => false,
        };

        let has_norm = match (
            pack.section_bytes(SECT_NORM_KNOWLEDGE),
            pack.section_bytes(SECT_NORM_PERCEPTRON),
        ) {
            (Ok((_, kb)), Ok((_, pb))) => {
                if let Err(e) = NormKnowledgeView::from_section_bytes(kb) {
                    return (e as u32) + 1;
                }
                if let Err(e) = NormPerceptronView::from_section_bytes(pb) {
                    return (e as u32) + 1;
                }
                caps |= CAP_NORM_STUDIO;
                true
            }
            _ => false,
        };

        let dynamic_trie = DynamicStemTrie::new(lexicon.len() as u32);
        slot.pipeline = Some(Box::new(FullPipeline {
            pack,
            graph,
            morph_strict,
            morph_informal,
            clean_lm,
            has_ending_trie,
            has_disambig,
            has_deasc,
            has_norm,
            spell: None,
            spell_stems_dirty: false,
            disambig: None,
            deasc: None,
            norm: None,
            dynamic_trie,
            scratch: AnalyzerScratch::default(),
            caches: BoundedWorkerCaches::new(),
            capabilities: caps,
        }));
    }

    0
}

#[inline]
unsafe fn read_input_utf8<'a>(in_len: u32) -> Result<&'a str, i32> {
    let n = in_len as usize;
    if n > INPUT_BUF_CAP {
        return Err(-2);
    }
    let (base, _) = ensure_io_ptrs();
    let slice = core::slice::from_raw_parts(base, n);
    core::str::from_utf8(slice).map_err(|_| -3)
}

#[inline]
unsafe fn write_output_bytes(src: &[u8]) -> i32 {
    if src.len() > OUTPUT_BUF_CAP {
        return -4;
    }
    let (_, base) = ensure_io_ptrs();
    let dst = core::slice::from_raw_parts_mut(base, src.len());
    dst.copy_from_slice(src);
    src.len() as i32
}

struct OutputWriter<'a> {
    buf: &'a mut [u8],
    pos: usize,
}

impl<'a> OutputWriter<'a> {
    fn new(buf: &'a mut [u8]) -> Self {
        Self { buf, pos: 0 }
    }

    #[inline]
    fn write_u8(&mut self, v: u8) -> bool {
        if self.pos + 1 > self.buf.len() {
            return false;
        }
        self.buf[self.pos] = v;
        self.pos += 1;
        true
    }

    #[inline]
    fn write_u16_le(&mut self, v: u16) -> bool {
        if self.pos + 2 > self.buf.len() {
            return false;
        }
        self.buf[self.pos..self.pos + 2].copy_from_slice(&v.to_le_bytes());
        self.pos += 2;
        true
    }

    #[inline]
    fn write_u32_le(&mut self, v: u32) -> bool {
        if self.pos + 4 > self.buf.len() {
            return false;
        }
        self.buf[self.pos..self.pos + 4].copy_from_slice(&v.to_le_bytes());
        self.pos += 4;
        true
    }

    #[inline]
    fn write_bytes(&mut self, b: &[u8]) -> bool {
        if self.pos + b.len() > self.buf.len() {
            return false;
        }
        self.buf[self.pos..self.pos + b.len()].copy_from_slice(b);
        self.pos += b.len();
        true
    }

    #[inline]
    fn write_len_prefixed_str(&mut self, s: &str) -> bool {
        let b = s.as_bytes();
        if b.len() > u16::MAX as usize {
            return false;
        }
        self.write_u16_le(b.len() as u16) && self.write_bytes(b)
    }
}

fn encode_issues_into(issues: &[SpanIssue], out_buf: &mut [u8]) -> Option<usize> {
    let mut w = OutputWriter::new(out_buf);
    if !w.write_u32_le(0) {
        return None;
    }
    let mut written_count = 0u32;
    for iss in issues {
        let kind_code: u8 = match iss.kind {
            IssueKind::Spelling => 1,
            IssueKind::CliticSplitMerge => 2,
            IssueKind::DeasciifyOrColloquial => 3,
        };
        let sug_count = iss.suggestions.len().min(5) as u8;
        if sug_count == 0 {
            continue;
        }
        let checkpoint = w.pos;
        let ok = w.write_u32_le(iss.utf16_start as u32)
            && w.write_u32_le(iss.utf16_end as u32)
            && w.write_u32_le(iss.utf8_start as u32)
            && w.write_u32_le(iss.utf8_end as u32)
            && w.write_u8(kind_code)
            && w.write_u8(sug_count)
            && w.write_u16_le(0)
            && iss
                .suggestions
                .iter()
                .take(sug_count as usize)
                .all(|s| w.write_len_prefixed_str(s));
        if !ok {
            w.pos = checkpoint;
            break;
        }
        written_count += 1;
    }
    w.buf[0..4].copy_from_slice(&written_count.to_le_bytes());
    Some(w.pos)
}

fn is_strict_valid_word(pipe: &mut FullPipeline<'static>, surface: &str, has_dyn: bool) -> bool {
    let wh = fnv1a64(surface.as_bytes());
    let slot_idx = (wh as usize) & LITE_WORD_CACHE_MASK;
    if !has_dyn && pipe.caches.lite_words[slot_idx].hash == wh {
        return pipe.caches.lite_words[slot_idx].valid;
    }
    let dyn_opt = if has_dyn {
        Some(&pipe.dynamic_trie)
    } else {
        None
    };
    let wa = pipe
        .morph_strict
        .analyze_word(surface, dyn_opt, &mut pipe.scratch);
    let case_type = WordAnalysisSurfaceFormatter::guess_case(surface);
    let apos = if surface.contains('’') {
        Some("’")
    } else if surface.contains('\'') {
        Some("'")
    } else {
        None
    };
    let ok = wa.analyses.iter().any(|a| {
        !a.is_unknown()
            && !a.is_runtime(&pipe.graph)
            && WordAnalysisSurfaceFormatter::can_be_formatted(a, &pipe.graph, case_type)
            && WordAnalysisSurfaceFormatter::format_to_case(a, &pipe.graph, case_type, apos)
                == surface
    });
    if !has_dyn {
        pipe.caches.lite_words[slot_idx] = LiteWordSlot { hash: wh, valid: ok };
    }
    ok
}

fn suggest_for_token(
    pipe: &mut FullPipeline<'static>,
    toks: &[zemberek_tok::SpanToken],
    idx: usize,
    has_dyn: bool,
) -> Vec<String> {
    pipe.ensure_spell_ready();
    let dyn_opt = if has_dyn {
        Some(&pipe.dynamic_trie)
    } else {
        None
    };
    let has_bigrams = pipe
        .clean_lm
        .as_ref()
        .is_some_and(|lm| !lm.bigrams.is_empty());
    let prev_low = if has_bigrams && idx > 0 && !toks[idx - 1].is_immutable() {
        Some(toks[idx - 1].lower.as_str())
    } else {
        None
    };
    let next_low = if has_bigrams && idx + 1 < toks.len() && !toks[idx + 1].is_immutable() {
        Some(toks[idx + 1].lower.as_str())
    } else {
        None
    };
    let surface = &toks[idx].surface;
    let ranked = match (&pipe.spell, &pipe.clean_lm) {
        (Some(sp_chk), Some(lm)) => sp_chk.suggest_ranked(
            surface,
            prev_low,
            next_low,
            lm,
            &pipe.morph_strict,
            dyn_opt,
            &mut pipe.scratch,
        ),
        (Some(sp_chk), None) => sp_chk
            .suggest_scored(surface, &pipe.morph_strict, dyn_opt, &mut pipe.scratch)
            .into_iter()
            .map(|c| c.surface)
            .collect(),
        _ => Vec::new(),
    };
    ranked
        .into_iter()
        .filter(|s| s != surface)
        .take(3)
        .collect()
}

/// Supplements conservative `SpanLatticePerceptronNormalizer` span diagnostics with
/// `CompactStemEndingSpellChecker` for any uncovered OOV spelling mistakes (such as
/// unattested-bigram edit-distance-1 typos like `"konrol"` or `"kitablar"`).
fn append_uncovered_spell_issues(
    pipe: &mut FullPipeline<'static>,
    paragraph: &str,
    issues: &mut Vec<SpanIssue>,
) {
    if paragraph.is_empty() || !pipe.has_ending_trie {
        return;
    }
    let has_dyn = !pipe.dynamic_trie.items().is_empty();
    let sent_spans = TurkishSentenceExtractor::DEFAULT.extract_raw_spans(paragraph);
    let initial_len = issues.len();

    for sp in sent_spans {
        let sent_str = &paragraph[sp.utf8_start..sp.utf8_end];
        let toks = span_tokenize(sent_str);
        for idx in 0..toks.len() {
            let tok = &toks[idx];
            if tok.is_immutable()
                || tok.contains_digit()
                || tok.surface.chars().count() < 3
                || tok.lower.bytes().any(|b| matches!(b, b'w' | b'x' | b'q'))
                || tok.shape == CasingShape::Upper
                || (idx > 0 && tok.shape == CasingShape::Title)
            {
                continue;
            }
            let u8_start = sp.utf8_start + tok.utf8_start;
            let u8_end = sp.utf8_start + tok.utf8_end;
            if issues[..initial_len]
                .iter()
                .any(|iss| iss.utf8_start < u8_end && iss.utf8_end > u8_start)
            {
                continue;
            }
            if is_strict_valid_word(pipe, &tok.surface, has_dyn) {
                continue;
            }
            if pipe
                .clean_lm
                .as_ref()
                .is_some_and(|lm| lm.unigram_count(&tok.lower) >= 25)
            {
                continue;
            }

            let suggestions = suggest_for_token(pipe, &toks, idx, has_dyn);
            if suggestions.is_empty() {
                continue;
            }
            let primary = suggestions[0].clone();
            let kind = if TurkishAlphabet::equals_ignore_diacritics(&tok.surface, &primary) {
                IssueKind::DeasciifyOrColloquial
            } else {
                IssueKind::Spelling
            };
            issues.push(SpanIssue {
                start_tok: idx,
                end_tok: idx + 1,
                utf8_start: u8_start,
                utf8_end: u8_end,
                utf16_start: sp.utf16_start + tok.utf16_start,
                utf16_end: sp.utf16_start + tok.utf16_end,
                kind,
                raw_text: tok.surface.clone(),
                replacement: primary,
                suggestions,
            });
        }
    }

    if issues.len() > initial_len {
        issues.sort_by_key(|i| i.utf8_start);
    }
}

/// Repairs clear residual lowercase OOV 1-edit typos left behind as `IDENTITY` by the conservative
/// social-media sentence normalizer (e.g., `"konrol" -> "kontrol"`, `"kitablar" -> "kitaplar"`),
/// without touching capitalized names, acronyms, foreign `w/q/x` words, or attested loanwords.
fn repair_residual_lowercase_typos(
    pipe: &mut FullPipeline<'static>,
    normalized_sent: String,
) -> String {
    if normalized_sent.is_empty() || !pipe.has_ending_trie {
        return normalized_sent;
    }
    let has_dyn = !pipe.dynamic_trie.items().is_empty();
    let toks = span_tokenize(&normalized_sent);
    let mut replacements: Vec<(usize, usize, String)> = Vec::new();

    for idx in 0..toks.len() {
        let tok = &toks[idx];
        let char_len = tok.surface.chars().count();
        if tok.is_immutable()
            || tok.shape != CasingShape::Lower
            || !(4..=18).contains(&char_len)
            || tok.contains_digit()
            || tok.lower.bytes().any(|b| matches!(b, b'w' | b'x' | b'q'))
        {
            continue;
        }
        let raw_uni_cnt = pipe
            .clean_lm
            .as_ref()
            .map_or(0, |lm| lm.unigram_count(&tok.lower));
        if raw_uni_cnt >= 25 {
            continue;
        }
        let dyn_opt = if has_dyn {
            Some(&pipe.dynamic_trie)
        } else {
            None
        };
        let wa = pipe
            .morph_informal
            .analyze_word(&tok.surface, dyn_opt, &mut pipe.scratch);
        if wa
            .analyses
            .iter()
            .any(|a| !a.is_unknown() && !a.is_runtime(&pipe.graph))
        {
            continue;
        }

        pipe.ensure_spell_ready();
        let dyn_opt = if has_dyn {
            Some(&pipe.dynamic_trie)
        } else {
            None
        };
        let scored = match pipe.spell {
            Some(ref sp_chk) => {
                sp_chk.suggest_scored(&tok.surface, &pipe.morph_strict, dyn_opt, &mut pipe.scratch)
            }
            None => continue,
        };
        let ranked = suggest_for_token(pipe, &toks, idx, has_dyn);
        let Some(best_surf) = ranked.into_iter().find(|s| s == &TurkishAlphabet::to_lower_tr(s))
        else {
            continue;
        };
        let within_one_edit = scored
            .iter()
            .any(|sc| sc.surface == best_surf && sc.edit_cost <= 1.0);
        let cand_uni_cnt = pipe
            .clean_lm
            .as_ref()
            .map_or(0, |lm| lm.unigram_count(&best_surf));
        if within_one_edit && cand_uni_cnt >= 40 && cand_uni_cnt >= raw_uni_cnt.saturating_mul(25) {
            replacements.push((tok.utf8_start, tok.utf8_end, best_surf));
        }
    }

    if replacements.is_empty() {
        return normalized_sent;
    }

    let mut rebuilt = String::with_capacity(normalized_sent.len() + 8);
    let mut pos = 0usize;
    for (s, e, rep) in replacements {
        rebuilt.push_str(&normalized_sent[pos..s]);
        rebuilt.push_str(&rep);
        pos = e;
    }
    rebuilt.push_str(&normalized_sent[pos..]);
    rebuilt
}

fn check_paragraph_lite(pipe: &mut FullPipeline<'static>, paragraph: &str) -> Vec<SpanIssue> {
    if paragraph.is_empty() || !pipe.has_ending_trie {
        return Vec::new();
    }
    let has_dyn = !pipe.dynamic_trie.items().is_empty();
    let sent_spans = TurkishSentenceExtractor::DEFAULT.extract_raw_spans(paragraph);
    let mut out = Vec::new();

    for sp in sent_spans {
        let sent_str = &paragraph[sp.utf8_start..sp.utf8_end];
        let toks = span_tokenize(sent_str);
        for idx in 0..toks.len() {
            let tok = &toks[idx];
            if tok.is_immutable()
                || tok.contains_digit()
                || tok.surface.chars().count() < 2
            {
                continue;
            }
            if is_strict_valid_word(pipe, &tok.surface, has_dyn) {
                continue;
            }
            let suggestions = suggest_for_token(pipe, &toks, idx, has_dyn);
            if suggestions.is_empty() {
                continue;
            }
            let primary = suggestions[0].clone();
            let kind = if TurkishAlphabet::equals_ignore_diacritics(&tok.surface, &primary) {
                IssueKind::DeasciifyOrColloquial
            } else {
                IssueKind::Spelling
            };
            out.push(SpanIssue {
                start_tok: idx,
                end_tok: idx + 1,
                utf8_start: sp.utf8_start + tok.utf8_start,
                utf8_end: sp.utf8_start + tok.utf8_end,
                utf16_start: sp.utf16_start + tok.utf16_start,
                utf16_end: sp.utf16_start + tok.utf16_end,
                kind,
                raw_text: tok.surface.clone(),
                replacement: primary,
                suggestions,
            });
        }
    }
    out
}

/// Inspects a UTF-8 paragraph in `INPUT_BUF[0..in_len]` and writes packed binary `SpanIssue`
/// records into `OUTPUT_BUF`, returning the output byte length (`>= 4`) or negative error code.
#[no_mangle]
pub extern "C" fn zmbk_check_paragraph(in_len: u32, flags: u32) -> i32 {
    unsafe {
        let Ok(paragraph) = read_input_utf8(in_len) else {
            return -2;
        };
        let Some(ref mut pipe) = (*ENGINE.0.get()).pipeline else {
            return -1;
        };

        let bypass_cache = (flags & CHECK_FLAG_BYPASS_CACHE) != 0;
        let mode_flags = flags & CHECK_FLAG_FORCE_LITE;
        let ph = fnv1a64(paragraph.as_bytes());
        let p_slot = (ph as usize) & PARA_CACHE_MASK;

        if !bypass_cache {
            let cached = &pipe.caches.para_ring[p_slot];
            if cached.hash == ph && cached.flags == mode_flags && cached.in_len == in_len {
                return write_output_bytes(&cached.payload[..cached.out_len as usize]);
            }
        }

        let force_lite = (flags & CHECK_FLAG_FORCE_LITE) != 0;
        let issues = if !force_lite && pipe.has_norm {
            pipe.ensure_norm_ready();
            let dyn_opt = if pipe.dynamic_trie.items().is_empty() {
                None
            } else {
                Some(&pipe.dynamic_trie)
            };
            match pipe.norm {
                Some(ref nm) => {
                    let mut iss = nm.check_paragraph(paragraph, dyn_opt, &mut pipe.scratch);
                    append_uncovered_spell_issues(pipe, paragraph, &mut iss);
                    iss
                }
                None => check_paragraph_lite(pipe, paragraph),
            }
        } else {
            if pipe.spell_stems_dirty {
                pipe.ensure_spell_ready();
            }
            check_paragraph_lite(pipe, paragraph)
        };

        let (_, out_base) = ensure_io_ptrs();
        let out_slice = core::slice::from_raw_parts_mut(out_base, OUTPUT_BUF_CAP);
        let Some(written) = encode_issues_into(&issues, out_slice) else {
            return -4;
        };

        if !bypass_cache && written <= MAX_CACHED_PARA_OUT_BYTES {
            let entry = &mut pipe.caches.para_ring[p_slot];
            entry.hash = ph;
            entry.flags = mode_flags;
            entry.in_len = in_len;
            entry.out_len = written as u32;
            entry.payload.clear();
            entry.payload.extend_from_slice(&out_slice[..written]);
        }

        written as i32
    }
}

/// Normalizes multi-sentence UTF-8 text in `INPUT_BUF[0..in_len]` while preserving inter-sentence
/// whitespace and linebreaks, writing the result into `OUTPUT_BUF`.
#[no_mangle]
pub extern "C" fn zmbk_normalize(in_len: u32) -> i32 {
    unsafe {
        let Ok(text) = read_input_utf8(in_len) else {
            return -2;
        };
        let Some(ref mut pipe) = (*ENGINE.0.get()).pipeline else {
            return -1;
        };
        if text.is_empty() {
            return 0;
        }
        if pipe.has_norm {
            pipe.ensure_norm_ready();
        } else if pipe.has_deasc {
            pipe.ensure_deasc_ready();
        }

        let spans = TurkishSentenceExtractor::DEFAULT.extract_raw_spans(text);
        if spans.is_empty() {
            return write_output_bytes(text.as_bytes());
        }

        let mut out = String::with_capacity(text.len() + 32);
        let mut cursor = 0usize;
        for sp in spans {
            out.push_str(&text[cursor..sp.utf8_start]);
            let raw_sent = &text[sp.utf8_start..sp.utf8_end];
            let dyn_opt = if pipe.dynamic_trie.items().is_empty() {
                None
            } else {
                Some(&pipe.dynamic_trie)
            };
            if let Some(ref nm) = pipe.norm {
                let norm_sent = nm.normalize(raw_sent, dyn_opt, &mut pipe.scratch);
                out.push_str(&repair_residual_lowercase_typos(pipe, norm_sent));
            } else if let Some(ref dc) = pipe.deasc {
                out.push_str(&dc.deasciify(raw_sent, dyn_opt, &mut pipe.scratch));
            } else {
                out.push_str(raw_sent);
            }
            cursor = sp.utf8_end;
        }
        out.push_str(&text[cursor..]);
        write_output_bytes(out.as_bytes())
    }
}

/// Deasciifies UTF-8 text in `INPUT_BUF[0..in_len]` (`mode`: `0=None`, `1=DisambiguationOnly`, `2=Full`).
#[no_mangle]
pub extern "C" fn zmbk_deasciify(in_len: u32, mode: u32) -> i32 {
    unsafe {
        let Ok(text) = read_input_utf8(in_len) else {
            return -2;
        };
        let Some(ref mut pipe) = (*ENGINE.0.get()).pipeline else {
            return -1;
        };
        if text.is_empty() {
            return 0;
        }
        pipe.ensure_deasc_ready();
        let Some(ref dc) = pipe.deasc else {
            return write_output_bytes(text.as_bytes());
        };
        let cmode = match mode {
            0 => CircumflexMode::None,
            2 => CircumflexMode::Full,
            _ => CircumflexMode::DisambiguationOnly,
        };
        let dyn_opt = if pipe.dynamic_trie.items().is_empty() {
            None
        } else {
            Some(&pipe.dynamic_trie)
        };

        let spans = TurkishSentenceExtractor::DEFAULT.extract_raw_spans(text);
        if spans.is_empty() {
            return write_output_bytes(text.as_bytes());
        }
        let mut out = String::with_capacity(text.len() + 16);
        let mut cursor = 0usize;
        for sp in spans {
            out.push_str(&text[cursor..sp.utf8_start]);
            let raw_sent = &text[sp.utf8_start..sp.utf8_end];
            out.push_str(&dc.deasciify_with_mode(raw_sent, cmode, dyn_opt, &mut pipe.scratch));
            cursor = sp.utf8_end;
        }
        out.push_str(&text[cursor..]);
        write_output_bytes(out.as_bytes())
    }
}

fn push_json_escaped(out: &mut String, s: &str) {
    out.push('"');
    for ch in s.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => {
                out.push(' ');
            }
            c => out.push(c),
        }
    }
    out.push('"');
}

/// Analyzes and contextually disambiguates a sentence/token in `INPUT_BUF[0..in_len]`, writing a
/// compact JSON array of token analyses into `OUTPUT_BUF` for UI POS/lemma badges.
#[no_mangle]
pub extern "C" fn zmbk_analyze_disambiguate(in_len: u32) -> i32 {
    unsafe {
        let Ok(sentence) = read_input_utf8(in_len) else {
            return -2;
        };
        let Some(ref mut pipe) = (*ENGINE.0.get()).pipeline else {
            return -1;
        };
        if sentence.trim().is_empty() {
            return write_output_bytes(b"[]");
        }
        pipe.ensure_disambig_ready();
        let dyn_opt = if pipe.dynamic_trie.items().is_empty() {
            None
        } else {
            Some(&pipe.dynamic_trie)
        };
        let was = pipe
            .morph_informal
            .analyze_sentence(sentence, dyn_opt, &mut pipe.scratch);
        let chosen_indices = if let Some((ref dis, ref mut dscratch)) = pipe.disambig {
            dis.best_indices(&pipe.morph_informal, &was, DecodeMode::Viterbi, dscratch)
                .0
        } else {
            vec![0usize; was.len()]
        };

        let mut json = String::with_capacity(was.len() * 96 + 4);
        json.push('[');
        for (idx, wa) in was.iter().enumerate() {
            if idx > 0 {
                json.push(',');
            }
            let best = wa.analyses.get(chosen_indices[idx]);
            json.push_str("{\"surface\":");
            push_json_escaped(&mut json, &wa.input);
            if let Some(sa) = best {
                let lemma = sa.item.lemma(&pipe.graph);
                let ppos = sa.pos(&pipe.morph_informal.fst).short_form();
                let spos = sa.item.secondary_pos(&pipe.graph);
                let spos_str = if spos == SecondaryPos::None {
                    ""
                } else {
                    spos.name()
                };
                let formatted = sa.format_long(&pipe.graph, &pipe.morph_informal.fst);
                let known = !sa.is_unknown() && !sa.is_runtime(&pipe.graph);
                json.push_str(",\"lemma\":");
                push_json_escaped(&mut json, &lemma);
                json.push_str(",\"pos\":");
                push_json_escaped(&mut json, ppos);
                json.push_str(",\"secondaryPos\":");
                push_json_escaped(&mut json, spos_str);
                json.push_str(",\"analysis\":");
                push_json_escaped(&mut json, &formatted);
                json.push_str(",\"known\":");
                json.push_str(if known { "true" } else { "false" });
            } else {
                json.push_str(
                    ",\"lemma\":\"UNK\",\"pos\":\"Unk\",\"secondaryPos\":\"\",\"analysis\":\"?\",\"known\":false",
                );
            }
            json.push('}');
        }
        json.push(']');
        write_output_bytes(json.as_bytes())
    }
}

#[no_mangle]
pub extern "C" fn zmbk_add_user_word(in_len: u32) -> i32 {
    unsafe {
        let Ok(word) = read_input_utf8(in_len) else {
            return -2;
        };
        let Some(ref mut pipe) = (*ENGINE.0.get()).pipeline else {
            return -1;
        };
        if pipe
            .dynamic_trie
            .add_user_word(word, &pipe.graph.bridge)
            .is_some()
        {
            pipe.spell_stems_dirty = true;
            pipe.caches.invalidate_all();
            1
        } else {
            0
        }
    }
}

#[no_mangle]
pub extern "C" fn zmbk_remove_user_word(in_len: u32) -> i32 {
    unsafe {
        let Ok(word) = read_input_utf8(in_len) else {
            return -2;
        };
        let Some(ref mut pipe) = (*ENGINE.0.get()).pipeline else {
            return -1;
        };
        if pipe.dynamic_trie.remove_user_word(word) {
            pipe.spell_stems_dirty = true;
            pipe.caches.invalidate_all();
            1
        } else {
            0
        }
    }
}

#[no_mangle]
pub extern "C" fn zmbk_clear_user_words() -> u32 {
    unsafe {
        let Some(ref mut pipe) = (*ENGINE.0.get()).pipeline else {
            return 0;
        };
        let prev = pipe.dynamic_trie.items().len() as u32;
        if prev > 0 {
            pipe.dynamic_trie.clear();
            pipe.spell_stems_dirty = true;
            pipe.caches.invalidate_all();
        }
        prev
    }
}

#[no_mangle]
pub extern "C" fn zmbk_user_word_count() -> u32 {
    unsafe {
        (*ENGINE.0.get())
            .pipeline
            .as_ref()
            .map_or(0, |p| p.dynamic_trie.stems().len() as u32)
    }
}

// =========================================================================
// Step 0 Backwards-Compatible Exports (`SECT_LEXICON` & `SECT_QWT_MPHF`)
// =========================================================================

#[no_mangle]
pub extern "C" fn zmbk_dict_count() -> u32 {
    unsafe { (*ENGINE.0.get()).lexicon.map_or(0, |l| l.len() as u32) }
}

#[no_mangle]
pub extern "C" fn zmbk_dict_lemma_span(idx: u32) -> u64 {
    unsafe {
        let Some(lex) = (*ENGINE.0.get()).lexicon else {
            return 0;
        };
        if (idx as usize) >= lex.len() {
            return 0;
        }
        let s = lex.lemma(idx as usize);
        ((s.len() as u64) << 32) | (s.as_ptr() as usize as u64)
    }
}

#[no_mangle]
pub extern "C" fn zmbk_dict_root_attrs(idx: u32) -> u32 {
    unsafe {
        let Some(lex) = (*ENGINE.0.get()).lexicon else {
            return 0;
        };
        lex.items().get(idx as usize).map_or(0, |it| it.root_attrs)
    }
}

#[no_mangle]
pub extern "C" fn zmbk_qwt_len() -> u32 {
    unsafe { (*ENGINE.0.get()).qwt.map_or(0, |q| q.len() as u32) }
}

#[no_mangle]
pub extern "C" fn zmbk_qwt_get(key: u64) -> f32 {
    unsafe { (*ENGINE.0.get()).qwt.map_or(0.0, |q| q.get(key)) }
}

#[no_mangle]
pub extern "C" fn zmbk_qwt_get_bits(key: u64) -> u32 {
    zmbk_qwt_get(key).to_bits()
}
