# Android MiniClient Roadmap

Last audited: 2026-10-19

This roadmap records unfinished or recently questioned work and classifies it
against the current working tree, tests, documentation, and git history.

Status meanings:

- **implemented** - present in the current source with supporting tests or
  established history.
- **partially implemented** - meaningful code exists, but part of the intended
  behavior, validation, or persistence is still missing.
- **not implemented** - the planned behavior is absent from the current source.
- **superseded** - later evidence invalidated the item or replaced its approach.
- **unable to determine** - this repository does not contain enough evidence to
  establish the external or runtime state.

## Audit summary

| Area | Classification | Repository evidence |
| --- | --- | --- |
| Migrate ExoPlayer 2.x to AndroidX Media3 | **not implemented** | Production code and tests still import `com.google.android.exoplayer2.*`; `build.gradle` still defines ExoPlayer 2.19.1 dependencies. No `androidx.media3` implementation is present. |
| NG server adoption of per-player capability properties | **unable to determine** | Client emission and consumption exist in `AndroidMiniClientOptions`, `MiniClientConnection`, and `PlayerSelectionUtil`, including `CAP_EFFECTIVE_PLAYER`. The authoritative server implementation is outside this repository. |
| Reactive Exo-to-IJK error fallback and signature cache | **not implemented** | `Exo2MediaPlayerImpl.onPlayerError()` retries the same Exo player. `PlayerSelectionUtil` contains extensive pre-open routing rules, but no runtime engine swap or container/codec failure-signature cache. |
| Adaptive trickplay timeouts based on observed RTT | **not implemented** | `TrickplayController` still uses fixed `SEEK_QUIET_WINDOW_MS`, `PUSH_SEEK_QUIET_WINDOW_MS`, `SEEK_MAX_DEFER_MS`, and `CONVERGENCE_TIMEOUT_MS`. `SeekCoalescePolicy` only extracts the existing fixed-window calculation for tests. |
| Exponential playback retry backoff | **not implemented** | `Exo2MediaPlayerImpl` retains `MAX_PLAYBACK_RETRY_COUNT = 12`; pull retries call `seekTo()` and `prepare()` immediately, while push retries use the same fixed prebuffer gate. No increasing delay schedule exists. |
| Adaptive push prebuffer threshold | **partially implemented** | Startup was improved from the old roadmap values: `Exo2MediaPlayerImpl.runWhenPrebuffered()` now uses a fixed 16 KiB / 2 s gate, and known container hints bypass sniff prebuffering. It still does not measure arrival rate or adapt threshold/timeout to bandwidth. |
| Wall-clock refresh of live pull file size | **not implemented** | `Exo2PullDataSource` refreshes `SIZE` only after 32 MiB read. There is no elapsed-time refresh condition, so low-bitrate growing recordings can retain a stale seek map for minutes. |
| Push-rate flow control | **partially implemented** | The legacy protocol already returns `playa.getBufferLeft()` from `MediaCmd` for each `PUSHBUFFER`, backed by `sage_transport_buffer_available()`. There is no proactive utilization telemetry, target fill level, or server ramp/throttle policy. |
| Full AC-4 playback support | **partially implemented** | AC-4 is modeled in `AudioCodec`, detected conservatively by `CodecCapabilityDetector`, mapped in `Exo2MediaPlayerImpl`, and routed away from IJK by `PlayerSelectionUtil`. The app still uses ExoPlayer 2.x, `PrivateStream1Demuxer` supports only AC3/DTS ranges, and no bundled FFmpeg AC-4 software decoder is present. |
| First-session double `TransportDS.open()` quirk | **unable to determine** | `IJKPushMediaSource` and related data sources still contain lazy/reopen paths, but there is no regression test or deterministic instrumentation proving whether the observed double-open remains harmful or has self-resolved. |
| Explicit native trickplay state callbacks | **not implemented** | Exo and IJK feed `TrickplayController.onPlayerPosition()` through polling; Exo uses a 500 ms `Handler` loop. No JNI callback carries native `RECOVERING -> STABLE_PLAYING` transitions. |
| External sink capability reporting | **partially implemented** | `SinkResolutionResolver` can scan `Display.getSupportedModes()`, `ExternalDisplayController.logModes()` logs mode lists, the UI requests the highest mode, and the current working tree adds shared `DisplayMeasurement` routing for both renderers. However, `resolveSink()` still prefers the active mode before the maximum supported mode, and real 4K/HDMI 2.0 validation is not represented by tests. |
| Server-connected Path B surface move | **partially implemented** | `ExternalVideoSurfaceController`, `ExternalVideoPresentation`, surface reattachment, Exo audio routing, Exo caption routing, phone controls, and orientation handling are present. Live-TV/IJK captions remain part of the phone GL OSD, and IJK does not override `BaseMediaPlayerImpl.setPreferredAudioOutput()`, so server-connected IJK audio routing remains incomplete. |
| OSD mirror to the external display | **not implemented** | The only reference is a future-mode comment in `ExternalVideoSurfaceController`; there is no second EGL surface, framebuffer blit, or mirrored libGDX/OpenGL render target. |
| Offline external-display IJK audio limitation | **superseded** | The old roadmap said IJK audio stayed on the phone. Commits `d697b76`, `52eac60`, and `bf54cad` added MediaRouter routing, player recreation, and patched preferred-device support. `OfflineExternalDisplayManager` and `OfflinePlaybackActivity` contain the current implementation. |
| Offline casting orientation lock and surface recreation hardening | **partially implemented** | Offline surface movement and return-to-phone behavior exist. `OfflinePlaybackActivity` exposes manual `OrientationController` cycling, but the offline manager does not automatically lock orientation while presenting, and its presentation-loss path returns playback to the phone rather than explicitly reasserting an external surface after phone-view recreation. |
| Pull-xcode transport and resume/reopen lifecycle | **not implemented** | Android has timeline-rebasing scaffolding for `CAP_EFFECTIVE_DELIVERY`, but it never implements the required `XCODE_SETUP` handshake and instead drives the selected stream with ordinary OPEN/SIZE/READ requests. `DELIVERY_MODES` therefore advertises only the working `push,pull` modes. Full support requires the complete setup, open, seek/reopen, EOF, and teardown lifecycle plus regression coverage. |
| `SEEK_EPOCH_V1` wire contract | **not implemented** | Client-side epoch bookkeeping exists, but `MiniClientConnection` deliberately defaults the capability to false because the fixed-size `GETMEDIATIME` response cannot carry an epoch. A compatible wire format and server consumer are still required. |
| `DVR_WINDOW_V1` behavior in push mode | **partially implemented** | Clamp logic exists only in the `MediaCmd.MEDIACMD_SEEK` branch. The same class handles push repositioning through `MEDIACMD_PUSHBUFFER` and `serverMuxTime`, but that path has no DVR-window clamp and `MiniClientConnection` has no implemented server window payload to supply one. |
| Named SAP/audio-track selector | **not implemented** | `SageCommand.DVD_AUDIO_CHANGE` supports blind cycling, and `StreamInfo.AudioTrack` exposes language, primary, and id. `MiniPlayerPlugin` has no audio-track enumeration/selected-track API, the UI has no SAP picker, and STREAMINFO has no explicit role/SAP marker or direct-select contract. |
| Exo audio-track bookkeeping prerequisite | **not implemented** | `Exo2MediaPlayerImpl.changeTrack()` still writes `selectedSubtitleTrack` for both text and audio changes. A future audio picker needs separate selected-audio bookkeeping before exposing direct selection. |
| IJK Main-vs-SAP track mapping | **partially implemented** | The current tree applies deferred audio selection before `player.start()` and logs all IJK track entries, but the mapping still assumes IJK audio enumeration matches SageTV PES order. No role/language-based correction or regression test proves Main is selected over SAP. |
| IJK release/EGL teardown race | **partially implemented** | The current working tree adds `releaseLock` and atomically claims/nulls the player and media session before teardown. This directly addresses the observed double-release race, but the change is not in HEAD and has no automated lifecycle/race regression test. |
| Long-session GFX reply starvation | **partially implemented** | The current working tree moves offline-cache image decoding out of the synchronous GFX command processor, schedules Android texture deletion on the renderer thread, and closes the graphics session when an active command exceeds four seconds so a late reply cannot poison protocol framing. Long-duration playback-to-menu soak validation is still required. |
| Interlaced MPEG-2 in Matroska on Media3/Exo | **partially implemented** | The current working tree marks Media3 MPEG-2 as progressive-only in surface constraints and routes STREAMINFO-confirmed interlaced MPEG-2 Matroska to IJK. It also adds decoder/frame/surface/audio freeze diagnostics. Playback beyond the former 193.944 s point still needs validation with the reported source. |
| High-latency/jittery transport reliability | **partially implemented** | Version 1.15.198 adds transport-scoped attempt/socket ownership, registers sockets before blocking connect/handshake operations so cancellation can close only the owned attempt, preserves the published connection's ownership until a replacement actually publishes, uses progress-aware handshake idle and absolute deadlines, records structured phase metrics, applies tri-state port-7818 probing, suppresses stale attempts, and independently reconnects the media channel with bounded retries. A repeatable network-impairment device test remains outstanding. |
| Mobile screen-off and HDMI-attached connection lifecycle | **partially implemented** | Version 1.15.198 no longer converts activity pause into server STOP, local player pause, or session teardown, preserves connections across configuration/display changes, suppresses screensaver exit while presenting externally, and requires explicit opt-in before launching the full UI on an attached external display. Explicit media-session Pause still sends SageTV PAUSE. Device validation with DeX/HDMI remains outstanding. |

