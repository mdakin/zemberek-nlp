package zemberek.normalization.v2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import zemberek.normalization.deasciifier.latticedeasciifier.QuantizedWeightTable;
import zemberek.normalization.v2.NormV2Types.AlignedSentence;
import zemberek.normalization.v2.NormV2Types.AlignedToken;
import zemberek.normalization.v2.NormV2Types.PreparedLattice;
import zemberek.normalization.v2.NormV2Types.SentenceLattice;
import zemberek.normalization.v2.NormV2Types.SpanArc;
import zemberek.normalization.v2.NormV2Types.SpanToken;
import zemberek.normalization.v2.NormV2Types.TextNormalizer;

/**
 * Normalizer 1 ({@code v2.3}): Joint Semi-Markov Cost-Augmented Margin Averaged Perceptron over
 * {@link SentenceLattice}.
 *
 * <p><b>Acronym Glossary:</b>
 * <ul>
 *   <li><b>PA-I</b> ({@code Passive-Aggressive Type-I}): Structured max-margin online learning rule
 *       that stays passive when the gold path beats every rival path by at least the task-specific
 *       loss margin, and performs a bounded ({@code <= C}) step proportional to the margin
 *       violation when a rival wins or comes too close.
 *   <li><b>MPHF</b> ({@code Minimal Perfect Hash Function}, {@code MultiLevelMphf} / {@link
 *       QuantizedWeightTable}): Maps active 64-bit hashed feature keys bijectively into {@code
 *       0..M-1} with palette weight quantization (~3.4 bytes/feature; ~670K features in ~2.2 MB).
 *   <li><b>LLR</b> ({@code Log-Likelihood Ratio}): {@code log P(candidate_span) - log P(raw_span)}
 *       evaluated across identical left and right lexical boundaries.
 *   <li><b>OOF</b> ({@code Out-Of-Fold}) &amp; <b>GBDT</b> ({@code Gradient Boosted Decision
 *       Trees}): In {@code Hybrid} mode, slot {@link #F_OOF_GBDT_LOGPROB} consumes 4-fold
 *       out-of-fold {@link TwoStageGbdtNormalizer} log-probabilities during training (and full
 *       GBDT log-probabilities at inference), combining tree non-linearity with global beam
 *       Viterbi transitions.
 *   <li><b>OOV</b> ({@code Out-Of-Vocabulary}) &amp; <b>TDK</b> ({@code Türk Dil Kurumu}, Turkish
 *       Language Association standard orthography / apostrophe rules).
 * </ul>
 */
public final class SpanLatticePerceptronNormalizer implements TextNormalizer {

  /** Number of calibrated continuous features scored alongside sparse binary templates on every arc. */
  public static final int DENSE_DIM = 20;

  // ===============================================================================================
  // Continuous Feature Slots (0 .. DENSE_DIM - 1)
  // ===============================================================================================

  /** Scaled ({@code 0.20x}) forward 2-gram {@code SmoothLm} score {@code log P(first_word | prev1_lex)}. */
  private static final int F_SMOOTH_FWD2 = 0;

  /** Scaled ({@code 0.20x}) right-lookahead 2-gram {@code SmoothLm} score {@code log P(next_lex | last_word)}. */
  private static final int F_SMOOTH_RIGHT2 = 1;

  /** Scaled ({@code 0.20x}) internal 2-gram {@code SmoothLm} transition sum inside {@code 1:2+} split arcs. */
  private static final int F_SMOOTH_INTERNAL = 2;

  /** Scaled ({@code 0.20x}) exact boundary-to-boundary 2-gram {@code SmoothLm} Span LLR ({@code cand - raw}). */
  private static final int F_SMOOTH_SPAN_LLR = 3;

  /** Scaled ({@code 0.20x}) forward 3-gram {@code CleanWordLm} score {@code log P(first_word | prev2_lex, prev1_lex)}. */
  private static final int F_CLEAN_FWD3 = 4;

  /** Scaled ({@code 0.20x}) right-lookahead 3-gram {@code CleanWordLm} score {@code log P(next_lex | penult, last_word)}. */
  private static final int F_CLEAN_RIGHT3 = 5;

  /** Scaled ({@code 0.20x}) exact boundary-to-boundary 3-gram {@code CleanWordLm} Span LLR ({@code cand - raw}). */
  private static final int F_CLEAN_SPAN_LLR = 6;

  /** Smoothed empirical parallel rewrite/merge probability {@code P(cand | raw_span)} (LOO-adjusted in training). */
  private static final int F_PARALLEL_PROB = 7;

  /** Scaled ({@code 0.25x}) {@code log(1 + count(raw_span -> cand))} in parallel training corpus. */
  private static final int F_PARALLEL_LOG_CNT = 8;

  /** Empirical precision of the induced suffix/orthographic rule that proposed this arc ({@code 0.0} if none). */
  private static final int F_SUFFIX_PRECISION = 9;

  /** Weighted character Levenshtein edit cost between raw span and candidate. */
  private static final int F_EDIT_COST = 10;

  /** Scaled ({@code 0.35x}) count of character positions differing only in Turkish diacritics. */
  private static final int F_DIACRITIC_DIFFS = 11;

  /** {@code 1.0} if candidate is a {@code 1:1} span preserving the raw token's lowercase form. */
  private static final int F_IS_IDENTITY_LOWER = 12;

  /** {@code 1.0} if candidate words all have valid Zemberek morphological parses (or strong clean attestation). */
  private static final int F_IS_REGULAR_MORPH = 13;

  /** Scaled ({@code 0.25x}) character 4-gram Turkish phonotactic log-probability of the candidate's last word. */
  private static final int F_CHAR_PHONOTACTICS = 14;

  /** Local {@code logSoftmax} log-probability from {@link TwoStageGbdtNormalizer} (4-fold OOF in training; {@code 0.0} if standalone). */
  private static final int F_OOF_GBDT_LOGPROB = 15;

  /** Sentence-wide ASCII word ratio ({@code [0, 1]}) active on diacritic-restoring arcs ({@code 0.0} otherwise). */
  private static final int F_ASCII_SENT_RATIO = 16;

  /** {@code +1.0} if proposed by exact-vowel informal morphology ({@code geliyo -> geliyor}), {@code -1.0} if stripping an explicit diacritic. */
  private static final int F_INFORMAL_EXACT_VS_STRIP = 17;

  /** Scaled ({@code 0.20x}) clitic-transparency skip-bigram LLR ({@code P(w_next | host)} vs {@code P(w_next | host+locative)}). */
  private static final int F_CLITIC_SKIP_LLR = 18;

  /** Guarded cross-token 4-way vowel harmony ({@code +1.0 = match, -1.0 = mismatch, 0.0 = neutral}) on standalone question clitics. */
  private static final int F_QUESTION_VOWEL_HARMONY = 19;

  // ===============================================================================================
  // 64-Bit Hashed Sparse Binary Feature Template IDs (1 .. 23)
  // Templates 1..19 are unary (extracted once per SpanArc); 20..23 are Markov decoding transitions.
  // ===============================================================================================

