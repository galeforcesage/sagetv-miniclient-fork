package sagex.miniclient.ngcontext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sagex.miniclient.IBus;

/**
 * Per-connection store for the current NG playback context. Thread-safe.
 * <p>
 * Lifecycle:
 * <ol>
 *   <li>Created when {@link sagex.miniclient.MiniClientConnection} is established</li>
 *   <li>{@link #onPropertyReceived(String)} called when server sends NG_PLAYBACK_CONTEXT SET_PROPERTY</li>
 *   <li>{@link #onMediaOpen(String)} called from MediaCmd on MEDIACMD_OPENURL</li>
 *   <li>{@link #onMediaClose()} called from MediaCmd on MEDIACMD_DEINIT / close</li>
 * </ol>
 * <p>
 * When connected to a legacy server, no methods are called and {@link #getCurrent()} returns null.
 */
public final class NgPlaybackContextStore {

    private static final Logger log = LoggerFactory.getLogger(NgPlaybackContextStore.class);

    private final IBus eventBus;
    private final NgSeekPolicy seekPolicy = new NgSeekPolicy();
    private final NgLivePoller livePoller;
    private volatile NgPlaybackContext current;
    private volatile String pendingOpenUrl;
    private volatile String serverBaseUrl;

    public NgPlaybackContextStore(IBus eventBus) {
        this.eventBus = eventBus;
        this.livePoller = new NgLivePoller(this);
    }

    /**
     * Set the server base URL for live polling (e.g. "http://192.168.0.10:8080").
     * Should be called when the connection is established.
     */
    public void setServerBaseUrl(String url) {
        this.serverBaseUrl = url;
    }

    /**
     * Called when the server sends NG_PLAYBACK_CONTEXT via SET_PROPERTY.
     * Parses the wire value and updates the current context.
     */
    public void onPropertyReceived(String wireValue) {
        if (wireValue == null || wireValue.isEmpty()) {
            log.debug("Received empty NG_PLAYBACK_CONTEXT, clearing context");
            clear();
            return;
        }

        var url = pendingOpenUrl;
        var previous = current;
        var newContext = NgPlaybackContextParser.parse(wireValue, url);
        current = newContext;
        seekPolicy.update(newContext);

        log.debug("NG Playback Context updated: {}", newContext);

        // Manage live poller based on new context
        if (seekPolicy.needsLivePoll() && serverBaseUrl != null) {
            String sid = newContext.sessionId();
            if (sid != null && !sid.isEmpty()) {
                livePoller.start(serverBaseUrl, sid, seekPolicy.getLivePollIntervalMs());
            }
        } else if (!seekPolicy.needsLivePoll()) {
            livePoller.stop();
        }

        if (eventBus != null) {
            eventBus.post(new NgPlaybackContextEvent.Updated(previous, newContext));
        }
    }

    /**
     * Called from MediaCmd when MEDIACMD_OPENURL is processed.
     * Stores the URL so that a subsequent context property can reference it.
     */
    public void onMediaOpen(String url) {
        this.pendingOpenUrl = url;
        log.debug("Media opened with URL: {}", url);
    }

    /**
     * Called from MediaCmd when media is closed (MEDIACMD_DEINIT or MediaCmd.close()).
     * Clears the current context.
     */
    public void onMediaClose() {
        clear();
        pendingOpenUrl = null;
        livePoller.stop();
    }

    /**
     * Returns the current playback context, or null if no NG context has been received
     * (legacy server, or no media is playing).
     */
    public NgPlaybackContext getCurrent() {
        return current;
    }

    /**
     * Returns the seek policy informed by the current NG context.
     * Always non-null; returns pass-through behavior when no context is present.
     */
    public NgSeekPolicy getSeekPolicy() {
        return seekPolicy;
    }

    /**
     * Returns the live poller instance (for lifecycle management).
     */
    public NgLivePoller getLivePoller() {
        return livePoller;
    }

    /**
     * Shut down all resources (connection closing).
     */
    public void shutdown() {
        clear();
        livePoller.shutdown();
    }

    private void clear() {
        var previous = current;
        current = null;
        seekPolicy.clear();
        if (previous != null && eventBus != null) {
            eventBus.post(new NgPlaybackContextEvent.Cleared(previous));
        }
    }
}
