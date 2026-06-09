/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package sagex.miniclient.android.offline;

import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.transformer.InAppMuxer;
import com.google.android.exoplayer2.transformer.Muxer;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.common.collect.ImmutableList;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link Muxer.Factory} that delegates to {@link InAppMuxer} (which writes
 * MP4 with 64-bit largesize boxes, eliminating the 4&nbsp;GB cap of the
 * framework {@code MediaMuxer}) but first converts H.264 from
 * <strong>Annex B</strong> (start-code delimited NAL units, as emitted by
 * ExoPlayer's TS / PS extractors) to <strong>AVCC</strong> (length-prefixed,
 * with an avcC configuration record) — the format InAppMuxer / Mp4Muxer
 * requires.
 *
 * <p>Non-H.264 tracks (audio, H.265, etc.) are passed through unchanged.
 */
final class AvccMuxerFactory implements Muxer.Factory {

    private final InAppMuxer.Factory delegate = new InAppMuxer.Factory();

    @Override
    public Muxer create(String path) throws Muxer.MuxerException {
        return new AvccMuxer(delegate.create(path));
    }

    @Override
    public ImmutableList<String> getSupportedSampleMimeTypes(int trackType) {
        return delegate.getSupportedSampleMimeTypes(trackType);
    }

    // -------------------------------------------------------------------------
    // Wrapping muxer
    // -------------------------------------------------------------------------

    private static final class AvccMuxer implements Muxer {
        private final Muxer inner;
        /** Track indexes (as returned by {@link #addTrack}) that require Annex B → AVCC sample rewriting. */
        private final Set<Integer> avcTracks = new HashSet<>();
        private final Map<Integer, TrackState> tracks = new HashMap<>();
        private int nextTrackIndex = 0;

        private static final int MAX_PENDING_SAMPLES = 128;

        private static final class PendingSample {
            final byte[] data;
            final long presentationTimeUs;
            final int flags;

            PendingSample(byte[] data, long presentationTimeUs, int flags) {
                this.data = data;
                this.presentationTimeUs = presentationTimeUs;
                this.flags = flags;
            }
        }

        private static final class TrackState {
            final Format originalFormat;
            Integer innerTrackIndex;
            boolean rewriteSamples;
            final Deque<PendingSample> pendingSamples = new ArrayDeque<>();
            final List<byte[]> pendingSps = new ArrayList<>();
            final List<byte[]> pendingPps = new ArrayList<>();

            TrackState(Format originalFormat) {
                this.originalFormat = originalFormat;
            }
        }

        AvccMuxer(Muxer inner) {
            this.inner = inner;
        }

        @Override
        public int addTrack(Format format) throws MuxerException {
            int outerTrackIndex = nextTrackIndex++;
            if (isH264Format(format)
                    && !format.initializationData.isEmpty()) {
                AvcInitInfo info = buildAvcCFromInitData(format.initializationData);
                if (info != null && info.sps != null && info.pps != null) {
                    List<byte[]> newInit = buildCsdInitData(info.sps, info.pps);
                    Format rewritten = format.buildUpon()
                            .setInitializationData(newInit)
                            .build();
                    int idx = inner.addTrack(rewritten);
                    TrackState state = new TrackState(format);
                    state.innerTrackIndex = idx;
                    state.rewriteSamples = info.samplesAreAnnexB;
                    tracks.put(outerTrackIndex, state);
                    if (info.samplesAreAnnexB) {
                        avcTracks.add(outerTrackIndex);
                    }
                    return outerTrackIndex;
                }
            }

            if (isH264Format(format)) {
                TrackState state = new TrackState(format);
                state.rewriteSamples = true;
                tracks.put(outerTrackIndex, state);
                avcTracks.add(outerTrackIndex);
                return outerTrackIndex;
            }

            TrackState state = new TrackState(format);
            state.innerTrackIndex = inner.addTrack(format);
            state.rewriteSamples = false;
            tracks.put(outerTrackIndex, state);
            return outerTrackIndex;
        }

