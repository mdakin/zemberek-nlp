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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import zemberek.normalization.dataset.DatasetModels.DomainBucket;
import zemberek.normalization.dataset.DatasetModels.LengthBucket;
import zemberek.normalization.dataset.DatasetModels.MinedCandidate;
import zemberek.normalization.dataset.DatasetModels.NoiseProfile;
import zemberek.normalization.dataset.DatasetModels.NoiseTier;
import zemberek.normalization.dataset.DatasetModels.OutputRecord;
import zemberek.normalization.dataset.DatasetModels.ProfanitySubtype;
import zemberek.normalization.dataset.DatasetModels.RawDocument;

/**
 * Stratified v2 noisy Turkish sentence miner for Zemberek normalization & classification training.
 *
 * <p>Key guarantees:
 * <ul>
 *   <li>Word-length distribution strictly gated to {@code 2-20} words: {@code 15%} Short
 *       ({@code 2-5}), {@code 65%} Core ({@code 6-15}), {@code 20%} Long ({@code 16-20}).</li>
 *   <li>5-tier noise stratification ({@code T0_CLEAN 18%}, {@code T1_LIGHT 32%},
 *       {@code T2_MEDIUM 25%}, {@code T3_HEAVY 15%}, {@code T4_PURE_ASCII 10%}) for General, and
 *       heavy-noise boosted stratification ({@code 70%} medium/heavy/pure-ASCII) for Profanity.</li>
 *   <li>Identically-stratified 5K slice files ({@code slices/train_slice_01.jsonl ..}) ready for
 *       incremental slice-by-slice LLM normalization and training.</li>
 *   <li>Zero leakage against {@code nasty_5k} (kept untouched on the side) and 7 other held-out
 *       normalization, disambiguation, offense, and sentiment benchmarks.</li>
 * </ul>
 */
public final class NoisyCorpusMinerV2 {

  private static final Path DEFAULT_BASE_NORM_DIR =
      Paths.get(System.getenv().getOrDefault("ZEMBEREK_DATA_DIR", System.getProperty("user.home") + "/data/turkish") + "/normalization");
  private static final Path DEFAULT_TURKISH_DATA_ROOT =
      Paths.get(System.getenv().getOrDefault("ZEMBEREK_DATA_DIR", System.getProperty("user.home") + "/data/turkish") + "");

  private NoisyCorpusMinerV2() {}

  public record CliConfig(
      String mode,
      String profile,
      int genTrain,
      int genDev,
      int genTest,
      int profTrain,
      int profDev,
      int profTest,
      int sliceSize,
      int threads,
      double scaleMultiplier,
      Path baseNormDir,
      Path turkishDataRoot,
      Path outDir,
      List<Path> excludeDirs
  ) {
    public int generalTotal() {
      return genTrain + genDev + genTest;
    }

    public int profanityTotal() {
      return profTrain + profDev + profTest;
    }

    public static CliConfig parse(String[] args) {
      String mode = "sample";
      String profile = "heavy";
      for (int i = 0; i + 1 < args.length; i++) {
        if ("--mode".equals(args[i])) {
          mode = args[i + 1].trim().toLowerCase(Locale.ROOT);
        } else if ("--profile".equals(args[i])) {
          profile = args[i + 1].trim().toLowerCase(Locale.ROOT);
        }
      }
      boolean isFull = "full".equals(mode);
      boolean isSecondaryProfile = !"heavy".equals(profile);
      int genTrain = isFull ? 100_000 : 1_000;
      int genDev = isFull ? 5_000 : 100;
      int genTest = isFull ? 5_000 : 100;
      int profTrain = isSecondaryProfile ? 0 : (isFull ? 10_000 : 200);
      int profDev = isSecondaryProfile ? 0 : (isFull ? 1_000 : 25);
      int profTest = isSecondaryProfile ? 0 : (isFull ? 1_000 : 25);
      int sliceSize = isFull ? 5_000 : 500;
      int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
      double scale = isFull
          ? ("medium_and_low".equals(profile) ? 2.60 : (isSecondaryProfile ? 2.20 : 1.80))
          : 0.20;
      Path baseNorm = DEFAULT_BASE_NORM_DIR;
      Path trRoot = DEFAULT_TURKISH_DATA_ROOT;
      Path outDir = null;
      List<Path> excludeDirs = new ArrayList<>();

      for (int i = 0; i < args.length; i++) {
        switch (args[i]) {
          case "--mode", "--profile" -> i++;
          case "--train" -> genTrain = Integer.parseInt(args[++i]);
          case "--dev" -> genDev = Integer.parseInt(args[++i]);
          case "--test" -> genTest = Integer.parseInt(args[++i]);
          case "--prof-train" -> profTrain = Integer.parseInt(args[++i]);
          case "--prof-dev" -> profDev = Integer.parseInt(args[++i]);
          case "--prof-test" -> profTest = Integer.parseInt(args[++i]);
          case "--slice-size" -> sliceSize = Integer.parseInt(args[++i]);
          case "--threads" -> threads = Math.max(1, Integer.parseInt(args[++i]));
          case "--scale" -> scale = Double.parseDouble(args[++i]);
          case "--base-norm-dir" -> baseNorm = Paths.get(args[++i]);
          case "--turkish-data-root" -> trRoot = Paths.get(args[++i]);
          case "--out-dir" -> outDir = Paths.get(args[++i]);
          case "--exclude-dir" -> excludeDirs.add(Paths.get(args[++i]));
          default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
        }
      }
      if (isSecondaryProfile && excludeDirs.isEmpty()) {
        Path existingFull = baseNorm.resolve("noisy_tr_v2/full_110k");
        if (Files.isDirectory(existingFull)) {
          excludeDirs.add(existingFull);
        }
      }
      if (outDir == null) {
        String defaultSub = switch (profile) {
          case "medium" -> isFull ? "medium_110k" : "sample_medium";
          case "low" -> isFull ? "low_110k" : "sample_low";
          case "medium_and_low" -> isFull ? "" : "sample_medium_and_low";
          default -> isFull ? "full_110k" : "sample_preview";
        };
        outDir = defaultSub.isEmpty()
            ? baseNorm.resolve("noisy_tr_v2")
            : baseNorm.resolve("noisy_tr_v2").resolve(defaultSub);
      }
      return new CliConfig(
          mode, profile, genTrain, genDev, genTest, profTrain, profDev, profTest,
          sliceSize, threads, scale, baseNorm, trRoot, outDir, excludeDirs);
    }
  }

