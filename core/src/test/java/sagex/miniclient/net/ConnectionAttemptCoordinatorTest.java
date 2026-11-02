package sagex.miniclient.net;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ConnectionAttemptCoordinatorTest {
    @Test
    public void staleAttemptCannotReplaceNewerConnection() {
        ConnectionAttemptCoordinator<Object> coordinator = new ConnectionAttemptCoordinator<Object>();
        Object oldCandidate = new Object();
        long oldAttempt = coordinator.beginAttempt();
        assertTrue(coordinator.registerPending(oldAttempt, oldCandidate));

        Object newCandidate = new Object();
        long newAttempt = coordinator.beginAttempt();
        assertTrue(coordinator.registerPending(newAttempt, newCandidate));

        assertFalse(coordinator.publish(oldAttempt, oldCandidate).accepted);
        assertTrue(coordinator.publish(newAttempt, newCandidate).accepted);
        assertSame(newCandidate, coordinator.getCurrent());
    }

    @Test
    public void staleCleanupCannotClearPublishedNewerConnection() {
        ConnectionAttemptCoordinator<Object> coordinator = new ConnectionAttemptCoordinator<Object>();
        Object first = new Object();
        long firstAttempt = coordinator.beginAttempt();
        coordinator.registerPending(firstAttempt, first);
        coordinator.publish(firstAttempt, first);

        Object second = new Object();
        long secondAttempt = coordinator.beginAttempt();
        coordinator.registerPending(secondAttempt, second);
        coordinator.publish(secondAttempt, second);

        assertFalse(coordinator.clearCurrent(first));
        assertSame(second, coordinator.getCurrent());
    }

    @Test
    public void cancellationOwnsOnlyCapturedResources() {
        ConnectionAttemptCoordinator<Object> coordinator = new ConnectionAttemptCoordinator<Object>();
        Object current = new Object();
        long firstAttempt = coordinator.beginAttempt();
        coordinator.registerPending(firstAttempt, current);
        coordinator.publish(firstAttempt, current);

        Object pending = new Object();
        long secondAttempt = coordinator.beginAttempt();
        coordinator.registerPending(secondAttempt, pending);

        ConnectionAttemptCoordinator.CancelResult<Object> cancelled = coordinator.cancelAll();
        assertSame(current, cancelled.current);
        assertSame(pending, cancelled.pending);
        assertNull(coordinator.getCurrent());
        assertFalse(coordinator.isOwner(secondAttempt));
    }

    @Test
    public void newerAttemptCapturesOnlySupersededPendingConnection() {
        ConnectionAttemptCoordinator<Object> coordinator = new ConnectionAttemptCoordinator<Object>();
        Object current = new Object();
        long currentAttempt = coordinator.beginAttempt();
        coordinator.registerPending(currentAttempt, current);
        coordinator.publish(currentAttempt, current);

        Object pending = new Object();
        long pendingAttempt = coordinator.beginAttempt();
        coordinator.registerPending(pendingAttempt, pending);

        ConnectionAttemptCoordinator.BeginResult<Object> next =
                coordinator.beginAttemptCapturingPending();

        assertSame(pending, next.supersededPending);
        assertSame(current, coordinator.getCurrent());
        assertFalse(coordinator.isOwner(pendingAttempt));
        assertTrue(coordinator.isOwner(next.attemptId));
    }

    @Test
    public void publishedConnectionRetainsOwnershipUntilReplacementPublishes() {
        ConnectionAttemptCoordinator<Object> coordinator = new ConnectionAttemptCoordinator<Object>();
        Object current = new Object();
        long currentAttempt = coordinator.beginAttempt();
        coordinator.registerPending(currentAttempt, current);
        coordinator.publish(currentAttempt, current);

        Object replacement = new Object();
        long replacementAttempt = coordinator.beginAttempt();
        coordinator.registerPending(replacementAttempt, replacement);

        assertTrue(coordinator.isOwner(currentAttempt));
        assertTrue(coordinator.isOwner(replacementAttempt));

        coordinator.publish(replacementAttempt, replacement);

        assertFalse(coordinator.isOwner(currentAttempt));
        assertTrue(coordinator.isOwner(replacementAttempt));
    }
}
