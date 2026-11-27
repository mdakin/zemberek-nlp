use rayon::prelude::*;
use rustc_hash::{FxHashMap, FxHashSet};
use std::collections::BTreeMap;
use std::fs::{self, File};
use std::io::{BufRead, BufReader, BufWriter, Write};
use std::path::Path;
use std::time::Instant;

use zemberek_core::{
    PhonoMask, PhoneticAttribute, PrimaryPos, RootAttribute, SecondaryPos, TurkishAlphabet,
    TurkishNumeralEndingMachine,
};
use zemberek_morph::condition_vm::{eval_condition_rpn, has_excessive_cycle, PathNode, RootCondCtx};
use zemberek_morph::{AnalyzerScratch, MorphologyConfig, TurkishMorphology};
use zemberek_pack::morph_fst::{cond_op, MorphFstView};
use zemberek_pack::stem_trie::PackedStemTarget;
use zemberek_pack::ZmbkPack;

const FLAG_NEEDAFFIX: u16 = 65000;
const FLAG_FORBIDDEN: u16 = 65001;
const FLAG_NOSUGGEST: u16 = 65002;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
struct ParadigmKey {
    mat_entry_state: u16,
    root_attrs_masked: u32,
    primary_pos: u8,
    secondary_pos: u8,
    special_root_id: u8,
    root_surface_id: u8,
    apostrophe_mode: u8, // 0 = normal, 1 = proper/abbrv apostrophe-only suffixation
}

impl ParadigmKey {
    fn to_root_ctx(self) -> RootCondCtx {
        RootCondCtx {
            root_attrs: self.root_attrs_masked,
            primary_pos: self.primary_pos,
            secondary_pos: self.secondary_pos,
            special_root_id: self.special_root_id,
            root_surface_id: self.root_surface_id,
        }
    }
}

/// Computes the exact masks of `RootCondCtx` fields inspected by any RPN program in `MorphFstView`.
fn compute_active_root_masks(fst: &MorphFstView<'_>) -> (u32, u32, u64) {
    let mut attr_mask = 0u32;
    let mut ppos_mask = 0u32;
    let mut spos_mask = 0u64;
    for &inst in fst.cond_insts {
        let op = (inst & 0xFF) as u8;
        let arg = (inst >> 8) as usize;
        match op {
            cond_op::HAS_ROOT_ATTR => {
                if arg < 32 {
                    attr_mask |= 1u32 << arg;
                }
            }
            cond_op::HAS_ANY_ROOT_ATTR => {
                if arg < fst.set_words.len() {
                    attr_mask |= fst.set_words[arg] as u32;
                }
            }
            cond_op::ROOT_PRIMARY_POS_IS => {
                if arg < 32 {
                    ppos_mask |= 1u32 << arg;
                }
            }
            cond_op::SEC_POS_IS => {
                if arg < 64 {
                    spos_mask |= 1u64 << arg;
                }
            }
            _ => {}
        }
    }
    (attr_mask, ppos_mask, spos_mask)
}

/// Checks whether a paradigm can legally terminate with empty suffix `""` in `MorphFstView`.
fn can_terminate_bare(morph: &TurkishMorphology<'_>, key: ParadigmKey) -> bool {
    let fst = &morph.fst;
    let bridge = &morph.graph.bridge;
    let root_ctx = key.to_root_ctx();
    let abs_state = bridge.mat_state(key.mat_entry_state).abstract_state_id;
    let init_flags = if fst.abstract_states[abs_state as usize].is_derivative() {
        PathNode::FLAG_CONTAINS_DERIVATION
    } else {
        0
    };
    let mut arena = vec![PathNode {
        parent: PathNode::NONE,
        mat_state: key.mat_entry_state,
        abs_state,
        surf_id: 0,
        tail_byte_idx: 0,
        length: 1,
        flags: init_flags,
    }];
    let mut cur_level = vec![0usize];
    let mut next_level = Vec::new();

    for _depth in 0..8 {
        if cur_level.is_empty() {
            break;
        }
        next_level.clear();
        for &idx in &cur_level {
            let path = arena[idx];
            let ms_meta = bridge.mat_state(path.mat_state);
            let is_term = fst.abstract_states[path.abs_state as usize].is_terminal();
            let cannot_term = (ms_meta.phono_bits & PhoneticAttribute::CannotTerminate.bit()) != 0;
            if is_term && !cannot_term {
                return true;
            }
            for &arc in fst.outgoing_arcs(path.mat_state) {
                if arc.has_surface_form() || arc.is_informal() {
                    continue;
                }
                if !eval_condition_rpn(fst, arc.cond_prog_idx(), root_ctx, &arena, idx, false) {
                    continue;
                }
                let tgt_mat = arc.target_mat_state();
                let tgt_abs = bridge.mat_state(tgt_mat).abstract_state_id;
                let mut fl = path.flags;
                if fst.abstract_states[tgt_abs as usize].is_derivative() {
                    fl |= PathNode::FLAG_CONTAINS_DERIVATION;
                }
                let nidx = arena.len();
                arena.push(PathNode {
                    parent: idx as u16,
                    mat_state: tgt_mat,
                    abs_state: tgt_abs,
                    surf_id: 0,
                    tail_byte_idx: 0,
                    length: path.length.saturating_add(1),
                    flags: fl,
                });
                next_level.push(nidx);
            }
        }
        std::mem::swap(&mut cur_level, &mut next_level);
    }
    false
}

/// Checks whether `suffix` (non-empty) is accepted by `MorphFstView` starting from `ParadigmKey`.
fn fst_accepts_suffix(morph: &TurkishMorphology<'_>, key: ParadigmKey, suffix: &str) -> bool {
    if suffix.is_empty() {
        return can_terminate_bare(morph, key);
    }
    let clean_suf = if key.apostrophe_mode == 1 || key.apostrophe_mode == 3 {
        let Some(rest) = suffix.strip_prefix('\'') else {
            return false;
        };
        if rest.is_empty() {
            return false;
        }
        rest
    } else {
        suffix
    };

    let fst = &morph.fst;
    let bridge = &morph.graph.bridge;
    let root_ctx = key.to_root_ctx();
    let abs_state = bridge.mat_state(key.mat_entry_state).abstract_state_id;
    let init_flags = if fst.abstract_states[abs_state as usize].is_derivative() {
        PathNode::FLAG_CONTAINS_DERIVATION
    } else {
        0
    };
    let mut arena = Vec::with_capacity(64);
    arena.push(PathNode {
        parent: PathNode::NONE,
        mat_state: key.mat_entry_state,
        abs_state,
        surf_id: 0,
        tail_byte_idx: 0,
        length: 1,
        flags: init_flags,
    });
    let mut cur_level = vec![0usize];
    let mut next_level = Vec::new();
    let in_len = clean_suf.len();

    while !cur_level.is_empty() {
        if cur_level.len() > 30 {
            cur_level.retain(|&n_idx| !has_excessive_cycle(&arena, n_idx));
        }
        next_level.clear();
        for &node_idx in &cur_level {
            let path = arena[node_idx];
            let tail_byte = path.tail_byte_idx as usize;
            if tail_byte >= in_len {
                let ms_meta = bridge.mat_state(path.mat_state);
                let is_term = fst.abstract_states[path.abs_state as usize].is_terminal();
                let cannot_term =
                    (ms_meta.phono_bits & PhoneticAttribute::CannotTerminate.bit()) != 0;
                if is_term && !cannot_term {
                    return true;
                }
                for &arc in fst.outgoing_arcs(path.mat_state) {
                    if arc.has_surface_form() || arc.is_informal() {
                        continue;
                    }
                    if !eval_condition_rpn(fst, arc.cond_prog_idx(), root_ctx, &arena, node_idx, false)
                    {
                        continue;
                    }
                    let tgt_mat = arc.target_mat_state();
                    let tgt_abs = bridge.mat_state(tgt_mat).abstract_state_id;
                    let mut fl = path.flags;
                    if fst.abstract_states[tgt_abs as usize].is_derivative() {
                        fl |= PathNode::FLAG_CONTAINS_DERIVATION;
                    }
                    let nidx = arena.len();
                    arena.push(PathNode {
                        parent: node_idx as u16,
                        mat_state: tgt_mat,
                        abs_state: tgt_abs,
                        surf_id: 0,
                        tail_byte_idx: tail_byte as u16,
                        length: path.length.saturating_add(1),
                        flags: fl,
                    });
                    next_level.push(nidx);
                }
                continue;
            }

            let tail = &clean_suf[tail_byte..];
            for &arc in fst.outgoing_arcs(path.mat_state) {
                if arc.is_informal() {
                    continue;
                }
                if !arc.has_surface_form() {
                    if !eval_condition_rpn(fst, arc.cond_prog_idx(), root_ctx, &arena, node_idx, true)
                    {
                        continue;
                    }
                    let tgt_mat = arc.target_mat_state();
                    let tgt_abs = bridge.mat_state(tgt_mat).abstract_state_id;
                    let mut fl = path.flags;
                    if fst.abstract_states[tgt_abs as usize].is_derivative() {
                        fl |= PathNode::FLAG_CONTAINS_DERIVATION;
                    }
                    let nidx = arena.len();
                    arena.push(PathNode {
                        parent: node_idx as u16,
                        mat_state: tgt_mat,
                        abs_state: tgt_abs,
                        surf_id: 0,
                        tail_byte_idx: tail_byte as u16,
                        length: path.length.saturating_add(1),
                        flags: fl,
                    });
                    next_level.push(nidx);
                    continue;
                }

                let surf = fst.surface_str(arc.surf_id());
                let consumed = if surf.is_empty() {
                    0usize
                } else {
                    if tail.as_bytes()[0] != surf.as_bytes()[0] || !tail.starts_with(surf) {
                        continue;
                    }
                    surf.len()
                };
                if !eval_condition_rpn(fst, arc.cond_prog_idx(), root_ctx, &arena, node_idx, true) {
                    continue;
                }
                let tgt_mat = arc.target_mat_state();
                let tgt_abs = bridge.mat_state(tgt_mat).abstract_state_id;
                let mut fl = path.flags;
                if fst.abstract_states[tgt_abs as usize].is_derivative() {
                    fl |= PathNode::FLAG_CONTAINS_DERIVATION;
                }
                if !surf.is_empty() {
                    fl |= PathNode::FLAG_CONTAINS_SUFFIX_SURFACE;
                }
                let nidx = arena.len();
                arena.push(PathNode {
                    parent: node_idx as u16,
                    mat_state: tgt_mat,
                    abs_state: tgt_abs,
                    surf_id: arc.surf_id(),
                    tail_byte_idx: (tail_byte + consumed) as u16,
                    length: path.length.saturating_add(1),
                    flags: fl,
                });
                next_level.push(nidx);
            }
        }
        std::mem::swap(&mut cur_level, &mut next_level);
    }
    false
}

/// Bounded BFS over `MorphFstView` starting at `ParadigmKey` to enumerate core grammatical suffixes
/// up to `max_surface_Transitions` non-empty surface morphemes and `max_bytes` UTF-8 length.
fn enumerate_fst_suffixes(
    morph: &TurkishMorphology<'_>,
    key: ParadigmKey,
    max_surf_steps: u8,
    max_derivs: u8,
    max_bytes: usize,
    max_results: usize,
) -> FxHashSet<String> {
    let fst = &morph.fst;
    let bridge = &morph.graph.bridge;
    let root_ctx = key.to_root_ctx();
    let abs_state = bridge.mat_state(key.mat_entry_state).abstract_state_id;
    let init_flags = if fst.abstract_states[abs_state as usize].is_derivative() {
        PathNode::FLAG_CONTAINS_DERIVATION
    } else {
        0
    };

    #[derive(Clone)]
    struct GenExtra {
        suf: String,
        surf_steps: u8,
        deriv_count: u8,
    }

    let mut arena = Vec::with_capacity(4096);
    let mut extras = Vec::with_capacity(4096);
    arena.push(PathNode {
        parent: PathNode::NONE,
        mat_state: key.mat_entry_state,
        abs_state,
        surf_id: 0,
        tail_byte_idx: 0,
        length: 1,
        flags: init_flags,
    });
    extras.push(GenExtra {
        suf: String::new(),
        surf_steps: 0,
        deriv_count: 0,
    });

    let mut cur_level = vec![0usize];
    let mut next_level = Vec::new();
    let mut results = FxHashSet::default();

    for _depth in 0..14 {
        if cur_level.is_empty() || results.len() >= max_results {
            break;
        }
        next_level.clear();
        for &node_idx in &cur_level {
            if results.len() >= max_results {
                break;
            }
            let path = arena[node_idx];
            let ext = extras[node_idx].clone();
            let ms_meta = bridge.mat_state(path.mat_state);
            let is_term = fst.abstract_states[path.abs_state as usize].is_terminal();
            let cannot_term =
                (ms_meta.phono_bits & PhoneticAttribute::CannotTerminate.bit()) != 0;
            if is_term && !cannot_term && !ext.suf.is_empty() {
                if key.apostrophe_mode == 1 || key.apostrophe_mode == 3 {
                    results.insert(format!("'{}", ext.suf));
                } else {
                    results.insert(ext.suf.clone());
                }
            }

            if arena.len() > 250_000 || has_excessive_cycle(&arena, node_idx) {
                continue;
            }

            for &arc in fst.outgoing_arcs(path.mat_state) {
                if arc.is_informal() {
                    continue;
                }
                let tgt_mat = arc.target_mat_state();
                let tgt_abs = bridge.mat_state(tgt_mat).abstract_state_id;
                let is_deriv = fst.abstract_states[tgt_abs as usize].is_derivative();
                let new_deriv = ext.deriv_count + u8::from(is_deriv);
                if new_deriv > max_derivs {
                    continue;
                }

                let surf = if arc.has_surface_form() {
                    fst.surface_str(arc.surf_id())
                } else {
                    ""
                };
                let new_surf_steps = ext.surf_steps + u8::from(!surf.is_empty());
                if new_surf_steps > max_surf_steps || ext.suf.len() + surf.len() > max_bytes {
                    continue;
                }

                // Check condition for both `has_tail=true` (continuing) or `has_tail=false` (terminating)
                let ok_cont =
                    eval_condition_rpn(fst, arc.cond_prog_idx(), root_ctx, &arena, node_idx, true);
                let ok_term = !arc.has_surface_form()
                    && eval_condition_rpn(
                        fst,
                        arc.cond_prog_idx(),
                        root_ctx,
                        &arena,
                        node_idx,
                        false,
                    );
                if !ok_cont && !ok_term {
                    continue;
                }

                let mut fl = path.flags;
                if is_deriv {
                    fl |= PathNode::FLAG_CONTAINS_DERIVATION;
                }
                if !surf.is_empty() {
                    fl |= PathNode::FLAG_CONTAINS_SUFFIX_SURFACE;
                }
                let mut new_suf = String::with_capacity(ext.suf.len() + surf.len());
                new_suf.push_str(&ext.suf);
                new_suf.push_str(surf);

                let nidx = arena.len();
                if nidx >= u16::MAX as usize - 2 {
                    break;
                }
                arena.push(PathNode {
                    parent: node_idx as u16,
                    mat_state: tgt_mat,
                    abs_state: tgt_abs,
                    surf_id: arc.surf_id(),
                    tail_byte_idx: new_suf.len() as u16,
                    length: path.length.saturating_add(1),
                    flags: fl,
                });
                extras.push(GenExtra {
                    suf: new_suf,
                    surf_steps: new_surf_steps,
                    deriv_count: new_deriv,
                });
                next_level.push(nidx);
            }
        }
        std::mem::swap(&mut cur_level, &mut next_level);
    }
    results
}

/// Traverses `StemTrieView` to collect all `(stem_surface, PackedStemTarget)` pairs.
fn collect_all_stem_transitions(morph: &TurkishMorphology<'_>) -> Vec<(String, PackedStemTarget)> {
    let trie = &morph.graph.stem_trie;
    let pool = morph.graph.lexicon.utf8_pool();
    let mut out = Vec::with_capacity(80_000);
    let mut buf = String::with_capacity(64);

    fn dfs(
        trie: &zemberek_pack::stem_trie::StemTrieView<'_>,
        pool: &str,
        node_idx: usize,
        buf: &mut String,
        out: &mut Vec<(String, PackedStemTarget)>,
    ) {
        if node_idx > 0 {
            let targs = trie.node_targets(node_idx);
            for &t in targs {
                out.push((buf.clone(), t));
            }
        }
        let parent = trie.node(node_idx);
        let base_idx = parent.first_child_idx();
        let children = trie.children_of(node_idx);
        for (off, &child) in children.iter().enumerate() {
            let prev = buf.len();
            buf.push_str(trie.edge_label(child, pool));
            dfs(trie, pool, base_idx + off, buf, out);
            buf.truncate(prev);
        }
    }

    dfs(trie, pool, 0, &mut buf, &mut out);
    out
}

fn has_foreign_chars(s: &str) -> bool {
    s.chars().any(|c| matches!(c, 'q' | 'w' | 'x' | 'Q' | 'W' | 'X'))
}

fn is_valid_tr_stem_chars(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 90
        && s.chars().all(|c| {
            c.is_alphabetic()
                || matches!(
                    c,
                    'ç' | 'ğ'
                        | 'ı'
                        | 'ö'
                        | 'ş'
                        | 'ü'
                        | 'Ç'
                        | 'Ğ'
                        | 'İ'
                        | 'I'
                        | 'Ö'
                        | 'Ş'
                        | 'Ü'
                        | 'â'
                        | 'î'
                        | 'û'
                        | 'Â'
                        | 'Î'
                        | 'Û'
                )
        })
}

/// Reapplies circumflex characters from `lemma` onto `stem` when `normalize_circumflex(lemma)`
/// shares the prefix with `stem` (e.g. `lemma = "kâğıt"`, `stem = "kağıd"` -> `"kâğıd"`).
fn reapply_circumflex(lemma: &str, stem: &str) -> Option<String> {
    if !TurkishAlphabet::contains_circumflex(lemma) {
        return None;
    }
    let lemma_chars: Vec<char> = TurkishAlphabet::to_lower_tr(lemma).chars().collect();
    let stem_chars: Vec<char> = stem.chars().collect();
    let mut out = String::with_capacity(stem.len() + 4);
    let mut changed = false;
    for (i, &sc) in stem_chars.iter().enumerate() {
        if i < lemma_chars.len() {
            let lc = lemma_chars[i];
            if TurkishAlphabet::normalize_circumflex_char(lc) == sc && lc != sc {
                out.push(lc);
                changed = true;
                continue;
            }
        }
        out.push(sc);
    }
    if changed {
        Some(out)
    } else {
        None
    }
}

