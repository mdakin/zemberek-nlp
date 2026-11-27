use zemberek_pack::morph_fst::{cond_op, MorphFstView};

/// Compact 12-byte search path node stored in a flat per-thread scratch `Vec<PathNode>`.
#[derive(Clone, Copy, Debug)]
pub struct PathNode {
    /// Index of parent `PathNode` in `arena`, or `u16::MAX` for stem root node.
    pub parent: u16,
    pub mat_state: u16,
    pub abs_state: u16,
    /// `0` = stem root (`stem_idx` stored in `tail_or_stem`), else `surf_id` from `MatFstArc`.
    pub surf_id: u16,
    /// Byte offset into `search_input` consumed after this node.
    pub tail_byte_idx: u16,
    pub length: u8,
    /// `bit 0` = `contains_derivation`, `bit 1` = `contains_suffix_with_surface`.
    pub flags: u8,
}

impl PathNode {
    pub const NONE: u16 = u16::MAX;
    pub const FLAG_CONTAINS_DERIVATION: u8 = 1 << 0;
    pub const FLAG_CONTAINS_SUFFIX_SURFACE: u8 = 1 << 1;

    #[inline]
    pub const fn contains_derivation(self) -> bool {
        (self.flags & Self::FLAG_CONTAINS_DERIVATION) != 0
    }

    #[inline]
    pub const fn contains_suffix_with_surface(self) -> bool {
        (self.flags & Self::FLAG_CONTAINS_SUFFIX_SURFACE) != 0
    }
}

/// Root item context passed to RPN condition evaluation for a stem candidate.
#[derive(Clone, Copy, Debug)]
pub struct RootCondCtx {
    pub root_attrs: u32,
    pub primary_pos: u8,
    pub secondary_pos: u8,
    pub special_root_id: u8,
    pub root_surface_id: u8,
}

/// Evaluates a compiled RPN condition program against `(root_ctx, arena, path_idx)`.
/// `has_tail` is `node.tail_byte_idx < input_len` in analysis mode, and `true` in `WordGenerator` mode.
pub fn eval_condition_rpn(
    fst: &MorphFstView<'_>,
    prog_idx: u8,
    root_ctx: RootCondCtx,
    arena: &[PathNode],
    path_idx: usize,
    has_tail: bool,
) -> bool {
    if prog_idx == 0 {
        return true;
    }
    let insts = fst.cond_program(prog_idx);
    let mut stack = [false; 16];
    let mut sp = 0usize;
    let cur = arena[path_idx];

    for &inst in insts {
        let op = (inst & 0xFF) as u8;
        let arg = (inst >> 8) as usize;
        match op {
            cond_op::TRUE => {
                stack[sp] = true;
                sp += 1;
            }
            cond_op::NOT => {
                stack[sp - 1] = !stack[sp - 1];
            }
            cond_op::AND => {
                let b = stack[sp - 1];
                sp -= 1;
                stack[sp - 1] &= b;
            }
            cond_op::OR => {
                let b = stack[sp - 1];
                sp -= 1;
                stack[sp - 1] |= b;
            }
            cond_op::HAS_ROOT_ATTR => {
                stack[sp] = (root_ctx.root_attrs & (1u32 << arg)) != 0;
                sp += 1;
            }
            cond_op::HAS_ANY_ROOT_ATTR => {
                let mask = fst.set_words[arg] as u32;
                stack[sp] = (root_ctx.root_attrs & mask) != 0;
                sp += 1;
            }
            cond_op::SEC_POS_IS => {
                stack[sp] = (root_ctx.secondary_pos as usize) == arg;
                sp += 1;
            }
            cond_op::ROOT_PRIMARY_POS_IS => {
                stack[sp] = (root_ctx.primary_pos as usize) == arg;
                sp += 1;
            }
            cond_op::DICT_ITEM_IN_SET => {
                let id = root_ctx.special_root_id;
                let mask = fst.set_words[arg];
                stack[sp] = id > 0 && id < 64 && ((mask >> id) & 1) != 0;
                sp += 1;
            }
            cond_op::ROOT_SURF_IN_MASK => {
                stack[sp] = ((arg >> root_ctx.root_surface_id) & 1) != 0;
                sp += 1;
            }
            cond_op::HAS_TAIL => {
                stack[sp] = has_tail;
                sp += 1;
            }
            cond_op::HAS_ANY_SUFFIX_SURFACE => {
                stack[sp] = cur.contains_suffix_with_surface();
                sp += 1;
            }
            cond_op::HAS_DERIVATION => {
                stack[sp] = cur.contains_derivation();
                sp += 1;
            }
            cond_op::NO_SURFACE_AFTER_DERIVATION => {
                stack[sp] = eval_no_surface_after_derivation(fst, arena, path_idx);
                sp += 1;
            }
            cond_op::PREV_MORPHEME_IN_SET => {
                stack[sp] = cur.parent != PathNode::NONE && {
                    let prev_abs = arena[cur.parent as usize].abs_state as usize;
                    let m = fst.abstract_states[prev_abs].morpheme_id;
                    morph_in_set(fst.set_words, arg, m)
                };
                sp += 1;
            }
            cond_op::PREV_STATE_IN_SET => {
                stack[sp] = cur.parent != PathNode::NONE && {
                    let prev_abs = arena[cur.parent as usize].abs_state;
                    state_in_set(fst.set_words, arg, prev_abs)
                };
                sp += 1;
            }
            cond_op::LAST_DERIV_IN_SET => {
                stack[sp] = eval_last_deriv_in_set(fst, arena, path_idx, arg);
                sp += 1;
            }
            cond_op::CUR_GROUP_CONTAINS_ANY => {
                stack[sp] = eval_cur_group_contains_any(fst, arena, path_idx, arg);
                sp += 1;
            }
            cond_op::PREV_GROUP_CONTAINS_STATE => {
                stack[sp] = eval_prev_group_contains_state(fst, arena, path_idx, arg);
                sp += 1;
            }
            cond_op::PREV_GROUP_CONTAINS_MORPH => {
                stack[sp] = eval_prev_group_contains_morph(fst, arena, path_idx, arg);
                sp += 1;
            }
            cond_op::CONTAINS_MORPHEME => {
                stack[sp] = eval_contains_morpheme(fst, arena, path_idx, arg);
                sp += 1;
            }
            cond_op::HAS_TAIL_SEQ => {
                stack[sp] = eval_has_tail_seq(fst, arena, path_idx, arg);
                sp += 1;
            }
            cond_op::CONTAINS_MORPH_SEQ => {
                stack[sp] = eval_contains_morph_seq(fst, arena, path_idx, arg);
                sp += 1;
            }
            _ => {
                stack[sp] = false;
                sp += 1;
            }
        }
    }
    stack[0]
}

