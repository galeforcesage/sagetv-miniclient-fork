package sagex.miniclient.android.media;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

import com.google.android.exoplayer2.audio.AudioCapabilities;
import com.google.android.exoplayer2.ext.ffmpeg.FfmpegLibrary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import sagex.miniclient.android.prefs.AndroidPrefStore;
import sagex.miniclient.media.AudioCodec;
import sagex.miniclient.media.Container;
import sagex.miniclient.media.VideoCodec;
import sagex.miniclient.prefs.PrefStore;

/**
 * Phase 2 helper: returns the auto-detected boolean for a given container or
 * codec on the current device + player combination. The detection mirrors the
 * "automatic" branch of {@link sagex.miniclient.android.AndroidMiniClientOptions}
 * so the codec-settings UI and the runtime capability emission stay in sync.
 *
 * <p>Static, read-only, no caching. Callers should compute results lazily and
 * reuse them where possible since {@link MediaCodecList} construction is not
 * free.</p>
 */
public final class CodecCapabilityDetector
{
    private CodecCapabilityDetector() { }

    /** True when the configured player can demux the container natively. */
    public static boolean isContainerSupported(Context ctx, AndroidPrefStore prefs, Container container)
    {
        if (isExoPlayer(prefs))
        {
            return isSupportedExoPlayerContainer(container);
        }
        // IJKPlayer assumed to handle everything.
        return true;
    }

    /** True when the configured player can decode the video codec on this device. */
    public static boolean isVideoCodecSupported(Context ctx, AndroidPrefStore prefs, VideoCodec codec)
    {
        if (!isExoPlayer(prefs))
        {
            return true; // IJKPlayer assumed to handle everything.
        }
        for (String mime : collectMimeTypes("video/"))
        {
            if (codec.hasAndroidMimeType(mime)) return true;
        }
        return false;
    }

    /** True when the configured player can decode the audio codec on this device. */
    public static boolean isAudioCodecSupported(Context ctx, AndroidPrefStore prefs, AudioCodec codec)
    {
        if (!isExoPlayer(prefs))
        {
            return true;
        }
        // Prefer FFmpeg ext when enabled.
        if (FfmpegLibrary.isAvailable() && prefs.getExoFfmpegExtensionMode() != 0)
        {
            if (FfmpegLibrary.supportsFormat(codec.getAndroidMimeType()))
            {
                return true;
            }
        }
        for (String mime : collectMimeTypes("audio/"))
        {
            if (codec.hasAndroidMimeType(mime)) return true;
        }
        // Last resort: passthrough capability (AC3, EAC3, etc. on HDMI sinks).
        AudioCapabilities caps = AudioCapabilities.getCapabilities(ctx);
        for (int enc : codec.getAndroidAudioEncodings())
        {
            if (caps.supportsEncoding(enc)) return true;
        }
        return false;
    }

    private static boolean isExoPlayer(AndroidPrefStore prefs)
    {
        return prefs.getString(PrefStore.Keys.default_player, "exoplayer").equalsIgnoreCase("exoplayer");
    }

    private static List<String> collectMimeTypes(String prefix)
    {
        List<String> out = new ArrayList<>();
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo info : codecList.getCodecInfos())
        {
            if (info.isEncoder()) continue;
            out.addAll(getTypes(info, prefix));
        }
        return out;
    }

    private static Set<String> getTypes(MediaCodecInfo info, String prefix)
    {
        if (info == null || info.getSupportedTypes() == null || info.getSupportedTypes().length == 0)
            return Collections.emptySet();
        Set<String> list = new TreeSet<>();
        for (String s : info.getSupportedTypes())
        {
            if (s.startsWith(prefix)) list.add(s.trim());
        }
        return list;
    }

    private static boolean isSupportedExoPlayerContainer(Container container)
    {
        switch (container)
        {
            case MATROSKA:
            case MP4:
            case MP3:
            case OGG:
            case WAV:
            case MPEG1PS:
            case MPEG2PS:
            case MPEG2TS:
            case FLASHVIDEO:
            case AAC:
                return true;
            default:
                return false;
        }
    }
}
