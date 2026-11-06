package sagex.miniclient.android.video.ijkplayer;

import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.support.v4.media.session.MediaSessionCompat;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import sagex.miniclient.android.MiniclientApplication;
import sagex.miniclient.android.ui.AndroidUIController;
import sagex.miniclient.android.util.AudioUtil;
import sagex.miniclient.android.video.BaseMediaPlayerImpl;
import sagex.miniclient.android.video.MediaSessionCallbackHandler;
import sagex.miniclient.android.video.PlaybackTelemetry;
import sagex.miniclient.android.middleware.TrickplayController;
import sagex.miniclient.android.middleware.TransportIjkMediaSource;
import sagex.miniclient.media.SubtitleTrack;
import sagex.miniclient.party.PartyPlaybackAdapter;
import sagex.miniclient.media.SubtitleCodec;
import sagex.miniclient.prefs.PrefStore;
import sagex.miniclient.uibridge.Dimension;
import sagex.miniclient.util.VerboseLogging;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaMeta;
import tv.danmaku.ijk.media.player.MediaInfo;
import tv.danmaku.ijk.media.player.misc.IMediaDataSource;
import tv.danmaku.ijk.media.player.misc.ITrackInfo;
import tv.danmaku.ijk.media.player.misc.IjkTrackInfo;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import static sagex.miniclient.util.Utils.toHHMMSS;

/**
 * Created by seans on 06/10/15.
 */
public class IJKMediaPlayerImpl extends BaseMediaPlayerImpl<IMediaPlayer, IMediaDataSource>
{
    long preSeekPos = -1;
    long resumeTimeOffset = -1;
    boolean resumeMode = false;
    int initialAudioStreamPos = -1;
    int initialTextStreamPos = -1;
    /** Last audio session id the client EQ was attached to (0 = not yet attached). */
    int eqSessionId = 0;
    long logTime = -1;
    /**
     * Set true once the native player reports first video/audio render
     * (MEDIA_INFO_*_RENDERING_START). Distinguishes a genuine end-of-stream
     * from an immediate-EOF failed open (malformed/empty push stream that
     * completes having never rendered a frame).
     */
    boolean firstFrameRendered = false;
    private volatile boolean audioRenderingStarted;
    private volatile boolean videoRenderingStarted;
    private volatile long partyBufferingProbePositionMs = -1;
    private volatile long partyBufferingProbeGeneration = -1;
    private volatile long partyBufferingProbeStartedAtMs = -1;
    private static final long PARTY_BUFFERING_CONFIRM_MS = 1_000;
    private final android.os.Handler partyHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable partyBufferingStart = new Runnable()
    {
        @Override public void run()
        {
            long elapsedMs = android.os.SystemClock.elapsedRealtime()
                    - partyBufferingProbeStartedAtMs;
            if (partyBufferingProbeStartedAtMs >= 0
                    && elapsedMs < PARTY_BUFFERING_CONFIRM_MS)
            {
                partyHandler.postDelayed(this,
                        PARTY_BUFFERING_CONFIRM_MS - elapsedMs);
                return;
            }
            IMediaPlayer current = player;
            long currentPositionMs = -1;
            if (current != null)
            {
                try
                {
                    currentPositionMs = current.getCurrentPosition();
                }
                catch (Throwable ignored)
                {
                }
            }
            if (IjkPartyBufferingGate.renderedOutputStillAdvancing(
                    partyBufferingProbePositionMs,
                    currentPositionMs,
                    partyBufferingProbeGeneration,
                    getPartyPlaybackCommandGeneration(),
                    audioRenderingStarted || videoRenderingStarted))
            {
                log.debug("Ignoring stale IJK buffering start; rendered output advanced {} ms",
                        currentPositionMs - partyBufferingProbePositionMs);
                setPartyBuffering(false);
                return;
            }
            if (IjkPartyBufferingGate.renderedPositionDiscontinuous(
                    partyBufferingProbePositionMs,
                    currentPositionMs,
                    partyBufferingProbeGeneration,
                    getPartyPlaybackCommandGeneration(),
                    audioRenderingStarted || videoRenderingStarted))
            {
                log.debug("Restarting IJK buffering confirmation after rendered "
                        + "position reset {} -> {}",
                        partyBufferingProbePositionMs, currentPositionMs);
                partyBufferingProbePositionMs = currentPositionMs;
                partyBufferingProbeStartedAtMs =
                        android.os.SystemClock.elapsedRealtime();
                partyHandler.postDelayed(this, PARTY_BUFFERING_CONFIRM_MS);
                return;
            }
            setPartyBuffering(true);
        }
    };

    @Override
    public void cancelPartyScheduledAction()
    {
        super.cancelPartyScheduledAction();
        partyHandler.removeCallbacks(partyBufferingStart);
        partyBufferingProbePositionMs = -1;
        partyBufferingProbeGeneration = -1;
        partyBufferingProbeStartedAtMs = -1;
    }
    private final IjkPartyBufferTracker partyBufferTracker = new IjkPartyBufferTracker();
    private String playerUrl;
    private volatile boolean pauseAfterGenerationRebuild;
    private volatile boolean rebuildingMatroskaGeneration;
    MediaSessionCompat mediaSession;
    /**
     * Guards releasePlayer() against overlapping/duplicate invocations (e.g. a
     * GFX-channel disconnect teardown racing a near-simultaneous activity
     * finish()/exit teardown). Without this, a second call could still observe
     * the old (non-null) {@code player}/{@code mediaSession} references --
     * {@code player} was previously nulled out only at the very end of
     * releasePlayer(), after the real teardown work ran -- and redundantly
     * re-run native release/EGL teardown concurrently with the first call's
     * still-settling teardown. That produced an observed
     * "libEGL: call to OpenGL ES API with no current context" violation and
     * left the renderer's native state inconsistent for the next playback
     * attempt (decoder configures cleanly but never renders a frame).
     */
    private final Object releaseLock = new Object();
    private int rebufferCount;
    private long stalledDurationMs;
    private long stallStartedAtMs = -1;
    private long latestSeekTargetMs = -1;
    private long latestSeekPositionMs = -1;
    private volatile boolean networkDisconnected;

    public IJKMediaPlayerImpl(AndroidUIController activity)
    {
        super(activity, true, true);
    }

    private void pauseAfterGenerationRebuildIfActivated()
    {
        if (!pauseAfterGenerationRebuild || !(player instanceof IjkMediaPlayer)) return;
        IjkMediaPlayer ijk = (IjkMediaPlayer) player;
        boolean audioActive = isActiveTrack(ijk, IjkTrackInfo.MEDIA_TRACK_TYPE_AUDIO);
        boolean videoActive = isActiveTrack(ijk, IjkTrackInfo.MEDIA_TRACK_TYPE_VIDEO);
        if (!IjkPartyTrackActivation.hasRendered(
                audioActive, videoActive,
                audioRenderingStarted, videoRenderingStarted))
        {
            return;
        }
        pauseAfterGenerationRebuild = false;
        player.pause();
        state = PAUSE_STATE;
        updateMediaSessionPlaybackState(player.getCurrentPosition());
    }

