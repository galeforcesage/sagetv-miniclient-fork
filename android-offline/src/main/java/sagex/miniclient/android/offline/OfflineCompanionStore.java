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
import android.net.Uri;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Companion-content side of M2 — see
 * {@code /memories/repo/offline-companion-spec.md}.
 *
 * <p>Splits the server-supplied {@code offline} JSON object into its
 * sub-blocks, stashes raw JSON on the {@link DownloadMetadata} for fast
 * UI rendering later, and writes the same blobs under a companion directory.
 * For file:// destinations this is {@code <file>.companion/}; for SAF
 * content:// destinations it is an app-private directory under
 * {@code files/offline-companion/} keyed by mediaFileID.
 */
final class OfflineCompanionStore {
    private static final Logger log = LoggerFactory.getLogger(OfflineCompanionStore.class);

    static final String SUFFIX = ".companion";
    static final String FILE_METADATA   = "manifest.json";
    static final String FILE_ARTWORK    = "artwork.json";
    static final String FILE_CAPTIONS   = "captions.json";
    static final String FILE_COMSKIP    = "comskip.json";
    static final String FILE_TRANSCRIPT = "transcript.json";
    private static final String SAF_COMPANION_ROOT = "offline-companion";

    private OfflineCompanionStore() {}

    /**
     * Parse {@code offlineJson} (the raw "offline" object), stash each
     * sub-block on {@code meta}, and write the same blobs under
     * {@code <localUri>.companion/} when {@code localUri} is a file:// URI.
     *
     * <p>Safe to call when {@code offlineJson == null} — it's a no-op.
     * Safe to call repeatedly (e.g. on merge) — each call overwrites.
     */
    static void apply(Context context, DownloadMetadata meta, String offlineJson) {
        if (meta == null || offlineJson == null || offlineJson.isEmpty()) {
            return;
        }
        String metadataJson;
        String artworkJson;
        String captionsJson;
        String comskipJson;
        String transcriptJson;
        OfflineManifestV1 manifest;
        try {
            manifest = OfflineManifestV1.parse(offlineJson);
            metadataJson = manifest.getRawJson();
            artworkJson = optRawString(manifest.getAssets(), "images");
            captionsJson = optRawString(manifest.getAssets(), "captions");
            comskipJson = optRawString(manifest.getAssets(), "comskip");
            transcriptJson = optRawString(manifest.getAssets(), "transcript");
        } catch (Exception e) {
            log.warn("manifest_parse_failure mediaFileID={} reason={}",
                    meta.getMediaFileID(), e.toString());
            return;
        }

        log.info("manifest_parse_success mediaFileID={} hash={} metadata_key_count={} credits_count={} artwork_count={}",
                meta.getMediaFileID(),
                manifest.getSnippetHash(),
                manifest.getMetadata().length(),
                manifest.getCredits().size(),
                manifest.getArtworkCount());

        if (manifest.findImageByKind("thumbnail") == null) {
            log.warn("manifest_missing_recommended_field mediaFileID={} field=thumbnail", meta.getMediaFileID());
        }
        String rated = manifest.getKnownMetadataValue("rated");
        if (rated == null || rated.trim().isEmpty()) {
            log.warn("manifest_missing_recommended_field mediaFileID={} field=rated", meta.getMediaFileID());
        }

        meta.setOfflineMetadataJson(metadataJson);
        meta.setArtworkManifestJson(artworkJson);
        meta.setCaptionsManifestJson(captionsJson);
        meta.setComskipManifestJson(comskipJson);
        meta.setTranscriptManifestJson(transcriptJson);
        applyPreviewFields(meta, metadataJson);
        applyPlaybackStateFields(meta, manifest);

        File companionDir = resolveCompanionDir(context, meta.getLocalUri(), meta.getMediaFileID());
        if (!ensureCompanionDir(companionDir)) {
            File fallbackDir = resolveAppPrivateCompanionDir(context, meta.getMediaFileID());
            if (!ensureCompanionDir(fallbackDir)) {
                log.info("Companion dir not creatable for {} (localUri={}); manifests held in memory only",
                        meta.getMediaFileID(), meta.getLocalUri());
                return;
            }
            log.warn("Companion dir fallback engaged for {}: primary={} fallback={}",
                    meta.getMediaFileID(), companionDir, fallbackDir);
            companionDir = fallbackDir;
        }
        meta.setCompanionDirPath(companionDir.getAbsolutePath());
        String preferredThumb = "poster.jpg";
        OfflineManifestV1.ImageAsset hero = manifest.pickHeroImage();
        String heroName = OfflineManifestV1.imageFileName(hero);
        if (heroName != null && !heroName.isEmpty()) {
            preferredThumb = heroName;
        }
        meta.setPreviewThumbnailPath(new File(companionDir, preferredThumb).getAbsolutePath());

        writeIfPresent(companionDir, FILE_METADATA,   metadataJson);
        writeIfPresent(companionDir, FILE_ARTWORK,    artworkJson);
        writeIfPresent(companionDir, FILE_CAPTIONS,   captionsJson);
        writeIfPresent(companionDir, FILE_COMSKIP,    comskipJson);
        writeIfPresent(companionDir, FILE_TRANSCRIPT, transcriptJson);

        log.info("Companion content persisted for {} at {}",
                meta.getMediaFileID(), companionDir);
    }

