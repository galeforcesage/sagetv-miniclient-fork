package sagex.miniclient.android.middleware;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TrickplayControllerClockTest
{
    @Test
    public void ordinaryFlushInvalidatesPlayerClockMapping()
    {
        assertTrue(TrickplayController.invalidatesMappingOnFlush(false));
    }

    @Test
    public void partyCorrectionMappingSurvivesServerFlush()
    {
        assertFalse(TrickplayController.invalidatesMappingOnFlush(true));
    }

    @Test
    public void rawMpegClockIsAnchoredToPartyTarget()
    {
        long targetPositionMs = 88_812L;
        long rawMpegPositionMs = 1_339_607_005L;

        long baseOffsetMs = TrickplayController.authoritativeBaseOffset(
                targetPositionMs, rawMpegPositionMs);

        assertEquals(targetPositionMs, rawMpegPositionMs + baseOffsetMs);
    }

    @Test
    public void correctionDoesNotAnchorBeforeTransportRepositionStarts()
    {
        assertFalse(TrickplayController.shouldAnchorAuthoritativePosition(
                false, true, 64_075L, 64_053L));
        assertTrue(TrickplayController.shouldAnchorAuthoritativePosition(
                true, true, 0L, 64_053L));
    }

    @Test
    public void pushTransportSeekDoesNotReplaceExplicitlyPreservedPartyTarget()
    {
        assertTrue(TrickplayController.preservesAuthoritativePositionOnPushSeek(
                true, true));
        assertFalse(TrickplayController.preservesAuthoritativePositionOnPushSeek(
                true, false));
        assertFalse(TrickplayController.preservesAuthoritativePositionOnPushSeek(
                false, true));
    }

    @Test
    public void softCorrectionDurationIgnoresFrameNoiseAndCapsAdjustment()
    {
        assertEquals(0, sagex.miniclient.android.video.BaseMediaPlayerImpl
                .softCorrectionDurationMs(10_067, 10_000));
        assertEquals(68, sagex.miniclient.android.video.BaseMediaPlayerImpl
                .softCorrectionDurationMs(10_068, 10_000));
        assertEquals(750, sagex.miniclient.android.video.BaseMediaPlayerImpl
                .softCorrectionDurationMs(12_000, 10_000));
    }
}
