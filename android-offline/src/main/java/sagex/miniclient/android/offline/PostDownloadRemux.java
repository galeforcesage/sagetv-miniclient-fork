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
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.DocumentsContract;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Orchestrates post-download trans-mux: runs {@link TsRemuxer}, places the
 * resulting MP4 next to the original recording (or inside the SAF tree),
 * deletes the original {@code .ts}/{@code .mpg}, and updates the
 * {@link DownloadMetadata} so playback picks up the new URI.
 *
 * <p>Remuxes are serialized through a single shared FIFO queue — only one
 * trans-mux runs at a time so I/O isn't the bottleneck while the next
 * download proceeds in parallel. Safe to call from any thread.
 */
final class PostDownloadRemux {

    private static final Logger log = LoggerFactory.getLogger(PostDownloadRemux.class);

    interface Listener {
        /** Called on the main thread when work completes (success or fallback). */
        void onFinished(DownloadMetadata meta);
    }

    // -- Shared serial queue --------------------------------------------------
    private static final Object QUEUE_LOCK = new Object();
    private static final Deque<PendingRemux> QUEUE = new ArrayDeque<>();
    private static final Set<String> ENQUEUED_IDS = new HashSet<>();
    private static boolean RUNNING = false;

    private static final class PendingRemux {
        final DownloadMetadata meta;
        @Nullable final Listener listener;
        @Nullable final RemuxFormat forced;
        PendingRemux(DownloadMetadata meta, @Nullable Listener listener,
                     @Nullable RemuxFormat forced) {
            this.meta = meta;
            this.listener = listener;
            this.forced = forced;
        }
    }

    private final Context context;
    private final DownloadRepository repository;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread io = new HandlerThread("PostDownloadRemux-io");
    private final Handler ioHandler;

    private enum RemuxFormat {
        MP4("mp4", "video/mp4"),
        MKV("mkv", "video/x-matroska");

        final String extension;
        final String mimeType;

        RemuxFormat(String extension, String mimeType) {
            this.extension = extension;
            this.mimeType = mimeType;
        }
    }

    PostDownloadRemux(Context context, DownloadRepository repository) {
        this.context = context.getApplicationContext();
        this.repository = repository;
        io.start();
        ioHandler = new Handler(io.getLooper());
    }

    /**
     * Returns true if {@code container}/{@code uri} appear to be a format
     * that benefits from trans-mux to MP4 (TS or MPEG-PS). Containers that
     * already have a moov-style index (mp4, mkv) are skipped.
     */
    static boolean shouldRemux(DownloadMetadata meta) {
        return shouldRemux(null, meta);
    }

    /**
     * Codec-aware variant. When a {@link Context} is supplied the source is
     * probed with {@link OfflineVideoProbe#probeFirstVideoMime}. If the first
     * video track is MPEG-1/2 we skip the remux entirely: every device that
     * runs this client (Shield, Fold, etc.) bundles {@code IjkMediaPlayer},
     * which plays MPEG-2 directly out of the original {@code .ts}/{@code
     * .mpg} via the {@code PfdMediaDataSource} adapter without needing the
     * file to be remuxed into MKV first. That saves a multi-GB transcode
     * pass and keeps the original recording on disk.
     *
     * <p>Falls back to the legacy extension/container heuristic when no
     * context is given or when the probe can't read the file (e.g. the
     * download just landed and the file isn't fully visible to the SAF
     * provider yet).
     */
    static boolean shouldRemux(@Nullable Context context, DownloadMetadata meta) {
        if (meta == null) return false;
        String uri = meta.getLocalUri();
        if (uri == null || uri.isEmpty()) return false;
        String lower = uri.toLowerCase();
        if (lower.endsWith(".mp4") || lower.endsWith(".mkv")) return false;
        boolean extensionSuggestsRemux =
                lower.endsWith(".ts") || lower.endsWith(".mpg")
                || lower.endsWith(".mpeg") || lower.endsWith(".m2ts")
                || lower.endsWith(".vob") || lower.endsWith(".ps");
        boolean containerSuggestsRemux = false;
        if (!extensionSuggestsRemux) {
            String c = meta.getContainer() == null ? "" : meta.getContainer().toLowerCase();
            containerSuggestsRemux = c.contains("mpeg2") || c.contains("mpeg-ts")
                    || c.contains("mpegts") || c.contains("ts")
                    || c.contains("mpg") || c.contains("ps");
        }
        if (!extensionSuggestsRemux && !containerSuggestsRemux) {
            return false;
        }
        if (context != null) {
            String mime = OfflineVideoProbe.probeFirstVideoMime(context, Uri.parse(uri));
            if (OfflineVideoProbe.isMpeg2VideoMime(mime)) {
                log.info("post_download_remux_skip mediaFileID={} reason=mpeg2_ijk_native uri={}",
                        meta.getMediaFileID(), uri);
                return false;
            }
        }
        return true;
    }

