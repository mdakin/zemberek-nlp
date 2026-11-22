package zemberek.morphology.ambiguity.model;

import com.beust.jcommander.Parameter;
import com.google.common.base.Stopwatch;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.analysis.AnalysisCache;
import zemberek.morphology.analysis.SingleAnalysis;
import zemberek.morphology.analysis.WordAnalysis;
import zemberek.morphology.lexicon.RootLexicon;
import zemberek.tokenization.Token;
import zemberek.tokenization.TurkishSentenceExtractor;
import zemberek.tokenization.TurkishTokenizer;

/**
 * Step 1 of the ambiguity model pipeline: Multi-threaded corpus refiner & profiler.
 *
 * <p>Reads raw Turkish text files from an input directory (e.g. {@code ~/data/turkish/clean}),
 * segments paragraphs into sentences, analyzes all tokens using {@link TurkishMorphology}
 * <b>without</b> loading or invoking any ambiguity resolver, writes cleaned sentences to
 * {@code outputDir}, and exports a comprehensive Markdown corpus report to {@code reportOut}.
 */
public class RefineCorpus extends ConsoleApp {

  @Parameter(
      names = {"--inputDir", "-i"},
      description = "Directory containing input .txt corpus files.")
  public Path inputDir = Paths.get(System.getProperty("user.home"), "data/turkish/clean");

  @Parameter(
      names = {"--outputDir", "-o"},
      description = "Directory where refined .txt corpus files will be written.")
  public Path outputDir = Paths.get(System.getProperty("user.home"), "data/turkish/refined");

  @Parameter(
      names = {"--reportOut", "-r"},
      description = "Markdown corpus report output file path.")
  public Path reportOut = Paths.get("experiment/docs/model/corpus_refinement_report.md");

  @Parameter(
      names = {"--threads", "-t"},
      description = "Number of worker threads.")
  public int threads = 16;

  @Parameter(
      names = {"--minWords"},
      description = "Minimum number of words in a valid sentence (inclusive).")
  public int minWords = 5;

  @Parameter(
      names = {"--maxWords"},
      description = "Maximum number of words in a valid sentence (inclusive).")
  public int maxWords = 30;

  @Parameter(
      names = {"--maxLinesPerFile"},
      description = "Maximum number of input lines to read per file (-1 for unlimited, useful for test runs).")
  public int maxLinesPerFile = -1;

  @Parameter(
      names = {"--maxFiles"},
      description = "Maximum number of files to process (-1 for all files, useful for test runs).")
  public int maxFiles = -1;

  @Parameter(
      names = {"--batchSize"},
      description = "Number of lines per parallel worker batch.")
  public int batchSize = 1000;

  private static final int HISTOGRAM_MAX_WORDS = 60;
  private static final int MAX_TRACKED_UNPARSED_TYPES = 100_000;

  public static void main(String[] args) {
    new RefineCorpus().execute(args);
  }

  @Override
  public String description() {
    return "Cleans raw Turkish corpus files using 16-thread Zemberek morphological analysis (without disambiguation) and generates a detailed corpus report.";
  }

  public static final class FileStats {
    public final String fileName;
    public long inputFileBytes;
    public long outputFileBytes;
    public long elapsedMs;

    // Line & sentence counts
    public final AtomicLong linesRead = new AtomicLong();
    public final AtomicLong totalSentences = new AtomicLong();
    public final AtomicLong acceptedSentences = new AtomicLong();
    public final AtomicLong rejectedTooShort = new AtomicLong();
    public final AtomicLong rejectedTooLong = new AtomicLong();
    public final AtomicLong rejectedUnparseable = new AtomicLong();

    // Original corpus word/token stats (across ALL extracted sentences)
    public final AtomicLong originalWords = new AtomicLong();
    public final AtomicLong originalPunctuation = new AtomicLong();
    public final AtomicLong originalWordChars = new AtomicLong();
    public final AtomicLong originalSentenceChars = new AtomicLong();
    public final AtomicLong originalParseableWords = new AtomicLong();
    public final AtomicLong originalUnparseableWords = new AtomicLong();

    // Refined (accepted) corpus word/token stats
    public final AtomicLong acceptedWords = new AtomicLong();
    public final AtomicLong acceptedPunctuation = new AtomicLong();
    public final AtomicLong acceptedWordChars = new AtomicLong();
    public final AtomicLong acceptedSentenceChars = new AtomicLong();

    // Morphological ambiguity & structure stats on accepted words
    public final AtomicLong acceptedAmbiguousWords = new AtomicLong();
    public final AtomicLong acceptedHighAmbiguityWords = new AtomicLong(); // >= 5 analyses
    public final AtomicLong acceptedTotalAnalyses = new AtomicLong();
    public final AtomicLong acceptedRuntimeWords = new AtomicLong();
    public final AtomicLong acceptedSingleGroupWords = new AtomicLong(); // 1 IG (no derivation)
    public final AtomicLong acceptedDerivedWords = new AtomicLong(); // >= 2 IGs
    public final AtomicLong acceptedTotalIgs = new AtomicLong();
    public final AtomicLong acceptedFullyUnambiguousSentences = new AtomicLong();

