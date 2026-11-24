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
import java.time.Instant;
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

/**
 * Extracts all failed morphological disambiguation analyses from a baseline model
 * and exports them into a structured JSON file and a visually formatted Markdown report.
 * Supports sorting by perceptron score, margin delta, or gold score.
 */
public class ExportFailedAnalyses extends ConsoleApp {

  @Parameter(
      names = {"--model", "-m"},
      description = "Path to baseline binary model (.bin)")
  public Path modelPath = Paths.get("data/ambiguity/model-distilled-33k-th20k.bin");

  @Parameter(
      names = {"--input", "-i"},
      description = "Path to test dataset JSONL. If omitted, defaults to golden suite.")
  public List<String> inputFiles = new ArrayList<>();

  @Parameter(
      names = {"--outputJson", "-oj"},
      description = "Output path for JSON export")
  public Path outputJsonPath = Paths.get("data/ambiguity/failed_analyses_golden.json");

  @Parameter(
      names = {"--outputMd", "-om"},
      description = "Output path for Markdown visual report")
  public Path outputMdPath = Paths.get("data/ambiguity/failed_analyses_golden.md");

  @Parameter(
      names = {"--sortBy", "-s"},
      description = "Sorting criterion: 'score_desc' (highest predicted score first), 'margin_asc' (closest calls first), 'margin_desc' (biggest blunders first)")
  public String sortBy = "score_desc";

  @Parameter(
      names = {"--maxMdCards", "-max"},
      description = "Maximum detailed error inspection cards to include in MD report")
  public int maxMdCards = 300;

  public static class CandidateInfo {
    public int rank;
    public String analysis;
    public float score;
    public boolean is_predicted;
    public boolean is_gold;
    public String pos;
    public String root;
  }

  public static class AnalysisChoice {
    public String analysis;
    public float score;
    public String pos;
    public String root;
    public int rank;
  }

  public static class FailedAnalysisRecord {
    public String id;
    public String dataset;
    public String sentence_id;
    public String sentence_text;
    public String highlighted_sentence;
    public int token_index;
    public String surface;
    public AnalysisChoice predicted = new AnalysisChoice();
    public AnalysisChoice gold = new AnalysisChoice();
    public float margin_delta; // S_pred - S_gold
    public String error_category;
    public int candidate_count;
    public List<CandidateInfo> candidates = new ArrayList<>();
  }

  public static class FailedAnalysesExport {
    public String generated_at = Instant.now().toString();
    public String model_path;
    public String sort_by;
    public int total_sentences_evaluated = 0;
    public int total_tokens_evaluated = 0;
    public int total_ambiguous_tokens = 0;
    public int total_failed_analyses = 0;
    public double overall_ambiguity_accuracy = 0.0;
    public Map<String, Integer> error_categories = new LinkedHashMap<>();
    public Map<String, Integer> delta_distribution = new LinkedHashMap<>();
    public List<FailedAnalysisRecord> errors = new ArrayList<>();
  }

  public static void main(String[] args) {
    new ExportFailedAnalyses().execute(args);
  }

  @Override
  public String description() {
    return "Exports all failed disambiguation analyses to JSON and Markdown, sorted by perceptron score.";
  }

