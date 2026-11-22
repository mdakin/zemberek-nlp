package zemberek.normalization.dataset;

import java.util.List;

/**
 * Core enums, domain/stratum definitions, and JSONL output DTOs for Zemberek's v2 noisy Turkish
 * corpus miner (general 100K/5K/5K normalization & classification track + separate profanity track).
 */
public final class DatasetModels {

  private DatasetModels() {}

  /**
   * Sentence lexical word-count buckets requested for v2:
   * - 15% Short (2-5 words)
   * - 65% Core  (6-15 words)
   * - 20% Long  (16-20 words)
   */
  public enum LengthBucket {
    SHORT_2_5("short_2_5", 2, 5, 0.15),
    CORE_6_15("core_6_15", 6, 15, 0.65),
    LONG_16_20("long_16_20", 16, 20, 0.20);

    public final String id;
    public final int minWords;
    public final int maxWords;
    public final double targetShare;

    LengthBucket(String id, int minWords, int maxWords, double targetShare) {
      this.id = id;
      this.minWords = minWords;
      this.maxWords = maxWords;
      this.targetShare = targetShare;
    }

    public static LengthBucket forWordCount(int wc) {
      if (wc >= 2 && wc <= 5) {
        return SHORT_2_5;
      }
      if (wc >= 6 && wc <= 15) {
        return CORE_6_15;
      }
      if (wc >= 16 && wc <= 20) {
        return LONG_16_20;
      }
      return null;
    }
  }

  /**
   * Orthogonal noise-density strata preventing aggressive CleanFP over-editing bias while covering
   * single-edit production traffic, multi-phenomenon social/forum text, and pure-ASCII keyboards.
   */
  public enum NoiseTier {
    T0_CLEAN("T0_clean_control", 0.18, 0.10),
    T1_LIGHT("T1_light_1_2", 0.32, 0.20),
    T2_MEDIUM("T2_medium", 0.25, 0.30),
    T3_HEAVY("T3_heavy", 0.15, 0.25),
    T4_PURE_ASCII("T4_pure_ascii", 0.10, 0.15);

    public final String id;
    public final double targetShare;
    public final double profanityTargetShare;

    NoiseTier(String id, double targetShare, double profanityTargetShare) {
      this.id = id;
      this.targetShare = targetShare;
      this.profanityTargetShare = profanityTargetShare;
    }
  }

  /**
   * Dataset-level noise density profiles allowing separate training/evaluation sets across heavy
   * social/chat noise ({@code heavy}), everyday forum/review noise ({@code medium}), and natural
   * low-noise / clean-context production traffic ({@code low}).
   */
  public enum NoiseProfile {
    HEAVY("heavy", "gen", 0.18, 0.32, 0.25, 0.15, 0.10),
    MEDIUM("medium", "med", 0.35, 0.45, 0.15, 0.02, 0.03),
    LOW("low", "low", 0.65, 0.30, 0.04, 0.00, 0.01);

    public final String id;
    public final String recordPrefix;
    public final double t0Share;
    public final double t1Share;
    public final double t2Share;
    public final double t3Share;
    public final double t4Share;

    NoiseProfile(
        String id,
        String recordPrefix,
        double t0Share,
        double t1Share,
        double t2Share,
        double t3Share,
        double t4Share) {
      this.id = id;
      this.recordPrefix = recordPrefix;
      this.t0Share = t0Share;
      this.t1Share = t1Share;
      this.t2Share = t2Share;
      this.t3Share = t3Share;
      this.t4Share = t4Share;
    }

    public double shareFor(NoiseTier tier) {
      return switch (tier) {
        case T0_CLEAN -> t0Share;
        case T1_LIGHT -> t1Share;
        case T2_MEDIUM -> t2Share;
        case T3_HEAVY -> t3Share;
        case T4_PURE_ASCII -> t4Share;
      };
    }

    public NoiseTier[] fallbackOrder() {
      return switch (this) {
        case HEAVY -> new NoiseTier[] {
            NoiseTier.T2_MEDIUM, NoiseTier.T1_LIGHT, NoiseTier.T3_HEAVY,
            NoiseTier.T4_PURE_ASCII, NoiseTier.T0_CLEAN
        };
        case MEDIUM -> new NoiseTier[] {
            NoiseTier.T1_LIGHT, NoiseTier.T0_CLEAN, NoiseTier.T2_MEDIUM,
            NoiseTier.T4_PURE_ASCII, NoiseTier.T3_HEAVY
        };
        case LOW -> new NoiseTier[] {
            NoiseTier.T0_CLEAN, NoiseTier.T1_LIGHT, NoiseTier.T2_MEDIUM,
            NoiseTier.T4_PURE_ASCII
        };
      };
    }
  }

