package zemberek.morphology.ambiguity.dataset;

import com.beust.jcommander.Parameter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import zemberek.apps.ConsoleApp;
import zemberek.core.data.WeightLookup;
import zemberek.core.logging.Log;
import zemberek.core.turkish.PrimaryPos;
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.ambiguity.dataset.DisambiguationCandidateExtractor.CandidateRecord;
import zemberek.morphology.ambiguity.dataset.DisambiguationCandidateExtractor.SentenceRecord;
import zemberek.morphology.ambiguity.dataset.DisambiguationCandidateExtractor.TokenRecord;
import zemberek.morphology.ambiguity.fast.FastPerceptronAmbiguityResolver;
import zemberek.morphology.ambiguity.fast.FastPerceptronAmbiguityResolver.CandidateContext;
import zemberek.morphology.ambiguity.fast.FastPerceptronAmbiguityResolver.FastDecodeResult;
import zemberek.morphology.ambiguity.fast.FastPerceptronAmbiguityResolver.FastDecoder;
import zemberek.morphology.analysis.SingleAnalysis;
import zemberek.morphology.analysis.WordAnalysis;
import zemberek.morphology.lexicon.RootLexicon;

public class DiagnosticCandidateScoreCloseness extends ConsoleApp {

  @Parameter(
      names = {"--model", "-m"},
      required = true,
      description = "Path to trained binary model (.bin)")
  public Path modelPath;

  @Parameter(
      names = {"--test1"},
      description = "Path to test 1 (e.g., golden_test_1000.jsonl)")
  public Path test1Path = Paths.get("data/ambiguity/golden/golden_test_1000.jsonl");

  @Parameter(
      names = {"--test2"},
      description = "Path to test 2 (e.g., golden_test_2500.jsonl)")
  public Path test2Path = Paths.get("data/ambiguity/golden/golden_test_2500.jsonl");

  @Parameter(
      names = {"--test3"},
      description = "Path to test 3 (e.g., golden_fresh_2500.jsonl)")
  public Path test3Path = Paths.get("data/ambiguity/golden/golden_fresh_2500.jsonl");

  @Parameter(
      names = {"--output", "-o"},
      required = true,
      description = "Path to write JSON report")
  public Path outputPath;

  public static void main(String[] args) {
    new DiagnosticCandidateScoreCloseness().execute(args);
  }

  @Override
  public String description() {
    return "Evaluates candidate score closeness, rank distributions, and margins on ambiguous error tokens.";
  }

  public static class CandidateScoreInfo {
    public SingleAnalysis analysis;
    public int candidateId;
    public String key;
    public float seqScore;
    public float localScore;
  }

  public static class ErrorTokenDetail {
    public String sentenceId;
    public int tokenIndex;
    public String surface;
    public int candidateCount;
    public int goldId;
    public String goldKey;
    public String goldPos;
    public int predId;
    public String predKey;
    public String predPos;
    public int seqRankGold;
    public float seqMargin; // pred_score - gold_score
    public double seqProbGold;
    public double seqProbPred;
    public int localRankGold;
    public float localMargin;
    public double localProbGold;
    public double localProbPred;
    public boolean samePos;
    public boolean sameLemma;
  }

  public static class BenchmarkStats {
    public String name;
    public int totalSentences = 0;
    public int totalTokens = 0;
    public int ambiguousTokens = 0;
    public int correctAmbiguous = 0;
    public int errorAmbiguous = 0;
    public double ambiguityAccuracy = 0.0;

    // Top-k Rank distribution among error tokens
    public int errorsGoldRank2 = 0;
    public int errorsGoldRank3 = 0;
    public int errorsGoldRank4Plus = 0;
    public int errorsGoldUnmatched = 0;

    public double pctErrorsGoldRank2 = 0.0;
    public double pctErrorsGoldInTop3 = 0.0;
    public double top2AmbiguityAccuracy = 0.0;
    public double top3AmbiguityAccuracy = 0.0;

