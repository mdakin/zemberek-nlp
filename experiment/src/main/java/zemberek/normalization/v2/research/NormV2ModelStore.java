package zemberek.normalization.v2.research;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import zemberek.lm.compression.SmoothLm;
import zemberek.morphology.TurkishMorphology;
import zemberek.normalization.TurkishSentenceNormalizer;
import zemberek.normalization.deasciifier.latticedeasciifier.QuantizedWeightTable;
import zemberek.normalization.experiment.CompactStemEndingSpellChecker;
import zemberek.normalization.v2.research.NormV2Types.AlignedSentence;
import zemberek.normalization.v2.research.NormV2Types.PreparedLattice;
import zemberek.normalization.v2.research.ParallelCorpusMiner.CasingCounts;
import zemberek.normalization.v2.research.ParallelCorpusMiner.CharPhonotacticModel;
import zemberek.normalization.v2.research.ParallelCorpusMiner.CleanCorpusKnowledge;
import zemberek.normalization.v2.research.ParallelCorpusMiner.CleanWordLm;
import zemberek.normalization.v2.research.ParallelCorpusMiner.MergeEntry;
import zemberek.normalization.v2.research.ParallelCorpusMiner.MinedKnowledge;
import zemberek.normalization.v2.research.ParallelCorpusMiner.RewriteEntry;
import zemberek.normalization.v2.research.ParallelCorpusMiner.SuffixRule;
import zemberek.normalization.v2.research.ParallelCorpusMiner.TargetCount;

/**
 * Fast binary snapshot loader and builder for the {@code v2.3} Turkish normalization models used by
 * the local Zemberek Web Demo server.
 *
 * <p>On first startup (or when {@code forceRetrain} is requested), mines the 1.8M-sentence clean
 * corpus + 75K multi-regime parallel training pool, trains {@link TwoStageGbdtNormalizer}, {@link
 * SpanLatticePerceptronNormalizer}, and {@code Hybrid}, and saves binary snapshots under {@code
 * cacheDir} so every subsequent server startup finishes in ~2–3 seconds.
 */
public final class NormV2ModelStore {

  private static final int SNAPSHOT_MAGIC = 0x5A4E5632; // "ZNV2"
  private static final int SNAPSHOT_VERSION = 3; // v2.4 (DENSE_DIM=20, RANK_DIM=46, 31 sparse templates)

  private static final Path HEAVY_SLICE_DIR =
      Paths.get(System.getenv().getOrDefault("ZEMBEREK_DATA_DIR", System.getProperty("user.home") + "/data/turkish") + "/normalization/noisy_tr_v2/full_110k/general/llm_gold/normalized");
  private static final Path MEDIUM_SLICE_DIR =
      Paths.get(System.getenv().getOrDefault("ZEMBEREK_DATA_DIR", System.getProperty("user.home") + "/data/turkish") + "/normalization/noisy_tr_v2/medium_110k/general/llm_gold/normalized");
  private static final Path LOW_SLICE_DIR =
      Paths.get(System.getenv().getOrDefault("ZEMBEREK_DATA_DIR", System.getProperty("user.home") + "/data/turkish") + "/normalization/noisy_tr_v2/low_110k/general/llm_gold/normalized");
  private static final String HELD_OUT_TEST_FILE = "test_slice_01_normalized.jsonl";
  private static final Path HELD_OUT_TEST_SLICE_02_V4 =
      Paths.get(System.getenv().getOrDefault("ZEMBEREK_DATA_DIR", System.getProperty("user.home") + "/data/turkish") + "/normalization/noisy_tr_v2/full_110k/general/llm_gold_v4/normalized/test_slice_02_v4_hhigh_normalized.jsonl");

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

  private NormV2ModelStore() {}

  /** Bundle of initialized Zemberek core resources and all {@code v2.3} normalization engines. */
  public record LoadedBundle(
      TurkishMorphology morphology,
      SmoothLm smoothLm,
      CompactStemEndingSpellChecker spellChecker,
      MinedKnowledge mined,
      SpanCandidateGenerator generator,
      TwoStageGbdtNormalizer gbdtGated,
      TwoStageGbdtNormalizer gbdtFull,
      SpanLatticePerceptronNormalizer perceptron,
      SpanLatticePerceptronNormalizer hybrid,
      TurkishSentenceNormalizer zemberekHead,
      boolean loadedFromCache,
      double startupSeconds) {}

