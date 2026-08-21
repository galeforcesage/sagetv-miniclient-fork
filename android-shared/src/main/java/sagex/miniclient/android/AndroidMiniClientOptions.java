package sagex.miniclient.android;

import static sagex.miniclient.media.Container.*;

import android.app.Application;
import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.preference.PreferenceManager;

import com.google.android.exoplayer2.audio.AudioCapabilities;
import com.google.android.exoplayer2.ext.ffmpeg.FfmpegLibrary;
import com.google.android.exoplayer2.util.MimeTypes;
import com.squareup.otto.Bus;
import com.squareup.otto.ThreadEnforcer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import sagex.miniclient.IBus;
import sagex.miniclient.MiniClientConnection;
import sagex.miniclient.MiniClientOptions;
import sagex.miniclient.android.media.CodecCapabilityDetector;
import sagex.miniclient.android.prefs.AndroidPrefStore;
//import sagex.miniclient.prefs.ConnectionPrefStore;
import sagex.miniclient.media.AudioCodec;
import sagex.miniclient.media.Container;
import sagex.miniclient.util.AspectModeManager;
import sagex.miniclient.prefs.PrefStore;
import sagex.miniclient.prefs.TriState;
import sagex.miniclient.media.VideoCodec;

/**
 * Created by seans on 08/11/15.
 */
public class AndroidMiniClientOptions implements MiniClientOptions {
    private static final Logger log = LoggerFactory.getLogger(AndroidMiniClientOptions.class);

    private final AndroidPrefStore prefs;
    private final File configDir;
    private final File cacheDir;
    private final IBus bus;
    private boolean isTV=false;
    private boolean isTOUCH=false;
    private boolean advancedAspects=false;
    private Context context;

    AndroidMiniClientOptions(Application ctx)
    {
        this.prefs=new AndroidPrefStore(PreferenceManager.getDefaultSharedPreferences(ctx));
        this.configDir = ctx.getFilesDir();
        this.cacheDir = ctx.getCacheDir();
        this.bus = new OttoBusImpl(new Bus(ThreadEnforcer.ANY));
        this.isTV = ctx.getResources().getBoolean(R.bool.istv);
        this.isTOUCH = !isTV;
        this.advancedAspects=true;
        this.context = ctx;
    }

    @Override
    public AndroidPrefStore getPrefs() {
        return prefs;
    }

    @Override
    public File getConfigDir() {
        return configDir;
    }

    @Override
    public File getCacheDir() {
        return cacheDir;
    }

    @Override
    public IBus getBus() {
        return bus;
    }

    @Override
    public void prepareCodecs(List<String> videoCodecs, List<String> audioCodecs, List<String> pushFormats, List<String> pullFormats)
    {

        Set<String> acodecs = new TreeSet<>();
        Set<String> vcodecs = new TreeSet<>();

        pushFormats.clear();
        List<Container> supPushContainers = this.getSupportedPushContainers();

        for(int i = 0; i < supPushContainers.size(); i++)
        {
            for(int j = 0; j < supPushContainers.get(i).getSageTVNames().length; j++)
            {
                pushFormats.add(supPushContainers.get(i).getSageTVNames()[j]);
            }
        }

        pullFormats.clear();
        List<Container> supPullContainers = this.getSupportedPullContainers();

        for(int i = 0; i < supPullContainers.size(); i++)
        {
            for(int j = 0; j < supPullContainers.get(i).getSageTVNames().length; j++)
            {
                pullFormats.add(supPullContainers.get(i).getSageTVNames()[j]);
            }
        }

        videoCodecs.clear();
        List<VideoCodec> supVideoCodecs = this.getSupportedVideoCodecs();

        for(int i = 0; i < supVideoCodecs.size(); i++)
        {
            for(int j = 0; j < supVideoCodecs.get(i).sageTVNames().length; j++)
            {
                videoCodecs.add(supVideoCodecs.get(i).sageTVNames()[j]);
            }
        }

        audioCodecs.clear();
        List<AudioCodec> supAudioCodecs = this.getSupportedAudioCodecs();

        for(int i = 0; i < supAudioCodecs.size(); i++)
        {
            for(int j = 0; j < supAudioCodecs.get(i).getSageTVNames().length; j++)
            {
                audioCodecs.add(supAudioCodecs.get(i).getSageTVNames()[j]);
            }
        }

    }

