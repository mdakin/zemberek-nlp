import type { TokenDisambiguation, WasmSpanIssue } from "./wasm/zemberek-client";

const statusEl = document.getElementById("sp-status") as HTMLSpanElement;
const editorEl = document.getElementById("sp-editor") as HTMLTextAreaElement;
const btnCheck = document.getElementById("sp-btn-check") as HTMLButtonElement;
const btnNormalize = document.getElementById("sp-btn-normalize") as HTMLButtonElement;
const btnDeasc = document.getElementById("sp-btn-deasc") as HTMLButtonElement;
const btnMorph = document.getElementById("sp-btn-morph") as HTMLButtonElement;
const btnCopy = document.getElementById("sp-btn-copy") as HTMLButtonElement;
const btnClear = document.getElementById("sp-btn-clear") as HTMLButtonElement;
const btnSettings = document.getElementById("sp-btn-settings") as HTMLButtonElement;
const issuesContainer = document.getElementById("sp-issues") as HTMLDivElement;
const morphContainer = document.getElementById("sp-morph") as HTMLDivElement;

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

function resetPanelsToIdle(): void {
  statusEl.textContent = "";
  const emptyIssues = document.createElement("span");
  emptyIssues.className = "meta";
  emptyIssues.textContent = "Henüz denetim çalıştırılmadı.";
  issuesContainer.replaceChildren(emptyIssues);

  const emptyMorph = document.createElement("span");
  emptyMorph.className = "meta";
  emptyMorph.textContent = 'Çözümleme görmek için "Çözümle" düğmesine tıklayın.';
  morphContainer.replaceChildren(emptyMorph);
}

async function initSidePanel(): Promise<void> {
  const saved = await chrome.storage.local.get("zmbk_scratch_draft");
  if (typeof saved.zmbk_scratch_draft === "string") {
    editorEl.value = saved.zmbk_scratch_draft;
  }
}

editorEl.addEventListener("input", () => {
  void chrome.storage.local.set({ zmbk_scratch_draft: editorEl.value });
});

function renderIssues(
  text: string,
  issues: readonly WasmSpanIssue[],
  skippedNonTurkish: boolean
): void {
  if (skippedNonTurkish) {
    statusEl.textContent = "";
    const nonTrMsg = document.createElement("span");
    nonTrMsg.className = "meta";
    nonTrMsg.textContent = "Metin Türkçe olarak algılanmadığı için öneri gösterilmedi.";
    issuesContainer.replaceChildren(nonTrMsg);
    return;
  }

  statusEl.textContent = issues.length > 0 ? `${issues.length} öneri` : "";
  if (issues.length === 0) {
    const cleanMsg = document.createElement("span");
    cleanMsg.className = "meta";
    cleanMsg.textContent = "Yazım veya ek hatası bulunamadı.";
    issuesContainer.replaceChildren(cleanMsg);
    return;
  }

  const rows = issues.map((iss) => {
    const row = document.createElement("div");
    row.className = "issue-row";

    const label = document.createElement("span");
    label.className = "issue-label";
    const rawSlice = text.slice(iss.utf16Start, iss.utf16End);
    label.textContent = `${rawSlice} → ${iss.replacement}`;

    const applyBtn = document.createElement("button");
    applyBtn.type = "button";
    applyBtn.textContent = "Uygula";
    applyBtn.addEventListener("click", () => {
      editorEl.value =
        editorEl.value.slice(0, iss.utf16Start) +
        iss.replacement +
        editorEl.value.slice(iss.utf16End);
      void chrome.storage.local.set({ zmbk_scratch_draft: editorEl.value });
      void runCheck();
    });

    row.replaceChildren(label, applyBtn);
    return row;
  });

  issuesContainer.replaceChildren(...rows);
}

async function runCheck(): Promise<void> {
  const text = editorEl.value;
  if (!text.trim()) return;
  const reply = await chrome.runtime.sendMessage({
    type: "PROXY_OFFSCREEN",
    payload: { type: "CHECK_PARAGRAPH", text },
  });
  if (reply?.ok && Array.isArray(reply.issues)) {
    renderIssues(
      text,
      reply.issues as WasmSpanIssue[],
      Boolean(reply.skippedNonTurkish)
    );
  }
}

btnCheck.addEventListener("click", () => void runCheck());

btnNormalize.addEventListener("click", () => {
  void (async () => {
    if (!editorEl.value.trim()) return;
    const reply = await chrome.runtime.sendMessage({
      type: "PROXY_OFFSCREEN",
      payload: { type: "NORMALIZE", text: editorEl.value },
    });
    if (reply?.ok && typeof reply.result === "string") {
      editorEl.value = reply.result;
      await chrome.storage.local.set({ zmbk_scratch_draft: editorEl.value });
      await runCheck();
    }
  })();
});

btnDeasc.addEventListener("click", () => {
  void (async () => {
    if (!editorEl.value.trim()) return;
    const reply = await chrome.runtime.sendMessage({
      type: "PROXY_OFFSCREEN",
      payload: { type: "DEASCIIFY", text: editorEl.value },
    });
    if (reply?.ok && typeof reply.result === "string") {
      editorEl.value = reply.result;
      await chrome.storage.local.set({ zmbk_scratch_draft: editorEl.value });
      await runCheck();
    }
  })();
});

btnMorph.addEventListener("click", () => {
  void (async () => {
    if (!editorEl.value.trim()) return;
    const reply = await chrome.runtime.sendMessage({
      type: "PROXY_OFFSCREEN",
      payload: { type: "ANALYZE", text: editorEl.value },
    });
    if (reply?.ok && Array.isArray(reply.tokens)) {
      const tokens = reply.tokens as TokenDisambiguation[];
      const rows = tokens.map((tok) => {
        const row = document.createElement("div");
        row.className = "morph-row";

        const head = document.createElement("div");
        head.className = "morph-head";

        const title = document.createElement("strong");
        title.textContent = `${tok.surface} → ${tok.lemma}`;

        const posBadge = document.createElement("span");
        posBadge.className = "pos-pill";
        posBadge.textContent = tok.secondaryPos
          ? `${tok.pos} · ${tok.secondaryPos}`
          : tok.pos;

        head.replaceChildren(title, posBadge);

        const detail = document.createElement("div");
        detail.className = "mono";
        // Insert zero-width break opportunities after '+' and '|' so long agglutinative chains wrap cleanly at morpheme boundaries
        detail.textContent = tok.analysis.replace(/([+|])/g, "$1\u200B");

        row.replaceChildren(head, detail);
        return row;
      });
      morphContainer.replaceChildren(...rows);
    }
  })();
});

btnCopy.addEventListener("click", () => {
  void (async () => {
    if (!editorEl.value) return;
    try {
      await navigator.clipboard.writeText(editorEl.value);
      reportCopyResult(btnCopy, true);
    } catch {
      reportCopyResult(btnCopy, false);
    }
  })();
});

btnClear.addEventListener("click", () => {
  editorEl.value = "";
  void chrome.storage.local.remove("zmbk_scratch_draft");
  resetPanelsToIdle();
  editorEl.focus();
});

btnSettings.addEventListener("click", () => {
  void chrome.runtime.openOptionsPage();
});

void initSidePanel();
