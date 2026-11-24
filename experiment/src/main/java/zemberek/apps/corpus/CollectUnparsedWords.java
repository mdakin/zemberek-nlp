package zemberek.apps.corpus;

import com.beust.jcommander.Parameter;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import zemberek.apps.ConsoleApp;
import zemberek.core.logging.Log;
import zemberek.core.turkish.Turkish;
import zemberek.core.turkish.TurkishAlphabet;
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.analysis.WordAnalysis;
import zemberek.morphology.lexicon.DictionaryItem;
import zemberek.morphology.lexicon.RootLexicon;
import zemberek.tokenization.Token;
import zemberek.tokenization.Token.Type;
import zemberek.tokenization.TurkishTokenizer;

/**
 * Scans cleaned Turkish text corpora, identifies words that Zemberek morphological analyzer cannot parse
 * (produces 0 analyses), counts their frequencies across the entire corpus, and exports them sorted
 * by frequency descending.
 */
public class CollectUnparsedWords extends ConsoleApp {

  @Parameter(
      names = {"--input", "-i"},
      description = "Input directory or file containing clean Turkish sentences."
  )
  public Path input = Paths.get(System.getProperty("user.home"), "data", "turkish", "clean");

  @Parameter(
      names = {"--output", "-o"},
      description = "Output TSV/text file path for sorted unparsed words and frequencies."
  )
  public Path output = Paths.get(System.getProperty("user.home"), "data", "turkish", "unparsed_words.tsv");

  @Parameter(
      names = {"--threadCount", "-tc"},
      description = "Number of worker threads (defaults to availableProcessors * 2)."
  )
  public int threadCount = Runtime.getRuntime().availableProcessors() * 2;

  @Parameter(
      names = {"--minFrequency", "-mf"},
      description = "Minimum frequency threshold to include a word in the final report."
  )
  public int minFrequency = 1;

  @Parameter(
      names = {"--lowercase", "-lc"},
      arity = 1,
      description = "If true, converts words to lowercase before checking and counting."
  )
  public boolean lowercase = false;

  @Parameter(
      names = {"--batchSize", "-b"},
      description = "Lines per batch to read before tokenizing."
  )
  public int batchSize = 10_000;

  @Override
  public String description() {
    return "Collects all words from clean text corpora that Zemberek cannot parse, "
        + "sorted by frequency descending.";
  }

  // Known parsable words cache (shared across all threads, ~5ns lookup)
  private final Set<String> knownWordsCache = ConcurrentHashMap.newKeySet(500_000);

  // Unparsed words frequency map
  private final ConcurrentHashMap<String, LongAdder> unparsedWordCounts = new ConcurrentHashMap<>(300_000);

  // Morphological analyzer instance (thread-safe for analyze calls)
  private TurkishMorphology morphology;

  // Global metrics
  private final AtomicLong totalLinesRead = new AtomicLong(0);
  private final AtomicLong totalWordTokens = new AtomicLong(0);
  private final AtomicLong totalUnparsedOccurrences = new AtomicLong(0);
  private final AtomicLong totalBytesRead = new AtomicLong(0);
  private final AtomicInteger filesProcessed = new AtomicInteger(0);

  @Override
  public void run() throws IOException, InterruptedException {
    if (!Files.exists(input)) {
      Log.error("Input path does not exist: %s", input);
      System.exit(1);
    }

    if (output.getParent() != null) {
      Files.createDirectories(output.getParent());
    }

    List<Path> files = collectFiles(input);
    if (files.isEmpty()) {
      Log.warn("No input files found in: %s", input);
      return;
    }

    long totalInputBytes = 0;
    for (Path f : files) {
      totalInputBytes += Files.size(f);
    }
    final long finalTotalBytes = totalInputBytes;

    Log.info("Initializing TurkishMorphology (perceptron disambiguator bypassed, unidentified token analyzer disabled)...");
    this.morphology = TurkishMorphology.builder()
        .setLexicon(RootLexicon.getDefault())
        .disableUnidentifiedTokenAnalyzer()
        .build();

    // Pre-populate knownWordsCache with default lexicon items to eliminate redundant root analyses
    for (DictionaryItem item : morphology.getLexicon()) {
      knownWordsCache.add(item.lemma);
      if (item.primaryPos != null) {
        knownWordsCache.add(item.root);
      }
    }
    Log.info("Pre-seeded known words cache with %,d dictionary entries.", knownWordsCache.size());

    Log.info("Starting CollectUnparsedWords pipeline:");
    Log.info("  Input:            %s", input);
    Log.info("  Output:           %s", output);
    Log.info("  Total Files:      %d (%.2f GB)", files.size(), totalInputBytes / (1024.0 * 1024 * 1024));
    Log.info("  Threads:          %d (SMT/cores * 2)", threadCount);
    Log.info("  Min Frequency:    %d", minFrequency);
    Log.info("  Lowercase:        %s", lowercase);

    long startTime = System.currentTimeMillis();
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);

    for (Path file : files) {
      executor.submit(() -> {
        try {
          processFile(file);
          int completed = filesProcessed.incrementAndGet();
          if (completed % 5 == 0 || completed == files.size()) {
            reportProgress(completed, files.size(), finalTotalBytes, startTime);
          }
        } catch (Exception e) {
          Log.error("Error processing file %s: %s", file, e.getMessage());
        }
      });
    }

    executor.shutdown();
    boolean finished = executor.awaitTermination(7, TimeUnit.DAYS);

    long totalTimeMs = System.currentTimeMillis() - startTime;
    double seconds = totalTimeMs / 1000.0;
    double throughputMBps = (totalBytesRead.get() / (1024.0 * 1024.0)) / (seconds > 0 ? seconds : 1.0);

