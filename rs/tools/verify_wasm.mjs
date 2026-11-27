import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { performance } from "node:perf_hooks";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(__dirname, "..");
const wasmReleasePath = path.join(
  rootDir,
  "target/wasm32-unknown-unknown/wasm-release/zemberek_wasm.wasm"
);
const wasmFallbackPath = path.join(
  rootDir,
  "target/wasm32-unknown-unknown/release/zemberek_wasm.wasm"
);
const wasmPath = fs.existsSync(wasmReleasePath) ? wasmReleasePath : wasmFallbackPath;
const step0ZmbkPath = path.join(rootDir, "testdata/step0_mini.zmbk");
const step0TsvPath = path.join(rootDir, "testdata/step0_expected.tsv");
const liteZmbkPath = path.join(rootDir, "testdata/zemberek_lite.zmbk");
const studioZmbkPath = path.join(rootDir, "testdata/zemberek_step2.zmbk");

const encoder = new TextEncoder();
const decoder = new TextDecoder("utf-8");

function makeChunkedStream(bytes, chunkSize = 65536) {
  let offset = 0;
  return new ReadableStream({
    pull(controller) {
      if (offset >= bytes.length) {
        controller.close();
        return;
      }
      const end = Math.min(bytes.length, offset + chunkSize);
      controller.enqueue(bytes.subarray(offset, end));
      offset = end;
    },
  });
}

async function streamIntoWasm(exports, bytes, verifyCrc = 1) {
  const ptr = exports.zmbk_alloc_pack(bytes.length) >>> 0;
  assert.notEqual(ptr, 0, "zmbk_alloc_pack returned null");
  assert.equal(ptr % 64, 0, "zmbk_alloc_pack must be 64-byte aligned");

  const reader = makeChunkedStream(bytes, 64 * 1024).getReader();
  let written = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    new Uint8Array(exports.memory.buffer, ptr + written, value.byteLength).set(value);
    written += value.byteLength;
  }
  assert.equal(written, bytes.length, "streamed byte count mismatch");

  const t0 = performance.now();
  const status = exports.zmbk_init(ptr, bytes.length, verifyCrc) >>> 0;
  const initMs = performance.now() - t0;
  assert.equal(status, 0, `zmbk_init failed with status=${status}`);
  return initMs;
}

function writeInput(exports, text) {
  const ptr = exports.zmbk_input_ptr() >>> 0;
  const cap = exports.zmbk_input_cap() >>> 0;
  const dst = new Uint8Array(exports.memory.buffer, ptr, cap);
  const { read, written } = encoder.encodeInto(text, dst);
  assert.equal(read, text.length, "input overflowed scratch buffer");
  return written;
}

function decodeIssues(exports, outLen) {
  assert.ok(outLen >= 4, `unexpected zmbk_check_paragraph status=${outLen}`);
  const outPtr = exports.zmbk_output_ptr() >>> 0;
  const view = new DataView(exports.memory.buffer, outPtr, outLen);
  const bytes = new Uint8Array(exports.memory.buffer, outPtr, outLen);
  const count = view.getUint32(0, true);
  const issues = [];
  let pos = 4;
  for (let i = 0; i < count; i++) {
    const utf16Start = view.getUint32(pos, true);
    const utf16End = view.getUint32(pos + 4, true);
    const utf8Start = view.getUint32(pos + 8, true);
    const utf8End = view.getUint32(pos + 12, true);
    const kind = view.getUint8(pos + 16);
    const sugCount = view.getUint8(pos + 17);
    pos += 20;
    const suggestions = [];
    for (let s = 0; s < sugCount; s++) {
      const sLen = view.getUint16(pos, true);
      pos += 2;
      suggestions.push(decoder.decode(bytes.subarray(pos, pos + sLen)));
      pos += sLen;
    }
    issues.push({
      utf16Start,
      utf16End,
      utf8Start,
      utf8End,
      kind,
      replacement: suggestions[0],
      suggestions,
    });
  }
  return issues;
}

function readOutStr(exports, outLen) {
  assert.ok(outLen >= 0, `negative output length: ${outLen}`);
  const outPtr = exports.zmbk_output_ptr() >>> 0;
  return decoder.decode(new Uint8Array(exports.memory.buffer, outPtr, outLen));
}

import zlib from "node:zlib";

const wasmBytes = fs.readFileSync(wasmPath);
const wasmGzipBytes = zlib.gzipSync(wasmBytes);
assert.ok(
  wasmBytes.length <= 1100 * 1024 && wasmGzipBytes.length <= 400 * 1024,
  `Release .wasm (${wasmBytes.length} B raw, ${wasmGzipBytes.length} B gzip) exceeded size gate!`
);

