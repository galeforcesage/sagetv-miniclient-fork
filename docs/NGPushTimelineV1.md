# NG Push Timeline V1

## Purpose

Define the authoritative recording timeline for every push-stream generation.
Clients must not guess an absolute recording position from mux-local PTS values
or from the position reported before a server-side seek.

## Problem

A server-side FF, REW, commercial skip, resume, or live-window reposition may:

1. send `MEDIACMD_FLUSH`;
2. restart the MPEG transport stream;
3. restart mux PTS near zero; and
4. begin sending media from an arbitrary recording-relative position.

`PUSHBUFFER.serverMuxTime` alone is insufficient in this case. A value such as
`456 ms` identifies the new mux timeline, not whether the displayed frame is at
`12 s`, `43 min`, or another position in the recording.

Observed failure on Android `1.15.312`:

- before REW, canonical position was approximately `2,595,334 ms`;
- the server flushed and restarted mux PTS at `456 ms`;
- no absolute target or updated stream epoch was delivered;
- ExoPlayer correctly restarted its local clock near zero;
- the client therefore had no authoritative value with which to map the new
  local clock back to the recording timeline.

Preserving the old client position would also be incorrect because the server
may have repositioned by an unknown amount.

## Required Server Contract

Every operation that changes which recording frame the push stream represents
MUST create a new stream generation.

Before the first media bytes of that generation are accepted by the client, the
server MUST provide:

- `streamEpoch`: monotonically increasing for the playback session;
- `serverMediaTimeMs`: absolute recording-relative position of the first
  decodable media sample in the new generation.

The update MUST describe the same generation as the following `MEDIACMD_FLUSH`
and pushed media. It may be delivered immediately before FLUSH or as part of a
single ordered generation-change message, but it must not race behind the new
media bytes.

The server MUST increment `streamEpoch` for:

- resume at a saved position;
- FF, REW, and commercial/chapter skip;
- explicit seek;
- live-window reposition;
- transcoder or remux restart that changes the media timeline.

A decoder-only restart that preserves the same media timeline does not require
a new epoch.

## Client Mapping

For a new generation, the client:

1. flushes old demux, decoder, render, EOS, and position state;
2. freezes the last canonical position while buffering;
3. rejects samples from the previous `streamEpoch`;
4. waits for the first rendered sample from the new generation;
5. anchors that rendered sample to `serverMediaTimeMs`; and
6. reports subsequent rendered positions through that mapping.

This works whether a player exposes:

- a zero-based clock after FLUSH;
- mux-local PTS; or
- source-relative PTS.

The mapping is established from the first rendered sample, not from buffered
data, decoder time, or the previous generation's position.

## Legacy Compatibility

Legacy servers that do not advertise this capability retain existing behavior.
Clients must not infer this contract from FLUSH alone.

The server should advertise a dedicated capability, for example:

`PUSH_TIMELINE_EPOCH_V1`

Clients advertise and consume the capability only when all required epoch and
absolute-origin fields are implemented.
