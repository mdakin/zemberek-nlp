use alloc::string::String;
use alloc::vec::Vec;
use zemberek_core::TurkishAlphabet;
use zemberek_pack::stem_trie::{BfsRadixNode, PackedStemTarget, StemTrieView};
use crate::dynamic::DynamicStemTrie;
use crate::lexicon::LexiconGraph;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum StemSource {
    Static,
    Dynamic,
}

/// Matched stem transition produced by `find_prefix_matches`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StemMatch {
    /// Number of bytes consumed from `input` (`&input[..consumed_input_bytes]`).
    pub consumed_input_bytes: usize,
    /// Number of Unicode scalar values (`char`s) consumed from `input`.
    pub consumed_chars: usize,
    /// Canonical Turkish stem surface (e.g. `"çocuk"` even when input was `"cocuk"` in ASCII-tolerant mode).
    pub canonical_surface: String,
    pub target: PackedStemTarget,
    pub source: StemSource,
}

impl<'a> LexiconGraph<'a> {
    /// Finds all stem transitions matching prefixes of `input`, matching
    /// `StemTransitionsMapBased.getPrefixMatches(input, asciiTolerant)`.
    pub fn find_prefix_matches(
        &self,
        input: &str,
        ascii_tolerant: bool,
        dynamic: Option<&DynamicStemTrie>,
        out: &mut Vec<StemMatch>,
    ) {
        out.clear();
        if input.is_empty() {
            return;
        }

        if !ascii_tolerant {
            self.find_exact_static(input, out);
            if let Some(dyn_trie) = dynamic {
                dyn_trie.find_exact_into(input, out);
                out.sort_by_key(|m| m.consumed_chars);
            }
        } else {
            self.find_ascii_tolerant_static(input, out);
            if let Some(dyn_trie) = dynamic {
                dyn_trie.find_ascii_tolerant_into(input, out);
            }
            // Group by `consumed_chars` in ascending prefix-length order, with exact match first
            // and deduplicated `(canonical_surface, item_id, mat_entry_state)` per prefix length!
            out.sort_by_key(|a| a.consumed_chars);
            dedup_per_prefix_len(out);
        }
    }

    fn find_exact_static(&self, input: &str, out: &mut Vec<StemMatch>) {
        let pool = self.lexicon.utf8_pool();
        let trie = &self.stem_trie;
        let in_bytes = input.as_bytes();
        let max_chars = trie.max_stem_char_len();

        let mut node_idx = 0usize;
        let mut consumed_bytes = 0usize;
        let mut consumed_chars = 0usize;

        while consumed_bytes < in_bytes.len() && consumed_chars < max_chars {
            let next_byte = in_bytes[consumed_bytes];
            let children = trie.children_of(node_idx);
            let start_c = children.partition_point(|c| c.first_byte(pool) < next_byte);

            let mut matched_child: Option<(usize, BfsRadixNode)> = None;
            let base_child_idx = trie.node(node_idx).first_child_idx();
            let mut k = start_c;
            while k < children.len() && children[k].first_byte(pool) == next_byte {
                let child = children[k];
                let lbl = trie.edge_label(child, pool);
                if input[consumed_bytes..].starts_with(lbl) {
                    matched_child = Some((base_child_idx + k, child));
                    break;
                }
                k += 1;
            }

            let Some((c_idx, child_node)) = matched_child else {
                break;
            };
            let lbl = trie.edge_label(child_node, pool);
            consumed_bytes += lbl.len();
            consumed_chars += lbl.chars().count();
            if consumed_chars > max_chars {
                break;
            }
            node_idx = c_idx;

            let targets = trie.node_targets(node_idx);
            if !targets.is_empty() {
                let surf = &input[..consumed_bytes];
                for &t in targets {
                    out.push(StemMatch {
                        consumed_input_bytes: consumed_bytes,
                        consumed_chars,
                        canonical_surface: String::from(surf),
                        target: t,
                        source: StemSource::Static,
                    });
                }
            }
        }
    }

    fn find_ascii_tolerant_static(&self, input: &str, out: &mut Vec<StemMatch>) {
        let pool = self.lexicon.utf8_pool();
        let mut canon_buf = String::with_capacity(32);
        let mut raw_hits: Vec<(bool, StemMatch)> = Vec::new();
        walk_ascii_tolerant(
            &self.stem_trie,
            pool,
            input,
            0,
            0,
            0,
            true,
            false,
            &mut canon_buf,
            &mut raw_hits,
        );
        // Sort by `(consumed_chars ASC, !exact_match ASC)` so exact stem matches come first at each length
        raw_hits.sort_by(|a, b| {
            a.1.consumed_chars
                .cmp(&b.1.consumed_chars)
                .then_with(|| b.0.cmp(&a.0))
        });
        for (_, m) in raw_hits {
            out.push(m);
        }
    }
}

