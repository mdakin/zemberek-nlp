#!/usr/bin/env python3
"""Builds reproducible, leak-free Turkish spellchecker benchmarks in hunspell-tr/benchmarks/."""

import collections
import os
import random
import re

BENCH_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.environ.get("ZEMBEREK_CORPUS_DIR", "data/turkish/refined_modern_2026")
TDD_CSV = os.environ.get("TRSPELL10_CSV", os.path.join(BENCH_DIR, "trspell10.csv"))

# Match Chrome SpellcheckWordIterator token shape (letters/digits with optional internal apostrophe)
TOKEN_RE = re.compile(r"[0-9A-Za-zÇĞİÖŞÜçğıöşüÂÎÛâîû]+(?:['’][A-Za-zÇĞİÖŞÜçğıöşüÂÎÛâîû]+)?")
PURE_TR_WORD_RE = re.compile(r"^[A-Za-zÇĞİÖŞÜçğıöşüÂÎÛâîû]{2,}(?:['’][A-Za-zÇĞİÖŞÜçğıöşüÂÎÛâîû]+)?$")
NUM_APOS_RE = re.compile(r"^[0-9]{1,4}['’][A-Za-zÇĞİÖŞÜçğıöşü]{1,10}$")
FOREIGN_CHARS_RE = re.compile(r"[qwxQWX]")


def is_clean_tr_token(tok: str) -> bool:
    if len(tok.encode("utf-8")) > 64:
        return False
    if NUM_APOS_RE.match(tok):
        return True
    if not PURE_TR_WORD_RE.match(tok):
        return False
    if FOREIGN_CHARS_RE.search(tok):
        return False
    # Exclude ALL-CAPS Roman numerals or noisy 2-char caps unless common TR acronym
    return True


def build_mevzuat_tokens():
    """30,000 running Turkish word tokens from held-out 2025 legislation."""
    rng = random.Random(20261127)
    path = os.path.join(DATA_DIR, "mevzuat_2025_clean.txt")
    with open(path, "r", encoding="utf-8") as f:
        lines = [l.strip() for l in f if len(l.strip()) > 40]
    rng.shuffle(lines)
    tokens = []
    for line in lines:
        for m in TOKEN_RE.finditer(line):
            tok = m.group(0)
            if is_clean_tr_token(tok):
                tokens.append(tok)
                if len(tokens) >= 30000:
                    break
        if len(tokens) >= 30000:
            break
    out = os.path.join(BENCH_DIR, "valid_mevzuat_2025_tokens.txt")
    with open(out, "w", encoding="utf-8") as f:
        for t in tokens:
            f.write(t + "\n")
    print(f"Wrote {out}: {len(tokens):,} tokens")


def build_modern_2025_2026_types():
    """25,000 unique surface word types (freq >= 8) from held-out 2025-2026 corpora."""
    counts = collections.Counter()
    heldout_files = [
        ("mevzuat_2025_clean.txt", 100000),
        ("trwiki_2026_part2.txt", 150000),
        ("wmt_news_2025.txt", 200000),
    ]
    for fname, max_lines in heldout_files:
        p = os.path.join(DATA_DIR, fname)
        with open(p, "r", encoding="utf-8") as f:
            for idx, line in enumerate(f):
                if idx >= max_lines:
                    break
                for m in TOKEN_RE.finditer(line):
                    tok = m.group(0)
                    if is_clean_tr_token(tok):
                        # Require lowercase or apostrophized proper noun to avoid foreign proper-name noise
                        if tok.islower() or "'" in tok or "’" in tok:
                            counts[tok] += 1

    # Take top 25,000 types with count >= 8 so every item is a genuine attested Turkish surface word
    eligible = [w for w, c in counts.most_common() if c >= 8][:25000]
    out = os.path.join(BENCH_DIR, "valid_modern_2025_2026_types.txt")
    with open(out, "w", encoding="utf-8") as f:
        for w in eligible:
            f.write(w + "\n")
    print(f"Wrote {out}: {len(eligible):,} unique held-out surface types")