  /** Exact lowercase rewrite {@code (raw_span_lower -> cand_lower)}, gated on {@code rawParallelTotal >= 3} for OOV generalization. */
  private static final int T_LEX_LOWER_PAIR = 1;

  /** Exact cased surface rewrite {@code (raw_surface -> cand_surface)}, gated on {@code rawParallelTotal >= 3}. */
  private static final int T_LEX_SURF_PAIR = 2;

  /** Conjunction of {@code (isIdentityLower, rawHasStrictMorph, candHasStrictMorph, sourceMask)}. */
  private static final int T_ID_OOV_MORPH_SRC = 3;

  /** Conjunction of {@code (sourceMask, sentenceAsciiBucket, rawHasStrictMorph, hasDiacriticDiffs)}. */
  private static final int T_SRC_ASCII_DIAC = 4;

  /** Right-context lexical lookahead bigram {@code (cand_last_word, next_raw_lex_word)}. */
  private static final int T_RIGHT_LEX_BIGRAM = 5;

  /** Right-contextualized rewrite {@code (raw_span_lower, cand_lower, next_raw_lex_word)} ({@code rawParallelTotal >= 3}). */
  private static final int T_LEX_PAIR_NEXT = 6;

  /** Left-contextualized rewrite {@code (prev_raw_lower, raw_span_lower, cand_lower)} ({@code rawParallelTotal >= 3}). */
  private static final int T_PREV_LEX_PAIR = 7;

  /** Split clitic tail conjoined with head-word POS/syntax and right word {@code (clitic_tail, headMorphAndSyntax, next_lex)}. */
  private static final int T_SPLIT_TAIL_HEAD_NEXT = 8;

  /** Split clitic tail conjoined with sentence {@code '?'} presence {@code (clitic_tail, hasQuestionMark)}. */
  private static final int T_SPLIT_TAIL_QMARK = 9;

  /** Multi-token merge span conjoined with right context {@code (raw_merge_span, next_lex)}. */
  private static final int T_MERGE_RAW_NEXT = 10;

  /** Multi-token merge target conjoined with left raw token {@code (prev_raw_lower, cand_lower)}. */
  private static final int T_MERGE_PREV_NORM = 11;

  /** Sentence overt subject pronoun person conjoined with finite verb person {@code (subjPerson, verbPerson, isIdentity)}. */
  private static final int T_SUBJ_VERB_AGREE = 12;

  /** Casing transition {@code (rawCasingShape, caseChanged, isSentenceStart, isProperOrAbbr)}. */
  private static final int T_CASING_TRANSITION = 13;

  /** TDK apostrophe insertion {@code (isProperOrAbbr, sourceMask)}. */
  private static final int T_APOSTROPHE_ADD = 14;

  /** Candidate coarse POS/case tag conjoined with following token's casing shape {@code (coarseMorphTag, nextShape)}. */
  private static final int T_MORPH_NEXT_SHAPE = 15;

  /** Unlexicalized split clitic tail conjoined with head-word POS/syntax {@code (clitic_tail, headMorphAndSyntax)}. */
  private static final int T_SPLIT_TAIL_HEAD = 16;

  /** Erroneous apostrophe removal {@code (isProperOrAbbr, sourceMask)}. */
  private static final int T_APOSTROPHE_DROP = 17;

  /** Multi-token merge source mask conjoined with proper/acronym status {@code (sourceMask, isProperOrAbbr)}. */
  private static final int T_MERGE_SRC_PROP = 18;

  /** Multi-token merge coarse POS/case tag conjoined with source mask and right word {@code (coarseMorphTag, sourceMask, next_lex)}. */
  private static final int T_MERGE_MORPH_NEXT = 19;

  /** Decoded Markov lexical bigram transition {@code (prev_decoded_lex_word, cand_first_word)}. */
  private static final int T_TRANS_LEX_BIGRAM = 20;

  /** Decoded Markov morphological tag transition {@code (prev_decoded_morph_tag, cand_first_word_morph_tag)}. */
  private static final int T_TRANS_MORPH_BIGRAM = 21;

  /** Decoded Markov word-to-POS transition {@code (prev_decoded_lex_word, cand_first_word_morph_tag)}. */
  private static final int T_TRANS_WORD_TAG = 22;

  /** Decoded Markov POS-to-word transition {@code (prev_decoded_morph_tag, cand_first_word)}. */
  private static final int T_TRANS_TAG_WORD = 23;

  /** Finite verb negation polarity preservation {@code (rawNegVerbCue, candVerbPolarity, sourceMask)}. */
  private static final int T_POLARITY_MATCH = 24;

  /** Non-linear unseen-OOV typo confidence {@code (sourceMask, wordLenBucket, editCostBucket, unaryCleanGainBucket)}. */
  private static final int T_SRC_LEN_COST_GAIN = 25;

  /** Non-linear phonotactic recovery {@code (sourceMask, wordLenBucket, phonoGainBucket)}. */
  private static final int T_SRC_PHONO_GAIN = 26;

  /** Raw clean-corpus attestation protection {@code (sourceMask, rawCleanCountBucket, rawCasingShape)}. */
  private static final int T_SRC_RAW_CLEAN_ATTEST = 27;

  /** Pronoun/nominal locative vs. additive clitic right-syntax agreement {@code (pronPerson, wordCount, nextLexState)}. */
  private static final int T_PRON_LOC_RIGHT = 28;

  /** Pronoun locative vs. additive clitic left-POS predicate transition {@code (prevMorphTag, wordCount, nextLexState)}. */
  private static final int T_TRANS_PRON_LOC_LEFT = 29;

  /** Guarded standalone question-clitic vowel harmony transition {@code (harmonySign, isIdentityLower)}. */
  private static final int T_TRANS_QUESTION_HARMONY = 30;

  /** Training hyperparameters and asymmetric PA-I loss weights for {@link SpanLatticePerceptronNormalizer}. */
  public static final class TrainConfig {
    /** Number of shuffled online PA-I passes over the prepared training lattices. */
    public int epochs = 6;

    /** Maximum Passive-Aggressive Type-I step size {@code C} bounding any single sentence update. */
    public float aggressivenessC = 0.25f;

    /** Structured margin penalty when the model corrupts a token that was already clean in the input ({@code 2x} missed-edit loss). */
    public float cleanCorruptLoss = 2.00f;

    /** Structured margin penalty when the model fails to normalize a noisy token ({@code +0.50} on span-boundary mismatch). */
    public float missedEditLoss = 1.00f;

    /** Structured margin penalty when lowercase words match gold but capitalization differs. */
    public float casingMismatchLoss = 0.35f;

    /** Maximum number of top-|weight| sparse features compiled into the MPHF {@link QuantizedWeightTable}. */
    public int maxQuantizedFeatures = 1_600_000;

    /** Whether to activate slot {@link #F_OOF_GBDT_LOGPROB} for {@code Hybrid OOF-GBDT + Perceptron} stacking. */
    public boolean useGbdtHybridFeature = false;

    /** Deterministic shuffle seed for reproducible training epochs. */
    public long seed = 42L;
  }

  /** Fast open-addressing long->float hash map for Averaged Perceptron training. */
  static final class OpenLongFloatTable {
    private long[] keys;
    private float[] weights;
    private float[] sumWeights;
    private int mask;
    private int size;
    private int threshold;

