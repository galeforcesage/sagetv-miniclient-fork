package sagex.miniclient.android.video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sagex.miniclient.MiniClient;
import sagex.miniclient.android.prefs.AndroidPrefStore;
import sagex.miniclient.prefs.PrefStore;

/**
 * Emits a single structured log line per OPENURL summarising the playback
 * decision the client just made, plus any higher-quality alternatives that
 * were weighed and rejected.
 *
 * <p>Fields covered:</p>
 * <ul>
 *   <li>Outer container (from {@code push:f=...} or pull URL extension)</li>
 *   <li>Video codec (from {@code bf=vid;f=...})</li>
 *   <li>Audio codec (from {@code bf=aud;f=...})</li>
 *   <li>Streaming mode (push vs pull, derived from URL prefix)</li>
 *   <li>Bandwidth / latency category &mdash; server-side per
 *       {@code ClientSettings.md}; logged as "server-side" so the user
 *       knows where to look (server's BW estimate + RTT classifier)</li>
 *   <li>Selected player ({@code ExoPlayer} or {@code IJKPlayer}) and the
 *       reason (default pref, one-time swap, landmine swap, .exlink, etc.)</li>
 *   <li>Higher-quality alternatives that were weighed but disregarded</li>
 * </ul>
 *
 * <p>Output is a single log line at INFO level with key=value pairs, plus a
 * follow-up line listing disregarded alternatives. Easy to grep for
 * {@code PLAYBACK-DECISION} in logcat.</p>
 */
public final class PlaybackDecisionLog
{
    private static final Logger log = LoggerFactory.getLogger(PlaybackDecisionLog.class);

    private PlaybackDecisionLog() {}

    /**
     * @param client       miniclient instance (for prefs lookup)
     * @param url          the full OPENURL string just received from the server
     * @param chosenPlayer "ExoPlayer" or "IJKPlayer" (whatever was selected)
     * @param swapReason   null if no landmine/one-time swap; otherwise a short reason
     */
    public static void log(MiniClient client, String url, String chosenPlayer, String swapReason)
    {
        try
        {
            String streamingMode = streamingModeOf(url);
            String outerContainer = outerContainerOf(url);
            String videoCodec = innerCodecOf(url, "VID");
            String audioCodec = innerCodecOf(url, "AUD");

            String prefStreamMode = client.properties().getString(AndroidPrefStore.STREAMING_MODE, AndroidPrefStore.STREAMING_MODE_DEFAULT);
            String prefFixedEncoding = client.properties().getString(AndroidPrefStore.FIXED_ENCODING_PREFERENCE, AndroidPrefStore.FIXED_ENCODING_PREFERENCE_DEFAULT);
            String prefPushContainer = client.properties().getString(AndroidPrefStore.FIXED_ENCODING_FORMAT, AndroidPrefStore.FIXED_ENCODING_FORMAT_DEFAULT);
            String prefPushAudio = client.properties().getString(AndroidPrefStore.FIXED_ENCODING_AUDIO_CODEC, AndroidPrefStore.FIXED_ENCODING_AUDIO_CODEC_DEFAULT);
            String prefRemuxPreference = client.properties().getString(AndroidPrefStore.FIXED_REMUXING_PREFERENCE, AndroidPrefStore.FIXED_REMUXING_PREFERENCE_DEFAULT);
            String prefRemuxContainer = client.properties().getString(AndroidPrefStore.FIXED_REMUXING_FORMAT, AndroidPrefStore.FIXED_REMUXING_FORMAT_DEFAULT);
            String prefDefaultPlayer = client.properties().getString(PrefStore.Keys.default_player, "exoplayer");
            String prefFfmpegExt = client.properties().getString(PrefStore.Keys.exoplayer_ffmpeg_extension_tri, null);
            if (prefFfmpegExt == null)
            {
                int legacy = Integer.parseInt(client.properties().getString(
                        PrefStore.Keys.exoplayer_ffmpeg_extension_setting, "1"));
                prefFfmpegExt = (legacy == 0 ? "off" : legacy == 1 ? "needed" : "preferred");
            }

            String selectionReason = (swapReason == null || swapReason.isEmpty())
                    ? ("pref default_player=" + prefDefaultPlayer)
                    : ("auto-swap: " + swapReason);

            log.info("PLAYBACK-DECISION streaming={} container={} video={} audio={} player={} reason=\"{}\" "
                            + "prefs[streamMode={}, fixedEnc={}, pushContainer={}, pushAudio={}, "
                            + "remux={}, remuxContainer={}, ffmpegExt={}] "
                            + "bandwidth=server-side(adaptive) latency=server-side(LAN<10ms / WAN>80ms classifier)",
                    streamingMode, outerContainer, videoCodec, audioCodec, chosenPlayer, selectionReason,
                    prefStreamMode, prefFixedEncoding, prefPushContainer, prefPushAudio,
                    prefRemuxPreference, prefRemuxContainer, prefFfmpegExt);

            String disregarded = analyzeDisregarded(streamingMode, outerContainer, videoCodec, audioCodec,
                    chosenPlayer, swapReason, prefPushAudio, prefPushContainer, prefFixedEncoding);
            if (disregarded != null && !disregarded.isEmpty())
            {
                log.info("PLAYBACK-DECISION disregarded: {}", disregarded);
            }
        }
        catch (Throwable t)
        {
            log.warn("PLAYBACK-DECISION logging failed (non-fatal): {}", t.toString());
        }
    }