    Log.info("\nCorpus scan complete in %.2fs (%.2f MB/s)!", seconds, throughputMBps);
    Log.info("  Total Lines:              %s", String.format("%,d", totalLinesRead.get()));
    Log.info("  Total Word Tokens:        %s", String.format("%,d", totalWordTokens.get()));
    Log.info("  Unique Known Words:       %s", String.format("%,d", knownWordsCache.size()));
    Log.info("  Unique Unparsed Words:    %s", String.format("%,d", unparsedWordCounts.size()));
    Log.info("  Unparsed Word Tokens:     %s (%.2f%% of all word tokens)",
        String.format("%,d", totalUnparsedOccurrences.get()),
        totalWordTokens.get() > 0 ? (totalUnparsedOccurrences.get() * 100.0 / totalWordTokens.get()) : 0.0);

    Log.info("Sorting unparsed words by frequency descending and writing to %s...", output);
    writeSortedResults();
    Log.info("Finished successfully!");
  }

  private void processFile(Path file) throws IOException {
    long fileSize = Files.size(file);
    totalBytesRead.addAndGet(fileSize);

    TurkishTokenizer tokenizer = TurkishTokenizer.DEFAULT;

    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        totalLinesRead.incrementAndGet();
        if (line.isEmpty()) {
          continue;
        }

        List<Token> tokens = tokenizer.tokenize(line);
        for (Token token : tokens) {
          Type type = token.getType();

          // Skip non-word tokens (punctuation, numerals, dates/times, URLs, emails, emojis, mentions, hashtags)
          if (type == Type.Punctuation || type == Type.URL || type == Type.Email
              || type == Type.Emoji || type == Type.Emoticon || type == Type.Mention
              || type == Type.HashTag || type == Type.MetaTag || type == Type.SpaceTab
              || type == Type.NewLine || type == Type.Number || type == Type.RomanNumeral
              || type == Type.PercentNumeral || type == Type.Date || type == Type.Time) {
            continue;
          }

          String text = token.getText();
          if (text.isEmpty() || !containsAtLeastOneLetter(text)) {
            continue;
          }

          if (lowercase) {
            text = text.toLowerCase(Turkish.LOCALE);
          }

          totalWordTokens.incrementAndGet();

          // Fast path 1: Already known parsable word
          if (knownWordsCache.contains(text)) {
            continue;
          }

          // Fast path 2: Already known unparsed word
          LongAdder existingAdder = unparsedWordCounts.get(text);
          if (existingAdder != null) {
            existingAdder.increment();
            totalUnparsedOccurrences.incrementAndGet();
            continue;
          }

          // Slow path: Not seen before, analyze with TurkishMorphology
          WordAnalysis analysis = morphology.analyze(text);
          if (analysis.analysisCount() > 0) {
            knownWordsCache.add(text);
          } else {
            unparsedWordCounts.computeIfAbsent(text, k -> new LongAdder()).increment();
            totalUnparsedOccurrences.incrementAndGet();
          }
        }
      }
    }
  }

  private boolean containsAtLeastOneLetter(String str) {
    for (int i = 0; i < str.length(); i++) {
      char c = str.charAt(i);
      if (Character.isLetter(c)) {
        return true;
      }
    }
    return false;
  }

  private void writeSortedResults() throws IOException {
    List<Map.Entry<String, LongAdder>> sorted = unparsedWordCounts.entrySet().stream()
        .filter(e -> e.getValue().sum() >= minFrequency)
        .sorted(Comparator.comparingLong((Map.Entry<String, LongAdder> e) -> e.getValue().sum()).reversed())
        .collect(Collectors.toList());

    try (BufferedWriter bw = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
      bw.write("word\tfrequency\n");
      for (Map.Entry<String, LongAdder> entry : sorted) {
        bw.write(entry.getKey() + "\t" + entry.getValue().sum() + "\n");
      }
    }

    Log.info("Exported %,d unparsed words (freq >= %d) to %s", sorted.size(), minFrequency, output);

    // Print Top 25 unparsed words to log for quick inspection
    Log.info("\n--- TOP 25 UNPARSED WORDS BY FREQUENCY ---");
    int limit = Math.min(25, sorted.size());
    for (int i = 0; i < limit; i++) {
      Map.Entry<String, LongAdder> e = sorted.get(i);
      Log.info("  %2d. %-30s %,d occurrences", i + 1, e.getKey(), e.getValue().sum());
    }
  }

  private List<Path> collectFiles(Path root) throws IOException {
    if (Files.isRegularFile(root)) {
      return Collections.singletonList(root);
    }
    try (Stream<Path> stream = Files.walk(root)) {
      return stream.filter(Files::isRegularFile)
          .sorted()
          .collect(Collectors.toList());
    }
  }

  private void reportProgress(int completed, int total, long totalBytes, long startTime) {
    long elapsedMs = System.currentTimeMillis() - startTime;
    double elapsedSec = elapsedMs / 1000.0;
    long bytesRead = totalBytesRead.get();
    double mbRead = bytesRead / (1024.0 * 1024.0);
    double mbRate = elapsedSec > 0 ? mbRead / elapsedSec : 0;
    double pct = (completed * 100.0) / total;
    double remSec = mbRate > 0 ? ((totalBytes - bytesRead) / (1024.0 * 1024.0)) / mbRate : 0;

    Log.info("[Progress] %d/%d files (%.1f%%) | %,d unparsed unique | %,d words scanned (%.1f MB/s) | ETA: %.1fs",
        completed, total, pct, unparsedWordCounts.size(), totalWordTokens.get(), mbRate, remSec);
  }

  public static void main(String[] args) {
    new CollectUnparsedWords().execute(args);
  }
}
