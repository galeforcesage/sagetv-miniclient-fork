package sagex.miniclient.android.offline;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import sagex.miniclient.MiniClient;
import sagex.miniclient.MiniClientConnection;
import sagex.miniclient.ServerInfo;
import sagex.miniclient.android.MiniclientApplication;

/** Best-effort offline playback-state reconciliation to NG server control-plane endpoints. */
final class OfflinePlaybackStateSync {
    private static final Logger log = LoggerFactory.getLogger(OfflinePlaybackStateSync.class);

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "OfflinePlaybackStateSync");
        thread.setDaemon(true);
        return thread;
    });

    private OfflinePlaybackStateSync() {}

    static void syncAsync(Context context, DownloadMetadata meta, String reason) {
        if (context == null || meta == null) return;
        List<DownloadMetadata> metas = new ArrayList<>(1);
        metas.add(meta);
        syncBatchAsync(context, metas, reason);
    }

    static void syncAllCompleteAsync(Context context, String reason) {
        if (context == null) return;
        DownloadRepository repository = DownloadManager.getInstance(context).getRepository();
        syncBatchAsync(context, repository.getByStatus(DownloadMetadata.Status.COMPLETE), reason);
    }

    private static void syncBatchAsync(Context context, List<DownloadMetadata> metas, String reason) {
        if (context == null || metas == null || metas.isEmpty()) return;
        List<PlaybackStateRecord> records = new ArrayList<>();
        DownloadMetadata first = null;
        String sharedSessionToken = null;
        String sharedCorrelationId = null;
        String sharedAccountFamily = null;

        for (DownloadMetadata meta : metas) {
            if (meta == null || meta.getStatus() != DownloadMetadata.Status.COMPLETE) continue;
            String mediaFileId = nullIfBlank(meta.getMediaFileID());
            if (mediaFileId == null) continue;
            if (first == null) first = meta;
            if (sharedSessionToken == null) sharedSessionToken = nullIfBlank(meta.getSessionToken());
            if (sharedCorrelationId == null) sharedCorrelationId = nullIfBlank(meta.getCorrelationId());
            if (sharedAccountFamily == null) sharedAccountFamily = nullIfBlank(meta.getAccountFamily());
            records.add(new PlaybackStateRecord(
                    mediaFileId,
                    Math.max(0L, meta.getPlaybackPositionMs()),
                    meta.isWatched(),
                    nullIfBlank(meta.getSessionToken()),
                    nullIfBlank(meta.getCorrelationId()),
                    nullIfBlank(meta.getAccountFamily())));
        }

        if (records.isEmpty() || first == null) return;
        String clientId = getNgClientIdOrNull();
        String base = resolveControlPlaneBase(first);
        if (base == null) {
            log.info("offline_playback_state_sync_skipped count={} reason=no_control_plane_base", records.size());
            return;
        }

        Context appContext = context.getApplicationContext();
        String sessionToken = sharedSessionToken;
        String correlationId = sharedCorrelationId;
        String accountFamily = sharedAccountFamily;
        EXECUTOR.submit(() -> postBatch(appContext, base, records,
                sessionToken, correlationId, accountFamily, clientId, reason));
    }

    private static void postBatch(Context context,
                                  String base,
                                  List<PlaybackStateRecord> records,
                                  String sessionToken,
                                  String correlationId,
                                  String accountFamily,
                                  String clientId,
                                  String reason) {
        String endpoint = trimTrailingSlash(base) + "/api/offline/playback-state-sync";
        HttpURLConnection conn = null;
        try {
            byte[] body = buildBatchPayload(records, sessionToken, correlationId, accountFamily, clientId, reason)
                    .getBytes(StandardCharsets.UTF_8);
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-SageTV-Offline-Sync", "playback-state-v1");
            if (sessionToken != null) {
                conn.setRequestProperty("X-Transfer-Token", sessionToken);
            }
            if (correlationId != null) {
                conn.setRequestProperty("X-Correlation-ID", correlationId);
            }
            if (clientId != null) {
                conn.setRequestProperty("x-ng-client-id", clientId);
            }
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                applyResponse(context, conn);
                log.info("offline_playback_state_sync_ok count={} reason={}", records.size(), reason);
            } else if (code == 404 || code == 405 || code == 501) {
                log.info("offline_playback_state_sync_unsupported count={} httpStatus={}", records.size(), code);
            } else {
                log.warn("offline_playback_state_sync_failed count={} httpStatus={}", records.size(), code);
            }
        } catch (Exception e) {
            log.info("offline_playback_state_sync_error count={} reason={}", records.size(), e.toString());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void applyResponse(Context context, HttpURLConnection conn) {
        try {
            String raw = readFully(conn.getInputStream());
            if (raw == null || raw.trim().isEmpty()) return;
            JSONObject root = new JSONObject(raw);
            JSONArray recordings = root.optJSONArray("recordings");
            if (recordings == null) {
                JSONObject single = root.optJSONObject("recording");
                if (single == null && root.has("media_file_id")) single = root;
                if (single != null) {
                    recordings = new JSONArray();
                    recordings.put(single);
                }
            }
            if (recordings == null || recordings.length() == 0) return;

            DownloadRepository repository = DownloadManager.getInstance(context).getRepository();
            int applied = 0;
            for (int i = 0; i < recordings.length(); i++) {
                JSONObject item = recordings.optJSONObject(i);
                if (item == null) continue;
                String mediaFileId = nullIfBlank(item.optString("media_file_id", null));
                if (mediaFileId == null) continue;
                DownloadMetadata meta = repository.getByMediaFileID(mediaFileId);
                if (meta == null || meta.getStatus() != DownloadMetadata.Status.COMPLETE) continue;

                boolean changed = false;
                if (item.has("watched")) {
                    boolean watched = item.optBoolean("watched", meta.isWatched());
                    if (watched && !meta.isWatched()) {
                        meta.setWatched(true);
                        changed = true;
                    }
                }
                if (item.has("resume_position_ms")) {
                    long serverPosition = Math.max(0L, item.optLong("resume_position_ms", 0L));
                    if (serverPosition > meta.getPlaybackPositionMs()) {
                        meta.setPlaybackPositionMs(serverPosition);
                        changed = true;
                    }
                }
                if (changed) {
                    repository.update(meta);
                    applied++;
                }
            }
            if (applied > 0) {
                log.info("offline_playback_state_sync_applied count={}", applied);
            }
        } catch (Exception e) {
            log.info("offline_playback_state_sync_response_ignored reason={}", e.toString());
        }
    }

    private static String buildBatchPayload(List<PlaybackStateRecord> records,
                                            String sessionToken,
                                            String correlationId,
                                            String accountFamily,
                                            String clientId,
                                            String reason) {
        long now = System.currentTimeMillis();
        StringBuilder payload = new StringBuilder(256 + (records.size() * 128));
        payload.append('{')
                .append("\"schema_version\":1")
                .append(",\"reason\":\"").append(escapeJson(reason)).append('"')
                .append(",\"updated_at_ms\":").append(now);
        appendString(payload, "session_token", sessionToken);
        appendString(payload, "correlation_id", correlationId);
        appendString(payload, "account_family", accountFamily);
        appendString(payload, "client_id", clientId);
        payload.append(",\"recordings\":[");
        for (int i = 0; i < records.size(); i++) {
            PlaybackStateRecord record = records.get(i);
            if (i > 0) payload.append(',');
            payload.append('{')
                    .append("\"media_file_id\":\"").append(escapeJson(record.mediaFileId)).append('"')
                    .append(",\"resume_position_ms\":").append(record.resumePositionMs)
                    .append(",\"watched\":").append(record.watched)
                    .append(",\"updated_at_ms\":").append(now);
            appendString(payload, "session_token", record.sessionToken);
            appendString(payload, "correlation_id", record.correlationId);
            appendString(payload, "account_family", record.accountFamily);
            payload.append('}');
        }
        payload.append("]}");
        return payload.toString();
    }

    private static String readFully(InputStream inputStream) throws Exception {
        if (inputStream == null) return null;
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                out.append(buffer, 0, read);
            }
        }
        return out.toString();
    }

    private static String resolveControlPlaneBase(DownloadMetadata meta) {
        try {
            MiniClient client = MiniclientApplication.get().getClient();
            ServerInfo serverInfo = client != null ? client.getConnectedServerInfo() : null;
            if (serverInfo == null && client != null && client.getServers() != null) {
                serverInfo = client.getServers().getLastConnectedServer();
            }
            if (serverInfo != null && nullIfBlank(serverInfo.address) != null) {
                int port = serverInfo.port > 0 ? serverInfo.port : 31099;
                return "http://" + serverInfo.address.trim() + ":" + port;
            }
        } catch (Exception ignored) {
        }
        return firstNonBlank(
                extractBaseFromUrl(meta.getOfflineMetadataUrl()),
                extractBaseFromUrl(meta.getDownloadUrl()));
    }

    private static String getNgClientIdOrNull() {
        try {
            MiniClient client = MiniclientApplication.get().getClient();
            MiniClientConnection connection = client != null ? client.getCurrentConnection() : null;
            return connection != null ? nullIfBlank(connection.getClientId()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractBaseFromUrl(String raw) {
        String value = nullIfBlank(raw);
        if (value == null) return null;
        try {
            URL url = new URL(value);
            int port = url.getPort();
            StringBuilder base = new StringBuilder();
            base.append(url.getProtocol()).append("://").append(url.getHost());
            if (port > 0) base.append(':').append(port);
            return base.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            String normalized = nullIfBlank(value);
            if (normalized != null) return normalized;
        }
        return null;
    }

    private static void appendString(StringBuilder payload, String key, String value) {
        String normalized = nullIfBlank(value);
        if (normalized == null) return;
        payload.append(',').append('"').append(key).append("\":\"")
                .append(escapeJson(normalized)).append('"');
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) return "";
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String nullIfBlank(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"') {
                out.append('\\').append(c);
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else if (c == '\t') {
                out.append("\\t");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static final class PlaybackStateRecord {
        final String mediaFileId;
        final long resumePositionMs;
        final boolean watched;
        final String sessionToken;
        final String correlationId;
        final String accountFamily;

        PlaybackStateRecord(String mediaFileId,
                            long resumePositionMs,
                            boolean watched,
                            String sessionToken,
                            String correlationId,
                            String accountFamily) {
            this.mediaFileId = mediaFileId;
            this.resumePositionMs = resumePositionMs;
            this.watched = watched;
            this.sessionToken = sessionToken;
            this.correlationId = correlationId;
            this.accountFamily = accountFamily;
        }
    }
}
