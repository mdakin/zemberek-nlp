package zemberek.morphology.ambiguity.dataset;

import com.beust.jcommander.Parameter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import zemberek.apps.ConsoleApp;
import zemberek.core.logging.Log;
import zemberek.core.text.TextUtil;
import zemberek.core.turkish.PrimaryPos;
import zemberek.core.turkish.SecondaryPos;
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.analysis.AnalysisFormatters;
import zemberek.morphology.analysis.SingleAnalysis;
import zemberek.morphology.analysis.WordAnalysis;
import zemberek.morphology.lexicon.DictionaryItem;
import zemberek.morphology.lexicon.RootLexicon;
import zemberek.tokenization.Token;
import zemberek.tokenization.TurkishSentenceExtractor;
import zemberek.tokenization.TurkishTokenizer;

/**
 * Stage 4: High-throughput stratified corpus harvesting engine.
 *
 * Implements a high-speed Producer-Consumer parallel architecture:
 * 1. Five concurrent domain reader threads stream pre-cleaned text lines across 5 domains in parallel.
 * 2. Employs Zemberek-native text normalization and cleaning facilities:
 *    - TextUtil.cleanAllHtmlRelated: strips HTML tags, comments, and resolves HTML entities to UTF-8.
 *    - TextUtil.normalizeSpacesAndSoftHyphens: removes soft hyphens (\u00ad) and unifies Unicode spaces.
 *    - TextUtil.normalizeQuotesHyphens: standardizes quotes, apostrophes, and dashes.
 *    - TextUtil.containsCombiningDiacritics: filters corrupted Unicode combining marks.
 *    - TurkishSentenceExtractor.DEFAULT: segments multi-sentence lines into clean linguistic sentences.
 *    - TextUtil.digitRatio & TextUtil.uppercaseRatio: filters numeric tables and all-caps shouting.
 * 3. Complete Solvability Gate: strictly enforces that 100% of tokens in every harvested sentence
 *    must have at least one valid morphological candidate in Zemberek (rejects typos, missing-space
 *    glued words, deasciified text, and unparseable slang).
 * 4. Ambiguity Quality Gate strictly enforces ambiguity ratio >= 0.35 and count >= 3.
 * 5. Worker pool (12-16 threads) executes compiled regex matching with UNICODE_CHARACTER_CLASS,
 *    priority-based least-filled balancing, and in-memory TurkishMorphology FST analysis.
 * 6. Enforces 50/50 Positive vs Counter arm balance across all 36 patterns,
 *    hard per-file cap (1,500), and precise trigger-span lemma diversity cap (15).
 * 7. Generates both stage4_harvested_sentences_20k.txt and stage4_harvested_candidates_20k.jsonl
 *    in a single pass with atomic quota management and self-documenting provenance metadata.
 */
public class HarvestContrastiveDataset extends ConsoleApp {

  @Parameter(
      names = {"--rules", "-r"},
      description = "Path to stage3_induced_rules_and_filters.json")
  public Path rulesPath = Paths.get("data/ambiguity/stage3_induced_rules_and_filters.json");

  @Parameter(
      names = {"--corpus", "-c"},
      description = "Directory containing clean corpus text files (default: ~/data/turkish/clean)")
  public Path corpusDir = Paths.get(System.getProperty("user.home"), "data/turkish/clean");

  @Parameter(
      names = {"--outputDir", "-o"},
      description = "Output directory for harvested datasets (default: data/ambiguity)")
  public Path outputDir = Paths.get("data/ambiguity");

  @Parameter(
      names = {"--targetTotal", "-total"},
      description = "Total target sentences to harvest (default: 20000)")
  public int targetTotal = 20000;

  @Parameter(
      names = {"--minWords"},
      description = "Minimum word length (default: 8)")
  public int minWords = 8;

  @Parameter(
      names = {"--maxWords"},
      description = "Maximum word length (default: 30)")
  public int maxWords = 30;

  @Parameter(
      names = {"--minAmbiguityRatio"},
      description = "Minimum ratio of ambiguous tokens in sentence (default: 0.35)")
  public double minAmbiguityRatio = 0.35;

  @Parameter(
      names = {"--minAmbiguousTokens"},
      description = "Minimum count of ambiguous tokens in sentence (default: 3)")
  public int minAmbiguousTokens = 3;

  @Parameter(
      names = {"--maxPerFile"},
      description = "Hard per-file ceiling (default: 1500)")
  public int maxPerFile = 1500;

  @Parameter(
      names = {"--maxPerLemma"},
      description = "Hard per-lemma cap per pattern (default: 15)")
  public int maxPerLemma = 15;

  @Parameter(
      names = {"--threads", "-t"},
      description = "Worker threads for matching and morphological analysis (default: 12)")
  public int threads = 12;

  public static void main(String[] args) {
    new HarvestContrastiveDataset().execute(args);
  }

  @Override
  public String description() {
    return "Stage 4: High-throughput stratified corpus harvesting engine with in-memory morphological candidate extraction.";
  }

