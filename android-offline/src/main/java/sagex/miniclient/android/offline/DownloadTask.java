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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performs a single file download from the SageTV NG transfer endpoint.
 * Supports resumable transfers via HTTP Range requests.
 */
public class DownloadTask {
    private static final Logger log = LoggerFactory.getLogger(DownloadTask.class);
    private static final int CHUNK_SIZE = 16384; // 16KB, matching server protocol
    private static final int CONNECT_TIMEOUT_MS = 30000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int PROGRESS_INTERVAL_BYTES = 256 * 1024; // report every 256KB

    public static final String ERROR_PAUSED_BY_SERVER = "PAUSED_BY_SERVER";
    public static final String ERROR_INVALID_RANGE = "INVALID_RANGE";
    public static final String ERROR_SESSION_EXPIRED = "SESSION_EXPIRED";
    public static final String ERROR_AUTH_INVALID_CREDENTIALS = "AUTH_INVALID_CREDENTIALS";
    public static final String ERROR_AUTH_REVOKED = "AUTH_REVOKED";
    public static final String ERROR_SERVER_UNAVAILABLE = "SERVER_UNAVAILABLE";
    public static final String ERROR_TRANSFER_NOT_FOUND = "TRANSFER_NOT_FOUND";
    public static final String ERROR_TRANSFER_GONE = "TRANSFER_GONE";

    public static final class Result {
        private boolean success;
        private String errorCode;
        private int httpStatus;
        private long finalOffset;
        private String refreshedSessionToken;
        private String refreshedDownloadUrl;
        private String refreshedSessionState;
        private String serverSessionId;

        public boolean isSuccess() { return success; }
        public String getErrorCode() { return errorCode; }
        public int getHttpStatus() { return httpStatus; }
        public long getFinalOffset() { return finalOffset; }
        public String getRefreshedSessionToken() { return refreshedSessionToken; }
        public String getRefreshedDownloadUrl() { return refreshedDownloadUrl; }
        public String getRefreshedSessionState() { return refreshedSessionState; }
        public String getServerSessionId() { return serverSessionId; }
    }

    public interface ProgressListener {
        void onProgress(String mediaFileID, long downloadedBytes, long totalBytes);
    }

    private final String serverAddress;
    private final int serverPort;
    private final long fileSize;
    private final long startOffset;
    private final String mediaFileID;
    private final StorageHelper storageHelper;
    private final String outputUri;
    private final String controlPlaneBase;
    private final String downloadUrl;
    private final String sessionToken;
    private final String accountUsername;
    private final String accountPassword;
    private final String correlationId;
    private final ProgressListener listener;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicLong bytesDownloaded = new AtomicLong(0);

    public DownloadTask(Context context, String serverAddress, int serverPort,
                        long fileSize, long startOffset, String mediaFileID,
                        StorageHelper storageHelper, String outputUri,
                        String controlPlaneBase, String downloadUrl, String sessionToken,
                        String accountUsername, String accountPassword, String correlationId,
                        ProgressListener listener) {
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
        this.fileSize = fileSize;
        this.startOffset = startOffset;
        this.mediaFileID = mediaFileID;
        this.storageHelper = storageHelper;
        this.outputUri = outputUri;
        this.controlPlaneBase = controlPlaneBase;
        this.downloadUrl = downloadUrl;
        this.sessionToken = sessionToken;
        this.accountUsername = accountUsername;
        this.accountPassword = accountPassword;
        this.correlationId = correlationId;
        this.listener = listener;
    }

    /**
     * Execute the download. This method blocks until complete, cancelled, or error.
     * Must be called on a background thread.
     *
     * @return the number of bytes downloaded in this session (may be less than fileSize if resumed)
     */
    public Result execute() {
        Result result = new Result();
        if (downloadUrl == null || downloadUrl.trim().isEmpty()) {
            result.errorCode = "TRANSFER_URL_MISSING";
            log.error("{} for mediaFileID={} correlationId={}", result.errorCode, mediaFileID, correlationId);
            return result;
        }
        return executeHttpTransfer();
    }

