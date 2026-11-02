package sagex.miniclient.party;

public final class PartyRecoveryState {
    public enum RecoveryAction {
        NONE, RESUME, REJOIN
    }

    static final long RESUME_GRACE_MS = 90_000;
    static final long REJOIN_TOMBSTONE_MS = 300_000;

    public String partyId;
    public String participantId;
    public String recoveryToken;
    public long recoveryTokenExpiresAtServerMonoMs;
    public long disconnectedAtClientMonoMs;

    public synchronized void update(String partyId, String participantId,
                                    String recoveryToken, long expiresAtServerMonoMs) {
        this.partyId = partyId;
        this.participantId = participantId;
        this.recoveryToken = recoveryToken;
        this.recoveryTokenExpiresAtServerMonoMs = expiresAtServerMonoMs;
        this.disconnectedAtClientMonoMs = 0;
    }

    public synchronized void markDisconnected(long nowClientMonoMs) {
        if (partyId != null) disconnectedAtClientMonoMs = nowClientMonoMs;
    }

    public synchronized boolean canResume(long nowClientMonoMs) {
        return recoveryAction(nowClientMonoMs) == RecoveryAction.RESUME;
    }

    public synchronized RecoveryAction recoveryAction(long nowClientMonoMs) {
        if (partyId == null || participantId == null || recoveryToken == null
                || disconnectedAtClientMonoMs <= 0) {
            return RecoveryAction.NONE;
        }

        long disconnectedMs = nowClientMonoMs - disconnectedAtClientMonoMs;
        if (disconnectedMs < 0) return RecoveryAction.NONE;
        if (disconnectedMs <= RESUME_GRACE_MS) return RecoveryAction.RESUME;
        if (disconnectedMs <= RESUME_GRACE_MS + REJOIN_TOMBSTONE_MS) {
            return RecoveryAction.REJOIN;
        }
        return RecoveryAction.NONE;
    }

    public synchronized boolean hasDisconnectedAuthority() {
        return partyId != null && participantId != null && recoveryToken != null
                && disconnectedAtClientMonoMs > 0;
    }

    public synchronized void clear() {
        partyId = null;
        participantId = null;
        recoveryToken = null;
        recoveryTokenExpiresAtServerMonoMs = 0;
        disconnectedAtClientMonoMs = 0;
    }
}
