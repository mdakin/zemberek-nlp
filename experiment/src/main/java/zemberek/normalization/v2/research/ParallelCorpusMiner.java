package zemberek.normalization.v2.research;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import zemberek.core.turkish.SecondaryPos;
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.analysis.SingleAnalysis;
import zemberek.morphology.morphotactics.TurkishMorphotactics;
import zemberek.normalization.v2.research.NormV2Types.AlignedSentence;
import zemberek.normalization.v2.research.NormV2Types.AlignedToken;
import zemberek.normalization.v2.research.NormV2Types.CasingShape;

/**
 * Offline empirical knowledge miner &amp; <b>LOO (Leave-One-Sentence-Out)</b> jackknife helper for
 * {@code v2.3} Turkish text normalization.
 *
 * <p>Extracts from aligned parallel {@code (noisy -> clean)} training slices and unlabeled clean
 * Turkish corpora (with strict SHA/fingerprint benchmark-leakage filtering):
 * <ol>
 *   <li>{@code parallel1ToN}: Dirichlet-smoothed {@code P(y | x)} rewrite probabilities &amp; counts
 *       for {@code 1:1} and {@code 1:2} splits, plus exact {@code keepCount}.
 *   <li>{@code parallel2To1} &amp; {@code parallel3To1}: {@code 2:1} and {@code 3:1} merge odds
 *       normalized by total raw bigram/trigram co-occurrences across the training corpus.
 *   <li>{@code suffixRulesByLastChar}: automatically induced productive informal suffix rules
 *       ({@code -iom -> -iyorum}, {@code -mucak -> -mayacak}, {@code -cam -> -cağım}, etc.).
 *   <li>{@code casingLexicon}: mid-sentence {@code (lower, Title, UPPER, Mixed)} frequencies and
 *       <b>TDK</b> (<i>Türk Dil Kurumu</i>) proper-noun / acronym apostrophe stems ({@code
 *       türkiye'}, {@code akp'}, {@code youtube'}).
 *   <li>{@code cleanLm}: compact primitive open-addressing 64-bit hashed 3-gram Stupid-Backoff
 *       clean Turkish language model ({@link CleanWordLm}) mined via 3-pass Apriori pruning from
 *       {@code ~1.8M} clean sentences (~108 MB RAM).
 *   <li>{@code charPhonotactics}: smoothed character 4-gram Turkish log-probability model scoring
 *       vowel-harmony and consonant-cluster well-formedness.
 * </ol>
 */
public final class ParallelCorpusMiner {

  private ParallelCorpusMiner() {}

  // ===============================================================================================
  // Contextual Truecasing Bitflags (B2 Layer 1 — zero lattice-arc duplication)
  // ===============================================================================================

  /** Sentence has noisy Title-Case-Every-Word style ({@code >=45%} of words capitalized). */
  public static final int CTX_SENT_TITLE_NOISY = 1 << 0;

  /** Immediately adjacent to a calendar day number ({@code 15 Nisan}) or 4-digit year ({@code Mayıs 2024}). */
  public static final int CTX_ADJ_DATE_NUM = 1 << 1;

  /** Immediately precedes a Gazetteer / Institutional / Street / Honorific head ({@code Konur Sokak'taki}). */
  public static final int CTX_BEFORE_GAZETTEER_OR_HONORIFIC = 1 << 2;

  /** Immediately follows a numeric token ({@code 5000 mAh} vs {@code Yıldızevler Mah}). */
  public static final int CTX_AFTER_NUMBER = 1 << 3;

  /** TDK two-word holiday, post-name honorific, or given-name before ProperNoun surname ({@code Sevgililer Günü}, {@code Lale Hanım}, {@code Ümit Özdağ}). */
  public static final int CTX_PROMOTE_PROPER_IN_CONTEXT = 1 << 4;

  private static final Set<String> TDK_MONTHS_AND_DAYS =
      Set.of(
          "ocak",
          "şubat",
          "mart",
          "nisan",
          "mayıs",
          "haziran",
          "temmuz",
          "ağustos",
          "eylül",
          "ekim",
          "kasım",
          "aralık",
          "pazartesi",
          "salı",
          "çarşamba",
          "perşembe",
          "cuma",
          "cumartesi",
          "pazar");

  private static final Set<String> LOWERCASE_HEADLINE_BIAS_STOPWORDS =
      Set.of(
          "pro", "max", "plus", "mini", "ultra", "air", "ban", "old", "new", "free", "full",
          "two", "top", "big", "good", "bad", "come", "get", "set", "run", "aka", "prof", "vs",
          "vb", "edp", "edt", "angel", "price", "grey", "express", "number", "super", "rootlu",
          "demokrat", "bankası", "üniversitesi", "müdürlüğü", "başkanı", "başkanlığı",
          "bakanlığı", "belediyesi", "paşa", "reis", "makbule", "menteşe", "çat", "ikra");

  private static final Set<String> LOWERCASE_APOSTROPHE_SLANG_STEMS =
      Set.of(
          "entry", "pp", "dm", "pm", "sub", "nick", "post", "story", "tweet", "fav", "rt", "mod",
          "ban", "bug", "lag", "ping", "fps", "btc-e", "recession", "spree");

  private static final Set<String> CANONICAL_UPPER_ACRONYM_STEMS =
      Set.of("seo", "php", "mp3", "mp4", "llm", "html", "sql", "smm", "asp", "iddk");

  private static final Map<String, String> CANONICAL_DOTTED_ABBR = buildCanonicalDottedAbbr();
  private static final Map<String, String> CANONICAL_TECH_TOKENS = buildCanonicalTechTokens();

  private static Map<String, String> buildCanonicalDottedAbbr() {
    Map<String, String> m = new HashMap<>(48);
    m.put("vb.", "vb.");
    m.put("vs.", "vs.");
    m.put("dr.", "Dr.");
    m.put("prof.", "Prof.");
    m.put("doç.", "Doç.");
    m.put("av.", "Av.");
    m.put("org.", "Org.");
    m.put("alb.", "Alb.");
    m.put("yrd.", "Yrd.");
    m.put("arş.", "Arş.");
    m.put("gör.", "Gör.");
    m.put("uzm.", "Uzm.");
    m.put("ecz.", "Ecz.");
    m.put("müh.", "Müh.");
    m.put("mim.", "Mim.");
    m.put("vet.", "Vet.");
    m.put("hz.", "Hz.");
    m.put("ltd.", "Ltd.");
    m.put("tic.", "Tic.");
    m.put("şti.", "Şti.");
    m.put("san.", "San.");
    m.put("mah.", "Mah.");
    m.put("cad.", "Cad.");
    m.put("sok.", "Sok.");
    m.put("apt.", "Apt.");
    m.put("no.", "No.");
    m.put("tel.", "Tel.");
    return Map.copyOf(m);
  }

  private static Map<String, String> buildCanonicalTechTokens() {
    Map<String, String> m = new HashMap<>(32);
    m.put("2k", "2K");
    m.put("4k", "4K");
    m.put("8k", "8K");
    m.put("i3", "i3");
    m.put("i5", "i5");
    m.put("i7", "i7");
    m.put("i9", "i9");
    m.put("ghz", "GHz");
    m.put("mhz", "MHz");
    m.put("mbps", "Mbps");
    m.put("gbps", "Gbps");
    m.put("mp3", "MP3");
    m.put("mp4", "MP4");
    m.put("seo", "SEO");
    m.put("php", "PHP");
    m.put("html", "HTML");
    m.put("sql", "SQL");
    return Map.copyOf(m);
  }

  public record TargetCount(String normLower, String bestSurface, int count) {}

  public record RewriteEntry(int totalCount, int keepCount, TargetCount[] targets) {
    public float keepProb() {
      return (keepCount + 1.5f) / (totalCount + 3.0f);
    }

    public float looKeepProb(boolean subtractKeep) {
      int tot = Math.max(0, totalCount - 1);
      int kp = Math.max(0, keepCount - (subtractKeep ? 1 : 0));
      return (kp + 1.5f) / (tot + 3.0f);
    }

    public float probOf(String normLower) {
      for (TargetCount tc : targets) {
        if (tc.normLower.equals(normLower)) {
          return tc.count / (totalCount + 3.0f);
        }
      }
      return 0.0f;
    }

    public int countOf(String normLower) {
      for (TargetCount tc : targets) {
        if (tc.normLower.equals(normLower)) {
          return tc.count;
        }
      }
      return 0;
    }
  }

  public record MergeEntry(int cooccurCount, TargetCount[] mergedTargets) {
    public int totalMergedCount() {
      int s = 0;
      for (TargetCount tc : mergedTargets) {
        s += tc.count;
      }
      return s;
    }

    public float mergeProb(TargetCount tc) {
      return tc.count / (Math.max(cooccurCount, totalMergedCount()) + 3.0f);
    }

    public int countOf(String normLower) {
      for (TargetCount tc : mergedTargets) {
        if (tc.normLower.equals(normLower)) {
          return tc.count;
        }
      }
      return 0;
    }
  }

  public record SuffixRule(
      String rawSuffix, String normSuffix, int support, int totalRawSuffixSeen, float precision) {}

  public static final class CasingCounts {
    public int lower;
    public int title;
    public int upper;
    public int mixed;
    public int apostropheStem;
    public String bestMixedSurface;
    public int bestMixedCount;

    public int total() {
      return lower + title + upper + mixed;
    }

    public void recordPlausibleMixed(String surface, int count) {
      if (isPlausibleBrandMixedCase(surface) && count > bestMixedCount) {
        bestMixedSurface = surface;
        bestMixedCount = count;
      }
    }

    public CasingCounts copy() {
      CasingCounts c = new CasingCounts();
      c.lower = this.lower;
      c.title = this.title;
      c.upper = this.upper;
      c.mixed = this.mixed;
      c.apostropheStem = this.apostropheStem;
      c.bestMixedSurface = this.bestMixedSurface;
      c.bestMixedCount = this.bestMixedCount;
      return c;
    }
  }

  /** Fast primitive open-addressing {@code long -> int} hash table with zero object overhead. */
  static final class OpenLongIntMap {
    private long[] keys;
    private int[] vals;
    private int mask;
    private int size;
    private int threshold;

    OpenLongIntMap(int initialPow2) {
      int cap = 1;
      while (cap < initialPow2) {
        cap <<= 1;
      }
      this.keys = new long[cap];
      this.vals = new int[cap];
      this.mask = cap - 1;
      this.threshold = (int) (cap * 0.68f);
    }

