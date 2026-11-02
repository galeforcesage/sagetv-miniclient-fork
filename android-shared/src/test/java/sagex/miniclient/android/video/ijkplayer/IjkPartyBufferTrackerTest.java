package sagex.miniclient.android.video.ijkplayer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class IjkPartyBufferTrackerTest
{
    @Test
    public void usesMinimumActiveTrackAndSafetyMargin()
    {
        IjkPartyBufferTracker tracker = new IjkPartyBufferTracker();

        assertEquals(-1, tracker.sample(1000, 2200, 1900, true, true));
        assertEquals(1650, tracker.sample(1150, 2250, 1900, true, true));
    }

    @Test
    public void usesSoleActiveTrack()
    {
        IjkPartyBufferTracker tracker = new IjkPartyBufferTracker();

        assertEquals(-1, tracker.sample(1000, 1800, -1, true, false));
        assertEquals(1600, tracker.sample(1200, 1850, -1, true, false));
    }

    @Test
    public void resetRequiresTwoFreshSamples()
    {
        IjkPartyBufferTracker tracker = new IjkPartyBufferTracker();

        assertEquals(-1, tracker.sample(1000, 2000, 2000, true, true));
        assertEquals(1750, tracker.sample(1200, 2000, 2000, true, true));
        tracker.reset();
        assertEquals(-1, tracker.sample(1300, 2000, 2000, true, true));
        assertEquals(1750, tracker.sample(1450, 2000, 2000, true, true));
    }

    @Test
    public void rejectsNegativeImplausibleAndDiscontinuousSamples()
    {
        IjkPartyBufferTracker tracker = new IjkPartyBufferTracker();

        assertEquals(-1, tracker.sample(1000, -1, 2000, true, true));
        assertEquals(-1, tracker.sample(1200, 3700000, -1, true, false));
        assertEquals(-1, tracker.sample(1400, 2000, -1, true, false));
        assertEquals(-1, tracker.sample(1600, 200000, -1, true, false));
        assertEquals(199750, tracker.sample(1800, 200000, -1, true, false));
    }
}
