package zemberek.normalization.v2.research;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import zemberek.lm.compression.SmoothLm;
import zemberek.morphology.TurkishMorphology;
import zemberek.normalization.TurkishSentenceNormalizer;
import zemberek.normalization.experiment.CompactStemEndingSpellChecker;
import zemberek.normalization.v2.research.NormV2Types.AlignedSentence;
import zemberek.normalization.v2.research.NormV2Types.PreparedLattice;
import zemberek.normalization.v2.research.NormV2Types.SentenceLattice;
import zemberek.normalization.v2.research.NormV2Types.SpanArc;
import zemberek.normalization.v2.research.NormV2Types.SpanToken;
import zemberek.normalization.v2.research.NormV2Types.TextNormalizer;
import zemberek.normalization.v2.research.ParallelCorpusMiner.CleanCorpusKnowledge;
import zemberek.normalization.v2.research.ParallelCorpusMiner.MinedKnowledge;
import zemberek.tokenization.Token;
import zemberek.tokenization.TurkishTokenizer;

/**
 * End-to-end multi-regime training, evaluation, ablation, data-scaling, and latency/throughput
 * benchmark runner for the {@code v2.3} Turkish normalization prototypes ({@link
 * SpanLatticePerceptronNormalizer}, {@link TwoStageGbdtNormalizer}, and {@code Hybrid OOF-GBDT +
 * LatticePerceptron}) vs. {@code HEAD} {@link TurkishSentenceNormalizer}.
 *
 * <h3>Acronyms &amp; Evaluation Metrics</h3>
 *
 * <ul>
 *   <li><b>W-ERR ({@code wordErrReductionPct})</b>: Word Error Reduction percentage vs. the
 *       do-nothing raw input baseline: {@code 100 * (baselineWordEdits - systemWordEdits) /
 *       baselineWordEdits} (computed via lowercase word-level Levenshtein distance against gold).
 *   <li><b>SentEM-Lo / SentEM-Ca ({@code sentExactLowerPct} / {@code sentExactCasedPct})</b>:
 *       Full-sentence exact-match accuracy after whitespace normalization (lowercase / case-exact).
 *   <li><b>CleanFPR ({@code cleanTokenFalseCorruptPct})</b>: Clean-Token False Positive Rate —
 *       percentage of already-clean tokens in the input that the normalizer erroneously alters.
 *   <li><b>GBDT</b>: Gradient Boosted Decision Trees ({@link ObliviousGbdt}).
 *   <li><b>OOF</b>: Out-Of-Fold cross-fitting (prevents train/inference distribution mismatch in
 *       stacked models).
 * </ul>
 *
 * <p>Automatically discovers all cleaned {@code train_slice_*_normalized.jsonl} files across the
 * {@code heavy} ({@code full_110k}), {@code medium} ({@code medium_110k}), and {@code low} ({@code
 * low_110k}) noise regimes while keeping {@code test_slice_01_normalized.jsonl} strictly held out
 * as {@code Eval 1}.
 */
public final class NormalizationV2BenchmarkRunner {

  /** Raw input and reference gold text pair for sentence-level normalization evaluation. */
  public record EvalPair(String id, String rawText, String goldText) {}

  /**
   * Accuracy and single-pass throughput metrics for one normalizer on one evaluation dataset.
   *
   * @param systemName human-readable normalizer configuration label
   * @param datasetName benchmark split name
   * @param sentences number of evaluated sentences
   * @param goldWords total reference word count
   * @param baselineWordEdits word-level Levenshtein distance of unnormalized raw input vs. gold
   * @param systemWordEdits word-level Levenshtein distance of normalizer output vs. gold
   * @param wordErrReductionPct {@code W-ERR}: percentage of baseline word errors eliminated
   * @param sentExactLowerPct {@code SentEM-Lo}: lowercase exact sentence match percentage
   * @param sentExactCasedPct {@code SentEM-Ca}: case-sensitive exact sentence match percentage
   * @param cleanTokenFalseCorruptPct {@code CleanFPR}: percentage of clean input tokens corrupted
   * @param tokensPerSec single-pass evaluation throughput in tokens/second
   * @param modelBytes estimated in-memory footprint of learned parameters in bytes
   */
  public record BenchmarkResult(
      String systemName,
      String datasetName,
      int sentences,
      int goldWords,
      int baselineWordEdits,
      int systemWordEdits,
      double wordErrReductionPct,
      double sentExactLowerPct,
      double sentExactCasedPct,
      double cleanTokenFalseCorruptPct,
      double tokensPerSec,
      long modelBytes) {}

  /**
   * Candidate lattice upper-bound (oracle) recall statistics prior to statistical ranking.
   *
   * @param label regime/configuration label
   * @param sentences number of sentences inspected
   * @param totalSpans total gold alignment spans evaluated
   * @param avgArcsPerToken average candidate arcs generated per input token
   * @param spanRecallLowerPct percentage of gold spans recoverable in lowercase from the lattice
   * @param spanRecallCasedPct percentage of gold spans recoverable with exact casing
   * @param sentOracleLowerPct percentage of complete sentences 100% recoverable from the lattice
   */
  public record OracleStats(
      String label,
      int sentences,
      int totalSpans,
      double avgArcsPerToken,
      double spanRecallLowerPct,
      double spanRecallCasedPct,
      double sentOracleLowerPct) {}

  /**
   * Dedicated cold/warm single-thread latency and 8-thread parallel throughput metrics.
   *
   * @param systemName normalizer configuration label
   * @param workloadName benchmark workload name
   * @param sentences number of sentences per timed pass
   * @param inputTokens total input tokens per timed pass
   * @param cold1TTokPerSec first-pass cold-cache single-thread throughput (tokens/sec)
   * @param warm1TTokPerSec steady-state JIT-warmed single-thread throughput (tokens/sec)
   * @param warm1TSentPerSec steady-state single-thread sentence throughput (sentences/sec)
   * @param meanMsPerSent mean single-thread latency per sentence in milliseconds
   * @param p50MsPerSent median (50th percentile) latency per sentence in milliseconds
   * @param p95MsPerSent 95th percentile tail latency per sentence in milliseconds
   * @param p99MsPerSent 99th percentile tail latency per sentence in milliseconds
   * @param warm8TTokPerSec 8-thread parallel throughput (tokens/sec)
   * @param warm8TSentPerSec 8-thread parallel sentence throughput (sentences/sec)
   * @param parallel8TSpeedup ratio {@code warm8TTokPerSec / warm1TTokPerSec}
   */
  public record SpeedBenchmarkResult(
      String systemName,
      String workloadName,
      int sentences,
      int inputTokens,
      double cold1TTokPerSec,
      double warm1TTokPerSec,
      double warm1TSentPerSec,
      double meanMsPerSent,
      double p50MsPerSent,
      double p95MsPerSent,
      double p99MsPerSent,
      double warm8TTokPerSec,
      double warm8TSentPerSec,
      double parallel8TSpeedup) {}

  private static final Path HEAVY_SLICE_DIR =
      Paths.get(System.getenv().getOrDefault("ZEMBEREK_DATA_DIR", System.getProperty("user.home") + "/data/turkish") + "/normalization/noisy_tr_v2/full_110k/general/llm_gold/normalized");
  private static final Path MEDIUM_SLICE_DIR =
      Paths.get(System.getenv().getOrDefault("ZEMBEREK_DATA_DIR", System.getProperty("user.home") + "/data/turkish") + "/normalization/noisy_tr_v2/medium_110k/general/llm_gold/normalized");
  private static final Path LOW_SLICE_DIR =
      Paths.get(System.getenv().getOrDefault("ZEMBEREK_DATA_DIR", System.getProperty("user.home") + "/data/turkish") + "/normalization/noisy_tr_v2/low_110k/general/llm_gold/normalized");
  private static final String PRIMARY_TEST_SLICE_FILE = "test_slice_01_normalized.jsonl";
  private static final String FALLBACK_HELD_OUT_SLICE_FILE = "train_slice_04_normalized.jsonl";

