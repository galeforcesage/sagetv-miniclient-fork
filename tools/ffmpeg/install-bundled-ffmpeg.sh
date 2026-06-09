#!/usr/bin/env bash
set -euo pipefail

# Copies built ffmpeg executables into Android app assets so
# BundledFfmpegRemuxer can find them at runtime.
#
# Usage:
#   ./tools/ffmpeg/install-bundled-ffmpeg.sh --profile offline-remux
#   ./tools/ffmpeg/install-bundled-ffmpeg.sh --profile offline-remux --abis "arm64-v8a armeabi-v7a"

PROFILE="offline-remux"
ABIS="arm64-v8a armeabi-v7a"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile)
      PROFILE="$2"
      shift 2
      ;;
    --abis)
      ABIS="$2"
      shift 2
      ;;
    *)
      echo "Unknown arg: $1" >&2
      exit 1
      ;;
  esac
done

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
BUILD_ROOT="$ROOT_DIR/build/ffmpeg-android/$PROFILE"
ASSET_ROOT="$ROOT_DIR/android-offline/src/main/assets/ffmpeg"

for abi in $ABIS; do
  src="$BUILD_ROOT/$abi/prefix/bin/ffmpeg"
  dst_dir="$ASSET_ROOT/$abi"
  dst="$dst_dir/ffmpeg"

  if [[ ! -f "$src" ]]; then
    echo "Missing built binary for $abi: $src" >&2
    echo "Run build-android-ffmpeg.sh first." >&2
    exit 1
  fi

  mkdir -p "$dst_dir"
  cp "$src" "$dst"
  chmod +x "$dst"
  echo "Installed $abi -> $dst"
done

echo "Bundled FFmpeg assets installed under: $ASSET_ROOT"
