package sagex.miniclient.ngcontext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side seek policy informed by the NG playback context.
 * <p>
 * Provides three guards that are consulted before any seek is executed:
 * <ol>
 *   <li><b>Ignore</b> — seek target is past the live edge and we're already at edge → no-op</li>
 *   <li><b>Clamp</b> — seek target exceeds safe boundary → clamp to safeSeekEndMs</li>
 *   <li><b>Coalesce</b> — rapid successive FF commands → defer until burst settles</li>
 * </ol>
 * <p>
 * When no NG context is present (legacy server), all methods return pass-through values
 * (never ignore, never clamp, never coalesce).
 */
public final class NgSeekPolicy {

    private static final Logger log = LoggerFactory.getLogger(NgSeekPolicy.class);

    /** If context is older than this, consider it stale and disable policy. */
    private static final long STALENESS_THRESHOLD_MS = 30_000;

    /**
     * How close to safeSeekEndMs counts as "at edge" for ignore purposes.
     * Intentionally tight (3s) — we want the first FF that overshoots to CLAMP to edge,
     * and only ignore subsequent FFs once the player has actually reached edge.
     */
    private static final long LIVE_EDGE_THRESHOLD_MS = 3_000;

    private volatile NgPlaybackContext context;
    private volatile long lastSeekExecutedAtMs;

    /**
     * Update the policy with a fresh context (called on each context refresh/poll).
     */
    public void update(NgPlaybackContext ctx) {
        this.context = ctx;
    }

    /**
     * Clear the policy (media closed or context unavailable).
     */
    public void clear() {
        this.context = null;
        this.lastSeekExecutedAtMs = 0;
    }

    /**
     * Should this seek be ignored entirely?
     * <p>
     * Rule: if target &gt; safeSeekEndMs AND current position is already AT the live edge
     * (within a tight threshold) → the user is already at edge, further FFs are no-ops.
     * <p>
     * "At edge" means within {@link #LIVE_EDGE_THRESHOLD_MS} of safeSeekEndMs.
     * This is intentionally tight — if the user is 10s behind edge and FFs past it,
     * we want to CLAMP (jump to edge), not ignore. Only once they're at edge do we ignore.
     *
     * @param targetMs         the desired seek position in ms
     * @param currentPositionMs the player's current position in ms
     * @return true if the seek should be silently dropped
     */
    public boolean shouldIgnoreSeek(long targetMs, long currentPositionMs) {
        var ctx = context;
        if (ctx == null || !ctx.live().isLive() || isStale(ctx)) return false;
        if (ctx.live().safeSeekEndMs() <= 0) return false;

        // Target is past the safe boundary
        boolean targetPastEdge = targetMs > ctx.live().safeSeekEndMs();
        // Current position is already AT the edge (tight threshold)
        boolean alreadyAtEdge = (ctx.live().safeSeekEndMs() - currentPositionMs) <= LIVE_EDGE_THRESHOLD_MS;

        if (targetPastEdge && alreadyAtEdge) {
            log.debug("NgSeekPolicy: IGNORE seek to {}ms — already at live edge (current={}ms, safeEnd={}ms)",
                    targetMs, currentPositionMs, ctx.live().safeSeekEndMs());
            return true;
        }
        return false;
    }

    /**
     * Clamp the seek target to the safe boundary if it exceeds it.
     * <p>
     * Rule: if target &gt; safeSeekEndMs and we're NOT at edge → clamp to safeSeekEndMs.
     *
     * @param targetMs the desired seek position in ms
     * @return the (possibly clamped) seek target
     */
    public long clampSeekTarget(long targetMs) {
        var ctx = context;
        if (ctx == null || isStale(ctx)) return targetMs;
        if (ctx.live().safeSeekEndMs() <= 0) return targetMs;

        if (targetMs > ctx.live().safeSeekEndMs()) {
            log.debug("NgSeekPolicy: CLAMP seek from {}ms to safeSeekEndMs={}ms", targetMs, ctx.live().safeSeekEndMs());
            return ctx.live().safeSeekEndMs();
        }
        return targetMs;
    }

    /**
     * Should this seek be coalesced (deferred) because a previous seek was too recent?
     * <p>
     * Rule: if time since last executed seek &lt; maxClientCoalesceMs → defer.
     * The caller should skip this seek; the next one will supersede it.
     *
     * @return true if this seek should be deferred
     */
    public boolean shouldCoalesce() {
        var ctx = context;
        if (ctx == null || isStale(ctx)) return false;
        if (ctx.seek().maxClientCoalesceMs() <= 0) return false;

        long now = System.currentTimeMillis();
        long elapsed = now - lastSeekExecutedAtMs;

        if (elapsed < ctx.seek().maxClientCoalesceMs()) {
            log.debug("NgSeekPolicy: COALESCE — {}ms since last seek (threshold={}ms)", elapsed, ctx.seek().maxClientCoalesceMs());
            return true;
        }
        return false;
    }

    /**
     * Mark that a seek was actually executed. Resets the coalesce timer.
     */
    public void markSeekExecuted() {
        this.lastSeekExecutedAtMs = System.currentTimeMillis();
    }

    /**
     * Is this a live stream that should be polled for context updates?
     */
    public boolean needsLivePoll() {
        var ctx = context;
        return ctx != null && ctx.live().isLive() && ctx.seek().preferredGranularityMs() > 0;
    }

    /**
     * Recommended poll interval for live context refresh.
     *
     * @return interval in ms (granularity - 500ms, minimum 1000ms)
     */
    public long getLivePollIntervalMs() {
        var ctx = context;
        if (ctx == null || ctx.seek().preferredGranularityMs() <= 0) return 5_000;
        return Math.max(1_000, ctx.seek().preferredGranularityMs() - 500);
    }

    /**
     * Returns the current context, or null if unavailable/stale.
     */
    public NgPlaybackContext getContext() {
        var ctx = context;
        return (ctx != null && !isStale(ctx)) ? ctx : null;
    }

    private boolean isStale(NgPlaybackContext ctx) {
        return (System.currentTimeMillis() - ctx.receivedAtMs()) > STALENESS_THRESHOLD_MS;
    }
}
