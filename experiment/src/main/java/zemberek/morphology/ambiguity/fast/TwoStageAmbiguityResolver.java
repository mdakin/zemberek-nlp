package zemberek.morphology.ambiguity.fast;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import zemberek.core.data.CompressedWeights;
import zemberek.core.data.WeightLookup;
import zemberek.core.data.Weights;
import zemberek.core.turkish.PrimaryPos;
import zemberek.core.turkish.SecondaryPos;
import zemberek.morphology.ambiguity.AmbiguityResolver;
import zemberek.morphology.ambiguity.fast.FastPerceptronAmbiguityResolver.CandidateContext;
import zemberek.morphology.ambiguity.fast.FastPerceptronAmbiguityResolver.FastDecoder;
import zemberek.morphology.ambiguity.fast.FastPerceptronAmbiguityResolver.FastDecodeResult;
import zemberek.morphology.analysis.SentenceAnalysis;
import zemberek.morphology.analysis.SentenceWordAnalysis;
import zemberek.morphology.analysis.SingleAnalysis;
import zemberek.morphology.analysis.WordAnalysis;

/**
 * Two-stage Ambiguity Resolver combining FastPerceptron (Stage 1 global Viterbi)
 * with a Confidence-Gated Non-Viterbi Re-ranker (Stage 2 contrastive tie-breaker).
 */
public class TwoStageAmbiguityResolver implements AmbiguityResolver {

  private final FastPerceptronAmbiguityResolver stage1;
  private final RerankerModel stage2;

  public TwoStageAmbiguityResolver(FastPerceptronAmbiguityResolver stage1, RerankerModel stage2) {
    this.stage1 = stage1;
    this.stage2 = stage2;
  }

  public static TwoStageAmbiguityResolver fromFiles(Path stage1Path, Path stage2Path) throws IOException {
    FastPerceptronAmbiguityResolver stage1 = FastPerceptronAmbiguityResolver.fromModelFile(stage1Path);
    RerankerModel stage2 = RerankerModel.loadFromFile(stage2Path);
    return new TwoStageAmbiguityResolver(stage1, stage2);
  }

  public FastPerceptronAmbiguityResolver getStage1() {
    return stage1;
  }

  public RerankerModel getStage2() {
    return stage2;
  }

  @Override
  public SentenceAnalysis disambiguate(String sentence, List<WordAnalysis> allAnalyses) {
    if (allAnalyses == null || allAnalyses.isEmpty()) {
      return new SentenceAnalysis(sentence, Collections.emptyList());
    }

    // Step 1: Run Stage 1 Fast Perceptron
    FastDecoder decoder = stage1.getDecoder();
    FastDecodeResult bestResult = stage1.isGreedy()
        ? decoder.bestPathGreedy(allAnalyses)
        : decoder.bestPath(allAnalyses);
    List<SingleAnalysis> bestParse = new ArrayList<>(bestResult.bestParse);

    // Step 2: Apply Stage 2 Re-ranker on close ties
    if (stage2 != null) {
      resolveStage2(allAnalyses, bestParse);
    }

    List<SentenceWordAnalysis> list = new ArrayList<>(allAnalyses.size());
    for (int i = 0; i < allAnalyses.size(); i++) {
      list.add(new SentenceWordAnalysis(bestParse.get(i), allAnalyses.get(i)));
    }
    return new SentenceAnalysis(sentence, list);
  }

