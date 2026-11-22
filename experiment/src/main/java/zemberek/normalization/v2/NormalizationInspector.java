package zemberek.normalization.v2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import zemberek.normalization.v2.NormV2ModelStore.LoadedBundle;
import zemberek.normalization.v2.NormV2Types.SentenceLattice;
import zemberek.normalization.v2.NormV2Types.SpanArc;
import zemberek.normalization.v2.NormV2Types.SpanToken;
import zemberek.normalization.v2.TwoStageGbdtNormalizer.SlotMaxima;

/**
 * Live normalization &amp; X-ray lattice inspector for {@link TwoStageGbdtNormalizer}, {@link
 * SpanLatticePerceptronNormalizer}, {@code Hybrid}, and {@code Zemberek HEAD}.
 */
public final class NormalizationInspector {

  private final LoadedBundle bundle;

  public NormalizationInspector(LoadedBundle bundle) {
    this.bundle = bundle;
  }

  public record CandidateView(
      int startIdx,
      int endIdx,
      String normSurface,
      String spanKind,
      int wordCount,
      float logit,
      float memmProb,
      float cleanSpanLlr,
      float smoothSpanLlr,
      float cliticSkipLlr,
      float parallelProb,
      float editCost,
      boolean regularMorph,
      List<String> sources,
      boolean winner) {}

  public record GateSignals(
      int wordLen,
      boolean hasTurkishDiacritics,
      boolean hasAsciiAmbiguous,
      boolean hasRepeatedChars,
      boolean cliticTailHint,
      boolean nextMergeHint,
      boolean strictMorph,
      float charPhonotactics,
      float parallelKeepProb,
      float cleanUnigramLogProb) {}

  public record SlotInspection(
      int tokenIdx,
      String rawSurface,
      String tokenType,
      boolean immutable,
      float gateLogit,
      float gateProb,
      float gateThreshold,
      boolean gateSkipped,
      String gateNote,
      GateSignals gateSignals,
      List<CandidateView> candidates) {}

  public record DecodedSpanView(
      int startIdx,
      int endIdx,
      String rawSurface,
      String normSurface,
      boolean changed,
      String changeKind,
      boolean attachToPrev,
      boolean spaceAfter,
      float gateProb,
      boolean gateSkipped,
      float winningLogit,
      float winningProb,
      List<String> sources) {}

  public record LineTrace(
      int lineIndex,
      String rawLine,
      String normalizedLine,
      int tokenCount,
      int editedSpanCount,
      List<DecodedSpanView> decodedSpans,
      List<SlotInspection> slots) {}

  public record EngineComparison(
      String engineId, String label, String sizeLabel, String normalizedText, double latencyMs) {}

  public record NormalizeResponse(
      String engineId,
      String engineLabel,
      String normalizedText,
      double latencyMs,
      double totalMs,
      int totalTokens,
      int totalEditedSpans,
      List<LineTrace> lines,
      List<EngineComparison> comparisons) {}