    /**
     * Safe to call from any thread; Transformer setup is bounced to the
     * main looper internally. Format is chosen automatically (TS health check).
     */
    void start(DownloadMetadata meta, @Nullable Listener listener) {
        startWithFormat(meta, listener, null);
    }

    /** Force-remux to MKV regardless of TS health. */
    void startForceMkv(DownloadMetadata meta, @Nullable Listener listener) {
        startWithFormat(meta, listener, RemuxFormat.MKV);
    }

    /** Force-remux to MP4; falls back to MKV if MP4 remux fails. */
    void startForceMp4(DownloadMetadata meta, @Nullable Listener listener) {
        startWithFormat(meta, listener, RemuxFormat.MP4);
    }

    private void startWithFormat(DownloadMetadata meta, @Nullable Listener listener,
                                 @Nullable RemuxFormat forced) {
        synchronized (QUEUE_LOCK) {
            String id = meta.getMediaFileID();
            if (id != null && !ENQUEUED_IDS.add(id)) {
                log.info("post_download_remux_already_queued id={}", id);
                return;
            }
            QUEUE.addLast(new PendingRemux(meta, listener, forced));
            log.info("post_download_remux_enqueued id={} queueDepth={} running={} forced={}",
                    id, QUEUE.size(), RUNNING, forced);
            if (RUNNING) return;
            RUNNING = true;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            drainNext();
        } else {
            main.post(this::drainNext);
        }
    }

    private void startWithFormatUnqueued(DownloadMetadata meta, @Nullable Listener listener,
                                         @Nullable RemuxFormat forced) {
        meta.setTransferSessionState(null);
        repository.update(meta);
        Uri source = Uri.parse(meta.getLocalUri());
        File tempOut = new File(new File(context.getFilesDir(), "remux"),
                meta.getMediaFileID() + "." + (forced == RemuxFormat.MKV ? "mkv" : "mp4"));
        if (!BundledFfmpegRemuxer.isAvailable(context)) {
            log.warn("post_download_remux_no_ffmpeg mediaFileID={}", meta.getMediaFileID());
            meta.setTransferSessionState("remux_failed");
            meta.setErrorMessage("No FFmpeg available");
            repository.update(meta);
            if (listener != null) listener.onFinished(meta);
            main.post(() -> onSlotFree(meta));
            return;
        }
        markRemuxing(meta, -1);
        if (forced == RemuxFormat.MKV) {
            ioHandler.post(() -> runFfmpegMkvOnIo(meta, source, tempOut, listener));
        } else if (forced == RemuxFormat.MP4) {
            ioHandler.post(() -> runFfmpegMp4OnIo(meta, source, tempOut, listener));
        } else {
            ioHandler.post(() -> {
                boolean suspicious = TsHealthClassifier.shouldPreferFfmpeg(
                        context, source, meta.getMediaFileID());
                main.post(() -> {
                    if (suspicious) {
                        log.info("post_download_remux_direct_mkv mediaFileID={} reason=mpeg_input_needs_remux",
                                meta.getMediaFileID());
                        ioHandler.post(() -> runFfmpegMkvOnIo(meta, source,
                            remuxTempOut(meta, RemuxFormat.MKV), listener));
                    } else {
                        log.info("post_download_remux_try_mp4 mediaFileID={}",
                                meta.getMediaFileID());
                        ioHandler.post(() -> runFfmpegMp4OnIo(meta, source, tempOut, listener));
                    }
                });
            });
        }
    }

