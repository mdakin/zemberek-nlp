use zemberek_pack::deasc::{ConfusionSetView, DeascModelView};
use crate::candidate::Candidate;
use crate::codec::Casing;

/// Exact port of `LexiconPriors` static acronym/foreign/taboo tables + zero-copy `DeascModelView` lookups.
pub struct LexiconPriors<'a> {
    pub model: DeascModelView<'a>,
}

impl<'a> LexiconPriors<'a> {
    #[inline]
    pub const fn new(model: DeascModelView<'a>) -> Self {
        Self { model }
    }

    pub fn is_foreign_ascii_keep(&self, ascii_lower: &str, casing: Casing) -> bool {
        if ascii_lower == "yok" {
            return false;
        }
        if casing == Casing::Upper {
            if get_all_caps_turkish_acronym(ascii_lower).is_some() {
                return false;
            }
            if is_seed_allcaps_foreign(ascii_lower) {
                return true;
            }
        }
        self.model.contains_foreign_keep(ascii_lower)
    }

    #[inline]
    pub fn get_all_caps_turkish_acronym(&self, ascii_lower: &str) -> Option<&'static str> {
        get_all_caps_turkish_acronym(ascii_lower)
    }

    #[inline]
    pub fn get_confusion(
        &self,
        ascii_lower: &str,
        casing: Casing,
        sentence_start: bool,
    ) -> Option<ConfusionSetView<'a>> {
        if !sentence_start && casing == Casing::Title {
            self.model
                .lookup_proper_confusion(ascii_lower)
                .or_else(|| self.model.lookup_common_confusion(ascii_lower))
        } else {
            self.model
                .lookup_common_confusion(ascii_lower)
                .or_else(|| self.model.lookup_proper_confusion(ascii_lower))
        }
    }

    #[inline]
    pub fn is_taboo_surface(lower_surface: &str) -> bool {
        matches!(
            lower_surface,
            "sik" | "siki" | "sikin" | "sikti" | "göt" | "götü" | "göte" | "amcık"
        )
    }

    #[inline]
    pub fn lemma_prior_size(&self) -> usize {
        self.model.lemma_log_counts.len()
    }

    #[inline]
    pub fn get_lemma_log_count(&self, cand: &Candidate) -> f32 {
        let mut v = self.model.lemma_log_counts.get(cand.lemma_hash);
        if v == 0.0 && cand.base_lemma_hash != cand.lemma_hash {
            v = self.model.lemma_log_counts.get(cand.base_lemma_hash);
        }
        v
    }
}

fn is_seed_allcaps_foreign(s: &str) -> bool {
    matches!(
        s,
        "it" | "ai"
            | "ui"
            | "ux"
            | "id"
            | "ip"
            | "os"
            | "pc"
            | "tv"
            | "cd"
            | "dvd"
            | "hd"
            | "sd"
            | "sim"
            | "pin"
            | "bit"
            | "ram"
            | "rom"
            | "iso"
            | "bios"
            | "cia"
            | "fbi"
            | "ibm"
            | "avi"
            | "zip"
            | "bbc"
            | "cnn"
            | "ntv"
            | "usb"
            | "cpu"
            | "gpu"
            | "ssd"
    )
}

pub fn get_all_caps_turkish_acronym(ascii_lower: &str) -> Option<&'static str> {
    match ascii_lower {
        "tuik" => Some("tüik"),
        "tubitak" => Some("tübitak"),
        "osym" => Some("ösym"),
        "iett" => Some("iett"),
        "iski" => Some("iski"),
        "aihm" => Some("aihm"),
        "dhmi" => Some("dhmi"),
        "disk" => Some("disk"),
        "tisk" => Some("tisk"),
        "igdas" => Some("iğdaş"),
        "tedas" => Some("tedaş"),
        "botas" => Some("botaş"),
        "tusas" => Some("tusaş"),
        "rtuk" => Some("rtük"),
        "tusiad" => Some("tüsiad"),
        "musiad" => Some("müsiad"),
        "turk" => Some("türk"),
        "turksat" => Some("türksat"),
        "turkcell" => Some("turkcell"),
        "ibb" => Some("ibb"),
        "ito" => Some("ito"),
        "itu" => Some("itü"),
        "odtu" => Some("odtü"),
        "ytu" => Some("ytü"),
        "ktu" => Some("ktü"),
        "ab" => Some("ab"),
        "abd" => Some("abd"),
        "tbmm" => Some("tbmm"),
        "kktc" => Some("kktc"),
        "tcdd" => Some("tcdd"),
        "thy" => Some("thy"),
        "tsk" => Some("tsk"),
        "sgk" => Some("sgk"),
        "kdv" => Some("kdv"),
        "trt" => Some("trt"),
        "ptt" => Some("ptt"),
        "kyk" => Some("kyk"),
        "meb" => Some("meb"),
        "egm" => Some("egm"),
        "hgs" => Some("hgs"),
        "ogs" => Some("ogs"),
        "pkk" => Some("pkk"),
        "chp" => Some("chp"),
        "akp" => Some("akp"),
        "mhp" => Some("mhp"),
        "tobb" => Some("tobb"),
        "bddk" => Some("bddk"),
        "spk" => Some("spk"),
        "epdk" => Some("epdk"),
        "kvkk" => Some("kvkk"),
        "kobi" => Some("kobi"),
        "toki" => Some("toki"),
        "afad" => Some("afad"),
        "umke" => Some("umke"),
        "iskur" => Some("işkur"),
        "kosgeb" => Some("kosgeb"),
        "otv" => Some("ötv"),
        "mit" => Some("mit"),
        "ais" => Some("ais"),
        "ales" => Some("ales"),
        "kpss" => Some("kpss"),
        "yks" => Some("yks"),
        "lgs" => Some("lgs"),
        "tff" => Some("tff"),
        "tcmb" => Some("tcmb"),
        "tmsf" => Some("tmsf"),
        _ => None,
    }
}