  public static LoadedBundle loadOrBuild(Path cacheDir, boolean forceRetrain) throws IOException {
    long t0 = System.nanoTime();
    Files.createDirectories(cacheDir);

    System.out.println("[NormV2ModelStore] Initializing Zemberek Morphology, SmoothLm, and CSR SpellChecker...");
    TurkishMorphology morphology = TurkishMorphology.createWithDefaults();
    Path lmPath = ZEMBEREK_NORM_ROOT.resolve("lm.2gram.slm");
    Path normDataDir = ZEMBEREK_NORM_ROOT.resolve("normalization");
    SmoothLm smoothLm = SmoothLm.builder(lmPath).logBase(Math.E).build();
    CompactStemEndingSpellChecker spellChecker =
        new CompactStemEndingSpellChecker(morphology, true);
    TurkishSentenceNormalizer zemberekHead =
        new TurkishSentenceNormalizer(morphology, normDataDir, lmPath);

    Path minedFile = cacheDir.resolve("mined_v23.bin");
    Path gbdtFile = cacheDir.resolve("gbdt_v23.bin");
    Path percFile = cacheDir.resolve("perceptron_v23.bin");
    Path hybridFile = cacheDir.resolve("hybrid_v23.bin");

    boolean cacheExists =
        !forceRetrain
            && Files.exists(minedFile)
            && Files.exists(gbdtFile)
            && Files.exists(percFile)
            && Files.exists(hybridFile);

    if (cacheExists) {
      try {
        System.out.println("[NormV2ModelStore] Loading cached v2.3 models from " + cacheDir + " ...");
        MinedKnowledge mined = loadMinedKnowledge(minedFile);
        SpanCandidateGenerator generator =
            new SpanCandidateGenerator(
                morphology, spellChecker, smoothLm, mined, SpanCandidateGenerator.Config.full());
        TwoStageGbdtNormalizer gbdtGated =
            loadGbdt(gbdtFile, generator, "Micro-GBDT + Gate (115 KB)", true);
        TwoStageGbdtNormalizer gbdtFull =
            gbdtGated.withGatekeeper(false, "Micro-GBDT Full Lattice (115 KB)");
        SpanLatticePerceptronNormalizer perceptron =
            loadPerceptron(percFile, generator, null, "Perceptron (v2.4)");
        SpanLatticePerceptronNormalizer hybrid =
            loadPerceptron(hybridFile, generator, gbdtFull, "Hybrid (Perceptron + GBDT)");
        double sec = (System.nanoTime() - t0) / 1e9;
        System.out.printf(
            Locale.US,
            "[NormV2ModelStore] Loaded cached models in %.2fs (GBDT=%.1f KB, Perceptron=%.2f MB, Hybrid=%.2f MB)%n",
            sec,
            gbdtGated.modelRamBytes() / 1024.0,
            perceptron.modelRamBytes() / (1024.0 * 1024.0),
            hybrid.modelRamBytes() / (1024.0 * 1024.0));
        return new LoadedBundle(
            morphology,
            smoothLm,
            spellChecker,
            mined,
            generator,
            gbdtGated,
            gbdtFull,
            perceptron,
            hybrid,
            zemberekHead,
            true,
            sec);
      } catch (Exception ex) {
        System.err.println(
            "[NormV2ModelStore] Cache load failed (" + ex.getMessage() + "), rebuilding from corpora...");
      }
    }

    System.out.println("[NormV2ModelStore] Building v2.3 models from 1.8M clean + 75K parallel corpora...");
    Set<String> forbiddenFingerprints = loadForbiddenEvalFingerprints();
    List<AlignedSentence> trainPool = loadMultiRegimeTrainPool(forbiddenFingerprints);
    System.out.printf(
        Locale.US, "[NormV2ModelStore] Training pool ready: %,d sentences%n", trainPool.size());

    List<Path> cleanCorpusFiles =
        List.of(
            CLEAN_DIR.resolve("ntv-750k-sentences.txt"),
            CLEAN_DIR.resolve("www.cnnturk.com.txt"),
            CLEAN_DIR.resolve("www.haberturk.com.txt"),
            CLEAN_DIR.resolve("vikipedi.txt"),
            CLEAN_DIR.resolve("onedio.com.txt"),
            CLEAN_DIR.resolve("open-subtitles.txt"));

    CleanCorpusKnowledge clean =
        ParallelCorpusMiner.mineCleanCorpus(cleanCorpusFiles, 300_000, forbiddenFingerprints);
    MinedKnowledge mined = ParallelCorpusMiner.mineWithClean(trainPool, clean, morphology);

    SpanCandidateGenerator generator =
        new SpanCandidateGenerator(
            morphology, spellChecker, smoothLm, mined, SpanCandidateGenerator.Config.full());
    List<PreparedLattice> prepared = generator.prepareTrainingLattices(trainPool);

    TwoStageGbdtNormalizer.TrainConfig gbdtCfg = new TwoStageGbdtNormalizer.TrainConfig();
    TwoStageGbdtNormalizer gbdtGated =
        TwoStageGbdtNormalizer.trainFromPrepared(
            "Micro-GBDT + Gate (115 KB)", generator, prepared, gbdtCfg);
    TwoStageGbdtNormalizer gbdtFull =
        gbdtGated.withGatekeeper(false, "Micro-GBDT Full Lattice (115 KB)");

    SpanLatticePerceptronNormalizer perceptron =
        SpanLatticePerceptronNormalizer.trainFromPrepared(
            "Perceptron (v2.4)",
            generator,
            prepared,
            new SpanLatticePerceptronNormalizer.TrainConfig(),
            null);

    TwoStageGbdtNormalizer.populateOutOfFoldScores(generator, prepared, gbdtCfg, 4);
    SpanLatticePerceptronNormalizer.TrainConfig hybridCfg =
        new SpanLatticePerceptronNormalizer.TrainConfig();
    hybridCfg.useGbdtHybridFeature = true;
    SpanLatticePerceptronNormalizer hybrid =
        SpanLatticePerceptronNormalizer.trainFromPrepared(
            "Hybrid (Perceptron + GBDT)", generator, prepared, hybridCfg, gbdtFull);

    System.out.println("[NormV2ModelStore] Saving binary model snapshots to " + cacheDir + " ...");
    saveMinedKnowledge(mined, minedFile);
    saveGbdt(gbdtGated, gbdtFile);
    savePerceptron(perceptron, percFile);
    savePerceptron(hybrid, hybridFile);
    generator.clearRuntimeCaches();

    double sec = (System.nanoTime() - t0) / 1e9;
    System.out.printf(Locale.US, "[NormV2ModelStore] Built and cached all models in %.2fs%n", sec);
    return new LoadedBundle(
        morphology,
        smoothLm,
        spellChecker,
        mined,
        generator,
        gbdtGated,
        gbdtFull,
        perceptron,
        hybrid,
        zemberekHead,
        false,
        sec);
  }

