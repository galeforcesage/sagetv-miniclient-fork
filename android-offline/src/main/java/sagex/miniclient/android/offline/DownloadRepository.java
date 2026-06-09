/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sagex.miniclient.android.offline;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe repository for download metadata, backed by SQLite
 * (PRD §6.1 / OQ-6 — the on-device "Wiz.bin equivalent").
 *
 * <p>An in-memory cache mirrors the database for fast read-mostly access;
 * each mutation writes a single row via {@code INSERT OR REPLACE} or
 * {@code DELETE}. Capacity is capped at {@link #MAX_ENTRIES} to bound disk
 * footprint, matching the prior JSON store's behaviour.
 *
 * <p>On first launch we auto-import any pre-existing {@code downloads.json}
 * from the JSON-era build, then rename it to {@code downloads.json.migrated}
 * so the import is a one-shot.
 */
public class DownloadRepository {
    private static final Logger log = LoggerFactory.getLogger(DownloadRepository.class);
    private static final String LEGACY_JSON_FILENAME = "downloads.json";
    private static final String LEGACY_MIGRATED_SUFFIX = ".migrated";
    private static final int MAX_ENTRIES = 25;

    private final DownloadDatabaseHelper dbHelper;
    private final File legacyJsonFile;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private List<DownloadMetadata> cache;

    private static final class SearchDoc {
        String title;
        String recording;
        String description;
        String people;
        String category;
        String channel;
        String airDate;
        String allText;
    }

    public DownloadRepository(Context context) {
        this.dbHelper = new DownloadDatabaseHelper(context);
        this.legacyJsonFile = new File(context.getFilesDir(), LEGACY_JSON_FILENAME);
        migrateLegacyJsonIfPresent();
        this.cache = loadFromDb();
        rebuildSearchIndex();
    }

    public List<DownloadMetadata> getAll() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(cache);
        } finally {
            lock.readLock().unlock();
        }
    }

    public DownloadMetadata getByMediaFileID(String mediaFileID) {
        lock.readLock().lock();
        try {
            for (DownloadMetadata meta : cache) {
                if (meta.getMediaFileID().equals(mediaFileID)) {
                    return meta;
                }
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public DownloadMetadata getBySessionToken(String sessionToken) {
        if (sessionToken == null || sessionToken.isEmpty()) {
            return null;
        }
        lock.readLock().lock();
        try {
            for (DownloadMetadata meta : cache) {
                if (sessionToken.equals(meta.getSessionToken())) {
                    return meta;
                }
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean add(DownloadMetadata metadata) {
        lock.writeLock().lock();
        try {
            // If we're replacing an existing row with the same ID, count it as in-bounds.
            boolean replacing = false;
            for (DownloadMetadata m : cache) {
                if (m.getMediaFileID().equals(metadata.getMediaFileID())) {
                    replacing = true;
                    break;
                }
            }
            if (!replacing && cache.size() >= MAX_ENTRIES) {
                log.warn("Download repository full ({} entries), rejecting new download: {}",
                        MAX_ENTRIES, metadata.getMediaFileID());
                return false;
            }
            cache.removeIf(m -> m.getMediaFileID().equals(metadata.getMediaFileID()));
            cache.add(metadata);
            upsertRow(metadata);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void update(DownloadMetadata metadata) {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < cache.size(); i++) {
                if (cache.get(i).getMediaFileID().equals(metadata.getMediaFileID())) {
                    cache.set(i, metadata);
                    upsertRow(metadata);
                    return;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void remove(String mediaFileID) {
        lock.writeLock().lock();
        try {
            cache.removeIf(m -> m.getMediaFileID().equals(mediaFileID));
            deleteRow(mediaFileID);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<DownloadMetadata> getByStatus(DownloadMetadata.Status status) {
        lock.readLock().lock();
        try {
            List<DownloadMetadata> result = new ArrayList<>();
            for (DownloadMetadata meta : cache) {
                if (meta.getStatus() == status) {
                    result.add(meta);
                }
            }
            if (status == DownloadMetadata.Status.QUEUED || status == DownloadMetadata.Status.PREPARING) {
                result.sort(new Comparator<DownloadMetadata>() {
                    @Override
                    public int compare(DownloadMetadata a, DownloadMetadata b) {
                        if (a.getQueuePriority() != b.getQueuePriority()) {
                            return Integer.compare(b.getQueuePriority(), a.getQueuePriority());
                        }
                        return Long.compare(a.getAddedTimestamp(), b.getAddedTimestamp());
                    }
                });
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<DownloadMetadata> search(String query, int limit) {
        if (query == null || query.trim().isEmpty()) {
            return getAll();
        }
        int boundedLimit = limit <= 0 ? MAX_ENTRIES : Math.min(limit, MAX_ENTRIES);
        lock.readLock().lock();
        try {
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            String sql = "SELECT s." + DownloadDatabaseHelper.COL_SEARCH_MEDIA_FILE_ID
                    + " FROM " + DownloadDatabaseHelper.SEARCH_FTS_TABLE + " f"
                    + " JOIN " + DownloadDatabaseHelper.SEARCH_TABLE + " s"
                    + " ON s." + DownloadDatabaseHelper.COL_SEARCH_MEDIA_FILE_ID
                    + " = f.media_file_id"
                    + " WHERE f." + DownloadDatabaseHelper.SEARCH_FTS_TABLE + " MATCH ?"
                    + " ORDER BY s." + DownloadDatabaseHelper.COL_SEARCH_AIR_DATE + " DESC,"
                    + " s." + DownloadDatabaseHelper.COL_SEARCH_TITLE + " COLLATE NOCASE ASC"
                    + " LIMIT " + boundedLimit;

            List<DownloadMetadata> result = new ArrayList<>();
            try (Cursor c = db.rawQuery(sql, new String[]{query.trim()})) {
                int colId = c.getColumnIndexOrThrow(DownloadDatabaseHelper.COL_SEARCH_MEDIA_FILE_ID);
                while (c.moveToNext()) {
                    String id = c.getString(colId);
                    if (id == null || id.isEmpty()) continue;
                    DownloadMetadata meta = findCachedById(id);
                    if (meta != null) {
                        result.add(meta);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Search query failed for '{}': {}", query, e.toString());
            return new ArrayList<>();
        } finally {
            lock.readLock().unlock();
        }
    }

    public String getOfflineCacheList() {
        lock.readLock().lock();
        try {
            StringBuilder sb = new StringBuilder();
            for (DownloadMetadata meta : cache) {
                if (meta.getStatus() == DownloadMetadata.Status.COMPLETE) {
                    sb.append(meta.getMediaFileID()).append("|");
                }
            }
            return sb.toString();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Persist a single row (INSERT OR REPLACE). Errors are logged, not thrown. */
    private void upsertRow(DownloadMetadata meta) {
        if (meta == null || meta.getMediaFileID() == null || meta.getMediaFileID().isEmpty()) {
            return;
        }
        try {
            ContentValues cv = new ContentValues();
            cv.put(DownloadDatabaseHelper.COL_MEDIA_FILE_ID, meta.getMediaFileID());
            cv.put(DownloadDatabaseHelper.COL_STATUS,
                    meta.getStatus() == null ? "QUEUED" : meta.getStatus().name());
            cv.put(DownloadDatabaseHelper.COL_ADDED_TIMESTAMP, meta.getAddedTimestamp());
            cv.put(DownloadDatabaseHelper.COL_QUEUE_PRIORITY, meta.getQueuePriority());
                cv.put(DownloadDatabaseHelper.COL_WATCHED, meta.isWatched() ? 1 : 0);
                cv.put(DownloadDatabaseHelper.COL_AUTO_COMSKIP, meta.isAutoComskip() ? 1 : 0);
                cv.put(DownloadDatabaseHelper.COL_HAS_METADATA, meta.hasMetadata() ? 1 : 0);
                cv.put(DownloadDatabaseHelper.COL_HAS_ARTWORK, meta.hasArtwork() ? 1 : 0);
                cv.put(DownloadDatabaseHelper.COL_HAS_CAPTIONS, meta.hasCaptions() ? 1 : 0);
                cv.put(DownloadDatabaseHelper.COL_HAS_COMSKIP, meta.hasComskip() ? 1 : 0);
                cv.put(DownloadDatabaseHelper.COL_HAS_TRANSCRIPT, meta.hasTranscript() ? 1 : 0);
            cv.put(DownloadDatabaseHelper.COL_DATA_JSON, toJson(meta).toString());
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            db.beginTransaction();
            try {
                db.insertWithOnConflict(DownloadDatabaseHelper.TABLE, null, cv,
                        SQLiteDatabase.CONFLICT_REPLACE);
                upsertSearchRow(db, meta);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Exception e) {
            log.error("Failed to upsert download row for {}", meta.getMediaFileID(), e);
        }
    }

    private void deleteRow(String mediaFileID) {
        try {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            db.beginTransaction();
            try {
                db.delete(DownloadDatabaseHelper.TABLE,
                        DownloadDatabaseHelper.COL_MEDIA_FILE_ID + " = ?",
                        new String[] { mediaFileID });
                deleteSearchRow(db, mediaFileID);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Exception e) {
            log.error("Failed to delete download row for {}", mediaFileID, e);
        }
    }

    private void rebuildSearchIndex() {
        try {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            db.beginTransaction();
            try {
                db.delete(DownloadDatabaseHelper.SEARCH_TABLE, null, null);
                db.delete(DownloadDatabaseHelper.SEARCH_FTS_TABLE, null, null);
                for (DownloadMetadata meta : cache) {
                    upsertSearchRow(db, meta);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Exception e) {
            log.warn("Failed to rebuild downloads search index: {}", e.toString());
        }
    }

    private void upsertSearchRow(SQLiteDatabase db, DownloadMetadata meta) {
        if (db == null || meta == null) return;
        String mediaFileId = meta.getMediaFileID();
        if (mediaFileId == null || mediaFileId.isEmpty()) return;

        SearchDoc doc = buildSearchDoc(meta);
        ContentValues cv = new ContentValues();
        cv.put(DownloadDatabaseHelper.COL_SEARCH_MEDIA_FILE_ID, mediaFileId);
        cv.put(DownloadDatabaseHelper.COL_SEARCH_TITLE, doc.title);
        cv.put(DownloadDatabaseHelper.COL_SEARCH_RECORDING, doc.recording);
        cv.put(DownloadDatabaseHelper.COL_SEARCH_DESCRIPTION, doc.description);
        cv.put(DownloadDatabaseHelper.COL_SEARCH_PEOPLE, doc.people);
        cv.put(DownloadDatabaseHelper.COL_SEARCH_CATEGORY, doc.category);
        cv.put(DownloadDatabaseHelper.COL_SEARCH_CHANNEL, doc.channel);
        cv.put(DownloadDatabaseHelper.COL_SEARCH_AIR_DATE, doc.airDate);
        cv.put(DownloadDatabaseHelper.COL_SEARCH_STATUS,
                meta.getStatus() == null ? "QUEUED" : meta.getStatus().name());
        cv.put(DownloadDatabaseHelper.COL_SEARCH_ALL_TEXT, doc.allText);
        db.insertWithOnConflict(DownloadDatabaseHelper.SEARCH_TABLE, null, cv,
                SQLiteDatabase.CONFLICT_REPLACE);

        deleteSearchRowFts(db, mediaFileId);
        ContentValues fts = new ContentValues();
        fts.put("media_file_id", mediaFileId);
        fts.put("title", safeLower(doc.title));
        fts.put("recording", safeLower(doc.recording));
        fts.put("description", safeLower(doc.description));
        fts.put("people", safeLower(doc.people));
        fts.put("all_text", safeLower(doc.allText));
        db.insert(DownloadDatabaseHelper.SEARCH_FTS_TABLE, null, fts);
    }

    private void deleteSearchRow(SQLiteDatabase db, String mediaFileID) {
        if (db == null || mediaFileID == null || mediaFileID.isEmpty()) return;
        db.delete(DownloadDatabaseHelper.SEARCH_TABLE,
                DownloadDatabaseHelper.COL_SEARCH_MEDIA_FILE_ID + " = ?",
                new String[]{mediaFileID});
        deleteSearchRowFts(db, mediaFileID);
    }

    private void deleteSearchRowFts(SQLiteDatabase db, String mediaFileID) {
        db.delete(DownloadDatabaseHelper.SEARCH_FTS_TABLE,
                "media_file_id = ?",
                new String[]{mediaFileID});
    }

    private DownloadMetadata findCachedById(String mediaFileID) {
        for (DownloadMetadata meta : cache) {
            if (mediaFileID.equals(meta.getMediaFileID())) return meta;
        }
        return null;
    }

    private List<DownloadMetadata> loadFromDb() {
        List<DownloadMetadata> list = new ArrayList<>();
        SQLiteDatabase db;
        try {
            db = dbHelper.getReadableDatabase();
        } catch (Exception e) {
            log.error("Failed to open downloads database", e);
            return list;
        }
        try (Cursor c = db.query(DownloadDatabaseHelper.TABLE,
                new String[] { 
                    DownloadDatabaseHelper.COL_DATA_JSON,
                    DownloadDatabaseHelper.COL_HAS_METADATA,
                    DownloadDatabaseHelper.COL_HAS_ARTWORK,
                    DownloadDatabaseHelper.COL_HAS_CAPTIONS,
                    DownloadDatabaseHelper.COL_HAS_COMSKIP,
                    DownloadDatabaseHelper.COL_HAS_TRANSCRIPT
                },
                null, null, null, null,
                DownloadDatabaseHelper.COL_ADDED_TIMESTAMP + " ASC")) {
            int colJson = c.getColumnIndexOrThrow(DownloadDatabaseHelper.COL_DATA_JSON);
            int colHasMetadata = c.getColumnIndexOrThrow(DownloadDatabaseHelper.COL_HAS_METADATA);
            int colHasArtwork = c.getColumnIndexOrThrow(DownloadDatabaseHelper.COL_HAS_ARTWORK);
            int colHasCaptions = c.getColumnIndexOrThrow(DownloadDatabaseHelper.COL_HAS_CAPTIONS);
            int colHasComskip = c.getColumnIndexOrThrow(DownloadDatabaseHelper.COL_HAS_COMSKIP);
            int colHasTranscript = c.getColumnIndexOrThrow(DownloadDatabaseHelper.COL_HAS_TRANSCRIPT);
            while (c.moveToNext()) {
                String json = c.getString(colJson);
                if (json == null || json.isEmpty()) continue;
                try {
                    DownloadMetadata meta = fromJson(new JSONObject(json));
                    // Load sidecar availability flags from denormalized columns
                    meta.setHasMetadata(c.getInt(colHasMetadata) != 0);
                    meta.setHasArtwork(c.getInt(colHasArtwork) != 0);
                    meta.setHasCaptions(c.getInt(colHasCaptions) != 0);
                    meta.setHasComskip(c.getInt(colHasComskip) != 0);
                    meta.setHasTranscript(c.getInt(colHasTranscript) != 0);
                    list.add(meta);
                } catch (Exception e) {
                    log.warn("Skipping malformed download row: {}", e.toString());
                }
            }
        } catch (Exception e) {
            log.error("Failed to read downloads table", e);
        }
        return list;
    }

    /**
     * One-shot import of legacy {@code downloads.json} into the SQLite
     * store. Idempotent: after import we rename the JSON file with a
     * {@code .migrated} suffix so subsequent launches skip the path.
     * Safe to call even when the DB already has rows — duplicates are
     * resolved by {@code INSERT OR REPLACE}.
     */
    private void migrateLegacyJsonIfPresent() {
        if (!legacyJsonFile.exists()) return;
        try (FileInputStream fis = new FileInputStream(legacyJsonFile);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            if (sb.length() == 0) {
                renameMigrated();
                return;
            }
            JSONArray arr = new JSONArray(sb.toString());
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            db.beginTransaction();
            try {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String id = obj.optString("mediaFileID", null);
                    if (id == null || id.isEmpty()) continue;
                    DownloadMetadata m = fromJson(obj);
                    ContentValues cv = new ContentValues();
                    cv.put(DownloadDatabaseHelper.COL_MEDIA_FILE_ID, id);
                    cv.put(DownloadDatabaseHelper.COL_STATUS,
                            m.getStatus() == null ? "QUEUED" : m.getStatus().name());
                    cv.put(DownloadDatabaseHelper.COL_ADDED_TIMESTAMP, m.getAddedTimestamp());
                    cv.put(DownloadDatabaseHelper.COL_QUEUE_PRIORITY, m.getQueuePriority());
                        cv.put(DownloadDatabaseHelper.COL_WATCHED, m.isWatched() ? 1 : 0);
                        cv.put(DownloadDatabaseHelper.COL_AUTO_COMSKIP, m.isAutoComskip() ? 1 : 0);
                        cv.put(DownloadDatabaseHelper.COL_HAS_METADATA, m.hasMetadata() ? 1 : 0);
                        cv.put(DownloadDatabaseHelper.COL_HAS_ARTWORK, m.hasArtwork() ? 1 : 0);
                        cv.put(DownloadDatabaseHelper.COL_HAS_CAPTIONS, m.hasCaptions() ? 1 : 0);
                        cv.put(DownloadDatabaseHelper.COL_HAS_COMSKIP, m.hasComskip() ? 1 : 0);
                        cv.put(DownloadDatabaseHelper.COL_HAS_TRANSCRIPT, m.hasTranscript() ? 1 : 0);
                    cv.put(DownloadDatabaseHelper.COL_DATA_JSON, toJson(m).toString());
                    db.insertWithOnConflict(DownloadDatabaseHelper.TABLE, null, cv,
                            SQLiteDatabase.CONFLICT_REPLACE);
                    upsertSearchRow(db, m);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            log.info("Migrated {} download(s) from legacy downloads.json to SQLite",
                    arr.length());
            renameMigrated();
        } catch (Exception e) {
            log.error("Failed to migrate legacy downloads.json — leaving file in place for retry", e);
        }
    }

    private void renameMigrated() {
        File target = new File(legacyJsonFile.getAbsolutePath() + LEGACY_MIGRATED_SUFFIX);
        if (target.exists()) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        legacyJsonFile.renameTo(target);
    }

    private static JSONObject toJson(DownloadMetadata meta) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("mediaFileID", meta.getMediaFileID());
        obj.put("title", meta.getTitle());
        obj.put("serverPath", meta.getServerPath());
        obj.put("localUri", meta.getLocalUri());
        obj.put("container", meta.getContainer());
        obj.put("fileSize", meta.getFileSize());
        obj.put("downloadedBytes", meta.getDownloadedBytes());
        obj.put("duration", meta.getDuration());
        obj.put("addedTimestamp", meta.getAddedTimestamp());
        obj.put("thumbnailUrl", meta.getThumbnailUrl());
        obj.put("status", meta.getStatus().name());
        obj.put("errorMessage", meta.getErrorMessage());
        obj.put("retryCount", meta.getRetryCount());
        obj.put("recordingState", meta.getRecordingState());
        obj.put("sessionToken", meta.getSessionToken());
        obj.put("downloadUrl", meta.getDownloadUrl());
        obj.put("transferSessionState", meta.getTransferSessionState());
        obj.put("accountFamily", meta.getAccountFamily());
        obj.put("accountUsername", meta.getAccountUsername());
        obj.put("correlationId", meta.getCorrelationId());
        obj.put("sessionId", meta.getSessionId());
        obj.put("authFailoverCount", meta.getAuthFailoverCount());
        obj.put("accountPoolExhausted", meta.isAccountPoolExhausted());
        obj.put("resumeFromOffset", meta.getResumeFromOffset());
        obj.put("reconnectGraceSeconds", meta.getReconnectGraceSeconds());
        obj.put("expiresInSeconds", meta.getExpiresInSeconds());
        obj.put("effectiveRateKbps", meta.getEffectiveRateKbps());
        obj.put("requestedPolicyJson", meta.getRequestedPolicyJson());
        obj.put("acceptedPolicyJson", meta.getAcceptedPolicyJson());
        obj.put("policyAdjustmentsJson", meta.getPolicyAdjustmentsJson());
        obj.put("recentReasonCodesJson", meta.getRecentReasonCodesJson());
        obj.put("serverQueueItemId", meta.getServerQueueItemId());
        obj.put("queuePriority", meta.getQueuePriority());
        obj.put("mergedRequestCount", meta.getMergedRequestCount());
        obj.put("downloadSpeedBytesPerSec", meta.getDownloadSpeedBytesPerSec());
        obj.put("etaSeconds", meta.getEtaSeconds());
        obj.put("lastProgressTimestampMs", meta.getLastProgressTimestampMs());
        obj.put("invalidRangeRetried", meta.isInvalidRangeRetried());
        // Offline companion content (M2)
        obj.put("companionDirPath", meta.getCompanionDirPath());
        obj.put("offlineMetadataJson", meta.getOfflineMetadataJson());
        obj.put("artworkManifestJson", meta.getArtworkManifestJson());
        obj.put("captionsManifestJson", meta.getCaptionsManifestJson());
        obj.put("comskipManifestJson", meta.getComskipManifestJson());
        obj.put("transcriptManifestJson", meta.getTranscriptManifestJson());
        obj.put("offlineMetadataUrl", meta.getOfflineMetadataUrl());
        obj.put("offlineMetadataPath", meta.getOfflineMetadataPath());
        obj.put("offlineInlineLevel", meta.getOfflineInlineLevel());
        obj.put("previewAiredOn", meta.getPreviewAiredOn());
        obj.put("previewCategory", meta.getPreviewCategory());
        obj.put("previewChannel", meta.getPreviewChannel());
        obj.put("previewDescription", meta.getPreviewDescription());
        obj.put("previewThumbnailPath", meta.getPreviewThumbnailPath());
        obj.put("watched", meta.isWatched());
        obj.put("autoComskip", meta.isAutoComskip());
        obj.put("playbackPositionMs", meta.getPlaybackPositionMs());
        obj.put("sidecarSelectionConfigured", meta.isSidecarSelectionConfigured());
        obj.put("sidecarRefreshArtwork", meta.isSidecarRefreshArtwork());
        obj.put("sidecarRefreshCaptions", meta.isSidecarRefreshCaptions());
        obj.put("sidecarRefreshComskip", meta.isSidecarRefreshComskip());
        obj.put("sidecarRefreshTranscript", meta.isSidecarRefreshTranscript());
        return obj;
    }

    private static DownloadMetadata fromJson(JSONObject obj) throws JSONException {
        DownloadMetadata meta = new DownloadMetadata();
        meta.setMediaFileID(obj.optString("mediaFileID"));
        meta.setTitle(obj.optString("title"));
        meta.setServerPath(obj.optString("serverPath"));
        meta.setLocalUri(obj.optString("localUri"));
        meta.setContainer(obj.optString("container"));
        meta.setFileSize(obj.optLong("fileSize", 0));
        meta.setDownloadedBytes(obj.optLong("downloadedBytes", 0));
        meta.setDuration(obj.optLong("duration", 0));
        meta.setAddedTimestamp(obj.optLong("addedTimestamp", 0));
        meta.setThumbnailUrl(obj.optString("thumbnailUrl"));
        meta.setErrorMessage(obj.optString("errorMessage", null));
        meta.setRetryCount(obj.optInt("retryCount", 0));
        meta.setRecordingState(obj.optString("recordingState", null));
        meta.setSessionToken(obj.optString("sessionToken", null));
        meta.setDownloadUrl(obj.optString("downloadUrl", null));
        meta.setTransferSessionState(obj.optString("transferSessionState", null));
        meta.setAccountFamily(obj.optString("accountFamily", null));
        meta.setAccountUsername(obj.optString("accountUsername", null));
        meta.setCorrelationId(obj.optString("correlationId", null));
        meta.setSessionId(obj.optString("sessionId", null));
        meta.setAuthFailoverCount(obj.optInt("authFailoverCount", 0));
        meta.setAccountPoolExhausted(obj.optBoolean("accountPoolExhausted", false));
        meta.setResumeFromOffset(obj.optLong("resumeFromOffset", 0));
        meta.setReconnectGraceSeconds(obj.optLong("reconnectGraceSeconds", 0));
        meta.setExpiresInSeconds(obj.optLong("expiresInSeconds", 0));
        meta.setEffectiveRateKbps(obj.optLong("effectiveRateKbps", 0));
        meta.setRequestedPolicyJson(obj.optString("requestedPolicyJson", null));
        meta.setAcceptedPolicyJson(obj.optString("acceptedPolicyJson", null));
        meta.setPolicyAdjustmentsJson(obj.optString("policyAdjustmentsJson", null));
        meta.setRecentReasonCodesJson(obj.optString("recentReasonCodesJson", null));
        meta.setServerQueueItemId(obj.optString("serverQueueItemId", null));
        meta.setQueuePriority(obj.optInt("queuePriority", 0));
        meta.setMergedRequestCount(Math.max(1, obj.optInt("mergedRequestCount", 1)));
        meta.setDownloadSpeedBytesPerSec(obj.optLong("downloadSpeedBytesPerSec", 0));
        meta.setEtaSeconds(obj.optLong("etaSeconds", 0));
        meta.setLastProgressTimestampMs(obj.optLong("lastProgressTimestampMs", 0));
        meta.setInvalidRangeRetried(obj.optBoolean("invalidRangeRetried", false));
        meta.setCompanionDirPath(obj.optString("companionDirPath", null));
        meta.setOfflineMetadataJson(obj.optString("offlineMetadataJson", null));
        meta.setArtworkManifestJson(obj.optString("artworkManifestJson", null));
        meta.setCaptionsManifestJson(obj.optString("captionsManifestJson", null));
        meta.setComskipManifestJson(obj.optString("comskipManifestJson", null));
        meta.setTranscriptManifestJson(obj.optString("transcriptManifestJson", null));
        meta.setOfflineMetadataUrl(obj.optString("offlineMetadataUrl", null));
        meta.setOfflineMetadataPath(obj.optString("offlineMetadataPath", null));
        meta.setOfflineInlineLevel(obj.optString("offlineInlineLevel", null));
        meta.setPreviewAiredOn(obj.optString("previewAiredOn", null));
        meta.setPreviewCategory(obj.optString("previewCategory", null));
        meta.setPreviewChannel(obj.optString("previewChannel", null));
        meta.setPreviewDescription(obj.optString("previewDescription", null));
        meta.setPreviewThumbnailPath(obj.optString("previewThumbnailPath", null));
        meta.setWatched(obj.optBoolean("watched", false));
        meta.setAutoComskip(obj.optBoolean("autoComskip", false));
        meta.setPlaybackPositionMs(obj.optLong("playbackPositionMs", 0));
        meta.setSidecarSelectionConfigured(obj.optBoolean("sidecarSelectionConfigured", false));
        meta.setSidecarRefreshArtwork(obj.optBoolean("sidecarRefreshArtwork", true));
        meta.setSidecarRefreshCaptions(obj.optBoolean("sidecarRefreshCaptions", false));
        meta.setSidecarRefreshComskip(obj.optBoolean("sidecarRefreshComskip", false));
        meta.setSidecarRefreshTranscript(obj.optBoolean("sidecarRefreshTranscript", false));
        try {
            meta.setStatus(DownloadMetadata.Status.valueOf(obj.optString("status", "QUEUED")));
        } catch (IllegalArgumentException e) {
            meta.setStatus(DownloadMetadata.Status.QUEUED);
        }
        return meta;
    }

    private static SearchDoc buildSearchDoc(DownloadMetadata meta) {
        SearchDoc out = new SearchDoc();
        StringBuilder recording = new StringBuilder();
        StringBuilder people = new StringBuilder();
        StringBuilder all = new StringBuilder();

        appendPart(recording, meta.getMediaFileID());
        appendPart(recording, meta.getTitle());
        appendPart(recording, meta.getServerPath());

        out.title = firstNonEmpty(meta.getTitle());
        out.description = firstNonEmpty(meta.getPreviewDescription());
        out.category = firstNonEmpty(meta.getPreviewCategory());
        out.channel = firstNonEmpty(meta.getPreviewChannel());
        out.airDate = firstNonEmpty(meta.getPreviewAiredOn());

        String offline = meta.getOfflineMetadataJson();
        if (offline != null && !offline.isEmpty()) {
            try {
                OfflineManifestV1 manifest = OfflineManifestV1.parse(offline);
                JSONObject m = manifest.getMetadata();
                out.title = firstNonEmpty(out.title, manifest.getTitle(), manifest.getSubtitle());
                out.description = firstNonEmpty(out.description, manifest.getPrimaryDescription());
                out.channel = firstNonEmpty(out.channel,
                        nullIfEmpty(m.optString("channel_name", null)),
                        nullIfEmpty(m.optString("channel", null)),
                        nullIfEmpty(m.optString("network", null)),
                        nullIfEmpty(m.optString("station", null)));
                out.airDate = firstNonEmpty(out.airDate,
                        nullIfEmpty(m.optString("original_air_date", null)),
                        nullIfEmpty(m.optString("aired_on", null)),
                        nullIfEmpty(m.optString("air_date", null)));
                List<String> categories = manifest.getCategories();
                if (!categories.isEmpty()) {
                    out.category = firstNonEmpty(out.category, String.join(" ", categories));
                }

                appendPart(recording, manifest.getRecordingId());
                appendPart(recording, m.optString("format", null));
                appendPart(recording, m.optString("audio_format_summary", null));
                appendPart(recording, manifest.getTitle());
                appendPart(recording, manifest.getSubtitle());
                appendPart(recording, m.optString("show_id", null));

                for (OfflineManifestV1.Credit credit : manifest.getCredits()) {
                    appendPart(people, credit.personName);
                    appendPart(people, credit.roleName);
                    appendPart(people, credit.personId);
                }

                appendAllStrings(manifest.getRoot(), all);
            } catch (Exception e) {
                appendPart(all, offline);
            }
        }

        out.recording = recording.toString();
        out.people = people.toString();
        appendPart(all, out.title);
        appendPart(all, out.recording);
        appendPart(all, out.description);
        appendPart(all, out.people);
        appendPart(all, out.category);
        appendPart(all, out.channel);
        appendPart(all, out.airDate);
        out.allText = all.toString();
        return out;
    }

    private static void appendPeopleFromRole(StringBuilder out, JSONArray peopleArr) {
        if (peopleArr == null) return;
        for (int i = 0; i < peopleArr.length(); i++) {
            JSONObject p = peopleArr.optJSONObject(i);
            if (p == null) continue;
            appendPart(out, p.optString("name", null));
            appendPart(out, p.optString("role", null));
            appendPart(out, p.optString("billing", null));
            appendPart(out, p.optString("person_id", null));
        }
    }

    private static String joinArray(JSONArray arr) {
        if (arr == null || arr.length() == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            String value = nullIfEmpty(arr.optString(i, null));
            if (value == null) continue;
            appendPart(sb, value);
        }
        return sb.toString();
    }

    private static void appendAllStrings(Object node, StringBuilder out) {
        if (node == null || node == JSONObject.NULL) return;
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            JSONArray names = obj.names();
            if (names == null) return;
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i, null);
                if (key == null) continue;
                appendPart(out, key);
                Object child = obj.opt(key);
                appendAllStrings(child, out);
            }
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                appendAllStrings(arr.opt(i), out);
            }
            return;
        }
        appendPart(out, String.valueOf(node));
    }

    private static void appendPart(StringBuilder sb, String value) {
        String v = nullIfEmpty(value);
        if (v == null) return;
        if (sb.length() > 0) sb.append(' ');
        sb.append(v);
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return null;
        for (String value : values) {
            String v = nullIfEmpty(value);
            if (v != null) return v;
        }
        return null;
    }

    private static String nullIfEmpty(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    private static String safeLower(String value) {
        if (value == null) return null;
        return value.toLowerCase();
    }
}
