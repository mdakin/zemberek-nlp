package zemberek.normalization.experiment;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import zemberek.core.logging.Log;
import zemberek.core.turkish.SecondaryPos;
import zemberek.core.turkish.Turkish;
import zemberek.core.turkish.TurkishAlphabet;
import zemberek.lm.compression.MultiFileUncompressedLm;
import zemberek.lm.compression.SmoothLm;
import zemberek.lm.compression.UncompressedToSmoothLmConverter;
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.analysis.SingleAnalysis;
import zemberek.morphology.analysis.WordAnalysis;
import zemberek.tokenization.Token;
import zemberek.tokenization.TurkishTokenizer;

/**
 * Builds (and caches on disk) realistic SmoothLm 2-gram language model and normalization lookup
 * tables from the local Turkish clean + noisy corpora so {@code TurkishSentenceNormalizer} and
 * experimental prototypes can be benchmarked head-to-head under identical conditions.
 */
public final class ExperimentDataBuilder {

  private static final Path DEFAULT_CORPUS_ROOT = Paths.get("/home/dndara/data/turkish");

  public static final class NormalizationResources {
    public final Path dataRoot;
    public final Path lmPath;

    public NormalizationResources(Path dataRoot, Path lmPath) {
      this.dataRoot = dataRoot;
      this.lmPath = lmPath;
    }
  }

  private ExperimentDataBuilder() {}

