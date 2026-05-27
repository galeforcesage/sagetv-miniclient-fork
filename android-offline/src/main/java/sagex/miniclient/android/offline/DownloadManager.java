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
import android.content.Intent;
import android.os.Build;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sagex.miniclient.DownloadRequest;
import sagex.miniclient.DownloadStatusProvider;
import sagex.miniclient.MiniClient;
import sagex.miniclient.ServerInfo;
import sagex.miniclient.android.MiniclientApplication;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Singleton download queue orchestrator. Manages the lifecycle of media file downloads:
 * enqueue, pause, resume, cancel. Runs downloads serially (1 concurrent) via
 * DownloadForegroundService.
 */
public class DownloadManager implements DownloadTask.ProgressListener, DownloadStatusProvider {
    private static final Logger log = LoggerFactory.getLogger(DownloadManager.class);
    private static final int MAX_RETRIES = 3;
    private static final int MAX_AUTH_FAILOVER_ATTEMPTS = 4;
    private static final long[] RETRY_DELAYS_MS = {5000, 15000, 45000};

    private static volatile DownloadManager instance;

    private final Context context;
    private final DownloadRepository repository;
    private final StorageHelper storageHelper;
    private final DownloadCredentialVault credentialVault;
    private final ExecutorService executor;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private volatile DownloadTask currentTask;
    private volatile String currentMediaFileID;

