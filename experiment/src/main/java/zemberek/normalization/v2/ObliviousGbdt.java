package zemberek.normalization.v2;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Compact, pure-Java <b>Oblivious (Symmetric) Gradient Boosted Decision Trees (GBDT)</b>
 * ({@code CatBoost}-style) with multi-threaded quantile-binned Newton histogram training and
 * branch-minimal L1-cache array inference.
 *
 * <p><b>What is an Oblivious GBDT?</b>
 * <ul>
 *   <li><b>GBDT (Gradient Boosted Decision Trees)</b> builds an additive sequence of shallow
 *       decision trees where each tree fits the second-order Taylor (Newton) step of binary
 *       cross-entropy ({@code LogLoss}) on the residuals of previous trees.
 *   <li><b>Oblivious / Symmetric Trees</b> constrain all {@code 2^d} internal nodes at tree depth
 *       {@code d} to test the exact same {@code (feature, threshold)} condition. Consequently,
 *       evaluating a depth-{@code 6} tree requires zero pointer chasing: {@code 6} comparisons pack
 *       a {@code 6}-bit integer {@code leaf in [0, 63]} that indexes directly into a flat {@code
 *       float[]} array ({@code ~292 bytes/tree}; {@code 360} depth-6 trees occupy {@code ~103 KB}).
 * </ul>
 */
public final class ObliviousGbdt {

  /** Training hyperparameters for {@link ObliviousGbdt#trainBinaryClassifier}. */
  public static final class HyperParams {
    /** Number of sequential boosting trees in the ensemble. */
    public int numTrees = 180;

    /** Symmetric depth {@code D} of each tree ({@code 2^D} leaves per tree; typically {@code 5} or {@code 6}). */
    public int depth = 6;

    /** Shrinkage factor {@code eta} applied to each Newton leaf update ({@code 0.05 .. 0.10}). */
    public float learningRate = 0.08f;

    /** L2 Hessian regularization {@code lambda} stabilizing leaf steps on sparse bins. */
    public float l2Reg = 4.0f;

    /** Number of quantile histogram buckets per continuous feature ({@code <= 127}, stored in {@code byte[]}). */
    public int maxBins = 64;

    public HyperParams() {}

    public HyperParams(int numTrees, int depth, float learningRate, float l2Reg, int maxBins) {
      this.numTrees = numTrees;
      this.depth = depth;
      this.learningRate = learningRate;
      this.l2Reg = l2Reg;
      this.maxBins = maxBins;
    }
  }

  private final int numFeatures;
  private final int numTrees;
  private final int depth;
  private final int leavesPerTree;
  private final float baseScore;
  private final short[] splitFeatures; // length = numTrees * depth
  private final float[] splitThresholds; // length = numTrees * depth
  private final float[] leafValues; // length = numTrees * (1 << depth)

  public ObliviousGbdt(
      int numFeatures,
      int numTrees,
      int depth,
      float baseScore,
      short[] splitFeatures,
      float[] splitThresholds,
      float[] leafValues) {
    this.numFeatures = numFeatures;
    this.numTrees = numTrees;
    this.depth = depth;
    this.leavesPerTree = 1 << depth;
    this.baseScore = baseScore;
    this.splitFeatures = splitFeatures;
    this.splitThresholds = splitThresholds;
    this.leafValues = leafValues;
  }

  public float predictLogit(float[] features) {
    float sum = baseScore;
    int splitOff = 0;
    int leafOff = 0;
    int dMax = depth;
    int stride = leavesPerTree;
    for (int t = 0; t < numTrees; t++) {
      int leaf = 0;
      for (int d = 0; d < dMax; d++) {
        int f = splitFeatures[splitOff + d];
        float thr = splitThresholds[splitOff + d];
        leaf = (leaf << 1) | (features[f] > thr ? 1 : 0);
      }
      sum += leafValues[leafOff + leaf];
      splitOff += dMax;
      leafOff += stride;
    }
    return sum;
  }

  public float predictProb(float[] features) {
    return sigmoid(predictLogit(features));
  }

