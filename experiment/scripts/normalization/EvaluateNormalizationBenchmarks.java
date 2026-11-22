import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import zemberek.core.turkish.Turkish;
import zemberek.morphology.TurkishMorphology;
import zemberek.normalization.CharacterGraphDecoder;
import zemberek.normalization.TurkishSentenceNormalizer;
import zemberek.normalization.TurkishSpellChecker;
import zemberek.tokenization.Token;
import zemberek.tokenization.TurkishTokenizer;

/**
 * Multi-benchmark evaluator for Zemberek's normalization & spell-checking stack.
 * Uses only public APIs on upstream/master so identical binaries measure baseline,
 * individual PRs, and combined PRs apples-to-apples.
 */
public final class EvaluateNormalizationBenchmarks {

  private static final Path DEFAULT_NORM_DATA = Paths.get(
      "/home/dndara/data/turkish/normalization/zemberek_norm_data");
  private static final Path NASTY_5K_TSV = Paths.get(
      "/home/dndara/data/turkish/normalization/custom_benchmark/nasty_5k/llm_gold/exports/nasty_5000_pairs.tsv");
  private static final Path MULTILEXNORM_TR = Paths.get(
      "/home/dndara/data/turkish/normalization/benchmarks/multilexnorm/tur/test.norm");
  private static final Path MUKAYESE_TRSPELL10 = Paths.get(
      "/home/dndara/data/turkish/normalization/benchmarks/mukayese_trspell10/test.csv");
  private static final Path GECTURK_SRC = Paths.get(
      "/home/dndara/data/turkish/normalization/benchmarks/gecturk_boun_clitics/boun_source_test.txt");
  private static final Path GECTURK_TGT = Paths.get(
      "/home/dndara/data/turkish/normalization/benchmarks/gecturk_boun_clitics/boun_target_test.txt");

  private static final class SentPair {
    final String raw;
    final String gold;

    SentPair(String raw, String gold) {
      this.raw = raw;
      this.gold = gold;
    }
  }

  private static final class SpellPair {
    final String input;
    final String gold;

    SpellPair(String input, String gold) {
      this.input = input;
      this.gold = gold;
    }
  }

  private static final class SentenceEvalStats {
    int sentences;
    long rawTokens;
    long goldTokens;
    long rawEditDist;
    long sysEditDist;
    int exactMatch;
    double elapsedSec;
    final List<String> sampleDiffs = new ArrayList<>();

    double wErr() {
      return rawEditDist == 0 ? 0.0 : (rawEditDist - sysEditDist) * 1.0 / rawEditDist;
    }

    double rawWer() {
      return goldTokens == 0 ? 0.0 : rawEditDist * 1.0 / goldTokens;
    }

    double sysWer() {
      return goldTokens == 0 ? 0.0 : sysEditDist * 1.0 / goldTokens;
    }

    double sentAcc() {
      return sentences == 0 ? 0.0 : exactMatch * 1.0 / sentences;
    }

    double tokPerSec() {
      return elapsedSec <= 0 ? 0.0 : rawTokens / elapsedSec;
    }
  }

  private static final class SpellStats {
    int total;
    int misspelled;
    int clean;
    int tp;
    int fp;
    int fn;
    int tn;
    int top1All;
    int top5All;
    int top1Detected;
    int top5Detected;
    double elapsedSec;

    double precision() {
      return (tp + fp) == 0 ? 0.0 : tp * 1.0 / (tp + fp);
    }

    double recall() {
      return (tp + fn) == 0 ? 0.0 : tp * 1.0 / (tp + fn);
    }

    double f1() {
      double p = precision();
      double r = recall();
      return (p + r) == 0 ? 0.0 : 2.0 * p * r / (p + r);
    }

    double fpRate() {
      return clean == 0 ? 0.0 : fp * 1.0 / clean;
    }

    double top1AllAcc() {
      return misspelled == 0 ? 0.0 : top1All * 1.0 / misspelled;
    }

