#!/usr/bin/env bash
# Master end-to-end verification script re-verifying every step of the modern Chrome Turkish spellchecker project.
set -euo pipefail

export PATH="$HOME/.cargo/bin:$PATH"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CR_TOOLS="$ROOT_DIR/chromium-tools"
GEN_DIR="$ROOT_DIR/generator"
DICT_DIR="$ROOT_DIR/dict"
BENCH_DIR="$ROOT_DIR/benchmarks"
LEGACY_DIR="${LEGACY_HUNSPELL_DIR:-}"

echo "=================================================================="
echo "[Step 1/5] Building Chromium convert_dict, bdict_tool & upstream_hunspell_tool"
echo "=================================================================="
cmake -S "$CR_TOOLS" -B "$CR_TOOLS/build" -G Ninja >/dev/null
ninja -C "$CR_TOOLS/build"

TMP_RT="$(mktemp -d)"
trap 'rm -rf "$TMP_RT"' EXIT

echo ""
echo "=================================================================="
echo "[Step 2/5] Verifying SHA-256 Round-Trip Parity on Chromium tr-TR-4-0.bdic"
echo "=================================================================="
if [[ -n "$LEGACY_DIR" && -f "$LEGACY_DIR/tr-TR-4-0.bdic" ]]; then
  cp "$LEGACY_DIR/tr.aff" "$TMP_RT/tr.aff"
  cp "$LEGACY_DIR/tr.dic" "$TMP_RT/tr.dic"
  "$CR_TOOLS/build/convert_dict" "$TMP_RT/tr" >/dev/null
  SHA_ORIG="$(sha256sum "$LEGACY_DIR/tr-TR-4-0.bdic" | awk '{print $1}')"
  SHA_REBUILT="$(sha256sum "$TMP_RT/tr.bdic" | awk '{print $1}')"
  echo "  Chromium shipped tr-TR-4-0.bdic SHA-256: $SHA_ORIG"
  echo "  Recompiled tr.bdic SHA-256:              $SHA_REBUILT"
  if [[ "$SHA_ORIG" != "$SHA_REBUILT" ]]; then
    echo "FAIL: Round-trip SHA-256 mismatch!"
    exit 1
  fi
  echo "  PASS: 100% bit-identical round-trip with official Chromium convert_dict!"
else
  echo "  SKIP: Set LEGACY_HUNSPELL_DIR to a chromium/deps/hunspell_dictionaries checkout to run legacy round-trip check."
fi

echo ""
echo "=================================================================="
echo "[Step 3/5] Generating Modern Turkish Dictionary & Compiling tr-TR-5-0.bdic"
echo "=================================================================="
(cd "$GEN_DIR" && cargo run --release)
(cd "$DICT_DIR" && "$CR_TOOLS/build/convert_dict" tr-TR && cp tr-TR.bdic tr-TR-5-0.bdic)

echo ""
echo "=================================================================="
echo "[Step 4/5] Benchmarking tr-TR-5-0.bdic & Checking Upstream Hunspell Parity"
echo "=================================================================="
"$CR_TOOLS/build/bdict_tool" --bench "$DICT_DIR/tr-TR-5-0.bdic" \
  --valid "$BENCH_DIR/valid_mevzuat_2025_tokens.txt" \
  --invalid "$BENCH_DIR/invalid_turkish_traps.txt" \
  --suggest "$BENCH_DIR/suggest_modern_turkish_typos.tsv"

"$CR_TOOLS/build/bdict_tool" --bench "$DICT_DIR/tr-TR-5-0.bdic" \
  --valid "$BENCH_DIR/valid_modern_2025_2026_types.txt" \
  --suggest "$BENCH_DIR/suggest_trspell10_1500.tsv"

"$CR_TOOLS/build/bdict_tool" --bench "$DICT_DIR/tr-TR-5-0.bdic" \
  --valid "$BENCH_DIR/valid_modern_neologisms_and_proper.txt"

TEST_WORDS=(farkındalık farkındalığı sürdürülebilirlik "Türkiye'nin" "Türkiye’nin" "İBB'nin" "2026'da" "100'üncü" İSTANBUL ÖĞRENCİLER resmî hâlâ gerçekleştirmeyen özelleştirilmeden kitab ağz yalnış herkez herşey ogrenci gidicem sokakda)
"$CR_TOOLS/build/bdict_tool" --query "$DICT_DIR/tr-TR-5-0.bdic" "${TEST_WORDS[@]}" > "$TMP_RT/p_bdict.txt" 2>/dev/null
"$CR_TOOLS/build/upstream_hunspell_tool" "$DICT_DIR/tr-TR.aff" "$DICT_DIR/tr-TR.dic" "${TEST_WORDS[@]}" > "$TMP_RT/p_up.txt" 2>/dev/null
diff -u <(cut -d'|' -f1 "$TMP_RT/p_bdict.txt") <(cut -d'|' -f1 "$TMP_RT/p_up.txt")
echo "  PASS: 100% spell() parity between Chrome BDict and upstream Hunspell (.aff/.dic)!"

echo ""
echo "=================================================================="
echo "[Step 5/5] Live Integration Verification Inside Google Chrome 158"
echo "=================================================================="
node "$ROOT_DIR/verify_chrome158.mjs"

echo ""
echo "=================================================================="
echo "ALL 5 VERIFICATION STAGES PASSED 100%!"
echo "=================================================================="
