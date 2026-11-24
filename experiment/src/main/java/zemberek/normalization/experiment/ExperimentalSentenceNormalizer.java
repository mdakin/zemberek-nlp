package zemberek.normalization.experiment;

import com.google.common.base.Splitter;
import com.google.common.collect.ArrayListMultimap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import zemberek.core.text.TextIO;
import zemberek.core.turkish.PrimaryPos;
import zemberek.core.turkish.SecondaryPos;
import zemberek.core.turkish.Turkish;
import zemberek.core.turkish.TurkishAlphabet;
import zemberek.lm.LmVocabulary;
import zemberek.lm.compression.SmoothLm;
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.analysis.AnalysisCache;
import zemberek.morphology.analysis.InformalAnalysisConverter;
import zemberek.morphology.analysis.SingleAnalysis;
import zemberek.morphology.analysis.WordAnalysis;
import zemberek.morphology.analysis.WordAnalysisSurfaceFormatter;
import zemberek.morphology.generator.WordGenerator;
import zemberek.normalization.CharacterGraphDecoder;
import zemberek.normalization.StemEndingGraph;
import zemberek.normalization.TurkishSpellChecker;
import zemberek.normalization.deasciifier.latticedeasciifier.LatticeDeasciifier;
import zemberek.tokenization.Token;
import zemberek.tokenization.Token.Type;
import zemberek.tokenization.TurkishTokenizer;

/**
 * Clean experimental Turkish sentence normalizer prototype supporting modular ablation of speed and
 * accuracy improvements without modifying existing classes in {@code zemberek-normalization}.
 */
public final class ExperimentalSentenceNormalizer {

  public static final class Config {
    public boolean useCompactSpellChecker = true;
    public boolean useSinglePassPipeline = true;
    public boolean useNoisyChannelScoring = true;
    public boolean useRichTurkishNoiseRules = true;
    public boolean preserveCaseAndPunctuation = false;

    public static Config baselineEquivalent() {
      Config c = new Config();
      c.useCompactSpellChecker = false;
      c.useSinglePassPipeline = false;
      c.useNoisyChannelScoring = false;
      c.useRichTurkishNoiseRules = false;
      c.preserveCaseAndPunctuation = false;
      return c;
    }

    public Config copy() {
      Config c = new Config();
      c.useCompactSpellChecker = this.useCompactSpellChecker;
      c.useSinglePassPipeline = this.useSinglePassPipeline;
      c.useNoisyChannelScoring = this.useNoisyChannelScoring;
      c.useRichTurkishNoiseRules = this.useRichTurkishNoiseRules;
      c.preserveCaseAndPunctuation = this.preserveCaseAndPunctuation;
      return c;
    }
  }

  private enum CaseStyle {
    LOWER,
    TITLE,
    UPPER
  }

  private static final class SpanToken {
    String text;
    Type type;
    CaseStyle caseStyle;
    boolean spaceBefore;
    boolean wasOriginallyCapitalizedMidSentence;

    SpanToken(
        String text,
        Type type,
        CaseStyle caseStyle,
        boolean spaceBefore,
        boolean wasOriginallyCapitalizedMidSentence) {
      this.text = text;
      this.type = type;
      this.caseStyle = caseStyle;
      this.spaceBefore = spaceBefore;
      this.wasOriginallyCapitalizedMidSentence = wasOriginallyCapitalizedMidSentence;
    }
  }

  private static final class ScoredCand {
    final String content;
    final int firstLmIdx;
    final int lastLmIdx;
    final float internalLmLogProb;
    final float channelScore;

    ScoredCand(
        String content,
        int firstLmIdx,
        int lastLmIdx,
        float internalLmLogProb,
        float channelScore) {
      this.content = content;
      this.firstLmIdx = firstLmIdx;
      this.lastLmIdx = lastLmIdx;
      this.internalLmLogProb = internalLmLogProb;
      this.channelScore = channelScore;
    }
  }

  // Destructive over-normalization pairs in legacy candidates-manual
  private static final Set<String> UNSAFE_MANUAL_KEYS =
      Set.of("duydum", "sordu", "amin", "michael", "los", "sam");

  private static final Map<String, String> COLLOQUIAL_SINGLE_OR_MULTI = buildColloquialMap();

  private final TurkishMorphology morphology;
  private final TurkishMorphology informalAsciiMorphology;
  private final TurkishMorphology informalStrictMorphology;
  private final InformalAnalysisConverter analysisConverter;
  private final WordAnalysisSurfaceFormatter surfaceFormatter = new WordAnalysisSurfaceFormatter();
  private final SmoothLm lm;
  private final LatticeDeasciifier latticeDeasciifier;

  private final CompactStemEndingSpellChecker compactSpellChecker;
  private final TurkishSpellChecker legacySpellChecker;

  private final ArrayListMultimap<String, String> lookupManual;
  private final ArrayListMultimap<String, String> lookupFromGraph;
  private final ArrayListMultimap<String, String> lookupFromAscii;
  private final Map<String, String> commonSplits = new HashMap<>();
  private final Map<String, String> replacements = new HashMap<>();
  private final Set<String> commonConnectedSuffixes = new HashSet<>();
  private final Set<String> noSplitWords = new HashSet<>();

  private Config config;

  public ExperimentalSentenceNormalizer(
      TurkishMorphology morphology,
      Path dataRoot,
      Path lmPath,
      Config config)
      throws IOException {
    this(
        morphology,
        SmoothLm.builder(lmPath).logBase(Math.E).build(),
        new CompactStemEndingSpellChecker(morphology, true),
        null,
        dataRoot,
        config);
  }

  public ExperimentalSentenceNormalizer(
      TurkishMorphology morphology,
      SmoothLm sharedLm,
      CompactStemEndingSpellChecker sharedCompactSpell,
      TurkishSpellChecker sharedLegacySpell,
      Path dataRoot,
      Config config)
      throws IOException {
    this.morphology = morphology;
    this.lm = sharedLm;
    this.config = config.copy();
    this.analysisConverter = new InformalAnalysisConverter(morphology.getWordGenerator());

    AnalysisCache smallCache1 = AnalysisCache.builder().dynamicCacheSize(16_384, 32_768).build();
    this.informalAsciiMorphology =
        TurkishMorphology.builder()
            .setLexicon(morphology.getLexicon())
            .setCache(smallCache1)
            .useInformalAnalysis()
            .ignoreDiacriticsInAnalysis()
            .build();

    AnalysisCache smallCache2 = AnalysisCache.builder().dynamicCacheSize(16_384, 32_768).build();
    this.informalStrictMorphology =
        TurkishMorphology.builder()
            .setLexicon(morphology.getLexicon())
            .setCache(smallCache2)
            .useInformalAnalysis()
            .build();

    this.latticeDeasciifier =
        LatticeDeasciifier.createWithDefaults(this.morphology, this.informalAsciiMorphology);

    this.compactSpellChecker = sharedCompactSpell;
    if (sharedLegacySpell != null) {
      this.legacySpellChecker = sharedLegacySpell;
    } else if (!config.useCompactSpellChecker) {
      TurkishSpellChecker base = new TurkishSpellChecker(morphology);
      this.legacySpellChecker =
          new TurkishSpellChecker(
              morphology,
              base.getDecoder(),
              CharacterGraphDecoder.DIACRITICS_IGNORING_MATCHER);
    } else {
      this.legacySpellChecker = null;
    }

    this.lookupFromGraph = loadMultiMap(Files.readAllLines(dataRoot.resolve("lookup-from-graph"), StandardCharsets.UTF_8));
    this.lookupFromAscii = loadMultiMap(Files.readAllLines(dataRoot.resolve("ascii-map"), StandardCharsets.UTF_8));
    this.lookupManual = loadMultiMap(TextIO.loadLinesFromResource("normalization/candidates-manual"));
    this.lookupManual.keySet().forEach(this.lookupFromGraph::removeAll);

    for (String splitLine : Files.readAllLines(dataRoot.resolve("split"), StandardCharsets.UTF_8)) {
      int eq = splitLine.indexOf('=');
      if (eq > 0) {
        commonSplits.put(splitLine.substring(0, eq).trim(), splitLine.substring(eq + 1).trim());
      }
    }

    this.commonConnectedSuffixes.addAll(
        TextIO.loadLinesFromResource("normalization/question-suffixes"));
    this.commonConnectedSuffixes.addAll(Arrays.asList("de", "da", "ki"));
    this.noSplitWords.addAll(TextIO.loadLinesFromResource("normalization/no-split"));

    for (String replaceLine :
        TextIO.loadLinesFromResource("normalization/multi-word-replacements")) {
      int eq = replaceLine.indexOf('=');
      if (eq > 0) {
        String k = replaceLine.substring(0, eq).trim();
        String v = replaceLine.substring(eq + 1).trim();
        if ("naparsak".equals(k) && "ne yaparasak".equals(v)) {
          v = "ne yaparsak";
        }
        replacements.put(k, v);
      }
    }
  }

