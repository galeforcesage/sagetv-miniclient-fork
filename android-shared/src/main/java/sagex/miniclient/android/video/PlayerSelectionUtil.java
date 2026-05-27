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

    // One-shot guard per process for the user-visible landmine swap toast.
    // We always log the swap; we toast only the first time per process so the
    // user is told (once) why we silently routed to IJK, without spamming a
    // toast on every recording in a marathon FF session.
    private static volatile boolean swapToastShown = false;

    /**
     * Show a one-shot user-visible toast on the first landmine-driven player
     * swap of the process. Subsequent swaps are silent (still logged). Safe
     * to call from any thread; uses {@link android.os.Handler} on the main
     * Looper. Pass null context to silently skip (e.g. tests).
     */
    public static void notifyLandmineSwap(final android.content.Context ctx, final String reason)
    {
        if (ctx == null) return;
        if (swapToastShown) return;
        swapToastShown = true;
        final String msg = "Auto-switched to IJK player (" + reason + ")";
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show(); }
                catch (Throwable ignored) {}
            }
        });
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

    /**
     * NG-server landmine: bare {@code push:} OPENURL with no format hint
     * string after the colon. Observed on sagetv-mine NG May 2026: the
     * server's profile resolver picked DIRECT_PLAY / REMUX but emitted an
     * OPENURL whose payload-format descriptor is empty. ExoPlayer needs
     * the container hint to pick the right Extractor; without it the
     * extractor falls back to sniff and on the Fold (Snapdragon 8 Gen 2)
     * partially succeeds &mdash; audio demuxes, video does not, screen
     * stays black with sound.
     *
     * <p>IJK uses libavformat to sniff and handles a wider set of input
     * shapes than ExoPlayer's hint-driven extractors, so swap to IJK when
     * we see this pattern. Same belt-and-suspenders strategy as
     * {@link #isExoPsMpeg4Landmine}.</p>
     *
     * <p>Pattern recognised: the URL ends with literally {@code push:} (no
     * characters after the colon) or {@code push:;} or {@code push:[...} with
     * no top-level {@code F=} format token. The full-form URLs we DO want to
     * route to ExoPlayer (e.g. {@code push:f=MPEG2-TS;[bf=vid;f=H264;...]})
     * always carry an {@code f=} segment immediately after {@code push:}.</p>
     */
    public static boolean isBarePushUrl(String url)
    {
        if (url == null) return false;
        String u = url.toUpperCase(java.util.Locale.ROOT);
        int p = u.indexOf("PUSH:");
        if (p < 0) return false;
        // What follows "PUSH:" up to the end of the URL token.
        String tail = u.substring(p + "PUSH:".length()).trim();
        if (tail.isEmpty()) return true;
        // A well-formed push URL always starts the format descriptor with
        // F=<container>; right after the colon. Anything else (empty,
        // bracket-only, garbage) is a server bug we should route around.
        return !tail.startsWith("F=");
    }

    /**
     * Exo MPEG-2 decode landmine: stream is PUSH with MPEG-2 video, but this
     * device does not expose a video/mpeg2 decoder to Exo's MediaCodec path.
     * In that case Exo commonly yields audio-only playback; route to IJK.
     */
    public static boolean isExoMpeg2NoDecoderLandmine(String url, boolean exoCanDecodeMpeg2)
    {
        if (exoCanDecodeMpeg2) return false;
        if (url == null) return false;
        String u = url.toUpperCase(java.util.Locale.ROOT);
        if (!u.contains("PUSH:")) return false;

        int vidIdx = u.indexOf("BF=VID");
        if (vidIdx < 0) return false;
        int end = Math.min(u.length(), vidIdx + 96);
        String window = u.substring(vidIdx, end);
        return window.contains("F=MPEG2-VIDEO;")
                || window.contains("F=MPEG2-VIDEO]")
                || window.contains("F=MPEG2-VIDEO@HL;")
                || window.contains("F=MPEG2-VIDEO@HL]");
    }
}
