package sagex.miniclient.ngcontext;

/**
 * Bus event posted when the NG playback context changes (new media opened, context received,
 * or media closed). Uses a sealed interface so consumers can pattern-match exhaustively.
 */
public sealed interface NgPlaybackContextEvent {

    /** Context was received or updated while media is playing. */
    record Updated(NgPlaybackContext previous, NgPlaybackContext current) implements NgPlaybackContextEvent {}

    /** Media was closed — context cleared. */
    record Cleared(NgPlaybackContext previous) implements NgPlaybackContextEvent {}
}