  public NormalizeResponse normalizeAndInspect(
      String rawText, String engineId, boolean includeInspection, boolean compareAll) {
    long tTotal0 = System.nanoTime();
    String text = rawText == null ? "" : rawText;
    String eng = (engineId == null || engineId.isBlank()) ? "gbdt_gated" : engineId.trim();

    String[] rawLines = text.split("\n", -1);
    StringBuilder fullOut = new StringBuilder(text.length() + 16);

    // 1. Measure pure inference latency across non-empty lines
    long tInferNs = 0L;
    String[] pureLinePreds = new String[rawLines.length];
    for (int i = 0; i < rawLines.length; i++) {
      String rl = rawLines[i];
      if (rl.endsWith("\r")) {
        rl = rl.substring(0, rl.length() - 1);
      }
      if (rl.isBlank()) {
        pureLinePreds[i] = rl;
      } else {
        long t0 = System.nanoTime();
        pureLinePreds[i] = runSingleEngine(eng, rl);
        tInferNs += (System.nanoTime() - t0);
      }
      if (i > 0) {
        fullOut.append('\n');
      }
      fullOut.append(pureLinePreds[i]);
    }

    // 2. Build rich span diffs & Stage-1 / Stage-2 lattice diagnostics
    List<LineTrace> lineTraces = new ArrayList<>(rawLines.length);
    int totalTokens = 0;
    int totalEdited = 0;
    for (int i = 0; i < rawLines.length; i++) {
      String rl = rawLines[i];
      if (rl.endsWith("\r")) {
        rl = rl.substring(0, rl.length() - 1);
      }
      LineTrace lt = buildLineTrace(i, rl, pureLinePreds[i], eng, includeInspection);
      totalTokens += lt.tokenCount();
      totalEdited += lt.editedSpanCount();
      lineTraces.add(lt);
    }

    // 3. Optional side-by-side comparison across all 4 engines
    List<EngineComparison> comparisons = null;
    if (compareAll && !text.isBlank()) {
      comparisons = new ArrayList<>(4);
      comparisons.add(runComparison("gbdt_gated", "Micro-GBDT + Gate", "115 KB", rawLines));
      comparisons.add(runComparison("hybrid", "Hybrid (Perceptron + GBDT)", "4.1 MB", rawLines));
      comparisons.add(runComparison("perceptron", "Perceptron (v2.3)", "4.0 MB", rawLines));
      comparisons.add(runComparison("zemberek_head", "Zemberek HEAD", "Rule + 2-gram", rawLines));
    }

    double inferMs = tInferNs / 1e6;
    double totalMs = (System.nanoTime() - tTotal0) / 1e6;
    return new NormalizeResponse(
        eng,
        engineLabel(eng),
        fullOut.toString(),
        inferMs,
        totalMs,
        totalTokens,
        totalEdited,
        lineTraces,
        comparisons);
  }

  private EngineComparison runComparison(
      String engId, String label, String sizeLabel, String[] rawLines) {
    StringBuilder sb = new StringBuilder();
    long t0 = System.nanoTime();
    for (int i = 0; i < rawLines.length; i++) {
      String rl = rawLines[i];
      if (rl.endsWith("\r")) {
        rl = rl.substring(0, rl.length() - 1);
      }
      if (i > 0) {
        sb.append('\n');
      }
      sb.append(rl.isBlank() ? rl : runSingleEngine(engId, rl));
    }
    double ms = (System.nanoTime() - t0) / 1e6;
    return new EngineComparison(engId, label, sizeLabel, sb.toString(), ms);
  }

  private String runSingleEngine(String engineId, String line) {
    return switch (engineId) {
      case "gbdt_full" -> bundle.gbdtFull().normalize(line);
      case "perceptron" -> bundle.perceptron().normalize(line);
      case "hybrid" -> bundle.hybrid().normalize(line);
      case "zemberek_head" -> bundle.zemberekHead().normalize(line);
      default -> bundle.gbdtGated().normalize(line);
    };
  }

  private static String engineLabel(String engineId) {
    return switch (engineId) {
      case "gbdt_full" -> "Micro-GBDT Full Lattice (115 KB)";
      case "perceptron" -> "Perceptron (v2.3 · 4.0 MB)";
      case "hybrid" -> "Hybrid Perceptron + GBDT (v2.3 · 4.1 MB)";
      case "zemberek_head" -> "Zemberek HEAD (Rule + SmoothLm)";
      default -> "Micro-GBDT + Gate (v2.3 · 115 KB)";
    };
  }

