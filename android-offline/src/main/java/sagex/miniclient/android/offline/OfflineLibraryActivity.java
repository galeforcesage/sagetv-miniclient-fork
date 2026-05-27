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
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import sagex.miniclient.android.offline.R;

/**
 * Full-screen offline media library browser styled to match the SageTV7 dark
 * theme that the server sends to placeshifter clients. Shows downloaded media
 * with thumbnails, titles, and status — only the menu items relevant to offline
 * content are presented (play, delete, info).
 *
 * Accessible from the server connect screen when downloads exist, or from
 * the Downloads settings entry.
 */
public class OfflineLibraryActivity extends Activity implements OfflineMediaAdapter.OnItemActionListener {
    private static final Logger log = LoggerFactory.getLogger(OfflineLibraryActivity.class);
    private static final long REFRESH_INTERVAL_MS = 3000;

    private RecyclerView mediaList;
    private View emptyState;
    private TextView headerCount;
    private OfflineMediaAdapter adapter;
    private DownloadManager downloadManager;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Full screen immersive — matches SageTV placeshifter full-screen mode
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        setContentView(R.layout.activity_offline_library);

        downloadManager = DownloadManager.getInstance(this);

        mediaList = findViewById(R.id.offline_media_list);
        emptyState = findViewById(R.id.offline_empty_state);
        headerCount = findViewById(R.id.offline_header_count);

        adapter = new OfflineMediaAdapter(this, this);
        mediaList.setLayoutManager(new LinearLayoutManager(this));
        mediaList.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
        startAutoRefresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAutoRefresh();
    }

    private void refreshList() {
        List<DownloadMetadata> downloads = downloadManager.getQueue();
        // Only show completed or in-progress downloads (not failed with 0 bytes)
        List<DownloadMetadata> displayList = new ArrayList<>();
        for (DownloadMetadata meta : downloads) {
            displayList.add(meta);
        }

        adapter.setItems(displayList);

        int completeCount = 0;
        for (DownloadMetadata m : displayList) {
            if (m.getStatus() == DownloadMetadata.Status.COMPLETE) completeCount++;
        }

        if (displayList.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            mediaList.setVisibility(View.GONE);
            headerCount.setText("");
        } else {
            emptyState.setVisibility(View.GONE);
            mediaList.setVisibility(View.VISIBLE);
            headerCount.setText(completeCount + " of " + displayList.size() + " ready");
        }
    }

    private void startAutoRefresh() {
        refreshHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
    }

    private void stopAutoRefresh() {
        refreshHandler.removeCallbacks(refreshRunnable);
    }

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshList();
            refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    // --- Item actions ---

    @Override
    public void onPlay(DownloadMetadata meta) {
        if (meta.getStatus() != DownloadMetadata.Status.COMPLETE) {
            return; // Can't play incomplete downloads
        }
        log.info("Playing offline media: {} ({})", meta.getTitle(), meta.getLocalUri());
        Intent intent = new Intent(this, OfflinePlaybackActivity.class);
        intent.putExtra(OfflinePlaybackActivity.EXTRA_MEDIA_URI, meta.getLocalUri());
        intent.putExtra(OfflinePlaybackActivity.EXTRA_MEDIA_TITLE, meta.getTitle());
        startActivity(intent);
    }

    @Override
    public void onShowOptions(DownloadMetadata meta) {
        showOptionsDialog(meta);
    }

    private void showOptionsDialog(DownloadMetadata meta) {
        String title = meta.getTitle() != null ? meta.getTitle() : meta.getMediaFileID();
        List<String> options = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (meta.getStatus() == DownloadMetadata.Status.COMPLETE) {
            options.add("Play");
            actions.add(() -> onPlay(meta));
        }
        if (meta.getStatus() == DownloadMetadata.Status.DOWNLOADING) {
            options.add("Pause Download");
            actions.add(() -> {
                downloadManager.pause(meta.getMediaFileID());
                refreshList();
            });
        }
        if (meta.getStatus() == DownloadMetadata.Status.PAUSED
                || meta.getStatus() == DownloadMetadata.Status.FAILED) {
            options.add("Resume Download");
            actions.add(() -> {
                downloadManager.resume(meta.getMediaFileID());
                refreshList();
            });
        }
        options.add("Delete");
        actions.add(() -> confirmDelete(meta));

        String[] items = options.toArray(new String[0]);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle(title)
                .setItems(items, (dialog, which) -> actions.get(which).run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDelete(DownloadMetadata meta) {
        String title = meta.getTitle() != null ? meta.getTitle() : meta.getMediaFileID();
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("Delete Download")
                .setMessage("Delete \"" + title + "\" and its file?")
                .setPositiveButton("Delete", (d, w) -> {
                    downloadManager.cancel(meta.getMediaFileID());
                    refreshList();
                })
                .setNegativeButton("Keep", null)
                .show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // BACK exits the offline library
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        // MENU key on focused item shows options
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            int pos = adapter.getFocusedPosition();
            if (pos >= 0 && pos < adapter.getItemCount()) {
                showOptionsDialog(adapter.getItem(pos));
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }
}
