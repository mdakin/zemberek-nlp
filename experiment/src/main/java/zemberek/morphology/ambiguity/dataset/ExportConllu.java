package zemberek.morphology.ambiguity.dataset;

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
import java.util.*;
import java.util.stream.Collectors;
import zemberek.apps.ConsoleApp;
import zemberek.core.logging.Log;
import zemberek.core.turkish.PrimaryPos;
import zemberek.core.turkish.SecondaryPos;
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.ambiguity.AmbiguityResolver;
import zemberek.morphology.ambiguity.fast.FastPerceptronAmbiguityResolver;
import zemberek.morphology.analysis.AnalysisFormatters;
import zemberek.morphology.analysis.SentenceAnalysis;
import zemberek.morphology.analysis.SingleAnalysis;
import zemberek.morphology.analysis.SingleAnalysis.MorphemeData;
import zemberek.morphology.analysis.WordAnalysis;
import zemberek.morphology.lexicon.DictionaryItem;
import zemberek.morphology.lexicon.RootLexicon;

/**
 * Disambiguates Turkish text and exports annotations to CoNLL-U format for official
 * Universal Dependencies evaluation. Supports gold CoNLL-U templates to preserve MWT spans.
 * Features comprehensive diagnostic logging for unmapped tags and morphemes.
 */
public class ExportConllu extends ConsoleApp {

  @Parameter(
      names = {"--conllu", "-c"},
      required = true,
      description = "Path to gold template CoNLL-U file (e.g. tr_boun-ud-test.conllu)")
  public Path conlluPath;

  @Parameter(
      names = {"--model", "-m"},
      description = "Path to trained binary ambiguity model (.bin). If omitted, uses default baseline.")
  public Path modelPath;

  @Parameter(
      names = {"--output", "-o"},
      required = true,
      description = "Path to write predicted CoNLL-U file")
  public Path outputPath;

  @Parameter(
      names = {"--log", "-l"},
      description = "Path to diagnostic log file. Default: <output>.diagnostics.log")
  public Path logPath;

  @Parameter(
      names = {"--summary", "-s"},
      description = "Path to diagnostic summary JSON. Default: <output>.diagnostics.json")
  public Path summaryPath;

  @Parameter(
      names = {"--beamSize", "-b"},
      description = "Beam size for FastPerceptronAmbiguityResolver (default -1 for exact Viterbi).")
  public int beamSize = -1;

  @Parameter(
      names = {"--greedy", "-g"},
      description = "Enable greedy decoding mode")
  public boolean greedy = false;

  @Parameter(
      names = {"--legacy"},
      description = "Use legacy PerceptronAmbiguityResolver with model-compressed (old baseline)")
  public boolean useLegacy = false;

  public static void main(String[] args) {
    new ExportConllu().execute(args);
  }

  @Override
  public String description() {
    return "Exports Zemberek disambiguated sentences to CoNLL-U format with diagnostic logging.";
  }

  // Diagnostic tracking structures
  public static class DiagnosticReport {
    public String created_at = Instant.now().toString();
    public String model_path;
    public String template_conllu;
    public int total_sentences = 0;
    public int total_tokens = 0;
    public int total_mwts = 0;
    public int unknown_analyses = 0;
    public Map<String, Integer> unmapped_morphemes = new TreeMap<>();
    public Map<String, Integer> unmapped_pos = new TreeMap<>();
    public Map<String, Integer> resolved_conflicts = new TreeMap<>();
    public List<UnmappedExample> unmapped_examples = new ArrayList<>();
  }

  public static class UnmappedExample {
    public String sentence_id;
    public String surface;
    public String tag;
    public String full_analysis;

    public UnmappedExample(String sentence_id, String surface, String tag, String full_analysis) {
      this.sentence_id = sentence_id;
      this.surface = surface;
      this.tag = tag;
      this.full_analysis = full_analysis;
    }
  }

  private DiagnosticReport diag = new DiagnosticReport();
  private final List<String> logLines = new ArrayList<>();
  private boolean isBoun = false;

