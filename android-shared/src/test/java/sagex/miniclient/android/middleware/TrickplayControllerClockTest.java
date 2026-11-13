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
    public void pendingPartyTargetArmsOnServerFlush()
    {
        assertTrue(TrickplayController.shouldArmAuthoritativeResetOnFlush(
                true, true));
        assertFalse(TrickplayController.shouldArmAuthoritativeResetOnFlush(
                true, false));
        assertFalse(TrickplayController.shouldArmAuthoritativeResetOnFlush(
                false, true));
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
    public void presentedSampleMappingDoesNotDependOnLatestLiveSample()
    {
        assertEquals(13_687L,
                TrickplayController.reportedTimeForPlayerPosition(
                        529L, false, -1L, false, 0L,
                        true, 13_158L, -1L));
        assertEquals(13_687L,
                TrickplayController.reportedTimeForPlayerPosition(
                        31_050L, false, -1L, true, 13_687L,
                        true, -17_363L, -1L));
    }

    @Test
    public void sourceRelativeIjkPositionDoesNotAddSeekTargetTwice()
    {
        long seekTargetMs = 726_589L;
        long sourceRelativePlayerPositionMs = 726_328L;

        assertEquals(0L, TrickplayController.initialPlayerTimelineOffset(
                seekTargetMs, sourceRelativePlayerPositionMs));
        assertEquals(sourceRelativePlayerPositionMs,
                TrickplayController.reportedTimeForPlayerPosition(
                        sourceRelativePlayerPositionMs, false, -1L,
                        false, 0L, false, 0L, seekTargetMs));
    }

    @Test
    public void zeroBasedIjkPositionAddsSeekTargetOnce()
    {
        long seekTargetMs = 726_589L;
        long zeroBasedPlayerPositionMs = 328L;

        assertEquals(seekTargetMs,
                TrickplayController.initialPlayerTimelineOffset(
                        seekTargetMs, zeroBasedPlayerPositionMs));
        assertEquals(726_917L,
                TrickplayController.reportedTimeForPlayerPosition(
                        zeroBasedPlayerPositionMs, false, -1L,
                        false, 0L, false, 0L, seekTargetMs));
    }

    @Test
    public void sourceRelativeExoPositionDoesNotAddPostFlushMuxBaseline()
    {
        long serverMuxTimeMs = 1_645_299L;
        long renderedPositionMs = 1_645_297L;

        assertTrue(TrickplayController.isSourceRelativePlayerTimeline(
                        serverMuxTimeMs, renderedPositionMs));
        assertEquals(renderedPositionMs,
                        TrickplayController.reportedTimeForPlayerPosition(
                                renderedPositionMs, false, -1L,
                                false, 0L, true, 0L, serverMuxTimeMs));
    }

    @Test
    public void zeroBasedExoPositionStillUsesPostFlushMuxBaseline()
    {
        assertFalse(TrickplayController.isSourceRelativePlayerTimeline(
                        878_240L, 297L));
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
    public void bufferingFreezesReportedSourceTimeline()
    {
        assertEquals(10_500L,
                TrickplayController.reportedTimeWhileBuffering(
                        true, 10_500L, 15_000L));
        assertEquals(15_000L,
                TrickplayController.reportedTimeWhileBuffering(
                        false, 10_500L, 15_000L));
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
