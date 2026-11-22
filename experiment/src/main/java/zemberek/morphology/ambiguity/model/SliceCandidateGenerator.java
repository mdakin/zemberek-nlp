package zemberek.morphology.ambiguity.model;

import com.beust.jcommander.Parameter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import zemberek.apps.ConsoleApp;
import zemberek.core.logging.Log;
import zemberek.core.text.TextUtil;
import zemberek.core.turkish.PrimaryPos;
import zemberek.core.turkish.RootAttribute;
import zemberek.core.turkish.SecondaryPos;
import zemberek.core.turkish.TurkishAlphabet;
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.analysis.SingleAnalysis;
import zemberek.morphology.analysis.WordAnalysis;
import zemberek.morphology.lexicon.DictionaryItem;
import zemberek.morphology.lexicon.RootLexicon;
import zemberek.tokenization.Token;
import zemberek.tokenization.TurkishTokenizer;

/**
 * Multi-threaded Zemberek Candidate JSONL Generator (ZCS-v2) for the 1,000-sentence
 * test and training slices in /home/dndara/data/turkish/selected_slices.
 *
 * Key upgrades over legacy DisambiguationCandidateExtractor:
 * 1. Globally unique sentence IDs (<slice_name>_s0001 .. <slice_name>_s1000) so slices
 *    can be freely concatenated without ID collisions.
 * 2. Pure Zemberek native representation (zemberek_key = sa.formatLong(), ig_key, last_group,
 *    derivation_count) — drops redundant Oflazer strings and unused char offsets.
 * 3. Embeds ZAS-v3 token diagnostics (macro_mechanism, observability, zas_signature) and
 *    TurkishTokenizer token_type directly on each token record.
 * 4. Automatically merges test slice metadata (.meta.tsv -> eval_stratum, source_domain).
 * 5. Processes all slices concurrently across a shared thread pool in seconds.
 */
public class SliceCandidateGenerator extends ConsoleApp {

  private static final Locale TR = Locale.forLanguageTag("tr");
  private static final TurkishAlphabet ALPHABET = TurkishAlphabet.INSTANCE;

  @Parameter(
      names = {"--slicesDir", "-i"},
      description = "Input root directory containing test_slices/ and train_slices/")
  public Path slicesDir = Paths.get("/home/dndara/data/turkish/selected_slices");

  @Parameter(
      names = {"--outputDir", "-o"},
      description = "Output root directory for generated candidate JSONL files")
  public Path outputDir = Paths.get("/home/dndara/data/turkish/annotated_slices");

  @Parameter(
      names = {"--pool", "-p"},
      description = "Which slice pool to export: 'all', 'test', or 'train' (default: 'all')")
  public String pool = "all";

  @Parameter(
      names = {"--maxSlices", "-m"},
      description = "Maximum number of slices per pool to export (-1 = all)")
  public int maxSlices = -1;

  @Parameter(
      names = {"--threads", "-t"},
      description = "Number of worker threads (default: 16)")
  public int threads = 16;

  public enum MacroMechanism {
    M1_INFLECTIONAL_SYNCRETISM,
    M2_DERIVATIONAL_SHIFT,
    M3_LEXICAL_POS_POLYSEMY,
    M4_PROPER_VS_COMMON,
    M5a_VOICE_LEXICALIZED,
    M5b_STEM_SUFFIX_COLLISION,
    M6a_HOMONYM_DIFF_POS,
    M6b_ORTHO_OR_COMPOUND_TWIN,
    M7_HYBRID_MULTI_LOCUS
  }

  public enum Observability {
    FULL_MORPHO_OBSERVABLE,
    PARTIAL_MORPHO_OBSERVABLE,
    LEXICAL_ONLY_OBSERVABLE
  }

  public static void main(String[] args) {
    new SliceCandidateGenerator().execute(args);
  }

  @Override
  public String description() {
    return "Exports ZCS-v2 Zemberek candidate JSONL files for selected test and training slices.";
  }

