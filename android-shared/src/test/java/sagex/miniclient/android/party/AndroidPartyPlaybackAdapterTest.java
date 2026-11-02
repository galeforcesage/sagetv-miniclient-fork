package sagex.miniclient.android.party;

import org.junit.Test;

import sagex.miniclient.party.PartyPlaybackAdapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class AndroidPartyPlaybackAdapterTest
{
    @Test
    public void networkBoundaryRequiresAChangedActiveNetworkSnapshot()
    {
        assertNull(AndroidPartyPlaybackAdapter.classifyNetworkBoundary(
                "wifi", "wifi",
                PartyPlaybackAdapter.NetworkBoundary.CAPABILITIES_CHANGED));
        assertEquals(PartyPlaybackAdapter.NetworkBoundary.AVAILABLE,
                AndroidPartyPlaybackAdapter.classifyNetworkBoundary(
                        "", "cell",
                        PartyPlaybackAdapter.NetworkBoundary.AVAILABLE));
        assertEquals(PartyPlaybackAdapter.NetworkBoundary.LOST,
                AndroidPartyPlaybackAdapter.classifyNetworkBoundary(
                        "wifi", "",
                        PartyPlaybackAdapter.NetworkBoundary.LOST));
        assertEquals(PartyPlaybackAdapter.NetworkBoundary.CAPABILITIES_CHANGED,
                AndroidPartyPlaybackAdapter.classifyNetworkBoundary(
                        "wifi", "cell",
                        PartyPlaybackAdapter.NetworkBoundary.AVAILABLE));
        assertEquals(PartyPlaybackAdapter.NetworkBoundary.LINK_PROPERTIES_CHANGED,
                AndroidPartyPlaybackAdapter.classifyNetworkBoundary(
                        "vpn:a", "vpn:b",
                        PartyPlaybackAdapter.NetworkBoundary.LINK_PROPERTIES_CHANGED));
    }
}
