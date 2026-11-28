package zemberek.apps.demo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Function;
import zemberek.dependency.DependencyNode;
import zemberek.dependency.DependencyTree;
import zemberek.dependency.TurkishDependencyParser;
import zemberek.lm.compression.SmoothLm;
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.analysis.SentenceAnalysis;
import zemberek.morphology.analysis.SentenceWordAnalysis;
import zemberek.morphology.analysis.SingleAnalysis;
import zemberek.morphology.analysis.format.ConlluMwtSpan;
import zemberek.normalization.v2.SpanLatticePerceptronNormalizer;
import zemberek.tokenization.Token;
import zemberek.tokenization.TurkishSentenceExtractor;
import zemberek.tokenization.TurkishTokenizer;

/**
 * Local interactive HTTP server ({@code jdk.httpserver} + {@code Gson}) for Zemberek NLP:
 * <ul>
 *   <li><b>Normalization</b>: {@link SpanLatticePerceptronNormalizer} ({@code v2.4}) with
 *       interactive multi-span lattice inspection.
 *   <li><b>Deasciifiers</b>: {@code LatticeDeasciifier} vs. classic {@code Deasciifier}.
 *   <li><b>Morphology &amp; Disambiguation</b>: {@link TurkishMorphology#analyzeAndDisambiguate}.
 *   <li><b>Dependency Parsing</b>: {@link TurkishDependencyParser} Universal Dependencies v2 trees.
 *   <li><b>Tokenization &amp; Sentence Boundary Extraction</b>: {@link TurkishTokenizer} and
 *       {@link TurkishSentenceExtractor}.
 * </ul>
 */
public final class ZemberekDemoServer {

  public static final int DEFAULT_PORT = 8080;
  public static final String DEFAULT_HOST = "127.0.0.1";

  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

  private static final List<Path> DEV_STATIC_DIRS =
      List.of(
          Paths.get("apps/src/main/resources/webdemo"),
          Paths.get("src/main/resources/webdemo"));

  private final TurkishMorphology morphology;
  private final NormalizationInspector normInspector;
  private final DeasciifierInspector deasciifierInspector;
  private final TurkishDependencyParser dependencyParser;
  private final Object engineLock = new Object();

  public ZemberekDemoServer(
      TurkishMorphology morphology, SpanLatticePerceptronNormalizer perceptron) {
    this(morphology, perceptron, tryLoadDefaultDependencyParser(morphology));
  }

  public ZemberekDemoServer(
      TurkishMorphology morphology,
      SpanLatticePerceptronNormalizer perceptron,
      TurkishDependencyParser dependencyParser) {
    this.morphology = morphology;
    this.normInspector = new NormalizationInspector(perceptron);
    this.deasciifierInspector = new DeasciifierInspector(morphology);
    this.dependencyParser = dependencyParser;
  }

  private static TurkishDependencyParser tryLoadDefaultDependencyParser(
      TurkishMorphology morphology) {
    try {
      return TurkishDependencyParser.createWithDefaults(morphology);
    } catch (IOException e) {
      return null;
    }
  }

  public record ServerInfoResponse(
      String version,
      boolean hasZemberekHead,
      List<DeasciifierInspector.DeasciifierEngineMeta> deasciifiers) {}

  public record MorphWordDto(
      String surface, String bestPos, String bestFormatted, List<String> altParses) {}

  public record MorphResponse(double latencyMs, List<MorphWordDto> words) {}

  public record DependencyNodeDto(
      int id,
      String form,
      String lemma,
      String upos,
      String xpos,
      String feats,
      int headId,
      String deprel,
      String misc) {}

  public record DependencyMwtDto(int startId, int endId, String form) {}

  public record DependencySentenceDto(
      String text,
      List<DependencyNodeDto> nodes,
      List<DependencyMwtDto> mwtSpans,
      String conllu) {}

  public record DependencyResponse(
      double latencyMs,
      int totalTokens,
      List<DependencySentenceDto> sentences,
      String conllu) {}

  public record TokenDto(String text, String type, int start, int end) {}

  public record TokenizeResponse(
      double latencyMs, List<String> sentences, List<TokenDto> tokens) {}

  private record NormalizeRequest(
      String text, String engine, Boolean inspect, Boolean compareAll) {}

  private record DeasciifyRequest(String text, String engine, Boolean compareAll) {}

