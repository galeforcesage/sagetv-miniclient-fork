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
- ~~`TriStatePreference` custom widget~~ ✅ Replaced ambiguous checkbox
  with a coloured pill on the right of each row (`AUTO·ON` / `AUTO·OFF`
  grey, `ON` green, `OFF` red) plus the title-superscript marker. Tap
  cycles AUTO → ON → OFF → AUTO; long-press resets to AUTO.
- ~~Convert ExoPlayer FFmpeg Extension setting to tri-state.~~ ✅ Done
  (`exoplayer_prefs.xml` key `exoplayer_ffmpeg_extension_tri`,
  `defaultValue="auto"`).
- ~~Codec & Container Settings submenu rebuild.~~ ✅ Done. `CodecContainerFragment`
  generates rows dynamically for every `Container`, `VideoCodec`, and
  `AudioCodec` from `CodecCapabilityDetector.is*Supported()`; each row is a
  `TriStatePreference` with summary showing the auto-detected baseline.
- ~~Wire `MiniClientConnection` capability emission (`PUSH_AV_CONTAINERS`,
  `PULL_AV_CONTAINERS`, `VIDEO_CODECS`, `AUDIO_CODECS`) to consult tri-state
  values.~~ ✅ Done. `AndroidMiniClientOptions.prepareCodecs()` →
  `getSupportedPushContainers/PullContainers/AudioCodecs/VideoCodecs` each read
  `TriState.fromPrefValue(prefs.get*Support(name))` and apply
  `state.resolve(detected)` (auto → include if capable, on → always include,
  off → always exclude). Verified in field logs (`Audio codec added [AUTO]: DTS`
  / `Pull Container excluded [AUTO]: AC3`).
- ~~Audio Passthrough sub-section (AC3 / EAC3 / DTS / TrueHD / AC4 / etc.),
  tri-state.~~ ✅ Done (1.15.119). Auto = `AudioCapabilities.supportsEncoding()`
  per codec via `CodecCapabilityDetector.isAudioPassthroughSupported()`.
  Pref keys `codec/audio_passthrough/<NAME>/support` (default `automatic`).
  UI: new `audio_passthrough` PreferenceCategory in `codec_container_prefs.xml`
  populated by `CodecContainerFragment` (skips codecs with no Android encoding
  constant). Runtime: `AndroidMiniClientOptions.prepareAudioPassthrough()`
  resolves the tri-state → SageTV codec name list → `MiniClientConnection`
  field `passthroughCodecs` → new `AUDIO_PASSTHROUGH` GetProperty handler
  (emits the CSV; `NONE` when empty). Legacy 9.2.x servers ignore the
  property; NG servers consult it to decide whether compressed surround
  can be pushed without PCM transcode.

Notes:
- Default Player stays **manual** (ExoPlayer / IJK radio).
- Migration: existing explicit choices (e.g. `streaming_mode=fixed`) are
  preserved — no automatic reset.

### Phase 3 — Per-player honest codec advertisement

✅ Done (1.15.120). Plumbing-only on the client; legacy 9.2.x servers are
unaffected (they don't query the new property names). NG-server team can
opt in by reading the new properties to bias profile selection.

- `CodecCapabilityDetector` split into per-player static probes:
  `isContainerSupportedByExo/ByIjk`, `isVideoCodecSupportedByExo/ByIjk`,
  `isAudioCodecSupportedByExo/ByIjk`. Existing `is*Supported(...)` methods
  retained as wrappers that dispatch on `default_player` so Phase 2 UI
  badge logic is unchanged. IJK probes return blanket `true` (libavformat /
  libavcodec coverage); narrow only if a specific codec proves IJK-broken.
- `MiniClientOptions.preparePerPlayerCapabilities(Map)` default no-op so
  non-Android platforms (Desktop) don't have to opt in.
- `AndroidMiniClientOptions.preparePerPlayerCapabilities()` walks every
  Container / VideoCodec / AudioCodec, applies the existing tri-state
  prefs against per-player auto-detection, and emits a map keyed by
  SageTV property name (eight entries: `EXO_PUSH_AV_CONTAINERS`,
  `IJK_PUSH_AV_CONTAINERS`, `EXO_PULL_AV_CONTAINERS`,
  `IJK_PULL_AV_CONTAINERS`, `EXO_VIDEO_CODECS`, `IJK_VIDEO_CODECS`,
  `EXO_AUDIO_CODECS`, `IJK_AUDIO_CODECS`). Same tri-state, honest auto.
- `MiniClientConnection.discoverCodecSupport()` now also calls
  `client.preparePerPlayerCapabilities(perPlayerCapabilities)`.
- New GetProperty handlers: a generic `EXO_*` / `IJK_*` matcher emits the
  per-player list as CSV (or `NONE` when empty). Plus a
  `MINICLIENT_DEFAULT_PLAYER` hint so NG servers can bias profile picks
  toward the user's preferred player (Exo by default; user-switchable
  from the OSD long-press menu).

