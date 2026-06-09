/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sagex.miniclient.android.offline;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.app.Fragment;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.widget.TextView;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ForwardingPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import com.google.android.exoplayer2.upstream.DefaultDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sagex.miniclient.android.offline.R;
import sagex.miniclient.android.media.CodecCapabilityDetector;
import sagex.miniclient.android.video.OrientationController;
import sagex.miniclient.media.VideoCodec;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import tv.danmaku.ijk.media.player.misc.IMediaDataSource;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen ExoPlayer-based playback for locally downloaded media files.
 * Uses the standard ExoPlayer pipeline with DefaultDataSource (file:// or
 * content:// URIs for SAF), FFmpeg audio extension when available, and the
 * built-in StyledPlayerView controller for play/pause/seek.
 *
 * Visual style matches the SageTV placeshifter video playback overlay:
 * black background, title bar at top, transport controls at bottom.
 */
public class OfflinePlaybackActivity extends Activity {
    private static final Logger log = LoggerFactory.getLogger(OfflinePlaybackActivity.class);
    private static OfflinePlaybackActivity currentInstance;

    public static final String EXTRA_MEDIA_URI = "media_uri";
    public static final String EXTRA_MEDIA_TITLE = "media_title";
    public static final String EXTRA_MEDIA_FILE_ID = "media_file_id";
    public static final String EXTRA_START_POSITION_MS = "start_position_ms";

    private static final long AUTOSKIP_POLL_MS = 500L;
    private static final long AUTOSKIP_EXIT_PADDING_MS = 250L;
    /**
     * After we auto-skip a commercial band, ignore re-entry into the same
     * band (identified by its EDL startMs) for this many milliseconds.
     * This prevents the "stuck skipping commercial over and over" symptom
     * when the player's reported position lags behind the requested seek
     * target and the next poll still sees us inside the band.
     */
    private static final long AUTOSKIP_COOLDOWN_MS = 10_000L;
    private static final long TIMEBAR_VISIBLE_MS = 5000L;
    private static final long TIMEBAR_UPDATE_MS = 250L;
    private static final long RESUME_THRESHOLD_MS = 3000L;
    private static final long POSITION_SAVE_INTERVAL_MS = 5000L;

    private ExoPlayer player;
    private IjkMediaPlayer ijkPlayer;
    private ParcelFileDescriptor ijkPfd;
    private Uri mediaUri;
    private StyledPlayerView playerView;
    private SurfaceView ijkSurfaceView;
    private View titleBar;
    private final Handler hideHandler = new Handler(Looper.getMainLooper());
    private final Handler autoSkipHandler = new Handler(Looper.getMainLooper());
    private final Handler timeBarHandler = new Handler(Looper.getMainLooper());
    private final Handler positionSaveHandler = new Handler(Looper.getMainLooper());
    private OfflineTimeBarView offlineTimeBar;
    private DownloadMetadata meta;
    private List<CommercialSegment> commercialSegments = Collections.emptyList();
    /**
     * EDL startMs of the most recently auto-skipped segment, paired with
     * the SystemClock.uptimeMillis() timestamp of when the skip fired.
     * Used by {@link #maybeAutoSkipCommercial(long)} to suppress redundant
     * skips while the player is still catching up to the seek target.
     */
    private long lastAutoSkippedSegmentStartMs = -1L;
    private long lastAutoSkippedAtUptimeMs = -1L;
    private long requestedStartPositionMs;
    private long lastSavedPositionMs = -1L;
    private boolean usingIjkPlayer;
    private boolean ijkPrepared;
    private int ijkVideoWidth;
    private int ijkVideoHeight;

    /**
     * Display-sizing options surfaced to the user via the aspect button on
     * the soft-remote overlay. The same enum drives both the Exo and IJK
     * code paths so the cycle order and labels stay consistent regardless
     * of which engine is active.
     */
    private enum ResizeMode {
        /**
         * Display at the video's native pixel dimensions / native aspect
         * ratio, centered, leaving black bars when the video is smaller
         * than the surface. Default: matches the SageTV placeshifter
         * behaviour and avoids the stretch the device default produced.
         */
        NATIVE,
        /** Stretch to fill the entire surface, ignoring source aspect. */
        FIT,
        /** Crop: preserve aspect ratio, fill the screen by overflowing. */
        ZOOM
    }

    private ResizeMode resizeMode = ResizeMode.NATIVE;

    private final Runnable savePositionRunnable = new Runnable() {
        @Override
        public void run() {
            persistPlaybackPosition(false);
            positionSaveHandler.postDelayed(this, POSITION_SAVE_INTERVAL_MS);
        }
    };

    private final Runnable hideTimeBarRunnable = new Runnable() {
        @Override
        public void run() {
            if (offlineTimeBar != null) {
                offlineTimeBar.setVisibility(View.GONE);
            }
            timeBarHandler.removeCallbacks(updateTimeBarRunnable);
        }
    };

    private final Runnable updateTimeBarRunnable = new Runnable() {
        @Override
        public void run() {
            updateOfflineTimeBar();
            if (offlineTimeBar != null && offlineTimeBar.getVisibility() == View.VISIBLE) {
                timeBarHandler.postDelayed(this, TIMEBAR_UPDATE_MS);
            }
        }
    };

    public enum LocalAction {
        TOGGLE_CONTROLS,
        TOGGLE_PLAYBACK,
        PLAY,
        PAUSE,
        STOP,
        FAST_FORWARD,
        REWIND,
        SKIP_FORWARD,
        SHOW_INFO,
        ROTATE,
        FIT_TO_SCREEN,
        NATIVE_ASPECT,
        CYCLE_RESIZE
    }

    public static boolean dispatchLocalAction(LocalAction action) {
        OfflinePlaybackActivity activity = currentInstance;
        if (activity == null) {
            return false;
        }
        activity.handleLocalAction(action);
        return true;
    }

    public static boolean isActive() {
        return currentInstance != null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        currentInstance = this;
        if (overlay != null) overlay.onResume();
    }

    private OfflineNavigationOverlay overlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Full screen immersive
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        setContentView(R.layout.activity_offline_playback);

        playerView = findViewById(R.id.offline_player_view);
        ijkSurfaceView = findViewById(R.id.offline_ijk_surface);
        titleBar = findViewById(R.id.offline_playback_title_bar);
        installOfflineTimeBar();

        bindControls();

        String title = getIntent().getStringExtra(EXTRA_MEDIA_TITLE);
        if (title != null) {
            ((TextView) findViewById(R.id.offline_playback_title)).setText(title);
        }

        String mediaFileId = getIntent().getStringExtra(EXTRA_MEDIA_FILE_ID);
        if (mediaFileId != null && !mediaFileId.isEmpty()) {
            meta = DownloadManager.getInstance(this).getRepository().getByMediaFileID(mediaFileId);
        }
        requestedStartPositionMs = Math.max(0L, getIntent().getLongExtra(EXTRA_START_POSITION_MS, 0L));

        String uriString = getIntent().getStringExtra(EXTRA_MEDIA_URI);
        if (uriString == null) {
            log.error("No media URI provided");
            finish();
            return;
        }

        Uri parsedUri = Uri.parse(uriString);
        loadCommercialSegments();
        if (shouldUseIjkPlayback(parsedUri)) {
            initializeIjkPlayer(parsedUri);
        } else {
            initializePlayer(parsedUri);
        }

        overlay = new OfflineNavigationOverlay(this);
        overlay.setShowPlayerRow(true);
        overlay.setTimebarView(offlineTimeBar);
        overlay.install(null);
    }

    private void initializePlayer(Uri mediaUri) {
        usingIjkPlayer = false;
        this.mediaUri = mediaUri;
        if (playerView != null) playerView.setVisibility(View.VISIBLE);
        if (ijkSurfaceView != null) ijkSurfaceView.setVisibility(View.GONE);

        // For SAF content:// URIs we must NOT use the default ContentDataSource:
        // it relies on AssetFileDescriptor.getDeclaredLength(), which is
        // UNKNOWN_LENGTH for DocumentsProvider URIs, leaving the TS extractor
        // unable to run its PCR-based duration scan and hence unable to seek.
        // SafContentDataSource uses ParcelFileDescriptor.getStatSize() to
        // report the real file length and Os.lseek to honour DataSpec.position.
        com.google.android.exoplayer2.upstream.DataSource.Factory dataSourceFactory;
        if (mediaUri != null && "content".equals(mediaUri.getScheme())) {
            dataSourceFactory = new SafContentDataSource.Factory(this);
        } else {
            dataSourceFactory = new DefaultDataSource.Factory(this);
        }

        // Belt-and-suspenders fallback for progressive containers that don't
        // expose a seek index (some recordings).
        DefaultExtractorsFactory extractorsFactory = new DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setConstantBitrateSeekingAlwaysEnabled(true);

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(
                        new DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory))
                .build();

        playerView.setPlayer(seekInterceptingPlayer(player));

        MediaItem mediaItem = MediaItem.fromUri(mediaUri);
        if (requestedStartPositionMs >= RESUME_THRESHOLD_MS) {
            player.setMediaItem(mediaItem, requestedStartPositionMs);
        } else {
            player.setMediaItem(mediaItem);
        }
        player.setPlayWhenReady(true);
        player.prepare();
        positionSaveHandler.postDelayed(savePositionRunnable, POSITION_SAVE_INTERVAL_MS);

        // Auto-hide title bar after 5 seconds
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    log.info("offline_player_ready dur={} seekable={}",
                            player.getDuration(),
                            player.isCurrentMediaItemSeekable());
                    startAutoSkipIfEnabled();
                    scheduleTitleHide();
                    showOfflineTimeBarBriefly();
                } else if (playbackState == Player.STATE_ENDED) {
                    stopAutoSkip();
                    finish();
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    showOfflineTimeBarBriefly();
                }
            }

            @Override
            public void onPositionDiscontinuity(
                    Player.PositionInfo oldPos, Player.PositionInfo newPos, int reason) {
                log.info("offline_player_discontinuity old={} new={} reason={}",
                        oldPos.positionMs, newPos.positionMs, reason);
                showOfflineTimeBarBriefly();
            }
        });

        log.info("Offline playback started: {}", mediaUri);
    }

    private void initializeIjkPlayer(Uri mediaUri) {
        usingIjkPlayer = true;
        this.mediaUri = mediaUri;
        if (playerView != null) {
            playerView.setPlayer(null);
            playerView.setVisibility(View.GONE);
        }
        if (ijkSurfaceView == null) {
            log.warn("offline_ijk_unavailable reason=no_surface");
            initializePlayer(mediaUri);
            return;
        }
        ijkSurfaceView.setVisibility(View.VISIBLE);
        SurfaceHolder holder = ijkSurfaceView.getHolder();
        if (holder.getSurface() != null && holder.getSurface().isValid()) {
            startIjkPlayer(mediaUri, holder);
        } else {
            holder.addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder surfaceHolder) {
                    startIjkPlayer(mediaUri, surfaceHolder);
                }
                @Override public void surfaceChanged(SurfaceHolder surfaceHolder, int format, int width, int height) { }
                @Override public void surfaceDestroyed(SurfaceHolder surfaceHolder) { }
            });
        }
    }

    private void startIjkPlayer(Uri mediaUri, SurfaceHolder holder) {
        if (!usingIjkPlayer || ijkPlayer != null) {
            return;
        }
        IjkPlaybackSource source = openIjkSource(mediaUri);
        if (source == null) {
            log.warn("offline_ijk_unsupported_uri uri={}", mediaUri);
            usingIjkPlayer = false;
            if (ijkSurfaceView != null) ijkSurfaceView.setVisibility(View.GONE);
            initializePlayer(mediaUri);
            return;
        }
        try {
            ijkPlayer = new IjkMediaPlayer();
            IjkMediaPlayer.native_setLogLevel(IjkMediaPlayer.IJK_LOG_ERROR);
            ijkPlayer.setDisplay(holder);
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-avc", 1);
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", 1);
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-mpeg2", 0);
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-all-videos", 0);
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1);
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1);
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 15 * 1024 * 1024);
            ijkPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 0);

            ijkPlayer.setOnPreparedListener(mp -> {
                ijkPrepared = true;
                log.info("offline_ijk_ready dur={}", mp.getDuration());
                if (requestedStartPositionMs >= RESUME_THRESHOLD_MS) {
                    mp.seekTo(requestedStartPositionMs);
                }
                mp.start();
                startAutoSkipIfEnabled();
                scheduleTitleHide();
                showOfflineTimeBarBriefly();
                positionSaveHandler.postDelayed(savePositionRunnable, POSITION_SAVE_INTERVAL_MS);
            });
            ijkPlayer.setOnCompletionListener(mp -> {
                stopAutoSkip();
                finish();
            });
            ijkPlayer.setOnErrorListener((mp, what, extra) -> {
                log.error("offline_ijk_error what={} extra={}", what, extra);
                Toast.makeText(this, "Offline playback failed", Toast.LENGTH_SHORT).show();
                return true;
            });
            ijkPlayer.setOnInfoListener((mp, what, extra) -> {
                if (what == IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    log.info("offline_ijk_video_rendering_start pos={}", mp.getCurrentPosition());
                    showOfflineTimeBarBriefly();
                }
                return false;
            });
            ijkPlayer.setOnVideoSizeChangedListener((mp, width, height, sarNum, sarDen) -> {
                log.info("offline_ijk_video_size width={} height={} sar={}:{}", width, height, sarNum, sarDen);
                int effectiveWidth = width;
                if (sarNum > 0 && sarDen > 0 && sarNum != sarDen) {
                    effectiveWidth = Math.round(width * ((float) sarNum / sarDen));
                }
                ijkVideoWidth = effectiveWidth;
                ijkVideoHeight = height;
                runOnUiThread(() -> applyIjkResizeMode(resizeMode));
            });
            if (source.dataSource != null) {
                ijkPlayer.setDataSource(source.dataSource);
                log.info("offline_ijk_started uri={} dataSource=imds size={}",
                        mediaUri, source.sizeForLog);
            } else {
                ijkPlayer.setDataSource(source.path);
                log.info("offline_ijk_started uri={} dataSource={}", mediaUri, source.path);
            }
            ijkPlayer.prepareAsync();
        } catch (Exception e) {
            log.warn("offline_ijk_start_failed uri={} err={}", mediaUri, e.toString());
            releaseIjkPlayer();
            usingIjkPlayer = false;
            if (ijkSurfaceView != null) ijkSurfaceView.setVisibility(View.GONE);
            initializePlayer(mediaUri);
        }
    }

    /**
     * Holder used by {@link #openIjkSource(Uri)} so the caller can choose
     * between a plain file path (preferred for {@code file://}) and an
     * {@link IMediaDataSource} adapter (required for SAF {@code content://}
     * URIs because Android scoped storage refuses to {@code open()} the
     * {@code /proc/self/fd/N} reflection from a different process / namespace).
     */
    private static final class IjkPlaybackSource {
        final String path;
        final IMediaDataSource dataSource;
        final long sizeForLog;
        IjkPlaybackSource(String path, IMediaDataSource dataSource, long sizeForLog) {
            this.path = path;
            this.dataSource = dataSource;
            this.sizeForLog = sizeForLog;
        }
    }

    private IjkPlaybackSource openIjkSource(Uri uri) {
        if (uri == null) return null;
        String scheme = uri.getScheme();
        if (scheme == null || "file".equals(scheme)) {
            String path = uri.getPath();
            if (path == null || path.isEmpty()) return null;
            return new IjkPlaybackSource(path, null, new File(path).length());
        }
        if ("content".equals(scheme)) {
            try {
                closeIjkPfdQuietly();
                ijkPfd = getContentResolver().openFileDescriptor(uri, "r");
                if (ijkPfd == null) return null;
                long size = ijkPfd.getStatSize();
                IMediaDataSource ds = new PfdMediaDataSource(ijkPfd, size);
                return new IjkPlaybackSource(null, ds, size);
            } catch (Throwable t) {
                log.warn("offline_ijk_open_pfd_failed uri={} err={}", uri, t.toString());
                closeIjkPfdQuietly();
                return null;
            }
        }
        return new IjkPlaybackSource(uri.toString(), null, -1L);
    }

    private void closeIjkPfdQuietly() {
        if (ijkPfd != null) {
            try { ijkPfd.close(); } catch (Throwable ignored) { }
            ijkPfd = null;
        }
    }

    /**
     * {@link IMediaDataSource} adapter that reads from a SAF
     * {@link ParcelFileDescriptor} via {@link FileChannel#read(ByteBuffer,long)}.
     * Positional reads avoid the shared file-pointer race that {@code pread64}
     * would otherwise solve, and the channel can survive across IJK seeks.
     * The wrapped {@link ParcelFileDescriptor} is owned by the activity and
     * closed in {@link #releaseIjkPlayer()}; {@link #close()} on this adapter
     * only releases the channel.
     */
    private static final class PfdMediaDataSource implements IMediaDataSource {
        private final FileInputStream stream;
        private final FileChannel channel;
        private final long size;

        PfdMediaDataSource(ParcelFileDescriptor pfd, long size) {
            this.stream = new FileInputStream(pfd.getFileDescriptor());
            this.channel = stream.getChannel();
            this.size = size;
        }

        @Override
        public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
            if (size <= 0) return 0;
            ByteBuffer bb = ByteBuffer.wrap(buffer, offset, size);
            int total = 0;
            while (bb.hasRemaining()) {
                int n = channel.read(bb, position + total);
                if (n < 0) {
                    return total == 0 ? -1 : total;
                }
                total += n;
            }
            return total;
        }

        @Override
        public long getSize() {
            return size;
        }

        @Override
        public void close() throws IOException {
            try { channel.close(); } catch (IOException ignored) { }
            stream.close();
        }
    }

    private boolean shouldUseIjkPlayback(Uri mediaUri) {
        if (mediaUri == null) return false;
        String mime = OfflineVideoProbe.probeFirstVideoMime(this, mediaUri);
        boolean mpeg2Mime = OfflineVideoProbe.isMpeg2VideoMime(mime);
        boolean mpeg2Meta = metadataLooksLikeMpeg2(meta);
        // Force IJK for MPEG-2 video regardless of what MediaCodecList
        // advertises: many devices (e.g. Samsung Fold SM-F946U) expose
        // c2.android.mpeg2.decoder but ExoPlayer ends up rendering audio
        // only – sound, no picture. IJK with its bundled MPEG-2 software
        // decoder reliably handles these recordings.
        boolean exoCanDecodeMpeg2 = CodecCapabilityDetector
                .isVideoCodecSupportedByExo(this, VideoCodec.MPEG2);
        boolean useIjk = mpeg2Mime || (mime == null && mpeg2Meta);
        log.info("offline_playback_adapt mediaFileID={} uri={} extractorMime={} metadataMpeg2={} exoMpeg2={} useIjk={}",
                meta == null ? null : meta.getMediaFileID(), mediaUri,
                mime, mpeg2Meta, exoCanDecodeMpeg2, useIjk);
        if (useIjk) {
            Toast.makeText(this, "Using software video playback for this device",
                    Toast.LENGTH_SHORT).show();
        }
        return useIjk;
    }

    /**
     * Uses Android's built-in {@link MediaExtractor} (stagefright) to read
     * the first video track's MIME type. Works for MKV, MP4, TS and other
     * containers the platform supports without depending on bundled ffmpeg.
     */
    @SuppressWarnings("unused")
    private static String probeFirstVideoMime(Context ctx, Uri uri) {
        return OfflineVideoProbe.probeFirstVideoMime(ctx, uri);
    }

    private static boolean isMpeg2VideoMime(String mime) {
        return OfflineVideoProbe.isMpeg2VideoMime(mime);
    }

    private static boolean metadataLooksLikeMpeg2(DownloadMetadata meta) {
        if (meta == null) return false;
        String raw = meta.getOfflineMetadataJson();
        if (raw == null || raw.isEmpty()) return false;
        String upper = raw.toUpperCase(Locale.US);
        return upper.contains("MPEG2-VIDEO")
                || upper.contains("MPEG2VIDEO")
                || upper.contains("MPEG-2")
                || upper.contains("MPEG2 VIDEO");
    }

    private void loadCommercialSegments() {
        commercialSegments = Collections.emptyList();
        if (meta == null) {
            return;
        }
        File edlFile = findComskipFile(meta);
        if (edlFile == null) {
            log.info("offline_comskip_unavailable mediaFileID={} reason=no_edl",
                    meta.getMediaFileID());
            return;
        }
        // Always parse the EDL when it's present so the timebar can render
        // the commercial bands. Whether we *auto-skip* over them is a
        // separate user pref (meta.isAutoComskip()) consulted by the
        // autoSkipRunnable / maybeAutoSkipCommercial path below.
        commercialSegments = parseEdl(edlFile);
        log.info("offline_comskip_loaded mediaFileID={} file={} segments={} autoSkip={}",
                meta.getMediaFileID(), edlFile.getName(),
                commercialSegments.size(), meta.isAutoComskip());
    }

    private File findComskipFile(DownloadMetadata meta) {
        String dirPath = meta.getCompanionDirPath();
        if (dirPath == null || dirPath.isEmpty()) return null;
        File dir = new File(dirPath);
        if (!dir.isDirectory()) return null;

        File preferred = new File(dir, "comskip.edl");
        if (preferred.isFile()) return preferred;

        File[] files = dir.listFiles((parent, name) -> {
            String lower = name.toLowerCase(java.util.Locale.US);
            return lower.startsWith("comskip") && lower.endsWith(".edl");
        });
        if (files == null || files.length == 0) return null;
        java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return files[0];
    }

    private List<CommercialSegment> parseEdl(File edlFile) {
        List<CommercialSegment> segments = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(edlFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                    continue;
                }
                String[] parts = trimmed.split("\\s+");
                if (parts.length < 2) {
                    continue;
                }
                long startMs = parseEdlSeconds(parts[0]);
                long endMs = parseEdlSeconds(parts[1]);
                if (startMs >= 0 && endMs > startMs) {
                    segments.add(new CommercialSegment(startMs, endMs));
                }
            }
        } catch (Exception e) {
            log.warn("offline_autoskip_parse_failed file={}", edlFile.getAbsolutePath(), e);
            return Collections.emptyList();
        }
        Collections.sort(segments, (a, b) -> Long.compare(a.startMs, b.startMs));
        return segments;
    }

    private long parseEdlSeconds(String text) {
        try {
            return Math.round(Double.parseDouble(text) * 1000.0d);
        } catch (Exception e) {
            return -1L;
        }
    }

    private void startAutoSkipIfEnabled() {
        stopAutoSkip();
        if (meta == null || !meta.isAutoComskip() || commercialSegments.isEmpty()) {
            return;
        }
        autoSkipHandler.postDelayed(autoSkipRunnable, AUTOSKIP_POLL_MS);
    }

    private void stopAutoSkip() {
        autoSkipHandler.removeCallbacks(autoSkipRunnable);
    }

    private void persistPlaybackPosition(boolean force) {
        if (meta == null || !hasPlaybackEngine()) {
            return;
        }
        long durationMs = getPlaybackDurationMs();
        long positionMs = Math.max(0L, getPlaybackPositionMs());
        if (positionMs < RESUME_THRESHOLD_MS) {
            positionMs = 0L;
        }
        if (durationMs > 0 && durationMs != com.google.android.exoplayer2.C.TIME_UNSET
                && durationMs - positionMs < 10_000L) {
            positionMs = 0L;
        }
        if (!force && Math.abs(positionMs - lastSavedPositionMs) < POSITION_SAVE_INTERVAL_MS) {
            return;
        }
        lastSavedPositionMs = positionMs;
        meta.setPlaybackPositionMs(positionMs);
        DownloadManager.getInstance(this).getRepository().update(meta);
        OfflinePlaybackStateSync.syncAsync(this, meta, force ? "playback_position_final" : "playback_position");
    }

    private final Runnable autoSkipRunnable = new Runnable() {
        @Override
        public void run() {
            if (!hasPlaybackEngine() || meta == null || !meta.isAutoComskip() || commercialSegments.isEmpty()) {
                return;
            }
            if (isPlaybackReady() && isPlaybackPlaying()) {
                maybeAutoSkipCommercial(getPlaybackPositionMs());
            }
            autoSkipHandler.postDelayed(this, AUTOSKIP_POLL_MS);
        }
    };

    private void maybeAutoSkipCommercial(long positionMs) {
        long now = android.os.SystemClock.uptimeMillis();
        for (CommercialSegment segment : commercialSegments) {
            if (positionMs < segment.startMs) {
                return;
            }
            if (positionMs >= segment.startMs && positionMs < segment.endMs) {
                // Cooldown: if we just skipped this exact band, do nothing
                // until the cooldown expires — even if position drifts up
                // a little while the seek is in flight. Without this the
                // 500ms poll keeps triggering Toasts + seek calls in a
                // tight loop on slow-seeking sources.
                if (segment.startMs == lastAutoSkippedSegmentStartMs
                        && (now - lastAutoSkippedAtUptimeMs) < AUTOSKIP_COOLDOWN_MS) {
                    return;
                }
                long targetMs = segment.endMs + AUTOSKIP_EXIT_PADDING_MS;
                lastAutoSkippedSegmentStartMs = segment.startMs;
                lastAutoSkippedAtUptimeMs = now;
                log.info("offline_autoskip from={} to={} pos={}",
                        segment.startMs, targetMs, positionMs);
                Toast.makeText(this, "Skipping commercial", Toast.LENGTH_SHORT).show();
                seekAbsolute(targetMs);
                return;
            }
        }
    }

    private void scheduleTitleHide() {
        titleBar.setVisibility(View.VISIBLE);
        hideHandler.removeCallbacks(hideTitleRunnable);
        hideHandler.postDelayed(hideTitleRunnable, 5000);
    }

    private void installOfflineTimeBar() {
        FrameLayout contentRoot = findViewById(android.R.id.content);
        if (contentRoot == null) {
            return;
        }
        offlineTimeBar = new OfflineTimeBarView(this);
        offlineTimeBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(72),
                android.view.Gravity.BOTTOM);
        // Keep the timebar parented to the activity content root so it can
        // appear briefly on hardware-remote FF/REW even when the soft-remote
        // popup is closed. The OfflineNavigationOverlay reparents it INTO
        // its bottom dock whenever the popup is attached, and reparents it
        // back here when the popup detaches — so when the popup is open the
        // timebar is visually part of it (no separate floating layer), and
        // when the popup is closed the timebar still flashes in its usual
        // place on every seek.
        contentRoot.addView(offlineTimeBar, lp);
    }

    /**
     * Activity-level seek hook used by {@link OfflineTimeBarView} when the
     * user taps the bar at a specific position. Routed through the same
     * {@link #seekAbsolute(long)} path used by the soft-remote transport
     * buttons so all the position/save/segment logic stays consistent.
     */
    void seekFromTimebar(long targetMs) {
        if (!hasPlaybackEngine()) return;
        long dur = getPlaybackDurationMs();
        if (dur <= 0L || dur == com.google.android.exoplayer2.C.TIME_UNSET) return;
        long clamped = Math.max(0L, Math.min(targetMs, dur));
        log.info("offline_timebar_seek targetMs={} dur={}", clamped, dur);
        seekAbsolute(clamped);
        showOfflineTimeBarBriefly();
    }

    /**
     * Called by {@link OfflineNavigationOverlay} when it reparents the
     * docked timebar into the popup. The popup keeps the bar visible for
     * the duration the user has the overlay open, so we kick the periodic
     * refresh runnable here too so the playhead advances and the comskip
     * bands paint immediately.
     */
    void refreshOfflineTimeBar() {
        if (offlineTimeBar == null) return;
        updateOfflineTimeBar();
        timeBarHandler.removeCallbacks(updateTimeBarRunnable);
        timeBarHandler.postDelayed(updateTimeBarRunnable, TIMEBAR_UPDATE_MS);
    }

    private void showOfflineTimeBarBriefly() {
        if (offlineTimeBar == null || !hasPlaybackEngine()) {
            return;
        }
        updateOfflineTimeBar();
        offlineTimeBar.setVisibility(View.VISIBLE);
        timeBarHandler.removeCallbacks(hideTimeBarRunnable);
        timeBarHandler.removeCallbacks(updateTimeBarRunnable);
        timeBarHandler.postDelayed(hideTimeBarRunnable, TIMEBAR_VISIBLE_MS);
        timeBarHandler.postDelayed(updateTimeBarRunnable, TIMEBAR_UPDATE_MS);
    }

    private void updateOfflineTimeBar() {
        if (offlineTimeBar == null || !hasPlaybackEngine()) {
            return;
        }
        long durationMs = getPlaybackDurationMs();
        if (durationMs == com.google.android.exoplayer2.C.TIME_UNSET) {
            durationMs = 0L;
        }
        offlineTimeBar.setPlaybackState(
                Math.max(0L, getPlaybackPositionMs()),
                Math.max(0L, durationMs),
                commercialSegments);
    }

    private void bindControls() {
        // No click handler on the player view — leave it to the built-in
        // StyledPlayerView controller (which has the scrubable timeline).
        // The soft remote is still reachable via the left-edge swipe gesture
        // and via long-press OK.
    }

    private final Runnable hideTitleRunnable = () -> {
        if (titleBar != null) {
            titleBar.animate().alpha(0f).setDuration(300).withEndAction(() ->
                    titleBar.setVisibility(View.GONE)).start();
        }
    };

    private void handleLocalAction(LocalAction action) {
        switch (action) {
            case TOGGLE_CONTROLS:
                if (playerView != null && !usingIjkPlayer) {
                    playerView.showController();
                }
                showTitleBriefly();
                showOfflineTimeBarBriefly();
                return;
            case TOGGLE_PLAYBACK:
                setPlaybackPlayWhenReady(!isPlaybackPlaying());
                showTitleBriefly();
                showOfflineTimeBarBriefly();
                return;
            case PLAY:
                setPlaybackPlayWhenReady(true);
                showOfflineTimeBarBriefly();
                return;
            case PAUSE:
                setPlaybackPlayWhenReady(false);
                showTitleBriefly();
                showOfflineTimeBarBriefly();
                return;
            case STOP:
                finish();
                return;
            case FAST_FORWARD:
                seekRelative(+30_000L);
                showTitleBriefly();
                showOfflineTimeBarBriefly();
                return;
            case REWIND:
                seekRelative(-10_000L);
                showTitleBriefly();
                showOfflineTimeBarBriefly();
                return;
            case SKIP_FORWARD:
                seekRelative(+60_000L);
                showTitleBriefly();
                showOfflineTimeBarBriefly();
                return;
            case SHOW_INFO:
                if (hasPlaybackEngine()) {
                    long duration = getPlaybackDurationMs();
                    long position = getPlaybackPositionMs();
                    Toast.makeText(
                            this,
                            "Position " + formatTime(position) + " / " + formatTime(duration),
                            Toast.LENGTH_SHORT).show();
                } else {
                    showTitleBriefly();
                }
                showOfflineTimeBarBriefly();
                return;
            case ROTATE:
                if (!OrientationController.isLeanback(this)) {
                    OrientationController.cycleAndApply(this);
                }
                return;
            case FIT_TO_SCREEN:
                applyResizeMode(ResizeMode.FIT);
                showOfflineTimeBarBriefly();
                return;
            case NATIVE_ASPECT:
                applyResizeMode(ResizeMode.NATIVE);
                showOfflineTimeBarBriefly();
                return;
            case CYCLE_RESIZE:
                applyResizeMode(nextResizeMode(resizeMode));
                showOfflineTimeBarBriefly();
                return;
        }
    }

    private static ResizeMode nextResizeMode(ResizeMode current) {
        switch (current) {
            case NATIVE: return ResizeMode.FIT;
            case FIT:    return ResizeMode.ZOOM;
            case ZOOM:   return ResizeMode.NATIVE;
            default:     return ResizeMode.NATIVE;
        }
    }

    private static String resizeLabel(ResizeMode mode) {
        switch (mode) {
            case NATIVE: return "Source resolution";
            case FIT:    return "Stretch to fill";
            case ZOOM:   return "Zoom (crop)";
            default:     return mode.name();
        }
    }

    /**
     * Applies the requested {@link ResizeMode} to whichever engine is
     * currently active and shows a brief toast. Safe to call before the
     * video size is known — in that case the IJK path will re-apply when
     * the size-changed callback fires.
     */
    private void applyResizeMode(ResizeMode mode) {
        this.resizeMode = mode;
        if (usingIjkPlayer) {
            applyIjkResizeMode(mode);
        } else {
            applyExoResizeMode(mode);
        }
        Toast.makeText(this, resizeLabel(mode), Toast.LENGTH_SHORT).show();
    }

    private void applyExoResizeMode(ResizeMode mode) {
        if (playerView == null) return;
        switch (mode) {
            case NATIVE:
                // Preserve aspect ratio and letterbox into the surface; this
                // is what the user thinks of as "source resolution" — the
                // video shows at its native aspect, with black bars wherever
                // the surface is larger than the source.
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                break;
            case FIT:
                // Stretch to fill, ignoring source aspect.
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
                break;
            case ZOOM:
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                break;
        }
    }

    private void applyIjkResizeMode(ResizeMode mode) {
        if (ijkSurfaceView == null) return;
        // Without a known video size we can't preserve aspect on a raw
        // SurfaceView; leave it match_parent and re-apply once the
        // OnVideoSizeChanged listener provides real dimensions.
        if (ijkVideoWidth <= 0 || ijkVideoHeight <= 0) {
            setIjkSurfaceSize(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            return;
        }
        View parent = (View) ijkSurfaceView.getParent();
        int parentW = parent != null ? parent.getWidth() : ijkSurfaceView.getWidth();
        int parentH = parent != null ? parent.getHeight() : ijkSurfaceView.getHeight();
        if (parentW <= 0 || parentH <= 0) {
            // Defer until layout is known.
            ijkSurfaceView.post(() -> applyIjkResizeMode(mode));
            return;
        }
        float videoAspect = (float) ijkVideoWidth / (float) ijkVideoHeight;
        int targetW, targetH;
        switch (mode) {
            case FIT: {
                // Stretch to fill, regardless of aspect ratio.
                targetW = parentW;
                targetH = parentH;
                break;
            }
            case ZOOM: {
                float parentAspect = (float) parentW / (float) parentH;
                if (videoAspect > parentAspect) {
                    // Video is wider than container: match height, overflow width
                    targetH = parentH;
                    targetW = Math.round(parentH * videoAspect);
                } else {
                    targetW = parentW;
                    targetH = Math.round(parentW / videoAspect);
                }
                break;
            }
            case NATIVE:
            default: {
                // Preserve aspect ratio, fit inside the surface (letterbox).
                // Matches the Exo NATIVE behaviour and keeps the source
                // pixel ratio intact.
                float parentAspect = (float) parentW / (float) parentH;
                if (videoAspect > parentAspect) {
                    targetW = parentW;
                    targetH = Math.round(parentW / videoAspect);
                } else {
                    targetH = parentH;
                    targetW = Math.round(parentH * videoAspect);
                }
                break;
            }
        }
        setIjkSurfaceSize(targetW, targetH);
    }

    private void setIjkSurfaceSize(int w, int h) {
        ViewGroup.LayoutParams lp = ijkSurfaceView.getLayoutParams();
        if (lp instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
            flp.width = w;
            flp.height = h;
            flp.gravity = Gravity.CENTER;
        } else {
            lp.width = w;
            lp.height = h;
        }
        ijkSurfaceView.setLayoutParams(lp);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (overlay != null && overlay.onKeyDown(keyCode, event)) return true;
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                Fragment current = getFragmentManager().findFragmentByTag("offline_playback_controls");
                if (current instanceof android.app.DialogFragment) {
                    ((android.app.DialogFragment) current).dismiss();
                    return true;
                }
                finish();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // overlay.onKeyDown() already called event.startTracking();
                // defer the short-press play/pause action to onKeyUp so the
                // long-press can fire and show the soft remote overlay.
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                setPlaybackPlayWhenReady(!isPlaybackPlaying());
                showTitleBriefly();
                showOfflineTimeBarBriefly();
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (overlay != null) overlay.activate();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                setPlaybackPlayWhenReady(true);
                showOfflineTimeBarBriefly();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                setPlaybackPlayWhenReady(false);
                showTitleBriefly();
                showOfflineTimeBarBriefly();
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                finish();
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                seekRelative(+30_000L);
                showTitleBriefly();
                showOfflineTimeBarBriefly();
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                seekRelative(-10_000L);
                showTitleBriefly();
                showOfflineTimeBarBriefly();
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                seekRelative(+60_000L);
                showTitleBriefly();
                showOfflineTimeBarBriefly();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                seekRelative(-10_000L);
                showTitleBriefly();
                showOfflineTimeBarBriefly();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (overlay != null && overlay.onKeyLongPress(keyCode, event)) return true;
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (overlay != null && overlay.onKeyUp(keyCode, event)) return true;
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                && event.isTracking() && !event.isCanceled()) {
            // Short press of OK: toggle play/pause (long press was already
            // consumed by the overlay's onKeyLongPress).
            setPlaybackPlayWhenReady(!isPlaybackPlaying());
            showTitleBriefly();
            showOfflineTimeBarBriefly();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    /**
     * Wraps the ExoPlayer so any seek request (from the controller's timeline
     * scrub, from media-session clients, etc.) is routed through our
     * non-seekable-aware re-prepare path.
     */
    private Player seekInterceptingPlayer(ExoPlayer real) {
        return new ForwardingPlayer(real) {
            @Override public void setPlayWhenReady(boolean playWhenReady) {
                showOfflineTimeBarBriefly();
                super.setPlayWhenReady(playWhenReady);
            }
            @Override public void seekTo(long positionMs) {
                log.info("offline_player_intercept call=seekTo pos={}", positionMs);
                showOfflineTimeBarBriefly();
                seekAbsolute(positionMs);
            }
            @Override public void seekTo(int mediaItemIndex, long positionMs) {
                log.info("offline_player_intercept call=seekTo idx={} pos={}", mediaItemIndex, positionMs);
                showOfflineTimeBarBriefly();
                seekAbsolute(positionMs);
            }
            @Override public void seekForward() {
                log.info("offline_player_intercept call=seekForward");
                showOfflineTimeBarBriefly();
                seekRelative(+30_000L);
            }
            @Override public void seekBack() {
                log.info("offline_player_intercept call=seekBack");
                showOfflineTimeBarBriefly();
                seekRelative(-10_000L);
            }
            @Override public void seekToNext() {
                log.info("offline_player_intercept call=seekToNext");
                showOfflineTimeBarBriefly();
                seekRelative(+30_000L);
            }
            @Override public void seekToPrevious() {
                log.info("offline_player_intercept call=seekToPrevious");
                showOfflineTimeBarBriefly();
                seekRelative(-10_000L);
            }
            @Override public boolean isCurrentMediaItemSeekable() {
                // Always advertise seekable to the StyledPlayerView controller
                // so the timeline scrubber stays interactive. We translate the
                // seek into a re-prepare in our seekRelative path when the real
                // player reports the media item as non-seekable.
                return true;
            }
            @Override public long getDuration() {
                long d = super.getDuration();
                if (d == com.google.android.exoplayer2.C.TIME_UNSET) {
                    // StyledPlayerView refuses to draw the timeline when
                    // duration is unknown. Fall back to a synthetic 2h window
                    // so the user can at least scrub; seekRelative re-prepares.
                    return 2L * 60L * 60L * 1000L;
                }
                return d;
            }
        };
    }

    private void seekAbsolute(long targetMs) {
        if (!hasPlaybackEngine()) return;
        long pos = getPlaybackPositionMs();
        seekRelative(targetMs - pos);
    }

    private void showTitleBriefly() {
        titleBar.setAlpha(1f);
        scheduleTitleHide();
    }

    /**
     * Seek the player by {@code deltaMs}. For seekable sources we use a
     * normal seekTo. For non-seekable sources (SageTV MPEG-TS recordings
     * with no seek index) ExoPlayer snaps the playhead back to 0, so we
     * re-prepare the same MediaItem with startPositionMs set to the target
     * instead.
     */
    private void seekRelative(long deltaMs) {
        if (!hasPlaybackEngine()) return;
        long pos = getPlaybackPositionMs();
        long dur = getPlaybackDurationMs();
        long target = pos + deltaMs;
        if (dur > 0) target = Math.min(target, dur);
        target = Math.max(0, target);
        if (usingIjkPlayer) {
            log.info("offline_ijk_seek pos={} dur={} target={} delta={}", pos, dur, target, deltaMs);
            seekPlaybackTo(target);
            return;
        }
        boolean seekable = player.isCurrentMediaItemSeekable();
        log.info("offline_player_seek pos={} dur={} target={} delta={} seekable={}",
                pos, dur, target, deltaMs, seekable);
        if (seekable) {
            player.seekTo(target);
        } else if (mediaUri != null) {
            MediaItem item = MediaItem.fromUri(mediaUri);
            player.setMediaItem(item, target);
            player.prepare();
        }
    }

    private boolean hasPlaybackEngine() {
        return usingIjkPlayer ? ijkPlayer != null : player != null;
    }

    private boolean isPlaybackReady() {
        if (usingIjkPlayer) {
            return ijkPlayer != null && ijkPrepared;
        }
        return player != null && player.getPlaybackState() == Player.STATE_READY;
    }

    private boolean isPlaybackPlaying() {
        if (usingIjkPlayer) {
            try {
                return ijkPlayer != null && ijkPlayer.isPlaying();
            } catch (Throwable ignored) {
                return false;
            }
        }
        return player != null && player.getPlayWhenReady();
    }

    private void setPlaybackPlayWhenReady(boolean playWhenReady) {
        if (usingIjkPlayer) {
            if (ijkPlayer == null || !ijkPrepared) return;
            try {
                if (playWhenReady) {
                    if (!ijkPlayer.isPlaying()) ijkPlayer.start();
                } else if (ijkPlayer.isPlaying()) {
                    ijkPlayer.pause();
                }
            } catch (Throwable t) {
                log.warn("offline_ijk_play_pause_failed play={} err={}", playWhenReady, t.toString());
            }
            return;
        }
        if (player != null) {
            player.setPlayWhenReady(playWhenReady);
        }
    }

    private long getPlaybackPositionMs() {
        if (usingIjkPlayer) {
            if (ijkPlayer == null) return 0L;
            try {
                return Math.max(0L, ijkPlayer.getCurrentPosition());
            } catch (Throwable ignored) {
                return 0L;
            }
        }
        return player == null ? 0L : player.getCurrentPosition();
    }

    private long getPlaybackDurationMs() {
        if (usingIjkPlayer) {
            if (ijkPlayer == null) return 0L;
            try {
                return ijkPlayer.getDuration();
            } catch (Throwable ignored) {
                return 0L;
            }
        }
        return player == null ? 0L : player.getDuration();
    }

    private void seekPlaybackTo(long targetMs) {
        if (usingIjkPlayer) {
            if (ijkPlayer == null || !ijkPrepared) return;
            try {
                ijkPlayer.seekTo(targetMs);
            } catch (Throwable t) {
                log.warn("offline_ijk_seek_failed target={} err={}", targetMs, t.toString());
            }
            return;
        }
        if (player != null) {
            player.seekTo(targetMs);
        }
    }

    private void releaseIjkPlayer() {
        ijkPrepared = false;
        if (ijkPlayer != null) {
            try { ijkPlayer.stop(); } catch (Throwable ignored) { }
            try { ijkPlayer.release(); } catch (Throwable ignored) { }
            ijkPlayer = null;
        }
        closeIjkPfdQuietly();
    }

    private String formatTime(long millis) {
        if (millis < 0) {
            return "--:--";
        }
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }

    @Override
    protected void onPause() {
        super.onPause();
        persistPlaybackPosition(true);
        setPlaybackPlayWhenReady(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        hideHandler.removeCallbacksAndMessages(null);
        stopAutoSkip();
        timeBarHandler.removeCallbacksAndMessages(null);
        positionSaveHandler.removeCallbacksAndMessages(null);
        persistPlaybackPosition(true);
        if (currentInstance == this) {
            currentInstance = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        releaseIjkPlayer();
    }

    private static final class CommercialSegment {
        final long startMs;
        final long endMs;

        CommercialSegment(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class OfflineTimeBarView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private List<CommercialSegment> segments = Collections.emptyList();
        private long positionMs;
        private long durationMs;

        OfflineTimeBarView(Context context) {
            super(context);
            setWillNotDraw(false);
            setPadding(dp(28), dp(8), dp(28), dp(12));
            // Fully transparent host \u2014 only the bar / segments / scrubber
            // painted in onDraw() should be visible.
            setBackgroundColor(android.graphics.Color.TRANSPARENT);
            setMinimumHeight(dp(72));
            setClickable(true);
            setFocusable(false);
        }

        void setPlaybackState(long positionMs, long durationMs, List<CommercialSegment> segments) {
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.segments = segments != null ? segments : Collections.emptyList();
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            // Only act on UP — DOWN/MOVE drags would invite a wider scrubber
            // implementation (delta, ghost playhead, friction). The current
            // seekAbsolute() path is heavyweight (re-prepare on non-seekable
            // sources) so a single tap-to-jump fits the model best.
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    && durationMs > 0L) {
                float left = getPaddingLeft();
                float right = getWidth() - getPaddingRight();
                if (right > left) {
                    float x = event.getX();
                    if (x < left) x = left;
                    if (x > right) x = right;
                    float ratio = (x - left) / (right - left);
                    long target = (long) (ratio * durationMs);
                    seekFromTimebar(target);
                    performClick();
                    return true;
                }
            }
            return super.onTouchEvent(event);
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float left = getPaddingLeft();
            float right = getWidth() - getPaddingRight();
            if (right <= left) {
                return;
            }

            float barHeight = dp(14);
            float barTop = getHeight() - getPaddingBottom() - barHeight;
            float barBottom = barTop + barHeight;
            float radius = barHeight / 2f;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF35E84F);
            rect.set(left, barTop, right, barBottom);
            canvas.drawRoundRect(rect, radius, radius, paint);

            if (durationMs > 0) {
                float playedRight = left + ((right - left) * clamp01(positionMs / (float) durationMs));
                paint.setColor(0xFFFFFFFF);
                rect.set(left, barTop, playedRight, barBottom);
                canvas.drawRoundRect(rect, radius, radius, paint);

                paint.setColor(0xFF3E3E3E);
                for (CommercialSegment segment : segments) {
                    float start = left + ((right - left) * clamp01(segment.startMs / (float) durationMs));
                    float end = left + ((right - left) * clamp01(segment.endMs / (float) durationMs));
                    if (end > start) {
                        rect.set(start, barTop, end, barBottom);
                        canvas.drawRect(rect, paint);
                    }
                }

                paint.setColor(0xFFFF3B30);
                float markerX = playedRight;
                rect.set(markerX - dp(1), barTop - dp(4), markerX + dp(1), barBottom + dp(4));
                canvas.drawRect(rect, paint);
            }

            paint.setTextSize(dp(15));
            paint.setColor(0xFFFFFFFF);
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(formatTime(positionMs), left, dp(22), paint);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(formatTime(durationMs), right, dp(22), paint);
        }

        private float clamp01(float value) {
            if (value < 0f) return 0f;
            if (value > 1f) return 1f;
            return value;
        }
    }
}
