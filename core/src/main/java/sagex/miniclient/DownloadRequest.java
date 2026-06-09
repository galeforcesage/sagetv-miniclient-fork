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
package sagex.miniclient;

/**
 * Represents a media download transfer-session contract received from the
 * SageTV server. Parsed from CMD_DOWNLOAD_REQUEST payloads where
 * type=TRANSFER_SESSION_ACK.
 */
public class DownloadRequest {
    private String mediaFileID;
    private String title;
    private String serverPath;
    private long fileSize;
    private long duration;
    private String thumbnailUrl;
    private String container;
    private String recordingState;

    // Transfer contract session metadata
    private String sessionToken;
    private String downloadUrl;
    private String sessionState;
    private String accountFamily;
    private String accountUsername;
    private String accountPassword;
    private long resumeFromOffset;
    private long reconnectGraceSeconds;
    private long expiresInSeconds;
    private long effectiveRateKbps;

    // Policy contract payloads are stored as raw JSON for portability.
    private String requestedPolicyJson;
    private String acceptedPolicyJson;
    private String policyAdjustmentsJson;
    private String recentReasonCodesJson;
    private String serverQueueItemId;
    private int queuePriority;
    private String requestIntent;
    private String seriesSelectionMode;
    private long estimatedSeriesBytes;
    private int estimatedItemCount;

    // Optional offline-companion content payload (raw JSON of the server's
    // "offline" object inside CMD_DOWNLOAD_REQUEST). Includes rich metadata
    // (media_file / airing / show), artwork manifest, captions, comskip and
    // transcript sidecar references. Persisted verbatim by the client so
    // future server fields survive without a client update. May be null when
    // the connected server does not advertise the OFFLINE_* capabilities.
    private String offlineCompanionJson;
    // Two-step offline manifest delivery pointers. Inline offline content is
    // first-paint data; when URL/path is present client should fetch full
    // manifest and replace snapshot content.
    private String offlineMetadataUrl;
    private String offlineMetadataPath;
    private String offlineInlineLevel;

    public DownloadRequest() {
    }

    public DownloadRequest(String mediaFileID, String title, String serverPath,
                           long fileSize, long duration, String thumbnailUrl, String container) {
        this.mediaFileID = mediaFileID;
        this.title = title;
        this.serverPath = serverPath;
        this.fileSize = fileSize;
        this.duration = duration;
        this.thumbnailUrl = thumbnailUrl;
        this.container = container;
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

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public void setThumbnailUrl(String thumbnailUrl) {
        this.thumbnailUrl = thumbnailUrl;
    }

    public String getContainer() {
        return container;
    }

    public void setContainer(String container) {
        this.container = container;
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

    public String getSessionState() {
        return sessionState;
    }

    public void setSessionState(String sessionState) {
        this.sessionState = sessionState;
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

    public String getAccountPassword() {
        return accountPassword;
    }

    public void setAccountPassword(String accountPassword) {
        this.accountPassword = accountPassword;
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

    public String getRequestIntent() {
        return requestIntent;
    }

    public void setRequestIntent(String requestIntent) {
        this.requestIntent = requestIntent;
    }

    public String getSeriesSelectionMode() {
        return seriesSelectionMode;
    }

    public void setSeriesSelectionMode(String seriesSelectionMode) {
        this.seriesSelectionMode = seriesSelectionMode;
    }

    public long getEstimatedSeriesBytes() {
        return estimatedSeriesBytes;
    }

    public void setEstimatedSeriesBytes(long estimatedSeriesBytes) {
        this.estimatedSeriesBytes = estimatedSeriesBytes;
    }

    public int getEstimatedItemCount() {
        return estimatedItemCount;
    }

    public void setEstimatedItemCount(int estimatedItemCount) {
        this.estimatedItemCount = estimatedItemCount;
    }

    /**
     * Raw JSON of the server's "offline" object inside CMD_DOWNLOAD_REQUEST.
     * Null when the server did not include companion content.
     */
    public String getOfflineCompanionJson() {
        return offlineCompanionJson;
    }

    public void setOfflineCompanionJson(String offlineCompanionJson) {
        this.offlineCompanionJson = offlineCompanionJson;
    }

    public String getOfflineMetadataUrl() {
        return offlineMetadataUrl;
    }

    public void setOfflineMetadataUrl(String offlineMetadataUrl) {
        this.offlineMetadataUrl = offlineMetadataUrl;
    }

    public String getOfflineMetadataPath() {
        return offlineMetadataPath;
    }

    public void setOfflineMetadataPath(String offlineMetadataPath) {
        this.offlineMetadataPath = offlineMetadataPath;
    }

    public String getOfflineInlineLevel() {
        return offlineInlineLevel;
    }

    public void setOfflineInlineLevel(String offlineInlineLevel) {
        this.offlineInlineLevel = offlineInlineLevel;
    }

    @Override
    public String toString() {
        return "DownloadRequest{" +
                "mediaFileID='" + mediaFileID + '\'' +
                ", title='" + title + '\'' +
                ", serverPath='" + serverPath + '\'' +
                ", fileSize=" + fileSize +
                ", duration=" + duration +
                ", container='" + container + '\'' +
                ", serverQueueItemId='" + serverQueueItemId + '\'' +
                ", queuePriority=" + queuePriority +
                ", requestIntent='" + requestIntent + '\'' +
                ", offlineInlineLevel='" + offlineInlineLevel + '\'' +
                ", sessionToken='" + redact(sessionToken) + '\'' +
                ", sessionState='" + sessionState + '\'' +
                ", accountFamily='" + accountFamily + '\'' +
                '}';
    }

    private static String redact(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.length() <= 8) return "***";
        return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
    }
}
