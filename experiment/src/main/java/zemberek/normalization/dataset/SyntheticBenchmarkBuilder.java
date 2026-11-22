package zemberek.normalization.dataset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import zemberek.core.turkish.RootAttribute;
import zemberek.core.turkish.SecondaryPos;
import zemberek.core.turkish.Turkish;
import zemberek.core.turkish.TurkishAlphabet;
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.analysis.AnalysisCache;
import zemberek.morphology.analysis.SentenceAnalysis;
import zemberek.morphology.analysis.SingleAnalysis;
import zemberek.morphology.lexicon.DictionaryItem;
import zemberek.morphology.morphotactics.TurkishMorphotactics;
import zemberek.tokenization.Token;
import zemberek.tokenization.TurkishSentenceExtractor;
import zemberek.tokenization.TurkishTokenizer;

/**
 * Reproducible, leak-free builder for three 1,000-sentence controlled Turkish normalization
 * benchmarks saved alongside existing test sets under
 * {@code /home/dndara/data/turkish/normalization/benchmarks/synthetic_1k/}:
 *
 * <ol>
 *   <li><b>{@code synth_asciified_1k}</b>: 1,000 verified clean multi-domain Turkish sentences
 *       completely asciified ({@code ç->c, ğ->g, ı->i, İ->I, ö->o, ş->s, ü->u}), each containing
 *       at least 2 diacritic-bearing words and enriched with natural Turkish ASCII minimal-pair
 *       homographs ({@code on/ön}, {@code oldu/öldü}, {@code uc/üç}, {@code is/iş}, {@code koy/köy}).
 *   <li><b>{@code synth_typo_1k}</b>: 1,000 verified clean Turkish sentences with 1–3 realistic
 *       Damerau-Levenshtein distance-1 typos injected into regular common words ({@code len >= 4})
 *       using physical Turkish Q-keyboard neighbor substitutions (35%), fast-typing character
 *       deletions (25%), adjacent transpositions (20%), and key-bounce/neighbor insertions (20%).
 *   <li><b>{@code synth_clean_1k}</b>: 1,000 completely valid, orthographically and morphologically
 *       verified clean Turkish sentences ({@code raw == gold}) across news, dialogue, science, and
 *       parliamentary prose — including legitimate acronyms ({@code TBMM}, {@code TÜBİTAK},
 *       {@code TSK}, {@code KDV}), TDK apostrophes ({@code Türkiye'nin}, {@code 2017'de}),
 *       no-apostrophe proper derivatives ({@code Türklerin}, {@code Türkçede}), {@code -ki} words,
 *       and unambiguous locatives — measuring false-positive corruption ({@code CleanFPR}).
 * </ol>
 *
 * <p>All 3,000 gold sentences are mutually disjoint and strictly deduplicated against
 * {@code ForbiddenLeakageVault}, {@code noisy_tr_v2} (342K sentences), and {@code CleanWordLm}'s
 * 1.8M-sentence unlabeled clean corpus.
 */
public final class SyntheticBenchmarkBuilder {

  private static final Locale TR = Turkish.LOCALE;
  private static final int TARGET_PER_SET = 1_000;
  private static final int MIN_LEX_WORDS = 5;
  private static final int MAX_LEX_WORDS = 22;
  private static final int MIN_CHARS = 28;
  private static final int MAX_CHARS = 185;

  private static final Pattern DISALLOWED_CHARS_RE =
      Pattern.compile(
          "[\\u00AD\\uFFFD\"“”«»‘’‹›;()\\[\\]{}<>@#_*~`|\\\\/+=^&âîûÂÎÛqwxQWX\\-–—…]");
  private static final Pattern SPACE_BEFORE_PUNCT_RE = Pattern.compile("\\s+[,.:!?]");
  private static final Pattern MISSING_SPACE_AFTER_PUNCT_RE =
      Pattern.compile("[,:][^\\s]|\\.[A-Za-zÇĞİÖŞÜçğıöşü]");
  private static final Pattern MULTI_PUNCT_RE = Pattern.compile("[.!?]{2,}|,,");
  private static final Pattern PURE_ALPHA_RE = Pattern.compile("^[A-Za-zÇĞİÖŞÜçğıöşü]+$");
  private static final Pattern PURE_DIGITS_RE = Pattern.compile("^[0-9]{1,6}$");
  private static final Pattern TR_DIACRITIC_RE = Pattern.compile("[çğıöşüÇĞİÖŞÜ]");

  private static final Set<String> CONTESTED_OR_COLLOQUIAL_LOWER =
      Set.of(
          "herkez",
          "yalnış",
          "yanlız",
          "birşey",
          "birşeyi",
          "birşeyler",
          "herşey",
          "herşeyi",
          "herşeyin",
          "herşeye",
          "herşeyden",
          "hiçkimse",
          "pekçok",
          "yanısıra",
          "ardarda",
          "bişey",
          "bişi",
          "tabi",
          "baya",
          "fiat",
          "fiatı",
          "döküman",
          "orjinal",
          "antreman",
          "gardolap",
          "muhattap",
          "hakket",
          "valla",
          "walla",
          "inş",
          "slm",
          "mrb",
          "işbirliği",
          "işbirliğine",
          "işbirliğini",
          "işbirliğinin",
          "haftasonu",
          "önsöz",
          "altyapı",
          "öngörü",
          "bende",
          "sende",
          "onda",
          "oda",
          "omu");

  // Multi-word TDK institution heads where TDK omits apostrophe ("Bakanlığına") while news wires
  // frequently insert one ("Bakanlığı'na") — excluded so benchmarks contain zero style disputes.
  private static final String[] INSTITUTIONAL_HEAD_STEMS = {
    "bakanlığı",
    "müdürlüğü",
    "üniversitesi",
    "fakültesi",
    "başkanlığı",
    "meclisi",
    "mahkemesi",
    "kurumu",
    "kurulu",
    "enstitüsü",
    "odası",
    "birliği",
    "teşkilatı",
    "komisyonu"
  };

  private static final Set<String> DETACHED_COMPOUND_PAIRS =
      Set.of(
          "hiç bir",
          "bir çok",
          "bir kaç",
          "her hangi",
          "bir biri",
          "bir birine",
          "bir birini",
          "ön görü",
          "alt yapı",
          "göz yaşı",
          "göz yaşlarına",
          "iş birliği",
          "iş birliğine",
          "suç üstü",
          "olağan üstü",
          "uluslar arası");

  private static final Set<String> VALID_NUM_APOSTROPHE_SUFFIXES =
      Set.of(
          "da", "de", "ta", "te", "dan", "den", "tan", "ten", "a", "e", "ya", "ye", "ı", "i", "u",
          "ü", "yı", "yi", "yu", "yü", "ın", "in", "un", "ün", "nın", "nin", "nun", "nün", "la",
          "le", "yla", "yle", "lı", "li", "lu", "lü", "lık", "lik", "luk", "lük", "lar", "ler",
          "larda", "lerde", "lardan", "lerden", "ların", "lerin", "ları", "leri");

