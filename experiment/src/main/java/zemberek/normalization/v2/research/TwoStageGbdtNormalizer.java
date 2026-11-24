package zemberek.normalization.v2.research;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import zemberek.normalization.v2.research.NormV2Types.AlignedSentence;
import zemberek.normalization.v2.research.NormV2Types.PreparedLattice;
import zemberek.normalization.v2.research.NormV2Types.SentenceLattice;
import zemberek.normalization.v2.research.NormV2Types.SpanArc;
import zemberek.normalization.v2.research.NormV2Types.SpanToken;
import zemberek.normalization.v2.research.NormV2Types.TextNormalizer;
import zemberek.normalization.v2.research.ObliviousGbdt.HyperParams;
import zemberek.normalization.v2.research.ParallelCorpusMiner.RewriteEntry;

/**
 * Normalizer 2 ({@code v2.3}): Two-Stage Selective {@link ObliviousGbdt} Normalizer.
 *
 * <p><b>Acronym Glossary:</b>
 * <ul>
 *   <li><b>GBDT</b> ({@code Gradient Boosted Decision Trees}): Ensemble of shallow regression trees
 *       trained sequentially via second-order gradient descent (Newton boosting) so each tree
 *       corrects the residual log-odds errors of earlier trees. Here we use <i>Oblivious
 *       (Symmetric)</i> trees ({@code CatBoost}-style), where every node at the same tree depth
 *       tests the exact same {@code (feature, threshold)} split, turning each depth-{@code d} tree
 *       into a branch-minimal {@code 2^d} lookup table (~115 KB total for 440 trees).
 *   <li><b>MEMM</b> ({@code Maximum Entropy Markov Model}) &amp; <b>DP</b> ({@code Dynamic
 *       Programming}): Outgoing candidate logits at each token boundary are locally normalized via
 *       {@code logSoftmax} into conditional log-probabilities {@code log P(arc | history, x)} so
 *       multi-token ({@code 2:1} merge) and single-token ({@code 1:1}) paths compare fairly without
 *       arc-count bias during left-to-right span DP ({@link #decodeSpanDp}).
 *   <li><b>LLR</b> ({@code Log-Likelihood Ratio}): {@code log P(candidate_span) - log P(raw_span)}
 *       evaluated across identical left and right lexical boundaries ({@code > 0} favors editing).
 *   <li><b>LOO</b> ({@code Leave-One-Sentence-Out}): Subtracts the current training sentence's own
 *       counts from mined parallel tables during training so 1-count words mimic unseen test words.
 *   <li><b>OOF</b> ({@code Out-Of-Fold}): {@code K}-fold cross-fitting ({@link
 *       #populateOutOfFoldScores}) used to produce unbiased GBDT log-probabilities on held-out
 *       folds when training the {@code Hybrid} Perceptron.
 *   <li><b>OOV</b> ({@code Out-Of-Vocabulary}) &amp; <b>TDK</b> ({@code Türk Dil Kurumu}, Turkish
 *       Language Association standard orthography / apostrophe rules).
 * </ul>
 *
 * <p><b>Two-Stage Architecture:</b>
 * <ul>
 *   <li><b>Stage 1 ({@code KeepGatekeeper})</b>: {@code 80} depth-{@code 5} symmetric trees
 *       predicting {@code P(needs_edit | token, context)} from {@link #GATE_DIM} fast token
 *       features; high-confidence clean tokens skip heavy FST/spellcheck expansion.
 *   <li><b>Stage 2 ({@code CandidateReranker})</b>: {@code 360} depth-{@code 6} symmetric trees
 *       scoring candidate arcs over {@link #RANK_DIM} continuous features, decoded via exact
 *       Semi-Markov MEMM span dynamic programming.
 * </ul>
 */
public final class TwoStageGbdtNormalizer implements TextNormalizer {

  /** Number of fast token-level continuous features fed to Stage-1 {@code KeepGatekeeper}. */
  public static final int GATE_DIM = 16;

  /** Number of span- and context-level continuous features fed to Stage-2 {@code CandidateReranker}. */
  public static final int RANK_DIM = 46;

  // ===============================================================================================
  // Stage-1 KeepGatekeeper Feature Slots (0 .. GATE_DIM - 1)
  // Fast O(1) signals estimating P(token i requires normalization) before full candidate expansion.
  // ===============================================================================================

  /** Lowercase character length of raw token {@code t_i}. */
  private static final int G_WORD_LEN = 0;

  /** {@code 1.0} if raw token explicitly contains Turkish diacritics ({@code ç, ğ, ı, ö, ş, ü}) and is not ALL-CAPS ASCII {@code 'I'}. */
  private static final int G_HAS_TURKISH_DIAC = 1;

  /** {@code 1.0} if raw token contains ASCII letters ({@code c, g, i, o, s, u} or ALL-CAPS {@code 'I'}) that often omit Turkish diacritics. */
  private static final int G_HAS_ASCII_AMBIG = 2;

  /** {@code 1.0} if raw token contains adjacent repeated letters (e.g., expressive elongation {@code "çooook"}, {@code "yaaa"}). */
  private static final int G_HAS_REPEATED_CHARS = 3;

  /** {@code 1.0} if raw token contains any ASCII digit ({@code 0-9}, e.g. {@code "16gb"}, {@code "2024te"}). */
  private static final int G_CONTAINS_DIGIT = 4;

  /** Ordinal of {@link NormV2Types.CasingShape} ({@code LOWER=0, TITLE=1, UPPER=2, MIXED=3, NON_ALPHA=4}). */
  private static final int G_CASING_SHAPE = 5;

  /** Length-normalized character 4-gram log-probability of {@code t_i} under clean Turkish phonotactics. */
  private static final int G_CHAR_PHONOTACTICS = 6;

  /** Smoothed empirical {@code P(keep_unchanged | raw_lower)} from parallel corpus (LOO-adjusted during training; {@code 0.5} if unseen). */
  private static final int G_PARALLEL_KEEP_PROB = 7;

  /** {@code log(1 + raw_count)} in parallel training corpus (LOO-adjusted during training). */
  private static final int G_PARALLEL_LOG_CNT = 8;

  /** Clamped unigram log-probability {@code log P(t_i)} in Zemberek's 2-gram {@code SmoothLm} ({@code [-20, 0]}). */
  private static final int G_SMOOTH_UNIGRAM = 9;

  /** Clamped left-context bigram {@code log P(t_i | t_{i-1})} in {@code SmoothLm}. */
  private static final int G_SMOOTH_PREV_BIGRAM = 10;

  /** Clamped right-context bigram {@code log P(w_next | t_i)} in {@code SmoothLm} (skipping punctuation). */
  private static final int G_SMOOTH_NEXT_BIGRAM = 11;

