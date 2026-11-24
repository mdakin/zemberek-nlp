package zemberek.normalization.dataset;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import zemberek.core.turkish.SecondaryPos;
import zemberek.core.turkish.Turkish;
import zemberek.core.turkish.TurkishAlphabet;
import zemberek.langid.LanguageIdentifier;
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.analysis.AnalysisCache;
import zemberek.morphology.analysis.SingleAnalysis;
import zemberek.morphology.analysis.WordAnalysis;
import zemberek.morphology.lexicon.RootLexicon;
import zemberek.normalization.dataset.DatasetModels.LengthBucket;
import zemberek.normalization.dataset.DatasetModels.MinedCandidate;
import zemberek.normalization.dataset.DatasetModels.NoiseTier;
import zemberek.normalization.dataset.DatasetModels.ProfanitySubtype;
import zemberek.normalization.dataset.DatasetModels.RawDocument;
import zemberek.tokenization.Token;
import zemberek.tokenization.TurkishSentenceExtractor;
import zemberek.tokenization.TurkishTokenizer;

/**
 * Emoji-aware Zemberek morphological noise analyzer, truncation/cipher filter, clean hard-negative
 * trap scorer, and profanity/offensive classifier for v2 corpus mining (2-20 lexical words).
 */
public final class ProfanityAndNoiseAnalyzer {

  private static final Locale TR = Turkish.LOCALE;
  private static final int MIN_WORDS = 2;
  private static final int MAX_WORDS = 20;
  private static final int MIN_CHARS = 7;
  private static final int MAX_CHARS = 215;
  private static final int MAX_TOTAL_TOKENS = 30;
  private static final int MAX_CACHE_SIZE = 350_000;

  // Cleaning & surface artifact patterns
  private static final Pattern LEADING_USERS_RE = Pattern.compile("^(?:@USER\\s+){2,}");
  private static final Pattern XENFORO_MERGE_RE =
      Pattern.compile("(?i)\\s*Mesaj otomatik birleştirildi:.*$");
  private static final Pattern VBULLETIN_MERGE_RE =
      Pattern.compile("(?i)\\s*Ek Olarak:\\s*");
  private static final Pattern NSFW_AD_SPAM_RE =
      Pattern.compile("(?iu)\\b(?:pasifim|aktifim|aktifler\\s+dm|eskort)\\b");
  private static final Pattern DEVICE_SIG_TAIL_RE =
      Pattern.compile("(?i)\\s+[A-Z0-9][A-Za-z0-9+\\- ]{2,25}\\s+Kullanıcısı$");
  private static final Pattern EKSI_TRAILING_UI_RE =
      Pattern.compile("(?i)(?:\\s+|(?<=[.!?]))(?:görsel|spoiler)\\s*$");
  private static final Pattern BBCODE_HTML_RE =
      Pattern.compile("(?i)\\[/?(?:quote|img|url|b|i|u|spoiler)[^\\]]*]|</?(?:div|br|p|span|a)\\b[^>]*>");
  private static final Pattern MULTI_SPACE_RE = Pattern.compile("\\s+");
  private static final Pattern MULTI_DOT_SPLIT_RE = Pattern.compile("(?:\\.{2,}|[!?]+)\\s+");
  private static final Pattern SMILE_GLUED_RE =
      Pattern.compile("(?i)\\w*smile\\b|:[a-z0-9_]{2,}:|\\(bkz:|🔗|https?://\\S*\\.\\.\\.|^(?:be|com|net|org)/|\\bwatch\\?\\s*$");
  private static final Pattern GERMAN_SLUG_RE = Pattern.compile(
      "(?i)\\b\\w*(?:ue|oe)\\w*(?:ue|oe)\\w*\\b|\\b(?:tuerkiye|tuerk|coezuem|coezmek|goeruentue|goerev|boeluek|boeyle|soeyle|uezere|uecretsiz|guencel|oezel|oegrenci|oesym|ueniversite|yueksek|duesuek|muezik|koetue|goere|doenem)\\b");
  private static final Pattern SEPARATOR_SPEC_RE = Pattern.compile(" - | / | \\| ");
  private static final Pattern LONG_DIGIT_RUN_RE = Pattern.compile("[0-9]{10,}");
  private static final Pattern STRIPPED_TR_CLUSTER_RE =
      Pattern.compile("^(?:dn|dm|dl|gm|km|bm|mn|ml|nr|nl|nm|tn|vr)[a-zçğıöşü]+$");
  private static final Pattern FOUR_CONSONANTS_RE =
      Pattern.compile(".*[bcçdfgğhjklmnprsştvyz]{4,}.*");
  private static final Pattern LEGAL_4CONS_SUBSTR_RE =
      Pattern.compile(".*(?:str|ktr|ntr|nsp|nsfr|kspr|kstr|mpr|mpl|ngl|ndstr|nstr|rkç|sttk|rtsch|sfenks|anksiy|eksp|kontr|kompl|santr|elektr|progr|probl|platf|transf).*");
  private static final Pattern PROPER_DERIV_SUFFIX_RE =
      Pattern.compile(".*(?:çe|ça|ce|ca|lı|li|lu|lü|lık|lik|luk|lük|cı|ci|cu|cü|çü|çı|çi|çu|sız|siz|suz|süz|lar|ler)$");
  private static final Pattern LEADING_BULLET_RE =
      Pattern.compile("^(?:\\*+|[-•–—])\\s+");
  private static final Pattern GLUED_DOT_COMMA_RE =
      Pattern.compile("(?:[a-zçğıöşüA-ZÇĞİÖŞÜ]{2,}[.,][a-zçğıöşüA-ZÇĞİÖŞÜ]{2,}|[0-9]+\\.[a-zçğıöşüA-ZÇĞİÖŞÜ]{2,})");
  private static final Pattern GLUED_WORD_DATE_RE =
      Pattern.compile("(?iu)[a-zçğıöşü]{4,}[0-9]{1,2}[./][0-9]{1,2}");

  // Linguistic & noise patterns
  private static final Pattern PURE_ALPHA_TR_RE = Pattern.compile("^[a-zçğıöşü]+$");
  private static final Pattern TR_SPECIFIC_RE = Pattern.compile("[çğıöşüÇĞİÖŞÜ]");
  private static final Pattern ANY_TR_VOWEL_RE = Pattern.compile("[aeıioöuüâîûAEIİOÖUÜÂÎÛ]");
  private static final Pattern RANDOM_INTERNAL_CAPS_RE =
      Pattern.compile(".*[a-zçğıöşü][A-ZÇĞİÖŞÜ][a-zçğıöşü].*[A-ZÇĞİÖŞÜ].*");
  private static final Pattern DIGIT_WITH_LETTERS_RE =
      Pattern.compile("^[0-9]+'?[a-zçğıöşü]{1,8}$");
  private static final Pattern PURE_DIGITS_RE = Pattern.compile("^[0-9]+$");
  private static final Pattern ELONG_3_RE = Pattern.compile("([a-zçğıöşü])\\1{2,}");
  private static final Pattern ELONG_2_VOWEL_OR_END_RE =
      Pattern.compile("([aeıioöuü])\\1|([bcçdfgğhjklmnprsştvyz])\\2$");
  private static final Pattern INFORMAL_YDROP_RE =
      Pattern.compile(".*(?:ıo|io|uo|üo|öo|oo)(?:m|n|z|lar|ler|rum|sun|ruz|dum|duk|muş|mus)?$");
  private static final Pattern INFORMAL_FUT_RE =
      Pattern.compile(".*(?:bilc[ae]|[^ae]c[ae][ğgk]|lc[ae][kğ]|pc[ae][kğ])[a-zçğıöşü]*$");
  private static final Pattern CLITIC_SUFFIX_RE = Pattern.compile(
      "^([a-zçğıöşü]{3,}?)(mıyım|miyim|muyum|müyüm|mısın|misin|musun|müsün|mıyız|miyiz|muyuz|müyüz|mı|mi|mu|mü|de|da|ki)$");
  private static final Pattern PRODUCTIVE_SUFFIX_RE = Pattern.compile(
      "^[a-zçğıöşü]{6,}(?:iyorum|ıyorum|uyoruz|üyoruz|mıyorum|miyorum|abilirim|ebilirim|yebilirim|yabilirim|bilirm|lardan|lerden|larımız|lerimiz|sınız|siniz|caklar|cekler|duğunu|diğini|cağını|ceğini)$");
  private static final Pattern DETACHED_NUM_SUFFIX_RE =
      Pattern.compile("^(?:den|dan|te|ta|ten|tan|ye|ya|nin|nın|nun|nün|de|da)$");