  private static boolean isPunctuation(String s) {
    if (s == null || s.isEmpty()) return false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (Character.isLetterOrDigit(c)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public void run() throws Exception {
    if (logPath == null) {
      logPath = Paths.get(outputPath.toString() + ".diagnostics.log");
    }
    if (summaryPath == null) {
      summaryPath = Paths.get(outputPath.toString() + ".diagnostics.json");
    }

    diag.template_conllu = conlluPath.toString();
    diag.model_path = modelPath != null ? modelPath.toString() : "DEFAULT_BASELINE";
    isBoun = conlluPath.getFileName().toString().toLowerCase(Locale.ROOT).contains("boun");
    Log.info("Target treebank convention: %s", isBoun ? "UD_Turkish-BOUN (ki=PART)" : "UD_Turkish-IMST (ki=ADP, Mood=Ind)");

    Log.info("Initializing TurkishMorphology...");
    TurkishMorphology morphology;
    if (useLegacy) {
      diag.model_path = modelPath != null ? ("LEGACY:" + modelPath.toString()) : "LEGACY_BASELINE_MODEL_COMPRESSED";
      if (modelPath != null) {
        Log.info("Loading legacy PerceptronAmbiguityResolver from: %s", modelPath);
        AmbiguityResolver resolver = zemberek.morphology.ambiguity.PerceptronAmbiguityResolver.fromModelFile(modelPath);
        morphology = TurkishMorphology.builder()
            .setLexicon(RootLexicon.getDefault())
            .setAmbiguityResolver(resolver)
            .build();
      } else {
        Log.info("Loading legacy PerceptronAmbiguityResolver with default resource: /tr/ambiguity/model-compressed");
        AmbiguityResolver resolver =
            zemberek.morphology.ambiguity.PerceptronAmbiguityResolver.fromResource(
                "/tr/ambiguity/model-compressed");
        morphology = TurkishMorphology.builder()
            .setLexicon(RootLexicon.getDefault())
            .setAmbiguityResolver(resolver)
            .build();
      }
    } else if (modelPath != null) {
      diag.model_path = modelPath.toString();
      Log.info("Loading ambiguity model from: %s", modelPath);
      AmbiguityResolver resolver = FastPerceptronAmbiguityResolver.fromModelFile(modelPath);
      if (resolver instanceof FastPerceptronAmbiguityResolver) {
        FastPerceptronAmbiguityResolver pr = (FastPerceptronAmbiguityResolver) resolver;
        pr.setBeamSize(beamSize);
        pr.setGreedy(greedy);
      }
      morphology = TurkishMorphology.builder()
          .setLexicon(RootLexicon.getDefault())
          .setAmbiguityResolver(resolver)
          .build();
    } else {
      Log.info("Using default baseline ambiguity resolver");
      diag.model_path = "DEFAULT_FAST_PERCEPTRON_30K";
      Log.info("Using default TurkishMorphology (FastPerceptronAmbiguityResolver with model-distilled-30k.bin)");
      morphology = TurkishMorphology.createWithDefaults();
    }

    Log.info("Reading CoNLL-U template: %s", conlluPath);
    List<ConlluSentence> sentences = parseConllu(conlluPath);
    Log.info("Parsed %d sentences from CoNLL-U template", sentences.size());

    diag.total_sentences = sentences.size();

    // Process sentences and write output
    try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
      for (int sIdx = 0; sIdx < sentences.size(); sIdx++) {
        ConlluSentence cs = sentences.get(sIdx);
        processSentence(cs, morphology, writer);
      }
    }

    Log.info("CoNLL-U export completed: %s", outputPath);

    // Save diagnostics
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    try (BufferedWriter sw = Files.newBufferedWriter(summaryPath, StandardCharsets.UTF_8)) {
      gson.toJson(diag, sw);
    }
    try (BufferedWriter lw = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8)) {
      for (String line : logLines) {
        lw.write(line);
        lw.newLine();
      }
    }

    Log.info("Diagnostics summary saved to: %s", summaryPath);
    Log.info("Diagnostic log saved to: %s", logPath);
    Log.info("Total tokens: %d, MWTs: %d, Unknown parses: %d, Unmapped morphemes types: %d",
        diag.total_tokens, diag.total_mwts, diag.unknown_analyses, diag.unmapped_morphemes.size());
  }

  private void processSentence(ConlluSentence cs, TurkishMorphology morphology, BufferedWriter writer)
      throws IOException {
    // Write comment headers (e.g. # sent_id, # text)
    for (String comment : cs.comments) {
      writer.write(comment);
      writer.newLine();
    }

    // Build token list for disambiguation
    List<String> surfaces = new ArrayList<>();
    for (ConlluItem item : cs.items) {
      surfaces.add(item.surface);
    }

    // Analyze tokens
    List<WordAnalysis> analyses = new ArrayList<>(surfaces.size());
    for (String surface : surfaces) {
      analyses.add(morphology.analyze(surface));
    }

    // Disambiguate
    SentenceAnalysis sentenceAnalysis = morphology.disambiguate(cs.text, analyses);
    List<SingleAnalysis> bestAnalyses = sentenceAnalysis.bestAnalysis();

    // Write CoNLL-U lines
    for (int i = 0; i < cs.items.size(); i++) {
      ConlluItem item = cs.items.get(i);
      SingleAnalysis best = bestAnalyses.get(i);
      diag.total_tokens++;

      if (best.isUnknown()) {
        diag.unknown_analyses++;
        logEvent("UNKNOWN_TOKEN", cs.sentId, item.surface, "No morphological parse available");
      }

      if (item.isMwt) {
        diag.total_mwts++;
        // Write MWT header line (e.g. 4-5\tağlayacaktı\t_\t_\t_\t_\t_\t_\t_\tSpaceAfter=No)
        writer.write(item.rawMwtLine);
        writer.newLine();

        // Project best analysis to the sub-tokens
        List<ConlluToken> subTokens = projectMwt(item, best, cs.sentId);
        for (ConlluToken st : subTokens) {
          writer.write(st.toConlluLine());
          writer.newLine();
        }
      } else {
        // Single token
        ConlluToken tok = projectSingle(item.subTokens.get(0), best, cs.sentId);
        writer.write(tok.toConlluLine());
        writer.newLine();
      }
    }

    // Trailing newline after sentence
    writer.newLine();
  }

  // --- Projection Logic ---