  public long ramBytesUsed() {
    return splitFeatures.length * 2L
        + splitThresholds.length * 4L
        + leafValues.length * 4L
        + 48L;
  }

  public int numFeatures() {
    return numFeatures;
  }

  public int numTrees() {
    return numTrees;
  }

  public int depth() {
    return depth;
  }

  public float baseScore() {
    return baseScore;
  }

  public short[] splitFeatures() {
    return splitFeatures;
  }

  public float[] splitThresholds() {
    return splitThresholds;
  }

  public float[] leafValues() {
    return leafValues;
  }

  public static float sigmoid(float z) {
    if (z >= 16.0f) {
      return 0.9999999f;
    }
    if (z <= -16.0f) {
      return 0.0000001f;
    }
    return (float) (1.0 / (1.0 + Math.exp(-z)));
  }

  /**
   * Trains an oblivious GBDT ensemble using weighted binary Newton boosting ({@code LogLoss}).
   *
   * @param flatX row-major {@code [numSamples * numFeatures]} continuous feature matrix
   * @param labels {@code 0} or {@code 1} target per sample
   * @param sampleWeights positive importance weight per sample
   */
  public static ObliviousGbdt trainBinaryClassifier(
      float[] flatX,
      byte[] labels,
      float[] sampleWeights,
      int numSamples,
      int numFeatures,
      HyperParams hp) {

    if (numSamples == 0) {
      return new ObliviousGbdt(
          numFeatures, 0, hp.depth, 0.0f, new short[0], new float[0], new float[0]);
    }
    long expectedCells = (long) numSamples * numFeatures;
    if (expectedCells > flatX.length) {
      throw new IllegalArgumentException(
          "flatX too short: expected " + expectedCells + " cells, got " + flatX.length);
    }

    int maxBins = Math.min(126, Math.max(8, hp.maxBins));
    float[][] binThresholds = new float[numFeatures][];
    // Column-major binned matrix [f][i] for sequential cache-line sweeps during feature search!
    byte[][] binnedCols = new byte[numFeatures][numSamples];

    IntStream.range(0, numFeatures)
        .parallel()
        .forEach(
            f -> {
              float[] col = new float[numSamples];
              for (int i = 0, idx = f; i < numSamples; i++, idx += numFeatures) {
                col[i] = flatX[idx];
              }
              float[] thr = buildQuantileThresholds(col, maxBins);
              binThresholds[f] = thr;
              byte[] bCol = binnedCols[f];
              for (int i = 0; i < numSamples; i++) {
                bCol[i] = (byte) findBin(thr, col[i]);
              }
            });

    // Compute weighted base prior logit
    double posW = 0.0;
    double totW = 0.0;
    for (int i = 0; i < numSamples; i++) {
      float w = sampleWeights[i];
      totW += w;
      if (labels[i] != 0) {
        posW += w;
      }
    }
    double p0 = Math.max(1e-4, Math.min(1.0 - 1e-4, posW / Math.max(1.0, totW)));
    float baseLogit = (float) Math.log(p0 / (1.0 - p0));

    float[] preds = new float[numSamples];
    Arrays.fill(preds, baseLogit);
    float[] grads = new float[numSamples];
    float[] hess = new float[numSamples];
    int[] curLeaf = new int[numSamples];

    int numTrees = hp.numTrees;
    int depth = hp.depth;
    int leavesPerTree = 1 << depth;
    short[] allSplitFeats = new short[numTrees * depth];
    float[] allSplitThrs = new float[numTrees * depth];
    float[] allLeafVals = new float[numTrees * leavesPerTree];

    final int numChunks = numSamples >= 65_536 ? 16 : 1;
    final int chunkSize = (numSamples + numChunks - 1) / numChunks;

    for (int t = 0; t < numTrees; t++) {
      // 1. Compute elementwise logistic gradients and Hessians (deterministic across chunks)
      if (numChunks > 1) {
        IntStream.range(0, numChunks)
            .parallel()
            .forEach(
                ch -> {
                  int start = ch * chunkSize;
                  int end = Math.min(numSamples, start + chunkSize);
                  for (int i = start; i < end; i++) {
                    float prob = sigmoid(preds[i]);
                    float w = sampleWeights[i];
                    float y = labels[i] != 0 ? 1.0f : 0.0f;
                    grads[i] = w * (y - prob);
                    hess[i] = Math.max(1e-6f, w * prob * (1.0f - prob));
                    curLeaf[i] = 0;
                  }
                });
      } else {
        for (int i = 0; i < numSamples; i++) {
          float prob = sigmoid(preds[i]);
          float w = sampleWeights[i];
          float y = labels[i] != 0 ? 1.0f : 0.0f;
          grads[i] = w * (y - prob);
          hess[i] = Math.max(1e-6f, w * prob * (1.0f - prob));
          curLeaf[i] = 0;
        }
      }

      // 2. Grow oblivious levels d = 0 .. depth - 1
      for (int d = 0; d < depth; d++) {
        final int numNodesAtLevel = 1 << d;
        // Compute current node totals before split
        final double[] nodeTotG = new double[numNodesAtLevel];
        final double[] nodeTotH = new double[numNodesAtLevel];
        for (int i = 0; i < numSamples; i++) {
          int nd = curLeaf[i];
          nodeTotG[nd] += grads[i];
          nodeTotH[nd] += hess[i];
        }

        double curScoreSum = 0.0;
        for (int nd = 0; nd < numNodesAtLevel; nd++) {
          curScoreSum += (nodeTotG[nd] * nodeTotG[nd]) / (nodeTotH[nd] + hp.l2Reg);
        }
        final double baseLevelScore = curScoreSum;

        // Evaluate all candidate features in parallel
        SplitCandidate[] bestPerFeat = new SplitCandidate[numFeatures];
        IntStream.range(0, numFeatures)
            .parallel()
            .forEach(
                f -> {
                  float[] thrs = binThresholds[f];
                  int nSplits = thrs.length;
                  if (nSplits == 0) {
                    bestPerFeat[f] = new SplitCandidate(f, -1, 0.0f, -1.0);
                    return;
                  }
                  int nBins = nSplits + 1;
                  double[] histG = new double[numNodesAtLevel * nBins];
                  double[] histH = new double[numNodesAtLevel * nBins];
                  byte[] bCol = binnedCols[f];
                  for (int i = 0; i < numSamples; i++) {
                    int idx = curLeaf[i] * nBins + bCol[i];
                    histG[idx] += grads[i];
                    histH[idx] += hess[i];
                  }

                  int bestBin = -1;
                  double bestGain = -1.0;
                  double[] leftG = new double[numNodesAtLevel];
                  double[] leftH = new double[numNodesAtLevel];

                  for (int b = 0; b < nSplits; b++) {
                    double splitScore = 0.0;
                    for (int nd = 0; nd < numNodesAtLevel; nd++) {
                      int idx = nd * nBins + b;
                      double lg = (leftG[nd] += histG[idx]);
                      double lh = (leftH[nd] += histH[idx]);
                      double rg = nodeTotG[nd] - lg;
                      double rh = nodeTotH[nd] - lh;
                      splitScore +=
                          (lg * lg) / (lh + hp.l2Reg) + (rg * rg) / (rh + hp.l2Reg);
                    }
                    double gain = splitScore - baseLevelScore;
                    if (gain > bestGain) {
                      bestGain = gain;
                      bestBin = b;
                    }
                  }
                  bestPerFeat[f] =
                      new SplitCandidate(
                          f, bestBin, bestBin >= 0 ? thrs[bestBin] : 0.0f, bestGain);
                });

        SplitCandidate chosen = bestPerFeat[0];
        for (int f = 1; f < numFeatures; f++) {
          if (bestPerFeat[f].gain > chosen.gain) {
            chosen = bestPerFeat[f];
          }
        }

        int splitIdx = t * depth + d;
        if (chosen.bin < 0 || chosen.gain <= 1e-7) {
          allSplitFeats[splitIdx] = 0;
          allSplitThrs[splitIdx] = Float.POSITIVE_INFINITY;
          if (numChunks > 1) {
            IntStream.range(0, numChunks)
                .parallel()
                .forEach(
                    ch -> {
                      int start = ch * chunkSize;
                      int end = Math.min(numSamples, start + chunkSize);
                      for (int i = start; i < end; i++) {
                        curLeaf[i] = curLeaf[i] << 1;
                      }
                    });
          } else {
            for (int i = 0; i < numSamples; i++) {
              curLeaf[i] = curLeaf[i] << 1;
            }
          }
        } else {
          allSplitFeats[splitIdx] = (short) chosen.feature;
          allSplitThrs[splitIdx] = chosen.threshold;
          byte[] bCol = binnedCols[chosen.feature];
          int cutBin = chosen.bin;
          if (numChunks > 1) {
            IntStream.range(0, numChunks)
                .parallel()
                .forEach(
                    ch -> {
                      int start = ch * chunkSize;
                      int end = Math.min(numSamples, start + chunkSize);
                      for (int i = start; i < end; i++) {
                        curLeaf[i] = (curLeaf[i] << 1) | (bCol[i] > cutBin ? 1 : 0);
                      }
                    });
          } else {
            for (int i = 0; i < numSamples; i++) {
              curLeaf[i] = (curLeaf[i] << 1) | (bCol[i] > cutBin ? 1 : 0);
            }
          }
        }
      }

      // 3. Compute L2-regularized Newton leaf values and update predictions
      double[] leafG = new double[leavesPerTree];
      double[] leafH = new double[leavesPerTree];
      for (int i = 0; i < numSamples; i++) {
        int lf = curLeaf[i];
        leafG[lf] += grads[i];
        leafH[lf] += hess[i];
      }
      int leafBase = t * leavesPerTree;
      for (int lf = 0; lf < leavesPerTree; lf++) {
        float val =
            leafH[lf] > 0.0
                ? (float) (hp.learningRate * (leafG[lf] / (leafH[lf] + hp.l2Reg)))
                : 0.0f;
        allLeafVals[leafBase + lf] = val;
      }
      if (numChunks > 1) {
        IntStream.range(0, numChunks)
            .parallel()
            .forEach(
                ch -> {
                  int start = ch * chunkSize;
                  int end = Math.min(numSamples, start + chunkSize);
                  for (int i = start; i < end; i++) {
                    preds[i] += allLeafVals[leafBase + curLeaf[i]];
                  }
                });
      } else {
        for (int i = 0; i < numSamples; i++) {
          preds[i] += allLeafVals[leafBase + curLeaf[i]];
        }
      }
    }

    return new ObliviousGbdt(
        numFeatures, numTrees, depth, baseLogit, allSplitFeats, allSplitThrs, allLeafVals);
  }

  private record SplitCandidate(int feature, int bin, float threshold, double gain) {}

  private static float[] buildQuantileThresholds(float[] col, int maxBins) {
    int stride = Math.max(1, col.length / 65_536);
    float[] sample = new float[(col.length + stride - 1) / stride];
    int p = 0;
    for (int i = 0; i < col.length; i += stride) {
      sample[p++] = col[i];
    }
    if (p < sample.length) {
      sample = Arrays.copyOf(sample, p);
    }
    Arrays.sort(sample);
    float[] tmp = new float[maxBins];
    int cnt = 0;
    for (int b = 1; b <= maxBins; b++) {
      int idx = (int) (((long) b * (sample.length - 1)) / (maxBins + 1));
      float v = sample[idx];
      if (cnt == 0 || v > tmp[cnt - 1] + 1e-7f) {
        tmp[cnt++] = v;
      }
    }
    return Arrays.copyOf(tmp, cnt);
  }

  private static int findBin(float[] thresholds, float v) {
    int lo = 0;
    int hi = thresholds.length;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (v > thresholds[mid]) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }
}
