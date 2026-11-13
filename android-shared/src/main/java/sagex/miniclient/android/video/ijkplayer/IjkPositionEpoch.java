package sagex.miniclient.android.video.ijkplayer;

final class IjkPositionEpoch
{
    private static final long TARGET_MATCH_WINDOW_MS = 5_000;
    private boolean ready;
    private long heldPositionMs;
    private long expectedTargetMs = -1;

    synchronized void prepareSeek(long targetMs)
    {
        expectedTargetMs = Math.max(0, targetMs);
    }

    synchronized void reset(long lastReportedPositionMs)
    {
        ready = false;
        heldPositionMs = Math.max(0, lastReportedPositionMs);
    }

    synchronized void resetForOpen(long initialPositionMs)
    {
        expectedTargetMs = -1;
        reset(initialPositionMs);
    }

    synchronized boolean markRendered(long playerPositionMs)
    {
        if (expectedTargetMs >= 0)
        {
            boolean plausible = playerPositionMs >= 0
                    && (playerPositionMs <= TARGET_MATCH_WINDOW_MS
                    || absoluteDifference(
                            playerPositionMs, expectedTargetMs)
                            <= TARGET_MATCH_WINDOW_MS);
            if (!plausible)
            {
                return false;
            }
            expectedTargetMs = -1;
        }
        ready = true;
        return true;
    }

    synchronized boolean isReady()
    {
        return ready;
    }

    synchronized long getHeldPosition()
    {
        return heldPositionMs;
    }

    synchronized void recordReportedPosition(long positionMs)
    {
        if (ready && positionMs >= 0)
        {
            heldPositionMs = positionMs;
        }
    }

    private static long absoluteDifference(long left, long right)
    {
        long difference = left - right;
        if (difference == Long.MIN_VALUE) return Long.MAX_VALUE;
        return Math.abs(difference);
    }
}
