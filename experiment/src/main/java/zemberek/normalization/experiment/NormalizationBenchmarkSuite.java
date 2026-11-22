package zemberek.normalization.experiment;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.UnaryOperator;
import zemberek.core.text.TextIO;
import zemberek.core.turkish.Turkish;
import zemberek.core.turkish.TurkishAlphabet;
import zemberek.lm.compression.SmoothLm;
import zemberek.morphology.TurkishMorphology;
import zemberek.normalization.CharacterGraphDecoder;
import zemberek.normalization.TurkishSentenceNormalizer;
import zemberek.normalization.TurkishSpellChecker;
import zemberek.tokenization.Token;
import zemberek.tokenization.TurkishTokenizer;

/**
 * End-to-end empirical benchmark and ablation suite comparing existing {@code zemberek-normalization}
 * classes ({@link TurkishSpellChecker}, {@link TurkishSentenceNormalizer}) against our experimental
 * prototypes ({@link CompactStemEndingSpellChecker}, {@link ExperimentalSentenceNormalizer}).
 */
public final class NormalizationBenchmarkSuite {

  private static final class GoldPair {
    final String category;
    final String input;
    final String expectedFormatted;
    final String expectedLowerTokenized;

    GoldPair(String category, String input, String expectedFormatted) {
      this.category = category;
      this.input = input;
      this.expectedFormatted = expectedFormatted;
      this.expectedLowerTokenized = toLowerTokenized(expectedFormatted);
    }
  }

  private static final class SpellCase {
    final String deformed;
    final String left;
    final String right;
    final String gold;

    SpellCase(String deformed, String left, String right, String gold) {
      this.deformed = deformed;
      this.left = left;
      this.right = right;
      this.gold = gold;
    }
  }

  public static void main(String[] args) throws Exception {
    Path workDir =
        args.length > 0
            ? Paths.get(args[0])
            : Paths.get(
                "scratch/norm-data");

    System.out.println("==========================================================================");
    System.out.println("  ZEMBEREK NORMALIZATION: EMPIRICAL BENCHMARK & ABLATION SUITE");
    System.out.println("==========================================================================");

    TurkishMorphology morphology = TurkishMorphology.createWithDefaults();
    ExperimentDataBuilder.NormalizationResources res =
        ExperimentDataBuilder.ensureBuilt(workDir, morphology);

    SmoothLm lm = SmoothLm.builder(res.lmPath).logBase(Math.E).build();
    System.out.printf(
        Locale.US,
        "Loaded 2-gram SmoothLm: order=%d, vocab=%d%n%n",
        lm.getOrder(),
        lm.getVocabulary().size());

    // 1. Run Spell-Checker Microbenchmark (Memory, Build Time, Speed, Recall/Top-K)
    TurkishSpellChecker legacySpellWithDiacritics = runSpellCheckerBenchmarks(morphology, lm);

    // 2. Initialize Baseline & Experimental Sentence Normalizers
    TurkishSentenceNormalizer zemberekLegacyDeasc =
        new TurkishSentenceNormalizer(morphology, res.dataRoot, res.lmPath).useLegacyDeasciifier();
    TurkishSentenceNormalizer zemberekCurrentLattice =
        new TurkishSentenceNormalizer(morphology, res.dataRoot, res.lmPath);

    CompactStemEndingSpellChecker sharedCompactSpell =
        new CompactStemEndingSpellChecker(morphology, true);

    ExperimentalSentenceNormalizer.Config cfgUpstreamEquiv =
        ExperimentalSentenceNormalizer.Config.baselineEquivalent();

    ExperimentalSentenceNormalizer.Config cfgFastOnly =
        ExperimentalSentenceNormalizer.Config.baselineEquivalent();
    cfgFastOnly.useCompactSpellChecker = true;
    cfgFastOnly.useSinglePassPipeline = true;

    ExperimentalSentenceNormalizer.Config cfgNoisyChannel = cfgFastOnly.copy();
    cfgNoisyChannel.useNoisyChannelScoring = true;

    ExperimentalSentenceNormalizer.Config cfgFullLower = cfgNoisyChannel.copy();
    cfgFullLower.useRichTurkishNoiseRules = true;

    ExperimentalSentenceNormalizer.Config cfgFullPreserving = cfgFullLower.copy();
    cfgFullPreserving.preserveCaseAndPunctuation = true;

    ExperimentalSentenceNormalizer.Config cfgS1Only =
        ExperimentalSentenceNormalizer.Config.baselineEquivalent();
    cfgS1Only.useCompactSpellChecker = true;

    ExperimentalSentenceNormalizer expUpstreamEquiv =
        new ExperimentalSentenceNormalizer(
            morphology, lm, sharedCompactSpell, legacySpellWithDiacritics, res.dataRoot, cfgUpstreamEquiv);
    ExperimentalSentenceNormalizer expS1Only =
        new ExperimentalSentenceNormalizer(
            morphology, lm, sharedCompactSpell, legacySpellWithDiacritics, res.dataRoot, cfgS1Only);
    ExperimentalSentenceNormalizer expFastOnly =
        new ExperimentalSentenceNormalizer(
            morphology, lm, sharedCompactSpell, legacySpellWithDiacritics, res.dataRoot, cfgFastOnly);
    ExperimentalSentenceNormalizer expNoisyChannel =
        new ExperimentalSentenceNormalizer(
            morphology, lm, sharedCompactSpell, legacySpellWithDiacritics, res.dataRoot, cfgNoisyChannel);
    ExperimentalSentenceNormalizer expFullLower =
        new ExperimentalSentenceNormalizer(
            morphology, lm, sharedCompactSpell, legacySpellWithDiacritics, res.dataRoot, cfgFullLower);
    ExperimentalSentenceNormalizer expFullPreserving =
        new ExperimentalSentenceNormalizer(
            morphology, lm, sharedCompactSpell, legacySpellWithDiacritics, res.dataRoot, cfgFullPreserving);

    // 3. Run Accuracy, False-Positive & MultiLexNorm ERR Benchmark
    runAccuracyAndErrBenchmarks(
        zemberekLegacyDeasc,
        zemberekCurrentLattice,
        expUpstreamEquiv,
        expFastOnly,
        expNoisyChannel,
        expFullLower,
        expFullPreserving);

    // 4. Run Real-Corpus Throughput & Latency Benchmark
    sharedCompactSpell.clearCache();
    runThroughputBenchmarks(
        zemberekLegacyDeasc,
        zemberekCurrentLattice,
        expS1Only,
        expFastOnly,
        expFullLower,
        expFullPreserving);
  }

