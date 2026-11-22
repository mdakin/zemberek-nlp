import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import zemberek.core.turkish.SecondaryPos;
import zemberek.core.turkish.Turkish;
import zemberek.core.turkish.TurkishAlphabet;
import zemberek.langid.LanguageIdentifier;
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.analysis.SingleAnalysis;
import zemberek.morphology.analysis.WordAnalysis;
import zemberek.tokenization.Token;
import zemberek.tokenization.TurkishSentenceExtractor;
import zemberek.tokenization.TurkishTokenizer;

public class MineNastySentences {

  private static final Locale TR = Turkish.LOCALE;
  private static final int TARGET_TOTAL = 5000;
  private static final int MIN_WORDS = 5;
  private static final int MAX_WORDS = 15;
  private static final int MIN_CHARS = 22;
  private static final int MAX_CHARS = 145;
  private static final int MAX_TOTAL_TOKENS = 22;
  private static final int MAX_CACHE_SIZE = 250_000;

  private static final Path BASE_NORM_DIR = Paths.get("/home/dndara/data/turkish/normalization");
  private static final Path OUT_DIR = BASE_NORM_DIR.resolve("custom_benchmark/nasty_5k");

  // Hard filter patterns
  private static final Pattern LEADING_USER_RE = Pattern.compile("^(?:@USER\\s+)+");
  private static final Pattern XENFORO_MERGE_RE =
      Pattern.compile("(?i)\\s*Mesaj otomatik birleştirildi:.*$");
  private static final Pattern EKSI_TRAILING_UI_RE =
      Pattern.compile("(?i)(?:\\s+|(?<=[.!?]))(?:görsel|spoiler)\\s*$");
  private static final Pattern MULTI_SPACE_RE = Pattern.compile("\\s+");
  private static final Pattern NON_ALNUM_KEY_RE = Pattern.compile("[^\\p{L}\\p{Nd}]+");
  private static final Pattern CENSOR_MASK_RE =
      Pattern.compile("\\*\\*+|[a-zA-ZçğıöşüÇĞİÖŞÜ]\\*+|\\*+[a-zA-ZçğıöşüÇĞİÖŞÜ]");
  private static final Pattern SMILE_GLUED_RE =
      Pattern.compile("(?i)\\w*smile\\b|:[a-z0-9_]{2,}:");
  private static final Pattern URL_LIKE_RE =
      Pattern.compile("(?i)(?:https?://|www\\.|\\.(?:com|net|org|tr|io|edu)\\b|gmail\\s+com|hotmail\\s+com|\\[/?quote|\\[/|<div|<br|@)");
  private static final Pattern GERMAN_SLUG_RE =
      Pattern.compile("(?i)\\b\\w*(?:ue|oe)\\w*(?:ue|oe)\\w*\\b|\\b(?:tuerkiye|coezuem|goeruentue|boeluek|uezere|guencel|oezel|yueksek|duesuek|muezik|koetue|goere)\\b");
  private static final Pattern SEPARATOR_SPEC_RE = Pattern.compile(" - | / ");
  private static final Pattern MULTI_DOT_SPLIT_RE = Pattern.compile("(?:\\.{2,}|[!?]+)\\s+");

  private static final Pattern PURE_ALPHA_TR_RE = Pattern.compile("^[a-zçğıöşü]+$");
  private static final Pattern TR_SPECIFIC_RE = Pattern.compile("[çğıöşüÇĞİÖŞÜ]");
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
  private static final Pattern CLITIC_SUFFIX_RE =
      Pattern.compile("^([a-zçğıöşü]{3,}?)(mıyım|miyim|muyum|müyüm|mısın|misin|musun|müsün|mıyız|miyiz|muyuz|müyüz|mı|mi|mu|mü|de|da|ki)$");
  private static final Pattern PRODUCTIVE_SUFFIX_RE =
      Pattern.compile("^[a-zçğıöşü]{6,}(?:iyorum|ıyorum|uyoruz|üyoruz|mıyorum|miyorum|abilirim|ebilirim|yebilirim|yabilirim|bilirm|lardan|lerden|larımız|lerimiz|sınız|siniz|caklar|cekler|duğunu|diğini|cağını|ceğini)$");
  private static final Pattern DETACHED_NUM_SUFFIX_RE =
      Pattern.compile("^(?:den|dan|te|ta|ten|tan|ye|ya|nin|nın|nun|nün|de|da)$");

  private static final List<String> PROFANITY_PREFIXES = Arrays.asList(
      "siktir", "sikey", "siker", "sikim", "sikiş", "soktuğum", "sokayım",
      "amk", "aq", "amina", "amına", "orospu", "oç", "piç", "yarrak", "yarak",
      "götveren", "pezevenk", "kahpe", "kaltak", "sürtük", "ibne", "puşt",
      "sıçayım", "sıçsın"
  );

  private static final Set<String> FOREIGN_AZ_STOPWORDS = new HashSet<>(Arrays.asList(
      "the", "and", "with", "this", "that", "from", "your", "have",
      "menim", "üçün", "yaxşı", "deyil", "nece", "qeder", "bele"
  ));

  private static final Set<String> DETACHED_SOLID_COMPOUNDS = new HashSet<>(Arrays.asList(
      "hiç bir", "bir çok", "bir kaç", "her hangi", "ön görü", "alt yapı"
  ));

  private final TurkishMorphology formalMorphology;
  private final TurkishMorphology informalMorphology;
  private final TurkishMorphology informalAsciiMorphology;
  private final LanguageIdentifier lid;

  private final Map<String, Boolean> formalCache = new HashMap<>();
  private final Map<String, Boolean> informalCache = new HashMap<>();
  private final Map<String, Boolean> informalVerbCache = new HashMap<>();
  private final Map<String, Boolean> asciiInformalCache = new HashMap<>();
  private final Map<String, Boolean> asciiInformalVerbCache = new HashMap<>();
  private final Map<String, Boolean> asciiProperOrAbbrOnlyCache = new HashMap<>();

