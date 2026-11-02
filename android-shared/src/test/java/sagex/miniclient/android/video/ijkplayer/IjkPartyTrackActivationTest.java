package sagex.miniclient.android.video.ijkplayer;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IjkPartyTrackActivationTest
{
    @Test
    public void singleVideoTrackIsActiveBeforeRenderingStarts()
    {
        assertTrue(IjkPartyTrackActivation.isActive(-1, 1));
    }

    @Test
    public void multipleUnselectedTracksAreActiveMedia()
    {
        assertTrue(IjkPartyTrackActivation.isActive(-1, 2));
    }

    @Test
    public void selectedTrackIsAlwaysActive()
    {
        assertTrue(IjkPartyTrackActivation.isActive(0, 0));
    }

    @Test
    public void absentTrackDoesNotBecomeActive()
    {
        assertFalse(IjkPartyTrackActivation.isActive(-1, 0));
    }

    @Test
    public void audioVideoMediaActivatesWhenVideoRenders()
    {
        assertTrue(IjkPartyTrackActivation.hasRendered(
                true, true, false, true));
    }

    @Test
    public void audioVideoMediaDoesNotActivateFromAudioAlone()
    {
        assertFalse(IjkPartyTrackActivation.hasRendered(
                true, true, true, false));
    }

    @Test
    public void audioOnlyMediaRequiresAudioRendering()
    {
        assertFalse(IjkPartyTrackActivation.hasRendered(
                true, false, false, false));
        assertTrue(IjkPartyTrackActivation.hasRendered(
                true, false, true, false));
    }

    @Test
    public void videoOnlyMediaRequiresVideoRendering()
    {
        assertFalse(IjkPartyTrackActivation.hasRendered(
                false, true, false, false));
        assertTrue(IjkPartyTrackActivation.hasRendered(
                false, true, false, true));
    }

    @Test
    public void mediaWithoutAudioOrVideoNeverActivates()
    {
        assertFalse(IjkPartyTrackActivation.hasRendered(
                false, false, true, true));
    }
}
