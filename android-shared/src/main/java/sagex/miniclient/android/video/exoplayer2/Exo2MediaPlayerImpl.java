package sagex.miniclient.android.video.exoplayer2;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.trackselection.TrackSelectionOverride;
import com.google.android.exoplayer2.upstream.DataSource;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.audio.AudioCapabilities;
import com.google.android.exoplayer2.audio.AudioSink;
import com.google.android.exoplayer2.audio.DefaultAudioSink;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.RendererCapabilities;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.ext.ffmpeg.FfmpegLibrary;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.text.Cue;

import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.ui.SubtitleView;
import com.google.android.exoplayer2.video.VideoSize;

import java.util.HashSet;
import java.util.List;

import sagex.miniclient.MiniPlayerPlugin;
import sagex.miniclient.android.MiniclientApplication;
import sagex.miniclient.android.ui.AndroidUIController;
import sagex.miniclient.android.util.Logger;
import sagex.miniclient.android.video.BaseMediaPlayerImpl;
import sagex.miniclient.android.video.MediaSessionCallbackHandler;
import sagex.miniclient.android.middleware.TrickplayController;
import sagex.miniclient.android.middleware.TransportDataSource;
import sagex.miniclient.media.SubtitleCodec;
import sagex.miniclient.media.SubtitleTrack;
import sagex.miniclient.prefs.PrefStore;
import sagex.miniclient.uibridge.Dimension;
import sagex.miniclient.util.Utils;
import sagex.miniclient.util.VerboseLogging;

import java.util.Set;
import android.support.v4.media.session.MediaSessionCompat;

/**
 * Created by seans on 24/09/16.
 */

public class Exo2MediaPlayerImpl extends BaseMediaPlayerImpl<ExoPlayer, DataSource>
{
    static final Logger log = Logger.getLogger(Exo2MediaPlayerImpl.class);
    static final int MAX_PLAYBACK_RETRY_COUNT = 12;

    private MediaSource mediaSource;
    private long playbackStartPosition = -1;
    private int initialAudioTrackIndex = -1;
    private volatile long currentPlaybackPosition = 0;
    private DefaultTrackSelector trackSelector;
    private int selectedSubtitleTrack = DISABLE_TRACK;

    private boolean errorState = false;
    private int retryCount = 0;

    private boolean showCaptions = false;
    private Handler handler;
    private Runnable progressRunnable;
    private String url;

    MediaSessionCompat mediaSession;

    private SubtitleView subView;

    public Exo2MediaPlayerImpl(AndroidUIController activity)
    {
        super(activity, true, false);
    }

    public long getPlaybackPosition()
    {
        return this.currentPlaybackPosition;
    }

    public void setPlaybackPosition(long position)
    {
        currentPlaybackPosition = Math.max(position, 0);
    }

    boolean ExoIsPlaying()
    {
        if (player == null)
        {
            return false;
        }

        return player.getPlayWhenReady();
    }

    void ExoPause()
    {
        if (player == null)
        {
            return;
        }

        log.logInfo("Pause was called");
        player.setPlayWhenReady(false);
    }

    void ExoStart()
    {
        if (player == null)
        {
            return;
        }
        log.logDebug("Start was called");
        player.setPlayWhenReady(true);
    }

