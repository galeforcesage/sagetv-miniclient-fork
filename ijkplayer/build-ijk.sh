#!/usr/bin/env bash

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

if [ "$ANDROID_SDK" = "" ] ; then
    echo "Set ANDROID_SDK to be the location of your Sdk, USING DEFAULT"
    export ANDROID_SDK=/home/seans/Android/Sdk/

fi

if [ "$ANDROID_NDK" = "" ] ; then
    if [ -d Ndk/android-ndk-r25c ] ; then
        export ANDROID_NDK=`pwd`/Ndk/android-ndk-r25c
    elif [ -d Ndk/android-ndk-r13b ] ; then
        export ANDROID_NDK=`pwd`/Ndk/android-ndk-r13b
    else
        echo "run init-sources to init the Ndk"
        exit 1
    fi
fi

if [ ! -d ijkplayer/.git ] ; then
    echo "Fetching IJKPlayer Sources"
    git clone https://github.com/Bilibili/ijkplayer.git ijkplayer
fi

export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --disable-linux-perf"
export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --extra-ldflags=-Wl,-z,max-page-size=16384"

for app_mk in \
    ijkplayer/android/ijkplayer/ijkplayer-armv7a/src/main/jni/Application.mk \
    ijkplayer/android/ijkplayer/ijkplayer-arm64/src/main/jni/Application.mk
do
    if [ -f "$app_mk" ]; then
        sed -i 's/^APP_STL := .*/APP_STL := c++_static/' "$app_mk"
        sed -i 's/^APP_STL = .*/APP_STL = c++_static/' "$app_mk"
        case "$app_mk" in
            *armv7a*)
                sed -i 's/^APP_PLATFORM := android-.*/APP_PLATFORM := android-21/' "$app_mk"
                ;;
            *arm64*)
                sed -i 's/^APP_PLATFORM := android-.*/APP_PLATFORM := android-21/' "$app_mk"
                ;;
        esac
    fi
done

for android_mk in \
    ijkplayer/ijkmedia/ijkplayer/Android.mk \
    ijkplayer/ijkmedia/ijksdl/Android.mk
do
    if [ -f "$android_mk" ] && ! grep -q 'max-page-size=16384' "$android_mk"; then
        sed -i '/LOCAL_LDLIBS += /a LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384' "$android_mk"
    fi
done

cd ijkplayer
cd config
ln -sf module-default.sh module.sh
cd ..
./init-android.sh

# Re-apply module flag fixes after init scripts to survive upstream script resets.
sed -i '/--disable-ffserver/d; /--disable-vda/d' config/module.sh
if ! grep -q -- '--disable-decoder=hevc' config/module.sh; then
    echo 'export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --disable-decoder=hevc --disable-parser=hevc --disable-demuxer=hevc"' >> config/module.sh
fi

# Keep C99 only on C compilation units; ndk-build forwards LOCAL_CFLAGS to C++ too.
for mk in \
    android/ijkplayer/ijkplayer-armv7a/src/main/jni/ijkmedia/*/Android.mk \
    android/ijkplayer/ijkplayer-arm64/src/main/jni/ijkmedia/*/Android.mk
do
    [ -f "$mk" ] || continue
    sed -i 's/^LOCAL_CFLAGS += -std=c99$/LOCAL_CONLYFLAGS += -std=c99/' "$mk"
done

# Work around clang + Linux termbits macro collision in ffmpeg opus_pvq.c (B0).
for ff_dir in android/contrib/ffmpeg-armv7a android/contrib/ffmpeg-arm64; do
    if [ -f "$ff_dir/libavcodec/opus_pvq.c" ]; then
        sed -i 's/int B0 = blocks;/int blocks0 = blocks;/g' "$ff_dir/libavcodec/opus_pvq.c"
        sed -i 's/(B0 == 1)/(blocks0 == 1)/g' "$ff_dir/libavcodec/opus_pvq.c"
        sed -i 's/|| B0 > 1/|| blocks0 > 1/g' "$ff_dir/libavcodec/opus_pvq.c"
        sed -i 's/B0 = blocks;/blocks0 = blocks;/g' "$ff_dir/libavcodec/opus_pvq.c"
        sed -i 's/if (B0 > 1/if (blocks0 > 1/g' "$ff_dir/libavcodec/opus_pvq.c"
        sed -i 's/B0 << recombine/blocks0 << recombine/g' "$ff_dir/libavcodec/opus_pvq.c"
    fi
done

# Fix SIGSEGV in ff_seek_frame_binary: null-check index_entries before access.
# Older ffmpeg (used by ijkplayer) crashes when MPEG-PS demuxer calls
# ff_seek_frame_binary on streams with no index (push/pipe sources).
# Modern ffmpeg already guards this with `if (sti->index_entries)`.
for ff_dir in android/contrib/ffmpeg-armv7a android/contrib/ffmpeg-arm64; do
    utils="$ff_dir/libavformat/utils.c"
    if [ -f "$utils" ] && grep -q 'ff_seek_frame_binary' "$utils"; then
        # Guard: early-return when index_entries is NULL or empty.
        # Insert right after the local variable declarations inside ff_seek_frame_binary.
        if ! grep -q 'index_entries.*null-check patch' "$utils"; then
            sed -i '/^int ff_seek_frame_binary/,/pos_limit = -1/{
                /pos_limit = -1/a\
\
    /* null-check patch: prevent SIGSEGV on streams with no index (e.g. piped MPEG-PS) */\
    if (stream_index >= 0 && stream_index < (int)s->nb_streams) {\
        AVStream *_st = s->streams[stream_index];\
        if (!_st->index_entries || _st->nb_index_entries <= 0) {\
            av_log(s, AV_LOG_WARNING, "ff_seek_frame_binary: no index entries for stream %d, cannot seek\\n", stream_index);\
            return -1;\
        }\
    } /* end null-check patch */
            }' "$utils"
            echo "Patched ff_seek_frame_binary null-check in $utils"
        fi
    fi
done

cd android/contrib/
./compile-ffmpeg.sh clean
./compile-ffmpeg.sh armv7a
./compile-ffmpeg.sh arm64
cd ..
./compile-ijk.sh armv7a rebuild
./compile-ijk.sh arm64 rebuild
cd ..

echo "run copy-resources to update the project's artifacts with the new player"
