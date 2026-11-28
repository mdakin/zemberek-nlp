# Turkish Dependency Parsing (`zemberek-dependency`)

`zemberek-dependency` provides zero-external-dependency, pure-Java Universal Dependencies v2 (`UD_Turkish-BOUN`) syntactic dependency parsing on top of Zemberek's morphological analyzer, disambiguator, and `ConlluFormatter`.

## Architecture (`ZDEP` v1)

```text
Input Sentence
  -> TurkishMorphology.analyzeAndDisambiguate
  -> ConlluFormatter.projectSentence (MWT splitting + LEMMA + UPOS + XPOS + FEATS)
  -> [LemmaEmb ; CharCNN ; UposEmb ; XposEmb ; MeanFeatEmb] -> Linear + LayerNorm + ReLU
  -> 2-layer Bidirectional GRU
  -> Deep Biaffine Arc Scorer (Dozat & Manning 2017) -> Exact Single-Root Chu-Liu/Edmonds MST
  -> Deep Biaffine Label Scorer on winning arcs
  -> DependencyTree (Java API + 10-column CoNLL-U v2)
```

## How It Works

1. **Morphology & UD v2 Splitting:** Zemberek first disambiguates the sentence and projects it into Universal Dependencies tokens via `ConlluFormatter`, splitting agglutinative multi-word tokens (e.g. `başlıyorlarmış -> başlıyorlar + mış`, `Evdeki -> Evde + ki`) and attaching `LEMMA`, `UPOS`, `XPOS`, and morphological `FEATS`.
2. **Compact Turkish Token Features:** Instead of a huge surface-word vocabulary, each token is encoded from five compact signals (~4.48M params total): lowercased lemma embeddings, a small character CNN (for suffixes, casing, and OOV words), `UPOS`/`XPOS` embeddings, and mean-pooled morphological `FEATS`.
3. **BiGRU + Deep Biaffine Scoring:** A 2-layer bidirectional GRU reads left-to-right and right-to-left sentence context. Following Dozat & Manning (2017), dedicated arc and label MLPs feed into biaffine scorers that score every candidate `(dependent, head)` edge and then predict the UD relation label on each selected arc.
4. **Exact Non-Projective Decoding:** Because Turkish word order is flexible and frequently non-projective, `ChuLiuEdmonds.decodeSingleRoot` computes the exact maximum spanning tree rooted at `<ROOT>`—guaranteeing a valid, cycle-free, single-root tree.
5. **Teacher–Student Distillation:** To get near-Transformer accuracy in a `4.21 MB` model, a `BERTurk` (`dbmdz/bert-base-turkish-cased`) Deep Biaffine teacher was trained on Zemberek-aligned `UD_Turkish-BOUN`, used to silver-label ~78k high-confidence sentences from diverse Turkish corpora, and distilled into the compact BiGRU student (`+4.36` UAS / `+5.42` LAS over supervised-only training).
6. **INT8 Storage & Fast Pure-Java CPU Inference:** Weights are stored in a gzipped per-row INT8 format (`ZDEP` v1, shrinking `15.95 MB` FP32 to `4.21 MB` with `99.5%` FP32 parity). At load time (`~85–110 ms`), compute matrices unpack once to `float[]` for C2-vectorized `Math.fma` dot products, using `ThreadLocal` scratch buffers for thread-safe, low-GC parsing.

## Benchmark (`UD_Turkish-BOUN` Test, 979 sents / 12,210 tokens)

| Model | Disk Size | Runtime | Test UAS | Test LAS | Root Acc | Exact Sent |
| :--- | :---: | :--- | :---: | :---: | :---: | :---: |
| Supervised Baseline (`medium`) | `2.70 MB` | Pure Java (0 deps) | `76.18%` | `67.84%` | `84.68%` | `18.28%` |
| **Bundled Default (`dep_boun_medium_int8`)** | **`4.21 MB`** | **Pure Java (0 deps)** | **`80.54%`** | **`73.26%`** | **`87.84%`** | **`23.39%`** |
| BERTurk Teacher (reference) | `445 MB` | PyTorch GPU | `82.96%` | `75.65%` | — | — |

### Runtime Speed & Latency (Single-Threaded CPU, Java 21)

Measured on real `UD_Turkish-BOUN` prose (`979` sentences, `~12.5` UD tokens/sentence average) using the bundled `dep_boun_medium_int8.bin.gz` model (`~85–110 ms` one-time load):

| Stage / Environment | Sentences / sec | Tokens / sec | Avg Latency / Sentence |
| :--- | :---: | :---: | :---: |
| Morphology + Disambiguation + UD Projection (`Xeon @ 2.30 GHz`) | `~5,390 sent/s` | `~68,140 tok/s` | `0.19 ms` |
| **End-to-End Dependency Parse** (`parser.parse(text)`, `Xeon @ 2.30 GHz`) | **`~45 sent/s`** | **`~570 tok/s`** | **`22.0 ms`** |
| **Neural Parser Throughput** (`Colab Xeon CPU`, `UD_Turkish-BOUN` test) | **`~56 sent/s`** | **`~695 tok/s`** | **`18.0 ms`** |

> **Notes:** Morphological analysis and CoNLL-U projection account for `< 1%` of end-to-end runtime; nearly all time is spent in the 2-layer BiGRU and biaffine matrix multiplications. Because model weights are immutable and scratch buffers are `ThreadLocal`, throughput scales linearly across threads when parsing sentences concurrently, and modern desktop/Apple Silicon CPUs typically run 2–3× faster per thread than a 2.30 GHz cloud Xeon.

## Usage

