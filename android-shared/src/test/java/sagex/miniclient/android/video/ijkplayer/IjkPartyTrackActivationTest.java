package sagex.miniclient.android.video.ijkplayer;

import org.junit.Test;

import tv.danmaku.ijk.media.player.IMediaPlayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IjkPartyTrackActivationTest
{
    @Test
    public void singleVideoTrackIsActiveBeforeRenderingStarts()
    {
        assertTrue(IjkPartyTrackActivation.isActive(-1, 1));
    }

    @Test
    public void multipleUnselectedTracksAreActiveMedia()
    {
        assertTrue(IjkPartyTrackActivation.isActive(-1, 2));
    }

    @Test
    public void selectedTrackIsAlwaysActive()
    {
        assertTrue(IjkPartyTrackActivation.isActive(0, 0));
    }

    @Test
    public void absentTrackDoesNotBecomeActive()
    {
        assertFalse(IjkPartyTrackActivation.isActive(-1, 0));
    }

    @Test
    public void audioVideoMediaActivatesWhenVideoRenders()
    {
        assertTrue(IjkPartyTrackActivation.hasRendered(
                true, true, false, true));
    }

    @Test
    public void audioVideoMediaDoesNotActivateFromAudioAlone()
    {
        assertFalse(IjkPartyTrackActivation.hasRendered(
                true, true, true, false));
    }

    @Test
    public void audioOnlyMediaRequiresAudioRendering()
    {
        assertFalse(IjkPartyTrackActivation.hasRendered(
                true, false, false, false));
        assertTrue(IjkPartyTrackActivation.hasRendered(
                true, false, true, false));
    }

    @Test
    public void videoOnlyMediaRequiresVideoRendering()
    {
        assertFalse(IjkPartyTrackActivation.hasRendered(
                false, true, false, false));
        assertTrue(IjkPartyTrackActivation.hasRendered(
                false, true, false, true));
    }

    @Test
    public void mediaWithoutAudioOrVideoNeverActivates()
    {
        assertFalse(IjkPartyTrackActivation.hasRendered(
                false, false, true, true));
    }

    @Test
    public void seekRenderingEventsReactivateTheirTracks()
    {
        assertTrue(IjkPartyTrackActivation.isVideoRenderingEvent(
                IMediaPlayer.MEDIA_INFO_VIDEO_SEEK_RENDERING_START));
        assertTrue(IjkPartyTrackActivation.isAudioRenderingEvent(
                IMediaPlayer.MEDIA_INFO_AUDIO_SEEK_RENDERING_START));
    }

    @Test
    public void changedOutputClockReactivatesPushGeneration()
    {
        assertTrue(IjkPartyTrackActivation.hasPresentedAfterReset(64_075, 0));
        assertTrue(IjkPartyTrackActivation.hasPresentedAfterReset(0, 33));
        assertFalse(IjkPartyTrackActivation.hasPresentedAfterReset(100, 110));
        assertFalse(IjkPartyTrackActivation.hasPresentedAfterReset(-1, 100));
    }

    @Test
    public void decodedFrameAdvanceConfirmsNewVideoGeneration()
    {
        assertTrue(IjkPartyTrackActivation.hasDecodedFrameAfterReset(100, 101));
        assertFalse(IjkPartyTrackActivation.hasDecodedFrameAfterReset(100, 100));
        assertFalse(IjkPartyTrackActivation.hasDecodedFrameAfterReset(101, 100));
        assertFalse(IjkPartyTrackActivation.hasDecodedFrameAfterReset(-1, 1));
    }

    @Test
    public void resumedPresentationAcceptsPositionOrDecodedFrameAdvance()
    {
        assertTrue(IjkPartyTrackActivation.hasPresentedAfterResume(
                1_000, 1_020, 100, 100));
        assertTrue(IjkPartyTrackActivation.hasPresentedAfterResume(
                1_000, 1_000, 100, 101));
        assertFalse(IjkPartyTrackActivation.hasPresentedAfterResume(
                1_000, 1_000, 100, 100));
    }

    @Test
    public void activePresentationSampleAdvancesWithMonotonicTime()
    {
        assertEquals(1_100,
                IjkPartyTrackActivation.advancePresentedPosition(
                        1_000, 1_000, 1_100, 1_300));
        assertEquals(1_050,
                IjkPartyTrackActivation.advancePresentedPosition(
                        1_000, 1_000, 1_100, 1_050));
        assertEquals(1_100,
                IjkPartyTrackActivation.advancePresentedPosition(
                        1_000, 1_000, 1_100, 1_000));
    }

    @Test
    public void pausedPresentationRequiresPostRepositionFrameStep()
    {
        assertFalse(IjkPartyTrackActivation.canAcceptPresentation(true, false));
        assertTrue(IjkPartyTrackActivation.canAcceptPresentation(true, true));
        assertTrue(IjkPartyTrackActivation.canAcceptPresentation(false, false));
    }

    @Test
    public void playingBarrierAcceptsRenderedFrameWithoutPausedPrime()
    {
        assertTrue(IjkPartyTrackActivation.canAcceptPresentation(false, false));
    }

    @Test
    public void pausedPrimeRetriesWhenBufferingFinishesAfterTimeout()
    {
        assertTrue(IjkPartyTrackActivation.shouldRetryPausedPrime(
                true, true, false));
        assertFalse(IjkPartyTrackActivation.shouldRetryPausedPrime(
                true, true, true));
        assertFalse(IjkPartyTrackActivation.shouldRetryPausedPrime(
                false, true, false));
        assertFalse(IjkPartyTrackActivation.shouldRetryPausedPrime(
                true, false, false));
    }

    @Test
    public void uncorrectedPushResumePreservesRenderedTracks()
    {
        assertTrue(IjkPartyTrackActivation.shouldPreserveRenderedTracksForResume(
                true, false, true, true, true));
    }

    @Test
    public void generationChangingCommandsResetRenderedTracks()
    {
        assertFalse(IjkPartyTrackActivation.shouldPreserveRenderedTracksForResume(
                false, false, true, true, true));
        assertFalse(IjkPartyTrackActivation.shouldPreserveRenderedTracksForResume(
                true, true, true, true, true));
        assertFalse(IjkPartyTrackActivation.shouldPreserveRenderedTracksForResume(
                true, false, false, true, true));
        assertFalse(IjkPartyTrackActivation.shouldPreserveRenderedTracksForResume(
                true, false, true, false, true));
        assertFalse(IjkPartyTrackActivation.shouldPreserveRenderedTracksForResume(
                true, false, true, true, false));
    }

    @Test
    public void uncorrectedPushPausePreservesPresentedFrame()
    {
        assertTrue(IjkPartyTrackActivation
                .shouldPreservePresentedFrameForPause(
                        true, false, true, true, true));
        assertFalse(IjkPartyTrackActivation
                .shouldPreservePresentedFrameForPause(
                        false, false, true, true, true));
        assertFalse(IjkPartyTrackActivation
                .shouldPreservePresentedFrameForPause(
                        true, true, true, true, true));
        assertFalse(IjkPartyTrackActivation
                .shouldPreservePresentedFrameForPause(
                        true, false, false, true, true));
        assertFalse(IjkPartyTrackActivation
                .shouldPreservePresentedFrameForPause(
                        true, false, true, false, true));
        assertFalse(IjkPartyTrackActivation
                .shouldPreservePresentedFrameForPause(
                        true, false, true, true, false));
    }
}
