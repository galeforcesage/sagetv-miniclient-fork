package sagex.miniclient.android.video.ijkplayer;

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
}
