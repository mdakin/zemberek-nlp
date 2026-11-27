use alloc::borrow::Cow;
use alloc::format;
use alloc::string::String;
use zemberek_core::{PrimaryPos, RootAttrMask, SecondaryPos, TurkishAlphabet};
use zemberek_pack::dict::{LemmaMode, LexiconView, PackedDictItem};
use zemberek_pack::stem_trie::{EntryBridgeView, StemTrieView};
use zemberek_pack::{PackError, ZmbkPack, SECT_ENTRY_BRIDGE, SECT_LEXICON, SECT_STEM_TRIE};

/// Rich typed view over a static `PackedDictItem` inside `LexiconView`.
#[derive(Clone, Copy, Debug)]
pub struct DictItemRef<'a> {
    pub item_id: u32,
    pub raw: &'a PackedDictItem,
    lexicon: LexiconView<'a>,
}

impl<'a> DictItemRef<'a> {
    #[inline]
    pub fn primary_pos(&self) -> PrimaryPos {
        PrimaryPos::from_u8(self.raw.primary_pos).unwrap_or(PrimaryPos::Unknown)
    }

    #[inline]
    pub fn secondary_pos(&self) -> SecondaryPos {
        SecondaryPos::from_u8(self.raw.secondary_pos).unwrap_or(SecondaryPos::None)
    }

    #[inline]
    pub fn root_attrs(&self) -> RootAttrMask {
        RootAttrMask(self.raw.root_attrs)
    }

    #[inline]
    pub fn homonym_index(&self) -> u8 {
        self.raw.homonym_index()
    }

    #[inline]
    pub fn special_root_id(&self) -> u8 {
        self.raw.special_root_id
    }

    #[inline]
    pub fn root(&self) -> &'a str {
        self.lexicon.root(self.item_id as usize)
    }

    /// Reconstructs the exact `DictionaryItem.lemma` (`Cow::Borrowed` when `ExactRoot` or `ExplicitPool`).
    pub fn lemma(&self) -> Cow<'a, str> {
        let root = self.root();
        match self.raw.lemma_mode() {
            LemmaMode::ExactRoot => Cow::Borrowed(root),
            LemmaMode::VerbAppendMak => Cow::Owned(format!("{root}mak")),
            LemmaMode::VerbAppendMek => Cow::Owned(format!("{root}mek")),
            LemmaMode::TrCapitalizeRoot => Cow::Owned(TurkishAlphabet::capitalize_tr(root)),
            LemmaMode::TrUpperRoot => Cow::Owned(TurkishAlphabet::to_upper_tr(root)),
            LemmaMode::ExplicitPool => {
                Cow::Borrowed(self.lexicon.explicit_lemma_slice(self.item_id as usize).unwrap_or(root))
            }
        }
    }

    /// Matches `DictionaryItem.normalizedLemma()` (removes last 3 chars from `Verb`, else returns `lemma`).
    pub fn normalized_lemma(&self) -> Cow<'a, str> {
        let root = self.root();
        match self.raw.lemma_mode() {
            LemmaMode::VerbAppendMak | LemmaMode::VerbAppendMek => Cow::Borrowed(root),
            LemmaMode::ExactRoot => {
                if self.primary_pos() == PrimaryPos::Verb {
                    let cut = root.char_indices().rev().nth(2).map_or(0, |(idx, _)| idx);
                    Cow::Borrowed(&root[..cut])
                } else {
                    Cow::Borrowed(root)
                }
            }
            _ => {
                let l = self.lemma();
                if self.primary_pos() == PrimaryPos::Verb {
                    match l {
                        Cow::Borrowed(b) => {
                            let cut = b.char_indices().rev().nth(2).map_or(0, |(idx, _)| idx);
                            Cow::Borrowed(&b[..cut])
                        }
                        Cow::Owned(mut o) => {
                            for _ in 0..3 {
                                o.pop();
                            }
                            Cow::Owned(o)
                        }
                    }
                } else {
                    l
                }
            }
        }
    }

    /// Reconstructs `DictionaryItem.id` (`lemma_POS[_SecPOS][_index]`).
    pub fn id_string(&self) -> String {
        let lemma = self.lemma();
        let ppos = self.primary_pos().short_form();
        let spos = self.secondary_pos();
        let idx = self.homonym_index();
        let mut out = String::with_capacity(lemma.len() + 12);
        out.push_str(&lemma);
        out.push('_');
        out.push_str(ppos);
        if spos != SecondaryPos::None {
            out.push('_');
            out.push_str(spos.short_form());
        }
        if idx > 0 {
            out.push('_');
            out.push((b'0' + idx) as char);
        }
        out
    }

    #[inline]
    pub fn reference_item_id(&self) -> Option<u32> {
        self.lexicon.reference_item_id(self.item_id as usize)
    }
}

/// Zero-copy combined view over `SECT_LEXICON`, `SECT_STEM_TRIE`, and `SECT_ENTRY_BRIDGE`.
#[derive(Clone, Copy, Debug)]
pub struct LexiconGraph<'a> {
    pub lexicon: LexiconView<'a>,
    pub stem_trie: StemTrieView<'a>,
    pub bridge: EntryBridgeView<'a>,
}

impl<'a> LexiconGraph<'a> {
    pub fn from_pack(pack: &ZmbkPack<'a>) -> Result<Self, PackError> {
        let (_, lex_bytes) = pack.section_bytes(SECT_LEXICON)?;
        let lexicon = LexiconView::from_section_bytes(lex_bytes)?;
        let (_, trie_bytes) = pack.section_bytes(SECT_STEM_TRIE)?;
        let stem_trie = StemTrieView::from_section_bytes(trie_bytes, lexicon.utf8_pool())?;
        let (_, bridge_bytes) = pack.section_bytes(SECT_ENTRY_BRIDGE)?;
        let bridge = EntryBridgeView::from_section_bytes(bridge_bytes)?;
        Ok(Self {
            lexicon,
            stem_trie,
            bridge,
        })
    }

    #[inline]
    pub fn item_count(&self) -> usize {
        self.lexicon.len()
    }

    #[inline]
    pub fn item(&self, item_id: u32) -> DictItemRef<'a> {
        DictItemRef {
            item_id,
            raw: self.lexicon.item(item_id as usize),
            lexicon: self.lexicon,
        }
    }
}
