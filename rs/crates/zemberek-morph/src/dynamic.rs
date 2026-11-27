use alloc::collections::BTreeMap;
use alloc::string::String;
use alloc::vec::Vec;
use zemberek_core::{
    compute_morphemic_attributes, PhonoMask, PhoneticAttribute, PrimaryPos, PronunciationGuesser,
    RootAttrMask, RootAttribute, SecondaryPos, TurkishAlphabet,
};
use zemberek_pack::stem_trie::{root_slot, EntryBridgeView, PackedStemTarget};
use crate::stem_matcher::{StemMatch, StemSource};

/// User-added runtime dictionary item stored in `DynamicStemTrie`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DynamicDictItem {
    pub item_id: u32,
    pub lemma: String,
    pub root: String,
    pub pronunciation: String,
    pub primary_pos: PrimaryPos,
    pub secondary_pos: SecondaryPos,
    pub root_attrs: RootAttrMask,
}

/// Mutable runtime overlay for custom user-dictionary words (`Add to Dictionary` in Chrome Extension).
#[derive(Clone, Debug, Default)]
pub struct DynamicStemTrie {
    base_item_id: u32,
    items: Vec<DynamicDictItem>,
    stems: BTreeMap<String, Vec<PackedStemTarget>>,
    ascii_map: BTreeMap<String, Vec<String>>,
    max_stem_chars: usize,
}

impl DynamicStemTrie {
    pub fn new(static_item_count: u32) -> Self {
        Self {
            base_item_id: static_item_count,
            items: Vec::new(),
            stems: BTreeMap::new(),
            ascii_map: BTreeMap::new(),
            max_stem_chars: 0,
        }
    }

    #[inline]
    pub fn items(&self) -> &[DynamicDictItem] {
        &self.items
    }

    #[inline]
    pub fn stems(&self) -> &BTreeMap<String, Vec<PackedStemTarget>> {
        &self.stems
    }

    #[inline]
    pub fn get_item(&self, item_id: u32) -> Option<&DynamicDictItem> {
        let idx = item_id.checked_sub(self.base_item_id)? as usize;
        self.items.get(idx)
    }

    /// Infers POS + `RootAttribute`s using `TurkishDictionaryLoader` rules and registers stem transitions.
    pub fn add_user_word(&mut self, word: &str, bridge: &EntryBridgeView<'_>) -> Option<u32> {
        let trimmed = word.trim();
        if trimmed.is_empty() {
            return None;
        }
        let first = trimmed.chars().next()?;
        let is_verb = first.is_lowercase()
            && trimmed.chars().count() > 3
            && (trimmed.ends_with("mak") || trimmed.ends_with("mek"));
        let primary_pos = if is_verb {
            PrimaryPos::Verb
        } else {
            PrimaryPos::Noun
        };
        let secondary_pos = if first.is_uppercase() {
            SecondaryPos::ProperNoun
        } else {
            SecondaryPos::None
        };
        self.add_item(trimmed, primary_pos, secondary_pos, RootAttrMask::EMPTY, bridge)
    }

    pub fn add_item(
        &mut self,
        lemma: &str,
        primary_pos: PrimaryPos,
        secondary_pos: SecondaryPos,
        extra_attrs: RootAttrMask,
        bridge: &EntryBridgeView<'_>,
    ) -> Option<u32> {
        if lemma.is_empty() {
            return None;
        }
        let raw_stem = if primary_pos == PrimaryPos::Verb
            && lemma.chars().count() > 3
            && (lemma.ends_with("mak") || lemma.ends_with("mek"))
        {
            &lemma[..lemma.len() - 3]
        } else {
            lemma
        };
        let lowered = TurkishAlphabet::normalize_circumflex(&TurkishAlphabet::to_lower_tr(raw_stem));
        let root: String = lowered
            .chars()
            .filter(|&c| c != '-' && !TurkishAlphabet::is_apostrophe(c) && c != '.')
            .collect();
        if root.is_empty() {
            return None;
        }

        let pron = if secondary_pos == SecondaryPos::Abbreviation {
            PronunciationGuesser::guess_for_abbreviation(&root)
        } else if TurkishAlphabet::contains_vowel(&root) {
            PronunciationGuesser::replace_english_specific_chars(&root)
        } else {
            PronunciationGuesser::to_turkish_letter_pronunciations(&root)
        };
        if !TurkishAlphabet::contains_vowel(&pron) {
            return None;
        }

        let mut attrs = extra_attrs;
        infer_morphemic_attributes(&pron, primary_pos, secondary_pos, &mut attrs);

        let item_id = self.base_item_id + (self.items.len() as u32);
        let transitions = build_stem_transitions(&root, &pron, primary_pos, secondary_pos, attrs, bridge)?;
        if transitions.is_empty() {
            return None;
        }

        for (surf, mat_state) in transitions {
            let char_len = surf.chars().count();
            if char_len > self.max_stem_chars {
                self.max_stem_chars = char_len;
            }
            let target = PackedStemTarget::new(item_id, mat_state, root_surface_id_of(&surf));
            if TurkishAlphabet::contains_ascii_related(&surf) {
                let asc = TurkishAlphabet::to_ascii(&surf);
                let vec = self.ascii_map.entry(asc).or_default();
                if !vec.contains(&surf) {
                    vec.push(surf.clone());
                }
            }
            self.stems.entry(surf).or_default().push(target);
        }

        self.items.push(DynamicDictItem {
            item_id,
            lemma: String::from(lemma),
            root,
            pronunciation: pron,
            primary_pos,
            secondary_pos,
            root_attrs: attrs,
        });
        Some(item_id)
    }