/// Extracts word counts from strictly DISJOINT training corpora (2019-2024 + wiki_part0/1 + tbmm).
/// NEVER touches held-out evaluation files (`mevzuat_2025_clean.txt`, `trwiki_2026_part2.txt`, `wmt_news_2025.txt`)!
fn scan_training_corpora() -> FxHashMap<String, u32> {
    let data_dir = std::env::var("ZEMBEREK_CORPUS_DIR")
        .unwrap_or_else(|_| "data/turkish/refined_modern_2026".to_string());
    // Strictly DISJOINT from held-out evaluation files (`mevzuat_2025_clean.txt`, `trwiki_2026_part2.txt`, `wmt_news_2025.txt`)!
    let train_files = [
        ("trwiki_2026_part0.txt", 450_000usize),
        ("trwiki_2026_part1.txt", 400_000),
        ("wmt_news_2020.txt", 350_000),
        ("wmt_news_2021.txt", 350_000),
        ("wmt_news_2022.txt", 350_000),
        ("wmt_news_2023.txt", 350_000),
        ("wmt_news_2024.txt", 450_000),
        ("tbmm-tutanak.txt", 350_000),
        ("yargitay-karar.txt", 350_000),
        ("resmi_gazete_2019_html_clean.txt", 250_000),
    ];

    let per_file: Vec<FxHashMap<String, u32>> = train_files
        .par_iter()
        .map(|(fname, max_lines)| {
            let path = format!("{data_dir}/{fname}");
            let mut local = FxHashMap::default();
            let Ok(file) = File::open(&path) else {
                return local;
            };
            let reader = BufReader::with_capacity(1 << 20, file);
            for (li, line_res) in reader.lines().enumerate() {
                if li >= *max_lines {
                    break;
                }
                let Ok(line) = line_res else { continue };
                let mut cur = String::new();
                for ch in line.chars() {
                    let norm_ch = match ch {
                        '’' | '‘' | 'ʼ' => '\'',
                        c => c,
                    };
                    if norm_ch.is_alphanumeric() || norm_ch == '\'' {
                        cur.push(norm_ch);
                    } else if !cur.is_empty() {
                        let trimmed = cur.trim_matches('\'');
                        if trimmed.len() >= 2 && trimmed.len() <= 64 {
                            *local.entry(trimmed.to_string()).or_insert(0) += 1;
                        }
                        cur.clear();
                    }
                }
                if !cur.is_empty() {
                    let trimmed = cur.trim_matches('\'');
                    if trimmed.len() >= 2 && trimmed.len() <= 64 {
                        *local.entry(trimmed.to_string()).or_insert(0) += 1;
                    }
                }
            }
            local
        })
        .collect();

    let mut merged = FxHashMap::default();
    for map in per_file {
        for (k, v) in map {
            *merged.entry(k).or_insert(0u32) = merged.get(&k).copied().unwrap_or(0).saturating_add(v);
        }
    }
    merged
}

/// Defines pure, 100%-co-occurring Outer (Layer 2) Suffix Continuation Blocks for high-ratio 2-fold factorization.
fn build_candidate_layer2_blocks() -> Vec<(&'static str, Vec<&'static str>)> {
    vec![
        // 1. Consonant-initial Nominal Case + Copula + -ki + Plural-P3-Case (no `-ca/-ce` on singular so Participles match 100%, while `-larca`/`-larınca` are universal!)
        ("NomCons_a_ı_voiced", vec![
            "da", "dan", "daki", "dakiler", "la", "dır", "dırlar",
            "lar", "ları", "ların", "lara", "larda", "lardan", "larla", "lardaki", "larca", "lardır", "larıdır", "larınca",
            "larına", "larını", "larında", "larından", "larıyla", "larındaki",
        ]),
        ("NomCons_a_ı_vless", vec![
            "ta", "tan", "taki", "takiler", "la", "tır", "tırlar",
            "lar", "ları", "ların", "lara", "larda", "lardan", "larla", "lardaki", "larca", "lardır", "larıdır", "larınca",
            "larına", "larını", "larında", "larından", "larıyla", "larındaki",
        ]),
        ("NomCons_e_i_voiced", vec![
            "de", "den", "deki", "dekiler", "le", "dir", "dirler",
            "ler", "leri", "lerin", "lere", "lerde", "lerden", "lerle", "lerdeki", "lerce", "lerdir", "leridir", "lerince",
            "lerine", "lerini", "lerinde", "lerinden", "leriyle", "lerindeki",
        ]),
        ("NomCons_e_i_vless", vec![
            "te", "ten", "teki", "tekiler", "le", "tir", "tirler",
            "ler", "leri", "lerin", "lere", "lerde", "lerden", "lerle", "lerdeki", "lerce", "lerdir", "leridir", "lerince",
            "lerine", "lerini", "lerinde", "lerinden", "leriyle", "lerindeki",
        ]),
        ("NomCons_o_u_voiced", vec![
            "da", "dan", "daki", "dakiler", "la", "dur", "durlar",
            "lar", "ları", "ların", "lara", "larda", "lardan", "larla", "lardaki", "larca", "lardır", "larıdır", "larınca",
            "larına", "larını", "larında", "larından", "larıyla", "larındaki",
        ]),
        ("NomCons_o_u_vless", vec![
            "ta", "tan", "taki", "takiler", "la", "tur", "turlar",
            "lar", "ları", "ların", "lara", "larda", "lardan", "larla", "lardaki", "larca", "lardır", "larıdır", "larınca",
            "larına", "larını", "larında", "larından", "larıyla", "larındaki",
        ]),
        ("NomCons_ö_ü_voiced", vec![
            "de", "den", "deki", "dekiler", "le", "dür", "dürler",
            "ler", "leri", "lerin", "lere", "lerde", "lerden", "lerle", "lerdeki", "lerce", "lerdir", "leridir", "lerince",
            "lerine", "lerini", "lerinde", "lerinden", "leriyle", "lerindeki",
        ]),
        ("NomCons_ö_ü_vless", vec![
            "te", "ten", "teki", "tekiler", "le", "tür", "türler",
            "ler", "leri", "lerin", "lere", "lerde", "lerden", "lerle", "lerdeki", "lerce", "lerdir", "leridir", "lerince",
            "lerine", "lerini", "lerinde", "lerinden", "leriyle", "lerindeki",
        ]),
        // 1b. Infinitive `-mak/-mek` outer case/copula (no plural `-lar`)
        ("InfCons_back", vec!["ta", "tan", "taki", "la", "tır", "tense", "tansa"]),
        ("InfCons_front", vec!["te", "ten", "teki", "le", "tir", "tense"]),
        // 1c. Nominal Case + Plural + P3-Case after Vowel-ending L1 derivations (`-cı/-ci`, `-lı/-li`, `-ma/-me`, `-ca/-ce` — no `"la"/"le"`, since `NomBuf_*` provides `"yla"/"yle"`!)
        ("NomAfterVow_a_ı", vec![
            "da", "dan", "daki", "dakiler", "dır", "dırlar",
            "lar", "ları", "ların", "lara", "larda", "lardan", "larla", "lardaki", "larca", "lardır", "larıdır", "larınca",
            "larına", "larını", "larında", "larından", "larıyla", "larındaki",
        ]),
        ("NomAfterVow_e_i", vec![
            "de", "den", "deki", "dekiler", "dir", "dirler",
            "ler", "leri", "lerin", "lere", "lerde", "lerden", "lerle", "lerdeki", "lerce", "lerdir", "leridir", "lerince",
            "lerine", "lerini", "lerinde", "lerinden", "leriyle", "lerindeki",
        ]),
        ("NomAfterVow_o_u", vec![
            "da", "dan", "daki", "dakiler", "dur", "durlar",
            "lar", "ları", "ların", "lara", "larda", "lardan", "larla", "lardaki", "larca", "lardır", "larıdır", "larınca",
            "larına", "larını", "larında", "larından", "larıyla", "larındaki",
        ]),
        ("NomAfterVow_ö_ü", vec![
            "de", "den", "deki", "dekiler", "dür", "dürler",
            "ler", "leri", "lerin", "lere", "lerde", "lerden", "lerle", "lerdeki", "lerce", "lerdir", "leridir", "lerince",
            "lerine", "lerini", "lerinde", "lerinden", "leriyle", "lerindeki",
        ]),
        // 1d. Invariant tails after ANY `-*ki` suffix (`daki, deki, ndaki, ndeki, mdaki, mdeki, sındaki, sindeki`)
        ("AfterKi_tails", vec![
            "ne", "ni", "nin", "nde", "nden", "yle", "dir",
            "ler", "lere", "leri", "lerin", "lerde", "lerden", "lerle", "lerce", "lerdir",
        ]),
        // 1e. Buffer-vowel Nominal Copula (`kilolusun`, `namusluyum`, `haklısındır`, `haklıysam`, `farkındasındır`)
        ("NomBufCop_a_ı", vec!["yım", "sın", "yız", "sınız", "ydı", "ydım", "ymış", "ysa", "ysam", "ysan", "ysanız", "sındır"]),
        ("NomBufCop_e_i", vec!["yim", "sin", "yiz", "siniz", "ydi", "ydim", "ymiş", "yse", "ysem", "ysen", "yseniz", "sindir"]),
        ("NomBufCop_o_u", vec!["yum", "sun", "yuz", "sunuz", "ydu", "ydum", "ymuş", "ysa", "ysam", "ysan", "ysanız", "sundur"]),
        ("NomBufCop_ö_ü", vec!["yüm", "sün", "yüz", "sünüz", "ydü", "ydüm", "ymüş", "yse", "ysem", "ysen", "yseniz", "sündür"]),
        // 1f. Partitive Plural Ablative Copula (`diyenlerdenim`, `olanlardanım`, `insanlardansın`)
        ("AfterLarAblCop_back", vec!["danım", "dansın", "danız", "dansınız"]),
        ("AfterLerAblCop_front", vec!["denim", "densin", "deniz", "densiniz"]),
        // 2. Pure Case + Possessive-Case after Plural `-lar/-ler` (no double `-larlar`, no `-ınca` so PastPart `-dıklar` matches 100%!)
        ("AfterLar_back", vec![
            "ı", "a", "da", "dan", "daki", "ın", "la", "ca", "dır",
            "ım", "ıma", "ımı", "ımda", "ımdan", "ımın", "ımla",
            "ımız", "ımıza", "ımızı", "ımızda", "ımızdan", "ımızın", "ımızla",
            "ınız", "ınıza", "ınızı", "ınızda", "ınızdan", "ınızın", "ınızla",
            "ına", "ını", "ında", "ından", "ındaki", "ındakiler", "ındakileri", "ıyla", "ıdır",
        ]),
        ("AfterLer_front", vec![
            "i", "e", "de", "den", "deki", "in", "le", "ce", "dir",
            "im", "ime", "imi", "imde", "imden", "imin", "imle",
            "imiz", "imize", "imizi", "imizde", "imizden", "imizin", "imizle",
            "iniz", "inize", "inizi", "inizde", "inizden", "inizin", "inizle",
            "ine", "ini", "inde", "inden", "indeki", "indekiler", "indekileri", "iyle", "idir",
        ]),
        // 3. Vowel-initial Possessive + Case (fires on `lığ`, `cığ`, `dığ`, `acağ`, `abildiğ`, `abileceğ`, `madığ`, `mayacağ` — no `-ınca` so FutPart `-acağ` matches 100%!)
        ("NomVow_a_ı", vec![
            "ı", "a", "ın", "ına", "ını", "ında", "ından", "ındaki", "ındakiler", "ındakileri", "ıyla", "ınla", "ının", "ıdır", "ıydı", "ıysa",
            "ım", "ıma", "ımı", "ımda", "ımdan", "ımdaki", "ımdakileri", "ımın", "ımla",
            "ımız", "ımıza", "ımızı", "ımızda", "ımızdan", "ımızın", "ımızla",
            "ınız", "ınıza", "ınızı", "ınızda", "ınızdan", "ınızın", "ınızla",
        ]),
        ("NomVow_e_i", vec![
            "i", "e", "in", "ine", "ini", "inde", "inden", "indeki", "indekiler", "indekileri", "iyle", "inle", "inin", "idir", "iydi", "iyse",
            "im", "ime", "imi", "imde", "imden", "imdeki", "imdekileri", "imin", "imle",
            "imiz", "imize", "imizi", "imizde", "imizden", "imizin", "imizle",
            "iniz", "inize", "inizi", "inizde", "inizden", "inizin", "inizle",
        ]),
        ("NomVow_o_u", vec![
            "u", "a", "un", "una", "unu", "unda", "undan", "undaki", "undakiler", "undakileri", "uyla", "unla", "unun", "udur", "uydu", "uysa",
            "um", "uma", "umu", "umda", "umdan", "umdaki", "umdakileri", "umun", "umla",
            "umuz", "umuza", "umuzu", "umuzda", "umuzdan", "umuzun", "umuzla",
            "unuz", "unuza", "unuzu", "unuzda", "unuzdan", "unuzun", "unuzla",
        ]),
        ("NomVow_ö_ü", vec![
            "ü", "e", "ün", "üne", "ünü", "ünde", "ünden", "ündeki", "ündekiler", "ündekileri", "üyle", "ünle", "ünün", "üdür", "üydü", "üyse",
            "üm", "üme", "ümü", "ümde", "ümden", "ümdeki", "ümdekileri", "ümün", "ümle",
            "ümüz", "ümüze", "ümüzü", "ümüzde", "ümüzden", "ümüzün", "ümüzle",
            "ünüz", "ünüze", "ünüzü", "ünüzde", "ünüzden", "ünüzün", "ünüzle",
        ]),
        // 4. Buffer-vowel Nominal Case + Possessive (including P2sg `n, na, nı, nda, ndan, nın, nla` & `-mdaki/-ndaki`!)
        ("NomBuf_a_ı", vec![
            "yı", "ya", "nın", "yla",
            "sı", "sına", "sını", "sında", "sından", "sındaki", "sındakiler", "sındakileri", "sıyla", "sının", "sınca", "sındır", "sıydı",
            "m", "ma", "mı", "mda", "mdan", "mdaki", "mdakine", "mdakinin", "mın", "mla",
            "n", "na", "nı", "nda", "ndan", "ndaki", "ndakine", "ndakinin", "nla",
            "mız", "mıza", "mızı", "mızda", "mızdan", "mızın", "mızla",
            "nız", "nıza", "nızı", "nızda", "nızdan", "nızın", "nızla",
        ]),
        ("NomBuf_e_i", vec![
            "yi", "ye", "nin", "yle",
            "si", "sine", "sini", "sinde", "sinden", "sindeki", "sindekiler", "sindekileri", "siyle", "sinin", "since", "sindir", "siydi",
            "m", "me", "mi", "mde", "mden", "mdeki", "mdekine", "mdekinin", "min", "mle",
            "n", "ne", "ni", "nde", "nden", "ndeki", "ndekine", "ndekinin", "nle",
            "miz", "mize", "mizi", "mizde", "mizden", "mizin", "mizle",
            "niz", "nize", "nizi", "nizde", "nizden", "nizin", "nizle",
        ]),
        ("NomBuf_o_u", vec![
            "yu", "ya", "nun", "yla",
            "su", "suna", "sunu", "sunda", "sundan", "sundaki", "sundakiler", "sundakileri", "suyla", "sunun", "sunca", "sundur", "suydu",
            "m", "ma", "mu", "mda", "mdan", "mdaki", "mdakine", "mdakinin", "mun", "mla",
            "n", "na", "nu", "nda", "ndan", "ndaki", "ndakine", "ndakinin", "nla",
            "muz", "muza", "muzu", "muzda", "muzdan", "muzun", "muzla",
            "nuz", "nuza", "nuzu", "nuzda", "nuzdan", "nuzun", "nuzla",
        ]),
        ("NomBuf_ö_ü", vec![
            "yü", "ye", "nün", "yle",
            "sü", "süne", "sünü", "sünde", "sünden", "sündeki", "sündekiler", "sündekileri", "süyle", "sünün", "sünce", "sündür", "süydü",
            "m", "me", "mü", "mde", "mden", "mdeki", "mdekine", "mdekinin", "mün", "mle",
            "n", "ne", "nü", "nde", "nden", "ndeki", "ndekine", "ndekinin", "nle",
            "müz", "müze", "müzü", "müzde", "müzden", "müzün", "müzle",
            "nüz", "nüze", "nüzü", "nüzde", "nüzden", "nüzün", "nüzle",
        ]),
        // 5. P3sg / Plural-Possessive Case tails (after `-ları/-leri` or `-sı/-si`!)
        ("P3Case_back", vec![
            "na", "nı", "nda", "ndan", "ndaki", "ndakiler", "ndakileri", "nın", "nındır", "ndadır", "ndansa", "yla", "nca",
        ]),
        ("P3Case_front", vec![
            "ne", "ni", "nde", "nden", "ndeki", "ndekiler", "ndekileri", "nin", "nindir", "ndedir", "ndense", "yle", "nce",
        ]),
        // 6. Verbal Person + Copula after `-yor`
        ("VerbYor_tails", vec![
            "um", "sun", "uz", "sunuz", "lar", "lardı", "larmış", "larsa", "lardır",
            "du", "dum", "dun", "duk", "dunuz",
            "muş", "muşum", "muşsun", "muşuz", "muşsunuz", "muşçasına",
            "sa", "sam", "san", "sak", "sanız",
        ]),
        // 7. Verbal Past/Conditional (`-dı/-di/-du/-dü`, `-sa/-se`, nominal `-ydı/-ydi`) tails
        ("VerbPast_a_ı", vec!["m", "n", "k", "nız", "lar"]),
        ("VerbPast_e_i", vec!["m", "n", "k", "niz", "ler"]),
        ("VerbPast_o_u", vec!["m", "n", "k", "nuz", "lar"]),
        ("VerbPast_ö_ü", vec!["m", "n", "k", "nüz", "ler"]),
        // 8. Verbal Evidential (`-mış/-miş/-muş/-müş`) tails
        ("VerbMiş_a_ı", vec![
            "ım", "sın", "ız", "sınız", "lar", "tır", "lardır", "ımdır", "sındır",
            "tı", "tım", "tın", "tık", "tınız", "sa", "çasına", "lık",
        ]),
        ("VerbMiş_e_i", vec![
            "im", "sin", "iz", "siniz", "ler", "tir", "lerdir", "imdir", "sindir",
            "ti", "tim", "tin", "tik", "tiniz", "se", "çesine", "lik",
        ]),
        ("VerbMiş_o_u", vec![
            "um", "sun", "uz", "sunuz", "lar", "tur", "lardır", "umdur", "sundur",
            "tu", "tum", "tun", "tuk", "tunuz", "sa", "çasına", "luk",
        ]),
        ("VerbMiş_ö_ü", vec![
            "üm", "sün", "üz", "sünüz", "ler", "tür", "lerdir", "ümdür", "sündür",
            "tü", "tüm", "tün", "tük", "tünüz", "se", "çesine", "lük",
        ]),
        // 9. Verbal Future Consonant (`-acak/-ecek`) & Negative Aorist (`-maz/-mez`) tails
        ("VerbFut_back", vec![
            "sın", "sınız", "lar", "tır", "lardır", "tı", "tım", "tın", "tık", "tınız", "tılar",
            "sa", "sam", "san", "sak", "sanız", "larsa", "mış",
        ]),
        ("VerbFut_front", vec![
            "sin", "siniz", "ler", "tir", "lerdir", "ti", "tim", "tin", "tik", "tiniz", "tiler",
            "se", "sem", "sen", "sek", "seniz", "lerse", "miş",
        ]),
        ("VerbNegAor_back", vec![
            "sın", "sınız", "lar", "dı", "dım", "dın", "dık", "dınız", "dılar",
            "mış", "sa", "sam", "san", "sak", "sanız",
        ]),
        ("VerbNegAor_front", vec![
            "sin", "siniz", "ler", "di", "dim", "din", "dik", "diniz", "diler",
            "miş", "se", "sem", "sen", "sek", "seniz",
        ]),
        // 10. Verbal Aorist (`-ır/-ir/-ur/-ür/-ar/-er/-r`) tails
        ("VerbAor_a_ı", vec![
            "ım", "sın", "ız", "sınız", "lar", "dı", "dım", "dın", "dık", "dınız",
            "mış", "mışım", "mışsın", "mışız", "mışsınız", "mışlar",
            "sa", "sam", "san", "sak", "sanız", "larsa", "lardı",
        ]),
        ("VerbAor_e_i", vec![
            "im", "sin", "iz", "siniz", "ler", "di", "dim", "din", "dik", "diniz",
            "miş", "mişim", "mişsin", "mişiz", "mişsiniz", "mişler",
            "se", "sem", "sen", "sek", "seniz", "lerse", "lerdi",
        ]),
        ("VerbAor_o_u", vec![
            "um", "sun", "uz", "sunuz", "lar", "du", "dum", "dun", "duk", "dunuz",
            "muş", "muşum", "muşsun", "muşuz", "muşsunuz", "muşlar",
            "sa", "sam", "san", "sak", "sanız", "larsa", "lardı",
        ]),
        ("VerbAor_ö_ü", vec![
            "üm", "sün", "üz", "sünüz", "ler", "dü", "düm", "dün", "dük", "dünüz",
            "müş", "müşüm", "müşsün", "müşüz", "müşsünüz", "müşler",
            "se", "sem", "sen", "sek", "seniz", "lerse", "lerdi",
        ]),
        // 11. Verbal Necessitative (`-malı/-meli`) & Formal Progressive (`-makta/-mekte`) tails
        ("VerbNec_back", vec![
            "yım", "sın", "yız", "sınız", "lar", "dır", "ydı", "ydım", "ymış",
        ]),
        ("VerbNec_front", vec![
            "yim", "sin", "yiz", "siniz", "ler", "dir", "ydi", "ydim", "ymiş",
        ]),
        // 12. Denominal & Compound Verb Stem Continuations (`-laş/-leş`, `-lan/-len`, `-laştır/-leştir`, `-landır/-lendir`, `-abil/-ebil`, `-tır/-tir`, `-ıl/-il`)
        ("VerbCommon_back", vec![
            "ma", "mak", "makta", "maktadır", "maksızın", "madan", "malı", "malıyım", "malısın", "malıyız", "malısınız", "malıdır",
            "mama", "mamak", "madı", "madım", "madın", "madık", "madınız", "madılar", "mamış",
            "mıyor", "mıyorum", "mıyorsun", "mıyoruz", "maz", "mazsa", "mayacak", "mayacağız", "mayan", "mayıp",
            "masa", "masam", "masan", "masın", "masınlar", "mayalım",
            "sa", "sam", "san", "sak", "sanız", "salar", "saydı", "saydım", "saydın", "saydık", "sın", "sınlar",
        ]),
        ("VerbCommon_front", vec![
            "me", "mek", "mekte", "mektedir", "meksizin", "meden", "meli", "meliyim", "melisin", "meliyiz", "melisiniz", "melidir",
            "meme", "memek", "medi", "medim", "medin", "medik", "mediniz", "mediler", "memiş",
            "miyor", "miyorum", "miyorsun", "miyoruz", "mez", "mezse", "meyecek", "meyeceğiz", "meyen", "meyip",
            "mese", "mesem", "mesen", "mesin", "mesinler", "meyelim",
            "se", "sem", "sen", "sek", "seniz", "seler", "seydi", "seydim", "seydin", "seydik", "sin", "sinler",
        ]),
        ("VerbVow_a_ı", vec![
            "ıyor", "ıyorum", "ıyorsun", "ıyoruz", "ıyorsunuz", "ıyorlar", "ıyordu",
            "acak", "acağım", "acaksın", "acağız", "acaksınız", "acaklar", "acaktı", "acaktır",
            "acağı", "acağın", "acağımız", "acağınız", "acakları",
            "an", "anlar", "anları", "anların", "anlara", "arak", "ınca",
            "ır", "ırım", "ırsın", "ırız", "ırsınız", "ırlar", "ırdı", "ırsa", "ırsam", "ırsan", "ırsanız", "ırken",
            "ayım", "alım", "ın", "ınız",
        ]),
        ("VerbVow_e_i", vec![
            "iyor", "iyorum", "iyorsun", "iyoruz", "iyorsunuz", "iyorlar", "iyordu",
            "ecek", "eceğim", "eceksin", "eceğiz", "eceksiniz", "ecekler", "ecekti", "ecektir",
            "eceği", "eceğin", "eceğimiz", "eceğiniz", "ecekleri",
            "en", "enler", "enleri", "enlerin", "enlere", "erek", "ince",
            "ir", "irim", "irsin", "iriz", "irsiniz", "irler", "irdi", "irse", "irsem", "irsen", "irseniz", "irken",
            "eyim", "elim", "in", "iniz",
        ]),
        ("VerbVow_o_u", vec![
            "uyor", "uyorum", "uyorsun", "uyoruz", "uyorsunuz", "uyorlar", "uyordu",
            "acak", "acağım", "acaksın", "acağız", "acaksınız", "acaklar", "acaktı", "acaktır",
            "acağı", "acağın", "acağımız", "acağınız", "acakları",
            "an", "anlar", "anları", "anların", "anlara", "arak", "unca",
            "ur", "urum", "ursun", "uruz", "ursunuz", "urlar", "urdu", "ursa", "ursam", "ursan", "ursanız", "urken",
            "ayım", "alım", "un", "unuz",
        ]),
        ("VerbVow_ö_ü", vec![
            "üyor", "üyorum", "üyorsun", "üyoruz", "üyorsunuz", "üyorlar", "üyordu",
            "ecek", "eceğim", "eceksin", "eceğiz", "eceksiniz", "ecekler", "ecekti", "ecektir",
            "eceği", "eceğin", "eceğimiz", "eceğiniz", "ecekleri",
            "en", "enler", "enleri", "enlerin", "enlere", "erek", "ünce",
            "ür", "ürüm", "ürsün", "ürüz", "ürsünüz", "ürler", "ürdü", "ürse", "ürsem", "ürsen", "ürseniz", "ürken",
            "eyim", "elim", "ün", "ünüz",
        ]),
    ]
}