// 1. Cold compile + warm cached WebAssembly.Module instantiate timing
const tCold0 = performance.now();
const compiledModule = await WebAssembly.compile(wasmBytes);
const coldCompileMs = performance.now() - tCold0;

const tInst0 = performance.now();
const instance = await WebAssembly.instantiate(compiledModule, {});
const instantiateMs = performance.now() - tInst0;
const exports = instance.exports;

// 2. Backwards compatibility check on Step 0 mini pack (if present)
if (fs.existsSync(step0ZmbkPath) && fs.existsSync(step0TsvPath)) {
  const miniBytes = fs.readFileSync(step0ZmbkPath);
  await streamIntoWasm(exports, miniBytes, 1);
  const lines = fs.readFileSync(step0TsvPath, "utf8").trim().split("\n");
  for (const line of lines.slice(0, 64)) {
    const cols = line.split("\t");
    if (cols[0] === "DICT") {
      const idx = Number(cols[1]);
      const span = BigInt.asUintN(64, exports.zmbk_dict_lemma_span(idx));
      const ptr = Number(span & 0xffffffffn);
      const len = Number(span >> 32n);
      const got = decoder.decode(new Uint8Array(exports.memory.buffer, ptr, len));
      assert.equal(got, cols[2]);
    }
  }
}

// 3. Core Lite pack verification (<= 4.5 MiB Wasm linear memory gate!)
const liteBytes = fs.readFileSync(liteZmbkPath);
const liteInitMs = await streamIntoWasm(exports, liteBytes, 0);
const liteBootMemoryBytes = exports.memory.buffer.byteLength;
assert.ok(
  liteBootMemoryBytes <= 4.5 * 1024 * 1024,
  `Core Lite initial Wasm memory (${(liteBootMemoryBytes / (1024 * 1024)).toFixed(2)} MiB) exceeded 4.5 MiB gate!`
);
assert.equal(exports.zmbk_capabilities() & 0b11, 0b11, "Core Lite must expose MORPH + SPELL");

// Verify clean paragraph check in Core Lite stays <= 4.5 MiB
const cleanPara =
  "Türkiye Cumhuriyeti Merkez Bankası bugün yeni faiz kararını kamuoyuna resmen açıkladı ve piyasalar olumlu tepki verdi.";
{
  const inLen = writeInput(exports, cleanPara);
  const outLen = exports.zmbk_check_paragraph(inLen, 0);
  const liteCleanIssues = decodeIssues(exports, outLen);
  assert.equal(liteCleanIssues.length, 0, "Clean paragraph should have 0 issues in Lite mode");
}
const liteCleanMemoryBytes = exports.memory.buffer.byteLength;
assert.ok(
  liteCleanMemoryBytes <= 4.5 * 1024 * 1024,
  `Core Lite steady clean-check Wasm memory (${(liteCleanMemoryBytes / (1024 * 1024)).toFixed(2)} MiB) exceeded 4.5 MiB gate!`
);

// Also verify typo suggestion in Core Lite
{
  const typoPara = "Bugün kitablar çok güzel görünüyor.";
  const inLen = writeInput(exports, typoPara);
  const outLen = exports.zmbk_check_paragraph(inLen, 0);
  const liteTypoIssues = decodeIssues(exports, outLen);
  assert.equal(liteTypoIssues.length, 1);
  assert.equal(typoPara.slice(liteTypoIssues[0].utf16Start, liteTypoIssues[0].utf16End), "kitablar");
  assert.equal(liteTypoIssues[0].replacement, "kitaplar");
}

// 4. Full Studio pack verification (streamed via chunked ReadableStream)
const studioBytes = fs.readFileSync(studioZmbkPath);
const studioInitMs = await streamIntoWasm(exports, studioBytes, 0);
const totalWarmBootMs = instantiateMs + studioInitMs;
assert.ok(
  totalWarmBootMs < 15.0,
  `Instantiate + Studio zero-copy init (${totalWarmBootMs.toFixed(2)} ms) exceeded 15 ms gate!`
);
assert.equal(exports.zmbk_capabilities(), 0b11111, "Studio pack must expose all 5 capabilities");

