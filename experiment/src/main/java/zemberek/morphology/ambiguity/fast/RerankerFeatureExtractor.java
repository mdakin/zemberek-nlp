package zemberek.morphology.ambiguity.fast;

import java.util.*;
import zemberek.core.collections.FloatValueMap;
import zemberek.core.turkish.PrimaryPos;
import zemberek.core.turkish.SecondaryPos;
import zemberek.morphology.analysis.SingleAnalysis;
import zemberek.morphology.analysis.SingleAnalysis.MorphemeData;
import zemberek.morphology.analysis.SingleAnalysis.MorphemeGroup;

/**
 * Non-Viterbi Feature Extractor for Second-Stage Morphological Re-Ranking.
 * Operates on the fully disambiguated sentence context to extract long-range agreement,
 * clause predicate transitivity, wide-window n-grams, and punctuation boundaries.
 */
public class RerankerFeatureExtractor {

  private static final Set<String> FUNCTION_LEMMAS = new HashSet<>(Arrays.asList(
      "bu", "o", "şu", "ne", "ile", "için", "gibi", "kadar", "diye", "ise", "mi",
      "daha", "en", "çok", "biri", "bir", "kendi", "biz", "siz", "ben", "sen", "onlar",
      "tüm", "her", "bazı", "hiç", "ve", "veya", "ama", "fakat", "çünkü", "değil",
      "olmak", "etmek", "yapmak", "var", "yok", "önce", "sonra", "karşı", "bence", "bizce"
  ));

