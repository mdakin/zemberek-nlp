package zemberek.apps.corpus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CollectUnparsedWordsTest {

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void testCollectUnparsedWords() throws Exception {
    Path inDir = temp.newFolder("clean_text").toPath();
    Path file1 = inDir.resolve("sample1.txt");

    // "kitap", "okula", "gitti", "Ankara'da" are valid Turkish words.
    // "koronavirüs", "blablabla" are unparsed words.
    String text1 =
        "Ali dün akşam okula gitti.\n"
            + "Ankara'da güzel bir gün yaşandı.\n"
            + "Yeni koronavirüs dalgası başladı.\n"
            + "Herkes koronavirüs hakkında konuşuyor.\n"
            + "Bu bir blablabla kelimesidir.\n";
    Files.write(file1, text1.getBytes(StandardCharsets.UTF_8));

    Path outFile = temp.newFile("unparsed.tsv").toPath();

    CollectUnparsedWords app = new CollectUnparsedWords();
    app.input = inDir;
    app.output = outFile;
    app.threadCount = 2;
    app.minFrequency = 1;

    app.run();

    Assert.assertTrue("Output file must exist", Files.exists(outFile));
    List<String> lines = Files.readAllLines(outFile, StandardCharsets.UTF_8);

    Assert.assertTrue("Output must have header", lines.size() >= 2);
    Assert.assertEquals("word\tfrequency", lines.get(0));

    // Verify known words are NOT in the unparsed list
    for (String line : lines) {
      String word = line.split("\t")[0];
      Assert.assertNotEquals("kitap", word);
      Assert.assertNotEquals("okula", word);
      Assert.assertNotEquals("gitti", word);
      Assert.assertNotEquals("güzel", word);
    }

    // Verify "koronavirüs" is collected with frequency 2
    boolean foundCorona = false;
    for (String line : lines) {
      if (line.startsWith("koronavirüs\t")) {
        foundCorona = true;
        Assert.assertEquals("koronavirüs\t2", line);
      }
    }
    Assert.assertTrue("koronavirüs must be detected as unparsed with frequency 2", foundCorona);

    // Verify "blablabla" is collected with frequency 1
    boolean foundBla = false;
    for (String line : lines) {
      if (line.startsWith("blablabla\t")) {
        foundBla = true;
        Assert.assertEquals("blablabla\t1", line);
      }
    }
    Assert.assertTrue("blablabla must be detected as unparsed with frequency 1", foundBla);

    // The first word after header must be "koronavirüs" because it has the highest frequency (2)
    Assert.assertTrue("First entry must be koronavirüs", lines.get(1).startsWith("koronavirüs\t"));
  }
}
