package sagex.miniclient;

import org.junit.Test;

import sagex.miniclient.ngcontext.NgPlaybackContext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MediaCmdLivePushTest
{
    @Test
    public void preOpenFreeSpaceIsBoundedByActualStagingCapacity()
    {
        assertEquals(4_096, MediaCmd.boundedFreeSpace(4_096, 0));
        assertEquals(3_072, MediaCmd.boundedFreeSpace(4_096, 1_024));
        assertEquals(0, MediaCmd.boundedFreeSpace(4_096, 4_096));
        assertEquals(0, MediaCmd.boundedFreeSpace(4_096, 8_192));
    }

    @Test
    public void liveRecordingExhaustionBuffersInsteadOfEndingPlayback()
    {
        assertTrue(MediaCmd.shouldTreatServerEosAsBuffering(context(true)));
        assertFalse(MediaCmd.shouldTreatServerEosAsBuffering(context(false)));
        assertFalse(MediaCmd.shouldTreatServerEosAsBuffering(null));
    }

    private static NgPlaybackContext context(boolean live)
    {
        return new NgPlaybackContext(
                1, "session", 1, 2, "push", "mpeg2-ts",
                60_000, 10_000, 1,
                new NgPlaybackContext.LiveContext(
                        live, 0, 0, 60_000, 60_000, 1_000, 1),
                null, null, null, null, null, 1);
    }
}
