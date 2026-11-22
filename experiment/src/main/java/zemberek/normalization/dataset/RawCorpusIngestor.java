package zemberek.normalization.dataset;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import zemberek.normalization.dataset.DatasetModels.DomainBucket;
import zemberek.normalization.dataset.DatasetModels.RawDocument;
import zemberek.normalization.dataset.DatasetModels.TaskLabels;

/**
 * Split-safe raw corpus ingestion reading exclusively from the unified
 * {@code ${ZEMBEREK_DATA_DIR:-~/data/turkish}/normalization/source_corpora/} directory while preserving
 * downstream sentiment, star-rating, offense, intent, and topic labels and avoiding sequential
 * prefix bias via deterministic stride/shuffled sampling and per-product/movie caps.
 */
public final class RawCorpusIngestor {

  private static final Pattern FAST_PROFANITY_SCAN_RE = Pattern.compile(
      "(?iu)(?:\\b(?:amk|aq|mk|amq|mq|oç|sg|sktr|sie|bok|piç|pic|puşt|pust|ibne|göt|got"
          + "|sik|yarr?a|orosp|orosb|rospu|pezeven|kahpe|kahbe|kaltak|kaşar|kasar|dalyarak"
          + "|gavat|yavşak|yavsak|şerefsiz|serefsiz|namussuz|haysiyetsiz|aptal|salak"
          + "|gerizekal|geri zekal|beyinsiz|embesil|angut|kevaşe|sokim|sokam|sokayım|sokayim"
          + "|skerm|amına|amina|amcık|amcik)|[a-zçğıöşü]\\*+[a-zçğıöşü]?)");

  private final Path sourceCorporaDir;

  public RawCorpusIngestor(Path sourceCorporaDir) {
    this.sourceCorporaDir = sourceCorporaDir;
  }

  /**
   * Streams split-safe documents across all 7 domain buckets, plus a fast regex-gated multi-domain
   * profanity supplement pass so heavy-noise profanity cells have deep coverage without slowing
   * down general ingestion.
   *
   * @param scaleMultiplier ~0.20 for fast sample preview, ~1.8 for full 110K + 12K profanity run.
   */
  public void ingestAll(double scaleMultiplier, Consumer<RawDocument> sink) throws IOException {
    ingestAll(scaleMultiplier, false, sink);
  }

  public void ingestAll(
      double scaleMultiplier, boolean includeCleanFormal, Consumer<RawDocument> sink)
      throws IOException {
    ingestEcommerceAndWinvoker(scaleMultiplier, sink);
    ingestTweets(scaleMultiplier, sink);
    ingestModernForums(scaleMultiplier, sink);
    ingestQaAndLifestyleForums(scaleMultiplier, sink);
    ingestEksiSozluk(scaleMultiplier, sink);
    ingestMoviesAndComplaints(scaleMultiplier, sink);
    ingestCivicAndQueries(scaleMultiplier, sink);
    if (includeCleanFormal) {
      ingestCleanFormalSupplement(scaleMultiplier, sink);
    }
    ingestTargetedProfanitySupplement(scaleMultiplier, sink);
  }