  // Physical Turkish Q-Keyboard adjacency (pure diacritic counterparts c/ç, g/ğ, ı/i, o/ö, s/ş, u/ü
  // intentionally omitted so synth_typo_1k tests real keyboard typos rather than asciification).
  private static final Map<Character, String> TR_Q_NEIGHBORS = buildTurkishQNeighbors();

  private enum SynthDomain {
    NEWS_ECONOMY("news_economy", 350),
    CONVERSATIONAL("conversational", 300),
    SCIENCE_KNOWLEDGE("science_knowledge", 200),
    PARLIAMENT_CIVIC("parliament_civic", 150);

    final String id;
    final int quotaPerSet;

    SynthDomain(String id, int quotaPerSet) {
      this.id = id;
      this.quotaPerSet = quotaPerSet;
    }
  }

  private record SourceSpec(
      SynthDomain domain, Path path, String sourceId, int skipLines, int maxLines, boolean splitParagraphs) {}

  private record VerifiedCleanSentence(
      SynthDomain domain,
      String sourceId,
      String text,
      List<Token> tokens,
      List<Integer> lexicalTokenIndices,
      List<Integer> typoEligibleTokenIndices,
      int wordCount,
      int diacriticWordCount,
      int asciiAmbigWordCount,
      int cleanTrapSignals,
      String skeleton,
      long[] shingle5Hashes) {}

  private record TypoEdit(
      int tokenIndex, String original, String corrupted, String editType) {}

  private record BenchmarkEntry(
      String id,
      String benchmark,
      String domain,
      String sourceId,
      String rawText,
      String goldText,
      String goldTokensLowerJoined,
      int wordCount,
      int changedWords,
      List<TypoEdit> edits) {}

  private SyntheticBenchmarkBuilder() {}

