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

/**
 * Metadata for a single downloaded media file. Persisted as part of the
 * downloads.json repository.
 */
public class DownloadMetadata {
    public enum Status {
        QUEUED,
        PREPARING,
        DOWNLOADING,
        PAUSED,
        COMPLETE,
        FAILED
    }

    private String mediaFileID;
    private String title;
    private String serverPath;
    private String localUri;
    private String container;
    private long fileSize;
    private long downloadedBytes;
    private long duration;
    private long addedTimestamp;
    private String thumbnailUrl;
    private Status status;
    private String errorMessage;
    private int retryCount;

    // Transfer contract state persisted for durable resume.
    private String recordingState;
    private String sessionToken;
    private String downloadUrl;
    private String transferSessionState;
    private String accountFamily;
    private String accountUsername;
    private String correlationId;
    private String sessionId;
    private int authFailoverCount;
    private boolean accountPoolExhausted;
    private long resumeFromOffset;
    private long reconnectGraceSeconds;
    private long expiresInSeconds;
    private long effectiveRateKbps;
    private String requestedPolicyJson;
    private String acceptedPolicyJson;
    private String policyAdjustmentsJson;
    private String recentReasonCodesJson;
    private String serverQueueItemId;
    private int queuePriority = 0;
    private int mergedRequestCount = 1;
    private long downloadSpeedBytesPerSec;
    private long etaSeconds;
    private long lastProgressTimestampMs;
    private boolean invalidRangeRetried;

    public DownloadMetadata() {
        this.status = Status.QUEUED;
        this.addedTimestamp = System.currentTimeMillis();
    }

    public String getMediaFileID() {
        return mediaFileID;
    }