  /** {@code 1.0} if {@code t_i} ends with a splittable clitic pattern ({@code -de/-da/-te/-ta/-mi/-mı/-mu/-mü/-ki}, {@code oda}, {@code omu}). */
  private static final int G_CLITIC_TAIL_HINT = 12;

  /** {@code 1.0} if {@code (t_i, t_{i+1})} is a candidate {@code 2:1} merge (in {@code parallel2To1} or followed by {@code de/da/ki/'}). */
  private static final int G_NEXT_MERGE_HINT = 13;

  /** {@code 1.0} if {@code t_i} has a strict, non-proper, non-runtime Zemberek morphological parse. */
  private static final int G_HAS_STRICT_MORPH = 14;

  /** Unigram log-probability {@code log P(t_i)} in the 1.8M-sentence {@code CleanWordLm}. */
  private static final int G_CLEAN_UNIGRAM = 15;

  // ===============================================================================================
  // Stage-2 CandidateReranker Feature Slots (0 .. RANK_DIM - 1)
  // Rich lexical, morphological, orthographic, and multi-span LM features for each SpanArc [i, j).
  // ===============================================================================================

  /** {@code 1.0} if candidate is a {@code 1:1} span identical to the raw token including exact casing. */
  private static final int R_IS_ID_SURF = 0;

  /** {@code 1.0} if candidate is a {@code 1:1} span identical to the raw token in Turkish lowercase (casing-only or unchanged). */
  private static final int R_IS_ID_LOWER = 1;

  /** Smoothed empirical rewrite/merge probability {@code P(cand | raw_span)} from parallel corpus (LOO-adjusted in training). */
  private static final int R_PARALLEL_PROB = 2;

  /** {@code log(1 + count(raw_span -> cand))} in parallel training corpus (LOO-adjusted in training). */
  private static final int R_PARALLEL_LOG_CNT = 3;

  /** Smoothed empirical probability {@code P(keep_unchanged | raw_token)} of the starting raw token. */
  private static final int R_RAW_KEEP_PROB = 4;

  /** {@code log(1 + count(raw_token))} of the starting raw token in parallel training corpus. */
  private static final int R_RAW_LOG_CNT = 5;

  /** Empirical precision of the induced suffix/orthographic rule that proposed this arc ({@code 0.0} if none). */
  private static final int R_SUFFIX_PRECISION = 6;

  /** Weighted character Levenshtein edit cost between raw span and candidate (Q-keyboard &amp; diacritic weighted). */
  private static final int R_EDIT_COST = 7;

  /** Count of vowel/consonant positions differing only in Turkish diacritics ({@code c<->ç, g<->ğ, ı<->i, o<->ö, s<->ş, u<->ü}). */
  private static final int R_DIACRITIC_DIFFS = 8;

  /** Character length difference {@code len(cand_lower) - len(raw_span_lower)} (positive for expansions like {@code slm -> selam}). */
  private static final int R_LEN_DELTA = 9;

  /** {@code 1.0} if proposed/verified by formal Zemberek morphology ({@link NormV2Types#SRC_FORMAL_MORPH}). */
  private static final int R_SRC_FORMAL_MORPH = 10;

  /** {@code 1.0} if proposed by diacritic-tolerant informal morphology ({@link NormV2Types#SRC_INFORMAL_MORPH}). */
  private static final int R_SRC_INFORMAL_MORPH = 11;

  /** {@code 1.0} if proposed by exact-vowel informal morphology ({@link NormV2Types#SRC_INFORMAL_EXACT}, e.g. {@code geliyo -> geliyor}). */
  private static final int R_SRC_INFORMAL_EXACT = 12;

  /** {@code 1.0} if proposed by {@code LatticeDeasciifier} or diacritic-restoring split ({@link NormV2Types#SRC_DEASCIIFIER}). */
  private static final int R_SRC_DEASCIIFIER = 13;

  /** {@code 1.0} if proposed by a mined productive suffix/hypocoristic rule ({@link NormV2Types#SRC_SUFFIX_RULE}). */
  private static final int R_SRC_SUFFIX_RULE = 14;

  /** {@code 1.0} if proposed by the guarded CSR edit-distance spellchecker ({@link NormV2Types#SRC_SPELLCHECK}). */
  private static final int R_SRC_SPELLCHECK = 15;

  /** {@code 1.0} if candidate splits one raw token into multiple words ({@code 1:2+}, e.g. {@code bende -> ben de}). */
  private static final int R_IS_SPLIT = 16;

  /** {@code 1.0} if candidate merges {@code 2} or {@code 3} raw tokens into one span ({@code [i, i+2)} or {@code [i, i+3)}). */
  private static final int R_IS_MERGE = 17;

  /** {@code 1.0} if candidate inserts a TDK apostrophe or joins a numeral suffix ({@code 2024'te}, {@code YouTube'da}). */
  private static final int R_IS_APOS_OR_NUM = 18;

  /** {@code 1.0} if candidate removes an erroneous apostrophe ({@code azerice'ye -> Azericeye}, {@code altın'ın -> altının}). */
  private static final int R_IS_APOS_DROP = 19;

  /** Forward 2-gram {@code SmoothLm} score {@code log P(first_word | prev_decoded_word)}. */
  private static final int R_SMOOTH_FWD2 = 20;

  /** Right-lookahead 2-gram {@code SmoothLm} score {@code log P(next_lex_word | last_word)}. */
  private static final int R_SMOOTH_RIGHT2 = 21;

  /** Unigram {@code SmoothLm} score {@code log P(last_word)}. */
  private static final int R_SMOOTH_UNI = 22;

  /** Average internal 2-gram {@code SmoothLm} transition score across multi-word {@code 1:2+} split candidates. */
  private static final int R_SMOOTH_INTERNAL = 23;

  /** Exact boundary-to-boundary 2-gram {@code SmoothLm} Span LLR: {@code log P(cand_span, w_next | h) - log P(raw_span, w_next | h)}. */
  private static final int R_SMOOTH_SPAN_LLR = 24;

  /** Slot-relative {@code SmoothLm} margin: {@code R_SMOOTH_SPAN_LLR - max_{a' in slot} R_SMOOTH_SPAN_LLR(a')} ({@code 0.0} = best in slot). */
  private static final int R_SMOOTH_REL_MARGIN = 25;

  /** Forward 3-gram {@code CleanWordLm} score {@code log P(first_word | prev2, prev1)}. */
  private static final int R_CLEAN_FWD3 = 26;

  /** Right-lookahead 3-gram {@code CleanWordLm} score {@code log P(next_lex_word | penult, last_word)}. */
  private static final int R_CLEAN_RIGHT3 = 27;

  /** Unigram 1.8M-sentence {@code CleanWordLm} score {@code log P(last_word)}. */
  private static final int R_CLEAN_UNI = 28;

  /** Exact boundary-to-boundary 3-gram {@code CleanWordLm} Span LLR: {@code log P_clean(cand_span, w_next | h) - log P_clean(raw_span, w_next | h)}. */
  private static final int R_CLEAN_SPAN_LLR = 29;