  /** Fast O(1) near-deduplicator across exact skeletons, opening-3-word prefixes, and shingles. */
  private static final class NearDeduplicator {
    private static final int MAX_POSTING_LEN = 512;

    private final Set<String> seenSkeletons;
    private final Map<String, Integer> opening3Counts = new HashMap<>(65_536);
    private final Map<Long, int[]> shinglePostings = new HashMap<>(262_144);
    private final Map<Long, Integer> shingleSizes = new HashMap<>(262_144);
    private final List<Integer> acceptedShingleCounts = new ArrayList<>(130_000);
    private final int maxOpening3Repeat;
    private final int maxShortShingleFreq;

    NearDeduplicator(Set<String> sharedSeenSkeletons, int targetTotal) {
      this.seenSkeletons = sharedSeenSkeletons;
      this.maxOpening3Repeat = Math.min(18, Math.max(5, targetTotal / 2_000));
      this.maxShortShingleFreq = Math.max(3, targetTotal / 12_000);
    }

    boolean tryAccept(MinedCandidate c) {
      String skel = c.dedupSkeleton();
      if (skel.isEmpty() || seenSkeletons.contains(skel)) {
        return false;
      }

      int openCap = c.wordCount() <= 3 ? 1 : maxOpening3Repeat;
      int curOpen = opening3Counts.getOrDefault(c.opening3Key(), 0);
      if (curOpen >= openCap) {
        return false;
      }

      long[] hashes = c.shingleHashes();
      if (hashes.length > 0) {
        Map<Integer, Integer> collisions = null;
        for (long h : hashes) {
          int sz = shingleSizes.getOrDefault(h, 0);
          if (sz == 0) {
            continue;
          }
          if (c.lengthBucket() == LengthBucket.SHORT_2_5 && sz >= maxShortShingleFreq) {
            return false;
          }
          int[] posting = shinglePostings.get(h);
          if (collisions == null) {
            collisions = new HashMap<>(16);
          }
          for (int i = 0; i < sz; i++) {
            int prevId = posting[i];
            int hits = collisions.merge(prevId, 1, Integer::sum);
            int prevLen = acceptedShingleCounts.get(prevId);
            int threshold = (hashes.length <= 2 && prevLen <= 2) ? 1 : 2;
            if (hits >= threshold) {
              return false;
            }
          }
        }
      }

      int myId = acceptedShingleCounts.size();
      acceptedShingleCounts.add(hashes.length);
      seenSkeletons.add(skel);
      opening3Counts.put(c.opening3Key(), curOpen + 1);
      for (long h : hashes) {
        int sz = shingleSizes.getOrDefault(h, 0);
        if (sz < MAX_POSTING_LEN) {
          int[] posting = shinglePostings.computeIfAbsent(h, k -> new int[MAX_POSTING_LEN]);
          posting[sz] = myId;
          shingleSizes.put(h, sz + 1);
        }
      }
      return true;
    }
  }