    // Histograms
    public final AtomicLongArray origSentenceWordHist = new AtomicLongArray(HISTOGRAM_MAX_WORDS + 2);
    public final AtomicLongArray acceptedSentenceWordHist = new AtomicLongArray(HISTOGRAM_MAX_WORDS + 2);
    public final AtomicLongArray primaryPosCounts = new AtomicLongArray(PrimaryPos.values().length);

    public FileStats(String fileName, long inputFileBytes) {
      this.fileName = fileName;
      this.inputFileBytes = inputFileBytes;
    }
  }

  private static final class BatchStats {
    long totalSentences;
    long acceptedSentences;
    long rejectedTooShort;
    long rejectedTooLong;
    long rejectedUnparseable;

    long originalWords;
    long originalPunctuation;
    long originalWordChars;
    long originalSentenceChars;
    long originalParseableWords;
    long originalUnparseableWords;

    long acceptedWords;
    long acceptedPunctuation;
    long acceptedWordChars;
    long acceptedSentenceChars;

    long acceptedAmbiguousWords;
    long acceptedHighAmbiguityWords;
    long acceptedTotalAnalyses;
    long acceptedRuntimeWords;
    long acceptedSingleGroupWords;
    long acceptedDerivedWords;
    long acceptedTotalIgs;
    long acceptedFullyUnambiguousSentences;

    final long[] origSentenceWordHist = new long[HISTOGRAM_MAX_WORDS + 2];
    final long[] acceptedSentenceWordHist = new long[HISTOGRAM_MAX_WORDS + 2];
    final long[] primaryPosCounts = new long[PrimaryPos.values().length];

    void mergeInto(FileStats target) {
      target.totalSentences.addAndGet(totalSentences);
      target.acceptedSentences.addAndGet(acceptedSentences);
      target.rejectedTooShort.addAndGet(rejectedTooShort);
      target.rejectedTooLong.addAndGet(rejectedTooLong);
      target.rejectedUnparseable.addAndGet(rejectedUnparseable);

      target.originalWords.addAndGet(originalWords);
      target.originalPunctuation.addAndGet(originalPunctuation);
      target.originalWordChars.addAndGet(originalWordChars);
      target.originalSentenceChars.addAndGet(originalSentenceChars);
      target.originalParseableWords.addAndGet(originalParseableWords);
      target.originalUnparseableWords.addAndGet(originalUnparseableWords);

      target.acceptedWords.addAndGet(acceptedWords);
      target.acceptedPunctuation.addAndGet(acceptedPunctuation);
      target.acceptedWordChars.addAndGet(acceptedWordChars);
      target.acceptedSentenceChars.addAndGet(acceptedSentenceChars);

      target.acceptedAmbiguousWords.addAndGet(acceptedAmbiguousWords);
      target.acceptedHighAmbiguityWords.addAndGet(acceptedHighAmbiguityWords);
      target.acceptedTotalAnalyses.addAndGet(acceptedTotalAnalyses);
      target.acceptedRuntimeWords.addAndGet(acceptedRuntimeWords);
      target.acceptedSingleGroupWords.addAndGet(acceptedSingleGroupWords);
      target.acceptedDerivedWords.addAndGet(acceptedDerivedWords);
      target.acceptedTotalIgs.addAndGet(acceptedTotalIgs);
      target.acceptedFullyUnambiguousSentences.addAndGet(acceptedFullyUnambiguousSentences);

      for (int i = 0; i < origSentenceWordHist.length; i++) {
        if (origSentenceWordHist[i] != 0) {
          target.origSentenceWordHist.addAndGet(i, origSentenceWordHist[i]);
        }
      }
      for (int i = 0; i < acceptedSentenceWordHist.length; i++) {
        if (acceptedSentenceWordHist[i] != 0) {
          target.acceptedSentenceWordHist.addAndGet(i, acceptedSentenceWordHist[i]);
        }
      }
      for (int i = 0; i < primaryPosCounts.length; i++) {
        if (primaryPosCounts[i] != 0) {
          target.primaryPosCounts.addAndGet(i, primaryPosCounts[i]);
        }
      }
    }
  }

