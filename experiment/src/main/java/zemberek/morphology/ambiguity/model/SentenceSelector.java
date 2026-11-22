package zemberek.morphology.ambiguity.model;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import zemberek.core.logging.Log;
import zemberek.core.text.TextUtil;
import zemberek.core.turkish.PrimaryPos;
import zemberek.core.turkish.RootAttribute;
import zemberek.core.turkish.SecondaryPos;
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
 * Stratified Curriculum Submodular Slicing ({@code SCSS-v1}) Sentence Selector for Turkish
 * Morphological Disambiguation.
 *
 * <p>Selects two strictly isolated, slice-structured datasets from {@code ~/data/turkish/refined}:
 *
 * <ol>
 *   <li><b>10,000-Sentence Held-Out Test Pool ({@code 10 slices x 1,000 sentences})</b>: {@code
 *       test_slices/test_slice_001.txt} .. {@code test_slice_010.txt} (plus per-slice {@code
 *       .meta.tsv} and {@code test_10k_ordered.txt}). Each 1,000-sentence test slice is a
 *       self-contained benchmark combining 700 Stratified Natural sentences and 300 Stratified
 *       Diagnostic Challenge sentences across all 9 {@code ZAS-v3} Macro-Mechanisms.
 *   <li><b>100,000-Sentence Master Training Pool ({@code 100 slices x 1,000 sentences})</b>: {@code
 *       train_slices/train_slice_001.txt} .. {@code train_slice_100.txt} (plus {@code
 *       train_100k_ordered.txt}), where every individual 1,000-sentence slice is a balanced,
 *       representative training set on its own, and any cumulative prefix of {@code k} slices
 *       maximizes submodular coverage of signatures, lemmas, and context frames.
 * </ol>
 */
public class SentenceSelector {

  private static final Locale TR = Locale.forLanguageTag("tr");
  private static final TurkishAlphabet ALPHABET = TurkishAlphabet.INSTANCE;

  @Parameter(
      names = {"-i", "--inputDir"},
      description = "Directory containing refined corpus files.")
  public Path inputDir = Paths.get("/home/dndara/data/turkish/refined");

  @Parameter(
      names = {"-o", "--outputDir"},
      description = "Output directory for selected test slices and training slices.")
  public Path outputDir = Paths.get("/home/dndara/data/turkish/selected_slices");

  @Parameter(
      names = {"-r", "--reportOut"},
      description = "Markdown report output file.")
  public Path reportOut =
      Paths.get("/home/dndara/work/zemberek-nlp/experiment/docs/model/sentence_selection_report.md");

  @Parameter(
      names = {"-j", "--jsonOut"},
      description = "JSON manifest output file.")
  public Path jsonOut =
      Paths.get(
          "/home/dndara/work/zemberek-nlp/experiment/docs/model/sentence_selection_manifest.json");

  @Parameter(
      names = {"-t", "--threads"},
      description = "Number of worker threads.")
  public int threads = 16;

  @Parameter(
      names = {"-m", "--maxLinesPerFile"},
      description = "Maximum candidate sentences to sample across each file (via 20 evenly-spaced file offsets).")
  public int maxLinesPerFile = 55000;

  @Parameter(
      names = {"--minWords"},
      description = "Minimum words per sentence (excluding punctuation).")
  public int minWords = 7;

  @Parameter(
      names = {"--maxWords"},
      description = "Maximum words per sentence (excluding punctuation).")
  public int maxWords = 15;

  @Parameter(
      names = {"--numTestSlices"},
      description = "Number of held-out test slices to generate (1,000 sentences each by default).")
  public int numTestSlices = 10;

  @Parameter(
      names = {"--numTrainSlices"},
      description = "Number of training slices to generate (1,000 sentences each by default).")
  public int numTrainSlices = 100;

  @Parameter(
      names = {"--sliceSize"},
      description = "Number of sentences per slice (for both test and training slices).")
  public int sliceSize = 1000;

  @Parameter(
      names = {"--testChallengeRatio"},
      description = "Fraction of each test slice dedicated to stratified diagnostic challenge sentences.")
  public double testChallengeRatio = 0.30;

  public enum MacroMechanism {
    M1_INFLECTIONAL_SYNCRETISM("Same root & derivation; suffix homophony (-I, -In, -lArI)"),
    M2_DERIVATIONAL_SHIFT("Same root; different IG count or derived POS (Participle/Inf/Zero)"),
    M3_LEXICAL_POS_POLYSEMY("Same lemma & stem; different lexical POS (Adj/Noun, Det/Pron)"),
    M4_PROPER_VS_COMMON("ProperNoun/Abbreviation vs Common word"),
    M5a_VOICE_LEXICALIZED("Stem-boundary split with identical final IG (F15/F17 blind)"),
    M5b_STEM_SUFFIX_COLLISION("Stem-boundary split with different final IGs"),
    M6a_HOMONYM_DIFF_POS("Same-length homonymous roots across different POS"),
    M6b_ORTHO_OR_COMPOUND_TWIN("Circumflex twins, CompoundP3sg twins, or vowel-drop twins"),
    M7_HYBRID_MULTI_LOCUS("Multi-locus ambiguity combining root/POS + inflectional syncretism");

    public final String description;

    MacroMechanism(String description) {
      this.description = description;
    }
  }

  public enum Observability {
    FULL_MORPHO_OBSERVABLE,
    PARTIAL_MORPHO_OBSERVABLE,
    LEXICAL_ONLY_OBSERVABLE
  }

  private static final int MAX_WORD_CACHE_SIZE = 400_000;
  private final ConcurrentHashMap<String, CachedWordInfo> wordCache =
      new ConcurrentHashMap<>(256_000);

  static final class CachedWordInfo {
    final boolean valid;
    final boolean ambiguous;
    final boolean certainAnchor;
    final String neighborAnchor;
    final String sigKey;
    final String lemmaKey;
    final byte mechanismOrd;
    final byte observabilityOrd;

    CachedWordInfo(
        boolean valid,
        boolean ambiguous,
        boolean certainAnchor,
        String neighborAnchor,
        String sigKey,
        String lemmaKey,
        byte mechanismOrd,
        byte observabilityOrd) {
      this.valid = valid;
      this.ambiguous = ambiguous;
      this.certainAnchor = certainAnchor;
      this.neighborAnchor = neighborAnchor;
      this.sigKey = sigKey;
      this.lemmaKey = lemmaKey;
      this.mechanismOrd = mechanismOrd;
      this.observabilityOrd = observabilityOrd;
    }
  }

  static final class RawAmbigToken {
    final String sigKey;
    final String lemmaKey;
    final String ctxKey;
    final byte mechanismOrd;
    final byte observabilityOrd;

    RawAmbigToken(
        String sigKey,
        String lemmaKey,
        String ctxKey,
        byte mechanismOrd,
        byte observabilityOrd) {
      this.sigKey = sigKey;
      this.lemmaKey = lemmaKey;
      this.ctxKey = ctxKey;
      this.mechanismOrd = mechanismOrd;
      this.observabilityOrd = observabilityOrd;
    }
  }

  static final class RawCandidate {
    final String text;
    final long normHash;
    final long[] shingles;
    final short domainId;
    final byte wordCount;
    final byte ambigCount;
    final byte certainAnchorCount;
    final RawAmbigToken[] ambigTokens;

    RawCandidate(
        String text,
        long normHash,
        long[] shingles,
        short domainId,
        byte wordCount,
        byte ambigCount,
        byte certainAnchorCount,
        RawAmbigToken[] ambigTokens) {
      this.text = text;
      this.normHash = normHash;
      this.shingles = shingles;
      this.domainId = domainId;
      this.wordCount = wordCount;
      this.ambigCount = ambigCount;
      this.certainAnchorCount = certainAnchorCount;
      this.ambigTokens = ambigTokens;
    }
  }

  static final class IndexedCandidate {
    final int id;
    final String text;
    final long normHash;
    final long[] shingles;
    final short domainId;
    final byte wordCount;
    final byte lengthBucket; // 0: 5..8, 1: 9..16, 2: 17..30
    final byte ambigCount;
    final byte dominantMechanism;
    final boolean hasLexicalOnly;
    final boolean hasPartialMorpho;
    final float lengthAndAnchorFactor;
    final float challengeScore;
    final int[] sigIds;
    final int[] lemmaIds;
    final int[] ctxIds;
    final byte[] mechOrds;
    final byte[] obsOrds;
    final float[] tokenMultipliers;

    double cachedScore;
    int scoreEpoch;
    boolean isChallengePick;

    IndexedCandidate(
        int id,
        String text,
        long normHash,
        long[] shingles,
        short domainId,
        byte wordCount,
        byte lengthBucket,
        byte ambigCount,
        byte dominantMechanism,
        boolean hasLexicalOnly,
        boolean hasPartialMorpho,
        float lengthAndAnchorFactor,
        float challengeScore,
        int[] sigIds,
        int[] lemmaIds,
        int[] ctxIds,
        byte[] mechOrds,
        byte[] obsOrds,
        float[] tokenMultipliers) {
      this.id = id;
      this.text = text;
      this.normHash = normHash;
      this.shingles = shingles;
      this.domainId = domainId;
      this.wordCount = wordCount;
      this.lengthBucket = lengthBucket;
      this.ambigCount = ambigCount;
      this.dominantMechanism = dominantMechanism;
      this.hasLexicalOnly = hasLexicalOnly;
      this.hasPartialMorpho = hasPartialMorpho;
      this.lengthAndAnchorFactor = lengthAndAnchorFactor;
      this.challengeScore = challengeScore;
      this.sigIds = sigIds;
      this.lemmaIds = lemmaIds;
      this.ctxIds = ctxIds;
      this.mechOrds = mechOrds;
      this.obsOrds = obsOrds;
      this.tokenMultipliers = tokenMultipliers;
    }
  }