  public static FloatValueMap<String> extractCandidateFeatures(
      SingleAnalysis cand,
      int tokenIndex,
      List<SingleAnalysis> sentenceAnalyses,
      float delta,
      String otherPos) {

    FloatValueMap<String> feats = new FloatValueMap<>();
    if (cand == null) return feats;

    String lemma = cand.getDictionaryItem() != null ? cand.getDictionaryItem().lemma : "";
    PrimaryPos rootPos = cand.getDictionaryItem() != null ? cand.getDictionaryItem().primaryPos : PrimaryPos.Unknown;
    PrimaryPos syntacticPos = cand.getPos() != null ? cand.getPos() : rootPos;
    SecondaryPos secPos = cand.getDictionaryItem() != null ? cand.getDictionaryItem().secondaryPos : SecondaryPos.None;
    String pos = syntacticPos.name();
    String rpos = rootPos.name();
    String sp = secPos == SecondaryPos.None ? "" : secPos.name();

    // 1. Intrinsic Candidate Features
    feats.incrementByAmount("C_POS:" + pos, 1.0f);
    feats.incrementByAmount("C_ROOT_POS:" + rpos, 1.0f);
    if (!sp.isEmpty()) {
      feats.incrementByAmount("C_SEC_POS:" + sp, 1.0f);
    }
    feats.incrementByAmount("C_IG_COUNT:" + cand.groupCount(), 1.0f);

    // Only extract lexical features for closed-class functional words to prevent overfitting
    boolean isFunctionLemma = FUNCTION_LEMMAS.contains(lemma.toLowerCase(Locale.ROOT));
    if (isFunctionLemma) {
      feats.incrementByAmount("C_FUNC_LEMMA:" + lemma, 1.0f);
      feats.incrementByAmount("C_FUNC_LEMMA_POS:" + lemma + "+" + pos, 1.0f);
    }

    String caseType = null;
    String possType = null;
    String candPerson = null;
    boolean isParticiple = false;

    for (int g = 0; g < cand.groupCount(); g++) {
      MorphemeGroup mg = cand.getGroup(g);
      String igLex = mg.lexicalForm();
      feats.incrementByAmount("C_IG:" + g + "-" + igLex, 1.0f);
      if (g == cand.groupCount() - 1) {
        feats.incrementByAmount("C_LAST_IG:" + igLex, 1.0f);
      }

      // Check morphemes
      for (MorphemeData md : mg.getMorphemes()) {
        String morph = md.morpheme != null ? md.morpheme.id : "";
        if (morph.isEmpty()) continue;
        feats.incrementByAmount("C_MORPH:" + morph, 1.0f);
        feats.incrementByAmount("C_POS_MORPH:" + pos + "+" + morph, 1.0f);

        if (morph.equals("Acc") || morph.equals("Dat") || morph.equals("Gen") || morph.equals("Loc") || morph.equals("Abl")) {
          caseType = morph;
        }
        if (morph.startsWith("P1") || morph.startsWith("P2") || morph.startsWith("P3") || morph.equals("Pnon")) {
          possType = morph;
        }
        if (morph.startsWith("A1") || morph.startsWith("A2") || morph.startsWith("A3")) {
          candPerson = morph;
        }
        if (morph.contains("Part")) {
          isParticiple = true;
        }
      }
    }

    if (caseType != null) {
      feats.incrementByAmount("C_CASE:" + caseType, 1.0f);
    }
    if (possType != null) {
      feats.incrementByAmount("C_POSS:" + possType, 1.0f);
    }
    if (caseType != null && possType != null) {
      feats.incrementByAmount("C_POSS_CASE:" + possType + "+" + caseType, 1.0f);
    }
    if (isParticiple) {
      feats.incrementByAmount("C_IS_PARTICIPLE", 1.0f);
    }

    // Postposition case requirement check
    if (syntacticPos == PrimaryPos.PostPositive || rootPos == PrimaryPos.PostPositive) {
      if (secPos == SecondaryPos.PCDat) {
        boolean hasDat = hasPrecedingCase(sentenceAnalyses, tokenIndex, "Dat");
        if (hasDat) {
          feats.incrementByAmount("POSTP_DAT_SATISFIED", 1.0f);
        } else {
          feats.incrementByAmount("POSTP_DAT_VIOLATED", 1.0f);
        }
      } else if (secPos == SecondaryPos.PCAbl) {
        boolean hasAbl = hasPrecedingCase(sentenceAnalyses, tokenIndex, "Abl");
        if (hasAbl) {
          feats.incrementByAmount("POSTP_ABL_SATISFIED", 1.0f);
        } else {
          feats.incrementByAmount("POSTP_ABL_VIOLATED", 1.0f);
        }
      } else if (secPos == SecondaryPos.PCIns) {
        boolean hasIns = hasPrecedingCase(sentenceAnalyses, tokenIndex, "Ins");
        if (hasIns) {
          feats.incrementByAmount("POSTP_INS_SATISFIED", 1.0f);
        } else {
          feats.incrementByAmount("POSTP_INS_VIOLATED", 1.0f);
        }
      }
    }

    // Participle Adjective requires following Noun/Adjective head
    int n = sentenceAnalyses.size();
    if (syntacticPos == PrimaryPos.Adjective && isParticiple) {
      if (tokenIndex + 1 < n) {
        SingleAnalysis nextSa = sentenceAnalyses.get(tokenIndex + 1);
        PrimaryPos nextPos = nextSa.getPos() != null ? nextSa.getPos() : PrimaryPos.Unknown;
        if (nextPos == PrimaryPos.Noun || nextPos == PrimaryPos.Adjective) {
          feats.incrementByAmount("PARTICIPLE_ADJ_HEAD_FOUND", 1.0f);
        } else {
          feats.incrementByAmount("PARTICIPLE_ADJ_HEAD_MISSING", 1.0f);
        }
      } else {
        feats.incrementByAmount("PARTICIPLE_ADJ_HEAD_MISSING", 1.0f);
      }
    }

    // 2. Wide Context Window (+-1, +-2, +-3)
    for (int offset = -3; offset <= 3; offset++) {
      if (offset == 0) continue;
      int idx = tokenIndex + offset;
      if (idx >= 0 && idx < n) {
        SingleAnalysis ctxSa = sentenceAnalyses.get(idx);
        PrimaryPos ctxSyntacticPos = ctxSa.getPos() != null ? ctxSa.getPos() : PrimaryPos.Unknown;
        String ctxPos = ctxSyntacticPos.name();
        String ctxLemma = ctxSa.getDictionaryItem() != null ? ctxSa.getDictionaryItem().lemma : "";

        feats.incrementByAmount("CTX_POS_" + offset + ":" + ctxPos, 1.0f);
        feats.incrementByAmount("CTX_POS_CROSS_" + offset + ":" + ctxPos + "+" + pos, 1.0f);
        if (Math.abs(offset) <= 2 && FUNCTION_LEMMAS.contains(ctxLemma.toLowerCase(Locale.ROOT))) {
          feats.incrementByAmount("CTX_FUNC_LEMMA_CROSS_" + offset + ":" + ctxLemma + "+" + pos, 1.0f);
        }
      } else if (idx < 0) {
        feats.incrementByAmount("CTX_BOUND_" + offset + ":<s>", 1.0f);
      } else {
        feats.incrementByAmount("CTX_BOUND_" + offset + ":</s>", 1.0f);
      }
    }

    // 3. Punctuation & Immediate Boundaries
    boolean isFirst = (tokenIndex == 0);
    boolean isLast = (tokenIndex == n - 1 || (tokenIndex == n - 2 && sentenceAnalyses.get(n - 1).surfaceForm().equals(".")));
    if (isFirst) {
      feats.incrementByAmount("IS_FIRST_WORD", 1.0f);
      feats.incrementByAmount("FIRST_WORD_POS:" + pos, 1.0f);
    }
    if (isLast) {
      feats.incrementByAmount("IS_LAST_WORD", 1.0f);
      feats.incrementByAmount("LAST_WORD_POS:" + pos, 1.0f);
      if (syntacticPos == PrimaryPos.Verb && !isParticiple) {
        feats.incrementByAmount("LAST_WORD_FINITE_VERB", 1.0f);
      }
    }

    // Immediate right neighbor syntax (Determiner vs Pronoun, etc.)
    if (tokenIndex + 1 < n) {
      SingleAnalysis nextSa = sentenceAnalyses.get(tokenIndex + 1);
      PrimaryPos nextPos = nextSa.getPos() != null ? nextSa.getPos() : PrimaryPos.Unknown;
      String nextSurf = nextSa.surfaceForm();

      if (nextSurf != null && (nextSurf.equals(",") || nextSurf.equals(";"))) {
        feats.incrementByAmount("NEXT_IS_COMMA", 1.0f);
        feats.incrementByAmount("NEXT_COMMA_x_POS:" + pos, 1.0f);
        if (syntacticPos == PrimaryPos.Pronoun) {
          feats.incrementByAmount("PRON_BEFORE_COMMA", 1.0f);
        }
        if (syntacticPos == PrimaryPos.Determiner) {
          feats.incrementByAmount("DET_BEFORE_COMMA_PENALTY", 1.0f);
        }
      }

      if (syntacticPos == PrimaryPos.Determiner) {
        if (nextPos == PrimaryPos.Noun || nextPos == PrimaryPos.Adjective) {
          feats.incrementByAmount("DET_FOLLOWED_BY_NOUN_OR_ADJ", 1.0f);
        } else if (nextPos == PrimaryPos.Punctuation || nextPos == PrimaryPos.Verb) {
          feats.incrementByAmount("DET_FOLLOWED_BY_PUNC_OR_VERB_PENALTY", 1.0f);
        }
      }
      if (syntacticPos == PrimaryPos.Pronoun) {
        if (nextPos == PrimaryPos.Verb) {
          feats.incrementByAmount("PRON_FOLLOWED_BY_VERB", 1.0f);
        }
      }
    }


    // 4. Long-Range Case Government (Genitive in clause)
    boolean hasPrevGenitive = false;
    for (int j = tokenIndex - 1; j >= Math.max(0, tokenIndex - 5); j--) {
      SingleAnalysis prevSa = sentenceAnalyses.get(j);
      if (hasMorpheme(prevSa, "Gen")) {
        hasPrevGenitive = true;
        break;
      }
    }

    if (hasPrevGenitive) {
      feats.incrementByAmount("PREV_GENITIVE_IN_SCOPE", 1.0f);
      if (possType != null && possType.startsWith("P3")) {
        feats.incrementByAmount("PREV_GEN_x_POSS_P3", 1.0f);
      }
      if (caseType != null && caseType.equals("Acc") && (possType == null || possType.equals("Pnon"))) {
        feats.incrementByAmount("PREV_GEN_x_ACC_NO_POSS", 1.0f);
      }
    } else {
      if (caseType != null && caseType.equals("Acc")) {
        feats.incrementByAmount("NO_PREV_GEN_x_ACC", 1.0f);
      }
    }

    // Preceding Dative within 2 words (trigger for Postposition)
    boolean hasPrevDative = false;
    for (int j = tokenIndex - 1; j >= Math.max(0, tokenIndex - 2); j--) {
      if (hasMorpheme(sentenceAnalyses.get(j), "Dat")) {
        hasPrevDative = true;
        break;
      }
    }
    if (hasPrevDative) {
      feats.incrementByAmount("PREV_DATIVE_NEARBY", 1.0f);
      feats.incrementByAmount("PREV_DAT_x_POS:" + pos, 1.0f);
    }

    // Preceding Ablative within 2 words (trigger for sonra, önce, vb.)
    boolean hasPrevAblative = false;
    for (int j = tokenIndex - 1; j >= Math.max(0, tokenIndex - 2); j--) {
      if (hasMorpheme(sentenceAnalyses.get(j), "Abl")) {
        hasPrevAblative = true;
        break;
      }
    }
    if (hasPrevAblative) {
      feats.incrementByAmount("PREV_ABLATIVE_NEARBY", 1.0f);
      feats.incrementByAmount("PREV_ABL_x_POS:" + pos, 1.0f);
    }

    // 5. Clause Main Predicate & Person/Number Agreement
    SingleAnalysis predSa = findSentencePredicate(sentenceAnalyses);
    if (predSa != null) {
      PrimaryPos predPos = predSa.getDictionaryItem() != null ? predSa.getDictionaryItem().primaryPos : PrimaryPos.Unknown;
      feats.incrementByAmount("PRED_POS:" + predPos.name(), 1.0f);
      feats.incrementByAmount("PRED_POS_x_C_POS:" + predPos.name() + "+" + pos, 1.0f);
      if (predPos == PrimaryPos.Verb) {
        feats.incrementByAmount("PRED_IS_VERB", 1.0f);
        if (caseType != null) {
          feats.incrementByAmount("PRED_VERB_x_CASE:" + caseType, 1.0f);
        }

        // Predicate Person Agreement
        String predPerson = extractPerson(predSa);
        if (predPerson != null) {
          feats.incrementByAmount("PRED_PERSON:" + predPerson, 1.0f);

          // Possessive person agreement
          if (possType != null) {
            feats.incrementByAmount("PRED_PERSON_x_POSS:" + predPerson + "+" + possType, 1.0f);
            boolean agree = isPersonMatch(predPerson, possType);
            if (agree) {
              feats.incrementByAmount("PRED_POSS_PERSON_AGREE", 1.0f);
            } else if (predPerson.startsWith("A1") || predPerson.startsWith("A2")) {
              feats.incrementByAmount("PRED_POSS_PERSON_CLASH", 1.0f);
            }
          }

          // Pronoun person agreement
          if (candPerson != null) {
            feats.incrementByAmount("PRED_PERSON_x_CAND_PERSON:" + predPerson + "+" + candPerson, 1.0f);
            if (predPerson.equals(candPerson)) {
              feats.incrementByAmount("PRED_CAND_PERSON_AGREE", 1.0f);
            }
          }
        }
      }
    }

    // 6. Stage 1 Margin Features
    String deltaBin = delta <= 0.5f ? "D_0_0.5" : (delta <= 1.0f ? "D_0.5_1.0" : (delta <= 2.0f ? "D_1.0_2.0" : "D_2.0_3.0"));
    feats.incrementByAmount("DELTA_BIN:" + deltaBin, 1.0f);
    feats.incrementByAmount("DELTA_BIN_x_POS:" + deltaBin + "+" + pos, 1.0f);

    return feats;
  }

