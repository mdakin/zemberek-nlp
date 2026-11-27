use alloc::string::String;
use alloc::vec::Vec;
use zemberek_core::{PhoneticAttribute, PrimaryPos, RootAttribute, TurkishAlphabet};
use zemberek_pack::morph_fst::MorphFstView;
use crate::analysis::{to_lower_en, AnalysisItem, MorphemeData, SingleAnalysis};
use crate::condition_vm::{eval_condition_rpn, has_excessive_cycle, PathNode, RootCondCtx};
use crate::dynamic::DynamicStemTrie;
use crate::lexicon::LexiconGraph;
use crate::stem_matcher::{StemMatch, StemSource};

/// Reusable scratch buffers so `RuleBasedAnalyzer` performs zero arena/queue heap allocations per token.
#[derive(Clone, Debug, Default)]
pub struct AnalyzerScratch {
    pub stem_matches: Vec<StemMatch>,
    pub raw_circ_matches: Vec<StemMatch>,
    pub root_ctxs: Vec<RootCondCtx>,
    pub arena: Vec<PathNode>,
    pub cur_level: Vec<(u16, u16)>,  // (arena_node_idx, stem_candidate_idx)
    pub next_level: Vec<(u16, u16)>,
    pub finished: Vec<(u16, u16)>,
}

/// Temporary stem candidate used by `UnidentifiedTokenAnalyzer` without mutating any trie.
#[derive(Clone, Debug)]
pub struct TempStemCandidate {
    pub item: AnalysisItem,
    pub stem_surface: String,
    pub consumed_bytes: usize,
    pub mat_entry_state: u16,
    pub root_ctx: RootCondCtx,
}

/// Exact port of `zemberek.morphology.analysis.RuleBasedAnalyzer` over the Materialized Byte-FST.
#[derive(Clone, Copy, Debug)]
pub struct RuleBasedAnalyzer<'a> {
    pub graph: LexiconGraph<'a>,
    pub fst: MorphFstView<'a>,
    pub allow_informal: bool,
    pub ascii_tolerant: bool,
}

impl<'a> RuleBasedAnalyzer<'a> {
    pub fn analyze(
        &self,
        input: &str,
        dynamic: Option<&DynamicStemTrie>,
        scratch: &mut AnalyzerScratch,
    ) -> Vec<SingleAnalysis> {
        let has_circ = TurkishAlphabet::contains_circumflex(input);
        if has_circ {
            let search_input = TurkishAlphabet::normalize_circumflex(input);
            self.analyze_with_search_input(input, &search_input, true, dynamic, &[], scratch)
        } else {
            self.analyze_with_search_input(input, input, false, dynamic, &[], scratch)
        }
    }

