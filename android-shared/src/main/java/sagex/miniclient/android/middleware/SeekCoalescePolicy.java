package sagex.miniclient.android.middleware;

/**
 * Pure (no Android dependency) decision logic extracted from
 * {@link TrickplayController} so it can be regression-tested on a plain JVM
 * without a Handler/Looper. Behavior-preserving extraction: these are the
 * exact same expressions that used to live inline in
 * {@code TrickplayController.beginSeek()} / {@code onPlayerPosition()}.
 */
final class SeekCoalescePolicy
{
    private SeekCoalescePolicy()
    {
    }

    /**
     * Computes the delay (ms) before a coalesced seek should commit, given a
     * new beginSeek() call arriving "now".
     *
     * @param now              current wallclock (System.currentTimeMillis())
     * @param burstStartedAtMs wallclock of the first beginSeek() in the
     *                         current burst (0 means "no burst in progress";
     *                         callers seed this with {@code now} on the first
     *                         call of a burst before invoking this method)
     * @param quietWindowMs    coalesce window for this mode (push vs pull)
     * @param maxDeferMs       hard ceiling on how long a burst may defer commit
     * @return milliseconds to wait before committing the latest target
     */
    static long computeCommitDelayMs(long now, long burstStartedAtMs, long quietWindowMs, long maxDeferMs)
    {
        long sinceBurstStart = now - burstStartedAtMs;
        long maxDeferRemaining = maxDeferMs - sinceBurstStart;
        return Math.min(quietWindowMs, Math.max(0, maxDeferRemaining));
    }

    /**
     * Decides whether a backward player-position sample should be dropped by
     * the pause-resume monotonicity guard.
     *
     * @param lastPlayerPosMs previously observed player position
     * @param positionMs      newly observed player position
     * @param seekArmed       true if a seek is armed (not yet sent to player)
     * @param seekCommitted   true if a seek was sent to the player and is
     *                        awaiting convergence
     * @param minRegressionMs regressions at or below this are normal jitter -&gt; keep
     * @param maxRegressionMs regressions at or above this are a real
     *                        reload/loop/file-change -&gt; keep (and resync)
     * @return true if the sample should be dropped (ignored)
     */
    static boolean shouldDropBackwardSample(long lastPlayerPosMs, long positionMs,
            boolean seekArmed, boolean seekCommitted,
            long minRegressionMs, long maxRegressionMs)
    {
        // A seek legitimately rewinds the clock; the mapping path owns that case.
        if (seekArmed || seekCommitted)
        {
            return false;
        }
        if (lastPlayerPosMs <= 0 || positionMs >= lastPlayerPosMs)
        {
            return false;
        }
        long regression = lastPlayerPosMs - positionMs;
        return regression > minRegressionMs && regression < maxRegressionMs;
    }
}