    private static String streamingModeOf(String url)
    {
        if (url == null) return "unknown";
        String u = url.toUpperCase(java.util.Locale.ROOT);
        if (u.contains("PUSH:")) return "push";
        if (u.startsWith("STV://") || u.contains("/PULL:") || u.contains(":7818")) return "pull";
        return "unknown";
    }

    private static String outerContainerOf(String url)
    {
        if (url == null) return "unknown";
        String u = url.toUpperCase(java.util.Locale.ROOT);
        int p = u.indexOf("PUSH:F=");
        if (p >= 0)
        {
            int start = p + "PUSH:F=".length();
            int end = u.indexOf(';', start);
            if (end < 0) end = u.indexOf('[', start);
            if (end < 0) end = u.length();
            return u.substring(start, end);
        }
        // Fall back to file extension for pull
        int q = u.indexOf('?');
        String stripped = (q > 0) ? u.substring(0, q) : u;
        int dot = stripped.lastIndexOf('.');
        if (dot > 0 && dot > stripped.length() - 6) return stripped.substring(dot + 1);
        return "unknown";
    }

    /**
     * Extract an inner-block codec, e.g. for {@code [bf=vid;f=MPEG4;]} with
     * type=="VID" returns {@code MPEG4}.
     */
    private static String innerCodecOf(String url, String type)
    {
        if (url == null) return "unknown";
        String u = url.toUpperCase(java.util.Locale.ROOT);
        String marker = "BF=" + type;
        int b = u.indexOf(marker);
        if (b < 0) return "n/a";
        // Start AFTER the marker so we don't match BF=VID/BF=AUD's own "F=".
        int searchFrom = b + marker.length();
        int fStart = u.indexOf("F=", searchFrom);
        if (fStart < 0) return "unknown";
        fStart += 2;
        int end = fStart;
        while (end < u.length())
        {
            char c = u.charAt(end);
            if (c == ';' || c == ']' || c == ',') break;
            end++;
        }
        return u.substring(fStart, end);
    }

    private static String analyzeDisregarded(String streamingMode, String container, String video, String audio,
                                             String player, String swapReason,
                                             String prefPushAudio, String prefPushContainer, String prefFixedEnc)
    {
        StringBuilder sb = new StringBuilder();

        // Landmine swap → ExoPlayer's HW decode was disregarded for compat
        if (swapReason != null && swapReason.contains("PS+MPEG-4"))
        {
            appendItem(sb, "ExoPlayer + HW H.264/HEVC decode (server emitted MPEG-4 Part 2 in MPEG-PS; "
                    + "ExoPlayer's PsExtractor crashes on this combo, IJK's libavformat handles it)");
        }
        if (swapReason != null && swapReason.contains("bare-push"))
        {
            appendItem(sb, "ExoPlayer (server emitted bare push: URL with no format hint; "
                    + "ExoPlayer cannot pick an Extractor without a hint, IJK sniffs via libavformat)");
        }

        // MPEG-4 Part 2 video is always a quality loss vs H.264/HEVC
        if ("MPEG4".equalsIgnoreCase(video))
        {
            appendItem(sb, "H.264 or HEVC video (server transcoded source to MPEG-4 Part 2 ~VCD-tier; "
                    + "likely because client doesn't advertise MPEG-2-VIDEO and source was MPEG-2 OTA/cable, "
                    + "OR remux preference prevented direct-play)");
        }

        // MP2 audio is a quality loss vs AC3/EAC3
        if ("MP2".equalsIgnoreCase(audio))
        {
            if ("mp2".equalsIgnoreCase(prefPushAudio))
            {
                appendItem(sb, "AC3 audio (client preference fixed_encoding/audio_codec=mp2 — change to ac3 "
                        + "for 5.1 surround at higher quality)");
            }
            else
            {
                appendItem(sb, "AC3 audio (server picked MP2 stereo; client prefs request AC3 — "
                        + "server may have downgraded for compatibility or bandwidth)");
            }
        }

        // MPEG-PS push container is worse than MKV/TS for modern decoders
        if ("MPEG2-PS".equalsIgnoreCase(container) && "push".equals(streamingMode))
        {
            if ("dvd".equalsIgnoreCase(prefPushContainer))
            {
                appendItem(sb, "MKV or MPEG-TS container (client preference fixed_encoding/format=dvd forces MPEG-PS)");
            }
            else
            {
                appendItem(sb, "MKV or MPEG-TS container (server picked MPEG-PS; client prefs request MKV)");
            }
        }

        // If transcoding preference is "always" we lose adaptive bitrate above the ceiling
        if ("always".equalsIgnoreCase(prefFixedEnc))
        {
            appendItem(sb, "source-quality direct-play (client preference fixed_encoding/preference=always "
                    + "forces transcode; server uses fixed profile as bitrate ceiling)");
        }

        return sb.toString();
    }

    private static void appendItem(StringBuilder sb, String item)
    {
        if (sb.length() > 0) sb.append(" | ");
        sb.append(item);
    }
}
