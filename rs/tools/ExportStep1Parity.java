import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import zemberek.core.turkish.PhoneticAttribute;
import zemberek.core.turkish.Turkish;
import zemberek.core.turkish.TurkishAlphabet;
import zemberek.core.turkish.hyphenation.TurkishSyllableExtractor;
import zemberek.morphology.analysis.AttributesHelper;
import zemberek.morphology.analysis.tr.PronunciationGuesser;
import zemberek.morphology.analysis.tr.TurkishNumbers;
import zemberek.morphology.analysis.tr.TurkishNumeralEndingMachine;
import zemberek.morphology.morphotactics.AttributeSet;
import zemberek.normalization.v2.NormV2Types;
import zemberek.tokenization.Token;
import zemberek.tokenization.TurkishDetokenizer;
import zemberek.tokenization.TurkishSentenceExtractor;
import zemberek.tokenization.TurkishTokenizer;

/**
 * Generates Step 1 ground-truth parity fixtures from Java Zemberek HEAD for
 * {@code zemberek-core} and {@code zemberek-tok}.
 */
public class ExportStep1Parity {

  private static String esc(String s) {
    StringBuilder sb = new StringBuilder(s.length() + 4);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\' -> sb.append("\\\\");
        case '\t' -> sb.append("\\t");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        default -> sb.append(c);
      }
    }
    return sb.toString();
  }

  private static int phonoMaskBits(AttributeSet<PhoneticAttribute> set) {
    int bits = 0;
    for (PhoneticAttribute a : PhoneticAttribute.values()) {
      if (set.contains(a)) {
        bits |= (1 << a.ordinal());
      }
    }
    return bits;
  }

  public static void main(String[] args) throws IOException {
    Path repoRoot = Paths.get(args.length > 0 ? args[0] : ".");
    Path outDir = repoRoot.resolve("rs/testdata");
    Files.createDirectories(outDir);

    TurkishAlphabet alpha = TurkishAlphabet.INSTANCE;
    PronunciationGuesser guesser = new PronunciationGuesser();
    TurkishNumeralEndingMachine endingMachine = new TurkishNumeralEndingMachine();

    // 1. Collect vocabulary & abbreviations for core tests
    List<String> words = new ArrayList<>();
    for (String line : Files.readAllLines(
        repoRoot.resolve("morphology/src/test/resources/top-20K-words.txt"),
        StandardCharsets.UTF_8)) {
      String w = line.trim();
      if (!w.isEmpty() && words.size() < 2000) {
        words.add(w);
      }
    }
    words.addAll(Arrays.asList(
        "kitap", "ağaç", "saat", "alkol", "kâğıt", "rüzgâr", "hükûmet", "İzmir", "Iğdır",
        "İSTANBUL", "IĞDIR", "i\u0307", "I\u0307STANBUL", "Ankara'da", "İstanbul’da",
        "göğüs", "şeker", "sıkıntı", "birkaç", "birkac", "tren", "spor", "yoğurt", "kart",
        "türk", "kürk", "kalp", "çift", "üç", "dört", "altı", "milyon", "Café", "naïve", "señor"));

    List<String> abbrs = new ArrayList<>();
    for (String line : Files.readAllLines(
        repoRoot.resolve("tokenization/src/main/resources/tokenization/abbreviations.txt"),
        StandardCharsets.UTF_8)) {
      String t = line.trim();
      if (!t.isEmpty()) {
        abbrs.add(t.replace(".", "").toLowerCase(Turkish.LOCALE));
      }
    }
    abbrs.addAll(Arrays.asList(
        "tbmm", "tübitak", "aselsan", "odtu", "odtü", "itu", "itü", "f16", "h1n1", "mp3",
        "b12", "3g", "4g", "a4", "x5", "w3c", "q7", "rtük", "tsk", "sgk", "kdv", "ötv",
        "100", "2026", "12300", "1000000", "00", "999999999999999999999a"));

    Path coreFile = outDir.resolve("step1_core_parity.tsv");
    int coreCount = 0;
    try (BufferedWriter bw = Files.newBufferedWriter(coreFile, StandardCharsets.UTF_8)) {
      // A. TurkishAlphabet + MorphemicAttributes + Syllables
      String[] suffixes = {"", "ler", "lar", "m", "n", "k", "t", "ş", "yor", "cık"};
      for (int idx = 0; idx < words.size(); idx++) {
        String w = words.get(idx);
        String lo = w.toLowerCase(Turkish.LOCALE);
        String up = w.toUpperCase(Turkish.LOCALE);
        String cap = Turkish.capitalize(w);
        String asc = alpha.toAscii(w);
        String circ = alpha.normalizeCircumflex(w);
        String apos = alpha.normalizeApostrophe(w);
        String strip = alpha.stripApostrophes(w);
        String fgn = alpha.foreignDiacriticsToTurkish(w);
        int phono0 = phonoMaskBits(AttributesHelper.getMorphemicAttributes(lo));
        String sfx = suffixes[idx % suffixes.length];
        int phono1 = phonoMaskBits(
            AttributesHelper.getMorphemicAttributes(sfx, AttributesHelper.getMorphemicAttributes(lo)));
        String sylDef = String.join("-", TurkishSyllableExtractor.DEFAULT.getSyllables(lo));
        String sylStrict = String.join("-", TurkishSyllableExtractor.STRICT.getSyllables(lo));
        bw.write("ALPHA\t" + esc(w) + "\t" + esc(lo) + "\t" + esc(up) + "\t" + esc(cap) + "\t"
            + esc(asc) + "\t" + esc(circ) + "\t" + esc(apos) + "\t" + esc(strip) + "\t"
            + esc(fgn) + "\t" + phono0 + "\t" + esc(sfx) + "\t" + phono1 + "\t"
            + esc(sylDef) + "\t" + esc(sylStrict) + "\n");
        coreCount++;
      }

      // B. PronunciationGuesser (deduplicated)
      for (String a : abbrs.stream().distinct().limit(600).toList()) {
        if (a.isEmpty()) continue;
        String g1 = guesser.guessForAbbreviation(a);
        String g2 = guesser.toTurkishLetterPronunciations(a);
        String g3 = guesser.replaceEnglishSpecificChars(a);
        bw.write("PRON\t" + esc(a) + "\t" + esc(g1) + "\t" + esc(g2) + "\t" + esc(g3) + "\n");
        coreCount++;
      }

      // C. NumeralEndingMachine & TurkishNumbers
      String[] numInputs = {
          "0", "00", "000", "1", "2", "3", "4", "5", "6", "7", "8", "9",
          "10", "11", "19", "20", "30", "40", "50", "60", "70", "80", "90",
          "100", "101", "110", "123", "999", "1000", "1001", "1200", "12300",
          "10000", "100000", "1000000", "2500000", "1000000000", "2026",
          "a20", "abc", "1abc", "1000000000000", "007", "+42"
      };
      for (String ns : numInputs) {
        String endWord = endingMachine.find(ns);
        String convStr = ns.chars().allMatch(c -> (c >= '0' && c <= '9') || c == '+')
            ? TurkishNumbers.convertNumberToString(ns)
            : "<NA>";
        bw.write("NUMEND\t" + esc(ns) + "\t" + esc(endWord) + "\t" + esc(convStr) + "\n");
        coreCount++;
      }

      long[] longs = {
          0L, 1L, -1L, 4L, 10L, 12L, 100L, 101L, 1000L, 1001L, 2000L, 12345L,
          1_000_000L, 1_000_001L, 82_000_002L, 1_000_000_000L, 1_000_000_000_000L,
          -999_999_999_999L, 999_999_999_999_999_999L
      };
      for (long lv : longs) {
        String text = TurkishNumbers.convertToString(lv);
        long back = lv >= 0 ? TurkishNumbers.convertToNumber(text) : -2L;
        bw.write("NUMLONG\t" + lv + "\t" + esc(text) + "\t" + back + "\n");
        coreCount++;
      }

      String[] ordInputs = {"0", "1.", "2.", "3", "4.", "6.", "10.", "20.", "100.", "1000.", "2026."};
      for (String oi : ordInputs) {
        bw.write("NUMORD\t" + esc(oi) + "\t" + esc(TurkishNumbers.convertOrdinalNumberString(oi)) + "\n");
        coreCount++;
      }

      String[] romanInvalid = {"", "IIII", "VV", "LL", "DD", "MMMM", "IC", "IL", "XM", "XD", "ABC", "CDXLIV", "MCMXCIX", "MMXXVI", "mmmcmxcix"};
      for (String ri : romanInvalid) {
        bw.write("ROMAN\t" + esc(ri) + "\t" + TurkishNumbers.romanToDecimal(ri) + "\n");
        coreCount++;
      }
    }

    // 2. Build 2,050 diverse paragraphs (~10,200 sentences) for SentenceExtractor & Tokenizer parity
    List<String> rawSentences = new ArrayList<>();
    rawSentences.addAll(Files.readAllLines(
        repoRoot.resolve("tokenization/src/test/resources/tokenization/Sentence-Boundary-Test.txt"),
        StandardCharsets.UTF_8));
    rawSentences.addAll(Files.readAllLines(
        repoRoot.resolve("tokenization/src/test/resources/tokenization/Sentence-Boundary-Train.txt"),
        StandardCharsets.UTF_8));

    List<String> paragraphs = new ArrayList<>(2100);
    // Group 10,000 sentences into 2,000 5-sentence paragraphs (including occasional missing space!)
    StringBuilder pb = new StringBuilder();
    int inPara = 0;
    int totalUsedSentences = 0;
    for (String s : rawSentences) {
      String clean = s.replace("\ufeff", "").trim();
      if (clean.isEmpty()) continue;
      if (inPara > 0) {
        if ((totalUsedSentences % 23) != 0) {
          pb.append(' ');
        }
      }
      pb.append(clean);
      inPara++;
      totalUsedSentences++;
      if (inPara == 5) {
        paragraphs.add(pb.toString());
        pb.setLength(0);
        inPara = 0;
        if (paragraphs.size() >= 2000) break;
      }
    }
    if (pb.length() > 0) {
      paragraphs.add(pb.toString());
    }

    // Add noisy, emoji, URL, email, Roman numeral, date/time, quote, and edge-case paragraphs
    paragraphs.addAll(Files.readAllLines(
        repoRoot.resolve("tokenization/src/test/resources/tokenization/Tokenizer-Train.txt"),
        StandardCharsets.UTF_8));
    paragraphs.addAll(Files.readAllLines(
        repoRoot.resolve("normalization/src/test/resources/normalization/mini-noisy-corpus.txt"),
        StandardCharsets.UTF_8));
    paragraphs.addAll(Arrays.asList(
        "🥰 Dr. Ahmet çok ❤️ güzel. 👨‍👩‍👧‍👦! 🇹🇷 bayrağı ve 👍🏽 işareti.",
        "Prof. Dr. Veli Zambur %2.5 lik enflasyon oranini begenmemis! Saat 10:20:53 veya 10.20.00'da buluşalım.",
        "\"Merhaba! Bugün hava çok güzel. Ne dersin?\" dedi tavşan. Havucu kemirirken.",
        "Siteye https://www.google.com.tr. adresinden veya www.fo.bar. ya da fo.com.tr/index.html? ile girin.",
        "Bana first.middle.last@sub.example.co.uk ve user-name+tag@my-domain.org'dan yazın: @kemal'in #foo_bar'a selamı var!",
        "XVI. yüzyılda M. Kemal Atatürk, I.B.M.'nin CD'ye ve DVD'de F-16'yı inceledi; 02.12.1998'de 1/1/2011 oldu.",
        "Fiyatı 100₺, 50€ veya $40 oldu; sıcaklık 25°C'de (yaklaşık %60 nem) ölçüldü!",
        "hediyye etdimm🥰 iyi❤ iyi❤️ ☝️☝️😊 kötü😡ama ucuz güzel👍teşekkürler🙏 😂😂😂 cok guzel ▪\uFE0F",
        "«Çalıkuşu»'nu ve “Yaban”’ı okudum. \"Soner\"'e boyle dedi (2020)'de [TÜBİTAK]'ın projesi.",
        "¯\\_(ツ)_/¯ ;) ;‑) :‑/ :'( :') :-) :-D 8-) O_o o_O \\o/ <3 <meta_tag> L'Oréal Bjørn زنبورك __he_llo__"
    ));

    TurkishSentenceExtractor segDefault = TurkishSentenceExtractor.DEFAULT;
    TurkishSentenceExtractor segNoQuote = TurkishSentenceExtractor.builder()
        .doNotSplitInDoubleQuotes()
        .useDefaultModel()
        .build();
    TurkishTokenizer tokDefault = TurkishTokenizer.DEFAULT;
    TurkishTokenizer tokAll = TurkishTokenizer.ALL;

    Path tokFile = outDir.resolve("step1_tok_parity.tsv");
    int totalTokensChecked = 0;
    try (BufferedWriter bw = Files.newBufferedWriter(tokFile, StandardCharsets.UTF_8)) {
      for (int idx = 0; idx < paragraphs.size(); idx++) {
        String p = paragraphs.get(idx);
        if (p.isEmpty()) continue;

        List<String> sDef = segDefault.fromParagraph(p);
        List<String> sNoQ = segNoQuote.fromParagraph(p);
        bw.write("SEG\t" + esc(p) + "\t" + esc(String.join("\u001F", sDef)) + "\t"
            + esc(String.join("\u001F", sNoQ)) + "\n");

        List<Token> toks = tokDefault.tokenize(p);
        StringBuilder tb = new StringBuilder(toks.size() * 16);
        for (int i = 0; i < toks.size(); i++) {
          if (i > 0) tb.append('\u001F');
          Token t = toks.get(i);
          tb.append(t.getType().ordinal())
              .append('\u001E')
              .append(t.getStart())
              .append('\u001E')
              .append(t.getEnd())
              .append('\u001E')
              .append(t.getText());
        }
        totalTokensChecked += toks.size();
        String detok = TurkishDetokenizer.DEFAULT.detokenizeTokens(toks);
        bw.write("TOK\t" + esc(p) + "\t" + esc(tb.toString()) + "\t" + esc(detok) + "\n");

        // Also export ALL-mode tokens + NormV2Types.spanTokenize on first 250 + last 50 paragraphs
        if (idx < 250 || idx >= paragraphs.size() - 50) {
          List<Token> allToks = tokAll.tokenize(p);
          StringBuilder ab = new StringBuilder(allToks.size() * 16);
          for (int i = 0; i < allToks.size(); i++) {
            if (i > 0) ab.append('\u001F');
            Token t = allToks.get(i);
            ab.append(t.getType().ordinal())
                .append('\u001E')
                .append(t.getStart())
                .append('\u001E')
                .append(t.getEnd());
          }
          List<NormV2Types.SpanToken> sToks = NormV2Types.spanTokenize(p);
          StringBuilder sb2 = new StringBuilder(sToks.size() * 16);
          List<NormV2Types.SpanArc> idPath = new ArrayList<>(sToks.size());
          for (int i = 0; i < sToks.size(); i++) {
            NormV2Types.SpanToken st = sToks.get(i);
            if (i > 0) sb2.append('\u001F');
            sb2.append(st.shape().ordinal())
                .append('\u001E')
                .append(st.adjacentToPrev() ? '1' : '0')
                .append('\u001E')
                .append(st.isRandomLaugh() ? '1' : '0')
                .append('\u001E')
                .append(st.lower())
                .append('\u001E')
                .append(st.ascii());
            idPath.add(NormV2Types.SpanArc.plain(
                i, i + 1, st.surface(), st.lower(), NormV2Types.SRC_IDENTITY, (short) 0));
          }
          String recon = NormV2Types.reconstructText(sToks, idPath);
          bw.write("SPAN\t" + esc(p) + "\t" + esc(ab.toString()) + "\t" + esc(sb2.toString())
              + "\t" + esc(recon) + "\n");
        }
      }
    }

    System.out.printf(
        "Exported Step 1 fixtures: core=%d records (%s), paragraphs=%d, sentences_used=%d, tokens=%d (%s)%n",
        coreCount, coreFile, paragraphs.size(), totalUsedSentences, totalTokensChecked, tokFile);
  }
}