  private static TurkishSpellChecker runSpellCheckerBenchmarks(
      TurkishMorphology morphology, SmoothLm lm) throws IOException {
    System.out.println("--------------------------------------------------------------------------");
    System.out.println("1. SPELL CHECKER ARCHITECTURE BENCHMARK (StemEndingGraph vs Compact CSR)");
    System.out.println("--------------------------------------------------------------------------");

    forceGc();
    long memBeforeLegacy = usedHeapBytes();
    long t0 = System.nanoTime();
    TurkishSpellChecker baseSpell = new TurkishSpellChecker(morphology);
    TurkishSpellChecker legacySpell =
        new TurkishSpellChecker(
            morphology, baseSpell.getDecoder(), CharacterGraphDecoder.DIACRITICS_IGNORING_MATCHER);
    long legacyBuildMs = (System.nanoTime() - t0) / 1_000_000;
    forceGc();
    long legacyHeapBytes = Math.max(0L, usedHeapBytes() - memBeforeLegacy);

    forceGc();
    long memBeforeCompact = usedHeapBytes();
    long t1 = System.nanoTime();
    CompactStemEndingSpellChecker compactSpellUncached =
        new CompactStemEndingSpellChecker(
            morphology, TextIO.loadLinesFromResource("endings"), true, 0);
    long compactBuildMs = (System.nanoTime() - t1) / 1_000_000;
    forceGc();
    long compactHeapBytes = Math.max(0L, usedHeapBytes() - memBeforeCompact);
    CompactStemEndingSpellChecker compactSpellCached =
        new CompactStemEndingSpellChecker(morphology, true);

    System.out.printf(
        Locale.US,
        "Legacy StemEndingGraph + Decoder : build=%4d ms | measured heap=~%5.1f MB%n",
        legacyBuildMs,
        legacyHeapBytes / (1024.0 * 1024.0));
    System.out.printf(
        Locale.US,
        "Compact CSR FlatTrie SpellChecker: build=%4d ms | measured heap=~%5.1f MB (estimated arrays=%4.1f MB, nodes=%d, edges=%d)%n",
        compactBuildMs,
        compactHeapBytes / (1024.0 * 1024.0),
        compactSpellUncached.estimatedGraphBytes() / (1024.0 * 1024.0),
        compactSpellUncached.totalNodeCount(),
        compactSpellUncached.totalEdgeCount());

    // Build Set A (1-edit typos in context) & Set B (diacritic + 1-edit typos exposing i/ı & u/ü overwrite bug)
    List<SpellCase> setA = buildContextualTypoSet(800);
    List<SpellCase> setB = buildDiacriticAndTypoSet();

    // Warmup
    for (int i = 0; i < 60; i++) {
      SpellCase sc = setA.get(i % setA.size());
      legacySpell.suggestForWord(sc.deformed, sc.left, sc.right, lm);
      compactSpellUncached.suggestForWord(sc.deformed, sc.left, sc.right, lm, false, 2.2f);
    }

    // Evaluate Set A (800 contextual 1-edit typos)
    evalSpellSet("Set A: 800 1-Edit Typos (Legacy TurkishSpellChecker)", setA,
        sc -> legacySpell.suggestForWord(sc.deformed, sc.left, sc.right, lm));
    compactSpellUncached.resetCounters();
    evalSpellSet("Set A: 800 1-Edit Typos (Compact CSR, Uncached, LM-only)", setA,
        sc -> compactSpellUncached.suggestForWord(sc.deformed, sc.left, sc.right, lm, false, 0.0f));
    System.out.printf(
        Locale.US,
        "   -> Avg morphology.analyze() calls per query in Compact CSR: %.1f%n",
        compactSpellUncached.getRawGraphAnalysesCount() * 1.0 / setA.size());
    evalSpellSet("Set A: 800 1-Edit Typos (Compact CSR, Uncached, LM+EditCost)", setA,
        sc -> compactSpellUncached.suggestForWord(sc.deformed, sc.left, sc.right, lm, false, 2.2f));

    // Evaluate Set B (Diacritic + 1-Edit Typos)
    evalSpellSet("Set B: Diacritic+Typo Words (Legacy DIACRITICS_IGNORING_MATCHER)", setB,
        sc -> legacySpell.suggestForWord(sc.deformed, sc.left, sc.right, lm));
    evalSpellSet("Set B: Diacritic+Typo Words (Compact CSR, Fixed Matcher + EditCost)", setB,
        sc -> compactSpellUncached.suggestForWord(sc.deformed, sc.left, sc.right, lm, true, 2.2f));

    // Evaluate Set C: Real Zipfian noisy OOV words from kadinlar-klubu.txt (2,500 tokens)
    List<String> zipfOov = loadNoisyOovStream(morphology, 2500);
    if (!zipfOov.isEmpty()) {
      long s0 = System.nanoTime();
      int cnt0 = 0;
      for (String w : zipfOov) {
        cnt0 += legacySpell.suggestForWord(w, null, null, lm).size();
      }
      double sec0 = (System.nanoTime() - s0) / 1e9;

      long s1 = System.nanoTime();
      int cnt1 = 0;
      for (String w : zipfOov) {
        cnt1 += compactSpellUncached.suggestForWord(w, null, null, lm, false, 2.2f).size();
      }
      double sec1 = (System.nanoTime() - s1) / 1e9;

      compactSpellCached.clearCache();
      long s2 = System.nanoTime();
      int cnt2 = 0;
      for (String w : zipfOov) {
        cnt2 += compactSpellCached.suggestForWord(w, null, null, lm, false, 2.2f).size();
      }
      double sec2 = (System.nanoTime() - s2) / 1e9;

      System.out.printf(
          Locale.US,
          "Set C: %d Real Forum OOV Tokens:%n"
              + "   Legacy TurkishSpellChecker     : %7.0f words/s (%.2f s, %d cands)%n"
              + "   Compact CSR (No Cache)         : %7.0f words/s (%.2f s, %d cands) [%.1fx faster]%n"
              + "   Compact CSR (16K LRU Cache)    : %7.0f words/s (%.2f s, %d cands) [%.1fx faster]%n%n",
          zipfOov.size(),
          zipfOov.size() / sec0,
          sec0,
          cnt0,
          zipfOov.size() / sec1,
          sec1,
          cnt1,
          sec0 / sec1,
          zipfOov.size() / sec2,
          sec2,
          cnt2,
          sec0 / sec2);
    }
    return legacySpell;
  }

  private static void evalSpellSet(
      String label,
      List<SpellCase> cases,
       java.util.function.Function<SpellCase, List<String>> fn) {
    int top1 = 0;
    int top3 = 0;
    int top5 = 0;
    long t0 = System.nanoTime();
    for (SpellCase sc : cases) {
      List<String> preds = fn.apply(sc);
      int rank = -1;
      for (int i = 0; i < preds.size(); i++) {
        if (preds.get(i).equalsIgnoreCase(sc.gold)
            || preds.get(i).toLowerCase(Turkish.LOCALE).equals(sc.gold.toLowerCase(Turkish.LOCALE))) {
          rank = i;
          break;
        }
      }
      if (rank == 0) {
        top1++;
      }
      if (rank >= 0 && rank < 3) {
        top3++;
      }
      if (rank >= 0 && rank < 5) {
        top5++;
      }
    }
    double sec = (System.nanoTime() - t0) / 1e9;
    int n = cases.size();
    System.out.printf(
        Locale.US,
        "%-68s | %6.0f w/s | Top1=%5.1f%% Top3=%5.1f%% Top5=%5.1f%%%n",
        label,
        n / sec,
        100.0 * top1 / n,
        100.0 * top3 / n,
        100.0 * top5 / n);
  }