  /** Source domain families with target shares across the general normalization corpus. */
  public enum DomainBucket {
    ECOMMERCE_REVIEWS("ecommerce_reviews", 0.20),
    TWEETS_SOCIAL("tweets_social", 0.15),
    FORUMS_MODERN_2020S("forums_modern_2020s", 0.17),
    QA_LIFESTYLE_FORUMS("qa_lifestyle_forums", 0.17),
    EKSISOZLUK("eksisozluk", 0.11),
    MOVIES_COMPLAINTS("movies_complaints", 0.10),
    CIVIC_AND_QUERIES("civic_and_queries", 0.10);

    public final String id;
    public final double targetShare;

    DomainBucket(String id, double targetShare) {
      this.id = id;
      this.targetShare = targetShare;
    }
  }

  /** Stratification subtypes for the separate profanity & offensive slang dataset. */
  public enum ProfanitySubtype {
    EXPLICIT_VULGAR("explicit_vulgar", 0.40),
    ACRONYM_AND_MASKED("acronym_and_masked", 0.25),
    TARGETED_INSULT("targeted_insult", 0.25),
    BENIGN_HARD_NEGATIVE("benign_hard_negative", 0.10);

    public final String id;
    public final double targetShare;

    ProfanitySubtype(String id, double targetShare) {
      this.id = id;
      this.targetShare = targetShare;
    }
  }

  /** Retained upstream ground-truth metadata for multi-task classification experiments. */
  public record TaskLabels(
      String sentiment,
      Double stars,
      String offenseGold,
      String categoryOrTopic
  ) {
    public static final TaskLabels EMPTY = new TaskLabels(null, null, null, null);

    public boolean isEmpty() {
      return sentiment == null && stars == null && offenseGold == null && categoryOrTopic == null;
    }
  }

  /** Split-safe raw document ingested from corpora_raw/, queries/, or local forum archives. */
  public record RawDocument(
      DomainBucket domain,
      String sourceId,
      String text,
      TaskLabels labels,
      boolean checkMidWordTruncation
  ) {}

  /** Candidate sentence scored and tagged by {@link ProfanityAndNoiseAnalyzer}. */
  public record MinedCandidate(
      DomainBucket domain,
      String sourceId,
      String text,
      int wordCount,
      int charLen,
      LengthBucket lengthBucket,
      NoiseTier noiseTier,
      ProfanitySubtype profanitySubtype, // null for clean general track
      int noisyWordCount,
      int unknownOovCount,
      double noisyRatio,
      double qualityScore,
      List<String> phenomena,
      List<String> profanityTags,
      List<String> noisyTokens,
      TaskLabels labels,
      String dedupSkeleton,
      String opening3Key,
      long[] shingleHashes
  ) {
    public boolean isProfanityTrack() {
      return profanitySubtype != null && profanitySubtype != ProfanitySubtype.BENIGN_HARD_NEGATIVE;
    }
  }

  /** Serializable JSONL record written to train/dev/test slices and sample inspection files. */
  public static final class OutputRecord {
    public String id;
    public String split;
    public String slice;
    public String track;
    public String domain;
    public String source_id;
    public String text;
    public int word_count;
    public int char_len;
    public String length_bucket;
    public String noise_tier;
    public String profanity_subtype;
    public int noisy_word_count;
    public int unknown_oov_count;
    public double noisy_ratio;
    public double quality_score;
    public List<String> phenomena;
    public List<String> profanity_tags;
    public List<String> noisy_tokens;
    public LabelsDto labels;

    public static final class LabelsDto {
      public String sentiment;
      public Double stars;
      public String offense_gold;
      public String category_or_topic;
    }

    public static OutputRecord from(
        String id, String split, String slice, String track, MinedCandidate c) {
      OutputRecord r = new OutputRecord();
      r.id = id;
      r.split = split;
      r.slice = slice;
      r.track = track;
      r.domain = c.domain().id;
      r.source_id = c.sourceId();
      r.text = c.text();
      r.word_count = c.wordCount();
      r.char_len = c.charLen();
      r.length_bucket = c.lengthBucket().id;
      r.noise_tier = c.noiseTier().id;
      r.profanity_subtype = c.profanitySubtype() != null ? c.profanitySubtype().id : null;
      r.noisy_word_count = c.noisyWordCount();
      r.unknown_oov_count = c.unknownOovCount();
      r.noisy_ratio = c.noisyRatio();
      r.quality_score = c.qualityScore();
      r.phenomena = c.phenomena();
      r.profanity_tags = c.profanityTags().isEmpty() ? null : c.profanityTags();
      r.noisy_tokens = c.noisyTokens();
      if (!c.labels().isEmpty()) {
        LabelsDto l = new LabelsDto();
        l.sentiment = c.labels().sentiment();
        l.stars = c.labels().stars();
        l.offense_gold = c.labels().offenseGold();
        l.category_or_topic = c.labels().categoryOrTopic();
        r.labels = l;
      }
      return r;
    }
  }
}