    OpenLongFloatTable(int initialPow2) {
      int cap = 1;
      while (cap < initialPow2) {
        cap <<= 1;
      }
      this.keys = new long[cap];
      this.weights = new float[cap];
      this.sumWeights = new float[cap];
      this.mask = cap - 1;
      this.threshold = (int) (cap * 0.68f);
    }

    float get(long k) {
      if (k == 0L) {
        k = 1L;
      }
      int slot = mixSlot(k) & mask;
      while (true) {
        long cur = keys[slot];
        if (cur == 0L) {
          return 0.0f;
        }
        if (cur == k) {
          return weights[slot];
        }
        slot = (slot + 1) & mask;
      }
    }

    void add(long k, float delta, long stepNow) {
      if (k == 0L) {
        k = 1L;
      }
      if (size >= threshold) {
        rehash();
      }
      int slot = mixSlot(k) & mask;
      while (true) {
        long cur = keys[slot];
        if (cur == 0L) {
          keys[slot] = k;
          weights[slot] = delta;
          sumWeights[slot] = delta * stepNow;
          size++;
          return;
        }
        if (cur == k) {
          weights[slot] += delta;
          sumWeights[slot] += delta * stepNow;
          return;
        }
        slot = (slot + 1) & mask;
      }
    }

    private void rehash() {
      long[] oldK = keys;
      float[] oldW = weights;
      float[] oldS = sumWeights;
      int newCap = oldK.length << 1;
      keys = new long[newCap];
      weights = new float[newCap];
      sumWeights = new float[newCap];
      mask = newCap - 1;
      threshold = (int) (newCap * 0.68f);
      for (int i = 0; i < oldK.length; i++) {
        long k = oldK[i];
        if (k != 0L) {
          int slot = mixSlot(k) & mask;
          while (keys[slot] != 0L) {
            slot = (slot + 1) & mask;
          }
          keys[slot] = k;
          weights[slot] = oldW[i];
          sumWeights[slot] = oldS[i];
        }
      }
    }

    QuantizedWeightTable toQuantizedTable(long totalSteps, int maxFeatures) {
      long[] outKeys = new long[size];
      float[] outVals = new float[size];
      int p = 0;
      float invSteps = 1.0f / Math.max(1L, totalSteps);
      for (int i = 0; i < keys.length; i++) {
        long k = keys[i];
        if (k != 0L) {
          float avg = weights[i] - sumWeights[i] * invSteps;
          if (Math.abs(avg) > 1e-4f) {
            outKeys[p] = k;
            outVals[p] = avg;
            p++;
          }
        }
      }
      if (p < outKeys.length) {
        outKeys = Arrays.copyOf(outKeys, p);
        outVals = Arrays.copyOf(outVals, p);
      }
      return QuantizedWeightTable.build(outKeys, outVals, maxFeatures);
    }

    private static int mixSlot(long k) {
      long z = k * 0x9E3779B97F4A7C15L;
      z ^= (z >>> 32);
      return (int) z;
    }
  }

  private final String modelName;
  private final SpanCandidateGenerator generator;
  private final float[] denseWeights;
  private final QuantizedWeightTable sparseWeights;
  private final TwoStageGbdtNormalizer optionalGbdtScorer;

  public SpanLatticePerceptronNormalizer(
      String modelName,
      SpanCandidateGenerator generator,
      float[] denseWeights,
      QuantizedWeightTable sparseWeights,
      TwoStageGbdtNormalizer optionalGbdtScorer) {
    this.modelName = modelName;
    this.generator = generator;
    this.denseWeights = denseWeights;
    this.sparseWeights = sparseWeights;
    this.optionalGbdtScorer = optionalGbdtScorer;
  }

  @Override
  public String name() {
    return modelName;
  }

  public long modelRamBytes() {
    return sparseWeights.ramBytesUsed()
        + DENSE_DIM * 4L
        + 64L
        + (optionalGbdtScorer != null ? optionalGbdtScorer.modelRamBytes() : 0L);
  }

  public int activeSparseFeatureCount() {
    return sparseWeights.size();
  }

  float[] denseWeights() {
    return denseWeights;
  }

  QuantizedWeightTable sparseWeights() {
    return sparseWeights;
  }

  TwoStageGbdtNormalizer optionalGbdtScorer() {
    return optionalGbdtScorer;
  }

  List<SpanArc> decodeLattice(SentenceLattice lattice) {
    if (lattice.tokens().isEmpty()) {
      return List.of();
    }
    if (optionalGbdtScorer != null) {
      optionalGbdtScorer.populateLatticeGbdtScores(lattice);
    }
    return decodeViterbi(
        lattice,
        generator,
        optionalGbdtScorer != null,
        denseWeights,
        null,
        sparseWeights,
        null,
        null);
  }

  @Override
  public String normalize(String rawSentence) {
    if (rawSentence == null || rawSentence.isEmpty()) {
      return "";
    }
    SentenceLattice lattice = generator.buildLattice(rawSentence);
    if (lattice.tokens().isEmpty()) {
      return rawSentence;
    }
    if (optionalGbdtScorer != null) {
      optionalGbdtScorer.populateLatticeGbdtScores(lattice);
    }
    List<SpanArc> bestPath =
        decodeViterbi(
            lattice,
            generator,
            optionalGbdtScorer != null,
            denseWeights,
            null,
            sparseWeights,
            null,
            null);
    return NormV2Types.reconstructText(lattice.tokens(), bestPath);
  }

  public static SpanLatticePerceptronNormalizer train(
      String modelName,
      SpanCandidateGenerator generator,
      List<AlignedSentence> trainData,
      TrainConfig cfg,
      TwoStageGbdtNormalizer optionalGbdtScorer) {
    List<PreparedLattice> prepared = generator.prepareTrainingLattices(trainData);
    if (optionalGbdtScorer != null && cfg.useGbdtHybridFeature) {
      TwoStageGbdtNormalizer.TrainConfig oofCfg = new TwoStageGbdtNormalizer.TrainConfig();
      TwoStageGbdtNormalizer.populateOutOfFoldScores(generator, prepared, oofCfg, 4);
    }
    return trainFromPrepared(modelName, generator, prepared, cfg, optionalGbdtScorer);
  }

