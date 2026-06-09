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

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight MPEG-TS probe that flags streams likely to trigger
 * Exo Transformer "format changes are not supported" failures.
 */
final class TsHealthClassifier {

    private static final Logger log = LoggerFactory.getLogger(TsHealthClassifier.class);
    private static final int TS_PACKET_SIZE = 188;
    private static final int PROBE_BYTES = 24 * 1024 * 1024;

    private TsHealthClassifier() {}

    static boolean shouldPreferFfmpeg(Context context, Uri source, String mediaFileId) {
        try {
            ProbeResult r = probe(context, source);
            boolean unstable = r.uniquePmtSignatures > 1 
                    || r.pidTypeFlipCount > 0
                    || r.hasAc3
                    || r.programStream
                    || r.audioVideoAudioPidCount > 1;
            if (unstable) {
                log.warn("ts_health_classifier_flagged mediaFileID={} pmtSignatures={} pidTypeFlips={} ac3={} programStream={} multiplePids={}",
                    mediaFileId, r.uniquePmtSignatures, r.pidTypeFlipCount, r.hasAc3, r.programStream, r.audioVideoAudioPidCount);
            } else {
                log.info("ts_health_classifier_ok mediaFileID={} pmtSignatures={} pidTypeFlips={} ac3={} programStream={} multiplePids={}",
                    mediaFileId, r.uniquePmtSignatures, r.pidTypeFlipCount, r.hasAc3, r.programStream, r.audioVideoAudioPidCount);
            }
            return unstable;
        } catch (Throwable t) {
            log.warn("ts_health_classifier_probe_failed mediaFileID={} cause={}",
                    mediaFileId, t.toString());
            return false;
        }
    }

    private static ProbeResult probe(Context context, Uri source) throws IOException {
        ProbeResult out = new ProbeResult();
        Map<Integer, Integer> pidToType = new HashMap<>();
        Set<Integer> pmtPids = new HashSet<>();
        Set<String> pmtSignatures = new HashSet<>();

        try (InputStream raw = openSourceStream(context, source);
             BufferedInputStream in = new BufferedInputStream(raw, 256 * 1024)) {

            byte[] packet = new byte[TS_PACKET_SIZE];
            int scanned = 0;
            while (scanned + TS_PACKET_SIZE <= PROBE_BYTES && readFully(in, packet, 0, TS_PACKET_SIZE)) {
                scanned += TS_PACKET_SIZE;
                if (scanned == TS_PACKET_SIZE && isMpegProgramStream(packet)) {
                    out.programStream = true;
                    break;
                }
                if ((packet[0] & 0xFF) != 0x47) {
                    continue;
                }
                boolean payloadStart = (packet[1] & 0x40) != 0;
                int pid = ((packet[1] & 0x1F) << 8) | (packet[2] & 0xFF);
                int afc = (packet[3] >> 4) & 0x3;
                if (afc == 0 || afc == 2) {
                    continue;
                }

                int offs = 4;
                if (afc == 3) {
                    int afl = packet[4] & 0xFF;
                    offs += 1 + afl;
                }
                if (offs >= TS_PACKET_SIZE) {
                    continue;
                }

                if (pid == 0 && payloadStart) {
                    parsePat(packet, offs, pmtPids);
                } else if (pmtPids.contains(pid) && payloadStart) {
                    String sig = parsePmt(packet, offs, pidToType, out);
                    if (sig != null && !sig.isEmpty()) {
                        pmtSignatures.add(sig);
                    }
                }
            }
        }

        out.uniquePmtSignatures = pmtSignatures.size();
        return out;
    }