  // Profanity & masked curse patterns (strictly asterisk/digit-masked profanity stems; never dots!)
  private static final Pattern CENSOR_MASK_WORD_RE = Pattern.compile(
      "(?iu)\\b(?:s[\\*]+k[a-zçğıöşü\\*]*|s1kt1r\\w*|a[\\*]+k|am[\\*]+k|am[\\*]+n[a-zçğıöşü]*|"
          + "o[\\*]+ç[a-zçğıöşü]*|or[\\*]+s[a-zçğıöşü\\*]*|g[\\*]+t[a-zçğıöşü]*|"
          + "p[\\*]+ç[a-zçğıöşü]*|y[\\*]+r+a[kğm][a-zçğıöşü]*|k[\\*]+hpe[a-zçğıöşü]*)\\b");

  // Benign ASCII homographs of sık-/sıkı-/şike- that must NOT be misclassified as explicit "sik-"
  private static final List<String> BENIGN_ASCII_SIK_PREFIXES = List.of(
      "sikilas", "sikistir", "sikisti", "sikisma", "sikismi", "sikinti", "sikici",
      "siktik", "sikma", "sikca", "sikesi", "sikeli", "siklik"
  );

  private static final List<String> EXPLICIT_PROFANITY_PREFIXES = List.of(
      "siktir", "sktir", "sikey", "siker", "sikec", "sikek", "skerm", "skerim",
      "sikim", "sikiş", "sikik",
      "siktig", "siktiğ", "siktimin", "sikmek", "sikiy", "sikic", "skecem", "skicem",
      "soktuğum", "soktugum", "sokayım", "sokayim", "sokuk",
      "amına", "amina", "amını", "amini", "amcık", "amcik", "amck",
      "orospu", "oruspu", "orosbu", "rospu", "oçluk", "fahişe", "fahise", "kevaşe", "kevase",
      "yarrak", "yarrağ", "yarrag", "yarak", "yarram", "dalyarak",
      "götveren", "gotveren", "götün", "gotun", "götüm", "gotum", "göte", "götten", "gotten",
      "götlek", "gotlek",
      "pezevenk", "kahpe", "kahbe", "kaltak", "sürtük", "surtuk", "ibne", "puşt", "pust",
      "gavat", "kancık", "kancik", "piclik", "piçlik",
      "taşak", "taşşak", "tassak", "tasak",
      "sıçayım", "sicayim", "sıçtı", "sicti", "sıçsa", "sıçmak", "sicmak", "boktan", "boklu"
  );

  private static final Set<String> EXPLICIT_PROFANITY_EXACT = Set.of(
      "piç", "piçi", "piçin", "piçler", "piçlik", "pici", "picin",
      "göt", "götü", "gotu", "bok", "boku", "bokun", "sik", "sürtük", "ibne", "puşt",
      "sokim", "sokım", "sokam"
  );

  private static final Set<String> PROFANITY_ACRONYMS = Set.of(
      "amk", "aq", "amq", "mk", "oç", "sg", "bsg", "skm", "sktr", "sie", "a.q", "o.ç", "s.g"
  );

  private static final List<String> STRONG_INSULT_PREFIXES = List.of(
      "gerizekalı", "gerizekali", "gerizek", "şerefsiz", "serefsiz", "beyinsiz", "beyizsiz",
      "haysiyetsiz", "namussuz", "yavşak", "yavsak", "lavuk", "dallama", "dangalak",
      "embesil", "angut", "salak", "aptal"
  );

  private static final Set<String> HARD_NEG_HOMOGRAPH_PREFIXES = Set.of(
      "sıkıcı", "sıkıntı", "sikinti", "sikici", "sıkılaş", "sikilas", "yaprak", "amca", "malzeme", "pişman", "piliç"
  );

  private static final String[] TRUNCATION_COMPLETIONS = {
      "ek", "ak", "mek", "mak", "or", "yor", "orum", "orsun", "orlar",
      "um", "im", "ım", "üm", "un", "in", "ın", "ün", "ar", "er", "lar", "ler",
      "dir", "dır", "dur", "dür", "tir", "tır", "ini", "ını", "unu", "ünü", "den", "dan"
  };

  private static final Set<String> FOREIGN_AZ_STOPWORDS = Set.of(
      "the", "and", "with", "this", "that", "from", "your", "have", "what", "when",
      "menim", "üçün", "yaxşı", "deyil", "nece", "qeder", "bele", "mən"
  );

  private static final Set<String> DETACHED_SOLID_COMPOUNDS = Set.of(
      "hiç bir", "bir çok", "bir kaç", "her hangi", "ön görü", "alt yapı"
  );

  private static final Set<String> ALLOWED_CONSONANT_SHORTHANDS = Set.of(
      "slm", "slmlar", "slmlr", "mrb", "mrblar", "mrblr", "nbr", "cnm", "cnmm", "knk",
      "tmm", "tmmdır", "tmmdir", "fln", "hrlde", "inş", "ins", "inşllh", "insllh",
      "tşk", "tsk", "tskr", "tşkr", "tskler", "tşkler", "tsklr", "tşklr", "tskrler", "tşkrler",
      "msj", "msjlar", "msjlr", "cvp", "cvplar", "cvplr", "kdr", "ztn", "dgl",
      "grşz", "grsz", "grsrz", "grşrz", "nslsn", "nslsnz", "ltfn", "ltn",
      "krdş", "krds", "krdşm", "krdsm", "arkdş", "arkds", "arkdslr", "arkdşlr",
      "blmyrm", "svyrm", "bklm", "glcm", "knşlm", "knslm", "tbrk", "tbrkler", "tbrklr",
      "yrn", "zmn", "gzl", "evt", "hyr", "tlf", "tlfn",
      "eyw", "eyv", "kib", "glb", "hg", "hb", "bjk", "gs", "fb", "ts", "chp", "akp",
      "mhp", "kdv", "ptt", "ssk", "sgk", "kyk", "tyt", "ayt", "kpss", "ales", "yds",
      "ssd", "hdd", "ram", "cpu", "gpu", "fps", "rgb", "usb", "hdmi", "pdf", "sms",
      "tl", "usd", "gb", "mb", "tb", "mhz", "ghz", "mah", "rpm", "amk", "aq", "amq",
      "mk", "sg", "bsg", "skm"
  );

  private static final Set<String> COMPOSITIONAL_SHORTHAND_BASES = Set.of(
      "slm", "mrb", "tşk", "tsk", "tskr", "tşkr", "msj", "cvp", "tmm", "cnm", "knk",
      "krdş", "krds", "arkdş", "arkds", "tlf", "tlfn", "tbrk"
  );

  private static final String[] SHORTHAND_INFLECTION_TAILS = {
      "lerden", "lardan", "lrdn", "lerde", "larda", "lrd", "ler", "lar", "lr",
      "im", "ım", "um", "üm", "imiz", "ımız", "in", "ın", "iniz", "ınız", "m", "n",
      "dir", "dır", "dur", "dür", "tir", "tır", "dr", "da", "de", "ta", "te", "dan", "den"
  };

  private static boolean isAllowedChatShorthandOrInflection(String wl) {
    if (ALLOWED_CONSONANT_SHORTHANDS.contains(wl)) {
      return true;
    }
    int end = wl.length() - 1;
    while (end > 0 && wl.charAt(end - 1) == wl.charAt(wl.length() - 1)) {
      end--;
    }
    String squeezed = end < wl.length() - 1 ? wl.substring(0, end + 1) : wl;
    if (ALLOWED_CONSONANT_SHORTHANDS.contains(squeezed)) {
      return true;
    }
    for (String tail : SHORTHAND_INFLECTION_TAILS) {
      if (squeezed.length() > tail.length() + 1 && squeezed.endsWith(tail)) {
        String base = squeezed.substring(0, squeezed.length() - tail.length());
        if (COMPOSITIONAL_SHORTHAND_BASES.contains(base)) {
          return true;
        }
      }
    }
    return false;
  }