  public void setConfig(Config config) {
    this.config = config.copy();
  }

  public Config getConfig() {
    return config.copy();
  }

  public String normalize(String sentence) {
    if (sentence == null || sentence.trim().isEmpty()) {
      return sentence;
    }

    boolean asciiLike = isAsciiLikeSentence(sentence);
    String workingSentence = sentence;

    // 1. Pre-squeeze extreme expressive elongations BEFORE deasciification if enabled, so
    // LatticeDeasciifier sees canonical stem shapes (e.g. "coooook guzel" -> "cok guzel" -> "çok güzel")!
    if (config.useRichTurkishNoiseRules && hasTripleOrDoubleRun(workingSentence)) {
      workingSentence = squeezeElongationsInSentence(workingSentence);
    }

    if (asciiLike) {
      workingSentence = latticeDeasciifier.deasciifyKnownWords(workingSentence);
    }

    List<SpanToken> stream =
        config.useSinglePassPipeline
            ? preProcessSinglePass(workingSentence, asciiLike)
            : preProcessLegacyStyle(workingSentence, asciiLike);

    if (stream.isEmpty()) {
      return "";
    }

    LmVocabulary vocab = lm.getVocabulary();
    List<List<ScoredCand>> lattice = new ArrayList<>(stream.size());

    for (int i = 0; i < stream.size(); i++) {
      SpanToken tok = stream.get(i);
      String current = tok.text;
      String prevText = i == 0 ? null : stream.get(i - 1).text;
      String nextText = i == stream.size() - 1 ? null : stream.get(i + 1).text;

      List<ScoredCand> tokenCands =
          buildCandidatesForToken(tok, prevText, nextText, asciiLike, vocab);
      lattice.add(tokenCands);
    }

    List<String> bestWords = decodeTrellisViterbi(lattice, vocab);
    if (!config.preserveCaseAndPunctuation) {
      return String.join(" ", bestWords);
    }
    return formatPreservingCaseAndPunctuation(stream, bestWords);
  }