    int get(long k) {
      if (k == 0L) {
        k = 1L;
      }
      int slot = slotOf(k) & mask;
      while (true) {
        long cur = keys[slot];
        if (cur == 0L) {
          return 0;
        }
        if (cur == k) {
          return vals[slot];
        }
        slot = (slot + 1) & mask;
      }
    }

    void add(long k, int delta) {
      if (k == 0L) {
        k = 1L;
      }
      if (size >= threshold) {
        rehash(keys.length << 1);
      }
      int slot = slotOf(k) & mask;
      while (true) {
        long cur = keys[slot];
        if (cur == 0L) {
          keys[slot] = k;
          vals[slot] = delta;
          size++;
          return;
        }
        if (cur == k) {
          vals[slot] += delta;
          return;
        }
        slot = (slot + 1) & mask;
      }
    }

    OpenLongIntMap compactMinCount(int minCount) {
      int kept = 0;
      for (int i = 0; i < keys.length; i++) {
        if (keys[i] != 0L && vals[i] >= minCount) {
          kept++;
        }
      }
      int cap = 16;
      while (cap * 0.68f < kept + 1) {
        cap <<= 1;
      }
      OpenLongIntMap out = new OpenLongIntMap(cap);
      for (int i = 0; i < keys.length; i++) {
        long k = keys[i];
        int v = vals[i];
        if (k != 0L && v >= minCount) {
          out.add(k, v);
        }
      }
      return out;
    }

    int size() {
      return size;
    }

    long estimatedBytes() {
      return (long) keys.length * Long.BYTES + (long) vals.length * Integer.BYTES + 32L;
    }

    void serialize(java.io.DataOutputStream out) throws IOException {
      out.writeInt(keys.length);
      out.writeInt(size);
      for (int i = 0; i < keys.length; i++) {
        if (keys[i] != 0L) {
          out.writeLong(keys[i]);
          out.writeInt(vals[i]);
        }
      }
    }

    static OpenLongIntMap deserialize(java.io.DataInputStream in) throws IOException {
      int cap = in.readInt();
      int count = in.readInt();
      OpenLongIntMap map = new OpenLongIntMap(cap);
      for (int i = 0; i < count; i++) {
        long k = in.readLong();
        int v = in.readInt();
        int slot = slotOf(k) & map.mask;
        while (map.keys[slot] != 0L) {
          slot = (slot + 1) & map.mask;
        }
        map.keys[slot] = k;
        map.vals[slot] = v;
      }
      map.size = count;
      return map;
    }

    private void rehash(int newCap) {
      long[] oldK = keys;
      int[] oldV = vals;
      keys = new long[newCap];
      vals = new int[newCap];
      mask = newCap - 1;
      threshold = (int) (newCap * 0.68f);
      for (int i = 0; i < oldK.length; i++) {
        long k = oldK[i];
        if (k != 0L) {
          int slot = slotOf(k) & mask;
          while (keys[slot] != 0L) {
            slot = (slot + 1) & mask;
          }
          keys[slot] = k;
          vals[slot] = oldV[i];
        }
      }
    }

    private static int slotOf(long k) {
      long z = k * 0x9E3779B97F4A7C15L;
      z ^= (z >>> 32);
      return (int) z;
    }
  }

  /**
   * Lock-free primitive 64-bit hashed Unigram + Bigram + Trigram Turkish language model mined from
   * clean Turkish corpora using Witten-Bell / Jelinek-Mercer normalized probability interpolation
   * (so chain-rule log-probabilities across 1:1, 1:2 splits, and 2:1 merges are strictly comparable).
   */
  public static final class CleanWordLm {
    static final long BOS_HASH = 0x517cc1b727220a95L;
    static final long EOS_HASH = 0x9e3779b97f4a7c15L;

    private final OpenLongIntMap unigrams;
    private final OpenLongIntMap bigrams;
    private final OpenLongIntMap trigrams;
    private final double denomUnigram;

    CleanWordLm(
        OpenLongIntMap unigrams,
        OpenLongIntMap bigrams,
        OpenLongIntMap trigrams,
        long totalTokens) {
      this(
          unigrams,
          bigrams,
          trigrams,
          Math.max(1.0, totalTokens + 0.5 * Math.max(250_000, unigrams.size())));
    }

    private CleanWordLm(
        OpenLongIntMap unigrams,
        OpenLongIntMap bigrams,
        OpenLongIntMap trigrams,
        double denomUnigram) {
      this.unigrams = unigrams;
      this.bigrams = bigrams;
      this.trigrams = trigrams;
      this.denomUnigram = denomUnigram;
    }

    void serialize(java.io.DataOutputStream out) throws IOException {
      out.writeDouble(denomUnigram);
      unigrams.serialize(out);
      bigrams.serialize(out);
      trigrams.serialize(out);
    }

    static CleanWordLm deserialize(java.io.DataInputStream in) throws IOException {
      double denom = in.readDouble();
      OpenLongIntMap u = OpenLongIntMap.deserialize(in);
      OpenLongIntMap b = OpenLongIntMap.deserialize(in);
      OpenLongIntMap t = OpenLongIntMap.deserialize(in);
      return new CleanWordLm(u, b, t, denom);
    }

    CleanWordLm prunedCopy(int minU, int minB, int minT) {
      return new CleanWordLm(
          unigrams.compactMinCount(minU),
          bigrams.compactMinCount(minB),
          trigrams.compactMinCount(minT),
          denomUnigram);
    }

    public int unigramCount(String lowerWord) {
      if (lowerWord == null || lowerWord.isEmpty()) {
        return 0;
      }
      return unigrams.get(wordHash(lowerWord));
    }

    public int bigramCount(String prevLower, String curLower) {
      long h1 = prevLower == null ? BOS_HASH : wordHash(prevLower);
      long h2 = curLower == null ? EOS_HASH : wordHash(curLower);
      return bigrams.get(bigramHash(h1, h2));
    }

    public float unigramLogProb(String lowerWord) {
      long h = lowerWord == null ? EOS_HASH : wordHash(lowerWord);
      return (float) Math.log(unigramProbByHash(h));
    }

    public float bigramLogProb(String prevLower, String curLower) {
      long h1 = prevLower == null ? BOS_HASH : wordHash(prevLower);
      long h2 = curLower == null ? EOS_HASH : wordHash(curLower);
      return (float) Math.log(bigramProbByHash(h1, h2));
    }

    public float trigramLogProb(String prev2Lower, String prev1Lower, String curLower) {
      long h1 = prev1Lower == null ? BOS_HASH : wordHash(prev1Lower);
      long h2 = curLower == null ? EOS_HASH : wordHash(curLower);
      if (prev1Lower == null) {
        return (float) Math.log(bigramProbByHash(BOS_HASH, h2));
      }
      long h0 = prev2Lower == null ? BOS_HASH : wordHash(prev2Lower);
      return (float) Math.log(trigramProbByHash(h0, h1, h2));
    }

    private double unigramProbByHash(long h) {
      int c = unigrams.get(h);
      return (c + 0.5) / denomUnigram;
    }

    private double bigramProbByHash(long hPrev, long hCur) {
      double p1 = unigramProbByHash(hCur);
      int cPrev = unigrams.get(hPrev);
      if (cPrev <= 0) {
        return p1;
      }
      double lambda2 = 0.82 * (cPrev / (cPrev + 8.0));
      int cBi = bigrams.get(bigramHash(hPrev, hCur));
      if (cBi <= 0) {
        return (1.0 - lambda2) * p1;
      }
      double mle2 = Math.min(1.0, cBi / (double) cPrev);
      return lambda2 * mle2 + (1.0 - lambda2) * p1;
    }

    private double trigramProbByHash(long hPrev2, long hPrev1, long hCur) {
      double p2 = bigramProbByHash(hPrev1, hCur);
      int cBiHist = bigrams.get(bigramHash(hPrev2, hPrev1));
      if (cBiHist <= 0) {
        return p2;
      }
      double lambda3 = 0.72 * (cBiHist / (cBiHist + 6.0));
      int cTri = trigrams.get(trigramHash(hPrev2, hPrev1, hCur));
      if (cTri <= 0) {
        return (1.0 - lambda3) * p2;
      }
      double mle3 = Math.min(1.0, cTri / (double) cBiHist);
      return lambda3 * mle3 + (1.0 - lambda3) * p2;
    }

    public int unigramSize() {
      return unigrams.size();
    }

    public int bigramSize() {
      return bigrams.size();
    }

    public int trigramSize() {
      return trigrams.size();
    }

    public long estimatedBytes() {
      return unigrams.estimatedBytes() + bigrams.estimatedBytes() + trigrams.estimatedBytes();
    }

    static long wordHash(String s) {
      long h = 0xcbf29ce484222325L;
      for (int i = 0; i < s.length(); i++) {
        h ^= s.charAt(i);
        h *= 0x100000001b3L;
      }
      h = mix64(h);
      if (h == 0L || h == BOS_HASH || h == EOS_HASH) {
        h ^= 0x27d4eb2f165667c5L;
      }
      return h;
    }

    static long bigramHash(long h1, long h2) {
      long z = mix64(h1 ^ 0x9E3779B97F4A7C15L) ^ mix64(h2 * 0xbf58476d1ce4e5b9L);
      return z == 0L ? 1L : z;
    }

    static long trigramHash(long h0, long h1, long h2) {
      long z = mix64(h0 * 0x94d049bb133111ebL);
      z = mix64(z ^ h1);
      z = mix64(z ^ (h2 * 0xbf58476d1ce4e5b9L));
      return z == 0L ? 1L : z;
    }

    private static long mix64(long z) {
      z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
      z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
      return z ^ (z >>> 31);
    }
  }

  /** Compact smoothed hashed character 4-gram Turkishness scorer (~256 KB). */
  public static final class CharPhonotacticModel {
    private static final int TABLE_BITS = 16;
    private static final int TABLE_SIZE = 1 << TABLE_BITS;
    private static final int MASK = TABLE_SIZE - 1;

    private final int[] ngram4Counts;
    private final int[] prefix3Counts;
    private final float defaultLogProb;

    CharPhonotacticModel(int[] ngram4Counts, int[] prefix3Counts) {
      this.ngram4Counts = ngram4Counts;
      this.prefix3Counts = prefix3Counts;
      this.defaultLogProb = -4.2f;
    }

