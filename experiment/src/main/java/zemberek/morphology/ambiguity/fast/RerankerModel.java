package zemberek.morphology.ambiguity.fast;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import zemberek.core.collections.FloatValueMap;
import zemberek.morphology.analysis.SingleAnalysis;

/**
 * Compact linear model for second-stage ambiguity re-ranking (tie-breaker).
 * Operates on contrastive difference vectors: x = phi(c2) - phi(c1).
 * Activates when Stage 1 score margin delta <= maxDelta.
 * Overrides Stage 1 choice (flips to c2) when score(x) > theta.
 */
public class RerankerModel {

  public static final int MAGIC = 0x5a454d52; // "ZEMR"
  public static final int VERSION = 1;

  private final FloatValueMap<String> weights;
  private float maxDelta = 3.0f;
  private float theta = 0.5f;

  public RerankerModel(FloatValueMap<String> weights, float maxDelta, float theta) {
    this.weights = weights;
    this.maxDelta = maxDelta;
    this.theta = theta;
  }

  public float getMaxDelta() {
    return maxDelta;
  }

  public void setMaxDelta(float maxDelta) {
    this.maxDelta = maxDelta;
  }

  public float getTheta() {
    return theta;
  }

  public void setTheta(float theta) {
    this.theta = theta;
  }

  public FloatValueMap<String> getWeights() {
    return weights;
  }

  public int featureCount() {
    return weights.size();
  }

  public float getWeight(String feature) {
    return weights.get(feature);
  }

  /**
   * Computes the linear flip score: w . (phi(c2) - phi(c1))
   */
  public float score(FloatValueMap<String> diffFeatures) {
    if (diffFeatures == null || diffFeatures.size() == 0) return 0.0f;
    float sum = 0.0f;
    for (FloatValueMap.Entry<String> entry : diffFeatures.iterableEntries()) {
      float w = weights.get(entry.key);
      if (w != 0.0f) {
        sum += w * entry.value;
      }
    }
    return sum;
  }

  /**
   * Decides whether to flip from c1 to c2.
   */
  public boolean shouldFlip(
      SingleAnalysis c1,
      SingleAnalysis c2,
      int tokenIndex,
      List<SingleAnalysis> sentenceAnalyses,
      float delta) {

    if (delta > maxDelta) return false;
    FloatValueMap<String> diff = RerankerFeatureExtractor.extractDifferenceVector(
        c1, c2, tokenIndex, sentenceAnalyses, delta);
    float s = score(diff);
    return s > theta;
  }

  public void saveToFile(Path path) throws IOException {
    try (DataOutputStream dos = new DataOutputStream(
        new BufferedOutputStream(Files.newOutputStream(path)))) {
      dos.writeInt(MAGIC);
      dos.writeInt(VERSION);
      dos.writeFloat(maxDelta);
      dos.writeFloat(theta);
      dos.writeInt(weights.size());
      for (FloatValueMap.Entry<String> entry : weights.iterableEntries()) {
        dos.writeUTF(entry.key);
        dos.writeFloat(entry.value);
      }
    }
  }

  public static RerankerModel loadFromFile(Path path) throws IOException {
    try (DataInputStream dis = new DataInputStream(
        new BufferedInputStream(Files.newInputStream(path)))) {
      int magic = dis.readInt();
      if (magic != MAGIC) {
        throw new IOException("Invalid reranker model magic: " + Integer.toHexString(magic));
      }
      int version = dis.readInt();
      if (version != VERSION) {
        throw new IOException("Unsupported reranker model version: " + version);
      }
      float maxDelta = dis.readFloat();
      float theta = dis.readFloat();
      int count = dis.readInt();
      FloatValueMap<String> weights = new FloatValueMap<>(count);
      for (int i = 0; i < count; i++) {
        String key = dis.readUTF();
        float val = dis.readFloat();
        weights.set(key, val);
      }
      return new RerankerModel(weights, maxDelta, theta);
    }
  }

  public void saveToTextFile(Path path) throws IOException {
    List<FloatValueMap.Entry<String>> entries = new ArrayList<>(weights.size());
    for (FloatValueMap.Entry<String> e : weights.iterableEntries()) {
      entries.add(e);
    }
    entries.sort((a, b) -> Float.compare(Math.abs(b.value), Math.abs(a.value)));

    try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      writer.write("# Reranker Model Weights\n");
      writer.write(String.format(Locale.ROOT, "# maxDelta=%.4f theta=%.4f featureCount=%d\n", maxDelta, theta, weights.size()));
      for (FloatValueMap.Entry<String> e : entries) {
        writer.write(String.format(Locale.ROOT, "%s\t%.6f\n", e.key, e.value));
      }
    }
  }
}