// 5. Emoji + astral surrogate-pair UTF-16 DOM offset exactness!
const emojiPara =
  "Merhaba 😀👨‍👩‍👧‍👦 bugun kutuphaneye gidicem gelecekmisiniz? 🇹🇷 Okudm kitablar cok guzel!";
{
  const inLen = writeInput(exports, emojiPara);
  const outLen = exports.zmbk_check_paragraph(inLen, 0);
  const issues = decodeIssues(exports, outLen);
  assert.ok(issues.length >= 6, `expected >= 6 issues in emoji paragraph, got ${issues.length}`);

  const rawSlices = issues.map((iss) => emojiPara.slice(iss.utf16Start, iss.utf16End));
  assert.ok(rawSlices.includes("bugun"), "emoji UTF-16 slice must isolate 'bugun'");
  assert.ok(rawSlices.includes("kutuphaneye"), "emoji UTF-16 slice must isolate 'kutuphaneye'");
  assert.ok(rawSlices.includes("gidicem"), "emoji UTF-16 slice must isolate 'gidicem'");
  assert.ok(rawSlices.includes("gelecekmisiniz"), "emoji UTF-16 slice must isolate 'gelecekmisiniz'");
  assert.ok(rawSlices.includes("cok"), "post-flag emoji UTF-16 slice must isolate 'cok'");
  assert.ok(rawSlices.includes("guzel"), "post-flag emoji UTF-16 slice must isolate 'guzel'");

  // Verify category codes: 1=Spelling, 2=CliticSplitMerge, 3=DeasciifyOrColloquial
  const kinds = new Set(issues.map((i) => i.kind));
  assert.ok(kinds.has(1), "expected Spelling issue kind (1)");
  assert.ok(kinds.has(2), "expected CliticSplitMerge issue kind (2)");
  assert.ok(kinds.has(3), "expected DeasciifyOrColloquial issue kind (3)");
}

// 6. Normalize, Deasciify, Disambiguate, and Custom User Dictionary round-trip
{
  const noisy = "Slm yarın bende gelicem kutuphaneye olurmu?";
  const normOut = readOutStr(exports, exports.zmbk_normalize(writeInput(exports, noisy)));
  assert.equal(normOut, "Selam yarın ben de geleceğim kütüphaneye olur mu?");

  const ascii = "Cocuklar kutuphanede calisiyor ve ruzgar sert esiyor.";
  const deascNone = readOutStr(exports, exports.zmbk_deasciify(writeInput(exports, ascii), 0));
  const deascDis = readOutStr(exports, exports.zmbk_deasciify(writeInput(exports, ascii), 1));
  assert.equal(deascNone, "Çocuklar kütüphanede çalışıyor ve rüzgar sert esiyor.");
  assert.equal(deascDis, "Çocuklar kütüphanede çalışıyor ve rüzgar sert esiyor.");

  const disJson = JSON.parse(
    readOutStr(
      exports,
      exports.zmbk_analyze_disambiguate(writeInput(exports, "Yarın kütüphaneye gideceğim."))
    )
  );
  assert.equal(disJson.length, 4);
  assert.equal(disJson[1].lemma, "kütüphane");
  assert.equal(disJson[2].lemma, "gitmek");

  // User dictionary add -> check -> remove round-trip
  assert.equal(exports.zmbk_add_user_word(writeInput(exports, "Gökbörü")), 1);
  assert.equal(exports.zmbk_add_user_word(writeInput(exports, "kankitap")), 1);
  const customClean = "Gökbörü'de kankitabımız hazır.";
  const customIssues = decodeIssues(
    exports,
    exports.zmbk_check_paragraph(writeInput(exports, customClean), 0)
  );
  assert.equal(customIssues.length, 0, "Added user words must yield 0 false positives");
  assert.equal(exports.zmbk_remove_user_word(writeInput(exports, "kankitap")), 1);
  exports.zmbk_clear_user_words();
  assert.equal(exports.zmbk_user_word_count(), 0);
}

// 7. 20-word paragraph check latency + 100,000-sentence ZERO-LEAK stress test!
const templates = [
  "Bugün sabah erkenden kütüphaneye gidip yeni çıkan bilimsel makaleleri dikkatlice inceledik ve önemli notlar aldık.",
  "Türkiye genelinde hava sıcaklıkları mevsim normallerinin üzerinde seyrederken hafta sonu yağış bekleniyor.",
  "Yazılım geliştirme sürecinde bellek güvenliği ve sıfır kopya veri yapıları tarayıcı performansını doğrudan artırır.",
  "Merhaba 😀 yarın saat üçte toplantı salonunda bir araya gelip projenin son durumunu detaylıca değerlendireceğiz.",
  "Öğrenciler sınav öncesinde kütüphanedeki kaynak kitapları inceleyerek eksik konularını hızla tamamladılar.",
  "Yeni nesil dil modelleri ve biçimbilimsel çözümleyiciler Türkçe metin işlemede yüksek doğruluk sağlıyor.",
  "Sabah kahvaltısından sonra sahilde uzun bir yürüyüş yaptık ve temiz deniz havasının tadını çıkardık.",
  "Şirketin yıllık faaliyet raporunda açıklanan veriler ihracat hacminin geçen yıla göre arttığını gösteriyor.",
];

