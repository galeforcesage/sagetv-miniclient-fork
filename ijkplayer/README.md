# Rebuilding IJKPlayer

The checked-in IJK AARs use Bilibili IJKPlayer `k0.8.8`, official FFmpeg
`n6.1.1`, and Android NDK r28c (`28.2.13676358`). Both source repositories
are pinned by immutable Git object IDs. FFmpeg 6.1.1's guarded
`ff_seek_frame_binary()` replaces the old FFmpeg 4 null-index patch.

Build on Linux or WSL using Linux-local source and output storage:

```bash
export ANDROID_SDK="$HOME/android-sdk"
export ANDROID_NDK="$HOME/android-ndk-r28c"
export IJKPLAYER_SOURCE_DIR="$HOME/sagetv-native-build/ijkplayer"
./build-ijk.sh
```

`build-ijk.sh` pins both upstream sources, applies the audited IJK protocol
hooks to official FFmpeg, ports IJK directly to FFmpeg 6 APIs, and applies
16 KB maximum/common page-size linker settings to FFmpeg, IJK, and SDL.
Deprecated packet, channel-layout, frame-position, FIFO, and stream-side-data
interfaces are disabled before FFmpeg is compiled and rejected during the IJK
build. This ordering keeps FFmpeg and IJK on the same public-structure ABI; the
generated headers must never be rewritten only after `libijkffmpeg.so` is
built. Stack `AVPacket` instances passed to FFmpeg 6 APIs that unref their input
must also be zero-initialized before first use. The Sage PUSH/PULL
`IMediaDataSource`, push seek/flush behavior, and existing IJK MediaCodec
pipeline are retained.

Replace only the native entries in the repository AARs:

```bash
python3 package-native-aars.py \
  --ijk-root "$IJKPLAYER_SOURCE_DIR" \
  --libs-dir ../libs \
  --readelf "$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" \
  --android-jar "$ANDROID_SDK/platforms/android-34/android.jar"
```

The packaging command fails unless every native library in both AARs passes
the repository ELF verifier. To rerun verification without repackaging:

```bash
python3 ../tools/verify-native-alignment.py \
  --readelf "$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" \
  ../libs/ijkplayer-arm64-0.8.8-SNAPSHOT.aar \
  ../libs/ijkplayer-armv7a-0.8.8-SNAPSHOT.aar
```

The native build now contains HEVC decoder, parser, demuxer, and MediaCodec
components. Do not advertise IJK HEVC support until MPEG-TS, Matroska, and
MP4 MediaCodec runtime validation is complete.

## Final application verification

Build both release flavors as APKs and AABs:

```bash
./gradlew \
  :android-tv:assembleOnlineRelease \
  :android-tv:assembleMobileRelease \
  :android-tv:bundleOnlineRelease \
  :android-tv:bundleMobileRelease
```

Production release builds require the repository's external signing
configuration. A disposable verification key may be used to inspect local
artifacts, but it does not prove production signing.

Verify both APK packaging and every native library in all four artifacts:

```bash
"$ANDROID_SDK/build-tools/35.0.0/zipalign" -v -c -P 16 4 \
  ../android-tv/build/outputs/apk/online/release/android-tv-online-release.apk
"$ANDROID_SDK/build-tools/35.0.0/zipalign" -v -c -P 16 4 \
  ../android-tv/build/outputs/apk/mobile/release/android-tv-mobile-release.apk

python3 ../tools/verify-native-alignment.py \
  --readelf "$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" \
  ../android-tv/build/outputs/apk/online/release/android-tv-online-release.apk \
  ../android-tv/build/outputs/apk/mobile/release/android-tv-mobile-release.apk \
  ../android-tv/build/outputs/bundle/onlineRelease/android-tv-online-release.aab \
  ../android-tv/build/outputs/bundle/mobileRelease/android-tv-mobile-release.aab

java -jar bundletool-all.jar dump config \
  --bundle=../android-tv/build/outputs/bundle/onlineRelease/android-tv-online-release.aab
java -jar bundletool-all.jar dump config \
  --bundle=../android-tv/build/outputs/bundle/mobileRelease/android-tv-mobile-release.aab
```

The bundle configuration must report `PAGE_ALIGNMENT_16K`. Runtime completion
still requires a 16 KB page-size emulator/device smoke test and IJK MediaCodec
HEVC playback tests for MPEG-TS, Matroska, and MP4.
