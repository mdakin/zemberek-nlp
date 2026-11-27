import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const extRoot = path.resolve(__dirname, "..");
const distDir = path.join(extRoot, "dist");

// ============================================================================
// Zero-Dependency Pipe CDP Client (`--remote-debugging-pipe`, fds 3 & 4)
// ============================================================================

class PipeCdpClient {
  constructor(writeStream, readStream) {
    this.writeStream = writeStream;
    this.readStream = readStream;
    this.buffer = "";
    this.nextId = 1;
    this.pending = new Map();
    this.eventListeners = [];

    readStream.on("data", (chunk) => {
      this.buffer += chunk.toString("utf8");
      const parts = this.buffer.split("\0");
      this.buffer = parts.pop() ?? "";
      for (const raw of parts) {
        if (!raw) continue;
        const msg = JSON.parse(raw);
        if (msg.id && this.pending.has(msg.id)) {
          const p = this.pending.get(msg.id);
          this.pending.delete(msg.id);
          if (msg.error) p.reject(new Error(JSON.stringify(msg.error)));
          else p.resolve(msg.result);
        } else {
          for (const fn of this.eventListeners) fn(msg);
        }
      }
    });
  }

  send(method, params = {}, sessionId = undefined) {
    const id = this.nextId++;
    const payload = { id, method, params };
    if (sessionId) payload.sessionId = sessionId;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.writeStream.write(JSON.stringify(payload) + "\0");
    });
  }

  onEvent(fn) {
    this.eventListeners.push(fn);
  }

  close() {
    try {
      this.writeStream.end();
    } catch {
      // Ignore pipe close
    }
  }
}

// ============================================================================
// Part 1: Static Manifest V3, Icon Dimensions, CSP, & Offscreen Boundary Audit
// ============================================================================

