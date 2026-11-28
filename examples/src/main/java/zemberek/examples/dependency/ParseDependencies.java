package zemberek.examples.dependency;

import java.io.IOException;
import zemberek.core.logging.Log;
import zemberek.dependency.DependencyNode;
import zemberek.dependency.DependencyTree;
import zemberek.dependency.TurkishDependencyParser;
import zemberek.morphology.TurkishMorphology;

public class ParseDependencies {

  public static void main(String[] args) throws IOException {
    TurkishMorphology morphology = TurkishMorphology.createWithDefaults();
    TurkishDependencyParser parser = TurkishDependencyParser.createWithDefaults(morphology);

    String[] sentences = {
        "Prof. Dr. Fethi Bey saat 14.30'da Türkiye'ye döndü.",
        "Fakülteyi bitirenler en uçtan göreve başlıyorlarmış.",
        "Evdeki çocuklar yarın okula gidecek."
    };

    for (String sentence : sentences) {
      DependencyTree tree = parser.parse(sentence);
      Log.info("Sentence: %s", sentence);
      Log.info("Root: %s (%s)", tree.getRoot().form(), tree.getRoot().upos());
      for (DependencyNode node : tree.getNodes()) {
        String headForm = node.isRoot() ? "ROOT" : tree.nodeById(node.headId()).form();
        Log.info("  %2d %-14s %-6s --(%s)--> %s",
            node.id(), node.form(), node.upos(), node.deprel(), headForm);
      }
      System.out.println("\nCoNLL-U:\n" + tree.toConllu());
    }
  }
}
