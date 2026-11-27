import { DEFAULT_SETTINGS, type ExtensionSettings } from "./shared/protocol";
import {
  addWordToDictionary,
  loadSettings,
  loadUserDictionary,
  removeWordFromDictionary,
  saveSettings,
} from "./shared/storage";

const MENU_NORMALIZE = "zmbk-normalize-selection";
const MENU_DEASCIIFY = "zmbk-deasciify-selection";
const MENU_OPEN_SIDEPANEL = "zmbk-open-sidepanel";
const SESSION_LOCK_KEY = "zmbk_offscreen_lock";

async function hasOffscreenDocument(): Promise<boolean> {
  const contexts = await chrome.runtime.getContexts({
    contextTypes: ["OFFSCREEN_DOCUMENT"],
  });
  return contexts.length > 0;
}

async function pushStateToOffscreen(): Promise<void> {
  const [settings, userWords] = await Promise.all([
    loadSettings(),
    loadUserDictionary(),
  ]);
  try {
    await chrome.runtime.sendMessage({
      target: "offscreen",
      type: "SYNC_STATE",
      settings,
      userWords,
    });
  } catch {
    // Offscreen worker may still be finishing its initial module evaluation; it pulls on boot too.
  }
}

async function ensureOffscreenReady(): Promise<void> {
  if (await hasOffscreenDocument()) {
    return;
  }

  // Guard concurrent tab wakeups via chrome.storage.session transitional state lock
  const sessionData = await chrome.storage.session.get(SESSION_LOCK_KEY);
  if (sessionData[SESSION_LOCK_KEY] === "creating") {
    for (let attempt = 0; attempt < 25; attempt++) {
      await new Promise((r) => setTimeout(r, 20));
      if (await hasOffscreenDocument()) return;
    }
  }

  await chrome.storage.session.set({ [SESSION_LOCK_KEY]: "creating" });
  try {
    if (!(await hasOffscreenDocument())) {
      await chrome.offscreen.createDocument({
        url: "offscreen.html",
        reasons: ["WORKERS"],
        justification:
          "Host shared singleton WebAssembly Worker for Turkish morphological spellchecking and normalization across browser tabs.",
      });
      await pushStateToOffscreen();
    }
  } finally {
    await chrome.storage.session.set({ [SESSION_LOCK_KEY]: "idle" });
  }
}

async function setupContextMenus(): Promise<void> {
  await chrome.contextMenus.removeAll();
  chrome.contextMenus.create({
    id: MENU_NORMALIZE,
    title: "Zemberek ile Düzelt (Normalize)",
    contexts: ["selection"],
  });
  chrome.contextMenus.create({
    id: MENU_DEASCIIFY,
    title: "Türkçe Karakterleri Tamamla (Deasciify)",
    contexts: ["selection"],
  });
  chrome.contextMenus.create({
    id: MENU_OPEN_SIDEPANEL,
    title: "Zemberek Yan Panelini Aç",
    contexts: ["page", "editable"],
  });
}

// ============================================================================
// Top-level synchronous MV3 Service Worker event registration (zero global state)
// ============================================================================

chrome.runtime.onInstalled.addListener((details) => {
  (async () => {
    if (details.reason === "install") {
      const existing = await loadSettings();
      await saveSettings({ ...DEFAULT_SETTINGS, ...existing });
    }
    await setupContextMenus();
    await ensureOffscreenReady();
  })().catch((err) => console.error("[Zemberek SW] onInstalled error:", err));
});

chrome.runtime.onStartup.addListener(() => {
  (async () => {
    await ensureOffscreenReady();
  })().catch((err) => console.error("[Zemberek SW] onStartup error:", err));
});

chrome.storage.onChanged.addListener(() => {
  (async () => {
    if (await hasOffscreenDocument()) {
      await pushStateToOffscreen();
    }
  })().catch((err) => console.error("[Zemberek SW] storage bridge error:", err));
});

chrome.contextMenus.onClicked.addListener((info, tab) => {
  (async () => {
    if (info.menuItemId === MENU_OPEN_SIDEPANEL && tab) {
      await chrome.sidePanel.open({ windowId: tab.windowId });
      return;
    }

    const selected = info.selectionText?.trim();
    if (!selected || !tab?.id) return;

    await ensureOffscreenReady();
    const settings = await loadSettings();

    if (info.menuItemId === MENU_NORMALIZE) {
      const res = await chrome.runtime.sendMessage({
        target: "offscreen",
        type: "NORMALIZE",
        text: selected,
      });
      await chrome.tabs.sendMessage(tab.id, {
        type: "APPLY_SELECTION_RESULT",
        actionLabel: "Metin düzeltildi",
        original: selected,
        result: res?.result ?? selected,
      });
    } else if (info.menuItemId === MENU_DEASCIIFY) {
      const res = await chrome.runtime.sendMessage({
        target: "offscreen",
        type: "DEASCIIFY",
        text: selected,
        circumflexMode: settings.circumflexMode,
      });
      await chrome.tabs.sendMessage(tab.id, {
        type: "APPLY_SELECTION_RESULT",
        actionLabel: "Türkçe karakterler tamamlandı",
        original: selected,
        result: res?.result ?? selected,
      });
    }
  })().catch((err) => console.warn("[Zemberek SW] contextMenus action error:", err));
});

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (!message || message.target === "offscreen") {
    return;
  }

  (async () => {
    switch (message.type) {
      case "ENSURE_OFFSCREEN": {
        await ensureOffscreenReady();
        const [settings, userWords] = await Promise.all([
          loadSettings(),
          loadUserDictionary(),
        ]);
        sendResponse({ ok: true, settings, userWords });
        break;
      }

      case "OFFSCREEN_BOOTED": {
        const [settings, userWords] = await Promise.all([
          loadSettings(),
          loadUserDictionary(),
        ]);
        sendResponse({ ok: true, settings, userWords });
        break;
      }

      case "GET_STATE": {
        await ensureOffscreenReady();
        const [settings, userWords, stats] = await Promise.all([
          loadSettings(),
          loadUserDictionary(),
          chrome.runtime
            .sendMessage({ target: "offscreen", type: "WORKER_STATS" })
            .catch(() => null),
        ]);
        sendResponse({ ok: true, settings, userWords, stats });
        break;
      }

      case "UPDATE_SETTINGS": {
        const settings = await saveSettings(
          (message.settings ?? {}) as Partial<ExtensionSettings>
        );
        await ensureOffscreenReady();
        await pushStateToOffscreen();
        sendResponse({ ok: true, settings });
        break;
      }

      case "ADD_USER_WORD": {
        const userWords = await addWordToDictionary(String(message.word ?? ""));
        await ensureOffscreenReady();
        await pushStateToOffscreen();
        sendResponse({ ok: true, userWords });
        break;
      }

      case "REMOVE_USER_WORD": {
        const userWords = await removeWordFromDictionary(String(message.word ?? ""));
        await ensureOffscreenReady();
        await pushStateToOffscreen();
        sendResponse({ ok: true, userWords });
        break;
      }

      case "PROXY_OFFSCREEN": {
        await ensureOffscreenReady();
        const reply = await chrome.runtime.sendMessage({
          ...message.payload,
          target: "offscreen",
        });
        sendResponse(reply);
        break;
      }

      default:
        sendResponse({ ok: false, error: `Unknown SW message: ${String(message.type)}` });
    }
  })().catch((err) => {
    sendResponse({ ok: false, error: err instanceof Error ? err.message : String(err) });
  });

  return true;
});