  private static final Set<String> TOP_TR_STOP_SKELETONS = Set.of(
      "bir", "bu", "ve", "de", "da", "ne", "cok", "ama", "icin", "gibi", "daha", "var",
      "yok", "ben", "sen", "biz", "siz", "ile", "olan", "olarak", "kadar", "sonra", "once",
      "ancak", "zaman", "her", "hic", "hem", "ise", "diye", "bile", "sadece", "zaten",
      "bence", "bana", "sana", "beni", "seni", "bunu", "sunu", "onun", "böyle", "boyle",
      "soyle", "oyle", "iyi", "guzel", "kotu", "aldım", "aldim", "geldi", "oldu", "olur"
  );

  private record MorphBundle(
      TurkishMorphology formal,
      TurkishMorphology informal,
      TurkishMorphology informalAscii
  ) {}

  private record AsciiMorphFlags(
      boolean valid,
      boolean onlyInformalVerb,
      boolean properOrAbbrOnly
  ) {}

  private final RootLexicon sharedLexicon;
  private final AnalysisCache sharedFormalCache;
  private final AnalysisCache sharedInformalCache;
  private final AnalysisCache sharedAsciiCache;
  private final ThreadLocal<MorphBundle> threadMorph;
  private final LanguageIdentifier lid;
  private final ForbiddenLeakageVault vault;

  private final Map<String, Boolean> formalCache = new ConcurrentHashMap<>(131_072);
  private final Map<String, Boolean> informalCache = new ConcurrentHashMap<>(131_072);
  private final Map<String, Boolean> informalVerbCache = new ConcurrentHashMap<>(131_072);
  private final Map<String, AsciiMorphFlags> asciiFlagsCache = new ConcurrentHashMap<>(131_072);

  private final Set<String> knownSplits = new HashSet<>();
  private final Set<String> knownContractions = new HashSet<>();
  private final Set<String> knownMisspellings = new HashSet<>();

  private final Set<String> allowedBrandsAndTech = Set.of(
      "iphone", "samsung", "xiaomi", "asus", "intel", "amd", "nvidia", "lenovo", "huawei",
      "windows", "linux", "android", "ios", "google", "youtube", "instagram", "chatgpt",
      "twitter", "twitch", "whatsapp", "telegram", "tiktok", "netflix", "bluetooth",
      "spotify", "steam", "discord", "reddit", "trendyol", "hepsiburada", "kargo",
      "amazon", "modem", "wifi", "internet", "online", "link", "topic", "valorant",
      "post", "nick", "admin", "ban", "lag", "fps", "ping", "ram", "ssd", "airfryer",
      "usb", "pdf", "sms", "cent", "facebook", "turkcell", "vodafone", "iban", "oversize"
  );

  private final Set<String> englishCommonNotElong = Set.of(
      "deep", "miss", "kerr", "cool", "good", "look", "free", "full", "will",
      "sell", "call", "bill", "pass", "boss", "loss", "room", "book", "food",
      "door", "week", "feel", "need", "keep", "less", "mess", "off", "all",
      "well", "tell", "kill", "johnny"
  );

  public ProfanityAndNoiseAnalyzer(Path baseNormDir, ForbiddenLeakageVault vault)
      throws IOException {
    this.vault = vault;
    TurkishMorphology seedFormal = TurkishMorphology.createWithDefaults();
    this.sharedLexicon = seedFormal.getLexicon();
    this.sharedFormalCache = AnalysisCache.builder().build();
    this.sharedInformalCache = AnalysisCache.builder().build();
    this.sharedAsciiCache = AnalysisCache.builder().build();
    this.threadMorph = ThreadLocal.withInitial(() -> new MorphBundle(
        TurkishMorphology.builder()
            .setLexicon(sharedLexicon)
            .setCache(sharedFormalCache)
            .build(),
        TurkishMorphology.builder()
            .setLexicon(sharedLexicon)
            .setCache(sharedInformalCache)
            .useInformalAnalysis()
            .build(),
        TurkishMorphology.builder()
            .setLexicon(sharedLexicon)
            .setCache(sharedAsciiCache)
            .useInformalAnalysis()
            .ignoreDiacriticsInAnalysis()
            .build()));
    this.lid = LanguageIdentifier.fromInternalModelGroup("tr_group");

    loadKnownSplitsAndContractions();
    loadExternalMisspellings(baseNormDir);
  }

  /**
   * Extracts up to 2 high-value sentence candidates from a raw document (splitting paragraphs via
   * {@link TurkishSentenceExtractor} when longer than 20 words).
   */
  public List<MinedCandidate> extractCandidates(RawDocument doc) {
    String cleaned = cleanRawText(doc.text(), doc.sourceId());
    if (cleaned.length() < MIN_CHARS) {
      return List.of();
    }

    List<Token> roughTokens = TurkishTokenizer.DEFAULT.tokenize(cleaned);
    int roughWords = 0;
    for (Token t : roughTokens) {
      if (isLexicalToken(t)) {
        roughWords++;
      }
    }

    if (roughWords >= MIN_WORDS && roughWords <= MAX_WORDS && cleaned.length() <= MAX_CHARS) {
      MinedCandidate single = evaluateSegment(cleaned, doc, false);
      return single == null ? List.of() : List.of(single);
    }

    if (roughWords > MAX_WORDS && roughWords <= 140) {
      List<String> rawSentences = TurkishSentenceExtractor.DEFAULT.fromParagraph(cleaned);
      List<MinedCandidate> found = new ArrayList<>(4);
      for (int idx = 0; idx < rawSentences.size(); idx++) {
        String sent = rawSentences.get(idx).trim();
        boolean isFinalInDoc = (idx == rawSentences.size() - 1);
        if (sent.length() < MIN_CHARS) {
          continue;
        }
        if (sent.length() <= MAX_CHARS) {
          MinedCandidate c = evaluateSegment(sent, doc, isFinalInDoc);
          if (c != null) {
            found.add(c);
          }
        } else {
          String[] subs = MULTI_DOT_SPLIT_RE.split(sent);
          for (int sIdx = 0; sIdx < subs.length; sIdx++) {
            MinedCandidate c =
                evaluateSegment(subs[sIdx], doc, isFinalInDoc && sIdx == subs.length - 1);
            if (c != null) {
              found.add(c);
            }
          }
        }
      }
      if (found.size() <= 2) {
        return found;
      }
      found.sort(Comparator.comparingDouble(MinedCandidate::qualityScore).reversed());
      return List.of(found.get(0), found.get(1));
    }
    return List.of();
  }

  private static String cleanRawText(String text, String sourceId) {
    if (text == null) {
      return "";
    }
    String s = text.replace('\u00A0', ' ')
        .replace("\uFEFF", "")
        .replace("\u00AD", "")
        .replace("i\u0307", "i");
    s = BBCODE_HTML_RE.matcher(s).replaceAll(" ");
    s = LEADING_USERS_RE.matcher(s).replaceFirst("@USER ");
    s = XENFORO_MERGE_RE.matcher(s).replaceFirst("");
    s = VBULLETIN_MERGE_RE.matcher(s).replaceAll(". ");
    s = DEVICE_SIG_TAIL_RE.matcher(s).replaceFirst("");
    if (sourceId.startsWith("eksisozluk")) {
      s = EKSI_TRAILING_UI_RE.matcher(s).replaceFirst("");
    }
    return MULTI_SPACE_RE.matcher(s).replaceAll(" ").trim();
  }

