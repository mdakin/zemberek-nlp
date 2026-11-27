import { classifyTextOrthography, hasTurkishDiacritics } from "./shared/language-gate";
import {
  CHECK_PORT_NAME,
  DEFAULT_SETTINGS,
  fnv1a32,
  type ContentPortResponse,
  type ExtensionSettings,
  type PortCheckResponse,
} from "./shared/protocol";
import type {
  IssueCategory,
  TokenDisambiguation,
  WasmSpanIssue,
} from "./wasm/zemberek-client";

interface TextNodeSlice {
  node: Text;
  startUtf16: number;
  endUtf16: number;
}

interface ResolvedIssue {
  issue: WasmSpanIssue;
  range: Range | null;
}

interface TrackedEditor {
  el: HTMLElement;
  kind: "contenteditable" | "textcontrol";
  origSpellcheckAttr: string | null;
  lastSkippedNonTurkish: boolean;
  lastHash: number;
  debounceTimer: number | null;
  activeReqId: number;
  textCacheDirty: boolean;
  cachedText: string;
  cachedSlices: TextNodeSlice[];
  issues: ResolvedIssue[];
  mutationObserver: MutationObserver | null;
  resizeObserver: ResizeObserver | null;
  mirrorHost: HTMLDivElement | null;
  mirrorContent: HTMLDivElement | null;
}

const DEBOUNCE_MS = 120;
const trackedEditors = new Map<HTMLElement, TrackedEditor>();
let clientSettings: ExtensionSettings = { ...DEFAULT_SETTINGS };
let isImeComposing = false;

// Optional Chrome Built-in AI `LanguageDetector` (Chrome 138+) — instantiated strictly on user gesture
// per `modern-web-guidance` (`language-detection`) so `NotAllowedError` can never be thrown.
interface BuiltInLanguageDetector {
  detect(input: string): Promise<Array<{ detectedLanguage: string; confidence: number }>>;
}
let webLanguageDetector: BuiltInLanguageDetector | null = null;
let webDetectorInitAttempted = false;

function tryInitWebLanguageDetectorOnUserGesture(): void {
  if (webDetectorInitAttempted) return;
  const ldGlobal = (globalThis as Record<string, any>).LanguageDetector;
  if (!ldGlobal || typeof ldGlobal.availability !== "function") {
    webDetectorInitAttempted = true;
    return;
  }
  webDetectorInitAttempted = true;
  void (async () => {
    try {
      const avail = await ldGlobal.availability();
      if (avail === "available") {
        webLanguageDetector = await ldGlobal.create();
      }
    } catch {
      webLanguageDetector = null;
    }
  })();
}

const CLIENT_SENTENCE_RE = /[^.!?\n]+(?:[.!?]+|\n|$)/g;

async function detectConfidentNonTurkishClientSide(text: string): Promise<boolean> {
  if (text.length < 35 || hasTurkishDiacritics(text)) return false;

  const sentences = text.match(CLIENT_SENTENCE_RE)?.map((s) => s.trim()).filter(Boolean) ?? [];
  if (sentences.length === 0) return false;

  // Never fast-skip a multi-sentence paragraph client-side unless EVERY sentence is clearly foreign;
  // mixed Turkish + English paragraphs are filtered per-sentence inside `wasm-worker.ts`.
  const allClearlyForeign = sentences.every((s) => classifyTextOrthography(s) === "non-tr");
  if (allClearlyForeign) return true;
  if (sentences.length > 1 || sentences.some((s) => classifyTextOrthography(s) === "tr")) {
    return false;
  }

  if (webLanguageDetector) {
    try {
      const results = await webLanguageDetector.detect(text);
      const top = results?.[0];
      if (top && top.detectedLanguage !== "tr" && top.detectedLanguage !== "und" && top.confidence >= 0.94) {
        return true;
      }
    } catch {
      // Fall through to chrome.i18n.detectLanguage
    }
  }

  if (typeof chrome !== "undefined" && typeof chrome.i18n?.detectLanguage === "function") {
    try {
      const cld = await chrome.i18n.detectLanguage(text);
      const top = cld?.languages?.[0];
      if (cld?.isReliable && top && top.language !== "tr" && top.language !== "und" && top.percentage >= 94) {
        return true;
      }
    } catch {
      // Worker language gate still guards all sentences downstream
    }
  }

  return false;
}

function syncNativeSpellcheckState(ed: TrackedEditor): void {
  const shouldSuppress =
    clientSettings.enabled &&
    clientSettings.suppressNativeSpellcheck &&
    !ed.lastSkippedNonTurkish;

  if (shouldSuppress) {
    ed.el.setAttribute("data-zmbk-suppress-native", "1");
    if (ed.el.getAttribute("spellcheck") !== "false") {
      ed.el.setAttribute("spellcheck", "false");
    }
    ed.el.spellcheck = false;
  } else {
    ed.el.removeAttribute("data-zmbk-suppress-native");
    if (ed.origSpellcheckAttr === null) {
      ed.el.removeAttribute("spellcheck");
      ed.el.spellcheck = true;
    } else {
      ed.el.setAttribute("spellcheck", ed.origSpellcheckAttr);
      ed.el.spellcheck = ed.origSpellcheckAttr.toLowerCase() !== "false";
    }
  }
}

function syncAllEditorsNativeSpellcheck(): void {
  for (const ed of trackedEditors.values()) {
    if (ed.el.isConnected) {
      syncNativeSpellcheckState(ed);
    }
  }
}
let port: chrome.runtime.Port | null = null;
let portConnecting: Promise<chrome.runtime.Port | null> | null = null;
let nextReqId = 1;
const reqToEditor = new Map<number, HTMLElement>();
const pendingAnalyzeCallbacks = new Map<
  number,
  (tokens: TokenDisambiguation[]) => void
>();
const pendingNormalizeCallbacks = new Map<number, (result: string) => void>();

let rafHighlightScheduled = false;

// ============================================================================
// Shadow-DOM UI Host (Suggestion Popover Card + Persistent Manual Toast)
// ============================================================================

let uiHost: HTMLDivElement | null = null;
let uiShadow: ShadowRoot | null = null;
let popoverCard: HTMLDivElement | null = null;
let toastRegion: HTMLDivElement | null = null;
let liveAnnouncer: HTMLDivElement | null = null;
let activeCardContext: {
  editor: TrackedEditor;
  resolved: ResolvedIssue;
  anchorRect: DOMRect;
} | null = null;

