# IJKPlayer native patches (SageTV-NG)

These patches are applied by `../build-ijk.sh` on top of the pristine upstream
[Bilibili/ijkplayer](https://github.com/Bilibili/ijkplayer) checkout, immediately
after it is cloned. They are format-patch style diffs whose paths are relative to
the ijkplayer clone root, so they apply with:

```sh
git -C ijkplayer apply patches/0001-ijk-audiotrack-setpreferreddevice.patch
```

`build-ijk.sh` applies every `*.patch` here idempotently (it skips any patch that
is already applied), so no manual step is required for a normal rebuild.

## 0001-ijk-audiotrack-setpreferreddevice.patch

Adds an IJK equivalent of ExoPlayer's `setPreferredAudioDevice` so IJK audio can
follow video onto an external HDMI/DeX display.

IJK renders audio through a Java `AudioTrack` created deep inside the SDL audio
output. Android will not migrate an already-playing `AudioTrack` to a route that
is selected afterward, and IJK exposes no handle to that track, so on a phone
(whose default route is the speaker) IJK audio stays on the phone when an HDMI
display is attached mid-playback. On devices where HDMI is the default route
(e.g. the Shield) the track is simply born on HDMI and "just works".

The patch:

- `android/.../IjkMediaPlayer.java` — adds a public
  `setPreferredAudioDevice(AudioDeviceInfo)` (stores a process-wide preference)
  and a `@CalledByNative applyPreferredAudioDevice(AudioTrack)` that calls
  `AudioTrack.setPreferredDevice(...)` (API 23+).
- `ijkmedia/ijkj4a/.../IjkMediaPlayer.{c,h}` — J4A bindings for the new static
  `applyPreferredAudioDevice` method (mirrors the existing `postEventFromNative`
  binding).
- `ijkmedia/ijksdl/android/android_audiotrack.c` — after the internal
  `AudioTrack` is created, invokes `applyPreferredAudioDevice` so the fresh track
  is pinned to the preferred device.

The app pins the device via reflection (see `OfflinePlaybackActivity`), so it
still runs against an unpatched ijkplayer AAR, where these methods are absent and
the call is a no-op (the app-side "recreate on the HDMI route" path is the
fallback).
