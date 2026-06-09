/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 */
package sagex.miniclient.android.offline;

import android.content.Context;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import sagex.miniclient.ServerInfo;
import sagex.miniclient.android.MiniclientApplication;

/**
 * Standalone HTTP-based fetcher for offline EPG guide and schedule snapshots.
 *
 * Unlike the full mini-client protocol, this makes plain REST calls to the
 * SageTV NG server's snapshot API endpoints so the user can refresh their
 * offline data with a single button press — no active streaming session needed.
 *
 * Server-side endpoints expected (NG extension — same base as download API):
 *   GET /api/offline/guide-snapshot   → CMD_OFFLINE_GUIDE_SNAPSHOT payload (JSON)
 *   GET /api/offline/sched-snapshot   → CMD_OFFLINE_SCHED_SNAPSHOT payload (JSON)
 *   GET /api/offline/favorites-snapshot → favorites snapshot payload (JSON)
 *
 * Authentication: HTTP Basic (username:password) sent as Authorization header.
 * Falls back to the stored DownloadCredentialVault accounts for the matching
 * server if no per-server credential has been configured.
 *
 * Public contract: all methods are thread-safe; callbacks are always invoked
 * on a background thread — callers must marshal to the UI thread themselves.
 */
public final class SnapshotSyncManager {
    private static final Logger log = LoggerFactory.getLogger(SnapshotSyncManager.class);

    private static final String PATH_GUIDE = "/api/offline/guide-snapshot";
    private static final String PATH_SCHED = "/api/offline/sched-snapshot";
    private static final String PATH_FAVORITES = "/api/offline/favorites-snapshot";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS    = 60_000;
    private static final int FETCH_RETRY_COUNT  = 2;

    public enum SnapshotKind { GUIDE, SCHEDULE, FAVORITES }

    public interface SyncCallback {
        /** Called when the fetch completes successfully. */
        void onSuccess(SnapshotKind kind, String serverName);
        /**
         * Called on any failure. {@code message} is a human-readable reason
         * suitable for display in a toast/dialog.
         */
        void onFailure(SnapshotKind kind, String serverName, String message);
    }