  private static final Path NASTY_5K_TSV =
      Paths.get(System.getenv().getOrDefault("ZEMBEREK_DATA_DIR", System.getProperty("user.home") + "/data/turkish") + "/normalization/custom_benchmark/nasty_5k/llm_gold/exports/nasty_5000_pairs.tsv");
  private static final Path MULTILEXNORM_TEST =
      Paths.get(System.getenv().getOrDefault("ZEMBEREK_DATA_DIR", System.getProperty("user.home") + "/data/turkish") + "/normalization/benchmarks/multilexnorm/tur/test.norm");
  private static final Path BOUN_SOURCE_TEST =
      Paths.get(System.getenv().getOrDefault("ZEMBEREK_DATA_DIR", System.getProperty("user.home") + "/data/turkish") + "/normalization/benchmarks/gecturk_boun_clitics/boun_source_test.txt");
  private static final Path BOUN_TARGET_TEST =
      Paths.get(System.getenv().getOrDefault("ZEMBEREK_DATA_DIR", System.getProperty("user.home") + "/data/turkish") + "/normalization/benchmarks/gecturk_boun_clitics/boun_target_test.txt");
  private static final Path SYNTH_ASCII_TSV =
      Paths.get(System.getenv().getOrDefault("ZEMBEREK_DATA_DIR", System.getProperty("user.home") + "/data/turkish") + "/normalization/benchmarks/synthetic_1k/synth_asciified_1k.tsv");
  private static final Path SYNTH_TYPO_TSV =
      Paths.get(System.getenv().getOrDefault("ZEMBEREK_DATA_DIR", System.getProperty("user.home") + "/data/turkish") + "/normalization/benchmarks/synthetic_1k/synth_typo_1k.tsv");
  private static final Path SYNTH_CLEAN_TSV =
      Paths.get(System.getenv().getOrDefault("ZEMBEREK_DATA_DIR", System.getProperty("user.home") + "/data/turkish") + "/normalization/benchmarks/synthetic_1k/synth_clean_1k.tsv");
  private static final Path ZEMBEREK_NORM_ROOT =
      Paths.get(System.getenv().getOrDefault("ZEMBEREK_DATA_DIR", System.getProperty("user.home") + "/data/turkish") + "/normalization/zemberek_norm_data");
  private static final Path CLEAN_DIR = Paths.get(System.getenv().getOrDefault("ZEMBEREK_DATA_DIR", System.getProperty("user.home") + "/data/turkish") + "/clean");