  private LineTrace buildLineTrace(
      int lineIndex,
      String rawLine,
      String normalizedLine,
      String engineId,
      boolean includeInspection) {
    if (rawLine.isBlank()) {
      return new LineTrace(lineIndex, rawLine, normalizedLine, 0, 0, List.of(), List.of());
    }

    // For Zemberek HEAD, align word tokens cleanly; for v2.3 engines, inspect actual lattice!
    if ("zemberek_head".equals(engineId)) {
      List<SpanToken> rawToks = NormV2Types.spanTokenize(rawLine);
      List<DecodedSpanView> single =
          List.of(
              new DecodedSpanView(
                  0,
                  rawToks.size(),
                  rawLine,
                  normalizedLine,
                  !rawLine.trim().equals(normalizedLine.trim()),
                  rawLine.trim().equals(normalizedLine.trim()) ? "keep" : "rewrite_1to1",
                  false,
                  false,
                  0.0f,
                  false,
                  0.0f,
                  1.0f,
                  List.of("ZemberekHEAD")));
      return new LineTrace(
          lineIndex,
          rawLine,
          normalizedLine,
          rawToks.size(),
          rawLine.trim().equals(normalizedLine.trim()) ? 0 : 1,
          single,
          List.of());
    }

    TwoStageGbdtNormalizer gbdt =
        "gbdt_full".equals(engineId) ? bundle.gbdtFull() : bundle.gbdtGated();
    boolean useGateInLattice = "gbdt_gated".equals(engineId);
    SpanCandidateGenerator gen = bundle.generator();

    float[] gateBuf = new float[TwoStageGbdtNormalizer.GATE_DIM];
    boolean[] skippedFlags = new boolean[256];
    SentenceLattice lattice =
        gen.buildLattice(
            rawLine,
            useGateInLattice
                ? (toks, idx) -> {
                  boolean sk = gbdt.shouldGateSkip(toks, idx, gateBuf);
                  if (idx < skippedFlags.length) {
                    skippedFlags[idx] = sk;
                  }
                  return sk;
                }
                : null);

    List<SpanToken> toks = lattice.tokens();
    int n = toks.size();
    if (n == 0) {
      return new LineTrace(lineIndex, rawLine, normalizedLine, 0, 0, List.of(), List.of());
    }

    List<SpanArc> decodedPath =
        switch (engineId) {
          case "perceptron" -> bundle.perceptron().decodeLattice(lattice);
          case "hybrid" -> bundle.hybrid().decodeLattice(lattice);
          default -> gbdt.decodeSpanDp(lattice);
        };

    SpanArc[] winnerByStart = new SpanArc[n];
    String[] prev2At = new String[n + 1];
    String[] prev1At = new String[n + 1];
    String[] nextLex2 = new String[2];
    for (SpanArc wa : decodedPath) {
      if (wa.startIdx >= 0 && wa.startIdx < n) {
        winnerByStart[wa.startIdx] = wa;
      }
      SpanCandidateGenerator.advanceLexicalHistory(
          lattice, prev2At[wa.startIdx], prev1At[wa.startIdx], wa, nextLex2);
      if (wa.endIdx <= n) {
        prev2At[wa.endIdx] = nextLex2[0];
        prev1At[wa.endIdx] = nextLex2[1];
      }
    }

    // Compute per-slot Stage-1 Gatekeeper & Stage-2 Reranker probabilities
    float[] gFeat = new float[TwoStageGbdtNormalizer.GATE_DIM];
    float[] rFeat = new float[TwoStageGbdtNormalizer.RANK_DIM];
    float[][] arcLm6 = new float[32][6];

    float[] slotGateProb = new float[n];
    boolean[] slotGateSkip = new boolean[n];
    float[] winLogitByStart = new float[n];
    float[] winProbByStart = new float[n];
    List<SlotInspection> slotList = includeInspection ? new ArrayList<>(n) : List.of();

    String rollPrev2 = null;
    String rollPrev1 = null;

    for (int i = 0; i < n; i++) {
      SpanToken t = toks.get(i);
      String p2 = prev2At[i] != null || i == 0 ? prev2At[i] : rollPrev2;
      String p1 = prev1At[i] != null || i == 0 ? prev1At[i] : rollPrev1;

      float gateLogit = -12.0f;
      float gateProb = 0.0f;
      boolean gateSkipped = i < skippedFlags.length && skippedFlags[i];
      String gateNote = null;
      GateSignals gs = null;

      if (t.isImmutable()) {
        gateSkipped = true;
        gateNote = "Immutable token (" + t.type().name() + ")";
      } else {
        TwoStageGbdtNormalizer.extractGateFeatures(toks, i, false, false, gen, gFeat);
        gateLogit = gbdt.keepGatekeeper().predictLogit(gFeat);
        gateProb = ObliviousGbdt.sigmoid(gateLogit);
        if (gFeat[12] > 0.5f) {
          gateNote = "Bypass guard: clitic tail (-de/-da/-mi/-ki)";
        } else if (gFeat[13] > 0.5f) {
          gateNote = "Bypass guard: 2:1 merge candidate";
        } else if (t.surface().indexOf('\'') >= 0) {
          gateNote = "Bypass guard: contains apostrophe";
        } else if (!useGateInLattice && gateProb < gbdt.gateSkipThreshold()) {
          gateNote = "Would skip in Gated mode (< threshold)";
        } else if (gateSkipped) {
          gateNote = "Skipped heavy expansion (clean token)";
        } else {
          gateNote = "Expanded full candidate lattice";
        }
        if (includeInspection) {
          gs =
              new GateSignals(
                  Math.round(gFeat[0]),
                  gFeat[1] > 0.5f,
                  gFeat[2] > 0.5f,
                  gFeat[3] > 0.5f,
                  gFeat[12] > 0.5f,
                  gFeat[13] > 0.5f,
                  gFeat[14] > 0.5f,
                  round3(gFeat[6]),
                  round3(gFeat[7]),
                  round3(gFeat[15]));
        }
      }
      slotGateProb[i] = round4(gateProb);
      slotGateSkip[i] = gateSkipped;

      List<SpanArc> arcs = lattice.outgoing()[i];
      SpanArc winArc = winnerByStart[i];
      winProbByStart[i] = 1.0f;

      if (!arcs.isEmpty()) {
        if (arcLm6.length < arcs.size()) {
          arcLm6 = new float[arcs.size() + 8][6];
        }
        SlotMaxima maxima =
            TwoStageGbdtNormalizer.computeSlotLmAndMaxima(lattice, arcs, p2, p1, gen, arcLm6);
        float[] logits = new float[arcs.size()];
        float maxLogit = Float.NEGATIVE_INFINITY;
        for (int a = 0; a < arcs.size(); a++) {
          SpanArc cand = arcs.get(a);
          if (arcs.size() == 1) {
            logits[a] = 0.0f;
            maxLogit = 0.0f;
          } else {
            TwoStageGbdtNormalizer.extractRankFeatures(
                lattice, p1, cand, arcLm6[a], maxima, false, false, gen, rFeat);
            float lg = gbdt.candidateReranker().predictLogit(rFeat);
            logits[a] = lg;
            if (lg > maxLogit) {
              maxLogit = lg;
            }
          }
        }
        double denomSum = 0.0;
        for (float lg : logits) {
          denomSum += Math.exp(lg - maxLogit);
        }
        float logDenom = maxLogit + (float) Math.log(Math.max(1e-12, denomSum));

        List<CandidateView> cViews = includeInspection ? new ArrayList<>(arcs.size()) : null;
        for (int a = 0; a < arcs.size(); a++) {
          SpanArc cand = arcs.get(a);
          float prob = (float) Math.exp(logits[a] - logDenom);
          boolean isWin =
              winArc != null
                  && cand.endIdx == winArc.endIdx
                  && cand.normSurface.equals(winArc.normSurface);
          if (isWin) {
            winLogitByStart[i] = round3(logits[a]);
            winProbByStart[i] = round4(prob);
          }
          if (includeInspection) {
            float sLlr = arcLm6[a][0] - arcLm6[a][1];
            float cLlr = arcLm6[a][2] - arcLm6[a][3];
            float cliticSkip = gen.computeCliticTransparencyLlr(lattice, cand);
            cViews.add(
                new CandidateView(
                    cand.startIdx,
                    cand.endIdx,
                    cand.normSurface,
                    describeSpanKind(cand),
                    cand.wordCount,
                    round3(logits[a]),
                    round4(prob),
                    round3(cLlr),
                    round3(sLlr),
                    round3(cliticSkip),
                    round3(cand.parallelProb),
                    round3(cand.editCost),
                    cand.isRegularMorph,
                    decodeSources(cand.sourceMask),
                    isWin));
          }
        }
        if (includeInspection) {
          cViews.sort(
              Comparator.comparing(CandidateView::winner)
                  .reversed()
                  .thenComparing(Comparator.comparingDouble(CandidateView::memmProb).reversed()));
          slotList.add(
              new SlotInspection(
                  i,
                  t.surface(),
                  t.type().name(),
                  t.isImmutable(),
                  round3(gateLogit),
                  round4(gateProb),
                  round4(gbdt.gateSkipThreshold()),
                  gateSkipped,
                  gateNote,
                  gs,
                  cViews));
        }

        SpanArc stepArc = winArc != null ? winArc : arcs.get(0);
        SpanCandidateGenerator.advanceLexicalHistory(lattice, p2, p1, stepArc, nextLex2);
        rollPrev2 = nextLex2[0];
        rollPrev1 = nextLex2[1];
      }
    }

    // Build decoded span views with exact detokenizer spacing flags so UI highlights match output 1:1
    List<DecodedSpanView> spanViews = new ArrayList<>(decodedPath.size());
    int editedCount = 0;
    boolean[] spaceBefore = computeSpaceBeforeFlags(toks, decodedPath);

    for (int pIdx = 0; pIdx < decodedPath.size(); pIdx++) {
      SpanArc arc = decodedPath.get(pIdx);
      String rawSpan = rawSpanText(rawLine, toks, arc.startIdx, arc.endIdx);
      boolean changed = !rawSpan.equals(arc.normSurface);
      if (changed) {
        editedCount++;
      }
      String changeKind = classifyChangeKind(toks, arc, rawSpan, changed);
      boolean attachPrev = pIdx > 0 && !spaceBefore[pIdx];
      boolean spAfter = (pIdx + 1 < decodedPath.size()) && spaceBefore[pIdx + 1];
      spanViews.add(
          new DecodedSpanView(
              arc.startIdx,
              arc.endIdx,
              rawSpan,
              arc.normSurface,
              changed,
              changeKind,
              attachPrev,
              spAfter,
              slotGateProb[arc.startIdx],
              slotGateSkip[arc.startIdx],
              winLogitByStart[arc.startIdx],
              winProbByStart[arc.startIdx],
              decodeSources(arc.sourceMask)));
    }

    String recon = NormV2Types.reconstructText(toks, decodedPath);
    return new LineTrace(lineIndex, rawLine, recon, n, editedCount, spanViews, slotList);
  }