    /**
     * Compute the player's reported media time.
     *
     * <p>This is invoked by {@link BaseMediaPlayerImpl#getMediaTimeMillis} only when the
     * native trickplay layer is unavailable, OR returns 0 — i.e., as a fallback
     * to the native two-clock truth. When native is active, the reported time
     * comes from {@code TrickplayController.getReportedTime()} and the
     * resume-offset bookkeeping below is bypassed.
     *
     * <p>The {@code resumeMode} / {@code resumeTimeOffset} hack compensates for IJK's
     * quirk of resetting its internal clock to 0 after a seek to a non-zero
     * position in PUSH mode, or after a server-conditioned pull-xcode reopen
     * (/msproxy/XCODE_SETUP) whose new local timeline restarts near zero --
     * both cases are driven by {@code serverStartTime} carrying the correct
     * baseline (see MediaCmd#classifyPullTimeline for the pull-xcode case).
     * Direct pull leaves {@code serverStartTime} at 0, an exact no-op. Native
     * trickplay handles this via {@code baseOffsetMs = seekTargetMs - positionMs}
     * during RECOVERING.
     */
    @Override
    public long getPlayerMediaTimeMillis(long serverStartTime)
    {
        long time = player.getCurrentPosition();

        // Feed position to native trickplay controller for state machine transitions
        if (trickplayController != null && trickplayController.isNativeAvailable() && time > 0)
        {
            trickplayController.onPlayerPosition(time);
        }

        // Applies to push and to a server-conditioned pull-xcode reopen
        // alike (see class javadoc above); a direct-pull stream always has
        // serverStartTime == 0 here, so this whole block is a no-op for it
        // (falls straight to the resumeMode==false branch below).
        //
        // we haven't determined the "resume" time yet
        if (resumeTimeOffset < 0)
        {
            if (serverStartTime < 500)
            {
                // this is start from beginning
                resumeMode = false;
                resumeTimeOffset = 0;
            }
            else
            {
                // IJK's getMediaTime() is a little quirky for PS/TS streams.  Basically, when you start
                // from 0, then seeking works fine.  But, when you resume from some other time, the
                // players internal clock is reset to 0.  So, the stream plays at the right position, but
                // the timescale is reset to 0.  For that reason, when pushMode is enabled, you can follow
                // the logic to see what has to happen to ensure the correct media time.
                resumeMode = true;
                resumeTimeOffset = serverStartTime;
                log.debug("IJK: getMediaTime(): RESUME from {}", resumeTimeOffset);
            }
        }

        if (time < 0)
        {
            // player is adjusting, after a push/seek.
            if (VerboseLogging.DETAILED_PLAYER_LOGGING)
            {
                log.debug("IJK: getPlayerMediaTimeMillis(): player adjusting using 0 but time was {}", toHHMMSS(time, true));
            }

            return time;
        }

        if (resumeMode)
        {
            // when in resume mode, you go back before the start of the resume, player time
            // seems to do a PTS rollover of sorts
            long realTime = time;

            if (time + resumeTimeOffset > PTS_ROLLOVER)
            {
                // need to adjust the time
                time = time - PTS_ROLLOVER;
            }
            time = time + resumeTimeOffset;

            if (VerboseLogging.DETAILED_PLAYER_LOGGING)
            {
                if (logTime != realTime / 1000)
                {
                    logTime = realTime / 1000;
                    log.debug("IJK: getPlayerMediaTimeMillis(): resume: {}, player time: {}, total: {}", toHHMMSS(resumeTimeOffset, true), toHHMMSS(realTime, true), toHHMMSS(time, true));
                }
            }
        }
        else
        {
            if (VerboseLogging.DETAILED_PLAYER_LOGGING)
            {
                if (logTime != time / 1000)
                {
                    logTime = time / 1000;
                    log.debug("IJK: getPlayerMediaTimeMillis(): time: {}", toHHMMSS(time, true));
                }
            }
        }


        return time;
    }

    // ---- NG-POSDIAG hooks ----
    @Override
    protected long getRenderedPositionMillis()
    {
        try
        {
            // IJK getCurrentPosition() is the rendered/output clock (on-screen frame).
            return (player != null) ? player.getCurrentPosition() : -1;
        }
        catch (Throwable t)
        {
            return -1;
        }
    }

    @Override
    public long getDetailedBufferMediaTimeMillis(long lastServerTime)
    {
        try
        {
            long position = (player != null) ? player.getCurrentPosition() : -1;
            if (position >= 0)
            {
                if (trickplayController != null
                        && trickplayController.isNativeAvailable())
                {
                    trickplayController.setServerStartTime(lastServerTime);
                    trickplayController.onPlayerPosition(position);
                    return trickplayController.getReportedTime();
                }
                return lastServerTime > 0 ? lastServerTime + position : position;
            }
        }
        catch (Throwable t)
        {
            // Fall through to the existing canonical/fallback path.
        }
        return getMediaTimeMillis(lastServerTime);
    }

    @Override
    protected long getBufferedPositionMillis()
    {
        // IJK exposes cache duration rather than an absolute buffered-head
        // position. Party readiness consumes those native measurements through
        // getPartyObservation(); NG-POSDIAG keeps this unavailable because its
        // contract requires an absolute player-timeline position.
        return -1;
    }

    @Override
    public PartyPlaybackAdapter.Observation getPartyObservation()
    {
        PartyPlaybackAdapter.Observation base = super.getPartyObservation();
        IMediaPlayer current = player;
        boolean prepared = playerReady && current != null;
        if (!(current instanceof IjkMediaPlayer) || !prepared)
        {
            return new PartyPlaybackAdapter.Observation(
                    base.sampleMonoMs, base.positionMs, base.renderedPositionMs,
                    -1, base.durationMs, base.state, prepared, false,
                    base.precisionMs);
        }

        IjkMediaPlayer ijk = (IjkMediaPlayer) current;
        boolean audioActive = isActiveTrack(ijk, IjkTrackInfo.MEDIA_TRACK_TYPE_AUDIO);
        boolean videoActive = isActiveTrack(ijk, IjkTrackInfo.MEDIA_TRACK_TYPE_VIDEO);
        boolean activated = IjkPartyTrackActivation.hasRendered(
                audioActive, videoActive,
                audioRenderingStarted, videoRenderingStarted);
        long bufferAheadMs = -1;
        if (activated)
        {
            try
            {
                bufferAheadMs = partyBufferTracker.sample(
                        base.sampleMonoMs,
                        ijk.getAudioCachedDuration(),
                        ijk.getVideoCachedDuration(),
                        audioActive,
                        videoActive);
            }
            catch (Throwable t)
            {
                partyBufferTracker.reset();
            }
        }

        return new PartyPlaybackAdapter.Observation(
                base.sampleMonoMs,
                base.positionMs,
                base.renderedPositionMs,
                bufferAheadMs, base.durationMs,
                base.state, prepared, activated, base.precisionMs);
    }

    @Override
    public void resetPartyReadiness()
    {
        partyBufferTracker.reset();
        super.resetPartyReadiness();
    }

