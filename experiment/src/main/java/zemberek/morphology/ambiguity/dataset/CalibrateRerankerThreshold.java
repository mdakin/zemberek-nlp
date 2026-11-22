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
import zemberek.core.collections.FloatValueMap;
import zemberek.core.logging.Log;
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.ambiguity.dataset.HarvestRerankerDataset.RerankerInstance;
import zemberek.morphology.ambiguity.fast.RerankerFeatureExtractor;
import zemberek.morphology.ambiguity.fast.RerankerModel;
import zemberek.morphology.analysis.SingleAnalysis;
import zemberek.morphology.analysis.WordAnalysis;
import zemberek.morphology.lexicon.RootLexicon;

/**
 * Sweeps confidence override threshold theta and delta slices on the validation set
 * to determine the optimal operating point maximizing net gain with precision >= 70%.
 */
public class CalibrateRerankerThreshold extends ConsoleApp {

  @Parameter(
      names = {"--model", "-m"},
      description = "Path to trained RerankerModel (.bin)")
  public Path modelPath = Paths.get("data/ambiguity/reranker-model.bin");

  @Parameter(
      names = {"--valFile", "-v"},
      description = "Path to validation pairs JSONL")
  public Path valFile = Paths.get("data/ambiguity/reranker_pairs_val.jsonl");

  @Parameter(
      names = {"--outputReport", "-o"},
      description = "Path to output JSON calibration report")
  public Path outputReport = Paths.get("data/ambiguity/reranker_calibration_report.json");

  public static void main(String[] args) {
    new CalibrateRerankerThreshold().execute(args);
  }

  @Override
  public String description() {
    return "Calibrates Reranker override threshold theta and delta slices on validation data.";
  }

  public static class CalibPoint {
    public float theta;
    public float maxDelta;
    public int totalEvaluated;
    public int flips;
    public int fixes;
    public int regressions;
    public float precision;
    public int netGain;
  }

  public static class EvalSample {
    public float score;
    public float delta;
    public int label; // +1 if FLIP is correct, -1 if KEEP is correct
    public String surface;
    public String c1Key;
    public String c2Key;
  }

  @Override
  public void run() throws Exception {
    Log.info("Loading RerankerModel from: %s", modelPath);
    RerankerModel model = RerankerModel.loadFromFile(modelPath);
    Log.info("Model loaded. Features: %d, Default maxDelta: %.2f, Default theta: %.2f",
        model.featureCount(), model.getMaxDelta(), model.getTheta());

    Log.info("Initializing TurkishMorphology...");
    TurkishMorphology morphology = TurkishMorphology.builder()
        .setLexicon(RootLexicon.getDefault())
        .build();

    Log.info("Loading validation instances from: %s", valFile);
    List<EvalSample> samples = new ArrayList<>();
    Gson gson = new Gson();
    Map<String, List<WordAnalysis>> sentenceCache = new HashMap<>();

    try (BufferedReader reader = Files.newBufferedReader(valFile, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty()) continue;
        RerankerInstance inst = gson.fromJson(line, RerankerInstance.class);
        if (inst == null || inst.text == null || inst.c1_key == null || inst.c2_key == null) {
          continue;
        }

        List<WordAnalysis> waList = sentenceCache.computeIfAbsent(inst.text, morphology::analyzeSentence);
        if (inst.token_index >= waList.size()) continue;

        List<SingleAnalysis> bestParse = new ArrayList<>();
        if (inst.sentence_best_parses != null) {
          for (int j = 0; j < inst.sentence_best_parses.size(); j++) {
            String targetKey = inst.sentence_best_parses.get(j);
            SingleAnalysis match = null;
            if (j < waList.size()) {
              for (SingleAnalysis sa : waList.get(j).getAnalysisResults()) {
                if (sa.formatLong().equals(targetKey)) {
                  match = sa;
                  break;
                }
              }
            }
            if (match == null) {
              match = (j < waList.size() && !waList.get(j).getAnalysisResults().isEmpty())
                  ? waList.get(j).getAnalysisResults().get(0)
                  : SingleAnalysis.unknown(targetKey);
            }
            bestParse.add(match);
          }
        }

        SingleAnalysis c1 = null;
        SingleAnalysis c2 = null;
        for (SingleAnalysis sa : waList.get(inst.token_index).getAnalysisResults()) {
          if (sa.formatLong().equals(inst.c1_key)) c1 = sa;
          if (sa.formatLong().equals(inst.c2_key)) c2 = sa;
        }

        if (c1 != null && c2 != null) {
          FloatValueMap<String> diff = RerankerFeatureExtractor.extractDifferenceVector(
              c1, c2, inst.token_index, bestParse, inst.delta);
          float s = model.score(diff);

          EvalSample es = new EvalSample();
          es.score = s;
          es.delta = inst.delta;
          es.label = inst.label;
          es.surface = inst.surface;
          es.c1Key = inst.c1_key;
          es.c2Key = inst.c2_key;
          samples.add(es);
        }
      }
    }
    Log.info("Pre-scored %d validation samples.", samples.size());