  @Override
  public void run() throws Exception {
    Log.info("Loading baseline FastPerceptron model from: %s", modelPath);
    FastPerceptronAmbiguityResolver resolver = FastPerceptronAmbiguityResolver.fromModelFile(modelPath);
    TurkishMorphology morphology = TurkishMorphology.builder()
        .setLexicon(RootLexicon.getDefault())
        .setAmbiguityResolver(resolver)
        .build();

    FastDecoder decoder = resolver.getDecoder();
    WeightLookup model = resolver.getModel();

    List<Path> datasets = new ArrayList<>();
    if (inputFiles != null && !inputFiles.isEmpty()) {
      for (String s : inputFiles) {
        datasets.add(Paths.get(s));
      }
    } else {
      // Default to 3 golden datasets
      datasets.add(Paths.get("data/ambiguity/golden_test_1000_thinking500.jsonl"));
      datasets.add(Paths.get("data/ambiguity/golden_test_2500_thinking500.jsonl"));
      datasets.add(Paths.get("data/ambiguity/golden_fresh_2500_canonical.jsonl"));
    }

    FailedAnalysesExport export = new FailedAnalysesExport();
    export.model_path = modelPath.toString();
    export.sort_by = sortBy;

    int errorSeq = 1;
    Gson gson = new Gson();

    int delta_0_to_0_5 = 0;
    int delta_0_5_to_1_0 = 0;
    int delta_1_0_to_2_0 = 0;
    int delta_2_0_to_5_0 = 0;
    int delta_5_plus = 0;

    for (Path dsPath : datasets) {
      if (!Files.exists(dsPath)) {
        Log.warn("Dataset file not found, skipping: %s", dsPath);
        continue;
      }
      String dsName = dsPath.getFileName().toString().replace(".jsonl", "");
      Log.info("Extracting failed analyses from: %s", dsPath);

      try (BufferedReader reader = Files.newBufferedReader(dsPath, StandardCharsets.UTF_8)) {
        String line;
        while ((line = reader.readLine()) != null) {
          line = line.trim();
          if (line.isEmpty()) continue;
          SentenceRecord rec = gson.fromJson(line, SentenceRecord.class);
          if (rec == null || rec.text == null || rec.tokens == null) continue;

          export.total_sentences_evaluated++;
          export.total_tokens_evaluated += rec.tokens.size();

          List<WordAnalysis> waList = morphology.analyzeSentence(rec.text);
          FastDecodeResult bestResult = decoder.bestPath(waList);
          List<SingleAnalysis> bestParse = bestResult.bestParse;

          int limit = Math.min(rec.tokens.size(), waList.size());
          for (int i = 0; i < limit; i++) {
            TokenRecord t = rec.tokens.get(i);
            if (!t.is_ambiguous) continue;
            export.total_ambiguous_tokens++;

            WordAnalysis wa = waList.get(i);
            List<SingleAnalysis> candidates = wa.getAnalysisResults();
            if (candidates.size() < 2) continue;

            int goldId = (t.selected_candidate_id != null) ? t.selected_candidate_id : 0;
            String goldKey = EvaluateAmbiguityModel.getCandidateKey(t, goldId);

            SingleAnalysis predSa = (i < bestParse.size()) ? bestParse.get(i) : null;
            int predId = EvaluateAmbiguityModel.matchAnalysisToCandidateId(t, predSa);
            String predKey = predSa != null ? predSa.formatLong() : "";

            boolean isMatch = EvaluateAmbiguityModel.isMatch(t, predId, goldId, predKey, goldKey);
            if (isMatch) continue; // Skip correct predictions

            export.total_failed_analyses++;

            // Local score computation for all candidates in context
            SingleAnalysis w_i_minus_2 = (i >= 2) ? bestParse.get(i - 2) : FastPerceptronAmbiguityResolver.sentenceBegin;
            SingleAnalysis w_i_minus_1 = (i >= 1) ? bestParse.get(i - 1) : FastPerceptronAmbiguityResolver.sentenceBegin;
            SingleAnalysis w_i_plus_1 = (i + 1 < bestParse.size()) ? bestParse.get(i + 1) : FastPerceptronAmbiguityResolver.sentenceEnd;
            SingleAnalysis w_i_plus_2 = (i + 2 < bestParse.size()) ? bestParse.get(i + 2) : FastPerceptronAmbiguityResolver.sentenceEnd;

            CandidateContext cc_prevPrev = new CandidateContext(w_i_minus_2, model, (i - 2 == 0), 0);
            CandidateContext cc_prev = new CandidateContext(w_i_minus_1, model, (i - 1 == 0), 0);
            CandidateContext cc_next = new CandidateContext(w_i_plus_1, model, false, 0);
            CandidateContext cc_nextNext = new CandidateContext(w_i_plus_2, model, false, 0);

            List<CandidateInfo> candInfoList = new ArrayList<>(candidates.size());
            CandidateInfo predCandInfo = null;
            CandidateInfo goldCandInfo = null;

            for (int cIdx = 0; cIdx < candidates.size(); cIdx++) {
              SingleAnalysis cSa = candidates.get(cIdx);
              CandidateContext cc_curr = new CandidateContext(cSa, model, (i == 0), 0);
              float score = cc_curr.uniScore;
              score += decoder.computeBigramScore(cc_prev, cc_curr);
              score += decoder.computeTrigramScore(cc_prevPrev, cc_prev, cc_curr);
              score += decoder.computeBigramScore(cc_curr, cc_next);
              score += decoder.computeTrigramScore(cc_prev, cc_curr, cc_next);
              score += decoder.computeTrigramScore(cc_curr, cc_next, cc_nextNext);

              CandidateInfo ci = new CandidateInfo();
              ci.analysis = cSa.formatLong();
              ci.score = score;
              ci.pos = (cSa.getPos() != null) ? cSa.getPos().name() : "Unk";
              ci.root = (cSa.getDictionaryItem() != null) ? cSa.getDictionaryItem().lemma : "";

              int cId = EvaluateAmbiguityModel.matchAnalysisToCandidateId(t, cSa);
              boolean isPredMatch = (cSa == predSa) || EvaluateAmbiguityModel.isMatch(t, cId, predId, ci.analysis, predKey);
              boolean isGoldMatch = EvaluateAmbiguityModel.isMatch(t, cId, goldId, ci.analysis, goldKey);

              ci.is_predicted = isPredMatch;
              ci.is_gold = isGoldMatch;

              candInfoList.add(ci);
              if (isPredMatch && predCandInfo == null) predCandInfo = ci;
              if (isGoldMatch && goldCandInfo == null) goldCandInfo = ci;
            }

            // Sort candidates by score descending
            candInfoList.sort((a, b) -> Float.compare(b.score, a.score));
            for (int r = 0; r < candInfoList.size(); r++) {
              candInfoList.get(r).rank = r + 1;
            }

            FailedAnalysisRecord err = new FailedAnalysisRecord();
            err.id = String.format("ERR_%05d", errorSeq++);
            err.dataset = dsName;
            err.sentence_id = (rec.sentence_id != null) ? rec.sentence_id : "sent_" + export.total_sentences_evaluated;
            err.sentence_text = rec.text;
            err.token_index = i;
            err.surface = t.surface;
            err.candidate_count = candidates.size();
            err.candidates = candInfoList;

            // Highlight word in sentence
            err.highlighted_sentence = buildHighlightedSentence(rec.text, rec.tokens, i);

            // Populate predicted
            if (predCandInfo != null) {
              err.predicted.analysis = predCandInfo.analysis;
              err.predicted.score = predCandInfo.score;
              err.predicted.pos = predCandInfo.pos;
              err.predicted.root = predCandInfo.root;
              err.predicted.rank = predCandInfo.rank;
            } else {
              err.predicted.analysis = predKey;
              err.predicted.score = candInfoList.isEmpty() ? 0 : candInfoList.get(0).score;
              err.predicted.pos = "Unknown";
              err.predicted.root = "";
              err.predicted.rank = 1;
            }

            // Populate gold
            if (goldCandInfo != null) {
              err.gold.analysis = goldCandInfo.analysis;
              err.gold.score = goldCandInfo.score;
              err.gold.pos = goldCandInfo.pos;
              err.gold.root = goldCandInfo.root;
              err.gold.rank = goldCandInfo.rank;
              err.margin_delta = err.predicted.score - err.gold.score;
            } else {
              err.gold.analysis = goldKey;
              err.gold.score = -999.0f;
              err.gold.pos = "Unknown";
              err.gold.root = "";
              err.gold.rank = -1; // Not in candidate set
              err.margin_delta = 999.0f;
            }

            // Error category: e.g. "Noun -> Verb", "Acc vs P3sg", etc.
            err.error_category = categorizeError(err.predicted.analysis, err.gold.analysis, err.predicted.pos, err.gold.pos);
            export.error_categories.merge(err.error_category, 1, Integer::sum);

            // Delta distribution
            if (err.margin_delta <= 0.5f) {
              delta_0_to_0_5++;
            } else if (err.margin_delta <= 1.0f) {
              delta_0_5_to_1_0++;
            } else if (err.margin_delta <= 2.0f) {
              delta_1_0_to_2_0++;
            } else if (err.margin_delta <= 5.0f) {
              delta_2_0_to_5_0++;
            } else {
              delta_5_plus++;
            }

            export.errors.add(err);
          }
        }
      }
    }

    export.overall_ambiguity_accuracy = 1.0 - ((double) export.total_failed_analyses / Math.max(1, export.total_ambiguous_tokens));

    export.delta_distribution.put("Δ 0.0 - 0.5 (Razor-thin ties)", delta_0_to_0_5);
    export.delta_distribution.put("Δ 0.5 - 1.0 (Close calls)", delta_0_5_to_1_0);
    export.delta_distribution.put("Δ 1.0 - 2.0 (Moderate margin)", delta_1_0_to_2_0);
    export.delta_distribution.put("Δ 2.0 - 5.0 (Clear margin)", delta_2_0_to_5_0);
    export.delta_distribution.put("Δ 5.0+ (Confident blunders)", delta_5_plus);

    // Sort errors according to sortBy
    if ("score_desc".equalsIgnoreCase(sortBy)) {
      export.errors.sort((a, b) -> Float.compare(b.predicted.score, a.predicted.score));
    } else if ("margin_asc".equalsIgnoreCase(sortBy)) {
      export.errors.sort((a, b) -> Float.compare(a.margin_delta, b.margin_delta));
    } else if ("margin_desc".equalsIgnoreCase(sortBy)) {
      export.errors.sort((a, b) -> Float.compare(b.margin_delta, a.margin_delta));
    } else if ("surface".equalsIgnoreCase(sortBy)) {
      export.errors.sort((a, b) -> a.surface.compareToIgnoreCase(b.surface));
    }

    // Write JSON file
    if (outputJsonPath.getParent() != null) {
      Files.createDirectories(outputJsonPath.getParent());
    }
    Gson prettyGson = new GsonBuilder().setPrettyPrinting().create();
    try (BufferedWriter writer = Files.newBufferedWriter(outputJsonPath, StandardCharsets.UTF_8)) {
      prettyGson.toJson(export, writer);
    }
    Log.info("Saved JSON failed analyses report to: %s (%d errors)", outputJsonPath, export.total_failed_analyses);

    // Write Markdown report
    if (outputMdPath.getParent() != null) {
      Files.createDirectories(outputMdPath.getParent());
    }
    writeMarkdownReport(export, outputMdPath, maxMdCards);
    Log.info("Saved Markdown visual inspection report to: %s", outputMdPath);
  }

