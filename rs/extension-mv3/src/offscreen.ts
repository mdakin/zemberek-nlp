import {
  CHECK_PORT_NAME,
  DEFAULT_SETTINGS,
  type ContentPortRequest,
  type ContentPortResponse,
  type ExtensionSettings,
  type WorkerStats,
} from "./shared/protocol";

// IMPORTANT (MV3 Offscreen restriction): Only `chrome.runtime` messaging & `getURL` + Web APIs
// are used inside `offscreen.ts` (`chrome.storage` / `chrome.tabs` are never accessed here).

const worker = new Worker(chrome.runtime.getURL("wasm-worker.js"), {
  type: "module",
});

interface PendingRpc {
  resolve: (val: any) => void;
  reject: (err: Error) => void;
}

let nextPortId = 1;
let nextRpcId = 1;
const activePorts = new Map<number, chrome.runtime.Port>();
const pendingRpcs = new Map<number, PendingRpc>();
let currentSettings: ExtensionSettings = { ...DEFAULT_SETTINGS };

function callWorkerRpc<T = any>(payload: Record<string, unknown>): Promise<T> {
  const rpcId = nextRpcId++;
  return new Promise<T>((resolve, reject) => {
    pendingRpcs.set(rpcId, { resolve, reject });
    worker.postMessage({ kind: "RPC", rpcId, ...payload });
  });
}

worker.addEventListener("message", (event: MessageEvent) => {
  const msg = event.data;
  if (!msg) return;

  if (msg.kind === "PORT_REPLY") {
    const port = activePorts.get(msg.portId);
    if (port) {
      try {
        port.postMessage(msg.response as ContentPortResponse);
      } catch {
        activePorts.delete(msg.portId);
      }
    }
    return;
  }

  if (msg.kind === "RPC_REPLY") {
    const pending = pendingRpcs.get(msg.rpcId);
    if (!pending) return;
    pendingRpcs.delete(msg.rpcId);
    if (msg.error) {
      pending.reject(new Error(String(msg.error)));
    } else {
      pending.resolve(msg.result);
    }
  }
});

// Multiplex all open tab content-script ports onto the single Dedicated Wasm Worker!
chrome.runtime.onConnect.addListener((port) => {
  if (port.name !== CHECK_PORT_NAME) return;

  const portId = nextPortId++;
  activePorts.set(portId, port);

  port.onMessage.addListener((rawReq: ContentPortRequest) => {
    if (!currentSettings.enabled && rawReq.type === "CHECK_PARAGRAPH") {
      port.postMessage({
        type: "CHECK_RESULT",
        reqId: rawReq.reqId,
        paragraphHash: rawReq.paragraphHash,
        issues: [],
        elapsedMs: 0,
      } satisfies ContentPortResponse);
      return;
    }
    worker.postMessage({
      kind: "PORT_REQ",
      portId,
      request: rawReq,
    });
  });

  port.onDisconnect.addListener(() => {
    activePorts.delete(portId);
  });
});

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (!message || message.target !== "offscreen") {
    return;
  }

  (async () => {
    switch (message.type) {
      case "SYNC_STATE": {
        if (message.settings) {
          currentSettings = { ...currentSettings, ...message.settings };
        }
        const result = await callWorkerRpc({
          op: "SYNC_STATE",
          settings: currentSettings,
          userWords: Array.isArray(message.userWords) ? message.userWords : [],
        });
        sendResponse({ ok: true, result });
        break;
      }

      case "NORMALIZE": {
        const result = await callWorkerRpc<{ result: string }>({
          op: "NORMALIZE",
          text: String(message.text ?? ""),
        });
        sendResponse({ ok: true, ...result });
        break;
      }

      case "DEASCIIFY": {
        const result = await callWorkerRpc<{ result: string }>({
          op: "DEASCIIFY",
          text: String(message.text ?? ""),
          circumflexMode: message.circumflexMode ?? currentSettings.circumflexMode,
        });
        sendResponse({ ok: true, ...result });
        break;
      }

      case "CHECK_PARAGRAPH": {
        const result = await callWorkerRpc({
          op: "CHECK_PARAGRAPH",
          text: String(message.text ?? ""),
        });
        sendResponse({ ok: true, ...result });
        break;
      }

      case "ANALYZE": {
        const result = await callWorkerRpc({
          op: "ANALYZE",
          text: String(message.text ?? ""),
        });
        sendResponse({ ok: true, ...result });
        break;
      }

      case "WORKER_STATS": {
        const wStats = await callWorkerRpc<Omit<WorkerStats, "activePortCount">>({
          op: "STATS",
        });
        sendResponse({
          ok: true,
          activePortCount: activePorts.size,
          ...wStats,
        } satisfies WorkerStats & { ok: true });
        break;
      }

      default:
        sendResponse({ ok: false, error: `Unknown offscreen op: ${String(message.type)}` });
    }
  })().catch((err) => {
    sendResponse({ ok: false, error: err instanceof Error ? err.message : String(err) });
  });

  return true;
});

// Pull initial settings & custom user dictionary from Service Worker on boot
(async () => {
  try {
    const boot = await chrome.runtime.sendMessage({ type: "OFFSCREEN_BOOTED" });
    if (boot?.ok) {
      if (boot.settings) {
        currentSettings = { ...currentSettings, ...boot.settings };
      }
      await callWorkerRpc({
        op: "SYNC_STATE",
        settings: currentSettings,
        userWords: Array.isArray(boot.userWords) ? boot.userWords : [],
      });
    }
  } catch (err) {
    console.warn("[Zemberek Offscreen] Initial boot sync deferred:", err);
  }
})();