  private List<ScoredCand> buildCandidatesForToken(
      SpanToken tok,
      String prevText,
      String nextText,
      boolean asciiLike,
      LmVocabulary vocab) {
    String current = tok.text;

    // Fast path for non-words (punctuation, numbers, URLs, emoticons, etc.)
    if (!isWordType(tok.type)) {
      return Collections.singletonList(makeSingleCand(current, 0.0f, vocab));
    }

    // Fast path in SinglePass mode: if sentence is already properly accented (!asciiLike), the
    // word is not in manual/graph/colloquial lookups, has no informal suffix cues, and has a
    // clean regular formal morphological parse, skip WordGenerator & spellcheck altogether!
    boolean hasManualKey =
        lookupManual.containsKey(current)
            && (!config.useNoisyChannelScoring || !UNSAFE_MANUAL_KEYS.contains(current));
    boolean hasGraphKey = lookupFromGraph.containsKey(current);
    boolean hasColloquial =
        config.useRichTurkishNoiseRules && COLLOQUIAL_SINGLE_OR_MULTI.containsKey(current);

    WordAnalysis formalAnalysis = morphology.analyze(current);
    boolean regularFormal = hasRegularAnalysis(formalAnalysis);
    boolean suspiciousOnlyParse =
        config.useRichTurkishNoiseRules
            && !regularFormal
            && formalAnalysis.analysisCount() > 0
            && isObscureAbbrvOrShortProperOnly(
                current, formalAnalysis, tok.wasOriginallyCapitalizedMidSentence);

    if (config.useSinglePassPipeline
        && !asciiLike
        && regularFormal
        && !hasManualKey
        && !hasGraphKey
        && !hasColloquial
        && !mightNeedCliticDeDaSplit(current, prevText, nextText)) {
      return Collections.singletonList(makeSingleCand(current, 0.0f, vocab));
    }

    Map<String, Float> candScores = new LinkedHashMap<>(6);

    // 1. High-precision colloquial / phonetic lexicon (Exp 4)
    if (hasColloquial) {
      addCandidate(candScores, COLLOQUIAL_SINGLE_OR_MULTI.get(current), -0.15f);
    }

    // 2. Manual lookup
    if (hasManualKey) {
      for (String m : lookupManual.get(current)) {
        if (config.useNoisyChannelScoring && "sizi".equals(current) && "size".equals(m)) {
          addCandidate(candScores, m, -2.4f);
        } else {
          float pen = m.equals(current) ? 0.0f : (TurkishAlphabet.INSTANCE.equalsIgnoreDiacritics(current, m) ? -0.6f : -0.4f);
          addCandidate(candScores, m, pen);
        }
      }
    }

    // 3. Random-walk graph lookup
    if (hasGraphKey) {
      for (String g : lookupFromGraph.get(current)) {
        float pen = g.equals(current) ? 0.0f : -1.10f;
        addCandidate(candScores, g, pen);
      }
    }

    // 4. ASCII lookup (only trusted strongly when sentence is ASCII-like or word has no formal parse)
    if (asciiLike || !regularFormal) {
      for (String a : lookupFromAscii.get(current)) {
        float pen = asciiLike ? -0.45f : -1.80f;
        addCandidate(candScores, a, pen);
      }
    }

    // 5. Informal & formal morphological analysis -> surface generation
    WordAnalysis infAnalyses =
        (asciiLike || !config.useSinglePassPipeline || !regularFormal)
            ? informalAsciiMorphology.analyze(current)
            : informalStrictMorphology.analyze(current);

    String normalizedCurrent = TurkishMorphology.normalizeForAnalysis(current).replace("'", "");
    boolean anyValidMorphSurface = false;

    for (SingleAnalysis sa : infAnalyses) {
      if (sa.isUnknown() || sa.isRuntime()) {
        continue;
      }
      if (config.useRichTurkishNoiseRules
          && isSuspiciousSingleAnalysis(current, sa, tok.wasOriginallyCapitalizedMidSentence)) {
        continue;
      }
      if (sa.containsInformalMorpheme()) {
        WordGenerator.Result res = analysisConverter.convert(current, sa);
        if (res != null) {
          anyValidMorphSurface = true;
          addCandidate(candScores, res.surface, -0.25f);
        }
      } else {
        List<WordGenerator.Result> results =
            morphology.getWordGenerator().generate(sa.getDictionaryItem(), sa.getMorphemes());
        for (WordGenerator.Result res : results) {
          if (TurkishAlphabet.INSTANCE.equalsIgnoreDiacritics(normalizedCurrent, res.surface)) {
            anyValidMorphSurface = true;
            float pen;
            if (res.surface.equals(current)) {
              pen = 0.0f;
            } else {
              // Strong protection (-2.10f) ONLY when the input already has a valid common-word parse;
              // when the input has no regular common-word parse (e.g. isolated missing diacritic in
              // "komsu", "tuslarla", "baska", "tusa"), allow diacritic restoration with mild cost.
              pen = (asciiLike || !regularFormal) ? -0.35f : -2.10f;
            }
            addCandidate(candScores, res.surface, pen);
          }
        }
      }
    }

    // 6. Extended Turkish morpho-phonological rules (Exp 4):
    //    -Io progressive ("çalışıom" -> "çalışıyorum"), -Abil/-Fut syncope ("buluşabilceğimiz"),
    //    locative/ablative haplology ("burdaki" -> "buradaki", "nerden" -> "nereden"),
    //    and residual 2->1 letter elongation squeezing ("merabaa" -> "merhaba")
    if (config.useRichTurkishNoiseRules && !regularFormal) {
      for (String restored : generateExtendedMorphoPhonologicalCandidates(current)) {
        anyValidMorphSurface = true;
        addCandidate(candScores, restored, -0.35f);
      }
    }

    // 7. Contextual clitic -de/-da split candidate when syntactically & information-theoretically justified
    if (config.useRichTurkishNoiseRules && mightNeedCliticDeDaSplit(current, prevText, nextText)) {
      String head = current.substring(0, current.length() - 2);
      String tail = current.substring(current.length() - 2);
      if (shouldOfferCliticSplit(current, head, tail, prevText, nextText, vocab)) {
        int tailIdx = vocab.indexOf(tail);
        // Compensate by -logP(de/da) so the extra transition contributes PMI(head; de/da) - 2.0f
        float pmiOffset = -lm.getUnigramProbability(tailIdx) - 2.0f;
        addCandidate(candScores, head + " " + tail, pmiOffset);
      }
    }

    // 8. Edit-distance 1 spell-checker fallback (also triggered when formal parse was only an
    //    obscure un-apostrophized Abbrv/ProperNoun or all candidates so far are LM-unknown)
    boolean allCandsUnknownInLm =
        !candScores.isEmpty() && areAllCandidatesUnknownInLm(candScores.keySet(), vocab);
    boolean shouldConsultSpellChecker =
        !regularFormal
            && current.length() > 3
            && !tok.wasOriginallyCapitalizedMidSentence
            && (candScores.isEmpty() || suspiciousOnlyParse || allCandsUnknownInLm);
    if (shouldConsultSpellChecker) {
      if (config.useCompactSpellChecker) {
        List<CompactStemEndingSpellChecker.ScoredCandidate> scList =
            compactSpellChecker.suggestScored(current);
        float editW = config.useNoisyChannelScoring ? 2.2f : 0.0f;
        List<String> topRanked =
            rankSpellCandidates(scList, prevText, nextText, asciiLike, editW, 3);
        for (String s : topRanked) {
          CompactStemEndingSpellChecker.ScoredCandidate matched = findScored(scList, s);
          float dist = matched != null ? matched.totalDistanceCost(asciiLike) : 1.0f;
          addCandidate(candScores, s, -1.6f - 1.2f * dist);
        }
      } else if (legacySpellChecker != null) {
        List<String> spellCands =
            legacySpellChecker.suggestForWord(current, prevText, nextText, lm);
        int limit = Math.min(3, spellCands.size());
        for (int k = 0; k < limit; k++) {
          addCandidate(candScores, spellCands.get(k), -2.4f);
        }
      }
    }

    // 9. Preserve original word if valid formal Turkish (unless solely an obscure un-apostrophized Abbrv/Prop)
    //    or if no candidates were found at all.
    if (candScores.isEmpty()) {
      addCandidate(candScores, current, 0.0f);
    } else if (regularFormal) {
      addCandidate(candScores, current, 0.0f);
    } else if (formalAnalysis.isCorrect() && !suspiciousOnlyParse) {
      addCandidate(candScores, current, -0.30f);
    } else if (formalAnalysis.isCorrect() && suspiciousOnlyParse) {
      addCandidate(candScores, current, -3.20f);
    }

    List<ScoredCand> out = new ArrayList<>(candScores.size());
    for (Map.Entry<String, Float> e : candScores.entrySet()) {
      float chScore = config.useNoisyChannelScoring ? e.getValue() : 0.0f;
      out.add(makeCand(e.getKey(), chScore, vocab));
    }
    return out;
  }

  private void addCandidate(Map<String, Float> map, String surface, float channelScore) {
    if (surface == null || surface.isEmpty()) {
      return;
    }
    Float prev = map.get(surface);
    if (prev == null || channelScore > prev) {
      map.put(surface, channelScore);
    }
  }

  private ScoredCand makeSingleCand(String content, float channelScore, LmVocabulary vocab) {
    int idx = vocab.indexOf(content);
    return new ScoredCand(content, idx, idx, 0.0f, channelScore);
  }

  private ScoredCand makeCand(String content, float channelScore, LmVocabulary vocab) {
    int sp = content.indexOf(' ');
    if (sp < 0) {
      int idx = vocab.indexOf(content);
      return new ScoredCand(content, idx, idx, 0.0f, channelScore);
    }
    String[] parts = content.split(" ");
    int firstIdx = vocab.indexOf(parts[0]);
    int prevIdx = firstIdx;
    float internal = 0.0f;
    for (int i = 1; i < parts.length; i++) {
      int curIdx = vocab.indexOf(parts[i]);
      internal += lm.getProbability(prevIdx, curIdx);
      prevIdx = curIdx;
    }
    return new ScoredCand(content, firstIdx, prevIdx, internal, channelScore);
  }

  /**
   * Fast, allocation-minimal trellis Viterbi decoder over candidate lists.
   */
  private List<String> decodeTrellisViterbi(List<List<ScoredCand>> lattice, LmVocabulary vocab) {
    int n = lattice.size();
    int startIdx = vocab.indexOf(vocab.getSentenceStart());
    int endIdx = vocab.indexOf(vocab.getSentenceEnd());
    int order = lm.getOrder();

    if (order == 2) {
      float[][] dp = new float[n][];
      int[][] back = new int[n][];

      List<ScoredCand> firstCol = lattice.get(0);
      dp[0] = new float[firstCol.size()];
      back[0] = new int[firstCol.size()];
      for (int c = 0; c < firstCol.size(); c++) {
        ScoredCand cand = firstCol.get(c);
        dp[0][c] =
            lm.getProbability(startIdx, cand.firstLmIdx)
                + cand.internalLmLogProb
                + cand.channelScore;
        back[0][c] = -1;
      }

      for (int i = 1; i < n; i++) {
        List<ScoredCand> prevCol = lattice.get(i - 1);
        List<ScoredCand> curCol = lattice.get(i);
        int curSize = curCol.size();
        int prevSize = prevCol.size();
        dp[i] = new float[curSize];
        back[i] = new int[curSize];

        for (int c = 0; c < curSize; c++) {
          ScoredCand cur = curCol.get(c);
          float bestScore = Float.NEGATIVE_INFINITY;
          int bestPrev = 0;
          for (int p = 0; p < prevSize; p++) {
            float trans = lm.getProbability(prevCol.get(p).lastLmIdx, cur.firstLmIdx);
            float total = dp[i - 1][p] + trans + cur.internalLmLogProb + cur.channelScore;
            if (total > bestScore) {
              bestScore = total;
              bestPrev = p;
            }
          }
          dp[i][c] = bestScore;
          back[i][c] = bestPrev;
        }
      }

      List<ScoredCand> lastCol = lattice.get(n - 1);
      float bestFinal = Float.NEGATIVE_INFINITY;
      int bestIdx = 0;
      for (int c = 0; c < lastCol.size(); c++) {
        float s = dp[n - 1][c] + lm.getProbability(lastCol.get(c).lastLmIdx, endIdx);
        if (s > bestFinal) {
          bestFinal = s;
          bestIdx = c;
        }
      }

      String[] chosen = new String[n];
      int cur = bestIdx;
      for (int i = n - 1; i >= 0; i--) {
        chosen[i] = lattice.get(i).get(cur).content;
        cur = back[i][cur];
      }
      return Arrays.asList(chosen);
    }

    // Fallback for order != 2
    List<String> greedy = new ArrayList<>(n);
    int prev = startIdx;
    for (List<ScoredCand> col : lattice) {
      float best = Float.NEGATIVE_INFINITY;
      ScoredCand bestCand = col.get(0);
      for (ScoredCand cand : col) {
        float s =
            (order == 1
                    ? lm.getUnigramProbability(cand.firstLmIdx)
                    : lm.getProbability(prev, cand.firstLmIdx))
                + cand.internalLmLogProb
                + cand.channelScore;
        if (s > best) {
          best = s;
          bestCand = cand;
        }
      }
      greedy.add(bestCand.content);
      prev = bestCand.lastLmIdx;
    }
    return greedy;
  }

