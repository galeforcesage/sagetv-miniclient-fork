/*
 * Copyright (C) 2016 The Android Open Source Project
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
import com.google.android.exoplayer2.extractor.Extractor;
import com.google.android.exoplayer2.extractor.ExtractorInput;
import com.google.android.exoplayer2.extractor.ExtractorOutput;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.extractor.PositionHolder;
import com.google.android.exoplayer2.extractor.SeekMap;
import com.google.android.exoplayer2.extractor.SeekPoint;
import com.google.android.exoplayer2.extractor.ts.TsPayloadReader.TrackIdGenerator;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.ParsableBitArray;
import com.google.android.exoplayer2.util.ParsableByteArray;
import com.google.android.exoplayer2.util.TimestampAdjuster;

import java.io.IOException;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/**
 * Extracts data from the MPEG-2 PS container format.
 *
 * <p>This is a modified copy of ExoPlayer's {@link PsExtractor} that uses
 * {@link PrivateStream1Demuxer} instead of a bare {@link Ac3Reader} for
 * private_stream_1 (0xBD) packets. This properly demultiplexes files with
 * multiple AC3/EAC3 audio sub-streams that share the same PES stream ID.
 *
 * <p>Based on ExoPlayer 2.18.1 PsExtractor source (Apache 2.0).
 */
public final class SagePsExtractor implements Extractor {

    /**
     * Provider of current file size for live/growing recordings. When set, the
     * {@link LinearPsSeekMap} will use the dynamically-reported size instead of
     * the frozen size captured at preparation time. This allows seeks (FF/REW
     * during commercial skip on a still-recording show) to map to correct byte
     * positions in the file as it grows.
     */
    public interface LiveSizeProvider {
        /** @return current file size in bytes, or -1 if not available. */
        long getCurrentSize();
    }

    /**
     * Per-instance live size provider. Injected via the constructor (preferred)
     * or, for legacy compatibility with the no-arg {@link ExtractorsFactory}
     * factory path, via {@link #setLiveSizeProvider(LiveSizeProvider)}.
     */
    private LiveSizeProvider instanceLiveSizeProvider = null;

    /** Factory for {@link SagePsExtractor} instances. */
    public static final ExtractorsFactory FACTORY = () -> new Extractor[]{new SagePsExtractor()};

    /* package */ static final int PACK_START_CODE = 0x000001BA;
    /* package */ static final int SYSTEM_HEADER_START_CODE = 0x000001BB;
    /* package */ static final int PACKET_START_CODE_PREFIX = 0x000001;
    /* package */ static final int MPEG_PROGRAM_END_CODE = 0x000001B9;
    private static final int MAX_STREAM_ID_PLUS_ONE = 0x100;

    // Max search length for first audio and video track in input data.
    private static final long MAX_SEARCH_LENGTH = 1024 * 1024;
    // Max search length for additional audio and video tracks in input data after at least one audio
    // and video track has been found.
    private static final long MAX_SEARCH_LENGTH_AFTER_AUDIO_AND_VIDEO_FOUND = 8 * 1024;

    public static final int PRIVATE_STREAM_1 = 0xBD;
    public static final int AUDIO_STREAM = 0xC0;
    public static final int AUDIO_STREAM_MASK = 0xE0;
    public static final int VIDEO_STREAM = 0xE0;
    public static final int VIDEO_STREAM_MASK = 0xF0;

    private final TimestampAdjuster timestampAdjuster;
    private final SparseArray<PesReader> psPayloadReaders; // Indexed by pid
    private final ParsableByteArray psPacketBuffer;
    private final PsDurationReader durationReader;

    private boolean foundAllTracks;
    private boolean foundAudioTrack;
    private boolean foundVideoTrack;
    private long lastTrackPosition;
    private static final String TAG = "SagePsExtractor";
    private static final boolean DEBUG = false;
    private boolean needsResync; // true after seek to non-zero position

    // Accessed only by the loading thread.
    private @MonotonicNonNull ExtractorOutput output;
    private boolean hasOutputSeekMap;
    private long durationPhaseInputLength = C.LENGTH_UNSET; // input length from PsDurationReader phase

    public SagePsExtractor() {
        this(new TimestampAdjuster(0), null);
    }

    public SagePsExtractor(TimestampAdjuster timestampAdjuster) {
        this(timestampAdjuster, null);
    }