  public static void main(String[] args) throws Exception {
    Path outDir = Paths.get("experiment/target/norm_v2_results");
    boolean skipSweeps = false;
    boolean heavyOnly = false;
    for (String arg : args) {
      if ("--skip-sweeps".equals(arg)) {
        skipSweeps = true;
      } else if ("--heavy-only".equals(arg)) {
        heavyOnly = true;
      } else if (!arg.startsWith("--")) {
        outDir = Paths.get(arg);
      }
    }
    Files.createDirectories(outDir);

    System.out.println("================================================================================");
    System.out.println("  TURKISH NORMALIZATION FROM SCRATCH (v2.3): DATA-DRIVEN ML & SPEED SUITE");
    System.out.println("================================================================================");

    // 1. Auto-discover dedicated test_slice_01 (or fallback train_slice_04) & multi-regime training slices
    long tLoad0 = System.nanoTime();
    boolean hasDedicatedTestSlice = Files.exists(HEAVY_SLICE_DIR.resolve(PRIMARY_TEST_SLICE_FILE));
    String heldOutSliceFile =
        hasDedicatedTestSlice ? PRIMARY_TEST_SLICE_FILE : FALLBACK_HELD_OUT_SLICE_FILE;
    String heldOutName = heldOutSliceFile.replace("_normalized.jsonl", "");

    record RegimeSource(String tag, Path dir, String excludeFile) {}
    List<RegimeSource> regimeSources = new ArrayList<>();
    regimeSources.add(new RegimeSource("heavy", HEAVY_SLICE_DIR, heldOutSliceFile));
    if (!heavyOnly) {
      regimeSources.add(new RegimeSource("medium", MEDIUM_SLICE_DIR, ""));
      regimeSources.add(new RegimeSource("low", LOW_SLICE_DIR, ""));
    }

    List<AlignedSentence> rawTrainPool = new ArrayList<>(75_000);
    List<String> loadedSliceNames = new ArrayList<>();
    Map<String, Integer> regimeSentenceCounts = new LinkedHashMap<>();
    for (RegimeSource rs : regimeSources) {
      int regimeCount = 0;
      if (Files.isDirectory(rs.dir())) {
        List<Path> sliceFiles;
        try (Stream<Path> st = Files.list(rs.dir())) {
          sliceFiles =
              st.filter(
                      p -> {
                        String fn = p.getFileName().toString();
                        return fn.startsWith("train_slice_")
                            && fn.endsWith("_normalized.jsonl")
                            && !fn.equals(rs.excludeFile());
                      })
                  .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                  .toList();
        }
        for (Path sp : sliceFiles) {
          List<AlignedSentence> sents = NormV2Types.loadAlignedJsonl(sp, 0);
          rawTrainPool.addAll(sents);
          regimeCount += sents.size();
          String baseName = sp.getFileName().toString().replace("_normalized.jsonl", "");
          loadedSliceNames.add(rs.tag() + ":" + baseName);
        }
      }
      regimeSentenceCounts.put(rs.tag(), regimeCount);
    }

    List<AlignedSentence> heldOutSlice04Aligned =
        NormV2Types.loadAlignedJsonl(HEAVY_SLICE_DIR.resolve(heldOutSliceFile), 0);
    List<EvalPair> slice04Eval = toEvalPairs(heldOutSlice04Aligned);
    List<EvalPair> nasty5kEval = loadNasty5kTsv(NASTY_5K_TSV);
    List<EvalPair> multiLexNormEval = loadMultiLexNorm(MULTILEXNORM_TEST);
    List<EvalPair>[] bounSplits = loadBounTestSplits(BOUN_SOURCE_TEST, BOUN_TARGET_TEST);
    List<EvalPair> bounCliticsEval = bounSplits[0];
    List<EvalPair> bounCleanEval = bounSplits[1];
    List<EvalPair> synthAsciiEval =
        Files.exists(SYNTH_ASCII_TSV) ? loadNasty5kTsv(SYNTH_ASCII_TSV) : List.of();
    List<EvalPair> synthTypoEval =
        Files.exists(SYNTH_TYPO_TSV) ? loadNasty5kTsv(SYNTH_TYPO_TSV) : List.of();
    List<EvalPair> synthCleanEval =
        Files.exists(SYNTH_CLEAN_TSV) ? loadNasty5kTsv(SYNTH_CLEAN_TSV) : List.of();

    // Enforce strict zero-leakage fingerprint set across ALL evaluation benchmarks
    Set<String> forbiddenFingerprints = new HashSet<>(65_536);
    addEvalFingerprints(forbiddenFingerprints, slice04Eval);
    addEvalFingerprints(forbiddenFingerprints, nasty5kEval);
    addEvalFingerprints(forbiddenFingerprints, multiLexNormEval);
    addEvalFingerprints(forbiddenFingerprints, bounCliticsEval);
    addEvalFingerprints(forbiddenFingerprints, bounCleanEval);
    addEvalFingerprints(forbiddenFingerprints, synthAsciiEval);
    addEvalFingerprints(forbiddenFingerprints, synthTypoEval);
    addEvalFingerprints(forbiddenFingerprints, synthCleanEval);

    List<AlignedSentence> trainPool = new ArrayList<>(rawTrainPool.size());
    int droppedTrainLeak = 0;
    for (AlignedSentence as : rawTrainPool) {
      if (forbiddenFingerprints.contains(ParallelCorpusMiner.sentenceFingerprint(as.rawText()))
          || forbiddenFingerprints.contains(
              ParallelCorpusMiner.sentenceFingerprint(as.goldText()))) {
        droppedTrainLeak++;
      } else {
        trainPool.add(as);
      }
    }

    String trainTag = Math.round(trainPool.size() / 1000.0) + "K";
    String heavyEvalTitle = heldOutName + " (5K Heavy)";
    System.out.printf(
        Locale.US,
        "Loaded datasets in %.2fs:%n"
            + "  Train Slices (%d)         : %s%n"
            + "  Regime Breakdown          : heavy=%,d | medium=%,d | low=%,d%n"
            + "  Train Pool (%s)           : %,d sentences (dropped %,d exact overlaps)%n"
            + "  Eval 1: %-17s : %,d sentences (strictly held-out unseen heavy)%n"
            + "  Eval 2: nasty_5k          : %,d sentences (independent social media)%n"
            + "  Eval 3: MultiLexNorm test : %,d sentences (W-NUT 2021 Turkish)%n"
            + "  Eval 4: GECTurk Clitics   : %,d sentences (formal clitic split/merge)%n"
            + "  Eval 5: GECTurk Clean     : %,d sentences (formal clean false-positive test)%n"
            + "  Eval 6: Synth Asciified   : %,d sentences (100%% asciified multi-domain)%n"
            + "  Eval 7: Synth Typo d=1    : %,d sentences (Q-keyboard distance-1 typos)%n"
            + "  Eval 8: Synth Clean       : %,d sentences (verified clean multi-domain)%n%n",
        (System.nanoTime() - tLoad0) / 1e9,
        loadedSliceNames.size(),
        String.join(", ", loadedSliceNames),
        regimeSentenceCounts.getOrDefault("heavy", 0),
        regimeSentenceCounts.getOrDefault("medium", 0),
        regimeSentenceCounts.getOrDefault("low", 0),
        trainTag,
        trainPool.size(),
        droppedTrainLeak,
        heldOutName,
        slice04Eval.size(),
        nasty5kEval.size(),
        multiLexNormEval.size(),
        bounCliticsEval.size(),
        bounCleanEval.size(),
        synthAsciiEval.size(),
        synthTypoEval.size(),
        synthCleanEval.size());

    // 2. Initialize shared Zemberek core resources (Morphology, 2-gram SmoothLm, Compact CSR Spell)
    long tInit0 = System.nanoTime();
    TurkishMorphology morphology = TurkishMorphology.createWithDefaults();
    Path lmPath = ZEMBEREK_NORM_ROOT.resolve("lm.2gram.slm");
    Path normDataDir = ZEMBEREK_NORM_ROOT.resolve("normalization");
    SmoothLm lm = SmoothLm.builder(lmPath).logBase(Math.E).build();
    CompactStemEndingSpellChecker compactSpell =
        new CompactStemEndingSpellChecker(morphology, true);

    TurkishSentenceNormalizer zemberekHead =
        new TurkishSentenceNormalizer(morphology, normDataDir, lmPath);
    TurkishSentenceNormalizer zemberekLegacy =
        new TurkishSentenceNormalizer(morphology, normDataDir, lmPath).useLegacyDeasciifier();

    System.out.printf(
        Locale.US,
        "Initialized Zemberek Morphology + SmoothLm (%.1f MB) + CompactCSRSpell (%.1f MB) + Baselines in %.2fs%n%n",
        Files.size(lmPath) / (1024.0 * 1024.0),
        compactSpell.estimatedGraphBytes() / (1024.0 * 1024.0),
        (System.nanoTime() - tInit0) / 1e9);

    List<Path> cleanCorpusFiles =
        List.of(
            CLEAN_DIR.resolve("ntv-750k-sentences.txt"),
            CLEAN_DIR.resolve("www.cnnturk.com.txt"),
            CLEAN_DIR.resolve("www.haberturk.com.txt"),
            CLEAN_DIR.resolve("vikipedi.txt"),
            CLEAN_DIR.resolve("onedio.com.txt"),
            CLEAN_DIR.resolve("open-subtitles.txt"));

    // 3. Mine Unlabeled Clean Corpus ONCE (6 files x 300K = ~1.8M clean sentences, 3-gram CleanWordLm)
    long tClean0 = System.nanoTime();
    CleanCorpusKnowledge sharedClean =
        ParallelCorpusMiner.mineCleanCorpus(cleanCorpusFiles, 300_000, forbiddenFingerprints);
    double cleanMineSec = (System.nanoTime() - tClean0) / 1e9;

    long tMine0 = System.nanoTime();
    MinedKnowledge minedFull =
        ParallelCorpusMiner.mineWithClean(trainPool, sharedClean, morphology);
    double parMineSec = (System.nanoTime() - tMine0) / 1e9;
    int totalSuffixRules =
        minedFull.suffixRulesByLastChar.values().stream().mapToInt(List::size).sum();
    System.out.printf(
        Locale.US,
        "ParallelCorpusMiner (clean=%,d sents in %.2fs + parallel=%s in %.2fs):%n"
            + "  1:N entries=%,d | 2:1 merges=%,d | 3:1 merges=%,d | suffixRules=%,d | casingLexicon=%,d%n"
            + "  CleanWordLm 3-gram=%.1f MB | Total Mined Knowledge=%.1f MB%n%n",
        minedFull.cleanSentenceCount,
        cleanMineSec,
        trainTag,
        parMineSec,
        minedFull.parallel1ToN.size(),
        minedFull.parallel2To1.size(),
        minedFull.parallel3To1.size(),
        totalSuffixRules,
        minedFull.casingLexicon.size(),
        minedFull.cleanLm.estimatedBytes() / (1024.0 * 1024.0),
        minedFull.estimatedBytes() / (1024.0 * 1024.0));

    SpanCandidateGenerator fullGen =
        new SpanCandidateGenerator(
            morphology, compactSpell, lm, minedFull, SpanCandidateGenerator.Config.full());

    // Measure Multi-Span Lattice Oracle Recall on unseen train_slice_04
    OracleStats oracleFull =
        computeOracleStats("Full Multi-Span DAG (" + trainTag + ")", fullGen, heldOutSlice04Aligned);
    printOracleStats(oracleFull);

    // 4. Prepare LOO Training Lattices in parallel, then train Norm1, Norm2, and OOF-Hybrid
    System.out.printf(
        Locale.US, "%n--- Training v2.3 ML Normalizers on %,d Sentences ---%n", trainPool.size());
    long tPrep0 = System.nanoTime();
    List<PreparedLattice> preparedTrain = fullGen.prepareTrainingLattices(trainPool);
    double prepSec = (System.nanoTime() - tPrep0) / 1e9;
    System.out.printf(
        Locale.US,
        "Prepared %,d LOO Multi-Span training lattices in %.2fs (8-core parallel)%n",
        preparedTrain.size(),
        prepSec);

    long tP0 = System.nanoTime();
    SpanLatticePerceptronNormalizer.TrainConfig percCfg =
        new SpanLatticePerceptronNormalizer.TrainConfig();
    SpanLatticePerceptronNormalizer norm1Perceptron =
        SpanLatticePerceptronNormalizer.trainFromPrepared(
            "Norm1: SpanLatticePerceptron (" + trainTag + ")", fullGen, preparedTrain, percCfg, null);
    double percTrainSec = (System.nanoTime() - tP0) / 1e9;
    System.out.printf(
        Locale.US,
        "Trained %s in %.2fs | active MPHF features=%,d | model RAM=%.2f KB%n",
        norm1Perceptron.name(),
        percTrainSec,
        norm1Perceptron.activeSparseFeatureCount(),
        norm1Perceptron.modelRamBytes() / 1024.0);

    long tG0 = System.nanoTime();
    TwoStageGbdtNormalizer.TrainConfig gbdtCfg = new TwoStageGbdtNormalizer.TrainConfig();
    TwoStageGbdtNormalizer norm2GbdtGated =
        TwoStageGbdtNormalizer.trainFromPrepared(
            "Norm2b: TwoStageGBDT + Gate (" + trainTag + ")", fullGen, preparedTrain, gbdtCfg);
    TwoStageGbdtNormalizer norm2GbdtFull =
        norm2GbdtGated.withGatekeeper(
            false, "Norm2a: ObliviousGBDT Reranker (" + trainTag + ")");
    double gbdtTrainSec = (System.nanoTime() - tG0) / 1e9;
    System.out.printf(
        Locale.US,
        "Trained %s in %.2fs | trees=80(d5)+360(d6) | model RAM=%.2f KB%n",
        norm2GbdtGated.name(),
        gbdtTrainSec,
        norm2GbdtGated.modelRamBytes() / 1024.0);

    long tH0 = System.nanoTime();
    TwoStageGbdtNormalizer.populateOutOfFoldScores(fullGen, preparedTrain, gbdtCfg, 4);
    SpanLatticePerceptronNormalizer.TrainConfig hybridCfg =
        new SpanLatticePerceptronNormalizer.TrainConfig();
    hybridCfg.useGbdtHybridFeature = true;
    SpanLatticePerceptronNormalizer normHybrid =
        SpanLatticePerceptronNormalizer.trainFromPrepared(
            "Hybrid: OOF-GBDT + Perceptron (" + trainTag + ")",
            fullGen,
            preparedTrain,
            hybridCfg,
            norm2GbdtFull);
    double hybridTrainSec = (System.nanoTime() - tH0) / 1e9;
    System.out.printf(
        Locale.US,
        "Trained %s (4-fold OOF) in %.2fs | model RAM=%.2f KB%n%n",
        normHybrid.name(),
        hybridTrainSec,
        normHybrid.modelRamBytes() / 1024.0);

    // Pure parallel-table memorization baseline (no Zemberek morph/deasc/suffix/spell)
    TextNormalizer pureMemorizationBaseline =
        buildMemorizationBaseline(minedFull, morphology, trainTag);

    List<TextNormalizer> mainSystems =
        List.of(
            new IdentityNormalizer(),
            wrapZemberek("Zemberek Legacy (Pattern Deasc)", zemberekLegacy, 108L * 1024 * 1024),
            wrapZemberek("Zemberek HEAD (LatticeDeasc)", zemberekHead, 110L * 1024 * 1024),
            pureMemorizationBaseline,
            norm1Perceptron,
            norm2GbdtFull,
            norm2GbdtGated,
            normHybrid);

    // Warmup JIT before benchmarking
    warmupSystems(mainSystems, slice04Eval.subList(0, Math.min(250, slice04Eval.size())));

    // 5. Evaluate Main Systems across all 5 Benchmarks
    List<BenchmarkResult> allMainResults = new ArrayList<>();
    Map<String, List<EvalPair>> evalSuites = new LinkedHashMap<>();
    evalSuites.put(heavyEvalTitle, slice04Eval);
    evalSuites.put("nasty_5k (5K Social)", nasty5kEval);
    evalSuites.put("MultiLexNorm (Test)", multiLexNormEval);
    evalSuites.put("GECTurk Clitics", bounCliticsEval);
    evalSuites.put("GECTurk Clean", bounCleanEval);
    if (!synthAsciiEval.isEmpty()) {
      evalSuites.put("Synth Asciified (1K)", synthAsciiEval);
    }
    if (!synthTypoEval.isEmpty()) {
      evalSuites.put("Synth Typo d=1 (1K)", synthTypoEval);
    }
    if (!synthCleanEval.isEmpty()) {
      evalSuites.put("Synth Clean (1K)", synthCleanEval);
    }

    for (Map.Entry<String, List<EvalPair>> entry : evalSuites.entrySet()) {
      String dsName = entry.getKey();
      List<EvalPair> pairs = entry.getValue();
      List<AlignedSentence> alignedForCleanFpr =
          dsName.equals(heavyEvalTitle) ? heldOutSlice04Aligned : null;
      System.out.println("================================================================================");
      System.out.println("  BENCHMARK: " + dsName + " (" + pairs.size() + " sentences)");
      System.out.println("================================================================================");
      System.out.printf(
          Locale.US,
          "%-38s | %8s | %9s | %9s | %8s | %9s%n",
          "System",
          "W-ERR %",
          "SentEM-Lo",
          "SentEM-Ca",
          "CleanFPR",
          "Tok/sec");
      System.out.println("--------------------------------------------------------------------------------");
      for (TextNormalizer sys : mainSystems) {
        compactSpell.clearCache();
        BenchmarkResult br = evaluateSystem(sys, dsName, pairs, alignedForCleanFpr);
        allMainResults.add(br);
        System.out.printf(
            Locale.US,
            "%-38s | %7.2f%% | %8.2f%% | %8.2f%% | %7.2f%% | %,9.0f%n",
            br.systemName,
            br.wordErrReductionPct,
            br.sentExactLowerPct,
            br.sentExactCasedPct,
            br.cleanTokenFalseCorruptPct,
            br.tokensPerSec);
      }
      System.out.println();
    }

    // 5b. Dedicated Speed, Per-Sentence Latency & 8-Core Parallel Throughput Benchmark
    List<TextNormalizer> speedSystems =
        List.of(
            wrapZemberek("Zemberek Legacy (Pattern Deasc)", zemberekLegacy, 108L * 1024 * 1024),
            wrapZemberek("Zemberek HEAD (LatticeDeasc)", zemberekHead, 110L * 1024 * 1024),
            norm1Perceptron,
            norm2GbdtFull,
            norm2GbdtGated,
            normHybrid);
    Map<String, List<EvalPair>> speedWorkloads = new LinkedHashMap<>();
    speedWorkloads.put(heavyEvalTitle, slice04Eval);
    speedWorkloads.put("nasty_5k (5K Social)", nasty5kEval);
    speedWorkloads.put("GECTurk Clean (Formal)", bounCleanEval);
    List<SpeedBenchmarkResult> speedResults =
        runSpeedAndLatencySuite(speedSystems, speedWorkloads, fullGen);

    List<BenchmarkResult> ablationResults = new ArrayList<>();
    List<BenchmarkResult> scalingResults = new ArrayList<>();
    List<OracleStats> scalingOracles = new ArrayList<>();
    if (!skipSweeps) {
      // 6. Zemberek Tool & Component Ablation Study
      System.out.println("================================================================================");
      System.out.println(
          "  ABLATION STUDY (SpanLatticePerceptron "
              + trainTag
              + " on "
              + heldOutName
              + " & nasty_5k)");
      System.out.println("================================================================================");
      ablationResults =
          runAblationStudy(
              morphology,
              compactSpell,
              lm,
              minedFull,
              trainPool,
              heldOutSlice04Aligned,
              slice04Eval,
              nasty5kEval,
              heldOutName);

      // 7. Data Scaling Law Curve (reusing sharedClean across 2.5K -> ... -> Full N)
      System.out.println("================================================================================");
      System.out.println(
          "  DATA SCALING CURVE: 2.5K -> ... -> " + trainTag + " Parallel Sentences");
      System.out.println("================================================================================");
      runScalingStudy(
          morphology,
          compactSpell,
          lm,
          sharedClean,
          trainPool,
          heldOutSlice04Aligned,
          slice04Eval,
          nasty5kEval,
          bounCliticsEval,
          heldOutName,
          scalingResults,
          scalingOracles);
    }

    // 8. Print Qualitative Examples & Remaining Error Modes
    printQualitativeShowcase(
        nasty5kEval.subList(0, Math.min(200, nasty5kEval.size())),
        zemberekHead,
        norm1Perceptron,
        norm2GbdtGated,
        normHybrid);
    printRemainingFailures(slice04Eval, normHybrid, heldOutName);

    // 9. Persist full JSON report
    Map<String, Object> summaryJson = new LinkedHashMap<>();
    summaryJson.put("version", "v2.3");
    summaryJson.put("heldOutSlice", heldOutName);
    summaryJson.put("trainSlices", loadedSliceNames);
    summaryJson.put("regimeSentenceCounts", regimeSentenceCounts);
    summaryJson.put("trainSentences", trainPool.size());
    summaryJson.put("cleanSentences", minedFull.cleanSentenceCount);
    summaryJson.put("cleanMineSeconds", cleanMineSec);
    summaryJson.put("parallelMineSeconds", parMineSec);
    summaryJson.put("latticePrepSeconds", prepSec);
    summaryJson.put("perceptronTrainSeconds", percTrainSec);
    summaryJson.put("gbdtTrainSeconds", gbdtTrainSec);
    summaryJson.put("hybridTrainSeconds", hybridTrainSec);
    summaryJson.put("oracleFull", oracleFull);
    summaryJson.put("mainResults", allMainResults);
    summaryJson.put("speedResults", speedResults);
    summaryJson.put("ablationResults", ablationResults);
    summaryJson.put("scalingOracles", scalingOracles);
    summaryJson.put("scalingResults", scalingResults);

    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    Path jsonOut = outDir.resolve("norm_v2_benchmark_results.json");
    Files.writeString(jsonOut, gson.toJson(summaryJson), StandardCharsets.UTF_8);
    System.out.println("\nSaved full JSON results to: " + jsonOut);
  }

