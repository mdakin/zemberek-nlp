package zemberek.morphology.ambiguity.model;

import com.beust.jcommander.Parameter;
import com.google.common.base.Stopwatch;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import zemberek.apps.ConsoleApp;
import zemberek.core.logging.Log;
import zemberek.core.text.TextUtil;
import zemberek.core.turkish.PrimaryPos;
import zemberek.core.turkish.RootAttribute;
import zemberek.core.turkish.SecondaryPos;
import zemberek.core.turkish.Turkish;
import zemberek.core.turkish.TurkishAlphabet;
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.analysis.AnalysisCache;
import zemberek.morphology.analysis.SingleAnalysis;
import zemberek.morphology.analysis.WordAnalysis;
import zemberek.morphology.lexicon.DictionaryItem;
import zemberek.morphology.lexicon.RootLexicon;
import zemberek.morphology.morphotactics.Morpheme;
import zemberek.tokenization.Token;
import zemberek.tokenization.TurkishTokenizer;

/**
 * Step 2 of the ambiguity model pipeline: Zemberek Ambiguity Signature v3 (ZAS-v3) Corpus Profiler.
 *
 * <p>Profiles Turkish morphological ambiguity across {@code ~/data/turkish/refined} (without using
 * a disambiguator) into a 4-tier hierarchy:
 * <ol>
 *   <li><b>Tier 1 (Macro-Mechanism M1..M7)</b>: Structural origin of the ambiguity.</li>
 *   <li><b>Tier 2 (Canonical Meso-Signature)</b>: Order-invariant sorted morphotactic signature
 *       with explicit zero-morphemes and stem-boundary / twin markers.</li>
 *   <li><b>Tier 3 (Decoder Feature Observability & Root Attributes)</b>: Quantifies whether
 *       {@code FastPerceptronAmbiguityResolver}'s unlexicalized transition features ({@code F15},
 *       {@code F17}) vs. lexicalized features ({@code F2..F10}) can distinguish the candidates.</li>
 *   <li><b>Tier 4 (Consensus Context Anchor Frames)</b>: Extracts certain grammatical anchors from
 *       unambiguous ({@code U:}) and candidate-consensus ({@code C:}) left/right neighbors.</li>
 * </ol>
 */
public class AmbiguityProfiler extends ConsoleApp {

  @Parameter(
      names = {"--inputDir", "-i"},
      description = "Directory containing refined .txt corpus files.")
  public Path inputDir = Paths.get(System.getProperty("user.home"), "data/turkish/refined");

  @Parameter(
      names = {"--reportOut", "-r"},
      description = "Markdown report output path.")
  public Path reportOut = Paths.get("experiment/docs/model/turkish_ambiguity_groups_v3_report.md");

  @Parameter(
      names = {"--jsonOut", "-j"},
      description = "Structured JSON catalog output path.")
  public Path jsonOut = Paths.get("experiment/docs/model/turkish_ambiguity_groups_v3.json");

  @Parameter(
      names = {"--threads", "-t"},
      description = "Number of worker threads.")
  public int threads = 16;

  @Parameter(
      names = {"--maxLinesPerFile"},
      description = "Maximum number of lines (sentences) to read per file (-1 for unlimited).")
  public int maxLinesPerFile = -1;

  @Parameter(
      names = {"--maxFiles"},
      description = "Maximum number of files to process (-1 for all files).")
  public int maxFiles = -1;

  @Parameter(
      names = {"--topGroups"},
      description = "Number of top Meso-Signatures to export in full detail.")
  public int topGroups = 150;

  @Parameter(
      names = {"--batchSize"},
      description = "Number of sentences per worker task.")
  public int batchSize = 2000;

  private static final int MAX_TOKEN_PROFILE_CACHE = 500_000;
  private static final int MAX_MESO_SIGNATURES = 100_000;
  private static final int MAX_LEMMAS_PER_GROUP = 2_000;
  private static final int MAX_FRAMES_PER_GROUP = 500;

  public enum MacroMechanism {
    M1_INFLECTIONAL_SYNCRETISM("Same DictionaryItem & derivational chain; pure suffix homophony (-I, -In, -lArI)"),
    M2_DERIVATIONAL_SHIFT("Same DictionaryItem; different IG count or derived POS (Participle/Infinitive/Zero)"),
    M3_LEXICAL_POS_POLYSEMY("Different DictionaryItems, same lemma & stem, different POS (Adj/Noun, Det/Pron, Adv/Noun)"),
    M4_PROPER_VS_COMMON("ProperNoun/Abbreviation vs. Common Noun/Adj/Verb collision"),
    M5a_VOICE_LEXICALIZED("Stem-boundary split with identical final IG (belir+Caus vs belirt) — F15/F17 blind"),
    M5b_STEM_SUFFIX_COLLISION("Stem-boundary split with different final IGs (koy+un vs koyun, yaş+a+ma vs yaşa+ma)"),
    M6a_HOMONYM_DIFF_POS("Same-length homonymous roots or phonological alternations across different POS (yüz, var, bin)"),
    M6b_ORTHO_OR_COMPOUND_TWIN("Circumflex twins (kar/kâr), CompoundP3sg twins (zeytinyağı), or vowel-drop twins"),
    M7_HYBRID_MULTI_LOCUS("Multi-locus ambiguity combining root/POS collision and inflectional/derivational syncretism");

    public final String description;

    MacroMechanism(String description) {
      this.description = description;
    }
  }

  public enum ObservabilityClass {
    FULL_MORPHO_OBSERVABLE("U_lastIG == K: F15/F17 unlexicalized transitions can distinguish all candidates"),
    PARTIAL_MORPHO_OBSERVABLE("1 < U_lastIG < K: F15/F17 narrow candidates; lexical/IG-count features break ties"),
    LEXICAL_ONLY_OBSERVABLE("U_lastIG == 1: All candidates share the exact same lastGroup; F15/F17 are 100% blind");

    public final String description;

    ObservabilityClass(String description) {
      this.description = description;
    }
  }

  // RootAttribute bit flags tracked per token
  private static final int ATTR_VOICING = 1;
  private static final int ATTR_LAST_VOWEL_DROP = 1 << 1;
  private static final int ATTR_DOUBLING = 1 << 2;
  private static final int ATTR_COMPOUND_P3SG = 1 << 3;
  private static final int ATTR_INVERSE_HARMONY = 1 << 4;
  private static final int ATTR_RUNTIME = 1 << 5;
  private static final int ATTR_EXT = 1 << 6;
  private static final String[] ATTR_NAMES = {
      "Voicing", "LastVowelDrop", "Doubling", "CompoundP3sg", "InverseHarmony", "Runtime", "Ext"
  };

  /**
   * Immutable cached profile of a single analyzed word token.
   */
  static final class TokenProfile {
    final String surface;
    final boolean isPunct;
    final int candidateCount;
    final String consensusAnchor; // Used when this token is a left/right neighbor (U:..., C:..., A:...)
    final int anchorCertainty; // 0 = exact unambiguous (K=1), 1 = consensus (K>=2), 2 = POS-ambiguous

    // Populated when candidateCount >= 2:
    final MacroMechanism mechanism;
    final ObservabilityClass observability;
    final String mesoSignature;
    final String lemmaKey; // e.g. "karar" or "belir/belirt" or "kar/kâr"
    final int uniqueItems;
    final int uniqueLemmas;
    final int uniqueLastGroups;
    final int uniqueGroupCounts;
    final int attrMask;

