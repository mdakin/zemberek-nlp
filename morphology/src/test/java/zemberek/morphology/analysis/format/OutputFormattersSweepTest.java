package zemberek.morphology.analysis.format;

import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.analysis.SentenceAnalysis;
import zemberek.morphology.analysis.SentenceWordAnalysis;
import zemberek.morphology.analysis.SingleAnalysis;

/**
 * Exhaustive structural invariant tests across all standard output formatters over diverse Turkish
 * morphosyntactic constructions, edge cases, named entities, clitics, numerals, and OOV words.
 */
public class OutputFormattersSweepTest {

  private static TurkishMorphology morphology;

  private static final String[] DIVERSE_SENTENCES = {
    "Fakülteyi bitirenler en uçtan göreve başlıyorlarmış.",
    "Eşeklerin sırtlarına yüklenmiş sepetlerle taşınırdı üzümler.",
    "Ankara'da ve İstanbul'daydı; TBMM'de 15'inci madde görüşülecekmiş!",
    "Gerçekleştirilen projelerdeki eksiklikler giderilemediğinden başvurular ertelendi.",
    "Bu kitaplarımızdan birini seninkiyle değiştirebilir miyiz?",
    "Yarın hava güzel olursa geliverir, olmazsa evde oturup çalışırız.",
    "Böyle bir şey ne görülmüştür ne de duyulmuştur, değil miydi?",
    "Birbirimize güvenmeden, konuşmaksızın ve anlaşamadan ilerleyemeyiz.",
    "Kendi kendine söylenirken oldukça yorgun görünüyordun.",
    "2026'da %12,5'lik artışla 3'er paket dağıtılacağı açıklandı.",
    "QuasarXyz123 bilinmeyen kelimesi de cümlede vardı, yoktur demeyin."
  };

  @BeforeClass
  public static void setUpClass() {
    morphology = TurkishMorphology.createWithDefaults();
  }

  @Test
  public void allFormattersSatisfyStructuralInvariantsAcrossDiverseCorpus() {
    OflazerFormatter[] oflazerDialects = {
      OflazerFormatter.oflazer94(),
      OflazerFormatter.metuSabanci(),
      OflazerFormatter.sak2008(),
      OflazerFormatter.starlangUpper()
    };
    ConlluFormatter[] conlluModes = {
      ConlluFormatter.builder()
          .dialect(ConlluFormatter.Dialect.UD_TURKIC_BOUN)
          .mwtMode(ConlluFormatter.MwtMode.COPULA_AND_KI)
          .includeDerivedFrom(true)
          .includeZemberekInMisc(true)
          .build(),
      ConlluFormatter.imstDefault(),
      ConlluFormatter.singleToken()
    };

    for (int sIdx = 0; sIdx < DIVERSE_SENTENCES.length; sIdx++) {
      String sentence = DIVERSE_SENTENCES[sIdx];
      SentenceAnalysis sa = morphology.analyzeAndDisambiguate(sentence);

      // 1. Validate CoNLL-U 10-column & UD v2 format invariants
      for (ConlluFormatter cf : conlluModes) {
        String conllu = cf.formatSentence("s_" + (sIdx + 1), sa);
        validateConlluBlock(conllu, sentence);
      }

      // 2. Validate Apertium sentence stream
      String apStream = ApertiumFormatter.INSTANCE.formatSentenceStream(sa);
      Assert.assertFalse(apStream.isEmpty());
      Assert.assertTrue(apStream.startsWith("^") && apStream.endsWith("$"));

      // 3. Validate every candidate analysis (not just best) across all single-word formatters
      for (SentenceWordAnalysis swa : sa) {
        List<SingleAnalysis> candidates = new ArrayList<>(swa.getWordAnalysis().getAnalysisResults());
        candidates.add(swa.getBestAnalysis());
        for (SingleAnalysis cand : candidates) {
          for (OflazerFormatter of : oflazerDialects) {
            String res = of.format(cand);
            Assert.assertFalse("Empty Oflazer output for " + cand, res.isEmpty());
            Assert.assertFalse("Double '+' in Oflazer output: " + res,
                !cand.getDictionaryItem().lemma.equals("+") && res.contains("++"));
            Assert.assertFalse("Trailing '+' in Oflazer output: " + res, res.endsWith("+"));
          }

          String ap = ApertiumFormatter.INSTANCE.format(cand);
          Assert.assertFalse(ap.isEmpty());
          if (!cand.isUnknown()) {
            Assert.assertTrue("Expected <tag> in Apertium output: " + ap,
                ap.contains("<") && ap.endsWith(">"));
          }

          String um = UniMorphFormatter.TAB_ROW.format(cand);
          String[] umCols = um.split("\t", -1);
          Assert.assertEquals("UniMorph must have 3 tab columns: " + um, 3, umCols.length);
          Assert.assertFalse(umCols[2].isEmpty());

          String seg = SegmentedSurfaceFormatter.CANONICAL_SPACE.format(cand);
          Assert.assertFalse(seg.isEmpty());

          String leipzig = SegmentedSurfaceFormatter.LEIPZIG.format(cand);
          Assert.assertEquals("Leipzig gloss must have 2 tab tiers: " + leipzig,
              2, leipzig.split("\t", -1).length);
        }
      }
    }
  }