  /** Slot-relative 3-gram {@code CleanWordLm} margin: {@code R_CLEAN_SPAN_LLR - max_{a' in slot} R_CLEAN_SPAN_LLR(a')} ({@code <= 0}). */
  private static final int R_CLEAN_REL_MARGIN = 30;

  /** Slot-relative parallel probability margin: {@code R_PARALLEL_PROB - max_{a' in slot} R_PARALLEL_PROB(a')} ({@code <= 0}). */
  private static final int R_PARALLEL_REL_MARGIN = 31;

  /** Gain in character 4-gram Turkish phonotactic log-probability: {@code phono(cand) - phono(raw)}. */
  private static final int R_PHONO_GAIN = 32;

  /** Fraction of alphabetic words in the sentence that look ASCII-typed (missing Turkish diacritics, {@code [0, 1]}). */
  private static final int R_SENT_ASCII_RATIO = 33;

  /** Fraction of alphabetic words in the sentence that fail strict morphological analysis ({@code [0, 1]} noise density). */
  private static final int R_SENT_OOV_RATIO = 34;

  /** Overt subject pronoun vs. finite verb person agreement ({@code +1.0} match, {@code -1.0} clash, {@code 0.0} N/A). */
  private static final int R_SUBJ_VERB_AGREE = 35;

  /** Composite TDK orthographic prior ({@code -1.5} if stripping explicit diacritic; {@code +0.5..+1.0} for valid proper casing/apostrophe). */
  private static final int R_ORTHO_PRIOR = 36;

  /** {@code 1.0} if the raw starting token already has a valid common-word morphological analysis. */
  private static final int R_RAW_REG_MORPH = 37;

  /** {@code 1.0} if the candidate's words all have valid morphological analyses (or strong clean-corpus attestation). */
  private static final int R_ARC_REG_MORPH = 38;

  /** Clitic split family code ({@code 0=none, 1=de/da, 2=question mı/mi/mu/mü, 3=conjunction ki, 4=other multi-word}). */
  private static final int R_CLITIC_TYPE = 39;

  /** Syntactic category of the head word ({@link NormV2Types#HEAD_SYNTAX_FINITE_OR_CONVERB}, {@code PRON_ADV_CONJ}, {@code NOMINAL}). */
  private static final int R_HEAD_SYNTAX_CLASS = 40;

  /** Question-clitic punctuation agreement ({@code +1.0} if {@code mı/mi} split in a {@code '?'}-sentence, {@code -0.35} without {@code '?'}). */
  private static final int R_QUESTION_CLITIC_AGREE = 41;

  /** Clitic-transparency skip-bigram LLR comparing {@code P(w_next | host)} vs {@code P(w_next | host+locative)} on {@code de/da} splits &amp; merges. */
  private static final int R_CLITIC_SKIP_LLR = 42;

  /** Encoded 16-bit Zemberek {@code (PrimaryPos, Case/Tense)} morphological tag of the candidate's final word. */
  private static final int R_COARSE_MORPH_TAG = 43;

  /** {@code 1.0} if proposed by curated chat shorthand, compositional shorthand+suffix, or {@code >=5}-consonant Abjad index ({@link NormV2Types#SRC_SHORTHAND_ABJAD}). */
  private static final int R_SRC_SHORTHAND_ABJAD = 44;

  /** Guarded cross-token 4-way vowel harmony ({@code +1.0 = match, -1.0 = mismatch, 0.0 = neutral}) on standalone question clitics. */
  private static final int R_QUESTION_VOWEL_HARMONY = 45;

  /** Hyperparameters for Stage-1 {@code KeepGatekeeper} and Stage-2 {@code CandidateReranker} training. */
  public static final class TrainConfig {
    /** Stage-1 gatekeeper ensemble: 80 depth-5 oblivious trees (32 leaves/tree). */
    public HyperParams gateParams = new HyperParams(80, 5, 0.09f, 3.0f, 96);

    /** Stage-2 candidate reranker ensemble: 360 depth-6 oblivious trees (64 leaves/tree). */
    public HyperParams rankParams = new HyperParams(360, 6, 0.07f, 4.0f, 112);

    /** Extra training sample weight (>1.0) on slots whose raw token is already clean, protecting clean prose. */
    public float cleanProtectionWeight = 1.35f;

    /** Gatekeeper probability cutoff below which clean tokens skip heavy morphological/spellcheck expansion. */
    public float gateSkipThreshold = 0.022f;

    /** Whether Stage-1 {@code KeepGatekeeper} pruning is active during {@link #normalize(String)}. */
    public boolean enableGatekeeperAtRuntime = true;
  }

  private final String modelName;
  private final SpanCandidateGenerator generator;
  private final ObliviousGbdt keepGatekeeper;
  private final ObliviousGbdt candidateReranker;
  private final float gateSkipThreshold;
  private final boolean enableGatekeeper;

  public TwoStageGbdtNormalizer(
      String modelName,
      SpanCandidateGenerator generator,
      ObliviousGbdt keepGatekeeper,
      ObliviousGbdt candidateReranker,
      float gateSkipThreshold,
      boolean enableGatekeeper) {
    this.modelName = modelName;
    this.generator = generator;
    this.keepGatekeeper = keepGatekeeper;
    this.candidateReranker = candidateReranker;
    this.gateSkipThreshold = gateSkipThreshold;
    this.enableGatekeeper = enableGatekeeper;
  }

  @Override
  public String name() {
    return modelName;
  }

  public long modelRamBytes() {
    return (keepGatekeeper != null ? keepGatekeeper.ramBytesUsed() : 0L)
        + candidateReranker.ramBytesUsed();
  }

  public TwoStageGbdtNormalizer withGatekeeper(boolean enabled, String newName) {
    return new TwoStageGbdtNormalizer(
        newName, generator, keepGatekeeper, candidateReranker, gateSkipThreshold, enabled);
  }

  SpanCandidateGenerator generator() {
    return generator;
  }

  ObliviousGbdt keepGatekeeper() {
    return keepGatekeeper;
  }

  ObliviousGbdt candidateReranker() {
    return candidateReranker;
  }

  float gateSkipThreshold() {
    return gateSkipThreshold;
  }

  boolean isGatekeeperEnabled() {
    return enableGatekeeper;
  }

  @Override
  public String normalize(String rawSentence) {
    if (rawSentence == null || rawSentence.isEmpty()) {
      return "";
    }
    float[] gateBuf = enableGatekeeper && keepGatekeeper != null ? new float[GATE_DIM] : null;
    SentenceLattice lattice =
        generator.buildLattice(
            rawSentence,
            gateBuf == null ? null : (toks, idx) -> shouldGateSkip(toks, idx, gateBuf));
    if (lattice.tokens().isEmpty()) {
      return rawSentence;
    }
    List<SpanArc> path = decodeSpanDp(lattice);
    return NormV2Types.reconstructText(lattice.tokens(), path);
  }