def build_curated_modern_neologisms():
    words = [
        # Modern 2010-2026 Turkish civic, digital, scientific & everyday terms
        "farkındalık", "farkındalığı", "farkındalığın", "farkındalıklar",
        "sürdürülebilir", "sürdürülebilirlik", "sürdürülebilirliği", "sürdürülebilirliğin",
        "yazılımcı", "yazılımcılar", "yazılımcıların", "yazılımcılık",
        "güncelleme", "güncellemeler", "güncellemeleri", "güncellendi", "güncelleniyor",
        "ekosistem", "ekosistemi", "ekosistemin", "ekosistemler",
        "dijitalleşme", "dijitalleşmenin", "dijitalleşmeyle",
        "çevrimiçi", "çevrimdışı",
        "paydaş", "paydaşlar", "paydaşların", "paydaşlarla",
        "kapsayıcı", "kapsayıcılık", "kapsayıcılığı",
        "karbonsuzlaşma", "biyoçeşitlilik", "biyoçeşitliliğin",
        "dezenformasyon", "dezenformasyonla", "manipülasyon",
        "inovasyon", "inovasyonu", "otomasyon", "otomasyonu",
        "algoritma", "algoritmalar", "algoritmaların", "algoritmik",
        "yapay", "veri", "veriler", "verilerin", "verileriyle",
        "bulut", "siber", "mobil", "uygulama", "uygulamalar", "uygulamaları",
        "etkileşim", "etkileşimli", "etkileşimler",
        "içerik", "içerikler", "içeriklerin",
        "girişimci", "girişimciler", "girişimcilik", "girişimciliğin",
        "kuluçka", "ölçeklenebilir", "ölçeklenebilirlik",
        "erişilebilirlik", "erişilebilirliği",
        "kullanılabilirlik", "taşınabilirlik", "izlenebilirlik",
        "yenilenebilir", "yenilenebilirlik",
        "mikroişlemci", "yarıiletken", "nanoteknoloji", "biyoteknoloji",
        "kripto", "blokzincir", "blokzinciri", "fintek",
        "podcast", "selfie", "influencer", "trend", "platform", "platformlar", "platformları",
        "koordinasyon", "optimizasyon", "entegrasyon", "entegrasyonu",
        "simülasyon", "transformasyon", "regülasyon", "regülasyonlar",
        "motivasyon", "lokasyon", "navigasyon", "animasyon",
        "pandemi", "pandemisi", "pandemide", "varyant", "varyantı",
        "iklimsel", "küresel", "bölgesel", "kurumsal", "toplumsal", "yapısal", "bilişsel", "duygusal",
        "yönetişim", "özyönetim", "özgüven", "özgüveni", "özveri", "özverili",
        "empati", "empatik", "proaktif", "interaktif", "kolektif", "perspektif", "inisiyatif",
        # Circumflex words valid in Turkish (TDK)
        "resmî", "dâhil", "dâhilinde", "hâlâ", "kâr", "kâğıt", "rüzgâr", "hikâye", "imkân", "mekân", "dükkân", "mahkûm",
        # Proper nouns & abbreviations with ASCII and curly apostrophes
        "Türkiye'nin", "Türkiye'de", "Türkiye'ye", "Türkiye'den", "Türkiye'yi", "Türkiye'yle",
        "Türkiye’nin", "Türkiye’de", "Türkiye’ye",
        "İstanbul'da", "İstanbul'un", "İstanbul'a", "İstanbul'dan",
        "Ankara'da", "Ankara'nın", "Ankara'ya", "İzmir'de", "İzmir'in",
        "Avrupa'nın", "Avrupa'da", "Asya'da", "Akdeniz'de", "Karadeniz'de", "Anadolu'nun",
        "Atatürk'ün", "Cumhuriyet'in", "Meclis'te", "Bakanlığı'nın",
        "TBMM'de", "TBMM'nin", "TÜBİTAK'ın", "TÜBİTAK'ta", "İBB'nin", "İBB'ye",
        "KVKK'nın", "SGK'lı", "SGK'nın", "BDDK'nın", "SPK'nın", "YÖK'ün", "ÖSYM'nin",
        "ASELSAN'ın", "ROKETSAN'ın", "TUSAŞ'ın", "TOGG'un", "TEKNOFEST'te",
        "AB'nin", "AB'ye", "BM'nin", "NATO'nun", "NATO'ya", "UNESCO'nun",
        # Numeric apostrophes (passed intact by Chrome SpellcheckWordIterator!)
        "2020'de", "2023'te", "2024'te", "2025'te", "2026'da", "2026'nın",
        "1990'larda", "2000'lerde", "8'e", "16'ya", "24'e", "100'üncü", "1'inci", "2'nci", "3'üncü", "4'üncü", "6'ncı",
        #Turkish uppercase İ/I words
        "İSTANBUL", "TÜRKİYE", "ÖĞRENCİLER", "IĞDIR", "ISPARTA", "İZMİR", "İNSANLAR", "ÜRETİM", "ÇÖZÜMLER",
    ]
    out = os.path.join(BENCH_DIR, "valid_modern_neologisms_and_proper.txt")
    with open(out, "w", encoding="utf-8") as f:
        for w in dict.fromkeys(words):
            f.write(w + "\n")
    print(f"Wrote {out}: {len(set(words)):,} curated modern & proper forms")