**NG server work (optional, dormant until shipped):**
- Read `MINICLIENT_DEFAULT_PLAYER` to know which player to bias toward.
- Read `EXO_VIDEO_CODECS` / `IJK_VIDEO_CODECS` (and the audio /
  push-container / pull-container counterparts) to build profile
  candidates per player. Pick the candidate that best matches the
  declared default player; fall back to the union if no per-player
  match is found. Existing `VIDEO_CODECS` / `AUDIO_CODECS` /
  `PUSH_AV_CONTAINERS` / `PULL_AV_CONTAINERS` remain authoritative for
  legacy compatibility and as a union fallback.
- No protocol-level handshake change required — these are just additional
  GetProperty keys.

**Legacy 9.2.x server compatibility:** the new property names are not in
the stock 9.2.x advertisement vocabulary, so legacy servers never query
them. Existing `VIDEO_CODECS` / `AUDIO_CODECS` / `PUSH_AV_CONTAINERS` /
`PULL_AV_CONTAINERS` continue to flow through `legacyAdvertise()` against
the same `LEGACY_*_UNIVERSE` filters as before — no behaviour change.

## Phone / non-Leanback mode

The app primarily targets Android TV (Leanback). Phone/tablet/foldable support
landed via two helpers gated on `PackageManager.FEATURE_LEANBACK`; on Leanback
both are no-ops so the existing TV pipeline is untouched.

- ~~**Aspect-ratio scaling.**~~ ✅ Done.
  `android-shared/.../video/NonLeanbackAspect.java` implements Fit / Fill / Zoom
  by transforming the destination rectangle the server hands the client. Mode
  is persisted under pref `non_leanback_aspect_mode` (default FIT). Applied in
  `BaseMediaPlayerImpl#updatePlayerView` (line ~669) using the live stream AR
  from `VideoInfo`. Cycled from the OSD AR button via
  `NavigationFragment` (line ~390). FILL preserves legacy stretch-to-fit
  behaviour. Has no effect on menus (UI is rendered on a separate GL/GDX canvas).

- ~~**Screen rotation.**~~ ✅ Done.
  `android-shared/.../video/OrientationController.java` programmatically calls
  `activity.setRequestedOrientation(...)` with a Leanback guard (no-op on TV).
  Mode is cycled from `NavigationFragment` (line ~410) and persisted across
  sessions. AndroidManifest still declares `screenOrientation="landscape"` as
  the boot default; the controller overrides per-Activity at runtime when
  not-Leanback. Existing `configChanges="keyboard|keyboardHidden|orientation|
  screenSize"` declarations on `MiniClientGDXActivity` and
  `MiniClientOpenGLActivity` keep the GL/Exo/IJK surface alive through rotation.

## Per-server legacy / NG mode

✅ Done (shipped through 1.15.117 → 1.15.118).

- `ServerInfo.legacyMode` enum (AUTO / LEGACY / NG) persisted as
  `servers/<key>/legacy_mode`. Default AUTO.
- Long-press a server tile to set Auto / Legacy / NG.
- `MiniClientConnection` consults `client.getConnectedServerInfo().legacyMode`
  via `isLegacyServerCompat()`. **AUTO now defaults to LEGACY** (was NG-with-
  self-heal in the original plan — flipped because the IO_UNSPECIFIED path
  was leaving people stuck mid-stream).