const SHADOW_UI_CSS = `
:host {
  all: initial;
  position: fixed !important;
  inset: 0 auto auto 0 !important;
  width: 0 !important;
  height: 0 !important;
  margin: 0 !important;
  padding: 0 !important;
  border: 0 !important;
  overflow: visible !important;
  pointer-events: none !important;
  color-scheme: light dark;
  --zmbk-bg-light: #ffffff;
  --zmbk-bg-dark: #18181b;
  --zmbk-fg-light: #18181b;
  --zmbk-fg-dark: #f4f4f5;
  --zmbk-muted-light: #52525b;
  --zmbk-muted-dark: #a1a1aa;
  --zmbk-border-light: #e4e4e7;
  --zmbk-border-dark: #3f3f46;
  --zmbk-accent-light: #0d9488;
  --zmbk-accent-dark: #2dd4bf;
  --zmbk-chip-bg-light: #f0fdfa;
  --zmbk-chip-bg-dark: #134e4a;
  --zmbk-bg: var(--zmbk-bg-light);
  --zmbk-fg: var(--zmbk-fg-light);
  --zmbk-muted: var(--zmbk-muted-light);
  --zmbk-border: var(--zmbk-border-light);
  --zmbk-accent: var(--zmbk-accent-light);
  --zmbk-chip-bg: var(--zmbk-chip-bg-light);
  font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
}

@media (prefers-color-scheme: dark) {
  :host {
    --zmbk-bg: var(--zmbk-bg-dark);
    --zmbk-fg: var(--zmbk-fg-dark);
    --zmbk-muted: var(--zmbk-muted-dark);
    --zmbk-border: var(--zmbk-border-dark);
    --zmbk-accent: var(--zmbk-accent-dark);
    --zmbk-chip-bg: var(--zmbk-chip-bg-dark);
  }
}

@supports (color: light-dark(white, black)) {
  :host {
    --zmbk-bg: light-dark(var(--zmbk-bg-light), var(--zmbk-bg-dark));
    --zmbk-fg: light-dark(var(--zmbk-fg-light), var(--zmbk-fg-dark));
    --zmbk-muted: light-dark(var(--zmbk-muted-light), var(--zmbk-muted-dark));
    --zmbk-border: light-dark(var(--zmbk-border-light), var(--zmbk-border-dark));
    --zmbk-accent: light-dark(var(--zmbk-accent-light), var(--zmbk-accent-dark));
    --zmbk-chip-bg: light-dark(var(--zmbk-chip-bg-light), var(--zmbk-chip-bg-dark));
  }
}

.zmbk-sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

.zmbk-card {
  position: fixed;
  z-index: 2147483646;
  pointer-events: auto;
  min-width: 240px;
  max-width: 340px;
  background: var(--zmbk-bg);
  color: var(--zmbk-fg);
  border: 1px solid var(--zmbk-border);
  border-radius: 10px;
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.18);
  padding: 10px 12px;
  box-sizing: border-box;
  contain: layout style paint;
  font-size: 13px;
  line-height: 1.4;
}

@supports (position-area: block-end) {
  .zmbk-card.zmbk-native-anchor {
    inset: auto;
    margin: 0;
    position-area: block-end span-inline-end;
    position-try-fallbacks: flip-block, flip-inline, flip-block flip-inline;
  }
}

.zmbk-card[hidden] {
  display: none !important;
}

.zmbk-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.zmbk-cat-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  font-weight: 600;
  padding: 2px 7px;
  border-radius: 999px;
}

.zmbk-cat-spelling {
  background: rgba(220, 38, 38, 0.14);
  color: #dc2626;
}

.zmbk-cat-clitic {
  background: rgba(37, 99, 235, 0.14);
  color: #2563eb;
}

.zmbk-cat-deasc {
  background: rgba(147, 51, 234, 0.14);
  color: #9333ea;
}

.zmbk-morph-badge {
  font-size: 11px;
  color: var(--zmbk-muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 165px;
}

.zmbk-suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}

.zmbk-sug-btn {
  appearance: none;
  border: 1px solid var(--zmbk-accent);
  background: var(--zmbk-chip-bg);
  color: var(--zmbk-fg);
  font-weight: 600;
  font-size: 13px;
  padding: 4px 10px;
  border-radius: 7px;
  cursor: pointer;
}

.zmbk-sug-btn:hover,
.zmbk-sug-btn:focus-visible {
  background: var(--zmbk-accent);
  color: #ffffff;
  outline: none;
}

.zmbk-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  border-top: 1px solid var(--zmbk-border);
  padding-top: 7px;
}

.zmbk-secondary-btn {
  appearance: none;
  border: 1px solid var(--zmbk-border);
  background: transparent;
  color: var(--zmbk-muted);
  font-size: 11.5px;
  padding: 3px 8px;
  border-radius: 6px;
  cursor: pointer;
}

.zmbk-secondary-btn:hover,
.zmbk-secondary-btn:focus-visible {
  color: var(--zmbk-fg);
  border-color: var(--zmbk-fg);
  outline: none;
}

.zmbk-toast-region {
  position: fixed;
  bottom: 18px;
  right: 18px;
  z-index: 2147483647;
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-width: min(360px, calc(100vw - 32px));
}

.zmbk-toast {
  margin: 0;
  inset: auto;
  position: relative;
  pointer-events: auto;
  border: 1px solid var(--zmbk-border);
  border-left: 4px solid var(--zmbk-accent);
  background: var(--zmbk-bg);
  color: var(--zmbk-fg);
  border-radius: 8px;
  padding: 9px 12px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.18);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  font-size: 12.5px;
  transition: opacity 0.18s ease, transform 0.18s ease;
  transition-behavior: allow-discrete;
}

@media (prefers-reduced-motion: reduce) {
  .zmbk-toast {
    transition: none;
  }
}

.zmbk-toast-close {
  appearance: none;
  border: 1px solid var(--zmbk-border);
  background: transparent;
  color: var(--zmbk-muted);
  border-radius: 5px;
  padding: 2px 6px;
  font-size: 11px;
  cursor: pointer;
}
`;

