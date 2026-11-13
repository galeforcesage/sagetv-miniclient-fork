package sagex.miniclient;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MiniClientConnectionCapabilityTest
{
    @After
    public void resetNegotiatedFlags()
    {
        MiniClientConnection.trickplayPositionV1Negotiated = false;
        MiniClientConnection.dvrWindowV1Negotiated = false;
    }

    @Test
    public void aggregateCapabilityRoundEnablesAdvertisedContracts()
    {
        assertFalse(MiniClientConnection.trickplayPositionV1Negotiated);
        assertFalse(MiniClientConnection.dvrWindowV1Negotiated);

        MiniClientConnection.markAggregateCapabilitiesNegotiated(true, true);

        assertTrue(MiniClientConnection.trickplayPositionV1Negotiated);
        assertTrue(MiniClientConnection.dvrWindowV1Negotiated);
    }

    @Test
    public void absentAggregateCapabilitiesRemainLegacy()
    {
        MiniClientConnection.markAggregateCapabilitiesNegotiated(false, false);

        assertFalse(MiniClientConnection.trickplayPositionV1Negotiated);
        assertFalse(MiniClientConnection.dvrWindowV1Negotiated);
    }
}
