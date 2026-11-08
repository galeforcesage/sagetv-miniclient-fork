# Party Mode v1: coordinator and player-adapter contract

Status: replacement pre-release contract. The failed recovery-heavy Party
contract is removed in place. Android, PWA and Tizen clients must implement this
document together; there is no compatibility mode for the removed contract.

Canonical sources:

- server protocol: `java/sage/party/`
- executable examples: `docs/party/v1/examples.cjs`
- golden envelopes: `docs/party/v1/golden.json`
- synthetic clients: `test/java/sage/party/PartyServiceTest.java`

## 1. Architecture

Party Mode has four boundaries:

1. **Party coordinator** — membership, host authority, join codes and one
   authoritative timeline.
2. **Clock model** — server/client monotonic offset, RTT and uncertainty.
3. **Player adapter** — prepare ordinary playback, observe media time and execute
   a future play/pause/seek command.
4. **Media delivery** — existing SageTV source lookup, Watch, transcoding,
   direct-play and per-client unicast connections.

The coordinator does not select a playback surface, issue media-producer
credentials, reserve GPU bitrate, own a transcoder, count buffer quorum or
interpret platform decoder internals.

### Existing SageTV playback is authoritative

The server resolves `mediaFileId` with the existing Wizard `MediaFile` lookup.
`PREPARE` calls the existing `VideoFrame.watch(...)` path through
`preparePartyMedia(...)`. That preserves ordinary file accessibility, parental
controls, tuner/resource checks, recording segments, playback-surface selection,
direct play and transcoder selection.

For server-owned MiniClient push playback, a scheduled directive enters the
existing `VideoFrame` job queue as `TIME_SET` followed by `PLAY` or `PAUSE`.
Pull/MSE/AVPlay/Media3 adapters execute the same command locally against their
ordinary source.

Party Mode never introduces a second source resolver or a parallel media loader.

### Independent unicast

Every participant retains its own media connection and player. A slow participant
cannot backpressure another participant. Direct-file reads may share the OS page
cache, and existing delivery infrastructure may reuse transcoders or fragments,
but no Party socket or player is shared.

Shared representation production is a delivery-layer optimization and does not
change this protocol.

## 2. Enablement and negotiation

Set `party/enabled=true` and restart the SageTV JVM. Party Mode remains disabled
by default until physical client acceptance passes.

An eligible renderer advertises all of:

```text
PARTY_V1
PARTY_JOIN_CODE_V1
PARTY_MONOTONIC_V1
PARTY_SCHEDULED_V1
PARTY_HEARTBEAT_V1
PARTY_PLAYER_ADAPTER_V1
PARTY_LOCAL_STOP_V1
PARTY_PRESENTED_FRAME_V1
PARTY_ARMED_REVEAL_V1
PARTY_INVITATION_V1
```

Growing recordings additionally require:

```text
PARTY_GROWING_MEDIA_V1
```

These tokens assert real adapter behavior. Do not advertise them from a client
that cannot prepare ordinary playback, use a monotonic clock, execute a future
deadline, report observed media time and intercept local Stop.

Authorization remains bound to the existing admitted MiniClient/NG session.
Party Mode does not change Placeshifter login, LAN trust, parental control or
security-profile behavior.

## 3. Transport and common envelope

Use the existing MiniClient SetProperty transport. Property name is
case-sensitive:

```text
PARTY_V1
```

Property payload:

```text
u16 nameByteLength
u16 valueByteLength
ASCII property name
UTF-8 JSON value
```

Client-to-server retains the existing 16-byte event header. Server-to-client
retains the existing four-byte command header.

Every JSON value is an object containing:

```json
{
  "schemaVersion": 1,
  "type": "HELLO"
}
```

Maximum JSON length is 8192 UTF-8 bytes. Identifiers and enum strings match
`[A-Za-z0-9_.:-]{1,128}`.

Every request contains:

```json
{
  "requestId": "unique-request-id",
  "requestSeq": 1
}
```

