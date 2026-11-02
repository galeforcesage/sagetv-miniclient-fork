#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="${SAGETV_IJK_SCRIPT_DIR:-$(cd "$(dirname "$0")" && pwd)}"
PATCH_DIR="$SCRIPT_DIR/patches/ijk"
IJKPLAYER_COMMIT="cced91e3ae3730f5c63f3605b00d25eafcf5b97b"
IJKPLAYER_ROOT="${IJKPLAYER_SOURCE_DIR:-$SCRIPT_DIR/ijkplayer}"

if [ "${ANDROID_SDK:-}" = "" ]; then
    echo "Set ANDROID_SDK to the Android SDK location." >&2
    exit 1
fi

if [ "${ANDROID_NDK:-}" = "" ]; then
    if [ -d "$ANDROID_SDK/ndk/28.2.13676358" ]; then
        export ANDROID_NDK="$ANDROID_SDK/ndk/28.2.13676358"
    else
        echo "Set ANDROID_NDK to Android NDK r28c (28.2.13676358)." >&2
        exit 1
    fi
fi

if [ ! -d "$IJKPLAYER_ROOT/.git" ]; then
    git clone https://github.com/Bilibili/ijkplayer.git "$IJKPLAYER_ROOT"
fi

git -C "$IJKPLAYER_ROOT" fetch --tags origin
git -C "$IJKPLAYER_ROOT" checkout --detach "$IJKPLAYER_COMMIT"
test "$(git -C "$IJKPLAYER_ROOT" rev-parse HEAD)" = "$IJKPLAYER_COMMIT"

cd "$IJKPLAYER_ROOT/config"
ln -sf module-default.sh module.sh
cd "$IJKPLAYER_ROOT"
./init-android.sh

normalize_patch()
{
    local patch_file="$1"
    local normalized_patch="$2"
    tr -d '\r' < "$patch_file" > "$normalized_patch"
}

apply_named_patch()
{
    local source_root="$1"
    local patch_file="$2"
    local normalized_patch
    normalized_patch="$(mktemp)"
    normalize_patch "$patch_file" "$normalized_patch"
    if git -C "$source_root" apply --check "$normalized_patch"; then
        git -C "$source_root" apply "$normalized_patch"
        echo "Applied: $(basename "$patch_file")"
    else
        rm -f "$normalized_patch"
        echo "Patch no longer applies cleanly: $patch_file" >&2
        exit 1
    fi
    rm -f "$normalized_patch"
}

# Restore only patch-owned files from their pinned source revisions. This makes
# reruns deterministic even when the patch content itself has evolved.
for source_file in \
    android/contrib/tools/do-compile-ffmpeg.sh \
    android/contrib/tools/do-detect-env.sh \
    android/ijkplayer/ijkplayer-arm64/src/main/jni/Application.mk \
    android/ijkplayer/ijkplayer-armv7a/src/main/jni/Application.mk \
    ijkmedia/ijkj4a/Android.mk \
    ijkmedia/ijkplayer/android/pipeline/ffpipeline_android.c \
    ijkmedia/ijkplayer/Android.mk \
    ijkmedia/ijkplayer/ff_ffplay.c \
    ijkmedia/ijksdl/Android.mk \
    ijkmedia/ijksdl/ijksdl_egl.c
do
    git -C "$IJKPLAYER_ROOT" show "$IJKPLAYER_COMMIT:$source_file" > "$IJKPLAYER_ROOT/$source_file"
done

for ff_dir in android/contrib/ffmpeg-armv7a android/contrib/ffmpeg-arm64; do
    git -C "$IJKPLAYER_ROOT/$ff_dir" show HEAD:libavcodec/opus_pvq.c \
        > "$IJKPLAYER_ROOT/$ff_dir/libavcodec/opus_pvq.c"
    git -C "$IJKPLAYER_ROOT/$ff_dir" show HEAD:libavformat/utils.c \
        > "$IJKPLAYER_ROOT/$ff_dir/libavformat/utils.c"
