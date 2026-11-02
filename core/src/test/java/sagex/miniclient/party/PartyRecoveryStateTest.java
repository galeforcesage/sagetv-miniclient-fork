package sagex.miniclient.party;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PartyRecoveryStateTest
{
    @Test
    public void resumeRequiresPrivateTokenAndHonorsNinetySecondGrace()
    {
        PartyRecoveryState state = new PartyRecoveryState();
        state.update("party", "participant", "private-token", 90_000);
        assertFalse(state.canResume(1_000));

        state.markDisconnected(10_000);
        assertTrue(state.canResume(100_000));
        assertFalse(state.canResume(100_001));
        assertEquals(PartyRecoveryState.RecoveryAction.REJOIN,
                state.recoveryAction(100_001));
        assertEquals(PartyRecoveryState.RecoveryAction.REJOIN,
                state.recoveryAction(400_000));
        assertEquals(PartyRecoveryState.RecoveryAction.NONE,
                state.recoveryAction(400_001));
    }

    @Test
    public void clearRemovesRecoveryAuthority()
    {
        PartyRecoveryState state = new PartyRecoveryState();
        state.update("party", "participant", "private-token", 90_000);
        state.markDisconnected(10_000);
        state.clear();
        assertFalse(state.canResume(10_001));
    }
}