  private static List<SpeedBenchmarkResult> runSpeedAndLatencySuite(
      List<TextNormalizer> systems,
      Map<String, List<EvalPair>> workloads,
      SpanCandidateGenerator sharedV2Gen)
      throws Exception {
    List<SpeedBenchmarkResult> out = new ArrayList<>();
    ForkJoinPool pool8 = new ForkJoinPool(8);
    try {
      for (Map.Entry<String, List<EvalPair>> entry : workloads.entrySet()) {
        String wlName = entry.getKey();
        List<EvalPair> pairs = entry.getValue();
        int n = pairs.size();
        String[] rawSents = new String[n];
        int totalInputToks = 0;
        for (int i = 0; i < n; i++) {
          rawSents[i] = pairs.get(i).rawText;
          totalInputToks += TurkishTokenizer.DEFAULT.tokenize(rawSents[i]).size();
        }

        System.out.println(
            "======================================================================================================================");
        System.out.printf(
            Locale.US,
            "  SPEED, LATENCY & 8-CORE THROUGHPUT: %s (%,d sents, %,d tokens)%n",
            wlName,
            n,
            totalInputToks);
        System.out.println(
            "======================================================================================================================");
        System.out.printf(
            Locale.US,
            "%-38s | %9s | %9s | %8s | %7s | %7s | %7s | %10s | %6s%n",
            "System",
            "Cold 1T/s",
            "Warm 1T/s",
            "Sent/s",
            "Mean ms",
            "P50 ms",
            "P95 ms",
            "Warm 8T/s",
            "8T Spd");
        System.out.println(
            "----------------------------------------------------------------------------------------------------------------------");

        for (TextNormalizer sys : systems) {
          // 1. Cold-cache 1-thread pass
          sharedV2Gen.clearRuntimeCaches();
          long tCold0 = System.nanoTime();
          int sink = 0;
          for (int i = 0; i < n; i++) {
            sink ^= sys.normalize(rawSents[i]).length();
          }
          double coldSec = Math.max(1e-6, (System.nanoTime() - tCold0) / 1e9);
          double coldTokSec = totalInputToks / coldSec;

          // 2. Warm 1-thread throughput & per-sentence latency (2 passes after cold pass warmed caches/JIT)
          int warmPasses = n < 1_000 ? 4 : 2;
          long[] sentNanos = new long[n * warmPasses];
          long tWarm0 = System.nanoTime();
          int idx = 0;
          for (int pass = 0; pass < warmPasses; pass++) {
            for (int i = 0; i < n; i++) {
              long s0 = System.nanoTime();
              sink ^= sys.normalize(rawSents[i]).length();
              sentNanos[idx++] = System.nanoTime() - s0;
            }
          }
          double warmSec = Math.max(1e-6, (System.nanoTime() - tWarm0) / 1e9);
          double warm1TTokSec = (totalInputToks * (double) warmPasses) / warmSec;
          double warm1TSentSec = (n * (double) warmPasses) / warmSec;

          Arrays.sort(sentNanos);
          double sumMs = 0.0;
          for (long ns : sentNanos) {
            sumMs += ns / 1e6;
          }
          double meanMs = sumMs / sentNanos.length;
          double p50Ms = sentNanos[(int) (0.50 * (sentNanos.length - 1))] / 1e6;
          double p95Ms = sentNanos[(int) (0.95 * (sentNanos.length - 1))] / 1e6;
          double p99Ms = sentNanos[(int) (0.99 * (sentNanos.length - 1))] / 1e6;

          // 3. Warm 8-thread parallel throughput (1 warmup pass + warmPasses timed passes)
          int[] sinkArr = new int[n];
          pool8
              .submit(
                  () ->
                      IntStream.range(0, n)
                          .parallel()
                          .forEach(i -> sinkArr[i] = sys.normalize(rawSents[i]).length()))
              .get();
          long tPar0 = System.nanoTime();
          for (int pass = 0; pass < warmPasses; pass++) {
            pool8
                .submit(
                    () ->
                        IntStream.range(0, n)
                            .parallel()
                            .forEach(i -> sinkArr[i] ^= sys.normalize(rawSents[i]).length()))
                .get();
          }
          double parSec = Math.max(1e-6, (System.nanoTime() - tPar0) / 1e9);
          for (int v : sinkArr) {
            sink ^= v;
          }
          if (sink == 0x7fffffff) {
            System.out.print("");
          }
          double warm8TTokSec = (totalInputToks * (double) warmPasses) / parSec;
          double warm8TSentSec = (n * (double) warmPasses) / parSec;
          double speedup = warm8TTokSec / Math.max(1.0, warm1TTokSec);

          SpeedBenchmarkResult sbr =
              new SpeedBenchmarkResult(
                  sys.name(),
                  wlName,
                  n,
                  totalInputToks,
                  coldTokSec,
                  warm1TTokSec,
                  warm1TSentSec,
                  meanMs,
                  p50Ms,
                  p95Ms,
                  p99Ms,
                  warm8TTokSec,
                  warm8TSentSec,
                  speedup);
          out.add(sbr);
          System.out.printf(
              Locale.US,
              "%-38s | %,9.0f | %,9.0f | %,8.0f | %7.3f | %7.3f | %7.3f | %,10.0f | %5.2fx%n",
              sbr.systemName,
              sbr.cold1TTokPerSec,
              sbr.warm1TTokPerSec,
              sbr.warm1TSentPerSec,
              sbr.meanMsPerSent,
              sbr.p50MsPerSent,
              sbr.p95MsPerSent,
              sbr.warm8TTokPerSec,
              sbr.parallel8TSpeedup);
        }
        System.out.println();
      }
    } finally {
      pool8.shutdown();
    }
    return out;
  }

