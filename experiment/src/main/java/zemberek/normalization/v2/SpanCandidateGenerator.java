package zemberek.normalization.v2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import zemberek.core.turkish.PrimaryPos;
import zemberek.core.turkish.RootAttribute;
import zemberek.core.turkish.SecondaryPos;
import zemberek.core.turkish.Turkish;
import zemberek.core.turkish.TurkishAlphabet;
import zemberek.lm.LmVocabulary;
import zemberek.lm.compression.SmoothLm;
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.analysis.AnalysisCache;
import zemberek.morphology.analysis.InformalAnalysisConverter;
import zemberek.morphology.analysis.SentenceAnalysis;
import zemberek.morphology.analysis.SingleAnalysis;
import zemberek.morphology.generator.WordGenerator.Result;
import zemberek.morphology.lexicon.DictionaryItem;
import zemberek.morphology.morphotactics.Morpheme;
import zemberek.morphology.morphotactics.TurkishMorphotactics;
import zemberek.normalization.deasciifier.latticedeasciifier.LatticeDeasciifier;
import zemberek.normalization.deasciifier.latticedeasciifier.LatticeDeasciifier.CircumflexMode;
import zemberek.normalization.experiment.CompactStemEndingSpellChecker;
import zemberek.normalization.experiment.CompactStemEndingSpellChecker.ScoredCandidate;
import zemberek.normalization.v2.NormV2Types.AlignedSentence;
import zemberek.normalization.v2.NormV2Types.CasingShape;
import zemberek.normalization.v2.NormV2Types.PreparedLattice;
import zemberek.normalization.v2.NormV2Types.SentenceLattice;
import zemberek.normalization.v2.NormV2Types.SpanArc;
import zemberek.normalization.v2.NormV2Types.SpanToken;
import zemberek.normalization.v2.ParallelCorpusMiner.CleanWordLm;
import zemberek.normalization.v2.ParallelCorpusMiner.MergeEntry;
import zemberek.normalization.v2.ParallelCorpusMiner.MinedKnowledge;
import zemberek.normalization.v2.ParallelCorpusMiner.RewriteEntry;
import zemberek.normalization.v2.ParallelCorpusMiner.SuffixRule;
import zemberek.normalization.v2.ParallelCorpusMiner.TargetCount;
import zemberek.tokenization.Token;

/**
 * Single-pass <b>Multi-Span Candidate DAG (Directed Acyclic Graph)</b> builder ({@code v2.3})
 * shared by {@link SpanLatticePerceptronNormalizer} and {@link TwoStageGbdtNormalizer}.
 *
 * <p>Combines empirical parallel corpus tables, Zemberek formal &amp; informal morphology (both
 * exact-vowel {@code SRC_INFORMAL_EXACT} and diacritic-ignoring {@code SRC_INFORMAL_MORPH} finite-state
 * transducers), gated whole-sentence {@link LatticeDeasciifier}, induced colloquial suffix rules,
 * <b>TDK</b> (<i>Türk Dil Kurumu</i>) apostrophe formatting &amp; erroneous-apostrophe cleanup,
 * ambiguity-preserving {@code 1:2} clitic/compound splits &amp; {@code 2:1}/{@code 3:1} merges, and
 * guarded <b>CSR</b> (<i>Compressed Sparse Row</i>) trie spellchecking ({@link
 * CompactStemEndingSpellChecker}).
 */
public final class SpanCandidateGenerator {

  /** Feature-source toggles and per-slot pruning cap used for component ablations and runtime lattice construction. */
  public static final class Config {
    /** Include empirical {@code 1:1}, {@code 1:2}, {@code 2:1}, and {@code 3:1} candidates from {@link ParallelCorpusMiner}. */
    public boolean useParallelTables = true;

    /** Include Zemberek exact/ASCII informal morphology, formal morphology, and gated {@link LatticeDeasciifier}. */
    public boolean useZemberekMorphAndDeasc = true;

    /** Include mined productive suffix rules, locative haplology, and {@code -cIm -> -cIğIm} hypocoristics. */
    public boolean useInducedSuffixRules = true;

    /** Include multi-word {@code 1:2} clitic/unit/compound split arcs and {@code 2:1}/{@code 3:1} merge arcs. */
    public boolean useSplitMergeArcs = true;

    /** Include guarded {@link CompactStemEndingSpellChecker} candidates on unhandled OOV tokens. */
    public boolean useGuardedSpellChecker = true;

    /** Include curated chat shorthands, compositional shorthand+suffix, and {@code >=5}-consonant Abjad candidates. */
    public boolean useShorthandAndAbjad = true;

    /** Maximum {@code [i, i+1)} candidate arcs retained per start token after heuristic pre-ranking. */
    public int maxArcsPerStart = 14;

    public static Config full() {
      return new Config();
    }

    public Config copy() {
      Config c = new Config();
      c.useParallelTables = this.useParallelTables;
      c.useZemberekMorphAndDeasc = this.useZemberekMorphAndDeasc;
      c.useInducedSuffixRules = this.useInducedSuffixRules;
      c.useSplitMergeArcs = this.useSplitMergeArcs;
      c.useGuardedSpellChecker = this.useGuardedSpellChecker;
      c.useShorthandAndAbjad = this.useShorthandAndAbjad;
      c.maxArcsPerStart = this.maxArcsPerStart;
      return c;
    }
  }

  private static final Set<String> LEXICAL_KI_WORDS =
      Set.of(
          "sanki",
          "oysaki",
          "mademki",
          "belki",
          "halbuki",
          "çünkü",
          "meğerki",
          "illaki",
          "benimki",
          "seninki",
          "onunki",
          "bizimki",
          "sizinki",
          "onlarınki",
          "dünkü",
          "bugünkü",
          "yarınki",
          "akşamki",
          "sabahki",
          "önceki",
          "sonraki",
          "öteki",
          "beriki",
          "karşıki",
          "yukarıki",
          "aşağıki");

  private static final Set<String> NON_SPLITTABLE_LOC_ADVERBS =
      Set.of("işte", "elbette", "adeta", "birlikte");

  private static final Set<String> CLOSED_COMPOUND_PREFIXES =
      Set.of(
          "hiçbir",
          "birkaç",
          "birçok",
          "herhangi",
          "birbiri",
          "vazgeç",
          "başvur",
          "öngör",
          "varsay");

  private static final Set<String> QUESTION_TAILS =
      Set.of(
          "mı",
          "mi",
          "mu",
          "mü",
          "mıyım",
          "miyim",
          "muyum",
          "müyüm",
          "mısın",
          "misin",
          "musun",
          "müsün",
          "mıyız",
          "miyiz",
          "muyuz",
          "müyüz",
          "mısınız",
          "misiniz",
          "musunuz",
          "müsünüz",
          "mıydı",
          "miydi",
          "muydu",
          "müydü",
          "mıydım",
          "miydim",
          "muydum",
          "müydüm",
          "mıydın",
          "miydin",
          "muydun",
          "müydün",
          "mıydık",
          "miydik",
          "muyduk",
          "müydük",
          "mıydınız",
          "miydiniz",
          "muydunuz",
          "müydünüz",
          "mıymış",
          "miymiş",
          "muymuş",
          "müymüş");

  private static final Set<String> COMMON_UNITS =
      Set.of(
          "gb", "mb", "kb", "tb", "tl", "usd", "eur", "mhz", "ghz", "mah", "kg", "gr", "mg", "cm",
          "mm", "km", "ml", "lt", "dk", "sn", "saat", "gün", "ay", "yıl", "adet");

  private static final Set<String> UPPERCASE_UNITS =
      Set.of("gb", "mb", "kb", "tb", "tl", "usd", "eur", "mhz", "ghz", "hd", "fps");

  private static final Set<String> DETACHED_NUM_SUFFIXES =
      Set.of(
          "da", "de", "ta", "te", "dan", "den", "tan", "ten", "a", "e", "ya", "ye", "ı", "i", "u",
          "ü", "yı", "yi", "yu", "yü", "ın", "in", "un", "ün", "nın", "nin", "nun", "nün", "la",
          "le", "yla", "yle", "ar", "er", "şar", "şer", "ıncı", "inci", "uncu", "üncü", "ncı",
          "nci", "ncu", "ncü", "sini", "sını", "sunu", "sünü", "ini", "ını", "unu", "ünü", "si",
          "sı", "su", "sü", "nde", "nda", "nden", "ndan", "lı", "li", "lu", "lü", "lık", "lik",
          "luk", "lük", "lılar", "liler", "lular", "lüler", "lar", "ler", "dı", "di", "du", "dü",
          "tı", "ti", "tu", "tü", "dır", "dir", "dur", "dür", "tır", "tir", "tur", "tür");

  private static final Set<String> DETACHED_WORD_APOSTROPHE_SUFFIXES =
      Set.of(
          "da", "de", "ta", "te", "dan", "den", "tan", "ten", "a", "e", "ya", "ye", "ı", "i", "u",
          "ü", "yı", "yi", "yu", "yü", "ın", "in", "un", "ün", "nın", "nin", "nun", "nün", "ini",
          "ını", "unu", "ünü", "si", "sı", "su", "sü", "nde", "nda", "nden", "ndan", "la", "le",
          "yla", "yle", "lı", "li", "lu", "lü", "lık", "lik", "luk", "lük", "lılar", "liler",
          "lular", "lüler", "lar", "ler", "dı", "di", "du", "dü", "tı", "ti", "tu", "tü");

  private static final Set<String> DETACHED_PROPER_NONLOC_SUFFIXES =
      Set.of(
          "ya", "ye", "a", "e", "yı", "yi", "yu", "yü", "nın", "nin", "nun", "nün", "ın", "in",
          "un", "ün", "yla", "yle", "la", "le", "lı", "li", "lu", "lü", "lık", "lik", "luk", "lük",
          "lar", "ler", "ları", "leri", "ların", "lerin", "lisini", "lısını", "lusunu", "lüsünü",
          "cılar", "ciler", "cular", "cüler", "çılar", "çiler", "çular", "çüler");

  private static final Set<String> SPLIT_COPULA_TAILS =
      Set.of(
          "dı", "di", "du", "dü", "tı", "ti", "tu", "tü", "dım", "dim", "dum", "düm", "tım", "tim",
          "tum", "tüm", "dın", "din", "dun", "dün", "tın", "tin", "tun", "tün", "dık", "dik", "duk",
          "dük", "tık", "tik", "tuk", "tük", "mış", "miş", "muş", "müş", "ydı", "ydi", "ydu", "ydü",
          "sa", "se", "ysa", "yse");

  private static final String[] OOV_PROPER_INFLECT_SUFS = {
    "dayız", "deyiz", "tayız", "teyiz", "dayım", "deyim", "tayım", "teyim", "ndaki", "ndeki",
    "daki", "deki", "taki", "teki", "lılar", "liler", "lular", "lüler", "dır", "dir", "dur", "dür",
    "tır", "tir", "tur", "tür", "ydı", "ydi", "ydu", "ydü", "dan", "den", "tan", "ten", "da", "de",
    "ta", "te", "nın", "nin", "nun", "nün", "ın", "in", "un", "ün", "ya", "ye", "yı", "yi", "yu",
    "yü", "yla", "yle", "la", "le", "li", "lı", "lu", "lü", "lik", "lık", "luk", "lük", "ler",
    "lar", "di", "dı", "du", "dü", "ti", "tı", "tu", "tü", "a", "e", "ı", "i", "u", "ü"
  };

  private static final String[] SPLIT_HEAD_PROPER_SUFS = {
    "nın", "nin", "nun", "nün", "ın", "in", "un", "ün", "ya", "ye", "yı", "yi", "yu", "yü", "da",
    "de", "ta", "te", "dan", "den", "tan", "ten", "yla", "yle", "la", "le", "a", "e", "ı", "i", "u",
    "ü"
  };

  private static final String[] TDK_NO_APOSTROPHE_DERIV_PREFIXES = {
    "lı", "li", "lu", "lü", "lık", "lik", "luk", "lük", "lar", "ler", "ca", "ce", "ça", "çe",
    "cı", "ci", "cu", "cü", "çı", "çi", "çu", "çü", "gil", "cığ", "ciğ", "cuğ", "cüğ", "çığ",
    "çiğ", "çuğ", "çüğ", "cug", "cüg", "cım", "cim", "cum", "cüm"
  };

  /** Vetted conversational SMS/chat shorthands loaded from {@code /normalization/v2/shorthands.tsv}. */
  private static final Map<String, String> CURATED_CHAT_SHORTHANDS = loadCuratedChatShorthands();

  private static final String NO_REALIZATION = "";

  private record CachedMorphItem(
      String fixedProperSurface,
      String normLower,
      int sourceMask,
      float suffixPrecision,
      float editCost,
      int diacriticDiffs) {}

  private record SplitHead(String customSurf, String normLower, boolean isProper) {}

  private final TurkishMorphology strictMorphology;
  private final TurkishMorphology exactInformalMorphology;
  private final TurkishMorphology informalAsciiMorphology;
  private final InformalAnalysisConverter informalConverter;
  private final LatticeDeasciifier latticeDeasciifier;
  private final CompactStemEndingSpellChecker spellChecker;
  private final SmoothLm lm;
  private final LmVocabulary lmVocab;
  private final boolean useCleanBigramWhenLmNull;
  private final MinedKnowledge mined;
  private final Config config;
  private final java.util.concurrent.ConcurrentHashMap<String, MorphSummary> morphSummaryCache =
      new java.util.concurrent.ConcurrentHashMap<>(32_768);
  private final java.util.concurrent.ConcurrentHashMap<String, String> realizeWordCache =
      new java.util.concurrent.ConcurrentHashMap<>(32_768);
  private final java.util.concurrent.ConcurrentHashMap<String, List<CachedMorphItem>>
      morphRealizeCache = new java.util.concurrent.ConcurrentHashMap<>(32_768);
  private final java.util.concurrent.ConcurrentHashMap<String, Character> questionVowelCache =
      new java.util.concurrent.ConcurrentHashMap<>(16_384);

  public SpanCandidateGenerator(
      TurkishMorphology strictMorphology,
      CompactStemEndingSpellChecker spellChecker,
      SmoothLm lm,
      MinedKnowledge mined,
      Config config) {
    this(strictMorphology, spellChecker, lm, mined, config, false);
  }

  public SpanCandidateGenerator(
      TurkishMorphology strictMorphology,
      CompactStemEndingSpellChecker spellChecker,
      SmoothLm lm,
      MinedKnowledge mined,
      Config config,
      boolean useCleanBigramWhenLmNull) {
    this.strictMorphology = strictMorphology;
    this.exactInformalMorphology =
        TurkishMorphology.builder()
            .setLexicon(strictMorphology.getLexicon())
            .useInformalAnalysis()
            .setCache(AnalysisCache.builder().dynamicCacheSize(16_384, 65_536).build())
            .setAmbiguityResolver((s, a) -> new SentenceAnalysis(s, Collections.emptyList()))
            .build();
    this.informalAsciiMorphology =
        TurkishMorphology.builder()
            .setLexicon(strictMorphology.getLexicon())
            .useInformalAnalysis()
            .ignoreDiacriticsInAnalysis()
            .setCache(AnalysisCache.builder().dynamicCacheSize(16_384, 65_536).build())
            .setAmbiguityResolver((s, a) -> new SentenceAnalysis(s, Collections.emptyList()))
            .build();
    this.informalConverter = new InformalAnalysisConverter(strictMorphology.getWordGenerator());
    this.latticeDeasciifier =
        LatticeDeasciifier.createWithDefaults(strictMorphology, informalAsciiMorphology)
            .withCircumflexMode(CircumflexMode.NONE);
    this.spellChecker = spellChecker;
    this.lm = lm;
    this.lmVocab = lm != null ? lm.getVocabulary() : null;
    this.useCleanBigramWhenLmNull = useCleanBigramWhenLmNull;
    this.mined = mined;
    this.config = config;
  }

  public SmoothLm lm() {
    return lm;
  }

  public MinedKnowledge mined() {
    return mined;
  }

  public TurkishMorphology strictMorphology() {
    return strictMorphology;
  }

  public void clearRuntimeCaches() {
    morphSummaryCache.clear();
    realizeWordCache.clear();
    morphRealizeCache.clear();
    questionVowelCache.clear();
    if (spellChecker != null) {
      spellChecker.clearCache();
    }
  }

