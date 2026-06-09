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

import java.io.BufferedOutputStream;
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
    private static final int IO_BUFFER_SIZE = 256 * 1024; // 256KB transfer/write buffer
    // Timeouts sized for VPN use: high-latency / brief stalls must not kill a healthy transfer.
    private static final int CONNECT_TIMEOUT_MS = 60000;
    private static final int READ_TIMEOUT_MS = 120000;
    private static final int ERROR_BODY_READ_LIMIT = 4096;
    private static final int PROGRESS_INTERVAL_BYTES = 1024 * 1024; // report every 1MB

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
        private String serverErrorCode;
        private String serverErrorMessage;

        public boolean isSuccess() { return success; }
        public String getErrorCode() { return errorCode; }
        public int getHttpStatus() { return httpStatus; }
        public long getFinalOffset() { return finalOffset; }
        public String getRefreshedSessionToken() { return refreshedSessionToken; }
        public String getRefreshedDownloadUrl() { return refreshedDownloadUrl; }
        public String getRefreshedSessionState() { return refreshedSessionState; }
        public String getServerSessionId() { return serverSessionId; }
        public String getServerErrorCode() { return serverErrorCode; }
        public String getServerErrorMessage() { return serverErrorMessage; }
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
    private final String ngClientId;
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
                        String ngClientId,
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
        this.ngClientId = ngClientId;
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
            if (ngClientId != null && !ngClientId.isEmpty()) {
                conn.setRequestProperty("x-ng-client-id", ngClientId);
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
                readServerErrorBody(conn, result);
                return result;
            }
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                result.errorCode = ERROR_TRANSFER_NOT_FOUND;
                readServerErrorBody(conn, result);
                log.warn("Transfer session 404 for mediaFileID={} serverCode={} serverMsg={} correlationId={}",
                        mediaFileID, safeValue(result.serverErrorCode), safeValue(result.serverErrorMessage), correlationId);
                return result;
            }
            if (code == HttpURLConnection.HTTP_GONE) {
                result.errorCode = ERROR_TRANSFER_GONE;
                readServerErrorBody(conn, result);
                log.warn("Transfer session 410 for mediaFileID={} serverCode={} serverMsg={} correlationId={}",
                        mediaFileID, safeValue(result.serverErrorCode), safeValue(result.serverErrorMessage), correlationId);
                return result;
            }
            if (code == 416) {
                result.errorCode = ERROR_INVALID_RANGE;
                readServerErrorBody(conn, result);
                return result;
            }
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                result.errorCode = ERROR_AUTH_INVALID_CREDENTIALS;
                readServerErrorBody(conn, result);
                return result;
            }
            if (code == HttpURLConnection.HTTP_FORBIDDEN) {
                result.errorCode = ERROR_AUTH_REVOKED;
                readServerErrorBody(conn, result);
                return result;
            }
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                readServerErrorBody(conn, result);
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

                in = new BufferedInputStream(conn.getInputStream(), IO_BUFFER_SIZE);
                localOut = new BufferedOutputStream(
                    storageHelper.openOutputStream(outputUri, append),
                    IO_BUFFER_SIZE);

                byte[] buffer = new byte[IO_BUFFER_SIZE];
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

    private static void readServerErrorBody(HttpURLConnection conn, Result result) {
        if (conn == null || result == null) return;
        InputStream err = null;
        try {
            err = conn.getErrorStream();
            if (err == null) return;
            byte[] buf = new byte[ERROR_BODY_READ_LIMIT];
            int off = 0;
            int n;
            while (off < buf.length && (n = err.read(buf, off, buf.length - off)) > 0) {
                off += n;
            }
            if (off <= 0) return;
            String body = new String(buf, 0, off, StandardCharsets.UTF_8);
            result.serverErrorCode = extractJsonString(body, "error_code");
            result.serverErrorMessage = extractJsonString(body, "message");
        } catch (Exception ignored) {
            // Best-effort enrichment only; never let body parsing mask the original error.
        } finally {
            if (err != null) {
                try { err.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static String extractJsonString(String body, String field) {
        if (body == null || field == null) return null;
        String needle = "\"" + field + "\"";
        int k = body.indexOf(needle);
        if (k < 0) return null;
        int colon = body.indexOf(':', k + needle.length());
        if (colon < 0) return null;
        int q1 = body.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        StringBuilder out = new StringBuilder();
        for (int i = q1 + 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) { out.append(body.charAt(i + 1)); i++; continue; }
            if (c == '"') return out.toString();
            out.append(c);
        }
        return null;
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