/// Curated Turkish `REP` replacement pairs (all unanchored and `_`-space-safe for `HUNSPELL_CHROME_CLIENT`!).
fn build_turkish_rep_table() -> Vec<(&'static str, &'static str)> {
    vec![
        // 1. Multi-word split errors ('_' -> ' ' in AffReader::AddReplacement)
        ("herşey", "her_şey"),
        ("herşeyi", "her_şeyi"),
        ("herşeyin", "her_şeyin"),
        ("herşeye", "her_şeye"),
        ("herşeyde", "her_şeyde"),
        ("herşeyden", "her_şeyden"),
        ("herşeyle", "her_şeyle"),
        ("birşey", "bir_şey"),
        ("birşeyi", "bir_şeyi"),
        ("birşeyin", "bir_şeyin"),
        ("birşeye", "bir_şeye"),
        ("birşeyler", "bir_şeyler"),
        ("hiçbirşey", "hiçbir_şey"),
        ("pekçok", "pek_çok"),
        ("hergün", "her_gün"),
        ("hertürlü", "her_türlü"),
        ("herzamanki", "her_zamanki"),
        ("herzaman", "her_zaman"),
        ("birarada", "bir_arada"),
        ("biraraya", "bir_araya"),
        ("yanısıra", "yanı_sıra"),
        ("ardarda", "art_arda"),
        ("peşpeşe", "peş_peşe"),
        ("başbaşa", "baş_başa"),
        ("yanyana", "yan_yana"),
        ("içiče", "iç_içe"),
        ("içiçe", "iç_içe"),
        ("yüzüstü", "yüzüstü"),
        ("farket", "fark_et"),
        ("terket", "terk_et"),
        ("arzet", "arz_et"),
        ("sarfet", "sarf_et"),
        ("sağol", "sağ_ol"),
        ("sağolun", "sağ_olun"),
        ("hoşçakal", "hoşça_kal"),
        ("hoşgeldin", "hoş_geldin"),
        ("hoşgeldiniz", "hoş_geldiniz"),
        ("inşallah", "inşallah"),
        // 2. High-frequency fossilized Turkish lexical misspellings
        ("yalnış", "yanlış"),
        ("yanlız", "yalnız"),
        ("herkez", "herkes"),
        ("orjinal", "orijinal"),
        ("şarz", "şarj"),
        ("süpriz", "sürpriz"),
        ("insiyatif", "inisiyatif"),
        ("muhattap", "muhatap"),
        ("entellektüel", "entelektüel"),
        ("antreman", "antrenman"),
        ("gardolap", "gardırop"),
        ("eşortman", "eşofman"),
        ("kiprik", "kirpik"),
        ("kirbit", "kibrit"),
        ("şöför", "şoför"),
        ("egzos", "egzoz"),
        ("egzost", "egzoz"),
        ("tesbit", "tespit"),
        ("tastik", "tasdik"),
        ("müsayit", "müsait"),
        ("mütahit", "müteahhit"),
        ("müteahit", "müteahhit"),
        ("taahüt", "taahhüt"),
        ("teşebbüz", "teşebbüs"),
        ("tenefüs", "teneffüs"),
        ("vejeteryan", "vejetaryen"),
        ("stajer", "stajyer"),
        ("kolleksiyon", "koleksiyon"),
        ("karekter", "karakter"),
        ("komser", "komiser"),
        ("laboratuar", "laboratuvar"),
        ("konservatuar", "konservatuvar"),
        ("repertuar", "repertuvar"),
        ("aşşağı", "aşağı"),
        ("meraba", "merhaba"),
        ("deil", "değil"),
        ("diil", "değil"),
        ("makina", "makine"),
        ("meyva", "meyve"),
        ("ünvan", "unvan"),
        ("mataryel", "materyal"),
        ("doküman", "doküman"),
        ("döküman", "doküman"),
        ("proğram", "program"),
        ("pardesü", "pardösü"),
        ("poaça", "poğaça"),
        ("pohaça", "poğaça"),
        ("maydonoz", "maydanoz"),
        ("sarmısak", "sarımsak"),
        ("karnıbahar", "karnabahar"),
        ("kurdela", "kurdele"),
        ("ataç", "ataş"),
        ("traş", "tıraş"),
        ("klavuz", "kılavuz"),
        ("klüp", "kulüp"),
        ("kravat", "kravat"),
        ("kıravat", "kravat"),
        ("grup", "grup"),
        ("gurup", "grup"),
        ("fiat", "fiyat"),
        ("seyehat", "seyahat"),
        ("hastahane", "hastane"),
        ("postahane", "postane"),
        ("eczaane", "eczane"),
        ("dersane", "dershane"),
        ("antipatik", "antipatik"),
        ("profesör", "profesör"),
        ("profösör", "profesör"),
        ("parelel", "paralel"),
        ("parlemento", "parlamento"),
        ("menapoz", "menopoz"),
        ("metod", "metot"),
        ("standart", "standart"),
        ("standard", "standart"),
        ("espiri", "espri"),
        ("espirili", "esprili"),
        ("afaroz", "aforoz"),
        ("acenta", "acente"),
        ("adele", "adale"),
        ("afilli", "afili"),
        ("ahpap", "ahbap"),
        ("akord", "akort"),
        ("aksesuar", "aksesuar"),
        ("alerji", "alerji"),
        ("allerji", "alerji"),
        ("aliminyum", "alüminyum"),
        ("alüminyum", "alüminyum"),
        ("ambalaj", "ambalaj"),
        ("amfi", "amfi"),
        ("anfi", "amfi"),
        ("antoloji", "antoloji"),
        ("aperatif", "aperitif"),
        ("aparatif", "aperitif"),
        ("arabesk", "arabesk"),
        ("araf", "araf"),
        ("arefe", "arife"),
        ("artiz", "artist"),
        ("asfalt", "asfalt"),
        ("asvalt", "asfalt"),
        ("ataşe", "ataşe"),
        ("ateşe", "ataşe"),
        ("atölye", "atölye"),
        ("avukat", "avukat"),
        ("avkat", "avukat"),
        ("baya", "bayağı"),
        ("bilimum", "bilumum"),
        ("bilader", "birader"),
        ("bisküüt", "bisküvi"),
        ("büsküvi", "bisküvi"),
        ("civa", "cıva"),
        ("cambaz", "cambaz"),
        ("canbaz", "cambaz"),
        ("cımhuriyet", "cumhuriyet"),
        ("çünki", "çünkü"),
        ("çünku", "çünkü"),
        ("dakka", "dakika"),
        ("dekarasyon", "dekorasyon"),
        ("deodarant", "deodorant"),
        ("detay", "detay"),
        ("dinazor", "dinozor"),
        ("direk", "direkt"),
        ("distribütör", "distribütör"),
        ("distiribütör", "distribütör"),
        ("doğalgaz", "doğal_gaz"),
        ("döküm", "döküm"),
        ("entegre", "entegre"),
        ("erozyon", "erozyon"),
        ("erezyon", "erozyon"),
        ("esortman", "eşofman"),
        ("farketmez", "fark_etmez"),
        ("fasülye", "fasulye"),
        ("florasan", "floresan"),
        ("gardrop", "gardırop"),
        ("gaste", "gazete"),
        ("gayriresmi", "gayriresmî"),
        ("gökdelen", "gökdelen"),
        ("gözlem", "gözlem"),
        ("güllaç", "güllaç"),
        ("hafriyat", "hafriyat"),
        ("harfiyat", "hafriyat"),
        ("halisünasyon", "halüsinasyon"),
        ("hammadde", "ham_madde"),
        ("hapşuruk", "hapşırık"),
        ("hristiyan", "Hristiyan"),
        ("hıristiyan", "Hristiyan"),
        ("hoşaf", "hoşaf"),
        ("idda", "iddia"),
        ("iddaa", "iddia"),
        ("illüzyon", "illüzyon"),
        ("ilüzyon", "illüzyon"),
        ("inkilap", "inkılap"),
        ("inziva", "inziva"),
        ("iskonto", "iskonto"),
        ("ıskonto", "iskonto"),
        ("istibarat", "istihbarat"),
        ("itibariyle", "itibarıyla"),
        ("izah", "izah"),
        ("jelatin", "jelatin"),
        ("jilet", "jilet"),
        ("jüri", "jüri"),
        ("kafeterya", "kafeterya"),
        ("kalorifer", "kalorifer"),
        ("kalörifer", "kalorifer"),
        ("kamuflaj", "kamuflaj"),
        ("kanepe", "kanepe"),
        ("kanape", "kanepe"),
        ("kapora", "kaparo"),
        ("karakol", "karakol"),
        ("karoser", "karoser"),
        ("katalog", "katalog"),
        ("katalok", "katalog"),
        ("katolog", "katalog"),
        ("kavun", "kavun"),
        ("zerafet", "zarafet"),
        ("zımbırtı", "zımbırtı"),
        // 3. Spoken / informal verbal contractions -> formal standard Turkish
        ("gidicem", "gideceğim"),
        ("gelicem", "geleceğim"),
        ("yapıcam", "yapacağım"),
        ("alıcam", "alacağım"),
        ("bakıcam", "bakacağım"),
        ("vericem", "vereceğim"),
        ("söylicem", "söyleyeceğim"),
        ("başlıcam", "başlayacağım"),
        ("icem", "eceğim"),
        ("ıcam", "acağım"),
        ("icam", "acağım"),
        ("ucam", "acağım"),
        ("ücem", "eceğim"),
        ("iyom", "iyorum"),
        ("ıyom", "ıyorum"),
        ("uyom", "uyorum"),
        ("üyom", "üyorum"),
        ("iyo", "iyor"),
        ("ıyo", "ıyor"),
        ("uyo", "uyor"),
        ("üyo", "üyor"),
        ("miyecek", "meyecek"),
        ("mıyacak", "mayacak"),
        ("miyeceğ", "meyeceğ"),
        ("mıyacağ", "mayacağ"),
        ("liyacak", "layacak"),
        ("liyecek", "leyecek"),
        ("lıyacak", "layacak"),
        ("miyo", "miyor"),
        ("mıyo", "mıyor"),
        // 4. Common Capitalized Deasciification shortcuts
        ("turkce", "Türkçe"),
        ("turkiye", "Türkiye"),
        ("istanbul", "İstanbul"),
        ("izmir", "İzmir"),
        ("ankara", "Ankara"),
        // 5. Turkish morphophonemic softening / assimilation / vowel-drop subword repairs
        ("ağızı", "ağzı"),
        ("burunu", "burnu"),
        ("karını", "karnı"),
        ("oğulu", "oğlu"),
        ("beyini", "beyni"),
        ("göğüsü", "göğsü"),
        ("omuzu", "omzu"),
        ("alını", "alnı"),
        ("boyunu", "boynu"),
        ("kitapı", "kitabı"),
        ("sokakı", "sokaktan"),
        ("kda", "kta"),
        ("kdan", "ktan"),
        ("kde", "kte"),
        ("kden", "kten"),
        ("pda", "pta"),
        ("pdan", "ptan"),
        ("pde", "pte"),
        ("pden", "pten"),
        ("çda", "çta"),
        ("çdan", "çtan"),
        ("çde", "çte"),
        ("çden", "çten"),
        ("şda", "şta"),
        ("şdan", "ştan"),
        ("şde", "şte"),
        ("şden", "şten"),
        ("fda", "fta"),
        ("fdan", "ftan"),
        ("fde", "fte"),
        ("fden", "ften"),
        ("hda", "hta"),
        ("hdan", "htan"),
        ("hde", "hte"),
        ("hden", "hten"),
        ("sda", "sta"),
        ("sdan", "stan"),
        ("sde", "ste"),
        ("sden", "sten"),
        ("tda", "tta"),
        ("tdan", "ttan"),
        ("tde", "tte"),
        ("tden", "tten"),
        ("ukca", "ukça"),
        ("ükce", "ükçe"),
        ("akca", "akça"),
        ("ekce", "ekçe"),
        ("aşca", "aşça"),
        ("eşce", "eşçe"),
        ("ışca", "ışça"),
        ("işce", "işçe"),
        ("uşca", "uşça"),
        ("üşce", "üşçe"),
        ("şdi", "şti"),
        ("şdı", "ştı"),
        ("şdu", "ştu"),
        ("şdü", "ştü"),
        ("kdı", "ktı"),
        ("kdi", "kti"),
        ("kdu", "ktu"),
        ("kdü", "ktü"),
        ("pdı", "ptı"),
        ("pdi", "pti"),
        ("pdu", "ptu"),
        ("pdü", "ptü"),
        ("çdı", "çtı"),
        ("çdi", "çti"),
        ("tdı", "ttı"),
        ("tdi", "tti"),
        ("kitablar", "kitaplar"),
        ("ağzlar", "ağızlar"),
        ("programlma", "programlama"),
        ("farketmek", "fark_etmek"),
        ("farketti", "fark_etti"),
        ("terketmek", "terk_etmek"),
        ("terketti", "terk_etti"),
        ("arzetmek", "arz_etmek"),
        ("arzetti", "arz_etti"),
        ("sarfetmek", "sarf_etmek"),
        ("sarfetti", "sarf_etti"),
        ("aktrist", "aktris"),
        ("akibet", "akıbet"),
        ("ambülans", "ambulans"),
    ]
}