  public static NormalizationResources ensureBuilt(Path workDir, TurkishMorphology morphology)
      throws IOException {
    Path dataRoot = workDir.resolve("normalization");
    Path lmPath = workDir.resolve("lm.2gram.slm");
    if (Files.exists(lmPath)
        && Files.exists(dataRoot.resolve("lookup-from-graph"))
        && Files.exists(dataRoot.resolve("ascii-map"))
        && Files.exists(dataRoot.resolve("split"))) {
      return new NormalizationResources(dataRoot, lmPath);
    }

    Files.createDirectories(dataRoot);
    long t0 = System.currentTimeMillis();
    Log.info("Building experimental SmoothLm + normalization lookup data in %s ...", workDir);

    Map<String, Long> unigramCounts = new HashMap<>(250_000);
    Map<Long, Integer> bigramCounts = new HashMap<>(2_000_000);

    List<Path> cleanSources = List.of(
        DEFAULT_CORPUS_ROOT.resolve("clean/ntv-750k-sentences.txt"),
        DEFAULT_CORPUS_ROOT.resolve("clean/open-subtitles.txt"),
        DEFAULT_CORPUS_ROOT.resolve("clean/onedio.com.txt"));

    // Pass 1: collect unigram frequencies across news + conversational subtitles + web text
    int maxLinesPerFile = 180_000;
    List<List<String>> tokenizedCorpus = new ArrayList<>(450_000);
    long totalSentences = 0;

    for (Path src : cleanSources) {
      if (!Files.exists(src)) {
        continue;
      }
      int linesRead = 0;
      try (BufferedReader br = Files.newBufferedReader(src, StandardCharsets.UTF_8)) {
        String line;
        while ((line = br.readLine()) != null && linesRead < maxLinesPerFile) {
          line = line.trim();
          if (line.length() < 4 || line.length() > 350 || line.startsWith("<")) {
            continue;
          }
          linesRead++;
          String lower = line.toLowerCase(Turkish.LOCALE);
          List<Token> rawTokens = TurkishTokenizer.DEFAULT.tokenize(lower);
          if (rawTokens.isEmpty()) {
            continue;
          }
          List<String> words = new ArrayList<>(rawTokens.size());
          for (Token tok : rawTokens) {
            String t = tok.getText().trim();
            if (!t.isEmpty() && t.indexOf(' ') < 0 && t.indexOf('\t') < 0) {
              words.add(t);
              unigramCounts.merge(t, 1L, Long::sum);
            }
          }
          if (!words.isEmpty()) {
            tokenizedCorpus.add(words);
            totalSentences++;
          }
        }
      }
    }

    // Keep vocabulary of words with count >= 3 (up to ~140k words) for fast, high-coverage SmoothLm
    List<Map.Entry<String, Long>> sortedUnigrams = new ArrayList<>(unigramCounts.entrySet());
    sortedUnigrams.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

    Map<String, Integer> vocabToId = new LinkedHashMap<>(150_000);
    List<String> idToVocab = new ArrayList<>(150_000);
    List<Long> idToCount = new ArrayList<>(150_000);

    vocabToId.put("<s>", 0);
    idToVocab.add("<s>");
    idToCount.add(Math.max(1L, totalSentences));

    vocabToId.put("</s>", 1);
    idToVocab.add("</s>");
    idToCount.add(Math.max(1L, totalSentences));

    long unkCount = 100L;
    int maxVocab = 130_000;
    for (Map.Entry<String, Long> e : sortedUnigrams) {
      String w = e.getKey();
      if ("<s>".equals(w) || "</s>".equals(w) || "<unk>".equals(w)) {
        continue;
      }
      if (e.getValue() >= 3 && idToVocab.size() < maxVocab) {
        vocabToId.put(w, idToVocab.size());
        idToVocab.add(w);
        idToCount.add(e.getValue());
      } else {
        unkCount += e.getValue();
      }
    }
    int unkId = idToVocab.size();
    vocabToId.put("<unk>", unkId);
    idToVocab.add("<unk>");
    // Give <unk> a floor count of 1 (standard OOV floor) rather than the sum of pruned rare
    // words, so OOV non-words never outscore real in-vocabulary Turkish words in SmoothLm!
    idToCount.add(1L);

    // Pass 2: collect bigram counts over in-vocabulary ids
    int[] leftOutTotal = new int[idToVocab.size()];
    int[] leftUniqueFollowers = new int[idToVocab.size()];
    for (List<String> sent : tokenizedCorpus) {
      int prev = 0; // <s>
      for (String w : sent) {
        Integer curObj = vocabToId.get(w);
        int cur = curObj != null ? curObj : unkId;
        if (prev != unkId && cur != unkId) {
          long key = (((long) prev) << 32) | (cur & 0xffffffffL);
          Integer old = bigramCounts.put(key, bigramCounts.getOrDefault(key, 0) + 1);
          leftOutTotal[prev]++;
          if (old == null) {
            leftUniqueFollowers[prev]++;
          }
        }
        prev = cur;
      }
      if (prev != unkId) {
        long endKey = (((long) prev) << 32) | 1L; // </s>
        Integer old = bigramCounts.put(endKey, bigramCounts.getOrDefault(endKey, 0) + 1);
        leftOutTotal[prev]++;
        if (old == null) {
          leftUniqueFollowers[prev]++;
        }
      }
    }
    tokenizedCorpus.clear();

    // Filter singleton bigrams except moderate-frequency words to keep .slm compact (~15-20 MB)
    List<Map.Entry<Long, Integer>> keptBigrams = new ArrayList<>(bigramCounts.size() / 2);
    long[] keptMassPerLeft = new long[idToVocab.size()];
    int[] keptTypesPerLeft = new int[idToVocab.size()];
    for (Map.Entry<Long, Integer> e : bigramCounts.entrySet()) {
      int c = e.getValue();
      if (c >= 2) {
        int prev = (int) (e.getKey() >>> 32);
        keptBigrams.add(e);
        keptMassPerLeft[prev] += c;
        keptTypesPerLeft[prev]++;
      }
    }
    bigramCounts.clear();

    // Write ARPA file with absolute discounting + interpolated backoff
    Path arpaPath = workDir.resolve("lm.2gram.arpa");
    long totalUnigramTokens = 0;
    for (int i = 1; i < idToCount.size(); i++) { // exclude <s> from denominator
      totalUnigramTokens += idToCount.get(i);
    }
    double discount = 0.75;
    double[] unigramProbs = new double[idToVocab.size()];
    double[] backoffLog10 = new double[idToVocab.size()];

    for (int i = 0; i < idToVocab.size(); i++) {
      if (i == 0) { // <s>
        unigramProbs[i] = 1e-6;
      } else {
        unigramProbs[i] = (idToCount.get(i) + 0.5) / (totalUnigramTokens + idToVocab.size() * 0.5);
      }
      if (leftOutTotal[i] > 0 && keptTypesPerLeft[i] > 0) {
        double remMass =
            (leftOutTotal[i] - keptMassPerLeft[i]) + discount * keptTypesPerLeft[i];
        double alpha = Math.max(1e-4, Math.min(0.999, remMass / leftOutTotal[i]));
        backoffLog10[i] = Math.log10(alpha);
      } else {
        backoffLog10[i] = 0.0;
      }
    }

    try (BufferedWriter bw = Files.newBufferedWriter(arpaPath, StandardCharsets.UTF_8)) {
      bw.write("\\data\\\n");
      bw.write("ngram 1= " + idToVocab.size() + "\n");
      bw.write("ngram 2= " + keptBigrams.size() + "\n\n");
      bw.write("\\1-grams:\n");
      for (int i = 0; i < idToVocab.size(); i++) {
        double log10p = (i == 0) ? -99.0 : Math.log10(unigramProbs[i]);
        bw.write(
            String.format(
                Locale.US, "%.5f %s %.5f\n", log10p, idToVocab.get(i), backoffLog10[i]));
      }
      bw.write("\n\\2-grams:\n");
      for (Map.Entry<Long, Integer> e : keptBigrams) {
        long key = e.getKey();
        int prev = (int) (key >>> 32);
        int cur = (int) key;
        int c = e.getValue();
        double alpha = Math.pow(10.0, backoffLog10[prev]);
        double p = Math.max(c - discount, 0.1) / leftOutTotal[prev] + alpha * unigramProbs[cur];
        bw.write(
            String.format(
                Locale.US,
                "%.5f %s %s\n",
                Math.log10(p),
                idToVocab.get(prev),
                idToVocab.get(cur)));
      }
      bw.write("\n\\end\\\n");
    }

    Path tmpBinDir = workDir.resolve("tmp-lm-bin");
    Files.createDirectories(tmpBinDir);
    File uncompressedDir =
        MultiFileUncompressedLm.generate(arpaPath.toFile(), tmpBinDir.toFile(), "UTF-8", 4)
            .getLmDir();
    UncompressedToSmoothLmConverter converter =
        new UncompressedToSmoothLmConverter(lmPath.toFile(), tmpBinDir.toFile());
    converter.convertSmall(
        uncompressedDir, new UncompressedToSmoothLmConverter.NgramDataBlock(16, 16, 16));

    Files.deleteIfExists(arpaPath);
    deleteDirQuietly(tmpBinDir);

    SmoothLm lm = SmoothLm.builder(lmPath).logBase(Math.E).build();
    buildLookupFiles(dataRoot, idToVocab, morphology, lm);

    Log.info(
        "Built SmoothLm (%d unigrams, %d bigrams) + normalization lookups in %d ms.",
        idToVocab.size(), keptBigrams.size(), (System.currentTimeMillis() - t0));
    return new NormalizationResources(dataRoot, lmPath);
  }

