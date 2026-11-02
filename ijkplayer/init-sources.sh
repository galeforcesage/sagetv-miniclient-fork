#!/usr/bin/env bash

set -euo pipefail

IJKPLAYER_COMMIT="cced91e3ae3730f5c63f3605b00d25eafcf5b97b"
FFMPEG_TAG_OBJECT="6f4048827982a8f48f71f551a6e1ed2362816eec"
FFMPEG_COMMIT="e38092ef9395d7049f871ef4d5411eb410e283e0"
NDK_VERSION="28.2.13676358"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
IJKPLAYER_ROOT="${IJKPLAYER_SOURCE_DIR:-$SCRIPT_DIR/ijkplayer}"

if [ ! -d "$IJKPLAYER_ROOT/.git" ]; then
    git clone https://github.com/Bilibili/ijkplayer.git "$IJKPLAYER_ROOT"
fi
git -C "$IJKPLAYER_ROOT" fetch --tags origin
git -C "$IJKPLAYER_ROOT" checkout --detach "$IJKPLAYER_COMMIT"
test "$(git -C "$IJKPLAYER_ROOT" rev-parse HEAD)" = "$IJKPLAYER_COMMIT"

for abi in armv7a arm64; do
    ffmpeg_root="$IJKPLAYER_ROOT/android/contrib/ffmpeg-$abi"
    if [ ! -d "$ffmpeg_root/.git" ]; then
        git clone https://github.com/FFmpeg/FFmpeg.git "$ffmpeg_root"
    fi
    git -C "$ffmpeg_root" fetch --force origin \
        "refs/tags/n6.1.1:refs/tags/n6.1.1"
    test "$(git -C "$ffmpeg_root" rev-parse refs/tags/n6.1.1)" = "$FFMPEG_TAG_OBJECT"
    test "$(git -C "$ffmpeg_root" rev-parse refs/tags/n6.1.1^{})" = "$FFMPEG_COMMIT"
    git -C "$ffmpeg_root" checkout --detach "$FFMPEG_COMMIT"
done

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
