package sagex.miniclient.party;

public interface PartyPlaybackAdapter {
    enum PlaybackState {
        PLAYING, PAUSED, BUFFERING, SEEKING, STOPPED, UNAVAILABLE
    }

    enum NetworkBoundary {
        AVAILABLE, LOST, CAPABILITIES_CHANGED, LINK_PROPERTIES_CHANGED
    }

    enum SeekAccuracy {
        FAST_SYNC, EXACT
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
        public final long commandSeq;
        public final long stateVersion;
        public final String action;
        public final long targetPositionMs;
        public final PlaybackState targetState;
        public final double targetRate;
        public final long executeAtClientMonoMs;
        public final SeekAccuracy seekAccuracy;

        public ScheduledCommand(long commandSeq, long stateVersion, String action,
                                long targetPositionMs, PlaybackState targetState,
                                double targetRate, long executeAtClientMonoMs) {
            this(commandSeq, stateVersion, action, targetPositionMs, targetState,
                    targetRate, executeAtClientMonoMs, SeekAccuracy.EXACT);
        }

        public ScheduledCommand(long commandSeq, long stateVersion, String action,
                                long targetPositionMs, PlaybackState targetState,
                                double targetRate, long executeAtClientMonoMs,
                                SeekAccuracy seekAccuracy) {
            this.commandSeq = commandSeq;
            this.stateVersion = stateVersion;
            this.action = action;
            this.targetPositionMs = targetPositionMs;
            this.targetState = targetState;
            this.targetRate = targetRate;
            this.executeAtClientMonoMs = executeAtClientMonoMs;
            this.seekAccuracy = seekAccuracy == null ? SeekAccuracy.EXACT : seekAccuracy;
        }
    }

    interface LifecycleListener {
        void onForegroundConfidenceLost();
        void onNetworkBoundary(NetworkBoundary boundary);
        void onPlaybackStateChanged();
        void onReadinessInvalidated();
    }

    boolean supportsRequiredGuarantees();

    long elapsedRealtimeMs();

    Observation observe();

    void setCanonicalPosition(long positionMs);

    void setPartyHold(boolean hold);

    void applyPlaybackState(PlaybackState state);

    boolean execute(ScheduledCommand command);

    void cancelScheduledAction();

    boolean supportsTemporaryRate();

    boolean setTemporaryRate(double rate, long durationMs);

    void reconnectControlTransport();

    void reconnectMediaTransport();

    void localStop();

    long playbackGeneration();

    long playbackCommandGeneration();

    void restoreAfterCreateFailure(long generation, boolean wasPlaying);

    String deliveryMode();

    boolean bindMediaAdmission(String handle);

    void clearMediaAdmission();

    void resetReadiness();

    void start(LifecycleListener listener);

    void stop();

    PartyPlaybackAdapter UNSUPPORTED = new PartyPlaybackAdapter() {
        @Override public boolean supportsRequiredGuarantees() { return false; }
        @Override public long elapsedRealtimeMs() { return 0; }
        @Override public Observation observe() {
            return new Observation(0, 0, 0, 0, PlaybackState.UNAVAILABLE,
                    false, false, 1000);
        }
        @Override public void setCanonicalPosition(long positionMs) { }
        @Override public void setPartyHold(boolean hold) { }
        @Override public void applyPlaybackState(PlaybackState state) { }
        @Override public boolean execute(ScheduledCommand command) { return false; }
        @Override public void cancelScheduledAction() { }
        @Override public boolean supportsTemporaryRate() { return false; }
        @Override public boolean setTemporaryRate(double rate, long durationMs) {
            return false;
        }
        @Override public void reconnectControlTransport() { }
        @Override public void reconnectMediaTransport() { }
        @Override public void localStop() { }
        @Override public long playbackGeneration() { return 0; }
        @Override public long playbackCommandGeneration() { return 0; }
        @Override public void restoreAfterCreateFailure(long generation, boolean wasPlaying) { }
        @Override public String deliveryMode() { return ""; }
        @Override public boolean bindMediaAdmission(String handle) { return false; }
        @Override public void clearMediaAdmission() { }
        @Override public void resetReadiness() { }
        @Override public void start(LifecycleListener listener) { }
        @Override public void stop() { }
    };
}
