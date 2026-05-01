package sagex.miniclient.android.middleware;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Frozen capability profile for Lane A (Windows Placeshifter parity).
 * <p>
 * Advertises only formats the Windows Placeshifter can handle.
 * HEVC and Android-only formats are never included.
 */
public final class WindowsCompatProfile
{
    private WindowsCompatProfile() {}

    /** Push containers Windows Placeshifter supports */
    public static final List<String> PUSH_CONTAINERS = Collections.unmodifiableList(
        Arrays.asList("MPEG2-PS", "MPEG2-TS", "MPEG1-PS")
    );

    /** Pull containers Windows Placeshifter supports */
    public static final List<String> PULL_CONTAINERS = Collections.unmodifiableList(
        Arrays.asList(
            "AVI", "FLASHVIDEO", "Quicktime", "Ogg", "MP3", "AAC",
            "WMV", "ASF", "FLAC", "MATROSKA", "WAV", "AC3"
        )
    );

    /** Video codecs Windows Placeshifter supports — no HEVC */
    public static final List<String> VIDEO_CODECS = Collections.unmodifiableList(
        Arrays.asList("MPEG2-VIDEO", "MPEG1-VIDEO", "MPEG4-VIDEO", "H.264", "VC1", "WMV9")
    );

    /** Audio codecs Windows Placeshifter supports */
    public static final List<String> AUDIO_CODECS = Collections.unmodifiableList(
        Arrays.asList(
            "MP3", "AAC", "AC3", "EAC3", "DTS", "MPEG2-AUDIO",
            "FLAC", "VORBIS", "WMA", "PCM", "WMAPRO"
        )
    );

    /**
     * Returns true if the given format/codec should be excluded in Lane A.
     */
    public static boolean isExcludedFormat(String format)
    {
        if (format == null) return false;
        String upper = format.toUpperCase();
        return upper.contains("HEVC") || upper.contains("H.265")
            || upper.contains("VP9") || upper.contains("AV1");
    }

    /**
     * Formats the push container list as a comma-separated string for negotiation.
     */
    public static String getPushContainersString()
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PUSH_CONTAINERS.size(); i++)
        {
            if (i > 0) sb.append(",");
            sb.append(PUSH_CONTAINERS.get(i));
        }
        return sb.toString();
    }

    /**
     * Formats the pull container list as a comma-separated string for negotiation.
     */
    public static String getPullContainersString()
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PULL_CONTAINERS.size(); i++)
        {
            if (i > 0) sb.append(",");
            sb.append(PULL_CONTAINERS.get(i));
        }
        return sb.toString();
    }

    public static String getVideoCodecsString()
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < VIDEO_CODECS.size(); i++)
        {
            if (i > 0) sb.append(",");
            sb.append(VIDEO_CODECS.get(i));
        }
        return sb.toString();
    }

    public static String getAudioCodecsString()
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < AUDIO_CODECS.size(); i++)
        {
            if (i > 0) sb.append(",");
            sb.append(AUDIO_CODECS.get(i));
        }
        return sb.toString();
    }
}
