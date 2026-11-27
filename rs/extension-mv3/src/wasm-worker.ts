import { isSentenceLikelyTurkish } from "./shared/language-gate";
import {
  DEFAULT_SETTINGS,
  type ContentPortRequest,
  type ContentPortResponse,
  type EngineProfile,
  type ExtensionSettings,
} from "./shared/protocol";
import {
  CHECK_FLAG_FORCE_LITE,
  ZemberekWasmClient,
  type CircumflexOption,
  type WasmSpanIssue,
} from "./wasm/zemberek-client";

let clientPromise: Promise<ZemberekWasmClient> | null = null;
let loadedPackFile: string | null = null;
let settings: ExtensionSettings = { ...DEFAULT_SETTINGS };
let cachedUserWords: string[] = [];

interface Utf16SentenceSpan {
  start: number;
  end: number;
  slice: string;
}

const SENTENCE_SPLIT_RE = /[^.!?\n]+(?:[.!?]+|\n|$)/g;

function splitRoughSentencesUtf16(text: string): Utf16SentenceSpan[] {
  const spans: Utf16SentenceSpan[] = [];
  SENTENCE_SPLIT_RE.lastIndex = 0;
  let m = SENTENCE_SPLIT_RE.exec(text);
  while (m) {
    const slice = m[0];
    if (slice.trim().length > 0) {
      spans.push({
        start: m.index,
        end: m.index + slice.length,
        slice,
      });
    }
    m = SENTENCE_SPLIT_RE.exec(text);
  }
  return spans;
}

function checkParagraphWithLanguageGate(
  client: ZemberekWasmClient,
  text: string,
  flags: number,
  ignoreNonTurkish: boolean
): { issues: WasmSpanIssue[]; skippedNonTurkish: boolean } {
  if (!ignoreNonTurkish) {
    return {
      issues: client.checkParagraph(text, flags),
      skippedNonTurkish: false,
    };
  }

  const sentences = splitRoughSentencesUtf16(text);
  if (sentences.length === 0) {
    return { issues: [], skippedNonTurkish: false };
  }

  const turkishMask = sentences.map((s) => isSentenceLikelyTurkish(s.slice, client));
  const trCount = turkishMask.filter(Boolean).length;

  if (trCount === 0) {
    return { issues: [], skippedNonTurkish: true };
  }

  const allIssues = client.checkParagraph(text, flags);
  if (trCount === sentences.length) {
    return { issues: allIssues, skippedNonTurkish: false };
  }

  // Mixed-language paragraph (e.g. Turkish + English sentences): keep issues only inside Turkish spans
  const filtered = allIssues.filter((iss) => {
    for (let i = 0; i < sentences.length; i++) {
      const s = sentences[i];
      if (iss.utf16Start >= s.start && iss.utf16Start < s.end) {
        return turkishMask[i];
      }
    }
    return true;
  });

  return { issues: filtered, skippedNonTurkish: false };
}

function resolveBaseAssetUrl(filename: string): string {
  return new URL(filename, self.location.href).href;
}

async function fetchPackWithFallback(preferred: EngineProfile): Promise<{
  response: Response;
  actualProfile: EngineProfile;
  packName: string;
}> {
  const candidates: Array<{ name: string; profile: EngineProfile }> =
    preferred === "lite"
      ? [
          { name: "zemberek_lite.zmbk", profile: "lite" },
          { name: "zemberek_studio.zmbk", profile: "studio" },
        ]
      : [
          { name: "zemberek_studio.zmbk", profile: "studio" },
          { name: "zemberek_lite.zmbk", profile: "lite" },
        ];

  for (const cand of candidates) {
    try {
      const resp = await fetch(resolveBaseAssetUrl(cand.name));
      if (resp.ok) {
        return { response: resp, actualProfile: cand.profile, packName: cand.name };
      }
    } catch {
      // Try next pack candidate
    }
  }
  throw new Error("Neither zemberek_studio.zmbk nor zemberek_lite.zmbk could be loaded");
}

async function ensureEngineLoaded(desiredProfile: EngineProfile): Promise<ZemberekWasmClient> {
  if (!clientPromise) {
    clientPromise = (async () => {
      const wasmResp = await fetch(resolveBaseAssetUrl("zemberek_wasm.wasm"));
      if (!wasmResp.ok) {
        throw new Error(`Failed to fetch zemberek_wasm.wasm: HTTP ${wasmResp.status}`);
      }
      return ZemberekWasmClient.instantiate(wasmResp);
    })();
  }

  const client = await clientPromise;
  const desiredFile =
    desiredProfile === "lite" ? "zemberek_lite.zmbk" : "zemberek_studio.zmbk";

  if (loadedPackFile !== desiredFile) {
    const { response, packName } = await fetchPackWithFallback(desiredProfile);
    await client.loadPackFromResponse(response, false);
    loadedPackFile = packName;
    if (cachedUserWords.length > 0) {
      client.syncUserWords(cachedUserWords);
    }
  }

  return client;
}

