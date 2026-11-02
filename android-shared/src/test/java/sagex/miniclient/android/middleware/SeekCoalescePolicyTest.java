package sagex.miniclient.android.middleware;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Regression coverage for the seek-coalescing and pause-resume monotonicity
 * guard math used by {@link TrickplayController}. Exercises the pure logic
 * extracted into {@link SeekCoalescePolicy} so these invariants can be
 * verified on a plain JVM (no Handler/Looper).
 *
 * <p>Per the NG trick-play contract handoff, this covers: FF, a second FF
 * arriving mid-burst, REW, a second REW arriving mid-burst, repeated taps
 * during settle (a long held-button burst), and stale/backward position
 * samples. Live/DVR edge clamping is covered separately by
 * {@code NgSeekPolicyTest} (core module) — that policy, not
 * {@code TrickplayController}, owns clamping seek targets to the server's
 * DVR window.
 */
public class SeekCoalescePolicyTest
{
    private static final long PULL_WINDOW_MS = 180;   // mirrors SEEK_QUIET_WINDOW_MS
    private static final long PUSH_WINDOW_MS = 250;   // mirrors PUSH_SEEK_QUIET_WINDOW_MS
    private static final long MAX_DEFER_MS = 1500;    // mirrors SEEK_MAX_DEFER_MS
    private static final long GUARD_MIN_MS = 250;     // mirrors MONOTONIC_GUARD_MIN_REGRESSION_MS
    private static final long GUARD_MAX_MS = 30_000;  // mirrors MONOTONIC_GUARD_MAX_REGRESSION_MS

    /* ---- FF (single tap, pull mode) ---- */

    @Test
    public void ff_firstTapInBurst_usesFullQuietWindow()
    {
        long now = 10_000L;
        long burstStartedAt = now; // beginSeek() seeds this with "now" on first call
        long delay = SeekCoalescePolicy.computeCommitDelayMs(now, burstStartedAt, PULL_WINDOW_MS, MAX_DEFER_MS);
        assertEquals(PULL_WINDOW_MS, delay);
    }

    /* ---- FF #2 (second tap mid-burst, before quiet window elapses) ---- */

    @Test
    public void ffSecondTap_midBurst_shrinksDelayButDoesNotResetBurst()
    {
        long burstStartedAt = 10_000L;
        // Second tap arrives 100ms into the burst, well under either window
        // and far under the max-defer ceiling.
        long now = burstStartedAt + 100;
        long delay = SeekCoalescePolicy.computeCommitDelayMs(now, burstStartedAt, PULL_WINDOW_MS, MAX_DEFER_MS);
        // Still bounded by the quiet window (max-defer remaining is huge),
        // i.e. the coalesce window itself is NOT restarted by this call —
        // beginSeek() callers reset the Handler callback, not burstStartedAtMs.
        assertEquals(PULL_WINDOW_MS, delay);
    }

    /* ---- REW (single tap, push mode uses the wider window) ---- */

    @Test
    public void rew_firstTapInBurst_pushModeUsesWiderWindow()
    {
        long now = 20_000L;
        long delay = SeekCoalescePolicy.computeCommitDelayMs(now, now, PUSH_WINDOW_MS, MAX_DEFER_MS);
        assertEquals(PUSH_WINDOW_MS, delay);
    }

    /* ---- REW #2 (held burst approaching the max-defer ceiling) ---- */

    @Test
    public void rewSecondTap_nearMaxDefer_clampsDelayToRemainingBudget()
    {
        long burstStartedAt = 0L;
        // 1400ms into a burst with a 1500ms ceiling: only 100ms of defer
        // budget remains, which is less than either quiet window.
        long now = 1400L;
        long delay = SeekCoalescePolicy.computeCommitDelayMs(now, burstStartedAt, PULL_WINDOW_MS, MAX_DEFER_MS);
        assertEquals(100L, delay);
    }

    /* ---- Repeated taps during settle: a burst that outlives max-defer ---- */