    TokenProfile(
        String surface,
        boolean isPunct,
        int candidateCount,
        String consensusAnchor,
        int anchorCertainty,
        MacroMechanism mechanism,
        ObservabilityClass observability,
        String mesoSignature,
        String lemmaKey,
        int uniqueItems,
        int uniqueLemmas,
        int uniqueLastGroups,
        int uniqueGroupCounts,
        int attrMask) {
      this.surface = surface;
      this.isPunct = isPunct;
      this.candidateCount = candidateCount;
      this.consensusAnchor = consensusAnchor;
      this.anchorCertainty = anchorCertainty;
      this.mechanism = mechanism;
      this.observability = observability;
      this.mesoSignature = mesoSignature;
      this.lemmaKey = lemmaKey;
      this.uniqueItems = uniqueItems;
      this.uniqueLemmas = uniqueLemmas;
      this.uniqueLastGroups = uniqueLastGroups;
      this.uniqueGroupCounts = uniqueGroupCounts;
      this.attrMask = attrMask;
    }
  }

  /**
   * Aggregated corpus statistics for a single Meso-Signature.
   */
  static final class SignatureStats {
    final String mesoSignature;
    final MacroMechanism mechanism;
    final ObservabilityClass observability;
    final int candidateCount;
    final int uniqueItems;
    final int uniqueLemmasPerToken;
    final int uniqueLastGroups;

    final LongAdder occurrences = new LongAdder();
    final AtomicLongArray positionCounts = new AtomicLongArray(4); // 0=INIT, 1=INTERNAL, 2=PRE_FINAL, 3=FINAL
    final AtomicLongArray attrCounts = new AtomicLongArray(ATTR_NAMES.length);
    final ConcurrentHashMap<String, LongAdder> lemmaCounts = new ConcurrentHashMap<>(64);
    final ConcurrentHashMap<String, LongAdder> surfaceCounts = new ConcurrentHashMap<>(64);
    final ConcurrentHashMap<String, LongAdder> contextFrameCounts = new ConcurrentHashMap<>(64);

    SignatureStats(TokenProfile proto) {
      this.mesoSignature = proto.mesoSignature;
      this.mechanism = proto.mechanism;
      this.observability = proto.observability;
      this.candidateCount = proto.candidateCount;
      this.uniqueItems = proto.uniqueItems;
      this.uniqueLemmasPerToken = proto.uniqueLemmas;
      this.uniqueLastGroups = proto.uniqueLastGroups;
    }

    void record(TokenProfile tp, int posRole, String leftAnchor, String rightAnchor) {
      occurrences.increment();
      positionCounts.incrementAndGet(posRole);
      int mask = tp.attrMask;
      for (int b = 0; b < ATTR_NAMES.length; b++) {
        if ((mask & (1 << b)) != 0) {
          attrCounts.incrementAndGet(b);
        }
      }
      if (lemmaCounts.size() < MAX_LEMMAS_PER_GROUP) {
        lemmaCounts.computeIfAbsent(tp.lemmaKey, k -> new LongAdder()).increment();
      } else {
        LongAdder la = lemmaCounts.get(tp.lemmaKey);
        if (la != null) {
          la.increment();
        }
      }
      String surfLower = tp.surface.toLowerCase(Turkish.LOCALE);
      if (surfaceCounts.size() < MAX_LEMMAS_PER_GROUP) {
        surfaceCounts.computeIfAbsent(surfLower, k -> new LongAdder()).increment();
      } else {
        LongAdder sa = surfaceCounts.get(surfLower);
        if (sa != null) {
          sa.increment();
        }
      }
      String frameKey = leftAnchor + " __ " + rightAnchor;
      if (contextFrameCounts.size() < MAX_FRAMES_PER_GROUP) {
        contextFrameCounts.computeIfAbsent(frameKey, k -> new LongAdder()).increment();
      } else {
        LongAdder fa = contextFrameCounts.get(frameKey);
        if (fa != null) {
          fa.increment();
        }
      }
    }
  }

  public static void main(String[] args) {
    new AmbiguityProfiler().execute(args);
  }

  @Override
  public String description() {
    return "Profiles Turkish morphological ambiguity across the refined corpus using the 4-tier ZAS-v3 taxonomy.";
  }