  private record TextRequest(String text) {}

  public static ZemberekDemoServer create(Path modelDirOverride, Path zemberekNormRootOverride)
      throws IOException {
    long t0 = System.nanoTime();
    System.out.println("[ZemberekDemoServer] Initializing TurkishMorphology...");
    TurkishMorphology morphology = TurkishMorphology.createWithDefaults();

    SpanLatticePerceptronNormalizer perceptron;
    if (modelDirOverride == null && zemberekNormRootOverride == null) {
      System.out.println(
          "[ZemberekDemoServer] Loading bundled normalization models from Zemberek resources...");
      SmoothLm sharedLm = SpanLatticePerceptronNormalizer.loadDefaultSmoothLm();
      perceptron = SpanLatticePerceptronNormalizer.createWithDefaults(morphology, sharedLm);
    } else {
      SmoothLm lm =
          zemberekNormRootOverride != null
              ? SmoothLm.builder(zemberekNormRootOverride.resolve("lm.2gram.slm"))
                  .logBase(Math.E)
                  .build()
              : SpanLatticePerceptronNormalizer.loadDefaultSmoothLm();
      perceptron =
          modelDirOverride != null
              ? SpanLatticePerceptronNormalizer.load(
                  morphology,
                  lm,
                  Files.exists(modelDirOverride.resolve("knowledge.bin.gz"))
                      ? modelDirOverride.resolve("knowledge.bin.gz")
                      : modelDirOverride.resolve("knowledge.bin"),
                  Files.exists(modelDirOverride.resolve("perceptron.bin.gz"))
                      ? modelDirOverride.resolve("perceptron.bin.gz")
                      : modelDirOverride.resolve("perceptron.bin"))
              : SpanLatticePerceptronNormalizer.createWithDefaults(morphology, lm);
    }

    System.out.println(
        "[ZemberekDemoServer] Loading bundled Turkish UD dependency parser...");
    TurkishDependencyParser dependencyParser =
        TurkishDependencyParser.createWithDefaults(morphology);

    System.out.printf(
        Locale.US,
        "[ZemberekDemoServer] Models loaded in %.2fs (Perceptron=%.2f MB, %,d sparse features)%n",
        (System.nanoTime() - t0) / 1e9,
        perceptron.modelRamBytes() / (1024.0 * 1024.0),
        perceptron.activeSparseFeatureCount());
    return new ZemberekDemoServer(morphology, perceptron, dependencyParser);
  }

  public HttpServer start(String host, int port) throws IOException {
    warmup();
    HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

    server.createContext("/api/info", this::handleInfo);
    server.createContext(
        "/api/normalize", ex -> handlePost(ex, NormalizeRequest.class, this::runNormalize));
    server.createContext(
        "/api/deasciify", ex -> handlePost(ex, DeasciifyRequest.class, this::runDeasciify));
    server.createContext(
        "/api/morphology", ex -> handlePost(ex, TextRequest.class, this::runMorphology));
    server.createContext(
        "/api/dependency", ex -> handlePost(ex, TextRequest.class, this::runDependency));
    server.createContext(
        "/api/tokenize", ex -> handlePost(ex, TextRequest.class, this::runTokenize));
    server.createContext("/", this::handleStatic);

    server.start();
    int boundPort = server.getAddress().getPort();
    System.out.println("========================================================================");
    System.out.printf(
        Locale.US, "  Zemberek NLP Web Demo running at: http://%s:%d/%n", host, boundPort);
    System.out.println(
        "  Active modules : Normalization (v2.4), Deasciifiers, Morphology, Dependency Parsing, Tokenization");
    System.out.println("  Press Ctrl+C to stop.");
    System.out.println("========================================================================");
    return server;
  }

  public static void main(String[] args) {
    StartWebDemo.main(args);
  }

  public void warmup() {
    synchronized (engineLock) {
      normInspector.normalizeAndInspect(
          "yarın bende gelicem ama saat 7 de orda olurmusun", "perceptron", true, true);
      deasciifierInspector.warmup();
      morphology.analyzeAndDisambiguate("Yarın ben de geleceğim.");
      if (dependencyParser != null) {
        dependencyParser.parse("Yarın ben de geleceğim.");
      }
    }
  }

