package sagex.miniclient.ngcontext;

import org.junit.Test;

import java.util.OptionalLong;

import static org.junit.Assert.*;

/**
 * Tests for {@link NgCommercialSkipper}.
 */
public class NgCommercialSkipperTest {

    @Test
    public void testGetSkipTarget_returnsEmpty_whenNoBreaks() {
        assertEquals(OptionalLong.empty(), NgCommercialSkipper.getSkipTarget(50_000, null));
        assertEquals(OptionalLong.empty(), NgCommercialSkipper.getSkipTarget(50_000, new long[0]));
    }

    @Test
    public void testGetSkipTarget_returnsEnd_whenInsideCommercial() {
        long[] breaks = {60_000, 90_000, 180_000, 210_000};
        assertEquals(OptionalLong.of(90_000), NgCommercialSkipper.getSkipTarget(75_000, breaks));
    }

    @Test
    public void testGetSkipTarget_returnsEnd_whenAtStart() {
        long[] breaks = {60_000, 90_000};
        assertEquals(OptionalLong.of(90_000), NgCommercialSkipper.getSkipTarget(60_000, breaks));
    }

    @Test
    public void testGetSkipTarget_returnsEmpty_whenOutsideBreak() {
        long[] breaks = {60_000, 90_000, 180_000, 210_000};
        assertEquals(OptionalLong.empty(), NgCommercialSkipper.getSkipTarget(100_000, breaks));
    }

    @Test
    public void testGetSkipTarget_returnsEmpty_whenAtEnd() {
        long[] breaks = {60_000, 90_000};
        assertEquals(OptionalLong.empty(), NgCommercialSkipper.getSkipTarget(90_000, breaks));
    }

    @Test
    public void testGetNextCommercialStart_returnsFirstUpcoming() {
        long[] breaks = {60_000, 90_000, 180_000, 210_000};
        assertEquals(OptionalLong.of(180_000), NgCommercialSkipper.getNextCommercialStart(100_000, breaks));
    }

    @Test
    public void testGetNextCommercialStart_returnsEmpty_whenPastAll() {
        long[] breaks = {60_000, 90_000, 180_000, 210_000};
        assertEquals(OptionalLong.empty(), NgCommercialSkipper.getNextCommercialStart(250_000, breaks));
    }

    @Test
    public void testGetNextCommercialStart_returnsEmpty_whenNull() {
        assertEquals(OptionalLong.empty(), NgCommercialSkipper.getNextCommercialStart(50_000, null));
    }
}
