package sagex.miniclient.party;

public final class PartyResumeState {
    static final long DEFAULT_GRACE_MS = 90_000;

    private String partyId;
    private String participantId;
    private String recoveryToken;
    private long disconnectedClientMonoMs;
    private long reconnectGraceMs = DEFAULT_GRACE_MS;

    public synchronized void update(String partyId, String participantId,
                                    String recoveryToken, long reconnectGraceMs) {
        this.partyId = partyId;
        this.participantId = participantId;
        this.recoveryToken = recoveryToken;
        this.reconnectGraceMs = reconnectGraceMs > 0
                ? reconnectGraceMs : DEFAULT_GRACE_MS;
        disconnectedClientMonoMs = 0;
    }

    public synchronized void markDisconnected(long clientMonoMs) {
        if (isPopulated()) disconnectedClientMonoMs = clientMonoMs;
    }

    public synchronized boolean canResume(long clientMonoMs) {
        return isPopulated() && disconnectedClientMonoMs > 0
                && clientMonoMs - disconnectedClientMonoMs <= reconnectGraceMs;
    }

    public synchronized String getPartyId() {
        return partyId;
    }

    public synchronized String getParticipantId() {
        return participantId;
    }

    public synchronized String getRecoveryToken() {
        return recoveryToken;
    }

    public synchronized void clear() {
        partyId = null;
        participantId = null;
        recoveryToken = null;
        disconnectedClientMonoMs = 0;
        reconnectGraceMs = DEFAULT_GRACE_MS;
    }

    private boolean isPopulated() {
        return partyId != null && participantId != null && recoveryToken != null;
    }
}
