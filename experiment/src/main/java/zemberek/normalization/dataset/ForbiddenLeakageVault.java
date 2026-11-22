package zemberek.normalization.dataset;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import zemberek.core.turkish.Turkish;
import zemberek.core.turkish.TurkishAlphabet;
import zemberek.tokenization.TurkishSentenceExtractor;

/**
 * Anti-leakage vault blocking exact and sentence-split overlaps against all held-out normalization,
 * spellcheck, disambiguation, and downstream classification benchmarks:
 * 1. nasty_5k (5,000 sentences kept on the side)
 * 2. MultiLexNorm (tur & tur_deu train + test)
 * 3. GECTurk / BOUN clitics (source & target train/dev/test)
 * 4. Mukayese TRSpell10 (test.csv)
 * 5. Disambiguation Permanent Held-Out 10K Manifest
 * 6. OffensEval-2020 Turkish official testset-v1 (3,515 tweets)
 * 7. Winvoker Sentiment test.csv (48,965 reviews + extracted sentences)
 * 8. Beyazperde Movie Reviews valid.json & test.json (10,000 reviews + extracted sentences)
 */
public final class ForbiddenLeakageVault {

  private static final Locale TR = Turkish.LOCALE;
  private static final Pattern NON_ALNUM_RE = Pattern.compile("[^\\p{L}\\p{Nd}]+");
  private static final Pattern MULTI_SPACE_RE = Pattern.compile("\\s+");

  private final Set<String> forbiddenSkeletons = new HashSet<>(160_000);
  // 5-word shingle hashes from benchmark sentences to prevent substring/near-duplicate leakage.
  private final Set<Long> benchmark5GramHashes = new HashSet<>(250_000);

  public static String normalizeSkeleton(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    String s = TurkishAlphabet.INSTANCE.toAscii(text.toLowerCase(TR));
    return NON_ALNUM_RE.matcher(s).replaceAll("");
  }

  public static String normalizeSpacedWords(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    String s = TurkishAlphabet.INSTANCE.toAscii(text.toLowerCase(TR));
    s = NON_ALNUM_RE.matcher(s).replaceAll(" ").trim();
    return MULTI_SPACE_RE.matcher(s).replaceAll(" ");
  }

  public void loadAll(Path baseNormDir, Path turkishDataRoot) throws IOException {
    loadAll(baseNormDir, turkishDataRoot, true);
  }

