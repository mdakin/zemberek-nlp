import type {
  CircumflexOption,
  TokenDisambiguation,
  WasmSpanIssue,
} from "../wasm/zemberek-client";

export type EngineProfile = "studio" | "lite";

export interface ExtensionSettings {
  enabled: boolean;
  engineProfile: EngineProfile;
  circumflexMode: CircumflexOption;
  ignoreNonTurkish: boolean;
  suppressNativeSpellcheck: boolean;
}

export const DEFAULT_SETTINGS: ExtensionSettings = {
  enabled: true,
  engineProfile: "studio",
  circumflexMode: "disambiguation",
  ignoreNonTurkish: true,
  suppressNativeSpellcheck: true,
};

export const CHECK_PORT_NAME = "zmbk-check";

export interface PortCheckRequest {
  type: "CHECK_PARAGRAPH";
  reqId: number;
  paragraphHash: number;
  text: string;
}

export interface PortAnalyzeRequest {
  type: "ANALYZE_TOKEN";
  reqId: number;
  text: string;
}

export interface PortNormalizeRequest {
  type: "NORMALIZE_TEXT";
  reqId: number;
  text: string;
}

export type ContentPortRequest =
  | PortCheckRequest
  | PortAnalyzeRequest
  | PortNormalizeRequest;

export interface PortCheckResponse {
  type: "CHECK_RESULT";
  reqId: number;
  paragraphHash: number;
  issues: WasmSpanIssue[];
  elapsedMs: number;
  skippedNonTurkish?: boolean;
}

export interface PortAnalyzeResponse {
  type: "ANALYZE_RESULT";
  reqId: number;
  tokens: TokenDisambiguation[];
}

export interface PortNormalizeResponse {
  type: "NORMALIZE_RESULT";
  reqId: number;
  result: string;
}

export interface PortErrorResponse {
  type: "ERROR_RESULT";
  reqId: number;
  error: string;
}

export type ContentPortResponse =
  | PortCheckResponse
  | PortAnalyzeResponse
  | PortNormalizeResponse
  | PortErrorResponse;

export interface WorkerStats {
  activePortCount: number;
  wasmMemoryBytes: number;
  capabilities: number;
  engineProfile: EngineProfile;
  userWordCount: number;
}

/** Fast deterministic 32-bit FNV-1a hash for skipping unchanged paragraphs on the client. */
export function fnv1a32(str: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}
