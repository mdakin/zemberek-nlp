# `rs/` — Zemberek Rust & WebAssembly Workspace (Step 0 Foundation)

Minimal, zero-dependency Rust + `wasm32-unknown-unknown` foundation validating the `.zmbk` zero-copy container, `PackedDictItem` lexicon records, and bit-exact `MultiLevelMphf` / `QuantizedWeightTable` parity against Java `zemberek-nlp`.

## Workspace Layout

- `crates/zemberek-pack` (`#![no_std]`):
  - 64-byte-aligned `.zmbk` v1 header, section directory, IEEE CRC32 verification, and `O(1)` zero-copy `Pod` slice casting.
  - `PackedDictItem` (`12 B`, `#[repr(C)]`) + shared UTF-8 string pool (`LexiconView`).
  - Bit-exact `MultiLevelMphfView` + `QuantizedWeightTableView` (`16-bit` fingerprint + `f32[256]` codebook).
- `crates/zemberek-wasm` (`cdylib`, `wasm32-unknown-unknown` with `+simd128` and 64 KiB stack):
  - Exports C-ABI Worker entrypoints (`zmbk_alloc_pack`, `zmbk_init`, `zmbk_dict_*`, `zmbk_qwt_get`).
- `tools/ExportStep0Zmbk.java`:
  - Exports a real 64-item slice of `RootLexicon.getDefault()` + a 1,024-key `QuantizedWeightTable` into `testdata/step0_mini.zmbk` and `testdata/step0_expected.tsv`.
- `tools/verify_wasm.mjs`:
  - Instantiates `zemberek_wasm.wasm` in Node.js (V8) and verifies `<1 ms` zero-copy initialization and 100% IEEE-754 bit parity against Java.

## Quick Verification

```bash
# 1. Native Rust unit & Java-parity integration tests
cargo test

# 2. Build compact Wasm module (-Oz + LTO + simd128) & verify in V8
cargo build -p zemberek-wasm --target wasm32-unknown-unknown --release
node tools/verify_wasm.mjs
```
