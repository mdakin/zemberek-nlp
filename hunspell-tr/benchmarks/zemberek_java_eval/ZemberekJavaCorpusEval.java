import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;
import zemberek.core.turkish.RootAttribute;
import zemberek.core.turkish.TurkishAlphabet;
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.analysis.SingleAnalysis;
import zemberek.morphology.analysis.WordAnalysis;
import zemberek.morphology.analysis.WordAnalysisSurfaceFormatter;
import zemberek.morphology.lexicon.DictionaryItem;
import zemberek.morphology.lexicon.RootLexicon;

public class ZemberekJavaCorpusEval {

  static final class FreqPair {
    final LongAdder formal = new LongAdder();
    final LongAdder forum = new LongAdder();
  }

  static final class WordRow {
    final String word;
    final long formalFreq;
    final long forumFreq;
    final long totalFreq;
    String category; // STRICT_SPELL_OK, CASE_OR_APOS_ONLY, EXT_OR_INFORMAL, DYNAMIC_RUNTIME, UNSOLVED

    WordRow(String word, long formalFreq, long forumFreq) {
      this.word = word;
      this.formalFreq = formalFreq;
      this.forumFreq = forumFreq;
      this.totalFreq = formalFreq + forumFreq;
    }
  }

  private static boolean isTurkishOrAsciiLetter(char c) {
    return (c >= 'a' && c <= 'z')
        || (c >= 'A' && c <= 'Z')
        || c == 'ç' || c == 'ğ' || c == 'ı' || c == 'ö' || c == 'ş' || c == 'ü'
        || c == 'Ç' || c == 'Ğ' || c == 'İ' || c == 'Ö' || c == 'Ş' || c == 'Ü'
        || c == 'â' || c == 'î' || c == 'û' || c == 'Â' || c == 'Î' || c == 'Û';
  }

  private static boolean isWordChar(char c) {
    return isTurkishOrAsciiLetter(c) || (c >= '0' && c <= '9') || c == '\'';
  }

  private static char normalizeAposChar(char c) {
    if (c == '’' || c == '‘' || c == 'ʼ') {
      return '\'';
    }
    return c;
  }

  // Replicates Chrome's SpellcheckWordIterator + HunspellEngine::CheckSpelling (NFKC, <=64 UTF-8B, >=1 Turkish/Latin letter, no non-Turkish scripts).
  private static void tokenizeLineIntoLocal(String rawLine, Map<String, long[]> localCounts, int colIdx) {
    String line = Normalizer.normalize(rawLine, Normalizer.Form.NFKC);
    int n = line.length();
    int i = 0;
    StringBuilder sb = new StringBuilder(32);
    while (i < n) {
      char c = normalizeAposChar(line.charAt(i));
      if (isWordChar(c)) {
        sb.setLength(0);
        boolean hasLetter = false;
        boolean badForeignChar = false;
        while (i < n) {
          char ch = normalizeAposChar(line.charAt(i));
          if (isWordChar(ch)) {
            if (isTurkishOrAsciiLetter(ch)) {
              hasLetter = true;
            }
            sb.append(ch);
            i++;
          } else if (Character.isLetter(ch)) {
            // Non-Turkish script attached inside token (e.g., Cyrillic/Greek/Arabic) -> skip whole token
            badForeignChar = true;
            sb.append(ch);
            i++;
          } else {
            break;
          }
        }
        if (badForeignChar || !hasLetter) {
          continue;
        }
        // Trim leading/trailing apostrophes
        int start = 0;
        int end = sb.length();
        while (start < end && sb.charAt(start) == '\'') start++;
        while (end > start && sb.charAt(end - 1) == '\'') end--;
        int charLen = end - start;
        if (charLen < 2 || charLen > 48) {
          continue;
        }
        String tok = sb.substring(start, end);
        // Skip URL/domain fragments if preceded/followed by www/http
        if (tok.startsWith("http") || tok.startsWith("www")) {
          continue;
        }
        int utf8Bytes = tok.getBytes(StandardCharsets.UTF_8).length;
        if (utf8Bytes <= 64) {
          long[] pair = localCounts.computeIfAbsent(tok, k -> new long[2]);
          pair[colIdx]++;
        }
      } else {
        i++;
      }
    }
  }

  private static void scanFile(Path path, int colIdx, ConcurrentHashMap<String, FreqPair> globalMap)
      throws IOException {
    long t0 = System.nanoTime();
    List<String> batch = new ArrayList<>(65536);
    long totalLines = 0;
    try (BufferedReader br = new BufferedReader(
        new InputStreamReader(new FileInputStream(path.toFile()), StandardCharsets.UTF_8), 1 << 20)) {
      String line;
      while ((line = br.readLine()) != null) {
        batch.add(line);
        totalLines++;
        if (batch.size() >= 65536) {
          processBatch(batch, colIdx, globalMap);
          batch.clear();
        }
      }
      if (!batch.isEmpty()) {
        processBatch(batch, colIdx, globalMap);
        batch.clear();
      }
    }
    double sec = (System.nanoTime() - t0) / 1e9;
    System.out.printf("  Scanned %-34s (%,10d lines) in %.2f s%n",
        path.getFileName().toString(), totalLines, sec);
  }

