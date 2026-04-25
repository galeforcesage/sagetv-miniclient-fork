/*
 * Copyright (C) 2026 SageTV Miniclient contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.extractor.ts;

import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.extractor.ExtractorOutput;
import com.google.android.exoplayer2.extractor.ts.TsPayloadReader.TrackIdGenerator;
import com.google.android.exoplayer2.util.ParsableByteArray;

/**
 * Demultiplexes MPEG-PS private_stream_1 (stream ID 0xBD) into separate
 * elementary stream readers based on the sub-stream ID byte.
 *
 * <p>In MPEG-PS, private_stream_1 can carry multiple audio sub-streams
 * (e.g. AC3 on sub-stream 0x80, EAC3 on sub-stream 0x81). The standard
 * ExoPlayer PsExtractor creates a single Ac3Reader for all private_stream_1
 * PES packets, which causes interleaved data from different sub-streams to
 * corrupt the reader's state machine and crash in Ac3Util.parseAc3SyncframeInfo.
 *
 * <p>This demuxer reads the sub-stream ID from each PES payload and routes
 * data to the correct Ac3Reader instance.
 */
/* package */ final class PrivateStream1Demuxer implements ElementaryStreamReader {

    private static final String TAG = "PS1Demuxer";

    /**
     * Maximum number of audio sub-streams we'll handle from private_stream_1.
     * Typical recordings have 1-3 audio tracks.
     */
    private static final int MAX_SUBSTREAMS = 4;

    /**
     * Sub-stream ID ranges in MPEG-PS private_stream_1:
     * 0x20-0x3F: Subtitles
     * 0x80-0x87: AC3
     * 0x88-0x8F: DTS
     * 0xA0-0xA7: LPCM
     */
    private static final int AC3_SUBSTREAM_MIN = 0x80;
    private static final int AC3_SUBSTREAM_MAX = 0x87;
    private static final int DTS_SUBSTREAM_MIN = 0x88;
    private static final int DTS_SUBSTREAM_MAX = 0x8F;

    private final SparseArray<Ac3Reader> secondaryReaders;

    @Nullable private ExtractorOutput extractorOutput;

    // Saved from packetStarted() for forwarding to the sub-stream reader
    private long activeTimeUs;
    private int activeFlags;

    // Primary reader: pre-created in createTracks() so its track is registered
    // before endTracks() is called. Handles the first sub-stream found, or
    // all data if no sub-stream headers are present.
    @Nullable private Ac3Reader primaryReader;

    // The sub-stream ID assigned to the primary reader, or -1 if not yet assigned
    private int primarySubStreamId;

    // Whether we've detected MPEG-PS private_stream_1 sub-stream headers
    private boolean detectedSubStreamHeaders;

    // Timestamp seeding: each PES packet carries a timestamp for its first frame,
    // but Ac3Reader parses ALL syncframes in the packet and accumulates timeUs
    // by sampleDurationUs per frame. If we forward PES timestamps on every packet,
    // the next PES timestamp resets Ac3Reader's accumulated timeUs back, creating
    // a constant discontinuity (256000us for 8-frame EAC3 packets).
    // Fix: seed with the first valid PES timestamp, then pass C.TIME_UNSET
    // for subsequent packets so Ac3Reader keeps its own smooth accumulation.
    // Ac3Reader.packetStarted() safely ignores C.TIME_UNSET timestamps.
    // On seek, Ac3Reader.seek() resets timeUs to C.TIME_UNSET, so we reset
    // our flags to allow re-seeding from the next valid PES timestamp.
    private boolean primaryTimestampSeeded;
    private final SparseArray<Boolean> secondaryTimestampSeeded;

    // Diagnostic counters
    private int primaryPacketCount;
    private int secondaryPacketCount;

    public PrivateStream1Demuxer() {
        secondaryReaders = new SparseArray<>();
        secondaryTimestampSeeded = new SparseArray<>();
        primarySubStreamId = -1;
        detectedSubStreamHeaders = false;
        primaryTimestampSeeded = false;
        primaryPacketCount = 0;
        secondaryPacketCount = 0;
    }

    @Override
    public void seek() {
        primaryTimestampSeeded = false;
        secondaryTimestampSeeded.clear();
        primaryPacketCount = 0;
        secondaryPacketCount = 0;
        if (primaryReader != null) {
            primaryReader.seek();
        }
        for (int i = 0; i < secondaryReaders.size(); i++) {
            secondaryReaders.valueAt(i).seek();
        }
    }

    @Override
    public void createTracks(ExtractorOutput output, TrackIdGenerator idGenerator) {
        this.extractorOutput = output;
        // Pre-create the primary reader so its track is registered BEFORE
        // endTracks() is called. This reader handles either:
        // - The first AC3 sub-stream found (if sub-stream headers are present)
        // - All data directly (if no sub-stream headers, i.e. raw AC3 sync frames)
        primaryReader = new Ac3Reader();
        primaryReader.createTracks(output, new TrackIdGenerator(
                PsExtractor.PRIVATE_STREAM_1, 0x100));
    }

    @Override
    public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
        activeTimeUs = pesTimeUs;
        activeFlags = flags;
    }

    @Override
    public void consume(ParsableByteArray data) throws ParserException {
        if (primaryReader == null || data.bytesLeft() < 1) {
            return;
        }

        int startPosition = data.getPosition();
        int firstByte = data.readUnsignedByte();

        // Check if the first byte is a known AC3 sub-stream ID
        if (isAc3SubStreamId(firstByte)) {
            detectedSubStreamHeaders = true;

            // AC3/EAC3 sub-stream header: 1 byte sub-stream ID + 3 bytes
            // (number_of_frame_headers + first_access_unit_pointer)
            if (data.bytesLeft() < 3) {
                return; // Truncated sub-stream header
            }
            data.skipBytes(3);

            if (primarySubStreamId == -1) {
                // First sub-stream encountered — assign it to the primary reader
                primarySubStreamId = firstByte;
                Log.i(TAG, "Primary sub-stream: 0x" + Integer.toHexString(firstByte));
            }

            if (firstByte == primarySubStreamId) {
                // Route to the pre-created primary reader (track registered before endTracks)
                primaryPacketCount++;
                // Only forward the PES timestamp until Ac3Reader has been seeded.
                // After that, pass C.TIME_UNSET so Ac3Reader keeps its own
                // smoothly-accumulated timeUs (prevents 256000us discontinuity).
                long forwardTimeUs;
                if (!primaryTimestampSeeded) {
                    forwardTimeUs = activeTimeUs;
                    if (activeTimeUs != C.TIME_UNSET) {
                        primaryTimestampSeeded = true;
                    }
                } else {
                    forwardTimeUs = C.TIME_UNSET;
                }
                if (primaryPacketCount <= 5 || primaryPacketCount % 500 == 0) {
                    Log.i(TAG, "Primary 0x" + Integer.toHexString(firstByte)
                            + " pkt#" + primaryPacketCount
                            + " pesTimeUs=" + activeTimeUs
                            + " fwdTimeUs=" + forwardTimeUs
                            + " dataBytes=" + data.bytesLeft());
                }
                primaryReader.packetStarted(forwardTimeUs, activeFlags);
                primaryReader.consume(data);
                primaryReader.packetFinished();
            } else {
                // Secondary sub-stream — try to create a reader.
                // The track may be created after endTracks(), in which case
                // ExoPlayer silently discards the samples (no crash).
                secondaryPacketCount++;
                // Same timestamp seeding logic for secondary readers
                long forwardTimeUs;
                Boolean seeded = secondaryTimestampSeeded.get(firstByte);
                if (seeded == null || !seeded) {
                    forwardTimeUs = activeTimeUs;
                    if (activeTimeUs != C.TIME_UNSET) {
                        secondaryTimestampSeeded.put(firstByte, true);
                    }
                } else {
                    forwardTimeUs = C.TIME_UNSET;
                }
                if (secondaryPacketCount <= 5 || secondaryPacketCount % 500 == 0) {
                    Log.i(TAG, "Secondary 0x" + Integer.toHexString(firstByte)
                            + " pkt#" + secondaryPacketCount
                            + " pesTimeUs=" + activeTimeUs
                            + " fwdTimeUs=" + forwardTimeUs
                            + " dataBytes=" + data.bytesLeft());
                }
                Ac3Reader reader = getOrCreateSecondaryReader(firstByte);
                if (reader != null) {
                    reader.packetStarted(forwardTimeUs, activeFlags);
                    reader.consume(data);
                    reader.packetFinished();
                }
            }
        } else if (isDtsSubStreamId(firstByte)) {
            // DTS sub-stream — skip for now (no DTS reader in base ExoPlayer)
            detectedSubStreamHeaders = true;
        } else {
            // Not a recognized sub-stream ID. This could mean:
            // 1. The muxer doesn't use sub-stream headers (data starts with AC3 sync words)
            // 2. This is a subtitle or other type we don't handle
            //
            // If we haven't detected sub-stream headers yet, fall back to
            // the original behavior (single Ac3Reader for all data).
            if (!detectedSubStreamHeaders) {
                data.setPosition(startPosition); // Rewind to include the byte we read
                long forwardTimeUs;
                if (!primaryTimestampSeeded) {
                    forwardTimeUs = activeTimeUs;
                    if (activeTimeUs != C.TIME_UNSET) {
                        primaryTimestampSeeded = true;
                    }
                } else {
                    forwardTimeUs = C.TIME_UNSET;
                }
                primaryReader.packetStarted(forwardTimeUs, activeFlags);
                primaryReader.consume(data);
                primaryReader.packetFinished();
            }
            // If we HAVE detected sub-stream headers, this packet has an
            // unrecognized sub-stream type — just skip it.
        }
    }

    @Override
    public void packetFinished() {
        // Already called per-reader in consume()
    }

    private boolean isAc3SubStreamId(int id) {
        return id >= AC3_SUBSTREAM_MIN && id <= AC3_SUBSTREAM_MAX;
    }

    private boolean isDtsSubStreamId(int id) {
        return id >= DTS_SUBSTREAM_MIN && id <= DTS_SUBSTREAM_MAX;
    }

    /**
     * Gets or creates an Ac3Reader for a secondary sub-stream ID.
     * These tracks may be created after endTracks(), in which case ExoPlayer
     * will silently discard their samples. Returns null if max reached.
     */
    @Nullable
    private Ac3Reader getOrCreateSecondaryReader(int subStreamId) {
        Ac3Reader reader = secondaryReaders.get(subStreamId);
        if (reader != null) {
            return reader;
        }

        if (secondaryReaders.size() >= MAX_SUBSTREAMS) {
            Log.w(TAG, "Ignoring sub-stream 0x" + Integer.toHexString(subStreamId)
                    + ": max secondary sub-streams (" + MAX_SUBSTREAMS + ") reached");
            return null;
        }

        if (extractorOutput == null) {
            return null;
        }

        // Create a new Ac3Reader for this secondary sub-stream.
        // Note: this track may be created after endTracks() was called,
        // which means ExoPlayer may not include it in the track selector.
        // That's OK — at minimum we prevent the crash by not feeding
        // this sub-stream's data to the primary reader.
        reader = new Ac3Reader();
        TrackIdGenerator subIdGenerator = new TrackIdGenerator(subStreamId, 0x100);
        reader.createTracks(extractorOutput, subIdGenerator);

        secondaryReaders.put(subStreamId, reader);

        Log.i(TAG, "Created secondary Ac3Reader for sub-stream 0x"
                + Integer.toHexString(subStreamId));

        return reader;
    }
}