fn main() {
    let t_start = Instant::now();
    let manifest_dir = Path::new(env!("CARGO_MANIFEST_DIR"));
    let default_pack = manifest_dir.join("../../rs/testdata/zemberek_lite.zmbk");
    let pack_path = std::env::var("ZEMBEREK_PACK_PATH")
        .map(std::path::PathBuf::from)
        .unwrap_or(default_pack);
    let pack_bytes = fs::read(&pack_path).expect("failed to read zemberek_lite.zmbk");
    let pack = ZmbkPack::parse(&pack_bytes, true).expect("invalid zmbk pack");
    let morph = TurkishMorphology::from_pack(&pack, MorphologyConfig::default())
        .expect("failed to load TurkishMorphology");

    println!(
        "[1/5] Loaded Zemberek 2026 Lexicon ({} items) & Materialized FST in {:.1} ms",
        morph.graph.item_count(),
        t_start.elapsed().as_secs_f64() * 1000.0
    );

    // Compute exact active masks from RPN condition bytecode
    let (active_attr_mask, _ppos_mask, _spos_mask) = compute_active_root_masks(&morph.fst);

    // Words we MUST NEVER emit into .dic (explicit misspellings / non-TDK run-ons / asciified Ext errors / archaic typo-collision stems)
    let forbidden_stems: FxHashSet<String> = [
        "yalnış", "yanlız", "herkez", "orjinal", "şarz", "süpriz", "herşey", "birşey",
        "hiçbirşey", "pekçok", "hertürlü", "hergün", "insiyatif", "muhattap", "entellektüel",
        "antreman", "gardolap", "eşortman", "kiprik", "kirbit", "şöför", "egzos", "egzost",
        "tesbit", "tastik", "müsayit", "mütahit", "müteahit", "taahüt", "teşebbüz", "tenefüs",
        "vejeteryan", "stajer", "kolleksiyon", "karekter", "komser", "laboratuar", "konservatuar",
        "repertuar", "aşşağı", "meraba", "deil", "diil",
        // Non-TDK run-ons & misspellings present in Zemberek's `non-tdk.dict` & REP table:
        "farket", "farked", "farketmek", "farketmez", "terket", "terked", "terketmek",
        "arzet", "arzed", "arzetmek", "sarfet", "sarfed", "sarfetmek", "altet", "alted", "altetmek",
        "halet", "haled", "haletmek", "başet", "başed", "başetmek", "haket", "haked", "haketmek",
        "sözet", "sözed", "sözetmek", "kabulet", "kabuled", "kabuletmek", "zaptet", "zapted", "zaptetmek",
        "aktrist", "akibet", "ambülans", "döküman", "proğram", "pardesü", "ataç",
        "klüp", "traş", "klavuz", "kıravat", "makina", "meyva", "ünvan", "mataryel",
        "fasülye", "florasan", "gardrop", "gaste", "dinazor", "erezyon", "esortman",
        "halisünasyon", "harfiyat", "hapşuruk", "idda", "iddaa", "ilüzyon", "inkilap",
        "istibarat", "kalörifer", "kanape", "katalok", "katolog", "zerafet", "espiri",
        "afaroz", "acenta", "afilli", "ahpap", "akord", "allerji", "aliminyum", "anfi",
        "aperatif", "aparatif", "arefe", "artiz", "asvalt", "avkat", "bilimum", "bilader",
        "bisküüt", "büsküvi", "canbaz", "cımhuriyet", "çünki", "çünku", "dakka",
        "dekarasyon", "deodarant", "distiribütör", "hastahane", "postahane", "eczaane", "dersane",
        "profösör", "parelel", "parlemento", "menapoz",
        "doğalgaz", "hammadde", "ardarda", "peşpeşe", "başbaşa", "yanyana", "içiçe",
        "birarada", "biraraya", "yanısıra", "hoşçakal", "hoşgeldin", "hoşgeldiniz", "sağol", "sağolun",
        // Strict ASCII degradations found in Zemberek's `Ext` (`non-tdk.dict`) that mask real typos:
        "degişim", "cizik", "geciş", "geciştir", "geciştirmek", "kacak", "ödünc", "yığıt",
        "ıklım", "bız", "hur", "eşkiya", "gayrımenkul", "gayrımüslim", "gayrıresmi", "aşcıbaşı",
        "civa", "istakoz", "ıskonto", "regülator", "simulasyon", "transistor", "armatur",
        "küsürat", "konteynır", "tereya", "mode",
        // Archaic/dialectal collision stems that mask high-frequency modern typos:
        "buru", "basari", "basar", "urun", "geçek", "şarka", "degiş", "gidicem", "gelicem", "yapıcam",
        "alıcam", "bakıcam", "vericem", "geliyom", "gidiyom", "yapıyom", "biliyom",
        "dren", "ari", "cel", "baç",
    ]
    .into_iter()
    .map(String::from)
    .collect();

    // Non-inflecting 2-letter musical notes / letter names that would cause typo collisions (`*dülerle`) if given full noun paradigms
    let bare_only_2char_nouns: FxHashSet<&'static str> = [
        "dü", "re", "mi", "fa", "la", "si", "do", "be", "ce", "fe", "ge", "ğe", "he", "je", "ka",
        "ke", "le", "me", "pe", "ra", "se", "şe", "te", "ze",
    ]
    .into_iter()
    .collect();

    // Map lowercase words that have `InverseHarmony` in Zemberek (`kemal`, `hilal`, `celal`, `cemal`, `bilal`, `resul`, `betül`, `gönül`, `istiklal`, `alkol`, `kontrol`, `gol`, `saat`, `harf`, `kalp`)
    // so capitalized ProperNouns (`Kemal`, `Hilal`, etc.) inherit `InverseHarmony` even when `person-names.dict` omitted `[A:InverseHarmony]`!
    let mut inverse_harmony_lower: FxHashSet<String> = [
        "kemal", "hilal", "celal", "cemal", "bilal", "meral", "zülal", "şevval", "istiklal",
        "resul", "betül", "gönül", "hal", "hâl", "saat", "harf", "kalp", "gol", "alkol",
        "kontrol", "petrol", "sembol", "rol", "idol", "mentol", "protokol", "festival", "moral",
        "normal", "orijinal", "terminal", "materyal", "kardinal", "liberal", "radikal", "lokal",
    ]
    .into_iter()
    .map(String::from)
    .collect();
    for id in 0..(morph.graph.item_count() as u32) {
        let it = morph.graph.item(id);
        if it.root_attrs().contains(RootAttribute::InverseHarmony) {
            inverse_harmony_lower.insert(TurkishAlphabet::to_lower_tr(&it.lemma()));
        }
    }

    // Strictly vowel-dropping common nouns whose un-dropped stem must NOT take vowel-initial suffixes
    // (e.g. `burun` -> `burnu` is valid, `*burunu` must be rejected!)
    let strict_vowel_drop_lemmas: FxHashSet<&'static str> = [
        "ağız", "burun", "karın", "alın", "boyun", "göğüs", "omuz", "beyin", "oğul", "bağır",
    ]
    .into_iter()
    .collect();

    // Scan training corpora (strictly disjoint from held-out benchmarks!)
    let t_corp = Instant::now();
    let train_counts = scan_training_corpora();
    println!(
        "[2/5] Scanned disjoint training corpora: {} unique surface tokens in {:.2} s",
        train_counts.len(),
        t_corp.elapsed().as_secs_f64()
    );

    // Collect all stem transitions from StemTrieView
    let raw_transitions = collect_all_stem_transitions(&morph);
    println!("      Raw StemTrie transitions: {}", raw_transitions.len());

    // Allowed 2-letter/3-letter ALL-CAPS abbreviations (either frequent in train corpus or well-known TR acronyms)
    let known_short_abbrvs: FxHashSet<&'static str> = [
        "AB", "ABD", "AFAD", "AGİT", "AİHM", "AKM", "AKP", "ALES", "ANAP", "AOÇ", "AÖF", "ARGE",
        "ASELSAN", "ASKİ", "ASO", "AŞ", "ATA", "ATM", "ATO", "ATP", "ATV", "AVM", "AYM", "BAE",
        "BAĞKUR", "BDDK", "BDP", "BES", "BİK", "BİST", "BJK", "BKM", "BKZ", "BLD", "BM", "BOTAŞ",
        "BTK", "BÜ", "CHP", "CIA", "CMK", "Cumhurbaşkanlığı", "ÇYDD", "DİB", "DİSK", "DMİ", "DNA",
        "DOĞAKA", "DPT", "DSİ", "DSÖ", "DTP", "EBA", "EBK", "EGO", "EGM", "EKPSS", "EMEP", "EPDK",
        "AB", "EÜ", "FB", "FBI", "FED", "FETÖ", "FIFA", "FİFA", "GAP", "GATA", "GDO", "GS",
        "GSM", "GSYH", "GÜ", "HABERTÜRK", "HDP", "HGS", "HSK", "HSYK", "THY", "İBB", "İDO",
        "İETT", "İHD", "İHH", "İMK", "İMKB", "İng", "İSO", "İŞKUR", "İSKİ", "İTO", "İTÜ", "İYİ",
        "JİTEM", "KHK", "KİK", "KİT", "KKTC", "KOBİ", "KOSGEB", "KPDS", "KPSS", "KTÜ", "KVKK",
        "KYK", "LGS", "LÖSEV", "MAK", "MEB", "MGK", "MHP", "MİT", "MKE", "MKYK", "MSB", "MSÜ",
        "MTV", "MÜSİAD", "NATO", "NTV", "ODTÜ", "OECD", "OGM", "OHAL", "OKS", "ÖSYM", "ÖTV",
        "ÖZEL", "PKK", "PFDK", "PTT", "ArGe", "REKABET", "ROKETSAN", "RTÜK", "TBMM", "TC", "TCDD",
        "TCMB", "TCK", "TDK", "TEB", "TEDAŞ", "TEFE", "TEKNOFEST", "TEMA", "TEOG", "TESK", "TFF",
        "TGK", "THK", "THY", "TİB", "TİKA", "TİM", "TİP", "TJK", "TL", "TMMOB", "TMO", "TMSF",
        "TOBB", "TOGG", "TOKİ", "TRT", "TSİ", "TSK", "TSE", "TTB", "TTK", "TUSAŞ", "TÜBA", "TÜBİTAK",
        "TÜFE", "TÜİK", "TÜPRAŞ", "TÜRKSAT", "TÜSEB", "TÜSİAD", "TÜVTÜRK", "TV", "UEFA", "UNESCO",
        "UNICEF", "UNRWA", "UYAP", "ÜFE", "ÜNİVERSİTESİ", "VAKIFBANK", "VB", "VD", "VS", "Yargıtay", "YDS",
        "YKS", "YÖK", "YSK", "YTÜ", "YY", "MAPEG", "TİHEK", "SPK", "KS",
        "DMM", "MESEM", "JASAT", "HAVELSAN", "BAYKAR", "ABB", "İZBAN", "MARMARAY", "DW", "BMW",
    ]
    .into_iter()
    .collect();

    // Common English stopwords / ordinary words present in `proper-from-corpus.dict` that must NOT be added as Turkish proper nouns
    let english_proper_blocklist: FxHashSet<&'static str> = [
        "the", "new", "world", "news", "show", "my", "all", "one", "in", "to", "me", "go", "up",
        "air", "red", "white", "west", "down", "next", "watch", "power", "quick", "will", "wall",
        "web", "now", "how", "who", "why", "what", "when", "where", "with", "from", "this", "that",
        "have", "your", "just", "like", "time", "good", "more", "make", "only", "over", "take",
        "year", "some", "into", "back", "work", "well", "play", "game", "live", "love", "life",
        "home", "page", "free", "view", "open", "city", "high", "best", "team", "club", "star",
        "gold", "black", "blue", "green", "house", "night", "light", "right", "left", "long",
        "full", "real", "true", "dark", "hard", "fast", "last", "main", "line", "part", "case",
        "week", "point", "group", "state", "force", "order", "local", "model", "press", "media",
        "video", "music", "movie", "quote", "bi", "ks", "dd", "off",
    ]
    .into_iter()
    .collect();

    // Valid TDK vowel-dropping / glottal legal `-etmek` compound verbs that carry `Ext` in Zemberek
    let allowed_ext_etmek_verbs: FxHashSet<&'static str> = [
        "rehnetmek", "celbetmek", "haczetmek", "defetmek", "menetmek", "katetmek", "vadetmek",
        "atfetmek", "feshetmek", "gasbetmek", "dercetmek", "nezretmek", "refetmek",
    ]
    .into_iter()
    .collect();

    // Map each stem string -> set of ParadigmKeys (and track synthetic ALL-CAPS `İ*` stems for NOSUGGEST)
    let mut stem_to_paradigms: BTreeMap<String, Vec<ParadigmKey>> = BTreeMap::new();
    let mut paradigm_stems_count: FxHashMap<ParadigmKey, usize> = FxHashMap::default();
    let mut all_caps_nosuggest: FxHashSet<String> = FxHashSet::default();

    for (raw_surf, target) in &raw_transitions {
        let item = morph.graph.item(target.item_id());
        let ppos = item.primary_pos();
        let spos = item.secondary_pos();
        let rattrs = item.root_attrs();

        if matches!(ppos, PrimaryPos::Punctuation | PrimaryPos::Unknown) {
            continue;
        }
        if matches!(
            spos,
            SecondaryPos::Email
                | SecondaryPos::Url
                | SecondaryPos::Mention
                | SecondaryPos::HashTag
                | SecondaryPos::Emoticon
        ) {
            continue;
        }
        // NOTE: Do NOT skip `RootAttribute::Dummy`! All 928 `Dummy` items in Zemberek are `CompoundP3sgRoot` bound stems
        // (`gözyaş`, `ipuç`, `işbirlik`, `havaalan`, `havaliman`, `başkanvekil`, `üzer`, `malvarlık`, `atasöz`, etc.)
        // guarded by `CannotTerminate` -> `FLAG_NEEDAFFIX`!
        if rattrs.contains(RootAttribute::Informal)
            || rattrs.contains(RootAttribute::Runtime)
            || rattrs.contains(RootAttribute::Unknown)
        {
            continue;
        }
        if !is_valid_tr_stem_chars(raw_surf) {
            continue;
        }

        let lemma = item.lemma();
        // Keep `Ext` words (needed for `ölçeklenebilir` and legal `Mevzuat` terms!), relying on `forbidden_stems` for archaic typo collisions.
        if strict_vowel_drop_lemmas.contains(lemma.as_ref())
            && !rattrs.contains(RootAttribute::LastVowelDrop)
        {
            // Skip duplicate non-dropping homonym for strict body-part/kinship vowel-drop nouns like `burun`
            continue;
        }

        let mat_state = target.mat_entry_state();

        if spos == SecondaryPos::Abbreviation || spos == SecondaryPos::RegularAbbreviation {
            let upper = TurkishAlphabet::to_upper_tr(&lemma);
            if upper.chars().count() < 2
                || english_proper_blocklist.contains(TurkishAlphabet::to_lower_tr(&upper).as_str())
            {
                continue;
            }
            let freq = train_counts.get(&upper).copied().unwrap_or(0)
                + train_counts.get(&format!("{upper}'nin")).copied().unwrap_or(0)
                + train_counts.get(&format!("{upper}'ın")).copied().unwrap_or(0)
                + train_counts.get(&format!("{upper}'de")).copied().unwrap_or(0)
                + train_counts.get(&format!("{upper}'da")).copied().unwrap_or(0);
            if upper.chars().count() <= 3 && freq < 5 && !known_short_abbrvs.contains(upper.as_str())
            {
                continue;
            }
            // `apostrophe_mode = 3` for ALL-CAPS abbreviations (`SGK'lı`, `CHP'li`, `TL'lik`, `TOBB'dur` — TDK requires apostrophe before `-lı`/`-lık` on abbreviations, unlike word proper nouns!)
            let pkey = ParadigmKey {
                mat_entry_state: mat_state,
                root_attrs_masked: rattrs.0 & active_attr_mask,
                primary_pos: ppos as u8,
                secondary_pos: spos as u8,
                special_root_id: item.special_root_id(),
                root_surface_id: target.root_surface_id(),
                apostrophe_mode: 3,
            };
            let entry = stem_to_paradigms.entry(upper).or_default();
            if !entry.contains(&pkey) {
                entry.push(pkey);
                *paradigm_stems_count.entry(pkey).or_insert(0) += 1;
            }
            continue;
        }

        if spos == SecondaryPos::ProperNoun {
            let cap_stem = if lemma.chars().next().is_some_and(char::is_uppercase)
                && raw_surf.chars().count() == lemma.chars().count()
            {
                // Preserve exact internal capitalization/circumflex of lemma (`AJet`, `Âdem`, `Türkiye`, `WhatsApp`)
                if *raw_surf == TurkishAlphabet::normalize_circumflex(&TurkishAlphabet::to_lower_tr(&lemma))
                {
                    lemma.to_string()
                } else {
                    TurkishAlphabet::capitalize_tr(raw_surf)
                }
            } else {
                TurkishAlphabet::capitalize_tr(raw_surf)
            };

            let low_proper = TurkishAlphabet::to_lower_tr(&cap_stem);
            if matches!(
                cap_stem.as_str(),
                "Istanbul" | "Islam" | "Islamic" | "Izmir" | "Ilk" | "Iki" | "Valla" | "Sağol"
                    | "Yaa" | "Yav" | "Eee" | "Ooo" | "Nerde" | "Nerden"
            ) || forbidden_stems.contains(&low_proper)
                || english_proper_blocklist.contains(low_proper.as_str())
            {
                continue;
            }

            let ms_meta = morph.graph.bridge.mat_state(mat_state);
            let cannot_term =
                (ms_meta.phono_bits & PhoneticAttribute::CannotTerminate.bit()) != 0;
            if cannot_term {
                continue;
            }

            let lower_cap = TurkishAlphabet::normalize_circumflex(&low_proper);
            let mut clean_phono = ms_meta.phono_bits
                & !(PhoneticAttribute::ExpectsConsonant.bit()
                    | PhoneticAttribute::ExpectsVowel.bit()
                    | PhoneticAttribute::CannotTerminate.bit());
            let mut eff_rattrs = rattrs.0 & !RootAttribute::Voicing.bit();
            if inverse_harmony_lower.contains(&lower_cap) {
                eff_rattrs |= RootAttribute::InverseHarmony.bit();
                clean_phono = (clean_phono & !PhoneticAttribute::LastVowelBack.bit())
                    | PhoneticAttribute::LastVowelFrontal.bit();
            }
            let apos_mat = morph
                .graph
                .bridge
                .resolve_entry_state(ms_meta.abstract_state_id, clean_phono)
                .unwrap_or(mat_state);

            let pkey = ParadigmKey {
                mat_entry_state: apos_mat,
                root_attrs_masked: eff_rattrs & active_attr_mask,
                primary_pos: ppos as u8,
                secondary_pos: spos as u8,
                special_root_id: item.special_root_id(),
                root_surface_id: target.root_surface_id(),
                apostrophe_mode: 1,
            };
            let deriv_pkey = ParadigmKey {
                apostrophe_mode: 2,
                ..pkey
            };

            // Emit both circumflex (`Şehitkâmil`, `Kâğıthane`, `Hakkâri`) and modern circumflex-free (`Şehitkamil`, `Kağıthane`, `Hakkari`) proper-noun variants!
            let mut prop_variants = vec![cap_stem.clone()];
            let plain_cap = TurkishAlphabet::normalize_circumflex(&cap_stem);
            if plain_cap != cap_stem {
                prop_variants.push(plain_cap);
            }
            for pv in prop_variants {
                let entry = stem_to_paradigms.entry(pv.clone()).or_default();
                if !entry.contains(&pkey) {
                    entry.push(pkey);
                    *paradigm_stems_count.entry(pkey).or_insert(0) += 1;
                }
                if !entry.contains(&deriv_pkey) {
                    entry.push(deriv_pkey);
                    *paradigm_stems_count.entry(deriv_pkey).or_insert(0) += 1;
                }
                if pv.starts_with('İ') {
                    let all_caps = TurkishAlphabet::to_upper_tr(&pv);
                    if all_caps != pv {
                        all_caps_nosuggest.insert(all_caps.clone());
                        let entry_uc = stem_to_paradigms.entry(all_caps).or_default();
                        if !entry_uc.contains(&pkey) {
                            entry_uc.push(pkey);
                            *paradigm_stems_count.entry(pkey).or_insert(0) += 1;
                        }
                    }
                }
            }
            continue;
        }

        // Common lexical stem: strictly reject `q/w/x` so lowercase English prose is never accepted!
        if has_foreign_chars(raw_surf) {
            continue;
        }
        // Note: reject free non-TDK `"standard"` & `"metod"`, while keeping `CannotTerminate` softened bound `"standard"`/`"metod"` of `"standart"`/`"metot"` (`standardı`, `metodu`)!
        let ms_meta_common = morph.graph.bridge.mat_state(mat_state);
        let is_bound_stem = (ms_meta_common.phono_bits & PhoneticAttribute::CannotTerminate.bit()) != 0;
        if (matches!(raw_surf.as_str(), "standard" | "metod") && !is_bound_stem)
            || forbidden_stems.contains(raw_surf)
            || forbidden_stems.contains(lemma.as_ref())
            || (rattrs.contains(RootAttribute::Ext)
                && lemma.ends_with("etmek")
                && !allowed_ext_etmek_verbs.contains(lemma.as_ref()))
        {
            continue;
        }
        // Allow 1-char `"o"` (pronoun/det) and `"i"` (`imek` bound stem -> `idi`, `imiş`, `ise`, `iken` guarded by `NEEDAFFIX`!)
        let char_len = raw_surf.chars().count();
        if char_len == 1 && raw_surf != "o" && !(raw_surf == "i" && ppos == PrimaryPos::Verb) {
            continue;
        }
        // Precision guard on 2-letter nouns: musical notes / letter names (`dü`, `re`, `mi`, `fa`, `la`, `si`) stay bare-only so `*dülerle` is rejected, while real 2-letter Turkish words (`ad`, `an`, `ev`, `iş`, `mı/mi/mu/mü`, `bu`, `şu`, `ne`, `ol`, `et`) inflect normally!
        if char_len == 2 && ppos == PrimaryPos::Noun && bare_only_2char_nouns.contains(raw_surf.as_str()) {
            let ms_meta = morph.graph.bridge.mat_state(mat_state);
            let cannot_term = (ms_meta.phono_bits & PhoneticAttribute::CannotTerminate.bit()) != 0;
            if !cannot_term && train_counts.get(raw_surf).copied().unwrap_or(0) >= 15 {
                stem_to_paradigms.entry(raw_surf.clone()).or_default();
            }
            continue;
        }
        // Precision guard on 3-letter `Ext` stems: skip unattested obscure 3-letter stems (`dren`, `ari`, `cel`)
        if char_len == 3
            && rattrs.contains(RootAttribute::Ext)
            && train_counts.get(raw_surf).copied().unwrap_or(0) < 20
        {
            continue;
        }

        let pkey = ParadigmKey {
            mat_entry_state: mat_state,
            root_attrs_masked: rattrs.0 & active_attr_mask,
            primary_pos: ppos as u8,
            secondary_pos: spos as u8,
            special_root_id: item.special_root_id(),
            root_surface_id: target.root_surface_id(),
            apostrophe_mode: 0,
        };

        // Add normal stem surface (unless lemma has mandatory circumflex? No, in Zemberek many circumflex words also appear without circumflex like `hükümet`/`hükûmet`, `imkan`/`imkân`, EXCEPT where circumflex distinguishes meaning like `hala`/`hâlâ`, `kar`/`kâr` — both are valid Turkish words!).
        {
            let entry = stem_to_paradigms.entry(raw_surf.clone()).or_default();
            if !entry.contains(&pkey) {
                entry.push(pkey);
                *paradigm_stems_count.entry(pkey).or_insert(0) += 1;
            }
        }

        // ALSO emit circumflex-preserving stem if `lemma` has circumflex (`hâlâ`, `kâr`, `resmî`, `dâhil`, `kâğıt`/`kâğıd`, `rüzgâr`, `hikâye`, `imkân`, `mekân`, `dükkân`, `mahkûm`, `hükûmet`, `millî`, `askerî`, `hâkim`, `hâl`)!
        if let Some(circ_surf) = reapply_circumflex(&lemma, raw_surf) {
            if !forbidden_stems.contains(&circ_surf) {
                let entry = stem_to_paradigms.entry(circ_surf).or_default();
                if !entry.contains(&pkey) {
                    entry.push(pkey);
                    *paradigm_stems_count.entry(pkey).or_insert(0) += 1;
                }
            }
        }
    }

    // Add Capitalized Institutional P3sg Proper Heads (`Üniversitesi'nde`, `Belediyesi'nin`, `Cumhuriyeti'nin`, `Bakanlığı'nın`, etc.)
    // and Month/Day names (`Ocak'ta`, `Temmuz'da`, `Ağustos'ta`, etc.)!
    add_institutional_and_numeric_proper_stems(&morph, active_attr_mask, &mut stem_to_paradigms, &mut paradigm_stems_count);

    // Add clean modern 2010-2026 Turkish lemmas & train-corpus high-confidence words missing from RootLexicon
    add_modern_turkish_lemmas(
        &morph,
        active_attr_mask,
        &train_counts,
        &forbidden_stems,
        &mut stem_to_paradigms,
        &mut paradigm_stems_count,
    );

    println!(
        "[3/5] Extracted {} unique stems across {} exact FST paradigms",
        stem_to_paradigms.len(),
        paradigm_stems_count.len()
    );

    // Collect empirical suffix frequencies per ParadigmKey by analyzing training corpus types!
    let t_emp = Instant::now();
    let empirical_paradigm_suffixes =
        mine_empirical_suffixes(&morph, &train_counts, &stem_to_paradigms, &paradigm_stems_count);
    println!(
        "      Mined empirical suffixes from training types in {:.2} s",
        t_emp.elapsed().as_secs_f64()
    );

    // Enumerate & factor suffixes for all paradigms in parallel!
    let t_fac = Instant::now();
    let layer2_defs = build_candidate_layer2_blocks();
    let num_l2 = layer2_defs.len();
    // Flag ID allocation:
    // 1..=num_l2 : Layer 2 continuation flags (`1..=31`)
    // 100..      : Paradigm Layer 1 flags
    let l2_flag_start: u16 = 1;
    let p_flag_start: u16 = 100;

    let mut sorted_paradigms: Vec<ParadigmKey> = paradigm_stems_count.keys().copied().collect();
    sorted_paradigms.sort();

    struct RawFactoredParadigm {
        key: ParadigmKey,
        needs_affix: bool,
        sfx_lines: Vec<String>,
        covered_suffixes: usize,
    }

    let raw_paradigms: Vec<RawFactoredParadigm> = sorted_paradigms
        .par_iter()
        .map(|&pkey| {
            let needs_affix = if matches!(pkey.apostrophe_mode, 1 | 2 | 3) {
                false
            } else if pkey.primary_pos == PrimaryPos::Verb as u8
                && morph.graph.bridge.mat_state(pkey.mat_entry_state).abstract_state_id
                    == morph
                        .graph
                        .bridge
                        .root_abstract_state(zemberek_pack::stem_trie::root_slot::IMEK_ROOT_S)
            {
                // `imek` root `"i"` MUST require affix (`idi`, `imiş`, `ise`, `iken` valid; bare `"i"` rejected!)
                true
            } else {
                !can_terminate_bare(&morph, pkey)
            };

            let ppos = PrimaryPos::from_u8(pkey.primary_pos).unwrap_or(PrimaryPos::Noun);
            // Lossless 3-morpheme core BFS (eliminating ~47K unattested 4-morpheme synthetic 1-fold tails while our expanded 2-fold L1+L2 system + empirical `c >= 2` suffixes cover 4–6 morpheme chains!)
            let (max_surf, max_deriv, max_bytes, max_res) = if pkey.apostrophe_mode == 1 || pkey.apostrophe_mode == 3 {
                (3u8, 2u8, 17usize, 480usize)
            } else if pkey.apostrophe_mode == 2 {
                (0u8, 0u8, 0usize, 0usize)
            } else if ppos == PrimaryPos::Verb {
                (3, 2, 18, 520)
            } else if matches!(ppos, PrimaryPos::Noun | PrimaryPos::Adjective) {
                (3, 2, 18, 460)
            } else {
                // Pronoun (`şunlardır`, `budur`, `kimdir`), Numeral (`üçte`, `dörtte`, `ikiye`, `milyondan`), PostPositive (`aittir`, `gibidir`, `kadarki`), Question (`musunuz`)
                (3, 2, 18, 420)
            };

            let mut valid_set = if max_res > 0 {
                enumerate_fst_suffixes(&morph, pkey, max_surf, max_deriv, max_bytes, max_res)
            } else {
                FxHashSet::default()
            };

            // Precision guard on Verbs: reject archaic 3sg Optative on `-abil/-ebil` (`*atlatabile`, `*kopyalayabile` — masks `-abilme`/`-abilen` typos!)
            if ppos == PrimaryPos::Verb {
                valid_set.retain(|s| !s.ends_with("abile") && !s.ends_with("ebile"));
            }

            // TDK precision filter on `apostrophe_mode == 1` (word proper nouns): strip `'cağız`, `'cık`, `'lı`, `'lık`, `'sız`, `'ca`!
            // (`apostrophe_mode == 3` for ALL-CAPS abbreviations & numbers KEEPS `'lı`/`'lık` for `SGK'lı`, `CHP'li`, `TL'lik`, `0'lık`!)
            if pkey.apostrophe_mode == 1 {
                valid_set.retain(|s| !is_disallowed_apostrophe_derivation(s));
            } else if pkey.apostrophe_mode == 3 {
                valid_set.retain(|s| !s.starts_with("'cağız") && !s.starts_with("'ceğiz") && !s.starts_with("'cık") && !s.starts_with("'cik"));
            }

            // Merge all corpus-attested suffixes for this paradigm (verified via `fst_accepts_suffix`)!
            if pkey.apostrophe_mode != 2 {
                if let Some(emp_list) = empirical_paradigm_suffixes.get(&pkey) {
                    for suf in emp_list {
                        if pkey.apostrophe_mode == 1 && is_disallowed_apostrophe_derivation(suf) {
                            continue;
                        }
                        if !valid_set.contains(suf) && fst_accepts_suffix(&morph, pkey, suf) {
                            valid_set.insert(suf.clone());
                        }
                    }
                }
            }

            let productive_l1_probes: &[&str] = if pkey.apostrophe_mode == 1 {
                &[
                    "'ı", "'i", "'u", "'ü", "'yı", "'yi", "'yu", "'yü", "'sı", "'si", "'su", "'sü",
                    "'lar", "'ler", "'ları", "'leri",
                ]
            } else if pkey.apostrophe_mode == 3 {
                &[
                    "'ı", "'i", "'u", "'ü", "'yı", "'yi", "'yu", "'yü", "'sı", "'si", "'su", "'sü",
                    "'lar", "'ler", "'ları", "'leri", "'lı", "'li", "'lu", "'lü",
                    "'lık", "'lik", "'luk", "'lük", "'lığ", "'liğ", "'luğ", "'lüğ",
                ]
            } else if pkey.apostrophe_mode == 2 {
                // TDK unquoted derivations & plurals on ProperNouns (`Türkiyeli`, `İstanbullular`, `Yargıtayca`, `Lazlardan`, `Gölcüklüye`)
                &[
                    "lı", "li", "lu", "lü", "lık", "lik", "luk", "lük", "lığ", "liğ", "luğ", "lüğ",
                    "sız", "siz", "suz", "süz", "ca", "ce", "ça", "çe",
                    "lar", "ler", "ları", "leri",
                    "cı", "ci", "cu", "cü", "çı", "çi", "çu", "çü",
                ]
            } else if ppos == PrimaryPos::Verb {
                &[
                    "yor", "iyor", "ıyor", "uyor", "üyor",
                    "dı", "di", "du", "dü", "tı", "ti", "tu", "tü",
                    "mış", "miş", "muş", "müş",
                    "acak", "ecek", "yacak", "yecek", "acağ", "eceğ", "yacağ", "yeceğ",
                    "acaklar", "ecekler", "yacaklar", "yecekler", "mayacaklar", "meyecekler", "abilecekler", "ebilecekler",
                    "dık", "dik", "duk", "dük", "tık", "tik", "tuk", "tük",
                    "dıklar", "dikler", "duklar", "dükler", "tıklar", "tikler", "tuklar", "tükler",
                    "madıklar", "medikler", "amadıklar", "emedikler", "abildikler", "ebildikler",
                    "dığ", "diğ", "duğ", "düğ", "tığ", "tiğ", "tuğ", "tüğ",
                    "ır", "ir", "ur", "ür", "ar", "er", "r",
                    "malı", "meli", "sa", "se",
                    "ma", "me", "mak", "mek", "mama", "meme", "mamak", "memek",
                    "ış", "iş", "uş", "üş", "yış", "yiş", "yuş", "yüş",
                    "ış", "ılış", "iliş", "uluş", "ülüş",
                    "ıcı", "ici", "ucu", "ücü", "yıcı", "yici", "yucu", "yücü", "macı", "meci",
                    "an", "en", "yan", "yen", "ılan", "ilen", "ulan", "ülen", "ınan", "inen", "nan", "nen",
                    "abil", "ebil", "yabil", "yebil",
                    "abilme", "ebilme", "yabilme", "yebilme", "abilmak", "ebilmek", "abilmek", "yabilmek", "yebilmek",
                    "abiliyor", "ebiliyor", "yabiliyor", "yebiliyor",
                    "abildi", "ebildi", "yabildi", "yebildi",
                    "abilmiş", "ebilmiş", "yabilmiş", "yebilmiş",
                    "abilir", "ebilir", "yabilir", "yebilir", "abilirlik", "ebilirlik", "abilirliğ", "ebilirliğ",
                    "abilecek", "ebilecek", "yabilecek", "yebilecek",
                    "abileceğ", "ebileceğ", "yabileceğ", "yebileceğ",
                    "abildik", "ebildik", "yabildik", "yebildik",
                    "abildiğ", "ebildiğ", "yabildiğ", "yebildiğ",
                    "abilelim", "ebilelim", "yabilelim", "yebilelim", "alım", "elim", "yalım", "yelim", "mayalım", "meyelim",
                    "miyor", "mıyor", "muyor", "müyor", "amıyor", "emiyor", "yamıyor", "yemiyor",
                    "madı", "medi", "amadı", "emedi", "yamadı", "yemedi",
                    "mamış", "memiş", "amamış", "ememiş",
                    "mayacak", "meyecek", "mayacağ", "meyeceğ", "amayacak", "emeyecek", "amayacağ", "emeyeceğ",
                    "amama", "ememe", "yamama", "yememe", "amamak", "ememek",
                    "madık", "medik", "madığ", "mediğ", "amadığ", "emediğ",
                    "maz", "mez", "amaz", "emez", "yamaz", "yemez", "mamazlık", "memezlik",
                    "ıl", "il", "ul", "ül", "ın", "in", "un", "ün", "n",
                    "ılıyor", "iliyor", "uluyor", "ülüyor", "ınıyor", "iniyor", "unuyor", "ünüyor", "nıyor", "niyor", "nuyor", "nüyor",
                    "ılmıyor", "ilmiyor", "ulmuyor", "ülmüyor", "ınmıyor", "inmiyor", "unmuyor", "ünmüyor", "nmıyor", "nmiyor", "nmuyor", "nmüyor",
                    "ılamıyor", "ilemiyor", "ulamıyor", "ülemiyor", "ınamıyor", "inemiyor",
                    "ıldı", "ildi", "uldu", "üldü", "ındı", "indi", "undu", "ündü", "ndı", "ndi", "ndu", "ndü",
                    "ılmadı", "ilmedi", "ulmadı", "ülmedi", "ınmadı", "inmedi", "nmadı", "nmedi",
                    "ılmış", "ilmiş", "ulmuş", "ülmüş", "ınmış", "inmiş", "unmuş", "ünmüş", "nmış", "nmiş",
                    "ılır", "ilir", "ulur", "ülür", "ınır", "inir", "unur", "ünür", "nır", "nir",
                    "ılacak", "ilecek", "ulacak", "ülecek", "ınacak", "inecek", "unacak", "ünecek", "nacak", "necek",
                    "ılacağ", "ileceğ", "ulacağ", "üleceğ", "ınacağ", "ineceğ", "unacağ", "üneceğ", "nacağ", "neceğ",
                    "ılmayacak", "ilmeyecek", "ulmayacak", "ülmeyecek", "ılmayacağ", "ilmeyeceğ", "ulmayacağ", "ülmeyeceğ",
                    "ılamayacak", "ilemeyecek", "ılamayacağ", "ilemeyeceğ", "ulamayacağ", "ülemeyeceğ",
                    "ıldık", "ildik", "ulduk", "üldük", "ıldığ", "ildiğ", "ulduğ", "üldüğ", "ındığ", "indiğ", "unduğ", "ündüğ",
                    "ılmadığ", "ilmediğ", "ulmadığ", "ülmediğ", "ınmadığ", "inmediğ", "nmadığ", "nmediğ",
                    "ılma", "ilme", "ulma", "ülme", "ınma", "inme", "unma", "ünme", "nma", "nme",
                    "ılmama", "ilmeme", "ulmama", "ülmeme", "ınmama", "inmeme", "nmama", "nmeme",
                    "ılabilme", "ilebilme", "ulabilme", "ülebilme", "ınabilme", "inebilme",
                    "ılabilir", "ilebilir", "ulabilir", "ülebilir", "ınabilir", "inebilir", "nabilir", "nebilir",
                    "ılabilecek", "ilebilecek", "ulabilecek", "ülebilecek",
                    "ılabileceğ", "ilebileceğ", "ulabileceğ", "ülebileceğ",
                    "ılabiliyor", "ilebiliyor", "ulabiliyor", "ülebiliyor",
                    "ılamaz", "ilemez", "ulamaz", "ülemez", "ınamaz", "inemez", "namaz", "nemez",
                    "ılmalı", "ilmeli", "ulmalı", "ülmeli", "ınmalı", "inmeli", "nmalı", "nmeli",
                    "ılmamalı", "ilmemeli", "ulmamalı", "ülmemeli",
                    "dır", "dir", "dur", "dür", "tır", "tir", "tur", "tür", "t",
                    "dırıyor", "diriyor", "tırıyor", "tiriyor", "tıyor", "tiyor",
                    "dırdı", "dirdi", "tırdı", "tirdi", "ttı", "tti",
                    "dırmış", "dirmiş", "tırmış", "tirmiş", "tmış", "tmiş",
                    "dıracak", "direcek", "tıracak", "tirecek",
                    "dıracağ", "direceğ", "tıracağ", "tireceğ",
                    "dırdığ", "dirdiğ", "tırdığ", "tirdiğ", "ttığ", "ttiğ", "ttuğ", "ttüğ",
                    "dırdıysa", "dirdiyse", "tırdıysa", "tirdiyse", "dıysa", "diyse", "duysa", "düyse", "tıysa", "tiyse", "tuysa", "tüyse",
                    "tırıl", "tiril", "turul", "türül", "dırıl", "diril", "durul", "dürül",
                    "tırılacağ", "tirileceğ", "turulacağ", "türüleceğ", "dırılacağ", "dirileceğ", "durulacağ", "dürüleceğ",
                    "tırıldığ", "tirildiğ", "turulduğ", "türüldüğ", "dırıldığ", "dirildiğ", "durulduğ", "dürüldüğ",
                    "tırılmış", "tirilmiş", "turulmuş", "türülmüş", "dırılmış", "dirilmiş", "durulmuş", "dürülmüş",
                    "tırılma", "tirilme", "turulma", "türülme", "dırılma", "dirilme", "durulma", "dürülme",
                    "tırılıyor", "tiriliyor", "turuluyor", "türülüyor", "dırılıyor", "diriliyor",
                    "tırılır", "tirilir", "turulur", "türülür", "dırılır", "dirilir",
                    "durttuğ", "dürttüğ", "tırttığ", "tirttiğ",
                    "mayan", "meyen", "amayan", "emeyen", "yamayan", "yemeyen",
                    "ılmayan", "ilmeyen", "ulmayan", "ülmeyen", "ınmayan", "inmeyen",
                    "tırmayan", "tirmeyen", "dırmayan", "dirmeyen",
                    "madıkça", "medikçe", "dıkça", "dikçe", "dukça", "dükçe", "tıkça", "tikçe", "tukça", "tükçe",
                    "abilmeli", "ebilmeli", "yabilmeli", "yebilmeli",
                    "tırabilir", "tirebilir", "dırabilir", "direbilir", "turabilir", "türebilir", "tabiliyor", "tebiliyor",
                    "makta", "mekte", "mamakta", "memekte", "ılmakta", "ilmekte", "ulmakta", "ülmekte",
                    "ınmakta", "inmekte", "unmakta", "ünmekte", "nmakta", "nmekte",
                    "ılmamakta", "ilmemekte", "ulmamakta", "ülmemekte", "ınmamakta", "inmemekte", "nmamakta", "nmemekte",
                    "ılamamakta", "ilememekte", "ulamamakta", "ülememekte",
                    "masa", "mese", "amasa", "emese", "yamasa", "yemese",
                    "saydı", "seydi", "masaydı", "meseydi", "amasaydı", "emeseydi",
                    "tırmalı", "tirmeli", "turmalı", "türmeli", "dırmalı", "dirmeli", "durmalı", "dürmeli",
                    "tırmıyor", "tirmiyor", "turmuyor", "türmüyor", "dırmıyor", "dirmiyor", "durmuyor", "dürmüyor",
                    "dıran", "diren", "duran", "düren", "tıran", "tiren", "turan", "türen",
                    "ndığ", "ndiğ", "nduğ", "ndüğ", "landığ", "lendiğ", "laştığ", "leştiğ",
                    "dırmayacağ", "dirmeyeceğ", "tırmayacağ", "tirmeyeceğ", "turmayacağ", "türmeyeceğ",
                    "ılmaz", "ilmez", "ulmaz", "ülmez", "ınmaz", "inmez",
                    "ınca", "ince", "unca", "ünce", "yınca", "yince", "yunca", "yünce",
                    "mayınca", "meyince", "amayınca", "emeyince", "ılamayınca", "ilemeyince",
                    "madan", "meden", "ılmadan", "ilmeden", "ulmadan", "ülmeden", "tirilmeden", "tırılmadan",
                    "ıp", "ip", "up", "üp", "yıp", "yip", "yup", "yüp", "mayıp", "meyip",
                    "arak", "erek", "yarak", "yerek", "mayarak", "meyerek", "ılarak", "ilerek", "ularak", "ülerek",
                    "dıktan", "dikten", "duktan", "dükten", "tıktan", "tikten", "tuktan", "tükten",
                    "ıldıkça", "ildikçe", "ıldıktan", "ildikten", "ulduktan", "üldükten",
                    "rken", "irken", "ırken", "urken", "ürken", "arken", "erken", "ılırken", "ilirken", "ulurken", "ülürken",
                    "masın", "mesin", "masınlar", "mesinler", "tırmasın", "tirmesin", "dırmasın", "dirmesin",
                    "dırma", "dirme", "durma", "dürme", "tırma", "tirme", "turma", "türme",
                    "dırmama", "dirmeme", "tırmama", "tirmeme",
                    "tmak", "tmek", "tırmak", "tirmek", "turmak", "türmek", "dırmak", "dirmek", "durmak", "dürmek",
                    "tırılan", "tirilen", "turulan", "türülen", "dırılan", "dirilen", "durulan", "dürülen",
                    "tırılacak", "tirilecek", "turulacak", "türülecek", "dırılacak", "dirilecek", "durulacak", "dürülecek",
                    "tırılabil", "tirilebil", "dırılabil", "dirilebil",
                    "mayabil", "meyebil", "yamayabil", "yemeyebil",
                    "mayabilir", "meyebilir", "yamayabilir", "yemeyebilir",
                    "mayabileceğ", "meyebileceğ", "yamayabileceğ", "yemeyebileceğ",
                    "ıver", "iver", "uver", "üver", "yıver", "yiver", "yuver", "yüver",
                    "ıveriyor", "iveriyor", "uveriyor", "üveriyor", "ıverdi", "iverdi", "uverdi", "üverdi", "ıverir", "iverir",
                    "ıncaya", "inceye", "uncaya", "ünceye", "yıncaya", "yinceye", "yuncaya", "yünceye",
                    "ası", "esi", "yası", "yesi",
                    "mışlığ", "mişliğ", "muşluğ", "müşlüğ",
                    "abildiyse", "ebildiyse", "yabildiyse", "yebildiyse",
                    "tırır", "tirir", "turur", "türür", "dırır", "dirir", "durur", "dürür",
                ]
            } else {
                &[
                    "lar", "ler", "ları", "leri",
                    "lık", "lik", "luk", "lük", "lığ", "liğ", "luğ", "lüğ",
                    "cı", "ci", "cu", "cü", "çı", "çi", "çu", "çü",
                    "cık", "cik", "cuk", "cük", "çık", "çik", "çuk", "çük",
                    "cığ", "ciğ", "cuğ", "cüğ", "çığ", "çiğ", "çuğ", "çüğ",
                    "cağız", "ceğiz", "çağız", "çeğiz",
                    "cılık", "cilik", "culuk", "cülük", "çılık", "çilik", "çuluk", "çülük",
                    "cılığ", "ciliğ", "culuğ", "cülüğ", "çılığ", "çiliğ", "çuluğ", "çülüğ",
                    "lı", "li", "lu", "lü", "sız", "siz", "suz", "süz",
                    "sızlık", "sizlik", "suzluk", "süzlük", "sızlığ", "sizliğ", "suzluğ", "süzlüğ",
                    "sal", "sel", "daş", "deş", "taş", "teş",
                    "sı", "si", "su", "sü", "ı", "i", "u", "ü",
                    "daki", "deki", "taki", "teki", "ki", "ndaki", "ndeki", "ındaki", "indeki", "undaki", "ündeki",
                    "sındaki", "sindeki", "sundaki", "sündeki", "mdaki", "mdeki",
                    "ca", "ce", "ça", "çe", "casına", "cesine", "çasına", "çesine",
                    "ydı", "ydi", "ydu", "ydü", "dı", "di", "du", "dü", "tı", "ti", "tu", "tü",
                    "ymış", "ymiş", "ymuş", "ymüş", "mış", "miş", "muş", "müş",
                    "ysa", "yse", "sa", "se", "taysa", "teyse", "daysa", "deyse",
                    "daydı", "deydi", "taydı", "teydi", "daymış", "deymiş", "taymış", "teymiş",
                    "sındır", "sindir", "sundur", "sündür", "ımdır", "imdir", "umdur", "ümdür",
                    "laş", "leş", "laşma", "leşme", "laşıyor", "leşiyor", "laştı", "leşti", "laşmış", "leşmiş", "laşır", "leşir",
                    "laşan", "leşen", "laşmayan", "leşmeyen", "laşmakta", "leşmekte", "laşırsa", "leşirse",
                    "laşacak", "leşecek", "laşacağ", "leşeceğ", "laştığ", "leştiğ",
                    "laştır", "leştir", "laştırma", "leştirme", "laştırıyor", "leştiriyor", "laştırdı", "leştirdi", "laştırmış", "leştirmiş",
                    "laştırır", "leştirir", "laştıracak", "leştirecek", "laştıracağ", "leştireceğ", "laştırdığ", "leştirdiğ",
                    "laştıran", "leştiren", "laştırmayan", "leştirmeyen", "laştırabil", "leştirebil", "laştırabiliyor", "leştirebiliyor",
                    "laştırıl", "leştiril", "laştırılma", "leştirilme", "laştırılıyor", "leştiriliyor", "laştırıldı", "leştirildi", "laştırılmış", "leştirilmiş",
                    "laştırılacak", "leştirilecek", "laştırılacağ", "leştirileceğ", "laştırıldığ", "leştirildiğ", "laştırılmadan", "leştirilmeden",
                    "laştırılabilir", "leştirilebilir", "laştırılabiliyor", "leştirilebiliyor",
                    "lan", "len", "lanma", "lenme", "lanıyor", "leniyor", "landı", "lendi", "lanmış", "lenmiş", "lanır", "lenir",
                    "lanan", "lenen", "lanmayan", "lenmeyen", "lanmakta", "lenmekte", "lanırsa", "lenirse",
                    "lanacak", "lenecek", "lanacağ", "leneceğ", "landığ", "lendiğ",
                    "landır", "lendir", "landırma", "lendirme", "landırmak", "lendirmek", "landırıyor", "lendiriyor", "landırdı", "lendirdi", "landıracak", "lendirecek",
                    "landırıl", "lendiril", "landırılma", "lendirilme", "landırılıyor", "lendiriliyor", "landırıldı", "lendirildi", "landırılmış", "lendirilmiş",
                    "landırılan", "lendirilen", "landırılacak", "lendirilecek", "landırılacağ", "lendirileceğ", "landırıldığ", "lendirildiğ",
                    "landırılabilir", "lendirilebilir", "landırılır", "lendirilir", "landırılmaktadır", "lendirilmektedir",
                    "sızlan", "sizlen", "sızlandır", "sizlendir", "sızlandırmak", "sizlendirmek", "sızlaş", "sizleş", "sızlaştığ", "sizleştiğ",
                ]
            };

            let mut sfx_lines = Vec::new();
            let mut covered: FxHashSet<String> = FxHashSet::default();

            for &u in productive_l1_probes {
                if u.is_empty() {
                    continue;
                }
                let mut matched_l2_flags: Vec<u16> = Vec::new();
                for (l2_idx, (_l2_name, l2_sufs)) in layer2_defs.iter().enumerate() {
                    let all_ok = l2_sufs.iter().all(|v| {
                        let combined = format!("{u}{v}");
                        valid_set.contains(&combined) || fst_accepts_suffix(&morph, pkey, &combined)
                    });
                    if all_ok {
                        matched_l2_flags.push(l2_flag_start + (l2_idx as u16));
                        for v in l2_sufs {
                            let combined = format!("{u}{v}");
                            valid_set.insert(combined.clone());
                            covered.insert(combined);
                        }
                    }
                }

                let u_standalone_valid =
                    valid_set.contains(u) || fst_accepts_suffix(&morph, pkey, u);
                if !matched_l2_flags.is_empty() {
                    if u_standalone_valid {
                        valid_set.insert(u.to_string());
                        covered.insert(u.to_string());
                    } else {
                        matched_l2_flags.push(FLAG_NEEDAFFIX);
                    }
                    let cont_str = matched_l2_flags
                        .iter()
                        .map(u16::to_string)
                        .collect::<Vec<_>>()
                        .join(",");
                    sfx_lines.push(format!("0 {u}/{cont_str} ."));
                } else if u_standalone_valid {
                    valid_set.insert(u.to_string());
                }
            }

            let mut remaining: Vec<&String> =
                valid_set.iter().filter(|s| !covered.contains(*s)).collect();
            remaining.sort();
            for s in remaining {
                sfx_lines.push(format!("0 {s} ."));
            }
            sfx_lines.sort();
            sfx_lines.dedup();

            RawFactoredParadigm {
                key: pkey,
                needs_affix,
                sfx_lines,
                covered_suffixes: valid_set.len(),
            }
        })
        .collect();

    // Stage 4A: Exact Deduplication — collapse any two ParadigmKeys whose `sfx_lines` are 100% identical!
    let mut unique_blocks_map: BTreeMap<Vec<String>, usize> = BTreeMap::new();
    let mut unique_blocks: Vec<FxHashSet<String>> = Vec::new();
    let mut pkey_to_ublock: FxHashMap<ParadigmKey, (Option<usize>, bool)> = FxHashMap::default();
    let mut total_covered_sufs = 0usize;

    for rp in raw_paradigms {
        total_covered_sufs += rp.covered_suffixes;
        if rp.sfx_lines.is_empty() {
            pkey_to_ublock.insert(rp.key, (None, rp.needs_affix));
            continue;
        }
        let uidx = if let Some(&existing) = unique_blocks_map.get(&rp.sfx_lines) {
            existing
        } else {
            let idx = unique_blocks.len();
            let as_set: FxHashSet<String> = rp.sfx_lines.iter().cloned().collect();
            unique_blocks.push(as_set);
            unique_blocks_map.insert(rp.sfx_lines, idx);
            idx
        };
        pkey_to_ublock.insert(rp.key, (Some(uidx), rp.needs_affix));
    }

    println!(
        "      Collapsed {} ParadigmKeys -> {} distinct suffix-rule sets",
        sorted_paradigms.len(),
        unique_blocks.len()
    );

    // Stage 4B: Multi-Round Greedy Orthogonal Shared Block Extraction
    // Cap `apostrophe_mode == 1` blocks at <= 6 shared flags (`+ 1` residual + `1` `apostrophe_mode = 2` = <= 8 flags -> single inline 2B leaf per ProperNoun!)
    let mut max_shared_for_ublock = vec![7usize; unique_blocks.len()];
    for (&pk, &(opt_ub, _)) in &pkey_to_ublock {
        if pk.apostrophe_mode == 1 {
            if let Some(u) = opt_ub {
                max_shared_for_ublock[u] = 6;
            }
        }
    }
    let mut assigned_shared_flags: Vec<Vec<u16>> = vec![Vec::new(); unique_blocks.len()];
    let mut compiled_paradigms: Vec<CompiledParadigm> = Vec::new();
    let mut next_flag_id = p_flag_start;

    for _round in 0..750 {
        let mut rule_holders: FxHashMap<String, Vec<usize>> = FxHashMap::default();
        for (bidx, bset) in unique_blocks.iter().enumerate() {
            if assigned_shared_flags[bidx].len() >= max_shared_for_ublock[bidx] || bset.len() < 4 {
                continue;
            }
            for r in bset {
                rule_holders.entry(r.clone()).or_default().push(bidx);
            }
        }
        let mut sig_to_rules: FxHashMap<Vec<usize>, Vec<String>> = FxHashMap::default();
        for (rule, holders) in rule_holders {
            if holders.len() >= 2 {
                sig_to_rules.entry(holders).or_default().push(rule);
            }
        }
        let mut best_cand: Option<(Vec<usize>, Vec<String>, usize)> = None;
        for (h, r) in sig_to_rules {
            let savings = (h.len() - 1) * r.len();
            if r.len() >= 3 && savings >= 12 {
                if best_cand.as_ref().map_or(true, |b| savings > b.2) {
                    best_cand = Some((h, r, savings));
                }
            }
        }

        // Wide pairwise intersection search across up to 140 blocks to eliminate residual 1-fold duplication!
        if best_cand.as_ref().map_or(true, |b| b.2 < 60) {
            let mut active_idxs: Vec<usize> = (0..unique_blocks.len())
                .filter(|&i| assigned_shared_flags[i].len() < max_shared_for_ublock[i] && unique_blocks[i].len() >= 8)
                .collect();
            active_idxs.sort_by_key(|&i| std::cmp::Reverse(unique_blocks[i].len()));
            active_idxs.truncate(140);

            for ii in 0..active_idxs.len() {
                let a = active_idxs[ii];
                for jj in (ii + 1)..active_idxs.len() {
                    let b = active_idxs[jj];
                    let inter: Vec<String> = unique_blocks[a]
                        .intersection(&unique_blocks[b])
                        .cloned()
                        .collect();
                    if inter.len() < 8 {
                        continue;
                    }
                    let holders: Vec<usize> = (0..unique_blocks.len())
                        .filter(|&k| {
                            assigned_shared_flags[k].len() < max_shared_for_ublock[k]
                                && unique_blocks[k].len() >= inter.len()
                                && inter.iter().all(|r| unique_blocks[k].contains(r))
                        })
                        .collect();
                    if holders.len() >= 2 {
                        let savings = (holders.len() - 1) * inter.len();
                        if best_cand.as_ref().map_or(true, |bc| savings > bc.2) {
                            best_cand = Some((holders, inter, savings));
                        }
                    }
                }
            }
        }

        let Some((holders, mut shared_rules, _sav)) = best_cand else {
            break;
        };
        shared_rules.sort();
        let fid = next_flag_id;
        next_flag_id += 1;

        for &bidx in &holders {
            for r in &shared_rules {
                unique_blocks[bidx].remove(r);
            }
            assigned_shared_flags[bidx].push(fid);
        }

        compiled_paradigms.push(CompiledParadigm {
            flag_id: fid,
            sfx_lines: shared_rules,
        });
    }

    // Emit residual SFX blocks for each unique_block
    let mut ublock_final_flags: Vec<Vec<u16>> = Vec::with_capacity(unique_blocks.len());
    for (bidx, bset) in unique_blocks.into_iter().enumerate() {
        let mut flags = assigned_shared_flags[bidx].clone();
        if !bset.is_empty() {
            let mut rem: Vec<String> = bset.into_iter().collect();
            rem.sort();
            let fid = next_flag_id;
            next_flag_id += 1;
            compiled_paradigms.push(CompiledParadigm {
                flag_id: fid,
                sfx_lines: rem,
            });
            flags.push(fid);
        }
        flags.sort_unstable();
        ublock_final_flags.push(flags);
    }

    let total_sfx_rules: usize = compiled_paradigms.iter().map(|p| p.sfx_lines.len()).sum();
    println!(
        "[4/5] Factored & deduplicated into {} SFX blocks ({} total SFX rules covering {} paradigm-suffix pairs = {:.1}x compression!) in {:.2} s",
        compiled_paradigms.len(),
        total_sfx_rules,
        total_covered_sufs,
        total_covered_sufs as f64 / (total_sfx_rules.max(1) as f64),
        t_fac.elapsed().as_secs_f64(),
    );

    let mut pkey_to_flags: FxHashMap<ParadigmKey, (Vec<u16>, bool)> = FxHashMap::default();
    for (&pk, &(opt_ub, needs_affix)) in &pkey_to_ublock {
        let fl = opt_ub
            .map(|u| ublock_final_flags[u].clone())
            .unwrap_or_default();
        pkey_to_flags.insert(pk, (fl, needs_affix));
    }

    // Write output files `tr-TR.aff` and `tr-TR.dic`
    let default_out_dir = manifest_dir.join("../dict");
    let out_dir_buf = std::env::var("HUNSPELL_OUT_DIR")
        .map(std::path::PathBuf::from)
        .unwrap_or(default_out_dir);
    let out_dir = out_dir_buf.as_path();
    fs::create_dir_all(out_dir).expect("failed to create dict output dir");
    let aff_path = out_dir.join("tr-TR.aff");
    let dic_path = out_dir.join("tr-TR.dic");

    write_aff_file(&aff_path, &layer2_defs, l2_flag_start, &compiled_paradigms);
    let (dic_count, uniq_af_count, max_alen) = write_dic_file(
        &dic_path,
        &stem_to_paradigms,
        &pkey_to_flags,
        &forbidden_stems,
        &all_caps_nosuggest,
    );

    println!(
        "[5/5] Wrote tr-TR.aff & tr-TR.dic: {} dic entries, {} unique AF groups, max flags/entry (alen) = {} in {:.2} s",
        dic_count,
        uniq_af_count,
        max_alen,
        t_start.elapsed().as_secs_f64()
    );
    let _ = num_l2;
}

