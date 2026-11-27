import esbuild from "esbuild";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { writeExtensionIcons } from "./generate-icons.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const extRoot = path.resolve(__dirname, "..");
const rsRoot = path.resolve(extRoot, "..");
const distDir = path.join(extRoot, "dist");

fs.rmSync(distDir, { recursive: true, force: true });
fs.mkdirSync(distDir, { recursive: true });

// 1. Generate separate-dimension real PNG icons (16x16, 48x48, 128x128)
writeExtensionIcons(path.join(distDir, "icons"));

// 2. Bundle ESM extension scripts (Service Worker, Offscreen, Wasm Worker, Popup, Side Panel, Options)
await esbuild.build({
  entryPoints: {
    "service-worker": path.join(extRoot, "src/service-worker.ts"),
    offscreen: path.join(extRoot, "src/offscreen.ts"),
    "wasm-worker": path.join(extRoot, "src/wasm-worker.ts"),
    popup: path.join(extRoot, "src/popup.ts"),
    sidepanel: path.join(extRoot, "src/sidepanel.ts"),
    options: path.join(extRoot, "src/options.ts"),
  },
  outdir: distDir,
  bundle: true,
  format: "esm",
  target: "chrome116",
  sourcemap: false,
  minify: false,
});

// 3. Bundle Content Script as self-contained IIFE (classic script context in MV3)
await esbuild.build({
  entryPoints: {
    "content-script": path.join(extRoot, "src/content-script.ts"),
  },
  outdir: distDir,
  bundle: true,
  format: "iife",
  target: "chrome116",
  sourcemap: false,
  minify: false,
});

// 4. Copy static manifest, HTML pages, and content stylesheet
for (const staticFile of [
  "manifest.json",
  "offscreen.html",
  "popup.html",
  "sidepanel.html",
  "options.html",
  "content-styles.css",
]) {
  fs.copyFileSync(path.join(extRoot, staticFile), path.join(distDir, staticFile));
}

// 5. Copy compiled Wasm binary + Core Lite & Studio .zmbk zero-copy packs
const wasmCandidates = [
  path.join(rsRoot, "target/wasm32-unknown-unknown/wasm-release/zemberek_wasm.wasm"),
  path.join(rsRoot, "target/wasm32-unknown-unknown/release/zemberek_wasm.wasm"),
];
const wasmSrc = wasmCandidates.find((p) => fs.existsSync(p));
if (!wasmSrc) {
  throw new Error("Missing zemberek_wasm.wasm — run cargo build --profile wasm-release first");
}
fs.copyFileSync(wasmSrc, path.join(distDir, "zemberek_wasm.wasm"));

const litePackSrc = path.join(rsRoot, "testdata/zemberek_lite.zmbk");
const studioPackSrc = path.join(rsRoot, "testdata/zemberek_step2.zmbk");
if (fs.existsSync(litePackSrc)) {
  fs.copyFileSync(litePackSrc, path.join(distDir, "zemberek_lite.zmbk"));
}
if (fs.existsSync(studioPackSrc)) {
  fs.copyFileSync(studioPackSrc, path.join(distDir, "zemberek_studio.zmbk"));
}

console.log(`Built MV3 extension into ${distDir}`);