  private static List<BenchmarkResult> runAblationStudy(
      TurkishMorphology morphology,
      CompactStemEndingSpellChecker compactSpell,
      SmoothLm lm,
      MinedKnowledge minedFull,
      List<AlignedSentence> trainPool,
      List<AlignedSentence> slice04Aligned,
      List<EvalPair> slice04Eval,
      List<EvalPair> nasty5kEval,
      String heldOutName) {

    record AblationSpec(String label, SpanCandidateGenerator.Config cfg) {}
    List<AblationSpec> specs = new ArrayList<>();

    specs.add(new AblationSpec("Full v2.3 LatticePerceptron", SpanCandidateGenerator.Config.full()));

    SpanCandidateGenerator.Config noPar = SpanCandidateGenerator.Config.full();
    noPar.useParallelTables = false;
    specs.add(new AblationSpec("- w/o Parallel Rewrite/Merge Tables", noPar));

    SpanCandidateGenerator.Config noMorph = SpanCandidateGenerator.Config.full();
    noMorph.useZemberekMorphAndDeasc = false;
    specs.add(new AblationSpec("- w/o Zemberek Morph & LatticeDeasc", noMorph));

    SpanCandidateGenerator.Config noSuf = SpanCandidateGenerator.Config.full();
    noSuf.useInducedSuffixRules = false;
    specs.add(new AblationSpec("- w/o Induced Suffix/Hypocoristics", noSuf));

    SpanCandidateGenerator.Config noSplit = SpanCandidateGenerator.Config.full();
    noSplit.useSplitMergeArcs = false;
    specs.add(new AblationSpec("- w/o Multi-Span Split/Merge Arcs", noSplit));

    SpanCandidateGenerator.Config noSpell = SpanCandidateGenerator.Config.full();
    noSpell.useGuardedSpellChecker = false;
    specs.add(new AblationSpec("- w/o Guarded CSR SpellChecker", noSpell));

    List<BenchmarkResult> out = new ArrayList<>();
    SpanLatticePerceptronNormalizer.TrainConfig trainCfg =
        new SpanLatticePerceptronNormalizer.TrainConfig();

    System.out.printf(
        Locale.US,
        "%-36s | %9s | %11s | %11s | %8s%n",
        "Ablation Variant",
        "OracleLo%",
        "Slice04 ERR",
        "Nasty5k ERR",
        "CleanFPR");
    System.out.println("--------------------------------------------------------------------------------");

    for (AblationSpec spec : specs) {
      compactSpell.clearCache();
      SpanCandidateGenerator gen =
          new SpanCandidateGenerator(morphology, compactSpell, lm, minedFull, spec.cfg);
      OracleStats os = computeOracleStats(spec.label, gen, slice04Aligned);
      SpanLatticePerceptronNormalizer model =
          SpanLatticePerceptronNormalizer.train(spec.label, gen, trainPool, trainCfg, null);
      BenchmarkResult r04 =
          evaluateSystem(model, heldOutName, slice04Eval, slice04Aligned);
      BenchmarkResult rNasty = evaluateSystem(model, "nasty_5k", nasty5kEval, null);
      out.add(r04);
      out.add(rNasty);
      System.out.printf(
          Locale.US,
          "%-36s | %8.2f%% | %10.2f%% | %10.2f%% | %7.2f%%%n",
          spec.label,
          os.spanRecallLowerPct,
          r04.wordErrReductionPct,
          rNasty.wordErrReductionPct,
          r04.cleanTokenFalseCorruptPct);
    }
    System.out.println();
    return out;
  }

