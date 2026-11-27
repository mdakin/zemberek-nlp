# `rs/` — Zemberek Rust, WebAssembly & Chrome MV3 Extension Workspace

Zero-dependency Rust + `wasm32-unknown-unknown` port of Zemberek-NLP (`#0`–`#9`), compiling Zemberek's lexicon, morphotactics, disambiguator, spellchecker, lattice deasciifier, and contextual span normalizer into zero-copy `.zmbk` packfiles and a Manifest V3 Chrome Writing Assistant extension.

## Key Documentation

- [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md): Complete architecture, binary `.zmbk` layout, Java-parity verification gates, multi-spellchecker coexistence policy, and 3-layer Turkish / non-Turkish language gating specification.
- [`extension-mv3/KURULUM.md`](extension-mv3/KURULUM.md): Unpacked Chrome extension installation guide, test scenarios, multiple-spellchecker handshake rules, and Turkish / non-Turkish / mixed-language behavior matrix (`v0.2.4`).
- [`extension-mv3/CHROMEWEBSTORE.md`](extension-mv3/CHROMEWEBSTORE.md): Chrome Web Store listing metadata, strict local-only privacy posture (`connect-src 'self'`), permission justifications, and version history.

## Workspace Layout

- `crates/zemberek-pack` (`#![no_std]`): 64-byte-aligned `.zmbk` v1 container, `PackedDictItem`, `MultiLevelMphf`, `MphfCountMap` (`CleanWordLm`), `StemTrie`, `EndingTrie`, and front-coded block CSR views.
- `crates/zemberek-core` & `crates/zemberek-tok` (`#![no_std]`): Turkish phonology, numerals, pronunciation guesser, and zero-regex DFA tokenizer/sentence segmenter with dual UTF-8 + UTF-16 spans.
- `crates/zemberek-morph`: `4,794`-state materialized Byte-FST, `DynamicStemTrie` (runtime user dictionary with full Turkish inflection), `UnidentifiedTokenAnalyzer`, and `WordGenerator`.
- `crates/zemberek-disambig`: Quantized `FastPerceptronAmbiguityResolver` (Trigram Viterbi / Beam-8 / Greedy).
- `crates/zemberek-spell`: Error-tolerant CSR stem+ending spellchecker ranked by `CleanWordLm` with input-casing tie-breaking.
- `crates/zemberek-deasc`: 2-pass SOV `LatticeDeasciifier` + `CircumflexMode`.
- `crates/zemberek-norm`: `SmoothLm`-free `SpanLatticePerceptronNormalizer` emitting categorized `SpanIssue` diagnostics (`Spelling`, `CliticSplitMerge`, `DeasciifyOrColloquial`).
- `crates/zemberek-wasm`: Streaming zero-copy `.zmbk` loader, bounded caches, Studio residual spellcheck fallback, and UTF-16 binary IPC ABI.
- `extension-mv3/`: TypeScript Chrome Manifest V3 extension (Singleton Offscreen Worker, `CSS.highlights` + Shadow-DOM `<textarea>` mirror, Popup, Side Panel Writing Studio, Options, native Chrome spellcheck suppression/handover, and 3-layer per-sentence non-Turkish gating).

## Quick Verification

```bash
# 1. Native Rust release unit & Java-parity integration tests
cargo test --release

# 2. Build compact Wasm module (-Oz + LTO + simd128) & verify in Node V8
cargo build --profile wasm-release --target wasm32-unknown-unknown -p zemberek-wasm
node tools/verify_wasm.mjs

# 3. Build & run Headless Chrome MV3 E2E verification
cd extension-mv3 && npm run typecheck && npm run build && npm test
```
