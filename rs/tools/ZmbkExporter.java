import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import zemberek.core.compression.LossyIntLookup;
import zemberek.core.data.CompressedWeights;
import zemberek.core.hash.MultiLevelMphf;
import zemberek.core.io.IOUtil;
import zemberek.core.text.TextIO;
import zemberek.core.turkish.PhoneticAttribute;
import zemberek.core.turkish.PrimaryPos;
import zemberek.core.turkish.RootAttribute;
import zemberek.core.turkish.SecondaryPos;
import zemberek.core.turkish.Turkish;
import zemberek.core.turkish.TurkishAlphabet;
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.ambiguity.fast.FastPerceptronAmbiguityResolver;
import zemberek.morphology.ambiguity.fast.FastPerceptronAmbiguityResolverTrainer;
import zemberek.morphology.analysis.AttributesHelper;
import zemberek.morphology.analysis.InformalAnalysisConverter;
import zemberek.morphology.analysis.SentenceAnalysis;
import zemberek.morphology.analysis.SentenceWordAnalysis;
import zemberek.morphology.analysis.SingleAnalysis;
import zemberek.morphology.analysis.StemTransitions;
import zemberek.morphology.analysis.SurfaceTransition;
import zemberek.morphology.analysis.SurfaceTransition.TemplateTokenType;
import zemberek.morphology.analysis.WordAnalysis;
import zemberek.morphology.analysis.WordAnalysisSurfaceFormatter;
import zemberek.morphology.generator.WordGenerator;
import zemberek.morphology.lexicon.DictionaryItem;
import zemberek.morphology.lexicon.RootLexicon;
import zemberek.morphology.lexicon.tr.TurkishDictionaryLoader;
import zemberek.morphology.morphotactics.AttributeSet;
import zemberek.morphology.morphotactics.CombinedCondition;
import zemberek.morphology.morphotactics.Condition;
import zemberek.morphology.morphotactics.InformalTurkishMorphotactics;
import zemberek.morphology.morphotactics.Morpheme;
import zemberek.morphology.morphotactics.MorphemeState;
import zemberek.morphology.morphotactics.MorphemeTransition;
import zemberek.morphology.morphotactics.StemTransition;
import zemberek.morphology.morphotactics.SuffixTransition;
import zemberek.morphology.morphotactics.TurkishMorphotactics;
import zemberek.normalization.TurkishSpellChecker;
import zemberek.normalization.deasciifier.latticedeasciifier.CharacterHarmonyModel;
import zemberek.normalization.deasciifier.latticedeasciifier.DeasciifierModel;
import zemberek.normalization.deasciifier.latticedeasciifier.LatticeDeasciifier;
import zemberek.normalization.deasciifier.latticedeasciifier.LexiconPriors;
import zemberek.normalization.deasciifier.latticedeasciifier.QuantizedWeightTable;
import zemberek.normalization.deasciifier.latticedeasciifier.TurkishTextCodec;
import zemberek.normalization.v2.CompactStemEndingSpellChecker;
import zemberek.normalization.v2.NormV2Types;
import zemberek.normalization.v2.NormalizationKnowledge;
import zemberek.normalization.v2.SpanLatticePerceptronNormalizer;
import zemberek.tokenization.TurkishSentenceExtractor;
import zemberek.tokenization.TurkishTokenizer;

/**
 * Offline compiler exporting Zemberek Java resources/models into 64-byte-aligned little-endian
 * {@code .zmbk} packfiles and generating binary parity fixtures for automated Rust verification.
 */
public class ZmbkExporter {

  private static final int ZMBK_VERSION = 1;
  private static final short ZMBK_ENDIAN_TAG = 0x0102;
  private static final int ALIGNMENT = 64;

  private static final int SECT_LEXICON = 1;
  private static final int SECT_STEM_TRIE = 3;
  private static final int SECT_ENTRY_BRIDGE = 4;
  private static final int SECT_MORPH_FST = 5;
  private static final int SECT_AMBIG_QWT = 6;
  private static final int SECT_CLEAN_LM = 7;
  private static final int SECT_ENDING_TRIE = 8;
  private static final int SECT_DEASC = 9;
  private static final int SECT_NORM_KNOWLEDGE = 10;
  private static final int SECT_NORM_PERCEPTRON = 11;

  // RPN condition bytecode opcodes matching `zemberek_pack::morph_fst::cond_op`
  private static final int OP_TRUE = 0;
  private static final int OP_NOT = 1;
  private static final int OP_AND = 2;
  private static final int OP_OR = 3;
  private static final int OP_HAS_ROOT_ATTR = 4;
  private static final int OP_HAS_ANY_ROOT_ATTR = 5;
  private static final int OP_SEC_POS_IS = 6;
  private static final int OP_ROOT_PRIMARY_POS_IS = 7;
  private static final int OP_DICT_ITEM_IN_SET = 8;
  private static final int OP_ROOT_SURF_IN_MASK = 9;
  private static final int OP_HAS_TAIL = 10;
  private static final int OP_HAS_ANY_SUFFIX_SURFACE = 11;
  private static final int OP_HAS_DERIVATION = 12;
  private static final int OP_NO_SURFACE_AFTER_DERIVATION = 13;
  private static final int OP_PREV_MORPHEME_IN_SET = 14;
  private static final int OP_PREV_STATE_IN_SET = 15;
  private static final int OP_LAST_DERIV_IN_SET = 16;
  private static final int OP_CUR_GROUP_CONTAINS_ANY = 17;
  private static final int OP_PREV_GROUP_CONTAINS_STATE = 18;
  private static final int OP_PREV_GROUP_CONTAINS_MORPH = 19;
  private static final int OP_CONTAINS_MORPHEME = 20;
  private static final int OP_HAS_TAIL_SEQ = 21;
  private static final int OP_CONTAINS_MORPH_SEQ = 22;

  private record SectionData(int kind, int flags, int elemCount, byte[] payload) {}

  private static int alignUp(int val, int align) {
    return (val + align - 1) & ~(align - 1);
  }

  private static int crc32Ieee(byte[] data) {
    CRC32 crc = new CRC32();
    crc.update(data);
    return (int) crc.getValue();
  }

  private static long fnv1a64Update(long hash, String s) {
    byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
    for (byte b : bytes) {
      hash ^= (b & 0xFFL);
      hash *= 0x100000001b3L;
    }
    hash ^= 0xFFL;
    hash *= 0x100000001b3L;
    return hash;
  }

  private static byte[] buildZmbkPack(int packFlags, List<SectionData> sections) {
    int dirEnd = 64 + sections.size() * 32;
    int cursor = alignUp(dirEnd, ALIGNMENT);
    int[] offsets = new int[sections.size()];
    for (int i = 0; i < sections.size(); i++) {
      offsets[i] = cursor;
      cursor = alignUp(cursor + sections.get(i).payload().length, ALIGNMENT);
    }
    int totalBytes = cursor;
    ByteBuffer buf = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN);
    buf.put((byte) 'Z').put((byte) 'M').put((byte) 'B').put((byte) 'K');
    buf.putShort((short) ZMBK_VERSION);
    buf.putShort(ZMBK_ENDIAN_TAG);
    buf.putInt(sections.size());
    buf.putInt(totalBytes);
    buf.putInt(packFlags);
    buf.position(64);

    for (int i = 0; i < sections.size(); i++) {
      SectionData s = sections.get(i);
      buf.putInt(s.kind());
      buf.putInt(s.flags());
      buf.putLong(offsets[i]);
      buf.putLong(s.payload().length);
      buf.putInt(s.elemCount());
      buf.putInt(crc32Ieee(s.payload()));
    }

