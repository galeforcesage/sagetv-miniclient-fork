package sagex.miniclient.android.video.ijkplayer;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IjkPartyBufferingGateTest
{
    @Test
    public void ignoresBufferingSignalWhileRenderedOutputAdvances()
    {
        assertTrue(IjkPartyBufferingGate.renderedOutputStillAdvancing(
                1_000, 1_500, 7, 7, true));
    }

    @Test
    public void acceptsBufferingSignalWhenRenderedOutputStalls()
    {
        assertFalse(IjkPartyBufferingGate.renderedOutputStillAdvancing(
                1_000, 1_050, 7, 7, true));
    }

    @Test
    public void acceptsBufferingSignalAcrossACommandGeneration()
    {
        assertFalse(IjkPartyBufferingGate.renderedOutputStillAdvancing(
                1_000, 2_000, 7, 8, true));
    }

    @Test
    public void requiresConfirmedRendering()
    {
        assertFalse(IjkPartyBufferingGate.renderedOutputStillAdvancing(
                1_000, 2_000, 7, 7, false));
    }

    @Test
    public void restartsConfirmationAfterRenderedPositionReset()
    {
        assertTrue(IjkPartyBufferingGate.renderedPositionDiscontinuous(
                4_600, 200, 7, 7, true));
        assertFalse(IjkPartyBufferingGate.renderedPositionDiscontinuous(
                4_600, 200, 7, 8, true));
    }
}
