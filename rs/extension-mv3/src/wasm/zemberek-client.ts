export type IssueCategory = "spelling" | "clitic" | "deasc";

export type CircumflexOption = "none" | "disambiguation" | "full";

export interface WasmSpanIssue {
  utf16Start: number;
  utf16End: number;
  utf8Start: number;
  utf8End: number;
  category: IssueCategory;
  replacement: string;
  suggestions: string[];
}

export interface TokenDisambiguation {
  surface: string;
  lemma: string;
  pos: string;
  secondaryPos: string;
  analysis: string;
  known: boolean;
}

export interface ZemberekWasmExports extends WebAssembly.Exports {
  memory: WebAssembly.Memory;
  zmbk_alloc_pack(byteLen: number): number;
  zmbk_init(ptr: number, len: number, verifyCrc: number): number;
  zmbk_capabilities(): number;
  zmbk_input_ptr(): number;
  zmbk_input_cap(): number;
  zmbk_output_ptr(): number;
  zmbk_output_cap(): number;
  zmbk_check_paragraph(inLen: number, flags: number): number;
  zmbk_normalize(inLen: number): number;
  zmbk_deasciify(inLen: number, mode: number): number;
  zmbk_analyze_disambiguate(inLen: number): number;
  zmbk_add_user_word(inLen: number): number;
  zmbk_remove_user_word(inLen: number): number;
  zmbk_clear_user_words(): number;
  zmbk_user_word_count(): number;
}

export const CAP_MORPH = 1 << 0;
export const CAP_SPELL = 1 << 1;
export const CAP_DISAMBIG = 1 << 2;
export const CAP_DEASC = 1 << 3;
export const CAP_NORM_STUDIO = 1 << 4;

export const CHECK_FLAG_FORCE_LITE = 1 << 0;
export const CHECK_FLAG_BYPASS_CACHE = 1 << 1;

const utf8Encoder = new TextEncoder();
const utf8Decoder = new TextDecoder("utf-8");

function decodeCategory(code: number): IssueCategory {
  if (code === 2) return "clitic";
  if (code === 3) return "deasc";
  return "spelling";
}

function encodeCircumflexMode(mode: CircumflexOption): number {
  if (mode === "none") return 0;
  if (mode === "full") return 2;
  return 1;
}

/**
 * Decodes the packed little-endian binary `SpanIssue` buffer emitted by `zmbk_check_paragraph`.
 */
export function decodeBinaryIssues(
  buffer: ArrayBuffer,
  byteOffset: number,
  byteLength: number
): WasmSpanIssue[] {
  if (byteLength < 4) return [];
  const view = new DataView(buffer, byteOffset, byteLength);
  const bytes = new Uint8Array(buffer, byteOffset, byteLength);
  const count = view.getUint32(0, true);
  const issues: WasmSpanIssue[] = [];
  let cursor = 4;

  for (let i = 0; i < count; i++) {
    if (cursor + 20 > byteLength) break;
    const utf16Start = view.getUint32(cursor, true);
    const utf16End = view.getUint32(cursor + 4, true);
    const utf8Start = view.getUint32(cursor + 8, true);
    const utf8End = view.getUint32(cursor + 12, true);
    const kindCode = view.getUint8(cursor + 16);
    const sugCount = view.getUint8(cursor + 17);
    cursor += 20;

    const suggestions: string[] = [];
    for (let s = 0; s < sugCount; s++) {
      if (cursor + 2 > byteLength) break;
      const sLen = view.getUint16(cursor, true);
      cursor += 2;
      if (cursor + sLen > byteLength) break;
      suggestions.push(utf8Decoder.decode(bytes.subarray(cursor, cursor + sLen)));
      cursor += sLen;
    }

    if (suggestions.length > 0) {
      issues.push({
        utf16Start,
        utf16End,
        utf8Start,
        utf8End,
        category: decodeCategory(kindCode),
        replacement: suggestions[0],
        suggestions,
      });
    }
  }
  return issues;
}

/**
 * Streams a `.zmbk` packfile chunk-by-chunk directly into a single 64-byte-aligned allocation
 * inside `WebAssembly.Memory`, preventing any `2×` JS ArrayBuffer memory spike.
 */
export async function streamZmbkIntoWasm(
  exports: ZemberekWasmExports,
  stream: ReadableStream<Uint8Array>,
  expectedByteLength: number,
  verifyCrc = true
): Promise<void> {
  const packPtr = exports.zmbk_alloc_pack(expectedByteLength) >>> 0;
  if (packPtr === 0 || packPtr % 64 !== 0) {
    throw new Error(`zmbk_alloc_pack(${expectedByteLength}) failed (ptr=${packPtr})`);
  }

  const reader = stream.getReader();
  let written = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (value && value.byteLength > 0) {
        if (written + value.byteLength > expectedByteLength) {
          throw new Error(
            `Stream exceeded expected .zmbk size (${written + value.byteLength} > ${expectedByteLength})`
          );
        }
        new Uint8Array(exports.memory.buffer, packPtr + written, value.byteLength).set(value);
        written += value.byteLength;
      }
    }
  } finally {
    reader.releaseLock();
  }

  if (written !== expectedByteLength) {
    throw new Error(
      `Incomplete .zmbk stream: got ${written} bytes, expected ${expectedByteLength}`
    );
  }

  const status = exports.zmbk_init(packPtr, expectedByteLength, verifyCrc ? 1 : 0) >>> 0;
  if (status !== 0) {
    throw new Error(`zmbk_init failed with PackError status=${status}`);
  }
}

export class ZemberekWasmClient {
  private readonly exports: ZemberekWasmExports;
  private readonly inputCap: number;

  constructor(exports: ZemberekWasmExports) {
    this.exports = exports;
    this.inputCap = exports.zmbk_input_cap() >>> 0;
  }

