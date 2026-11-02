#!/usr/bin/env bash

set -euo pipefail

IJKPLAYER_COMMIT="cced91e3ae3730f5c63f3605b00d25eafcf5b97b"
NDK_VERSION="28.2.13676358"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
IJKPLAYER_ROOT="${IJKPLAYER_SOURCE_DIR:-$SCRIPT_DIR/ijkplayer}"

if [ ! -d "$IJKPLAYER_ROOT/.git" ]; then
    git clone https://github.com/Bilibili/ijkplayer.git "$IJKPLAYER_ROOT"
fi
git -C "$IJKPLAYER_ROOT" fetch --tags origin
git -C "$IJKPLAYER_ROOT" checkout --detach "$IJKPLAYER_COMMIT"
test "$(git -C "$IJKPLAYER_ROOT" rev-parse HEAD)" = "$IJKPLAYER_COMMIT"

if [ "${ANDROID_SDK:-}" = "" ]; then
    echo "Set ANDROID_SDK to an Android SDK containing cmdline-tools." >&2
    exit 1
fi

SDKMANAGER="$ANDROID_SDK/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
    echo "Missing supported sdkmanager at $SDKMANAGER" >&2
    exit 1
fi

"$SDKMANAGER" "ndk;$NDK_VERSION"
echo "Set ANDROID_NDK=$ANDROID_SDK/ndk/$NDK_VERSION"