  private final Set<String> forbiddenKeys = new HashSet<>();
  private final Set<String> knownSplits = new HashSet<>();
  private final Set<String> knownContractions = new HashSet<>();
  private final Set<String> knownMisspellings = new HashSet<>();
  private final Set<String> allowedForeignBrands = new HashSet<>(Arrays.asList(
      "iphone", "samsung", "xiaomi", "asus", "intel", "amd", "nvidia",
      "windows", "linux", "android", "ios", "google", "youtube", "instagram",
      "twitter", "twitch", "whatsapp", "telegram", "tiktok", "netflix",
      "spotify", "steam", "discord", "reddit", "trendyol", "hepsiburada",
      "amazon", "modem", "wifi", "internet", "online", "link", "topic",
      "post", "nick", "admin", "ban", "lag", "fps", "ping", "ram", "ssd",
      "usb", "pdf", "sms", "tus", "cent", "facebook", "turkcell", "mbit"
  ));
  private final Set<String> englishCommonNotElong = new HashSet<>(Arrays.asList(
      "deep", "miss", "kerr", "cool", "good", "look", "free", "full", "will",
      "sell", "call", "bill", "pass", "boss", "loss", "room", "book", "food",
      "door", "week", "feel", "need", "keep", "less", "mess", "off", "all",
      "well", "tell", "kill", "johnny"
  ));
  private final Set<String> coreLexicalCategories = new HashSet<>(Arrays.asList(
      "informal_verb", "clitic_or_split_merge", "contraction_or_slang",
      "elongation", "typo_recoverable", "ascii_partial"
  ));

  static class SourceFileSpec {
    final Path path;
    final String bucket;
    final int sampleLines; // <= 0 means read all lines

    SourceFileSpec(Path path, String bucket, int sampleLines) {
      this.path = path;
      this.bucket = bucket;
      this.sampleLines = sampleLines;
    }
  }

  static class Candidate {
    final String sourceCorpus;
    final String sourceFile;
    final String text;
    final int wordCount;
    final int charLen;
    final int noisyWordCount;
    final double noisyRatio;
    final double nastinessScore;
    final List<String> categories;
    final List<String> noisyTokens;
    final String asciiSkeletonSentence;
    final String open3Key;
    final Set<String> wordSkeletonSet;

    Candidate(
        String sourceCorpus,
        String sourceFile,
        String text,
        int wordCount,
        int charLen,
        int noisyWordCount,
        double noisyRatio,
        double nastinessScore,
        List<String> categories,
        List<String> noisyTokens,
        String asciiSkeletonSentence,
        String open3Key,
        Set<String> wordSkeletonSet) {
      this.sourceCorpus = sourceCorpus;
      this.sourceFile = sourceFile;
      this.text = text;
      this.wordCount = wordCount;
      this.charLen = charLen;
      this.noisyWordCount = noisyWordCount;
      this.noisyRatio = noisyRatio;
      this.nastinessScore = nastinessScore;
      this.categories = categories;
      this.noisyTokens = noisyTokens;
      this.asciiSkeletonSentence = asciiSkeletonSentence;
      this.open3Key = open3Key;
      this.wordSkeletonSet = wordSkeletonSet;
    }
  }

  static class OutputRecord {
    String id;
    String source_corpus;
    String source_file;
    String text;
    int word_count;
    int char_len;
    int noisy_word_count;
    double noisy_ratio;
    double nastiness_score;
    List<String> categories;
    List<String> noisy_tokens;
  }

  public MineNastySentences() throws IOException {
    System.out.println("[INIT] Loading Zemberek formal/informal/ascii morphologies & LID...");
    this.formalMorphology = TurkishMorphology.createWithDefaults();
    this.informalMorphology = TurkishMorphology.builder()
        .setLexicon(formalMorphology.getLexicon())
        .useInformalAnalysis()
        .build();
    this.informalAsciiMorphology = TurkishMorphology.builder()
        .setLexicon(formalMorphology.getLexicon())
        .useInformalAnalysis()
        .ignoreDiacriticsInAnalysis()
        .build();
    this.lid = LanguageIdentifier.fromInternalModelGroup("tr_group");

    loadForbiddenKeys();
    loadKnownSplitsAndContractions();
    loadExternalMisspellings();
  }

  private static String normalizeDedupKey(String text) {
    if (text == null) {
      return "";
    }
    String s = text.toLowerCase(TR);
    s = TurkishAlphabet.INSTANCE.toAscii(s);
    s = NON_ALNUM_KEY_RE.matcher(s).replaceAll(" ").trim();
    return MULTI_SPACE_RE.matcher(s).replaceAll(" ");
  }

  private void loadForbiddenKeys() throws IOException {
    List<Path> normFiles = Arrays.asList(
        BASE_NORM_DIR.resolve("benchmarks/multilexnorm/tur/train.norm"),
        BASE_NORM_DIR.resolve("benchmarks/multilexnorm/tur/test.norm"),
        BASE_NORM_DIR.resolve("benchmarks/multilexnorm/tur_deu/train.norm"),
        BASE_NORM_DIR.resolve("benchmarks/multilexnorm/tur_deu/test.norm")
    );
    for (Path p : normFiles) {
      if (!Files.exists(p)) {
        continue;
      }
      List<String> tokens = new ArrayList<>();
      try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
        String line;
        while ((line = br.readLine()) != null) {
          if (line.isBlank()) {
            if (!tokens.isEmpty()) {
              forbiddenKeys.add(normalizeDedupKey(String.join(" ", tokens)));
              tokens.clear();
            }
          } else {
            String[] parts = line.split("\t");
            if (parts.length > 0 && !parts[0].isBlank()) {
              tokens.add(parts[0].trim());
            }
          }
        }
        if (!tokens.isEmpty()) {
          forbiddenKeys.add(normalizeDedupKey(String.join(" ", tokens)));
        }
      }
    }

