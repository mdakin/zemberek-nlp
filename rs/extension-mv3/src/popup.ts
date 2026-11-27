import type { ExtensionSettings } from "./shared/protocol";

const scratchInput = document.getElementById("scratch-input") as HTMLTextAreaElement;
const btnNormalize = document.getElementById("btn-normalize") as HTMLButtonElement;
const btnDeasciify = document.getElementById("btn-deasciify") as HTMLButtonElement;
const btnCopy = document.getElementById("btn-copy") as HTMLButtonElement;
const btnClear = document.getElementById("btn-clear") as HTMLButtonElement;
const dictInput = document.getElementById("dict-word-input") as HTMLInputElement;
const btnAddWord = document.getElementById("btn-add-word") as HTMLButtonElement;
const dictChips = document.getElementById("dict-chips") as HTMLDivElement;
const toggleEnabled = document.getElementById("toggle-enabled") as HTMLInputElement;
const btnOpenSidePanel = document.getElementById("btn-open-sidepanel") as HTMLButtonElement;
const btnOpenSettings = document.getElementById("btn-open-settings") as HTMLButtonElement;

const copyTimers = new WeakMap<HTMLButtonElement, number>();
let lastCopyMessage = "";

function reportCopyResult(button: HTMLButtonElement, ok: boolean): void {
  const tip = button.nextElementSibling as HTMLElement | null;
  const prevTimer = copyTimers.get(button);
  if (prevTimer !== undefined) {
    window.clearTimeout(prevTimer);
  }
  button.removeAttribute("data-copied");
  button.removeAttribute("data-failed");

  let message = ok ? "Kopyalandı" : "Kopyalanamadı";
  if (message === lastCopyMessage) {
    message += "\u00A0";
  }
  lastCopyMessage = message;

  if (tip) {
    tip.textContent = "";
  }
  void button.offsetWidth;
  button.setAttribute(ok ? "data-copied" : "data-failed", "");
  if (tip) {
    requestAnimationFrame(() => {
      tip.textContent = message;
    });
  }

  copyTimers.set(
    button,
    window.setTimeout(() => {
      button.removeAttribute("data-copied");
      button.removeAttribute("data-failed");
      if (tip) {
        tip.textContent = "";
      }
    }, 1800)
  );
}

function renderChips(words: readonly string[]): void {
  const nodes = words.map((word) => {
    const chip = document.createElement("span");
    chip.className = "chip";
    const text = document.createElement("span");
    text.textContent = word;
    const delBtn = document.createElement("button");
    delBtn.type = "button";
    delBtn.ariaLabel = `${word} kelimesini kaldır`;
    delBtn.title = "Kaldır";
    delBtn.textContent = "×";
    delBtn.addEventListener("click", () => {
      void (async () => {
        const res = await chrome.runtime.sendMessage({
          type: "REMOVE_USER_WORD",
          word,
        });
        if (res?.ok) {
          renderChips(res.userWords ?? []);
          await broadcastRecheck();
        }
      })();
    });
    chip.replaceChildren(text, delBtn);
    return chip;
  });
  dictChips.replaceChildren(...nodes);
}

async function broadcastRecheck(): Promise<void> {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (tab?.id) {
    await chrome.tabs.sendMessage(tab.id, { type: "RECHECK_ALL_EDITORS" }).catch(() => null);
  }
}

async function refreshPopupState(): Promise<void> {
  const [stateResp, savedDraft] = await Promise.all([
    chrome.runtime.sendMessage({ type: "GET_STATE" }),
    chrome.storage.local.get("zmbk_scratch_draft"),
  ]);

  if (typeof savedDraft.zmbk_scratch_draft === "string" && !scratchInput.value) {
    scratchInput.value = savedDraft.zmbk_scratch_draft;
  }

  if (stateResp?.ok) {
    const s = stateResp.settings as ExtensionSettings;
    toggleEnabled.checked = s.enabled;
    renderChips(stateResp.userWords ?? []);
  }
}

scratchInput.addEventListener("input", () => {
  void chrome.storage.local.set({ zmbk_scratch_draft: scratchInput.value });
});

toggleEnabled.addEventListener("change", () => {
  void (async () => {
    await chrome.runtime.sendMessage({
      type: "UPDATE_SETTINGS",
      settings: { enabled: toggleEnabled.checked },
    });
    await broadcastRecheck();
  })();
});

btnNormalize.addEventListener("click", () => {
  void (async () => {
    if (!scratchInput.value.trim()) return;
    const reply = await chrome.runtime.sendMessage({
      type: "PROXY_OFFSCREEN",
      payload: { type: "NORMALIZE", text: scratchInput.value },
    });
    if (reply?.ok && typeof reply.result === "string") {
      scratchInput.value = reply.result;
      await chrome.storage.local.set({ zmbk_scratch_draft: scratchInput.value });
    }
  })();
});

btnDeasciify.addEventListener("click", () => {
  void (async () => {
    if (!scratchInput.value.trim()) return;
    const reply = await chrome.runtime.sendMessage({
      type: "PROXY_OFFSCREEN",
      payload: {
        type: "DEASCIIFY",
        text: scratchInput.value,
      },
    });
    if (reply?.ok && typeof reply.result === "string") {
      scratchInput.value = reply.result;
      await chrome.storage.local.set({ zmbk_scratch_draft: scratchInput.value });
    }
  })();
});

btnCopy.addEventListener("click", () => {
  void (async () => {
    if (!scratchInput.value) return;
    try {
      await navigator.clipboard.writeText(scratchInput.value);
      reportCopyResult(btnCopy, true);
    } catch {
      reportCopyResult(btnCopy, false);
    }
  })();
});

btnClear.addEventListener("click", () => {
  scratchInput.value = "";
  void chrome.storage.local.remove("zmbk_scratch_draft");
  scratchInput.focus();
});

const submitNewWord = async (): Promise<void> => {
  const word = dictInput.value.trim();
  if (!word) return;
  dictInput.value = "";
  const res = await chrome.runtime.sendMessage({
    type: "ADD_USER_WORD",
    word,
  });
  if (res?.ok) {
    renderChips(res.userWords ?? []);
    await broadcastRecheck();
  }
};

btnAddWord.addEventListener("click", () => void submitNewWord());
dictInput.addEventListener("keydown", (ev) => {
  if (ev.key === "Enter") {
    ev.preventDefault();
    void submitNewWord();
  }
});

btnOpenSettings.addEventListener("click", () => {
  void chrome.runtime.openOptionsPage();
});

// Explicit Side Panel open trigger from Popup (`chrome-extensions` Mandatory Rule #2)
btnOpenSidePanel.addEventListener("click", () => {
  void (async () => {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (tab) {
      await chrome.sidePanel.open({ windowId: tab.windowId });
      window.close();
    }
  })();
});

void refreshPopupState();