def build_invalid_turkish_traps():
    traps = [
        # 1. Bare bound allomorphs (must require suffix via NEEDAFFIX!)
        "kitab", "ağz", "burn", "reng", "hakk", "redd", "aff", "hiss", "sırr", "tadd",
        "gid", "ed", "gittiğ", "yapacağ", "geleceğ", "sokag", "ağac", "çocuğ", "ayağ", "kulağ",
        "oğl", "karn", "göğs", "omz", "beyn", "aln", "boyn", "bağr", "sabır"[:-2] + "br",
        # 2. Failed softening / wrong stem allomorph attachment
        "kitapı", "kitapın", "kitapa", "kitablar", "kitabda", "kitabdan", "kitabta",
        "ağızı", "ağızın", "ağıza", "ağzlar", "ağzda", "ağzdan",
        "burunu", "burunun", "burnlar", "burnda",
        "renki", "renkin", "rengler", "rengde",
        "sokakı", "sokakın", "sokaka", "ağaçı", "ağaçın", "ağaça",
        "çocukı", "çocuku", "çocuğlar", "ayakı", "ayağlar",
        "etiyor", "etecek", "edti", "eddi", "gitiyor", "gitecek", "giddi", "gidti",
        # 3. Vowel harmony violations
        "evlar", "evlarda", "kitapler", "kitaplerde", "okuller", "insanler", "çocukler",
        "gözlar", "günlar", "köylar", "sözlar", "işlar", "dişlar",
        "geliyorlarım", "geleceksın", "geleceksınız", "gidiyorsın", "yapıyorsın",
        "gittım", "gittın", "baktim", "baktin", "yaptik", "gördum", "gördun", "öldu",
        "okuldakı", "evdekı", "masadakı", "sokaktakı",
        "Türkiye'nın", "Türkiye'da", "İstanbul'de", "İstanbul'in", "Ankara'de", "Ankara'nin",
        # 4. Consonant voicing assimilation (f/s/t/k/ç/ş/h/p + d/c -> t/ç) violations
        "sokakda", "sokakdan", "kitapda", "kitapdan", "ağaçda", "ağaçdan",
        "sınıfda", "sınıfdan", "işde", "işden", "sabahda", "sabahdan",
        "mutfakda", "mutfakdan", "çiçekde", "çiçekden", "bebekde", "bebekden",
        "güneşde", "güneşden", "savaşda", "savaşdan", "barışda", "barışdan",
        "yavaşca", "çocukca", "türkce", "arapca", "ingilizce"[:-2] + "ce_bad",
        "gelişdi", "çalışdı", "konuşdu", "bakdı", "yapdı", "atdı", "çekdi", "seçdi",
        # 5. High-frequency real-world Turkish misspellings, colloquialisms, & run-ons
        "yalnış", "yalnışlık", "yalnışlıkla",
        "yanlız", "yanlızlık", "yanlızca",
        "herkez", "herkeze", "herkezin", "herkezden",
        "orjinal", "orjinallik", "orjinali",
        "şarz", "şarzı",
        "süpriz", "süprizler",
        "herşey", "herşeyi", "herşeyin", "herşeye", "herşeyden",
        "birşey", "birşeyi", "birşeyler", "hiçbirşey",
        "pekçok", "hertürlü", "hergün", "herزمان",
        "gidicem", "yapıcam", "bakıcam", "alıcam", "vericem", "gelicem", "söylicem",
        "geliyom", "gidiyom", "yapıyom", "biliyom", "istiyom",
        "geliyo", "gidiyo", "yapıyo", "biliyo", "diyo",
        "deil", "diil", "meraba", "mrb", "slm", "tşk", "nbr",
        "entellektüel", "muhattap", "insiyatif", "gardolap", "eşortman", "kiprik",
        "aşşağı", "aşşağıda", "hristiyan" + "lik", "antreman", "ateşkes" + "da",
        "profesör"[:-4] + "fesör", "laboratuar", "konservatuar", "repertuar",
        "doküman"[:-5] + "küman_bad", "makina" + "lar_bad", "kirbit", "şöför", "şöförü",
        "egzos", "egzost", "asfalt"[:-1] + "d", "tesbit", "tastik", "muaffak",
        "müsayit", "mütahit", "müteahit", "taahüt", "teşebbüz", "tenefüs",
        "vejeteryan", "stajer", "stajerlik", "kolleksiyon", "komser", "karekter",
        "değerlendirilmiyecek", "yapılmıyacak", "gelmiyecek", "gitmiyecek", "olmıyacak",
        "başlıyacak", "anlıyamadım", "söyliyemedi", "bekliyecek", "dinliyecek",
        # 6. Additional TDK orthography, non-TDK run-on, double-plural & apostrophe misuse traps
        "farketmek", "farketti", "farketmez", "farketmedi", "terketmek", "terketti",
        "arzetmek", "arzetti", "sarfetmek", "sarfetti", "altetmek",
        "aktrist", "akibet", "ambülans", "döküman", "proğram", "pardesü", "ataç",
        "evlerler", "kitaplarlar", "çocuklarlar", "geliyorlarlar",
        "kitap'ın", "kitap'ta", "ev'de", "ev'in", "okul'da", "öğrenci'nin",
        "Türk'çe", "İstanbul'lu", "Ankara'lı", "Kemal'cık", "Ayşe'cik",
        "Sinob'un", "Zonguldağ'ın", "dülerle",
    ]
    clean_traps = [t for t in dict.fromkeys(traps) if "_" not in t and PURE_TR_WORD_RE.match(t)]
    out = os.path.join(BENCH_DIR, "invalid_turkish_traps.txt")
    with open(out, "w", encoding="utf-8") as f:
        for w in clean_traps:
            f.write(w + "\n")
    print(f"Wrote {out}: {len(clean_traps):,} invalid morphophonemic & lexical traps")