    List<Path> bounFiles = Arrays.asList(
        BASE_NORM_DIR.resolve("benchmarks/gecturk_boun_clitics/boun_source_train.txt"),
        BASE_NORM_DIR.resolve("benchmarks/gecturk_boun_clitics/boun_source_dev.txt"),
        BASE_NORM_DIR.resolve("benchmarks/gecturk_boun_clitics/boun_source_test.txt")
    );
    for (Path p : bounFiles) {
      if (!Files.exists(p)) {
        continue;
      }
      try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
        String line;
        while ((line = br.readLine()) != null) {
          if (line.startsWith("S ")) {
            forbiddenKeys.add(normalizeDedupKey(line.substring(2)));
          }
        }
      }
    }

    Path manifest = Paths.get("/home/dndara/data/turkish/distilled/PERMANENT_HELDOUT_TEST_SET_MANIFEST.json");
    if (Files.exists(manifest)) {
      String content = Files.readString(manifest, StandardCharsets.UTF_8);
      JsonElement root = JsonParser.parseString(content);
      if (root.isJsonObject()) {
        JsonObject sets = root.getAsJsonObject().getAsJsonObject("sets");
        if (sets != null) {
          for (Map.Entry<String, JsonElement> entry : sets.entrySet()) {
            JsonObject info = entry.getValue().getAsJsonObject();
            if (info.has("sentence_texts") && info.get("sentence_texts").isJsonArray()) {
              for (JsonElement el : info.getAsJsonArray("sentence_texts")) {
                forbiddenKeys.add(normalizeDedupKey(el.getAsString()));
              }
            }
          }
        }
      }
    }
    forbiddenKeys.remove("");
    System.out.println("[INIT] Forbidden anti-leakage keys loaded: " + forbiddenKeys.size());
  }

  private void loadKnownSplitsAndContractions() throws IOException {
    try (InputStream is = MineNastySentences.class.getResourceAsStream("/normalization/multi-word-replacements")) {
      if (is != null) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
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

    knownSplits.addAll(Arrays.asList(
        "herşey", "birşey", "bişey", "bişi", "hiçbirşey", "hiçbişey", "pekçok",
        "hemde", "yinede", "tabiki", "tabikide", "bencede", "sencede",
        "ozaman", "okadar", "değilmi", "dimi", "olurmu", "banada", "sanada", "herkez"
    ));
    // Ensure valid words are never mistakenly forced into splits
    knownSplits.remove("herhangi");
    knownSplits.remove("bende");

    knownContractions.addAll(Arrays.asList(
        "bi", "tmm", "slm", "mrb", "nbr", "naber", "cnm", "knk",
        "bnde", "snde", "bn", "fln", "felan", "heralde", "hrlde",
        "deil", "diil", "inş", "ins", "tşk", "tsk", "tskler", "tsklr",
        "msj", "cvp", "walla", "die", "nie", "baya", "orjinal", "herkez",
        "şarz", "yalnış", "yanlız", "küsür", "bağzı",
        "napcam", "napcan", "napcaz", "napıyon", "napıyosun", "sölüo", "sölüyor"
    ));
    // Explicit constraint: kanka is valid slang, do not flag as error
    knownContractions.remove("kanka");
  }

  private void loadExternalMisspellings() throws IOException {
    Path asim = BASE_NORM_DIR.resolve("lexicons_external/asimokby_spelling_dict/base_turkish_misspellings.csv");
    if (Files.exists(asim)) {
      try (BufferedReader br = Files.newBufferedReader(asim, StandardCharsets.UTF_8)) {
        String line = br.readLine(); // skip header
        while ((line = br.readLine()) != null) {
          int comma = line.indexOf(',');
          if (comma > 0) {
            String wrong = line.substring(0, comma).trim().toLowerCase(TR);
            String right = line.substring(comma + 1).trim().toLowerCase(TR);
            if (wrong.length() >= 4
                && !wrong.equals(right)
                && !TurkishAlphabet.INSTANCE.toAscii(wrong).equals(TurkishAlphabet.INSTANCE.toAscii(right))
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

    Path emine = BASE_NORM_DIR.resolve("lexicons_external/eminesener_boun/normalization.tsv");
    if (Files.exists(emine)) {
      try (BufferedReader br = Files.newBufferedReader(emine, StandardCharsets.UTF_8)) {
        String line;
        while ((line = br.readLine()) != null) {
          if (line.isBlank() || line.startsWith("#")) {
            continue;
          }
          String[] parts = line.split("\t");
          if (parts.length >= 2) {
            String wrong = parts[0].trim().toLowerCase(TR);
            String right = parts[1].trim().toLowerCase(TR);
            if (wrong.equals("surface") && right.equals("normalized")) {
              continue;
            }
            if (wrong.length() >= 3
                && !wrong.equals(right)
                && !TurkishAlphabet.INSTANCE.toAscii(wrong).equals(TurkishAlphabet.INSTANCE.toAscii(right))
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
    System.out.println("[INIT] Splits=" + knownSplits.size()
        + ", Contractions=" + knownContractions.size()
        + ", Misspellings=" + knownMisspellings.size());
  }

  private void trimCachesIfNeeded() {
    if (formalCache.size() > MAX_CACHE_SIZE) {
      formalCache.clear();
      informalCache.clear();
      informalVerbCache.clear();
      asciiInformalCache.clear();
      asciiInformalVerbCache.clear();
      asciiProperOrAbbrOnlyCache.clear();
    }
  }

  private boolean isFormalWordLower(String wl) {
    Boolean cached = formalCache.get("L:" + wl);
    if (cached != null) {
      return cached;
    }
    trimCachesIfNeeded();
    WordAnalysis wa = formalMorphology.analyze(wl);
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
    formalCache.put("L:" + wl, ok);
    return ok;
  }

  private boolean isFormalValid(String surface, boolean isSentenceStart) {
    String wl = surface.toLowerCase(TR);
    if (knownContractions.contains(wl) || knownSplits.contains(wl)) {
      return false;
    }
    String key = (isSentenceStart ? "S:" : "M:") + surface;
    Boolean cached = formalCache.get(key);
    if (cached != null) {
      return cached;
    }
    trimCachesIfNeeded();

    boolean ok = checkFormalSurface(surface);
    if (!ok && (isSentenceStart || (!surface.isEmpty() && Character.isUpperCase(surface.charAt(0))))) {
      ok = checkFormalSurface(wl);
    }
    formalCache.put(key, ok);
    return ok;
  }

  private boolean checkFormalSurface(String form) {
    WordAnalysis wa = formalMorphology.analyze(form);
    boolean hasApostrophe = form.indexOf('\'') >= 0 || form.indexOf('’') >= 0;
    boolean allLower = form.equals(form.toLowerCase(TR));
    for (SingleAnalysis sa : wa.getAnalysisResults()) {
      if (sa.isUnknown() || sa.containsInformalMorpheme()) {
        continue;
      }
      if (allLower && !hasApostrophe) {
        SecondaryPos sp = sa.getDictionaryItem().secondaryPos;
        if (sp == SecondaryPos.ProperNoun || sp == SecondaryPos.Abbreviation) {
          continue;
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
    WordAnalysis wa = informalMorphology.analyze(wl);
    boolean valid = false;
    boolean anyInformal = false;
    boolean anyNonInformal = false;
    for (SingleAnalysis sa : wa.getAnalysisResults()) {
      if (sa.isUnknown()) {
        continue;
      }
      SecondaryPos sp = sa.getDictionaryItem().secondaryPos;
      if (sp == SecondaryPos.Abbreviation) {
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
    informalCache.put(wl, valid);
    informalVerbCache.put(wl, onlyInformal);
    return requireInformalMorpheme ? onlyInformal : valid;
  }

  private boolean analyzeAsciiInformal(String wl, int mode) {
    // mode 0 = any valid (prefer non-Abbreviation), mode 1 = onlyInformal, mode 2 = inflected ProperNoun/Abbreviation only
    if (!asciiInformalCache.containsKey(wl)) {
      WordAnalysis wa = informalAsciiMorphology.analyze(wl);
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
      boolean accepted = validNonAbbr || (anyValid && inflectedProperOrAbbr);
      asciiInformalCache.put(wl, accepted);
      asciiInformalVerbCache.put(wl, anyInformal && !anyNonInformal);
      asciiProperOrAbbrOnlyCache.put(wl, !nonProperNormalValid && inflectedProperOrAbbr);
    }
    if (mode == 0) {
      return asciiInformalCache.get(wl);
    } else if (mode == 1) {
      return asciiInformalVerbCache.get(wl);
    } else {
      return asciiProperOrAbbrOnlyCache.get(wl);
    }
  }

  private boolean isBareLowerProperNounExact(String wl) {
    WordAnalysis wa = formalMorphology.analyze(wl);
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
    // For 2-char repetitions (no 3+ repeat), require Turkish chars, length >= 5, or common short chat elongations
    if (ELONG_3_RE.matcher(wl).find()) {
      return false;
    }
    boolean allowShort2 = TR_SPECIFIC_RE.matcher(wl).find()
        || wl.length() >= 5
        || Arrays.asList("cokk", "heep", "yavv", "anaa", "yokk", "tamm", "yaa", "haa").contains(wl);
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
    // Single duplicate deletion
    for (int i = 0; i + 1 < wl.length(); i++) {
      if (wl.charAt(i) == wl.charAt(i + 1)) {
        String cand = wl.substring(0, i) + wl.substring(i + 1);
        if (analyzeAsciiCommonValid(cand)) {
          return true;
        }
      }
    }
    // Single adjacent transposition
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
    // Only flag attached clitics when the whole token isn't already a valid Turkish word/locative (under diacritic tolerance)
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

  private static boolean hasUnicodeLetter(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (Character.isLetter(s.charAt(i))) {
        return true;
      }
    }
    return false;
  }

  private static boolean isLexicalToken(Token t) {
    Token.Type type = t.type;
    boolean okType = type == Token.Type.Word
        || type == Token.Type.WordAlphanumerical
        || type == Token.Type.WordWithSymbol
        || type == Token.Type.Abbreviation
        || type == Token.Type.AbbreviationWithDots
        || type == Token.Type.UnknownWord
        || type == Token.Type.Number;
    return okType && hasUnicodeLetter(t.content);
  }

  private static boolean isRejectedTokenType(Token.Type type) {
    return type == Token.Type.URL
        || type == Token.Type.Email
        || type == Token.Type.Mention
        || type == Token.Type.HashTag
        || type == Token.Type.MetaTag
        || type == Token.Type.Unknown;
  }

  private static String cleanRawLine(String line, String bucket) {
    if (line == null) {
      return "";
    }
    String s = line.replace('\u00A0', ' ').replace("\uFEFF", "");
    s = LEADING_USER_RE.matcher(s).replaceFirst("");
    s = XENFORO_MERGE_RE.matcher(s).replaceFirst("");
    if ("eksisozluk".equals(bucket)) {
      s = EKSI_TRAILING_UI_RE.matcher(s).replaceFirst("");
    }
    s = MULTI_SPACE_RE.matcher(s).replaceAll(" ").trim();
    return s;
  }

  private boolean passesHardStringGates(String s, String bucket) {
    if (s.isEmpty() || s.length() < MIN_CHARS) {
      return false;
    }
    // Mojibake & HTML entities
    if (s.indexOf('Ã') >= 0 || s.indexOf('Ä') >= 0 || s.indexOf('Å') >= 0
        || s.indexOf('\uFFFD') >= 0 || s.contains("&quot;") || s.contains("&amp;")
        || s.contains("&#") || s.contains("&lt;") || s.contains("&gt;")) {
      return false;
    }
    if (URL_LIKE_RE.matcher(s).find()) {
      return false;
    }
    if (CENSOR_MASK_RE.matcher(s).find()) {
      return false;
    }
    if (SMILE_GLUED_RE.matcher(s).find()) {
      return false;
    }
    if ("search_queries".equals(bucket) && GERMAN_SLUG_RE.matcher(s).find()) {
      return false;
    }

    // Check scripts (reject Cyrillic, Arabic, Greek, Azerbaijani ə) & letter/digit/upper ratios
    int letters = 0;
    int upper = 0;
    int digits = 0;
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      if (ch == 'ə' || ch == 'Ə') {
        return false;
      }
      Character.UnicodeBlock ub = Character.UnicodeBlock.of(ch);
      if (ub == Character.UnicodeBlock.CYRILLIC
          || ub == Character.UnicodeBlock.ARABIC
          || ub == Character.UnicodeBlock.GREEK) {
        return false;
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
    if (letters < 10) {
      return false;
    }
    if ((double) upper / letters > 0.55) {
      return false;
    }
    if ((double) digits / s.length() > 0.12) {
      return false;
    }

    int sepCount = 0;
    Matcher sepMatcher = SEPARATOR_SPEC_RE.matcher(s);
    while (sepMatcher.find()) {
      sepCount++;
      if (sepCount >= 3) {
        return false;
      }
    }
    return true;
  }

  private Candidate evaluateSegment(String segment, String bucket, String sourceFile) {
    segment = MULTI_SPACE_RE.matcher(segment).replaceAll(" ").trim();
    if (segment.length() < MIN_CHARS || segment.length() > MAX_CHARS) {
      return null;
    }
    if (!Character.isLetterOrDigit(segment.charAt(0))) {
      return null;
    }
    if (!passesHardStringGates(segment, bucket)) {
      return null;
    }

    String dedupKey = normalizeDedupKey(segment);
    if (dedupKey.isEmpty() || forbiddenKeys.contains(dedupKey)) {
      return null;
    }

    List<Token> tokens = TurkishTokenizer.DEFAULT.tokenize(segment);
    if (tokens.size() > MAX_TOTAL_TOKENS) {
      return null;
    }

    List<Token> lexical = new ArrayList<>();
    for (Token t : tokens) {
      if (isRejectedTokenType(t.type)) {
        return null;
      }
      if (isLexicalToken(t)) {
        lexical.add(t);
      }
    }
    int wordCount = lexical.size();
    if (wordCount < MIN_WORDS || wordCount > MAX_WORDS) {
      return null;
    }

    boolean sentenceHasTurkishChars = TR_SPECIFIC_RE.matcher(segment).find();

    // Check severe profanity & foreign stopwords
    for (Token t : lexical) {
      String wl = t.content.toLowerCase(TR);
      if (FOREIGN_AZ_STOPWORDS.contains(wl)) {
        return null;
      }
      for (String prof : PROFANITY_PREFIXES) {
        if (wl.equals(prof) || (prof.length() >= 4 && wl.startsWith(prof))) {
          return null;
        }
      }
    }

    Set<String> categories = new TreeSet<>();
    Set<Integer> noisyIndices = new HashSet<>();
    List<String> noisyTokens = new ArrayList<>();
    int unknownUnexplained = 0;
    int recoverableOrFormalTrWords = 0;

    for (int i = 0; i < wordCount; i++) {
      Token tok = lexical.get(i);
      String w = tok.content;
      String wl = w.toLowerCase(TR);
      boolean isStart = (i == 0);

      if (isFormalValid(w, isStart)) {
        recoverableOrFormalTrWords++;
        continue;
      }
      if (allowedForeignBrands.contains(wl) || englishCommonNotElong.contains(wl)) {
        continue;
      }

      boolean exactMorphValid = analyzeInformalExact(wl, false);
      boolean bareLowerProp = isBareLowerProperNounExact(wl);
      boolean matchedAny = false;
      boolean hasTrSpecific = TR_SPECIFIC_RE.matcher(wl).find();

      // 1. elongation (never on words already accepted by exact informal morphology)
      if (!exactMorphValid
          && PURE_ALPHA_TR_RE.matcher(wl).matches()
          && (ELONG_3_RE.matcher(wl).find() || ELONG_2_VOWEL_OR_END_RE.matcher(wl).find())
          && checkMorphRecoverableAfterCollapse(wl)) {
        categories.add("elongation");
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
            if (!hasTrSpecific && TR_SPECIFIC_RE.matcher(c3).find()) {
              neededAsciiForVerb = false;
            }
          }
        }
      }
      if (isInformalVerb) {
        categories.add("informal_verb");
        if (neededAsciiForVerb) {
          categories.add(hasTrSpecific || sentenceHasTurkishChars ? "ascii_partial" : "ascii_full");
        }
        matchedAny = true;
      }

      // 3. clitic_or_split_merge (unigram part)
      if (knownSplits.contains(wl) || isCliticSplitMatch(wl)) {
        categories.add("clitic_or_split_merge");
        matchedAny = true;
      }

      // 4. contraction_or_slang
      if (knownContractions.contains(wl)) {
        categories.add("contraction_or_slang");
        matchedAny = true;
      }

      // 5. numeral_or_apostrophe (exclude bare lowercase ProperNouns like türk/ispanyollar)
      if (DIGIT_WITH_LETTERS_RE.matcher(wl).matches() && w.indexOf('\'') < 0 && w.indexOf('’') < 0) {
        categories.add("numeral_or_apostrophe");
        matchedAny = true;
      } else if (!bareLowerProp
          && PURE_ALPHA_TR_RE.matcher(wl).matches()
          && w.equals(wl)
          && w.indexOf('\'') < 0
          && (analyzeAsciiInformal(wl, 2))) {
        categories.add("numeral_or_apostrophe");
        matchedAny = true;
      }

      // 6. ascii_partial / ascii_full
      if (!isInformalVerb
          && PURE_ALPHA_TR_RE.matcher(wl).matches()
          && !exactMorphValid
          && analyzeAsciiInformal(wl, 0)) {
        if (hasTrSpecific || sentenceHasTurkishChars) {
          categories.add("ascii_partial");
        } else {
          categories.add("ascii_full");
        }
        matchedAny = true;
      }

      // 7. typo_recoverable (never on words already accepted by exact or ascii-tolerant morphology)
      if (!matchedAny
          && !exactMorphValid
          && !analyzeAsciiInformal(wl, 0)
          && PURE_ALPHA_TR_RE.matcher(wl).matches()
          && wl.length() >= 4) {
        if (knownMisspellings.contains(wl)
            || checkSingleCollapseOrTransposition(wl)
            || PRODUCTIVE_SUFFIX_RE.matcher(wl).matches()) {
          categories.add("typo_recoverable");
          matchedAny = true;
        }
      }

      if (matchedAny) {
        noisyIndices.add(i);
        noisyTokens.add(w);
        recoverableOrFormalTrWords++;
      } else if (bareLowerProp) {
        // Minor capitalization-only ProperNoun: not a lexical error, also not an unknown word
        recoverableOrFormalTrWords++;
      } else {
        unknownUnexplained++;
      }
    }

    // Check bigram split/merge spans across adjacent tokens
    for (int i = 0; i + 1 < tokens.size(); i++) {
      Token t1 = tokens.get(i);
      Token t2 = tokens.get(i + 1);
      String l1 = t1.content.toLowerCase(TR);
      String l2 = t2.content.toLowerCase(TR);
      String pair = l1 + " " + l2;
      boolean bigramHit = DETACHED_SOLID_COMPOUNDS.contains(pair)
          || (("de".equals(l1) || "da".equals(l1)) && "ki".equals(l2))
          || (PURE_DIGITS_RE.matcher(l1).matches() && DETACHED_NUM_SUFFIX_RE.matcher(l2).matches());
      if (bigramHit) {
        categories.add("clitic_or_split_merge");
        noisyTokens.add(t1.content + " " + t2.content);
        int lexIdx = lexical.indexOf(t2);
        if (lexIdx >= 0) {
          noisyIndices.add(lexIdx);
        } else {
          noisyIndices.add(1000 + i);
        }
      }
    }

    // 2. Unknown threshold
    if (unknownUnexplained > 1 || ((double) unknownUnexplained / wordCount) > 0.15) {
      return null;
    }

    // 3. Language ID check
    String detectedLang = lid.identify(segment);
    if (!"tr".equals(detectedLang)) {
      if (!(unknownUnexplained == 0 && recoverableOrFormalTrWords == wordCount)) {
        return null;
      }
    }

    int noisyWordCount = noisyIndices.size();
    double noisyRatio = (double) noisyWordCount / wordCount;

    // 4. Minimum nastiness (must have at least one core lexical category beyond pure ascii_full / apostrophes)
    if (noisyWordCount < 3 || noisyRatio < 0.30) {
      return null;
    }
    if (categories.size() == 1 && categories.contains("ascii_full")) {
      return null;
    }
    boolean hasCoreLexical = false;
    for (String cat : categories) {
      if (coreLexicalCategories.contains(cat)) {
        hasCoreLexical = true;
        break;
      }
    }
    if (!hasCoreLexical) {
      return null;
    }
    if (categories.size() < 2 && noisyWordCount < 4) {
      return null;
    }

    // 5. Composite Nastiness Score
    double bonus = 0.0;
    if (categories.contains("informal_verb")) bonus += 1.2;
    if (categories.contains("clitic_or_split_merge")) bonus += 1.0;
    if (categories.contains("ascii_partial")) bonus += 1.0;
    if (categories.contains("typo_recoverable")) bonus += 0.9;
    if (categories.contains("contraction_or_slang")) bonus += 0.8;
    if (categories.contains("elongation")) bonus += 0.7;
    if (categories.contains("numeral_or_apostrophe")) bonus += 0.3;

    double score = 2.4 * categories.size() + 1.35 * noisyWordCount + 3.0 * noisyRatio + bonus;

    Set<String> wordSkelSet = new HashSet<>();
    StringBuilder open3 = new StringBuilder();
    for (int i = 0; i < lexical.size(); i++) {
      String skel = TurkishAlphabet.INSTANCE.toAscii(lexical.get(i).content.toLowerCase(TR));
      wordSkelSet.add(skel);
      if (i < 3) {
        if (i > 0) open3.append(' ');
        open3.append(skel);
      }
    }

    return new Candidate(
        bucket,
        sourceFile,
        segment,
        wordCount,
        segment.length(),
        noisyWordCount,
        Math.round(noisyRatio * 10000.0) / 10000.0,
        Math.round(score * 1000.0) / 1000.0,
        new ArrayList<>(categories),
        noisyTokens,
        dedupKey,
        open3.toString(),
        wordSkelSet
    );
  }

  private Candidate evaluateLineForBestSegment(String rawLine, String bucket, String sourceFile) {
    String cleaned = cleanRawLine(rawLine, bucket);
    if (cleaned.length() < MIN_CHARS) {
      return null;
    }

    List<Token> roughTokens = TurkishTokenizer.DEFAULT.tokenize(cleaned);
    int roughWords = 0;
    for (Token t : roughTokens) {
      if (isLexicalToken(t)) {
        roughWords++;
      }
    }

    if (roughWords >= MIN_WORDS && roughWords <= MAX_WORDS && cleaned.length() <= MAX_CHARS) {
      return evaluateSegment(cleaned, bucket, sourceFile);
    }

    if (roughWords >= MAX_WORDS + 1 && roughWords <= 75) {
      List<String> sents = TurkishSentenceExtractor.DEFAULT.fromParagraph(cleaned);
      Candidate best = null;
      for (String sent : sents) {
        String sTrim = sent.trim();
        if (sTrim.length() < MIN_CHARS) {
          continue;
        }
        if (sTrim.length() <= MAX_CHARS) {
          Candidate c = evaluateSegment(sTrim, bucket, sourceFile);
          if (c != null && (best == null || c.nastinessScore > best.nastinessScore)) {
            best = c;
          }
        } else {
          for (String sub : MULTI_DOT_SPLIT_RE.split(sTrim)) {
            Candidate c = evaluateSegment(sub, bucket, sourceFile);
            if (c != null && (best == null || c.nastinessScore > best.nastinessScore)) {
              best = c;
            }
          }
        }
      }
      return best;
    }
    return null;
  }

  private List<String> sampleLinesFromFile(Path file, int targetSampleLines) throws IOException {
    List<String> lines = new ArrayList<>();
    if (!Files.exists(file)) {
      System.err.println("[WARN] Missing file: " + file);
      return lines;
    }

    long fileSize = Files.size(file);
    if (targetSampleLines <= 0 || fileSize < 25_000_000L) {
      // Read entire file (unless targetSampleLines > 0 and file has way more lines, still read and stride or cap)
      try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        if (targetSampleLines <= 0) {
          String line;
          while ((line = br.readLine()) != null) {
            lines.add(line);
          }
          return lines;
        }
      }
    }

    // Evenly spaced byte-offset chunk sampling (UTF-8 safe)
    int numChunks = 200;
    int linesPerChunk = Math.max(1, (targetSampleLines + numChunks - 1) / numChunks);
    long step = Math.max(1L, fileSize / numChunks);

    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
      CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPLACE)
          .onUnmappableCharacter(CodingErrorAction.REPLACE);
      ByteBuffer byteBuf = ByteBuffer.allocate(64 * 1024);

      for (int chunk = 0; chunk < numChunks; chunk++) {
        long offset = chunk * step;
        if (offset >= fileSize) {
          break;
        }
        ch.position(offset);

        // If offset > 0, discard bytes until first '\n' to align cleanly on UTF-8 line start
        if (offset > 0) {
          boolean foundNl = false;
          while (!foundNl) {
            byteBuf.clear();
            int read = ch.read(byteBuf);
            if (read <= 0) {
              break;
            }
            byteBuf.flip();
            while (byteBuf.hasRemaining()) {
              byte b = byteBuf.get();
              if (b == (byte) '\n') {
                // Rewind FileChannel right after this '\n'
                long posAfterNl = ch.position() - byteBuf.remaining();
                ch.position(posAfterNl);
                foundNl = true;
                break;
              }
            }
          }
          if (!foundNl) {
            continue;
          }
        }

        // Read linesPerChunk UTF-8 lines starting from the current FileChannel position
        // Read up to ~256 KB buffer per chunk directly from channel to avoid BufferedReader closing channel
        StringBuilder partial = new StringBuilder();
        int collectedInChunk = 0;
        byte[] arr = new byte[64 * 1024];
        while (collectedInChunk < linesPerChunk) {
          ByteBuffer bb = ByteBuffer.wrap(arr);
          int n = ch.read(bb);
          if (n <= 0) {
            if (partial.length() > 0) {
              lines.add(partial.toString());
              collectedInChunk++;
            }
            break;
          }
          String str;
          try {
            bb.flip();
            str = decoder.decode(bb).toString();
          } catch (CharacterCodingException e) {
            str = new String(arr, 0, n, StandardCharsets.UTF_8);
          }
          int start = 0;
          for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == '\n') {
              partial.append(str, start, i);
              if (partial.length() > 0 && partial.charAt(partial.length() - 1) == '\r') {
                partial.setLength(partial.length() - 1);
              }
              lines.add(partial.toString());
              partial.setLength(0);
              collectedInChunk++;
              start = i + 1;
              if (collectedInChunk >= linesPerChunk) {
                break;
              }
            }
          }
          if (collectedInChunk < linesPerChunk && start < str.length()) {
            partial.append(str, start, str.length());
          }
          if (partial.length() > 32768) {
            partial.setLength(0);
            break;
          }
        }
        decoder.reset();
      }
    }
    return lines;
  }

  private static double jaccard(Set<String> a, Set<String> b) {
    if (a.isEmpty() || b.isEmpty()) {
      return 0.0;
    }
    int inter = 0;
    Set<String> smaller = a.size() <= b.size() ? a : b;
    Set<String> larger = a.size() <= b.size() ? b : a;
    for (String s : smaller) {
      if (larger.contains(s)) {
        inter++;
      }
    }
    int union = a.size() + b.size() - inter;
    return union == 0 ? 0.0 : (double) inter / union;
  }

  public void run() throws IOException {
    Files.createDirectories(OUT_DIR);

    Map<String, Integer> quotas = new LinkedHashMap<>();
    quotas.put("kadinlar_klubu", 1200);
    quotas.put("forums_modern_2020s", 900);
    quotas.put("kizlarsoruyor", 650);
    quotas.put("reviews_ecommerce", 650);
    quotas.put("eksisozluk", 500);
    quotas.put("classic_forums_comments", 450);
    quotas.put("search_queries", 350);
    quotas.put("tweets_social", 300);

    List<SourceFileSpec> specs = Arrays.asList(
        new SourceFileSpec(Paths.get("/home/dndara/data/turkish/noisy/kadinlar-klubu.txt"), "kadinlar_klubu", 350_000),
        new SourceFileSpec(BASE_NORM_DIR.resolve("corpora_plain/forums_modern_2020s.txt"), "forums_modern_2020s", 250_000),
        new SourceFileSpec(Paths.get("/home/dndara/data/turkish/clean/www.kizlarsoruyor.com.txt"), "kizlarsoruyor", 250_000),
        new SourceFileSpec(BASE_NORM_DIR.resolve("corpora_plain/reviews_trendyol_2025.txt"), "reviews_ecommerce", 100_000),
        new SourceFileSpec(BASE_NORM_DIR.resolve("corpora_plain/reviews_winvoker.txt"), "reviews_ecommerce", 100_000),
        new SourceFileSpec(BASE_NORM_DIR.resolve("corpora_plain/reviews_beyazperde.txt"), "reviews_ecommerce", 0),
        new SourceFileSpec(BASE_NORM_DIR.resolve("corpora_plain/complaints_sikayetler.txt"), "reviews_ecommerce", 0),
        new SourceFileSpec(BASE_NORM_DIR.resolve("corpora_plain/eksisozluk_all.txt"), "eksisozluk", 0),
        new SourceFileSpec(Paths.get("/home/dndara/data/turkish/clean/forum.memurlar.net.txt"), "classic_forums_comments", 80_000),
        new SourceFileSpec(Paths.get("/home/dndara/data/turkish/clean/forum.shiftdelete.net.txt"), "classic_forums_comments", 60_000),
        new SourceFileSpec(Paths.get("/home/dndara/data/turkish/clean/www.tvyorumlari.com.txt"), "classic_forums_comments", 0),
        new SourceFileSpec(Paths.get("/home/dndara/data/turkish/clean/www.nurturia.com.tr.txt"), "classic_forums_comments", 0),
        new SourceFileSpec(BASE_NORM_DIR.resolve("queries/plain/forum_search_thread_slugs.txt"), "search_queries", 0),
        new SourceFileSpec(BASE_NORM_DIR.resolve("queries/plain/ecommerce_customer_queries.txt"), "search_queries", 0),
        new SourceFileSpec(BASE_NORM_DIR.resolve("corpora_plain/tweets_offenseval2020.txt"), "tweets_social", 0)
    );

    Map<String, List<Candidate>> bucketPools = new LinkedHashMap<>();
    for (String b : quotas.keySet()) {
      bucketPools.put(b, new ArrayList<>());
    }

    for (SourceFileSpec spec : specs) {
      long t0 = System.currentTimeMillis();
      List<String> rawLines = sampleLinesFromFile(spec.path, spec.sampleLines);
      String fileName = spec.path.getFileName().toString();
      int accepted = 0;
      List<Candidate> pool = bucketPools.get(spec.bucket);
      int cap = quotas.get(spec.bucket) * 6;
      for (String raw : rawLines) {
        Candidate c = evaluateLineForBestSegment(raw, spec.bucket, fileName);
        if (c != null) {
          pool.add(c);
          accepted++;
          if (pool.size() >= cap * 2) {
            break;
          }
        }
      }
      long dt = System.currentTimeMillis() - t0;
      System.out.printf("[MINE] bucket=%-24s file=%-34s sampled=%7d accepted=%6d (%d ms)%n",
          spec.bucket, fileName, rawLines.size(), accepted, dt);
    }

    // Sort every bucket deterministically
    Comparator<Candidate> cmp = Comparator
        .comparingDouble((Candidate c) -> c.nastinessScore).reversed()
        .thenComparing(c -> c.asciiSkeletonSentence);
    for (List<Candidate> pool : bucketPools.values()) {
      pool.sort(cmp);
    }

    // Global deduplication & diversity state
    Set<String> seenExact = new HashSet<>();
    Map<String, Integer> open3Counts = new HashMap<>();
    Map<String, List<Candidate>> wordIndex = new HashMap<>();
    List<Candidate> selected = new ArrayList<>(TARGET_TOTAL);
    Map<String, Integer> nextIdx = new HashMap<>();
    Map<String, Integer> selectedPerBucket = new LinkedHashMap<>();
    for (String b : quotas.keySet()) {
      nextIdx.put(b, 0);
      selectedPerBucket.put(b, 0);
    }

    // Phase 1: fill quotas per bucket in order
    for (Map.Entry<String, Integer> qEntry : quotas.entrySet()) {
      String bucket = qEntry.getKey();
      int quota = qEntry.getValue();
      List<Candidate> pool = bucketPools.get(bucket);
      int idx = nextIdx.get(bucket);
      while (idx < pool.size() && selectedPerBucket.get(bucket) < quota) {
        Candidate c = pool.get(idx++);
        if (tryAcceptCandidate(c, seenExact, open3Counts, wordIndex)) {
          selected.add(c);
          selectedPerBucket.put(bucket, selectedPerBucket.get(bucket) + 1);
        }
      }
      nextIdx.put(bucket, idx);
      System.out.printf("[QUOTA] %-24s target=%4d got=%4d (pool=%d)%n",
          bucket, quota, selectedPerBucket.get(bucket), pool.size());
    }

    // Phase 2: spillover round-robin from top remaining pools
    List<String> spilloverOrder = Arrays.asList(
        "kadinlar_klubu", "forums_modern_2020s", "kizlarsoruyor", "reviews_ecommerce",
        "eksisozluk", "classic_forums_comments", "search_queries", "tweets_social"
    );
    boolean madeProgress = true;
    while (selected.size() < TARGET_TOTAL && madeProgress) {
      madeProgress = false;
      for (String bucket : spilloverOrder) {
        if (selected.size() >= TARGET_TOTAL) {
          break;
        }
        List<Candidate> pool = bucketPools.get(bucket);
        int idx = nextIdx.get(bucket);
        while (idx < pool.size()) {
          Candidate c = pool.get(idx++);
          if (tryAcceptCandidate(c, seenExact, open3Counts, wordIndex)) {
            selected.add(c);
            selectedPerBucket.put(bucket, selectedPerBucket.get(bucket) + 1);
            madeProgress = true;
            break;
          }
        }
        nextIdx.put(bucket, idx);
      }
    }

    if (selected.size() != TARGET_TOTAL) {
      throw new IllegalStateException("Only selected " + selected.size() + " candidates (expected " + TARGET_TOTAL + ")");
    }

    // Deterministically shuffle so IDs interleave domains cleanly
    Collections.shuffle(selected, new Random(42));

    Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    Gson prettyGson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    Path outTxt = OUT_DIR.resolve("nasty_5000_sentences.txt");
    Path outJsonl = OUT_DIR.resolve("nasty_5000_candidates.jsonl");
    Path outSummary = OUT_DIR.resolve("nasty_5000_summary.json");

    Map<Integer, Integer> wordCountHist = new TreeMap<>();
    Map<Integer, Integer> noisyWordCountHist = new TreeMap<>();
    Map<String, Integer> categoryCounts = new TreeMap<>();
    long sumWordCount = 0;
    long sumNoisyWordCount = 0;
    double sumNoisyRatio = 0.0;
    double sumScore = 0.0;

    List<OutputRecord> records = new ArrayList<>(TARGET_TOTAL);
    try (BufferedWriter txtW = Files.newBufferedWriter(outTxt, StandardCharsets.UTF_8);
         BufferedWriter jsonlW = Files.newBufferedWriter(outJsonl, StandardCharsets.UTF_8)) {
      for (int i = 0; i < selected.size(); i++) {
        Candidate c = selected.get(i);
        OutputRecord rec = new OutputRecord();
        rec.id = String.format(Locale.ROOT, "nasty5k_%05d", i + 1);
        rec.source_corpus = c.sourceCorpus;
        rec.source_file = c.sourceFile;
        rec.text = c.text;
        rec.word_count = c.wordCount;
        rec.char_len = c.charLen;
        rec.noisy_word_count = c.noisyWordCount;
        rec.noisy_ratio = c.noisyRatio;
        rec.nastiness_score = c.nastinessScore;
        rec.categories = c.categories;
        rec.noisy_tokens = c.noisyTokens;
        records.add(rec);

        txtW.write(c.text);
        txtW.newLine();
        jsonlW.write(gson.toJson(rec));
        jsonlW.newLine();

        wordCountHist.merge(c.wordCount, 1, Integer::sum);
        noisyWordCountHist.merge(c.noisyWordCount, 1, Integer::sum);
        for (String cat : c.categories) {
          categoryCounts.merge(cat, 1, Integer::sum);
        }
        sumWordCount += c.wordCount;
        sumNoisyWordCount += c.noisyWordCount;
        sumNoisyRatio += c.noisyRatio;
        sumScore += c.nastinessScore;
      }
    }

    // Build 25 diverse sample records (top 10 + 15 stratified)
    List<OutputRecord> sortedByScore = new ArrayList<>(records);
    sortedByScore.sort(Comparator.comparingDouble((OutputRecord r) -> r.nastiness_score).reversed()
        .thenComparing(r -> r.id));
    List<OutputRecord> samples25 = new ArrayList<>();
    Set<String> sampleIds = new HashSet<>();
    for (int i = 0; i < Math.min(10, sortedByScore.size()); i++) {
      samples25.add(sortedByScore.get(i));
      sampleIds.add(sortedByScore.get(i).id);
    }
    int stepSample = Math.max(1, records.size() / 15);
    for (int i = 0; i < records.size() && samples25.size() < 25; i += stepSample) {
      OutputRecord r = records.get(i);
      if (sampleIds.add(r.id)) {
        samples25.add(r);
      }
    }

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("total_sentences", selected.size());
    summary.put("counts_per_source_corpus", selectedPerBucket);
    summary.put("word_count_histogram", wordCountHist);
    summary.put("noisy_word_count_histogram", noisyWordCountHist);
    summary.put("category_occurrence_counts", categoryCounts);
    summary.put("mean_word_count", Math.round(((double) sumWordCount / selected.size()) * 1000.0) / 1000.0);
    summary.put("mean_noisy_word_count", Math.round(((double) sumNoisyWordCount / selected.size()) * 1000.0) / 1000.0);
    summary.put("mean_noisy_ratio", Math.round((sumNoisyRatio / selected.size()) * 10000.0) / 10000.0);
    summary.put("mean_nastiness_score", Math.round((sumScore / selected.size()) * 1000.0) / 1000.0);
    summary.put("sample_records_25", samples25);

    Files.writeString(outSummary, prettyGson.toJson(summary) + "\n", StandardCharsets.UTF_8);
    System.out.println("[DONE] Wrote " + selected.size() + " sentences to " + OUT_DIR);
  }

  private boolean tryAcceptCandidate(
      Candidate c,
      Set<String> seenExact,
      Map<String, Integer> open3Counts,
      Map<String, List<Candidate>> wordIndex) {
    if (seenExact.contains(c.asciiSkeletonSentence)) {
      return false;
    }
    if (open3Counts.getOrDefault(c.open3Key, 0) >= 2) {
      return false;
    }

    // Candidate set of neighbors via inverted word skeleton index (ignores very common short words for speed)
    Set<Candidate> neighbors = new HashSet<>();
    for (String w : c.wordSkeletonSet) {
      if (w.length() < 3) {
        continue;
      }
      List<Candidate> list = wordIndex.get(w);
      if (list != null) {
        neighbors.addAll(list);
      }
    }
    for (Candidate other : neighbors) {
      if (jaccard(c.wordSkeletonSet, other.wordSkeletonSet) >= 0.55) {
        return false;
      }
    }

    seenExact.add(c.asciiSkeletonSentence);
    open3Counts.merge(c.open3Key, 1, Integer::sum);
    for (String w : c.wordSkeletonSet) {
      if (w.length() >= 3) {
        wordIndex.computeIfAbsent(w, k -> new ArrayList<>()).add(c);
      }
    }
    return true;
  }

  public static void main(String[] args) throws Exception {
    new MineNastySentences().run();
  }
}