    pub fn clear(&mut self) {
        self.items.clear();
        self.stems.clear();
        self.ascii_map.clear();
        self.max_stem_chars = 0;
    }

    pub fn remove_user_word(&mut self, word: &str) -> bool {
        let trimmed = word.trim();
        if trimmed.is_empty() {
            return false;
        }
        let target_low = TurkishAlphabet::to_lower_tr(trimmed);
        let ids: Vec<u32> = self
            .items
            .iter()
            .filter(|it| TurkishAlphabet::to_lower_tr(&it.lemma) == target_low)
            .map(|it| it.item_id)
            .collect();
        let mut any = false;
        for id in ids {
            any |= self.remove_item(id);
        }
        any
    }

    pub fn remove_item(&mut self, item_id: u32) -> bool {
        let mut removed = false;
        self.stems.retain(|surf, list| {
            let before = list.len();
            list.retain(|t| t.item_id() != item_id);
            if list.len() < before {
                removed = true;
            }
            if list.is_empty() && TurkishAlphabet::contains_ascii_related(surf) {
                let asc = TurkishAlphabet::to_ascii(surf);
                if let Some(asc_list) = self.ascii_map.get_mut(&asc) {
                    asc_list.retain(|s| s != surf);
                    if asc_list.is_empty() {
                        self.ascii_map.remove(&asc);
                    }
                }
            }
            !list.is_empty()
        });
        removed
    }

    pub(crate) fn find_exact_into(&self, input: &str, out: &mut Vec<StemMatch>) {
        if self.stems.is_empty() {
            return;
        }
        let mut consumed_bytes = 0usize;
        for (idx, ch) in input.chars().enumerate() {
            if idx >= self.max_stem_chars {
                break;
            }
            consumed_bytes += ch.len_utf8();
            let prefix = &input[..consumed_bytes];
            if let Some(targets) = self.stems.get(prefix) {
                for &t in targets {
                    out.push(StemMatch {
                        consumed_input_bytes: consumed_bytes,
                        consumed_chars: idx + 1,
                        canonical_surface: String::from(prefix),
                        target: t,
                        source: StemSource::Dynamic,
                    });
                }
            }
        }
    }

    pub(crate) fn find_ascii_tolerant_into(&self, input: &str, out: &mut Vec<StemMatch>) {
        if self.stems.is_empty() {
            return;
        }
        let mut consumed_bytes = 0usize;
        let mut ascii_prefix = String::with_capacity(32);
        for (idx, ch) in input.chars().enumerate() {
            if idx >= self.max_stem_chars {
                break;
            }
            consumed_bytes += ch.len_utf8();
            ascii_prefix.push(TurkishAlphabet::get_ascii_equal(ch));
            let prefix = &input[..consumed_bytes];
            if let Some(targets) = self.stems.get(prefix) {
                for &t in targets {
                    out.push(StemMatch {
                        consumed_input_bytes: consumed_bytes,
                        consumed_chars: idx + 1,
                        canonical_surface: String::from(prefix),
                        target: t,
                        source: StemSource::Dynamic,
                    });
                }
            }
            if let Some(surfaces) = self.ascii_map.get(&ascii_prefix) {
                for surf in surfaces {
                    if surf == prefix {
                        continue;
                    }
                    if let Some(targets) = self.stems.get(surf) {
                        for &t in targets {
                            out.push(StemMatch {
                                consumed_input_bytes: consumed_bytes,
                                consumed_chars: idx + 1,
                                canonical_surface: surf.clone(),
                                target: t,
                                source: StemSource::Dynamic,
                            });
                        }
                    }
                }
            }
        }
    }
}