    public float scoreWord(String lowerWord) {
      if (lowerWord == null || lowerWord.isEmpty()) {
        return defaultLogProb;
      }
      float penalty = 0.0f;
      for (int i = 0; i < lowerWord.length(); i++) {
        char ch = lowerWord.charAt(i);
        if (ch == 'q' || ch == 'w' || ch == 'x') {
          penalty -= 1.35f;
        }
      }
      String padded = "^" + lowerWord + "$";
      if (padded.length() < 4) {
        return -2.4f + penalty;
      }
      float sum = 0.0f;
      int cnt = 0;
      for (int i = 0; i <= padded.length() - 4; i++) {
        int slot4 = hashChars(padded, i, 4) & MASK;
        int slot3 = hashChars(padded, i, 3) & MASK;
        int c4 = ngram4Counts[slot4];
        int c3 = Math.max(c4, prefix3Counts[slot3]);
        sum += c4 > 0 ? (float) Math.log((c4 + 0.5) / (c3 + 29.0)) : defaultLogProb;
        cnt++;
      }
      return (sum / Math.max(1, cnt)) + penalty;
    }

    public long estimatedBytes() {
      return (long) (ngram4Counts.length + prefix3Counts.length) * Integer.BYTES + 32L;
    }

    void serialize(java.io.DataOutputStream out) throws IOException {
      out.writeInt(ngram4Counts.length);
      for (int v : ngram4Counts) {
        out.writeInt(v);
      }
      for (int v : prefix3Counts) {
        out.writeInt(v);
      }
    }

    static CharPhonotacticModel deserialize(java.io.DataInputStream in) throws IOException {
      int len = in.readInt();
      int[] n4 = new int[len];
      int[] p3 = new int[len];
      for (int i = 0; i < len; i++) {
        n4[i] = in.readInt();
      }
      for (int i = 0; i < len; i++) {
        p3[i] = in.readInt();
      }
      return new CharPhonotacticModel(n4, p3);
    }

    static int hashChars(String s, int off, int len) {
      int h = 0x811c9dc5;
      for (int i = 0; i < len; i++) {
        h ^= s.charAt(off + i);
        h *= 0x01000193;
      }
      return h ^ (h >>> 16);
    }
  }

  /**
   * Pre-mined unlabeled clean corpus knowledge (shared across scaling experiments so clean files
   * are scanned only once at startup).
   */
  public static final class CleanCorpusKnowledge {
    public final CleanWordLm cleanLm;
    public final Map<String, CasingCounts> cleanCasing;
    public final Map<String, Integer> cleanOpenBigrams;
    public final Map<String, Integer> cleanClosedWords;
    public final int[] char4Counts;
    public final int[] char3ContextCounts;
    public final int cleanSentenceCount;

    CleanCorpusKnowledge(
        CleanWordLm cleanLm,
        Map<String, CasingCounts> cleanCasing,
        Map<String, Integer> cleanOpenBigrams,
        Map<String, Integer> cleanClosedWords,
        int[] char4Counts,
        int[] char3ContextCounts,
        int cleanSentenceCount) {
      this.cleanLm = cleanLm;
      this.cleanCasing = cleanCasing;
      this.cleanOpenBigrams = cleanOpenBigrams;
      this.cleanClosedWords = cleanClosedWords;
      this.char4Counts = char4Counts;
      this.char3ContextCounts = char3ContextCounts;
      this.cleanSentenceCount = cleanSentenceCount;
    }
  }

  public static final class MinedKnowledge {
    public final Map<String, RewriteEntry> parallel1ToN;
    public final Map<String, MergeEntry> parallel2To1;
    public final Map<String, MergeEntry> parallel3To1;
    public final Map<String, List<SuffixRule>> suffixRulesByLastChar;
    public final Map<String, CasingCounts> casingLexicon;
    public final Map<String, Integer> cleanOpenBigrams;
    public final Map<String, Integer> cleanClosedWords;
    public final CleanWordLm cleanLm;
    public final CharPhonotacticModel charPhonotactics;
    public final int trainedSentenceCount;
    public final int cleanSentenceCount;

    MinedKnowledge(
        Map<String, RewriteEntry> parallel1ToN,
        Map<String, MergeEntry> parallel2To1,
        Map<String, MergeEntry> parallel3To1,
        Map<String, List<SuffixRule>> suffixRulesByLastChar,
        Map<String, CasingCounts> casingLexicon,
        Map<String, Integer> cleanOpenBigrams,
        Map<String, Integer> cleanClosedWords,
        CleanWordLm cleanLm,
        CharPhonotacticModel charPhonotactics,
        int trainedSentenceCount,
        int cleanSentenceCount) {
      this.parallel1ToN = parallel1ToN;
      this.parallel2To1 = parallel2To1;
      this.parallel3To1 = parallel3To1;
      this.suffixRulesByLastChar = suffixRulesByLastChar;
      this.casingLexicon = casingLexicon;
      this.cleanOpenBigrams = cleanOpenBigrams;
      this.cleanClosedWords = cleanClosedWords;
      this.cleanLm = cleanLm;
      this.charPhonotactics = charPhonotactics;
      this.trainedSentenceCount = trainedSentenceCount;
      this.cleanSentenceCount = cleanSentenceCount;
    }

    MinedKnowledge withPrunedCleanLm(int minU, int minB, int minT) {
      return new MinedKnowledge(
          parallel1ToN,
          parallel2To1,
          parallel3To1,
          suffixRulesByLastChar,
          casingLexicon,
          cleanOpenBigrams,
          cleanClosedWords,
          cleanLm.prunedCopy(minU, minB, minT),
          charPhonotactics,
          trainedSentenceCount,
          cleanSentenceCount);
    }

    /**
     * Restores natural Turkish casing (and stem capitalization before apostrophes) for a candidate
     * lowercase string {@code normLower}.
     */
    public String truecase(
        String normLower,
        CasingShape rawShape,
        String rawSurface,
        boolean capitalizeStart,
        boolean sentenceAllCaps,
        TurkishMorphology morphology) {
      return truecase(
          normLower, rawShape, rawSurface, capitalizeStart, sentenceAllCaps, morphology, 0);
    }

    /**
     * Context-aware deterministic Turkish truecaser (<b>B2 Layer 1</b>: emits a single canonical
     * surface string per candidate without duplicating lattice arcs).
     */
    public String truecase(
        String normLower,
        CasingShape rawShape,
        String rawSurface,
        boolean capitalizeStart,
        boolean sentenceAllCaps,
        TurkishMorphology morphology,
        int casingCtx) {
      if (normLower == null || normLower.isEmpty()) {
        return "";
      }
      int sp = normLower.indexOf(' ');
      if (sp > 0) {
        String[] parts = normLower.split(" ");
        int noisyFlag = casingCtx & CTX_SENT_TITLE_NOISY;
        StringBuilder sb = new StringBuilder(normLower.length() + 2);
        for (int i = 0; i < parts.length; i++) {
          if (i > 0) {
            sb.append(' ');
          }
          int partCtx =
              i == 0
                  ? (casingCtx & (CTX_SENT_TITLE_NOISY | CTX_ADJ_DATE_NUM | CTX_AFTER_NUMBER))
                  : (i == parts.length - 1
                      ? (noisyFlag | (casingCtx & CTX_BEFORE_GAZETTEER_OR_HONORIFIC))
                      : noisyFlag);
          if (parts.length == 2
              && i == 0
              && isTdkHolidayModifier(parts[0])
              && parts[1].startsWith("günü")) {
            partCtx |= CTX_PROMOTE_PROPER_IN_CONTEXT;
          } else if (parts.length == 2
              && i == 1
              && isTdkHolidayModifier(parts[0])
              && parts[1].startsWith("günü")) {
            partCtx |= CTX_PROMOTE_PROPER_IN_CONTEXT;
          }
          sb.append(
              truecaseSingleWord(
                  parts[i],
                  i == 0 ? rawShape : CasingShape.LOWER,
                  i == 0 ? rawSurface : parts[i],
                  i == 0 && capitalizeStart,
                  sentenceAllCaps,
                  morphology,
                  partCtx));
        }
        return sb.toString();
      }
      return truecaseSingleWord(
          normLower,
          rawShape,
          rawSurface,
          capitalizeStart,
          sentenceAllCaps,
          morphology,
          casingCtx);
    }