  private MinedCandidate evaluateSegment(
      String segment, RawDocument doc, boolean isFinalSegmentOfDoc) {
    segment = LEADING_BULLET_RE.matcher(MULTI_SPACE_RE.matcher(segment).replaceAll(" ").trim())
        .replaceFirst("")
        .trim();
    if (segment.length() < MIN_CHARS || segment.length() > MAX_CHARS) {
      return null;
    }
    if (!passesHardStringGates(segment, doc.sourceId())) {
      return null;
    }

    String dedupSkeleton = ForbiddenLeakageVault.normalizeSkeleton(segment);
    if (vault.isForbidden(segment, dedupSkeleton)) {
      return null;
    }

    List<Token> tokens = TurkishTokenizer.DEFAULT.tokenize(segment);
    if (tokens.isEmpty() || tokens.size() > MAX_TOTAL_TOKENS) {
      return null;
    }

    List<Token> lexical = new ArrayList<>(tokens.size());
    int mentions = 0;
    int hashtags = 0;
    int urls = 0;
    int emojis = 0;
    int pureNumbers = 0;
    for (Token t : tokens) {
      Token.Type type = t.getType();
      if (type == Token.Type.MetaTag || type == Token.Type.Email || type == Token.Type.Unknown) {
        return null;
      }
      if (type == Token.Type.Mention) {
        if (++mentions > 2) {
          return null;
        }
      } else if (type == Token.Type.HashTag) {
        if (++hashtags > 2) {
          return null;
        }
      } else if (type == Token.Type.URL) {
        if (++urls > 1) {
          return null;
        }
      } else if (t.isEmoji()) {
        emojis++;
      } else if (t.isNumeral() && !hasUnicodeLetter(t.getText())) {
        if (++pureNumbers > 4) {
          return null;
        }
      }
      if (isLexicalToken(t)) {
        lexical.add(t);
      }
    }

    int wordCount = lexical.size();
    LengthBucket lengthBucket = LengthBucket.forWordCount(wordCount);
    if (lengthBucket == null) {
      return null;
    }

    // Script & casing inspection
    int letters = 0;
    int upper = 0;
    int digits = 0;
    for (int i = 0; i < segment.length(); i++) {
      char ch = segment.charAt(i);
      if (ch == 'ə' || ch == 'Ə') {
        return null;
      }
      Character.UnicodeBlock ub = Character.UnicodeBlock.of(ch);
      if (ub == Character.UnicodeBlock.CYRILLIC
          || ub == Character.UnicodeBlock.ARABIC
          || ub == Character.UnicodeBlock.GREEK) {
        return null;
      }
      if (Character.isLetter(ch)) {
        letters++;
        if (Character.isUpperCase(ch)) {
          upper++;
        }
      } else if (Character.isDigit(ch)) {
        digits++;
      }
    }
    if (letters < 5 || (double) digits / segment.length() > 0.28) {
      return null;
    }
    boolean isAllCaps = letters >= 8 && ((double) upper / letters) > 0.72;
    boolean sentenceHasTrChars = TR_SPECIFIC_RE.matcher(segment).find();

    // Check foreign stopwords, vowel-less cipher memes, and profanity/insult signals
    Set<String> profanityTags = new TreeSet<>();
    boolean hasHomographHardNeg = false;
    int vowellessLongWords = 0;

    if (CENSOR_MASK_WORD_RE.matcher(segment).find()) {
      profanityTags.add("profanity_masked");
    }

    for (int lIdx = 0; lIdx < wordCount; lIdx++) {
      Token t = lexical.get(lIdx);
      String rawWord = t.getText();
      String wl = rawWord.toLowerCase(TR);
      if (FOREIGN_AZ_STOPWORDS.contains(wl)) {
        return null;
      }
      // Reject random internal case cipher memes like "tVlky dV okfyjVnN"
      if (!isAllCaps && rawWord.length() >= 4 && RANDOM_INTERNAL_CAPS_RE.matcher(rawWord).matches()) {
        return null;
      }
      boolean allowedShorthand = isAllowedChatShorthandOrInflection(wl);
      if (wl.length() >= 3
          && !ANY_TR_VOWEL_RE.matcher(wl).find()
          && !allowedShorthand
          && !Character.isDigit(wl.charAt(0))) {
        vowellessLongWords++;
        if (wl.length() >= 5 || vowellessLongWords >= 2) {
          return null;
        }
      }
      // Reject stripped-non-ASCII corruption like "Dnem", "dnyorum", "amatrce", "akllara"
      if (PURE_ALPHA_TR_RE.matcher(wl).matches() && !allowedShorthand) {
        if (STRIPPED_TR_CLUSTER_RE.matcher(wl).matches()) {
          return null;
        }
        if (FOUR_CONSONANTS_RE.matcher(wl).matches()
            && !LEGAL_4CONS_SUBSTR_RE.matcher(wl).matches()
            && !isLaughToken(wl)) {
          return null;
        }
      }

      String prevWl = lIdx > 0 ? lexical.get(lIdx - 1).getText().toLowerCase(TR) : "";
      String nextWl = lIdx + 1 < wordCount ? lexical.get(lIdx + 1).getText().toLowerCase(TR) : "";
      boolean benignAsciiSikHomograph = isBenignAsciiSikContext(
          wl, prevWl, nextWl, doc.domain(), doc.sourceId(), sentenceHasTrChars);
      if (benignAsciiSikHomograph) {
        hasHomographHardNeg = true;
      } else {
        classifyProfanityToken(wl, prevWl, doc.domain(), sentenceHasTrChars, profanityTags);
      }
      for (String hn : HARD_NEG_HOMOGRAPH_PREFIXES) {
        if (wl.startsWith(hn)) {
          hasHomographHardNeg = true;
          break;
        }
      }
    }
    // Note: Do NOT auto-tag OffensEval OFF tweets without lexical profanity/insult tokens,
    // because multi-sentence tweets & implicit political snark otherwise inject clean non-profane
    // sentences into the dedicated profanity dataset.

    // Morphological & noise analysis per lexical token
    Set<String> phenomena = new TreeSet<>();
    Set<Integer> noisyIndices = new HashSet<>();
    List<String> noisyTokens = new ArrayList<>();
    int unknownUnexplained = 0;
    boolean lastWordWasUnexplained = false;
    int trapSignals = 0;

    if (isAllCaps) {
      phenomena.add("all_caps");
    }
    if (emojis > 0) {
      phenomena.add("emoji");
      trapSignals++;
    }
    if (mentions > 0 || hashtags > 0 || urls > 0) {
      phenomena.add("social_entity");
      trapSignals++;
    }
    if (pureNumbers > 0) {
      trapSignals++;
    }
    if (hasGluedEmojiOrPunctuation(segment, tokens)) {
      phenomena.add("glued_punct_or_emoji");
    }

    for (int i = 0; i < wordCount; i++) {
      Token tok = lexical.get(i);
      String w = tok.getText();
      String wNormCase = isAllCaps ? w.toLowerCase(TR) : w;
      String wl = w.toLowerCase(TR);
      boolean isStart = (i == 0);

      String wlTrimDot = wl.endsWith(".") ? wl.substring(0, wl.length() - 1) : wl;
      // Profanity acronyms (amk/aq/mk/sg/sktr) & asterisk-masked curses count as noisy slang tokens
      if (PROFANITY_ACRONYMS.contains(wlTrimDot)
          || ("oc".equals(wlTrimDot) && doc.domain() != DatasetModels.DomainBucket.FORUMS_MODERN_2020S)
          || w.indexOf('*') >= 0) {
        phenomena.add("contraction_or_slang");
        noisyIndices.add(i);
        noisyTokens.add(w);
        continue;
      }

      if (isFormalValid(wNormCase, isStart || isAllCaps)) {
        if (wl.endsWith("de") || wl.endsWith("da") || wl.endsWith("ki")) {
          trapSignals++;
        }
        if (w.indexOf('\'') >= 0 || w.indexOf('’') >= 0) {
          trapSignals++;
        }
        continue;
      }
      if (allowedBrandsAndTech.contains(wl) || englishCommonNotElong.contains(wl)) {
        trapSignals++;
        continue;
      }

      boolean exactMorphValid = analyzeInformalExact(wl, false);
      boolean bareLowerProp = isBareLowerProperNounExact(wl);
      boolean matchedAny = false;
      boolean hasTrSpecific = TR_SPECIFIC_RE.matcher(wl).find();

      // 1. elongation
      if (!exactMorphValid
          && PURE_ALPHA_TR_RE.matcher(wl).matches()
          && (ELONG_3_RE.matcher(wl).find() || ELONG_2_VOWEL_OR_END_RE.matcher(wl).find())
          && checkMorphRecoverableAfterCollapse(wl)) {
        phenomena.add("elongation");
        matchedAny = true;
      }

      // 2. informal_verb
      boolean isInformalVerb = false;
      boolean neededAsciiForVerb = false;
      if (PURE_ALPHA_TR_RE.matcher(wl).matches()) {
        if (analyzeInformalExact(wl, true)) {
          isInformalVerb = true;
        } else if (analyzeAsciiInformal(wl, 1)) {
          isInformalVerb = true;
          neededAsciiForVerb = true;
        } else if (!analyzeAsciiInformal(wl, 0)) {
          String c3 = ELONG_3_RE.matcher(wl).replaceAll("$1");
          String c2 = collapseAllDoubleLetters(wl);
          if (INFORMAL_YDROP_RE.matcher(c3).matches() || INFORMAL_FUT_RE.matcher(c3).matches()
              || INFORMAL_YDROP_RE.matcher(c2).matches() || INFORMAL_FUT_RE.matcher(c2).matches()) {
            isInformalVerb = true;
          }
        }
      }
      if (isInformalVerb) {
        phenomena.add("informal_verb");
        if (neededAsciiForVerb) {
          phenomena.add(hasTrSpecific || sentenceHasTrChars ? "ascii_partial" : "ascii_full");
        }
        matchedAny = true;
      }

      // 3. clitic_or_split_merge (including 2-word missing-space merges like "sonrayaptıklarından")
      if (knownSplits.contains(wl) || isCliticSplitMatch(wl) || isTwoFormalWordsGlued(wl)) {
        phenomena.add("clitic_or_split_merge");
        matchedAny = true;
      }

      // 4. contraction_or_slang
      if (knownContractions.contains(wl)) {
        phenomena.add("contraction_or_slang");
        matchedAny = true;
      }

      // 5. numeral_or_apostrophe
      if (DIGIT_WITH_LETTERS_RE.matcher(wl).matches()
          && w.indexOf('\'') < 0 && w.indexOf('’') < 0) {
        phenomena.add("numeral_or_apostrophe");
        matchedAny = true;
      } else if (!bareLowerProp
          && PURE_ALPHA_TR_RE.matcher(wl).matches()
          && wNormCase.equals(wl)
          && w.indexOf('\'') < 0
          && analyzeAsciiInformal(wl, 2)) {
        phenomena.add("numeral_or_apostrophe");
        matchedAny = true;
      }

      // 6. ascii_partial / ascii_full
      if (!isInformalVerb
          && PURE_ALPHA_TR_RE.matcher(wl).matches()
          && !exactMorphValid
          && analyzeAsciiInformal(wl, 0)) {
        phenomena.add(hasTrSpecific || sentenceHasTrChars ? "ascii_partial" : "ascii_full");
        matchedAny = true;
      }

      // 7. typo_recoverable
      if (!matchedAny
          && !exactMorphValid
          && !analyzeAsciiInformal(wl, 0)
          && PURE_ALPHA_TR_RE.matcher(wl).matches()
          && wl.length() >= 4
          && (knownMisspellings.contains(wl)
              || checkSingleCollapseOrTransposition(wl)
              || PRODUCTIVE_SUFFIX_RE.matcher(wl).matches())) {
        phenomena.add("typo_recoverable");
        matchedAny = true;
      }

      if (matchedAny) {
        noisyIndices.add(i);
        noisyTokens.add(w);
      } else if (isExplicitVulgarRecognized(wl)) {
        // Non-formal ASCII/colloquial vulgarity (e.g. "orosbu", "skerm", "sokim", "siktigimin")
        // is recognized Turkish profanity (not unknown gibberish), and still counts as a noisy word!
        phenomena.add(hasTrSpecific || sentenceHasTrChars ? "typo_recoverable" : "ascii_full");
        noisyIndices.add(i);
        noisyTokens.add(w);
      } else if (bareLowerProp) {
        if (!isStart && !isAllCaps && w.equals(wl)) {
          phenomena.add("numeral_or_apostrophe");
          noisyIndices.add(i);
          noisyTokens.add(w);
        }
      } else {
        unknownUnexplained++;
        noisyIndices.add(i);
        noisyTokens.add(w);
        phenomena.add("typo_or_open_oov");
        if (i == wordCount - 1) {
          lastWordWasUnexplained = true;
        }
      }
    }

    // Check bigram split/merge spans & ambiguous clitic traps
    boolean hasAmbiguousCleanClitic = false;
    for (int i = 0; i + 1 < tokens.size(); i++) {
      Token t1 = tokens.get(i);
      Token t2 = tokens.get(i + 1);
      String l1 = t1.getText().toLowerCase(TR);
      String l2 = t2.getText().toLowerCase(TR);
      String pair = l1 + " " + l2;
      boolean detachedInfinitiveCase =
          (l1.length() >= 5 && (l1.endsWith("mak") || l1.endsWith("mek")))
              && ("dan".equals(l2) || "den".equals(l2) || "tan".equals(l2)
                  || "ten".equals(l2) || "ta".equals(l2) || "te".equals(l2));
      boolean bigramHit = DETACHED_SOLID_COMPOUNDS.contains(pair)
          || (("de".equals(l1) || "da".equals(l1)) && "ki".equals(l2))
          || (PURE_DIGITS_RE.matcher(l1).matches() && DETACHED_NUM_SUFFIX_RE.matcher(l2).matches())
          || detachedInfinitiveCase;
      if (bigramHit) {
        phenomena.add("clitic_or_split_merge");
        noisyTokens.add(t1.getText() + " " + t2.getText());
        int lexIdx = lexical.indexOf(t2);
        noisyIndices.add(lexIdx >= 0 ? lexIdx : 1000 + i);
      } else if (("de".equals(l2) || "da".equals(l2))
          && (allowedBrandsAndTech.contains(l1) || isBareLowerProperNounExact(l1))) {
        // e.g. "youtube de", "almanya da", "istanbul da" -> detached locative error, NOT T0 clean!
        phenomena.add("clitic_or_split_merge");
        noisyTokens.add(t1.getText() + " " + t2.getText());
        int lexIdx = lexical.indexOf(t2);
        noisyIndices.add(lexIdx >= 0 ? lexIdx : 1000 + i);
      }
      if ("bende".equals(l1) || "sende".equals(l1) || "onda".equals(l1)) {
        hasAmbiguousCleanClitic = true;
      }
    }

    // Relaxed OOV threshold (fixes v1 starvation of 2-edit typos & brand OOVs while blocking junk)
    int maxAllowedOov = wordCount <= 4 ? 1 : Math.min(3, (int) Math.ceil(wordCount * 0.28));
    if (unknownUnexplained > maxAllowedOov) {
      return null;
    }

    // Mid-word character-limit truncation guard (specifically catches Yankihue / scraper cutoffs)
    if (lastWordWasUnexplained
        && (doc.checkMidWordTruncation() || (isFinalSegmentOfDoc && segment.length() >= 115))
        && !endsWithTerminalPunctOrEmoji(tokens)) {
      String lastWl = lexical.get(wordCount - 1).getText().toLowerCase(TR);
      if (looksLikeMidWordTruncation(lastWl)) {
        return null;
      }
    }

    // Turkish language identification guard
    String lidText = stripSocialTokensForLid(tokens);
    if (lidText.length() >= 12) {
      String lang = lid.identify(lidText);
      if (!"tr".equals(lang) && unknownUnexplained > 0) {
        return null;
      }
    } else if (unknownUnexplained > 0 && !sentenceHasTrChars) {
      return null;
    }

    int noisyWordCount = noisyIndices.size();
    if (isAllCaps && noisyWordCount == 0) {
      noisyWordCount = 1; // ALL-CAPS requires casing normalization
    }
    double noisyRatio = (double) noisyWordCount / wordCount;

    // Assign NoiseTier (T0..T4)
    NoiseTier tier;
    if (noisyWordCount == 0 && !phenomena.contains("glued_punct_or_emoji")) {
      if (unknownUnexplained > 0 || hasAmbiguousCleanClitic) {
        return null;
      }
      tier = NoiseTier.T0_CLEAN;
    } else if (!sentenceHasTrChars && phenomena.contains("ascii_full")) {
      tier = NoiseTier.T4_PURE_ASCII;
    } else if (noisyWordCount <= 2 && (wordCount <= 5 ? noisyWordCount == 1 : noisyRatio <= 0.25)) {
      tier = NoiseTier.T1_LIGHT;
    } else if (noisyRatio < 0.45 && noisyWordCount <= 5) {
      tier = NoiseTier.T2_MEDIUM;
    } else {
      tier = NoiseTier.T3_HEAVY;
    }

    // Determine ProfanitySubtype (null -> General clean-of-profanity dataset)
    ProfanitySubtype profSubtype = null;
    if (profanityTags.contains("profanity_explicit")) {
      profSubtype = ProfanitySubtype.EXPLICIT_VULGAR;
    } else if (profanityTags.contains("profanity_acronym")
        || profanityTags.contains("profanity_masked")) {
      profSubtype = ProfanitySubtype.ACRONYM_AND_MASKED;
    } else if (profanityTags.contains("offensive_insult")) {
      profSubtype = ProfanitySubtype.TARGETED_INSULT;
    } else if (hasHomographHardNeg || "NOT".equalsIgnoreCase(doc.labels().offenseGold())) {
      // Marked so profanity sampler can also draw benign hard negatives if needed;
      // NOTE: BENIGN_HARD_NEGATIVE is free of profanity, so it is only routed if needed!
      profSubtype = hasHomographHardNeg ? ProfanitySubtype.BENIGN_HARD_NEGATIVE : null;
    }

    // Compute quality / priority score within its (LengthBucket, NoiseTier) cell
    double qualityScore = computeQualityScore(
        tier, phenomena, profanityTags, noisyWordCount, noisyRatio, trapSignals, doc);

    // Build opening-3-word key & 2-word stopword-filtered shingle hashes for O(1) near-dedup
    StringBuilder open3 = new StringBuilder();
    List<String> contentSkeletons = new ArrayList<>(wordCount);
    for (int i = 0; i < wordCount; i++) {
      String skel = TurkishAlphabet.INSTANCE.toAscii(lexical.get(i).getText().toLowerCase(TR));
      if (i < 3) {
        if (i > 0) {
          open3.append(' ');
        }
        open3.append(skel);
      }
      if (skel.length() >= 3 && !TOP_TR_STOP_SKELETONS.contains(skel)) {
        contentSkeletons.add(skel);
      }
    }
    long[] shingleHashes = computeShingleHashes(contentSkeletons);

    return new MinedCandidate(
        doc.domain(),
        doc.sourceId(),
        segment,
        wordCount,
        segment.length(),
        lengthBucket,
        tier,
        profSubtype,
        noisyWordCount,
        unknownUnexplained,
        Math.round(noisyRatio * 10000.0) / 10000.0,
        Math.round(qualityScore * 1000.0) / 1000.0,
        new ArrayList<>(phenomena),
        new ArrayList<>(profanityTags),
        noisyTokens,
        doc.labels(),
        dedupSkeleton,
        open3.toString(),
        shingleHashes);
  }

