package zemberek.morphology.ambiguity.dataset;

import com.beust.jcommander.Parameter;
import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import zemberek.apps.ConsoleApp;
import zemberek.core.data.WeightLookup;
import zemberek.core.logging.Log;
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.ambiguity.dataset.DisambiguationCandidateExtractor.SentenceRecord;
import zemberek.morphology.ambiguity.dataset.DisambiguationCandidateExtractor.TokenRecord;
import zemberek.morphology.ambiguity.fast.FastPerceptronAmbiguityResolver;
import zemberek.morphology.ambiguity.fast.FastPerceptronAmbiguityResolver.CandidateContext;
import zemberek.morphology.ambiguity.fast.FastPerceptronAmbiguityResolver.FastDecodeResult;
import zemberek.morphology.ambiguity.fast.FastPerceptronAmbiguityResolver.FastDecoder;
import zemberek.morphology.analysis.SingleAnalysis;
import zemberek.morphology.analysis.WordAnalysis;
import zemberek.morphology.lexicon.RootLexicon;

public class AnalyzeTopTwoScoreDifference extends ConsoleApp {

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

  public static void main(String[] args) {
    new AnalyzeTopTwoScoreDifference().execute(args);
  }

  @Override
  public String description() {
    return "Analyzes score differences between Top 1 and Top 2 candidate analyses for correct vs incorrect ambiguous tokens.";
  }

  public static class StatsAccumulator {
    public int count = 0;
    public List<Float> margins = new ArrayList<>();
    public List<Float> topScores = new ArrayList<>();
    public List<Float> secondScores = new ArrayList<>();
    public List<Double> topProbs = new ArrayList<>();
    public List<Double> secondProbs = new ArrayList<>();

    public int bin_0_to_0_5 = 0;
    public int bin_0_5_to_1_0 = 0;
    public int bin_1_0_to_2_0 = 0;
    public int bin_2_0_to_5_0 = 0;
    public int bin_5_0_to_10_0 = 0;
    public int bin_10_plus = 0;

    public void add(float s1, float s2, double p1, double p2) {
      count++;
      float delta = s1 - s2;
      margins.add(delta);
      topScores.add(s1);
      secondScores.add(s2);
      topProbs.add(p1);
      secondProbs.add(p2);

      if (delta <= 0.5f) {
        bin_0_to_0_5++;
      } else if (delta <= 1.0f) {
        bin_0_5_to_1_0++;
      } else if (delta <= 2.0f) {
        bin_1_0_to_2_0++;
      } else if (delta <= 5.0f) {
        bin_2_0_to_5_0++;
      } else if (delta <= 10.0f) {
        bin_5_0_to_10_0++;
      } else {
        bin_10_plus++;
      }
    }

    public double meanMargin() {
      if (margins.isEmpty()) return 0;
      double sum = 0;
      for (float m : margins) sum += m;
      return sum / margins.size();
    }

    public float medianMargin() {
      if (margins.isEmpty()) return 0;
      Collections.sort(margins);
      return margins.get(margins.size() / 2);
    }

    public float p25Margin() {
      if (margins.isEmpty()) return 0;
      Collections.sort(margins);
      return margins.get((int) (margins.size() * 0.25));
    }

    public float p75Margin() {
      if (margins.isEmpty()) return 0;
      Collections.sort(margins);
      return margins.get((int) (margins.size() * 0.75));
    }

    public double meanTopProb() {
      if (topProbs.isEmpty()) return 0;
      double sum = 0;
      for (double p : topProbs) sum += p;
      return sum / topProbs.size();
    }

    public double meanSecondProb() {
      if (secondProbs.isEmpty()) return 0;
      double sum = 0;
      for (double p : secondProbs) sum += p;
      return sum / secondProbs.size();
    }
  }

  @Override
  public void run() throws Exception {
    Log.info("Loading FastPerceptron model from: %s", modelPath);
    FastPerceptronAmbiguityResolver resolver = FastPerceptronAmbiguityResolver.fromModelFile(modelPath);
    TurkishMorphology morphology = TurkishMorphology.builder()
        .setLexicon(RootLexicon.getDefault())
        .setAmbiguityResolver(resolver)
        .build();

    FastDecoder decoder = resolver.getDecoder();
    WeightLookup model = resolver.getModel();

    StatsAccumulator allTokens = new StatsAccumulator();
    StatsAccumulator correctTokens = new StatsAccumulator();
    StatsAccumulator errorTokens = new StatsAccumulator();
    StatsAccumulator errorRank2Tokens = new StatsAccumulator();

    List<Path> testPaths = Arrays.asList(test1Path, test2Path, test3Path);
    for (Path p : testPaths) {
      if (!Files.exists(p)) continue;
      Log.info("Processing benchmark file: %s", p);
      processBenchmark(morphology, decoder, model, p, allTokens, correctTokens, errorTokens, errorRank2Tokens);
    }

    printAnalysisReport(allTokens, correctTokens, errorTokens, errorRank2Tokens);
  }