    pub fn analyze_with_search_input(
        &self,
        input: &str,
        search_input: &str,
        has_circumflex: bool,
        dynamic: Option<&DynamicStemTrie>,
        temp_stems: &[TempStemCandidate],
        scratch: &mut AnalyzerScratch,
    ) -> Vec<SingleAnalysis> {
        self.graph
            .find_prefix_matches(search_input, self.ascii_tolerant, dynamic, &mut scratch.stem_matches);

        let check_circumflex = has_circumflex && !self.ascii_tolerant;
        if check_circumflex {
            self.graph
                .find_prefix_matches(input, false, dynamic, &mut scratch.raw_circ_matches);
            if !scratch.raw_circ_matches.is_empty() {
                for rm in &mut scratch.raw_circ_matches {
                    rm.consumed_input_bytes = search_input
                        .char_indices()
                        .nth(rm.consumed_chars)
                        .map_or(search_input.len(), |(b, _)| b);
                }
                scratch.stem_matches.append(&mut scratch.raw_circ_matches);
            }
        }

        let static_cand_count = scratch.stem_matches.len();
        let total_cand_count = static_cand_count + temp_stems.len();
        if total_cand_count == 0 {
            return Vec::new();
        }

        scratch.root_ctxs.clear();
        scratch.arena.clear();
        scratch.cur_level.clear();
        scratch.next_level.clear();
        scratch.finished.clear();

        // Build RootCondCtx for each candidate and seed initial SearchPath nodes
        for (c_idx, sm) in scratch.stem_matches.iter().enumerate() {
            let ctx = self.build_stem_root_ctx(sm, dynamic);
            scratch.root_ctxs.push(ctx);
            let mat_state = sm.target.mat_entry_state();
            let abs_state = self.graph.bridge.mat_state(mat_state).abstract_state_id;
            let flags = if self.fst.abstract_states[abs_state as usize].is_derivative() {
                PathNode::FLAG_CONTAINS_DERIVATION
            } else {
                0
            };
            let node_idx = scratch.arena.len() as u16;
            scratch.arena.push(PathNode {
                parent: PathNode::NONE,
                mat_state,
                abs_state,
                surf_id: 0,
                tail_byte_idx: sm.consumed_input_bytes as u16,
                length: 1,
                flags,
            });
            scratch.cur_level.push((node_idx, c_idx as u16));
        }

        for (t_idx, ts) in temp_stems.iter().enumerate() {
            let c_idx = static_cand_count + t_idx;
            scratch.root_ctxs.push(ts.root_ctx);
            let mat_state = ts.mat_entry_state;
            let abs_state = self.graph.bridge.mat_state(mat_state).abstract_state_id;
            let flags = if self.fst.abstract_states[abs_state as usize].is_derivative() {
                PathNode::FLAG_CONTAINS_DERIVATION
            } else {
                0
            };
            let node_idx = scratch.arena.len() as u16;
            scratch.arena.push(PathNode {
                parent: PathNode::NONE,
                mat_state,
                abs_state,
                surf_id: 0,
                tail_byte_idx: ts.consumed_bytes as u16,
                length: 1,
                flags,
            });
            scratch.cur_level.push((node_idx, c_idx as u16));
        }

        // Exact level-by-level BFS matching `RuleBasedAnalyzer.search`
        let in_len = search_input.len();
        while !scratch.cur_level.is_empty() {
            if scratch.cur_level.len() > 30 {
                scratch
                    .cur_level
                    .retain(|&(n_idx, _)| !has_excessive_cycle(&scratch.arena, n_idx as usize));
            }
            scratch.next_level.clear();

            for i in 0..scratch.cur_level.len() {
                let (node_idx, cand_idx) = scratch.cur_level[i];
                let path = scratch.arena[node_idx as usize];
                let tail_byte = path.tail_byte_idx as usize;
                let root_ctx = scratch.root_ctxs[cand_idx as usize];

                if tail_byte >= in_len {
                    let ms_meta = self.graph.bridge.mat_state(path.mat_state);
                    let is_term = self.fst.abstract_states[path.abs_state as usize].is_terminal();
                    let cannot_term =
                        (ms_meta.phono_bits & PhoneticAttribute::CannotTerminate.bit()) != 0;
                    if is_term && !cannot_term {
                        scratch.finished.push((node_idx, cand_idx));
                        continue;
                    }
                    // `advanceEmpty`: only lexical empty transitions (`!hasSurfaceForm()`) can fire
                    for &arc in self.fst.outgoing_arcs(path.mat_state) {
                        if arc.has_surface_form() {
                            continue;
                        }
                        if !self.allow_informal && arc.is_informal() {
                            continue;
                        }
                        if !eval_condition_rpn(
                            &self.fst,
                            arc.cond_prog_idx(),
                            root_ctx,
                            &scratch.arena,
                            node_idx as usize,
                            false,
                        ) {
                            continue;
                        }
                        self.push_next_node(path, node_idx, cand_idx, arc, tail_byte, scratch);
                    }
                    continue;
                }

                let tail = &search_input[tail_byte..];

                for &arc in self.fst.outgoing_arcs(path.mat_state) {
                    if !self.allow_informal && arc.is_informal() {
                        continue;
                    }
                    if !arc.has_surface_form() {
                        if !eval_condition_rpn(
                            &self.fst,
                            arc.cond_prog_idx(),
                            root_ctx,
                            &scratch.arena,
                            node_idx as usize,
                            true,
                        ) {
                            continue;
                        }
                        self.push_next_node(path, node_idx, cand_idx, arc, tail_byte, scratch);
                        continue;
                    }

                    let surf = self.fst.surface_str(arc.surf_id());
                    let consumed = if surf.is_empty() {
                        0usize
                    } else if !self.ascii_tolerant {
                        if tail.as_bytes()[0] != surf.as_bytes()[0] || !tail.starts_with(surf) {
                            continue;
                        }
                        surf.len()
                    } else {
                        let Some(c_bytes) = match_prefix_ignore_diacritics_bytes(tail, surf) else {
                            continue;
                        };
                        c_bytes
                    };

                    if !eval_condition_rpn(
                        &self.fst,
                        arc.cond_prog_idx(),
                        root_ctx,
                        &scratch.arena,
                        node_idx as usize,
                        true,
                    ) {
                        continue;
                    }

                    self.push_next_node(
                        path,
                        node_idx,
                        cand_idx,
                        arc,
                        tail_byte + consumed,
                        scratch,
                    );
                }
            }

            core::mem::swap(&mut scratch.cur_level, &mut scratch.next_level);
        }

        // Build SingleAnalysis results + circumflex filter + deduplication
        let mut results: Vec<SingleAnalysis> = Vec::with_capacity(scratch.finished.len());
        for &(end_node_idx, cand_idx) in &scratch.finished {
            let c_idx = cand_idx as usize;
            let (raw_item, stem_surf) = if c_idx < static_cand_count {
                let sm = &scratch.stem_matches[c_idx];
                let item = match sm.source {
                    StemSource::Static => AnalysisItem::Static(sm.target.item_id()),
                    StemSource::Dynamic => {
                        let d = dynamic.unwrap().get_item(sm.target.item_id()).unwrap();
                        AnalysisItem::Runtime {
                            lemma: d.lemma.clone(),
                            root: d.root.clone(),
                            primary_pos: d.primary_pos,
                            secondary_pos: d.secondary_pos,
                            root_attrs: d.root_attrs,
                            homonym_index: 0,
                        }
                    }
                };
                (item, sm.canonical_surface.as_str())
            } else {
                let ts = &temp_stems[c_idx - static_cand_count];
                (ts.item.clone(), ts.stem_surface.as_str())
            };

            let resolved_item = self.resolve_dummy_reference(raw_item);
            if check_circumflex && !self.matches_circumflex(&resolved_item, stem_surf, input) {
                continue;
            }
            let analysis = self.build_single_analysis(
                resolved_item,
                stem_surf,
                &scratch.arena,
                end_node_idx as usize,
            );
            results.push(analysis);
        }

        if results.len() > 1 {
            self.deduplicate_analyses(&mut results);
        }
        results
    }