  /**
   * Builds LOO-adjusted training lattices and projected oracle paths in parallel across CPU cores
   * so Perceptron, GBDT, and OOF-Hybrid share identical pre-built lattices without redundant work.
   */
  public List<PreparedLattice> prepareTrainingLattices(List<AlignedSentence> trainData) {
    return trainData
        .parallelStream()
        .map(
            as -> {
              SentenceLattice lat = buildLattice(as.rawText());
              if (lat.tokens().isEmpty()) {
                return null;
              }
              mined.applyLeaveOneSentenceOut(lat, as);
              List<SpanArc> oracle = SpanLatticePerceptronNormalizer.projectOraclePath(as, lat);
              if (oracle.isEmpty()) {
                return null;
              }
              SpanArc[] byStart = new SpanArc[lat.tokens().size()];
              for (SpanArc ga : oracle) {
                byStart[ga.startIdx] = ga;
              }
              return new PreparedLattice(as, lat, oracle, byStart);
            })
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  public SentenceLattice buildLattice(String rawSentence) {
    return buildLattice(rawSentence, null);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public SentenceLattice buildLattice(
      String rawSentence, BiPredicate<List<SpanToken>, Integer> skipHeavyExpansion) {
    List<SpanToken> tokens = NormV2Types.spanTokenize(rawSentence);
    int n = tokens.size();
    List<SpanArc>[] outgoing = new List[n];
    for (int i = 0; i < n; i++) {
      outgoing[i] = new ArrayList<>(4);
    }
    if (n == 0) {
      return new SentenceLattice(rawSentence, tokens, outgoing, 0f, 0f, (byte) 0, false, false);
    }

    int alphaWords = 0;
    int allUpperWords = 0;
    int titleCaseWords = 0;
    int asciiCandidateWords = 0;
    int pureAsciiWords = 0;
    int explicitDiacriticWords = 0;
    int oovAsciiWords = 0;
    int oovWords = 0;
    boolean firstAlphaSeen = false;
    boolean firstAlphaCap = false;
    boolean hasQuestionMark = false;
    byte overtSubjectPerson = 0;

    boolean[] strictKnown = new boolean[n];
    for (int i = 0; i < n; i++) {
      SpanToken t = tokens.get(i);
      if (t.type() == Token.Type.Punctuation && t.surface().indexOf('?') >= 0) {
        hasQuestionMark = true;
      }
      if (t.isImmutable()) {
        continue;
      }
      if (t.shape() != CasingShape.NON_ALPHA) {
        alphaWords++;
        if (t.shape() == CasingShape.UPPER) {
          allUpperWords++;
        } else if (t.shape() == CasingShape.TITLE) {
          titleCaseWords++;
        }
        if (!firstAlphaSeen) {
          firstAlphaSeen = true;
          firstAlphaCap = Character.isUpperCase(t.surface().charAt(0));
        }
      }
      boolean reg = hasRegularStrictAnalysis(t.lower());
      strictKnown[i] = reg;
      if (!reg) {
        oovWords++;
      }
      boolean explicitDiac = hasExplicitTurkishDiacritics(t.lower());
      if (explicitDiac) {
        explicitDiacriticWords++;
      }
      boolean upperAmbI = t.hasUpperAsciiAmbiguousI();
      if (hasAsciiAmbiguousChars(t.lower()) || upperAmbI) {
        asciiCandidateWords++;
        if (upperAmbI || !explicitDiac) {
          pureAsciiWords++;
        }
        if (!reg) {
          oovAsciiWords++;
        }
      }
      if (overtSubjectPerson == 0) {
        overtSubjectPerson =
            switch (t.lower()) {
              case "ben" -> (byte) 1;
              case "sen" -> (byte) 2;
              case "biz" -> (byte) 4;
              case "siz" -> (byte) 5;
              default -> (byte) 0;
            };
      }
    }

    boolean sentenceAllCaps = alphaWords >= 3 && allUpperWords * 10 >= alphaWords * 8;
    boolean sentenceTitleNoisy =
        !sentenceAllCaps && alphaWords >= 4 && titleCaseWords * 100 >= alphaWords * 45;
    boolean startsCap = sentenceAllCaps || firstAlphaCap;
    float sentenceAsciiRatio =
        asciiCandidateWords > 0 ? (pureAsciiWords / (float) asciiCandidateWords) : 0.0f;
    float sentenceOovRatio = alphaWords > 0 ? (oovWords / (float) alphaWords) : 0.0f;

    String[] deascByTok = null;
    boolean shouldRunSentenceDeasc =
        oovAsciiWords > 0
            || explicitDiacriticWords == 0
            || (pureAsciiWords >= 2 && pureAsciiWords * 2 >= alphaWords);
    if (config.useZemberekMorphAndDeasc && asciiCandidateWords > 0 && shouldRunSentenceDeasc) {
      boolean loweredAllCaps =
          sentenceAllCaps
              && pureAsciiWords > 0
              && !NormV2Types.hasUpperTurkishDiacritics(rawSentence);
      String deascInput =
          loweredAllCaps ? NormV2Types.trLower(rawSentence).replace('ı', 'i') : rawSentence;
      String deascSent = latticeDeasciifier.deasciifyKnownWords(deascInput);
      if (!deascSent.equals(deascInput)) {
        List<SpanToken> dToks = NormV2Types.spanTokenize(deascSent);
        List<SpanToken> inToks = loweredAllCaps ? NormV2Types.spanTokenize(deascInput) : tokens;
        if (dToks.size() == n && inToks.size() == n) {
          deascByTok = new String[n];
          for (int i = 0; i < n; i++) {
            String dLow = dToks.get(i).lower();
            String inLow = inToks.get(i).lower();
            if (!dLow.equals(inLow) || hasRegularStrictAnalysis(dLow)) {
              deascByTok[i] = dLow;
            }
          }
        }
      }
    }

    boolean[] capStartAt =
        computeSentenceStarts(tokens, startsCap, sentenceAllCaps, sentenceTitleNoisy);
    int[] casingCtx =
        computeTokenCasingContexts(tokens, sentenceAllCaps, sentenceTitleNoisy);

    for (int i = 0; i < n; i++) {
      SpanToken tok = tokens.get(i);
      boolean capHere = capStartAt[i];
      int tokCaseCtx = casingCtx[i];

      if (tok.isImmutable()) {
        short punctTag = encodeMorphTag(PrimaryPos.Punctuation, null);
        outgoing[i].add(
            SpanArc.plain(
                i, i + 1, tok.surface(), tok.lower(), NormV2Types.SRC_IDENTITY, punctTag));
        if (config.useSplitMergeArcs) {
          addMultiTokenMerges(tokens, i, capHere, sentenceAllCaps, tokCaseCtx, outgoing[i]);
        }
        continue;
      }

      Map<String, CandidateDraft> drafts = new LinkedHashMap<>(10);
      char expectedPrevQ = computeImmediatePrevQuestionVowel(tokens, deascByTok, i, sentenceAllCaps);

      // (A) Identity candidate (truecased cleanly; neutralize context-free unigram keepProb on disharmonic mI)
      RewriteEntry parEntry =
          config.useParallelTables ? mined.parallel1ToN.get(tok.lower()) : null;
      float rawKeepProb = parEntry != null ? parEntry.keepProb() : 0.0f;
      float rawKeepLogCnt =
          parEntry != null ? (float) Math.log1p(parEntry.keepCount()) : 0.0f;
      boolean disharmonicStandaloneQuestion =
          expectedPrevQ != 0
              && isStandaloneQuestionClitic(tok.lower())
              && tok.lower().length() >= 2
              && tok.lower().charAt(1) != expectedPrevQ
              && isSameRoundingQuestionVowel(tok.lower().charAt(1), expectedPrevQ);
      float idParProb =
          disharmonicStandaloneQuestion ? Math.min(rawKeepProb, 0.12f) : rawKeepProb;
      float idParLogCnt =
          disharmonicStandaloneQuestion ? Math.min(rawKeepLogCnt, 1.0f) : rawKeepLogCnt;
      String idSurf =
          mined.truecase(
              tok.lower(),
              tok.shape(),
              tok.surface(),
              capHere,
              sentenceAllCaps,
              strictMorphology,
              tokCaseCtx);
      addOrMergeDraft(
          drafts,
          tok,
          idSurf,
          tok.lower(),
          NormV2Types.SRC_IDENTITY
              | (strictKnown[i] ? NormV2Types.SRC_FORMAL_MORPH : 0)
              | (parEntry != null && parEntry.keepCount() > 0
                  ? NormV2Types.SRC_PARALLEL_1TO1
                  : 0),
          idParProb,
          idParLogCnt,
          0.0f,
          0.0f,
          0);

      // (B) Parallel 1:1 & 1:2 rewrite table (guarded on explicit UPPER acronyms like "TSK", "AB")
      boolean protectExplicitUpperAcronym =
          tok.shape() == CasingShape.UPPER
              && !sentenceAllCaps
              && (mined.isKnownAcronymStem(tok.lower()) || isKnownStrictAbbreviation(tok.lower()));
      boolean protectMidTitlePhrase =
          !sentenceAllCaps
              && !sentenceTitleNoisy
              && !capHere
              && tok.shape() == CasingShape.TITLE
              && i > 0
              && tokens.get(i - 1).shape() == CasingShape.TITLE;
      if (parEntry != null) {
        for (TargetCount tc : parEntry.targets()) {
          if (tc.normLower().equals(tok.lower())) {
            continue;
          }
          if ((protectExplicitUpperAcronym || protectMidTitlePhrase)
              && !TurkishAlphabet.INSTANCE.equalsIgnoreDiacritics(tok.lower(), tc.normLower())) {
            continue;
          }
          int srcFlag =
              tc.normLower().indexOf(' ') >= 0
                  ? NormV2Types.SRC_PARALLEL_SPLIT
                  : NormV2Types.SRC_PARALLEL_1TO1;
          String surf =
              mined.truecase(
                  tc.normLower(),
                  tok.shape(),
                  tok.surface(),
                  capHere,
                  sentenceAllCaps,
                  strictMorphology);
          if (tc.bestSurface().indexOf('\'') >= 0 && surf.indexOf('\'') < 0) {
            surf =
                mined.truecase(
                    NormV2Types.trLower(tc.bestSurface()),
                    tok.shape(),
                    tok.surface(),
                    capHere,
                    sentenceAllCaps,
                    strictMorphology);
          }
          float prob = parEntry.probOf(tc.normLower());
          float logCnt = (float) Math.log1p(tc.count());
          int dDiffs = countDiacriticDiffs(tok.lower(), tc.normLower());
          float eCost = computeWeightedEditCost(tok.lower(), tc.normLower());
          addOrMergeDraft(
              drafts, tok, surf, tc.normLower(), srcFlag, prob, logCnt, 0.0f, eCost, dDiffs);
        }
      }

      boolean skipHeavy = skipHeavyExpansion != null && skipHeavyExpansion.test(tokens, i);

      // (C) Whole-sentence LatticeDeasciifier + Exact/ASCII Zemberek Morphology
      if (!skipHeavy && config.useZemberekMorphAndDeasc && !tok.isRandomLaugh()) {
        if (deascByTok != null && deascByTok[i] != null && !deascByTok[i].equals(tok.lower())) {
          String dl = deascByTok[i];
          if (!stripsExplicitDiacritic(tok.lower(), dl)) {
            String ds =
                mined.truecase(
                    dl, tok.shape(), tok.surface(), capHere, sentenceAllCaps, strictMorphology);
            int dDiffs = countDiacriticDiffs(tok.lower(), dl);
            addOrMergeDraft(
                drafts,
                tok,
                ds,
                dl,
                NormV2Types.SRC_DEASCIIFIER,
                0.0f,
                0.0f,
                0.0f,
                dDiffs * 0.12f,
                dDiffs);
          }
        }
        if (!strictKnown[i]
            || tok.hasUpperAsciiAmbiguousI()
            || (tok.lower().length() <= 6
                && !hasExplicitTurkishDiacritics(tok.lower())
                && hasAsciiAmbiguousChars(tok.lower()))) {
          addMorphologicalRealizations(tok, capHere, sentenceAllCaps, drafts);
        }
        // Same-rounding diacritic twins ("mi"<->"mı", "misin"<->"mısın", "misiniz"<->"mısınız", "mu"<->"mü")
        if (!(!sentenceAllCaps && !capHere && tok.shape() == CasingShape.TITLE)) {
          addQuestionCliticDiacriticTwins(
              tok,
              tok.lower(),
              capHere,
              sentenceAllCaps,
              expectedPrevQ,
              rawKeepProb,
              rawKeepLogCnt,
              false,
              drafts);
        }
      }

      // (D) Productive Induced Suffix Rules + Haplology + Hypocoristics (on OOV / informal tokens)
      if (!skipHeavy && !strictKnown[i] && config.useInducedSuffixRules && !tok.isRandomLaugh()) {
        addSuffixAndHaplologyCandidates(tok, capHere, sentenceAllCaps, drafts);
      }

      // (D2) Curated Shorthands, Compositional Shorthand+Suffix & Scoped >=5-Consonant Abjad Index
      if (config.useShorthandAndAbjad
          && !strictKnown[i]
          && !tok.containsDigit()
          && !tok.isRandomLaugh()
          && !(tok.shape() == CasingShape.UPPER && !sentenceAllCaps)) {
        addShorthandAndAbjadCandidates(tok, capHere, sentenceAllCaps, drafts);
      }

      // (E) TDK ProperNoun / Acronym / Numeral Apostrophe & Erroneous Apostrophe Removal
      if (!skipHeavy && !tok.isRandomLaugh()) {
        addProperAcronymAndNumeralCandidates(tok, capHere, sentenceAllCaps, drafts);
      }

      // (F) Expressive Elongation Squeeze
      if (!skipHeavy && !tok.isRandomLaugh() && hasRepeatedLetters(tok.lower())) {
        addElongationCandidates(
            tok, capHere, sentenceAllCaps, expectedPrevQ, strictKnown[i], drafts);
      }

      // (G) Ambiguity-Preserving 1:2 Clitic & Open-Compound Splits
      if (config.useSplitMergeArcs && !tok.isRandomLaugh()) {
        addAmbiguityPreservingSplits(tok, capHere, sentenceAllCaps, strictKnown[i], drafts);
      }

      // (H) Guarded Compact CSR SpellChecker
      boolean protectStrictProperOrUpper =
          (!sentenceAllCaps && tok.shape() == CasingShape.UPPER)
              || (isStrictProperOrAbbrOnly(tok.lower())
                  && (tok.shape() != CasingShape.LOWER
                      || (mined.cleanLm != null && mined.cleanLm.unigramCount(tok.lower()) >= 10)));
      boolean protectShortCleanLoanword =
          (parEntry == null || parEntry.keepCount() >= parEntry.totalCount())
              && mined.cleanLm != null
              && ((tok.lower().length() <= 4 && mined.cleanLm.unigramCount(tok.lower()) >= 12)
                  || (tok.lower().length() <= 6
                      && !hasExplicitTurkishDiacritics(tok.lower())
                      && mined.cleanLm.unigramCount(tok.lower()) >= 35));
      if (!skipHeavy
          && config.useGuardedSpellChecker
          && spellChecker != null
          && !strictKnown[i]
          && !protectStrictProperOrUpper
          && !protectShortCleanLoanword
          && !mined.isKnownAcronymStem(tok.lower())
          && !tok.containsDigit()
          && !tok.isRandomLaugh()
          && tok.lower().length() >= 3
          && tok.lower().length() <= 18
          && (parEntry == null || parEntry.totalCount() < 8)) {
        boolean hasCleanDiacriticCand = hasLowCostMorphDraft(drafts);
        boolean protectMidTitle =
            !sentenceAllCaps && !sentenceTitleNoisy && !capHere && tok.shape() == CasingShape.TITLE;
        if (!containsForeignWxq(tok.lower()) && !hasCleanDiacriticCand) {
          addGuardedSpellCandidates(tok, capHere, sentenceAllCaps, protectMidTitle, drafts);
        }
      }

      if (tokCaseCtx != 0) {
        Map<String, CandidateDraft> ctxDrafts = new LinkedHashMap<>(drafts.size());
        for (CandidateDraft d : drafts.values()) {
          String surf = d.normSurface;
          String baseTruecased =
              mined.truecase(
                  d.normLower,
                  tok.shape(),
                  tok.surface(),
                  capHere,
                  sentenceAllCaps,
                  strictMorphology,
                  0);
          if (surf.equals(baseTruecased)) {
            surf =
                mined.truecase(
                    d.normLower,
                    tok.shape(),
                    tok.surface(),
                    capHere,
                    sentenceAllCaps,
                    strictMorphology,
                    tokCaseCtx);
          }
          addOrMergeDraft(
              ctxDrafts,
              tok,
              surf,
              d.normLower,
              d.sourceMask,
              d.parallelProb,
              d.parallelLogCount,
              d.suffixPrecision,
              d.editCost,
              d.diacriticDiffs);
        }
        drafts = ctxDrafts;
      }

      int rawParTotal = parEntry != null ? parEntry.totalCount() : 0;
      boolean rawNegCue = hasRawNegativeVerbCue(tok.lower());
      String nextLexForUnary = nextLexicalWordOrNull(tokens, i + 1);
      byte nextState = computeNextSyntacticState(tokens, i + 1, nextLexForUnary);
      List<SpanArc> arcsHere = outgoing[i];
      int count = 0;
      for (CandidateDraft d : drafts.values()) {
        if (count >= config.maxArcsPerStart && (d.sourceMask & NormV2Types.SRC_IDENTITY) == 0) {
          continue;
        }
        SpanArc arc = materializeArc(i, i + 1, tok, nextLexForUnary, d);
        arc.rawParallelTotal = rawParTotal;
        arc.rawNegVerbCue = rawNegCue;
        arc.nextLexState = nextState;
        arcsHere.add(arc);
        count++;
      }

      // (I) Multi-token 2:1 and 3:1 merges starting at i
      if (config.useSplitMergeArcs) {
        addMultiTokenMerges(tokens, i, capHere, sentenceAllCaps, tokCaseCtx, arcsHere);
      }
    }

    return new SentenceLattice(
        rawSentence,
        tokens,
        outgoing,
        sentenceAsciiRatio,
        sentenceOovRatio,
        overtSubjectPerson,
        startsCap,
        hasQuestionMark);
  }

  private void addMorphologicalRealizations(
      SpanToken tok,
      boolean capHere,
      boolean sentenceAllCaps,
      Map<String, CandidateDraft> drafts) {
    String low = tok.lower();
    if (low.length() < 2 || tok.containsDigit()) {
      return;
    }
    List<CachedMorphItem> items = morphRealizeCache.get(low);
    if (items == null) {
      items = computeMorphRealizations(low);
      if (morphRealizeCache.size() < 65_536) {
        morphRealizeCache.put(low, items);
      }
    }
    for (CachedMorphItem item : items) {
      String surf =
          item.fixedProperSurface() != null
              ? item.fixedProperSurface()
              : mined.truecase(
                  item.normLower(),
                  tok.shape(),
                  tok.surface(),
                  capHere,
                  sentenceAllCaps,
                  strictMorphology);
      addOrMergeDraft(
          drafts,
          tok,
          surf,
          item.normLower(),
          item.sourceMask(),
          0.0f,
          0.0f,
          item.suffixPrecision(),
          item.editCost(),
          item.diacriticDiffs());
    }
  }

  private List<CachedMorphItem> computeMorphRealizations(String low) {
    List<CachedMorphItem> out = new ArrayList<>(6);

    // 1. Exact-vowel informal morphology FIRST (e.g. "donuyodu" -> ONLY "donuyordu", NOT "dönüyordu"!)
    for (SingleAnalysis a : exactInformalMorphology.analyze(low)) {
      if (a.isUnknown() || a.isRuntime() || !a.containsInformalMorpheme()) {
        continue;
      }
      if (a.getDictionaryItem().primaryPos == PrimaryPos.Interjection
          && a.getDictionaryItem().hasAttribute(RootAttribute.Ext)) {
        continue;
      }
      Result res = informalConverter.convert(low, a);
      if (res != null && !stripsExplicitStemDiacritic(low, res.surface)) {
        String nl = NormV2Types.trLower(res.surface);
        out.add(
            new CachedMorphItem(
                null,
                nl,
                NormV2Types.SRC_INFORMAL_EXACT | NormV2Types.SRC_INFORMAL_MORPH,
                0.93f,
                computeWeightedEditCost(low, nl),
                countDiacriticDiffs(low, nl)));
      }
    }

    // 2. Diacritic-ignoring informal & formal morphology (sorted by CleanWordLm so high-frequency stems win)
    List<CachedMorphItem> asciiPool = new ArrayList<>(8);
    for (SingleAnalysis a : informalAsciiMorphology.analyze(low)) {
      if (a.isUnknown() || a.isRuntime()) {
        continue;
      }
      SecondaryPos sp = a.getDictionaryItem().secondaryPos;
      if (a.getDictionaryItem().primaryPos == PrimaryPos.Interjection
          && a.getDictionaryItem().hasAttribute(RootAttribute.Ext)) {
        continue;
      }
      if (a.containsInformalMorpheme()) {
        Result res = informalConverter.convert(low, a);
        if (res != null && !stripsExplicitStemDiacritic(low, res.surface)) {
          String nl = NormV2Types.trLower(res.surface);
          asciiPool.add(
              new CachedMorphItem(
                  null,
                  nl,
                  NormV2Types.SRC_INFORMAL_MORPH,
                  0.82f,
                  computeWeightedEditCost(low, nl),
                  countDiacriticDiffs(low, nl)));
        }
      } else if (sp != SecondaryPos.Abbreviation) {
        for (Result r :
            strictMorphology.getWordGenerator().generate(a.getDictionaryItem(), a.getMorphemes())) {
          if (TurkishAlphabet.INSTANCE.equalsIgnoreDiacritics(low, r.surface)
              && !stripsExplicitDiacritic(low, r.surface)) {
            String nl = NormV2Types.trLower(r.surface);
            if (!nl.equals(low) || sp == SecondaryPos.ProperNoun) {
              String fixedProp =
                  sp == SecondaryPos.ProperNoun ? formatProperOrAbbrTDK(a, nl) : null;
              String finalLow = fixedProp != null ? NormV2Types.trLower(fixedProp) : nl;
              int dDiffs = countDiacriticDiffs(low, finalLow);
              asciiPool.add(
                  new CachedMorphItem(
                      fixedProp,
                      finalLow,
                      NormV2Types.SRC_FORMAL_MORPH,
                      0.0f,
                      computeWeightedEditCost(low, finalLow),
                      dDiffs));
            }
          }
        }
      }
    }
    if (asciiPool.size() > 1 && mined.cleanLm != null) {
      asciiPool.sort(
          (x, y) ->
              Float.compare(
                  mined.cleanLm.unigramLogProb(y.normLower()),
                  mined.cleanLm.unigramLogProb(x.normLower())));
    }
    int emitted = 0;
    for (CachedMorphItem item : asciiPool) {
      out.add(item);
      if (++emitted >= 6) {
        break;
      }
    }
    return out.isEmpty() ? Collections.emptyList() : List.copyOf(out);
  }

  /**
   * Formats a Zemberek {@link SecondaryPos#ProperNoun} or {@link SecondaryPos#Abbreviation}
   * strictly according to TDK Yazim Kilavuzu rules:
   * <ul>
   *   <li>ALL-CAPS abbreviations ALWAYS take {@code '} before any non-empty suffix ({@code AKP'li},
   *       {@code RAM'di}, {@code TBMM'de}).
   *   <li>Proper nouns with {@link RootAttribute#NoQuote} ({@code Türkçe}, {@code Azerice}, {@code
   *       İngilizce}) or whose first attached suffix is derivational ({@code -lI}, {@code -lIk},
   *       {@code -cA}) or plural {@code -lAr} take NO apostrophe ({@code Azericeye}, {@code
   *       Türkçede}, {@code Ankaralıdan}, {@code Türklerin}).
   *   <li>Proper nouns with an immediate inflectional suffix take {@code '} ({@code Türkiye'de},
   *       {@code Ankara'dakiler}).
   * </ul>
   */
  static String formatProperOrAbbrTDK(SingleAnalysis a) {
    return formatProperOrAbbrTDK(a, null);
  }

  static String formatProperOrAbbrTDK(SingleAnalysis a, String generatedLowerOrNull) {
    DictionaryItem item = a.getDictionaryItem();
    Locale loc = item.hasAttribute(RootAttribute.LocaleEn) ? Locale.ENGLISH : Turkish.LOCALE;
    String ending = a.getEnding();
    if (generatedLowerOrNull != null && !ending.isEmpty()) {
      String lemmaLow = NormV2Types.trLower(item.normalizedLemma());
      int stemLen =
          generatedLowerOrNull.startsWith(lemmaLow)
              ? lemmaLow.length()
              : Math.min(generatedLowerOrNull.length(), a.getStem().length());
      if (stemLen < generatedLowerOrNull.length()) {
        ending = generatedLowerOrNull.substring(stemLen);
      }
    }
    if (item.secondaryPos == SecondaryPos.Abbreviation) {
      String upperLemma = item.normalizedLemma().toUpperCase(loc);
      return ending.isEmpty() ? upperLemma : upperLemma + "'" + ending;
    }
    if (item.secondaryPos == SecondaryPos.ProperNoun) {
      String titleLemma = NormV2Types.trCapitalize(item.normalizedLemma());
      if (ending.isEmpty()) {
        return titleLemma;
      }
      if (properNounTakesApostropheTDK(a)) {
        return titleLemma + "'" + ending;
      }
      if (generatedLowerOrNull != null) {
        return NormV2Types.trCapitalize(generatedLowerOrNull);
      }
      String stemSurf =
          item.attributes.contains(RootAttribute.NoQuote) ? item.normalizedLemma() : a.getStem();
      return NormV2Types.trCapitalize(stemSurf + ending);
    }
    return null;
  }

  static boolean properNounTakesApostropheTDK(SingleAnalysis a) {
    if (a.getEnding().isEmpty()) {
      return false;
    }
    if (a.getDictionaryItem().attributes.contains(RootAttribute.NoQuote)) {
      return false;
    }
    List<SingleAnalysis.MorphemeData> mdList = a.getMorphemeDataList();
    for (int i = 1; i < mdList.size(); i++) {
      SingleAnalysis.MorphemeData md = mdList.get(i);
      if (md.morpheme == TurkishMorphotactics.a3pl && !md.surface.isEmpty()) {
        return false;
      }
      if (md.morpheme.derivational && !md.surface.isEmpty()) {
        return false;
      }
      if (!md.surface.isEmpty()) {
        return true;
      }
    }
    return false;
  }

  private void addSuffixAndHaplologyCandidates(
      SpanToken tok,
      boolean capHere,
      boolean sentenceAllCaps,
      Map<String, CandidateDraft> drafts) {
    String low = tok.lower();
    if (low.length() < 4 || tok.containsDigit()) {
      return;
    }
    String lastKey = String.valueOf(low.charAt(low.length() - 1));
    List<SuffixRule> rules = mined.suffixRulesByLastChar.get(lastKey);
    if (rules == null) {
      return;
    }
    int added = 0;
    for (SuffixRule sr : rules) {
      if (low.length() <= sr.rawSuffix().length() + 1 || !low.endsWith(sr.rawSuffix())) {
        continue;
      }
      String stem = low.substring(0, low.length() - sr.rawSuffix().length());
      String realized = realizeValidWordOrNull(stem + sr.normSuffix());
      if (realized != null && !stripsExplicitStemDiacritic(low, realized)) {
        String surf =
            mined.truecase(
                realized, tok.shape(), tok.surface(), capHere, sentenceAllCaps, strictMorphology);
        addOrMergeDraft(
            drafts,
            tok,
            surf,
            realized,
            NormV2Types.SRC_SUFFIX_RULE,
            0.0f,
            (float) Math.log1p(sr.support()),
            sr.precision(),
            computeWeightedEditCost(low, realized),
            countDiacriticDiffs(low, realized));
        if (++added >= 4) {
          break;
        }
      }
    }
  }

  private void addProperAcronymAndNumeralCandidates(
      SpanToken tok,
      boolean capHere,
      boolean sentenceAllCaps,
      Map<String, CandidateDraft> drafts) {
    String low = tok.lower();
    int apIdx = low.indexOf('\'');
    if (apIdx > 0 && apIdx < low.length() - 1) {
      // Erroneous apostrophe cleanup: e.g. "azerice'ye"->"Azericeye", "altın'ın"->"altının", "Demacia'lı"->"Demacialı"
      String stem = low.substring(0, apIdx);
      String tail = low.substring(apIdx + 1);
      String joined = stem + tail;
      if (!isDigitsOrPunctOnly(stem)) {
        for (SingleAnalysis a : strictMorphology.analyze(joined)) {
          if (a.isUnknown() || a.isRuntime()) {
            continue;
          }
          if (a.getDictionaryItem().secondaryPos == SecondaryPos.ProperNoun
              && !properNounTakesApostropheTDK(a)
              && !mined.isKnownAcronymStem(stem)
              && !(tok.shape() == CasingShape.UPPER && !sentenceAllCaps)) {
            String fmt = formatProperOrAbbrTDK(a);
            if (fmt != null && fmt.indexOf('\'') < 0) {
              String fl = NormV2Types.trLower(fmt);
              addOrMergeDraft(
                  drafts,
                  tok,
                  fmt,
                  fl,
                  NormV2Types.SRC_APOSTROPHE_DROP | NormV2Types.SRC_FORMAL_MORPH,
                  0.0f,
                  0.0f,
                  0.92f,
                  0.15f,
                  countDiacriticDiffs(joined, fl));
            }
          }
        }
        if (hasRegularStrictAnalysis(joined) && !mined.isKnownAcronymStem(stem)) {
          ParallelCorpusMiner.CasingCounts cc = mined.casingLexicon.get(stem);
          boolean commonLower =
              cc != null && cc.lower >= 5 && cc.lower > (cc.title + cc.upper + cc.apostropheStem) * 2;
          if (commonLower || tok.shape() == CasingShape.LOWER) {
            String surf = capHere ? NormV2Types.trCapitalize(joined) : joined;
            addOrMergeDraft(
                drafts,
                tok,
                surf,
                joined,
                NormV2Types.SRC_APOSTROPHE_DROP | NormV2Types.SRC_FORMAL_MORPH,
                0.0f,
                0.0f,
                commonLower ? 0.85f : 0.55f,
                0.20f,
                0);
          }
        } else if (!mined.isKnownAcronymStem(stem) && startsWithNoApostropheDeriv(tail)) {
          String assimilatedTail = assimilateDerivationalTail(stem, tail);
          String candLow = stem + assimilatedTail;
          ParallelCorpusMiner.CasingCounts cc = mined.casingLexicon.get(stem);
          boolean commonLower =
              cc != null && cc.lower >= 4 && cc.lower > (cc.title + cc.upper + cc.apostropheStem) * 2;
          String surf =
              commonLower
                  ? (capHere ? NormV2Types.trCapitalize(candLow) : candLow)
                  : (tok.shape() == CasingShape.LOWER && !mined.isKnownProperOrAcronymStem(stem)
                      ? (capHere ? NormV2Types.trCapitalize(candLow) : candLow)
                      : NormV2Types.trCapitalize(candLow));
          addOrMergeDraft(
              drafts,
              tok,
              surf,
              candLow,
              NormV2Types.SRC_APOSTROPHE_DROP,
              0.0f,
              0.0f,
              0.86f,
              0.18f,
              countDiacriticDiffs(joined, candLow));
        }
      }
      return;
    }
    if (apIdx >= 0) {
      return;
    }

    // 1. Digits + Letters: e.g. "16gb"->"16 GB", "45kg"->"45 kg", "2024te"->"2024'te", "tv8e"->"TV8'e", "5si"->"5s'i"
    if (tok.containsDigit()) {
      int dEnd = 0;
      while (dEnd < low.length() && Character.isDigit(low.charAt(dEnd))) {
        dEnd++;
      }
      if (dEnd > 0 && dEnd < low.length()) {
        String numPart = low.substring(0, dEnd);
        String rest = low.substring(dEnd);
        if (rest.startsWith(".") && rest.length() > 1) {
          String ordSuf = rest.substring(1);
          String ordJoined = buildOrdinalInflected(numPart, ordSuf);
          if (ordJoined != null) {
            addOrMergeDraft(
                drafts,
                tok,
                ordJoined,
                ordJoined,
                NormV2Types.SRC_OOV_APOSTROPHE,
                0.0f,
                0.0f,
                0.90f,
                0.2f,
                0);
          }
        } else if (COMMON_UNITS.contains(rest)) {
          String unitNorm =
              UPPERCASE_UNITS.contains(rest)
                  ? NormV2Types.trUpper(rest)
                  : (rest.equals("mah") ? "mAh" : rest);
          String splitUnit = numPart + " " + unitNorm;
          addOrMergeDraft(
              drafts,
              tok,
              splitUnit,
              NormV2Types.trLower(splitUnit),
              NormV2Types.SRC_CLITIC_SPLIT,
              0.0f,
              0.0f,
              0.90f,
              0.2f,
              0);
        } else if (DETACHED_NUM_SUFFIXES.contains(rest)) {
          String joined = numPart + "'" + rest;
          addOrMergeDraft(
              drafts,
              tok,
              joined,
              joined,
              NormV2Types.SRC_OOV_APOSTROPHE,
              0.0f,
              0.0f,
              0.88f,
              0.2f,
              0);
        } else if (rest.length() >= 2
            && (rest.charAt(0) == 's'
                || rest.charAt(0) == 'g'
                || rest.charAt(0) == 'k'
                || rest.charAt(0) == 'd')
            && DETACHED_WORD_APOSTROPHE_SUFFIXES.contains(rest.substring(1))) {
          char unitCh = rest.charAt(0);
          String modelStem =
              numPart + (unitCh == 's' ? "s" : String.valueOf(Character.toUpperCase(unitCh)));
          String suf = rest.substring(1);
          String joinedSurf = modelStem + "'" + suf;
          String joinedLow = NormV2Types.trLower(joinedSurf);
          addOrMergeDraft(
              drafts,
              tok,
              joinedSurf,
              joinedLow,
              NormV2Types.SRC_OOV_APOSTROPHE,
              0.0f,
              0.0f,
              0.86f,
              0.2f,
              0);
        } else if (rest.length() >= 2) {
          String realWord = realizeValidWordOrNull(rest);
          if (realWord != null) {
            String splitNumWord = numPart + " " + realWord;
            addOrMergeDraft(
                drafts,
                tok,
                splitNumWord,
                splitNumWord,
                NormV2Types.SRC_CLITIC_SPLIT,
                0.0f,
                0.0f,
                0.85f,
                0.2f,
                countDiacriticDiffs(rest, realWord));
          }
        }
      } else if (dEnd == 0) {
        int lastDigit = -1;
        for (int k = 0; k < low.length(); k++) {
          if (Character.isDigit(low.charAt(k))) {
            lastDigit = k;
          }
        }
        if (lastDigit >= 1 && lastDigit <= 5 && lastDigit < low.length() - 1) {
          String stem = low.substring(0, lastDigit + 1);
          String suf = low.substring(lastDigit + 1);
          if (DETACHED_NUM_SUFFIXES.contains(suf)) {
            String joinedSurf = NormV2Types.trUpper(stem) + "'" + suf;
            String joinedLow = stem + "'" + suf;
            addOrMergeDraft(
                drafts,
                tok,
                joinedSurf,
                joinedLow,
                NormV2Types.SRC_OOV_APOSTROPHE,
                0.0f,
                0.0f,
                0.86f,
                0.2f,
                0);
          }
        }
      }
      return;
    }

    // 2. Known ProperNoun / Abbreviation in Zemberek strict morphology (TDK-compliant apostrophe)
    for (SingleAnalysis a : strictMorphology.analyze(low)) {
      if (a.isUnknown() || a.isRuntime() || a.getEnding().isEmpty()) {
        continue;
      }
      SecondaryPos sp = a.getDictionaryItem().secondaryPos;
      if (sp == SecondaryPos.ProperNoun || sp == SecondaryPos.Abbreviation) {
        String formatted = formatProperOrAbbrTDK(a);
        if (formatted != null && !formatted.equals(tok.surface())) {
          String fl = NormV2Types.trLower(formatted);
          addOrMergeDraft(
              drafts,
              tok,
              formatted,
              fl,
              NormV2Types.SRC_OOV_APOSTROPHE,
              0.0f,
              0.0f,
              0.85f,
              0.15f,
              countDiacriticDiffs(low, fl));
        }
      }
    }

    // 3. OOV Brand / Acronym + Case/Derivational/Copula Suffix ("ramdi"->"RAM'di", "akpli"->"AKP'li", "cekudayız"->"Ceku'dayız")
    if (low.length() >= 4 && low.length() <= 20) {
      boolean regularWord = hasRegularStrictAnalysis(low);
      for (String suf : OOV_PROPER_INFLECT_SUFS) {
        if (low.length() - suf.length() < 2) {
          continue;
        }
        if (!low.endsWith(suf)) {
          continue;
        }
        String stemLow = low.substring(0, low.length() - suf.length());
        boolean acronymOnlySuf =
            suf.startsWith("li")
                || suf.startsWith("lı")
                || suf.startsWith("lu")
                || suf.startsWith("lü")
                || suf.equals("ler")
                || suf.equals("lar");
        boolean isAcronym = mined.isKnownAcronymStem(stemLow);
        if (acronymOnlySuf && !isAcronym) {
          continue;
        }
        if (tok.shape() == CasingShape.LOWER && CURATED_CHAT_SHORTHANDS.containsKey(low)) {
          continue; // Prevent nonsensical "TSK'ler" on lowercase chat shorthand "tskler"
        }
        if (regularWord && !isAcronym) {
          continue;
        }
        boolean knownProper = isAcronym || mined.isKnownProperOrAcronymStem(stemLow);
        boolean midSentTitleOov =
            !knownProper
                && !capHere
                && tok.shape() == CasingShape.TITLE
                && stemLow.length() >= 3
                && !hasRegularStrictAnalysis(stemLow);
        if (knownProper || midSentTitleOov) {
          String candLow = stemLow + "'" + suf;
          String candSurf =
              isAcronym
                  ? NormV2Types.trUpper(stemLow) + "'" + suf
                  : mined.truecase(
                      candLow,
                      tok.shape(),
                      tok.surface(),
                      capHere,
                      sentenceAllCaps,
                      strictMorphology);
          addOrMergeDraft(
              drafts,
              tok,
              candSurf,
              candLow,
              NormV2Types.SRC_OOV_APOSTROPHE,
              0.0f,
              0.0f,
              isAcronym ? 0.86f : (knownProper ? 0.80f : 0.72f),
              0.22f,
              0);
          break;
        }
      }
    }
  }

  private static boolean startsWithNoApostropheDeriv(String tail) {
    for (String p : TDK_NO_APOSTROPHE_DERIV_PREFIXES) {
      if (tail.startsWith(p)) {
        return true;
      }
    }
    return false;
  }

  private static String assimilateDerivationalTail(String stem, String tail) {
    if (stem.isEmpty() || tail.isEmpty()) {
      return tail;
    }
    char lastC = stem.charAt(stem.length() - 1);
    boolean voiceless = "fstkçşhp".indexOf(lastC) >= 0;
    String out = tail;
    if (out.startsWith("cug")) {
      out = (voiceless ? "çuğ" : "cuğ") + out.substring(3);
    } else if (out.startsWith("cüg")) {
      out = (voiceless ? "çüğ" : "cüğ") + out.substring(3);
    } else if (out.startsWith("cig")) {
      out = (voiceless ? "çiğ" : "ciğ") + out.substring(3);
    } else if (out.startsWith("cıg")) {
      out = (voiceless ? "çığ" : "cığ") + out.substring(3);
    } else if (voiceless && out.startsWith("c")) {
      out = "ç" + out.substring(1);
    }
    return out;
  }

  private void addElongationCandidates(
      SpanToken tok,
      boolean capHere,
      boolean sentenceAllCaps,
      char expectedPrevQ,
      boolean strictKnown,
      Map<String, CandidateDraft> drafts) {
    String low = tok.lower();
    int maxRun = maxRepeatedLetterRun(low);
    if (maxRun < 3) {
      if (strictKnown
          || low.length() <= 3
          || isStrictProperOrAbbrOnly(low)
          || mined.isKnownProperOrAcronymStem(low)
          || (!sentenceAllCaps
              && (tok.shape() == CasingShape.UPPER
                  || (!capHere && tok.shape() == CasingShape.TITLE)))) {
        return;
      }
    }
    boolean mStemQuestionBoundary = maxRun < 3 && isMStemPlusQuestionClitic(tok);
    String sq1 = mStemQuestionBoundary ? low : squeezeRuns(low, 1);
    String sq2 = squeezeRuns(low, 2);
    String sqTrail = squeezeTrailingRun(low);
    String sqVow = squeezeVowelRuns(low, 1);
    List<String> uniqueSq = new ArrayList<>(4);
    for (String candSq : new String[] {sq1, sq2, sqTrail, sqVow}) {
      if (candSq.length() >= 2 && !candSq.equals(low) && !uniqueSq.contains(candSq)) {
        uniqueSq.add(candSq);
      }
    }
    for (String sq : uniqueSq) {
      RewriteEntry re = config.useParallelTables ? mined.parallel1ToN.get(sq) : null;
      if (re != null) {
        for (TargetCount tc : re.targets()) {
          String surf =
              mined.truecase(
                  tc.normLower(),
                  tok.shape(),
                  tok.surface(),
                  capHere,
                  sentenceAllCaps,
                  strictMorphology);
          addOrMergeDraft(
              drafts,
              tok,
              surf,
              tc.normLower(),
              NormV2Types.SRC_ELONGATION | NormV2Types.SRC_PARALLEL_1TO1,
              re.probOf(tc.normLower()),
              (float) Math.log1p(tc.count()),
              0.0f,
              0.35f,
              countDiacriticDiffs(sq, tc.normLower()));
        }
      }
      String realized = realizeValidWordOrNull(sq);
      if (realized != null) {
        String surf =
            mined.truecase(
                realized, tok.shape(), tok.surface(), capHere, sentenceAllCaps, strictMorphology);
        boolean disharmonicSqQuestion =
            expectedPrevQ != 0
                && isStandaloneQuestionClitic(realized)
                && realized.length() >= 2
                && realized.charAt(1) != expectedPrevQ;
        addOrMergeDraft(
            drafts,
            tok,
            surf,
            realized,
            NormV2Types.SRC_ELONGATION,
            0.0f,
            0.0f,
            disharmonicSqQuestion ? 0.25f : 0.80f,
            0.35f,
            countDiacriticDiffs(sq, realized));
      }
      if (!(!sentenceAllCaps && !capHere && tok.shape() == CasingShape.TITLE)) {
        addQuestionCliticDiacriticTwins(
            tok, sq, capHere, sentenceAllCaps, expectedPrevQ, 0.0f, 0.0f, true, drafts);
      }
    }
  }

  public static boolean isStandaloneQuestionClitic(String wordLower) {
    if (wordLower == null || wordLower.length() < 2) {
      return false;
    }
    if (QUESTION_TAILS.contains(wordLower)) {
      return true;
    }
    String asc = NormV2Types.toAscii(wordLower);
    return asc.equals("miymis") || asc.equals("muymus");
  }

  private static boolean isSameRoundingQuestionVowel(char v1, char v2) {
    return ((v1 == 'i' || v1 == 'ı') && (v2 == 'i' || v2 == 'ı'))
        || ((v1 == 'u' || v1 == 'ü') && (v2 == 'u' || v2 == 'ü'));
  }

  private char computeImmediatePrevQuestionVowel(
      List<SpanToken> tokens, String[] deascByTok, int idx, boolean sentenceAllCaps) {
    if (idx <= 0) {
      return 0;
    }
    SpanToken cur = tokens.get(idx);
    if (!sentenceAllCaps && cur.shape() == CasingShape.TITLE) {
      return 0;
    }
    if (cur.lower().equals("mi") && idx + 1 < tokens.size()) {
      SpanToken nt = tokens.get(idx + 1);
      if (nt.containsDigit() || isXiaomiMiProductTail(nt.lower())) {
        return 0;
      }
    }
    SpanToken prev = tokens.get(idx - 1);
    if (prev.isImmutable() || (!sentenceAllCaps && prev.shape() == CasingShape.UPPER)) {
      return 0;
    }
    if (prev.containsDigit() && prev.lower().indexOf('\'') < 0 && prev.surface().indexOf('’') < 0) {
      return 0;
    }
    String prevQuery = prev.surface().indexOf('’') >= 0 ? prev.lower().replace('’', '\'') : prev.lower();
    if (deascByTok != null && deascByTok[idx - 1] != null && !deascByTok[idx - 1].equals(prevQuery)) {
      char deascExp = expectedTurkishQuestionVowel(deascByTok[idx - 1]);
      if (deascExp != 0) {
        return deascExp;
      }
    }
    // Never flip an explicit Turkish dotless 'ı' ("mı"/"mısın") using an un-deasciified pure-ASCII prev token
    if (cur.lower().indexOf('ı') >= 0
        && !hasExplicitTurkishDiacritics(prevQuery)
        && hasAsciiAmbiguousChars(prevQuery)) {
      return 0;
    }
    return expectedTurkishQuestionVowel(prevQuery);
  }

  private static boolean isXiaomiMiProductTail(String w) {
    return w.equals("band")
        || w.equals("box")
        || w.equals("note")
        || w.equals("mix")
        || w.equals("stick")
        || w.equals("pad")
        || w.equals("tv");
  }

  private void addQuestionCliticDiacriticTwins(
      SpanToken tok,
      String candidateBaseLower,
      boolean capHere,
      boolean sentenceAllCaps,
      char expectedPrevQ,
      float rawKeepProb,
      float rawKeepLogCnt,
      boolean fromElongation,
      Map<String, CandidateDraft> drafts) {
    if (!isStandaloneQuestionClitic(candidateBaseLower)) {
      return;
    }
    char curV = candidateBaseLower.length() >= 2 ? candidateBaseLower.charAt(1) : 0;
    char[] sameRoundingVowels;
    if (curV == 'i' || curV == 'ı') {
      sameRoundingVowels = new char[] {'i', 'ı'};
    } else if (curV == 'u' || curV == 'ü') {
      sameRoundingVowels = new char[] {'u', 'ü'};
    } else {
      return;
    }
    for (char hv : sameRoundingVowels) {
      String variant = harmonizeQuestionClitic(candidateBaseLower, hv);
      if (!fromElongation && variant.equals(tok.lower())) {
        continue;
      }
      String surf =
          mined.truecase(
              variant, tok.shape(), tok.surface(), capHere, sentenceAllCaps, strictMorphology);
      int rawDiffs = countDiacriticDiffs(candidateBaseLower, variant);
      // All vowels in a Turkish question clitic ("mısınız") harmonize as 1 morphophonological choice
      int effectiveDiffs = Math.min(1, rawDiffs);
      boolean matchesPrevHarmony = expectedPrevQ != 0 && hv == expectedPrevQ;
      int srcMask =
          NormV2Types.SRC_FORMAL_MORPH
              | (rawDiffs > 0 ? NormV2Types.SRC_DEASCIIFIER : 0)
              | (fromElongation ? NormV2Types.SRC_ELONGATION : 0)
              | (matchesPrevHarmony && rawKeepProb > 0.0f ? NormV2Types.SRC_PARALLEL_1TO1 : 0);
      float parProb =
          matchesPrevHarmony && !fromElongation ? Math.max(0.85f, rawKeepProb) : 0.0f;
      float parLogCnt = matchesPrevHarmony && !fromElongation ? rawKeepLogCnt : 0.0f;
      float sufPrec =
          matchesPrevHarmony
              ? 0.92f
              : (fromElongation && expectedPrevQ == 0 ? 0.78f : 0.0f);
      float cost = (fromElongation ? 0.32f : 0.0f) + effectiveDiffs * 0.08f;
      addOrMergeDraft(
          drafts, tok, surf, variant, srcMask, parProb, parLogCnt, sufPrec, cost, effectiveDiffs);
    }
  }

  /**
   * Detects {@code m}-final nominal/adjectival stems glued to a {@code m}-initial question clitic
   * ({@code "intikammı"}, {@code "filmmi"}, {@code "tamammı"}, {@code "ayrımmı"}) so {@code mm ->
   * m} squeeze does not corrupt them into 3sg possessives ({@code "intikamı"}, {@code "filmi"}).
   */
  private boolean isMStemPlusQuestionClitic(SpanToken tok) {
    String low = tok.lower();
    for (String qt : QUESTION_TAILS) {
      if (low.length() >= qt.length() + 3
          && (low.endsWith(qt) || tok.ascii().endsWith(NormV2Types.toAscii(qt)))) {
        String rawHead = low.substring(0, low.length() - qt.length());
        if (rawHead.endsWith("m")
            && (hasRegularStrictAnalysis(rawHead) || realizeValidWordOrNull(rawHead) != null)) {
          return true;
        }
      }
    }
    return false;
  }

  private void addAmbiguityPreservingSplits(
      SpanToken tok,
      boolean capHere,
      boolean sentenceAllCaps,
      boolean strictKnown,
      Map<String, CandidateDraft> drafts) {
    String low = tok.lower();
    int len = low.length();
    if (len < 3) {
      return;
    }

    // 0a. Short 3-char pronoun clitics ("oda" -> "o da", "omu" -> "o mu")
    if (len == 3 && !tok.containsDigit()) {
      if (low.equals("oda")) {
        String splitSurf =
            mined.truecase(
                "o da", tok.shape(), tok.surface(), capHere, sentenceAllCaps, strictMorphology);
        addOrMergeDraft(
            drafts, tok, splitSurf, "o da", NormV2Types.SRC_CLITIC_SPLIT, 0.0f, 0.0f, 0.0f, 0.20f, 0);
      } else if (low.equals("omu")) {
        String splitSurf =
            mined.truecase(
                "o mu", tok.shape(), tok.surface(), capHere, sentenceAllCaps, strictMorphology);
        addOrMergeDraft(
            drafts, tok, splitSurf, "o mu", NormV2Types.SRC_CLITIC_SPLIT, 0.0f, 0.0f, 0.88f, 0.18f, 0);
      }
      return;
    }

    // 0b. Apostrophized ProperNoun / Numeral clitic errors ("Ali'de geldi" -> "Ali de", "Türkiye'dede" -> "Türkiye'de de", "Ali'mi" -> "Ali mi")
    int ap = low.indexOf('\'');
    if (ap > 0 && ap < len - 1) {
      String stemLow = low.substring(0, ap);
      String tailLow = low.substring(ap + 1);
      if (tailLow.equals("de") || tailLow.equals("da") || tailLow.equals("te") || tailLow.equals("ta")) {
        if (!isAllDigits(stemLow)) {
          String clitic = isFrontVowel(lastTurkishVowel(stemLow)) ? "de" : "da";
          String splitLow = stemLow + " " + clitic;
          String splitSurf =
              mined.truecase(
                  splitLow, tok.shape(), tok.surface(), capHere, sentenceAllCaps, strictMorphology);
          addOrMergeDraft(
              drafts,
              tok,
              splitSurf,
              splitLow,
              NormV2Types.SRC_CLITIC_SPLIT | NormV2Types.SRC_APOSTROPHE_DROP,
              0.0f,
              0.0f,
              0.0f,
              0.24f,
              0);
        }
      } else if (tailLow.length() == 4
          && (tailLow.endsWith("de") || tailLow.endsWith("da"))
          && (tailLow.startsWith("de")
              || tailLow.startsWith("da")
              || tailLow.startsWith("te")
              || tailLow.startsWith("ta"))) {
        String locPart = tailLow.substring(0, 2);
        String clitic = tailLow.substring(2);
        String splitLow = stemLow + "'" + locPart + " " + clitic;
        String splitSurf =
            isAllDigits(stemLow)
                ? splitLow
                : mined.truecase(
                    splitLow, tok.shape(), tok.surface(), capHere, sentenceAllCaps, strictMorphology);
        addOrMergeDraft(
            drafts,
            tok,
            splitSurf,
            splitLow,
            NormV2Types.SRC_CLITIC_SPLIT,
            0.0f,
            0.0f,
            0.90f,
            0.18f,
            0);
      } else if (QUESTION_TAILS.contains(tailLow) && !isAllDigits(stemLow)) {
        String qt = harmonizeQuestionClitic(tailLow, lastTurkishVowel(stemLow));
        String splitLow = stemLow + " " + qt;
        String splitSurf =
            mined.truecase(
                splitLow, tok.shape(), tok.surface(), capHere, sentenceAllCaps, strictMorphology);
        addOrMergeDraft(
            drafts,
            tok,
            splitSurf,
            splitLow,
            NormV2Types.SRC_CLITIC_SPLIT | NormV2Types.SRC_APOSTROPHE_DROP,
            0.0f,
            0.0f,
            0.90f,
            0.18f,
            0);
      }
      return;
    }

    if (tok.containsDigit() || ap >= 0) {
      return;
    }

    boolean hasValidSingleWord = strictKnown || hasValidSingleWordDraft(drafts);

    // 1. Separate clitic de/da ("bende"->"ben de", "bılıyorda"->"biliyor da", "bizleride"->"bizleri de", "millayida"->"Milla'yı da")
    if ((low.endsWith("de") || low.endsWith("da") || low.endsWith("te") || low.endsWith("ta"))
        && !NON_SPLITTABLE_LOC_ADVERBS.contains(low)) {
      boolean unvoicedTail = low.endsWith("te") || low.endsWith("ta");
      String rawHead = low.substring(0, len - 2);
      if (rawHead.length() >= 2) {
        String queryHead = tok.hasUpperAsciiAmbiguousI() ? rawHead.replace('ı', 'i') : rawHead;
        for (SplitHead sh : resolveSplitHeads(queryHead, rawHead, tok, capHere, sentenceAllCaps)) {
          String headReal = sh.normLower();
          char lv = lastTurkishVowel(headReal);
          String clitic = isFrontVowel(lv) ? "de" : "da";
          String splitLow = headReal + " " + clitic;
          String splitSurf =
              sh.customSurf() != null
                  ? sh.customSurf()
                      + " "
                      + (sentenceAllCaps ? NormV2Types.trUpper(clitic) : clitic)
                  : mined.truecase(
                      splitLow,
                      tok.shape(),
                      tok.surface(),
                      capHere,
                      sentenceAllCaps,
                      strictMorphology);
          MorphSummary headMs = summarizeMorph(headReal);
          int dDiffs = countDiacriticDiffs(rawHead, headReal);
          int srcMask =
              NormV2Types.SRC_CLITIC_SPLIT
                  | (dDiffs > 0 ? NormV2Types.SRC_DEASCIIFIER : 0)
                  | (sh.isProper() ? NormV2Types.SRC_OOV_APOSTROPHE : 0);
          float splitPrec =
              headMs.syntaxClass == NormV2Types.HEAD_SYNTAX_FINITE_OR_CONVERB
                  ? 0.92f
                  : ((sh.isProper() || !hasValidSingleWord) ? 0.80f : 0.0f);
          float splitCost = unvoicedTail ? 0.26f : 0.22f;
          addOrMergeDraft(
              drafts, tok, splitSurf, splitLow, srcMask, 0.0f, 0.0f, splitPrec, splitCost, dDiffs);
        }
      }
    }

    // 2. Question clitic splits ("olurmu"->"olur mu", "hostmu"->"host mu", "güçlümüydük"->"güçlü müydük")
    for (String qt : QUESTION_TAILS) {
      if (len >= qt.length() + 2
          && (low.endsWith(qt) || tok.ascii().endsWith(NormV2Types.toAscii(qt)))) {
        String rawHead = low.substring(0, len - qt.length());
        String queryHead = tok.hasUpperAsciiAmbiguousI() ? rawHead.replace('ı', 'i') : rawHead;
        List<SplitHead> qHeads =
            resolveSplitHeads(queryHead, rawHead, tok, capHere, sentenceAllCaps);
        if (!qHeads.isEmpty()) {
          boolean emittedAny = false;
          for (SplitHead sh : qHeads) {
            String headReal = sh.normLower();
            // Bare 2sg imperative verb stems ("sev miyim", "gel mi") cannot take question clitics in Turkish;
            // also block 1st/2nd-person question copulas on "al" ("almiyim" = "almayayım", not archaic red "al mıyım").
            if (!sh.isProper()
                && (isBareImperativeVerbOnly(headReal)
                    || (qt.length() >= 5 && headReal.equals("al")))) {
              continue;
            }
            char dictHv = expectedTurkishQuestionVowel(headReal);
            String harmonizedQt =
                harmonizeQuestionClitic(qt, dictHv != 0 ? dictHv : lastTurkishVowel(headReal));
            String splitLow = headReal + " " + harmonizedQt;
            String splitSurf =
                sh.customSurf() != null
                    ? sh.customSurf()
                        + " "
                        + (sentenceAllCaps ? NormV2Types.trUpper(harmonizedQt) : harmonizedQt)
                    : mined.truecase(
                        splitLow,
                        tok.shape(),
                        tok.surface(),
                        capHere,
                        sentenceAllCaps,
                        strictMorphology);
            int dDiffs = countDiacriticDiffs(rawHead, headReal);
            int srcMask =
                NormV2Types.SRC_CLITIC_SPLIT
                    | (dDiffs > 0 ? NormV2Types.SRC_DEASCIIFIER : 0)
                    | (sh.isProper() ? NormV2Types.SRC_OOV_APOSTROPHE : 0);
            addOrMergeDraft(
                drafts, tok, splitSurf, splitLow, srcMask, 0.0f, 0.0f, 0.88f, 0.18f, dDiffs);
            emittedAny = true;
          }
          if (emittedAny) {
            break;
          }
        }
      }
    }

    // 3. Conjunction -ki split (outside SOMBAHÇEM and locative -daki/-deki)
    if (len >= 4
        && (low.endsWith("ki") || low.endsWith("kı"))
        && !LEXICAL_KI_WORDS.contains(low)
        && !low.endsWith("daki")
        && !low.endsWith("deki")
        && !low.endsWith("taki")
        && !low.endsWith("teki")) {
      String rawHead = low.substring(0, len - 2);
      String queryHead = tok.hasUpperAsciiAmbiguousI() ? rawHead.replace('ı', 'i') : rawHead;
      for (SplitHead sh : resolveSplitHeads(queryHead, rawHead, tok, capHere, sentenceAllCaps)) {
        String headReal = sh.normLower();
        if (!LEXICAL_KI_WORDS.contains(headReal + "ki")) {
          String splitLow = headReal + " ki";
          String splitSurf =
              sh.customSurf() != null
                  ? sh.customSurf() + (sentenceAllCaps ? " Kİ" : " ki")
                  : mined.truecase(
                      splitLow,
                      tok.shape(),
                      tok.surface(),
                      capHere,
                      sentenceAllCaps,
                      strictMorphology);
          int dDiffs = countDiacriticDiffs(rawHead, headReal);
          int srcMask =
              NormV2Types.SRC_CLITIC_SPLIT | (dDiffs > 0 ? NormV2Types.SRC_DEASCIIFIER : 0);
          addOrMergeDraft(
              drafts, tok, splitSurf, splitLow, srcMask, 0.0f, 0.0f, 0.82f, 0.22f, dDiffs);
        }
      }
    }

    // 4a. Productive "nap*" -> "ne yap*" and "nol*" -> "ne ol*" verb contractions ("napıyorlar"->"ne yapıyorlar", "noluyo"->"ne oluyor")
    if (len >= 5 && !strictKnown && (low.startsWith("nap") || low.startsWith("nol"))) {
      String verbBase = low.startsWith("nap") ? "yap" : "ol";
      String tail = low.substring(3);
      String verbReal = realizeValidWordOrNull(verbBase + tail);
      if (verbReal != null && verbReal.startsWith(verbBase)) {
        String splitLow = "ne " + verbReal;
        String splitSurf =
            mined.truecase(
                splitLow, tok.shape(), tok.surface(), capHere, sentenceAllCaps, strictMorphology);
        addOrMergeDraft(
            drafts,
            tok,
            splitSurf,
            splitLow,
            NormV2Types.SRC_CLITIC_SPLIT | NormV2Types.SRC_INFORMAL_MORPH,
            0.0f,
            0.0f,
            0.92f,
            0.22f,
            countDiacriticDiffs(verbBase + tail, verbReal));
      }
    }

    // 4b. Clean-corpus open-compound split (birşey -> bir şey, haftasonunda -> hafta sonunda, koltukaltlarından -> koltuk altlarından)
    if (len >= 5 && len <= 22) {
      int closedFreq = mined.cleanClosedWords.getOrDefault(low, 0);
      for (int cut = 2; cut <= len - 2; cut++) {
        String p1 = low.substring(0, cut);
        String p2 = low.substring(cut);
        Integer bgCnt = mined.cleanOpenBigrams.get(p1 + " " + p2);
        if (bgCnt != null
            && bgCnt >= 10
            && (!strictKnown || bgCnt >= 80 || bgCnt > closedFreq * 3)) {
          String splitLow = p1 + " " + p2;
          String splitSurf =
              mined.truecase(
                  splitLow, tok.shape(), tok.surface(), capHere, sentenceAllCaps, strictMorphology);
          addOrMergeDraft(
              drafts,
              tok,
              splitSurf,
              splitLow,
              NormV2Types.SRC_CLITIC_SPLIT,
              0.0f,
              (float) Math.log1p(bgCnt),
              0.80f,
              0.25f,
              0);
        }
      }
    }

    // 5. Missing-space & deasciified OOV open-compound / glued words ("haftasonunda"->"hafta sonunda", "hayalkirikligina"->"hayal kırıklığına")
    if (!strictKnown && len >= 7 && len <= 24 && !hasLowCostMorphDraft(drafts)) {
      String bestSplitLow = null;
      int bestDiacDiffs = 0;
      float bestBigram = -100.0f;
      String queryFull = tok.hasUpperAsciiAmbiguousI() ? low.replace('ı', 'i') : low;
      for (int cut = 3; cut <= len - 3; cut++) {
        String raw1 = queryFull.substring(0, cut);
        String raw2 = queryFull.substring(cut);
        String w1 = null;
        if (hasRegularStrictAnalysis(raw1)
            || (mined.cleanLm != null && mined.cleanLm.unigramCount(raw1) >= 20)) {
          w1 = raw1;
        } else if (cut <= 9 && hasAsciiAmbiguousChars(raw1)) {
          String r1 = realizeValidWordOrNull(raw1);
          if (r1 != null
              && !stripsExplicitStemDiacritic(low.substring(0, cut), r1)
              && mined.cleanLm != null
              && mined.cleanLm.unigramCount(r1) >= 40) {
            w1 = r1;
          }
        }
        if (w1 == null) {
          continue;
        }
        String w2 = null;
        if (hasRegularStrictAnalysis(raw2)
            || (mined.cleanLm != null && mined.cleanLm.unigramCount(raw2) >= 20)) {
          w2 = raw2;
        } else if (mined.cleanLm != null
            && mined.cleanLm.unigramCount(w1) >= 40
            && hasAsciiAmbiguousChars(raw2)) {
          String r2 = realizeValidWordOrNull(raw2);
          if (r2 != null && !stripsExplicitStemDiacritic(low.substring(cut), r2)) {
            w2 = r2;
          }
        }
        if (w2 != null && mined.cleanLm != null) {
          float bg = mined.cleanLm.bigramLogProb(w1, w2);
          if (bg > bestBigram) {
            bestBigram = bg;
            bestSplitLow = w1 + " " + w2;
            bestDiacDiffs =
                countDiacriticDiffs(low.substring(0, cut), w1)
                    + countDiacriticDiffs(low.substring(cut), w2);
          }
        }
      }
      if (bestSplitLow != null && bestBigram > -11.5f) {
        String splitSurf =
            mined.truecase(
                bestSplitLow,
                tok.shape(),
                tok.surface(),
                capHere,
                sentenceAllCaps,
                strictMorphology);
        int srcMask =
            NormV2Types.SRC_CLITIC_SPLIT | (bestDiacDiffs > 0 ? NormV2Types.SRC_DEASCIIFIER : 0);
        addOrMergeDraft(
            drafts,
            tok,
            splitSurf,
            bestSplitLow,
            srcMask,
            0.0f,
            0.0f,
            bestBigram > -6.5f ? 0.84f : 0.72f,
            0.28f,
            bestDiacDiffs);
      }
    }
  }

  private void addGuardedSpellCandidates(
      SpanToken tok,
      boolean capHere,
      boolean sentenceAllCaps,
      boolean protectMidTitle,
      Map<String, CandidateDraft> drafts) {
    List<ScoredCandidate> cands = spellChecker.suggestScored(tok.lower());
    boolean rawNegCue = hasRawNegativeVerbCue(tok.lower());
    boolean mStemQuestion = isMStemPlusQuestionClitic(tok);
    String squeezed1 = mStemQuestion ? squeezeRuns(tok.lower(), 1) : null;
    int added = 0;
    for (ScoredCandidate sc : cands) {
      String nl = NormV2Types.trLower(sc.surface);
      if (nl.equals(tok.lower())) {
        continue;
      }
      if (squeezed1 != null && nl.equals(squeezed1)) {
        continue;
      }
      if (protectMidTitle && sc.editCost > 0.35f) {
        continue;
      }
      if (!hasRegularStrictAnalysis(nl)) {
        continue;
      }
      if (stripsExplicitDiacritic(tok.lower(), nl) && sc.editCost > 0.65f) {
        continue;
      }
      float effEditCost = sc.editCost;
      if (rawNegCue && summarizeMorph(nl).verbPolarity >= 0) {
        // Penalize deleting/mutating an explicit negative verb suffix into a non-negative form ("çıkmarlar"->"çıkarlar")
        effEditCost += 0.55f;
      }
      String surf =
          mined.truecase(
              nl, tok.shape(), tok.surface(), capHere, sentenceAllCaps, strictMorphology);
      addOrMergeDraft(
          drafts,
          tok,
          surf,
          nl,
          NormV2Types.SRC_SPELLCHECK,
          0.0f,
          0.0f,
          0.0f,
          effEditCost,
          sc.diacriticDiffs);
      if (++added >= 5) {
        break;
      }
    }
  }

  private void addMultiTokenMerges(
      List<SpanToken> tokens,
      int i,
      boolean capHere,
      boolean sentenceAllCaps,
      int tokCaseCtx,
      List<SpanArc> outArcs) {
    int n = tokens.size();
    SpanToken t1 = tokens.get(i);
    int noisyOnlyCtx = tokCaseCtx & ParallelCorpusMiner.CTX_SENT_TITLE_NOISY;

    // 3-token merges
    if (i + 2 < n) {
      SpanToken t2 = tokens.get(i + 1);
      SpanToken t3 = tokens.get(i + 2);
      if (config.useParallelTables) {
        String k3 = t1.lower() + "\t" + t2.lower() + "\t" + t3.lower();
        MergeEntry me3 = mined.parallel3To1.get(k3);
        if (me3 != null) {
          for (TargetCount tc : me3.mergedTargets()) {
            String surf =
                mined.truecase(
                    tc.normLower(),
                    t1.shape(),
                    t1.surface(),
                    capHere,
                    sentenceAllCaps,
                    strictMorphology,
                    noisyOnlyCtx);
            outArcs.add(
                buildDirectArc(
                    i,
                    i + 3,
                    surf,
                    tc.normLower(),
                    NormV2Types.SRC_PARALLEL_MERGE,
                    me3.mergeProb(tc),
                    (float) Math.log1p(tc.count()),
                    0.0f,
                    0.20f,
                    0));
          }
        }
      }
      // Detached apostrophe: e.g. "49" + "'" + "lular" -> "49'lular", "Türkiye" + "'" + "de" -> "Türkiye'de"
      if (("'" .equals(t2.surface()) || "’".equals(t2.surface()))
          && !t1.isImmutable()
          && DETACHED_WORD_APOSTROPHE_SUFFIXES.contains(t3.lower())) {
        String joinedLow = t1.lower() + "'" + t3.lower();
        String joinedSurf =
            t1.type() == Token.Type.Number
                ? t1.surface() + "'" + t3.lower()
                : mined.truecase(
                    joinedLow,
                    t1.shape(),
                    t1.surface(),
                    capHere,
                    sentenceAllCaps,
                    strictMorphology,
                    noisyOnlyCtx);
        outArcs.add(
            buildDirectArc(
                i,
                i + 3,
                joinedSurf,
                joinedLow,
                NormV2Types.SRC_NUMERAL_JOIN | NormV2Types.SRC_OOV_APOSTROPHE,
                0.0f,
                0.0f,
                0.92f,
                0.12f,
                0));
      }
      if (".".equals(t2.surface())
          && isAllDigits(t1.surface())
          && DETACHED_NUM_SUFFIXES.contains(t3.lower())) {
        String ord = buildOrdinalInflected(t1.surface(), t3.lower());
        if (ord != null) {
          outArcs.add(
              buildDirectArc(
                  i, i + 3, ord, ord, NormV2Types.SRC_NUMERAL_JOIN, 0.0f, 0.0f, 0.90f, 0.15f, 0));
        }
      }
      if ("$".equals(t2.surface())
          && t2.adjacentToPrev()
          && t3.adjacentToPrev()
          && !t1.isImmutable()
          && !t3.isImmutable()) {
        String leetJoined = t1.lower() + "ş" + t3.lower();
        String real = realizeValidWordOrNull(leetJoined);
        if (real != null) {
          String surf =
              mined.truecase(
                  real,
                  t1.shape(),
                  t1.surface(),
                  capHere,
                  sentenceAllCaps,
                  strictMorphology,
                  noisyOnlyCtx);
          outArcs.add(
              buildDirectArc(
                  i,
                  i + 3,
                  surf,
                  real,
                  NormV2Types.SRC_COMPOUND_MERGE,
                  0.0f,
                  0.0f,
                  0.90f,
                  0.20f,
                  0));
        } else {
          for (String qt : QUESTION_TAILS) {
            if (leetJoined.length() > qt.length() + 2 && leetJoined.endsWith(qt)) {
              String headReal =
                  realizeValidWordOrNull(
                      leetJoined.substring(0, leetJoined.length() - qt.length()));
              if (headReal != null) {
                String splitLow =
                    headReal + " " + harmonizeQuestionClitic(qt, lastTurkishVowel(headReal));
                String surf =
                    mined.truecase(
                        splitLow,
                        t1.shape(),
                        t1.surface(),
                        capHere,
                        sentenceAllCaps,
                        strictMorphology,
                        noisyOnlyCtx);
                outArcs.add(
                    buildDirectArc(
                        i,
                        i + 3,
                        surf,
                        splitLow,
                        NormV2Types.SRC_COMPOUND_MERGE | NormV2Types.SRC_CLITIC_SPLIT,
                        0.0f,
                        0.0f,
                        0.90f,
                        0.20f,
                        0));
                break;
              }
            }
          }
        }
      }
    }

    // 2-token merges
    if (i + 1 >= n) {
      return;
    }
    SpanToken t2 = tokens.get(i + 1);
    boolean orphansAdjacentThird =
        ("$".equals(t2.surface()) || "'".equals(t2.surface()) || "’".equals(t2.surface()))
            && i + 2 < n
            && (tokens.get(i + 2).adjacentToPrev()
                || DETACHED_WORD_APOSTROPHE_SUFFIXES.contains(tokens.get(i + 2).lower()));

    // (1) Empirical 2:1 table
    if (config.useParallelTables && !orphansAdjacentThird) {
      String k2 = t1.lower() + "\t" + t2.lower();
      MergeEntry me2 = mined.parallel2To1.get(k2);
      if (me2 != null) {
        int rawCo = Math.max(me2.cooccurCount(), me2.totalMergedCount());
        for (TargetCount tc : me2.mergedTargets()) {
          String surf =
              mined.truecase(
                  tc.normLower(),
                  t1.shape(),
                  t1.surface(),
                  capHere,
                  sentenceAllCaps,
                  strictMorphology,
                  noisyOnlyCtx);
          SpanArc ma =
              buildDirectArc(
                  i,
                  i + 2,
                  surf,
                  tc.normLower(),
                  NormV2Types.SRC_PARALLEL_MERGE,
                  me2.mergeProb(tc),
                  (float) Math.log1p(tc.count()),
                  0.0f,
                  0.15f,
                  0);
          ma.rawParallelTotal = rawCo;
          outArcs.add(ma);
        }
      }
    }

    // (2) Detached numeral / alphanumeric suffix: "12" + "de" -> "12'de", "9.52" + "de" -> "9.52'de", "5s" + "ini" -> "5s'ini"
    if (t1.type() == Token.Type.Number && DETACHED_NUM_SUFFIXES.contains(t2.lower())) {
      String numSurf = t1.surface();
      if (numSurf.endsWith(".") && numSurf.length() > 1) {
        String ord =
            buildOrdinalInflected(numSurf.substring(0, numSurf.length() - 1), t2.lower());
        if (ord != null) {
          outArcs.add(
              buildDirectArc(
                  i, i + 2, ord, ord, NormV2Types.SRC_NUMERAL_JOIN, 0.0f, 0.0f, 0.90f, 0.15f, 0));
        }
      } else if (isNumericSurface(numSurf)) {
        String joined = numSurf + "'" + t2.lower();
        outArcs.add(
            buildDirectArc(
                i,
                i + 2,
                joined,
                joined,
                NormV2Types.SRC_NUMERAL_JOIN,
                0.0f,
                0.0f,
                0.85f,
                0.15f,
                0));
      }
    } else if (!t1.isImmutable()
        && t1.containsDigit()
        && t1.surface().length() <= 6
        && DETACHED_NUM_SUFFIXES.contains(t2.lower())) {
      String stemSurf =
          isLettersThenDigits(t1.lower()) ? NormV2Types.trUpper(t1.lower()) : t1.surface();
      String joinedSurf = stemSurf + "'" + t2.lower();
      String joinedLow = t1.lower() + "'" + t2.lower();
      outArcs.add(
          buildDirectArc(
              i,
              i + 2,
              joinedSurf,
              joinedLow,
              NormV2Types.SRC_NUMERAL_JOIN | NormV2Types.SRC_OOV_APOSTROPHE,
              0.0f,
              0.0f,
              0.84f,
              0.16f,
              0));
    }

    // (3) Closed Turkish compounds, split ability, assimilated detached locatives/ablatives & -ki relativizers
    if (!t1.isImmutable()
        && !t2.isImmutable()
        && !t2.adjacentToPrev()
        && !t1.containsDigit()
        && !t2.containsDigit()) {
      String t2Low = t2.lower();
      boolean isShortUpperAcronym =
          t1.shape() == CasingShape.UPPER
              && !sentenceAllCaps
              && t1.surface().length() >= 2
              && t1.surface().length() <= 5
              && !hasRegularStrictAnalysis(t1.lower());
      boolean isKnownAcronym = mined.isKnownAcronymStem(t1.lower()) || isShortUpperAcronym;
      boolean isProperOrAcronym =
          isKnownAcronym
              || mined.isKnownProperOrAcronymStem(t1.lower())
              || isStrictProperOrAbbrOnly(t1.lower())
              || (!capHere
                  && t1.shape() == CasingShape.TITLE
                  && !hasRegularStrictAnalysis(t1.lower()));

      // (3a) Detached locative / ablative with automatic Turkish voicing & vowel assimilation ("kitap da"->"kitapta", "denemek de"->"denemekte")
      boolean isLocOrAbl =
          t2Low.equals("de")
              || t2Low.equals("da")
              || t2Low.equals("te")
              || t2Low.equals("ta")
              || t2Low.equals("den")
              || t2Low.equals("dan")
              || t2Low.equals("ten")
              || t2Low.equals("tan");
      if (isLocOrAbl && t1.lower().length() >= 2) {
        String stemReal = realizeValidWordOrNull(t1.lower());
        if (stemReal == null) {
          stemReal = t1.lower();
        }
        String assimilatedSuf = assimilateLocOrAblSuffix(stemReal, t2Low.length() == 3);
        String candMerged = stemReal + assimilatedSuf;
        Integer closedCnt = mined.cleanClosedWords.get(candMerged);
        boolean isInfinitiveProg =
            t2Low.length() == 2 && (stemReal.endsWith("mak") || stemReal.endsWith("mek"));
        if (hasRegularStrictAnalysis(candMerged)
            && !candMerged.equals("yada")
            && !candMerged.equals("bide")
            && !candMerged.equals("birde")
            && !candMerged.equals("hemde")
            && (isInfinitiveProg || (closedCnt != null && closedCnt >= 8))) {
          String surf =
              mined.truecase(
                  candMerged,
                  t1.shape(),
                  t1.surface(),
                  capHere,
                  sentenceAllCaps,
                  strictMorphology,
                  noisyOnlyCtx);
          outArcs.add(
              buildDirectArc(
                  i,
                  i + 2,
                  surf,
                  candMerged,
                  NormV2Types.SRC_COMPOUND_MERGE,
                  0.0f,
                  closedCnt != null ? (float) Math.log1p(closedCnt) : 0.0f,
                  isInfinitiveProg ? 0.72f : 0.58f,
                  0.20f,
                  countDiacriticDiffs(t1.lower() + t2Low, candMerged)));
        }
        // Detached ProperNoun / Acronym case suffix ("Ayasofya da" -> "Ayasofya'da", "TYT de" -> "TYT'de")
        if (isProperOrAcronym) {
          String propSuf = assimilateLocOrAblSuffix(t1.lower(), t2Low.length() == 3);
          String apLow = t1.lower() + "'" + propSuf;
          String apSurf =
              isKnownAcronym
                  ? NormV2Types.trUpper(t1.lower()) + "'" + propSuf
                  : mined.truecase(
                      apLow,
                      t1.shape(),
                      t1.surface(),
                      capHere,
                      sentenceAllCaps,
                      strictMorphology,
                      noisyOnlyCtx);
          outArcs.add(
              buildDirectArc(
                  i,
                  i + 2,
                  apSurf,
                  apLow,
                  NormV2Types.SRC_OOV_APOSTROPHE | NormV2Types.SRC_COMPOUND_MERGE,
                  0.0f,
                  0.0f,
                  0.76f,
                  0.18f,
                  0));
        }
      }

      // (3b) Detached relativizer -ki/-kü ("masada ki" -> "masadaki", "benim ki" -> "benimki", "dün kü" -> "dünkü")
      if ((t2Low.equals("ki") || t2Low.equals("kü") || t2Low.equals("kı"))
          && t1.lower().length() >= 3) {
        String stemReal = realizeValidWordOrNull(t1.lower());
        if (stemReal == null) {
          stemReal = t1.lower();
        }
        for (String kiVar : new String[] {stemReal + "ki", stemReal + "kü"}) {
          if (LEXICAL_KI_WORDS.contains(kiVar) || hasRegularStrictAnalysis(kiVar)) {
            Integer closedCnt = mined.cleanClosedWords.get(kiVar);
            String surf =
                mined.truecase(
                    kiVar,
                    t1.shape(),
                    t1.surface(),
                    capHere,
                    sentenceAllCaps,
                    strictMorphology,
                    noisyOnlyCtx);
            outArcs.add(
                buildDirectArc(
                    i,
                    i + 2,
                    surf,
                    kiVar,
                    NormV2Types.SRC_COMPOUND_MERGE,
                    0.0f,
                    closedCnt != null ? (float) Math.log1p(closedCnt) : 0.0f,
                    0.78f,
                    0.18f,
                    countDiacriticDiffs(t1.lower() + t2Low, kiVar)));
            break;
          }
        }
      }

      // (3c) General closed compounds ("hiç bir" -> "hiçbir"), split ability ("göndere bilirler") & split copulas ("deniliyor du")
      if (!isLocOrAbl && !t2Low.equals("ki") && !t2Low.equals("kü")) {
        String concatRaw = t1.lower() + t2Low;
        String realized = realizeValidWordOrNull(concatRaw);
        if (realized != null) {
          boolean isClosedPrefix = false;
          for (String cp : CLOSED_COMPOUND_PREFIXES) {
            if (realized.startsWith(cp)) {
              isClosedPrefix = true;
              break;
            }
          }
          boolean isSplitAbility =
              (t1.lower().endsWith("a") || t1.lower().endsWith("e"))
                  && (t2Low.startsWith("bil") || t2.ascii().startsWith("bil"));
          boolean isSplitCopula =
              SPLIT_COPULA_TAILS.contains(t2Low)
                  && (!t2Low.equals("sa") && !t2Low.equals("se")
                      || summarizeMorph(t1.lower()).syntaxClass
                          == NormV2Types.HEAD_SYNTAX_FINITE_OR_CONVERB)
                  && hasRegularStrictAnalysis(realized);
          Integer closedFreq = mined.cleanClosedWords.get(realized);
          if (isClosedPrefix
              || isSplitAbility
              || isSplitCopula
              || (closedFreq != null && closedFreq >= 80)) {
            if (!realized.contains("şey")
                && !realized.equals("yada")
                && !realized.equals("şuan")) {
              String surf =
                  mined.truecase(
                      realized,
                      t1.shape(),
                      t1.surface(),
                      capHere,
                      sentenceAllCaps,
                      strictMorphology,
                      noisyOnlyCtx);
              outArcs.add(
                  buildDirectArc(
                      i,
                      i + 2,
                      surf,
                      realized,
                      NormV2Types.SRC_COMPOUND_MERGE,
                      0.0f,
                      closedFreq != null ? (float) Math.log1p(closedFreq) : 0.0f,
                      0.85f,
                      0.20f,
                      countDiacriticDiffs(concatRaw, realized)));
            }
          }
        }

        // (3d) Detached non-locative case & derivational suffixes on ProperNouns / Acronyms ("TRT ye"->"TRT'ye", "Tefal e"->"Tefal'e", "XT li"->"XT'li")
        if (isProperOrAcronym
            && t1.lower().length() >= 2
            && DETACHED_PROPER_NONLOC_SUFFIXES.contains(t2Low)) {
          boolean derivSuf =
              t2Low.startsWith("l") && !t2Low.equals("la") && !t2Low.equals("le");
          if (derivSuf && !isKnownAcronym) {
            String closedLow = t1.lower() + t2Low;
            String closedSurf =
                !sentenceAllCaps && t1.shape() == CasingShape.MIXED
                    ? t1.surface() + t2Low
                    : NormV2Types.trCapitalize(closedLow);
            outArcs.add(
                buildDirectArc(
                    i,
                    i + 2,
                    closedSurf,
                    closedLow,
                    NormV2Types.SRC_COMPOUND_MERGE,
                    0.0f,
                    0.0f,
                    0.78f,
                    0.18f,
                    0));
          } else {
            String apLow = t1.lower() + "'" + t2Low;
            String apSurf =
                isKnownAcronym
                    ? NormV2Types.trUpper(t1.lower()) + "'" + t2Low
                    : mined.truecase(
                        apLow,
                        t1.shape(),
                        t1.surface(),
                        capHere,
                        sentenceAllCaps,
                        strictMorphology,
                        noisyOnlyCtx);
            outArcs.add(
                buildDirectArc(
                    i,
                    i + 2,
                    apSurf,
                    apLow,
                    NormV2Types.SRC_OOV_APOSTROPHE | NormV2Types.SRC_COMPOUND_MERGE,
                    0.0f,
                    0.0f,
                    isKnownAcronym ? 0.84f : 0.78f,
                    0.18f,
                    0));
          }
        }
      }
    }
  }

  private static String assimilateLocOrAblSuffix(String stemLower, boolean ablative) {
    char lv = lastTurkishVowel(stemLower);
    String vowel = isFrontVowel(lv) ? "e" : "a";
    char lastCh = stemLower.charAt(stemLower.length() - 1);
    boolean voiceless = "fstkçşhp".indexOf(lastCh) >= 0;
    String cons = voiceless ? "t" : "d";
    return ablative ? cons + vowel + "n" : cons + vowel;
  }

  /**
   * Computes syntactic right-context state after token {@code i}: {@code 10} for existential
   * predicates ({@code var/yok/mevcut}), {@code 11} at clause/emoticon boundaries, {@code 1..6} for
   * finite verb person agreement, or {@code 0} otherwise.
   */
  private byte computeNextSyntacticState(List<SpanToken> tokens, int nextIdx, String nextLexLower) {
    if (nextIdx >= tokens.size()) {
      return 11;
    }
    SpanToken nt = tokens.get(nextIdx);
    if (nt.type() == Token.Type.Emoticon || nt.type() == Token.Type.Emoji) {
      return 11;
    }
    if (nt.type() == Token.Type.Punctuation) {
      String s = nt.surface();
      if (s.indexOf('.') >= 0
          || s.indexOf('!') >= 0
          || s.indexOf('?') >= 0
          || s.equals(";")
          || s.equals(":")) {
        return 11;
      }
    }
    if (nextLexLower == null) {
      return 11;
    }
    if (nextLexLower.equals("var")
        || nextLexLower.equals("yok")
        || nextLexLower.startsWith("vardı")
        || nextLexLower.startsWith("yoktu")
        || nextLexLower.startsWith("mevcut")) {
      return 10;
    }
    MorphSummary nms = summarizeMorph(nextLexLower);
    return nms.verbPerson;
  }

  private boolean isStrictProperOrAbbrOnly(String wordLower) {
    boolean hasProp = false;
    for (SingleAnalysis a : strictMorphology.analyze(wordLower)) {
      if (a.isUnknown() || a.isRuntime()) {
        continue;
      }
      SecondaryPos sp = a.getDictionaryItem().secondaryPos;
      if (sp == SecondaryPos.ProperNoun || sp == SecondaryPos.Abbreviation) {
        if (a.getEnding().isEmpty()) {
          hasProp = true;
        }
      } else {
        return false;
      }
    }
    return hasProp;
  }

  private boolean isBareImperativeVerbOnly(String wordLower) {
    boolean hasBareVerb = false;
    for (SingleAnalysis a : strictMorphology.analyze(wordLower)) {
      if (a.isUnknown() || a.isRuntime()) {
        continue;
      }
      if (a.getDictionaryItem().primaryPos == PrimaryPos.Verb && a.getEnding().isEmpty()) {
        hasBareVerb = true;
      } else {
        return false;
      }
    }
    return hasBareVerb;
  }

  private boolean isVerbRootCandidate(String stemLower) {
    for (SingleAnalysis a : informalAsciiMorphology.analyze(stemLower)) {
      if (!a.isUnknown()
          && !a.isRuntime()
          && a.getDictionaryItem().primaryPos == PrimaryPos.Verb
          && a.getEnding().isEmpty()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Detects unambiguous Turkish finite negative verb suffixes on noisy/OOV raw tokens (e.g. {@code
   * "çıkmarlar"}, {@code "seçimiyor"}, {@code "gelmıyo"}) without false-firing on {@code -mA}
   * verbal nouns ({@code "açıklama"}, {@code "çalışma"}).
   */
  public boolean hasRawNegativeVerbCue(String rawLower) {
    if (rawLower == null || rawLower.length() < 5) {
      return false;
    }
    MorphSummary ms = summarizeMorph(rawLower);
    if (ms.verbPolarity != 0) {
      return ms.verbPolarity < 0;
    }
    String asc = NormV2Types.toAscii(rawLower);
    if (asc.contains("miyor")
        || asc.contains("muyor")
        || asc.endsWith("miyo")
        || asc.endsWith("muyo")
        || asc.endsWith("miyolar")
        || asc.endsWith("muyolar")
        || asc.contains("mayacak")
        || asc.contains("meyecek")
        || asc.contains("micak")
        || asc.contains("micek")
        || asc.contains("mucak")
        || asc.contains("mucek")
        || asc.endsWith("mazlar")
        || asc.endsWith("mezler")
        || asc.endsWith("mazsin")
        || asc.endsWith("mezsin")) {
      return true;
    }
    if ((asc.endsWith("marlar") || asc.endsWith("merler")) && rawLower.length() >= 8) {
      String stem = rawLower.substring(0, rawLower.length() - 6);
      return isVerbRootCandidate(stem);
    }
    return false;
  }

  private SpanArc materializeArc(
      int start, int end, SpanToken rawTok, String nextLexLower, CandidateDraft d) {
    String firstW = d.firstWordLower();
    String lastW = d.lastWordLower();
    MorphSummary msLast = summarizeMorph(lastW);
    MorphSummary msFirst = firstW.equals(lastW) ? msLast : summarizeMorph(firstW);
    boolean caseChanged =
        !d.normSurface.equals(rawTok.surface()) && d.normLower.equals(rawTok.lower());
    boolean apAdded = rawTok.surface().indexOf('\'') < 0 && d.normSurface.indexOf('\'') >= 0;
    boolean apRemoved = rawTok.surface().indexOf('\'') >= 0 && d.normSurface.indexOf('\'') < 0;
    boolean stripsDiac = stripsExplicitDiacritic(rawTok.lower(), d.normLower);
    float phono = mined.charPhonotactics.scoreWord(lastW);
    float intLm = d.internalLmBonus(this);
    SpanArc arc =
        new SpanArc(
            start,
            end,
            d.normSurface,
            d.normLower,
            d.sourceMask,
            msFirst.coarseTag,
            msLast.coarseTag,
            msFirst.syntaxClass,
            msLast.verbPerson,
            msLast.isRegular && msFirst.isRegular,
            msLast.isProperOrAbbr || d.normSurface.indexOf('\'') > 0,
            d.parallelProb,
            d.parallelLogCount,
            d.suffixPrecision,
            d.editCost,
            d.diacriticDiffs,
            caseChanged,
            apAdded,
            apRemoved,
            stripsDiac,
            phono,
            intLm);
    arc.verbPolarity = msLast.verbPolarity;
    arc.rawCleanCount = mined.cleanLm != null ? mined.cleanLm.unigramCount(rawTok.lower()) : 0;
    arc.rawCharPhono = mined.charPhonotactics.scoreWord(rawTok.lower());
    if (mined.cleanLm != null && !d.normLower.equals(rawTok.lower())) {
      float uniDiff =
          mined.cleanLm.unigramLogProb(lastW) - mined.cleanLm.unigramLogProb(rawTok.lower());
      float bgDiff =
          nextLexLower != null
              ? mined.cleanLm.bigramLogProb(lastW, nextLexLower)
                  - mined.cleanLm.bigramLogProb(rawTok.lower(), nextLexLower)
              : 0.0f;
      arc.unaryCleanGain = uniDiff + bgDiff;
    }
    return arc;
  }

  private SpanArc buildDirectArc(
      int start,
      int end,
      String normSurf,
      String normLow,
      int sourceMask,
      float parProb,
      float parLogCnt,
      float sufPrec,
      float editCost,
      int diacDiffs) {
    int spFirst = normLow.indexOf(' ');
    int spLast = normLow.lastIndexOf(' ');
    String firstW = spFirst >= 0 ? normLow.substring(0, spFirst) : normLow;
    String lastW = spLast >= 0 ? normLow.substring(spLast + 1) : normLow;
    MorphSummary msLast = summarizeMorph(lastW);
    MorphSummary msFirst = firstW.equals(lastW) ? msLast : summarizeMorph(firstW);
    float phono = mined.charPhonotactics.scoreWord(lastW);
    float intLm = spFirst >= 0 ? computeInternalLm(normLow) : 0.0f;
    SpanArc arc =
        new SpanArc(
            start,
            end,
            normSurf,
            normLow,
            sourceMask,
            msFirst.coarseTag,
            msLast.coarseTag,
            msFirst.syntaxClass,
            msLast.verbPerson,
            msLast.isRegular && msFirst.isRegular,
            msLast.isProperOrAbbr || normSurf.indexOf('\'') > 0,
            parProb,
            parLogCnt,
            sufPrec,
            editCost,
            diacDiffs,
            false,
            normSurf.indexOf('\'') >= 0,
            false,
            false,
            phono,
            intLm);
    arc.verbPolarity = msLast.verbPolarity;
    return arc;
  }

  private record MorphSummary(
      short coarseTag,
      byte syntaxClass,
      byte verbPerson,
      byte verbPolarity,
      boolean isRegular,
      boolean isProperOrAbbr,
      boolean isStrictRegularCommon) {}

  private MorphSummary summarizeMorph(String wordLower) {
    MorphSummary cached = morphSummaryCache.get(wordLower);
    if (cached != null) {
      return cached;
    }
    MorphSummary computed = computeMorphSummary(wordLower);
    if (morphSummaryCache.size() < 65_536) {
      morphSummaryCache.put(wordLower, computed);
    }
    return computed;
  }

  private MorphSummary computeMorphSummary(String wordLower) {
    int ap = wordLower.indexOf('\'');
    String query = ap > 0 ? wordLower.substring(0, ap) + wordLower.substring(ap + 1) : wordLower;
    SingleAnalysis bestReg = null;
    SingleAnalysis bestAny = null;
    boolean seenNegVerb = false;
    boolean seenPosVerb = false;
    boolean seenNonVerb = false;
    for (SingleAnalysis a : strictMorphology.analyze(query)) {
      if (a.isUnknown()) {
        continue;
      }
      if (bestAny == null) {
        bestAny = a;
      }
      SecondaryPos sp = a.getDictionaryItem().secondaryPos;
      if (!a.isRuntime()
          && sp != SecondaryPos.ProperNoun
          && sp != SecondaryPos.Abbreviation
          && !(a.getDictionaryItem().primaryPos == PrimaryPos.Interjection
              && a.getDictionaryItem().hasAttribute(RootAttribute.Ext))) {
        if (bestReg == null) {
          bestReg = a;
        }
        if (a.getPos() == PrimaryPos.Verb) {
          List<Morpheme> ms = a.getMorphemes();
          if (ms.contains(TurkishMorphotactics.neg) || ms.contains(TurkishMorphotactics.unable)) {
            seenNegVerb = true;
          } else {
            seenPosVerb = true;
          }
        } else {
          seenNonVerb = true;
        }
      }
    }
    SingleAnalysis chosen = bestReg != null ? bestReg : bestAny;
    if (chosen == null) {
      boolean cleanAttested = mined.cleanLm != null && mined.cleanLm.unigramCount(wordLower) >= 8;
      return new MorphSummary(
          (short) 0,
          cleanAttested ? NormV2Types.HEAD_SYNTAX_NOMINAL : NormV2Types.HEAD_SYNTAX_OTHER,
          (byte) 0,
          (byte) 0,
          cleanAttested,
          ap > 0,
          false);
    }
    byte polarity = 0;
    if (!seenNonVerb && seenNegVerb != seenPosVerb) {
      polarity = seenNegVerb ? (byte) -1 : (byte) 1;
    }
    PrimaryPos rootPos = chosen.getDictionaryItem().primaryPos;
    PrimaryPos finalPos = chosen.getPos();
    SecondaryPos sp = chosen.getDictionaryItem().secondaryPos;
    Morpheme lastCaseOrTense = null;
    byte person = 0;
    List<Morpheme> morphs = chosen.getMorphemes();
    boolean isVerb = finalPos == PrimaryPos.Verb;
    for (Morpheme m : morphs) {
      if (m == TurkishMorphotactics.nom
          || m == TurkishMorphotactics.loc
          || m == TurkishMorphotactics.dat
          || m == TurkishMorphotactics.abl
          || m == TurkishMorphotactics.acc
          || m == TurkishMorphotactics.gen
          || m == TurkishMorphotactics.ins) {
        lastCaseOrTense = m;
      }
      if (isVerb) {
        if (m == TurkishMorphotactics.a1sg) person = 1;
        else if (m == TurkishMorphotactics.a2sg) person = 2;
        else if (m == TurkishMorphotactics.a3sg) person = 3;
        else if (m == TurkishMorphotactics.a1pl) person = 4;
        else if (m == TurkishMorphotactics.a2pl) person = 5;
        else if (m == TurkishMorphotactics.a3pl) person = 6;
      }
    }
    byte synClass = NormV2Types.HEAD_SYNTAX_OTHER;
    if (finalPos == PrimaryPos.Verb
        || (rootPos == PrimaryPos.Verb && finalPos == PrimaryPos.Adverb)) {
      synClass = NormV2Types.HEAD_SYNTAX_FINITE_OR_CONVERB;
    } else if (finalPos == PrimaryPos.Pronoun
        || finalPos == PrimaryPos.Adverb
        || finalPos == PrimaryPos.Conjunction
        || finalPos == PrimaryPos.PostPositive) {
      synClass = NormV2Types.HEAD_SYNTAX_PRON_ADV_CONJ;
    } else if (finalPos == PrimaryPos.Noun
        || finalPos == PrimaryPos.Adjective
        || finalPos == PrimaryPos.Numeral) {
      synClass = NormV2Types.HEAD_SYNTAX_NOMINAL;
    }
    boolean properNoQuoteDerivative =
        sp == SecondaryPos.ProperNoun
            && !chosen.getEnding().isEmpty()
            && !properNounTakesApostropheTDK(chosen);
    boolean takesApostrophe =
        (sp == SecondaryPos.Abbreviation)
            || (sp == SecondaryPos.ProperNoun && !properNoQuoteDerivative);
    return new MorphSummary(
        encodeMorphTag(finalPos, lastCaseOrTense),
        synClass,
        person,
        polarity,
        bestReg != null || properNoQuoteDerivative,
        takesApostrophe,
        ap <= 0 && bestReg != null);
  }

  private static short encodeMorphTag(PrimaryPos pos, Morpheme caseMorph) {
    int p = pos == null ? 0 : (pos.ordinal() + 1);
    int c = 0;
    if (caseMorph == TurkishMorphotactics.nom) c = 1;
    else if (caseMorph == TurkishMorphotactics.acc) c = 2;
    else if (caseMorph == TurkishMorphotactics.dat) c = 3;
    else if (caseMorph == TurkishMorphotactics.loc) c = 4;
    else if (caseMorph == TurkishMorphotactics.abl) c = 5;
    else if (caseMorph == TurkishMorphotactics.gen) c = 6;
    else if (caseMorph == TurkishMorphotactics.ins) c = 7;
    return (short) ((p << 4) | c);
  }

  /**
   * Resolves the next lexical word (or {@code null} for sentence end / {@code .?!}) starting at
   * {@code fromIdx}, skipping non-clause-breaking punctuation/emojis so language model right-context
   * is never polluted by commas or quotes.
   */
  public static String nextLexicalWordOrNull(List<SpanToken> toks, int fromIdx) {
    int n = toks.size();
    for (int k = fromIdx; k < n; k++) {
      SpanToken t = toks.get(k);
      if (t.type() == Token.Type.Punctuation) {
        String s = t.surface();
        if (s.indexOf('.') >= 0 || s.indexOf('?') >= 0 || s.indexOf('!') >= 0) {
          return null;
        }
        continue;
      }
      if (t.isImmutable()) {
        continue;
      }
      return t.lower();
    }
    return null;
  }

  public static boolean isSentenceBoundaryPunct(SpanToken t) {
    if (t.type() != Token.Type.Punctuation) {
      return false;
    }
    String s = t.surface();
    return s.indexOf('.') >= 0 || s.indexOf('?') >= 0 || s.indexOf('!') >= 0;
  }

  /**
   * Computes exact boundary-to-boundary generative chain-rule log-probabilities over {@code
   * [arc.startIdx, arc.endIdx) -> nextLexicalWord} for both {@code SmoothLm} (2-gram) and {@link
   * CleanWordLm} (3-gram), comparing {@code arc} directly against the unmodified raw tokens over
   * the exact same span.
   *
   * @param out6 {@code [candSmooth, rawSmooth, candClean3, rawClean3, cleanFwd3, cleanRight3]}
   */
  public void computeSpanLmComparison(
      SentenceLattice lat, String prev2Word, String prevWord, SpanArc arc, float[] out6) {
    List<SpanToken> toks = lat.tokens();
    String nextLex = nextLexicalWordOrNull(toks, arc.endIdx);
    CleanWordLm cleanLm = mined.cleanLm;

    // 1. Raw baseline path over [startIdx, endIdx) -> nextLex
    float rawSmooth = 0.0f;
    float rawClean3 = 0.0f;
    String rPrev2 = prev2Word;
    String rPrev1 = prevWord;
    for (int k = arc.startIdx; k < arc.endIdx; k++) {
      SpanToken rt = toks.get(k);
      if (rt.isImmutable()) {
        if (isSentenceBoundaryPunct(rt)) {
          rPrev2 = null;
          rPrev1 = null;
        }
        continue;
      }
      String rw = rt.lower();
      rawSmooth += NormV2Types.clampLm(lmBigram(rPrev1, rw));
      rawClean3 += cleanLm.trigramLogProb(rPrev2, rPrev1, rw);
      rPrev2 = rPrev1;
      rPrev1 = rw;
    }
    rawSmooth += NormV2Types.clampLm(lmBigram(rPrev1, nextLex));
    rawClean3 += cleanLm.trigramLogProb(rPrev2, rPrev1, nextLex);

    // 2. Candidate arc path over [startIdx, endIdx) -> nextLex
    float candSmooth = 0.0f;
    float candClean3 = 0.0f;
    String cPrev2 = prev2Word;
    String cPrev1 = prevWord;
    float cleanFwd3;
    if (arc.wordCount == 1) {
      String cw = arc.firstWordLower;
      candSmooth += NormV2Types.clampLm(lmBigram(cPrev1, cw));
      cleanFwd3 = cleanLm.trigramLogProb(cPrev2, cPrev1, cw);
      candClean3 += cleanFwd3;
      cPrev2 = cPrev1;
      cPrev1 = cw;
    } else {
      cleanFwd3 = cleanLm.trigramLogProb(cPrev2, cPrev1, arc.firstWordLower);
      int start = 0;
      String nl = arc.normLower;
      for (int idx = 0; idx <= nl.length(); idx++) {
        if (idx == nl.length() || nl.charAt(idx) == ' ') {
          if (idx > start) {
            String cw = nl.substring(start, idx);
            candSmooth += NormV2Types.clampLm(lmBigram(cPrev1, cw));
            candClean3 += cleanLm.trigramLogProb(cPrev2, cPrev1, cw);
            cPrev2 = cPrev1;
            cPrev1 = cw;
          }
          start = idx + 1;
        }
      }
    }
    float cleanRight3 = cleanLm.trigramLogProb(cPrev2, cPrev1, nextLex);
    candSmooth += NormV2Types.clampLm(lmBigram(cPrev1, nextLex));
    candClean3 += cleanRight3;

    out6[0] = candSmooth;
    out6[1] = rawSmooth;
    out6[2] = candClean3;
    out6[3] = rawClean3;
    out6[4] = cleanFwd3;
    out6[5] = cleanRight3;
  }

  /**
   * Computes a symmetric clitic-transparency skip-bigram log-likelihood ratio over {@link
   * CleanWordLm}:
   *
   * <ul>
   *   <li>For {@code 1:2} {@code de/da} or {@code mi} splits ({@code raw -> head + clitic}),
   *       returns {@code log P(nextLex | head) - log P(nextLex | raw)}: positive when {@code
   *       clitic} is syntactically transparent ({@code "ben [de] geldim"}), negative when {@code
   *       raw} is a true locative ({@code "bende var"}).
   *   <li>For {@code 2:1} locative/ablative merges ({@code t1 + de/da -> mergedLoc}), returns
   *       {@code log P(nextLex | mergedLoc) - log P(nextLex | t1)}: positive when {@code
   *       mergedLoc} governs {@code nextLex} ({@code "evde kaldım"}), negative when {@code de/da}
   *       is a discourse clitic ({@code "kitap da aldım"}).
   * </ul>
   */
  public float computeCliticTransparencyLlr(SentenceLattice lat, SpanArc arc) {
    CleanWordLm cleanLm = mined.cleanLm;
    if (cleanLm == null) {
      return 0.0f;
    }
    List<SpanToken> toks = lat.tokens();
    String nextLex = nextLexicalWordOrNull(toks, arc.endIdx);
    if (arc.endIdx == arc.startIdx + 1 && arc.wordCount == 2) {
      int ct = classifyCliticSplitType(arc);
      if (ct == 1 || ct == 2) {
        String rawLow = toks.get(arc.startIdx).lower();
        float skipBg = cleanLm.bigramLogProb(arc.firstWordLower, nextLex);
        float closedBg = cleanLm.bigramLogProb(rawLow, nextLex);
        return Math.max(-12.0f, Math.min(12.0f, skipBg - closedBg));
      }
      return 0.0f;
    }
    if (arc.endIdx == arc.startIdx + 2 && arc.wordCount == 1 && arc.startIdx + 1 < toks.size()) {
      String t2Low = toks.get(arc.startIdx + 1).lower();
      if (t2Low.equals("de")
          || t2Low.equals("da")
          || t2Low.equals("te")
          || t2Low.equals("ta")
          || t2Low.equals("den")
          || t2Low.equals("dan")
          || t2Low.equals("ten")
          || t2Low.equals("tan")) {
        String t1Low = toks.get(arc.startIdx).lower();
        float mergedBg = cleanLm.bigramLogProb(arc.lastWordLower, nextLex);
        float skipBg = cleanLm.bigramLogProb(t1Low, nextLex);
        return Math.max(-12.0f, Math.min(12.0f, mergedBg - skipBg));
      }
    }
    return 0.0f;
  }

  /**
   * Guarded cross-token Turkish 4-way vowel-harmony score ({@code +1.0 = harmonic}, {@code -1.0 =
   * disharmonic}, {@code 0.0 = neutral/guarded}) for standalone question clitics ({@code mi/mı},
   * {@code misin/mısın}, {@code misiniz/mısınız}, {@code mu/mü}, ...).
   *
   * <p>Returns {@code 0.0} whenever {@code prev1Lex} is separated by punctuation/immutable tokens,
   * contains digits, is an uppercase acronym, is OOV/proper/foreign ({@code tag mi}, {@code Apple
   * mı}), or has ambiguous {@link RootAttribute#InverseHarmony} ({@code normal mi}, {@code hal
   * mi}).
   */
  public float computeQuestionCliticHarmony(SentenceLattice lat, String prev1Lex, SpanArc arc) {
    // 1:2 question-clitic split ("açmış mıyım", "olur mu"): check internal head -> clitic harmony
    if (arc.endIdx == arc.startIdx + 1
        && arc.wordCount == 2
        && QUESTION_TAILS.contains(arc.lastWordLower)
        && arc.lastWordLower.length() >= 2) {
      char headV = expectedTurkishQuestionVowel(arc.firstWordLower);
      if (headV == 0) {
        return 0.0f;
      }
      return arc.lastWordLower.charAt(1) == headV ? 1.0f : -1.0f;
    }
    if (arc.endIdx != arc.startIdx + 1
        || arc.wordCount != 1
        || arc.startIdx <= 0
        || prev1Lex == null
        || !QUESTION_TAILS.contains(arc.normLower)) {
      return 0.0f;
    }
    List<SpanToken> toks = lat.tokens();
    boolean allCapsSent = isSentenceAllCapsHeuristic(toks);
    SpanToken curTok = toks.get(arc.startIdx);
    // Protect mid-sentence TitleCase homographs ("Xiaomi Mi 11", "Mi Band") & Xiaomi specs ("mi 11", "mi band")
    if (curTok.shape() == CasingShape.TITLE && !allCapsSent) {
      return 0.0f;
    }
    if (curTok.lower().equals("mi") && arc.endIdx < toks.size()) {
      SpanToken nextTok = toks.get(arc.endIdx);
      if (nextTok.containsDigit() || isXiaomiMiProductTail(nextTok.lower())) {
        return 0.0f;
      }
    }
    SpanToken prevTok = toks.get(arc.startIdx - 1);
    if (prevTok.isImmutable() || (prevTok.shape() == CasingShape.UPPER && !allCapsSent)) {
      return 0.0f;
    }
    if (prevTok.containsDigit() && prev1Lex.indexOf('\'') < 0) {
      return 0.0f;
    }
    char expectedV = expectedTurkishQuestionVowel(prev1Lex);
    if (expectedV == 0 || arc.normLower.length() < 2) {
      return 0.0f;
    }
    char candV = arc.normLower.charAt(1);
    return candV == expectedV ? 1.0f : -1.0f;
  }

  private static boolean isSentenceAllCapsHeuristic(List<SpanToken> toks) {
    int alpha = 0;
    int upper = 0;
    for (SpanToken t : toks) {
      if (!t.isImmutable() && t.shape() != CasingShape.NON_ALPHA) {
        alpha++;
        if (t.shape() == CasingShape.UPPER) {
          upper++;
        }
      }
    }
    return alpha >= 3 && upper * 10 >= alpha * 8;
  }

  /**
   * Computes the expected 4-way Turkish high vowel ({@code 'ı', 'i', 'u', 'ü'}) after {@code
   * prevWordLower}, respecting {@link RootAttribute#InverseHarmony} ({@code saat mi}, {@code gol
   * mü}, {@code kabul mü}), native Turkish case suffixes after apostrophes ({@code X'a mı}, {@code
   * X'e mi}), and returning {@code 0} when {@code prevWordLower} is OOV, proper, foreign ({@code
   * RootAttribute.LocaleEn}), or dual-harmony ({@code normal}, {@code hal}).
   */
  public char expectedTurkishQuestionVowel(String prevWordLower) {
    if (prevWordLower == null || prevWordLower.isEmpty()) {
      return 0;
    }
    int ap = prevWordLower.lastIndexOf('\'');
    if (ap >= 0) {
      if (ap == 0 || ap + 1 >= prevWordLower.length()) {
        return 0;
      }
      String suf = prevWordLower.substring(ap + 1);
      if (!DETACHED_NUM_SUFFIXES.contains(suf) && !DETACHED_WORD_APOSTROPHE_SUFFIXES.contains(suf)) {
        return 0;
      }
      char sv = lastTurkishVowel(suf);
      return switch (sv) {
        case 'e', 'i', 'î' -> 'i';
        case 'ö', 'ü' -> 'ü';
        case 'o', 'u', 'û' -> 'u';
        case 'a', 'ı', 'â' -> 'ı';
        default -> 0;
      };
    }
    Character cached = questionVowelCache.get(prevWordLower);
    if (cached != null) {
      return cached;
    }
    char computed = computeExpectedTurkishQuestionVowel(prevWordLower);
    if (questionVowelCache.size() < 32_768) {
      questionVowelCache.put(prevWordLower, computed);
    }
    return computed;
  }

  private char computeExpectedTurkishQuestionVowel(String prevWordLower) {
    boolean hasInverse = false;
    boolean hasNormal = false;
    for (SingleAnalysis a : strictMorphology.analyze(prevWordLower)) {
      if (a.isUnknown() || a.isRuntime()) {
        continue;
      }
      DictionaryItem item = a.getDictionaryItem();
      SecondaryPos sp = item.secondaryPos;
      if (sp == SecondaryPos.ProperNoun
          || sp == SecondaryPos.Abbreviation
          || item.hasAttribute(RootAttribute.LocaleEn)
          || (item.primaryPos == PrimaryPos.Interjection && item.hasAttribute(RootAttribute.Ext))) {
        continue;
      }
      if (a.getEnding().isEmpty() && item.hasAttribute(RootAttribute.InverseHarmony)) {
        hasInverse = true;
      } else {
        hasNormal = true;
      }
    }
    // OOV / ProperNoun / foreign ("tag", "Apple") OR dual-entry loanword ("normal", "hal", "kalp") -> neutral
    if ((!hasInverse && !hasNormal) || (hasInverse && hasNormal)) {
      return 0;
    }
    char lv = lastTurkishVowel(prevWordLower);
    if (lv == 0) {
      return 0;
    }
    if (hasInverse) {
      lv =
          switch (lv) {
            case 'a', 'â' -> 'e';
            case 'ı' -> 'i';
            case 'o' -> 'ö';
            case 'u', 'û' -> 'ü';
            default -> lv;
          };
    }
    return switch (lv) {
      case 'e', 'i', 'î' -> 'i';
      case 'ö', 'ü' -> 'ü';
      case 'o', 'u', 'û' -> 'u';
      case 'a', 'ı', 'â' -> 'ı';
      default -> 0;
    };
  }

  /**
   * Classifies multi-word split candidates by Turkish clitic family: {@code 0}=not split, {@code
   * 1}={@code de/da}, {@code 2}=question clitic ({@code mi/mı/mu/mü/...}), {@code 3}={@code ki},
   * {@code 4}=open compound / glued words.
   */
  public static int classifyCliticSplitType(SpanArc arc) {
    if (arc.wordCount < 2) {
      return 0;
    }
    String lw = arc.lastWordLower;
    if ("de".equals(lw) || "da".equals(lw)) {
      return 1;
    }
    if (QUESTION_TAILS.contains(lw)) {
      return 2;
    }
    if ("ki".equals(lw)) {
      return 3;
    }
    return 4;
  }

  /**
   * Advances lexical 2-word history {@code (prev2, prev1)} across {@code arc}, ignoring non-clause
   * immutable tokens (commas, quotes, emojis, @mentions) and resetting to {@code (null, null)} at
   * sentence-ending punctuation ({@code .?!}).
   */
  public static void advanceLexicalHistory(
      SentenceLattice lat, String prev2, String prev1, SpanArc arc, String[] out2) {
    if (arc.wordCount == 1 && arc.endIdx == arc.startIdx + 1) {
      SpanToken t = lat.tokens().get(arc.startIdx);
      if (t.isImmutable()) {
        if (isSentenceBoundaryPunct(t)) {
          out2[0] = null;
          out2[1] = null;
        } else {
          out2[0] = prev2;
          out2[1] = prev1;
        }
        return;
      }
      out2[0] = prev1;
      out2[1] = arc.lastWordLower;
      return;
    }
    if (arc.wordCount == 1) {
      out2[0] = prev1;
      out2[1] = arc.lastWordLower;
      return;
    }
    if (arc.wordCount == 2) {
      out2[0] = arc.firstWordLower;
      out2[1] = arc.lastWordLower;
      return;
    }
    int lastSp = arc.normLower.lastIndexOf(' ');
    int prevSp = arc.normLower.lastIndexOf(' ', Math.max(0, lastSp - 1));
    out2[0] = arc.normLower.substring(prevSp + 1, lastSp);
    out2[1] = arc.lastWordLower;
  }

  public float lmBigram(String prevWordLower, String curWordLower) {
    if (lm == null) {
      return (useCleanBigramWhenLmNull && mined.cleanLm != null)
          ? mined.cleanLm.bigramLogProb(prevWordLower, curWordLower)
          : 0.0f;
    }
    int prevIdx =
        prevWordLower == null
            ? lmVocab.getSentenceStartIndex()
            : lmVocab.indexOfTurkishWord(prevWordLower);
    int curIdx =
        curWordLower == null
            ? lmVocab.getSentenceEndIndex()
            : lmVocab.indexOfTurkishWord(curWordLower);
    if (prevIdx < 0) {
      prevIdx = lmVocab.getUnknownWordIndex();
    }
    if (curIdx < 0) {
      curIdx = lmVocab.getUnknownWordIndex();
    }
    return lm.getProbability(prevIdx, curIdx);
  }

  public float lmUnigram(String wordLower) {
    if (lm == null) {
      return (useCleanBigramWhenLmNull && mined.cleanLm != null)
          ? mined.cleanLm.unigramLogProb(wordLower)
          : 0.0f;
    }
    int idx = lmVocab.indexOfTurkishWord(wordLower);
    if (idx < 0) {
      idx = lmVocab.getUnknownWordIndex();
    }
    return lm.getUnigramProbability(idx);
  }

  float computeInternalLm(String multiWordLower) {
    String[] parts = multiWordLower.split(" ");
    if (parts.length < 2) {
      return 0.0f;
    }
    float s = 0.0f;
    for (int i = 1; i < parts.length; i++) {
      s += lmBigram(parts[i - 1], parts[i]);
    }
    return s;
  }

  boolean hasRegularStrictAnalysis(String wordLower) {
    if (wordLower.isEmpty() || wordLower.indexOf('\'') >= 0) {
      return false;
    }
    return summarizeMorph(wordLower).isStrictRegularCommon;
  }

  private String realizeValidOrAttestedStem(String rawHead) {
    String real = realizeValidWordOrNull(rawHead);
    if (real != null) {
      return real;
    }
    if (rawHead.length() >= 3
        && ((mined.cleanLm != null && mined.cleanLm.unigramCount(rawHead) >= 4)
            || (lmVocab != null && lmVocab.contains(rawHead)))) {
      return rawHead;
    }
    return null;
  }

  private List<SplitHead> resolveSplitHeads(
      String queryHead,
      String rawHead,
      SpanToken tok,
      boolean capHere,
      boolean sentenceAllCaps) {
    List<SplitHead> out = new ArrayList<>(3);
    boolean strictReg = hasRegularStrictAnalysis(queryHead);
    if (strictReg && !stripsExplicitStemDiacritic(rawHead, queryHead)) {
      out.add(new SplitHead(null, queryHead, false));
    }

    // 1. Exact-vowel informal morphology ("geliyo" -> "geliyor")
    if (!strictReg) {
      for (SingleAnalysis a : exactInformalMorphology.analyze(queryHead)) {
        if (a.isUnknown() || a.isRuntime() || !a.containsInformalMorpheme()) {
          continue;
        }
        Result conv = informalConverter.convert(queryHead, a);
        if (conv != null && !stripsExplicitStemDiacritic(rawHead, conv.surface)) {
          String nl = NormV2Types.trLower(conv.surface);
          addUniqueSplitHead(out, new SplitHead(null, nl, false));
          break;
        }
      }
    }

    // 2. Diacritic-ignoring regular & ProperNoun realizations (ranked by CleanWordLm: "esın"->"eşin", "millayi"->"Milla'yı")
    if (!strictReg
        || (queryHead.length() <= 6
            && !hasExplicitTurkishDiacritics(queryHead)
            && hasAsciiAmbiguousChars(queryHead))) {
      List<String> regCands = new ArrayList<>(4);
      SplitHead bestProper = null;
      for (SingleAnalysis a : informalAsciiMorphology.analyze(queryHead)) {
        if (a.isUnknown()
            || a.isRuntime()
            || a.getDictionaryItem().secondaryPos == SecondaryPos.Abbreviation) {
          continue;
        }
        if (a.getDictionaryItem().primaryPos == PrimaryPos.Interjection
            && a.getDictionaryItem().hasAttribute(RootAttribute.Ext)) {
          continue;
        }
        boolean isProp = a.getDictionaryItem().secondaryPos == SecondaryPos.ProperNoun;
        if (a.containsInformalMorpheme()) {
          Result conv = informalConverter.convert(queryHead, a);
          if (conv != null && !stripsExplicitStemDiacritic(rawHead, conv.surface)) {
            String nl = NormV2Types.trLower(conv.surface);
            if (!isProp && !regCands.contains(nl)) {
              regCands.add(nl);
            }
          }
        } else {
          for (Result r :
              strictMorphology.getWordGenerator().generate(a.getDictionaryItem(), a.getMorphemes())) {
            if (TurkishAlphabet.INSTANCE.equalsIgnoreDiacritics(queryHead, r.surface)
                && !stripsExplicitDiacritic(rawHead, r.surface)) {
              String nl = NormV2Types.trLower(r.surface);
              if (!isProp) {
                if (!regCands.contains(nl)) {
                  regCands.add(nl);
                }
              } else if (bestProper == null) {
                String fmt = formatProperOrAbbrTDK(a, nl);
                if (fmt != null && !fmt.isEmpty()) {
                  String fl = NormV2Types.trLower(fmt);
                  String surf = sentenceAllCaps ? NormV2Types.trUpper(fmt) : fmt;
                  bestProper = new SplitHead(surf, fl, true);
                }
              }
            }
          }
        }
      }
      if (regCands.size() > 1 && mined.cleanLm != null) {
        regCands.sort(
            (x, y) ->
                Float.compare(
                    mined.cleanLm.unigramLogProb(y), mined.cleanLm.unigramLogProb(x)));
      }
      int addedReg = 0;
      for (String rc : regCands) {
        if (addUniqueSplitHead(out, new SplitHead(null, rc, false)) && ++addedReg >= 2) {
          break;
        }
      }
      if (bestProper != null && out.size() < 3) {
        addUniqueSplitHead(out, bestProper);
      }
    }

    // 3. OOV ProperNoun / Acronym + inflectional suffix inside split head ("youtubedada"->"YouTube'da da", "cekuyuda"->"Ceku'yu da")
    if (out.isEmpty() && queryHead.length() >= 4) {
      for (String suf : SPLIT_HEAD_PROPER_SUFS) {
        if (queryHead.length() - suf.length() < 2 || !queryHead.endsWith(suf)) {
          continue;
        }
        String stemLow = queryHead.substring(0, queryHead.length() - suf.length());
        boolean isAcronym = mined.isKnownAcronymStem(stemLow);
        if (isAcronym || mined.isKnownProperOrAcronymStem(stemLow)) {
          String candLow = stemLow + "'" + suf;
          String candSurf =
              isAcronym
                  ? NormV2Types.trUpper(stemLow) + "'" + suf
                  : mined.truecase(
                      candLow,
                      tok.shape(),
                      tok.surface(),
                      capHere,
                      sentenceAllCaps,
                      strictMorphology);
          addUniqueSplitHead(out, new SplitHead(candSurf, candLow, true));
          break;
        }
      }
    }

    // 4. Foreign x->ks / w->v adaptation & CleanWordLm-attested loanwords ("sexsizde"->"sekssiz de", "hostmu"->"host mu")
    if (queryHead.indexOf('x') >= 0 || queryHead.indexOf('w') >= 0) {
      String adapted = realizeValidWordOrNull(queryHead.replace("x", "ks").replace("w", "v"));
      if (adapted != null) {
        addUniqueSplitHead(out, new SplitHead(null, adapted, false));
      }
    }
    if (out.isEmpty()
        && queryHead.length() >= 3
        && ((mined.cleanLm != null && mined.cleanLm.unigramCount(queryHead) >= 4)
            || (lmVocab != null && lmVocab.contains(queryHead))
            || mined.isKnownProperOrAcronymStem(queryHead))) {
      addUniqueSplitHead(out, new SplitHead(null, queryHead, false));
    }
    return out;
  }

  private static boolean addUniqueSplitHead(List<SplitHead> list, SplitHead cand) {
    for (SplitHead sh : list) {
      if (sh.normLower().equals(cand.normLower())) {
        return false;
      }
    }
    list.add(cand);
    return true;
  }

  private String realizeValidWordOrNull(String candidateLower) {
    if (candidateLower.isEmpty()) {
      return null;
    }
    String cached = realizeWordCache.get(candidateLower);
    if (cached != null) {
      return cached.isEmpty() ? null : cached;
    }
    String computed = computeRealizeValidWordOrNull(candidateLower);
    if (realizeWordCache.size() < 65_536) {
      realizeWordCache.put(candidateLower, computed != null ? computed : NO_REALIZATION);
    }
    return computed;
  }

  private String computeRealizeValidWordOrNull(String candidateLower) {
    if (hasRegularStrictAnalysis(candidateLower)) {
      return candidateLower;
    }
    // Prefer exact-vowel informal conversion first ("bılıyor" no, "geliyo" -> "geliyor")
    for (SingleAnalysis a : exactInformalMorphology.analyze(candidateLower)) {
      if (a.isUnknown() || a.isRuntime() || !a.containsInformalMorpheme()) {
        continue;
      }
      Result conv = informalConverter.convert(candidateLower, a);
      if (conv != null && !stripsExplicitStemDiacritic(candidateLower, conv.surface)) {
        return NormV2Types.trLower(conv.surface);
      }
    }
    // Rank non-ProperNoun regular realizations by CleanWordLm unigram probability before ProperNoun fallback
    String bestReg = null;
    float bestRegLp = -1000.0f;
    String fallbackProper = null;
    for (SingleAnalysis a : informalAsciiMorphology.analyze(candidateLower)) {
      if (a.isUnknown()
          || a.isRuntime()
          || a.getDictionaryItem().secondaryPos == SecondaryPos.Abbreviation) {
        continue;
      }
      if (a.getDictionaryItem().primaryPos == PrimaryPos.Interjection
          && a.getDictionaryItem().hasAttribute(RootAttribute.Ext)) {
        continue;
      }
      boolean isProp = a.getDictionaryItem().secondaryPos == SecondaryPos.ProperNoun;
      if (a.containsInformalMorpheme()) {
        Result conv = informalConverter.convert(candidateLower, a);
        if (conv != null && !stripsExplicitStemDiacritic(candidateLower, conv.surface)) {
          String nl = NormV2Types.trLower(conv.surface);
          if (!isProp) {
            float lp = mined.cleanLm != null ? mined.cleanLm.unigramLogProb(nl) : 0.0f;
            if (bestReg == null || lp > bestRegLp) {
              bestReg = nl;
              bestRegLp = lp;
            }
          } else if (fallbackProper == null) {
            fallbackProper = nl;
          }
        }
      } else {
        for (Result r :
            strictMorphology.getWordGenerator().generate(a.getDictionaryItem(), a.getMorphemes())) {
          if (TurkishAlphabet.INSTANCE.equalsIgnoreDiacritics(candidateLower, r.surface)
              && !stripsExplicitDiacritic(candidateLower, r.surface)) {
            String nl = NormV2Types.trLower(r.surface);
            if (!isProp) {
              float lp = mined.cleanLm != null ? mined.cleanLm.unigramLogProb(nl) : 0.0f;
              if (bestReg == null || lp > bestRegLp) {
                bestReg = nl;
                bestRegLp = lp;
              }
            } else if (fallbackProper == null) {
              fallbackProper = nl;
            }
          }
        }
      }
    }
    return bestReg != null ? bestReg : fallbackProper;
  }

  private static boolean hasLowCostMorphDraft(Map<String, CandidateDraft> drafts) {
    for (CandidateDraft d : drafts.values()) {
      if ((d.sourceMask
                  & (NormV2Types.SRC_DEASCIIFIER
                      | NormV2Types.SRC_FORMAL_MORPH
                      | NormV2Types.SRC_INFORMAL_EXACT))
              != 0
          && d.editCost <= 0.30f) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasValidSingleWordDraft(Map<String, CandidateDraft> drafts) {
    for (CandidateDraft d : drafts.values()) {
      if (d.normLower.indexOf(' ') < 0
          && (d.sourceMask
                  & (NormV2Types.SRC_DEASCIIFIER
                      | NormV2Types.SRC_FORMAL_MORPH
                      | NormV2Types.SRC_INFORMAL_MORPH
                      | NormV2Types.SRC_INFORMAL_EXACT
                      | NormV2Types.SRC_OOV_APOSTROPHE
                      | NormV2Types.SRC_SUFFIX_RULE
                      | NormV2Types.SRC_ELONGATION
                      | NormV2Types.SRC_SHORTHAND_ABJAD))
              != 0) {
        return true;
      }
    }
    return false;
  }

  private static final class CandidateDraft {
    String normSurface;
    final String normLower;
    int sourceMask;
    float parallelProb;
    float parallelLogCount;
    float suffixPrecision;
    float editCost;
    int diacriticDiffs;

    CandidateDraft(
        String normSurface,
        String normLower,
        int sourceMask,
        float parallelProb,
        float parallelLogCount,
        float suffixPrecision,
        float editCost,
        int diacriticDiffs) {
      this.normSurface = normSurface;
      this.normLower = normLower;
      this.sourceMask = sourceMask;
      this.parallelProb = parallelProb;
      this.parallelLogCount = parallelLogCount;
      this.suffixPrecision = suffixPrecision;
      this.editCost = editCost;
      this.diacriticDiffs = diacriticDiffs;
    }

    String firstWordLower() {
      int sp = normLower.indexOf(' ');
      return sp >= 0 ? normLower.substring(0, sp) : normLower;
    }

    String lastWordLower() {
      int sp = normLower.lastIndexOf(' ');
      return sp >= 0 ? normLower.substring(sp + 1) : normLower;
    }

    float internalLmBonus(SpanCandidateGenerator gen) {
      return normLower.indexOf(' ') >= 0 ? gen.computeInternalLm(normLower) : 0.0f;
    }
  }

  private static void addOrMergeDraft(
      Map<String, CandidateDraft> drafts,
      SpanToken rawTok,
      String normSurface,
      String normLower,
      int sourceMask,
      float parallelProb,
      float parallelLogCount,
      float suffixPrecision,
      float editCost,
      int diacriticDiffs) {
    if (normSurface == null || normSurface.isEmpty()) {
      return;
    }
    CandidateDraft existing = drafts.get(normSurface);
    if (existing == null) {
      drafts.put(
          normSurface,
          new CandidateDraft(
              normSurface,
              normLower,
              sourceMask,
              parallelProb,
              parallelLogCount,
              suffixPrecision,
              editCost,
              diacriticDiffs));
    } else {
      existing.sourceMask |= sourceMask;
      existing.parallelProb = Math.max(existing.parallelProb, parallelProb);
      existing.parallelLogCount = Math.max(existing.parallelLogCount, parallelLogCount);
      existing.suffixPrecision = Math.max(existing.suffixPrecision, suffixPrecision);
      existing.editCost = Math.min(existing.editCost, editCost);
    }
  }

  private static final Set<String> NON_SENT_END_ABBREVS =
      Set.of(
          "vs", "vb", "mah", "cad", "sok", "sk", "apt", "no", "blv", "dr", "prof",
          "doç", "doc", "op", "uzm", "av", "arb", "hz", "bkz", "bknz", "yy", "kr",
          "tl", "dk", "sn", "st", "gr", "kg", "cm", "mm", "km", "lt", "ltd", "şti",
          "sti", "inc", "co", "mr", "mrs", "ms", "inş", "ins", "ör", "örn", "yak",
          "ort", "maks", "min", "tel", "faks", "fax", "cep", "www", "http", "https",
          "alb", "yb", "gn", "yd", "yrd", "ogr", "öğr", "gör", "gor", "arş", "ars",
          "vet", "ecz", "dt", "mim", "müh", "muh");

  private static final Set<String> DOMAIN_OR_FILE_EXTS =
      Set.of(
          "com", "net", "org", "tr", "gov", "edu", "io", "co", "uk", "de", "ru",
          "nl", "fr", "it", "info", "biz", "php", "html", "htm", "asp", "aspx",
          "jsp", "js", "css", "pdf", "jpg", "jpeg", "png", "gif", "mp3", "mp4",
          "exe", "zip", "rar", "txt");

  private static final Set<String> QUOTED_DETACHED_SUFFIXES =
      Set.of(
          "ler", "lar", "da", "de", "ta", "te", "dan", "den", "tan", "ten",
          "nin", "nın", "nun", "nün", "in", "ın", "un", "ün", "ye", "ya",
          "yi", "yı", "yu", "yü", "le", "la", "li", "lı", "lu", "lü");

  private static boolean isDotOrEllipsisSurface(String s) {
    return s.equals(".") || s.equals("..") || s.equals("...") || s.equals("…");
  }

  private static boolean isClosingQuoteOrBracket(String s) {
    return s.equals("\"")
        || s.equals("'")
        || s.equals("”")
        || s.equals("’")
        || s.equals(")")
        || s.equals("]");
  }

  private static boolean isTrueInternalSentenceBoundary(List<SpanToken> tokens, int i) {
    int n = tokens.size();
    String s = tokens.get(i).surface();
    if (!s.equals(".") && !s.equals("!") && !s.equals("?")) {
      return false;
    }
    if (i == 0) {
      return false;
    }
    if (s.equals(".")) {
      if (isDotOrEllipsisSurface(tokens.get(i - 1).surface())
          || (i + 1 < n && isDotOrEllipsisSurface(tokens.get(i + 1).surface()))) {
        return false;
      }
    } else if (i + 1 < n) {
      String ns = tokens.get(i + 1).surface();
      if (ns.equals("!") || ns.equals("?")) {
        return false;
      }
    }

    SpanToken prev = tokens.get(i - 1);
    if (s.equals(".")) {
      String ps = prev.surface();
      String pl = prev.lower();
      if (prev.type() == Token.Type.Number
          || prev.type() == Token.Type.URL
          || prev.type() == Token.Type.Email
          || prev.type() == Token.Type.Mention
          || prev.type() == Token.Type.Date
          || prev.type() == Token.Type.Time
          || prev.type() == Token.Type.RomanNumeral
          || prev.containsDigit()
          || ps.indexOf('/') >= 0
          || ps.indexOf(':') >= 0) {
        return false;
      }
      if ((pl.length() == 1 && Character.isLetter(pl.charAt(0)))
          || NON_SENT_END_ABBREVS.contains(pl)) {
        return false;
      }
    }

    int j = i + 1;
    boolean hadQuote = false;
    while (j < n && isClosingQuoteOrBracket(tokens.get(j).surface())) {
      hadQuote = true;
      j++;
    }
    if (j >= n) {
      return false;
    }
    SpanToken nxt = tokens.get(j);
    if (nxt.isImmutable() || nxt.type() == Token.Type.Number || nxt.containsDigit()) {
      return false;
    }
    String nl = nxt.lower();
    boolean hasAlpha = false;
    for (int k = 0; k < nl.length(); k++) {
      if (Character.isLetter(nl.charAt(k))) {
        hasAlpha = true;
        break;
      }
    }
    if (!hasAlpha) {
      return false;
    }
    if (s.equals(".") && DOMAIN_OR_FILE_EXTS.contains(nl)) {
      return false;
    }
    if (j + 2 < n
        && tokens.get(j + 1).surface().equals(".")
        && DOMAIN_OR_FILE_EXTS.contains(tokens.get(j + 2).lower())) {
      return false;
    }
    if (j + 1 < n
        && (tokens.get(j + 1).type() == Token.Type.Mention
            || tokens.get(j + 1).type() == Token.Type.Email)) {
      return false;
    }
    if (hadQuote && QUOTED_DETACHED_SUFFIXES.contains(nl)) {
      return false;
    }
    return true;
  }

  private static boolean[] computeSentenceStarts(
      List<SpanToken> tokens,
      boolean startsCap,
      boolean sentenceAllCaps,
      boolean sentenceTitleNoisy) {
    int n = tokens.size();
    boolean[] out = new boolean[n];

    int firstTrueBound = -1;
    for (int i = 0; i < n - 1; i++) {
      if (tokens.get(i).type() == Token.Type.Punctuation
          && isTrueInternalSentenceBoundary(tokens, i)) {
        firstTrueBound = i;
        break;
      }
    }

    boolean pendingStart = startsCap;
    if (!pendingStart && firstTrueBound > 0) {
      int idx0 = 0;
      while (idx0 < firstTrueBound
          && (tokens.get(idx0).type() == Token.Type.Mention
              || tokens.get(idx0).type() == Token.Type.Emoji
              || tokens.get(idx0).type() == Token.Type.Emoticon)) {
        idx0++;
      }
      if (idx0 < firstTrueBound) {
        SpanToken firstTok = tokens.get(idx0);
        if (!firstTok.isImmutable()
            && firstTok.type() != Token.Type.Number
            && !firstTok.containsDigit()) {
          pendingStart = true;
        }
      }
    }

    boolean afterTrueEndPunct = false;
    boolean afterEndPunct = false;
    boolean softBoundary = false;
    for (int i = 0; i < n; i++) {
      SpanToken t = tokens.get(i);
      if (t.type() == Token.Type.Punctuation) {
        String s = t.surface();
        if (s.equals(".")
            || s.equals("!")
            || s.equals("?")
            || s.equals("..")
            || s.equals("...")
            || s.equals("…")) {
          if (isTrueInternalSentenceBoundary(tokens, i)) {
            afterTrueEndPunct = true;
            afterEndPunct = false;
          } else {
            afterEndPunct = true;
          }
        } else if (s.equals(":")
            || s.equals(";")
            || s.equals("-")
            || s.equals("–")
            || s.equals("—")) {
          softBoundary = true;
        }
        continue;
      }
      if (t.type() == Token.Type.Emoji || t.type() == Token.Type.Emoticon) {
        if (!pendingStart && !afterTrueEndPunct) {
          softBoundary = true;
        }
        continue;
      }
      if (t.isImmutable()) {
        if (t.type() == Token.Type.URL
            || t.type() == Token.Type.Email
            || t.type() == Token.Type.Date
            || t.type() == Token.Type.Time
            || t.type() == Token.Type.PercentNumeral) {
          pendingStart = false;
          afterTrueEndPunct = false;
          afterEndPunct = false;
          softBoundary = false;
        }
        continue;
      }
      if (t.type() == Token.Type.Number || t.containsDigit()) {
        pendingStart = false;
        afterTrueEndPunct = false;
        afterEndPunct = false;
        softBoundary = false;
        continue;
      }
      if (pendingStart) {
        out[i] = true;
        pendingStart = false;
        afterTrueEndPunct = false;
        afterEndPunct = false;
        softBoundary = false;
      } else if (afterTrueEndPunct) {
        out[i] = true;
        afterTrueEndPunct = false;
        afterEndPunct = false;
        softBoundary = false;
      } else if (afterEndPunct) {
        if (sentenceAllCaps
            || (!t.surface().isEmpty() && Character.isUpperCase(t.surface().charAt(0)))) {
          out[i] = true;
        }
        afterEndPunct = false;
        softBoundary = false;
      } else if (softBoundary) {
        if (!sentenceAllCaps
            && !sentenceTitleNoisy
            && !t.surface().isEmpty()
            && Character.isUpperCase(t.surface().charAt(0))) {
          out[i] = true;
        }
        softBoundary = false;
      }
    }
    return out;
  }

  private int[] computeTokenCasingContexts(
      List<SpanToken> tokens, boolean sentenceAllCaps, boolean sentenceTitleNoisy) {
    int n = tokens.size();
    int[] out = new int[n];
    int baseFlags = sentenceTitleNoisy ? ParallelCorpusMiner.CTX_SENT_TITLE_NOISY : 0;
    for (int i = 0; i < n; i++) {
      int flags = baseFlags;
      SpanToken cur = tokens.get(i);
      if (cur.isImmutable()) {
        out[i] = flags;
        continue;
      }
      SpanToken prev = i > 0 ? tokens.get(i - 1) : null;
      SpanToken next = i + 1 < n ? tokens.get(i + 1) : null;
      String curLow = cur.lower();

      if (prev != null && (prev.type() == Token.Type.Number || prev.containsDigit())) {
        flags |= ParallelCorpusMiner.CTX_AFTER_NUMBER;
      }
      if ((prev != null && isCalendarDayOrYearNumber(prev.surface()))
          || (next != null && isCalendarYearNumber(next.surface()))) {
        flags |= ParallelCorpusMiner.CTX_ADJ_DATE_NUM;
      }
      out[i] = flags;
    }
    return out;
  }

  private static boolean isCalendarDayOrYearNumber(String s) {
    if (s == null || s.isEmpty()) {
      return false;
    }
    int start = 0;
    while (start < s.length() && (s.charAt(start) == '(' || s.charAt(start) == '[')) {
      start++;
    }
    int end = start;
    while (end < s.length() && Character.isDigit(s.charAt(end))) {
      end++;
    }
    if (end == start || end - start > 4) {
      return false;
    }
    int v = Integer.parseInt(s.substring(start, end));
    if (!((v >= 1 && v <= 31) || (v >= 1850 && v <= 2099))) {
      return false;
    }
    if (end == s.length() || (end == s.length() - 1 && s.charAt(end) == '.')) {
      return true;
    }
    char sep = s.charAt(end);
    return (sep == '-' || sep == '/') && end + 1 < s.length() && Character.isDigit(s.charAt(end + 1));
  }

  private static boolean isCalendarYearNumber(String s) {
    if (s == null || s.length() < 4) {
      return false;
    }
    String clean =
        (s.endsWith(")") || s.endsWith("]") || s.endsWith("."))
            ? s.substring(0, s.length() - 1)
            : s;
    int ap = Math.max(clean.indexOf('\''), clean.indexOf('’'));
    if (ap == 4) {
      clean = clean.substring(0, 4);
    }
    if (clean.length() != 4 || !isAllDigits(clean)) {
      return false;
    }
    int v = Integer.parseInt(clean);
    return v >= 1850 && v <= 2099;
  }

  private static String buildOrdinalInflected(String digits, String suf) {
    if (digits.isEmpty()) {
      return null;
    }
    char lastDigit = digits.charAt(digits.length() - 1);
    if (digits.endsWith("10")
        || digits.endsWith("30")
        || digits.endsWith("40")
        || digits.endsWith("60")
        || digits.endsWith("90")) {
      lastDigit = 'X';
    }
    String ordBase =
        switch (lastDigit) {
          case '1', '5', '8' -> "inci";
          case '2', '7' -> "nci";
          case '3', '4' -> "üncü";
          case '6' -> "ncı";
          case '9', '0' -> "uncu";
          default -> "uncu";
        };
    if (suf.startsWith("nc")
        || suf.startsWith("ınc")
        || suf.startsWith("inc")
        || suf.startsWith("unc")
        || suf.startsWith("ünc")) {
      return digits + "'" + suf;
    }
    char lv = lastTurkishVowel(ordBase);
    String harmonizedSuf = suf;
    if (suf.equals("da") || suf.equals("de")) {
      harmonizedSuf = isFrontVowel(lv) ? "de" : "da";
    } else if (suf.equals("dan") || suf.equals("den")) {
      harmonizedSuf = isFrontVowel(lv) ? "den" : "dan";
    }
    return digits + "'" + ordBase + harmonizedSuf;
  }

  private static String harmonizeQuestionClitic(String rawQt, char stemVowel) {
    char hv =
        switch (stemVowel) {
          case 'e', 'i' -> 'i';
          case 'ö', 'ü' -> 'ü';
          case 'o', 'u' -> 'u';
          default -> 'ı';
        };
    if (rawQt.length() == 2) {
      return "m" + hv;
    }
    String asc = NormV2Types.toAscii(rawQt);
    if (asc.equals("miyim") || asc.equals("muyum")) {
      return "m" + hv + "y" + hv + "m";
    }
    if (asc.equals("misin") || asc.equals("musun")) {
      return "m" + hv + "s" + hv + "n";
    }
    if (asc.equals("miyiz") || asc.equals("muyuz")) {
      return "m" + hv + "y" + hv + "z";
    }
    if (asc.equals("misiniz") || asc.equals("musunuz")) {
      return "m" + hv + "s" + hv + "n" + hv + "z";
    }
    if (asc.equals("miydi") || asc.equals("muydu")) {
      return "m" + hv + "yd" + hv;
    }
    if (asc.equals("miydim") || asc.equals("muydum")) {
      return "m" + hv + "yd" + hv + "m";
    }
    if (asc.equals("miydin") || asc.equals("muydun")) {
      return "m" + hv + "yd" + hv + "n";
    }
    if (asc.equals("miydik") || asc.equals("muyduk")) {
      return "m" + hv + "yd" + hv + "k";
    }
    if (asc.equals("miydiniz") || asc.equals("muydunuz")) {
      return "m" + hv + "yd" + hv + "n" + hv + "z";
    }
    if (asc.equals("miymis") || asc.equals("muymus")) {
      return "m" + hv + "ym" + hv + "ş";
    }
    return rawQt;
  }

  static boolean stripsExplicitDiacritic(String srcLower, String candLower) {
    if (srcLower.length() != candLower.length()) {
      return false;
    }
    for (int i = 0; i < srcLower.length(); i++) {
      char s = srcLower.charAt(i);
      char c = candLower.charAt(i);
      if ((s == 'ç' && c == 'c')
          || (s == 'ğ' && c == 'g')
          || (s == 'ö' && c == 'o')
          || (s == 'ş' && c == 's')
          || (s == 'ü' && c == 'u')) {
        return true;
      }
    }
    return false;
  }

  static boolean stripsExplicitStemDiacritic(String srcLower, String candSurface) {
    String cl = NormV2Types.trLower(candSurface);
    int n = Math.min(3, Math.min(srcLower.length(), cl.length()));
    for (int i = 0; i < n; i++) {
      char s = srcLower.charAt(i);
      char c = cl.charAt(i);
      if ((s == 'ç' && c == 'c')
          || (s == 'ğ' && c == 'g')
          || (s == 'ö' && c == 'o')
          || (s == 'ş' && c == 's')
          || (s == 'ü' && c == 'u')) {
        return true;
      }
    }
    return false;
  }

  static boolean hasAsciiAmbiguousChars(String lower) {
    for (int i = 0; i < lower.length(); i++) {
      char c = lower.charAt(i);
      if (c == 'c' || c == 'g' || c == 'i' || c == 'o' || c == 's' || c == 'u') {
        return true;
      }
    }
    return false;
  }

  static boolean hasExplicitTurkishDiacritics(String lower) {
    for (int i = 0; i < lower.length(); i++) {
      char c = lower.charAt(i);
      if (c == 'ç' || c == 'ğ' || c == 'ı' || c == 'ö' || c == 'ş' || c == 'ü') {
        return true;
      }
    }
    return false;
  }

  private static boolean containsForeignWxq(String lower) {
    for (int i = 0; i < lower.length(); i++) {
      char c = lower.charAt(i);
      if (c == 'w' || c == 'x' || c == 'q') {
        return true;
      }
    }
    return false;
  }

  private static boolean isDigitsOrPunctOnly(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (Character.isLetter(s.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  static boolean hasRepeatedLetters(String s) {
    return maxRepeatedLetterRun(s) >= 2;
  }

  static int maxRepeatedLetterRun(String s) {
    int maxRun = 1;
    int run = 1;
    for (int i = 1; i < s.length(); i++) {
      if (s.charAt(i) == s.charAt(i - 1) && Character.isLetter(s.charAt(i))) {
        run++;
        if (run > maxRun) {
          maxRun = run;
        }
      } else {
        run = 1;
      }
    }
    return maxRun;
  }

  static String squeezeRuns(String s, int maxRun) {
    StringBuilder sb = new StringBuilder(s.length());
    int run = 0;
    char prev = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (i > 0 && c == prev && Character.isLetter(c)) {
        run++;
        if (run <= maxRun) {
          sb.append(c);
        }
      } else {
        prev = c;
        run = 1;
        sb.append(c);
      }
    }
    return sb.toString();
  }

  static String squeezeTrailingRun(String s) {
    int n = s.length();
    if (n < 2) {
      return s;
    }
    char last = s.charAt(n - 1);
    if (!Character.isLetter(last)) {
      return s;
    }
    int cut = n - 1;
    while (cut > 0 && s.charAt(cut - 1) == last) {
      cut--;
    }
    return cut < n - 1 ? s.substring(0, cut + 1) : s;
  }

  static String squeezeVowelRuns(String s, int maxVowelRun) {
    StringBuilder sb = new StringBuilder(s.length());
    int run = 0;
    char prev = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (i > 0 && c == prev && "aeıioöuü".indexOf(c) >= 0) {
        run++;
        if (run <= maxVowelRun) {
          sb.append(c);
        }
      } else {
        prev = c;
        run = 1;
        sb.append(c);
      }
    }
    return sb.toString();
  }

  static char lastTurkishVowel(String s) {
    for (int i = s.length() - 1; i >= 0; i--) {
      char c = s.charAt(i);
      if ("aeıioöuüâîû".indexOf(c) >= 0) {
        return c;
      }
    }
    return 0;
  }

  static boolean isFrontVowel(char v) {
    return v == 'e' || v == 'i' || v == 'ö' || v == 'ü' || v == 'î';
  }

  private static boolean isAllDigits(String s) {
    if (s.isEmpty()) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      if (!Character.isDigit(s.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static boolean isNumericSurface(String s) {
    int n = s.length();
    if (n == 0 || !Character.isDigit(s.charAt(n - 1))) {
      return false;
    }
    boolean seenDigit = false;
    for (int i = 0; i < n; i++) {
      char c = s.charAt(i);
      if (Character.isDigit(c)) {
        seenDigit = true;
      } else if (c != '.' && c != ',' && c != '%' && c != '/' && c != ':') {
        return false;
      }
    }
    return seenDigit;
  }

  private static boolean isLettersThenDigits(String s) {
    int n = s.length();
    if (n < 2) {
      return false;
    }
    int i = 0;
    while (i < n && Character.isLetter(s.charAt(i))) {
      i++;
    }
    if (i == 0 || i > 4 || i == n) {
      return false;
    }
    while (i < n) {
      if (!Character.isDigit(s.charAt(i))) {
        return false;
      }
      i++;
    }
    return true;
  }

  static int countDiacriticDiffs(String a, String b) {
    if (a.length() != b.length()) {
      return 0;
    }
    int d = 0;
    for (int i = 0; i < a.length(); i++) {
      char ca = a.charAt(i);
      char cb = b.charAt(i);
      if (ca != cb
          && NormV2Types.toAscii(String.valueOf(ca))
              .equals(NormV2Types.toAscii(String.valueOf(cb)))) {
        d++;
      }
    }
    return d;
  }

  /** Fast weighted Levenshtein distance (diacritic diff = 0.15, space/' = 0.20, full = 0.85..0.90). */
  public static float computeWeightedEditCost(String a, String b) {
    if (a.equals(b)) {
      return 0.0f;
    }
    int n = a.length();
    int m = b.length();
    if (Math.abs(n - m) > 8) {
      return Math.abs(n - m);
    }
    float[] prev = new float[m + 1];
    float[] curr = new float[m + 1];
    for (int j = 0; j <= m; j++) {
      prev[j] = j * 0.85f;
    }
    for (int i = 1; i <= n; i++) {
      curr[0] = i * 0.85f;
      char ca = a.charAt(i - 1);
      for (int j = 1; j <= m; j++) {
        char cb = b.charAt(j - 1);
        float subCost;
        if (ca == cb) {
          subCost = 0.0f;
        } else if (TurkishAlphabet.INSTANCE.getAsciiEqual(ca)
            == TurkishAlphabet.INSTANCE.getAsciiEqual(cb)) {
          subCost = 0.15f;
        } else if (cb == '\'' || cb == ' ') {
          subCost = 0.25f;
        } else {
          subCost = 0.90f;
        }
        float insCost = (cb == '\'' || cb == ' ') ? 0.20f : 0.85f;
        float delCost = (ca == '\'' || (i > 1 && ca == a.charAt(i - 2))) ? 0.20f : 0.85f;
        curr[j] =
            Math.min(prev[j - 1] + subCost, Math.min(curr[j - 1] + insCost, prev[j] + delCost));
      }
      float[] tmp = prev;
      prev = curr;
      curr = tmp;
    }
    return prev[m];
  }

  // ===============================================================================================
  // Conversational Shorthand, Compositional Shorthand+Suffix & Scoped >=5-Consonant Abjad Index
  // ===============================================================================================

  private boolean isKnownStrictAbbreviation(String wordLower) {
    for (SingleAnalysis a : strictMorphology.analyze(wordLower)) {
      if (!a.isUnknown()
          && !a.isRuntime()
          && a.getEnding().isEmpty()
          && a.getDictionaryItem().secondaryPos == SecondaryPos.Abbreviation) {
        return true;
      }
    }
    return false;
  }

  private void addShorthandAndAbjadCandidates(
      SpanToken tok,
      boolean capHere,
      boolean sentenceAllCaps,
      Map<String, CandidateDraft> drafts) {
    String low = tok.lower();
    if (low.length() < 2 || low.length() > 16 || low.indexOf('\'') >= 0) {
      return;
    }
    String sqTrail = squeezeTrailingRun(low);
    String sqAll = squeezeRuns(low, 1);
    String[] lookupKeys =
        sqTrail.equals(low)
            ? new String[] {low}
            : (sqAll.equals(sqTrail)
                ? new String[] {low, sqTrail}
                : new String[] {low, sqTrail, sqAll});
    for (String key : lookupKeys) {
      String exactTarget = CURATED_CHAT_SHORTHANDS.get(key);
      if (exactTarget != null && !exactTarget.equals(low)) {
        emitShorthandCandidate(
            tok,
            capHere,
            sentenceAllCaps,
            drafts,
            exactTarget,
            NormV2Types.SRC_SHORTHAND_ABJAD
                | (exactTarget.indexOf(' ') >= 0
                    ? NormV2Types.SRC_CLITIC_SPLIT
                    : NormV2Types.SRC_FORMAL_MORPH)
                | (!key.equals(low) ? NormV2Types.SRC_ELONGATION : 0),
            0.94f);
      }
    }
  }

  private void emitShorthandCandidate(
      SpanToken tok,
      boolean capHere,
      boolean sentenceAllCaps,
      Map<String, CandidateDraft> drafts,
      String targetLowerOrCased,
      int srcMask,
      float precision) {
    String normLow = NormV2Types.trLower(targetLowerOrCased);
    String surf;
    if (targetLowerOrCased.indexOf(' ') >= 0) {
      if (sentenceAllCaps) {
        surf = NormV2Types.trUpper(targetLowerOrCased);
      } else if (Character.isUpperCase(targetLowerOrCased.charAt(0))) {
        surf = targetLowerOrCased;
      } else {
        surf = capHere ? NormV2Types.trCapitalize(targetLowerOrCased) : targetLowerOrCased;
      }
    } else {
      surf =
          mined.truecase(
              normLow, tok.shape(), tok.surface(), capHere, sentenceAllCaps, strictMorphology);
    }
    addOrMergeDraft(
        drafts,
        tok,
        surf,
        normLow,
        srcMask,
        0.0f,
        0.0f,
        precision,
        computeWeightedEditCost(tok.lower(), normLow),
        countDiacriticDiffs(tok.lower(), normLow));
  }

  static Map<String, String> loadCuratedChatShorthands() {
    Map<String, String> m = new LinkedHashMap<>(160);
    try {
      java.io.InputStream in =
          SpanCandidateGenerator.class.getResourceAsStream("/normalization/v2/shorthands.tsv");
      if (in == null) {
        java.nio.file.Path fallback =
            java.nio.file.Paths.get(
                "/home/dndara/work/zemberek-nlp/normalization/src/main/resources/normalization/v2/shorthands.tsv");
        if (java.nio.file.Files.exists(fallback)) {
          in = java.nio.file.Files.newInputStream(fallback);
        }
      }
      if (in != null) {
        try (java.io.BufferedReader br =
            new java.io.BufferedReader(
                new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
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
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    return Map.copyOf(m);
  }
}