  /**
   * Single-pass token-stream preprocessor: tokenizes once, tracks original casing & punctuation
   * spacing, applies replacements, combines detached suffixes/compounds, and splits attached
   * clitics without repeated String.join + re-tokenization cycles.
   */
  private List<SpanToken> preProcessSinglePass(String sentence, boolean alreadyDeasciified) {
    List<Token> rawTokens = TurkishTokenizer.DEFAULT.tokenize(sentence);
    if (rawTokens.isEmpty()) {
      return Collections.emptyList();
    }

    boolean mixedCaseSentence = hasAnyLowercaseLetter(sentence);
    List<SpanToken> stream = new ArrayList<>(rawTokens.size() + 4);
    int prevEnd = -1;
    boolean atSentenceStart = true;

    for (Token tok : rawTokens) {
      String rawText = tok.getText();
      boolean spaceBefore = prevEnd >= 0 && tok.getStart() > prevEnd;
      prevEnd = tok.getEnd() + 1;

      CaseStyle style = detectCaseStyle(rawText);
      boolean midSentenceCap =
          mixedCaseSentence
              && !atSentenceStart
              && isWordType(tok.getType())
              && !rawText.isEmpty()
              && Character.isUpperCase(rawText.charAt(0));

      if (isWordType(tok.getType())) {
        atSentenceStart = false;
      } else if (isSentenceBoundary(rawText)) {
        atSentenceStart = true;
      }

      String lower = rawText.toLowerCase(Turkish.LOCALE);
      String rep = replacements.get(lower);
      if (rep == null && config.useRichTurkishNoiseRules) {
        String multi = COLLOQUIAL_SINGLE_OR_MULTI.get(lower);
        if (multi != null && multi.indexOf(' ') > 0) {
          rep = multi;
        }
      }
      if (rep != null) {
        appendPossiblyMultiWord(stream, rep, style, spaceBefore, false);
      } else {
        stream.add(new SpanToken(lower, tok.getType(), style, spaceBefore, midSentenceCap));
      }
    }

    // Pass A: Combine split suffixes, apostrophe tails, number suffixes, and solid compounds
    stream = combineStreamTokens(stream);

    // Pass B: Split attached question clitics, conjunctions, and common split table entries
    stream = splitStreamTokens(stream, true, alreadyDeasciified);

    return stream;
  }

  private List<SpanToken> combineStreamTokens(List<SpanToken> in) {
    if (in.size() < 2) {
      return in;
    }
    List<SpanToken> out = new ArrayList<>(in.size());
    int i = 0;
    while (i < in.size()) {
      if (i + 1 < in.size()) {
        SpanToken first = in.get(i);
        SpanToken second = in.get(i + 1);
        // Handle detached suffixes after numbers ("12 de" -> "12'de", "10 ar" -> "10'ar")
        if (config.useRichTurkishNoiseRules
            && first.type == Type.Number
            && isWordType(second.type)
            && isLikelyDetachedNumberSuffix(second.text)) {
          String withApos = first.text + "'" + second.text;
          if (morphology.analyze(withApos).isCorrect()) {
            out.add(
                new SpanToken(
                    withApos, Type.WordWithSymbol, first.caseStyle, first.spaceBefore, false));
            i += 2;
            continue;
          }
        }
        if (isWordType(first.type) && isWordType(second.type)) {
          String combined = combineCommon(first.text, second.text);
          if (!combined.isEmpty()) {
            out.add(
                new SpanToken(
                    combined,
                    combined.indexOf('\'') > 0 ? Type.WordWithSymbol : Type.Word,
                    first.caseStyle,
                    first.spaceBefore,
                    first.wasOriginallyCapitalizedMidSentence));
            i += 2;
            continue;
          }
        }
      }
      out.add(in.get(i));
      i++;
    }
    return out;
  }

  private List<SpanToken> splitStreamTokens(
      List<SpanToken> in, boolean useLookup, boolean deasciifySplitPieces) {
    List<SpanToken> out = new ArrayList<>(in.size() + 4);
    for (SpanToken tok : in) {
      if (!isWordType(tok.type)) {
        out.add(tok);
        continue;
      }
      String split = separateCommon(tok.text, useLookup);
      if (!split.equals(tok.text)) {
        if (deasciifySplitPieces && isAsciiLikeSentence(split)) {
          split = latticeDeasciifier.deasciifyKnownWords(split).toLowerCase(Turkish.LOCALE);
        }
        appendPossiblyMultiWord(
            out, split, tok.caseStyle, tok.spaceBefore, tok.wasOriginallyCapitalizedMidSentence);
      } else {
        out.add(tok);
      }
    }
    return out;
  }

  private void appendPossiblyMultiWord(
      List<SpanToken> out,
      String text,
      CaseStyle firstStyle,
      boolean firstSpaceBefore,
      boolean midSentenceCap) {
    int sp = text.indexOf(' ');
    if (sp < 0) {
      out.add(new SpanToken(text, Type.Word, firstStyle, firstSpaceBefore, midSentenceCap));
      return;
    }
    String[] parts = text.split(" ");
    for (int idx = 0; idx < parts.length; idx++) {
      if (parts[idx].isEmpty()) {
        continue;
      }
      CaseStyle st =
          (idx == 0)
              ? firstStyle
              : (firstStyle == CaseStyle.UPPER ? CaseStyle.UPPER : CaseStyle.LOWER);
      boolean sb = (idx == 0) ? firstSpaceBefore : true;
      out.add(new SpanToken(parts[idx], Type.Word, st, sb, idx == 0 && midSentenceCap));
    }
  }

  /**
   * Reproduces the legacy multi-tokenization preProcess flow for clean speed ablation against
   * {@link #preProcessSinglePass}.
   */
  private List<SpanToken> preProcessLegacyStyle(String sentence, boolean deasciified) {
    String lower = sentence.toLowerCase(Turkish.LOCALE);
    List<Token> tokens = TurkishTokenizer.DEFAULT.tokenize(lower);
    List<String> r1 = new ArrayList<>(tokens.size());
    for (Token t : tokens) {
      r1.add(replacements.getOrDefault(t.getText(), t.getText()));
    }
    String s = String.join(" ", r1);
    tokens = TurkishTokenizer.DEFAULT.tokenize(s);
    s = combineLegacy(tokens);
    tokens = TurkishTokenizer.DEFAULT.tokenize(s);
    s = splitLegacy(tokens, false);
    if (deasciified && !s.equals(lower)) {
      s = latticeDeasciifier.deasciifyKnownWords(s).toLowerCase(Turkish.LOCALE);
    }
    tokens = TurkishTokenizer.DEFAULT.tokenize(s);
    s = combineLegacy(tokens);
    tokens = TurkishTokenizer.DEFAULT.tokenize(s);
    s = splitLegacy(tokens, true);
    tokens = TurkishTokenizer.DEFAULT.tokenize(s);

    List<SpanToken> out = new ArrayList<>(tokens.size());
    for (int i = 0; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      out.add(new SpanToken(t.getText(), t.getType(), CaseStyle.LOWER, i > 0, false));
    }
    return out;
  }