  /**
   * Computes the contrastive feature difference vector:
   * x = phi(c2, context) - phi(c1, context)
   */
  public static FloatValueMap<String> extractDifferenceVector(
      SingleAnalysis c1,
      SingleAnalysis c2,
      int tokenIndex,
      List<SingleAnalysis> sentenceAnalyses,
      float delta) {

    PrimaryPos p1 = c1.getPos() != null ? c1.getPos() : (c1.getDictionaryItem() != null ? c1.getDictionaryItem().primaryPos : PrimaryPos.Unknown);
    PrimaryPos p2 = c2.getPos() != null ? c2.getPos() : (c2.getDictionaryItem() != null ? c2.getDictionaryItem().primaryPos : PrimaryPos.Unknown);
    String c1Pos = p1.name();
    String c2Pos = p2.name();

    FloatValueMap<String> phi1 = extractCandidateFeatures(c1, tokenIndex, sentenceAnalyses, delta, c2Pos);
    FloatValueMap<String> phi2 = extractCandidateFeatures(c2, tokenIndex, sentenceAnalyses, delta, c1Pos);

    FloatValueMap<String> diff = new FloatValueMap<>();

    // Add phi2 (+1)
    for (FloatValueMap.Entry<String> entry : phi2.iterableEntries()) {
      diff.incrementByAmount(entry.key, entry.value);
    }
    // Subtract phi1 (-1)
    for (FloatValueMap.Entry<String> entry : phi1.iterableEntries()) {
      diff.incrementByAmount(entry.key, -entry.value);
    }

    // Specific pairwise category competition features (syntactic POS)
    String compPair = "PAIR:" + c1Pos + "_vs_" + c2Pos;
    diff.incrementByAmount(compPair, 1.0f);

    // Root-level competition feature if different
    PrimaryPos rp1 = c1.getDictionaryItem() != null ? c1.getDictionaryItem().primaryPos : PrimaryPos.Unknown;
    PrimaryPos rp2 = c2.getDictionaryItem() != null ? c2.getDictionaryItem().primaryPos : PrimaryPos.Unknown;
    if (rp1 != p1 || rp2 != p2) {
      diff.incrementByAmount("ROOT_PAIR:" + rp1.name() + "_vs_" + rp2.name(), 1.0f);
    }

    // Filter near-zero differences
    FloatValueMap<String> cleaned = new FloatValueMap<>();
    for (FloatValueMap.Entry<String> entry : diff.iterableEntries()) {
      if (Math.abs(entry.value) > 1e-5f) {
        cleaned.set(entry.key, entry.value);
      }
    }
    return cleaned;
  }

