package sagex.miniclient.android.video.ijkplayer;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IjkPositionEpochTest
{
    @Test
    public void flushHoldsLastPositionUntilNewEpochRenders()
    {
        IjkPositionEpoch epoch = new IjkPositionEpoch();
        epoch.markRendered(726_589L);
        epoch.recordReportedPosition(726_589L);

        epoch.reset(726_589L);

        assertFalse(epoch.isReady());
        assertEquals(726_589L, epoch.getHeldPosition());
        epoch.recordReportedPosition(1_452_917L);
        assertEquals(726_589L, epoch.getHeldPosition());

        epoch.markRendered(328L);
        epoch.recordReportedPosition(726_917L);
        assertTrue(epoch.isReady());
        assertEquals(726_917L, epoch.getHeldPosition());
    }

    @Test
    public void seekRejectsOldEpochRenderCallback()
    {
        IjkPositionEpoch epoch = new IjkPositionEpoch();
        epoch.markRendered(395_084L);
        epoch.recordReportedPosition(395_084L);
        epoch.prepareSeek(427_407L);
        epoch.reset(395_084L);

        assertFalse(epoch.markRendered(395_117L));
        assertFalse(epoch.isReady());
        assertEquals(395_084L, epoch.getHeldPosition());
    }

    @Test
    public void seekAcceptsZeroBasedNewEpoch()
    {
        IjkPositionEpoch epoch = new IjkPositionEpoch();
        epoch.prepareSeek(427_407L);
        epoch.reset(395_084L);

        assertTrue(epoch.markRendered(187L));
        assertTrue(epoch.isReady());
    }

    @Test
    public void seekAcceptsSourceRelativeNewEpoch()
    {
        IjkPositionEpoch epoch = new IjkPositionEpoch();
        epoch.prepareSeek(427_407L);
        epoch.reset(395_084L);

        assertTrue(epoch.markRendered(427_812L));
        assertTrue(epoch.isReady());
    }

    @Test
    public void openClearsPendingSeekTarget()
    {
        IjkPositionEpoch epoch = new IjkPositionEpoch();
        epoch.prepareSeek(427_407L);
        epoch.resetForOpen(0);

        assertTrue(epoch.markRendered(1_000_000L));
    }

    @Test
    public void seekAcceptsZeroWhenPriorRawClockWasAlsoZero()
    {
        IjkPositionEpoch epoch = new IjkPositionEpoch();
        epoch.prepareSeek(3_092_495L);
        epoch.reset(3_050_846L);

        assertTrue(epoch.markRendered(0));
        assertTrue(epoch.isReady());
    }
}