  private static void processBatch(
      List<String> lines, int colIdx, ConcurrentHashMap<String, FreqPair> globalMap) {
    int numChunks = Math.max(1, Runtime.getRuntime().availableProcessors());
    int chunkSize = (lines.size() + numChunks - 1) / numChunks;
    IntStream.range(0, numChunks).parallel().forEach(ci -> {
      int from = ci * chunkSize;
      int to = Math.min(lines.size(), from + chunkSize);
      if (from >= to) return;
      Map<String, long[]> local = new HashMap<>(16384);
      for (int idx = from; idx < to; idx++) {
        tokenizeLineIntoLocal(lines.get(idx), local, colIdx);
      }
      for (Map.Entry<String, long[]> e : local.entrySet()) {
        FreqPair fp = globalMap.computeIfAbsent(e.getKey(), k -> new FreqPair());
        long[] v = e.getValue();
        if (v[0] > 0) fp.formal.add(v[0]);
        if (v[1] > 0) fp.forum.add(v[1]);
      }
    });
  }

  private static boolean matchesFormattedSurface(
      WordAnalysisSurfaceFormatter formatter,
      SingleAnalysis a,
      String input,
      WordAnalysisSurfaceFormatter.CaseType caseType,
      String apos) {
    if (!formatter.canBeFormatted(a, caseType)) {
      return false;
    }
    String formatted = formatter.formatToCase(a, caseType, apos);
    if (input.equals(formatted)) {
      return true;
    }
    // Zemberek Java strips circumflexes in getStem(); preserve valid circumflex words like `hâlâ`, `resmî`, `kâğıt`
    DictionaryItem item = a.getDictionaryItem();
    if (TurkishAlphabet.INSTANCE.containsCircumflex(input)
        || TurkishAlphabet.INSTANCE.containsCircumflex(item.lemma)) {
      String normIn = TurkishAlphabet.INSTANCE.normalizeCircumflex(input);
      String normFmt = TurkishAlphabet.INSTANCE.normalizeCircumflex(formatted);
      return normIn.equals(normFmt);
    }
    return false;
  }

  private static String classifyWord(
      TurkishMorphology morph, WordAnalysisSurfaceFormatter formatter, String word) {
    WordAnalysis wa = morph.analyze(word);
    if (!wa.isCorrect()) {
      return "UNSOLVED";
    }
    WordAnalysisSurfaceFormatter.CaseType caseType = formatter.guessCase(word);
    String apos = word.indexOf('\'') > 0 ? "'" : null;

    boolean hasStrictSpellOk = false;
    boolean hasStaticNonExt = false;
    boolean hasExtOrInformal = false;
    boolean hasDynamicRuntime = false;

    for (SingleAnalysis a : wa) {
      if (a.isUnknown()) {
        continue;
      }
      DictionaryItem item = a.getDictionaryItem();
      boolean isRuntime = a.isRuntime() || item.hasAttribute(RootAttribute.Runtime);
      boolean isExtOrInf = item.hasAttribute(RootAttribute.Ext)
          || item.hasAttribute(RootAttribute.Informal)
          || a.containsInformalMorpheme();

      if (isRuntime) {
        hasDynamicRuntime = true;
      } else if (isExtOrInf) {
        hasExtOrInformal = true;
      } else {
        hasStaticNonExt = true;
        if (matchesFormattedSurface(formatter, a, word, caseType, apos)) {
          hasStrictSpellOk = true;
          break;
        }
      }
    }

    if (hasStrictSpellOk) {
      return "STRICT_SPELL_OK";
    }
    if (hasStaticNonExt) {
      return "CASE_OR_APOS_ONLY";
    }
    if (hasExtOrInformal) {
      return "EXT_OR_INFORMAL";
    }
    if (hasDynamicRuntime) {
      return "DYNAMIC_RUNTIME";
    }
    return "UNSOLVED";
  }