  public static void main(String[] args) throws Exception {
    long t0 = System.nanoTime();
    Path baseNormDir = Paths.get("/home/dndara/data/turkish/normalization");
    Path turkishRoot = Paths.get("/home/dndara/data/turkish");
    Path cleanDir = turkishRoot.resolve("clean");
    Path outDir = baseNormDir.resolve("benchmarks/synthetic_1k");
    for (int i = 0; i + 1 < args.length; i++) {
      if ("--out-dir".equals(args[i])) {
        outDir = Paths.get(args[++i]);
      }
    }
    Files.createDirectories(outDir);

    System.out.println("=== Building 3 Controlled 1,000-Sentence Synthetic Normalization Benchmarks ===");
    System.out.println("[OUT] " + outDir);

    // 1. Initialize Zemberek Morphology (strict + diacritic-ignoring for ambiguity measurement)
    TurkishMorphology strictMorph = TurkishMorphology.createWithDefaults();
    TurkishMorphology asciiMorph =
        TurkishMorphology.builder()
            .setLexicon(strictMorph.getLexicon())
            .ignoreDiacriticsInAnalysis()
            .setCache(AnalysisCache.builder().dynamicCacheSize(16_384, 65_536).build())
            .setAmbiguityResolver((s, a) -> new SentenceAnalysis(s, Collections.emptyList()))
            .build();

    // 2. Build exhaustive zero-leakage exclusion sets (Vault + noisy_tr_v2 + CleanWordLm 1.8M)
    ForbiddenLeakageVault vault = new ForbiddenLeakageVault();
    vault.loadAll(baseNormDir, turkishRoot, false);

    Set<String> excludedSkeletons = new HashSet<>(600_000);
    Set<Long> used5GramShingles = new HashSet<>(500_000);

    int noisyLoaded = loadNoisyTrV2Skeletons(baseNormDir.resolve("noisy_tr_v2"), excludedSkeletons);
    int cleanLmLoaded = loadCleanLmPrefixSkeletons(cleanDir, excludedSkeletons);
    System.out.printf(
        Locale.US,
        "[ANTI-LEAKAGE] Excluded %,d noisy_tr_v2 skeletons + %,d CleanWordLm skeletons (+ ForbiddenLeakageVault).%n",
        noisyLoaded,
        cleanLmLoaded);

    // 3. Stream disjoint clean sources across 4 domains and collect strictly verified gold pools
    List<SourceSpec> sources =
        List.of(
            new SourceSpec(
                SynthDomain.NEWS_ECONOMY,
                cleanDir.resolve("www.dunya.com.txt"),
                "dunya_com",
                0,
                90_000,
                false),
            new SourceSpec(
                SynthDomain.NEWS_ECONOMY,
                cleanDir.resolve("www.iha.com.tr.txt"),
                "iha_com_tr",
                0,
                90_000,
                false),
            new SourceSpec(
                SynthDomain.NEWS_ECONOMY,
                cleanDir.resolve("www.ensonhaber.com.txt"),
                "ensonhaber",
                0,
                90_000,
                false),
            new SourceSpec(
                SynthDomain.NEWS_ECONOMY,
                cleanDir.resolve("ntv-750k-sentences.txt"),
                "ntv_tail_post350k",
                350_000,
                120_000,
                false),
            new SourceSpec(
                SynthDomain.CONVERSATIONAL,
                cleanDir.resolve("open-subtitles-tr-2018.txt"),
                "opensubtitles_2018",
                50_000,
                260_000,
                false),
            new SourceSpec(
                SynthDomain.CONVERSATIONAL,
                cleanDir.resolve("subtitle-tr-10M-sentences.txt"),
                "subtitles_tr",
                50_000,
                160_000,
                false),
            new SourceSpec(
                SynthDomain.SCIENCE_KNOWLEDGE,
                cleanDir.resolve("www.bilim.org.txt"),
                "bilim_org",
                0,
                60_000,
                false),
            new SourceSpec(
                SynthDomain.SCIENCE_KNOWLEDGE,
                cleanDir.resolve("radikal.txt"),
                "radikal_feature",
                0,
                35_000,
                true),
            new SourceSpec(
                SynthDomain.SCIENCE_KNOWLEDGE,
                cleanDir.resolve("star-gazete.txt"),
                "star_feature",
                0,
                35_000,
                true),
            new SourceSpec(
                SynthDomain.PARLIAMENT_CIVIC,
                cleanDir.resolve("tbmm-tutanak.txt"),
                "tbmm_tutanak",
                0,
                180_000,
                false));

    Map<SynthDomain, List<VerifiedCleanSentence>> pools = new EnumMap<>(SynthDomain.class);
    for (SynthDomain d : SynthDomain.values()) {
      pools.put(d, new ArrayList<>(4_000));
    }

    Map<String, Boolean> commonMorphCache = new HashMap<>(131_072);
    Map<String, Integer> asciiAmbigCache = new HashMap<>(65_536);

    for (SourceSpec spec : sources) {
      if (!Files.exists(spec.path())) {
        continue;
      }
      int needForDomain = spec.domain().quotaPerSet * 8; // generous candidate pool for ranking/interleaving
      if (pools.get(spec.domain()).size() >= needForDomain * 2) {
        continue;
      }
      int acceptedFromSource = 0;
      int capPerSource = Math.max(800, spec.domain().quotaPerSet * 4);
      try (BufferedReader br = Files.newBufferedReader(spec.path(), StandardCharsets.UTF_8)) {
        String line;
        int lineIdx = 0;
        while ((line = br.readLine()) != null) {
          if (lineIdx++ < spec.skipLines()) {
            continue;
          }
          if (lineIdx - spec.skipLines() > spec.maxLines() || acceptedFromSource >= capPerSource) {
            break;
          }
          line = line.trim();
          if (line.length() < MIN_CHARS) {
            continue;
          }
          List<String> candidates =
              spec.splitParagraphs() && line.length() > MAX_CHARS
                  ? TurkishSentenceExtractor.DEFAULT.fromParagraph(line)
                  : List.of(line);
          for (String cand : candidates) {
            VerifiedCleanSentence vcs =
                verifyGoldCleanSentence(
                    cand.trim(),
                    spec.domain(),
                    spec.sourceId(),
                    strictMorph,
                    asciiMorph,
                    vault,
                    excludedSkeletons,
                    used5GramShingles,
                    commonMorphCache,
                    asciiAmbigCache);
            if (vcs != null) {
              excludedSkeletons.add(vcs.skeleton());
              for (long h : vcs.shingle5Hashes()) {
                used5GramShingles.add(h);
              }
              pools.get(spec.domain()).add(vcs);
              acceptedFromSource++;
              if (acceptedFromSource >= capPerSource) {
                break;
              }
            }
          }
        }
      }
      System.out.printf(
          Locale.US,
          "  [POOL %-18s] %-20s -> accepted %,d gold-verified sentences%n",
          spec.domain().id,
          spec.sourceId(),
          acceptedFromSource);
    }

    // 4. Deterministically interleave sources within each domain and allocate 3 disjoint 1,000-sentence sets
    List<BenchmarkEntry> asciiSet = new ArrayList<>(TARGET_PER_SET);
    List<BenchmarkEntry> typoSet = new ArrayList<>(TARGET_PER_SET);
    List<BenchmarkEntry> cleanSet = new ArrayList<>(TARGET_PER_SET);

    Random typoRng = new Random(20261115L);
    Map<String, Integer> editTypeCounts = new LinkedHashMap<>();

    for (SynthDomain dom : SynthDomain.values()) {
      List<VerifiedCleanSentence> interleaved = interleaveBySource(pools.get(dom));
      int want = dom.quotaPerSet;
      int gotAscii = 0;
      int gotTypo = 0;
      int gotClean = 0;
      int turn = 0;

      for (VerifiedCleanSentence vcs : interleaved) {
        if (gotAscii >= want && gotTypo >= want && gotClean >= want) {
          break;
        }
        boolean okAscii = vcs.diacriticWordCount() >= 2 && vcs.asciiAmbigWordCount() >= 1;
        boolean okTypo = vcs.typoEligibleTokenIndices().size() >= 3;

        // Assign round-robin to preserve identical domain & length balance across all 3 benchmarks
        boolean assigned = false;
        for (int attempt = 0; attempt < 3 && !assigned; attempt++) {
          int slot = (turn + attempt) % 3;
          if (slot == 0 && gotAscii < want && okAscii) {
            BenchmarkEntry be = buildAsciifiedEntry(asciiSet.size() + 1, vcs);
            if (be != null) {
              asciiSet.add(be);
              gotAscii++;
              assigned = true;
            }
          } else if (slot == 1 && gotTypo < want && okTypo) {
            BenchmarkEntry be =
                buildTypoEntry(typoSet.size() + 1, vcs, typoRng, strictMorph, commonMorphCache, editTypeCounts);
            if (be != null) {
              typoSet.add(be);
              gotTypo++;
              assigned = true;
            }
          } else if (slot == 2 && gotClean < want) {
            cleanSet.add(buildCleanEntry(cleanSet.size() + 1, vcs));
            gotClean++;
            assigned = true;
          }
        }
        if (assigned) {
          turn = (turn + 1) % 3;
        }
      }

      // Fallback pass if strict asciiAmbigWordCount >= 1 left a few slots open
      if (gotAscii < want) {
        Set<String> alreadyPicked = new HashSet<>();
        asciiSet.forEach(e -> alreadyPicked.add(e.goldText()));
        typoSet.forEach(e -> alreadyPicked.add(e.goldText()));
        cleanSet.forEach(e -> alreadyPicked.add(e.goldText()));
        for (VerifiedCleanSentence vcs : interleaved) {
          if (gotAscii >= want) {
            break;
          }
          if (!alreadyPicked.contains(vcs.text()) && vcs.diacriticWordCount() >= 2) {
            BenchmarkEntry be = buildAsciifiedEntry(asciiSet.size() + 1, vcs);
            if (be != null) {
              asciiSet.add(be);
              alreadyPicked.add(vcs.text());
              gotAscii++;
            }
          }
        }
      }

      if (gotAscii != want || gotTypo != want || gotClean != want) {
        throw new IllegalStateException(
            String.format(
                "Underfilled domain %s: want=%d, ascii=%d, typo=%d, clean=%d",
                dom.id, want, gotAscii, gotTypo, gotClean));
      }
    }

    //Deterministic shuffle of each 1,000-sentence set so domains are evenly mixed throughout the file, then re-index IDs
    Collections.shuffle(asciiSet, new Random(1001L));
    Collections.shuffle(typoSet, new Random(1002L));
    Collections.shuffle(cleanSet, new Random(1003L));
    asciiSet = renumberIds(asciiSet, "synth_ascii");
    typoSet = renumberIds(typoSet, "synth_typo");
    cleanSet = renumberIds(cleanSet, "synth_clean");

    // 5. Write TSV + JSONL + summary.json + README.md
    Path asciiTsv = outDir.resolve("synth_asciified_1k.tsv");
    Path asciiJsonl = outDir.resolve("synth_asciified_1k.jsonl");
    Path typoTsv = outDir.resolve("synth_typo_1k.tsv");
    Path typoJsonl = outDir.resolve("synth_typo_1k.jsonl");
    Path cleanTsv = outDir.resolve("synth_clean_1k.tsv");
    Path cleanJsonl = outDir.resolve("synth_clean_1k.jsonl");

    writeTsv(asciiTsv, asciiSet);
    writeJsonl(asciiJsonl, asciiSet);
    writeTsv(typoTsv, typoSet);
    writeJsonl(typoJsonl, typoSet);
    writeTsv(cleanTsv, cleanSet);
    writeJsonl(cleanJsonl, cleanSet);

    writeSummaryAndReadme(outDir, asciiSet, typoSet, cleanSet, editTypeCounts, asciiTsv, typoTsv, cleanTsv);

    System.out.printf(
        Locale.US,
        "=== Built 3x1,000 Synthetic Benchmarks in %.2fs ===%n"
            + "  1. %s (%,d sents, %,d asciified words)%n"
            + "  2. %s (%,d sents, %,d distance-1 typos: %s)%n"
            + "  3. %s (%,d sents, 0 errors, gold clean)%n",
        (System.nanoTime() - t0) / 1e9,
        asciiTsv,
        asciiSet.size(),
        asciiSet.stream().mapToInt(BenchmarkEntry::changedWords).sum(),
        typoTsv,
        typoSet.size(),
        typoSet.stream().mapToInt(BenchmarkEntry::changedWords).sum(),
        editTypeCounts,
        cleanTsv,
        cleanSet.size());
  }