    private List<Container> getSupportedPushContainers()
    {
        List<Container> supportedContainers = new ArrayList<Container>();
        Container [] allContainers = new Container[]{MPEG1PS, MPEG2PS, MPEG2TS, MATROSKA};

        for (Container c : allContainers)
        {
            TriState state = TriState.fromPrefValue(prefs.getContainerSupport(c.getName()));
            boolean detected = CodecCapabilityDetector.isContainerSupported(context, prefs, c);
            if (state.resolve(detected))
            {
                log.debug("Push Container added [{}]: {}", state, c.getName());
                supportedContainers.add(c);
            }
            else
            {
                log.debug("Push Container excluded [{}]: {}", state, c.getName());
            }
        }
        return supportedContainers;
    }

    private List<Container> getSupportedPullContainers()
    {
        List<Container> supportedContainers = new ArrayList<Container>();
        for (Container c : Container.values())
        {
            // Push-only containers are never offered for pull.
            if (c == MPEG1PS || c == MPEG2TS || c == MPEG2PS) continue;

            TriState state = TriState.fromPrefValue(prefs.getContainerSupport(c.getName()));
            boolean detected = CodecCapabilityDetector.isContainerSupported(context, prefs, c);
            if (state.resolve(detected))
            {
                log.debug("Pull Container added [{}]: {}", state, c.getName());
                supportedContainers.add(c);
            }
            else
            {
                log.debug("Pull Container excluded [{}]: {}", state, c.getName());
            }
        }
        return supportedContainers;
    }

    private List<AudioCodec> getSupportedAudioCodecs()
    {
        List<AudioCodec> supportedCodecs = new ArrayList<AudioCodec>();
        for (AudioCodec c : AudioCodec.values())
        {
            TriState state = TriState.fromPrefValue(prefs.getAudioCodecSupport(c.getName()));
            boolean detected = CodecCapabilityDetector.isAudioCodecSupported(context, prefs, c);
            if (state.resolve(detected))
            {
                log.debug("Audio codec added [{}]: {}", state, c.getName());
                supportedCodecs.add(c);
            }
            else
            {
                log.debug("Audio codec excluded [{}]: {}", state, c.getName());
            }
        }
        return supportedCodecs;
    }

    @Override
    public void prepareAudioPassthrough(List<String> passthroughCodecs)
    {
        passthroughCodecs.clear();
        for (AudioCodec c : AudioCodec.values())
        {
            // Skip codecs with no Android encoding constant \u2014 passthrough is meaningless.
            if (c.getAndroidAudioEncodings() == null || c.getAndroidAudioEncodings().length == 0) continue;

            TriState state = TriState.fromPrefValue(prefs.getAudioPassthroughSupport(c.getName()));
            boolean detected = CodecCapabilityDetector.isAudioPassthroughSupported(context, c);
            if (state.resolve(detected))
            {
                log.debug("Audio passthrough added [{}]: {}", state, c.getName());
                for (String sageName : c.getSageTVNames())
                {
                    passthroughCodecs.add(sageName);
                }
            }
            else
            {
                log.debug("Audio passthrough excluded [{}]: {}", state, c.getName());
            }
        }
    }