    // Sequence Margins (pred - gold)
    public double meanSeqMargin = 0.0;
    public double medianSeqMargin = 0.0;
    public double p25SeqMargin = 0.0;
    public double p75SeqMargin = 0.0;
    public double p90SeqMargin = 0.0;

    public int seqMargin_0_to_0_5 = 0;
    public int seqMargin_0_5_to_1_0 = 0;
    public int seqMargin_1_0_to_2_0 = 0;
    public int seqMargin_2_0_to_5_0 = 0;
    public int seqMargin_5_plus = 0;

    // Local Margins (pred - gold)
    public double meanLocalMargin = 0.0;
    public double medianLocalMargin = 0.0;
    public int localMargin_0_to_0_5 = 0;
    public int localMargin_0_5_to_1_0 = 0;
    public int localMargin_1_0_to_2_0 = 0;
    public int localMargin_2_0_to_5_0 = 0;
    public int localMargin_5_plus = 0;

    // Softmax probabilities
    public double meanSeqProbGold = 0.0;
    public double medianSeqProbGold = 0.0;
    public double meanSeqProbPred = 0.0;

    // Linguistic severity
    public int errorsSamePos = 0;
    public int errorsDifferentPos = 0;
    public int errorsSameLemma = 0;
    public double pctErrorsSamePos = 0.0;

    // Top error words
    public Map<String, Integer> topErrorWords = new LinkedHashMap<>();
    public List<Map<String, Object>> topErrorWordProfiles = new ArrayList<>();
  }

  public static class OverallReport {
    public String modelName;
    public BenchmarkStats test1000 = new BenchmarkStats();
    public BenchmarkStats test2500 = new BenchmarkStats();
    public BenchmarkStats fresh2500 = new BenchmarkStats();
    public BenchmarkStats combined = new BenchmarkStats();
    public List<ErrorTokenDetail> sampleErrors = new ArrayList<>();
  }

  @Override
  public void run() throws Exception {
    Log.info("Loading FastPerceptron model from: %s", modelPath);
    FastPerceptronAmbiguityResolver resolver = FastPerceptronAmbiguityResolver.fromModelFile(modelPath);
    TurkishMorphology morphology = TurkishMorphology.builder()
        .setLexicon(RootLexicon.getDefault())
        .setAmbiguityResolver(resolver)
        .build();

    OverallReport report = new OverallReport();
    report.modelName = modelPath.getFileName().toString();

    List<ErrorTokenDetail> allErrorsCombined = new ArrayList<>();

    if (Files.exists(test1Path)) {
      Log.info("Processing Test 1 (1000 sentences): %s", test1Path);
      report.test1000.name = "golden_test_1000";
      List<ErrorTokenDetail> errs = processBenchmark(morphology, resolver, test1Path, report.test1000);
      allErrorsCombined.addAll(errs);
    }

    if (Files.exists(test2Path)) {
      Log.info("Processing Test 2 (2500 sentences): %s", test2Path);
      report.test2500.name = "golden_test_2500";
      List<ErrorTokenDetail> errs = processBenchmark(morphology, resolver, test2Path, report.test2500);
      allErrorsCombined.addAll(errs);
    }

    if (Files.exists(test3Path)) {
      Log.info("Processing Test 3 (2500 fresh sentences): %s", test3Path);
      report.fresh2500.name = "golden_fresh_2500";
      List<ErrorTokenDetail> errs = processBenchmark(morphology, resolver, test3Path, report.fresh2500);
      allErrorsCombined.addAll(errs);
    }

    // Compute combined statistics
    Log.info("Aggregating combined statistics across all benchmarks...");
    computeCombinedStats(report, allErrorsCombined);

    // Pick 50 sample errors for inspection
    if (allErrorsCombined.size() > 50) {
      report.sampleErrors = allErrorsCombined.subList(0, 50);
    } else {
      report.sampleErrors = allErrorsCombined;
    }

    if (outputPath.getParent() != null) {
      Files.createDirectories(outputPath.getParent());
    }
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
      gson.toJson(report, writer);
    }