  private static VerifiedCleanSentence verifyGoldCleanSentence(
      String text,
      SynthDomain domain,
      String sourceId,
      TurkishMorphology strictMorph,
      TurkishMorphology asciiMorph,
      ForbiddenLeakageVault vault,
      Set<String> excludedSkeletons,
      Set<Long> used5GramShingles,
      Map<String, Boolean> commonMorphCache,
      Map<String, Integer> asciiAmbigCache) {
    if (text.length() < MIN_CHARS || text.length() > MAX_CHARS) {
      return null;
    }
    char firstCh = text.charAt(0);
    if (!Character.isLetter(firstCh) || !Character.isUpperCase(firstCh)) {
      return null;
    }
    char lastCh = text.charAt(text.length() - 1);
    if (lastCh != '.' && lastCh != '?' && lastCh != '!') {
      return null;
    }
    if (DISALLOWED_CHARS_RE.matcher(text).find()
        || SPACE_BEFORE_PUNCT_RE.matcher(text).find()
        || MISSING_SPACE_AFTER_PUNCT_RE.matcher(text).find()
        || MULTI_PUNCT_RE.matcher(text).find()) {
      return null;
    }

    String skel = ForbiddenLeakageVault.normalizeSkeleton(text);
    if (skel.length() < 20 || excludedSkeletons.contains(skel) || vault.isForbidden(text, skel)) {
      return null;
    }

    String spacedWords = ForbiddenLeakageVault.normalizeSpacedWords(text);
    String[] skelWords = spacedWords.isEmpty() ? new String[0] : spacedWords.split(" ");
    long[] shingles = compute5GramShingles(skelWords);
    int shingleCollisions = 0;
    for (long h : shingles) {
      if (used5GramShingles.contains(h) && ++shingleCollisions >= 2) {
        return null;
      }
    }

    List<Token> tokens = TurkishTokenizer.DEFAULT.tokenize(text);
    if (tokens.size() < 6 || tokens.size() > 30) {
      return null;
    }

    List<Integer> lexIndices = new ArrayList<>(tokens.size());
    List<Integer> typoEligible = new ArrayList<>(tokens.size());
    int diacriticWords = 0;
    int asciiAmbigWords = 0;
    int trapSignals = 0;
    int lexPos = 0;

    for (int tIdx = 0; tIdx < tokens.size(); tIdx++) {
      Token tok = tokens.get(tIdx);
      Token.Type type = tok.getType();
      String surf = tok.getText();

      if (type == Token.Type.Punctuation) {
        if (!surf.equals(",")
            && !surf.equals(".")
            && !surf.equals("?")
            && !surf.equals("!")
            && !surf.equals(":")) {
          return null;
        }
        continue;
      }
      if (type == Token.Type.PercentNumeral) {
        if (!surf.matches("^%[0-9]{1,3}$")) {
          return null;
        }
        trapSignals++;
        continue;
      }
      if (type == Token.Type.Number) {
        if (PURE_DIGITS_RE.matcher(surf).matches()) {
          trapSignals++;
          continue;
        }
        int ap = surf.indexOf('\'');
        if (ap > 0 && ap < surf.length() - 1) {
          String digits = surf.substring(0, ap);
          String suf = surf.substring(ap + 1);
          if (PURE_DIGITS_RE.matcher(digits).matches() && VALID_NUM_APOSTROPHE_SUFFIXES.contains(suf)) {
            trapSignals++;
            continue;
          }
        }
        return null;
      }
      if (type != Token.Type.Word
          && type != Token.Type.WordAlphanumerical
          && type != Token.Type.WordWithSymbol
          && type != Token.Type.Abbreviation) {
        return null;
      }

      String low = surf.toLowerCase(TR);
      if (CONTESTED_OR_COLLOQUIAL_LOWER.contains(low) || containsInstitutionalHead(low)) {
        return null;
      }

      boolean isSentenceStart = (lexPos == 0);
      int apIdx = surf.indexOf('\'');
      if (apIdx >= 0) {
        // Must have a single apostrophe and match strict TDK ProperNoun / Abbreviation formatting bit-for-bit
        if (apIdx == 0 || apIdx == surf.length() - 1 || surf.indexOf('\'', apIdx + 1) >= 0) {
          return null;
        }
        String numPrefix = surf.substring(0, apIdx);
        String tail = surf.substring(apIdx + 1);
        if (PURE_DIGITS_RE.matcher(numPrefix).matches()) {
          if (!VALID_NUM_APOSTROPHE_SUFFIXES.contains(tail)) {
            return null;
          }
          trapSignals++;
          continue;
        }
        if (!matchesExactTdkApostropheForm(surf, low.replace("'", ""), strictMorph)) {
          return null;
        }
        trapSignals++;
        lexIndices.add(tIdx);
        if (TR_DIACRITIC_RE.matcher(surf).find()) {
          diacriticWords++;
        }
        lexPos++;
        continue;
      }

      if (!PURE_ALPHA_RE.matcher(surf).matches()) {
        return null;
      }

      boolean allUpper = surf.length() >= 2 && surf.equals(surf.toUpperCase(TR));
      boolean allLower = surf.equals(low);
      boolean titleCase =
          surf.length() >= 2
              && Character.isUpperCase(surf.charAt(0))
              && surf.substring(1).equals(low.substring(1));
      boolean singleUpper = surf.length() == 1 && Character.isUpperCase(surf.charAt(0));

      if (allUpper) {
        if (!isKnownStrictAbbreviation(low, strictMorph)) {
          return null; // Reject newspaper drop-caps like "BÜYÜK ihaleleri..."
        }
        trapSignals++;
      } else if (allLower) {
        if (isSentenceStart) {
          return null;
        }
        if (!hasRegularCommonParseCached(low, strictMorph, commonMorphCache)) {
          return null;
        }
      } else if (titleCase || singleUpper) {
        if (isSentenceStart) {
          boolean regularCommon = hasRegularCommonParseCached(low, strictMorph, commonMorphCache);
          boolean validProper = matchesExactUnapostrophizedProperTDK(surf, low, strictMorph);
          if (!regularCommon && !validProper) {
            return null; // Blocks un-apostrophized "Türkiyede" at index 0!
          }
        } else {
          if (!matchesExactUnapostrophizedProperTDK(surf, low, strictMorph)) {
            return null; // Mid-sentence TitleCase must be a verified TDK ProperNoun
          }
          trapSignals++;
        }
      } else {
        return null; // Reject mixed-case anomalies
      }

      if (low.endsWith("de") || low.endsWith("da") || low.endsWith("ki")) {
        trapSignals++;
      }
      if (TR_DIACRITIC_RE.matcher(surf).find()) {
        diacriticWords++;
      }
      if (!allUpper && countDistinctAsciiSurfaceForms(low, asciiMorph, asciiAmbigCache) >= 2) {
        asciiAmbigWords++;
      }

      lexIndices.add(tIdx);
      if (!allUpper
          && (allLower || isSentenceStart)
          && surf.length() >= 4
          && surf.length() <= 16
          && hasRegularCommonParseCached(low, strictMorph, commonMorphCache)) {
        typoEligible.add(tIdx);
      }
      lexPos++;
    }

    if (lexPos < MIN_LEX_WORDS || lexPos > MAX_LEX_WORDS) {
      return null;
    }

    // Multi-word sequence gate: block detached -ebil, detached closed compounds, and ambiguous clitic bigrams
    for (int i = 0; i + 1 < tokens.size(); i++) {
      String l1 = tokens.get(i).getText().toLowerCase(TR);
      String l2 = tokens.get(i + 1).getText().toLowerCase(TR);
      if (DETACHED_COMPOUND_PAIRS.contains(l1 + " " + l2)) {
        return null;
      }
      if (l1.length() >= 3
          && (l1.endsWith("a") || l1.endsWith("e"))
          && (l2.startsWith("bil") && l2.length() >= 5)) {
        return null; // e.g. "alına bilinecek", "yapıla bilir"
      }
      if ((l2.equals("de") || l2.equals("da") || l2.equals("te") || l2.equals("ta"))
          && (PURE_DIGITS_RE.matcher(l1).matches()
              || (i > 0 && Character.isUpperCase(tokens.get(i).getText().charAt(0))))) {
        return null; // Avoid unedited detached locatives after numerals/proper nouns
      }
      if (l2.equals("ki")
          && (l1.equals("de")
              || l1.equals("da")
              || l1.endsWith("da")
              || l1.endsWith("de")
              || l1.endsWith("ta")
              || l1.endsWith("te")
              || Set.of(
                      "akşam", "sabah", "yarın", "bugün", "dün", "gece", "önce", "sonra", "öte",
                      "beri", "karşı", "yukarı", "aşağı", "benim", "senin", "onun", "bizim",
                      "sizin", "onların", "san", "bel", "halbu", "madem", "oysa", "meğer", "illa")
                  .contains(l1))) {
        return null; // Reject unedited detached relativizer/lexical -ki ("akşam ki maçı", "evde ki")
      }
    }

    return new VerifiedCleanSentence(
        domain,
        sourceId,
        text,
        tokens,
        lexIndices,
        typoEligible,
        lexPos,
        diacriticWords,
        asciiAmbigWords,
        trapSignals,
        skel,
        shingles);
  }

