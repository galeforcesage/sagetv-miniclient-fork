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

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe JSON-based repository for download metadata.
 * Stores up to MAX_ENTRIES downloads in a downloads.json file in app-private storage.
 */
public class DownloadRepository {
    private static final Logger log = LoggerFactory.getLogger(DownloadRepository.class);
    private static final String FILENAME = "downloads.json";
    private static final int MAX_ENTRIES = 25;

    private final File storageFile;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private List<DownloadMetadata> cache;

    public DownloadRepository(Context context) {
        this.storageFile = new File(context.getFilesDir(), FILENAME);
        this.cache = load();
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

    public boolean add(DownloadMetadata metadata) {
        lock.writeLock().lock();
        try {
            if (cache.size() >= MAX_ENTRIES) {
                log.warn("Download repository full ({} entries), rejecting new download: {}",
                        MAX_ENTRIES, metadata.getMediaFileID());
                return false;
            }
            // Remove existing entry with same ID (replace)
            cache.removeIf(m -> m.getMediaFileID().equals(metadata.getMediaFileID()));
            cache.add(metadata);
            persist();
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
                    persist();
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
            persist();
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
            return result;
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

    private void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (DownloadMetadata meta : cache) {
                arr.put(toJson(meta));
            }
            try (FileOutputStream fos = new FileOutputStream(storageFile);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                writer.write(arr.toString(2));
            }
        } catch (Exception e) {
            log.error("Failed to persist download repository", e);
        }
    }

    private List<DownloadMetadata> load() {
        if (!storageFile.exists()) {
            return new ArrayList<>();
        }
        try (FileInputStream fis = new FileInputStream(storageFile);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            JSONArray arr = new JSONArray(sb.toString());
            List<DownloadMetadata> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                list.add(fromJson(arr.getJSONObject(i)));
            }
            return list;
        } catch (Exception e) {
            log.error("Failed to load download repository", e);
            return new ArrayList<>();
        }
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
        try {
            meta.setStatus(DownloadMetadata.Status.valueOf(obj.optString("status", "QUEUED")));
        } catch (IllegalArgumentException e) {
            meta.setStatus(DownloadMetadata.Status.QUEUED);
        }
        return meta;
    }
}