    private static void parsePat(byte[] packet, int payloadOffset, Set<Integer> pmtPids) {
        int ptr = packet[payloadOffset] & 0xFF;
        int secStart = payloadOffset + 1 + ptr;
        if (secStart + 8 >= TS_PACKET_SIZE) return;
        if ((packet[secStart] & 0xFF) != 0x00) return;

        int sectionLen = ((packet[secStart + 1] & 0x0F) << 8) | (packet[secStart + 2] & 0xFF);
        int end = Math.min(secStart + 3 + sectionLen - 4, TS_PACKET_SIZE);

        int i = secStart + 8;
        while (i + 4 <= end) {
            int program = ((packet[i] & 0xFF) << 8) | (packet[i + 1] & 0xFF);
            int pid = ((packet[i + 2] & 0x1F) << 8) | (packet[i + 3] & 0xFF);
            if (program != 0) {
                pmtPids.add(pid);
            }
            i += 4;
        }
    }

    private static String parsePmt(byte[] packet,
                                   int payloadOffset,
                                   Map<Integer, Integer> pidToType,
                                   ProbeResult out) {
        int ptr = packet[payloadOffset] & 0xFF;
        int secStart = payloadOffset + 1 + ptr;
        if (secStart + 12 >= TS_PACKET_SIZE) return null;
        if ((packet[secStart] & 0xFF) != 0x02) return null;

        int sectionLen = ((packet[secStart + 1] & 0x0F) << 8) | (packet[secStart + 2] & 0xFF);
        int programInfoLen = ((packet[secStart + 10] & 0x0F) << 8) | (packet[secStart + 11] & 0xFF);

        int end = Math.min(secStart + 3 + sectionLen - 4, TS_PACKET_SIZE);
        int i = secStart + 12 + programInfoLen;

        StringBuilder sig = new StringBuilder();
        int videoPidCount = 0;
        int audioPidCount = 0;
        while (i + 5 <= end) {
            int streamType = packet[i] & 0xFF;
            int elemPid = ((packet[i + 1] & 0x1F) << 8) | (packet[i + 2] & 0xFF);
            int esInfoLen = ((packet[i + 3] & 0x0F) << 8) | (packet[i + 4] & 0xFF);

            Integer prev = pidToType.put(elemPid, streamType);
            if (prev != null && prev != streamType) {
                out.pidTypeFlipCount++;
            }

            // Detect AC3 (0x81) and count video/audio PIDs
            if (streamType == 0x81) {
                out.hasAc3 = true;
            }
            if (isVideoType(streamType)) {
                videoPidCount++;
            } else if (isAudioType(streamType)) {
                audioPidCount++;
            }

            sig.append(elemPid).append(':').append(streamType).append(';');
            i += 5 + esInfoLen;
        }
        out.audioVideoAudioPidCount = Math.max(videoPidCount, audioPidCount);
        return sig.toString();
    }

    private static InputStream openSourceStream(Context context, Uri source) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        InputStream in = resolver.openInputStream(source);
        if (in == null) {
            throw new IOException("Cannot open source stream: " + source);
        }
        return in;
    }

    private static boolean readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = in.read(b, off + read, len - read);
            if (n < 0) return false;
            read += n;
        }
        return true;
    }

    private static boolean isVideoType(int streamType) {
        // Video: H.264 (0x1B), H.265 (0x24), MPEG-2 (0x02)
        return streamType == 0x1B || streamType == 0x24 || streamType == 0x02;
    }

    private static boolean isAudioType(int streamType) {
        // Audio: AAC (0x0F), AC3 (0x81), MP3 (0x03), etc.
        return streamType == 0x0F || streamType == 0x81 || streamType == 0x03
                || streamType == 0x04 || streamType == 0x06;
    }

    private static boolean isMpegProgramStream(byte[] packet) {
        return packet != null && packet.length >= 4
                && (packet[0] & 0xFF) == 0x00
                && (packet[1] & 0xFF) == 0x00
                && (packet[2] & 0xFF) == 0x01
                && (((packet[3] & 0xFF) == 0xBA) || ((packet[3] & 0xFF) == 0xBB));
    }

    private static final class ProbeResult {
        int uniquePmtSignatures;
        int pidTypeFlipCount;
        boolean hasAc3;
        boolean programStream;
        int audioVideoAudioPidCount;  // Max of video/audio PID counts
    }
}