  public static void main(String[] args) throws IOException {
    long t0 = System.currentTimeMillis();
    CliConfig cfg = CliConfig.parse(args);
    Path sourceCorporaDir = cfg.baseNormDir().resolve("source_corpora");
    System.out.printf(
        Locale.ROOT,
        "=== NoisyCorpusMinerV2 [mode=%s, profile=%s, general=%,d (%,d/%,d/%,d), profanity=%,d (%,d/%,d/%,d), slice=%,d, threads=%d, scale=%.2f] ===%n",
        cfg.mode(), cfg.profile(),
        cfg.generalTotal(), cfg.genTrain(), cfg.genDev(), cfg.genTest(),
        cfg.profanityTotal(), cfg.profTrain(), cfg.profDev(), cfg.profTest(),
        cfg.sliceSize(), cfg.threads(), cfg.scaleMultiplier());
    System.out.println("[SRC] " + sourceCorporaDir);
    System.out.println("[OUT] " + cfg.outDir());

    // 1. Load anti-leakage vault (including nasty_5k + all held-out test splits)
    ForbiddenLeakageVault vault = new ForbiddenLeakageVault();
    vault.loadAll(cfg.baseNormDir(), cfg.turkishDataRoot());

    // 2. Initialize emoji-aware Zemberek analyzer & unified source ingestor
    ProfanityAndNoiseAnalyzer analyzer = new ProfanityAndNoiseAnalyzer(cfg.baseNormDir(), vault);
    RawCorpusIngestor ingestor = new RawCorpusIngestor(sourceCorporaDir);

    // General 3D pools: LengthBucket x NoiseTier x DomainBucket
    Map<LengthBucket, Map<NoiseTier, Map<DomainBucket, List<MinedCandidate>>>> generalPools =
        new EnumMap<>(LengthBucket.class);
    for (LengthBucket lb : LengthBucket.values()) {
      Map<NoiseTier, Map<DomainBucket, List<MinedCandidate>>> byTier =
          new EnumMap<>(NoiseTier.class);
      for (NoiseTier nt : NoiseTier.values()) {
        Map<DomainBucket, List<MinedCandidate>> byDom = new EnumMap<>(DomainBucket.class);
        for (DomainBucket db : DomainBucket.values()) {
          byDom.put(db, new ArrayList<>());
        }
        byTier.put(nt, byDom);
      }
      generalPools.put(lb, byTier);
    }

    // Profanity 3D pools: ProfanitySubtype x LengthBucket x NoiseTier
    Map<ProfanitySubtype, Map<LengthBucket, Map<NoiseTier, List<MinedCandidate>>>> profanityPools =
        new EnumMap<>(ProfanitySubtype.class);
    for (ProfanitySubtype ps : ProfanitySubtype.values()) {
      Map<LengthBucket, Map<NoiseTier, List<MinedCandidate>>> byLb =
          new EnumMap<>(LengthBucket.class);
      for (LengthBucket lb : LengthBucket.values()) {
        Map<NoiseTier, List<MinedCandidate>> byNt = new EnumMap<>(NoiseTier.class);
        for (NoiseTier nt : NoiseTier.values()) {
          byNt.put(nt, new ArrayList<>());
        }
        byLb.put(lb, byNt);
      }
      profanityPools.put(ps, byLb);
    }

    Set<String> ingestSeenGeneral = new HashSet<>(500_000);
    Set<String> ingestSeenProf = new HashSet<>(60_000);
    final long[] docCounter = {0};
    final long[] candCounter = {0};
    final long[] nextLogAt = {50_000};

    final int batchDocs = 8_192;
    List<RawDocument> pendingDocs = new ArrayList<>(batchDocs);
    ExecutorService pool = Executors.newFixedThreadPool(cfg.threads());

    Runnable flushBatch = () -> {
      if (pendingDocs.isEmpty()) {
        return;
      }
      int n = pendingDocs.size();
      int subSize = Math.max(64, (n + cfg.threads() - 1) / cfg.threads());
      List<Future<List<List<MinedCandidate>>>> futures = new ArrayList<>(cfg.threads());
      for (int start = 0; start < n; start += subSize) {
        final List<RawDocument> slice =
            new ArrayList<>(pendingDocs.subList(start, Math.min(n, start + subSize)));
        futures.add(pool.submit(() -> {
          List<List<MinedCandidate>> res = new ArrayList<>(slice.size());
          for (RawDocument d : slice) {
            res.add(analyzer.extractCandidates(d));
          }
          return res;
        }));
      }
      pendingDocs.clear();

      // Gather in deterministic input order
      for (Future<List<List<MinedCandidate>>> fut : futures) {
        List<List<MinedCandidate>> subRes;
        try {
          subRes = fut.get();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        } catch (ExecutionException e) {
          throw new RuntimeException(e.getCause());
        }
        for (List<MinedCandidate> cands : subRes) {
          docCounter[0]++;
          for (MinedCandidate c : cands) {
            candCounter[0]++;
            if (c.isProfanityTrack()) {
              if (ingestSeenProf.add(c.dedupSkeleton())) {
                profanityPools.get(c.profanitySubtype())
                    .get(c.lengthBucket())
                    .get(c.noiseTier())
                    .add(c);
              }
            } else {
              if (c.profanitySubtype() == ProfanitySubtype.BENIGN_HARD_NEGATIVE) {
                if (ingestSeenProf.add(c.dedupSkeleton())) {
                  profanityPools.get(ProfanitySubtype.BENIGN_HARD_NEGATIVE)
                      .get(c.lengthBucket())
                      .get(c.noiseTier())
                      .add(c);
                }
              }
              if (ingestSeenGeneral.add(c.dedupSkeleton())) {
                generalPools.get(c.lengthBucket()).get(c.noiseTier()).get(c.domain()).add(c);
              }
            }
          }
        }
      }
      if (docCounter[0] >= nextLogAt[0]) {
        System.out.printf(
            Locale.ROOT,
            "  ... processed %,d raw docs (general pool=%,d, profanity pool=%,d)%n",
            docCounter[0], ingestSeenGeneral.size(), ingestSeenProf.size());
        nextLogAt[0] = ((docCounter[0] / 50_000) + 1) * 50_000;
      }
    };

    boolean includeCleanFormal = !"heavy".equals(cfg.profile());
    System.out.printf(
        Locale.ROOT,
        "[INGEST] Streaming split-safe corpora from source_corpora/ (includeCleanFormal=%s) across %d worker threads...%n",
        includeCleanFormal, cfg.threads());
    try {
      ingestor.ingestAll(cfg.scaleMultiplier(), includeCleanFormal, doc -> {
        pendingDocs.add(doc);
        if (pendingDocs.size() >= batchDocs) {
          flushBatch.run();
        }
      });
      flushBatch.run();
    } finally {
      pool.shutdown();
    }

    System.out.printf(
        Locale.ROOT,
        "[INGEST] Done in %.1fs: %,d raw docs -> %,d valid candidates (general unique=%,d, profanity+hardneg=%,d).%n",
        (System.currentTimeMillis() - t0) / 1000.0,
        docCounter[0], candCounter[0], ingestSeenGeneral.size(), ingestSeenProf.size());

    // Sort & interleave by domain/source deterministically
    for (LengthBucket lb : LengthBucket.values()) {
      for (NoiseTier nt : NoiseTier.values()) {
        for (DomainBucket db : DomainBucket.values()) {
          List<MinedCandidate> cell = generalPools.get(lb).get(nt).get(db);
          generalPools.get(lb).get(nt).put(db, rankAndInterleave(cell, false));
        }
      }
    }
    for (ProfanitySubtype ps : ProfanitySubtype.values()) {
      for (LengthBucket lb : LengthBucket.values()) {
        for (NoiseTier nt : NoiseTier.values()) {
          List<MinedCandidate> cell = profanityPools.get(ps).get(lb).get(nt);
          profanityPools.get(ps).get(lb).put(nt, rankAndInterleave(cell, true));
        }
      }
    }

    Set<String> globalUsedSkeletons =
        new HashSet<>(cfg.generalTotal() * 2 + cfg.profanityTotal() + 131_072);
    for (Path exDir : cfg.excludeDirs()) {
      int before = globalUsedSkeletons.size();
      loadExistingJsonlSkeletons(exDir, globalUsedSkeletons);
      System.out.printf(
          Locale.ROOT,
          "[DEDUP] Excluded %,d existing sentence skeletons from %s%n",
          globalUsedSkeletons.size() - before,
          exDir);
    }

    if ("medium_and_low".equals(cfg.profile())) {
      boolean isFull = "full".equals(cfg.mode());
      Path medRoot = cfg.outDir().resolve(isFull ? "medium_110k" : "sample_medium");
      Path lowRoot = cfg.outDir().resolve(isFull ? "low_110k" : "sample_low");

      List<MinedCandidate> selectedMedium = selectGeneralTrack(
          generalPools, NoiseProfile.MEDIUM, cfg.generalTotal(), globalUsedSkeletons);
      writeTrackOutputs(
          medRoot.resolve("general"),
          "general_medium",
          NoiseProfile.MEDIUM.recordPrefix,
          selectedMedium,
          cfg.genTrain(),
          cfg.genDev(),
          cfg.genTest(),
          cfg.sliceSize(),
          c -> c.lengthBucket().id + "|" + c.noiseTier().id + "|" + c.domain().id);

      List<MinedCandidate> selectedLow = selectGeneralTrack(
          generalPools, NoiseProfile.LOW, cfg.generalTotal(), globalUsedSkeletons);
      writeTrackOutputs(
          lowRoot.resolve("general"),
          "general_low",
          NoiseProfile.LOW.recordPrefix,
          selectedLow,
          cfg.genTrain(),
          cfg.genDev(),
          cfg.genTest(),
          cfg.sliceSize(),
          c -> c.lengthBucket().id + "|" + c.noiseTier().id + "|" + c.domain().id);

      System.out.printf(
          Locale.ROOT,
          "=== Completed in %.1fs: medium=%,d (%s), low=%,d (%s) ===%n",
          (System.currentTimeMillis() - t0) / 1000.0,
          selectedMedium.size(), medRoot,
          selectedLow.size(), lowRoot);
      return;
    }

    NoiseProfile activeProfile = switch (cfg.profile()) {
      case "medium" -> NoiseProfile.MEDIUM;
      case "low" -> NoiseProfile.LOW;
      default -> NoiseProfile.HEAVY;
    };

    // 3. Select Separate Profanity Track first if requested (heavy-noise boosted!)
    List<MinedCandidate> selectedProfanity = cfg.profanityTotal() > 0
        ? selectProfanityTrack(profanityPools, cfg.profanityTotal(), globalUsedSkeletons)
        : List.of();

    // 4. Select General Track (100% profanity-free; exact 15% / 65% / 20% length quotas)
    List<MinedCandidate> selectedGeneral = selectGeneralTrack(
        generalPools, activeProfile, cfg.generalTotal(), globalUsedSkeletons);

    // 5. Stratified train/dev/test splits + identically-stratified 5K slices
    String trackLabel = activeProfile == NoiseProfile.HEAVY
        ? "general"
        : "general_" + activeProfile.id;
    writeTrackOutputs(
        cfg.outDir().resolve("general"),
        trackLabel,
        activeProfile.recordPrefix,
        selectedGeneral,
        cfg.genTrain(),
        cfg.genDev(),
        cfg.genTest(),
        cfg.sliceSize(),
        c -> c.lengthBucket().id + "|" + c.noiseTier().id + "|" + c.domain().id);

    if (!selectedProfanity.isEmpty()) {
      writeTrackOutputs(
          cfg.outDir().resolve("profanity"),
          "profanity",
          "prof",
          selectedProfanity,
          cfg.profTrain(),
          cfg.profDev(),
          cfg.profTest(),
          cfg.sliceSize(),
          c -> (c.profanitySubtype() != null ? c.profanitySubtype().id : "other")
              + "|" + c.noiseTier().id + "|" + c.lengthBucket().id);
    }

    System.out.printf(
        Locale.ROOT,
        "=== Completed in %.1fs: general(%s)=%,d sentences, profanity=%,d sentences -> %s ===%n",
        (System.currentTimeMillis() - t0) / 1000.0,
        activeProfile.id,
        selectedGeneral.size(),
        selectedProfanity.size(),
        cfg.outDir());
  }