  private void processBenchmark(
      TurkishMorphology morphology,
      FastDecoder decoder,
      WeightLookup model,
      Path jsonlPath,
      StatsAccumulator allTokens,
      StatsAccumulator correctTokens,
      StatsAccumulator errorTokens,
      StatsAccumulator errorRank2Tokens) throws IOException {

    Gson gson = new Gson();
    try (BufferedReader reader = Files.newBufferedReader(jsonlPath, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty()) continue;
        SentenceRecord rec = gson.fromJson(line, SentenceRecord.class);
        if (rec == null || rec.text == null || rec.tokens == null) continue;

        List<WordAnalysis> waList = morphology.analyzeSentence(rec.text);
        FastDecodeResult bestResult = decoder.bestPath(waList);
        List<SingleAnalysis> bestParse = bestResult.bestParse;

        int limit = Math.min(rec.tokens.size(), waList.size());
        for (int i = 0; i < limit; i++) {
          TokenRecord t = rec.tokens.get(i);
          if (!t.is_ambiguous) continue;

          WordAnalysis wa = waList.get(i);
          List<SingleAnalysis> candidates = wa.getAnalysisResults();
          if (candidates.size() < 2) continue;

          int goldId = (t.selected_candidate_id != null) ? t.selected_candidate_id : 0;
          String goldKey = EvaluateAmbiguityModel.getCandidateKey(t, goldId);

          SingleAnalysis predSa = (i < bestParse.size()) ? bestParse.get(i) : null;
          int predId = EvaluateAmbiguityModel.matchAnalysisToCandidateId(t, predSa);
          String predKey = predSa != null ? predSa.formatLong() : "";

          boolean isMatch = EvaluateAmbiguityModel.isMatch(t, predId, goldId, predKey, goldKey);

          // Local score computation for all candidates in current sentence context
          SingleAnalysis w_i_minus_2 = (i >= 2) ? bestParse.get(i - 2) : FastPerceptronAmbiguityResolver.sentenceBegin;
          SingleAnalysis w_i_minus_1 = (i >= 1) ? bestParse.get(i - 1) : FastPerceptronAmbiguityResolver.sentenceBegin;
          SingleAnalysis w_i_plus_1 = (i + 1 < bestParse.size()) ? bestParse.get(i + 1) : FastPerceptronAmbiguityResolver.sentenceEnd;
          SingleAnalysis w_i_plus_2 = (i + 2 < bestParse.size()) ? bestParse.get(i + 2) : FastPerceptronAmbiguityResolver.sentenceEnd;

          CandidateContext cc_prevPrev = new CandidateContext(w_i_minus_2, model, (i - 2 == 0), 0);
          CandidateContext cc_prev = new CandidateContext(w_i_minus_1, model, (i - 1 == 0), 0);
          CandidateContext cc_next = new CandidateContext(w_i_plus_1, model, false, 0);
          CandidateContext cc_nextNext = new CandidateContext(w_i_plus_2, model, false, 0);

          List<Float> candScores = new ArrayList<>(candidates.size());
          int goldCandIdx = -1;
          for (int cIdx = 0; cIdx < candidates.size(); cIdx++) {
            SingleAnalysis cSa = candidates.get(cIdx);
            CandidateContext cc_curr = new CandidateContext(cSa, model, (i == 0), 0);
            float score = cc_curr.uniScore;
            score += decoder.computeBigramScore(cc_prev, cc_curr);
            score += decoder.computeTrigramScore(cc_prevPrev, cc_prev, cc_curr);
            score += decoder.computeBigramScore(cc_curr, cc_next);
            score += decoder.computeTrigramScore(cc_prev, cc_curr, cc_next);
            score += decoder.computeTrigramScore(cc_curr, cc_next, cc_nextNext);
            candScores.add(score);

            int cId = EvaluateAmbiguityModel.matchAnalysisToCandidateId(t, cSa);
            if (goldCandIdx == -1 && EvaluateAmbiguityModel.isMatch(t, cId, goldId, cSa.formatLong(), goldKey)) {
              goldCandIdx = cIdx;
            }
          }

          // Sort scores descending
          List<Float> sortedScores = new ArrayList<>(candScores);
          sortedScores.sort(Collections.reverseOrder());

          float s1 = sortedScores.get(0);
          float s2 = sortedScores.get(1);

          // Softmax probabilities for top 2
          double sumExp = 0.0;
          for (float s : sortedScores) {
            sumExp += Math.exp(s - s1);
          }
          double p1 = Math.exp(s1 - s1) / sumExp;
          double p2 = Math.exp(s2 - s1) / sumExp;

          allTokens.add(s1, s2, p1, p2);
          if (isMatch) {
            correctTokens.add(s1, s2, p1, p2);
          } else {
            errorTokens.add(s1, s2, p1, p2);
            // Check if gold was at rank 2
            if (goldCandIdx != -1) {
              float gScore = candScores.get(goldCandIdx);
              if (Math.abs(gScore - s2) < 1e-4) {
                errorRank2Tokens.add(s1, s2, p1, p2);
              }
            }
          }
        }
      }
    }
  }

  private static void printAnalysisReport(
      StatsAccumulator all,
      StatsAccumulator correct,
      StatsAccumulator error,
      StatsAccumulator errorRank2) {

    System.out.println("=========================================================================================");
    System.out.println("TOP-1 vs TOP-2 SCORE DIFFERENCE (MARGIN) ANALYSIS");
    System.out.println("=========================================================================================");
    System.out.printf("Total Ambiguous Tokens Analyzed: %,d\n", all.count);
    System.out.printf("  - Correct Tokens (Rank 1 = Gold): %,d (%.2f%%)\n", correct.count, (double) correct.count / all.count * 100);
    System.out.printf("  - Error Tokens   (Rank 1 != Gold): %,d (%.2f%%)\n", error.count, (double) error.count / all.count * 100);
    System.out.printf("    * Of which Gold is Rank 2:      %,d (%.2f%% of errors)\n", errorRank2.count, (double) errorRank2.count / error.count * 100);
    System.out.println("-----------------------------------------------------------------------------------------");
    System.out.println("1. SUMMARY METRICS COMPARISON (Delta = Score_1 - Score_2):");
    System.out.println("-----------------------------------------------------------------------------------------");
    System.out.printf("%-35s | %-12s | %-12s | %-12s\n", "Metric", "All Tokens", "Correct Tokens", "Error Tokens");
    System.out.println("-----------------------------------------------------------------------------------------");
    System.out.printf("%-35s | %-12.3f | %-12.3f | %-12.3f\n", "Mean Score Margin (Delta)", all.meanMargin(), correct.meanMargin(), error.meanMargin());
    System.out.printf("%-35s | %-12.3f | %-12.3f | %-12.3f\n", "Median Score Margin (Delta)", all.medianMargin(), correct.medianMargin(), error.medianMargin());
    System.out.printf("%-35s | %-12.3f | %-12.3f | %-12.3f\n", "25th Percentile Margin", all.p25Margin(), correct.p25Margin(), error.p25Margin());
    System.out.printf("%-35s | %-12.3f | %-12.3f | %-12.3f\n", "75th Percentile Margin", all.p75Margin(), correct.p75Margin(), error.p75Margin());
    System.out.printf("%-35s | %-12.1f%% | %-12.1f%% | %-12.1f%%\n", "Mean Top-1 Softmax Prob P(1)", all.meanTopProb() * 100, correct.meanTopProb() * 100, error.meanTopProb() * 100);
    System.out.printf("%-35s | %-12.1f%% | %-12.1f%% | %-12.1f%%\n", "Mean Top-2 Softmax Prob P(2)", all.meanSecondProb() * 100, correct.meanSecondProb() * 100, error.meanSecondProb() * 100);
    System.out.println("-----------------------------------------------------------------------------------------");
    System.out.println("2. SCORE MARGIN (Delta = S1 - S2) DISTRIBUTION BREAKDOWN:");
    System.out.println("-----------------------------------------------------------------------------------------");
    printBinRow("Razor-thin (0.0 to 0.5]", all.bin_0_to_0_5, all.count, correct.bin_0_to_0_5, correct.count, error.bin_0_to_0_5, error.count);
    printBinRow("Very close (0.5 to 1.0]", all.bin_0_5_to_1_0, all.count, correct.bin_0_5_to_1_0, correct.count, error.bin_0_5_to_1_0, error.count);
    printBinRow("Moderate   (1.0 to 2.0]", all.bin_1_0_to_2_0, all.count, correct.bin_1_0_to_2_0, correct.count, error.bin_1_0_to_2_0, error.count);
    printBinRow("Clear Lead (2.0 to 5.0]", all.bin_2_0_to_5_0, all.count, correct.bin_2_0_to_5_0, correct.count, error.bin_2_0_to_5_0, error.count);
    printBinRow("Decisive   (5.0 to 10.0]", all.bin_5_0_to_10_0, all.count, correct.bin_5_0_to_10_0, correct.count, error.bin_5_0_to_10_0, error.count);
    printBinRow("Extreme    (> 10.0)", all.bin_10_plus, all.count, correct.bin_10_plus, correct.count, error.bin_10_plus, error.count);
    System.out.println("=========================================================================================");
  }

  private static void printBinRow(String label, int allC, int allTot, int corC, int corTot, int errC, int errTot) {
    System.out.printf("%-25s | %6d (%5.1f%%) | %6d (%5.1f%%) | %6d (%5.1f%%)\n",
        label,
        allC, (double) allC / allTot * 100,
        corC, (double) corC / corTot * 100,
        errC, (double) errC / errTot * 100);
  }
}