  private static double computeQualityScore(
      NoiseTier tier,
      Set<String> phenomena,
      Set<String> profanityTags,
      int noisyWordCount,
      double noisyRatio,
      int trapSignals,
      RawDocument doc) {
    double labelBonus = doc.labels().isEmpty() ? 0.0 : 0.8;
    double profBonus = profanityTags.size() * 1.5;
    if (tier == NoiseTier.T0_CLEAN) {
      // Prioritize rich hard-negative traps (-de/-da, apostrophes, emojis, brands, numbers)
      return 2.0 * trapSignals + labelBonus;
    }
    double bonus = labelBonus + profBonus;
    if (phenomena.contains("informal_verb")) bonus += 1.2;
    if (phenomena.contains("clitic_or_split_merge")) bonus += 1.2;
    if (phenomena.contains("glued_punct_or_emoji")) bonus += 1.1;
    if (phenomena.contains("typo_recoverable")) bonus += 1.0;
    if (phenomena.contains("contraction_or_slang")) bonus += 0.9;
    if (phenomena.contains("elongation")) bonus += 0.8;
    if (phenomena.contains("emoji")) bonus += 0.6;
    return 1.8 * phenomena.size() + 0.9 * noisyWordCount + 2.0 * noisyRatio + bonus;
  }

