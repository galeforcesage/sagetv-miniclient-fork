package sagex.miniclient.ngcontext;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link NgSeekPolicy} — live-edge guard, seek clamping, and coalesce logic.
 */
public class NgSeekPolicyTest {

    private NgSeekPolicy policy;

    @Before
    public void setUp() {
        policy = new NgSeekPolicy();
    }

    @Test
    public void testShouldIgnoreSeek_returnsFalse_whenNoContext() {
        assertFalse(policy.shouldIgnoreSeek(100_000, 90_000));
    }

    @Test
    public void testShouldIgnoreSeek_returnsFalse_whenNotLive() {
        policy.update(buildContext(false, 280_000, 30_000, 0));
        assertFalse(policy.shouldIgnoreSeek(290_000, 270_000));
    }

    @Test
    public void testShouldIgnoreSeek_returnsTrue_whenAtLiveEdgeAndTargetPastSafe() {
        // safeSeekEnd=280s, current=278s (within 3s threshold), target=290s (past safe)
        policy.update(buildContext(true, 280_000, 30_000, 0));
        assertTrue(policy.shouldIgnoreSeek(290_000, 278_000));
    }

    @Test
    public void testShouldIgnoreSeek_returnsFalse_whenNearButNotAtEdge() {
        // safeSeekEnd=280s, current=270s (10s from edge, beyond 3s threshold), target=290s
        // This should NOT ignore — it should clamp instead (jump to edge)
        policy.update(buildContext(true, 280_000, 30_000, 0));
        assertFalse(policy.shouldIgnoreSeek(290_000, 270_000));
    }

    @Test
    public void testShouldIgnoreSeek_returnsFalse_whenFarFromEdge() {
        // current=100s (far from 280s edge)
        policy.update(buildContext(true, 280_000, 30_000, 0));
        assertFalse(policy.shouldIgnoreSeek(290_000, 100_000));
    }

    @Test
    public void testShouldIgnoreSeek_returnsFalse_whenTargetWithinSafe() {
        policy.update(buildContext(true, 280_000, 30_000, 0));
        assertFalse(policy.shouldIgnoreSeek(270_000, 265_000));
    }

    @Test
    public void testClampSeekTarget_returnsOriginal_whenNoContext() {
        assertEquals(500_000L, policy.clampSeekTarget(500_000));
    }

    @Test
    public void testClampSeekTarget_returnsOriginal_whenWithinBounds() {
        policy.update(buildContext(true, 280_000, 30_000, 0));
        assertEquals(270_000L, policy.clampSeekTarget(270_000));
    }

    @Test
    public void testClampSeekTarget_clampsToSafeEnd_whenExceedsBounds() {
        policy.update(buildContext(true, 280_000, 30_000, 0));
        assertEquals(280_000L, policy.clampSeekTarget(350_000));
    }

    @Test
    public void testClampSeekTarget_returnsOriginal_whenSafeEndZero() {
        policy.update(buildContext(true, 0, 30_000, 0));
        assertEquals(350_000L, policy.clampSeekTarget(350_000));
    }

    @Test
    public void testShouldCoalesce_returnsFalse_whenNoContext() {
        assertFalse(policy.shouldCoalesce());
    }

    @Test
    public void testShouldCoalesce_returnsTrue_whenWithinCoalesceWindow() {
        policy.update(buildContext(false, 280_000, 30_000, 500));
        policy.markSeekExecuted();
        assertTrue(policy.shouldCoalesce());
    }

    @Test
    public void testShouldCoalesce_returnsFalse_afterWindowExpires() throws InterruptedException {
        policy.update(buildContext(false, 280_000, 30_000, 50));
        policy.markSeekExecuted();
        Thread.sleep(60);
        assertFalse(policy.shouldCoalesce());
    }

    @Test
    public void testNeedsLivePoll_returnsTrue_whenLiveWithGranularity() {
        policy.update(buildContext(true, 280_000, 30_000, 0));
        assertTrue(policy.needsLivePoll());
    }

    @Test
    public void testNeedsLivePoll_returnsFalse_whenNotLive() {
        policy.update(buildContext(false, 280_000, 30_000, 0));
        assertFalse(policy.needsLivePoll());
    }

    @Test
    public void testGetLivePollIntervalMs_returnsGranularityMinus500() {
        policy.update(buildContext(true, 280_000, 30_000, 0));
        assertEquals(29_500L, policy.getLivePollIntervalMs());
    }

    @Test
    public void testGetLivePollIntervalMs_minimumIs1000() {
        policy.update(buildContext(true, 280_000, 1_000, 0));
        assertEquals(1_000L, policy.getLivePollIntervalMs());
    }

    @Test
    public void testClear_resetsAllState() {
        policy.update(buildContext(true, 280_000, 30_000, 500));
        policy.markSeekExecuted();

        policy.clear();

        assertNull(policy.getContext());
        assertFalse(policy.shouldCoalesce());
        assertFalse(policy.shouldIgnoreSeek(500_000, 200_000));
        assertEquals(500_000L, policy.clampSeekTarget(500_000));
    }

    // --- helper: builds context with server-canonical nested structure ---

    private NgPlaybackContext buildContext(boolean live, long safeSeekEnd,
                                           long granularity, long coalesce) {
        return new NgPlaybackContext.Builder()
                .sessionId("test-session")
                .mediaFileId(123)
                .mode("push")
                .container("ts")
                .live(new NgPlaybackContext.LiveContext(
                        live, 0, 0, safeSeekEnd, safeSeekEnd + 20_000, 0, 0))
                .seek(new NgPlaybackContext.SeekPolicy(
                        granularity, 250, coalesce, true, false))
                .receivedAtMs(System.currentTimeMillis())
                .build();
    }
}