    private String truecaseSingleWord(
        String wordLower,
        CasingShape rawShape,
        String rawSurface,
        boolean capitalizeStart,
        boolean sentenceAllCaps,
        TurkishMorphology morphology,
        int casingCtx) {
      boolean noisyTitleSent = (casingCtx & CTX_SENT_TITLE_NOISY) != 0;

      // 1. Canonical TDK dotted abbreviations ("DR."->"Dr.", "LTD."->"Ltd.", "VB."->"vb.") & personal initials
      String dotted = CANONICAL_DOTTED_ABBR.get(wordLower);
      if (dotted != null) {
        return capitalizeStart && Character.isLowerCase(dotted.charAt(0))
            ? NormV2Types.trCapitalize(dotted)
            : dotted;
      }
      if (wordLower.length() == 2
          && wordLower.charAt(1) == '.'
          && Character.isLetter(wordLower.charAt(0))) {
        if ((casingCtx & CTX_PROMOTE_PROPER_IN_CONTEXT) != 0
            || capitalizeStart
            || (!sentenceAllCaps && rawShape == CasingShape.TITLE)) {
          return NormV2Types.trUpper(wordLower);
        }
        return wordLower;
      }

      // 2. Canonical tech tokens ("2k"->"2K", "4k"->"4K", "i7"->"i7", "ghz"->"GHz", "seo"->"SEO", "php"->"PHP")
      String canonTech = CANONICAL_TECH_TOKENS.get(wordLower);
      if (canonTech != null) {
        return canonTech;
      }
      if (wordLower.equals("mah")) {
        if ((casingCtx & CTX_AFTER_NUMBER) != 0) {
          return "mAh";
        }
        if (!sentenceAllCaps
            && !noisyTitleSent
            && (rawShape == CasingShape.TITLE
                || (casingCtx & CTX_BEFORE_GAZETTEER_OR_HONORIFIC) != 0)) {
          return "Mah";
        }
        return capitalizeStart ? "Mah" : "mah";
      }

      // 3. Alphanumeric hardware/spec shapes ("4070Ti", "5600X", "13700HX", "550W", "3000-2450M", "10K'ya", "300Mbps")
      if (containsDigit(wordLower)) {
        String canonNumUnit = formatNumericTechSpecOrNull(wordLower);
        if (canonNumUnit != null) {
          return canonNumUnit;
        }
        if (!sentenceAllCaps && rawSurface != null) {
          String rawNormAp = rawSurface.replace('’', '\'');
          if (hasAnyUpperLetter(rawNormAp)
              && NormV2Types.trLower(rawNormAp).equals(wordLower)) {
            return rawNormAp;
          }
          int apIdx = wordLower.indexOf('\'');
          int rawApIdx = rawNormAp.indexOf('\'');
          if (apIdx > 0
              && rawApIdx == apIdx
              && hasAnyUpperLetter(rawNormAp.substring(0, rawApIdx))
              && NormV2Types.trLower(rawNormAp.substring(0, rawApIdx))
                  .equals(wordLower.substring(0, apIdx))) {
            return rawNormAp.substring(0, rawApIdx) + wordLower.substring(apIdx);
          }
        }
      }

      // 4. Apostrophized stems ("Türkiye'de", "TR'de", "LLM'lerdeki", "SEO'ya", "WMAracı'nı" vs slang "entry'mde")
      int ap = wordLower.indexOf('\'');
      if (ap > 0) {
        String stemLower = wordLower.substring(0, ap);
        String tailLower = wordLower.substring(ap);
        if (isDigitsOrPunctOnly(stemLower)) {
          return stemLower + tailLower;
        }
        if (CANONICAL_UPPER_ACRONYM_STEMS.contains(stemLower)) {
          return NormV2Types.trUpper(stemLower) + tailLower;
        }
        if (!sentenceAllCaps && rawSurface != null) {
          String rawNormAp = rawSurface.replace('’', '\'');
          int rawAp = rawNormAp.indexOf('\'');
          String rawStem = rawAp > 0 ? rawNormAp.substring(0, rawAp) : rawNormAp;
          CasingShape stemShape = NormV2Types.detectShape(rawStem);
          if (stemShape == CasingShape.UPPER
              && stemLower.length() >= 2
              && stemLower.length() <= 6) {
            return NormV2Types.trUpper(stemLower) + tailLower;
          }
          if (stemShape == CasingShape.MIXED
              && !noisyTitleSent
              && NormV2Types.trLower(rawStem).equals(stemLower)
              && !analyzeLexiconRole(stemLower, morphology).hasCommon()) {
            return rawStem + tailLower;
          }
        }
        CasingCounts stemCounts = casingLexicon.get(stemLower);
        if (stemCounts != null
            && stemCounts.bestMixedSurface != null
            && stemCounts.mixed >= 4
            && stemCounts.mixed > (stemCounts.lower + stemCounts.title)) {
          return stemCounts.bestMixedSurface + tailLower;
        }
        if (stemCounts != null
            && stemCounts.upper >= 5
            && stemCounts.upper > (stemCounts.lower + stemCounts.title) * 2
            && stemLower.length() >= 2
            && stemLower.length() <= 6) {
          return NormV2Types.trUpper(stemLower) + tailLower;
        }
        if (!sentenceAllCaps
            && rawShape == CasingShape.UPPER
            && stemLower.length() >= 2
            && stemLower.length() <= 6) {
          return NormV2Types.trUpper(stemLower) + tailLower;
        }
        if (!capitalizeStart && rawShape == CasingShape.LOWER) {
          if (stemCounts != null
              && stemCounts.lower >= 15
              && stemCounts.lower
                  > (stemCounts.title + stemCounts.upper + stemCounts.apostropheStem) * 4) {
            return stemLower + tailLower;
          }
          boolean rawHadApostrophe =
              rawSurface != null
                  && (rawSurface.indexOf('\'') >= 0 || rawSurface.indexOf('’') >= 0);
          if (rawHadApostrophe && LOWERCASE_APOSTROPHE_SLANG_STEMS.contains(stemLower)) {
            return stemLower + tailLower;
          }
        }
        return NormV2Types.trCapitalize(stemLower) + tailLower;
      }

      // 5. Foreign >5-char ALL-CAPS ASCII 'I' names/brands ("DAVID"->"David", "AIBA"->"AIBA")
      if (!sentenceAllCaps
          && rawShape == CasingShape.UPPER
          && rawSurface != null
          && rawSurface.indexOf('I') >= 0
          && !NormV2Types.hasUpperTurkishDiacritics(rawSurface)
          && wordLower.indexOf('ı') >= 0) {
        CasingCounts curCc = casingLexicon.get(wordLower);
        if ((curCc == null || curCc.total() < 3)
            && !analyzeLexiconRole(wordLower, morphology).hasAny()) {
          String iFolded = wordLower.replace('ı', 'i');
          CasingCounts altCc = casingLexicon.get(iFolded);
          if (altCc != null && altCc.total() >= 4) {
            wordLower = iFolded;
          }
        }
      }

      // 6. Contextual TDK promotion ("Sevgililer Günü", "Lale Hanım", "Ümit Özdağ", "Mimar Sinan")
      if (!sentenceAllCaps && (casingCtx & CTX_PROMOTE_PROPER_IN_CONTEXT) != 0) {
        return NormV2Types.trCapitalize(wordLower);
      }

      // 7. TDK Month & Day rule: lowercase when raw is lowercase without adjacent date/gazetteer context
      if (TDK_MONTHS_AND_DAYS.contains(wordLower)) {
        boolean dateOrGazCtx =
            (casingCtx & (CTX_ADJ_DATE_NUM | CTX_BEFORE_GAZETTEER_OR_HONORIFIC)) != 0;
        boolean explicitAuthorTitle =
            !sentenceAllCaps && !noisyTitleSent && rawShape == CasingShape.TITLE;
        if (capitalizeStart || dateOrGazCtx || explicitAuthorTitle) {
          return NormV2Types.trCapitalize(wordLower);
        }
        return wordLower;
      }

      // 8. Clean-corpus casing lexicon (guarded against headline Title-Case bias on common & English words)
      CasingCounts cc = casingLexicon.get(wordLower);
      if (cc != null) {
        int tot = cc.total();
        if (cc.bestMixedSurface != null
            && cc.mixed >= 4
            && cc.mixed > (cc.lower + cc.title + cc.upper)) {
          return cc.bestMixedSurface;
        }
        if (cc.upper >= 8
            && cc.upper > (cc.lower + cc.title) * 2
            && wordLower.length() >= 2
            && wordLower.length() <= 6) {
          return NormV2Types.trUpper(wordLower);
        }
        if (tot >= 6 && cc.title > (cc.lower * 3) && cc.title >= 5) {
          if (!capitalizeStart && rawShape == CasingShape.LOWER) {
            if (!LOWERCASE_HEADLINE_BIAS_STOPWORDS.contains(wordLower)) {
              return NormV2Types.trCapitalize(wordLower);
            }
          } else {
            return NormV2Types.trCapitalize(wordLower);
          }
        }
      }

      // 9. Check Zemberek lexicon when unseen/rare in corpus casing table
      if ((cc == null || (cc.total() < 5 && cc.lower < 2)) && wordLower.length() >= 2) {
        LexiconRole role = analyzeLexiconRole(wordLower, morphology);
        if (!role.hasCommon() && role.onlyBareAbbr() && wordLower.length() <= 5) {
          return NormV2Types.trUpper(wordLower);
        }
        if (!role.hasCommon() && role.hasProperNoApostrophe()) {
          return NormV2Types.trCapitalize(wordLower);
        }
      }

      // 10. Preserve explicit author CamelCase / hyphenated compounds ("IPS-Level", "AtanamayanTarım", "YouTuberların")
      if (!sentenceAllCaps
          && !noisyTitleSent
          && rawShape == CasingShape.MIXED
          && rawSurface != null) {
        String rawNorm = rawSurface.replace('’', '\'');
        if (wordLower.indexOf('-') > 0 && NormV2Types.trLower(rawNorm).equals(wordLower)) {
          return rawNorm;
        }
        if ((cc == null || cc.lower < 5)
            && !isShiftSlipPrefix(rawNorm)
            && !analyzeLexiconRole(wordLower, morphology).hasCommon()) {
          if (NormV2Types.trLower(rawNorm).equals(wordLower)) {
            return rawNorm;
          }
          String rawLow = NormV2Types.trLower(rawNorm);
          if (rawLow.length() >= 4
              && wordLower.length() > rawLow.length()
              && wordLower.startsWith(rawLow)) {
            return rawNorm + wordLower.substring(rawNorm.length());
          }
        }
      }

      // 11. Preserve explicit mid-sentence TitleCase on OOV names/brands & Gazetteer modifiers (suppressed in noisy Every-Word-Title sentences!)
      if (!sentenceAllCaps && rawShape == CasingShape.TITLE && !capitalizeStart) {
        if (!noisyTitleSent) {
          if ((casingCtx & CTX_BEFORE_GAZETTEER_OR_HONORIFIC) != 0
              || cc == null
              || cc.lower < 25
              || cc.title * 5 >= cc.lower) {
            return NormV2Types.trCapitalize(wordLower);
          }
        } else if ((cc == null || cc.lower < 3)
            && !analyzeLexiconRole(wordLower, morphology).hasCommon()) {
          return NormV2Types.trCapitalize(wordLower);
        }
      }

      // 12. Preserve explicit mid-sentence UPPER acronyms & tech brands ("ATT", "RYZEN", "HÜDA", "AIBA")
      if (!sentenceAllCaps && rawShape == CasingShape.UPPER && wordLower.length() >= 2) {
        if (wordLower.length() <= 5 && (cc == null || cc.lower < 15)) {
          return NormV2Types.trUpper(wordLower);
        }
        if (wordLower.length() <= 8
            && cc != null
            && cc.upper >= 4
            && cc.upper > (cc.lower + cc.title) * 2) {
          return NormV2Types.trUpper(wordLower);
        }
      }

      if (capitalizeStart) {
        return NormV2Types.trCapitalize(wordLower);
      }
      return wordLower;
    }

    private record LexiconRole(
        boolean hasCommon, boolean onlyBareAbbr, boolean hasProperNoApostrophe, boolean hasAnyProper) {
      boolean hasProperOrAbbr() {
        return onlyBareAbbr || hasProperNoApostrophe || hasAnyProper;
      }

      boolean hasAny() {
        return hasCommon || hasProperOrAbbr();
      }
    }