  private static void runAccuracyAndErrBenchmarks(
      TurkishSentenceNormalizer zemberekLegacyDeasc,
      TurkishSentenceNormalizer zemberekCurrentLattice,
      ExperimentalSentenceNormalizer expUpstreamEquiv,
      ExperimentalSentenceNormalizer expFastOnly,
      ExperimentalSentenceNormalizer expNoisyChannel,
      ExperimentalSentenceNormalizer expFullLower,
      ExperimentalSentenceNormalizer expFullPreserving) {
    System.out.println("--------------------------------------------------------------------------");
    System.out.println("2. SENTENCE NORMALIZATION ACCURACY, FALSE POSITIVES & MultiLexNorm ERR");
    System.out.println("--------------------------------------------------------------------------");

    List<GoldPair> noisySet = buildNoisyGoldDataset();
    List<GoldPair> cleanSet = buildCleanControlDataset();
    List<GoldPair> combined = new ArrayList<>(noisySet.size() + cleanSet.size());
    combined.addAll(noisySet);
    combined.addAll(cleanSet);

    Map<String, UnaryOperator<String>> systems = new LinkedHashMap<>();
    systems.put("0. Leave-As-Is (Lower+Tokenize Baseline)", NormalizationBenchmarkSuite::toLowerTokenized);
    systems.put("1. Zemberek Legacy (Pattern Deasciifier)", zemberekLegacyDeasc::normalize);
    systems.put("2. Zemberek Branch (LatticeDeasciifier)", zemberekCurrentLattice::normalize);
    systems.put("3. Zemberek Upstream-Equiv (+PR #339/#340)", expUpstreamEquiv::normalize);
    systems.put("4. + Exp S1+S2 (CompactSpell + SinglePass)", expFastOnly::normalize);
    systems.put("5. + Exp A1 (Noisy-Channel Viterbi + SafeMap)", expNoisyChannel::normalize);
    systems.put("6. + Exp A2 (Full: Elong+Abbrv+Colloquial+Morph)", expFullLower::normalize);

    // Compute LAI token edit baseline for ERR calculation
    EvalMetrics laiMetrics = evaluateLower(combined, noisySet.size(), systems.get("0. Leave-As-Is (Lower+Tokenize Baseline)"));
    double laiWordAcc = laiMetrics.overallWordAccuracy();

    System.out.printf(
        Locale.US,
        "Dataset: %d sentences (%d Noisy across 8 categories + %d Clean Control), %d gold tokens%n"
            + "Leave-As-Is Token Accuracy = %.2f%% (Error Rate = %.2f%%)%n%n",
        combined.size(),
        noisySet.size(),
        cleanSet.size(),
        laiMetrics.totalGoldWords,
        100.0 * laiWordAcc,
        100.0 * (1.0 - laiWordAcc));

    System.out.printf(
        Locale.US,
        "%-49s | %9s | %9s | %8s | %10s | %8s%n",
        "System Configuration",
        "NoisySent",
        "CleanSent",
        "CleanFP",
        "WordAcc",
        "ERR (%)");
    System.out.println(
        "--------------------------------------------------+-----------+-----------+----------+------------+---------");

    Map<String, EvalMetrics> allMetrics = new LinkedHashMap<>();
    for (Map.Entry<String, UnaryOperator<String>> entry : systems.entrySet()) {
      EvalMetrics m = evaluateLower(combined, noisySet.size(), entry.getValue());
      allMetrics.put(entry.getKey(), m);
      double wAcc = m.overallWordAccuracy();
      double err = (wAcc - laiWordAcc) / (1.0 - laiWordAcc) * 100.0;
      System.out.printf(
          Locale.US,
          "%-49s | %8.1f%% | %8.1f%% | %3d/%-4d | %9.2f%% | %+7.2f%%%n",
          entry.getKey(),
          100.0 * m.noisyExactSentences / m.noisyTotalSentences,
          100.0 * m.cleanExactSentences / m.cleanTotalSentences,
          m.cleanCorruptedSentences,
          m.cleanTotalSentences,
          100.0 * wAcc,
          err);
    }

    // Print Per-Category Breakdown on the 8 Noisy Categories
    System.out.println("\nPer-Category Noisy Sentence Exact-Match Breakdown:");
    System.out.printf(
        Locale.US,
        "%-24s | %12s | %12s | %12s | %12s | %12s%n",
        "Category",
        "Zemb-Legacy",
        "Zemb-Branch",
        "Zemb-Upstrm",
        "+NoisyChan",
        "+Full(ExpA2)");
    System.out.println(
        "-------------------------+--------------+--------------+--------------+--------------+--------------");
    EvalMetrics mLeg = allMetrics.get("1. Zemberek Legacy (Pattern Deasciifier)");
    EvalMetrics mCur = allMetrics.get("2. Zemberek Branch (LatticeDeasciifier)");
    EvalMetrics mUps = allMetrics.get("3. Zemberek Upstream-Equiv (+PR #339/#340)");
    EvalMetrics mNC = allMetrics.get("5. + Exp A1 (Noisy-Channel Viterbi + SafeMap)");
    EvalMetrics mFull = allMetrics.get("6. + Exp A2 (Full: Elong+Abbrv+Colloquial+Morph)");

    for (String cat : mFull.categoryTotals.keySet()) {
      if ("CLEAN_CONTROL".equals(cat)) {
        continue;
      }
      int tot = mFull.categoryTotals.get(cat);
      System.out.printf(
          Locale.US,
          "%-24s | %4d/%-2d(%3.0f%%) | %4d/%-2d(%3.0f%%) | %4d/%-2d(%3.0f%%) | %4d/%-2d(%3.0f%%) | %4d/%-2d(%3.0f%%)%n",
          cat,
          mLeg.categoryExact.getOrDefault(cat, 0), tot, 100.0 * mLeg.categoryExact.getOrDefault(cat, 0) / tot,
          mCur.categoryExact.getOrDefault(cat, 0), tot, 100.0 * mCur.categoryExact.getOrDefault(cat, 0) / tot,
          mUps.categoryExact.getOrDefault(cat, 0), tot, 100.0 * mUps.categoryExact.getOrDefault(cat, 0) / tot,
          mNC.categoryExact.getOrDefault(cat, 0), tot, 100.0 * mNC.categoryExact.getOrDefault(cat, 0) / tot,
          mFull.categoryExact.getOrDefault(cat, 0), tot, 100.0 * mFull.categoryExact.getOrDefault(cat, 0) / tot);
    }

    // Print Qualitative Examples + Formatting Preservation Benchmark (Exp A3)
    int fmtExactLegacy = 0;
    int fmtExactPreserving = 0;
    for (GoldPair gp : combined) {
      if (zemberekCurrentLattice.normalize(gp.input).equals(gp.expectedFormatted)) {
        fmtExactLegacy++;
      }
      if (expFullPreserving.normalize(gp.input).equals(gp.expectedFormatted)) {
        fmtExactPreserving++;
      }
    }
    System.out.printf(
        Locale.US,
        "%nCase & Punctuation-Preserving Exact Sentence Match (all %d sentences):%n"
            + "   Zemberek Current (forced lowercase + spaced punct): %3d/%d (%5.1f%%)%n"
            + "   Exp A3 (preserveCaseAndPunctuation = true)        : %3d/%d (%5.1f%%)%n%n",
        combined.size(),
        fmtExactLegacy,
        combined.size(),
        100.0 * fmtExactLegacy / combined.size(),
        fmtExactPreserving,
        combined.size(),
        100.0 * fmtExactPreserving / combined.size());

    System.out.println("Sample Qualitative Comparisons (Current Zemberek vs Full Prototype w/ Formatting):");
    String[] demoInputs = {
      "Tmm, yarin Ankara'ya gidicem!",
      "Eveeet bugün çoooook mutluyum, biraz bilgi verirmisinzz?",
      "Ah aynen ya annemde fark etti siz evinizden çıkmayın diyo.",
      "Bnde girmiom hiiiiç derslere, sadece komite zamanı çalışıom.",
      "Gece 12 de uyuyup sabah kalkcağını sanıyorsun ama deil.",
      "Bunu ben de duydum ve komsu bize adres sordu."
    };
    for (String in : demoInputs) {
      System.out.println("  IN   : " + in);
      System.out.println("  ZEMB : " + zemberekCurrentLattice.normalize(in));
      System.out.println("  EXP  : " + expFullPreserving.normalize(in));
    }
    System.out.println();
  }

