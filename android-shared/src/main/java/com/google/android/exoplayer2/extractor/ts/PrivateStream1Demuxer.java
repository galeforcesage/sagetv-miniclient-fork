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
     * Sub-stream ID ranges: 0x80-0x87 AC3, 0x88-0x8F DTS.
     */
    private static final int AC3_SUBSTREAM_MIN = 0x80;
    private static final int AC3_SUBSTREAM_MAX = 0x87;
    private static final int DTS_SUBSTREAM_MIN = 0x88;
    private static final int DTS_SUBSTREAM_MAX = 0x8F;

    private final SparseArray<Ac3Reader> secondaryReaders;

    @Nullable private ExtractorOutput extractorOutput;

    private long activeTimeUs;
    private int activeFlags;
    @Nullable private Ac3Reader primaryReader;
    private int primarySubStreamId;
    private boolean detectedSubStreamHeaders;

    // Seed first valid PES timestamp per reader, then pass C.TIME_UNSET so
    // Ac3Reader accumulates smoothly (prevents 256000us discontinuity).
    // On seek, Ac3Reader.seek() resets timeUs to C.TIME_UNSET, so we reset
    // our flags to allow re-seeding from the next valid PES timestamp.
    private boolean primaryTimestampSeeded;
    private final SparseArray<Boolean> secondaryTimestampSeeded;

    public PrivateStream1Demuxer() {
        secondaryReaders = new SparseArray<>();
        secondaryTimestampSeeded = new SparseArray<>();
        primarySubStreamId = -1;
    }

    @Override
    public void seek() {
        primaryTimestampSeeded = false;
        secondaryTimestampSeeded.clear();
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

            // AC3 sub-stream header: 1 byte ID + 3 bytes header
            if (data.bytesLeft() < 3) {
                return;
            }
            data.skipBytes(3);

            if (primarySubStreamId == -1) {
                // First sub-stream encountered — assign it to the primary reader
                primarySubStreamId = firstByte;
                Log.i(TAG, "Primary sub-stream: 0x" + Integer.toHexString(firstByte));
            }

            if (firstByte == primarySubStreamId) {
                long forwardTimeUs = seedTimestamp(primaryTimestampSeeded, activeTimeUs);
                if (!primaryTimestampSeeded && activeTimeUs != C.TIME_UNSET) {
                    primaryTimestampSeeded = true;
                }
                primaryReader.packetStarted(forwardTimeUs, activeFlags);
                primaryReader.consume(data);
                primaryReader.packetFinished();
            } else {
                Boolean seeded = secondaryTimestampSeeded.get(firstByte);
                long forwardTimeUs = seedTimestamp(seeded != null && seeded, activeTimeUs);
                if ((seeded == null || !seeded) && activeTimeUs != C.TIME_UNSET) {
                    secondaryTimestampSeeded.put(firstByte, true);
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
            // No sub-stream header — fall back to single-reader mode
            if (!detectedSubStreamHeaders) {
                data.setPosition(startPosition);
                long forwardTimeUs = seedTimestamp(primaryTimestampSeeded, activeTimeUs);
                if (!primaryTimestampSeeded && activeTimeUs != C.TIME_UNSET) {
                    primaryTimestampSeeded = true;
                }
                primaryReader.packetStarted(forwardTimeUs, activeFlags);
                primaryReader.consume(data);
                primaryReader.packetFinished();
            }
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

    /** Returns pesTimeUs for the first valid timestamp (seeding), C.TIME_UNSET after. */
    private static long seedTimestamp(boolean alreadySeeded, long pesTimeUs) {
        return alreadySeeded ? C.TIME_UNSET : pesTimeUs;
    }

    /** Gets or creates an Ac3Reader for a secondary sub-stream ID. */
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

        reader = new Ac3Reader();
        reader.createTracks(extractorOutput, new TrackIdGenerator(subStreamId, 0x100));
        secondaryReaders.put(subStreamId, reader);
        Log.i(TAG, "Created secondary reader for sub-stream 0x" + Integer.toHexString(subStreamId));

        return reader;
    }
}
