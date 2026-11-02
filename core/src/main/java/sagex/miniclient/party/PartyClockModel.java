package sagex.miniclient.party;

public final class PartyClockModel {
    static final long FRESH_SAMPLE_WINDOW_MS = 60_000;

    private final double serverMinusClientMs;
    private final double uncertaintyMs;
    private final long p95RttMs;
    private final boolean serverConfident;
    private final int acceptedSamples;
    private final long receivedClientMonoMs;

    public PartyClockModel(double serverMinusClientMs, double uncertaintyMs,
                           long p95RttMs, boolean serverConfident,
                           int acceptedSamples, long receivedClientMonoMs) {
        this.serverMinusClientMs = serverMinusClientMs;
        this.uncertaintyMs = uncertaintyMs;
        this.p95RttMs = p95RttMs;
        this.serverConfident = serverConfident;
        this.acceptedSamples = acceptedSamples;
        this.receivedClientMonoMs = receivedClientMonoMs;
    }

    public boolean isConfident(long nowClientMonoMs) {
        return serverConfident
                && acceptedSamples >= 5
                && uncertaintyMs <= 150.0
                && nowClientMonoMs - receivedClientMonoMs <= FRESH_SAMPLE_WINDOW_MS;
    }

    public long clientDeadline(long executeAtServerMonoMs) {
        return Math.round(executeAtServerMonoMs - serverMinusClientMs);
    }

    public double getServerMinusClientMs() {
        return serverMinusClientMs;
    }

    public double getUncertaintyMs() {
        return uncertaintyMs;
    }

    public long getP95RttMs() {
        return p95RttMs;
    }

    public int getAcceptedSamples() {
        return acceptedSamples;
    }
}