Every request except `HELLO` also contains the latest `serverEpoch` and
`sessionToken` from `AVAILABLE`. Membership requests contain `partyId`, except
code-only `JOIN`.

Successful replies echo `requestId` and include `serverMonoMs`. Events omit
`requestId`. Exact request retries return the cached reply; reusing a request ID
with different content fails.

## 4. Availability

Request:

```json
{
  "schemaVersion": 1,
  "type": "HELLO",
  "requestId": "hello-1",
  "requestSeq": 1
}
```

Reply:

```json
{
  "schemaVersion": 1,
  "type": "AVAILABLE",
  "contract": "PARTY_V1_COORDINATOR",
  "serverEpoch": "opaque",
  "sessionHandle": "opaque",
  "sessionToken": "opaque",
  "sessionTokenExpiresAtServerMonoMs": 1900000,
  "maxParticipants": 5,
  "joinCodeDigits": 3,
  "reconnectGraceMs": 90000,
  "playbackPath": "SAGETV_WATCH",
  "sourceResolution": "WIZARD_MEDIAFILE"
}
```

`LIST_CLIENTS` returns currently eligible renderer session handles for optional
invitation UI. Handles are not participant identities.

## 5. Party creation, joining and reconnect

### CREATE

```json
{
  "type": "CREATE",
  "mediaFileId": 42,
  "startMode": "BEGINNING"
}
```

`mediaFileId` may be omitted only when the server can identify the renderer's
currently loaded media. `startMode` is `BEGINNING` or `CURRENT_POSITION`.
Arbitrary client-supplied start positions are rejected.

`CREATED` returns:

- `partyId`
- creator `participantId`
- private `recoveryToken`
- three-digit `joinCode`
- code expiry
- `prepareVia:"SAGETV_WATCH"`
- canonical `snapshot`

### JOIN

```json
{
  "type": "JOIN",
  "joinCode": "042"
}
```

An invitation may additionally supply the expected `partyId` and `mediaFileId`;
they must match. A join code confirms the room but never replaces normal session
authorization.

### RESUME

```json
{
  "type": "RESUME",
  "partyId": "opaque",
  "participantId": "opaque",
  "recoveryToken": "opaque"
}
```

Resume is available for 90000 ms after connection loss and requires the same
authorized principal/LAN realm. It restores participant identity but always
requires fresh clock calibration and media preparation.

There is no tombstone `REJOIN`. After reconnect grace expires, join again with
the room code as a new participant.

### Host exit

Local Stop or host disconnect:

1. schedules a canonical Pause when the room was playing;
2. preserves the Party and join code;
3. transfers host authority to the earliest connected non-stopped participant;
4. re-sends `JOIN_CODE` to a still-connected stopping host.

## 6. Authoritative timeline

Snapshot fields:

```json
{
  "stateVersion": 3,
  "commandSeq": 3,
  "mediaFileId": 42,
  "state": "PLAYING",
  "anchorPositionMs": 120000,
  "anchorServerMonoMs": 500000,
  "rate": 1,
  "durationMs": 3600000,
  "mediaGrowing": false,
  "availableEndMs": 3600000,
  "playableEndMs": 3600000
}
```

Projected media time at server monotonic time `t`:

```text
PAUSED  -> anchorPositionMs
PLAYING -> anchorPositionMs + max(0, t - anchorServerMonoMs)
```

Clamp the result to `0..playableEndMs`.

When `pendingCommand` exists and its deadline has passed, project from that
command's target position/state/deadline instead.

`stateVersion` is the playback generation. Ignore commands, heartbeats and
corrections for older generations.

## 7. Clock calibration

Client requests `CLOCK`. Server returns:

```json
{
  "type": "CLOCK_PROBE",
  "probeId": "opaque",
  "s0": 500000
}
```

Client records receive/send monotonic timestamps `c1` and `c2`, then sends:

```json
{
  "type": "CLOCK_REPLY",
  "partyId": "opaque",
  "probeId": "opaque",
  "c1": 499900,
  "c2": 499901
}
```

