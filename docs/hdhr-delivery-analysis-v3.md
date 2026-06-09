# HDHomeRun → SageTV Legacy → Miniclient: Delivery Quality Analysis (v3)

> **Provenance rules for this document**
> - Every concrete claim about server behavior cites `[google/SageTV@f55505f:<file>]`. SHA = `f55505fe923f4256b8f3d87cb59368ac68d38afe` (current master at time of analysis).
> - File contents were retrieved via `https://raw.githubusercontent.com/google/sagetv/<sha>/<path>` and read verbatim.
> - Anything not citation-backed is tagged `[INFERRED]` or `[DEFERRED — Legacy Server validation]`.
> - **NG server is out of scope.** This analysis targets the upstream legacy 9.2.x codebase (which the user's "Legacy Server" server runs). Constraint from user: client must not break NG interop, but no NG changes are proposed.
> - **No hallucinations.** Where upstream evidence is absent, the answer is "I don't know" — not a guess.

---

## 1. What HDHomeRun devices deliver to a SageTV (legacy) server

### 1.1 On-the-wire from the HDHR tuner
HDHomeRun Connect and Flex 4K both deliver **MPEG2-TS** from the tuner to whatever client subscribes (this is a property of the HDHR firmware / libhdhomerun protocol and is outside the SageTV repo). The server consumes that TS stream via its `HDHomeRunCaptureDevice` integration.

### 1.2 What the server writes to disk
[google/SageTV@f55505f:java/sage/HDHomeRunCaptureDevice.java] — `getTunerStreamType()` for any digital input returns:

```java
return Sage.getBoolean("mmc/encode_digital_tv_as_program_stream", true)
        ? MediaFile.MEDIASUBTYPE_MPEG2_PS
        : MediaFile.MEDIASUBTYPE_MPEG2_TS;
```

**The default is `true` → recordings are remuxed to MPEG2-PS on disk.** The TS-from-tuner is wrapped into PS before being written. There is no transcoding at capture time; both PS and TS choices are lossless container wrappings of the original MPEG-2/AC-3 elementary streams.

`activateInput()` passes `videoFormat=1` when the encoder media format is `MPEG2_PS` (else 0) into the native `setInput0` call, telling the native HDHR capture layer which container to emit.

Consequence for the client: a stock 9.2.x server records HDHR content as `.mpg` (MPEG-PS) files, full-bitrate, with the original codecs intact (typically MPEG-2 video + AC-3 audio for OTA, H.264 + AC-3 for many cable QAM channels via Flex 4K).

### 1.3 Operator override
Setting `mmc/encode_digital_tv_as_program_stream=false` in `Sage.properties` makes the server keep raw MPEG2-TS on disk. The 4K-source benefit of this: HEVC elementary streams in TS are preserved without a PS-remux step that can be lossy for some HEVC GOP structures (MPEG-PS was never designed for HEVC). **[DEFERRED — Legacy Server validation]** for whether Flex 4K HEVC paths actually require this on user's server.

---

## 2. How the server decides what to ship to a miniclient

[google/SageTV@f55505f:java/sage/MiniPlayer.java] — `load(byte majorTypeHint, byte minorTypeHint, ...)` is the canonical decision point. The renderer (`mcsr` = `MiniClientSageRenderer`) supplies:

| Capability getter on MCSR | What it returns | Set from client property |
|---|---|---|
| `isSupportedVideoCodec("MPEG2-VIDEO@HL")` | `clientCanDoMPEGHD` | `VIDEO_CODECS` |
| `isSupportedVideoCodec("MPEG4-VIDEO")` | `clientCanDoMpeg4` | `VIDEO_CODECS` |
| `isSupportedPushContainerFormat("MPEG2-PS")` | `clientDoesMPEG2Push` | `PUSH_AV_CONTAINERS` |
| `isSupportedPullContainerFormat(format)` | `containerSupported` | `PULL_AV_CONTAINERS` |
| `isSupportedAudioCodec(...)` | `audioCodecSupported` | `AUDIO_CODECS` |
| `getFixedPushMediaFormat()` | `fixedPushFormat` (string) | `FIXED_PUSH_MEDIA_FORMAT` |
| `getFixedPushRemuxFormat()` | `fixedPushRemuxFormat` (string) | `FIXED_PUSH_REMUX_FORMAT` |
| `isMediaExtender()` | extender-class device | absence of `MOUSE` in `INPUT_DEVICES` |

[google/SageTV@f55505f:java/sage/MiniClientSageRenderer.java] confirms each capability is read once during `initMini()` and stored.

### 2.1 PULL vs PUSH choice
PULL is chosen when:
```
clientDoesPull && (
    httpls ||
    pureLocal ||
    !clientDoesMPEG2Push ||
    !clientCanDoMpeg4 ||
    uiBandwidthEstimate >= miniplayer/min_bandwidth_for_no_transcode
)
```
Default `miniplayer/min_bandwidth_for_no_transcode = 2_000_000` bps.

So on a LAN where the bandwidth estimator reports ≥2 Mbps **and** the client supports `stv://` pull, the server hands the client the file URL directly and the client streams it itself — no transcode, no remux, full quality. This is the highest-fidelity path.

### 2.2 PUSH path branching
If PULL isn't chosen, the server enters PUSH mode:

1. **WAN guard**: if not local AND 2 Mbps ≤ BW < 10 Mbps AND `miniplayer/wan_prevent_push=true` (default), the server forces `uiBandwidthEstimate = min - 1000` to trigger transcode. Even fast WAN links won't get raw push without disabling this.
2. **Media-extender + HD**:
   - if everything supported → direct push of the file (no transcode)
   - if **only container** unsupported AND `clientCanDoMPEGHD` → `prefTranscodeMode = "mpeg2psremux"` (stream copy, container repack only)
   - if video or audio codec unsupported → full transcode
3. **Non-extender or low bandwidth**: `prefTranscodeMode = "dynamic"` (PS push supported) or `"dynamicts"` (TS only)
4. **Operator pin via client capability**:
   - `FIXED_PUSH_MEDIA_FORMAT` set + video+audio supported but container is not → use `FIXED_PUSH_REMUX_FORMAT`
   - `FIXED_PUSH_MEDIA_FORMAT` set otherwise → use it (overrides recipe selection AND disables dynamic rate adjust)

### 2.3 Recipes — what each push mode actually does
[google/SageTV@f55505f:java/sage/MediaServer.java] sets default `media_server/transcode_quality/*` recipes in the `MediaServer` constructor (verbatim):

| Recipe | FFmpeg args | Meaning |
|---|---|---|
| `mpeg2psremux` | `-f dvd -vcodec copy -acodec copy -copyts` | **Lossless** stream copy into DVD container. No re-encode. This is the high-quality push path. |
| `DVD` | `-f dvd -b 4000 -s 720x480\|720x576 -acodec mp2 -r 29.97\|25 -ab 128 -ar 48000 -ac 2` | Re-encode to D1 4 Mbps |
| `SVCD` | `-f dvd -b 2000 -g 3 -bf 0 -acodec mp2 -ab 128 -ar 48000 -ac 2 -s 352x240\|352x288 -r 29.97\|25` | 352-wide low-bitrate fallback |
| `music`/`music128`/`music256` | audio-only MP2 transcodes | for music files |

### 2.4 The "dynamic" push string sent to the client
[google/SageTV@f55505f:java/sage/MiniPlayer.java] — `openURL0("push:" + format)`:

| Mode | Format string |
|---|---|
| `"dynamic"` | `f=MPEG2-PS;[bf=vid;f=MPEG4;][bf=aud;f=MP2]` |
| `"dynamicts"` | `f=MPEG2-TS;[bf=vid;f=MPEG4;][bf=aud;f=AAC]` |
| `"music"`/`"music128"` | `f=MPEG2-PS;[bf=aud;f=MP2]` |

These are the strings the user has been seeing on Legacy Server. Note: **MPEG4 in this context means MPEG-4 Part 2 (a/k/a DIVX/XVID), not H.264**. That is what triggers the ExoPlayer `PsExtractor.H262Reader.parseCsdBuffer` ArrayIndexOutOfBounds landmine the fork already mitigates by routing landmine URLs to IJK ([sagetv-miniclient repo memory: "Auto Player Swap"]).

---

## 3. **The Legacy Server 352x240 root cause** — corrected from v1/v2

Earlier analysis hypothesized that 352x240 came from the `SVCD` recipe in `xcode_qualities`. **That was wrong.** Confirmed-by-source root cause:

[google/SageTV@f55505f:java/sage/FFMPEGTranscoder.java] — `startTranscode()`, the `else if (dynamicRateAdjust)` branch:

```java
xcodeParamsVec.add("-f");      xcodeParamsVec.add(iOSMode ? "mpegts" : "dvd");
xcodeParamsVec.add("-vcodec"); xcodeParamsVec.add(videoCodec = "mpeg4");
xcodeParamsVec.add("-s");
xcodeParamsVec.add(MMC.getInstance().isNTSCVideoFormat() ? "352x240" : "352x288");
targetWidth  = 352;
targetHeight = MMC.getInstance().isNTSCVideoFormat() ? 240 : 288;
```

**352x240 is hardcoded into the `dynamic`/`dynamicts` placeshifter encoder path itself.** It is not configurable via any property. No xcode_qualities recipe controls it. The bandwidth tiers (under 90 / 150 / 900 / ≥900 kbps) only adjust bitrate, fps, and audio sampling — width and height are fixed.

So whenever MiniPlayer above selects `prefTranscodeMode = "dynamic"` (the default for non-extender or low-bandwidth pushes), the user gets 352x240 mpeg4-part2 video regardless of bandwidth, content resolution, or any tuning.

### 3.1 Three legitimate ways to escape 352x240 on a stock 9.2.x server

| Path | Mechanism | Quality |
|---|---|---|
| **A. PULL mode** | Client advertises `stv://` in `STREAMING_PROTOCOLS` + a sufficient `PULL_AV_CONTAINERS` set + bandwidth estimate ≥ 2 Mbps | **Source fidelity** — server hands raw file URL, client streams it |
| **B. Media-extender + mpeg2psremux** | Client identifies as extender (`INPUT_DEVICES` w/o `MOUSE`) + supports HD MPEG2 + supports MPEG2 push container | **Source fidelity** — stream copy only, container remux |
| **C. FIXED_PUSH_MEDIA_FORMAT pin** | Client sends e.g. `videocodec=mpeg4;videobitrate=8000000;resolution=1080;fps=SOURCE;audiocodec=mp2;audiobitrate=192000;container=dvd` | **Pinned recipe**, parsed by `FFMPEGTranscoder.setTranscodeFormat` fallback branch. Note: disables dynamic bitrate adjust. |

[google/SageTV@f55505f:java/sage/FFMPEGTranscoder.java] — `setTranscodeFormat(String,ContainerFormat)`:
- `"dynamic"` → dynamicRateAdjust=true, PS output
- `"dynamicts"` → dynamicRateAdjust=true + iOSMode=true, TS output
- Else: looks up `Sage.get(MediaServer.XCODE_QUALITIES_PROPERTY_ROOT + str, null)`
  - If null, parses the string itself as `key=val;` tokens (videocodec, audiocodec, videobitrate, audiobitrate, gop, bframes, fps, audiosampling, resolution, audiochannels, container)
  - `resolution=D1` → 720x480/576; `=720` → 1280x720; `=1080` → 1920x1080; `=SOURCE` → use source dims

This is the lever Path C uses: send the pin as `FIXED_PUSH_MEDIA_FORMAT` and bypass the hardcoded dynamic recipe entirely.

---

## 4. Client capability properties — full upstream inventory

[google/SageTV@f55505f:java/sage/MiniClientSageRenderer.java] — `initMini()` issues `sendGetPropertyAsync(...)` for every name below and parses the result into a field. **Any client property name not on this list is silently dropped by the server** (it goes into the renderer's set/get pipeline but is never read for delivery decisions).

### 4.1 Properties the legacy server READS for delivery decisions
- `VIDEO_CODECS` (comma list, uppercased; default `MPEG2-VIDEO`, `MPEG1-VIDEO` if not sent)
- `AUDIO_CODECS` (default `MP2`, `MP3`, `AC3`)
- `PULL_AV_CONTAINERS`
- `PUSH_AV_CONTAINERS` (default `MPEG2-PS` if not sent)
- `STREAMING_PROTOCOLS`
- `FIXED_PUSH_MEDIA_FORMAT` — pin the recipe (see §3.1 path C)
- `FIXED_PUSH_REMUX_FORMAT` — pin remux variant when video+audio supported but container is not
- `DETAILED_BUFFER_STATS`, `PUSH_BUFFER_SEEKING`, `PUSH_BUFFER_LIMIT`, `MEDIA_PLAYER_BUFFER_DELAY`
- `INPUT_DEVICES` — presence of `MOUSE` toggles `isMediaExtender()` ↔ "regular client"; this single property changes everything about the §2.2 branching
- `OPENURL_INIT`, `FRAME_STEP`, `GFX_SUBTITLES`, `FORCED_MEDIA_RECONNECT`, `AUTH_CACHE`, `DEINTERLACE_CONTROL`, `ADVANCED_IMAGE_CACHING`, `ZLIB_COMM`

### 4.2 Properties the legacy server NEVER reads
The following are commonly proposed for codec advertising on modern clients but **have zero effect** on a stock 9.2.x server:

- `AUDIO_PASSTHROUGH`
- `MAX_VIDEO_WIDTH`, `MAX_VIDEO_HEIGHT`, `MAX_VIDEO_FRAMERATE`, `MAX_VIDEO_BITRATE`
- `HDR_FORMATS`, `DOLBY_VISION`
- `AC4`, `EAC3_JOC`
- any `MEDIA_PLAYER_*` extension property not on the §4.1 list

Sending them is harmless (SET_PROPERTY for unknown names returns 0 = success), but they **do not** influence what gets pushed. **Don't put them on the critical path for 9.2.x delivery decisions.** Their proper home is NG, which is out of scope here.

---

## 5. SageTV-patched FFmpeg

The repo carries a `third_party/ffmpeg/` directory with SageTV-patched ffmpeg (per user note). Concrete patch enumeration not yet performed in this iteration. Two operationally-visible upstream fingerprints already known to this codebase (from repo memory):

- `-stdinctrl` flag for live transcode control — accepted by SageTV-patched ffmpeg, rejected by stock BtbN n7.x ([sagetv-miniclient repo memory: "sagetv-mine Server FFmpeg Wrapper"]).
- `-activefile`, `-brokendts`, `-dumpmetadata`, numeric `-v <N>` levels — same patched-only surface.

[google/SageTV@f55505f:java/sage/FFMPEGTranscoder.java] confirms these are emitted unconditionally (e.g. `-stdinctrl` line ~1 path, `-brokendts` guarded by `xcode_fix_broken_hdpvr_streams`, `-priority` Windows-only).

Implication for Legacy Server: assuming Legacy Server runs the stock SageTV server install on Windows with its bundled patched ffmpeg, all four paths in §3.1 work as documented. On a containerized re-implementation with stock ffmpeg, the wrapper described in repo memory must strip these.

**[DEFERRED]** — full diff of `third_party/ffmpeg/` against upstream FFmpeg is out of scope for the delivery-quality question; the relevant behavior (recipes, flags, push string) is all in the Java code above.

---

## 6. Client-side recommendations (sagetv-miniclient fork)

These are layered so each phase is independently shippable and reversible. **No NG-server property changes are proposed.** All recommendations target the upstream legacy server's actual decision surface as documented above.

### Phase A — fork already covers this (1.15.108, shipped)
- Per-server `StreamingModeOverride` enum {INHERIT, AUTOMATIC, PULL, DYNAMIC, FIXED} ([repo memory: "Per-Server Legacy Mode"])
- Device-aware LEGACY codec advertisement intersection with HW capabilities ([repo memory: "Device-Aware LEGACY Advertisement"])
- IJK auto-routing for the MPEG-4 Part 2 PS landmine ([repo memory: "Auto Player Swap"])

These are the table-stakes for talking to a 9.2.x server competently. No changes proposed.

### Phase B — extender mode + remux for "stream-copy HD" (highest priority)
**Goal**: trigger §3.1 Path B (mpeg2psremux) for HDHR recordings on Legacy Server.

Required client property posture:
- `INPUT_DEVICES` advertised **without** `MOUSE` token → marks client as media extender to the server. This single change flips the `mediaExtender` branch in `MiniPlayer.load()` and is the prerequisite for mpeg2psremux selection.
- `VIDEO_CODECS` must include `MPEG2-VIDEO@HL` (HD MPEG-2)
- `PUSH_AV_CONTAINERS` must include `MPEG2-PS`
- `PULL_AV_CONTAINERS` must NOT include the source's container (so the "only container unsupported" branch fires)

Then for any HDHR PS recording the server will emit `mpeg2psremux` = `-f dvd -vcodec copy -acodec copy -copyts`, which is a pure stream copy. On Shield this is the existing "Fixed mode + Remux Always → HD MKV" config already documented working ([repo memory: "Shield TV Video Issue (RESOLVED)"]).

**Risk**: marking a non-extender device (e.g. user's Galaxy Fold while in casual playback) as an extender removes mouse-driven UI behaviors. Mitigate by gating on the per-server `StreamingModeOverride` already shipped: only advertise extender-style `INPUT_DEVICES` when the user explicitly chose FIXED for that server, **OR** plumb this as a separate per-server "Identify as extender" toggle alongside the StreamingModeOverride.

### Phase C — FIXED_PUSH_MEDIA_FORMAT pin for HD-pushed transcode at source resolution
**Goal**: when neither A (PULL) nor B (remux) is achievable for a given file, get an HD-quality transcode instead of 352x240.

Required property:
- `FIXED_PUSH_MEDIA_FORMAT` advertised at init, e.g.:
  `container=dvd;videocodec=mpeg4;videobitrate=8000000;resolution=SOURCE;fps=SOURCE;audiocodec=mp2;audiobitrate=192000;audiochannels=2`

This causes the server to bypass the dynamic 352x240 hardcode entirely and emit an FFmpeg command honoring the pinned recipe (see §3, end). Note the upstream comment in `mcprop.h`:
> `//{ "FIXED_PUSH_MEDIA_FORMAT", "videobitrate=650000;audiobitrate=80000;gop=300;bframes=3;fps=30;resolution=CIF;"}`

confirms the token grammar. The DECODER on the client must still support MPEG-4 Part 2 at 1080p — which ExoPlayer does NOT do reliably (the PsExtractor landmine), so this path also requires routing to IJK, which the existing Auto Player Swap already handles.

**Tradeoff**: `FIXED_PUSH_MEDIA_FORMAT` set disables dynamic rate adjust ([google/SageTV@f55505f:java/sage/MiniPlayer.java] explicit: `dynamicRateAdjust = (fixedPushFormat == null || fixedPushFormat.length() == 0)`). For a fixed-bandwidth LAN this is fine; for WAN it can cause underruns. Make this per-server, default off.

### Phase D — UI surface + diagnostics
Add to the per-server long-press menu (already added for Phases 1 & 2):
- "Identify as Extender" boolean (drives §B)
- "HD Push Pin" boolean (drives §C, sends FIXED_PUSH_MEDIA_FORMAT)

Add a "Server Delivery Status" debug overlay (debug builds only) showing the parsed `push:` URL string and which §3.1 path is in effect, so users can verify Phases B/C are actually engaging on Legacy Server without reading server logs.

---

## 7. Order of operations

1. Ship **Phase B** (Identify as Extender toggle) and validate on Legacy Server with HDHR recordings on Shield + Fold + Pixel. Expected: HD MKV stream copy, no transcode, full source bitrate.
2. Once Phase B is proven, ship **Phase C** (HD Push Pin) for files where the container can't be remuxed losslessly (e.g. some H.264 recordings, MKV originals) — still avoids 352x240.
3. **Phase D** UI/diagnostics is bundled with each ship.
4. Confirm **Phase A** behavior unchanged. Confirm NG server interop unchanged (the new client properties are still advertised; NG server ignores extender-INPUT_DEVICES and FIXED_PUSH_MEDIA_FORMAT just as harmlessly as the legacy server ignores AUDIO_PASSTHROUGH).

---

## 8. Open items / not-yet-validated

| Item | Status |
|---|---|
| Live Legacy Server validation of Phases B/C | **[DEFERRED — Legacy Server validation]** |
| `third_party/ffmpeg/` upstream diff | **[DEFERRED]** — not on the delivery-quality critical path |
| Flex 4K HEVC behavior on disk (PS vs TS preference) | **[DEFERRED — Legacy Server validation]** |
| HDR / Dolby Vision pass-through end-to-end on legacy | **N/A** — legacy server has no codepath for it (§4.2). Only meaningful on NG, which is out of scope. |
| AC-4 on legacy | **N/A** — same reason |

---

*Document prepared from upstream files retrieved at SHA `f55505fe923f4256b8f3d87cb59368ac68d38afe`:*
- `java/sage/HDHomeRunCaptureDevice.java`
- `java/sage/MiniPlayer.java`
- `java/sage/MediaServer.java`
- `java/sage/FFMPEGTranscoder.java`
- `java/sage/MiniClientSageRenderer.java`

---

## 9. Server-Side Draft: Capability Schema v2 (copy/paste)

### 9.1 Goal

Keep the current codec/container handshake backward compatible, but add enough detail for the server to make safe NO-TRANSCODE decisions for interlaced sources (especially MPEG-2 1080i) without relying on device-name heuristics.

### 9.2 New properties (optional, versioned)

Client sends these in addition to existing keys:

- `CAP_SCHEMA_VERSION=2`
- `EXO_VIDEO_CONSTRAINTS`
- `IJK_VIDEO_CONSTRAINTS`
- `EXO_AUDIO_CONSTRAINTS`
- `IJK_AUDIO_CONSTRAINTS`
- `EXO_CONTAINER_CONSTRAINTS`
- `IJK_CONTAINER_CONSTRAINTS`

If `CAP_SCHEMA_VERSION` is missing or not parseable, server behavior is unchanged (current boolean capability logic).

### 9.3 Encoding format

- Property value is CSV rows.
- Each row uses semicolon key-value tokens.

Examples:

```text
EXO_VIDEO_CONSTRAINTS=
MPEG2-VIDEO;scan=progressive;interlaced=false;decoder=hw;maxW=1920;maxH=1080;maxFps=30.00;maxBitrate=40000000;profiles=1:2|1:4;adaptive=false;secure=true;tunneled=false,
MPEG2-VIDEO@HL;scan=progressive;interlaced=false;decoder=hw;maxW=1920;maxH=1080;maxFps=30.00;maxBitrate=40000000;profiles=1:2|1:4;adaptive=false;secure=true;tunneled=false,
H.264;scan=interlaced+progressive;interlaced=true;decoder=hw;maxW=3840;maxH=2160;maxFps=60.00;maxBitrate=120000000;profiles=8:256|8:512;adaptive=true;secure=true;tunneled=true

EXO_AUDIO_CONSTRAINTS=
AC3;decode=true;passthrough=true,
AAC;decode=true;passthrough=false

EXO_CONTAINER_CONSTRAINTS=
MPEG2-TS;push=true;pull=false,
MATROSKA;push=false;pull=true
```

Video row optional extras now wired by client:

- `maxW`, `maxH`, `maxFps`, `maxBitrate`
- `profiles` as `profile:level|profile:level|...`
- feature flags: `adaptive`, `secure`, `tunneled`
- optional decoder counters on newer Android: `hwDecoders`, `swDecoders`

### 9.4 Parsing rules

1. Split rows on comma.
2. For each row: first token is codec/container name, remaining tokens are `k=v` attributes.
3. Unknown attributes must be ignored.
4. Missing attributes are `unknown` (never assumed `true`).

### 9.5 Decision integration (minimal delta)

Apply existing decision order unchanged:

1. `NO-TRANSCODE`
2. `REMUX`
3. `TRANSCODE`

But for step 1 and step 2, add one extra guard when schema v2 data exists:

- Direct or remux candidate is valid only if constraints permit source characteristics.

Required source characteristics to validate:

- video codec
- scan type (progressive/interlaced)
- container compatibility for selected transport (push/pull)

Specific rule for this bug class:

- If source is interlaced and chosen video codec row has `interlaced=false`, reject direct play and remux paths for that codec.

### 9.6 Fallback behavior

- No schema v2 data: current behavior.
- Schema v2 present but codec row missing: treat as unknown and keep current behavior for that codec (or conservative reject if preferred).
- Schema v2 row present but `interlaced` missing: treat as unknown.

### 9.7 Acceptance tests

1. Fold-like client advertises:
  - `EXO_VIDEO_CODECS` contains `MPEG2-VIDEO`
  - `EXO_VIDEO_CONSTRAINTS` says `MPEG2-VIDEO;interlaced=false`
  - Source is MPEG-2 1080i
  - Expected: server does not choose `NO-TRANSCODE` for Exo path.

2. Shield-like client advertises:
  - `MPEG2-VIDEO;interlaced=true`
  - Source is MPEG-2 1080i
  - Expected: server may choose `NO-TRANSCODE` if all other checks pass.

3. Legacy client (no `CAP_SCHEMA_VERSION`):
  - Expected: no behavioral change from current logic.

### 9.8 Rollout recommendation

1. Land parser + guarded check behind `CAP_SCHEMA_VERSION >= 2`.
2. Log one decision breadcrumb per playback with:
  - selected player profile
  - source scan type
  - constraint row used
  - reason for reject/accept.
3. After validation, optionally tighten unknown handling to conservative defaults.
- `native/elf/newminiclient/mcprop.h` (cross-reference for property names)