  static Set<String> loadForbiddenEvalFingerprints() throws IOException {
    Set<String> forbidden = new HashSet<>(32_768);
    Path testSlice = HEAVY_SLICE_DIR.resolve(HELD_OUT_TEST_FILE);
    if (Files.exists(testSlice)) {
      NormalizationV2BenchmarkRunner.addEvalFingerprints(
          forbidden,
          NormalizationV2BenchmarkRunner.toEvalPairs(NormV2Types.loadAlignedJsonl(testSlice, 0)));
    }
    if (Files.exists(HELD_OUT_TEST_SLICE_02_V4)) {
      NormalizationV2BenchmarkRunner.addEvalFingerprints(
          forbidden,
          NormalizationV2BenchmarkRunner.toEvalPairs(
              NormV2Types.loadAlignedJsonl(HELD_OUT_TEST_SLICE_02_V4, 0)));
    }
    if (Files.exists(NASTY_5K_TSV)) {
      NormalizationV2BenchmarkRunner.addEvalFingerprints(
          forbidden, NormalizationV2BenchmarkRunner.loadNasty5kTsv(NASTY_5K_TSV));
    }
    if (Files.exists(MULTILEXNORM_TEST)) {
      NormalizationV2BenchmarkRunner.addEvalFingerprints(
          forbidden, NormalizationV2BenchmarkRunner.loadMultiLexNorm(MULTILEXNORM_TEST));
    }
    if (Files.exists(BOUN_SOURCE_TEST) && Files.exists(BOUN_TARGET_TEST)) {
      var splits =
          NormalizationV2BenchmarkRunner.loadBounTestSplits(BOUN_SOURCE_TEST, BOUN_TARGET_TEST);
      NormalizationV2BenchmarkRunner.addEvalFingerprints(forbidden, splits[0]);
      NormalizationV2BenchmarkRunner.addEvalFingerprints(forbidden, splits[1]);
    }
    for (Path synthPath : List.of(SYNTH_ASCII_TSV, SYNTH_TYPO_TSV, SYNTH_CLEAN_TSV)) {
      if (Files.exists(synthPath)) {
        NormalizationV2BenchmarkRunner.addEvalFingerprints(
            forbidden, NormalizationV2BenchmarkRunner.loadNasty5kTsv(synthPath));
      }
    }
    return forbidden;
  }