  private static void runScalingStudy(
      TurkishMorphology morphology,
      CompactStemEndingSpellChecker compactSpell,
      SmoothLm lm,
      CleanCorpusKnowledge sharedClean,
      List<AlignedSentence> trainPool,
      List<AlignedSentence> slice04Aligned,
      List<EvalPair> slice04Eval,
      List<EvalPair> nasty5kEval,
      List<EvalPair> bounCliticsEval,
      String heldOutName,
      List<BenchmarkResult> outResults,
      List<OracleStats> outOracles) {

    int[] candidateSteps = {2_500, 5_000, 10_000, 15_000, 25_000, 35_000, 45_000};
    List<Integer> sizes = new ArrayList<>();
    for (int st : candidateSteps) {
      if (st < trainPool.size()) {
        sizes.add(st);
      }
    }
    sizes.add(trainPool.size());

    System.out.printf(
        Locale.US,
        "%-8s | %9s | %10s | %10s | %10s | %10s | %10s | %10s | %10s%n",
        "Train N",
        "OracleLo%",
        "Perc S04%",
        "GBDT S04%",
        "Hybr S04%",
        "Perc N5k%",
        "GBDT N5k%",
        "Hybr N5k%",
        "Perc Clit%");
    System.out.println(
        "--------------------------------------------------------------------------------------------------------------");

    for (int actualN : sizes) {
      List<AlignedSentence> subTrain = trainPool.subList(0, actualN);
      MinedKnowledge subMined =
          ParallelCorpusMiner.mineWithClean(subTrain, sharedClean, morphology);
      compactSpell.clearCache();
      SpanCandidateGenerator subGen =
          new SpanCandidateGenerator(
              morphology, compactSpell, lm, subMined, SpanCandidateGenerator.Config.full());

      OracleStats os = computeOracleStats("N=" + actualN, subGen, slice04Aligned);
      outOracles.add(os);

      List<PreparedLattice> subPrepared = subGen.prepareTrainingLattices(subTrain);

      SpanLatticePerceptronNormalizer perc =
          SpanLatticePerceptronNormalizer.trainFromPrepared(
              "Perceptron@" + actualN,
              subGen,
              subPrepared,
              new SpanLatticePerceptronNormalizer.TrainConfig(),
              null);

      TwoStageGbdtNormalizer.TrainConfig gCfg = new TwoStageGbdtNormalizer.TrainConfig();
      TwoStageGbdtNormalizer gbdtGated =
          TwoStageGbdtNormalizer.trainFromPrepared("GBDT@" + actualN, subGen, subPrepared, gCfg);
      TwoStageGbdtNormalizer gbdtFull = gbdtGated.withGatekeeper(false, "GBDTFull@" + actualN);

      TwoStageGbdtNormalizer.populateOutOfFoldScores(subGen, subPrepared, gCfg, 4);
      SpanLatticePerceptronNormalizer.TrainConfig hCfg =
          new SpanLatticePerceptronNormalizer.TrainConfig();
      hCfg.useGbdtHybridFeature = true;
      SpanLatticePerceptronNormalizer hybr =
          SpanLatticePerceptronNormalizer.trainFromPrepared(
              "Hybrid@" + actualN, subGen, subPrepared, hCfg, gbdtFull);

      BenchmarkResult rPerc04 = evaluateSystem(perc, heldOutName, slice04Eval, slice04Aligned);
      BenchmarkResult rGbdt04 =
          evaluateSystem(gbdtGated, heldOutName, slice04Eval, slice04Aligned);
      BenchmarkResult rHybr04 = evaluateSystem(hybr, heldOutName, slice04Eval, slice04Aligned);
      BenchmarkResult rPercN5 = evaluateSystem(perc, "nasty_5k", nasty5kEval, null);
      BenchmarkResult rGbdtN5 = evaluateSystem(gbdtGated, "nasty_5k", nasty5kEval, null);
      BenchmarkResult rHybrN5 = evaluateSystem(hybr, "nasty_5k", nasty5kEval, null);
      BenchmarkResult rPercClit = evaluateSystem(perc, "GECTurk Clitics", bounCliticsEval, null);
      BenchmarkResult rGbdtClit =
          evaluateSystem(gbdtGated, "GECTurk Clitics", bounCliticsEval, null);
      BenchmarkResult rHybrClit = evaluateSystem(hybr, "GECTurk Clitics", bounCliticsEval, null);

      outResults.add(rPerc04);
      outResults.add(rGbdt04);
      outResults.add(rHybr04);
      outResults.add(rPercN5);
      outResults.add(rGbdtN5);
      outResults.add(rHybrN5);
      outResults.add(rPercClit);
      outResults.add(rGbdtClit);
      outResults.add(rHybrClit);

      System.out.printf(
          Locale.US,
          "%,8d | %8.2f%% | %9.2f%% | %9.2f%% | %9.2f%% | %9.2f%% | %9.2f%% | %9.2f%% | %9.2f%%%n",
          actualN,
          os.spanRecallLowerPct,
          rPerc04.wordErrReductionPct,
          rGbdt04.wordErrReductionPct,
          rHybr04.wordErrReductionPct,
          rPercN5.wordErrReductionPct,
          rGbdtN5.wordErrReductionPct,
          rHybrN5.wordErrReductionPct,
          rPercClit.wordErrReductionPct);
    }
    System.out.println();
  }