    private Result executeHttpTransfer() {
        Result result = new Result();
        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream localOut = null;

        try {
            URL resolvedUrl = new URL(resolveDownloadUrl(controlPlaneBase, downloadUrl));
            conn = (HttpURLConnection) resolvedUrl.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Connection", "Keep-Alive");
            conn.setRequestProperty("X-Correlation-ID", correlationId);
            if (sessionToken != null && !sessionToken.isEmpty()) {
                conn.setRequestProperty("X-Transfer-Token", sessionToken);
            }
            if (accountUsername != null && !accountUsername.isEmpty()
                    && accountPassword != null && !accountPassword.isEmpty()) {
                String basic = accountUsername + ":" + accountPassword;
                String encoded = Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8));
                conn.setRequestProperty("Authorization", "Basic " + encoded);
            }
            if (startOffset > 0) {
                conn.setRequestProperty("Range", "bytes=" + startOffset + "-");
            }

            int code = conn.getResponseCode();
            result.httpStatus = code;
            result.refreshedSessionToken = firstNonBlank(
                    conn.getHeaderField("X-Transfer-Token"),
                    conn.getHeaderField("X-Session-Token"));
            result.refreshedDownloadUrl = firstNonBlank(
                    conn.getHeaderField("X-Download-Url"),
                    conn.getHeaderField("X-Transfer-Url"));
            result.refreshedSessionState = conn.getHeaderField("X-Session-State");
            result.serverSessionId = firstNonBlank(
                    conn.getHeaderField("X-Session-Id"),
                    conn.getHeaderField("X-Request-Id"));