## Priority work

### 1. Land and regression-test the current playback fixes

The working tree contains useful but not-yet-persisted playback work:

- Dormant pull-xcode timeline classification and rebasing scaffolding.
- IJK release idempotency/EGL race protection.
- Shared display measurement for both GDX and OpenGL renderers.
- Seek-coalescing policy extraction and JVM tests.
- IJK audio-selection ordering and diagnostics.

Before treating these as shipped:

1. Keep pull-xcode disabled until the client implements and tests XCODE_SETUP,
   initial open, absolute seek/reopen, EOF, teardown, and unchanged push/pull
   behavior.
2. Add lifecycle coverage for repeated/overlapping IJK release calls.
3. Add display resolver tests for active mode, maximum supported mode,
   last-known-good fallback, and an external-display override.
4. Resolve the IJK Main/SAP mapping rather than retaining diagnostics alone.

### 2. Finish the NG trickplay contract

- Define a backward-compatible position response that can carry a seek epoch,
  then enable `SEEK_EPOCH_V1`.
- Move or duplicate DVR-window enforcement into the actual push reposition
  path. This requires the server to send an authoritative seekable window or
  live-edge context.
- Keep legacy google/SageTV behavior unchanged when NG capabilities or hints
  are absent.
- Replace fixed timeout values only after collecting a stable RTT signal from
  control operations; do not infer network latency from decoder timing.

