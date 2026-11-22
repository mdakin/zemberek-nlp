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

public class HarvestRerankerDataset extends ConsoleApp {

  @Parameter(
      names = {"--model", "-m"},
      required = true,
      description = "Path to primary Stage 1 model (.bin)")
  public Path modelPath;

  @Parameter(
      names = {"--input", "-i"},
      required = true,
      description = "Path to ground truth annotated JSONL dataset (e.g. stage5_annotated_canonical_8k.jsonl)")
  public Path inputPath;

  @Parameter(
      names = {"--outputTrain", "-ot"},
      description = "Path to save harvested training pairs JSONL")
  public Path outputTrainPath = Paths.get("data/ambiguity/reranker_pairs_train.jsonl");

  @Parameter(
      names = {"--outputVal", "-ov"},
      description = "Path to save harvested validation pairs JSONL")
  public Path outputValPath = Paths.get("data/ambiguity/reranker_pairs_val.jsonl");

  @Parameter(
      names = {"--maxDelta", "-d"},
      description = "Maximum score margin (S1 - S2) to harvest contrastive pair")
  public float maxDelta = 3.0f;

  @Parameter(
      names = {"--valRatio", "-vr"},
      description = "Ratio of sentences reserved for validation")
  public double valRatio = 0.15;

  public static void main(String[] args) {
    new HarvestRerankerDataset().execute(args);
  }

  @Override
  public String description() {
    return "Harvests contrastive candidate pairs (c1, c2, label, context) where Stage 1 margin <= maxDelta.";
  }

  public static class RerankerInstance {
    public String sentence_id;
    public String text;
    public int token_index;
    public String surface;
    public float delta;
    public float s1;
    public float s2;
    public String c1_key;
    public String c1_pos;
    public String c2_key;
    public String c2_pos;
    public String gold_key;
    public int label; // +1 if c2 is gold (FLIP), -1 if c1 is gold (KEEP)
    public List<String> sentence_best_parses;
    public List<String> all_candidate_keys;
  }

  public static class ScoredCand {
    public SingleAnalysis analysis;
    public float score;
    public int id;
    public String key;
    public String pos;
  }

  @Override
  public void run() throws Exception {
    Log.info("Loading Stage 1 FastPerceptron model from: %s", modelPath);
    FastPerceptronAmbiguityResolver resolver = FastPerceptronAmbiguityResolver.fromModelFile(modelPath);
    TurkishMorphology morphology = TurkishMorphology.builder()
        .setLexicon(RootLexicon.getDefault())
        .setAmbiguityResolver(resolver)
        .build();

    FastDecoder decoder = resolver.getDecoder();
    WeightLookup model = resolver.getModel();

    Log.info("Reading input sentences from: %s", inputPath);
    List<SentenceRecord> allRecords = new ArrayList<>();
    Gson gson = new Gson();
    try (BufferedReader reader = Files.newBufferedReader(inputPath, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty()) continue;
        SentenceRecord rec = gson.fromJson(line, SentenceRecord.class);
        if (rec != null && rec.text != null && rec.tokens != null) {
          allRecords.add(rec);
        }
      }
    }
    Log.info("Loaded %d sentences.", allRecords.size());

    // Deterministic train/val split based on sentence_id hash
    List<RerankerInstance> trainInstances = new ArrayList<>();
    List<RerankerInstance> valInstances = new ArrayList<>();

    int totalAmbiguousChecked = 0;
    int totalHarvested = 0;
    int totalFlips = 0;
    int totalKeeps = 0;
    int totalRank3PlusDropped = 0;

