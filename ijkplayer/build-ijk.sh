#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="${SAGETV_IJK_SCRIPT_DIR:-$(cd "$(dirname "$0")" && pwd)}"
PATCH_DIR="$SCRIPT_DIR/patches/ijk"
FFMPEG_PATCH_DIR="$SCRIPT_DIR/patches/ffmpeg"
IJKPLAYER_COMMIT="cced91e3ae3730f5c63f3605b00d25eafcf5b97b"
FFMPEG_TAG_OBJECT="6f4048827982a8f48f71f551a6e1ed2362816eec"
FFMPEG_COMMIT="e38092ef9395d7049f871ef4d5411eb410e283e0"
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
./init-config.sh
./init-android-libyuv.sh
./init-android-soundtouch.sh

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
    shift 2
    local normalized_patch
    normalized_patch="$(mktemp)"
    normalize_patch "$patch_file" "$normalized_patch"
    if git -C "$source_root" apply "$@" --check "$normalized_patch"; then
        git -C "$source_root" apply "$@" "$normalized_patch"
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
    android/ijkplayer/ijkplayer-java/src/main/java/tv/danmaku/ijk/media/player/IjkMediaPlayer.java \
    android/ijkplayer/ijkplayer-arm64/src/main/jni/Application.mk \
    android/ijkplayer/ijkplayer-armv7a/src/main/jni/Application.mk \
    ijkmedia/ijkj4a/Android.mk \
    ijkmedia/ijkplayer/android/pipeline/ffpipeline_android.c \
    ijkmedia/ijkplayer/android/pipeline/ffpipenode_android_mediacodec_vdec.c \
    ijkmedia/ijkplayer/Android.mk \
    ijkmedia/ijkplayer/ff_cmdutils.c \
    ijkmedia/ijkplayer/ff_ffmsg.h \
    ijkmedia/ijkplayer/ff_ffplay.c \
    ijkmedia/ijkplayer/ff_ffplay.h \
    ijkmedia/ijkplayer/ff_ffplay_def.h \
    ijkmedia/ijkplayer/ijkavformat/allformats.c \
    ijkmedia/ijkplayer/ijkavformat/ijkasync.c \
    ijkmedia/ijkplayer/ijkavformat/ijkiocache.c \
    ijkmedia/ijkplayer/ijkavformat/ijkiourlhook.c \
    ijkmedia/ijkplayer/ijkavutil/ijkdict.c \
    ijkmedia/ijkplayer/ijkavutil/ijkdict.h \
    ijkmedia/ijkplayer/ijkmeta.c \
    ijkmedia/ijkplayer/ijkmeta.h \
    ijkmedia/ijksdl/Android.mk \
    ijkmedia/ijksdl/ijksdl_egl.c
do
    git -C "$IJKPLAYER_ROOT" show "$IJKPLAYER_COMMIT:$source_file" > "$IJKPLAYER_ROOT/$source_file"
done

apply_named_patch "$IJKPLAYER_ROOT" "$PATCH_DIR/0001-fix-c99-flag-placement.patch"
apply_named_patch "$IJKPLAYER_ROOT" \
    "$PATCH_DIR/0003-add-ndk-r28-compatibility.patch" \
    --exclude=android/contrib/ffmpeg-arm64/libavcodec/aarch64/h264dsp_init_aarch64.c
apply_named_patch "$IJKPLAYER_ROOT" "$PATCH_DIR/0006-support-16k-page-alignment.patch"
apply_named_patch "$IJKPLAYER_ROOT" "$PATCH_DIR/0007-port-ffmpeg-6.1.1-apis.patch"

cd "$IJKPLAYER_ROOT/android/contrib"
./compile-ffmpeg.sh clean
cd "$IJKPLAYER_ROOT"