        @Override
        public void writeSampleData(int trackIndex, ByteBuffer data,
                                    long presentationTimeUs, int flags) throws MuxerException {
            TrackState state = tracks.get(trackIndex);
            if (state == null) {
                throw new IllegalStateException("Unknown track index " + trackIndex);
            }

            if (state.innerTrackIndex == null) {
                byte[] sampleBytes = copyRemainingBytes(data);
                state.pendingSamples.addLast(new PendingSample(sampleBytes, presentationTimeUs, flags));

                collectParameterSetsFromAnnexBSample(sampleBytes, state.pendingSps, state.pendingPps);
                byte[] avcC = null;
                if (!state.pendingSps.isEmpty() && !state.pendingPps.isEmpty()) {
                    avcC = buildAvcCFromParameterSets(state.pendingSps, state.pendingPps);
                }
                if (avcC == null) {
                    if (state.pendingSamples.size() > MAX_PENDING_SAMPLES) {
                        throw new IllegalStateException(
                                "H264 remux could not derive SPS/PPS from init data or early samples");
                    }
                    return;
                }

                List<byte[]> newInit = buildCsdInitData(
                    state.pendingSps.get(0),
                    state.pendingPps.get(0));
                Format rewritten = state.originalFormat.buildUpon()
                        .setInitializationData(newInit)
                        .build();
                state.innerTrackIndex = inner.addTrack(rewritten);
                flushPendingSamples(state);
                return;
            }

            if (!avcTracks.contains(trackIndex) || !state.rewriteSamples) {
                inner.writeSampleData(state.innerTrackIndex, data, presentationTimeUs, flags);
                return;
            }

            ByteBuffer rewritten = annexBSampleToAvcc(data);
            inner.writeSampleData(state.innerTrackIndex, rewritten, presentationTimeUs, flags);
        }

        @Override
        public void addMetadata(Metadata metadata) {
            inner.addMetadata(metadata);
        }

        @Override
        public void release(boolean forCancellation) throws MuxerException {
            if (!forCancellation) {
                for (TrackState state : tracks.values()) {
                    if (state.innerTrackIndex == null && !state.pendingSamples.isEmpty()) {
                        throw new IllegalStateException(
                                "H264 remux ended before SPS/PPS became available for MP4 muxing");
                    }
                }
            }
            inner.release(forCancellation);
        }

        @Override
        public long getMaxDelayBetweenSamplesMs() {
            return inner.getMaxDelayBetweenSamplesMs();
        }

        private void flushPendingSamples(TrackState state) throws MuxerException {
            while (!state.pendingSamples.isEmpty()) {
                PendingSample sample = state.pendingSamples.removeFirst();
                ByteBuffer buffer = ByteBuffer.wrap(sample.data);
                if (state.rewriteSamples) {
                    buffer = annexBSampleToAvcc(buffer);
                }
                inner.writeSampleData(state.innerTrackIndex,
                        buffer, sample.presentationTimeUs, sample.flags);
            }
        }
    }

    // -------------------------------------------------------------------------
    // csd-0 conversion: Annex B (SPS+PPS start-coded) → AVCC (avcC box payload)
    // -------------------------------------------------------------------------

    private static final class AvcInitInfo {
        final byte[] sps;
        final byte[] pps;
        final boolean samplesAreAnnexB;

        AvcInitInfo(byte[] sps, byte[] pps, boolean samplesAreAnnexB) {
            this.sps = sps;
            this.pps = pps;
            this.samplesAreAnnexB = samplesAreAnnexB;
        }
    }

    private static AvcInitInfo buildAvcCFromInitData(List<byte[]> initData) {
        if (initData == null || initData.isEmpty()) return null;

        // If avcC is present, extract SPS/PPS from it.
        byte[] first = initData.get(0);
        if (looksLikeAvcC(first)) {
            List<byte[]> sets = parseAvcCParameterSets(first);
            if (sets.size() >= 2) {
                return new AvcInitInfo(sets.get(0), sets.get(1), false);
            }
        }

        boolean sawAnnexB = false;
        List<byte[]> sps = new ArrayList<>();
        List<byte[]> pps = new ArrayList<>();

        for (byte[] entry : initData) {
            if (entry == null || entry.length == 0) continue;
            if (startsWithAnnexBStartCode(entry)) {
                sawAnnexB = true;
                List<byte[]> nals = splitAnnexBNals(entry);
                for (byte[] nal : nals) {
                    if (nal.length == 0) continue;
                    int nalType = nal[0] & 0x1F;
                    if (nalType == 7) sps.add(nal);
                    else if (nalType == 8) pps.add(nal);
                }
                continue;
            }

            // Some extractors expose raw SPS/PPS without start code.
            int nalType = entry[0] & 0x1F;
            if (nalType == 7) sps.add(entry);
            else if (nalType == 8) pps.add(entry);
        }

        if (sps.isEmpty() || pps.isEmpty()) return null;
        return new AvcInitInfo(sps.get(0), pps.get(0), sawAnnexB);
    }

    private static List<byte[]> buildCsdInitData(byte[] sps, byte[] pps) {
        List<byte[]> out = new ArrayList<>(2);
        out.add(withAnnexBStartCode(sps));
        out.add(withAnnexBStartCode(pps));
        return out;
    }

    private static byte[] withAnnexBStartCode(byte[] nal) {
        if (nal == null || nal.length == 0) return nal;
        if (startsWithAnnexBStartCode(nal)) return nal;
        byte[] out = new byte[nal.length + 4];
        out[0] = 0;
        out[1] = 0;
        out[2] = 0;
        out[3] = 1;
        System.arraycopy(nal, 0, out, 4, nal.length);
        return out;
    }