  private static long[] computeShingleHashes(List<String> contentWords) {
    if (contentWords.size() < 2) {
      return new long[0];
    }
    int n = contentWords.size() - 1;
    long[] out = new long[Math.min(n, 8)];
    for (int i = 0; i < out.length; i++) {
      String a = contentWords.get(i);
      String b = contentWords.get(i + 1);
      if (a.compareTo(b) > 0) {
        String tmp = a;
        a = b;
        b = tmp;
      }
      long h = 0xcbf29ce484222325L;
      for (int c = 0; c < a.length(); c++) {
        h = (h ^ a.charAt(c)) * 0x100000001b3L;
      }
      h = (h ^ 0x7cL) * 0x100000001b3L;
      for (int c = 0; c < b.length(); c++) {
        h = (h ^ b.charAt(c)) * 0x100000001b3L;
      }
      out[i] = h;
    }
    return out;
  }

  private static boolean isLaughToken(String wl) {
    return wl.length() >= 5 && wl.matches("^[asdfghjklşsjkzxcvbnmr]+$")
        && (wl.contains("sjsj") || wl.contains("asdf") || wl.contains("ksks") || wl.contains("hshs"));
  }

  private static boolean isBenignAsciiSikContext(
      String wl,
      String prevWl,
      String nextWl,
      DatasetModels.DomainBucket domain,
      String sourceId,
      boolean sentenceHasTrChars) {
    for (String benign : BENIGN_ASCII_SIK_PREFIXES) {
      if (wl.startsWith(benign)) {
        return true;
      }
    }
    if (domain == DatasetModels.DomainBucket.ECOMMERCE_REVIEWS
        || "query_forum_slugs".equals(sourceId)) {
      if ("sik".equals(wl) || "sikiyorum".equals(wl) || "sikiyor".equals(wl)) {
        return true; // "şık", "sık sık", perfume "sıkıyorum"
      }
    }
    if ("sik".equals(wl)) {
      if ("sik".equals(prevWl) || "sik".equals(nextWl)
          || "tasarim".equals(nextWl) || "tasarım".equals(nextWl)
          || "urun".equals(nextWl) || "ürün".equals(nextWl)
          || "gorunum".equals(nextWl) || "duruyor".equals(nextWl)
          || "ve".equals(nextWl) || "ama".equals(nextWl) || "ancak".equals(nextWl)
          || "en".equals(prevWl) || "cok".equals(prevWl) || "çok".equals(prevWl)
          || "gayet".equals(prevWl) || "derece".equals(prevWl) || "oldukca".equals(prevWl)
          || (!sentenceHasTrChars && "bir".equals(nextWl))) {
        return true;
      }
    }
    return false;
  }

  private static void classifyProfanityToken(
      String wl,
      String prevWl,
      DatasetModels.DomainBucket domain,
      boolean sentenceHasTrChars,
      Set<String> profanityTags) {
    String wlTrimDot = wl.endsWith(".") ? wl.substring(0, wl.length() - 1) : wl;
    if (PROFANITY_ACRONYMS.contains(wlTrimDot)
        || ("oc".equals(wlTrimDot)
            && domain != DatasetModels.DomainBucket.FORUMS_MODERN_2020S
            && domain != DatasetModels.DomainBucket.ECOMMERCE_REVIEWS)) {
      profanityTags.add("profanity_acronym");
      return;
    }
    if (EXPLICIT_PROFANITY_EXACT.contains(wl)) {
      profanityTags.add("profanity_explicit");
      return;
    }
    for (String p : EXPLICIT_PROFANITY_PREFIXES) {
      if (wl.startsWith(p)) {
        profanityTags.add("profanity_explicit");
        return;
      }
    }
    for (String ins : STRONG_INSULT_PREFIXES) {
      if (wl.startsWith(ins)) {
        profanityTags.add("offensive_insult");
        return;
      }
    }
  }

  private static boolean isExplicitVulgarRecognized(String wl) {
    if (EXPLICIT_PROFANITY_EXACT.contains(wl)) {
      return true;
    }
    for (String p : EXPLICIT_PROFANITY_PREFIXES) {
      if (wl.startsWith(p)) {
        return true;
      }
    }
    return false;
  }