  private String combineLegacy(List<Token> tokens) {
    if (tokens.isEmpty()) {
      return "";
    }
    List<String> res = new ArrayList<>(tokens.size());
    boolean combined = false;
    for (int i = 0; i < tokens.size() - 1; i++) {
      Token f = tokens.get(i);
      Token sec = tokens.get(i + 1);
      if (!isWordType(f.getType()) || !isWordType(sec.getType())) {
        combined = false;
        res.add(f.getText());
        continue;
      }
      if (combined) {
        combined = false;
        continue;
      }
      String c = combineCommon(f.getText(), sec.getText());
      if (!c.isEmpty()) {
        res.add(c);
        combined = true;
      } else {
        res.add(f.getText());
        combined = false;
      }
    }
    if (!combined) {
      res.add(tokens.get(tokens.size() - 1).getText());
    }
    return String.join(" ", res);
  }

  private String splitLegacy(List<Token> tokens, boolean useLookup) {
    List<String> res = new ArrayList<>(tokens.size());
    for (Token t : tokens) {
      res.add(isWordType(t.getType()) ? separateCommon(t.getText(), useLookup) : t.getText());
    }
    return String.join(" ", res);
  }

  private String combineCommon(String i1, String i2) {
    String combined = i1 + i2;
    if (i2.startsWith("'") || i2.startsWith("bil") || isAlwaysSolidCompound(i1, i2)) {
      if (hasAnyNonUnknownAnalysis(morphology.analyze(combined))) {
        return combined;
      }
    }
    if (!hasRegularAnalysis(morphology.analyze(i2))) {
      if (hasAnyNonUnknownAnalysis(morphology.analyze(combined))) {
        return combined;
      }
    }
    return "";
  }

  private static boolean isAlwaysSolidCompound(String i1, String i2) {
    return (("uluslar".equals(i1) || "milletler".equals(i1)) && i2.startsWith("arası"))
        || ("hiç".equals(i1) && i2.startsWith("bir"))
        || ("bir".equals(i1) && i2.startsWith("kaç"))
        || ("her".equals(i1) && i2.startsWith("hangi"))
        || ("bir".equals(i1) && i2.startsWith("çok"))
        || ("vaz".equals(i1) && i2.startsWith("geç"));
  }

  private String separateCommon(String input, boolean useLookup) {
    if (noSplitWords.contains(input)) {
      return input;
    }
    if (useLookup && commonSplits.containsKey(input)) {
      return commonSplits.get(input);
    }
    if (!hasRegularAnalysis(morphology.analyze(input))) {
      for (int i = 1; i < input.length() - 1; i++) {
        String tail = input.substring(i);
        String normalizedTail = normalizeQuestionTailTypo(tail);
        if (normalizedTail != null && commonConnectedSuffixes.contains(normalizedTail)) {
          String head = input.substring(0, i);
          WordAnalysis headAnalysis = morphology.analyze(head);
          if (normalizedTail.length() < 3) {
            boolean seenBigram = lm.ngramExists(lm.getVocabulary().toIndexes(head, normalizedTail));
            boolean finiteVerbQuestion =
                config.useRichTurkishNoiseRules
                    && isShortQuestionParticle(normalizedTail)
                    && isFiniteVerbHead(headAnalysis);
            if (!seenBigram && !finiteVerbQuestion) {
              continue;
            }
          }
          if (hasRegularAnalysis(headAnalysis)
              || informalAsciiMorphology.analyze(head).analysisCount() > 0) {
            return head + " " + normalizedTail;
          }
        }
      }
    }
    return input;
  }

  private String normalizeQuestionTailTypo(String tail) {
    if (commonConnectedSuffixes.contains(tail)) {
      return tail;
    }
    if (!config.useRichTurkishNoiseRules) {
      return null;
    }
    switch (tail) {
      case "misinz":
        return "misiniz";
      case "mısınz":
        return "mısınız";
      case "musunz":
        return "musunuz";
      case "müsünz":
        return "müsünüz";
      default:
        return null;
    }
  }

  private static boolean isShortQuestionParticle(String s) {
    return "mı".equals(s) || "mi".equals(s) || "mu".equals(s) || "mü".equals(s);
  }

  private static boolean isFiniteVerbHead(WordAnalysis headAnalysis) {
    for (SingleAnalysis sa : headAnalysis) {
      if (!sa.isUnknown()
          && !sa.isRuntime()
          && sa.getDictionaryItem().primaryPos == PrimaryPos.Verb
          && sa.getPos() == PrimaryPos.Verb) {
        return true;
      }
    }
    return false;
  }

  /**
   * Morphology-guided expressive elongation squeezer. Collapses runs of >=2 repeated letters only
   * when the raw token lacks a regular formal analysis, validating collapsed forms against
   * morphology, informal morphology, colloquial lookups, and clitic splitters.
   */
  private String squeezeElongationsInSentence(String sentence) {
    List<Token> tokens = TurkishTokenizer.DEFAULT.tokenize(sentence);
    if (tokens.isEmpty()) {
      return sentence;
    }
    StringBuilder sb = new StringBuilder(sentence.length());
    int cursor = 0;
    for (Token tok : tokens) {
      if (tok.getStart() > cursor) {
        sb.append(sentence, cursor, tok.getStart());
      }
      String raw = tok.getText();
      if (isWordType(tok.getType()) && hasTripleOrDoubleRun(raw)) {
        sb.append(squeezeWordPreservingCase(raw));
      } else {
        sb.append(raw);
      }
      cursor = tok.getEnd() + 1;
    }
    if (cursor < sentence.length()) {
      sb.append(sentence, cursor, sentence.length());
    }
    return sb.toString();
  }

  private String squeezeWordPreservingCase(String raw) {
    String lower = raw.toLowerCase(Turkish.LOCALE);
    if (hasRegularAnalysis(morphology.analyze(lower))
        || COLLOQUIAL_SINGLE_OR_MULTI.containsKey(lower)
        || replacements.containsKey(lower)) {
      return raw;
    }
    CaseStyle style = detectCaseStyle(raw);

    // Try collapsing 3+ runs to 2 first (for geminate roots like "teşşşekkür" or "annnne" -> "anne")
    String max2 = collapseRuns(lower, 2);
    if (!max2.equals(lower) && isRecognizedNormalizedCandidate(max2)) {
      return applyCaseStyle(max2, style);
    }

    // Collapse all 2+ letter runs to 1 ("çoooook" -> "çok", "verirmisinzz" -> "verirmisiniz", "kadaar" -> "kadar")
    String max1 = collapseRuns(lower, 1);
    if (!max1.equals(lower) && isRecognizedNormalizedCandidate(max1)) {
      return applyCaseStyle(max1, style);
    }

    // If word had >=3 consecutive identical letters (never valid in Turkish!), or ends with
    // repeated identical letters while remaining completely OOV, collapse so downstream stages can fix it.
    if (hasRunAtLeast(lower, 3)
        || (lower.length() >= 4
            && lower.charAt(lower.length() - 1) == lower.charAt(lower.length() - 2)
            && !hasAnyNonUnknownAnalysis(morphology.analyze(lower)))) {
      return applyCaseStyle(max1, style);
    }
    return raw;
  }