  private static void loadExistingJsonlSkeletons(Path dirOrFile, Set<String> out)
      throws IOException {
    if (!Files.exists(dirOrFile)) {
      return;
    }
    if (Files.isRegularFile(dirOrFile)) {
      readSkeletonsFromJsonlFile(dirOrFile, out);
      return;
    }
    try (Stream<Path> walk = Files.walk(dirOrFile)) {
      List<Path> jsonls = walk
          .filter(Files::isRegularFile)
          .filter(p -> {
            String fn = p.getFileName().toString();
            return fn.equals("train.jsonl") || fn.equals("dev.jsonl") || fn.equals("test.jsonl");
          })
          .toList();
      for (Path p : jsonls) {
        readSkeletonsFromJsonlFile(p, out);
      }
    }
  }

  private static void readSkeletonsFromJsonlFile(Path file, Set<String> out) throws IOException {
    try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while ((line = br.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        try {
          JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
          if (obj.has("text") && !obj.get("text").isJsonNull()) {
            String skel = ForbiddenLeakageVault.normalizeSkeleton(obj.get("text").getAsString());
            if (!skel.isEmpty()) {
              out.add(skel);
            }
          }
        } catch (Exception ignored) {
          // skip malformed line
        }
      }
    }
  }

  /**
   * Deterministic quality ranking + round-robin interleaving across domain/source so no single
   * upstream sub-corpus monopolizes a cell.
   */
  private static List<MinedCandidate> rankAndInterleave(
      List<MinedCandidate> items, boolean groupByDomainFirst) {
    if (items.size() <= 1) {
      return items;
    }
    Comparator<MinedCandidate> cmp = Comparator
        .comparingDouble((MinedCandidate c) -> c.qualityScore() + jitter(c.dedupSkeleton()))
        .reversed()
        .thenComparing(MinedCandidate::dedupSkeleton);

    Map<String, List<MinedCandidate>> groups = new TreeMap<>();
    for (MinedCandidate c : items) {
      String key = groupByDomainFirst ? c.domain().id : c.sourceId();
      groups.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
    }
    for (List<MinedCandidate> list : groups.values()) {
      list.sort(cmp);
    }

    List<MinedCandidate> interleaved = new ArrayList<>(items.size());
    List<List<MinedCandidate>> queues = new ArrayList<>(groups.values());
    int idx = 0;
    boolean any = true;
    while (any) {
      any = false;
      for (List<MinedCandidate> q : queues) {
        if (idx < q.size()) {
          interleaved.add(q.get(idx));
          any = true;
        }
      }
      idx++;
    }
    return interleaved;
  }

  private static double jitter(String skeleton) {
    int h = skeleton.hashCode() & 0x7fffffff;
    return (h % 1000) / 1000.0 * 0.65;
  }