const MIRROR_OVERLAY_CSS = `
:host {
  all: initial;
  position: fixed !important;
  margin: 0 !important;
  padding: 0 !important;
  border: 0 !important;
  pointer-events: none !important;
  z-index: 2147483640 !important;
  overflow: hidden !important;
  contain: layout style paint;
}
.zmbk-mirror-surface {
  box-sizing: border-box;
  color: transparent !important;
  background: transparent !important;
  caret-color: transparent !important;
  white-space: pre-wrap;
  word-wrap: break-word;
  overflow-wrap: break-word;
  pointer-events: none;
}
.zmbk-mark {
  color: transparent !important;
  background: transparent !important;
  text-decoration-line: underline;
  text-decoration-style: wavy;
  text-decoration-thickness: 1.5px;
  text-underline-offset: 2px;
}
.zmbk-mark-spelling {
  text-decoration-color: #dc2626;
}
.zmbk-mark-clitic {
  text-decoration-color: #2563eb;
}
.zmbk-mark-deasc {
  text-decoration-color: #9333ea;
}
`;

function ensureShadowUi(): void {
  if (uiHost && uiShadow) return;

  uiHost = document.createElement("div");
  uiHost.id = "zmbk-assistant-root";
  uiHost.setAttribute("data-zmbk-ui", "1");
  uiHost.style.cssText =
    "position:fixed!important;inset:0 auto auto 0!important;width:0!important;height:0!important;margin:0!important;padding:0!important;border:0!important;overflow:visible!important;pointer-events:none!important;";
  uiShadow = uiHost.attachShadow({ mode: "open" });

  const style = document.createElement("style");
  style.textContent = SHADOW_UI_CSS;

  popoverCard = document.createElement("div");
  popoverCard.className = "zmbk-card";
  popoverCard.hidden = true;

  toastRegion = document.createElement("div");
  toastRegion.className = "zmbk-toast-region";
  toastRegion.setAttribute("role", "region");
  toastRegion.setAttribute("aria-label", "Zemberek Bildirimleri");

  const srLabel = document.createElement("p");
  srLabel.className = "zmbk-sr-only";
  srLabel.textContent = "Zemberek ekran okuyucu bildirimleri";

  liveAnnouncer = document.createElement("div");
  liveAnnouncer.className = "zmbk-sr-only";
  liveAnnouncer.setAttribute("aria-live", "polite");

  uiShadow.replaceChildren(style, popoverCard, toastRegion, srLabel, liveAnnouncer);
  (document.documentElement || document.body).appendChild(uiHost);
}

function showToast(message: string): void {
  ensureShadowUi();
  if (!toastRegion || !liveAnnouncer) return;

  const toast = document.createElement("div");
  toast.className = "zmbk-toast";
  toast.setAttribute("popover", "manual");

  const msgSpan = document.createElement("span");
  msgSpan.textContent = message;

  const closeBtn = document.createElement("button");
  closeBtn.type = "button";
  closeBtn.className = "zmbk-toast-close";
  closeBtn.textContent = "Kapat";

  let dismissed = false;
  const dismiss = () => {
    if (dismissed) return;
    dismissed = true;
    try {
      (toast as any).hidePopover?.();
    } catch {
      // Fallback if popover API not active
    }
    toast.remove();
  };

  closeBtn.addEventListener("click", dismiss);
  toast.replaceChildren(msgSpan, closeBtn);
  toastRegion.appendChild(toast);

  try {
    (toast as any).showPopover?.();
  } catch {
    // Graceful non-top-layer display inside fixed toastRegion
  }

  if (typeof (toastRegion as any).ariaNotify === "function") {
    (toastRegion as any).ariaNotify(message);
  } else {
    liveAnnouncer.textContent = message;
  }

  const scheduleAutoDismiss = () => {
    window.setTimeout(() => {
      if (dismissed) return;
      if (toast.matches(":focus-within")) {
        toast.addEventListener("focusout", scheduleAutoDismiss, { once: true });
        return;
      }
      dismiss();
    }, 3600);
  };
  scheduleAutoDismiss();
}

function hideSuggestionCard(): void {
  if (popoverCard) {
    popoverCard.hidden = true;
    popoverCard.replaceChildren();
  }
  activeCardContext = null;
}

// ============================================================================
// Singleton Offscreen Port Connection
// ============================================================================

async function getOrConnectPort(): Promise<chrome.runtime.Port | null> {
  if (port) return port;
  if (portConnecting) return portConnecting;

  portConnecting = (async () => {
    try {
      const bootResp = await chrome.runtime.sendMessage({ type: "ENSURE_OFFSCREEN" });
      if (bootResp?.settings) {
        clientSettings = { ...clientSettings, ...bootResp.settings };
        syncAllEditorsNativeSpellcheck();
      }
      const connected = chrome.runtime.connect({ name: CHECK_PORT_NAME });
      connected.onMessage.addListener(handlePortMessage);
      connected.onDisconnect.addListener(() => {
        void chrome.runtime.lastError;
        if (port === connected) {
          port = null;
        }
      });
      port = connected;
      return connected;
    } catch {
      return null;
    } finally {
      portConnecting = null;
    }
  })();

  return portConnecting;
}

function handlePortMessage(msg: ContentPortResponse): void {
  if (!msg) return;

  if (msg.type === "CHECK_RESULT") {
    const el = reqToEditor.get(msg.reqId);
    reqToEditor.delete(msg.reqId);
    if (!el) return;
    const ed = trackedEditors.get(el);
    if (!ed || ed.activeReqId !== msg.reqId) return;
    applyCheckResponse(ed, msg);
    return;
  }

  if (msg.type === "ANALYZE_RESULT") {
    const cb = pendingAnalyzeCallbacks.get(msg.reqId);
    pendingAnalyzeCallbacks.delete(msg.reqId);
    cb?.(msg.tokens);
    return;
  }

  if (msg.type === "NORMALIZE_RESULT") {
    const cb = pendingNormalizeCallbacks.get(msg.reqId);
    pendingNormalizeCallbacks.delete(msg.reqId);
    cb?.(msg.result);
    return;
  }
}

// ============================================================================
// TreeWalker Text Extraction & DOM Range Mapping (`highlight-text-ranges`)
// ============================================================================

