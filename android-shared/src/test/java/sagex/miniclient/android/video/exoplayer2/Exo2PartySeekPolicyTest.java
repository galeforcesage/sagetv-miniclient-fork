package sagex.miniclient.android.video.exoplayer2;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Exo2PartySeekPolicyTest
{
    @Test
    public void pushModeUsesServerOwnedPartySeek()
    {
        assertFalse(Exo2MediaPlayerImpl.shouldSeekLocallyForParty(true));
    }

    @Test
    public void pullModeStillSeeksLocally()
    {
        assertTrue(Exo2MediaPlayerImpl.shouldSeekLocallyForParty(false));
    }
}
