package com.google.android.exoplayer2.extractor.ts;

import com.google.android.exoplayer2.extractor.SeekMap;
import com.google.android.exoplayer2.extractor.SeekPoint;
import com.google.android.exoplayer2.extractor.ts.SagePsExtractor.LinearPsSeekMap;
import com.google.android.exoplayer2.extractor.ts.SagePsExtractor.LiveSizeProvider;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pure-JVM unit tests for {@link LinearPsSeekMap}. Covers:
 *   - frozen-size mode (no LiveSizeProvider)
 *   - live-growing mode (mock LiveSizeProvider)
 *   - seek to zero / past-duration / typical interior point
 *   - 2 KB byte-position alignment
 */
public class LinearPsSeekMapTest {

    private static final long ONE_SEC_US = 1_000_000L;

    /** Mock provider that returns a fixed size, mutable between calls. */
    private static final class FixedProvider implements LiveSizeProvider {
        long size;
        FixedProvider(long size) { this.size = size; }
        @Override public long getCurrentSize() { return size; }
    }

    @Test
    public void frozenSize_returnsInitialDuration() {
        // 60s, 60 MB → 1 MB/s
        LinearPsSeekMap m = new LinearPsSeekMap(60 * ONE_SEC_US, 60_000_000L, null);
        assertTrue(m.isSeekable());
        assertEquals(60 * ONE_SEC_US, m.getDurationUs());
    }

    @Test
    public void seekToZero_returnsByteZero() {
        LinearPsSeekMap m = new LinearPsSeekMap(60 * ONE_SEC_US, 60_000_000L, null);
        SeekMap.SeekPoints points = m.getSeekPoints(0L);
        assertEquals(0L, points.first.position);
        assertEquals(0L, points.first.timeUs);
    }

    @Test
    public void seekPastDuration_clampsToFileSize() {
        LinearPsSeekMap m = new LinearPsSeekMap(60 * ONE_SEC_US, 60_000_000L, null);
        SeekMap.SeekPoints points = m.getSeekPoints(120 * ONE_SEC_US);
        assertEquals(60_000_000L, points.first.position);
    }

    @Test
    public void seekInterior_isLinearAndAligned() {
        // 100s, 100 MB → 1 MB/s. Seek to 30s expects 30 MB, 2 KB-aligned.
        LinearPsSeekMap m = new LinearPsSeekMap(100 * ONE_SEC_US, 100_000_000L, null);
        SeekMap.SeekPoints points = m.getSeekPoints(30 * ONE_SEC_US);
        long expected = 30_000_000L;
        long aligned = (expected / 2048) * 2048;
        assertEquals(aligned, points.first.position);
        // Position must be 2 KB-aligned (MPEG-PS pack boundary alignment).
        assertEquals(0L, points.first.position % 2048);
    }

    @Test
    public void liveGrowing_durationGrowsWithFile() {
        FixedProvider provider = new FixedProvider(60_000_000L);
        // Start: 60s of 60 MB. Constant bitrate = 1 MB/s.
        LinearPsSeekMap m = new LinearPsSeekMap(60 * ONE_SEC_US, 60_000_000L, provider);
        assertEquals(60 * ONE_SEC_US, m.getDurationUs());

        // File grows to 90 MB → reported duration should grow to 90s.
        provider.size = 90_000_000L;
        assertEquals(90 * ONE_SEC_US, m.getDurationUs());
    }

    @Test
    public void liveGrowing_seekIntoFutureExtensionMapsToCorrectByte() {
        FixedProvider provider = new FixedProvider(60_000_000L);
        // 60s/60MB initial; file grew to 120 MB while playing.
        LinearPsSeekMap m = new LinearPsSeekMap(60 * ONE_SEC_US, 60_000_000L, provider);
        provider.size = 120_000_000L;

        // Seek to 90s — past the original 60s window but well inside the new
        // 120s duration (and far enough from the live edge that the safety
        // margin does not apply).
        SeekMap.SeekPoints points = m.getSeekPoints(90 * ONE_SEC_US);

        // Duration is now 120s, file size 120 MB, so 90s -> 90 MB (aligned).
        long expected = 90_000_000L;
        long aligned = (expected / 2048) * 2048;
        assertEquals(aligned, points.first.position);
    }

    @Test
    public void liveGrowing_seekNearLiveEdgeIsClampedBehindHead() {
        // Bug 2 regression: FF that lands within ~1s of the live write-head
        // freezes because the server's MediaServer thread blocks waiting for
        // bytes that aren't flushed yet. The seek map must hold a small safety
        // margin behind the head on live recordings.
        FixedProvider provider = new FixedProvider(60_000_000L);
        LinearPsSeekMap m = new LinearPsSeekMap(60 * ONE_SEC_US, 60_000_000L, provider);
        provider.size = 120_000_000L; // duration now 120s

        // Ask to seek to 119.5s (0.5s behind head) — must clamp to 117s
        // (3s safety margin) and map to the corresponding byte position.
        SeekMap.SeekPoints points = m.getSeekPoints(119_500_000L);
        long clampedUs = 120 * ONE_SEC_US - 3 * ONE_SEC_US;
        long expectedByte = (long) ((double) clampedUs / (120 * ONE_SEC_US) * 120_000_000L);
        long aligned = (expectedByte / 2048) * 2048;
        assertEquals(clampedUs, points.first.timeUs);
        assertEquals(aligned, points.first.position);
        // And the byte position must be strictly less than the live file size
        // so the server never blocks on un-flushed bytes.
        assertTrue(points.first.position < 120_000_000L);
    }

    @Test
    public void frozenSize_seekNearEndIsNotClamped() {
        // Safety margin must NOT apply to non-live (frozen-size) maps; those
        // are completed recordings where the entire file is flushed and the
        // user expects to be able to FF up to the exact end.
        LinearPsSeekMap m = new LinearPsSeekMap(60 * ONE_SEC_US, 60_000_000L, null);
        SeekMap.SeekPoints points = m.getSeekPoints(59_500_000L);
        // No clamp: timeUs is preserved.
        assertEquals(59_500_000L, points.first.timeUs);
    }

    @Test
    public void liveGrowing_providerReturningSmallerSizeIsIgnored() {
        // Live provider must never go backward; if it does (server hiccup),
        // we keep the original size to avoid mapping seeks into garbage.
        FixedProvider provider = new FixedProvider(30_000_000L); // smaller than initial
        LinearPsSeekMap m = new LinearPsSeekMap(60 * ONE_SEC_US, 60_000_000L, provider);
        assertEquals(60 * ONE_SEC_US, m.getDurationUs());
    }

    @Test
    public void zeroDuration_doesNotCrashOnSeek() {
        // Defensive: a sniff that produced duration=0 must not divide by zero.
        LinearPsSeekMap m = new LinearPsSeekMap(0L, 0L, null);
        SeekMap.SeekPoints points = m.getSeekPoints(5 * ONE_SEC_US);
        assertEquals(0L, points.first.position);
    }
}
