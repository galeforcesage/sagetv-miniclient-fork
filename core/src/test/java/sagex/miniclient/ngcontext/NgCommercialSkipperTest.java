package sagex.miniclient.ngcontext;

import org.junit.Test;

import java.util.List;
import java.util.OptionalLong;

import static org.junit.Assert.*;

/**
 * Tests for {@link NgCommercialSkipper} using proper SkipSegment objects.
 */
public class NgCommercialSkipperTest {

    @Test
    public void testGetSkipTarget_returnsEmpty_whenNoBreaks() {
        assertEquals(OptionalLong.empty(), NgCommercialSkipper.getSkipTarget(50_000, null));
        assertEquals(OptionalLong.empty(), NgCommercialSkipper.getSkipTarget(50_000, List.of()));
    }

    @Test
    public void testGetSkipTarget_returnsEnd_whenInsideCommercial() {
        var commercials = List.of(
                new NgPlaybackContext.SkipSegment(60_000, 90_000, "commercial", 0),
                new NgPlaybackContext.SkipSegment(180_000, 210_000, "commercial", 0)
        );
        assertEquals(OptionalLong.of(90_000), NgCommercialSkipper.getSkipTarget(75_000, commercials));
    }

    @Test
    public void testGetSkipTarget_accountsForPreroll() {
        // Commercial at 60s-90s with 2s preroll → effective start is 58s
        var commercials = List.of(
                new NgPlaybackContext.SkipSegment(60_000, 90_000, "commercial", 2000)
        );
        // At 59s → inside preroll zone → should skip
        assertEquals(OptionalLong.of(90_000), NgCommercialSkipper.getSkipTarget(59_000, commercials));
        // At 57s → before preroll → no skip
        assertEquals(OptionalLong.empty(), NgCommercialSkipper.getSkipTarget(57_000, commercials));
    }

    @Test
    public void testGetSkipTarget_returnsEmpty_whenOutsideBreak() {
        var commercials = List.of(
                new NgPlaybackContext.SkipSegment(60_000, 90_000, "commercial", 0)
        );
        assertEquals(OptionalLong.empty(), NgCommercialSkipper.getSkipTarget(100_000, commercials));
    }

    @Test
    public void testGetSkipTarget_returnsEmpty_whenAtEnd() {
        var commercials = List.of(
                new NgPlaybackContext.SkipSegment(60_000, 90_000, "commercial", 0)
        );
        assertEquals(OptionalLong.empty(), NgCommercialSkipper.getSkipTarget(90_000, commercials));
    }

    @Test
    public void testGetNextCommercialStart_returnsFirstUpcoming() {
        var commercials = List.of(
                new NgPlaybackContext.SkipSegment(60_000, 90_000, "commercial", 0),
                new NgPlaybackContext.SkipSegment(180_000, 210_000, "commercial", 0)
        );
        assertEquals(OptionalLong.of(180_000), NgCommercialSkipper.getNextCommercialStart(100_000, commercials));
    }

    @Test
    public void testGetNextCommercialStart_returnsEmpty_whenPastAll() {
        var commercials = List.of(
                new NgPlaybackContext.SkipSegment(60_000, 90_000, "commercial", 0)
        );
        assertEquals(OptionalLong.empty(), NgCommercialSkipper.getNextCommercialStart(250_000, commercials));
    }

    @Test
    public void testGetNextCommercialStart_returnsEmpty_whenNull() {
        assertEquals(OptionalLong.empty(), NgCommercialSkipper.getNextCommercialStart(50_000, null));
    }
}
