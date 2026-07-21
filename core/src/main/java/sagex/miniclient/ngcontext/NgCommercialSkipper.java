package sagex.miniclient.ngcontext;

import java.util.OptionalLong;

/**
 * Determines whether the current playback position is inside a commercial break
 * and provides a skip-to target if so.
 * <p>
 * Commercial breaks in {@link NgPlaybackContext#commercialBreaksMs()} are stored as
 * sequential start/end pairs: [start0, end0, start1, end1, ...].
 * <p>
 * This is a future feature stub — auto-skip is not yet wired into the player.
 * Once enabled, it will be consulted periodically (e.g. every second) to determine
 * if the player should jump forward past a commercial.
 */
public final class NgCommercialSkipper {

    private NgCommercialSkipper() { }

    /**
     * Given the current playback position and the commercial breaks array,
     * returns the skip-to target if the position is inside a commercial break.
     *
     * @param currentPositionMs current playback position in ms
     * @param commercialBreaksMs pairs of [start, end, start, end, ...] in ms
     * @return the end of the current commercial break to skip to, or empty if not in a commercial
     */
    public static OptionalLong getSkipTarget(long currentPositionMs, long[] commercialBreaksMs) {
        if (commercialBreaksMs == null || commercialBreaksMs.length < 2) {
            return OptionalLong.empty();
        }

        for (int i = 0; i + 1 < commercialBreaksMs.length; i += 2) {
            long start = commercialBreaksMs[i];
            long end = commercialBreaksMs[i + 1];
            if (currentPositionMs >= start && currentPositionMs < end) {
                return OptionalLong.of(end);
            }
        }
        return OptionalLong.empty();
    }

    /**
     * Returns the next commercial break start time after the given position,
     * or empty if there are no more commercials ahead.
     *
     * @param currentPositionMs current playback position in ms
     * @param commercialBreaksMs pairs of [start, end, start, end, ...] in ms
     * @return the start of the next upcoming commercial break, or empty
     */
    public static OptionalLong getNextCommercialStart(long currentPositionMs, long[] commercialBreaksMs) {
        if (commercialBreaksMs == null || commercialBreaksMs.length < 2) {
            return OptionalLong.empty();
        }

        for (int i = 0; i + 1 < commercialBreaksMs.length; i += 2) {
            long start = commercialBreaksMs[i];
            if (start > currentPositionMs) {
                return OptionalLong.of(start);
            }
        }
        return OptionalLong.empty();
    }
}
