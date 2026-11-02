package sagex.miniclient.net;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SimplePullDataSourcePartyTest
{
    @Test
    public void bindsOnTheAlreadyOpenProducerWithoutLoggingOrChangingTheHandle()
            throws Exception
    {
        SimplePullDataSource source = new SimplePullDataSource();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        source.opened = true;
        source.remoteReader = new DataInputStream(
                new ByteArrayInputStream("OK\r\n".getBytes("US-ASCII")));
        source.remoteWriter = output;

        String handle = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        assertTrue(source.bindPartyAdmission(handle));
        assertEquals("PARTY_BIND " + handle + "\r\n",
                output.toString("US-ASCII"));
    }

    @Test
    public void rejectsInvalidOrClosedProducerBindings()
    {
        SimplePullDataSource source = new SimplePullDataSource();
        assertFalse(source.bindPartyAdmission("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
        source.opened = true;
        assertFalse(source.bindPartyAdmission("short"));
    }

    @Test
    public void failsClosedWhenServerDeniesTheBinding() throws Exception
    {
        SimplePullDataSource source = new SimplePullDataSource();
        source.opened = true;
        source.remoteReader = new DataInputStream(
                new ByteArrayInputStream("PARTY_DENIED\r\n".getBytes("US-ASCII")));
        source.remoteWriter = new ByteArrayOutputStream();

        assertFalse(source.bindPartyAdmission("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
    }
}
