import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;
import zemberek.core.hash.MultiLevelMphf;
import zemberek.core.turkish.RootAttribute;
import zemberek.morphology.lexicon.DictionaryItem;
import zemberek.morphology.lexicon.RootLexicon;
import zemberek.normalization.deasciifier.latticedeasciifier.QuantizedWeightTable;

/**
 * Step 0 `.zmbk` exporter: compiles a real miniature slice of Zemberek's {@link RootLexicon} plus a
 * real {@link QuantizedWeightTable} ({@link MultiLevelMphf} + 16-bit fingerprints + 256-centroid
 * codebook) into a little-endian, 64-byte-aligned `.zmbk` file and emits golden parity vectors.
 */
public final class ExportStep0Zmbk {

  private static final int SECT_LEXICON = 1;
  private static final int SECT_QWT_MPHF = 2;
  private static final int ALIGN = 64;

  public static void main(String[] args) throws Exception {
    Path outDir = Path.of(args.length > 0 ? args[0] : "rs/testdata");
    Files.createDirectories(outDir);

    RootLexicon lexicon = RootLexicon.getDefault();
    List<DictionaryItem> selected = selectRepresentativeLexiconSlice(lexicon);

    // Build shared deduplicated UTF-8 string pool + PackedDictItem[N]
    Map<String, int[]> poolOffsets = new LinkedHashMap<>();
    ByteArrayOutputStream utf8Pool = new ByteArrayOutputStream();
    for (DictionaryItem it : selected) {
      if (!poolOffsets.containsKey(it.lemma)) {
        byte[] b = it.lemma.getBytes(StandardCharsets.UTF_8);
        if (b.length > 255) {
          throw new IllegalStateException("Lemma exceeds 255 UTF-8 bytes: " + it.lemma);
        }
        int off = utf8Pool.size();
        utf8Pool.write(b);
        poolOffsets.put(it.lemma, new int[] {off, b.length});
      }
    }
    byte[] poolBytes = utf8Pool.toByteArray();

    ByteBuffer lexBuf =
        ByteBuffer.allocate(16 + selected.size() * 12 + poolBytes.length)
            .order(ByteOrder.LITTLE_ENDIAN);
    lexBuf.putInt(selected.size());
    lexBuf.putInt(poolBytes.length);
    lexBuf.putInt(0);
    lexBuf.putInt(0);

    int[] rootAttrMasks = new int[selected.size()];
    for (int i = 0; i < selected.size(); i++) {
      DictionaryItem it = selected.get(i);
      int[] offLen = poolOffsets.get(it.lemma);
      int packedOffLen = (offLen[0] << 8) | (offLen[1] & 0xFF);
      int attrMask = 0;
      for (RootAttribute ra : it.attributes) {
        attrMask |= (1 << ra.ordinal());
      }
      rootAttrMasks[i] = attrMask;
      int flags = (it.index & 0x0F) | (it.getReferenceItem() != null ? 0x80 : 0);

      lexBuf.putInt(packedOffLen);
      lexBuf.putInt(attrMask);
      lexBuf.put((byte) it.primaryPos.ordinal());
      lexBuf.put((byte) it.secondaryPos.ordinal());
      lexBuf.put((byte) 0);
      lexBuf.put((byte) flags);
    }
    lexBuf.put(poolBytes);
    byte[] lexSection = lexBuf.array();

    // Build a real 1,024-feature QuantizedWeightTable (spans multiple MPHF displacement levels)
    int nKeys = 1024;
    long[] keys = new long[nKeys];
    float[] vals = new float[nKeys];
    Random rng = new Random(0x5EED_2026L);
    for (int i = 0; i < nKeys; i++) {
      long k = rng.nextLong();
      if (k == 0L) k = i + 1L;
      keys[i] = k;
      vals[i] = (float) (rng.nextGaussian() * 2.75);
    }
    QuantizedWeightTable qwt = QuantizedWeightTable.build(keys, vals, nKeys);
    byte[] qwtSection = serializeAlignedQwtSection(qwt);

    // Assemble 64-byte-aligned .zmbk container
    int headerAndDir = 64 + 2 * 32; // 128 bytes (exact multiple of 64)
    int sec1Off = alignUp(headerAndDir, ALIGN);
    int sec2Off = alignUp(sec1Off + lexSection.length, ALIGN);
    int totalBytes = alignUp(sec2Off + qwtSection.length, ALIGN);

    ByteBuffer zmbk = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN);
    // Header (64 bytes)
    zmbk.put(new byte[] {'Z', 'M', 'B', 'K'});
    zmbk.putShort((short) 1); // version
    zmbk.putShort((short) 0x0102); // endian_tag
    zmbk.putInt(2); // section_count
    zmbk.putInt(totalBytes);
    zmbk.putInt(0); // flags
    zmbk.position(64);