  @Override
  public void run() throws Exception {
    if (!Files.exists(inputDir) || !Files.isDirectory(inputDir)) {
      throw new IllegalArgumentException("Input directory does not exist: " + inputDir);
    }
    Files.createDirectories(outputDir);
    if (reportOut != null && reportOut.getParent() != null) {
      Files.createDirectories(reportOut.getParent());
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

    Log.info("=== RefineCorpus Configuration ===");
    Log.info("Input directory   : %s (%d files)", inputDir, inputFiles.size());
    Log.info("Output directory  : %s", outputDir);
    Log.info("Report output     : %s", reportOut);
    Log.info("Worker threads    : %d", threads);
    Log.info("Word count bounds : [%d, %d] words", minWords, maxWords);
    Log.info("Max lines / file  : %s", maxLinesPerFile > 0 ? String.valueOf(maxLinesPerFile) : "UNLIMITED");

    Stopwatch globalSw = Stopwatch.createStarted();

    final RootLexicon sharedLexicon = RootLexicon.getDefault();
    final AnalysisCache sharedCache = AnalysisCache.builder()
        .dynamicCacheSize(100_000, 1_000_000)
        .build();
    final TurkishTokenizer tokenizer = TurkishTokenizer.DEFAULT;
    final TurkishSentenceExtractor sentenceExtractor = TurkishSentenceExtractor.DEFAULT;

    final ThreadLocal<TurkishMorphology> threadMorphology = ThreadLocal.withInitial(() ->
        TurkishMorphology.builder()
            .setLexicon(sharedLexicon)
            .setCache(sharedCache)
            .setTokenizer(tokenizer)
            .setAmbiguityResolver((sentence, analyses) -> null)
            .build());

    ConcurrentHashMap<String, LongAdder> unparsedTokenCounts = new ConcurrentHashMap<>(16_384);
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    Semaphore inFlightGate = new Semaphore(threads * 4);
    List<FileStats> allStats = new ArrayList<>(inputFiles.size());

    try {
      for (int idx = 0; idx < inputFiles.size(); idx++) {
        Path inFile = inputFiles.get(idx);
        String fileName = inFile.getFileName().toString();
        Path outFile = outputDir.resolve(fileName);
        FileStats stats = new FileStats(fileName, Files.size(inFile));
        allStats.add(stats);

        Stopwatch fileSw = Stopwatch.createStarted();
        List<Future<?>> futures = new ArrayList<>();
        final Object writeLock = new Object();

        try (BufferedReader reader = Files.newBufferedReader(inFile, StandardCharsets.UTF_8);
             BufferedWriter writer = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {

          List<String> batch = new ArrayList<>(batchSize);
          String line;
          long linesRead = 0;

          while ((line = reader.readLine()) != null) {
            linesRead++;
            if (!line.isBlank()) {
              batch.add(line);
              if (batch.size() >= batchSize) {
                submitBatch(batch, executor, inFlightGate, futures, threadMorphology,
                    sentenceExtractor, tokenizer, writer, writeLock, stats, unparsedTokenCounts);
                batch = new ArrayList<>(batchSize);
              }
            }
            if (maxLinesPerFile > 0 && linesRead >= maxLinesPerFile) {
              break;
            }
          }
          if (!batch.isEmpty()) {
            submitBatch(batch, executor, inFlightGate, futures, threadMorphology,
                sentenceExtractor, tokenizer, writer, writeLock, stats, unparsedTokenCounts);
          }

          stats.linesRead.set(linesRead);
          for (Future<?> f : futures) {
            f.get();
          }
          writer.flush();
        }

        stats.outputFileBytes = Files.exists(outFile) ? Files.size(outFile) : 0L;
        stats.elapsedMs = fileSw.elapsed(TimeUnit.MILLISECONDS);
        double wordParsePct = stats.originalWords.get() > 0
            ? (100.0 * stats.originalParseableWords.get() / stats.originalWords.get())
            : 0.0;
        Log.info(
            "[%d/%d] %s -> %,d / %,d sents kept (short=%,d, long=%,d, unparsed=%,d) | orig words=%,d (%.1f%% parsed) in %.2f s",
            idx + 1,
            inputFiles.size(),
            fileName,
            stats.acceptedSentences.get(),
            stats.totalSentences.get(),
            stats.rejectedTooShort.get(),
            stats.rejectedTooLong.get(),
            stats.rejectedUnparseable.get(),
            stats.originalWords.get(),
            wordParsePct,
            stats.elapsedMs / 1000.0);
      }
    } finally {
      executor.shutdown();
      executor.awaitTermination(1, TimeUnit.HOURS);
    }

    long totalElapsedMs = globalSw.elapsed(TimeUnit.MILLISECONDS);
    writeMarkdownReport(allStats, unparsedTokenCounts, totalElapsedMs);
    Log.info("All files processed in %.2f s. Report written to %s",
        totalElapsedMs / 1000.0, reportOut.toAbsolutePath());
  }

  private void submitBatch(
      List<String> linesBatch,
      ExecutorService executor,
      Semaphore inFlightGate,
      List<Future<?>> futures,
      ThreadLocal<TurkishMorphology> threadMorphology,
      TurkishSentenceExtractor sentenceExtractor,
      TurkishTokenizer tokenizer,
      BufferedWriter writer,
      Object writeLock,
      FileStats stats,
      ConcurrentHashMap<String, LongAdder> unparsedTokenCounts) throws InterruptedException {

    inFlightGate.acquire();
    Future<?> future = executor.submit(() -> {
      try {
        TurkishMorphology morphology = threadMorphology.get();
        List<String> accepted = new ArrayList<>(linesBatch.size());
        BatchStats bs = new BatchStats();
        int[] sentPosCounts = new int[PrimaryPos.values().length];

        for (int i = 0; i < linesBatch.size(); i++) {
          String rawLine = TextUtil.normalizeSpacesAndSoftHyphens(linesBatch.get(i));
          if (rawLine.isEmpty()) {
            continue;
          }
          List<String> extracted = sentenceExtractor.fromParagraph(rawLine);
          for (int sIdx = 0; sIdx < extracted.size(); sIdx++) {
            String sentence = extracted.get(sIdx).trim();
            if (sentence.isEmpty()) {
              continue;
            }
            bs.totalSentences++;
            bs.originalSentenceChars += sentence.length();

            String normalized = TextUtil.normalizeQuotesHyphens(sentence);
            List<Token> tokens = tokenizer.tokenize(normalized);
            int tokenCount = tokens.size();

            int wordCount = 0;
            int punctCount = 0;
            int wordChars = 0;
            boolean allParseable = true;

            int sentAmbigWords = 0;
            int sentHighAmbigWords = 0;
            int sentTotalAnalyses = 0;
            int sentRuntimeWords = 0;
            int sentSingleGroupWords = 0;
            int sentDerivedWords = 0;
            int sentTotalIgs = 0;
            java.util.Arrays.fill(sentPosCounts, 0);

            for (int t = 0; t < tokenCount; t++) {
              Token tok = tokens.get(t);
              String text = tok.getText();
              boolean isPunct = (tok.getType() == Token.Type.Punctuation);
              if (isPunct) {
                punctCount++;
              } else {
                wordCount++;
                wordChars += text.length();
              }

              WordAnalysis wa = morphology.analyze(tok);
              if (wa.isCorrect()) {
                if (!isPunct) {
                  bs.originalParseableWords++;
                  int aCount = wa.analysisCount();
                  sentTotalAnalyses += aCount;
                  if (aCount > 1) {
                    sentAmbigWords++;
                    if (aCount >= 5) {
                      sentHighAmbigWords++;
                    }
                  }
                  SingleAnalysis first = wa.getAnalysisResults().get(0);
                  sentPosCounts[first.getDictionaryItem().primaryPos.ordinal()]++;
                  if (first.isRuntime()) {
                    sentRuntimeWords++;
                  }
                  int gCount = first.groupCount();
                  sentTotalIgs += gCount;
                  if (gCount <= 1) {
                    sentSingleGroupWords++;
                  } else {
                    sentDerivedWords++;
                  }
                }
              } else {
                allParseable = false;
                if (!isPunct) {
                  bs.originalUnparseableWords++;
                  if (unparsedTokenCounts.size() < MAX_TRACKED_UNPARSED_TYPES) {
                    unparsedTokenCounts.computeIfAbsent(text, k -> new LongAdder()).increment();
                  } else {
                    LongAdder existing = unparsedTokenCounts.get(text);
                    if (existing != null) {
                      existing.increment();
                    }
                  }
                }
              }
            }

            bs.originalWords += wordCount;
            bs.originalPunctuation += punctCount;
            bs.originalWordChars += wordChars;
            int histBin = Math.min(wordCount, HISTOGRAM_MAX_WORDS + 1);
            bs.origSentenceWordHist[histBin]++;

            if (wordCount < minWords) {
              bs.rejectedTooShort++;
              continue;
            }
            if (wordCount > maxWords) {
              bs.rejectedTooLong++;
              continue;
            }
            if (!allParseable) {
              bs.rejectedUnparseable++;
              continue;
            }

            // Sentence passed all filters!
            bs.acceptedSentences++;
            bs.acceptedWords += wordCount;
            bs.acceptedPunctuation += punctCount;
            bs.acceptedWordChars += wordChars;
            bs.acceptedSentenceChars += sentence.length();
            bs.acceptedAmbiguousWords += sentAmbigWords;
            bs.acceptedHighAmbiguityWords += sentHighAmbigWords;
            bs.acceptedTotalAnalyses += sentTotalAnalyses;
            bs.acceptedRuntimeWords += sentRuntimeWords;
            bs.acceptedSingleGroupWords += sentSingleGroupWords;
            bs.acceptedDerivedWords += sentDerivedWords;
            bs.acceptedTotalIgs += sentTotalIgs;
            if (sentAmbigWords == 0) {
              bs.acceptedFullyUnambiguousSentences++;
            }
            bs.acceptedSentenceWordHist[histBin]++;

            for (int p = 0; p < sentPosCounts.length; p++) {
              bs.primaryPosCounts[p] += sentPosCounts[p];
            }

            accepted.add(sentence);
          }
        }

        bs.mergeInto(stats);

        if (!accepted.isEmpty()) {
          StringBuilder sb = new StringBuilder(accepted.size() * 80);
          for (int i = 0; i < accepted.size(); i++) {
            sb.append(accepted.get(i)).append('\n');
          }
          synchronized (writeLock) {
            writer.write(sb.toString());
          }
        }
      } catch (IOException e) {
        throw new RuntimeException("Failed writing refined sentences for " + stats.fileName, e);
      } finally {
        inFlightGate.release();
      }
    });
    futures.add(future);
  }

  private static int percentileFromHistogram(long[] hist, long totalCount, double quantile) {
    if (totalCount <= 0) {
      return 0;
    }
    long threshold = Math.max(1L, Math.round(totalCount * quantile));
    long cumulative = 0;
    for (int i = 0; i < hist.length; i++) {
      cumulative += hist[i];
      if (cumulative >= threshold) {
        return i;
      }
    }
    return hist.length - 1;
  }

  private void writeMarkdownReport(
      List<FileStats> allStats,
      ConcurrentHashMap<String, LongAdder> unparsedTokenCounts,
      long totalElapsedMs) throws IOException {
    if (reportOut == null) {
      return;
    }

    long sumInBytes = 0;
    long sumOutBytes = 0;
    long sumLines = 0;
    long sumTotalSents = 0;
    long sumAcceptedSents = 0;
    long sumShortSents = 0;
    long sumLongSents = 0;
    long sumUnparsedSents = 0;

    long sumOrigWords = 0;
    long sumOrigPunct = 0;
    long sumOrigWordChars = 0;
    long sumOrigSentChars = 0;
    long sumOrigParsedWords = 0;
    long sumOrigUnparsedWords = 0;

    long sumAccWords = 0;
    long sumAccPunct = 0;
    long sumAccWordChars = 0;
    long sumAccSentChars = 0;

    long sumAccAmbigWords = 0;
    long sumAccHighAmbigWords = 0;
    long sumAccAnalyses = 0;
    long sumAccRuntimeWords = 0;
    long sumAccSingleIgWords = 0;
    long sumAccDerivedWords = 0;
    long sumAccTotalIgs = 0;
    long sumAccUnambigSents = 0;

    long[] globalOrigHist = new long[HISTOGRAM_MAX_WORDS + 2];
    long[] globalAccHist = new long[HISTOGRAM_MAX_WORDS + 2];
    long[] globalPosCounts = new long[PrimaryPos.values().length];

    for (FileStats s : allStats) {
      sumInBytes += s.inputFileBytes;
      sumOutBytes += s.outputFileBytes;
      sumLines += s.linesRead.get();
      sumTotalSents += s.totalSentences.get();
      sumAcceptedSents += s.acceptedSentences.get();
      sumShortSents += s.rejectedTooShort.get();
      sumLongSents += s.rejectedTooLong.get();
      sumUnparsedSents += s.rejectedUnparseable.get();

      sumOrigWords += s.originalWords.get();
      sumOrigPunct += s.originalPunctuation.get();
      sumOrigWordChars += s.originalWordChars.get();
      sumOrigSentChars += s.originalSentenceChars.get();
      sumOrigParsedWords += s.originalParseableWords.get();
      sumOrigUnparsedWords += s.originalUnparseableWords.get();

      sumAccWords += s.acceptedWords.get();
      sumAccPunct += s.acceptedPunctuation.get();
      sumAccWordChars += s.acceptedWordChars.get();
      sumAccSentChars += s.acceptedSentenceChars.get();

      sumAccAmbigWords += s.acceptedAmbiguousWords.get();
      sumAccHighAmbigWords += s.acceptedHighAmbiguityWords.get();
      sumAccAnalyses += s.acceptedTotalAnalyses.get();
      sumAccRuntimeWords += s.acceptedRuntimeWords.get();
      sumAccSingleIgWords += s.acceptedSingleGroupWords.get();
      sumAccDerivedWords += s.acceptedDerivedWords.get();
      sumAccTotalIgs += s.acceptedTotalIgs.get();
      sumAccUnambigSents += s.acceptedFullyUnambiguousSentences.get();

      for (int i = 0; i < globalOrigHist.length; i++) {
        globalOrigHist[i] += s.origSentenceWordHist.get(i);
        globalAccHist[i] += s.acceptedSentenceWordHist.get(i);
      }
      for (int i = 0; i < globalPosCounts.length; i++) {
        globalPosCounts[i] += s.primaryPosCounts.get(i);
      }
    }

    double sec = Math.max(0.001, totalElapsedMs / 1000.0);
    double sentKeepPct = sumTotalSents > 0 ? (100.0 * sumAcceptedSents / sumTotalSents) : 0.0;
    double wordParsePct = sumOrigWords > 0 ? (100.0 * sumOrigParsedWords / sumOrigWords) : 0.0;
    double wordUnparsePct = sumOrigWords > 0 ? (100.0 * sumOrigUnparsedWords / sumOrigWords) : 0.0;

    double origAvgWordsPerSent = sumTotalSents > 0 ? ((double) sumOrigWords / sumTotalSents) : 0.0;
    double origAvgCharsPerSent = sumTotalSents > 0 ? ((double) sumOrigSentChars / sumTotalSents) : 0.0;
    double origAvgWordLen = sumOrigWords > 0 ? ((double) sumOrigWordChars / sumOrigWords) : 0.0;

    double accAvgWordsPerSent = sumAcceptedSents > 0 ? ((double) sumAccWords / sumAcceptedSents) : 0.0;
    double accAvgCharsPerSent = sumAcceptedSents > 0 ? ((double) sumAccSentChars / sumAcceptedSents) : 0.0;
    double accAvgWordLen = sumAccWords > 0 ? ((double) sumAccWordChars / sumAccWords) : 0.0;

    double accAmbigWordPct = sumAccWords > 0 ? (100.0 * sumAccAmbigWords / sumAccWords) : 0.0;
    double accHighAmbigPct = sumAccWords > 0 ? (100.0 * sumAccHighAmbigWords / sumAccWords) : 0.0;
    double accAvgAnalysesPerWord = sumAccWords > 0 ? ((double) sumAccAnalyses / sumAccWords) : 0.0;
    long sumAccUnambigWords = sumAccWords - sumAccAmbigWords;
    double accAvgAnalysesPerAmbigWord = sumAccAmbigWords > 0
        ? ((double) (sumAccAnalyses - sumAccUnambigWords) / sumAccAmbigWords)
        : 0.0;
    double accRuntimeWordPct = sumAccWords > 0 ? (100.0 * sumAccRuntimeWords / sumAccWords) : 0.0;
    double accDerivedWordPct = sumAccWords > 0 ? (100.0 * sumAccDerivedWords / sumAccWords) : 0.0;
    double accAvgIgsPerWord = sumAccWords > 0 ? ((double) sumAccTotalIgs / sumAccWords) : 0.0;
    double accAmbigSentPct = sumAcceptedSents > 0
        ? (100.0 * (sumAcceptedSents - sumAccUnambigSents) / sumAcceptedSents)
        : 0.0;

    int origP50 = percentileFromHistogram(globalOrigHist, sumTotalSents, 0.50);
    int origP90 = percentileFromHistogram(globalOrigHist, sumTotalSents, 0.90);
    int origP99 = percentileFromHistogram(globalOrigHist, sumTotalSents, 0.99);

    int accP50 = percentileFromHistogram(globalAccHist, sumAcceptedSents, 0.50);
    int accP90 = percentileFromHistogram(globalAccHist, sumAcceptedSents, 0.90);
    int accP99 = percentileFromHistogram(globalAccHist, sumAcceptedSents, 0.99);

    StringBuilder md = new StringBuilder(16_384);
    md.append("# Turkish Corpus Refinement & Morphological Analysis Report\n\n");
    md.append("## 1. Run Configuration & Throughput\n\n");
    md.append("| Parameter | Value |\n");
    md.append("| :--- | :--- |\n");
    md.append("| **Timestamp** | `").append(Instant.now().toString()).append("` |\n");
    md.append("| **Input Directory** | `").append(inputDir.toAbsolutePath()).append("` (").append(allStats.size()).append(" files) |\n");
    md.append("| **Output Directory** | `").append(outputDir.toAbsolutePath()).append("` |\n");
    md.append("| **Worker Threads** | `").append(threads).append("` |\n");
    md.append("| **Sentence Length Filter** | `[").append(minWords).append(", ").append(maxWords).append("]` words (excluding punctuation) |\n");
    md.append("| **Disambiguator Used** | `None` (pure `TurkishMorphology` token analysis) |\n");
    md.append("| **Max Lines Per File** | `").append(maxLinesPerFile > 0 ? String.format(Locale.ROOT, "%,d", maxLinesPerFile) : "UNLIMITED (Full Corpus)").append("` |\n");
    md.append(String.format(Locale.ROOT, "| **Total Wall Time** | `%.2f s` (`%.2f min`) |\n", sec, sec / 60.0));
    md.append(String.format(Locale.ROOT, "| **Processing Throughput** | `%,.0f sentences/sec` / `%,.0f words/sec` |\n",
        sumTotalSents / sec, sumOrigWords / sec));
    md.append(String.format(Locale.ROOT, "| **Disk Size (Source Files -> Refined Output)** | `%.2f MB` -> `%.2f MB` |\n\n",
        sumInBytes / (1024.0 * 1024.0), sumOutBytes / (1024.0 * 1024.0)));

    md.append("## 2. Executive Corpus Summary (Original vs. Refined)\n\n");
    md.append("### 2.1 Sentence Filtering Breakdown\n\n");
    md.append("| Metric | Sentence Count | Share of Extracted |\n");
    md.append("| :--- | ---: | ---: |\n");
    md.append(String.format(Locale.ROOT, "| **Raw Input Lines Read** | %,d | - |\n", sumLines));
    md.append(String.format(Locale.ROOT, "| **Total Extracted Sentences (Original)** | **%,d** | **100.00%%** |\n", sumTotalSents));
    md.append(String.format(Locale.ROOT, "| **Accepted Clean Sentences (Refined)** | **%,d** | **%.2f%%** |\n", sumAcceptedSents, sentKeepPct));
    md.append(String.format(Locale.ROOT, "| Rejected: Too Short (`< %d` words) | %,d | %.2f%% |\n",
        minWords, sumShortSents, sumTotalSents > 0 ? 100.0 * sumShortSents / sumTotalSents : 0.0));
    md.append(String.format(Locale.ROOT, "| Rejected: Too Long (`> %d` words) | %,d | %.2f%% |\n",
        maxWords, sumLongSents, sumTotalSents > 0 ? 100.0 * sumLongSents / sumTotalSents : 0.0));
    md.append(String.format(Locale.ROOT, "| Rejected: Contains `>= 1` Unparseable Word (in `[%d, %d]` range) | %,d | %.2f%% |\n\n",
        minWords, maxWords, sumUnparsedSents, sumTotalSents > 0 ? 100.0 * sumUnparsedSents / sumTotalSents : 0.0));

    md.append("### 2.2 Word & Parseability Statistics\n\n");
    md.append("| Metric | Original Corpus (All Extracted Sentences) | Refined Corpus (Accepted Clean Sentences) |\n");
    md.append("| :--- | ---: | ---: |\n");
    md.append(String.format(Locale.ROOT, "| **Total Words (Non-Punctuation Tokens)** | **%,d** | **%,d** (%.2f%% of original) |\n",
        sumOrigWords, sumAccWords, sumOrigWords > 0 ? 100.0 * sumAccWords / sumOrigWords : 0.0));
    md.append(String.format(Locale.ROOT, "| **Total Punctuation Tokens** | %,d | %,d |\n", sumOrigPunct, sumAccPunct));
    md.append(String.format(Locale.ROOT, "| **Total Tokens (Words + Punctuation)** | %,d | %,d |\n",
        sumOrigWords + sumOrigPunct, sumAccWords + sumAccPunct));
    md.append(String.format(Locale.ROOT, "| **Words Parsed by Zemberek** | **%,d (%.2f%%)** | **%,d (100.00%%)** |\n",
        sumOrigParsedWords, wordParsePct, sumAccWords));
    md.append(String.format(Locale.ROOT, "| **Words Unparseable (`UNKNOWN` / No Parse)** | %,d (%.2f%%) | 0 (0.00%%) |\n",
        sumOrigUnparsedWords, wordUnparsePct));
    md.append(String.format(Locale.ROOT, "| **Average Sentence Size (Words / Sentence)** | **%.2f words** (p50=`%d`, p90=`%d`, p99=`%d`) | **%.2f words** (p50=`%d`, p90=`%d`, p99=`%d`) |\n",
        origAvgWordsPerSent, origP50, origP90, origP99, accAvgWordsPerSent, accP50, accP90, accP99));
    md.append(String.format(Locale.ROOT, "| **Average Sentence Length (Chars / Sentence)** | %.1f chars | %.1f chars |\n",
        origAvgCharsPerSent, accAvgCharsPerSent));
    md.append(String.format(Locale.ROOT, "| **Average Word Size (Chars / Word)** | **%.2f chars** | **%.2f chars** |\n\n",
        origAvgWordLen, accAvgWordLen));

    md.append("## 3. Morphological & Ambiguity Insights (Refined Corpus)\n\n");
    md.append("| Morphological Property | Value | Notes |\n");
    md.append("| :--- | ---: | :--- |\n");
    md.append(String.format(Locale.ROOT, "| **Morphologically Ambiguous Words (`>= 2` analyses)** | **%,d (%.2f%%)** | Words requiring contextual disambiguation |\n",
        sumAccAmbigWords, accAmbigWordPct));
    md.append(String.format(Locale.ROOT, "| **Unambiguous Words (`1` analysis)** | %,d (%.2f%%) | Deterministic single-parse words |\n",
        sumAccUnambigWords, 100.0 - accAmbigWordPct));
    md.append(String.format(Locale.ROOT, "| **Highly Ambiguous Words (`>= 5` analyses)** | %,d (%.2f%%) | Heavy homonym / derivational ambiguity |\n",
        sumAccHighAmbigWords, accHighAmbigPct));
    md.append(String.format(Locale.ROOT, "| **Average Analyses per Word (All Words)** | **%.2f** | Mean morphological branching factor |\n",
        accAvgAnalysesPerWord));
    md.append(String.format(Locale.ROOT, "| **Average Analyses per Ambiguous Word** | **%.2f** | Mean candidates when ambiguous (`>= 2`) |\n",
        accAvgAnalysesPerAmbigWord));
    md.append(String.format(Locale.ROOT, "| **Sentences with `>= 1` Ambiguous Word** | **%,d (%.2f%%)** | Sentences useful for disambiguator training |\n",
        sumAcceptedSents - sumAccUnambigSents, accAmbigSentPct));
    md.append(String.format(Locale.ROOT, "| **Fully Unambiguous Sentences (`0` ambiguous words)** | %,d (%.2f%%) | Every word has only 1 valid parse |\n",
        sumAccUnambigSents, 100.0 - accAmbigSentPct));
    md.append(String.format(Locale.ROOT, "| **Derived Words (`>= 2` Inflectional Groups)** | %,d (%.2f%%) | Words undergoing POS/derivational transitions |\n",
        sumAccDerivedWords, accDerivedWordPct));
    md.append(String.format(Locale.ROOT, "| **Average Inflectional Groups (IGs) per Word** | **%.2f IGs** | `1.0` = root/inflection only, `>1.0` = derivation |\n",
        accAvgIgsPerWord));
    md.append(String.format(Locale.ROOT, "| **Dynamic/Runtime Words (Numbers, Dates, OOV Proper)** | %,d (%.2f%%) | Parsed via `UnidentifiedTokenAnalyzer` |\n\n",
        sumAccRuntimeWords, accRuntimeWordPct));

    md.append("## 4. Sentence Length Distribution (Refined Corpus `[")
        .append(minWords).append("..").append(maxWords).append("]` Words)\n\n");
    md.append("| Word Count | Accepted Sentences | Share (%) | Bar |\n");
    md.append("| ---: | ---: | ---: | :--- |\n");
    long maxBinCount = 1;
    for (int w = minWords; w <= maxWords && w < globalAccHist.length; w++) {
      if (globalAccHist[w] > maxBinCount) {
        maxBinCount = globalAccHist[w];
      }
    }
    for (int w = minWords; w <= maxWords && w < globalAccHist.length; w++) {
      long c = globalAccHist[w];
      double pct = sumAcceptedSents > 0 ? (100.0 * c / sumAcceptedSents) : 0.0;
      int bars = (int) Math.round(25.0 * c / maxBinCount);
      String barStr = "#".repeat(Math.max(0, bars));
      md.append(String.format(Locale.ROOT, "| `%2d` | %,d | %.2f%% | `%s` |\n", w, c, pct, barStr));
    }
    md.append("\n");

    md.append("## 5. Primary Part-of-Speech (POS) Distribution (First Analysis on Refined Words)\n\n");
    md.append("| Primary POS | Word Count | Share (%) |\n");
    md.append("| :--- | ---: | ---: |\n");
    PrimaryPos[] posValues = PrimaryPos.values();
    List<Integer> posOrder = new ArrayList<>();
    for (int i = 0; i < posValues.length; i++) {
      if (globalPosCounts[i] > 0) {
        posOrder.add(i);
      }
    }
    posOrder.sort((a, b) -> Long.compare(globalPosCounts[b], globalPosCounts[a]));
    for (int idx : posOrder) {
      long count = globalPosCounts[idx];
      double pct = sumAccWords > 0 ? (100.0 * count / sumAccWords) : 0.0;
      md.append(String.format(Locale.ROOT, "| `%s` | %,d | %.2f%% |\n", posValues[idx].shortForm, count, pct));
    }
    md.append("\n");

    md.append("## 6. Top 30 Most Frequent Unparseable Tokens (Why Sentences Were Rejected)\n\n");
    md.append("| Rank | Token | Occurrences |\n");
    md.append("| ---: | :--- | ---: |\n");
    List<Map.Entry<String, Long>> topUnparsed = unparsedTokenCounts.entrySet().stream()
        .map(e -> Map.entry(e.getKey(), e.getValue().sum()))
        .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
        .limit(30)
        .collect(Collectors.toList());
    for (int i = 0; i < topUnparsed.size(); i++) {
      Map.Entry<String, Long> e = topUnparsed.get(i);
      md.append(String.format(Locale.ROOT, "| %d | `%s` | %,d |\n",
          i + 1, e.getKey().replace("|", "\\|"), e.getValue()));
    }
    md.append("\n");

    md.append("## 7. Per-File Breakdown\n\n");
    md.append("| File | Lines | Orig Sents | Kept Sents | Keep % | `<")
        .append(minWords).append("` | `>").append(maxWords)
        .append("` | Unparsed Sents | Orig Words | Word Parse % | Kept Words | Avg Sent Words | Avg Word Chars | Ambig % | Time (s) |\n");
    md.append("| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
    for (FileStats s : allStats) {
      long totS = s.totalSentences.get();
      long accS = s.acceptedSentences.get();
      long origW = s.originalWords.get();
      long accW = s.acceptedWords.get();
      double sKeep = totS > 0 ? (100.0 * accS / totS) : 0.0;
      double wParse = origW > 0 ? (100.0 * s.originalParseableWords.get() / origW) : 0.0;
      double avgSentW = accS > 0 ? ((double) accW / accS) : 0.0;
      double avgWordC = accW > 0 ? ((double) s.acceptedWordChars.get() / accW) : 0.0;
      double ambigPct = accW > 0 ? (100.0 * s.acceptedAmbiguousWords.get() / accW) : 0.0;
      md.append(String.format(
          Locale.ROOT,
          "| `%s` | %,d | %,d | **%,d** | %.1f%% | %,d | %,d | %,d | %,d | %.1f%% | **%,d** | %.1f | %.2f | %.1f%% | %.2f |\n",
          s.fileName,
          s.linesRead.get(),
          totS,
          accS,
          sKeep,
          s.rejectedTooShort.get(),
          s.rejectedTooLong.get(),
          s.rejectedUnparseable.get(),
          origW,
          wParse,
          accW,
          avgSentW,
          avgWordC,
          ambigPct,
          s.elapsedMs / 1000.0));
    }

    Files.writeString(reportOut, md.toString(), StandardCharsets.UTF_8);
  }
}