```java
TurkishMorphology morphology = TurkishMorphology.createWithDefaults();
TurkishDependencyParser parser = TurkishDependencyParser.createWithDefaults(morphology);
// Or load a custom external model:
// TurkishDependencyParser parser = TurkishDependencyParser.fromModelFile(morphology, Paths.get("/path/to/model.bin.gz"));

DependencyTree tree = parser.parse(
    "Küçük sarı kuş yavaşça penceremin kenarına kondu.");

for (DependencyNode node : tree.getNodes()) {
  System.out.printf("%d\t%s\t%s\t%s\t%d\t%s%n",
      node.id(), node.form(), node.lemma(), node.upos(), node.headId(), node.deprel());
}

// Direct 10-column CoNLL-U v2 output (with MWT lines & HEAD/DEPREL populated):
System.out.println(tree.toConllu());
```

## Training & Google Colab / Kaggle Pipeline

Reproducible PyTorch training, `BERTurk` / `ConvBERT-Turkish` teacher–student distillation, INT8 `ZDEP` export, and a ready-to-run Colab/Kaggle notebook live under [`experiment/scripts/dependency/`](../experiment/scripts/dependency/):

- `model_zdep.py` — PyTorch `ZdepBiaffineParser` matching `BiaffineDependencyModel.java` + `ZDEP` v1 exporter.
- `train_student.py` — Supervised & distilled student trainer (`compact`, `medium`, `large` presets + `--resume`).
- `train_teacher_distill.py` — `BERTurk` / `ConvBERT-Turkish` Deep Biaffine teacher trainer & confidence-filtered silver CoNLL-U generator.
- `Zemberek_Turkish_Dependency_Colab.ipynb` — Turnkey Colab / Kaggle notebook.

## References & Resources

### Parsing Architecture & Algorithms
- **Deep Biaffine Attention:** Timothy Dozat and Christopher D. Manning. 2017. *Deep Biaffine Attention for Neural Dependency Parsing*. In *Proceedings of ICLR 2017*. https://arxiv.org/abs/1611.01734
- **Non-Projective MST Dependency Parsing:** Ryan McDonald, Fernando Pereira, Kiril Ribarov, and Jan Hajič. 2005. *Non-projective Dependency Parsing using Spanning Tree Algorithms*. In *Proceedings of HLT/EMNLP 2005*, pp. 523–530. https://aclanthology.org/H05-1066/
- **Chu–Liu/Edmonds Arborescence Algorithm:**
  - Yoeng-Jin Chu and Tseng-Hong Liu. 1965. *On the Shortest Arborescence of a Directed Graph*. *Science Sinica*, 14:1396–1400.
  - Jack Edmonds. 1967. *Optimum Branchings*. *Journal of Research of the National Bureau of Standards*, 71B(4):233–240.
- **Gated Recurrent Units (GRU):** Kyunghyun Cho, Bart van Merriënboer, Caglar Gulcehre, Dzmitry Bahdanau, Fethi Bougares, Holger Schwenk, and Yoshua Bengio. 2014. *Learning Phrase Representations using RNN Encoder–Decoder for Statistical Machine Translation*. In *Proceedings of EMNLP 2014*. https://aclanthology.org/D14-1179/
- **Character-Level CNN Representations:** Xuezhe Ma and Eduard Hovy. 2016. *End-to-end Sequence Labeling via Bi-directional LSTM-CNNs-CRF*. In *Proceedings of ACL 2016*. https://aclanthology.org/P16-1101/
- **Knowledge Distillation for Compact Sequence & Parsing Models:**
  - Geoffrey Hinton, Oriol Vinyals, and Jeff Dean. 2015. *Distilling the Knowledge in a Neural Network*. https://arxiv.org/abs/1503.02531
  - Yoon Kim and Alexander M. Rush. 2016. *Sequence-Level Knowledge Distillation*. In *Proceedings of EMNLP 2016*. https://aclanthology.org/D16-1139/
  - Adhiguna Kuncoro, Miguel Ballesteros, Lingpeng Kong, Chris Dyer, and Noah A. Smith. 2016. *Distilling an Ensemble of Greedy Dependency Parsers into One MST Parser*. In *Proceedings of EMNLP 2016*. https://aclanthology.org/D16-1180/

### Turkish Datasets & Pretrained Models
- **Universal Dependencies v2:** Marie-Catherine de Marneffe, Christopher D. Manning, Joakim Nivre, and Daniel Zeman. 2021. *Universal Dependencies*. *Computational Linguistics*, 47(2):255–308. https://universaldependencies.org/
- **UD_Turkish-BOUN Treebank:** Utku Türk, Furkan Atmaca, Şaziye Betül Özateş, Gözde Berk, Seyyit Talha Bedir, Abdullatif Köksal, Balkız Öztürk Başaran, Tunga Güngör, and Arzucan Özgür. 2022. *Resources for Turkish Dependency Parsing: Introducing the BOUN Treebank and the BoAT Annotation Tool*. *Language Resources and Evaluation*, 56:259–307. https://github.com/UniversalDependencies/UD_Turkish-BOUN
- **BERTurk (Distillation Teacher):** Stefan Schweter. 2020. *BERTurk — BERT models for Turkish* (`dbmdz/bert-base-turkish-cased`). Zenodo. https://huggingface.co/dbmdz/bert-base-turkish-cased
- **Unlabeled Turkish Distillation Texts:** Turkish Wikipedia (`wikimedia/wikipedia`), Multilingual C4 (`allenai/c4` / `mc4`), and raw sentence texts from public Turkish Universal Dependencies treebanks (`UD_Turkish-IMST`, `UD_Turkish-Kenet`, `UD_Turkish-Penn`, `UD_Turkish-Atis`, `UD_Turkish-Tourism`, `UD_Turkish-FrameNet`).