function rebuildEditorTextCache(ed: TrackedEditor): void {
  if (!ed.textCacheDirty) return;

  if (ed.kind === "textcontrol") {
    const input = ed.el as HTMLTextAreaElement | HTMLInputElement;
    ed.cachedText = input.value ?? "";
    ed.cachedSlices = [];
    ed.textCacheDirty = false;
    return;
  }

  // MANDATORY (`highlight-text-ranges`): Use TreeWalker to collect text nodes — never touch innerHTML.
  const walker = document.createTreeWalker(ed.el, NodeFilter.SHOW_TEXT);
  const slices: TextNodeSlice[] = [];
  const chunks: string[] = [];
  let cursorUtf16 = 0;

  let current = walker.nextNode();
  while (current) {
    const textNode = current as Text;
    const val = textNode.nodeValue ?? "";
    if (val.length > 0) {
      slices.push({
        node: textNode,
        startUtf16: cursorUtf16,
        endUtf16: cursorUtf16 + val.length,
      });
      chunks.push(val);
      cursorUtf16 += val.length;
    }
    current = walker.nextNode();
  }

  ed.cachedText = chunks.join("");
  ed.cachedSlices = slices;
  ed.textCacheDirty = false;
}

function locateSliceIndex(slices: readonly TextNodeSlice[], utf16Offset: number): number {
  let lo = 0;
  let hi = slices.length - 1;
  while (lo <= hi) {
    const mid = (lo + hi) >>> 1;
    const s = slices[mid];
    if (utf16Offset < s.startUtf16) {
      hi = mid - 1;
    } else if (utf16Offset > s.endUtf16) {
      lo = mid + 1;
    } else {
      if (utf16Offset === s.endUtf16 && mid + 1 < slices.length && slices[mid + 1].startUtf16 === utf16Offset) {
        return mid + 1;
      }
      return mid;
    }
  }
  return -1;
}

function createDomRangeForUtf16Span(
  slices: readonly TextNodeSlice[],
  utf16Start: number,
  utf16End: number
): Range | null {
  if (slices.length === 0 || utf16End <= utf16Start) return null;
  const startIdx = locateSliceIndex(slices, utf16Start);
  if (startIdx < 0) return null;

  let endIdx = locateSliceIndex(slices, utf16End);
  if (endIdx < 0) endIdx = slices.length - 1;
  if (
    endIdx > 0 &&
    utf16End === slices[endIdx].startUtf16 &&
    slices[endIdx - 1].endUtf16 === utf16End
  ) {
    endIdx -= 1;
  }

  const startSlice = slices[startIdx];
  const endSlice = slices[endIdx];
  const localStart = Math.max(0, Math.min(startSlice.node.length, utf16Start - startSlice.startUtf16));
  const localEnd = Math.max(0, Math.min(endSlice.node.length, utf16End - endSlice.startUtf16));

  // MANDATORY (`highlight-text-ranges`): Set range start/end on Text nodes, not Element nodes.
  const range = new Range();
  range.setStart(startSlice.node, localStart);
  range.setEnd(endSlice.node, localEnd);
  return range;
}

// ============================================================================
// Highlight Painting (`CSS.highlights` + `<textarea>` OpaqueRange / Mirror)
// ============================================================================

function scheduleHighlightFlush(): void {
  if (rafHighlightScheduled) return;
  rafHighlightScheduled = true;
  requestAnimationFrame(() => {
    rafHighlightScheduled = false;
    flushAllHighlights();
  });
}

function supportsOpaqueValueRange(el: HTMLElement): boolean {
  return typeof (el as any).createValueRange === "function";
}

function ensureTextareaMirror(ed: TrackedEditor): void {
  if (ed.mirrorHost && ed.mirrorContent) return;
  const host = document.createElement("div");
  host.setAttribute("data-zmbk-mirror", "1");
  host.style.cssText =
    "position:fixed!important;margin:0!important;padding:0!important;border:0!important;pointer-events:none!important;overflow:hidden!important;z-index:2147483640!important;";
  const shadow = host.attachShadow({ mode: "open" });
  const style = document.createElement("style");
  style.textContent = MIRROR_OVERLAY_CSS;
  const surface = document.createElement("div");
  surface.className = "zmbk-mirror-surface";
  shadow.replaceChildren(style, surface);
  (document.documentElement || document.body).appendChild(host);
  ed.mirrorHost = host;
  ed.mirrorContent = surface;
}

interface MirrorReadSnapshot {
  ed: TrackedEditor;
  rect: DOMRect;
  computed: CSSStyleDeclaration;
  scrollTop: number;
  scrollLeft: number;
  isSingleLine: boolean;
}

