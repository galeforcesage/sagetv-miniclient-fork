package sagex.miniclient.android.video.ijkplayer;

final class IjkPartyBufferTracker
{
    static final long SAFETY_MARGIN_MS = 250;
    static final long MIN_SAMPLE_INTERVAL_MS = 100;
    static final long MAX_SAMPLE_INTERVAL_MS = 2000;
    static final long MAX_SANE_CACHE_MS = 60 * 60 * 1000;
    static final long MAX_BASE_DISCONTINUITY_MS = 2 * 60 * 1000;

    private long generation;
    private long sampleGeneration = -1;
    private long previousSampleAtMs = -1;
    private long previousCachedDurationMs = -1;

    synchronized void reset()
    {
        generation++;
        clearSample();
    }

    synchronized long sample(long nowMs, long audioCachedDurationMs,
                             long videoCachedDurationMs, boolean audioActive,
                             boolean videoActive)
    {
        long cachedDurationMs = activeCacheDuration(
                audioCachedDurationMs, videoCachedDurationMs, audioActive, videoActive);
        if (!isSane(cachedDurationMs))
        {
            clearSample();
            return -1;
        }

        if (sampleGeneration != generation || previousSampleAtMs < 0)
        {
            remember(nowMs, cachedDurationMs);
            return -1;
        }

        long elapsedMs = nowMs - previousSampleAtMs;
        if (elapsedMs < MIN_SAMPLE_INTERVAL_MS)
        {
            return -1;
        }
        if (elapsedMs > MAX_SAMPLE_INTERVAL_MS
                || isDiscontinuous(elapsedMs, cachedDurationMs))
        {
            remember(nowMs, cachedDurationMs);
            return -1;
        }

        remember(nowMs, cachedDurationMs);
        return Math.max(0, cachedDurationMs - SAFETY_MARGIN_MS);
    }

    private long activeCacheDuration(long audioCachedDurationMs,
                                     long videoCachedDurationMs,
                                     boolean audioActive, boolean videoActive)
    {
        if (audioActive && videoActive)
        {
            if (!isSane(audioCachedDurationMs) || !isSane(videoCachedDurationMs)) return -1;
            return Math.min(audioCachedDurationMs, videoCachedDurationMs);
        }
        if (audioActive) return audioCachedDurationMs;
        if (videoActive) return videoCachedDurationMs;
        return -1;
    }

    private boolean isSane(long cachedDurationMs)
    {
        return cachedDurationMs >= 0 && cachedDurationMs <= MAX_SANE_CACHE_MS;
    }

    private boolean isDiscontinuous(long elapsedMs, long cachedDurationMs)
    {
        long allowedDeltaMs = Math.max(MAX_BASE_DISCONTINUITY_MS, elapsedMs * 50);
        return Math.abs(cachedDurationMs - previousCachedDurationMs) > allowedDeltaMs;
    }

    private void remember(long nowMs, long cachedDurationMs)
    {
        sampleGeneration = generation;
        previousSampleAtMs = nowMs;
        previousCachedDurationMs = cachedDurationMs;
    }

    private void clearSample()
    {
        sampleGeneration = -1;
        previousSampleAtMs = -1;
        previousCachedDurationMs = -1;
    }
}
