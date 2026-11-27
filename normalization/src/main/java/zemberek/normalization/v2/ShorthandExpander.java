package zemberek.normalization.v2;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import zemberek.normalization.v2.NormV2Types.SpanToken;
import zemberek.normalization.v2.SpanCandidateGenerator.SlotDraftBuilder;

/**
 * Expands curated conversational chat shorthands ({@code slm -> selam}, {@code tsk -> teşekkür},
 * {@code kib -> kendine iyi bak}) loaded from {@code /normalization/v2/shorthands.tsv}.
 */
final class ShorthandExpander {

  private static final String SHORTHANDS_RESOURCE = "/normalization/v2/shorthands.tsv";
  private static final Map<String, String> CURATED_CHAT_SHORTHANDS = loadCuratedChatShorthands();

  ShorthandExpander() {}

  static boolean isCuratedShorthand(String lower) {
    return CURATED_CHAT_SHORTHANDS.containsKey(lower);
  }

  void addCandidates(SlotDraftBuilder slot) {
    SpanToken tok = slot.tok;
    String low = tok.lower();
    if (low.length() < 2 || low.length() > 16 || low.indexOf('\'') >= 0) {
      return;
    }

    String sqTrail = SingleTokenRewriteGenerator.squeezeTrailingRun(low);
    String sqAll = SingleTokenRewriteGenerator.squeezeRuns(low, 1);
    String[] lookupKeys = distinctLookupKeys(low, sqTrail, sqAll);
    addExactShorthands(slot, low, lookupKeys);
  }

  private static String[] distinctLookupKeys(String low, String sqTrail, String sqAll) {
    if (sqTrail.equals(low)) {
      return new String[] {low};
    }
    return sqAll.equals(sqTrail)
        ? new String[] {low, sqTrail}
        : new String[] {low, sqTrail, sqAll};
  }

  private static void addExactShorthands(SlotDraftBuilder slot, String low, String[] lookupKeys) {
    for (String key : lookupKeys) {
      String exactTarget = CURATED_CHAT_SHORTHANDS.get(key);
      if (exactTarget != null && !exactTarget.equals(low)) {
        int morphOrSplitFlag =
            exactTarget.indexOf(' ') >= 0
                ? NormV2Types.SRC_CLITIC_SPLIT
                : NormV2Types.SRC_FORMAL_MORPH;
        int elongFlag = !key.equals(low) ? NormV2Types.SRC_ELONGATION : 0;
        emitShorthand(
            slot, exactTarget, NormV2Types.SRC_SHORTHAND | morphOrSplitFlag | elongFlag, 0.94f);
      }
    }
  }

  private static void emitShorthand(
      SlotDraftBuilder slot, String targetLowerOrCased, int srcMask, float precision) {
    String rawLow = slot.tok.lower();
    String normLow = NormV2Types.trLower(targetLowerOrCased);
    String surf =
        targetLowerOrCased.indexOf(' ') >= 0
            ? formatMultiWordShorthand(slot, targetLowerOrCased)
            : slot.truecase(normLow);
    slot.addRule(
        surf,
        normLow,
        srcMask,
        precision,
        SpanCandidateGenerator.computeWeightedEditCost(rawLow, normLow),
        SpanCandidateGenerator.countDiacriticDiffs(rawLow, normLow));
  }

  private static String formatMultiWordShorthand(SlotDraftBuilder slot, String target) {
    if (slot.sentenceAllCaps) {
      return NormV2Types.trUpper(target);
    }
    if (Character.isUpperCase(target.charAt(0))) {
      return target;
    }
    return slot.capHere ? NormV2Types.trCapitalize(target) : target;
  }

  private static Map<String, String> loadCuratedChatShorthands() {
    Map<String, String> m = new LinkedHashMap<>(160);
    try {
      InputStream in = ShorthandExpander.class.getResourceAsStream(SHORTHANDS_RESOURCE);
      if (in == null) {
        Path fallback =
            Paths.get(
                "/home/dndara/work/zemberek-nlp/normalization/src/main/resources/normalization/v2/shorthands.tsv");
        if (Files.exists(fallback)) {
          in = Files.newInputStream(fallback);
        }
      }
      if (in != null) {
        try (BufferedReader br =
            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
          String line;
          while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
              continue;
            }
            int tab = line.indexOf('\t');
            if (tab > 0 && tab < line.length() - 1) {
              m.put(line.substring(0, tab).trim(), line.substring(tab + 1).trim());
            }
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return Map.copyOf(m);
  }
}