  static BenchmarkResult evaluateSystem(
      TextNormalizer sys,
      String datasetName,
      List<EvalPair> pairs,
      List<AlignedSentence> optionalAligned) {

    int n = pairs.size();
    List<String> preds = new ArrayList<>(n);
    int totalInputToks = 0;
    for (EvalPair ep : pairs) {
      totalInputToks += TurkishTokenizer.DEFAULT.tokenize(ep.rawText).size();
    }

    long t0 = System.nanoTime();
    for (int i = 0; i < n; i++) {
      preds.add(sys.normalize(pairs.get(i).rawText));
    }
    double elapsedSec = Math.max(1e-6, (System.nanoTime() - t0) / 1e9);
    double tokPerSec = totalInputToks / elapsedSec;

    int goldWordsTotal = 0;
    int baseWordEdits = 0;
    int sysWordEdits = 0;
    int exactLower = 0;
    int exactCased = 0;

    int cleanCheckTotal = 0;
    int cleanFalseCorrupt = 0;

    for (int i = 0; i < n; i++) {
      EvalPair ep = pairs.get(i);
      String pred = preds.get(i);

      List<String> rawLowToks = tokenizeLowerWordsAndPunct(ep.rawText);
      List<String> goldLowToks = tokenizeLowerWordsAndPunct(ep.goldText);
      List<String> predLowToks = tokenizeLowerWordsAndPunct(pred);

      goldWordsTotal += goldLowToks.size();
      int bEd = wordLevenshtein(rawLowToks, goldLowToks);
      int sEd = wordLevenshtein(predLowToks, goldLowToks);
      baseWordEdits += bEd;
      sysWordEdits += sEd;

      if (predLowToks.equals(goldLowToks)) {
        exactLower++;
      }
      if (normalizeWhitespace(pred).equals(normalizeWhitespace(ep.goldText))) {
        exactCased++;
      }

      if (bEd == 0) {
        cleanCheckTotal += goldLowToks.size();
        cleanFalseCorrupt += sEd;
      } else if (optionalAligned != null && i < optionalAligned.size()) {
        AlignedSentence as = optionalAligned.get(i);
        if (as.tokens().size() == rawLowToks.size() && predLowToks.size() == rawLowToks.size()) {
          for (int k = 0; k < as.tokens().size(); k++) {
            String surfLow = NormV2Types.trLower(as.tokens().get(k).surface());
            String gLow = NormV2Types.trLower(as.tokens().get(k).norm());
            if (!"Punctuation".equals(as.tokens().get(k).type()) && surfLow.equals(gLow)) {
              cleanCheckTotal++;
              if (!predLowToks.get(k).equals(gLow)) {
                cleanFalseCorrupt++;
              }
            }
          }
        }
      }
    }

    double wErr =
        baseWordEdits > 0
            ? 100.0 * (baseWordEdits - sysWordEdits) / (double) baseWordEdits
            : (sysWordEdits == 0 ? 100.0 : -100.0 * sysWordEdits / Math.max(1.0, goldWordsTotal));
    double cleanFpr =
        cleanCheckTotal > 0 ? 100.0 * cleanFalseCorrupt / (double) cleanCheckTotal : 0.0;

    long ramBytes = 0L;
    if (sys instanceof SpanLatticePerceptronNormalizer p) {
      ramBytes = p.modelRamBytes();
    } else if (sys instanceof TwoStageGbdtNormalizer g) {
      ramBytes = g.modelRamBytes();
    } else if (sys instanceof SizedNormalizer sz) {
      ramBytes = sz.ramBytes;
    }

    return new BenchmarkResult(
        sys.name(),
        datasetName,
        n,
        goldWordsTotal,
        baseWordEdits,
        sysWordEdits,
        wErr,
        100.0 * exactLower / Math.max(1, n),
        100.0 * exactCased / Math.max(1, n),
        cleanFpr,
        tokPerSec,
        ramBytes);
  }

  private static OracleStats computeOracleStats(
      String label, SpanCandidateGenerator gen, List<AlignedSentence> alignedEval) {
    int totalToks = 0;
    long totalArcs = 0;
    int totalSpans = 0;
    int hitLower = 0;
    int hitCased = 0;
    int fullSentLowerHit = 0;

    for (AlignedSentence as : alignedEval) {
      SentenceLattice lat = gen.buildLattice(as.rawText());
      int n = lat.tokens().size();
      if (n == 0 || as.tokens().size() != n) {
        continue;
      }
      totalToks += n;
      for (int i = 0; i < n; i++) {
        totalArcs += lat.outgoing()[i].size();
      }
      boolean sentAllOk = true;
      int i = 0;
      while (i < n) {
        String gSurf = as.tokens().get(i).norm() == null ? "" : as.tokens().get(i).norm().trim();
        if (gSurf.isEmpty()) {
          i++;
          continue;
        }
        int j = i + 1;
        while (j < n
            && (as.tokens().get(j).norm() == null || as.tokens().get(j).norm().trim().isEmpty())) {
          j++;
        }
        totalSpans++;
        String gLow = NormV2Types.trLower(gSurf);
        boolean okLow = false;
        boolean okCased = false;
        for (SpanArc a : lat.outgoing()[i]) {
          if (a.endIdx == j) {
            if (a.normLower.equals(gLow)) {
              okLow = true;
            }
            if (a.normSurface.equals(gSurf)) {
              okCased = true;
            }
          }
        }
        if (okLow) {
          hitLower++;
        } else {
          sentAllOk = false;
        }
        if (okCased) {
          hitCased++;
        }
        i = j;
      }
      if (sentAllOk) {
        fullSentLowerHit++;
      }
    }
    return new OracleStats(
        label,
        alignedEval.size(),
        totalSpans,
        totalArcs / (double) Math.max(1, totalToks),
        100.0 * hitLower / Math.max(1, totalSpans),
        100.0 * hitCased / Math.max(1, totalSpans),
        100.0 * fullSentLowerHit / Math.max(1, alignedEval.size()));
  }

  private static void printOracleStats(OracleStats os) {
    System.out.printf(
        Locale.US,
        "Multi-Span Lattice Oracle [%s]: avgArcs/tok=%.2f | SpanRecall(Lower)=%.2f%% | SpanRecall(Cased)=%.2f%% | FullSentOracle=%.2f%%%n",
        os.label,
        os.avgArcsPerToken,
        os.spanRecallLowerPct,
        os.spanRecallCasedPct,
        os.sentOracleLowerPct);
  }

  private static void printQualitativeShowcase(
      List<EvalPair> samples,
      TurkishSentenceNormalizer zHead,
      TextNormalizer perc,
      TextNormalizer gbdt,
      TextNormalizer hybrid) {
    System.out.println("================================================================================");
    System.out.println("  QUALITATIVE SIDE-BY-SIDE EXAMPLES (nasty_5k unseen sentences)");
    System.out.println("================================================================================");
    int shown = 0;
    for (EvalPair ep : samples) {
      String zOut = zHead.normalize(ep.rawText);
      String pOut = perc.normalize(ep.rawText);
      String gOut = gbdt.normalize(ep.rawText);
      String hOut = hybrid.normalize(ep.rawText);
      boolean zWrong =
          !tokenizeLowerWordsAndPunct(zOut).equals(tokenizeLowerWordsAndPunct(ep.goldText));
      boolean hRight =
          tokenizeLowerWordsAndPunct(hOut).equals(tokenizeLowerWordsAndPunct(ep.goldText));
      if (zWrong && hRight) {
        System.out.printf("RAW    : %s%n", ep.rawText);
        System.out.printf("GOLD   : %s%n", ep.goldText);
        System.out.printf("Z-HEAD : %s%n", zOut);
        System.out.printf("PERC   : %s%n", pOut);
        System.out.printf("GBDT   : %s%n", gOut);
        System.out.printf("HYBRID : %s%n", hOut);
        System.out.println("--------------------------------------------------------------------------------");
        if (++shown >= 8) {
          break;
        }
      }
    }
  }

  private static void printRemainingFailures(
      List<EvalPair> slice04, TextNormalizer bestModel, String heldOutName) {
    System.out.println("================================================================================");
    System.out.println("  SAMPLE REMAINING ERROR CASES OF HYBRID v2.3 ON " + heldOutName);
    System.out.println("================================================================================");
    int shown = 0;
    for (EvalPair ep : slice04) {
      String pred = bestModel.normalize(ep.rawText);
      if (!tokenizeLowerWordsAndPunct(pred).equals(tokenizeLowerWordsAndPunct(ep.goldText))) {
        System.out.printf("RAW  : %s%n", ep.rawText);
        System.out.printf("GOLD : %s%n", ep.goldText);
        System.out.printf("PRED : %s%n", pred);
        System.out.println("--------------------------------------------------------------------------------");
        if (++shown >= 8) {
          break;
        }
      }
    }
  }