- **Server self-declaration:** when an NG server sends
  `SetProperty SAGETV_NG_SERVER=1` during the initial post-auth property
  exchange, the client promotes the AUTO state to NG and persists it. Stock
  9.x servers never send this property and stay LEGACY. Spec delivered to and
  shipped by server team.
- Device-aware LEGACY codec advertisement: `buildLegacyDeviceAwarePushFormat()`
  and `buildLegacyDeviceAwareRemuxFormat()` intersect the legacy universe with
  what the device's HW decoders actually report.
- The global `legacy_server_compat` checkbox path remains as a manual escape
  hatch; per-server mode takes precedence when set.

## Auto player selection (Exo ↔ IJK)

Default player is a manual user choice today (Exo or IJK). For known
landmines the client silently picks the right one for that one stream so
the user doesn't have to know which player handles which codec.

1. ~~**Pre-emptive URL inspection (primary).**~~ ✅ Done.
   `PlayerSelectionUtil` is consulted at OPENURL time by
   `MiniClientGDXRenderer`, `OpenGLRenderer`, and `Exo2MediaPlayerImpl`.
   Two landmine patterns recognised:
   - `isExoPsMpeg4Landmine(url)` — `f=MPEG2-PS` (or `MPEG2-TS`) containing
     `[bf=vid;f=MPEG4;...]` (MPEG-4 Part 2 in MPEG program/transport
     stream) — `PsExtractor.H262Reader.parseCsdBuffer` AIOOBE in
     ExoPlayer 2.18.1; IJK demuxes natively.
   - `isBarePushUrl(url)` — empty/garbled `push:` URL with no `F=`
     format descriptor (observed on sagetv-mine NG May 2026 where the
     server's profile resolver emitted DIRECT_PLAY/REMUX with an empty
     payload descriptor). Without the hint, ExoPlayer's sniff path
     partially succeeds on the Fold — audio plays, video stays black.
     IJK uses libavformat sniff and handles a wider set of input shapes.
   First swap per process emits a one-shot toast via
   `PlayerSelectionUtil.notifyLandmineSwap()` so the user knows why we
   re-routed; subsequent swaps are silent (still logged).

2. **Reactive error fallback (safety net).** Still open. If ExoPlayer
   surfaces `ERROR_CODE_PARSING_CONTAINER_MALFORMED` (or similar
   demux-level parse errors) on initial sniff and the URL was *not* on
   the known landmine list, swap to IJK for this stream and remember
   the URL container/codec signature so the next play of a similar
   stream goes straight to IJK without the failed first attempt. Cache
   lives in `MiniClient` for the session; consider persisting later if
   the list stabilises.

3. **User preference (fallback).** When neither rule fires, use the
   user-selected default player. The auto-swap is invisible unless
   something on the landmine list shows up.

Non-goals (for v1):
- No keep-warm second player. Each swap pays the ~300–500 ms cold-start
  cost. Revisit if instrumentation says it matters.
- ~~No toast.~~ Implemented as one-shot per process to balance "user
  should know why" against "don't spam a marathon FF session".

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

- ~~**Pause-resume scrubber monotonicity guard.**~~ ✅ Done (1.15.119).
  `TrickplayController.onPlayerPosition()` now drops player-position
  samples whose backward delta from the previous sample falls inside
  `[MONOTONIC_GUARD_MIN_REGRESSION_MS, MONOTONIC_GUARD_MAX_REGRESSION_MS]`
  (250 ms … 30 s) *when no seek is armed or committed*. Catches the
  pause→resume re-anchor blip (player re-reports the last decoded
  keyframe before catching up) without interfering with legitimate
  seeks (handled by the existing mapping path) or real loops/reloads
  (regression > 30 s, accepted).
- Double `TransportDS.open()` quirk on first push session: self-recovers
  on retry, but worth investigating for a cleaner first-frame latency.
- Native trickplay state-machine transitions are observed only by
  position polling. Adding explicit JNI callbacks would tighten
  RECOVERING → STABLE detection.