    private final Context context;
    private final ExecutorService exec = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "SnapshotSync");
        t.setDaemon(true);
        return t;
    });

    public SnapshotSyncManager(Context context) {
        this.context = context.getApplicationContext();
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Returns all servers currently saved in the client's server list. */
    public List<ServerInfo> getSavedServers() {
        return MiniclientApplication.get(context).getClient().getServers().getSavedServers();
    }

    /**
     * Kicks off an async sync of all saved servers, one at a time.
     * {@code callback} fires once per server, per kind.
     */
    public void syncAll(SnapshotKind kind, SyncCallback callback) {
        List<ServerInfo> servers = getSavedServers();
        if (servers.isEmpty()) {
            callback.onFailure(kind, "—", "No servers configured. Add a server first.");
            return;
        }
        exec.submit(() -> {
            for (ServerInfo si : servers) {
                syncOneSync(kind, si, callback);
            }
        });
    }

    /**
     * Kicks off an async sync of a single specific server.
     */
    public void syncServer(SnapshotKind kind, ServerInfo server, SyncCallback callback) {
        exec.submit(() -> syncOneSync(kind, server, callback));
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private void syncOneSync(SnapshotKind kind, ServerInfo si, SyncCallback callback) {
        String serverName = si.name != null && !si.name.isEmpty() ? si.name
                : si.address + ":" + si.port;
        String serverId = si.address + ":" + si.port;
        String base = buildBase(si);
        String path;
        if (kind == SnapshotKind.GUIDE) {
            path = PATH_GUIDE;
        } else if (kind == SnapshotKind.SCHEDULE) {
            path = PATH_SCHED;
        } else {
            path = PATH_FAVORITES;
        }

        String credential = resolveCredential(si);

        log.info("SnapshotSync {} {} → {}{}", kind, serverName, base, path);
        try {
            String json = null;
            Exception last = null;
            for (int attempt = 1; attempt <= FETCH_RETRY_COUNT; attempt++) {
                try {
                    json = httpGet(base + path, credential);
                    json = normalizeSnapshotJson(json);
                    // Validate it is actually JSON before persisting.
                    new JSONObject(json);
                    break;
                } catch (HttpFetchException e) {
                    // Retry only for transport/truncation style failures.
                    last = e;
                    if (!e.retryable || attempt == FETCH_RETRY_COUNT) {
                        throw e;
                    }
                    log.warn("SnapshotSync {} {} attempt {}/{} failed (retrying): {}",
                            kind, serverName, attempt, FETCH_RETRY_COUNT, e.getMessage());
                } catch (Exception e) {
                    // Common case from screenshot: unterminated JSON due partial payload.
                    last = e;
                    String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                    boolean maybeTruncated = msg.contains("unterminated");
                    boolean malformedStatusLine = msg.contains("unexpected status line");
                    if ((!maybeTruncated && !malformedStatusLine) || attempt == FETCH_RETRY_COUNT) {
                        throw e;
                    }
                    log.warn("SnapshotSync {} {} attempt {}/{} got malformed response (retrying): {}",
                            kind, serverName, attempt, FETCH_RETRY_COUNT, e.getMessage());
                }
            }
            if (json == null || json.isEmpty()) {
                callback.onFailure(kind, serverName, "Empty response from server");
                return;
            }

            OfflineEpgRepository repo = new OfflineEpgRepository(context);
            if (kind == SnapshotKind.GUIDE) {
                repo.replaceGuideSnapshot(serverId, serverName, json);
            } else if (kind == SnapshotKind.SCHEDULE) {
                repo.replaceScheduleSnapshot(serverId, serverName, json);
            } else {
                repo.replaceFavoritesSnapshot(serverId, serverName, json);
            }
            callback.onSuccess(kind, serverName);

        } catch (HttpFetchException e) {
            String msg;
            if (e.code == 404) {
                msg = "Server " + serverName + " does not support offline snapshots yet "
                        + "(endpoint not found). Upgrade SageTV NG server.";
            } else if (e.code == 401 || e.code == 403) {
                msg = "Authentication failed for " + serverName + ". Check credentials.";
            } else if (e.code == HttpFetchException.CODE_TRUNCATED_PAYLOAD) {
                msg = "Server returned a truncated snapshot payload from " + serverName
                        + ". Please retry or check server logs.";
            } else if (e.code > 0) {
                msg = "Server returned HTTP " + e.code + " from " + serverName;
            } else {
                msg = "Could not reach " + serverName + ": " + e.getMessage();
            }
            log.warn("SnapshotSync {} {} failed: {}", kind, serverName, msg);
            callback.onFailure(kind, serverName, msg);

        } catch (Exception e) {
            log.warn("SnapshotSync {} {} unexpected error: {}", kind, serverName, e.toString());
            String message = e.getMessage();
            if (message != null && message.toLowerCase().contains("unterminated")) {
                callback.onFailure(kind, serverName,
                        "Malformed snapshot JSON received from server (unterminated object). "
                        + "This usually means a truncated payload; client attempted "
                        + "tail-repair but payload is still incomplete. Please retry and "
                        + "check server snapshot serialization.");
            } else if (message != null && message.toLowerCase().contains("unexpected status line")) {
                callback.onFailure(kind, serverName,
                        "Malformed HTTP response framing received from server while syncing snapshot. "
                                + "Please retry. If it persists, check server response writer/connection handling.");
            } else {
                callback.onFailure(kind, serverName, "Unexpected error: " + e.getMessage());
            }
        }
    }

    private String httpGet(String urlString, String basicCredential) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Connection", "close");
            if (basicCredential != null && !basicCredential.isEmpty()) {
                conn.setRequestProperty("Authorization", "Basic " + basicCredential);
            }
            conn.setRequestProperty("X-Sage-Client", "SageTV-OfflinePlayer/1.0");
            conn.connect();

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new HttpFetchException(code, "HTTP " + code + " from " + urlString, false);
            }

            long expectedLength = conn.getContentLengthLong();
            byte[] data;
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                data = out.toByteArray();
            }

            if (expectedLength > 0 && data.length < expectedLength) {
                throw new HttpFetchException(
                        HttpFetchException.CODE_TRUNCATED_PAYLOAD,
                        "Truncated payload: expected " + expectedLength + " bytes, got " + data.length,
                        true);
            }

            return new String(data, StandardCharsets.UTF_8).trim();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Keep only the first complete top-level JSON object from a response body.
     * Protects against trailing protocol text appended by broken transport framing.
     */
    private static String sanitizeToFirstJsonObject(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        if (start < 0) return raw;

        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }

            if (ch == '"') {
                inString = true;
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return raw.substring(start, i + 1);
                }
            }
        }
        return raw;
    }

    /**
     * Normalizes/sanitizes snapshot payloads that may contain framing garbage.
     * Strategy:
     * 1) normal sanitize + tail-repair
     * 2) if still invalid and embedded HTTP lines are present, split into
     *    candidate chunks and return the first parseable JSON object.
     */
    private static String normalizeSnapshotJson(String raw) throws Exception {
        if (raw == null) return null;

        String base = tryRepairTruncatedJsonTail(sanitizeToFirstJsonObject(raw));
        if (isValidJsonObject(base)) {
            return base;
        }

        if (raw.contains("HTTP/1.1") || raw.contains("HTTP/1.0")) {
            String recovered = recoverJsonFromEmbeddedHttp(raw);
            if (recovered != null) {
                return recovered;
            }
        }
        return base;
    }

    private static boolean isValidJsonObject(String candidate) {
        if (candidate == null || candidate.isEmpty()) return false;
        try {
            new JSONObject(candidate);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Attempts to recover a valid JSON payload from a body contaminated with
     * embedded HTTP status lines/headers.
     */
    private static String recoverJsonFromEmbeddedHttp(String raw) {
        String[] parts = raw.split("HTTP/1\\.[01] 200 OK");
        for (String p : parts) {
            String candidate = p;

            // If this segment starts with headers, skip to blank-line delimiter.
            int headerEnd = candidate.indexOf("\r\n\r\n");
            if (headerEnd >= 0) {
                candidate = candidate.substring(headerEnd + 4);
            } else {
                int headerEndLf = candidate.indexOf("\n\n");
                if (headerEndLf >= 0) {
                    candidate = candidate.substring(headerEndLf + 2);
                }
            }

            candidate = tryRepairTruncatedJsonTail(sanitizeToFirstJsonObject(candidate));
            if (isValidJsonObject(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Best-effort repair for responses truncated only at the tail: append any
     * missing closing braces/brackets based on delimiter stack balance.
     * If truncation happens mid-token/mid-string, parsing will still fail.
     */
    private static String tryRepairTruncatedJsonTail(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return s;

        int start = s.indexOf('{');
        if (start > 0) {
            s = s.substring(start);
        }

        ArrayDeque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }

            if (ch == '"') {
                inString = true;
                continue;
            }
            if (ch == '{' || ch == '[') {
                stack.push(ch);
            } else if (ch == '}' || ch == ']') {
                if (stack.isEmpty()) {
                    return s;
                }
                char open = stack.peek();
                if ((open == '{' && ch == '}') || (open == '[' && ch == ']')) {
                    stack.pop();
                } else {
                    return s;
                }
            }
        }

        if (inString) {
            // Unsafe to auto-close a string when content is truncated mid-token.
            return s;
        }
        if (stack.isEmpty()) {
            return s;
        }

        StringBuilder repaired = new StringBuilder(s);
        while (!stack.isEmpty()) {
            char open = stack.pop();
            repaired.append(open == '{' ? '}' : ']');
        }
        return repaired.toString();
    }

    /**
     * Resolves a Basic-auth credential string for a server.
     *
     * Priority order:
     * 1. Per-server credential stored in DownloadCredentialVault under the
     *    family key derived from the server address:port.
     * 2. Any credential in the vault (first non-revoked account).
     * 3. No credential (unauthenticated request — works on open NG installs).
     */
    private String resolveCredential(ServerInfo si) {
        try {
            DownloadCredentialVault vault = new DownloadCredentialVault(context);
            // Try server-specific family first.
            String family = "sagetv:" + si.address + ":" + si.port;
            List<DownloadCredentialVault.AccountSnapshot> candidates =
                    vault.getCandidates(family, null);
            if (candidates.isEmpty()) {
                // Fall back to default / first-available account.
                candidates = vault.getCandidates("sagetv", null);
            }
            if (!candidates.isEmpty()) {
                DownloadCredentialVault.AccountSnapshot acct = candidates.get(0);
                String raw = acct.username + ":" + acct.password;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
                }
                return android.util.Base64.encodeToString(
                        raw.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
            }
        } catch (Exception e) {
            log.warn("resolveCredential failed: {}", e.toString());
        }
        return null;
    }

    private static String buildBase(ServerInfo si) {
        String host = si.address == null ? "" : si.address.trim();
        int port = si.port > 0 ? si.port : 31099;
        return "http://" + host + ":" + port;
    }

    private static final class HttpFetchException extends Exception {
        static final int CODE_TRUNCATED_PAYLOAD = -2;
        final int code;
        final boolean retryable;
        HttpFetchException(int code, String msg, boolean retryable) {
            super(msg);
            this.code = code;
            this.retryable = retryable;
        }
    }
}
