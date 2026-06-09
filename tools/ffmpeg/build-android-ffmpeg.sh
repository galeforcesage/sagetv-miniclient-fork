#!/usr/bin/env bash
set -euo pipefail

# Builds FFmpeg for Android with explicit profiles so offline remux work
# never silently drops capabilities needed by the in-app IJK player stack.
#
# Usage:
#   ./tools/ffmpeg/build-android-ffmpeg.sh --ffmpeg-src /path/to/ffmpeg --profile offline-remux
#   ./tools/ffmpeg/build-android-ffmpeg.sh --ffmpeg-src /path/to/ffmpeg --profile ijk-superset
#   ./tools/ffmpeg/build-android-ffmpeg.sh --ffmpeg-src /path/to/ffmpeg --profile exo-ijk-superset

PROFILE="offline-remux"
FFMPEG_SRC=""
ABIS="arm64-v8a armeabi-v7a"
API_LEVEL=21

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile)
      PROFILE="$2"
      shift 2
      ;;
    --ffmpeg-src)
      FFMPEG_SRC="$2"
      shift 2
      ;;
    --abis)
      ABIS="$2"
      shift 2
      ;;
    --api-level)
      API_LEVEL="$2"
      shift 2
      ;;
    *)
      echo "Unknown arg: $1" >&2
      exit 1
      ;;
  esac
done

if [[ -z "${ANDROID_NDK:-}" ]]; then
  echo "ANDROID_NDK is required" >&2
  exit 1
fi
if [[ -z "$FFMPEG_SRC" || ! -d "$FFMPEG_SRC" ]]; then
  echo "--ffmpeg-src must point to an FFmpeg source tree" >&2
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
OUT_ROOT="$ROOT_DIR/build/ffmpeg-android/$PROFILE"
mkdir -p "$OUT_ROOT"

HOST_TAG=""
case "$(uname -s)" in
  Darwin)
    for candidate in darwin-x86_64 darwin-arm64; do
      if [[ -d "$ANDROID_NDK/toolchains/llvm/prebuilt/$candidate" ]]; then
        HOST_TAG="$candidate"
        break
      fi
    done
    ;;
  Linux)
    for candidate in linux-x86_64 windows-x86_64; do
      if [[ -d "$ANDROID_NDK/toolchains/llvm/prebuilt/$candidate" ]]; then
        HOST_TAG="$candidate"
        break
      fi
    done
    ;;
  *) echo "Unsupported host OS" >&2; exit 1 ;;
esac

if [[ -z "$HOST_TAG" ]]; then
  echo "No compatible NDK prebuilt host toolchain found under $ANDROID_NDK/toolchains/llvm/prebuilt" >&2
  exit 1
fi

TOOLCHAIN="$ANDROID_NDK/toolchains/llvm/prebuilt/$HOST_TAG"

common_flags=(
  --target-os=android
  --enable-cross-compile
  --disable-doc
  --disable-debug
  --enable-small
  --enable-static
  --disable-shared
  --disable-ffplay
  --disable-ffprobe
)

# Minimal deterministic remux profile for TS/PS -> MP4 fallback.
offline_remux_flags=(
  --disable-everything
  --enable-ffmpeg
  --enable-protocol=file
  --enable-protocol=pipe
  --enable-demuxer=mpegts
  --enable-demuxer=mpegps
  --enable-demuxer=h264
  --enable-demuxer=hevc
  --enable-parser=aac
  --enable-parser=ac3
  --enable-parser=h264
  --enable-parser=hevc
  --enable-parser=mpegaudio
  --enable-parser=mpegvideo
  --enable-muxer=mp4
  --enable-muxer=mov
  --enable-muxer=matroska
  --enable-bsf=aac_adtstoasc
)