  private ConlluToken projectSingle(ConlluToken goldToken, SingleAnalysis analysis, String sentId) {
    ConlluToken pred = new ConlluToken(goldToken);
    if (analysis.isUnknown()) {
      pred.lemma = goldToken.form.toLowerCase(Locale.forLanguageTag("tr"));
      pred.upos = "X";
      pred.xpos = "_";
      pred.feats = "_";
      String surface = goldToken.form;
      if (isPunctuation(surface)) {
        pred.lemma = surface;
        pred.upos = "PUNCT";
        pred.feats = "_";
      } else if (!surface.isEmpty() && Character.isUpperCase(surface.charAt(0))) {
        pred.lemma = surface;
        pred.upos = "PROPN";
        pred.feats = "Case=Nom|Number=Sing|Person=3";
      } else {
        pred.lemma = surface.toLowerCase(Locale.forLanguageTag("tr"));
        pred.upos = "NOUN";
        pred.feats = "Case=Nom|Number=Sing|Person=3";
      }
      return pred;
    }

    String lemma = extractLemma(analysis);
    String upos = determineUpos(analysis, sentId, goldToken.form);
    String feats = extractFeatures(analysis, upos, sentId, goldToken.form);

    pred.lemma = lemma;
    pred.upos = upos;
    pred.feats = feats;
    return pred;
  }

  private List<ConlluToken> projectMwt(ConlluItem mwtItem, SingleAnalysis analysis, String sentId) {
    List<ConlluToken> result = new ArrayList<>();
    if (mwtItem.subTokens.size() < 2) {
      for (ConlluToken st : mwtItem.subTokens) {
        result.add(projectSingle(st, analysis, sentId));
      }
      return result;
    }

    ConlluToken part1 = new ConlluToken(mwtItem.subTokens.get(0));
    ConlluToken part2 = new ConlluToken(mwtItem.subTokens.get(1));

    if (analysis.isUnknown()) {
      String surf1 = part1.form;
      if (isPunctuation(surf1)) {
        part1.lemma = surf1;
        part1.upos = "PUNCT";
        part1.feats = "_";
      } else if (!surf1.isEmpty() && Character.isUpperCase(surf1.charAt(0))) {
        part1.lemma = surf1;
        part1.upos = "PROPN";
        part1.feats = "Case=Nom|Number=Sing|Person=3";
      } else {
        part1.lemma = surf1.toLowerCase(Locale.forLanguageTag("tr"));
        part1.upos = "NOUN";
        part1.feats = "Case=Nom|Number=Sing|Person=3";
      }

      String form2 = part2.form.toLowerCase(Locale.forLanguageTag("tr"));
      if (form2.equals("ki")) {
        part2.lemma = "ki";
        part2.upos = isBoun ? "PART" : "ADP";
        part2.feats = "_";
      } else if (form2.matches("l[ıiuü]")) {
        part2.lemma = "li";
        part2.upos = "ADP";
        part2.feats = "_";
      } else if (form2.matches("s[ıiuü]z")) {
        part2.lemma = "siz";
        part2.upos = "ADP";
        part2.feats = "_";
      } else if (form2.matches("c[ae]") || form2.matches("ç[ae]")) {
        part2.lemma = "ce";
        part2.upos = "ADP";
        part2.feats = "_";
      } else if (form2.matches("l[ıiuü]k")) {
        part2.lemma = "lik";
        part2.upos = "ADP";
        part2.feats = "_";
      } else {
        part2.lemma = "i";
        part2.upos = "AUX";
        part2.feats = "_";
      }
      result.add(part1);
      result.add(part2);
      for (int k = 2; k < mwtItem.subTokens.size(); k++) {
        ConlluToken extra = new ConlluToken(mwtItem.subTokens.get(k));
        extra.feats = "_";
        result.add(extra);
      }
      return result;
    }

    // Inspect inflectional groups
    int groupCount = analysis.groupCount();
    if (groupCount >= 2) {
      // The rightmost group is the copula / bound auxiliary (e.g. Zero+Past+A3sg or Dir+A3sg)
      SingleAnalysis.MorphemeGroup copulaGroup = analysis.getGroup(groupCount - 1);
      
      // Stem / base part
      part1.lemma = extractLemma(analysis);
      part1.upos = determineUposForStem(analysis, sentId, part1.form);
      part1.feats = extractFeaturesForGroups(analysis, 0, groupCount - 2, part1.upos, sentId, part1.form);

      // Handle copulas vs other MWT derivations (Sulubacak 2016 Table 1a)
      String form2 = part2.form.toLowerCase(Locale.forLanguageTag("tr"));
      if (form2.equals("ki")) {
        part2.lemma = "ki";
        part2.upos = isBoun ? "PART" : "ADP";
        part2.feats = "_";
      } else if (form2.matches("l[ıiuü]")) {
        part2.lemma = "li";
        part2.upos = "ADP";
        part2.feats = "_";
      } else if (form2.matches("s[ıiuü]z")) {
        part2.lemma = "siz";
        part2.upos = "ADP";
        part2.feats = "_";
      } else if (form2.matches("c[ae]") || form2.matches("ç[ae]")) {
        part2.lemma = "ce";
        part2.upos = "ADP";
        part2.feats = "_";
      } else if (form2.matches("l[ıiuü]k")) {
        part2.lemma = "lik";
        part2.upos = "ADP";
        part2.feats = "_";
      } else {
        part2.lemma = "i";
        part2.upos = "AUX";
        part2.feats = extractFeaturesForGroup(copulaGroup, "AUX", sentId, part2.form);
      }
    } else {
      // Single group fallback
      part1.lemma = extractLemma(analysis);
      part1.upos = determineUpos(analysis, sentId, part1.form);
      part1.feats = extractFeatures(analysis, part1.upos, sentId, part1.form);

      String form2 = part2.form.toLowerCase(Locale.forLanguageTag("tr"));
      if (form2.equals("ki")) {
        part2.lemma = "ki";
        part2.upos = isBoun ? "PART" : "ADP";
        part2.feats = "_";
      } else if (form2.matches("l[ıiuü]")) {
        part2.lemma = "li";
        part2.upos = "ADP";
        part2.feats = "_";
      } else if (form2.matches("s[ıiuü]z")) {
        part2.lemma = "siz";
        part2.upos = "ADP";
        part2.feats = "_";
      } else if (form2.matches("c[ae]") || form2.matches("ç[ae]")) {
        part2.lemma = "ce";
        part2.upos = "ADP";
        part2.feats = "_";
      } else if (form2.matches("l[ıiuü]k")) {
        part2.lemma = "lik";
        part2.upos = "ADP";
        part2.feats = "_";
      } else {
        part2.lemma = "i";
        part2.upos = "AUX";
        part2.feats = "_";
      }
      logEvent("MWT_SINGLE_GROUP_FALLBACK", sentId, mwtItem.surface,
          "Word was MWT in template but Zemberek produced 1 group: " + analysis.formatLong());
    }

    result.add(part1);
    result.add(part2);
    // If there are more than 2 sub-tokens (extremely rare in Turkish UD)
    for (int k = 2; k < mwtItem.subTokens.size(); k++) {
      ConlluToken extra = new ConlluToken(mwtItem.subTokens.get(k));
      extra.feats = "_";
      result.add(extra);
    }

    return result;
  }