  private static void buildLookupFiles(
      Path dataRoot,
      List<String> cleanVocab,
      TurkishMorphology morphology,
      SmoothLm lm)
      throws IOException {
    // 1. Build ascii-map from clean vocabulary words that have Turkish diacritics
    Map<String, Set<String>> asciiToClean = new LinkedHashMap<>();
    Set<String> validCleanWords = new LinkedHashSet<>();
    for (String w : cleanVocab) {
      if (w.length() < 2 || w.startsWith("<")) {
        continue;
      }
      WordAnalysis wa = morphology.analyze(w);
      boolean regular = false;
      for (SingleAnalysis sa : wa) {
        if (!sa.isUnknown()
            && !sa.isRuntime()
            && sa.getDictionaryItem().secondaryPos != SecondaryPos.ProperNoun
            && sa.getDictionaryItem().secondaryPos != SecondaryPos.Abbreviation) {
          regular = true;
          break;
        }
      }
      if (regular) {
        validCleanWords.add(w);
        String asc = TurkishAlphabet.INSTANCE.toAscii(w);
        if (!asc.equals(w)) {
          asciiToClean.computeIfAbsent(asc, k -> new LinkedHashSet<>()).add(w);
        }
      }
    }

    // Load top unparsed noisy words from unparsed_words.tsv if available
    Path unparsedPath = DEFAULT_CORPUS_ROOT.resolve("unparsed_words.tsv");
    List<String> topNoisyWords = new ArrayList<>();
    if (Files.exists(unparsedPath)) {
      try (BufferedReader br = Files.newBufferedReader(unparsedPath, StandardCharsets.UTF_8)) {
        String line = br.readLine(); // header
        int limit = 60_000;
        while ((line = br.readLine()) != null && topNoisyWords.size() < limit) {
          int tab = line.indexOf('\t');
          if (tab > 0) {
            String w = line.substring(0, tab).trim().toLowerCase(Turkish.LOCALE);
            if (w.length() >= 2 && w.length() <= 24 && isAllLetters(w)) {
              topNoisyWords.add(w);
            }
          }
        }
      }
    }

    // Write ascii-map
    try (BufferedWriter bw =
        Files.newBufferedWriter(dataRoot.resolve("ascii-map"), StandardCharsets.UTF_8)) {
      for (Map.Entry<String, Set<String>> e : asciiToClean.entrySet()) {
        if (e.getValue().size() <= 4) {
          bw.write(e.getKey() + "=" + String.join(",", e.getValue()) + "\n");
        }
      }
    }

    // 2. Write split file using LM bigram probabilities (same logic as NormalizationScripts.splitWords)
    int unkIdx = lm.getVocabulary().getUnknownWordIndex();
    Set<String> asciiKeys = asciiToClean.keySet();
    Map<String, String> splits = new LinkedHashMap<>();
    for (String w : topNoisyWords) {
      if (w.length() < 5 || asciiKeys.contains(w) || validCleanWords.contains(w)) {
        continue;
      }
      if (morphology.analyze(w).isCorrect()) {
        continue;
      }
      float bestScore = -7.0f;
      String bestSplit = null;
      for (int i = 2; i <= w.length() - 2; i++) {
        String head = w.substring(0, i);
        String tail = w.substring(i);
        int hi = lm.getVocabulary().indexOf(head);
        int ti = lm.getVocabulary().indexOf(tail);
        if (hi == unkIdx || ti == unkIdx) {
          continue;
        }
        if ((tail.equals("de") || tail.equals("da")) && morphology.analyze(head).isCorrect()) {
          continue;
        }
        if (validCleanWords.contains(head) && validCleanWords.contains(tail) && lm.ngramExists(hi, ti)) {
          float s = lm.getProbability(hi, ti);
          if (s > bestScore) {
            bestScore = s;
            bestSplit = head + " " + tail;
          }
        }
      }
      if (bestSplit != null) {
        splits.put(w, bestSplit);
      }
    }
    // Ensure standard common splits are present
    splits.putIfAbsent("herşey", "her şey");
    splits.putIfAbsent("herşeyi", "her şeyi");
    splits.putIfAbsent("herşeyin", "her şeyin");
    splits.putIfAbsent("herşeye", "her şeye");
    splits.putIfAbsent("herşeyden", "her şeyden");
    splits.putIfAbsent("pekçok", "pek çok");
    splits.putIfAbsent("birçokları", "bir çokları");
    splits.putIfAbsent("yada", "ya da");

    try (BufferedWriter bw =
        Files.newBufferedWriter(dataRoot.resolve("split"), StandardCharsets.UTF_8)) {
      for (Map.Entry<String, String> e : splits.entrySet()) {
        bw.write(e.getKey() + "=" + e.getValue() + "\n");
      }
    }

    // 3. Write lookup-from-graph simulating Zemberek's noisy-lexicon graph candidates
    Map<String, String> graphCandidates = new LinkedHashMap<>();
    graphCandidates.put("slm", "selam");
    graphCandidates.put("mrb", "merhaba");
    graphCandidates.put("yrn", "yarın");
    graphCandidates.put("cnm", "canım");
    graphCandidates.put("krdş", "kardeş");
    graphCandidates.put("inş", "inşallah");
    graphCandidates.put("tşk", "teşekkür");
    graphCandidates.put("eyw", "eyvallah");
    graphCandidates.put("nbr", "ne haber");
    graphCandidates.put("fln", "falan");
    graphCandidates.put("felan", "falan");
    graphCandidates.put("malesef", "maalesef");
    graphCandidates.put("heralde", "herhalde");
    graphCandidates.put("yalnış", "yanlış");
    graphCandidates.put("yanlız", "yalnız");
    graphCandidates.put("herkez", "herkes");
    graphCandidates.put("deil", "değil");
    graphCandidates.put("ii", "iyi");
    // Also include realistic noisy-graph ambiguity pairs where unweighted LM can over-normalize!
    graphCandidates.put("baya", "bayağı,baya");
    graphCandidates.put("kardo", "kardeş");

    try (BufferedWriter bw =
        Files.newBufferedWriter(dataRoot.resolve("lookup-from-graph"), StandardCharsets.UTF_8)) {
      for (Map.Entry<String, String> e : graphCandidates.entrySet()) {
        bw.write(e.getKey() + "=" + e.getValue() + "\n");
      }
    }
  }

  private static boolean isAllLetters(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (!Character.isLetter(s.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static void deleteDirQuietly(Path dir) {
    if (!Files.exists(dir)) {
      return;
    }
    try {
      Files.walk(dir)
          .sorted(Collections.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
              });
    } catch (IOException ignored) {
    }
  }
}