    /**
     * Safe to call from any thread; Transformer setup is bounced to the
     * main looper internally. (old entry point delegates to startWithFormat)
     */
    void startLegacy(DownloadMetadata meta, @Nullable Listener listener) {
        startWithFormat(meta, listener, null);
    }

    private void startLegacyInner(DownloadMetadata meta, @Nullable Listener listener) {
        // Enqueue and drain serially. Downloads continue in parallel; only
        // one trans-mux runs at a time so disk/SAF I/O stays predictable.
        synchronized (QUEUE_LOCK) {
            String id = meta.getMediaFileID();
            if (id != null && !ENQUEUED_IDS.add(id)) {
                log.info("post_download_remux_already_queued id={}", id);
                return;
            }
            QUEUE.addLast(new PendingRemux(meta, listener, null));
            log.info("post_download_remux_enqueued id={} queueDepth={} running={}",
                    id, QUEUE.size(), RUNNING);
            if (RUNNING) return;
            RUNNING = true;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            drainNext();
        } else {
            main.post(this::drainNext);
        }
    }

    @MainThread
    private void drainNext() {
        final PendingRemux next;
        synchronized (QUEUE_LOCK) {
            next = QUEUE.pollFirst();
            if (next == null) {
                RUNNING = false;
                return;
            }
        }
        runOne(next.meta, next.listener, next.forced);
    }

    @MainThread
    private void onSlotFree(DownloadMetadata meta) {
        synchronized (QUEUE_LOCK) {
            if (meta.getMediaFileID() != null) {
                ENQUEUED_IDS.remove(meta.getMediaFileID());
            }
        }
        drainNext();
    }

    @MainThread
    private void runOne(DownloadMetadata meta, @Nullable Listener listener,
                        @Nullable RemuxFormat forced) {
        startWithFormatUnqueued(meta, listener, forced);
    }

    private void runOneLegacy(DownloadMetadata meta, @Nullable Listener listener) {
        // Reset any previous remux_failed status so retries start fresh
        meta.setTransferSessionState(null);
        repository.update(meta);
        
        Uri source = Uri.parse(meta.getLocalUri());
        File tempOut = new File(new File(context.getFilesDir(), "remux"),
                meta.getMediaFileID() + ".mp4");

        // FFmpeg is now the primary remux strategy.
        // First check TS health to decide format (MP4 vs MKV).
        if (BundledFfmpegRemuxer.isAvailable(context)) {
            markRemuxing(meta, -1);
            ioHandler.post(() -> {
                boolean suspicious = TsHealthClassifier.shouldPreferFfmpeg(
                    context, source, meta.getMediaFileID());
                main.post(() -> {
                    if (suspicious) {
                        log.info("post_download_remux_direct_mkv mediaFileID={} reason=mpeg_input_needs_remux",
                                meta.getMediaFileID());
                        ioHandler.post(() -> runFfmpegMkvOnIo(meta, source,
                            remuxTempOut(meta, RemuxFormat.MKV), listener));
                    } else {
                        log.info("post_download_remux_try_mp4 mediaFileID={}",
                                meta.getMediaFileID());
                        ioHandler.post(() -> runFfmpegMp4OnIo(meta, source, tempOut, listener));
                    }
                });
            });
            return;
        }

        // Fallback if no FFmpeg: mark failed (Exo is deprecated)
        log.warn("post_download_remux_no_ffmpeg mediaFileID={}", meta.getMediaFileID());
        meta.setTransferSessionState("remux_failed");
        meta.setErrorMessage("No FFmpeg available and Exo transformer is deprecated");
        repository.update(meta);
        tempOut.delete();
        if (listener != null) listener.onFinished(meta);
        onSlotFree(meta);
    }