  static final class CoverageStats {
    final int numSentences;
    final long totalWords;
    final long totalAmbigWords;
    final int uniqueSignatures;
    final double coveredSignatureMassPct;
    final int uniqueLemmaPairs;
    final int uniqueContextFrames;
    final long[] mechCounts = new long[MacroMechanism.values().length];
    final long[] obsCounts = new long[Observability.values().length];
    final int[] lengthBucketCounts = new int[3];
    final int activeDomains;

    CoverageStats(
        int numSentences,
        long totalWords,
        long totalAmbigWords,
        int uniqueSignatures,
        double coveredSignatureMassPct,
        int uniqueLemmaPairs,
        int uniqueContextFrames,
        long[] mechCounts,
        long[] obsCounts,
        int[] lengthBucketCounts,
        int activeDomains) {
      this.numSentences = numSentences;
      this.totalWords = totalWords;
      this.totalAmbigWords = totalAmbigWords;
      this.uniqueSignatures = uniqueSignatures;
      this.coveredSignatureMassPct = coveredSignatureMassPct;
      this.uniqueLemmaPairs = uniqueLemmaPairs;
      this.uniqueContextFrames = uniqueContextFrames;
      System.arraycopy(mechCounts, 0, this.mechCounts, 0, mechCounts.length);
      System.arraycopy(obsCounts, 0, this.obsCounts, 0, obsCounts.length);
      System.arraycopy(lengthBucketCounts, 0, this.lengthBucketCounts, 0, 3);
      this.activeDomains = activeDomains;
    }
  }

  public static void main(String[] args) throws Exception {
    SentenceSelector selector = new SentenceSelector();
    JCommander.newBuilder().addObject(selector).build().parse(args);
    selector.run();
  }