  private void handleInfo(HttpExchange ex) throws IOException {
    if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
      sendJson(ex, 405, Map.of("error", "Method not allowed"));
      return;
    }
    sendJson(
        ex,
        200,
        new ServerInfoResponse(
            "v2.4", normInspector.hasZemberekHead(), DeasciifierInspector.engineMetadata()));
  }

  private Object runNormalize(NormalizeRequest req) {
    String text = clampText(req.text(), 20_000);
    String engine = req.engine() == null || req.engine().isBlank() ? "perceptron" : req.engine();
    boolean inspect = req.inspect() == null || req.inspect();
    boolean compareAll = Boolean.TRUE.equals(req.compareAll());
    synchronized (engineLock) {
      return normInspector.normalizeAndInspect(text, engine, inspect, compareAll);
    }
  }

  private Object runDeasciify(DeasciifyRequest req) {
    String text = clampText(req.text(), 20_000);
    String engine = req.engine() == null || req.engine().isBlank() ? "lattice" : req.engine();
    boolean compareAll = req.compareAll() == null || req.compareAll();
    synchronized (engineLock) {
      return deasciifierInspector.deasciifyAndInspect(text, engine, compareAll);
    }
  }

  private MorphResponse runMorphology(TextRequest req) {
    String text = clampText(req.text(), 5_000).trim();
    if (text.isEmpty()) {
      return new MorphResponse(0.0, List.of());
    }
    List<MorphWordDto> words = new ArrayList<>();
    long t0 = System.nanoTime();
    synchronized (engineLock) {
      List<String> sents = TurkishSentenceExtractor.DEFAULT.fromParagraph(text);
      if (sents.isEmpty()) {
        sents = List.of(text);
      }
      for (String s : sents) {
        if (s.isBlank()) {
          continue;
        }
        SentenceAnalysis sa = morphology.analyzeAndDisambiguate(s);
        for (SentenceWordAnalysis swa : sa.getWordAnalyses()) {
          SingleAnalysis best = swa.getBestAnalysis();
          List<String> altParses = new ArrayList<>();
          for (SingleAnalysis ca : swa.getWordAnalysis()) {
            if (!ca.equals(best)) {
              altParses.add(ca.formatLong());
            }
          }
          words.add(
              new MorphWordDto(
                  swa.getWordAnalysis().getInput(),
                  best.getPos().shortForm,
                  best.formatLong(),
                  altParses));
        }
      }
    }
    return new MorphResponse(roundMs((System.nanoTime() - t0) / 1e6), words);
  }

  private DependencyResponse runDependency(TextRequest req) {
    String text = clampText(req.text(), 4_000).trim();
    if (text.isEmpty() || dependencyParser == null) {
      return new DependencyResponse(0.0, 0, List.of(), "");
    }
    List<DependencySentenceDto> sentences = new ArrayList<>();
    StringBuilder allConllu = new StringBuilder();
    int totalTokens = 0;
    long t0 = System.nanoTime();
    synchronized (engineLock) {
      List<String> sents = TurkishSentenceExtractor.DEFAULT.fromParagraph(text);
      if (sents.isEmpty()) {
        sents = List.of(text);
      }
      int maxSents = 12;
      for (String s : sents) {
        if (s.isBlank()) {
          continue;
        }
        if (sentences.size() >= maxSents) {
          break;
        }
        DependencyTree tree = dependencyParser.parse(s);
        List<DependencyNodeDto> nodeDtos = new ArrayList<>(tree.size());
        for (DependencyNode n : tree.getNodes()) {
          nodeDtos.add(
              new DependencyNodeDto(
                  n.id(),
                  n.form(),
                  n.lemma(),
                  n.upos(),
                  n.xpos(),
                  n.feats(),
                  n.headId(),
                  n.deprel(),
                  n.misc()));
        }
        List<DependencyMwtDto> mwtDtos = new ArrayList<>();
        for (ConlluMwtSpan span : tree.getConlluSentence().mwtSpans()) {
          mwtDtos.add(new DependencyMwtDto(span.startId(), span.endId(), span.form()));
        }
        String sentConllu = tree.toConllu().trim();
        if (!allConllu.isEmpty() && !sentConllu.isEmpty()) {
          allConllu.append("\n\n");
        }
        allConllu.append(sentConllu);
        totalTokens += nodeDtos.size();
        sentences.add(new DependencySentenceDto(s, nodeDtos, mwtDtos, sentConllu));
      }
    }
    return new DependencyResponse(
        roundMs((System.nanoTime() - t0) / 1e6), totalTokens, sentences, allConllu.toString());
  }

  private TokenizeResponse runTokenize(TextRequest req) {
    String text = clampText(req.text(), 10_000);
    long t0 = System.nanoTime();
    List<String> sents =
        text.isBlank() ? List.of() : TurkishSentenceExtractor.DEFAULT.fromParagraph(text);
    List<Token> rawToks = text.isEmpty() ? List.of() : TurkishTokenizer.DEFAULT.tokenize(text);
    double ms = roundMs((System.nanoTime() - t0) / 1e6);

    List<TokenDto> dtos = new ArrayList<>(rawToks.size());
    for (Token t : rawToks) {
      dtos.add(new TokenDto(t.getText(), t.getType().name(), t.getStart(), t.getEnd()));
    }
    return new TokenizeResponse(ms, sents, dtos);
  }

  private <R> void handlePost(
      HttpExchange ex, Class<R> reqType, Function<R, Object> handler) throws IOException {
    if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
      sendJson(ex, 405, Map.of("error", "Method not allowed"));
      return;
    }
    try (InputStream is = ex.getRequestBody()) {
      String raw = new String(is.readAllBytes(), StandardCharsets.UTF_8);
      R req = GSON.fromJson(raw.isBlank() ? "{}" : raw, reqType);
      sendJson(ex, 200, handler.apply(req));
    } catch (Exception e) {
      sendJson(
          ex, 500, Map.of("error", e.getMessage() == null ? "Internal error" : e.getMessage()));
    }
  }

  private void handleStatic(HttpExchange ex) throws IOException {
    String path = ex.getRequestURI().getPath();
    if (path == null || path.equals("/") || path.isBlank()) {
      path = "/index.html";
    }
    String cleanName =
        switch (path) {
          case "/index.html",
              "/deasciifiers",
              "/deasciifier",
              "/normalization",
              "/morphology",
              "/dependency",
              "/dependencies",
              "/tokenization",
              "/tokenizer" ->
              "index.html";
          case "/styles.css" -> "styles.css";
          case "/app.js" -> "app.js";
          default -> null;
        };
    if (cleanName == null) {
      sendText(ex, 404, "Not Found");
      return;
    }

    byte[] bytes = loadStaticAsset(cleanName);
    if (bytes == null) {
      sendText(ex, 500, "Missing static asset: " + cleanName);
      return;
    }

    String contentType =
        switch (cleanName) {
          case "styles.css" -> "text/css; charset=utf-8";
          case "app.js" -> "application/javascript; charset=utf-8";
          default -> "text/html; charset=utf-8";
        };
    sendBytes(ex, 200, contentType, bytes);
  }

  private static byte[] loadStaticAsset(String fileName) throws IOException {
    for (Path dir : DEV_STATIC_DIRS) {
      Path diskFile = dir.resolve(fileName);
      if (Files.exists(diskFile)) {
        return Files.readAllBytes(diskFile);
      }
    }
    try (InputStream is = ZemberekDemoServer.class.getResourceAsStream("/webdemo/" + fileName)) {
      return is == null ? null : is.readAllBytes();
    }
  }

  private static String clampText(String s, int maxLen) {
    if (s == null) {
      return "";
    }
    return s.length() > maxLen ? s.substring(0, maxLen) : s;
  }

  private static double roundMs(double ms) {
    return Math.round(ms * 1000.0) / 1000.0;
  }

  private static void sendJson(HttpExchange ex, int status, Object payload) throws IOException {
    sendBytes(
        ex, status, "application/json; charset=utf-8", GSON.toJson(payload).getBytes(StandardCharsets.UTF_8));
  }

  private static void sendText(HttpExchange ex, int status, String message) throws IOException {
    sendBytes(ex, status, "text/plain; charset=utf-8", message.getBytes(StandardCharsets.UTF_8));
  }

  private static void sendBytes(HttpExchange ex, int status, String contentType, byte[] bytes)
      throws IOException {
    ex.getResponseHeaders().set("Content-Type", contentType);
    ex.getResponseHeaders().set("Cache-Control", "no-store");
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(bytes);
    }
  }
}