  private boolean isRecognizedNormalizedCandidate(String lowerCandidate) {
    if (COLLOQUIAL_SINGLE_OR_MULTI.containsKey(lowerCandidate)
        || replacements.containsKey(lowerCandidate)
        || lookupManual.containsKey(lowerCandidate)
        || commonSplits.containsKey(lowerCandidate)) {
      return true;
    }
    if (informalAsciiMorphology.analyze(lowerCandidate).analysisCount() > 0) {
      return true;
    }
    if (!separateCommon(lowerCandidate, true).equals(lowerCandidate)) {
      return true;
    }
    return !generateExtendedMorphoPhonologicalCandidates(lowerCandidate).isEmpty();
  }

  private List<String> generateExtendedMorphoPhonologicalCandidates(String word) {
    List<String> results = new ArrayList<>(2);

    // 1. Productive demonstrative/interrogative locative & ablative haplology:
    //    burda* -> burada*, şurda* -> şurada*, orda* -> orada*, nerde* -> nerede*
    String[][] haplologyPrefixes = {
      {"burda", "burada"},
      {"şurda", "şurada"},
      {"orda", "orada"},
      {"nerde", "nerede"}
    };
    for (String[] pair : haplologyPrefixes) {
      if (word.startsWith(pair[0])) {
        String expanded = pair[1] + word.substring(pair[0].length());
        if (hasRegularAnalysis(morphology.analyze(expanded))) {
          results.add(expanded);
        }
      }
    }

    // 2. Productive "-Io" progressive contraction (y-drop in -ıyor/-iyor/-uyor/-üyor):
    //    e.g., çalışıom -> çalışıyorum, dinleniorum -> dinleniyorum, gelio -> geliyor,
    //    çalışıodum -> çalışıyordum, bilmiom -> bilmiyorum, sölüo -> söylüyor
    addProgressiveYDropCandidates(word, results);

    // 3. Productive syncopated future / future-participle (-cAk / -cAğ, especially after -Abil or passive):
    //    e.g., buluşabilceğimiz -> buluşabileceğimiz, kalkcağını -> kalkacağını, içilcek -> içilecek
    addFutureSyncopeCandidates(word, results);

    // 4. Residual 2->1 letter run reduction + colloquial lookup (e.g. "merabaa" -> "meraba" -> "merhaba")
    if (hasTripleOrDoubleRun(word)) {
      String sq = collapseRuns(word, 1);
      String col = COLLOQUIAL_SINGLE_OR_MULTI.get(sq);
      if (col != null) {
        results.add(col);
      } else if (hasRegularAnalysis(morphology.analyze(sq))) {
        results.add(sq);
      }
    }

    return results;
  }

  private void addProgressiveYDropCandidates(String word, List<String> out) {
    String stemAdjusted = word;
    if (stemAdjusted.startsWith("sölü")) {
      stemAdjusted = "söylü" + stemAdjusted.substring(4);
    }
    int len = stemAdjusted.length();
    for (int i = 1; i < len - 1; i++) {
      char v = stemAdjusted.charAt(i);
      if ((v == 'ı' || v == 'i' || v == 'u' || v == 'ü') && stemAdjusted.charAt(i + 1) == 'o') {
        String prefix = stemAdjusted.substring(0, i + 1) + "yo";
        String tail = stemAdjusted.substring(i + 2);
        // Either tail already starts with 'r' ("iorum" -> "iyorum") or 'r' was also dropped ("ıom" -> "ıyo" -> informal converter!)
        List<String> probes = new ArrayList<>(2);
        if (tail.startsWith("r")) {
          probes.add(prefix + tail);
        } else {
          probes.add(prefix + "r" + tail);
          probes.add(prefix + tail); // e.g. çalışıom -> çalışıyom -> informal converter -> çalışıyorum!
        }
        for (String probe : probes) {
          WordAnalysis wa = informalStrictMorphology.analyze(probe);
          for (SingleAnalysis sa : wa) {
            if (!sa.isUnknown() && sa.getDictionaryItem().primaryPos == PrimaryPos.Verb) {
              WordGenerator.Result conv = analysisConverter.convert(probe, sa);
              if (conv != null && !out.contains(conv.surface)) {
                out.add(conv.surface);
              }
            }
          }
        }
      }
    }
  }

  private void addFutureSyncopeCandidates(String word, List<String> out) {
    String[] markers = {"ceğ", "cağ", "cek", "cak", "cem", "cam", "cen", "can", "cez", "caz"};
    for (String marker : markers) {
      int idx = word.indexOf(marker);
      if (idx >= 2) {
        char prev = word.charAt(idx - 1);
        if (!TurkishAlphabet.INSTANCE.isVowel(prev) && prev != 'y') {
          char insVowel = marker.charAt(1) == 'e' ? 'e' : 'a';
          String expanded = word.substring(0, idx) + insVowel + word.substring(idx);
          WordAnalysis wa = informalStrictMorphology.analyze(expanded);
          for (SingleAnalysis sa : wa) {
            if (!sa.isUnknown() && sa.getDictionaryItem().primaryPos == PrimaryPos.Verb) {
              WordGenerator.Result conv = analysisConverter.convert(expanded, sa);
              if (conv != null && !out.contains(conv.surface)) {
                out.add(conv.surface);
              }
            }
          }
        }
      }
    }
  }

  private boolean mightNeedCliticDeDaSplit(String word, String prevText, String nextText) {
    if (word.length() < 4 || noSplitWords.contains(word)) {
      return false;
    }
    if (!word.endsWith("de") && !word.endsWith("da")) {
      return false;
    }
    // Common lexicalized locatives that should almost never be split unless overwhelming evidence
    if ("nerede".equals(word) || "burada".equals(word) || "orada".equals(word) || "şurada".equals(word)) {
      return false;
    }
    return nextText != null || prevText != null;
  }

  private boolean shouldOfferCliticSplit(
      String joined,
      String head,
      String tail,
      String prevText,
      String nextText,
      LmVocabulary vocab) {
    WordAnalysis headAnalysis = morphology.analyze(head);
    if (!hasRegularAnalysis(headAnalysis)) {
      return false;
    }
    // Require head to be a valid nominal or pronoun (e.g. "annem", "ben", "sen", "bunun", "oğluna")
    boolean nominalOrPronoun = false;
    for (SingleAnalysis sa : headAnalysis) {
      PrimaryPos pos = sa.getDictionaryItem().primaryPos;
      if (pos == PrimaryPos.Noun || pos == PrimaryPos.Pronoun || pos == PrimaryPos.Adjective) {
        nominalOrPronoun = true;
        break;
      }
    }
    if (!nominalOrPronoun) {
      return false;
    }
    int unk = vocab.getUnknownWordIndex();
    int headIdx = vocab.indexOf(head);
    int tailIdx = vocab.indexOf(tail);
    int joinedIdx = vocab.indexOf(joined);
    if (headIdx == unk || tailIdx == unk) {
      return false;
    }
    // Only propose split if (head, de/da) is an attested bigram AND joined is NOT strongly bound to nextText
    if (!lm.ngramExists(headIdx, tailIdx)) {
      return false;
    }
    if (nextText != null) {
      int nextIdx = vocab.indexOf(nextText);
      if (nextIdx != unk && joinedIdx != unk && lm.ngramExists(joinedIdx, nextIdx)) {
        // Attested locative bigram (e.g. "evde kaldım", "okulda var") -> never split!
        return false;
      }
    }
    return true;
  }

