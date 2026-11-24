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
import zemberek.core.collections.FloatValueMap;
import zemberek.core.collections.IntValueMap;
import zemberek.core.logging.Log;
import zemberek.core.turkish.PrimaryPos;
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.ambiguity.dataset.HarvestRerankerDataset.RerankerInstance;
import zemberek.morphology.ambiguity.fast.RerankerFeatureExtractor;
import zemberek.morphology.ambiguity.fast.RerankerModel;
import zemberek.morphology.analysis.SingleAnalysis;
import zemberek.morphology.analysis.WordAnalysis;
import zemberek.morphology.lexicon.RootLexicon;

/**
 * Trains a Binary Averaged Perceptron Re-ranker on contrastive difference vectors:
 *   x = phi(c2) - phi(c1)
 * Label y in {+1 (FLIP to c2), -1 (KEEP c1)}.
 * Optimizes margin loss: if y * (w . x) <= margin, updates weights.
 */
public class TrainRerankerPerceptron extends ConsoleApp {

  @Parameter(
      names = {"--trainFile", "-t"},
      description = "Path to harvested training pairs JSONL")
  public Path trainFile = Paths.get("data/ambiguity/reranker_pairs_train.jsonl");

  @Parameter(
      names = {"--valFile", "-v"},
      description = "Path to harvested validation pairs JSONL")
  public Path valFile = Paths.get("data/ambiguity/reranker_pairs_val.jsonl");

  @Parameter(
      names = {"--outputModel", "-o"},
      description = "Path to save serialized binary reranker model")
  public Path outputModel = Paths.get("data/ambiguity/reranker-model.bin");

  @Parameter(
      names = {"--outputText", "-ot"},
      description = "Path to save readable text feature weights")
  public Path outputText = Paths.get("data/ambiguity/reranker-model.txt");

  @Parameter(
      names = {"--epochs", "-e"},
      description = "Number of training epochs")
  public int epochs = 10;

  @Parameter(
      names = {"--margin", "-m"},
      description = "Margin parameter gamma for perceptron loss")
  public float margin = 1.0f;

  @Parameter(
      names = {"--learningRate", "-lr"},
      description = "Perceptron learning rate eta")
  public float learningRate = 1.0f;

  @Parameter(
      names = {"--minPruneWeight", "-p"},
      description = "Pruning threshold for small weights")
  public float minPruneWeight = 0.02f;

  @Parameter(
      names = {"--maxDelta", "-d"},
      description = "Maximum Stage 1 score margin threshold for re-ranking")
  public float maxDelta = 3.0f;

  @Parameter(
      names = {"--theta", "-th"},
      description = "Default confidence gating threshold theta for overriding Stage 1")
  public float theta = 0.5f;

  public static void main(String[] args) {
    new TrainRerankerPerceptron().execute(args);
  }

  @Override
  public String description() {
    return "Trains a Second-Stage Binary Averaged Perceptron Re-ranker on contrastive candidate pairs.";
  }

  public static class TrainingSample {
    public FloatValueMap<String> diffFeatures;
    public int label; // +1 or -1
    public float delta;
    public String surface;
    public String c1Key;
    public String c2Key;
  }