  private static boolean containsInstitutionalHead(String low) {
    String clean = low.replace("'", "");
    for (String stem : INSTITUTIONAL_HEAD_STEMS) {
      if (clean.startsWith(stem) && clean.length() > stem.length()) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasRegularCommonParseCached(
      String low, TurkishMorphology strictMorph, Map<String, Boolean> cache) {
    Boolean cached = cache.get(low);
    if (cached != null) {
      return cached;
    }
    boolean ok = false;
    for (SingleAnalysis a : strictMorph.analyze(low)) {
      if (a.isUnknown() || a.isRuntime() || a.containsInformalMorpheme()) {
        continue;
      }
      SecondaryPos sp = a.getDictionaryItem().secondaryPos;
      if (sp != SecondaryPos.ProperNoun && sp != SecondaryPos.Abbreviation) {
        ok = true;
        break;
      }
    }
    if (cache.size() < 250_000) {
      cache.put(low, ok);
    }
    return ok;
  }

  private static boolean isKnownStrictAbbreviation(String low, TurkishMorphology strictMorph) {
    for (SingleAnalysis a : strictMorph.analyze(low)) {
      if (!a.isUnknown()
          && !a.isRuntime()
          && a.getEnding().isEmpty()
          && a.getDictionaryItem().secondaryPos == SecondaryPos.Abbreviation) {
        return true;
      }
    }
    return false;
  }

  private static boolean matchesExactUnapostrophizedProperTDK(
      String surface, String low, TurkishMorphology strictMorph) {
    for (SingleAnalysis a : strictMorph.analyze(low)) {
      if (a.isUnknown() || a.isRuntime() || a.containsInformalMorpheme()) {
        continue;
      }
      if (a.getDictionaryItem().secondaryPos == SecondaryPos.ProperNoun
          && !properNounTakesApostropheTDK(a)) {
        String fmt = formatProperOrAbbrTDK(a);
        if (surface.equals(fmt)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean matchesExactTdkApostropheForm(
      String surfaceWithApostrophe, String joinedLow, TurkishMorphology strictMorph) {
    for (SingleAnalysis a : strictMorph.analyze(joinedLow)) {
      if (a.isUnknown() || a.isRuntime() || a.getEnding().isEmpty()) {
        continue;
      }
      SecondaryPos sp = a.getDictionaryItem().secondaryPos;
      if (sp == SecondaryPos.Abbreviation
          || (sp == SecondaryPos.ProperNoun && properNounTakesApostropheTDK(a))) {
        String fmt = formatProperOrAbbrTDK(a);
        if (surfaceWithApostrophe.equals(fmt)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean properNounTakesApostropheTDK(SingleAnalysis a) {
    if (a.getEnding().isEmpty()) {
      return false;
    }
    if (a.getDictionaryItem().attributes.contains(RootAttribute.NoQuote)) {
      return false;
    }
    List<SingleAnalysis.MorphemeData> mdList = a.getMorphemeDataList();
    for (int i = 1; i < mdList.size(); i++) {
      SingleAnalysis.MorphemeData md = mdList.get(i);
      if (md.morpheme == TurkishMorphotactics.a3pl && !md.surface.isEmpty()) {
        return false;
      }
      if (md.morpheme.derivational && !md.surface.isEmpty()) {
        return false;
      }
      if (!md.surface.isEmpty()) {
        return true;
      }
    }
    return false;
  }

  private static String formatProperOrAbbrTDK(SingleAnalysis a) {
    DictionaryItem item = a.getDictionaryItem();
    Locale loc = item.hasAttribute(RootAttribute.LocaleEn) ? Locale.ENGLISH : TR;
    String ending = a.getEnding();
    if (item.secondaryPos == SecondaryPos.Abbreviation) {
      String upperLemma = item.normalizedLemma().toUpperCase(loc);
      return ending.isEmpty() ? upperLemma : upperLemma + "'" + ending;
    }
    if (item.secondaryPos == SecondaryPos.ProperNoun) {
      String titleLemma = capitalizeTr(item.normalizedLemma());
      if (ending.isEmpty()) {
        return titleLemma;
      }
      if (properNounTakesApostropheTDK(a)) {
        return titleLemma + "'" + ending;
      }
      String stemSurf =
          item.attributes.contains(RootAttribute.NoQuote) ? item.normalizedLemma() : a.getStem();
      return capitalizeTr(stemSurf + ending);
    }
    return null;
  }

  private static int countDistinctAsciiSurfaceForms(
      String low, TurkishMorphology asciiMorph, Map<String, Integer> cache) {
    String asc = TurkishAlphabet.INSTANCE.toAscii(low);
    Integer cached = cache.get(asc);
    if (cached != null) {
      return cached;
    }
    Set<String> distinct = new HashSet<>(4);
    for (SingleAnalysis a : asciiMorph.analyze(asc)) {
      if (a.isUnknown() || a.isRuntime()) {
        continue;
      }
      SecondaryPos sp = a.getDictionaryItem().secondaryPos;
      if (sp == SecondaryPos.ProperNoun || sp == SecondaryPos.Abbreviation) {
        continue;
      }
      for (var r : asciiMorph.getWordGenerator().generate(a.getDictionaryItem(), a.getMorphemes())) {
        if (TurkishAlphabet.INSTANCE.equalsIgnoreDiacritics(asc, r.surface)) {
          distinct.add(r.surface.toLowerCase(TR));
          if (distinct.size() >= 2) {
            break;
          }
        }
      }
      if (distinct.size() >= 2) {
        break;
      }
    }
    int count = distinct.size();
    if (cache.size() < 120_000) {
      cache.put(asc, count);
    }
    return count;
  }

  private static BenchmarkEntry buildAsciifiedEntry(int seq, VerifiedCleanSentence vcs) {
    String raw = asciifyPreserveCase(vcs.text());
    if (raw.equals(vcs.text())) {
      return null;
    }
    int changedWords = 0;
    for (int tIdx : vcs.lexicalTokenIndices()) {
      String w = vcs.tokens().get(tIdx).getText();
      if (!asciifyPreserveCase(w).equals(w)) {
        changedWords++;
      }
    }
    if (changedWords < 2) {
      return null;
    }
    return new BenchmarkEntry(
        String.format(Locale.ROOT, "synth_ascii_%04d", seq),
        "synth_asciified_1k",
        vcs.domain().id,
        vcs.sourceId(),
        raw,
        vcs.text(),
        joinLowerTokens(vcs.tokens()),
        vcs.wordCount(),
        changedWords,
        List.of());
  }

  private static BenchmarkEntry buildCleanEntry(int seq, VerifiedCleanSentence vcs) {
    return new BenchmarkEntry(
        String.format(Locale.ROOT, "synth_clean_%04d", seq),
        "synth_clean_1k",
        vcs.domain().id,
        vcs.sourceId(),
        vcs.text(),
        vcs.text(),
        joinLowerTokens(vcs.tokens()),
        vcs.wordCount(),
        0,
        List.of());
  }

  private static BenchmarkEntry buildTypoEntry(
      int seq,
      VerifiedCleanSentence vcs,
      Random rng,
      TurkishMorphology strictMorph,
      Map<String, Boolean> commonMorphCache,
      Map<String, Integer> editTypeCounts) {
    List<Integer> eligible = new ArrayList<>(vcs.typoEligibleTokenIndices());
    if (eligible.size() < 2) {
      return null;
    }
    Collections.shuffle(eligible, rng);

    double u = rng.nextDouble();
    int wantEdits =
        (u < 0.45 || eligible.size() < 4)
            ? 1
            : (u < 0.85 || eligible.size() < 6 ? 2 : 3);

    Map<Integer, TypoEdit> chosenByTokIdx = new HashMap<>(4);
    for (int tokIdx : eligible) {
      if (chosenByTokIdx.size() >= wantEdits) {
        break;
      }
      // Prefer non-adjacent corrupted tokens so surrounding context stays realistic
      if (chosenByTokIdx.containsKey(tokIdx - 1) || chosenByTokIdx.containsKey(tokIdx + 1)) {
        continue;
      }
      String orig = vcs.tokens().get(tokIdx).getText();
      TypoEdit edit = generateSingleDistance1Typo(tokIdx, orig, rng, strictMorph, commonMorphCache);
      if (edit != null) {
        chosenByTokIdx.put(tokIdx, edit);
      }
    }
    if (chosenByTokIdx.isEmpty()) {
      return null;
    }

    List<TypoEdit> orderedEdits =
        chosenByTokIdx.values().stream()
            .sorted(Comparator.comparingInt(TypoEdit::tokenIndex))
            .toList();
    for (TypoEdit te : orderedEdits) {
      editTypeCounts.merge(te.editType(), 1, Integer::sum);
    }

    // Reconstruct raw text preserving exact original inter-token whitespace
    String gold = vcs.text();
    StringBuilder rawSb = new StringBuilder(gold.length() + 4);
    int cursor = 0;
    for (TypoEdit te : orderedEdits) {
      Token tok = vcs.tokens().get(te.tokenIndex());
      int start = tok.getStart();
      int end = tok.getEnd() + 1;
      rawSb.append(gold, cursor, start);
      rawSb.append(te.corrupted());
      cursor = end;
    }
    rawSb.append(gold, cursor, gold.length());

    return new BenchmarkEntry(
        String.format(Locale.ROOT, "synth_typo_%04d", seq),
        "synth_typo_1k",
        vcs.domain().id,
        vcs.sourceId(),
        rawSb.toString(),
        gold,
        joinLowerTokens(vcs.tokens()),
        vcs.wordCount(),
        orderedEdits.size(),
        orderedEdits);
  }

  private static TypoEdit generateSingleDistance1Typo(
      int tokenIndex,
      String orig,
      Random rng,
      TurkishMorphology strictMorph,
      Map<String, Boolean> commonMorphCache) {
    boolean titleStart = Character.isUpperCase(orig.charAt(0));
    String low = orig.toLowerCase(TR);
    int len = low.length();

    String[] ops = {"sub_kbd", "del", "swap", "ins"};
    double r = rng.nextDouble();
    int primaryOp = r < 0.35 ? 0 : (r < 0.60 ? 1 : (r < 0.80 ? 2 : 3));

    for (int off = 0; off < ops.length; off++) {
      String op = ops[(primaryOp + off) % ops.length];
      for (int attempt = 0; attempt < 12; attempt++) {
        String candLow = null;
        switch (op) {
          case "sub_kbd" -> {
            int pos = titleStart ? (1 + rng.nextInt(len - 1)) : rng.nextInt(len);
            String nbrs = TR_Q_NEIGHBORS.get(low.charAt(pos));
            if (nbrs != null && !nbrs.isEmpty()) {
              char repl = nbrs.charAt(rng.nextInt(nbrs.length()));
              candLow = low.substring(0, pos) + repl + low.substring(pos + 1);
            }
          }
          case "del" -> {
            if (len >= 5) {
              int pos = 1 + rng.nextInt(len - 1);
              candLow = low.substring(0, pos) + low.substring(pos + 1);
            }
          }
          case "swap" -> {
            if (len >= 4) {
              int startMin = titleStart ? 1 : 0;
              if (len - 1 > startMin) {
                int pos = startMin + rng.nextInt((len - 1) - startMin);
                if (low.charAt(pos) != low.charAt(pos + 1)) {
                  candLow =
                      low.substring(0, pos)
                          + low.charAt(pos + 1)
                          + low.charAt(pos)
                          + low.substring(pos + 2);
                }
              }
            }
          }
          case "ins" -> {
            int pos = 1 + rng.nextInt(len - 1);
            char base = low.charAt(pos);
            char insCh = base;
            if (rng.nextBoolean()) {
              String nbrs = TR_Q_NEIGHBORS.get(base);
              if (nbrs != null && !nbrs.isEmpty()) {
                insCh = nbrs.charAt(rng.nextInt(nbrs.length()));
              }
            }
            candLow = low.substring(0, pos + 1) + insCh + low.substring(pos + 1);
          }
          default -> {}
        }

        if (candLow == null || candLow.equals(low)) {
          continue;
        }
        if (TurkishAlphabet.INSTANCE.equalsIgnoreDiacritics(low, candLow)) {
          continue;
        }
        if (damerauLevenshtein1(low, candLow) != 1) {
          continue;
        }
        // Must be a genuine non-word spelling error (unambiguous gold target)
        if (hasAnyStrictParse(candLow, strictMorph, commonMorphCache)) {
          continue;
        }
        String corruptedSurf = titleStart ? capitalizeTr(candLow) : candLow;
        return new TypoEdit(tokenIndex, orig, corruptedSurf, op);
      }
    }
    return null;
  }

  private static boolean hasAnyStrictParse(
      String low, TurkishMorphology strictMorph, Map<String, Boolean> commonMorphCache) {
    if (hasRegularCommonParseCached(low, strictMorph, commonMorphCache)) {
      return true;
    }
    for (SingleAnalysis a : strictMorph.analyze(low)) {
      if (!a.isUnknown() && !a.isRuntime()) {
        return true;
      }
    }
    return false;
  }

  private static int damerauLevenshtein1(String a, String b) {
    int n = a.length();
    int m = b.length();
    if (Math.abs(n - m) > 1) {
      return 2;
    }
    int[][] dp = new int[n + 1][m + 1];
    for (int i = 0; i <= n; i++) {
      dp[i][0] = i;
    }
    for (int j = 0; j <= m; j++) {
      dp[0][j] = j;
    }
    for (int i = 1; i <= n; i++) {
      for (int j = 1; j <= m; j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        int best = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
        if (i > 1
            && j > 1
            && a.charAt(i - 1) == b.charAt(j - 2)
            && a.charAt(i - 2) == b.charAt(j - 1)) {
          best = Math.min(best, dp[i - 2][j - 2] + 1);
        }
        dp[i][j] = best;
      }
    }
    return dp[n][m];
  }

  private static String asciifyPreserveCase(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      sb.append(
          switch (ch) {
            case 'ç' -> 'c';
            case 'ğ' -> 'g';
            case 'ı' -> 'i';
            case 'ö' -> 'o';
            case 'ş' -> 's';
            case 'ü' -> 'u';
            case 'Ç' -> 'C';
            case 'Ğ' -> 'G';
            case 'İ' -> 'I';
            case 'Ö' -> 'O';
            case 'Ş' -> 'S';
            case 'Ü' -> 'U';
            default -> ch;
          });
    }
    return sb.toString();
  }

  private static String capitalizeTr(String s) {
    if (s == null || s.isEmpty()) {
      return s;
    }
    return s.substring(0, 1).toUpperCase(TR) + s.substring(1);
  }

  private static String joinLowerTokens(List<Token> tokens) {
    StringBuilder sb = new StringBuilder();
    for (Token t : tokens) {
      if (sb.length() > 0) {
        sb.append(' ');
      }
      sb.append(t.getText().toLowerCase(TR));
    }
    return sb.toString();
  }

  private static List<VerifiedCleanSentence> interleaveBySource(List<VerifiedCleanSentence> pool) {
    Map<String, List<VerifiedCleanSentence>> bySource = new LinkedHashMap<>();
    for (VerifiedCleanSentence vcs : pool) {
      bySource.computeIfAbsent(vcs.sourceId(), k -> new ArrayList<>()).add(vcs);
    }
    Random rng = new Random(777L);
    for (List<VerifiedCleanSentence> list : bySource.values()) {
      // Shuffle deterministically, then lightly prefer richer trap/ambiguity sentences
      Collections.shuffle(list, rng);
      list.sort(
          Comparator.comparingInt(
                  (VerifiedCleanSentence v) ->
                      Math.min(3, v.asciiAmbigWordCount()) + Math.min(2, v.cleanTrapSignals()))
              .reversed());
    }
    List<VerifiedCleanSentence> out = new ArrayList<>(pool.size());
    List<List<VerifiedCleanSentence>> queues = new ArrayList<>(bySource.values());
    int idx = 0;
    boolean any = true;
    while (any) {
      any = false;
      for (List<VerifiedCleanSentence> q : queues) {
        if (idx < q.size()) {
          out.add(q.get(idx));
          any = true;
        }
      }
      idx++;
    }
    return out;
  }

  private static List<BenchmarkEntry> renumberIds(List<BenchmarkEntry> items, String prefix) {
    List<BenchmarkEntry> out = new ArrayList<>(items.size());
    for (int i = 0; i < items.size(); i++) {
      BenchmarkEntry e = items.get(i);
      out.add(
          new BenchmarkEntry(
              String.format(Locale.ROOT, "%s_%04d", prefix, i + 1),
              e.benchmark(),
              e.domain(),
              e.sourceId(),
              e.rawText(),
              e.goldText(),
              e.goldTokensLowerJoined(),
              e.wordCount(),
              e.changedWords(),
              e.edits()));
    }
    return out;
  }

  private static int loadNoisyTrV2Skeletons(Path noisyRoot, Set<String> out) throws IOException {
    if (!Files.isDirectory(noisyRoot)) {
      return 0;
    }
    int before = out.size();
    try (Stream<Path> walk = Files.walk(noisyRoot)) {
      List<Path> jsonls =
          walk.filter(Files::isRegularFile)
              .filter(
                  p -> {
                    String fn = p.getFileName().toString();
                    return fn.equals("train.jsonl")
                        || fn.equals("dev.jsonl")
                        || fn.equals("test.jsonl");
                  })
              .toList();
      for (Path p : jsonls) {
        try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
          String line;
          while ((line = br.readLine()) != null) {
            if (line.isBlank()) {
              continue;
            }
            try {
              JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
              if (obj.has("text") && !obj.get("text").isJsonNull()) {
                out.add(ForbiddenLeakageVault.normalizeSkeleton(obj.get("text").getAsString()));
              }
            } catch (Exception ignored) {
              // ignore
            }
          }
        }
      }
    }
    out.remove("");
    return out.size() - before;
  }

  private static int loadCleanLmPrefixSkeletons(Path cleanDir, Set<String> out) throws IOException {
    List<String> cleanLmFiles =
        List.of(
            "ntv-750k-sentences.txt",
            "www.cnnturk.com.txt",
            "www.haberturk.com.txt",
            "vikipedi.txt",
            "onedio.com.txt",
            "open-subtitles.txt");
    int before = out.size();
    for (String fn : cleanLmFiles) {
      Path p = cleanDir.resolve(fn);
      if (!Files.exists(p)) {
        continue;
      }
      int used = 0;
      try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
        String line;
        while ((line = br.readLine()) != null && used < 310_000) {
          line = line.trim();
          if (line.length() < 12 || line.length() > 420) {
            continue;
          }
          used++;
          out.add(ForbiddenLeakageVault.normalizeSkeleton(line));
        }
      }
    }
    out.remove("");
    return out.size() - before;
  }

  private static long[] compute5GramShingles(String[] words) {
    if (words.length < 5) {
      return new long[0];
    }
    long[] out = new long[words.length - 4];
    for (int i = 0; i + 5 <= words.length; i++) {
      long h = 0xcbf29ce484222325L;
      for (int k = 0; k < 5; k++) {
        String w = words[i + k];
        for (int c = 0; c < w.length(); c++) {
          h ^= w.charAt(c);
          h *= 0x100000001b3L;
        }
        h ^= 0x20L;
        h *= 0x100000001b3L;
      }
      out[i] = h;
    }
    return out;
  }

  private static void writeTsv(Path path, List<BenchmarkEntry> items) throws IOException {
    try (BufferedWriter bw = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      bw.write("id\traw_text\tnormalized_text\tnormalized_tokens_joined_tr_lower\n");
      for (BenchmarkEntry e : items) {
        bw.write(e.id());
        bw.write('\t');
        bw.write(e.rawText().replace('\t', ' ').replace('\n', ' '));
        bw.write('\t');
        bw.write(e.goldText().replace('\t', ' ').replace('\n', ' '));
        bw.write('\t');
        bw.write(e.goldTokensLowerJoined().replace('\t', ' ').replace('\n', ' '));
        bw.write('\n');
      }
    }
  }

  private static void writeJsonl(Path path, List<BenchmarkEntry> items) throws IOException {
    Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    try (BufferedWriter bw = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      for (BenchmarkEntry e : items) {
        bw.write(gson.toJson(e));
        bw.write('\n');
      }
    }
  }

  private static void writeSummaryAndReadme(
      Path outDir,
      List<BenchmarkEntry> asciiSet,
      List<BenchmarkEntry> typoSet,
      List<BenchmarkEntry> cleanSet,
      Map<String, Integer> editTypeCounts,
      Path asciiTsv,
      Path typoTsv,
      Path cleanTsv)
      throws Exception {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("generated_at_utc", Instant.now().toString());
    summary.put("sentences_per_set", TARGET_PER_SET);
    summary.put(
        "domain_quotas",
        Map.of(
            "news_economy", 350,
            "conversational", 300,
            "science_knowledge", 200,
            "parliament_civic", 150));
    summary.put(
        "synth_asciified_1k",
        Map.of(
            "tsv", asciiTsv.getFileName().toString(),
            "sha256", sha256(asciiTsv),
            "sentences", asciiSet.size(),
            "total_words", asciiSet.stream().mapToInt(BenchmarkEntry::wordCount).sum(),
            "asciified_words", asciiSet.stream().mapToInt(BenchmarkEntry::changedWords).sum()));
    summary.put(
        "synth_typo_1k",
        Map.of(
            "tsv", typoTsv.getFileName().toString(),
            "sha256", sha256(typoTsv),
            "sentences", typoSet.size(),
            "total_words", typoSet.stream().mapToInt(BenchmarkEntry::wordCount).sum(),
            "corrupted_words", typoSet.stream().mapToInt(BenchmarkEntry::changedWords).sum(),
            "edit_type_breakdown", editTypeCounts));
    summary.put(
        "synth_clean_1k",
        Map.of(
            "tsv", cleanTsv.getFileName().toString(),
            "sha256", sha256(cleanTsv),
            "sentences", cleanSet.size(),
            "total_words", cleanSet.stream().mapToInt(BenchmarkEntry::wordCount).sum(),
            "corrupted_words", 0));

    Gson pretty = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    Files.writeString(outDir.resolve("summary.json"), pretty.toJson(summary) + "\n");

    String readme =
        "# Controlled Synthetic Turkish Normalization Benchmarks (3 x 1,000 Sentences)\n\n"
            + "Generated by `zemberek.normalization.dataset.SyntheticBenchmarkBuilder` with strict\n"
            + "Zemberek morphological & TDK orthographic gold verification and 0% sentence/5-gram\n"
            + "overlap against `noisy_tr_v2` (342K sentences), `CleanWordLm` (1.8M sentences), and\n"
            + "`ForbiddenLeakageVault`.\n\n"
            + "## Files\n\n"
            + "- `synth_asciified_1k.tsv` / `.jsonl`: 1,000 completely asciified multi-domain sentences (`>= 2` diacritic words/sentence).\n"
            + "- `synth_typo_1k.tsv` / `.jsonl`: 1,000 sentences with 1–3 Turkish Q-keyboard Damerau-Levenshtein distance-1 non-word typos (`sub_kbd`, `del`, `swap`, `ins`).\n"
            + "- `synth_clean_1k.tsv` / `.jsonl`: 1,000 zero-error verified clean sentences measuring false-positive corruption (`CleanFPR`).\n\n"
            + "## Domain Balance (Identical Across All 3 Disjoint Pools)\n\n"
            + "- `news_economy`: 35% (350 sentences)\n"
            + "- `conversational`: 30% (300 sentences)\n"
            + "- `science_knowledge`: 20% (200 sentences)\n"
            + "- `parliament_civic`: 15% (150 sentences)\n";
    Files.writeString(outDir.resolve("README.md"), readme);
  }

  private static String sha256(Path file) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    md.update(Files.readAllBytes(file));
    return HexFormat.of().formatHex(md.digest());
  }

  private static Map<Character, String> buildTurkishQNeighbors() {
    Map<Character, String> m = new HashMap<>();
    m.put('e', "wrds");
    m.put('r', "etdf");
    m.put('t', "ryfg");
    m.put('y', "tugh");
    m.put('u', "yığhj");
    m.put('ı', "uojk");
    m.put('o', "ıpkl");
    m.put('p', "oğşl");
    m.put('ğ', "püliş");
    m.put('ü', "ğşi");
    m.put('a', "sz");
    m.put('s', "adez");
    m.put('d', "serfc");
    m.put('f', "drtgv");
    m.put('g', "ftyhb");
    m.put('h', "gyujn");
    m.put('j', "huıkm");
    m.put('k', "jıolöm");
    m.put('l', "kopşöç");
    m.put('ş', "lpğüiç");
    m.put('i', "şğüç");
    m.put('z', "as");
    m.put('c', "dfv");
    m.put('v', "cfgb");
    m.put('b', "vghn");
    m.put('n', "bhjm");
    m.put('m', "njkö");
    m.put('ö', "mklşç");
    m.put('ç', "ölşi");
    return Map.copyOf(m);
  }
}