  public void loadAll(Path baseNormDir, Path turkishDataRoot, boolean includeSynthetic1k)
      throws IOException {
    int before;

    // 1. nasty_5k (kept untouched on the side)
    before = forbiddenSkeletons.size();
    loadPlainLines(baseNormDir.resolve("custom_benchmark/nasty_5k/nasty_5000_sentences.txt"), true);
    int nastyAdded = forbiddenSkeletons.size() - before;

    // 2. MultiLexNorm (tur + tur_deu)
    for (String rel : List.of(
        "benchmarks/multilexnorm/tur/train.norm",
        "benchmarks/multilexnorm/tur/test.norm",
        "benchmarks/multilexnorm/tur_deu/train.norm",
        "benchmarks/multilexnorm/tur_deu/test.norm")) {
      loadMultiLexNorm(baseNormDir.resolve(rel));
    }

    // 3. GECTurk / BOUN
    for (String rel : List.of(
        "benchmarks/gecturk_boun_clitics/boun_source_train.txt",
        "benchmarks/gecturk_boun_clitics/boun_source_dev.txt",
        "benchmarks/gecturk_boun_clitics/boun_source_test.txt",
        "benchmarks/gecturk_boun_clitics/boun_target_train.txt",
        "benchmarks/gecturk_boun_clitics/boun_target_dev.txt",
        "benchmarks/gecturk_boun_clitics/boun_target_test.txt")) {
      loadBounFile(baseNormDir.resolve(rel));
    }

    // 4. Mukayese TRSpell10
    loadPlainLines(baseNormDir.resolve("benchmarks/mukayese_trspell10/test.csv"), false);

    // 5. Disambiguation 10K Held-Out Manifest
    loadDisambiguationManifest(
        turkishDataRoot.resolve("distilled/PERMANENT_HELDOUT_TEST_SET_MANIFEST.json"));

    Path srcDir = baseNormDir.resolve("source_corpora");

    // 6. OffensEval-2020 official testset-v1
    loadOffensEvalTest(srcDir.resolve(
        "tweets/offenseval2020_tr/offenseval2020-turkish/"
            + "offenseval-tr-testset-v1/offenseval-tr-testset-v1.tsv"));

    // 7. Winvoker test.csv (paragraphs + extracted sentences)
    loadWinvokerTest(srcDir.resolve("ecommerce_reviews/winvoker_reviews/test.csv"));

    // 8. Beyazperde valid.json + test.json (paragraphs + extracted sentences)
    loadBeyazperdeHeldOut(srcDir.resolve("movies_and_complaints/beyazperde/valid.json"));
    loadBeyazperdeHeldOut(srcDir.resolve("movies_and_complaints/beyazperde/test.json"));

    // 9. Controlled 3x1,000 synthetic benchmarks (asciified, distance-1 typo, clean)
    if (includeSynthetic1k) {
      for (String rel :
          List.of(
              "benchmarks/synthetic_1k/synth_asciified_1k.tsv",
              "benchmarks/synthetic_1k/synth_typo_1k.tsv",
              "benchmarks/synthetic_1k/synth_clean_1k.tsv")) {
        loadSyntheticTsv(baseNormDir.resolve(rel));
      }
    }

    forbiddenSkeletons.remove("");
    System.out.printf(
        Locale.ROOT,
        "[VAULT] Loaded %,d forbidden sentence skeletons (nasty_5k=%,d) and %,d 5-gram hashes.%n",
        forbiddenSkeletons.size(),
        nastyAdded,
        benchmark5GramHashes.size());
  }

  public boolean isForbidden(String segmentText, String dedupSkeleton) {
    if (dedupSkeleton == null || dedupSkeleton.isEmpty()) {
      return true;
    }
    if (forbiddenSkeletons.contains(dedupSkeleton)) {
      return true;
    }
    // Check if candidate shares >= 2 distinct 5-word shingles with core normalization benchmarks.
    String spaced = normalizeSpacedWords(segmentText);
    String[] words = spaced.isEmpty() ? new String[0] : spaced.split(" ");
    if (words.length >= 6) {
      int hits = 0;
      for (int i = 0; i + 5 <= words.length; i++) {
        long h = hash5Words(words, i);
        if (benchmark5GramHashes.contains(h) && ++hits >= 2) {
          return true;
        }
      }
    }
    return false;
  }

  private void registerForbiddenText(String text, boolean indexShingles, boolean splitParagraph) {
    if (text == null || text.isBlank()) {
      return;
    }
    String skel = normalizeSkeleton(text);
    if (!skel.isEmpty()) {
      forbiddenSkeletons.add(skel);
      if (indexShingles) {
        add5GramShingles(normalizeSpacedWords(text));
      }
    }
    if (splitParagraph && text.length() > 40) {
      for (String sent : TurkishSentenceExtractor.DEFAULT.fromParagraph(text)) {
        String sSkel = normalizeSkeleton(sent);
        if (sSkel.length() >= 10) {
          forbiddenSkeletons.add(sSkel);
          if (indexShingles) {
            add5GramShingles(normalizeSpacedWords(sent));
          }
        }
      }
    }
  }

  private void add5GramShingles(String skel) {
    String[] words = skel.split(" ");
    for (int i = 0; i + 5 <= words.length; i++) {
      benchmark5GramHashes.add(hash5Words(words, i));
    }
  }

  private static long hash5Words(String[] words, int offset) {
    long h = 0xcbf29ce484222325L;
    for (int i = 0; i < 5; i++) {
      String w = words[offset + i];
      for (int c = 0; c < w.length(); c++) {
        h ^= w.charAt(c);
        h *= 0x100000001b3L;
      }
      h ^= 0x20L;
      h *= 0x100000001b3L;
    }
    return h;
  }