  @Override
  public void run() throws Exception {
    Log.info("Initializing TurkishMorphology for feature reconstruction...");
    TurkishMorphology morphology = TurkishMorphology.builder()
        .setLexicon(RootLexicon.getDefault())
        .build();

    Log.info("Loading training instances from: %s", trainFile);
    List<TrainingSample> trainSamples = loadSamples(trainFile, morphology);
    Log.info("Loaded %d training samples.", trainSamples.size());

    Log.info("Loading validation instances from: %s", valFile);
    List<TrainingSample> valSamples = loadSamples(valFile, morphology);
    Log.info("Loaded %d validation samples.", valSamples.size());

    // Feature frequency check & vocab
    IntValueMap<String> featureDocFreq = new IntValueMap<>();
    for (TrainingSample s : trainSamples) {
      for (FloatValueMap.Entry<String> e : s.diffFeatures.iterableEntries()) {
        featureDocFreq.addOrIncrement(e.key);
      }
    }
    Log.info("Total distinct features in training: %d", featureDocFreq.size());

    // Perceptron Weight Vectors
    FloatValueMap<String> weights = new FloatValueMap<>();
    FloatValueMap<String> averagedWeights = new FloatValueMap<>();
    IntValueMap<String> lastUpdatedStep = new IntValueMap<>();

    int totalSteps = 0;
    Random rnd = new Random(0xCAFE);

    Log.info("Starting Averaged Perceptron training: %d epochs, margin=%.2f, lr=%.2f",
        epochs, margin, learningRate);

    float bestValAcc = 0.0f;
    FloatValueMap<String> bestAveragedWeights = new FloatValueMap<>();

    for (int epoch = 1; epoch <= epochs; epoch++) {
      // Shuffle training samples
      Collections.shuffle(trainSamples, rnd);

      int updates = 0;
      int marginViolations = 0;
      int zeroOneErrors = 0;

      for (TrainingSample sample : trainSamples) {
        totalSteps++;

        // Compute current dot product: w . x
        float score = 0.0f;
        for (FloatValueMap.Entry<String> e : sample.diffFeatures.iterableEntries()) {
          score += weights.get(e.key) * e.value;
        }

        // Loss check: y * (w . x) <= margin
        float functionalMargin = sample.label * score;
        if (functionalMargin <= 0.0f) {
          zeroOneErrors++;
        }

        if (functionalMargin <= margin) {
          marginViolations++;
          updates++;

          // Update weights
          for (FloatValueMap.Entry<String> e : sample.diffFeatures.iterableEntries()) {
            String feat = e.key;
            float featVal = e.value;

            // Lazy update running average
            int prevStep = lastUpdatedStep.get(feat);
            float curWeight = weights.get(feat);
            float curAvg = averagedWeights.get(feat);

            // Accumulate weight across steps where it stayed constant
            float updatedAvg = curAvg + (totalSteps - prevStep - 1) * curWeight;
            // Now add the new weight
            float newWeight = curWeight + (sample.label * featVal * learningRate);
            updatedAvg += newWeight;

            weights.set(feat, newWeight);
            averagedWeights.set(feat, updatedAvg);
            lastUpdatedStep.put(feat, totalSteps);
          }
        }
      }

      // Evaluate epoch snapshot on validation set
      // Finalize temporary averaged weights for evaluation
      FloatValueMap<String> epochAvgWeights = new FloatValueMap<>(weights.size());
      for (FloatValueMap.Entry<String> e : weights.iterableEntries()) {
        String feat = e.key;
        int prevStep = lastUpdatedStep.get(feat);
        float curWeight = e.value;
        float curAvg = averagedWeights.get(feat);
        float finalAvg = curAvg + (totalSteps - prevStep) * curWeight;
        epochAvgWeights.set(feat, finalAvg / totalSteps);
      }

      // Evaluate on val
      ValMetrics vm = evaluate(valSamples, epochAvgWeights, theta);
      Log.info("Epoch %2d/%2d - Updates: %4d, MarginViolations: %4d (%.1f%%), 0-1 Errs: %4d (%.1f%%) | Val Acc: %.2f%%, Flips: %d (Fix: %d, Reg: %d, Prec: %.1f%%, Net: %+d)",
          epoch, epochs,
          updates,
          marginViolations, (100.0 * marginViolations / trainSamples.size()),
          zeroOneErrors, (100.0 * zeroOneErrors / trainSamples.size()),
          vm.accuracy * 100.0,
          vm.totalFlips, vm.fixes, vm.regressions, vm.flipPrecision * 100.0, vm.netGain);

      if (vm.accuracy > bestValAcc) {
        bestValAcc = vm.accuracy;
        bestAveragedWeights = new FloatValueMap<>(epochAvgWeights.size());
        for (FloatValueMap.Entry<String> e : epochAvgWeights.iterableEntries()) {
          bestAveragedWeights.set(e.key, e.value);
        }
      }
    }

    Log.info("Training complete. Best Validation Accuracy: %.2f%%", bestValAcc * 100.0);

    // Prune small weights
    int beforePrune = bestAveragedWeights.size();
    FloatValueMap<String> prunedWeights = new FloatValueMap<>();
    for (FloatValueMap.Entry<String> e : bestAveragedWeights.iterableEntries()) {
      if (Math.abs(e.value) > minPruneWeight) {
        prunedWeights.set(e.key, e.value);
      }
    }
    Log.info("Pruned weights: %d -> %d (kept %.1f%% with |w| > %.3f)",
        beforePrune, prunedWeights.size(), (100.0 * prunedWeights.size() / beforePrune), minPruneWeight);

    // Validate pruned model
    ValMetrics finalVm = evaluate(valSamples, prunedWeights, theta);
    Log.info("Pruned Model Val Acc: %.2f%%, Flips: %d (Fix: %d, Reg: %d, Prec: %.1f%%, Net: %+d)",
        finalVm.accuracy * 100.0,
        finalVm.totalFlips, finalVm.fixes, finalVm.regressions, finalVm.flipPrecision * 100.0, finalVm.netGain);

    // Save Model
    RerankerModel model = new RerankerModel(prunedWeights, maxDelta, theta);
    if (outputModel.getParent() != null) {
      Files.createDirectories(outputModel.getParent());
    }
    model.saveToFile(outputModel);
    Log.info("Saved binary reranker model to: %s (%d bytes)",
        outputModel, Files.size(outputModel));

    if (outputText != null) {
      model.saveToTextFile(outputText);
      Log.info("Saved text feature weights to: %s", outputText);
    }
  }