// Warm up internal caches over 500 mixed distinct sentences before capturing steady-state memory & latency
for (let i = 0; i < 500; i++) {
  const s = `${i + 1}. ${templates[i & 7]}`;
  const inLen = writeInput(exports, s);
  exports.zmbk_check_paragraph(inLen, i % 5 === 0 ? 0 : 1);
}

// Measure uncached 20-word paragraph latency (bypassing paragraph cache via flag=2)
const tStudioUncached0 = performance.now();
for (let i = 0; i < templates.length; i++) {
  const inLen = writeInput(exports, templates[i]);
  const outLen = exports.zmbk_check_paragraph(inLen, 2);
  assert.ok(outLen >= 4);
}
const avgStudioUncachedMs = (performance.now() - tStudioUncached0) / templates.length;

const memBefore100k = exports.memory.buffer.byteLength;
const t100k0 = performance.now();
let totalIssuesCounted = 0;

for (let i = 0; i < 100_000; i++) {
  // Every iteration constructs a distinct numbered sentence so paragraph hashes continuously rotate!
  const sent = `${i + 1000}. ${templates[i & 7]}`;
  const inLen = writeInput(exports, sent);
  // Exercise Studio normalizer on realistic paragraph edits + Lite fast-spell across all 100,000 sentences
  const flags = (i & 31) === 0 ? 2 : 1;
  const outLen = exports.zmbk_check_paragraph(inLen, flags);
  assert.ok(outLen >= 4);
  const count = new DataView(exports.memory.buffer, exports.zmbk_output_ptr() >>> 0, 4).getUint32(
    0,
    true
  );
  totalIssuesCounted += count;
}
const elapsed100kMs = performance.now() - t100k0;
const memAfter100k = exports.memory.buffer.byteLength;
const memoryGrowthBytes = memAfter100k - memBefore100k;

assert.equal(
  memoryGrowthBytes,
  0,
  `Expected 0 B Wasm linear memory growth over 100,000 sentences, got +${memoryGrowthBytes} B!`
);

// Measure realistic browser paragraph check latency (mix of active typing + cached/unchanged sentences)
const sample20Word =
  "Merhaba 😀 bugun kutuphaneye gidip yeni kitablari inceledik ve yarın bende toplantiya katilicam.";
// Prime morph caches once, then measure uncached paragraph check (`flags = 2`) and warm paragraph check (`flags = 0`)
exports.zmbk_check_paragraph(writeInput(exports, sample20Word), 2);
const tWarm0 = performance.now();
for (let k = 0; k < 50; k++) {
  exports.zmbk_check_paragraph(writeInput(exports, sample20Word), 0);
}
const warmHitUs = ((performance.now() - tWarm0) * 1000) / 50;

assert.ok(
  avgStudioUncachedMs < 1.5,
  `Average 20-word paragraph check (${avgStudioUncachedMs.toFixed(3)} ms) exceeded 1.5 ms gate!`
);

console.log(
  JSON.stringify(
    {
      wasmBinaryBytes: wasmBytes.length,
      wasmBinaryKiB: Number((wasmBytes.length / 1024).toFixed(1)),
      wasmGzipKiB: Number((wasmGzipBytes.length / 1024).toFixed(1)),
      litePackMiB: Number((liteBytes.length / (1024 * 1024)).toFixed(3)),
      liteWasmMemoryMiB: Number((liteCleanMemoryBytes / (1024 * 1024)).toFixed(3)),
      studioPackMiB: Number((studioBytes.length / (1024 * 1024)).toFixed(3)),
      studioWasmMemoryMiB: Number((memAfter100k / (1024 * 1024)).toFixed(3)),
      coldV8CompileMs: Number(coldCompileMs.toFixed(2)),
      instantiateAndStudioZeroCopyInitMs: Number(totalWarmBootMs.toFixed(2)),
      studioZeroCopyInitUs: Number((studioInitMs * 1000).toFixed(1)),
      avg20WordUncachedParagraphMs: Number(avgStudioUncachedMs.toFixed(3)),
      warmParagraphCacheHitUs: Number(warmHitUs.toFixed(2)),
      sentences100kElapsedSec: Number((elapsed100kMs / 1000).toFixed(2)),
      memoryGrowthOver100kSentencesBytes: memoryGrowthBytes,
      totalIssuesCounted,
    },
    null,
    2
  )
);