            if (code == HttpURLConnection.HTTP_CONFLICT) {
                result.errorCode = ERROR_PAUSED_BY_SERVER;
                return result;
            }
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                result.errorCode = ERROR_TRANSFER_NOT_FOUND;
                return result;
            }
            if (code == HttpURLConnection.HTTP_GONE) {
                result.errorCode = ERROR_TRANSFER_GONE;
                return result;
            }
            if (code == 416) {
                result.errorCode = ERROR_INVALID_RANGE;
                return result;
            }
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                result.errorCode = ERROR_AUTH_INVALID_CREDENTIALS;
                return result;
            }
            if (code == HttpURLConnection.HTTP_FORBIDDEN) {
                result.errorCode = ERROR_AUTH_REVOKED;
                return result;
            }
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                if (code >= 500) {
                    result.errorCode = ERROR_SERVER_UNAVAILABLE;
                } else {
                    result.errorCode = "HTTP_" + code;
                }
                return result;
            }

            long responseOffset = 0;
            long total = fileSize;
            boolean append = startOffset > 0;

            String contentRange = conn.getHeaderField("Content-Range");
            if (contentRange != null) {
                responseOffset = parseStartFromContentRange(contentRange);
                long parsedTotal = parseTotalFromContentRange(contentRange);
                if (parsedTotal > 0) total = parsedTotal;
            }

            if (code == HttpURLConnection.HTTP_OK) {
                append = false;
                responseOffset = 0;
            } else if (code == HttpURLConnection.HTTP_PARTIAL && responseOffset <= 0) {
                responseOffset = startOffset;
            }

            in = new BufferedInputStream(conn.getInputStream(), CHUNK_SIZE);
            localOut = storageHelper.openOutputStream(outputUri, append);

            byte[] buffer = new byte[CHUNK_SIZE];
            long downloaded = 0;
            long lastProgressReport = 0;
            while (true) {
                if (cancelled.get()) {
                    log.info("Download cancelled: {}", mediaFileID);
                    break;
                }
                if (paused.get()) {
                    log.info("Download paused: {} at {} bytes", mediaFileID, responseOffset + downloaded);
                    break;
                }
                int bytesRead = in.read(buffer);
                if (bytesRead < 0) {
                    break;
                }
                localOut.write(buffer, 0, bytesRead);
                downloaded += bytesRead;
                bytesDownloaded.set(responseOffset + downloaded);

                if (downloaded - lastProgressReport >= PROGRESS_INTERVAL_BYTES) {
                    lastProgressReport = downloaded;
                    if (listener != null) {
                        listener.onProgress(mediaFileID, responseOffset + downloaded, total);
                    }
                }
            }

            localOut.flush();
            localOut.close();
            localOut = null;

            if (!cancelled.get() && !paused.get()) {
                long finalBytes = responseOffset + downloaded;
                result.success = true;
                result.finalOffset = finalBytes;
                if (listener != null) {
                    listener.onProgress(mediaFileID, finalBytes, total);
                }
                log.info("HTTP download complete: {} ({} bytes) correlationId={} sessionId={}",
                        mediaFileID, finalBytes, correlationId, safeValue(result.serverSessionId));
            } else {
                result.finalOffset = responseOffset + downloaded;
                if (paused.get()) {
                    result.errorCode = ERROR_PAUSED_BY_SERVER;
                }
            }

            return result;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("timed out")) {
                result.errorCode = ERROR_SERVER_UNAVAILABLE;
            } else if (msg != null && msg.contains("Connection")) {
                result.errorCode = ERROR_SERVER_UNAVAILABLE;
            } else {
                result.errorCode = msg == null ? "UNKNOWN_ERROR" : msg;
            }
            result.finalOffset = Math.max(0, bytesDownloaded.get());
                log.error("HTTP download error for {} correlationId={} error={}",
                    mediaFileID, correlationId, result.errorCode);
            return result;
        } finally {
            closeQuietly(localOut);
            closeQuietly(in);
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String resolveDownloadUrl(String base, String rawUrl) {
        String value = rawUrl.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        if (base == null || base.trim().isEmpty()) {
            throw new IllegalArgumentException("CONTROL_PLANE_BASE_MISSING");
        }
        String baseTrim = base.trim();
        if (baseTrim.endsWith("/") && value.startsWith("/")) {
            return baseTrim.substring(0, baseTrim.length() - 1) + value;
        }
        if (!baseTrim.endsWith("/") && !value.startsWith("/")) {
            return baseTrim + "/" + value;
        }
        return baseTrim + value;
    }

    private static long parseStartFromContentRange(String contentRange) {
        // Example: bytes 1024-2047/8192
        try {
            int space = contentRange.indexOf(' ');
            int dash = contentRange.indexOf('-');
            if (space < 0 || dash < 0 || dash <= space + 1) return 0;
            return Long.parseLong(contentRange.substring(space + 1, dash).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static long parseTotalFromContentRange(String contentRange) {
        try {
            int slash = contentRange.indexOf('/');
            if (slash < 0 || slash + 1 >= contentRange.length()) return 0;
            String total = contentRange.substring(slash + 1).trim();
            if ("*".equals(total)) return 0;
            return Long.parseLong(total);
        } catch (Exception e) {
            return 0;
        }
    }

    public void cancel() {
        cancelled.set(true);
    }

    public void pause() {
        paused.set(true);
    }

    public long getBytesDownloaded() {
        return bytesDownloaded.get();
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public boolean isPaused() {
        return paused.get();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.trim().isEmpty()) return a;
        if (b != null && !b.trim().isEmpty()) return b;
        return null;
    }

    private static String safeValue(String value) {
        return value == null ? "" : value;
    }

    private static void closeQuietly(Object closeable) {
        if (closeable == null) return;
        try {
            if (closeable instanceof OutputStream) ((OutputStream) closeable).close();
            else if (closeable instanceof InputStream) ((InputStream) closeable).close();
        } catch (Exception ignored) {
        }
    }
}
