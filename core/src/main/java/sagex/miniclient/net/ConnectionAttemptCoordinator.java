package sagex.miniclient.net;

/**
 * Ownership gate that prevents an older asynchronous attempt from publishing
 * over, or cleaning up, a newer transport.
 */
public final class ConnectionAttemptCoordinator<T> {
    public static final class BeginResult<T> {
        public final long attemptId;
        public final T supersededPending;

        private BeginResult(long attemptId, T supersededPending) {
            this.attemptId = attemptId;
            this.supersededPending = supersededPending;
        }
    }

    public static final class PublishResult<T> {
        public final boolean accepted;
        public final T replaced;

        private PublishResult(boolean accepted, T replaced) {
            this.accepted = accepted;
            this.replaced = replaced;
        }
    }

    public static final class CancelResult<T> {
        public final T current;
        public final T pending;

        private CancelResult(T current, T pending) {
            this.current = current;
            this.pending = pending;
        }
    }

    private long sequence;
    private long activeAttemptId;
    private long currentAttemptId;
    private T pending;
    private T current;

    public synchronized long beginAttempt() {
        return beginAttemptCapturingPending().attemptId;
    }

    public synchronized BeginResult<T> beginAttemptCapturingPending() {
        T supersededPending = pending;
        pending = null;
        activeAttemptId = ++sequence;
        return new BeginResult<T>(activeAttemptId, supersededPending);
    }

    public synchronized boolean registerPending(long attemptId, T candidate) {
        if (activeAttemptId != attemptId) return false;
        pending = candidate;
        return true;
    }

    public synchronized PublishResult<T> publish(long attemptId, T candidate) {
        if (activeAttemptId != attemptId || pending != candidate) {
            return new PublishResult<T>(false, null);
        }
        T replaced = current;
        current = candidate;
        currentAttemptId = attemptId;
        pending = null;
        return new PublishResult<T>(true, replaced);
    }

    public synchronized CancelResult<T> cancelAll() {
        activeAttemptId = ++sequence;
        CancelResult<T> result = new CancelResult<T>(current, pending);
        current = null;
        currentAttemptId = 0;
        pending = null;
        return result;
    }

    public synchronized boolean clearCurrent(T expected) {
        if (current != expected) return false;
        current = null;
        currentAttemptId = 0;
        return true;
    }

    public synchronized void clearPending(T expected) {
        if (pending == expected) pending = null;
    }

    public synchronized boolean isOwner(long attemptId) {
        return activeAttemptId == attemptId || currentAttemptId == attemptId;
    }

    public synchronized T getCurrent() {
        return current;
    }

    public synchronized void setCurrent(T value) {
        current = value;
        currentAttemptId = value == null ? 0 : activeAttemptId;
    }
}