  boolean shouldGateSkip(List<SpanToken> toks, int idx, float[] gateBuf) {
    SpanToken t = toks.get(idx);
    if (t.isImmutable()) {
      return true;
    }
    extractGateFeatures(toks, idx, false, false, generator, gateBuf);
    // Never skip potential clitic splits, apostrophe errors, or 2:1 merges
    if (gateBuf[G_CLITIC_TAIL_HINT] > 0.5f
        || gateBuf[G_NEXT_MERGE_HINT] > 0.5f
        || t.surface().indexOf('\'') >= 0) {
      return false;
    }
    return keepGatekeeper.predictProb(gateBuf) < gateSkipThreshold;
  }

  /** Populates {@code arc.cachedGbdtScore} across all arcs in {@code lattice} for Hybrid mode. */
  public void populateLatticeGbdtScores(SentenceLattice lattice) {
    populateLatticeGbdtScores(lattice, null);
  }

  public void populateLatticeGbdtScores(SentenceLattice lattice, SpanArc[] looGoldByStart) {
    int n = lattice.tokens().size();
    float[] bestScore = new float[n + 1];
    String[] bestPrev2Word = new String[n + 1];
    String[] bestPrev1Word = new String[n + 1];
    for (int i = 1; i <= n; i++) {
      bestScore[i] = Float.NEGATIVE_INFINITY;
    }
    bestScore[0] = 0.0f;

    float[] featBuf = new float[RANK_DIM];
    float[] logits = new float[16];
    float[][] arcLm6 = new float[24][6];
    String[] nextLex2 = new String[2];
    boolean loo = looGoldByStart != null;

    for (int i = 0; i < n; i++) {
      List<SpanArc> arcs = lattice.outgoing()[i];
      if (arcs.isEmpty()) {
        continue;
      }
      String prev2Word = bestPrev2Word[i];
      String prev1Word = bestPrev1Word[i];
      if (arcs.size() == 1) {
        SpanArc only = arcs.get(0);
        only.cachedGbdtScore = 0.0f;
        if (only.endIdx <= n && bestScore[i] > bestScore[only.endIdx]) {
          bestScore[only.endIdx] = bestScore[i];
          SpanCandidateGenerator.advanceLexicalHistory(
              lattice, prev2Word, prev1Word, only, nextLex2);
          bestPrev2Word[only.endIdx] = nextLex2[0];
          bestPrev1Word[only.endIdx] = nextLex2[1];
        }
        continue;
      }
      if (logits.length < arcs.size()) {
        logits = new float[arcs.size() + 8];
      }
      if (arcLm6.length < arcs.size()) {
        arcLm6 = new float[arcs.size() + 8][6];
      }
      boolean rawWasKeep =
          loo
              && looGoldByStart[i] != null
              && looGoldByStart[i].endIdx == i + 1
              && looGoldByStart[i].normLower.equals(lattice.tokens().get(i).lower());
      SlotMaxima maxima =
          computeSlotLmAndMaxima(lattice, arcs, prev2Word, prev1Word, generator, arcLm6);

      float maxLogit = Float.NEGATIVE_INFINITY;
      for (int a = 0; a < arcs.size(); a++) {
        SpanArc arc = arcs.get(a);
        extractRankFeatures(
            lattice, prev1Word, arc, arcLm6[a], maxima, loo, rawWasKeep, generator, featBuf);
        float logit = candidateReranker.predictLogit(featBuf);
        logits[a] = logit;
        if (logit > maxLogit) {
          maxLogit = logit;
        }
      }
      float logDenom = logSumExp(logits, arcs.size(), maxLogit);
      for (int a = 0; a < arcs.size(); a++) {
        SpanArc arc = arcs.get(a);
        float logProb = logits[a] - logDenom;
        arc.cachedGbdtScore = Math.max(-6.0f, logProb);
        float candPathScore = bestScore[i] + logProb;
        if (arc.endIdx <= n && candPathScore > bestScore[arc.endIdx]) {
          bestScore[arc.endIdx] = candPathScore;
          SpanCandidateGenerator.advanceLexicalHistory(
              lattice, prev2Word, prev1Word, arc, nextLex2);
          bestPrev2Word[arc.endIdx] = nextLex2[0];
          bestPrev1Word[arc.endIdx] = nextLex2[1];
        }
      }
    }
  }

  List<SpanArc> decodeSpanDp(SentenceLattice lattice) {
    int n = lattice.tokens().size();
    float[] bestScore = new float[n + 1];
    SpanArc[] bestIncoming = new SpanArc[n + 1];
    String[] bestPrev2Word = new String[n + 1];
    String[] bestPrev1Word = new String[n + 1];
    for (int i = 1; i <= n; i++) {
      bestScore[i] = Float.NEGATIVE_INFINITY;
    }
    bestScore[0] = 0.0f;

    float[] featBuf = new float[RANK_DIM];
    float[] logits = new float[16];
    float[][] arcLm6 = new float[24][6];
    String[] nextLex2 = new String[2];

    for (int i = 0; i < n; i++) {
      if (bestScore[i] == Float.NEGATIVE_INFINITY) {
        continue;
      }
      String prev2Word = bestPrev2Word[i];
      String prev1Word = bestPrev1Word[i];
      List<SpanArc> arcs = lattice.outgoing()[i];
      if (arcs.isEmpty()) {
        continue;
      }
      if (arcs.size() == 1) {
        SpanArc only = arcs.get(0);
        if (only.endIdx <= n && bestScore[i] > bestScore[only.endIdx]) {
          bestScore[only.endIdx] = bestScore[i];
          bestIncoming[only.endIdx] = only;
          SpanCandidateGenerator.advanceLexicalHistory(lattice, prev2Word, prev1Word, only, nextLex2);
          bestPrev2Word[only.endIdx] = nextLex2[0];
          bestPrev1Word[only.endIdx] = nextLex2[1];
        }
        continue;
      }
      if (logits.length < arcs.size()) {
        logits = new float[arcs.size() + 8];
      }
      if (arcLm6.length < arcs.size()) {
        arcLm6 = new float[arcs.size() + 8][6];
      }
      SlotMaxima maxima =
          computeSlotLmAndMaxima(lattice, arcs, prev2Word, prev1Word, generator, arcLm6);
      float maxLogit = Float.NEGATIVE_INFINITY;
      for (int a = 0; a < arcs.size(); a++) {
        SpanArc arc = arcs.get(a);
        extractRankFeatures(
            lattice, prev1Word, arc, arcLm6[a], maxima, false, false, generator, featBuf);
        float logit = candidateReranker.predictLogit(featBuf);
        logits[a] = logit;
        if (logit > maxLogit) {
          maxLogit = logit;
        }
      }
      // Exact Semi-Markov MEMM local log-softmax over outgoing partition A(i)
      float logDenom = logSumExp(logits, arcs.size(), maxLogit);
      for (int a = 0; a < arcs.size(); a++) {
        SpanArc arc = arcs.get(a);
        float logProb = logits[a] - logDenom;
        float candPathScore = bestScore[i] + logProb;
        if (arc.endIdx <= n && candPathScore > bestScore[arc.endIdx]) {
          bestScore[arc.endIdx] = candPathScore;
          bestIncoming[arc.endIdx] = arc;
          SpanCandidateGenerator.advanceLexicalHistory(lattice, prev2Word, prev1Word, arc, nextLex2);
          bestPrev2Word[arc.endIdx] = nextLex2[0];
          bestPrev1Word[arc.endIdx] = nextLex2[1];
        }
      }
    }

    if (bestIncoming[n] == null) {
      List<SpanArc> fallback = new ArrayList<>(n);
      for (int i = 0; i < n; i++) {
        if (!lattice.outgoing()[i].isEmpty()) {
          fallback.add(lattice.outgoing()[i].get(0));
        }
      }
      return fallback;
    }

    List<SpanArc> path = new ArrayList<>(n);
    int cur = n;
    while (cur > 0 && bestIncoming[cur] != null) {
      SpanArc a = bestIncoming[cur];
      path.add(a);
      cur = a.startIdx;
    }
    Collections.reverse(path);
    return path;
  }