    /**
     * Construct with a {@link LiveSizeProvider} that the extractor's seek map
     * will consult to determine the current file size for live/growing
     * recordings. Pass {@code null} for completed-file (bounded) playback.
     */
    public SagePsExtractor(TimestampAdjuster timestampAdjuster, LiveSizeProvider liveSizeProvider) {
        this.timestampAdjuster = timestampAdjuster;
        this.instanceLiveSizeProvider = liveSizeProvider;
        psPacketBuffer = new ParsableByteArray(4096);
        psPayloadReaders = new SparseArray<>();
        durationReader = new PsDurationReader();
    }

    /**
     * Set or replace the {@link LiveSizeProvider} after construction.
     * Used when the extractor was created via the no-arg
     * {@link ExtractorsFactory} path and the data source needs to wire itself
     * in after the extractor has been built.
     */
    public void setLiveSizeProvider(LiveSizeProvider provider) {
        this.instanceLiveSizeProvider = provider;
    }

    // Extractor implementation.

    /**
     * Maximum number of leading bytes the sniffer is willing to skip while
     * looking for the first {@link #PACK_START_CODE}. Push-mode playback
     * starts the transport buffer at an arbitrary stream position so the
     * very first bytes are usually mid-PES; without this scan ExoPlayer
     * raises a transient {@code UnrecognizedInputFormatException} and burns
     * several player retries before alignment happens to land on a pack
     * boundary. 16 KB is well over a typical PES packet so we converge on
     * the first try.
     */
    private static final int SNIFF_SCAN_BYTES = 16 * 1024;

    @Override
    public boolean sniff(ExtractorInput input) throws IOException {
        // Look for PACK_START_CODE somewhere in the first SNIFF_SCAN_BYTES,
        // not necessarily at offset 0. Returns the byte offset of the start
        // code, or -1 if not found / not enough data.
        int packOffset = peekPackStartCodeOffset(input);
        if (packOffset < 0) {
            return false;
        }

        // Validate the pack header marker bits at the discovered offset.
        // peekPackStartCodeOffset already validated the 4-byte start code;
        // here we re-peek the surrounding 14 bytes (skipping leading garbage)
        // and run the original SCR / marker-bit checks.
        input.resetPeekPosition();
        if (packOffset > 0) {
            input.advancePeekPosition(packOffset);
        }

        byte[] scratch = new byte[14];
        if (!input.peekFully(scratch, 0, 14, true)) {
            return false;
        }

        // Verify the 01xxx1xx marker on the 5th byte
        if ((scratch[4] & 0xC4) != 0x44) {
            return false;
        }
        // Verify the xxxxx1xx marker on the 7th byte
        if ((scratch[6] & 0x04) != 0x04) {
            return false;
        }
        // Verify the xxxxx1xx marker on the 9th byte
        if ((scratch[8] & 0x04) != 0x04) {
            return false;
        }
        // Verify the xxxxxxx1 marker on the 10th byte
        if ((scratch[9] & 0x01) != 0x01) {
            return false;
        }
        // Verify the xxxxxx11 marker on the 13th byte
        if ((scratch[12] & 0x03) != 0x03) {
            return false;
        }
        // Read the stuffing length from the 14th byte (last 3 bits)
        int packStuffingLength = scratch[13] & 0x07;
        input.advancePeekPosition(packStuffingLength);
        // Now check that the next 3 bytes are the beginning of an MPEG start code
        if (!input.peekFully(scratch, 0, 3, true)) {
            return false;
        }
        if (PACKET_START_CODE_PREFIX
                != (((scratch[0] & 0xFF) << 16) | ((scratch[1] & 0xFF) << 8) | (scratch[2] & 0xFF))) {
            return false;
        }

        // If we had to skip leading garbage, remember to resync the read
        // position on the very first read() call. We can't call skipFully()
        // here (sniff is peek-only); the existing resync path handles it.
        if (packOffset > 0) {
            needsResync = true;
            if (DEBUG) {
                Log.d(TAG, "sniff: PACK_START_CODE at +" + packOffset
                        + ", read path will resync");
            }
        }
        return true;
    }

