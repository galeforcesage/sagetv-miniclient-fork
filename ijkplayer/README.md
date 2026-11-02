# Rebuilding IJKPlayer

The checked-in IJK AARs use Bilibili IJKPlayer `k0.8.8` and Android NDK
r28c (`28.2.13676358`). The build retains SageTV's null-index guard in
FFmpeg's `ff_seek_frame_binary()` for push/pipe MPEG-PS playback.

Build on Linux or WSL using Linux-local source and output storage:

```bash
export ANDROID_SDK="$HOME/android-sdk"
export ANDROID_NDK="$HOME/android-ndk-r28c"
export IJKPLAYER_SOURCE_DIR="$HOME/sagetv-native-build/ijkplayer"
./build-ijk.sh
```

`build-ijk.sh` pins the upstream tag, adapts IJK's legacy FFmpeg scripts to
the NDK r28 LLVM toolchain, and applies both 16 KB maximum/common page-size
linker settings to FFmpeg, IJK, and SDL. ARMv7 FFmpeg assembly is disabled
because the FFmpeg 4.0-era source is not accepted by Clang 19; ARM64 NEON
remains enabled. The Sage push seek/flush and unindexed MPEG-PS seek guard are
retained.

Replace only the native entries in the repository AARs:

```bash
python3 package-native-aars.py \
  --ijk-root "$IJKPLAYER_SOURCE_DIR" \
  --libs-dir ../libs \
  --readelf "$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
```

The packaging command fails unless every native library in both AARs passes
the repository ELF verifier. To rerun verification without repackaging:

```bash
python3 ../tools/verify-native-alignment.py \
  --readelf "$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" \
  ../libs/ijkplayer-arm64-0.8.8-SNAPSHOT.aar \
  ../libs/ijkplayer-armv7a-0.8.8-SNAPSHOT.aar
```

IJK HEVC decoder, parser, and demuxer support remains disabled in this build
profile. Do not advertise IJK HEVC support until the separate MediaCodec
parser/demux and container validation work is complete.
