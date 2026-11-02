package sagex.miniclient.android.middleware;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TrickplayControllerClockTest
{
    @Test
    public void ordinaryFlushInvalidatesPlayerClockMapping()
    {
        assertTrue(TrickplayController.invalidatesMappingOnFlush(false));
    }

    @Test
    public void partyCorrectionMappingSurvivesServerFlush()
    {
        assertFalse(TrickplayController.invalidatesMappingOnFlush(true));
    }
}