  static List<AlignedSentence> loadMultiRegimeTrainPool(Set<String> forbidden)
      throws IOException {
    record DirSpec(Path dir, String exclude) {}
    List<DirSpec> dirs =
        List.of(
            new DirSpec(HEAVY_SLICE_DIR, HELD_OUT_TEST_FILE),
            new DirSpec(MEDIUM_SLICE_DIR, ""),
            new DirSpec(LOW_SLICE_DIR, ""));
    List<AlignedSentence> out = new ArrayList<>(75_000);
    for (DirSpec ds : dirs) {
      if (!Files.isDirectory(ds.dir())) {
        continue;
      }
      List<Path> files;
      try (Stream<Path> st = Files.list(ds.dir())) {
        files =
            st.filter(
                    p -> {
                      String fn = p.getFileName().toString();
                      return fn.startsWith("train_slice_")
                          && fn.endsWith("_normalized.jsonl")
                          && !fn.equals(ds.exclude());
                    })
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
      }
      for (Path f : files) {
        for (AlignedSentence as : NormV2Types.loadAlignedJsonl(f, 0)) {
          if (!forbidden.contains(ParallelCorpusMiner.sentenceFingerprint(as.rawText()))
              && !forbidden.contains(ParallelCorpusMiner.sentenceFingerprint(as.goldText()))) {
            out.add(as);
          }
        }
      }
    }
    return out;
  }

