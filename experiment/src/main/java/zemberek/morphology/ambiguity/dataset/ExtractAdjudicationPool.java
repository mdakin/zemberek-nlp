package zemberek.morphology.ambiguity.dataset;

import com.beust.jcommander.Parameter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import zemberek.apps.ConsoleApp;
import zemberek.core.logging.Log;
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.ambiguity.AmbiguityResolver;
import zemberek.morphology.ambiguity.fast.FastPerceptronAmbiguityResolver;
import zemberek.morphology.analysis.SentenceAnalysis;
import zemberek.morphology.analysis.SingleAnalysis;
import zemberek.morphology.lexicon.RootLexicon;

/**
 * Scans the training corpus and extracts high-uncertainty and disputed sentences for
 * targeted Chain-of-Thought LLM adjudication.
 */
public class ExtractAdjudicationPool extends ConsoleApp {

  @Parameter(
      names = {"--input", "-i"},
      required = true,
      description = "Input training JSONL file (e.g. train_combined_v2.jsonl)")
  public Path inputPath;

  @Parameter(
      names = {"--model", "-m"},
      required = true,
      description = "Path to baseline binary model (e.g. model-distilled-v2.bin)")
  public Path modelPath;

  @Parameter(
      names = {"--output", "-o"},
      required = true,
      description = "Output JSONL file for adjudication pool")
  public Path outputPath;

  @Parameter(
      names = {"--maxSentences", "-max"},
      description = "Maximum number of sentences to extract (default 2000)")
  public int maxSentences = 2000;

  public static void main(String[] args) {
    new ExtractAdjudicationPool().execute(args);
  }

  @Override
  public String description() {
    return "Extracts disputed and high-uncertainty sentences for Phase 3 CoT adjudication.";
  }