    private static LexiconRole analyzeLexiconRole(String wordLower, TurkishMorphology morphology) {
      if (morphology == null || wordLower.length() < 2) {
        return new LexiconRole(false, false, false, false);
      }
      boolean onlyBareAbbr = false;
      boolean properNoAp = false;
      boolean anyProper = false;
      boolean hasCommon = false;
      for (SingleAnalysis a : morphology.analyze(wordLower)) {
        if (a.isUnknown() || a.isRuntime()) {
          continue;
        }
        SecondaryPos sp = a.getDictionaryItem().secondaryPos;
        if (sp == SecondaryPos.Abbreviation) {
          anyProper = true;
          if (a.getEnding().isEmpty()) {
            onlyBareAbbr = true;
          }
        } else if (sp == SecondaryPos.ProperNoun) {
          anyProper = true;
          if (a.getEnding().isEmpty()) {
            properNoAp = true;
          } else if (!SpanCandidateGenerator.properNounTakesApostropheTDK(a)
              && !a.getMorphemes().contains(TurkishMorphotactics.a3pl)) {
            properNoAp = true;
          }
        } else {
          hasCommon = true;
        }
      }
      return new LexiconRole(hasCommon, onlyBareAbbr, properNoAp, anyProper);
    }

    private static boolean isTdkHolidayModifier(String w) {
      return w.equals("sevgililer")
          || w.equals("anneler")
          || w.equals("babalar")
          || w.equals("öğretmenler")
          || w.equals("kadınlar");
    }

    private static boolean hasAnyUpperLetter(String s) {
      for (int i = 0; i < s.length(); i++) {
        if (Character.isLetter(s.charAt(i)) && Character.isUpperCase(s.charAt(i))) {
          return true;
        }
      }
      return false;
    }

    /** Detects fast-Shift-release slips like {@code "GEldi"}, {@code "BEn"}, {@code "YApıyorum"}. */
    private static boolean isShiftSlipPrefix(String s) {
      if (s.length() < 3
          || !Character.isUpperCase(s.charAt(0))
          || !Character.isUpperCase(s.charAt(1))) {
        return false;
      }
      for (int i = 2; i < s.length(); i++) {
        char c = s.charAt(i);
        if (Character.isLetter(c) && Character.isUpperCase(c)) {
          return false;
        }
      }
      return true;
    }

    private static String formatNumericTechSpecOrNull(String wordLower) {
      int n = wordLower.length();
      int dEnd = 0;
      while (dEnd < n && Character.isDigit(wordLower.charAt(dEnd))) {
        dEnd++;
      }
      if (dEnd == 0 || dEnd >= n) {
        return null;
      }
      String digits = wordLower.substring(0, dEnd);
      String unit = wordLower.substring(dEnd);
      return switch (unit) {
        case "ghz" -> digits + "GHz";
        case "mhz" -> digits + "MHz";
        case "khz" -> digits + "kHz";
        case "hz" -> digits + "Hz";
        case "mbps" -> digits + "Mbps";
        case "gbps" -> digits + "Gbps";
        case "kbps" -> digits + "Kbps";
        case "mah" -> digits + "mAh";
        default -> null;
      };
    }

    public boolean isKnownProperOrAcronymStem(String stemLower) {
      CasingCounts cc = casingLexicon.get(stemLower);
      if (cc == null || stemLower.length() < 2) {
        return false;
      }
      int propVotes = cc.title + cc.upper + cc.mixed;
      if (cc.apostropheStem >= 2 && (propVotes + cc.apostropheStem * 2) > cc.lower * 2) {
        return true;
      }
      return propVotes >= 4 && propVotes > cc.lower * 4;
    }

    public boolean isKnownAcronymStem(String stemLower) {
      CasingCounts cc = casingLexicon.get(stemLower);
      if (cc == null || stemLower.length() < 2 || stemLower.length() > 6) {
        return false;
      }
      return cc.upper >= 6 && cc.upper > (cc.lower + cc.title) * 2;
    }

    public boolean isKnownBrandMixedCase(String wordLower) {
      CasingCounts cc = casingLexicon.get(wordLower);
      return cc != null
          && cc.bestMixedSurface != null
          && cc.mixed >= 3
          && cc.mixed > (cc.lower + cc.title);
    }

    /**
     * Adjusts every candidate arc's {@code parallelProb}, {@code parallelLogCount}, {@code
     * rawParallelTotal}, and {@code sourceMask} in-place using Leave-One-Sentence-Out (jackknife)
     * counts BEFORE oracle projection, removing purely memorized singleton arcs ({@code sourceMask
     * == 0}) so ML training matches unseen test sentences bit-for-bit!
     */
    public void applyLeaveOneSentenceOut(
        NormV2Types.SentenceLattice lat, AlignedSentence sent) {
      List<NormV2Types.SpanToken> toks = lat.tokens();
      int n = toks.size();
      if (n == 0) {
        return;
      }
      String[] goldLowByStart = new String[n];
      int[] goldEndByStart = new int[n];
      List<AlignedToken> gToks = sent.tokens();
      if (gToks.size() == n) {
        int idx = 0;
        while (idx < n) {
          String gn = gToks.get(idx).norm() == null ? "" : gToks.get(idx).norm().trim();
          if (gn.isEmpty()) {
            idx++;
            continue;
          }
          int j = idx + 1;
          while (j < n && (gToks.get(j).norm() == null || gToks.get(j).norm().trim().isEmpty())) {
            j++;
          }
          goldLowByStart[idx] = NormV2Types.trLower(gn);
          goldEndByStart[idx] = j;
          idx = j;
        }
      } else {
        for (int i = 0; i < n; i++) {
          goldLowByStart[i] = toks.get(i).lower();
          goldEndByStart[i] = i + 1;
        }
      }

      for (int i = 0; i < n; i++) {
        String goldLow = goldLowByStart[i];
        int goldEnd = goldEndByStart[i];

        String rawTokLow = toks.get(i).lower();
        RewriteEntry re = parallel1ToN.get(rawTokLow);
        boolean validSingleGold =
            goldLow != null
                && goldEnd == i + 1
                && !isCorruptParallelPair(rawTokLow, goldLow);
        int looTot1 =
            re != null ? Math.max(0, re.totalCount() - (validSingleGold ? 1 : 0)) : 0;

        java.util.Iterator<NormV2Types.SpanArc> it = lat.outgoing()[i].iterator();
        while (it.hasNext()) {
          NormV2Types.SpanArc arc = it.next();
          int spanLen = arc.endIdx - arc.startIdx;
          boolean matchedThisGold =
              goldLow != null && arc.endIdx == goldEnd && arc.normLower.equals(goldLow);
          if (spanLen == 1) {
            arc.rawParallelTotal = looTot1;
            if (arc.normLower.equals(rawTokLow)) {
              int looKeep =
                  re == null ? 0 : Math.max(0, re.keepCount() - (matchedThisGold ? 1 : 0));
              arc.parallelProb = looTot1 > 0 ? (looKeep + 1.5f) / (looTot1 + 3.0f) : 0.0f;
              arc.parallelLogCount = (float) Math.log1p(looKeep);
              if (looKeep == 0) {
                arc.sourceMask &= ~NormV2Types.SRC_PARALLEL_1TO1;
                if (arc.sourceMask == 0) {
                  it.remove();
                }
              }
            } else {
              int looC =
                  re == null
                      ? 0
                      : Math.max(0, re.countOf(arc.normLower) - (matchedThisGold ? 1 : 0));
              arc.parallelProb = (looTot1 > 0 && looC > 0) ? (looC / (looTot1 + 3.0f)) : 0.0f;
              arc.parallelLogCount = (float) Math.log1p(looC);
              if (looC == 0) {
                arc.sourceMask &=
                    ~(NormV2Types.SRC_PARALLEL_1TO1 | NormV2Types.SRC_PARALLEL_SPLIT);
                if (arc.sourceMask == 0) {
                  it.remove();
                }
              }
            }
          } else if (spanLen == 2 && i + 1 < n) {
            MergeEntry me2 =
                parallel2To1.get(toks.get(i).lower() + "\t" + toks.get(i + 1).lower());
            if (me2 != null) {
              int looCo = Math.max(0, Math.max(me2.cooccurCount(), me2.totalMergedCount()) - 1);
              int looC = Math.max(0, me2.countOf(arc.normLower) - (matchedThisGold ? 1 : 0));
              arc.rawParallelTotal = looCo;
              arc.parallelProb = (looCo > 0 && looC > 0) ? (looC / (looCo + 3.0f)) : 0.0f;
              arc.parallelLogCount = (float) Math.log1p(looC);
              if (looC == 0) {
                arc.sourceMask &= ~NormV2Types.SRC_PARALLEL_MERGE;
                if (arc.sourceMask == 0) {
                  it.remove();
                }
              }
            }
          } else if (spanLen == 3 && i + 2 < n) {
            MergeEntry me3 =
                parallel3To1.get(
                    toks.get(i).lower()
                        + "\t"
                        + toks.get(i + 1).lower()
                        + "\t"
                        + toks.get(i + 2).lower());
            if (me3 != null) {
              int looCo = Math.max(0, Math.max(me3.cooccurCount(), me3.totalMergedCount()) - 1);
              int looC = Math.max(0, me3.countOf(arc.normLower) - (matchedThisGold ? 1 : 0));
              arc.rawParallelTotal = looCo;
              arc.parallelProb = (looCo > 0 && looC > 0) ? (looC / (looCo + 3.0f)) : 0.0f;
              arc.parallelLogCount = (float) Math.log1p(looC);
              if (looC == 0) {
                arc.sourceMask &= ~NormV2Types.SRC_PARALLEL_MERGE;
                if (arc.sourceMask == 0) {
                  it.remove();
                }
              }
            }
          }
        }
      }
    }

    public long estimatedBytes() {
      return parallel1ToN.size() * 96L
          + parallel2To1.size() * 96L
          + parallel3To1.size() * 96L
          + casingLexicon.size() * 56L
          + cleanOpenBigrams.size() * 48L
          + cleanClosedWords.size() * 40L
          + cleanLm.estimatedBytes()
          + charPhonotactics.estimatedBytes();
    }
  }