    /**
     * Resolve {@code <file>.companion/} sibling of the destination media
     * file. Returns null for non-file:// URIs (SAF) or if parsing fails.
     */
    static File resolveCompanionDir(Context context, String localUri, String mediaFileID) {
        if (localUri == null || localUri.isEmpty()) return null;
        try {
            Uri uri = Uri.parse(localUri);
            if ("file".equals(uri.getScheme())) {
                String path = uri.getPath();
                if (path == null || path.isEmpty()) return null;
                File media = new File(path);
                String name = media.getName();
                int dot = name.lastIndexOf('.');
                String base = dot > 0 ? name.substring(0, dot) : name;
                return new File(media.getParentFile(), base + SUFFIX);
            }
            if ("content".equals(uri.getScheme()) && context != null) {
                File root = new File(context.getFilesDir(), SAF_COMPANION_ROOT);
                if (!root.exists() && !root.mkdirs()) {
                    return null;
                }
                String key = nullIfEmpty(mediaFileID);
                if (key == null) {
                    key = Integer.toHexString(localUri.hashCode());
                }
                return new File(root, sanitizeName(key) + SUFFIX);
            }
            return null;
        } catch (Exception e) {
            log.warn("Failed to resolve companion dir for {}: {}", localUri, e.toString());
            return null;
        }
    }

    private static File resolveAppPrivateCompanionDir(Context context, String mediaFileID) {
        if (context == null) return null;
        File root = new File(context.getFilesDir(), SAF_COMPANION_ROOT);
        String key = nullIfEmpty(mediaFileID);
        if (key == null) {
            key = "unknown";
        }
        return new File(root, sanitizeName(key) + SUFFIX);
    }

    private static boolean ensureCompanionDir(File dir) {
        if (dir == null) return false;
        if (dir.exists()) return dir.isDirectory();
        return dir.mkdirs();
    }

    private static String sanitizeName(String value) {
        if (value == null || value.isEmpty()) return "unknown";
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * Returns the raw JSON-encoded string for {@code key} if present in
     * {@code obj}, or null. Preserves the original shape (object vs array
     * vs scalar) by re-serializing via JSONObject/JSONArray toString.
     */
    private static String optRawString(JSONObject obj, String key) {
        Object v = obj.opt(key);
        if (v == null || v == JSONObject.NULL) return null;
        return v.toString();
    }

    private static void writeIfPresent(File dir, String name, String contents) {
        if (contents == null || contents.isEmpty()) return;
        File f = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(f);
             OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            w.write(contents);
        } catch (Exception e) {
            log.warn("Failed to write {}: {}", f, e.toString());
        }
    }

