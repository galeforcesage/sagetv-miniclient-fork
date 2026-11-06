package sagex.miniclient.party;

import org.json.JSONObject;
import org.junit.Test;

import sagex.miniclient.SageCommand;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
    public void expectedPausedReprimeDoesNotReportBuffering() {
        assertEquals("PAUSED", PartyClient.heartbeatState(
                PartyPlaybackAdapter.PlaybackState.BUFFERING,
                PartyPlaybackAdapter.PlaybackState.PAUSED, true));
        assertEquals("BUFFERING", PartyClient.heartbeatState(
                PartyPlaybackAdapter.PlaybackState.BUFFERING,
                PartyPlaybackAdapter.PlaybackState.PLAYING, true));
        assertEquals("BUFFERING", PartyClient.heartbeatState(
                PartyPlaybackAdapter.PlaybackState.BUFFERING,
                PartyPlaybackAdapter.PlaybackState.PAUSED, false));
    }

    @Test
    public void staleStateVersionsAreIgnored() {
        assertTrue(PartyClient.isStaleStateVersion(7, 6));
        assertFalse(PartyClient.isStaleStateVersion(7, 7));
        assertFalse(PartyClient.isStaleStateVersion(7, 8));
    }

    @Test
    public void readyIsSentOnlyOncePerPreparationTransition() {
        assertTrue(PartyClient.readinessTransitionOpen(
                true, false, false, false));
        assertFalse(PartyClient.readinessTransitionOpen(
                true, false, true, false));
        assertFalse(PartyClient.readinessTransitionOpen(
                true, false, false, true));
        assertFalse(PartyClient.readinessTransitionOpen(
                true, true, false, false));
        assertFalse(PartyClient.readinessTransitionOpen(
                false, false, false, false));
    }

    @Test
    public void readyEchoesCurrentStateAndPreparationGeneration() {
        JSONObject fields = PartyClient.readyFields(
                "party-1", 12, 34, -1);

        assertEquals("party-1", fields.getString("partyId"));
        assertEquals(12, fields.getLong("stateVersion"));
        assertEquals(34, fields.getLong("playbackGeneration"));
        assertEquals(0, fields.getLong("bufferAheadMs"));
    }

    @Test
    public void heartbeatStartsOnlyAfterReadinessHandshake() {
        assertFalse(PartyClient.canSendHeartbeat(false, null));
        assertFalse(PartyClient.canSendHeartbeat(false, "PAUSED"));
        assertTrue(PartyClient.canSendHeartbeat(true, "PAUSED"));
        assertTrue(PartyClient.canSendHeartbeat(false, "STOPPED"));
    }

    @Test
    public void notPreparedIsTransientOnlyDuringPreparation() {
        assertTrue(PartyClient.isTransientNotPrepared(
                "NOT_PREPARED", true, "PREPARING"));
        assertTrue(PartyClient.isTransientNotPrepared(
                "NOT_PREPARED", false, "PREPARING"));
        assertFalse(PartyClient.isTransientNotPrepared(
                "NOT_PREPARED", false, "READYING"));
        assertFalse(PartyClient.isTransientNotPrepared(
                "NOT_READY", true, "PREPARING"));
    }

    @Test
    public void readinessRequiresPreparedAndActivatedPlayback() {
        assertTrue(PartyClient.isActivationReady(new PartyPlaybackAdapter.Observation(
                1, 2, 3, 4, PartyPlaybackAdapter.PlaybackState.PAUSED,
                true, true, 50)));
        assertFalse(PartyClient.isActivationReady(new PartyPlaybackAdapter.Observation(
                1, 2, 3, 4, PartyPlaybackAdapter.PlaybackState.PAUSED,
                true, false, 50)));
    }

    @Test
    public void requestEnvelopeAuthorizesEverythingExceptHello() {
        JSONObject hello = PartyClient.requestEnvelope(
                "HELLO", "hello-1", 1, null, null, new JSONObject());
        JSONObject action = PartyClient.requestEnvelope(
                "ACTION", "action-1", 2, "epoch", "token",
                new JSONObject().put("partyId", "party")
                        .put("stateVersion", 3)
                        .put("action", "PLAY"));

        assertFalse(hello.has("serverEpoch"));
        assertFalse(hello.has("sessionToken"));
        assertEquals(1, hello.getLong("requestSeq"));
        assertEquals("epoch", action.getString("serverEpoch"));
        assertEquals("token", action.getString("sessionToken"));
        assertEquals(2, action.getLong("requestSeq"));
    }

    @Test
    public void joinCodeEventCompletesRotationWithoutPretendingToBeAReply() {
        assertTrue(PartyClient.completesRequest(
                "JOIN_CODE", "rotate-1", "JOIN_CODE", null));
        assertTrue(PartyClient.completesRequest(
                "ACTION", "action-1", "COMMAND", null));
        assertFalse(PartyClient.completesRequest(
                "PREPARE", "prepare-1", "PREPARATION_RESULT", null));
    }

    @Test
    public void sharedControlsExcludeLocalOnlyCommands() {
        assertEquals("PLAY", PartyClient.sharedActionFor(
                SageCommand.PLAY, PartyPlaybackAdapter.PlaybackState.PAUSED));
        assertEquals("PAUSE", PartyClient.sharedActionFor(
                SageCommand.PLAY_PAUSE,
                PartyPlaybackAdapter.PlaybackState.PLAYING));
        assertEquals("FF", PartyClient.sharedActionFor(
                SageCommand.FF, PartyPlaybackAdapter.PlaybackState.PLAYING));
        assertEquals("REW", PartyClient.sharedActionFor(
                SageCommand.REW, PartyPlaybackAdapter.PlaybackState.PLAYING));
        assertEquals("FF2", PartyClient.sharedActionFor(
                SageCommand.FF_2, PartyPlaybackAdapter.PlaybackState.PLAYING));
        assertEquals("REW2", PartyClient.sharedActionFor(
                SageCommand.REW_2, PartyPlaybackAdapter.PlaybackState.PLAYING));
        assertEquals("PAUSE", PartyClient.sharedActionFor(
                SageCommand.PAUSE,
                PartyPlaybackAdapter.PlaybackState.BUFFERING));
        assertNull(PartyClient.sharedActionFor(
                SageCommand.VOLUME_UP,
                PartyPlaybackAdapter.PlaybackState.PLAYING));
        assertNull(PartyClient.sharedActionFor(
                SageCommand.DVD_SUBTITLE_TOGGLE,
                PartyPlaybackAdapter.PlaybackState.PLAYING));
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
