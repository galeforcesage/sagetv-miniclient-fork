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
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import com.google.android.exoplayer2.upstream.DefaultDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sagex.miniclient.android.offline.R;

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

    public static final String EXTRA_MEDIA_URI = "media_uri";
    public static final String EXTRA_MEDIA_TITLE = "media_title";

    private ExoPlayer player;
    private StyledPlayerView playerView;
    private View titleBar;
    private final Handler hideHandler = new Handler(Looper.getMainLooper());

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
        titleBar = findViewById(R.id.offline_playback_title_bar);

        String title = getIntent().getStringExtra(EXTRA_MEDIA_TITLE);
        if (title != null) {
            ((TextView) findViewById(R.id.offline_playback_title)).setText(title);
        }

        String uriString = getIntent().getStringExtra(EXTRA_MEDIA_URI);
        if (uriString == null) {
            log.error("No media URI provided");
            finish();
            return;
        }

        initializePlayer(Uri.parse(uriString));
    }

    private void initializePlayer(Uri mediaUri) {
        DefaultDataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(this);

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory))
                .build();

        playerView.setPlayer(player);

        MediaItem mediaItem = MediaItem.fromUri(mediaUri);
        player.setMediaItem(mediaItem);
        player.setPlayWhenReady(true);
        player.prepare();

        // Auto-hide title bar after 5 seconds
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    scheduleTitleHide();
                } else if (playbackState == Player.STATE_ENDED) {
                    finish();
                }
            }
        });

        log.info("Offline playback started: {}", mediaUri);
    }

    private void scheduleTitleHide() {
        titleBar.setVisibility(View.VISIBLE);
        hideHandler.removeCallbacks(hideTitleRunnable);
        hideHandler.postDelayed(hideTitleRunnable, 5000);
    }

    private final Runnable hideTitleRunnable = () -> {
        if (titleBar != null) {
            titleBar.animate().alpha(0f).setDuration(300).withEndAction(() ->
                    titleBar.setVisibility(View.GONE)).start();
        }
    };

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                finish();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (player != null) {
                    player.setPlayWhenReady(!player.getPlayWhenReady());
                }
                showTitleBriefly();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (player != null) player.setPlayWhenReady(true);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (player != null) player.setPlayWhenReady(false);
                showTitleBriefly();
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                finish();
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (player != null) {
                    player.seekTo(Math.min(player.getCurrentPosition() + 30000,
                            player.getDuration()));
                }
                showTitleBriefly();
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (player != null) {
                    player.seekTo(Math.max(player.getCurrentPosition() - 10000, 0));
                }
                showTitleBriefly();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void showTitleBriefly() {
        titleBar.setAlpha(1f);
        scheduleTitleHide();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) {
            player.setPlayWhenReady(false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        hideHandler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
