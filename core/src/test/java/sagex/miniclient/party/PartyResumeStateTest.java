package sagex.miniclient.party;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PartyResumeStateTest {
    @Test
    public void resumeIsLimitedToReconnectGrace() {
        PartyResumeState state = new PartyResumeState();
        state.update("party", "participant", "token", 90_000);
        state.markDisconnected(10_000);

        assertTrue(state.canResume(100_000));
        assertFalse(state.canResume(100_001));
        assertEquals("party", state.getPartyId());
        assertEquals("participant", state.getParticipantId());
        assertEquals("token", state.getRecoveryToken());
    }

    @Test
    public void clearRemovesResumeCredentials() {
        PartyResumeState state = new PartyResumeState();
        state.update("party", "participant", "token", 90_000);
        state.markDisconnected(10_000);
        state.clear();

        assertFalse(state.canResume(10_001));
    }
}