  /**
   * Trains the structured Semi-Markov Perceptron on pre-built {@link PreparedLattice}s. When {@code
   * cfg.useGbdtHybridFeature} is true, callers should populate {@code arc.cachedGbdtScore} via
   * {@link TwoStageGbdtNormalizer#populateOutOfFoldScores} beforehand and pass the full-data {@code
   * runtimeGbdtScorer} for inference.
   */
  public static SpanLatticePerceptronNormalizer trainFromPrepared(
      String modelName,
      SpanCandidateGenerator generator,
      List<PreparedLattice> prepared,
      TrainConfig cfg,
      TwoStageGbdtNormalizer runtimeGbdtScorer) {

    float[] wDense = new float[DENSE_DIM];
    double[] sumDense = new double[DENSE_DIM];
    // Calibrated warm-start priors across SmoothLm, CleanWordLm 3-gram, and parallel priors
    wDense[F_SMOOTH_FWD2] = 0.14f;
    wDense[F_SMOOTH_RIGHT2] = 0.10f;
    wDense[F_SMOOTH_INTERNAL] = 0.14f;
    wDense[F_SMOOTH_SPAN_LLR] = 0.18f;
    wDense[F_CLEAN_FWD3] = 0.16f;
    wDense[F_CLEAN_RIGHT3] = 0.12f;
    wDense[F_CLEAN_SPAN_LLR] = 0.22f;
    wDense[F_PARALLEL_PROB] = 1.60f;
    wDense[F_PARALLEL_LOG_CNT] = 0.35f;
    wDense[F_SUFFIX_PRECISION] = 0.70f;
    wDense[F_EDIT_COST] = -0.85f;
    wDense[F_OOF_GBDT_LOGPROB] = cfg.useGbdtHybridFeature ? 0.55f : 0.0f;
    wDense[F_INFORMAL_EXACT_VS_STRIP] = 0.30f;
    wDense[F_CLITIC_SKIP_LLR] = 0.18f;

    OpenLongFloatTable sparseTable = new OpenLongFloatTable(1 << 20);
    int[] order = new int[prepared.size()];
    for (int i = 0; i < order.length; i++) {
      order[i] = i;
    }
    Random rnd = new Random(cfg.seed);
    long step = 1L;

    long[] goldFeatsBuf = new long[64];
    long[] predFeatsBuf = new long[64];
    float[] goldDenseAcc = new float[DENSE_DIM];
    float[] predDenseAcc = new float[DENSE_DIM];
    float[] tmpDense = new float[DENSE_DIM];
    float[] lm6Buf = new float[6];
    String[] nextLex2 = new String[2];

    for (int ep = 0; ep < cfg.epochs; ep++) {
      for (int i = order.length - 1; i > 0; i--) {
        int j = rnd.nextInt(i + 1);
        int t = order[i];
        order[i] = order[j];
        order[j] = t;
      }

      for (int idx : order) {
        PreparedLattice pl = prepared.get(idx);
        SentenceLattice lat = pl.lattice();
        SpanArc[] goldAt = pl.goldByStart();
        List<SpanArc> goldPath = pl.oraclePath();

        List<SpanArc> hatPath =
            decodeViterbi(
                lat, generator, cfg.useGbdtHybridFeature, wDense, sparseTable, null, goldAt, cfg);
        if (samePath(hatPath, goldPath)) {
          step++;
          continue;
        }

        Arrays.fill(goldDenseAcc, 0.0f);
        Arrays.fill(predDenseAcc, 0.0f);
        List<Long> plusSparse = new ArrayList<>(goldPath.size() * 20);
        List<Long> minusSparse = new ArrayList<>(hatPath.size() * 20);

        float goldScore =
            accumulatePathFeatures(
                lat,
                goldPath,
                wDense,
                sparseTable,
                cfg.useGbdtHybridFeature,
                goldDenseAcc,
                plusSparse,
                goldFeatsBuf,
                tmpDense,
                lm6Buf,
                nextLex2,
                generator);
        float hatScore =
            accumulatePathFeatures(
                lat,
                hatPath,
                wDense,
                sparseTable,
                cfg.useGbdtHybridFeature,
                predDenseAcc,
                minusSparse,
                predFeatsBuf,
                tmpDense,
                lm6Buf,
                nextLex2,
                generator);

        float loss = 0.0f;
        for (SpanArc ha : hatPath) {
          loss += computeArcLoss(lat, ha, goldAt, cfg);
        }
        float marginViolation = loss - (goldScore - hatScore);
        if (marginViolation <= 1e-5f) {
          step++;
          continue;
        }

        float denseNormSq = 0.0f;
        for (int d = 0; d < DENSE_DIM; d++) {
          float diff = goldDenseAcc[d] - predDenseAcc[d];
          denseNormSq += diff * diff;
        }
        float totalNormSq = denseNormSq * 0.25f + (plusSparse.size() + minusSparse.size()) + 1.0f;
        float eta = Math.min(cfg.aggressivenessC, marginViolation / totalNormSq);

        float denseEta = eta * 0.25f;
        for (int d = 0; d < DENSE_DIM; d++) {
          float delta = (goldDenseAcc[d] - predDenseAcc[d]) * denseEta;
          if (delta != 0.0f) {
            wDense[d] += delta;
            sumDense[d] += (double) delta * step;
          }
        }
        for (int i = 0; i < plusSparse.size(); i++) {
          sparseTable.add(plusSparse.get(i), eta, step);
        }
        for (int i = 0; i < minusSparse.size(); i++) {
          sparseTable.add(minusSparse.get(i), -eta, step);
        }
        step++;
      }
    }

    float[] avgDense = new float[DENSE_DIM];
    for (int d = 0; d < DENSE_DIM; d++) {
      avgDense[d] = (float) (wDense[d] - sumDense[d] / Math.max(1L, step));
    }
    QuantizedWeightTable quantized =
        sparseTable.toQuantizedTable(step, cfg.maxQuantizedFeatures);

    return new SpanLatticePerceptronNormalizer(
        modelName,
        generator,
        avgDense,
        quantized,
        cfg.useGbdtHybridFeature ? runtimeGbdtScorer : null);
  }

  private static float accumulatePathFeatures(
      SentenceLattice lat,
      List<SpanArc> path,
      float[] wDense,
      OpenLongFloatTable sparseTrain,
      boolean useGbdt,
      float[] denseAcc,
      List<Long> sparseList,
      long[] featBuf,
      float[] tmpDense,
      float[] lm6Buf,
      String[] nextLex2,
      SpanCandidateGenerator gen) {
    float totalScore = 0.0f;
    String prev2Lex = null;
    String prev1Lex = null;
    short prevMorphTag = -1;
    for (SpanArc arc : path) {
      extractDenseFeatures(lat, prev2Lex, prev1Lex, arc, useGbdt, gen, lm6Buf, tmpDense);
      for (int d = 0; d < DENSE_DIM; d++) {
        denseAcc[d] += tmpDense[d];
        totalScore += wDense[d] * tmpDense[d];
      }
      int nUnary = extractUnarySparseFeatures(lat, gen, arc, featBuf);
      int nTrans =
          extractTransitionSparseFeatures(lat, gen, prev1Lex, prevMorphTag, arc, featBuf, nUnary);
      int nSparse = nUnary + nTrans;
      for (int f = 0; f < nSparse; f++) {
        long key = featBuf[f];
        sparseList.add(key);
        totalScore += sparseTrain.get(key);
      }
      prevMorphTag = advanceLexMorphTag(lat, prevMorphTag, arc);
      SpanCandidateGenerator.advanceLexicalHistory(lat, prev2Lex, prev1Lex, arc, nextLex2);
      prev2Lex = nextLex2[0];
      prev1Lex = nextLex2[1];
    }
    return totalScore;
  }

  private static short advanceLexMorphTag(SentenceLattice lat, short prevTag, SpanArc arc) {
    if (arc.wordCount == 1 && arc.endIdx == arc.startIdx + 1) {
      SpanToken t = lat.tokens().get(arc.startIdx);
      if (t.isImmutable()) {
        return SpanCandidateGenerator.isSentenceBoundaryPunct(t) ? (short) -1 : prevTag;
      }
    }
    return arc.coarseMorphTag;
  }