  private static void validateConlluBlock(String conllu, String expectedText) {
    String[] lines = conllu.split("\n");
    Assert.assertTrue(lines[0].startsWith("# sent_id = "));
    Assert.assertEquals("# text = " + expectedText, lines[1]);

    int expectedNextId = 1;
    int mwtEnd = -1;

    for (int i = 2; i < lines.length; i++) {
      String line = lines[i];
      String[] cols = line.split("\t", -1);
      Assert.assertEquals("Row must have 10 columns: " + line, 10, cols.length);
      for (String col : cols) {
        Assert.assertFalse("Empty column not allowed in CoNLL-U (must be '_'): " + line, col.isEmpty());
      }

      String id = cols[0];
      if (id.contains("-")) {
        String[] parts = id.split("-");
        int start = Integer.parseInt(parts[0]);
        int end = Integer.parseInt(parts[1]);
        Assert.assertEquals("MWT start must equal next syntactic ID: " + line, expectedNextId, start);
        Assert.assertTrue("MWT end must exceed start: " + line, end > start);
        for (int c = 2; c <= 8; c++) {
          Assert.assertEquals("MWT columns 3..9 must be '_': " + line, "_", cols[c]);
        }
        mwtEnd = end;
      } else {
        int tokId = Integer.parseInt(id);
        Assert.assertEquals("Syntactic IDs must be strictly contiguous: " + line, expectedNextId, tokId);
        expectedNextId++;

        if (tokId <= mwtEnd) {
          Assert.assertEquals("Sub-token inside MWT must have MISC='_': " + line, "_", cols[9]);
        }

        // Verify FEATS keys are strictly sorted per CoNLL-U v2 specification
        String feats = cols[5];
        if (!"_".equals(feats)) {
          String[] kvs = feats.split("\\|");
          String prevKey = null;
          for (String kv : kvs) {
            int eq = kv.indexOf('=');
            Assert.assertTrue("Invalid FEATS entry: " + kv, eq > 0 && eq < kv.length() - 1);
            String key = kv.substring(0, eq);
            if (prevKey != null) {
              Assert.assertTrue(
                  "FEATS keys out of CoNLL-U v2 order (" + prevKey + " vs " + key + ") in: " + line,
                  UdFeatureMapper.CONLLU_FEAT_KEY_ORDER.compare(prevKey, key) < 0);
            }
            prevKey = key;
          }
        }

        // Verify MISC entries are well-formed Key=Value pairs without unescaped pipes
        String misc = cols[9];
        if (!"_".equals(misc)) {
          for (String mEntry : misc.split("\\|")) {
            Assert.assertTrue("Malformed MISC entry '" + mEntry + "' in: " + line,
                mEntry.indexOf('=') > 0);
          }
        }
      }
    }
  }
}