# Superset profile intended when one FFmpeg build is expected to satisfy
# both offline remux and broader software decode/transcode requirements.
ijk_superset_flags=(
  --disable-everything
  --enable-ffmpeg
  --enable-protocol=file
  --enable-protocol=pipe
  --enable-demuxer=mpegts
  --enable-demuxer=mpegps
  --enable-demuxer=matroska
  --enable-demuxer=mov
  --enable-parser=aac
  --enable-parser=ac3
  --enable-parser=h264
  --enable-parser=hevc
  --enable-parser=mpegaudio
  --enable-parser=mpegvideo
  --enable-muxer=mp4
  --enable-muxer=mov
  --enable-muxer=matroska
  --enable-bsf=aac_adtstoasc
  --enable-decoder=aac
  --enable-decoder=ac3
  --enable-decoder=h264
  --enable-decoder=hevc
)

# Broader playback-oriented profile covering common Exo FFmpeg extension
# software-audio use-cases plus IJK needs.
exo_ijk_superset_flags=(
  --disable-everything
  --enable-ffmpeg
  --enable-protocol=file
  --enable-protocol=pipe
  --enable-demuxer=mpegts
  --enable-demuxer=mpegps
  --enable-demuxer=matroska
  --enable-demuxer=mov
  --enable-parser=aac
  --enable-parser=ac3
  --enable-parser=h264
  --enable-parser=hevc
  --enable-parser=mpegaudio
  --enable-parser=mpegvideo
  --enable-muxer=mp4
  --enable-muxer=mov
  --enable-muxer=matroska
  --enable-bsf=aac_adtstoasc
  --enable-decoder=aac
  --enable-decoder=ac3
  --enable-decoder=eac3
  --enable-decoder=dca
  --enable-decoder=truehd
  --enable-decoder=mp2
  --enable-decoder=mp3
  --enable-decoder=vorbis
  --enable-decoder=opus
  --enable-decoder=flac
  --enable-decoder=h264
  --enable-decoder=hevc
)

profile_flags=()
case "$PROFILE" in
  offline-remux) profile_flags=("${offline_remux_flags[@]}") ;;
  ijk-superset) profile_flags=("${ijk_superset_flags[@]}") ;;
  exo-ijk-superset) profile_flags=("${exo_ijk_superset_flags[@]}") ;;
  *) echo "Unsupported profile: $PROFILE" >&2; exit 1 ;;
esac

abi_to_target() {
  case "$1" in
    arm64-v8a) echo "aarch64-linux-android aarch64 armv8-a" ;;
    armeabi-v7a) echo "armv7a-linux-androideabi arm armv7-a" ;;
    x86) echo "i686-linux-android x86 i686" ;;
    x86_64) echo "x86_64-linux-android x86_64 x86_64" ;;
    *) echo "unsupported" ;;
  esac
}

for abi in $ABIS; do
  read -r triple ff_arch ff_cpu < <(abi_to_target "$abi")
  if [[ "$triple" == "unsupported" ]]; then
    echo "Unsupported ABI: $abi" >&2
    exit 1
  fi

  PREFIX="$OUT_ROOT/$abi/prefix"
  BUILD_DIR="$OUT_ROOT/$abi/build"
  mkdir -p "$BUILD_DIR"
  rm -rf "$BUILD_DIR"/*

  pushd "$BUILD_DIR" >/dev/null

  CC="$TOOLCHAIN/bin/${triple}${API_LEVEL}-clang"
  CXX="$TOOLCHAIN/bin/${triple}${API_LEVEL}-clang++"
  AR="$TOOLCHAIN/bin/llvm-ar"
  NM="$TOOLCHAIN/bin/llvm-nm"
  STRIP="$TOOLCHAIN/bin/llvm-strip"

  "$FFMPEG_SRC/configure" \
    --prefix="$PREFIX" \
    --arch="$ff_arch" \
    --cpu="$ff_cpu" \
    --cc="$CC" \
    --cxx="$CXX" \
    --ar="$AR" \
    --nm="$NM" \
    --strip="$STRIP" \
    --extra-cflags="-fPIC" \
    --extra-ldflags="-Wl,-z,max-page-size=16384" \
    "${common_flags[@]}" \
    "${profile_flags[@]}"

  make -j"$(getconf _NPROCESSORS_ONLN)"
  make install
  popd >/dev/null

done

echo "FFmpeg build complete: $OUT_ROOT"