    public void setMediaFileID(String mediaFileID) {
        this.mediaFileID = mediaFileID;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getServerPath() {
        return serverPath;
    }

    public void setServerPath(String serverPath) {
        this.serverPath = serverPath;
    }

    public String getLocalUri() {
        return localUri;
    }

    public void setLocalUri(String localUri) {
        this.localUri = localUri;
    }

    public String getContainer() {
        return container;
    }

    public void setContainer(String container) {
        this.container = container;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public long getDownloadedBytes() {
        return downloadedBytes;
    }

    public void setDownloadedBytes(long downloadedBytes) {
        this.downloadedBytes = downloadedBytes;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public long getAddedTimestamp() {
        return addedTimestamp;
    }

    public void setAddedTimestamp(long addedTimestamp) {
        this.addedTimestamp = addedTimestamp;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public void setThumbnailUrl(String thumbnailUrl) {
        this.thumbnailUrl = thumbnailUrl;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public String getRecordingState() {
        return recordingState;
    }

    public void setRecordingState(String recordingState) {
        this.recordingState = recordingState;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public void setSessionToken(String sessionToken) {
        this.sessionToken = sessionToken;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }

    public String getTransferSessionState() {
        return transferSessionState;
    }

    public void setTransferSessionState(String transferSessionState) {
        this.transferSessionState = transferSessionState;
    }

    public String getAccountFamily() {
        return accountFamily;
    }

    public void setAccountFamily(String accountFamily) {
        this.accountFamily = accountFamily;
    }

    public String getAccountUsername() {
        return accountUsername;
    }

    public void setAccountUsername(String accountUsername) {
        this.accountUsername = accountUsername;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public int getAuthFailoverCount() {
        return authFailoverCount;
    }

    public void setAuthFailoverCount(int authFailoverCount) {
        this.authFailoverCount = authFailoverCount;
    }

    public boolean isAccountPoolExhausted() {
        return accountPoolExhausted;
    }

    public void setAccountPoolExhausted(boolean accountPoolExhausted) {
        this.accountPoolExhausted = accountPoolExhausted;
    }

    public long getResumeFromOffset() {
        return resumeFromOffset;
    }

    public void setResumeFromOffset(long resumeFromOffset) {
        this.resumeFromOffset = resumeFromOffset;
    }

    public long getReconnectGraceSeconds() {
        return reconnectGraceSeconds;
    }

    public void setReconnectGraceSeconds(long reconnectGraceSeconds) {
        this.reconnectGraceSeconds = reconnectGraceSeconds;
    }

    public long getExpiresInSeconds() {
        return expiresInSeconds;
    }

    public void setExpiresInSeconds(long expiresInSeconds) {
        this.expiresInSeconds = expiresInSeconds;
    }

    public long getEffectiveRateKbps() {
        return effectiveRateKbps;
    }

    public void setEffectiveRateKbps(long effectiveRateKbps) {
        this.effectiveRateKbps = effectiveRateKbps;
    }

    public String getRequestedPolicyJson() {
        return requestedPolicyJson;
    }

    public void setRequestedPolicyJson(String requestedPolicyJson) {
        this.requestedPolicyJson = requestedPolicyJson;
    }

    public String getAcceptedPolicyJson() {
        return acceptedPolicyJson;
    }

    public void setAcceptedPolicyJson(String acceptedPolicyJson) {
        this.acceptedPolicyJson = acceptedPolicyJson;
    }

    public String getPolicyAdjustmentsJson() {
        return policyAdjustmentsJson;
    }

    public void setPolicyAdjustmentsJson(String policyAdjustmentsJson) {
        this.policyAdjustmentsJson = policyAdjustmentsJson;
    }

    public String getRecentReasonCodesJson() {
        return recentReasonCodesJson;
    }

    public void setRecentReasonCodesJson(String recentReasonCodesJson) {
        this.recentReasonCodesJson = recentReasonCodesJson;
    }

    public String getServerQueueItemId() {
        return serverQueueItemId;
    }

    public void setServerQueueItemId(String serverQueueItemId) {
        this.serverQueueItemId = serverQueueItemId;
    }

    public int getQueuePriority() {
        return queuePriority;
    }

    public void setQueuePriority(int queuePriority) {
        this.queuePriority = queuePriority;
    }

    public int getMergedRequestCount() {
        return mergedRequestCount;
    }

    public void setMergedRequestCount(int mergedRequestCount) {
        this.mergedRequestCount = mergedRequestCount;
    }

    public long getDownloadSpeedBytesPerSec() {
        return downloadSpeedBytesPerSec;
    }

    public void setDownloadSpeedBytesPerSec(long downloadSpeedBytesPerSec) {
        this.downloadSpeedBytesPerSec = downloadSpeedBytesPerSec;
    }

    public long getEtaSeconds() {
        return etaSeconds;
    }

    public void setEtaSeconds(long etaSeconds) {
        this.etaSeconds = etaSeconds;
    }

    public long getLastProgressTimestampMs() {
        return lastProgressTimestampMs;
    }

    public void setLastProgressTimestampMs(long lastProgressTimestampMs) {
        this.lastProgressTimestampMs = lastProgressTimestampMs;
    }

    public boolean isInvalidRangeRetried() {
        return invalidRangeRetried;
    }

    public void setInvalidRangeRetried(boolean invalidRangeRetried) {
        this.invalidRangeRetried = invalidRangeRetried;
    }

    public String getEffectiveSessionState() {
        if (transferSessionState != null && !transferSessionState.isEmpty()) {
            return transferSessionState;
        }
        switch (status) {
            case PREPARING:
                return "preparing";
            case DOWNLOADING:
                return "transferring";
            case PAUSED:
                return "paused_by_client";
            case COMPLETE:
                return "completed";
            case FAILED:
                return "error";
            case QUEUED:
            default:
                return "queued";
        }
    }

    public int getProgressPercent() {
        if (fileSize <= 0) return 0;
        return (int) ((downloadedBytes * 100) / fileSize);
    }

    public boolean isResumable() {
        return status == Status.PAUSED || status == Status.FAILED;
    }
}
