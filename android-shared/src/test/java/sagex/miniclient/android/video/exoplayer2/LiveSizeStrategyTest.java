package sagex.miniclient.android.video.exoplayer2;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.DataSourceException;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link LiveSizeStrategy} and {@link LiveModeDetector}.
 * Covers the regime selection that previously lived inline in
 * {@code Exo2PullDataSource.open()}.
 */
public class LiveSizeStrategyTest {

    private static final long FILE_SIZE = 1_000_000_000L; // 1 GB
    private static final long NO_LEN = C.LENGTH_UNSET;

    // ---------- non-timeshifted (bounded) ----------

    @Test
    public void boundedFile_returnsBytesRemainingFromPosition() throws Exception {
        LiveModeDetector det = new LiveModeDetector(false);
        det.onOpen(FILE_SIZE);
        long r = LiveSizeStrategy.computeBytesRemaining(det, FILE_SIZE, 100, NO_LEN);
        assertEquals(FILE_SIZE - 100, r);
    }

    @Test
    public void boundedFile_atEnd_returnsEndOfInput() throws Exception {
        LiveModeDetector det = new LiveModeDetector(false);
        det.onOpen(FILE_SIZE);
        long r = LiveSizeStrategy.computeBytesRemaining(det, FILE_SIZE, FILE_SIZE, NO_LEN);
        assertEquals(C.RESULT_END_OF_INPUT, r);
    }

    @Test
    public void boundedFile_pastEnd_throws() {
        LiveModeDetector det = new LiveModeDetector(false);
        det.onOpen(FILE_SIZE);
        try {
            LiveSizeStrategy.computeBytesRemaining(det, FILE_SIZE, FILE_SIZE + 10, NO_LEN);
            fail("Expected DataSourceException");
        } catch (DataSourceException expected) {
            // ok
        }
    }

    @Test
    public void boundedFile_capsAtRequestedLength() throws Exception {
        LiveModeDetector det = new LiveModeDetector(false);
        det.onOpen(FILE_SIZE);
        long r = LiveSizeStrategy.computeBytesRemaining(det, FILE_SIZE, 100, 5000);
        assertEquals(5000, r);
    }

    // ---------- timeshifted, not yet actually-live (frozen) ----------

    @Test
    public void timeshiftedFirstOpen_freezesSize() throws Exception {
        LiveModeDetector det = new LiveModeDetector(true);
        det.onOpen(FILE_SIZE);
        assertEquals(FILE_SIZE, det.frozenSize());
        assertFalse(det.isActuallyLive());
        long r = LiveSizeStrategy.computeBytesRemaining(det, FILE_SIZE, 0, NO_LEN);
        assertEquals(FILE_SIZE, r);
    }

    @Test
    public void timeshiftedSecondOpenSameSize_staysFrozen() throws Exception {
        LiveModeDetector det = new LiveModeDetector(true);
        det.onOpen(FILE_SIZE);
        det.onOpen(FILE_SIZE);
        assertFalse(det.isActuallyLive());
        long r = LiveSizeStrategy.computeBytesRemaining(det, FILE_SIZE, 500, NO_LEN);
        assertEquals(FILE_SIZE - 500, r);
    }

    // ---------- timeshifted, actually-live (headroom) ----------

    @Test
    public void timeshiftedGrowsBetweenOpens_promotesToActuallyLive() {
        LiveModeDetector det = new LiveModeDetector(true);
        det.onOpen(FILE_SIZE);
        det.onOpen(FILE_SIZE + 5_000_000L);
        assertTrue(det.isActuallyLive());
    }

    @Test
    public void actuallyLiveAfterPreparation_returnsHeadroom() throws Exception {
        LiveModeDetector det = new LiveModeDetector(true);
        det.onOpen(FILE_SIZE);
        det.onOpen(FILE_SIZE + 1_000_000L);
        long sizeNow = FILE_SIZE + 2_000_000L;
        det.onOpen(sizeNow);
        long r = LiveSizeStrategy.computeBytesRemaining(det, sizeNow, 1000, NO_LEN);
        long expected = (sizeNow + LiveSizeStrategy.LIVE_HEADROOM_BYTES) - 1000;
        assertEquals(expected, r);
    }

    @Test
    public void actuallyLiveDuringPreparationOpens_doesNotApplyHeadroom() throws Exception {
        // Even if growth detected on open #2, openCount must be > 2 before
        // headroom kicks in. This protects PsDurationReader.
        LiveModeDetector det = new LiveModeDetector(true);
        det.onOpen(FILE_SIZE);
        det.onOpen(FILE_SIZE + 1_000_000L);
        assertEquals(2, det.openCount());
        assertTrue(det.isActuallyLive());
        long r = LiveSizeStrategy.computeBytesRemaining(det, FILE_SIZE + 1_000_000L, 0, NO_LEN);
        assertEquals(FILE_SIZE + 1_000_000L, r);
    }

    @Test
    public void actuallyLive_capsAtRequestedLength() throws Exception {
        LiveModeDetector det = new LiveModeDetector(true);
        det.onOpen(FILE_SIZE);
        det.onOpen(FILE_SIZE + 1_000_000L);
        long sizeNow = FILE_SIZE + 2_000_000L;
        det.onOpen(sizeNow);
        long r = LiveSizeStrategy.computeBytesRemaining(det, sizeNow, 0, 12345);
        assertEquals(12345, r);
    }

    // ---------- frozen-snapshot stale catchup ----------

    @Test
    public void positionPastFrozenButWithinReportedSize_advancesFrozen() throws Exception {
        LiveModeDetector det = new LiveModeDetector(true);
        det.onOpen(FILE_SIZE);
        long grownSize = FILE_SIZE + 500_000L;
        long r = LiveSizeStrategy.computeBytesRemaining(det, grownSize, FILE_SIZE + 100, NO_LEN);
        assertEquals(grownSize - (FILE_SIZE + 100), r);
        assertEquals(grownSize, det.frozenSize());
    }
}