    private DownloadManager(Context context) {
        this.context = context.getApplicationContext();
        this.repository = new DownloadRepository(this.context);
        this.storageHelper = new StorageHelper(this.context);
        this.credentialVault = new DownloadCredentialVault(this.context);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "DownloadManager-Worker");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        });
    }

    public static DownloadManager getInstance(Context context) {
        if (instance == null) {
            synchronized (DownloadManager.class) {
                if (instance == null) {
                    instance = new DownloadManager(context);
                }
            }
        }
        return instance;
    }

    public StorageHelper getStorageHelper() {
        return storageHelper;
    }

    public DownloadRepository getRepository() {
        return repository;
    }

    /**
     * Enqueue a new download. Creates metadata, validates storage, and starts the service.
     *
     * @return true if enqueued successfully, false if rejected (full, no space, duplicate)
     */
    public boolean enqueue(DownloadRequest request) {
        if (request == null || request.getMediaFileID() == null) {
            log.warn("Rejecting null or invalid download request");
            return false;
        }

        // Check for duplicate
        DownloadMetadata existing = repository.getByMediaFileID(request.getMediaFileID());
        if (existing != null && existing.getStatus() == DownloadMetadata.Status.COMPLETE) {
            log.info("Download already complete: {}", request.getMediaFileID());
            return false;
        }
        if (existing != null && (existing.getStatus() == DownloadMetadata.Status.QUEUED
                || existing.getStatus() == DownloadMetadata.Status.DOWNLOADING)) {
            log.info("Download already in queue: {}", request.getMediaFileID());
            return false;
        }

        // Check storage space
        if (!storageHelper.hasEnoughSpace(request.getFileSize())) {
            log.warn("Insufficient storage for download: {} ({} bytes needed)",
                    request.getMediaFileID(), request.getFileSize());
            return false;
        }

        // Create metadata
        DownloadMetadata meta = new DownloadMetadata();
        meta.setMediaFileID(request.getMediaFileID());
        meta.setTitle(request.getTitle());
        meta.setServerPath(request.getServerPath());
        meta.setFileSize(request.getFileSize());
        meta.setDuration(request.getDuration());
        meta.setThumbnailUrl(request.getThumbnailUrl());
        meta.setContainer(request.getContainer());
        meta.setStatus(DownloadMetadata.Status.QUEUED);
        meta.setRecordingState(request.getRecordingState());
        meta.setSessionToken(request.getSessionToken());
        meta.setDownloadUrl(request.getDownloadUrl());
        meta.setTransferSessionState(normalizeSessionState(request.getSessionState(), "queued"));
        meta.setAccountFamily(normalizeAccountFamily(request.getAccountFamily()));
        meta.setAccountUsername(request.getAccountUsername());
        meta.setCorrelationId(UUID.randomUUID().toString());
        meta.setAuthFailoverCount(0);
        meta.setAccountPoolExhausted(false);
        meta.setResumeFromOffset(Math.max(0, request.getResumeFromOffset()));
        meta.setReconnectGraceSeconds(request.getReconnectGraceSeconds());
        meta.setExpiresInSeconds(request.getExpiresInSeconds());
        meta.setEffectiveRateKbps(request.getEffectiveRateKbps());
        meta.setRequestedPolicyJson(normalizeRequestedPolicy(request.getRequestedPolicyJson()));
        meta.setAcceptedPolicyJson(request.getAcceptedPolicyJson());
        meta.setPolicyAdjustmentsJson(request.getPolicyAdjustmentsJson());
        meta.setRecentReasonCodesJson(request.getRecentReasonCodesJson());

        if (request.getAccountUsername() != null && request.getAccountPassword() != null
            && !request.getAccountUsername().trim().isEmpty()
            && !request.getAccountPassword().trim().isEmpty()) {
            credentialVault.upsertAccount(
                meta.getAccountFamily(),
                request.getAccountUsername().trim(),
                request.getAccountPassword());
        }

        // Create output file
        String fileName = buildFileName(request);
        String outputUri = storageHelper.createOutputFile(fileName);
        meta.setLocalUri(outputUri);

        if (!repository.add(meta)) {
            log.warn("Repository full, cannot add: {}", request.getMediaFileID());
            return false;
        }

        log.info("Download enqueued: {} -> {}", request.getMediaFileID(), outputUri);
        startServiceIfNeeded();
        processNext();
        return true;
    }

    /**
     * Pause the current or queued download.
     */
    public void pause(String mediaFileID) {
        DownloadMetadata meta = repository.getByMediaFileID(mediaFileID);
        if (meta == null) return;

        if (meta.getStatus() == DownloadMetadata.Status.DOWNLOADING && currentTask != null
                && mediaFileID.equals(currentMediaFileID)) {
            currentTask.pause();
        }
        meta.setStatus(DownloadMetadata.Status.PAUSED);
        meta.setTransferSessionState("paused_by_client");
        repository.update(meta);
    }

    /**
     * Resume a paused or failed download.
     */
    public void resume(String mediaFileID) {
        DownloadMetadata meta = repository.getByMediaFileID(mediaFileID);
        if (meta == null) return;

        if (meta.getStatus() == DownloadMetadata.Status.PAUSED
                || meta.getStatus() == DownloadMetadata.Status.FAILED) {
            if (!canRunUnderCurrentNetworkPolicy(meta)) {
                meta.setErrorMessage("WIFI_REQUIRED");
                meta.setTransferSessionState("paused_by_server");
                repository.update(meta);
                return;
            }
            meta.setStatus(DownloadMetadata.Status.QUEUED);
            meta.setTransferSessionState("queued");
            meta.setErrorMessage(null);
            repository.update(meta);
            startServiceIfNeeded();
            processNext();
        }
    }

    /**
     * Cancel and remove a download.
     */
    public void cancel(String mediaFileID) {
        DownloadMetadata meta = repository.getByMediaFileID(mediaFileID);
        if (meta == null) return;

        if (currentTask != null && mediaFileID.equals(currentMediaFileID)) {
            currentTask.cancel();
        }

        meta.setTransferSessionState("canceled");
        repository.update(meta);

        // Delete partial file
        if (meta.getLocalUri() != null) {
            storageHelper.deleteFile(meta.getLocalUri());
        }
        repository.remove(mediaFileID);
    }

    /**
     * Get all downloads with their current status.
     */
    public List<DownloadMetadata> getQueue() {
        return repository.getAll();
    }

    /**
     * Process the next queued download. Called after enqueue or completion.
     */
    public void processNext() {
        if (isRunning.get()) return; // Already running a download

        executor.submit(() -> {
            List<DownloadMetadata> queued = repository.getByStatus(DownloadMetadata.Status.QUEUED);
            if (queued.isEmpty()) {
                stopServiceIfIdle();
                return;
            }

            DownloadMetadata next = queued.get(0);
            if (!canRunUnderCurrentNetworkPolicy(next)) {
                next.setStatus(DownloadMetadata.Status.PAUSED);
                next.setTransferSessionState("paused_by_server");
                next.setErrorMessage("WIFI_REQUIRED");
                repository.update(next);
                stopServiceIfIdle();
                return;
            }
            executeDownload(next);
        });
    }

    private void executeDownload(DownloadMetadata meta) {
        if (!isRunning.compareAndSet(false, true)) return;

        currentMediaFileID = meta.getMediaFileID();

        if (meta.getDownloadUrl() == null || meta.getDownloadUrl().trim().isEmpty()) {
            meta.setStatus(DownloadMetadata.Status.FAILED);
            meta.setTransferSessionState("error");
            meta.setErrorMessage("TRANSFER_URL_MISSING");
            repository.update(meta);
            DownloadForegroundService.notifyError(context, meta.getMediaFileID(), "TRANSFER_URL_MISSING");
            isRunning.set(false);
            currentMediaFileID = null;
            processNext();
            return;
        }

        meta.setStatus(DownloadMetadata.Status.DOWNLOADING);
        meta.setTransferSessionState("transferring");
        repository.update(meta);

        try {
            MiniClient client = MiniclientApplication.get().getClient();
            ServerInfo serverInfo = client != null ? client.getConnectedServerInfo() : null;
            if (serverInfo == null) {
                throw new IllegalStateException("Not connected to server");
            }

            String controlPlaneBase = buildControlPlaneBase(serverInfo);
            String family = normalizeAccountFamily(meta.getAccountFamily());
            List<DownloadCredentialVault.AccountSnapshot> candidates =
                    credentialVault.getCandidates(family, meta.getAccountUsername());

            if (candidates.isEmpty()) {
                meta.setStatus(DownloadMetadata.Status.FAILED);
                meta.setTransferSessionState("error");
                meta.setErrorMessage("ACCOUNT_POOL_EXHAUSTED|family=" + family);
                meta.setAccountPoolExhausted(true);
                repository.update(meta);
                DownloadForegroundService.notifyError(context, meta.getMediaFileID(), meta.getErrorMessage());
                processNext();
                return;
            }

            int attempts = Math.min(MAX_AUTH_FAILOVER_ATTEMPTS, candidates.size());
            DownloadTask.Result lastResult = null;
            for (int i = 0; i < attempts; i++) {
                DownloadCredentialVault.AccountSnapshot account = candidates.get(i);
                meta.setAccountUsername(account.username);
                repository.update(meta);

                long startOffset = Math.max(meta.getDownloadedBytes(), meta.getResumeFromOffset());
                currentTask = new DownloadTask(
                        context,
                        serverInfo.address,
                        serverInfo.port,
                        meta.getFileSize(),
                        startOffset,
                        meta.getMediaFileID(),
                        storageHelper,
                        meta.getLocalUri(),
                        controlPlaneBase,
                        meta.getDownloadUrl(),
                        meta.getSessionToken(),
                        account.username,
                        account.password,
                        ensureCorrelationId(meta),
                        this
                );

                DownloadTask.Result result = currentTask.execute();
                lastResult = result;
                applyTransferRefresh(meta, result);

                if (result.isSuccess()) {
                    credentialVault.markSuccess(family, account.username);
                    markComplete(meta.getMediaFileID());
                    return;
                }

                String errorCode = result.getErrorCode();
                if (DownloadTask.ERROR_AUTH_INVALID_CREDENTIALS.equals(errorCode)
                        || DownloadTask.ERROR_AUTH_REVOKED.equals(errorCode)) {
                    boolean revoked = DownloadTask.ERROR_AUTH_REVOKED.equals(errorCode);
                    credentialVault.markAuthFailure(family, account.username, revoked, true);
                    meta.setAuthFailoverCount(meta.getAuthFailoverCount() + 1);
                    repository.update(meta);

                    boolean hasAnotherCandidate = i < attempts - 1;
                    if (hasAnotherCandidate) {
                        long delay = RETRY_DELAYS_MS[Math.min(i, RETRY_DELAYS_MS.length - 1)];
                        log.warn("Download auth failover for mediaFileID={} family={} attempt={} error={} correlationId={} sessionId={}",
                                meta.getMediaFileID(), family, (i + 1), errorCode,
                                meta.getCorrelationId(), meta.getSessionId());
                        sleepQuietly(delay);
                        continue;
                    }

                    meta.setStatus(DownloadMetadata.Status.FAILED);
                    meta.setTransferSessionState("error");
                    meta.setAccountPoolExhausted(true);
                    meta.setErrorMessage("ACCOUNT_POOL_EXHAUSTED|" + toUserError(errorCode, meta));
                    repository.update(meta);
                    DownloadForegroundService.notifyError(context, meta.getMediaFileID(), meta.getErrorMessage());
                    processNext();
                    return;
                }

                handleTaskFailure(meta, errorCode);
                return;
            }

            String errorCode = lastResult != null ? lastResult.getErrorCode() : "UNKNOWN_ERROR";
            handleTaskFailure(meta, errorCode);

        } catch (Exception e) {
            log.error("Download execution failed: {}", meta.getMediaFileID(), e);
            handleTaskFailure(meta, e.getMessage());
        } finally {
            isRunning.set(false);
            currentTask = null;
            currentMediaFileID = null;
        }
    }

    @Override
    public void onProgress(String mediaFileID, long downloadedBytes, long totalBytes) {
        DownloadMetadata meta = repository.getByMediaFileID(mediaFileID);
        if (meta != null) {
            meta.setDownloadedBytes(downloadedBytes);
            meta.setResumeFromOffset(downloadedBytes);
            meta.setTransferSessionState("transferring");
            repository.update(meta);
        }
        DownloadForegroundService.updateProgress(context, mediaFileID, downloadedBytes, totalBytes);
    }

    private void markComplete(String mediaFileID) {
        DownloadMetadata meta = repository.getByMediaFileID(mediaFileID);
        if (meta != null) {
            meta.setStatus(DownloadMetadata.Status.COMPLETE);
            meta.setDownloadedBytes(meta.getFileSize());
            meta.setResumeFromOffset(meta.getFileSize());
            meta.setTransferSessionState("completed");
            meta.setErrorMessage(null);
            repository.update(meta);
        }
        log.info("Download complete: {}", mediaFileID);
        DownloadForegroundService.notifyComplete(context, mediaFileID);
        processNext();
    }

    private void handleTaskFailure(DownloadMetadata meta, String errorCode) {
        String normalized = errorCode == null ? "UNKNOWN_ERROR" : errorCode;

        if (normalized.startsWith(DownloadTask.ERROR_PAUSED_BY_SERVER)) {
            meta.setStatus(DownloadMetadata.Status.PAUSED);
            meta.setTransferSessionState("paused_by_server");
            meta.setErrorMessage(toUserError(normalized, meta));
            repository.update(meta);
            processNext();
            return;
        }

        if (normalized.startsWith(DownloadTask.ERROR_INVALID_RANGE)) {
            if (meta.getFileSize() > 0 && meta.getDownloadedBytes() >= meta.getFileSize()) {
                markComplete(meta.getMediaFileID());
            } else {
                meta.setStatus(DownloadMetadata.Status.FAILED);
                meta.setTransferSessionState("error");
                meta.setErrorMessage(toUserError(normalized, meta));
                repository.update(meta);
                DownloadForegroundService.notifyError(context, meta.getMediaFileID(), meta.getErrorMessage());
                processNext();
            }
            return;
        }

        if (normalized.startsWith(DownloadTask.ERROR_SERVER_UNAVAILABLE)) {
            scheduleRetry(meta, normalized);
            return;
        }

        scheduleRetry(meta, normalized);
    }

    private void scheduleRetry(DownloadMetadata meta, String errorCode) {
        meta.setRetryCount(meta.getRetryCount() + 1);
        if (meta.getRetryCount() <= MAX_RETRIES) {
            meta.setStatus(DownloadMetadata.Status.QUEUED);
            meta.setTransferSessionState("queued");
            meta.setErrorMessage("Retry " + meta.getRetryCount() + ": " + toUserError(errorCode, meta));
            repository.update(meta);
            long delay = RETRY_DELAYS_MS[Math.min(meta.getRetryCount() - 1, RETRY_DELAYS_MS.length - 1)];
            log.info("Scheduling retry {} for {} in {}ms", meta.getRetryCount(), meta.getMediaFileID(), delay);
            executor.submit(() -> {
                sleepQuietly(delay);
                processNext();
            });
        } else {
            meta.setStatus(DownloadMetadata.Status.FAILED);
            meta.setTransferSessionState("error");
            meta.setErrorMessage(toUserError(errorCode, meta));
            repository.update(meta);
            DownloadForegroundService.notifyError(context, meta.getMediaFileID(), meta.getErrorMessage());
            processNext();
        }
    }

    private void startServiceIfNeeded() {
        Intent intent = new Intent(context, DownloadForegroundService.class);
        intent.setAction(DownloadForegroundService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    private void stopServiceIfIdle() {
        List<DownloadMetadata> active = repository.getByStatus(DownloadMetadata.Status.QUEUED);
        List<DownloadMetadata> downloading = repository.getByStatus(DownloadMetadata.Status.DOWNLOADING);
        if (active.isEmpty() && downloading.isEmpty()) {
            Intent intent = new Intent(context, DownloadForegroundService.class);
            intent.setAction(DownloadForegroundService.ACTION_STOP);
            context.startService(intent);
        }
    }

    private static String buildFileName(DownloadRequest request) {
        String base = request.getMediaFileID();
        if (request.getTitle() != null && !request.getTitle().isEmpty()) {
            // Sanitize title for filename
            base = request.getTitle().replaceAll("[^a-zA-Z0-9._\\-]", "_");
            if (base.length() > 80) base = base.substring(0, 80);
            base = base + "_" + request.getMediaFileID();
        }
        String ext = ".ts";
        if (request.getContainer() != null) {
            String c = request.getContainer().toLowerCase();
            if (c.contains("mkv") || c.contains("matroska")) ext = ".mkv";
            else if (c.contains("mp4")) ext = ".mp4";
            else if (c.contains("avi")) ext = ".avi";
            else if (c.contains("mpg") || c.contains("mpeg")) ext = ".mpg";
        }
        return base + ext;
    }

    @Override
    public String getDownloadStatus(String mediaFileID) {
        DownloadMetadata meta = repository.getByMediaFileID(mediaFileID);
        if (meta == null) return null;
        String sessionState = meta.getEffectiveSessionState();
        switch (meta.getStatus()) {
            case DOWNLOADING:
                return "DOWNLOADING|" + meta.getProgressPercent() + "|" + sessionState;
            case COMPLETE:
                return "COMPLETE|" + sessionState;
            case FAILED:
                if (meta.isAccountPoolExhausted()) {
                    return "FAILED|POOL_EXHAUSTED|" + (meta.getErrorMessage() != null ? meta.getErrorMessage() : "");
                }
                return "FAILED|" + (meta.getErrorMessage() != null ? meta.getErrorMessage() : "Unknown error");
            case PAUSED:
                return "PAUSED|" + meta.getProgressPercent() + "|" + sessionState;
            case QUEUED:
                return "QUEUED|" + sessionState;
            default:
                return meta.getStatus().name();
        }
    }

    public void recoverInterruptedSessions() {
        List<DownloadMetadata> all = repository.getAll();
        boolean hasWork = false;
        for (DownloadMetadata meta : all) {
            if (meta.getStatus() == DownloadMetadata.Status.DOWNLOADING) {
                meta.setStatus(DownloadMetadata.Status.QUEUED);
                meta.setTransferSessionState("queued");
                repository.update(meta);
                hasWork = true;
            } else if (meta.getStatus() == DownloadMetadata.Status.QUEUED
                    || meta.getStatus() == DownloadMetadata.Status.PAUSED
                    || meta.getStatus() == DownloadMetadata.Status.FAILED) {
                hasWork = true;
            }
        }
        if (hasWork) {
            startServiceIfNeeded();
            processNext();
        }
    }

    private static String buildControlPlaneBase(ServerInfo serverInfo) {
        String host = serverInfo.address == null ? "" : serverInfo.address.trim();
        if (host.isEmpty()) {
            throw new IllegalStateException("Server address unavailable");
        }
        int port = serverInfo.port > 0 ? serverInfo.port : 31099;
        return "http://" + host + ":" + port;
    }

    private static String ensureCorrelationId(DownloadMetadata meta) {
        if (meta.getCorrelationId() == null || meta.getCorrelationId().trim().isEmpty()) {
            meta.setCorrelationId(UUID.randomUUID().toString());
        }
        return meta.getCorrelationId();
    }

    private void applyTransferRefresh(DownloadMetadata meta, DownloadTask.Result result) {
        if (result == null) return;

        if (result.getRefreshedSessionToken() != null && !result.getRefreshedSessionToken().isEmpty()) {
            meta.setSessionToken(result.getRefreshedSessionToken());
        }
        if (result.getRefreshedDownloadUrl() != null && !result.getRefreshedDownloadUrl().isEmpty()) {
            // Server-provided URL remains authoritative for resume and subsequent requests.
            meta.setDownloadUrl(result.getRefreshedDownloadUrl());
        }
        if (result.getRefreshedSessionState() != null && !result.getRefreshedSessionState().isEmpty()) {
            meta.setTransferSessionState(normalizeSessionState(result.getRefreshedSessionState(), "queued"));
        }
        if (result.getServerSessionId() != null && !result.getServerSessionId().isEmpty()) {
            meta.setSessionId(result.getServerSessionId());
        }
        if (result.getFinalOffset() > 0) {
            meta.setDownloadedBytes(Math.max(meta.getDownloadedBytes(), result.getFinalOffset()));
            meta.setResumeFromOffset(Math.max(meta.getResumeFromOffset(), result.getFinalOffset()));
        }
        repository.update(meta);
    }

    private static String toUserError(String errorCode, DownloadMetadata meta) {
        String code;
        if (errorCode == null || errorCode.trim().isEmpty()) {
            code = "UNKNOWN_ERROR";
        } else {
            code = errorCode;
        }

        if (DownloadTask.ERROR_AUTH_INVALID_CREDENTIALS.equals(code)) {
            code = "INVALID_CREDENTIALS";
        } else if (DownloadTask.ERROR_AUTH_REVOKED.equals(code)) {
            code = "ACCOUNT_REVOKED";
        } else if (DownloadTask.ERROR_SERVER_UNAVAILABLE.equals(code)) {
            code = "SERVER_UNAVAILABLE";
        }

        String corr = meta.getCorrelationId() == null ? "" : meta.getCorrelationId();
        String sid = meta.getSessionId() == null ? "" : meta.getSessionId();
        return code + "|corr=" + corr + "|sid=" + sid;
    }

    private static void sleepQuietly(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean canRunUnderCurrentNetworkPolicy(DownloadMetadata meta) {
        String policy = meta.getAcceptedPolicyJson();
        if (policy == null || policy.isEmpty()) {
            policy = meta.getRequestedPolicyJson();
        }
        boolean wifiOnly = extractJsonBoolean(policy, "wifi_only", true);
        boolean allowMetered = extractJsonBoolean(policy, "allow_metered", false);
        if (!wifiOnly && allowMetered) {
            return true;
        }

        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return true;
        }
        Network network = cm.getActiveNetwork();
        if (network == null) {
            return false;
        }
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        if (caps == null) {
            return false;
        }
        boolean hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        if (wifiOnly && !hasWifi) {
            return false;
        }
        if (!allowMetered && cm.isActiveNetworkMetered() && !hasWifi) {
            return false;
        }
        return true;
    }

    private static boolean extractJsonBoolean(String json, String key, boolean defaultVal) {
        if (json == null) return defaultVal;
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return defaultVal;
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return defaultVal;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return defaultVal;
        if (json.startsWith("true", start)) return true;
        if (json.startsWith("false", start)) return false;
        return defaultVal;
    }

    private static String normalizeSessionState(String state, String defaultState) {
        if (state == null || state.trim().isEmpty()) return defaultState;
        return state.trim().toLowerCase();
    }

    private static String normalizeAccountFamily(String family) {
        if (family == null || family.trim().isEmpty()) return "sage";
        return family.trim().toLowerCase();
    }

    private static String normalizeRequestedPolicy(String policyJson) {
        if (policyJson != null && !policyJson.trim().isEmpty()) {
            return policyJson;
        }
        return "{\"download_mode\":\"background\",\"rate_profile\":\"balanced\",\"max_rate_kbps\":0,\"concurrency\":1,\"wifi_only\":true,\"allow_metered\":false}";
    }
}