prepare_ffmpeg()
{
    local abi="$1"
    local ffmpeg_root="$IJKPLAYER_ROOT/android/contrib/ffmpeg-$abi"
    if [ ! -d "$ffmpeg_root/.git" ]; then
        git clone https://github.com/FFmpeg/FFmpeg.git "$ffmpeg_root"
    fi
    git -C "$ffmpeg_root" fetch --force origin \
        "refs/tags/n6.1.1:refs/tags/n6.1.1"
    test "$(git -C "$ffmpeg_root" rev-parse refs/tags/n6.1.1)" = "$FFMPEG_TAG_OBJECT"
    test "$(git -C "$ffmpeg_root" rev-parse refs/tags/n6.1.1^{})" = "$FFMPEG_COMMIT"
    git -C "$ffmpeg_root" checkout --detach "$FFMPEG_COMMIT"
    git -C "$ffmpeg_root" reset --hard "$FFMPEG_COMMIT"
    apply_named_patch "$ffmpeg_root" \
        "$FFMPEG_PATCH_DIR/0001-add-ijk-application-protocol-hooks.patch"
    grep -q 'if (sti->index_entries)' "$ffmpeg_root/libavformat/seek.c"
}

for abi in armv7a arm64; do
    prepare_ffmpeg "$abi"
done

# Keep the historical Sage codec profile separate from source compatibility patches.
sed -i '/--disable-ffserver/d; /--disable-vda/d; /--disable-avresample/d' config/module.sh
cat >> config/module.sh <<'EOF'
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-decoder=hevc --enable-parser=hevc --enable-demuxer=hevc"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --enable-jni --enable-mediacodec --enable-decoder=h264_mediacodec --enable-decoder=hevc_mediacodec"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --disable-vulkan --disable-x86asm"
EOF

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

    local ffmpeg_root="$IJKPLAYER_ROOT/android/contrib/ffmpeg-$abi"
    local include_root="$IJKPLAYER_ROOT/android/contrib/build/ffmpeg-$abi/output/include"
    for header_root in "$ffmpeg_root" "$include_root"; do
        sed -i -E \
            's/^#define FF_API_INIT_PACKET.*/#define FF_API_INIT_PACKET 0/' \
            "$header_root/libavcodec/version_major.h"
        sed -i -E \
            's/^#define FF_API_OLD_CHANNEL_LAYOUT.*/#define FF_API_OLD_CHANNEL_LAYOUT 0/' \
            "$header_root/libavutil/version.h"
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
    if grep -q 'error:' "$log_file"; then
        echo "Native compilation emitted errors for $abi." >&2
        exit 1
    fi
    if grep -q 'is deprecated' "$log_file"; then
        echo "Deprecated FFmpeg API use remains for $abi." >&2
        exit 1
    fi
    for library in libijkffmpeg.so libijkplayer.so libijksdl.so; do
        test -s "$IJKPLAYER_ROOT/android/ijkplayer/ijkplayer-$abi/src/main/libs/$(
            [ "$abi" = armv7a ] && echo armeabi-v7a || echo arm64-v8a
        )/$library"
    done
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

if grep -R -n -E \
    'av_init_packet|av_stream_get_side_data|frame->pkt_pos|frame->channel_layout|avctx->channel_layout|codecpar->channel_layout|frame->channels|avctx->channels|codecpar->channels' \
    "$IJKPLAYER_ROOT/ijkmedia/ijkplayer" \
    "$IJKPLAYER_ROOT/ijkmedia/ijksdl"; then
    echo "Legacy FFmpeg APIs remain in the IJK source." >&2
    exit 1
fi
if grep -q 'ijklivehook.c' "$IJKPLAYER_ROOT/ijkmedia/ijkplayer/Android.mk" ||
   grep -q 'ijkff_ijklivehook_demuxer' \
       "$IJKPLAYER_ROOT/ijkmedia/ijkplayer/ijkavformat/allformats.c"; then
    echo "The private livehook demuxer must not be compiled against FFmpeg 6." >&2
    exit 1
fi

echo "Run package-native-aars.py to update the checked-in ABI AARs."