#[inline]
fn root_surface_id_of(surf: &str) -> u8 {
    match surf {
        "de" => 1,
        "di" => 2,
        "ye" => 3,
        "yi" => 4,
        _ => 0,
    }
}

fn infer_morphemic_attributes(
    word: &str,
    primary_pos: PrimaryPos,
    secondary_pos: SecondaryPos,
    attrs: &mut RootAttrMask,
) {
    let Some(last) = word.chars().next_back() else {
        return;
    };
    let last_vowel = TurkishAlphabet::is_vowel(last);
    let vowel_count = TurkishAlphabet::vowel_count(word);

    match primary_pos {
        PrimaryPos::Verb => {
            if last_vowel {
                attrs.insert(RootAttribute::ProgressiveVowelDrop);
                attrs.insert(RootAttribute::PassiveIn);
            }
            if vowel_count > 1 && !attrs.contains(RootAttribute::AoristA) {
                attrs.insert(RootAttribute::AoristI);
            }
            if vowel_count == 1 && !attrs.contains(RootAttribute::AoristI) {
                attrs.insert(RootAttribute::AoristA);
            }
            if last == 'l' {
                attrs.insert(RootAttribute::PassiveIn);
            }
            if last_vowel || ((last == 'l' || last == 'r') && vowel_count > 1) {
                attrs.insert(RootAttribute::CausativeT);
            }
        }
        PrimaryPos::Noun | PrimaryPos::Adjective | PrimaryPos::Duplicator => {
            if vowel_count > 1
                && TurkishAlphabet::is_stop_consonant(last)
                && secondary_pos != SecondaryPos::ProperNoun
                && secondary_pos != SecondaryPos::Abbreviation
                && !attrs.contains(RootAttribute::NoVoicing)
                && !attrs.contains(RootAttribute::InverseHarmony)
            {
                attrs.insert(RootAttribute::Voicing);
            }
            if word.ends_with("nk") || word.ends_with("og") {
                if !attrs.contains(RootAttribute::NoVoicing)
                    && secondary_pos != SecondaryPos::ProperNoun
                    && secondary_pos != SecondaryPos::Abbreviation
                {
                    attrs.insert(RootAttribute::Voicing);
                }
            } else if vowel_count < 2 && !attrs.contains(RootAttribute::Voicing) {
                attrs.insert(RootAttribute::NoVoicing);
            }
        }
        _ => {}
    }
}

fn resolve_dynamic_root_slot(
    primary_pos: PrimaryPos,
    secondary_pos: SecondaryPos,
    attrs: RootAttrMask,
    phono: PhonoMask,
) -> usize {
    if phono.contains(PhoneticAttribute::LastLetterDropped) {
        return root_slot::VERB_ROOT_VOWEL_DROP_S;
    }
    if attrs.contains(RootAttribute::Reciprocal) {
        return root_slot::V_IMPLICIT_RECIP_ROOT_S;
    }
    if attrs.contains(RootAttribute::Reflexive) {
        return root_slot::V_IMPLICIT_REFLEX_ROOT_S;
    }
    match primary_pos {
        PrimaryPos::Noun => match secondary_pos {
            SecondaryPos::ProperNoun
            | SecondaryPos::Email
            | SecondaryPos::Url
            | SecondaryPos::HashTag
            | SecondaryPos::Mention => root_slot::NOUN_PROPER_S,
            SecondaryPos::Abbreviation => root_slot::NOUN_ABBRV_S,
            SecondaryPos::Emoticon | SecondaryPos::RomanNumeral => root_slot::NOUN_NO_SUFFIX_S,
            _ => {
                if attrs.contains(RootAttribute::CompoundP3sgRoot) {
                    root_slot::NOUN_COMPOUND_ROOT_S
                } else {
                    root_slot::NOUN_S
                }
            }
        },
        PrimaryPos::Adjective => root_slot::ADJECTIVE_ROOT_ST,
        PrimaryPos::Verb => root_slot::VERB_ROOT_S,
        PrimaryPos::Adverb => root_slot::ADV_ROOT_ST,
        PrimaryPos::Conjunction => root_slot::CONJ_ROOT_ST,
        PrimaryPos::Interjection => root_slot::INTERJ_ROOT_ST,
        PrimaryPos::Determiner => root_slot::DET_ROOT_ST,
        PrimaryPos::PostPositive => root_slot::POSTP_ROOT_ST,
        PrimaryPos::Numeral => root_slot::NUMERAL_ROOT_ST,
        PrimaryPos::Duplicator => root_slot::DUP_ROOT_ST,
        PrimaryPos::Question => root_slot::QUESTION_ROOT_S,
        PrimaryPos::Punctuation => root_slot::PUNC_ROOT_ST,
        _ => root_slot::NOUN_S,
    }
}