  private static boolean[] computeSpaceBeforeFlags(List<SpanToken> toks, List<SpanArc> path) {
    boolean[] spaceBefore = new boolean[path.size()];
    StringBuilder acc = new StringBuilder();
    for (int k = 0; k < path.size(); k++) {
      String partial = NormV2Types.reconstructText(toks, path.subList(0, k + 1));
      if (k == 0) {
        spaceBefore[k] = false;
      } else {
        spaceBefore[k] = partial.length() > acc.length() && partial.charAt(acc.length()) == ' ';
      }
      acc.setLength(0);
      acc.append(partial);
    }
    return spaceBefore;
  }

  private static String rawSpanText(
      String rawLine, List<SpanToken> toks, int startIdx, int endIdx) {
    SpanToken first = toks.get(startIdx);
    SpanToken last = toks.get(Math.min(toks.size() - 1, endIdx - 1));
    if (first.start() >= 0
        && last.end() >= first.start()
        && last.end() + 1 <= rawLine.length()) {
      return rawLine.substring(first.start(), last.end() + 1);
    }
    StringBuilder sb = new StringBuilder();
    for (int i = startIdx; i < endIdx && i < toks.size(); i++) {
      if (i > startIdx && !toks.get(i).adjacentToPrev()) {
        sb.append(' ');
      }
      sb.append(toks.get(i).surface());
    }
    return sb.toString();
  }

