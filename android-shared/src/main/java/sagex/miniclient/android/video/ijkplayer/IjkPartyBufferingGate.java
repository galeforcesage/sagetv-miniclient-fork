package sagex.miniclient.android.video.ijkplayer;

final class IjkPartyBufferingGate
{
    private static final long MIN_PROGRESS_MS = 100;

    private IjkPartyBufferingGate()
    {
    }

    static boolean renderedOutputStillAdvancing(long startPositionMs,
            long currentPositionMs, long startGeneration,
            long currentGeneration, boolean renderingStarted)
    {
        return renderingStarted
                && startGeneration == currentGeneration
                && startPositionMs >= 0
                && currentPositionMs - startPositionMs >= MIN_PROGRESS_MS;
    }

    static boolean renderedPositionDiscontinuous(long startPositionMs,
            long currentPositionMs, long startGeneration,
            long currentGeneration, boolean renderingStarted)
    {
        return renderingStarted
                && startGeneration == currentGeneration
                && startPositionMs >= 0
                && currentPositionMs >= 0
                && currentPositionMs < startPositionMs;
    }
}
