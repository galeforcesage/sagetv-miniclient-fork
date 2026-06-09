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
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.transformer.Composition;
import com.google.android.exoplayer2.transformer.ExportException;
import com.google.android.exoplayer2.transformer.ExportResult;
import com.google.android.exoplayer2.transformer.ProgressHolder;
import com.google.android.exoplayer2.transformer.TransformationException;
import com.google.android.exoplayer2.transformer.TransformationResult;
import com.google.android.exoplayer2.transformer.Transformer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Trans-muxes a SageTV MPEG-TS recording to MP4 (with a proper moov box)
 * so {@link com.google.android.exoplayer2.ExoPlayer} can seek and report
 * duration without the SAF / TS length-unknown limitations.
 *
 * <p>Trans-mux only — bitstream is copied, no re-encoding. Uses
 * {@link InAppMuxer} (pure-Java MP4 writer) to avoid the framework
 * {@code MediaMuxer}'s 4&nbsp;GB cap and pre-Android-9 atom-size bugs.
 */
final class TsRemuxer {

    private static final Logger log = LoggerFactory.getLogger(TsRemuxer.class);
    private static final long POLL_INTERVAL_MS = 1500L;
    private static final long REPORT_MIN_INTERVAL_MS = 2500L;

    interface Callback {
        @MainThread void onProgress(int percent);
        @MainThread void onCompleted(File mp4);
        @MainThread void onFailed(Throwable cause);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    @Nullable private Transformer transformer;
    @Nullable private Runnable poll;
    private boolean cancelled;

    TsRemuxer(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Start an asynchronous trans-mux of {@code source} into {@code outputMp4}.
     * Caller is responsible for choosing the output path; this class will
     * delete any existing file at that path before starting.
     */
    @MainThread
    void start(Uri source, File outputMp4, Callback callback) {
        if (outputMp4.exists() && !outputMp4.delete()) {
            log.warn("remux_cleanup_failed path={}", outputMp4);
        }
        File parent = outputMp4.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        log.info("remux_start src={} out={} mode={}",
            source, outputMp4, "copy");
        // Use AvccMuxerFactory which wraps InAppMuxer (pure-Java MP4 writer
        // with 64-bit largesize boxes — no 4 GB cap) and converts H.264
        // Annex B (start codes) emitted by the TS/PS extractors into the
        // AVCC (length-prefixed) form Mp4Muxer requires.
        Transformer.Builder builder = new Transformer.Builder(context)
                .setMuxerFactory(new AvccMuxerFactory())
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(Composition composition, ExportResult result) {
                        finishPoll();
                        log.info("remux_completed out={} size={} durMs={}",
                                outputMp4, outputMp4.length(), result.durationMs);
                        main.post(() -> callback.onCompleted(outputMp4));
                    }
                    @Override
                    public void onTransformationCompleted(MediaItem item, TransformationResult result) {
                        finishPoll();
                        log.info("remux_completed_legacy out={} size={} durMs={}",
                                outputMp4, outputMp4.length(), result.durationMs);
                        main.post(() -> callback.onCompleted(outputMp4));
                    }
                    @Override
                    public void onError(Composition composition, ExportResult result, ExportException e) {
                        finishPoll();
                        log.error("remux_error", e);
                        outputMp4.delete();
                        main.post(() -> callback.onFailed(e));
                    }
                    @Override
                    public void onTransformationError(MediaItem item, TransformationException e) {
                        finishPoll();
                        log.error("remux_error_legacy", e);
                        outputMp4.delete();
                        main.post(() -> callback.onFailed(e));
                    }
                });

        transformer = builder.build();

        try {
            MediaItem mediaItem = MediaItem.fromUri(source);
            transformer.start(mediaItem, outputMp4.getAbsolutePath());
        } catch (Exception e) {
            log.error("remux_start_failed src={}", source, e);
            main.post(() -> callback.onFailed(e));
            return;
        }
        startProgressPolling(source, outputMp4, callback);
    }

    @MainThread
    void cancel() {
        cancelled = true;
        finishPoll();
        if (transformer != null) {
            try { transformer.cancel(); } catch (Throwable ignored) {}
            transformer = null;
        }
    }

    private void startProgressPolling(Uri source, File outputMp4, Callback callback) {
        final ProgressHolder holder = new ProgressHolder();
        final long sourceSizeBytes = resolveSourceSizeBytes(source);
        final int[] lastPercent = new int[] { 0 };
        final long[] lastReportAt = new long[] { 0L };
        poll = new Runnable() {
            @Override public void run() {
                if (cancelled || transformer == null) return;
                long now = System.currentTimeMillis();
                int state = transformer.getProgress(holder);
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    int pct = Math.max(0, Math.min(99, holder.progress));
                    if (pct < lastPercent[0]) {
                        pct = lastPercent[0];
                    }
                    lastPercent[0] = pct;
                    if (shouldReportProgress(now, lastReportAt[0], pct)) {
                        callback.onProgress(pct);
                        lastReportAt[0] = now;
                    }
                } else {
                    int estimated = estimateProgressFromBytes(outputMp4, sourceSizeBytes, lastPercent[0]);
                    if (estimated >= 0) {
                        lastPercent[0] = estimated;
                        if (shouldReportProgress(now, lastReportAt[0], estimated)) {
                            callback.onProgress(estimated);
                            lastReportAt[0] = now;
                        }
                    } else {
                        // If source length can't be resolved, fall back to indeterminate.
                        if (now - lastReportAt[0] >= REPORT_MIN_INTERVAL_MS) {
                            callback.onProgress(-1);
                            lastReportAt[0] = now;
                        }
                    }
                }
                main.postDelayed(this, POLL_INTERVAL_MS);
            }
        };
        main.postDelayed(poll, POLL_INTERVAL_MS);
    }

    private static boolean shouldReportProgress(long now, long lastReportAt, int percent) {
        if (lastReportAt == 0L) return true;
        if (percent >= 99) return true;
        return now - lastReportAt >= REPORT_MIN_INTERVAL_MS;
    }

    private static int estimateProgressFromBytes(File outputMp4, long sourceSizeBytes, int lastPercent) {
        if (sourceSizeBytes <= 0) return -1;
        long outBytes = outputMp4.length();
        int pct = (int) ((outBytes * 100L) / sourceSizeBytes);
        pct = Math.max(0, Math.min(99, pct));
        if (pct < lastPercent) {
            pct = lastPercent;
        }
        return pct;
    }

    private long resolveSourceSizeBytes(Uri source) {
        try {
            if ("file".equals(source.getScheme())) {
                String p = source.getPath();
                if (p != null) {
                    File f = new File(p);
                    if (f.exists() && f.isFile()) {
                        return f.length();
                    }
                }
            }

            ContentResolver cr = context.getContentResolver();
            try (AssetFileDescriptor afd = cr.openAssetFileDescriptor(source, "r")) {
                if (afd != null && afd.getLength() > 0) {
                    return afd.getLength();
                }
            }

            try (Cursor c = cr.query(source, new String[] { OpenableColumns.SIZE }, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(OpenableColumns.SIZE);
                    if (idx >= 0 && !c.isNull(idx)) {
                        long size = c.getLong(idx);
                        if (size > 0) return size;
                    }
                }
            }
        } catch (Throwable t) {
            log.warn("remux_source_size_unknown uri={} cause={}", source, t.toString());
        }
        return -1;
    }

    private void finishPoll() {
        if (poll != null) {
            main.removeCallbacks(poll);
            poll = null;
        }
    }
}