fn add_institutional_and_numeric_proper_stems(
    morph: &TurkishMorphology<'_>,
    active_attr_mask: u32,
    stem_to_paradigms: &mut BTreeMap<String, Vec<ParadigmKey>>,
    paradigm_stems_count: &mut FxHashMap<ParadigmKey, usize>,
) {
    // 1. Capitalized P3sg institutional proper heads (`Üniversitesi'nde`, `Belediyesi'nin`, `Cumhuriyeti'nin`, `Bakanlığı'nın`, etc.)
    // Find exemplar P3sg or ProperNoun states via `UnidentifiedTokenAnalyzer` / known proper nouns:
    // Actually even cleaner: in Turkish, P3sg institutional titles (`Üniversitesi`, `Belediyesi`, `Bakanlığı`, `Cumhuriyeti`,
    // `Mahallesi`, `Hastanesi`, `Valiliği`, `Müdürlüğü`, `Başkanlığı`, `Kurumu`, `Kurulu`, `Enstitüsü`, `Fakültesi`,
    // `Derneği`, `Vakfı`, `Ligi`, `Şampiyonası`, `Olimpiyatları`, `Federasyonu`, `Bankası`, `Borsası`, `Meclisi`,
    // `Mahkemesi`, `Ajansı`, `Örgütü`, `Birliği`, `Merkezi`, `Komisyonu`, `Konseyi`, `İdaresi`, `Kuruluşu`, `Teşkilatı`,
    // `Akademisi`, `Lisesi`, `İlkokulu`, `Ortaokulu`, `Koleji`, `Stadyumu`, `Havalimanı`, `Köprüsü`, `Boğazı`, `Denizi`,
    // `Gölü`, `Dağı`, `Ovası`, `Adası`, `Yarımadası`, `Caddesi`, `Sokağı`, `Bulvarı`, `Meydanı`, `Parkı`, `Sarayı`, `Camii`, `Müzesi`)
    // take compound-P3sg apostrophe suffixes (`'nde`, `'nden`, `'ne`, `'ni`, `'nin`, `'yle` / `'nda`, `'ndan`, `'na`, `'nı`, `'nın`, `'yla`)!
    // In Zemberek, `RootAttribute::CompoundP3sg` (like `zeytinyağı`, `cezaevi`, `yüzbaşı`) models THIS EXACT P3sg pronominal-n inflection!
    let mut back_unr_p3 = None;
    let mut front_unr_p3 = None;
    let mut back_rnd_p3 = None;
    let mut front_rnd_p3 = None;

    for id in 0..(morph.graph.item_count() as u32) {
        let item = morph.graph.item(id);
        if item.primary_pos() == PrimaryPos::Noun
            && item.root_attrs().contains(RootAttribute::CompoundP3sg)
        {
            let lemma = item.lemma();
            // Find the full-form stem transition (ending in vowel ı/i/u/ü, NOT the dropped root!)
            let mut scratch = AnalyzerScratch::default();
            morph
                .graph
                .find_prefix_matches(&lemma, false, None, &mut scratch.stem_matches);
            for sm in &scratch.stem_matches {
                if sm.canonical_surface == lemma.as_ref() {
                    let pkey = ParadigmKey {
                        mat_entry_state: sm.target.mat_entry_state(),
                        root_attrs_masked: item.root_attrs().0 & active_attr_mask,
                        primary_pos: PrimaryPos::Noun as u8,
                        secondary_pos: SecondaryPos::ProperNoun as u8,
                        special_root_id: 0,
                        root_surface_id: 0,
                        apostrophe_mode: 1,
                    };
                    if lemma.ends_with('ı') && back_unr_p3.is_none() {
                        back_unr_p3 = Some(pkey);
                    } else if lemma.ends_with('i') && front_unr_p3.is_none() {
                        front_unr_p3 = Some(pkey);
                    } else if lemma.ends_with('u') && back_rnd_p3.is_none() {
                        back_rnd_p3 = Some(pkey);
                    } else if lemma.ends_with('ü') && front_rnd_p3.is_none() {
                        front_rnd_p3 = Some(pkey);
                    }
                }
            }
        }
    }

    let inst_heads = [
        "Üniversitesi", "Belediyesi", "Cumhuriyeti", "Bakanlığı", "Mahallesi", "Hastanesi",
        "Valiliği", "Müdürlüğü", "Başkanlığı", "Kurumu", "Kurulu", "Enstitüsü", "Fakültesi",
        "Derneği", "Vakfı", "Ligi", "Şampiyonası", "Olimpiyatları", "Federasyonu", "Bankası",
        "Borsası", "Meclisi", "Mahkemesi", "Ajansı", "Örgütü", "Birliği", "Merkezi", "Komisyonu",
        "Konseyi", "İdaresi", "Teşkilatı", "Akademisi", "Lisesi", "Koleji", "Stadyumu",
        "Havalimanı", "Köprüsü", "Boğazı", "Denizi", "Gölü", "Dağı", "Ovası", "Adası",
        "Yarımadası", "Caddesi", "Sokağı", "Bulvarı", "Meydanı", "Parkı", "Sarayı", "Müzesi",
        "Kanunu", "Yönetmeliği", "Tebliği", "Kararnamesi", "Sözleşmesi", "Protokolü", "Raporu",
        "Endeksi", "Oranı", "Fonu", "Programı", "Projesi", "Sistemi", "Modeli", "Ödülü",
        "Festivali", "Fuarı", "Zirvesi", "Kongresi", "Sempozyumu", "Çalıştayı", "Devletleri",
        "Milletleri", "Krallığı", "Emirlikleri", "Prensliği", "Cumhuriyetleri",
    ];

    for head in inst_heads {
        let opt_pk = if head.ends_with('ı') {
            back_unr_p3
        } else if head.ends_with('i') {
            front_unr_p3
        } else if head.ends_with('u') {
            back_rnd_p3
        } else if head.ends_with('ü') {
            front_rnd_p3
        } else {
            None
        };
        if let Some(pk) = opt_pk {
            let entry = stem_to_paradigms.entry(head.to_string()).or_default();
            if !entry.contains(&pk) {
                entry.push(pk);
                *paradigm_stems_count.entry(pk).or_insert(0) += 1;
            }
        }
    }

    // 2. Numeric stems (`0..=100`, `1900..=2035`, `1000`, etc.) with exact Turkish numeral pronunciation apostrophe inflection!
    // Find proper-noun exemplar paradigms for each Turkish number ending word (`bir`, `iki`, `üç`, `dört`, `beş`, `altı`, `yedi`, `sekiz`, `dokuz`, `on`, `yirmi`, `otuz`, `kırk`, `elli`, `altmış`, `yetmiş`, `seksen`, `doksan`, `yüz`, `bin`, `sıfır`).
    let proper_abs = morph
        .graph
        .bridge
        .root_abstract_state(zemberek_pack::stem_trie::root_slot::NOUN_PROPER_S);

    // Full integer & historical year coverage (`0..=2100`) — ~5 KB in `.bdic` prefix trie, 0 new `.aff` rules!
    let nums: Vec<u32> = (0..=2100).collect();

    for n in nums {
        let num_str = n.to_string();
        let ending_word = TurkishNumeralEndingMachine::find(&num_str);
        if ending_word.is_empty() {
            continue;
        }
        let phono = zemberek_core::compute_morphemic_attributes(&ending_word, PhonoMask::EMPTY);
        let clean_phono = phono.0
            & !(PhoneticAttribute::ExpectsConsonant.bit()
                | PhoneticAttribute::ExpectsVowel.bit()
                | PhoneticAttribute::CannotTerminate.bit());
        if let Some(mat_st) = morph.graph.bridge.resolve_entry_state(proper_abs, clean_phono) {
            let pkey = ParadigmKey {
                mat_entry_state: mat_st,
                root_attrs_masked: 0,
                primary_pos: PrimaryPos::Noun as u8,
                secondary_pos: SecondaryPos::ProperNoun as u8,
                special_root_id: 0,
                root_surface_id: 0,
                apostrophe_mode: 3, // Numbers take `'lık`/`'lı` WITH apostrophe (`0'lık`, `100'lük`, `2026'da`)!
            };
            let entry = stem_to_paradigms.entry(num_str.clone()).or_default();
            if !entry.contains(&pkey) {
                entry.push(pkey);
                *paradigm_stems_count.entry(pkey).or_insert(0) += 1;
            }
            // Exact Turkish ordinal apostrophe form (`1'inci`, `2'nci`, `3'üncü`, `4'üncü`, `6'ncı`, `100'üncü`)
            let ord_suf = match ending_word {
                "iki" | "yedi" | "yirmi" | "elli" => "nci",
                "altı" => "ncı",
                "bir" | "beş" | "sekiz" | "yetmiş" | "seksen" | "bin" => "inci",
                "üç" | "dört" | "yüz" => "üncü",
                "dokuz" | "on" | "otuz" => "uncu",
                _ => "ıncı", // sıfır, kırk, altmış, doksan
            };
            let ord_word = format!("{num_str}'{ord_suf}");
            stem_to_paradigms.entry(ord_word).or_default();
        }
    }
    // Also add 00 and 000 for split thousand tokens (`000'den`, `000'i`, `000'e`, `00'de`)
    for z in ["00", "000"] {
        for s in [
            "da", "de", "ta", "te", "dan", "den", "tan", "ten", "ün", "in", "ın", "un", "i", "e",
            "ü", "a", "lik", "lük", "lükte",
        ] {
            stem_to_paradigms.entry(format!("{z}'{s}")).or_default();
        }
    }
    // Inflecting lowercase Turkish unit abbreviations (`km'lik`, `km'ye`, `km'dir`, `cm'lik`, `kg'lık`, `dk'da`)
    for (unit_abbr, expansion) in [
        ("km", "kilometre"),
        ("cm", "santimetre"),
        ("mm", "milimetre"),
        ("mg", "miligram"),
        ("kg", "kilogram"),
        ("gr", "gram"),
        ("lt", "litre"),
        ("ml", "mililitre"),
        ("sn", "saniye"),
        ("dk", "dakika"),
        // Uppercase Turkish single-letter section/clause/group/grade stems (`A'nın`, `A'da`, `B'nin`, `B'li`, `C'ye`, `D'de`, `O'nun`)
        ("A", "a"), ("B", "be"), ("C", "ce"), ("Ç", "çe"), ("D", "de"), ("E", "e"),
        ("F", "fe"), ("G", "ge"), ("H", "he"), ("I", "ı"), ("İ", "i"), ("J", "je"),
        ("K", "ke"), ("L", "le"), ("M", "me"), ("N", "ne"), ("O", "o"), ("Ö", "ö"),
        ("P", "pe"), ("R", "re"), ("S", "se"), ("Ş", "şe"), ("T", "te"), ("U", "u"),
        ("Ü", "ü"), ("V", "ve"), ("Y", "ye"), ("Z", "ze"),
        ("X", "iks"), ("W", "dabılyu"), ("Q", "kü"),
        ("Euro", "euro"), ("No", "no"), ("NO", "no"), ("FETÖ", "fetö"),
        // Tier-3 standard alphanumeric codes (`SpellcheckWordIterator` includes `[0-9]` inside words!)
        ("5G", "beşge"), ("4G", "dörtge"), ("3G", "üçge"), ("2G", "ikige"),
        ("G20", "geyirmi"), ("G7", "geyedi"), ("G8", "gesekiz"),
        ("B12", "beoniki"), ("B6", "bealtı"), ("B1", "bebir"), ("B2", "beiki"), ("D3", "deüç"),
        ("F1", "efbir"), ("F16", "efonaltı"),
        ("U19", "uondokuz"), ("U21", "uyirmibir"), ("U17", "uonyedi"), ("U16", "uonaltı"),
        ("CO2", "ceiki"), ("H2O", "o"), ("H3N2", "neiki"), ("H1N1", "nebir"),
        ("A4", "adört"), ("A3", "aüç"), ("A5", "abeş"),
        ("D100", "deyüz"), ("E5", "ebeş"), ("TV8", "tevesekiz"), ("A101", "ayüzbir"),
        ("M1", "mebir"), ("M2", "meiki"), ("M3", "meüç"), ("M4", "medört"), ("M5", "mebeş"),
        ("3D", "üçde"), ("2D", "ikide"), ("4K", "dörtka"), ("8K", "sekizka"),
        ("MP3", "empeüç"), ("MP4", "empedört"),
    ] {
        let phono = zemberek_core::compute_morphemic_attributes(expansion, PhonoMask::EMPTY);
        let clean_phono = phono.0
            & !(PhoneticAttribute::ExpectsConsonant.bit()
                | PhoneticAttribute::ExpectsVowel.bit()
                | PhoneticAttribute::CannotTerminate.bit());
        if let Some(mat_st) = morph.graph.bridge.resolve_entry_state(proper_abs, clean_phono) {
            let pkey = ParadigmKey {
                mat_entry_state: mat_st,
                root_attrs_masked: 0,
                primary_pos: PrimaryPos::Noun as u8,
                secondary_pos: SecondaryPos::ProperNoun as u8,
                special_root_id: 0,
                root_surface_id: 0,
                apostrophe_mode: 3,
            };
            let entry = stem_to_paradigms.entry(unit_abbr.to_string()).or_default();
            if !entry.contains(&pkey) {
                entry.push(pkey);
                *paradigm_stems_count.entry(pkey).or_insert(0) += 1;
            }
        }
    }
    // Standard period-stripped Turkish title/legal/address abbreviations (Chrome's SpellcheckWordIterator strips trailing `.` from `Prof.`, `Dr.`, `Doç.`, etc.), Roman numerals (`I`..`XX`), glottal legal terms & fixed forms (`omuza`, `buruna`, `ağıza`, `adlî`, `re'sen`, `ti'ye`, `kadarki`)
    for fixed in [
        "md", "yy", "vb", "bkz", "vs", "vd", "kr", "min",
        "Dr", "Prof", "Doç", "Hz", "Av", "Uzm", "Op", "Alb", "Yrd", "Bşk",
        "Mah", "Cad", "Sk", "Sok", "Apt", "Tel", "Ltd", "Tic", "San",
        "İnş", "Müh", "Öğr", "Gör", "Arş", "İbn", "St", "Jr", "Cd",
        "No'lu", "NO'LU", "FETÖ'cü", "Euro'luk", "Sünni", "Sünniler", "Tüpraş",
        "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X",
        "XI", "XII", "XIII", "XIV", "XV", "XVI", "XVII", "XVIII", "XIX", "XX",
        "Kur'an", "Kur'an'da", "Kur'an'dan", "Kur'an'ın", "Kur'an'a", "Kur'an'ı",
        "mer'i", "mer'idir", "fer'i", "fer'idir", "nev'i", "nev'inden", "cüz'ü", "cüz'i",
        "re'sen", "Re'sen", "adlî", "Adlî", "ti'ye",
        "omuza", "buruna", "kadarki", "mucibince", "rehni", "rehnin", "rehne",
        "müdafii", "müdafiin", "müdafiine", "müdafiinin", "müdafiinden",
    ] {
        stem_to_paradigms.entry(fixed.to_string()).or_default();
    }
}

