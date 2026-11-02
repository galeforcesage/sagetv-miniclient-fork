package sagex.miniclient.party;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PartyMediaWindowTest
{
    @Test
    public void clampsTargetsBuffersAndReadinessToPlayableEdge()
    {
        PartyMediaWindow window = new PartyMediaWindow();
        assertTrue(window.update(windowMessage(true, 120000, 115000, 5000, 4)));

        assertEquals(115000, window.clampTarget(119000));
        assertEquals(3000, window.clampBufferAhead(112000, 8000));
        assertEquals(3000, window.remainingPlayableMs(112000, 999999));
        assertTrue(window.isMediaGrowing());
        assertEquals(120000, window.getAvailableEndMs());
        assertEquals(5000, window.getLiveEdgeDelayMs());
    }

    @Test
    public void completedTransitionRetainsAuthoritativeWindow()
    {
        PartyMediaWindow window = new PartyMediaWindow();
        assertTrue(window.update(windowMessage(true, 120000, 115000, 5000, 4)));
        assertTrue(window.update(windowMessage(false, 121000, 121000, 0, 5)));

        assertFalse(window.isMediaGrowing());
        assertEquals(121000, window.getPlayableEndMs());
        assertEquals(1000, window.remainingPlayableMs(120000, 121000));
    }

    @Test
    public void positionAcknowledgementCanAdvanceWindowAtSameStateVersion()
    {
        PartyMediaWindow window = new PartyMediaWindow();
        assertTrue(window.update(windowMessage(true, 120000, 115000, 5000, 4)));
        assertTrue(window.update(windowMessage(true, 130000, 125000, 5000, 4)));

        assertEquals(125000, window.getPlayableEndMs());
        assertEquals(5000, window.remainingPlayableMs(120000, 120000));
    }

    @Test
    public void ignoresStaleWindowAndRejectsMalformedTuple()
    {
        PartyMediaWindow window = new PartyMediaWindow();
        assertTrue(window.update(windowMessage(true, 120000, 115000, 5000, 4)));
        assertTrue(window.update(windowMessage(true, 90000, 85000, 5000, 3)));
        assertEquals(115000, window.getPlayableEndMs());

        assertFalse(window.update(new JSONObject()
                .put("mediaGrowing", true)
                .put("availableEndMs", 120000)
                .put("playableEndMs", 115000)));
        assertFalse(window.update(windowMessage(true, 110000, 115000, 5000, 5)));
    }

    private static JSONObject windowMessage(boolean growing, long available,
                                            long playable, long delay, long version)
    {
        return new JSONObject()
                .put("mediaGrowing", growing)
                .put("availableEndMs", available)
                .put("playableEndMs", playable)
                .put("liveEdgeDelayMs", delay)
                .put("stateVersion", version);
    }
}