  private boolean looksLikeMidWordTruncation(String lastWl) {
    if (lastWl.length() < 4 || !PURE_ALPHA_TR_RE.matcher(lastWl).matches()) {
      return false;
    }
    for (String comp : TRUNCATION_COMPLETIONS) {
      if (analyzeAsciiCommonValid(lastWl + comp)) {
        return true;
      }
    }
    return false;
  }

  private static boolean endsWithTerminalPunctOrEmoji(List<Token> tokens) {
    Token last = tokens.get(tokens.size() - 1);
    if (last.isEmoji()) {
      return true;
    }
    if (last.getType() == Token.Type.Punctuation) {
      String p = last.getText();
      return p.contains(".") || p.contains("!") || p.contains("?") || p.contains("…");
    }
    return false;
  }

  private static boolean hasGluedEmojiOrPunctuation(String segment, List<Token> tokens) {
    if (GLUED_DOT_COMMA_RE.matcher(segment).find()) {
      return true;
    }
    for (int i = 0; i + 1 < tokens.size(); i++) {
      Token a = tokens.get(i);
      Token b = tokens.get(i + 1);
      if (a.getEnd() + 1 == b.getStart()) {
        if ((a.isWordLike() && b.isEmoji()) || (a.isEmoji() && b.isWordLike())) {
          return true;
        }
      }
    }
    return false;
  }

  private static String stripSocialTokensForLid(List<Token> tokens) {
    StringBuilder sb = new StringBuilder();
    for (Token t : tokens) {
      if (t.isEmoji() || t.isWebRelated()) {
        continue;
      }
      if (!sb.isEmpty()) {
        sb.append(' ');
      }
      sb.append(t.getText());
    }
    return sb.toString();
  }

  private static boolean passesHardStringGates(String s, String sourceId) {
    if (s.indexOf('Ã') >= 0 || s.indexOf('Ä') >= 0 || s.indexOf('Å') >= 0
        || s.indexOf('\uFFFD') >= 0 || s.contains("&quot;") || s.contains("&amp;")
        || s.contains("&#") || s.contains("&lt;") || s.contains("&gt;")) {
      return false;
    }
    if (SMILE_GLUED_RE.matcher(s).find()
        || LONG_DIGIT_RUN_RE.matcher(s).find()
        || GLUED_WORD_DATE_RE.matcher(s).find()
        || s.indexOf('☠') >= 0
        || NSFW_AD_SPAM_RE.matcher(s).find()
        || GERMAN_SLUG_RE.matcher(s).find()) {
      return false;
    }
    int sepCount = 0;
    Matcher sepMatcher = SEPARATOR_SPEC_RE.matcher(s);
    while (sepMatcher.find()) {
      if (++sepCount >= 3) {
        return false;
      }
    }
    return true;
  }