    double top5AllAcc() {
      return misspelled == 0 ? 0.0 : top5All * 1.0 / misspelled;
    }

    double wordsPerSec() {
      return elapsedSec <= 0 ? 0.0 : total / elapsedSec;
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println(
          "Usage: EvaluateNormalizationBenchmarks <label> <outJsonPath> [normDataRoot]");
      System.exit(2);
    }
    String label = args[0];
    Path outJsonPath = Paths.get(args[1]);
    Path normDataRoot = args.length >= 3 ? Paths.get(args[2]) : DEFAULT_NORM_DATA;
    Path dataRoot = normDataRoot.resolve("normalization");
    Path lmPath = normDataRoot.resolve("lm.2gram.slm");

    long tInit0 = System.nanoTime();
    TurkishMorphology morphology = TurkishMorphology.createWithDefaults();
    TurkishSentenceNormalizer normalizer =
        new TurkishSentenceNormalizer(morphology, dataRoot, lmPath);
    long heapBeforeSpell = usedHeapBytes();
    TurkishSpellChecker exactSpell = new TurkishSpellChecker(morphology);
    TurkishSpellChecker diacriticSpell = createDiacriticSpellChecker(morphology, exactSpell);
    long spellHeapBytes = Math.max(0L, usedHeapBytes() - heapBeforeSpell);
    double initSec = (System.nanoTime() - tInit0) / 1e9;

    // Warmup
    normalizer.normalize("slm nabersiniz bugun cok guzel bi gun 12 de gelicem");
    exactSpell.suggestForWord("guldum");
    diacriticSpell.suggestForWord("guldum", exactSpell.getUnigramLanguageModel());

    List<SpellPair> trSpellPairs = loadTrSpell10(MUKAYESE_TRSPELL10);
    List<SentPair> nastyPairs = loadNasty5k(NASTY_5K_TSV);
    List<SentPair> multiLexPairs = loadMultiLexNorm(MULTILEXNORM_TR);
    List<SentPair> gecturkAll = loadGecTurk(GECTURK_SRC, GECTURK_TGT);

    Set<String> uniqueClean = new LinkedHashSet<>();
    List<SentPair> gecturkCleanPairs = new ArrayList<>();
    List<SentPair> gecturkNoisyPairs = new ArrayList<>();
    for (SentPair p : gecturkAll) {
      List<String> srcTok = wordTokensLower(p.raw);
      List<String> tgtTok = wordTokensLower(p.gold);
      if (srcTok.equals(tgtTok)) {
        if (uniqueClean.add(p.gold)) {
          gecturkCleanPairs.add(new SentPair(p.gold, p.gold));
        }
      } else {
        gecturkNoisyPairs.add(p);
      }
    }

    SpellStats spellExactStats =
        evalSpellChecker(exactSpell, exactSpell.getUnigramLanguageModel(), trSpellPairs);
    SpellStats spellDiacStats =
        evalSpellChecker(diacriticSpell, exactSpell.getUnigramLanguageModel(), trSpellPairs);

    SentenceEvalStats nastyWordStats = evalSentenceSet(normalizer, nastyPairs, false, 0);
    SentenceEvalStats multiLexWordStats = evalSentenceSet(normalizer, multiLexPairs, false, 0);
    SentenceEvalStats bounCleanStats = evalSentenceSet(normalizer, gecturkCleanPairs, false, 15);
    SentenceEvalStats gecturkNoisyStats = evalSentenceSet(normalizer, gecturkNoisyPairs, false, 0);

    SentenceEvalStats formattedNastyStats = null;
    SentenceEvalStats formattedBounStats = null;
    Method preserveMethod = findNormalizePreservingFormat(normalizer);
    if (preserveMethod != null) {
      formattedNastyStats =
          evalSentenceSetFormatted(normalizer, preserveMethod, nastyPairs, 0);
      formattedBounStats =
          evalSentenceSetFormatted(normalizer, preserveMethod, gecturkCleanPairs, 0);
    }