    @Test
    public void repeatedTapsDuringSettle_pastMaxDefer_forcesImmediateCommit()
    {
        long burstStartedAt = 0L;
        long now = MAX_DEFER_MS + 50; // burst has run past the hard ceiling
        long delay = SeekCoalescePolicy.computeCommitDelayMs(now, burstStartedAt, PULL_WINDOW_MS, MAX_DEFER_MS);
        // Clamped to zero (Math.max(0, negative)), i.e. commit now so a held
        // button still advances the video even if seeks keep arriving.
        assertEquals(0L, delay);
    }

    @Test
    public void repeatedTapsDuringSettle_exactlyAtMaxDefer_commitsImmediately()
    {
        long burstStartedAt = 0L;
        long now = MAX_DEFER_MS;
        long delay = SeekCoalescePolicy.computeCommitDelayMs(now, burstStartedAt, PULL_WINDOW_MS, MAX_DEFER_MS);
        assertEquals(0L, delay);
    }

    /* ---- Stale / backward position samples (pause-resume monotonicity guard) ---- */

    @Test
    public void backwardSample_withinGuardBand_isDropped()
    {
        // 1000ms blip that falls strictly inside (MIN, MAX) and no seek in flight.
        boolean drop = SeekCoalescePolicy.shouldDropBackwardSample(
                10_000L, 9_000L, false, false, GUARD_MIN_MS, GUARD_MAX_MS);
        assertTrue("blip inside the guard band must be dropped", drop);
    }

    @Test
    public void backwardSample_atOrBelowMin_isNormalJitter_notDropped()
    {
        // Exactly MIN is NOT inside the open interval (MIN, MAX) -> keep.
        boolean drop = SeekCoalescePolicy.shouldDropBackwardSample(
                10_000L, 10_000L - GUARD_MIN_MS, false, false, GUARD_MIN_MS, GUARD_MAX_MS);
        assertFalse("regression at the MIN boundary is normal jitter, must be kept", drop);
    }

    @Test
    public void backwardSample_atOrAboveMax_isRealReload_notDropped()
    {
        // Exactly MAX is NOT inside the open interval (MIN, MAX) -> keep (resync).
        boolean drop = SeekCoalescePolicy.shouldDropBackwardSample(
                40_000L, 40_000L - GUARD_MAX_MS, false, false, GUARD_MIN_MS, GUARD_MAX_MS);
        assertFalse("regression at/above MAX must be treated as a real reload/loop, not dropped", drop);
    }

    @Test
    public void backwardSample_whileSeekArmed_isNeverDropped()
    {
        // A seek legitimately rewinds the clock; the mapping path owns this,
        // so the monotonicity guard must stand down whenever a seek is in
        // flight — otherwise a real seek-induced rewind would be swallowed.
        boolean drop = SeekCoalescePolicy.shouldDropBackwardSample(
                10_000L, 9_000L, true, false, GUARD_MIN_MS, GUARD_MAX_MS);
        assertFalse(drop);
    }

    @Test
    public void backwardSample_whileSeekCommitted_isNeverDropped()
    {
        boolean drop = SeekCoalescePolicy.shouldDropBackwardSample(
                10_000L, 9_000L, false, true, GUARD_MIN_MS, GUARD_MAX_MS);
        assertFalse(drop);
    }

    @Test
    public void forwardSample_isNeverDropped()
    {
        // positionMs >= lastPlayerPosMs is normal forward progress.
        boolean drop = SeekCoalescePolicy.shouldDropBackwardSample(
                10_000L, 10_500L, false, false, GUARD_MIN_MS, GUARD_MAX_MS);
        assertFalse(drop);
    }

    @Test
    public void backwardSample_beforeAnyPriorPosition_isNeverDropped()
    {
        // lastPlayerPosMs <= 0 means no baseline yet (fresh session) -> nothing to guard.
        boolean drop = SeekCoalescePolicy.shouldDropBackwardSample(
                0L, -5L, false, false, GUARD_MIN_MS, GUARD_MAX_MS);
        assertFalse(drop);
    }
}