  private static String buildHighlightedSentence(String text, List<TokenRecord> tokens, int targetIdx) {
    if (tokens == null || targetIdx >= tokens.size() || targetIdx < 0) {
      return text;
    }
    TokenRecord targetToken = tokens.get(targetIdx);
    if (targetToken.char_start >= 0 && targetToken.char_end <= text.length() && targetToken.char_start < targetToken.char_end) {
      return text.substring(0, targetToken.char_start)
          + "**[" + text.substring(targetToken.char_start, targetToken.char_end) + "]**"
          + text.substring(targetToken.char_end);
    }
    // Fallback: join token surfaces
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < tokens.size(); i++) {
      if (i > 0) sb.append(" ");
      if (i == targetIdx) {
        sb.append("**[").append(tokens.get(i).surface).append("]**");
      } else {
        sb.append(tokens.get(i).surface);
      }
    }
    return sb.toString();
  }

  private static String categorizeError(String predKey, String goldKey, String predPos, String goldPos) {
    if (predKey.contains(":Acc") && goldKey.contains(":P3sg")) return "Noun: Accusative -> Possessive(P3sg)";
    if (predKey.contains(":P3sg") && goldKey.contains(":Acc")) return "Noun: Possessive(P3sg) -> Accusative";
    if (predKey.contains(":A3pl+ı:P3sg") || predKey.contains(":A3sg+ları:P3pl")) return "Noun: Plural/Possessive internal tie";
    if (predPos.equals(goldPos)) return predPos + " (Internal Inflection Tie)";
    return predPos + " -> " + goldPos;
  }

  private static void writeMarkdownReport(FailedAnalysesExport export, Path mdPath, int maxCards) throws IOException {
    try (BufferedWriter w = Files.newBufferedWriter(mdPath, StandardCharsets.UTF_8)) {
      w.write("# Failed Disambiguation Analyses Inspection Report\n\n");
      w.write("> **Model**: `" + export.model_path + "`  \n");
      w.write("> **Generated At**: " + export.generated_at + "  \n");
      w.write("> **Sort Order**: `" + export.sort_by + "`  \n");
      w.write("> **Ambiguity Accuracy**: **" + String.format("%.2f%%", export.overall_ambiguity_accuracy * 100) + "** ");
      w.write("(" + (export.total_ambiguous_tokens - export.total_failed_analyses) + " / " + export.total_ambiguous_tokens + " correct)\n\n");

      w.write("## 1. Executive Summary & Statistics\n\n");
      w.write("| Metric | Value |\n");
      w.write("| :--- | :--- |\n");
      w.write("| **Total Sentences Evaluated** | " + String.format("%,d", export.total_sentences_evaluated) + " |\n");
      w.write("| **Total Tokens Evaluated** | " + String.format("%,d", export.total_tokens_evaluated) + " |\n");
      w.write("| **Ambiguous Tokens** | " + String.format("%,d", export.total_ambiguous_tokens) + " |\n");
      w.write("| **Total Failed Analyses** | **" + String.format("%,d", export.total_failed_analyses) + "** (" + String.format("%.2f%%", (double) export.total_failed_analyses / export.total_ambiguous_tokens * 100) + " error rate) |\n\n");

      w.write("### Perceptron Score Margin (Delta = S_pred - S_gold) Distribution\n\n");
      w.write("| Margin Range (Delta) | Mistake Count | Share of Errors |\n");
      w.write("| :--- | :---: | :---: |\n");
      for (Map.Entry<String, Integer> e : export.delta_distribution.entrySet()) {
        double pct = (double) e.getValue() / Math.max(1, export.total_failed_analyses) * 100.0;
        w.write("| **" + e.getKey() + "** | " + String.format("%,d", e.getValue()) + " | " + String.format("%.1f%%", pct) + " |\n");
      }
      w.write("\n");

      w.write("### Top Error Categories (POS / Morpheme Transitions)\n\n");
      w.write("| Error Category | Count | Share |\n");
      w.write("| :--- | :---: | :---: |\n");
      int catCount = 0;
      for (Map.Entry<String, Integer> e : export.error_categories.entrySet()) {
        double pct = (double) e.getValue() / Math.max(1, export.total_failed_analyses) * 100.0;
        w.write("| `" + e.getKey() + "` | " + String.format("%,d", e.getValue()) + " | " + String.format("%.1f%%", pct) + " |\n");
        if (++catCount >= 20) break;
      }
      w.write("\n---\n\n");

      w.write("## 2. Mistaken Tokens Leaderboard (Sorted by " + export.sort_by + ")\n\n");
      w.write("Showing top **" + Math.min(export.errors.size(), maxCards) + "** mistakes of " + export.total_failed_analyses + " total.\n\n");
      w.write("| ID | Surface | Predicted POS | Gold POS | Pred Score | Gold Score | Margin Δ | Gold Rank |\n");
      w.write("| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: |\n");
      int limit = Math.min(export.errors.size(), maxCards);
      for (int i = 0; i < Math.min(limit, 50); i++) {
        FailedAnalysisRecord r = export.errors.get(i);
        w.write(String.format("| [%s](#%s) | **%s** | `%s` | `%s` | `%.2f` | `%.2f` | `+%.2f` | Rank %d |\n",
            r.id, r.id.toLowerCase(), r.surface, r.predicted.pos, r.gold.pos, r.predicted.score, r.gold.score, r.margin_delta, r.gold.rank));
      }
      w.write("\n---\n\n");

      w.write("## 3. Detailed Visual Inspection Cards\n\n");
      for (int i = 0; i < limit; i++) {
        FailedAnalysisRecord r = export.errors.get(i);
        w.write("### <a id=\"" + r.id.toLowerCase() + "\"></a>" + r.id + ": `" + r.surface + "` (" + r.error_category + ")\n\n");
        w.write("- **Sentence (" + r.dataset + " / " + r.sentence_id + ")**:\n");
        w.write("  > *" + r.highlighted_sentence + "*\n\n");

        w.write("| Metric | Baseline Model (Incorrect) | Ground Truth (Gold) |\n");
        w.write("| :--- | :--- | :--- |\n");
        w.write("| **Selected Analysis** | `" + r.predicted.analysis + "` | `" + r.gold.analysis + "` |\n");
        w.write("| **Perceptron Score** | **`" + String.format("%.2f", r.predicted.score) + "`** (Rank " + r.predicted.rank + ") | `" + String.format("%.2f", r.gold.score) + "` (Rank " + (r.gold.rank > 0 ? r.gold.rank : "N/A") + ") |\n");
        w.write("| **Score Margin (Delta)** | \\- | **`+" + String.format("%.2f", r.margin_delta) + "`** |\n");
        w.write("| **Category** | `" + r.predicted.pos + "` (`" + r.predicted.root + "`) | `" + r.gold.pos + "` (`" + r.gold.root + "`) |\n\n");

        w.write("<details>\n<summary><b>Candidate Analyses Leaderboard (" + r.candidate_count + " candidates)</b></summary>\n\n");
        w.write("| Rank | Candidate Analysis | Perceptron Score | Status |\n");
        w.write("| :---: | :--- | :---: | :---: |\n");
        for (CandidateInfo c : r.candidates) {
          String status = "";
          if (c.is_predicted) status = "❌ **PREDICTED**";
          else if (c.is_gold) status = "✅ **GOLD**";
          w.write(String.format("| %d | `%s` | `%.2f` | %s |\n", c.rank, c.analysis, c.score, status));
        }
        w.write("\n</details>\n\n");
        w.write("---\n\n");
      }
    }
  }
}