  // --- Lemma Extraction ---

  private String extractLemma(SingleAnalysis analysis) {
    DictionaryItem item = analysis.getDictionaryItem();
    if (item == null || item.isUnknown()) {
      return analysis.getStem().toLowerCase(Locale.forLanguageTag("tr"));
    }
    // For verbs, UD Turkish uses the verb stem without -mek/-mak (e.g. 'yaşa', 'gel', 'git')
    if (item.primaryPos == PrimaryPos.Verb) {
      return item.root;
    }
    // For proper nouns and other lexical items, use root or dictionary lemma
    if (item.primaryPos == PrimaryPos.Noun && item.secondaryPos == SecondaryPos.ProperNoun) {
      return item.lemma;
    }
    // If derived lexicalized noun/adj, check lemmas list
    List<String> lemmas = analysis.getLemmas();
    if (!lemmas.isEmpty()) {
      return lemmas.get(lemmas.size() - 1);
    }
    return item.root;
  }

  // --- UPOS Determination ---

  private String determineUpos(SingleAnalysis analysis, String sentId, String form) {
    List<String> morphs = analysis.getMorphemes().stream().map(m -> m.id).collect(Collectors.toList());

    // Verbal derivations take UPOS=VERB in UD (Sulubacak 2016 Table 3)
    if (morphs.contains("PresPart") || morphs.contains("PastPart") || morphs.contains("FutPart") || morphs.contains("AorPart") || morphs.contains("NarrPart")) {
      return "VERB";
    }
    if (morphs.contains("Inf") || morphs.contains("Inf1") || morphs.contains("Inf2") || morphs.contains("Inf3") || morphs.contains("ActOf")) {
      return "VERB";
    }
    if (morphs.contains("ByDoingSo") || morphs.contains("While") || morphs.contains("SinceDoingSo")
        || morphs.contains("When") || morphs.contains("AfterDoingSo") || morphs.contains("AsIf") || morphs.contains("AsLongAs")
        || morphs.contains("EverSince") || morphs.contains("Adamantly") || morphs.contains("WithoutHavingDoneSo") || morphs.contains("WithoutBeingAbleToHaveDoneSo")) {
      return "VERB";
    }

    DictionaryItem item = analysis.getDictionaryItem();
    PrimaryPos pos = analysis.getPos();
    if (pos == null) {
      pos = item.primaryPos;
    }

    if (item.primaryPos == PrimaryPos.Verb) {
      // Auxiliaries like 'değil'
      if (item.root.equals("değil")) {
        return "AUX";
      }
      return "VERB";
    }

    switch (pos) {
      case Noun:
        if (item.secondaryPos == SecondaryPos.ProperNoun) {
          return "PROPN";
        }
        return "NOUN";
      case Verb:
        return "VERB";
      case Adjective:
        return "ADJ";
      case Adverb:
      case Duplicator:
        return "ADV";
      case Pronoun:
        return "PRON";
      case Determiner:
        return "DET";
      case Conjunction:
        // 'ki' or subordinating can be SCONJ or CCONJ, default CCONJ
        if (form.equalsIgnoreCase("ki") || form.equalsIgnoreCase("eğer") || form.equalsIgnoreCase("çünkü")) {
          return "SCONJ";
        }
        return "CCONJ";
      case PostPositive:
        return "ADP";
      case Question:
        return "AUX";
      case Interjection:
        return "INTJ";
      case Numeral:
        return "NUM";
      case Punctuation:
        return "PUNCT";
      default:
        recordUnmappedPos(pos.name(), sentId, form, analysis.formatLong());
        return "NOUN";
    }
  }