  private static void runThroughputBenchmarks(
      TurkishSentenceNormalizer zemberekLegacyDeasc,
      TurkishSentenceNormalizer zemberekCurrentLattice,
      ExperimentalSentenceNormalizer expS1Only,
      ExperimentalSentenceNormalizer expFastOnly,
      ExperimentalSentenceNormalizer expFullLower,
      ExperimentalSentenceNormalizer expFullPreserving)
      throws IOException {
    System.out.println("--------------------------------------------------------------------------");
    System.out.println("3. LARGE-SCALE REAL-CORPUS THROUGHPUT & LATENCY BENCHMARK");
    System.out.println("--------------------------------------------------------------------------");

    List<String> noisyCorpus =
        loadRealSentences(
            List.of(
                Paths.get(System.getenv().getOrDefault("ZEMBEREK_DATA_DIR", System.getProperty("user.home") + "/data/turkish") + "/noisy/kadinlar-klubu.txt"),
                Paths.get(System.getenv().getOrDefault("ZEMBEREK_DATA_DIR", System.getProperty("user.home") + "/data/turkish") + "/noisy/www.kizlarsoruyor.com.txt")),
            2000);
    List<String> cleanCorpus =
        loadRealSentences(
            List.of(Paths.get(System.getenv().getOrDefault("ZEMBEREK_DATA_DIR", System.getProperty("user.home") + "/data/turkish") + "/clean/ntv-750k-sentences.txt")),
            2000);

    int noisyWords = countTokens(noisyCorpus);
    int cleanWords = countTokens(cleanCorpus);
    System.out.printf(
        Locale.US,
        "Noisy Corpus: %d forum sentences (%d tokens) | Clean Corpus: %d news sentences (%d tokens)%n%n",
        noisyCorpus.size(),
        noisyWords,
        cleanCorpus.size(),
        cleanWords);

    // Warmup all normalizers
    List<String> warm = noisyCorpus.subList(0, Math.min(150, noisyCorpus.size()));
    for (String s : warm) {
      zemberekLegacyDeasc.normalize(s);
      zemberekCurrentLattice.normalize(s);
      expS1Only.normalize(s);
      expFastOnly.normalize(s);
      expFullLower.normalize(s);
      expFullPreserving.normalize(s);
    }

    Map<String, UnaryOperator<String>> runners = new LinkedHashMap<>();
    runners.put("1. Zemberek Legacy (Pattern Deasciifier)", zemberekLegacyDeasc::normalize);
    runners.put("2. Zemberek Current (LatticeDeasciifier)", zemberekCurrentLattice::normalize);
    runners.put("3. + Exp S1 (Compact CSR SpellChecker + Cache)", expS1Only::normalize);
    runners.put("4. + Exp S1+S2 (+ SinglePass + CleanFastPath)", expFastOnly::normalize);
    runners.put("5. + Exp Full Lower (S1+S2+A1+A2)", expFullLower::normalize);
    runners.put("6. + Exp Full Preserving (S1+S2+A1+A2+A3)", expFullPreserving::normalize);

    System.out.printf(
        Locale.US,
        "%-48s | %16s | %16s | %9s%n",
        "Pipeline Variant",
        "Noisy (tok/s)",
        "Clean (tok/s)",
        "Noisy p95");
    System.out.println(
        "-------------------------------------------------+------------------+------------------+----------");

    double baseNoisyTokSec = 0;
    double baseCleanTokSec = 0;
    for (Map.Entry<String, UnaryOperator<String>> e : runners.entrySet()) {
      BenchStats noisyStats = timeCorpus(noisyCorpus, noisyWords, e.getValue());
      BenchStats cleanStats = timeCorpus(cleanCorpus, cleanWords, e.getValue());
      if (e.getKey().startsWith("2.")) {
        baseNoisyTokSec = noisyStats.tokensPerSec;
        baseCleanTokSec = cleanStats.tokensPerSec;
      }
      String noisyMult =
          baseNoisyTokSec > 0
              ? String.format(Locale.US, "(%.2fx)", noisyStats.tokensPerSec / baseNoisyTokSec)
              : "       ";
      String cleanMult =
          baseCleanTokSec > 0
              ? String.format(Locale.US, "(%.2fx)", cleanStats.tokensPerSec / baseCleanTokSec)
              : "       ";
      System.out.printf(
          Locale.US,
          "%-48s | %8.0f %-7s | %8.0f %-7s | %6.2f ms%n",
          e.getKey(),
          noisyStats.tokensPerSec,
          noisyMult,
          cleanStats.tokensPerSec,
          cleanMult,
          noisyStats.p95Millis);
    }
  }

  private static final class BenchStats {
    final double tokensPerSec;
    final double p95Millis;

    BenchStats(double tokensPerSec, double p95Millis) {
      this.tokensPerSec = tokensPerSec;
      this.p95Millis = p95Millis;
    }
  }

  private static BenchStats timeCorpus(
      List<String> sentences, int totalTokens, UnaryOperator<String> fn) {
    long[] latNs = new long[sentences.size()];
    long t0 = System.nanoTime();
    int sink = 0;
    for (int i = 0; i < sentences.size(); i++) {
      long s = System.nanoTime();
      String out = fn.apply(sentences.get(i));
      latNs[i] = System.nanoTime() - s;
      sink ^= out.length();
    }
    long totalNs = System.nanoTime() - t0;
    if (sink == 42_424_242) {
      System.out.print("");
    }
    Arrays.sort(latNs);
    double p95Ms = latNs[(int) (latNs.length * 0.95)] / 1e6;
    double tokPerSec = totalTokens / (totalNs / 1e9);
    return new BenchStats(tokPerSec, p95Ms);
  }

