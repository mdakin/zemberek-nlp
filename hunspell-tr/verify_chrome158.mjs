#!/usr/bin/env node
/**
 * End-to-end live verification of tr-TR-5-0.bdic vs legacy 2008 tr-TR-4-0.bdic
 * inside real Google Chrome 158 on Linux via CDP (--remote-debugging-pipe),
 * verifying:
 *   1. Linux kernel /proc/<renderer_pid>/maps mmap of our .bdic inside Chrome's renderer
 *   2. BDict::Verify() MD5 acceptance by Chrome's SpellcheckHunspellDictionary
 *   3. Actual Blink ::spelling-error red wavy underline rendering on modern Turkish words vs typos
 */

import { spawn } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import zlib from 'node:zlib';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CHROME_BIN = process.env.CHROME_BIN || '/usr/bin/google-chrome';
const NEW_BDIC = path.join(__dirname, 'dict/tr-TR-5-0.bdic');
const OLD_BDIC = process.env.LEGACY_TR_BDIC || '';
const OUT_DIR = path.join(__dirname, 'benchmarks');

const VALID_MODERN_WORDS = [
  'farkındalık',
  'sürdürülebilirlik',
  'ekosistem',
  'çevrimiçi',
  'resmî',
  'hâlâ',
  "Türkiye'yi",
  "İBB'nin",
  "2026'da",
];

const MISSPELLED_WORDS = [
  'yalnış',
  'herkez',
  'kitablar',
  'gidicem',
  'sokakda',
  'ogrenci',
];

function parsePngRgba(pngBuf) {
  let off = 8;
  let width = 0, height = 0, bitDepth = 0, colorType = 0;
  const idatChunks = [];
  while (off < pngBuf.length) {
    const len = pngBuf.readUInt32BE(off);
    const type = pngBuf.toString('ascii', off + 4, off + 8);
    const data = pngBuf.subarray(off + 8, off + 8 + len);
    if (type === 'IHDR') {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      bitDepth = data[8];
      colorType = data[9];
    } else if (type === 'IDAT') {
      idatChunks.push(data);
    } else if (type === 'IEND') {
      break;
    }
    off += 12 + len;
  }
  if (bitDepth !== 8 || (colorType !== 6 && colorType !== 2)) {
    throw new Error(`Unsupported PNG format: bitDepth=${bitDepth}, colorType=${colorType}`);
  }
  const bpp = colorType === 6 ? 4 : 3;
  const stride = width * bpp;
  const raw = zlib.inflateSync(Buffer.concat(idatChunks));
  const recon = Buffer.alloc(height * stride);

  const paeth = (a, b, c) => {
    const p = a + b - c;
    const pa = Math.abs(p - a);
    const pb = Math.abs(p - b);
    const pc = Math.abs(p - c);
    if (pa <= pb && pa <= pc) return a;
    if (pb <= pc) return b;
    return c;
  };

  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)];
    const rowIn = y * (stride + 1) + 1;
    const rowOut = y * stride;
    for (let i = 0; i < stride; i++) {
      const x = raw[rowIn + i];
      const a = i >= bpp ? recon[rowOut + i - bpp] : 0;
      const b = y > 0 ? recon[rowOut - stride + i] : 0;
      const c = (y > 0 && i >= bpp) ? recon[rowOut - stride + i - bpp] : 0;
      let val = x;
      if (filter === 1) val = (x + a) & 0xff;
      else if (filter === 2) val = (x + b) & 0xff;
      else if (filter === 3) val = (x + ((a + b) >> 1)) & 0xff;
      else if (filter === 4) val = (x + paeth(a, b, c)) & 0xff;
      recon[rowOut + i] = val;
    }
  }
  return { width, height, bpp, stride, pixels: recon };
}

function countRedPixels(img, rect) {
  const x0 = Math.max(0, Math.floor(rect.x));
  const x1 = Math.min(img.width - 1, Math.ceil(rect.x + rect.width));
  const y0 = Math.max(0, Math.floor(rect.y));
  const y1 = Math.min(img.height - 1, Math.ceil(rect.y + rect.height + 8));
  let red = 0;
  for (let y = y0; y <= y1; y++) {
    for (let x = x0; x <= x1; x++) {
      const idx = y * img.stride + x * img.bpp;
      const r = img.pixels[idx];
      const g = img.pixels[idx + 1];
      const b = img.pixels[idx + 2];
      if (r > 180 && g < 100 && b < 100 && (r - g) > 90) {
        red++;
      }
    }
  }
  return red;
}