  private static class ValMetrics {
    public float accuracy;
    public int totalFlips;
    public int fixes;
    public int regressions;
    public float flipPrecision;
    public int netGain;
  }

  private static ValMetrics evaluate(List<TrainingSample> samples, FloatValueMap<String> w, float threshold) {
    int correct = 0;
    int fixes = 0;
    int regressions = 0;
    int flips = 0;

    for (TrainingSample s : samples) {
      float score = 0.0f;
      for (FloatValueMap.Entry<String> e : s.diffFeatures.iterableEntries()) {
        score += w.get(e.key) * e.value;
      }

      int predSign = score > 0.0f ? 1 : -1;
      if (predSign == s.label) {
        correct++;
      }

      // Gated override decision: flip to c2 if score > threshold
      if (score > threshold) {
        flips++;
        if (s.label == 1) {
          fixes++;
        } else {
          regressions++;
        }
      }
    }

    ValMetrics vm = new ValMetrics();
    vm.accuracy = (float) correct / samples.size();
    vm.totalFlips = flips;
    vm.fixes = fixes;
    vm.regressions = regressions;
    vm.flipPrecision = flips > 0 ? (float) fixes / flips : 0.0f;
    vm.netGain = fixes - regressions;
    return vm;
  }

  private List<TrainingSample> loadSamples(Path path, TurkishMorphology morphology) throws IOException {
    List<TrainingSample> samples = new ArrayList<>();
    Gson gson = new Gson();
    Map<String, List<WordAnalysis>> sentenceCache = new HashMap<>();

    int totalLines = 0;
    int matchedCount = 0;

    try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty()) continue;
        totalLines++;

        RerankerInstance inst = gson.fromJson(line, RerankerInstance.class);
        if (inst == null || inst.text == null || inst.c1_key == null || inst.c2_key == null) {
          continue;
        }

        List<WordAnalysis> waList = sentenceCache.computeIfAbsent(inst.text, morphology::analyzeSentence);
        if (inst.token_index >= waList.size()) continue;

        // Reconstruct bestParse
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

        // Find c1 and c2
        SingleAnalysis c1 = null;
        SingleAnalysis c2 = null;
        for (SingleAnalysis sa : waList.get(inst.token_index).getAnalysisResults()) {
          if (sa.formatLong().equals(inst.c1_key)) {
            c1 = sa;
          }
          if (sa.formatLong().equals(inst.c2_key)) {
            c2 = sa;
          }
        }

        if (c1 != null && c2 != null) {
          matchedCount++;
          FloatValueMap<String> diff = RerankerFeatureExtractor.extractDifferenceVector(
              c1, c2, inst.token_index, bestParse, inst.delta);

          TrainingSample s = new TrainingSample();
          s.diffFeatures = diff;
          s.label = inst.label;
          s.delta = inst.delta;
          s.surface = inst.surface;
          s.c1Key = inst.c1_key;
          s.c2Key = inst.c2_key;
          samples.add(s);
        }
      }
    }
    Log.info("From %s: loaded %d instances, successfully matched %d (%.1f%%).",
        path.getFileName(), totalLines, matchedCount, (100.0 * matchedCount / Math.max(1, totalLines)));
    return samples;
  }
}
