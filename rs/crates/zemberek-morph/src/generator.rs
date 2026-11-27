use alloc::string::String;
use alloc::vec::Vec;
use zemberek_core::{PhoneticAttribute, RootAttribute};
use zemberek_pack::morph_fst::MorphFstView;
use zemberek_pack::stem_trie::PackedStemTarget;
use crate::analysis::{AnalysisItem, SingleAnalysis};
use crate::analyzer::RuleBasedAnalyzer;
use crate::condition_vm::{eval_condition_rpn, PathNode, RootCondCtx};
use crate::dynamic::DynamicStemTrie;
use crate::lexicon::LexiconGraph;

#[derive(Clone, Debug)]
pub struct GenerationResult {
    pub surface: String,
    pub analysis: SingleAnalysis,
}

/// Exact port of `zemberek.morphology.generator.WordGenerator` over the Materialized Byte-FST.
#[derive(Clone, Copy, Debug)]
pub struct WordGenerator<'a> {
    pub graph: LexiconGraph<'a>,
    pub fst: MorphFstView<'a>,
    pub allow_informal: bool,
}

impl<'a> WordGenerator<'a> {
    pub fn find_morpheme_id(&self, name: &str) -> Option<u8> {
        (0..(self.fst.header.morpheme_count as u8)).find(|&id| self.fst.morpheme_id_str(id) == name)
    }

    pub fn generate_by_names(&self, item_id: u32, morpheme_names: &[&str]) -> Vec<GenerationResult> {
        let mut ids = Vec::with_capacity(morpheme_names.len());
        for &n in morpheme_names {
            let Some(id) = self.find_morpheme_id(n) else {
                return Vec::new();
            };
            ids.push(id);
        }
        self.generate(item_id, &ids)
    }