    /**
     * Peek-only scan for the first {@link #PACK_START_CODE}. Reads up to
     * {@link #SNIFF_SCAN_BYTES} bytes from the current peek position and
     * returns the offset of the start code, or -1 if it can't be located.
     * Resets the peek position before returning either way.
     *
     * <p>Uses incremental {@link ExtractorInput#peek} reads so partial data
     * from a non-seekable / live source (e.g. push-mode ring buffer that
     * has fewer than {@link #SNIFF_SCAN_BYTES} bytes available right after
     * a server flush) still produces a valid result instead of failing the
     * whole sniff.
     */
    private int peekPackStartCodeOffset(ExtractorInput input) throws IOException {
        input.resetPeekPosition();
        byte[] buf = new byte[SNIFF_SCAN_BYTES];
        int filled = 0;
        // Read whatever is currently available, growing up to SNIFF_SCAN_BYTES.
        // peek() returns the number of bytes read, or RESULT_END_OF_INPUT.
        while (filled < buf.length) {
            int read = input.peek(buf, filled, buf.length - filled);
            if (read == C.RESULT_END_OF_INPUT) {
                break;
            }
            if (read <= 0) {
                // Defensive: never spin.
                break;
            }
            filled += read;
        }
        input.resetPeekPosition();
        if (filled < 4) return -1;

        for (int i = 0; i <= filled - 4; i++) {
            if (buf[i] == 0x00 && buf[i + 1] == 0x00
                    && buf[i + 2] == 0x01 && (buf[i + 3] & 0xFF) == 0xBA) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void init(ExtractorOutput output) {
        this.output = output;
    }

    @Override
    public void seek(long position, long timeUs) {
        // Always reset the timestamp adjuster to the seek target time.
        // The adjuster computes: offset = timeUs - firstPTS, so all samples
        // after seeking get adjusted timestamps starting at timeUs.
        // Without this, ExoPlayer's sample queue discards samples whose
        // adjusted timestamps don't match the seek position.
        timestampAdjuster.reset(timeUs);

        // After seeking to a non-zero position, we need to resync to the next
        // PACK_START_CODE since LinearPsSeekMap byte positions are approximate.
        needsResync = (position > 0);
        if (DEBUG) Log.d(TAG, "seek() called: position=" + position + ", timeUs=" + timeUs
                + ", reset timestampAdjuster to " + timeUs + ", needsResync=" + needsResync);

        for (int i = 0; i < psPayloadReaders.size(); i++) {
            psPayloadReaders.valueAt(i).seek();
        }
    }

    @Override
    public void release() {
        // Do nothing
    }

    @Override
    public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
        Assertions.checkStateNotNull(output); // Asserts init has been called.

        long inputLength = input.getLength();
        boolean canReadDuration = inputLength != C.LENGTH_UNSET;
        if (canReadDuration && !durationReader.isDurationReadFinished()) {
            // Store the input length from the duration-reading phase so we can
            // use it for seek map creation even if later opens report LENGTH_UNSET
            // (which happens for timeshifted/live content after preparation).
            durationPhaseInputLength = inputLength;
            return durationReader.readDuration(input, seekPosition);
        }
        // Use the stored duration-phase length for seek map if current is unknown
        long seekMapLength = (inputLength != C.LENGTH_UNSET) ? inputLength : durationPhaseInputLength;
        maybeOutputSeekMap(seekMapLength);

        // After a seek to a non-zero position, scan forward efficiently to find
        // the next PACK_START_CODE. The LinearPsSeekMap provides approximate byte
        // positions that may not land exactly on a pack header.
        if (needsResync) {
            if (DEBUG) Log.d(TAG, "Resync starting at input position " + input.getPosition());
            if (!resyncToPackStartCode(input)) {
                Log.w(TAG, "Resync failed - END_OF_INPUT");
                return RESULT_END_OF_INPUT;
            }
            if (DEBUG) Log.d(TAG, "Resync complete, now at position " + input.getPosition());
            needsResync = false;
        }

        input.resetPeekPosition();
        long peekBytesLeft =
                inputLength != C.LENGTH_UNSET ? inputLength - input.getPeekPosition() : C.LENGTH_UNSET;
        if (peekBytesLeft != C.LENGTH_UNSET && peekBytesLeft < 4) {
            return RESULT_END_OF_INPUT;
        }
        // First peek and check what type of start code is next.
        if (!input.peekFully(psPacketBuffer.getData(), 0, 4, true)) {
            return RESULT_END_OF_INPUT;
        }

        psPacketBuffer.setPosition(0);
        int nextStartCode = psPacketBuffer.readInt();
        if (nextStartCode == MPEG_PROGRAM_END_CODE) {
            return RESULT_END_OF_INPUT;
        } else if (nextStartCode == PACK_START_CODE) {
            // Now peek the rest of the pack_header.
            input.peekFully(psPacketBuffer.getData(), 0, 10);

            // We only care about the pack_stuffing_length in here, skip the first 77 bits.
            psPacketBuffer.setPosition(9);

            // Last 3 bits is the length.
            int packStuffingLength = psPacketBuffer.readUnsignedByte() & 0x07;

            // Now skip the stuffing and the pack header.
            input.skipFully(packStuffingLength + 14);
            return RESULT_CONTINUE;
        } else if (nextStartCode == SYSTEM_HEADER_START_CODE) {
            // We just skip all this, but we need to get the length first.
            input.peekFully(psPacketBuffer.getData(), 0, 2);

            // Length is the next 2 bytes.
            psPacketBuffer.setPosition(0);
            int systemHeaderLength = psPacketBuffer.readUnsignedShort();
            input.skipFully(systemHeaderLength + 6);
            return RESULT_CONTINUE;
        } else if (((nextStartCode & 0xFFFFFF00) >> 8) != PACKET_START_CODE_PREFIX) {
            input.skipFully(1); // Skip bytes until we see a valid start code again.
            return RESULT_CONTINUE;
        }

        // We're at the start of a regular PES packet now.
        // Get the stream ID off the last byte of the start code.
        int streamId = nextStartCode & 0xFF;

        // Check to see if we have this one in our map yet, and if not, then add it.
        PesReader payloadReader = psPayloadReaders.get(streamId);
        if (!foundAllTracks) {
            if (payloadReader == null) {
                @Nullable ElementaryStreamReader elementaryStreamReader = null;
                if (streamId == PRIVATE_STREAM_1) {
                    // SAGE CHANGE: Use PrivateStream1Demuxer instead of bare Ac3Reader.
                    // This properly handles files with multiple AC3/EAC3 sub-streams
                    // that share the same PES stream ID 0xBD.
                    elementaryStreamReader = new PrivateStream1Demuxer();
                    foundAudioTrack = true;
                    lastTrackPosition = input.getPosition();
                } else if ((streamId & AUDIO_STREAM_MASK) == AUDIO_STREAM) {
                    elementaryStreamReader = new MpegAudioReader();
                    foundAudioTrack = true;
                    lastTrackPosition = input.getPosition();
                } else if ((streamId & VIDEO_STREAM_MASK) == VIDEO_STREAM) {
                    elementaryStreamReader = new H262Reader();
                    foundVideoTrack = true;
                    lastTrackPosition = input.getPosition();
                }
                if (elementaryStreamReader != null) {
                    TrackIdGenerator idGenerator = new TrackIdGenerator(streamId, MAX_STREAM_ID_PLUS_ONE);
                    elementaryStreamReader.createTracks(output, idGenerator);
                    payloadReader = new PesReader(elementaryStreamReader, timestampAdjuster);
                    psPayloadReaders.put(streamId, payloadReader);
                }
            }
            long maxSearchPosition =
                    foundAudioTrack && foundVideoTrack
                            ? lastTrackPosition + MAX_SEARCH_LENGTH_AFTER_AUDIO_AND_VIDEO_FOUND
                            : MAX_SEARCH_LENGTH;
            if (input.getPosition() > maxSearchPosition) {
                foundAllTracks = true;
                output.endTracks();
            }
        }

        // The next 2 bytes are the length. Once we have that we can consume the complete packet.
        input.peekFully(psPacketBuffer.getData(), 0, 2);
        psPacketBuffer.setPosition(0);
        int payloadLength = psPacketBuffer.readUnsignedShort();
        int pesLength = payloadLength + 6;

        if (payloadReader == null) {
            // Just skip this data.
            input.skipFully(pesLength);
        } else {
            psPacketBuffer.reset(pesLength);
            // Read the whole packet and the header for consumption.
            input.readFully(psPacketBuffer.getData(), 0, pesLength);
            psPacketBuffer.setPosition(6);
            payloadReader.consume(psPacketBuffer);
            psPacketBuffer.setLimit(psPacketBuffer.capacity());
        }

        return RESULT_CONTINUE;
    }

    // Internals.

    /**
     * Scans forward from the current input position to find the next PACK_START_CODE
     * (0x000001BA). Reads up to 64KB in one pass. Returns true if found (input is
     * positioned at the start code), false if end of input reached.
     */
    private boolean resyncToPackStartCode(ExtractorInput input) throws IOException {
        byte[] buf = new byte[8192];
        int maxScans = 8; // 8 * 8KB = 64KB max scan distance
        for (int scan = 0; scan < maxScans; scan++) {
            int bytesRead = buf.length;
            long remaining = input.getLength() != C.LENGTH_UNSET
                    ? input.getLength() - input.getPosition() : Long.MAX_VALUE;
            if (remaining <= 0) return false;
            if (remaining < bytesRead) bytesRead = (int) remaining;

            if (!input.peekFully(buf, 0, bytesRead, true)) {
                if (DEBUG) Log.w(TAG, "resync: peekFully returned false at scan " + scan);
                return false;
            }
            for (int i = 0; i < bytesRead - 3; i++) {
                if (buf[i] == 0x00 && buf[i + 1] == 0x00
                        && buf[i + 2] == 0x01 && (buf[i + 3] & 0xFF) == 0xBA) {
                    // Found PACK_START_CODE. Skip to this position.
                    input.resetPeekPosition();
                    input.skipFully(i);
                    if (DEBUG) Log.d(TAG, "resync: found PACK_START_CODE at offset +" + i + " in scan " + scan
                            + ", new position=" + input.getPosition());
                    return true;
                }
            }
            // Didn't find it in this chunk. Advance past it (minus 3 for overlap).
            input.resetPeekPosition();
            input.skipFully(bytesRead - 3);
            if (DEBUG) Log.d(TAG, "resync: scan " + scan + " no pack header, skipped to " + input.getPosition());
        }
        // Fell through without finding a pack start code in 64KB — unlikely but
        // let the normal read loop handle byte-by-byte from here.
        return true;
    }

    @RequiresNonNull("output")
    private void maybeOutputSeekMap(long inputLength) {
        if (!hasOutputSeekMap) {
            hasOutputSeekMap = true;
            long durationUs = durationReader.getDurationUs();
            if (inputLength != C.LENGTH_UNSET) {
                // Always provide a seekable linear map. If the duration reader
                // failed (common with SageTV MPEG-PS recordings where SCR
                // timestamps are unreliable), estimate duration from file size
                // assuming ~5 Mbps (625000 bytes/sec). The two-clock middleware
                // handles any positional inaccuracy from the estimate.
                if (durationUs == C.TIME_UNSET) {
                    durationUs = inputLength * 1000000L / 625000L; // estimate ~5 Mbps
                }
                output.seekMap(new LinearPsSeekMap(durationUs, inputLength, instanceLiveSizeProvider));
            } else {
                output.seekMap(new SeekMap.Unseekable(durationUs));
            }
        }
    }

    /**
     * A simple SeekMap that maps time to byte position using linear interpolation.
     * Byte positions are aligned to 2048-byte boundaries (MPEG-PS pack size).
     *
     * <p>For live/growing recordings, consults the supplied
     * {@link LiveSizeProvider} to use the current file size, and reports a
     * duration that scales with the size via the constant bitrate measured
     * during preparation. This ensures FF/REW (e.g. comskip jumps) on a
     * still-recording show maps to correct byte positions instead of getting
     * clamped to the original (small) duration.
     */
    /* package */ static final class LinearPsSeekMap implements SeekMap {
        private final long initialDurationUs;
        private final long initialFileSize;
        private final double bytesPerUs;
        private final LiveSizeProvider liveSizeProvider;

        LinearPsSeekMap(long durationUs, long fileSize, LiveSizeProvider liveSizeProvider) {
            this.initialDurationUs = durationUs;
            this.initialFileSize = fileSize;
            this.bytesPerUs = (durationUs > 0) ? (double) fileSize / durationUs : 0.0;
            this.liveSizeProvider = liveSizeProvider;
        }

        /** Returns current file size, dynamically queried for live recordings. */
        private long currentFileSize() {
            LiveSizeProvider p = liveSizeProvider;
            if (p != null) {
                long sz = p.getCurrentSize();
                if (sz > initialFileSize) return sz;
            }
            return initialFileSize;
        }

        /** Returns current duration scaled with current file size (constant bitrate). */
        private long currentDurationUs() {
            long size = currentFileSize();
            if (size == initialFileSize || bytesPerUs <= 0) return initialDurationUs;
            return (long) (size / bytesPerUs);
        }

        @Override
        public boolean isSeekable() {
            return true;
        }

        @Override
        public long getDurationUs() {
            return currentDurationUs();
        }

        @Override
        public SeekMap.SeekPoints getSeekPoints(long timeUs) {
            long durationUs = currentDurationUs();
            long fileSize = currentFileSize();
            if (timeUs <= 0) {
                return new SeekMap.SeekPoints(new SeekPoint(0, 0));
            }
            if (timeUs >= durationUs) {
                return new SeekMap.SeekPoints(new SeekPoint(durationUs, fileSize));
            }
            long bytePos = (long) ((double) timeUs / durationUs * fileSize);
            // Align to 2048-byte boundary (typical MPEG-PS pack size)
            bytePos = (bytePos / 2048) * 2048;
            return new SeekMap.SeekPoints(new SeekPoint(timeUs, bytePos));
        }
    }

    /** Parses PES packet data and extracts samples. */
    private static final class PesReader {

        private static final int PES_SCRATCH_SIZE = 64;

        private final ElementaryStreamReader pesPayloadReader;
        private final TimestampAdjuster timestampAdjuster;
        private final ParsableBitArray pesScratch;

        private boolean ptsFlag;
        private boolean dtsFlag;
        private boolean seenFirstDts;
        private int extendedHeaderLength;
        private long timeUs;

        public PesReader(ElementaryStreamReader pesPayloadReader,
                         TimestampAdjuster timestampAdjuster) {
            this.pesPayloadReader = pesPayloadReader;
            this.timestampAdjuster = timestampAdjuster;
            pesScratch = new ParsableBitArray(new byte[PES_SCRATCH_SIZE]);
        }

        /**
         * Notifies the reader that a seek has occurred.
         *
         * <p>Following a call to this method, the data passed to the next invocation of {@link
         * #consume(ParsableByteArray)} will not be a continuation of the data that was previously
         * passed. Hence the reader should reset any internal state.
         */
        public void seek() {
            seenFirstDts = false;
            pesPayloadReader.seek();
        }

        /**
         * Consumes the payload of a PS packet.
         *
         * @param data The PES packet. The position will be set to the start of the payload.
         * @throws ParserException If the payload could not be parsed.
         */
        public void consume(ParsableByteArray data) throws ParserException {
            data.readBytes(pesScratch.data, 0, 3);
            pesScratch.setPosition(0);
            parseHeader();
            data.readBytes(pesScratch.data, 0, extendedHeaderLength);
            pesScratch.setPosition(0);
            parseHeaderExtension();
            pesPayloadReader.packetStarted(timeUs, TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR);
            pesPayloadReader.consume(data);
            // We always have complete PES packets with program stream.
            pesPayloadReader.packetFinished();
        }

        private void parseHeader() {
            // Note: see ISO/IEC 13818-1, section 2.4.3.6 for detailed information on the format of
            // the header.
            // First 8 bits are skipped: '10' (2), PES_scrambling_control (2), PES_priority (1),
            // data_alignment_indicator (1), copyright (1), original_or_copy (1)
            pesScratch.skipBits(8);
            ptsFlag = pesScratch.readBit();
            dtsFlag = pesScratch.readBit();
            // ESCR_flag (1), ES_rate_flag (1), DSM_trick_mode_flag (1),
            // additional_copy_info_flag (1), PES_CRC_flag (1), PES_extension_flag (1)
            pesScratch.skipBits(6);
            extendedHeaderLength = pesScratch.readBits(8);
        }

        private void parseHeaderExtension() {
            timeUs = C.TIME_UNSET;
            if (ptsFlag) {
                pesScratch.skipBits(4); // '0010' or '0011'
                long pts = (long) pesScratch.readBits(3) << 30;
                pesScratch.skipBits(1); // marker_bit
                pts |= pesScratch.readBits(15) << 15;
                pesScratch.skipBits(1); // marker_bit
                pts |= pesScratch.readBits(15);
                pesScratch.skipBits(1); // marker_bit
                if (!seenFirstDts && dtsFlag) {
                    pesScratch.skipBits(4); // '0011'
                    long dts = (long) pesScratch.readBits(3) << 30;
                    pesScratch.skipBits(1); // marker_bit
                    dts |= pesScratch.readBits(15) << 15;
                    pesScratch.skipBits(1); // marker_bit
                    dts |= pesScratch.readBits(15);
                    pesScratch.skipBits(1); // marker_bit
                    // Subsequent PES packets may have earlier presentation timestamps than this one, but they
                    // should all be greater than or equal to this packet's decode timestamp. We feed the
                    // decode timestamp to the adjuster here so that in the case that this is the first to be
                    // fed, the adjuster will be able to compute an offset to apply such that the adjusted
                    // presentation timestamps of all future packets are non-negative.
                    timestampAdjuster.adjustTsTimestamp(dts);
                    seenFirstDts = true;
                }
                timeUs = timestampAdjuster.adjustTsTimestamp(pts);
            }
        }
    }
}