  private static float logSumExp(float[] vals, int len, float maxVal) {
    double sum = 0.0;
    for (int i = 0; i < len; i++) {
      sum += Math.exp(vals[i] - maxVal);
    }
    return maxVal + (float) Math.log(Math.max(1e-12, sum));
  }

  public static TwoStageGbdtNormalizer train(
      String modelName,
      SpanCandidateGenerator generator,
      List<AlignedSentence> trainData,
      TrainConfig cfg) {
    return trainFromPrepared(
        modelName, generator, generator.prepareTrainingLattices(trainData), cfg);
  }

  public static TwoStageGbdtNormalizer trainFromPrepared(
      String modelName,
      SpanCandidateGenerator generator,
      List<PreparedLattice> prepared,
      TrainConfig cfg) {

    FloatBuffer gateX = new FloatBuffer(prepared.size() * 12 * GATE_DIM);
    ByteBuffer gateY = new ByteBuffer(prepared.size() * 12);
    FloatBuffer gateW = new FloatBuffer(prepared.size() * 12);

    FloatBuffer rankX = new FloatBuffer(prepared.size() * 32 * RANK_DIM);
    ByteBuffer rankY = new ByteBuffer(prepared.size() * 32);
    FloatBuffer rankW = new FloatBuffer(prepared.size() * 32);

    float[] gateTmp = new float[GATE_DIM];
    float[] rankTmp = new float[RANK_DIM];
    float[][] arcLm6 = new float[32][6];
    String[] nextLex2 = new String[2];

    for (PreparedLattice pl : prepared) {
      SentenceLattice lat = pl.lattice();
      List<SpanToken> toks = lat.tokens();
      int n = toks.size();
      List<SpanArc> oraclePath = pl.oraclePath();
      SpanArc[] goldByStart = pl.goldByStart();

      boolean[] tokenEdited = new boolean[n];
      for (SpanArc ga : oraclePath) {
        if (ga.endIdx - ga.startIdx > 1
            || !ga.normSurface.equals(toks.get(ga.startIdx).surface())) {
          for (int k = ga.startIdx; k < ga.endIdx && k < n; k++) {
            tokenEdited[k] = true;
          }
        }
      }

      // 1. Stage-1 Gatekeeper rows (every mutable token, with LOO parallel counts)
      for (int i = 0; i < n; i++) {
        SpanToken t = toks.get(i);
        if (t.isImmutable()) {
          continue;
        }
        boolean rawKeepLow =
            goldByStart[i] != null
                && goldByStart[i].endIdx == i + 1
                && goldByStart[i].normLower.equals(t.lower());
        extractGateFeatures(toks, i, true, rawKeepLow, generator, gateTmp);
        gateX.append(gateTmp);
        gateY.append((byte) (tokenEdited[i] ? 1 : 0));
        gateW.append(tokenEdited[i] ? 3.2f : 1.0f);
      }

      // 2. Stage-2 Reranker rows along oraclePath + competing arcs
      String prev2GoldWord = null;
      String prev1GoldWord = null;
      for (SpanArc goldArc : oraclePath) {
        int i = goldArc.startIdx;
        SpanToken rawTok = toks.get(i);
        List<SpanArc> cands = lat.outgoing()[i];
        if (cands.size() <= 1) {
          SpanCandidateGenerator.advanceLexicalHistory(
              lat, prev2GoldWord, prev1GoldWord, goldArc, nextLex2);
          prev2GoldWord = nextLex2[0];
          prev1GoldWord = nextLex2[1];
          continue;
        }
        if (arcLm6.length < cands.size()) {
          arcLm6 = new float[cands.size() + 8][6];
        }
        boolean rawWasClean =
            (goldArc.endIdx == i + 1) && goldArc.normLower.equals(rawTok.lower());
        float slotWeight = rawWasClean ? cfg.cleanProtectionWeight : 1.0f;
        SlotMaxima maxima =
            computeSlotLmAndMaxima(lat, cands, prev2GoldWord, prev1GoldWord, generator, arcLm6);

        for (int a = 0; a < cands.size(); a++) {
          SpanArc cand = cands.get(a);
          extractRankFeatures(
              lat, prev1GoldWord, cand, arcLm6[a], maxima, true, rawWasClean, generator, rankTmp);
          boolean isGold =
              cand.endIdx == goldArc.endIdx && cand.normSurface.equals(goldArc.normSurface);
          rankX.append(rankTmp);
          rankY.append((byte) (isGold ? 1 : 0));
          rankW.append(slotWeight);
        }
        SpanCandidateGenerator.advanceLexicalHistory(
            lat, prev2GoldWord, prev1GoldWord, goldArc, nextLex2);
        prev2GoldWord = nextLex2[0];
        prev1GoldWord = nextLex2[1];
      }
    }

    float[] gxArr = gateX.toArray();
    byte[] gyArr = gateY.toArray();
    float[] gwArr = gateW.toArray();
    int gateRows = gateY.size();
    gateX = null;
    gateY = null;
    gateW = null;

    ObliviousGbdt gateModel =
        ObliviousGbdt.trainBinaryClassifier(
            gxArr, gyArr, gwArr, gateRows, GATE_DIM, cfg.gateParams);
    gxArr = null;
    gyArr = null;
    gwArr = null;

    float[] rxArr = rankX.toArray();
    byte[] ryArr = rankY.toArray();
    float[] rwArr = rankW.toArray();
    int rankRows = rankY.size();
    rankX = null;
    rankY = null;
    rankW = null;

    ObliviousGbdt rankModel =
        ObliviousGbdt.trainBinaryClassifier(
            rxArr, ryArr, rwArr, rankRows, RANK_DIM, cfg.rankParams);

    return new TwoStageGbdtNormalizer(
        modelName,
        generator,
        gateModel,
        rankModel,
        cfg.gateSkipThreshold,
        cfg.enableGatekeeperAtRuntime);
  }