    Log.info("Diagnostic completed successfully. Report written to %s", outputPath);
    printReportSummary(report);
  }

  private List<ErrorTokenDetail> processBenchmark(
      TurkishMorphology morphology,
      FastPerceptronAmbiguityResolver resolver,
      Path jsonlPath,
      BenchmarkStats stats) throws IOException {

    FastDecoder decoder = resolver.getDecoder();
    WeightLookup model = resolver.getModel();
    CandidateContext beginContext = new CandidateContext(FastPerceptronAmbiguityResolver.sentenceBegin, model, false, 0);
    CandidateContext endContext = new CandidateContext(FastPerceptronAmbiguityResolver.sentenceEnd, model, false, 0);

    List<ErrorTokenDetail> errors = new ArrayList<>();
    Gson gson = new Gson();

    Map<String, Integer> wordFailCounts = new HashMap<>();
    Map<String, List<Float>> wordMargins = new HashMap<>();

    try (BufferedReader reader = Files.newBufferedReader(jsonlPath, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty()) continue;
        SentenceRecord rec = gson.fromJson(line, SentenceRecord.class);
        if (rec == null || rec.text == null || rec.tokens == null) continue;

        stats.totalSentences++;
        String text = rec.text;

        List<WordAnalysis> waList = morphology.analyzeSentence(text);
        FastDecodeResult bestResult = decoder.bestPath(waList);
        List<SingleAnalysis> bestParse = bestResult.bestParse;
        float bestScore = bestResult.score;

        int limit = Math.min(rec.tokens.size(), waList.size());
        for (int i = 0; i < limit; i++) {
          TokenRecord t = rec.tokens.get(i);
          stats.totalTokens++;
          if (!t.is_ambiguous) {
            continue;
          }
          stats.ambiguousTokens++;

          int goldId = (t.selected_candidate_id != null) ? t.selected_candidate_id : 0;
          String goldKey = EvaluateAmbiguityModel.getCandidateKey(t, goldId);
          String goldPos = EvaluateAmbiguityModel.getCandidatePos(t, goldId);

          SingleAnalysis predSa = (i < bestParse.size()) ? bestParse.get(i) : null;
          int predId = EvaluateAmbiguityModel.matchAnalysisToCandidateId(t, predSa);
          String predKey = predSa != null ? predSa.formatLong() : "";
          String predPos = (predSa != null && predSa.getDictionaryItem() != null)
              ? predSa.getDictionaryItem().primaryPos.name()
              : "Unknown";

          boolean isMatch = EvaluateAmbiguityModel.isMatch(t, predId, goldId, predKey, goldKey);
          if (isMatch) {
            stats.correctAmbiguous++;
            continue;
          }

          // Error token!
          stats.errorAmbiguous++;
          WordAnalysis origWa = waList.get(i);
          List<SingleAnalysis> candidates = origWa.getAnalysisResults();
          if (candidates.isEmpty()) {
            candidates = Collections.singletonList(SingleAnalysis.unknown(origWa.getInput()));
          }

          // 1. Constrained Sequence Viterbi for each candidate
          List<CandidateScoreInfo> scoredCands = new ArrayList<>(candidates.size());
          CandidateScoreInfo goldInfo = null;
          CandidateScoreInfo predInfo = null;

          for (int cIdx = 0; cIdx < candidates.size(); cIdx++) {
            SingleAnalysis cSa = candidates.get(cIdx);
            CandidateScoreInfo cInfo = new CandidateScoreInfo();
            cInfo.analysis = cSa;
            cInfo.key = cSa.formatLong();
            cInfo.candidateId = EvaluateAmbiguityModel.matchAnalysisToCandidateId(t, cSa);

            // Constrained Viterbi
            waList.set(i, origWa.copyFor(Collections.singletonList(cSa)));
            FastDecodeResult cRes = decoder.bestPath(waList);
            cInfo.seqScore = cRes.score;

            // Local score within unconstrained bestParse context
            SingleAnalysis w_i_minus_2 = (i >= 2) ? bestParse.get(i - 2) : FastPerceptronAmbiguityResolver.sentenceBegin;
            SingleAnalysis w_i_minus_1 = (i >= 1) ? bestParse.get(i - 1) : FastPerceptronAmbiguityResolver.sentenceBegin;
            SingleAnalysis w_i_plus_1 = (i + 1 < bestParse.size()) ? bestParse.get(i + 1) : FastPerceptronAmbiguityResolver.sentenceEnd;
            SingleAnalysis w_i_plus_2 = (i + 2 < bestParse.size()) ? bestParse.get(i + 2) : FastPerceptronAmbiguityResolver.sentenceEnd;

            CandidateContext cc_prevPrev = new CandidateContext(w_i_minus_2, model, (i - 2 == 0), 0);
            CandidateContext cc_prev = new CandidateContext(w_i_minus_1, model, (i - 1 == 0), 0);
            CandidateContext cc_curr = new CandidateContext(cSa, model, (i == 0), 0);
            CandidateContext cc_next = new CandidateContext(w_i_plus_1, model, false, 0);
            CandidateContext cc_nextNext = new CandidateContext(w_i_plus_2, model, false, 0);

            float localScore = cc_curr.uniScore;
            localScore += decoder.computeBigramScore(cc_prev, cc_curr);
            localScore += decoder.computeTrigramScore(cc_prevPrev, cc_prev, cc_curr);
            localScore += decoder.computeBigramScore(cc_curr, cc_next);
            localScore += decoder.computeTrigramScore(cc_prev, cc_curr, cc_next);
            localScore += decoder.computeTrigramScore(cc_curr, cc_next, cc_nextNext);
            cInfo.localScore = localScore;

            scoredCands.add(cInfo);

            if (EvaluateAmbiguityModel.isMatch(t, cInfo.candidateId, goldId, cInfo.key, goldKey)) {
              if (goldInfo == null || cInfo.seqScore > goldInfo.seqScore) {
                goldInfo = cInfo;
              }
            }
            if (EvaluateAmbiguityModel.isMatch(t, cInfo.candidateId, predId, cInfo.key, predKey)) {
              if (predInfo == null || cInfo.seqScore > predInfo.seqScore) {
                predInfo = cInfo;
              }
            }
          }

          // Restore original WordAnalysis
          waList.set(i, origWa);

          // If predInfo not found by match, fallback to highest scored candidate
          if (predInfo == null && !scoredCands.isEmpty()) {
            predInfo = scoredCands.stream().max(Comparator.comparingDouble(c -> c.seqScore)).orElse(null);
          }

          if (scoredCands.isEmpty()) {
            continue;
          }

          // Evaluate sequence ranks and softmax
          scoredCands.sort((a, b) -> Float.compare(b.seqScore, a.seqScore));
          int seqRankGold = 0;
          for (int r = 0; r < scoredCands.size(); r++) {
            if (goldInfo != null && scoredCands.get(r) == goldInfo) {
              seqRankGold = r + 1;
              break;
            }
          }

          // Softmax sequence probabilities
          float maxSeqScore = scoredCands.get(0).seqScore;
          double seqSumExp = 0.0;
          for (CandidateScoreInfo c : scoredCands) {
            seqSumExp += Math.exp(c.seqScore - maxSeqScore);
          }
          double seqProbGold = (goldInfo != null && seqSumExp > 0)
              ? Math.exp(goldInfo.seqScore - maxSeqScore) / seqSumExp
              : 0.0;
          double seqProbPred = (predInfo != null && seqSumExp > 0)
              ? Math.exp(predInfo.seqScore - maxSeqScore) / seqSumExp
              : 0.0;

          // Local rank and softmax
          scoredCands.sort((a, b) -> Float.compare(b.localScore, a.localScore));
          int localRankGold = 0;
          for (int r = 0; r < scoredCands.size(); r++) {
            if (goldInfo != null && scoredCands.get(r) == goldInfo) {
              localRankGold = r + 1;
              break;
            }
          }
          float maxLocalScore = scoredCands.get(0).localScore;
          double localSumExp = 0.0;
          for (CandidateScoreInfo c : scoredCands) {
            localSumExp += Math.exp(c.localScore - maxLocalScore);
          }
          double localProbGold = (goldInfo != null && localSumExp > 0)
              ? Math.exp(goldInfo.localScore - maxLocalScore) / localSumExp
              : 0.0;
          double localProbPred = (predInfo != null && localSumExp > 0)
              ? Math.exp(predInfo.localScore - maxLocalScore) / localSumExp
              : 0.0;

          // Compute Margins
          float seqMargin = (goldInfo != null && predInfo != null)
              ? (predInfo.seqScore - goldInfo.seqScore)
              : 99.0f;
          float localMargin = (goldInfo != null && predInfo != null)
              ? (predInfo.localScore - goldInfo.localScore)
              : 99.0f;

          ErrorTokenDetail detail = new ErrorTokenDetail();
          detail.sentenceId = rec.sentence_id;
          detail.tokenIndex = i;
          detail.surface = t.surface;
          detail.candidateCount = candidates.size();
          detail.goldId = goldId;
          detail.goldKey = goldKey;
          detail.goldPos = goldPos;
          detail.predId = predId;
          detail.predKey = predKey;
          detail.predPos = predPos;
          detail.seqRankGold = seqRankGold;
          detail.seqMargin = seqMargin;
          detail.seqProbGold = seqProbGold;
          detail.seqProbPred = seqProbPred;
          detail.localRankGold = localRankGold;
          detail.localMargin = localMargin;
          detail.localProbGold = localProbGold;
          detail.localProbPred = localProbPred;

          String gLemma = goldInfo != null && goldInfo.analysis.getDictionaryItem() != null
              ? goldInfo.analysis.getDictionaryItem().lemma : "";
          String pLemma = predSa != null && predSa.getDictionaryItem() != null
              ? predSa.getDictionaryItem().lemma : "";
          detail.sameLemma = !gLemma.isEmpty() && gLemma.equalsIgnoreCase(pLemma);

          PrimaryPos gPos = goldInfo != null && goldInfo.analysis.getDictionaryItem() != null
              ? goldInfo.analysis.getDictionaryItem().primaryPos : null;
          PrimaryPos pPos = predSa != null && predSa.getDictionaryItem() != null
              ? predSa.getDictionaryItem().primaryPos : null;
          detail.samePos = (gPos != null && gPos == pPos);

          errors.add(detail);

          // Update Top error word tracking
          String surfLower = t.surface.toLowerCase(Locale.forLanguageTag("tr"));
          wordFailCounts.put(surfLower, wordFailCounts.getOrDefault(surfLower, 0) + 1);
          wordMargins.computeIfAbsent(surfLower, k -> new ArrayList<>()).add(seqMargin);
        }
      }
    }

    // Populate benchmark statistics
    computeStatsForList(stats, errors, wordFailCounts, wordMargins);
    return errors;
  }

  private static void computeStatsForList(
      BenchmarkStats stats,
      List<ErrorTokenDetail> errors,
      Map<String, Integer> wordFailCounts,
      Map<String, List<Float>> wordMargins) {

    if (stats.ambiguousTokens > 0) {
      stats.ambiguityAccuracy = (double) stats.correctAmbiguous / stats.ambiguousTokens;
    }

    List<Float> validSeqMargins = new ArrayList<>();
    List<Float> validLocalMargins = new ArrayList<>();
    List<Double> validSeqProbs = new ArrayList<>();
    List<Double> validPredProbs = new ArrayList<>();

    for (ErrorTokenDetail e : errors) {
      if (e.seqRankGold == 2) {
        stats.errorsGoldRank2++;
      } else if (e.seqRankGold == 3) {
        stats.errorsGoldRank3++;
      } else if (e.seqRankGold > 3) {
        stats.errorsGoldRank4Plus++;
      } else {
        stats.errorsGoldUnmatched++;
      }

      if (e.seqRankGold > 0) {
        validSeqMargins.add(e.seqMargin);
        validLocalMargins.add(e.localMargin);
        validSeqProbs.add(e.seqProbGold);
        validPredProbs.add(e.seqProbPred);

        // Sequence Margin bins
        if (e.seqMargin <= 0.5f) {
          stats.seqMargin_0_to_0_5++;
        } else if (e.seqMargin <= 1.0f) {
          stats.seqMargin_0_5_to_1_0++;
        } else if (e.seqMargin <= 2.0f) {
          stats.seqMargin_1_0_to_2_0++;
        } else if (e.seqMargin <= 5.0f) {
          stats.seqMargin_2_0_to_5_0++;
        } else {
          stats.seqMargin_5_plus++;
        }

        // Local Margin bins
        if (e.localMargin <= 0.5f) {
          stats.localMargin_0_to_0_5++;
        } else if (e.localMargin <= 1.0f) {
          stats.localMargin_0_5_to_1_0++;
        } else if (e.localMargin <= 2.0f) {
          stats.localMargin_1_0_to_2_0++;
        } else if (e.localMargin <= 5.0f) {
          stats.localMargin_2_0_to_5_0++;
        } else {
          stats.localMargin_5_plus++;
        }
      }

      if (e.samePos) {
        stats.errorsSamePos++;
      } else {
        stats.errorsDifferentPos++;
      }
      if (e.sameLemma) {
        stats.errorsSameLemma++;
      }
    }

    if (stats.errorAmbiguous > 0) {
      stats.pctErrorsGoldRank2 = (double) stats.errorsGoldRank2 / stats.errorAmbiguous;
      stats.pctErrorsGoldInTop3 = (double) (stats.errorsGoldRank2 + stats.errorsGoldRank3) / stats.errorAmbiguous;
      stats.pctErrorsSamePos = (double) stats.errorsSamePos / stats.errorAmbiguous;
    }
    if (stats.ambiguousTokens > 0) {
      stats.top2AmbiguityAccuracy = (double) (stats.correctAmbiguous + stats.errorsGoldRank2) / stats.ambiguousTokens;
      stats.top3AmbiguityAccuracy = (double) (stats.correctAmbiguous + stats.errorsGoldRank2 + stats.errorsGoldRank3) / stats.ambiguousTokens;
    }

    Collections.sort(validSeqMargins);
    if (!validSeqMargins.isEmpty()) {
      double sum = 0;
      for (float m : validSeqMargins) sum += m;
      stats.meanSeqMargin = sum / validSeqMargins.size();
      stats.medianSeqMargin = validSeqMargins.get(validSeqMargins.size() / 2);
      stats.p25SeqMargin = validSeqMargins.get((int) (validSeqMargins.size() * 0.25));
      stats.p75SeqMargin = validSeqMargins.get((int) (validSeqMargins.size() * 0.75));
      stats.p90SeqMargin = validSeqMargins.get((int) (validSeqMargins.size() * 0.90));
    }

    Collections.sort(validLocalMargins);
    if (!validLocalMargins.isEmpty()) {
      double sum = 0;
      for (float m : validLocalMargins) sum += m;
      stats.meanLocalMargin = sum / validLocalMargins.size();
      stats.medianLocalMargin = validLocalMargins.get(validLocalMargins.size() / 2);
    }

    if (!validSeqProbs.isEmpty()) {
      double sum = 0;
      for (double p : validSeqProbs) sum += p;
      stats.meanSeqProbGold = sum / validSeqProbs.size();
      Collections.sort(validSeqProbs);
      stats.medianSeqProbGold = validSeqProbs.get(validSeqProbs.size() / 2);
    }
    if (!validPredProbs.isEmpty()) {
      double sum = 0;
      for (double p : validPredProbs) sum += p;
      stats.meanSeqProbPred = sum / validPredProbs.size();
    }

    // Top error words
    List<Map.Entry<String, Integer>> sortedWords = new ArrayList<>(wordFailCounts.entrySet());
    sortedWords.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
    int topN = Math.min(25, sortedWords.size());
    for (int i = 0; i < topN; i++) {
      Map.Entry<String, Integer> entry = sortedWords.get(i);
      stats.topErrorWords.put(entry.getKey(), entry.getValue());

      List<Float> margins = wordMargins.get(entry.getKey());
      float avgM = 0;
      if (margins != null && !margins.isEmpty()) {
        for (float m : margins) avgM += m;
        avgM /= margins.size();
      }
      Map<String, Object> prof = new LinkedHashMap<>();
      prof.put("word", entry.getKey());
      prof.put("fail_count", entry.getValue());
      prof.put("avg_margin", avgM);
      stats.topErrorWordProfiles.add(prof);
    }
  }

  private static void computeCombinedStats(OverallReport report, List<ErrorTokenDetail> allErrors) {
    BenchmarkStats c = report.combined;
    c.name = "combined_golden_6000";
    c.totalSentences = report.test1000.totalSentences + report.test2500.totalSentences + report.fresh2500.totalSentences;
    c.totalTokens = report.test1000.totalTokens + report.test2500.totalTokens + report.fresh2500.totalTokens;
    c.ambiguousTokens = report.test1000.ambiguousTokens + report.test2500.ambiguousTokens + report.fresh2500.ambiguousTokens;
    c.correctAmbiguous = report.test1000.correctAmbiguous + report.test2500.correctAmbiguous + report.fresh2500.correctAmbiguous;
    c.errorAmbiguous = report.test1000.errorAmbiguous + report.test2500.errorAmbiguous + report.fresh2500.errorAmbiguous;

    Map<String, Integer> wordFailCounts = new HashMap<>();
    Map<String, List<Float>> wordMargins = new HashMap<>();
    for (ErrorTokenDetail e : allErrors) {
      String surfLower = e.surface.toLowerCase(Locale.forLanguageTag("tr"));
      wordFailCounts.put(surfLower, wordFailCounts.getOrDefault(surfLower, 0) + 1);
      wordMargins.computeIfAbsent(surfLower, k -> new ArrayList<>()).add(e.seqMargin);
    }

    computeStatsForList(c, allErrors, wordFailCounts, wordMargins);
  }

  private static void printReportSummary(OverallReport report) {
    BenchmarkStats c = report.combined;
    System.out.println("==========================================================================");
    System.out.println("FAST PERCEPTRON CANDIDATE CLOSENESS REPORT: " + report.modelName);
    System.out.println("==========================================================================");
    System.out.printf("Total Golden Sentences:  %,d\n", c.totalSentences);
    System.out.printf("Total Tokens:            %,d\n", c.totalTokens);
    System.out.printf("Ambiguous Tokens:        %,d\n", c.ambiguousTokens);
    System.out.printf("Ambiguity Accuracy:      %.2f%% (%d / %d)\n", c.ambiguityAccuracy * 100, c.correctAmbiguous, c.ambiguousTokens);
    System.out.printf("Total Ambiguity Errors:  %,d\n", c.errorAmbiguous);
    System.out.println("--------------------------------------------------------------------------");
    System.out.println("1. CANDIDATE RANK DISTRIBUTION ON ERRORS (How close was gold?):");
    System.out.printf("   - Gold at Rank 2 (Runner-up):    %,d (%.1f%% of all errors)\n", c.errorsGoldRank2, c.pctErrorsGoldRank2 * 100);
    System.out.printf("   - Gold at Rank 3:                %,d (%.1f%% of all errors)\n", c.errorsGoldRank3, (double) c.errorsGoldRank3 / c.errorAmbiguous * 100);
    System.out.printf("   - Gold in Top 3:                 %,d (%.1f%% of all errors)\n", c.errorsGoldRank2 + c.errorsGoldRank3, c.pctErrorsGoldInTop3 * 100);
    System.out.printf("   - Gold at Rank 4+:               %,d (%.1f%% of all errors)\n", c.errorsGoldRank4Plus, (double) c.errorsGoldRank4Plus / c.errorAmbiguous * 100);
    System.out.printf("   - Gold Unmatched / Lexicon gap:  %,d (%.1f%% of all errors)\n", c.errorsGoldUnmatched, (double) c.errorsGoldUnmatched / c.errorAmbiguous * 100);
    System.out.println("   --> TOP-2 AMBIGUITY ACCURACY:    " + String.format("%.2f%%", c.top2AmbiguityAccuracy * 100));
    System.out.println("   --> TOP-3 AMBIGUITY ACCURACY:    " + String.format("%.2f%%", c.top3AmbiguityAccuracy * 100));
    System.out.println("--------------------------------------------------------------------------");
    System.out.println("2. SEQUENCE SCORE MARGIN DISTRIBUTION (Delta = Score(pred) - Score(gold)):");
    System.out.printf("   - Mean Delta Margin:             %.3f\n", c.meanSeqMargin);
    System.out.printf("   - Median Delta Margin:           %.3f\n", c.medianSeqMargin);
    System.out.printf("   - 25th Percentile:               %.3f\n", c.p25SeqMargin);
    System.out.printf("   - 75th Percentile:               %.3f\n", c.p75SeqMargin);
    System.out.printf("   - 90th Percentile:               %.3f\n", c.p90SeqMargin);
    System.out.println("   Margin Breakdown:");
    int mTotal = c.errorAmbiguous - c.errorsGoldUnmatched;
    System.out.printf("     * Razor-thin (0.0 to 0.5]:     %,d (%.1f%%)\n", c.seqMargin_0_to_0_5, (double) c.seqMargin_0_to_0_5 / mTotal * 100);
    System.out.printf("     * Very close (0.5 to 1.0]:     %,d (%.1f%%)\n", c.seqMargin_0_5_to_1_0, (double) c.seqMargin_0_5_to_1_0 / mTotal * 100);
    System.out.printf("     * Moderate   (1.0 to 2.0]:     %,d (%.1f%%)\n", c.seqMargin_1_0_to_2_0, (double) c.seqMargin_1_0_to_2_0 / mTotal * 100);
    System.out.printf("     * Wide       (2.0 to 5.0]:     %,d (%.1f%%)\n", c.seqMargin_2_0_to_5_0, (double) c.seqMargin_2_0_to_5_0 / mTotal * 100);
    System.out.printf("     * Suppressed (> 5.0):          %,d (%.1f%%)\n", c.seqMargin_5_plus, (double) c.seqMargin_5_plus / mTotal * 100);
    System.out.println("--------------------------------------------------------------------------");
    System.out.println("3. SOFTMAX POSTERIOR PROBABILITY:");
    System.out.printf("   - Mean P(Gold) on Errors:        %.3f (%.1f%%)\n", c.meanSeqProbGold, c.meanSeqProbGold * 100);
    System.out.printf("   - Median P(Gold) on Errors:      %.3f (%.1f%%)\n", c.medianSeqProbGold, c.medianSeqProbGold * 100);
    System.out.printf("   - Mean P(Pred) on Errors:        %.3f (%.1f%%)\n", c.meanSeqProbPred, c.meanSeqProbPred * 100);
    System.out.println("--------------------------------------------------------------------------");
    System.out.println("4. MORPHOLOGICAL / POS SEVERITY:");
    System.out.printf("   - Same Primary POS (Inflection): %,d (%.1f%% of errors)\n", c.errorsSamePos, c.pctErrorsSamePos * 100);
    System.out.printf("   - Same Root Lemma:               %,d (%.1f%% of errors)\n", c.errorsSameLemma, (double) c.errorsSameLemma / c.errorAmbiguous * 100);
    System.out.printf("   - Different Primary POS:         %,d (%.1f%% of errors)\n", c.errorsDifferentPos, (double) c.errorsDifferentPos / c.errorAmbiguous * 100);
    System.out.println("==========================================================================");
  }
}