  public static boolean hasPrecedingCase(List<SingleAnalysis> sentenceAnalyses, int tokenIndex, String caseName) {
    if (sentenceAnalyses == null) return false;
    for (int j = tokenIndex - 1; j >= Math.max(0, tokenIndex - 2); j--) {
      SingleAnalysis prev = sentenceAnalyses.get(j);
      if (prev.getDictionaryItem() != null && prev.getDictionaryItem().primaryPos == PrimaryPos.Punctuation) {
        continue;
      }
      return hasMorpheme(prev, caseName);
    }
    return false;
  }

  public static boolean hasMorpheme(SingleAnalysis sa, String morphemeName) {
    if (sa == null) return false;
    for (int g = 0; g < sa.groupCount(); g++) {
      MorphemeGroup mg = sa.getGroup(g);
      for (MorphemeData md : mg.getMorphemes()) {
        if (md.morpheme != null && md.morpheme.id.equals(morphemeName)) {
          return true;
        }
      }
    }
    return false;
  }

  private static String extractPerson(SingleAnalysis sa) {
    if (sa == null) return null;
    for (int g = 0; g < sa.groupCount(); g++) {
      MorphemeGroup mg = sa.getGroup(g);
      for (MorphemeData md : mg.getMorphemes()) {
        String mId = md.morpheme != null ? md.morpheme.id : "";
        if (mId.startsWith("A1") || mId.startsWith("A2") || mId.startsWith("A3")) {
          return mId;
        }
      }
    }
    return null;
  }