    private void runFfmpegMp4OnIo(DownloadMetadata meta,
                                   Uri source,
                                   File tempOut,
                                   @Nullable Listener listener) {
        try {
            meta.setTransferSessionState("remuxing...");
            repository.update(meta);
            BundledFfmpegRemuxer.remuxToMp4(context, source, tempOut, meta.getMediaFileID());
            finalizeOnIo(meta, source, tempOut, listener, RemuxFormat.MP4);
        } catch (Exception e) {
            log.warn("post_download_remux_mp4_failed mediaFileID={} cause={}",
                    meta.getMediaFileID(), e.toString());
            tempOut.delete();
            // MP4 failed, try MKV as fallback
            log.info("post_download_remux_mp4_fallback_mkv mediaFileID={}", meta.getMediaFileID());
            runFfmpegMkvOnIo(meta, source, remuxTempOut(meta, RemuxFormat.MKV), listener);
        }
    }

    private void runFfmpegMkvOnIo(DownloadMetadata meta,
                                  Uri source,
                                  File tempOut,
                                  @Nullable Listener listener) {
        try {
            meta.setTransferSessionState("remuxing...");
            repository.update(meta);
            BundledFfmpegRemuxer.remuxToMkv(context, source, tempOut, meta.getMediaFileID());
            finalizeOnIo(meta, source, tempOut, listener, RemuxFormat.MKV);
        } catch (Exception e) {
            log.warn("post_download_remux_mkv_failed mediaFileID={} cause={}",
                    meta.getMediaFileID(), e.toString());
            tempOut.delete();
            meta.setTransferSessionState("remux_failed");
            meta.setErrorMessage("Remux failed; recording is not ready for offline playback");
            repository.update(meta);
            if (listener != null) main.post(() -> listener.onFinished(meta));
            main.post(() -> onSlotFree(meta));
        }
    }

    private File remuxTempOut(DownloadMetadata meta, RemuxFormat format) {
        return new File(new File(context.getFilesDir(), "remux"),
                meta.getMediaFileID() + "." + format.extension);
    }

    private void markRemuxing(DownloadMetadata meta, int percent) {
        int clamped = percent;
        int existing = parseRemuxPercent(meta.getTransferSessionState());
        if (percent >= 0 && existing >= 0) {
            clamped = Math.max(percent, existing);
        }
        if (clamped < 0) {
            meta.setTransferSessionState("remuxing...");
        } else {
            meta.setTransferSessionState("remuxing " + clamped + "%");
        }
        repository.update(meta);
        DownloadForegroundService.updateProgress(
                context, meta.getMediaFileID(), Math.max(0, clamped), 100);
    }