    /**
     * Phase 3: produce honest per-player capability lists. The same tri-state
     * settings drive both EXO_* and IJK_* lists, but the auto-detected
     * baseline is computed independently per player so AUTO is honest. ON
     * still forces both, OFF still excludes both.
     *
     * <p>Emitted under SageTV property names {@code EXO_VIDEO_CODECS},
     * {@code IJK_VIDEO_CODECS}, etc. Legacy 9.2.x servers never query these;
     * NG servers can use them to pick a codec/container combination that
     * matches whichever player will actually decode the stream (tracked by
     * the same {@code default_player} setting plus the
     * {@code PlayerSelectionUtil} landmine swaps at OPENURL time).</p>
     */
    @Override
    public void preparePerPlayerCapabilities(java.util.Map<String, List<String>> caps)
    {
        caps.clear();

        // Push containers (push-only set used today by getSupportedPushContainers).
        java.util.List<String> exoPush = new ArrayList<>();
        java.util.List<String> ijkPush = new ArrayList<>();
        Container[] pushAll = new Container[]{MPEG1PS, MPEG2PS, MPEG2TS, MATROSKA};
        for (Container c : pushAll)
        {
            TriState state = TriState.fromPrefValue(prefs.getContainerSupport(c.getName()));
            if (state.resolve(CodecCapabilityDetector.isContainerSupportedByExo(c)))
                addAllSageNames(exoPush, c.getSageTVNames());
            if (state.resolve(CodecCapabilityDetector.isContainerSupportedByIjk(c)))
                addAllSageNames(ijkPush, c.getSageTVNames());
        }
        caps.put("EXO_PUSH_AV_CONTAINERS", exoPush);
        caps.put("IJK_PUSH_AV_CONTAINERS", ijkPush);

        // Pull containers (everything except the push-only set).
        java.util.List<String> exoPull = new ArrayList<>();
        java.util.List<String> ijkPull = new ArrayList<>();
        for (Container c : Container.values())
        {
            if (c == MPEG1PS || c == MPEG2TS || c == MPEG2PS) continue;
            TriState state = TriState.fromPrefValue(prefs.getContainerSupport(c.getName()));
            if (state.resolve(CodecCapabilityDetector.isContainerSupportedByExo(c)))
                addAllSageNames(exoPull, c.getSageTVNames());
            if (state.resolve(CodecCapabilityDetector.isContainerSupportedByIjk(c)))
                addAllSageNames(ijkPull, c.getSageTVNames());
        }
        caps.put("EXO_PULL_AV_CONTAINERS", exoPull);
        caps.put("IJK_PULL_AV_CONTAINERS", ijkPull);

        // Video codecs.
        java.util.List<String> exoVideo = new ArrayList<>();
        java.util.List<String> ijkVideo = new ArrayList<>();
        for (VideoCodec c : VideoCodec.values())
        {
            TriState state = TriState.fromPrefValue(prefs.getVideoCodecSupport(c.getName()));
            if (state.resolve(CodecCapabilityDetector.isVideoCodecSupportedByExo(context, c)))
                addAllSageNames(exoVideo, c.sageTVNames());
            if (state.resolve(CodecCapabilityDetector.isVideoCodecSupportedByIjk(c)))
                addAllSageNames(ijkVideo, c.sageTVNames());
        }
        caps.put("EXO_VIDEO_CODECS", exoVideo);
        caps.put("IJK_VIDEO_CODECS", ijkVideo);

        // Audio codecs.
        java.util.List<String> exoAudio = new ArrayList<>();
        java.util.List<String> ijkAudio = new ArrayList<>();
        for (AudioCodec c : AudioCodec.values())
        {
            TriState state = TriState.fromPrefValue(prefs.getAudioCodecSupport(c.getName()));
            if (state.resolve(CodecCapabilityDetector.isAudioCodecSupportedByExo(context, prefs, c)))
                addAllSageNames(exoAudio, c.getSageTVNames());
            if (state.resolve(CodecCapabilityDetector.isAudioCodecSupportedByIjk(c)))
                addAllSageNames(ijkAudio, c.getSageTVNames());
        }
        caps.put("EXO_AUDIO_CODECS", exoAudio);
        caps.put("IJK_AUDIO_CODECS", ijkAudio);

        log.debug("Per-player capabilities prepared: EXO_VIDEO={} IJK_VIDEO={} EXO_AUDIO={} IJK_AUDIO={}",
                  exoVideo.size(), ijkVideo.size(), exoAudio.size(), ijkAudio.size());
    }

    private static void addAllSageNames(List<String> dst, String[] names)
    {
        if (names == null) return;
        for (String n : names) dst.add(n);
    }