  @Override
  public void run() throws Exception {
    if (!Files.exists(inputDir) || !Files.isDirectory(inputDir)) {
      throw new IllegalArgumentException("Input directory does not exist: " + inputDir);
    }
    if (reportOut != null && reportOut.getParent() != null) {
      Files.createDirectories(reportOut.getParent());
    }
    if (jsonOut != null && jsonOut.getParent() != null) {
      Files.createDirectories(jsonOut.getParent());
    }

    List<Path> inputFiles;
    try (Stream<Path> stream = Files.list(inputDir)) {
      inputFiles = stream
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".txt"))
          .sorted(Comparator.comparing(p -> p.getFileName().toString()))
          .collect(Collectors.toList());
    }
    if (maxFiles > 0 && inputFiles.size() > maxFiles) {
      inputFiles = new ArrayList<>(inputFiles.subList(0, maxFiles));
    }

    Log.info("=== AmbiguityProfiler (ZAS-v3) Configuration ===");
    Log.info("Input directory   : %s (%d files)", inputDir, inputFiles.size());
    Log.info("Markdown report   : %s", reportOut);
    Log.info("JSON catalog      : %s", jsonOut);
    Log.info("Worker threads    : %d", threads);
    Log.info("Max lines / file  : %s", maxLinesPerFile > 0 ? String.valueOf(maxLinesPerFile) : "UNLIMITED");

    Stopwatch sw = Stopwatch.createStarted();

    final RootLexicon sharedLexicon = RootLexicon.getDefault();
    final AnalysisCache sharedCache = AnalysisCache.builder()
        .dynamicCacheSize(100_000, 1_000_000)
        .build();
    final TurkishTokenizer tokenizer = TurkishTokenizer.DEFAULT;

    final ThreadLocal<TurkishMorphology> threadMorphology = ThreadLocal.withInitial(() ->
        TurkishMorphology.builder()
            .setLexicon(sharedLexicon)
            .setCache(sharedCache)
            .setTokenizer(tokenizer)
            .setAmbiguityResolver((sentence, analyses) -> null)
            .build());

    final ConcurrentHashMap<String, TokenProfile> profileCache = new ConcurrentHashMap<>(131_072);
    final ConcurrentHashMap<String, SignatureStats> signatureMap = new ConcurrentHashMap<>(16_384);
    final ConcurrentHashMap<String, LongAdder> unambiguousBigramCounts = new ConcurrentHashMap<>(32_768);

    final AtomicLong totalSentences = new AtomicLong();
    final AtomicLong totalWords = new AtomicLong();
    final AtomicLong unambiguousWords = new AtomicLong();
    final AtomicLong ambiguousWords = new AtomicLong();
    final AtomicLong totalCandidatesOnAmbig = new AtomicLong();

    final AtomicLongArray mechanismTokenCounts = new AtomicLongArray(MacroMechanism.values().length);
    final AtomicLongArray observabilityTokenCounts = new AtomicLongArray(ObservabilityClass.values().length);
    final AtomicLongArray neighborCertaintyCounts = new AtomicLongArray(4); // 0=Boundary, 1=U(K=1), 2=C(Consensus), 3=A(Ambig)

    ExecutorService executor = Executors.newFixedThreadPool(threads);
    Semaphore inFlightGate = new Semaphore(threads * 4);

    try {
      for (int idx = 0; idx < inputFiles.size(); idx++) {
        Path inFile = inputFiles.get(idx);
        String fileName = inFile.getFileName().toString();
        Stopwatch fileSw = Stopwatch.createStarted();
        List<Future<?>> futures = new ArrayList<>();
        long linesRead = 0;

        try (BufferedReader reader = Files.newBufferedReader(inFile, StandardCharsets.UTF_8)) {
          List<String> batch = new ArrayList<>(batchSize);
          String line;
          while ((line = reader.readLine()) != null) {
            linesRead++;
            if (!line.isBlank()) {
              batch.add(line);
              if (batch.size() >= batchSize) {
                submitBatch(
                    batch,
                    executor,
                    inFlightGate,
                    futures,
                    threadMorphology,
                    tokenizer,
                    profileCache,
                    signatureMap,
                    unambiguousBigramCounts,
                    totalSentences,
                    totalWords,
                    unambiguousWords,
                    ambiguousWords,
                    totalCandidatesOnAmbig,
                    mechanismTokenCounts,
                    observabilityTokenCounts,
                    neighborCertaintyCounts);
                batch = new ArrayList<>(batchSize);
              }
            }
            if (maxLinesPerFile > 0 && linesRead >= maxLinesPerFile) {
              break;
            }
          }
          if (!batch.isEmpty()) {
            submitBatch(
                batch,
                executor,
                inFlightGate,
                futures,
                threadMorphology,
                tokenizer,
                profileCache,
                signatureMap,
                unambiguousBigramCounts,
                totalSentences,
                totalWords,
                unambiguousWords,
                ambiguousWords,
                totalCandidatesOnAmbig,
                mechanismTokenCounts,
                observabilityTokenCounts,
                neighborCertaintyCounts);
          }
          for (Future<?> f : futures) {
            f.get();
          }
        }

        Log.info("[%d/%d] %s (%,d sents) profiled in %.2f s | unique signatures so far: %,d",
            idx + 1,
            inputFiles.size(),
            fileName,
            linesRead,
            fileSw.elapsed(TimeUnit.MILLISECONDS) / 1000.0,
            signatureMap.size());
      }
    } finally {
      executor.shutdown();
      executor.awaitTermination(1, TimeUnit.HOURS);
    }

    long elapsedMs = sw.elapsed(TimeUnit.MILLISECONDS);
    exportReports(
        inputFiles.size(),
        elapsedMs,
        totalSentences.get(),
        totalWords.get(),
        unambiguousWords.get(),
        ambiguousWords.get(),
        totalCandidatesOnAmbig.get(),
        mechanismTokenCounts,
        observabilityTokenCounts,
        neighborCertaintyCounts,
        signatureMap,
        unambiguousBigramCounts);

    Log.info("Ambiguity profiling completed in %.2f s.", elapsedMs / 1000.0);
    Log.info("Markdown report : %s", reportOut.toAbsolutePath());
    Log.info("JSON catalog    : %s", jsonOut.toAbsolutePath());
  }

  private void submitBatch(
      List<String> sentences,
      ExecutorService executor,
      Semaphore inFlightGate,
      List<Future<?>> futures,
      ThreadLocal<TurkishMorphology> threadMorphology,
      TurkishTokenizer tokenizer,
      ConcurrentHashMap<String, TokenProfile> profileCache,
      ConcurrentHashMap<String, SignatureStats> signatureMap,
      ConcurrentHashMap<String, LongAdder> unambiguousBigramCounts,
      AtomicLong totalSentences,
      AtomicLong totalWords,
      AtomicLong unambiguousWords,
      AtomicLong ambiguousWords,
      AtomicLong totalCandidatesOnAmbig,
      AtomicLongArray mechanismTokenCounts,
      AtomicLongArray observabilityTokenCounts,
      AtomicLongArray neighborCertaintyCounts) throws InterruptedException {

    inFlightGate.acquire();
    Future<?> future = executor.submit(() -> {
      try {
        TurkishMorphology morphology = threadMorphology.get();
        long localSents = 0;
        long localWords = 0;
        long localUnambig = 0;
        long localAmbig = 0;
        long localCandsOnAmbig = 0;
        long[] localMech = new long[MacroMechanism.values().length];
        long[] localObs = new long[ObservabilityClass.values().length];
        long[] localCert = new long[4];

        List<TokenProfile> wordProfiles = new ArrayList<>(32);

        for (int sIdx = 0; sIdx < sentences.size(); sIdx++) {
          String sentence = sentences.get(sIdx);
          String normalized = TextUtil.normalizeQuotesHyphens(sentence);
          List<Token> tokens = tokenizer.tokenize(normalized);
          wordProfiles.clear();

          for (int t = 0; t < tokens.size(); t++) {
            Token tok = tokens.get(t);
            if (tok.getType() == Token.Type.Punctuation) {
              continue;
            }
            String text = tok.getText();
            TokenProfile tp = profileCache.get(text);
            if (tp == null) {
              WordAnalysis wa = morphology.analyze(tok);
              tp = buildTokenProfile(text, wa);
              if (profileCache.size() < MAX_TOKEN_PROFILE_CACHE) {
                TokenProfile prev = profileCache.putIfAbsent(text, tp);
                if (prev != null) {
                  tp = prev;
                }
              }
            }
            if (!tp.isPunct && tp.candidateCount > 0) {
              wordProfiles.add(tp);
            }
          }

          int n = wordProfiles.size();
          if (n == 0) {
            continue;
          }
          localSents++;
          localWords += n;

          for (int i = 0; i < n; i++) {
            TokenProfile wp = wordProfiles.get(i);
            if (wp.candidateCount == 1) {
              localUnambig++;
              // Track unambiguous word bigrams (like 2012 unambiguous_bigrams.txt)
              if (i + 1 < n && wordProfiles.get(i + 1).candidateCount == 1) {
                if (unambiguousBigramCounts.size() < 50_000) {
                  String bg = wp.surface.toLowerCase(Turkish.LOCALE) + " "
                      + wordProfiles.get(i + 1).surface.toLowerCase(Turkish.LOCALE);
                  unambiguousBigramCounts.computeIfAbsent(bg, k -> new LongAdder()).increment();
                }
              }
            } else {
              localAmbig++;
              localCandsOnAmbig += wp.candidateCount;
              localMech[wp.mechanism.ordinal()]++;
              localObs[wp.observability.ordinal()]++;

              int posRole;
              if (i == 0) {
                posRole = 0; // SENT_INITIAL
              } else if (i == n - 1) {
                posRole = 3; // SENT_FINAL
              } else if (i == n - 2) {
                posRole = 2; // PRE_FINAL
              } else {
                posRole = 1; // INTERNAL
              }

              String leftAnchor;
              if (i == 0) {
                leftAnchor = "<S>";
                localCert[0]++;
              } else {
                TokenProfile prev = wordProfiles.get(i - 1);
                leftAnchor = prev.consensusAnchor;
                localCert[prev.anchorCertainty + 1]++;
              }

              String rightAnchor;
              if (i == n - 1) {
                rightAnchor = "</S>";
                localCert[0]++;
              } else {
                TokenProfile next = wordProfiles.get(i + 1);
                rightAnchor = next.consensusAnchor;
                localCert[next.anchorCertainty + 1]++;
              }

              SignatureStats ss = signatureMap.get(wp.mesoSignature);
              if (ss == null) {
                if (signatureMap.size() < MAX_MESO_SIGNATURES) {
                  SignatureStats created = new SignatureStats(wp);
                  SignatureStats existing = signatureMap.putIfAbsent(wp.mesoSignature, created);
                  ss = (existing != null) ? existing : created;
                }
              }
              if (ss != null) {
                ss.record(wp, posRole, leftAnchor, rightAnchor);
              }
            }
          }
        }

        totalSentences.addAndGet(localSents);
        totalWords.addAndGet(localWords);
        unambiguousWords.addAndGet(localUnambig);
        ambiguousWords.addAndGet(localAmbig);
        totalCandidatesOnAmbig.addAndGet(localCandsOnAmbig);
        for (int i = 0; i < localMech.length; i++) {
          if (localMech[i] != 0) {
            mechanismTokenCounts.addAndGet(i, localMech[i]);
          }
        }
        for (int i = 0; i < localObs.length; i++) {
          if (localObs[i] != 0) {
            observabilityTokenCounts.addAndGet(i, localObs[i]);
          }
        }
        for (int i = 0; i < localCert.length; i++) {
          if (localCert[i] != 0) {
            neighborCertaintyCounts.addAndGet(i, localCert[i]);
          }
        }
      } finally {
        inFlightGate.release();
      }
    });
    futures.add(future);
  }

  static TokenProfile buildTokenProfile(String surface, WordAnalysis wa) {
    List<SingleAnalysis> results = wa.getAnalysisResults();
    int K = results.size();
    if (K == 0 || results.get(0).isUnknown()) {
      return new TokenProfile(surface, false, 0, "UNK", 2, null, null, null, null, 0, 0, 0, 0, 0);
    }

    // 1. Build Consensus Context Anchor for when this word is a neighbor (w_{t-1} or w_{t+1})
    String firstConcise = conciseNeighborTag(results.get(0));
    boolean allSameConcise = true;
    String firstFinalPos = finalPosTag(results.get(0));
    boolean allSameFinalPos = true;

    for (int i = 1; i < K; i++) {
      SingleAnalysis sa = results.get(i);
      if (allSameConcise && !firstConcise.equals(conciseNeighborTag(sa))) {
        allSameConcise = false;
      }
      if (allSameFinalPos && !firstFinalPos.equals(finalPosTag(sa))) {
        allSameFinalPos = false;
      }
    }

    String consensusAnchor;
    int anchorCertainty;
    if (K == 1) {
      consensusAnchor = "U:" + firstConcise;
      anchorCertainty = 0;
    } else if (allSameConcise) {
      consensusAnchor = "C:" + firstConcise;
      anchorCertainty = 1;
    } else if (allSameFinalPos) {
      consensusAnchor = "C:" + firstFinalPos;
      anchorCertainty = 1;
    } else {
      Set<String> posSet = new HashSet<>(4);
      for (int i = 0; i < K; i++) {
        posSet.add(finalPosTag(results.get(i)));
      }
      List<String> sortedPos = new ArrayList<>(posSet);
      Collections.sort(sortedPos);
      consensusAnchor = "A:" + String.join("/", sortedPos);
      anchorCertainty = 2;
    }

    if (K == 1) {
      return new TokenProfile(surface, false, 1, consensusAnchor, anchorCertainty,
          null, null, null, results.get(0).getDictionaryItem().lemma, 1, 1, 1, 1, 0);
    }

    // 2. Analyze K >= 2 candidates
    Set<DictionaryItem> itemSet = new HashSet<>(K);
    Set<String> lemmaSet = new HashSet<>(K);
    Set<String> normLemmaSet = new HashSet<>(K);
    Set<String> lastGroupSet = new HashSet<>(K);
    Set<Integer> groupCountSet = new HashSet<>(K);
    Set<String> posPairSet = new HashSet<>(K);
    Set<PrimaryPos> rootPosSet = new HashSet<>(K);
    Set<String> derivChainSet = new HashSet<>(K);

    int minStemLen = Integer.MAX_VALUE;
    int maxStemLen = Integer.MIN_VALUE;
    boolean hasProper = false;
    boolean hasNonProper = false;
    boolean hasCompoundP3sgAttr = false;
    int attrMask = 0;

    TurkishAlphabet alphabet = TurkishAlphabet.INSTANCE;

    for (int i = 0; i < K; i++) {
      SingleAnalysis sa = results.get(i);
      DictionaryItem item = sa.getDictionaryItem();
      itemSet.add(item);
      lemmaSet.add(item.lemma);
      String normLemma = alphabet.normalize(item.lemma.toLowerCase(Turkish.LOCALE));
      normLemmaSet.add(normLemma);

      int stemLen = sa.getStem().length();
      if (stemLen < minStemLen) {
        minStemLen = stemLen;
      }
      if (stemLen > maxStemLen) {
        maxStemLen = stemLen;
      }

      SecondaryPos sp = item.secondaryPos;
      boolean isProp = (sp == SecondaryPos.ProperNoun
          || sp == SecondaryPos.Abbreviation
          || sp == SecondaryPos.RegularAbbreviation);
      if (isProp) {
        hasProper = true;
      } else {
        hasNonProper = true;
      }

      rootPosSet.add(item.primaryPos);
      posPairSet.add(item.primaryPos.shortForm + "," + sp.shortForm);

      int gCount = sa.groupCount();
      groupCountSet.add(gCount);

      StringBuilder dChain = new StringBuilder();
      for (int g = 0; g < gCount; g++) {
        if (g > 0) {
          dChain.append("->");
        }
        dChain.append(sa.getGroup(g).getPos().shortForm);
      }
      derivChainSet.add(dChain.toString());

      // Exact FastPerceptronAmbiguityResolver lastGroup string
      String spPrefix = (sp == SecondaryPos.None) ? "" : sp.name();
      String lastIg = (gCount == 1)
          ? (spPrefix + sa.getGroup(0).lexicalForm())
          : sa.getGroup(gCount - 1).lexicalForm();
      lastGroupSet.add(lastIg);

      if (item.hasAttribute(RootAttribute.Voicing)) {
        attrMask |= ATTR_VOICING;
      }
      if (item.hasAttribute(RootAttribute.LastVowelDrop)) {
        attrMask |= ATTR_LAST_VOWEL_DROP;
      }
      if (item.hasAttribute(RootAttribute.Doubling)) {
        attrMask |= ATTR_DOUBLING;
      }
      if (item.hasAttribute(RootAttribute.CompoundP3sg) || item.hasAttribute(RootAttribute.CompoundP3sgRoot)) {
        attrMask |= ATTR_COMPOUND_P3SG;
        hasCompoundP3sgAttr = true;
      }
      if (item.hasAttribute(RootAttribute.InverseHarmony)) {
        attrMask |= ATTR_INVERSE_HARMONY;
      }
      if (item.hasAttribute(RootAttribute.Runtime)) {
        attrMask |= ATTR_RUNTIME;
      }
      if (item.hasAttribute(RootAttribute.Ext)) {
        attrMask |= ATTR_EXT;
      }
    }

    int uItems = itemSet.size();
    int uLemmas = lemmaSet.size();
    int uNormLemmas = normLemmaSet.size();
    int uLastGroups = lastGroupSet.size();
    int uGroupCounts = groupCountSet.size();

    // 3. Determine ObservabilityClass for FastPerceptronAmbiguityResolver
    ObservabilityClass observability;
    if (uLastGroups == K) {
      observability = ObservabilityClass.FULL_MORPHO_OBSERVABLE;
    } else if (uLastGroups == 1) {
      observability = ObservabilityClass.LEXICAL_ONLY_OBSERVABLE;
    } else {
      observability = ObservabilityClass.PARTIAL_MORPHO_OBSERVABLE;
    }

    // 4. Determine MacroMechanism (M1..M7)
    MacroMechanism mechanism;
    boolean sameStemLen = (minStemLen == maxStemLen);
    if (uItems == 1) {
      if (derivChainSet.size() == 1) {
        mechanism = MacroMechanism.M1_INFLECTIONAL_SYNCRETISM;
      } else {
        mechanism = MacroMechanism.M2_DERIVATIONAL_SHIFT;
      }
    } else if (hasProper && hasNonProper) {
      mechanism = MacroMechanism.M4_PROPER_VS_COMMON;
    } else if (uNormLemmas == 1 && sameStemLen) {
      if (posPairSet.size() > 1) {
        if (K > uItems) {
          mechanism = MacroMechanism.M7_HYBRID_MULTI_LOCUS;
        } else {
          mechanism = MacroMechanism.M3_LEXICAL_POS_POLYSEMY;
        }
      } else {
        mechanism = MacroMechanism.M6b_ORTHO_OR_COMPOUND_TWIN;
      }
    } else if (!sameStemLen) {
      if (hasCompoundP3sgAttr && uNormLemmas <= 2 && rootPosSet.size() == 1) {
        mechanism = MacroMechanism.M6b_ORTHO_OR_COMPOUND_TWIN;
      } else if (uLastGroups == 1 && rootPosSet.size() == 1) {
        mechanism = MacroMechanism.M5a_VOICE_LEXICALIZED;
      } else {
        mechanism = MacroMechanism.M5b_STEM_SUFFIX_COLLISION;
      }
    } else {
      if (posPairSet.size() > 1) {
        if (K > uItems && uLastGroups < K) {
          mechanism = MacroMechanism.M7_HYBRID_MULTI_LOCUS;
        } else {
          mechanism = MacroMechanism.M6a_HOMONYM_DIFF_POS;
        }
      } else {
        mechanism = MacroMechanism.M6b_ORTHO_OR_COMPOUND_TWIN;
      }
    }

    // 5. Build Canonical Sorted Meso-Signature
    String[] candDescs = new String[K];
    for (int i = 0; i < K; i++) {
      SingleAnalysis sa = results.get(i);
      DictionaryItem item = sa.getDictionaryItem();
      StringBuilder sb = new StringBuilder(48);

      if (uItems > 1 && !sameStemLen) {
        int delta = maxStemLen - sa.getStem().length();
        sb.append("STEM(").append(delta == 0 ? "0" : "-" + delta).append(")|");
      }

      int gCount = sa.groupCount();
      for (int g = 0; g < gCount; g++) {
        if (g > 0) {
          sb.append("->");
        }
        List<SingleAnalysis.MorphemeData> mList = sa.getGroup(g).getMorphemes();
        for (int m = 0; m < mList.size(); m++) {
          if (m > 0) {
            sb.append("+");
          }
          sb.append(mList.get(m).morpheme.id);
          if (g == 0 && m == 0 && item.secondaryPos != SecondaryPos.None) {
            sb.append(",").append(item.secondaryPos.shortForm);
          }
        }
      }

      // If multiple DictionaryItems share the same normalized lemma or CompoundP3sg, tag twin origin
      if (uItems > 1 && posPairSet.size() == 1 && derivChainSet.size() == 1) {
        if (alphabet.containsCircumflex(item.lemma)) {
          sb.append("@CIRC");
        } else if (item.hasAttribute(RootAttribute.CompoundP3sg)) {
          sb.append("@COMP_P3SG");
        } else if (item.hasAttribute(RootAttribute.CompoundP3sgRoot)) {
          sb.append("@COMP_ROOT");
        } else if (item.hasAttribute(RootAttribute.LastVowelDrop)) {
          sb.append("@VDROP");
        } else if (item.hasAttribute(RootAttribute.Ext)) {
          sb.append("@EXT");
        }
      }

      candDescs[i] = sb.toString();
    }

    Arrays.sort(candDescs);
    // Ensure identical descriptors from distinct DictionaryItems are explicitly marked (#2)
    for (int i = 1; i < K; i++) {
      if (candDescs[i].equals(candDescs[i - 1])) {
        candDescs[i] = candDescs[i] + "#ALT";
      }
    }
    String mesoSignature = String.join(" || ", candDescs);

    // 6. Build canonical lemma key
    String lemmaKey;
    if (uLemmas == 1) {
      lemmaKey = results.get(0).getDictionaryItem().lemma;
    } else {
      List<String> sortedLemmas = new ArrayList<>(lemmaSet);
      Collections.sort(sortedLemmas);
      lemmaKey = String.join("/", sortedLemmas);
    }

    return new TokenProfile(
        surface,
        false,
        K,
        consensusAnchor,
        anchorCertainty,
        mechanism,
        observability,
        mesoSignature,
        lemmaKey,
        uItems,
        uLemmas,
        uLastGroups,
        uGroupCounts,
        attrMask);
  }

  private static String finalPosTag(SingleAnalysis sa) {
    int gCount = sa.groupCount();
    PrimaryPos finalPos = sa.getGroup(gCount - 1).getPos();
    SecondaryPos sp = sa.getDictionaryItem().secondaryPos;
    if (gCount == 1 && (sp == SecondaryPos.PCDat || sp == SecondaryPos.PCAbl
        || sp == SecondaryPos.PCIns || sp == SecondaryPos.PCGen || sp == SecondaryPos.ProperNoun)) {
      return finalPos.shortForm + "," + sp.shortForm;
    }
    return finalPos.shortForm;
  }

  private static String conciseNeighborTag(SingleAnalysis sa) {
    int gCount = sa.groupCount();
    SingleAnalysis.MorphemeGroup lastGroup = sa.getGroup(gCount - 1);
    PrimaryPos finalPos = lastGroup.getPos();
    SecondaryPos sp = sa.getDictionaryItem().secondaryPos;

    StringBuilder sb = new StringBuilder(16);
    sb.append(finalPos.shortForm);
    if (gCount == 1 && sp != SecondaryPos.None) {
      if (sp == SecondaryPos.PCDat || sp == SecondaryPos.PCAbl || sp == SecondaryPos.PCIns
          || sp == SecondaryPos.PCGen || sp == SecondaryPos.DemonstrativePron || sp == SecondaryPos.ProperNoun
          || sp == SecondaryPos.Time || sp == SecondaryPos.QuestionPron) {
        sb.append(",").append(sp.shortForm);
      }
    }

    List<SingleAnalysis.MorphemeData> mList = lastGroup.getMorphemes();
    if (mList.size() > 1) {
      Morpheme lastM = mList.get(mList.size() - 1).morpheme;
      sb.append(":").append(lastM.id);
    }
    return sb.toString();
  }

  private static double computeEntropyBits(Map<String, LongAdder> map, long total) {
    if (total <= 0 || map.size() <= 1) {
      return 0.0;
    }
    double h = 0.0;
    double invLog2 = 1.0 / Math.log(2.0);
    for (LongAdder adder : map.values()) {
      long c = adder.sum();
      if (c > 0) {
        double p = (double) c / total;
        h -= p * Math.log(p) * invLog2;
      }
    }
    return h;
  }

  private static List<Map.Entry<String, Long>> topEntries(Map<String, LongAdder> map, int limit) {
    return map.entrySet().stream()
        .map(e -> Map.entry(e.getKey(), e.getValue().sum()))
        .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
        .limit(limit)
        .collect(Collectors.toList());
  }

  private static Map<String, Long> topEntriesMap(Map<String, LongAdder> map, int limit) {
    Map<String, Long> out = new LinkedHashMap<>();
    for (Map.Entry<String, Long> e : topEntries(map, limit)) {
      out.put(e.getKey(), e.getValue());
    }
    return out;
  }

  private void exportReports(
      int filesCount,
      long elapsedMs,
      long totalSentences,
      long totalWords,
      long unambiguousWords,
      long ambiguousWords,
      long totalCandidatesOnAmbig,
      AtomicLongArray mechanismTokenCounts,
      AtomicLongArray observabilityTokenCounts,
      AtomicLongArray neighborCertaintyCounts,
      ConcurrentHashMap<String, SignatureStats> signatureMap,
      ConcurrentHashMap<String, LongAdder> unambiguousBigramCounts) throws IOException {

    List<SignatureStats> sortedGroups = new ArrayList<>(signatureMap.values());
    sortedGroups.sort((a, b) -> Long.compare(b.occurrences.sum(), a.occurrences.sum()));

    // Count unique signatures per mechanism & observability
    int[] mechSigCounts = new int[MacroMechanism.values().length];
    int[] obsSigCounts = new int[ObservabilityClass.values().length];
    for (SignatureStats ss : sortedGroups) {
      mechSigCounts[ss.mechanism.ordinal()]++;
      obsSigCounts[ss.observability.ordinal()]++;
    }

    double sec = Math.max(0.001, elapsedMs / 1000.0);
    double ambigPct = totalWords > 0 ? (100.0 * ambiguousWords / totalWords) : 0.0;
    double avgCandAmbig = ambiguousWords > 0 ? ((double) totalCandidatesOnAmbig / ambiguousWords) : 0.0;

    long totalNeighbors = 0;
    for (int i = 0; i < 4; i++) {
      totalNeighbors += neighborCertaintyCounts.get(i);
    }
    long certainNeighbors = neighborCertaintyCounts.get(0) + neighborCertaintyCounts.get(1) + neighborCertaintyCounts.get(2);

    StringBuilder md = new StringBuilder(65_536);
    md.append("# Turkish Morphological Ambiguity Taxonomy & Corpus Profile Report (`ZAS-v3`)\n\n");
    md.append("## 1. Executive Summary & Corpus Scope\n\n");
    md.append("| Metric | Value |\n");
    md.append("| :--- | :--- |\n");
    md.append("| **Timestamp** | `").append(Instant.now().toString()).append("` |\n");
    md.append("| **Refined Corpus Directory** | `").append(inputDir.toAbsolutePath()).append("` (").append(filesCount).append(" files) |\n");
    md.append("| **Max Sentences / File** | `").append(maxLinesPerFile > 0 ? String.format(Locale.ROOT, "%,d", maxLinesPerFile) : "UNLIMITED (Full Corpus)").append("` |\n");
    md.append(String.format(Locale.ROOT, "| **Sentences Profiled** | **%,d** (%.0f sents/sec) |\n", totalSentences, totalSentences / sec));
    md.append(String.format(Locale.ROOT, "| **Total Words Profiled (Non-Punctuation)** | **%,d** (%.0f words/sec) |\n", totalWords, totalWords / sec));
    md.append(String.format(Locale.ROOT, "| **Unambiguous Words (`K = 1`)** | %,d (%.2f%%) |\n", unambiguousWords, 100.0 - ambigPct));
    md.append(String.format(Locale.ROOT, "| **Ambiguous Words (`K >= 2`)** | **%,d (%.2f%%)** |\n", ambiguousWords, ambigPct));
    md.append(String.format(Locale.ROOT, "| **Mean Candidate Analyses per Ambiguous Word** | **%.2f** |\n", avgCandAmbig));
    md.append(String.format(Locale.ROOT, "| **Unique Canonical Meso-Signatures (`ZAS-v3`)** | **%,d** |\n", sortedGroups.size()));
    md.append(String.format(Locale.ROOT, "| **Certain Context Anchor Coverage (`<S>` + `U:` + `C:`)** | **%.2f%%** of all neighbor positions (without disambiguator!) |\n\n",
        totalNeighbors > 0 ? 100.0 * certainNeighbors / totalNeighbors : 0.0));

    md.append("## 2. Tier 1: Breakdown by the 7 Macro-Mechanisms of Turkish Ambiguity\n\n");
    md.append("| Macro-Mechanism | Ambiguous Words | Share of Ambiguous (%) | Share of All Words (%) | Unique Signatures | Description |\n");
    md.append("| :--- | ---: | ---: | ---: | ---: | :--- |\n");
    for (MacroMechanism m : MacroMechanism.values()) {
      long count = mechanismTokenCounts.get(m.ordinal());
      double shareAmbig = ambiguousWords > 0 ? (100.0 * count / ambiguousWords) : 0.0;
      double shareAll = totalWords > 0 ? (100.0 * count / totalWords) : 0.0;
      md.append(String.format(
          Locale.ROOT,
          "| **`%s`** | %,d | **%.2f%%** | %.2f%% | %,d | %s |\n",
          m.name(), count, shareAmbig, shareAll, mechSigCounts[m.ordinal()], m.description));
    }
    md.append("\n");

    md.append("## 3. Tier 3: `FastPerceptronAmbiguityResolver` Feature Observability Breakdown\n\n");
    md.append("| Observability Class | Ambiguous Words | Share (%) | Unique Signatures | Architectural Meaning for `FastPerceptronAmbiguityResolver` |\n");
    md.append("| :--- | ---: | ---: | ---: | :--- |\n");
    for (ObservabilityClass oc : ObservabilityClass.values()) {
      long count = observabilityTokenCounts.get(oc.ordinal());
      double shareAmbig = ambiguousWords > 0 ? (100.0 * count / ambiguousWords) : 0.0;
      md.append(String.format(
          Locale.ROOT,
          "| **`%s`** | %,d | **%.2f%%** | %,d | %s |\n",
          oc.name(), count, shareAmbig, obsSigCounts[oc.ordinal()], oc.description));
    }
    md.append("\n");

    md.append("## 4. Tier 4: Neighbor Context Anchor Certainty (Without Any Disambiguator)\n\n");
    md.append("| Neighbor Anchor Type | Neighbor Positions | Share (%) | Meaning |\n");
    md.append("| :--- | ---: | ---: | :--- |\n");
    md.append(String.format(Locale.ROOT, "| **Sentence Boundary (`<S>` / `</S>`)** | %,d | %.2f%% | 100%% deterministic sentence start/end boundary |\n",
        neighborCertaintyCounts.get(0), totalNeighbors > 0 ? 100.0 * neighborCertaintyCounts.get(0) / totalNeighbors : 0.0));
    md.append(String.format(Locale.ROOT, "| **Exact Unambiguous Word (`U:`, `K = 1`)** | %,d | %.2f%% | Neighbor has only 1 morphological parse |\n",
        neighborCertaintyCounts.get(1), totalNeighbors > 0 ? 100.0 * neighborCertaintyCounts.get(1) / totalNeighbors : 0.0));
    md.append(String.format(Locale.ROOT, "| **Consensus Unambiguous (`C:`, `K >= 2`)** | %,d | **%.2f%%** | Neighbor has `>= 2` parses, but **100%% agree** on final POS/case! |\n",
        neighborCertaintyCounts.get(2), totalNeighbors > 0 ? 100.0 * neighborCertaintyCounts.get(2) / totalNeighbors : 0.0));
    md.append(String.format(Locale.ROOT, "| **POS-Ambiguous Neighbor (`A:`, `K >= 2`)** | %,d | %.2f%% | Neighbor candidates disagree on final POS |\n\n",
        neighborCertaintyCounts.get(3), totalNeighbors > 0 ? 100.0 * neighborCertaintyCounts.get(3) / totalNeighbors : 0.0));

    md.append("## 5. Cumulative Zipfian Coverage Across Canonical Meso-Signatures\n\n");
    md.append("| Top $N$ Signatures | Cumulative Ambiguous Words | Cumulative Share of Ambiguous (%) |\n");
    md.append("| ---: | ---: | ---: |\n");
    int[] checkpoints = {5, 10, 25, 50, 100, 250, 500, 1000};
    long running = 0;
    int cpIdx = 0;
    for (int i = 0; i < sortedGroups.size(); i++) {
      running += sortedGroups.get(i).occurrences.sum();
      if (cpIdx < checkpoints.length && (i + 1) == checkpoints[cpIdx]) {
        md.append(String.format(Locale.ROOT, "| **Top %,d** | %,d | **%.2f%%** |\n",
            checkpoints[cpIdx], running, ambiguousWords > 0 ? 100.0 * running / ambiguousWords : 0.0));
        cpIdx++;
      }
    }
    md.append(String.format(Locale.ROOT, "| **All %,d Signatures** | **%,d** | **100.00%%** |\n\n",
        sortedGroups.size(), ambiguousWords));

    int limit = Math.min(topGroups, sortedGroups.size());
    md.append("## 6. Top ").append(limit).append(" Canonical Ambiguity Signatures (`ZAS-v3` Summary Table)\n\n");
    md.append("| Rank | Count | Share % | Cum % | Mechanism | Observability | Regime | $H(\\text{lemma})$ | Unique Lemmas | Canonical Meso-Signature (`ZAS-v3`) | Top Surface Words |\n");
    md.append("| ---: | ---: | ---: | ---: | :--- | :--- | :--- | ---: | ---: | :--- | :--- |\n");

    long cumCount = 0;
    for (int i = 0; i < limit; i++) {
      SignatureStats ss = sortedGroups.get(i);
      long c = ss.occurrences.sum();
      cumCount += c;
      double pct = ambiguousWords > 0 ? (100.0 * c / ambiguousWords) : 0.0;
      double cumPct = ambiguousWords > 0 ? (100.0 * cumCount / ambiguousWords) : 0.0;
      double hLemma = computeEntropyBits(ss.lemmaCounts, c);
      int uLemmas = ss.lemmaCounts.size();
      String regime = (uLemmas <= 25 || hLemma < 2.5) ? "CLOSED_LEX" : "OPEN_PARADIGM";

      String topSurf = topEntries(ss.surfaceCounts, 5).stream()
          .map(e -> e.getKey() + " (" + e.getValue() + ")")
          .collect(Collectors.joining(", "));

      md.append(String.format(
          Locale.ROOT,
          "| %d | %,d | %.2f%% | %.2f%% | `%s` | `%s` | `%s` | %.2f | %,d | `%s` | %s |\n",
          i + 1,
          c,
          pct,
          cumPct,
          ss.mechanism.name(),
          ss.observability.name().replace("_OBSERVABLE", ""),
          regime,
          hLemma,
          uLemmas,
          ss.mesoSignature.replace("|", "\\|"),
          topSurf.replace("|", "\\|")));
    }
    md.append("\n");

    // Section 7: Detailed Profiles of Top 40 Signatures (like 2012 ambiguity_groups_empty_visible(1).txt, enriched!)
    int detailedLimit = Math.min(40, sortedGroups.size());
    md.append("## 7. Detailed Profiles of Top ").append(detailedLimit).append(" Ambiguity Signatures (with Context Anchor Frames)\n\n");
    for (int i = 0; i < detailedLimit; i++) {
      SignatureStats ss = sortedGroups.get(i);
      long c = ss.occurrences.sum();
      double pct = ambiguousWords > 0 ? (100.0 * c / ambiguousWords) : 0.0;
      double hLemma = computeEntropyBits(ss.lemmaCounts, c);
      double hFrame = computeEntropyBits(ss.contextFrameCounts, c);

      md.append("### #").append(i + 1).append(": `").append(ss.mesoSignature).append("`\n\n");
      md.append(String.format(Locale.ROOT,
          "- **Occurrences**: `%,d` (**%.2f%%** of all ambiguous words) | **Candidates ($K$)**: `%d` | **Distinct Items**: `%d` | **Distinct `lastGroup`s**: `%d`\n",
          c, pct, ss.candidateCount, ss.uniqueItems, ss.uniqueLastGroups));
      md.append(String.format(Locale.ROOT,
          "- **Mechanism**: `%s` | **Decoder Observability**: `%s`\n",
          ss.mechanism.name(), ss.observability.name()));
      md.append(String.format(Locale.ROOT,
          "- **Lexical Diversity**: `%,d` tracked lemmas ($H_{\\text{lemma}} = %.2f$ bits) | **Context Diversity**: `%,d` tracked frames ($H_{\\text{context}} = %.2f$ bits)\n",
          ss.lemmaCounts.size(), hLemma, ss.contextFrameCounts.size(), hFrame));
      md.append(String.format(Locale.ROOT,
          "- **Sentence Position Distribution**: Initial=`%.1f%%`, Internal=`%.1f%%`, Pre-Final=`%.1f%%`, Sentence-Final=`%.1f%%`\n",
          100.0 * ss.positionCounts.get(0) / c,
          100.0 * ss.positionCounts.get(1) / c,
          100.0 * ss.positionCounts.get(2) / c,
          100.0 * ss.positionCounts.get(3) / c));

      // Active RootAttributes
      List<String> activeAttrs = new ArrayList<>();
      for (int b = 0; b < ATTR_NAMES.length; b++) {
        long ac = ss.attrCounts.get(b);
        if (ac > 0) {
          activeAttrs.add(String.format(Locale.ROOT, "%s (%.1f%%)", ATTR_NAMES[b], 100.0 * ac / c));
        }
      }
      if (!activeAttrs.isEmpty()) {
        md.append("- **Active Lexicon `RootAttribute`s**: ").append(String.join(", ", activeAttrs)).append("\n");
      }

      String topWords = topEntries(ss.surfaceCounts, 12).stream()
          .map(e -> String.format(Locale.ROOT, "`%s` (%,d)", e.getKey(), e.getValue()))
          .collect(Collectors.joining(", "));
      md.append("- **Top Surface Words**: ").append(topWords).append("\n");

      String topLemmas = topEntries(ss.lemmaCounts, 10).stream()
          .map(e -> String.format(Locale.ROOT, "`%s` (%,d)", e.getKey(), e.getValue()))
          .collect(Collectors.joining(", "));
      md.append("- **Top Lemmas / Lemma Pairs**: ").append(topLemmas).append("\n");

      String topFrames = topEntries(ss.contextFrameCounts, 6).stream()
          .map(e -> String.format(Locale.ROOT, "`[%s]` (%,d)", e.getKey(), e.getValue()))
          .collect(Collectors.joining(", "));
      md.append("- **Top Consensus Context Frames (`LeftAnchor __ RightAnchor`)**: ").append(topFrames).append("\n\n");
    }

    // Section 8: Special Diagnostic Table — LEXICAL_ONLY_OBSERVABLE Signatures (Where F15/F17 are blind!)
    md.append("## 8. Diagnostic Audit: Top 25 `LEXICAL_ONLY_OBSERVABLE` Signatures (`F15`/`F17` Blind Twins & Collisions)\n\n");
    md.append("These ambiguity signatures have `U_lastIG = 1` (all candidates end in the exact same `lastGroup`), meaning `FastPerceptronAmbiguityResolver`'s unlexicalized transition features (`F15`, `F17`) have **zero discriminative power** on them:\n\n");
    md.append("| Rank | Occurrences | Mechanism | Signature | Top Lemma Collisions | Top Surface Words |\n");
    md.append("| ---: | ---: | :--- | :--- | :--- | :--- |\n");
    int lexRank = 0;
    for (SignatureStats ss : sortedGroups) {
      if (ss.observability != ObservabilityClass.LEXICAL_ONLY_OBSERVABLE) {
        continue;
      }
      lexRank++;
      long c = ss.occurrences.sum();
      String topLemmas = topEntries(ss.lemmaCounts, 5).stream()
          .map(e -> e.getKey() + " (" + e.getValue() + ")")
          .collect(Collectors.joining(", "));
      String topSurf = topEntries(ss.surfaceCounts, 5).stream()
          .map(e -> e.getKey() + " (" + e.getValue() + ")")
          .collect(Collectors.joining(", "));
      md.append(String.format(
          Locale.ROOT,
          "| %d | %,d | `%s` | `%s` | %s | %s |\n",
          lexRank,
          c,
          ss.mechanism.name(),
          ss.mesoSignature.replace("|", "\\|"),
          topLemmas.replace("|", "\\|"),
          topSurf.replace("|", "\\|")));
      if (lexRank >= 25) {
        break;
      }
    }
    md.append("\n");

    // Section 9: Top 30 Unambiguous Word Bigrams (2012 unambiguous_bigrams.txt equivalent)
    md.append("## 9. Top 30 Unambiguous Word Bigrams (`K = 1` + `K = 1`)\n\n");
    md.append("| Rank | Unambiguous Bigram | Occurrences |\n");
    md.append("| ---: | :--- | ---: |\n");
    List<Map.Entry<String, Long>> topBigrams = topEntries(unambiguousBigramCounts, 30);
    for (int i = 0; i < topBigrams.size(); i++) {
      Map.Entry<String, Long> e = topBigrams.get(i);
      md.append(String.format(Locale.ROOT, "| %d | `%s` | %,d |\n", i + 1, e.getKey(), e.getValue()));
    }

    Files.writeString(reportOut, md.toString(), StandardCharsets.UTF_8);

    // Export structured JSON catalog for downstream sentence harvesting & benchmarking
    if (jsonOut != null) {
      Map<String, Object> root = new LinkedHashMap<>();
      root.put("schema_version", "ZAS-v3");
      root.put("created_at", Instant.now().toString());
      root.put("input_dir", inputDir.toAbsolutePath().toString());
      root.put("sentences_profiled", totalSentences);
      root.put("words_profiled", totalWords);
      root.put("unambiguous_words", unambiguousWords);
      root.put("ambiguous_words", ambiguousWords);
      root.put("unique_meso_signatures", sortedGroups.size());

      List<Map<String, Object>> mechList = new ArrayList<>();
      for (MacroMechanism m : MacroMechanism.values()) {
        Map<String, Object> mm = new LinkedHashMap<>();
        long c = mechanismTokenCounts.get(m.ordinal());
        mm.put("mechanism", m.name());
        mm.put("description", m.description);
        mm.put("occurrences", c);
        mm.put("share_of_ambiguous", ambiguousWords > 0 ? (double) c / ambiguousWords : 0.0);
        mm.put("unique_signatures", mechSigCounts[m.ordinal()]);
        mechList.add(mm);
      }
      root.put("macro_mechanisms", mechList);

      List<Map<String, Object>> sigList = new ArrayList<>( limit);
      for (int i = 0; i < limit; i++) {
        SignatureStats ss = sortedGroups.get(i);
        long c = ss.occurrences.sum();
        double hLemma = computeEntropyBits(ss.lemmaCounts, c);
        double hFrame = computeEntropyBits(ss.contextFrameCounts, c);
        Map<String, Object> sm = new LinkedHashMap<>();
        sm.put("rank", i + 1);
        sm.put("meso_signature", ss.mesoSignature);
        sm.put("mechanism", ss.mechanism.name());
        sm.put("observability", ss.observability.name());
        sm.put("candidate_count", ss.candidateCount);
        sm.put("unique_items_per_token", ss.uniqueItems);
        sm.put("unique_last_groups_per_token", ss.uniqueLastGroups);
        sm.put("occurrences", c);
        sm.put("share_of_ambiguous", ambiguousWords > 0 ? (double) c / ambiguousWords : 0.0);
        sm.put("lemma_entropy_bits", Math.round(hLemma * 1000.0) / 1000.0);
        sm.put("context_frame_entropy_bits", Math.round(hFrame * 1000.0) / 1000.0);
        sm.put("regime", (ss.lemmaCounts.size() <= 25 || hLemma < 2.5) ? "CLOSED_LEXICAL" : "OPEN_PARADIGM");
        sm.put("top_surfaces", topEntriesMap(ss.surfaceCounts, 15));
        sm.put("top_lemmas", topEntriesMap(ss.lemmaCounts, 15));
        sm.put("top_context_frames", topEntriesMap(ss.contextFrameCounts, 15));
        sigList.add(sm);
      }
      root.put("top_signatures", sigList);
      root.put("top_unambiguous_bigrams", topEntriesMap(unambiguousBigramCounts, 100));

      Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
      try (BufferedWriter writer = Files.newBufferedWriter(jsonOut, StandardCharsets.UTF_8)) {
        gson.toJson(root, writer);
      }
    }
  }
}