    private static List<byte[]> parseAvcCParameterSets(byte[] avcC) {
        List<byte[]> out = new ArrayList<>(2);
        if (avcC == null || avcC.length < 7) return out;
        int pos = 5;
        int spsCount = avcC[pos] & 0x1F;
        pos++;
        for (int i = 0; i < spsCount; i++) {
            if (pos + 2 > avcC.length) return out;
            int len = ((avcC[pos] & 0xFF) << 8) | (avcC[pos + 1] & 0xFF);
            pos += 2;
            if (len <= 0 || pos + len > avcC.length) return out;
            byte[] sps = new byte[len];
            System.arraycopy(avcC, pos, sps, 0, len);
            out.add(sps);
            pos += len;
        }
        if (pos >= avcC.length) return out;
        int ppsCount = avcC[pos] & 0xFF;
        pos++;
        for (int i = 0; i < ppsCount; i++) {
            if (pos + 2 > avcC.length) return out;
            int len = ((avcC[pos] & 0xFF) << 8) | (avcC[pos + 1] & 0xFF);
            pos += 2;
            if (len <= 0 || pos + len > avcC.length) return out;
            byte[] pps = new byte[len];
            System.arraycopy(avcC, pos, pps, 0, len);
            out.add(pps);
            pos += len;
        }
        return out;
    }

    /**
     * Returns avcC (ISO/IEC 14496-15) configuration record bytes built from
     * an Annex B csd-0 that concatenates SPS and PPS NAL units delimited by
     * start codes (0x00000001 or 0x000001). Returns {@code null} when the
     * input doesn't look like Annex B (so the caller leaves the original
     * csd alone and lets the downstream muxer reject it loudly instead of
     * us emitting a silently-bad avcC).
     */
    static byte[] annexBCsdToAvcC(byte[] csd) {
        if (csd == null || csd.length < 8) return null;
        List<byte[]> nals = splitAnnexBNals(csd);
        if (nals.isEmpty()) return null;
        List<byte[]> sps = new ArrayList<>();
        List<byte[]> pps = new ArrayList<>();
        for (byte[] nal : nals) {
            if (nal.length == 0) continue;
            int nalType = nal[0] & 0x1F;
            if (nalType == 7) sps.add(nal);
            else if (nalType == 8) pps.add(nal);
        }
        if (sps.isEmpty() || pps.isEmpty()) return null;
        return buildAvcCFromParameterSets(sps, pps);
    }

