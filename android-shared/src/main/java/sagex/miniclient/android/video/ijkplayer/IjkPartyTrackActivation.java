package sagex.miniclient.android.video.ijkplayer;

import tv.danmaku.ijk.media.player.IMediaPlayer;

final class IjkPartyTrackActivation
{
    private IjkPartyTrackActivation()
    {
    }

    static boolean isActive(int selectedTrack, int matchingTrackCount)
    {
        return selectedTrack >= 0 || matchingTrackCount > 0;
    }

    static boolean hasRendered(boolean audioActive, boolean videoActive,
            boolean audioRenderingStarted, boolean videoRenderingStarted)
    {
        if (videoActive) return videoRenderingStarted;
        return audioActive && audioRenderingStarted;
    }

    static boolean isVideoRenderingEvent(int what)
    {
        return what == IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START
                || what == IMediaPlayer.MEDIA_INFO_VIDEO_SEEK_RENDERING_START;
    }

    static boolean isAudioRenderingEvent(int what)
    {
        return what == IMediaPlayer.MEDIA_INFO_AUDIO_RENDERING_START
                || what == IMediaPlayer.MEDIA_INFO_AUDIO_SEEK_RENDERING_START;
    }

    static boolean hasPresentedAfterReset(long beforePositionMs,
                                          long currentPositionMs)
    {
        return beforePositionMs >= 0
                && currentPositionMs >= 0
                && Math.abs(currentPositionMs - beforePositionMs) >= 16;
    }

    static boolean hasDecodedFrameAfterReset(long beforeFrames,
                                             long currentFrames)
    {
        return beforeFrames >= 0 && currentFrames > beforeFrames;
    }

    static boolean canAcceptPresentation(boolean pausedPresentationPending,
                                         boolean frameStepArmed)
    {
        return !pausedPresentationPending || frameStepArmed;
    }

    static boolean shouldRetryPausedPrime(boolean pausedPresentationPending,
                                          boolean frameStepArmed,
                                          boolean playing)
    {
        return pausedPresentationPending && frameStepArmed && !playing;
    }
}
