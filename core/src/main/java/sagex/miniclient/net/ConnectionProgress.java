package sagex.miniclient.net;

/**
 * Transport-neutral progress and deadline state for one physical connection attempt.
 */
public final class ConnectionProgress {
    public interface Clock {
        long nowMs();
    }

    public enum Phase {
        CREATED,
        CONNECTING,
        SOCKET_CONNECTED,
        HANDSHAKE_SENT,
        HANDSHAKE_ACCEPTED,
        CHANNEL_REGISTERED,
        ACTIVE,
        CLOSED
    }

    public enum ClosureOrigin {
        NONE,
        LOCAL_CANCEL,
        LOCAL_TIMEOUT,
        LOCAL_REPLACED,
        REMOTE_CLOSE,
        TRANSPORT_ERROR,
        INTENTIONAL_STOP
    }

    private final long attemptId;
    private final long socketId;
    private final Clock clock;
    private final long startedAtMs;
    private final long idleTimeoutMs;
    private final long absoluteTimeoutMs;
    private long lastProgressAtMs;
    private long bytesSent;
    private long bytesReceived;
    private long messagesSent;
    private long messagesReceived;
    private long connectedAtMs = -1;
    private long lastReplyLatencyMs = -1;
    private long previousReplyLatencyMs = -1;
    private long jitterMs = -1;
    private Phase phase = Phase.CREATED;
    private ClosureOrigin closureOrigin = ClosureOrigin.NONE;
    private String closureReason = "";

    public ConnectionProgress(long attemptId, long socketId, Clock clock,
                              long idleTimeoutMs, long absoluteTimeoutMs) {
        this.attemptId = attemptId;
        this.socketId = socketId;
        this.clock = clock;
        this.idleTimeoutMs = idleTimeoutMs;
        this.absoluteTimeoutMs = absoluteTimeoutMs;
        this.startedAtMs = clock.nowMs();
        this.lastProgressAtMs = startedAtMs;
    }

    public synchronized void advance(Phase next) {
        phase = next;
        markProgress();
        if (next == Phase.SOCKET_CONNECTED) {
            connectedAtMs = clock.nowMs();
        }
    }

    public synchronized void sent(long bytes) {
        bytesSent += Math.max(0, bytes);
        messagesSent++;
        markProgress();
    }

    public synchronized void received(long bytes, long requestStartedAtMs) {
        bytesReceived += Math.max(0, bytes);
        messagesReceived++;
        long latency = Math.max(0, clock.nowMs() - requestStartedAtMs);
        if (previousReplyLatencyMs >= 0) {
            jitterMs = Math.abs(latency - previousReplyLatencyMs);
        }
        previousReplyLatencyMs = latency;
        lastReplyLatencyMs = latency;
        markProgress();
    }

    public synchronized boolean isIdleExpired() {
        return clock.nowMs() - lastProgressAtMs >= idleTimeoutMs;
    }

    public synchronized boolean isAbsoluteExpired() {
        return clock.nowMs() - startedAtMs >= absoluteTimeoutMs;
    }

    public synchronized int nextReadTimeoutMs() {
        long idleRemaining = idleTimeoutMs - (clock.nowMs() - lastProgressAtMs);
        long absoluteRemaining = absoluteTimeoutMs - (clock.nowMs() - startedAtMs);
        long remaining = Math.min(idleRemaining, absoluteRemaining);
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, remaining));
    }

    public synchronized void close(ClosureOrigin origin, String reason) {
        phase = Phase.CLOSED;
        closureOrigin = origin == null ? ClosureOrigin.TRANSPORT_ERROR : origin;
        closureReason = reason == null ? "" : reason;
    }

    public synchronized String summary() {
        return "attempt=" + attemptId
                + " socket=" + socketId
                + " phase=" + phase
                + " ageMs=" + (clock.nowMs() - startedAtMs)
                + " idleMs=" + (clock.nowMs() - lastProgressAtMs)
                + " connectMs=" + (connectedAtMs < 0 ? -1 : connectedAtMs - startedAtMs)
                + " txBytes=" + bytesSent
                + " rxBytes=" + bytesReceived
                + " txMessages=" + messagesSent
                + " rxMessages=" + messagesReceived
                + " replyLatencyMs=" + lastReplyLatencyMs
                + " jitterMs=" + jitterMs
                + " closure=" + closureOrigin
                + " reason=" + closureReason;
    }

    public long getAttemptId() {
        return attemptId;
    }

    public long getSocketId() {
        return socketId;
    }

    private void markProgress() {
        lastProgressAtMs = clock.nowMs();
    }
}