  @Override
  public void run() throws Exception {
    long t0 = System.currentTimeMillis();
    if (!Files.exists(slicesDir)) {
      throw new IllegalArgumentException("Slices directory not found: " + slicesDir);
    }

    TurkishTokenizer tokenizer = TurkishTokenizer.DEFAULT;
    TurkishMorphology morphology =
        TurkishMorphology.builder()
            .setLexicon(RootLexicon.getDefault())
            .setTokenizer(tokenizer)
            .build();

    List<SliceJob> jobs = new ArrayList<>();
    if ("all".equalsIgnoreCase(pool) || "test".equalsIgnoreCase(pool)) {
      jobs.addAll(discoverSliceJobs(slicesDir.resolve("test_slices"), outputDir.resolve("test/candidates"), "test"));
    }
    if ("all".equalsIgnoreCase(pool) || "train".equalsIgnoreCase(pool)) {
      jobs.addAll(discoverSliceJobs(slicesDir.resolve("train_slices"), outputDir.resolve("train/candidates"), "train"));
    }

    Log.info("Discovered %d slice files to export using %d threads.", jobs.size(), threads);

    ExecutorService executor = Executors.newFixedThreadPool(threads);
    List<Future<SliceExportSummary>> futures = new ArrayList<>(jobs.size());
    for (SliceJob job : jobs) {
      futures.add(executor.submit(() -> exportSingleSlice(job, tokenizer, morphology)));
    }

    long totalSentences = 0;
    long totalTokens = 0;
    long totalAmbigTokens = 0;
    for (Future<SliceExportSummary> f : futures) {
      SliceExportSummary sum = f.get();
      totalSentences += sum.sentenceCount;
      totalTokens += sum.tokenCount;
      totalAmbigTokens += sum.ambiguousTokenCount;
    }
    executor.shutdown();

    double elapsedSec = (System.currentTimeMillis() - t0) / 1000.0;
    Log.info(
        "Completed exporting %d slices (%,d sentences, %,d tokens, %,d ambiguous tokens) in %.2f s.",
        jobs.size(), totalSentences, totalTokens, totalAmbigTokens, elapsedSec);
  }

  private List<SliceJob> discoverSliceJobs(Path inPoolDir, Path outCandidatesDir, String poolType)
      throws IOException {
    List<SliceJob> list = new ArrayList<>();
    if (!Files.exists(inPoolDir)) {
      return list;
    }
    List<Path> txtFiles;
    try (Stream<Path> s = Files.list(inPoolDir)) {
      txtFiles =
          s.filter(Files::isRegularFile)
              .filter(p -> p.getFileName().toString().endsWith(".txt"))
              .sorted(Comparator.comparing(p -> p.getFileName().toString()))
              .collect(Collectors.toList());
    }
    if (maxSlices > 0 && txtFiles.size() > maxSlices) {
      txtFiles = txtFiles.subList(0, maxSlices);
    }
    for (Path txt : txtFiles) {
      String fname = txt.getFileName().toString();
      String sliceName = fname.substring(0, fname.length() - 4);
      Path metaTsv = inPoolDir.resolve(sliceName + ".meta.tsv");
      Path outJsonl = outCandidatesDir.resolve(sliceName + "_candidates.jsonl");
      Path outMetaJson = outCandidatesDir.resolve(sliceName + "_candidates.meta.json");
      list.add(new SliceJob(sliceName, poolType, txt, metaTsv, outJsonl, outMetaJson));
    }
    return list;
  }

