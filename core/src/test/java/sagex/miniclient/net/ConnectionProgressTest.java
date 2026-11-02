package sagex.miniclient.net;

import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConnectionProgressTest {
    private static final class FakeClock implements ConnectionProgress.Clock {
        long now;
        @Override public long nowMs() { return now; }
        void advance(long ms) { now += ms; }
    }

    @Test
    public void progressExtendsIdleButNotAbsoluteDeadline() {
        FakeClock clock = new FakeClock();
        ConnectionProgress progress = new ConnectionProgress(1, 2, clock, 10_000, 30_000);

        clock.advance(9_000);
        progress.sent(8);
        clock.advance(9_000);
        assertFalse(progress.isIdleExpired());
        assertFalse(progress.isAbsoluteExpired());

        progress.received(1, 17_000);
        clock.advance(12_000);
        assertTrue(progress.isIdleExpired());
        assertTrue(progress.isAbsoluteExpired());
    }

    @Test
    public void noProgressExpiresIdleDeadline() {
        FakeClock clock = new FakeClock();
        ConnectionProgress progress = new ConnectionProgress(1, 2, clock, 5_000, 30_000);
        clock.advance(5_000);
        assertTrue(progress.isIdleExpired());
        assertFalse(progress.isAbsoluteExpired());
    }

    @Test
    public void delayedSocketAndChannelProgressRemainWithinIdleBudget() {
        FakeClock clock = new FakeClock();
        ConnectionProgress progress = new ConnectionProgress(7, 9, clock, 5_000, 20_000);

        clock.advance(3_000);
        progress.advance(ConnectionProgress.Phase.SOCKET_CONNECTED);
        clock.advance(4_000);
        progress.sent(8);
        clock.advance(4_000);
        progress.received(1, 7_000);
        clock.advance(4_000);

        assertFalse(progress.isIdleExpired());
        assertFalse(progress.isAbsoluteExpired());
    }

    @Test
    public void trickleProgressCannotDefeatAbsoluteCap() {
        FakeClock clock = new FakeClock();
        ConnectionProgress progress = new ConnectionProgress(7, 9, clock, 5_000, 12_000);

        clock.advance(4_000);
        progress.sent(1);
        clock.advance(4_000);
        progress.sent(1);
        clock.advance(4_000);
        progress.sent(1);

        assertFalse(progress.isIdleExpired());
        assertTrue(progress.isAbsoluteExpired());
    }

    @Test
    public void pullProbeDistinguishesUnsupportedAndTransient() {
        assertEquals(PullServerProbe.Result.UNSUPPORTED,
                PullServerProbe.probe("host", (h, p, t) -> {
                    throw new ConnectException("refused");
                }, 2, 1));

        assertEquals(PullServerProbe.Result.INDETERMINATE,
                PullServerProbe.probe("host", (h, p, t) -> {
                    throw new SocketTimeoutException("slow");
                }, 2, 1));

        assertEquals(PullServerProbe.Result.SUPPORTED,
                PullServerProbe.probe("host", new PullServerProbe.Connector() {
                    int calls;
                    @Override public void connect(String h, int p, int t) throws IOException {
                        if (calls++ == 0) throw new SocketTimeoutException("slow");
                    }
                }, 2, 1));
    }
}