// (removed unused helper)

fn add_modern_turkish_lemmas(
    morph: &TurkishMorphology<'_>,
    active_attr_mask: u32,
    train_counts: &FxHashMap<String, u32>,
    forbidden_stems: &FxHashSet<String>,
    stem_to_paradigms: &mut BTreeMap<String, Vec<ParadigmKey>>,
    paradigm_stems_count: &mut FxHashMap<ParadigmKey, usize>,
) {
    let noun_abs = morph
        .graph
        .bridge
        .root_abstract_state(zemberek_pack::stem_trie::root_slot::NOUN_S);
    let proper_abs = morph
        .graph
        .bridge
        .root_abstract_state(zemberek_pack::stem_trie::root_slot::NOUN_PROPER_S);

    fn register_noun(
        morph: &TurkishMorphology<'_>,
        noun_abs: u16,
        active_attr_mask: u32,
        forbidden_stems: &FxHashSet<String>,
        stem_to_paradigms: &mut BTreeMap<String, Vec<ParadigmKey>>,
        paradigm_stems_count: &mut FxHashMap<ParadigmKey, usize>,
        lemma: &str,
        allow_voicing: bool,
    ) {
        if forbidden_stems.contains(lemma) || stem_to_paradigms.contains_key(lemma) {
            return;
        }
        let ends_k_p_c_t = lemma.ends_with("lık")
            || lemma.ends_with("lik")
            || lemma.ends_with("luk")
            || lemma.ends_with("lük")
            || (allow_voicing && (lemma.ends_with('k') || lemma.ends_with('p') || lemma.ends_with('ç')));

        if ends_k_p_c_t {
            let rattr = zemberek_core::RootAttrMask::EMPTY.with(RootAttribute::Voicing);
            let phono_ec = zemberek_core::compute_morphemic_attributes(lemma, PhonoMask::EMPTY)
                .with(PhoneticAttribute::ExpectsConsonant);
            if let Some(ms) = morph.graph.bridge.resolve_entry_state(noun_abs, phono_ec.0) {
                let pk = ParadigmKey {
                    mat_entry_state: ms,
                    root_attrs_masked: rattr.0 & active_attr_mask,
                    primary_pos: PrimaryPos::Noun as u8,
                    secondary_pos: SecondaryPos::None as u8,
                    special_root_id: 0,
                    root_surface_id: 0,
                    apostrophe_mode: 0,
                };
                stem_to_paradigms.entry(lemma.to_string()).or_default().push(pk);
                *paradigm_stems_count.entry(pk).or_insert(0) += 1;
            }
            // Softened bound stem (`farkındalık` -> `farkındalığ`)
            let mut chars: Vec<char> = lemma.chars().collect();
            if let Some(last) = chars.last_mut() {
                *last = match *last {
                    'k' => 'ğ',
                    'p' => 'b',
                    'ç' => 'c',
                    c => c,
                };
            }
            let soft: String = chars.into_iter().collect();
            let phono_ev = zemberek_core::compute_morphemic_attributes(&soft, PhonoMask::EMPTY)
                .with(PhoneticAttribute::ExpectsVowel)
                .with(PhoneticAttribute::CannotTerminate);
            if let Some(ms_soft) = morph.graph.bridge.resolve_entry_state(noun_abs, phono_ev.0) {
                let pk_soft = ParadigmKey {
                    mat_entry_state: ms_soft,
                    root_attrs_masked: rattr.0 & active_attr_mask,
                    primary_pos: PrimaryPos::Noun as u8,
                    secondary_pos: SecondaryPos::None as u8,
                    special_root_id: 0,
                    root_surface_id: 0,
                    apostrophe_mode: 0,
                };
                stem_to_paradigms.entry(soft).or_default().push(pk_soft);
                *paradigm_stems_count.entry(pk_soft).or_insert(0) += 1;
            }
        } else {
            let phono = zemberek_core::compute_morphemic_attributes(
                lemma,
                PhonoMask::EMPTY,
            );
            if let Some(ms) = morph.graph.bridge.resolve_entry_state(noun_abs, phono.0) {
                let pk = ParadigmKey {
                    mat_entry_state: ms,
                    root_attrs_masked: 0,
                    primary_pos: PrimaryPos::Noun as u8,
                    secondary_pos: SecondaryPos::None as u8,
                    special_root_id: 0,
                    root_surface_id: 0,
                    apostrophe_mode: 0,
                };
                stem_to_paradigms.entry(lemma.to_string()).or_default().push(pk);
                *paradigm_stems_count.entry(pk).or_insert(0) += 1;
            }
        }
    }

    // High-value modern Turkish compounds/terms & international loans established in modern Turkish
    let modern_nouns = [
        ("farkındalık", true),
        ("sürdürülebilirlik", true),
        ("yazılımcılık", true),
        ("ekosistem", false),
        ("dijitalleşme", false),
        ("çevrimiçi", false),
        ("çevrimdışı", false),
        ("kapsayıcılık", true),
        ("karbonsuzlaşma", false),
        ("biyoçeşitlilik", true),
        ("dezenformasyon", false),
        ("inovasyon", false),
        ("otomasyon", false),
        ("optimizasyon", false),
        ("regülasyon", false),
        ("simülasyon", false),
        ("transformasyon", false),
        ("entegrasyon", false),
        ("koordinasyon", false),
        ("motivasyon", false),
        ("lokasyon", false),
        ("navigasyon", false),
        ("animasyon", false),
        ("manipülasyon", false),
        ("siber", false),
        ("ölçeklenebilirlik", true),
        ("erişilebilirlik", true),
        ("kullanılabilirlik", true),
        ("taşınabilirlik", true),
        ("izlenebilirlik", true),
        ("yenilenebilirlik", true),
        ("mikroişlemci", false),
        ("yarıiletken", false),
        ("nanoteknoloji", false),
        ("biyoteknoloji", false),
        ("kripto", false),
        ("blokzincir", false),
        ("fintek", false),
        ("podcast", false),
        ("selfie", false),
        ("influencer", false),
        ("pandemi", false),
        ("varyant", false),
        ("bilişsel", false),
        ("yönetişim", false),
        ("özyönetim", false),
        ("özgüven", false),
        ("özveri", false),
        ("empati", false),
        ("empatik", false),
        ("proaktif", false),
        ("interaktif", false),
        ("algoritmik", false),
        ("paydaş", false),
        ("kapsayıcı", false),
        ("internet", false),
        ("arabulucu", false),
        ("arabuluculuk", true),
        ("uzlaştırmacı", false),
        ("uzlaştırmacılık", true),
        ("uyuşmazlık", true),
        ("yüklenici", false),
        ("kemalist", false),
        ("marksist", false),
        ("olimpiyat", false),
        ("yargıtay", false),
        ("danıştay", false),
        ("müdafi", false),
        ("mükerrir", false),
        ("muvafakat", false),
        ("tevhid", false),
        ("mutad", false),
        ("inşai", false),
        ("nolu", false),
        ("rapçi", false),
        ("metamfetamin", false),
        ("alpin", false),
        ("elebaşılık", true),
    ];
    for (w, v) in modern_nouns {
        register_noun(
            morph,
            noun_abs,
            active_attr_mask,
            forbidden_stems,
            stem_to_paradigms,
            paradigm_stems_count,
            w,
            v,
        );
    }

    // Canonical global tech/media platforms & proper nouns frequently inflected with Turkish apostrophes (`WhatsApp'ta`, `YouTube'da`, `Netflix'in`, `Windows'ta`, `Linux'ta`)
    for (prop_name, pron) in [
        ("WhatsApp", "vatsap"),
        ("Whatsapp", "vatsap"),
        ("YouTube", "yutup"),
        ("Instagram", "instagram"),
        ("Facebook", "feysbuk"),
        ("TikTok", "tiktok"),
        ("LinkedIn", "linkdin"),
        ("Telegram", "telegram"),
        ("Spotify", "spotifay"),
        ("Wikipedia", "vikipediya"),
        ("Netflix", "netfliks"),
        ("Twitter", "tivitır"),
        ("Google", "gugıl"),
        ("Android", "android"),
        ("Microsoft", "maykrosoft"),
        ("Windows", "vindovs"),
        ("Linux", "linuks"),
        ("Apple", "epıl"),
        ("Samsung", "samsung"),
        ("Huawei", "huavey"),
        ("Xiaomi", "şaomi"),
        ("OpenAI", "opıneyay"),
        ("ChatGPT", "çetcipti"),
        ("DeepSeek", "dipsik"),
        ("Ofis", "ofis"),
    ] {
        let phono = zemberek_core::compute_morphemic_attributes(pron, PhonoMask::EMPTY);
        let clean_phono = phono.0
            & !(PhoneticAttribute::ExpectsConsonant.bit()
                | PhoneticAttribute::ExpectsVowel.bit()
                | PhoneticAttribute::CannotTerminate.bit());
        if let Some(ms) = morph.graph.bridge.resolve_entry_state(proper_abs, clean_phono) {
            let pk = ParadigmKey {
                mat_entry_state: ms,
                root_attrs_masked: 0,
                primary_pos: PrimaryPos::Noun as u8,
                secondary_pos: SecondaryPos::ProperNoun as u8,
                special_root_id: 0,
                root_surface_id: 0,
                apostrophe_mode: 1,
            };
            let entry = stem_to_paradigms.entry(prop_name.to_string()).or_default();
            if !entry.contains(&pk) {
                entry.push(pk);
                *paradigm_stems_count.entry(pk).or_insert(0) += 1;
            }
        }
    }

    let mut scratch = AnalyzerScratch::default();
    let mut proper_appos_counts: FxHashMap<String, u32> = FxHashMap::default();
    let mut oov_lower_candidates: Vec<(&String, u32)> = Vec::new();

    for (tok, &cnt) in train_counts {
        if let Some(apos_idx) = tok.find('\'') {
            if apos_idx >= 2 && tok.chars().next().is_some_and(char::is_uppercase) {
                let base = &tok[..apos_idx];
                if is_valid_tr_stem_chars(base) {
                    *proper_appos_counts.entry(base.to_string()).or_insert(0) += cnt;
                }
            }
        } else if cnt >= 40
            && tok.chars().all(char::is_lowercase)
            && tok.chars().count() >= 4
            && is_valid_tr_stem_chars(tok)
            && !has_foreign_chars(tok)
            && !forbidden_stems.contains(tok)
        {
            oov_lower_candidates.push((tok, cnt));
        }
    }

    let mut added_proper = 0usize;
    for (pbase, tot_cnt) in proper_appos_counts {
        if tot_cnt < 20 || stem_to_paradigms.contains_key(&pbase) {
            continue;
        }
        // Only add proper nouns that have at least one Turkish vowel
        if !pbase.chars().any(TurkishAlphabet::is_vowel) {
            continue;
        }
        let phono = zemberek_core::compute_morphemic_attributes(
            &TurkishAlphabet::to_lower_tr(&pbase),
            PhonoMask::EMPTY,
        );
        let clean_phono = phono.0
            & !(PhoneticAttribute::ExpectsConsonant.bit()
                | PhoneticAttribute::ExpectsVowel.bit()
                | PhoneticAttribute::CannotTerminate.bit());
        if let Some(ms) = morph.graph.bridge.resolve_entry_state(proper_abs, clean_phono) {
            let pk = ParadigmKey {
                mat_entry_state: ms,
                root_attrs_masked: 0,
                primary_pos: PrimaryPos::Noun as u8,
                secondary_pos: SecondaryPos::ProperNoun as u8,
                special_root_id: 0,
                root_surface_id: 0,
                apostrophe_mode: 1,
            };
            stem_to_paradigms.entry(pbase).or_default().push(pk);
            *paradigm_stems_count.entry(pk).or_insert(0) += 1;
            added_proper += 1;
        }
    }

    let mut added_common = 0usize;
    for (w, _cnt) in oov_lower_candidates {
        if !w.chars().any(TurkishAlphabet::is_vowel) || stem_to_paradigms.contains_key(w) {
            continue;
        }
        // Check if `w` is ALREADY covered by a lowercase stem in `stem_to_paradigms` (don't skip just because a Capitalized ProperNoun like `İnternet` analyzed it!)
        let ana = morph.analyze_word(w, None, &mut scratch);
        let covered_by_lower = ana.analyses.iter().any(|a| {
            a.item.secondary_pos(&morph.graph) != SecondaryPos::ProperNoun
                && stem_to_paradigms.contains_key(a.stem())
        });
        if covered_by_lower {
            continue;
        }
        // Check morphological productivity in `train_counts`: must co-occur with >= 2 standard Turkish nominal inflections
        // so typos like `yalnış` (which rarely inflects across full paradigms compared to real nouns) or foreign English words (`the`, `and`) are rejected!
        let inflections = [
            format!("{w}lar"),
            format!("{w}ler"),
            format!("{w}da"),
            format!("{w}de"),
            format!("{w}ta"),
            format!("{w}te"),
            format!("{w}dan"),
            format!("{w}den"),
            format!("{w}tan"),
            format!("{w}ten"),
            format!("{w}ın"),
            format!("{w}in"),
            format!("{w}un"),
            format!("{w}ün"),
            format!("{w}nın"),
            format!("{w}nin"),
        ];
        let inf_hits = inflections
            .iter()
            .filter(|inf| train_counts.get(*inf).copied().unwrap_or(0) >= 5)
            .count();
        if inf_hits >= 2 {
            let voicing = w.ends_with("lık") || w.ends_with("lik") || w.ends_with("luk") || w.ends_with("lük");
            register_noun(
                morph,
                noun_abs,
                active_attr_mask,
                forbidden_stems,
                stem_to_paradigms,
                paradigm_stems_count,
                w,
                voicing,
            );
            added_common += 1;
        }
    }
    println!(
        "      Mined {} frequent proper nouns and {} productive common lemmas from train corpora",
        added_proper, added_common
    );
}