  private void ingestEcommerceAndWinvoker(double scale, Consumer<RawDocument> sink)
      throws IOException {
    // 1. Trendyol 2025 CSV: stride across entire file + <=2 per product_url + (category, star) cap
    Path trendyolCsv = sourceCorporaDir.resolve(
        "ecommerce_reviews/trendyol_2025/trendyol_reviews_20251212_000835.csv");
    if (Files.exists(trendyolCsv)) {
      int capLowStarPerCat = scaleCount(3_200, scale);
      int capHighStarPerCat = scaleCount(2_400, scale);
      int rowStride = scale < 0.5 ? 5 : 1;
      Map<String, Integer> catStarCounts = new HashMap<>();
      Map<String, Integer> prodCounts = new HashMap<>(65_536);
      final int[] rowIdx = {0};
      streamCsvRows(trendyolCsv, ',', row -> {
        int idx = rowIdx[0]++;
        if (idx == 0) {
          return true; // header
        }
        if (row.size() < 6) {
          return true;
        }
        String category = row.get(0).trim();
        String prodUrl = row.get(1).trim();
        String pointStr = row.get(4).trim();
        String comment = row.get(5).trim();
        if (comment.isEmpty()) {
          return true;
        }
        Double stars = parseDoubleSafe(pointStr);
        boolean lowOrMid = stars != null && stars <= 3.0;
        // Always keep scarce 1-3 star reviews; stride-sample abundant 4-5 star reviews
        if (!lowOrMid && (idx % rowStride != 0)) {
          return true;
        }
        if (!prodUrl.isEmpty()) {
          int pCur = prodCounts.getOrDefault(prodUrl, 0);
          if (pCur >= 2) {
            return true;
          }
          prodCounts.put(prodUrl, pCur + 1);
        }
        String bucketKey = category + "|" + (lowOrMid ? "1_3" : "4_5");
        int cur = catStarCounts.getOrDefault(bucketKey, 0);
        int limit = lowOrMid ? capLowStarPerCat : capHighStarPerCat;
        if (cur >= limit) {
          return true;
        }
        catStarCounts.put(bucketKey, cur + 1);
        String sentiment = stars == null ? null
            : (stars <= 2.0 ? "Negative" : (stars == 3.0 ? "Neutral" : "Positive"));
        sink.accept(new RawDocument(
            DomainBucket.ECOMMERCE_REVIEWS,
            "trendyol_2025",
            comment,
            new TaskLabels(sentiment, stars, null, emptyToNull(category)),
            false));
        return true;
      });
    }

    // 2. Winvoker train.csv ONLY (exclude 'wiki' and 'random'; stride across full 440K file)
    Path winvokerTrain =
        sourceCorporaDir.resolve("ecommerce_reviews/winvoker_reviews/train.csv");
    if (Files.exists(winvokerTrain)) {
      int maxPerSubDataset = scaleCount(22_000, scale);
      int urunStride = scale < 0.5 ? 10 : 2;
      int otherStride = scale < 0.5 ? 4 : 1;
      Map<String, Integer> subSeen = new HashMap<>();
      Map<String, Integer> subKept = new HashMap<>();
      final boolean[] isHeader = {true};
      streamCsvRows(winvokerTrain, ',', row -> {
        if (isHeader[0]) {
          isHeader[0] = false;
          return true;
        }
        if (row.size() < 3) {
          return true;
        }
        String text = row.get(0).trim();
        String label = row.get(1).trim();
        String subDataset = row.get(2).trim();
        if (text.isEmpty()
            || "wiki".equalsIgnoreCase(subDataset)
            || "random".equalsIgnoreCase(subDataset)) {
          return true;
        }
        int seen = subSeen.merge(subDataset, 1, Integer::sum);
        int stride = "urun_yorumlari".equals(subDataset) ? urunStride : otherStride;
        if (seen % stride != 0) {
          return true;
        }
        int cur = subKept.getOrDefault(subDataset, 0);
        if (cur >= maxPerSubDataset) {
          return true;
        }
        subKept.put(subDataset, cur + 1);
        DomainBucket domain = switch (subDataset) {
          case "tweet-pn" -> DomainBucket.TWEETS_SOCIAL;
          case "HUMIR" -> DomainBucket.MOVIES_COMPLAINTS;
          default -> DomainBucket.ECOMMERCE_REVIEWS;
        };
        sink.accept(new RawDocument(
            domain,
            "winvoker_train_" + subDataset,
            text,
            new TaskLabels(normalizeWinvokerLabel(label), null, null, subDataset),
            false));
        return true;
      });
    }
  }

  private void ingestTweets(double scale, Consumer<RawDocument> sink) throws IOException {
    // 1. OffensEval 2020 Turkish training-v1.tsv ONLY (31,277 tweets; shuffled before cap!)
    Path offenseTrain = sourceCorporaDir.resolve(
        "tweets/offenseval2020_tr/offenseval2020-turkish/"
            + "offenseval-tr-training-v1/offenseval-tr-training-v1.tsv");
    if (Files.exists(offenseTrain)) {
      List<RawDocument> notDocs = new ArrayList<>(26_000);
      List<RawDocument> offDocs = new ArrayList<>(6_500);
      try (BufferedReader br = Files.newBufferedReader(offenseTrain, StandardCharsets.UTF_8)) {
        br.readLine(); // skip header: id\ttweet\tsubtask_a
        String line;
        while ((line = br.readLine()) != null) {
          String[] parts = line.split("\t", 3);
          if (parts.length < 3) {
            continue;
          }
          String tweet = parts[1].trim();
          String offLabel = parts[2].trim().toUpperCase(Locale.ROOT);
          if (tweet.isEmpty()) {
            continue;
          }
          RawDocument doc = new RawDocument(
              DomainBucket.TWEETS_SOCIAL,
              "offenseval2020_train",
              tweet,
              new TaskLabels(null, null, offLabel, null),
              false);
          if ("OFF".equals(offLabel)) {
            offDocs.add(doc);
          } else {
            notDocs.add(doc);
          }
        }
      }
      Collections.shuffle(notDocs, new Random(4201L));
      Collections.shuffle(offDocs, new Random(4202L));
      int capNot = Math.min(notDocs.size(), scaleCount(16_000, scale));
      int capOff = Math.min(offDocs.size(), scaleCount(6_500, scale)); // keeps all ~6K OFF in full!
      for (int i = 0; i < capNot; i++) {
        sink.accept(notDocs.get(i));
      }
      for (int i = 0; i < capOff; i++) {
        sink.accept(offDocs.get(i));
      }
    }

    // 2. Yankihue tweets data.csv (11,108 tweets; shuffled across Pozitif & Negatif!)
    Path yankihueCsv = sourceCorporaDir.resolve("tweets/yankihue_tweets_tr/data.csv");
    if (Files.exists(yankihueCsv)) {
      List<RawDocument> allTweets = new ArrayList<>(11_500);
      final boolean[] isHeader = {true};
      streamCsvRows(yankihueCsv, ',', row -> {
        if (isHeader[0]) {
          isHeader[0] = false;
          return true;
        }
        if (row.size() < 2) {
          return true;
        }
        String text = row.get(0).replace('\u0092', '\'').replace('\u0091', '\'').trim();
        String tip = row.get(1).trim();
        if (!text.isEmpty()) {
          String sent = "Pozitif".equalsIgnoreCase(tip) ? "Positive"
              : ("Negatif".equalsIgnoreCase(tip) ? "Negative" : null);
          allTweets.add(new RawDocument(
              DomainBucket.TWEETS_SOCIAL,
              "yankihue_tweets",
              text,
              new TaskLabels(sent, null, null, null),
              true));
        }
        return true;
      });
      Collections.shuffle(allTweets, new Random(4203L));
      int cap = Math.min(allTweets.size(), scaleCount(11_200, scale));
      for (int i = 0; i < cap; i++) {
        sink.accept(allTweets.get(i));
      }
    }
  }