  private static List<MinedCandidate> selectGeneralTrack(
      Map<LengthBucket, Map<NoiseTier, Map<DomainBucket, List<MinedCandidate>>>> pools,
      NoiseProfile profile,
      int totalTarget,
      Set<String> globalUsedSkeletons) {
    if (totalTarget <= 0) {
      return List.of();
    }
    NearDeduplicator dedup = new NearDeduplicator(globalUsedSkeletons, totalTarget);
    double capsRatio = profile == NoiseProfile.LOW ? 0.005
        : (profile == NoiseProfile.MEDIUM ? 0.02 : 0.04);
    int maxAllCaps = Math.max(1, (int) Math.round(totalTarget * capsRatio));
    final int[] allCapsCount = {0};

    Map<LengthBucket, Integer> lengthQuotas = allocateQuotas(
        totalTarget,
        LengthBucket.values(),
        lb -> lb.targetShare);

    List<MinedCandidate> selected = new ArrayList<>(totalTarget);

    for (LengthBucket lb : LengthBucket.values()) {
      int lbTarget = lengthQuotas.get(lb);
      List<MinedCandidate> lbSelected = new ArrayList<>(lbTarget);

      Map<NoiseTier, Integer> tierQuotas = allocateQuotas(
          lbTarget,
          NoiseTier.values(),
          profile::shareFor);

      Map<NoiseTier, Map<DomainBucket, Integer>> cursors = new EnumMap<>(NoiseTier.class);
      for (NoiseTier nt : NoiseTier.values()) {
        Map<DomainBucket, Integer> domCursors = new EnumMap<>(DomainBucket.class);
        for (DomainBucket db : DomainBucket.values()) {
          domCursors.put(db, 0);
        }
        cursors.put(nt, domCursors);
      }

      for (NoiseTier nt : NoiseTier.values()) {
        int ntTarget = tierQuotas.get(nt);
        if (ntTarget <= 0) {
          continue;
        }
        int ntAdded = 0;
        Map<DomainBucket, Integer> domQuotas = allocateQuotas(
            ntTarget,
            DomainBucket.values(),
            db -> db.targetShare);

        // Stage 1: Exact domain quota inside (lb, nt)
        for (DomainBucket db : DomainBucket.values()) {
          int want = domQuotas.get(db);
          int got = takeFromCell(
              pools.get(lb).get(nt).get(db), cursors.get(nt), db, want,
              dedup, allCapsCount, maxAllCaps, lbSelected);
          ntAdded += got;
        }

        // Stage 2: Spillover across remaining domains inside the SAME (lb, nt) round-robin
        while (ntAdded < ntTarget) {
          boolean progress = false;
          for (DomainBucket db : DomainBucket.values()) {
            if (ntAdded >= ntTarget) {
              break;
            }
            int got = takeFromCell(
                pools.get(lb).get(nt).get(db), cursors.get(nt), db, 1,
                dedup, allCapsCount, maxAllCaps, lbSelected);
            if (got > 0) {
              ntAdded += got;
              progress = true;
            }
          }
          if (!progress) {
            break;
          }
        }
      }

      // Stage 3: Preserve exact LengthBucket share (15% / 65% / 20%) if any rare tier under-filled
      NoiseTier[] fallbackOrder = profile.fallbackOrder();
      while (lbSelected.size() < lbTarget) {
        boolean progress = false;
        for (NoiseTier nt : fallbackOrder) {
          for (DomainBucket db : DomainBucket.values()) {
            if (lbSelected.size() >= lbTarget) {
              break;
            }
            int got = takeFromCell(
                pools.get(lb).get(nt).get(db), cursors.get(nt), db, 1,
                dedup, allCapsCount, maxAllCaps, lbSelected);
            if (got > 0) {
              progress = true;
            }
          }
        }
        if (!progress) {
          break;
        }
      }

      selected.addAll(lbSelected);
    }
    return selected;
  }

  private static int takeFromCell(
      List<MinedCandidate> pool,
      Map<DomainBucket, Integer> domCursors,
      DomainBucket db,
      int want,
      NearDeduplicator dedup,
      int[] allCapsCount,
      int maxAllCaps,
      List<MinedCandidate> out) {
    if (want <= 0) {
      return 0;
    }
    int idx = domCursors.get(db);
    int got = 0;
    while (idx < pool.size() && got < want) {
      MinedCandidate c = pool.get(idx++);
      boolean isCaps = c.phenomena().contains("all_caps");
      if (isCaps && allCapsCount[0] >= maxAllCaps) {
        continue;
      }
      if (dedup.tryAccept(c)) {
        if (isCaps) {
          allCapsCount[0]++;
        }
        out.add(c);
        got++;
      }
    }
    domCursors.put(db, idx);
    return got;
  }