/// Analyzes frequent surface types in `train_counts` against `stem_to_paradigms` to ensure
/// every empirically attested suffix in 2019-2024 Turkish prose is included in its paradigm!
/// In TDK orthography, derivational suffixes (`-lı`, `-lık`, `-sız`, `-cık`, `-cağız`, `-ca`, `-daş`, `-cı`)
/// NEVER take an apostrophe on proper nouns (`*Kemal'cık` and `*Türk'çe` are invalid; `Türkçe` and `İstanbullu` are unquoted!).
fn is_disallowed_apostrophe_derivation(suf: &str) -> bool {
    let Some(rest) = suf.strip_prefix('\'') else {
        return false;
    };
    const BAD_PREFIXES: &[&str] = &[
        "cağız", "ceğiz", "çağız", "çeğiz",
        "cık", "cik", "cuk", "cük", "çık", "çik", "çuk", "çük",
        "cığ", "ciğ", "cuğ", "cüğ", "çığ", "çiğ", "çuğ", "çüğ",
        "lık", "lik", "luk", "lük", "lığ", "liğ", "luğ", "lüğ",
        "lı", "li", "lu", "lü",
        "sız", "siz", "suz", "süz",
        "cı", "ci", "cu", "cü", "çı", "çi", "çu", "çü",
        "ca", "ce", "ça", "çe",
        "daş", "deş", "taş", "teş",
        "sal", "sel", "gil", "msı", "msi",
    ];
    BAD_PREFIXES.iter().any(|p| rest.starts_with(p))
}