    // Section 1 directory entry (32 bytes)
    zmbk.putInt(SECT_LEXICON);
    zmbk.putInt(0);
    zmbk.putLong(sec1Off);
    zmbk.putLong(lexSection.length);
    zmbk.putInt(selected.size());
    zmbk.putInt(crc32(lexSection));

    // Section 2 directory entry (32 bytes)
    zmbk.putInt(SECT_QWT_MPHF);
    zmbk.putInt(0);
    zmbk.putLong(sec2Off);
    zmbk.putLong(qwtSection.length);
    zmbk.putInt(qwt.size());
    zmbk.putInt(crc32(qwtSection));

    zmbk.position(sec1Off);
    zmbk.put(lexSection);
    zmbk.position(sec2Off);
    zmbk.put(qwtSection);

    Path zmbkPath = outDir.resolve("step0_mini.zmbk");
    Files.write(zmbkPath, zmbk.array());

    // Emit golden TSV vectors (DICT rows + QWT present & unseen OOV rows)
    StringBuilder tsv = new StringBuilder();
    for (int i = 0; i < selected.size(); i++) {
      DictionaryItem it = selected.get(i);
      tsv.append("DICT\t")
          .append(i)
          .append('\t')
          .append(it.lemma)
          .append('\t')
          .append(Integer.toUnsignedString(rootAttrMasks[i]))
          .append('\t')
          .append(it.primaryPos.ordinal())
          .append('\t')
          .append(it.secondaryPos.ordinal())
          .append('\n');
    }
    for (int i = 0; i < nKeys; i++) {
      float w = qwt.get(keys[i]);
      tsv.append("QWT\t")
          .append(Long.toUnsignedString(keys[i]))
          .append('\t')
          .append(Integer.toUnsignedString(Float.floatToRawIntBits(w)))
          .append('\n');
    }
    for (int i = 0; i < 256; i++) {
      long oov = rng.nextLong() ^ 0xA5A5_5A5A_F0F0_0F0FL;
      float w = qwt.get(oov);
      tsv.append("QWT\t")
          .append(Long.toUnsignedString(oov))
          .append('\t')
          .append(Integer.toUnsignedString(Float.floatToRawIntBits(w)))
          .append('\n');
    }
    Path tsvPath = outDir.resolve("step0_expected.tsv");
    Files.writeString(tsvPath, tsv.toString(), StandardCharsets.UTF_8);