  /**
   * Selects the separate Profanity Track with explicit heavy-noise stratification
   * ({@code 10% T0}, {@code 20% T1}, {@code 30% T2}, {@code 25% T3}, {@code 15% T4} = 70%
   * medium/heavy/ASCII noise + profanity) and an Ekşi Sözlük domain cap ({@code <= 24%}).
   */
  private static List<MinedCandidate> selectProfanityTrack(
      Map<ProfanitySubtype, Map<LengthBucket, Map<NoiseTier, List<MinedCandidate>>>> pools,
      int totalTarget,
      Set<String> globalUsedSkeletons) {
    if (totalTarget <= 0) {
      return List.of();
    }
    NearDeduplicator dedup = new NearDeduplicator(globalUsedSkeletons, Math.max(1_000, totalTarget));
    int maxEksi = Math.max(10, (int) Math.round(totalTarget * 0.30));
    final int[] eksiCount = {0};
    Map<NoiseTier, List<MinedCandidate>> deferredEksiByTier = new EnumMap<>(NoiseTier.class);
    for (NoiseTier nt : NoiseTier.values()) {
      deferredEksiByTier.put(nt, new ArrayList<>());
    }

    Map<ProfanitySubtype, Integer> subQuotas = allocateQuotas(
        totalTarget,
        ProfanitySubtype.values(),
        ps -> ps.targetShare);

    Map<ProfanitySubtype, Map<LengthBucket, Map<NoiseTier, Integer>>> cursors =
        new EnumMap<>(ProfanitySubtype.class);
    for (ProfanitySubtype ps : ProfanitySubtype.values()) {
      Map<LengthBucket, Map<NoiseTier, Integer>> lbMap = new EnumMap<>(LengthBucket.class);
      for (LengthBucket lb : LengthBucket.values()) {
        Map<NoiseTier, Integer> ntMap = new EnumMap<>(NoiseTier.class);
        for (NoiseTier nt : NoiseTier.values()) {
          ntMap.put(nt, 0);
        }
        lbMap.put(lb, ntMap);
      }
      cursors.put(ps, lbMap);
    }

    // Spillover order prioritizes heavy & medium noisy profanity!
    NoiseTier[] noisyFirstOrder = {
        NoiseTier.T3_HEAVY, NoiseTier.T2_MEDIUM, NoiseTier.T4_PURE_ASCII,
        NoiseTier.T1_LIGHT, NoiseTier.T0_CLEAN
    };

    List<MinedCandidate> selected = new ArrayList<>(totalTarget);
    for (ProfanitySubtype ps : ProfanitySubtype.values()) {
      int psTarget = subQuotas.get(ps);
      int psAdded = 0;
      Map<LengthBucket, Integer> lbQuotas = allocateQuotas(
          psTarget,
          LengthBucket.values(),
          lb -> lb.targetShare);

      // Stage 1: Exact (ProfanitySubtype, LengthBucket, NoiseTier) quotas
      for (LengthBucket lb : LengthBucket.values()) {
        int lbTarget = lbQuotas.get(lb);
        int lbAdded = 0;
        Map<NoiseTier, Integer> ntQuotas = allocateQuotas(
            lbTarget,
            NoiseTier.values(),
            nt -> nt.profanityTargetShare);

        for (NoiseTier nt : NoiseTier.values()) {
          int got = takeFromProfTierCell(
              pools.get(ps).get(lb).get(nt),
              cursors.get(ps).get(lb),
              nt,
              ntQuotas.get(nt),
              dedup,
              eksiCount,
              maxEksi,
              deferredEksiByTier.get(nt),
              selected);
          lbAdded += got;
          psAdded += got;
        }

        // Stage 2: Spillover across NoiseTiers (heaviest noise first!) inside same (ps, lb)
        while (lbAdded < lbTarget) {
          boolean progress = false;
          for (NoiseTier nt : noisyFirstOrder) {
            if (lbAdded >= lbTarget) {
              break;
            }
            int got = takeFromProfTierCell(
                pools.get(ps).get(lb).get(nt),
                cursors.get(ps).get(lb),
                nt,
                1,
                dedup,
                eksiCount,
                maxEksi,
                deferredEksiByTier.get(nt),
                selected);
            if (got > 0) {
              lbAdded += got;
              psAdded += got;
              progress = true;
            }
          }
          if (!progress) {
            break;
          }
        }
      }

      // Stage 3: Spillover across LengthBuckets (heaviest noise first!) within same ProfanitySubtype
      while (psAdded < psTarget) {
        boolean progress = false;
        for (NoiseTier nt : noisyFirstOrder) {
          for (LengthBucket lb : LengthBucket.values()) {
            if (psAdded >= psTarget) {
              break;
            }
            int got = takeFromProfTierCell(
                pools.get(ps).get(lb).get(nt),
                cursors.get(ps).get(lb),
                nt,
                1,
                dedup,
                eksiCount,
                maxEksi,
                deferredEksiByTier.get(nt),
                selected);
            if (got > 0) {
              psAdded += got;
              progress = true;
            }
          }
        }
        if (!progress) {
          break;
        }
      }
    }

    // Stage 4: Spillover across offensive subtypes if needed (heavy noise first, including deferred Eksi)
    ProfanitySubtype[] offFallback = {
        ProfanitySubtype.EXPLICIT_VULGAR,
        ProfanitySubtype.ACRONYM_AND_MASKED,
        ProfanitySubtype.TARGETED_INSULT
    };
    while (selected.size() < totalTarget) {
      boolean progress = false;
      for (NoiseTier nt : noisyFirstOrder) {
        for (ProfanitySubtype ps : offFallback) {
          for (LengthBucket lb : LengthBucket.values()) {
            if (selected.size() >= totalTarget) {
              break;
            }
            int got = takeFromProfTierCell(
                pools.get(ps).get(lb).get(nt),
                cursors.get(ps).get(lb),
                nt,
                1,
                dedup,
                eksiCount,
                Integer.MAX_VALUE,
                null,
                selected);
            if (got > 0) {
              progress = true;
            }
          }
        }
        // Also drain any heavy-noise deferred Eksi items for this tier before falling back to lighter tiers!
        List<MinedCandidate> defList = deferredEksiByTier.get(nt);
        while (!defList.isEmpty() && selected.size() < totalTarget) {
          MinedCandidate c = defList.remove(0);
          if (dedup.tryAccept(c)) {
            eksiCount[0]++;
            selected.add(c);
            progress = true;
          }
        }
      }
      if (!progress) {
        break;
      }
    }
    return selected;
  }

  private static int takeFromProfTierCell(
      List<MinedCandidate> pool,
      Map<NoiseTier, Integer> cursors,
      NoiseTier nt,
      int want,
      NearDeduplicator dedup,
      int[] eksiCount,
      int maxEksi,
      List<MinedCandidate> deferredEksi,
      List<MinedCandidate> out) {
    if (want <= 0) {
      return 0;
    }
    int idx = cursors.get(nt);
    int got = 0;
    while (idx < pool.size() && got < want) {
      MinedCandidate c = pool.get(idx++);
      boolean isEksi = c.domain() == DomainBucket.EKSISOZLUK;
      if (isEksi && eksiCount[0] >= maxEksi) {
        if (deferredEksi != null && c.profanitySubtype() != ProfanitySubtype.BENIGN_HARD_NEGATIVE) {
          deferredEksi.add(c);
        }
        continue;
      }
      if (dedup.tryAccept(c)) {
        if (isEksi) {
          eksiCount[0]++;
        }
        out.add(c);
        got++;
      }
    }
    cursors.put(nt, idx);
    return got;
  }

  private interface ShareExtractor<E> {
    double share(E elem);
  }

  private static <E extends Enum<E>> Map<E, Integer> allocateQuotas(
      int total, E[] values, ShareExtractor<E> shareFn) {
    Map<E, Integer> out = new LinkedHashMap<>();
    int assigned = 0;
    double[] remainders = new double[values.length];
    for (int i = 0; i < values.length; i++) {
      double exact = total * shareFn.share(values[i]);
      int floor = (int) Math.floor(exact);
      out.put(values[i], floor);
      assigned += floor;
      remainders[i] = exact - floor;
    }
    while (assigned < total) {
      int bestIdx = 0;
      for (int i = 1; i < values.length; i++) {
        if (remainders[i] > remainders[bestIdx]) {
          bestIdx = i;
        }
      }
      remainders[bestIdx] = -1.0;
      out.put(values[bestIdx], out.get(values[bestIdx]) + 1);
      assigned++;
    }
    return out;
  }

  private interface StratumKeyFn {
    String key(MinedCandidate c);
  }