  private String determineUposForStem(SingleAnalysis analysis, String sentId, String form) {
    // For stem of MWT before copula
    int groupCount = analysis.groupCount();
    if (groupCount >= 2) {
      SingleAnalysis.MorphemeGroup stemGroup = analysis.getGroup(groupCount - 2);
      List<String> morphs = stemGroup.getMorphemes().stream().map(m -> m.morpheme.id).collect(Collectors.toList());

      if (morphs.contains("PresPart") || morphs.contains("PastPart") || morphs.contains("FutPart") || morphs.contains("AorPart")) {
        return "VERB";
      }
      if (morphs.contains("Inf") || morphs.contains("Inf1") || morphs.contains("Inf2") || morphs.contains("Inf3")) {
        return "VERB";
      }
      PrimaryPos gPos = stemGroup.getPos();
      if (gPos != null && gPos != PrimaryPos.Unknown) {
        if (gPos == PrimaryPos.Noun) return "NOUN";
        if (gPos == PrimaryPos.Adjective) return "ADJ";
        if (gPos == PrimaryPos.Adverb) return "ADV";
        if (gPos == PrimaryPos.Verb) return "VERB";
        if (gPos == PrimaryPos.Pronoun) return "PRON";
      }
    }
    return determineUpos(analysis, sentId, form);
  }

  // --- Feature Mapping (Sak et al. & TABI Table 12) ---

  private static final Map<String, String[]> SAK_TO_UD_MAP = new HashMap<>();

