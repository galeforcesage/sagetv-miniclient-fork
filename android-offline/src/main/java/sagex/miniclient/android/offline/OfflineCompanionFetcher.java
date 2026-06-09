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

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * M3 sidecar fetcher — pulls the binary companion assets (artwork JPEGs,
 * caption files, comskip EDL, transcript VTT) referenced by the manifests
 * persisted in M2 onto the device under the {@code <file>.companion/}
 * directory.
 *
 * <p>Server-team contract:
 * <ul>
 *   <li>All URLs accept the same {@code session_token} the main download
 *       used, sent via the {@code X-Transfer-Token} HTTP header (same
 *       transport as {@link DownloadTask}).</li>
 *   <li>URLs may be absolute or relative; relative URLs are resolved
 *       against the same control-plane base as {@code download_url}.</li>
 *   <li>Unknown {@code kind}/{@code format}/{@code language} values are
 *       skipped, never rejected — forward-compat invariant.</li>
 * </ul>
 *
 * <p>Network model: best-effort, sequential per manifest, never blocks the
 * primary download pipeline. Failures are logged and skipped — a missing
 * artwork JPEG does not poison the catalog row. Re-run is idempotent: if a
 * target file already exists with non-zero size, the fetch is skipped (so
 * repeated calls after partial network loss converge cleanly).
 */