    printSummary(label, initSec, spellHeapBytes, spellExactStats, spellDiacStats,
        nastyWordStats, multiLexWordStats, bounCleanStats, gecturkNoisyStats,
        formattedNastyStats, formattedBounStats);

    if (outJsonPath.getParent() != null) {
      Files.createDirectories(outJsonPath.getParent());
    }
    writeJson(
        outJsonPath, label, initSec, spellHeapBytes,
        spellExactStats, spellDiacStats,
        nastyWordStats, multiLexWordStats, bounCleanStats, gecturkNoisyStats,
        formattedNastyStats, formattedBounStats);
  }

  private static TurkishSpellChecker createDiacriticSpellChecker(
      TurkishMorphology morphology, TurkishSpellChecker exactSpell) {
    try {
      Constructor<TurkishSpellChecker> ctor =
          TurkishSpellChecker.class.getConstructor(TurkishMorphology.class, boolean.class);
      return ctor.newInstance(morphology, true);
    } catch (Exception ignored) {
      return new TurkishSpellChecker(
          morphology,
          exactSpell.getDecoder(),
          CharacterGraphDecoder.DIACRITICS_IGNORING_MATCHER);
    }
  }

  private static Method findNormalizePreservingFormat(TurkishSentenceNormalizer normalizer) {
    try {
      return normalizer.getClass().getMethod("normalizePreservingFormat", String.class);
    } catch (NoSuchMethodException e) {
      return null;
    }
  }