  /**
   * Semi-Markov Viterbi over {@link SentenceLattice} tracking punctuation-bridging lexical history
   * {@code (prev2Lex, prev1Lex, prevMorphTag)} pruned to top beam {@code B = 4} states per boundary.
   */
  private static List<SpanArc> decodeViterbi(
      SentenceLattice lat,
      SpanCandidateGenerator generator,
      boolean useGbdt,
      float[] wDense,
      OpenLongFloatTable trainSparse,
      QuantizedWeightTable evalSparse,
      SpanArc[] goldAt,
      TrainConfig trainCfg) {

    int n = lat.tokens().size();
    final int beamK = 4;

    @SuppressWarnings({"unchecked", "rawtypes"})
    List<BackPtr>[] chart = new List[n + 1];
    for (int i = 0; i <= n; i++) {
      chart[i] = new ArrayList<>(beamK + 1);
    }
    chart[0].add(new BackPtr(null, null, null, null, (short) -1, 0.0f));

    long[] featBuf = new long[64];
    float[] denseBuf = new float[DENSE_DIM];
    float[] lm6Buf = new float[6];
    String[] nextLex2 = new String[2];

    for (int i = 0; i < n; i++) {
      List<BackPtr> incoming = chart[i];
      if (incoming.isEmpty()) {
        continue;
      }
      List<SpanArc> arcs = lat.outgoing()[i];
      for (int aIdx = 0; aIdx < arcs.size(); aIdx++) {
        SpanArc arc = arcs.get(aIdx);
        float arcLoss =
            (goldAt != null && trainCfg != null)
                ? computeArcLoss(lat, arc, goldAt, trainCfg)
                : 0.0f;

        int nUnary = extractUnarySparseFeatures(lat, generator, arc, featBuf);
        float unarySparseScore = 0.0f;
        if (trainSparse != null) {
          for (int f = 0; f < nUnary; f++) {
            unarySparseScore += trainSparse.get(featBuf[f]);
          }
        } else {
          for (int f = 0; f < nUnary; f++) {
            unarySparseScore += evalSparse.get(featBuf[f]);
          }
        }

        BackPtr bestPrevForArc = null;
        float bestTotalForArc = Float.NEGATIVE_INFINITY;

        for (int b = 0; b < incoming.size(); b++) {
          BackPtr prevBp = incoming.get(b);
          extractDenseFeatures(
              lat, prevBp.prev2Lex, prevBp.prev1Lex, arc, useGbdt, generator, lm6Buf, denseBuf);
          float denseScore = 0.0f;
          for (int d = 0; d < DENSE_DIM; d++) {
            denseScore += wDense[d] * denseBuf[d];
          }
          int nTrans =
              extractTransitionSparseFeatures(
                  lat, generator, prevBp.prev1Lex, prevBp.prevMorphTag, arc, featBuf, 0);
          float transSparseScore = 0.0f;
          if (trainSparse != null) {
            for (int f = 0; f < nTrans; f++) {
              transSparseScore += trainSparse.get(featBuf[f]);
            }
          } else {
            for (int f = 0; f < nTrans; f++) {
              transSparseScore += evalSparse.get(featBuf[f]);
            }
          }
          float candTotal =
              prevBp.score + unarySparseScore + denseScore + transSparseScore + arcLoss;
          if (candTotal > bestTotalForArc) {
            bestTotalForArc = candTotal;
            bestPrevForArc = prevBp;
          }
        }

        if (bestPrevForArc != null && arc.endIdx <= n) {
          short nextTag = advanceLexMorphTag(lat, bestPrevForArc.prevMorphTag, arc);
          SpanCandidateGenerator.advanceLexicalHistory(
              lat, bestPrevForArc.prev2Lex, bestPrevForArc.prev1Lex, arc, nextLex2);
          insertTopK(
              chart[arc.endIdx],
              new BackPtr(
                  arc, bestPrevForArc, nextLex2[0], nextLex2[1], nextTag, bestTotalForArc),
              beamK);
        }
      }
    }

    List<BackPtr> finals = chart[n];
    if (finals.isEmpty()) {
      List<SpanArc> fallback = new ArrayList<>(n);
      for (int i = 0; i < n; i++) {
        if (!lat.outgoing()[i].isEmpty()) {
          fallback.add(lat.outgoing()[i].get(0));
        }
      }
      return fallback;
    }

    BackPtr bestEnd = finals.get(0);
    for (int i = 1; i < finals.size(); i++) {
      if (finals.get(i).score > bestEnd.score) {
        bestEnd = finals.get(i);
      }
    }

    List<SpanArc> path = new ArrayList<>(n);
    BackPtr cur = bestEnd;
    while (cur != null && cur.arc != null) {
      path.add(cur.arc);
      cur = cur.prev;
    }
    Collections.reverse(path);
    return path;
  }

  private static void insertTopK(List<BackPtr> list, BackPtr item, int k) {
    for (int i = 0; i < list.size(); i++) {
      BackPtr ex = list.get(i);
      if (ex.prevMorphTag == item.prevMorphTag
          && java.util.Objects.equals(ex.prev1Lex, item.prev1Lex)
          && java.util.Objects.equals(ex.prev2Lex, item.prev2Lex)) {
        if (item.score > ex.score) {
          list.set(i, item);
        }
        return;
      }
    }
    if (list.size() < k) {
      list.add(item);
      return;
    }
    int minIdx = 0;
    float minScore = list.get(0).score;
    for (int i = 1; i < list.size(); i++) {
      if (list.get(i).score < minScore) {
        minScore = list.get(i).score;
        minIdx = i;
      }
    }
    if (item.score > minScore) {
      list.set(minIdx, item);
    }
  }

  private record BackPtr(
      SpanArc arc,
      BackPtr prev,
      String prev2Lex,
      String prev1Lex,
      short prevMorphTag,
      float score) {}