  @Override
  public void run() throws Exception {
    Log.info("Loading baseline model from: %s", modelPath);
    AmbiguityResolver resolver = FastPerceptronAmbiguityResolver.fromModelFile(modelPath);

    TurkishMorphology morphology = TurkishMorphology.builder()
        .setLexicon(RootLexicon.getDefault())
        .setAmbiguityResolver(resolver)
        .build();

    Log.info("Scanning sentences from: %s", inputPath);
    Gson gson = new Gson();

    int totalSentences = 0;
    int disagreementCount = 0;
    int highStakesCount = 0;
    int narrowMarginCount = 0;

    List<DisputedSentence> pool = new ArrayList<>();

    try (BufferedReader reader = Files.newBufferedReader(inputPath, StandardCharsets.UTF_8)) {
      String line;
      int lineIdx = 0;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty()) {
          continue;
        }
        totalSentences++;
        lineIdx++;

        PoolSentenceRecord rec = gson.fromJson(line, PoolSentenceRecord.class);
        if (rec == null || rec.text == null || rec.tokens == null) {
          continue;
        }

        SentenceAnalysis sa = morphology.analyzeAndDisambiguate(rec.text);
        List<SingleAnalysis> bestParses = sa.bestAnalysis();

        boolean hasDisagreement = false;
        boolean hasHighStakes = false;
        boolean hasNarrowMargin = false;
        double priorityScore = 0.0;
        List<String> reasons = new ArrayList<>();

        for (int i = 0; i < rec.tokens.size(); i++) {
          PoolTokenRecord t = rec.tokens.get(i);
          if (!t.is_ambiguous || t.candidates == null || t.candidates.size() < 2) {
            continue;
          }

          int goldId = extractSelectedId(t);
          SingleAnalysis predictedSa = (i < bestParses.size()) ? bestParses.get(i) : null;
          int predId = matchAnalysisToCandidateId(t, predictedSa);

          boolean isDisagreement = (predId != -1 && predId != goldId);
          if (isDisagreement) {
            hasDisagreement = true;
            priorityScore += 10.0;
            reasons.add(String.format("Disagreement on '%s': Perceptron=%d vs TrainLabel=%d", t.surface, predId, goldId));
          }

          // Check high-stakes ambiguity classes
          boolean hasAcc = false;
          boolean hasP3 = false;
          boolean hasAor = false;
          boolean hasNoun = false;
          boolean hasInf2 = false;
          boolean hasNegImp = false;

          for (PoolCandidateRecord c : t.candidates) {
            String key = c.zemberek_key != null ? c.zemberek_key : "";
            if (key.contains("+Acc") || key.contains(":Acc")) hasAcc = true;
            if (key.contains("+P3sg") || key.contains("+P3pl") || key.contains(":P3sg") || key.contains(":P3pl")) hasP3 = true;
            if (key.contains("+Aor") || key.contains(":Aor")) hasAor = true;
            if (c.pos != null && c.pos.equals("Noun")) hasNoun = true;
            if (key.contains("+Inf2") || key.contains(":Inf2")) hasInf2 = true;
            if ((key.contains("+Neg") || key.contains(":Neg")) && (key.contains("+Imp") || key.contains(":Imp"))) hasNegImp = true;
          }

          boolean isAccVsP3 = hasAcc && hasP3;
          boolean isAorVsNoun = hasAor && hasNoun;
          boolean isInf2VsNegImp = hasInf2 && hasNegImp;

          if (isAccVsP3 || isAorVsNoun || isInf2VsNegImp) {
            hasHighStakes = true;
            priorityScore += 5.0;
            if (isAccVsP3) reasons.add(String.format("AccVsP3 on '%s'", t.surface));
            if (isAorVsNoun) reasons.add(String.format("AorVsNoun on '%s'", t.surface));
            if (isInf2VsNegImp) reasons.add(String.format("Inf2VsNegImp on '%s'", t.surface));
          }

          // Check margin
          if (t.margin != null && t.margin >= 0.0 && t.margin < 0.20) {
            hasNarrowMargin = true;
            priorityScore += 3.0;
            reasons.add(String.format("NarrowMargin (%.3f) on '%s'", t.margin, t.surface));
          }
        }

        if (hasDisagreement) disagreementCount++;
        if (hasHighStakes) highStakesCount++;
        if (hasNarrowMargin) narrowMarginCount++;

        // Select if there is disagreement OR (high stakes AND narrow margin)
        if (hasDisagreement || (hasHighStakes && hasNarrowMargin)) {
          DisputedSentence ds = new DisputedSentence();
          ds.lineIndex = lineIdx - 1; // 0-indexed in file
          ds.sentenceId = rec.sentence_id;
          ds.text = rec.text;
          ds.priorityScore = priorityScore;
          ds.reasons = reasons;
          ds.rawJson = line;
          pool.add(ds);
        }

        if (totalSentences % 5000 == 0) {
          Log.info("Processed %d sentences... Disagreements so far: %d, High-stakes: %d, Qualified pool: %d",
              totalSentences, disagreementCount, highStakesCount, pool.size());
        }
      }
    }

    Log.info("Scan complete!");
    Log.info("Total sentences: %d", totalSentences);
    Log.info("Sentences with Perceptron Disagreement: %d", disagreementCount);
    Log.info("Sentences with High-Stakes Ambiguities:  %d", highStakesCount);
    Log.info("Sentences with Narrow Margin (<0.2):    %d", narrowMarginCount);
    Log.info("Total candidates qualified for pool:     %d", pool.size());

    // Sort by priority score descending
    pool.sort((a, b) -> Double.compare(b.priorityScore, a.priorityScore));

    int selectedCount = Math.min(maxSentences, pool.size());
    List<DisputedSentence> selected = pool.subList(0, selectedCount);

    // Keep natural file order for deterministic reproducibility and easy merging
    selected.sort((a, b) -> Integer.compare(a.lineIndex, b.lineIndex));

    if (outputPath.getParent() != null) {
      Files.createDirectories(outputPath.getParent());
    }

    try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
      for (DisputedSentence ds : selected) {
        writer.write(ds.rawJson);
        writer.newLine();
      }
    }

    // Also write an index/meta file so we know exactly which line indices were extracted
    Path metaPath = outputPath.resolveSibling(outputPath.getFileName().toString() + ".meta.json");
    Gson prettyGson = new GsonBuilder().setPrettyPrinting().create();
    try (BufferedWriter writer = Files.newBufferedWriter(metaPath, StandardCharsets.UTF_8)) {
      prettyGson.toJson(selected, writer);
    }

    Log.info("Wrote %d selected sentences to: %s", selected.size(), outputPath);
    Log.info("Wrote metadata and line indices to: %s", metaPath);
  }

  private static int extractSelectedId(PoolTokenRecord t) {
    if (t.selected_candidate_id != null) {
      return t.selected_candidate_id;
    }
    if (t.candidates != null) {
      for (PoolCandidateRecord c : t.candidates) {
        if (c.selected != null && c.selected) {
          return c.id;
        }
      }
    }
    return 0;
  }

  private static int matchAnalysisToCandidateId(PoolTokenRecord t, SingleAnalysis sa) {
    if (t.candidates == null || sa == null) {
      return -1;
    }
    String saKey = sa.formatLong();
    for (PoolCandidateRecord c : t.candidates) {
      if (saKey.equals(c.zemberek_key)) {
        return c.id;
      }
    }
    return -1;
  }

  public static class PoolSentenceRecord {
    public String sentence_id;
    public String text;
    public Double min_margin;
    public List<PoolTokenRecord> tokens = new ArrayList<>();
  }

  public static class PoolTokenRecord {
    public int index;
    public String surface;
    public boolean is_ambiguous;
    public Double margin;
    public Integer selected_candidate_id;
    public List<PoolCandidateRecord> candidates = new ArrayList<>();
  }

  public static class PoolCandidateRecord {
    public int id;
    public String zemberek_key;
    public String lemma;
    public String pos;
    public String secondary_pos;
    public String oflazer_style;
    public Double score;
    public Boolean selected;
  }

  public static class DisputedSentence {
    public int lineIndex;
    public String sentenceId;
    public String text;
    public double priorityScore;
    public List<String> reasons;
    public transient String rawJson;
  }
}

