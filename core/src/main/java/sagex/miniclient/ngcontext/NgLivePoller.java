package sagex.miniclient.ngcontext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Periodically polls the NG server's playback-context endpoint to keep
 * live-edge fields ({@code live.safeSeekEndMs}, {@code live.playableEndMs}) fresh.
 * <p>
 * This is a <b>fallback/supplement</b> for the primary delivery path which is
 * server-pushed SET_PROPERTY over the event channel. The poller is useful when:
 * <ul>
 *   <li>The server doesn't push at the right interval</li>
 *   <li>The connection was interrupted and context may be stale</li>
 *   <li>The server version only supports HTTP polling (no push)</li>
 * </ul>
 * <p>
 * Only active when the current context indicates a live stream with
 * {@code seek.preferredGranularityMs > 0}. Automatically stops on media close.
 * <p>
 * Uses {@code GET /ng/playback-context/{sessionId}} or
 * {@code GET /ng/playback-context/current?clientName={name}}.
 */
public final class NgLivePoller {

    private static final Logger log = LoggerFactory.getLogger(NgLivePoller.class);
    private static final int HTTP_TIMEOUT_MS = 3_000;

    private final NgPlaybackContextStore store;
    private final ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> pollTask;
    private volatile String pollUrl;

    public NgLivePoller(NgPlaybackContextStore store) {
        this.store = store;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "NgLivePoller");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start polling using a session ID.
     *
     * @param serverBaseUrl base URL (e.g. "http://192.168.0.10:31099")
     * @param sessionId     the NG session UUID from the context
     * @param intervalMs    poll interval in milliseconds
     */
    public void start(String serverBaseUrl, String sessionId, long intervalMs) {
        stop();
        this.pollUrl = serverBaseUrl + "/ng/playback-context/" + sessionId;
        log.info("NgLivePoller: starting poll every {}ms → {}", intervalMs, pollUrl);
        pollTask = scheduler.scheduleAtFixedRate(this::poll, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Start polling using a client name (fallback when sessionId not available).
     *
     * @param serverBaseUrl base URL
     * @param clientName    the MiniClient identity name
     * @param intervalMs    poll interval in milliseconds
     */
    public void startByClientName(String serverBaseUrl, String clientName, long intervalMs) {
        stop();
        this.pollUrl = serverBaseUrl + "/ng/playback-context/current?clientName=" + clientName;
        log.info("NgLivePoller: starting poll every {}ms → {}", intervalMs, pollUrl);
        pollTask = scheduler.scheduleAtFixedRate(this::poll, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Stop polling (media closed, channel change, or no longer live).
     */
    public void stop() {
        var task = pollTask;
        if (task != null) {
            task.cancel(false);
            pollTask = null;
            log.debug("NgLivePoller: stopped");
        }
    }

    /**
     * Shut down the executor (connection closing).
     */
    public void shutdown() {
        stop();
        scheduler.shutdownNow();
    }

    /**
     * Whether the poller is actively running.
     */
    public boolean isRunning() {
        var task = pollTask;
        return task != null && !task.isCancelled() && !task.isDone();
    }

    private void poll() {
        var url = pollUrl;
        if (url == null) return;

        try {
            var conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");

            int status = conn.getResponseCode();
            if (status == 200) {
                try (var reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    var sb = new StringBuilder(1024);
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    String body = sb.toString();
                    if (!body.isEmpty()) {
                        store.onPropertyReceived(body);
                        log.debug("NgLivePoller: context refreshed");
                    }
                }
            } else {
                log.debug("NgLivePoller: server returned HTTP {}", status);
            }
            conn.disconnect();
        } catch (Exception e) {
            log.warn("NgLivePoller: poll failed — {}", e.getMessage());
        }
    }
}