#[inline]
fn morph_in_set(words: &[u64], base: usize, morph_id: u8) -> bool {
    let w = base + ((morph_id as usize) >> 6);
    ((words[w] >> (morph_id & 63)) & 1) != 0
}

#[inline]
fn state_in_set(words: &[u64], base: usize, abs_state: u16) -> bool {
    let w = base + ((abs_state as usize) >> 6);
    ((words[w] >> (abs_state & 63)) & 1) != 0
}

fn eval_no_surface_after_derivation(
    fst: &MorphFstView<'_>,
    arena: &[PathNode],
    mut idx: usize,
) -> bool {
    while arena[idx].parent != PathNode::NONE {
        let n = arena[idx];
        if fst.abstract_states[n.abs_state as usize].is_derivative() {
            return true;
        }
        if n.surf_id != 0 {
            return false;
        }
        idx = n.parent as usize;
    }
    true
}

fn eval_last_deriv_in_set(
    fst: &MorphFstView<'_>,
    arena: &[PathNode],
    mut idx: usize,
    set_base: usize,
) -> bool {
    while arena[idx].parent != PathNode::NONE {
        let n = arena[idx];
        if fst.abstract_states[n.abs_state as usize].is_derivative() {
            return state_in_set(fst.set_words, set_base, n.abs_state);
        }
        idx = n.parent as usize;
    }
    false
}

fn eval_cur_group_contains_any(
    fst: &MorphFstView<'_>,
    arena: &[PathNode],
    mut idx: usize,
    set_base: usize,
) -> bool {
    while arena[idx].parent != PathNode::NONE {
        let n = arena[idx];
        if state_in_set(fst.set_words, set_base, n.abs_state) {
            return true;
        }
        if fst.abstract_states[n.abs_state as usize].is_derivative() {
            return false;
        }
        idx = n.parent as usize;
    }
    false
}

