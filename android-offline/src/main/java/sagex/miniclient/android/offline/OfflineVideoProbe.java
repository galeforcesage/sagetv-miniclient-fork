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

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Stateless helpers for inspecting downloaded video files. Used by both
 * {@link OfflinePlaybackActivity} (engine selection) and
 * {@link PostDownloadRemux} (skip remux when the source codec already
 * forces the IJK lane). Centralizing the probe ensures both code paths
 * see the same answer for a given recording.
 */
final class OfflineVideoProbe {

    private static final Logger log = LoggerFactory.getLogger(OfflineVideoProbe.class);

    private OfflineVideoProbe() {}

    /**
     * Uses Android's built-in {@link MediaExtractor} (stagefright) to read
     * the first video track's MIME type. Works for MKV, MP4, TS and other
     * containers the platform supports without depending on bundled ffmpeg.
     * Returns {@code null} on probe failure or if no video track is found.
     */
    static String probeFirstVideoMime(Context ctx, Uri uri) {
        if (ctx == null || uri == null) return null;
        MediaExtractor extractor = new MediaExtractor();
        try {
            String scheme = uri.getScheme();
            if ("content".equals(scheme)) {
                extractor.setDataSource(ctx, uri, null);
            } else if (scheme == null || "file".equals(scheme)) {
                String path = uri.getPath();
                if (path == null) return null;
                extractor.setDataSource(path);
            } else {
                extractor.setDataSource(uri.toString());
            }
            int n = extractor.getTrackCount();
            for (int i = 0; i < n; i++) {
                MediaFormat fmt = extractor.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    return mime;
                }
            }
        } catch (Throwable t) {
            log.warn("offline_video_extractor_probe_failed uri={} err={}", uri, t.toString());
        } finally {
            try { extractor.release(); } catch (Throwable ignored) { }
        }
        return null;
    }

    /**
     * Recognizes the various MIME spellings stagefright uses for MPEG-1/2
     * elementary streams across Android versions and container types.
     */
    static boolean isMpeg2VideoMime(String mime) {
        if (mime == null) return false;
        String m = mime.toLowerCase(Locale.US);
        return m.equals("video/mpeg2")
                || m.equals("video/mpeg2video")
                || m.equals("video/mpeg2-video")
                || m.equals("video/mpeg");
    }
}
