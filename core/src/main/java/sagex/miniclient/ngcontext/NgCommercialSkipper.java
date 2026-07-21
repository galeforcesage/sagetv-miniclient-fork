package sagex.miniclient.ngcontext;

import java.util.List;
import java.util.OptionalLong;

/**
 * Determines whether the current playback position is inside a commercial break
 * and provides a skip-to target if so.
 * <p>
 * Commercial breaks come from {@link NgPlaybackContext.SkipContext#commercials()} as
 * a list of {@link NgPlaybackContext.SkipSegment} objects with startMs, endMs, type, and prerollMs.
 * <p>
 * This is a future feature stub — auto-skip is not yet wired into the player.
 * Once enabled, it will be consulted periodically (e.g. every second) to determine
 * if the player should jump forward past a commercial.
 */
public final class NgCommercialSkipper {

    private NgCommercialSkipper() { }

    /**
     * Given the current playback position and the commercial segments,
     * returns the skip-to target if the position is inside a commercial break.
     * Accounts for preroll (subtracts prerollMs from the effective start).
     *
     * @param currentPositionMs current playback position in ms
     * @param commercials       list of commercial skip segments
     * @return the end of the current commercial break to skip to, or empty if not in a commercial
     */
    public static OptionalLong getSkipTarget(long currentPositionMs, List<NgPlaybackContext.SkipSegment> commercials) {
        if (commercials == null || commercials.isEmpty()) {
            return OptionalLong.empty();
        }

        for (var seg : commercials) {
            long effectiveStart = seg.startMs() - seg.prerollMs();
            if (currentPositionMs >= effectiveStart && currentPositionMs < seg.endMs()) {
                return OptionalLong.of(seg.endMs());
            }
        }
        return OptionalLong.empty();
    }

    /**
     * Returns the next commercial break start time after the given position,
     * or empty if there are no more commercials ahead.
     *
     * @param currentPositionMs current playback position in ms
     * @param commercials       list of commercial skip segments
     * @return the start of the next upcoming commercial break, or empty
     */
    public static OptionalLong getNextCommercialStart(long currentPositionMs, List<NgPlaybackContext.SkipSegment> commercials) {
        if (commercials == null || commercials.isEmpty()) {
            return OptionalLong.empty();
        }

        for (var seg : commercials) {
            if (seg.startMs() > currentPositionMs) {
                return OptionalLong.of(seg.startMs());
            }
        }
        return OptionalLong.empty();
    }
}