final class OfflineCompanionFetcher {
    private static final Logger log = LoggerFactory.getLogger(OfflineCompanionFetcher.class);

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS    = 30_000;
    private static final int SIDECAR_FETCH_ATTEMPTS = 3;
    private static final int[] SIDECAR_FETCH_RETRY_DELAYS_MS = {600, 1800};
    /** Cap each sidecar asset; comskip/captions stay small, JPEGs ~few MB. */
    private static final int MAX_ASSET_BYTES    = 8 * 1024 * 1024;

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "OfflineCompanionFetcher");
        t.setDaemon(true);
        return t;
    });
    private static final Map<String, CancelFlag> ACTIVE = new ConcurrentHashMap<>();
    private static final Set<String> PAUSED = ConcurrentHashMap.newKeySet();

    private static final class CancelFlag {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
    }

    interface FetchListener {
        void onCompleted(String mediaFileId, int fetched, int skipped, int failed, String error);
    }

    private OfflineCompanionFetcher() {}

    /**
     * Best-effort async fetch of all sidecar assets referenced by the
     * manifests on {@code meta}. No-op when no companion dir is set
     * (SAF destination, or no {@code offline} block was sent).
     */
    static void fetchAsync(DownloadMetadata meta,
                           String controlPlaneBase,
                           String ngClientId) {
        fetchAsync(meta, controlPlaneBase, ngClientId, true, true, true, true, null);
    }

    static void fetchAsync(DownloadMetadata meta,
                           String controlPlaneBase,
                           String ngClientId,
                           boolean includeArtwork,
                           boolean includeCaptions,
                           boolean includeComskip,
                           boolean includeTranscript) {
        fetchAsync(meta, controlPlaneBase, ngClientId,
                includeArtwork, includeCaptions, includeComskip, includeTranscript, null);
    }

    static void fetchAsync(DownloadMetadata meta,
                           String controlPlaneBase,
                           String ngClientId,
                           boolean includeArtwork,
                           boolean includeCaptions,
                           boolean includeComskip,
                           boolean includeTranscript,
                           FetchListener listener) {
        if (meta == null) return;
        final String mediaFileId = meta.getMediaFileID();
        if (mediaFileId == null || mediaFileId.isEmpty()) return;
        if (PAUSED.contains(mediaFileId)) {
            log.info("Companion fetch paused for {} (not dispatching)", mediaFileId);
            return;
        }
        String dirPath = meta.getCompanionDirPath();
        if (dirPath == null || dirPath.isEmpty()) {
            return;
        }
        final String token       = meta.getSessionToken();
        final String correlation = meta.getCorrelationId();
        final String artworkJson    = meta.getArtworkManifestJson();
        final String captionsJson   = meta.getCaptionsManifestJson();
        final String comskipJson    = meta.getComskipManifestJson();
        final String transcriptJson = meta.getTranscriptManifestJson();
        final CancelFlag flag = new CancelFlag();
        ACTIVE.put(mediaFileId, flag);

        EXEC.submit(() -> {
            try {
                File dir = new File(dirPath);
                if (!dir.exists() && !dir.mkdirs()) {
                    log.warn("Companion dir missing and uncreatable: {}", dir);
                    if (listener != null) {
                        listener.onCompleted(mediaFileId, 0, 0, 1, "COMPANION_DIR_UNAVAILABLE");
                    }
                    return;
                }
                AtomicInteger ok = new AtomicInteger();
                AtomicInteger skipped = new AtomicInteger();
                AtomicInteger failed = new AtomicInteger();

                if (includeArtwork) {
                    fetchArtwork(dir, artworkJson, controlPlaneBase, token, ngClientId,
                            correlation, ok, skipped, failed, flag);
                }
                if (includeCaptions) {
                    fetchCaptions(dir, captionsJson, controlPlaneBase, token, ngClientId,
                            correlation, ok, skipped, failed, flag);
                }
                if (includeComskip) {
                    fetchComskip(dir, comskipJson, controlPlaneBase, token, ngClientId,
                            correlation, ok, skipped, failed, flag);
                }
                if (includeTranscript) {
                    fetchTranscript(dir, transcriptJson, controlPlaneBase, token, ngClientId,
                            correlation, ok, skipped, failed, flag);
                }

                log.info("Companion fetch for {}: {} fetched, {} skipped (exists), {} failed",
                        mediaFileId, ok.get(), skipped.get(), failed.get());
                if (listener != null) {
                    listener.onCompleted(mediaFileId, ok.get(), skipped.get(), failed.get(), null);
                }
            } catch (Exception e) {
                log.warn("Companion fetch crashed for {}: {}", mediaFileId, e.toString());
                if (listener != null) {
                    listener.onCompleted(mediaFileId, 0, 0, 1, e.getClass().getSimpleName());
                }
            } finally {
                ACTIVE.remove(mediaFileId, flag);
            }
        });
    }

    static void pause(String mediaFileId) {
        if (mediaFileId == null || mediaFileId.isEmpty()) return;
        PAUSED.add(mediaFileId);
        cancel(mediaFileId);
    }

    static void resume(String mediaFileId) {
        if (mediaFileId == null || mediaFileId.isEmpty()) return;
        PAUSED.remove(mediaFileId);
    }

    static void cancel(String mediaFileId) {
        if (mediaFileId == null || mediaFileId.isEmpty()) return;
        CancelFlag flag = ACTIVE.get(mediaFileId);
        if (flag != null) {
            flag.cancelled.set(true);
        }
    }

    // ---- artwork ----

    private static void fetchArtwork(File dir, String json, String base,
                                     String token, String ngClientId, String correlationId,
                                     AtomicInteger ok, AtomicInteger skipped, AtomicInteger failed,
                                     CancelFlag flag) {
        if (flag.cancelled.get()) return;
        if (json == null || json.isEmpty()) return;
        JSONArray arr;
        try { arr = new JSONArray(json); } catch (Exception e) {
            log.warn("Artwork manifest unparsable: {}", e.toString()); return;
        }
        File castDir = new File(dir, "cast");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            String kind = item.optString("kind", null);
            String url  = item.optString("url", null);
            if (kind == null || url == null || url.isEmpty()) { skipped.incrementAndGet(); continue; }
            kind = kind.trim().toLowerCase(Locale.US);
            File target;
            switch (kind) {
                case "thumbnail": target = new File(dir, "thumbnail.jpg"); break;
                case "poster": target = new File(dir, "poster.jpg"); break;
                case "fanart": target = new File(dir, "fanart.jpg"); break;
                case "banner": target = new File(dir, "banner.jpg"); break;
                case "person":
                case "cast": {
                    String pid = item.optString("person_id", null);
                    if (pid == null || pid.isEmpty()) {
                        pid = item.optString("subject_id", null);
                    }
                    if (pid == null || pid.isEmpty()) { skipped.incrementAndGet(); continue; }
                    if (!castDir.exists() && !castDir.mkdirs()) {
                        failed.incrementAndGet(); continue;
                    }
                    target = new File(castDir, pid + ".jpg");
                    break;
                }
                default:
                    String ext = extensionFromUrl(url);
                    if (ext.isEmpty()) {
                        ext = "jpg";
                    }
                    String safeKind = kind.replaceAll("[^a-z0-9_-]", "_");
                    if (safeKind.isEmpty()) {
                        safeKind = "artwork";
                    }
                    target = new File(dir, safeKind + "." + sanitizeExt(ext));
                    break;
            }
            attempt(target, url, base, token, ngClientId, correlationId, ok, skipped, failed, flag);
        }
    }

    // ---- captions ----

    private static void fetchCaptions(File dir, String json, String base,
                                      String token, String ngClientId, String correlationId,
                                      AtomicInteger ok, AtomicInteger skipped, AtomicInteger failed,
                                      CancelFlag flag) {
        if (flag.cancelled.get()) return;
        if (json == null || json.isEmpty()) return;
        List<JSONObject> entries = collectUrlEntries(json);
        if (entries.isEmpty()) return;
        File capDir = new File(dir, "captions");
        Set<String> seenNames = new HashSet<>();
        int seq = 0;
        for (JSONObject item : entries) {
            String lang = normalizeToken(item.optString("language", "eng"), "eng");
            String kind = normalizeToken(item.optString("kind", "srt"), "srt");
            String format = normalizeToken(item.optString("format", kind), kind);
            String url  = item.optString("url", null);
            if (url == null || url.isEmpty()) { skipped.incrementAndGet(); continue; }
            String ext = extensionForCaption(item, url, format, kind);
            if (!capDir.exists() && !capDir.mkdirs()) { failed.incrementAndGet(); continue; }
            String baseName = lang + "." + kind + "." + ext;
            File target = uniqueFile(capDir, baseName, seenNames, seq++);
            attempt(target, url, base, token, ngClientId, correlationId, ok, skipped, failed, flag);
        }
    }

    // ---- comskip ----

    private static void fetchComskip(File dir, String json, String base,
                                     String token, String ngClientId, String correlationId,
                                     AtomicInteger ok, AtomicInteger skipped, AtomicInteger failed,
                                     CancelFlag flag) {
        if (flag.cancelled.get()) return;
        if (json == null || json.isEmpty()) return;
        List<JSONObject> entries = collectUrlEntries(json);
        if (entries.isEmpty()) return;
        Set<String> seenNames = new HashSet<>();
        int seq = 0;
        for (JSONObject obj : entries) {
            String url = obj.optString("url", null);
            if (url == null || url.isEmpty()) continue;
            String format = normalizeToken(obj.optString("format", extensionFromUrl(url)), "edl");
            String baseName = "comskip." + sanitizeExt(format);
            File target = uniqueFile(dir, baseName, seenNames, seq++);
            attempt(target, url, base, token, ngClientId, correlationId, ok, skipped, failed, flag);
        }
    }

    // ---- transcript ----

    private static void fetchTranscript(File dir, String json, String base,
                                        String token, String ngClientId, String correlationId,
                                        AtomicInteger ok, AtomicInteger skipped, AtomicInteger failed,
                                        CancelFlag flag) {
        if (flag.cancelled.get()) return;
        if (json == null || json.isEmpty()) return;
        List<JSONObject> entries = collectUrlEntries(json);
        if (entries.isEmpty()) return;
        Set<String> seenNames = new HashSet<>();
        int seq = 0;
        for (JSONObject obj : entries) {
            String url = obj.optString("url", null);
            if (url == null || url.isEmpty()) continue;
            String lang = normalizeToken(obj.optString("language", "eng"), "eng");
            String format = normalizeToken(obj.optString("format", extensionFromUrl(url)), "vtt");
            String baseName = "transcript." + lang + "." + sanitizeExt(format);
            File target = uniqueFile(dir, baseName, seenNames, seq++);
            attempt(target, url, base, token, ngClientId, correlationId, ok, skipped, failed, flag);
        }
    }

    private static List<JSONObject> collectUrlEntries(String json) {
        List<JSONObject> out = new ArrayList<>();
        try {
            Object root = new JSONTokener(json).nextValue();
            collectUrlEntriesRecursive(root, out);
        } catch (Exception e) {
            log.warn("Sidecar manifest unparsable: {}", e.toString());
        }
        return out;
    }

    private static void collectUrlEntriesRecursive(Object node, List<JSONObject> out) {
        if (node == null) return;
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            String url = obj.optString("url", null);
            if (url != null && !url.isEmpty()) {
                out.add(obj);
            }
            JSONArray names = obj.names();
            if (names == null) return;
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i, null);
                if (key == null) continue;
                Object child = obj.opt(key);
                if (child != null && child != JSONObject.NULL) {
                    collectUrlEntriesRecursive(child, out);
                }
            }
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                Object child = arr.opt(i);
                if (child != null && child != JSONObject.NULL) {
                    collectUrlEntriesRecursive(child, out);
                }
            }
        }
    }

    private static String extensionForCaption(JSONObject item, String url, String format, String kind) {
        String fromUrl = extensionFromUrl(url);
        if (!fromUrl.isEmpty()) return sanitizeExt(fromUrl);
        if (!format.isEmpty()) return sanitizeExt(format);
        if (!kind.isEmpty()) {
            String k = kind.toLowerCase(Locale.US);
            if ("vtt".equals(k)) return "vtt";
            if ("srt".equals(k) || "cc".equals(k)) return "srt";
            return sanitizeExt(k);
        }
        return "srt";
    }

    private static String extensionFromUrl(String url) {
        if (url == null) return "";
        String s = url;
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        int slash = s.lastIndexOf('/');
        String name = slash >= 0 ? s.substring(slash + 1) : s;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot >= name.length() - 1) return "";
        return name.substring(dot + 1);
    }

    private static String normalizeToken(String value, String fallback) {
        if (value == null) return fallback;
        String v = value.trim().toLowerCase(Locale.US);
        if (v.isEmpty()) return fallback;
        return v;
    }

    private static String sanitizeExt(String ext) {
        if (ext == null || ext.trim().isEmpty()) return "bin";
        String v = ext.trim().toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
        if (v.isEmpty()) return "bin";
        return v;
    }

    private static File uniqueFile(File dir, String baseName, Set<String> seenNames, int seq) {
        String candidate = baseName;
        if (!seenNames.add(candidate) || new File(dir, candidate).exists()) {
            int dot = baseName.lastIndexOf('.');
            String stem = dot > 0 ? baseName.substring(0, dot) : baseName;
            String ext = dot > 0 ? baseName.substring(dot) : "";
            int index = Math.max(2, seq + 1);
            while (true) {
                candidate = stem + "_" + index + ext;
                File f = new File(dir, candidate);
                if (seenNames.add(candidate) && !f.exists()) {
                    break;
                }
                index++;
            }
        }
        return new File(dir, candidate);
    }

    // ---- core fetch ----

    /** Skip if already present and non-empty; otherwise download to a temp
     *  file and rename on success (atomic-ish). */
    private static void attempt(File target, String url, String base,
                                String token, String ngClientId, String correlationId,
                                AtomicInteger ok, AtomicInteger skipped, AtomicInteger failed,
                                CancelFlag flag) {
        if (flag.cancelled.get()) return;
        if (target.exists() && target.length() > 0) {
            if (isImageTarget(target) && !isValidImageFile(target)) {
                log.warn("Invalid cached image sidecar {}; deleting for refetch", target.getAbsolutePath());
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            } else {
                skipped.incrementAndGet();
                return;
            }
        }
        String resolved;
        try {
            resolved = resolveUrl(base, url);
        } catch (Exception e) {
            log.warn("Cannot resolve sidecar URL {}: {}", url, e.toString());
            failed.incrementAndGet();
            return;
        }
        File tmp = new File(target.getAbsolutePath() + ".part");
        Throwable finalError = null;
        for (int attempt = 1; attempt <= SIDECAR_FETCH_ATTEMPTS; attempt++) {
            if (flag.cancelled.get()) return;
            HttpURLConnection conn = null;
            try {
                URL u = new URL(resolved);
                conn = (HttpURLConnection) u.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("Accept", "*/*");
                if (token != null && !token.isEmpty()) {
                    conn.setRequestProperty("X-Transfer-Token", token);
                }
                if (ngClientId != null && !ngClientId.isEmpty()) {
                    conn.setRequestProperty("x-ng-client-id", ngClientId);
                }
                if (correlationId != null && !correlationId.isEmpty()) {
                    conn.setRequestProperty("X-Correlation-ID", correlationId);
                }
                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    if (code == 429 || code >= 500) {
                        throw new IllegalStateException("HTTP_" + code);
                    }
                    log.info("Sidecar GET {} -> HTTP {} (skipping)", resolved, code);
                    failed.incrementAndGet();
                    return;
                }
                try (InputStream in = new BufferedInputStream(conn.getInputStream());
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16 * 1024];
                    int total = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (flag.cancelled.get()) {
                            throw new IllegalStateException("CANCELLED");
                        }
                        if (total + n > MAX_ASSET_BYTES) {
                            throw new IllegalStateException("Asset exceeds " + MAX_ASSET_BYTES + " bytes");
                        }
                        out.write(buf, 0, n);
                        total += n;
                    }
                }
                if (flag.cancelled.get()) {
                    if (tmp.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                    }
                    return;
                }
                if (target.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    target.delete();
                }
                if (!tmp.renameTo(target)) {
                    throw new IllegalStateException("Rename .part -> target failed for " + target);
                }
                if (isImageTarget(target) && !isValidImageFile(target)) {
                    throw new IllegalStateException("Downloaded sidecar is not a valid image: " + target);
                }
                ok.incrementAndGet();
                return;
            } catch (Exception e) {
                if ("CANCELLED".equals(e.getMessage())) {
                    if (tmp.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                    }
                    return;
                }
                finalError = e;
                if (tmp.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
                boolean retryable = isRetryableSidecarError(e);
                if (retryable && attempt < SIDECAR_FETCH_ATTEMPTS) {
                    int delay = SIDECAR_FETCH_RETRY_DELAYS_MS[Math.min(
                            attempt - 1,
                            SIDECAR_FETCH_RETRY_DELAYS_MS.length - 1)];
                    log.info("Sidecar fetch retry {}/{} for {} after {}ms: {}",
                            attempt + 1,
                            SIDECAR_FETCH_ATTEMPTS,
                            resolved,
                            delay,
                            e.toString());
                    sleepQuietly(delay);
                    continue;
                }
                break;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        log.warn("Sidecar fetch failed for {}: {}", resolved,
                finalError == null ? "unknown" : finalError.toString());
        failed.incrementAndGet();
    }

    private static boolean isRetryableSidecarError(Exception e) {
        if (e == null) return false;
        if (e instanceof SocketTimeoutException) return true;
        String m = e.getMessage();
        if (m == null) return false;
        String lower = m.toLowerCase(Locale.US);
        return lower.contains("timeout")
                || lower.contains("http_5")
                || lower.contains("http_429");
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isImageTarget(File target) {
        if (target == null) return false;
        String name = target.getName().toLowerCase(Locale.US);
        return name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".png") || name.endsWith(".webp");
    }

    private static boolean isValidImageFile(File file) {
        if (file == null || !file.exists() || file.length() <= 0) return false;
        byte[] head = new byte[12];
        int read = 0;
        try (FileInputStream in = new FileInputStream(file)) {
            read = in.read(head);
        } catch (Exception e) {
            return false;
        }
        if (read >= 3
                && (head[0] & 0xFF) == 0xFF
                && (head[1] & 0xFF) == 0xD8
                && (head[2] & 0xFF) == 0xFF) {
            return true; // JPEG
        }
        if (read >= 8
                && (head[0] & 0xFF) == 0x89
                && head[1] == 0x50
                && head[2] == 0x4E
                && head[3] == 0x47
                && (head[4] & 0xFF) == 0x0D
                && (head[5] & 0xFF) == 0x0A
                && (head[6] & 0xFF) == 0x1A
                && (head[7] & 0xFF) == 0x0A) {
            return true; // PNG
        }
        if (read >= 12
                && head[0] == 0x52
                && head[1] == 0x49
                && head[2] == 0x46
                && head[3] == 0x46
                && head[8] == 0x57
                && head[9] == 0x45
                && head[10] == 0x42
                && head[11] == 0x50) {
            return true; // WEBP
        }
        return false;
    }

    /** Same resolution rules as {@code DownloadTask.resolveDownloadUrl}. */
    private static String resolveUrl(String base, String raw) {
        String value = raw.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) return value;
        if (base == null || base.trim().isEmpty()) {
            throw new IllegalArgumentException("CONTROL_PLANE_BASE_MISSING");
        }
        String b = base.trim();
        if (b.endsWith("/") && value.startsWith("/")) return b.substring(0, b.length() - 1) + value;
        if (!b.endsWith("/") && !value.startsWith("/")) return b + "/" + value;
        return b + value;
    }
}