function flushAllHighlights(): void {
  const spellingRanges: AbstractRange[] = [];
  const cliticRanges: AbstractRange[] = [];
  const deascRanges: AbstractRange[] = [];
  const mirrorReads: MirrorReadSnapshot[] = [];

  // Phase 1: Batch all DOM geometry/style READS first (zero layout thrashing!)
  for (const ed of trackedEditors.values()) {
    if (!ed.el.isConnected) continue;

    if (ed.kind === "contenteditable") {
      for (const item of ed.issues) {
        if (!item.range) continue;
        if (item.issue.category === "clitic") cliticRanges.push(item.range);
        else if (item.issue.category === "deasc") deascRanges.push(item.range);
        else spellingRanges.push(item.range);
      }
    } else if (supportsOpaqueValueRange(ed.el)) {
      for (const item of ed.issues) {
        try {
          const opaqueRange: AbstractRange = (ed.el as any).createValueRange(
            item.issue.utf16Start,
            item.issue.utf16End
          );
          if (item.issue.category === "clitic") cliticRanges.push(opaqueRange);
          else if (item.issue.category === "deasc") deascRanges.push(opaqueRange);
          else spellingRanges.push(opaqueRange);
        } catch {
          // Ignore stale offset
        }
      }
    } else {
      mirrorReads.push({
        ed,
        rect: ed.el.getBoundingClientRect(),
        computed: window.getComputedStyle(ed.el),
        scrollTop: (ed.el as HTMLTextAreaElement).scrollTop ?? 0,
        scrollLeft: (ed.el as HTMLTextAreaElement).scrollLeft ?? 0,
        isSingleLine: ed.el.tagName === "INPUT",
      });
    }
  }

  // Phase 2: Batch all `CSS.highlights` & Shadow-DOM mirror WRITES
  if (typeof CSS !== "undefined" && CSS.highlights && typeof Highlight !== "undefined") {
    CSS.highlights.delete("zmbk-spelling");
    CSS.highlights.delete("zmbk-clitic");
    CSS.highlights.delete("zmbk-deasc");

    if (spellingRanges.length > 0) {
      const h = new Highlight(...spellingRanges);
      h.priority = 3;
      CSS.highlights.set("zmbk-spelling", h);
    }
    if (cliticRanges.length > 0) {
      const h = new Highlight(...cliticRanges);
      h.priority = 2;
      CSS.highlights.set("zmbk-clitic", h);
    }
    if (deascRanges.length > 0) {
      const h = new Highlight(...deascRanges);
      h.priority = 1;
      CSS.highlights.set("zmbk-deasc", h);
    }
  }

  for (const snap of mirrorReads) {
    const { ed, rect, computed, scrollTop, scrollLeft, isSingleLine } = snap;
    if (ed.issues.length === 0 || rect.width === 0 || rect.height === 0) {
      if (ed.mirrorHost) {
        ed.mirrorHost.style.display = "none";
      }
      continue;
    }

    ensureTextareaMirror(ed);
    const host = ed.mirrorHost!;
    const surface = ed.mirrorContent!;

    host.style.display = "block";
    host.style.left = `${rect.left}px`;
    host.style.top = `${rect.top}px`;
    host.style.width = `${rect.width}px`;
    host.style.height = `${rect.height}px`;

    surface.style.font = computed.font;
    surface.style.letterSpacing = computed.letterSpacing;
    surface.style.lineHeight = computed.lineHeight;
    surface.style.padding = computed.padding;
    surface.style.borderWidth = computed.borderWidth;
    surface.style.borderStyle = "solid";
    surface.style.borderColor = "transparent";
    surface.style.textAlign = computed.textAlign;
    surface.style.direction = computed.direction;
    surface.style.whiteSpace = isSingleLine ? "pre" : "pre-wrap";
    surface.style.width = `${rect.width}px`;
    surface.style.minHeight = `${rect.height}px`;
    surface.style.transform = `translate(${-scrollLeft}px, ${-scrollTop}px)`;

    const text = (ed.el as HTMLTextAreaElement | HTMLInputElement).value ?? "";
    const sorted = ed.issues.toSorted(
      (a, b) => a.issue.utf16Start - b.issue.utf16Start
    );
    const nodes: Node[] = [];
    let pos = 0;

    for (const item of sorted) {
      const { utf16Start, utf16End, category } = item.issue;
      if (utf16Start < pos || utf16End > text.length) continue;
      if (utf16Start > pos) {
        nodes.push(document.createTextNode(text.slice(pos, utf16Start)));
      }
      const mark = document.createElement("span");
      mark.className = `zmbk-mark zmbk-mark-${category}`;
      mark.dataset.zmbkStart = String(utf16Start);
      mark.dataset.zmbkEnd = String(utf16End);
      mark.textContent = text.slice(utf16Start, utf16End);
      nodes.push(mark);
      pos = utf16End;
    }
    if (pos < text.length) {
      nodes.push(document.createTextNode(text.slice(pos)));
    }
    surface.replaceChildren(...nodes);
  }
}

function applyCheckResponse(ed: TrackedEditor, resp: PortCheckResponse): void {
  rebuildEditorTextCache(ed);
  if (fnv1a32(ed.cachedText) !== resp.paragraphHash) {
    return;
  }

  ed.lastSkippedNonTurkish = Boolean(resp.skippedNonTurkish);
  syncNativeSpellcheckState(ed);

  ed.issues = resp.issues.map((iss) => ({
    issue: iss,
    range:
      ed.kind === "contenteditable"
        ? createDomRangeForUtf16Span(ed.cachedSlices, iss.utf16Start, iss.utf16End)
        : null,
  }));

  ed.el.setAttribute("data-zmbk-issue-count", String(ed.issues.length));
  ed.el.setAttribute("data-zmbk-non-tr", ed.lastSkippedNonTurkish ? "1" : "0");
  ed.el.dispatchEvent(
    new CustomEvent("zmbk:issues-updated", {
      detail: {
        count: ed.issues.length,
        elapsedMs: resp.elapsedMs,
        skippedNonTurkish: ed.lastSkippedNonTurkish,
      },
      bubbles: true,
      composed: true,
    })
  );

  scheduleHighlightFlush();
}

// ============================================================================
// Debounced Paragraph Check Dispatch
// ============================================================================

function scheduleEditorCheck(ed: TrackedEditor, immediate = false): void {
  if (isImeComposing) return;
  if (ed.debounceTimer !== null) {
    window.clearTimeout(ed.debounceTimer);
    ed.debounceTimer = null;
  }

  const run = async () => {
    ed.debounceTimer = null;
    if (isImeComposing || !ed.el.isConnected) return;

    ed.textCacheDirty = true;
    rebuildEditorTextCache(ed);
    const text = ed.cachedText;

    if (!clientSettings.enabled) {
      ed.lastHash = 0;
      ed.issues = [];
      syncNativeSpellcheckState(ed);
      ed.el.setAttribute("data-zmbk-issue-count", "0");
      scheduleHighlightFlush();
      return;
    }

    if (!text.trim()) {
      ed.lastHash = 0;
      ed.lastSkippedNonTurkish = false;
      ed.issues = [];
      syncNativeSpellcheckState(ed);
      ed.el.setAttribute("data-zmbk-issue-count", "0");
      ed.el.setAttribute("data-zmbk-non-tr", "0");
      scheduleHighlightFlush();
      return;
    }

    const hash = fnv1a32(text);
    if (!immediate && hash === ed.lastHash) {
      return;
    }
    ed.lastHash = hash;

    if (clientSettings.ignoreNonTurkish) {
      const explicitLang = ed.el.getAttribute("lang")?.trim().toLowerCase();
      const explicitForeignAttr =
        Boolean(explicitLang) &&
        !explicitLang!.startsWith("tr") &&
        !hasTurkishDiacritics(text);
      const clientNonTr =
        explicitForeignAttr || (await detectConfidentNonTurkishClientSide(text));

      if (clientNonTr) {
        ed.lastSkippedNonTurkish = true;
        ed.issues = [];
        syncNativeSpellcheckState(ed);
        ed.el.setAttribute("data-zmbk-issue-count", "0");
        ed.el.setAttribute("data-zmbk-non-tr", "1");
        scheduleHighlightFlush();
        return;
      }
    }

    const activePort = await getOrConnectPort();
    if (!activePort) return;

    const reqId = nextReqId++;
    ed.activeReqId = reqId;
    reqToEditor.set(reqId, ed.el);
    activePort.postMessage({
      type: "CHECK_PARAGRAPH",
      reqId,
      paragraphHash: hash,
      text,
    });
  };

  if (immediate) {
    void run();
  } else {
    ed.debounceTimer = window.setTimeout(() => void run(), DEBOUNCE_MS);
  }
}

