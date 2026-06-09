# FFmpeg Build Profiles and Player Impact

This repository has two separate FFmpeg consumers:

1. IJKPlayer runtime libraries (bundled in IJK AARs)
2. Offline remux fallback path (planned command/binary flow)
3. ExoPlayer FFmpeg extension AAR for software audio fallback

They are related technology-wise, but not automatically the same binary.

## Does IJK depend on offline remux FFmpeg

No, not directly.

IJK currently ships prebuilt AAR artifacts and links its own native stack. See:

- [android-shared/build.gradle](android-shared/build.gradle)
- [ijkplayer/build-ijk.sh](ijkplayer/build-ijk.sh)
- [ijkplayer/ijkplayer/config/module-default.sh](ijkplayer/ijkplayer/config/module-default.sh)

So adding an offline remux FFmpeg binary does not change IJK behavior unless we intentionally unify them.

## Does ExoPlayer use FFmpeg in this repo

Yes, optionally.

Exo core does not require FFmpeg, but this app includes `extension-ffmpeg` and can enable it at runtime.

- [android-shared/build.gradle](android-shared/build.gradle)
- [android-shared/src/main/java/sagex/miniclient/android/video/exoplayer2/Exo2MediaPlayerImpl.java](android-shared/src/main/java/sagex/miniclient/android/video/exoplayer2/Exo2MediaPlayerImpl.java)
- [android-shared/src/main/java/sagex/miniclient/android/ui/settings/ExoPlayerSettingsFragment.java](android-shared/src/main/java/sagex/miniclient/android/ui/settings/ExoPlayerSettingsFragment.java)

That means FFmpeg capability planning should account for Exo extension fallback codecs in addition to IJK.

## Why profiles are required

Offline remux fallback can be tiny and deterministic.

IJK playback may need a wider codec set and should keep its own tested configuration.

Using one under-scoped FFmpeg build for both is risky because playback regressions can be subtle.

## Build script

Use:

- [tools/ffmpeg/build-android-ffmpeg.sh](tools/ffmpeg/build-android-ffmpeg.sh)

Supported profiles:

1. offline-remux
2. ijk-superset
3. exo-ijk-superset

The `offline-remux` profile is intentionally minimal for TS/PS to MP4/MKV remux:

- demuxers: `mpegts`, `mpegps`
- muxers: `mp4`, `mov`, `matroska`
- parser: `aac`, `ac3`, `h264`, `hevc`, `mpegaudio`, `mpegvideo`
- bsf: `aac_adtstoasc`

The build script is configured to produce an executable `ffmpeg` binary (static, no shared-lib runtime dependency) for each ABI.

The `ijk-superset` profile adds broader decode support (`aac`, `ac3`, `h264`, `hevc`) to reduce mismatch risk if you choose to converge binaries later.

The `exo-ijk-superset` profile further includes common software-audio decoders used in Exo extension deployments (`eac3`, `dca`, `truehd`, `mp2`, `mp3`, `vorbis`, `opus`, `flac`).

## Recommended operating model

1. Keep IJK build pipeline unchanged for now.
2. Build and validate offline-remux FFmpeg separately.
3. If and only if stable, evaluate convergence to one shared FFmpeg artifact with explicit capability tests.

## Bundle into app assets

After building, copy binaries into Android assets expected by runtime fallback:

1. `./tools/ffmpeg/install-bundled-ffmpeg.sh --profile offline-remux`
2. Verify files exist:
	- `android-offline/src/main/assets/ffmpeg/arm64-v8a/ffmpeg`
	- `android-offline/src/main/assets/ffmpeg/armeabi-v7a/ffmpeg`

## Validation checklist before any convergence

1. Offline remux: TS -> MP4 completes with audio on problematic format-change samples.
2. Exo playback: seek + audio track selection still works on generated MP4.
3. IJK playback: live TV, AC3 streams, HEVC recordings, subtitle scenarios.
4. APK size and startup impact remain acceptable.
