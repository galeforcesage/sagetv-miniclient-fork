package sagex.miniclient.ngcontext;

/**
 * Bus event posted when the NG playback context changes (new media opened, context received,
 * or media closed). Subscribers should null-check both fields.
 */
public final class NgPlaybackContextEvent {

    /** Previous context, or null if this is the first context for this session. */
    public final NgPlaybackContext previous;

    /** Current context, or null if media was closed. */
    public final NgPlaybackContext current;

    public NgPlaybackContextEvent(NgPlaybackContext previous, NgPlaybackContext current) {
        this.previous = previous;
        this.current = current;
    }

    @Override
    public String toString() {
        return "NgPlaybackContextEvent{" +
                "previous=" + previous +
                ", current=" + current +
                '}';
    }
}