// ============================================================================
// Safe Undo-Preserving Replacement (`beforeinput` + `execCommand('insertText')`)
// ============================================================================

function applyIssueReplacement(
  ed: TrackedEditor,
  resolved: ResolvedIssue,
  replacement: string
): void {
  const { utf16Start, utf16End } = resolved.issue;

  if (ed.kind === "textcontrol") {
    const ctrl = ed.el as HTMLTextAreaElement | HTMLInputElement;
    ctrl.focus();
    ctrl.setSelectionRange(utf16Start, utf16End);

    const beforeVal = ctrl.value;
    let applied = false;
    try {
      applied = document.execCommand("insertText", false, replacement);
    } catch {
      applied = false;
    }

    if (!applied || ctrl.value === beforeVal) {
      const proto =
        ctrl instanceof HTMLTextAreaElement
          ? HTMLTextAreaElement.prototype
          : HTMLInputElement.prototype;
      const nativeSetter = Object.getOwnPropertyDescriptor(proto, "value")?.set;
      const updated =
        beforeVal.slice(0, utf16Start) + replacement + beforeVal.slice(utf16End);
      if (nativeSetter) {
        nativeSetter.call(ctrl, updated);
      } else {
        ctrl.value = updated;
      }
      const caret = utf16Start + replacement.length;
      ctrl.setSelectionRange(caret, caret);
      ctrl.dispatchEvent(
        new InputEvent("input", {
          bubbles: true,
          composed: true,
          inputType: "insertReplacementText",
          data: replacement,
        })
      );
    }

    hideSuggestionCard();
    ed.lastHash = 0;
    scheduleEditorCheck(ed, true);
    return;
  }

  // `contenteditable` (Plain DOM, ProseMirror, Lexical, Slate, Gmail)
  ed.textCacheDirty = true;
  rebuildEditorTextCache(ed);
  const liveRange =
    createDomRangeForUtf16Span(ed.cachedSlices, utf16Start, utf16End) ??
    resolved.range;
  if (!liveRange) {
    hideSuggestionCard();
    return;
  }

  ed.el.focus();
  const sel = window.getSelection();
  if (sel) {
    sel.removeAllRanges();
    sel.addRange(liveRange);
  }

  const snapshotBefore = ed.el.textContent ?? "";
  const beforeInputEv = new InputEvent("beforeinput", {
    inputType: "insertReplacementText",
    data: replacement,
    bubbles: true,
    cancelable: true,
    composed: true,
  });
  const notCancelled = ed.el.dispatchEvent(beforeInputEv);

  // ProseMirror / plain contenteditable let `beforeinput` proceed to native `insertText`;
  // Lexical / Slate intercept `beforeinput` directly when focused.
  if (notCancelled && (ed.el.textContent ?? "") === snapshotBefore) {
    document.execCommand("insertText", false, replacement);
  }

  hideSuggestionCard();
  ed.lastHash = 0;
  scheduleEditorCheck(ed, true);
}

// ============================================================================
// Hover & Click Hit-Testing (`caretPositionFromPoint` + Mirror Span Lookup)
// ============================================================================

function categoryLabel(cat: IssueCategory): string {
  if (cat === "clitic") return "Ek / Ayrı-Bitişik Yazım";
  if (cat === "deasc") return "Türkçe Karakter / Günlük Dil";
  return "Yazım Denetimi";
}

function openSuggestionCard(
  ed: TrackedEditor,
  resolved: ResolvedIssue,
  anchorRect: DOMRect
): void {
  ensureShadowUi();
  if (!popoverCard) return;

  activeCardContext = { editor: ed, resolved, anchorRect };

  const header = document.createElement("div");
  header.className = "zmbk-header";

  const catBadge = document.createElement("span");
  catBadge.className = `zmbk-cat-badge zmbk-cat-${resolved.issue.category}`;
  catBadge.textContent = categoryLabel(resolved.issue.category);

  const morphBadge = document.createElement("span");
  morphBadge.className = "zmbk-morph-badge";
  morphBadge.textContent = "Çözümleniyor…";
  header.replaceChildren(catBadge, morphBadge);

  const sugContainer = document.createElement("div");
  sugContainer.className = "zmbk-suggestions";
  for (const sug of resolved.issue.suggestions) {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "zmbk-sug-btn";
    btn.textContent = sug;
    btn.addEventListener("click", (ev) => {
      ev.preventDefault();
      ev.stopPropagation();
      applyIssueReplacement(ed, resolved, sug);
    });
    sugContainer.appendChild(btn);
  }

  const footer = document.createElement("div");
  footer.className = "zmbk-footer";

  const rawWord = ed.cachedText
    .slice(resolved.issue.utf16Start, resolved.issue.utf16End)
    .trim();

  const addDictBtn = document.createElement("button");
  addDictBtn.type = "button";
  addDictBtn.className = "zmbk-secondary-btn";
  addDictBtn.textContent = "Sözlüğe Ekle";
  addDictBtn.addEventListener("click", (ev) => {
    ev.preventDefault();
    ev.stopPropagation();
    void (async () => {
      await chrome.runtime.sendMessage({
        type: "ADD_USER_WORD",
        word: rawWord,
      });
      hideSuggestionCard();
      showToast(`"${rawWord}" özel sözlüğe eklendi`);
      ed.lastHash = 0;
      scheduleEditorCheck(ed, true);
    })();
  });

  const fixAllBtn = document.createElement("button");
  fixAllBtn.type = "button";
  fixAllBtn.className = "zmbk-secondary-btn";
  fixAllBtn.textContent = "Tümünü Düzelt";
  fixAllBtn.addEventListener("click", (ev) => {
    ev.preventDefault();
    ev.stopPropagation();
    void (async () => {
      const activePort = await getOrConnectPort();
      if (!activePort) return;
      const fullText = ed.cachedText;
      const reqId = nextReqId++;
      pendingNormalizeCallbacks.set(reqId, (normText) => {
        if (normText && normText !== fullText) {
          applyIssueReplacement(
            ed,
            {
              issue: {
                utf16Start: 0,
                utf16End: fullText.length,
                utf8Start: 0,
                utf8End: 0,
                category: "deasc",
                replacement: normText,
                suggestions: [normText],
              },
              range:
                ed.kind === "contenteditable"
                  ? createDomRangeForUtf16Span(ed.cachedSlices, 0, fullText.length)
                  : null,
            },
            normText
          );
          showToast("Paragraf Zemberek ile düzeltildi");
        }
      });
      activePort.postMessage({
        type: "NORMALIZE_TEXT",
        reqId,
        text: fullText,
      });
    })();
  });

  footer.replaceChildren(addDictBtn, fixAllBtn);
  popoverCard.replaceChildren(header, sugContainer, footer);
  popoverCard.hidden = false;

  const viewportW = window.innerWidth;
  const viewportH = window.innerHeight;
  const cardW = 290;
  const cardH = 118;
  let left = Math.max(8, Math.min(anchorRect.left, viewportW - cardW - 12));
  let top = anchorRect.bottom + 6;
  if (top + cardH > viewportH - 8 && anchorRect.top - cardH - 6 > 8) {
    top = anchorRect.top - cardH - 6;
  }
  popoverCard.style.left = `${Math.round(left)}px`;
  popoverCard.style.top = `${Math.round(top)}px`;

  // Fetch morphological lemma + POS badge asynchronously for the primary suggestion
  void (async () => {
    const activePort = await getOrConnectPort();
    if (!activePort) return;
    const reqId = nextReqId++;
    pendingAnalyzeCallbacks.set(reqId, (tokens) => {
      if (activeCardContext?.resolved !== resolved) return;
      const first = tokens[0];
      if (first) {
        morphBadge.textContent = `${first.lemma} (${first.pos})`;
      } else {
        morphBadge.textContent = "";
      }
    });
    activePort.postMessage({
      type: "ANALYZE_TOKEN",
      reqId,
      text: resolved.issue.replacement,
    });
  })();
}