  private static long usedHeapBytes() {
    for (int i = 0; i < 3; i++) {
      System.gc();
      try {
        Thread.sleep(30);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
    }
    Runtime rt = Runtime.getRuntime();
    return rt.totalMemory() - rt.freeMemory();
  }

  private static SpellStats evalSpellChecker(
      TurkishSpellChecker spell,
      zemberek.lm.NgramLanguageModel lm,
      List<SpellPair> pairs) {
    SpellStats st = new SpellStats();
    st.total = pairs.size();
    long t0 = System.nanoTime();
    for (SpellPair p : pairs) {
      String inLower = p.input.toLowerCase(Turkish.LOCALE);
      String goldLower = p.gold.toLowerCase(Turkish.LOCALE);
      boolean isClean = inLower.equals(goldLower);
      boolean accepted = spell.check(p.input);
      if (isClean) {
        st.clean++;
        if (accepted) {
          st.tn++;
        } else {
          st.fp++;
        }
      } else {
        st.misspelled++;
        if (!accepted) {
          st.tp++;
        } else {
          st.fn++;
        }
        List<String> suggestions = spell.suggestForWord(inLower, lm);
        boolean hit1 = !suggestions.isEmpty()
            && suggestions.get(0).toLowerCase(Turkish.LOCALE).equals(goldLower);
        boolean hit5 = false;
        int limit = Math.min(5, suggestions.size());
        for (int i = 0; i < limit; i++) {
          if (suggestions.get(i).toLowerCase(Turkish.LOCALE).equals(goldLower)) {
            hit5 = true;
            break;
          }
        }
        if (hit1) {
          st.top1All++;
          if (!accepted) {
            st.top1Detected++;
          }
        }
        if (hit5) {
          st.top5All++;
          if (!accepted) {
            st.top5Detected++;
          }
        }
      }
    }
    st.elapsedSec = (System.nanoTime() - t0) / 1e9;
    return st;
  }

  private static SentenceEvalStats evalSentenceSet(
      TurkishSentenceNormalizer normalizer,
      List<SentPair> pairs,
      boolean includePunctuation,
      int maxSamples) {
    SentenceEvalStats st = new SentenceEvalStats();
    st.sentences = pairs.size();
    long t0 = System.nanoTime();
    for (SentPair p : pairs) {
      String sys = normalizer.normalize(p.raw);
      List<String> rawToks = tokenizeLower(p.raw, includePunctuation);
      List<String> sysToks = tokenizeLower(sys, includePunctuation);
      List<String> goldToks = tokenizeLower(p.gold, includePunctuation);

      int rawDist = levenshteinTokens(rawToks, goldToks);
      int sysDist = levenshteinTokens(sysToks, goldToks);
      st.rawTokens += rawToks.size();
      st.goldTokens += goldToks.size();
      st.rawEditDist += rawDist;
      st.sysEditDist += sysDist;
      if (sysToks.equals(goldToks)) {
        st.exactMatch++;
      } else if (st.sampleDiffs.size() < maxSamples) {
        st.sampleDiffs.add(
            "RAW=[" + String.join(" ", rawToks) + "] -> SYS=[" + String.join(" ", sysToks) + "]");
      }
    }
    st.elapsedSec = (System.nanoTime() - t0) / 1e9;
    return st;
  }

  private static SentenceEvalStats evalSentenceSetFormatted(
      TurkishSentenceNormalizer normalizer,
      Method preserveMethod,
      List<SentPair> pairs,
      int maxSamples) throws Exception {
    SentenceEvalStats st = new SentenceEvalStats();
    st.sentences = pairs.size();
    long t0 = System.nanoTime();
    for (SentPair p : pairs) {
      String sys = (String) preserveMethod.invoke(normalizer, p.raw);
      List<String> rawToks = tokenizeExact(p.raw, true);
      List<String> sysToks = tokenizeExact(sys, true);
      List<String> goldToks = tokenizeExact(p.gold, true);

      int rawDist = levenshteinTokens(rawToks, goldToks);
      int sysDist = levenshteinTokens(sysToks, goldToks);
      st.rawTokens += rawToks.size();
      st.goldTokens += goldToks.size();
      st.rawEditDist += rawDist;
      st.sysEditDist += sysDist;
      if (sysToks.equals(goldToks)) {
        st.exactMatch++;
      } else if (st.sampleDiffs.size() < maxSamples) {
        st.sampleDiffs.add(
            "RAW=[" + String.join(" ", rawToks) + "] -> SYS=[" + String.join(" ", sysToks) + "]");
      }
    }
    st.elapsedSec = (System.nanoTime() - t0) / 1e9;
    return st;
  }

  private static List<String> tokenizeExact(String text, boolean includePunctuation) {
    List<Token> toks = TurkishTokenizer.DEFAULT.tokenize(text);
    List<String> out = new ArrayList<>(toks.size());
    for (Token t : toks) {
      if (!includePunctuation && t.getType() == Token.Type.Punctuation) {
        continue;
      }
      out.add(t.getText());
    }
    return out;
  }

  private static List<String> wordTokensLower(String text) {
    return tokenizeLower(text, false);
  }

  private static List<String> tokenizeLower(String text, boolean includePunctuation) {
    List<Token> toks = TurkishTokenizer.DEFAULT.tokenize(text);
    List<String> out = new ArrayList<>(toks.size());
    for (Token t : toks) {
      if (!includePunctuation && t.getType() == Token.Type.Punctuation) {
        continue;
      }
      out.add(t.getText().toLowerCase(Turkish.LOCALE));
    }
    return out;
  }

  private static int levenshteinTokens(List<String> a, List<String> b) {
    int n = a.size();
    int m = b.size();
    if (n == 0) {
      return m;
    }
    if (m == 0) {
      return n;
    }
    int[] prev = new int[m + 1];
    int[] curr = new int[m + 1];
    for (int j = 0; j <= m; j++) {
      prev[j] = j;
    }
    for (int i = 1; i <= n; i++) {
      curr[0] = i;
      String ai = a.get(i - 1);
      for (int j = 1; j <= m; j++) {
        int cost = ai.equals(b.get(j - 1)) ? 0 : 1;
        int del = prev[j] + 1;
        int ins = curr[j - 1] + 1;
        int sub = prev[j - 1] + cost;
        curr[j] = Math.min(sub, Math.min(del, ins));
      }
      int[] tmp = prev;
      prev = curr;
      curr = tmp;
    }
    return prev[m];
  }

  private static List<SpellPair> loadTrSpell10(Path path) throws IOException {
    List<SpellPair> out = new ArrayList<>();
    try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String line = br.readLine(); // header input,gold
      while ((line = br.readLine()) != null) {
        if (line.isEmpty()) {
          continue;
        }
        int comma = line.indexOf(',');
        if (comma > 0) {
          out.add(new SpellPair(line.substring(0, comma).trim(), line.substring(comma + 1).trim()));
        }
      }
    }
    return out;
  }

