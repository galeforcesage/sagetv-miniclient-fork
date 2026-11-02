package sagex.miniclient.events;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConnectionLostTest {
    @Test
    public void preservesReconnectState() {
        assertTrue(new ConnectionLost(true).reconnecting);
        assertFalse(new ConnectionLost(false).reconnecting);
    }
}