  private static final class EvalMetrics {
    int noisyTotalSentences = 0;
    int noisyExactSentences = 0;
    int cleanTotalSentences = 0;
    int cleanExactSentences = 0;
    int cleanCorruptedSentences = 0;
    int totalGoldWords = 0;
    int totalWordEdits = 0;
    final Map<String, Integer> categoryTotals = new LinkedHashMap<>();
    final Map<String, Integer> categoryExact = new LinkedHashMap<>();

    double overallWordAccuracy() {
      return Math.max(0.0, 1.0 - (totalWordEdits * 1.0 / totalGoldWords));
    }
  }

  private static EvalMetrics evaluateLower(
      List<GoldPair> dataset, int noisyCount, UnaryOperator<String> fn) {
    EvalMetrics m = new EvalMetrics();
    for (int i = 0; i < dataset.size(); i++) {
      GoldPair gp = dataset.get(i);
      String predRaw = fn.apply(gp.input);
      String predNorm = toLowerTokenized(predRaw);
      boolean exact = predNorm.equals(gp.expectedLowerTokenized);

      m.categoryTotals.merge(gp.category, 1, Integer::sum);
      if (exact) {
        m.categoryExact.merge(gp.category, 1, Integer::sum);
      }

      if (i < noisyCount) {
        m.noisyTotalSentences++;
        if (exact) {
          m.noisyExactSentences++;
        }
      } else {
        m.cleanTotalSentences++;
        if (exact) {
          m.cleanExactSentences++;
        } else {
          m.cleanCorruptedSentences++;
        }
      }

      String[] goldToks = gp.expectedLowerTokenized.split(" ");
      String[] predToks = predNorm.isEmpty() ? new String[0] : predNorm.split(" ");
      m.totalGoldWords += goldToks.length;
      m.totalWordEdits += wordLevenshtein(goldToks, predToks);
    }
    return m;
  }

  private static int wordLevenshtein(String[] a, String[] b) {
    int n = a.length;
    int m = b.length;
    int[] prev = new int[m + 1];
    int[] cur = new int[m + 1];
    for (int j = 0; j <= m; j++) {
      prev[j] = j;
    }
    for (int i = 1; i <= n; i++) {
      cur[0] = i;
      for (int j = 1; j <= m; j++) {
        int cost = a[i - 1].equals(b[j - 1]) ? 0 : 1;
        cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
      }
      int[] tmp = prev;
      prev = cur;
      cur = tmp;
    }
    return prev[m];
  }

  private static String toLowerTokenized(String s) {
    List<Token> toks = TurkishTokenizer.DEFAULT.tokenize(s.toLowerCase(Turkish.LOCALE));
    List<String> out = new ArrayList<>(toks.size());
    for (Token t : toks) {
      out.add(t.getText());
    }
    return String.join(" ", out);
  }

  private static List<SpellCase> buildContextualTypoSet(int maxCases) throws IOException {
    List<String> sentences = TextIO.loadLinesFromResource("spell-checker-test-small.txt");
    Random rnd = new Random(42);
    String nearChars = "aeıioöuübcçdfgğhjklmnprsştvyz";
    List<SpellCase> out = new ArrayList<>(maxCases);

    while (out.size() < maxCases) {
      for (String sentence : sentences) {
        List<String> toks = TurkishSpellChecker.tokenizeForSpelling(sentence);
        for (int i = 0; i < toks.size() && out.size() < maxCases; i++) {
          String w = toks.get(i);
          if (w.length() < 5 || w.indexOf('\'') >= 0 || hasDigits(w)) {
            continue;
          }
          String def = deformSingleEdit(w, rnd, nearChars);
          if (def.equals(w)) {
            continue;
          }
          String left = i == 0 ? null : toks.get(i - 1);
          String right = i == toks.size() - 1 ? null : toks.get(i + 1);
          out.add(new SpellCase(def, left, right, w));
        }
      }
    }
    return out;
  }

  private static String deformSingleEdit(String word, Random rnd, String alphabet) {
    StringBuilder sb = new StringBuilder(word);
    int mode = rnd.nextInt(4);
    int pos = 1 + rnd.nextInt(word.length() - 1);
    switch (mode) {
      case 0: // deletion
        sb.deleteCharAt(pos);
        break;
      case 1: // insertion
        sb.insert(pos, alphabet.charAt(rnd.nextInt(alphabet.length())));
        break;
      case 2: // substitution
        char rep = alphabet.charAt(rnd.nextInt(alphabet.length()));
        if (rep == sb.charAt(pos)) {
          rep = 'k';
        }
        sb.setCharAt(pos, rep);
        break;
      case 3: // transposition
        if (pos < word.length() - 1 && sb.charAt(pos) != sb.charAt(pos + 1)) {
          char t = sb.charAt(pos);
          sb.setCharAt(pos, sb.charAt(pos + 1));
          sb.setCharAt(pos + 1, t);
        } else {
          sb.deleteCharAt(pos);
        }
        break;
    }
    return sb.toString();
  }

  private static List<SpellCase> buildDiacriticAndTypoSet() {
    // Exposes both i/ı and u/ü multi-diacritic words + 1-edit typo combinations
    return List.of(
        new SpellCase("sikinti", "bir", "var", "sıkıntı"),
        new SpellCase("sikintilar", "büyük", "yaşadık", "sıkıntılar"),
        new SpellCase("guldum", "çok", "buna", "güldüm"),
        new SpellCase("gulduk", "hepimiz", "buna", "güldük"),
        new SpellCase("gumus", "eski", "yüzük", "gümüş"),
        new SpellCase("uzuldum", "çok", "gerçekten", "üzüldüm"),
        new SpellCase("dusunce", "güzel", "ama", "düşünce"),
        new SpellCase("dusundum", "bunu", "dün", "düşündüm"),
        new SpellCase("gorusuruz", "yarın", "artık", "görüşürüz"),
        new SpellCase("cozum", "yeni", "bulduk", "çözüm"),
        new SpellCase("ogretmen", "yeni", "geldi", "öğretmen"),
        new SpellCase("ogrenciler", "bütün", "sınıfta", "öğrenciler"),
        new SpellCase("turkce", "güzel", "konuşuyor", "türkçe"),
        new SpellCase("kucuk", "çok", "ev", "küçük"),
        new SpellCase("buyuk", "daha", "sorun", "büyük"),
        new SpellCase("guzelce", "işi", "yaptık", "güzelce"),
        new SpellCase("guldm", "çok", "buna", "güldüm"),
        new SpellCase("uzuldm", "çok", "sana", "üzüldüm"),
        new SpellCase("dusundm", "bunu", "çok", "düşündüm"),
        new SpellCase("kizlra", "bütün", "söyledim", "kızlara"),
        new SpellCase("arkdaslar", "merhaba", "nasılsınız", "arkadaşlar"),
        new SpellCase("calismaktn", "çok", "yoruldum", "çalışmaktan"),
        new SpellCase("konusabliriz", "yarın", "bunu", "konuşabiliriz"),
        new SpellCase("okuyablirim", "bunu", "rahatça", "okuyabilirim"),
        new SpellCase("gorusebliriz", "akşam", "belki", "görüşebiliriz"));
  }