fn build_stem_transitions(
    root: &str,
    pron: &str,
    primary_pos: PrimaryPos,
    secondary_pos: SecondaryPos,
    attrs: RootAttrMask,
    bridge: &EntryBridgeView<'_>,
) -> Option<Vec<(String, u16)>> {
    let mut orig_phono = compute_morphemic_attributes(pron, PhonoMask::EMPTY);
    let has_modifiers = attrs.contains(RootAttribute::Voicing)
        || attrs.contains(RootAttribute::Doubling)
        || attrs.contains(RootAttribute::InverseHarmony)
        || attrs.contains(RootAttribute::ProgressiveVowelDrop);

    if !has_modifiers {
        let abs_state = bridge.root_abstract_state(resolve_dynamic_root_slot(
            primary_pos,
            secondary_pos,
            attrs,
            orig_phono,
        ));
        let mat = bridge.resolve_entry_state(abs_state, orig_phono.0)?;
        return Some(alloc::vec![(String::from(root), mat)]);
    }

    let mut mod_surf_chars: Vec<char> = root.chars().collect();
    let mut mod_pron_chars: Vec<char> = pron.chars().collect();
    let mut mod_phono = orig_phono;

    if attrs.contains(RootAttribute::Voicing) && !mod_surf_chars.is_empty() {
        let last_s = mod_surf_chars.len() - 1;
        let voiced_s = if root.ends_with("nk") {
            'g'
        } else {
            TurkishAlphabet::voice(mod_surf_chars[last_s])
        };
        mod_surf_chars[last_s] = voiced_s;
        if !mod_pron_chars.is_empty() {
            let last_p = mod_pron_chars.len() - 1;
            mod_pron_chars[last_p] = if root.ends_with("nk") {
                'g'
            } else {
                TurkishAlphabet::voice(mod_pron_chars[last_p])
            };
        }
        mod_phono.remove(PhoneticAttribute::LastLetterVoicelessStop);
        orig_phono.insert(PhoneticAttribute::ExpectsConsonant);
        mod_phono.insert(PhoneticAttribute::ExpectsVowel);
        mod_phono.insert(PhoneticAttribute::CannotTerminate);
    }

    if attrs.contains(RootAttribute::Doubling) && !mod_surf_chars.is_empty() {
        let last = *mod_surf_chars.last().unwrap();
        mod_surf_chars.push(last);
        if let Some(&lp) = mod_pron_chars.last() {
            mod_pron_chars.push(lp);
        }
        orig_phono.insert(PhoneticAttribute::ExpectsConsonant);
        mod_phono.insert(PhoneticAttribute::ExpectsVowel);
        mod_phono.insert(PhoneticAttribute::CannotTerminate);
    }

    if attrs.contains(RootAttribute::InverseHarmony) {
        orig_phono.insert(PhoneticAttribute::LastVowelFrontal);
        orig_phono.remove(PhoneticAttribute::LastVowelBack);
        mod_phono.insert(PhoneticAttribute::LastVowelFrontal);
        mod_phono.remove(PhoneticAttribute::LastVowelBack);
    }

    if attrs.contains(RootAttribute::ProgressiveVowelDrop) && mod_surf_chars.len() > 1 {
        mod_surf_chars.pop();
        if mod_pron_chars.len() > 1 {
            mod_pron_chars.pop();
        }
        let tmp: String = mod_pron_chars.iter().collect();
        if TurkishAlphabet::contains_vowel(&tmp) {
            mod_phono = compute_morphemic_attributes(&tmp, PhonoMask::EMPTY);
        }
        mod_phono.insert(PhoneticAttribute::LastLetterDropped);
    }

    let mod_surf: String = mod_surf_chars.into_iter().collect();
    let orig_abs = bridge.root_abstract_state(resolve_dynamic_root_slot(
        primary_pos,
        secondary_pos,
        attrs,
        orig_phono,
    ));
    let mod_abs = bridge.root_abstract_state(resolve_dynamic_root_slot(
        primary_pos,
        secondary_pos,
        attrs,
        mod_phono,
    ));

    let orig_mat = bridge.resolve_entry_state(orig_abs, orig_phono.0)?;
    let mod_mat = bridge.resolve_entry_state(mod_abs, mod_phono.0)?;

    if root == mod_surf && orig_mat == mod_mat {
        Some(alloc::vec![(String::from(root), orig_mat)])
    } else {
        Some(alloc::vec![
            (String::from(root), orig_mat),
            (mod_surf, mod_mat)
        ])
    }
}
