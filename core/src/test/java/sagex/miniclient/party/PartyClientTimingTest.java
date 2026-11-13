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
    public void joinedClockIsRefreshedEveryTwentySeconds() {
        assertEquals(20_000, PartyClient.CLOCK_REFRESH_INTERVAL_MS);
        assertFalse(PartyClient.clockRefreshDue(-1, 20_000, 5, false));
        assertFalse(PartyClient.clockRefreshDue(1_000, 20_999, 5, false));
        assertFalse(PartyClient.clockRefreshDue(1_000, 21_000, 4, false));
        assertFalse(PartyClient.clockRefreshDue(1_000, 21_000, 5, true));
        assertTrue(PartyClient.clockRefreshDue(1_000, 21_000, 5, false));
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
        assertEquals(5_000,
                PartyClient.heartbeatIntervalMs("PLAYING", false));
        assertEquals(5_000,
                PartyClient.heartbeatIntervalMs("BUFFERING", false));
        assertEquals(5_000,
                PartyClient.heartbeatIntervalMs("PAUSED", true));
        assertEquals(15_000,
                PartyClient.heartbeatIntervalMs("PAUSED", false));
    }

    @Test
    public void heartbeatCadenceUsesSendTimeNotFrameTime() {
        assertTrue(PartyClient.heartbeatDue(0, 10_000, 5_000));
        assertFalse(PartyClient.heartbeatDue(10_000, 14_999, 5_000));
        assertTrue(PartyClient.heartbeatDue(10_000, 15_000, 5_000));
    }

    @Test
    public void screenMeasurementsWaitForPresentationActivation() {
        assertFalse(PartyClient.canSendMeasurementHeartbeat(
                true, false, true, null));
        assertFalse(PartyClient.canSendMeasurementHeartbeat(
                true, true, true, null));
        assertTrue(PartyClient.canSendMeasurementHeartbeat(
                true, true, false, null));
        assertFalse(PartyClient.canSendMeasurementHeartbeat(
                true, false, true, "BUFFERING"));
        assertTrue(PartyClient.canSendMeasurementHeartbeat(
                false, false, false, "STOPPED"));
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
    public void preparationFailureMustKeepPartyOutputSuppressed() {
        assertTrue(PartyClient.playbackHoldAfterPreparationFailure());
    }

    @Test
    public void staleStateVersionsAreIgnored() {
        assertTrue(PartyClient.isStaleStateVersion(7, 6));
        assertFalse(PartyClient.isStaleStateVersion(7, 7));
        assertFalse(PartyClient.isStaleStateVersion(7, 8));
    }

    @Test
    public void partyScopedCountersResetForNewMembership() {
        assertEquals(0, PartyClient.membershipScopedCounter(
                null, "new-party", 4));
        assertEquals(0, PartyClient.membershipScopedCounter(
                "old-party", "new-party", 4));
        assertEquals(4, PartyClient.membershipScopedCounter(
                "same-party", "same-party", 4));
    }

    @Test
    public void readyIsSentOnlyOncePerPreparationTransition() {
        assertTrue(PartyClient.readinessTransitionOpen(
                true, false, false, false, false, false, false));
        assertFalse(PartyClient.readinessTransitionOpen(
                true, false, true, false, false, false, false));
        assertFalse(PartyClient.readinessTransitionOpen(
                true, false, false, true, false, false, false));
        assertFalse(PartyClient.readinessTransitionOpen(
                true, true, false, false, false, false, false));
        assertFalse(PartyClient.readinessTransitionOpen(
                false, false, false, false, false, false, false));
        assertFalse(PartyClient.readinessTransitionOpen(
                true, false, false, false, true, false, false));
        assertFalse(PartyClient.readinessTransitionOpen(
                true, false, false, false, false, true, false));
        assertFalse(PartyClient.readinessTransitionOpen(
                true, false, false, false, false, false, true));
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
    public void correctionKindIsStrict() {
        assertEquals(PartyPlaybackAdapter.CorrectionKind.NONE,
                PartyClient.correctionKind(new JSONObject().put("kind", "NONE"), true));
        assertEquals(PartyPlaybackAdapter.CorrectionKind.SOFT,
                PartyClient.correctionKind(new JSONObject().put("kind", "SOFT"), true));
        assertEquals(PartyPlaybackAdapter.CorrectionKind.HARD,
                PartyClient.correctionKind(new JSONObject().put("kind", "HARD"), true));
        assertEquals(PartyPlaybackAdapter.CorrectionKind.NONE,
                PartyClient.correctionKind(new JSONObject(), false));
        assertEquals(null,
                PartyClient.correctionKind(new JSONObject(), true));
    }

    @Test
    public void directiveRequiresExactCorrelationAndSchedulingFields() {
        JSONObject initialCorrection = new JSONObject()
                .put("stateVersion", 3)
                .put("commandSeq", 0)
                .put("directiveId", "directive-0")
                .put("targetPositionMs", 120_000)
                .put("targetState", "PLAYING")
                .put("targetRate", 1)
                .put("executeAtServerMonoMs", 500_750);
        JSONObject missingDirectiveId =
                new JSONObject(initialCorrection.toString());
        missingDirectiveId.remove("directiveId");

        assertTrue(PartyClient.hasRequiredDirectiveFields(initialCorrection));
        assertFalse(PartyClient.hasRequiredDirectiveFields(missingDirectiveId));
        assertFalse(PartyClient.hasRequiredDirectiveFields(
                new JSONObject(initialCorrection.toString())
                        .put("directiveId", "")));
    }

    @Test
    public void barrierDirectiveRequiresCompletePrepareFields() {
        JSONObject barrier = new JSONObject()
                .put("stateVersion", 4)
                .put("commandSeq", 8)
                .put("directiveId", "barrier-8")
                .put("targetPositionMs", 120_000)
                .put("targetState", "PAUSED")
                .put("targetRate", 1)
                .put("executeAtServerMonoMs", 497_000)
                .put("phase", "PREPARE")
                .put("barrier", true)
                .put("visibleAtServerMonoMs", 500_750)
                .put("predictedPresentationLatencyMs", 3_450)
                .put("presentationUncertaintyMs", 300);
        JSONObject missingVisibleDeadline =
                new JSONObject(barrier.toString());
        missingVisibleDeadline.remove("visibleAtServerMonoMs");

        assertTrue(PartyClient.hasRequiredDirectiveFields(barrier));
        assertFalse(PartyClient.hasRequiredDirectiveFields(
                missingVisibleDeadline));
        assertFalse(PartyClient.hasRequiredDirectiveFields(
                new JSONObject(barrier.toString())
                        .put("phase", "REVEAL")));
    }

    @Test
    public void directiveAdmissionMatchesParticipantLifecycle() {
        assertTrue(PartyClient.canExecuteDirective(
                true, PartyPlaybackAdapter.PlaybackState.PAUSED,
                true, true, false, true));
        assertFalse(PartyClient.canExecuteDirective(
                true, PartyPlaybackAdapter.PlaybackState.PAUSED,
                true, false, false, true));
        assertTrue(PartyClient.canExecuteDirective(
                true, PartyPlaybackAdapter.PlaybackState.PLAYING,
                true, true, true, false));
        assertTrue(PartyClient.canExecuteDirective(
                false, PartyPlaybackAdapter.PlaybackState.PAUSED,
                true, false, false, false));
        assertFalse(PartyClient.canExecuteDirective(
                false, PartyPlaybackAdapter.PlaybackState.PLAYING,
                true, true, false, true));
        assertTrue(PartyClient.canExecuteDirective(
                false, PartyPlaybackAdapter.PlaybackState.PLAYING,
                true, true, true, false));
        assertFalse(PartyClient.canExecuteDirective(
                false, PartyPlaybackAdapter.PlaybackState.PAUSED,
                false, false, true, true));
    }

    @Test
    public void playbackCallbacksCannotPublishStableStateDuringSettlement() {
        assertTrue(PartyClient.canPublishStablePlaybackState(false, false));
        assertFalse(PartyClient.canPublishStablePlaybackState(true, false));
        assertFalse(PartyClient.canPublishStablePlaybackState(false, true));
    }

    @Test
    public void playbackRecoveryRetriesPresentedDuringRevealSettlement() {
        assertTrue(PartyClient.shouldRetryPresentedAfterPlaybackStateChange(
                true, true, true));
        assertFalse(PartyClient.shouldRetryPresentedAfterPlaybackStateChange(
                false, true, true));
        assertFalse(PartyClient.shouldRetryPresentedAfterPlaybackStateChange(
                true, false, true));
        assertFalse(PartyClient.shouldRetryPresentedAfterPlaybackStateChange(
                true, true, false));
    }

    @Test
    public void directiveCannotReplaceOneStillSettling() {
        assertTrue(PartyClient.canAcceptDirective(false));
        assertFalse(PartyClient.canAcceptDirective(true));
    }

    @Test
    public void acceptedDirectiveCompletionUsesDirectiveGeneration() {
        assertTrue(PartyClient.isCurrentDirectiveExecution(4, 4, false));
        assertFalse(PartyClient.isCurrentDirectiveExecution(4, 5, false));
        assertFalse(PartyClient.isCurrentDirectiveExecution(4, 4, true));
    }

    @Test
    public void readinessResetCannotCancelExecutingDirective() {
        assertFalse(PartyClient.shouldCancelOnReadinessLoss(false, true));
        assertFalse(PartyClient.shouldCancelOnReadinessLoss(true, false));
        assertTrue(PartyClient.shouldCancelOnReadinessLoss(false, false));
    }

    @Test
    public void revealWaitsForArmedAckReply() {
        assertTrue(PartyClient.shouldDeferReveal(true, true));
        assertFalse(PartyClient.shouldDeferReveal(true, false));
        assertFalse(PartyClient.shouldDeferReveal(false, true));
    }

    @Test
    public void preparationResultWaitsForPrepareAcceptance() {
        assertTrue(PartyClient.shouldDeferPreparationResult(
                true, null, "prepare-1"));
        assertFalse(PartyClient.shouldDeferPreparationResult(
                false, null, "prepare-1"));
        assertFalse(PartyClient.shouldDeferPreparationResult(
                true, "prepare-1", "prepare-1"));
        assertFalse(PartyClient.shouldDeferPreparationResult(
                true, null, null));
    }

    @Test
    public void availabilityRefreshPreservesJoinedState() {
        assertTrue(PartyClient.shouldReplaceStatusOnAvailable(false));
        assertFalse(PartyClient.shouldReplaceStatusOnAvailable(true));
    }

    @Test
    public void partyEventsRequireCurrentMembership() {
        assertTrue(PartyClient.partyEventMatches("party-1", "party-1"));
        assertFalse(PartyClient.partyEventMatches("party-1", "party-2"));
        assertFalse(PartyClient.partyEventMatches(null, "party-1"));
        assertFalse(PartyClient.partyEventMatches("party-1", null));
    }

    @Test
    public void visibleFrameCanCompleteAsynchronousPlayerExecution() {
        assertTrue(PartyClient.shouldCompleteExecutionFromPresentation(
                true, false, PartyPlaybackAdapter.PlaybackState.PAUSED));
        assertFalse(PartyClient.shouldCompleteExecutionFromPresentation(
                false, false, PartyPlaybackAdapter.PlaybackState.PAUSED));
        assertFalse(PartyClient.shouldCompleteExecutionFromPresentation(
                true, true, PartyPlaybackAdapter.PlaybackState.PAUSED));
        assertFalse(PartyClient.shouldCompleteExecutionFromPresentation(
                true, false, PartyPlaybackAdapter.PlaybackState.PLAYING));
    }

    @Test
    public void presentedObservationMustMatchDirectiveStateAndPosition() {
        PartyPlaybackAdapter.ScheduledCommand play =
                command(PartyPlaybackAdapter.PlaybackState.PLAYING);
        assertTrue(PartyClient.isPresentedObservationFor(
                play, observation(2_100, 1_100,
                        PartyPlaybackAdapter.PlaybackState.PLAYING, true)));
        assertFalse(PartyClient.isPresentedObservationFor(
                play, observation(2_100, 1_100,
                        PartyPlaybackAdapter.PlaybackState.PAUSED, true)));
        assertFalse(PartyClient.isPresentedObservationFor(
                play, observation(2_100, 1_100,
                        PartyPlaybackAdapter.PlaybackState.BUFFERING, true)));
        assertFalse(PartyClient.isPresentedObservationFor(
                play, observation(2_100, 1_100,
                        PartyPlaybackAdapter.PlaybackState.PLAYING, false)));
        assertFalse(PartyClient.isPresentedObservationFor(
                play, observation(2_100, 7_300,
                        PartyPlaybackAdapter.PlaybackState.PLAYING, true)));
        assertFalse(PartyClient.isPresentedObservationFor(
                play, observation(1_900, 1_000,
                        PartyPlaybackAdapter.PlaybackState.PLAYING, true)));
    }

    @Test
    public void armedObservationMustBePausedAtBarrierTarget() {
        PartyPlaybackAdapter.ScheduledCommand barrier =
                new PartyPlaybackAdapter.ScheduledCommand(
                        "barrier-1", 1, 1, "CORRECTION",
                        PartyPlaybackAdapter.CorrectionKind.HARD,
                        10_000,
                        PartyPlaybackAdapter.PlaybackState.PLAYING,
                        1.0, 2_000, true, "PREPARE",
                        3_000, 500, 300);

        assertTrue(PartyClient.isArmedObservationFor(
                barrier, observation(2_500, 10_200,
                        PartyPlaybackAdapter.PlaybackState.PAUSED, true)));
        assertFalse(PartyClient.isArmedObservationFor(
                barrier, observation(2_500, 3_800,
                        PartyPlaybackAdapter.PlaybackState.PAUSED, true)));
        assertFalse(PartyClient.isArmedObservationFor(
                barrier, observation(2_500, 10_200,
                        PartyPlaybackAdapter.PlaybackState.PLAYING, true)));
    }

    @Test
    public void staleHeartbeatSampleIsNonFatalTelemetry() {
        assertTrue(PartyClient.isNonFatalTelemetryError(
                "STALE_SAMPLE", "HEARTBEAT"));
        assertFalse(PartyClient.isNonFatalTelemetryError(
                "STALE_SAMPLE", "ACK"));
        assertFalse(PartyClient.isNonFatalTelemetryError(
                "INVALID_PRESENTATION", "HEARTBEAT"));
    }

    @Test
    public void settledParticipantIgnoresRendererReadinessReset() {
        assertTrue(PartyClient.shouldPreserveSettledReadiness(
                true, true, false, false));
        assertFalse(PartyClient.shouldPreserveSettledReadiness(
                false, true, false, false));
        assertFalse(PartyClient.shouldPreserveSettledReadiness(
                true, true, true, false));
        assertFalse(PartyClient.shouldPreserveSettledReadiness(
                true, true, false, true));
    }

    @Test
    public void presentedAckEchoesInitialDirectiveIdentifiersAndSample() {
        PartyPlaybackAdapter.ScheduledCommand command =
                new PartyPlaybackAdapter.ScheduledCommand(
                        "directive-0", 0, 3, "CORRECTION",
                        PartyPlaybackAdapter.CorrectionKind.HARD,
                        120_000,
                        PartyPlaybackAdapter.PlaybackState.PLAYING,
                        1.0, 500_750);
        PartyPlaybackAdapter.Observation observation =
                new PartyPlaybackAdapter.Observation(
                        501_020, 120_270, 2_000, 180_000,
                        PartyPlaybackAdapter.PlaybackState.PLAYING,
                        true, true, 50);

        JSONObject fields = PartyClient.ackFields(
                "party-1", command, "PRESENTED", true, observation);

        assertEquals("party-1", fields.getString("partyId"));
        assertEquals("directive-0", fields.getString("directiveId"));
        assertEquals(3, fields.getLong("stateVersion"));
        assertEquals(0, fields.getLong("commandSeq"));
        assertEquals("PRESENTED", fields.getString("stage"));
        assertTrue(fields.getBoolean("success"));
        assertEquals(501_020, fields.getLong("sampleMonoMs"));
        assertEquals(120_270, fields.getLong("positionMs"));
        assertEquals("PLAYING", fields.getString("state"));
    }

    @Test
    public void armedAckCarriesOneFrameCoherentObservation() {
        PartyPlaybackAdapter.ScheduledCommand command =
                new PartyPlaybackAdapter.ScheduledCommand(
                        "barrier-1", 7, 9, "JUMP",
                        PartyPlaybackAdapter.CorrectionKind.NONE,
                        120_000,
                        PartyPlaybackAdapter.PlaybackState.PAUSED,
                        1.0, 497_000, true, "PREPARE",
                        500_750, 3_450, 300);
        PartyPlaybackAdapter.Observation observation =
                new PartyPlaybackAdapter.Observation(
                        500_120, 120_000, 4_000, 180_000,
                        PartyPlaybackAdapter.PlaybackState.PAUSED,
                        true, true, 50);

        JSONObject armed = PartyClient.ackFields(
                "party-1", command, "ARMED", true, observation);
        JSONObject executed = PartyClient.ackFields(
                "party-1", command, "EXECUTED", false, observation);

        assertEquals("ARMED", armed.getString("stage"));
        assertEquals(500_120, armed.getLong("sampleMonoMs"));
        assertEquals(120_000, armed.getLong("positionMs"));
        assertEquals("PAUSED", armed.getString("state"));
        assertFalse(executed.has("sampleMonoMs"));
        assertFalse(executed.has("positionMs"));
        assertFalse(executed.has("state"));
        assertFalse(executed.getBoolean("success"));
    }

    @Test
    public void revealMustMatchAllDirectiveIdentifiers() {
        PartyPlaybackAdapter.ScheduledCommand command =
                new PartyPlaybackAdapter.ScheduledCommand(
                        "barrier-1", 7, 9, "JUMP",
                        PartyPlaybackAdapter.CorrectionKind.NONE,
                        120_000,
                        PartyPlaybackAdapter.PlaybackState.PAUSED,
                        1.0, 497_000, true, "PREPARE",
                        500_750, 3_450, 300);
        JSONObject reveal = new JSONObject()
                .put("partyId", "party-1")
                .put("directiveId", "barrier-1")
                .put("stateVersion", 9)
                .put("commandSeq", 7)
                .put("executeAtServerMonoMs", 500_750);

        assertTrue(PartyClient.hasRequiredRevealFields(reveal));
        assertTrue(PartyClient.matchesDirective(reveal, command));
        JSONObject missingDeadline = new JSONObject(reveal.toString());
        missingDeadline.remove("executeAtServerMonoMs");
        assertFalse(PartyClient.hasRequiredRevealFields(missingDeadline));
        assertFalse(PartyClient.matchesDirective(
                new JSONObject(reveal.toString()).put("commandSeq", 8),
                command));
        assertFalse(PartyClient.matchesDirective(
                new JSONObject(reveal.toString())
                        .put("directiveId", "stale-barrier"),
                command));
        PartyClockModel clock = new PartyClockModel(
                100, 5, 10, true, 5, 500_000);
        assertNull(PartyClient.revealRejectionReason(
                reveal, "party-1", command, clock, 500_001));
        assertEquals("DIRECTIVE_MISMATCH",
                PartyClient.revealRejectionReason(
                        new JSONObject(reveal.toString())
                                .put("directiveId", "stale-barrier"),
                        "party-1", command, clock, 500_001));
    }

    @Test
    public void establishedMemberRestoresPlaybackStateAfterClockCalibration() {
        PartyPlaybackAdapter.Observation paused =
                new PartyPlaybackAdapter.Observation(
                        1, 2, 3, 4,
                        PartyPlaybackAdapter.PlaybackState.PAUSED,
                        true, true, 50);

        assertEquals("PAUSED",
                PartyClient.restoredClockStatus(false, paused));
        assertEquals("CONVERGING",
                PartyClient.restoredClockStatus(true, paused));
        assertEquals("READY",
                PartyClient.restoredClockStatus(false, null));
    }

    @Test
    public void invalidRequestDiagnosticsExcludeAuthorizationFields() {
        JSONObject envelope = PartyClient.requestEnvelope(
                "HEARTBEAT", "request-1", 4, "epoch-1", "secret-token",
                new JSONObject()
                        .put("partyId", "party-1")
                        .put("stateVersion", 2)
                        .put("positionMs", 88_812));

        String fields = PartyClient.diagnosticFields(envelope.toString());

        assertTrue(fields.contains("\"positionMs\":88812"));
        assertFalse(fields.contains("secret-token"));
        assertFalse(fields.contains("epoch-1"));
        assertFalse(fields.contains("request-1"));
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
    public void correlatedRepliesRequireTheCurrentRequest() {
        assertTrue(PartyClient.isCorrelatedReply(
                "PREPARE_ACCEPTED", "prepare-1"));
        assertTrue(PartyClient.isCorrelatedReply(
                "ERROR", "action-1"));
        assertFalse(PartyClient.isCorrelatedReply(
                "PREPARATION_RESULT", null));
        assertFalse(PartyClient.isCorrelatedReply(
                "PARTICIPANT_STATE", null));
        assertTrue(PartyClient.matchesInFlightRequest(
                "prepare-1", "prepare-1"));
        assertFalse(PartyClient.matchesInFlightRequest(
                "prepare-1", "prepare-2"));
        assertFalse(PartyClient.matchesInFlightRequest(
                null, "prepare-1"));
    }

    @Test
    public void terminalMembershipKeepsIndependentSessionRequests() {
        assertFalse(PartyClient.isMembershipScopedRequest("HELLO"));
        assertFalse(PartyClient.isMembershipScopedRequest("LIST_CLIENTS"));
        assertFalse(PartyClient.isMembershipScopedRequest("CREATE"));
        assertFalse(PartyClient.isMembershipScopedRequest("JOIN"));
        assertTrue(PartyClient.isMembershipScopedRequest("HEARTBEAT"));
        assertTrue(PartyClient.isMembershipScopedRequest("ACK"));
        assertTrue(PartyClient.isMembershipScopedRequest("PREPARE"));
    }

    private static PartyPlaybackAdapter.ScheduledCommand command(
            PartyPlaybackAdapter.PlaybackState targetState) {
        return new PartyPlaybackAdapter.ScheduledCommand(
                "directive-1", 1, 1, "COMMAND",
                PartyPlaybackAdapter.CorrectionKind.NONE,
                1_000, targetState, 1.0, 2_000);
    }

    private static PartyPlaybackAdapter.Observation observation(
            PartyPlaybackAdapter.PlaybackState state, boolean activated) {
        return observation(1_000, 2_000, state, activated);
    }

    private static PartyPlaybackAdapter.Observation observation(
            long sampleMonoMs, long positionMs,
            PartyPlaybackAdapter.PlaybackState state, boolean activated) {
        return new PartyPlaybackAdapter.Observation(
                sampleMonoMs, positionMs, 3_000, 4_000,
                state, true, activated, 50);
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
                        "directive-1", 3, 4, "PLAY",
                        PartyPlaybackAdapter.CorrectionKind.NONE, 5_000,
                        PartyPlaybackAdapter.PlaybackState.PLAYING,
                        1.0, 100_645);

        assertEquals(100_645, command.executeAtClientMonoMs);
        assertEquals(5_000, command.targetPositionMs);
        assertEquals(PartyPlaybackAdapter.PlaybackState.PLAYING,
                command.targetState);
    }

    @Test
    public void barrierCommandCarriesPreparationAndVisibleDeadlines() {
        PartyPlaybackAdapter.ScheduledCommand command =
                new PartyPlaybackAdapter.ScheduledCommand(
                        "barrier-1", 3, 4, "JUMP",
                        PartyPlaybackAdapter.CorrectionKind.NONE, 5_000,
                        PartyPlaybackAdapter.PlaybackState.PLAYING,
                        1.0, 100_645, true, "PREPARE",
                        104_395, 3_450, 300);

        assertTrue(command.barrier);
        assertEquals("PREPARE", command.phase);
        assertEquals(100_645, command.executeAtClientMonoMs);
        assertEquals(104_395, command.visibleAtClientMonoMs);
        assertEquals(3_450, command.predictedPresentationLatencyMs);
        assertEquals(300, command.presentationUncertaintyMs);
    }
}