#[inline]
fn is_ascii_related_char(c: char) -> bool {
    matches!(
        c,
        'c' | 'C'
            | 'g'
            | 'G'
            | 'i'
            | 'I'
            | 'o'
            | 'O'
            | 's'
            | 'S'
            | 'u'
            | 'U'
            | 'ç'
            | 'Ç'
            | 'ğ'
            | 'Ğ'
            | 'ı'
            | 'İ'
            | 'ö'
            | 'Ö'
            | 'ş'
            | 'Ş'
            | 'ü'
            | 'Ü'
    )
}

#[allow(clippy::too_many_arguments)]
fn walk_ascii_tolerant(
    trie: &StemTrieView<'_>,
    pool: &str,
    input: &str,
    node_idx: usize,
    in_byte_pos: usize,
    in_char_pos: usize,
    exact_so_far: bool,
    stem_has_ascii_rel: bool,
    canon_buf: &mut String,
    out: &mut Vec<(bool, StemMatch)>,
) {
    if in_byte_pos >= input.len() || in_char_pos >= trie.max_stem_char_len() {
        return;
    }
    let parent = trie.node(node_idx);
    let children = trie.children_of(node_idx);
    if children.is_empty() {
        return;
    }

    let first_in_ch = input[in_byte_pos..].chars().next().unwrap();
    let first_in_ascii = TurkishAlphabet::get_ascii_equal(first_in_ch);
    let base_idx = parent.first_child_idx();

    for (offset, &child) in children.iter().enumerate() {
        // Quick first-byte filter when child starts with an ASCII non-related byte!
        let fb = child.first_byte(pool);
        if fb < 0x80 {
            let fc = fb as char;
            if fc != first_in_ch && TurkishAlphabet::get_ascii_equal(fc) != first_in_ascii {
                continue;
            }
        }

        let lbl = trie.edge_label(child, pool);
        let mut in_it = input[in_byte_pos..].chars();
        let mut cur_bytes = in_byte_pos;
        let mut cur_chars = in_char_pos;
        let mut cur_exact = exact_so_far;
        let mut cur_has_rel = stem_has_ascii_rel;
        let mut matched = true;

        for edge_ch in lbl.chars() {
            let Some(in_ch) = in_it.next() else {
                matched = false;
                break;
            };
            if in_ch != edge_ch
                && TurkishAlphabet::get_ascii_equal(in_ch)
                    != TurkishAlphabet::get_ascii_equal(edge_ch)
            {
                matched = false;
                break;
            }
            if in_ch != edge_ch {
                cur_exact = false;
            }
            if is_ascii_related_char(edge_ch) {
                cur_has_rel = true;
            }
            cur_bytes += in_ch.len_utf8();
            cur_chars += 1;
        }

        if !matched || cur_chars > trie.max_stem_char_len() {
            continue;
        }

        let prev_canon_len = canon_buf.len();
        canon_buf.push_str(lbl);
        let child_idx = base_idx + offset;

        if cur_exact || cur_has_rel {
            let targets = trie.node_targets(child_idx);
            for &t in targets {
                out.push((
                    cur_exact,
                    StemMatch {
                        consumed_input_bytes: cur_bytes,
                        consumed_chars: cur_chars,
                        canonical_surface: canon_buf.clone(),
                        target: t,
                        source: StemSource::Static,
                    },
                ));
            }
        }

        walk_ascii_tolerant(
            trie,
            pool,
            input,
            child_idx,
            cur_bytes,
            cur_chars,
            cur_exact,
            cur_has_rel,
            canon_buf,
            out,
        );
        canon_buf.truncate(prev_canon_len);
    }
}

fn dedup_per_prefix_len(matches: &mut Vec<StemMatch>) {
    let mut write = 0usize;
    for read in 0..matches.len() {
        let mut duplicate = false;
        let mut prev = write;
        while prev > 0 {
            prev -= 1;
            if matches[prev].consumed_chars != matches[read].consumed_chars {
                break;
            }
            if matches[prev].target == matches[read].target
                && matches[prev].canonical_surface == matches[read].canonical_surface
                && matches[prev].source == matches[read].source
            {
                duplicate = true;
                break;
            }
        }
        if !duplicate {
            if write != read {
                matches[write] = matches[read].clone();
            }
            write += 1;
        }
    }
    matches.truncate(write);
}