  @Override
  public void run() throws Exception {
    Log.info("Starting Stage 4 High-Throughput Corpus Harvester...");
    Log.info("Rules file: %s", rulesPath);
    Log.info("Corpus directory: %s", corpusDir);
    Log.info("Output directory: %s", outputDir);
    Log.info("Target total: %d sentences (Min words: %d, Max words: %d)", targetTotal, minWords, maxWords);
    Log.info("Solvability Gate: 100%% of tokens must be parseable by Zemberek");
    Log.info("Ambiguity Gate: Min ratio: %.2f, Min count: %d", minAmbiguityRatio, minAmbiguousTokens);
    Log.info("Caps: Max per file: %d, Max per lemma: %d", maxPerFile, maxPerLemma);
    Log.info("Threads: %d workers + 5 domain stream feeders", threads);

    if (!Files.exists(rulesPath)) {
      throw new IllegalArgumentException("Rules file not found: " + rulesPath);
    }
    if (!Files.exists(corpusDir) || !Files.isDirectory(corpusDir)) {
      throw new IllegalArgumentException("Corpus directory not found: " + corpusDir);
    }
    if (!Files.exists(outputDir)) {
      Files.createDirectories(outputDir);
    }

    // 1. Ingest pattern rules & compile regexes
    List<PatternBundle> patternBundles = loadPatternRules(rulesPath, targetTotal);
    Log.info("Loaded and compiled %d pattern bundles (72 total regex arms).", patternBundles.size());

    // 2. Define domain file map & Execution state
    HarvestState state = new HarvestState(patternBundles, targetTotal, maxPerFile, maxPerLemma);
    Map<DomainBucket, List<Path>> domainFiles = mapDomainFiles(corpusDir);
    for (Map.Entry<DomainBucket, List<Path>> entry : domainFiles.entrySet()) {
      int quota = state.getDomainQuota(entry.getKey());
      Log.info("Domain %s: %d files (Target: %d sentences, %.1f%%)",
          entry.getKey().name(), entry.getValue().size(), quota, entry.getKey().targetPct * 100.0);
    }

    // 3. Initialize Shared TurkishMorphology
    Log.info("Initializing TurkishMorphology FST engine...");
    TurkishMorphology morphology = TurkishMorphology.builder()
        .setLexicon(RootLexicon.getDefault())
        .setTokenizer(TurkishTokenizer.DEFAULT)
        .build();

    String countLabel = (targetTotal % 1000 == 0) ? (targetTotal / 1000) + "k" : String.valueOf(targetTotal);
    Path txtOutput = outputDir.resolve("stage4_harvested_sentences_" + countLabel + ".txt");
    Path jsonlOutput = outputDir.resolve("stage4_harvested_candidates_" + countLabel + ".jsonl");
    Path summaryOutput = outputDir.resolve("stage4_harvested_summary_" + countLabel + ".json");

    Gson gson = new Gson();
    long startTime = System.currentTimeMillis();

    // 4. Run Concurrent Producer-Consumer Pipeline
    try (BufferedWriter txtWriter = Files.newBufferedWriter(txtOutput, StandardCharsets.UTF_8);
         BufferedWriter jsonlWriter = Files.newBufferedWriter(jsonlOutput, StandardCharsets.UTF_8)) {

      executeParallelHarvest(
          domainFiles,
          patternBundles,
          morphology,
          state,
          txtWriter,
          jsonlWriter,
          gson);
    }

    long elapsedSec = (System.currentTimeMillis() - startTime) / 1000;
    Log.info("================================================================================");
    Log.info("Harvest completed in %d seconds!", elapsedSec);
    Log.info("Total sentences harvested: %d / %d", state.totalHarvested.get(), targetTotal);
    Log.info("Sentences written to: %s", txtOutput);
    Log.info("Candidate JSONL written to: %s", jsonlOutput);

    // 5. Write audit summary
    HarvestSummary summary = state.buildSummary(elapsedSec);
    Gson prettyGson = new GsonBuilder().setPrettyPrinting().create();
    try (BufferedWriter summaryWriter = Files.newBufferedWriter(summaryOutput, StandardCharsets.UTF_8)) {
      prettyGson.toJson(summary, summaryWriter);
    }
    Log.info("Harvest summary written to: %s", summaryOutput);
    Log.info("================================================================================");
  }