  private static List<GoldPair> buildNoisyGoldDataset() {
    List<GoldPair> list = new ArrayList<>(85);

    // Category 1: INFORMAL_VERBS (including -Io y-drop and -Abil/-Fut syncope!)
    list.add(new GoldPair("1_INFORMAL_VERBS", "Yarın okula gidicem.", "Yarın okula gideceğim."));
    list.add(new GoldPair("1_INFORMAL_VERBS", "Akşam size geliyoruz diyo.", "Akşam size geliyoruz diyor."));
    list.add(new GoldPair("1_INFORMAL_VERBS", "Böyle baskı yapıyosa hemen ayrıl.", "Böyle baskı yapıyorsa hemen ayrıl."));
    list.add(new GoldPair("1_INFORMAL_VERBS", "Ben de bu konuyu hiç bilmiom.", "Ben de bu konuyu hiç bilmiyorum."));
    list.add(new GoldPair("1_INFORMAL_VERBS", "Evde biraz dinleniorum şimdi.", "Evde biraz dinleniyorum şimdi."));
    list.add(new GoldPair("1_INFORMAL_VERBS", "Geçen sene çok sıkı çalışıodum.", "Geçen sene çok sıkı çalışıyordum."));
    list.add(new GoldPair("1_INFORMAL_VERBS", "Hep bir arada buluşabilceğimiz bir yer olsun.", "Hep bir arada buluşabileceğimiz bir yer olsun."));
    list.add(new GoldPair("1_INFORMAL_VERBS", "Zorunlunun kalkcağını hiç sanmıyorum.", "Zorunlunun kalkacağını hiç sanmıyorum."));
    list.add(new GoldPair("1_INFORMAL_VERBS", "Günde iki litre su içilcek.", "Günde iki litre su içilecek."));
    list.add(new GoldPair("1_INFORMAL_VERBS", "Herkes aynı şeyi sölüo.", "Herkes aynı şeyi söylüyor."));

    // Category 2: DIACRITICS_MIXED
    list.add(new GoldPair("2_DIACRITICS_MIXED", "Yarin havuza giricem ve aksama kadar yaticam.", "Yarın havuza gireceğim ve akşama kadar yatacağım."));
    list.add(new GoldPair("2_DIACRITICS_MIXED", "Yok hocam kesinlikle oyle bir sey yok.", "Yok hocam kesinlikle öyle bir şey yok."));
    list.add(new GoldPair("2_DIACRITICS_MIXED", "Bu iste buyuk bir sikinti var.", "Bu işte büyük bir sıkıntı var."));
    list.add(new GoldPair("2_DIACRITICS_MIXED", "Senin soylediklerine cok guldum.", "Senin söylediklerine çok güldüm."));
    list.add(new GoldPair("2_DIACRITICS_MIXED", "Istanbulda yasamak gercekten cok zorlasti.", "İstanbulda yaşamak gerçekten çok zorlaştı."));
    list.add(new GoldPair("2_DIACRITICS_MIXED", "Yarin aksam gorusuruz arkadaslar.", "Yarın akşam görüşürüz arkadaşlar."));
    list.add(new GoldPair("2_DIACRITICS_MIXED", "Turkce dogal dil isleme calismalari hizlandi.", "Türkçe doğal dil işleme çalışmaları hızlandı."));
    list.add(new GoldPair("2_DIACRITICS_MIXED", "Ogrenciler sinava cok iyi hazirlandi.", "Öğrenciler sınava çok iyi hazırlandı."));
    list.add(new GoldPair("2_DIACRITICS_MIXED", "Gercek mi bu soyledigin sey?", "Gerçek mi bu söylediğin şey?"));
    list.add(new GoldPair("2_DIACRITICS_MIXED", "Boyle insanlarin hayatinda olmamasi gerek.", "Böyle insanların hayatında olmaması gerek."));

    // Category 3: ELONGATIONS
    list.add(new GoldPair("3_ELONGATIONS", "Eveeet yarın sınava başlıyorum.", "Evet yarın sınava başlıyorum."));
    list.add(new GoldPair("3_ELONGATIONS", "Bu yemek çoooook güzel olmuş.", "Bu yemek çok güzel olmuş."));
    list.add(new GoldPair("3_ELONGATIONS", "Bunu yapacağını hiiiiç sanmıyorum.", "Bunu yapacağını hiç sanmıyorum."));
    list.add(new GoldPair("3_ELONGATIONS", "Bana biraz bilgi verirmisinzz?", "Bana biraz bilgi verir misiniz?"));
    list.add(new GoldPair("3_ELONGATIONS", "Merabaa arkadaşlar nasılsınız?", "Merhaba arkadaşlar nasılsınız?"));
    list.add(new GoldPair("3_ELONGATIONS", "O kadaar çok çalıştım ki yoruldum.", "O kadar çok çalıştım ki yoruldum."));
    list.add(new GoldPair("3_ELONGATIONS", "Sabah heep geç uyanıyorum.", "Sabah hep geç uyanıyorum."));
    list.add(new GoldPair("3_ELONGATIONS", "Yardımın için teşşşekkür ederim.", "Yardımın için teşekkür ederim."));
    list.add(new GoldPair("3_ELONGATIONS", "Cooook guzel bir haber aldik.", "Çok güzel bir haber aldık."));
    list.add(new GoldPair("3_ELONGATIONS", "Lütfeeen biraz sessiz olun.", "Lütfen biraz sessiz olun."));

    // Category 4: SPLIT_MERGE_CLITICS
    list.add(new GoldPair("4_SPLIT_MERGE_CLITICS", "Yarın bizimle gelecekmisiniz?", "Yarın bizimle gelecek misiniz?"));
    list.add(new GoldPair("4_SPLIT_MERGE_CLITICS", "Böyle bir şey olurmu hiç?", "Böyle bir şey olur mu hiç?"));
    list.add(new GoldPair("4_SPLIT_MERGE_CLITICS", "Herşeyi sana açıkça anlattım.", "Her şeyi sana açıkça anlattım."));
    list.add(new GoldPair("4_SPLIT_MERGE_CLITICS", "Hiç bir zaman ümidini kaybetme.", "Hiçbir zaman ümidini kaybetme."));
    list.add(new GoldPair("4_SPLIT_MERGE_CLITICS", "Bir kaç gün sonra dönecekler.", "Birkaç gün sonra dönecekler."));
    list.add(new GoldPair("4_SPLIT_MERGE_CLITICS", "Evrakları yarın göndere bilirler.", "Evrakları yarın gönderebilirler."));
    list.add(new GoldPair("4_SPLIT_MERGE_CLITICS", "Gece 12 de uyudum.", "Gece 12'de uyudum."));
    list.add(new GoldPair("4_SPLIT_MERGE_CLITICS", "10 ar dakika arayla su içtim.", "10'ar dakika arayla su içtim."));
    list.add(new GoldPair("4_SPLIT_MERGE_CLITICS", "Ah aynen ya annemde fark etti.", "Ah aynen ya annem de fark etti."));
    list.add(new GoldPair("4_SPLIT_MERGE_CLITICS", "Tutupda bana yalan söyleme.", "Tutup da bana yalan söyleme."));

    // Category 5: COLLOQUIAL_HAPLOLOGY
    list.add(new GoldPair("5_COLLOQUIAL_HAPLOLOGY", "Tmm yarın sabah konuşuruz.", "Tamam yarın sabah konuşuruz."));
    list.add(new GoldPair("5_COLLOQUIAL_HAPLOLOGY", "Slm cnm bugün müsait misin?", "Selam canım bugün müsait misin?"));
    list.add(new GoldPair("5_COLLOQUIAL_HAPLOLOGY", "Bnde sınava hazırlanıyorum.", "Ben de sınava hazırlanıyorum."));
    list.add(new GoldPair("5_COLLOQUIAL_HAPLOLOGY", "Bu durum hiç kolay deil.", "Bu durum hiç kolay değil."));
    list.add(new GoldPair("5_COLLOQUIAL_HAPLOLOGY", "Herkez aynı hatayı yapıyor.", "Herkes aynı hatayı yapıyor."));
    list.add(new GoldPair("5_COLLOQUIAL_HAPLOLOGY", "Malesef bu soruyu yalnış çözmüşsün.", "Maalesef bu soruyu yanlış çözmüşsün."));
    list.add(new GoldPair("5_COLLOQUIAL_HAPLOLOGY", "Heralde yarın yağmur yağacak.", "Herhalde yarın yağmur yağacak."));
    list.add(new GoldPair("5_COLLOQUIAL_HAPLOLOGY", "Burdaki kitapları nerden aldın?", "Buradaki kitapları nereden aldın?"));
    list.add(new GoldPair("5_COLLOQUIAL_HAPLOLOGY", "Orda bişi bulamadık.", "Orada bir şey bulamadık."));
    list.add(new GoldPair("5_COLLOQUIAL_HAPLOLOGY", "Telefonun şarz aleti orjinal değil.", "Telefonun şarj aleti orijinal değil."));

    // Category 6: TYPOS_KEYBOARD
    list.add(new GoldPair("6_TYPOS_KEYBOARD", "Bu yazıyı rahatça okuyablirim.", "Bu yazıyı rahatça okuyabilirim."));
    list.add(new GoldPair("6_TYPOS_KEYBOARD", "Merhaba arkdaşlar hoş geldiniz.", "Merhaba arkadaşlar hoş geldiniz."));
    list.add(new GoldPair("6_TYPOS_KEYBOARD", "Masadaki kitplar yere düştü.", "Masadaki kitaplar yere düştü."));
    list.add(new GoldPair("6_TYPOS_KEYBOARD", "Yeni bir bilgisyaar almak istiyorum.", "Yeni bir bilgisayar almak istiyorum."));
    list.add(new GoldPair("6_TYPOS_KEYBOARD", "Dün akşam eve geç gelidim.", "Dün akşam eve geç geldim."));
    list.add(new GoldPair("6_TYPOS_KEYBOARD", "Yarın okua erken gideceğim.", "Yarın okula erken gideceğim."));
    list.add(new GoldPair("6_TYPOS_KEYBOARD", "Seni kapının önünde bekliyrum.", "Seni kapının önünde bekliyorum."));
    list.add(new GoldPair("6_TYPOS_KEYBOARD", "Bu konuyu yarın konuşabliriz.", "Bu konuyu yarın konuşabiliriz."));
    list.add(new GoldPair("6_TYPOS_KEYBOARD", "Bütün gün çalışmakatn yoruldum.", "Bütün gün çalışmaktan yoruldum."));
    list.add(new GoldPair("6_TYPOS_KEYBOARD", "Bunu ben de çok düşünyorum.", "Bunu ben de çok düşünüyorum."));

    // Category 7: MORPH_FALSE_FRIENDS (Abbreviation / Short ProperNoun false parses)
    list.add(new GoldPair("7_MORPH_FALSE_FRIENDS", "Eşyaları kutuya goyduk.", "Eşyaları kutuya koyduk."));
    list.add(new GoldPair("7_MORPH_FALSE_FRIENDS", "Alt kattaki komsu çok gürültü yapıyor.", "Alt kattaki komşu çok gürültü yapıyor."));
    list.add(new GoldPair("7_MORPH_FALSE_FRIENDS", "Bozuk tuslarla yazı yazmak zor.", "Bozuk tuşlarla yazı yazmak zor."));
    list.add(new GoldPair("7_MORPH_FALSE_FRIENDS", "Sabah erkenden pencereyi acmış.", "Sabah erkenden pencereyi açmış."));
    list.add(new GoldPair("7_MORPH_FALSE_FRIENDS", "Bize dün akşam sevdimi diye sordu.", "Bize dün akşam sevdi mi diye sordu."));
    list.add(new GoldPair("7_MORPH_FALSE_FRIENDS", "Çantayı masanın üstüne goydum.", "Çantayı masanın üstüne koydum."));
    list.add(new GoldPair("7_MORPH_FALSE_FRIENDS", "Bütün komsular bahçede toplandı.", "Bütün komşular bahçede toplandı."));
    list.add(new GoldPair("7_MORPH_FALSE_FRIENDS", "Yanlışlıkla kırmızı tusa bastım.", "Yanlışlıkla kırmızı tuşa bastım."));
    list.add(new GoldPair("7_MORPH_FALSE_FRIENDS", "Pencereleri sonuna kadar actık.", "Pencereleri sonuna kadar açtık."));
    list.add(new GoldPair("7_MORPH_FALSE_FRIENDS", "Burada baska kimse kalmadı.", "Burada başka kimse kalmadı."));

    // Category 8: REAL_FORUM_SENTENCES (Multi-phenomenon social media sentences)
    list.add(new GoldPair("8_REAL_FORUM_MIXED", "Yrn okua gidicem.", "Yarın okula gideceğim."));
    list.add(new GoldPair("8_REAL_FORUM_MIXED", "Tmm, yarın havuza giricem ve akşama kadar yatıcam.", "Tamam, yarın havuza gireceğim ve akşama kadar yatacağım."));
    list.add(new GoldPair("8_REAL_FORUM_MIXED", "Artık unutulması bile beklenmiyo.", "Artık unutulması bile beklenmiyor."));
    list.add(new GoldPair("8_REAL_FORUM_MIXED", "Bi ben varım heralde nöbetçiydim gece.", "Bir ben varım herhalde nöbetçiydim gece."));
    list.add(new GoldPair("8_REAL_FORUM_MIXED", "Bnde bugün dersaneye erken gittim.", "Ben de bugün dershaneye erken gittim."));
    list.add(new GoldPair("8_REAL_FORUM_MIXED", "İnş bir an önce biter ve mezun olurum.", "İnşallah bir an önce biter ve mezun olurum."));
    list.add(new GoldPair("8_REAL_FORUM_MIXED", "Aile hekimi fln sözleşmeli biliyosun.", "Aile hekimi falan sözleşmeli biliyorsun."));
    list.add(new GoldPair("8_REAL_FORUM_MIXED", "Zorunlu kalkacak diye bişiler duydum.", "Zorunlu kalkacak diye bir şeyler duydum."));
    list.add(new GoldPair("8_REAL_FORUM_MIXED", "Siz daha evvel böyle birşey duydunuzmu?", "Siz daha evvel böyle bir şey duydunuz mu?"));
    list.add(new GoldPair("8_REAL_FORUM_MIXED", "Yanlız kalmak bazen insana ii gelio.", "Yalnız kalmak bazen insana iyi geliyor."));

    return list;
  }

