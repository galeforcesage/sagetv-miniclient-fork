package sagex.miniclient.party;

public interface PartyPlaybackAdapter {
    enum PlaybackState {
        PLAYING, PAUSED, BUFFERING, STOPPED, ERROR, UNAVAILABLE
    }

    enum NetworkBoundary {
        AVAILABLE, LOST, CAPABILITIES_CHANGED, LINK_PROPERTIES_CHANGED
    }

    enum CorrectionKind {
        NONE, SOFT, HARD
    }

    final class Observation {
        public final long sampleMonoMs;
        public final long positionMs;
        public final long renderedPositionMs;
        public final long bufferAheadMs;
        public final long durationMs;
        public final PlaybackState state;
        public final boolean prepared;
        public final boolean activated;
        public final int precisionMs;

        public Observation(long sampleMonoMs, long positionMs, long bufferAheadMs,
                           long durationMs, PlaybackState state, boolean prepared,
                           boolean activated, int precisionMs) {
            this(sampleMonoMs, positionMs, positionMs, bufferAheadMs, durationMs,
                    state, prepared, activated, precisionMs);
        }

        public Observation(long sampleMonoMs, long positionMs,
                           long renderedPositionMs, long bufferAheadMs,
                           long durationMs, PlaybackState state, boolean prepared,
                           boolean activated, int precisionMs) {
            this.sampleMonoMs = sampleMonoMs;
            this.positionMs = positionMs;
            this.renderedPositionMs = renderedPositionMs;
            this.bufferAheadMs = bufferAheadMs;
            this.durationMs = durationMs;
            this.state = state;
            this.prepared = prepared;
            this.activated = activated;
            this.precisionMs = precisionMs;
        }
    }

    final class ScheduledCommand {
        public final String directiveId;
        public final long commandSeq;
        public final long stateVersion;
        public final String action;
        public final CorrectionKind correctionKind;
        public final long targetPositionMs;
        public final PlaybackState targetState;
        public final double targetRate;
        public final long executeAtClientMonoMs;
        public final boolean barrier;
        public final String phase;
        public final long visibleAtClientMonoMs;
        public final long predictedPresentationLatencyMs;
        public final long presentationUncertaintyMs;

        public ScheduledCommand(String directiveId,
                                long commandSeq, long stateVersion, String action,
                                CorrectionKind correctionKind,
                                long targetPositionMs, PlaybackState targetState,
                                double targetRate, long executeAtClientMonoMs) {
            this(directiveId, commandSeq, stateVersion, action, correctionKind,
                    targetPositionMs, targetState, targetRate,
                    executeAtClientMonoMs, false, "", -1, 0, 0);
        }

        public ScheduledCommand(String directiveId,
                                long commandSeq, long stateVersion, String action,
                                CorrectionKind correctionKind,
                                long targetPositionMs, PlaybackState targetState,
                                double targetRate, long executeAtClientMonoMs,
                                boolean barrier, String phase,
                                long visibleAtClientMonoMs,
                                long predictedPresentationLatencyMs,
                                long presentationUncertaintyMs) {
            this.directiveId = directiveId;
            this.commandSeq = commandSeq;
            this.stateVersion = stateVersion;
            this.action = action;
            this.correctionKind = correctionKind;
            this.targetPositionMs = targetPositionMs;
            this.targetState = targetState;
            this.targetRate = targetRate;
            this.executeAtClientMonoMs = executeAtClientMonoMs;
            this.barrier = barrier;
            this.phase = phase == null ? "" : phase;
            this.visibleAtClientMonoMs = visibleAtClientMonoMs;
            this.predictedPresentationLatencyMs =
                    predictedPresentationLatencyMs;
            this.presentationUncertaintyMs = presentationUncertaintyMs;
        }
    }

    interface LifecycleListener {
        void onForegroundConfidenceLost();
        void onNetworkBoundary(NetworkBoundary boundary);
        void onPlaybackStateChanged();
        void onReadinessChanged(boolean activated);
    }

    interface ExecutionCallback {
        void onComplete(boolean success);
    }

    boolean supportsRequiredGuarantees();

    long elapsedRealtimeMs();

    Observation observe();

    void setPartyHold(boolean hold);

    void coverCurrentFrame(ExecutionCallback callback);

    void execute(ScheduledCommand command, ExecutionCallback callback);

    void reveal(ScheduledCommand command, ExecutionCallback callback);

    void cancelScheduledAction();

    void reconnectControlTransport();

    void localStop();

    void start(LifecycleListener listener);

    void stop();

    PartyPlaybackAdapter UNSUPPORTED = new PartyPlaybackAdapter() {
        @Override public boolean supportsRequiredGuarantees() { return false; }
        @Override public long elapsedRealtimeMs() { return 0; }
        @Override public Observation observe() {
            return new Observation(0, 0, 0, 0, PlaybackState.UNAVAILABLE,
                    false, false, 1000);
        }
        @Override public void setPartyHold(boolean hold) { }
        @Override public void coverCurrentFrame(ExecutionCallback callback) {
            if (callback != null) callback.onComplete(false);
        }
        @Override public void execute(ScheduledCommand command,
                                      ExecutionCallback callback) {
            if (callback != null) callback.onComplete(false);
        }
        @Override public void reveal(ScheduledCommand command,
                                     ExecutionCallback callback) {
            if (callback != null) callback.onComplete(false);
        }
        @Override public void cancelScheduledAction() { }
        @Override public void reconnectControlTransport() { }
        @Override public void localStop() { }
        @Override public void start(LifecycleListener listener) { }
        @Override public void stop() { }
    };
}
