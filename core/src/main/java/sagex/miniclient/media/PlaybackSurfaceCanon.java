package sagex.miniclient.media;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Canonicalizes this client's internal SageTV codec / container token spelling
 * to the strict Protocol 2.1 "Playback Surface" wire vocabulary the NG server
 * accepts in {@code PLAYBACK_SURFACE_<id>_{VIDEO_CODECS,AUDIO_CODECS,CONTAINERS}}.
 *
 * <p>The per-player capability sets we already compute ({@code EXO_VIDEO_CODECS},
 * {@code IJK_AUDIO_CODECS}, {@code EXO_PULL_AV_CONTAINERS}, ...) carry the enum
 * {@code sageTVNames()} spellings (e.g. {@code H.264}, {@code AAC-HE},
 * {@code DOLBYTRUEHD}, {@code FLASHVIDEO}, {@code QUICKTIME},
 * {@code MPEG2-VIDEO@HL}). The server's {@code PlaybackSurfaceSet} canonicalizes a
 * few of these and drops the rest, so mapping here (a) keeps codecs the server
 * would otherwise discard for a spelling mismatch (HE-AAC, TRUEHD, FLV) and
 * (b) guarantees we only ever put canonical tokens on the wire.
 *
 * <p>Canonical target vocabularies mirror
 * {@code sage.client.PlaybackSurfaceSet.CANONICAL_*} exactly. Any token that
 * does not map to a canonical name is dropped (e.g. VP8, VC1/WMV, MJPEG, H.263,
 * WMA, Vorbis, ALAC, and audio-only containers like OGG/WAV/ASF).
 */
public final class PlaybackSurfaceCanon
{
    private PlaybackSurfaceCanon() { }

    private static final Set<String> CANONICAL_VIDEO = new HashSet<String>(Arrays.asList(
            "MPEG1-VIDEO", "MPEG2-VIDEO", "MPEG4-VIDEO", "H264", "HEVC", "VP9", "AV1"));

    private static final Set<String> CANONICAL_AUDIO = new HashSet<String>(Arrays.asList(
            "MP2", "MP3", "AAC", "HE-AAC", "AC3", "EAC3", "AC4",
            "DTS", "TRUEHD", "OPUS", "FLAC", "PCM"));

    private static final Set<String> CANONICAL_CONTAINER = new HashSet<String>(Arrays.asList(
            "MPEG2-PS", "MPEG2-TS", "MP4", "MATROSKA", "AVI", "MOV", "FLV", "WEBM"));

    /** Map a per-player video token to its canonical spelling, or {@code null} to drop. */
    static String video(String raw)
    {
        if (raw == null) return null;
        String u = raw.trim().toUpperCase(Locale.ROOT);
        if (u.isEmpty()) return null;
        // Strip decode-profile suffix (e.g. MPEG2-VIDEO@HL -> MPEG2-VIDEO).
        int at = u.indexOf('@');
        if (at > 0) u = u.substring(0, at);
        if ("H.264".equals(u) || "H264".equals(u)) return "H264";
        if ("H.265".equals(u) || "H265".equals(u) || "HEVC".equals(u)) return "HEVC";
        if ("MSMPEG4-VIDEO".equals(u)) return "MPEG4-VIDEO";
        return CANONICAL_VIDEO.contains(u) ? u : null;
    }

    /** Map a per-player audio token to its canonical spelling, or {@code null} to drop. */
    static String audio(String raw)
    {
        if (raw == null) return null;
        String u = raw.trim().toUpperCase(Locale.ROOT);
        if (u.isEmpty()) return null;
        if ("MPG1L2".equals(u)) return "MP2";
        if ("MPG1L3".equals(u)) return "MP3";
        if ("AAC-HE".equals(u)) return "HE-AAC";
        if ("AC-3".equals(u)) return "AC3";
        if ("E-AC-3".equals(u) || "EC-3".equals(u)) return "EAC3";
        if ("AC-4".equals(u)) return "AC4";
        if ("DCA".equals(u) || "DTS-HD".equals(u) || "DTS-MA".equals(u)) return "DTS";
        if ("DOLBYTRUEHD".equals(u)) return "TRUEHD";
        if ("PCM_S16LE".equals(u)) return "PCM";
        return CANONICAL_AUDIO.contains(u) ? u : null;
    }

    /** Map a per-player container token to its canonical spelling, or {@code null} to drop. */
    static String container(String raw)
    {
        if (raw == null) return null;
        String u = raw.trim().toUpperCase(Locale.ROOT);
        if (u.isEmpty()) return null;
        if ("QUICKTIME".equals(u)) return "MP4";
        if ("MKV".equals(u)) return "MATROSKA";
        if ("TS".equals(u)) return "MPEG2-TS";
        if ("MPEG".equals(u) || "MPG".equals(u)) return "MPEG2-PS";
        if ("FLASHVIDEO".equals(u)) return "FLV";
        return CANONICAL_CONTAINER.contains(u) ? u : null;
    }

    public static List<String> canonVideo(List<String> raw)  { return map(raw, 'v'); }
    public static List<String> canonAudio(List<String> raw)  { return map(raw, 'a'); }
    public static List<String> canonContainer(List<String> raw) { return map(raw, 'c'); }

    private static List<String> map(List<String> raw, char kind)
    {
        List<String> out = new ArrayList<String>();
        if (raw == null) return out;
        for (String t : raw)
        {
            String c = (kind == 'v') ? video(t) : (kind == 'a') ? audio(t) : container(t);
            if (c != null && !out.contains(c)) out.add(c);
        }
        return out;
    }

    /** Comma-join, never null. */
    public static String csv(List<String> tokens)
    {
        if (tokens == null || tokens.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++)
        {
            if (i > 0) sb.append(',');
            sb.append(tokens.get(i));
        }
        return sb.toString();
    }
}
