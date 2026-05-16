package sagex.miniclient.android.video;

/**
 * Tiny helpers for routing a stream to the right player BEFORE we even try
 * to prepare it. Today there is exactly one well-known landmine pattern
 * worth recognising up-front:
 *
 * <p><b>MPEG-4 Part 2 video inside a SageTV "dynamic" MPEG-PS push.</b>
 * Pattern: {@code push:f=MPEG2-PS;[bf=vid;f=MPEG4;...]} (also MPEG2-TS as
 * outer container in some configs). The 9.2.x server emits this when the
 * client advertises NG codec caps (i.e. doesn't claim MPEG-2-VIDEO support)
 * but the source is MPEG-2 OTA / cable; the server transcodes the video to
 * MPEG-4 Part 2 and re-muxes into MPEG-PS. ExoPlayer 2.18.1's
 * {@code PsExtractor.H262Reader.parseCsdBuffer} throws
 * {@code ArrayIndexOutOfBounds} on this combination &mdash; the playback
 * surface stays black, the error is reported as
 * {@code ERROR_CODE_IO_UNSPECIFIED}, and the 12-retry loop never recovers.
 * IJK uses libavformat to demux MPEG-PS and handles MPEG-4 Part 2 fine.</p>
 *
 * <p>Detection is intentionally loose &mdash; the URL is whatever the server
 * sent and we don't want to be brittle about whitespace, attribute order,
 * or future tag additions. The check is also cheap (substring scan) and
 * called exactly once per OPENURL.</p>
 */
public final class PlayerSelectionUtil
{
    private PlayerSelectionUtil()
    {
    }

    /**
     * @return true if the URL looks like a SageTV "dynamic Placeshifter"
     *         push of MPEG-4 Part 2 video inside MPEG-PS &mdash; the
     *         exact case ExoPlayer 2.18.1's PsExtractor crashes on.
     */
    public static boolean isExoPsMpeg4Landmine(String url)
    {
        if (url == null) return false;
        // Server URLs are uppercase tokens; lowercase only if someone has
        // reformatted them in transit. Be defensive.
        String u = url.toUpperCase(java.util.Locale.ROOT);
        if (!u.contains("PUSH:")) return false;
        // Outer container must be an MPEG program/transport stream
        if (!(u.contains("F=MPEG2-PS") || u.contains("F=MPEG2-TS"))) return false;
        // Inner video block must declare MPEG-4 (not MPEG2-VIDEO, not H.264, not HEVC)
        // The block looks like "[BF=VID;F=MPEG4;...]" - guard against accidentally
        // matching the outer MPEG2-PS container by requiring BF=VID nearby.
        int vidIdx = u.indexOf("BF=VID");
        if (vidIdx < 0) return false;
        // Look at the next ~64 chars after BF=VID for F=MPEG4 (without trailing
        // -anything, so we don't catch MPEG4-VIDEO if a future server uses it).
        int end = Math.min(u.length(), vidIdx + 80);
        String window = u.substring(vidIdx, end);
        // Match F=MPEG4 followed by ; or end-of-block ] (not F=MPEG4-VIDEO etc.)
        return window.contains("F=MPEG4;") || window.contains("F=MPEG4]");
    }
}