  static async instantiate(wasmSource: BufferSource | Response): Promise<ZemberekWasmClient> {
    if (typeof Response !== "undefined" && wasmSource instanceof Response) {
      if (typeof WebAssembly.instantiateStreaming === "function") {
        try {
          const { instance } = await WebAssembly.instantiateStreaming(wasmSource.clone(), {});
          return new ZemberekWasmClient(instance.exports as ZemberekWasmExports);
        } catch {
          // Fall back if MIME type is not application/wasm (e.g. file:// in tests)
        }
      }
      const bytes = await wasmSource.arrayBuffer();
      const { instance } = await WebAssembly.instantiate(bytes, {});
      return new ZemberekWasmClient(instance.exports as ZemberekWasmExports);
    }
    const { instance } = await WebAssembly.instantiate(wasmSource as BufferSource, {});
    return new ZemberekWasmClient(instance.exports as ZemberekWasmExports);
  }

  async loadPackFromResponse(response: Response, verifyCrc = false): Promise<void> {
    const lenHeader = response.headers.get("content-length");
    if (response.body && lenHeader) {
      const totalBytes = Number(lenHeader);
      if (Number.isFinite(totalBytes) && totalBytes > 0) {
        await streamZmbkIntoWasm(this.exports, response.body, totalBytes, verifyCrc);
        return;
      }
    }
    const buf = new Uint8Array(await response.arrayBuffer());
    this.loadPackFromBytes(buf, verifyCrc);
  }

  async loadPackFromStream(
    stream: ReadableStream<Uint8Array>,
    byteLength: number,
    verifyCrc = true
  ): Promise<void> {
    await streamZmbkIntoWasm(this.exports, stream, byteLength, verifyCrc);
  }

  loadPackFromBytes(bytes: Uint8Array, verifyCrc = true): void {
    const packPtr = this.exports.zmbk_alloc_pack(bytes.byteLength) >>> 0;
    if (packPtr === 0 || packPtr % 64 !== 0) {
      throw new Error(`zmbk_alloc_pack(${bytes.byteLength}) failed`);
    }
    new Uint8Array(this.exports.memory.buffer, packPtr, bytes.byteLength).set(bytes);
    const status = this.exports.zmbk_init(packPtr, bytes.byteLength, verifyCrc ? 1 : 0) >>> 0;
    if (status !== 0) {
      throw new Error(`zmbk_init failed with status=${status}`);
    }
  }

  get capabilities(): number {
    return this.exports.zmbk_capabilities() >>> 0;
  }

  get memoryBytes(): number {
    return this.exports.memory.buffer.byteLength;
  }

  private writeInput(text: string): number {
    const ptr = this.exports.zmbk_input_ptr() >>> 0;
    const dst = new Uint8Array(this.exports.memory.buffer, ptr, this.inputCap);
    const { read, written } = utf8Encoder.encodeInto(text, dst);
    if (read < text.length) {
      throw new Error(`Input text exceeds ${this.inputCap}-byte Wasm scratch buffer`);
    }
    return written;
  }

  private readOutputString(byteLen: number): string {
    if (byteLen <= 0) return "";
    const outPtr = this.exports.zmbk_output_ptr() >>> 0;
    return utf8Decoder.decode(new Uint8Array(this.exports.memory.buffer, outPtr, byteLen));
  }

  checkParagraph(paragraph: string, flags = 0): WasmSpanIssue[] {
    if (!paragraph) return [];
    const inLen = this.writeInput(paragraph);
    const outLen = this.exports.zmbk_check_paragraph(inLen, flags);
    if (outLen < 0) {
      throw new Error(`zmbk_check_paragraph failed with error ${outLen}`);
    }
    const outPtr = this.exports.zmbk_output_ptr() >>> 0;
    return decodeBinaryIssues(this.exports.memory.buffer, outPtr, outLen);
  }

  normalize(text: string): string {
    if (!text) return "";
    const inLen = this.writeInput(text);
    const outLen = this.exports.zmbk_normalize(inLen);
    if (outLen < 0) {
      throw new Error(`zmbk_normalize failed with error ${outLen}`);
    }
    return this.readOutputString(outLen);
  }

  deasciify(text: string, mode: CircumflexOption = "disambiguation"): string {
    if (!text) return "";
    const inLen = this.writeInput(text);
    const outLen = this.exports.zmbk_deasciify(inLen, encodeCircumflexMode(mode));
    if (outLen < 0) {
      throw new Error(`zmbk_deasciify failed with error ${outLen}`);
    }
    return this.readOutputString(outLen);
  }

  analyzeDisambiguate(text: string): TokenDisambiguation[] {
    if (!text.trim()) return [];
    const inLen = this.writeInput(text);
    const outLen = this.exports.zmbk_analyze_disambiguate(inLen);
    if (outLen < 0) {
      throw new Error(`zmbk_analyze_disambiguate failed with error ${outLen}`);
    }
    return JSON.parse(this.readOutputString(outLen)) as TokenDisambiguation[];
  }

  addUserWord(word: string): boolean {
    const trimmed = word.trim();
    if (!trimmed) return false;
    const inLen = this.writeInput(trimmed);
    return this.exports.zmbk_add_user_word(inLen) > 0;
  }

  removeUserWord(word: string): boolean {
    const trimmed = word.trim();
    if (!trimmed) return false;
    const inLen = this.writeInput(trimmed);
    return this.exports.zmbk_remove_user_word(inLen) > 0;
  }

  clearUserWords(): number {
    return this.exports.zmbk_clear_user_words() >>> 0;
  }

  syncUserWords(words: readonly string[]): void {
    this.clearUserWords();
    for (const w of words) {
      this.addUserWord(w);
    }
  }
}