  private static boolean isDigitsOrPunctOnly(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (Character.isLetter(s.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static boolean containsDigit(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (Character.isDigit(s.charAt(i))) {
        return true;
      }
    }
    return false;
  }

  private static int countLetters(String s) {
    int cnt = 0;
    for (int i = 0; i < s.length(); i++) {
      if (Character.isLetter(s.charAt(i))) {
        cnt++;
      }
    }
    return cnt;
  }

  private static boolean isAllDigits(String s) {
    if (s == null || s.isEmpty()) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      if (!Character.isDigit(s.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Filters LLM slot-alignment boundary leaks where pure numbers are spelled out/dropped (e.g.
   * {@code "2" -> "iki"}) or attached unit/word letters on {@code <digits><unit>} tokens are
   * stripped into an adjacent slot ({@code "300tl" -> "300"}, {@code "100gb" -> "100"}), while
   * keeping leetspeak ({@code "b1r" -> "bir"}) intact.
   */
  static boolean isCorruptParallelPair(String rawLow, String normLow) {
    if (rawLow == null || normLow == null || rawLow.equals(normLow)) {
      return false;
    }
    if (isAllDigits(rawLow) && !containsDigit(normLow)) {
      return true;
    }
    if (!containsDigit(rawLow) && containsDigit(normLow)) {
      return true;
    }
    if (containsDigit(rawLow) && countLetters(rawLow) >= 2 && countLetters(normLow) == 0) {
      return true;
    }
    return false;
  }

  private static final class MutableRewrite {
    int totalCount;
    int keepCount;
    final Map<String, Integer> targetLowerCounts = new HashMap<>(4);
    final Map<String, Map<String, Integer>> surfaceVotes = new HashMap<>(4);

    void add(String rawLower, String normSurface, String normLower) {
      totalCount++;
      if (rawLower.equals(normLower)) {
        keepCount++;
      }
      targetLowerCounts.merge(normLower, 1, Integer::sum);
      surfaceVotes
          .computeIfAbsent(normLower, k -> new HashMap<>(2))
          .merge(normSurface, 1, Integer::sum);
    }

    RewriteEntry freeze(int maxTargets) {
      List<Map.Entry<String, Integer>> list = new ArrayList<>(targetLowerCounts.entrySet());
      list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
      int k = Math.min(maxTargets, list.size());
      TargetCount[] arr = new TargetCount[k];
      for (int i = 0; i < k; i++) {
        String nl = list.get(i).getKey();
        int cnt = list.get(i).getValue();
        String bestSurf = bestVotedSurface(surfaceVotes.get(nl), nl);
        arr[i] = new TargetCount(nl, bestSurf, cnt);
      }
      return new RewriteEntry(totalCount, keepCount, arr);
    }
  }

  private static final class MutableMerge {
    int cooccurCount;
    final Map<String, Integer> mergedLowerCounts = new HashMap<>(2);
    final Map<String, Map<String, Integer>> surfaceVotes = new HashMap<>(2);

    void addMerged(String normSurface, String normLower) {
      mergedLowerCounts.merge(normLower, 1, Integer::sum);
      surfaceVotes
          .computeIfAbsent(normLower, k -> new HashMap<>(2))
          .merge(normSurface, 1, Integer::sum);
    }

    MergeEntry freeze(int maxTargets) {
      List<Map.Entry<String, Integer>> list = new ArrayList<>(mergedLowerCounts.entrySet());
      list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
      int k = Math.min(maxTargets, list.size());
      TargetCount[] arr = new TargetCount[k];
      for (int i = 0; i < k; i++) {
        String nl = list.get(i).getKey();
        int cnt = list.get(i).getValue();
        String bestSurf = bestVotedSurface(surfaceVotes.get(nl), nl);
        arr[i] = new TargetCount(nl, bestSurf, cnt);
      }
      return new MergeEntry(cooccurCount, arr);
    }
  }

  private static String bestVotedSurface(Map<String, Integer> votes, String fallback) {
    if (votes == null || votes.isEmpty()) {
      return fallback;
    }
    String best = fallback;
    int bestCnt = -1;
    for (Map.Entry<String, Integer> e : votes.entrySet()) {
      if (e.getValue() > bestCnt) {
        bestCnt = e.getValue();
        best = e.getKey();
      }
    }
    return best;
  }

  public static String sentenceFingerprint(String s) {
    if (s == null || s.isEmpty()) {
      return "";
    }
    String low = NormV2Types.trLower(s);
    StringBuilder sb = new StringBuilder(low.length());
    for (int i = 0; i < low.length(); i++) {
      char c = low.charAt(i);
      if (Character.isLetterOrDigit(c)) {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  private static final class FileCleanShard {
    final List<long[]> hashedSentences = new ArrayList<>(65_536);
    final Map<String, CasingCounts> casing = new HashMap<>(32_768);
    final Map<String, Integer> openBigrams = new HashMap<>(16_384);
    final Map<String, Integer> closedWords = new HashMap<>(32_768);
    final int[] char4 = new int[1 << 16];
    final int[] char3 = new int[1 << 16];
    int sentsUsed;
    long tokenCount;
  }

  /**
   * Scans unlabeled clean Turkish files in parallel (filtering headlines, ALL-CAPS, and all
   * evaluation fingerprints) and constructs {@link CleanCorpusKnowledge} including a 3-gram {@link
   * CleanWordLm}.
   */
  public static CleanCorpusKnowledge mineCleanCorpus(
      List<Path> cleanCorpusFiles,
      int maxCleanSentencesPerFile,
      Set<String> forbiddenEvalFingerprints)
      throws IOException {
    int[] quotas = new int[cleanCorpusFiles.size()];
    boolean[] recCasing = new boolean[cleanCorpusFiles.size()];
    Arrays.fill(quotas, maxCleanSentencesPerFile);
    Arrays.fill(recCasing, true);
    return mineCleanCorpus(cleanCorpusFiles, quotas, recCasing, forbiddenEvalFingerprints);
  }

  public static CleanCorpusKnowledge mineCleanCorpus(
      List<Path> cleanCorpusFiles,
      int[] maxPerFile,
      boolean[] recordCasingPerFile,
      Set<String> forbiddenEvalFingerprints)
      throws IOException {

    List<FileCleanShard> shards =
        java.util.stream.IntStream.range(0, cleanCorpusFiles.size())
            .parallel()
            .mapToObj(
                fIdx -> {
                  Path path = cleanCorpusFiles.get(fIdx);
                  int fileCap = maxPerFile[fIdx];
                  boolean doCasing = recordCasingPerFile[fIdx];
                  FileCleanShard shard = new FileCleanShard();
                  if (!Files.exists(path) || fileCap <= 0) {
                    return shard;
                  }
                  List<String> lineWords = new ArrayList<>(32);
                  try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = br.readLine()) != null && shard.sentsUsed < fileCap) {
                      line = line.trim();
                      if (line.length() < 12 || line.length() > 420) {
                        continue;
                      }
                      if (forbiddenEvalFingerprints != null
                          && !forbiddenEvalFingerprints.isEmpty()
                          && forbiddenEvalFingerprints.contains(sentenceFingerprint(line))) {
                        continue;
                      }
                      extractWordsFast(line, lineWords);
                      int nw = lineWords.size();
                      if (nw < 3 || nw > 64) {
                        continue;
                      }
                      if (isHeadlineOrAllCapsLine(lineWords)) {
                        continue;
                      }
                      shard.sentsUsed++;
                      shard.tokenCount += nw + 1; // + EOS

                      long[] sentHashes = new long[nw];
                      String prevCleanLow = null;
                      for (int wIdx = 0; wIdx < nw; wIdx++) {
                        String w = lineWords.get(wIdx);
                        String wLow = NormV2Types.trLower(w);
                        sentHashes[wIdx] = CleanWordLm.wordHash(wLow);
                        if (doCasing) {
                          recordSingleWordCasingAndChars(
                              w,
                              wLow,
                              wIdx == 0,
                              shard.casing,
                              wIdx <= 10,
                              shard.char4,
                              shard.char3);
                        }
                        if (wLow.length() >= 3 && wLow.indexOf('\'') < 0) {
                          shard.closedWords.merge(wLow, 1, Integer::sum);
                        }
                        if (prevCleanLow != null
                            && prevCleanLow.length() >= 2
                            && wLow.length() >= 2
                            && prevCleanLow.indexOf('\'') < 0
                            && wLow.indexOf('\'') < 0
                            && isOpenCompoundCandidate(prevCleanLow, wLow)) {
                          shard.openBigrams.merge(prevCleanLow + " " + wLow, 1, Integer::sum);
                        }
                        prevCleanLow = wLow;
                      }
                      shard.hashedSentences.add(sentHashes);
                    }
                  } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                  }
                  return shard;
                })
            .toList();

    Map<String, CasingCounts> mergedCasing = new HashMap<>(262_144);
    Map<String, Integer> mergedOpenBigrams = new HashMap<>(131_072);
    Map<String, Integer> mergedClosedWords = new HashMap<>(262_144);
    int[] char4Counts = new int[1 << 16];
    int[] char3Counts = new int[1 << 16];
    int totalSents = 0;
    long totalTokens = 0L;

    OpenLongIntMap rawUnigrams = new OpenLongIntMap(1 << 20);

    for (FileCleanShard sh : shards) {
      totalSents += sh.sentsUsed;
      totalTokens += sh.tokenCount;
      for (int i = 0; i < char4Counts.length; i++) {
        char4Counts[i] += sh.char4[i];
        char3Counts[i] += sh.char3[i];
      }
      for (Map.Entry<String, Integer> e : sh.openBigrams.entrySet()) {
        mergedOpenBigrams.merge(e.getKey(), e.getValue(), Integer::sum);
      }
      for (Map.Entry<String, Integer> e : sh.closedWords.entrySet()) {
        mergedClosedWords.merge(e.getKey(), e.getValue(), Integer::sum);
      }
      for (Map.Entry<String, CasingCounts> e : sh.casing.entrySet()) {
        CasingCounts src = e.getValue();
        CasingCounts dst = mergedCasing.computeIfAbsent(e.getKey(), k -> new CasingCounts());
        dst.lower += src.lower;
        dst.title += src.title;
        dst.upper += src.upper;
        dst.mixed += src.mixed;
        dst.apostropheStem += src.apostropheStem;
        if (src.bestMixedSurface != null) {
          dst.recordPlausibleMixed(src.bestMixedSurface, src.bestMixedCount);
        }
      }
      if (sh.sentsUsed > 0) {
        rawUnigrams.add(CleanWordLm.BOS_HASH, sh.sentsUsed);
        rawUnigrams.add(CleanWordLm.EOS_HASH, sh.sentsUsed);
      }
      for (long[] sHashes : sh.hashedSentences) {
        for (long wh : sHashes) {
          rawUnigrams.add(wh, 1);
        }
      }
    }

    OpenLongIntMap unigrams = rawUnigrams.compactMinCount(2);
    rawUnigrams = null;

    // Pass 2 (Apriori): Count bigrams whose endpoint words appear >= 2 times
    OpenLongIntMap rawBigrams = new OpenLongIntMap(1 << 22);
    for (FileCleanShard sh : shards) {
      for (long[] sHashes : sh.hashedSentences) {
        long prev = CleanWordLm.BOS_HASH;
        boolean prevOk = true;
        for (long cur : sHashes) {
          boolean curOk = unigrams.get(cur) >= 2;
          if (prevOk && curOk) {
            rawBigrams.add(CleanWordLm.bigramHash(prev, cur), 1);
          }
          prev = cur;
          prevOk = curOk;
        }
        if (prevOk) {
          rawBigrams.add(CleanWordLm.bigramHash(prev, CleanWordLm.EOS_HASH), 1);
        }
      }
    }
    OpenLongIntMap bigrams = rawBigrams.compactMinCount(2);
    rawBigrams = null;

    // Pass 3 (Apriori): Count trigrams whose constituent bigrams both appear >= 2 times
    OpenLongIntMap rawTrigrams = new OpenLongIntMap(1 << 22);
    for (FileCleanShard sh : shards) {
      for (long[] sHashes : sh.hashedSentences) {
        int len = sHashes.length;
        for (int i = 0; i <= len; i++) {
          long h2 = (i < len) ? sHashes[i] : CleanWordLm.EOS_HASH;
          long h1 = (i - 1 >= 0) ? sHashes[i - 1] : CleanWordLm.BOS_HASH;
          if (i == 0) {
            continue; // no (BOS, BOS, w0) needed since trigramLogProb uses bigram at pos 0
          }
          long h0 = (i - 2 >= 0) ? sHashes[i - 2] : CleanWordLm.BOS_HASH;
          if (bigrams.get(CleanWordLm.bigramHash(h0, h1)) >= 2
              && bigrams.get(CleanWordLm.bigramHash(h1, h2)) >= 2) {
            rawTrigrams.add(CleanWordLm.trigramHash(h0, h1, h2), 1);
          }
        }
      }
      sh.hashedSentences.clear();
    }
    OpenLongIntMap trigrams = rawTrigrams.compactMinCount(2);
    rawTrigrams = null;

    mergedClosedWords.entrySet().removeIf(e -> e.getValue() < 3);
    mergedOpenBigrams.entrySet().removeIf(e -> e.getValue() < 4);
    mergedCasing
        .entrySet()
        .removeIf(e -> e.getValue().total() < 2 && e.getValue().apostropheStem == 0);

    CleanWordLm cleanLm = new CleanWordLm(unigrams, bigrams, trigrams, totalTokens);
    return new CleanCorpusKnowledge(
        cleanLm,
        mergedCasing,
        mergedOpenBigrams,
        mergedClosedWords,
        char4Counts,
        char3Counts,
        totalSents);
  }

  public static MinedKnowledge mine(
      List<AlignedSentence> trainSentences,
      List<Path> cleanCorpusFiles,
      int maxCleanSentencesPerFile,
      Set<String> forbiddenEvalFingerprints,
      TurkishMorphology morphology)
      throws IOException {
    CleanCorpusKnowledge clean =
        mineCleanCorpus(cleanCorpusFiles, maxCleanSentencesPerFile, forbiddenEvalFingerprints);
    return mineWithClean(trainSentences, clean, morphology);
  }

  /**
   * Combines pre-mined {@link CleanCorpusKnowledge} with aligned parallel training sentences in
   * {@code < 0.5s} (reusing {@code cleanLm} without re-reading disk).
   */
  public static MinedKnowledge mineWithClean(
      List<AlignedSentence> trainSentences,
      CleanCorpusKnowledge clean,
      TurkishMorphology morphology) {

    Map<String, MutableRewrite> raw1ToN = new HashMap<>(65_536);
    Map<String, MutableMerge> raw2To1 = new HashMap<>(8_192);
    Map<String, MutableMerge> raw3To1 = new HashMap<>(2_048);
    Map<String, Map<String, Integer>> rawSuffixEdits = new HashMap<>(8_192);
    Map<String, Integer> rawSuffixTotals = new HashMap<>(16_384);

    Map<String, CasingCounts> casingMap = new HashMap<>(clean.cleanCasing.size() * 4 / 3 + 1024);
    for (Map.Entry<String, CasingCounts> e : clean.cleanCasing.entrySet()) {
      casingMap.put(e.getKey(), e.getValue().copy());
    }

    int[] char4Counts = Arrays.copyOf(clean.char4Counts, clean.char4Counts.length);
    int[] char3ContextCounts =
        Arrays.copyOf(clean.char3ContextCounts, clean.char3ContextCounts.length);

    // Pass 1: Mine aligned parallel sentences (1:1, 1:2, 2:1, 3:1, suffix edits, gold casing)
    for (AlignedSentence sent : trainSentences) {
      List<AlignedToken> toks = sent.tokens();
      int n = toks.size();
      int i = 0;
      while (i < n) {
        AlignedToken t = toks.get(i);
        String norm = t.norm() == null ? "" : t.norm().trim();
        if (norm.isEmpty()) {
          i++;
          continue;
        }
        int j = i + 1;
        while (j < n && (toks.get(j).norm() == null || toks.get(j).norm().trim().isEmpty())) {
          j++;
        }
        int spanLen = j - i;
        if (spanLen == 2) {
          String t1Low = NormV2Types.trLower(toks.get(i).surface());
          String t2Low = NormV2Types.trLower(toks.get(i + 1).surface());
          String k2 = t1Low + "\t" + t2Low;
          String normLow = NormV2Types.trLower(norm);
          if (!isCorruptParallelPair(t1Low + t2Low, normLow)) {
            raw2To1.computeIfAbsent(k2, k -> new MutableMerge()).addMerged(norm, normLow);
          }
          recordGoldWordCasing(norm, i == 0, casingMap, char4Counts, char3ContextCounts);
          i = j;
          continue;
        } else if (spanLen == 3) {
          String t1Low = NormV2Types.trLower(toks.get(i).surface());
          String t2Low = NormV2Types.trLower(toks.get(i + 1).surface());
          String t3Low = NormV2Types.trLower(toks.get(i + 2).surface());
          String k3 = t1Low + "\t" + t2Low + "\t" + t3Low;
          String normLow = NormV2Types.trLower(norm);
          if (!isCorruptParallelPair(t1Low + t2Low + t3Low, normLow)) {
            raw3To1.computeIfAbsent(k3, k -> new MutableMerge()).addMerged(norm, normLow);
          }
          recordGoldWordCasing(norm, i == 0, casingMap, char4Counts, char3ContextCounts);
          i = j;
          continue;
        } else if (spanLen > 3) {
          i = j;
          continue;
        }

        String rawSurf = t.surface();
        String rawLow = NormV2Types.trLower(rawSurf);
        String normLow = NormV2Types.trLower(norm);

        if (!rawLow.isEmpty()
            && !"Punctuation".equals(t.type())
            && !isCorruptParallelPair(rawLow, normLow)) {
          raw1ToN.computeIfAbsent(rawLow, k -> new MutableRewrite()).add(rawLow, norm, normLow);

          if (isPureLetters(rawLow) && rawLow.length() >= 4) {
            int maxSuf = Math.min(9, rawLow.length() - 2);
            for (int sl = 2; sl <= maxSuf; sl++) {
              rawSuffixTotals.merge(rawLow.substring(rawLow.length() - sl), 1, Integer::sum);
            }
          }

          if (normLow.indexOf(' ') < 0
              && !rawLow.equals(normLow)
              && isPureLetters(rawLow)
              && isPureLettersOrApostrophe(normLow)
              && !NormV2Types.toAscii(rawLow).equals(NormV2Types.toAscii(normLow))) {
            induceSuffixPairs(rawLow, normLow, rawSuffixEdits);
          }
        }
        recordGoldWordCasing(norm, i == 0, casingMap, char4Counts, char3ContextCounts);
        i++;
      }
    }

    // Pass 1b: Count total co-occurrences of every candidate 2:1 and 3:1 merge pair in training raw text
    for (AlignedSentence sent : trainSentences) {
      List<AlignedToken> toks = sent.tokens();
      int n = toks.size();
      for (int idx = 0; idx + 1 < n; idx++) {
        String k2 =
            NormV2Types.trLower(toks.get(idx).surface())
                + "\t"
                + NormV2Types.trLower(toks.get(idx + 1).surface());
        MutableMerge mm2 = raw2To1.get(k2);
        if (mm2 != null) {
          mm2.cooccurCount++;
        }
        if (idx + 2 < n) {
          String k3 = k2 + "\t" + NormV2Types.trLower(toks.get(idx + 2).surface());
          MutableMerge mm3 = raw3To1.get(k3);
          if (mm3 != null) {
            mm3.cooccurCount++;
          }
        }
      }
    }

    Map<String, RewriteEntry> frozen1ToN = new HashMap<>(raw1ToN.size() * 4 / 3 + 1);
    for (Map.Entry<String, MutableRewrite> e : raw1ToN.entrySet()) {
      RewriteEntry re = e.getValue().freeze(8);
      if (re.totalCount() >= 2 || re.keepCount() < re.totalCount()) {
        frozen1ToN.put(e.getKey(), re);
      }
    }

    Map<String, MergeEntry> frozen2To1 = new HashMap<>(raw2To1.size() * 4 / 3 + 1);
    for (Map.Entry<String, MutableMerge> e : raw2To1.entrySet()) {
      frozen2To1.put(e.getKey(), e.getValue().freeze(4));
    }

    Map<String, MergeEntry> frozen3To1 = new HashMap<>(raw3To1.size() * 4 / 3 + 1);
    for (Map.Entry<String, MutableMerge> e : raw3To1.entrySet()) {
      frozen3To1.put(e.getKey(), e.getValue().freeze(3));
    }

    Map<String, List<SuffixRule>> suffixByLast = new HashMap<>(64);
    for (Map.Entry<String, Map<String, Integer>> e : rawSuffixEdits.entrySet()) {
      String rawSuf = e.getKey();
      int denom = Math.max(rawSuffixTotals.getOrDefault(rawSuf, 0), 1);
      for (Map.Entry<String, Integer> te : e.getValue().entrySet()) {
        int sup = te.getValue();
        String normSuf = te.getKey();
        float prec = sup / (float) (denom + 2);
        if (sup >= 4 && prec >= 0.16f) {
          String key = String.valueOf(rawSuf.charAt(rawSuf.length() - 1));
          suffixByLast
              .computeIfAbsent(key, k -> new ArrayList<>())
              .add(new SuffixRule(rawSuf, normSuf, sup, denom, prec));
        }
      }
    }
    for (List<SuffixRule> rules : suffixByLast.values()) {
      rules.sort(
          (a, b) -> {
            double sa =
                a.precision()
                    * Math.log1p(a.support())
                    * (a.rawSuffix().length() >= 3 ? 1.5 : 1.0);
            double sb =
                b.precision()
                    * Math.log1p(b.support())
                    * (b.rawSuffix().length() >= 3 ? 1.5 : 1.0);
            return Double.compare(sb, sa);
          });
    }

    CharPhonotacticModel charModel = new CharPhonotacticModel(char4Counts, char3ContextCounts);

    return new MinedKnowledge(
        frozen1ToN,
        frozen2To1,
        frozen3To1,
        suffixByLast,
        casingMap,
        clean.cleanOpenBigrams,
        clean.cleanClosedWords,
        clean.cleanLm,
        charModel,
        trainSentences.size(),
        clean.cleanSentenceCount);
  }

  private static boolean isOpenCompoundCandidate(String w1, String w2) {
    if (w2.equals("şey")
        || w2.startsWith("şeyler")
        || w2.startsWith("şeyi")
        || w2.startsWith("şeye")
        || w2.startsWith("şeyde")
        || w2.startsWith("şeyden")
        || w2.equals("de")
        || w2.equals("da")
        || w2.equals("ki")
        || w2.equals("an")
        || w2.equals("anda")
        || w2.equals("kadar")
        || w2.equals("gibi")
        || w2.equals("için")
        || w2.equals("sıra")
        || w2.equals("üzere")
        || w2.startsWith("mi")
        || w2.startsWith("mı")
        || w2.startsWith("mu")
        || w2.startsWith("mü")
        || w2.startsWith("et")
        || w2.startsWith("ed")
        || w2.startsWith("ol")) {
      return true;
    }
    return w1.equals("her")
        || w1.equals("bir")
        || w1.equals("hiç")
        || w1.equals("hiçbir")
        || w1.equals("şu")
        || w1.equals("ya")
        || w1.equals("ne")
        || w1.equals("pek")
        || w1.equals("en")
        || w1.equals("çok")
        || w1.equals("sağ")
        || w1.equals("hoş");
  }

  private static void induceSuffixPairs(
      String rawLow, String normLow, Map<String, Map<String, Integer>> rawSuffixEdits) {
    String rawAsc = NormV2Types.toAscii(rawLow);
    String normAsc = NormV2Types.toAscii(normLow);
    int maxPrefix = Math.min(rawAsc.length(), normAsc.length());
    int lcp = 0;
    while (lcp < maxPrefix && rawAsc.charAt(lcp) == normAsc.charAt(lcp)) {
      lcp++;
    }
    if (lcp < 2) {
      return;
    }
    for (int cut : new int[] {lcp, lcp - 1, lcp - 2}) {
      if (cut < 2) {
        continue;
      }
      String rawTail = rawLow.substring(cut);
      String normTail = normLow.substring(cut);
      if (rawTail.length() >= 2
          && rawTail.length() <= 9
          && normTail.length() >= 2
          && normTail.length() <= 11
          && !rawTail.equals(normTail)) {
        rawSuffixEdits
            .computeIfAbsent(rawTail, k -> new HashMap<>(4))
            .merge(normTail, 1, Integer::sum);
      }
    }
  }

  private static void recordGoldWordCasing(
      String normText,
      boolean isFirstInSentence,
      Map<String, CasingCounts> casingMap,
      int[] char4Counts,
      int[] char3ContextCounts) {
    int start = 0;
    boolean first = isFirstInSentence;
    for (int i = 0; i <= normText.length(); i++) {
      if (i == normText.length() || normText.charAt(i) == ' ') {
        if (i > start) {
          String w = normText.substring(start, i);
          recordSingleWordCasingAndChars(
              w,
              NormV2Types.trLower(w),
              first,
              casingMap,
              true,
              char4Counts,
              char3ContextCounts);
          first = false;
        }
        start = i + 1;
      }
    }
  }

  private static void recordSingleWordCasingAndChars(
      String surface,
      String lower,
      boolean isSentenceStart,
      Map<String, CasingCounts> casingMap,
      boolean updateChars,
      int[] char4Counts,
      int[] char3ContextCounts) {
    if (surface.length() < 2) {
      return;
    }
    int ap = lower.indexOf('\'');
    if (ap > 0) {
      String stemSurf = surface.substring(0, ap);
      String stemLow = lower.substring(0, ap);
      if (!isDigitsOrPunctOnly(stemLow) && stemLow.length() >= 2) {
        CasingCounts sc = casingMap.computeIfAbsent(stemLow, k -> new CasingCounts());
        sc.apostropheStem++;
        CasingShape sh = NormV2Types.detectShape(stemSurf);
        if (sh == CasingShape.UPPER) {
          sc.upper++;
        } else if (sh == CasingShape.MIXED && isPlausibleBrandMixedCase(stemSurf)) {
          sc.mixed++;
          sc.recordPlausibleMixed(stemSurf, sc.mixed);
        } else if (sh == CasingShape.TITLE && !isSentenceStart) {
          sc.title++;
        } else if (sh == CasingShape.LOWER) {
          sc.lower++;
        }
      }
      return;
    }
    if (!isPureLetters(lower)) {
      return;
    }
    CasingShape shape = NormV2Types.detectShape(surface);
    if (shape == CasingShape.UPPER) {
      casingMap.computeIfAbsent(lower, k -> new CasingCounts()).upper++;
    } else if (shape == CasingShape.MIXED && isPlausibleBrandMixedCase(surface)) {
      CasingCounts cc = casingMap.computeIfAbsent(lower, k -> new CasingCounts());
      cc.mixed++;
      cc.recordPlausibleMixed(surface, cc.mixed);
    } else if (!isSentenceStart) {
      CasingCounts cc = casingMap.computeIfAbsent(lower, k -> new CasingCounts());
      if (shape == CasingShape.TITLE) {
        cc.title++;
      } else if (shape == CasingShape.LOWER) {
        cc.lower++;
      }
    }
    if (updateChars && lower.length() >= 3 && lower.length() <= 24) {
      String padded = "^" + lower + "$";
      int mask = char4Counts.length - 1;
      for (int i = 0; i <= padded.length() - 4; i++) {
        int h4 = CharPhonotacticModel.hashChars(padded, i, 4) & mask;
        int h3 = CharPhonotacticModel.hashChars(padded, i, 3) & mask;
        char4Counts[h4]++;
        char3ContextCounts[h3]++;
      }
    }
  }

  /**
   * Filters genuine CamelCase / tech-brand mixed-case words ({@code YouTube}, {@code WhatsApp},
   * {@code ChatGPT}, {@code iPhone}, {@code eDevlet}, {@code mRNA}) from accidental shift-key typos
   * ({@code TÜrkiye}, {@code dOğru}, {@code GEldi}).
   */
  static boolean isPlausibleBrandMixedCase(String s) {
    if (s == null || s.length() < 3) {
      return false;
    }
    char c0 = s.charAt(0);
    char c1 = s.charAt(1);
    char c2 = s.charAt(2);
    if (Character.isUpperCase(c0) && Character.isUpperCase(c1) && Character.isLowerCase(c2)) {
      return false;
    }
    if (Character.isUpperCase(c0) && Character.isLowerCase(c1)) {
      return true;
    }
    if (Character.isLowerCase(c0) && Character.isUpperCase(c1)) {
      char low0 = Character.toLowerCase(c0);
      return low0 == 'i' || low0 == 'e' || low0 == 'm' || low0 == 'x';
    }
    return false;
  }

  /** Wait-free character-level word extractor for clean Turkish lines. */
  private static void extractWordsFast(String line, List<String> out) {
    out.clear();
    int n = line.length();
    int start = -1;
    for (int i = 0; i <= n; i++) {
      char c = i < n ? line.charAt(i) : ' ';
      boolean wordChar =
          Character.isLetterOrDigit(c)
              || ((c == '\'' || c == '’')
                  && start >= 0
                  && i + 1 < n
                  && Character.isLetter(line.charAt(i + 1)));
      if (wordChar) {
        if (start < 0) {
          start = i;
        }
      } else if (start >= 0) {
        out.add(line.substring(start, i).replace('’', '\''));
        start = -1;
      }
    }
  }

  /** Filters out Title-Case clickbait headlines and ALL-CAPS lines from clean corpora. */
  private static boolean isHeadlineOrAllCapsLine(List<String> words) {
    int midAlpha = 0;
    int midCap = 0;
    int allUpperWords = 0;
    for (int i = 1; i < words.size(); i++) {
      CasingShape sh = NormV2Types.detectShape(words.get(i));
      if (sh != CasingShape.NON_ALPHA) {
        midAlpha++;
        if (sh == CasingShape.TITLE || sh == CasingShape.UPPER) {
          midCap++;
        }
        if (sh == CasingShape.UPPER) {
          allUpperWords++;
        }
      }
    }
    if (midAlpha >= 3 && midCap * 10 >= midAlpha * 4) {
      return true;
    }
    return words.size() >= 3 && allUpperWords * 2 >= words.size();
  }

  static boolean isPureLetters(String s) {
    if (s.isEmpty()) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      if (!Character.isLetter(s.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  static boolean isPureLettersOrApostrophe(String s) {
    if (s.isEmpty()) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (!Character.isLetter(c) && c != '\'') {
        return false;
      }
    }
    return true;
  }
}