    for (SentenceRecord rec : allRecords) {
      boolean isVal = Math.abs(rec.sentence_id != null ? rec.sentence_id.hashCode() : rec.text.hashCode()) % 100 < (valRatio * 100);

      List<WordAnalysis> waList = morphology.analyzeSentence(rec.text);
      FastDecodeResult bestResult = decoder.bestPath(waList);
      List<SingleAnalysis> bestParse = bestResult.bestParse;

      List<String> bestParseKeys = new ArrayList<>(bestParse.size());
      for (SingleAnalysis sa : bestParse) {
        bestParseKeys.add(sa.formatLong());
      }

      int limit = Math.min(rec.tokens.size(), waList.size());
      for (int i = 0; i < limit; i++) {
        TokenRecord t = rec.tokens.get(i);
        if (!t.is_ambiguous) continue;
        totalAmbiguousChecked++;

        WordAnalysis wa = waList.get(i);
        List<SingleAnalysis> candidates = wa.getAnalysisResults();
        if (candidates.size() < 2) continue;

        int goldId = (t.selected_candidate_id != null) ? t.selected_candidate_id : 0;
        String goldKey = EvaluateAmbiguityModel.getCandidateKey(t, goldId);

        // Context analyses for w_i
        SingleAnalysis w_i_minus_2 = (i >= 2) ? bestParse.get(i - 2) : FastPerceptronAmbiguityResolver.sentenceBegin;
        SingleAnalysis w_i_minus_1 = (i >= 1) ? bestParse.get(i - 1) : FastPerceptronAmbiguityResolver.sentenceBegin;
        SingleAnalysis w_i_plus_1 = (i + 1 < bestParse.size()) ? bestParse.get(i + 1) : FastPerceptronAmbiguityResolver.sentenceEnd;
        SingleAnalysis w_i_plus_2 = (i + 2 < bestParse.size()) ? bestParse.get(i + 2) : ((i + 1 < bestParse.size()) ? FastPerceptronAmbiguityResolver.sentenceEnd : null);

        CandidateContext cc_prevPrev = new CandidateContext(w_i_minus_2, model, (i - 2 == 0), 0);
        CandidateContext cc_prev = new CandidateContext(w_i_minus_1, model, (i - 1 == 0), 0);
        CandidateContext cc_next = new CandidateContext(w_i_plus_1, model, false, 0);
        CandidateContext cc_nextNext = (w_i_plus_2 != null) ? new CandidateContext(w_i_plus_2, model, false, 0) : null;

        List<ScoredCand> scoredCands = new ArrayList<>(candidates.size());
        for (int cIdx = 0; cIdx < candidates.size(); cIdx++) {
          SingleAnalysis cSa = candidates.get(cIdx);
          CandidateContext cc_curr = new CandidateContext(cSa, model, (i == 0), 0);

          float score = cc_curr.uniScore;
          score += decoder.computeBigramScore(cc_prev, cc_curr);
          score += decoder.computeTrigramScore(cc_prevPrev, cc_prev, cc_curr);

          score += decoder.computeBigramScore(cc_curr, cc_next);
          String prefix15_next = "15:" + cc_prev.lastGroup + "-" + cc_curr.lastGroup + "-";
          for (String ig : cc_next.igs) {
            score += model.get(prefix15_next + ig);
          }

          if (cc_nextNext != null) {
            score += model.get(cc_curr.f2Prefix + cc_nextNext.rIg);
            score += cc_curr.c10cScore;
            String prefix15_nextNext = "15:" + cc_curr.lastGroup + "-" + cc_next.lastGroup + "-";
            for (String ig : cc_nextNext.igs) {
              score += model.get(prefix15_nextNext + ig);
            }
          }

          ScoredCand sc = new ScoredCand();
          sc.analysis = cSa;
          sc.score = score;
          sc.key = cSa.formatLong();
          sc.pos = (cSa.getDictionaryItem() != null && cSa.getDictionaryItem().primaryPos != null)
              ? cSa.getDictionaryItem().primaryPos.name()
              : "Unknown";
          sc.id = EvaluateAmbiguityModel.matchAnalysisToCandidateId(t, cSa);
          scoredCands.add(sc);
        }

        // Sort descending
        scoredCands.sort((a, b) -> Float.compare(b.score, a.score));

        ScoredCand c1 = scoredCands.get(0);
        ScoredCand c2 = null;
        for (int k = 1; k < scoredCands.size(); k++) {
          if (!scoredCands.get(k).key.equals(c1.key)) {
            c2 = scoredCands.get(k);
            break;
          }
        }
        if (c2 == null) {
          continue; // no distinct second candidate
        }

        float delta = c1.score - c2.score;
        if (delta > maxDelta) {
          continue; // Stage 1 is too confident, skip
        }

        boolean c1MatchesGold = EvaluateAmbiguityModel.isMatch(t, c1.id, goldId, c1.key, goldKey);
        boolean c2MatchesGold = EvaluateAmbiguityModel.isMatch(t, c2.id, goldId, c2.key, goldKey);

        int label = 0;
        if (c1MatchesGold) {
          // Stage 1 was ALREADY correct -> always KEEP!
          label = -1;
          totalKeeps++;
        } else if (c2MatchesGold) {
          // Stage 1 was incorrect, but Candidate 2 was correct -> FLIP!
          label = 1;
          totalFlips++;
        } else {
          // Neither c1 nor c2 is gold (Rank 3+ or unparseable)
          totalRank3PlusDropped++;
          continue;
        }

        RerankerInstance inst = new RerankerInstance();
        inst.sentence_id = rec.sentence_id;
        inst.text = rec.text;
        inst.token_index = i;
        inst.surface = t.surface;
        inst.delta = delta;
        inst.s1 = c1.score;
        inst.s2 = c2.score;
        inst.c1_key = c1.key;
        inst.c1_pos = c1.pos;
        inst.c2_key = c2.key;
        inst.c2_pos = c2.pos;
        inst.gold_key = goldKey;
        inst.label = label;
        inst.sentence_best_parses = bestParseKeys;

        inst.all_candidate_keys = new ArrayList<>();
        for (ScoredCand sc : scoredCands) {
          inst.all_candidate_keys.add(sc.key);
        }

        totalHarvested++;
        if (isVal) {
          valInstances.add(inst);
        } else {
          trainInstances.add(inst);
        }
      }
    }

    Log.info("Harvesting finished:");
    Log.info("  Total ambiguous checked: %,d", totalAmbiguousChecked);
    Log.info("  Total harvested (Delta <= %.2f): %,d", maxDelta, totalHarvested);
    Log.info("    * Flips (+1): %,d (%.2f%%)", totalFlips, (double) totalFlips / totalHarvested * 100);
    Log.info("    * Keeps (-1): %,d (%.2f%%)", totalKeeps, (double) totalKeeps / totalHarvested * 100);
    Log.info("    * Rank 3+ dropped: %,d", totalRank3PlusDropped);
    Log.info("  Train instances: %,d", trainInstances.size());
    Log.info("  Val instances:   %,d", valInstances.size());

    // Write outputs
    writeJsonl(trainInstances, outputTrainPath);
    writeJsonl(valInstances, outputValPath);
    Log.info("Wrote train dataset to: %s", outputTrainPath);
    Log.info("Wrote val dataset to:   %s", outputValPath);
  }

  private static void writeJsonl(List<RerankerInstance> instances, Path outPath) throws IOException {
    if (outPath.getParent() != null) {
      Files.createDirectories(outPath.getParent());
    }
    Gson gson = new GsonBuilder().create();
    try (BufferedWriter writer = Files.newBufferedWriter(outPath, StandardCharsets.UTF_8)) {
      for (RerankerInstance inst : instances) {
        writer.write(gson.toJson(inst));
        writer.newLine();
      }
    }
  }
}