`CLOCK_MODEL` returns:

- `confident`
- `serverMinusClientMs`
- `uncertaintyMs`
- `p95RttMs`

Bootstrap with at least five successful exchanges. Use `performance.now()` on
PWA, `SystemClock.elapsedRealtime()` on Android and the platform monotonic clock
on Tizen. Never use wall time for Party deadlines.

Convert a server deadline to client monotonic time:

```text
clientDeadline = executeAtServerMonoMs - serverMinusClientMs
```

## 8. Preparation and readiness

### PREPARE

```json
{
  "type": "PREPARE",
  "partyId": "opaque"
}
```

The server:

1. resolves the canonical `MediaFile` and returns `PREPARE_ACCEPTED`;
2. calls ordinary SageTV Watch asynchronously;
3. seeks to the current canonical position;
4. verifies the expected playback generation and holds that player paused;
5. on successful load, stores the current menu as `StartPlaybackMenu` and enters
   the standard `MediaPlayer OSD`, matching ordinary Watch Now presentation;
6. emits `PREPARATION_RESULT`.

`PREPARATION_RESULT.status` is `LOADED` or `FAILED`. A loaded result includes
`playbackGeneration` and diagnostic `delivery` metadata. The metadata describes
the normal playback route; it is not a second source API.

`CREATED`, `JOINED`, and `PREPARE_ACCEPTED` do not establish readiness. The
client remains `PREPARING` and sends no ordinary heartbeat until a loaded
preparation has completed clock calibration, sent `READY`, and settled the
initial `CORRECTION` through accepted `PRESENTED`. A transient
`NOT_PREPARED` received while `PREPARING` does not fail the room. A failed
preparation remains fatal and exposes both its server `code` and `watchCode`.

Presentation occurs only for a verified `LOADED` preparation. A failed,
cancelled or superseded preparation does not navigate. Entering the standard
playback UI does not release the paused hold; only a canonical correction or
scheduled command may begin playback.

### READY

After preparation and confident clock calibration:

```json
{
  "type": "READY",
  "partyId": "opaque",
  "stateVersion": 0,
  "playbackGeneration": 7,
  "bufferAheadMs": 0
}
```

`playbackGeneration` echoes the verified `LOADED` preparation generation. Zero
buffer is valid while paused. The server verifies that the ordinary player
is actually loaded and returns a participant-specific `CORRECTION` for initial
alignment.

The client follows the directive lifecycle in section 9. It does not begin
ordinary `HEARTBEAT` reporting until the initial correction reaches
`ACK/PRESENTED`.

`READY` is transition-scoped, not a polling message. The client sends it
exactly once for a verified preparation generation. Its meaningful server
response is the participant-specific initial `CORRECTION`; the protocol does
not define a successful duplicate-READY acknowledgement.

While joined, including while paused, the client performs a fresh five-sample
clock exchange every 20 seconds. Before sending an `ACTION`, it confirms that
the latest `CLOCK_MODEL` remains confident. If not, it retains only the latest
user action, calibrates until confident, requests a fresh `SNAPSHOT`, and sends
that action once with the snapshot's current `stateVersion`.

`CLOCK_UNCERTAIN` is a recoverable scheduling barrier. For a rejected `ACTION`,
the client recalibrates, fetches a fresh snapshot, and retries the retained
action once. It does not enter `ERROR`, leave the Party, disconnect, or execute
the rejected action locally.

## 9. Shared controls and scheduled commands

Client request:

```json
{
  "type": "ACTION",
  "partyId": "opaque",
  "stateVersion": 2,
  "action": "PLAY"
}
```

Supported actions:

```text
PLAY
PAUSE
JUMP
FF
REW
FF2
REW2
COMSKIP
```

`JUMP` additionally supplies `targetPositionMs`. `COMSKIP` is host-only and uses
the existing server-owned commercial markers. FF/REW deltas use existing SageTV
skip settings.

Server event/reply:

```json
{
  "type": "COMMAND",
  "partyId": "opaque",
  "directiveId": "participant-command-3",
  "stateVersion": 3,
  "commandSeq": 3,
  "cause": "PLAY",
  "targetPositionMs": 120000,
  "targetState": "PLAYING",
  "targetRate": 1,
  "executeAtServerMonoMs": 500750,
  "originParticipantId": "opaque"
}
```

`directiveId` is participant-specific and is mandatory on every `COMMAND` and
`CORRECTION`. The server freezes the ready recipient cohort at dispatch. A
late/buffering participant never delays ready participants and does not receive
PLAY/JUMP until it catches up. PAUSE reaches every connected prepared
participant, including a buffering participant.

Command lead is bounded to 300..2500 ms from the worst ready participant p95
RTT. `targetRate` is always 1.0.

Single-phase PLAY, initial alignment, SOFT correction, and PAUSE directives
execute at the scheduled deadline. Successful dispatch produces no wire
message because it does not establish visible playback. If dispatch fails, the
client reports the meaningful failure:

```json
{
  "type": "ACK",
  "partyId": "opaque",
  "directiveId": "participant-command-3",
  "stateVersion": 3,
  "commandSeq": 3,
  "stage": "EXECUTED",
  "success": false
}
```

The client suppresses ordinary heartbeats until the first post-command frame is
presented and reports:

```json
{
  "type": "ACK",
  "partyId": "opaque",
  "directiveId": "participant-command-3",
  "stateVersion": 3,
  "commandSeq": 3,
  "stage": "PRESENTED",
  "success": true,
  "sampleMonoMs": 500812,
  "positionMs": 120062,
  "state": "PLAYING"
}
```

`sampleMonoMs` and `positionMs` have the same presentation semantics as a
heartbeat. PAUSE reports the frame held visibly after the pause operation; it
must not wait for playback position to advance. Failure and PRESENTED ACKs echo
the directive's exact identifiers and are sent at most once. Each directive
ACK is a request with a unique `requestId` and increasing `requestSeq`; the
client processes the matching server `ACK` or `ERROR`. `ACK/PRESENTED` settles
the directive only after the server accepts it.

JUMP, FF, REW, FF2, REW2, COMSKIP, and HARD correction use the barrier
lifecycle. Their initial directive has `phase=PREPARE`, `barrier=true`,
`executeAtServerMonoMs`, `visibleAtServerMonoMs`,
`predictedPresentationLatencyMs`, and `presentationUncertaintyMs`. The client:

1. captures the currently visible video frame into a cover;
2. repositions and decodes the target behind that cover;
3. holds the target paused and sends `ACK/ARMED` with one frame-coherent
   `sampleMonoMs`, `positionMs`, and `state`;
4. remains covered until a matching `REVEAL`;
5. removes the cover at the REVEAL deadline, enters the target play/pause state
   without seeking again, and sends `ACK/PRESENTED` for the newly visible frame.

`PRESENTED` before accepted `ARMED` is invalid. A stale REVEAL with different
`stateVersion`, `commandSeq`, or `directiveId` must not affect playback.

A client accepts only one settling directive at a time. A later directive does
not cancel or replace the scheduled/executed directive; it is negatively
acknowledged without changing the existing target or expected state. A failed
execution marks only that participant late.

## 10. Heartbeats and correction

Send every five seconds while stably playing and every fifteen seconds while
stably paused. Do not send ordinary position heartbeats while a command is
converging or waiting for post-command presentation. Send `BUFFERING` and the
first recovered `PLAYING` sample immediately:

```json
{
  "type": "HEARTBEAT",
  "partyId": "opaque",
  "stateVersion": 3,
  "sampleMonoMs": 499900,
  "positionMs": 119900,
  "bufferAheadMs": 2000,
  "state": "PLAYING"
}
```

Heartbeat cadence is a liveness guarantee, not an ACK-dependent request cadence.
If a `HEARTBEAT_ACK` is lost, the client replaces the unacknowledged heartbeat
with a fresh request and current sample when the next interval is due.