  static {
    // Agreement
    SAK_TO_UD_MAP.put("A1sg", new String[]{"Number=Sing", "Person=1"});
    SAK_TO_UD_MAP.put("A2sg", new String[]{"Number=Sing", "Person=2"});
    SAK_TO_UD_MAP.put("A3sg", new String[]{"Number=Sing", "Person=3"});
    SAK_TO_UD_MAP.put("A1pl", new String[]{"Number=Plur", "Person=1"});
    SAK_TO_UD_MAP.put("A2pl", new String[]{"Number=Plur", "Person=2"});
    SAK_TO_UD_MAP.put("A3pl", new String[]{"Number=Plur", "Person=3"});

    // Possessive
    SAK_TO_UD_MAP.put("P1sg", new String[]{"Number[psor]=Sing", "Person[psor]=1"});
    SAK_TO_UD_MAP.put("P2sg", new String[]{"Number[psor]=Sing", "Person[psor]=2"});
    SAK_TO_UD_MAP.put("P3sg", new String[]{"Number[psor]=Sing", "Person[psor]=3"});
    SAK_TO_UD_MAP.put("P1pl", new String[]{"Number[psor]=Plur", "Person[psor]=1"});
    SAK_TO_UD_MAP.put("P2pl", new String[]{"Number[psor]=Plur", "Person[psor]=2"});
    SAK_TO_UD_MAP.put("P3pl", new String[]{"Number[psor]=Plur", "Person[psor]=3"});

    // Case
    SAK_TO_UD_MAP.put("Nom", new String[]{"Case=Nom"});
    SAK_TO_UD_MAP.put("Acc", new String[]{"Case=Acc"});
    SAK_TO_UD_MAP.put("Dat", new String[]{"Case=Dat"});
    SAK_TO_UD_MAP.put("Gen", new String[]{"Case=Gen"});
    SAK_TO_UD_MAP.put("Loc", new String[]{"Case=Loc"});
    SAK_TO_UD_MAP.put("Abl", new String[]{"Case=Abl"});
    SAK_TO_UD_MAP.put("Ins", new String[]{"Case=Ins"});
    SAK_TO_UD_MAP.put("Equ", new String[]{"Case=Equ"});

    // Voice
    SAK_TO_UD_MAP.put("Pass", new String[]{"Voice=Pass"});
    SAK_TO_UD_MAP.put("Caus", new String[]{"Voice=Cau"});
    SAK_TO_UD_MAP.put("Reflex", new String[]{"Voice=Rfl"});
    SAK_TO_UD_MAP.put("Recip", new String[]{"Voice=Rcp"});

    // Polarity
    SAK_TO_UD_MAP.put("Pos", new String[]{"Polarity=Pos"});
    SAK_TO_UD_MAP.put("Neg", new String[]{"Polarity=Neg"});

    // Tense / Aspect
    SAK_TO_UD_MAP.put("Past", new String[]{"Aspect=Perf", "Evident=Fh", "Tense=Past"});
    SAK_TO_UD_MAP.put("Narr", new String[]{"Evident=Nfh", "Tense=Past"});
    SAK_TO_UD_MAP.put("Fut", new String[]{"Aspect=Imp", "Tense=Fut"});
    SAK_TO_UD_MAP.put("Aor", new String[]{"Aspect=Hab", "Tense=Aor"});
    SAK_TO_UD_MAP.put("Pres", new String[]{"Aspect=Imp", "Tense=Pres"});
    SAK_TO_UD_MAP.put("Prog1", new String[]{"Aspect=Prog", "Tense=Pres"});
    SAK_TO_UD_MAP.put("Prog2", new String[]{"Aspect=Prog", "Tense=Pres"});

    // Mood
    SAK_TO_UD_MAP.put("Desr", new String[]{"Mood=Des"});
    SAK_TO_UD_MAP.put("Cond", new String[]{"Mood=Cnd"});
    SAK_TO_UD_MAP.put("Neces", new String[]{"Mood=Nec"});
    SAK_TO_UD_MAP.put("Opt", new String[]{"Mood=Opt"});
    SAK_TO_UD_MAP.put("Imp", new String[]{"Mood=Imp"});
    SAK_TO_UD_MAP.put("Able", new String[]{"Mood=Abil"});
    SAK_TO_UD_MAP.put("Repeat", new String[]{"Mood=Iter"});
    SAK_TO_UD_MAP.put("Hastily", new String[]{"Mood=Rapid"});
    SAK_TO_UD_MAP.put("Almost", new String[]{"Mood=Pro"});
    SAK_TO_UD_MAP.put("Stay", new String[]{"Mood=Dur"});

    // Participles & Gerunds
    SAK_TO_UD_MAP.put("Inf", new String[]{"VerbForm=Vnoun"});
    SAK_TO_UD_MAP.put("Inf1", new String[]{"VerbForm=Vnoun"});
    SAK_TO_UD_MAP.put("Inf2", new String[]{"VerbForm=Vnoun"});
    SAK_TO_UD_MAP.put("Inf3", new String[]{"VerbForm=Vnoun"});
    SAK_TO_UD_MAP.put("ActOf", new String[]{"VerbForm=Vnoun"});
    SAK_TO_UD_MAP.put("PresPart", new String[]{"VerbForm=Part", "Tense=Pres"});
    SAK_TO_UD_MAP.put("PastPart", new String[]{"VerbForm=Part", "Aspect=Perf", "Tense=Past"});
    SAK_TO_UD_MAP.put("NarrPart", new String[]{"VerbForm=Part", "Aspect=Perf", "Evident=Nfh", "Tense=Past"});
    SAK_TO_UD_MAP.put("FutPart", new String[]{"VerbForm=Part", "Aspect=Imp", "Tense=Fut"});
    SAK_TO_UD_MAP.put("AorPart", new String[]{"VerbForm=Part", "Tense=Aor"});

    // Converbs
    SAK_TO_UD_MAP.put("ByDoingSo", new String[]{"VerbForm=Conv", "Mood=Imp"});
    SAK_TO_UD_MAP.put("While", new String[]{"VerbForm=Conv", "Mood=Imp"});
    SAK_TO_UD_MAP.put("SinceDoingSo", new String[]{"VerbForm=Conv"});
    SAK_TO_UD_MAP.put("AsLongAs", new String[]{"VerbForm=Conv"});
    SAK_TO_UD_MAP.put("AfterDoingSo", new String[]{"VerbForm=Conv"});
    SAK_TO_UD_MAP.put("When", new String[]{"VerbForm=Conv"});
    SAK_TO_UD_MAP.put("AsIf", new String[]{"VerbForm=Conv"});
    SAK_TO_UD_MAP.put("EverSince", new String[]{"VerbForm=Conv"});
    SAK_TO_UD_MAP.put("Adamantly", new String[]{"VerbForm=Conv"});
    SAK_TO_UD_MAP.put("WithoutHavingDoneSo", new String[]{"VerbForm=Conv", "Polarity=Neg"});
    SAK_TO_UD_MAP.put("WithoutBeingAbleToHaveDoneSo", new String[]{"VerbForm=Conv", "Mood=Abil", "Polarity=Neg"});

    // Negative Ability
    SAK_TO_UD_MAP.put("Unable", new String[]{"Mood=Abil", "Polarity=Neg"});

    // Pronouns
    SAK_TO_UD_MAP.put("DemonsP", new String[]{"PronType=Dem"});
    SAK_TO_UD_MAP.put("Demons", new String[]{"PronType=Dem"});
    SAK_TO_UD_MAP.put("QuesP", new String[]{"PronType=Int"});
    SAK_TO_UD_MAP.put("Ques", new String[]{"PronType=Int"});
    SAK_TO_UD_MAP.put("ReflexP", new String[]{"PronType=Prs", "Reflex=Yes"});
    SAK_TO_UD_MAP.put("Reflex", new String[]{"PronType=Prs", "Reflex=Yes"});
    SAK_TO_UD_MAP.put("PersP", new String[]{"PronType=Prs"});
    SAK_TO_UD_MAP.put("Pers", new String[]{"PronType=Prs"});
    SAK_TO_UD_MAP.put("QuantP", new String[]{"PronType=Ind"});
    SAK_TO_UD_MAP.put("Quant", new String[]{"PronType=Ind"});

    // Numerals
    SAK_TO_UD_MAP.put("Card", new String[]{"NumType=Card"});
    SAK_TO_UD_MAP.put("Ord", new String[]{"NumType=Ord"});
    SAK_TO_UD_MAP.put("Distrib", new String[]{"NumType=Dist"});
    SAK_TO_UD_MAP.put("Ratio", new String[]{"NumType=Frac"});
    SAK_TO_UD_MAP.put("Range", new String[]{"NumType=Range"});

    // Structural morphemes that deliberately do not map to UD inflectional features
    String[] empty = new String[0];
    SAK_TO_UD_MAP.put("Zero", empty);
    SAK_TO_UD_MAP.put("Pnon", empty);
    SAK_TO_UD_MAP.put("Noun", empty);
    SAK_TO_UD_MAP.put("Verb", empty);
    SAK_TO_UD_MAP.put("Adj", empty);
    SAK_TO_UD_MAP.put("Adv", empty);
    SAK_TO_UD_MAP.put("Pron", empty);
    SAK_TO_UD_MAP.put("Det", empty);
    SAK_TO_UD_MAP.put("Conj", empty);
    SAK_TO_UD_MAP.put("Postp", empty);
    SAK_TO_UD_MAP.put("Punc", empty);
    SAK_TO_UD_MAP.put("Interj", empty);
    SAK_TO_UD_MAP.put("Num", empty);
    SAK_TO_UD_MAP.put("Dup", empty);
    SAK_TO_UD_MAP.put("Prop", empty);
    SAK_TO_UD_MAP.put("Abbrv", empty);
    SAK_TO_UD_MAP.put("Without", empty);
    SAK_TO_UD_MAP.put("With", empty);
    SAK_TO_UD_MAP.put("Rel", empty);
    SAK_TO_UD_MAP.put("Ness", empty);
    SAK_TO_UD_MAP.put("Agt", empty);
    SAK_TO_UD_MAP.put("Dim", empty);
    SAK_TO_UD_MAP.put("FitFor", empty);
    SAK_TO_UD_MAP.put("Acquire", empty);
    SAK_TO_UD_MAP.put("Become", empty);
    SAK_TO_UD_MAP.put("FeelLike", empty);
    SAK_TO_UD_MAP.put("NotState", empty);
    SAK_TO_UD_MAP.put("Ly", empty);
    SAK_TO_UD_MAP.put("Related", empty);
    SAK_TO_UD_MAP.put("JustLike", empty);
    SAK_TO_UD_MAP.put("Start", empty);
    SAK_TO_UD_MAP.put("Copula", empty);
    SAK_TO_UD_MAP.put("Cop", empty);
  }