function hitTestEditorIssue(
  ed: TrackedEditor,
  clientX: number,
  clientY: number
): { resolved: ResolvedIssue; rect: DOMRect } | null {
  if (ed.issues.length === 0) return null;

  if (ed.kind === "contenteditable") {
    // Fast bounding-rect check across active `Range`s + `caretPositionFromPoint` refinement
    for (const res of ed.issues) {
      if (!res.range) continue;
      const rects = res.range.getClientRects();
      for (let i = 0; i < rects.length; i++) {
        const r = rects[i];
        if (
          clientX >= r.left - 2 &&
          clientX <= r.right + 2 &&
          clientY >= r.top - 2 &&
          clientY <= r.bottom + 2
        ) {
          return { resolved: res, rect: r };
        }
      }
    }

    if (typeof document.caretPositionFromPoint === "function") {
      const pos = document.caretPositionFromPoint(clientX, clientY);
      if (pos && pos.offsetNode.nodeType === Node.TEXT_NODE) {
        const slice = ed.cachedSlices.find((s) => s.node === pos.offsetNode);
        if (slice) {
          const globalUtf16 = slice.startUtf16 + pos.offset;
          const hit = ed.issues.find(
            (it) =>
              globalUtf16 >= it.issue.utf16Start &&
              globalUtf16 <= it.issue.utf16End
          );
          if (hit?.range) {
            return { resolved: hit, rect: hit.range.getBoundingClientRect() };
          }
        }
      }
    }
    return null;
  }

  // `<textarea>` / `<input>`: check native `OpaqueRange` (`createValueRange`) first, then mirror marks
  if (supportsOpaqueValueRange(ed.el)) {
    for (const res of ed.issues) {
      try {
        const opaque = (ed.el as any).createValueRange(
          res.issue.utf16Start,
          res.issue.utf16End
        );
        if (typeof opaque?.getClientRects === "function") {
          const rects: DOMRectList = opaque.getClientRects();
          for (let i = 0; i < rects.length; i++) {
            const r = rects[i];
            if (
              clientX >= r.left - 2 &&
              clientX <= r.right + 2 &&
              clientY >= r.top - 2 &&
              clientY <= r.bottom + 2
            ) {
              return { resolved: res, rect: r };
            }
          }
        }
      } catch {
        // Fall through to selectionStart / mirror check
      }
    }
    const ctrl = ed.el as HTMLTextAreaElement | HTMLInputElement;
    const caret = ctrl.selectionStart ?? -1;
    if (caret >= 0) {
      const hit = ed.issues.find(
        (it) => caret >= it.issue.utf16Start && caret <= it.issue.utf16End
      );
      if (hit) {
        return { resolved: hit, rect: ed.el.getBoundingClientRect() };
      }
    }
  }

  if (ed.mirrorContent) {
    const marks = ed.mirrorContent.querySelectorAll<HTMLSpanElement>(".zmbk-mark");
    for (let i = 0; i < marks.length; i++) {
      const m = marks[i];
      const r = m.getBoundingClientRect();
      if (
        clientX >= r.left - 2 &&
        clientX <= r.right + 2 &&
        clientY >= r.top - 2 &&
        clientY <= r.bottom + 2
      ) {
        const start = Number(m.dataset.zmbkStart);
        const end = Number(m.dataset.zmbkEnd);
        const hit = ed.issues.find(
          (it) => it.issue.utf16Start === start && it.issue.utf16End === end
        );
        if (hit) {
          return { resolved: hit, rect: r };
        }
      }
    }
  }
  return null;
}

// ============================================================================
// Editor Discovery & Lifecycle Registration
// ============================================================================

function isEligibleTextControl(el: Element): el is HTMLTextAreaElement | HTMLInputElement {
  if (el instanceof HTMLTextAreaElement) {
    return !el.readOnly && !el.disabled;
  }
  if (el instanceof HTMLInputElement) {
    const type = (el.type || "text").toLowerCase();
    return (type === "text" || type === "search") && !el.readOnly && !el.disabled;
  }
  return false;
}