fn eval_prev_group_contains_state(
    fst: &MorphFstView<'_>,
    arena: &[PathNode],
    mut idx: usize,
    set_base: usize,
) -> bool {
    while !fst.abstract_states[arena[idx].abs_state as usize].is_derivative() {
        let p = arena[idx].parent;
        if p == PathNode::NONE {
            return false;
        }
        idx = p as usize;
    }
    let mut cur = arena[idx].parent;
    while cur != PathNode::NONE {
        let n = arena[cur as usize];
        if n.parent == PathNode::NONE {
            break;
        }
        if state_in_set(fst.set_words, set_base, n.abs_state) {
            return true;
        }
        if fst.abstract_states[n.abs_state as usize].is_derivative() {
            return false;
        }
        cur = n.parent;
    }
    false
}

fn eval_prev_group_contains_morph(
    fst: &MorphFstView<'_>,
    arena: &[PathNode],
    mut idx: usize,
    set_base: usize,
) -> bool {
    while !fst.abstract_states[arena[idx].abs_state as usize].is_derivative() {
        let p = arena[idx].parent;
        if p == PathNode::NONE {
            return false;
        }
        idx = p as usize;
    }
    let mut cur = arena[idx].parent;
    while cur != PathNode::NONE {
        let n = arena[cur as usize];
        if n.parent == PathNode::NONE {
            break;
        }
        let m = fst.abstract_states[n.abs_state as usize].morpheme_id;
        if morph_in_set(fst.set_words, set_base, m) {
            return true;
        }
        if fst.abstract_states[n.abs_state as usize].is_derivative() {
            return false;
        }
        cur = n.parent;
    }
    false
}

fn eval_contains_morpheme(
    fst: &MorphFstView<'_>,
    arena: &[PathNode],
    mut idx: usize,
    set_base: usize,
) -> bool {
    loop {
        let n = arena[idx];
        let m = fst.abstract_states[n.abs_state as usize].morpheme_id;
        if morph_in_set(fst.set_words, set_base, m) {
            return true;
        }
        if n.parent == PathNode::NONE {
            break;
        }
        idx = n.parent as usize;
    }
    false
}

fn eval_has_tail_seq(fst: &MorphFstView<'_>, arena: &[PathNode], mut idx: usize, off: usize) -> bool {
    let seq_len = fst.seq_bytes[off] as usize;
    if (arena[idx].length as usize) < seq_len {
        return false;
    }
    let seq = &fst.seq_bytes[off + 1..off + 1 + seq_len];
    for k in (0..seq_len).rev() {
        let m = fst.abstract_states[arena[idx].abs_state as usize].morpheme_id;
        if m != seq[k] {
            return false;
        }
        if k > 0 {
            idx = arena[idx].parent as usize;
        }
    }
    true
}

fn eval_contains_morph_seq(
    fst: &MorphFstView<'_>,
    arena: &[PathNode],
    mut end_idx: usize,
    off: usize,
) -> bool {
    let seq_len = fst.seq_bytes[off] as usize;
    if seq_len == 0 {
        return true;
    }
    if (arena[end_idx].length as usize) < seq_len {
        return false;
    }
    let seq = &fst.seq_bytes[off + 1..off + 1 + seq_len];
    let last_m = seq[seq_len - 1];
    loop {
        let end_node = arena[end_idx];
        if (end_node.length as usize) < seq_len {
            return false;
        }
        let m = fst.abstract_states[end_node.abs_state as usize].morpheme_id;
        if m == last_m {
            let mut ok = true;
            let mut p = end_node.parent;
            for k in (0..seq_len - 1).rev() {
                let pn = arena[p as usize];
                if fst.abstract_states[pn.abs_state as usize].morpheme_id != seq[k] {
                    ok = false;
                    break;
                }
                p = pn.parent;
            }
            if ok {
                return true;
            }
        }
        if end_node.parent == PathNode::NONE {
            return false;
        }
        end_idx = end_node.parent as usize;
    }
}

/// Checks `RuleBasedAnalyzer.hasExcessiveCycle` (`MAX_REPEATING_SUFFIX_TYPE_COUNT = 3`).
pub fn has_excessive_cycle(arena: &[PathNode], idx: usize) -> bool {
    if arena[idx].length <= 3 {
        return false;
    }
    let mut outer = idx;
    loop {
        let id = arena[outer].abs_state;
        let mut count = 1u8;
        let mut inner = arena[outer].parent;
        while inner != PathNode::NONE {
            let in_node = arena[inner as usize];
            if in_node.abs_state == id {
                count += 1;
                if count > 3 {
                    return true;
                }
            }
            inner = in_node.parent;
        }
        let next = arena[outer].parent;
        if next == PathNode::NONE {
            break;
        }
        outer = next as usize;
    }
    false
}