  private List<String> rankSpellCandidates(
      List<CompactStemEndingSpellChecker.ScoredCandidate> scList,
      String left,
      String right,
      boolean asciiContext,
      float editWeight,
      int maxResults) {
    if (scList.isEmpty()) {
      return Collections.emptyList();
    }
    LmVocabulary vocab = lm.getVocabulary();
    int leftIdx =
        left == null ? vocab.indexOf(vocab.getSentenceStart()) : vocab.indexOf(left);
    int rightIdx =
        right == null ? vocab.indexOf(vocab.getSentenceEnd()) : vocab.indexOf(right);

    int n = scList.size();
    Integer[] order = new Integer[n];
    float[] scores = new float[n];
    for (int i = 0; i < n; i++) {
      order[i] = i;
      CompactStemEndingSpellChecker.ScoredCandidate c = scList.get(i);
      int wIdx = vocab.indexOf(c.surface.toLowerCase(Turkish.LOCALE));
      float lmScore =
          lm.getOrder() >= 2
              ? lm.getProbability(leftIdx, wIdx) + lm.getProbability(wIdx, rightIdx)
              : lm.getUnigramProbability(wIdx);
      scores[i] = lmScore - editWeight * c.totalDistanceCost(asciiContext);
    }
    Arrays.sort(order, (a, b) -> Float.compare(scores[b], scores[a]));
    int k = Math.min(maxResults, n);
    List<String> top = new ArrayList<>(k);
    for (int i = 0; i < k; i++) {
      top.add(scList.get(order[i]).surface.toLowerCase(Turkish.LOCALE));
    }
    return top;
  }

  private static CompactStemEndingSpellChecker.ScoredCandidate findScored(
      List<CompactStemEndingSpellChecker.ScoredCandidate> list, String lowerSurface) {
    for (CompactStemEndingSpellChecker.ScoredCandidate sc : list) {
      if (sc.surface.toLowerCase(Turkish.LOCALE).equals(lowerSurface)) {
        return sc;
      }
    }
    return null;
  }

  private String formatPreservingCaseAndPunctuation(
      List<SpanToken> originalStream, List<String> chosenLowerWords) {
    StringBuilder sb = new StringBuilder(chosenLowerWords.size() * 7);
    boolean atSentenceStart = true;

    for (int i = 0; i < originalStream.size(); i++) {
      SpanToken orig = originalStream.get(i);
      String chosen = chosenLowerWords.get(i);

      boolean needSpace = resolveSpacingBefore(i, orig, originalStream);
      if (needSpace && sb.length() > 0) {
        sb.append(' ');
      }

      if (!isWordType(orig.type)) {
        sb.append(chosen);
        if (isSentenceBoundary(chosen)) {
          atSentenceStart = true;
        }
        continue;
      }

      String cased = projectCasing(chosen, orig.caseStyle, atSentenceStart);
      sb.append(cased);
      atSentenceStart = false;
    }
    return sb.toString();
  }

  private static boolean resolveSpacingBefore(
      int index, SpanToken cur, List<SpanToken> stream) {
    if (index == 0) {
      return false;
    }
    String text = cur.text;
    // Closing/attaching punctuation never takes a preceding space
    if (",".equals(text)
        || ".".equals(text)
        || "!".equals(text)
        || "?".equals(text)
        || "...".equals(text)
        || ":".equals(text)
        || ";".equals(text)
        || ")".equals(text)
        || "]".equals(text)) {
      return false;
    }
    SpanToken prev = stream.get(index - 1);
    if ("(".equals(prev.text) || "[".equals(prev.text)) {
      return false;
    }
    return cur.spaceBefore || isWordType(cur.type);
  }

  private String projectCasing(
      String lowerText, CaseStyle origStyle, boolean atSentenceStart) {
    if (lowerText.indexOf(' ') > 0) {
      String[] parts = lowerText.split(" ");
      StringBuilder sb = new StringBuilder(lowerText.length() + 2);
      for (int i = 0; i < parts.length; i++) {
        if (i > 0) {
          sb.append(' ');
        }
        CaseStyle partStyle =
            (i == 0)
                ? origStyle
                : (origStyle == CaseStyle.UPPER ? CaseStyle.UPPER : CaseStyle.LOWER);
        sb.append(projectSingleWordCasing(parts[i], partStyle, i == 0 && atSentenceStart));
      }
      return sb.toString();
    }
    return projectSingleWordCasing(lowerText, origStyle, atSentenceStart);
  }

  private String projectSingleWordCasing(
      String lowerWord, CaseStyle origStyle, boolean atSentenceStart) {
    if (origStyle == CaseStyle.UPPER && lowerWord.length() > 1) {
      return lowerWord.toUpperCase(Turkish.LOCALE);
    }
    // If word has apostrophe (e.g. "ankara'ya" or "12'de"), capitalize proper-noun stem properly
    int apos = lowerWord.indexOf('\'');
    if (apos > 0 && Character.isLetter(lowerWord.charAt(0))) {
      return Turkish.capitalize(lowerWord);
    }
    // Check if word is unambiguously a ProperNoun in morphology when originally TitleCase
    if (origStyle == CaseStyle.TITLE || atSentenceStart) {
      return Turkish.capitalize(lowerWord);
    }
    WordAnalysis wa = morphology.analyze(Turkish.capitalize(lowerWord));
    if (wa.analysisCount() > 0 && !hasRegularAnalysis(morphology.analyze(lowerWord))) {
      for (SingleAnalysis sa : wa) {
        if (sa.getDictionaryItem().secondaryPos == SecondaryPos.ProperNoun) {
          return surfaceFormatter.formatToCase(
              sa, WordAnalysisSurfaceFormatter.CaseType.TITLE_CASE, apos > 0 ? "'" : null);
        }
      }
    }
    return lowerWord;
  }

  private static boolean isObscureAbbrvOrShortProperOnly(
      String lowerWord, WordAnalysis wa, boolean wasMidSentenceCap) {
    if (lowerWord.indexOf('\'') >= 0 || lowerWord.indexOf('’') >= 0) {
      return false;
    }
    boolean any = false;
    for (SingleAnalysis sa : wa) {
      if (sa.isUnknown() || sa.isRuntime()) {
        continue;
      }
      any = true;
      if (!isSuspiciousSingleAnalysis(lowerWord, sa, wasMidSentenceCap)) {
        return false;
      }
    }
    return any;
  }

