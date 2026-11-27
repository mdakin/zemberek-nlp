import type { EngineProfile, ExtensionSettings } from "./shared/protocol";
import type { CircumflexOption } from "./wasm/zemberek-client";

const profileSelect = document.getElementById("opt-profile") as HTMLSelectElement;
const circumflexSelect = document.getElementById("opt-circumflex") as HTMLSelectElement;
const ignoreNonTrCheckbox = document.getElementById("opt-ignore-non-tr") as HTMLInputElement;
const suppressNativeCheckbox = document.getElementById("opt-suppress-native") as HTMLInputElement;
const statusEl = document.getElementById("opt-status") as HTMLDivElement;

let statusTimer: number | null = null;
let lastAnnouncement = "";

function showSaved(): void {
  if (statusTimer !== null) {
    window.clearTimeout(statusTimer);
  }
  let msg = "Ayarlar kaydedildi";
  if (msg === lastAnnouncement) {
    msg += "\u00A0";
  }
  lastAnnouncement = msg;
  statusEl.textContent = msg;
  statusTimer = window.setTimeout(() => {
    statusEl.textContent = "";
  }, 1800);
}

async function broadcastRecheckAllTabs(): Promise<void> {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (tab?.id) {
    await chrome.tabs.sendMessage(tab.id, { type: "RECHECK_ALL_EDITORS" }).catch(() => null);
  }
}

async function initOptions(): Promise<void> {
  const resp = await chrome.runtime.sendMessage({ type: "GET_STATE" });
  if (resp?.ok && resp.settings) {
    const s = resp.settings as ExtensionSettings;
    profileSelect.value = s.engineProfile;
    circumflexSelect.value = s.circumflexMode;
    ignoreNonTrCheckbox.checked = s.ignoreNonTurkish;
    suppressNativeCheckbox.checked = s.suppressNativeSpellcheck;
  }
}

async function persistSettings(partial: Partial<ExtensionSettings>): Promise<void> {
  await chrome.runtime.sendMessage({
    type: "UPDATE_SETTINGS",
    settings: partial,
  });
  showSaved();
  await broadcastRecheckAllTabs();
}

profileSelect.addEventListener("change", () => {
  void persistSettings({ engineProfile: profileSelect.value as EngineProfile });
});

circumflexSelect.addEventListener("change", () => {
  void persistSettings({ circumflexMode: circumflexSelect.value as CircumflexOption });
});

ignoreNonTrCheckbox.addEventListener("change", () => {
  void persistSettings({ ignoreNonTurkish: ignoreNonTrCheckbox.checked });
});

suppressNativeCheckbox.addEventListener("change", () => {
  void persistSettings({ suppressNativeSpellcheck: suppressNativeCheckbox.checked });
});

void initOptions();