    /// Generates formal/informal surface forms from `(item_id, morpheme_ids)`.
    pub fn generate(&self, item_id: u32, morpheme_ids: &[u8]) -> Vec<GenerationResult> {
        let candidates = self.stem_transitions_for_item(item_id);
        if candidates.is_empty() {
            return Vec::new();
        }

        let raw_item = self.graph.lexicon.item(item_id as usize);
        let mut arena: Vec<PathNode> = Vec::with_capacity(64);
        // (arena_node_idx, cand_idx, consumed_morpheme_count)
        let mut cur_paths: Vec<(u16, u8, u8)> = Vec::with_capacity(candidates.len() * 2);
        let mut next_paths: Vec<(u16, u8, u8)> = Vec::new();
        let mut finished: Vec<(u16, u8)> = Vec::new();

        for (c_idx, &(surf, target)) in candidates.iter().enumerate() {
            let _ = surf;
            let mat_state = target.mat_entry_state();
            let abs_state = self.graph.bridge.mat_state(mat_state).abstract_state_id;
            let root_morph = self.fst.abstract_states[abs_state as usize].morpheme_id;
            let start_m_pos = if !morpheme_ids.is_empty() && morpheme_ids[0] == root_morph {
                1u8
            } else {
                0u8
            };
            let flags = if self.fst.abstract_states[abs_state as usize].is_derivative() {
                PathNode::FLAG_CONTAINS_DERIVATION
            } else {
                0
            };
            let node_idx = arena.len() as u16;
            arena.push(PathNode {
                parent: PathNode::NONE,
                mat_state,
                abs_state,
                surf_id: 0,
                tail_byte_idx: 0,
                length: 1,
                flags,
            });
            cur_paths.push((node_idx, c_idx as u8, start_m_pos));
        }

        let total_m = morpheme_ids.len() as u8;
        while !cur_paths.is_empty() {
            next_paths.clear();
            for &(node_idx, c_idx, m_pos) in &cur_paths {
                let path = arena[node_idx as usize];
                let (_, target) = candidates[c_idx as usize];
                let root_ctx = RootCondCtx {
                    root_attrs: raw_item.root_attrs,
                    primary_pos: raw_item.primary_pos,
                    secondary_pos: raw_item.secondary_pos,
                    special_root_id: raw_item.special_root_id,
                    root_surface_id: target.root_surface_id(),
                };

                if m_pos == total_m {
                    let ms_meta = self.graph.bridge.mat_state(path.mat_state);
                    let is_term = self.fst.abstract_states[path.abs_state as usize].is_terminal();
                    let cannot_term =
                        (ms_meta.phono_bits & PhoneticAttribute::CannotTerminate.bit()) != 0;
                    if is_term && !cannot_term {
                        finished.push((node_idx, c_idx));
                        continue;
                    }
                }

                for &arc in self.fst.outgoing_arcs(path.mat_state) {
                    if !self.allow_informal && arc.is_informal() {
                        continue;
                    }
                    if m_pos == total_m && arc.has_surface_form() {
                        continue;
                    }

                    let to_mat = arc.target_mat_state();
                    let to_abs = self.graph.bridge.mat_state(to_mat).abstract_state_id;
                    let to_morph = self.fst.abstract_states[to_abs as usize].morpheme_id;

                    // Morpheme match check (`GenerationPath.matches`)
                    if arc.has_surface_form()
                        && (m_pos >= total_m || to_morph != morpheme_ids[m_pos as usize])
                    {
                        continue;
                    }

                    // Note: in `WordGenerator`, `tail` is `" "` so `has_tail` is ALWAYS `true`!
                    if !eval_condition_rpn(
                        &self.fst,
                        arc.cond_prog_idx(),
                        root_ctx,
                        &arena,
                        node_idx as usize,
                        true,
                    ) {
                        continue;
                    }

                    let next_m_pos = if arc.is_epsilon() {
                        if m_pos < total_m && to_morph == morpheme_ids[m_pos as usize] {
                            m_pos + 1
                        } else {
                            m_pos
                        }
                    } else {
                        m_pos + 1
                    };

                    if arena.len() >= (PathNode::NONE as usize) {
                        continue;
                    }
                    let mut flags = path.flags;
                    if self.fst.abstract_states[to_abs as usize].is_derivative() {
                        flags |= PathNode::FLAG_CONTAINS_DERIVATION;
                    }
                    if !arc.is_epsilon() {
                        flags |= PathNode::FLAG_CONTAINS_SUFFIX_SURFACE;
                    }
                    let new_idx = arena.len() as u16;
                    arena.push(PathNode {
                        parent: node_idx,
                        mat_state: to_mat,
                        abs_state: to_abs,
                        surf_id: arc.surf_id(),
                        tail_byte_idx: 0,
                        length: path.length.saturating_add(1),
                        flags,
                    });
                    next_paths.push((new_idx, c_idx, next_m_pos));
                }
            }
            core::mem::swap(&mut cur_paths, &mut next_paths);
        }

        let helper = RuleBasedAnalyzer {
            graph: self.graph,
            fst: self.fst,
            allow_informal: self.allow_informal,
            ascii_tolerant: false,
        };

        let resolved_item_id = if self
            .graph
            .item(item_id)
            .root_attrs()
            .contains(RootAttribute::Dummy)
        {
            self.graph
                .item(item_id)
                .reference_item_id()
                .unwrap_or(item_id)
        } else {
            item_id
        };

        let mut results = Vec::with_capacity(finished.len());
        for (end_node, c_idx) in finished {
            let (stem_surf, _) = candidates[c_idx as usize];
            let analysis = helper.build_single_analysis(
                AnalysisItem::Static(resolved_item_id),
                stem_surf,
                &arena,
                end_node as usize,
            );
            let surface = analysis.surface_form();
            results.push(GenerationResult { surface, analysis });
        }
        results
    }