async function handlePortRequest(req: ContentPortRequest): Promise<ContentPortResponse> {
  const client = await ensureEngineLoaded(settings.engineProfile);

  switch (req.type) {
    case "CHECK_PARAGRAPH": {
      const t0 = performance.now();
      const flags = settings.engineProfile === "lite" ? CHECK_FLAG_FORCE_LITE : 0;
      const { issues, skippedNonTurkish } = checkParagraphWithLanguageGate(
        client,
        req.text,
        flags,
        settings.ignoreNonTurkish
      );
      return {
        type: "CHECK_RESULT",
        reqId: req.reqId,
        paragraphHash: req.paragraphHash,
        issues,
        elapsedMs: Number((performance.now() - t0).toFixed(3)),
        skippedNonTurkish,
      };
    }

    case "ANALYZE_TOKEN": {
      const tokens = client.analyzeDisambiguate(req.text);
      return {
        type: "ANALYZE_RESULT",
        reqId: req.reqId,
        tokens,
      };
    }

    case "NORMALIZE_TEXT": {
      const result = client.normalize(req.text);
      return {
        type: "NORMALIZE_RESULT",
        reqId: req.reqId,
        result,
      };
    }
  }
}

self.addEventListener("message", (event: MessageEvent) => {
  const msg = event.data;
  if (!msg) return;

  if (msg.kind === "PORT_REQ") {
    const portId = Number(msg.portId);
    const req = msg.request as ContentPortRequest;
    (async () => {
      try {
        const response = await handlePortRequest(req);
        self.postMessage({ kind: "PORT_REPLY", portId, response });
      } catch (err) {
        self.postMessage({
          kind: "PORT_REPLY",
          portId,
          response: {
            type: "ERROR_RESULT",
            reqId: req.reqId,
            error: err instanceof Error ? err.message : String(err),
          } satisfies ContentPortResponse,
        });
      }
    })();
    return;
  }

  if (msg.kind === "RPC") {
    const rpcId = Number(msg.rpcId);
    (async () => {
      try {
        switch (msg.op) {
          case "SYNC_STATE": {
            if (msg.settings) {
              settings = { ...settings, ...msg.settings };
            }
            if (Array.isArray(msg.userWords)) {
              cachedUserWords = msg.userWords.map(String);
            }
            const client = await ensureEngineLoaded(settings.engineProfile);
            client.syncUserWords(cachedUserWords);
            self.postMessage({
              kind: "RPC_REPLY",
              rpcId,
              result: {
                capabilities: client.capabilities,
                wasmMemoryBytes: client.memoryBytes,
              },
            });
            break;
          }

          case "NORMALIZE": {
            const client = await ensureEngineLoaded(settings.engineProfile);
            const result = client.normalize(String(msg.text ?? ""));
            self.postMessage({ kind: "RPC_REPLY", rpcId, result: { result } });
            break;
          }

          case "DEASCIIFY": {
            const client = await ensureEngineLoaded(settings.engineProfile);
            const mode = (msg.circumflexMode ?? settings.circumflexMode) as CircumflexOption;
            const result = client.deasciify(String(msg.text ?? ""), mode);
            self.postMessage({ kind: "RPC_REPLY", rpcId, result: { result } });
            break;
          }

          case "CHECK_PARAGRAPH": {
            const client = await ensureEngineLoaded(settings.engineProfile);
            const t0 = performance.now();
            const flags = settings.engineProfile === "lite" ? CHECK_FLAG_FORCE_LITE : 0;
            const { issues, skippedNonTurkish } = checkParagraphWithLanguageGate(
              client,
              String(msg.text ?? ""),
              flags,
              settings.ignoreNonTurkish
            );
            self.postMessage({
              kind: "RPC_REPLY",
              rpcId,
              result: {
                issues,
                skippedNonTurkish,
                elapsedMs: Number((performance.now() - t0).toFixed(3)),
              },
            });
            break;
          }

          case "ANALYZE": {
            const client = await ensureEngineLoaded(settings.engineProfile);
            const tokens = client.analyzeDisambiguate(String(msg.text ?? ""));
            self.postMessage({ kind: "RPC_REPLY", rpcId, result: { tokens } });
            break;
          }

          case "STATS": {
            const client = await ensureEngineLoaded(settings.engineProfile);
            self.postMessage({
              kind: "RPC_REPLY",
              rpcId,
              result: {
                wasmMemoryBytes: client.memoryBytes,
                capabilities: client.capabilities,
                engineProfile: settings.engineProfile,
                userWordCount: cachedUserWords.length,
              },
            });
            break;
          }

          default:
            throw new Error(`Unsupported worker op: ${String(msg.op)}`);
        }
      } catch (err) {
        self.postMessage({
          kind: "RPC_REPLY",
          rpcId,
          error: err instanceof Error ? err.message : String(err),
        });
      }
    })();
  }
});