  private SliceExportSummary exportSingleSlice(
      SliceJob job, TurkishTokenizer tokenizer, TurkishMorphology morphology) throws IOException {
    Files.createDirectories(job.outJsonl.getParent());

    Map<Integer, MetaRow> metaByIndex = loadMetaTsv(job.metaTsv);
    Gson jsonlGson = new GsonBuilder().disableHtmlEscaping().create();

    int sentenceCount = 0;
    int wordCountTotal = 0;
    int tokenCountTotal = 0;
    int ambigTokenCountTotal = 0;
    Map<String, Integer> mechanismCounts = new HashMap<>();
    Map<String, Integer> observabilityCounts = new HashMap<>();

    try (BufferedReader reader = Files.newBufferedReader(job.txtPath, StandardCharsets.UTF_8);
        BufferedWriter writer = Files.newBufferedWriter(job.outJsonl, StandardCharsets.UTF_8)) {
      String line;
      int idxInSlice = 0;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty()) {
          continue;
        }
        idxInSlice++;
        String sentenceId = String.format("%s_s%04d", job.sliceName, idxInSlice);
        MetaRow meta = metaByIndex.get(idxInSlice);
        String stratum = (meta != null) ? meta.stratum : ("test".equals(job.poolType) ? "TEST" : "TRAIN");
        String domain = (meta != null) ? meta.domain : null;

        SentenceRecord rec =
            buildSentenceRecord(
                sentenceId,
                job.sliceName,
                idxInSlice,
                stratum,
                domain,
                line,
                tokenizer,
                morphology,
                mechanismCounts,
                observabilityCounts);

        writer.write(jsonlGson.toJson(rec));
        writer.newLine();

        sentenceCount++;
        wordCountTotal += rec.word_count;
        tokenCountTotal += rec.tokens.size();
        ambigTokenCountTotal += rec.ambiguous_word_count;
      }
    }

    SliceMetadata metaOut = new SliceMetadata();
    metaOut.schema_version = "ZCS-v2.0";
    metaOut.slice_name = job.sliceName;
    metaOut.pool = job.poolType;
    metaOut.source_txt = job.txtPath.toString();
    metaOut.created_at = Instant.now().toString();
    metaOut.sentence_count = sentenceCount;
    metaOut.word_count = wordCountTotal;
    metaOut.token_count = tokenCountTotal;
    metaOut.ambiguous_token_count = ambigTokenCountTotal;
    metaOut.ambiguity_rate =
        tokenCountTotal > 0 ? Math.round((ambigTokenCountTotal * 10000.0) / tokenCountTotal) / 10000.0 : 0.0;
    metaOut.mechanism_distribution = mechanismCounts;
    metaOut.observability_distribution = observabilityCounts;

    Gson prettyGson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    try (BufferedWriter mw = Files.newBufferedWriter(job.outMetaJson, StandardCharsets.UTF_8)) {
      prettyGson.toJson(metaOut, mw);
    }

    return new SliceExportSummary(job.sliceName, sentenceCount, tokenCountTotal, ambigTokenCountTotal);
  }

  private static SentenceRecord buildSentenceRecord(
      String sentenceId,
      String sliceName,
      int sliceIndex,
      String evalStratum,
      String sourceDomain,
      String rawSentence,
      TurkishTokenizer tokenizer,
      TurkishMorphology morphology,
      Map<String, Integer> mechanismCounts,
      Map<String, Integer> observabilityCounts) {

    String normalized = TextUtil.normalizeQuotesHyphens(rawSentence);
    List<Token> rawTokens = tokenizer.tokenize(normalized);

    SentenceRecord rec = new SentenceRecord();
    rec.sentence_id = sentenceId;
    rec.slice_name = sliceName;
    rec.slice_index = sliceIndex;
    rec.eval_stratum = evalStratum;
    rec.source_domain = sourceDomain;
    rec.text = normalized;

    int wordCount = 0;
    int ambigCount = 0;
    int tIdx = 0;

    for (Token token : rawTokens) {
      TokenRecord tr = new TokenRecord();
      tr.index = tIdx++;
      tr.surface = token.getText();
      tr.token_type = token.getType().name();

      if (token.getType() != Token.Type.Punctuation) {
        wordCount++;
      }

      WordAnalysis wa = morphology.analyze(token);
      List<SingleAnalysis> results = wa.getAnalysisResults();
      int cId = 0;
      for (SingleAnalysis sa : results) {
        CandidateRecord cr = new CandidateRecord();
        cr.id = cId++;
        cr.zemberek_key = sa.formatLong();

        DictionaryItem item = sa.getDictionaryItem();
        cr.lemma = item.lemma;
        cr.pos = item.primaryPos != null ? item.primaryPos.shortForm : PrimaryPos.Unknown.shortForm;
        cr.secondary_pos =
            (item.secondaryPos != null && item.secondaryPos != SecondaryPos.None)
                ? item.secondaryPos.shortForm
                : "None";
        cr.ig_key = buildFastPerceptronRig(sa);
        cr.last_group = sa.getLastGroup().lexicalForm();
        cr.derivation_count = Math.max(0, sa.groupCount() - 1);
        cr.morphemes =
            sa.getMorphemes().stream().map(m -> m.id).collect(Collectors.toList());

        tr.candidates.add(cr);
      }

      tr.is_ambiguous = tr.candidates.size() > 1;
      if (tr.is_ambiguous) {
        ambigCount++;
        tr.selected_candidate_id = null; // To be filled by LLM disambiguator
        ZasTokenProfile zas = classifyZasV3(results);
        tr.macro_mechanism = zas.mechanism.name();
        tr.observability = zas.observability.name();
        tr.zas_signature = zas.signature;
        mechanismCounts.merge(tr.macro_mechanism, 1, Integer::sum);
        observabilityCounts.merge(tr.observability, 1, Integer::sum);
      } else {
        tr.selected_candidate_id = 0;
      }

      rec.tokens.add(tr);
    }

    rec.word_count = wordCount;
    rec.ambiguous_word_count = ambigCount;
    return rec;
  }

  /**
   * Reconstructs the exact `rIg` string (`lemma + "+" + igJoined`) used by
   * FastPerceptronAmbiguityResolver.AnalysisTemplate.
   */
  private static String buildFastPerceptronRig(SingleAnalysis sa) {
    DictionaryItem item = sa.getDictionaryItem();
    String lemma = item.lemma != null ? item.lemma : "";
    SecondaryPos secPos = item.secondaryPos;
    String sp = (secPos == null || secPos == SecondaryPos.None) ? "" : secPos.name();
    int gCount = sa.groupCount();
    StringBuilder sb = new StringBuilder(lemma.length() + 32);
    sb.append(lemma).append("+");
    for (int i = 0; i < gCount; i++) {
      if (i > 0) {
        sb.append("+");
      }
      String lex = sa.getGroup(i).lexicalForm();
      if (i == 0 && !sp.isEmpty()) {
        sb.append(sp);
      }
      sb.append(lex);
    }
    return sb.toString();
  }

  private static ZasTokenProfile classifyZasV3(List<SingleAnalysis> results) {
    int K = results.size();
    Set<DictionaryItem> itemSet = new HashSet<>(K * 2);
    Set<String> normLemmaSet = new HashSet<>(K * 2);
    Set<PrimaryPos> rootPosSet = new HashSet<>(K * 2);
    Set<String> posPairSet = new HashSet<>(K * 2);
    Set<Integer> stemLenSet = new HashSet<>(K * 2);
    Set<Integer> groupCountSet = new HashSet<>(K * 2);
    Set<String> derivChainSet = new HashSet<>(K * 2);
    Set<String> lastGroupSet = new HashSet<>(K * 2);

    boolean hasProperOrAbbrv = false;
    boolean hasCommon = false;
    boolean hasCompoundP3sgAttr = false;
    int maxStemLen = 0;

    for (SingleAnalysis sa : results) {
      DictionaryItem item = sa.getDictionaryItem();
      itemSet.add(item);
      String normLemma =
          ALPHABET.normalizeCircumflex(item.lemma.toLowerCase(TR)).replace(".", "");
      normLemmaSet.add(normLemma);
      rootPosSet.add(item.primaryPos);
      posPairSet.add(item.primaryPos.shortForm + ":" + item.secondaryPos.shortForm);

      if (item.secondaryPos == SecondaryPos.ProperNoun
          || item.secondaryPos == SecondaryPos.Abbreviation) {
        hasProperOrAbbrv = true;
      } else {
        hasCommon = true;
      }
      if (item.hasAttribute(RootAttribute.CompoundP3sg)
          || item.hasAttribute(RootAttribute.CompoundP3sgRoot)) {
        hasCompoundP3sgAttr = true;
      }

      int stemLen = sa.getStem().length();
      stemLenSet.add(stemLen);
      if (stemLen > maxStemLen) {
        maxStemLen = stemLen;
      }

      int gCount = sa.groupCount();
      groupCountSet.add(gCount);
      StringBuilder dChain = new StringBuilder(16);
      for (int g = 0; g < gCount; g++) {
        if (g > 0) {
          dChain.append("->");
        }
        dChain.append(sa.getGroup(g).getPos().shortForm);
      }
      derivChainSet.add(dChain.toString());
      lastGroupSet.add(sa.getLastGroup().lexicalForm());
    }

    int uItems = itemSet.size();
    int uNormLemmas = normLemmaSet.size();
    int uLastGroups = lastGroupSet.size();
    int uGroupCounts = groupCountSet.size();
    boolean sameStemLen = (stemLenSet.size() == 1);

    Observability obs;
    if (uLastGroups == K) {
      obs = Observability.FULL_MORPHO_OBSERVABLE;
    } else if (uLastGroups == 1) {
      obs = Observability.LEXICAL_ONLY_OBSERVABLE;
    } else {
      obs = Observability.PARTIAL_MORPHO_OBSERVABLE;
    }

    MacroMechanism mechanism;
    if (uItems == 1) {
      if (uGroupCounts == 1 && derivChainSet.size() == 1) {
        mechanism = MacroMechanism.M1_INFLECTIONAL_SYNCRETISM;
      } else {
        mechanism = MacroMechanism.M2_DERIVATIONAL_SHIFT;
      }
    } else if (hasProperOrAbbrv && hasCommon) {
      mechanism = MacroMechanism.M4_PROPER_VS_COMMON;
    } else if (uNormLemmas == 1 && sameStemLen) {
      if (posPairSet.size() > 1) {
        if (K > uItems && uLastGroups < K) {
          mechanism = MacroMechanism.M7_HYBRID_MULTI_LOCUS;
        } else {
          mechanism = MacroMechanism.M3_LEXICAL_POS_POLYSEMY;
        }
      } else {
        mechanism = MacroMechanism.M6b_ORTHO_OR_COMPOUND_TWIN;
      }
    } else if (!sameStemLen) {
      if (hasCompoundP3sgAttr && uNormLemmas <= 2 && rootPosSet.size() == 1) {
        mechanism = MacroMechanism.M6b_ORTHO_OR_COMPOUND_TWIN;
      } else if (uLastGroups == 1 && rootPosSet.size() == 1) {
        mechanism = MacroMechanism.M5a_VOICE_LEXICALIZED;
      } else {
        mechanism = MacroMechanism.M5b_STEM_SUFFIX_COLLISION;
      }
    } else {
      if (posPairSet.size() > 1) {
        if (K > uItems && uLastGroups < K) {
          mechanism = MacroMechanism.M7_HYBRID_MULTI_LOCUS;
        } else {
          mechanism = MacroMechanism.M6a_HOMONYM_DIFF_POS;
        }
      } else {
        mechanism = MacroMechanism.M6b_ORTHO_OR_COMPOUND_TWIN;
      }
    }

    String[] candDescs = new String[K];
    for (int i = 0; i < K; i++) {
      SingleAnalysis sa = results.get(i);
      DictionaryItem item = sa.getDictionaryItem();
      StringBuilder sb = new StringBuilder(48);

      if (uItems > 1 && !sameStemLen) {
        int delta = maxStemLen - sa.getStem().length();
        sb.append("STEM(").append(delta == 0 ? "0" : "-" + delta).append(")|");
      }

      int gCount = sa.groupCount();
      for (int g = 0; g < gCount; g++) {
        if (g > 0) {
          sb.append("->");
        }
        List<SingleAnalysis.MorphemeData> mList = sa.getGroup(g).getMorphemes();
        for (int m = 0; m < mList.size(); m++) {
          if (m > 0) {
            sb.append("+");
          }
          sb.append(mList.get(m).morpheme.id);
          if (g == 0 && m == 0 && item.secondaryPos != SecondaryPos.None) {
            sb.append(",").append(item.secondaryPos.shortForm);
          }
        }
      }

      if (uItems > 1 && posPairSet.size() == 1 && derivChainSet.size() == 1) {
        if (ALPHABET.containsCircumflex(item.lemma)) {
          sb.append("@CIRC");
        } else if (item.hasAttribute(RootAttribute.CompoundP3sg)) {
          sb.append("@COMP_P3SG");
        } else if (item.hasAttribute(RootAttribute.CompoundP3sgRoot)) {
          sb.append("@COMP_ROOT");
        } else if (item.hasAttribute(RootAttribute.LastVowelDrop)) {
          sb.append("@VDROP");
        }
      }
      candDescs[i] = sb.toString();
    }

    Arrays.sort(candDescs);
    for (int i = 1; i < K; i++) {
      if (candDescs[i].equals(candDescs[i - 1])) {
        candDescs[i] = candDescs[i] + "#ALT";
      }
    }
    String sigKey = String.join(" || ", candDescs);
    return new ZasTokenProfile(mechanism, obs, sigKey);
  }

  private static Map<Integer, MetaRow> loadMetaTsv(Path metaTsv) throws IOException {
    Map<Integer, MetaRow> map = new HashMap<>();
    if (metaTsv == null || !Files.exists(metaTsv)) {
      return map;
    }
    try (BufferedReader r = Files.newBufferedReader(metaTsv, StandardCharsets.UTF_8)) {
      String line;
      while ((line = r.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("slice_idx")) {
          continue;
        }
        String[] parts = line.split("\t");
        if (parts.length >= 3) {
          try {
            int idx = Integer.parseInt(parts[0]);
            map.put(idx, new MetaRow(parts[1], parts[2]));
          } catch (NumberFormatException ignored) {
          }
        }
      }
    }
    return map;
  }

  // --- Data Structures ---

  private static final class SliceJob {
    final String sliceName;
    final String poolType;
    final Path txtPath;
    final Path metaTsv;
    final Path outJsonl;
    final Path outMetaJson;

    SliceJob(
        String sliceName,
        String poolType,
        Path txtPath,
        Path metaTsv,
        Path outJsonl,
        Path outMetaJson) {
      this.sliceName = sliceName;
      this.poolType = poolType;
      this.txtPath = txtPath;
      this.metaTsv = metaTsv;
      this.outJsonl = outJsonl;
      this.outMetaJson = outMetaJson;
    }
  }

  private static final class MetaRow {
    final String stratum;
    final String domain;

    MetaRow(String stratum, String domain) {
      this.stratum = stratum;
      this.domain = domain;
    }
  }

  private static final class ZasTokenProfile {
    final MacroMechanism mechanism;
    final Observability observability;
    final String signature;

    ZasTokenProfile(MacroMechanism mechanism, Observability observability, String signature) {
      this.mechanism = mechanism;
      this.observability = observability;
      this.signature = signature;
    }
  }

  private static final class SliceExportSummary {
    final String sliceName;
    final int sentenceCount;
    final int tokenCount;
    final int ambiguousTokenCount;

    SliceExportSummary(String sliceName, int sentenceCount, int tokenCount, int ambiguousTokenCount) {
      this.sliceName = sliceName;
      this.sentenceCount = sentenceCount;
      this.tokenCount = tokenCount;
      this.ambiguousTokenCount = ambiguousTokenCount;
    }
  }

  public static class SentenceRecord {
    public String sentence_id;
    public String slice_name;
    public int slice_index;
    public String eval_stratum;
    public String source_domain;
    public String text;
    public int word_count;
    public int ambiguous_word_count;
    public List<TokenRecord> tokens = new ArrayList<>();
  }

  public static class TokenRecord {
    public int index;
    public String surface;
    public String token_type;
    public boolean is_ambiguous;
    public Integer selected_candidate_id;
    public String macro_mechanism;
    public String observability;
    public String zas_signature;
    public List<CandidateRecord> candidates = new ArrayList<>();
  }

  public static class CandidateRecord {
    public int id;
    public String zemberek_key;
    public String ig_key;
    public String last_group;
    public String lemma;
    public String pos;
    public String secondary_pos;
    public int derivation_count;
    public List<String> morphemes = new ArrayList<>();
  }

  public static class SliceMetadata {
    public String schema_version;
    public String slice_name;
    public String pool;
    public String source_txt;
    public String created_at;
    public int sentence_count;
    public int word_count;
    public int token_count;
    public int ambiguous_token_count;
    public double ambiguity_rate;
    public Map<String, Integer> mechanism_distribution;
    public Map<String, Integer> observability_distribution;
  }
}
