package zemberek.dependency;

import java.io.IOException;
import java.util.List;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.analysis.format.ConlluSentence;

/**
 * End-to-end integration tests verifying the bundled {@code dep_boun_medium_int8.bin.gz} model
 * loaded via {@link TurkishDependencyParser#createWithDefaults(TurkishMorphology)}.
 */
public class TurkishDependencyParserIntegrationTest {

  private static TurkishDependencyParser parser;

  @BeforeClass
  public static void setUpClass() throws IOException {
    TurkishMorphology morphology = TurkishMorphology.createWithDefaults();
    parser = TurkishDependencyParser.createWithDefaults(morphology);
  }

  @Test
  public void parsesNewsSentenceWithEntitiesAndTime() {
    String sentence = "Prof. Dr. Fethi Bey saat 14.30'da Türkiye'ye döndü.";
    DependencyTree tree = parser.parse(sentence);

    Assert.assertEquals(9, tree.size());
    DependencyNode root = tree.getRoot();
    Assert.assertNotNull(root);
    Assert.assertEquals("döndü", root.form());
    Assert.assertEquals("root", root.deprel());

    // Core syntactic attachments on the bundled model
    DependencyNode turkiyeye = tree.nodeById(7);
    Assert.assertEquals("Türkiye'ye", turkiyeye.form());
    Assert.assertEquals(root.id(), turkiyeye.headId());
    Assert.assertEquals("obl", turkiyeye.deprel());

    DependencyNode period = tree.nodeById(9);
    Assert.assertEquals(root.id(), period.headId());
    Assert.assertEquals("punct", period.deprel());

    // Entire sentence belongs to the root subtree and round-trips through CoNLL-U
    Assert.assertEquals(tree.size(), tree.getSubtree(root.id()).size());
    List<ConlluSentence> parsed = ConlluSentence.parseAll(tree.toConllu());
    Assert.assertEquals(1, parsed.size());
    Assert.assertEquals(tree.toConllu(), parsed.getFirst().toConllu());
  }

  @Test
  public void parsesMwtBoundCopulaAndRelativeClauseSentences() {
    DependencyTree mwtTree = parser.parse("Fakülteyi bitirenler en uçtan göreve başlıyorlarmış.");
    Assert.assertFalse(mwtTree.getConlluSentence().mwtSpans().isEmpty());
    DependencyNode root = mwtTree.getRoot();
    Assert.assertNotNull(root);
    Assert.assertEquals("başlıyorlar", root.form());
    Assert.assertEquals("root", root.deprel());

    // Bound copula MWT part "mış" attaches to "başlıyorlar"
    DependencyNode copNode = mwtTree.nodeById(root.id() + 1);
    Assert.assertEquals("mış", copNode.form());
    Assert.assertEquals(root.id(), copNode.headId());
    Assert.assertEquals("cop", copNode.deprel());

    // Bound -ki + bound copula sentence
    DependencyTree kiTree = parser.parse("Evdeki çocuklar İstanbul'daydı.");
    Assert.assertNotNull(kiTree.getRoot());
    Assert.assertEquals("root", kiTree.getRoot().deprel());
    Assert.assertEquals(kiTree.size(), kiTree.getSubtree(kiTree.getRoot().id()).size());
  }

  @Test
  public void handlesEmptyAndSingleTokenInputsGracefully() {
    Assert.assertEquals(0, parser.parse("").size());
    Assert.assertEquals(0, parser.parse("   ").size());

    DependencyTree single = parser.parse("Merhaba");
    Assert.assertEquals(1, single.size());
    Assert.assertEquals(0, single.nodeById(1).headId());
    Assert.assertEquals("root", single.nodeById(1).deprel());
  }
}
