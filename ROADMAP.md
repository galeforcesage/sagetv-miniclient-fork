# Roadmap

Planned / candidate work for the Android MiniClient.

## Media stack

### Migrate from ExoPlayer 2.x to AndroidX Media3

**Why:**
- ExoPlayer 2.x is in maintenance-only mode; active development moved to AndroidX
  Media3 (same codebase, repackaged under `androidx.media3.*`).
- **AC-4 audio** decoding requires Media3 1.2+ (late 2023). It is not available
  in any 2.x release. Now that SageTV can record ATSC 3.0 streams with HEVC and
  AC-4, this is the blocker for playing those recordings on the client.
- Media3 continues to receive HEVC quirks, HDR metadata, and ATSC 3.0–related
  fixes that 2.x will not.

**Scope:**
- Package rename across all imports: `com.google.android.exoplayer2.*` →
  `androidx.media3.*`.
- Re-fork our custom files against Media3's current versions:
  - `SagePsExtractor` (forked from `PsExtractor`, includes
    `PrivateStream1Demuxer` and `LinearPsSeekMap`).
  - `Exo2PullDataSource`, `Exo2MediaPlayerImpl`, trickplay controller, etc.
- Replace the FFmpeg extension AAR (currently 2.x in `libs/`) with the Media3
  equivalent, or rebuild from source.
- Validate stable paths (push mode, completed-recording playback, seeking,
  comskip, live recording).

**Suggested approach:**
- Fresh branch off `java-upgrade`.
- Keep both AARs side-by-side during transition.
- Migrate one module at a time (`core` is largely unaffected; the bulk lives
  in `android-shared`).
- Schedule after the current live-playback fixes have baked for a couple of
  weeks so any regressions are easier to isolate.

## Settings UX

### Phase 2 — Automatic-First Settings UX

Goal: most users never open settings. Each capability defaults to **Automatic**;
the user can opt out per-setting when they want a hard override.

Done so far:
- `TriState` enum + `PrefStore.getTriState()` / `setTriState()` API
  (`core/src/main/java/sagex/miniclient/prefs/TriState.java`). Persisted as
  `"auto"` / `"on"` / `"off"`. Cycle order: AUTO → ON → OFF → AUTO.
- Streaming Mode: XML default flipped to `automatic`, label rendered as
  "Automatic ᵃ", summary rewritten to describe Automatic instead of "Fixed
  is preferred".
- "Fixed Transcoding Settings" → **"Transcoding Settings"**.
- "Fixed Remuxing Settings" → **"Remuxing Settings"**.
- Transcoding/Remuxing submenus enabled in any non-Pull mode (was previously
  gated to `fixed` only — they apply whenever the server actually transcodes
  or remuxes, which can happen in Automatic / Dynamic / Fixed).

To do:
- `TriStatePreference` custom widget — checkbox-with-superscript control
  (`ᵃ` for auto-derived value, `ᵒ` for user override). Tap cycles AUTO →
  ON → OFF → AUTO; long-press resets to AUTO.
- Convert ExoPlayer FFmpeg Extension setting to tri-state.
- Codec & Container Settings submenu rebuild — items generated dynamically
  from the union of `MediaCodecList` (REGULAR_CODECS), IJK FFmpeg software
  decoders, and ExoPlayer FFmpeg extension decoders. Each row is a
  `TriStatePreference`. Replaces the largely-commented-out current XML.
- Audio Passthrough sub-section (AC3 / EAC3 / DTS / TrueHD / Opus),
  tri-state. Auto = result of `AudioCapabilities.supportsEncoding()`.
- Wire `MiniClientConnection` capability emission (`PUSH_AV_CONTAINERS`,
  `PULL_AV_CONTAINERS`, `VIDEO_CODECS`, `AUDIO_CODECS`,
  `AUDIO_PASSTHROUGH`) to consult tri-state values:
  - `auto` → include if auto-detection says capable
  - `on`   → always include
  - `off`  → always exclude

Notes:
- Default Player stays **manual** (ExoPlayer / IJK radio).
- Migration: existing explicit choices (e.g. `streaming_mode=fixed`) are
  preserved — no automatic reset.

### Phase 3 — Per-player honest codec advertisement