done
git -C "$IJKPLAYER_ROOT/android/contrib/ffmpeg-arm64" \
    show HEAD:libavcodec/aarch64/h264dsp_init_aarch64.c \
    > "$IJKPLAYER_ROOT/android/contrib/ffmpeg-arm64/libavcodec/aarch64/h264dsp_init_aarch64.c"

apply_named_patch "$IJKPLAYER_ROOT" "$PATCH_DIR/0001-fix-c99-flag-placement.patch"
apply_named_patch "$IJKPLAYER_ROOT" "$PATCH_DIR/0003-add-ndk-r28-compatibility.patch"
apply_named_patch "$IJKPLAYER_ROOT" "$PATCH_DIR/0006-support-16k-page-alignment.patch"

for ff_dir in android/contrib/ffmpeg-armv7a android/contrib/ffmpeg-arm64; do
    apply_named_patch "$IJKPLAYER_ROOT/$ff_dir" "$PATCH_DIR/0002-fix-opus-b0-clang-collision.patch"
    apply_named_patch "$IJKPLAYER_ROOT/$ff_dir" "$PATCH_DIR/0004-guard-unindexed-binary-seek.patch"
done

# Keep the historical Sage codec profile separate from source compatibility patches.
sed -i '/--disable-ffserver/d; /--disable-vda/d' config/module.sh
if ! grep -q -- '--disable-decoder=hevc' config/module.sh; then
    echo 'export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --disable-decoder=hevc --disable-parser=hevc --disable-demuxer=hevc"' >> config/module.sh
fi

# 0005 patches this repository rather than the external IJK checkout. Check the
# exact behavior because Windows worktree line endings make reverse git-apply
# checks unreliable from WSL.
repository_player="$SCRIPT_DIR/../android-shared/src/main/java/sagex/miniclient/android/video/ijkplayer/IJKMediaPlayerImpl.java"
if ! grep -q 'Flush in push mode: seekTo(0) to flush IJK internal queues' "$repository_player" ||
   ! sed -n '/if (player != null && pushMode)/,/else if (player != null)/p' "$repository_player" |
       grep -q 'seekToImpl(0);'; then
    echo "Repository-side push queue flush behavior from 0005 is missing." >&2
    exit 1
fi

cd "$IJKPLAYER_ROOT/android/contrib"
./compile-ffmpeg.sh clean

build_ffmpeg()
{
    local abi="$1"
    local log_file="$IJKPLAYER_ROOT/android/contrib/build/ffmpeg-$abi-link.log"
    mkdir -p "$(dirname "$log_file")"
    bash -x ./compile-ffmpeg.sh "$abi" 2>&1 | tee "$log_file"

    local config_mak="$IJKPLAYER_ROOT/android/contrib/ffmpeg-$abi/ffbuild/config.mak"
    grep -q -- "-z,max-page-size=16384" "$config_mak"
    grep -q -- "-z,common-page-size=16384" "$config_mak"
    grep -q -- "-z,relro" "$config_mak"
    grep -q -- "-z,now" "$config_mak"
    for flag in \
        "-Wl,-Bsymbolic" \
        "-Wl,-z,relro" \
        "-Wl,-z,now" \
        "-Wl,-z,max-page-size=16384" \
        "-Wl,-z,common-page-size=16384"
    do
        grep "libijkffmpeg.so" "$log_file" | grep -q -- "$flag"
    done
}

build_ffmpeg armv7a
build_ffmpeg arm64

cd "$IJKPLAYER_ROOT/android"

build_ijk()
{
    local abi="$1"
    local log_file="$IJKPLAYER_ROOT/android/build/ijk-$abi-link.log"
    mkdir -p "$(dirname "$log_file")"
    V=1 ./compile-ijk.sh "$abi" rebuild 2>&1 | tee "$log_file"
    for library in libijkplayer.so libijksdl.so; do
        for flag in \
            "-Wl,-z,relro" \
            "-Wl,-z,now" \
            "-Wl,-z,max-page-size=16384" \
            "-Wl,-z,common-page-size=16384"
        do
            grep "$library" "$log_file" | grep -q -- "$flag"
        done
    done
}

build_ijk armv7a
build_ijk arm64

echo "Run package-native-aars.py to update the checked-in ABI AARs."