    private List<VideoCodec> getSupportedVideoCodecs()
    {
        List<VideoCodec> supportedCodecs = new ArrayList<VideoCodec>();
        for (VideoCodec c : VideoCodec.values())
        {
            TriState state = TriState.fromPrefValue(prefs.getVideoCodecSupport(c.getName()));
            boolean detected = CodecCapabilityDetector.isVideoCodecSupported(context, prefs, c);
            if (state.resolve(detected))
            {
                log.debug("Video codec added [{}]: {}", state, c.getName());
                supportedCodecs.add(c);
            }
            else
            {
                log.debug("Video codec excluded [{}]: {}", state, c.getName());
            }
        }
        return supportedCodecs;
    }


    @Override
    public boolean isTouchUI()
    {
        return isTOUCH;
    }

    @Override
    public boolean isTVUI()
    {
        return isTV;
    }

    @Override
    public boolean isDesktopUI()
    {
        return false;
    }

    /**
     * Coarse device classification for legacy-server capability tuning.
     * Used by {@code MiniClientConnection} to decide e.g. whether to drop
     * MPEG2-PS from the legacy push advertisement on cheap AndroidTV
     * sticks whose MediaCodec stacks handle MPEG2-TS more reliably.
     *
     * <p>Detection uses {@link android.os.Build#MANUFACTURER} +
     * {@link android.os.Build#MODEL} + {@link android.os.Build#PRODUCT}
     * substring matching. Anything we can't confidently slot returns
     * {@code "UNKNOWN"}, which keeps the existing behavior.</p>
     */
    @Override
    public String getDeviceClass()
    {
        String mfr = String.valueOf(android.os.Build.MANUFACTURER).toLowerCase(java.util.Locale.ROOT);
        String model = String.valueOf(android.os.Build.MODEL).toLowerCase(java.util.Locale.ROOT);
        String prod = String.valueOf(android.os.Build.PRODUCT).toLowerCase(java.util.Locale.ROOT);

        // NVIDIA Shield TV (mdarcy, foster, sif, etc.)
        if (mfr.contains("nvidia") || model.contains("shield") || prod.contains("foster") || prod.contains("mdarcy") || prod.contains("sif"))
            return "SHIELD";

        if (isTV)
        {
            // Cheap AndroidTV sticks: Chromecast w/ Google TV (sabrina/boreal),
            // Onn 4K boxes (dopinder etc.), Walmart-tier devices.
            if (model.contains("chromecast") || prod.contains("sabrina") || prod.contains("boreal")
                    || model.contains("onn") || prod.contains("dopinder")
                    || mfr.contains("amlogic") || mfr.contains("rockchip") || mfr.contains("allwinner")
                    || mfr.contains("walmart"))
                return "BUDGET_ATV";
            // Other TV devices we haven't classified -> treat as budget by default
            // (safer to TS-bias than to PS-bias on unknown TV hardware).
            return "BUDGET_ATV";
        }

        // Foldables: Galaxy Z Fold (q5q/q6q/...), Galaxy Z Flip
        if (model.contains("fold") || model.contains("flip") || prod.contains("q5q") || prod.contains("q6q") || prod.contains("b6q"))
            return "FOLDABLE";

        // Tablets: Galaxy Tab S/A series, Pixel Tablet
        if (model.contains("tab") || model.contains("tablet") || prod.contains("gts") || prod.contains("gta"))
            return "TABLET";

        // Premium phones: Galaxy S Ultra, Pixel Pro
        if (model.contains("ultra") || (model.contains("pixel") && model.contains("pro")) || prod.contains("dm3q") || prod.contains("e3q"))
            return "PREMIUM_PHONE";

        // Pixel non-Pro, Galaxy S non-Ultra, mid-range
        if (model.contains("pixel") || (mfr.contains("samsung") && model.startsWith("sm-s")))
            return "MID_PHONE";

        // Budget phones: Galaxy A1x, Moto G
        if (model.startsWith("moto g") || (mfr.contains("samsung") && (model.startsWith("sm-a1") || model.startsWith("sm-a2"))))
            return "BUDGET_PHONE";

        return "UNKNOWN";
    }