    protected void releasePlayer()
    {
        if(mediaSession != null)
        {
            log.logDebug("Releaseing Android Media Session");
            mediaSession.setActive(false);
            mediaSession.release();
        }

        context.runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                if (player != null)
                {
                    try
                    {
                        if (ExoIsPlaying())
                        {
                            ExoPause();
                        }
                    }
                    catch (Exception ex)
                    {
                        log.logError("Error pausing video during player releasing", ex);
                    }

                    try
                    {
                        player.release();
                    }
                    catch (Exception ex)
                    {
                        log.logError("Error calling release on player", ex);
                    }

                    player = null;
                    Exo2MediaPlayerImpl.super.releasePlayer();
                }
            }
        });

        this.RemoveSubTitleView();
    }

    @Override
    public Dimension getVideoDimensions()
    {
        log.logDebug("getVideoDimensions");

        if (player != null)
        {
            if (player.getVideoFormat() != null)
            {
                Dimension d = new Dimension(player.getVideoFormat().width, player.getVideoFormat().height);
                log.logDebug("getVideoSize(): " + d);

                return d;
            }
            else
            {
                log.logDebug("getVideoDimensions: player.getFormat is null");
            }
        }
        else
        {
            log.logDebug("getVideoDimensions: player is null");
        }
        return null;
    }

    @Override
    public long getPlayerMediaTimeMillis(long lastServerTime)
    {
        long position = this.getPlaybackPosition();

        //log.debug("ExoLogging - getPlayerMediaTimeMillis Called lastServerTime=" + Utils.toHHMMSS(lastServerTime) + " position=" + Utils.toHHMMSS(position));

        if (lastServerTime < 0)
        {
            log.logDebug("getPlayerMediaTimeMillis(): Flush was called waiting for last serverTime to be > 0");
            return -1;
        }

        return lastServerTime + position;
    }

    @Override
    public void stop()
    {
        context.runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                log.logDebug("Stop called");
                if(player != null)
                {
                    player.stop();
                }

                if(mediaSession != null)
                {
                    mediaSession.setActive(false);
                }

                if (playerReady)
                {
                    if (player == null)
                    {
                        return;
                    }

                    player.setPlayWhenReady(false);
                }
            }
        });

        super.stop();
    }

    @Override
    public void pause()
    {
        log.logDebug("Pause called");

        if (this.getState() == MiniPlayerPlugin.PAUSE_STATE && !pushMode)
        {
            log.logDebug("Already in pause state.  Seeking frame instead...");
            //TODO: Could not find the framerate in ExoPlayer.  Going to assume 30fps for now.
            this.seek(this.getPlaybackPosition() + Math.round(1000.0 / 30.0));
            return;
        }


        super.pause();

        context.runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                if (playerReady)
                {
                    ExoPause();
                }
            }
        });

        updateMediaSessionPlaybackState(Exo2MediaPlayerImpl.this.getPlaybackPosition());
    }

    @Override
    public void play()
    {
        log.logDebug("Play called");

        super.play();

        context.runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                if (playerReady)
                {
                    ExoStart();
                }
            }
        });

        updateMediaSessionPlaybackState(Exo2MediaPlayerImpl.this.getPlaybackPosition());
    }

    private void seekToImpl(long timeInMillis)
    {
        if (timeInMillis > 0)
        {
            context.runOnUiThread(new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        if (player == null) return;

                        if (VerboseLogging.DETAILED_PLAYER_LOGGING)
                            log.logDebug("Seek Called - Current Position: " + player.getContentPosition()
                                + "  Seek Request: " + timeInMillis
                                + " Difference: " + (player.getContentPosition() - timeInMillis));

                        player.seekTo(timeInMillis);
                    }
                    catch (Exception ex)
                    {
                        log.logError("Error during seek request. Position MS: " + timeInMillis, ex);
                    }
                }
            });
        }
    }

    @Override
    public void seek(long timeInMS)
    {
        try
        {

            log.logDebug("Seek - pushmode: " + pushMode + ", timeinMS " + timeInMS + ", playerReady " + playerReady);

            super.seek(timeInMS);

            if (playerReady)
            {
                if (!pushMode)
                {
                    if (player == null)
                    {
                        log.logDebug("Seek player is null storing position: " + timeInMS);
                        playbackStartPosition = timeInMS;
                    }
                    // Pull-mode: super.seek() above already called
                    // trickplayController.beginSeek(timeInMS), which arms the
                    // coalesce timer. When it fires, seekCallback ->
                    // seekToImpl -> player.seekTo() is invoked with the latest
                    // coalesced target. ExoPlayer handles seekTo() in any
                    // state (IDLE / BUFFERING / READY), so no further deferral
                    // is required here.
                }
                else
                {
                    // Push-mode: also drive through the controller's coalesce.
                    // super.seek() called beginSeek() which arms the timer; the
                    // commit Runnable will invoke seekToImpl via seekCallback,
                    // collapsing rapid bursts (smooth-FF/REW) into a single
                    // pipeline rebuild.
                    if (player == null)
                    {
                        log.logDebug("Seek (push) player is null storing position: " + timeInMS);
                        playbackStartPosition = timeInMS;
                    }
                }
            }
            else
            {

                log.logDebug("Seek Resume: " + timeInMS);
                playbackStartPosition = timeInMS;
            }
        }
        catch (Exception ex)
        {
            log.logError("Unexpected error during seek", ex);
            ex.printStackTrace();
        }
    }

    @Override
    public void setSubtitleTrack(final int streamPos)
    {
        // ExoPlayer is main-thread-only. Like setAudioTrack, this is invoked
        // from the Media-* dispatch thread, so the changeTrack() call and any
        // SubtitleView lifecycle changes (which manipulate the View hierarchy)
        // must run on the UI thread.
        context.runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                int mappedIndex = (streamPos == Exo2MediaPlayerImpl.DISABLE_TRACK)
                        ? streamPos
                        : mapSubtitleStreamPosToTrackIndex(streamPos);
                log.logDebug("Set Subtitle Track Called: " + streamPos + " mapped to: " + mappedIndex);

                if (mappedIndex == Exo2MediaPlayerImpl.DISABLE_TRACK)
                {
                    showCaptions = false;
                    RemoveSubTitleView();
                }
                else
                {
                    showCaptions = true;
                    AddSubTitleView();
                }

                changeTrack(C.TRACK_TYPE_TEXT, mappedIndex, 0);
            }
        });
    }

    private int mapSubtitleStreamPosToTrackIndex(int streamPos)
    {
        int mapped = (streamPos >= 0x2000 && streamPos < 0x4000)
                ? streamPos - 0x2000
                : streamPos;
        if (trackSelector != null)
        {
            int textGroups = getTrackCount(C.TRACK_TYPE_TEXT);
            if (textGroups > 0 && (mapped < 0 || mapped >= textGroups))
            {
                log.logDebug("mapSubtitleStreamPosToTrackIndex: mapped index " + mapped
                        + " out of range (textGroups=" + textGroups + "); falling back to 0");
                mapped = 0;
            }
        }
        return mapped;
    }

    @Override
    public int getSelectedSubtitleTrack()
    {
        return this.selectedSubtitleTrack;
    }

    @Override
    public int getSubtitleTrackCount()
    {
        return getTrackCount(C.TRACK_TYPE_TEXT);
    }

    @Override
    public void setAudioTrack(final int streamPos)
    {
        // ExoPlayer is main-thread-only. setAudioTrack is invoked from the
        // Media-* thread (MediaCmd dispatch), so the read of getPlayWhenReady()
        // and any subsequent changeTrack() call MUST be marshalled to the UI
        // thread or ExoPlayerImpl.verifyApplicationThread throws.
        context.runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                // SageTV server sends MPEG PES stream IDs (e.g. 0xC000 for first MPEG audio,
                // 0xBD80 for first AC3 sub-stream of private_stream_1) rather than zero-based
                // track indices. Map to a zero-based group index that ExoPlayer can use.
                int mappedIndex = mapStreamPosToTrackIndex(streamPos);
                log.logDebug("setAudioTrack: streamPos=" + streamPos + " (0x" + Integer.toHexString(streamPos)
                        + ") mapped to groupIndex=" + mappedIndex);

                if (!ExoIsPlaying())
                {
                    initialAudioTrackIndex = mappedIndex;
                }
                else
                {
                    initialAudioTrackIndex = -1;
                    changeTrack(C.TRACK_TYPE_AUDIO, mappedIndex, 0);
                }
            }
        });
    }

    /**
     * Maps a SageTV-supplied audio stream identifier to a zero-based ExoPlayer
     * audio-renderer group index.
     *
     * SageTV may send the value as a raw MPEG PES stream identifier:
     *   - MPEG audio:           0xC000-0xDFFF  -> (streamPos - 0xC000)
     *   - AC3 (private_stream_1 sub-stream):
     *                            0xBD80-0xBDBF -> (streamPos & 0x07)
     *   - DTS (private_stream_1 sub-stream):
     *                            0xBD88-0xBD8F -> (streamPos & 0x07)
     *
     * Anything else is assumed to already be a zero-based index. The result is
     * clamped to the actual number of available audio groups (falling back to 0
     * when the mapping points beyond the discovered tracks) so that
     * {@link #changeTrack(int, int, int)} cannot raise an
     * {@link IndexOutOfBoundsException}.
     */
    private int mapStreamPosToTrackIndex(int streamPos)
    {
        int mapped;
        if (streamPos >= 0xC000 && streamPos < 0xE000)
        {
            mapped = streamPos - 0xC000;
        }
        else if (streamPos >= 0xBD80 && streamPos <= 0xBDBF)
        {
            mapped = streamPos & 0x07;
        }
        else
        {
            mapped = streamPos;
        }

        // Only clamp when the player/trackSelector exists. Before setupPlayer()
        // there is no track info and getTrackCount() would NPE; the stored
        // initialAudioTrackIndex is reapplied in STATE_READY where this method
        // can clamp safely.
        if (trackSelector != null)
        {
            int audioGroups = getTrackCount(C.TRACK_TYPE_AUDIO);
            if (audioGroups > 0 && (mapped < 0 || mapped >= audioGroups))
            {
                log.logDebug("mapStreamPosToTrackIndex: mapped index " + mapped
                        + " out of range (audioGroups=" + audioGroups + "); falling back to 0");
                mapped = 0;
            }
        }
        return mapped;
    }

    @Override
    public synchronized void flush()
    {
        log.logDebug("Flush called, pushMode=" + pushMode);
        super.flush();

        context.runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    if (player == null)
                    {
                        return;
                    }

                    if (pushMode)
                    {
                        // Push-mode flush: rebuild the player's MediaSource
                        // so renderers (especially the audio sink) are
                        // fully reset. seekTo(...) is too light — it leaves
                        // partial DefaultAudioSink calibration state that
                        // accumulates A/V drift across multiple REWs.
                        //
                        // The cascade-of-rebuilds that used to break this
                        // is prevented two ways:
                        //   1. runWhenPrebuffered gates on ~64 KB arriving
                        //      so sniff doesn't fail.
                        //   2. Pending gates are cancelled before the next
                        //      one is scheduled, so a burst of 5 flushes
                        //      coalesces into a single rebuild.
                        final com.google.android.exoplayer2.source.MediaSource finalSrc = mediaSource;
                        // Mark rebuild armed immediately so any malformed
                        // error from the previous prepare's Loader (cancelled
                        // by the ring clear in super.flush()) is suppressed.
                        // Cleared in STATE_READY when the new prepare lands.
                        pushRebuildInProgress = true;
                        runWhenPrebuffered(new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                if (player == null) return;
                                player.setMediaSource(finalSrc, true);
                                player.prepare();
                                if (VerboseLogging.DETAILED_PLAYER_LOGGING)
                                    log.logDebug("Push flush: setMediaSource+prepare after prebuffer");
                            }
                        });
                    }
                    else
                    {
                        // Pull/HTTPLS mode: the subsequent MEDIACMD_SEEK will reposition.
                        // Don't destroy the pipeline — just let ExoPlayer handle it via seekTo().
                        // Clearing flushed flag here since no pushData() call will do it.
                        flushed = false;
                        if (VerboseLogging.DETAILED_PLAYER_LOGGING)
                            log.logDebug("Pull flush: no-op, will seek next");
                    }

                    Exo2MediaPlayerImpl.this.currentPlaybackPosition = player.getCurrentPosition();
                }
                catch (Exception ex)
                {
                    log.logError("Error during flush", ex);
                }
            }
        });
    }

    /** Pending prebuffer gate, cancelled when a new flush arrives. */
    private Runnable pendingPrebufferGate;

    /**
     * True from the moment a push-mode flush arms a rebuild until the
     * next STATE_READY confirms the new prepare has loaded. Any
     * ERROR_CODE_PARSING_CONTAINER_MALFORMED that fires while this is
     * true is treated as a transient race with the rebuild and
     * suppressed.
     */
    private volatile boolean pushRebuildInProgress;

    /**
     * Schedule {@code action} to run on the UI thread once the native
     * transport ring buffer holds at least 64 KB of data, or after a 3 s
     * safety timeout (whichever comes first). Used to gate
     * {@code prepare()} on initial open and the rebuild on push-mode
     * flush, mirroring the placeshifter's {@code pushDataLeftBeforeInit}
     * behavior.
     *
     * <p>Calls coalesce: scheduling a new gate cancels any pending one,
     * so a burst of 5 flushes within ~500 ms results in exactly one
     * rebuild instead of 5 (which would race each other's sniffs).
     */
    private void runWhenPrebuffered(final Runnable action)
    {
        final long startedAt = System.currentTimeMillis();
        final int PREBUFFER_THRESHOLD = 64 * 1024;
        final int POLL_INTERVAL_MS = 25;
        final int TIMEOUT_MS = 3_000;
        final Runnable[] gate = new Runnable[1];
        gate[0] = new Runnable()
        {
            @Override
            public void run()
            {
                if (player == null) return; // released
                if (pendingPrebufferGate != gate[0]) return; // superseded
                int filled = (trickplayController != null && trickplayController.isNativeAvailable())
                        ? trickplayController.bufferFilledBytes() : PREBUFFER_THRESHOLD;
                long elapsed = System.currentTimeMillis() - startedAt;
                if (filled >= PREBUFFER_THRESHOLD || elapsed >= TIMEOUT_MS)
                {
                    if (VerboseLogging.DETAILED_PLAYER_LOGGING)
                        log.logDebug("Prebuffer gate fired: filled=" + filled
                                + " elapsed=" + elapsed + "ms");
                    pendingPrebufferGate = null;
                    action.run();
                }
                else
                {
                    if (handler == null) handler = new Handler();
                    handler.postDelayed(gate[0], POLL_INTERVAL_MS);
                }
            }
        };
        context.runOnUiThread(new Runnable()
        {
            @Override public void run()
            {
                if (handler == null) handler = new Handler();
                if (pendingPrebufferGate != null)
                {
                    handler.removeCallbacks(pendingPrebufferGate);
                }
                pendingPrebufferGate = gate[0];
                handler.post(gate[0]);
            }
        });
    }

    @Override
    protected void setupPlayer(String sageTVurl)
    {
        initialAudioTrackIndex = -1;

        if (player != null)
        {
            releasePlayer();
        }

        this.url = sageTVurl;

        // VerboseLogUtil.setEnableAllTags(true);

        //if (VerboseLogging.DETAILED_PLAYER_LOGGING)
        log.logDebug("Setting up the Exo2 media player for: " + sageTVurl);

        if (pushMode)
        {
            if (trickplayController != null && trickplayController.isNativeAvailable())
            {
                log.logDebug("Creating TransportDataSource (native ring buffer)");
                trickplayController.open(true);
                TransportDataSource transportDs = new TransportDataSource(trickplayController);
                dataSource = (DataSource) transportDs;

                trickplayController.setSeekCallback(new TrickplayController.PlayerSeekCallback()
                {
                    @Override
                    public void onSeekTo(long timeMs)
                    {
                        seekToImpl(timeMs);
                    }
                });
            }
            else
            {
                log.logDebug("Creating Exo2PushDataSource datasource");
                dataSource = new Exo2PushDataSource();
            }
        }
        else
        {
            if (!httpls)
            {
                log.logDebug("Creating datasource, timeshifted=" + timeshifted);
                dataSource = new Exo2PullDataSource(context.getClient().getConnectedServerInfo().address, timeshifted);

                // Open trickplay controller for pull mode time truth
                if (trickplayController != null && trickplayController.isNativeAvailable())
                {
                    if (VerboseLogging.DETAILED_PLAYER_LOGGING)
                        log.logDebug("Opening trickplay controller for PULL mode");
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
            else
            {
                log.logDebug("Creating null datasource");
                dataSource = null;
            }
        }

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context.getContext()) {
            @Override
            protected AudioSink buildAudioSink(android.content.Context ctx, boolean enableFloatOutput, boolean enableAudioTrackPlaybackParams, boolean enableOffload) {
                // ExoPlayer 2.19.0+ requires Context for DefaultAudioSink so it can
                // register an AudioCapabilitiesReceiver and react to HDMI plug events.
                return new DefaultAudioSink.Builder(ctx)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setOffloadMode(enableOffload ? DefaultAudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_REQUIRED : DefaultAudioSink.OFFLOAD_MODE_DISABLED)
                        .build();
            }
        };

        if (FfmpegLibrary.isAvailable())
        {
            final int preferExtensionDecoders = ((sagex.miniclient.android.prefs.AndroidPrefStore)
                    MiniclientApplication.get().getClient().properties()).getExoFfmpegExtensionMode();

            switch (preferExtensionDecoders)
            {
                case DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER:
                    log.logDebug("Setting FFmpeg Extension to Prefer");
                    renderersFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
                    break;
                case DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON:
                    log.logDebug("Setting FFmpeg Extension to On");
                    renderersFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
                    break;
                case DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF:
                    log.logDebug("Setting FFmpeg Extension to Off");
                    renderersFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);
                    break;
                default:
                    log.logDebug("Defaulting FFmpeg Extension to On");
                    renderersFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
            }
        }

        CustomMediaCodecSelector mediaCodecSelector = new CustomMediaCodecSelector();
        renderersFactory.setMediaCodecSelector(mediaCodecSelector);

        trackSelector = new DefaultTrackSelector(context.getContext());

        ExoPlayer.Builder builder = new ExoPlayer.Builder(context.getContext(), renderersFactory);

        // Tune buffer sizes for streams over slow/variable connections (e.g. Tailscale).
        // Keep default min/max buffer, but reduce rebuffer threshold so playback
        // resumes quickly after a stall rather than waiting to refill a large buffer.
        builder.setLoadControl(
                new com.google.android.exoplayer2.DefaultLoadControl.Builder()
                        .setBufferDurationsMs(
                                /* minBufferMs= */ 15_000,
                                /* maxBufferMs= */ 50_000,
                                /* bufferForPlaybackMs= */ 2_500,
                                /* bufferForPlaybackAfterRebufferMs= */ 2_500)
                        .build());

        builder.setTrackSelector(trackSelector);
        player = builder.build();
        //player.addAnalyticsListener(new EventLogger(trackSelector));

        player.addListener(new Player.Listener()
        {
            @Override
            public void onPlayerError(PlaybackException error)
            {
                log.logDebug("PLAYER ERROR: " + error.getErrorCodeName());
                error.printStackTrace();

                // AUTO-mode legacy-server detection: ERROR_CODE_IO_UNSPECIFIED
                // on first OPENURL is the canonical signal that a SageTV 9.2.x
                // server's profile resolver couldn't match our NG capability
                // advertisement. Flip the connected ServerInfo to LEGACY and
                // persist; a one-shot toast tells the user to reconnect.
                // Only fires once per playback session (retryCount == 0) so
                // we don't spam during the existing 12-retry loop.
                //
                // GUARD: do NOT flip if this URL is the known ExoPlayer-PsExtractor
                // landmine (MPEG-4 Part 2 in MPEG-PS). That's a *decoder* problem
                // already routed around by PlayerSelectionUtil at newPlayerPlugin
                // time on most code paths; if we still got here it means the user
                // is forcing ExoPlayer for this stream. Flipping AUTO→LEGACY would
                // tell the server "I can play MPEG-2 raw" which is wrong on devices
                // without an MPEG-2 hardware decoder (e.g. Galaxy Fold) and would
                // strand the user with silent video on every subsequent play.
                if (retryCount == 0
                        && error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                        && !sagex.miniclient.android.video.PlayerSelectionUtil.isExoPsMpeg4Landmine(url)
                        && context != null && context.getClient() != null
                        && context.getClient().getCurrentConnection() != null)
                {
                    try
                    {
                        boolean flipped = context.getClient().getCurrentConnection()
                                .notifyServerCompatibilityFailure();
                        if (flipped)
                        {
                            context.showErrorMessage(
                                    "Detected legacy SageTV server. Reconnect to enable compatibility mode.",
                                    "Exo2MediaPlayer");
                        }
                    }
                    catch (Throwable t)
                    {
                        log.logWarning("notifyServerCompatibilityFailure raised: " + t);
                    }
                }

                // Push-mode flush-rebuild race: super.flush() clears the
                // native ring while a previous prepare's Loader is still
                // sniffing → cancellation surfaces as MALFORMED_CONTAINER.
                // Suppress the toast ONLY when we know a flush rebuild is in
                // flight (gate scheduled or super.flush() just ran). Genuine
                // container errors (no decoder, broken file, etc.) will still
                // toast so we can see them. Retry path runs unconditionally.
                boolean transientFlushRace =
                        pushMode
                        && (pendingPrebufferGate != null || pushRebuildInProgress)
                        && error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED;

                if (transientFlushRace) {
                    // One-line breadcrumb. No stack trace — this fires often
                    // during FF mash and stack traces flood logcat enough to
                    // perceptibly slow the UI.
                    Throwable cause = error.getCause();
                    log.logWarning("Suppressed transient flush-race container error: "
                            + error.getErrorCodeName()
                            + " retryCount=" + retryCount
                            + " cause=" + (cause != null
                                    ? cause.getClass().getSimpleName() + ": " + cause.getMessage()
                                    : "none"));
                }

                if (retryCount == 0
                        && !transientFlushRace
                        && !(error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                        && pushMode))
                {
                    // For push-mode MPEG-PS startup, the first sniff frequently fails
                    // because the ring buffer hasn't filled yet when prepare() runs;
                    // the retry path recovers transparently. Suppress the toast in
                    // that transient case — only surface non-recoverable errors.
                    context.showErrorMessage(error.getErrorCodeName(), "Exo2MediaPlayer");
                }

                if (retryCount <= MAX_PLAYBACK_RETRY_COUNT)
                {

                    errorState = true;
                    retryCount++;

                    player.seekTo(player.getCurrentPosition() + 100);
                    player.prepare();
                }
                else
                {
                    log.logDebug("PLAYER ERROR: " + error.getErrorCodeName());
                    log.logError("Playback Exception: " + error.getErrorCodeName(), error);
                    context.showErrorMessage("Max playback retry reached!", "Exo2MediaPlayer");
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState)
            {
                if (playbackState == Player.STATE_ENDED)
                {
                    log.logDebug("Player.STATE_ENDED");
                    log.logDebug("Player Has Ended, set EOS");
                    //stop(); - JVL: Not sure if we will need to do this or not
                    //notifySageTVStop();
                    eos = true;
                    state = Exo2MediaPlayerImpl.EOS_STATE;
                }
                if (playbackState == Player.STATE_READY)
                {
                    log.logDebug("Player.STATE_READY - Media loaded and ready for playback");
                    pushRebuildInProgress = false;
                    if (errorState)
                    {
                        errorState = false;
                        retryCount = 0;
                    }

                    // Notify the trickplay controller so it can release any
                    // seek that was buffered while the player was preparing.
                    if (trickplayController != null && trickplayController.isNativeAvailable())
                    {
                        trickplayController.notifyPlayerReady();
                    }

                    // Apply any deferred initial-resume seek (pull mode only).
                    // Runtime seeks go through the trickplay controller's
                    // coalesce timer, but the very first resume after
                    // setMediaSource() needs the SeekMap to exist first.
                    if (!pushMode && playbackStartPosition >= 0)
                    {
                        long pending = playbackStartPosition;
                        playbackStartPosition = -1;
                        log.logDebug("STATE_READY: applying deferred initial resume seek to " + pending);
                        seekToImpl(pending);
                    }

                    log.logDebug("Player.STATE_READY - setAudioTrack getting called");
                    if (initialAudioTrackIndex != -1)
                    {
                        setAudioTrack(initialAudioTrackIndex);
                        initialAudioTrackIndex = -1;
                    }

                    log.logDebug("Player.STATE_READY - Debugging available tracks in file");
                    debugAvailableTracks();

                    long duration = 0;

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
                    if (mediaSession != null) mediaSession.setActive(true);
                    updateMediaSessionPlaybackState(Exo2MediaPlayerImpl.this.getPlaybackPosition());
                }
                if (playbackState == Player.STATE_IDLE)
                {
                    if (errorState)
                    {
                        log.logDebug("Player.STATE_IDLE - Error state is true, retry count: " + retryCount);
                    }
                }

            }

            @Override
            public void onTimelineChanged(Timeline timeline, int reason)
            {
                updateMediaSessionPlaybackState(Exo2MediaPlayerImpl.this.getPlaybackPosition());
                seekPending = false;
            }

            @Override
            public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason)
            {
                switch (reason)
                {
                    case Player.DISCONTINUITY_REASON_SEEK:
                        updateMediaSessionPlaybackState(Exo2MediaPlayerImpl.this.getPlaybackPosition());
                        seekPending = false;

                        if (trickplayController != null && trickplayController.isNativeAvailable())
                        {
                            trickplayController.notifySeekComplete();
                        }
                        break;

                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize)
            {
                int width = videoSize.width;
                int height = videoSize.height;
                float pixelWidthHeightRatio = videoSize.pixelWidthHeightRatio;

                if (VerboseLogging.DETAILED_PLAYER_LOGGING)
                {
                    log.logDebug("ExoPlayer.onVideoSizeChanged: " + width + "x" + height + ", pixel ratio: " + pixelWidthHeightRatio);
                }

                // note if pixel ratio is != 0 then calc the ar and apply it.
                if (pixelWidthHeightRatio != 0f)
                {
                    setVideoSize(width, height, pixelWidthHeightRatio * ((float) width / (float) height));
                }
                else
                {
                    setVideoSize(width, height, 0);
                }
            }
        });


        player.addListener(new Player.Listener()
        {
            @Override
            public void onCues(List<Cue> cues)
            {
                if (showCaptions && subView != null)

                    subView.setCues(cues);
            }
        });


        final String sageTVurlFinal = sageTVurl;
        if (!httpls)
        {

            com.google.android.exoplayer2.upstream.DataSource.Factory dataSourceFactory = new DataSource.Factory()
            {
                @Override
                public DataSource createDataSource()
                {
                    return dataSource;
                }
            };

            //mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(Uri.parse(sageTVurl));

            // Use SageExtractorsFactory which provides our patched PsExtractor
            // that properly demuxes MPEG-PS private_stream_1 sub-streams (AC3/EAC3).
            // For pull-mode playback, we also wire a per-instance live size
            // provider so LinearPsSeekMap can map FF/REW (e.g. comskip jumps)
            // to correct byte positions as a still-recording file grows.
            com.google.android.exoplayer2.extractor.ts.SagePsExtractor.LiveSizeProvider liveSizeProvider = null;
            if (dataSource instanceof Exo2PullDataSource) {
                final Exo2PullDataSource pullDs = (Exo2PullDataSource) dataSource;
                liveSizeProvider = pullDs::queryCurrentSize;
            }
            // In push mode the SageTV server always sends MPEG-PS, so register
            // only SagePsExtractor. This avoids spurious
            // ERROR_CODE_PARSING_CONTAINER_MALFORMED rebuilds when a post-flush
            // sniff against a partially-filled ring fails over to Mp3Extractor /
            // other extractors that then bail after scanning >1MB.
            mediaSource = new ProgressiveMediaSource.Factory(
                    dataSourceFactory,
                    new SageExtractorsFactory(liveSizeProvider, pushMode))
                    .createMediaSource(MediaItem.fromUri(Uri.parse(sageTVurl)));


            boolean haveStartPosition = (playbackStartPosition >= 0);

            if (VerboseLogging.DETAILED_PLAYER_LOGGING)
                log.logDebug("ExoLogging - Preparing playback, startPosition=" + playbackStartPosition);

            if (haveStartPosition && dataSource instanceof Exo2PullDataSource)
            {
                // For initial resume in pull mode, the time-based seek can't
                // be applied at setMediaSource() time because ExoPlayer's
                // SeekMap isn't established until enough of the stream has
                // been parsed. Leave playbackStartPosition set; the
                // STATE_READY handler will consume it and call seekTo().
                if (VerboseLogging.DETAILED_PLAYER_LOGGING)
                    log.logDebug("ExoLogging - Deferring initial resume seek to STATE_READY: " + playbackStartPosition);
                player.setMediaSource(mediaSource, true);
                player.prepare();
            }
            else if (haveStartPosition)
            {
                player.setMediaSource(mediaSource, playbackStartPosition);
                if (VerboseLogging.DETAILED_PLAYER_LOGGING)
                    log.logDebug("ExoLogging - setMediaSource with startPosition: " + playbackStartPosition);
                player.prepare();
            }
            else if (pushMode && trickplayController != null && trickplayController.isNativeAvailable())
            {
                // Push-mode prebuffer gate (placeshifter pattern).
                // Defer setMediaSource()+prepare() until ~64 KB has actually
                // arrived in the ring (matches placeshifter's
                // pushDataLeftBeforeInit). Without this the first sniff()
                // runs against a near-empty ring, fails with
                // UnrecognizedInputFormatException, and ExoPlayer's retry
                // path burns 1-2 seconds per attempt.
                final com.google.android.exoplayer2.source.MediaSource finalSrc = mediaSource;
                runWhenPrebuffered(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        if (player == null) return;
                        player.setMediaSource(finalSrc, true);
                        player.prepare();
                        if (VerboseLogging.DETAILED_PLAYER_LOGGING)
                            log.logDebug("Push setupPlayer: prepare() after prebuffer");
                    }
                });
            }
            else
            {
                player.setMediaSource(mediaSource, true);
                player.prepare();
            }

        }

        //Set seek preferences
        //player.setSeekParameters(SeekParameters.CLOSEST_SYNC);

        // start playing
        player.setVideoSurface(((SurfaceView) context.getVideoView()).getHolder().getSurface());
        player.setPlayWhenReady(true);

        //Create Media Session
        try {
            mediaSession = new MediaSessionCompat(this.context.getContext(), "SageTV Android TV Client");
            mediaSession.setCallback(new MediaSessionCallbackHandler(this, context.getClient(), context.getContext()));
            mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS | MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS);
        } catch (Exception e) {
            log.logError("Failed to create MediaSession", e);
            mediaSession = null;
        }

        this.playerReady = true;

        super.play();

        log.logDebug("Creating handler");
        handler = new Handler();


        context.runOnUiThread(progressRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                if (player != null)
                {
                    long pos = player.getContentPosition();
                    Exo2MediaPlayerImpl.this.setPlaybackPosition(pos);

                    if (trickplayController != null && trickplayController.isNativeAvailable())
                    {
                        trickplayController.onPlayerPosition(pos);
                    }

                    handler.postDelayed(progressRunnable, 500);
                }
                else
                {
                    Exo2MediaPlayerImpl.this.setPlaybackPosition(0);
                }
            }
        });

        handler.postDelayed(progressRunnable, 0);
    }

    public void changeTrack(int trackType, int groupIndex, int trackIndex)
    {
        context.runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                TrackSelectionOverride override;
                DefaultTrackSelector.Parameters.Builder parametersBuilder;

                if (trackSelector == null)
                {
                    log.logTrace("Track Selector Null");
                    return;
                }

                MappingTrackSelector.MappedTrackInfo trackInfo = trackSelector.getCurrentMappedTrackInfo();

                if (trackInfo == null)
                {
                    log.logTrace("Track info null");
                    return;
                }

                try
                {
                    TrackGroupArray trackGroup = trackInfo.getTrackGroups(trackType);


                    if (groupIndex == Exo2MediaPlayerImpl.DISABLE_TRACK) //Disable trackType from rendering
                    {
                        parametersBuilder = trackSelector.buildUponParameters();
                        parametersBuilder.setRendererDisabled(trackType, true);
                        parametersBuilder.setTrackTypeDisabled(trackType, true);

                        trackSelector.setParameters(parametersBuilder.build());

                        log.logDebug("JVL - Track change executed for disable: TrackType=" + trackType + " TrackGroup=" + trackGroup + " TrackIndex=" + trackIndex);
                        selectedSubtitleTrack = DISABLE_TRACK;
                    }
                    else
                    {
                        if (trackInfo.getTrackSupport(trackType, groupIndex, trackIndex) == RendererCapabilities.FORMAT_HANDLED)
                        {
                            //Clear set new track selection
                            parametersBuilder = trackSelector.buildUponParameters();

                            //override = new DefaultTrackSelector.SelectionOverride(groupIndex, trackIndex);
                            override = new TrackSelectionOverride(trackGroup.get(groupIndex), trackIndex);

                            parametersBuilder.setTrackTypeDisabled(trackType, false);

                            //parametersBuilder.setSelectionOverride(trackType, trackGroup, override);
                            parametersBuilder.addOverride(override);


                            trackSelector.setParameters(parametersBuilder.build());
                            log.logDebug("JVL - Track change executed: TrackType=" + trackType + " TrackGroup=" + trackGroup + " TrackIndex=" + trackIndex);
                            selectedSubtitleTrack = groupIndex;
                        }
                        else
                        {
                            log.logDebug("Unable to render the track, TrackType= " + trackType + ", GroupIndex= " + groupIndex + ", TrackIndex= " + trackIndex);
                        }
                    }
                }
                catch (Exception ex)
                {
                    log.logError("Error render the track, TrackType= " + trackType + ", GroupIndex= " + groupIndex + ", TrackIndex= " + trackIndex, ex);
                    ex.printStackTrace();
                }
            }
        });
    }

    /**
     * Counts support tracks of the given track type
     *
     * @param RenderType The type of track (VIDEO, AUDIO, TEXT, ect...)
     */
    public int getTrackCount(int RenderType)
    {
        MappingTrackSelector.MappedTrackInfo mappedTrackInfo = trackSelector.getCurrentMappedTrackInfo();
        int count = 0;

        if (mappedTrackInfo == null)
        {
            log.logWarning("No Mapped Track Info found");
            return count;
        }

        TrackGroupArray trackGroups = mappedTrackInfo.getTrackGroups(RenderType);

        if (trackGroups.length != 0)
        {
            //This code is assuming one track to a group.  It will increment the count by one
            //if there is a supported track in the groun
            for (int j = 0; j < trackGroups.length; j++)
            {
                boolean supported = false;

                for (int k = 0; k < trackGroups.get(j).length; k++)
                {
                    if (mappedTrackInfo.getTrackSupport(RenderType, j, k) == C.FORMAT_HANDLED)
                    {
                        log.logDebug("Format is handled");
                        supported = true;
                    }
                    else
                    {
                        log.logDebug("Format IS NOT HANDLED");
                    }
                }

                if (supported)
                {
                    count++;
                }
            }
        }

        return count;
    }

    @Override
    public SubtitleTrack[] getSubtitleTracks()
    {
        MappingTrackSelector.MappedTrackInfo mappedTrackInfo = trackSelector.getCurrentMappedTrackInfo();
        TrackGroupArray trackGroups = mappedTrackInfo.getTrackGroups(C.TRACK_TYPE_TEXT);
        int trackCount = trackGroups.length;
        SubtitleTrack[] tracks = new SubtitleTrack[0];

        if (trackCount > 0)
        {
            tracks = new SubtitleTrack[trackCount];

            for (int i = 0; i < trackCount; i++)
            {
                TrackGroup trackGroup = trackGroups.get(i);

                SubtitleCodec codec = SubtitleCodec.parse(trackGroup.getFormat(0).sampleMimeType);
                String langugae = trackGroup.getFormat(0).language;
                String label = trackGroup.getFormat(0).label;
                boolean supported = (mappedTrackInfo.getTrackSupport(C.TRACK_TYPE_TEXT, i, 0) == C.FORMAT_HANDLED);

                SubtitleTrack track = new SubtitleTrack(i, codec, langugae, label, supported);
                tracks[i] = track;
            }
        }

        return tracks;
    }

    public void debugAvailableTracks()
    {
        MappingTrackSelector.MappedTrackInfo mappedTrackInfo = trackSelector.getCurrentMappedTrackInfo();

        if (mappedTrackInfo == null)
        {
            log.logWarning("No Mapped Track Info");
            return;
        }

        for (int i = 0; i < mappedTrackInfo.getRendererCount(); i++)
        {
            TrackGroupArray trackGroups = mappedTrackInfo.getTrackGroups(i);

            log.logDebug("Track Render Group " + i);

            if (trackGroups.length != 0)
            {
                int label;

                switch (player.getRendererType(i))
                {
                    case C.TRACK_TYPE_AUDIO:

                        log.logDebug("TRACK_TYPE_AUDIO");
                        break;

                    case C.TRACK_TYPE_VIDEO:

                        log.logDebug("TRACK_TYPE_VIDEO");
                        break;

                    case C.TRACK_TYPE_TEXT:

                        log.logDebug("TRACK_TYPE_TEXT");
                        break;

                    default:
                        continue;
                }

                for (int j = 0; j < trackGroups.length; j++)
                {
                    log.logDebug("\t Track Group " + j);

                    for (int k = 0; k < trackGroups.get(j).length; k++)
                    {
                        Format format = trackGroups.get(j).getFormat(k);

                        log.logDebug("\t\tTrack : " + k);
                        log.logDebug("\t\tContainer MimeType: " + format.containerMimeType);
                        log.logDebug("\t\tSample MimeType: " + format.sampleMimeType);
                        log.logDebug("\t\tCodecs: " + format.codecs);
                        log.logDebug("\t\tLanguage: " + format.language);


                        if (player.getRendererType(i) == C.TRACK_TYPE_TEXT)
                        {

                            /*if((MimeTypes.APPLICATION_CEA708.equalsIgnoreCase(format.sampleMimeType) || MimeTypes.APPLICATION_CEA608.equalsIgnoreCase(format.sampleMimeType))
                                    && format.language.equalsIgnoreCase("en"))
                            {
                                log.debug("-----Setting Subtitle track to active");
                                //Enable this track.  This is just debugging
                                this.setSubtitleTrack(j);
                            }
                            else
                            {
                                log.debug("-----NOT Setting Subtitle track to active");
                            }*/
                        }


                        if (player.getRendererType(i) == C.TRACK_TYPE_AUDIO)
                        {
                            log.logDebug("\t\tChannel: " + format.channelCount);
                            log.logDebug("\t\tBitrate: " + format.bitrate);
                            log.logDebug("\t\tAverageBitrate: " + format.averageBitrate);
                            log.logDebug("\t\tPeakBitrate: " + format.peakBitrate);
                            log.logDebug("\t\tPCM Encoding: " + format.pcmEncoding);

                        }

                        if (player.getRendererType(i) == C.TRACK_TYPE_VIDEO)
                        {
                            if (format.colorInfo != null)
                            {
                                log.logDebug("\t\tColor: " + format.colorInfo.toString());
                            }
                            log.logDebug("\t\tBitrate: " + format.bitrate);
                            log.logDebug("\t\tAverageBitrate: " + format.averageBitrate);
                            log.logDebug("\t\tPeakBitrate: " + format.peakBitrate);
                            log.logDebug("\t\tFramerate: " + format.frameRate);
                            log.logDebug("\t\tHeight: " + format.height);
                            log.logDebug("\t\tWidth: " + format.width);

                        }

                        log.logDebug("\t\tID: " + format.id);
                        log.logDebug("\t\tLabel: " + format.label);
                        if (format.metadata != null)
                        {
                            log.logDebug("\t\t\tMetadata length: " + format.metadata.length());
                        }


                        if (mappedTrackInfo.getTrackSupport(i, j, k) == RendererCapabilities.FORMAT_HANDLED)
                        {
                            //Add debug info
                            log.logDebug("\t\t Format is handled");
                        }
                        else
                        {
                            //Add debug info
                            log.logDebug("\t\t Format IS NOT HANDLED");
                        }
                    }

                }

            }
            else
            {
                log.logDebug("Track Group Empty");
            }
        }
    }

    // cncb - Add and remove ExoPlayer2 SubTitleView for embedded PGS subtitles
    private void AddSubTitleView()
    {
        if (subView == null)
        {
            subView = new SubtitleView(context.getContext());
            subView.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
            context.runOnUiThread(new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        FrameLayout layout = (FrameLayout) context.getVideoView().getParent();
                        layout.addView(subView);
                    }
                    catch (Exception ex)
                    {
                        log.logError("Error adding SubTitleView: ", ex);
                    }
                }
            });
        }
    }

    private void RemoveSubTitleView()
    {
        if (subView != null)
        {
            context.runOnUiThread(new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        FrameLayout layout = (FrameLayout) context.getVideoView().getParent();
                        layout.removeView(subView);
                    }
                    catch (Exception ex)
                    {
                        log.logError("Error removing SubTitleView ",  ex);
                    }
                    finally
                    {
                        subView = null;
                    }
                }
            });
        }
    }

    private void updateMediaSessionPlaybackState(long playbackPostion)
    {
        if(mediaSession != null && mediaSession.isActive()) {
            PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder();
            stateBuilder.setActions(this.getMediaSessionActions());

            if (player != null && getState() == PLAY_STATE) {
                stateBuilder.setState(PlaybackStateCompat.STATE_PLAYING, playbackPostion, 1.0f);
            } else {
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
        if (mediaSession == null) return;
        MediaMetadataCompat.Builder metaDataBuilder = new MediaMetadataCompat.Builder();

        metaDataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, displayTitle);
        metaDataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration);
        mediaSession.setMetadata(metaDataBuilder.build());
    }
}