  /**
   * Populates {@code arc.cachedGbdtScore} across all {@code prepared} training lattices using
   * strict {@code numFolds}-fold Out-Of-Fold ({@code OOF}) GBDT predictions so downstream stacked
   * models ({@code Hybrid}) never see overfit in-fold GBDT probabilities during training.
   */
  public static void populateOutOfFoldScores(
      SpanCandidateGenerator generator,
      List<PreparedLattice> prepared,
      TrainConfig cfg,
      int numFolds) {
    int totalSents = prepared.size();
    if (totalSents == 0) {
      return;
    }
    int folds = Math.max(2, Math.min(numFolds, totalSents));

    // Extract Stage-2 reranker matrices once across all sentences and slice by fold
    int[] sentRowStart = new int[totalSents];
    int[] sentRowEnd = new int[totalSents];
    FloatBuffer rankX = new FloatBuffer(totalSents * 32 * RANK_DIM);
    ByteBuffer rankY = new ByteBuffer(totalSents * 32);
    FloatBuffer rankW = new FloatBuffer(totalSents * 32);

    float[] rankTmp = new float[RANK_DIM];
    float[][] arcLm6 = new float[32][6];
    String[] nextLex2 = new String[2];

    for (int s = 0; s < totalSents; s++) {
      sentRowStart[s] = rankY.size();
      PreparedLattice pl = prepared.get(s);
      SentenceLattice lat = pl.lattice();
      List<SpanToken> toks = lat.tokens();
      String prev2GoldWord = null;
      String prev1GoldWord = null;
      for (SpanArc goldArc : pl.oraclePath()) {
        int i = goldArc.startIdx;
        List<SpanArc> cands = lat.outgoing()[i];
        if (cands.size() <= 1) {
          SpanCandidateGenerator.advanceLexicalHistory(
              lat, prev2GoldWord, prev1GoldWord, goldArc, nextLex2);
          prev2GoldWord = nextLex2[0];
          prev1GoldWord = nextLex2[1];
          continue;
        }
        if (arcLm6.length < cands.size()) {
          arcLm6 = new float[cands.size() + 8][6];
        }
        boolean rawWasClean =
            (goldArc.endIdx == i + 1) && goldArc.normLower.equals(toks.get(i).lower());
        float slotWeight = rawWasClean ? cfg.cleanProtectionWeight : 1.0f;
        SlotMaxima maxima =
            computeSlotLmAndMaxima(lat, cands, prev2GoldWord, prev1GoldWord, generator, arcLm6);
        for (int a = 0; a < cands.size(); a++) {
          SpanArc cand = cands.get(a);
          extractRankFeatures(
              lat, prev1GoldWord, cand, arcLm6[a], maxima, true, rawWasClean, generator, rankTmp);
          boolean isGold =
              cand.endIdx == goldArc.endIdx && cand.normSurface.equals(goldArc.normSurface);
          rankX.append(rankTmp);
          rankY.append((byte) (isGold ? 1 : 0));
          rankW.append(slotWeight);
        }
        SpanCandidateGenerator.advanceLexicalHistory(
            lat, prev2GoldWord, prev1GoldWord, goldArc, nextLex2);
        prev2GoldWord = nextLex2[0];
        prev1GoldWord = nextLex2[1];
      }
      sentRowEnd[s] = rankY.size();
    }

    float[] allX = rankX.toArray();
    byte[] allY = rankY.toArray();
    float[] allW = rankW.toArray();
    rankX = null;
    rankY = null;
    rankW = null;
    HyperParams oofHp =
        new HyperParams(
            Math.min(260, cfg.rankParams.numTrees),
            cfg.rankParams.depth,
            cfg.rankParams.learningRate,
            cfg.rankParams.l2Reg,
            cfg.rankParams.maxBins);

    for (int f = 0; f < folds; f++) {
      int foldRows = 0;
      for (int s = 0; s < totalSents; s++) {
        if ((s % folds) != f) {
          foldRows += (sentRowEnd[s] - sentRowStart[s]);
        }
      }
      float[] fx = new float[foldRows * RANK_DIM];
      byte[] fy = new byte[foldRows];
      float[] fw = new float[foldRows];
      int dst = 0;
      for (int s = 0; s < totalSents; s++) {
        if ((s % folds) != f) {
          int r0 = sentRowStart[s];
          int len = sentRowEnd[s] - r0;
          if (len > 0) {
            System.arraycopy(allX, r0 * RANK_DIM, fx, dst * RANK_DIM, len * RANK_DIM);
            System.arraycopy(allY, r0, fy, dst, len);
            System.arraycopy(allW, r0, fw, dst, len);
            dst += len;
          }
        }
      }
      ObliviousGbdt foldReranker =
          ObliviousGbdt.trainBinaryClassifier(fx, fy, fw, foldRows, RANK_DIM, oofHp);
      TwoStageGbdtNormalizer foldScorer =
          new TwoStageGbdtNormalizer(
              "OOF-" + f, generator, null, foldReranker, cfg.gateSkipThreshold, false);

      int foldId = f;
      java.util.stream.IntStream.range(0, totalSents)
          .parallel()
          .filter(s -> (s % folds) == foldId)
          .forEach(
              s -> {
                PreparedLattice pl = prepared.get(s);
                foldScorer.populateLatticeGbdtScores(pl.lattice(), pl.goldByStart());
              });
    }
  }

  record SlotMaxima(
      float maxSmoothLlr, float maxCleanLlr, float maxParallelProb, float rawPhono) {}

  static SlotMaxima computeSlotLmAndMaxima(
      SentenceLattice lat,
      List<SpanArc> arcs,
      String prev2Word,
      String prev1Word,
      SpanCandidateGenerator gen,
      float[][] arcLm6Out) {
    SpanToken rawTok = lat.tokens().get(arcs.get(0).startIdx);
    float rawPhono = gen.mined().charPhonotactics.scoreWord(rawTok.lower());

    float maxSmoothLlr = -40.0f;
    float maxCleanLlr = -40.0f;
    float maxPar = 0.0f;
    for (int i = 0; i < arcs.size(); i++) {
      SpanArc a = arcs.get(i);
      float[] lm6 = arcLm6Out[i];
      gen.computeSpanLmComparison(lat, prev2Word, prev1Word, a, lm6);
      float sLlr = lm6[0] - lm6[1];
      float cLlr = lm6[2] - lm6[3];
      if (sLlr > maxSmoothLlr) {
        maxSmoothLlr = sLlr;
      }
      if (cLlr > maxCleanLlr) {
        maxCleanLlr = cLlr;
      }
      if (a.parallelProb > maxPar) {
        maxPar = a.parallelProb;
      }
    }
    return new SlotMaxima(maxSmoothLlr, maxCleanLlr, maxPar, rawPhono);
  }