  private void ingestModernForums(double scale, Consumer<RawDocument> sink) throws IOException {
    Path dir = sourceCorporaDir.resolve("forums_modern_2020s");
    if (Files.isDirectory(dir)) {
      int maxPostsPerFile = scaleCount(22_000, scale);
      try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jsonl")) {
        List<Path> files = new ArrayList<>();
        ds.forEach(files::add);
        Collections.sort(files);
        for (Path p : files) {
          String forumName = p.getFileName().toString().replace(".jsonl", "");
          List<String> sampledLines = sampleLinesEvenly(p, scaleCount(10_000, scale));
          int emitted = 0;
          for (String line : sampledLines) {
            if (emitted >= maxPostsPerFile) {
              break;
            }
            if (line.isBlank()) {
              continue;
            }
            try {
              JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
              String url = obj.has("url") && !obj.get("url").isJsonNull()
                  ? extractThreadSlug(obj.get("url").getAsString()) : null;
              if (obj.has("texts") && obj.get("texts").isJsonArray()) {
                JsonArray arr = obj.get("texts").getAsJsonArray();
                for (JsonElement el : arr) {
                  if (!el.isJsonNull()) {
                    sink.accept(new RawDocument(
                        DomainBucket.FORUMS_MODERN_2020S,
                        "forum_" + forumName,
                        el.getAsString(),
                        new TaskLabels(null, null, null, url),
                        false));
                    if (++emitted >= maxPostsPerFile) {
                      break;
                    }
                  }
                }
              }
            } catch (Exception ignored) {
              // skip malformed line
            }
          }
        }
      }

      // shiftdelete tech forum (consolidated inside forums_modern_2020s/)
      ingestSampledPlainForum(
          dir.resolve("forum.shiftdelete.net.txt"),
          DomainBucket.FORUMS_MODERN_2020S,
          "forum_shiftdelete",
          scaleCount(26_000, scale),
          sink);
    }
  }

  private void ingestQaAndLifestyleForums(double scale, Consumer<RawDocument> sink)
      throws IOException {
    Path dir = sourceCorporaDir.resolve("forums_qa_lifestyle");
    ingestSampledPlainForum(
        dir.resolve("kadinlar-klubu.txt"),
        DomainBucket.QA_LIFESTYLE_FORUMS,
        "kadinlar_klubu",
        scaleCount(38_000, scale),
        sink);
    ingestSampledPlainForum(
        dir.resolve("www.kizlarsoruyor.com.txt"),
        DomainBucket.QA_LIFESTYLE_FORUMS,
        "kizlarsoruyor_raw",
        scaleCount(45_000, scale),
        sink);
    ingestSampledPlainForum(
        dir.resolve("www.nurturia.com.tr.txt"),
        DomainBucket.QA_LIFESTYLE_FORUMS,
        "nurturia",
        scaleCount(18_000, scale),
        sink);
  }

  private void ingestEksiSozluk(double scale, Consumer<RawDocument> sink) throws IOException {
    // 1. 2025-2026 tech & finance JSONLs (shuffled to avoid topic ordering bias)
    Path jsonlDir = sourceCorporaDir.resolve("eksisozluk/eksisozluk_2025_2026_jsonl");
    if (Files.isDirectory(jsonlDir)) {
      List<RawDocument> docs2025 = new ArrayList<>(35_000);
      try (DirectoryStream<Path> ds = Files.newDirectoryStream(jsonlDir, "*.jsonl")) {
        List<Path> files = new ArrayList<>();
        ds.forEach(files::add);
        Collections.sort(files);
        for (Path p : files) {
          String fname = p.getFileName().toString();
          String domainPrefix =
              fname.contains("__") ? fname.substring(0, fname.indexOf("__")) : "general";
          try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
              if (line.isBlank()) {
                continue;
              }
              try {
                JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
                String text = obj.has("text") && !obj.get("text").isJsonNull()
                    ? obj.get("text").getAsString() : "";
                String topic = obj.has("topic") && !obj.get("topic").isJsonNull()
                    ? domainPrefix + ":" + obj.get("topic").getAsString() : domainPrefix;
                if (!text.isBlank()) {
                  docs2025.add(new RawDocument(
                      DomainBucket.EKSISOZLUK,
                      "eksisozluk_2025_" + domainPrefix,
                      text,
                      new TaskLabels(null, null, null, topic),
                      false));
                }
              } catch (Exception ignored) {
                // skip
              }
            }
          }
        }
      }
      Collections.shuffle(docs2025, new Random(4204L));
      int cap = Math.min(docs2025.size(), scaleCount(25_000, scale));
      for (int i = 0; i < cap; i++) {
        sink.accept(docs2025.get(i));
      }
    }

    // 2. 2024 Apache CSV (150K entries; stride across full file)
    Path eksiCsv = sourceCorporaDir.resolve("eksisozluk/eksisozluk_2024_apache/eksisozluk_entries.csv");
    if (Files.exists(eksiCsv)) {
      int maxEntries = scaleCount(35_000, scale);
      int stride = scale < 0.5 ? 12 : 2;
      final int[] rowIdx = {0};
      final int[] kept = {0};
      streamCsvRows(eksiCsv, ',', row -> {
        int idx = rowIdx[0]++;
        if (idx == 0) {
          return true; // header
        }
        if (idx % stride != 0) {
          return true;
        }
        if (row.size() >= 3 && !row.get(2).isBlank()) {
          sink.accept(new RawDocument(
              DomainBucket.EKSISOZLUK,
              "eksisozluk_2024",
              row.get(2).trim(),
              new TaskLabels(null, null, null, emptyToNull(row.get(1).trim())),
              false));
          if (++kept[0] >= maxEntries) {
            return false;
          }
        }
        return true;
      });
    }
  }

  private void ingestMoviesAndComplaints(double scale, Consumer<RawDocument> sink)
      throws IOException {
    Path dir = sourceCorporaDir.resolve("movies_and_complaints");

    // 1. Beyazperde train.json ONLY (shuffled across all movies + <=4 per movie; valid/test locked!)
    Path beyazTrain = dir.resolve("beyazperde/train.json");
    if (Files.exists(beyazTrain)) {
      List<RawDocument> allReviews = new ArrayList<>(30_000);
      try (BufferedReader br = Files.newBufferedReader(beyazTrain, StandardCharsets.UTF_8)) {
        String line;
        while ((line = br.readLine()) != null) {
          if (line.isBlank()) {
            continue;
          }
          try {
            JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
            String text = obj.has("text") && !obj.get("text").isJsonNull()
                ? obj.get("text").getAsString() : "";
            Double star = obj.has("star") && !obj.get("star").isJsonNull()
                ? obj.get("star").getAsDouble() : null;
            String movie = obj.has("movie") && !obj.get("movie").isJsonNull()
                ? obj.get("movie").getAsString() : null;
            if (!text.isBlank()) {
              String sent = star == null ? null
                  : (star <= 2.0 ? "Negative" : (star >= 3.5 ? "Positive" : "Neutral"));
              allReviews.add(new RawDocument(
                  DomainBucket.MOVIES_COMPLAINTS,
                  "beyazperde_train",
                  text,
                  new TaskLabels(sent, star, null, movie),
                  false));
            }
          } catch (Exception ignored) {
            // skip
          }
        }
      }
      Collections.shuffle(allReviews, new Random(4205L));
      int cap = scaleCount(18_000, scale);
      Map<String, Integer> movieCounts = new HashMap<>();
      int emitted = 0;
      for (RawDocument d : allReviews) {
        if (emitted >= cap) {
          break;
        }
        String mv = d.labels().categoryOrTopic();
        if (mv != null) {
          int mc = movieCounts.getOrDefault(mv, 0);
          if (mc >= 6) {
            continue;
          }
          movieCounts.put(mv, mc + 1);
        }
        sink.accept(d);
        emitted++;
      }
    }

    // 2. Sikayetler CSV (shuffled)
    Path sikayetCsv = dir.resolve("sikayetler/sikayetler.csv");
    if (Files.exists(sikayetCsv)) {
      List<RawDocument> allDocs = new ArrayList<>(4_000);
      final boolean[] isHeader = {true};
      streamCsvRows(sikayetCsv, ',', row -> {
        if (isHeader[0]) {
          isHeader[0] = false;
          return true;
        }
        if (row.size() >= 2 && !row.get(0).isBlank()) {
          allDocs.add(new RawDocument(
              DomainBucket.MOVIES_COMPLAINTS,
              "sikayetler",
              row.get(0).trim(),
              new TaskLabels("Negative", null, null, emptyToNull(row.get(1).trim())),
              false));
        }
        return true;
      });
      Collections.shuffle(allDocs, new Random(4206L));
      int cap = Math.min(allDocs.size(), scaleCount(15_000, scale));
      for (int i = 0; i < cap; i++) {
        sink.accept(allDocs.get(i));
      }
    }

    // 3. sikayetim.com & tvyorumlari.com
    ingestSampledPlainForum(
        dir.resolve("www.sikayetim.com.txt"),
        DomainBucket.MOVIES_COMPLAINTS,
        "sikayetim_com",
        scaleCount(16_000, scale),
        sink);
    ingestSampledPlainForum(
        dir.resolve("www.tvyorumlari.com.txt"),
        DomainBucket.MOVIES_COMPLAINTS,
        "tvyorumlari_com",
        scaleCount(16_000, scale),
        sink);
  }

  private void ingestCivicAndQueries(double scale, Consumer<RawDocument> sink) throws IOException {
    Path civicDir = sourceCorporaDir.resolve("civic_forums");
    Path queriesDir = sourceCorporaDir.resolve("queries");

    // 1. 1.4 GB wowturkey.com + 205 MB forum.memurlar.net
    ingestSampledPlainForum(
        civicDir.resolve("wowturkey.com.txt"),
        DomainBucket.CIVIC_AND_QUERIES,
        "wowturkey",
        scaleCount(28_000, scale),
        sink);
    ingestSampledPlainForum(
        civicDir.resolve("forum.memurlar.net.txt"),
        DomainBucket.CIVIC_AND_QUERIES,
        "memurlar_forum",
        scaleCount(25_000, scale),
        sink);

    // 2. E-commerce intent-labeled queries (shuffled)
    Path ecomIntent = queriesDir.resolve("raw/ecommerce_intent_tr.csv");
    if (Files.exists(ecomIntent)) {
      List<RawDocument> qDocs = new ArrayList<>();
      final boolean[] isHeader = {true};
      streamCsvRows(ecomIntent, ',', row -> {
        if (isHeader[0]) {
          isHeader[0] = false;
          return true;
        }
        if (row.size() >= 2 && !row.get(0).isBlank()) {
          qDocs.add(new RawDocument(
              DomainBucket.CIVIC_AND_QUERIES,
              "query_ecommerce_intent",
              row.get(0).trim(),
              new TaskLabels(null, null, null, "intent:" + row.get(1).trim()),
              false));
        }
        return true;
      });
      Collections.shuffle(qDocs, new Random(4207L));
      int cap = Math.min(qDocs.size(), scaleCount(12_000, scale));
      for (int i = 0; i < cap; i++) {
        sink.accept(qDocs.get(i));
      }
    }

    // 3. Bing Turkey search queries (shuffled)
    Path bingTsv = queriesDir.resolve("raw/bing_turkey_2020_2021.tsv");
    if (Files.exists(bingTsv)) {
      List<RawDocument> bDocs = new ArrayList<>();
      try (BufferedReader br = Files.newBufferedReader(bingTsv, StandardCharsets.UTF_8)) {
        br.readLine(); // skip header
        String line;
        while ((line = br.readLine()) != null) {
          String[] parts = line.split("\t");
          if (parts.length >= 2 && !parts[1].isBlank()) {
            bDocs.add(new RawDocument(
                DomainBucket.CIVIC_AND_QUERIES,
                "query_bing_tr",
                parts[1].trim(),
                TaskLabels.EMPTY,
                false));
          }
        }
      }
      Collections.shuffle(bDocs, new Random(4208L));
      int cap = Math.min(bDocs.size(), scaleCount(15_000, scale));
      for (int i = 0; i < cap; i++) {
        sink.accept(bDocs.get(i));
      }
    }

    // 4. MKQA Google Turkish clean questions (queries.tr)
    Path mkqaGz = queriesDir.resolve("raw/mkqa.jsonl.gz");
    if (Files.exists(mkqaGz)) {
      List<RawDocument> mDocs = new ArrayList<>(10_000);
      try (BufferedReader br = new BufferedReader(
          new InputStreamReader(new GZIPInputStream(Files.newInputStream(mkqaGz)),
              StandardCharsets.UTF_8))) {
        String line;
        while ((line = br.readLine()) != null) {
          try {
            JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
            JsonObject queries = obj.getAsJsonObject("queries");
            if (queries != null && queries.has("tr") && !queries.get("tr").isJsonNull()) {
              mDocs.add(new RawDocument(
                  DomainBucket.CIVIC_AND_QUERIES,
                  "query_mkqa_tr",
                  queries.get("tr").getAsString(),
                  TaskLabels.EMPTY,
                  false));
            }
          } catch (Exception ignored) {
            // skip
          }
        }
      }
      Collections.shuffle(mDocs, new Random(4209L));
      int cap = Math.min(mDocs.size(), scaleCount(5_000, scale));
      for (int i = 0; i < cap; i++) {
        sink.accept(mDocs.get(i));
      }
    }

    // 5. Forum search thread slugs & Eksi topic titles (rich in 2-5 word ASCII queries!)
    ingestSampledPlainForum(
        queriesDir.resolve("plain/forum_search_thread_slugs.txt"),
        DomainBucket.CIVIC_AND_QUERIES,
        "query_forum_slugs",
        scaleCount(18_000, scale),
        sink);
    ingestSampledPlainForum(
        queriesDir.resolve("plain/eksisozluk_topic_titles.txt"),
        DomainBucket.CIVIC_AND_QUERIES,
        "query_eksi_titles",
        scaleCount(10_000, scale),
        sink);
  }

  /**
   * Samples clean edited news, economics, science, and technical prose from
   * {@code source_corpora/clean_formal/} for medium/low-noise profiles so {@code T0_clean_control}
   * anchors cover formal register, multi-word entities, acronyms, and apostrophes alongside UGC.
   */
  private void ingestCleanFormalSupplement(double scale, Consumer<RawDocument> sink)
      throws IOException {
    Path cleanDir = sourceCorporaDir.resolve("clean_formal");
    if (!Files.isDirectory(cleanDir)) {
      return;
    }
    ingestSampledPlainForum(
        cleanDir.resolve("www.cnnturk.com.txt"),
        DomainBucket.CIVIC_AND_QUERIES,
        "clean_news_cnnturk",
        scaleCount(12_000, scale),
        sink);
    ingestSampledPlainForum(
        cleanDir.resolve("www.dunya.com.txt"),
        DomainBucket.CIVIC_AND_QUERIES,
        "clean_news_dunya",
        scaleCount(12_000, scale),
        sink);
    ingestSampledPlainForum(
        cleanDir.resolve("www.haberturk.com.txt"),
        DomainBucket.CIVIC_AND_QUERIES,
        "clean_news_haberturk",
        scaleCount(10_000, scale),
        sink);
    ingestSampledPlainForum(
        cleanDir.resolve("www.bilim.org.txt"),
        DomainBucket.CIVIC_AND_QUERIES,
        "clean_sci_bilim",
        scaleCount(5_000, scale),
        sink);
    ingestSampledPlainForum(
        cleanDir.resolve("www.defenceturk.com.txt"),
        DomainBucket.CIVIC_AND_QUERIES,
        "clean_tech_defenceturk",
        scaleCount(6_000, scale),
        sink);
  }

  private void ingestSampledPlainForum(
      Path path,
      DomainBucket domain,
      String sourceId,
      int targetLines,
      Consumer<RawDocument> sink) throws IOException {
    for (String line : sampleLinesEvenly(path, targetLines)) {
      if (!line.isBlank()) {
        sink.accept(new RawDocument(domain, sourceId, line, TaskLabels.EMPTY, false));
      }
    }
  }

  /**
   * Fast regex-gated multi-domain supplement pass over unsampled rows of large forums, reviews, and
   * Ekşi Sözlük so medium/heavy/ASCII noisy profanity cells fill cleanly across all domains.
   */
  private void ingestTargetedProfanitySupplement(double scale, Consumer<RawDocument> sink)
      throws IOException {
    // 1. Remaining odd-indexed rows of Ekşi Sözlük 2024 CSV matching profanity cues
    Path eksiCsv =
        sourceCorporaDir.resolve("eksisozluk/eksisozluk_2024_apache/eksisozluk_entries.csv");
    if (Files.exists(eksiCsv)) {
      int cap = scaleCount(18_000, scale);
      final int[] rowIdx = {0};
      final int[] kept = {0};
      streamCsvRows(eksiCsv, ',', row -> {
        int idx = rowIdx[0]++;
        if (idx == 0 || (idx % 2 == 0)) {
          return true; // already scanned in main pass
        }
        if (row.size() >= 3) {
          String text = row.get(2).trim();
          if (!text.isEmpty() && FAST_PROFANITY_SCAN_RE.matcher(text).find()) {
            sink.accept(new RawDocument(
                DomainBucket.EKSISOZLUK,
                "eksisozluk_2024",
                text,
                new TaskLabels(null, null, null, emptyToNull(row.get(1).trim())),
                false));
            if (++kept[0] >= cap) {
              return false;
            }
          }
        }
        return true;
      });
    }

    // 2. Unsampled rows of Winvoker train.csv matching profanity cues
    Path winvokerTrain =
        sourceCorporaDir.resolve("ecommerce_reviews/winvoker_reviews/train.csv");
    if (Files.exists(winvokerTrain)) {
      int cap = scaleCount(15_000, scale);
      final boolean[] isHeader = {true};
      final int[] kept = {0};
      streamCsvRows(winvokerTrain, ',', row -> {
        if (isHeader[0]) {
          isHeader[0] = false;
          return true;
        }
        if (row.size() < 3) {
          return true;
        }
        String text = row.get(0).trim();
        String label = row.get(1).trim();
        String subDataset = row.get(2).trim();
        if (text.isEmpty()
            || "wiki".equalsIgnoreCase(subDataset)
            || "random".equalsIgnoreCase(subDataset)) {
          return true;
        }
        if (FAST_PROFANITY_SCAN_RE.matcher(text).find()) {
          DomainBucket domain = switch (subDataset) {
            case "tweet-pn" -> DomainBucket.TWEETS_SOCIAL;
            case "HUMIR" -> DomainBucket.MOVIES_COMPLAINTS;
            default -> DomainBucket.ECOMMERCE_REVIEWS;
          };
          sink.accept(new RawDocument(
              domain,
              "winvoker_train_" + subDataset,
              text,
              new TaskLabels(normalizeWinvokerLabel(label), null, null, subDataset),
              false));
          if (++kept[0] >= cap) {
            return false;
          }
        }
        return true;
      });
    }

    // 3. Deeper regex-gated scan on Modern 2020s ForumSohbetleri JSONLs
    Path modernDir = sourceCorporaDir.resolve("forums_modern_2020s");
    if (Files.isDirectory(modernDir)) {
      int maxMatchedPerForum = scaleCount(4_500, scale);
      try (DirectoryStream<Path> ds = Files.newDirectoryStream(modernDir, "*.jsonl")) {
        List<Path> files = new ArrayList<>();
        ds.forEach(files::add);
        Collections.sort(files);
        for (Path p : files) {
          String forumName = p.getFileName().toString().replace(".jsonl", "");
          int matched = 0;
          for (String line : sampleLinesEvenly(p, scaleCount(45_000, scale))) {
            if (matched >= maxMatchedPerForum || line.isBlank()) {
              continue;
            }
            if (!FAST_PROFANITY_SCAN_RE.matcher(line).find()) {
              continue;
            }
            try {
              JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
              String url = obj.has("url") && !obj.get("url").isJsonNull()
                  ? extractThreadSlug(obj.get("url").getAsString()) : null;
              if (obj.has("texts") && obj.get("texts").isJsonArray()) {
                for (JsonElement el : obj.get("texts").getAsJsonArray()) {
                  if (!el.isJsonNull()) {
                    String post = el.getAsString();
                    if (FAST_PROFANITY_SCAN_RE.matcher(post).find()) {
                      sink.accept(new RawDocument(
                          DomainBucket.FORUMS_MODERN_2020S,
                          "forum_" + forumName,
                          post,
                          new TaskLabels(null, null, null, url),
                          false));
                      if (++matched >= maxMatchedPerForum) {
                        break;
                      }
                    }
                  }
                }
              }
            } catch (Exception ignored) {
              // skip
            }
          }
        }
      }
    }

    // 4. Fast byte-offset regex-gated scan across large plain-text forums (reads <25 MB per file)
    record PlainTarget(Path path, DomainBucket domain, String sourceId, int scanLines, int cap) {}
    List<PlainTarget> targets = List.of(
        new PlainTarget(
            sourceCorporaDir.resolve("forums_qa_lifestyle/www.kizlarsoruyor.com.txt"),
            DomainBucket.QA_LIFESTYLE_FORUMS, "kizlarsoruyor_raw",
            scaleCount(350_000, scale), scaleCount(8_000, scale)),
        new PlainTarget(
            sourceCorporaDir.resolve("forums_qa_lifestyle/kadinlar-klubu.txt"),
            DomainBucket.QA_LIFESTYLE_FORUMS, "kadinlar_klubu",
            scaleCount(250_000, scale), scaleCount(5_000, scale)),
        new PlainTarget(
            sourceCorporaDir.resolve("forums_modern_2020s/forum.shiftdelete.net.txt"),
            DomainBucket.FORUMS_MODERN_2020S, "forum_shiftdelete",
            scaleCount(220_000, scale), scaleCount(5_000, scale)),
        new PlainTarget(
            sourceCorporaDir.resolve("civic_forums/wowturkey.com.txt"),
            DomainBucket.CIVIC_AND_QUERIES, "wowturkey",
            scaleCount(220_000, scale), scaleCount(5_000, scale)),
        new PlainTarget(
            sourceCorporaDir.resolve("civic_forums/forum.memurlar.net.txt"),
            DomainBucket.CIVIC_AND_QUERIES, "memurlar_forum",
            scaleCount(180_000, scale), scaleCount(4_000, scale)),
        new PlainTarget(
            sourceCorporaDir.resolve("movies_and_complaints/www.tvyorumlari.com.txt"),
            DomainBucket.MOVIES_COMPLAINTS, "tvyorumlari_com",
            scaleCount(80_000, scale), scaleCount(3_000, scale)));

    for (PlainTarget pt : targets) {
      int matched = 0;
      for (String line : sampleLinesEvenly(pt.path(), pt.scanLines())) {
        if (!line.isBlank() && FAST_PROFANITY_SCAN_RE.matcher(line).find()) {
          sink.accept(new RawDocument(pt.domain(), pt.sourceId(), line, TaskLabels.EMPTY, false));
          if (++matched >= pt.cap()) {
            break;
          }
        }
      }
    }
  }

  /**
   * Evenly-spaced UTF-8 byte-offset chunk sampler for multi-hundred-MB / multi-GB files, shuffled
   * deterministically so early quota fills span 0%..100% of the file rather than only its prefix.
   */
  public static List<String> sampleLinesEvenly(Path file, int targetSampleLines)
      throws IOException {
    List<String> lines = new ArrayList<>();
    if (!Files.exists(file)) {
      System.err.println("[WARN] Missing corpus file: " + file);
      return lines;
    }
    long fileSize = Files.size(file);
    if (targetSampleLines <= 0 || fileSize < 8_000_000L) {
      try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        String line;
        while ((line = br.readLine()) != null) {
          lines.add(line);
        }
      }
      Collections.shuffle(lines, new Random(42L ^ file.getFileName().toString().hashCode()));
      if (targetSampleLines > 0 && lines.size() > targetSampleLines) {
        return new ArrayList<>(lines.subList(0, targetSampleLines));
      }
      return lines;
    }

    int numChunks = 250;
    int linesPerChunk = Math.max(1, (targetSampleLines + numChunks - 1) / numChunks);
    long step = Math.max(1L, fileSize / numChunks);

    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
      CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPLACE)
          .onUnmappableCharacter(CodingErrorAction.REPLACE);
      ByteBuffer alignBuf = ByteBuffer.allocate(32 * 1024);
      byte[] arr = new byte[64 * 1024];

      for (int chunk = 0; chunk < numChunks; chunk++) {
        long offset = chunk * step;
        if (offset >= fileSize) {
          break;
        }
        ch.position(offset);
        if (offset > 0) {
          boolean aligned = false;
          while (!aligned) {
            alignBuf.clear();
            int n = ch.read(alignBuf);
            if (n <= 0) {
              break;
            }
            alignBuf.flip();
            while (alignBuf.hasRemaining()) {
              if (alignBuf.get() == (byte) '\n') {
                ch.position(ch.position() - alignBuf.remaining());
                aligned = true;
                break;
              }
            }
          }
          if (!aligned) {
            continue;
          }
        }

        StringBuilder partial = new StringBuilder();
        int collected = 0;
        while (collected < linesPerChunk) {
          ByteBuffer bb = ByteBuffer.wrap(arr);
          int n = ch.read(bb);
          if (n <= 0) {
            if (!partial.isEmpty()) {
              lines.add(partial.toString());
            }
            break;
          }
          String str;
          try {
            bb.flip();
            str = decoder.decode(bb).toString();
          } catch (CharacterCodingException e) {
            str = new String(arr, 0, n, StandardCharsets.UTF_8);
          }
          int start = 0;
          for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == '\n') {
              partial.append(str, start, i);
              if (!partial.isEmpty() && partial.charAt(partial.length() - 1) == '\r') {
                partial.setLength(partial.length() - 1);
              }
              lines.add(partial.toString());
              partial.setLength(0);
              start = i + 1;
              if (++collected >= linesPerChunk) {
                break;
              }
            }
          }
          if (collected < linesPerChunk && start < str.length()) {
            partial.append(str, start, str.length());
          }
          if (partial.length() > 32_768) {
            partial.setLength(0);
            break;
          }
        }
        decoder.reset();
      }
    }
    Collections.shuffle(lines, new Random(42L ^ file.getFileName().toString().hashCode()));
    return lines;
  }

  /** Minimal streaming RFC-4180 CSV reader supporting multi-line quoted cells & UTF-8 BOM. */
  public static void streamCsvRows(
      Path path, char delimiter, Predicate<List<String>> rowConsumer) throws IOException {
    try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      List<String> row = new ArrayList<>(12);
      StringBuilder cell = new StringBuilder(256);
      boolean inQuotes = false;
      boolean firstChar = true;
      int chInt;
      while ((chInt = br.read()) != -1) {
        char c = (char) chInt;
        if (firstChar) {
          firstChar = false;
          if (c == '\uFEFF') {
            continue;
          }
        }
        if (inQuotes) {
          if (c == '"') {
            br.mark(1);
            int next = br.read();
            if (next == '"') {
              cell.append('"');
            } else {
              inQuotes = false;
              if (next != -1) {
                br.reset();
              }
            }
          } else if (cell.length() < 64_000) {
            cell.append(c);
          }
        } else {
          if (c == '"' && cell.isEmpty()) {
            inQuotes = true;
          } else if (c == delimiter) {
            row.add(cell.toString());
            cell.setLength(0);
          } else if (c == '\r') {
            // ignore CR before LF
          } else if (c == '\n') {
            row.add(cell.toString());
            cell.setLength(0);
            if (!rowConsumer.test(row)) {
              return;
            }
            row = new ArrayList<>(12);
          } else if (cell.length() < 64_000) {
            cell.append(c);
          }
        }
      }
      if (!cell.isEmpty() || !row.isEmpty()) {
        row.add(cell.toString());
        rowConsumer.test(row);
      }
    }
  }

  private static int scaleCount(int baseSampleCount, double scale) {
    return Math.max(300, (int) Math.round(baseSampleCount * scale));
  }

  private static Double parseDoubleSafe(String s) {
    if (s == null || s.isEmpty()) {
      return null;
    }
    try {
      return Double.parseDouble(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String normalizeWinvokerLabel(String l) {
    if ("Notr".equalsIgnoreCase(l)) {
      return "Neutral";
    }
    if ("Positive".equalsIgnoreCase(l) || "Negative".equalsIgnoreCase(l)) {
      return l;
    }
    return emptyToNull(l);
  }

  private static String extractThreadSlug(String url) {
    if (url == null || url.isEmpty()) {
      return null;
    }
    String u = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    int slash = u.lastIndexOf('/');
    return slash >= 0 && slash + 1 < u.length() ? u.substring(slash + 1) : u;
  }

  private static String emptyToNull(String s) {
    return (s == null || s.isEmpty()) ? null : s;
  }
}