  private String extractFeatures(SingleAnalysis analysis, String upos, String sentId, String form) {
    return extractFeaturesForMorphemes(analysis.getMorphemeDataList(), upos, sentId, form, analysis.formatLong());
  }

  private String extractFeaturesForGroups(SingleAnalysis analysis, int startGroup, int endGroup,
                                          String upos, String sentId, String form) {
    List<MorphemeData> morphemes = new ArrayList<>();
    for (int g = startGroup; g <= endGroup && g < analysis.groupCount(); g++) {
      morphemes.addAll(analysis.getGroup(g).getMorphemes());
    }
    return extractFeaturesForMorphemes(morphemes, upos, sentId, form, analysis.formatLong());
  }

  private String extractFeaturesForGroup(SingleAnalysis.MorphemeGroup group, String upos, String sentId, String form) {
    return extractFeaturesForMorphemes(group.getMorphemes(), upos, sentId, form, "Group:" + group.surfaceForm());
  }

  private String extractFeaturesForMorphemes(List<MorphemeData> morphemeDataList, String upos,
                                             String sentId, String form, String context) {
    List<String> rawFeats = new ArrayList<>();

    for (MorphemeData md : morphemeDataList) {
      String mId = md.morpheme.id;
      if (SAK_TO_UD_MAP.containsKey(mId)) {
        String[] mapped = SAK_TO_UD_MAP.get(mId);
        Collections.addAll(rawFeats, mapped);
      } else {
        recordUnmappedMorpheme(mId, sentId, form, context);
      }
    }

    if (rawFeats.isEmpty()) {
      return "_";
    }

    // Resolve conflicts (convert_sak_morp_to_ud_morp.py lines 75-83)
    if (rawFeats.contains("Tense=Past") && rawFeats.contains("Tense=Pres")) {
      rawFeats.remove("Tense=Pres");
      recordConflict("Tense=Past+Pres->Past");
    }

    int pastCount = 0;
    for (String f : rawFeats) {
      if (f.equals("Tense=Past")) pastCount++;
    }
    if (pastCount >= 2 && rawFeats.contains("Evident=Fh") && rawFeats.contains("Evident=Nfh")) {
      rawFeats.remove("Evident=Nfh");
      rawFeats.remove("Tense=Past");
      rawFeats.remove("Tense=Past");
      rawFeats.add("Tense=Pqb");
      recordConflict("Past+Narr->Tense=Pqb");
    }

    // Deduplicate by feature key, keeping the last (rightmost) inflection
    Map<String, String> uniqueFeats = new LinkedHashMap<>();
    for (String feat : rawFeats) {
      if (feat.contains("=")) {
        String key = feat.split("=", 2)[0];
        uniqueFeats.put(key, feat);
      }
    }

    // Apply UD Turkish zero-morpheme conventions
    if ("NOUN".equals(upos) || "PROPN".equals(upos) || "PRON".equals(upos)) {
      if (!uniqueFeats.containsKey("Case")) {
        uniqueFeats.put("Case", "Case=Nom");
      }
      if (!uniqueFeats.containsKey("Number")) {
        uniqueFeats.put("Number", "Number=Sing");
      }
      if (!uniqueFeats.containsKey("Person")) {
        uniqueFeats.put("Person", "Person=3");
      }
    } else if ("VERB".equals(upos)) {
      if (!uniqueFeats.containsKey("Polarity")) {
        uniqueFeats.put("Polarity", "Polarity=Pos");
      }
      if (!uniqueFeats.containsKey("VerbForm")) {
        if (!uniqueFeats.containsKey("Number")) {
          uniqueFeats.put("Number", "Number=Sing");
        }
        if (!uniqueFeats.containsKey("Person")) {
          uniqueFeats.put("Person", "Person=3");
        }
      }
      if (!isBoun && !uniqueFeats.containsKey("Mood")) {
        uniqueFeats.put("Mood", "Mood=Ind");
      }
    } else if ("AUX".equals(upos)) {
      if (!uniqueFeats.containsKey("Number")) {
        uniqueFeats.put("Number", "Number=Sing");
      }
      if (!uniqueFeats.containsKey("Person")) {
        uniqueFeats.put("Person", "Person=3");
      }
      if (!isBoun && !uniqueFeats.containsKey("Mood")) {
        uniqueFeats.put("Mood", "Mood=Ind");
      }
    }

    if (uniqueFeats.isEmpty()) {
      return "_";
    }

    List<String> sorted = new ArrayList<>(uniqueFeats.values());
    Collections.sort(sorted);
    return String.join("|", sorted);
  }