    /// Generates surface forms for either a static lexicon item or a runtime `DynamicStemTrie` user word.
    pub fn generate_for_analysis_item(
        &self,
        item: &AnalysisItem,
        morpheme_ids: &[u8],
        dynamic: Option<&DynamicStemTrie>,
    ) -> Vec<GenerationResult> {
        match item {
            AnalysisItem::Static(item_id) => self.generate(*item_id, morpheme_ids),
            AnalysisItem::Runtime {
                lemma,
                primary_pos,
                secondary_pos,
                root_attrs,
                ..
            } => {
                let Some(dyn_trie) = dynamic else {
                    return Vec::new();
                };
                let Some(dyn_item) = dyn_trie.items().iter().find(|it| {
                    it.lemma == *lemma
                        && it.primary_pos == *primary_pos
                        && it.secondary_pos == *secondary_pos
                }) else {
                    return Vec::new();
                };
                let mut candidates: Vec<(&str, PackedStemTarget)> = Vec::new();
                for (surf, targets) in dyn_trie.stems() {
                    for &t in targets {
                        if t.item_id() == dyn_item.item_id {
                            candidates.push((surf.as_str(), t));
                        }
                    }
                }
                if candidates.is_empty() {
                    return Vec::new();
                }

                let mut arena: Vec<PathNode> = Vec::with_capacity(64);
                let mut cur_paths: Vec<(u16, u8, u8)> = Vec::with_capacity(candidates.len() * 2);
                let mut next_paths: Vec<(u16, u8, u8)> = Vec::new();
                let mut finished: Vec<(u16, u8)> = Vec::new();

                for (c_idx, &(_, target)) in candidates.iter().enumerate() {
                    let mat_state = target.mat_entry_state();
                    let abs_state = self.graph.bridge.mat_state(mat_state).abstract_state_id;
                    let root_morph = self.fst.abstract_states[abs_state as usize].morpheme_id;
                    let start_m_pos = if !morpheme_ids.is_empty() && morpheme_ids[0] == root_morph {
                        1u8
                    } else {
                        0u8
                    };
                    let flags = if self.fst.abstract_states[abs_state as usize].is_derivative() {
                        PathNode::FLAG_CONTAINS_DERIVATION
                    } else {
                        0
                    };
                    let node_idx = arena.len() as u16;
                    arena.push(PathNode {
                        parent: PathNode::NONE,
                        mat_state,
                        abs_state,
                        surf_id: 0,
                        tail_byte_idx: 0,
                        length: 1,
                        flags,
                    });
                    cur_paths.push((node_idx, c_idx as u8, start_m_pos));
                }

                let total_m = morpheme_ids.len() as u8;
                while !cur_paths.is_empty() {
                    next_paths.clear();
                    for &(node_idx, c_idx, m_pos) in &cur_paths {
                        let path = arena[node_idx as usize];
                        let (_, target) = candidates[c_idx as usize];
                        let root_ctx = RootCondCtx {
                            root_attrs: root_attrs.0,
                            primary_pos: *primary_pos as u8,
                            secondary_pos: *secondary_pos as u8,
                            special_root_id: 0,
                            root_surface_id: target.root_surface_id(),
                        };

                        if m_pos == total_m {
                            let ms_meta = self.graph.bridge.mat_state(path.mat_state);
                            let is_term =
                                self.fst.abstract_states[path.abs_state as usize].is_terminal();
                            let cannot_term = (ms_meta.phono_bits
                                & PhoneticAttribute::CannotTerminate.bit())
                                != 0;
                            if is_term && !cannot_term {
                                finished.push((node_idx, c_idx));
                                continue;
                            }
                        }

                        for &arc in self.fst.outgoing_arcs(path.mat_state) {
                            if !self.allow_informal && arc.is_informal() {
                                continue;
                            }
                            if m_pos == total_m && arc.has_surface_form() {
                                continue;
                            }

                            let to_mat = arc.target_mat_state();
                            let to_abs = self.graph.bridge.mat_state(to_mat).abstract_state_id;
                            let to_morph = self.fst.abstract_states[to_abs as usize].morpheme_id;

                            if arc.has_surface_form()
                                && (m_pos >= total_m || to_morph != morpheme_ids[m_pos as usize])
                            {
                                continue;
                            }

                            if !eval_condition_rpn(
                                &self.fst,
                                arc.cond_prog_idx(),
                                root_ctx,
                                &arena,
                                node_idx as usize,
                                true,
                            ) {
                                continue;
                            }

                            let next_m_pos = if arc.is_epsilon() {
                                if m_pos < total_m && to_morph == morpheme_ids[m_pos as usize] {
                                    m_pos + 1
                                } else {
                                    m_pos
                                }
                            } else {
                                m_pos + 1
                            };

                            if arena.len() >= (PathNode::NONE as usize) {
                                continue;
                            }
                            let mut flags = path.flags;
                            if self.fst.abstract_states[to_abs as usize].is_derivative() {
                                flags |= PathNode::FLAG_CONTAINS_DERIVATION;
                            }
                            if !arc.is_epsilon() {
                                flags |= PathNode::FLAG_CONTAINS_SUFFIX_SURFACE;
                            }
                            let new_idx = arena.len() as u16;
                            arena.push(PathNode {
                                parent: node_idx,
                                mat_state: to_mat,
                                abs_state: to_abs,
                                surf_id: arc.surf_id(),
                                tail_byte_idx: 0,
                                length: path.length.saturating_add(1),
                                flags,
                            });
                            next_paths.push((new_idx, c_idx, next_m_pos));
                        }
                    }
                    core::mem::swap(&mut cur_paths, &mut next_paths);
                }

                let helper = RuleBasedAnalyzer {
                    graph: self.graph,
                    fst: self.fst,
                    allow_informal: self.allow_informal,
                    ascii_tolerant: false,
                };

                let mut results = Vec::with_capacity(finished.len());
                for (end_node, c_idx) in finished {
                    let (stem_surf, _) = candidates[c_idx as usize];
                    let analysis = helper.build_single_analysis(
                        item.clone(),
                        stem_surf,
                        &arena,
                        end_node as usize,
                    );
                    let surface = analysis.surface_form();
                    results.push(GenerationResult { surface, analysis });
                }
                results
            }
            AnalysisItem::Unknown => Vec::new(),
        }
    }