  private void loadPlainLines(Path path, boolean indexShingles) throws IOException {
    if (!Files.exists(path)) {
      return;
    }
    try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String line;
      while ((line = br.readLine()) != null) {
        registerForbiddenText(line, indexShingles, false);
      }
    }
  }

  private void loadSyntheticTsv(Path path) throws IOException {
    if (!Files.exists(path)) {
      return;
    }
    try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String line = br.readLine(); // skip header
      if (line == null) {
        return;
      }
      while ((line = br.readLine()) != null) {
        String[] cols = line.split("\t", -1);
        if (cols.length >= 3) {
          registerForbiddenText(cols[1], true, false);
          registerForbiddenText(cols[2], true, false);
        }
      }
    }
  }

  private void loadMultiLexNorm(Path path) throws IOException {
    if (!Files.exists(path)) {
      return;
    }
    List<String> rawToks = new ArrayList<>();
    List<String> normToks = new ArrayList<>();
    try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String line;
      while ((line = br.readLine()) != null) {
        if (line.isBlank()) {
          if (!rawToks.isEmpty()) {
            registerForbiddenText(String.join(" ", rawToks), true, false);
            registerForbiddenText(String.join(" ", normToks), true, false);
            rawToks.clear();
            normToks.clear();
          }
        } else {
          String[] parts = line.split("\t");
          if (parts.length >= 1 && !parts[0].isBlank()) {
            rawToks.add(parts[0].trim());
          }
          if (parts.length >= 2 && !parts[1].isBlank()) {
            normToks.add(parts[1].trim());
          }
        }
      }
      if (!rawToks.isEmpty()) {
        registerForbiddenText(String.join(" ", rawToks), true, false);
        registerForbiddenText(String.join(" ", normToks), true, false);
      }
    }
  }

  private void loadBounFile(Path path) throws IOException {
    if (!Files.exists(path)) {
      return;
    }
    try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String line;
      while ((line = br.readLine()) != null) {
        if (line.startsWith("S ")) {
          registerForbiddenText(line.substring(2), true, false);
        } else if (!line.startsWith("A ") && !line.isBlank()) {
          registerForbiddenText(line, true, false);
        }
      }
    }
  }

  private void loadDisambiguationManifest(Path path) throws IOException {
    if (!Files.exists(path)) {
      return;
    }
    JsonElement root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
    if (!root.isJsonObject()) {
      return;
    }
    JsonObject sets = root.getAsJsonObject().getAsJsonObject("sets");
    if (sets == null) {
      return;
    }
    for (Map.Entry<String, JsonElement> entry : sets.entrySet()) {
      JsonObject info = entry.getValue().getAsJsonObject();
      if (info.has("sentence_texts") && info.get("sentence_texts").isJsonArray()) {
        for (JsonElement el : info.getAsJsonArray("sentence_texts")) {
          registerForbiddenText(el.getAsString(), false, false);
        }
      }
    }
  }

  private void loadOffensEvalTest(Path path) throws IOException {
    if (!Files.exists(path)) {
      return;
    }
    try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String line = br.readLine(); // skip header id\ttweet
      while ((line = br.readLine()) != null) {
        int tab = line.indexOf('\t');
        if (tab > 0 && tab + 1 < line.length()) {
          registerForbiddenText(line.substring(tab + 1), true, true);
        }
      }
    }
  }

  private void loadWinvokerTest(Path path) throws IOException {
    if (!Files.exists(path)) {
      return;
    }
    RawCorpusIngestor.streamCsvRows(path, ',', row -> {
      if (!row.isEmpty() && !"text".equalsIgnoreCase(row.get(0))) {
        registerForbiddenText(row.get(0), false, true);
      }
      return true;
    });
  }

  private void loadBeyazperdeHeldOut(Path path) throws IOException {
    if (!Files.exists(path)) {
      return;
    }
    try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String line;
      while ((line = br.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        try {
          JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
          if (obj.has("text") && !obj.get("text").isJsonNull()) {
            registerForbiddenText(obj.get("text").getAsString(), false, true);
          }
        } catch (Exception ignored) {
          // ignore malformed line
        }
      }
    }
  }
}