  /**
   * Evaluates candidates for each word and flips from c1 to c2 if delta <= maxDelta and reranker fires.
   * Returns the total number of flips made.
   */
  public int resolveStage2(List<WordAnalysis> allAnalyses, List<SingleAnalysis> bestParse) {
    if (stage2 == null || allAnalyses == null || allAnalyses.size() < 1) {
      return 0;
    }

    int flips = 0;
    FastDecoder decoder = stage1.getDecoder();
    WeightLookup model = stage1.getModel();
    float maxDelta = stage2.getMaxDelta();

    for (int i = 0; i < allAnalyses.size(); i++) {
      WordAnalysis wa = allAnalyses.get(i);
      List<SingleAnalysis> candidates = wa.getAnalysisResults();
      if (candidates == null || candidates.size() < 2) continue;

      SingleAnalysis w_i_minus_2 = (i >= 2) ? bestParse.get(i - 2) : FastPerceptronAmbiguityResolver.sentenceBegin;
      SingleAnalysis w_i_minus_1 = (i >= 1) ? bestParse.get(i - 1) : FastPerceptronAmbiguityResolver.sentenceBegin;
      SingleAnalysis w_i_plus_1 = (i + 1 < bestParse.size()) ? bestParse.get(i + 1) : FastPerceptronAmbiguityResolver.sentenceEnd;
      SingleAnalysis w_i_plus_2 = (i + 2 < bestParse.size()) ? bestParse.get(i + 2) : ((i + 1 < bestParse.size()) ? FastPerceptronAmbiguityResolver.sentenceEnd : null);

      CandidateContext cc_prevPrev = new CandidateContext(w_i_minus_2, model, (i - 2 == 0), 0);
      CandidateContext cc_prev = new CandidateContext(w_i_minus_1, model, (i - 1 == 0), 0);
      CandidateContext cc_next = new CandidateContext(w_i_plus_1, model, false, 0);
      CandidateContext cc_nextNext = (w_i_plus_2 != null) ? new CandidateContext(w_i_plus_2, model, false, 0) : null;

      SingleAnalysis bestC1 = null;
      float bestS1 = -1e9f;
      SingleAnalysis bestC2 = null;
      float bestS2 = -1e9f;

      for (SingleAnalysis cSa : candidates) {
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

        String candKey = cSa.formatLong();
        if (bestC1 == null || score > bestS1) {
          if (bestC1 != null && !bestC1.formatLong().equals(candKey)) {
            bestS2 = bestS1;
            bestC2 = bestC1;
          }
          bestS1 = score;
          bestC1 = cSa;
        } else if (!candKey.equals(bestC1.formatLong()) && (bestC2 == null || score > bestS2)) {
          bestS2 = score;
          bestC2 = cSa;
        }
      }

      if (bestC1 == null || bestC2 == null) continue;

      PrimaryPos syn1 = bestC1.getPos() != null ? bestC1.getPos() : PrimaryPos.Unknown;
      PrimaryPos syn2 = bestC2.getPos() != null ? bestC2.getPos() : PrimaryPos.Unknown;

      // Skip Noun-Noun internal case/possessive ties where Stage 1 global Viterbi is already superior
      if (syn1 == PrimaryPos.Noun && syn2 == PrimaryPos.Noun) continue;

      // Grammatical Guard 1: değil Verb -> Conj
      // In Turkish, değil is a negative copula verb in ~98% of usages; Stage 1 Viterbi is highly reliable on it.
      if (bestC1.formatLong().contains("değil:Verb") && bestC2.formatLong().contains("değil:Conj")) {
        continue;
      }

      // Grammatical Guard 2: Postposition case requirement
      // If Candidate 2 is a postposition requiring Dative (e.g. karşı/göre) or Ablative (e.g. sonra/fazla/aşağı),
      // it CANNOT be chosen if the preceding word does not bear that governed case.
      SecondaryPos sec2 = bestC2.getDictionaryItem() != null ? bestC2.getDictionaryItem().secondaryPos : SecondaryPos.None;
      if (syn2 == PrimaryPos.PostPositive || (bestC2.getDictionaryItem() != null && bestC2.getDictionaryItem().primaryPos == PrimaryPos.PostPositive)) {
        if (sec2 == SecondaryPos.PCDat && !RerankerFeatureExtractor.hasPrecedingCase(bestParse, i, "Dat")) {
          continue;
        }
        if (sec2 == SecondaryPos.PCAbl && !RerankerFeatureExtractor.hasPrecedingCase(bestParse, i, "Abl")) {
          continue;
        }
        if (sec2 == SecondaryPos.PCIns && !RerankerFeatureExtractor.hasPrecedingCase(bestParse, i, "Ins")) {
          continue;
        }
      }

      float delta = bestS1 - bestS2;
      if (delta <= maxDelta) {
        if (stage2.shouldFlip(bestC1, bestC2, i, bestParse, delta)) {
          bestParse.set(i, bestC2);
          flips++;
        }
      }
    }
    return flips;
  }
}
