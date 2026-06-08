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
import android.os.Handler;
import android.os.Looper;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sagex.miniclient.DownloadRequest;
import sagex.miniclient.DownloadStatusProvider;
import sagex.miniclient.MiniClient;
import sagex.miniclient.MiniClientConnection;
import sagex.miniclient.ServerInfo;
import sagex.miniclient.android.MiniclientApplication;
import sagex.miniclient.prefs.PrefStore;
import sagex.miniclient.util.RandomMACAddressResolver;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
    private static final int MAX_MANIFEST_BYTES = 512 * 1024;
    private static final int MANIFEST_FETCH_ATTEMPTS = 3;
    private static final long[] MANIFEST_FETCH_RETRY_DELAYS_MS = {2000L, 10000L, 30000L};
    // Testing toggle: when true, every user-initiated metadata/sidecar refresh
    // requests a fresh transfer session/manifest path first instead of reusing
    // the existing pointer+token path. Set back to false to restore reuse-first
    // behavior.
    private static final boolean FORCE_FRESH_MANIFEST_ON_USER_REFRESH = true;
    private static final int MAX_MANIFEST_REFRESH_RECONNECT_ATTEMPTS = 4;
    private static final long[] MANIFEST_REFRESH_RECONNECT_DELAYS_MS = {1000L, 2000L, 4000L, 8000L};
    private static final long HTTP_REFRESH_TIMEOUT_MS = 3_000L;
    // Persistent retry schedule used AFTER the in-session MANIFEST_FETCH_ATTEMPTS
    // are exhausted. The PRD requires that manifest fetch failures never fail
    // the download and that we keep retrying in the background, so we schedule
    // an extended backoff chain (5min, 15min, 60min, then repeat hourly).
    private static final long[] MANIFEST_PERSISTENT_RETRY_DELAYS_MS = {
            5L * 60_000L,    // 5 minutes
            15L * 60_000L,   // 15 minutes
            60L * 60_000L    // 60 minutes; subsequent attempts reuse this cap.
    };
    // Sized for VPN tolerance: server-side hiccups + tunnel reconnects can take tens of seconds.
    private static final long[] RETRY_DELAYS_MS = {10000, 30000, 90000};
    // If the server does not push a refreshed TRANSFER_SESSION_ACK back within this window,
    // assume the inbound DOWNLOAD_REFRESH_REQUEST was ignored (e.g. older server build) and
    // surface a clear failure so the user can retry or re-initiate from the server menu.
    private static final int HTTP_REFRESH_ATTEMPTS = 2;
    private static final long[] HTTP_REFRESH_RETRY_DELAYS_MS = {0L, 1500L};
    private static final int HTTP_REFRESH_CONNECT_TIMEOUT_MS = 3000;
    private static final long REFRESH_TIMEOUT_MS = 45_000L;
    private static final String REFRESH_PENDING_MESSAGE =
            "REFRESH_PENDING|Waiting for server to refresh transfer session";
    // Persist progress to SQLite at a lower cadence to reduce IO overhead
    // during sustained high-throughput transfers.
    private static final long PROGRESS_PERSIST_MIN_INTERVAL_MS = 1500L;
    private static final long PROGRESS_PERSIST_MIN_BYTES = 2L * 1024L * 1024L;

    private static final class UnifiedChecklist {
        int expected;
        int present;
    }

    private static final class SidecarAvailability {
        final boolean artwork;
        final boolean captions;
        final boolean comskip;
        final boolean transcript;

        SidecarAvailability(boolean artwork, boolean captions, boolean comskip, boolean transcript) {
            this.artwork = artwork;
            this.captions = captions;
            this.comskip = comskip;
            this.transcript = transcript;
        }
    }

    public enum RemuxMode {
        NONE,
        AUTO,
        MKV,
        MP4
    }

    public static final class ActionOptions {
        private final boolean restartTransfer;
        private final boolean refreshMetadataAndArtwork;
        private final boolean refreshArtwork;
        private final boolean refreshCaptions;
        private final boolean refreshComskip;
        private final boolean refreshTranscript;
        private final RemuxMode remuxMode;

        private ActionOptions(Builder builder) {
            this.restartTransfer = builder.restartTransfer;
            this.refreshMetadataAndArtwork = builder.refreshMetadataAndArtwork;
            this.refreshArtwork = builder.refreshArtwork;
            this.refreshCaptions = builder.refreshCaptions;
            this.refreshComskip = builder.refreshComskip;
            this.refreshTranscript = builder.refreshTranscript;
            this.remuxMode = builder.remuxMode;
        }

        private boolean hasSidecarRefresh() {
            return refreshArtwork || refreshCaptions || refreshComskip || refreshTranscript;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static ActionOptions restartOnly() {
            return builder().restartTransfer(true).build();
        }

        public static ActionOptions metadataAndArtwork() {
            return builder().refreshMetadataAndArtwork(true).build();
        }

        public static ActionOptions sidecarsAll() {
            return builder()
                    .refreshArtwork(true)
                    .refreshCaptions(true)
                    .refreshComskip(true)
                    .refreshTranscript(true)
                    .build();
        }

        public static ActionOptions sidecarsCaptions() {
            return builder().refreshCaptions(true).build();
        }

        public static ActionOptions sidecarsComskip() {
            return builder().refreshComskip(true).build();
        }

        public static ActionOptions sidecarsTranscript() {
            return builder().refreshTranscript(true).build();
        }

        public static ActionOptions remux(RemuxMode mode) {
            return builder().remuxMode(mode).build();
        }

        public static final class Builder {
            private boolean restartTransfer;
            private boolean refreshMetadataAndArtwork;
            private boolean refreshArtwork;
            private boolean refreshCaptions;
            private boolean refreshComskip;
            private boolean refreshTranscript;
            private RemuxMode remuxMode = RemuxMode.NONE;

            public Builder restartTransfer(boolean value) {
                this.restartTransfer = value;
                return this;
            }

            public Builder refreshMetadataAndArtwork(boolean value) {
                this.refreshMetadataAndArtwork = value;
                return this;
            }

            public Builder refreshArtwork(boolean value) {
                this.refreshArtwork = value;
                return this;
            }

            public Builder refreshCaptions(boolean value) {
                this.refreshCaptions = value;
                return this;
            }

            public Builder refreshComskip(boolean value) {
                this.refreshComskip = value;
                return this;
            }

            public Builder refreshTranscript(boolean value) {
                this.refreshTranscript = value;
                return this;
            }

            public Builder remuxMode(RemuxMode value) {
                this.remuxMode = value == null ? RemuxMode.NONE : value;
                return this;
            }

            public ActionOptions build() {
                return new ActionOptions(this);
            }
        }
    }

    private static volatile DownloadManager instance;

    private final Context context;
    private final DownloadRepository repository;
    private final StorageHelper storageHelper;
    private final DownloadCredentialVault credentialVault;
    private final ExecutorService executor;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private volatile DownloadTask currentTask;
    private volatile String currentMediaFileID;
    private volatile ConnectivityManager.NetworkCallback networkCallback;
    private final ExecutorService manifestFetchExecutor;
    // PRD 5.8: `core` inline-level means the inline payload is intentionally
    // light and the FULL manifest fetch is urgent (artwork/credits/etc. are
    // not yet available to the UI). To prevent a slow `full`-level background
    // fetch from delaying urgent ones we route `core` fetches to a separate
    // single-thread executor.
    private final ExecutorService urgentManifestFetchExecutor;
    private final ScheduledExecutorService manifestRetryScheduler;
    private final Set<String> manifestFetchInFlight = ConcurrentHashMap.newKeySet();
    // Persistent retry tracking: maps mediaFileId -> next attempt index in the
    // MANIFEST_PERSISTENT_RETRY_DELAYS_MS schedule. Cleared on success or on a
    // fresh ACK so user-initiated activity restarts the schedule cleanly.
    private final Map<String, Integer> manifestPersistentRetryAttempt = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.ScheduledFuture<?>> manifestPendingRetries =
            new ConcurrentHashMap<>();
    // In-process listener registry so detail/list activities can re-bind when
    // the full manifest replaces the inline first-paint snapshot. Registered
    // by activities in onResume, removed in onPause. Callbacks dispatch on the
    // Android main thread.
    private final Map<String, Set<Runnable>> manifestUpdateListeners = new ConcurrentHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // Tracks mediaFileIDs for which we have already issued an automatic
    // DOWNLOAD_REFRESH_REQUEST (opcode 228) during this process lifetime.
    // We allow exactly ONE automatic refresh per item per session so we do
    // not loop if the server still won't honor it; user-initiated retries
    // are unaffected. Cleared when a fresh CMD_DOWNLOAD_REQUEST arrives.
    private final Set<String> autoRefreshAttempted = ConcurrentHashMap.newKeySet();
    private final Set<String> manifestRefreshAttempted = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Integer> manifestRefreshReconnectAttempt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> manifestRefreshAckTimeoutAttempt = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.ScheduledFuture<?>> manifestRefreshAckTimeoutTasks =
            new ConcurrentHashMap<>();
    private final Object manifestReconnectLock = new Object();
        private final AtomicBoolean manifestBackgroundSessionOwned = new AtomicBoolean(false);
    // Tracks recordings where the user explicitly tapped "Update Metadata"
    // so we can persist visible requested/success/failure feedback in the
    // Downloads manager status row.
    private final Set<String> userMetadataRefreshPending = ConcurrentHashMap.newKeySet();
    // One-shot guard for FORCE_FRESH_MANIFEST_ON_USER_REFRESH. Prevents the
    // refresh flow from repeatedly requesting fresh ACKs instead of advancing
    // to full-manifest fetch after the first reauth attempt.
    private final Set<String> forcedFreshManifestRequested = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> progressLastPersistTimestampMs = new ConcurrentHashMap<>();
    private final Map<String, Long> progressLastPersistBytes = new ConcurrentHashMap<>();

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
        this.manifestFetchExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "DownloadManager-ManifestFetch");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        this.urgentManifestFetchExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "DownloadManager-ManifestFetchUrgent");
            t.setDaemon(true);
            // Slightly above the background manifest worker so a `core` fetch
            // is not starved if both queues are busy.
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        this.manifestRetryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DownloadManager-ManifestRetry");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        ensureNetworkWatcher();
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
            // A fresh ACK on a COMPLETE item means a metadata-refresh reauth succeeded.
            // Update session token/metadata pointer and continue through the same
            // manifest + selected sidecar refresh flow used by user-initiated
            // metadata/artwork refresh.
            boolean hasNewToken = request.getSessionToken() != null
                    && !request.getSessionToken().isEmpty()
                    && !request.getSessionToken().equals(existing.getSessionToken());
            boolean hasNewMetadataUrl = request.getOfflineMetadataUrl() != null
                    && !request.getOfflineMetadataUrl().isEmpty()
                    && !request.getOfflineMetadataUrl().equals(existing.getOfflineMetadataUrl());
            if (hasNewToken || hasNewMetadataUrl) {
                log.info("metadata_refresh_session_update mediaFileID={} newToken={} newMetadataUrl={}",
                        request.getMediaFileID(), hasNewToken, hasNewMetadataUrl);
                refreshSessionAndRejoinFullRefreshFlow(existing, request);
                return true;
            }
            log.info("Download already complete: {}", request.getMediaFileID());
            return false;
        }
        if (existing != null) {
            mergeWithExisting(existing, request);
            return true;
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
            // Additional logic for closing background refresh session
            // ...
        meta.setTransferSessionState(normalizeSessionState(request.getSessionState(), "queued"));
        applySessionStateStatus(meta, meta.getTransferSessionState());
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
        meta.setServerQueueItemId(request.getServerQueueItemId());
        meta.setQueuePriority(request.getQueuePriority());
        meta.setMergedRequestCount(1);
        meta.setDownloadSpeedBytesPerSec(0);
        meta.setEtaSeconds(0);
        meta.setLastProgressTimestampMs(0);
        meta.setInvalidRangeRetried(false);
        meta.setOfflineMetadataUrl(nullIfBlank(request.getOfflineMetadataUrl()));
        meta.setOfflineMetadataPath(nullIfBlank(request.getOfflineMetadataPath()));
        meta.setOfflineInlineLevel(normalizeInlineLevel(request.getOfflineInlineLevel()));

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

        // M2: persist offline companion content (rich metadata + artwork/captions/comskip/transcript
        // manifests) verbatim to <file>.companion/ when localUri is file://. See
        // /memories/repo/offline-companion-spec.md. Null payload is a no-op.
        OfflineCompanionStore.apply(context, meta, request.getOfflineCompanionJson());

        if (!repository.add(meta)) {
            log.warn("Repository full, cannot add: {}", request.getMediaFileID());
            return false;
        }

        maybeFetchFullManifest(meta);

        log.info("Download enqueued: {} -> {}", request.getMediaFileID(), outputUri);
        startServiceIfNeeded();
        if (isTransferReady(meta)) {
            processNext();
        }
        return true;
    }

    /**
     * Pause the current or queued download.
     */
    public void pause(String mediaFileID) {
        DownloadMetadata meta = repository.getByMediaFileID(mediaFileID);
        if (meta == null) return;

        OfflineCompanionFetcher.pause(mediaFileID);
        if (meta.getStatus() == DownloadMetadata.Status.DOWNLOADING && currentTask != null
                && mediaFileID.equals(currentMediaFileID)) {
            currentTask.pause();
        }
        meta.setStatus(DownloadMetadata.Status.PAUSED);
        meta.setTransferSessionState("paused_by_client");
        repository.update(meta);
        clearProgressTracking(mediaFileID);
    }

    /**
     * Resume a paused or failed download.
     */
    public void resume(String mediaFileID) {
        DownloadMetadata meta = repository.getByMediaFileID(mediaFileID);
        if (meta == null) return;

        OfflineCompanionFetcher.resume(mediaFileID);
        if (isPrimaryVideoComplete(meta)) {
            restart(mediaFileID);
            return;
        }

        if (meta.getStatus() == DownloadMetadata.Status.PAUSED
                || meta.getStatus() == DownloadMetadata.Status.FAILED) {
            if (!canRunUnderCurrentNetworkPolicy(meta)) {
                // Don't fail — keep the item in the queue and let the
                // network callback resume it when Wi-Fi becomes available.
                meta.setStatus(DownloadMetadata.Status.QUEUED);
                meta.setTransferSessionState("waiting_for_wifi");
                meta.setErrorMessage("WIFI_REQUIRED");
                repository.update(meta);
                return;
            }
            // Expired sessions need a fresh token from the server; route through retry().
            if ("expired".equalsIgnoreCase(meta.getTransferSessionState())) {
                retry(mediaFileID);
                return;
            }
            meta.setStatus(DownloadMetadata.Status.QUEUED);
            meta.setTransferSessionState("retry_pending");
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

        OfflineCompanionFetcher.cancel(mediaFileID);
        if (currentTask != null && mediaFileID.equals(currentMediaFileID)) {
            currentTask.cancel();
        }

        meta.setTransferSessionState("canceled");
        repository.update(meta);

        // Delete partial file
        if (meta.getLocalUri() != null) {
            storageHelper.deleteFile(meta.getLocalUri());
        }
        clearProgressTracking(mediaFileID);
        deleteCompanionArtifacts(meta);
        repository.remove(mediaFileID);
    }

    public boolean executeAction(String mediaFileID, ActionOptions options) {
        if (mediaFileID == null || mediaFileID.trim().isEmpty() || options == null) {
            return false;
        }
        DownloadMetadata meta = repository.getByMediaFileID(mediaFileID);
        if (meta == null) {
            return false;
        }
        boolean handled = false;
        if (options.refreshMetadataAndArtwork) {
            handled = refreshMetadataAndArtworkInternal(mediaFileID) || handled;
        }
        if (options.restartTransfer) {
            restartInternal(mediaFileID);
            handled = true;
        }
        if (options.hasSidecarRefresh()) {
            dispatchSidecarFetchById(mediaFileID,
                    options.refreshArtwork,
                    options.refreshCaptions,
                    options.refreshComskip,
                    options.refreshTranscript);
            handled = true;
        }
        if (options.remuxMode != RemuxMode.NONE) {
            retryRemuxWithMode(mediaFileID, options.remuxMode);
            handled = true;
        }
        return handled;
    }

    /**
     * Request a fresh metadata/artwork fetch for an already downloaded
     * recording. This reuses the same full-manifest path used during the
     * initial download so the server response can tell us whether missing
     * fields are client-side or server-side.
     */
    public boolean refreshMetadataAndArtwork(String mediaFileID) {
        return executeAction(mediaFileID, ActionOptions.metadataAndArtwork());
    }

    /**
     * Refresh based on a user-selected set of flags from the offline refresh menu.
     * Metadata refresh uses the full-manifest pipeline; sidecar items use the
     * existing sidecar selection flags.
     */
    public boolean refreshWithFlags(String mediaFileID, DownloadMetadata.SidecarFlags flags) {
        if (flags == null || mediaFileID == null) return false;

        // Set sidecar selection first so the pipeline reads the right flags.
        setSidecarRefreshSelection(mediaFileID,
                flags.refreshArtwork,
                flags.refreshCaptions,
                flags.refreshComskip,
                flags.refreshTranscript);

        if (flags.refreshMetadata || flags.refreshArtwork) {
            // Full manifest + companion fetch covers both metadata and artwork.
            return refreshMetadataAndArtwork(mediaFileID);
        } else if (flags.refreshCaptions || flags.refreshComskip || flags.refreshTranscript) {
            // Sidecar-only refresh — no manifest needed.
            refreshSidecars(mediaFileID);
            return true;
        }
        return false;
    }

    private boolean refreshMetadataAndArtworkInternal(String mediaFileID) {
        if (mediaFileID == null || mediaFileID.trim().isEmpty()) {
            return false;
        }
        DownloadMetadata meta = repository.getByMediaFileID(mediaFileID);
        if (meta == null) {
            return false;
        }
        String pointer = firstNonBlank(meta.getOfflineMetadataUrl(), meta.getOfflineMetadataPath());
        if (pointer == null) {
            meta.setTransferSessionState("metadata_refresh_unavailable");
            meta.setErrorMessage("Metadata refresh unavailable: missing metadata pointer");
            repository.update(meta);
            log.warn("Metadata refresh skipped for {}: missing offline manifest pointer", mediaFileID);
            return false;
        }
        userMetadataRefreshPending.add(mediaFileID);
        forcedFreshManifestRequested.remove(mediaFileID);
        meta.setTransferSessionState("metadata_refresh_requested");
        meta.setErrorMessage("Metadata refresh requested");
        repository.update(meta);
        cancelPersistentManifestRetry(mediaFileID);
        manifestPersistentRetryAttempt.remove(mediaFileID);
        manifestRefreshAttempted.remove(mediaFileID);
        manifestRefreshReconnectAttempt.remove(mediaFileID);
        cancelManifestRefreshAckTimeout(mediaFileID);
        manifestRefreshAckTimeoutAttempt.remove(mediaFileID);
        maybeFetchFullManifest(meta);
        return true;
    }

    public void onServerPause(String sessionToken, String mediaFileID, long bytesTransferred) {
        DownloadMetadata meta = resolveMetadata(sessionToken, mediaFileID);
        if (meta == null) return;

        if (bytesTransferred > 0) {
            meta.setDownloadedBytes(Math.max(meta.getDownloadedBytes(), bytesTransferred));
            meta.setResumeFromOffset(Math.max(meta.getResumeFromOffset(), bytesTransferred));
        }
        if (currentTask != null && meta.getMediaFileID().equals(currentMediaFileID)) {
            currentTask.pause();
        }
        meta.setStatus(DownloadMetadata.Status.PAUSED);
        meta.setTransferSessionState("paused_by_server");
        repository.update(meta);
        clearProgressTracking(meta.getMediaFileID());
        stopServiceIfIdle();
    }

    public void onServerResume(String sessionToken, String mediaFileID, String downloadUrl,
                               long bytesTransferred, String sessionState) {
        DownloadMetadata meta = resolveMetadata(sessionToken, mediaFileID);
        if (meta == null) return;

        if (sessionToken != null && !sessionToken.trim().isEmpty()) {
            meta.setSessionToken(sessionToken);
        }
        if (downloadUrl != null && !downloadUrl.trim().isEmpty()) {
            meta.setDownloadUrl(downloadUrl.trim());
        }
        if (bytesTransferred > 0) {
            meta.setDownloadedBytes(Math.max(meta.getDownloadedBytes(), bytesTransferred));
            meta.setResumeFromOffset(Math.max(meta.getResumeFromOffset(), bytesTransferred));
        }

        String normalizedState = normalizeSessionState(sessionState, "transferring");
        meta.setTransferSessionState(normalizedState);
        meta.setErrorMessage(null);
        meta.setRetryCount(0);
        meta.setAccountPoolExhausted(false);
        applySessionStateStatus(meta, normalizedState);
        repository.update(meta);

        startServiceIfNeeded();
        if (isTransferReady(meta)) {
            processNext();
        }
    }

    public void onServerCancel(String sessionToken, String mediaFileID) {
        DownloadMetadata meta = resolveMetadata(sessionToken, mediaFileID);
        if (meta == null) return;
        cancel(meta.getMediaFileID());
    }

    public void onTransferSessionError(String mediaFileID,
                                       String correlationId,
                                       String errorCode,
                                       String message,
                                       boolean retriable) {
        DownloadMetadata meta = null;
        if (mediaFileID != null && !mediaFileID.trim().isEmpty()) {
            meta = repository.getByMediaFileID(mediaFileID.trim());
        }
        if (meta == null && correlationId != null && !correlationId.trim().isEmpty()) {
            String corr = correlationId.trim();
            for (DownloadMetadata candidate : repository.getAll()) {
                if (corr.equals(candidate.getCorrelationId())) {
                    meta = candidate;
                    break;
                }
            }
        }
        if (meta == null) {
            log.warn("TRANSFER_SESSION_ERROR unmatched mediaFileID={} corr={} code={} message={}",
                    mediaFileID, correlationId, errorCode, message);
            return;
        }

        String code = nullIfBlank(errorCode);
        String detail = nullIfBlank(message);
        if (shouldRequestSessionRefreshForTransferError(code)) {
            if (userMetadataRefreshPending.contains(meta.getMediaFileID())) {
                String inline = normalizeInlineLevel(meta.getOfflineInlineLevel());
                requestManifestSessionRefresh(meta, inline,
                        "transfer_error_" + normalizeRefreshReasonToken(code));
            }
        }
        StringBuilder userDetail = new StringBuilder("REFRESH_FAILED");
        if (code != null) userDetail.append('|').append(code);
        if (detail != null) userDetail.append('|').append(detail);
        if (retriable) userDetail.append("|retryable=true");

        if ("awaiting_refresh".equalsIgnoreCase(meta.getTransferSessionState())) {
            meta.setStatus(DownloadMetadata.Status.FAILED);
            meta.setTransferSessionState("expired");
        } else {
            meta.setTransferSessionState("metadata_refresh_failed");
        }
        meta.setErrorMessage(userDetail.toString());
        userMetadataRefreshPending.remove(meta.getMediaFileID());
        repository.update(meta);
    }

    /**
     * Get all downloads with their current status.
     */
    public List<DownloadMetadata> getQueue() {
        return repository.getAll();
    }

    public List<DownloadMetadata> searchLibrary(String query, int limit) {
        return repository.search(query, limit);
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

            for (DownloadMetadata next : queued) {
                if (!isTransferReady(next)) {
                    continue;
                }
                if (!canRunUnderCurrentNetworkPolicy(next)) {
                    // Keep status QUEUED so the item stays in the queue and
                    // auto-resumes when Wi-Fi/Ethernet becomes available
                    // (see network callback registered in ensureNetworkWatcher).
                    // Skip this item and try the next — another download might
                    // have allow_metered set in its accepted policy.
                    if (!"waiting_for_wifi".equals(next.getTransferSessionState())) {
                        next.setTransferSessionState("waiting_for_wifi");
                        next.setErrorMessage("WIFI_REQUIRED");
                        repository.update(next);
                    }
                    continue;
                }
                executeDownload(next);
                return;
            }
            stopServiceIfIdle();
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

        meta.setStatus(DownloadMetadata.Status.PREPARING);
        meta.setTransferSessionState("preparing");
        meta.setDownloadSpeedBytesPerSec(0);
        meta.setEtaSeconds(0);
        meta.setLastProgressTimestampMs(0);
        repository.update(meta);

        try {
            MiniClient client = MiniclientApplication.get().getClient();
            ServerInfo serverInfo = client != null ? client.getConnectedServerInfo() : null;
            MiniClientConnection connection = client != null ? client.getCurrentConnection() : null;
            if (serverInfo == null) {
                throw new IllegalStateException("Not connected to server");
            }

            meta.setStatus(DownloadMetadata.Status.DOWNLOADING);
            meta.setTransferSessionState("transferring");
            repository.update(meta);

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
                        connection != null ? connection.getClientId() : null,
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
            long previousBytes = Math.max(0, meta.getDownloadedBytes());
            long now = System.currentTimeMillis();
            long previousTs = meta.getLastProgressTimestampMs();

            meta.setDownloadedBytes(downloadedBytes);
            meta.setResumeFromOffset(downloadedBytes);
            meta.setTransferSessionState("transferring");
            if (previousTs > 0 && now > previousTs && downloadedBytes > previousBytes) {
                long elapsedMs = now - previousTs;
                long deltaBytes = downloadedBytes - previousBytes;
                long speed = (deltaBytes * 1000L) / elapsedMs;
                meta.setDownloadSpeedBytesPerSec(Math.max(0, speed));
                long remaining = Math.max(0, totalBytes - downloadedBytes);
                if (speed > 0) {
                    meta.setEtaSeconds(remaining / speed);
                }
            }
            meta.setLastProgressTimestampMs(now);

            long lastPersistTs = progressLastPersistTimestampMs.getOrDefault(mediaFileID, 0L);
            long lastPersistBytes = progressLastPersistBytes.getOrDefault(mediaFileID, 0L);
            boolean shouldPersist = lastPersistTs <= 0
                    || downloadedBytes >= totalBytes
                    || (now - lastPersistTs) >= PROGRESS_PERSIST_MIN_INTERVAL_MS
                    || (downloadedBytes - lastPersistBytes) >= PROGRESS_PERSIST_MIN_BYTES;
            if (shouldPersist) {
                repository.update(meta);
                progressLastPersistTimestampMs.put(mediaFileID, now);
                progressLastPersistBytes.put(mediaFileID, downloadedBytes);
            }
        }
        DownloadForegroundService.updateProgress(context, mediaFileID, downloadedBytes, totalBytes);
    }

    private void markComplete(String mediaFileID) {
        DownloadMetadata meta = repository.getByMediaFileID(mediaFileID);
        if (meta != null) {
            OfflineCompanionFetcher.resume(mediaFileID);
            meta.setStatus(DownloadMetadata.Status.COMPLETE);
            meta.setDownloadedBytes(meta.getFileSize());
            meta.setResumeFromOffset(meta.getFileSize());
            meta.setTransferSessionState("completed");
            meta.setErrorMessage(null);
            meta.setDownloadSpeedBytesPerSec(0);
            meta.setEtaSeconds(0);
            meta.setInvalidRangeRetried(false);
            repository.update(meta);
            clearProgressTracking(mediaFileID);

            // M3: pull binary sidecar assets (artwork/captions/comskip/transcript)
            // referenced by the M2-persisted manifests. Best-effort, async,
            // never blocks the queue. No-op when companionDirPath is null
            // (SAF destination or no offline block from server).
            try {
                MiniClient client = MiniclientApplication.get().getClient();
                ServerInfo serverInfo = client != null ? client.getConnectedServerInfo() : null;
                MiniClientConnection connection = client != null ? client.getCurrentConnection() : null;
                if (serverInfo != null) {
                    String base = buildControlPlaneBase(serverInfo);
                    String ngClientId = connection != null ? connection.getClientId() : null;
                    OfflineCompanionFetcher.fetchAsync(meta, base, ngClientId);
                }
            } catch (Exception e) {
                log.warn("Sidecar fetch dispatch failed for {}: {}", mediaFileID, e.toString());
            }
        }
        log.info("Download complete: {}", mediaFileID);
        DownloadForegroundService.notifyComplete(context, mediaFileID);

        // Trans-mux TS / MPEG-PS recordings to MP4 so playback can seek and
        // report duration. Runs on its own thread; the next download in the
        // queue can start in parallel.
        if (meta != null && PostDownloadRemux.shouldRemux(meta)) {
            try {
                new PostDownloadRemux(context, repository).start(meta, null);
            } catch (Throwable t) {
                log.warn("Post-download remux dispatch failed for {}: {}",
                        mediaFileID, t.toString());
            }
        }
        processNext();
    }

    private void handleTaskFailure(DownloadMetadata meta, String errorCode) {
        String normalized = errorCode == null ? "UNKNOWN_ERROR" : errorCode;
        clearProgressTracking(meta.getMediaFileID());

        if (normalized.startsWith(DownloadTask.ERROR_PAUSED_BY_SERVER)) {
            meta.setStatus(DownloadMetadata.Status.PAUSED);
            meta.setTransferSessionState("paused_by_server");
            meta.setErrorMessage(toUserError(normalized, meta));
            repository.update(meta);
            processNext();
            return;
        }

        if (normalized.startsWith(DownloadTask.ERROR_INVALID_RANGE)) {
            if (!meta.isInvalidRangeRetried()) {
                meta.setInvalidRangeRetried(true);
                meta.setDownloadedBytes(0);
                meta.setResumeFromOffset(0);
                meta.setStatus(DownloadMetadata.Status.QUEUED);
                meta.setTransferSessionState("retry_pending");
                meta.setErrorMessage("Retry 1: RANGE_RESET");
                repository.update(meta);
                processNext();
                return;
            }
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

        if (normalized.startsWith(DownloadTask.ERROR_TRANSFER_NOT_FOUND)
                || normalized.startsWith(DownloadTask.ERROR_TRANSFER_GONE)
                || normalized.startsWith("HTTP_404")
                || normalized.startsWith("HTTP_410")) {
            // The SageTV server has no client-callable refresh API (verified against Sage.jar:
            // MiniClientSageRenderer only emits CMD_TRANSFER_* outbound; the server has no inbound
            // handler for them, and HTTPLSServer exposes only /api/transfers/{token}/content).
            // Once the server has dropped the session, the only way to obtain a new token is for
            // the user to re-initiate the download from the SageTV server menu. Fail fast with a
            // user-actionable message and preserve partial bytes on disk for the new session.
            markSessionLost(meta, normalized);
            return;
        }

        scheduleRetry(meta, normalized);
    }

    private void markSessionLost(DownloadMetadata meta, String errorCode) {
        // Before failing the item, try to transparently refresh the session
        // via DOWNLOAD_REFRESH_REQUEST (opcode 228). The SageTV server-side
        // handler echoes the correlationId via a fresh CMD_DOWNLOAD_REQUEST /
        // TRANSFER_SESSION_ACK, which DownloadEventHandler -> enqueue ->
        // mergeWithExisting will route back here as a new sessionToken/url.
        // We attempt this at most once per mediaFileID per process lifetime
        // so a server that never responds cannot put us into a refresh loop.
        // User-initiated retry (retry()) is a separate explicit code path
        // and is not gated by autoRefreshAttempted.
        if (autoRefreshAttempted.add(meta.getMediaFileID())
                && requestSessionRefresh(meta, "auto_stale_token")) {
            log.info("Auto-issued DOWNLOAD_REFRESH_REQUEST for stale-token mediaFileID={} (errorCode={})",
                    meta.getMediaFileID(), errorCode);
            return;
        }
        meta.setStatus(DownloadMetadata.Status.FAILED);
        meta.setTransferSessionState("expired");
        meta.setErrorMessage(toUserError(errorCode, meta));
        repository.update(meta);
        log.warn("Transfer session lost for mediaFileID={} (errorCode={}). Partial bytes preserved: {}",
                meta.getMediaFileID(), errorCode, meta.getDownloadedBytes());
        DownloadForegroundService.notifyError(context, meta.getMediaFileID(), meta.getErrorMessage());
        processNext();
    }

    private void scheduleRetry(DownloadMetadata meta, String errorCode) {
        meta.setRetryCount(meta.getRetryCount() + 1);
        if (meta.getRetryCount() <= MAX_RETRIES) {
            meta.setStatus(DownloadMetadata.Status.QUEUED);
            meta.setTransferSessionState("retry_pending");
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
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable t) {
            // Android 12+ can reject foreground-service starts from background
            // callbacks. Never crash the app for this; queue processing can
            // continue and the service can start on next allowed path.
            log.warn("Download service start skipped: {}", t.toString());
        }
    }

    private void stopServiceIfIdle() {
        List<DownloadMetadata> active = repository.getByStatus(DownloadMetadata.Status.QUEUED);
        List<DownloadMetadata> preparing = repository.getByStatus(DownloadMetadata.Status.PREPARING);
        List<DownloadMetadata> downloading = repository.getByStatus(DownloadMetadata.Status.DOWNLOADING);
        if (active.isEmpty() && preparing.isEmpty() && downloading.isEmpty()) {
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
            case PREPARING:
                return "PREPARING|" + sessionState;
            case DOWNLOADING:
                return "DOWNLOADING|" + meta.getProgressPercent() + "|" + sessionState;
            case COMPLETE:
                if (isUnifiedComplete(meta)) {
                    return "COMPLETE|" + sessionState;
                }
                UnifiedChecklist c = buildUnifiedChecklist(meta);
                return "COMPLETE_PARTIAL|" + c.present + "/" + c.expected + "|" + sessionState;
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
            maybeFetchFullManifest(meta);
            if (meta.getStatus() == DownloadMetadata.Status.DOWNLOADING) {
                meta.setStatus(DownloadMetadata.Status.QUEUED);
                meta.setTransferSessionState("retry_pending");
                repository.update(meta);
                hasWork = true;
            } else if (meta.getStatus() == DownloadMetadata.Status.PREPARING) {
                meta.setStatus(DownloadMetadata.Status.QUEUED);
                meta.setTransferSessionState("retry_pending");
                repository.update(meta);
                hasWork = true;
            } else if (meta.getStatus() == DownloadMetadata.Status.QUEUED
                    || meta.getStatus() == DownloadMetadata.Status.PAUSED
                    || meta.getStatus() == DownloadMetadata.Status.FAILED) {
                if (meta.getStatus() == DownloadMetadata.Status.QUEUED
                        && !isTransferReady(meta)
                        && shouldPromoteQueuedToRetry(meta)) {
                    meta.setTransferSessionState("retry_pending");
                    repository.update(meta);
                }
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
            meta.setAccountPoolExhausted(false);
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

    public void retry(String mediaFileID) {
        restart(mediaFileID);
    }

    /**
     * Re-runs post-download remux for an already-downloaded recording without
     * restarting the transfer session.
     */
    public void retryRemux(String mediaFileID) {
        executeAction(mediaFileID, ActionOptions.remux(RemuxMode.AUTO));
    }

    public void retryRemuxMkv(String mediaFileID) {
        executeAction(mediaFileID, ActionOptions.remux(RemuxMode.MKV));
    }

    public void retryRemuxMp4(String mediaFileID) {
        executeAction(mediaFileID, ActionOptions.remux(RemuxMode.MP4));
    }

    private void retryRemuxWithMode(String mediaFileID, RemuxMode mode) {
        DownloadMetadata meta = repository.getByMediaFileID(mediaFileID);
        if (meta == null) return;
        if (!isPrimaryVideoComplete(meta)) {
            log.warn("retryRemux skipped for {}: primary video not complete", mediaFileID);
            return;
        }
        meta.setStatus(DownloadMetadata.Status.COMPLETE);
        meta.setTransferSessionState("queued_for_remux");
        meta.setErrorMessage(null);
        repository.update(meta);
        try {
            PostDownloadRemux remuxer = new PostDownloadRemux(context, repository);
            if (mode == RemuxMode.MKV) {
                remuxer.startForceMkv(meta, null);
            } else if (mode == RemuxMode.MP4) {
                remuxer.startForceMp4(meta, null);
            } else {
                remuxer.start(meta, null);
            }
            log.info("retryRemux({}) enqueued for {}", mode, mediaFileID);
        } catch (Throwable t) {
            log.warn("retryRemux dispatch failed for {}: {}", mediaFileID, t.toString());
            meta.setTransferSessionState("remux_failed");
            meta.setErrorMessage("Remux retry failed to start");
            repository.update(meta);
        }
    }

    /**
     * Unified restart across primary video and sidecars.
     *
     * <p>If the primary video is already complete, this method does not
     * restart/transcode video bytes; it only re-runs best-effort sidecar
     * fetch, which naturally skips files that already exist and only fills
     * missing/partial assets.
     */
    public void restart(String mediaFileID) {
        executeAction(mediaFileID, ActionOptions.restartOnly());
    }

    private void restartInternal(String mediaFileID) {
        DownloadMetadata meta = repository.getByMediaFileID(mediaFileID);
        if (meta == null) return;
        log.info("Restart requested: id={} status={} sessionState={} bytes={}/{}",
            mediaFileID,
            meta.getStatus(),
            meta.getTransferSessionState(),
            meta.getDownloadedBytes(),
            meta.getFileSize());
        OfflineCompanionFetcher.resume(mediaFileID);

        if (isPrimaryVideoComplete(meta)) {
            meta.setStatus(DownloadMetadata.Status.COMPLETE);
            boolean unifiedDone = isUnifiedComplete(meta);
            meta.setTransferSessionState(unifiedDone ? "completed" : "completed_partial");
            if (unifiedDone) {
                meta.setErrorMessage(null);
            } else {
                UnifiedChecklist c = buildUnifiedChecklist(meta);
                meta.setErrorMessage("COMPANION_PENDING|" + c.present + "/" + c.expected);
            }
            meta.setRetryCount(0);
            repository.update(meta);
            if (!unifiedDone) {
                log.info("Restart dispatching companion fetch: id={} progress={}",
                        mediaFileID, meta.getErrorMessage());
                dispatchCompanionFetch(meta);
            }
            return;
        }

        meta.setRetryCount(0);
        meta.setInvalidRangeRetried(false);
        meta.setErrorMessage(null);
        meta.setStatus(DownloadMetadata.Status.QUEUED);

        // If the session was lost ("expired"), the stale token will 404 again
        // immediately. Ask the server to re-issue a fresh TRANSFER_SESSION_ACK
        // (download_url + session_token) for this mediaFileID via
        // DOWNLOAD_REFRESH_REQUEST. When the server pushes the new ACK back
        // through CMD_DOWNLOAD_REQUEST, DownloadEventHandler -> enqueue ->
        // mergeWithExisting will update sessionToken/downloadUrl, clear the
        // error, normalize state, and processNext. We deliberately do NOT
        // processNext here in that case; isTransferReady() returns false for
        // "awaiting_refresh" so the queue stays parked until the fresh ACK
        // arrives. If no ACK arrives within REFRESH_TIMEOUT_MS we unstick the
        // row with an actionable error message.
        boolean expired = "expired".equalsIgnoreCase(meta.getTransferSessionState());
        if (expired) {
            // User-initiated retry: clear the auto-refresh "already tried"
            // flag so a fresh attempt can be made, then route through the
            // same emit-and-wait helper used by automatic stale detection.
            autoRefreshAttempted.remove(meta.getMediaFileID());
            if (requestSessionRefresh(meta, "user_retry_expired")) {
                return;
            }
            // requestSessionRefresh already finalized meta to FAILED with a
            // user-actionable message; nothing more to do here.
            return;
        }

        meta.setTransferSessionState("retry_pending");
        repository.update(meta);
        log.info("Restart queued full transfer retry: id={}", mediaFileID);
        processNext();
    }

    /**
     * Sets the item to {@code awaiting_refresh}, emits a
     * {@code DOWNLOAD_REFRESH_REQUEST} (opcode 228) on the event channel, and
     * schedules a timeout fallback that finalizes the item as FAILED if no
     * fresh {@code CMD_DOWNLOAD_REQUEST} arrives within
     * {@link #REFRESH_TIMEOUT_MS}.
     *
     * <p>Returns {@code true} if the request was successfully written to the
     * event channel (item is now parked in {@code awaiting_refresh} and the
     * caller should NOT mark FAILED). Returns {@code false} if the request
     * could not be sent (no live connection, write failed, etc.); in that
     * case this method has already finalized {@code meta} as FAILED with an
     * appropriate error message and the caller should not touch it again.
     */
    private boolean requestSessionRefresh(DownloadMetadata meta, String reason) {
        meta.setTransferSessionState("awaiting_refresh");
        meta.setStatus(DownloadMetadata.Status.QUEUED);
        meta.setErrorMessage(REFRESH_PENDING_MESSAGE);
        repository.update(meta);
        final MiniClient client = MiniclientApplication.get().getClient();
        final MiniClientConnection connection = client != null ? client.getCurrentConnection() : null;
        if (connection == null) {
            log.warn("Cannot refresh session for {}: no active MiniClient connection",
                    meta.getMediaFileID());
            meta.setStatus(DownloadMetadata.Status.FAILED);
            meta.setTransferSessionState("expired");
            meta.setErrorMessage("NO_CONNECTION|Connect to the SageTV server and try again.");
            repository.update(meta);
            return false;
        }
        final String mediaFileID = meta.getMediaFileID();
        final String corr = meta.getCorrelationId() != null ? meta.getCorrelationId()
                : UUID.randomUUID().toString();
        // Run the actual socket write off the calling thread (main thread
        // for user-initiated retry; worker thread for auto-stale path). The
        // write blocks on flush(), and on a torn-down channel it will throw
        // and call eventChannelError() which we should not be doing under
        // the UI thread.
        executor.submit(() -> {
            try {
                connection.postDownloadRefreshRequest(
                        mediaFileID,
                        reason,
                        corr,
                        meta.getSessionToken(),
                        Math.max(meta.getDownloadedBytes(), meta.getResumeFromOffset()));
                log.info("Posted DOWNLOAD_REFRESH_REQUEST mediaFileID={} reason={} corr={}",
                        mediaFileID, reason, corr);
            } catch (Throwable t) {
                log.warn("Failed to post DOWNLOAD_REFRESH_REQUEST for {}: {}: {}",
                        mediaFileID, t.getClass().getName(), t.getMessage());
                DownloadMetadata cur = repository.getByMediaFileID(mediaFileID);
                if (cur != null
                        && "awaiting_refresh".equalsIgnoreCase(cur.getTransferSessionState())) {
                    cur.setStatus(DownloadMetadata.Status.FAILED);
                    cur.setTransferSessionState("expired");
                    cur.setErrorMessage("REFRESH_FAILED|" + t.getClass().getSimpleName()
                            + ": " + t.getMessage());
                    repository.update(cur);
                }
            }
        });
        // Schedule the timeout fallback on the same executor; it will fire
        // after REFRESH_TIMEOUT_MS regardless of whether the post succeeded.
        executor.submit(() -> {
            sleepQuietly(REFRESH_TIMEOUT_MS);
            DownloadMetadata cur = repository.getByMediaFileID(mediaFileID);
            if (cur != null
                    && "awaiting_refresh".equalsIgnoreCase(cur.getTransferSessionState())) {
                cur.setStatus(DownloadMetadata.Status.FAILED);
                cur.setTransferSessionState("expired");
                cur.setErrorMessage(
                        "REFRESH_TIMEOUT|Server did not respond to refresh request. "
                      + "Try again or re-initiate from the SageTV server menu.");
                repository.update(cur);
                log.warn("DOWNLOAD_REFRESH_REQUEST timed out for {} after {}ms",
                        mediaFileID, REFRESH_TIMEOUT_MS);
            }
        });
        return true;
    }

    public void pauseAll() {
        List<DownloadMetadata> all = repository.getAll();
        for (DownloadMetadata meta : all) {
            if (meta.getStatus() == DownloadMetadata.Status.QUEUED
                    || meta.getStatus() == DownloadMetadata.Status.PREPARING
                    || meta.getStatus() == DownloadMetadata.Status.DOWNLOADING) {
                pause(meta.getMediaFileID());
            }
        }
    }

    public void resumeAll() {
        List<DownloadMetadata> all = repository.getAll();
        for (DownloadMetadata meta : all) {
            if (meta.getStatus() == DownloadMetadata.Status.PAUSED
                    || meta.getStatus() == DownloadMetadata.Status.FAILED) {
                resume(meta.getMediaFileID());
            }
        }
    }

    public void clearCompleted() {
        List<DownloadMetadata> complete = repository.getByStatus(DownloadMetadata.Status.COMPLETE);
        for (DownloadMetadata meta : complete) {
            OfflineCompanionFetcher.cancel(meta.getMediaFileID());
            if (meta.getLocalUri() != null) {
                storageHelper.deleteFile(meta.getLocalUri());
            }
            deleteCompanionArtifacts(meta);
            repository.remove(meta.getMediaFileID());
        }
    }

    private boolean isPrimaryVideoComplete(DownloadMetadata meta) {
        if (meta == null) return false;
        if (meta.getStatus() == DownloadMetadata.Status.COMPLETE) return true;
        long total = meta.getFileSize();
        if (total <= 0) return false;
        return meta.getDownloadedBytes() >= total || meta.getResumeFromOffset() >= total;
    }

    private boolean isUnifiedComplete(DownloadMetadata meta) {
        if (!isPrimaryVideoComplete(meta)) return false;
        UnifiedChecklist checklist = buildUnifiedChecklist(meta);
        return checklist.present >= checklist.expected;
    }

    private UnifiedChecklist buildUnifiedChecklist(DownloadMetadata meta) {
        UnifiedChecklist c = new UnifiedChecklist();
        c.expected = 1;
        c.present = isPrimaryVideoComplete(meta) ? 1 : 0;

        String dirPath = meta != null ? meta.getCompanionDirPath() : null;
        File dir = (dirPath == null || dirPath.isEmpty()) ? null : new File(dirPath);

        int expectedArtwork = expectedArtwork(meta != null ? meta.getArtworkManifestJson() : null);
        c.expected += expectedArtwork;
        c.present += presentArtwork(dir);

        int expectedCaptions = countUrlEntries(meta != null ? meta.getCaptionsManifestJson() : null);
        c.expected += expectedCaptions;
        c.present += countFilesInDir(dir == null ? null : new File(dir, "captions"));

        int expectedComskip = countUrlEntries(meta != null ? meta.getComskipManifestJson() : null);
        c.expected += expectedComskip;
        c.present += countFilesWithPrefix(dir, "comskip.");

        int expectedTranscript = countUrlEntries(meta != null ? meta.getTranscriptManifestJson() : null);
        c.expected += expectedTranscript;
        c.present += countFilesWithPrefix(dir, "transcript.");

        if (c.present > c.expected) c.present = c.expected;
        return c;
    }

    private static int expectedArtwork(String json) {
        if (json == null || json.isEmpty()) return 0;
        try {
            JSONArray arr = new JSONArray(json);
            int count = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String kind = item.optString("kind", "");
                if ("thumbnail".equals(kind) || "poster".equals(kind) || "fanart".equals(kind) || "banner".equals(kind)) {
                    count++;
                } else if ("cast".equals(kind) || "person".equals(kind)) {
                    String pid = item.optString("person_id", "");
                    if (pid.isEmpty()) {
                        pid = item.optString("subject_id", "");
                    }
                    if (!pid.isEmpty()) count++;
                }
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int presentArtwork(File dir) {
        if (dir == null || !dir.exists()) return 0;
        int count = 0;
        if (new File(dir, "thumbnail.jpg").isFile()) count++;
        if (new File(dir, "poster.jpg").isFile()) count++;
        if (new File(dir, "fanart.jpg").isFile()) count++;
        if (new File(dir, "banner.jpg").isFile()) count++;
        File castDir = new File(dir, "cast");
        count += countFilesBySuffix(castDir, ".jpg");
        return count;
    }

    private static int countFilesInDir(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int count = 0;
        for (File f : files) {
            if (f != null && f.isFile() && f.length() > 0 && !f.getName().endsWith(".part")) {
                count++;
            }
        }
        return count;
    }

    private static int countFilesWithPrefix(File dir, String prefix) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int count = 0;
        for (File f : files) {
            if (f == null || !f.isFile() || f.length() <= 0) continue;
            String name = f.getName();
            if (name.endsWith(".part")) continue;
            if (name.startsWith(prefix)) count++;
        }
        return count;
    }

    private static int countFilesBySuffix(File dir, String suffix) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int count = 0;
        for (File f : files) {
            if (f == null || !f.isFile() || f.length() <= 0) continue;
            if (f.getName().endsWith(suffix)) count++;
        }
        return count;
    }

    private static int countUrlEntries(String json) {
        if (json == null || json.isEmpty()) return 0;
        try {
            Object root = new JSONTokener(json).nextValue();
            return countUrlEntriesRecursive(root);
        } catch (Exception e) {
            return 0;
        }
    }

    private static int countUrlEntriesRecursive(Object node) {
        if (node == null) return 0;
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            int count = 0;
            String url = obj.optString("url", null);
            if (url != null && !url.isEmpty()) count++;
            JSONArray names = obj.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String key = names.optString(i, null);
                    if (key == null) continue;
                    Object child = obj.opt(key);
                    if (child != null && child != JSONObject.NULL) {
                        count += countUrlEntriesRecursive(child);
                    }
                }
            }
            return count;
        }
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            int count = 0;
            for (int i = 0; i < arr.length(); i++) {
                Object child = arr.opt(i);
                if (child != null && child != JSONObject.NULL) {
                    count += countUrlEntriesRecursive(child);
                }
            }
            return count;
        }
        return 0;
    }

    /** Re-fetch all companion sidecars for an already-complete recording. */
    public void refreshSidecars(String mediaFileID) {
        executeAction(mediaFileID, getDefaultSidecarActionOptions(mediaFileID));
    }

    public void refreshCaptions(String mediaFileID) {
        executeAction(mediaFileID, ActionOptions.sidecarsCaptions());
    }

    public void refreshComskip(String mediaFileID) {
        executeAction(mediaFileID, ActionOptions.sidecarsComskip());
    }

    public void refreshTranscript(String mediaFileID) {
        executeAction(mediaFileID, ActionOptions.sidecarsTranscript());
    }

    public void setSidecarRefreshSelection(String mediaFileID,
                                           boolean includeArtwork,
                                           boolean includeCaptions,
                                           boolean includeComskip,
                                           boolean includeTranscript) {
        DownloadMetadata meta = repository.getByMediaFileID(mediaFileID);
        if (meta == null) return;
        meta.setSidecarSelectionConfigured(true);
        meta.setSidecarRefreshArtwork(includeArtwork);
        meta.setSidecarRefreshCaptions(includeCaptions);
        meta.setSidecarRefreshComskip(includeComskip);
        meta.setSidecarRefreshTranscript(includeTranscript);
        repository.update(meta);
    }

    public void enableSidecarRefreshFunctions(String mediaFileID,
                                              boolean includeCaptions,
                                              boolean includeComskip,
                                              boolean includeTranscript) {
        DownloadMetadata meta = repository.getByMediaFileID(mediaFileID);
        if (meta == null) return;
        meta.setSidecarSelectionConfigured(true);
        meta.setSidecarRefreshArtwork(meta.isSidecarRefreshArtwork());
        if (includeCaptions) meta.setSidecarRefreshCaptions(true);
        if (includeComskip) meta.setSidecarRefreshComskip(true);
        if (includeTranscript) meta.setSidecarRefreshTranscript(true);
        repository.update(meta);
    }

    private SidecarAvailability getSidecarAvailability(DownloadMetadata meta) {
        if (meta == null) {
            return new SidecarAvailability(false, false, false, false);
        }
        return new SidecarAvailability(
                countUrlEntries(meta.getArtworkManifestJson()) > 0,
                countUrlEntries(meta.getCaptionsManifestJson()) > 0,
                countUrlEntries(meta.getComskipManifestJson()) > 0,
                countUrlEntries(meta.getTranscriptManifestJson()) > 0);
    }

    private void clearUnavailableSelectedSidecarFlags(DownloadMetadata meta, SidecarAvailability available) {
        if (meta == null || available == null || !meta.isSidecarSelectionConfigured()) {
            return;
        }
        boolean changed = false;
        if (meta.isSidecarRefreshArtwork() && !available.artwork) {
            meta.setSidecarRefreshArtwork(false);
            changed = true;
        }
        if (meta.isSidecarRefreshCaptions() && !available.captions) {
            meta.setSidecarRefreshCaptions(false);
            changed = true;
        }
        if (meta.isSidecarRefreshComskip() && !available.comskip) {
            meta.setSidecarRefreshComskip(false);
            changed = true;
        }
        if (meta.isSidecarRefreshTranscript() && !available.transcript) {
            meta.setSidecarRefreshTranscript(false);
            changed = true;
        }
        if (changed) {
            repository.update(meta);
        }
    }

    private void dispatchSidecarFetchById(String mediaFileID) {
        ActionOptions defaults = getDefaultSidecarActionOptions(mediaFileID);
        dispatchSidecarFetchById(mediaFileID,
                defaults.refreshArtwork,
                defaults.refreshCaptions,
                defaults.refreshComskip,
                defaults.refreshTranscript);
    }

    private void dispatchSidecarFetchById(String mediaFileID,
                                          boolean includeArtwork,
                                          boolean includeCaptions,
                                          boolean includeComskip,
                                          boolean includeTranscript) {
        DownloadMetadata meta = repository.getByMediaFileID(mediaFileID);
        if (meta == null) return;
        SidecarAvailability available = getSidecarAvailability(meta);
        clearUnavailableSelectedSidecarFlags(meta, available);
        dispatchCompanionFetch(meta,
            includeArtwork && available.artwork,
            includeCaptions && available.captions,
            includeComskip && available.comskip,
            includeTranscript && available.transcript);
    }

    private void dispatchCompanionFetch(DownloadMetadata meta) {
        ActionOptions defaults = getDefaultSidecarActionOptions(
                meta != null ? meta.getMediaFileID() : null);
        dispatchCompanionFetch(meta,
            defaults.refreshArtwork,
            defaults.refreshCaptions,
            defaults.refreshComskip,
            defaults.refreshTranscript);
    }

    private ActionOptions getDefaultSidecarActionOptions(String mediaFileID) {
        DownloadMetadata meta = mediaFileID == null ? null : repository.getByMediaFileID(mediaFileID);
        if (meta != null && meta.isSidecarSelectionConfigured()) {
            return ActionOptions.builder()
                    .refreshArtwork(meta.isSidecarRefreshArtwork())
                    .refreshCaptions(meta.isSidecarRefreshCaptions())
                    .refreshComskip(meta.isSidecarRefreshComskip())
                    .refreshTranscript(meta.isSidecarRefreshTranscript())
                    .build();
        }
        MiniClient client = MiniclientApplication.get().getClient();
        boolean captions = client != null
            && client.properties().getBoolean(PrefStore.Keys.offline_cap_captions, true);
        boolean comskip = client != null
            && client.properties().getBoolean(PrefStore.Keys.offline_cap_comskip, true);
        boolean transcript = client != null
            && client.properties().getBoolean(PrefStore.Keys.offline_cap_transcript, true);
        return ActionOptions.builder()
            .refreshArtwork(true)
            .refreshCaptions(captions)
            .refreshComskip(comskip)
            .refreshTranscript(transcript)
            .build();
        }

    private void dispatchCompanionFetch(DownloadMetadata meta,
                                        boolean includeArtwork,
                                        boolean includeCaptions,
                                        boolean includeComskip,
                                        boolean includeTranscript) {
        if (meta == null) return;
        if (!includeArtwork && !includeCaptions && !includeComskip && !includeTranscript) {
            log.info("sidecar_refresh_skipped mediaFileID={} reason=no_selected_sidecars", meta.getMediaFileID());
            meta.setTransferSessionState("sidecar_refresh_skipped");
            meta.setErrorMessage("No selected sidecar data available to refresh");
            repository.update(meta);
            return;
        }
        log.info("sidecar_refresh_requested mediaFileID={} artwork={} captions={} comskip={} transcript={}",
                meta.getMediaFileID(), includeArtwork, includeCaptions, includeComskip, includeTranscript);
        meta.setTransferSessionState("sidecar_refresh_requested");
        meta.setErrorMessage("Refreshing selected sidecar data");
        repository.update(meta);
        try {
            MiniClient client = MiniclientApplication.get().getClient();
            ServerInfo serverInfo = resolveReconnectServer(meta, client);
            MiniClientConnection connection = client != null ? client.getCurrentConnection() : null;
            String base = serverInfo != null
                ? buildControlPlaneBase(serverInfo)
                : firstNonBlank(
                    extractControlPlaneBaseFromUrl(meta.getDownloadUrl()),
                    extractControlPlaneBaseFromUrl(meta.getOfflineMetadataUrl()));
            if (base != null) {
            String ngClientId = firstNonBlank(
                connection != null ? connection.getClientId() : null,
                extractClientIdFromDownloadUrl(meta.getDownloadUrl()));
                OfflineCompanionFetcher.fetchAsync(meta,
                        base,
                        ngClientId,
                        includeArtwork,
                        includeCaptions,
                        includeComskip,
                        includeTranscript,
                        (mediaFileId, fetched, skipped, failed, error) -> {
                            DownloadMetadata refreshed = repository.getByMediaFileID(mediaFileId);
                            if (refreshed == null) return;
                            if (error != null && !error.isEmpty()) {
                                refreshed.setTransferSessionState("sidecar_refresh_failed");
                                refreshed.setErrorMessage("Sidecar refresh failed: " + error);
                            } else if (failed > 0 && fetched > 0) {
                                refreshed.setTransferSessionState("sidecar_refresh_partial");
                                refreshed.setErrorMessage("Sidecar refresh partial: " + fetched
                                        + " fetched, " + failed + " failed");
                            } else if (failed > 0) {
                                refreshed.setTransferSessionState("sidecar_refresh_failed");
                                refreshed.setErrorMessage("Sidecar refresh failed: " + failed + " failed");
                            } else {
                                refreshed.setTransferSessionState("sidecar_refresh_complete");
                                refreshed.setErrorMessage("Sidecar refresh complete: " + fetched
                                        + " fetched, " + skipped + " cached");
                            }
                            repository.update(refreshed);
                        });
            } else {
                meta.setTransferSessionState("sidecar_refresh_unavailable");
                meta.setErrorMessage("Cannot refresh sidecars: no connected server");
                repository.update(meta);
            }
        } catch (Exception e) {
            log.warn("Sidecar fetch dispatch failed for {}: {}", meta.getMediaFileID(), e.toString());
            meta.setTransferSessionState("sidecar_refresh_failed");
            meta.setErrorMessage("Sidecar refresh dispatch failed: " + e.getClass().getSimpleName());
            repository.update(meta);
        }
    }

    private void maybeFetchFullManifest(DownloadMetadata meta) {
        if (meta == null) return;
        String mediaFileId = nullIfBlank(meta.getMediaFileID());
        if (mediaFileId == null) return;

        final String pointer = firstNonBlank(meta.getOfflineMetadataUrl(), meta.getOfflineMetadataPath());
        if (pointer == null) {
            return;
        }
        if (!manifestFetchInFlight.add(mediaFileId)) {
            return;
        }

        final String inlineLevel = normalizeInlineLevel(meta.getOfflineInlineLevel());
        // PRD 5.8: "core" = inline is intentionally light; full manifest is
        // urgent because artwork/credits aren't available to the UI yet.
        // "full" still permits a background refresh fetch for consistency.
        final boolean urgent = "core".equalsIgnoreCase(inlineLevel);
        ExecutorService target = urgent ? urgentManifestFetchExecutor : manifestFetchExecutor;
        log.info("full_manifest_fetch_scheduled mediaFileID={} inline_level={} urgent={}",
                mediaFileId, inlineLevel, urgent);
        target.submit(() -> {
            try {
                fetchFullManifestWithRetry(mediaFileId, inlineLevel);
            } finally {
                manifestFetchInFlight.remove(mediaFileId);
            }
        });
    }

    private void fetchFullManifestWithRetry(String mediaFileId, String inlineLevel) {
        for (int attempt = 1; attempt <= MANIFEST_FETCH_ATTEMPTS; attempt++) {
            DownloadMetadata current = repository.getByMediaFileID(mediaFileId);
            if (current == null) return;

            if (FORCE_FRESH_MANIFEST_ON_USER_REFRESH
                    && userMetadataRefreshPending.contains(mediaFileId)
                    && forcedFreshManifestRequested.add(mediaFileId)
                    && !manifestRefreshAttempted.contains(mediaFileId)) {
                current.setTransferSessionState("metadata_refresh_reauth");
                current.setErrorMessage("Metadata refresh requesting fresh session from server");
                repository.update(current);
                requestManifestSessionRefresh(current, inlineLevel, "forced_fresh_manifest");
                return;
            }

            String pointer = firstNonBlank(current.getOfflineMetadataUrl(), current.getOfflineMetadataPath());
            if (pointer == null) return;

            try {
                if (userMetadataRefreshPending.contains(mediaFileId)) {
                    current.setTransferSessionState("metadata_refresh_fetching");
                    current.setErrorMessage("Fetching manifest attempt " + attempt + "/" + MANIFEST_FETCH_ATTEMPTS);
                    repository.update(current);
                }
                String base = getControlPlaneBaseOrNull();
                String resolved = resolveUrl(base, pointer);
                String raw = fetchManifestText(
                        resolved,
                        current.getSessionToken(),
                        ensureCorrelationId(current),
                        getNgClientIdOrNull());

                // Strict accept: only manifest_version=1 payloads can replace snapshot.
                OfflineManifestV1 parsed = OfflineManifestV1.parse(raw);
                OfflineCompanionStore.apply(context, current, raw);
                SidecarAvailability available = getSidecarAvailability(current);
                clearUnavailableSelectedSidecarFlags(current, available);
                repository.update(current);
                log.info("manifest_sidecar_availability mediaFileID={} artwork={} captions={} comskip={} transcript={} user_requested={}",
                        mediaFileId,
                        available.artwork,
                        available.captions,
                        available.comskip,
                        available.transcript,
                        userMetadataRefreshPending.contains(mediaFileId));
                if (userMetadataRefreshPending.contains(mediaFileId)) {
                    dispatchCompanionFetch(current, available.artwork, false, false, false);
                } else {
                    dispatchCompanionFetch(current);
                }
                markUserMetadataRefreshResult(mediaFileId, true, null);
                // PRD 5.8 validation #1: details/artwork must update without
                // restart. Wake up any registered listeners (detail/list
                // activities) so they re-bind from the refreshed repository
                // snapshot. Also clear any pending persistent-retry schedule.
                cancelPersistentManifestRetry(mediaFileId);
                manifestPersistentRetryAttempt.remove(mediaFileId);
                notifyManifestUpdated(mediaFileId);
                log.info("full_manifest_fetch_success mediaFileID={} inline_level={} attempt={} hash={} artwork_count={}",
                        mediaFileId,
                        inlineLevel,
                        attempt,
                        parsed.getSnippetHash(),
                        parsed.getArtworkCount());
                return;
            } catch (Exception e) {
                if (userMetadataRefreshPending.contains(mediaFileId)
                        && isManifestControlPlaneConnectionError(e)) {
                    String reason = e.getMessage();
                    if (reason == null || reason.trim().isEmpty()) {
                        reason = e.toString();
                    }
                    log.warn("full_manifest_refresh_connectivity_failure mediaFileID={} inline_level={} attempt={}/{} reason={}",
                            mediaFileId,
                            inlineLevel,
                            attempt,
                            MANIFEST_FETCH_ATTEMPTS,
                            reason);
                    markUserMetadataRefreshResult(mediaFileId, false,
                            "Metadata refresh failed: unable to reach SageTV server");
                    return;
                }
                if (isManifestRefreshableHttpError(e)) {
                    if (!userMetadataRefreshPending.contains(mediaFileId)) {
                        // Background two-step fetches for inline_level=core need
                        // full manifest data to populate offline artwork/credits.
                        // If the fetch failed due to an auth/session refreshable
                        // error, attempt standalone HTTP reauth once inline-core
                        // is detected instead of waiting only on persistent retry.
                        if ("core".equalsIgnoreCase(inlineLevel)) {
                            try {
                                if (tryHttpManifestSessionRefresh(current, inlineLevel,
                                        "inline_core_auto_reauth")) {
                                    sleepQuietly(150);
                                    continue;
                                }
                            } catch (Throwable refreshError) {
                                log.info("full_manifest_refresh_auto_reauth_failed mediaFileID={} inline_level={} reason={}",
                                        mediaFileId, inlineLevel, refreshError.toString());
                            }
                        }
                        log.info("full_manifest_refresh_skipped mediaFileID={} inline_level={} reason=not_user_requested",
                                mediaFileId, inlineLevel);
                        schedulePersistentManifestRetry(mediaFileId, inlineLevel);
                        return;
                    }
                    if (userMetadataRefreshPending.contains(mediaFileId)) {
                        current.setTransferSessionState("metadata_refresh_reauth");
                        current.setErrorMessage("Metadata refresh requested new session from server: "
                                + manifestRefreshReasonForException(e));
                        repository.update(current);
                    }
                    requestManifestSessionRefresh(current, inlineLevel,
                            manifestRefreshReasonForException(e));
                    return;
                }
                log.warn("full_manifest_fetch_failure mediaFileID={} inline_level={} attempt={}/{} reason={}",
                        mediaFileId,
                        inlineLevel,
                        attempt,
                        MANIFEST_FETCH_ATTEMPTS,
                        e.toString());
                if (attempt >= MANIFEST_FETCH_ATTEMPTS) {
                    String reason = e.getMessage();
                    if (reason == null || reason.trim().isEmpty()) {
                        reason = e.toString();
                    }
                    markUserMetadataRefreshResult(mediaFileId, false,
                            "Metadata refresh failed: " + reason);
                    // PRD 5.8: keep inline rendering and retry in the background
                    // with bounded backoff; never fail the download.
                    schedulePersistentManifestRetry(mediaFileId, inlineLevel);
                    return;
                }
                sleepQuietly(MANIFEST_FETCH_RETRY_DELAYS_MS[Math.min(attempt - 1,
                        MANIFEST_FETCH_RETRY_DELAYS_MS.length - 1)]);
            }
        }
    }

    /**
     * Schedule the next persistent retry pass for {@code mediaFileId}. Each
     * call advances through {@link #MANIFEST_PERSISTENT_RETRY_DELAYS_MS}; once
     * the schedule is exhausted, subsequent retries reuse the final (max) delay
     * so we eventually pick up a fixed transient outage without spinning.
     */
    private void schedulePersistentManifestRetry(String mediaFileId, String inlineLevel) {
        if (mediaFileId == null || mediaFileId.isEmpty()) return;
        int nextAttempt = manifestPersistentRetryAttempt
                .compute(mediaFileId, (k, v) -> v == null ? 0 : v + 1);
        long delayMs = MANIFEST_PERSISTENT_RETRY_DELAYS_MS[Math.min(nextAttempt,
                MANIFEST_PERSISTENT_RETRY_DELAYS_MS.length - 1)];
        log.info("full_manifest_persistent_retry_scheduled mediaFileID={} inline_level={} pass={} delay_ms={}",
                mediaFileId, inlineLevel, nextAttempt + 1, delayMs);
        // Replace any previously scheduled retry for this id so we don't fan out.
        java.util.concurrent.ScheduledFuture<?> prior = manifestPendingRetries.remove(mediaFileId);
        if (prior != null) prior.cancel(false);
        java.util.concurrent.ScheduledFuture<?> future = manifestRetryScheduler.schedule(() -> {
            manifestPendingRetries.remove(mediaFileId);
            DownloadMetadata refreshed = repository.getByMediaFileID(mediaFileId);
            if (refreshed == null) {
                manifestPersistentRetryAttempt.remove(mediaFileId);
                return;
            }
            maybeFetchFullManifest(refreshed);
        }, delayMs, TimeUnit.MILLISECONDS);
        manifestPendingRetries.put(mediaFileId, future);
    }

    private void cancelPersistentManifestRetry(String mediaFileId) {
        if (mediaFileId == null || mediaFileId.isEmpty()) return;
        java.util.concurrent.ScheduledFuture<?> future = manifestPendingRetries.remove(mediaFileId);
        if (future != null) future.cancel(false);
    }

    private void markUserMetadataRefreshResult(String mediaFileId, boolean success, String detail) {
        if (mediaFileId == null || mediaFileId.isEmpty()) return;
        cancelManifestRefreshAckTimeout(mediaFileId);
        manifestRefreshAckTimeoutAttempt.remove(mediaFileId);
        forcedFreshManifestRequested.remove(mediaFileId);
        if (!userMetadataRefreshPending.remove(mediaFileId)) return;
        DownloadMetadata meta = repository.getByMediaFileID(mediaFileId);
        if (meta == null) return;
        if (success) {
            meta.setTransferSessionState("metadata_refresh_complete");
            meta.setErrorMessage(null);
        } else {
            meta.setTransferSessionState("metadata_refresh_failed");
            meta.setErrorMessage(detail == null || detail.isEmpty()
                    ? "Metadata refresh failed"
                    : detail);
        }
        repository.update(meta);
                closeBackgroundRefreshSessionIfOwned(success ? "metadata_refresh_complete" : "metadata_refresh_failed");
    }

    // ---- Manifest update listeners (PRD 5.8 validation #1: live UI refresh) ----

    /**
     * Register a listener that will be invoked on the Android main thread
     * whenever the full manifest for {@code mediaFileId} replaces the inline
     * first-paint snapshot. Safe to call multiple times with distinct
     * {@link Runnable}s; pair every call with
     * {@link #removeManifestUpdateListener(String, Runnable)} from the same
     * lifecycle scope to avoid leaks.
     */
    public void addManifestUpdateListener(String mediaFileId, Runnable listener) {
        if (mediaFileId == null || mediaFileId.isEmpty() || listener == null) return;
        manifestUpdateListeners
                .computeIfAbsent(mediaFileId, k -> new CopyOnWriteArraySet<>())
                .add(listener);
    }

    public void removeManifestUpdateListener(String mediaFileId, Runnable listener) {
        if (mediaFileId == null || mediaFileId.isEmpty() || listener == null) return;
        Set<Runnable> set = manifestUpdateListeners.get(mediaFileId);
        if (set != null) {
            set.remove(listener);
            if (set.isEmpty()) {
                manifestUpdateListeners.remove(mediaFileId, set);
            }
        }
    }

    private void notifyManifestUpdated(String mediaFileId) {
        Set<Runnable> set = manifestUpdateListeners.get(mediaFileId);
        if (set == null || set.isEmpty()) return;
        for (Runnable r : set) {
            mainHandler.post(() -> {
                try { r.run(); }
                catch (Throwable t) { log.warn("Manifest update listener threw: {}", t.toString()); }
            });
        }
    }

    private static String fetchManifestText(String resolvedUrl,
                                            String sessionToken,
                                            String correlationId,
                                            String ngClientId) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(resolvedUrl).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("Accept", "application/json,*/*");
            if (sessionToken != null && !sessionToken.isEmpty()) {
                conn.setRequestProperty("X-Transfer-Token", sessionToken);
            }
            if (ngClientId != null && !ngClientId.isEmpty()) {
                conn.setRequestProperty("x-ng-client-id", ngClientId);
            }
            if (correlationId != null && !correlationId.isEmpty()) {
                conn.setRequestProperty("X-Correlation-ID", correlationId);
            }
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("HTTP_" + code);
            }
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8 * 1024];
                int total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_MANIFEST_BYTES) {
                        throw new IllegalStateException("manifest exceeds " + MAX_MANIFEST_BYTES + " bytes");
                    }
                    out.write(buf, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String getControlPlaneBaseOrNull() {
        try {
            MiniClient client = MiniclientApplication.get().getClient();
            ServerInfo serverInfo = client != null ? client.getConnectedServerInfo() : null;
            if (serverInfo == null) return null;
            return buildControlPlaneBase(serverInfo);
        } catch (Exception e) {
            return null;
        }
    }

    private String getNgClientIdOrNull() {
        try {
            MiniClient client = MiniclientApplication.get().getClient();
            MiniClientConnection connection = client != null ? client.getCurrentConnection() : null;
            return connection != null ? connection.getClientId() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void requestManifestSessionRefresh(DownloadMetadata meta,
                                               String inlineLevel,
                                               String reasonCode) {
        if (meta == null || meta.getMediaFileID() == null || meta.getMediaFileID().isEmpty()) return;
        final String mediaFileID = meta.getMediaFileID();
        if (!userMetadataRefreshPending.contains(mediaFileID)) {
            log.info("full_manifest_refresh_skipped mediaFileID={} inline_level={} reason=not_user_requested",
                mediaFileID, inlineLevel);
            return;
        }
        final String refreshReason = nullIfBlank(reasonCode) != null
                ? reasonCode
                : "manifest_fetch_401";
        executor.submit(() -> {
            DownloadMetadata current = repository.getByMediaFileID(mediaFileID);
            if (current == null) return;
            try {
                if (tryHttpManifestSessionRefresh(current, inlineLevel, refreshReason)) {
                    return;
                }
            } catch (Throwable t) {
                log.warn("HTTP manifest refresh attempt failed for {}: {}: {}",
                        mediaFileID, t.getClass().getName(), t.getMessage());
            }
            requestManifestSessionRefreshViaCommandChannel(current, inlineLevel, refreshReason);
        });
    }

    private void requestManifestSessionRefreshViaCommandChannel(DownloadMetadata meta,
                                                                String inlineLevel,
                                                                String reasonCode) {
        if (meta == null || meta.getMediaFileID() == null || meta.getMediaFileID().isEmpty()) return;
        final String mediaFileID = meta.getMediaFileID();
        final MiniClient client = MiniclientApplication.get().getClient();
        final MiniClientConnection connection = getReadyCommandChannelConnection(client);
        if (connection == null) {
            int attempt = manifestRefreshReconnectAttempt
                .compute(mediaFileID, (k, v) -> v == null ? 1 : v + 1);
            if (attempt <= MAX_MANIFEST_REFRESH_RECONNECT_ATTEMPTS) {
            long delayMs = MANIFEST_REFRESH_RECONNECT_DELAYS_MS[Math.min(
                attempt - 1,
                MANIFEST_REFRESH_RECONNECT_DELAYS_MS.length - 1)];
            meta.setTransferSessionState("metadata_refresh_reauth");
            meta.setErrorMessage("Standalone reauth retry " + attempt + "/"
                + MAX_MANIFEST_REFRESH_RECONNECT_ATTEMPTS);
            repository.update(meta);
            log.info("full_manifest_refresh_command_skipped mediaFileID={} inline_level={} reason=no_command_channel_standalone retry={}/{} delay_ms={}",
                mediaFileID,
                inlineLevel,
                attempt,
                MAX_MANIFEST_REFRESH_RECONNECT_ATTEMPTS,
                delayMs);
            executor.submit(() -> {
                sleepQuietly(delayMs);
                DownloadMetadata current = repository.getByMediaFileID(mediaFileID);
                if (current != null) {
                requestManifestSessionRefresh(current, inlineLevel, reasonCode);
                }
            });
            return;
            }
            manifestRefreshReconnectAttempt.remove(mediaFileID);
            markUserMetadataRefreshResult(mediaFileID, false,
                "Metadata refresh failed: server rejected standalone reauth");
            return;
        }
        manifestRefreshReconnectAttempt.remove(mediaFileID);
        if (!manifestRefreshAttempted.add(mediaFileID)) {
            log.warn("full_manifest_refresh_skipped mediaFileID={} inline_level={} reason=already_attempted",
                    mediaFileID, inlineLevel);
            return;
        }
        final String corr = ensureCorrelationId(meta);
        meta.setTransferSessionState("metadata_refresh_reauth");
        meta.setErrorMessage("Requested reauth from server; waiting for ACK");
        repository.update(meta);
        executor.submit(() -> {
            try {
                connection.postDownloadRefreshRequest(
                        mediaFileID,
                        reasonCode,
                        corr,
                        meta.getSessionToken(),
                        Math.max(meta.getDownloadedBytes(), meta.getResumeFromOffset()));
                log.info("Posted DOWNLOAD_REFRESH_REQUEST for full manifest mediaFileID={} inline_level={} reason={} corr={}",
                        mediaFileID, inlineLevel, reasonCode, corr);
                manifestRefreshReconnectAttempt.remove(mediaFileID);
                scheduleManifestRefreshAckTimeout(mediaFileID, inlineLevel, reasonCode);
            } catch (Throwable t) {
                manifestRefreshAttempted.remove(mediaFileID);
                log.warn("Failed manifest refresh request for {}: {}: {}",
                        mediaFileID, t.getClass().getName(), t.getMessage());
            }
        });
    }

    private boolean tryHttpManifestSessionRefresh(DownloadMetadata meta,
                                                  String inlineLevel,
                                                  String reasonCode) {
        if (meta == null) return false;
        ServerInfo serverInfo = resolveReconnectServer(meta, MiniclientApplication.get().getClient());
        String controlPlaneBase = serverInfo != null
            ? buildControlPlaneBase(serverInfo)
            : firstNonBlank(
                extractControlPlaneBaseFromUrl(meta.getOfflineMetadataUrl()),
                extractControlPlaneBaseFromUrl(meta.getDownloadUrl()));
        if (controlPlaneBase == null || controlPlaneBase.isEmpty()) {
            log.info("full_manifest_refresh_http_skipped mediaFileID={} inline_level={} reason=no_server_info",
                    meta.getMediaFileID(), inlineLevel);
            return false;
        }
        String corr = ensureCorrelationId(meta);
        DownloadRequest ack = postHttpTransferRefresh(controlPlaneBase, meta, corr, reasonCode);
        if (ack == null) {
            log.info("full_manifest_refresh_http_fallback mediaFileID={} inline_level={} reason={} corr={}",
                    meta.getMediaFileID(), inlineLevel, reasonCode, corr);
            return false;
        }
        log.info("full_manifest_refresh_http_ack mediaFileID={} inline_level={} reason={} corr={}",
                meta.getMediaFileID(), inlineLevel, reasonCode, corr);
        mergeWithExisting(meta, ack);
        if (userMetadataRefreshPending.contains(meta.getMediaFileID())) {
            meta.setTransferSessionState("metadata_refresh_fetching");
            meta.setErrorMessage("Reauth ACK received via HTTP; fetching manifest");
            repository.update(meta);
        }
        return true;
    }

    private DownloadRequest postHttpTransferRefresh(String controlPlaneBase,
                                                    DownloadMetadata meta,
                                                    String correlationId,
                                                    String reasonCode) {
        String[] endpoints = buildHttpRefreshEndpoints(controlPlaneBase, meta);
        String token = nullIfBlank(meta.getSessionToken());
        String clientId = firstNonBlank(getNgClientIdOrNull(), extractClientIdFromDownloadUrl(meta.getDownloadUrl()));
        for (String endpoint : endpoints) {
            if (endpoint == null || endpoint.isEmpty()) continue;
            boolean tokenSpecific = token != null
                    && endpoint.contains("/api/transfers/" + token + "/refresh");
            if (tokenSpecific) {
                DownloadRequest ack = postHttpTransferRefreshAttempt(
                        endpoint, meta, correlationId, reasonCode, clientId, true);
                if (ack != null) return ack;
                continue;
            }

            DownloadRequest ack = postHttpTransferRefreshAttempt(
                    endpoint, meta, correlationId, reasonCode, clientId, false);
            if (ack != null) return ack;

            // Standalone tolerance: some servers accept generic refresh only when
            // the prior session token is echoed in body.
            if (token != null && endpoint.contains("/api/transfers/refresh")) {
                ack = postHttpTransferRefreshAttempt(
                        endpoint, meta, correlationId, reasonCode, clientId, true);
                if (ack != null) return ack;
            }
        }
        return null;
    }

    private DownloadRequest postHttpTransferRefreshAttempt(String endpoint,
                                                           DownloadMetadata meta,
                                                           String correlationId,
                                                           String reasonCode,
                                                           String clientId,
                                                           boolean includeSessionToken) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout((int) HTTP_REFRESH_TIMEOUT_MS);
            conn.setReadTimeout((int) HTTP_REFRESH_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-Correlation-Id", correlationId);
            byte[] body = buildHttpRefreshRequestBody(meta, correlationId, reasonCode, clientId, includeSessionToken)
                    .getBytes(StandardCharsets.UTF_8);
            conn.getOutputStream().write(body);
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                String json = readHttpBody(conn.getInputStream(), MAX_MANIFEST_BYTES);
                return parseHttpTransferSessionAck(json);
            }
            log.info("HTTP transfer refresh endpoint {} returned status {} for mediaFileID={} include_session_token={}",
                    endpoint, code, meta.getMediaFileID(), includeSessionToken);
        } catch (Throwable t) {
            log.info("HTTP transfer refresh endpoint {} failed for mediaFileID={} include_session_token={}: {}",
                    endpoint, meta.getMediaFileID(), includeSessionToken, t.toString());
        }
        return null;
    }

    private static String[] buildHttpRefreshEndpoints(String controlPlaneBase, DownloadMetadata meta) {
        String token = nullIfBlank(meta.getSessionToken());
        // Rule 13-14: token-specific endpoint FIRST; generic endpoint as fallback
        // only when no session token is available.
        String tokenEndpoint = token == null ? null : controlPlaneBase + "/api/transfers/" + token + "/refresh";
        if (tokenEndpoint != null) {
            return new String[]{
                    tokenEndpoint,
                    controlPlaneBase + "/api/transfers/refresh"
            };
        }
        return new String[]{
                controlPlaneBase + "/api/transfers/refresh"
        };
    }

    private static String buildHttpRefreshRequestBody(DownloadMetadata meta,
                                                      String correlationId,
                                                      String reasonCode,
                                                      String clientId,
                                                      boolean includeSessionToken) {
        StringBuilder payload = new StringBuilder(256);
        payload.append('{')
                .append("\"recording_id\":\"").append(escapeJsonBody(meta.getMediaFileID())).append('\"')
                .append(",\"mediaFileID\":\"").append(escapeJsonBody(meta.getMediaFileID())).append('\"')
                .append(",\"correlation_id\":\"").append(escapeJsonBody(correlationId)).append('\"')
                .append(",\"reason\":\"").append(escapeJsonBody(reasonCode)).append('\"')
                .append(",\"bytes_transferred\":").append(Math.max(meta.getDownloadedBytes(), meta.getResumeFromOffset()));
        if (includeSessionToken && meta.getSessionToken() != null && !meta.getSessionToken().isEmpty()) {
            payload.append(",\"session_token\":\"").append(escapeJsonBody(meta.getSessionToken())).append('\"');
        }
        if (clientId != null && !clientId.isEmpty()) {
            payload.append(",\"ng_client_id\":\"").append(escapeJsonBody(clientId)).append('\"')
                    .append(",\"clientId\":\"").append(escapeJsonBody(clientId)).append('\"');
        }
        payload.append('}');
        return payload.toString();
    }

    private static String readHttpBody(InputStream in, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new IllegalStateException("HTTP_REFRESH_RESPONSE_TOO_LARGE");
            }
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static DownloadRequest parseHttpTransferSessionAck(String json) throws Exception {
        if (json == null || json.trim().isEmpty()) return null;
        JSONObject obj = new JSONObject(json);
        DownloadRequest req = new DownloadRequest();
        req.setMediaFileID(firstNonBlank(obj.optString("recording_id", null), obj.optString("mediaFileID", null)));
        if (req.getMediaFileID() == null || req.getMediaFileID().isEmpty()) return null;
        req.setTitle(firstNonBlank(obj.optString("title", null), obj.optString("file_name", null)));
        req.setServerPath(firstNonBlank(obj.optString("serverPath", null), obj.optString("download_path", null)));
        req.setContainer(obj.optString("container", null));
        req.setThumbnailUrl(obj.optString("thumbnailUrl", null));
        req.setFileSize(obj.optLong("fileSize", obj.optLong("total_bytes", 0)));
        req.setDuration(obj.optLong("duration", 0));
        req.setRecordingState(obj.optString("recording_state", null));
        req.setSessionToken(obj.optString("session_token", null));
        req.setDownloadUrl(firstNonBlank(obj.optString("download_url", null), obj.optString("download_path", null)));
        req.setSessionState(obj.optString("session_state", null));
        req.setAccountFamily(firstNonBlank(obj.optString("app_family", null), obj.optString("account_family", null)));
        req.setAccountUsername(obj.optString("username", null));
        req.setAccountPassword(obj.optString("password", null));
        req.setResumeFromOffset(obj.optLong("resume_from_offset", 0));
        req.setReconnectGraceSeconds(obj.optLong("reconnect_grace_seconds", 0));
        req.setExpiresInSeconds(obj.optLong("expires_in_seconds", 0));
        req.setEffectiveRateKbps(obj.optLong("effective_rate_kbps", 0));
        JSONObject requestedPolicy = obj.optJSONObject("requested_policy");
        if (requestedPolicy != null) req.setRequestedPolicyJson(requestedPolicy.toString());
        JSONObject acceptedPolicy = obj.optJSONObject("accepted_policy");
        if (acceptedPolicy != null) req.setAcceptedPolicyJson(acceptedPolicy.toString());
        JSONArray policyAdjustments = obj.optJSONArray("policy_adjustments");
        if (policyAdjustments != null) req.setPolicyAdjustmentsJson(policyAdjustments.toString());
        JSONArray reasonCodes = obj.optJSONArray("recent_reason_codes");
        if (reasonCodes != null) req.setRecentReasonCodesJson(reasonCodes.toString());
        req.setServerQueueItemId(firstNonBlank(obj.optString("server_queue_item_id", null), obj.optString("queue_item_id", null)));
        req.setQueuePriority(obj.optInt("queue_priority", obj.optInt("priority", 0)));
        req.setRequestIntent(firstNonBlank(obj.optString("request_intent", null), obj.optString("intent", null)));
        req.setSeriesSelectionMode(obj.optString("series_selection_mode", null));
        req.setEstimatedSeriesBytes(obj.optLong("estimated_series_bytes", 0));
        req.setEstimatedItemCount(obj.optInt("estimated_item_count", 0));
        JSONObject offline = obj.optJSONObject("offline");
        if (offline != null) req.setOfflineCompanionJson(offline.toString());
        req.setOfflineMetadataUrl(obj.optString("offline_metadata_url", null));
        req.setOfflineMetadataPath(obj.optString("offline_metadata_path", null));
        req.setOfflineInlineLevel(obj.optString("offline_inline_level", null));
        return req;
    }

    private static String extractClientIdFromDownloadUrl(String downloadUrl) {
        if (downloadUrl == null || downloadUrl.isEmpty()) return null;
        int idx = downloadUrl.indexOf("client=");
        if (idx < 0) return null;
        int start = idx + 7;
        int end = downloadUrl.indexOf('&', start);
        if (end < 0) end = downloadUrl.length();
        return start < end ? downloadUrl.substring(start, end) : null;
    }

    private static String escapeJsonBody(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private void scheduleManifestRefreshAckTimeout(String mediaFileID,
                                                   String inlineLevel,
                                                   String reasonCode) {
        if (mediaFileID == null || mediaFileID.isEmpty()) return;
        java.util.concurrent.ScheduledFuture<?> prior = manifestRefreshAckTimeoutTasks.remove(mediaFileID);
        if (prior != null) prior.cancel(false);
        java.util.concurrent.ScheduledFuture<?> future = manifestRetryScheduler.schedule(() -> {
            if (!manifestRefreshAttempted.contains(mediaFileID)) {
                return;
            }
            int attempt = manifestRefreshAckTimeoutAttempt
                    .compute(mediaFileID, (k, v) -> v == null ? 1 : v + 1);
            if (attempt >= MAX_MANIFEST_REFRESH_RECONNECT_ATTEMPTS) {
                manifestRefreshAttempted.remove(mediaFileID);
                manifestRefreshReconnectAttempt.remove(mediaFileID);
                manifestRefreshAckTimeoutAttempt.remove(mediaFileID);
                markUserMetadataRefreshResult(mediaFileID, false,
                        "Metadata refresh failed: no refresh response from server");
                log.warn("full_manifest_refresh_timeout mediaFileID={} inline_level={} attempts={}",
                        mediaFileID, inlineLevel, attempt);
                return;
            }
            manifestRefreshAttempted.remove(mediaFileID);
            DownloadMetadata current = repository.getByMediaFileID(mediaFileID);
            if (current != null) {
                current.setTransferSessionState("metadata_refresh_reauth");
                current.setErrorMessage("Reauth ACK timeout; retrying request "
                    + attempt + "/" + MAX_MANIFEST_REFRESH_RECONNECT_ATTEMPTS);
                repository.update(current);
                log.warn("full_manifest_refresh_retry_after_timeout mediaFileID={} inline_level={} attempt={}/{}",
                        mediaFileID,
                        inlineLevel,
                        attempt,
                        MAX_MANIFEST_REFRESH_RECONNECT_ATTEMPTS);
                requestManifestSessionRefresh(current, inlineLevel, reasonCode);
            }
        }, REFRESH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        manifestRefreshAckTimeoutTasks.put(mediaFileID, future);
    }

    private void cancelManifestRefreshAckTimeout(String mediaFileID) {
        if (mediaFileID == null || mediaFileID.isEmpty()) return;
        java.util.concurrent.ScheduledFuture<?> task = manifestRefreshAckTimeoutTasks.remove(mediaFileID);
        if (task != null) task.cancel(false);
    }

    private static MiniClientConnection getReadyCommandChannelConnection(MiniClient client) {
        if (client == null) return null;
        MiniClientConnection connection = client.getCurrentConnection();
        if (connection == null) return null;
        if (!connection.isConnected()) return null;
        if (!connection.hasEventChannel()) return null;
        return connection;
    }

    private static String extractHostFromUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) return null;
        try {
            URL u = new URL(rawUrl);
            String host = u.getHost();
            return (host == null || host.trim().isEmpty()) ? null : host.trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String extractControlPlaneBaseFromUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) return null;
        try {
            URL u = new URL(rawUrl);
            String host = u.getHost();
            if (host == null || host.trim().isEmpty()) return null;
            String protocol = u.getProtocol();
            if (protocol == null || protocol.trim().isEmpty()) {
                protocol = "http";
            }
            int port = u.getPort();
            if (port <= 0) {
                port = u.getDefaultPort();
            }
            if (port <= 0) {
                port = "https".equalsIgnoreCase(protocol) ? 443 : 31099;
            }
            return protocol + "://" + host + ":" + port;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean hostEquals(String a, String b) {
        if (a == null || b == null) return false;
        return a.trim().equalsIgnoreCase(b.trim());
    }

    private ServerInfo resolveReconnectServer(DownloadMetadata meta, MiniClient client) {
        if (client == null) return null;

        String hostFromRecording = extractHostFromUrl(meta != null ? meta.getDownloadUrl() : null);
        if (hostFromRecording == null) {
            hostFromRecording = extractHostFromUrl(meta != null ? meta.getOfflineMetadataUrl() : null);
        }

        ServerInfo connected = client.getConnectedServerInfo();
        if (hostFromRecording != null && connected != null && hostEquals(hostFromRecording, connected.address)) {
            return connected;
        }

        if (hostFromRecording != null && client.getServers() != null) {
            for (ServerInfo saved : client.getServers().getSavedServers()) {
                if (saved != null && hostEquals(hostFromRecording, saved.address)) {
                    return saved;
                }
            }
        }

        if (connected != null) return connected;
        if (client.getServers() != null) {
            return client.getServers().getLastConnectedServer();
        }
        return null;
    }

    private void tryReconnectToServer(DownloadMetadata meta) {
        try {
            MiniClient client = MiniclientApplication.get().getClient();
            if (client == null) return;
            if (getReadyCommandChannelConnection(client) != null) return;
            synchronized (manifestReconnectLock) {
                if (getReadyCommandChannelConnection(client) != null) return;
                MiniClientConnection current = client.getCurrentConnection();
                if (current != null && (!current.isConnected() || !current.hasEventChannel())) {
                    client.closeConnection();
                }
                ServerInfo serverInfo = resolveReconnectServer(meta, client);
                if (serverInfo == null) return;
                if (client.getUIRenderer() == null) {
                    client.setUIRenderer(new HeadlessBackgroundUIRenderer());
                    manifestBackgroundSessionOwned.set(true);
                }
                client.connect(serverInfo, new RandomMACAddressResolver(client.properties()));
            }
        } catch (Throwable t) {
            log.warn("Manifest refresh reconnect attempt failed: {}", t.toString());
        }
    }

    private void closeBackgroundRefreshSessionIfOwned(String reason) {
        if (!manifestBackgroundSessionOwned.get()) return;
        if (!userMetadataRefreshPending.isEmpty()) return;
        try {
            MiniClient client = MiniclientApplication.get().getClient();
            if (client != null) {
                client.closeConnection();
                client.setUIRenderer(null);
            }
            log.info("Closed background manifest refresh session: {}", reason);
        } catch (Throwable t) {
            log.warn("Failed to close background manifest refresh session: {}", t.toString());
        } finally {
            manifestBackgroundSessionOwned.set(false);
        }
    }

    private static boolean isManifestRefreshableHttpError(Exception e) {
        String reason = manifestRefreshReasonForException(e);
        return "manifest_fetch_401".equals(reason)
                || "manifest_fetch_403".equals(reason)
                || "manifest_fetch_404".equals(reason)
                || "manifest_fetch_410".equals(reason);
    }

    private static boolean isManifestControlPlaneConnectionError(Exception e) {
        if (e == null) return false;
        if (e instanceof ConnectException || e instanceof SocketTimeoutException) return true;
        String message = String.valueOf(e.getMessage());
        return message.contains("failed to connect")
                || message.contains("Connection refused")
                || message.contains("connect timed out")
                || message.contains("timeout");
    }

    private static String manifestRefreshReasonForException(Exception e) {
        String msg = e == null ? "" : String.valueOf(e.getMessage());
        if (msg.contains("HTTP_401")) return "manifest_fetch_401";
        if (msg.contains("HTTP_403")) return "manifest_fetch_403";
        if (msg.contains("HTTP_404")) return "manifest_fetch_404";
        if (msg.contains("HTTP_410")) return "manifest_fetch_410";
        return "manifest_fetch_error";
    }

    private static boolean shouldRequestSessionRefreshForTransferError(String errorCode) {
        String code = normalizeErrorCode(errorCode);
        if (code.isEmpty()) return false;
        return "TRANSFER_SESSION_NOT_FOUND".equals(code)
                || "TRANSFER_TOKEN_INVALID".equals(code)
                || "TOKEN_INVALID".equals(code)
                || "SESSION_EXPIRED".equals(code)
                || "CODE_SESSION_EXPIRED".equals(code)
                || "CODE_TOKEN_INVALID".equals(code);
    }

    private static String normalizeErrorCode(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        if (normalized.isEmpty()) return "";
        return normalized.toUpperCase(Locale.US);
    }

    private static String normalizeRefreshReasonToken(String value) {
        String normalized = normalizeErrorCode(value);
        if (normalized.isEmpty()) return "UNKNOWN";
        StringBuilder sb = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    private static String resolveUrl(String base, String raw) {
        String value = nullIfBlank(raw);
        if (value == null) throw new IllegalArgumentException("manifest pointer missing");
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        if (base == null || base.trim().isEmpty()) {
            throw new IllegalStateException("CONTROL_PLANE_BASE_MISSING");
        }
        String b = base.trim();
        if (b.endsWith("/") && value.startsWith("/")) return b.substring(0, b.length() - 1) + value;
        if (!b.endsWith("/") && !value.startsWith("/")) return b + "/" + value;
        return b + value;
    }

    private static String normalizeInlineLevel(String raw) {
        String value = nullIfBlank(raw);
        if (value == null) return null;
        String normalized = value.toLowerCase(Locale.US);
        if ("core".equals(normalized) || "full".equals(normalized)) {
            return normalized;
        }
        return normalized;
    }

    private static String firstNonBlank(String first, String second) {
        String a = nullIfBlank(first);
        if (a != null) return a;
        return nullIfBlank(second);
    }

    private static String nullIfBlank(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void deleteCompanionArtifacts(DownloadMetadata meta) {
        if (meta == null) return;
        String dirPath = meta.getCompanionDirPath();
        if (dirPath == null || dirPath.trim().isEmpty()) return;
        try {
            deleteRecursively(new File(dirPath));
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    public void moveUp(String mediaFileID) {
        adjustPriority(mediaFileID, 1);
    }

    public void moveDown(String mediaFileID) {
        adjustPriority(mediaFileID, -1);
    }

    public void setPriority(String mediaFileID, int priority) {
        DownloadMetadata meta = repository.getByMediaFileID(mediaFileID);
        if (meta == null) return;
        meta.setQueuePriority(priority);
        repository.update(meta);
    }

    private void adjustPriority(String mediaFileID, int delta) {
        DownloadMetadata meta = repository.getByMediaFileID(mediaFileID);
        if (meta == null) return;
        meta.setQueuePriority(meta.getQueuePriority() + delta);
        repository.update(meta);
    }

    /**
     * COMPLETE-item reauth ACK handler for the unified refresh flow.
     *
     * Updates session token/download URL/metadata pointer from fresh ACK and
     * re-enters the same full manifest + selected sidecar refresh path as a
     * user-initiated metadata/artwork refresh.
     */
    private void refreshSessionAndRejoinFullRefreshFlow(DownloadMetadata existing, DownloadRequest request) {
        autoRefreshAttempted.remove(existing.getMediaFileID());
        manifestRefreshAttempted.remove(existing.getMediaFileID());
        cancelManifestRefreshAckTimeout(existing.getMediaFileID());
        manifestRefreshAckTimeoutAttempt.remove(existing.getMediaFileID());
        cancelPersistentManifestRetry(existing.getMediaFileID());
        manifestPersistentRetryAttempt.remove(existing.getMediaFileID());
        if (request.getSessionToken() != null && !request.getSessionToken().isEmpty()) {
            existing.setSessionToken(request.getSessionToken());
        }
        if (request.getDownloadUrl() != null && !request.getDownloadUrl().isEmpty()) {
            existing.setDownloadUrl(request.getDownloadUrl());
        }
        if (request.getServerPath() != null && !request.getServerPath().isEmpty()) {
            existing.setServerPath(request.getServerPath());
        }
        existing.setOfflineMetadataUrl(firstNonBlank(
                request.getOfflineMetadataUrl(), existing.getOfflineMetadataUrl()));
        existing.setOfflineMetadataPath(firstNonBlank(
                request.getOfflineMetadataPath(), existing.getOfflineMetadataPath()));
        existing.setOfflineInlineLevel(normalizeInlineLevel(firstNonBlank(
                request.getOfflineInlineLevel(), existing.getOfflineInlineLevel())));
        if (request.getExpiresInSeconds() > 0) {
            existing.setExpiresInSeconds(request.getExpiresInSeconds());
        }
        if (userMetadataRefreshPending.contains(existing.getMediaFileID())) {
            existing.setTransferSessionState("metadata_refresh_fetching");
            existing.setErrorMessage("Reauth ACK received; fetching manifest and artwork");
        }
        repository.update(existing);
        maybeFetchFullManifest(existing);
    }

    private void mergeWithExisting(DownloadMetadata existing, DownloadRequest request) {
        // A fresh CMD_DOWNLOAD_REQUEST for this mediaFileID means the server
        // has issued (or re-issued) a session token. Clear the auto-refresh
        // throttle so a future stale-token event can request another refresh.
        autoRefreshAttempted.remove(existing.getMediaFileID());
        manifestRefreshAttempted.remove(existing.getMediaFileID());
        manifestRefreshReconnectAttempt.remove(existing.getMediaFileID());
        cancelManifestRefreshAckTimeout(existing.getMediaFileID());
        manifestRefreshAckTimeoutAttempt.remove(existing.getMediaFileID());
        // A fresh ACK supersedes any pending persistent retry — reset the
        // backoff schedule so we retry promptly with the (potentially new)
        // pointer/session token.
        cancelPersistentManifestRetry(existing.getMediaFileID());
        manifestPersistentRetryAttempt.remove(existing.getMediaFileID());
        existing.setMergedRequestCount(existing.getMergedRequestCount() + 1);
        existing.setQueuePriority(Math.max(existing.getQueuePriority(), request.getQueuePriority()));
        if (request.getServerQueueItemId() != null && !request.getServerQueueItemId().isEmpty()) {
            existing.setServerQueueItemId(request.getServerQueueItemId());
        }
        if (request.getSessionToken() != null && !request.getSessionToken().isEmpty()) {
            existing.setSessionToken(request.getSessionToken());
        }
        if (request.getDownloadUrl() != null && !request.getDownloadUrl().isEmpty()) {
            existing.setDownloadUrl(request.getDownloadUrl());
        }
        if (request.getAccountFamily() != null && !request.getAccountFamily().trim().isEmpty()) {
            existing.setAccountFamily(normalizeAccountFamily(request.getAccountFamily()));
        }
        if (request.getAccountUsername() != null && !request.getAccountUsername().trim().isEmpty()) {
            existing.setAccountUsername(request.getAccountUsername().trim());
        }
        if (request.getReconnectGraceSeconds() > 0) {
            existing.setReconnectGraceSeconds(request.getReconnectGraceSeconds());
        }
        if (request.getExpiresInSeconds() > 0) {
            existing.setExpiresInSeconds(request.getExpiresInSeconds());
        }
        if (request.getEffectiveRateKbps() > 0) {
            existing.setEffectiveRateKbps(request.getEffectiveRateKbps());
        }
        existing.setRequestedPolicyJson(normalizeRequestedPolicy(firstNonBlank(
                request.getRequestedPolicyJson(), existing.getRequestedPolicyJson())));
        existing.setAcceptedPolicyJson(firstNonBlank(
                request.getAcceptedPolicyJson(), existing.getAcceptedPolicyJson()));
        existing.setPolicyAdjustmentsJson(firstNonBlank(
                request.getPolicyAdjustmentsJson(), existing.getPolicyAdjustmentsJson()));
        existing.setRecentReasonCodesJson(firstNonBlank(
                request.getRecentReasonCodesJson(), existing.getRecentReasonCodesJson()));
        // Rule 16: replace URL/path/level from fresh ACK; only fall back to
        // existing when server sends nothing (null/blank).
        if (nullIfBlank(request.getOfflineMetadataUrl()) != null) {
            existing.setOfflineMetadataUrl(request.getOfflineMetadataUrl().trim());
        }
        if (nullIfBlank(request.getOfflineMetadataPath()) != null) {
            existing.setOfflineMetadataPath(request.getOfflineMetadataPath().trim());
        }
        if (nullIfBlank(request.getOfflineInlineLevel()) != null) {
            existing.setOfflineInlineLevel(normalizeInlineLevel(request.getOfflineInlineLevel()));
        }
        if (request.getFileSize() > 0) {
            existing.setFileSize(request.getFileSize());
        }
        existing.setResumeFromOffset(Math.max(0, request.getResumeFromOffset()));
        existing.setDownloadedBytes(Math.max(existing.getDownloadedBytes(), existing.getResumeFromOffset()));
        String normalizedState = normalizeSessionState(request.getSessionState(), existing.getTransferSessionState());
        if (normalizedState != null && !normalizedState.isEmpty()) {
            existing.setTransferSessionState(normalizedState);
        }
        if (existing.getStatus() == DownloadMetadata.Status.PAUSED
                || existing.getStatus() == DownloadMetadata.Status.FAILED
                || existing.getStatus() == DownloadMetadata.Status.QUEUED) {
            applySessionStateStatus(existing, normalizeSessionState(existing.getTransferSessionState(), "queued"));
            if (existing.getStatus() == DownloadMetadata.Status.QUEUED) {
                existing.setErrorMessage(null);
            }
        }
        // Rule 6: replace whole companion sections when server sends fresh data.
        // Never sticky-merge stale sections into a fresh ACK.
        // Servers may omit "offline" on some refresh responses (e.g. session-
        // only refresh); keep existing sections only in that case.
        if (request.getOfflineCompanionJson() != null
                && !request.getOfflineCompanionJson().isEmpty()) {
            // Full replace: apply clears and rewrites all sections from the
            // fresh manifest, overwriting any cached stale data.
            existing.setOfflineMetadataJson(null);
            existing.setArtworkManifestJson(null);
            existing.setCaptionsManifestJson(null);
            existing.setComskipManifestJson(null);
            existing.setTranscriptManifestJson(null);
            OfflineCompanionStore.apply(context, existing, request.getOfflineCompanionJson());
        }
        if (userMetadataRefreshPending.contains(existing.getMediaFileID())) {
            existing.setTransferSessionState("metadata_refresh_fetching");
            existing.setErrorMessage("Metadata refresh fetching latest manifest");
        }
        repository.update(existing);
        maybeFetchFullManifest(existing);
        startServiceIfNeeded();
        if (isTransferReady(existing)) {
            processNext();
        }
    }

    private DownloadMetadata resolveMetadata(String sessionToken, String mediaFileID) {
        DownloadMetadata meta = repository.getBySessionToken(sessionToken);
        if (meta != null) {
            return meta;
        }
        if (mediaFileID != null && !mediaFileID.trim().isEmpty()) {
            return repository.getByMediaFileID(mediaFileID.trim());
        }
        return null;
    }

    private static void applySessionStateStatus(DownloadMetadata meta, String normalizedState) {
        if (normalizedState == null) {
            meta.setStatus(DownloadMetadata.Status.QUEUED);
            return;
        }
        if (normalizedState.startsWith("paused")) {
            meta.setStatus(DownloadMetadata.Status.PAUSED);
            return;
        }
        if (normalizedState.startsWith("error")) {
            meta.setStatus(DownloadMetadata.Status.FAILED);
            return;
        }
        meta.setStatus(DownloadMetadata.Status.QUEUED);
    }

    private static boolean isTransferReady(DownloadMetadata meta) {
        if (meta == null) return false;
        String state = normalizeSessionState(meta.getTransferSessionState(), "queued");
        if (state == null || state.isEmpty()) return true;
        if (state.startsWith("transferring")) return true;
        if (state.startsWith("retry")) return true;
        if (state.startsWith("queued")) return true;
        if (state.startsWith("preparing")) return false;
        if (state.startsWith("awaiting_refresh")) return false;
        if (state.startsWith("paused")) return false;
        if (state.startsWith("error")) return false;
        return true;
    }

    private static boolean shouldPromoteQueuedToRetry(DownloadMetadata meta) {
        if (meta == null) return false;
        if (meta.getDownloadedBytes() > 0) return true;
        if (meta.getResumeFromOffset() > 0) return true;
        return meta.getRetryCount() > 0;
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
        } else if (DownloadTask.ERROR_TRANSFER_NOT_FOUND.equals(code)
                || DownloadTask.ERROR_TRANSFER_GONE.equals(code)
                || "HTTP_404".equals(code) || "HTTP_410".equals(code)) {
            code = "SESSION_EXPIRED|Restart download from the SageTV server menu";
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

    /**
     * Re-evaluate the queue after the user toggles the "Wi-Fi only"
     * preference. If the running download violates the new policy, pause it
     * and re-queue with {@code waiting_for_wifi}. Either way, kick
     * processNext so queued items can be promoted or demoted appropriately.
     */
    public void onWifiOnlyPrefChanged() {
        executor.submit(() -> {
            try {
                DownloadMetadata running = currentMediaFileID == null
                        ? null
                        : repository.getByMediaFileID(currentMediaFileID);
                if (running != null && !canRunUnderCurrentNetworkPolicy(running)) {
                    if (currentTask != null) {
                        currentTask.pause();
                    }
                    running.setStatus(DownloadMetadata.Status.QUEUED);
                    running.setTransferSessionState("waiting_for_wifi");
                    running.setErrorMessage("WIFI_REQUIRED");
                    repository.update(running);
                }
            } catch (Throwable t) {
                log.warn("onWifiOnlyPrefChanged failed: {}", t.toString());
            }
            processNext();
        });
    }

    /**
     * Register a connectivity callback that re-runs processNext() whenever
     * Wi-Fi or Ethernet becomes available. This is what makes
     * {@code waiting_for_wifi} items auto-resume when the device comes back
     * onto an unmetered network — no user action required.
     */
    private void ensureNetworkWatcher() {
        if (networkCallback != null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .build();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    log.info("Unmetered network available — re-evaluating download queue");
                    startServiceIfNeeded();
                    processNext();
                }
            };
            cm.registerNetworkCallback(request, networkCallback);
        } catch (Throwable t) {
            log.warn("Failed to register network callback for Wi-Fi auto-resume: {}", t.toString());
            networkCallback = null;
        }
    }

    private boolean canRunUnderCurrentNetworkPolicy(DownloadMetadata meta) {
        String policy = meta.getAcceptedPolicyJson();
        if (policy == null || policy.isEmpty()) {
            policy = meta.getRequestedPolicyJson();
        }
        boolean wifiOnly = extractJsonBoolean(policy, "wifi_only", true);
        boolean allowMetered = extractJsonBoolean(policy, "allow_metered", false);
        // User preference overrides the server policy: if the user has the
        // "Wi-Fi only" checkbox enabled, we force wifi-only and disallow
        // metered regardless of what the server requested. The user's choice
        // is more authoritative than the server's default for this client.
        if (storageHelper.isWifiOnlyDownloads()) {
            wifiOnly = true;
            allowMetered = false;
        }
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

    private void clearProgressTracking(String mediaFileID) {
        if (mediaFileID == null || mediaFileID.isEmpty()) return;
        progressLastPersistTimestampMs.remove(mediaFileID);
        progressLastPersistBytes.remove(mediaFileID);
    }
}
