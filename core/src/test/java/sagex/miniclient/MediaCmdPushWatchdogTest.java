package sagex.miniclient;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MediaCmdPushWatchdogTest
{
    @Test
    public void partyPreparationDoesNotUseTheOrdinaryPushDeadline()
    {
        assertEquals(7_000, MediaCmd.pushDataflowTimeoutMs(false));
        assertEquals(20_000, MediaCmd.pushDataflowTimeoutMs(true));
        assertTrue(MediaCmd.pushDataflowTimeoutMs(true) > 15_000);
    }
}
