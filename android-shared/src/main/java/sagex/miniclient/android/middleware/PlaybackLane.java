package sagex.miniclient.android.middleware;

/**
 * Playback lane selection.
 * <p>
 * Lane A: Windows-compatible Placeshifter (TS + PS only).
 * Lane B: Extended formats (HEVC, etc.).
 */
public enum PlaybackLane
{
    /**
     * Windows Placeshifter parity. TS/PS only.
     * Negotiation advertises only Windows-equivalent capabilities.
     * Trickplay semantics match DirectShow blocking behavior.
     */
    LANE_A_PLACESHIFTER,

    /**
     * Extended format support. HEVC and Android-specific codecs.
     * Separate negotiation profile. Falls back to Lane A on instability.
     */
    LANE_B_EXTENDED
}