  public void run() throws Exception {
    long startMs = System.currentTimeMillis();
    if (!Files.exists(inputDir) || !Files.isDirectory(inputDir)) {
      throw new IllegalArgumentException("Input directory does not exist: " + inputDir);
    }

    List<Path> files;
    try (Stream<Path> stream = Files.list(inputDir)) {
      files =
          stream
              .filter(Files::isRegularFile)
              .filter(p -> p.getFileName().toString().endsWith(".txt"))
              .sorted(Comparator.comparing(p -> p.getFileName().toString()))
              .collect(Collectors.toList());
    }

    Log.info("Initializing shared RootLexicon, AnalysisCache & thread-local TurkishMorphology...");
    final RootLexicon sharedLexicon = RootLexicon.getDefault();
    final AnalysisCache sharedCache =
        AnalysisCache.builder().dynamicCacheSize(100_000, 1_000_000).build();
    final TurkishTokenizer tokenizer = TurkishTokenizer.DEFAULT;

    final ThreadLocal<TurkishMorphology> threadMorphology =
        ThreadLocal.withInitial(
            () ->
                TurkishMorphology.builder()
                    .setLexicon(sharedLexicon)
                    .setCache(sharedCache)
                    .setTokenizer(tokenizer)
                    .setAmbiguityResolver((sentence, analyses) -> null)
                    .build());

    Log.info(
        "Stage 0: Sampling and profiling candidate sentences across %d files (%d threads, max %d lines/file across 20 file offsets)...",
        files.size(), threads, maxLinesPerFile);

    List<String> domainNames =
        files.stream().map(p -> p.getFileName().toString()).collect(Collectors.toList());

    AtomicLong rawSampledLines = new AtomicLong();
    AtomicLong rejectedHygiene = new AtomicLong();
    AtomicLong rejectedAmbigBounds = new AtomicLong();
    ConcurrentHashMap<Long, Boolean> seenExactHashes = new ConcurrentHashMap<>(800_000);

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    List<Future<List<RawCandidate>>> futures = new ArrayList<>(files.size());

    for (int fIdx = 0; fIdx < files.size(); fIdx++) {
      final short domainId = (short) fIdx;
      final Path file = files.get(fIdx);
      futures.add(
          pool.submit(
              () ->
                  sampleFileCandidates(
                      file,
                      domainId,
                      threadMorphology.get(),
                      tokenizer,
                      rawSampledLines,
                      rejectedHygiene,
                      rejectedAmbigBounds,
                      seenExactHashes)));
    }

    List<RawCandidate> rawCandidates = new ArrayList<>(600_000);
    for (Future<List<RawCandidate>> fut : futures) {
      rawCandidates.addAll(fut.get());
    }

    long stage0Ms = System.currentTimeMillis() - startMs;
    Log.info(
        "Stage 0 complete in %.2f s: %d raw lines sampled -> %d clean candidates retained (hygiene/orthography rejected: %d, ambig-bounds rejected: %d).",
        stage0Ms / 1000.0,
        rawSampledLines.get(),
        rawCandidates.size(),
        rejectedHygiene.get(),
        rejectedAmbigBounds.get());

    // Build compact integer dictionaries for fast O(1) submodular scoring
    Map<String, Integer> sigToId = new HashMap<>(64_000);
    List<String> idToSig = new ArrayList<>(64_000);
    Map<String, Integer> lemmaToId = new HashMap<>(256_000);
    Map<String, Integer> ctxToId = new HashMap<>(512_000);

    for (RawCandidate rc : rawCandidates) {
      for (RawAmbigToken tok : rc.ambigTokens) {
        if (!sigToId.containsKey(tok.sigKey)) {
          sigToId.put(tok.sigKey, idToSig.size());
          idToSig.add(tok.sigKey);
        }
        lemmaToId.putIfAbsent(tok.lemmaKey, lemmaToId.size());
        ctxToId.putIfAbsent(tok.ctxKey, ctxToId.size());
      }
    }

    int numSigs = idToSig.size();
    int numLemmas = lemmaToId.size();
    int numCtxs = ctxToId.size();

    long[] corpusSigCounts = new long[numSigs];
    long[] corpusLemmaCounts = new long[numLemmas];
    long totalCandidateAmbigTokens = 0;

    for (RawCandidate rc : rawCandidates) {
      for (RawAmbigToken tok : rc.ambigTokens) {
        int sId = sigToId.get(tok.sigKey);
        int lId = lemmaToId.get(tok.lemmaKey);
        corpusSigCounts[sId]++;
        corpusLemmaCounts[lId]++;
        totalCandidateAmbigTokens++;
      }
    }

    // Precompute mild log-prior weights and per-slice saturation quotas
    float[] sigLogPrior = new float[numSigs];
    int[] sigSliceQuota = new int[numSigs];
    for (int s = 0; s < numSigs; s++) {
      long c = corpusSigCounts[s];
      sigLogPrior[s] = (float) (1.0 + 0.14 * Math.log(1.0 + c));
      double share = totalCandidateAmbigTokens > 0 ? (double) c / totalCandidateAmbigTokens : 0.0;
      if (share >= 0.005) {
        sigSliceQuota[s] = 15;
      } else if (share >= 0.001) {
        sigSliceQuota[s] = 25;
      } else {
        sigSliceQuota[s] = 40;
      }
    }

    // Convert RawCandidates into IndexedCandidates
    List<IndexedCandidate> allCandidates = new ArrayList<>(rawCandidates.size());
    int[] mechFreqTemp = new int[MacroMechanism.values().length];

    for (int i = 0; i < rawCandidates.size(); i++) {
      RawCandidate rc = rawCandidates.get(i);
      int m = rc.ambigTokens.length;
      int[] sIds = new int[m];
      int[] lIds = new int[m];
      int[] cIds = new int[m];
      byte[] mOrds = new byte[m];
      byte[] oOrds = new byte[m];
      float[] mults = new float[m];

      Arrays.fill(mechFreqTemp, 0);
      boolean hasLexOnly = false;
      boolean hasPartMorph = false;
      float challengeSum = 0.0f;

      for (int k = 0; k < m; k++) {
        RawAmbigToken rt = rc.ambigTokens[k];
        int sId = sigToId.get(rt.sigKey);
        int lId = lemmaToId.get(rt.lemmaKey);
        int cId = ctxToId.get(rt.ctxKey);
        sIds[k] = sId;
        lIds[k] = lId;
        cIds[k] = cId;
        mOrds[k] = rt.mechanismOrd;
        oOrds[k] = rt.observabilityOrd;
        mechFreqTemp[rt.mechanismOrd]++;

        float mult = 1.0f;
        if (rt.observabilityOrd == Observability.LEXICAL_ONLY_OBSERVABLE.ordinal()) {
          mult *= 1.55f;
          hasLexOnly = true;
          challengeSum += 2.2f;
        } else if (rt.observabilityOrd == Observability.PARTIAL_MORPHO_OBSERVABLE.ordinal()) {
          mult *= 1.25f;
          hasPartMorph = true;
          challengeSum += 1.4f;
        }

        MacroMechanism mech = MacroMechanism.values()[rt.mechanismOrd];
        if (mech == MacroMechanism.M5a_VOICE_LEXICALIZED
            || mech == MacroMechanism.M7_HYBRID_MULTI_LOCUS
            || mech == MacroMechanism.M6a_HOMONYM_DIFF_POS
            || mech == MacroMechanism.M6b_ORTHO_OR_COMPOUND_TWIN) {
          mult *= 1.30f;
          challengeSum += 1.6f;
        } else if (mech == MacroMechanism.M1_INFLECTIONAL_SYNCRETISM
            || mech == MacroMechanism.M2_DERIVATIONAL_SHIFT) {
          mult *= 1.20f;
          challengeSum += 1.2f;
        } else if (mech == MacroMechanism.M5b_STEM_SUFFIX_COLLISION) {
          mult *= 1.10f;
          challengeSum += 0.9f;
        }

        mults[k] = mult * sigLogPrior[sId];
      }

      // Dominant non-trivial mechanism for challenge stratification
      byte domMech = 0;
      int bestCount = -1;
      int[] priorityOrder = {
        MacroMechanism.M5a_VOICE_LEXICALIZED.ordinal(),
        MacroMechanism.M6b_ORTHO_OR_COMPOUND_TWIN.ordinal(),
        MacroMechanism.M7_HYBRID_MULTI_LOCUS.ordinal(),
        MacroMechanism.M6a_HOMONYM_DIFF_POS.ordinal(),
        MacroMechanism.M2_DERIVATIONAL_SHIFT.ordinal(),
        MacroMechanism.M1_INFLECTIONAL_SYNCRETISM.ordinal(),
        MacroMechanism.M5b_STEM_SUFFIX_COLLISION.ordinal(),
        MacroMechanism.M4_PROPER_VS_COMMON.ordinal(),
        MacroMechanism.M3_LEXICAL_POS_POLYSEMY.ordinal()
      };
      for (int ord : priorityOrder) {
        if (mechFreqTemp[ord] > bestCount) {
          bestCount = mechFreqTemp[ord];
          domMech = (byte) ord;
        }
      }

      byte lenBucket = lengthBucket(rc.wordCount);
      float anchorRatio =
          rc.ambigCount > 0 ? (float) rc.certainAnchorCount / (2.0f * rc.ambigCount) : 0.5f;
      float anchorBonus = (anchorRatio >= 0.45f && anchorRatio <= 0.92f) ? 1.12f : 0.95f;
      // Normalize by both ambigCount^0.55 and wordCount^0.35 so sentences with natural ambiguity
      // density (~65%-75%) and genuine K=1 anchors are preferred over 100%-ambiguous sentences
      float normDenom =
          (float) (Math.pow(Math.max(2, rc.ambigCount), 0.55) * Math.pow(Math.max(5, rc.wordCount), 0.35));
      float lengthAndAnchorFactor = anchorBonus / normDenom;

      allCandidates.add(
          new IndexedCandidate(
              i,
              rc.text,
              rc.normHash,
              rc.shingles,
              rc.domainId,
              rc.wordCount,
              lenBucket,
              rc.ambigCount,
              domMech,
              hasLexOnly,
              hasPartMorph,
              lengthAndAnchorFactor,
              challengeSum * lengthAndAnchorFactor,
              sIds,
              lIds,
              cIds,
              mOrds,
              oOrds,
              mults));
    }
    rawCandidates.clear();

    Log.info(
        "Indexed %d candidate sentences (%d unique Meso-Signatures, %d unique (Sig,Lemma) pairs, %d unique (Sig,ContextFrame) pairs).",
        allCandidates.size(), numSigs, numLemmas, numCtxs);

    BitSet usedCandidates = new BitSet(allCandidates.size());
    Set<Long> evalExcludedShingles = new HashSet<>(512_000);
    Set<Long> usedTrainShingles = new HashSet<>(2_000_000);

    // =========================================================================
    // STAGE 1: Held-Out Test Slices Selection (10 x 1,000 = 10,000 sentences)
    // =========================================================================
    Log.info(
        "Stage 1: Selecting %d Held-Out Test Slices of %d sentences each (%,d total test sentences)...",
        numTestSlices, sliceSize, numTestSlices * sliceSize);

    List<List<IndexedCandidate>> testSlices =
        selectHeldOutTestSlices(
            allCandidates,
            usedCandidates,
            evalExcludedShingles,
            files.size(),
            numSigs,
            numLemmas,
            numTestSlices,
            sliceSize,
            testChallengeRatio);

    List<IndexedCandidate> allSelectedTest = new ArrayList<>(numTestSlices * sliceSize);
    List<IndexedCandidate> allTestNatural = new ArrayList<>();
    List<IndexedCandidate> allTestChallenge = new ArrayList<>();
    for (List<IndexedCandidate> ts : testSlices) {
      for (IndexedCandidate c : ts) {
        allSelectedTest.add(c);
        if (c.isChallengePick) {
          allTestChallenge.add(c);
        } else {
          allTestNatural.add(c);
        }
      }
    }

    Log.info(
        "Stage 1 complete: %,d test sentences across %d slices (Natural=%d, Challenge=%d; %,d 4-gram shingles locked in firewall).",
        allSelectedTest.size(),
        testSlices.size(),
        allTestNatural.size(),
        allTestChallenge.size(),
        evalExcludedShingles.size());

    // Filter out any remaining training candidates that overlap with Test 4-gram shingles
    int leakedFiltered = 0;
    for (IndexedCandidate cand : allCandidates) {
      if (usedCandidates.get(cand.id)) {
        continue;
      }
      if (hasEvalShingleLeak(cand.shingles, evalExcludedShingles)) {
        usedCandidates.set(cand.id);
        leakedFiltered++;
      }
    }
    Log.info(
        "Excluded %,d training candidates due to 4-gram shingle overlap with the Held-Out Test Pool.",
        leakedFiltered);

    // =========================================================================
    // STAGE 2: Stratified Curriculum Submodular Slicing (100 x 1,000 = 100,000)
    // =========================================================================
    Log.info(
        "Stage 2: Running Stratified Curriculum Submodular Slicing (SCSS-v1) for %d training slices of %d sentences...",
        numTrainSlices, sliceSize);

    int[] globalSigCounts = new int[numSigs];
    int[] globalLemmaCounts = new int[numLemmas];
    int[] globalCtxCounts = new int[numCtxs];
    int[] localSigCounts = new int[numSigs];

    // Partition eligible training candidates by length bucket (0: Compact 7-9w, 1: Standard 10-12w, 2: Extended 13-15w)
    @SuppressWarnings("unchecked")
    List<IndexedCandidate>[] bucketPools = new List[3];
    for (int b = 0; b < 3; b++) {
      bucketPools[b] = new ArrayList<>(250_000);
    }
    for (IndexedCandidate cand : allCandidates) {
      if (!usedCandidates.get(cand.id)) {
        bucketPools[cand.lengthBucket].add(cand);
      }
    }
    Log.info(
        "Eligible training candidate pools by length bucket: Compact(7-9w)=%,d, Standard(10-12w)=%,d, Extended(13-15w)=%,d",
        bucketPools[0].size(), bucketPools[1].size(), bucketPools[2].size());

    List<List<IndexedCandidate>> trainSlices = new ArrayList<>(numTrainSlices);
    List<IndexedCandidate> allSelectedTrain = new ArrayList<>(numTrainSlices * sliceSize);

    // 20-sentence micro-cycle template: 7 Compact (35%), 8 Standard (40%), 5 Extended (25%)
    int[] microCycleBuckets = {
      1, 0, 2, 1, 0,
      1, 2, 0, 1, 0,
      2, 1, 0, 1, 2,
      0, 1, 2, 0, 1
    };

    int maxDomainPerSlice = Math.max(40, (int) Math.ceil(sliceSize * 0.06)); // 6% cap per domain

    Comparator<IndexedCandidate> pqComparator =
        (a, b) -> Double.compare(b.cachedScore, a.cachedScore);

    @SuppressWarnings("unchecked")
    PriorityQueue<IndexedCandidate>[] bucketPQs = new PriorityQueue[3];
    for (int b = 0; b < 3; b++) {
      bucketPQs[b] = new PriorityQueue<>(Math.max(1000, bucketPools[b].size()), pqComparator);
    }

    int globalEpoch = 1;
    for (int sIdx = 0; sIdx < numTrainSlices; sIdx++) {
      Arrays.fill(localSigCounts, 0);
      int[] domainCountsInSlice = new int[files.size()];
      final int sliceStartEpoch = globalEpoch;

      // Parallel score refresh at the start of slice sIdx across all remaining candidates
      List<Callable<Void>> scoreTasks = new ArrayList<>(threads * 3);
      for (int b = 0; b < 3; b++) {
        final List<IndexedCandidate> poolB = bucketPools[b];
        int chunkSize = Math.max(1, (poolB.size() + threads - 1) / threads);
        for (int start = 0; start < poolB.size(); start += chunkSize) {
          final int s = start;
          final int e = Math.min(poolB.size(), start + chunkSize);
          scoreTasks.add(
              () -> {
                for (int idx = s; idx < e; idx++) {
                  IndexedCandidate c = poolB.get(idx);
                  if (!usedCandidates.get(c.id)) {
                    c.cachedScore =
                        computeSubmodularScore(
                            c,
                            globalSigCounts,
                            globalLemmaCounts,
                            globalCtxCounts,
                            localSigCounts,
                            sigSliceQuota);
                    c.scoreEpoch = sliceStartEpoch;
                  }
                }
                return null;
              });
        }
      }
      pool.invokeAll(scoreTasks);

      // Rebuild PriorityQueues for the 3 length buckets
      for (int b = 0; b < 3; b++) {
        bucketPQs[b].clear();
        List<IndexedCandidate> survivors = new ArrayList<>(bucketPools[b].size());
        for (IndexedCandidate c : bucketPools[b]) {
          if (!usedCandidates.get(c.id)) {
            survivors.add(c);
            bucketPQs[b].add(c);
          }
        }
        bucketPools[b] = survivors;
      }

      List<IndexedCandidate> sliceList = new ArrayList<>(sliceSize);
      int step = 0;
      while (sliceList.size() < sliceSize) {
        int targetBucket = microCycleBuckets[step % microCycleBuckets.length];
        step++;

        IndexedCandidate chosen =
            popLazyGreedyBest(
                bucketPQs[targetBucket],
                usedCandidates,
                usedTrainShingles,
                domainCountsInSlice,
                maxDomainPerSlice,
                globalSigCounts,
                globalLemmaCounts,
                globalCtxCounts,
                localSigCounts,
                sigSliceQuota,
                globalEpoch);

        if (chosen == null) {
          // Fallback to any non-empty bucket if targetBucket is exhausted
          for (int alt = 0; alt < 3; alt++) {
            chosen =
                popLazyGreedyBest(
                    bucketPQs[alt],
                    usedCandidates,
                    usedTrainShingles,
                    domainCountsInSlice,
                    files.size() * sliceSize,
                    globalSigCounts,
                    globalLemmaCounts,
                    globalCtxCounts,
                    localSigCounts,
                    sigSliceQuota,
                    globalEpoch);
            if (chosen != null) {
              break;
            }
          }
        }

        if (chosen == null) {
          Log.warn("Candidate pool exhausted at slice %d, size %d!", sIdx + 1, sliceList.size());
          break;
        }

        // Commit chosen candidate
        usedCandidates.set(chosen.id);
        domainCountsInSlice[chosen.domainId]++;
        for (long sh : chosen.shingles) {
          usedTrainShingles.add(sh);
        }
        for (int k = 0; k < chosen.sigIds.length; k++) {
          int sId = chosen.sigIds[k];
          globalSigCounts[sId]++;
          localSigCounts[sId]++;
          globalLemmaCounts[chosen.lemmaIds[k]]++;
          globalCtxCounts[chosen.ctxIds[k]]++;
        }
        sliceList.add(chosen);
        allSelectedTrain.add(chosen);
        globalEpoch++;
      }

      trainSlices.add(sliceList);
      if ((sIdx + 1) % 10 == 0 || sIdx == 0 || sIdx == numTrainSlices - 1) {
        Log.info(
            "  Completed Training Slice %03d/%03d (%,d total training sentences selected)",
            sIdx + 1, numTrainSlices, allSelectedTrain.size());
      }
    }

    pool.shutdown();

    // =========================================================================
    // STAGE 3: Write Selected Files & Generate Comprehensive Report
    // =========================================================================
    writeOutputDatasets(domainNames, testSlices, allSelectedTest, trainSlices, allSelectedTrain);

    long elapsedMs = System.currentTimeMillis() - startMs;
    generateReports(
        elapsedMs,
        domainNames,
        allCandidates,
        testSlices,
        allSelectedTest,
        allTestNatural,
        allTestChallenge,
        trainSlices,
        allSelectedTrain,
        corpusSigCounts,
        totalCandidateAmbigTokens,
        numSigs,
        numLemmas,
        numCtxs);

    Log.info("Sentence selection finished in %.2f s.", elapsedMs / 1000.0);
    Log.info("Output directory : %s", outputDir.toAbsolutePath());
    Log.info("Markdown report  : %s", reportOut.toAbsolutePath());
    Log.info("JSON manifest    : %s", jsonOut.toAbsolutePath());
  }