  static void extractGateFeatures(
      List<SpanToken> toks,
      int i,
      boolean loo,
      boolean looRawWasKeep,
      SpanCandidateGenerator gen,
      float[] out) {
    SpanToken t = toks.get(i);
    String low = t.lower();
    String prev = i > 0 ? toks.get(i - 1).lower() : null;
    String next = SpanCandidateGenerator.nextLexicalWordOrNull(toks, i + 1);
    RewriteEntry re = gen.mined().parallel1ToN.get(low);
    int effTot = re == null ? 0 : (loo ? Math.max(0, re.totalCount() - 1) : re.totalCount());
    float effKeepProb =
        (re == null || effTot == 0)
            ? 0.50f
            : (loo ? re.looKeepProb(looRawWasKeep) : re.keepProb());

    boolean cliticTail =
        low.equals("oda")
            || low.equals("omu")
            || SpanCandidateGenerator.isStandaloneQuestionClitic(low)
            || (low.length() >= 4
                && (low.endsWith("de")
                    || low.endsWith("da")
                    || low.endsWith("te")
                    || low.endsWith("ta")
                    || low.endsWith("mi")
                    || low.endsWith("mı")
                    || low.endsWith("mu")
                    || low.endsWith("mü")
                    || low.endsWith("ki")));
    String nextSurfLow = i + 1 < toks.size() ? toks.get(i + 1).lower() : "";
    boolean nextMerge =
        i + 1 < toks.size()
            && (gen.mined().parallel2To1.containsKey(low + "\t" + nextSurfLow)
                || nextSurfLow.equals("de")
                || nextSurfLow.equals("da")
                || nextSurfLow.equals("ki")
                || nextSurfLow.equals("'"));

    boolean upperAmbI = t.hasUpperAsciiAmbiguousI();
    out[G_WORD_LEN] = low.length();
    out[G_HAS_TURKISH_DIAC] =
        (!upperAmbI && SpanCandidateGenerator.hasExplicitTurkishDiacritics(low)) ? 1.0f : 0.0f;
    out[G_HAS_ASCII_AMBIG] =
        (upperAmbI || SpanCandidateGenerator.hasAsciiAmbiguousChars(low)) ? 1.0f : 0.0f;
    out[G_HAS_REPEATED_CHARS] = SpanCandidateGenerator.hasRepeatedLetters(low) ? 1.0f : 0.0f;
    out[G_CONTAINS_DIGIT] = t.containsDigit() ? 1.0f : 0.0f;
    out[G_CASING_SHAPE] = t.shape().ordinal();
    out[G_CHAR_PHONOTACTICS] = gen.mined().charPhonotactics.scoreWord(low);
    out[G_PARALLEL_KEEP_PROB] = effKeepProb;
    out[G_PARALLEL_LOG_CNT] = (float) Math.log1p(effTot);
    out[G_SMOOTH_UNIGRAM] = NormV2Types.clampLm(gen.lmUnigram(low));
    out[G_SMOOTH_PREV_BIGRAM] = NormV2Types.clampLm(gen.lmBigram(prev, low));
    out[G_SMOOTH_NEXT_BIGRAM] = NormV2Types.clampLm(gen.lmBigram(low, next));
    out[G_CLITIC_TAIL_HINT] = cliticTail ? 1.0f : 0.0f;
    out[G_NEXT_MERGE_HINT] = nextMerge ? 1.0f : 0.0f;
    out[G_HAS_STRICT_MORPH] = gen.hasRegularStrictAnalysis(low) ? 1.0f : 0.0f;
    out[G_CLEAN_UNIGRAM] = gen.mined().cleanLm.unigramLogProb(low);
  }