    @Override
    public boolean isInterlacedVideoSafe(String sageCodecToken, boolean exoPath)
    {
        if (!exoPath) return true;
        if (sageCodecToken == null) return true;

        VideoCodec resolved = null;
        for (VideoCodec codec : VideoCodec.values())
        {
            for (String sageName : codec.sageTVNames())
            {
                if (sageName != null && sageName.equalsIgnoreCase(sageCodecToken))
                {
                    resolved = codec;
                    break;
                }
            }
            if (resolved != null) break;
        }

        if (resolved == null) return true;
        return CodecCapabilityDetector.isInterlacedVideoSafeByExo(getDeviceClass(), resolved);
    }

    @Override
    public String getVideoConstraintExtras(String sageCodecToken, boolean exoPath)
    {
        if (sageCodecToken == null) return "";

        VideoCodec resolved = resolveVideoCodec(sageCodecToken);
        if (resolved == null) return "";

        // NG 4K contract Phase 0: the IJK path now advertises real MediaCodec
        // geometry too, but only when a hardware decoder would actually be
        // selected (fail-closed otherwise so the server never routes an enhanced
        // codec to software IJK).
        return exoPath
                ? CodecCapabilityDetector.getVideoConstraintExtrasByExo(context, resolved)
                : CodecCapabilityDetector.getVideoConstraintExtrasByIjk(context, resolved);
    }

    @Override
    public String getVideoDecoderKind(String sageCodecToken, boolean exoPath)
    {
        if (exoPath) return "hw";
        VideoCodec resolved = resolveVideoCodec(sageCodecToken);
        return CodecCapabilityDetector.getVideoDecoderKind(resolved, false);
    }

    private static VideoCodec resolveVideoCodec(String sageCodecToken)
    {
        if (sageCodecToken == null) return null;
        for (VideoCodec codec : VideoCodec.values())
        {
            for (String sageName : codec.sageTVNames())
            {
                if (sageName != null && sageName.equalsIgnoreCase(sageCodecToken))
                {
                    return codec;
                }
            }
        }
        return null;
    }

    @Override
    public boolean isUsingAdvancedAspectModes()
    {
        return advancedAspects;
    }

    @Override
    public String getAdvancedApectModes()
    {
        return AspectModeManager.ASPECT_MODES;
    }

    @Override
    public String getDefaultAdvancedAspectMode()
    {
        return AspectModeManager.DEFAULT_ASPECT_MODE;
    }

    private Set<String> getAudioCodecs(MediaCodecInfo info)
    {
        if (info == null || info.getSupportedTypes() == null || info.getSupportedTypes().length == 0)
            return Collections.emptySet();

        Set<String> list = new TreeSet<>();

        for (String s : info.getSupportedTypes())
        {
            if (s.startsWith("audio/"))
            {
                list.add(s.trim());
            }
        }
        return list;
    }

    private Set<String> getVideoCodecs(MediaCodecInfo info)
    {
        if (info == null || info.getSupportedTypes() == null || info.getSupportedTypes().length == 0)
            return Collections.emptySet();

        Set<String> list = new TreeSet<>();
        for (String s : info.getSupportedTypes())
        {
            if (s.startsWith("video/"))
            {
                list.add(s.trim());
            }
        }
        return list;
    }

    private boolean isSupportedExoPlayerContainer(Container container)
    {
        switch(container)
        {
            case MATROSKA:
                return true;
            case MP4:
                return true;
            case MP3:
                return true;
            case OGG:
                return true;
            case WAV:
                return true;
            case MPEG1PS:
                return true;
            case MPEG2PS:
                return true;
            case MPEG2TS:
                return true;
            case FLASHVIDEO:
                return true;
            case AAC:
                return true;
            default:
                return false;

        }


    }

    private boolean isAudioDecoder(MediaCodecInfo info)
    {
        if (info == null || info.getSupportedTypes() == null || info.getSupportedTypes().length == 0)
            return false;

        for (String s : info.getSupportedTypes())
        {
            if (s.startsWith("audio/"))
            {
                return true;
            }
        }
        return false;
    }

    private boolean isVideoDecoder(MediaCodecInfo info)
    {
        if (info == null || info.getSupportedTypes() == null || info.getSupportedTypes().length == 0)
            return false;

        for (String s : info.getSupportedTypes())
        {
            if (s.startsWith("video/"))
            {
                return true;
            }
        }
        return false;
    }



}
