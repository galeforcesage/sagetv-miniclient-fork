package sagex.miniclient.android.video.exoplayer2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * State machine that tracks whether a timeshifted SageTV stream is a
 * "truly live" (still-recording, growing) file or a completed recording
 * that just happens to be opened in timeshift mode.
 *
 * <p>Lifecycle (per playback session):
 * <ul>
 *   <li>First {@code open()}: capture the reported size as {@link #frozenSize}.
 *       This stops {@code PsDurationReader} from looping endlessly when
 *       successive opens would otherwise return larger sizes for a growing
 *       recording.
 *   <li>Subsequent {@code open()}s: if the reported size has grown past
 *       {@code frozenSize}, transition into {@link #isActuallyLive() actually-live}
 *       mode. Once set, this flag never clears for the session.
 * </ul>
 *
 * <p>Extracted from {@code Exo2PullDataSource} (Phase 3) so the live-mode
 * decision can be reasoned about, logged consistently, and unit-tested
 * independently of ExoPlayer / network plumbing.
 */
public final class LiveModeDetector {

    private static final Logger log = LoggerFactory.getLogger(LiveModeDetector.class);

    private final boolean timeshifted;
    private long frozenSize = -1L;
    private boolean isActuallyLive = false;
    private int openCount = 0;

    public LiveModeDetector(boolean timeshifted) {
        this.timeshifted = timeshifted;
    }

    /**
     * Notify the detector of a new {@code open()} call. Updates internal state
     * and returns the (possibly updated) frozen size.
     *
     * @param reportedSize the size reported by the underlying data source
     *                     for this open
     */
    public void onOpen(long reportedSize) {
        openCount++;
        if (timeshifted && frozenSize < 0) {
            frozenSize = reportedSize;
            log.debug("LiveModeDetector: open #{} — freezing size at {}", openCount, frozenSize);
        } else if (timeshifted && reportedSize > frozenSize) {
            if (!isActuallyLive) {
                log.info("LiveModeDetector: open #{} — detected actual live growth ({} -> {})",
                        openCount, frozenSize, reportedSize);
            }
            isActuallyLive = true;
        }
    }

    /**
     * Force-advance {@link #frozenSize} when the requested read position is
     * beyond the current snapshot but still within the server-reported size.
     */
    public void advanceFrozenSize(long newFrozenSize) {
        if (newFrozenSize > frozenSize) {
            frozenSize = newFrozenSize;
            log.debug("LiveModeDetector: advanced frozen size to {}", frozenSize);
        }
    }

    public boolean isTimeshifted() {
        return timeshifted;
    }

    public boolean isActuallyLive() {
        return isActuallyLive;
    }

    public int openCount() {
        return openCount;
    }

    public long frozenSize() {
        return frozenSize;
    }
}