    fn stem_transitions_for_item(&self, item_id: u32) -> Vec<(&'a str, PackedStemTarget)> {
        if let Some(diffs) = self.fst.diff_targets_for_item(item_id) {
            let mut out = Vec::with_capacity(diffs.len());
            for dt in diffs {
                let surf = self.graph.lexicon.slice_pool(dt.surf_off_len);
                out.push((surf, PackedStemTarget(dt.packed_stem_target)));
            }
            return out;
        }
        // Regular item: stem surface IS `item.root` in `SECT_STEM_TRIE`!
        let root = self.graph.lexicon.root(item_id as usize);
        let pool = self.graph.lexicon.utf8_pool();
        let trie = &self.graph.stem_trie;
        let mut node_idx = 0usize;
        let mut pos = 0usize;
        while pos < root.len() {
            let next_byte = root.as_bytes()[pos];
            let children = trie.children_of(node_idx);
            let start_c = children.partition_point(|c| c.first_byte(pool) < next_byte);
            let base = trie.node(node_idx).first_child_idx();
            let mut found = None;
            let mut k = start_c;
            while k < children.len() && children[k].first_byte(pool) == next_byte {
                let lbl = trie.edge_label(children[k], pool);
                if root[pos..].starts_with(lbl) {
                    found = Some((base + k, lbl.len()));
                    break;
                }
                k += 1;
            }
            let Some((c_idx, len)) = found else {
                return Vec::new();
            };
            pos += len;
            node_idx = c_idx;
        }
        let mut out = Vec::new();
        for &t in trie.node_targets(node_idx) {
            if t.item_id() == item_id {
                out.push((root, t));
            }
        }
        out
    }
}

/// Exact port of `zemberek.morphology.analysis.InformalAnalysisConverter`.
#[derive(Clone, Copy, Debug)]
pub struct InformalAnalysisConverter<'a> {
    pub generator: WordGenerator<'a>,
}

impl<'a> InformalAnalysisConverter<'a> {
    pub fn new(graph: LexiconGraph<'a>, fst: MorphFstView<'a>) -> Self {
        Self {
            generator: WordGenerator {
                graph,
                fst,
                allow_informal: false,
            },
        }
    }

    pub fn convert(&self, input: &str, analysis: &SingleAnalysis) -> Option<GenerationResult> {
        self.convert_with_dynamic(input, analysis, None)
    }

    pub fn convert_with_dynamic(
        &self,
        input: &str,
        analysis: &SingleAnalysis,
        dynamic: Option<&DynamicStemTrie>,
    ) -> Option<GenerationResult> {
        if !analysis.contains_informal_morpheme(&self.generator.fst) {
            return Some(GenerationResult {
                surface: String::from(input),
                analysis: analysis.clone(),
            });
        }
        let mut formal_morphemes = Vec::with_capacity(analysis.morphemes.len());
        for md in &analysis.morphemes {
            let pm = self.generator.fst.morphemes[md.morpheme_id as usize];
            if pm.is_informal() && pm.mapped_morpheme_id != 0 {
                formal_morphemes.push(pm.mapped_morpheme_id);
            } else {
                formal_morphemes.push(md.morpheme_id);
            }
        }
        self.generator
            .generate_for_analysis_item(&analysis.item, &formal_morphemes, dynamic)
            .into_iter()
            .next()
    }
}