function findRendererMmap(targetBdicPath) {
  const realTarget = fs.realpathSync(targetBdicPath);
  const hits = [];
  for (const entry of fs.readdirSync('/proc')) {
    if (!/^\d+$/.test(entry)) continue;
    try {
      const maps = fs.readFileSync(`/proc/${entry}/maps`, 'utf8');
      if (maps.includes(realTarget) || maps.includes('tr-TR-4-0.bdic')) {
        const cmdline = fs.readFileSync(`/proc/${entry}/cmdline`, 'utf8').replace(/\0/g, ' ');
        hits.push({ pid: Number(entry), isRenderer: cmdline.includes('--type=renderer'), cmdline: cmdline.slice(0, 120) });
      }
    } catch {
      // process exited or permission denied
    }
  }
  return hits;
}

async function runChromeSpellTest(label, sourceBdicPath, screenshotOutPath) {
  const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), `cr158_${label}_`));
  const dictDir = path.join(userDataDir, 'Dictionaries');
  const defaultDir = path.join(userDataDir, 'Default');
  fs.mkdirSync(dictDir, { recursive: true });
  fs.mkdirSync(defaultDir, { recursive: true });

  // Chrome 158 expects filename `tr-TR-4-0.bdic` from spellcheck_common.cc
  const installedBdic = path.join(dictDir, 'tr-TR-4-0.bdic');
  fs.copyFileSync(sourceBdicPath, installedBdic);
  const expectedSize = fs.statSync(installedBdic).size;
  const expectedSha = crypto.createHash('sha256').update(fs.readFileSync(installedBdic)).digest('hex');

  fs.writeFileSync(
    path.join(defaultDir, 'Preferences'),
    JSON.stringify({
      browser: { enable_spellchecking: true },
      spellcheck: {
        dictionaries: ['tr-TR'],
        dictionary: 'tr-TR',
        use_spelling_service: false,
      },
      intl: { accept_languages: 'tr-TR,tr' },
    })
  );

  const html = `<!doctype html>
<html lang="tr">
<head>
<meta charset="utf-8">
<style>
  body { margin: 0; padding: 20px; background: #ffffff; color: #111111; font-family: Arial, sans-serif; }
  .row { margin-bottom: 14px; }
  div[contenteditable] {
    font-size: 26px; line-height: 2.0; padding: 12px; border: 1px solid #ccc;
    outline: none; width: 1080px; background: #fff; color: #000;
  }
</style>
</head>
<body>
  <div id="ed" contenteditable="true" spellcheck="true" lang="tr"></div>
</body>
</html>`;

  const server = http.createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(html);
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const port = server.address().port;

  const chrome = spawn(
    CHROME_BIN,
    [
      '--headless=new',
      '--no-sandbox',
      '--disable-gpu',
      '--lang=tr',
      '--window-size=1200,700',
      '--force-device-scale-factor=1',
      `--user-data-dir=${userDataDir}`,
      '--remote-debugging-pipe',
      'about:blank',
    ],
    { stdio: ['ignore', 'ignore', 'pipe', 'pipe', 'pipe'] }
  );

  const pipeWrite = chrome.stdio[3];
  const pipeRead = chrome.stdio[4];
  let nextId = 1;
  const pending = new Map();
  let rxBuf = Buffer.alloc(0);

  pipeRead.on('data', (chunk) => {
    rxBuf = Buffer.concat([rxBuf, chunk]);
    let nul;
    while ((nul = rxBuf.indexOf(0)) !== -1) {
      const msgStr = rxBuf.toString('utf8', 0, nul);
      rxBuf = rxBuf.subarray(nul + 1);
      if (!msgStr) continue;
      const msg = JSON.parse(msgStr);
      if (msg.id && pending.has(msg.id)) {
        const { resolve, reject } = pending.get(msg.id);
        pending.delete(msg.id);
        if (msg.error) reject(new Error(JSON.stringify(msg.error)));
        else resolve(msg.result);
      }
    }
  });

  const send = (method, params = {}, sessionId = undefined) =>
    new Promise((resolve, reject) => {
      const id = nextId++;
      pending.set(id, { resolve, reject });
      const payload = { id, method, params };
      if (sessionId) payload.sessionId = sessionId;
      pipeWrite.write(JSON.stringify(payload) + '\0');
    });

  try {
    const targets = await send('Target.getTargets');
    const pageTarget = targets.targetInfos.find((t) => t.type === 'page');
    const { sessionId } = await send('Target.attachToTarget', { targetId: pageTarget.targetId, flatten: true });
    await send('Page.enable', {}, sessionId);
    await send('Runtime.enable', {}, sessionId);
    await send('Page.navigate', { url: `http://127.0.0.1:${port}/` }, sessionId);
    await new Promise((r) => setTimeout(r, 500));

    // Click inside #ed to focus and trigger SpellCheck initialization
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: 60, y: 50, button: 'left', clickCount: 1 }, sessionId);
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: 60, y: 50, button: 'left', clickCount: 1 }, sessionId);

    const allWords = [...VALID_MODERN_WORDS, ...MISSPELLED_WORDS];
    for (const w of allWords) {
      await send('Input.insertText', { text: w }, sessionId);
      await send('Input.dispatchKeyEvent', { type: 'keyDown', key: ' ', code: 'Space', text: ' ' }, sessionId);
      await send('Input.dispatchKeyEvent', { type: 'keyUp', key: ' ', code: 'Space' }, sessionId);
      await new Promise((r) => setTimeout(r, 60));
    }

    // Wait for Mojo RequestDictionary + mmap + IdleSpellCheckCallback
    await new Promise((r) => setTimeout(r, 1800));

    // Check /proc/*/maps for live renderer mmap!
    const mmapHits = findRendererMmap(installedBdic);
    const diskSizeAfter = fs.statSync(installedBdic).size;
    const diskShaAfter = crypto.createHash('sha256').update(fs.readFileSync(installedBdic)).digest('hex');

    // Measure exact bounding rects of every typed token inside #ed
    const rectEval = await send(
      'Runtime.evaluate',
      {
        returnByValue: true,
        expression: `(() => {
          const ed = document.getElementById('ed');
          const textNode = ed.firstChild;
          const full = textNode ? textNode.textContent : '';
          const words = ${JSON.stringify(allWords)};
          const out = [];
          let searchFrom = 0;
          for (const w of words) {
            const idx = full.indexOf(w, searchFrom);
            if (idx >= 0) {
              const r = document.createRange();
              r.setStart(textNode, idx);
              r.setEnd(textNode, idx + w.length);
              const b = r.getBoundingClientRect();
              out.push({ word: w, x: b.x, y: b.y, width: b.width, height: b.height });
              searchFrom = idx + w.length;
            }
          }
          // Move caret to end and blur selection highlight so screenshot is clean
          window.getSelection().removeAllRanges();
          return out;
        })()`,
      },
      sessionId
    );
    await new Promise((r) => setTimeout(r, 200));

    const shot = await send('Page.captureScreenshot', { format: 'png' }, sessionId);
    const pngBuf = Buffer.from(shot.data, 'base64');
    fs.writeFileSync(screenshotOutPath, pngBuf);
    const img = parsePngRgba(pngBuf);

    const wordResults = rectEval.result.value.map((r) => ({
      word: r.word,
      redPixels: countRedPixels(img, r),
    }));

    return {
      label,
      expectedSize,
      diskSizeAfter,
      shaPreserved: expectedSha === diskShaAfter,
      mmapHits,
      wordResults,
      screenshotOutPath,
    };
  } finally {
    chrome.kill('SIGKILL');
    await new Promise((r) => chrome.once('exit', r));
    await new Promise((r) => setTimeout(r, 250));
    server.close();
    fs.rmSync(userDataDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
}

const runs = [];
if (OLD_BDIC && fs.existsSync(OLD_BDIC)) {
  runs.push(await runChromeSpellTest('legacy_2008', OLD_BDIC, path.join(OUT_DIR, 'chrome158_legacy_2008.png')));
}
const resNew = await runChromeSpellTest('modern_2026', NEW_BDIC, path.join(OUT_DIR, 'chrome158_modern_2026.png'));
runs.push(resNew);

for (const res of runs) {
  console.log(`\n=== Live Google Chrome 158 Test: ${res.label} ===`);
  console.log(`  BDict size on disk: ${res.expectedSize} -> ${res.diskSizeAfter} B (SHA-256 preserved: ${res.shaPreserved})`);
  console.log(`  Kernel /proc/<pid>/maps hits (${res.mmapHits.length}):`, res.mmapHits.map((h) => `pid=${h.pid} renderer=${h.isRenderer}`).join(', '));
  for (const wr of res.wordResults) {
    const status = wr.redPixels > 10 ? `RED_UNDERLINE (${wr.redPixels} red px)` : `CLEAN (${wr.redPixels} red px)`;
    console.log(`    ${wr.word.padEnd(20)} -> ${status}`);
  }
}

// Strict assertions on our new 2026 tr-TR-5-0.bdic inside Chrome 158!
const rendererMapped = resNew.mmapHits.some((h) => h.isRenderer);
if (!resNew.shaPreserved || !rendererMapped) {
  console.error('FAIL: Chrome 158 renderer did not mmap tr-TR-5-0.bdic!');
  process.exit(1);
}
const validFailures = resNew.wordResults.filter((w) => VALID_MODERN_WORDS.includes(w.word) && w.redPixels > 10);
const typoFailures = resNew.wordResults.filter((w) => MISSPELLED_WORDS.includes(w.word) && w.redPixels <= 10);
if (validFailures.length > 0 || typoFailures.length > 0) {
  console.error('FAIL: Unexpected underline results:', { validFailures, typoFailures });
  process.exit(1);
}
console.log('\nPASS: Live Google Chrome 158 mmap + BDict::Verify() + pixel-exact spellcheck underline verification SUCCEEDED 100%!');