  private void recordUnmappedMorpheme(String morpheme, String sentId, String form, String context) {
    diag.unmapped_morphemes.put(morpheme, diag.unmapped_morphemes.getOrDefault(morpheme, 0) + 1);
    if (diag.unmapped_examples.size() < 100) {
      diag.unmapped_examples.add(new UnmappedExample(sentId, form, morpheme, context));
    }
    logEvent("UNMAPPED_MORPHEME", sentId, form, "Morpheme '" + morpheme + "' has no UD mapping");
  }

  private void recordUnmappedPos(String pos, String sentId, String form, String context) {
    diag.unmapped_pos.put(pos, diag.unmapped_pos.getOrDefault(pos, 0) + 1);
    logEvent("UNMAPPED_POS", sentId, form, "POS '" + pos + "' defaulted to NOUN in: " + context);
  }

  private void recordConflict(String conflictType) {
    diag.resolved_conflicts.put(conflictType, diag.resolved_conflicts.getOrDefault(conflictType, 0) + 1);
  }

  private void logEvent(String eventType, String sentId, String form, String details) {
    String msg = String.format("[%s] sent=%s word='%s' -> %s", eventType, sentId, form, details);
    logLines.add(msg);
  }

  // --- CoNLL-U Parser & Data Structures ---

  public static class ConlluSentence {
    public String sentId = "";
    public String text = "";
    public List<String> comments = new ArrayList<>();
    public List<ConlluItem> items = new ArrayList<>();
  }

  public static class ConlluItem {
    public String surface;
    public boolean isMwt = false;
    public String rawMwtLine = "";
    public List<ConlluToken> subTokens = new ArrayList<>();
  }

  public static class ConlluToken {
    public String id;
    public String form;
    public String lemma = "_";
    public String upos = "_";
    public String xpos = "_";
    public String feats = "_";
    public String head = "_";
    public String deprel = "_";
    public String deps = "_";
    public String misc = "_";

    public ConlluToken() {}

    public ConlluToken(ConlluToken other) {
      this.id = other.id;
      this.form = other.form;
      this.lemma = other.lemma;
      this.upos = other.upos;
      this.xpos = other.xpos;
      this.feats = other.feats;
      this.head = other.head;
      this.deprel = other.deprel;
      this.deps = other.deps;
      this.misc = other.misc;
    }

    public static ConlluToken fromLine(String line) {
      String[] p = line.split("\t", -1);
      ConlluToken t = new ConlluToken();
      t.id = p[0];
      t.form = p.length > 1 ? p[1] : "_";
      t.lemma = p.length > 2 ? p[2] : "_";
      t.upos = p.length > 3 ? p[3] : "_";
      t.xpos = p.length > 4 ? p[4] : "_";
      t.feats = p.length > 5 ? p[5] : "_";
      // Sanitize gold annotations to ensure complete evaluation integrity (no leakage)
      t.lemma = "_";
      t.upos = "_";
      t.xpos = "_";
      t.feats = "_";
      t.head = p.length > 6 ? p[6] : "_";
      t.deprel = p.length > 7 ? p[7] : "_";
      t.deps = p.length > 8 ? p[8] : "_";
      t.deps = "_";
      t.misc = p.length > 9 ? p[9] : "_";
      return t;
    }

    public String toConlluLine() {
      return String.join("\t", id, form, lemma, upos, xpos, feats, head, deprel, deps, misc);
    }
  }

  private List<ConlluSentence> parseConllu(Path path) throws IOException {
    List<ConlluSentence> list = new ArrayList<>();
    ConlluSentence curr = new ConlluSentence();
    ConlluItem currMwt = null;
    int mwtExpectedRemaining = 0;

    try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String line;
      while ((line = r.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty()) {
          if (!curr.items.isEmpty()) {
            list.add(curr);
            curr = new ConlluSentence();
            currMwt = null;
            mwtExpectedRemaining = 0;
          }
          continue;
        }

        if (line.startsWith("#")) {
          curr.comments.add(line);
          if (line.startsWith("# sent_id =")) {
            curr.sentId = line.substring(line.indexOf('=') + 1).trim();
          } else if (line.startsWith("# text =")) {
            curr.text = line.substring(line.indexOf('=') + 1).trim();
          }
          continue;
        }

        String[] cols = line.split("\t", -1);
        String idCol = cols[0];

        if (idCol.contains("-")) {
          // MWT line (e.g. 4-5\tağlayacaktı\t...)
          String[] span = idCol.split("-");
          int start = Integer.parseInt(span[0]);
          int end = Integer.parseInt(span[1]);
          mwtExpectedRemaining = end - start + 1;

          currMwt = new ConlluItem();
          currMwt.isMwt = true;
          currMwt.surface = cols[1];
          currMwt.rawMwtLine = line;
          curr.items.add(currMwt);
        } else {
          ConlluToken tok = ConlluToken.fromLine(line);
          if (currMwt != null && mwtExpectedRemaining > 0) {
            currMwt.subTokens.add(tok);
            mwtExpectedRemaining--;
            if (mwtExpectedRemaining == 0) {
              currMwt = null;
            }
          } else {
            ConlluItem single = new ConlluItem();
            single.isMwt = false;
            single.surface = tok.form;
            single.subTokens.add(tok);
            curr.items.add(single);
          }
        }
      }
      if (!curr.items.isEmpty()) {
        list.add(curr);
      }
    }
    return list;
  }
}