  private static boolean hasUnicodeLetter(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (Character.isLetter(s.charAt(i))) {
        return true;
      }
    }
    return false;
  }

  private static boolean isLexicalToken(Token t) {
    return switch (t.getType()) {
      case Word, WordAlphanumerical, WordWithSymbol, Abbreviation, AbbreviationWithDots,
          UnknownWord, Number -> hasUnicodeLetter(t.getText());
      default -> false;
    };
  }

  private static WordAnalysis analyzeSafe(TurkishMorphology morph, String word) {
    try {
      return morph.analyze(word);
    } catch (RuntimeException e) {
      return WordAnalysis.EMPTY_INPUT_RESULT;
    }
  }

  private boolean isFormalWordLower(String wl) {
    Boolean cached = formalCache.get("L:" + wl);
    if (cached != null) {
      return cached;
    }
    WordAnalysis wa = analyzeSafe(threadMorph.get().formal(), wl);
    boolean ok = false;
    boolean hasApostrophe = wl.indexOf('\'') >= 0 || wl.indexOf('’') >= 0;
    for (SingleAnalysis sa : wa.getAnalysisResults()) {
      if (sa.isUnknown() || sa.containsInformalMorpheme()) {
        continue;
      }
      if (!hasApostrophe) {
        SecondaryPos sp = sa.getDictionaryItem().secondaryPos;
        if (sp == SecondaryPos.ProperNoun || sp == SecondaryPos.Abbreviation) {
          continue;
        }
      }
      ok = true;
      break;
    }
    if (formalCache.size() < 600_000) {
      formalCache.put("L:" + wl, ok);
    }
    return ok;
  }

  private boolean isFormalValid(String surface, boolean allowCaseFold) {
    String wl = surface.toLowerCase(TR);
    if (knownContractions.contains(wl) || knownSplits.contains(wl)) {
      return false;
    }
    String key = (allowCaseFold ? "S:" : "M:") + surface;
    Boolean cached = formalCache.get(key);
    if (cached != null) {
      return cached;
    }
    boolean ok = checkFormalSurface(surface);
    if (!ok && (allowCaseFold || (!surface.isEmpty() && Character.isUpperCase(surface.charAt(0))))) {
      ok = checkFormalSurface(wl);
    }
    if (formalCache.size() < 600_000) {
      formalCache.put(key, ok);
    }
    return ok;
  }

  private boolean checkFormalSurface(String form) {
    WordAnalysis wa = analyzeSafe(threadMorph.get().formal(), form);
    boolean hasApostrophe = form.indexOf('\'') >= 0 || form.indexOf('’') >= 0;
    String wl = form.toLowerCase(TR);
    boolean allLower = form.equals(wl);
    for (SingleAnalysis sa : wa.getAnalysisResults()) {
      if (sa.isUnknown() || sa.containsInformalMorpheme()) {
        continue;
      }
      SecondaryPos sp = sa.getDictionaryItem().secondaryPos;
      if (sp == SecondaryPos.ProperNoun || sp == SecondaryPos.Abbreviation) {
        if (allLower) {
          continue;
        }
        if (!hasApostrophe) {
          // Even when capitalized, inflected ProperNouns require an apostrophe ("İzmir'de", NOT "İzmirde")
          // unless bare root or derivational/plural ("Türkçe", "İstanbullu", "Türkiyeli", "Türkler").
          String root = sa.getDictionaryItem().root.toLowerCase(TR);
          if (wl.length() > root.length() && !PROPER_DERIV_SUFFIX_RE.matcher(wl).matches()) {
            continue;
          }
        }
      }
      return true;
    }
    return false;
  }

  private boolean analyzeInformalExact(String wl, boolean requireInformalMorpheme) {
    Map<String, Boolean> cache = requireInformalMorpheme ? informalVerbCache : informalCache;
    Boolean cached = cache.get(wl);
    if (cached != null) {
      return cached;
    }
    WordAnalysis wa = analyzeSafe(threadMorph.get().informal(), wl);
    boolean valid = false;
    boolean anyInformal = false;
    boolean anyNonInformal = false;
    for (SingleAnalysis sa : wa.getAnalysisResults()) {
      if (sa.isUnknown() || sa.getDictionaryItem().secondaryPos == SecondaryPos.Abbreviation) {
        continue;
      }
      valid = true;
      if (sa.containsInformalMorpheme()) {
        anyInformal = true;
      } else {
        anyNonInformal = true;
      }
    }
    boolean onlyInformal = anyInformal && !anyNonInformal;
    if (informalCache.size() < 600_000) {
      informalCache.put(wl, valid);
      informalVerbCache.put(wl, onlyInformal);
    }
    return requireInformalMorpheme ? onlyInformal : valid;
  }

  private boolean analyzeAsciiInformal(String wl, int mode) {
    AsciiMorphFlags flags = asciiFlagsCache.get(wl);
    if (flags == null) {
      WordAnalysis wa = analyzeSafe(threadMorph.get().informalAscii(), wl);
      boolean validNonAbbr = false;
      boolean anyInformal = false;
      boolean anyNonInformal = false;
      boolean anyValid = false;
      boolean nonProperNormalValid = false;
      boolean inflectedProperOrAbbr = false;
      for (SingleAnalysis sa : wa.getAnalysisResults()) {
        if (sa.isUnknown()) {
          continue;
        }
        anyValid = true;
        SecondaryPos sp = sa.getDictionaryItem().secondaryPos;
        if (sp != SecondaryPos.Abbreviation) {
          validNonAbbr = true;
        }
        if (sa.containsInformalMorpheme()) {
          anyInformal = true;
        } else if (sp != SecondaryPos.Abbreviation) {
          anyNonInformal = true;
        }
        if (sp == SecondaryPos.ProperNoun || sp == SecondaryPos.Abbreviation) {
          String root = sa.getDictionaryItem().root.toLowerCase(TR);
          if (wl.length() > root.length() + 1) {
            inflectedProperOrAbbr = true;
          }
        } else {
          nonProperNormalValid = true;
        }
      }
      flags = new AsciiMorphFlags(
          validNonAbbr || (anyValid && inflectedProperOrAbbr),
          anyInformal && !anyNonInformal,
          !nonProperNormalValid && inflectedProperOrAbbr);
      if (asciiFlagsCache.size() < 600_000) {
        asciiFlagsCache.put(wl, flags);
      }
    }
    return switch (mode) {
      case 0 -> flags.valid();
      case 1 -> flags.onlyInformalVerb();
      default -> flags.properOrAbbrOnly();
    };
  }

  private boolean isBareLowerProperNounExact(String wl) {
    WordAnalysis wa = analyzeSafe(threadMorph.get().formal(), wl);
    for (SingleAnalysis sa : wa.getAnalysisResults()) {
      if (sa.isUnknown() || sa.containsInformalMorpheme()) {
        continue;
      }
      if (sa.getDictionaryItem().secondaryPos == SecondaryPos.ProperNoun) {
        String root = sa.getDictionaryItem().root.toLowerCase(TR);
        if (wl.equals(root) || wl.equals(root + "lar") || wl.equals(root + "ler")) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean analyzeAsciiCommonValid(String wl) {
    return analyzeAsciiInformal(wl, 0) && !analyzeAsciiInformal(wl, 2);
  }

  private boolean checkMorphRecoverableAfterCollapse(String wl) {
    if (englishCommonNotElong.contains(wl)) {
      return false;
    }
    String c3 = ELONG_3_RE.matcher(wl).replaceAll("$1");
    if (!c3.equals(wl) && (analyzeAsciiCommonValid(c3) || knownContractions.contains(c3))) {
      return true;
    }
    String c1 = ELONG_3_RE.matcher(wl).replaceAll("$1$1");
    if (!c1.equals(wl) && (analyzeAsciiCommonValid(c1) || knownContractions.contains(c1))) {
      return true;
    }
    if (ELONG_3_RE.matcher(wl).find()) {
      return false;
    }
    boolean allowShort2 = TR_SPECIFIC_RE.matcher(wl).find()
        || wl.length() >= 5
        || Set.of("cokk", "heep", "yavv", "anaa", "yokk", "tamm", "yaa", "haa").contains(wl);
    if (!allowShort2) {
      return false;
    }
    String c2 = collapseAllDoubleLetters(wl);
    return !c2.equals(wl) && (analyzeAsciiCommonValid(c2) || knownContractions.contains(c2));
  }

  private static String collapseAllDoubleLetters(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    char prev = 0;
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      if (ch != prev) {
        sb.append(ch);
        prev = ch;
      }
    }
    return sb.toString();
  }

  private boolean checkSingleCollapseOrTransposition(String wl) {
    if (wl.length() < 4 || wl.length() > 24 || englishCommonNotElong.contains(wl)) {
      return false;
    }
    for (int i = 0; i + 1 < wl.length(); i++) {
      if (wl.charAt(i) == wl.charAt(i + 1)) {
        String cand = wl.substring(0, i) + wl.substring(i + 1);
        if (analyzeAsciiCommonValid(cand)) {
          return true;
        }
      }
    }
    char[] arr = wl.toCharArray();
    for (int i = 0; i + 1 < arr.length; i++) {
      if (arr[i] == arr[i + 1]) {
        continue;
      }
      char tmp = arr[i];
      arr[i] = arr[i + 1];
      arr[i + 1] = tmp;
      if (analyzeAsciiCommonValid(new String(arr))) {
        return true;
      }
      tmp = arr[i];
      arr[i] = arr[i + 1];
      arr[i + 1] = tmp;
    }
    return false;
  }

  private boolean isCliticSplitMatch(String wl) {
    if (analyzeAsciiInformal(wl, 0)) {
      return false;
    }
    Matcher m = CLITIC_SUFFIX_RE.matcher(wl);
    if (!m.matches()) {
      return false;
    }
    String stem = m.group(1);
    return isFormalWordLower(stem) || analyzeAsciiInformal(stem, 0);
  }

  private boolean isTwoFormalWordsGlued(String wl) {
    if (wl.length() < 11 || wl.length() > 26 || !PURE_ALPHA_TR_RE.matcher(wl).matches()) {
      return false;
    }
    if (analyzeAsciiInformal(wl, 0)) {
      return false;
    }
    for (int cut = 4; cut <= wl.length() - 5; cut++) {
      if (isFormalWordLower(wl.substring(0, cut)) && isFormalWordLower(wl.substring(cut))) {
        return true;
      }
    }
    return false;
  }

  private void loadKnownSplitsAndContractions() throws IOException {
    try (InputStream is =
        ProfanityAndNoiseAnalyzer.class.getResourceAsStream("/normalization/multi-word-replacements")) {
      if (is != null) {
        try (BufferedReader br =
            new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
          String line;
          while ((line = br.readLine()) != null) {
            int eq = line.indexOf('=');
            if (eq > 0) {
              String key = line.substring(0, eq).trim().toLowerCase(TR);
              if (!key.isEmpty()) {
                knownSplits.add(key);
              }
            }
          }
        }
      }
    }
    knownSplits.addAll(List.of(
        "herşey", "birşey", "bişey", "bişi", "hiçbirşey", "hiçbişey", "pekçok",
        "hemde", "yinede", "tabiki", "tabikide", "bencede", "sencede", "buda", "şuda",
        "ozaman", "okadar", "değilmi", "dimi", "olurmu", "banada", "sanada", "herkez",
        "sonrada", "öncede"
    ));
    knownSplits.remove("herhangi");
    knownSplits.remove("bende");

    knownContractions.addAll(List.of(
        "bi", "bı", "tmm", "slm", "mrb", "nbr", "naber", "cnm", "knk",
        "bnde", "snde", "bn", "fln", "felan", "heralde", "hrlde",
        "deil", "diil", "inş", "ins", "tşk", "tsk", "tskler", "tsklr",
        "msj", "cvp", "walla", "die", "nie", "baya", "orjinal", "herkez",
        "şarz", "yalnış", "yanlız", "küsür", "bağzı", "direk", "eyw", "grşz", "kib",
        "napcam", "napcan", "napcaz", "napıyon", "napıyosun", "sölüo", "sölüyor",
        "burda", "orda", "şurda", "nerde", "burdan", "ordan", "şurdan", "nerden"
    ));
    knownContractions.remove("kanka");
  }

  private void loadExternalMisspellings(Path baseNormDir) throws IOException {
    Path asim =
        baseNormDir.resolve("lexicons_external/asimokby_spelling_dict/base_turkish_misspellings.csv");
    if (!Files.exists(asim)) {
      return;
    }
    try (BufferedReader br = Files.newBufferedReader(asim, StandardCharsets.UTF_8)) {
      String line = br.readLine(); // skip header
      while ((line = br.readLine()) != null) {
        int comma = line.indexOf(',');
        if (comma > 0) {
          String wrong = line.substring(0, comma).trim().toLowerCase(TR);
          String right = line.substring(comma + 1).trim().toLowerCase(TR);
          if (wrong.length() >= 4
              && !wrong.equals(right)
              && !TurkishAlphabet.INSTANCE.toAscii(wrong)
                  .equals(TurkishAlphabet.INSTANCE.toAscii(right))
              && !englishCommonNotElong.contains(wrong)
              && !isFormalWordLower(wrong)
              && !analyzeInformalExact(wrong, false)
              && !analyzeAsciiInformal(wrong, 0)) {
            knownMisspellings.add(wrong);
          }
        }
      }
    }
  }
}