  private static List<GoldPair> buildCleanControlDataset() {
    // Clean, well-formed sentences testing precision (False Positives / Over-Normalization),
    // including known trap words in candidates-manual, geminates, diacritic minimal pairs, and proper nouns.
    String[] cleanSentences = {
      "Bunu ben de dün akşam duydum.",
      "Yoldaki görevli bize adres sordu.",
      "Sizi burada görmek bizi çok mutlu etti.",
      "Dua bittikten sonra herkes amin dedi.",
      "Sakin bir sahil kasabasında yaşıyoruz.",
      "Toplantıya toplam on kişi katıldı.",
      "Bu kış dağlara çok yoğun kar yağdı.",
      "Rüzgardan dolayı odanın camı kırıldı.",
      "Dünkü konser gerçekten çok güzel oldu.",
      "Dedemin yaşadığı köy merkeze çok uzak.",
      "Anne saat beşte eve dönecek.",
      "Bu sınavdan elli puan almak gerekiyor.",
      "Herkes kendi hakkı olanı savunmalıdır.",
      "Şirketin hisse değerleri hızla yükseldi.",
      "Bu konuda ciddi adımlar atılması şart.",
      "Lütfen bana son raporu göster.",
      "Tüm ürünleri kategoriye göre listele.",
      "Evde kaldım ve bütün gün kitap okudum.",
      "Okulda yeni bir kütüphane açıldı.",
      "Masada iki bardak çay duruyor.",
      "Türkiye'de eğitim alanında yeni projeler başlatıldı.",
      "Ankara'ya hızlı trenle iki saatte gittik.",
      "İstanbul'un tarihi yarımadası turistlerle doluydu.",
      "Bugün Google ve Microsoft yeni yapay zeka modellerini tanıttı.",
      "Yarın sabah Paris aktarmalı uçuşumuz var.",
      "Merkez Bankası faiz kararını perşembe günü açıklayacak.",
      "Üniversite sınavına giren adaylar sonuçları bekliyor.",
      "Sağlıklı beslenme ve düzenli uyku bağışıklığı güçlendirir.",
      "Bilim insanları küresel ısınmanın etkilerini araştırıyor.",
      "Yeni açılan sergi sanatseverlerden yoğun ilgi gördü.",
      "Çocuklar bahçede neşeyle oyun oynuyor.",
      "Kitabın son bölümünü dün gece bitirdim.",
      "Sabah erken kalkıp sahilde yürüyüş yaptım.",
      "Bu projenin bütçesi geçen hafta onaylandı.",
      "Müşteri hizmetleri sorununuzu kısa sürede çözecektir.",
      "Doğal dil işleme teknolojileri hızla gelişiyor.",
      "Kış aylarında su tüketimine dikkat etmek gerekir.",
      "Trafik yoğunluğu nedeniyle işe biraz geç kaldık.",
      "Öğretmenimiz sınav sorularını çok dikkatli hazırlamış.",
      "Arkadaşlarımla hafta sonu sinemaya gideceğiz.",
      "Uluslararası standartlara uygun üretim yapılıyor.",
      "Hiçbir başarı tesadüfen elde edilmez.",
      "Birkaç dakika içinde toplantı başlayacak.",
      "Herhangi bir sorun olursa bana haber verin.",
      "Bu karardan kesinlikle vazgeçmeyeceğiz.",
      "Gazi Üniversitesi yeni akademik yıla başladı.",
      "Tıp fakültesi öğrencileri hastanede staj yapıyor.",
      "Yarın hava sıcaklığı beş derece düşecek.",
      "Ekonomik göstergeler yılın ikinci yarısında iyileşti.",
      "Belediye ekipleri bozuk yolları onarmaya başladı."
    };
    List<GoldPair> out = new ArrayList<>(cleanSentences.length);
    for (String s : cleanSentences) {
      out.add(new GoldPair("CLEAN_CONTROL", s, s));
    }
    return out;
  }