  public static void main(String[] args) throws Exception {
    // Ensure Turkish.<clinit> initializes Turkish.LOCALE before TurkishAlphabet/DictionarySerializer
    if (zemberek.core.turkish.Turkish.LOCALE == null) {
      throw new IllegalStateException("Turkish.LOCALE is null");
    }
    String corpusRoot = System.getenv().getOrDefault("TURKISH_CORPUS_ROOT", "data/turkish");
    Path outDir = Path.of(
        System.getenv().getOrDefault("ZEMBEREK_EVAL_OUT", "hunspell-tr/benchmarks/zemberek_java_eval"));
    Files.createDirectories(outDir);

    System.out.println("=== [1/3] Initializing Zemberek Java 0.20.1 RootLexicon ===");
    long tInit = System.nanoTime();
    RootLexicon sharedLexicon = RootLexicon.getDefault();
    System.out.printf("  Loaded Zemberek Java RootLexicon (%,d items) in %.2f s%n",
        sharedLexicon.size(), (System.nanoTime() - tInit) / 1e9);

    System.out.println("\n=== [2/3] Scanning 1.63 GB Strictly Held-Out RAW Unfiltered Corpora ===");
    ConcurrentHashMap<String, FreqPair> counts = new ConcurrentHashMap<>(1 << 22);
    // 100% Raw, Unfiltered Formal Held-Out Corpora (colIdx = 0: 848 MB)
    scanFile(Path.of(corpusRoot, "modern_formal_2020_2026/plain/wmt_news_2025.txt"), 0, counts);
    scanFile(Path.of(corpusRoot, "modern_formal_2020_2026/plain/mevzuat_2025_clean.txt"), 0, counts);
    // 100% Raw, Unfiltered Noisy User-Forum Corpora (colIdx = 1: 778 MB)
    scanFile(Path.of(corpusRoot, "noisy/www.kizlarsoruyor.com.txt"), 1, counts);
    scanFile(Path.of(corpusRoot, "raw/hukuki-net.txt"), 1, counts);

    List<WordRow> rows = new ArrayList<>(counts.size());
    long sumFormal = 0, sumForum = 0;
    for (Map.Entry<String, FreqPair> e : counts.entrySet()) {
      long fm = e.getValue().formal.sum();
      long fr = e.getValue().forum.sum();
      sumFormal += fm;
      sumForum += fr;
      rows.add(new WordRow(e.getKey(), fm, fr));
    }
    counts.clear();
    rows.sort(Comparator.comparingLong((WordRow r) -> r.totalFreq).reversed().thenComparing(r -> r.word));
    System.out.printf("  Total running tokens: %,d (Formal: %,d | Forum: %,d)%n",
        (sumFormal + sumForum), sumFormal, sumForum);
    System.out.printf("  Total unique surface word types: %,d%n", rows.size());

    System.out.println("\n=== [3/3] Analyzing All Unique Word Types with Zemberek Java (Thread-Isolated Instances) ===");
    long tAna = System.nanoTime();
    // Each worker thread gets its own isolated TurkishMorphology + StemTransitionsTrie because
    // UnidentifiedTokenAnalyzer mutates analyzer.getStemTransitions() during runtime ProperNoun/Numeral analysis!
    ThreadLocal<TurkishMorphology> tlMorph = ThreadLocal.withInitial(() ->
        TurkishMorphology.builder().setLexicon(sharedLexicon).disableCache().build());
    ThreadLocal<WordAnalysisSurfaceFormatter> tlFmt =
        ThreadLocal.withInitial(WordAnalysisSurfaceFormatter::new);

    IntStream.range(0, rows.size()).parallel().forEach(idx -> {
      WordRow r = rows.get(idx);
      r.category = classifyWord(tlMorph.get(), tlFmt.get(), r.word);
    });
    System.out.printf("  Zemberek Java analyzed %,d unique word types in %.2f s%n",
        rows.size(), (System.nanoTime() - tAna) / 1e9);

    Path solvedPath = outDir.resolve("zemberek_solved_with_freq.tsv");
    Path unsolvedPath = outDir.resolve("zemberek_unsolved_with_freq.tsv");

    long solvedTypes = 0, solvedTokens = 0;
    long unsolvedTypes = 0, unsolvedTokens = 0;

    try (BufferedWriter wSolved = new BufferedWriter(
            new OutputStreamWriter(new FileOutputStream(solvedPath.toFile()), StandardCharsets.UTF_8), 1 << 20);
         BufferedWriter wUnsolved = new BufferedWriter(
            new OutputStreamWriter(new FileOutputStream(unsolvedPath.toFile()), StandardCharsets.UTF_8), 1 << 20)) {
      for (WordRow r : rows) {
        String line = r.word + "\t" + r.totalFreq + "\t" + r.formalFreq + "\t" + r.forumFreq + "\t" + r.category + "\n";
        if ("UNSOLVED".equals(r.category)) {
          wUnsolved.write(line);
          unsolvedTypes++;
          unsolvedTokens += r.totalFreq;
        } else {
          wSolved.write(line);
          solvedTypes++;
          solvedTokens += r.totalFreq;
        }
      }
    }

    System.out.printf("  Wrote List 1 (Zemberek SOLVED):   %,10d unique words | %,12d tokens (%.2f%%)%n",
        solvedTypes, solvedTokens, 100.0 * solvedTokens / (solvedTokens + unsolvedTokens));
    System.out.printf("  Wrote List 2 (Zemberek UNSOLVED): %,10d unique words | %,12d tokens (%.2f%%)%n",
        unsolvedTypes, unsolvedTokens, 100.0 * unsolvedTokens / (solvedTokens + unsolvedTokens));
  }
}
