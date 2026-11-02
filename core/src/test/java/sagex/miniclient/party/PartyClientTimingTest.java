package sagex.miniclient.party;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PartyClientTimingTest
{
    @Test
    public void correctionObservationEndsBeforeServerExpiry()
    {
        assertEquals(8_000, PartyClient.CORRECTION_EXECUTION_TIMEOUT_MS);
        assertTrue(PartyClient.CORRECTION_EXECUTION_TIMEOUT_MS < 12_000);
    }

    @Test
    public void combinedSyncUsesContractCadence()
    {
        assertEquals(5, PartyClient.CLOCK_CALIBRATION_SAMPLE_COUNT);
        assertEquals(10_000, PartyClient.SYNC_PLAYING_INTERVAL_MS);
        assertEquals(30_000, PartyClient.SYNC_PAUSED_INTERVAL_MS);
        assertEquals(2_000, PartyClient.SYNC_RECOVERY_INTERVAL_MS);
        assertEquals(300_000, PartyClient.HELLO_REFRESH_INTERVAL_MS);
        assertEquals(PartyClient.SYNC_PLAYING_INTERVAL_MS,
                PartyClient.syncIntervalMs("PLAYING",
                        PartyPlaybackAdapter.PlaybackState.PLAYING));
        assertEquals(PartyClient.SYNC_PAUSED_INTERVAL_MS,
                PartyClient.syncIntervalMs("PAUSED",
                        PartyPlaybackAdapter.PlaybackState.PAUSED));
        assertEquals(PartyClient.SYNC_RECOVERY_INTERVAL_MS,
                PartyClient.syncIntervalMs("BUFFERING",
                        PartyPlaybackAdapter.PlaybackState.PLAYING));
    }

    @Test
    public void syncProbeReplyUsesImmediateMonotonicTimestamps()
    {
        JSONObject fields = PartyClient.clockReplyFields(
                "party-1", "probe-1", 12_345, 12_347);

        assertEquals("party-1", fields.getString("partyId"));
        assertEquals("probe-1", fields.getString("probeId"));
        assertEquals(12_345, fields.getLong("c1"));
        assertEquals(12_347, fields.getLong("c2"));
    }

    @Test
    public void pushCorrectionWaitsForCommandBurstToSettle()
    {
        assertFalse(PartyClient.commandGenerationSettled(false, 1_000, 2_000));
        assertFalse(PartyClient.commandGenerationSettled(true, 1_000,
                1_000 + PartyClient.PUSH_COMMAND_SETTLE_MS - 1));
        assertTrue(PartyClient.commandGenerationSettled(true, 1_000,
                1_000 + PartyClient.PUSH_COMMAND_SETTLE_MS));
    }

    @Test
    public void pushTimelineAnchorsOnlyAfterRenderedPlaybackResumes()
    {
        PartyPlaybackAdapter.Observation buffering =
                new PartyPlaybackAdapter.Observation(
                        1_000, 0, 5_000, 10_000,
                        PartyPlaybackAdapter.PlaybackState.BUFFERING,
                        true, true, 125);
        PartyPlaybackAdapter.Observation playing =
                new PartyPlaybackAdapter.Observation(
                        1_500, 250, 5_000, 10_000,
                        PartyPlaybackAdapter.PlaybackState.PLAYING,
                        true, true, 125);

        assertFalse(PartyClient.shouldAnchorPushTimeline(
                true, false, false, 0,
                PartyPlaybackAdapter.PlaybackState.PLAYING, playing));
        assertFalse(PartyClient.shouldAnchorPushTimeline(
                true, true, false, 0,
                PartyPlaybackAdapter.PlaybackState.PLAYING, buffering));
        assertTrue(PartyClient.shouldAnchorPushTimeline(
                true, true, false, 0,
                PartyPlaybackAdapter.PlaybackState.PLAYING, playing));
        assertFalse(PartyClient.shouldAnchorPushTimeline(
                true, true, true, 0,
                PartyPlaybackAdapter.PlaybackState.PLAYING, playing));
        assertFalse(PartyClient.shouldAnchorPushTimeline(
                false, true, false, 0,
                PartyPlaybackAdapter.PlaybackState.PLAYING, playing));
        assertFalse(PartyClient.shouldAnchorPushTimeline(
                true, true, false, 250,
                PartyPlaybackAdapter.PlaybackState.PLAYING, playing));
    }

    @Test
    public void matchingCorrectionSupersedesPendingCommand()
    {
        JSONObject command = new JSONObject()
                .put("commandSeq", 7)
                .put("stateVersion", 3)
                .put("targetState", "PLAYING");
        JSONObject correction = new JSONObject(command.toString())
                .put("kind", "HARD")
                .put("targetPositionMs", 4_000);

        assertTrue(PartyClient.correctionSupersedesCommand(command, correction));
        assertFalse(PartyClient.correctionSupersedesCommand(command,
                new JSONObject(correction.toString()).put("commandSeq", 8)));
        assertFalse(PartyClient.correctionSupersedesCommand(command,
                new JSONObject(correction.toString()).put("targetState", "PAUSED")));
    }

    @Test
    public void playingTargetProjectsToTheObservationTime()
    {
        assertEquals(8_250, PartyClient.projectPlayingTarget(
                5_000, 100_000, 98_000, 5_250));
    }

    @Test
    public void playingTargetNeverProjectsBackward()
    {
        assertEquals(5_000, PartyClient.projectPlayingTarget(
                5_000, 100_000, 90_000, 5_000));
    }

    @Test
    public void blockedControlPreservesPreparationBarrier()
    {
        assertEquals("PREPARING", PartyClient.blockedControlStatus(
                "PREPARING", false, false, false));
        assertEquals("REJOINING", PartyClient.blockedControlStatus(
                "PREPARING", true, false, false));
        assertEquals("SYNCHRONIZING", PartyClient.blockedControlStatus(
                "PREPARING", false, true, false));
    }

    @Test
    public void clockSuspectStateIsNotDefinitiveRecovery()
    {
        JSONObject state = new JSONObject()
                .put("status", "PAUSED")
                .put("syncState", "SUSPECT")
                .put("reason", "CLOCK_UNCERTAIN");

        assertTrue(PartyClient.isClockSuspect(state));
        assertFalse(PartyClient.isDefinitiveRecoveryStatus(
                state.getString("status")));
        assertTrue(PartyClient.isDefinitiveRecoveryStatus("RECOVERING"));
        assertTrue(PartyClient.isDefinitiveRecoveryStatus("DEGRADED"));
    }

    @Test
    public void nonConfidentPositionAckRemainsAHeartbeat()
    {
        JSONObject ack = new JSONObject()
                .put("type", "ACK")
                .put("clockConfident", false);

        assertTrue(PartyClient.isClockUncertainPositionAck(ack));
        assertFalse(PartyClient.isClockUncertainPositionAck(
                new JSONObject().put("type", "ERROR")
                        .put("code", "CLOCK_UNCERTAIN")));
    }

    @Test
    public void removedMemberErrorsDiscardLocalMembership()
    {
        assertTrue(PartyClient.isMembershipTerminalError("NOT_MEMBER"));
        assertTrue(PartyClient.isMembershipTerminalError("PREPARATION_EXPIRED"));
        assertFalse(PartyClient.isMembershipTerminalError("PREPARATION_FAILED"));
    }

    @Test
    public void renderedOutputMustAdvanceAcrossTheStallWindow()
    {
        assertFalse(PartyClient.renderedOutputStalled(999, 0));
        assertFalse(PartyClient.renderedOutputStalled(1_000, 100));
        assertTrue(PartyClient.renderedOutputStalled(1_000, 99));
        assertFalse(PartyClient.renderedOutputStalled(1_000, -1));
        assertFalse(PartyClient.renderedOutputAdvancing(999, 1_000));
        assertTrue(PartyClient.renderedOutputAdvancing(1_000, 100));
        assertFalse(PartyClient.renderedOutputAdvancing(1_000, -1));
    }

    @Test
    public void bufferingRecoveryAllowsUnknownNativeBufferWhileOutputAdvances()
    {
        assertTrue(PartyClient.hasRecoveryBuffer(-1, 1_500));
        assertFalse(PartyClient.hasRecoveryBuffer(1_499, 1_500));
        assertTrue(PartyClient.hasRecoveryBuffer(1_500, 1_500));
    }

    @Test
    public void correctionWithoutIdHasStableDeduplicationKey()
    {
        JSONObject correction = new JSONObject()
                .put("commandSeq", 12)
                .put("stateVersion", 7)
                .put("targetPositionMs", 5_000)
                .put("targetState", "PLAYING");

        assertEquals(PartyClient.correctionKey(correction, 0),
                PartyClient.correctionKey(new JSONObject(correction.toString()), 99));
        assertEquals("server-id", PartyClient.correctionKey(
                new JSONObject().put("correctionId", "server-id"), 99));
    }

    @Test
    public void scheduledCommandsDefaultToExactSeek()
    {
        PartyPlaybackAdapter.ScheduledCommand command =
                new PartyPlaybackAdapter.ScheduledCommand(1, 2, "CORRECTION",
                        3_000, PartyPlaybackAdapter.PlaybackState.PLAYING,
                        1.0, 4_000);

        assertEquals(PartyPlaybackAdapter.SeekAccuracy.EXACT, command.seekAccuracy);
    }

    @Test
    public void correctionsAreHardOnly()
    {
        assertTrue(PartyClient.isHardCorrection(new JSONObject()
                .put("kind", "HARD")
                .put("targetRate", 1)
                .put("durationMs", 0)));
        assertFalse(PartyClient.isHardCorrection(new JSONObject()
                .put("kind", "SOFT")
                .put("targetRate", 1.01)
                .put("durationMs", 5000)));
        assertFalse(PartyClient.isHardCorrection(new JSONObject()
                .put("kind", "HARD")
                .put("targetRate", "1")
                .put("durationMs", 0)));
    }

    @Test
    public void deliveryAndCapacityRequireExactNumericFields()
    {
        JSONObject delivery = new JSONObject()
                .put("surface", "android")
                .put("mode", "push")
                .put("videoCodec", "MPEG2")
                .put("audioCodec", "AC3")
                .put("plannedReservationKbps", 8000)
                .put("selectedBitrateKbps", 4000)
                .put("transcode", false);
        assertTrue(PartyClient.consumeDelivery(delivery) != null);
        assertTrue(PartyClient.consumeDelivery(new JSONObject(delivery.toString())
                .put("plannedReservationKbps", "8000")) == null);

        JSONObject capacity = new JSONObject()
                .put("plannedReservationKbps", 8000)
                .put("plannedRemoteEgressKbps", 0)
                .put("selectedOutputKbps", 4000)
                .put("actualOutputKbps", 3500)
                .put("activeTranscodes", 1)
                .put("maxReservationKbps", 100000)
                .put("maxTranscodes", 2);
        assertTrue(PartyClient.consumeCapacity(capacity) != null);
        assertTrue(PartyClient.consumeCapacity(new JSONObject(capacity.toString())
                .put("actualOutputKbps", "3500")) == null);
    }
}