    for (int i = 0; i < sections.size(); i++) {
      buf.position(offsets[i]);
      buf.put(sections.get(i).payload());
    }
    return buf.array();
  }

  private static int phonoBits(AttributeSet<PhoneticAttribute> s) {
    int b = 0;
    for (PhoneticAttribute a : PhoneticAttribute.values()) {
      if (s.contains(a)) {
        b |= (1 << a.ordinal());
      }
    }
    return b;
  }

  private static AttributeSet<PhoneticAttribute> phonoSetFromBits(int bits) {
    AttributeSet<PhoneticAttribute> s = AttributeSet.emptySet();
    for (PhoneticAttribute a : PhoneticAttribute.values()) {
      if ((bits & (1 << a.ordinal())) != 0) {
        s.add(a);
      }
    }
    return s;
  }

  private static int rootAttrBits(Set<RootAttribute> attrs) {
    int b = 0;
    for (RootAttribute a : attrs) {
      b |= (1 << a.ordinal());
    }
    return b;
  }

  private static int rootSurfaceId(String surf) {
    return switch (surf) {
      case "de" -> 1;
      case "di" -> 2;
      case "ye" -> 3;
      case "yi" -> 4;
      default -> 0;
    };
  }

  private static int checkedRootSurfaceId(String surf) {
    int id = rootSurfaceId(surf);
    if (id == 0) {
      throw new IllegalStateException("Unexpected RootSurfaceIs surface: " + surf);
    }
    return id;
  }

  private static Object getField(Object obj, String fieldName) throws Exception {
    Class<?> cls = obj.getClass();
    while (cls != null) {
      try {
        Field f = cls.getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.get(obj);
      } catch (NoSuchFieldException ignored) {
        cls = cls.getSuperclass();
      }
    }
    throw new NoSuchFieldException(fieldName + " on " + obj.getClass().getName());
  }

  @SuppressWarnings("unchecked")
  private static void collectConditionMetadata(
      Condition c, Set<String> specialItems, Set<Morpheme> morphemes) throws Exception {
    if (c == null) {
      return;
    }
    if (c instanceof CombinedCondition cc) {
      for (Condition sub : (List<Condition>) getField(cc, "conditions")) {
        collectConditionMetadata(sub, specialItems, morphemes);
      }
      return;
    }
    String name = c.getClass().getSimpleName();
    switch (name) {
      case "NotCondition" ->
          collectConditionMetadata((Condition) getField(c, "condition"), specialItems, morphemes);
      case "DictionaryItemIs" -> {
        DictionaryItem it = (DictionaryItem) getField(c, "item");
        if (it != null) {
          specialItems.add(it.id);
        }
      }
      case "DictionaryItemIsAny", "DictionaryItemIsNone" -> {
        for (Object o : (Set<?>) getField(c, "items")) {
          if (o instanceof DictionaryItem it) {
            specialItems.add(it.id);
          }
        }
      }
      case "CurrentMorphemeIs", "PreviousMorphemeIs" ->
          morphemes.add((Morpheme) getField(c, "morpheme"));
      case "CurrentMorphemeIsAny",
          "PreviousMorphemeIsAny",
          "PreviousGroupContainsMorpheme",
          "ContainsMorpheme" -> {
        for (Object o : (Set<?>) getField(c, "morphemes")) {
          morphemes.add((Morpheme) o);
        }
      }
      case "HasTailSequence", "ContainsMorphemeSequence" -> {
        for (Morpheme m : (Morpheme[]) getField(c, "morphemes")) {
          morphemes.add(m);
        }
      }
      default -> {}
    }
  }

  private record MatKey(int absStateId, int phonoBits) implements Comparable<MatKey> {
    @Override
    public int compareTo(MatKey o) {
      int c = Integer.compare(this.absStateId, o.absStateId);
      return c != 0 ? c : Integer.compareUnsigned(this.phonoBits, o.phonoBits);
    }
  }

  /** Folded condition AST node before emitting RPN instructions. */
  private sealed interface FoldedCond {
    record Const(boolean val) implements FoldedCond {}

    record Rpn(List<Integer> insts) implements FoldedCond {}
  }

  /** Compiler & deduplicator for condition RPN programs, bitsets, and morpheme sequences. */
  private static final class ConditionCompiler {
    private final Map<String, Integer> specialRootIdMap;
    private final Map<String, Integer> abstractStateIdMap;
    private final Map<Morpheme, Integer> morphemeIdMap;

    final List<Long> setWords = new ArrayList<>();
    private final Map<List<Long>, Integer> setCache = new HashMap<>();

    final ByteArrayOutputStream seqBytes = new ByteArrayOutputStream();
    private final Map<List<Integer>, Integer> seqCache = new HashMap<>();

    final List<List<Integer>> programs = new ArrayList<>();
    private final Map<List<Integer>, Integer> progCache = new HashMap<>();

    ConditionCompiler(
        Map<String, Integer> specialRootIdMap,
        Map<String, Integer> abstractStateIdMap,
        Map<Morpheme, Integer> morphemeIdMap) {
      this.specialRootIdMap = specialRootIdMap;
      this.abstractStateIdMap = abstractStateIdMap;
      this.morphemeIdMap = morphemeIdMap;
      // Program 0 is unconditional `true` (empty slice)
      programs.add(List.of());
      progCache.put(List.of(), 0);
    }

    private static int inst(int op, int arg) {
      if (arg < 0 || arg > 0x00FF_FFFF) {
        throw new IllegalStateException("RPN arg overflow: op=" + op + " arg=" + arg);
      }
      return ((arg & 0x00FF_FFFF) << 8) | (op & 0xFF);
    }

    private int internSet(long... words) {
      List<Long> key = new ArrayList<>(words.length);
      for (long w : words) {
        key.add(w);
      }
      Integer existing = setCache.get(key);
      if (existing != null) {
        return existing;
      }
      int base = setWords.size();
      setWords.addAll(key);
      setCache.put(key, base);
      return base;
    }

    private int internMorphemeSet(Collection<Morpheme> ms) {
      long[] words = new long[2]; // up to 128 morphemes
      for (Morpheme m : ms) {
        int id = Objects.requireNonNull(morphemeIdMap.get(m), "Unknown morpheme: " + m);
        if (id >= 128) {
          throw new IllegalStateException("Morpheme ID >= 128: " + id);
        }
        words[id >>> 6] |= (1L << (id & 63));
      }
      return internSet(words);
    }

    private int internStateSet(Collection<MorphemeState> states) {
      long[] words = new long[5]; // up to 320 abstract states
      for (MorphemeState s : states) {
        Integer id = abstractStateIdMap.get(s.id);
        if (id == null) {
          // Unreachable state in condition set: safely ignore since no path can visit it
          continue;
        }
        if (id >= 320) {
          throw new IllegalStateException("AbstractState ID >= 320: " + id);
        }
        words[id >>> 6] |= (1L << (id & 63));
      }
      return internSet(words);
    }

    private int internMorphemeSeq(Morpheme[] seq) {
      List<Integer> key = new ArrayList<>(seq.length + 1);
      key.add(seq.length);
      for (Morpheme m : seq) {
        key.add(Objects.requireNonNull(morphemeIdMap.get(m)));
      }
      Integer existing = seqCache.get(key);
      if (existing != null) {
        return existing;
      }
      int off = seqBytes.size();
      for (int b : key) {
        seqBytes.write(b & 0xFF);
      }
      seqCache.put(key, off);
      return off;
    }

    /**
     * Returns `-1` if condition constant-folds to `FALSE` (dead arc!), `0` if unconditional `TRUE`,
     * or `1..255` for a deduplicated RPN program index.
     */
    int compile(
        Condition cond, MorphemeState curState, AttributeSet<PhoneticAttribute> curPhonoSet)
        throws Exception {
      FoldedCond fc = fold(cond, curState, curPhonoSet);
      if (fc instanceof FoldedCond.Const c) {
        return c.val() ? 0 : -1;
      }
      List<Integer> rpn = ((FoldedCond.Rpn) fc).insts();
      verifyStackDepth(rpn);
      Integer idx = progCache.get(rpn);
      if (idx != null) {
        return idx;
      }
      int newIdx = programs.size();
      if (newIdx > 255) {
        throw new IllegalStateException("cond_prog_idx exceeded 8-bit limit (255): " + newIdx);
      }
      List<Integer> copy = List.copyOf(rpn);
      programs.add(copy);
      progCache.put(copy, newIdx);
      return newIdx;
    }

    private static void verifyStackDepth(List<Integer> rpn) {
      int sp = 0;
      for (int ins : rpn) {
        int op = ins & 0xFF;
        if (op == OP_NOT) {
          if (sp < 1) {
            throw new IllegalStateException("RPN stack underflow on NOT");
          }
        } else if (op == OP_AND || op == OP_OR) {
          if (sp < 2) {
            throw new IllegalStateException("RPN stack underflow on binary op");
          }
          sp--;
        } else {
          sp++;
          if (sp > 16) {
            throw new IllegalStateException("RPN stack overflow (>16): " + sp);
          }
        }
      }
      if (sp != 1) {
        throw new IllegalStateException("RPN final stack size != 1: " + sp);
      }
    }

    @SuppressWarnings("unchecked")
    private FoldedCond fold(
        Condition c, MorphemeState curState, AttributeSet<PhoneticAttribute> curPhonoSet)
        throws Exception {
      if (c == null) {
        return new FoldedCond.Const(true);
      }
      if (c instanceof CombinedCondition cc) {
        boolean isAnd = String.valueOf(getField(cc, "operator")).equals("AND");
        List<Condition> subs = (List<Condition>) getField(cc, "conditions");
        List<List<Integer>> kept = new ArrayList<>(subs.size());
        for (Condition sub : subs) {
          FoldedCond f = fold(sub, curState, curPhonoSet);
          if (f instanceof FoldedCond.Const cst) {
            if (isAnd && !cst.val()) {
              return new FoldedCond.Const(false);
            }
            if (!isAnd && cst.val()) {
              return new FoldedCond.Const(true);
            }
          } else {
            kept.add(((FoldedCond.Rpn) f).insts());
          }
        }
        if (kept.isEmpty()) {
          return new FoldedCond.Const(isAnd);
        }
        List<Integer> out = new ArrayList<>(kept.get(0));
        int combineOp = isAnd ? OP_AND : OP_OR;
        for (int i = 1; i < kept.size(); i++) {
          out.addAll(kept.get(i));
          out.add(inst(combineOp, 0));
        }
        return new FoldedCond.Rpn(out);
      }

      String name = c.getClass().getSimpleName();
      return switch (name) {
        case "HasPhoneticAttribute" -> {
          PhoneticAttribute pa = (PhoneticAttribute) getField(c, "attribute");
          yield new FoldedCond.Const(curPhonoSet.contains(pa));
        }
        case "CurrentStateIs" -> {
          MorphemeState st = (MorphemeState) getField(c, "state");
          yield new FoldedCond.Const(curState.equals(st));
        }
        case "CurrentStateIsNot" -> {
          MorphemeState st = (MorphemeState) getField(c, "state");
          yield new FoldedCond.Const(!curState.equals(st));
        }
        case "CurrentMorphemeIs" -> {
          Morpheme m = (Morpheme) getField(c, "morpheme");
          yield new FoldedCond.Const(curState.morpheme.equals(m));
        }
        case "CurrentMorphemeIsAny" -> {
          Set<Morpheme> ms = (Set<Morpheme>) getField(c, "morphemes");
          yield new FoldedCond.Const(ms.contains(curState.morpheme));
        }
        case "NotCondition" -> {
          FoldedCond inner = fold((Condition) getField(c, "condition"), curState, curPhonoSet);
          if (inner instanceof FoldedCond.Const cst) {
            yield new FoldedCond.Const(!cst.val());
          }
          List<Integer> rpn = new ArrayList<>(((FoldedCond.Rpn) inner).insts());
          if (!rpn.isEmpty() && rpn.get(rpn.size() - 1) == inst(OP_NOT, 0)) {
            rpn.remove(rpn.size() - 1);
          } else {
            rpn.add(inst(OP_NOT, 0));
          }
          yield new FoldedCond.Rpn(rpn);
        }
        case "HasRootAttribute" -> {
          RootAttribute ra = (RootAttribute) getField(c, "attribute");
          yield new FoldedCond.Rpn(List.of(inst(OP_HAS_ROOT_ATTR, ra.ordinal())));
        }
        case "HasAnyRootAttribute" -> {
          RootAttribute[] ras = (RootAttribute[]) getField(c, "attributes");
          long mask = 0;
          for (RootAttribute ra : ras) {
            mask |= (1L << ra.ordinal());
          }
          yield new FoldedCond.Rpn(List.of(inst(OP_HAS_ANY_ROOT_ATTR, internSet(mask))));
        }
        case "SecondaryPosIs" -> {
          SecondaryPos sp = (SecondaryPos) getField(c, "pos");
          yield new FoldedCond.Rpn(List.of(inst(OP_SEC_POS_IS, sp.ordinal())));
        }
        case "RootPrimaryPosIs" -> {
          PrimaryPos pp = (PrimaryPos) getField(c, "pos");
          yield new FoldedCond.Rpn(List.of(inst(OP_ROOT_PRIMARY_POS_IS, pp.ordinal())));
        }
        case "DictionaryItemIs" -> {
          DictionaryItem it = (DictionaryItem) getField(c, "item");
          if (it == null) {
            yield new FoldedCond.Const(false);
          }
          int specId = Objects.requireNonNull(specialRootIdMap.get(it.id));
          yield new FoldedCond.Rpn(List.of(inst(OP_DICT_ITEM_IN_SET, internSet(1L << specId))));
        }
        case "DictionaryItemIsAny" -> {
          Set<DictionaryItem> set = (Set<DictionaryItem>) getField(c, "items");
          long mask = 0;
          for (DictionaryItem it : set) {
            if (it != null) {
              mask |= (1L << Objects.requireNonNull(specialRootIdMap.get(it.id)));
            }
          }
          yield new FoldedCond.Rpn(List.of(inst(OP_DICT_ITEM_IN_SET, internSet(mask))));
        }
        case "DictionaryItemIsNone" -> {
          Set<DictionaryItem> set = (Set<DictionaryItem>) getField(c, "items");
          long mask = 0;
          for (DictionaryItem it : set) {
            if (it != null) {
              mask |= (1L << Objects.requireNonNull(specialRootIdMap.get(it.id)));
            }
          }
          yield new FoldedCond.Rpn(
              List.of(inst(OP_DICT_ITEM_IN_SET, internSet(mask)), inst(OP_NOT, 0)));
        }
        case "RootSurfaceIs" -> {
          String s = (String) getField(c, "surface");
          int mask = 1 << checkedRootSurfaceId(s);
          yield new FoldedCond.Rpn(List.of(inst(OP_ROOT_SURF_IN_MASK, mask)));
        }
        case "RootSurfaceIsAny" -> {
          String[] ss = (String[]) getField(c, "surfaces");
          int mask = 0;
          for (String s : ss) {
            mask |= (1 << checkedRootSurfaceId(s));
          }
          yield new FoldedCond.Rpn(List.of(inst(OP_ROOT_SURF_IN_MASK, mask)));
        }
        case "HasTail" -> new FoldedCond.Rpn(List.of(inst(OP_HAS_TAIL, 0)));
        case "HasNoTail" -> new FoldedCond.Rpn(List.of(inst(OP_HAS_TAIL, 0), inst(OP_NOT, 0)));
        case "HasAnySuffixSurface" ->
            new FoldedCond.Rpn(List.of(inst(OP_HAS_ANY_SUFFIX_SURFACE, 0)));
        case "HasDerivation" -> new FoldedCond.Rpn(List.of(inst(OP_HAS_DERIVATION, 0)));
        case "NoSurfaceAfterDerivation" ->
            new FoldedCond.Rpn(List.of(inst(OP_NO_SURFACE_AFTER_DERIVATION, 0)));
        case "PreviousMorphemeIs" -> {
          Morpheme m = (Morpheme) getField(c, "morpheme");
          yield new FoldedCond.Rpn(
              List.of(inst(OP_PREV_MORPHEME_IN_SET, internMorphemeSet(List.of(m)))));
        }
        case "PreviousMorphemeIsAny" -> {
          Set<Morpheme> ms = (Set<Morpheme>) getField(c, "morphemes");
          yield new FoldedCond.Rpn(
              List.of(inst(OP_PREV_MORPHEME_IN_SET, internMorphemeSet(ms))));
        }
        case "PreviousStateIs" -> {
          MorphemeState st = (MorphemeState) getField(c, "state");
          yield new FoldedCond.Rpn(
              List.of(inst(OP_PREV_STATE_IN_SET, internStateSet(List.of(st)))));
        }
        case "PreviousStateIsNot" -> {
          MorphemeState st = (MorphemeState) getField(c, "state");
          yield new FoldedCond.Rpn(
              List.of(inst(OP_PREV_STATE_IN_SET, internStateSet(List.of(st))), inst(OP_NOT, 0)));
        }
        case "PreviousStateIsAny" -> {
          Set<MorphemeState> sts = (Set<MorphemeState>) getField(c, "states");
          yield new FoldedCond.Rpn(List.of(inst(OP_PREV_STATE_IN_SET, internStateSet(sts))));
        }
        case "LastDerivationIs" -> {
          MorphemeState st = (MorphemeState) getField(c, "state");
          yield new FoldedCond.Rpn(
              List.of(inst(OP_LAST_DERIV_IN_SET, internStateSet(List.of(st)))));
        }
        case "LastDerivationIsAny" -> {
          Set<MorphemeState> sts = (Set<MorphemeState>) getField(c, "states");
          yield new FoldedCond.Rpn(List.of(inst(OP_LAST_DERIV_IN_SET, internStateSet(sts))));
        }
        case "CurrentGroupContainsAny" -> {
          Set<MorphemeState> sts = (Set<MorphemeState>) getField(c, "states");
          yield new FoldedCond.Rpn(List.of(inst(OP_CUR_GROUP_CONTAINS_ANY, internStateSet(sts))));
        }
        case "PreviousGroupContains" -> {
          Set<MorphemeState> sts = (Set<MorphemeState>) getField(c, "states");
          yield new FoldedCond.Rpn(
              List.of(inst(OP_PREV_GROUP_CONTAINS_STATE, internStateSet(sts))));
        }
        case "PreviousGroupContainsMorpheme" -> {
          Set<Morpheme> ms = (Set<Morpheme>) getField(c, "morphemes");
          yield new FoldedCond.Rpn(
              List.of(inst(OP_PREV_GROUP_CONTAINS_MORPH, internMorphemeSet(ms))));
        }
        case "ContainsMorpheme" -> {
          Set<Morpheme> ms = (Set<Morpheme>) getField(c, "morphemes");
          yield new FoldedCond.Rpn(List.of(inst(OP_CONTAINS_MORPHEME, internMorphemeSet(ms))));
        }
        case "HasTailSequence" -> {
          Morpheme[] seq = (Morpheme[]) getField(c, "morphemes");
          yield new FoldedCond.Rpn(List.of(inst(OP_HAS_TAIL_SEQ, internMorphemeSeq(seq))));
        }
        case "ContainsMorphemeSequence" -> {
          Morpheme[] seq = (Morpheme[]) getField(c, "morphemes");
          yield new FoldedCond.Rpn(List.of(inst(OP_CONTAINS_MORPH_SEQ, internMorphemeSeq(seq))));
        }
        default -> throw new IllegalStateException("Unhandled Condition class: " + name);
      };
    }
  }

  /** Substring-sharing UTF-8 pool builder (shares prefixes and suffixes on char boundaries). */
  private static final class SharedUtf8Pool {
    private final ByteArrayOutputStream raw = new ByteArrayOutputStream(360_000);
    private final Map<String, Integer> offsets = new HashMap<>(200_000);

    byte[] build(Collection<String> allStrings) {
      List<String> sorted = new ArrayList<>(allStrings);
      sorted.sort(
          Comparator.comparingInt((String s) -> s.getBytes(StandardCharsets.UTF_8).length)
              .reversed()
              .thenComparing(Comparator.naturalOrder()));

      // Pass 1: filter out strings that are already exact substrings of longer strings
      Set<String> seenSubstrings = new HashSet<>(sorted.size() * 8);
      List<String> keepers = new ArrayList<>(sorted.size());
      for (String s : sorted) {
        if (s.isEmpty() || seenSubstrings.contains(s)) {
          continue;
        }
        keepers.add(s);
        int n = s.length();
        for (int i = 0; i < n; i++) {
          for (int j = i + 1; j <= n; j++) {
            seenSubstrings.add(s.substring(i, j));
          }
        }
      }

      // Pass 2: greedy prefix-tail overlap superstring packing (zero runtime cost in Rust!)
      @SuppressWarnings("unchecked")
      Map<String, ArrayDeque<Integer>>[] byPrefix = new HashMap[7];
      for (int k = 1; k <= 6; k++) {
        byPrefix[k] = new HashMap<>();
      }
      for (int idx = 0; idx < keepers.size(); idx++) {
        String s = keepers.get(idx);
        int maxK = Math.min(6, s.length());
        for (int k = 1; k <= maxK; k++) {
          byPrefix[k].computeIfAbsent(s.substring(0, k), key -> new ArrayDeque<>()).add(idx);
        }
      }

      boolean[] used = new boolean[keepers.size()];
      StringBuilder superStr = new StringBuilder(300_000);

      int nextUnused = 0;
      int placed = 0;
      while (placed < keepers.size()) {
        int chosenIdx = -1;
        int chosenOverlapChars = 0;
        int tailMax = Math.min(6, superStr.length());
        for (int k = tailMax; k >= 1; k--) {
          String suffix = superStr.substring(superStr.length() - k);
          ArrayDeque<Integer> bucket = byPrefix[k].get(suffix);
          if (bucket != null) {
            while (!bucket.isEmpty() && used[bucket.peekFirst()]) {
              bucket.pollFirst();
            }
            if (!bucket.isEmpty()) {
              chosenIdx = bucket.pollFirst();
              chosenOverlapChars = k;
              break;
            }
          }
        }
        if (chosenIdx < 0) {
          while (used[nextUnused]) {
            nextUnused++;
          }
          chosenIdx = nextUnused;
          chosenOverlapChars = 0;
        }

        used[chosenIdx] = true;
        placed++;
        String s = keepers.get(chosenIdx);
        superStr.append(s, chosenOverlapChars, s.length());
      }

      String poolStr = superStr.toString();
      byte[] poolUtf8 = poolStr.getBytes(StandardCharsets.UTF_8);
      raw.writeBytes(poolUtf8);

      int pLen = poolStr.length();
      int[] byteAtChar = new int[pLen + 1];
      int bPos = 0;
      for (int i = 0; i < pLen; i++) {
        byteAtChar[i] = bPos;
        char c = poolStr.charAt(i);
        if (Character.isHighSurrogate(c)
            && i + 1 < pLen
            && Character.isLowSurrogate(poolStr.charAt(i + 1))) {
          byteAtChar[i + 1] = bPos;
          i++;
          bPos += 4;
        } else {
          bPos += c < 0x80 ? 1 : (c < 0x800 ? 2 : 3);
        }
      }
      byteAtChar[pLen] = bPos;

      Set<String> needed = new HashSet<>(allStrings);
      offsets.put("", 0);
      for (int i = 0; i < pLen; i++) {
        int maxJ = Math.min(pLen, i + 32);
        for (int j = i + 1; j <= maxJ; j++) {
          String sub = poolStr.substring(i, j);
          if (needed.contains(sub)) {
            offsets.putIfAbsent(sub, byteAtChar[i]);
          }
        }
      }
      return raw.toByteArray();
    }

    int offsetOf(String s) {
      if (s.isEmpty()) {
        return 0;
      }
      Integer off = offsets.get(s);
      if (off == null) {
        throw new IllegalStateException("Missing string in SharedUtf8Pool: " + s);
      }
      return off;
    }

    int offLen(String s) {
      int off = offsetOf(s);
      int len = s.getBytes(StandardCharsets.UTF_8).length;
      if (off > 0x00FF_FFFF || len > 0xFF) {
        throw new IllegalStateException("Pool offset/len overflow for: " + s + " off=" + off);
      }
      return (off << 8) | len;
    }
  }

  /** Codepoint-boundary compressed Radix Trie builder. */
  private static final class RadixNodeBuilder {
    String edgeLabel = "";
    List<Integer> packedTargets = new ArrayList<>(0);
    TreeMap<Integer, RadixNodeBuilder> childrenByFirstCp = new TreeMap<>();
    List<RadixNodeBuilder> sortedChildren = List.of();
    int bfsIndex;
    int firstChildIdx;
  }

  private static int commonCodepointPrefixChars(String a, String b) {
    int lim = Math.min(a.length(), b.length());
    int i = 0;
    while (i < lim) {
      int cpA = a.codePointAt(i);
      int cpB = b.codePointAt(i);
      if (cpA != cpB) {
        break;
      }
      i += Character.charCount(cpA);
    }
    return i;
  }

  private static void insertRadix(RadixNodeBuilder node, String key, List<Integer> targets) {
    if (key.isEmpty()) {
      node.packedTargets.addAll(targets);
      return;
    }
    int firstCp = key.codePointAt(0);
    RadixNodeBuilder existing = node.childrenByFirstCp.get(firstCp);
    if (existing == null) {
      RadixNodeBuilder leaf = new RadixNodeBuilder();
      leaf.edgeLabel = key;
      leaf.packedTargets = new ArrayList<>(targets);
      node.childrenByFirstCp.put(firstCp, leaf);
      return;
    }
    int lcp = commonCodepointPrefixChars(existing.edgeLabel, key);
    if (lcp == existing.edgeLabel.length()) {
      insertRadix(existing, key.substring(lcp), targets);
      return;
    }
    RadixNodeBuilder mid = new RadixNodeBuilder();
    mid.edgeLabel = existing.edgeLabel.substring(0, lcp);
    existing.edgeLabel = existing.edgeLabel.substring(lcp);
    mid.childrenByFirstCp.put(existing.edgeLabel.codePointAt(0), existing);
    node.childrenByFirstCp.put(firstCp, mid);

    String rem = key.substring(lcp);
    if (rem.isEmpty()) {
      mid.packedTargets.addAll(targets);
    } else {
      RadixNodeBuilder newLeaf = new RadixNodeBuilder();
      newLeaf.edgeLabel = rem;
      newLeaf.packedTargets = new ArrayList<>(targets);
      mid.childrenByFirstCp.put(rem.codePointAt(0), newLeaf);
    }
  }

  private static MorphemeState getStateField(Object statesObj, String fieldName) throws Exception {
    Field f = statesObj.getClass().getDeclaredField(fieldName);
    f.setAccessible(true);
    return (MorphemeState) f.get(statesObj);
  }

  private static Map<String, Integer> buildFormalOutgoingCounts(RootLexicon lex, String[] rootSlots)
      throws Exception {
    TurkishMorphotactics formal = new TurkishMorphotactics(lex);
    Object fStates = getField(formal, "states");
    LinkedHashSet<MorphemeState> visited = new LinkedHashSet<>();
    for (String fn : rootSlots) {
      visited.add(getStateField(fStates, fn));
    }
    for (StemTransition st : formal.getStemTransitions().getTransitions()) {
      visited.add(st.to);
    }
    Queue<MorphemeState> q = new ArrayDeque<>(visited);
    while (!q.isEmpty()) {
      MorphemeState cur = q.poll();
      for (MorphemeTransition mt : cur.getOutgoing()) {
        if (visited.add(mt.to)) {
          q.add(mt.to);
        }
      }
    }
    Map<String, Integer> counts = new HashMap<>(visited.size() * 2);
    for (MorphemeState ms : visited) {
      counts.put(ms.id, ms.getOutgoing().size());
    }
    return counts;
  }

  public static void main(String[] args) throws Exception {
    Path repoRoot = Paths.get(args.length > 0 ? args[0] : ".");
    Path outDir = repoRoot.resolve("rs/testdata");
    Files.createDirectories(outDir);

    RootLexicon lex = RootLexicon.getDefault();
    InformalTurkishMorphotactics morph = new InformalTurkishMorphotactics(lex);
    StemTransitions stemTrans = morph.getStemTransitions();

    // 1. Assign deterministic item_id 0..68,443 in RootLexicon iteration order
    List<DictionaryItem> items = new ArrayList<>(lex.size());
    Map<String, Integer> itemIdByStrId = new HashMap<>(lex.size() * 2);
    for (DictionaryItem it : lex) {
      itemIdByStrId.put(it.id, items.size());
      items.add(it);
    }

    // 2. Discover all abstract MorphemeStates, Morphemes, & 41 special condition DictionaryItems
    Object morphStatesObj = getField(morph, "states");

    String[] rootSlotFieldNames = {
      "noun_S",
      "nounProper_S",
      "nounAbbrv_S",
      "nounNoSuffix_S",
      "nounCompoundRoot_S",
      "nounSuRoot_S",
      "nounLastVowelDropRoot_S",
      "adjectiveRoot_ST",
      "adjLastVowelDropRoot_S",
      "verbRoot_S",
      "verbRoot_VowelDrop_S",
      "verbLastVowelDropModRoot_S",
      "verbLastVowelDropUnmodRoot_S",
      "vImplicitRecipRoot_S",
      "vImplicitReflexRoot_S",
      "vDeYeRoot_S",
      "imekRoot_S",
      "nVerbDegil_S",
      "pronPers_S",
      "pronPers_Mod_S",
      "pronDemons_S",
      "pronQuant_S",
      "pronQuantModified_S",
      "pronQues_S",
      "pronReflex_S",
      "advRoot_ST",
      "advForVerbDeriv_ST",
      "conjRoot_ST",
      "questionRoot_S",
      "interjRoot_ST",
      "puncRoot_ST",
      "detRoot_ST",
      "postpRoot_ST",
      "numeralRoot_ST",
      "dupRoot_ST"
    };

    Map<String, Integer> formalOutgoingCounts = buildFormalOutgoingCounts(lex, rootSlotFieldNames);

    LinkedHashSet<MorphemeState> allAbstractStates = new LinkedHashSet<>();
    for (String fn : rootSlotFieldNames) {
      allAbstractStates.add(getStateField(morphStatesObj, fn));
    }
    for (StemTransition st : stemTrans.getTransitions()) {
      allAbstractStates.add(st.to);
    }

    Set<String> specialCondItemIds = new TreeSet<>();
    LinkedHashSet<Morpheme> discoveredMorphemes = new LinkedHashSet<>();
    discoveredMorphemes.add(Morpheme.UNKNOWN);
    discoveredMorphemes.addAll(TurkishMorphotactics.getAllMorphemes());

    Queue<MorphemeState> sq = new ArrayDeque<>(allAbstractStates);
    while (!sq.isEmpty()) {
      MorphemeState cur = sq.poll();
      discoveredMorphemes.add(cur.morpheme);
      if (cur.morpheme.mappedMorpheme != null) {
        discoveredMorphemes.add(cur.morpheme.mappedMorpheme);
      }
      for (MorphemeTransition mt : cur.getOutgoing()) {
        SuffixTransition sfx = (SuffixTransition) mt;
        collectConditionMetadata(sfx.getCondition(), specialCondItemIds, discoveredMorphemes);
        if (allAbstractStates.add(mt.to)) {
          sq.add(mt.to);
        }
      }
    }

    List<MorphemeState> absStateList = new ArrayList<>(allAbstractStates);
    Map<String, Integer> abstractStateIdMap = new LinkedHashMap<>();
    for (int i = 0; i < absStateList.size(); i++) {
      abstractStateIdMap.put(absStateList.get(i).id, i);
    }

    List<Morpheme> morphemeList = new ArrayList<>(discoveredMorphemes);
    Map<Morpheme, Integer> morphemeIdMap = new LinkedHashMap<>();
    for (int i = 0; i < morphemeList.size(); i++) {
      morphemeIdMap.put(morphemeList.get(i), i);
    }
    if (morphemeList.get(0) != Morpheme.UNKNOWN) {
      throw new IllegalStateException("Morpheme 0 must be Morpheme.UNKNOWN");
    }

    Map<String, Integer> specialRootIdMap = new HashMap<>();
    int specCounter = 1;
    for (String sid : specialCondItemIds) {
      if (specCounter >= 64) {
        throw new IllegalStateException("specialRootIdMap exceeded 63 items");
      }
      specialRootIdMap.put(sid, specCounter++);
    }

    // 3. Seed all static + dynamic + vowelless (abstractStateId, phonoBits) entry pairs
    TreeSet<MatKey> entryMatKeys = new TreeSet<>();
    for (StemTransition st : stemTrans.getTransitions()) {
      entryMatKeys.add(
          new MatKey(abstractStateIdMap.get(st.to.id), phonoBits(st.getPhoneticAttributes())));
    }
    // Seed vowelless PhonoMask::EMPTY (0) & LastLetterConsonant for emoji/emoticon/abbreviation OOVs
    int consonantOnlyBits = 1 << PhoneticAttribute.LastLetterConsonant.ordinal();
    int voicelessConsBits =
        consonantOnlyBits | (1 << PhoneticAttribute.LastLetterVoiceless.ordinal());
    int stopConsBits =
        voicelessConsBits | (1 << PhoneticAttribute.LastLetterVoicelessStop.ordinal());
    for (String fn : rootSlotFieldNames) {
      int absId = abstractStateIdMap.get(getStateField(morphStatesObj, fn).id);
      entryMatKeys.add(new MatKey(absId, 0));
      entryMatKeys.add(new MatKey(absId, consonantOnlyBits));
      entryMatKeys.add(new MatKey(absId, voicelessConsBits));
      entryMatKeys.add(new MatKey(absId, stopConsBits));
    }

    String[] sampleRoots = {
      "a", "e", "ı", "i", "o", "ö", "u", "ü",
      "ak", "ek", "ık", "ik", "ok", "ök", "uk", "ük",
      "at", "et", "ıt", "it", "ot", "öt", "ut", "üt",
      "ap", "ep", "ıp", "ip", "op", "öp", "up", "üp",
      "aç", "eç", "ıç", "iç", "oç", "öç", "uç", "üç",
      "aş", "eş", "ış", "iş", "oş", "öş", "uş", "üş",
      "al", "el", "ıl", "il", "ol", "öl", "ul", "ül",
      "ka", "ke", "kı", "ki", "ko", "kö", "ku", "kü",
      "bak", "bek", "bık", "bik", "bok", "bök", "buk", "bük",
      "kapak", "kepek", "kıpık", "kipik", "kopuk", "köpük", "kupuk", "küpük",
      "kankito", "kelebegi", "kutu", "kütü", "kato", "ketö", "kıta", "kite",
      "ank", "enk", "onk", "önk", "unk", "ünk", "psikolog", "monolog"
    };
    String[] posSpecs = {
      "Noun", "Noun, Prop", "Noun, Abbrv", "Adj", "Adv", "Conj", "Interj", "Det", "Postp", "Num",
      "Dup", "Ques"
    };
    List<String> synthLines = new ArrayList<>();
    for (String r : sampleRoots) {
      for (String ps : posSpecs) {
        synthLines.add(r + " [P:" + ps + "]");
        if (ps.startsWith("Noun") || ps.equals("Adj")) {
          synthLines.add(r + " [P:" + ps + "; A:Doubling]");
          synthLines.add(r + " [P:" + ps + "; A:InverseHarmony]");
        }
      }
      synthLines.add(r + "mak [P:Verb]");
      synthLines.add(r + "mek [P:Verb]");
    }
    RootLexicon synthLex = TurkishDictionaryLoader.load(synthLines);
    InformalTurkishMorphotactics synthMorph = new InformalTurkishMorphotactics(synthLex);
    for (StemTransition st : synthMorph.getStemTransitions().getTransitions()) {
      Integer absId = abstractStateIdMap.get(st.to.id);
      if (absId != null) {
        entryMatKeys.add(new MatKey(absId, phonoBits(st.getPhoneticAttributes())));
      }
    }

    int sortedEntryCount = entryMatKeys.size();
    if (sortedEntryCount > 4096) {
      throw new IllegalStateException("sortedEntryCount exceeded 12-bit stem target limit: " + sortedEntryCount);
    }

    // Assign mat_state_id 0..sortedEntryCount-1 to sorted entry keys FIRST!
    Map<MatKey, Integer> matStateMap = new LinkedHashMap<>();
    List<MatKey> matStatesList = new ArrayList<>(4600);
    for (MatKey mk : entryMatKeys) {
      matStateMap.put(mk, matStatesList.size());
      matStatesList.add(mk);
    }

    // 4. Materialize the entire (State x Phono) Byte-FST via BFS over InformalTurkishMorphotactics!
    ConditionCompiler condCompiler =
        new ConditionCompiler(specialRootIdMap, abstractStateIdMap, morphemeIdMap);
    List<String> fstSurfaces = new ArrayList<>(512);
    Map<String, Integer> fstSurfMap = new LinkedHashMap<>();
    fstSurfaces.add("");
    fstSurfMap.put("", 0);

    List<Integer> stateArcOffsets = new ArrayList<>(4600);
    List<Integer> packedArcs = new ArrayList<>(24_000);

    for (int mIdx = 0; mIdx < matStatesList.size(); mIdx++) {
      stateArcOffsets.add(packedArcs.size());
      MatKey curKey = matStatesList.get(mIdx);
      MorphemeState curState = absStateList.get(curKey.absStateId());
      AttributeSet<PhoneticAttribute> curPhonoSet = phonoSetFromBits(curKey.phonoBits());
      int formalCount = formalOutgoingCounts.getOrDefault(curState.id, 0);

      List<MorphemeTransition> outgoing = curState.getOutgoing();
      for (int edgeIdx = 0; edgeIdx < outgoing.size(); edgeIdx++) {
        SuffixTransition sfx = (SuffixTransition) outgoing.get(edgeIdx);
        boolean isInformalArc = edgeIdx >= formalCount;
        boolean hasSurfForm = sfx.hasSurfaceForm();

        int progIdx = condCompiler.compile(sfx.getCondition(), curState, curPhonoSet);
        if (progIdx < 0) {
          // Dead arc under `curKey` (`HasPhoneticAttribute` or `CurrentStateIs*` folded to FALSE)
          continue;
        }

        int surfId;
        int nextPhonoBits;
        if (!hasSurfForm) {
          surfId = 0;
          nextPhonoBits = curKey.phonoBits();
        } else {
          String surface;
          try {
            surface = SurfaceTransition.generateSurface(sfx, curPhonoSet);
          } catch (IllegalArgumentException vowellessEx) {
            // Vowelless OOV state (e.g. emoji/consonant-only abbrv) cannot generate vowel-harmony suffix
            continue;
          }
          Integer sid = fstSurfMap.get(surface);
          if (sid == null) {
            sid = fstSurfaces.size();
            if (sid >= 512) {
              throw new IllegalStateException("surf_id exceeded 9-bit limit (511): " + sid);
            }
            fstSurfaces.add(surface);
            fstSurfMap.put(surface, sid);
          }
          surfId = sid;

          AttributeSet<PhoneticAttribute> nextAttrs =
              AttributesHelper.getMorphemicAttributes(surface, curPhonoSet);
          nextAttrs.remove(PhoneticAttribute.CannotTerminate);
          TemplateTokenType lastType = sfx.getLastTemplateToken().getType();
          if (lastType == TemplateTokenType.LAST_VOICED) {
            nextAttrs.add(PhoneticAttribute.ExpectsConsonant);
          } else if (lastType == TemplateTokenType.LAST_NOT_VOICED) {
            nextAttrs.add(PhoneticAttribute.ExpectsVowel);
            nextAttrs.add(PhoneticAttribute.CannotTerminate);
          }
          nextPhonoBits = phonoBits(nextAttrs);
        }

        int toAbsId = abstractStateIdMap.get(sfx.to.id);
        MatKey targetKey = new MatKey(toAbsId, nextPhonoBits);
        Integer targetMatId = matStateMap.get(targetKey);
        if (targetMatId == null) {
          targetMatId = matStatesList.size();
          if (targetMatId >= 8192) {
            throw new IllegalStateException("target_mat_state exceeded 13-bit limit (8191): " + targetMatId);
          }
          matStateMap.put(targetKey, targetMatId);
          matStatesList.add(targetKey);
        }

        int arcWord =
            (targetMatId & 0x1FFF)
                | ((surfId & 0x01FF) << 13)
                | ((progIdx & 0xFF) << 22)
                | (isInformalArc ? (1 << 30) : 0)
                | (hasSurfForm ? (1 << 31) : 0);
        packedArcs.add(arcWord);
      }
    }
    stateArcOffsets.add(packedArcs.size());

    // 5. Collect all strings for SharedUtf8Pool and build Radix Trie + diff_items
    Set<String> allPoolStrings = new LinkedHashSet<>(80_000);
    int[] itemLemmaModes = new int[items.size()];
    List<int[]> explicitLemmaPairs = new ArrayList<>(1600);
    List<int[]> refPairs = new ArrayList<>(1024);

    for (int id = 0; id < items.size(); id++) {
      DictionaryItem it = items.get(id);
      String r = it.root;
      String l = it.lemma;
      allPoolStrings.add(r);
      int mode;
      if (l.equals(r)) {
        mode = 0;
      } else if (it.primaryPos == PrimaryPos.Verb && l.equals(r + "mak")) {
        mode = 1;
      } else if (it.primaryPos == PrimaryPos.Verb && l.equals(r + "mek")) {
        mode = 2;
      } else if (l.equals(Turkish.capitalize(r))) {
        mode = 3;
      } else if (l.equals(r.toUpperCase(Turkish.LOCALE))) {
        mode = 4;
      } else {
        mode = 5;
        allPoolStrings.add(l);
      }
      itemLemmaModes[id] = mode;
      if (it.getReferenceItem() != null) {
        Integer refId = itemIdByStrId.get(it.getReferenceItem().id);
        if (refId != null) {
          refPairs.add(new int[] {id, refId});
        }
      }
    }

    Map<String, List<Integer>> surfaceToTargets = new TreeMap<>();
    List<int[]> diffItems = new ArrayList<>(7000); // [item_id, start_and_count]
    List<int[]> diffTargetsRaw = new ArrayList<>(14_000); // [packedStemTarget, itemIdxInDiffSurf]
    List<String> diffTargetSurfs = new ArrayList<>(14_000);

    int maxStemCharLen = 0;
    for (int id = 0; id < items.size(); id++) {
      DictionaryItem it = items.get(id);
      List<StemTransition> trans = stemTrans.getTransitions(it);
      boolean isDiff =
          trans.size() > 1 || (trans.size() == 1 && !it.root.equals(trans.get(0).surface));
      int diffStart = diffTargetsRaw.size();

      for (StemTransition st : trans) {
        allPoolStrings.add(st.surface);
        maxStemCharLen = Math.max(maxStemCharLen, st.surface.length());
        int absId = abstractStateIdMap.get(st.to.id);
        int pBits = phonoBits(st.getPhoneticAttributes());
        int matId = Objects.requireNonNull(matStateMap.get(new MatKey(absId, pBits)));
        if (matId >= 4096) {
          throw new IllegalStateException("Entry matId >= 4096: " + matId);
        }
        int rSurfId = rootSurfaceId(st.surface);
        int packed = (id & 0x0001_FFFF) | ((matId & 0x0FFF) << 17) | ((rSurfId & 0x07) << 29);
        List<Integer> list = surfaceToTargets.computeIfAbsent(st.surface, k -> new ArrayList<>(1));
        if (!list.contains(packed)) {
          list.add(packed);
        }
        if (isDiff) {
          diffTargetsRaw.add(new int[] {packed, diffTargetSurfs.size()});
          diffTargetSurfs.add(st.surface);
        }
      }
      if (isDiff) {
        int cnt = diffTargetsRaw.size() - diffStart;
        diffItems.add(new int[] {id, ((cnt & 0xFF) << 24) | (diffStart & 0x00FF_FFFF)});
      }
    }

    RadixNodeBuilder rootBuilder = new RadixNodeBuilder();
    for (Map.Entry<String, List<Integer>> e : surfaceToTargets.entrySet()) {
      insertRadix(rootBuilder, e.getKey(), e.getValue());
    }

    List<RadixNodeBuilder> bfsNodes = new ArrayList<>(90_000);
    bfsNodes.add(rootBuilder);
    rootBuilder.bfsIndex = 0;
    for (int idx = 0; idx < bfsNodes.size(); idx++) {
      RadixNodeBuilder cur = bfsNodes.get(idx);
      List<RadixNodeBuilder> children = new ArrayList<>(cur.childrenByFirstCp.values());
      children.sort(
          (a, b) ->
              Arrays.compareUnsigned(
                  a.edgeLabel.getBytes(StandardCharsets.UTF_8),
                  b.edgeLabel.getBytes(StandardCharsets.UTF_8)));
      cur.sortedChildren = children;
      cur.firstChildIdx = children.isEmpty() ? 0 : bfsNodes.size();
      for (RadixNodeBuilder ch : children) {
        ch.bfsIndex = bfsNodes.size();
        bfsNodes.add(ch);
        allPoolStrings.add(ch.edgeLabel);
      }
    }

    SharedUtf8Pool pool = new SharedUtf8Pool();
    byte[] utf8PoolBytes = pool.build(allPoolStrings);

    for (int id = 0; id < items.size(); id++) {
      if (itemLemmaModes[id] == 5) {
        explicitLemmaPairs.add(new int[] {id, pool.offLen(items.get(id).lemma)});
      }
    }

    // 6. Serialize SECT_LEXICON
    int lexBytesLen =
        16
            + items.size() * 12
            + explicitLemmaPairs.size() * 8
            + refPairs.size() * 8
            + utf8PoolBytes.length;
    ByteBuffer lexBuf = ByteBuffer.allocate(lexBytesLen).order(ByteOrder.LITTLE_ENDIAN);
    lexBuf.putInt(items.size());
    lexBuf.putInt(utf8PoolBytes.length);
    lexBuf.putInt(explicitLemmaPairs.size());
    lexBuf.putInt(refPairs.size());

    for (int id = 0; id < items.size(); id++) {
      DictionaryItem it = items.get(id);
      lexBuf.putInt(pool.offLen(it.root));
      lexBuf.putInt(rootAttrBits(it.attributes));
      lexBuf.put((byte) it.primaryPos.ordinal());
      lexBuf.put((byte) it.secondaryPos.ordinal());
      lexBuf.put((byte) specialRootIdMap.getOrDefault(it.id, 0).intValue());
      int flags = (it.index & 0x03) | ((itemLemmaModes[id] & 0x07) << 2);
      if (it.getReferenceItem() != null) {
        flags |= 0x80;
      }
      lexBuf.put((byte) flags);
    }
    for (int[] ep : explicitLemmaPairs) {
      lexBuf.putInt(ep[0]);
      lexBuf.putInt(ep[1]);
    }
    for (int[] rp : refPairs) {
      lexBuf.putInt(rp[0]);
      lexBuf.putInt(rp[1]);
    }
    lexBuf.put(utf8PoolBytes);

    // 7. Serialize SECT_STEM_TRIE
    int nodeCount = bfsNodes.size();
    int bvU64Count = (nodeCount + 63) / 64;
    long[] termBits = new long[bvU64Count];
    int[] termRankBase = new int[bvU64Count];
    List<Integer> termTargets = new ArrayList<>(surfaceToTargets.size());
    List<Integer> overflowTargets = new ArrayList<>(12_000);

    for (int i = 0; i < nodeCount; i++) {
      RadixNodeBuilder n = bfsNodes.get(i);
      if (!n.packedTargets.isEmpty()) {
        termBits[i >>> 6] |= (1L << (i & 63));
        if (n.packedTargets.size() == 1) {
          termTargets.add(n.packedTargets.get(0));
        } else {
          int ovfOff = overflowTargets.size();
          int cnt = n.packedTargets.size();
          if (cnt > 31 || ovfOff > 0x00FF_FFFF) {
            throw new IllegalStateException("Overflow target bounds exceeded: cnt=" + cnt);
          }
          overflowTargets.addAll(n.packedTargets);
          int ptr = (7 << 29) | ((cnt & 0x1F) << 24) | (ovfOff & 0x00FF_FFFF);
          termTargets.add(ptr);
        }
      }
    }
    int runningPop = 0;
    for (int w = 0; w < bvU64Count; w++) {
      termRankBase[w] = runningPop;
      runningPop += Long.bitCount(termBits[w]);
    }
    if (runningPop != termTargets.size()) {
      throw new IllegalStateException("Rank mismatch");
    }

    int nodesBytesPadded = (nodeCount * 6 + 7) & ~7;
    int trieBytesLen =
        24
            + nodesBytesPadded
            + bvU64Count * 8
            + bvU64Count * 4
            + termTargets.size() * 4
            + overflowTargets.size() * 4;
    ByteBuffer trieBuf = ByteBuffer.allocate(trieBytesLen).order(ByteOrder.LITTLE_ENDIAN);
    trieBuf.putInt(nodeCount);
    trieBuf.putInt(bvU64Count);
    trieBuf.putInt(termTargets.size());
    trieBuf.putInt(overflowTargets.size());
    trieBuf.putInt(maxStemCharLen);
    trieBuf.putInt(bfsNodes.get(0).sortedChildren.size());

    for (int i = 0; i < nodeCount; i++) {
      RadixNodeBuilder n = bfsNodes.get(i);
      byte[] lblBytes = n.edgeLabel.getBytes(StandardCharsets.UTF_8);
      int lblOff = i == 0 ? 0 : pool.offsetOf(n.edgeLabel);
      int lblLen = lblBytes.length;
      int childCount = i == 0 ? 0 : n.sortedChildren.size();
      if (lblOff > 0x0007_FFFF || lblLen > 31 || childCount > 63 || n.firstChildIdx > 0x0001_FFFF) {
        throw new IllegalStateException(
            "RadixNode 48-bit overflow at node " + i + ": off=" + lblOff + " len=" + lblLen + " cc=" + childCount);
      }
      long bits48 =
          (lblOff & 0x0007_FFFFL)
              | ((lblLen & 0x1FL) << 19)
              | (n.packedTargets.isEmpty() ? 0L : (1L << 24))
              | ((n.firstChildIdx & 0x0001_FFFFL) << 25)
              | ((childCount & 0x3FL) << 42);
      trieBuf.putShort((short) (bits48 & 0xFFFF));
      trieBuf.putShort((short) ((bits48 >>> 16) & 0xFFFF));
      trieBuf.putShort((short) ((bits48 >>> 32) & 0xFFFF));
    }
    trieBuf.position(24 + nodesBytesPadded);
    for (long bits : termBits) {
      trieBuf.putLong(bits);
    }
    for (int rb : termRankBase) {
      trieBuf.putInt(rb);
    }
    for (int tt : termTargets) {
      trieBuf.putInt(tt);
    }
    for (int ot : overflowTargets) {
      trieBuf.putInt(ot);
    }

    // 8. Serialize SECT_ENTRY_BRIDGE (all `matStatesList` states; first `sortedEntryCount` are sorted entries!)
    int matCount = matStatesList.size();
    int bridgeBytesLen = 80 + matCount * 8 + sortedEntryCount * 2;
    ByteBuffer bridgeBuf = ByteBuffer.allocate(bridgeBytesLen).order(ByteOrder.LITTLE_ENDIAN);
    bridgeBuf.putInt(matCount);
    bridgeBuf.putInt(sortedEntryCount);
    for (int slot = 0; slot < 36; slot++) {
      if (slot < rootSlotFieldNames.length) {
        MorphemeState ms = getStateField(morphStatesObj, rootSlotFieldNames[slot]);
        bridgeBuf.putShort((short) abstractStateIdMap.get(ms.id).intValue());
      } else {
        bridgeBuf.putShort((short) 0);
      }
    }
    for (MatKey mk : matStatesList) {
      bridgeBuf.putShort((short) mk.absStateId());
      bridgeBuf.putShort((short) 0);
      bridgeBuf.putInt(mk.phonoBits());
    }
    for (int i = 0; i < sortedEntryCount; i++) {
      bridgeBuf.putShort((short) i);
    }

    // 9. Serialize SECT_MORPH_FST (`kind = 5`)
    ByteArrayOutputStream fstPoolBaos = new ByteArrayOutputStream(4096);
    Map<String, Integer> fstPoolOffLens = new HashMap<>();
    fstPoolOffLens.put("", 0);
    java.util.function.Function<String, Integer> addFstStr =
        s -> {
          if (s.isEmpty()) {
            return 0;
          }
          Integer ex = fstPoolOffLens.get(s);
          if (ex != null) {
            return ex;
          }
          byte[] b = s.getBytes(StandardCharsets.UTF_8);
          int off = fstPoolBaos.size();
          fstPoolBaos.writeBytes(b);
          int ol = (off << 8) | (b.length & 0xFF);
          fstPoolOffLens.put(s, ol);
          return ol;
        };

    int[] surfOffLens = new int[fstSurfaces.size()];
    for (int i = 0; i < fstSurfaces.size(); i++) {
      surfOffLens[i] = addFstStr.apply(fstSurfaces.get(i));
    }
    int[] morphIdOffLens = new int[morphemeList.size()];
    for (int i = 0; i < morphemeList.size(); i++) {
      morphIdOffLens[i] = addFstStr.apply(morphemeList.get(i).id);
    }
    byte[] fstStrPoolBytes = fstPoolBaos.toByteArray();
    byte[] seqBytesArr = condCompiler.seqBytes.toByteArray();

    List<Integer> condProgOffsets = new ArrayList<>(condCompiler.programs.size() + 1);
    List<Integer> condInsts = new ArrayList<>(512);
    for (List<Integer> prog : condCompiler.programs) {
      condProgOffsets.add(condInsts.size());
      condInsts.addAll(prog);
    }
    condProgOffsets.add(condInsts.size());

    int nomMorphId = morphemeIdMap.get(TurkishMorphotactics.nom);
    int pnonMorphId = morphemeIdMap.get(TurkishMorphotactics.pnon);
    int p3sgMorphId = morphemeIdMap.get(TurkishMorphotactics.p3sg);

    int fstBytesLen =
        56
            + condCompiler.setWords.size() * 8
            + stateArcOffsets.size() * 4
            + packedArcs.size() * 4
            + condProgOffsets.size() * 4
            + condInsts.size() * 4
            + surfOffLens.length * 4
            + morphemeList.size() * 8
            + absStateList.size() * 4
            + diffItems.size() * 8
            + diffTargetsRaw.size() * 8
            + seqBytesArr.length
            + fstStrPoolBytes.length;

    if (fstBytesLen > 280 * 1024) {
      throw new IllegalStateException("SECT_MORPH_FST exceeded 280 KiB gate: " + fstBytesLen + " B");
    }

    ByteBuffer fstBuf = ByteBuffer.allocate(fstBytesLen).order(ByteOrder.LITTLE_ENDIAN);
    // MorphFstHeader (56 bytes)
    fstBuf.putInt(matCount);
    fstBuf.putInt(packedArcs.size());
    fstBuf.putInt(morphemeList.size());
    fstBuf.putInt(absStateList.size());
    fstBuf.putInt(surfOffLens.length);
    fstBuf.putInt(condCompiler.programs.size());
    fstBuf.putInt(condInsts.size());
    fstBuf.putInt(condCompiler.setWords.size());
    fstBuf.putInt(diffItems.size());
    fstBuf.putInt(diffTargetsRaw.size());
    fstBuf.putInt(seqBytesArr.length);
    fstBuf.putInt(fstStrPoolBytes.length);
    fstBuf.put((byte) nomMorphId);
    fstBuf.put((byte) pnonMorphId);
    fstBuf.put((byte) p3sgMorphId);
    fstBuf.put((byte) 0);
    fstBuf.putInt(0);

    for (long w : condCompiler.setWords) {
      fstBuf.putLong(w);
    }
    for (int off : stateArcOffsets) {
      fstBuf.putInt(off);
    }
    for (int arc : packedArcs) {
      fstBuf.putInt(arc);
    }
    for (int off : condProgOffsets) {
      fstBuf.putInt(off);
    }
    for (int ins : condInsts) {
      fstBuf.putInt(ins);
    }
    for (int ol : surfOffLens) {
      fstBuf.putInt(ol);
    }
    for (int i = 0; i < morphemeList.size(); i++) {
      Morpheme m = morphemeList.get(i);
      fstBuf.putInt(morphIdOffLens[i]);
      fstBuf.put((byte) (m.pos == null ? 0xFF : m.pos.ordinal()));
      int mFlags = (m.derivational ? 1 : 0) | (m.informal ? 2 : 0);
      fstBuf.put((byte) mFlags);
      int mappedId = m.mappedMorpheme == null ? 0 : morphemeIdMap.get(m.mappedMorpheme);
      fstBuf.put((byte) mappedId);
      fstBuf.put((byte) 0);
    }
    for (MorphemeState ms : absStateList) {
      fstBuf.put((byte) morphemeIdMap.get(ms.morpheme).intValue());
      int sFlags = (ms.terminal ? 1 : 0) | (ms.derivative ? 2 : 0) | (ms.posRoot ? 4 : 0);
      fstBuf.put((byte) sFlags);
      fstBuf.putShort((short) 0);
    }
    for (int[] di : diffItems) {
      fstBuf.putInt(di[0]);
      fstBuf.putInt(di[1]);
    }
    for (int[] dt : diffTargetsRaw) {
      fstBuf.putInt(dt[0]);
      fstBuf.putInt(pool.offLen(diffTargetSurfs.get(dt[1])));
    }
    fstBuf.put(seqBytesArr);
    fstBuf.put(fstStrPoolBytes);

    // 10. Quantize `model_15k_v25.bin` into SECT_AMBIG_QWT (`kind = 6`)
    AmbigQwtBundle ambigBundle = buildAmbigQwtSection();

    // 11. Export `CleanWordLm` (`SECT_CLEAN_LM`, `kind = 7`) and `endingTrie` (`SECT_ENDING_TRIE`, `kind = 8`)
    NormalizationKnowledge normKnowledge = NormalizationKnowledge.loadDefault();
    byte[] cleanLmBytes = buildCleanLmSection(normKnowledge.cleanLm);
    EndingTrieBundle endingBundle = buildEndingTrieSection();

    // 12. Export `DeasciifierModel` (`SECT_DEASC`, `kind = 9`)
    DeascBundle deascBundle = buildDeascSection();

    // 13. Export `SmoothLm`-free `NormalizationKnowledge` (`SECT_NORM_KNOWLEDGE = 10`) & `SpanLatticePerceptronNormalizer` (`SECT_NORM_PERCEPTRON = 11`)
    TurkishMorphology strictNormMorph = TurkishMorphology.createWithDefaults();
    String customNoSmoothPerc = System.getProperty("zmbk.noSmoothPerceptron", "").trim();
    SpanLatticePerceptronNormalizer normNoSmooth;
    if (!customNoSmoothPerc.isEmpty()) {
      SpanLatticePerceptronNormalizer tmp =
          SpanLatticePerceptronNormalizer.createWithoutSmoothLm(strictNormMorph);
      normNoSmooth =
          SpanLatticePerceptronNormalizer.loadModel(Paths.get(customNoSmoothPerc), tmp.generator());
      System.out.println("Loaded dedicated No-SmoothLm Perceptron from " + customNoSmoothPerc);
    } else {
      normNoSmooth = SpanLatticePerceptronNormalizer.createWithoutSmoothLm(strictNormMorph);
    }
    byte[] normKnowBytes = buildNormKnowledgeSection(normKnowledge, normNoSmooth);
    byte[] normPercBytes = buildNormPerceptronSection(normNoSmooth);

    byte[] zmbkBytes =
        buildZmbkPack(
            0,
            List.of(
                new SectionData(SECT_LEXICON, 0, items.size(), lexBuf.array()),
                new SectionData(SECT_STEM_TRIE, 0, termTargets.size(), trieBuf.array()),
                new SectionData(SECT_ENTRY_BRIDGE, 0, matCount, bridgeBuf.array()),
                new SectionData(SECT_MORPH_FST, 0, packedArcs.size(), fstBuf.array()),
                new SectionData(SECT_AMBIG_QWT, 0, ambigBundle.count(), ambigBundle.sectionBytes()),
                new SectionData(SECT_CLEAN_LM, 0, 3, cleanLmBytes),
                new SectionData(
                    SECT_ENDING_TRIE, 0, endingBundle.nodeCount(), endingBundle.sectionBytes()),
                new SectionData(
                    SECT_DEASC, 0, deascBundle.sentWeightCount(), deascBundle.sectionBytes()),
                new SectionData(
                    SECT_NORM_KNOWLEDGE, 0, normKnowledge.parallel1ToN.size(), normKnowBytes),
                new SectionData(
                    SECT_NORM_PERCEPTRON,
                    0,
                    normNoSmooth.activeSparseFeatureCount(),
                    normPercBytes)));

    Path zmbkPath = outDir.resolve("zemberek_step2.zmbk");
    Files.write(zmbkPath, zmbkBytes);

    byte[] liteCleanLmBytes = buildUnigramCleanLmSection(normKnowledge.cleanLm);
    byte[] liteZmbkBytes =
        buildZmbkPack(
            1,
            List.of(
                new SectionData(SECT_LEXICON, 0, items.size(), lexBuf.array()),
                new SectionData(SECT_STEM_TRIE, 0, termTargets.size(), trieBuf.array()),
                new SectionData(SECT_ENTRY_BRIDGE, 0, matCount, bridgeBuf.array()),
                new SectionData(SECT_MORPH_FST, 0, packedArcs.size(), fstBuf.array()),
                new SectionData(SECT_CLEAN_LM, 1, 1, liteCleanLmBytes),
                new SectionData(
                    SECT_ENDING_TRIE, 0, endingBundle.nodeCount(), endingBundle.sectionBytes())));
    Path liteZmbkPath = outDir.resolve("zemberek_lite.zmbk");
    Files.write(liteZmbkPath, liteZmbkBytes);
    System.out.printf(
        "Built Core Lite %s: total=%,d B (%.3f MiB) | LITE_CLEAN_LM=%,d B%n",
        liteZmbkPath,
        liteZmbkBytes.length,
        liteZmbkBytes.length / (1024.0 * 1024.0),
        liteCleanLmBytes.length);

    System.out.printf(
        "Built %s: total=%,d B (%.3f MiB) | LEX=%,d B | TRIE=%,d B | BRIDGE=%,d B | FST=%,d B | AMBIG_QWT=%,d B | CLEAN_LM=%,d B (%.2f MiB) | ENDING_TRIE=%,d B | DEASC=%,d B | NORM_KNOW=%,d B (%.2f MiB) | NORM_PERC=%,d B (%.2f MiB)%n",
        zmbkPath,
        zmbkBytes.length,
        zmbkBytes.length / (1024.0 * 1024.0),
        lexBytesLen,
        trieBytesLen,
        bridgeBytesLen,
        fstBytesLen,
        ambigBundle.sectionBytes().length,
        cleanLmBytes.length,
        cleanLmBytes.length / (1024.0 * 1024.0),
        endingBundle.sectionBytes().length,
        deascBundle.sectionBytes().length,
        normKnowBytes.length,
        normKnowBytes.length / (1024.0 * 1024.0),
        normPercBytes.length,
        normPercBytes.length / (1024.0 * 1024.0));

    Path step2ParityBin = outDir.resolve("step2_parity.bin");
    if (!Files.exists(step2ParityBin)) {
      exportStep2Parity(repoRoot, step2ParityBin, items, itemIdByStrId, specialRootIdMap, abstractStateIdMap, stemTrans);
    }

    Path step3ParityBin = outDir.resolve("step3_parity.bin");
    if (!Files.exists(step3ParityBin)) {
      exportStep3Parity(repoRoot, step3ParityBin, items, itemIdByStrId);
    }

    Path step4ParityBin = outDir.resolve("step4_parity.bin");
    if (!Files.exists(step4ParityBin)) {
      exportStep4Parity(repoRoot, step4ParityBin, ambigBundle.lookup());
    }

    Path step5ParityBin = outDir.resolve("step5_parity.bin");
    if (!Files.exists(step5ParityBin)) {
      exportStep5Parity(repoRoot, step5ParityBin, normKnowledge.cleanLm);
    }

    Path step6ParityBin = outDir.resolve("step6_parity.bin");
    if (!Files.exists(step6ParityBin)) {
      exportStep6Parity(repoRoot, step6ParityBin);
    }

    Path step7ParityBin = outDir.resolve("step7_parity.bin");
    if (!Files.exists(step7ParityBin)) {
      exportStep7Parity(repoRoot, step7ParityBin, normNoSmooth);
    }
  }

  private static void exportStep2Parity(
      Path repoRoot,
      Path parityBin,
      List<DictionaryItem> items,
      Map<String, Integer> itemIdByStrId,
      Map<String, Integer> specialRootIdMap,
      Map<String, Integer> abstractStateIdMap,
      StemTransitions stemTrans)
      throws Exception {
    LinkedHashSet<String> testWordsSet = new LinkedHashSet<>(55_000);
    for (DictionaryItem it : items) {
      if (TurkishAlphabet.INSTANCE.containsCircumflex(it.lemma)
          || it.hasAttribute(RootAttribute.LocaleEn)
          || !it.alternativePronunciations.isEmpty()
          || specialRootIdMap.containsKey(it.id)) {
        String low = it.lemma.toLowerCase(Turkish.LOCALE);
        testWordsSet.add(low);
        testWordsSet.add(TurkishAlphabet.INSTANCE.toAscii(low));
        testWordsSet.add(low + "lar");
        testWordsSet.add(TurkishAlphabet.INSTANCE.toAscii(low + "leri"));
      }
    }
    for (String line :
        Files.readAllLines(
            repoRoot.resolve("morphology/src/test/resources/top-20K-words.txt"),
            StandardCharsets.UTF_8)) {
      String w = line.trim().toLowerCase(Turkish.LOCALE);
      if (!w.isEmpty()) {
        testWordsSet.add(w);
        if (testWordsSet.size() < 35_000) {
          testWordsSet.add(TurkishAlphabet.INSTANCE.toAscii(w));
        }
      }
    }
    for (String line :
        Files.readAllLines(
            repoRoot.resolve("normalization/src/test/resources/10000_frequent_turkish_word"),
            StandardCharsets.UTF_8)) {
      String w = line.trim().toLowerCase(Turkish.LOCALE);
      if (!w.isEmpty() && testWordsSet.size() < 45_000) {
        testWordsSet.add(w);
      }
    }
    for (String line :
        Files.readAllLines(
            repoRoot.resolve("tokenization/src/test/resources/tokenization/Sentence-Boundary-Train.txt"),
            StandardCharsets.UTF_8)) {
      if (testWordsSet.size() >= 50_000) {
        break;
      }
      for (String tok : TurkishTokenizer.DEFAULT.tokenizeToStrings(line)) {
        String w = tok.toLowerCase(Turkish.LOCALE);
        if (!w.isEmpty()) {
          testWordsSet.add(w);
          if (testWordsSet.size() >= 50_000) {
            break;
          }
        }
      }
    }

    List<String> testWords = new ArrayList<>(testWordsSet).subList(0, 50_000);
    try (DataOutputStream dos =
        new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(parityBin), 1 << 20))) {
      dos.writeInt(items.size());
      for (int id = 0; id < items.size(); id++) {
        DictionaryItem it = items.get(id);
        writeStr(dos, it.lemma);
        writeStr(dos, it.root);
        writeStr(dos, it.normalizedLemma());
        writeStr(dos, it.id);
        dos.writeByte(it.primaryPos.ordinal());
        dos.writeByte(it.secondaryPos.ordinal());
        dos.writeInt(rootAttrBits(it.attributes));
        dos.writeByte(it.index);
        dos.writeByte(specialRootIdMap.getOrDefault(it.id, 0));
        int refId =
            it.getReferenceItem() != null
                ? itemIdByStrId.getOrDefault(it.getReferenceItem().id, -1)
                : -1;
        dos.writeInt(refId);
      }

      dos.writeInt(testWords.size());
      for (String w : testWords) {
        writeStr(dos, w);
        for (boolean asciiTol : new boolean[] {false, true}) {
          List<StemTransition> matches = stemTrans.getPrefixMatches(w, asciiTol);
          dos.writeShort(matches.size());
          for (StemTransition st : matches) {
            writeStr(dos, st.surface);
            dos.writeInt(itemIdByStrId.get(st.item.id));
            dos.writeShort(abstractStateIdMap.get(st.to.id));
            dos.writeInt(phonoBits(st.getPhoneticAttributes()));
          }
        }
      }
    }
  }

  private static void exportStep3Parity(
      Path repoRoot,
      Path outBin,
      List<DictionaryItem> items,
      Map<String, Integer> itemIdByStrId)
      throws Exception {
    TurkishMorphology morphFormal = TurkishMorphology.builder().setLexicon(RootLexicon.getDefault()).disableCache().build();
    TurkishMorphology morphInformal =
        TurkishMorphology.builder()
            .setLexicon(RootLexicon.getDefault())
            .useInformalAnalysis()
            .disableCache()
            .build();
    TurkishMorphology morphAsciiInformal =
        TurkishMorphology.builder()
            .setLexicon(RootLexicon.getDefault())
            .useInformalAnalysis()
            .ignoreDiacriticsInAnalysis()
            .disableCache()
            .build();
    TurkishMorphology[] modes = {morphFormal, morphInformal, morphAsciiInformal};

    LinkedHashSet<String> sentencesSet = new LinkedHashSet<>(12_000);
    // 1. Handcrafted edge-case suite covering every corner of Turkish morphology & OOV analysis
    String[] edgeSentences = {
      "Bugün saat 14:00'te ve 14:30'da Dr. Ahmet'le TBMM'de görüşeceğiz.",
      "TÜBİTAK'ın 01.05.2024'te açıkladığı %15'lik artış 3/4'ünü kapsıyor.",
      "II. Dünya Savaşı'nda XIV'üncü kolordu 10-15'inci gün MCMXCIX kişiyi kurtardı.",
      "Kâr amacı gütmeyen kurumlar kar yağışında hâlâ hala ile âdet ve adet tartıştı.",
      "Rüzgârda laik ve lâik, yar ve yâr kelimeleri su'yu ve camii'nde ile incelendi.",
      "Yarın okula gidiyom, sen de geliyosun di mi 😀 :)",
      "Ben bu işi yapcam ama sen yapmicam diyosun, bi bakıyim okuyim dedim!",
      "Tweet'leri #Türkiye'de @ahmet_bey'e https://ornek.org.tr'den gönderdim.",
      "1990'larda MP3'ler, A4'e, F16'ların ve G20'nin gündemindeydi.",
      "İçeride, dışarıda, yukarıda, şurada, burada ve orada bana, sana ne dedi, ne yedi?",
      "Birbirimize, çoğumuza, öbürüne ve birçoğuna zeytinyağı ve ayakkabıları verdiler.",
      "Google'da, Apple'ın, Word'de, Twitter'dan ve WhatsApp'ta mesajlaştık.",
      "Dördüncü, 4'üncü, 4., 3'er, 100'üncü ve 0'dan başlayarak 12,5'i hesapladık.",
      "İstanbul'un, Ankara'nın, İzmir'de, Çanakkale'ye, Ağrı'dan selamlar ''merhaba'' — test!"
    };
    Collections.addAll(sentencesSet, edgeSentences);

    // 2. Noisy social-media corpus
    Path noisyPath = repoRoot.resolve("normalization/src/test/resources/normalization/mini-noisy-corpus.txt");
    if (Files.exists(noisyPath)) {
      for (String line : Files.readAllLines(noisyPath, StandardCharsets.UTF_8)) {
        String s = line.trim();
        if (!s.isEmpty()) {
          sentencesSet.add(s);
        }
      }
    }

    // 3. Deasciifier benchmarks (news, wikipedia, social)
    Path deascDir = repoRoot.resolve("experiment/src/test/resources/deasciifier-benchmarks");
    if (Files.isDirectory(deascDir)) {
      try (Stream<Path> paths = Files.list(deascDir)) {
        for (Path p : paths.sorted().toList()) {
          if (p.toString().endsWith(".txt")) {
            for (String para : Files.readAllLines(p, StandardCharsets.UTF_8)) {
              for (String s : TurkishSentenceExtractor.DEFAULT.fromParagraph(para)) {
                String t = s.trim();
                if (t.length() >= 4 && sentencesSet.size() < 4500) {
                  sentencesSet.add(t);
                }
              }
            }
          }
        }
      }
    }

    // 4. Sentence-Boundary-Test & Train
    for (String rel :
        new String[] {
          "tokenization/src/test/resources/tokenization/Sentence-Boundary-Test.txt",
          "tokenization/src/test/resources/tokenization/Sentence-Boundary-Train.txt"
        }) {
      for (String raw : Files.readAllLines(repoRoot.resolve(rel), StandardCharsets.UTF_8)) {
        if (sentencesSet.size() >= 10_000) {
          break;
        }
        String clean = raw.replace("|", " ").trim();
        for (String s : TurkishSentenceExtractor.DEFAULT.fromParagraph(clean)) {
          String t = s.trim();
          if (!t.isEmpty()) {
            sentencesSet.add(t);
            if (sentencesSet.size() >= 10_000) {
              break;
            }
          }
        }
      }
    }

    // Top up deterministically if needed so we have >= 10,000 distinct sentences
    int synthIdx = 1;
    while (sentencesSet.size() < 10_000) {
      sentencesSet.add("Bu " + synthIdx + ". test cümlesinde kitaplarımızdan bahsediyoruz.");
      synthIdx++;
    }

    List<String> sentences = new ArrayList<>(sentencesSet).subList(0, 10_000);
    int fullDetailCount = 400;
    long totalTokenChecks = 0;

    try (DataOutputStream dos =
        new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(outBin), 1 << 22))) {
      dos.writeInt(sentences.size());
      dos.writeInt(fullDetailCount);

      for (int sIdx = 0; sIdx < sentences.size(); sIdx++) {
        String sent = sentences.get(sIdx);
        writeStr(dos, sent);
        boolean fullDetail = sIdx < fullDetailCount;

        for (int m = 0; m < 3; m++) {
          List<WordAnalysis> was = modes[m].analyzeSentence(sent);
          dos.writeShort(was.size());
          totalTokenChecks += was.size();
          for (WordAnalysis wa : was) {
            List<SingleAnalysis> ans = wa.getAnalysisResults();
            dos.writeShort(ans.size());

            long h = 0xcbf29ce484222325L;
            h = fnv1a64Update(h, wa.getInput());
            h = fnv1a64Update(h, wa.getNormalizedInput());
            for (SingleAnalysis sa : ans) {
              h = fnv1a64Update(h, sa.formatLong());
              h = fnv1a64Update(h, String.join(",", sa.getStems()));
              h = fnv1a64Update(h, String.join(",", sa.getLemmas()));
            }
            dos.writeLong(h);

            if (fullDetail) {
              writeStr(dos, wa.getInput());
              writeStr(dos, wa.getNormalizedInput());
              for (SingleAnalysis sa : ans) {
                writeStr(dos, sa.formatLong());
                writeStr(dos, String.join(",", sa.getStems()));
                writeStr(dos, String.join(",", sa.getLemmas()));
              }
            }
          }
        }
      }

      // Part B: WordGenerator parity cases (formal WordGenerator)
      WordGenerator gen = morphFormal.getWordGenerator();
      String[][] nounMorphChains = {
        {"Noun", "A3sg", "Dat"},
        {"Noun", "A3pl", "P1sg", "Loc"},
        {"Noun", "A3sg", "P3sg", "Abl"},
        {"Noun", "A3sg", "With", "A3pl", "Acc"},
        {"Noun", "Dim", "A3sg", "P1sg", "Nom"}
      };
      String[][] verbMorphChains = {
        {"Verb", "Prog1", "A1sg"},
        {"Verb", "Neg", "Fut", "A3pl"},
        {"Verb", "Able", "Past", "A2sg"},
        {"Verb", "Pass", "Narr", "A3sg"},
        {"Verb", "Caus", "Aor", "A1pl"}
      };
      String[] targetItemIds = {
        "kitap_Noun", "elma_Noun", "su_Noun", "ağız_Noun", "burun_Noun", "hak_Noun", "saat_Noun",
        "renk_Noun", "zeytinyağı_Noun", "içeri_Noun", "bura_Noun", "Ankara_Noun_Prop",
        "Google_Noun_Prop", "ben_Pron_Pers", "sen_Pron_Pers", "birbiri_Pron_Quant",
        "gelmek_Verb", "aramak_Verb", "demek_Verb", "yemek_Verb", "etmek_Verb", "kavurmak_Verb"
      };
      record GenCase(int itemId, String[] morphs, List<WordGenerator.Result> results) {}
      List<GenCase> genCases = new ArrayList<>();
      for (String strId : targetItemIds) {
        Integer itemId = itemIdByStrId.get(strId);
        if (itemId == null) {
          continue;
        }
        DictionaryItem item = items.get(itemId);
        String[][] chains = item.primaryPos == PrimaryPos.Verb ? verbMorphChains : nounMorphChains;
        for (String[] ch : chains) {
          List<WordGenerator.Result> res = gen.generate(item, ch);
          genCases.add(new GenCase(itemId, ch, res));
        }
        if (item.primaryPos == PrimaryPos.Pronoun) {
          for (String caseM : new String[] {"Dat", "Loc", "Abl", "Gen", "Acc"}) {
            String[] ch = {"Pron", caseM};
            genCases.add(new GenCase(itemId, ch, gen.generate(item, ch)));
          }
        }
      }
      dos.writeInt(genCases.size());
      for (GenCase gc : genCases) {
        dos.writeInt(gc.itemId());
        dos.writeByte(gc.morphs().length);
        for (String m : gc.morphs()) {
          writeStr(dos, m);
        }
        dos.writeShort(gc.results().size());
        for (WordGenerator.Result r : gc.results()) {
          writeStr(dos, r.surface);
          writeStr(dos, r.analysis.formatLong());
        }
      }

      // Part C: InformalAnalysisConverter parity cases
      InformalAnalysisConverter converter =
          new InformalAnalysisConverter(morphInformal.getWordGenerator());
      String[] informalWords = {
        "gidiyom", "geliyosun", "yapıyo", "biliyonuz", "bakıyolar", "yapcam", "yapmicam",
        "gidicem", "gelmicez", "gidecen", "okuyim", "bakıyim", "arıyim", "diyo", "yiyo",
        "diycek", "yiycek", "yapcakmış", "geliyodu", "gidiyomuş", "bilmiyom", "göremiyom",
        "kitaplar", "evdeyim"
      };
      dos.writeInt(informalWords.length);
      for (String iw : informalWords) {
        writeStr(dos, iw);
        WordAnalysis wa = morphInformal.analyze(iw);
        List<SingleAnalysis> ans = wa.getAnalysisResults();
        dos.writeShort(ans.size());
        for (SingleAnalysis sa : ans) {
          writeStr(dos, sa.formatLong());
          WordGenerator.Result conv = converter.convert(iw, sa);
          dos.writeBoolean(conv != null);
          if (conv != null) {
            writeStr(dos, conv.surface);
            writeStr(dos, conv.analysis.formatLong());
          }
        }
      }

      // Part D: WordAnalysisSurfaceFormatter parity cases
      WordAnalysisSurfaceFormatter formatter = new WordAnalysisSurfaceFormatter();
      WordAnalysisSurfaceFormatter.CaseType[] allCases =
          WordAnalysisSurfaceFormatter.CaseType.values();
      String[] surfWords = {
        "Ankara'da", "ANKARA'da", "İSTANBUL", "kitaplarımızdan", "Türkiye'nin", "Google'da",
        "12'ye", "01.05.2024'te", "TBMM'de", "Türkçeye", "İngilizcede", "rüzgârda"
      };
      dos.writeInt(surfWords.length);
      for (String sw : surfWords) {
        writeStr(dos, sw);
        dos.writeByte(formatter.guessCase(sw).ordinal());
        WordAnalysis wa = morphFormal.analyze(sw);
        List<SingleAnalysis> ans = wa.getAnalysisResults();
        dos.writeShort(ans.size());
        for (SingleAnalysis sa : ans) {
          writeStr(dos, formatter.format(sa, null));
          writeStr(dos, formatter.format(sa, "'"));
          writeStr(dos, formatter.format(sa, "’"));
          for (WordAnalysisSurfaceFormatter.CaseType ct : allCases) {
            boolean can = formatter.canBeFormatted(sa, ct);
            dos.writeBoolean(can);
            writeStr(dos, formatter.formatToCase(sa, ct, null));
          }
        }
      }
    }

    System.out.printf(
        "Exported Step 3 parity fixture: %s (%,d B = %.2f MiB, sentences=%,d, totalTokenAnalyses=%,d)%n",
        outBin, Files.size(outBin), Files.size(outBin) / (1024.0 * 1024.0), sentences.size(), totalTokenChecks);
  }

  private record AmbigQwtBundle(int count, byte[] sectionBytes, QuantizedAmbiguityLookup lookup) {}

  private static short fp16FromFp27(int fp27) {
    short fp = (short) (((fp27 >>> 11) ^ fp27) & 0xFFFF);
    return fp == 0 ? (short) 1 : fp;
  }

  private static float[] fitCodebook256(float[] values) {
    float[] sorted = values.clone();
    Arrays.sort(sorted);
    float[] codebook = new float[256];
    codebook[0] = 0.0f;

    int n = sorted.length;
    for (int c = 1; c < 256; c++) {
      int pos = (int) (((c - 0.5) / 255.0) * (n - 1));
      codebook[c] = sorted[pos];
    }

    int stride = Math.max(1, n / 150_000);
    double[] sums = new double[256];
    int[] counts = new int[256];
    for (int iter = 0; iter < 12; iter++) {
      Arrays.fill(sums, 0.0);
      Arrays.fill(counts, 0);
      for (int i = 0; i < n; i += stride) {
        int c = nearestCentroid(codebook, sorted[i]);
        sums[c] += sorted[i];
        counts[c]++;
      }
      for (int c = 1; c < 256; c++) {
        if (counts[c] > 0) {
          codebook[c] = (float) (sums[c] / counts[c]);
        }
      }
      Arrays.sort(codebook, 1, 256);
    }
    return codebook;
  }

  private static int nearestCentroid(float[] codebook, float value) {
    int lo = 1;
    int hi = 255;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (codebook[mid] < value) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return (lo > 1 && Math.abs(value - codebook[lo - 1]) < Math.abs(value - codebook[lo]))
        ? lo - 1
        : lo;
  }

  private static final class QuantizedAmbiguityLookup extends CompressedWeights {
    private static final int[] POW31 = new int[512];

    static {
      POW31[0] = 1;
      for (int i = 1; i < POW31.length; i++) {
        POW31[i] = POW31[i - 1] * 31;
      }
    }

    private final MultiLevelMphf mphf;
    private final float scale;
    private final int[] fingerprints;
    private final short[] qweights;

    QuantizedAmbiguityLookup(
        MultiLevelMphf mphf, float scale, int[] fingerprints, short[] qweights) {
      super(null);
      this.mphf = mphf;
      this.scale = scale;
      this.fingerprints = fingerprints;
      this.qweights = qweights;
    }

    @Override
    public int size() {
      return qweights.length;
    }

    @Override
    public float get(String key) {
      int fp27 = key.hashCode() & 0x07FF_FFFF;
      int idx = mphf.get(key);
      return fingerprints[idx] == fp27 ? qweights[idx] * scale : 0f;
    }

    @Override
    public float get(String prefix, String suffix) {
      int sLen = suffix.length();
      int fp27 =
          sLen < POW31.length
              ? ((prefix.hashCode() * POW31[sLen] + suffix.hashCode()) & 0x07FF_FFFF)
              : ((prefix + suffix).hashCode() & 0x07FF_FFFF);
      int idx = mphf.get(prefix, suffix);
      return fingerprints[idx] == fp27 ? qweights[idx] * scale : 0f;
    }

    @Override
    public float get(String p1, String p2, String p3) {
      int len2 = p2.length();
      int len3 = p3.length();
      int fp27 =
          (len2 < POW31.length && len3 < POW31.length)
              ? (((p1.hashCode() * POW31[len2] + p2.hashCode()) * POW31[len3] + p3.hashCode())
                  & 0x07FF_FFFF)
              : ((p1 + p2 + p3).hashCode() & 0x07FF_FFFF);
      int idx = mphf.get(p1, p2, p3);
      return fingerprints[idx] == fp27 ? qweights[idx] * scale : 0f;
    }
  }

  private static byte[] serializeMphfI16(
      MultiLevelMphf mphf, float scale, int[] fingerprints, short[] qweights) throws Exception {
    Object[] levels = (Object[]) getField(mphf, "hashLevelData");
    int count = qweights.length;
    int levelCount = levels.length;
    int totalFailed = 0;
    int totalSeeds = 0;

    int[] keyAmounts = new int[levelCount];
    int[] bucketAmounts = new int[levelCount];
    int[] seedOffsets = new int[levelCount];
    int[] failedOffsets = new int[levelCount];
    byte[][] seedArrays = new byte[levelCount][];
    int[][] failedArrays = new int[levelCount][];

    for (int i = 0; i < levelCount; i++) {
      Object lvl = levels[i];
      keyAmounts[i] = (Integer) getField(lvl, "keyAmount");
      bucketAmounts[i] = (Integer) getField(lvl, "bucketAmount");
      seedArrays[i] = (byte[]) getField(lvl, "bucketHashSeedValues");
      failedArrays[i] = (int[]) getField(lvl, "failedIndexes");
      seedOffsets[i] = totalSeeds;
      failedOffsets[i] = totalFailed;
      totalSeeds += seedArrays[i].length;
      totalFailed += failedArrays[i].length;
    }

    int byteLen =
        24 + levelCount * 24 + totalFailed * 4 + count * 4 + count * 2 + totalSeeds;
    ByteBuffer buf = ByteBuffer.allocate(byteLen).order(ByteOrder.LITTLE_ENDIAN);
    buf.putInt(count);
    buf.putInt(levelCount);
    buf.putInt(totalFailed);
    buf.putInt(totalSeeds);
    buf.putFloat(scale);
    buf.putInt(0);
    for (int i = 0; i < levelCount; i++) {
      buf.putInt(keyAmounts[i]);
      buf.putInt(bucketAmounts[i]);
      buf.putInt(seedOffsets[i]);
      buf.putInt(failedOffsets[i]);
      buf.putInt(failedArrays[i].length);
      buf.putInt(0);
    }
    for (int i = 0; i < levelCount; i++) {
      for (int f : failedArrays[i]) {
        buf.putInt(f);
      }
    }
    for (int fp : fingerprints) {
      buf.putInt(fp);
    }
    for (short qw : qweights) {
      buf.putShort(qw);
    }
    for (int i = 0; i < levelCount; i++) {
      buf.put(seedArrays[i]);
    }
    return buf.array();
  }

  private static AmbigQwtBundle buildAmbigQwtSection() throws Exception {
    LossyIntLookup rawLookup;
    try (var dis = IOUtil.getDataInputStream(FastPerceptronAmbiguityResolver.DEFAULT_MODEL_RESOURCE)) {
      rawLookup = LossyIntLookup.deserialize(dis);
    }
    MultiLevelMphf mphf = (MultiLevelMphf) getField(rawLookup, "mphf");
    int[] data = (int[]) getField(rawLookup, "data");
    int count = data.length / 2;

    float[] vals = new float[count];
    int[] fps = new int[count];
    float maxAbs = 1e-6f;
    for (int i = 0; i < count; i++) {
      fps[i] = data[i * 2] & 0x07FF_FFFF;
      float v = Float.intBitsToFloat(data[i * 2 + 1]);
      vals[i] = v;
      float av = Math.abs(v);
      if (av > maxAbs) {
        maxAbs = av;
      }
    }
    float scale = maxAbs / 32767.0f;
    short[] qweights = new short[count];
    for (int i = 0; i < count; i++) {
      int q = Math.round(vals[i] / scale);
      qweights[i] = (short) Math.max(-32767, Math.min(32767, q));
    }

    byte[] secBytes = serializeMphfI16(mphf, scale, fps, qweights);
    QuantizedAmbiguityLookup qLookup = new QuantizedAmbiguityLookup(mphf, scale, fps, qweights);
    return new AmbigQwtBundle(count, secBytes, qLookup);
  }

  private static double evalAmbiguousAccuracy(
      List<SentenceAnalysis> goldSents, FastPerceptronAmbiguityResolver resolver) {
    int ambigHit = 0;
    int ambigTotal = 0;
    for (SentenceAnalysis gold : goldSents) {
      SentenceAnalysis pred = resolver.disambiguate(gold.getSentence(), gold.ambiguousAnalysis());
      List<SentenceWordAnalysis> gWords = gold.parseEntries();
      List<SingleAnalysis> pBest = pred.bestAnalysis();
      for (int i = 0; i < gWords.size(); i++) {
        WordAnalysis wa = gWords.get(i).getWordAnalysis();
        if (wa.analysisCount() > 1) {
          ambigTotal++;
          if (gWords.get(i).getBestAnalysis().formatLong().equals(pBest.get(i).formatLong())) {
            ambigHit++;
          }
        }
      }
    }
    return ambigTotal > 0 ? ((double) ambigHit / ambigTotal) : 1.0;
  }

  private static void exportStep4Parity(
      Path repoRoot, Path outBin, QuantizedAmbiguityLookup quantLookup) throws Exception {
    TurkishMorphology morph =
        TurkishMorphology.builder().setLexicon(RootLexicon.getDefault()).disableCache().build();
    FastPerceptronAmbiguityResolver baseResolver = FastPerceptronAmbiguityResolver.createDefault();

    FastPerceptronAmbiguityResolver quantViterbi = new FastPerceptronAmbiguityResolver(quantLookup);
    FastPerceptronAmbiguityResolver quantBeam8 = new FastPerceptronAmbiguityResolver(quantLookup);
    quantBeam8.setBeamSize(8);
    FastPerceptronAmbiguityResolver quantGreedy = new FastPerceptronAmbiguityResolver(quantLookup);
    quantGreedy.setGreedy(true);

    Path dataRoot = Paths.get(System.getProperty("user.home"), "data", "turkish", "annotated_slices");
    Path[] goldFiles = {
      dataRoot.resolve("neon_modern_v25/test/annotated/test_1k_neon_v25.zemberek.txt"),
      dataRoot.resolve("argon_high_conventions_test_refreshed/test/annotated/test_1k_argon_v24_refreshed.zemberek.txt"),
      dataRoot.resolve("natural_500_v25/annotated/test_500_natural_carbon_v25.zemberek.txt")
    };

    List<SentenceAnalysis> goldSentences = new ArrayList<>(2500);
    for (Path gf : goldFiles) {
      if (Files.exists(gf)) {
        FastPerceptronAmbiguityResolverTrainer.DataSet ds =
            FastPerceptronAmbiguityResolverTrainer.DataSet.load(gf, morph);
        goldSentences.addAll(ds.sentences);
      }
    }

    float baseAcc = 1.0f;
    float quantAcc = 1.0f;
    List<String> evalSentences = new ArrayList<>(2500);
    if (!goldSentences.isEmpty()) {
      baseAcc = (float) evalAmbiguousAccuracy(goldSentences, baseResolver);
      quantAcc = (float) evalAmbiguousAccuracy(goldSentences, quantViterbi);
      for (SentenceAnalysis sa : goldSentences) {
        evalSentences.add(sa.getSentence());
      }
    } else {
      // Fallback corpus sentences if ~/data/turkish is absent
      for (String raw :
          Files.readAllLines(
              repoRoot.resolve("tokenization/src/test/resources/tokenization/Sentence-Boundary-Test.txt"),
              StandardCharsets.UTF_8)) {
        for (String s : TurkishSentenceExtractor.DEFAULT.fromParagraph(raw.replace("|", " "))) {
          if (!s.isBlank() && evalSentences.size() < 2200) {
            evalSentences.add(s.trim());
          }
        }
      }
    }

    try (DataOutputStream dos =
        new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(outBin), 1 << 20))) {
      dos.writeFloat(baseAcc);
      dos.writeFloat(quantAcc);
      dos.writeInt(evalSentences.size());

      for (String sent : evalSentences) {
        writeStr(dos, sent);
        List<WordAnalysis> was = morph.analyzeSentence(sent);
        dos.writeShort(was.size());

        FastPerceptronAmbiguityResolver.FastDecodeResult rVit =
            quantViterbi.getDecoder().bestPath(was);
        FastPerceptronAmbiguityResolver.FastDecodeResult rBeam =
            quantBeam8.getDecoder().bestPath(was);
        FastPerceptronAmbiguityResolver.FastDecodeResult rGrd =
            quantGreedy.getDecoder().bestPathGreedy(was);

        for (FastPerceptronAmbiguityResolver.FastDecodeResult res :
            new FastPerceptronAmbiguityResolver.FastDecodeResult[] {rVit, rBeam, rGrd}) {
          dos.writeFloat(res.score);
          for (SingleAnalysis sa : res.bestParse) {
            writeStr(dos, sa.formatLong());
          }
        }
      }
    }

    System.out.printf(
        "Exported Step 4 parity fixture: %s (%,d B = %.2f MiB, sentences=%,d, baseAmbig=%.2f%%, quantAmbig=%.2f%%, delta=%+.3f%%)%n",
        outBin,
        Files.size(outBin),
        Files.size(outBin) / (1024.0 * 1024.0),
        evalSentences.size(),
        baseAcc * 100.0,
        quantAcc * 100.0,
        (quantAcc - baseAcc) * 100.0);
  }

  private static byte[] serializeCountMap(Object countMap) throws Exception {
    int fpBits = (Integer) getField(countMap, "fpBits");
    byte[] codes = (byte[]) getField(countMap, "codes");
    int count = codes.length;
    if (count == 0) {
      ByteBuffer empty = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
      empty.putInt(0);
      empty.putInt(fpBits);
      empty.putInt(0);
      empty.putInt(0);
      empty.putInt(0);
      empty.putInt(0);
      return empty.array();
    }

    MultiLevelMphf mphf = (MultiLevelMphf) getField(countMap, "mphf");
    int[] codebook = (int[]) getField(countMap, "codebook");
    Object[] levels = (Object[]) getField(mphf, "hashLevelData");
    int levelCount = levels.length;
    int totalFailed = 0;
    int totalSeeds = 0;

    int[] keyAmounts = new int[levelCount];
    int[] bucketAmounts = new int[levelCount];
    int[] seedOffsets = new int[levelCount];
    int[] failedOffsets = new int[levelCount];
    byte[][] seedArrays = new byte[levelCount][];
    int[][] failedArrays = new int[levelCount][];

    for (int i = 0; i < levelCount; i++) {
      Object lvl = levels[i];
      keyAmounts[i] = (Integer) getField(lvl, "keyAmount");
      bucketAmounts[i] = (Integer) getField(lvl, "bucketAmount");
      seedArrays[i] = (byte[]) getField(lvl, "bucketHashSeedValues");
      failedArrays[i] = (int[]) getField(lvl, "failedIndexes");
      seedOffsets[i] = totalSeeds;
      failedOffsets[i] = totalFailed;
      totalSeeds += seedArrays[i].length;
      totalFailed += failedArrays[i].length;
    }

    short[] fp16 = fpBits == 16 ? (short[]) getField(countMap, "fp16") : null;
    byte[] fpPacked = fpBits == 16 ? null : (byte[]) getField(countMap, "fpPacked");
    int fpByteLen = fpBits == 16 ? (count * 2) : Objects.requireNonNull(fpPacked).length;

    int rawLen =
        24 + 256 * 4 + levelCount * 24 + totalFailed * 4 + fpByteLen + count + totalSeeds;
    int alignedLen = alignUp(rawLen, 8);
    ByteBuffer buf = ByteBuffer.allocate(alignedLen).order(ByteOrder.LITTLE_ENDIAN);
    buf.putInt(count);
    buf.putInt(fpBits);
    buf.putInt(levelCount);
    buf.putInt(totalFailed);
    buf.putInt(totalSeeds);
    buf.putInt(fpByteLen);
    for (int c : codebook) {
      buf.putInt(c);
    }
    for (int i = 0; i < levelCount; i++) {
      buf.putInt(keyAmounts[i]);
      buf.putInt(bucketAmounts[i]);
      buf.putInt(seedOffsets[i]);
      buf.putInt(failedOffsets[i]);
      buf.putInt(failedArrays[i].length);
      buf.putInt(0);
    }
    for (int i = 0; i < levelCount; i++) {
      for (int f : failedArrays[i]) {
        buf.putInt(f);
      }
    }
    if (fpBits == 16) {
      for (short fp : Objects.requireNonNull(fp16)) {
        buf.putShort(fp);
      }
    } else {
      buf.put(Objects.requireNonNull(fpPacked));
    }
    buf.put(codes);
    for (int i = 0; i < levelCount; i++) {
      buf.put(seedArrays[i]);
    }
    return buf.array();
  }

  private static byte[] buildCleanLmSection(NormalizationKnowledge.CleanWordLm cleanLm)
      throws Exception {
    double denom = (Double) getField(cleanLm, "denomUnigram");
    byte[] uBytes = serializeCountMap(getField(cleanLm, "unigrams"));
    byte[] bBytes = serializeCountMap(getField(cleanLm, "bigrams"));
    byte[] tBytes = serializeCountMap(getField(cleanLm, "trigrams"));

    int total = 32 + uBytes.length + bBytes.length + tBytes.length;
    ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
    buf.putDouble(denom);
    buf.putInt(uBytes.length);
    buf.putInt(bBytes.length);
    buf.putInt(tBytes.length);
    buf.putInt(0);
    buf.putInt(0);
    buf.putInt(0);
    buf.put(uBytes);
    buf.put(bBytes);
    buf.put(tBytes);
    return buf.array();
  }

  private static byte[] buildUnigramCleanLmSection(NormalizationKnowledge.CleanWordLm cleanLm)
      throws Exception {
    double denom = (Double) getField(cleanLm, "denomUnigram");
    byte[] uBytes = serializeCountMap(getField(cleanLm, "unigrams"));
    byte[] emptyMap = new byte[24];

    int total = 32 + uBytes.length + emptyMap.length * 2;
    ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
    buf.putDouble(denom);
    buf.putInt(uBytes.length);
    buf.putInt(emptyMap.length);
    buf.putInt(emptyMap.length);
    buf.putInt(1); // flags = 1 (unigram-only Lite LM)
    buf.putInt(0);
    buf.putInt(0);
    buf.put(uBytes);
    buf.put(emptyMap);
    buf.put(emptyMap);
    return buf.array();
  }

  private record EndingTrieBundle(int nodeCount, byte[] sectionBytes) {}

  private static final class EndingNodeBuilder {
    final TreeMap<Character, EndingNodeBuilder> children = new TreeMap<>();
    boolean canTerminate;
  }

  private static EndingTrieBundle buildEndingTrieSection() throws IOException {
    List<String> endings = TextIO.loadLinesFromResource("endings");
    EndingNodeBuilder root = new EndingNodeBuilder();
    TreeSet<Integer> paletteSet = new TreeSet<>();
    for (String ending : endings) {
      if (ending.isEmpty()) {
        continue;
      }
      EndingNodeBuilder cur = root;
      for (int i = 0; i < ending.length(); i++) {
        char c = ending.charAt(i);
        paletteSet.add((int) c);
        cur = cur.children.computeIfAbsent(c, k -> new EndingNodeBuilder());
      }
      cur.canTerminate = true;
    }

    if (paletteSet.size() > 256) {
      throw new IllegalStateException("EndingTrie palette > 256: " + paletteSet.size());
    }
    int[] palette = new int[256];
    int[] charToCode = new int[65536];
    int paletteLen = 0;
    for (int ch : paletteSet) {
      palette[paletteLen] = ch;
      charToCode[ch] = paletteLen;
      paletteLen++;
    }

    List<EndingNodeBuilder> bfs = new ArrayList<>(70_000);
    bfs.add(root);
    for (int i = 0; i < bfs.size(); i++) {
      bfs.addAll(bfs.get(i).children.values());
    }

    int n = bfs.size();
    int edgeCount = n - 1;
    int blockCount = (n >>> 4) + 1;
    int[] edgeStartBlocks = new int[blockCount];
    byte[] childCounts = new byte[n];
    byte[] edgeCharCodes = new byte[edgeCount];
    byte[] flags = new byte[n];

    int edgeCursor = 0;
    for (int i = 0; i < n; i++) {
      if ((i & 15) == 0) {
        edgeStartBlocks[i >>> 4] = edgeCursor;
      }
      EndingNodeBuilder cur = bfs.get(i);
      int cc = cur.children.size();
      if (cc > 255) {
        throw new IllegalStateException("EndingTrie node childCount > 255: " + cc);
      }
      childCounts[i] = (byte) cc;
      flags[i] = cur.canTerminate ? (byte) 1 : (byte) 0;
      for (char ch : cur.children.keySet()) {
        edgeCharCodes[edgeCursor++] = (byte) charToCode[ch];
      }
    }

    int[] minRemInt = new int[n];
    int[] maxRemInt = new int[n];
    int edgeEnd = edgeCount;
    for (int i = n - 1; i >= 0; i--) {
      int cnt = childCounts[i] & 0xFF;
      int start = edgeEnd - cnt;
      edgeEnd = start;
      int minR = (flags[i] & 1) != 0 ? 0 : 127;
      int maxR = 0;
      for (int e = start; e < start + cnt; e++) {
        int child = e + 1;
        minR = Math.min(minR, 1 + minRemInt[child]);
        maxR = Math.max(maxR, 1 + maxRemInt[child]);
      }
      minRemInt[i] = Math.min(126, minR);
      maxRemInt[i] = Math.min(126, maxR);
    }

    byte[] minRemAndTerm = new byte[n];
    byte[] maxRem = new byte[n];
    for (int i = 0; i < n; i++) {
      minRemAndTerm[i] = (byte) ((minRemInt[i] & 0x7F) | ((flags[i] & 1) != 0 ? 0x80 : 0));
      maxRem[i] = (byte) maxRemInt[i];
    }
    int maxEndingLen = maxRemInt[0];

    int byteLen = 32 + blockCount * 4 + 256 * 2 + n + edgeCount + n + n;
    if (byteLen > 420 * 1024) {
      throw new IllegalStateException("SECT_ENDING_TRIE exceeded 420 KiB gate: " + byteLen + " B");
    }

    ByteBuffer buf = ByteBuffer.allocate(byteLen).order(ByteOrder.LITTLE_ENDIAN);
    buf.putInt(n);
    buf.putInt(paletteLen);
    buf.putInt(blockCount);
    buf.putInt(maxEndingLen);
    buf.putInt(0);
    buf.putInt(0);
    buf.putInt(0);
    buf.putInt(0);
    for (int blk : edgeStartBlocks) {
      buf.putInt(blk);
    }
    for (int p : palette) {
      buf.putShort((short) p);
    }
    buf.put(childCounts);
    buf.put(edgeCharCodes);
    buf.put(minRemAndTerm);
    buf.put(maxRem);
    return new EndingTrieBundle(n, buf.array());
  }

  private static String deformWord(String w, Random rng, int mode) {
    if (w.length() < 2) {
      return w + "a";
    }
    StringBuilder sb = new StringBuilder(w);
    return switch (mode % 6) {
      case 0 -> {
        int idx = rng.nextInt(sb.length());
        char[] pool = {'a', 'e', 'i', 'ı', 'k', 'l', 'r', 's', 't', 'x'};
        sb.setCharAt(idx, pool[rng.nextInt(pool.length)]);
        yield sb.toString();
      }
      case 1 -> {
        int idx = rng.nextInt(sb.length() + 1);
        sb.insert(idx, sb.charAt(Math.min(idx, sb.length() - 1)));
        yield sb.toString();
      }
      case 2 -> {
        sb.deleteCharAt(rng.nextInt(sb.length()));
        yield sb.toString();
      }
      case 3 -> {
        int idx = rng.nextInt(sb.length() - 1);
        char tmp = sb.charAt(idx);
        sb.setCharAt(idx, sb.charAt(idx + 1));
        sb.setCharAt(idx + 1, tmp);
        yield sb.toString();
      }
      case 4 -> TurkishAlphabet.INSTANCE.toAscii(w);
      default -> Turkish.capitalize(TurkishAlphabet.INSTANCE.toAscii(w));
    };
  }

  private static void writeOptStr(DataOutputStream dos, String s) throws IOException {
    if (s == null) {
      dos.writeBoolean(false);
    } else {
      dos.writeBoolean(true);
      writeStr(dos, s);
    }
  }

  private static void exportStep5Parity(
      Path repoRoot, Path outBin, NormalizationKnowledge.CleanWordLm cleanLm) throws Exception {
    TurkishMorphology morph =
        TurkishMorphology.builder().setLexicon(RootLexicon.getDefault()).disableCache().build();
    TurkishSpellChecker legacyCheckOnly = new TurkishSpellChecker(morph);
    CompactStemEndingSpellChecker v2Spell =
        new CompactStemEndingSpellChecker(morph, TextIO.loadLinesFromResource("endings"), 0);

    // Build vocabulary pool for CleanWordLm n-gram parity
    List<String> lmVocab = new ArrayList<>(4096);
    lmVocab.add(null);
    lmVocab.add("bir");
    lmVocab.add("ve");
    lmVocab.add("bu");
    lmVocab.add("için");
    lmVocab.add("türkiye");
    lmVocab.add("istanbul'da");
    lmVocab.add("rüzgâr");
    lmVocab.add("kağıt");
    lmVocab.add("olmayan_kelime_xyz123");
    lmVocab.add("😀emoji");

    List<List<String>> sentTokens = new ArrayList<>(1000);
    for (String raw :
        Files.readAllLines(
            repoRoot.resolve("tokenization/src/test/resources/tokenization/Sentence-Boundary-Test.txt"),
            StandardCharsets.UTF_8)) {
      for (String s : TurkishSentenceExtractor.DEFAULT.fromParagraph(raw.replace("|", " "))) {
        List<String> toks = TurkishTokenizer.DEFAULT.tokenizeToStrings(s);
        if (!toks.isEmpty()) {
          List<String> low = new ArrayList<>(toks.size());
          for (String t : toks) {
            String lw = t.toLowerCase(Turkish.LOCALE);
            low.add(lw);
            if (lmVocab.size() < 4000) {
              lmVocab.add(lw);
            }
          }
          sentTokens.add(low);
        }
      }
    }

    List<String[]> lmQueries = new ArrayList<>(10_500);
    lmQueries.add(new String[] {null, null, null});
    lmQueries.add(new String[] {null, null, "bir"});
    lmQueries.add(new String[] {null, "bu", "bir"});
    lmQueries.add(new String[] {"bu", "bir", null});
    lmQueries.add(new String[] {"😀emoji", "türkiye", "istanbul'da"});

    for (List<String> st : sentTokens) {
      for (int i = 0; i <= st.size() && lmQueries.size() < 6_500; i++) {
        String w0 = i >= 2 ? st.get(i - 2) : null;
        String w1 = i >= 1 ? st.get(i - 1) : null;
        String w2 = i < st.size() ? st.get(i) : null;
        lmQueries.add(new String[] {w0, w1, w2});
      }
    }

    Random rng = new Random(0x5EED_57E5L);
    while (lmQueries.size() < 10_200) {
      String w0 = rng.nextInt(6) == 0 ? null : lmVocab.get(rng.nextInt(lmVocab.size()));
      String w1 = rng.nextInt(6) == 0 ? null : lmVocab.get(rng.nextInt(lmVocab.size()));
      String w2 = rng.nextInt(6) == 0 ? null : lmVocab.get(rng.nextInt(lmVocab.size()));
      lmQueries.add(new String[] {w0, w1, w2});
    }

    // Collect `check(word)` test inputs
    LinkedHashSet<String> checkInputs =
        new LinkedHashSet<>(
            List.of(
                "Ankara",
                "ANKARA",
                "Ankara'da",
                "ANKARA'DA",
                "ANKARA'da",
                "Ankara’da",
                "Ankara'",
                "ankara",
                "AnKARA",
                "Ankarada",
                "ankara'DA",
                "-Ankara",
                "Türkiye'nin",
                "TBMM'de",
                "Google'dan",
                "1'e",
                "4'ten",
                "123'ü",
                "12,5'ten",
                "1'E",
                "4'TEN",
                "123'Ü",
                "12,5'TEN",
                "%1",
                "%1'i",
                "%1,3'ü",
                "okudum",
                "okudm",
                "sıkıntı",
                "sikinti",
                "rüzgârda",
                "İSTANBUL"));
    for (List<String> st : sentTokens) {
      for (String w : st) {
        if (checkInputs.size() >= 500) {
          break;
        }
        checkInputs.add(w);
      }
      if (checkInputs.size() >= 500) {
        break;
      }
    }

    // Collect 5,200 diverse noisy/misspelled words for `suggestScored` parity
    LinkedHashSet<String> suggestInputs =
        new LinkedHashSet<>(
            List.of(
                "kitablar",
                "sikinti",
                "sikint",
                "guldum",
                "guldm",
                "okudm",
                "ankarada",
                "ankrada",
                "Ankara'da",
                "Ankara’da",
                "ANKARADA",
                "İSTANBUL'DA",
                "ISTANBUL'DA",
                "Turkiyede",
                "Türkiye'de",
                "rlma",
                "ylma",
                "elam",
                "armtu",
                "yapıcam",
                "geliyom",
                "gidiyomuş",
                "rüzgarda",
                "kağıt",
                "yalnış",
                "herkez",
                "birşey",
                "şarz",
                "kiprik",
                "mütevazi",
                "ev",
                "a",
                "'Ankara"));

    Path noisyCorpus =
        repoRoot.resolve("normalization/src/test/resources/normalization/mini-noisy-corpus.txt");
    if (Files.exists(noisyCorpus)) {
      for (String line : Files.readAllLines(noisyCorpus, StandardCharsets.UTF_8)) {
        for (String tok : TurkishTokenizer.DEFAULT.tokenizeToStrings(line)) {
          if (suggestInputs.size() < 1_800 && tok.length() <= 28) {
            suggestInputs.add(tok);
          }
        }
      }
    }

    Path freqWordsPath =
        repoRoot.resolve("normalization/src/test/resources/10000_frequent_turkish_word");
    List<String> freqWords = Files.readAllLines(freqWordsPath, StandardCharsets.UTF_8);
    int mode = 0;
    for (String fw : freqWords) {
      String clean = fw.trim();
      if (clean.isEmpty() || clean.length() > 24) {
        continue;
      }
      suggestInputs.add(deformWord(clean, rng, mode++));
      if (mode % 7 == 0) {
        suggestInputs.add(clean);
      }
      if (suggestInputs.size() >= 5_200) {
        break;
      }
    }

    try (DataOutputStream dos =
        new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(outBin), 1 << 20))) {
      dos.writeInt(lmQueries.size());
      for (String[] q : lmQueries) {
        writeOptStr(dos, q[0]);
        writeOptStr(dos, q[1]);
        writeOptStr(dos, q[2]);
        dos.writeInt(q[2] == null ? 0 : cleanLm.unigramCount(q[2]));
        dos.writeInt(cleanLm.bigramCount(q[1], q[2]));
        dos.writeFloat(cleanLm.unigramLogProb(q[2]));
        dos.writeFloat(cleanLm.bigramLogProb(q[1], q[2]));
        dos.writeFloat(cleanLm.trigramLogProb(q[0], q[1], q[2]));
      }

      dos.writeInt(checkInputs.size());
      for (String w : checkInputs) {
        writeStr(dos, w);
        dos.writeBoolean(legacyCheckOnly.check(w));
      }

      dos.writeInt(suggestInputs.size());
      for (String w : suggestInputs) {
        writeStr(dos, w);
        List<CompactStemEndingSpellChecker.ScoredCandidate> cands = v2Spell.suggestScored(w);
        dos.writeShort(cands.size());
        for (CompactStemEndingSpellChecker.ScoredCandidate c : cands) {
          writeStr(dos, c.surface());
          dos.writeFloat(c.editCost());
          dos.writeShort(c.diacriticDiffs());
        }
      }
    }

    System.out.printf(
        "Exported Step 5 parity fixture: %s (%,d B = %.2f MiB, lmQueries=%,d, checks=%,d, suggestWords=%,d)%n",
        outBin,
        Files.size(outBin),
        Files.size(outBin) / (1024.0 * 1024.0),
        lmQueries.size(),
        checkInputs.size(),
        suggestInputs.size());
  }

  private record DeascBundle(int sentWeightCount, byte[] sectionBytes) {}

  private static byte[] serializeAlignedQwtPadded8(QuantizedWeightTable qwt) throws Exception {
    MultiLevelMphf mphf = (MultiLevelMphf) getField(qwt, "mphf");
    short[] fingerprints = (short[]) getField(qwt, "fingerprints");
    byte[] codes = (byte[]) getField(qwt, "codes");
    float[] codebook = (float[]) getField(qwt, "codebook");

    int count = codes.length;
    if (mphf == null || count == 0) {
      int padded = alignUp(16 + 256 * 4, 8);
      ByteBuffer buf = ByteBuffer.allocate(padded).order(ByteOrder.LITTLE_ENDIAN);
      buf.putInt(0);
      buf.putInt(0);
      buf.putInt(0);
      buf.putInt(0);
      for (int i = 0; i < 256; i++) {
        buf.putFloat(0.0f);
      }
      return buf.array();
    }

    Object[] levels = (Object[]) getField(mphf, "hashLevelData");
    int levelCount = levels.length;
    int[] keyAmounts = new int[levelCount];
    int[] bucketAmounts = new int[levelCount];
    byte[][] seedArrays = new byte[levelCount][];
    int[][] failedArrays = new int[levelCount][];

    int totalFailed = 0;
    int totalSeeds = 0;
    for (int i = 0; i < levelCount; i++) {
      Object lvl = levels[i];
      keyAmounts[i] = (Integer) getField(lvl, "keyAmount");
      bucketAmounts[i] = (Integer) getField(lvl, "bucketAmount");
      seedArrays[i] = (byte[]) getField(lvl, "bucketHashSeedValues");
      failedArrays[i] = (int[]) getField(lvl, "failedIndexes");
      totalSeeds += seedArrays[i].length;
      totalFailed += failedArrays[i].length;
    }

    int rawBytes =
        16
            + (256 * 4)
            + (levelCount * 24)
            + (totalFailed * 4)
            + (count * 2)
            + count
            + totalSeeds;
    int paddedBytes = alignUp(rawBytes, 8);

    ByteBuffer buf = ByteBuffer.allocate(paddedBytes).order(ByteOrder.LITTLE_ENDIAN);
    buf.putInt(count);
    buf.putInt(levelCount);
    buf.putInt(totalFailed);
    buf.putInt(totalSeeds);

    for (int i = 0; i < 256; i++) {
      buf.putFloat(codebook[i]);
    }

    int seedOff = 0;
    int failOff = 0;
    for (int i = 0; i < levelCount; i++) {
      buf.putInt(keyAmounts[i]);
      buf.putInt(bucketAmounts[i]);
      buf.putInt(seedOff);
      buf.putInt(failOff);
      buf.putInt(failedArrays[i].length);
      buf.putInt(0);
      seedOff += seedArrays[i].length;
      failOff += failedArrays[i].length;
    }

    for (int i = 0; i < levelCount; i++) {
      for (int idx : failedArrays[i]) {
        buf.putInt(idx);
      }
    }

    for (short fp : fingerprints) {
      buf.putShort(fp);
    }

    buf.put(codes);

    for (int i = 0; i < levelCount; i++) {
      buf.put(seedArrays[i]);
    }

    return buf.array();
  }

  @SuppressWarnings("unchecked")
  private static DeascBundle buildDeascSection() throws Exception {
    DeasciifierModel dm = DeasciifierModel.loadDefault();
    LexiconPriors priors = dm.lexiconPriors();
    CharacterHarmonyModel charModel = dm.characterModel();
    QuantizedWeightTable sentWeights = dm.sentenceWeights();

    Set<String> foreignKeepSet = (Set<String>) getField(priors, "foreignKeepWords");
    Map<String, LexiconPriors.ConfusionSet> commonMap =
        (Map<String, LexiconPriors.ConfusionSet>) getField(priors, "confusionMap");
    Map<String, LexiconPriors.ConfusionSet> properMap =
        (Map<String, LexiconPriors.ConfusionSet>) getField(priors, "properConfusionMap");
    QuantizedWeightTable lemmaLogCounts = (QuantizedWeightTable) getField(priors, "lemmaLogCounts");
    QuantizedWeightTable charWeights = (QuantizedWeightTable) getField(charModel, "weights");

    byte[] lemmaQwt = serializeAlignedQwtPadded8(lemmaLogCounts);
    byte[] charQwt = serializeAlignedQwtPadded8(charWeights);
    byte[] sentQwt = serializeAlignedQwtPadded8(sentWeights);

    Comparator<String> utf8Order =
        (a, b) ->
            Arrays.compareUnsigned(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));

    List<String> foreignSorted = new ArrayList<>(foreignKeepSet);
    foreignSorted.sort(utf8Order);

    List<String> commonKeys = new ArrayList<>(commonMap.keySet());
    commonKeys.sort(utf8Order);

    List<String> properKeys = new ArrayList<>(properMap.keySet());
    properKeys.sort(utf8Order);

    ByteArrayOutputStream strPoolBaos = new ByteArrayOutputStream(262_144);
    Map<String, Integer> strOffLens = new HashMap<>(65_536);
    strOffLens.put("", 0);
    java.util.function.Function<String, Integer> internStr =
        s -> {
          if (s.isEmpty()) {
            return 0;
          }
          Integer ex = strOffLens.get(s);
          if (ex != null) {
            return ex;
          }
          byte[] b = s.getBytes(StandardCharsets.UTF_8);
          if (b.length > 255) {
            throw new IllegalStateException("Deasc string > 255 UTF-8 bytes: " + s);
          }
          int off = strPoolBaos.size();
          if (off > 0x00FF_FFFF) {
            throw new IllegalStateException("Deasc str_pool exceeded 16 MiB: " + off);
          }
          strPoolBaos.writeBytes(b);
          int ol = (off << 8) | (b.length & 0xFF);
          strOffLens.put(s, ol);
          return ol;
        };

    int[] foreignOffLens = new int[foreignSorted.size()];
    for (int i = 0; i < foreignSorted.size(); i++) {
      foreignOffLens[i] = internStr.apply(foreignSorted.get(i));
    }

    List<int[]> confTargetsSurf = new ArrayList<>(65_536);
    List<Float> confTargetsProb = new ArrayList<>(65_536);

    record ConfRow(int keyOffLen, int targetStart, int totalCount, int targetCount) {}
    List<ConfRow> commonRows = new ArrayList<>(commonKeys.size());
    for (String k : commonKeys) {
      LexiconPriors.ConfusionSet cs = commonMap.get(k);
      String[] surfs = cs.surfaces();
      float[] probs = cs.logProbs();
      if (surfs.length > 255) {
        throw new IllegalStateException("ConfusionSet target count > 255 for " + k);
      }
      int start = confTargetsSurf.size();
      for (int i = 0; i < surfs.length; i++) {
        confTargetsSurf.add(new int[] {internStr.apply(surfs[i])});
        confTargetsProb.add(probs[i]);
      }
      commonRows.add(new ConfRow(internStr.apply(k), start, Math.min(65535, cs.totalCount()), surfs.length));
    }

    List<ConfRow> properRows = new ArrayList<>(properKeys.size());
    for (String k : properKeys) {
      LexiconPriors.ConfusionSet cs = properMap.get(k);
      String[] surfs = cs.surfaces();
      float[] probs = cs.logProbs();
      if (surfs.length > 255) {
        throw new IllegalStateException("ConfusionSet target count > 255 for " + k);
      }
      int start = confTargetsSurf.size();
      for (int i = 0; i < surfs.length; i++) {
        confTargetsSurf.add(new int[] {internStr.apply(surfs[i])});
        confTargetsProb.add(probs[i]);
      }
      properRows.add(new ConfRow(internStr.apply(k), start, Math.min(65535, cs.totalCount()), surfs.length));
    }

    byte[] strPoolBytes = strPoolBaos.toByteArray();
    int totalLen =
        48
            + lemmaQwt.length
            + charQwt.length
            + sentQwt.length
            + foreignOffLens.length * 4
            + commonRows.size() * 12
            + properRows.size() * 12
            + confTargetsSurf.size() * 8
            + strPoolBytes.length;

    ByteBuffer buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN);
    buf.putInt(foreignOffLens.length);
    buf.putInt(commonRows.size());
    buf.putInt(properRows.size());
    buf.putInt(confTargetsSurf.size());
    buf.putInt(strPoolBytes.length);
    buf.putInt(lemmaQwt.length);
    buf.putInt(charQwt.length);
    buf.putInt(sentQwt.length);
    buf.putInt(0);
    buf.putInt(0);
    buf.putInt(0);
    buf.putInt(0);

    buf.put(lemmaQwt);
    buf.put(charQwt);
    buf.put(sentQwt);

    for (int ol : foreignOffLens) {
      buf.putInt(ol);
    }
    for (ConfRow r : commonRows) {
      buf.putInt(r.keyOffLen());
      buf.putInt(r.targetStart());
      buf.putShort((short) r.totalCount());
      buf.put((byte) r.targetCount());
      buf.put((byte) 0);
    }
    for (ConfRow r : properRows) {
      buf.putInt(r.keyOffLen());
      buf.putInt(r.targetStart());
      buf.putShort((short) r.totalCount());
      buf.put((byte) r.targetCount());
      buf.put((byte) 0);
    }
    for (int i = 0; i < confTargetsSurf.size(); i++) {
      buf.putInt(confTargetsSurf.get(i)[0]);
      buf.putFloat(confTargetsProb.get(i));
    }
    buf.put(strPoolBytes);

    return new DeascBundle(sentWeights.size(), buf.array());
  }

  private static String partiallyAsciify(String s, Random rng) {
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      char asc = switch (c) {
        case 'ç' -> 'c';
        case 'Ç' -> 'C';
        case 'ğ' -> 'g';
        case 'Ğ' -> 'G';
        case 'ı' -> 'i';
        case 'İ' -> 'I';
        case 'ö' -> 'o';
        case 'Ö' -> 'O';
        case 'ş' -> 's';
        case 'Ş' -> 'S';
        case 'ü' -> 'u';
        case 'Ü' -> 'U';
        case 'â' -> 'a';
        case 'Â' -> 'A';
        case 'î' -> 'i';
        case 'Î' -> 'I';
        case 'û' -> 'u';
        case 'Û' -> 'U';
        default -> c;
      };
      if (asc != c && rng.nextBoolean()) {
        sb.append(asc);
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  private static void exportStep6Parity(Path repoRoot, Path outBin) throws Exception {
    LatticeDeasciifier defDeasc = LatticeDeasciifier.createWithDefaults();
    TurkishMorphology strictMorph = TurkishMorphology.createWithDefaults();
    TurkishMorphology informalAsciiMorph =
        TurkishMorphology.builder()
            .setLexicon(RootLexicon.getDefault())
            .ignoreDiacriticsInAnalysis()
            .useInformalAnalysis()
            .build();
    LatticeDeasciifier normDeasc =
        LatticeDeasciifier.createWithDefaults(strictMorph, informalAsciiMorph)
            .withCircumflexMode(LatticeDeasciifier.CircumflexMode.NONE);

    record DeascCase(int kind, String input, String gold) {}
    List<DeascCase> cases = new ArrayList<>(900);

    // 0: Unit test & edge cases (including surrogate-pair emojis, URLs, domains, dotless-ı)
    List<String> unitInputs =
        List.of(
            "",
            "12345 !? ...",
            "Cocuklar okuldan gelene kadar evi toparlayip camasirlari balkondaki ipe asmam gerekiyor.",
            "Bu zorlu engeli asmam hic kolay olmayacak.",
            "Derin yapay sinir aglari goruntu islemede cigir acti.",
            "Modern bilgisayarlarda kullanilan en hizli bellek turu hangisidir?",
            "Bisikletciler zorlu Karadeniz turu icin sabah erkenden pedal cevirdi.",
            "Bu onemli sirri kimseye sakin soyleme!",
            "Deniz bugun her zamankinden daha sakin gorunuyordu.",
            "Aradiginiz kayit sistemde mevcut mudur?",
            "Sirketin yeni genel mudur yardimcisi bugun goreve basladi.",
            "Ruzgar sert esiyor ama hala disarida yuruyus yapan insanlar var.",
            "Sirket bu yil gecen yila gore cok daha yuksek kar elde etti.",
            "Sirketin yillik kar orani beklentileri asti.",
            "Sabahlari vadi cok sisli oluyor ama Sisli meydani kalabalikti.",
            "Polis insan avi baslatirken kameradaki AVI dosyasini inceledi.",
            "Bilgisayar acilirken BIOS ayarlarina girip RAM hizini kontrol ettim.",
            "TUIK ve TUBITAK verileri OSYM ile ISKI tarafindan incelendi.",
            "II. Dunya Savasi hakkinda https://ornek.gov.tr/basvuru/urunler adresinden @ornek_hesap ve cimcime50 yazdi.",
            "Detaylari WhatsApp'tan ve GitHub uzerinden paylastik.",
            "ABD'nin karari sonrasinda OTV'nin orani AIHM'e ve TSK'nin arsivine yansidi.",
            "Eski MP3'un sesini actik.",
            "Yarismayi 3'uncu sirada bitirip gruplara 6'sar kisi dagitarak 1923'un 100'uncu yilini kutladik.",
            "Turkiye'nin Cukurova'daki coooook guzel bahceleri.",
            "Öğrenciler kutuphanede çalışıyor.",
            "TURKIYE CUMHURIYETI MERKEZ BANKASI FAIZ KARARINI ACIKLADI.",
            "ıstanbul'da hava bugun cok guzel.",
            "ıyı gunler dılerım, nasılsınız?",
            "Cocuklara guzel bir hikaye anlatti.",
            "Eski dukkanin onunde kagit topluyordu.",
            "Bu yatirim sirket icin oldukca karli gorunuyor.",
            "Rüzgârda kâgit ucusuyor.",
            "Merhaba 😀 bugun Cukurova'da ruzgar cok sert esiyor! 🚀 sub.ornek.gov.tr ve ornek.com acik.",
            "Zorlu Zonguldak'tan gelip Kupeli'de dinlendik.");
    for (String in : unitInputs) {
      cases.add(new DeascCase(0, in, ""));
    }

    // 1: All 5 curated benchmark files (242 sentences)
    Path benchDir = repoRoot.resolve("experiment/src/test/resources/deasciifier-benchmarks");
    String[] plainGoldFiles = {
      "curated_homographs_100.txt",
      "curated_oov_typos_numerals_40.txt",
      "curated_foreign_acronyms_spans_36.txt",
      "curated_noisy_slang_clitics_36.txt"
    };
    for (String fn : plainGoldFiles) {
      Path p = benchDir.resolve(fn);
      for (String raw : Files.readAllLines(p, StandardCharsets.UTF_8)) {
        String gold = raw.trim();
        if (!gold.isEmpty() && !gold.startsWith("#")) {
          cases.add(new DeascCase(1, TurkishTextCodec.asciify(gold), gold));
        }
      }
    }
    Path allcapsPath = benchDir.resolve("curated_allcaps_and_turkish_locale_lower_30.txt");
    for (String raw : Files.readAllLines(allcapsPath, StandardCharsets.UTF_8)) {
      String line = raw.trim();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      int tab = line.indexOf('\t');
      if (tab > 0) {
        cases.add(new DeascCase(1, line.substring(0, tab), line.substring(tab + 1)));
      }
    }

    // 2: 500 partial-ASCII safety sentences
    Random rng = new Random(0x5EED_57E6L);
    int partialCount = 0;
    for (String raw :
        Files.readAllLines(
            repoRoot.resolve("tokenization/src/test/resources/tokenization/Sentence-Boundary-Test.txt"),
            StandardCharsets.UTF_8)) {
      for (String sent : TurkishSentenceExtractor.DEFAULT.fromParagraph(raw.replace("|", " "))) {
        String clean = sent.trim();
        if (clean.length() >= 12 && partialCount < 500) {
          cases.add(new DeascCase(2, partiallyAsciify(clean, rng), clean));
          partialCount++;
        }
      }
    }
    Path freqWords = repoRoot.resolve("normalization/src/test/resources/10000_frequent_turkish_word");
    List<String> fwLines = Files.readAllLines(freqWords, StandardCharsets.UTF_8);
    int fwIdx = 0;
    while (partialCount < 500 && fwIdx + 6 < fwLines.size()) {
      StringBuilder sb = new StringBuilder();
      for (int k = 0; k < 6; k++) {
        if (k > 0) sb.append(' ');
        String w = fwLines.get(fwIdx++).trim();
        sb.append(k == 0 ? Turkish.capitalize(w) : w);
      }
      sb.append('.');
      String gold = sb.toString();
      cases.add(new DeascCase(2, partiallyAsciify(gold, rng), gold));
      partialCount++;
    }

    try (DataOutputStream dos =
        new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(outBin), 1 << 20))) {
      dos.writeInt(cases.size());
      for (DeascCase c : cases) {
        dos.writeByte(c.kind());
        writeStr(dos, c.input());
        writeStr(dos, c.gold());
        writeStr(dos, defDeasc.deasciify(c.input(), LatticeDeasciifier.CircumflexMode.DISAMBIGUATION_ONLY));
        writeStr(dos, defDeasc.deasciify(c.input(), LatticeDeasciifier.CircumflexMode.NONE));
        writeStr(dos, defDeasc.deasciify(c.input(), LatticeDeasciifier.CircumflexMode.FULL));
        writeStr(dos, defDeasc.deasciifyKnownWords(c.input()));
        writeStr(dos, normDeasc.deasciify(c.input()));
      }
    }

    System.out.printf(
        "Exported Step 6 parity fixture: %s (%,d B = %.2f MiB, totalCases=%,d, partialAscii=%,d)%n",
        outBin,
        Files.size(outBin),
        Files.size(outBin) / (1024.0 * 1024.0),
        cases.size(),
        partialCount);
  }

  @SuppressWarnings("unchecked")
  private static byte[] buildNormKnowledgeSection(
      NormalizationKnowledge nk, SpanLatticePerceptronNormalizer normNoSmooth) throws Exception {
    Comparator<String> utf8Order =
        (a, b) ->
            Arrays.compareUnsigned(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));

    // 1. CharPhonotacticModel codebook + 2 x 65,536 u8 arrays
    int[] n4 = (int[]) getField(nk.charPhonotactics, "ngram4Counts");
    int[] p3 = (int[]) getField(nk.charPhonotactics, "prefix3Counts");
    if (n4.length != 65536 || p3.length != 65536) {
      throw new IllegalStateException("Unexpected CharPhonotacticModel table size: " + n4.length);
    }
    TreeSet<Integer> distinctPhono = new TreeSet<>();
    distinctPhono.add(0);
    for (int v : n4) distinctPhono.add(v);
    for (int v : p3) distinctPhono.add(v);
    if (distinctPhono.size() > 256) {
      throw new IllegalStateException("CharPhonotacticModel distinct values > 256: " + distinctPhono.size());
    }
    int[] phonoCb = new int[256];
    Map<Integer, Integer> phonoCodeMap = new HashMap<>(512);
    int cbIdx = 0;
    for (int v : distinctPhono) {
      phonoCodeMap.put(v, cbIdx);
      phonoCb[cbIdx++] = v;
    }
    byte[] phonoQ4 = new byte[65536];
    byte[] phonoQ3 = new byte[65536];
    for (int i = 0; i < 65536; i++) {
      phonoQ4[i] = (byte) phonoCodeMap.get(n4[i]).intValue();
      phonoQ3[i] = (byte) phonoCodeMap.get(p3[i]).intValue();
    }

    // 2. Extract precomputed consonantSkeletonIndex directly from ShorthandExpander
    Object shorthandExp = getField(normNoSmooth.generator(), "shorthandExpander");
    Map<String, String[]> skelMap =
        (Map<String, String[]>) getField(shorthandExp, "consonantSkeletonIndex");

    ByteArrayOutputStream strPoolBaos = new ByteArrayOutputStream(3 * 1024 * 1024);
    Map<String, Integer> strOffLens = new HashMap<>(262_144);
    strOffLens.put("", 0);
    java.util.function.Function<String, Integer> internStr =
        s -> {
          if (s == null || s.isEmpty()) {
            return 0;
          }
          Integer ex = strOffLens.get(s);
          if (ex != null) {
            return ex;
          }
          byte[] b = s.getBytes(StandardCharsets.UTF_8);
          if (b.length > 255) {
            throw new IllegalStateException("NormKnowledge string > 255 UTF-8 bytes (" + b.length + "): " + s);
          }
          int off = strPoolBaos.size();
          if (off > 0x00FF_FFFF) {
            throw new IllegalStateException("NormKnowledge str_pool > 16 MiB: " + off);
          }
          strPoolBaos.writeBytes(b);
          int ol = (off << 8) | (b.length & 0xFF);
          strOffLens.put(s, ol);
          return ol;
        };

    List<int[]> targetPool = new ArrayList<>(131_072); // [normOffLen, count]

    // parallel1ToN (preserving ALL entries including count == 1 singletons!)
    List<String> p1Keys = new ArrayList<>(nk.parallel1ToN.keySet());
    p1Keys.sort(utf8Order);
    List<int[]> p1Rows = new ArrayList<>(p1Keys.size());
    for (String k : p1Keys) {
      if (k.getBytes(StandardCharsets.UTF_8).length > 255) continue;
      NormalizationKnowledge.RewriteEntry re = nk.parallel1ToN.get(k);
      NormalizationKnowledge.TargetCount[] tcs = re.targets();
      int start = targetPool.size();
      int kept = 0;
      for (NormalizationKnowledge.TargetCount tc : tcs) {
        if (tc.normLower().getBytes(StandardCharsets.UTF_8).length > 255) continue;
        targetPool.add(new int[] {internStr.apply(tc.normLower()), tc.count()});
        kept++;
        if (kept == 255) break;
      }
      if (start > 0x00FF_FFFF) {
        throw new IllegalStateException("targetPool exceeded 24-bit index: " + start);
      }
      int packedSpan = (start & 0x00FF_FFFF) | ((kept & 0xFF) << 24);
      p1Rows.add(new int[] {internStr.apply(k), re.totalCount(), re.keepCount(), packedSpan});
    }

    // parallel2To1
    List<String> p2Keys = new ArrayList<>(nk.parallel2To1.keySet());
    p2Keys.sort(utf8Order);
    List<int[]> p2Rows = new ArrayList<>(p2Keys.size());
    for (String k : p2Keys) {
      if (k.getBytes(StandardCharsets.UTF_8).length > 255) continue;
      NormalizationKnowledge.MergeEntry me = nk.parallel2To1.get(k);
      int start = targetPool.size();
      int kept = 0;
      for (NormalizationKnowledge.TargetCount tc : me.mergedTargets()) {
        if (tc.normLower().getBytes(StandardCharsets.UTF_8).length > 255) continue;
        targetPool.add(new int[] {internStr.apply(tc.normLower()), tc.count()});
        kept++;
        if (kept == 255) break;
      }
      int packedSpan = (start & 0x00FF_FFFF) | ((kept & 0xFF) << 24);
      p2Rows.add(new int[] {internStr.apply(k), me.cooccurCount(), packedSpan});
    }

    // parallel3To1
    List<String> p3Keys = new ArrayList<>(nk.parallel3To1.keySet());
    p3Keys.sort(utf8Order);
    List<int[]> p3Rows = new ArrayList<>(p3Keys.size());
    for (String k : p3Keys) {
      if (k.getBytes(StandardCharsets.UTF_8).length > 255) continue;
      NormalizationKnowledge.MergeEntry me = nk.parallel3To1.get(k);
      int start = targetPool.size();
      int kept = 0;
      for (NormalizationKnowledge.TargetCount tc : me.mergedTargets()) {
        if (tc.normLower().getBytes(StandardCharsets.UTF_8).length > 255) continue;
        targetPool.add(new int[] {internStr.apply(tc.normLower()), tc.count()});
        kept++;
        if (kept == 255) break;
      }
      int packedSpan = (start & 0x00FF_FFFF) | ((kept & 0xFF) << 24);
      p3Rows.add(new int[] {internStr.apply(k), me.cooccurCount(), packedSpan});
    }

    // suffixRulesByLastChar
    record SufBucket(int lastChar, List<NormalizationKnowledge.SuffixRule> rules) {}
    List<SufBucket> sufBuckets = new ArrayList<>(nk.suffixRulesByLastChar.size());
    for (var e : nk.suffixRulesByLastChar.entrySet()) {
      if (!e.getKey().isEmpty()) {
        sufBuckets.add(new SufBucket(e.getKey().codePointAt(0), e.getValue()));
      }
    }
    sufBuckets.sort(Comparator.comparingInt(SufBucket::lastChar));
    List<int[]> sufBucketRows = new ArrayList<>(sufBuckets.size());
    record SufRuleRow(int rawOl, int normOl, int support, float prec) {}
    List<SufRuleRow> sufRuleRows = new ArrayList<>(1024);
    for (SufBucket sb : sufBuckets) {
      int start = sufRuleRows.size();
      int cnt = sb.rules().size();
      if (start > 65535 || cnt > 65535) {
        throw new IllegalStateException("SuffixRule 16-bit overflow");
      }
      for (NormalizationKnowledge.SuffixRule sr : sb.rules()) {
        sufRuleRows.add(
            new SufRuleRow(
                internStr.apply(sr.rawSuffix()),
                internStr.apply(sr.normSuffix()),
                sr.support(),
                sr.precision()));
      }
      sufBucketRows.add(new int[] {sb.lastChar(), (start & 0xFFFF) | ((cnt & 0xFFFF) << 16)});
    }

    // casingLexicon
    List<String> casingKeys = new ArrayList<>(nk.casingLexicon.keySet());
    casingKeys.sort(utf8Order);
    List<int[]> casingRows = new ArrayList<>(casingKeys.size());
    for (String k : casingKeys) {
      if (k.getBytes(StandardCharsets.UTF_8).length > 255) continue;
      NormalizationKnowledge.CasingCounts cc = nk.casingLexicon.get(k);
      int bestMixedOl =
          (cc.bestMixedSurface != null
                  && cc.bestMixedSurface.getBytes(StandardCharsets.UTF_8).length <= 255)
              ? internStr.apply(cc.bestMixedSurface)
              : 0;
      casingRows.add(
          new int[] {
            internStr.apply(k),
            cc.lower,
            cc.title,
            cc.upper,
            cc.mixed,
            cc.apostropheStem,
            bestMixedOl
          });
    }

    // consonantSkeletonIndex
    List<String> skelKeys = new ArrayList<>(skelMap.keySet());
    skelKeys.sort(utf8Order);
    List<int[]> skelRows = new ArrayList<>(skelKeys.size());
    List<Integer> skelTargets = new ArrayList<>(skelKeys.size() * 2);
    for (String sk : skelKeys) {
      if (sk.getBytes(StandardCharsets.UTF_8).length > 255) continue;
      String[] cands = skelMap.get(sk);
      int start = skelTargets.size();
      int kept = 0;
      for (String w : cands) {
        if (w.getBytes(StandardCharsets.UTF_8).length > 255) continue;
        skelTargets.add(internStr.apply(w));
        kept++;
        if (kept == 255) break;
      }
      skelRows.add(new int[] {internStr.apply(sk), (start & 0x00FF_FFFF) | ((kept & 0xFF) << 24)});
    }

    byte[] strPoolBytes = strPoolBaos.toByteArray();
    int totalLen =
        64
            + 256 * 4
            + p1Rows.size() * 16
            + p2Rows.size() * 12
            + p3Rows.size() * 12
            + targetPool.size() * 8
            + sufBucketRows.size() * 8
            + sufRuleRows.size() * 16
            + casingRows.size() * 28
            + skelRows.size() * 8
            + skelTargets.size() * 4
            + 65536
            + 65536
            + strPoolBytes.length;

    ByteBuffer buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN);
    buf.putInt(p1Rows.size());
    buf.putInt(p2Rows.size());
    buf.putInt(p3Rows.size());
    buf.putInt(targetPool.size());
    buf.putInt(sufBucketRows.size());
    buf.putInt(sufRuleRows.size());
    buf.putInt(casingRows.size());
    buf.putInt(skelRows.size());
    buf.putInt(skelTargets.size());
    buf.putInt(strPoolBytes.length);
    for (int i = 0; i < 6; i++) buf.putInt(0);

    for (int v : phonoCb) buf.putInt(v);
    for (int[] r : p1Rows) {
      buf.putInt(r[0]).putInt(r[1]).putInt(r[2]).putInt(r[3]);
    }
    for (int[] r : p2Rows) {
      buf.putInt(r[0]).putInt(r[1]).putInt(r[2]);
    }
    for (int[] r : p3Rows) {
      buf.putInt(r[0]).putInt(r[1]).putInt(r[2]);
    }
    for (int[] t : targetPool) {
      buf.putInt(t[0]).putInt(t[1]);
    }
    for (int[] b : sufBucketRows) {
      buf.putInt(b[0]).putInt(b[1]);
    }
    for (SufRuleRow r : sufRuleRows) {
      buf.putInt(r.rawOl()).putInt(r.normOl()).putInt(r.support()).putFloat(r.prec());
    }
    for (int[] c : casingRows) {
      for (int v : c) buf.putInt(v);
    }
    for (int[] s : skelRows) {
      buf.putInt(s[0]).putInt(s[1]);
    }
    for (int ol : skelTargets) {
      buf.putInt(ol);
    }
    buf.put(phonoQ4);
    buf.put(phonoQ3);
    buf.put(strPoolBytes);
    return buf.array();
  }

  private static byte[] buildNormPerceptronSection(SpanLatticePerceptronNormalizer normNoSmooth)
      throws Exception {
    float[] denseWeights = (float[]) getField(normNoSmooth, "denseWeights");
    QuantizedWeightTable sparseWeights =
        (QuantizedWeightTable) getField(normNoSmooth, "sparseWeights");
    byte[] sparseBytes = serializeAlignedQwtPadded8(sparseWeights);
    ByteBuffer buf = ByteBuffer.allocate(80 + sparseBytes.length).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < 20; i++) {
      buf.putFloat(denseWeights[i]);
    }
    buf.put(sparseBytes);
    return buf.array();
  }

  private static String extractJsonStrField(String line, String key) {
    String pat = "\"" + key + "\": \"";
    int idx = line.indexOf(pat);
    if (idx < 0) {
      pat = "\"" + key + "\":\"";
      idx = line.indexOf(pat);
      if (idx < 0) return null;
    }
    int start = idx + pat.length();
    StringBuilder sb = new StringBuilder();
    for (int i = start; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '\\' && i + 1 < line.length()) {
        char nx = line.charAt(++i);
        switch (nx) {
          case '"' -> sb.append('"');
          case '\\' -> sb.append('\\');
          case 'n' -> sb.append('\n');
          case 't' -> sb.append('\t');
          default -> sb.append(nx);
        }
      } else if (c == '"') {
        return sb.toString();
      } else {
        sb.append(c);
      }
    }
    return null;
  }

  private static void exportStep7Parity(
      Path repoRoot, Path outBin, SpanLatticePerceptronNormalizer norm) throws Exception {
    LinkedHashSet<String> inputs =
        new LinkedHashSet<>(
            List.of(
                "",
                "12345 ...",
                "Slm bugun geliyomusun?",
                "Slm bugun geliyomusun :)",
                "Bu sistem alınır mi?",
                "normal mi",
                "yarın bende gelicem ama saat 7 de orda olurmusun bilmiyom",
                "Çok güzel :D Sitedeki ürünler 16-18 mayıs",
                "ISTANBUL'DA hava cok guzel yarin kutuphaneye gidicem gelecekmisiniz",
                "Merhaba 😀 bugun kutuphaneye gidicem gelecekmisiniz!",
                "16gb ram ve 5000mah batarya 300mbps hizinda 2024te alindi.",
                "annecim nerdesin geliyomuş dediler almiyim ben tskler",
                "bende gelebilirmiyim acaba birsey sorcam napcaz simdi",
                "Ali'de geldi ama azerice'ye ve altın'ın degerine bakti.",
                "DindarıSolcusu herkes haftasonunda akpli veya chpli tartisiyor.",
                "ba$kan gelmi$mi acaba 3 . sirada tv8e cikti"));

    Path miniNoisy =
        repoRoot.resolve("normalization/src/test/resources/normalization/mini-noisy-corpus.txt");
    if (Files.exists(miniNoisy)) {
      for (String raw : Files.readAllLines(miniNoisy, StandardCharsets.UTF_8)) {
        String s = raw.trim();
        if (!s.isEmpty() && s.length() <= 400) {
          inputs.add(s);
        }
      }
    }

    Path testSlice =
        Paths.get(
            System.getProperty("user.home")
                + "/data/turkish/normalization/noisy_tr_v2/full_110k/general/llm_gold/normalized/test_slice_01_normalized.jsonl");
    if (Files.exists(testSlice)) {
      try (var br = Files.newBufferedReader(testSlice, StandardCharsets.UTF_8)) {
        String line;
        int added = 0;
        while ((line = br.readLine()) != null && added < 550) {
          String raw = extractJsonStrField(line, "text");
          if (raw != null && !raw.isEmpty() && raw.length() <= 400 && inputs.add(raw)) {
            added++;
          }
        }
      }
    }

    Path nastyTsv =
        Paths.get(
            System.getProperty("user.home")
                + "/data/turkish/normalization/custom_benchmark/nasty_5k/llm_gold/exports/nasty_5000_pairs.tsv");
    if (Files.exists(nastyTsv)) {
      try (var br = Files.newBufferedReader(nastyTsv, StandardCharsets.UTF_8)) {
        br.readLine();
        String line;
        int added = 0;
        while ((line = br.readLine()) != null && added < 500) {
          String[] cols = line.split("\t", -1);
          if (cols.length >= 3 && !cols[1].isEmpty() && cols[1].length() <= 400) {
            if (inputs.add(cols[1])) {
              added++;
            }
          }
        }
      }
    }

    // Fallback synthetic noisy sentences if < 1,020 inputs collected
    if (inputs.size() < 1_020) {
      Random rng = new Random(0x5EED_57E7L);
      for (String raw :
          Files.readAllLines(
              repoRoot.resolve("tokenization/src/test/resources/tokenization/Sentence-Boundary-Test.txt"),
              StandardCharsets.UTF_8)) {
        for (String sent : TurkishSentenceExtractor.DEFAULT.fromParagraph(raw.replace("|", " "))) {
          if (inputs.size() >= 1_050) break;
          inputs.add(partiallyAsciify(sent.trim(), rng));
        }
      }
    }

    try (DataOutputStream dos =
        new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(outBin), 1 << 20))) {
      dos.writeInt(inputs.size());
      for (String raw : inputs) {
        String normText = norm.normalize(raw);
        NormV2Types.SentenceLattice lat = norm.generator().buildLattice(raw);
        List<NormV2Types.SpanArc> path = norm.decodeLattice(lat);
        writeStr(dos, raw);
        writeStr(dos, normText);
        dos.writeShort(path.size());
        for (NormV2Types.SpanArc a : path) {
          dos.writeShort(a.startIdx);
          dos.writeShort(a.endIdx);
          writeStr(dos, a.normSurface);
        }
      }
    }

    System.out.printf(
        "Exported Step 7 parity fixture: %s (%,d B = %.2f MiB, sentences=%,d)%n",
        outBin,
        Files.size(outBin),
        Files.size(outBin) / (1024.0 * 1024.0),
        inputs.size());
  }

  private static void writeStr(DataOutputStream dos, String s) throws IOException {
    byte[] b = s.getBytes(StandardCharsets.UTF_8);
    if (b.length > 65535) {
      throw new IllegalStateException("String UTF-8 > 65535 bytes: " + b.length);
    }
    dos.writeShort(b.length);
    dos.write(b);
  }
}