  private static TextNormalizer buildMemorizationBaseline(
      MinedKnowledge mined, TurkishMorphology morphology, String trainTag) {
    return new SizedNormalizer(
        "Baseline: Parallel Table Only (" + trainTag + ")", mined.estimatedBytes()) {
      @Override
      public String normalize(String rawSentence) {
        List<SpanToken> toks = NormV2Types.spanTokenize(rawSentence);
        List<SpanArc> path = new ArrayList<>(toks.size());
        int n = toks.size();
        int i = 0;
        while (i < n) {
          SpanToken t = toks.get(i);
          if (i + 1 < n) {
            ParallelCorpusMiner.MergeEntry me =
                mined.parallel2To1.get(t.lower() + "\t" + toks.get(i + 1).lower());
            if (me != null
                && me.mergedTargets().length > 0
                && me.mergeProb(me.mergedTargets()[0]) > 0.55f) {
              String nl = me.mergedTargets()[0].normLower();
              String surf =
                  mined.truecase(nl, t.shape(), t.surface(), i == 0, false, morphology);
              path.add(SpanArc.plain(i, i + 2, surf, nl, NormV2Types.SRC_PARALLEL_MERGE));
              i += 2;
              continue;
            }
          }
          ParallelCorpusMiner.RewriteEntry re = mined.parallel1ToN.get(t.lower());
          String chosenLow = t.lower();
          if (re != null
              && re.targets().length > 0
              && re.probOf(re.targets()[0].normLower()) > 0.52f) {
            chosenLow = re.targets()[0].normLower();
          }
          String surf =
              t.isImmutable()
                  ? t.surface()
                  : mined.truecase(chosenLow, t.shape(), t.surface(), i == 0, false, morphology);
          path.add(SpanArc.plain(i, i + 1, surf, chosenLow, NormV2Types.SRC_IDENTITY));
          i++;
        }
        return NormV2Types.reconstructText(toks, path);
      }
    };
  }

  private abstract static class SizedNormalizer implements TextNormalizer {
    final String label;
    final long ramBytes;

    SizedNormalizer(String label, long ramBytes) {
      this.label = label;
      this.ramBytes = ramBytes;
    }

    @Override
    public String name() {
      return label;
    }
  }

  private static final class IdentityNormalizer implements TextNormalizer {
    @Override
    public String name() {
      return "Identity (Do-Nothing)";
    }

    @Override
    public String normalize(String rawSentence) {
      return rawSentence;
    }
  }

  private static TextNormalizer wrapZemberek(
      String label, TurkishSentenceNormalizer norm, long estBytes) {
    return new SizedNormalizer(label, estBytes) {
      @Override
      public String normalize(String rawSentence) {
        return norm.normalize(rawSentence);
      }
    };
  }

  private static void warmupSystems(List<TextNormalizer> systems, List<EvalPair> sample) {
    for (TextNormalizer sys : systems) {
      for (EvalPair ep : sample) {
        sys.normalize(ep.rawText);
      }
    }
  }

  private static List<String> tokenizeLowerWordsAndPunct(String text) {
    List<Token> toks = TurkishTokenizer.DEFAULT.tokenize(text);
    List<String> out = new ArrayList<>(toks.size());
    for (Token t : toks) {
      out.add(NormV2Types.trLower(t.getText()));
    }
    return out;
  }

  private static String normalizeWhitespace(String s) {
    return s == null ? "" : s.trim().replace('’', '\'').replaceAll("\\s+", " ");
  }

  private static int wordLevenshtein(List<String> a, List<String> b) {
    int n = a.size();
    int m = b.size();
    if (n == 0) return m;
    if (m == 0) return n;
    int[] prev = new int[m + 1];
    int[] curr = new int[m + 1];
    for (int j = 0; j <= m; j++) {
      prev[j] = j;
    }
    for (int i = 1; i <= n; i++) {
      curr[0] = i;
      String wa = a.get(i - 1);
      for (int j = 1; j <= m; j++) {
        int cost = wa.equals(b.get(j - 1)) ? 0 : 1;
        curr[j] = Math.min(prev[j - 1] + cost, Math.min(curr[j - 1] + 1, prev[j] + 1));
      }
      int[] tmp = prev;
      prev = curr;
      curr = tmp;
    }
    return prev[m];
  }

  static void addEvalFingerprints(Set<String> set, List<EvalPair> pairs) {
    for (EvalPair p : pairs) {
      set.add(ParallelCorpusMiner.sentenceFingerprint(p.rawText));
      set.add(ParallelCorpusMiner.sentenceFingerprint(p.goldText));
    }
  }

  static List<EvalPair> toEvalPairs(List<AlignedSentence> aligned) {
    List<EvalPair> out = new ArrayList<>(aligned.size());
    for (AlignedSentence a : aligned) {
      out.add(new EvalPair(a.id(), a.rawText(), a.goldText()));
    }
    return out;
  }

  static List<EvalPair> loadNasty5kTsv(Path tsvPath) throws IOException {
    List<EvalPair> out = new ArrayList<>(5_000);
    try (BufferedReader br = Files.newBufferedReader(tsvPath, StandardCharsets.UTF_8)) {
      String header = br.readLine();
      if (header == null) {
        return out;
      }
      String line;
      while ((line = br.readLine()) != null) {
        if (line.isEmpty()) {
          continue;
        }
        String[] cols = line.split("\t", -1);
        if (cols.length >= 3) {
          out.add(new EvalPair(cols[0], cols[1], cols[2]));
        }
      }
    }
    return out;
  }

  static List<EvalPair> loadMultiLexNorm(Path normPath) throws IOException {
    List<EvalPair> out = new ArrayList<>(600);
    List<String> rawToks = new ArrayList<>();
    List<String> goldToks = new ArrayList<>();
    int id = 1;
    try (BufferedReader br = Files.newBufferedReader(normPath, StandardCharsets.UTF_8)) {
      String line;
      while ((line = br.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty()) {
          if (!rawToks.isEmpty()) {
            out.add(
                new EvalPair(
                    "mln_" + (id++),
                    String.join(" ", rawToks),
                    String.join(" ", goldToks)));
            rawToks.clear();
            goldToks.clear();
          }
          continue;
        }
        String[] parts = line.split("\t");
        if (parts.length >= 2) {
          rawToks.add(parts[0]);
          goldToks.add(parts[1]);
        }
      }
      if (!rawToks.isEmpty()) {
        out.add(
            new EvalPair(
                "mln_" + id, String.join(" ", rawToks), String.join(" ", goldToks)));
      }
    }
    return out;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  static List<EvalPair>[] loadBounTestSplits(Path srcPath, Path tgtPath)
      throws IOException {
    List<String> sources = new ArrayList<>();
    try (BufferedReader br = Files.newBufferedReader(srcPath, StandardCharsets.UTF_8)) {
      String line;
      while ((line = br.readLine()) != null) {
        if (line.startsWith("S ")) {
          sources.add(line.substring(2).trim());
        }
      }
    }
    List<String> targets = Files.readAllLines(tgtPath, StandardCharsets.UTF_8);
    List<EvalPair> cliticNoisy = new ArrayList<>();
    List<EvalPair> cleanFormal = new ArrayList<>();
    int limit = Math.min(sources.size(), targets.size());
    for (int i = 0; i < limit; i++) {
      String s = sources.get(i).trim();
      String t = targets.get(i).trim();
      if (s.isEmpty() || t.isEmpty()) {
        continue;
      }
      if (s.equals(t)) {
        cleanFormal.add(new EvalPair("boun_clean_" + i, s, t));
      } else {
        cliticNoisy.add(new EvalPair("boun_clitic_" + i, s, t));
      }
    }
    return new List[] {cliticNoisy, cleanFormal};
  }
}