  private IndexedCandidate popLazyGreedyBest(
      PriorityQueue<IndexedCandidate> pq,
      BitSet usedCandidates,
      Set<Long> usedTrainShingles,
      int[] domainCountsInSlice,
      int maxDomainPerSlice,
      int[] globalSigCounts,
      int[] globalLemmaCounts,
      int[] globalCtxCounts,
      int[] localSigCounts,
      int[] sigSliceQuota,
      int currentEpoch) {

    while (!pq.isEmpty()) {
      IndexedCandidate top = pq.poll();
      if (usedCandidates.get(top.id)) {
        continue;
      }
      // Once a domain hits its cap for the current slice, drop it from the current slice's PQ.
      // (It remains in bucketPools and will be re-queued at the start of the next slice.)
      if (domainCountsInSlice[top.domainId] >= maxDomainPerSlice) {
        continue;
      }
      if (isNearDuplicate(top.shingles, usedTrainShingles)) {
        usedCandidates.set(top.id);
        continue;
      }

      if (top.scoreEpoch == currentEpoch) {
        return top;
      }

      double exact =
          computeSubmodularScore(
              top,
              globalSigCounts,
              globalLemmaCounts,
              globalCtxCounts,
              localSigCounts,
              sigSliceQuota);
      top.cachedScore = exact;
      top.scoreEpoch = currentEpoch;

      if (pq.isEmpty() || exact >= pq.peek().cachedScore) {
        return top;
      } else {
        pq.add(top);
      }
    }
    return null;
  }

  private static double computeSubmodularScore(
      IndexedCandidate c,
      int[] globalSigCounts,
      int[] globalLemmaCounts,
      int[] globalCtxCounts,
      int[] localSigCounts,
      int[] sigSliceQuota) {
    double sumGain = 0.0;
    int m = c.sigIds.length;
    for (int k = 0; k < m; k++) {
      int sId = c.sigIds[k];
      int localC = localSigCounts[sId];
      int quota = sigSliceQuota[sId];
      // Once a signature hits its per-slice quota, its selection reward drops by 95%
      float quotaFactor = localC < quota ? 1.0f : 0.05f;

      double sigTerm = 3.0 / (1.0 + globalSigCounts[sId]);
      double lemTerm = 2.0 / (1.0 + globalLemmaCounts[c.lemmaIds[k]]);
      double ctxTerm = 1.5 / (1.0 + globalCtxCounts[c.ctxIds[k]]);
      double localTerm = 1.2 / (1.0 + localC);

      sumGain += quotaFactor * c.tokenMultipliers[k] * (sigTerm + lemTerm + ctxTerm + localTerm);
    }
    return sumGain * c.lengthAndAnchorFactor;
  }

  private List<List<IndexedCandidate>> selectHeldOutTestSlices(
      List<IndexedCandidate> allCandidates,
      BitSet usedCandidates,
      Set<Long> evalExcludedShingles,
      int numDomains,
      int numSigs,
      int numLemmas,
      int numSlices,
      int sliceSize,
      double challengeRatio) {

    // 1. Prepare Domain-Stratified Pools for Natural picks
    @SuppressWarnings("unchecked")
    List<IndexedCandidate>[] byDomain = new List[numDomains];
    for (int d = 0; d < numDomains; d++) {
      byDomain[d] = new ArrayList<>();
    }
    for (IndexedCandidate c : allCandidates) {
      if (!usedCandidates.get(c.id)) {
        byDomain[c.domainId].add(c);
      }
    }
    Random rng = new Random(42L);
    for (int d = 0; d < numDomains; d++) {
      Collections.shuffle(byDomain[d], rng);
    }
    int[] domainCursors = new int[numDomains];

    // 2. Prepare Mechanism-Stratified Pools for Challenge picks
    int numMechs = MacroMechanism.values().length;
    @SuppressWarnings("unchecked")
    List<IndexedCandidate>[] byMech = new List[numMechs];
    for (int m = 0; m < numMechs; m++) {
      byMech[m] = new ArrayList<>();
    }
    for (IndexedCandidate c : allCandidates) {
      if (!usedCandidates.get(c.id)) {
        byMech[c.dominantMechanism].add(c);
      }
    }
    for (int m = 0; m < numMechs; m++) {
      byMech[m].sort((a, b) -> Float.compare(b.challengeScore, a.challengeScore));
    }
    int[] mechCursors = new int[numMechs];
    int[] evalSigCounts = new int[numSigs];
    int[] evalLemmaCounts = new int[numLemmas];

    int challengePerSlice = (int) Math.round(sliceSize * challengeRatio);
    int naturalPerSlice = sliceSize - challengePerSlice;
    int maxDomainPerSlice = Math.max(25, (int) Math.ceil(sliceSize * 0.06));

    List<List<IndexedCandidate>> testSlices = new ArrayList<>(numSlices);
    int dCursor = 0;
    int mCursor = 0;

    for (int s = 0; s < numSlices; s++) {
      int[] domainCountsInSlice = new int[numDomains];
      List<IndexedCandidate> natList = new ArrayList<>(naturalPerSlice);
      List<IndexedCandidate> chalList = new ArrayList<>(challengePerSlice);

      // Select challengePerSlice diagnostic challenge sentences first
      int emptyMechCycles = 0;
      while (chalList.size() < challengePerSlice && emptyMechCycles < numMechs * 2) {
        List<IndexedCandidate> pool = byMech[mCursor];
        IndexedCandidate bestPick = null;
        double bestScore = -1.0;
        int bestIdx = -1;

        int startIdx = mechCursors[mCursor];
        int scanned = 0;
        for (int idx = startIdx; idx < pool.size() && scanned < 120; idx++) {
          IndexedCandidate cand = pool.get(idx);
          if (usedCandidates.get(cand.id)) {
            if (idx == mechCursors[mCursor]) {
              mechCursors[mCursor]++;
            }
            continue;
          }
          if (domainCountsInSlice[cand.domainId] >= maxDomainPerSlice) {
            continue;
          }
          if (isNearDuplicate(cand.shingles, evalExcludedShingles)) {
            usedCandidates.set(cand.id);
            continue;
          }
          scanned++;

          double novelty = 0.0;
          for (int k = 0; k < cand.sigIds.length; k++) {
            novelty +=
                (2.0 / (1.0 + evalSigCounts[cand.sigIds[k]]))
                    + (1.5 / (1.0 + evalLemmaCounts[cand.lemmaIds[k]]));
          }
          double score = cand.challengeScore * (1.0 + 0.35 * novelty / cand.sigIds.length);
          if (score > bestScore) {
            bestScore = score;
            bestPick = cand;
            bestIdx = idx;
          }
        }

        if (bestPick != null) {
          if (bestIdx == mechCursors[mCursor]) {
            mechCursors[mCursor]++;
          }
          bestPick.isChallengePick = true;
          usedCandidates.set(bestPick.id);
          domainCountsInSlice[bestPick.domainId]++;
          for (long sh : bestPick.shingles) {
            evalExcludedShingles.add(sh);
          }
          for (int k = 0; k < bestPick.sigIds.length; k++) {
            evalSigCounts[bestPick.sigIds[k]]++;
            evalLemmaCounts[bestPick.lemmaIds[k]]++;
          }
          chalList.add(bestPick);
          emptyMechCycles = 0;
        } else {
          emptyMechCycles++;
        }
        mCursor = (mCursor + 1) % numMechs;
      }

      // Select naturalPerSlice stratified natural sentences
      int emptyDomPasses = 0;
      while (natList.size() < naturalPerSlice && emptyDomPasses < numDomains * 2) {
        List<IndexedCandidate> pool = byDomain[dCursor];
        boolean found = false;
        while (domainCursors[dCursor] < pool.size()) {
          IndexedCandidate cand = pool.get(domainCursors[dCursor]++);
          if (usedCandidates.get(cand.id)) {
            continue;
          }
          if (isNearDuplicate(cand.shingles, evalExcludedShingles)) {
            continue;
          }
          cand.isChallengePick = false;
          usedCandidates.set(cand.id);
          domainCountsInSlice[cand.domainId]++;
          for (long sh : cand.shingles) {
            evalExcludedShingles.add(sh);
          }
          for (int k = 0; k < cand.sigIds.length; k++) {
            evalSigCounts[cand.sigIds[k]]++;
            evalLemmaCounts[cand.lemmaIds[k]]++;
          }
          natList.add(cand);
          found = true;
          break;
        }
        if (!found) {
          emptyDomPasses++;
        } else {
          emptyDomPasses = 0;
        }
        dCursor = (dCursor + 1) % numDomains;
      }

      // Interleave Natural and Challenge sentences cleanly (e.g. 7 Natural, 3 Challenge per 10)
      List<IndexedCandidate> sliceCombined = new ArrayList<>(sliceSize);
      int nIdx = 0;
      int cIdx = 0;
      for (int pos = 0; pos < sliceSize; pos++) {
        boolean pickChal = ((pos % 10) == 2 || (pos % 10) == 5 || (pos % 10) == 8);
        if (pickChal && cIdx < chalList.size()) {
          sliceCombined.add(chalList.get(cIdx++));
        } else if (nIdx < natList.size()) {
          sliceCombined.add(natList.get(nIdx++));
        } else if (cIdx < chalList.size()) {
          sliceCombined.add(chalList.get(cIdx++));
        }
      }
      testSlices.add(sliceCombined);
    }

    return testSlices;
  }

