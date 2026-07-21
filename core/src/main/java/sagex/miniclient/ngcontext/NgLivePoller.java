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
 * live-edge fields ({@code playableEndMs}, {@code safeSeekEndMs}) fresh.
 * <p>
 * Only active when the current context indicates a live stream with
 * {@code preferredGranularityMs > 0}. Automatically stops on media close.
 * <p>
 * The poll result is fed back into the {@link NgPlaybackContextStore} which
 * updates the context and re-publishes the bus event.
 */
public final class NgLivePoller {

    private static final Logger log = LoggerFactory.getLogger(NgLivePoller.class);
    private static final int HTTP_TIMEOUT_MS = 3_000;

    private final NgPlaybackContextStore store;
    private final ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> pollTask;
    private volatile String serverBaseUrl;

    public NgLivePoller(NgPlaybackContextStore store) {
        this.store = store;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "NgLivePoller");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start polling at the given interval.
     *
     * @param serverBaseUrl base URL of the NG server (e.g. "http://192.168.0.10:8080")
     * @param intervalMs    poll interval in milliseconds
     */
    public void start(String serverBaseUrl, long intervalMs) {
        stop(); // cancel any existing poll
        this.serverBaseUrl = serverBaseUrl;
        log.info("NgLivePoller: starting poll every {}ms against {}", intervalMs, serverBaseUrl);
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
        var base = serverBaseUrl;
        if (base == null) return;

        try {
            var url = URI.create(base + "/ng/playback-context/current").toURL();
            var conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "text/plain");

            int status = conn.getResponseCode();
            if (status == 200) {
                try (var reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    var wireValue = reader.readLine();
                    if (wireValue != null && !wireValue.isEmpty()) {
                        store.onPropertyReceived(wireValue);
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