  private static void writeTrackOutputs(
      Path trackDir,
      String trackName,
      String idPrefix,
      List<MinedCandidate> allSelected,
      int wantTrain,
      int wantDev,
      int wantTest,
      int sliceSize,
      StratumKeyFn stratumFn) throws IOException {
    Path slicesDir = trackDir.resolve("slices");
    Files.createDirectories(slicesDir);

    int n = allSelected.size();
    int totalRequested = Math.max(1, wantTrain + wantDev + wantTest);
    int actualTest = Math.min(n, (int) Math.round((double) n * wantTest / totalRequested));
    int actualDev = Math.min(n - actualTest, (int) Math.round((double) n * wantDev / totalRequested));

    // Stratified split assignment so train/dev/test have identical bucket & tier distributions
    Map<String, List<MinedCandidate>> byStratum = new TreeMap<>();
    for (MinedCandidate c : allSelected) {
      byStratum.computeIfAbsent(stratumFn.key(c), k -> new ArrayList<>()).add(c);
    }

    Random splitRng = new Random(20261114L ^ trackName.hashCode());
    List<MinedCandidate> testList = new ArrayList<>(actualTest + 16);
    List<MinedCandidate> devList = new ArrayList<>(actualDev + 16);
    List<MinedCandidate> trainList = new ArrayList<>(Math.max(16, n - actualTest - actualDev + 16));

    double testAcc = 0.0;
    double devAcc = 0.0;
    for (List<MinedCandidate> group : byStratum.values()) {
      Collections.shuffle(group, splitRng);
      double exactTest = (double) group.size() * actualTest / Math.max(1, n);
      double exactDev = (double) group.size() * actualDev / Math.max(1, n);
      testAcc += exactTest;
      devAcc += exactDev;
      int gTest = (int) Math.round(testAcc);
      testAcc -= gTest;
      int gDev = (int) Math.round(devAcc);
      devAcc -= gDev;

      for (int i = 0; i < group.size(); i++) {
        if (i < gTest && testList.size() < actualTest) {
          testList.add(group.get(i));
        } else if (i < gTest + gDev && devList.size() < actualDev) {
          devList.add(group.get(i));
        } else {
          trainList.add(group.get(i));
        }
      }
    }

    Collections.shuffle(devList, splitRng);
    Collections.shuffle(testList, splitRng);

    // Stratified round-robin sharding of trainList into K identically-distributed slices!
    int effSliceSize = Math.max(1, sliceSize);
    int numTrainSlices = Math.max(1, (trainList.size() + effSliceSize - 1) / effSliceSize);
    List<List<MinedCandidate>> trainSlices = new ArrayList<>(numTrainSlices);
    for (int s = 0; s < numTrainSlices; s++) {
      trainSlices.add(new ArrayList<>(effSliceSize + 8));
    }

    Map<String, List<MinedCandidate>> trainByStratum = new TreeMap<>();
    for (MinedCandidate c : trainList) {
      trainByStratum.computeIfAbsent(stratumFn.key(c), k -> new ArrayList<>()).add(c);
    }
    int dealCursor = 0;
    for (List<MinedCandidate> grp : trainByStratum.values()) {
      Collections.shuffle(grp, splitRng);
      for (MinedCandidate c : grp) {
        trainSlices.get(dealCursor % numTrainSlices).add(c);
        dealCursor++;
      }
    }
    for (List<MinedCandidate> sl : trainSlices) {
      Collections.shuffle(sl, splitRng);
    }

    Gson lineGson = new GsonBuilder().disableHtmlEscaping().create();

    // Build OutputRecords with slice tags and sequential IDs
    List<OutputRecord> trainRecs = new ArrayList<>(trainList.size());
    List<String> sliceManifest = new ArrayList<>();
    int globalTrainIdx = 0;
    for (int s = 0; s < numTrainSlices; s++) {
      String sliceId = String.format(Locale.ROOT, "train_slice_%02d", s + 1);
      List<MinedCandidate> slCands = trainSlices.get(s);
      List<OutputRecord> slRecs = new ArrayList<>(slCands.size());
      for (MinedCandidate c : slCands) {
        String id = String.format(Locale.ROOT, "%s_tr_%06d", idPrefix, ++globalTrainIdx);
        OutputRecord rec = OutputRecord.from(id, "train", sliceId, trackName, c);
        slRecs.add(rec);
        trainRecs.add(rec);
      }
      Path slPath = slicesDir.resolve(sliceId + ".jsonl");
      writeJsonl(slPath, slRecs, lineGson);
      sliceManifest.add(String.format(Locale.ROOT, "slices/%s.jsonl (%,d)", sliceId, slRecs.size()));
    }

    List<OutputRecord> devRecs =
        toSplitRecords(devList, "dev", "dev_01", trackName, idPrefix + "_dv_");
    List<OutputRecord> testRecs =
        toSplitRecords(testList, "test", "test_01", trackName, idPrefix + "_te_");

    writeJsonl(trackDir.resolve("train.jsonl"), trainRecs, lineGson);
    writeJsonl(trackDir.resolve("dev.jsonl"), devRecs, lineGson);
    writeJsonl(trackDir.resolve("test.jsonl"), testRecs, lineGson);
    writeJsonl(slicesDir.resolve("dev_slice_01.jsonl"), devRecs, lineGson);
    writeJsonl(slicesDir.resolve("test_slice_01.jsonl"), testRecs, lineGson);
    sliceManifest.add(String.format(Locale.ROOT, "slices/dev_slice_01.jsonl (%,d)", devRecs.size()));
    sliceManifest.add(String.format(Locale.ROOT, "slices/test_slice_01.jsonl (%,d)", testRecs.size()));

    List<OutputRecord> combined = new ArrayList<>(n);
    combined.addAll(trainRecs);
    combined.addAll(devRecs);
    combined.addAll(testRecs);

    // Write human-readable TSV + plain text preview (all records if <= 5000, else first 1,500)
    List<OutputRecord> inspectSlice =
        combined.size() <= 5_000 ? combined : combined.subList(0, 1_500);
    writeInspectFiles(
        trackDir.resolve("inspect_sample.tsv"),
        trackDir.resolve("inspect_sentences.txt"),
        inspectSlice);

    Map<String, Object> summary = buildSummaryMap(
        trackName, combined, trainRecs.size(), devRecs.size(), testRecs.size(), sliceManifest);
    Gson prettyGson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    Files.writeString(
        trackDir.resolve("summary.json"),
        prettyGson.toJson(summary) + "\n",
        StandardCharsets.UTF_8);
  }

