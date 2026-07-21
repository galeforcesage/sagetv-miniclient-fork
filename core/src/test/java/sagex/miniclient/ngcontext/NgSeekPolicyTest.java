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

    // --- shouldIgnoreSeek tests ---

    @Test
    public void testShouldIgnoreSeek_returnsFalse_whenNoContext() {
        assertFalse(policy.shouldIgnoreSeek(100_000, 90_000));
    }

    @Test
    public void testShouldIgnoreSeek_returnsFalse_whenNotLive() {
        var ctx = buildContext(false, 300_000, 280_000, 30_000, 0);
        policy.update(ctx);
        assertFalse(policy.shouldIgnoreSeek(290_000, 270_000));
    }

    @Test
    public void testShouldIgnoreSeek_returnsTrue_whenAtLiveEdgeAndTargetPastSafe() {
        var ctx = buildContext(true, 300_000, 280_000, 30_000, 0);
        policy.update(ctx);
        assertTrue(policy.shouldIgnoreSeek(290_000, 260_000));
    }

    @Test
    public void testShouldIgnoreSeek_returnsFalse_whenFarFromEdge() {
        var ctx = buildContext(true, 300_000, 280_000, 30_000, 0);
        policy.update(ctx);
        assertFalse(policy.shouldIgnoreSeek(290_000, 100_000));
    }

    @Test
    public void testShouldIgnoreSeek_returnsFalse_whenTargetWithinSafe() {
        var ctx = buildContext(true, 300_000, 280_000, 30_000, 0);
        policy.update(ctx);
        assertFalse(policy.shouldIgnoreSeek(270_000, 265_000));
    }

    // --- clampSeekTarget tests ---

    @Test
    public void testClampSeekTarget_returnsOriginal_whenNoContext() {
        assertEquals(500_000L, policy.clampSeekTarget(500_000));
    }

    @Test
    public void testClampSeekTarget_returnsOriginal_whenWithinBounds() {
        var ctx = buildContext(true, 300_000, 280_000, 30_000, 0);
        policy.update(ctx);
        assertEquals(270_000L, policy.clampSeekTarget(270_000));
    }

    @Test
    public void testClampSeekTarget_clampsToSafeEnd_whenExceedsBounds() {
        var ctx = buildContext(true, 300_000, 280_000, 30_000, 0);
        policy.update(ctx);
        assertEquals(280_000L, policy.clampSeekTarget(350_000));
    }

    @Test
    public void testClampSeekTarget_returnsOriginal_whenSafeEndNotSet() {
        var ctx = buildContext(true, 300_000, -1, 30_000, 0);
        policy.update(ctx);
        assertEquals(350_000L, policy.clampSeekTarget(350_000));
    }

    // --- shouldCoalesce tests ---

    @Test
    public void testShouldCoalesce_returnsFalse_whenNoContext() {
        assertFalse(policy.shouldCoalesce());
    }

    @Test
    public void testShouldCoalesce_returnsFalse_whenCoalesceNotConfigured() {
        var ctx = buildContext(false, 300_000, 280_000, 30_000, 0);
        policy.update(ctx);
        assertFalse(policy.shouldCoalesce());
    }

    @Test
    public void testShouldCoalesce_returnsTrue_whenWithinCoalesceWindow() {
        var ctx = buildContext(false, 300_000, 280_000, 30_000, 500);
        policy.update(ctx);
        policy.markSeekExecuted();
        assertTrue(policy.shouldCoalesce());
    }

    @Test
    public void testShouldCoalesce_returnsFalse_afterCoalesceWindowExpires() throws InterruptedException {
        var ctx = buildContext(false, 300_000, 280_000, 30_000, 50);
        policy.update(ctx);
        policy.markSeekExecuted();
        Thread.sleep(60);
        assertFalse(policy.shouldCoalesce());
    }

    // --- needsLivePoll tests ---

    @Test
    public void testNeedsLivePoll_returnsTrue_whenLiveWithGranularity() {
        var ctx = buildContext(true, 300_000, 280_000, 30_000, 0);
        policy.update(ctx);
        assertTrue(policy.needsLivePoll());
    }

    @Test
    public void testNeedsLivePoll_returnsFalse_whenNotLive() {
        var ctx = buildContext(false, 300_000, 280_000, 30_000, 0);
        policy.update(ctx);
        assertFalse(policy.needsLivePoll());
    }

    @Test
    public void testNeedsLivePoll_returnsFalse_whenNoGranularity() {
        var ctx = buildContext(true, 300_000, 280_000, 0, 0);
        policy.update(ctx);
        assertFalse(policy.needsLivePoll());
    }

    // --- getLivePollIntervalMs tests ---

    @Test
    public void testGetLivePollIntervalMs_returnsGranularityMinus500() {
        var ctx = buildContext(true, 300_000, 280_000, 30_000, 0);
        policy.update(ctx);
        assertEquals(29_500L, policy.getLivePollIntervalMs());
    }

    @Test
    public void testGetLivePollIntervalMs_minimumIs1000() {
        var ctx = buildContext(true, 300_000, 280_000, 1_000, 0);
        policy.update(ctx);
        assertEquals(1_000L, policy.getLivePollIntervalMs());
    }

    // --- clear tests ---

    @Test
    public void testClear_resetsAllState() {
        var ctx = buildContext(true, 300_000, 280_000, 30_000, 500);
        policy.update(ctx);
        policy.markSeekExecuted();

        policy.clear();

        assertNull(policy.getContext());
        assertFalse(policy.shouldCoalesce());
        assertFalse(policy.shouldIgnoreSeek(500_000, 200_000));
        assertEquals(500_000L, policy.clampSeekTarget(500_000));
    }

    // --- helper ---

    private NgPlaybackContext buildContext(boolean live, long playableEnd, long safeSeekEnd,
                                           long granularity, long coalesce) {
        return new NgPlaybackContext.Builder()
                .mediaFileId("test-123")
                .title("Test")
                .durationMs(-1)
                .contentType(live ? "live" : "recording")
                .isLive(live)
                .playableEndMs(playableEnd)
                .safeSeekEndMs(safeSeekEnd)
                .preferredGranularityMs(granularity)
                .maxClientCoalesceMs(coalesce)
                .receivedAtMs(System.currentTimeMillis())
                .build();
    }
}