  private List<RawCandidate> sampleFileCandidates(
      Path file,
      short domainId,
      TurkishMorphology morphology,
      TurkishTokenizer tokenizer,
      AtomicLong rawSampledLines,
      AtomicLong rejectedHygiene,
      AtomicLong rejectedAmbigBounds,
      ConcurrentHashMap<Long, Boolean> seenExactHashes) {

    List<RawCandidate> out = new ArrayList<>(maxLinesPerFile);
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      long fileLen = channel.size();
      if (fileLen == 0) {
        return out;
      }
      int numSlices = 20;
      int linesPerSlice = Math.max(1, maxLinesPerFile / numSlices);

      for (int s = 0; s < numSlices; s++) {
        long startOffset = (fileLen * s) / numSlices;
        channel.position(startOffset);
        CharsetDecoder decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        BufferedReader reader =
            new BufferedReader(Channels.newReader(channel, decoder, 65536), 65536);
        if (s > 0) {
          // Advance to next newline boundary so we don't start mid-line (or mid-UTF8 char)
          reader.readLine();
        }

        int readInSlice = 0;
        while (readInSlice < linesPerSlice) {
          String rawLine = reader.readLine();
          if (rawLine == null) {
            break;
          }
          String line = TextUtil.normalizeQuotesHyphens(rawLine.trim());
          if (line.isEmpty()) {
            continue;
          }
          readInSlice++;
          rawSampledLines.incrementAndGet();

          RawCandidate cand =
              evaluateCandidateLine(
                  line,
                  domainId,
                  morphology,
                  tokenizer,
                  rejectedHygiene,
                  rejectedAmbigBounds,
                  seenExactHashes);
          if (cand != null) {
            out.add(cand);
          }
        }
      }
    } catch (IOException e) {
      Log.error("Failed reading %s: %s", file, e.getMessage());
    }
    return out;
  }

  private RawCandidate evaluateCandidateLine(
      String line,
      short domainId,
      TurkishMorphology morphology,
      TurkishTokenizer tokenizer,
      AtomicLong rejectedHygiene,
      AtomicLong rejectedAmbigBounds,
      ConcurrentHashMap<Long, Boolean> seenExactHashes) {

    // 1. Clean sentence orthography, parenthesis & emoji rejection check
    int charLen = line.length();
    if (charLen < 25 || charLen > 200) {
      rejectedHygiene.incrementAndGet();
      return null;
    }
    char firstChar = line.charAt(0);
    char lastChar = line.charAt(charLen - 1);
    // Must start with an uppercase letter (eliminates numbered list items like "3. aile...")
    // and end with clean terminal punctuation (., ?, !)
    if (!Character.isLetter(firstChar)
        || !Character.isUpperCase(firstChar)
        || (lastChar != '.' && lastChar != '?' && lastChar != '!')) {
      rejectedHygiene.incrementAndGet();
      return null;
    }
    if (!isCleanTurkishSentenceChars(line)) {
      rejectedHygiene.incrementAndGet();
      return null;
    }
    if (line.contains("..")
        || line.contains("http")
        || line.contains("www.")
        || line.contains(" vs.")
        || line.contains(" vb.")
        || line.contains(":)")
        || line.contains(":(")
        || line.contains(";)")
        || line.contains(":D")
        || line.contains(":-)")
        || line.contains(":-(")) {
      rejectedHygiene.incrementAndGet();
      return null;
    }

    List<Token> tokens = tokenizer.tokenize(line);
    List<String> words = new ArrayList<>(tokens.size());
    int digitHeavyCount = 0;
    int singleCharWords = 0;

    for (Token tok : tokens) {
      if (tok.getType() == Token.Type.Punctuation) {
        continue;
      }
      String c = tok.getText();
      if (c.isEmpty()) {
        continue;
      }
      words.add(c);
      if (c.length() == 1 && !c.equalsIgnoreCase("o")) {
        singleCharWords++;
      }
      int digits = 0;
      for (int i = 0; i < c.length(); i++) {
        if (Character.isDigit(c.charAt(i))) {
          digits++;
        }
      }
      if (digits > 0) {
        digitHeavyCount++;
      }
    }

    int n = words.size();
    if (n < minWords || n > maxWords) {
      rejectedHygiene.incrementAndGet();
      return null;
    }
    if (digitHeavyCount * 4 > n || singleCharWords > 2) {
      rejectedHygiene.incrementAndGet();
      return null;
    }

    // Check token repetition & compute normalized hash + 4-gram word shingles
    String[] lowerWords = new String[n];
    Map<String, Integer> wordFreq = new HashMap<>(n * 2);
    long normHash = 0xcbf29ce484222325L;
    for (int i = 0; i < n; i++) {
      String lw = words.get(i).toLowerCase(TR);
      lowerWords[i] = lw;
      int f = wordFreq.merge(lw, 1, Integer::sum);
      if (f >= 3) {
        rejectedHygiene.incrementAndGet();
        return null;
      }
      for (int cIdx = 0; cIdx < lw.length(); cIdx++) {
        normHash ^= lw.charAt(cIdx);
        normHash *= 0x100000001b3L;
      }
      normHash ^= 0xFFL;
      normHash *= 0x100000001b3L;
    }

    if (seenExactHashes.putIfAbsent(normHash, Boolean.TRUE) != null) {
      rejectedHygiene.incrementAndGet();
      return null;
    }

    // Analyze all words using lock-free wordCache
    CachedWordInfo[] wordInfos = new CachedWordInfo[n];
    int ambigCount = 0;
    for (int i = 0; i < n; i++) {
      String surface = words.get(i);
      CachedWordInfo info = wordCache.get(surface);
      if (info == null) {
        info = computeCachedWordInfo(morphology.analyze(surface));
        if (wordCache.size() < MAX_WORD_CACHE_SIZE) {
          CachedWordInfo prev = wordCache.putIfAbsent(surface, info);
          if (prev != null) {
            info = prev;
          }
        }
      }
      if (!info.valid) {
        rejectedHygiene.incrementAndGet();
        return null;
      }
      wordInfos[i] = info;
      if (info.ambiguous) {
        ambigCount++;
      }
    }

    // Require at least 2 ambiguous words AND at least 1 unambiguous (K=1) word
    if (ambigCount < 2 || ambigCount >= n) {
      rejectedAmbigBounds.incrementAndGet();
      return null;
    }

    RawAmbigToken[] ambigTokens = new RawAmbigToken[ambigCount];
    int aIdx = 0;
    int certainAnchorsNextToAmbig = 0;

    for (int i = 0; i < n; i++) {
      CachedWordInfo info = wordInfos[i];
      if (!info.ambiguous) {
        continue;
      }
      String leftAnchor = (i == 0) ? "<S>" : wordInfos[i - 1].neighborAnchor;
      String rightAnchor = (i == n - 1) ? "</S>" : wordInfos[i + 1].neighborAnchor;
      if (i == 0 || wordInfos[i - 1].certainAnchor) {
        certainAnchorsNextToAmbig++;
      }
      if (i == n - 1 || wordInfos[i + 1].certainAnchor) {
        certainAnchorsNextToAmbig++;
      }
      String ctxKey = info.sigKey + "##" + leftAnchor + "__" + rightAnchor;
      ambigTokens[aIdx++] =
          new RawAmbigToken(
              info.sigKey, info.lemmaKey, ctxKey, info.mechanismOrd, info.observabilityOrd);
    }

    // Compute 4-gram word shingles for near-duplicate & train/test isolation
    int numShingles = Math.max(1, n - 3);
    long[] shingles = new long[numShingles];
    for (int i = 0; i < numShingles; i++) {
      long h = 0xcbf29ce484222325L;
      int end = Math.min(n, i + 4);
      for (int j = i; j < end; j++) {
        String w = lowerWords[j];
        for (int cIdx = 0; cIdx < w.length(); cIdx++) {
          h ^= w.charAt(cIdx);
          h *= 0x100000001b3L;
        }
        h ^= 0x7FL;
        h *= 0x100000001b3L;
      }
      shingles[i] = h;
    }

    return new RawCandidate(
        line,
        normHash,
        shingles,
        domainId,
        (byte) n,
        (byte) ambigCount,
        (byte) certainAnchorsNextToAmbig,
        ambigTokens);
  }

  private static CachedWordInfo computeCachedWordInfo(WordAnalysis wa) {
    if (!wa.isCorrect()) {
      return new CachedWordInfo(false, false, false, "UNK", null, null, (byte) 0, (byte) 0);
    }
    List<SingleAnalysis> results = wa.getAnalysisResults();
    int K = results.size();
    if (K == 0 || results.get(0).isUnknown()) {
      return new CachedWordInfo(false, false, false, "UNK", null, null, (byte) 0, (byte) 0);
    }
    if (K == 1) {
      String anchor = "U:" + conciseNeighborTag(results.get(0));
      return new CachedWordInfo(true, false, true, anchor, null, null, (byte) 0, (byte) 0);
    }

    Set<String> fullTags = new TreeSet<>();
    Set<String> posSet = new TreeSet<>();
    for (SingleAnalysis sa : results) {
      fullTags.add(conciseNeighborTag(sa));
      posSet.add(finalPosTag(sa));
    }
    String neighborAnchor;
    boolean certainAnchor;
    if (fullTags.size() == 1) {
      neighborAnchor = "C:" + fullTags.iterator().next();
      certainAnchor = true;
    } else if (posSet.size() == 1) {
      neighborAnchor = "C:" + posSet.iterator().next();
      certainAnchor = true;
    } else {
      neighborAnchor = "A:" + String.join("/", posSet);
      certainAnchor = false;
    }

    RawAmbigToken rat = buildRawAmbigToken(results, "");
    return new CachedWordInfo(
        true,
        true,
        certainAnchor,
        neighborAnchor,
        rat.sigKey,
        rat.lemmaKey,
        rat.mechanismOrd,
        rat.observabilityOrd);
  }

  private static RawAmbigToken buildRawAmbigToken(
      List<SingleAnalysis> results, String contextFrame) {
    int K = results.size();
    Set<DictionaryItem> itemSet = new HashSet<>(K * 2);
    Set<String> lemmaSet = new TreeSet<>();
    Set<String> normLemmaSet = new HashSet<>(K * 2);
    Set<PrimaryPos> rootPosSet = new HashSet<>(K * 2);
    Set<String> posPairSet = new HashSet<>(K * 2);
    Set<Integer> stemLenSet = new HashSet<>(K * 2);
    Set<Integer> groupCountSet = new HashSet<>(K * 2);
    Set<String> derivChainSet = new HashSet<>(K * 2);
    Set<String> lastGroupSet = new HashSet<>(K * 2);

    boolean hasProperOrAbbrv = false;
    boolean hasCommon = false;
    boolean hasCompoundP3sgAttr = false;
    int maxStemLen = 0;

    for (SingleAnalysis sa : results) {
      DictionaryItem item = sa.getDictionaryItem();
      itemSet.add(item);
      lemmaSet.add(item.lemma);
      String normLemma =
          ALPHABET.normalizeCircumflex(item.lemma.toLowerCase(TR)).replace(".", "");
      normLemmaSet.add(normLemma);
      rootPosSet.add(item.primaryPos);
      posPairSet.add(item.primaryPos.shortForm + ":" + item.secondaryPos.shortForm);

      if (item.secondaryPos == SecondaryPos.ProperNoun
          || item.secondaryPos == SecondaryPos.Abbreviation) {
        hasProperOrAbbrv = true;
      } else {
        hasCommon = true;
      }
      if (item.hasAttribute(RootAttribute.CompoundP3sg)
          || item.hasAttribute(RootAttribute.CompoundP3sgRoot)) {
        hasCompoundP3sgAttr = true;
      }

      int stemLen = sa.getStem().length();
      stemLenSet.add(stemLen);
      if (stemLen > maxStemLen) {
        maxStemLen = stemLen;
      }

      int gCount = sa.groupCount();
      groupCountSet.add(gCount);
      StringBuilder dChain = new StringBuilder(16);
      for (int g = 0; g < gCount; g++) {
        if (g > 0) {
          dChain.append("->");
        }
        dChain.append(sa.getGroup(g).getPos().shortForm);
      }
      derivChainSet.add(dChain.toString());
      lastGroupSet.add(sa.getLastGroup().lexicalForm());
    }

    int uItems = itemSet.size();
    int uNormLemmas = normLemmaSet.size();
    int uLastGroups = lastGroupSet.size();
    int uGroupCounts = groupCountSet.size();
    boolean sameStemLen = (stemLenSet.size() == 1);

    Observability obs;
    if (uLastGroups == K) {
      obs = Observability.FULL_MORPHO_OBSERVABLE;
    } else if (uLastGroups == 1) {
      obs = Observability.LEXICAL_ONLY_OBSERVABLE;
    } else {
      obs = Observability.PARTIAL_MORPHO_OBSERVABLE;
    }

    MacroMechanism mechanism;
    if (uItems == 1) {
      if (uGroupCounts == 1 && derivChainSet.size() == 1) {
        mechanism = MacroMechanism.M1_INFLECTIONAL_SYNCRETISM;
      } else {
        mechanism = MacroMechanism.M2_DERIVATIONAL_SHIFT;
      }
    } else if (hasProperOrAbbrv && hasCommon) {
      mechanism = MacroMechanism.M4_PROPER_VS_COMMON;
    } else if (uNormLemmas == 1 && sameStemLen) {
      if (posPairSet.size() > 1) {
        if (K > uItems && uLastGroups < K) {
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

      if (uItems > 1 && posPairSet.size() == 1 && derivChainSet.size() == 1) {
        if (ALPHABET.containsCircumflex(item.lemma)) {
          sb.append("@CIRC");
        } else if (item.hasAttribute(RootAttribute.CompoundP3sg)) {
          sb.append("@COMP_P3SG");
        } else if (item.hasAttribute(RootAttribute.CompoundP3sgRoot)) {
          sb.append("@COMP_ROOT");
        } else if (item.hasAttribute(RootAttribute.LastVowelDrop)) {
          sb.append("@VDROP");
        }
      }
      candDescs[i] = sb.toString();
    }

    Arrays.sort(candDescs);
    for (int i = 1; i < K; i++) {
      if (candDescs[i].equals(candDescs[i - 1])) {
        candDescs[i] = candDescs[i] + "#ALT";
      }
    }

    String sigKey = String.join(" || ", candDescs);
    String lemmaPair = String.join("/", lemmaSet);
    String lemmaKey = sigKey + "@@" + lemmaPair;
    String ctxKey = sigKey + "##" + contextFrame;

    return new RawAmbigToken(
        sigKey, lemmaKey, ctxKey, (byte) mechanism.ordinal(), (byte) obs.ordinal());
  }

  private static String finalPosTag(SingleAnalysis sa) {
    int gCount = sa.groupCount();
    PrimaryPos finalPos = sa.getGroup(gCount - 1).getPos();
    SecondaryPos sp = sa.getDictionaryItem().secondaryPos;
    if (gCount == 1
        && (sp == SecondaryPos.PCDat
            || sp == SecondaryPos.PCAbl
            || sp == SecondaryPos.PCIns
            || sp == SecondaryPos.PCGen
            || sp == SecondaryPos.ProperNoun)) {
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
      if (sp == SecondaryPos.PCDat
          || sp == SecondaryPos.PCAbl
          || sp == SecondaryPos.PCIns
          || sp == SecondaryPos.PCGen
          || sp == SecondaryPos.DemonstrativePron
          || sp == SecondaryPos.ProperNoun
          || sp == SecondaryPos.Time
          || sp == SecondaryPos.QuestionPron) {
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

  /**
   * Strict character-level whitelist that immediately rejects parentheses, brackets, emojis,
   * supplementary Unicode symbols, dingbats, currency/math symbols, and non-Turkish letters.
   */
  private static boolean isCleanTurkishSentenceChars(String line) {
    int len = line.length();
    for (int i = 0; i < len; i++) {
      char ch = line.charAt(i);
      if (Character.isSurrogate(ch)) {
        // Rejects all supplementary code points (all modern Unicode emojis U+1F300..U+1FAFF, etc.)
        return false;
      }
      if (ch == '(' || ch == ')' || ch == '[' || ch == ']' || ch == '{' || ch == '}' || ch == '<' || ch == '>') {
        return false;
      }
      if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == ' ') {
        continue;
      }
      switch (ch) {
        case 'ç':
        case 'Ç':
        case 'ğ':
        case 'Ğ':
        case 'ı':
        case 'İ':
        case 'ö':
        case 'Ö':
        case 'ş':
        case 'Ş':
        case 'ü':
        case 'Ü':
        case 'â':
        case 'Â':
        case 'î':
        case 'Î':
        case 'û':
        case 'Û':
        case '.':
        case ',':
        case ';':
        case ':':
        case '?':
        case '!':
        case '\'':
        case '-':
        case '%':
        case '/':
          continue;
        default:
          return false;
      }
    }
    return true;
  }

  private static byte lengthBucket(int wordCount) {
    if (wordCount <= 9) {
      return 0; // Compact: 7..9 words
    } else if (wordCount <= 12) {
      return 1; // Standard: 10..12 words
    } else {
      return 2; // Extended: 13..15 words
    }
  }

  private static boolean hasEvalShingleLeak(long[] shingles, Set<Long> evalExcludedShingles) {
    int shared = 0;
    for (long sh : shingles) {
      if (evalExcludedShingles.contains(sh)) {
        shared++;
        // Disqualify if >= 2 four-gram shingles (or >= 50% for short sentences) overlap with Test
        if (shared >= 2 || shared * 2 >= shingles.length) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean isNearDuplicate(long[] shingles, Set<Long> usedShingles) {
    if (shingles.length == 0) {
      return false;
    }
    int shared = 0;
    int threshold = Math.max(2, (shingles.length + 1) / 2);
    for (long sh : shingles) {
      if (usedShingles.contains(sh)) {
        shared++;
        if (shared >= threshold) {
          return true;
        }
      }
    }
    return false;
  }

  private void writeOutputDatasets(
      List<String> domainNames,
      List<List<IndexedCandidate>> testSlices,
      List<IndexedCandidate> allSelectedTest,
      List<List<IndexedCandidate>> trainSlices,
      List<IndexedCandidate> allSelectedTrain)
      throws IOException {

    Path testSlicesDir = outputDir.resolve("test_slices");
    Path trainSlicesDir = outputDir.resolve("train_slices");
    Files.createDirectories(testSlicesDir);
    Files.createDirectories(trainSlicesDir);

    for (int s = 0; s < testSlices.size(); s++) {
      String txtName = String.format(Locale.ROOT, "test_slice_%03d.txt", s + 1);
      String metaName = String.format(Locale.ROOT, "test_slice_%03d.meta.tsv", s + 1);
      List<IndexedCandidate> slice = testSlices.get(s);
      writeSentenceFile(testSlicesDir.resolve(txtName), slice);
      writeTestSliceMetaFile(testSlicesDir.resolve(metaName), slice, domainNames);
    }

    for (int s = 0; s < trainSlices.size(); s++) {
      String txtName = String.format(Locale.ROOT, "train_slice_%03d.txt", s + 1);
      writeSentenceFile(trainSlicesDir.resolve(txtName), trainSlices.get(s));
    }

    writeSentenceFile(outputDir.resolve("test_10k_ordered.txt"), allSelectedTest);
    writeSentenceFile(outputDir.resolve("train_100k_ordered.txt"), allSelectedTrain);
  }

  private static void writeSentenceFile(Path path, List<IndexedCandidate> list) throws IOException {
    try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      for (IndexedCandidate c : list) {
        writer.write(c.text);
        writer.newLine();
      }
    }
  }

  private static void writeTestSliceMetaFile(
      Path path, List<IndexedCandidate> list, List<String> domainNames) throws IOException {
    try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      writer.write(
          "line_no\tsubset_type\tdomain\tword_count\tambig_count\tdominant_mechanism\thas_lexical_only\n");
      for (int i = 0; i < list.size(); i++) {
        IndexedCandidate c = list.get(i);
        writer.write(
            String.format(
                Locale.ROOT,
                "%d\t%s\t%s\t%d\t%d\t%s\t%s\n",
                i + 1,
                c.isChallengePick ? "CHALLENGE" : "NATURAL",
                domainNames.get(c.domainId),
                c.wordCount,
                c.ambigCount,
                MacroMechanism.values()[c.dominantMechanism].name(),
                c.hasLexicalOnly ? "true" : "false"));
      }
    }
  }

  private static CoverageStats computeCoverageStats(
      List<IndexedCandidate> subset,
      long[] corpusSigCounts,
      long totalCandidateAmbigTokens,
      int numSigs,
      int numLemmas,
      int numCtxs) {

    BitSet seenSigs = new BitSet(numSigs);
    BitSet seenLemmas = new BitSet(numLemmas);
    BitSet seenCtxs = new BitSet(numCtxs);
    BitSet seenDomains = new BitSet(64);
    long totalWords = 0;
    long totalAmbig = 0;
    long[] mechCounts = new long[MacroMechanism.values().length];
    long[] obsCounts = new long[Observability.values().length];
    int[] lenCounts = new int[3];

    for (IndexedCandidate c : subset) {
      totalWords += c.wordCount;
      totalAmbig += c.ambigCount;
      lenCounts[c.lengthBucket]++;
      seenDomains.set(c.domainId);
      for (int k = 0; k < c.sigIds.length; k++) {
        seenSigs.set(c.sigIds[k]);
        seenLemmas.set(c.lemmaIds[k]);
        seenCtxs.set(c.ctxIds[k]);
        mechCounts[c.mechOrds[k]]++;
        obsCounts[c.obsOrds[k]]++;
      }
    }

    long coveredCorpusMass = 0;
    for (int s = seenSigs.nextSetBit(0); s >= 0; s = seenSigs.nextSetBit(s + 1)) {
      coveredCorpusMass += corpusSigCounts[s];
    }
    double massPct =
        totalCandidateAmbigTokens > 0
            ? 100.0 * coveredCorpusMass / totalCandidateAmbigTokens
            : 0.0;

    return new CoverageStats(
        subset.size(),
        totalWords,
        totalAmbig,
        seenSigs.cardinality(),
        massPct,
        seenLemmas.cardinality(),
        seenCtxs.cardinality(),
        mechCounts,
        obsCounts,
        lenCounts,
        seenDomains.cardinality());
  }

  private void generateReports(
      long elapsedMs,
      List<String> domainNames,
      List<IndexedCandidate> allCandidates,
      List<List<IndexedCandidate>> testSlices,
      List<IndexedCandidate> allSelectedTest,
      List<IndexedCandidate> allTestNatural,
      List<IndexedCandidate> allTestChallenge,
      List<List<IndexedCandidate>> trainSlices,
      List<IndexedCandidate> allSelectedTrain,
      long[] corpusSigCounts,
      long totalCandidateAmbigTokens,
      int numSigs,
      int numLemmas,
      int numCtxs)
      throws IOException {

    CoverageStats statsAllTest =
        computeCoverageStats(
            allSelectedTest,
            corpusSigCounts,
            totalCandidateAmbigTokens,
            numSigs,
            numLemmas,
            numCtxs);
    CoverageStats statsTestNat =
        computeCoverageStats(
            allTestNatural,
            corpusSigCounts,
            totalCandidateAmbigTokens,
            numSigs,
            numLemmas,
            numCtxs);
    CoverageStats statsTestChal =
        computeCoverageStats(
            allTestChallenge,
            corpusSigCounts,
            totalCandidateAmbigTokens,
            numSigs,
            numLemmas,
            numCtxs);
    CoverageStats statsTestSlice1 =
        computeCoverageStats(
            testSlices.get(0),
            corpusSigCounts,
            totalCandidateAmbigTokens,
            numSigs,
            numLemmas,
            numCtxs);

    // Prepare random baseline pool for empirical comparison at 1K, 2K, 5K, 10K, 25K, 50K, 100K
    List<IndexedCandidate> randomBaselinePool = new ArrayList<>(allCandidates);
    Collections.shuffle(randomBaselinePool, new Random(777L));

    int[] milestones = {1000, 2000, 5000, 10000, 25000, 50000, 100000};
    Map<Integer, CoverageStats> scssMilestoneStats = new LinkedHashMap<>();
    Map<Integer, CoverageStats> randMilestoneStats = new LinkedHashMap<>();

    for (int m : milestones) {
      int k = Math.min(m, allSelectedTrain.size());
      scssMilestoneStats.put(
          m,
          computeCoverageStats(
              allSelectedTrain.subList(0, k),
              corpusSigCounts,
              totalCandidateAmbigTokens,
              numSigs,
              numLemmas,
              numCtxs));
      int rk = Math.min(m, randomBaselinePool.size());
      randMilestoneStats.put(
          m,
          computeCoverageStats(
              randomBaselinePool.subList(0, rk),
              corpusSigCounts,
              totalCandidateAmbigTokens,
              numSigs,
              numLemmas,
              numCtxs));
    }

    int[] sampleTrainSliceIndices = {0, 1, 4, 9, 24, 49, 74, 99};
    Map<Integer, CoverageStats> trainSliceStats = new LinkedHashMap<>();
    for (int idx : sampleTrainSliceIndices) {
      if (idx < trainSlices.size()) {
        trainSliceStats.put(
            idx + 1,
            computeCoverageStats(
                trainSlices.get(idx),
                corpusSigCounts,
                totalCandidateAmbigTokens,
                numSigs,
                numLemmas,
                numCtxs));
      }
    }

    if (reportOut.getParent() != null) {
      Files.createDirectories(reportOut.getParent());
    }

    try (BufferedWriter md = Files.newBufferedWriter(reportOut, StandardCharsets.UTF_8)) {
      md.write("# Turkish Disambiguation Dataset Selection Report (`SCSS-v1`)\n\n");
      md.write("## 1. Executive Summary & Generated Files\n\n");
      md.write("| Metric | Value |\n");
      md.write("| :--- | :--- |\n");
      md.write(String.format(Locale.ROOT, "| **Timestamp** | `%s` |\n", Instant.now().toString()));
      md.write(
          String.format(
              Locale.ROOT,
              "| **Source Corpus** | `%s` (%d domains) |\n",
              inputDir.toAbsolutePath(),
              domainNames.size()));
      md.write(
          String.format(
              Locale.ROOT, "| **Output Directory** | `%s` |\n", outputDir.toAbsolutePath()));
      md.write(
          String.format(
              Locale.ROOT,
              "| **Candidate Pool Evaluated** | **%,d** clean sentences (%,d unique `ZAS-v3` signatures) |\n",
              allCandidates.size(),
              numSigs));
      md.write(
          String.format(
              Locale.ROOT,
              "| **Held-Out Test Pool** | **%,d** sentences across **%d slices** (`test_slices/test_slice_001.txt` .. `test_slice_%03d.txt`) |\n",
              allSelectedTest.size(),
              testSlices.size(),
              testSlices.size()));
      md.write(
          String.format(
              Locale.ROOT,
              "| **Master Training Pool** | **%,d** sentences across **%d slices** (`train_slices/train_slice_001.txt` .. `train_slice_%03d.txt`) |\n",
              allSelectedTrain.size(),
              trainSlices.size(),
              trainSlices.size()));
      md.write(
          String.format(
              Locale.ROOT,
              "| **Total Selection Wall Time** | **%.2f seconds** |\n\n",
              elapsedMs / 1000.0));

      md.write("## 2. Held-Out Test Pool Profile (`10 Slices x 1,000 Sentences`)\n\n");
      md.write(
          "All 10,000 test sentences and their 4-gram word shingles are strictly excluded from the 100,000 training sentences.\n\n");
      md.write(
          "| Test Subset / Slice | Sentences | Total Words | Ambig Words (%) | Unique Signatures | Corpus Sig Mass Covered (%) | Unique `(Sig, Lemma)` | `LEXICAL_ONLY` Tokens (%) |\n");
      md.write("| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
      writeEvalRow(md, "`test_slices/test_slice_001.txt` *(Single Slice)*", statsTestSlice1);
      writeEvalRow(md, "`test_10k_ordered.txt` *(All 10 Slices)*", statsAllTest);
      writeEvalRow(md, "↳ *Natural Component (700/slice)*", statsTestNat);
      writeEvalRow(md, "↳ *Challenge Component (300/slice)*", statsTestChal);
      md.write("\n");

      md.write("### Macro-Mechanism Distribution in Test Pool: Natural vs. Challenge Components\n\n");
      md.write(
          "| Macro-Mechanism | Natural Component Tokens (%) | Challenge Component Tokens (%) | Combined 10K Test Pool (%) | Diagnostic Role |\n");
      md.write("| :--- | ---: | ---: | ---: | :--- |\n");
      for (MacroMechanism mech : MacroMechanism.values()) {
        int ord = mech.ordinal();
        long cNat = statsTestNat.mechCounts[ord];
        long cChal = statsTestChal.mechCounts[ord];
        long cAll = statsAllTest.mechCounts[ord];
        double pNat =
            statsTestNat.totalAmbigWords > 0 ? 100.0 * cNat / statsTestNat.totalAmbigWords : 0.0;
        double pChal =
            statsTestChal.totalAmbigWords > 0 ? 100.0 * cChal / statsTestChal.totalAmbigWords : 0.0;
        double pAll =
            statsAllTest.totalAmbigWords > 0 ? 100.0 * cAll / statsAllTest.totalAmbigWords : 0.0;
        md.write(
            String.format(
                Locale.ROOT,
                "| **`%s`** | %,d (%.2f%%) | %,d (**%.2f%%**) | %,d (%.2f%%) | %s |\n",
                mech.name(),
                cNat,
                pNat,
                cChal,
                pChal,
                cAll,
                pAll,
                mech.description));
      }
      md.write("\n");

      md.write("## 3. Prefix Learning-Curve Coverage: `SCSS-v1` vs. Uniform Random Sampling\n\n");
      md.write(
          "This table compares taking the first $K$ sentences of `train_100k_ordered.txt` (`Slices 1..k`) against $K$ uniformly sampled clean sentences:\n\n");
      md.write(
          "| Prefix Budget ($K$) | Strategy | Unique `ZAS-v3` Signatures | Corpus Ambiguity Mass Covered (%) | Unique `(Sig, Lemma)` Pairs | Unique `(Sig, ContextFrame)` Pairs | `LEXICAL_ONLY` Twins (`F15`/`F17` Blind) |\n");
      md.write("| ---: | :--- | ---: | ---: | ---: | ---: | ---: |\n");
      for (int m : milestones) {
        CoverageStats scss = scssMilestoneStats.get(m);
        CoverageStats rand = randMilestoneStats.get(m);
        int lexIdx = Observability.LEXICAL_ONLY_OBSERVABLE.ordinal();
        md.write(
            String.format(
                Locale.ROOT,
                "| **%,d sents** | **`SCSS-v1` (Ours)** | **%,d** | **%.2f%%** | **%,d** | **%,d** | **%,d** |\n",
                m,
                scss.uniqueSignatures,
                scss.coveredSignatureMassPct,
                scss.uniqueLemmaPairs,
                scss.uniqueContextFrames,
                scss.obsCounts[lexIdx]));
        md.write(
            String.format(
                Locale.ROOT,
                "| *(%,d slices)* | *Uniform Random* | *%,d* | *%.2f%%* | *%,d* | *%,d* | *%,d* |\n",
                m / sliceSize,
                rand.uniqueSignatures,
                rand.coveredSignatureMassPct,
                rand.uniqueLemmaPairs,
                rand.uniqueContextFrames,
                rand.obsCounts[lexIdx]));
      }
      md.write("\n");

      md.write("## 4. Individual 1,000-Sentence Training Slice Consistency Audit\n\n");
      md.write(
          "Every individual `train_slice_XXX.txt` (1,000 sentences) is engineered to be a self-contained, representative training set across sentence lengths, domains, and all 9 Macro-Mechanisms:\n\n");
      md.write(
          "| Slice File | Sentences | Words | Ambig Words (%) | `7-9w / 10-12w / 13-15w` (`%`) | Domains | Unique Signatures | Corpus Sig Mass Covered (%) | `M1+M2` Morpho (%) | `LEXICAL_ONLY` (%) |\n");
      md.write("| :--- | ---: | ---: | ---: | :---: | ---: | ---: | ---: | ---: | ---: |\n");
      for (Map.Entry<Integer, CoverageStats> entry : trainSliceStats.entrySet()) {
        int sNum = entry.getKey();
        CoverageStats cs = entry.getValue();
        double ambigPct = cs.totalWords > 0 ? 100.0 * cs.totalAmbigWords / cs.totalWords : 0.0;
        double pShort = 100.0 * cs.lengthBucketCounts[0] / cs.numSentences;
        double pMed = 100.0 * cs.lengthBucketCounts[1] / cs.numSentences;
        double pLong = 100.0 * cs.lengthBucketCounts[2] / cs.numSentences;
        long m1m2 =
            cs.mechCounts[MacroMechanism.M1_INFLECTIONAL_SYNCRETISM.ordinal()]
                + cs.mechCounts[MacroMechanism.M2_DERIVATIONAL_SHIFT.ordinal()];
        double pM1M2 = cs.totalAmbigWords > 0 ? 100.0 * m1m2 / cs.totalAmbigWords : 0.0;
        double pLex =
            cs.totalAmbigWords > 0
                ? 100.0
                    * cs.obsCounts[Observability.LEXICAL_ONLY_OBSERVABLE.ordinal()]
                    / cs.totalAmbigWords
                : 0.0;
        md.write(
            String.format(
                Locale.ROOT,
                "| `train_slice_%03d.txt` | %,d | %,d | %,d (%.1f%%) | `%.0f%% / %.0f%% / %.0f%%` | %d/%d | %,d | **%.2f%%** | %.1f%% | %.1f%% |\n",
                sNum,
                cs.numSentences,
                cs.totalWords,
                cs.totalAmbigWords,
                ambigPct,
                pShort,
                pMed,
                pLong,
                cs.activeDomains,
                domainNames.size(),
                cs.uniqueSignatures,
                cs.coveredSignatureMassPct,
                pM1M2,
                pLex));
      }
      md.write("\n");

      md.write("## 5. Sample Selected Sentences from `train_slice_001.txt` and `test_slice_001.txt`\n\n");
      md.write("### First 10 Sentences of `train_slice_001.txt`\n\n");
      List<IndexedCandidate> tr1 = trainSlices.get(0);
      for (int i = 0; i < Math.min(10, tr1.size()); i++) {
        IndexedCandidate c = tr1.get(i);
        md.write(
            String.format(
                Locale.ROOT,
                "%d. **[%s | %dw | %d ambig]** `%s`\n",
                i + 1,
                domainNames.get(c.domainId),
                c.wordCount,
                c.ambigCount,
                c.text));
      }
      md.write("\n### First 10 Sentences of `test_slice_001.txt`\n\n");
      List<IndexedCandidate> te1 = testSlices.get(0);
      for (int i = 0; i < Math.min(10, te1.size()); i++) {
        IndexedCandidate c = te1.get(i);
        md.write(
            String.format(
                Locale.ROOT,
                "%d. **[%s | `%s` | `%s` | %dw | %d ambig]** `%s`\n",
                i + 1,
                domainNames.get(c.domainId),
                c.isChallengePick ? "CHALLENGE" : "NATURAL",
                MacroMechanism.values()[c.dominantMechanism].name(),
                c.wordCount,
                c.ambigCount,
                c.text));
      }
    }

    if (jsonOut != null) {
      Map<String, Object> root = new LinkedHashMap<>();
      root.put("schema_version", "SCSS-v1");
      root.put("created_at", Instant.now().toString());
      root.put("input_dir", inputDir.toAbsolutePath().toString());
      root.put("output_dir", outputDir.toAbsolutePath().toString());
      root.put("candidate_pool_size", allCandidates.size());
      root.put("unique_signatures_in_pool", numSigs);
      root.put("unique_lemma_pairs_in_pool", numLemmas);
      root.put("unique_context_frames_in_pool", numCtxs);
      root.put("num_test_slices", testSlices.size());
      root.put("num_train_slices", trainSlices.size());
      root.put("slice_size", sliceSize);
      root.put("total_test_sentences", allSelectedTest.size());
      root.put("total_training_sentences", allSelectedTrain.size());

      Map<String, Object> milestonesJson = new LinkedHashMap<>();
      for (int m : milestones) {
        Map<String, Object> mm = new LinkedHashMap<>();
        mm.put("scss_v1", scssMilestoneStats.get(m));
        mm.put("uniform_random", randMilestoneStats.get(m));
        milestonesJson.put(String.valueOf(m), mm);
      }
      root.put("prefix_milestones", milestonesJson);

      Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
      try (BufferedWriter writer = Files.newBufferedWriter(jsonOut, StandardCharsets.UTF_8)) {
        gson.toJson(root, writer);
      }
    }
  }

  private static void writeEvalRow(BufferedWriter md, String label, CoverageStats cs)
      throws IOException {
    double ambigPct = cs.totalWords > 0 ? 100.0 * cs.totalAmbigWords / cs.totalWords : 0.0;
    long lexCount = cs.obsCounts[Observability.LEXICAL_ONLY_OBSERVABLE.ordinal()];
    double lexPct = cs.totalAmbigWords > 0 ? 100.0 * lexCount / cs.totalAmbigWords : 0.0;
    md.write(
        String.format(
            Locale.ROOT,
            "| %s | **%,d** | %,d | %,d (%.1f%%) | %,d | **%.2f%%** | %,d | %,d (%.1f%%) |\n",
            label,
            cs.numSentences,
            cs.totalWords,
            cs.totalAmbigWords,
            ambigPct,
            cs.uniqueSignatures,
            cs.coveredSignatureMassPct,
            cs.uniqueLemmaPairs,
            lexCount,
            lexPct));
  }
}
