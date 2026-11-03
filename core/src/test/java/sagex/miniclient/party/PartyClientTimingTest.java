package sagex.miniclient.party;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PartyClientTimingTest {
    @Test
    public void replacementContractUsesFiveClockSamples() {
        assertEquals(5, PartyClient.CLOCK_CALIBRATION_SAMPLE_COUNT);
    }

    @Test
    public void clockReplyUsesImmediateMonotonicTimestamps() {
        JSONObject fields = PartyClient.clockReplyFields(
                "party-1", "probe-1", 12_345, 12_347);

        assertEquals("party-1", fields.getString("partyId"));
        assertEquals("probe-1", fields.getString("probeId"));
        assertEquals(12_345, fields.getLong("c1"));
        assertEquals(12_347, fields.getLong("c2"));
    }

    @Test
    public void heartbeatCadenceMatchesPlaybackState() {
        assertEquals(1_000,
                PartyClient.heartbeatIntervalMs("PLAYING", false));
        assertEquals(1_000,
                PartyClient.heartbeatIntervalMs("BUFFERING", false));
        assertEquals(1_000,
                PartyClient.heartbeatIntervalMs("PAUSED", true));
        assertEquals(5_000,
                PartyClient.heartbeatIntervalMs("PAUSED", false));
    }

    @Test
    public void staleStateVersionsAreIgnored() {
        assertTrue(PartyClient.isStaleStateVersion(7, 6));
        assertFalse(PartyClient.isStaleStateVersion(7, 7));
        assertFalse(PartyClient.isStaleStateVersion(7, 8));
    }

    @Test
    public void scheduledCommandCarriesConvertedMonotonicDeadline() {
        PartyPlaybackAdapter.ScheduledCommand command =
                new PartyPlaybackAdapter.ScheduledCommand(
                        3, 4, "PLAY", 5_000,
                        PartyPlaybackAdapter.PlaybackState.PLAYING,
                        1.0, 100_645);

        assertEquals(100_645, command.executeAtClientMonoMs);
        assertEquals(5_000, command.targetPositionMs);
        assertEquals(PartyPlaybackAdapter.PlaybackState.PLAYING,
                command.targetState);
    }
}