  static void extractDenseFeatures(
      SentenceLattice lat,
      String prev2Lex,
      String prev1Lex,
      SpanArc arc,
      boolean useGbdt,
      SpanCandidateGenerator gen,
      float[] lm6Buf,
      float[] out) {
    String nextLex = SpanCandidateGenerator.nextLexicalWordOrNull(lat.tokens(), arc.endIdx);
    gen.computeSpanLmComparison(lat, prev2Lex, prev1Lex, arc, lm6Buf);

    float fwdSmooth2 = NormV2Types.clampLm(gen.lmBigram(prev1Lex, arc.firstWordLower));
    float rightSmooth2 = NormV2Types.clampLm(gen.lmBigram(arc.lastWordLower, nextLex));
    float intSmooth = arc.wordCount >= 2 ? NormV2Types.clampLm(arc.internalLmBonus) : 0.0f;
    float smoothSpanLlr = lm6Buf[0] - lm6Buf[1];
    float cleanFwd3 = lm6Buf[4];
    float cleanRight3 = lm6Buf[5];
    float cleanSpanLlr = lm6Buf[2] - lm6Buf[3];
    float cliticSkipLlr = gen.computeCliticTransparencyLlr(lat, arc);

    SpanToken rawTok = lat.tokens().get(arc.startIdx);
    boolean isIdentityLower =
        (arc.endIdx == arc.startIdx + 1) && arc.normLower.equals(rawTok.lower());

    out[F_SMOOTH_FWD2] = fwdSmooth2 * 0.20f;
    out[F_SMOOTH_RIGHT2] = rightSmooth2 * 0.20f;
    out[F_SMOOTH_INTERNAL] = intSmooth * 0.20f;
    out[F_SMOOTH_SPAN_LLR] = smoothSpanLlr * 0.20f;
    out[F_CLEAN_FWD3] = cleanFwd3 * 0.20f;
    out[F_CLEAN_RIGHT3] = cleanRight3 * 0.20f;
    out[F_CLEAN_SPAN_LLR] = cleanSpanLlr * 0.20f;
    out[F_PARALLEL_PROB] = arc.parallelProb;
    out[F_PARALLEL_LOG_CNT] = arc.parallelLogCount * 0.25f;
    out[F_SUFFIX_PRECISION] = arc.suffixPrecision;
    out[F_EDIT_COST] = arc.editCost;
    out[F_DIACRITIC_DIFFS] = arc.diacriticDiffs * 0.35f;
    out[F_IS_IDENTITY_LOWER] = isIdentityLower ? 1.0f : 0.0f;
    out[F_IS_REGULAR_MORPH] = arc.isRegularMorph ? 1.0f : 0.0f;
    out[F_CHAR_PHONOTACTICS] = clampPhono(arc.charPhonotacticScore) * 0.25f;
    out[F_OOF_GBDT_LOGPROB] = useGbdt ? arc.cachedGbdtScore : 0.0f;
    out[F_ASCII_SENT_RATIO] =
        ((arc.sourceMask & (NormV2Types.SRC_DEASCIIFIER | NormV2Types.SRC_FORMAL_MORPH)) != 0
                && arc.diacriticDiffs > 0)
            ? lat.sentenceAsciiRatio()
            : 0.0f;
    out[F_INFORMAL_EXACT_VS_STRIP] =
        (arc.sourceMask & NormV2Types.SRC_INFORMAL_EXACT) != 0
            ? 1.0f
            : (arc.stripsExplicitDiacritic ? -1.0f : 0.0f);
    out[F_CLITIC_SKIP_LLR] = cliticSkipLlr * 0.20f;
    out[F_QUESTION_VOWEL_HARMONY] = gen.computeQuestionCliticHarmony(lat, prev1Lex, arc) * 1.5f;
  }

  private static float clampPhono(float v) {
    if (Float.isNaN(v) || Float.isInfinite(v)) {
      return -4.5f;
    }
    return Math.max(-6.0f, Math.min(0.0f, v));
  }