  private static boolean isPersonMatch(String predPerson, String possType) {
    if (predPerson == null || possType == null) return false;
    if (predPerson.equals("A1sg") && possType.equals("P1sg")) return true;
    if (predPerson.equals("A2sg") && possType.equals("P2sg")) return true;
    if (predPerson.equals("A3sg") && possType.equals("P3sg")) return true;
    if (predPerson.equals("A1pl") && possType.equals("P1pl")) return true;
    if (predPerson.equals("A2pl") && possType.equals("P2pl")) return true;
    if (predPerson.equals("A3pl") && possType.equals("P3pl")) return true;
    return false;
  }

  private static SingleAnalysis findSentencePredicate(List<SingleAnalysis> sentenceAnalyses) {
    if (sentenceAnalyses == null || sentenceAnalyses.isEmpty()) return null;
    // Scan backward for the last finite verb
    for (int j = sentenceAnalyses.size() - 1; j >= 0; j--) {
      SingleAnalysis sa = sentenceAnalyses.get(j);
      if (sa.getDictionaryItem() != null && sa.getDictionaryItem().primaryPos == PrimaryPos.Verb) {
        return sa;
      }
    }
    // If no verb found, return the last non-punctuation token
    for (int j = sentenceAnalyses.size() - 1; j >= 0; j--) {
      SingleAnalysis sa = sentenceAnalyses.get(j);
      if (sa.getDictionaryItem() != null && sa.getDictionaryItem().primaryPos != PrimaryPos.Punctuation) {
        return sa;
      }
    }
    return sentenceAnalyses.get(sentenceAnalyses.size() - 1);
  }
}