    private boolean isActiveTrack(IjkMediaPlayer ijk, int trackType)
    {
        try
        {
            int selectedTrack = ijk.getSelectedTrack(trackType);
            if (selectedTrack >= 0) return true;
            ITrackInfo[] tracks = ijk.getTrackInfo();
            if (tracks == null) return false;
            int matchingTracks = 0;
            for (ITrackInfo track : tracks)
            {
                if (track != null && track.getTrackType() == trackType) matchingTracks++;
            }
            return IjkPartyTrackActivation.isActive(
                    selectedTrack, matchingTracks);
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    private void resetPartyActivation()
    {
        audioRenderingStarted = false;
        videoRenderingStarted = false;
        resetPartyReadiness();
    }

    @Override
    protected long getDurationMillis()
    {
        try
        {
            return player != null && player.getDuration() >= 0 ? player.getDuration() : 0;
        }
        catch (Throwable t)
        {
            return 0;
        }
    }

    @Override
    public PlaybackTelemetry getPlaybackTelemetry()
    {
        IjkMediaPlayer ijk = player instanceof IjkMediaPlayer ? (IjkMediaPlayer) player : null;
        if (ijk == null) return PlaybackTelemetry.UNAVAILABLE;

        long videoBuffered = ijk.getVideoCachedDuration();
        long audioBuffered = ijk.getAudioCachedDuration();
        long bufferedDuration = videoBuffered > 0 && audioBuffered > 0
                ? Math.min(videoBuffered, audioBuffered)
                : Math.max(videoBuffered, audioBuffered);
        long totalStall = stalledDurationMs;
        if (stallStartedAtMs >= 0)
        {
            totalStall += SystemClock.elapsedRealtime() - stallStartedAtMs;
        }

        MediaInfo mediaInfo = ijk.getMediaInfo();
        String decoderName = "";
        if (mediaInfo != null)
        {
            decoderName = mediaInfo.mVideoDecoderImpl != null
                    ? mediaInfo.mVideoDecoderImpl : mediaInfo.mVideoDecoder;
        }
        boolean hardwareDecoder =
                ijk.getVideoDecoder() == IjkMediaPlayer.FFP_PROPV_DECODER_MEDIACODEC;

        return new PlaybackTelemetry.Builder()
                .decoder(decoderName, hardwareDecoder)
                .bufferedDurationMs(bufferedDuration)
                .rebuffer(rebufferCount, totalStall)
                .frameCounts(ijk.getVideoDecodedFrames(), ijk.getVideoDroppedFrames())
                .decoderLatencyMs(ijk.getVideoDecoderLatencyMs())
                .audioVideoClockDifferenceMs(ijk.getAvDiff() * 1000.0)
                .latestSeek(latestSeekTargetMs, latestSeekPositionMs)
                .networkDisconnected(networkDisconnected)
                .tracks(getPlaybackTracks(ijk, mediaInfo))
                .build();
    }

    private List<PlaybackTelemetry.Track> getPlaybackTracks(
            IjkMediaPlayer ijk, MediaInfo mediaInfo)
    {
        List<PlaybackTelemetry.Track> tracks = new ArrayList<>();
        ITrackInfo[] trackInfo = ijk.getTrackInfo();
        if (trackInfo == null) return tracks;

        for (int i = 0; i < trackInfo.length; i++)
        {
            ITrackInfo track = trackInfo[i];
            if (track == null) continue;
            int trackType = track.getTrackType();
            String type = getTrackTypeName(trackType);
            boolean selected = ijk.getSelectedTrack(trackType) == i;
            String codec = "";
            long channelLayout = 0;
            if (mediaInfo != null && mediaInfo.mMeta != null
                    && mediaInfo.mMeta.mStreams != null
                    && i < mediaInfo.mMeta.mStreams.size())
            {
                IjkMediaMeta.IjkStreamMeta stream = mediaInfo.mMeta.mStreams.get(i);
                if (stream != null)
                {
                    codec = stream.mCodecName;
                    channelLayout = stream.mChannelLayout;
                }
            }
            tracks.add(new PlaybackTelemetry.Track(
                    "ijk:" + type + ":" + i,
                    type,
                    track.getLanguage(),
                    codec,
                    channelLayout,
                    selected));
        }
        return tracks;
    }

    private static String getTrackTypeName(int trackType)
    {
        switch (trackType)
        {
            case ITrackInfo.MEDIA_TRACK_TYPE_VIDEO: return "video";
            case ITrackInfo.MEDIA_TRACK_TYPE_AUDIO: return "audio";
            case ITrackInfo.MEDIA_TRACK_TYPE_SUBTITLE:
            case ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT: return "text";
            case ITrackInfo.MEDIA_TRACK_TYPE_METADATA: return "metadata";
            default: return "unknown";
        }
    }

    @Override
    public void stop()
    {
        if(mediaSession != null)
        {
            mediaSession.setActive(false);
        }

        if (player == null) return;

        if (player.isPlaying())
        {
            player.stop();
            //this.releasePlayer();
        }
        super.stop();
    }

    @Override
    public void reattachVideoSurface(final android.view.SurfaceHolder holder)
    {
        final IMediaPlayer p = player;
        if (p == null || holder == null) return;
        videoRenderingStarted = false;
        resetPartyReadiness();
        context.runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    // Hot-swaps the decoder's output surface. IJK keeps the
                    // demux/decode/network session running, so position and all
                    // seek state survive the move (no restream, no reconnect).
                    p.setDisplay(holder);
                    log.info("IJK video surface re-targeted (surface move)");
                }
                catch (Throwable t)
                {
                    log.error("reattachVideoSurface (IJK) failed", t);
                }
            }
        });
    }

    @Override
    public void pause()
    {


        if (state == PAUSE_STATE && !pushMode)
        {
            log.debug("In pause state.  Seek frame instead...");
            //TODO: Could not find the framerate in IJKPlayer.  Going to assume 30fps for now.
            this.seek(player.getCurrentPosition() + 1000);
            return;
        }

        super.pause();

        if (player != null && player.isPlaying())
        {
            player.pause();
            updateMediaSessionPlaybackState(player.getCurrentPosition());
        }
    }

    @Override
    public void play()
    {
        super.play();

        if (!playerReady)
        {
            log.debug("Player not ready, deferring play");
            pendingPlay = true;
            return;
        }

        if (player != null && !player.isPlaying())
        {
            player.start();
            updateMediaSessionPlaybackState(player.getCurrentPosition());
        }
    }

    @Override
    protected void applyPartyHold(boolean hold)
    {
        IMediaPlayer current = player;
        if (current != null)
        {
            try
            {
                current.setVolume(hold ? 0.0f : 1.0f, hold ? 0.0f : 1.0f);
            }
            catch (Throwable ignored)
            {
            }
        }
        super.applyPartyHold(hold);
    }

    @Override
    public void flush()
    {
        resetPartyActivation();
        if (pushMode && isMatroskaContainer())
        {
            rebuildPushMatroskaGeneration();
            return;
        }
        if (!playerReady)
        {
            log.debug("Player not ready, deferring flush");
            pendingFlush = true;
            return;
        }

        super.flush();

        // Reset resume tracking so time reporting starts fresh after seek.
        // The server will push new data from the new position and
        // getPlayerMediaTimeMillis will recalculate based on the new serverStartTime.
        resumeTimeOffset = -1;
        resumeMode = false;
        logTime = -1;

        if (player != null && pushMode)
        {
            // Seek to position 0 to trigger IJK's internal packet/decoder queue flush.
            // The actual target position doesn't matter (server controls what data arrives
            // next via push buffer). We just need IJK's read_thread to process a seek_req
            // which flushes all packet queues. Using 0 avoids Long.MAX_VALUE overflow
            // when IJK's JNI casts to (int) for the FFP_REQ_SEEK message.
            log.debug("Flush in push mode: seekTo(0) to flush IJK internal queues");
            seekToImpl(0);
        }
        else if (player != null)
        {
            if (VerboseLogging.DETAILED_PLAYER_LOGGING)
            {
                log.debug("Flush in pull mode: force seek to clear buffers");
            }
            seekToImpl(Long.MAX_VALUE);
        }
    }

    @Override
    public Dimension getVideoDimensions()
    {
        if (player != null)
        {
            Dimension d = new Dimension(player.getVideoWidth(), player.getVideoHeight());
            if (VerboseLogging.DETAILED_PLAYER_LOGGING) log.debug("getVideoSize(): {}", d);
            return d;
        }
        return null;
    }

    /**
     * True when the SageTV push descriptor URL describes an MPEG2-TS container
     * carrying an AC3 or E-AC3 (Dolby Digital / Digital Plus) audio track.
     *
     * <p>The TS push descriptor frequently omits the {@code sr=}/{@code ch=}
     * audio fields (unlike the MPEG2-PS variant), so ffmpeg must probe a Dolby
     * sync frame to learn the sample rate / channel count. That needs a larger
     * probe window than the default low-latency push tuning provides; see
     * {@link #setupPlayer(String)} for the failure mode this guards against.</p>
     */
    private static boolean isMpeg2TsDolbyAudio(String url)
    {
        if (url == null) return false;
        if (!url.contains("f=MPEG2-TS")) return false;
        // Audio tokens look like "[bf=aud;f=AC3;...]" / "f=EAC3" / "f=E-AC3".
        // "f=AC3" does not substring-match "f=EAC3"/"f=E-AC3", so test each.
        return url.contains("f=AC3") || url.contains("f=EAC3") || url.contains("f=E-AC3");
    }

    protected void setupPlayer(String sageTVurl)
    {
        log.debug("Creating Player");

        playerUrl = sageTVurl;
        resetPartyActivation();
        preSeekPos = -1;
        resumeTimeOffset = -1;
        resumeMode = false;
        firstFrameRendered = false;
        initialAudioStreamPos = -1;
        initialTextStreamPos = -1;
        logTime = -1;
        rebufferCount = 0;
        stalledDurationMs = 0;
        stallStartedAtMs = -1;
        latestSeekTargetMs = -1;
        latestSeekPositionMs = -1;
        networkDisconnected = false;

        releasePlayer();
        try
        {
            if (player == null)
            {
                player = new IjkMediaPlayer();
                ((IjkMediaPlayer) player).setOnMediaCodecSelectListener(CodecSelector.sInstance);
            }
            IjkMediaPlayer.native_setLogLevel(IjkMediaPlayer.IJK_LOG_ERROR);

            player.setDisplay(resolveInitialVideoHolder());

            ((IjkMediaPlayer) player).setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-avc", 1); // enable hardware acceleration
            ((IjkMediaPlayer) player).setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", 1); // enable hardware acceleration
            ((IjkMediaPlayer) player).setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-mpeg2", 1); // enable hardware acceleration
            ((IjkMediaPlayer) player).setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-all-videos", 1); // enable HW decode for all video codecs (MPEG4, VP8/VP9, etc.)
            ((IjkMediaPlayer) player).setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1); // let HW decoder handle rotation instead of software
            ((IjkMediaPlayer) player).setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1); // handle resolution changes in HW decoder without fallback to software
            ((IjkMediaPlayer) player).setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1);
            ((IjkMediaPlayer) player).setOption(
                    IjkMediaPlayer.OPT_CATEGORY_PLAYER,
                    "enable-accurate-seek",
                    pushMode ? 0 : 1);

            if (pushMode)
            {
                // Push-mode tuning: reduce internal buffering to cut startup
                // latency and limit stale data after seek. Values must be
                // conservative enough for MPEG2-TS (needs >32KB for PID/PMT
                // detection) while still faster than defaults (5MB/5s).
                //
                // Exception: AC3 / E-AC3 audio inside MPEG2-TS needs a larger
                // probe window than mere PID/PMT detection. ffmpeg must read a
                // Dolby sync frame to resolve sample_rate/channels, and the TS
                // push descriptor often omits sr=/ch= (unlike the MPEG2-PS
                // variant, which carries them). With the small 128KB/1.5s window
                // those params can come back 0, after which IJK aborts the audio
                // output with "Invalid sample rate or channel count!" — video
                // plays but there is no sound. AC3-in-MPEG2-PS resolves within
                // the small window, so only TS + (E-)AC3 is widened here; the
                // common AAC / PS paths keep the low-latency defaults.
                boolean widenAudioProbe = isMpeg2TsDolbyAudio(sageTVurl);
                int probeSize = widenAudioProbe ? 1024 * 1024 : 131072;        // 1 MB vs 128 KB
                int analyzeDuration = widenAudioProbe ? 4000000 : 1500000;     // 4s vs 1.5s
                ((IjkMediaPlayer) player).setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", probeSize);
                ((IjkMediaPlayer) player).setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", analyzeDuration);
                ((IjkMediaPlayer) player).setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1);
                ((IjkMediaPlayer) player).setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 2 * 1024 * 1024); // 2 MB (vs 15 MB) — limits stale data after seek
                if (widenAudioProbe)
                    log.debug("Push MPEG2-TS with AC3/E-AC3 audio: widening probe window to {} bytes / {} us so audio params resolve", probeSize, analyzeDuration);
            }
            else
            {
                ((IjkMediaPlayer) player).setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1);
                ((IjkMediaPlayer) player).setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 15 * 1024 * 1024); // 15MB internal buffer
            }

            // setting this to 0 removes the pixelization for mpeg2 videos
            ((IjkMediaPlayer) player).setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 0);

            player.setOnVideoSizeChangedListener(new IMediaPlayer.OnVideoSizeChangedListener()
            {
                @Override
                public void onVideoSizeChanged(IMediaPlayer iMediaPlayer, int width, int height, int sarNum, int sarDen)
                {
                    if (VerboseLogging.DETAILED_PLAYER_LOGGING)
                    {
                        log.debug("IJKPlayer.onVideoSizeChanged: {}x{}, {},{}", width, height, sarNum, sarDen);
                    }
                    setVideoSize(width, height, sarNum, sarDen);
                }
            });


            player.setOnErrorListener(new IMediaPlayer.OnErrorListener()
            {
                @Override
                public boolean onError(IMediaPlayer mp, int what, int extra)
                {
                    log.error("IjkPlayer onERROR: {}, {}", what, extra);
                    playerFailed();
                    return false;
                }
            });


            log.debug("Sending {} to mediaplayer", sageTVurl);

            if (pushMode)
            {
                log.info("Playing URL {} PUSH mode", sageTVurl);
                if (trickplayController != null && trickplayController.isNativeAvailable())
                {
                    log.debug("Using TransportIjkMediaSource (native ring buffer)");
                    trickplayController.open(true);
                    TransportIjkMediaSource transportDs = new TransportIjkMediaSource(trickplayController);
                    dataSource = (IMediaDataSource) transportDs;

                    trickplayController.setSeekCallback(new TrickplayController.PlayerSeekCallback()
                    {
                        @Override
                        public void onSeekTo(long timeMs)
                        {
                            // Push mode on IJK: the data source is a pipe and
                            // player.seekTo() is meaningless. The server
                            // handles the seek by flushing and re-pushing
                            // data from the new position. We rely on the
                            // controller's own state (freeze + convergence
                            // via onPlayerPosition) without invoking the
                            // player here.
                            log.debug("Push-mode seek commit (no-op on player): {}", timeMs);
                        }
                    });
                }
                else
                {
                    dataSource = new IJKPushMediaSource();
                    ((IJKPushMediaSource) dataSource).open(sageTVurl);
                }
                player.setDataSource(dataSource);
            }
            else
            {
                if (httpls)
                {
                    log.info("Playing URL Using HTTPL: isPush:{}, sageTVUrl: {}", pushMode, sageTVurl);
                    player.setDataSource(sageTVurl);
                }
                else
                {
                    log.info("Playing URL Using DataSource: isPush:{}, sageTVUrl: {}", pushMode, sageTVurl);
                    dataSource = new IJKPullMediaSource(
                            MiniclientApplication.get().getClient().getConnectedServerInfo().address,
                            new IJKPullMediaSource.DisconnectListener()
                            {
                                @Override
                                public void onNetworkDisconnected(java.io.IOException error)
                                {
                                    networkDisconnected = true;
                                    log.error("IJK pull transport disconnected after retries", error);
                                }
                            });
                    ((IJKPullMediaSource) dataSource).open(sageTVurl);
                    player.setDataSource(dataSource);

                    // Wire trickplay controller for pull mode time truth
                    if (trickplayController != null && trickplayController.isNativeAvailable())
                    {
                        trickplayController.open(false);
                        trickplayController.setSeekCallback(new TrickplayController.PlayerSeekCallback()
                        {
                            @Override
                            public void onSeekTo(long timeMs)
                            {
                                seekToImpl(timeMs);
                            }
                        });
                    }
                }
            }

            player.setOnCompletionListener(new IMediaPlayer.OnCompletionListener()
            {
                @Override
                public void onCompletion(IMediaPlayer iMediaPlayer)
                {
                    if (VerboseLogging.DETAILED_PLAYER_LOGGING) log.debug("MEDIA COMPLETE");
                    if (pushMode && !firstFrameRendered)
                    {
                        // Immediate EOF on a push stream that never rendered a frame:
                        // this is a failed open (malformed/empty container from the server),
                        // NOT a genuine end-of-stream. Surface one clear diagnostic for the
                        // server team instead of silently spinning in EOS. Behavior is
                        // otherwise unchanged (still enters EOS so getBufferLeft signals -1).
                        log.warn("OnCompletionListener fired with NO rendered frame in push mode - "
                            + "treating as immediate-EOF failed open (likely malformed/empty stream). uri={}", lastUri);
                    }
                    else
                    {
                        log.debug("OnCompletionListener fired.  Stoping playback and setting state to EOS");
                    }
                    stop();
                    state = EOS_STATE;
                    eos = true;
                }
            });

            player.setOnSeekCompleteListener(new IMediaPlayer.OnSeekCompleteListener()
            {
                @Override
                public void onSeekComplete(IMediaPlayer iMediaPlayer)
                {
                    seekPending = false;
                    latestSeekPositionMs = iMediaPlayer.getCurrentPosition();
                    if (trickplayController != null && trickplayController.isNativeAvailable())
                    {
                        trickplayController.notifySeekComplete();
                    }
                    if(player != null)
                    {
                        updateMediaSessionPlaybackState(player.getCurrentPosition());
                    }
                }
            });

            player.setOnInfoListener(new IMediaPlayer.OnInfoListener()
            {
                @Override
                public boolean onInfo(IMediaPlayer mp, int what, int extra)
                {
                    if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_START)
                    {
                        partyHandler.removeCallbacks(partyBufferingStart);
                        partyBufferingProbePositionMs = mp.getCurrentPosition();
                        partyBufferingProbeGeneration =
                                getPartyPlaybackCommandGeneration();
                        partyBufferingProbeStartedAtMs =
                                android.os.SystemClock.elapsedRealtime();
                        partyHandler.postDelayed(partyBufferingStart,
                                PARTY_BUFFERING_CONFIRM_MS);
                        if (stallStartedAtMs < 0)
                        {
                            rebufferCount++;
                            stallStartedAtMs = SystemClock.elapsedRealtime();
                        }
                    }
                    else if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_END)
                    {
                        partyHandler.removeCallbacks(partyBufferingStart);
                        partyBufferingProbePositionMs = -1;
                        partyBufferingProbeGeneration = -1;
                        partyBufferingProbeStartedAtMs = -1;
                        setPartyBuffering(false);
                        if (stallStartedAtMs >= 0)
                        {
                            stalledDurationMs +=
                                    SystemClock.elapsedRealtime() - stallStartedAtMs;
                            stallStartedAtMs = -1;
                        }
                    }
                    boolean videoRenderingEvent =
                            IjkPartyTrackActivation.isVideoRenderingEvent(what);
                    boolean audioRenderingEvent =
                            IjkPartyTrackActivation.isAudioRenderingEvent(what);
                    if (videoRenderingEvent || audioRenderingEvent)
                    {
                        log.info("IJK {} rendering started at playerPositionMs={}",
                                videoRenderingEvent ? "video" : "audio",
                                player != null ? player.getCurrentPosition() : -1);
                        if (isPartyHold())
                        {
                            try
                            {
                                player.setVolume(0.0f, 0.0f);
                            }
                            catch (Throwable ignored)
                            {
                            }
                        }
                        firstFrameRendered = true;
                        if (videoRenderingEvent && !videoRenderingStarted)
                        {
                            videoRenderingStarted = true;
                            resetPartyReadiness();
                        }
                        if (audioRenderingEvent && !audioRenderingStarted)
                        {
                            audioRenderingStarted = true;
                            resetPartyReadiness();
                        }
                        if (trickplayController != null && trickplayController.isNativeAvailable() && player != null)
                        {
                            trickplayController.onPlayerPosition(player.getCurrentPosition());
                        }
                        pauseAfterGenerationRebuildIfActivated();
                    }

                    // Attach the client EQ once audio is actually rendering: only then has
                    // IJK created its internal AudioTrack, so getAudioSessionId() returns a
                    // real (>0) effect-capable session. At onPrepared it is still 0.
                    if (what == IMediaPlayer.MEDIA_INFO_AUDIO_RENDERING_START)
                    {
                        attachEqFromIjkSession();
                    }
                    return false;
                }
            });

            player.setOnPreparedListener(new IMediaPlayer.OnPreparedListener()
            {
                @Override
                public void onPrepared(IMediaPlayer mp)
                {
                    playerReady = true;

                    if (pendingFlush)
                    {
                        log.debug("Clearing deferred flush - player just prepared, no flush needed");
                        pendingFlush = false;
                    }

                    if (pendingPlay)
                    {
                        log.debug("Clearing deferred play - onPrepared will start player");
                        pendingPlay = false;
                    }

                    // Apply a server-requested initial audio track BEFORE start(). IJK
                    // auto-selects a default audio track as soon as the decoder starts
                    // running; correcting it afterward races IJK's own default-stream
                    // heuristic (observed picking an AC3 SAP sub-stream over Main) and
                    // is not reliably honored by selectTrack() once playback is already
                    // decoding the wrong track. playerReady is already true above, so
                    // setAudioTrack() applies immediately instead of deferring.
                    if (initialAudioStreamPos != -1)
                    {
                        setAudioTrack(initialAudioStreamPos);
                    }

                    if (isPartyHold())
                    {
                        player.setVolume(0.0f, 0.0f);
                        if (player instanceof IjkMediaPlayer)
                        {
                            IjkMediaPlayer ijk = (IjkMediaPlayer) player;
                            int audioTrack = partyAudioTrack(ijk);
                            if (audioTrack >= 0)
                            {
                                if (ijk.getSelectedTrack(
                                        IjkTrackInfo.MEDIA_TRACK_TYPE_AUDIO) != audioTrack)
                                {
                                    ijk.selectTrack(audioTrack);
                                }
                                log.info("Party hold retained muted IJK audio track {}",
                                        audioTrack);
                            }
                            else
                            {
                                log.warn("Party hold could not resolve an IJK audio track");
                            }
                        }
                        player.start();
                        state = PLAY_STATE;
                    }
                    else
                    {
                        player.start();
                        state = PLAY_STATE;
                    }

                    // First attempt (often still session 0 here); the real attach happens
                    // on MEDIA_INFO_AUDIO_RENDERING_START once the AudioTrack exists.
                    attachEqFromIjkSession();

                    if (!pushMode)
                    {
                        if (preSeekPos != -1)
                        {
                            if (VerboseLogging.DETAILED_PLAYER_LOGGING)
                                log.debug("Resuming At Position: {}", preSeekPos);
                            seekToImpl(preSeekPos);
                            preSeekPos = -1;
                        }
                        else
                        {
                            if (VerboseLogging.DETAILED_PLAYER_LOGGING)
                                log.debug("No Resume");
                        }
                    }

                    if (MiniclientApplication.get().getClient().properties().getBoolean(PrefStore.Keys.announce_software_decoder, false))
                    {
                        MediaInfo mi = player.getMediaInfo();
                        if (mi != null)
                        {
                            log.info("MEDIAINFO: video: {},{}", mi.mVideoDecoder, mi.mVideoDecoderImpl);
                            if (!"mediacodec".equalsIgnoreCase(mi.mVideoDecoder))
                            {
                                message("Using Software Decoder (" + (pushMode ? "PUSH MODE" : "PULL MODE") + ")");
                            }
                        }
                    }

                    //Create Media Session
                    IJKMediaPlayerImpl.this.mediaSession = new MediaSessionCompat(IJKMediaPlayerImpl.this.context.getContext(), "SageTV Android TV Client");
                    mediaSession.setCallback(new MediaSessionCallbackHandler(IJKMediaPlayerImpl.this, context.getClient(), context.getContext()));

                    mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS | MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS);

                    long duration;

                    if(player.getDuration() < 0)
                    {
                        duration = -1;
                    }
                    else
                    {
                        duration = player.getDuration();
                    }
                    //Library files start with stc:// but do not have push in it
                    //Live TV has push: with a lot of other data in it

                    setMediaSessionMetadata(sageTVurl, duration);
                    mediaSession.setActive(true);

                    updateMediaSessionPlaybackState(player.getCurrentPosition());
                }
            });

            player.prepareAsync();
            log.debug("mediaplayer has our URL");
        }

        catch (Exception e)
        {
            log.error("Failed to create player", e);
            playerFailed();
        }
    }

    private int partyAudioTrack(IjkMediaPlayer ijk)
    {
        int selected = ijk.getSelectedTrack(IjkTrackInfo.MEDIA_TRACK_TYPE_AUDIO);
        if (selected >= 0) return selected;
        IjkTrackInfo[] tracks = ijk.getTrackInfo();
        if (tracks == null) return -1;
        for (int i = 0; i < tracks.length; i++)
        {
            if (tracks[i] != null
                    && tracks[i].getTrackType() == IjkTrackInfo.MEDIA_TRACK_TYPE_AUDIO)
            {
                return i;
            }
        }
        return -1;
    }

    protected void seekToImpl(long timeInMillis)
    {
        seekPending = true;
        latestSeekTargetMs = timeInMillis;
        latestSeekPositionMs = -1;
        player.seekTo(timeInMillis);
    }

    private boolean isMatroskaContainer()
    {
        if (containerHint == null) return false;
        String normalized = containerHint.trim().toUpperCase(java.util.Locale.ROOT);
        return "MATROSKA".equals(normalized) || "MKV".equals(normalized)
                || "WEBM".equals(normalized);
    }

    private void rebuildPushMatroskaGeneration()
    {
        final String rebuildUrl = playerUrl;
        final boolean resumePlaying = state == PLAY_STATE;
        rebuildingMatroskaGeneration = true;
        final CountDownLatch stopped = new CountDownLatch(1);
        context.runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    releasePlayer();
                    log.info("Push Matroska flush: retired old IJK parser/player generation");
                }
                finally
                {
                    stopped.countDown();
                }
            }
        });
        awaitGenerationStep(stopped, "retiring old IJK parser/player");

        super.flush();

        final CountDownLatch rebuilt = new CountDownLatch(1);
        context.runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    if (rebuildUrl == null)
                    {
                        playerFailed();
                        return;
                    }
                    pauseAfterGenerationRebuild = !resumePlaying;
                    state = LOADED_STATE;
                    context.setupVideoFrame();
                    setupPlayer(rebuildUrl);
                    log.info("Push Matroska flush: created fresh IJK parser/player generation");
                }
                finally
                {
                    rebuildingMatroskaGeneration = false;
                    rebuilt.countDown();
                }
            }
        });
        awaitGenerationStep(rebuilt, "creating fresh IJK parser/player");
    }

    private void awaitGenerationStep(CountDownLatch latch, String action)
    {
        try
        {
            if (!latch.await(2, TimeUnit.SECONDS))
            {
                log.warn("Push Matroska flush: timed out {}", action);
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void seek(long timeInMS)
    {
        resetPartyActivation();
        super.seek(timeInMS);
        if (player == null || state == NO_STATE || state == LOADED_STATE)
        {
            if (VerboseLogging.DETAILED_PLAYER_LOGGING)
            {
                log.debug("Setting Pre-Seek {}", timeInMS);
            }
            preSeekPos = timeInMS;
            return;
        }

        if (!pushMode)
        {
            if (player.isPlaying() || state == PAUSE_STATE || state == PLAY_STATE)
            {
                if (VerboseLogging.DETAILED_PLAYER_LOGGING)
                {
                    log.debug("Immediate Seek {}", timeInMS);
                }
                seekToImpl(timeInMS);
            }
            else
            {
                log.info("We Missed a Seek for {}: player.isPlaying {}; State: {}; playerReader: {}", timeInMS, player.isPlaying(), state, playerReady);
            }
        }
        else
        {
            // In push mode, don't call player.seekTo() — the data source is a pipe
            // that can't seek. The server handles seeking by flushing the old data
            // and pushing new data from the seek position. Reset resume tracking
            // so getPlayerMediaTimeMillis() picks up the new serverStartTime correctly.
            log.debug("Push mode seek to {}: reset resume state, server will push new data", timeInMS);
            resumeTimeOffset = -1;
            resumeMode = false;
            logTime = -1;
        }
    }

    /**
     * Read IJK's live audio session id and (re)attach the client EQ to it. IJK creates
     * its internal AudioTrack lazily on the audio output thread, so a valid (&gt;0) session
     * id is only available once audio is rendering. If it is still 0, retry shortly.
     */
    private void attachEqFromIjkSession()
    {
        attachEqFromIjkSession(0);
    }

    private void attachEqFromIjkSession(final int attempt)
    {
        if (player == null) return;
        int sid;
        try
        {
            sid = player.getAudioSessionId();
        }
        catch (Throwable t)
        {
            log.warn("EQ: IJK getAudioSessionId() failed: {}", t.getMessage());
            return;
        }

        if (sid > 0)
        {
            int ch = ijkOutputChannelCount();
            if (sid != eqSessionId)
            {
                eqSessionId = sid;
                log.info("EQ: attaching to IJK audio session id={} ch={} (attempt {})", sid, ch, attempt);
            }
            // Feed IJK's REAL output channel count so the engine builds a matching
            // DynamicsProcessing config. A stereo processor on a 5.1 stream (or vice
            // versa) garbles audio into chirps.
            sagex.miniclient.android.audio.eq.EqManager.get().onAudioSessionChanged(sid, ch, true);
            return;
        }

        if (attempt < 10)
        {
            // AudioTrack not up yet; retry on the main thread.
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable()
            {
                @Override
                public void run()
                {
                    attachEqFromIjkSession(attempt + 1);
                }
            }, 250);
        }
        else
        {
            log.warn("EQ: IJK audio session id still 0 after {} attempts; EQ not attached", attempt);
        }
    }

    /**
     * Best-effort read of IJK's decoded audio channel count from the active audio
     * stream's ffmpeg channel-layout bitmask (popcount = channel count). This is the
     * number of PCM channels IJK feeds its AudioTrack, which the EQ engine must match.
     * Falls back to stereo when unknown. Clamped to 1..8.
     */
    private int ijkOutputChannelCount()
    {
        try
        {
            if (player != null)
            {
                MediaInfo mi = player.getMediaInfo();
                if (mi != null && mi.mMeta != null && mi.mMeta.mAudioStream != null)
                {
                    long layout = mi.mMeta.mAudioStream.mChannelLayout;
                    int ch = Long.bitCount(layout);
                    if (ch >= 1 && ch <= 8)
                    {
                        log.info("EQ: IJK audio channelLayout=0x{} -> {} ch",
                                Long.toHexString(layout), ch);
                        return ch;
                    }
                    log.info("EQ: IJK audio channelLayout=0x{} out of range ({}); using stereo",
                            Long.toHexString(layout), ch);
                }
            }
        }
        catch (Throwable t)
        {
            log.warn("EQ: could not read IJK channel count: {}", t.getMessage());
        }
        return 2;
    }

    /**
     * This finds the correct track position in the IJKPlayer track list.  SageTV gives us a zero based index of all audio tracks.  IJKPlayer
     * uses a track position of all tracks in the file.  Video is is track 0.  If it was an audio only file, I assume track 0 would be the audio
     *
     * @param sageTVPosition Track position sage has requested us to change to
     * @return IJKPlayer track position
     */
    private int getAudioTrackPosition(int sageTVPosition)
    {
        if (player == null) return -1;

        ITrackInfo info[] = player.getTrackInfo();
        if (info == null || info.length == 0) return -1;

        // SageTV may send raw MPEG PES stream IDs rather than zero-based indices.
        // Translate them the same way Exo2MediaPlayerImpl does so callers using
        // either backend get consistent behavior.
        //   MPEG audio:                       0xC000-0xDFFF -> (sageTVPosition - 0xC000)
        //   AC3 (private_stream_1 sub-stream): 0xBD80-0xBDBF -> (sageTVPosition & 0x07)
        int zeroBasedPos;
        if (sageTVPosition >= 0xC000 && sageTVPosition < 0xE000)
        {
            zeroBasedPos = sageTVPosition - 0xC000;
        }
        else if (sageTVPosition >= 0xBD80 && sageTVPosition <= 0xBDBF)
        {
            zeroBasedPos = sageTVPosition & 0x07;
        }
        else
        {
            zeroBasedPos = sageTVPosition;
        }

        int audioTrackCount = 0;
        int foundPos = -1;

        for (int i = 0; i < info.length; i++)
        {
            if (info[i] == null) continue;

            // Temporary diagnostic: dump every track IJK enumerates (type/lang/codec)
            // so we can verify its physical audio-track order actually matches the
            // SageTV PES-stream-ID assumption above (SAP-vs-Main mis-selection triage).
            log.debug("AudioTrackScan i={} type={} lang={} info={}",
                    i, info[i].getTrackType(), info[i].getLanguage(), info[i].getInfoInline());

            if (info[i].getTrackType() == IjkTrackInfo.MEDIA_TRACK_TYPE_AUDIO)
            {
                if (foundPos == -1 && audioTrackCount == zeroBasedPos)
                {
                    foundPos = i;
                }

                audioTrackCount++;
            }
        }

        log.debug("AudioTrackScan sageTVPosition={} zeroBasedPos={} audioTrackCount={} resolvedIjkPos={}",
                sageTVPosition, zeroBasedPos, audioTrackCount, foundPos);

        return foundPos;
    }

    /**
     * This finds the correct track position in the IJKPlayer track list.  SageTV gives us a zero based index of all aubtitle tracks.  IJKPlayer
     * uses a track position of all tracks in the file.  Video is is track 0.  If it was an audio only file, I assume track 0 would be the audio
     *
     * @param sageTVPosition Track position sage has requested us to change to
     * @return IJKPlayer track position
     */
    private int getSubtitleTrackPosition(int sageTVPosition)
    {
        if (player == null) return -1;

        ITrackInfo info[] = player.getTrackInfo();
        if (info == null || info.length == 0) return -1;

        int subtitleTrackCount = 0;

        for (int i = 0; i < info.length; i++)
        {
            if (info[i] == null) continue;

            log.debug("Track Pos {}, TrackType {}, Track Info {}, Track Lang ", i, info[i].getTrackType(), info[i].getInfoInline(), info[i].getLanguage());

            if (info[i].getTrackType() == IjkTrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT)
            {
                if (subtitleTrackCount == sageTVPosition)
                {
                    return i;
                }

                subtitleTrackCount++;
            }
        }

        return -1;
    }

    @Override
    public void setAudioTrack(int streamPos)
    {
        //NOTE: Do not try to set the track position to a currently selected audio track. IJKPlayer really does not like that, and will crash.

        log.debug("setAudioTrack Called StreamPosition: {}", streamPos);

        if (playerReady)
        {
            int currentTrack = ((IjkMediaPlayer) player).getSelectedTrack(IjkTrackInfo.MEDIA_TRACK_TYPE_AUDIO);
            int setTrackTo = this.getAudioTrackPosition(streamPos);

            log.debug("Selected Audio Track Pos: {}", currentTrack);

            if (setTrackTo == -1)
            {
                log.warn("Unable to find audio track postion in IJKPlayer!");
                return;
            }

            if (currentTrack != setTrackTo)
            {
                log.debug("Setting audio track to IJKPlayer Track Position: {}", setTrackTo);
                ((IjkMediaPlayer) player).selectTrack(setTrackTo);
            }

        }
        else
        {
            log.debug("setAudioTrack player not ready.  Storing values for setting when player is initialized");

            this.initialAudioStreamPos = streamPos;
        }
    }

    @Override
    public int getSubtitleTrackCount()
    {
        return getSubtitleTracks().length;
    }

    @Override
    public SubtitleTrack[] getSubtitleTracks()
    {
        if (player == null) return new SubtitleTrack[0];
        ITrackInfo[] trackInfo = player.getTrackInfo();
        MediaInfo mediaInfo = player.getMediaInfo();
        if (trackInfo == null) return new SubtitleTrack[0];

        List<SubtitleTrack> subtitles = new ArrayList<>();
        for (int i = 0; i < trackInfo.length; i++)
        {
            ITrackInfo track = trackInfo[i];
            if (track == null) continue;
            if (track.getTrackType() != ITrackInfo.MEDIA_TRACK_TYPE_SUBTITLE
                    && track.getTrackType() != ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT)
            {
                continue;
            }
            String codecName = "";
            if (mediaInfo != null && mediaInfo.mMeta != null
                    && i < mediaInfo.mMeta.mStreams.size())
            {
                IjkMediaMeta.IjkStreamMeta stream = mediaInfo.mMeta.mStreams.get(i);
                if (stream != null && stream.mCodecName != null) codecName = stream.mCodecName;
            }
            SubtitleCodec codec = getSubtitleCodec(codecName);
            subtitles.add(new SubtitleTrack(
                    i,
                    codec,
                    track.getLanguage(),
                    codecName,
                    false));
        }
        return subtitles.toArray(new SubtitleTrack[subtitles.size()]);
    }

    private static SubtitleCodec getSubtitleCodec(String codecName)
    {
        if (codecName == null) return null;
        if ("subrip".equalsIgnoreCase(codecName) || "srt".equalsIgnoreCase(codecName))
            return SubtitleCodec.SUBRIP;
        if ("hdmv_pgs_subtitle".equalsIgnoreCase(codecName)
                || "pgssub".equalsIgnoreCase(codecName))
            return SubtitleCodec.PGS;
        return null;
    }

    @Override
    public void setSubtitleTrack(int streamPos)
    {
        //Displaying subtitle/timedtext does not appear to be supported at this time.
        log.debug("TODO: setSubtitleTrack Called StreamPosition: {}", streamPos);

        if (player == null)
        {
            this.initialTextStreamPos = streamPos;
        }
        else
        {
            // NOT IMPLEMENTED YET, let's comment out the code until it is
            // since it's causing problems in the playback.
//            int currentTrack = ((IjkMediaPlayer) player).getSelectedTrack(IjkTrackInfo.MEDIA_TRACK_TYPE_SUBTITLE);
//            int trackPos = this.getSubtitleTrackPosition(streamPos);
//
//            if (playerReady && currentTrack != trackPos && trackPos != -1) {
//                log.debug("FUNCTION NOT SUPPORTED (Setting subtitle to IJKPosition): {}", trackPos);
//                //((IjkMediaPlayer) player).selectTrack(trackPos);
//            }
        }
    }

    @Override
    public int getSelectedSubtitleTrack()
    {
        if (!(player instanceof IjkMediaPlayer)) return DISABLE_TRACK;
        int selected = ((IjkMediaPlayer) player)
                .getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
        if (selected < 0)
        {
            selected = ((IjkMediaPlayer) player)
                    .getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_SUBTITLE);
        }
        return selected < 0 ? DISABLE_TRACK : selected;
    }

    protected void releasePlayer()
    {
        resetPartyActivation();
        if (!rebuildingMatroskaGeneration)
        {
            pauseAfterGenerationRebuild = false;
        }
        // Atomically claim the player/mediaSession references and null them
        // out BEFORE doing any teardown work. If releasePlayer() is invoked
        // a second time while this teardown is still in flight (e.g. a
        // GFX-channel disconnect handler racing an activity finish()/exit
        // path), the second call sees null immediately and returns as a
        // true no-op instead of redundantly touching the same native
        // player/surface/EGL state. See releaseLock javadoc above.
        final IMediaPlayer playerToRelease;
        final MediaSessionCompat mediaSessionToRelease;
        synchronized (releaseLock)
        {
            if (player == null && mediaSession == null)
                return;
            playerToRelease = player;
            mediaSessionToRelease = mediaSession;
            player = null;
            mediaSession = null;
        }

        if (mediaSessionToRelease != null)
        {
            log.debug("Media Session RELEASE");
            mediaSessionToRelease.setActive(false);
            mediaSessionToRelease.release();
        }

        try
        {
            releaseDataSource();
        }
        catch (Throwable error)
        {
            log.warn("Unable to release IJK data source", error);
        }

        if (playerToRelease == null)
        {
            super.releasePlayer();
            return;
        }
        log.debug("Releasing Player");

        try
        {
            playerToRelease.setDisplay(null);
        }
        catch (RuntimeException error)
        {
            log.warn("Unable to detach IJK display before release", error);
        }

        releaseIjkPlayerBounded(playerToRelease);

        try
        {
            clearSurface();
        }
        catch (Throwable t)
        {
        }
        try
        {
            sagex.miniclient.android.audio.eq.EqManager.get().onPlayerReleased();
        }
        catch (Throwable ignored) { }
        super.releasePlayer();
    }

    private void releaseIjkPlayerBounded(final IMediaPlayer playerToRelease)
    {
        final CountDownLatch released = new CountDownLatch(1);
        Thread releaseThread = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    if (playerToRelease.isPlaying()) playerToRelease.pause();
                }
                catch (RuntimeException error)
                {
                    log.warn("IJK pause during release failed", error);
                }
                try
                {
                    playerToRelease.stop();
                }
                catch (RuntimeException error)
                {
                    log.warn("IJK stop during release failed", error);
                }
                try
                {
                    playerToRelease.reset();
                }
                catch (RuntimeException error)
                {
                    log.warn("IJK reset during release failed", error);
                }
                try
                {
                    playerToRelease.release();
                }
                catch (RuntimeException error)
                {
                    log.warn("IJK release failed", error);
                }
                finally
                {
                    released.countDown();
                }
            }
        }, "ijk-release");
        releaseThread.setDaemon(true);
        releaseThread.start();
        try
        {
            if (!released.await(2, TimeUnit.SECONDS))
            {
                log.error("IJK release exceeded 2 seconds after transport cancellation");
                releaseThread.interrupt();
            }
        }
        catch (InterruptedException error)
        {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for IJK release", error);
        }
    }

    private void updateMediaSessionPlaybackState(long playbackPostion)
    {
        if(mediaSession != null)
        {
            PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder();
            stateBuilder.setActions(this.getMediaSessionActions());

            if (player != null && getState() == PLAY_STATE)
            {
                stateBuilder.setState(PlaybackStateCompat.STATE_PLAYING, playbackPostion, 1.0f);
            }
            else
            {
                stateBuilder.setState(PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f);
            }


            mediaSession.setPlaybackState(stateBuilder.build());
        }
    }

    private long getMediaSessionActions()
    {
        long actions = 0;

        if(player != null)
        {
            if (getState() == PLAY_STATE)
            {
                actions = PlaybackState.ACTION_STOP;
                actions |= PlaybackState.ACTION_PAUSE;
                actions |= PlaybackState.ACTION_FAST_FORWARD;
                actions |= PlaybackState.ACTION_REWIND;
                actions |= PlaybackState.ACTION_SEEK_TO;
                actions |= PlaybackState.ACTION_SKIP_TO_NEXT;
                actions |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
            }
            else
            {
                actions = PlaybackState.ACTION_PLAY;
            }

        }

        return actions;
    }

    private void setMediaSessionMetadata(String displayTitle, long duration)
    {
        MediaMetadataCompat.Builder metaDataBuilder = new MediaMetadataCompat.Builder();

        metaDataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, displayTitle);
        metaDataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration);
        mediaSession.setMetadata(metaDataBuilder.build());
    }
}