  private static List<String> loadNoisyOovStream(TurkishMorphology morphology, int maxTokens)
      throws IOException {
    Path p = Paths.get(System.getenv().getOrDefault("ZEMBEREK_DATA_DIR", System.getProperty("user.home") + "/data/turkish") + "/noisy/kadinlar-klubu.txt");
    if (!Files.exists(p)) {
      return Collections.emptyList();
    }
    List<String> out = new ArrayList<>(maxTokens);
    try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
      String line;
      while ((line = br.readLine()) != null && out.size() < maxTokens) {
        List<Token> toks = TurkishTokenizer.DEFAULT.tokenize(line.toLowerCase(Turkish.LOCALE));
        for (Token t : toks) {
          if (t.getType() == Token.Type.Word) {
            String w = t.getText();
            if (w.length() >= 4 && w.length() <= 18 && morphology.analyze(w).analysisCount() == 0) {
              out.add(w);
              if (out.size() >= maxTokens) {
                break;
              }
            }
          }
        }
      }
    }
    return out;
  }

  private static List<String> loadRealSentences(List<Path> files, int targetTotal)
      throws IOException {
    List<String> out = new ArrayList<>(targetTotal);
    int perFile = Math.max(1, targetTotal / files.size());
    for (Path f : files) {
      if (!Files.exists(f)) {
        continue;
      }
      int count = 0;
      try (BufferedReader br = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
        String line;
        while ((line = br.readLine()) != null && count < perFile && out.size() < targetTotal) {
          line = line.trim();
          if (line.length() >= 15 && line.length() <= 220 && !line.startsWith("<")) {
            out.add(line);
            count++;
          }
        }
      }
    }
    return out;
  }

  private static int countTokens(List<String> sentences) {
    int c = 0;
    for (String s : sentences) {
      c += TurkishTokenizer.DEFAULT.tokenize(s).size();
    }
    return c;
  }

  private static boolean hasDigits(String s) {
    return TurkishAlphabet.INSTANCE.containsDigit(s);
  }

  private static void forceGc() {
    for (int i = 0; i < 3; i++) {
      System.gc();
      try {
        Thread.sleep(30);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static long usedHeapBytes() {
    Runtime rt = Runtime.getRuntime();
    return rt.totalMemory() - rt.freeMemory();
  }
}