  static void extractRankFeatures(
      SentenceLattice lat,
      String prev1Word,
      SpanArc arc,
      float[] lm6,
      SlotMaxima maxima,
      boolean loo,
      boolean looRawWasKeep,
      SpanCandidateGenerator gen,
      float[] out) {
    List<SpanToken> toks = lat.tokens();
    SpanToken t0 = toks.get(arc.startIdx);
    boolean singleSpan = arc.endIdx == arc.startIdx + 1;
    boolean isIdSurf = singleSpan && arc.normSurface.equals(t0.surface());
    boolean isIdLow = singleSpan && arc.normLower.equals(t0.lower());

    RewriteEntry rawRe = gen.mined().parallel1ToN.get(t0.lower());
    int effRawTot =
        rawRe == null ? 0 : (loo ? Math.max(0, rawRe.totalCount() - 1) : rawRe.totalCount());
    float rawKeepProb =
        (rawRe == null || effRawTot == 0)
            ? 0.50f
            : (loo ? rawRe.looKeepProb(looRawWasKeep) : rawRe.keepProb());
    float rawLogCnt = (float) Math.log1p(effRawTot);

    String nextLex = SpanCandidateGenerator.nextLexicalWordOrNull(toks, arc.endIdx);
    float fwdSmooth2 = NormV2Types.clampLm(gen.lmBigram(prev1Word, arc.firstWordLower));
    float rightSmooth2 = NormV2Types.clampLm(gen.lmBigram(arc.lastWordLower, nextLex));
    float uniSmooth = NormV2Types.clampLm(gen.lmUnigram(arc.lastWordLower));
    float intSmooth =
        arc.wordCount >= 2
            ? NormV2Types.clampLm(arc.internalLmBonus) / (arc.wordCount - 1)
            : 0.0f;

    float smoothSpanLlr = lm6[0] - lm6[1];
    float cleanSpanLlr = lm6[2] - lm6[3];
    float cleanFwd3 = lm6[4];
    float cleanRight3 = lm6[5];
    float cleanUni = gen.mined().cleanLm.unigramLogProb(arc.lastWordLower);
    float cliticSkipLlr = gen.computeCliticTransparencyLlr(lat, arc);

    float agree = 0.0f;
    if (lat.overtSubjectPerson() != 0 && arc.verbPerson != 0) {
      agree = (lat.overtSubjectPerson() == arc.verbPerson) ? 1.0f : -1.0f;
    }

    float orthoPrior = 0.0f;
    if (arc.stripsExplicitDiacritic) {
      orthoPrior -= 1.5f;
    }
    if (arc.apostropheAdded && arc.isProperOrAbbr) {
      orthoPrior += 1.0f;
    }
    if (arc.apostropheRemoved && !arc.isProperOrAbbr) {
      orthoPrior += 0.8f;
    }
    if (arc.caseChanged && arc.isProperOrAbbr) {
      orthoPrior += 0.5f;
    }

    int rawCharLen = t0.lower().length();
    for (int k = arc.startIdx + 1; k < arc.endIdx; k++) {
      rawCharLen += 1 + toks.get(k).lower().length();
    }

    int sm = arc.sourceMask;
    int cliticType = SpanCandidateGenerator.classifyCliticSplitType(arc);
    float qCliticAgree =
        cliticType == 2 ? (lat.hasQuestionMark() ? 1.0f : -0.35f) : 0.0f;

    out[R_IS_ID_SURF] = isIdSurf ? 1.0f : 0.0f;
    out[R_IS_ID_LOWER] = isIdLow ? 1.0f : 0.0f;
    out[R_PARALLEL_PROB] = arc.parallelProb;
    out[R_PARALLEL_LOG_CNT] = arc.parallelLogCount;
    out[R_RAW_KEEP_PROB] = rawKeepProb;
    out[R_RAW_LOG_CNT] = rawLogCnt;
    out[R_SUFFIX_PRECISION] = arc.suffixPrecision;
    out[R_EDIT_COST] = arc.editCost;
    out[R_DIACRITIC_DIFFS] = arc.diacriticDiffs;
    out[R_LEN_DELTA] = arc.normLower.length() - rawCharLen;

    out[R_SRC_FORMAL_MORPH] = (sm & NormV2Types.SRC_FORMAL_MORPH) != 0 ? 1.0f : 0.0f;
    out[R_SRC_INFORMAL_MORPH] = (sm & NormV2Types.SRC_INFORMAL_MORPH) != 0 ? 1.0f : 0.0f;
    out[R_SRC_INFORMAL_EXACT] = (sm & NormV2Types.SRC_INFORMAL_EXACT) != 0 ? 1.0f : 0.0f;
    out[R_SRC_DEASCIIFIER] = (sm & NormV2Types.SRC_DEASCIIFIER) != 0 ? 1.0f : 0.0f;
    out[R_SRC_SUFFIX_RULE] = (sm & NormV2Types.SRC_SUFFIX_RULE) != 0 ? 1.0f : 0.0f;
    out[R_SRC_SPELLCHECK] = (sm & NormV2Types.SRC_SPELLCHECK) != 0 ? 1.0f : 0.0f;
    out[R_IS_SPLIT] =
        (arc.wordCount >= 2
                || (sm & (NormV2Types.SRC_CLITIC_SPLIT | NormV2Types.SRC_PARALLEL_SPLIT)) != 0)
            ? 1.0f
            : 0.0f;
    out[R_IS_MERGE] =
        ((arc.endIdx - arc.startIdx) >= 2
                || (sm & (NormV2Types.SRC_COMPOUND_MERGE | NormV2Types.SRC_PARALLEL_MERGE)) != 0)
            ? 1.0f
            : 0.0f;
    out[R_IS_APOS_OR_NUM] =
        (sm & (NormV2Types.SRC_OOV_APOSTROPHE | NormV2Types.SRC_NUMERAL_JOIN)) != 0 ? 1.0f : 0.0f;
    out[R_IS_APOS_DROP] =
        ((sm & NormV2Types.SRC_APOSTROPHE_DROP) != 0 || arc.apostropheRemoved) ? 1.0f : 0.0f;

    out[R_SMOOTH_FWD2] = fwdSmooth2;
    out[R_SMOOTH_RIGHT2] = rightSmooth2;
    out[R_SMOOTH_UNI] = uniSmooth;
    out[R_SMOOTH_INTERNAL] = intSmooth;
    out[R_SMOOTH_SPAN_LLR] = smoothSpanLlr;
    out[R_SMOOTH_REL_MARGIN] = smoothSpanLlr - maxima.maxSmoothLlr;
    out[R_CLEAN_FWD3] = cleanFwd3;
    out[R_CLEAN_RIGHT3] = cleanRight3;
    out[R_CLEAN_UNI] = cleanUni;
    out[R_CLEAN_SPAN_LLR] = cleanSpanLlr;
    out[R_CLEAN_REL_MARGIN] = cleanSpanLlr - maxima.maxCleanLlr;
    out[R_PARALLEL_REL_MARGIN] = arc.parallelProb - maxima.maxParallelProb;

    out[R_PHONO_GAIN] = arc.charPhonotacticScore - maxima.rawPhono;
    out[R_SENT_ASCII_RATIO] = lat.sentenceAsciiRatio();
    out[R_SENT_OOV_RATIO] = lat.sentenceOovRatio();
    out[R_SUBJ_VERB_AGREE] = agree;
    out[R_ORTHO_PRIOR] = orthoPrior;
    List<SpanArc> slotArcs = lat.outgoing()[arc.startIdx];
    boolean rawRegMorph = !slotArcs.isEmpty() && slotArcs.get(0).isRegularMorph;
    out[R_RAW_REG_MORPH] = rawRegMorph ? 1.0f : 0.0f;
    out[R_ARC_REG_MORPH] = arc.isRegularMorph ? 1.0f : 0.0f;
    out[R_CLITIC_TYPE] = cliticType;
    out[R_HEAD_SYNTAX_CLASS] = arc.headSyntaxClass;
    out[R_QUESTION_CLITIC_AGREE] = qCliticAgree;
    out[R_CLITIC_SKIP_LLR] = cliticSkipLlr;
    out[R_COARSE_MORPH_TAG] = arc.coarseMorphTag;
    out[R_SRC_SHORTHAND_ABJAD] = (sm & NormV2Types.SRC_SHORTHAND_ABJAD) != 0 ? 1.0f : 0.0f;
    out[R_QUESTION_VOWEL_HARMONY] = gen.computeQuestionCliticHarmony(lat, prev1Word, arc);
  }

  private static final class FloatBuffer {
    private float[] buf;
    private int size;

    FloatBuffer(int cap) {
      this.buf = new float[Math.max(64, cap)];
    }

    void append(float v) {
      if (size == buf.length) {
        buf = java.util.Arrays.copyOf(buf, buf.length * 2);
      }
      buf[size++] = v;
    }

    void append(float[] arr) {
      if (size + arr.length > buf.length) {
        buf = java.util.Arrays.copyOf(buf, Math.max(buf.length * 2, size + arr.length));
      }
      System.arraycopy(arr, 0, buf, size, arr.length);
      size += arr.length;
    }

    float[] toArray() {
      return java.util.Arrays.copyOf(buf, size);
    }
  }

  private static final class ByteBuffer {
    private byte[] buf;
    private int size;

    ByteBuffer(int cap) {
      this.buf = new byte[Math.max(64, cap)];
    }

    void append(byte v) {
      if (size == buf.length) {
        buf = java.util.Arrays.copyOf(buf, buf.length * 2);
      }
      buf[size++] = v;
    }

    int size() {
      return size;
    }

    byte[] toArray() {
      return java.util.Arrays.copyOf(buf, size);
    }
  }
}