  private void executeParallelHarvest(
      Map<DomainBucket, List<Path>> domainFiles,
      List<PatternBundle> patternBundles,
      TurkishMorphology morphology,
      HarvestState state,
      BufferedWriter txtWriter,
      BufferedWriter jsonlWriter,
      Gson gson) throws InterruptedException {

    BlockingQueue<RawSentenceTask> queue = new LinkedBlockingQueue<>(20000);
    AtomicBoolean readersFinished = new AtomicBoolean(false);

    ExecutorService readerPool = Executors.newFixedThreadPool(DomainBucket.values().length);
    ExecutorService workerPool = Executors.newFixedThreadPool(threads);

    CountDownLatch readersLatch = new CountDownLatch(DomainBucket.values().length);
    CountDownLatch workersLatch = new CountDownLatch(threads);

    try {
      // Launch 5 Domain Reader threads in parallel (pure I/O producers)
      for (DomainBucket bucket : DomainBucket.values()) {
        List<Path> files = domainFiles.get(bucket);
        readerPool.submit(() -> {
          try {
            streamDomainFiles(bucket, files, queue, state);
          } catch (Exception e) {
            Log.error("Error in domain reader %s: %s", bucket.name(), e.getMessage());
          } finally {
            readersLatch.countDown();
          }
        });
      }

      // Monitor thread to signal when all readers finish
      Thread monitorThread = new Thread(() -> {
        try {
          readersLatch.await();
          readersFinished.set(true);
        } catch (InterruptedException ignored) {}
      });
      monitorThread.setDaemon(true);
      monitorThread.start();

      // Launch Consumer Worker threads (parallel NLP, cleaning & morphology)
      for (int i = 0; i < threads; i++) {
        workerPool.submit(() -> {
          TurkishTokenizer tokenizer = TurkishTokenizer.DEFAULT;
          TurkishSentenceExtractor sentenceExtractor = TurkishSentenceExtractor.DEFAULT;
          try {
            while (!state.isFullyCompleted()) {
              RawSentenceTask task = queue.poll(100, TimeUnit.MILLISECONDS);
              if (task == null) {
                if (readersFinished.get() && queue.isEmpty()) {
                  break;
                }
                continue;
              }

              processSentenceTask(task, patternBundles, morphology, tokenizer, sentenceExtractor, state, txtWriter, jsonlWriter, gson);
            }
          } catch (Exception e) {
            Log.error("Error in worker thread: %s", e.getMessage());
          } finally {
            workersLatch.countDown();
          }
        });
      }

      // Await worker completion
      workersLatch.await();

    } finally {
      readerPool.shutdownNow();
      workerPool.shutdownNow();
    }
  }