    private static void applyPreviewFields(DownloadMetadata meta, String metadataJson) {
        if (meta == null || metadataJson == null || metadataJson.isEmpty()) return;
        try {
            OfflineManifestV1 manifest = OfflineManifestV1.parse(metadataJson);
            JSONObject m = manifest.getMetadata();
            meta.setPreviewAiredOn(nullIfEmpty(firstNonEmpty(
                    m.optString("original_air_date", null),
                    m.optString("aired_on", null),
                    m.optString("air_date", null))));
            meta.setPreviewChannel(nullIfEmpty(firstNonEmpty(
                    m.optString("channel_name", null),
                    m.optString("channel", null),
                    m.optString("network", null),
                    m.optString("station", null))));
            meta.setPreviewDescription(nullIfEmpty(manifest.getPrimaryDescription()));
            List<String> categories = manifest.getCategories();
            meta.setPreviewCategory(categories.isEmpty() ? null : String.join(" / ", categories));
        } catch (Exception e) {
            log.warn("Failed to extract preview fields for {}: {}",
                    meta.getMediaFileID(), e.toString());
        }
    }

    private static void applyPlaybackStateFields(DownloadMetadata meta, OfflineManifestV1 manifest) {
        if (meta == null || manifest == null) return;
        long resumeMs = firstPositivePlaybackMs(
                manifest.getRoot(),
                manifest.getRoot().optJSONObject("core"),
                manifest.getMetadata(),
                manifest.getRoot().optJSONObject("playback"),
                manifest.getRoot().optJSONObject("watch"),
                manifest.getRoot().optJSONObject("watched"));
        long localResumeMs = meta.getPlaybackPositionMs();
        if (resumeMs >= 3000L && resumeMs > localResumeMs) {
            meta.setPlaybackPositionMs(resumeMs);
            log.info("manifest_resume_position_applied mediaFileID={} serverPositionMs={} previousLocalPositionMs={}",
                meta.getMediaFileID(), resumeMs, localResumeMs);
        }

        Boolean watched = firstBoolean(
                manifest.getRoot(),
                manifest.getRoot().optJSONObject("core"),
                manifest.getMetadata(),
                manifest.getRoot().optJSONObject("playback"),
                manifest.getRoot().optJSONObject("watch"),
                manifest.getRoot().optJSONObject("watched"));
        if (watched != null) {
            meta.setWatched(watched);
        }
    }

    private static long firstPositivePlaybackMs(JSONObject... sources) {
        if (sources == null) return 0L;
        String[] msKeys = {
                "playback_position_ms", "playbackPositionMs",
                "resume_position_ms", "resumePositionMs",
                "resume_time_ms", "resumeTimeMs",
                "last_playback_position_ms", "lastPlaybackPositionMs",
                "last_watched_position_ms", "lastWatchedPositionMs",
                "watch_position_ms", "watchPositionMs",
                "bookmark_position_ms", "bookmarkPositionMs",
                "media_time_ms", "mediaTimeMs"
        };
        String[] secondKeys = {
                "playback_position_sec", "playbackPositionSec",
                "resume_position_sec", "resumePositionSec",
                "resume_time_sec", "resumeTimeSec",
                "last_playback_position_sec", "lastPlaybackPositionSec",
                "last_watched_position_sec", "lastWatchedPositionSec",
                "watch_position_sec", "watchPositionSec",
                "bookmark_position_sec", "bookmarkPositionSec",
                "playback_position", "resume_position", "resume_time",
                "last_playback_position", "last_watched_position",
                "watch_position", "bookmark_position"
        };
        for (JSONObject source : sources) {
            long ms = firstPositiveLong(source, msKeys);
            if (ms > 0L) return ms;
            long seconds = firstPositiveLong(source, secondKeys);
            if (seconds > 0L) return seconds * 1000L;
        }
        return 0L;
    }

    private static long firstPositiveLong(JSONObject source, String... keys) {
        if (source == null || keys == null) return 0L;
        for (String key : keys) {
            if (!source.has(key)) continue;
            long value = source.optLong(key, 0L);
            if (value > 0L) return value;
        }
        return 0L;
    }

    private static Boolean firstBoolean(JSONObject... sources) {
        if (sources == null) return null;
        String[] keys = {
                "watched", "is_watched", "isWatched",
                "viewed", "is_viewed", "isViewed",
                "played", "is_played", "isPlayed"
        };
        for (JSONObject source : sources) {
            if (source == null) continue;
            for (String key : keys) {
                if (source.has(key)) {
                    return source.optBoolean(key, false);
                }
            }
        }
        return null;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return null;
        for (String v : values) {
            String n = nullIfEmpty(v);
            if (n != null) return n;
        }
        return null;
    }

    private static String nullIfEmpty(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
