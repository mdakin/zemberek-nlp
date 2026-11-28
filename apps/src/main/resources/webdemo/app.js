(() => {
  "use strict";

  const PRESETS = [
    {
      label: "Informal & Clitics",
      text: "yarın bende gelicem ama saat 7 de orda olurmusun bilmiyom"
    },
    {
      label: "Full ASCII",
      text: "turkiyede yasayan ogrencilerin pek cogu bu durumu farketmedi bile"
    },
    {
      label: "Social & Slang",
      text: "slm cnm çok tşk ederim saolll valla bişey dicem herkez yanlız kalmış"
    },
    {
      label: "Shorthand & Vowel-Drop",
      text: "mrblar arkdslr nslsnz, blmyrm yrn glmycm glb cok tskler ltfn cvplar mısınız?"
    },
    {
      label: "Splits, Merges & Units",
      text: "hiç bir zaman 16gb ram yetmiyo, diğerleride youtubeda takılıyo"
    },
    {
      label: "TDK Apostrophes",
      text: "ankaralı arkadasım 2024te türkçe'ye ve ingilizce'ye cok calıstı"
    },
    {
      label: "Clean Prose (FPR Test)",
      text: "Bu sabah kahvaltıdan sonra sahilde uzun ve sakin bir yürüyüş yaptık."
    }
  ];

  const DEASC_PRESETS = [
    {
      label: "Minimal Pairs & â/î/û",
      text: "Bu yil sirketin net kar orani rekor kirdi ama disarida yogun kar yagisi hala suruyor ve ruzgar sert esiyor."
    },
    {
      label: "Context Homographs",
      text: "Bahcedeki beyaz guller acinca yuzumuz guldu, kirlarda kosan atlar su birikintisini asti."
    },
    {
      label: "Şişli/sisli & Aydın/aydın",
      text: "Bu sabah vadi cok sisli gorunuyor ama Sisli meydaninda hava acik; aydin insanlar Aydin'da bulustu."
    },
    {
      label: "Tech Brands & URLs",
      text: "Google ve YouTube uzerinden Apple etkinligi canli yayinlandi; detaylar https://zemberek.dev adresinde paylasildi."
    },
    {
      label: "Partial-ASCII Pin Safety",
      text: "Öğrencilerin bir kismi sinav sonuçlarını ogrenmek icin sabah erkenden fakulteye uğradı."
    },
    {
      label: "OOV & Numerals (3'üncü, 6'şar)",
      text: "Saat 14.30'da 3'uncu katta 6'sar kisilik gruplarla toplancaz, kullanici adi cimcime50 olarak kaldi."
    },
    {
      label: "ALL-CAPS Headline",
      text: "ISTANBUL'DA YENI METRO HATTI ACILDI, OGRENCILER ICIN UCRETSIZ ULASIM BASLADI!"
    },
    {
      label: "Classic News",
      text: "Turkiye'de yasayan milyonlarca ogrenci bu hafta ders basi yaparken egitim teknolojileri hizla gelisiyor."
    }
  ];

  const DEP_PRESETS = [
    {
      label: "Simple Clause",
      text: "Küçük sarı kuş yavaşça penceremin kenarına kondu."
    },
    {
      label: "News & Entities",
      text: "Prof. Dr. Fethi Bey saat 14.30'da Türkiye'ye döndü."
    },
    {
      label: "MWT Copula & -ki",
      text: "Dün İstanbul'daydı ama masadaki kitabı evde unutmuştu."
    },
    {
      label: "Relative Clause",
      text: "Okuldan eve dönen öğrenciler yeni açılan kütüphanede ders çalıştı."
    },
    {
      label: "Multi-Sentence",
      text: "Zemberek doğal dil işleme kütüphanesi artık bağımlılık ayrıştırmayı destekliyor. Türkçe cümleler hızla çözümleniyor."
    }
  ];

  const ASCII_FOLD_MAP = {
    "ç": "c", "Ç": "C",
    "ğ": "g", "Ğ": "G",
    "ı": "i", "İ": "I",
    "ö": "o", "Ö": "O",
    "ş": "s", "Ş": "S",
    "ü": "u", "Ü": "U",
    "â": "a", "Â": "A",
    "î": "i", "Î": "I",
    "û": "u", "Û": "U"
  };
  const ASCII_FOLD_RE = /[çÇğĞıİöÖşŞüÜâÂîÎûÛ]/g;

  const CIRCUMFLEX_SET = new Set(["â", "Â", "î", "Î", "û", "Û"]);
  const TR_PIN_SET = new Set(["ç", "Ç", "ğ", "Ğ", "ı", "İ", "ö", "Ö", "ş", "Ş", "ü", "Ü", "â", "Â", "î", "Î", "û", "Û"]);

  const state = {
    deascMetaById: {},
    lastResponse: null,
    lastDeascResponse: null,
    lastDepResponse: null,
    selectedLineIdx: 0,
    selectedTokenIdx: null,
    selectedDeascTokenIdx: null
  };
  const inflight = {};

  // Shared / Topbar DOM
  const serverStatusPill = document.getElementById("serverStatusPill");
  const themeToggleBtn = document.getElementById("themeToggleBtn");

  // Normalization DOM
  const engineSelect = document.getElementById("engineSelect");
  const presetButtons = document.getElementById("presetButtons");
  const normInput = document.getElementById("normInput");
  const normOutput = document.getElementById("normOutput");
  const inputCounter = document.getElementById("inputCounter");
  const latencyBadge = document.getElementById("latencyBadge");
  const editCountBadge = document.getElementById("editCountBadge");
  const highlightToggle = document.getElementById("highlightToggle");
  const compareAllToggle = document.getElementById("compareAllToggle");
  const clearInputBtn = document.getElementById("clearInputBtn");
  const copyOutputBtn = document.getElementById("copyOutputBtn");
  const sendToMorphBtn = document.getElementById("sendToMorphBtn");
  const sendToDepBtn = document.getElementById("sendToDepBtn");
  const comparisonPanel = document.getElementById("comparisonPanel");
  const comparisonBody = document.getElementById("comparisonBody");
  const inspectorPanel = document.getElementById("inspectorPanel");
  const latticeStrip = document.getElementById("latticeStrip");
  const signalDetailBody = document.getElementById("signalDetailBody");
  const arcDetailBody = document.getElementById("arcDetailBody");

  // Deasciifiers DOM
  const deascEngineSelect = document.getElementById("deascEngineSelect");
  const deascModeNote = document.getElementById("deascModeNote");
  const deascPresetButtons = document.getElementById("deascPresetButtons");
  const deascInput = document.getElementById("deascInput");
  const deascOutput = document.getElementById("deascOutput");
  const deascInputCounter = document.getElementById("deascInputCounter");
  const deascLatencyBadge = document.getElementById("deascLatencyBadge");
  const deascChangesBadge = document.getElementById("deascChangesBadge");
  const deascCircBadge = document.getElementById("deascCircBadge");
  const deascHighlightToggle = document.getElementById("deascHighlightToggle");
  const deascCompareToggle = document.getElementById("deascCompareToggle");
  const asciifyInputBtn = document.getElementById("asciifyInputBtn");
  const clearDeascBtn = document.getElementById("clearDeascBtn");
  const copyDeascBtn = document.getElementById("copyDeascBtn");
  const deascToNormBtn = document.getElementById("deascToNormBtn");
  const deascToMorphBtn = document.getElementById("deascToMorphBtn");
  const deascToDepBtn = document.getElementById("deascToDepBtn");
  const deascComparisonPanel = document.getElementById("deascComparisonPanel");
  const deascComparisonBody = document.getElementById("deascComparisonBody");
  const deascDisagreeBadge = document.getElementById("deascDisagreeBadge");
  const deascInspectorPanel = document.getElementById("deascInspectorPanel");
  const deascChangedOnlyToggle = document.getElementById("deascChangedOnlyToggle");
  const deascTokenStrip = document.getElementById("deascTokenStrip");
  const deascCharDetailBody = document.getElementById("deascCharDetailBody");
  const deascVariantDetailBody = document.getElementById("deascVariantDetailBody");

  // Morphology, Dependency & Tokenizer DOM
  const morphInput = document.getElementById("morphInput");
  const morphResults = document.getElementById("morphResults");
  const morphLatencyBadge = document.getElementById("morphLatencyBadge");
  const depPresetButtons = document.getElementById("depPresetButtons");
  const depInput = document.getElementById("depInput");
  const depLatencyBadge = document.getElementById("depLatencyBadge");
  const depStatsBadge = document.getElementById("depStatsBadge");
  const depToMorphBtn = document.getElementById("depToMorphBtn");
  const clearDepBtn = document.getElementById("clearDepBtn");
  const copyDepConlluBtn = document.getElementById("copyDepConlluBtn");
  const depShowConlluToggle = document.getElementById("depShowConlluToggle");
  const depTrees = document.getElementById("depTrees");
  const depConlluSection = document.getElementById("depConlluSection");
  const depConlluTableWrap = document.getElementById("depConlluTableWrap");
  const depConlluRaw = document.getElementById("depConlluRaw");
  const tokInput = document.getElementById("tokInput");
  const tokSentences = document.getElementById("tokSentences");
  const tokResults = document.getElementById("tokResults");
  const tokLatencyBadge = document.getElementById("tokLatencyBadge");

  // Theme initialization
  const prefersDark = window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
  const savedTheme = localStorage.getItem("zemberek_demo_theme") || (prefersDark ? "dark" : "light");
  document.documentElement.setAttribute("data-theme", savedTheme);

  themeToggleBtn.addEventListener("click", () => {
    const cur = document.documentElement.getAttribute("data-theme") === "dark" ? "light" : "dark";
    document.documentElement.setAttribute("data-theme", cur);
    localStorage.setItem("zemberek_demo_theme", cur);
  });

  async function postJsonWithAbort(key, url, payload) {
    if (inflight[key]) {
      inflight[key].abort();
    }
    const ctrl = new AbortController();
    inflight[key] = ctrl;
    try {
      const resp = await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
        signal: ctrl.signal
      });
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      return await resp.json();
    } catch (err) {
      if (err.name === "AbortError") return null;
      serverStatusPill.textContent = "Request error";
      return null;
    }
  }

  function initPresets(presets, container, inputEl, onSelect) {
    presets.forEach((p, idx) => {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "preset-chip";
      btn.textContent = p.label;
      btn.addEventListener("click", () => {
        inputEl.value = p.text;
        onSelect();
        inputEl.focus();
      });
      container.appendChild(btn);
      if (idx === 0) {
        inputEl.value = p.text;
      }
    });
  }

  function bindCopyButton(btn, getText) {
    btn.addEventListener("click", async () => {
      const outText = getText();
      if (!outText) return;
      await navigator.clipboard.writeText(outText);
      const orig = btn.textContent;
      btn.textContent = "Copied!";
      setTimeout(() => {
        btn.textContent = orig;
      }, 1100);
    });
  }

  function openInMorphology(text) {
    if (text) {
      morphInput.value = text;
    }
    switchTab("morphology", true);
  }

  function openInDependency(text) {
    if (text) {
      depInput.value = text;
    }
    switchTab("dependency", true);
  }

  // Module Tab Switching & URL Sync
  document.querySelectorAll(".tab-btn").forEach((btn) => {
    btn.addEventListener("click", () => switchTab(btn.dataset.tab, true));
  });

  function resolveInitialTab() {
    const path = (window.location.pathname || "/").replace(/\/+$/, "").toLowerCase();
    const hash = (window.location.hash || "").replace(/^#/, "").toLowerCase();
    const candidate = hash || path.replace(/^\//, "");
    if (candidate === "deasciifiers" || candidate === "deasciifier") return "deasciifiers";
    if (candidate === "morphology") return "morphology";
    if (candidate === "dependency" || candidate === "dependencies") return "dependency";
    if (candidate === "tokenization" || candidate === "tokenizer") return "tokenization";
    return "normalization";
  }

  function switchTab(tabName, updateUrl = false) {
    document.querySelectorAll(".tab-btn").forEach((b) => {
      const active = b.dataset.tab === tabName;
      b.classList.toggle("active", active);
      b.setAttribute("aria-selected", String(active));
    });
    document.querySelectorAll(".tab-panel").forEach((p) => {
      p.classList.toggle("active", p.id === `tab-${tabName}`);
    });
    if (updateUrl && window.history && window.history.replaceState) {
      window.history.replaceState(null, "", tabName === "normalization" ? "/" : `/${tabName}`);
    }
    if (tabName === "normalization") runNormalize();
    else if (tabName === "deasciifiers") runDeasciify();
    else if (tabName === "morphology") runMorphology();
    else if (tabName === "dependency") runDependency();
    else if (tabName === "tokenization") runTokenize();
  }

  initPresets(PRESETS, presetButtons, normInput, () => {
    state.selectedTokenIdx = null;
    runNormalize();
  });

  initPresets(DEASC_PRESETS, deascPresetButtons, deascInput, () => {
    state.selectedDeascTokenIdx = null;
    runDeasciify();
  });

  initPresets(DEP_PRESETS, depPresetButtons, depInput, () => {
    runDependency();
  });

  // Load Server Info
  async function loadServerInfo() {
    try {
      const r = await fetch("/api/info");
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      const info = await r.json();
      if (!info.hasZemberekHead) {
        const headOpt = engineSelect.querySelector('option[value="zemberek_head"]');
        if (headOpt) headOpt.remove();
      }
      (info.deasciifiers || []).forEach((d) => {
        state.deascMetaById[d.id] = d;
      });
      updateDeascStatsBar();
      serverStatusPill.textContent = `Ready · ${info.version}`;
    } catch (err) {
      serverStatusPill.textContent = "Backend unreachable";
    }
  }

  function updateDeascStatsBar() {
    const meta = state.deascMetaById[deascEngineSelect.value];
    if (!meta) return;
    deascModeNote.textContent = meta.description;
  }

  // ==================== TAB 1: NORMALIZATION ====================
  async function runNormalize() {
    const text = normInput.value;
    if (!text.trim()) {
      state.lastResponse = null;
      normOutput.textContent = "Normalized Turkish text will appear here as you type…";
      normOutput.classList.add("empty-placeholder");
      inputCounter.textContent = `${text.length} chars · 0 tokens`;
      latencyBadge.textContent = "0.00 ms";
      editCountBadge.textContent = "0 edits";
      comparisonPanel.classList.add("hidden");
      latticeStrip.innerHTML = `<span class="io-meta">Type a Turkish sentence above to inspect its candidate lattice.</span>`;
      signalDetailBody.innerHTML = `<span class="io-meta">No token selected.</span>`;
      arcDetailBody.innerHTML = `<span class="io-meta">No token selected.</span>`;
      return;
    }

    const data = await postJsonWithAbort("norm", "/api/normalize", {
      text,
      engine: engineSelect.value,
      inspect: true,
      compareAll: compareAllToggle.checked
    });
    if (!data) return;
    state.lastResponse = data;
    renderNormalizeResult(data);
  }

  function renderNormalizeResult(data) {
    normOutput.classList.remove("empty-placeholder");
    inputCounter.textContent = `${normInput.value.length} chars · ${data.totalTokens} tokens`;
    latencyBadge.textContent = `${data.latencyMs.toFixed(2)} ms`;
    editCountBadge.textContent = `${data.totalEditedSpans} ${data.totalEditedSpans === 1 ? "edit" : "edits"}`;
    serverStatusPill.textContent = `Live · ${data.latencyMs.toFixed(2)} ms`;

    if (!highlightToggle.checked || engineSelect.value === "zemberek_head") {
      normOutput.textContent = data.normalizedText;
    } else {
      normOutput.innerHTML = "";
      (data.lines || []).forEach((lt, lIdx) => {
        if (lIdx > 0) {
          normOutput.appendChild(document.createTextNode("\n"));
        }
        const spans = lt.decodedSpans || [];
        if (spans.length === 0) {
          normOutput.appendChild(document.createTextNode(lt.normalizedLine || ""));
          return;
        }
        spans.forEach((sp) => {
          const el = document.createElement("span");
          el.className = sp.changed ? `out-span changed kind-${sp.changeKind}` : "out-span";
          el.textContent = sp.normSurface;
          if (sp.changed) {
            el.title = `${sp.rawSurface} → ${sp.normSurface} (${(sp.sources || []).join(", ")})`;
            el.addEventListener("click", () => {
              state.selectedLineIdx = lIdx;
              state.selectedTokenIdx = sp.startIdx;
              renderInspector(data);
              inspectorPanel.scrollIntoView({ behavior: "smooth", block: "nearest" });
            });
          }
          normOutput.appendChild(el);
          if (sp.spaceAfter) {
            normOutput.appendChild(document.createTextNode(" "));
          }
        });
      });
    }

    if (compareAllToggle.checked && data.comparisons && data.comparisons.length > 0) {
      comparisonPanel.classList.remove("hidden");
      comparisonBody.innerHTML = data.comparisons
        .map(
          (c) => `
            <div class="comp-row">
              <div class="comp-engine">${escapeHtml(c.label)}</div>
              <div class="comp-text">${escapeHtml(c.normalizedText)}</div>
              <div class="comp-lat">${c.latencyMs.toFixed(2)} ms</div>
            </div>
          `
        )
        .join("");
    } else {
      comparisonPanel.classList.add("hidden");
    }

    renderInspector(data);
  }

  function renderInspector(data) {
    if (engineSelect.value === "zemberek_head") {
      latticeStrip.innerHTML = `<span class="io-meta">Select Span-Lattice Perceptron (v2.4) above to inspect the Multi-Span Candidate Lattice and Semi-Markov Viterbi scores.</span>`;
      signalDetailBody.innerHTML = `<span class="io-meta">Not applicable to Zemberek HEAD baseline.</span>`;
      arcDetailBody.innerHTML = `<span class="io-meta">Not applicable to Zemberek HEAD baseline.</span>`;
      return;
    }

    const nonEmptyLines = (data.lines || []).filter((l) => (l.decodedSpans || []).length > 0);
    if (nonEmptyLines.length === 0) {
      latticeStrip.innerHTML = `<span class="io-meta">No tokens to inspect.</span>`;
      return;
    }

    const activeLine =
      nonEmptyLines.find((l) => l.lineIndex === state.selectedLineIdx) || nonEmptyLines[0];
    state.selectedLineIdx = activeLine.lineIndex;

    const spans = activeLine.decodedSpans || [];
    const slots = activeLine.slots || [];

    const validStarts = new Set(spans.map((s) => s.startIdx));
    if (state.selectedTokenIdx === null || !validStarts.has(state.selectedTokenIdx)) {
      const firstEdited = spans.find((s) => s.changed);
      state.selectedTokenIdx = firstEdited ? firstEdited.startIdx : spans[0].startIdx;
    }

    latticeStrip.innerHTML = "";
    nonEmptyLines.forEach((lineObj) => {
      (lineObj.decodedSpans || []).forEach((sp) => {
        const chip = document.createElement("button");
        chip.type = "button";
        const isSel =
          lineObj.lineIndex === state.selectedLineIdx && sp.startIdx === state.selectedTokenIdx;
        chip.className = `span-chip kind-${sp.changeKind}${isSel ? " selected" : ""}`;

        const topHtml = sp.changed
          ? `<span class="span-chip-raw">${escapeHtml(sp.rawSurface)}</span><span>${escapeHtml(sp.normSurface)}</span>`
          : `<span>${escapeHtml(sp.normSurface)}</span>`;

        const spanRange =
          sp.endIdx - sp.startIdx > 1 ? `[${sp.startIdx}..${sp.endIdx}) merge` : `[${sp.startIdx}]`;
        const modeTag = sp.immutable ? "Immutable" : `P=${(sp.winningProb * 100).toFixed(0)}%`;

        chip.innerHTML = `
          <div class="span-chip-top">${topHtml}</div>
          <div class="span-chip-sub">${spanRange} · ${modeTag}</div>
        `;
        chip.addEventListener("click", () => {
          state.selectedLineIdx = lineObj.lineIndex;
          state.selectedTokenIdx = sp.startIdx;
          renderInspector(data);
        });
        latticeStrip.appendChild(chip);
      });
    });

    const slot = slots.find((s) => s.tokenIdx === state.selectedTokenIdx) || slots[0];
    if (!slot) return;

    renderSignalCard(slot);
    renderArcCard(slot);
  }

  function renderSignalCard(slot) {
    const pct = Math.min(100, Math.max(0, slot.editProb * 100));
    const sig = slot.signals;

    const signalsHtml = sig
      ? `
        <div class="signal-grid">
          <div class="signal-item"><span>Length</span><strong>${sig.wordLen}</strong></div>
          <div class="signal-item"><span>Strict Morph</span><strong>${sig.strictMorph ? "Yes" : "No"}</strong></div>
          <div class="signal-item"><span>ASCII Ambig</span><strong>${sig.hasAsciiAmbiguous ? "Yes" : "No"}</strong></div>
          <div class="signal-item"><span>TR Diacritics</span><strong>${sig.hasTurkishDiacritics ? "Yes" : "No"}</strong></div>
          <div class="signal-item"><span>Clitic Hint</span><strong>${sig.cliticTailHint ? "Yes" : "No"}</strong></div>
          <div class="signal-item"><span>Merge Hint</span><strong>${sig.nextMergeHint ? "Yes" : "No"}</strong></div>
          <div class="signal-item"><span>Parallel Keep</span><strong>${(sig.parallelKeepProb * 100).toFixed(0)}%</strong></div>
          <div class="signal-item"><span>Char Phono</span><strong>${sig.charPhonotactics.toFixed(2)}</strong></div>
        </div>
      `
      : "";

    signalDetailBody.innerHTML = `
      <div class="gate-meter-row">
        <span>Token <code>${escapeHtml(slot.rawSurface)}</code> P(needs_edit)</span>
        <span class="gate-prob-big">${pct.toFixed(1)}%</span>
      </div>
      <div class="gate-bar-track">
        <div class="gate-bar-fill" style="width: ${Math.max(pct, 1.2)}%"></div>
      </div>
      <div class="gate-note">${escapeHtml(slot.note || "")} (edit log-odds: <code>${slot.editLogOdds.toFixed(2)}</code>)</div>
      ${signalsHtml}
    `;
  }

  function renderArcCard(slot) {
    const cands = slot.candidates || [];
    if (cands.length === 0) {
      arcDetailBody.innerHTML = `<span class="io-meta">No candidate arcs at this position.</span>`;
      return;
    }

    const rowsHtml = cands
      .map((c) => {
        const pct = (c.softmaxProb * 100).toFixed(1);
        const srcHtml = (c.sources || [])
          .map((s) => `<span class="src-badge">${escapeHtml(s)}</span>`)
          .join("");
        const winBadge = c.winner ? "★ " : "";
        return `
          <tr class="${c.winner ? "winner-row" : ""}">
            <td class="cand-surf">${winBadge}${escapeHtml(c.normSurface)}</td>
            <td class="mono">[${c.startIdx},${c.endIdx}) ${escapeHtml(c.spanKind)}</td>
            <td class="prob-cell">
              <div class="prob-bar-wrap">
                <div class="prob-mini-bar"><div class="prob-mini-fill" style="width:${Math.max(2, c.softmaxProb * 100)}%"></div></div>
                <span>${pct}%</span>
              </div>
            </td>
            <td class="mono">${c.arcScore.toFixed(2)}</td>
            <td class="mono">${formatSigned(c.cleanSpanLlr)}</td>
            <td class="mono">${formatSigned(c.cliticSkipLlr)}</td>
            <td class="mono">${c.editCost.toFixed(2)}</td>
            <td>${srcHtml}</td>
          </tr>
        `;
      })
      .join("");

    arcDetailBody.innerHTML = `
      <div class="cand-table-wrap">
        <table class="cand-table">
          <thead>
            <tr>
              <th>Candidate</th>
              <th>Span</th>
              <th>Softmax P</th>
              <th>Arc Score</th>
              <th>3-Gram LLR</th>
              <th>Clitic Skip</th>
              <th>Edit Cost</th>
              <th>Generators</th>
            </tr>
          </thead>
          <tbody>${rowsHtml}</tbody>
        </table>
      </div>
    `;
  }

  // ==================== TAB 2: DEASCIIFIERS ====================
  async function runDeasciify() {
    const text = deascInput.value;
    if (!text.trim()) {
      state.lastDeascResponse = null;
      deascOutput.textContent = "Deasciified Turkish text will appear here as you type…";
      deascOutput.classList.add("empty-placeholder");
      deascInputCounter.textContent = `${text.length} chars · 0 tokens`;
      deascLatencyBadge.textContent = "0.00 ms";
      deascChangesBadge.textContent = "0 words · 0 chars";
      deascCircBadge.classList.add("hidden");
      deascComparisonPanel.classList.add("hidden");
      deascTokenStrip.innerHTML = `<span class="io-meta">Type or paste ASCII Turkish text above to inspect restored diacritics.</span>`;
      deascCharDetailBody.innerHTML = `<span class="io-meta">No token selected.</span>`;
      deascVariantDetailBody.innerHTML = `<span class="io-meta">No token selected.</span>`;
      return;
    }

    const data = await postJsonWithAbort("deasc", "/api/deasciify", {
      text,
      engine: deascEngineSelect.value,
      compareAll: deascCompareToggle.checked
    });
    if (!data) return;
    state.lastDeascResponse = data;
    renderDeasciifyResult(data);
  }

  function classifyCharDiff(r, o, sentenceHasOtherTr) {
    if (r === o) return "same";
    if (TR_PIN_SET.has(r) && !TR_PIN_SET.has(o) && !(r === "ı" && o === "i" && !sentenceHasOtherTr)) {
      return "stripped";
    }
    if (CIRCUMFLEX_SET.has(o) && !CIRCUMFLEX_SET.has(r)) {
      return "circumflex";
    }
    return "diacritic";
  }

  function renderDeasciifyResult(data) {
    deascOutput.classList.remove("empty-placeholder");
    const rawText = data.inputText || "";
    const outText = data.deasciifiedText || "";
    const sentenceHasOtherTr = hasNonDotlessTr(rawText);

    deascInputCounter.textContent = `${rawText.length} chars · ${data.totalTokens} tokens`;
    deascLatencyBadge.textContent = `${data.latencyMs.toFixed(2)} ms`;
    deascChangesBadge.textContent = `${data.changedWords} ${data.changedWords === 1 ? "word" : "words"} · ${data.changedChars} ${data.changedChars === 1 ? "char" : "chars"}`;

    if (data.circumflexCount > 0) {
      deascCircBadge.textContent = `${data.circumflexCount} â/î/û`;
      deascCircBadge.classList.remove("hidden");
    } else {
      deascCircBadge.classList.add("hidden");
    }
    serverStatusPill.textContent = `Live · ${data.latencyMs.toFixed(2)} ms`;

    const tokens = data.tokens || [];
    if (!deascHighlightToggle.checked || !data.sameLength || tokens.length === 0) {
      deascOutput.textContent = outText;
    } else {
      deascOutput.innerHTML = "";
      let cursor = 0;
      tokens.forEach((tok) => {
        if (tok.start > cursor) {
          deascOutput.appendChild(document.createTextNode(outText.slice(cursor, tok.start)));
        }
        const span = document.createElement("span");
        const classes = ["deasc-word"];
        if (tok.changed) classes.push("changed", `kind-${tok.changeKind}`);
        if (tok.engineDisagreement) classes.push("disagree");
        span.className = classes.join(" ");

        if (tok.changed && tok.rawSurface.length === tok.restoredSurface.length) {
          span.innerHTML = buildCharHighlightedHtml(tok.rawSurface, tok.restoredSurface, sentenceHasOtherTr);
        } else {
          span.textContent = tok.restoredSurface;
        }

        if (tok.changed || tok.engineDisagreement) {
          const diffHint = tok.engineDisagreement ? " · Engines differ (click to inspect)" : "";
          span.title = `${tok.rawSurface} → ${tok.restoredSurface}${diffHint}`;
          span.addEventListener("click", () => {
            state.selectedDeascTokenIdx = tok.index;
            renderDeascInspector(data, sentenceHasOtherTr);
            deascInspectorPanel.scrollIntoView({ behavior: "smooth", block: "nearest" });
          });
        }
        deascOutput.appendChild(span);
        cursor = tok.end;
      });
      if (cursor < outText.length) {
        deascOutput.appendChild(document.createTextNode(outText.slice(cursor)));
      }
    }

    if (deascCompareToggle.checked && data.comparisons && data.comparisons.length > 0) {
      deascComparisonPanel.classList.remove("hidden");
      deascDisagreeBadge.textContent = `${data.disagreementCount} ${data.disagreementCount === 1 ? "word disagreement" : "word disagreements"}`;
      deascComparisonBody.innerHTML = "";

      data.comparisons.forEach((comp) => {
        const isCurrent = comp.id === data.engine;
        const row = document.createElement("div");
        row.className = `deasc-comp-row${isCurrent ? " active-engine" : ""}`;
        row.title = isCurrent ? "Currently active deasciifier" : `Click to switch primary output to ${comp.name}`;

        const diffRenderedText = renderComparisonDiffHtml(comp.outputText, outText, tokens);
        const circNote = comp.circumflexCount > 0 ? ` · ${comp.circumflexCount}^` : "";
        const stripWarn = comp.strippedPinCount > 0 ? ` · ${comp.strippedPinCount} stripped!` : "";
        const activeBadge = isCurrent ? `<span class="active-tag">Active</span>` : "";

        row.innerHTML = `
          <div class="comp-engine">
            ${escapeHtml(comp.name)}${activeBadge}
          </div>
          <div class="comp-text">${diffRenderedText}</div>
          <div class="deasc-comp-meta">
            <div>${comp.latencyMs.toFixed(2)} ms</div>
            <div>${comp.changedWords}w / ${comp.changedChars}c${circNote}${stripWarn}</div>
          </div>
        `;
        row.addEventListener("click", () => {
          if (deascEngineSelect.value !== comp.id) {
            deascEngineSelect.value = comp.id;
            updateDeascStatsBar();
            runDeasciify();
          }
        });
        deascComparisonBody.appendChild(row);
      });
    } else {
      deascComparisonPanel.classList.add("hidden");
    }

    renderDeascInspector(data, sentenceHasOtherTr);
  }

  function buildCharHighlightedHtml(rawWord, outWord, sentenceHasOtherTr) {
    let html = "";
    for (let i = 0; i < outWord.length; i++) {
      const o = outWord[i];
      const kind = classifyCharDiff(rawWord[i], o, sentenceHasOtherTr);
      if (kind === "same") {
        html += escapeHtml(o);
      } else if (kind === "diacritic") {
        html += `<mark class="deasc-char">${escapeHtml(o)}</mark>`;
      } else {
        html += `<mark class="deasc-char ${kind}">${escapeHtml(o)}</mark>`;
      }
    }
    return html;
  }

  function renderComparisonDiffHtml(compText, selectedText, tokens) {
    if (compText === selectedText || compText.length !== selectedText.length || tokens.length === 0) {
      return escapeHtml(compText);
    }
    let html = "";
    let cursor = 0;
    tokens.forEach((tok) => {
      if (tok.start > cursor) {
        html += escapeHtml(compText.slice(cursor, tok.start));
      }
      const cWord = compText.slice(tok.start, tok.end);
      const sWord = selectedText.slice(tok.start, tok.end);
      if (cWord !== sWord) {
        html += `<span class="comp-diff-word" title="Selected engine outputs: ${escapeHtml(sWord)}">${escapeHtml(cWord)}</span>`;
      } else {
        html += escapeHtml(cWord);
      }
      cursor = tok.end;
    });
    if (cursor < compText.length) {
      html += escapeHtml(compText.slice(cursor));
    }
    return html;
  }

  function renderDeascInspector(data, sentenceHasOtherTr = hasNonDotlessTr(data.inputText || "")) {
    const allTokens = data.tokens || [];
    if (allTokens.length === 0) {
      deascTokenStrip.innerHTML = `<span class="io-meta">No tokens to inspect.</span>`;
      deascCharDetailBody.innerHTML = `<span class="io-meta">No token selected.</span>`;
      deascVariantDetailBody.innerHTML = `<span class="io-meta">No token selected.</span>`;
      return;
    }

    const visibleTokens = deascChangedOnlyToggle.checked
      ? allTokens.filter((t) => t.changed || t.engineDisagreement)
      : allTokens;

    if (
      state.selectedDeascTokenIdx === null ||
      !allTokens.some((t) => t.index === state.selectedDeascTokenIdx)
    ) {
      const pick =
        allTokens.find((t) => t.engineDisagreement) ||
        allTokens.find((t) => t.changed) ||
        allTokens[0];
      state.selectedDeascTokenIdx = pick.index;
    }

    deascTokenStrip.innerHTML = "";
    if (visibleTokens.length === 0) {
      deascTokenStrip.innerHTML = `<span class="io-meta">No changed or disagreeing tokens on this input. Uncheck filter above to inspect all tokens.</span>`;
    } else {
      visibleTokens.forEach((tok) => {
        const chip = document.createElement("button");
        chip.type = "button";
        const isSel = tok.index === state.selectedDeascTokenIdx;
        const kindClass =
          tok.changeKind === "circumflex"
            ? "kind-apostrophe"
            : tok.changeKind === "diacritic"
            ? "kind-rewrite"
            : tok.changeKind === "stripped_pin"
            ? "kind-split"
            : "kind-keep";
        const disagreeClass = tok.engineDisagreement ? " disagree-ring" : "";
        chip.className = `span-chip ${kindClass}${disagreeClass}${isSel ? " selected" : ""}`;

        const topHtml = tok.changed
          ? `<span class="span-chip-raw">${escapeHtml(tok.rawSurface)}</span><span>${escapeHtml(tok.restoredSurface)}</span>`
          : `<span>${escapeHtml(tok.restoredSurface)}</span>`;

        const subTag = tok.engineDisagreement
          ? `${tok.charDiffCount}c · Differs`
          : tok.changed
          ? `${tok.charDiffCount} ${tok.charDiffCount === 1 ? "char" : "chars"}`
          : tok.immutable
          ? "Immutable"
          : "Kept";

        chip.innerHTML = `
          <div class="span-chip-top">${topHtml}</div>
          <div class="span-chip-sub">[${tok.index}] · ${subTag}</div>
        `;
        chip.addEventListener("click", () => {
          state.selectedDeascTokenIdx = tok.index;
          renderDeascInspector(data, sentenceHasOtherTr);
        });
        deascTokenStrip.appendChild(chip);
      });
    }

    const selectedTok =
      allTokens.find((t) => t.index === state.selectedDeascTokenIdx) || allTokens[0];
    renderDeascCharCard(selectedTok, sentenceHasOtherTr);
    renderDeascVariantCard(selectedTok);
  }

  function renderDeascCharCard(tok, sentenceHasOtherTr) {
    const raw = tok.rawSurface || "";
    const out = tok.restoredSurface || "";

    let cellsHtml = "";
    if (raw.length === out.length) {
      for (let i = 0; i < raw.length; i++) {
        const r = raw[i];
        const o = out[i];
        const kind = classifyCharDiff(r, o, sentenceHasOtherTr);
        const cls = kind === "same" ? "char-cell" : `char-cell diff-${kind}`;
        cellsHtml += `
          <div class="${cls}" title="Offset ${tok.start + i}: '${escapeHtml(r)}' → '${escapeHtml(o)}'">
            <span class="char-raw">${escapeHtml(r)}</span>
            <span class="char-arrow">${r === o ? "·" : "↓"}</span>
            <span class="char-out">${escapeHtml(o)}</span>
          </div>
        `;
      }
    }

    deascCharDetailBody.innerHTML = `
      <div class="char-map-strip">${cellsHtml}</div>
      <div class="signal-grid">
        <div class="signal-item"><span>Span Offsets</span><strong>[${tok.start}..${tok.end})</strong></div>
        <div class="signal-item"><span>Changed Chars</span><strong>${tok.charDiffCount} / ${raw.length}</strong></div>
        <div class="signal-item"><span>Input Casing</span><strong>${escapeHtml(tok.casing)}</strong></div>
        <div class="signal-item"><span>Pinned TR Input</span><strong>${tok.hasPinnedTurkish ? "Yes" : "No"}</strong></div>
        <div class="signal-item"><span>Immutable Shield</span><strong>${tok.immutable ? "Yes (Protected)" : "No"}</strong></div>
        <div class="signal-item"><span>Engine Agreement</span><strong>${tok.engineDisagreement ? "Disagrees" : "Unanimous"}</strong></div>
      </div>
    `;
  }

  function renderDeascVariantCard(tok) {
    const latCands = tok.latticeCandidates || [];
    let latticeHtml = "";
    if (latCands.length > 0) {
      const latRows = latCands
        .map((c) => {
          const win = c.winner ? "★ " : "";
          return `
            <tr class="${c.winner ? "winner-row" : ""}">
              <td class="cand-surf">${win}${escapeHtml(c.surface)}</td>
              <td class="mono">${escapeHtml(c.lemma)}</td>
              <td class="mono">${escapeHtml(c.morphSummary)}</td>
              <td class="mono">${formatSigned(c.empPrior)}</td>
              <td class="mono">${formatSigned(c.charScore)}</td>
              <td class="mono">${formatSigned(c.unaryScore)}</td>
              <td class="mono"><strong>${formatSigned(c.seqScore)}</strong></td>
            </tr>
          `;
        })
        .join("");

      latticeHtml = `
        <div class="io-meta" style="margin-bottom:5px;">
          <strong>LatticeDeasciifier TokenLattice (${latCands.length} ${latCands.length === 1 ? "candidate" : "candidates"}):</strong>
        </div>
        <div class="cand-table-wrap" style="margin-bottom:12px;">
          <table class="cand-table">
            <thead>
              <tr>
                <th>Candidate</th>
                <th>Lemma</th>
                <th>Morph</th>
                <th>Emp LogP</th>
                <th>Char</th>
                <th>Unary</th>
                <th>Seq Total</th>
              </tr>
            </thead>
            <tbody>${latRows}</tbody>
          </table>
        </div>
      `;
    }

    const variants = tok.variants || [];
    if (variants.length === 0) {
      const parsesHtml =
        (tok.selectedMorphParses || []).length > 0
          ? tok.selectedMorphParses.map((p) => `<span class="morph-parse-pill">${escapeHtml(p)}</span>`).join("")
          : `<span class="io-meta">No dictionary parse (OOV / Foreign / Numeral)</span>`;
      deascVariantDetailBody.innerHTML = `
        ${latticeHtml}
        <div style="margin-bottom:8px;">Selected output: <strong>${escapeHtml(tok.restoredSurface)}</strong></div>
        <div>${parsesHtml}</div>
        <div class="io-meta" style="margin-top:8px;">Enable "Compare both" above to compare Lattice and Legacy outputs on this word.</div>
      `;
      return;
    }

    const rowsHtml = variants
      .map((v) => {
        const parses =
          (v.morphParses || []).length > 0
            ? v.morphParses.map((p) => `<span class="morph-parse-pill">${escapeHtml(p)}</span>`).join("")
            : `<span class="io-meta">OOV / Unanalyzed</span>`;
        const matchTag = v.matchesSelected ? "★ " : "";
        return `
          <tr class="${v.matchesSelected ? "winner-row" : ""}">
            <td class="mono">${escapeHtml(v.name)}</td>
            <td class="cand-surf">${matchTag}${escapeHtml(v.surface)}</td>
            <td>${parses}</td>
          </tr>
        `;
      })
      .join("");

    deascVariantDetailBody.innerHTML = `
      ${latticeHtml}
      <div class="io-meta" style="margin-bottom:5px;"><strong>Engine Outputs &amp; Morphological Parses:</strong></div>
      <div class="cand-table-wrap">
        <table class="cand-table">
          <thead>
            <tr>
              <th>Deasciifier</th>
              <th>Restored Token</th>
              <th>Zemberek Morphological Analyses</th>
            </tr>
          </thead>
          <tbody>${rowsHtml}</tbody>
        </table>
      </div>
    `;
  }

  function hasNonDotlessTr(s) {
    for (let i = 0; i < s.length; i++) {
      const c = s[i];
      if (c !== "ı" && TR_PIN_SET.has(c)) return true;
    }
    return false;
  }

  function foldTurkishToAscii(s) {
    return s.replace(ASCII_FOLD_RE, (c) => ASCII_FOLD_MAP[c]);
  }

  // ==================== TAB 3: MORPHOLOGY ====================
  async function runMorphology() {
    const text = morphInput.value.trim();
    if (!text) {
      morphResults.innerHTML = "";
      morphLatencyBadge.textContent = "0.00 ms";
      return;
    }
    const data = await postJsonWithAbort("morph", "/api/morphology", { text });
    if (!data) return;

    morphLatencyBadge.textContent = `${data.latencyMs.toFixed(2)} ms`;
    serverStatusPill.textContent = `Live · ${data.latencyMs.toFixed(2)} ms`;
    morphResults.innerHTML = (data.words || [])
      .map((w) => {
        const alts = (w.altParses || []).map(escapeHtml).join("  ·  ");
        return `
          <div class="morph-card">
            <div class="morph-head">
              <span class="morph-word">${escapeHtml(w.surface)}</span>
              <span class="src-badge">${escapeHtml(w.bestPos)}</span>
              <span class="morph-best">★ ${escapeHtml(w.bestFormatted)}</span>
            </div>
            ${alts ? `<div class="morph-alts">Other parses: ${alts}</div>` : ""}
          </div>
        `;
      })
      .join("");
  }

  // ==================== TAB 4: DEPENDENCY TREE ====================
  const SVG_NS = "http://www.w3.org/2000/svg";
  const DEP_CORE_RELS = new Set(["nsubj", "obj", "iobj", "csubj", "ccomp", "xcomp"]);
  const DEP_FUNC_RELS = new Set(["case", "aux", "cop", "det", "cc", "mark", "clf", "fixed", "expl"]);

  function classifyDeprel(deprel) {
    const base = String(deprel || "").split(":")[0].toLowerCase();
    if (base === "root") return "root";
    if (base === "punct") return "punct";
    if (DEP_CORE_RELS.has(base)) return "core";
    if (DEP_FUNC_RELS.has(base)) return "func";
    return "mod";
  }

  function svgEl(tag, attrs = {}, textContent = null) {
    const el = document.createElementNS(SVG_NS, tag);
    for (const [k, v] of Object.entries(attrs)) {
      el.setAttribute(k, String(v));
    }
    if (textContent !== null) {
      el.textContent = textContent;
    }
    return el;
  }

  async function runDependency() {
    const text = depInput.value.trim();
    if (!text) {
      state.lastDepResponse = null;
      depLatencyBadge.textContent = "0.00 ms";
      depStatsBadge.textContent = "0 tokens";
      depTrees.innerHTML = `<div class="io-meta" style="padding: 12px 4px;">Type a Turkish sentence above to visualize its Universal Dependencies v2 tree.</div>`;
      depConlluSection.classList.add("hidden");
      depConlluTableWrap.innerHTML = "";
      depConlluRaw.textContent = "";
      return;
    }

    const data = await postJsonWithAbort("dep", "/api/dependency", { text });
    if (!data) return;
    state.lastDepResponse = data;
    renderDependencyResponse(data);
  }

  function renderDependencyResponse(data) {
    const sents = data.sentences || [];
    depLatencyBadge.textContent = `${data.latencyMs.toFixed(2)} ms`;
    const sentWord = sents.length === 1 ? "sentence" : "sentences";
    const tokWord = data.totalTokens === 1 ? "token" : "tokens";
    depStatsBadge.textContent = `${sents.length} ${sentWord} · ${data.totalTokens} ${tokWord}`;
    serverStatusPill.textContent = `Live · ${data.latencyMs.toFixed(2)} ms`;

    depTrees.innerHTML = "";
    if (sents.length === 0) {
      depTrees.innerHTML = `<div class="io-meta" style="padding: 12px 4px;">No syntactic tokens produced.</div>`;
      depConlluSection.classList.add("hidden");
      return;
    }

    sents.forEach((sent, idx) => {
      depTrees.appendChild(renderDependencySentenceCard(sent, idx, sents.length));
    });

    if (depShowConlluToggle.checked) {
      depConlluSection.classList.remove("hidden");
      renderDependencyConllu(data);
    } else {
      depConlluSection.classList.add("hidden");
    }
  }

  function renderDependencySentenceCard(sent, sIdx, totalSents) {
    const card = document.createElement("div");
    card.className = "dep-sent-card";

    const nodes = sent.nodes || [];
    const rootNode = nodes.find((n) => n.headId === 0) || nodes[0];
    const mwtCount = (sent.mwtSpans || []).length;
    const mwtMeta = mwtCount > 0 ? ` · ${mwtCount} MWT` : "";

    const header = document.createElement("div");
    header.className = "dep-sent-header";
    header.innerHTML = `
      <span class="dep-sent-title">${totalSents > 1 ? `S${sIdx + 1}: ` : ""}${escapeHtml(sent.text)}</span>
      <span class="dep-sent-meta">${nodes.length} UD tokens${mwtMeta}${rootNode ? ` · root=${escapeHtml(rootNode.form)}` : ""}</span>
    `;
    card.appendChild(header);

    const svgWrap = document.createElement("div");
    svgWrap.className = "dep-svg-wrap";

    const detailStrip = document.createElement("div");
    detailStrip.className = "dep-detail-strip";

    const selectedInitId = rootNode ? rootNode.id : null;
    const svg = buildDependencySvg(sent, selectedInitId, (nodeId) => {
      renderDepTokenDetail(detailStrip, sent, nodeId);
    });
    svgWrap.appendChild(svg);
    card.appendChild(svgWrap);

    if (selectedInitId !== null) {
      renderDepTokenDetail(detailStrip, sent, selectedInitId);
    }
    card.appendChild(detailStrip);

    return card;
  }

  function buildDependencySvg(sent, initialSelectedId, onSelectNode) {
    const nodes = sent.nodes || [];
    const mwtSpans = sent.mwtSpans || [];

    const padX = 24;
    const colGap = 14;
    const boxH = 54;

    const layoutById = {};
    let curX = padX;
    nodes.forEach((n) => {
      const formW = String(n.form || "").length * 8.4 + 22;
      const uposW = String(n.upos || "").length * 7.2 + 18;
      const w = Math.min(220, Math.max(70, Math.round(Math.max(formW, uposW))));
      layoutById[n.id] = { x: curX, w, cx: curX + w / 2, node: n };
      curX += w + colGap;
    });
    const svgW = Math.max(320, curX - colGap + padX);

    // Build and layer non-root arcs so nested arcs stack cleanly without colliding
    const arcs = [];
    nodes.forEach((n) => {
      if (n.headId > 0 && layoutById[n.headId]) {
        const left = Math.min(n.headId, n.id);
        const right = Math.max(n.headId, n.id);
        arcs.push({
          head: n.headId,
          dep: n.id,
          deprel: n.deprel,
          cat: classifyDeprel(n.deprel),
          left,
          right,
          span: right - left,
          level: 1
        });
      }
    });

    arcs.sort((a, b) => a.span - b.span || a.left - b.left);
    const placed = [];
    arcs.forEach((arc) => {
      let maxLvl = 0;
      for (const p of placed) {
        const overlaps = p.left < arc.right && p.right > arc.left;
        if (overlaps && p.level > maxLvl) {
          maxLvl = p.level;
        }
      }
      arc.level = maxLvl + 1;
      placed.push(arc);
    });

    const maxLevel = placed.reduce((m, a) => Math.max(m, a.level), 1);
    const levelStep = 34;
    const topPad = 42;
    const arcBaseY = topPad + maxLevel * levelStep + 14;
    const boxY = arcBaseY + 6;
    const mwtExtra = mwtSpans.length > 0 ? 34 : 12;
    const svgH = boxY + boxH + mwtExtra;

    const svg = svgEl("svg", {
      class: "dep-svg",
      width: svgW,
      height: svgH,
      viewBox: `0 0 ${svgW} ${svgH}`
    });

    const arcsLayer = svgEl("g", { class: "dep-arcs-layer" });
    const tokensLayer = svgEl("g", { class: "dep-tokens-layer" });
    const mwtLayer = svgEl("g", { class: "dep-mwt-layer" });

    // Root indicator arcs (headId === 0)
    nodes.forEach((n) => {
      if (n.headId !== 0) return;
      const cx = layoutById[n.id].cx;
      const g = svgEl("g", {
        class: "dep-arc dep-cat-root",
        "data-head": 0,
        "data-dep": n.id
      });
      g.appendChild(
        svgEl("line", {
          class: "dep-arc-path",
          x1: cx,
          y1: 22,
          x2: cx,
          y2: arcBaseY - 5
        })
      );
      g.appendChild(
        svgEl("polygon", {
          class: "dep-arc-arrow",
          points: `${cx},${arcBaseY + 1} ${cx - 4.5},${arcBaseY - 7} ${cx + 4.5},${arcBaseY - 7}`
        })
      );
      const pillW = 42;
      g.appendChild(
        svgEl("rect", {
          class: "dep-arc-label-bg",
          x: cx - pillW / 2,
          y: 4,
          width: pillW,
          height: 17,
          rx: 8.5
        })
      );
      g.appendChild(
        svgEl("text", { class: "dep-arc-label", x: cx, y: 12.5 }, n.deprel || "root")
      );
      arcsLayer.appendChild(g);
    });

    // Non-root dependency arcs
    placed.forEach((arc) => {
      const headLayout = layoutById[arc.head];
      const depLayout = layoutById[arc.dep];
      const dir = arc.dep > arc.head ? 1 : -1;
      const x1 = headLayout.cx + dir * Math.min(10, headLayout.w * 0.14);
      const x2 = depLayout.cx;
      const targetApexY = arcBaseY - arc.level * levelStep;
      // Cubic Bezier passing exactly through ((x1+x2)/2, targetApexY) at t=0.5
      const cpY = (4 * targetApexY - arcBaseY) / 3;
      const midX = (x1 + x2) / 2;

      const g = svgEl("g", {
        class: `dep-arc dep-cat-${arc.cat}`,
        "data-head": arc.head,
        "data-dep": arc.dep
      });
      g.appendChild(
        svgEl("path", {
          class: "dep-arc-path",
          d: `M ${x1} ${arcBaseY} C ${x1} ${cpY}, ${x2} ${cpY}, ${x2} ${arcBaseY - 5}`
        })
      );
      g.appendChild(
        svgEl("polygon", {
          class: "dep-arc-arrow",
          points: `${x2},${arcBaseY + 1} ${x2 - 4.5},${arcBaseY - 7} ${x2 + 4.5},${arcBaseY - 7}`
        })
      );

      const labelText = arc.deprel || "dep";
      const pillW = Math.max(30, Math.round(labelText.length * 6.8 + 12));
      const pillH = 17;
      g.appendChild(
        svgEl("rect", {
          class: "dep-arc-label-bg",
          x: midX - pillW / 2,
          y: targetApexY - pillH / 2,
          width: pillW,
          height: pillH,
          rx: 8.5
        })
      );
      g.appendChild(
        svgEl("text", { class: "dep-arc-label", x: midX, y: targetApexY + 0.5 }, labelText)
      );
      arcsLayer.appendChild(g);
    });

    // Token boxes
    let selectedId = initialSelectedId;
    nodes.forEach((n) => {
      const l = layoutById[n.id];
      const classes = ["dep-token"];
      if (n.headId === 0) classes.push("is-root");
      if (n.id === selectedId) classes.push("is-selected");

      const g = svgEl("g", {
        class: classes.join(" "),
        "data-id": n.id
      });
      g.appendChild(
        svgEl("rect", {
          class: "dep-token-box",
          x: l.x,
          y: boxY,
          width: l.w,
          height: boxH
        })
      );
      g.appendChild(
        svgEl("text", { class: "dep-token-id", x: l.cx, y: boxY + 12 }, `#${n.id}`)
      );
      g.appendChild(
        svgEl("text", { class: "dep-token-form", x: l.cx, y: boxY + 30 }, n.form)
      );
      g.appendChild(
        svgEl("text", { class: "dep-token-upos", x: l.cx, y: boxY + 46 }, n.upos)
      );
      tokensLayer.appendChild(g);
    });

    // MWT span brackets under split tokens (e.g. 1-2 İstanbul'daydı)
    mwtSpans.forEach((span) => {
      const startL = layoutById[span.startId];
      const endL = layoutById[span.endId];
      if (!startL || !endL) return;
      const xStart = startL.x + 6;
      const xEnd = endL.x + endL.w - 6;
      const yTop = boxY + boxH + 4;
      const yBot = yTop + 8;
      mwtLayer.appendChild(
        svgEl("path", {
          class: "dep-mwt-line",
          d: `M ${xStart} ${yTop} L ${xStart} ${yBot} L ${xEnd} ${yBot} L ${xEnd} ${yTop}`
        })
      );
      mwtLayer.appendChild(
        svgEl(
          "text",
          {
            class: "dep-mwt-label",
            x: (xStart + xEnd) / 2,
            y: yBot + 14
          },
          `${span.startId}-${span.endId} ${span.form}`
        )
      );
    });

    svg.appendChild(arcsLayer);
    svg.appendChild(tokensLayer);
    svg.appendChild(mwtLayer);

    // Interactive focus/highlight & click selection
    const allArcEls = Array.from(arcsLayer.querySelectorAll(".dep-arc"));
    const allTokEls = Array.from(tokensLayer.querySelectorAll(".dep-token"));

    function clearFocus() {
      svg.classList.remove("has-focus");
      allArcEls.forEach((a) => a.classList.remove("focused"));
      allTokEls.forEach((t) => t.classList.remove("focused"));
    }

    function focusToken(tokenId) {
      svg.classList.add("has-focus");
      const relatedToks = new Set([tokenId]);
      allArcEls.forEach((a) => {
        const h = Number(a.dataset.head);
        const d = Number(a.dataset.dep);
        const hit = h === tokenId || d === tokenId;
        a.classList.toggle("focused", hit);
        if (hit) {
          if (h > 0) relatedToks.add(h);
          relatedToks.add(d);
        }
      });
      allTokEls.forEach((t) => {
        t.classList.toggle("focused", relatedToks.has(Number(t.dataset.id)));
      });
    }

    function focusArc(headId, depId) {
      svg.classList.add("has-focus");
      allArcEls.forEach((a) => {
        const hit = Number(a.dataset.head) === headId && Number(a.dataset.dep) === depId;
        a.classList.toggle("focused", hit);
      });
      allTokEls.forEach((t) => {
        const id = Number(t.dataset.id);
        t.classList.toggle("focused", id === headId || id === depId);
      });
    }

    function selectToken(tokenId) {
      selectedId = tokenId;
      allTokEls.forEach((t) => {
        t.classList.toggle("is-selected", Number(t.dataset.id) === tokenId);
      });
      onSelectNode(tokenId);
    }

    allTokEls.forEach((t) => {
      const id = Number(t.dataset.id);
      t.addEventListener("mouseenter", () => focusToken(id));
      t.addEventListener("mouseleave", clearFocus);
      t.addEventListener("click", () => selectToken(id));
    });

    allArcEls.forEach((a) => {
      const h = Number(a.dataset.head);
      const d = Number(a.dataset.dep);
      a.addEventListener("mouseenter", () => focusArc(h, d));
      a.addEventListener("mouseleave", clearFocus);
      a.addEventListener("click", () => selectToken(d));
    });

    return svg;
  }

  function renderDepTokenDetail(container, sent, nodeId) {
    const nodes = sent.nodes || [];
    const node = nodes.find((n) => n.id === nodeId);
    if (!node) {
      container.innerHTML = `<span class="io-meta">Click any token to inspect its syntactic and morphological features.</span>`;
      return;
    }
    const headNode = node.headId > 0 ? nodes.find((n) => n.id === node.headId) : null;
    const headDesc =
      node.headId === 0
        ? "ROOT (0)"
        : `#${node.headId} ${headNode ? headNode.form : ""}`;

    const children = nodes.filter((n) => n.headId === node.id);
    const childrenHtml =
      children.length > 0
        ? children
            .map(
              (c) =>
                `<span class="dep-feat-pill">${escapeHtml(c.deprel)}→#${c.id} ${escapeHtml(c.form)}</span>`
            )
            .join("")
        : `<span class="io-meta">none</span>`;

    const featsList =
      node.feats && node.feats !== "_" ? node.feats.split("|") : [];
    const featsHtml =
      featsList.length > 0
        ? featsList.map((f) => `<span class="dep-feat-pill">${escapeHtml(f)}</span>`).join("")
        : `<span class="io-meta">_</span>`;

    const cat = classifyDeprel(node.deprel);
    container.innerHTML = `
      <span class="dep-detail-item"><span class="dep-detail-label">Token</span><strong>#${node.id} ${escapeHtml(node.form)}</strong></span>
      <span class="dep-detail-item"><span class="dep-detail-label">Lemma</span><code>${escapeHtml(node.lemma)}</code></span>
      <span class="dep-detail-item"><span class="dep-detail-label">UPOS</span><code>${escapeHtml(node.upos)}</code></span>
      ${node.xpos && node.xpos !== "_" ? `<span class="dep-detail-item"><span class="dep-detail-label">XPOS</span><code>${escapeHtml(node.xpos)}</code></span>` : ""}
      <span class="dep-detail-item"><span class="dep-detail-label">Relation</span><span class="dep-legend-pill cat-${cat}">${escapeHtml(node.deprel)}</span> <span class="io-meta">← ${escapeHtml(headDesc)}</span></span>
      <span class="dep-detail-item"><span class="dep-detail-label">Feats</span><span class="dep-feat-pills">${featsHtml}</span></span>
      <span class="dep-detail-item"><span class="dep-detail-label">Dependents</span><span class="dep-feat-pills">${childrenHtml}</span></span>
    `;
  }

  function renderDependencyConllu(data) {
    const sents = data.sentences || [];
    const rows = [];
    sents.forEach((sent, sIdx) => {
      const mwtByStart = {};
      (sent.mwtSpans || []).forEach((m) => {
        mwtByStart[m.startId] = m;
      });
      if (sents.length > 1) {
        rows.push(`
          <tr>
            <td colspan="9" class="mono" style="background: var(--surface-subtle); font-weight: 700;">
              # sent ${sIdx + 1}: ${escapeHtml(sent.text)}
            </td>
          </tr>
        `);
      }
      (sent.nodes || []).forEach((n) => {
        const mwt = mwtByStart[n.id];
        if (mwt) {
          rows.push(`
            <tr style="background: var(--violet-soft);">
              <td class="mono"><strong>${mwt.startId}-${mwt.endId}</strong></td>
              <td class="cand-surf">${escapeHtml(mwt.form)}</td>
              <td class="mono">_</td>
              <td class="mono">_</td>
              <td class="mono">_</td>
              <td class="mono">_</td>
              <td class="mono">_</td>
              <td class="mono">_</td>
              <td class="mono">MWT</td>
            </tr>
          `);
        }
        const cat = classifyDeprel(n.deprel);
        rows.push(`
          <tr class="${n.headId === 0 ? "winner-row" : ""}">
            <td class="mono">${n.id}</td>
            <td class="cand-surf">${escapeHtml(n.form)}</td>
            <td class="mono">${escapeHtml(n.lemma)}</td>
            <td class="mono">${escapeHtml(n.upos)}</td>
            <td class="mono">${escapeHtml(n.xpos)}</td>
            <td class="mono">${escapeHtml(n.feats)}</td>
            <td class="mono">${n.headId}</td>
            <td><span class="dep-legend-pill cat-${cat}">${escapeHtml(n.deprel)}</span></td>
            <td class="mono">${escapeHtml(n.misc)}</td>
          </tr>
        `);
      });
    });

    depConlluTableWrap.innerHTML = `
      <table class="cand-table">
        <thead>
          <tr>
            <th>ID</th>
            <th>FORM</th>
            <th>LEMMA</th>
            <th>UPOS</th>
            <th>XPOS</th>
            <th>FEATS</th>
            <th>HEAD</th>
            <th>DEPREL</th>
            <th>MISC</th>
          </tr>
        </thead>
        <tbody>${rows.join("")}</tbody>
      </table>
    `;
    depConlluRaw.textContent = data.conllu || "";
  }

  // ==================== TAB 5: TOKENIZER ====================
  async function runTokenize() {
    const text = tokInput.value;
    if (!text.trim()) {
      tokSentences.innerHTML = "";
      tokResults.innerHTML = "";
      tokLatencyBadge.textContent = "0.00 ms";
      return;
    }
    const data = await postJsonWithAbort("tok", "/api/tokenize", { text });
    if (!data) return;

    tokLatencyBadge.textContent = `${data.latencyMs.toFixed(2)} ms`;
    serverStatusPill.textContent = `Live · ${data.latencyMs.toFixed(2)} ms`;
    tokSentences.innerHTML = (data.sentences || [])
      .map((s, idx) => `<div class="tok-sent-item"><strong>S${idx + 1}:</strong> ${escapeHtml(s)}</div>`)
      .join("");
    tokResults.innerHTML = (data.tokens || [])
      .map(
        (t) => `
          <div class="tok-pill" title="Span [${t.start}..${t.end}]">
            <span class="tok-pill-text">${escapeHtml(t.text)}</span>
            <span class="tok-pill-type">${escapeHtml(t.type)}</span>
          </div>
        `
      )
      .join("");
  }

  function formatSigned(v) {
    if (Math.abs(v) < 0.005) return "0.00";
    return (v > 0 ? "+" : "") + v.toFixed(2);
  }

  function escapeHtml(s) {
    return String(s ?? "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  // Normalization Event Listeners
  normInput.addEventListener("input", runNormalize);
  engineSelect.addEventListener("change", runNormalize);
  highlightToggle.addEventListener("change", () => {
    if (state.lastResponse) renderNormalizeResult(state.lastResponse);
  });
  compareAllToggle.addEventListener("change", runNormalize);

  clearInputBtn.addEventListener("click", () => {
    normInput.value = "";
    state.selectedTokenIdx = null;
    runNormalize();
    normInput.focus();
  });

  bindCopyButton(copyOutputBtn, () => (state.lastResponse ? state.lastResponse.normalizedText : ""));
  sendToMorphBtn.addEventListener("click", () =>
    openInMorphology(state.lastResponse && state.lastResponse.normalizedText)
  );
  sendToDepBtn.addEventListener("click", () =>
    openInDependency(state.lastResponse && state.lastResponse.normalizedText)
  );

  // Deasciifiers Event Listeners
  deascInput.addEventListener("input", runDeasciify);
  deascEngineSelect.addEventListener("change", () => {
    updateDeascStatsBar();
    runDeasciify();
  });
  deascHighlightToggle.addEventListener("change", () => {
    if (state.lastDeascResponse) renderDeasciifyResult(state.lastDeascResponse);
  });
  deascCompareToggle.addEventListener("change", runDeasciify);
  deascChangedOnlyToggle.addEventListener("change", () => {
    if (state.lastDeascResponse) renderDeascInspector(state.lastDeascResponse);
  });

  asciifyInputBtn.addEventListener("click", () => {
    if (!deascInput.value) return;
    deascInput.value = foldTurkishToAscii(deascInput.value);
    state.selectedDeascTokenIdx = null;
    runDeasciify();
    deascInput.focus();
  });

  clearDeascBtn.addEventListener("click", () => {
    deascInput.value = "";
    state.selectedDeascTokenIdx = null;
    runDeasciify();
    deascInput.focus();
  });

  bindCopyButton(
    copyDeascBtn,
    () => (state.lastDeascResponse ? state.lastDeascResponse.deasciifiedText : "")
  );

  deascToNormBtn.addEventListener("click", () => {
    if (deascInput.value.trim()) {
      normInput.value = deascInput.value;
      state.selectedTokenIdx = null;
    }
    switchTab("normalization", true);
  });

  deascToMorphBtn.addEventListener("click", () =>
    openInMorphology(state.lastDeascResponse && state.lastDeascResponse.deasciifiedText)
  );
  deascToDepBtn.addEventListener("click", () =>
    openInDependency(state.lastDeascResponse && state.lastDeascResponse.deasciifiedText)
  );

  // Dependency Tree Event Listeners
  depInput.addEventListener("input", runDependency);
  depShowConlluToggle.addEventListener("change", () => {
    if (state.lastDepResponse) renderDependencyResponse(state.lastDepResponse);
  });
  clearDepBtn.addEventListener("click", () => {
    depInput.value = "";
    runDependency();
    depInput.focus();
  });
  depToMorphBtn.addEventListener("click", () => openInMorphology(depInput.value.trim()));
  bindCopyButton(copyDepConlluBtn, () => (state.lastDepResponse ? state.lastDepResponse.conllu : ""));

  // Morphology & Tokenizer Event Listeners
  morphInput.addEventListener("input", runMorphology);
  tokInput.addEventListener("input", runTokenize);

  // Boot
  const initialTab = resolveInitialTab();
  loadServerInfo().then(() => switchTab(initialTab, false));
})();