  private static int extractUnarySparseFeatures(
      SentenceLattice lat, SpanCandidateGenerator gen, SpanArc arc, long[] buf) {
    int p = 0;
    List<SpanToken> toks = lat.tokens();
    SpanToken t0 = toks.get(arc.startIdx);
    String rawSpanLow =
        arc.endIdx == arc.startIdx + 1
            ? t0.lower()
            : (arc.endIdx == arc.startIdx + 2
                ? t0.lower() + "+" + toks.get(arc.startIdx + 1).lower()
                : t0.lower() + "+3");

    String prevRaw = arc.startIdx > 0 ? toks.get(arc.startIdx - 1).lower() : "^";
    String nextLex = SpanCandidateGenerator.nextLexicalWordOrNull(toks, arc.endIdx);
    String nextLexKey = nextLex == null ? "$" : nextLex;
    boolean isIdLow = (arc.endIdx == arc.startIdx + 1) && arc.normLower.equals(t0.lower());
    int asciiBkt =
        lat.sentenceAsciiRatio() > 0.45f ? 2 : (lat.sentenceAsciiRatio() > 0.10f ? 1 : 0);

    // 1. Exact lexical rewrite pair (skip context-free vowel bias on standalone mI when left word determines harmony!)
    if (arc.rawParallelTotal >= 3) {
      boolean rawExplicitDotlessI = rawSpanLow.indexOf('ı') >= 0;
      boolean prevPureAmbiguousAscii =
          !SpanCandidateGenerator.hasExplicitTurkishDiacritics(prevRaw)
              && SpanCandidateGenerator.hasAsciiAmbiguousChars(prevRaw);
      boolean contextGovernedQuestionTwin =
          arc.wordCount == 1
              && arc.startIdx > 0
              && !(rawExplicitDotlessI && prevPureAmbiguousAscii)
              && SpanCandidateGenerator.isStandaloneQuestionClitic(rawSpanLow)
              && SpanCandidateGenerator.isStandaloneQuestionClitic(arc.normLower)
              && gen.computeQuestionCliticHarmony(lat, prevRaw, arc) != 0.0f;
      if (!contextGovernedQuestionTwin) {
        buf[p++] = hash2(T_LEX_LOWER_PAIR, strHash(rawSpanLow), strHash(arc.normLower));
        buf[p++] = hash2(T_LEX_SURF_PAIR, strHash(t0.surface()), strHash(arc.normSurface));
      }
      buf[p++] =
          hash3(T_LEX_PAIR_NEXT, strHash(rawSpanLow), strHash(arc.normLower), strHash(nextLexKey));
      buf[p++] =
          hash3(T_PREV_LEX_PAIR, strHash(prevRaw), strHash(rawSpanLow), strHash(arc.normLower));
    }

    // 2. Identity vs edit x raw-OOV-status x candidate morphological validity x full 17-bit source mask
    List<SpanArc> slotArcs = lat.outgoing()[arc.startIdx];
    boolean rawRegMorph = !slotArcs.isEmpty() && slotArcs.get(0).isRegularMorph;
    int compactSrc = arc.sourceMask & 0x1FFFF;
    buf[p++] =
        hash3(
            T_ID_OOV_MORPH_SRC,
            (isIdLow ? 2 : 0) | (rawRegMorph ? 1 : 0),
            arc.isRegularMorph ? 1 : 0,
            compactSrc);
    buf[p++] =
        hash3(
            T_SRC_ASCII_DIAC,
            compactSrc,
            (asciiBkt << 1) | (rawRegMorph ? 1 : 0),
            arc.diacriticDiffs > 0 ? 1 : 0);

    // 3. Right lexical context lookahead (crucial for "bende + var" vs "ben de + geldim"!)
    buf[p++] = hash2(T_RIGHT_LEX_BIGRAM, strHash(arc.lastWordLower), strHash(nextLexKey));

    // 4. Split clitic features (de/da, mi, ki) x HEAD word POS/syntax x right context!
    if (arc.wordCount >= 2) {
      int headState = ((arc.firstWordMorphTag & 0xFFFF) << 3) | (arc.headSyntaxClass & 0x7);
      buf[p++] =
          hash3(T_SPLIT_TAIL_HEAD_NEXT, strHash(arc.lastWordLower), headState, strHash(nextLexKey));
      buf[p++] = hash2(T_SPLIT_TAIL_HEAD, strHash(arc.lastWordLower), headState);
      buf[p++] = hash2(T_SPLIT_TAIL_QMARK, strHash(arc.lastWordLower), lat.hasQuestionMark() ? 1 : 0);
    }

    // 5. Merge features (2:1 / 3:1)
    if (arc.endIdx - arc.startIdx >= 2) {
      buf[p++] = hash2(T_MERGE_RAW_NEXT, strHash(rawSpanLow), strHash(nextLexKey));
      buf[p++] = hash2(T_MERGE_PREV_NORM, strHash(prevRaw), strHash(arc.normLower));
      buf[p++] = hash2(T_MERGE_SRC_PROP, compactSrc, arc.isProperOrAbbr ? 1 : 0);
      buf[p++] =
          hash3(T_MERGE_MORPH_NEXT, arc.coarseMorphTag & 0xFFFF, compactSrc, strHash(nextLexKey));
    }

    // 6. Sentence-wide overt pronoun -> finite verb person agreement!
    if (lat.overtSubjectPerson() != 0 && arc.verbPerson != 0) {
      buf[p++] = hash3(T_SUBJ_VERB_AGREE, lat.overtSubjectPerson(), arc.verbPerson, isIdLow ? 1 : 0);
    }

    // 7. Casing & TDK apostrophe addition / erroneous apostrophe removal features
    if (arc.caseChanged
        || arc.apostropheAdded
        || arc.apostropheRemoved
        || t0.shape() != NormV2Types.CasingShape.LOWER) {
      buf[p++] =
          hash3(
              T_CASING_TRANSITION,
              t0.shape().ordinal(),
              arc.caseChanged ? 1 : 0,
              (arc.startIdx == 0 ? 2 : 0) | (arc.isProperOrAbbr ? 1 : 0));
      if (arc.apostropheAdded) {
        buf[p++] = hash2(T_APOSTROPHE_ADD, arc.isProperOrAbbr ? 1 : 0, compactSrc);
      }
      if (arc.apostropheRemoved) {
        buf[p++] = hash2(T_APOSTROPHE_DROP, arc.isProperOrAbbr ? 1 : 0, compactSrc);
      }
    }

    // 8. Coarse morph tag + right token shape
    int nextShape = arc.endIdx < toks.size() ? toks.get(arc.endIdx).shape().ordinal() : 5;
    buf[p++] = hash2(T_MORPH_NEXT_SHAPE, arc.coarseMorphTag, nextShape);

    // 9. Finite verb negation polarity match (only active when negation is present on raw or candidate)
    if (!isIdLow && (arc.rawNegVerbCue || arc.verbPolarity < 0)) {
      buf[p++] = hash3(T_POLARITY_MATCH, arc.rawNegVerbCue ? 1 : 0, arc.verbPolarity + 2, compactSrc);
    }

    // 10. Non-linear typo-confidence & clean-attestation interactions for unseen OOV 1:1 rewrites
    if (arc.endIdx == arc.startIdx + 1 && !isIdLow) {
      int lenBkt = t0.lower().length() >= 8 ? 3 : (t0.lower().length() >= 6 ? 2 : (t0.lower().length() >= 5 ? 1 : 0));
      int costBkt = arc.editCost <= 0.35f ? 0 : (arc.editCost <= 0.85f ? 1 : (arc.editCost <= 1.25f ? 2 : 3));
      int gainBkt =
          arc.unaryCleanGain >= 8.0f
              ? 4
              : (arc.unaryCleanGain >= 4.0f
                  ? 3
                  : (arc.unaryCleanGain >= 1.5f ? 2 : (arc.unaryCleanGain >= -0.5f ? 1 : 0)));
      float phonoGain = arc.charPhonotacticScore - arc.rawCharPhono;
      int phonoBkt = phonoGain >= 1.0f ? 3 : (phonoGain >= 0.35f ? 2 : (phonoGain >= -0.15f ? 1 : 0));
      int cleanCntBkt =
          arc.rawCleanCount >= 80
              ? 4
              : (arc.rawCleanCount >= 20
                  ? 3
                  : (arc.rawCleanCount >= 5 ? 2 : (arc.rawCleanCount >= 1 ? 1 : 0)));
      buf[p++] = hash3(T_SRC_LEN_COST_GAIN, compactSrc, (lenBkt << 3) | costBkt, gainBkt);
      buf[p++] = hash3(T_SRC_PHONO_GAIN, compactSrc, lenBkt, phonoBkt);
      buf[p++] = hash3(T_SRC_RAW_CLEAN_ATTEST, compactSrc, cleanCntBkt, t0.shape().ordinal());
    }

    // 11. Pronoun/nominal locative ("bende uyandırmıyor", "rekor bende.") vs. additive ("ben de geldim") syntax
    if (arc.endIdx == arc.startIdx + 1) {
      int pronCode = locativePronounCode(t0.lower());
      if (pronCode != 0) {
        buf[p++] = hash3(T_PRON_LOC_RIGHT, pronCode, arc.wordCount, arc.nextLexState);
      } else if (arc.wordCount == 2 && ("de".equals(arc.lastWordLower) || "da".equals(arc.lastWordLower))) {
        buf[p++] = hash3(T_PRON_LOC_RIGHT, 7, arc.headSyntaxClass, arc.nextLexState);
      }
    }

    return p;
  }

  private static int locativePronounCode(String rawLow) {
    return switch (rawLow) {
      case "bende" -> 1;
      case "sende" -> 2;
      case "onda", "oda" -> 3;
      case "bizde" -> 4;
      case "sizde" -> 5;
      default -> 0;
    };
  }

  private static int extractTransitionSparseFeatures(
      SentenceLattice lat,
      SpanCandidateGenerator gen,
      String prev1Lex,
      short prevMorphTag,
      SpanArc arc,
      long[] buf,
      int offset) {
    int p = offset;
    long prevW = prev1Lex == null ? 0x1111L : strHash(prev1Lex);
    int prevTag = prevMorphTag;

    // Decoded lexical bigram, morph-tag bigram, and cross word-morph transitions
    buf[p++] = hash2(T_TRANS_LEX_BIGRAM, prevW, strHash(arc.firstWordLower));
    buf[p++] = hash2(T_TRANS_MORPH_BIGRAM, prevTag, arc.firstWordMorphTag);
    buf[p++] = hash2(T_TRANS_WORD_TAG, prevW, arc.firstWordMorphTag);
    buf[p++] = hash2(T_TRANS_TAG_WORD, prevTag, strHash(arc.firstWordLower));
    if (arc.endIdx == arc.startIdx + 1) {
      if (arc.nextLexState != 0) {
        String rawLow = arc.normLower.length() <= 6 ? arc.firstWordLower : "";
        if ("ben".equals(rawLow)
            || "bende".equals(rawLow)
            || "sen".equals(rawLow)
            || "sende".equals(rawLow)
            || "o".equals(rawLow)
            || "onda".equals(rawLow)
            || "oda".equals(rawLow)
            || "biz".equals(rawLow)
            || "bizde".equals(rawLow)
            || "siz".equals(rawLow)
            || "sizde".equals(rawLow)) {
          buf[p++] = hash3(T_TRANS_PRON_LOC_LEFT, prevTag, arc.wordCount, arc.nextLexState);
        }
      }
      float qh = gen.computeQuestionCliticHarmony(lat, prev1Lex, arc);
      if (qh != 0.0f) {
        boolean isIdLow = arc.normLower.equals(lat.tokens().get(arc.startIdx).lower());
        buf[p++] = hash2(T_TRANS_QUESTION_HARMONY, qh > 0.0f ? 1 : -1, isIdLow ? 1 : 0);
      }
    }
    return p - offset;
  }