function findEditableRoot(target: EventTarget | null): HTMLElement | null {
  if (!(target instanceof HTMLElement)) return null;
  if (target.closest("#zmbk-assistant-root")) return null;
  if (target.closest(".monaco-editor, .cm-editor, .CodeMirror, .ace_editor")) return null;
  if (isEligibleTextControl(target)) return target;

  if (target.isContentEditable) {
    let cur: HTMLElement | null = target;
    while (cur.parentElement && cur.parentElement.isContentEditable) {
      cur = cur.parentElement;
    }
    if (cur.closest(".monaco-editor, .cm-editor, .CodeMirror, .ace_editor")) return null;
    return cur;
  }
  return null;
}

function attachEditor(el: HTMLElement): TrackedEditor {
  const existing = trackedEditors.get(el);
  if (existing) return existing;

  const kind: "contenteditable" | "textcontrol" = isEligibleTextControl(el)
    ? "textcontrol"
    : "contenteditable";

  const ed: TrackedEditor = {
    el,
    kind,
    origSpellcheckAttr: el.getAttribute("spellcheck"),
    lastSkippedNonTurkish: false,
    lastHash: 0,
    debounceTimer: null,
    activeReqId: 0,
    textCacheDirty: true,
    cachedText: "",
    cachedSlices: [],
    issues: [],
    mutationObserver: null,
    resizeObserver: null,
    mirrorHost: null,
    mirrorContent: null,
  };

  syncNativeSpellcheckState(ed);

  if (kind === "contenteditable") {
    const mo = new MutationObserver(() => {
      ed.textCacheDirty = true;
      scheduleEditorCheck(ed, false);
    });
    mo.observe(el, {
      subtree: true,
      childList: true,
      characterData: true,
    });
    ed.mutationObserver = mo;
  } else {
    el.addEventListener("scroll", () => scheduleHighlightFlush(), { passive: true });
    if (typeof ResizeObserver !== "undefined") {
      const ro = new ResizeObserver(() => scheduleHighlightFlush());
      ro.observe(el);
      ed.resizeObserver = ro;
    }
  }

  el.addEventListener("input", () => {
    ed.textCacheDirty = true;
    scheduleEditorCheck(ed, false);
  });

  const handlePointerInspect = (ev: MouseEvent) => {
    const hit = hitTestEditorIssue(ed, ev.clientX, ev.clientY);
    if (hit) {
      if (activeCardContext?.resolved !== hit.resolved) {
        openSuggestionCard(ed, hit.resolved, hit.rect);
      }
    }
  };

  el.addEventListener("mousemove", handlePointerInspect, { passive: true });
  el.addEventListener("click", handlePointerInspect);

  trackedEditors.set(el, ed);
  scheduleEditorCheck(ed, true);
  return ed;
}

// ============================================================================
// Global Event Listeners & Context Menu Selection Replacement
// ============================================================================

document.addEventListener(
  "compositionstart",
  () => {
    isImeComposing = true;
  },
  true
);

document.addEventListener(
  "compositionend",
  (ev) => {
    isImeComposing = false;
    const root = findEditableRoot(ev.target);
    if (root) {
      const ed = attachEditor(root);
      scheduleEditorCheck(ed, false);
    }
  },
  true
);

document.addEventListener(
  "focusin",
  (ev) => {
    const root = findEditableRoot(ev.target);
    if (root) {
      attachEditor(root);
    }
  },
  true
);

document.addEventListener(
  "pointerdown",
  (ev) => {
    tryInitWebLanguageDetectorOnUserGesture();
    if (!popoverCard || popoverCard.hidden) return;
    const path = ev.composedPath();
    if (uiHost && path.includes(uiHost)) return;
    hideSuggestionCard();
  },
  true
);

document.addEventListener(
  "keydown",
  () => {
    tryInitWebLanguageDetectorOnUserGesture();
  },
  true
);

if (typeof chrome !== "undefined" && chrome.storage?.onChanged) {
  chrome.storage.onChanged.addListener((changes, areaName) => {
    if (areaName === "sync" && changes.zmbk_settings?.newValue) {
      clientSettings = {
        ...clientSettings,
        ...(changes.zmbk_settings.newValue as Partial<ExtensionSettings>),
      };
      syncAllEditorsNativeSpellcheck();
      for (const ed of trackedEditors.values()) {
        ed.lastHash = 0;
        scheduleEditorCheck(ed, true);
      }
    }
  });
}

window.addEventListener("scroll", () => scheduleHighlightFlush(), {
  passive: true,
  capture: true,
});
window.addEventListener("resize", () => scheduleHighlightFlush(), {
  passive: true,
});

// Proactively disconnect port before entering BFCache so Chrome never raises a forced-close error,
// and seamlessly re-validate tracked editors if the page is restored from BFCache.
window.addEventListener("pagehide", () => {
  if (port) {
    try {
      port.disconnect();
    } catch {
      // Ignore already-closed channel
    }
    port = null;
  }
});

window.addEventListener("pageshow", (ev) => {
  if (ev.persisted) {
    for (const ed of trackedEditors.values()) {
      ed.lastHash = 0;
      scheduleEditorCheck(ed, true);
    }
  }
});

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message?.type === "APPLY_SELECTION_RESULT") {
    const resultText = String(message.result ?? "");
    const active = document.activeElement;
    if (active instanceof HTMLTextAreaElement || active instanceof HTMLInputElement) {
      const start = active.selectionStart ?? 0;
      const end = active.selectionEnd ?? start;
      if (end > start) {
        active.focus();
        document.execCommand("insertText", false, resultText);
      }
    } else if (active instanceof HTMLElement && active.isContentEditable) {
      document.execCommand("insertText", false, resultText);
    } else if (navigator.clipboard?.writeText) {
      void navigator.clipboard.writeText(resultText);
    }
    showToast(`${String(message.actionLabel ?? "Zemberek")}: panoya / alana uygulandı`);
    sendResponse({ ok: true });
    return;
  }

  if (message?.type === "RECHECK_ALL_EDITORS") {
    for (const ed of trackedEditors.values()) {
      ed.lastHash = 0;
      scheduleEditorCheck(ed, true);
    }
    sendResponse({ ok: true });
    return;
  }
});

// Scan initial visible editable fields on `document_idle`
function scanInitialEditables(): void {
  const candidates = document.querySelectorAll<HTMLElement>(
    'textarea, input[type="text"], input[type="search"], input:not([type]), [contenteditable="true"], [contenteditable=""]'
  );
  for (let i = 0; i < candidates.length; i++) {
    const root = findEditableRoot(candidates[i]);
    if (root) {
      attachEditor(root);
    }
  }
}

scanInitialEditables();