  private static List<SentPair> loadNasty5k(Path path) throws IOException {
    List<SentPair> out = new ArrayList<>();
    try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String line = br.readLine(); // header
      while ((line = br.readLine()) != null) {
        if (line.isEmpty()) {
          continue;
        }
        String[] parts = line.split("\t", -1);
        if (parts.length >= 3) {
          out.add(new SentPair(parts[1], parts[2]));
        }
      }
    }
    return out;
  }

  private static List<SentPair> loadMultiLexNorm(Path path) throws IOException {
    List<SentPair> out = new ArrayList<>();
    List<String> raw = new ArrayList<>();
    List<String> gold = new ArrayList<>();
    try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String line;
      while ((line = br.readLine()) != null) {
        if (line.trim().isEmpty()) {
          if (!raw.isEmpty()) {
            out.add(new SentPair(String.join(" ", raw), String.join(" ", gold)));
            raw.clear();
            gold.clear();
          }
          continue;
        }
        String[] parts = line.split("\t", -1);
        if (parts.length >= 2) {
          raw.add(parts[0]);
          gold.add(parts[1]);
        }
      }
    }
    if (!raw.isEmpty()) {
      out.add(new SentPair(String.join(" ", raw), String.join(" ", gold)));
    }
    return out;
  }

  private static List<SentPair> loadGecTurk(Path m2Src, Path tgtPath) throws IOException {
    List<String> sources = new ArrayList<>();
    try (BufferedReader br = Files.newBufferedReader(m2Src, StandardCharsets.UTF_8)) {
      String line;
      while ((line = br.readLine()) != null) {
        if (line.startsWith("S ")) {
          sources.add(line.substring(2));
        }
      }
    }
    List<String> targets = Files.readAllLines(tgtPath, StandardCharsets.UTF_8);
    int n = Math.min(sources.size(), targets.size());
    List<SentPair> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      out.add(new SentPair(sources.get(i), targets.get(i)));
    }
    return out;
  }

  private static void printSummary(
      String label,
      double initSec,
      long spellHeapBytes,
      SpellStats spellExact,
      SpellStats spellDiac,
      SentenceEvalStats nasty,
      SentenceEvalStats multiLex,
      SentenceEvalStats bounClean,
      SentenceEvalStats gecturkNoisy,
      SentenceEvalStats formattedNasty,
      SentenceEvalStats formattedBoun) {
    System.out.printf(
        Locale.US,
        "=== BENCHMARK SUMMARY [%s] (init=%.2fs, spellHeap=%.2fMB) ===%n",
        label, initSec, spellHeapBytes / (1024.0 * 1024.0));
    System.out.printf(
        Locale.US,
        "Mukayese TRSpell10 Exact    : n=%d (misspelled=%d) F1=%.2f%% Top1=%.2f%% Top5=%.2f%% %.0f w/s%n",
        spellExact.total, spellExact.misspelled,
        spellExact.f1() * 100.0, spellExact.top1AllAcc() * 100.0, spellExact.top5AllAcc() * 100.0,
        spellExact.wordsPerSec());
    System.out.printf(
        Locale.US,
        "Mukayese TRSpell10 DiacIgn  : n=%d (misspelled=%d) F1=%.2f%% Top1=%.2f%% Top5=%.2f%% %.0f w/s%n",
        spellDiac.total, spellDiac.misspelled,
        spellDiac.f1() * 100.0, spellDiac.top1AllAcc() * 100.0, spellDiac.top5AllAcc() * 100.0,
        spellDiac.wordsPerSec());
    System.out.printf(
        Locale.US,
        "nasty_5k (%d sents)       : RawDist=%d SysDist=%d W-ERR=%.2f%% WER=%.2f%% SentAcc=%.2f%% %.0f tok/s%n",
        nasty.sentences, nasty.rawEditDist, nasty.sysEditDist,
        nasty.wErr() * 100.0, nasty.sysWer() * 100.0, nasty.sentAcc() * 100.0, nasty.tokPerSec());
    System.out.printf(
        Locale.US,
        "MultiLexNorm TR (%d sents) : RawDist=%d SysDist=%d W-ERR=%.2f%% WER=%.2f%% SentAcc=%.2f%% %.0f tok/s%n",
        multiLex.sentences, multiLex.rawEditDist, multiLex.sysEditDist,
        multiLex.wErr() * 100.0, multiLex.sysWer() * 100.0, multiLex.sentAcc() * 100.0,
        multiLex.tokPerSec());
    System.out.printf(
        Locale.US,
        "BOUN Clean Control (%d)    : CorruptedEdits=%d/%d (%.3f%%) SentPreserved=%.2f%%%n",
        bounClean.sentences, bounClean.sysEditDist, bounClean.goldTokens,
        bounClean.sysWer() * 100.0, bounClean.sentAcc() * 100.0);
    System.out.printf(
        Locale.US,
        "GECTurk Noisy (%d sents)   : RawDist=%d SysDist=%d W-ERR=%.2f%% WER=%.2f%% SentAcc=%.2f%%%n",
        gecturkNoisy.sentences, gecturkNoisy.rawEditDist, gecturkNoisy.sysEditDist,
        gecturkNoisy.wErr() * 100.0, gecturkNoisy.sysWer() * 100.0, gecturkNoisy.sentAcc() * 100.0);
    if (formattedNasty != null && formattedBoun != null) {
      System.out.printf(
          Locale.US,
          "Formatted nasty_5k (Case+Punc) : SysDist=%d W-ERR=%.2f%% WER=%.2f%% SentAcc=%.2f%%%n",
          formattedNasty.sysEditDist, formattedNasty.wErr() * 100.0,
          formattedNasty.sysWer() * 100.0, formattedNasty.sentAcc() * 100.0);
      System.out.printf(
          Locale.US,
          "Formatted BOUN Clean (Case+Punc): CorruptedEdits=%d/%d (%.3f%%) SentPreserved=%.2f%%%n",
          formattedBoun.sysEditDist, formattedBoun.goldTokens,
          formattedBoun.sysWer() * 100.0, formattedBoun.sentAcc() * 100.0);
    }
  }

  private static void writeJson(
      Path outPath,
      String label,
      double initSec,
      long spellHeapBytes,
      SpellStats spellExact,
      SpellStats spellDiac,
      SentenceEvalStats nasty,
      SentenceEvalStats multiLex,
      SentenceEvalStats bounClean,
      SentenceEvalStats gecturkNoisy,
      SentenceEvalStats formattedNasty,
      SentenceEvalStats formattedBoun) throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.append("{\n");
    sb.append("  \"label\": \"").append(escapeJson(label)).append("\",\n");
    sb.append(String.format(Locale.US, "  \"initSec\": %.4f,\n", initSec));
    sb.append("  \"spellHeapBytes\": ").append(spellHeapBytes).append(",\n");
    appendSpellJson(sb, "mukayese_exact", spellExact, true);
    appendSpellJson(sb, "mukayese_diac_ignoring", spellDiac, true);
    appendSentJson(sb, "nasty_5k", nasty, true);
    appendSentJson(sb, "multilexnorm_tr", multiLex, true);
    appendSentJson(sb, "boun_clean", bounClean, true);
    boolean hasFormatted = formattedNasty != null && formattedBoun != null;
    appendSentJson(sb, "gecturk_noisy", gecturkNoisy, hasFormatted);
    if (hasFormatted) {
      appendSentJson(sb, "formatted_nasty_5k", formattedNasty, true);
      appendSentJson(sb, "formatted_boun_clean", formattedBoun, false);
    }
    sb.append("}\n");
    try (BufferedWriter bw = Files.newBufferedWriter(outPath, StandardCharsets.UTF_8)) {
      bw.write(sb.toString());
    }
  }

  private static void appendSpellJson(StringBuilder sb, String name, SpellStats s, boolean comma) {
    sb.append("  \"").append(name).append("\": {\n");
    sb.append("    \"total\": ").append(s.total).append(",\n");
    sb.append("    \"misspelled\": ").append(s.misspelled).append(",\n");
    sb.append("    \"clean\": ").append(s.clean).append(",\n");
    sb.append("    \"tp\": ").append(s.tp).append(",\n");
    sb.append("    \"fp\": ").append(s.fp).append(",\n");
    sb.append("    \"fn\": ").append(s.fn).append(",\n");
    sb.append("    \"tn\": ").append(s.tn).append(",\n");
    sb.append(String.format(Locale.US, "    \"precision\": %.6f,\n", s.precision()));
    sb.append(String.format(Locale.US, "    \"recall\": %.6f,\n", s.recall()));
    sb.append(String.format(Locale.US, "    \"f1\": %.6f,\n", s.f1()));
    sb.append("    \"top1All\": ").append(s.top1All).append(",\n");
    sb.append("    \"top5All\": ").append(s.top5All).append(",\n");
    sb.append(String.format(Locale.US, "    \"top1Acc\": %.6f,\n", s.top1AllAcc()));
    sb.append(String.format(Locale.US, "    \"top5Acc\": %.6f,\n", s.top5AllAcc()));
    sb.append(String.format(Locale.US, "    \"wordsPerSec\": %.2f\n", s.wordsPerSec()));
    sb.append("  }").append(comma ? ",\n" : "\n");
  }

  private static void appendSentJson(
      StringBuilder sb, String name, SentenceEvalStats s, boolean comma) {
    sb.append("  \"").append(name).append("\": {\n");
    sb.append("    \"sentences\": ").append(s.sentences).append(",\n");
    sb.append("    \"rawTokens\": ").append(s.rawTokens).append(",\n");
    sb.append("    \"goldTokens\": ").append(s.goldTokens).append(",\n");
    sb.append("    \"rawEditDist\": ").append(s.rawEditDist).append(",\n");
    sb.append("    \"sysEditDist\": ").append(s.sysEditDist).append(",\n");
    sb.append("    \"exactMatch\": ").append(s.exactMatch).append(",\n");
    sb.append(String.format(Locale.US, "    \"wErr\": %.6f,\n", s.wErr()));
    sb.append(String.format(Locale.US, "    \"rawWer\": %.6f,\n", s.rawWer()));
    sb.append(String.format(Locale.US, "    \"sysWer\": %.6f,\n", s.sysWer()));
    sb.append(String.format(Locale.US, "    \"sentAcc\": %.6f,\n", s.sentAcc()));
    sb.append(String.format(Locale.US, "    \"elapsedSec\": %.4f,\n", s.elapsedSec));
    sb.append(String.format(Locale.US, "    \"tokensPerSec\": %.2f,\n", s.tokPerSec()));
    sb.append("    \"sampleDiffs\": [");
    for (int i = 0; i < s.sampleDiffs.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append("\"").append(escapeJson(s.sampleDiffs.get(i))).append("\"");
    }
    sb.append("]\n");
    sb.append("  }").append(comma ? ",\n" : "\n");
  }

  private static String escapeJson(String s) {
    StringBuilder out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\') {
        out.append("\\\\");
      } else if (c == '"') {
        out.append("\\\"");
      } else if (c == '\n') {
        out.append("\\n");
      } else if (c == '\r') {
        out.append("\\r");
      } else if (c == '\t') {
        out.append("\\t");
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
}