    private static int parseRemuxPercent(String sessionState) {
        if (sessionState == null || !sessionState.startsWith("remuxing")) return -1;
        int p = sessionState.indexOf('%');
        int s = sessionState.lastIndexOf(' ');
        if (p <= 0 || s <= 0 || p <= s) return -1;
        try {
            return Math.max(0, Math.min(100,
                    Integer.parseInt(sessionState.substring(s + 1, p).trim())));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private void finalizeOnIo(DownloadMetadata meta, Uri source, File tempOut,
                              @Nullable Listener listener,
                              RemuxFormat outFmt) {
        try {
            String newUri;
            if ("file".equals(source.getScheme())) {
                newUri = placeIntoFileTree(source, tempOut, outFmt.extension);
            } else if ("content".equals(source.getScheme())) {
                newUri = placeIntoSafTree(source, tempOut, outFmt.mimeType, outFmt.extension);
            } else {
                throw new IOException("Unsupported source scheme: " + source.getScheme());
            }
            // Best-effort: delete source recording now that the MP4 is in place.
            deleteSource(source);
            tempOut.delete();

            meta.setLocalUri(newUri);
            meta.setContainer(outFmt.extension);
            meta.setTransferSessionState("completed");
            meta.setErrorMessage(null);
            // Recompute persisted size from the new MP4 file.
            long newSize = sizeOf(newUri);
            if (newSize > 0) {
                meta.setFileSize(newSize);
                meta.setDownloadedBytes(newSize);
                meta.setResumeFromOffset(newSize);
            }
            repository.update(meta);
            DownloadForegroundService.updateProgress(
                    context, meta.getMediaFileID(), 100, 100);
            log.info("post_download_remux_done mediaFileID={} newUri={}",
                    meta.getMediaFileID(), newUri);
            if (listener != null) main.post(() -> listener.onFinished(meta));
            main.post(() -> onSlotFree(meta));
        } catch (Exception e) {
            log.error("post_download_remux_finalize_failed mediaFileID={}",
                    meta.getMediaFileID(), e);
            tempOut.delete();
            meta.setTransferSessionState("remux_failed");
            meta.setErrorMessage("Remux finalize failed: " + e.getMessage());
            repository.update(meta);
            if (listener != null) main.post(() -> listener.onFinished(meta));
            main.post(() -> onSlotFree(meta));
        }
    }

    private String placeIntoFileTree(Uri source, File tempOut, String extension) throws IOException {
        String path = source.getPath();
        if (path == null) throw new IOException("Source file URI has no path");
        File src = new File(path);
        File parent = src.getParentFile();
        if (parent == null) parent = src.getAbsoluteFile().getParentFile();
        if (parent == null) throw new IOException("Source has no parent: " + src);
        String name = src.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        File dest = new File(parent, base + "." + extension);
        // If a stale .mp4 already exists (re-download), overwrite it.
        if (dest.exists() && !dest.delete()) {
            throw new IOException("Cannot overwrite existing " + dest);
        }
        if (!tempOut.renameTo(dest)) {
            // Cross-filesystem rename can fail — fall back to byte copy.
            copyBytes(tempOut, dest);
        }
        return Uri.fromFile(dest).toString();
    }

    private String placeIntoSafTree(Uri source, File tempOut, String mimeType, String extension) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String docId = DocumentsContract.getDocumentId(source);
        int slash = docId.lastIndexOf('/');
        if (slash <= 0) {
            throw new IOException("Cannot derive parent directory for SAF doc id: " + docId);
        }
        String parentDocId = docId.substring(0, slash);
        String name = docId.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        Uri parentDocUri = DocumentsContract.buildDocumentUriUsingTree(source, parentDocId);
        Uri newDocUri = DocumentsContract.createDocument(
            resolver, parentDocUri, mimeType, base + "." + extension);
        if (newDocUri == null) {
            throw new IOException("createDocument returned null for parent " + parentDocUri);
        }
        try (InputStream in = new FileInputStream(tempOut);
             OutputStream out = resolver.openOutputStream(newDocUri, "w")) {
            if (out == null) throw new IOException("openOutputStream null for " + newDocUri);
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return newDocUri.toString();
    }

    private void deleteSource(Uri source) {
        try {
            if ("file".equals(source.getScheme())) {
                String p = source.getPath();
                if (p != null) {
                    File f = new File(p);
                    if (f.exists() && !f.delete()) {
                        log.warn("post_download_remux_source_delete_failed path={}", p);
                    }
                }
            } else if ("content".equals(source.getScheme())) {
                DocumentsContract.deleteDocument(context.getContentResolver(), source);
            }
        } catch (Exception e) {
            log.warn("post_download_remux_source_delete_error uri={} err={}",
                    source, e.toString());
        }
    }

    private static void copyBytes(File src, File dst) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private long sizeOf(String uriString) {
        try {
            Uri uri = Uri.parse(uriString);
            if ("file".equals(uri.getScheme())) {
                String p = uri.getPath();
                return p != null ? new File(p).length() : -1;
            }
            try (android.os.ParcelFileDescriptor pfd =
                         context.getContentResolver().openFileDescriptor(uri, "r")) {
                return pfd != null ? pfd.getStatSize() : -1;
            }
        } catch (Exception e) {
            return -1;
        }
    }
}
