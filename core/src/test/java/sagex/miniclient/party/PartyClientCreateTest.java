package sagex.miniclient.party;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class PartyClientCreateTest
{
    @Test
    public void createUsesServerOwnedCurrentPlaybackIdentity()
    {
        JSONObject fields = PartyClient.createFields("CURRENT_POSITION");

        assertEquals("CURRENT_POSITION", fields.getString("startMode"));
        assertFalse(fields.has("mediaFileId"));
    }
}