### 3. Complete audio-track selection

- Add audio-track enumeration and selected-track APIs parallel to subtitle
  APIs in `MiniPlayerPlugin`.
- Fix Exo audio selection bookkeeping so audio changes cannot overwrite
  `selectedSubtitleTrack`.
- Agree on a cross-client server canonical for track role (`main`, `sap`,
  commentary, descriptive audio), stable track id, and direct selection.
- Preserve `DVD_AUDIO_CHANGE` as a legacy-compatible cycle fallback.
- Validate track selection across push, pull-direct, and pull-xcode reopen.

### 4. Make network recovery adaptive

- Add bounded exponential backoff to Exo retry attempts.
- Adapt push prebuffer timing to measured first-byte/chunk arrival rate.
- Add a wall-clock live-size refresh alongside the 32 MiB threshold.
- Decide whether existing free-buffer replies are sufficient server flow
  control or whether NG needs explicit fill-level telemetry and rate targets.

### 5. Finish external-display behavior

- Decide and document whether `DISPLAY_SINK_RESOLUTION` means current active
  mode or maximum supported sink mode. Use the same canonical across Android
  and other clients.
- Validate mode selection and sink reporting on a real 4K panel over an HDMI
  2.0-capable path.
- Implement server-connected IJK audio routing if the patched IJK
  `setPreferredAudioDevice` API is suitable for the online player.
- Implement OSD mirroring only as an opt-in mode; keep clean-video/phone-control
  Path B behavior unchanged.
- Add offline orientation locking and external-surface recreation recovery.

## Deferred

### AndroidX Media3 and AC-4

Migrate to Media3 before claiming general AC-4 support. The migration must
rebase the custom `SagePsExtractor`, `PrivateStream1Demuxer`,
`LinearPsSeekMap`, data sources, player integration, and FFmpeg extension.
Validate push, pull, completed recordings, growing recordings, seeking,
comskip, live playback, HEVC/HDR, and legacy server compatibility.

Until a verified decoder path exists, continue to fail closed when advertising
AC-4 and allow the server to transcode to a supported codec.

### Party Mode v1 hardware qualification

The Android client implements negotiated `PARTY_V1` framing, clock
calibration, scheduled command observation, direct-pull producer binding,
recovery, Local Stop isolation, and media-popup controls. Direct pull and
server-owned push remain fail-closed until actual preparation, clock, buffer,
surface, and admission checks pass.

Before release, qualify decoder seek/position behavior, surface activation,
background/network transitions, 30-minute mixed-client synchronization,
encoder headroom, and teardown on real devices. IJK cannot currently prove
contiguous buffered milliseconds, and HLS lacks admission-header propagation,
so those Party readiness paths remain unsupported.

### Reactive player fallback

Keep the existing pre-open `PlayerSelectionUtil` rules as the primary path.
A future reactive fallback should be bounded to initial demux/decode startup
failures, swap engines only once per open, and cache a normalized
container/video/audio signature for the process. It must not create an
unbounded Exo/IJK retry loop.

### Native transition callbacks

JNI callbacks could reduce trickplay stabilization latency, but polling remains
the safer implementation until the native callback lifecycle can be proven
safe across flush, seek, release, and activity recreation.