    float[] thetaList = new float[]{0.0f, 0.25f, 0.50f, 0.75f, 1.00f, 1.25f, 1.50f, 1.75f, 2.00f, 2.50f, 3.00f};
    float[] maxDeltaList = new float[]{0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f};

    List<CalibPoint> results = new ArrayList<>();

    System.out.println("\n==========================================================================================");
    System.out.println("                         RERANKER THRESHOLD CALIBRATION SWEEP                            ");
    System.out.println("==========================================================================================");
    System.out.printf("%-10s | %-10s | %-8s | %-8s | %-8s | %-8s | %-10s | %-8s\n",
        "MaxDelta", "Theta", "Total", "Flips", "Fixes", "Regr", "Precision", "NetGain");
    System.out.println("------------------------------------------------------------------------------------------");

    CalibPoint bestPoint = null;

    for (float maxD : maxDeltaList) {
      for (float th : thetaList) {
        int total = 0;
        int flips = 0;
        int fixes = 0;
        int regr = 0;

        for (EvalSample s : samples) {
          if (s.delta <= maxD) {
            total++;
            if (s.score > th) {
              flips++;
              if (s.label == 1) {
                fixes++;
              } else {
                regr++;
              }
            }
          }
        }

        float prec = flips > 0 ? (float) fixes / flips : 0.0f;
        int net = fixes - regr;

        CalibPoint cp = new CalibPoint();
        cp.maxDelta = maxD;
        cp.theta = th;
        cp.totalEvaluated = total;
        cp.flips = flips;
        cp.fixes = fixes;
        cp.regressions = regr;
        cp.precision = prec;
        cp.netGain = net;
        results.add(cp);

        System.out.printf("delta <= %-3.1f | theta = %-4.2f | %-8d | %-8d | %-8d | %-8d | %8.2f%% | %+d\n",
            maxD, th, total, flips, fixes, regr, prec * 100.0, net);

        if (prec >= 0.70f && (bestPoint == null || net > bestPoint.netGain)) {
          bestPoint = cp;
        }
      }
      System.out.println("------------------------------------------------------------------------------------------");
    }

    if (bestPoint != null) {
      System.out.println("\n>>> RECOMMENDED OPERATING POINT <<<");
      System.out.printf("MaxDelta: %.2f | Theta: %.2f | Flips: %d | Fixes: %d | Regr: %d | Precision: %.2f%% | Net Gain: %+d\n\n",
          bestPoint.maxDelta, bestPoint.theta, bestPoint.flips, bestPoint.fixes, bestPoint.regressions,
          bestPoint.precision * 100.0, bestPoint.netGain);
    }

    if (outputReport != null) {
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("modelPath", modelPath.toString());
      report.put("valSamplesCount", samples.size());
      report.put("bestPoint", bestPoint);
      report.put("grid", results);

      if (outputReport.getParent() != null) {
        Files.createDirectories(outputReport.getParent());
      }
      try (BufferedWriter writer = Files.newBufferedWriter(outputReport, StandardCharsets.UTF_8)) {
        new GsonBuilder().setPrettyPrinting().create().toJson(report, writer);
      }
      Log.info("Saved calibration report to: %s", outputReport);
    }
  }
}