fn mine_empirical_suffixes(
    morph: &TurkishMorphology<'_>,
    train_counts: &FxHashMap<String, u32>,
    stem_to_paradigms: &BTreeMap<String, Vec<ParadigmKey>>,
    _paradigm_stems_count: &FxHashMap<ParadigmKey, usize>,
) -> FxHashMap<ParadigmKey, Vec<String>> {
    let frequent_words: Vec<&String> = train_counts
        .iter()
        .filter_map(|(w, &c)| if c >= 2 && !has_foreign_chars(w) { Some(w) } else { None })
        .collect();

    let per_chunk: Vec<FxHashMap<ParadigmKey, FxHashMap<String, u32>>> = frequent_words
        .par_chunks(4096)
        .map(|chunk| {
            let mut local: FxHashMap<ParadigmKey, FxHashMap<String, u32>> = FxHashMap::default();
            for &word in chunk {
                for (byte_idx, _) in word.char_indices().skip(1) {
                    let pref = &word[..byte_idx];
                    let suf = &word[byte_idx..];
                    if suf.is_empty() || suf.len() > 24 {
                        continue;
                    }
                    if let Some(pkeys) = stem_to_paradigms.get(pref) {
                        for &pk in pkeys {
                            if pk.apostrophe_mode == 2 {
                                continue;
                            }
                            if matches!(pk.apostrophe_mode, 1 | 3) != suf.starts_with('\'') {
                                continue;
                            }
                            if fst_accepts_suffix(morph, pk, suf) {
                                *local.entry(pk).or_default().entry(suf.to_string()).or_insert(0) += 1;
                            }
                        }
                    }
                }
            }
            local
        })
        .collect();

    let mut merged: FxHashMap<ParadigmKey, FxHashMap<String, u32>> = FxHashMap::default();
    for map in per_chunk {
        for (pk, smap) in map {
            let dst = merged.entry(pk).or_default();
            for (s, cnt) in smap {
                *dst.entry(s).or_insert(0) += cnt;
            }
        }
    }
    merged
        .into_iter()
        .map(|(pk, smap)| {
            // Maximum-recall mode: keep every FST-verified empirical suffix (`min_stems = 1`)!
            let min_stems = 1;
            let mut v: Vec<String> = smap
                .into_iter()
                .filter_map(|(s, cnt)| if cnt >= min_stems { Some(s) } else { None })
                .collect();
            v.sort();
            (pk, v)
        })
        .collect()
}

struct CompiledParadigm {
    flag_id: u16,
    sfx_lines: Vec<String>, // body lines: `0 add .` or `0 add/cont .`
}

fn write_aff_file(
    aff_path: &Path,
    layer2_defs: &[(&str, Vec<&str>)],
    l2_flag_start: u16,
    compiled_paradigms: &[CompiledParadigm],
) {
    let file = File::create(aff_path).expect("failed to create .aff");
    let mut w = BufWriter::with_capacity(1 << 20, file);

    writeln!(w, "# Modern Turkish Hunspell Dictionary (zemberek-hunspell-tr v2.0)").unwrap();
    writeln!(w, "# Generated from Zemberek-NLP 2026 Lexicon & Materialized FST").unwrap();
    writeln!(w, "# License: Apache-2.0").unwrap();
    writeln!(w, "LANG tr_TR").unwrap();
    writeln!(w, "SET UTF-8").unwrap();
    writeln!(w, "TRY aeınrlkdtmsuyboüiöşçğzgvcphfj'İI").unwrap();
    writeln!(
        w,
        "KEY qwertyuıopğü|asdfghjklşi|zxcvbnmöç|qaz|wsx|edc|rfv|tgb|yhn|ujm|ıkö|olç|pşğ|sdx"
    )
    .unwrap();
    writeln!(w, "WORDCHARS '’‘ʼ0123456789.").unwrap();
    writeln!(w, "ICONV 3").unwrap();
    writeln!(w, "ICONV ’ '").unwrap();
    writeln!(w, "ICONV ‘ '").unwrap();
    writeln!(w, "ICONV ʼ '").unwrap();
    writeln!(w, "FLAG num").unwrap();
    writeln!(w, "NEEDAFFIX {FLAG_NEEDAFFIX}").unwrap();
    writeln!(w, "FORBIDDENWORD {FLAG_FORBIDDEN}").unwrap();
    writeln!(w, "NOSUGGEST {FLAG_NOSUGGEST}").unwrap();
    writeln!(w, "MAXCPDSUGS 0").unwrap();
    writeln!(w, "NOSPLITSUGS").unwrap();
    writeln!(w).unwrap();

    writeln!(w, "MAP 7").unwrap();
    writeln!(w, "MAP iıİIîÎ").unwrap();
    writeln!(w, "MAP sşSŞ").unwrap();
    writeln!(w, "MAP gğGĞ").unwrap();
    writeln!(w, "MAP cçCÇ").unwrap();
    writeln!(w, "MAP oöOÖ").unwrap();
    writeln!(w, "MAP uüUÜûÛ").unwrap();
    writeln!(w, "MAP aâAÂ").unwrap();
    writeln!(w).unwrap();

    let reps = build_turkish_rep_table();
    // Deduplicate REP pairs preserving order
    let mut seen_rep = FxHashSet::default();
    let mut clean_reps = Vec::new();
    for (pat, rep) in reps {
        if pat != rep && seen_rep.insert((pat, rep)) {
            clean_reps.push((pat, rep));
        }
    }
    writeln!(w, "REP {}", clean_reps.len()).unwrap();
    for (pat, rep) in &clean_reps {
        writeln!(w, "REP {pat} {rep}").unwrap();
    }
    writeln!(w).unwrap();

    // Emit Layer 2 continuation SFX blocks
    for (idx, (_name, sufs)) in layer2_defs.iter().enumerate() {
        let fid = l2_flag_start + (idx as u16);
        writeln!(w, "SFX {fid} Y {}", sufs.len()).unwrap();
        for s in sufs {
            writeln!(w, "SFX {fid} 0 {s} .").unwrap();
        }
        writeln!(w).unwrap();
    }

    // Emit Layer 1 paradigm SFX blocks
    for cp in compiled_paradigms {
        let fid = cp.flag_id;
        let lines = &cp.sfx_lines;
        if lines.is_empty() {
            continue;
        }
        writeln!(w, "SFX {fid} Y {}", lines.len()).unwrap();
        for l in lines {
            writeln!(w, "SFX {fid} {l}").unwrap();
        }
        writeln!(w).unwrap();
    }
}

fn write_dic_file(
    dic_path: &Path,
    stem_to_paradigms: &BTreeMap<String, Vec<ParadigmKey>>,
    pkey_to_flags: &FxHashMap<ParadigmKey, (Vec<u16>, bool)>,
    forbidden_stems: &FxHashSet<String>,
    all_caps_nosuggest: &FxHashSet<String>,
) -> (usize, usize, usize) {
    let mut lines: Vec<String> = Vec::with_capacity(stem_to_paradigms.len() + forbidden_stems.len());
    let mut uniq_af: FxHashSet<String> = FxHashSet::default();
    let mut max_alen = 0usize;

    for (stem, pkeys) in stem_to_paradigms {
        if stem.len() > 100 || forbidden_stems.contains(stem) {
            continue;
        }
        let is_nosug = all_caps_nosuggest.contains(stem);
        let mut free_flags: Vec<u16> = Vec::new();
        let mut bound_flags: Vec<u16> = Vec::new();
        let mut is_valid_bare = pkeys.is_empty();

        for pk in pkeys {
            if let Some((fls, needs_affix)) = pkey_to_flags.get(pk) {
                if *needs_affix {
                    for &fid in fls {
                        if !bound_flags.contains(&fid) {
                            bound_flags.push(fid);
                        }
                    }
                } else {
                    is_valid_bare = true;
                    for &fid in fls {
                        if !free_flags.contains(&fid) {
                            free_flags.push(fid);
                        }
                    }
                }
            }
        }

        if is_valid_bare {
            for &bf in &bound_flags {
                if !free_flags.contains(&bf) {
                    free_flags.push(bf);
                }
            }
            free_flags.sort_unstable();
            free_flags.dedup();
            // Split across homonym lines if > 8 flags so EVERY line satisfies `alen <= 8` (`ScopedHashEntryFactory::kMaxAffixLen = 8`)!
            if free_flags.is_empty() {
                if is_nosug {
                    let fl_str = FLAG_NOSUGGEST.to_string();
                    uniq_af.insert(fl_str.clone());
                    lines.push(format!("{stem}/{fl_str}"));
                } else {
                    lines.push(stem.clone());
                }
            } else if is_nosug {
                for chunk in free_flags.chunks(7) {
                    let mut row: Vec<u16> = chunk.to_vec();
                    row.push(FLAG_NOSUGGEST);
                    max_alen = max_alen.max(row.len());
                    let fl_str = row.iter().map(u16::to_string).collect::<Vec<_>>().join(",");
                    uniq_af.insert(fl_str.clone());
                    lines.push(format!("{stem}/{fl_str}"));
                }
            } else {
                for chunk in free_flags.chunks(8) {
                    max_alen = max_alen.max(chunk.len());
                    let fl_str = chunk.iter().map(u16::to_string).collect::<Vec<_>>().join(",");
                    uniq_af.insert(fl_str.clone());
                    lines.push(format!("{stem}/{fl_str}"));
                }
            }
        } else if !bound_flags.is_empty() {
            bound_flags.sort_unstable();
            bound_flags.dedup();
            for chunk in bound_flags.chunks(7) {
                let mut row: Vec<u16> = chunk.to_vec();
                row.push(FLAG_NEEDAFFIX);
                max_alen = max_alen.max(row.len());
                let fl_str = row.iter().map(u16::to_string).collect::<Vec<_>>().join(",");
                uniq_af.insert(fl_str.clone());
                lines.push(format!("{stem}/{fl_str}"));
            }
        }
    }

    // Explicitly mark high-frequency Turkish misspellings with FORBIDDENWORD so they are never accepted or suggested
    let mut sorted_forb: Vec<&String> = forbidden_stems.iter().collect();
    sorted_forb.sort();
    for fw in sorted_forb {
        let fl_str = FLAG_FORBIDDEN.to_string();
        uniq_af.insert(fl_str.clone());
        lines.push(format!("{fw}/{fl_str}"));
    }

    let file = File::create(dic_path).expect("failed to create .dic");
    let mut w = BufWriter::with_capacity(1 << 20, file);
    writeln!(w, "{}", lines.len()).unwrap();
    for l in &lines {
        writeln!(w, "{l}").unwrap();
    }
    (lines.len(), uniq_af.len(), max_alen)
}