    static byte[] buildAvcCFromParameterSets(List<byte[]> sps, List<byte[]> pps) {
        byte[] firstSps = sps.get(0);
        if (firstSps.length < 4) return null;

        int size = 7; // fixed header
        for (byte[] s : sps) size += 2 + s.length;
        size += 1;    // numOfPPS
        for (byte[] p : pps) size += 2 + p.length;

        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 0x01);            // configurationVersion
        buf.put(firstSps[1]);            // AVCProfileIndication
        buf.put(firstSps[2]);            // profile_compatibility
        buf.put(firstSps[3]);            // AVCLevelIndication
        buf.put((byte) 0xFF);            // 111111 (reserved) | lengthSizeMinusOne = 3 → 4-byte length prefixes
        buf.put((byte) (0xE0 | (sps.size() & 0x1F))); // 111 (reserved) | numOfSPS
        for (byte[] s : sps) {
            buf.putShort((short) s.length);
            buf.put(s);
        }
        buf.put((byte) (pps.size() & 0xFF));
        for (byte[] p : pps) {
            buf.putShort((short) p.length);
            buf.put(p);
        }
        return buf.array();
    }

    private static boolean looksLikeAvcC(byte[] data) {
        return data != null && data.length >= 7 && data[0] == 0x01;
    }

    private static boolean startsWithAnnexBStartCode(byte[] data) {
        return data != null && matchStartCode(data, 0) != 0;
    }

    private static boolean isH264Format(Format format) {
        if (format == null) return false;
        String mime = format.sampleMimeType;
        if (mime != null) {
            if (MimeTypes.VIDEO_H264.equals(mime)) return true;
            String lower = mime.toLowerCase();
            if (lower.contains("h264") || lower.contains("avc")) return true;
        }
        String codecs = format.codecs;
        if (codecs != null) {
            String lowerCodecs = codecs.toLowerCase();
            if (lowerCodecs.contains("avc1") || lowerCodecs.contains("avc3")
                    || lowerCodecs.contains("h264")) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Sample conversion: Annex B start codes → 4-byte big-endian length prefixes
    // -------------------------------------------------------------------------

    /**
     * Returns a new ByteBuffer containing {@code sample}'s NAL units in AVCC
     * form (each preceded by a 4-byte big-endian length). The position of
     * the returned buffer is 0 and its limit is the rewritten size.
     *
     * <p>If the input doesn't begin with an Annex B start code we assume
     * it's already in AVCC form (or some opaque format) and return it
     * untouched.
     */
    static ByteBuffer annexBSampleToAvcc(ByteBuffer sample) {
        int pos = sample.position();
        int limit = sample.limit();
        if (limit - pos < 4) return sample;
        // Quick check: must start with a start code (00 00 00 01 or 00 00 01).
        byte b0 = sample.get(pos);
        byte b1 = sample.get(pos + 1);
        byte b2 = sample.get(pos + 2);
        boolean startsWith4 = b0 == 0 && b1 == 0 && b2 == 0
                && sample.get(pos + 3) == 1;
        boolean startsWith3 = b0 == 0 && b1 == 0 && b2 == 1;
        if (!startsWith4 && !startsWith3) return sample;

        // Copy out so we can index linearly. ExoPlayer's sample buffers can
        // be direct or non-direct; the muxer accepts either, so we always
        // allocate non-direct here for simplicity.
        byte[] src = new byte[limit - pos];
        sample.get(src);
        sample.position(pos); // restore in case caller looks at it

        List<int[]> spans = new ArrayList<>(); // {nalStart, nalLength}
        int i = 0;
        int n = src.length;
        while (i < n) {
            int scLen = matchStartCode(src, i);
            if (scLen == 0) {
                // Malformed — bail out and pass through unchanged.
                return ByteBuffer.wrap(src);
            }
            int nalStart = i + scLen;
            int j = nalStart;
            // Find next start code.
            while (j < n) {
                if (matchStartCode(src, j) != 0) break;
                j++;
            }
            int nalLen = j - nalStart;
            if (nalLen > 0) spans.add(new int[]{nalStart, nalLen});
            i = j;
        }

        int outSize = 0;
        for (int[] s : spans) outSize += 4 + s[1];
        ByteBuffer out = ByteBuffer.allocate(outSize).order(ByteOrder.BIG_ENDIAN);
        for (int[] s : spans) {
            out.putInt(s[1]);
            out.put(src, s[0], s[1]);
        }
        out.flip();
        return out;
    }

    private static byte[] copyRemainingBytes(ByteBuffer sample) {
        int pos = sample.position();
        int limit = sample.limit();
        byte[] out = new byte[limit - pos];
        sample.get(out);
        sample.position(pos);
        return out;
    }

    /** Returns 4 if {@code src} starts with 00 00 00 01 at {@code i}, 3 if 00 00 01, else 0. */
    private static int matchStartCode(byte[] src, int i) {
        if (i + 3 < src.length
                && src[i] == 0 && src[i + 1] == 0 && src[i + 2] == 0 && src[i + 3] == 1) {
            return 4;
        }
        if (i + 2 < src.length
                && src[i] == 0 && src[i + 1] == 0 && src[i + 2] == 1) {
            return 3;
        }
        return 0;
    }

    /** Splits an Annex B byte stream into individual NAL unit payloads (start codes stripped). */
    static List<byte[]> splitAnnexBNals(byte[] data) {
        List<byte[]> out = new ArrayList<>();
        int i = 0;
        int n = data.length;
        int nalStart = -1;
        while (i < n) {
            int scLen = matchStartCode(data, i);
            if (scLen != 0) {
                if (nalStart >= 0) {
                    int len = i - nalStart;
                    if (len > 0) {
                        byte[] nal = new byte[len];
                        System.arraycopy(data, nalStart, nal, 0, len);
                        out.add(nal);
                    }
                }
                i += scLen;
                nalStart = i;
            } else {
                i++;
            }
        }
        if (nalStart >= 0 && nalStart < n) {
            int len = n - nalStart;
            byte[] nal = new byte[len];
            System.arraycopy(data, nalStart, nal, 0, len);
            out.add(nal);
        }
        return out;
    }

    private static void collectParameterSetsFromAnnexBSample(byte[] sample,
                                                              List<byte[]> spsOut,
                                                              List<byte[]> ppsOut) {
        if (sample == null || sample.length == 0) return;
        List<byte[]> nals = splitAnnexBNals(sample);
        for (byte[] nal : nals) {
            if (nal == null || nal.length == 0) continue;
            int nalType = nal[0] & 0x1F;
            if (nalType == 7) {
                addUniqueNal(spsOut, nal);
            } else if (nalType == 8) {
                addUniqueNal(ppsOut, nal);
            }
        }
    }

    private static void addUniqueNal(List<byte[]> list, byte[] candidate) {
        for (byte[] existing : list) {
            if (java.util.Arrays.equals(existing, candidate)) {
                return;
            }
        }
        list.add(candidate);
    }
}