  private static float computeArcLoss(
      SentenceLattice lat, SpanArc arc, SpanArc[] goldAt, TrainConfig cfg) {
    SpanArc ga = goldAt[arc.startIdx];
    if (ga != null && ga.endIdx == arc.endIdx) {
      if (ga.normSurface.equals(arc.normSurface)) {
        return 0.0f;
      }
      if (ga.normLower.equals(arc.normLower)) {
        return cfg.casingMismatchLoss;
      }
      SpanToken rawTok = lat.tokens().get(arc.startIdx);
      boolean rawWasClean =
          (ga.endIdx == ga.startIdx + 1) && rawTok.lower().equals(ga.normLower);
      return rawWasClean ? cfg.cleanCorruptLoss : cfg.missedEditLoss;
    }
    return cfg.missedEditLoss + 0.5f;
  }

  /**
   * Projects aligned gold tokens onto reachable {@link SpanArc}s in {@code lat} (using exact match
   * when present and closest-Levenshtein fallback on rare unreachable gold tokens).
   */
  public static List<SpanArc> projectOraclePath(AlignedSentence sent, SentenceLattice lat) {
    List<SpanToken> latToks = lat.tokens();
    List<AlignedToken> goldToks = sent.tokens();
    int n = latToks.size();
    if (n == 0) {
      return List.of();
    }

    String[] goldByLatIdx = new String[n];
    int[] goldSpanEndByLatIdx = new int[n];
    if (goldToks.size() == n) {
      int i = 0;
      while (i < n) {
        String gNorm = goldToks.get(i).norm() == null ? "" : goldToks.get(i).norm().trim();
        if (gNorm.isEmpty()) {
          goldByLatIdx[i] = "";
          goldSpanEndByLatIdx[i] = i + 1;
          i++;
          continue;
        }
        int j = i + 1;
        while (j < n
            && (goldToks.get(j).norm() == null || goldToks.get(j).norm().trim().isEmpty())) {
          j++;
        }
        goldByLatIdx[i] = gNorm;
        goldSpanEndByLatIdx[i] = Math.min(n, Math.max(i + 1, j));
        i = j;
      }
    } else {
      for (int i = 0; i < n; i++) {
        goldByLatIdx[i] = latToks.get(i).surface();
        goldSpanEndByLatIdx[i] = i + 1;
      }
      for (int gi = 0; gi < goldToks.size(); gi++) {
        AlignedToken gt = goldToks.get(gi);
        String gNorm = gt.norm() == null ? "" : gt.norm().trim();
        if (gNorm.isEmpty()) {
          continue;
        }
        int gj = gi + 1;
        while (gj < goldToks.size()
            && (goldToks.get(gj).norm() == null || goldToks.get(gj).norm().trim().isEmpty())) {
          gj++;
        }
        int charStart = gt.start();
        int charEnd = goldToks.get(gj - 1).end();
        int li = findLatTokenByStart(latToks, charStart);
        int lj = findLatTokenAfterEnd(latToks, charEnd, li);
        if (li >= 0 && lj > li) {
          goldByLatIdx[li] = gNorm;
          goldSpanEndByLatIdx[li] = lj;
        }
      }
    }

    List<SpanArc> projected = new ArrayList<>(n);
    int pos = 0;
    while (pos < n) {
      String targetSurf = goldByLatIdx[pos];
      int targetEnd = goldSpanEndByLatIdx[pos];
      if (targetSurf == null || targetSurf.isEmpty() || targetEnd <= pos) {
        targetSurf = latToks.get(pos).surface();
        targetEnd = pos + 1;
      }
      String targetLow = NormV2Types.trLower(targetSurf);
      List<SpanArc> cands = lat.outgoing()[pos];

      SpanArc exactCased = null;
      SpanArc exactLower = null;
      SpanArc closestSameSpan = null;
      float closestDist = Float.POSITIVE_INFINITY;

      for (SpanArc a : cands) {
        if (a.endIdx == targetEnd) {
          if (a.normSurface.equals(targetSurf)) {
            exactCased = a;
            break;
          }
          if (a.normLower.equals(targetLow) && exactLower == null) {
            exactLower = a;
          }
          float d = SpanCandidateGenerator.computeWeightedEditCost(a.normLower, targetLow);
          if (d < closestDist) {
            closestDist = d;
            closestSameSpan = a;
          }
        }
      }

      if (exactCased != null) {
        projected.add(exactCased);
        pos = exactCased.endIdx;
      } else if (exactLower != null) {
        projected.add(exactLower);
        pos = exactLower.endIdx;
      } else if (closestSameSpan != null) {
        projected.add(closestSameSpan);
        pos = closestSameSpan.endIdx;
      } else {
        SpanArc fallback = cands.get(0);
        projected.add(fallback);
        pos = fallback.endIdx;
      }
    }
    return projected;
  }

  private static int findLatTokenByStart(List<SpanToken> toks, int start) {
    for (int i = 0; i < toks.size(); i++) {
      if (toks.get(i).start() == start) {
        return i;
      }
    }
    return -1;
  }

  private static int findLatTokenAfterEnd(List<SpanToken> toks, int end, int minIdx) {
    if (minIdx < 0) {
      return -1;
    }
    for (int i = minIdx; i < toks.size(); i++) {
      if (toks.get(i).end() >= end) {
        return i + 1;
      }
    }
    return minIdx + 1;
  }

  private static boolean samePath(List<SpanArc> a, List<SpanArc> b) {
    if (a.size() != b.size()) {
      return false;
    }
    for (int i = 0; i < a.size(); i++) {
      SpanArc x = a.get(i);
      SpanArc y = b.get(i);
      if (x.startIdx != y.startIdx
          || x.endIdx != y.endIdx
          || !x.normSurface.equals(y.normSurface)) {
        return false;
      }
    }
    return true;
  }

  private static long strHash(String s) {
    if (s == null) {
      return 0L;
    }
    long h = 0xcbf29ce484222325L;
    for (int i = 0; i < s.length(); i++) {
      h ^= s.charAt(i);
      h *= 0x100000001b3L;
    }
    return h;
  }

  private static long hash2(int template, long a, long b) {
    long h = 0x9E3779B97F4A7C15L * (template + 17L);
    h = mix64(h ^ a);
    h = mix64(h ^ b);
    return h == 0L ? 1L : h;
  }

  private static long hash3(int template, long a, long b, long c) {
    long h = 0x9E3779B97F4A7C15L * (template + 31L);
    h = mix64(h ^ a);
    h = mix64(h ^ b);
    h = mix64(h ^ c);
    return h == 0L ? 1L : h;
  }

  private static long mix64(long z) {
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
    return z ^ (z >>> 31);
  }
}