function verifyStaticMv3Compliance() {
  const manifest = JSON.parse(fs.readFileSync(path.join(distDir, "manifest.json"), "utf8"));
  assert.equal(manifest.manifest_version, 3, "Must be Manifest V3");
  assert.ok(
    Number(manifest.minimum_chrome_version) >= 116,
    "minimum_chrome_version must be >= 116"
  );
  assert.ok(manifest.description.length <= 132, "Manifest description must be <= 132 chars");

  // Verify real separate PNG files with exact IHDR pixel dimensions
  for (const size of [16, 32, 48, 128]) {
    const iconRel = manifest.icons[String(size)];
    const iconBytes = fs.readFileSync(path.join(distDir, iconRel));
    assert.equal(iconBytes.subarray(1, 4).toString("ascii"), "PNG");
    const width = iconBytes.readUInt32BE(16);
    const height = iconBytes.readUInt32BE(20);
    assert.equal(width, size, `icon-${size}.png width mismatch`);
    assert.equal(height, size, `icon-${size}.png height mismatch`);
  }

  // Verify zero inline scripts & zero inline event handlers in HTML pages
  for (const htmlName of ["offscreen.html", "popup.html", "sidepanel.html", "options.html"]) {
    const html = fs.readFileSync(path.join(distDir, htmlName), "utf8");
    assert.ok(
      !/<script(?![^>]*\bsrc=)[^>]*>/i.test(html),
      `${htmlName} contains forbidden inline <script>`
    );
    assert.ok(!/\son[a-z]+\s*=/i.test(html), `${htmlName} contains forbidden inline event handler`);
    assert.ok(
      html.includes('name="color-scheme"'),
      `${htmlName} must declare <meta name="color-scheme">`
    );
  }

  // Verify zero eval / new Function & strict Offscreen API boundary
  for (const jsName of [
    "service-worker.js",
    "offscreen.js",
    "wasm-worker.js",
    "content-script.js",
    "popup.js",
    "sidepanel.js",
    "options.js",
  ]) {
    const code = fs.readFileSync(path.join(distDir, jsName), "utf8");
    assert.ok(!/\beval\s*\(/.test(code), `${jsName} must not call eval()`);
    assert.ok(!/\bnew\s+Function\s*\(/.test(code), `${jsName} must not call new Function()`);
  }

  const offscreenJs = fs.readFileSync(path.join(distDir, "offscreen.js"), "utf8");
  assert.ok(
    !/chrome\.(storage|tabs|action|downloads)\b/.test(offscreenJs),
    "offscreen.js must not touch restricted chrome.storage/tabs/action APIs"
  );
}

// ============================================================================
// Part 2: Chunked `chrome.storage.sync` + `local` Overflow Unit Test (>80 KB)
// ============================================================================

async function verifyChunkedStorageLogic() {
  const syncStore = {};
  const localStore = {};
  globalThis.chrome = {
    storage: {
      sync: {
        async get(keys) {
          if (typeof keys === "string") return { [keys]: syncStore[keys] };
          if (Array.isArray(keys)) {
            const out = {};
            for (const k of keys) if (k in syncStore) out[k] = syncStore[k];
            return out;
          }
          return { ...syncStore };
        },
        async set(items) {
          for (const [k, v] of Object.entries(items)) {
            const byteLen = Buffer.byteLength(JSON.stringify(v), "utf8") + k.length;
            assert.ok(
              byteLen <= 8192,
              `chrome.storage.sync key ${k} (${byteLen} B) exceeded QUOTA_BYTES_PER_ITEM (8192 B)`
            );
            syncStore[k] = structuredClone(v);
          }
        },
        async remove(keys) {
          const list = Array.isArray(keys) ? keys : [keys];
          for (const k of list) delete syncStore[k];
        },
      },
      local: {
        async get(keys) {
          if (typeof keys === "string") return { [keys]: localStore[keys] };
          return { ...localStore };
        },
        async set(items) {
          Object.assign(localStore, structuredClone(items));
        },
        async remove(key) {
          delete localStore[key];
        },
      },
    },
  };

  // Bundle storage.ts on the fly via esbuild to test exact production storage logic
  const esbuild = (await import("esbuild")).default;
  const built = await esbuild.build({
    entryPoints: [path.join(extRoot, "src/shared/storage.ts")],
    bundle: true,
    write: false,
    format: "esm",
  });
  const modUrl = `data:text/javascript;base64,${Buffer.from(built.outputFiles[0].text).toString(
    "base64"
  )}`;
  const storageMod = await import(modUrl);

  // Generate 5,500 Turkish custom words (~95 KB > 80 KB sync quota!)
  const bigWordList = Array.from({ length: 5500 }, (_, i) => `özelkelime_${i}_ğüşiöç`);
  await storageMod.saveUserDictionary(bigWordList);
  assert.ok(
    Array.isArray(localStore.zmbk_dict_overflow) && localStore.zmbk_dict_overflow.length > 0,
    "Expected automatic overflow into chrome.storage.local above 80 KB"
  );
  const loadedBack = await storageMod.loadUserDictionary();
  assert.equal(loadedBack.length, 5500);
  assert.equal(loadedBack[0], bigWordList[0]);
  assert.equal(loadedBack[5499], bigWordList[5499]);
  delete globalThis.chrome;
}

// ============================================================================
// Part 3: Real Headless Chrome E2E Harness (5 Editor Surfaces + 10 Tabs + Undo)
// ============================================================================

const TEST_FIXTURE_HTML = `<!DOCTYPE html>
<html lang="tr">
<head>
  <meta charset="utf-8" />
  <title>Zemberek MV3 Editor Surfaces Fixture</title>
  <style>
    body { font-family: system-ui, sans-serif; padding: 24px; display: grid; gap: 18px; }
    textarea, input, .editor {
      width: 640px; padding: 10px; font-size: 16px; line-height: 1.5;
      border: 1px solid #ccc; border-radius: 6px;
    }
    .editor { min-height: 54px; }
  </style>
</head>
<body>
  <textarea id="ta-editor">Bugun kitablar cok guzel.</textarea>
  <input id="in-editor" type="text" value="Yarin kutuphaneye gidicem." />
  <div id="ce-plain" class="editor" contenteditable="true">Merhaba 😀 bugun gelecekmisiniz?</div>
  <div id="ce-prosemirror" class="editor ProseMirror" contenteditable="true"><p>Sabah <strong>kutuphaneye</strong> ve okula gidicem.</p></div>
  <div id="ce-lexical" class="editor lexical-editor" contenteditable="true" data-lexical-editor="true"><span>Yarin bende toplantiya katilicam.</span></div>
  <textarea id="ta-english">Please check the project settings and review the pull request before tomorrow.</textarea>
  <div id="ce-mixed" class="editor" contenteditable="true">Bugun kitablar cok guzel. Please review the pull request tomorrow morning.</div>

  <script>
    // Simulate Lexical's controlled beforeinput handler + Ctrl+Z undo history stack
    const lex = document.getElementById("ce-lexical");
    const lexHistory = [];
    window.__lexUndo = () => {
      if (lexHistory.length > 0) {
        lex.firstElementChild.textContent = lexHistory.pop();
        lex.dispatchEvent(new InputEvent("input", { bubbles: true }));
      }
    };
    lex.addEventListener("beforeinput", (ev) => {
      if (ev.inputType === "insertReplacementText" && typeof ev.data === "string") {
        const sel = window.getSelection();
        if (sel && sel.rangeCount > 0) {
          ev.preventDefault();
          const r = sel.getRangeAt(0);
          const spanNode = lex.firstElementChild;
          const prev = spanNode.textContent;
          lexHistory.push(prev);
          spanNode.textContent =
            prev.slice(0, r.startOffset) + ev.data + prev.slice(r.endOffset);
          lex.dispatchEvent(new InputEvent("input", { bubbles: true }));
        }
      }
    });
  </script>
</body>
</html>`;

async function startFixtureServer() {
  const server = http.createServer((req, res) => {
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    res.end(TEST_FIXTURE_HTML);
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const addr = server.address();
  return { server, url: `http://127.0.0.1:${addr.port}/fixture` };
}

async function launchChromeWithExtension(userDataDir) {
  const chromeBin = "/usr/bin/google-chrome";
  const args = [
    "--headless=new",
    "--no-sandbox",
    "--disable-gpu",
    "--disable-dev-shm-usage",
    "--no-first-run",
    "--no-default-browser-check",
    "--enable-unsafe-extension-debugging",
    "--remote-debugging-pipe",
    `--user-data-dir=${userDataDir}`,
    "about:blank",
  ];

  const proc = spawn(chromeBin, args, {
    stdio: ["ignore", "pipe", "pipe", "pipe", "pipe"],
  });
  const cdp = new PipeCdpClient(proc.stdio[3], proc.stdio[4]);
  const { id: extId } = await cdp.send("Extensions.loadUnpacked", {
    path: distDir,
  });
  return { proc, cdp, extId };
}

async function evalInSession(cdp, sessionId, expression) {
  const res = await cdp.send(
    "Runtime.evaluate",
    {
      expression,
      awaitPromise: true,
      returnByValue: true,
    },
    sessionId
  );
  if (res.exceptionDetails) {
    throw new Error(
      `Runtime.evaluate exception: ${JSON.stringify(res.exceptionDetails.exception || res.exceptionDetails)}`
    );
  }
  return res.result.value;
}

async function waitForCondition(fn, timeoutMs = 15000, pollMs = 80) {
  const deadline = Date.now() + timeoutMs;
  let lastErr = null;
  while (Date.now() < deadline) {
    try {
      const val = await fn();
      if (val) return val;
    } catch (err) {
      lastErr = err;
    }
    await new Promise((r) => setTimeout(r, pollMs));
  }
  throw lastErr || new Error("Timed out waiting for condition");
}

async function runRealChromiumE2E() {
  const { server, url: fixtureUrl } = await startFixtureServer();
  const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), "zmbk-mv3-e2e-"));
  let chromeProc = null;
  let cdp = null;

  try {
    const launched = await launchChromeWithExtension(userDataDir);
    chromeProc = launched.proc;
    cdp = launched.cdp;

    const consoleErrors = [];
    cdp.onEvent((ev) => {
      if (ev.method === "Runtime.exceptionThrown") {
        consoleErrors.push(JSON.stringify(ev.params.exceptionDetails));
      }
      if (
        ev.method === "Log.entryAdded" &&
        String(ev.params?.entry?.text || "").includes("Unchecked runtime.lastError")
      ) {
        consoleErrors.push(`lastError: ${ev.params.entry.text}`);
      }
    });

    // 1. Discover our MV3 Service Worker target & verify Extension ID
    const swTarget = await waitForCondition(async () => {
      const { targetInfos } = await cdp.send("Target.getTargets");
      return targetInfos.find(
        (t) =>
          t.type === "service_worker" &&
          t.url === `chrome-extension://${launched.extId}/service-worker.js`
      );
    }, 15000);

    const extId = launched.extId;
    const { sessionId: swSession } = await cdp.send("Target.attachToTarget", {
      targetId: swTarget.targetId,
      flatten: true,
    });
    await cdp.send("Runtime.enable", {}, swSession);

    // 2. Open Primary Fixture Tab and verify all 5 Editor Surfaces!
    const { targetId: tab1Target } = await cdp.send("Target.createTarget", {
      url: fixtureUrl,
    });
    const { sessionId: tab1Session } = await cdp.send("Target.attachToTarget", {
      targetId: tab1Target,
      flatten: true,
    });
    await cdp.send("Runtime.enable", {}, tab1Session);
    await cdp.send("Page.enable", {}, tab1Session);
    await cdp.send("Page.bringToFront", {}, tab1Session);

    // Wait for content script to annotate all 5 editors AND flush rAF highlights!
    const initialCounts = await waitForCondition(async () => {
      const counts = await evalInSession(
        cdp,
        tab1Session,
        `(async () => {
          const ids = ["ta-editor", "in-editor", "ce-plain", "ce-prosemirror", "ce-lexical"];
          const map = {};
          for (const id of ids) {
            const el = document.getElementById(id);
            map[id] = Number(el?.getAttribute("data-zmbk-issue-count") || -1);
          }
          if (!ids.every((id) => map[id] > 0)) return null;
          await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
          return map;
        })()`
      );
      return counts;
    }, 20000);

    // Verify CSS.highlights has registered wavy underline ranges AND zero injected DOM spans in contenteditable!
    const highlightState = await evalInSession(
      cdp,
      tab1Session,
      `(() => ({
        hasSpelling: CSS.highlights.has("zmbk-spelling"),
        hasClitic: CSS.highlights.has("zmbk-clitic"),
        hasDeasc: CSS.highlights.has("zmbk-deasc"),
        cePlainChildElements: document.getElementById("ce-plain").children.length,
        pmStrongIntact: !!document.querySelector("#ce-prosemirror strong"),
        hasNativeOpaqueRange: typeof HTMLTextAreaElement.prototype.createValueRange === "function",
        mirrorCount: document.querySelectorAll("[data-zmbk-mirror]").length,
      }))()`
    );
    assert.equal(highlightState.hasSpelling, true, "CSS.highlights must contain zmbk-spelling");
    assert.equal(highlightState.hasClitic, true, "CSS.highlights must contain zmbk-clitic");
    assert.equal(highlightState.hasDeasc, true, "CSS.highlights must contain zmbk-deasc");
    assert.equal(
      highlightState.cePlainChildElements,
      0,
      "CSS.highlights must not inject any child elements into contenteditable"
    );
    assert.equal(highlightState.pmStrongIntact, true, "ProseMirror <strong> node must stay intact");
    assert.ok(
      highlightState.hasNativeOpaqueRange || highlightState.mirrorCount >= 2,
      "Expected either native OpaqueRange (Chrome 152+) or Shadow-DOM mirrors for textarea & input"
    );

    // 3. Test Hover/Click Suggestion Card + 1-Click Replacement + Ctrl+Z Undo on <textarea>
    await evalInSession(
      cdp,
      tab1Session,
      `(() => {
        const ta = document.getElementById("ta-editor");
        ta.focus();
        ta.setSelectionRange(2, 2); // Inside 'Bugun'
        const mirrors = [...document.querySelectorAll("[data-zmbk-mirror]")];
        const mark = mirrors
          .map((h) => h.shadowRoot.querySelector(".zmbk-mark"))
          .find(Boolean);
        let r = mark ? mark.getBoundingClientRect() : null;
        if (!r && typeof ta.createValueRange === "function") {
          const or = ta.createValueRange(0, 5);
          const rects = or.getClientRects?.();
          if (rects && rects.length > 0) r = rects[0];
        }
        if (!r) r = ta.getBoundingClientRect();
        ta.dispatchEvent(
          new MouseEvent("click", {
            bubbles: true,
            clientX: r.left + 6,
            clientY: r.top + 6,
          })
        );
      })()`
    );

    // Wait for suggestion card & morphological lemma/POS badge to populate, then click first suggestion
    const taReplaced = await waitForCondition(async () => {
      return evalInSession(
        cdp,
        tab1Session,
        `(() => {
          const root = document.getElementById("zmbk-assistant-root")?.shadowRoot;
          const card = root?.querySelector(".zmbk-card");
          if (!card || card.hidden) return null;
          const badge = card.querySelector(".zmbk-morph-badge")?.textContent || "";
          if (!badge.includes("(")) return null;
          const btn = card.querySelector(".zmbk-sug-btn");
          if (!btn) return null;
          btn.click();
          return document.getElementById("ta-editor").value;
        })()`
      );
    }, 8000);
    assert.ok(
      taReplaced.startsWith("Bugün"),
      `Expected <textarea> replacement to start with 'Bugün', got: ${taReplaced}`
    );

    // Verify Ctrl+Z (`execCommand('undo')`) restores exact original <textarea> text!
    const taAfterUndo = await evalInSession(
      cdp,
      tab1Session,
      `(() => {
        const ta = document.getElementById("ta-editor");
        ta.focus();
        document.execCommand("undo");
        return ta.value;
      })()`
    );
    assert.equal(
      taAfterUndo,
      "Bugun kitablar cok guzel.",
      "<textarea> undo must restore exact original value"
    );

    // 4. Test <input>, plain contenteditable, ProseMirror, & Lexical 1-click replacements + undo integrity!
    const pmAndLex = await evalInSession(
      cdp,
      tab1Session,
      `(() => {
        const root = document.getElementById("zmbk-assistant-root").shadowRoot;

        // 4a. <input type="text"> ('Yarin' -> 'Yarın') + undo
        const inp = document.getElementById("in-editor");
        inp.focus();
        inp.setSelectionRange(2, 2);
        const inpRect = inp.getBoundingClientRect();
        inp.dispatchEvent(
          new MouseEvent("click", {
            bubbles: true,
            clientX: inpRect.left + 14,
            clientY: inpRect.top + 14,
          })
        );
        root.querySelector(".zmbk-sug-btn").click();
        const inpAfterReplace = inp.value;
        inp.focus();
        document.execCommand("undo");
        const inpAfterUndo = inp.value;

        // 4b. Plain contenteditable ('gelecekmisiniz' -> 'gelecek misiniz') + undo
        const cePlain = document.getElementById("ce-plain");
        const ceNode = cePlain.firstChild;
        const ceRange = new Range();
        ceRange.setStart(ceNode, 18);
        ceRange.setEnd(ceNode, 32);
        const ceRect = ceRange.getBoundingClientRect();
        cePlain.dispatchEvent(
          new MouseEvent("click", {
            bubbles: true,
            clientX: ceRect.left + 6,
            clientY: ceRect.top + 6,
          })
        );
        root.querySelector(".zmbk-sug-btn").click();
        const cePlainAfterReplace = cePlain.textContent;
        cePlain.focus();
        document.execCommand("undo");
        const cePlainAfterUndo = cePlain.textContent;

        // 4c. Click underlined 'kutuphaneye' inside #ce-prosemirror strong
        const pm = document.getElementById("ce-prosemirror");
        const strongRect = pm.querySelector("strong").getBoundingClientRect();
        pm.dispatchEvent(
          new MouseEvent("click", {
            bubbles: true,
            clientX: strongRect.left + 6,
            clientY: strongRect.top + 6,
          })
        );
        root.querySelector(".zmbk-sug-btn").click();
        const pmAfterReplace = pm.textContent;
        const pmStrongStillThere = !!pm.querySelector("strong");
        pm.focus();
        document.execCommand("undo");
        const pmAfterUndo = pm.textContent;

        // 4d. Click underlined 'bende' inside #ce-lexical (controlled beforeinput editor)
        const lex = document.getElementById("ce-lexical");
        const textNode = lex.firstElementChild.firstChild;
        const r = new Range();
        r.setStart(textNode, 6);
        r.setEnd(textNode, 11);
        const br = r.getBoundingClientRect();
        lex.dispatchEvent(
          new MouseEvent("click", {
            bubbles: true,
            clientX: br.left + 4,
            clientY: br.top + 4,
          })
        );
        root.querySelector(".zmbk-sug-btn").click();
        const lexAfterReplace = lex.textContent;
        window.__lexUndo();
        const lexAfterUndo = lex.textContent;

        return {
          inpAfterReplace,
          inpAfterUndo,
          cePlainAfterReplace,
          cePlainAfterUndo,
          pmAfterReplace,
          pmStrongStillThere,
          pmAfterUndo,
          lexAfterReplace,
          lexAfterUndo,
        };
      })()`
    );
    assert.ok(
      pmAndLex.inpAfterReplace.startsWith("Yarın"),
      `<input> replacement failed: ${pmAndLex.inpAfterReplace}`
    );
    assert.equal(
      pmAndLex.inpAfterUndo,
      "Yarin kutuphaneye gidicem.",
      "<input> undo must restore exact original text"
    );
    assert.ok(
      pmAndLex.cePlainAfterReplace.includes("gelecek misiniz"),
      `Plain contenteditable replacement failed: ${pmAndLex.cePlainAfterReplace}`
    );
    assert.equal(
      pmAndLex.cePlainAfterUndo,
      "Merhaba 😀 bugun gelecekmisiniz?",
      "Plain contenteditable undo must restore exact original text"
    );
    assert.ok(
      pmAndLex.pmAfterReplace.includes("kütüphaneye"),
      `ProseMirror replacement failed: ${pmAndLex.pmAfterReplace}`
    );
    assert.equal(pmAndLex.pmStrongStillThere, true, "ProseMirror <strong> tag preserved");
    assert.equal(
      pmAndLex.pmAfterUndo,
      "Sabah kutuphaneye ve okula gidicem.",
      "ProseMirror undo must restore exact original text"
    );
    assert.ok(
      pmAndLex.lexAfterReplace.includes("ben de"),
      `Lexical controlled beforeinput replacement failed: ${pmAndLex.lexAfterReplace}`
    );
    assert.equal(
      pmAndLex.lexAfterUndo,
      "Yarin bende toplantiya katilicam.",
      "Lexical undo must restore exact original text"
    );

    // Verify native Chrome spellcheck suppression on Turkish editors vs automatic restoration on English editor!
    const squiggleAndLangState = await waitForCondition(async () => {
      const st = await evalInSession(
        cdp,
        tab1Session,
        `(() => {
          const taTr = document.getElementById("ta-editor");
          const taEn = document.getElementById("ta-english");
          const ceMix = document.getElementById("ce-mixed");
          const enDone = taEn?.getAttribute("data-zmbk-non-tr") === "1";
          const mixCount = Number(ceMix?.getAttribute("data-zmbk-issue-count") || -1);
          if (!enDone || mixCount < 0) return null;
          return {
            trSuppressAttr: taTr.getAttribute("data-zmbk-suppress-native"),
            trSpellcheckProp: taTr.spellcheck,
            enIssueCount: Number(taEn.getAttribute("data-zmbk-issue-count") || 0),
            enSuppressAttr: taEn.getAttribute("data-zmbk-suppress-native"),
            enSpellcheckProp: taEn.spellcheck,
            mixIssueCount: mixCount,
          };
        })()`
      );
      return st;
    }, 10000);

    assert.equal(
      squiggleAndLangState.trSuppressAttr,
      "1",
      "Turkish editor must suppress overlapping native Chrome spellcheck squiggles"
    );
    assert.equal(
      squiggleAndLangState.trSpellcheckProp,
      false,
      "Turkish editor must set el.spellcheck = false while Zemberek is active"
    );
    assert.equal(
      squiggleAndLangState.enIssueCount,
      0,
      "English editor must produce 0 false Turkish suggestions"
    );
    assert.equal(
      squiggleAndLangState.enSuppressAttr,
      null,
      "English editor must NOT suppress native Chrome English spellchecker"
    );
    assert.equal(
      squiggleAndLangState.enSpellcheckProp,
      true,
      "English editor must preserve native el.spellcheck = true"
    );
    assert.equal(
      squiggleAndLangState.mixIssueCount,
      initialCounts["ta-editor"],
      "Mixed Turkish + English paragraph must flag the exact Turkish sentence issues and 0 English words"
    );

    // 5. Verify Add to Dictionary ("Gökbörü") + Popup, Options & Side Panel UI improvements with 0 CSP errors
    const { targetId: popupTarget } = await cdp.send("Target.createTarget", {
      url: `chrome-extension://${extId}/popup.html`,
    });
    const { sessionId: popupSession } = await cdp.send("Target.attachToTarget", {
      targetId: popupTarget,
      flatten: true,
    });
    await cdp.send("Runtime.enable", {}, popupSession);

    const popupUiCheck = await waitForCondition(async () => {
      return evalInSession(
        cdp,
        popupSession,
        `(() => {
          const normBtn = document.getElementById("btn-normalize");
          const deascBtn = document.getElementById("btn-deasciify");
          const copyBtn = document.getElementById("btn-copy");
          const spBtn = document.getElementById("btn-open-sidepanel");
          const setBtn = document.getElementById("btn-open-settings");
          const cb = document.getElementById("toggle-enabled");
          const lbl = cb?.closest("label");
          if (!normBtn || !deascBtn || !copyBtn || !spBtn || !setBtn || !cb || !lbl) return null;
          const cbRect = cb.getBoundingClientRect();
          const lblRect = lbl.getBoundingClientRect();
          const centerDeltaPx = Math.abs((cbRect.top + cbRect.height / 2) - (lblRect.top + lblRect.height / 2));
          return {
            hasRemovedControls: !document.getElementById("profile-select") && !document.getElementById("circumflex-select"),
            hasRemovedStats: !document.getElementById("worker-stats") && !document.body.textContent.includes("MiB"),
            normText: normBtn.textContent.trim(),
            deascText: deascBtn.textContent.trim(),
            copyHasSvg: !!copyBtn.querySelector("svg"),
            spHasSvg: !!spBtn.querySelector("svg"),
            centerDeltaPx,
          };
        })()`
      );
    }, 10000);
    assert.equal(popupUiCheck.hasRemovedControls, true, "Engine & circumflex controls moved out of popup");
    assert.equal(popupUiCheck.hasRemovedStats, true, "Studio/MiB/tab count badge removed from popup");
    assert.equal(popupUiCheck.normText, "Düzelt", "Shorter normalize button label");
    assert.equal(popupUiCheck.deascText, "Deasciify", "Familiar Deasciify button label");
    assert.equal(popupUiCheck.copyHasSvg, true, "Copy button uses standard icon");
    assert.equal(popupUiCheck.spHasSvg, true, "Open in Side Panel uses icon button");
    assert.ok(
      popupUiCheck.centerDeltaPx <= 2.0,
      `Checkbox vertical center misaligned by ${popupUiCheck.centerDeltaPx}px`
    );

    const dictResult = await evalInSession(
      cdp,
      popupSession,
      `(async () => {
        const res = await chrome.runtime.sendMessage({ type: "ADD_USER_WORD", word: "Gökbörü" });
        const check = await chrome.runtime.sendMessage({
          type: "PROXY_OFFSCREEN",
          payload: { type: "CHECK_PARAGRAPH", text: "Gökbörü'de toplantı hazır." }
        });
        const konrolStandalone = await chrome.runtime.sendMessage({
          type: "PROXY_OFFSCREEN",
          payload: { type: "CHECK_PARAGRAPH", text: "konrol" }
        });
        const konrolSentNorm = await chrome.runtime.sendMessage({
          type: "PROXY_OFFSCREEN",
          payload: { type: "NORMALIZE", text: "Hemen konrol yapalım." }
        });
        return {
          words: res.userWords,
          issueCount: check.issues.length,
          konrolRep: konrolStandalone.issues[0]?.replacement,
          konrolSentNorm: konrolSentNorm.result,
        };
      })()`
    );
    assert.ok(dictResult.words.includes("Gökbörü"), "User word persisted in chrome.storage");
    assert.equal(dictResult.issueCount, 0, "Added custom proper noun must produce 0 issues");
    assert.equal(dictResult.konrolRep, "kontrol", "Standalone 'konrol' must suggest 'kontrol'");
    assert.equal(dictResult.konrolSentNorm, "Hemen kontrol yapalım.", "'konrol' in unattested bigram context must normalize to 'kontrol'");

    // Verify Options page (`options.html`) loads & updates engineProfile / circumflexMode
    const { targetId: optTarget } = await cdp.send("Target.createTarget", {
      url: `chrome-extension://${extId}/options.html`,
    });
    const { sessionId: optSession } = await cdp.send("Target.attachToTarget", {
      targetId: optTarget,
      flatten: true,
    });
    await cdp.send("Runtime.enable", {}, optSession);

    const optionsLoaded = await waitForCondition(async () => {
      return evalInSession(
        cdp,
        optSession,
        `(() => {
          const p = document.getElementById("opt-profile");
          const c = document.getElementById("opt-circumflex");
          const ig = document.getElementById("opt-ignore-non-tr");
          const sup = document.getElementById("opt-suppress-native");
          if (!p || !c || !ig || !sup) return null;
          return { profile: p.value, circumflex: c.value, ignoreNonTr: ig.checked, suppressNative: sup.checked };
        })()`
      );
    }, 10000);
    assert.equal(optionsLoaded.profile, "studio");
    assert.equal(optionsLoaded.ignoreNonTr, true);
    assert.equal(optionsLoaded.suppressNative, true);

    const { targetId: spTarget } = await cdp.send("Target.createTarget", {
      url: `chrome-extension://${extId}/sidepanel.html`,
    });
    const { sessionId: spSession } = await cdp.send("Target.attachToTarget", {
      targetId: spTarget,
      flatten: true,
    });
    await cdp.send("Runtime.enable", {}, spSession);
    await cdp.send(
      "Emulation.setDeviceMetricsOverride",
      { width: 340, height: 720, deviceScaleFactor: 2, mobile: false },
      spSession
    );

    // Verify Side Panel has ZERO vertical scrollbar when initial content fits the window!
    const spInitialScroll = await waitForCondition(async () => {
      return evalInSession(
        cdp,
        spSession,
        `(() => {
          const ed = document.getElementById("sp-editor");
          if (!ed) return null;
          ed.value = "";
          return {
            scrollHeight: document.documentElement.scrollHeight,
            clientHeight: document.documentElement.clientHeight,
          };
        })()`
      );
    }, 5000);
    assert.ok(
      spInitialScroll.scrollHeight <= spInitialScroll.clientHeight,
      `Side panel created unnecessary vertical scrollbar on open: scrollHeight=${spInitialScroll.scrollHeight} > clientHeight=${spInitialScroll.clientHeight}`
    );

    const spResult = await waitForCondition(async () => {
      return evalInSession(
        cdp,
        spSession,
        `(async () => {
          const ed = document.getElementById("sp-editor");
          if (!ed) return null;
          const noWasmBadge = !document.body.textContent.includes("MiB") && !document.body.textContent.includes("Wasm");
          ed.value = "Slm bugun kutuphaneye gidicem olurmu?";
          document.getElementById("sp-btn-normalize").click();
          await new Promise((r) => setTimeout(r, 180));
          if (!ed.value.includes("Selam")) return null;
          const normVal = ed.value;

          // Test long agglutinative morphological analysis at narrow 340px Side Panel width (0px overflow!)
          ed.value = "Sürdürülebilirliklerimizden ve farkındalıklarımızdan bahsediyoruz.";
          document.getElementById("sp-btn-morph").click();
          await new Promise((r) => setTimeout(r, 180));
          const rows = [...document.querySelectorAll(".morph-row")];
          if (rows.length < 3) return null;
          const card = document.getElementById("sp-morph").parentElement;
          const overflowPx = Math.max(
            document.documentElement.scrollWidth - document.documentElement.clientWidth,
            card.scrollWidth - card.clientWidth,
            ...rows.map((r) => r.scrollWidth - r.clientWidth)
          );
          const hasCopyAndClear = !!document.querySelector("#sp-btn-copy svg") && !!document.querySelector("#sp-btn-clear svg");
          return { noWasmBadge, normVal, overflowPx, hasCopyAndClear };
        })()`
      );
    }, 10000);
    assert.equal(spResult.noWasmBadge, true, "Side panel must not show MiB / Wasm badge");
    assert.equal(spResult.normVal, "Selam bugün kütüphaneye gideceğim olur mu?");
    assert.equal(spResult.hasCopyAndClear, true, "Side panel has Copy and Clear icon buttons");
    assert.ok(
      spResult.overflowPx <= 0,
      `Side panel morphological analysis overflowed horizontally by ${spResult.overflowPx}px`
    );

    // 6. Open 9 Additional Concurrent Fixture Tabs (10 Total!) & Verify Singleton Offscreen Worker + < 1.5 ms Warm IPC RTT!
    const extraTabTargets = [];
    for (let t = 0; t < 9; t++) {
      const { targetId } = await cdp.send("Target.createTarget", {
        url: `${fixtureUrl}?tab=${t + 2}`,
      });
      extraTabTargets.push(targetId);
    }

    const multiTabStats = await waitForCondition(async () => {
      const info = await evalInSession(
        cdp,
        popupSession,
        `(async () => {
          const contexts = await chrome.runtime.getContexts({ contextTypes: ["OFFSCREEN_DOCUMENT"] });
          const state = await chrome.runtime.sendMessage({ type: "GET_STATE" });
          if (!state?.stats || state.stats.activePortCount < 10) return null;
          return {
            offscreenCount: contexts.length,
            activePortCount: state.stats.activePortCount,
            wasmMemoryBytes: state.stats.wasmMemoryBytes,
          };
        })()`
      );
      return info;
    }, 20000);

    assert.equal(
      multiTabStats.offscreenCount,
      1,
      "Must retain strictly 1 Offscreen Document across 10 open tabs"
    );
    assert.ok(
      multiTabStats.activePortCount >= 10,
      `Expected >= 10 active tab ports multiplexed to singleton Worker, got ${multiTabStats.activePortCount}`
    );

    // Attach to Offscreen Document Log stream and navigate a connected tab to trigger BFCache port closure;
    // verify ZERO "Unchecked runtime.lastError" entries are logged!
    const { targetInfos: allTargetsNow } = await cdp.send("Target.getTargets");
    const offscreenTarget = allTargetsNow.find((t) => t.url.endsWith("/offscreen.html"));
    if (offscreenTarget) {
      const { sessionId: offSession } = await cdp.send("Target.attachToTarget", {
        targetId: offscreenTarget.targetId,
        flatten: true,
      });
      await cdp.send("Log.enable", {}, offSession);
      await cdp.send("Runtime.enable", {}, offSession);
    }
    const { sessionId: bfTabSession } = await cdp.send("Target.attachToTarget", {
      targetId: extraTabTargets[0],
      flatten: true,
    });
    await cdp.send("Page.navigate", { url: "about:blank" }, bfTabSession);
    await new Promise((r) => setTimeout(r, 250));

    // Measure warm paragraph Port IPC round-trip latency directly from Tab 1 content-script world (`< 1.5 ms` gate!)
    const warmPortRttMs = await evalInSession(
      cdp,
      popupSession,
      `(async () => {
        const port = chrome.runtime.connect({ name: "zmbk-check" });
        const sample = "Türkiye genelinde hava sıcaklıkları mevsim normallerinin üzerinde seyrederken hafta sonu yağış bekleniyor.";
        const sendOnce = (reqId) =>
          new Promise((resolve) => {
            const onMsg = (msg) => {
              if (msg.reqId === reqId) {
                port.onMessage.removeListener(onMsg);
                resolve(msg);
              }
            };
            port.onMessage.addListener(onMsg);
            port.postMessage({
              type: "CHECK_PARAGRAPH",
              reqId,
              paragraphHash: 12345,
              text: sample,
            });
          });
        // Prime warm paragraph cache
        await sendOnce(1);
        const rounds = 40;
        const t0 = performance.now();
        for (let i = 0; i < rounds; i++) {
          await sendOnce(100 + i);
        }
        const avgMs = (performance.now() - t0) / rounds;
        port.disconnect();
        return avgMs;
      })()`
    );

    assert.ok(
      warmPortRttMs < 1.5,
      `Warm Port IPC RTT (${warmPortRttMs.toFixed(3)} ms) exceeded 1.5 ms gate!`
    );
    assert.equal(
      consoleErrors.length,
      0,
      `Expected 0 runtime/CSP errors across extension contexts, got: ${consoleErrors.join("; ")}`
    );

    return {
      initialCounts,
      offscreenCount: multiTabStats.offscreenCount,
      activePortCount: multiTabStats.activePortCount,
      studioWasmMemoryMiB: Number(
        (multiTabStats.wasmMemoryBytes / (1024 * 1024)).toFixed(2)
      ),
      warmPortIpcRttMs: Number(warmPortRttMs.toFixed(3)),
      runtimeOrCspErrors: consoleErrors.length,
    };
  } finally {
    if (cdp) cdp.close();
    if (chromeProc) {
      chromeProc.kill("SIGKILL");
      await new Promise((r) => chromeProc.once("exit", r));
      await new Promise((r) => setTimeout(r, 150));
    }
    server.close();
    try {
      fs.rmSync(userDataDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
    } catch {
      // Ignore delayed OS lock cleanup in /tmp
    }
  }
}

verifyStaticMv3Compliance();
await verifyChunkedStorageLogic();
const e2eMetrics = await runRealChromiumE2E();

console.log(
  JSON.stringify(
    {
      staticMv3Audit: "PASSED (MV3, PNG 16/48/128, strict CSP, Offscreen API boundary)",
      chunkedStorageOverflowTest: "PASSED (5,500 words > 80 KB sync -> local overflow)",
      chromiumHeadlessE2E: e2eMetrics,
    },
    null,
    2
  )
);