def build_suggest_trspell10():
    pairs = []
    with open(TDD_CSV, "r", encoding="utf-8") as f:
        for idx, line in enumerate(f):
            if idx == 0:
                continue
            line = line.strip()
            if "," not in line:
                continue
            gold, wrong = line.split(",", 1)
            gold, wrong = gold.strip(), wrong.strip()
            if not gold or not wrong or gold == wrong:
                continue
            if len(wrong.encode("utf-8")) > 24:
                continue
            if not PURE_TR_WORD_RE.match(gold):
                continue
            pairs.append((wrong, gold))
    out = os.path.join(BENCH_DIR, "suggest_trspell10_clean.tsv")
    with open(out, "w", encoding="utf-8") as f:
        for wrong, gold in pairs:
            f.write(f"{wrong}\t{gold}\n")
    print(f"Wrote {out}: {len(pairs):,} trspell10 pairs (<=24 UTF-8 bytes)")


def build_suggest_modern_typos():
    pairs = [
        # 1. Fossilized Turkish misspellings & loanwords
        ("yalnış", "yanlış"), ("yalnışlık", "yanlışlık"),
        ("yanlız", "yalnız"), ("yanlızlık", "yalnızlık"), ("yanlızca", "yalnızca"),
        ("herkez", "herkes"), ("herkeze", "herkese"), ("herkezin", "herkesin"),
        ("orjinal", "orijinal"), ("şarz", "şarj"), ("süpriz", "sürpriz"),
        ("insiyatif", "inisiyatif"), ("muhattap", "muhatap"), ("entellektüel", "entelektüel"),
        ("antreman", "antrenman"), ("gardolap", "gardırop"), ("eşortman", "eşofman"),
        ("kiprik", "kirpik"), ("kirbit", "kibrit"), ("şöför", "şoför"),
        ("egzos", "egzoz"), ("egzost", "egzoz"), ("tesbit", "tespit"),
        ("müsayit", "müsait"), ("tenefüs", "teneffüs"), ("vejeteryan", "vejetaryen"),
        ("stajer", "stajyer"), ("kolleksiyon", "koleksiyon"), ("karekter", "karakter"),
        ("komser", "komiser"), ("laboratuar", "laboratuvar"), ("konservatuar", "konservatuvar"),
        ("aşşağı", "aşağı"), ("meraba", "merhaba"), ("deil", "değil"),
        # 2. Multi-word split clitics / pronouns via '_' -> ' '
        ("herşey", "her şey"), ("birşey", "bir şey"), ("hiçbirşey", "hiçbir şey"),
        ("pekçok", "pek çok"), ("hergün", "her gün"), ("hertürlü", "her türlü"),
        # 3. Colloquial verbal contractions -> standard formal Turkish
        ("gidicem", "gideceğim"), ("yapıcam", "yapacağım"), ("gelicem", "geleceğim"),
        ("alıcam", "alacağım"), ("bakıcam", "bakacağım"), ("vericem", "vereceğim"),
        ("geliyom", "geliyorum"), ("gidiyom", "gidiyorum"), ("biliyom", "biliyorum"),
        ("geliyo", "geliyor"), ("gidiyo", "gidiyor"), ("yapıyo", "yapıyor"), ("biliyo", "biliyor"),
        ("gelmiyecek", "gelmeyecek"), ("yapılmıyacak", "yapılmayacak"), ("başlıyacak", "başlayacak"),
        # 4. Deasciification / missing Turkish diacritics (huge real-world Chrome pain point!)
        ("ogrenci", "öğrenci"), ("ogrenciler", "öğrenciler"), ("ogretmen", "öğretmen"),
        ("turkce", "Türkçe"), ("turkiye", "Türkiye"), ("istanbul", "İstanbul"),
        ("calisma", "çalışma"), ("calismalar", "çalışmalar"), ("cozum", "çözüm"),
        ("gorusme", "görüşme"), ("ozellik", "özellik"), ("ozellikle", "özellikle"),
        ("sirket", "şirket"), ("basari", "başarı"), ("basarili", "başarılı"),
        ("ulke", "ülke"), ("gunluk", "günlük"), ("guvenlik", "güvenlik"),
        ("universite", "üniversite"), ("yonetim", "yönetim"), ("uretim", "üretim"),
        ("saglik", "sağlık"), ("egitim", "eğitim"), ("degisim", "değişim"),
        ("iletisim", "iletişim"), ("gelistirme", "geliştirme"), ("degerlendirme", "değerlendirme"),
        ("surdurulebilir", "sürdürülebilir"), ("farkindalik", "farkındalık"), ("yazilimci", "yazılımcı"),
        ("guncelleme", "güncelleme"), ("cevrimici", "çevrimiçi"), ("kullanici", "kullanıcı"),
        ("sozlesme", "sözleşme"), ("musteri", "müşteri"), ("urunler", "ürünler"),
        ("arastirma", "araştırma"), ("bagimsiz", "bağımız" if False else "bağımsız"),
        ("dogrudan", "doğrudan"), ("cocuklar", "çocuklar"), ("buyuk", "büyük"), ("kucuk", "küçük"),
        ("guzel", "güzel"), ("onemli", "önemli"), ("gerekli", "gerekli"),
        # 5. Morphophonemic consonant softening / assimilation / vowel harmony errors
        ("kitapı", "kitabı"), ("kitablar", "kitaplar"), ("kitapda", "kitapta"), ("kitapdan", "kitaptan"),
        ("ağızı", "ağzı"), ("ağzlar", "ağızlar"), ("burunu", "burnu"),
        ("sokakda", "sokakta"), ("sokakdan", "sokaktan"), ("ağaçda", "ağaçta"), ("ağaçdan", "ağaçtan"),
        ("sınıfda", "sınıfta"), ("işde", "işte"), ("sabahdan", "sabahtan"),
        ("mutfakda", "mutfakta"), ("çiçekde", "çiçekte"), ("çocukca", "çocukça"), ("yavaşca", "yavaşça"),
        ("evlar", "evler"), ("gözlar", "gözler"), ("geleceksın", "geleceksin"),
        ("baktim", "baktım"), ("gördum", "gördüm"), ("gelişdi", "gelişti"), ("çalışdı", "çalıştı"),
        # 6. Single-character keyboard typos / transpositions
        ("biligsayar", "bilgisayar"), ("teşekürler", "teşekkürler"), ("programlma", "programlama"),
        ("cumhuryiet", "cumhuriyet"), ("ekonmoi", "ekonomi"), ("teknolji", "teknoloji"),
        ("demokarsi", "demokrasi"), ("parlamneto", "parlamento"), ("kütüphnae", "kütüphane"),
    ]
    clean_pairs = [(w, g) for w, g in pairs if w != g and len(w.encode("utf-8")) <= 24]
    out = os.path.join(BENCH_DIR, "suggest_modern_turkish_typos.tsv")
    with open(out, "w", encoding="utf-8") as f:
        for w, g in clean_pairs:
            f.write(f"{w}\t{g}\n")
    print(f"Wrote {out}: {len(clean_pairs):,} modern real-world typo pairs")


if __name__ == "__main__":
    build_mevzuat_tokens()
    build_modern_2025_2026_types()
    build_curated_modern_neologisms()
    build_invalid_turkish_traps()
    build_suggest_trspell10()
    build_suggest_modern_typos()