    #[inline]
    fn push_next_node(
        &self,
        parent_node: PathNode,
        parent_idx: u16,
        cand_idx: u16,
        arc: zemberek_pack::morph_fst::MatFstArc,
        next_tail_byte: usize,
        scratch: &mut AnalyzerScratch,
    ) {
        if scratch.arena.len() >= (PathNode::NONE as usize) {
            return;
        }
        let target_mat = arc.target_mat_state();
        let target_abs = self.graph.bridge.mat_state(target_mat).abstract_state_id;
        let mut flags = parent_node.flags;
        if self.fst.abstract_states[target_abs as usize].is_derivative() {
            flags |= PathNode::FLAG_CONTAINS_DERIVATION;
        }
        if !arc.is_epsilon() {
            flags |= PathNode::FLAG_CONTAINS_SUFFIX_SURFACE;
        }
        let new_idx = scratch.arena.len() as u16;
        scratch.arena.push(PathNode {
            parent: parent_idx,
            mat_state: target_mat,
            abs_state: target_abs,
            surf_id: arc.surf_id(),
            tail_byte_idx: next_tail_byte as u16,
            length: parent_node.length.saturating_add(1),
            flags,
        });
        scratch.next_level.push((new_idx, cand_idx));
    }

    fn build_stem_root_ctx(&self, sm: &StemMatch, dynamic: Option<&DynamicStemTrie>) -> RootCondCtx {
        match sm.source {
            StemSource::Static => {
                let raw = self.graph.lexicon.item(sm.target.item_id() as usize);
                RootCondCtx {
                    root_attrs: raw.root_attrs,
                    primary_pos: raw.primary_pos,
                    secondary_pos: raw.secondary_pos,
                    special_root_id: raw.special_root_id,
                    root_surface_id: sm.target.root_surface_id(),
                }
            }
            StemSource::Dynamic => {
                let d = dynamic.unwrap().get_item(sm.target.item_id()).unwrap();
                RootCondCtx {
                    root_attrs: d.root_attrs.0,
                    primary_pos: d.primary_pos as u8,
                    secondary_pos: d.secondary_pos as u8,
                    special_root_id: 0,
                    root_surface_id: sm.target.root_surface_id(),
                }
            }
        }
    }

    fn resolve_dummy_reference(&self, item: AnalysisItem) -> AnalysisItem {
        if let AnalysisItem::Static(id) = item {
            let item_ref = self.graph.item(id);
            if item_ref.root_attrs().contains(RootAttribute::Dummy) {
                if let Some(ref_id) = item_ref.reference_item_id() {
                    return AnalysisItem::Static(ref_id);
                }
            }
        }
        item
    }