  private static List<OutputRecord> toSplitRecords(
      List<MinedCandidate> list, String split, String slice, String track, String prefix) {
    List<OutputRecord> out = new ArrayList<>(list.size());
    for (int i = 0; i < list.size(); i++) {
      String id = String.format(Locale.ROOT, "%s%06d", prefix, i + 1);
      out.add(OutputRecord.from(id, split, slice, track, list.get(i)));
    }
    return out;
  }

  private static void writeJsonl(Path file, List<OutputRecord> records, Gson gson)
      throws IOException {
    try (BufferedWriter bw = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      for (OutputRecord r : records) {
        bw.write(gson.toJson(r));
        bw.newLine();
      }
    }
  }

  private static void writeInspectFiles(
      Path tsvPath, Path txtPath, List<OutputRecord> records) throws IOException {
    try (BufferedWriter tsv = Files.newBufferedWriter(tsvPath, StandardCharsets.UTF_8);
         BufferedWriter txt = Files.newBufferedWriter(txtPath, StandardCharsets.UTF_8)) {
      tsv.write("id\tsplit\tslice\tlength_bucket\tword_count\tnoise_tier\tprofanity_subtype\t"
          + "domain\tsource_id\tphenomena\tnoisy_tokens\ttext\n");
      for (OutputRecord r : records) {
        String phen = String.join(",", r.phenomena);
        String ntoks = String.join(",", r.noisy_tokens);
        String psub = r.profanity_subtype != null ? r.profanity_subtype : "-";
        String cleanLineText = r.text.replace('\t', ' ').replace('\n', ' ');
        tsv.write(String.format(
            Locale.ROOT,
            "%s\t%s\t%s\t%s\t%d\t%s\t%s\t%s\t%s\t%s\t%s\t%s%n",
            r.id, r.split, r.slice, r.length_bucket, r.word_count, r.noise_tier, psub,
            r.domain, r.source_id, phen, ntoks, cleanLineText));
        txt.write(String.format(
            Locale.ROOT,
            "[%s | %2dw | %s%s | %s] %s%n",
            r.length_bucket,
            r.word_count,
            r.noise_tier,
            r.profanity_subtype != null ? " | " + r.profanity_subtype : "",
            r.source_id,
            cleanLineText));
      }
    }
  }

  private static Map<String, Object> buildSummaryMap(
      String trackName,
      List<OutputRecord> all,
      int trainCount,
      int devCount,
      int testCount,
      List<String> sliceManifest) {
    int total = all.size();
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("track", trackName);
    root.put("total_sentences", total);
    Map<String, Integer> splits = new LinkedHashMap<>();
    splits.put("train", trainCount);
    splits.put("dev", devCount);
    splits.put("test", testCount);
    root.put("splits", splits);
    root.put("slices", sliceManifest);

    Map<String, Integer> lengthCounts = new LinkedHashMap<>();
    for (LengthBucket lb : LengthBucket.values()) {
      lengthCounts.put(lb.id, 0);
    }
    Map<String, Integer> tierCounts = new LinkedHashMap<>();
    for (NoiseTier nt : NoiseTier.values()) {
      tierCounts.put(nt.id, 0);
    }
    Map<String, Integer> domainCounts = new LinkedHashMap<>();
    for (DomainBucket db : DomainBucket.values()) {
      domainCounts.put(db.id, 0);
    }
    Map<String, Integer> profSubtypeCounts = new LinkedHashMap<>();
    Map<String, Integer> sourceCounts = new TreeMap<>();
    Map<String, Integer> phenomCounts = new TreeMap<>();
    Map<String, Integer> profTagCounts = new TreeMap<>();
    Map<Integer, Integer> wordCountHist = new TreeMap<>();
    int withSentiment = 0;
    int withStars = 0;
    int withOffenseGold = 0;
    int withTopic = 0;

    for (OutputRecord r : all) {
      lengthCounts.merge(r.length_bucket, 1, Integer::sum);
      tierCounts.merge(r.noise_tier, 1, Integer::sum);
      domainCounts.merge(r.domain, 1, Integer::sum);
      if (r.profanity_subtype != null) {
        profSubtypeCounts.merge(r.profanity_subtype, 1, Integer::sum);
      }
      sourceCounts.merge(r.source_id, 1, Integer::sum);
      wordCountHist.merge(r.word_count, 1, Integer::sum);
      for (String p : r.phenomena) {
        phenomCounts.merge(p, 1, Integer::sum);
      }
      if (r.profanity_tags != null) {
        for (String pt : r.profanity_tags) {
          profTagCounts.merge(pt, 1, Integer::sum);
        }
      }
      if (r.labels != null) {
        if (r.labels.sentiment != null) withSentiment++;
        if (r.labels.stars != null) withStars++;
        if (r.labels.offense_gold != null) withOffenseGold++;
        if (r.labels.category_or_topic != null) withTopic++;
      }
    }

    root.put("length_buckets", withPercentages(lengthCounts, total));
    root.put("noise_tiers", withPercentages(tierCounts, total));
    root.put("domains", withPercentages(domainCounts, total));
    if (!profSubtypeCounts.isEmpty()) {
      root.put("profanity_subtypes", withPercentages(profSubtypeCounts, total));
      root.put("profanity_tags", profTagCounts);
    }
    root.put("word_count_histogram", wordCountHist);
    root.put("phenomena_counts", phenomCounts);
    root.put("source_counts", sourceCounts);

    Map<String, Integer> labelStats = new LinkedHashMap<>();
    labelStats.put("with_sentiment", withSentiment);
    labelStats.put("with_stars", withStars);
    labelStats.put("with_offense_gold", withOffenseGold);
    labelStats.put("with_category_or_topic", withTopic);
    root.put("downstream_label_coverage", labelStats);
    return root;
  }

  private static Map<String, String> withPercentages(Map<String, Integer> counts, int total) {
    Map<String, String> out = new LinkedHashMap<>();
    int denom = Math.max(1, total);
    for (Map.Entry<String, Integer> e : counts.entrySet()) {
      double pct = 100.0 * e.getValue() / denom;
      out.put(e.getKey(), String.format(Locale.ROOT, "%,d (%.2f%%)", e.getValue(), pct));
    }
    return out;
  }
}