  private void streamDomainFiles(
      DomainBucket bucket,
      List<Path> files,
      BlockingQueue<RawSentenceTask> queue,
      HarvestState state) {

    if (files == null || files.isEmpty()) {
      return;
    }

    // Shuffle domain files for round-robin diversity
    List<Path> shuffled = new ArrayList<>(files);
    Collections.shuffle(shuffled, new Random(bucket.ordinal() * 31L + 42L));

    for (Path filePath : shuffled) {
      if (Thread.currentThread().isInterrupted() || state.isFullyCompleted()) {
        break;
      }
      if (state.domainCounters.get(bucket).get() >= state.getDomainQuotaMax(bucket)) {
        break;
      }

      String fileName = filePath.getFileName().toString();
      int fileHarvestedCount = state.fileCounters.computeIfAbsent(fileName, k -> new AtomicInteger(0)).get();
      if (fileHarvestedCount >= state.effectiveMaxPerFile) {
        continue;
      }

      try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (Thread.currentThread().isInterrupted() || state.isFullyCompleted()) {
            break;
          }
          if (state.domainCounters.get(bucket).get() >= state.getDomainQuotaMax(bucket)) {
            break;
          }
          if (state.fileCounters.get(fileName).get() >= state.effectiveMaxPerFile) {
            break;
          }

          line = line.trim();
          if (line.length() < 20 || line.startsWith("<")) {
            continue;
          }

          // Enqueue task for parallel worker threads
          try {
            while (!Thread.currentThread().isInterrupted() && !state.isFullyCompleted()
                && state.domainCounters.get(bucket).get() < state.getDomainQuotaMax(bucket)) {
              if (queue.offer(new RawSentenceTask(bucket, fileName, line), 100, TimeUnit.MILLISECONDS)) {
                break;
              }
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      } catch (Exception e) {
        if (Thread.currentThread().isInterrupted()) {
          break;
        }
        Log.warn("Error reading %s: %s", fileName, e.getMessage());
      }
    }
  }

  private void processSentenceTask(
      RawSentenceTask task,
      List<PatternBundle> patternBundles,
      TurkishMorphology morphology,
      TurkishTokenizer tokenizer,
      TurkishSentenceExtractor sentenceExtractor,
      HarvestState state,
      BufferedWriter txtWriter,
      BufferedWriter jsonlWriter,
      Gson gson) throws IOException {

    if (state.isFullyCompleted()) {
      return;
    }
    if (state.domainCounters.get(task.bucket).get() >= state.getDomainQuotaMax(task.bucket)) {
      return;
    }
    if (state.fileCounters.get(task.fileName).get() >= state.effectiveMaxPerFile) {
      return;
    }

    String raw = task.sentence;

    // Phase 1: Zemberek HTML & Entity Cleaning
    if (raw.contains("<") || raw.contains("&")) {
      raw = TextUtil.cleanAllHtmlRelated(raw);
    }

    // Phase 2: Zemberek Whitespace & Soft-Hyphen Normalization
    raw = TextUtil.normalizeSpacesAndSoftHyphens(raw);

    // Phase 3: Zemberek Typographical Quotes & Hyphens Standardization
    raw = TextUtil.normalizeQuotesHyphens(raw);

    // Phase 4: Filter combining diacritic corruptions
    if (TextUtil.containsCombiningDiacritics(raw)) {
      return;
    }

    // Phase 5: Conditional sentence extraction for multi-sentence lines/paragraphs
    List<String> extracted;
    if (raw.length() > 250 || countDelimiters(raw) > 1) {
      extracted = sentenceExtractor.fromParagraph(raw);
    } else {
      extracted = Collections.singletonList(raw);
    }

    for (String sentenceStr : extracted) {
      if (state.isFullyCompleted()) {
        break;
      }
      if (state.domainCounters.get(task.bucket).get() >= state.getDomainQuotaMax(task.bucket)) {
        break;
      }
      if (state.fileCounters.get(task.fileName).get() >= state.effectiveMaxPerFile) {
        break;
      }

      sentenceStr = sentenceStr.trim();
      if (sentenceStr.length() < 20 || sentenceStr.length() > 400) {
        continue;
      }

      // Word length constraint (strictly 8-30 words)
      String[] words = sentenceStr.split("\\s+");
      if (words.length < minWords || words.length > maxWords) {
        continue;
      }

      // Zemberek noise filters: digit ratio and uppercase shouting ratio
      if (TextUtil.digitRatio(sentenceStr) > 0.15) {
        continue;
      }
      if (TextUtil.uppercaseRatio(sentenceStr) > 0.30) {
        continue;
      }

      // Capitalization check (strip leading quotes first)
      String firstWord = words[0].replaceAll("^[\"'«“‘]+", "");
      if (firstWord.isEmpty()) {
        continue;
      }
      char firstChar = firstWord.charAt(0);
      if (!Character.isUpperCase(firstChar) && !Character.isDigit(firstChar)) {
        continue;
      }

      // Valid terminal punctuation check
      char lastChar = sentenceStr.charAt(sentenceStr.length() - 1);
      if (lastChar != '.' && lastChar != '!' && lastChar != '?' && lastChar != '"'
          && lastChar != '»' && lastChar != '”' && lastChar != '’' && lastChar != '…') {
        continue;
      }

      // Technical symbol noise check
      int noiseCount = 0;
      for (String w : words) {
        if (w.chars().anyMatch(HarvestContrastiveDataset::isTechnicalSymbol)) {
          noiseCount++;
        }
      }
      if ((double) noiseCount / words.length > 0.10) {
        continue;
      }

      // Deduplication check
      String normalizedKey = sentenceStr.toLowerCase(Locale.forLanguageTag("tr"));
      if (state.seenSentences.contains(normalizedKey)) {
        continue;
      }

      // Evaluate pattern matches (prioritizing least-filled patterns)
      MatchResult match = state.findMatchingPattern(sentenceStr, patternBundles);
      if (match == null) {
        continue;
      }

      // In-memory morphological candidate extraction
      DisambiguationCandidateExtractor.SentenceRecord record = evaluateAndExtractCandidates(
          sentenceStr,
          tokenizer,
          morphology);

      // Complete Solvability Gate: 100% of tokens must have >= 1 candidate in Zemberek
      boolean allTokensSolvable = record.tokens.stream().allMatch(t -> !t.candidates.isEmpty());
      if (!allTokensSolvable) {
        continue;
      }

      int totalTokens = record.tokens.size();
      long ambigTokens = record.tokens.stream().filter(t -> t.is_ambiguous).count();
      double ambigRatio = totalTokens > 0 ? (double) ambigTokens / totalTokens : 0.0;

      // Ambiguity Quality Gate
      if (ambigTokens < minAmbiguousTokens || ambigRatio < minAmbiguityRatio) {
        continue;
      }

      // Extract exact trigger lemma from regex match span
      String triggerLemma = extractTriggerLemmaFromMatch(record, match.matchStart, match.matchEnd, match.bundle);

      // Atomically claim quota and check lemma cap
      int assignedSentenceIndex = state.claimQuota(match.bundle, match.isPositiveArm, task.bucket, task.fileName, triggerLemma);
      if (assignedSentenceIndex < 0) {
        continue;
      }

      state.seenSentences.add(normalizedKey);

      // Enrich record with self-documenting provenance metadata
      HarvestCandidateRecord enrichedRecord = new HarvestCandidateRecord();
      enrichedRecord.sentence_id = "s_" + String.format("%06d", assignedSentenceIndex);
      enrichedRecord.domain = task.bucket.name();
      enrichedRecord.pattern_id = match.bundle.patternId;
      enrichedRecord.pattern_arm = match.isPositiveArm ? "positive" : "counter";
      enrichedRecord.source_file = task.fileName;
      enrichedRecord.text = record.text;
      enrichedRecord.total_tokens = totalTokens;
      enrichedRecord.ambiguous_tokens = (int) ambigTokens;
      enrichedRecord.ambiguity_rate = Math.round(ambigRatio * 10000.0) / 10000.0;
      enrichedRecord.tokens = record.tokens;

      // Write to both outputs in single pass
      synchronized (txtWriter) {
        txtWriter.write(sentenceStr);
        txtWriter.newLine();
        jsonlWriter.write(gson.toJson(enrichedRecord));
        jsonlWriter.newLine();
      }

      if (assignedSentenceIndex % 500 == 0 || assignedSentenceIndex == targetTotal) {
        Log.info("Progress: %d / %d sentences harvested (%.1f%%) | Domain %s: %d/%d",
            assignedSentenceIndex, targetTotal, (assignedSentenceIndex * 100.0) / targetTotal,
            task.bucket.name(), state.domainCounters.get(task.bucket).get(), state.getDomainQuota(task.bucket));
      }
    }
  }

  private static int countDelimiters(String s) {
    int count = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '.' || c == '!' || c == '?' || c == '…') {
        count++;
      }
    }
    return count;
  }

  private static boolean isTechnicalSymbol(int ch) {
    return ch == '@' || ch == '#' || ch == '$' || ch == '%' || ch == '&' || ch == '*'
        || ch == '_' || ch == '=' || ch == '+' || ch == '/' || ch == '\\' || ch == '|'
        || ch == '~' || ch == '^' || ch == '{' || ch == '}' || ch == '[' || ch == ']'
        || ch == '<' || ch == '>';
  }

  private static DisambiguationCandidateExtractor.SentenceRecord evaluateAndExtractCandidates(
      String sentence,
      TurkishTokenizer tokenizer,
      TurkishMorphology morphology) {

    DisambiguationCandidateExtractor.SentenceRecord record = new DisambiguationCandidateExtractor.SentenceRecord();
    record.text = sentence;

    String normalized = TextUtil.normalizeQuotesHyphens(sentence);
    List<Token> tokens = tokenizer.tokenize(normalized);

    int tokenIndex = 0;
    for (Token token : tokens) {
      DisambiguationCandidateExtractor.TokenRecord tr = new DisambiguationCandidateExtractor.TokenRecord();
      tr.index = tokenIndex++;
      tr.surface = token.getText();
      tr.char_start = token.getStart();
      tr.char_end = token.getEnd();

      WordAnalysis wa = morphology.analyze(token);
      int candidateId = 0;
      for (SingleAnalysis sa : wa.getAnalysisResults()) {
        DisambiguationCandidateExtractor.CandidateRecord cr = new DisambiguationCandidateExtractor.CandidateRecord();
        cr.id = candidateId++;
        cr.zemberek_key = sa.formatLong();

        DictionaryItem item = sa.getDictionaryItem();
        cr.lemma = item.lemma;
        cr.pos = item.primaryPos != null ? item.primaryPos.shortForm : PrimaryPos.Unknown.shortForm;
        cr.secondary_pos = (item.secondaryPos != null && item.secondaryPos != SecondaryPos.None)
            ? item.secondaryPos.shortForm
            : "None";
        cr.oflazer_style = AnalysisFormatters.OFLAZER_STYLE.format(sa);
        cr.morphemes = sa.getMorphemes().stream()
            .map(m -> m.id)
            .collect(Collectors.toList());
        cr.is_informal = sa.containsInformalMorpheme();

        tr.candidates.add(cr);
      }

      tr.is_ambiguous = tr.candidates.size() > 1;
      record.tokens.add(tr);
    }

    return record;
  }

  private static String extractTriggerLemmaFromMatch(
      DisambiguationCandidateExtractor.SentenceRecord record,
      int matchStart,
      int matchEnd,
      PatternBundle bundle) {

    // For P06 (plural possessive vs accusative), the target ambiguous token is the noun ending with ları/leri
    if (bundle.patternId.startsWith("P06")) {
      for (DisambiguationCandidateExtractor.TokenRecord tr : record.tokens) {
        if (tr.char_end > matchStart && tr.char_start < matchEnd) {
          String s = tr.surface.toLowerCase(Locale.forLanguageTag("tr"));
          if (s.contains("ları") || s.contains("leri")) {
            if (!tr.candidates.isEmpty()) {
              return tr.candidates.get(0).lemma;
            }
          }
        }
      }
    }

    // First try: ambiguous token strictly overlapping the regex match span
    for (DisambiguationCandidateExtractor.TokenRecord tr : record.tokens) {
      if (tr.char_end > matchStart && tr.char_start < matchEnd) {
        if (tr.is_ambiguous && !tr.candidates.isEmpty()) {
          return tr.candidates.get(0).lemma;
        }
      }
    }
    // Fallback: any token overlapping match span
    for (DisambiguationCandidateExtractor.TokenRecord tr : record.tokens) {
      if (tr.char_end > matchStart && tr.char_start < matchEnd) {
        if (!tr.candidates.isEmpty()) {
          return tr.candidates.get(0).lemma;
        }
      }
    }
    return "UNKNOWN";
  }

  private static List<PatternBundle> loadPatternRules(Path rulesPath, int targetTotal) throws IOException {
    String jsonContent = Files.readString(rulesPath, StandardCharsets.UTF_8);
    JsonObject root = JsonParser.parseString(jsonContent).getAsJsonObject();
    JsonArray patternsArr = root.getAsJsonArray("patterns");

    List<PatternBundle> list = new ArrayList<>();
    for (JsonElement el : patternsArr) {
      JsonObject p = el.getAsJsonObject();
      String pid = p.get("pattern_id").getAsString();
      String canonicalName = p.get("canonical_name").getAsString();
      String macroAxis = p.get("macro_axis").getAsString();

      JsonObject hf = p.getAsJsonObject("harvesting_filter");
      int rawQuota = hf.get("harvesting_quota").getAsInt();
      int totalQuota;
      if (targetTotal == 20000) {
        totalQuota = rawQuota;
      } else {
        totalQuota = Math.max(1, (int) Math.round(rawQuota * ((double) targetTotal / 20000.0)));
      }

      JsonObject posArm = hf.getAsJsonObject("positive_arm");
      JsonObject cntArm = hf.getAsJsonObject("counter_arm");

      String posRegexStr = posArm.get("regex").getAsString();
      String cntRegexStr = cntArm.get("regex").getAsString();

      Pattern posPattern = Pattern.compile(posRegexStr,
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
      Pattern cntPattern = Pattern.compile(cntRegexStr,
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

      int posQuota = Math.max(1, (totalQuota + 1) / 2);
      int cntQuota = Math.max(1, totalQuota / 2);

      list.add(new PatternBundle(pid, canonicalName, macroAxis, totalQuota, posQuota, cntQuota, posPattern, cntPattern));
    }
    return list;
  }

  private static Map<DomainBucket, List<Path>> mapDomainFiles(Path corpusDir) {
    Map<DomainBucket, List<Path>> map = new LinkedHashMap<>();
    for (DomainBucket b : DomainBucket.values()) {
      map.put(b, new ArrayList<>());
    }

    Set<String> newsFiles = Set.of(
        "cnn-turk.txt", "dunya.txt", "ntv-750k-sentences.txt", "ntvmsnbc.txt",
        "radikal.txt", "star-gazete.txt", "www.cnnturk.com.txt", "www.dunya.com.txt",
        "www.ensonhaber.com.txt", "www.haberturk.com.txt", "www.iha.com.tr.txt");

    Set<String> forumFiles = Set.of(
        "forum.memurlar.net.txt", "forum.shiftdelete.net.txt", "kadinlar-klubu.txt",
        "wowturkey.com.txt", "www.kizlarsoruyor.com.txt", "www.nurturia.com.tr.txt",
        "www.sikayetim.com.txt");

    Set<String> subFiles = Set.of(
        "open-subtitles-tr-2018.txt", "open-subtitles.txt",
        "subtitle-tr-10M-sentences.txt", "subtitle-tr-1M-sentences.txt");

    Set<String> legalFiles = Set.of(
        "hukuki-net.txt", "tbmm-tutanak.txt", "tbmm.txt",
        "www.hukuki.net.txt", "yargitay-karar.txt");

    Set<String> wikiFiles = Set.of(
        "onedio.com.txt", "vikipedi.txt", "www.bilim.org.txt",
        "www.defenceturk.com.txt", "www.tvyorumlari.com.txt");

    try (Stream<Path> stream = Files.list(corpusDir)) {
      stream.filter(Files::isRegularFile).forEach(p -> {
        String fname = p.getFileName().toString();
        if (newsFiles.contains(fname)) {
          map.get(DomainBucket.DOMAIN_A_NEWS).add(p);
        } else if (forumFiles.contains(fname)) {
          map.get(DomainBucket.DOMAIN_B_FORUMS).add(p);
        } else if (subFiles.contains(fname)) {
          map.get(DomainBucket.DOMAIN_C_SUBTITLES).add(p);
        } else if (legalFiles.contains(fname)) {
          map.get(DomainBucket.DOMAIN_D_LEGAL).add(p);
        } else if (wikiFiles.contains(fname)) {
          map.get(DomainBucket.DOMAIN_E_ENCYCLOPEDIC).add(p);
        } else {
          map.get(DomainBucket.DOMAIN_A_NEWS).add(p);
        }
      });
    } catch (IOException e) {
      Log.error("Error scanning corpus directory: %s", e.getMessage());
    }

    return map;
  }

  // --- Supporting Classes ---

  public enum DomainBucket {
    DOMAIN_A_NEWS("News & Journalism", 6000, 0.30),
    DOMAIN_B_FORUMS("Conversational & Forums", 5000, 0.25),
    DOMAIN_C_SUBTITLES("Dialogue & Subtitles", 4000, 0.20),
    DOMAIN_D_LEGAL("Official & Legal", 3000, 0.15),
    DOMAIN_E_ENCYCLOPEDIC("Encyclopedic & Science", 2000, 0.10);

    public final String label;
    public final int targetQuota;
    public final double targetPct;

    DomainBucket(String label, int targetQuota, double targetPct) {
      this.label = label;
      this.targetQuota = targetQuota;
      this.targetPct = targetPct;
    }
  }

  public static class RawSentenceTask {
    public final DomainBucket bucket;
    public final String fileName;
    public final String sentence;

    public RawSentenceTask(DomainBucket bucket, String fileName, String sentence) {
      this.bucket = bucket;
      this.fileName = fileName;
      this.sentence = sentence;
    }
  }

  public static class PatternBundle {
    public final String patternId;
    public final String canonicalName;
    public final String macroAxis;
    public final int totalQuota;
    public final int posQuota;
    public final int cntQuota;
    public final Pattern posPattern;
    public final Pattern cntPattern;

    public PatternBundle(
        String patternId,
        String canonicalName,
        String macroAxis,
        int totalQuota,
        int posQuota,
        int cntQuota,
        Pattern posPattern,
        Pattern cntPattern) {
      this.patternId = patternId;
      this.canonicalName = canonicalName;
      this.macroAxis = macroAxis;
      this.totalQuota = totalQuota;
      this.posQuota = posQuota;
      this.cntQuota = cntQuota;
      this.posPattern = posPattern;
      this.cntPattern = cntPattern;
    }
  }

  public static class MatchResult {
    public final PatternBundle bundle;
    public final boolean isPositiveArm;
    public final int matchStart;
    public final int matchEnd;

    public MatchResult(PatternBundle bundle, boolean isPositiveArm, int matchStart, int matchEnd) {
      this.bundle = bundle;
      this.isPositiveArm = isPositiveArm;
      this.matchStart = matchStart;
      this.matchEnd = matchEnd;
    }
  }

  public static class HarvestState {
    public final int targetTotal;
    public final int maxPerFile;
    public final int effectiveMaxPerFile;
    public final int maxPerLemma;

    public final AtomicInteger totalHarvested = new AtomicInteger(0);
    public final Map<DomainBucket, Integer> domainQuotas = new ConcurrentHashMap<>();
    public final Map<DomainBucket, AtomicInteger> domainCounters = new ConcurrentHashMap<>();
    public final Map<String, AtomicInteger> fileCounters = new ConcurrentHashMap<>();
    public final Map<String, AtomicInteger> posArmCounters = new ConcurrentHashMap<>();
    public final Map<String, AtomicInteger> cntArmCounters = new ConcurrentHashMap<>();
    public final Map<String, ConcurrentHashMap<String, AtomicInteger>> lemmaCounters = new ConcurrentHashMap<>();
    public final Set<String> seenSentences = ConcurrentHashMap.newKeySet();

    public HarvestState(List<PatternBundle> bundles, int targetTotal, int maxPerFile, int maxPerLemma) {
      this.targetTotal = targetTotal;
      this.maxPerFile = maxPerFile;
      this.effectiveMaxPerFile = targetTotal == 20000
          ? maxPerFile
          : Math.max(10, (int) Math.round(maxPerFile * ((double) targetTotal / 20000.0)));
      this.maxPerLemma = maxPerLemma;

      for (DomainBucket b : DomainBucket.values()) {
        int quota = Math.max(1, (int) Math.round(targetTotal * b.targetPct));
        domainQuotas.put(b, quota);
        domainCounters.put(b, new AtomicInteger(0));
      }
      for (PatternBundle b : bundles) {
        posArmCounters.put(b.patternId, new AtomicInteger(0));
        cntArmCounters.put(b.patternId, new AtomicInteger(0));
        lemmaCounters.put(b.patternId, new ConcurrentHashMap<>());
      }
    }

    public int getDomainQuota(DomainBucket b) {
      return domainQuotas.getOrDefault(b, (int) Math.round(targetTotal * b.targetPct));
    }

    public int getDomainQuotaMax(DomainBucket b) {
      int base = getDomainQuota(b);
      // Soft envelope (1.30x) accommodates natural syntactic register variations across domains
      return (int) Math.round(base * 1.30);
    }

    public boolean isFullyCompleted() {
      return totalHarvested.get() >= targetTotal;
    }

    public MatchResult findMatchingPattern(String sentence, List<PatternBundle> bundles) {
      List<PatternBundle> prioritized = new ArrayList<>(bundles);
      prioritized.sort(Comparator.comparingDouble(b -> {
        int filled = posArmCounters.get(b.patternId).get() + cntArmCounters.get(b.patternId).get();
        return (double) filled / b.totalQuota;
      }));

      for (PatternBundle b : prioritized) {
        int posCount = posArmCounters.get(b.patternId).get();
        int cntCount = cntArmCounters.get(b.patternId).get();

        if (posCount >= b.posQuota && cntCount >= b.cntQuota) {
          continue;
        }

        // Check arm with fewer matches first
        boolean checkPosFirst = posCount <= cntCount;

        if (checkPosFirst) {
          if (posCount < b.posQuota) {
            Matcher m = b.posPattern.matcher(sentence);
            if (m.find()) {
              return new MatchResult(b, true, m.start(), m.end());
            }
          }
          if (cntCount < b.cntQuota) {
            Matcher m = b.cntPattern.matcher(sentence);
            if (m.find()) {
              return new MatchResult(b, false, m.start(), m.end());
            }
          }
        } else {
          if (cntCount < b.cntQuota) {
            Matcher m = b.cntPattern.matcher(sentence);
            if (m.find()) {
              return new MatchResult(b, false, m.start(), m.end());
            }
          }
          if (posCount < b.posQuota) {
            Matcher m = b.posPattern.matcher(sentence);
            if (m.find()) {
              return new MatchResult(b, true, m.start(), m.end());
            }
          }
        }
      }
      return null;
    }

    private static final Set<String> CLOSED_CLASS_PATTERNS = Set.of(
        "P10_SHIFT_ADJ_TO_NOUN_SUBSTANTIVIZED",
        "P16_SPEC_PRON_TO_DET_BEFORE_NON_NOUN",
        "P17_SPEC_PRON_TO_DET_WITH_COMMA",
        "P18_SPEC_DET_TO_PRON_BEFORE_NOUN",
        "P19_SPEC_CARDINAL_NUM_TO_INDEF_DET",
        "P20_SPEC_INDEF_DET_TO_CARDINAL_NUM",
        "P21_SPEC_PRON_PERS_VS_DEMONS",
        "P29_ARTIFACT_CIRCUMFLEX_TWIN",
        "P30_ARTIFACT_DICTIONARY_NISBA_TWIN",
        "P31_ARTIFACT_FIXED_NOUN_YUZDE",
        "P32_ARTIFACT_FUNCTION_WORD_CONVENTION",
        "P33_ARTIFACT_POSTPOSITION_CASE_FRAME",
        "P35_FUNCTION_ADV_VS_CONJ",
        "P36_INTERJECTION_VS_CONTENT_WORD"
    );

    public boolean isLemmaCappedPattern(String patternId) {
      return !CLOSED_CLASS_PATTERNS.contains(patternId);
    }

    public synchronized int claimQuota(
        PatternBundle bundle,
        boolean isPos,
        DomainBucket bucket,
        String fileName,
        String triggerLemma) {

      if (totalHarvested.get() >= targetTotal) return -1;
      if (domainCounters.get(bucket).get() >= getDomainQuotaMax(bucket)) return -1;

      AtomicInteger armCount = isPos ? posArmCounters.get(bundle.patternId) : cntArmCounters.get(bundle.patternId);
      int armTarget = isPos ? bundle.posQuota : bundle.cntQuota;
      if (armCount.get() >= armTarget) return -1;

      AtomicInteger fileCount = fileCounters.computeIfAbsent(fileName, k -> new AtomicInteger(0));
      if (fileCount.get() >= effectiveMaxPerFile) return -1;

      // Check and increment lemma cap atomically (for open-class patterns)
      if (isLemmaCappedPattern(bundle.patternId) && triggerLemma != null && !triggerLemma.equals("UNKNOWN")) {
        AtomicInteger lemmaCount = lemmaCounters.get(bundle.patternId).computeIfAbsent(triggerLemma, k -> new AtomicInteger(0));
        if (lemmaCount.get() >= maxPerLemma) return -1;
        lemmaCount.incrementAndGet();
      }

      // Increment counters atomically
      armCount.incrementAndGet();
      domainCounters.get(bucket).incrementAndGet();
      fileCount.incrementAndGet();
      return totalHarvested.incrementAndGet();
    }

    public HarvestSummary buildSummary(long elapsedSec) {
      HarvestSummary s = new HarvestSummary();
      s.total_harvested = totalHarvested.get();
      s.target_total = targetTotal;
      s.elapsed_seconds = elapsedSec;
      s.timestamp = Instant.now().toString();

      for (DomainBucket b : DomainBucket.values()) {
        s.domain_distribution.put(b.name(), domainCounters.get(b).get());
      }
      for (Map.Entry<String, AtomicInteger> e : fileCounters.entrySet()) {
        if (e.getValue().get() > 0) {
          s.per_file_counts.put(e.getKey(), e.getValue().get());
        }
      }
      for (Map.Entry<String, AtomicInteger> e : posArmCounters.entrySet()) {
        String pid = e.getKey();
        int pos = e.getValue().get();
        int cnt = cntArmCounters.getOrDefault(pid, new AtomicInteger(0)).get();
        HarvestSummary.PatternArmStats stats = new HarvestSummary.PatternArmStats();
        stats.positive = pos;
        stats.counter = cnt;
        stats.total = pos + cnt;
        s.pattern_arm_counts.put(pid, stats);
      }
      return s;
    }
  }

  public static class HarvestCandidateRecord {
    public String sentence_id;
    public String domain;
    public String pattern_id;
    public String pattern_arm;
    public String source_file;
    public String text;
    public int total_tokens;
    public int ambiguous_tokens;
    public double ambiguity_rate;
    public List<DisambiguationCandidateExtractor.TokenRecord> tokens;
  }

  public static class HarvestSummary {
    public int total_harvested;
    public int target_total;
    public long elapsed_seconds;
    public String timestamp;
    public Map<String, Integer> domain_distribution = new LinkedHashMap<>();
    public Map<String, Integer> per_file_counts = new LinkedHashMap<>();
    public Map<String, PatternArmStats> pattern_arm_counts = new LinkedHashMap<>();

    public static class PatternArmStats {
      public int total;
      public int positive;
      public int counter;
    }
  }
}