    pub(crate) fn build_single_analysis(
        &self,
        item: AnalysisItem,
        stem_surface: &str,
        arena: &[PathNode],
        end_idx: usize,
    ) -> SingleAnalysis {
        let len = arena[end_idx].length as usize;
        let mut chain = [0usize; 48];
        let mut cur = end_idx;
        for pos in (0..len).rev() {
            if pos < chain.len() {
                chain[pos] = cur;
            }
            let p = arena[cur].parent;
            if p == PathNode::NONE {
                break;
            }
            cur = p as usize;
        }

        let nom_id = self.fst.header.nom_morpheme_id;
        let pnon_id = self.fst.header.pnon_morpheme_id;
        let mut morphemes = Vec::with_capacity(len);
        let mut group_boundaries = Vec::with_capacity(4);
        group_boundaries.push(0u8);

        for (step, &node_idx) in chain[..len.min(chain.len())].iter().enumerate() {
            let node = arena[node_idx];
            let m_id = self.fst.abstract_states[node.abs_state as usize].morpheme_id;
            if m_id == nom_id || m_id == pnon_id {
                continue;
            }
            if self.fst.morphemes[m_id as usize].is_derivational() {
                group_boundaries.push(morphemes.len() as u8);
            }
            let surf = if step == 0 {
                String::from(stem_surface)
            } else {
                String::from(self.fst.surface_str(node.surf_id))
            };
            morphemes.push(MorphemeData {
                morpheme_id: m_id,
                surface: surf,
            });
        }

        SingleAnalysis {
            item,
            morphemes,
            group_boundaries,
        }
    }

    fn matches_circumflex(
        &self,
        resolved_item: &AnalysisItem,
        stem_surf: &str,
        input_with_circumflex: &str,
    ) -> bool {
        let stem_char_len = stem_surf.chars().count();
        let mut lemma_chars: Option<Vec<char>> = None;

        for (i, c) in input_with_circumflex.chars().enumerate() {
            if TurkishAlphabet::normalize_circumflex_char(c) == c {
                continue;
            }
            if i >= stem_char_len {
                return false;
            }
            let l_chars = lemma_chars.get_or_insert_with(|| {
                let ppos = resolved_item.primary_pos(&self.graph);
                if ppos == PrimaryPos::Punctuation {
                    return resolved_item.lemma(&self.graph).chars().collect();
                }
                let norm_l = resolved_item.normalized_lemma(&self.graph);
                let lowered = if resolved_item
                    .root_attrs(&self.graph)
                    .contains(RootAttribute::LocaleEn)
                {
                    to_lower_en(&norm_l)
                } else {
                    TurkishAlphabet::to_lower_tr(&norm_l)
                };
                TurkishAlphabet::normalize_apostrophe(&lowered)
                    .chars()
                    .filter(|&ch| ch != '.' && ch != '-' && ch != '\'')
                    .collect()
            });
            if i >= l_chars.len() || l_chars[i] != c {
                return false;
            }
        }
        true
    }

    fn deduplicate_analyses(&self, analyses: &mut Vec<SingleAnalysis>) {
        let mut i = 1usize;
        while i < analyses.len() {
            let mut removed = false;
            for j in 0..i {
                if self.is_same_analysis(&analyses[j], &analyses[i]) {
                    let idx_cand = analyses[i].item.homonym_index(&self.graph);
                    let idx_exist = analyses[j].item.homonym_index(&self.graph);
                    if idx_cand < idx_exist {
                        analyses[j] = analyses[i].clone();
                    }
                    analyses.remove(i);
                    removed = true;
                    break;
                }
            }
            if !removed {
                i += 1;
            }
        }
    }

    fn is_same_analysis(&self, a: &SingleAnalysis, b: &SingleAnalysis) -> bool {
        a.group_count() == b.group_count()
            && a.morphemes == b.morphemes
            && a.item.primary_pos(&self.graph) == b.item.primary_pos(&self.graph)
            && a.item.secondary_pos(&self.graph) == b.item.secondary_pos(&self.graph)
            && a.item.lemma(&self.graph) == b.item.lemma(&self.graph)
    }
}

/// Codepoint-aware port of `TurkishAlphabet.startsWithIgnoreDiacritics(tail, 0, surface)`,
/// returning the exact byte count consumed from `tail`!
#[inline]
fn match_prefix_ignore_diacritics_bytes(tail: &str, surface: &str) -> Option<usize> {
    let mut tail_it = tail.chars();
    let mut consumed_bytes = 0usize;
    for surf_ch in surface.chars() {
        let tc = tail_it.next()?;
        if !TurkishAlphabet::is_ascii_equal(tc, surf_ch) {
            return None;
        }
        consumed_bytes += tc.len_utf8();
    }
    Some(consumed_bytes)
}