  private static boolean isSuspiciousSingleAnalysis(
      String lowerWord, SingleAnalysis sa, boolean wasMidSentenceCap) {
    if (lowerWord.indexOf('\'') >= 0 || lowerWord.indexOf('’') >= 0) {
      return false;
    }
    SecondaryPos spos = sa.getDictionaryItem().secondaryPos;
    String root = sa.getDictionaryItem().root;
    if (spos == SecondaryPos.Abbreviation && lowerWord.length() > root.length()) {
      return true;
    }
    if (spos == SecondaryPos.ProperNoun) {
      if (root.length() <= 3 && lowerWord.length() > root.length()) {
        return true;
      }
      if (!wasMidSentenceCap && root.length() <= 5 && lowerWord.length() >= root.length()) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasRegularAnalysis(WordAnalysis a) {
    for (SingleAnalysis k : a) {
      if (!k.isUnknown()
          && !k.isRuntime()
          && k.getDictionaryItem().secondaryPos != SecondaryPos.ProperNoun
          && k.getDictionaryItem().secondaryPos != SecondaryPos.Abbreviation) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasAnyNonUnknownAnalysis(WordAnalysis w) {
    for (SingleAnalysis s : w) {
      if (!s.isRuntime() && !s.isUnknown()) {
        return true;
      }
    }
    return false;
  }

  private static boolean isLikelyDetachedNumberSuffix(String s) {
    return s.length() <= 5
        && ("de".equals(s)
            || "da".equals(s)
            || "te".equals(s)
            || "ta".equals(s)
            || "den".equals(s)
            || "dan".equals(s)
            || "ten".equals(s)
            || "tan".equals(s)
            || "e".equals(s)
            || "a".equals(s)
            || "ye".equals(s)
            || "ya".equals(s)
            || "i".equals(s)
            || "ı".equals(s)
            || "u".equals(s)
            || "ü".equals(s)
            || "yi".equals(s)
            || "yı".equals(s)
            || "yu".equals(s)
            || "yü".equals(s)
            || "ar".equals(s)
            || "er".equals(s)
            || "şer".equals(s)
            || "şar".equals(s));
  }

  private static boolean areAllCandidatesUnknownInLm(Set<String> cands, LmVocabulary vocab) {
    int unk = vocab.getUnknownWordIndex();
    for (String c : cands) {
      if (c.indexOf(' ') >= 0) {
        return false;
      }
      if (vocab.indexOf(c) != unk) {
        return false;
      }
    }
    return true;
  }

  boolean isAsciiLikeSentence(String sentence) {
    if (!config.useSinglePassPipeline) {
      return legacyProbablyRequiresDeasciifier(sentence);
    }
    int turkishSpecCount = 0;
    int letterCount = 0;
    for (int i = 0; i < sentence.length(); i++) {
      char c = sentence.charAt(i);
      if (Character.isLetter(c)) {
        letterCount++;
      }
      if (c == 'I') {
        continue;
      }
      if (TurkishAlphabet.INSTANCE.isTurkishSpecific(c)) {
        turkishSpecCount++;
      }
    }
    if (letterCount == 0) {
      return false;
    }
    return (turkishSpecCount * 1.0 / letterCount) < 0.05;
  }

  static boolean legacyProbablyRequiresDeasciifier(String sentence) {
    if (sentence.isEmpty()) {
      return false;
    }
    int turkishSpecCount = 0;
    for (int i = 0; i < sentence.length(); i++) {
      char c = sentence.charAt(i);
      if (c == 'ı' || c == 'I') {
        continue;
      }
      if (TurkishAlphabet.INSTANCE.isTurkishSpecific(c)) {
        turkishSpecCount++;
      }
    }
    return (turkishSpecCount * 1.0 / sentence.length()) < 0.1;
  }

  private static boolean isWordType(Type type) {
    return type == Type.Word
        || type == Type.WordWithSymbol
        || type == Type.WordAlphanumerical
        || type == Type.UnknownWord;
  }

  private static boolean isSentenceBoundary(String text) {
    return ".".equals(text) || "!".equals(text) || "?".equals(text) || "...".equals(text);
  }

  private static boolean hasAnyLowercaseLetter(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (Character.isLowerCase(s.charAt(i))) {
        return true;
      }
    }
    return false;
  }

  private static CaseStyle detectCaseStyle(String raw) {
    if (raw.isEmpty()) {
      return CaseStyle.LOWER;
    }
    boolean firstUpper = Character.isUpperCase(raw.charAt(0));
    if (!firstUpper) {
      return CaseStyle.LOWER;
    }
    boolean anyLower = false;
    int upperCount = 0;
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (Character.isLowerCase(c)) {
        anyLower = true;
      } else if (Character.isUpperCase(c)) {
        upperCount++;
      }
    }
    if (!anyLower && upperCount >= 2) {
      return CaseStyle.UPPER;
    }
    return CaseStyle.TITLE;
  }

  private static String applyCaseStyle(String lower, CaseStyle style) {
    if (style == CaseStyle.UPPER) {
      return lower.toUpperCase(Turkish.LOCALE);
    } else if (style == CaseStyle.TITLE) {
      return Turkish.capitalize(lower);
    }
    return lower;
  }

  private static boolean hasTripleOrDoubleRun(String s) {
    for (int i = 1; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == s.charAt(i - 1) && Character.isLetter(c)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasRunAtLeast(String s, int minRun) {
    int run = 1;
    for (int i = 1; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == s.charAt(i - 1) && Character.isLetter(c)) {
        run++;
        if (run >= minRun) {
          return true;
        }
      } else {
        run = 1;
      }
    }
    return false;
  }

  private static String collapseRuns(String s, int maxAllowedRun) {
    StringBuilder sb = new StringBuilder(s.length());
    int run = 0;
    char prev = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (i > 0 && c == prev && Character.isLetter(c)) {
        run++;
        if (run <= maxAllowedRun) {
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

  private static ArrayListMultimap<String, String> loadMultiMap(List<String> lines) {
    ArrayListMultimap<String, String> result = ArrayListMultimap.create();
    for (String line : lines) {
      int index = line.indexOf('=');
      if (index < 0) {
        continue;
      }
      String key = line.substring(0, index).trim();
      String value = line.substring(index + 1).trim();
      if (value.indexOf(',') >= 0) {
        for (String token : Splitter.on(',').trimResults().split(value)) {
          result.put(key, token);
        }
      } else {
        result.put(key, value);
      }
    }
    return result;
  }

  private static Map<String, String> buildColloquialMap() {
    Map<String, String> m = new HashMap<>(128);
    // High-frequency Turkish SMS/chat contractions & phonetic spellings (mined from unparsed_words.tsv)
    m.put("slm", "selam");
    m.put("mrb", "merhaba");
    m.put("meraba", "merhaba");
    m.put("tmm", "tamam");
    m.put("cnm", "canım");
    m.put("yrn", "yarın");
    m.put("inş", "inşallah");
    m.put("ins", "inşallah");
    m.put("tşk", "teşekkür");
    m.put("tsk", "teşekkür");
    m.put("tşkler", "teşekkürler");
    m.put("tskler", "teşekkürler");
    m.put("tsklr", "teşekkürler");
    m.put("eyw", "eyvallah");
    m.put("eyv", "eyvallah");
    m.put("krdş", "kardeş");
    m.put("nbr", "ne haber");
    m.put("hb", "hoş bulduk");
    m.put("hg", "hoş geldin");
    m.put("aeo", "allaha emanet ol");
    m.put("bnde", "ben de");
    m.put("snde", "sen de");
    m.put("bn", "ben");
    m.put("sn", "sen");
    m.put("bi", "bir");
    m.put("bı", "bir");
    m.put("deil", "değil");
    m.put("degil", "değil");
    m.put("diil", "değil");
    m.put("ii", "iyi");
    m.put("die", "diye");
    m.put("böle", "böyle");
    m.put("şöle", "şöyle");
    m.put("öle", "öyle");
    m.put("fln", "falan");
    m.put("felan", "falan");
    m.put("fılan", "falan");
    m.put("heralde", "herhalde");
    m.put("haralde", "herhalde");
    m.put("malesef", "maalesef");
    m.put("yalnış", "yanlış");
    m.put("yanlız", "yalnız");
    m.put("herkez", "herkes");
    m.put("herkeze", "herkese");
    m.put("herkezi", "herkesi");
    m.put("herkezin", "herkesin");
    m.put("orjinal", "orijinal");
    m.put("süpriz", "sürpriz");
    m.put("şarz", "şarj");
    m.put("idda", "iddia");
    m.put("iddaa", "iddia");
    m.put("tesbit", "tespit");
    m.put("afedersiniz", "affedersiniz");
    m.put("afedersin", "affedersin");
    m.put("dersane", "dershane");
    m.put("dersaneye", "dershaneye");
    m.put("dersanede", "dershanede");
    m.put("espiri", "espri");
    m.put("kiprik", "kirpik");
    m.put("kirbit", "kibrit");
    m.put("bişiler", "bir şeyler");
    m.put("bişeyler", "bir şeyler");
    m.put("yav", "yahu");
    m.put("yaw", "yahu");
    return Collections.unmodifiableMap(m);
  }
}
