package sagex.miniclient.android.media;

import android.content.Context;
import android.os.Build;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

import com.google.android.exoplayer2.audio.AudioCapabilities;
import com.google.android.exoplayer2.ext.ffmpeg.FfmpegLibrary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
        return isExoPlayer(prefs)
                ? isContainerSupportedByExo(container)
                : isContainerSupportedByIjk(container);
    }

    /** Phase 3: ExoPlayer's hard-coded native demuxer set. */
    public static boolean isContainerSupportedByExo(Container container)
    {
        return isSupportedExoPlayerContainer(container);
    }

    /**
     * Phase 3: IJKPlayer is libavformat-based and demuxes essentially every
     * container the project ships SageTV names for. Blanket {@code true} is
     * the historical assumption and matches field behaviour. If a specific
     * container ever proves IJK-broken in practice, narrow this method.
     */
    public static boolean isContainerSupportedByIjk(Container container)
    {
        return true;
    }

    /** True when the configured player can decode the video codec on this device. */
    public static boolean isVideoCodecSupported(Context ctx, AndroidPrefStore prefs, VideoCodec codec)
    {
        return isExoPlayer(prefs)
                ? isVideoCodecSupportedByExo(ctx, codec)
                : isVideoCodecSupportedByIjk(codec);
    }

    /** Phase 3: ExoPlayer-specific video decode probe (MediaCodec only). */
    public static boolean isVideoCodecSupportedByExo(Context ctx, VideoCodec codec)
    {
        for (String mime : collectMimeTypes("video/"))
        {
            if (codec.hasAndroidMimeType(mime)) return true;
        }
        return false;
    }

    /**
     * Heuristic scan-type capability used for schema v2 reporting.
     *
     * Android exposes codec presence, not deinterlacing guarantees.
     * We therefore keep this conservative: only Shield-class devices are
     * marked interlaced-safe for the legacy MPEG-2/MPEG-1/MPEG-4 Part 2
     * family. Other devices are reported as progressive-only so the
     * server can avoid direct play on 1080i sources.
     */
    public static boolean isInterlacedVideoSafeByExo(String deviceClass, VideoCodec codec)
    {
        if (deviceClass != null && "SHIELD".equalsIgnoreCase(deviceClass)) return true;

        switch (codec)
        {
            case MPEG1:
            case MPEG2:
            case MPEG4:
            case HEVC:
                return false;
            default:
                return true;
        }
    }

    /**
     * Returns semicolon-prefixed schema-v2 extras for a video codec token.
     * Includes profile/level pairs, max dimensions/fps/bitrate and selected
     * feature flags discovered from decoder capabilities.
     */
    public static String getVideoConstraintExtrasByExo(Context ctx, VideoCodec codec)
    {
        if (codec == null) return "";
        final String mime = codec.getAndroidMimeType();
        if (mime == null || mime.isEmpty()) return "";

        int maxW = -1;
        int maxH = -1;
        double maxFps = -1;
        int maxBitrate = -1;
        boolean adaptive = false;
        boolean secure = false;
        boolean tunneled = false;
        int hwCount = 0;
        int swCount = 0;
        final LinkedHashSet<String> profileLevels = new LinkedHashSet<>();

        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo info : codecList.getCodecInfos())
        {
            if (info == null || info.isEncoder()) continue;

            boolean supportsMime = false;
            for (String type : info.getSupportedTypes())
            {
                if (type != null && type.equalsIgnoreCase(mime))
                {
                    supportsMime = true;
                    break;
                }
            }
            if (!supportsMime) continue;

            if (Build.VERSION.SDK_INT >= 29)
            {
                try
                {
                    if (info.isHardwareAccelerated()) hwCount++;
                    if (info.isSoftwareOnly()) swCount++;
                }
                catch (Throwable ignored) { }
            }

            try
            {
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
                if (caps == null) continue;

                if (caps.profileLevels != null)
                {
                    for (MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels)
                    {
                        if (pl == null) continue;
                        profileLevels.add(pl.profile + ":" + pl.level);
                    }
                }

                try { adaptive |= caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback); } catch (Throwable ignored) { }
                try { secure |= caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback); } catch (Throwable ignored) { }
                try { tunneled |= caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback); } catch (Throwable ignored) { }

                if (Build.VERSION.SDK_INT >= 21)
                {
                    try
                    {
                        MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();
                        if (vc != null)
                        {
                            maxW = Math.max(maxW, vc.getSupportedWidths().getUpper());
                            maxH = Math.max(maxH, vc.getSupportedHeights().getUpper());
                            maxBitrate = Math.max(maxBitrate, vc.getBitrateRange().getUpper());
                            maxFps = Math.max(maxFps, vc.getSupportedFrameRates().getUpper());
                        }
                    }
                    catch (Throwable ignored) { }
                }
            }
            catch (Throwable ignored) { }
        }

        StringBuilder extras = new StringBuilder();
        if (maxW > 0) extras.append(";maxW=").append(maxW);
        if (maxH > 0) extras.append(";maxH=").append(maxH);
        if (maxFps > 0)
        {
            extras.append(";maxFps=").append(String.format(Locale.US, "%.2f", maxFps));
        }
        if (maxBitrate > 0) extras.append(";maxBitrate=").append(maxBitrate);
        if (!profileLevels.isEmpty()) extras.append(";profiles=").append(String.join("|", profileLevels));
        extras.append(";adaptive=").append(adaptive ? "true" : "false");
        extras.append(";secure=").append(secure ? "true" : "false");
        extras.append(";tunneled=").append(tunneled ? "true" : "false");
        if (hwCount > 0) extras.append(";hwDecoders=").append(hwCount);
        if (swCount > 0) extras.append(";swDecoders=").append(swCount);

        return extras.toString();
    }

    /** Phase 3: IJKPlayer libavcodec covers the project's video codec list. */
    public static boolean isVideoCodecSupportedByIjk(VideoCodec codec)
    {
        return true;
    }

    /**
     * True when the audio sink reports passthrough (bitstream) capability
     * for any of the codec's Android encoding constants. Independent of
     * the per-codec MediaCodec / FFmpeg software decode path used by
     * {@link #isAudioCodecSupported}.
     */
    public static boolean isAudioPassthroughSupported(Context ctx, AudioCodec codec)
    {
        int[] encodings = codec.getAndroidAudioEncodings();
        if (encodings == null || encodings.length == 0) return false;
        // C3 guard: AC-4 advertisement only allowed when the device actually
        // has an audio/ac4 MediaCodec decoder. Several Shields' audio sinks
        // (esp. via certain HDMI receivers) falsely advertise AC-4 passthrough
        // capability even though no decoder is registered; the audio renderer
        // then fails to init and the video pipeline goes black. Strip AC-4
        // unless a real decoder is present.
        if (codec == AudioCodec.AC4 && !hasMediaCodecDecoderForMime("audio/ac4"))
        {
            return false;
        }
        AudioCapabilities caps = AudioCapabilities.getCapabilities(ctx);
        for (int enc : encodings)
        {
            if (caps.supportsEncoding(enc)) return true;
        }
        return false;
    }

    /** True when the configured player can decode the audio codec on this device. */
    public static boolean isAudioCodecSupported(Context ctx, AndroidPrefStore prefs, AudioCodec codec)
    {
        return isExoPlayer(prefs)
                ? isAudioCodecSupportedByExo(ctx, prefs, codec)
                : isAudioCodecSupportedByIjk(codec);
    }

    /**
     * Phase 3: ExoPlayer-specific audio decode probe (MediaCodec + optional
     * FFmpeg ext + AudioCapabilities passthrough fallback).
     */
    public static boolean isAudioCodecSupportedByExo(Context ctx, AndroidPrefStore prefs, AudioCodec codec)
    {
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
        // C3 guard: AC-4 must not fall through to the passthrough probe -
        // an HDMI sink that advertises AC-4 passthrough is not sufficient
        // when no MediaCodec / FFmpeg decoder exists, because ExoPlayer's
        // audio renderer init fails and the video pipeline goes black.
        if (codec == AudioCodec.AC4) return false;
        // Last resort: passthrough capability (AC3, EAC3, etc. on HDMI sinks).
        AudioCapabilities caps = AudioCapabilities.getCapabilities(ctx);
        for (int enc : codec.getAndroidAudioEncodings())
        {
            if (caps.supportsEncoding(enc)) return true;
        }
        return false;
    }

    /** Phase 3: IJKPlayer libavcodec covers the project's audio codec list. */
    public static boolean isAudioCodecSupportedByIjk(AudioCodec codec)
    {
        return true;
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

    /**
     * True when at least one non-encoder MediaCodec on the device supports
     * decoding the given mime type. Used by the AC-4 advertisement guard
     * (C3) to refuse advertisement when no real decoder is present.
     */
    private static boolean hasMediaCodecDecoderForMime(String mime)
    {
        if (mime == null || mime.isEmpty()) return false;
        try
        {
            MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : codecList.getCodecInfos())
            {
                if (info.isEncoder()) continue;
                for (String s : info.getSupportedTypes())
                {
                    if (mime.equalsIgnoreCase(s)) return true;
                }
            }
        }
        catch (Throwable t)
        {
            // MediaCodecList init can throw on broken devices - treat as
            // "no decoder" to keep us out of the failure-prone path.
        }
        return false;
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