  private static String describeSpanKind(SpanArc arc) {
    int spanLen = arc.endIdx - arc.startIdx;
    if (spanLen >= 2) {
      return spanLen + ":1 Merge";
    }
    if (arc.wordCount >= 2) {
      return "1:" + arc.wordCount + " Split";
    }
    return "1:1";
  }

  private static String classifyChangeKind(
      List<SpanToken> toks, SpanArc arc, String rawSpan, boolean changed) {
    if (!changed) {
      return "keep";
    }
    if (arc.endIdx - arc.startIdx >= 2) {
      return "merge";
    }
    if (arc.wordCount >= 2) {
      return "split";
    }
    if (arc.apostropheAdded || arc.apostropheRemoved) {
      return "apostrophe";
    }
    if (arc.endIdx == arc.startIdx + 1
        && toks.get(arc.startIdx).lower().equals(arc.normLower)) {
      return "casing";
    }
    return "rewrite";
  }

  public static List<String> decodeSources(int mask) {
    List<String> out = new ArrayList<>(4);
    if ((mask & NormV2Types.SRC_IDENTITY) != 0) out.add("Identity");
    if ((mask & NormV2Types.SRC_PARALLEL_1TO1) != 0) out.add("Parallel 1:1");
    if ((mask & NormV2Types.SRC_PARALLEL_SPLIT) != 0) out.add("Parallel Split");
    if ((mask & NormV2Types.SRC_PARALLEL_MERGE) != 0) out.add("Parallel Merge");
    if ((mask & NormV2Types.SRC_FORMAL_MORPH) != 0) out.add("Formal Morph");
    if ((mask & NormV2Types.SRC_INFORMAL_EXACT) != 0) out.add("Informal Exact");
    if ((mask & NormV2Types.SRC_INFORMAL_MORPH) != 0) out.add("Informal Morph");
    if ((mask & NormV2Types.SRC_DEASCIIFIER) != 0) out.add("Deasciifier");
    if ((mask & NormV2Types.SRC_SUFFIX_RULE) != 0) out.add("Suffix Rule");
    if ((mask & NormV2Types.SRC_OOV_APOSTROPHE) != 0) out.add("TDK Apostrophe");
    if ((mask & NormV2Types.SRC_APOSTROPHE_DROP) != 0) out.add("Apostrophe Drop");
    if ((mask & NormV2Types.SRC_ELONGATION) != 0) out.add("Elongation");
    if ((mask & NormV2Types.SRC_SPELLCHECK) != 0) out.add("CSR Spellcheck");
    if ((mask & NormV2Types.SRC_CLITIC_SPLIT) != 0) out.add("Clitic/Compound Split");
    if ((mask & NormV2Types.SRC_COMPOUND_MERGE) != 0) out.add("Compound/Case Merge");
    if ((mask & NormV2Types.SRC_NUMERAL_JOIN) != 0) out.add("Numeral Join");
    if ((mask & NormV2Types.SRC_SHORTHAND_ABJAD) != 0) out.add("Shorthand/Abjad");
    return out;
  }

  private static float round3(float v) {
    if (Float.isNaN(v) || Float.isInfinite(v)) {
      return 0.0f;
    }
    return Math.round(v * 1000.0f) / 1000.0f;
  }

  private static float round4(float v) {
    if (Float.isNaN(v) || Float.isInfinite(v)) {
      return 0.0f;
    }
    return Math.round(v * 10000.0f) / 10000.0f;
  }
}
