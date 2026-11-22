package zemberek.morphology.ambiguity.dataset;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.analysis.SingleAnalysis;
import zemberek.morphology.analysis.WordAnalysis;
import zemberek.morphology.generator.WordGenerator;
import zemberek.morphology.lexicon.DictionaryItem;
import zemberek.morphology.lexicon.RootLexicon;
import zemberek.morphology.morphotactics.Morpheme;

public class InformalAnalysisExperimentTest {

  @Test
  public void testInformalAndDiacritics() {
    String[] testWords = {
        "oluyo", "olcak", "diyo", "yapıyo", "yapcak", "biliyosun", "görüyo",
        "bi", "cok", "bişey", "cnm", "ayy", "canımcım",
        "hemde", "bencede", "yinede", "seninde", "tabiki", "vede", "olsada", "olurmu",
        "anataşınmazın", "anagayrimenkulün", "Anayapının",
        "tesbiti", "malesef", "ınşallah", "insallah", "aptallğı", "olmadıgını",
        "sizinde", "rakamnlar", "'"
    };

    TurkishMorphology defaultMorphology = TurkishMorphology.createWithDefaults();

    TurkishMorphology informalMorphology = TurkishMorphology.builder()
        .setLexicon(RootLexicon.getDefault())
        .useInformalAnalysis()
        .build();

    TurkishMorphology informalNoDiacritics = TurkishMorphology.builder()
        .setLexicon(RootLexicon.getDefault())
        .useInformalAnalysis()
        .ignoreDiacriticsInAnalysis()
        .build();

    System.out.println("================================================================================");
    System.out.printf("%-18s | %-12s | %-12s | %-12s | %s\n",
        "Word", "Default", "Informal", "Inf+NoDiacr", "Informal Analysis / Lemma");
    System.out.println("-------------------+--------------+--------------+--------------+-----------------------");

    for (String w : testWords) {
      WordAnalysis waDef = defaultMorphology.analyze(w);
      WordAnalysis waInf = informalMorphology.analyze(w);
      WordAnalysis waNoDia = informalNoDiacritics.analyze(w);

      boolean defOk = waDef.isCorrect() && waDef.analysisCount() > 0;
      boolean infOk = waInf.isCorrect() && waInf.analysisCount() > 0;
      boolean noDiaOk = waNoDia.isCorrect() && waNoDia.analysisCount() > 0;

      String sampleParse = "";
      if (infOk) {
        SingleAnalysis sa = waInf.getAnalysisResults().get(0);
        sampleParse = sa.formatLong();
      } else if (noDiaOk) {
        SingleAnalysis sa = waNoDia.getAnalysisResults().get(0);
        sampleParse = sa.formatLong() + " (diacritics)";
      }

      System.out.printf("%-18s | %-12s | %-12s | %-12s | %s\n",
          w,
          defOk ? "OK (" + waDef.analysisCount() + ")" : "FAIL",
          infOk ? "OK (" + waInf.analysisCount() + ")" : "FAIL",
          noDiaOk ? "OK (" + waNoDia.analysisCount() + ")" : "FAIL",
          sampleParse);
    }
    System.out.println("================================================================================");
  }

  @Test
  public void testInformalToFormalGeneration() {
    String[] testWords = {"oluyo", "olcak", "diyo", "yapıyo", "yapcak", "biliyosun", "görüyo"};

    TurkishMorphology defaultMorphology = TurkishMorphology.createWithDefaults();
    TurkishMorphology informalMorphology = TurkishMorphology.builder()
        .setLexicon(RootLexicon.getDefault())
        .useInformalAnalysis()
        .build();

    WordGenerator formalGenerator = defaultMorphology.getWordGenerator();

    System.out.println("================================================================================");
    System.out.printf("%-12s -> %-15s | %s\n", "Informal", "Formal Generated", "Morphemes");
    System.out.println("-------------+-----------------+------------------------------------------------");

    for (String w : testWords) {
      WordAnalysis wa = informalMorphology.analyze(w);
      if (!wa.isCorrect() || wa.analysisCount() == 0) {
        System.out.printf("%-12s -> %-15s | FAILED TO PARSE\n", w, "---");
        continue;
      }

      SingleAnalysis sa = wa.getAnalysisResults().get(0);
      DictionaryItem item = sa.getDictionaryItem();

      // Map informal morphemes to their formal equivalents
      List<Morpheme> formalMorphemes = new ArrayList<>();
      for (Morpheme m : sa.getMorphemes()) {
        if (m.informal && m.mappedMorpheme != null) {
          formalMorphemes.add(m.mappedMorpheme);
        } else {
          formalMorphemes.add(m);
        }
      }

      List<WordGenerator.Result> results = formalGenerator.generate(item, formalMorphemes);
      String generated = results.isEmpty() ? "---" : results.get(0).surface;

      System.out.printf("%-12s -> %-15s | %s\n", w, generated, formalMorphemes);
    }
    System.out.println("================================================================================");
  }
}

