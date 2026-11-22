import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import zemberek.tokenization.Token;
import zemberek.tokenization.TurkishTokenizer;

/**
 * Tokenizes raw sentences from a JSONL or plain-text input file using
 * Zemberek's TurkishTokenizer.DEFAULT and writes JSONL records containing
 * indexed tokens with character offsets.
 */
public class TokenizeForNormalization {

  public static void main(String[] args) throws IOException {
    if (args.length < 2) {
      System.err.println(
          "Usage: TokenizeForNormalization <input_jsonl_or_txt> <output_tokenized_jsonl>");
      System.exit(1);
    }

    Path inputPath = Path.of(args[0]);
    Path outputPath = Path.of(args[1]);
    if (outputPath.getParent() != null) {
      Files.createDirectories(outputPath.getParent());
    }

    boolean isJsonl = inputPath.getFileName().toString().toLowerCase().endsWith(".jsonl");
    String stem = stripExtension(inputPath.getFileName().toString());
    Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    int count = 0;
    try (BufferedReader reader = Files.newBufferedReader(inputPath, StandardCharsets.UTF_8);
        BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
      String line;
      int lineIdx = 0;
      while ((line = reader.readLine()) != null) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
          continue;
        }

        JsonObject rec;
        if (isJsonl) {
          rec = JsonParser.parseString(trimmed).getAsJsonObject();
          String sid =
              rec.has("sentence_id") && !rec.get("sentence_id").isJsonNull()
                  ? rec.get("sentence_id").getAsString()
                  : (rec.has("id") && !rec.get("id").isJsonNull()
                      ? rec.get("id").getAsString()
                      : String.format("%s_%05d", stem, lineIdx + 1));
          if (!rec.has("id") || rec.get("id").isJsonNull()) {
            rec.addProperty("id", sid);
          }
          rec.addProperty("sentence_id", sid);
        } else {
          rec = new JsonObject();
          String sid = String.format("%s_%05d", stem, lineIdx + 1);
          rec.addProperty("id", sid);
          rec.addProperty("sentence_id", sid);
          rec.addProperty("text", line);
        }

        String text =
            rec.has("text") && !rec.get("text").isJsonNull()
                ? rec.get("text").getAsString()
                : rec.get("raw_text").getAsString();
        if (!rec.has("text") || rec.get("text").isJsonNull()) {
          rec.addProperty("text", text);
        }
        List<Token> tokens = TurkishTokenizer.DEFAULT.tokenize(text);
        JsonArray tokArr = new JsonArray();
        for (int i = 0; i < tokens.size(); i++) {
          Token t = tokens.get(i);
          JsonObject tObj = new JsonObject();
          tObj.addProperty("index", i);
          tObj.addProperty("surface", t.getText());
          tObj.addProperty("type", t.getType().name());
          tObj.addProperty("start", t.getStart());
          tObj.addProperty("end", t.getEnd());
          tokArr.add(tObj);
        }
        rec.add("tokens", tokArr);

        writer.write(gson.toJson(rec));
        writer.newLine();
        count++;
        lineIdx++;
      }
    }

    System.out.println("Tokenized " + count + " sentences -> " + outputPath);
  }

  private static String stripExtension(String name) {
    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(0, dot) : name;
  }
}
