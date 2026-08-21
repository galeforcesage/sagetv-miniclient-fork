package sagex.miniclient.android.display;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pure-logic tests for the NG sink resolver's measurement helpers. Per server
 * contract §7.3 the sink is a pure measurement: the resolver always returns the
 * honest physical panel (landscape-normalized) when measurable, and "" only when
 * genuinely unknown. There is no report/suppress gate to test anymore — that
 * intent moved to QUALITY_HINT.
 */
public class SinkResolutionResolverTest {

    @Test
    public void formatLandscape_landscapePanelPassthrough() {
        assertEquals("3840x2160", SinkResolutionResolver.formatLandscape(3840, 2160));
        assertEquals("2960x1848", SinkResolutionResolver.formatLandscape(2960, 1848));
    }

    @Test
    public void formatLandscape_portraitIsNormalizedToLandscape() {
        // A phone panel reported portrait must be normalized so W >= H.
        assertEquals("2400x1080", SinkResolutionResolver.formatLandscape(1080, 2400));
    }

    @Test
    public void formatLandscape_squareIsUnchanged() {
        assertEquals("1000x1000", SinkResolutionResolver.formatLandscape(1000, 1000));
    }

    @Test
    public void formatLandscape_zeroOrNegativeFailsClosed() {
        assertEquals("", SinkResolutionResolver.formatLandscape(0, 2160));
        assertEquals("", SinkResolutionResolver.formatLandscape(3840, 0));
        assertEquals("", SinkResolutionResolver.formatLandscape(-1, -1));
    }
}