    System.out.printf(
        "Exported %s (%d bytes): %d dict items (%d B UTF-8 pool), %d QWT keys%n",
        zmbkPath, totalBytes, selected.size(), poolBytes.length, qwt.size());
  }

  private static List<DictionaryItem> selectRepresentativeLexiconSlice(RootLexicon lexicon) {
    String[] targetIds = {
      "kitap_Noun",
      "ağaç_Noun",
      "gelmek_Verb",
      "demek_Verb",
      "su_Noun",
      "Türkiye_Noun_Prop",
      "Tübitak_Noun_Abbrv",
      "saat_Noun",
      "kâr_Noun",
      "kar_Noun",
      "hâlâ_Adv",
      "ben_Pron_Pers",
      "şehir_Noun",
      "hak_Noun"
    };
    List<DictionaryItem> out = new ArrayList<>(64);
    for (String id : targetIds) {
      DictionaryItem item = lexicon.getItemById(id);
      if (item != null && !out.contains(item)) {
        out.add(item);
      }
    }
    int step = Math.max(1, lexicon.size() / 64);
    int idx = 0;
    for (DictionaryItem item : lexicon) {
      if (out.size() >= 64) break;
      if (idx % step == 0 && !out.contains(item)) {
        out.add(item);
      }
      idx++;
    }
    return out;
  }

  private static byte[] serializeAlignedQwtSection(QuantizedWeightTable qwt) throws Exception {
    Field fMphf = QuantizedWeightTable.class.getDeclaredField("mphf");
    Field fFp = QuantizedWeightTable.class.getDeclaredField("fingerprints");
    Field fCodes = QuantizedWeightTable.class.getDeclaredField("codes");
    Field fCb = QuantizedWeightTable.class.getDeclaredField("codebook");
    fMphf.setAccessible(true);
    fFp.setAccessible(true);
    fCodes.setAccessible(true);
    fCb.setAccessible(true);

    MultiLevelMphf mphf = (MultiLevelMphf) fMphf.get(qwt);
    short[] fingerprints = (short[]) fFp.get(qwt);
    byte[] codes = (byte[]) fCodes.get(qwt);
    float[] codebook = (float[]) fCb.get(qwt);

    Field fLevels = MultiLevelMphf.class.getDeclaredField("hashLevelData");
    fLevels.setAccessible(true);
    Object[] levels = (Object[]) fLevels.get(mphf);

    int levelCount = levels.length;
    int[] keyAmounts = new int[levelCount];
    int[] bucketAmounts = new int[levelCount];
    byte[][] seedArrays = new byte[levelCount][];
    int[][] failedArrays = new int[levelCount][];

    int totalFailed = 0;
    int totalSeeds = 0;
    for (int i = 0; i < levelCount; i++) {
      Object lvl = levels[i];
      Class<?> lc = lvl.getClass();
      Field fk = lc.getDeclaredField("keyAmount");
      Field fb = lc.getDeclaredField("bucketAmount");
      Field fs = lc.getDeclaredField("bucketHashSeedValues");
      Field ff = lc.getDeclaredField("failedIndexes");
      fk.setAccessible(true);
      fb.setAccessible(true);
      fs.setAccessible(true);
      ff.setAccessible(true);

      keyAmounts[i] = fk.getInt(lvl);
      bucketAmounts[i] = fb.getInt(lvl);
      seedArrays[i] = (byte[]) fs.get(lvl);
      failedArrays[i] = (int[]) ff.get(lvl);
      totalSeeds += seedArrays[i].length;
      totalFailed += failedArrays[i].length;
    }

    int count = codes.length;
    int totalBytes =
        16
            + (256 * 4)
            + (levelCount * 24)
            + (totalFailed * 4)
            + (count * 2)
            + count
            + totalSeeds;

    ByteBuffer buf = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN);
    // QwtSectionHeader (16B)
    buf.putInt(count);
    buf.putInt(levelCount);
    buf.putInt(totalFailed);
    buf.putInt(totalSeeds);

    // Codebook (256 * 4B)
    for (int i = 0; i < 256; i++) {
      buf.putFloat(codebook[i]);
    }

    // MphfLevelDesc[levelCount] (24B each)
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

    // Concatenated failedIndexes (u32[])
    for (int i = 0; i < levelCount; i++) {
      for (int idx : failedArrays[i]) {
        buf.putInt(idx);
      }
    }

    // Fingerprints (u16[count])
    for (short fp : fingerprints) {
      buf.putShort(fp);
    }

    // Codes (u8[count])
    buf.put(codes);

    // Concatenated seeds (u8[])
    for (int i = 0; i < levelCount; i++) {
      buf.put(seedArrays[i]);
    }

    return buf.array();
  }

  private static int alignUp(int val, int align) {
    return (val + align - 1) & ~(align - 1);
  }

  private static int crc32(byte[] data) {
    CRC32 crc = new CRC32();
    crc.update(data);
    return (int) crc.getValue();
  }
}
