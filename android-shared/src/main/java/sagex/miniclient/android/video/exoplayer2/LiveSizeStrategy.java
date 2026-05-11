package sagex.miniclient.android.video.exoplayer2;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.upstream.DataSourceException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure (no I/O) computation of the value to return from
 * {@link com.google.android.exoplayer2.upstream.DataSource#open(com.google.android.exoplayer2.upstream.DataSpec)}
 * for a SageTV pull stream, given the current {@link LiveModeDetector} state
 * and the size reported by the underlying data source.
 *
 * <p>Three regimes:
 * <ul>
 *   <li><b>Bounded</b>: non-timeshifted file or completed recording opened in
 *       timeshift mode. Returns {@code effectiveSize - position}.
 *   <li><b>Frozen</b>: timeshifted but not yet detected as actually-live
 *       (only one open seen, or size hasn't grown). Same as bounded but uses
 *       the frozen snapshot to avoid the {@code PsDurationReader} loop.
 *   <li><b>Live headroom</b>: timeshifted, actually-live, openCount &gt; 2.
 *       Returns {@code (size + ~10.8 GB) - position} so ExoPlayer stays on
 *       its bounded-source path but doesn't EOF as the file grows. The
 *       {@code LinearPsSeekMap} separately uses the {@code LiveSizeProvider}
 *       for accurate FF/REW.
 * </ul>
 *
 * <p>Extracted from {@code Exo2PullDataSource.open()} (Phase 3) for
 * testability and to make the regime-selection logic explicit.
 */
public final class LiveSizeStrategy {

    private static final Logger log = LoggerFactory.getLogger(LiveSizeStrategy.class);

    /** Headroom for actually-live mode: ~2 hours at 12 Mbps = ~10.8 GB. */
    static final long LIVE_HEADROOM_BYTES = 2L * 3600L * 12L * 1024L * 1024L / 8L;

    private LiveSizeStrategy() {}

    /**
     * Compute the value to return from {@code DataSource.open()}.
     *
     * @param det          the live-mode state machine (already updated for this open)
     * @param reportedSize the size reported by the underlying data source for this open
     * @param position     {@code dataSpec.position}
     * @param length       {@code dataSpec.length} ({@link C#LENGTH_UNSET} if no cap)
     * @return bytes available from {@code position}, or
     *         {@link C#RESULT_END_OF_INPUT} / {@link C#LENGTH_UNSET} as appropriate
     * @throws DataSourceException if the requested position is out of range
     *                             for a non-live source
     */
    public static long computeBytesRemaining(
            LiveModeDetector det, long reportedSize, long position, long length)
            throws DataSourceException {

        // Live headroom regime: after PsDurationReader finishes (first 2 opens),
        // give ExoPlayer a generous bounded length so it doesn't hit premature
        // EOF as the recording grows.
        if (det.isTimeshifted() && det.isActuallyLive() && det.openCount() > 2) {
            long headroomSize = reportedSize + LIVE_HEADROOM_BYTES;
            long bytesRemaining = headroomSize - position;
            if (bytesRemaining <= 0) {
                log.debug("Open #{}: live mode, position {} past headroom {}, returning LENGTH_UNSET",
                        det.openCount(), position, headroomSize);
                return C.LENGTH_UNSET;
            }
            if (length != C.LENGTH_UNSET) {
                bytesRemaining = Math.min(bytesRemaining, length);
            }
            log.debug("Open #{}: live mode, size {}, returning headroom bytesRemaining {}",
                    det.openCount(), reportedSize, bytesRemaining);
            return bytesRemaining;
        }

        // Frozen vs bounded: timeshifted-but-not-yet-live uses the frozen
        // snapshot; everything else uses the live size.
        long effectiveSize =
                (det.isTimeshifted() && det.frozenSize() > 0 && !det.isActuallyLive())
                        ? det.frozenSize()
                        : reportedSize;

        if (position == effectiveSize) {
            log.debug("END OF INPUT");
            return C.RESULT_END_OF_INPUT;
        }
        if (position > effectiveSize) {
            // For timeshifted files, position beyond the frozen snapshot but
            // still within the server-reported size means the snapshot is just
            // stale — advance it.
            if (det.isTimeshifted() && position <= reportedSize) {
                det.advanceFrozenSize(reportedSize);
                effectiveSize = reportedSize;
            } else {
                log.debug("IO_READ_POSITION_OUT_OF_RANGE position={} effectiveSize={}",
                        position, effectiveSize);
                throw new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
            }
        }

        long bytesRemaining = effectiveSize - position;
        if (length != C.LENGTH_UNSET) {
            bytesRemaining = Math.min(bytesRemaining, length);
        }
        return bytesRemaining;
    }
}