Today `AndroidMiniClientOptions` advertises one merged codec list to the
server regardless of which player will actually decode the stream. ExoPlayer
and IJK have different decoder coverage (e.g. IJK has FFmpeg software
fallback for things ExoPlayer can't do; ExoPlayer has hardware HEVC the IJK
build doesn't always pick up). The server then picks a codec the chosen
player can't actually decode.

Split into per-player capability sets and advertise the appropriate one
based on the active player preference / per-stream selection. Best built
on top of Phase 2's tri-state foundation.

## Network latency resilience

Hardening for clients on high-RTT / low-bandwidth / jittery links (remote /
VPN / Wi-Fi mesh). LAN behaviour today is solid; these items address the
"works at home, freezes at the cabin" case.

### High-leverage

- ~~**Pull-mode socket read timeout.**~~ ✅ Done. `SimplePullDataSource` now
  sets a 30 s `setSoTimeout` after connecting (overridable via
  `-Dsagetv.pull.read.timeout.ms=N`, set 0 to restore legacy blocking
  behaviour). A timeout surfaces as `SocketTimeoutException` → ExoPlayer load
  error → existing 12-retry seek+prepare loop recovers transparently.

- ~~**Control-channel heartbeat.**~~ ✅ Done. New `SocketKeepAlive` helper
  tunes OS TCP keep-alive timing via `jdk.net.ExtendedSocketOptions`
  (reflective; Android API 30+ / Java 11+). Defaults: probe after 30 s
  idle, retry every 10 s, give up after 5 — so a dead control or pull-mode
  connection is detected in ~80 s instead of the OS default ~2 h. Applied
  to both the GFX/media control sockets in `MiniClientConnection` and the
  pull-mode media socket in `SimplePullDataSource`. Tunable via
  `-Dsagetv.tcp.keepidle.s=N`, `-Dsagetv.tcp.keepinterval.s=N`,
  `-Dsagetv.tcp.keepcount=N`. No-op on platforms that don't expose the
  options (falls back to OS defaults silently).

- ~~**Larger ring buffer for non-LAN servers.**~~ ✅ Done. `TrickplayController`
  default ring buffer bumped 4 MB → 16 MB (overridable via
  `-Dsagetv.push.ring.bytes=N`). Roughly 4× the jitter-absorption window
  before underrun; trivial memory cost on Shield-class hardware.

### Medium-leverage

- **Adaptive trickplay timeouts.** `SEEK_QUIET_WINDOW_MS` (180),
  `SEEK_MAX_DEFER_MS` (1500) and `CONVERGENCE_TIMEOUT_MS` (1500) in
  `TrickplayController.java` are fixed constants tuned for LAN. On a link
  with >500 ms RTT a single seek can never converge inside 1500 ms, which
  forces the player into the repeated-flush state we just papered over for
  HEVC. Measure recent server reply latency on `Flush`/`Seek` and scale
  these by ~3× of observed RTT.

- **Exponential backoff in retry loop.** `MAX_PLAYBACK_RETRY_COUNT = 12`
  in `Exo2MediaPlayerImpl.java#L65` retries immediately at ~200 ms
  intervals. On a slow server that's a 2.4 s hot-loop that often gives
  up before the server has even responded. Back off 200 → 400 → 800 → 1600
  to give the server a chance to recover.

- **Adaptive prebuffer threshold.** The 64 KB / 3 s prebuffer gate in
  `Exo2MediaPlayerImpl.java#L616-L618` is fine for LAN but at ≤256 kbps
  the 64 KB takes ~2 s to arrive, leaving <1 s of slack before the safety
  timeout fires and triggers a sniff retry. Measure first-chunk arrival
  rate and scale the threshold (and timeout) to it.

### Lower priority

- **Live SIZE refresh cadence.** `Exo2PullDataSource` re-queries server
  file size every 32 MB read (`#L40`). On a slow link that can be many
  minutes between refreshes, leaving the seek map stale during growing
  recordings. Refresh on a wall-clock interval as well (e.g. every 30 s)
  in addition to the byte threshold.

- **Push-rate flow control.** Long-term: client reports buffer utilisation
  back to the server, server throttles push when buffer is full and ramps
  it up when draining. Useful on shared / mobile uplinks where server
  push rate currently saturates the link.

## Deferred

### AC4 audio support

Parked. Re-engage when there is concrete user demand or an off-the-shelf
AC4-capable extension AAR ships.

Requires:
- ExoPlayer FFmpeg extension AAR rebuilt against FFmpeg 7.1+ with
  `--enable-decoder=ac4` (current bundled extension is FFmpeg 6.0). Note
  this dependency partially overlaps the Media3 migration above — Media3
  1.2+ has the AC4 demuxer/reader already.
- Shield Pro vendor AC4 decoder is **not** exposed via `MediaCodecList`
  even with `ALL_CODECS`, so a software path is the only viable route on
  current Android TV devices.

When revisited, re-add the code that was reverted in May 2026:
- `0xBDC0-0xBDCF` AC-4 sub-stream branch in
  `Exo2MediaPlayerImpl.mapStreamPosToTrackIndex`.
- `AC4_SUBSTREAM_*` constants and `Ac4Reader` selection in
  `PrivateStream1Demuxer` (commit history has the full deferred-reader
  refactor).
- Switch `AndroidMiniClientOptions` back to `MediaCodecList.ALL_CODECS`
  (currently `REGULAR_CODECS`).
- Add `"AC4"` to the audio codec capability list emitted to the server
  once Phase 2's codec/container submenu is in place.

In the meantime the server transcodes AC4 → EAC3 and the client plays
EAC3 natively, which is the expected behaviour.

### Trickplay polish (low priority)

- Double `TransportDS.open()` quirk on first push session: self-recovers
  on retry, but worth investigating for a cleaner first-frame latency.
- Native trickplay state-machine transitions are observed only by
  position polling. Adding explicit JNI callbacks would tighten
  RECOVERING → STABLE detection.