Allowed states:

```text
PLAYING
PAUSED
BUFFERING
STOPPED
ERROR
```

`positionMs` is the media position of the last frame delivered to the display,
not downloaded bytes, decoder input, buffered head, transport position, or a
seek target. `sampleMonoMs` is the client monotonic time associated with that
frame's display release. For audio-only playback, use the audio presentation
head.

Heartbeat cadence is measured from request send time, not from
`sampleMonoMs`. A paused participant therefore continues sending liveness
heartbeats even though the same held frame legitimately retains the same
presentation timestamp. Accepted `PRESENTED` already carries the current
presentation sample, so the client does not immediately duplicate it in a
heartbeat.

The coordinator compares participants at one common server-monotonic instant by
projecting each `PLAYING` presentation sample at rate 1.0. It does not compare
heartbeats captured at different instants directly. `PAUSED` samples are not
projected.

`BUFFERING` marks presentation starvation and affects only that participant.
While buffering, activating, or converging, the coordinator does not issue
drift corrections. The first post-starvation frame is reported immediately.
After a directive, `ACK/PRESENTED` ends the settling interval and ordinary
sparse heartbeats resume.

`HEARTBEAT_ACK` includes:

- current `stateVersion` and `commandSeq`
- `desiredState`
- projected `desiredPositionMs`
- observed `skewMs`

Correction policy:

| Condition | Result |
| --- | --- |
| absolute drift at or below 67 ms | none |
| drift above 67 ms through 750 ms for three post-activation samples | one soft correction |
| drift above 750 ms, wrong play/pause state, or post-starvation drift | one hard correction |
| BUFFERING or ERROR | mark participant late; do not correct |

Only one correction may be in flight per participant, and another correction
must not be issued while it is settling. A `CORRECTION` contains the same
participant-specific `directiveId`, target position/state/rate/deadline fields
as a command plus `reason` and `kind`:

- `SOFT` is presentation-only. When ahead, hold the displayed progression for
  the instructed drift interval. When behind, skip delivery of intermediate
  video frames while the rate-1 player advances. It must not seek, reposition
  the transport, or alter playback rate.
- `HARD` performs a real canonical reposition using the barrier lifecycle.

The no-seek rule applies only to `SOFT` correction. User JUMP, FF, REW,
COMSKIP, and `HARD` correction continue to perform real timeline repositioning.
SOFT uses the single-phase presentation lifecycle; HARD uses
PREPARE/ARMED/REVEAL/PRESENTED. Neither changes the canonical timeline
generation.

## 11. Local controls

Captions, audio-track selection, subtitle styling and volume remain local.

Local Stop is not a shared Stop command. Report it as:

```json
{
  "type": "HEARTBEAT",
  "state": "STOPPED",
  "positionMs": 120000,
  "bufferAheadMs": 0
}
```

The participant must PREPARE and READY again before receiving PLAY/JUMP.

## 12. Leave, end and expiry

- `LEAVE` removes only the requesting participant and commits its watched/resume
  position through existing SageTV policy.
- host-only `END` ends the room.
- Parties expire six hours after creation.
- A Party with no remaining members is removed immediately.

Server shutdown clears ephemeral Party state without stopping ordinary playback.

## 13. Client adapter acceptance

Before advertising the required capabilities, each platform must prove:

1. ordinary media preparation uses its production player/source path;
2. preparation remains paused until correction or command execution;
3. server monotonic deadlines are converted correctly;
4. stale generations are ignored;
5. observed media time is trustworthy;
6. local Stop is intercepted and reported;
7. one buffering participant does not pause or delay another;
8. PAUSE works while a participant reports buffering;
9. barrier preparation preserves the old visible frame until matching REVEAL;
10. REVEAL changes visibility/state without a second seek;
11. invitation JOIN includes the paired `partyId` and `mediaFileId`;
12. reconnect requires fresh clock calibration, preparation, and READY;
13. captions/audio/subtitle changes remain local.
