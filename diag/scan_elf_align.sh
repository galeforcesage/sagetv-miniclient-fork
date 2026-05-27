#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <apk_path>" >&2
  exit 2
fi

APK_PATH="$1"
READELF_BIN="/home/sagetv/.cache/sagetv-exoplayer-build/android-ndk-r25c/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"

if [[ ! -x "$READELF_BIN" ]]; then
  echo "ERROR: llvm-readelf missing: $READELF_BIN" >&2
  exit 3
fi

if [[ ! -f "$APK_PATH" ]]; then
  echo "ERROR: APK not found: $APK_PATH" >&2
  exit 4
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

unzip -qq "$APK_PATH" "lib/*" -d "$TMP_DIR"

fail=0
for so in "$TMP_DIR"/lib/arm64-v8a/*.so "$TMP_DIR"/lib/armeabi-v7a/*.so; do
  [[ -f "$so" ]] || continue

  aligns="$($READELF_BIN -lW "$so" | awk '/^  LOAD/ {print $NF}' | sort -u)"
  min_align=999999999
  min_hex=""

  for a in $aligns; do
    v=$((16#${a#0x}))
    if [[ "$v" -lt "$min_align" ]]; then
      min_align="$v"
      min_hex="$a"
    fi
    if [[ "$v" -lt 16384 ]]; then
      fail=1
    fi
  done

  echo "$(basename "$so")|min=$min_hex|all=$aligns"
done

if [[ "$fail" -eq 0 ]]; then
  echo "RESULT:PASS"
else
  echo "RESULT:FAIL"
  exit 1
fi