  private static void saveGbdt(TwoStageGbdtNormalizer gbdt, Path path) throws IOException {
    try (DataOutputStream out =
        new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path), 1 << 16))) {
      out.writeInt(SNAPSHOT_MAGIC);
      out.writeInt(SNAPSHOT_VERSION);
      out.writeFloat(gbdt.gateSkipThreshold());
      writeObliviousEnsemble(gbdt.keepGatekeeper(), out);
      writeObliviousEnsemble(gbdt.candidateReranker(), out);
    }
  }

  private static TwoStageGbdtNormalizer loadGbdt(
      Path path, SpanCandidateGenerator gen, String name, boolean enableGate) throws IOException {
    try (DataInputStream in =
        new DataInputStream(new BufferedInputStream(Files.newInputStream(path), 1 << 16))) {
      checkHeader(in);
      float gateThr = in.readFloat();
      ObliviousGbdt gate = readObliviousEnsemble(in);
      ObliviousGbdt rank = readObliviousEnsemble(in);
      return new TwoStageGbdtNormalizer(name, gen, gate, rank, gateThr, enableGate);
    }
  }

  private static void writeObliviousEnsemble(ObliviousGbdt m, DataOutputStream out)
      throws IOException {
    out.writeInt(m.numFeatures());
    out.writeInt(m.numTrees());
    out.writeInt(m.depth());
    out.writeFloat(m.baseScore());
    for (short sf : m.splitFeatures()) {
      out.writeShort(sf);
    }
    for (float st : m.splitThresholds()) {
      out.writeFloat(st);
    }
    for (float lv : m.leafValues()) {
      out.writeFloat(lv);
    }
  }

  private static ObliviousGbdt readObliviousEnsemble(DataInputStream in) throws IOException {
    int nf = in.readInt();
    int nt = in.readInt();
    int d = in.readInt();
    float base = in.readFloat();
    short[] sf = new short[nt * d];
    float[] st = new float[nt * d];
    float[] lv = new float[nt * (1 << d)];
    for (int i = 0; i < sf.length; i++) {
      sf[i] = in.readShort();
    }
    for (int i = 0; i < st.length; i++) {
      st[i] = in.readFloat();
    }
    for (int i = 0; i < lv.length; i++) {
      lv[i] = in.readFloat();
    }
    return new ObliviousGbdt(nf, nt, d, base, sf, st, lv);
  }

  private static void savePerceptron(SpanLatticePerceptronNormalizer p, Path path)
      throws IOException {
    try (DataOutputStream out =
        new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path), 1 << 16))) {
      out.writeInt(SNAPSHOT_MAGIC);
      out.writeInt(SNAPSHOT_VERSION);
      float[] dw = p.denseWeights();
      out.writeInt(dw.length);
      for (float v : dw) {
        out.writeFloat(v);
      }
      p.sparseWeights().serialize(out);
    }
  }

  private static SpanLatticePerceptronNormalizer loadPerceptron(
      Path path, SpanCandidateGenerator gen, TwoStageGbdtNormalizer optGbdt, String name)
      throws IOException {
    try (DataInputStream in =
        new DataInputStream(new BufferedInputStream(Files.newInputStream(path), 1 << 16))) {
      checkHeader(in);
      int dLen = in.readInt();
      float[] dw = new float[dLen];
      for (int i = 0; i < dLen; i++) {
        dw[i] = in.readFloat();
      }
      QuantizedWeightTable sw = QuantizedWeightTable.deserialize(in);
      return new SpanLatticePerceptronNormalizer(name, gen, dw, sw, optGbdt);
    }
  }

  private static void saveMinedKnowledge(MinedKnowledge m, Path path) throws IOException {
    try (DataOutputStream out =
        new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path), 1 << 20))) {
      out.writeInt(SNAPSHOT_MAGIC);
      out.writeInt(SNAPSHOT_VERSION);
      out.writeInt(m.trainedSentenceCount);
      out.writeInt(m.cleanSentenceCount);

      out.writeInt(m.parallel1ToN.size());
      for (var e : m.parallel1ToN.entrySet()) {
        out.writeUTF(e.getKey());
        RewriteEntry re = e.getValue();
        out.writeInt(re.totalCount());
        out.writeInt(re.keepCount());
        writeTargets(re.targets(), out);
      }

      writeMergeMap(m.parallel2To1, out);
      writeMergeMap(m.parallel3To1, out);

      out.writeInt(m.suffixRulesByLastChar.size());
      for (var e : m.suffixRulesByLastChar.entrySet()) {
        out.writeUTF(e.getKey());
        List<SuffixRule> list = e.getValue();
        out.writeInt(list.size());
        for (SuffixRule sr : list) {
          out.writeUTF(sr.rawSuffix());
          out.writeUTF(sr.normSuffix());
          out.writeInt(sr.support());
          out.writeInt(sr.totalRawSuffixSeen());
          out.writeFloat(sr.precision());
        }
      }

      out.writeInt(m.casingLexicon.size());
      for (var e : m.casingLexicon.entrySet()) {
        out.writeUTF(e.getKey());
        CasingCounts cc = e.getValue();
        out.writeInt(cc.lower);
        out.writeInt(cc.title);
        out.writeInt(cc.upper);
        out.writeInt(cc.mixed);
        out.writeInt(cc.apostropheStem);
        out.writeBoolean(cc.bestMixedSurface != null);
        if (cc.bestMixedSurface != null) {
          out.writeUTF(cc.bestMixedSurface);
          out.writeInt(cc.bestMixedCount);
        }
      }

      writeStrIntMap(m.cleanOpenBigrams, out);
      writeStrIntMap(m.cleanClosedWords, out);
      m.cleanLm.serialize(out);
      m.charPhonotactics.serialize(out);
    }
  }

  private static MinedKnowledge loadMinedKnowledge(Path path) throws IOException {
    try (DataInputStream in =
        new DataInputStream(new BufferedInputStream(Files.newInputStream(path), 1 << 20))) {
      checkHeader(in);
      int trainedSents = in.readInt();
      int cleanSents = in.readInt();

      int n1 = in.readInt();
      Map<String, RewriteEntry> p1 = new HashMap<>(n1 * 4 / 3 + 16);
      for (int i = 0; i < n1; i++) {
        String key = in.readUTF();
        int tot = in.readInt();
        int keep = in.readInt();
        p1.put(key, new RewriteEntry(tot, keep, readTargets(in)));
      }

      Map<String, MergeEntry> p2 = readMergeMap(in);
      Map<String, MergeEntry> p3 = readMergeMap(in);

      int nSufKeys = in.readInt();
      Map<String, List<SuffixRule>> sufMap = new HashMap<>(nSufKeys * 4 / 3 + 16);
      for (int i = 0; i < nSufKeys; i++) {
        String chKey = in.readUTF();
        int cnt = in.readInt();
        List<SuffixRule> rules = new ArrayList<>(cnt);
        for (int j = 0; j < cnt; j++) {
          rules.add(
              new SuffixRule(
                  in.readUTF(), in.readUTF(), in.readInt(), in.readInt(), in.readFloat()));
        }
        sufMap.put(chKey, rules);
      }

      int nCase = in.readInt();
      Map<String, CasingCounts> casing = new HashMap<>(nCase * 4 / 3 + 16);
      for (int i = 0; i < nCase; i++) {
        String w = in.readUTF();
        CasingCounts cc = new CasingCounts();
        cc.lower = in.readInt();
        cc.title = in.readInt();
        cc.upper = in.readInt();
        cc.mixed = in.readInt();
        cc.apostropheStem = in.readInt();
        if (in.readBoolean()) {
          cc.bestMixedSurface = in.readUTF();
          cc.bestMixedCount = in.readInt();
        }
        casing.put(w, cc);
      }

      Map<String, Integer> openBg = readStrIntMap(in);
      Map<String, Integer> closedW = readStrIntMap(in);
      CleanWordLm cleanLm = CleanWordLm.deserialize(in);
      CharPhonotacticModel phono = CharPhonotacticModel.deserialize(in);

      return new MinedKnowledge(
          p1, p2, p3, sufMap, casing, openBg, closedW, cleanLm, phono, trainedSents, cleanSents);
    }
  }

  private static void writeMergeMap(Map<String, MergeEntry> map, DataOutputStream out)
      throws IOException {
    out.writeInt(map.size());
    for (var e : map.entrySet()) {
      out.writeUTF(e.getKey());
      MergeEntry me = e.getValue();
      out.writeInt(me.cooccurCount());
      writeTargets(me.mergedTargets(), out);
    }
  }

  private static Map<String, MergeEntry> readMergeMap(DataInputStream in) throws IOException {
    int sz = in.readInt();
    Map<String, MergeEntry> out = new HashMap<>(sz * 4 / 3 + 16);
    for (int i = 0; i < sz; i++) {
      String k = in.readUTF();
      int co = in.readInt();
      out.put(k, new MergeEntry(co, readTargets(in)));
    }
    return out;
  }

  private static void writeTargets(TargetCount[] arr, DataOutputStream out) throws IOException {
    out.writeInt(arr.length);
    for (TargetCount tc : arr) {
      out.writeUTF(tc.normLower());
      out.writeUTF(tc.bestSurface());
      out.writeInt(tc.count());
    }
  }

  private static TargetCount[] readTargets(DataInputStream in) throws IOException {
    int k = in.readInt();
    TargetCount[] arr = new TargetCount[k];
    for (int i = 0; i < k; i++) {
      arr[i] = new TargetCount(in.readUTF(), in.readUTF(), in.readInt());
    }
    return arr;
  }

  private static void writeStrIntMap(Map<String, Integer> map, DataOutputStream out)
      throws IOException {
    out.writeInt(map.size());
    for (var e : map.entrySet()) {
      out.writeUTF(e.getKey());
      out.writeInt(e.getValue());
    }
  }

  private static Map<String, Integer> readStrIntMap(DataInputStream in) throws IOException {
    int sz = in.readInt();
    Map<String, Integer> out = new HashMap<>(sz * 4 / 3 + 16);
    for (int i = 0; i < sz; i++) {
      out.put(in.readUTF(), in.readInt());
    }
    return out;
  }

  private static void checkHeader(DataInputStream in) throws IOException {
    int magic = in.readInt();
    int ver = in.readInt();
    if (magic != SNAPSHOT_MAGIC || ver != SNAPSHOT_VERSION) {
      throw new IOException(
          "Unexpected snapshot header: magic=" + Integer.toHexString(magic) + " ver=" + ver);
    }
  }
}
