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
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

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
    private View libraryContent;
    private TextView headerClock;
    private TextView footerCount;
    private ImageView previewThumb;
    private TextView previewTitle;
    private TextView previewEpisode;
    private TextView previewMeta;
    private TextView previewAiredOn;
    private TextView previewOriginalAirDate;
    private TextView previewCategory;
    private TextView previewChannel;
    private TextView previewDescription;
    private OfflineMediaAdapter adapter;
    private DownloadManager downloadManager;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private String activeMediaFileId;

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
        libraryContent = findViewById(R.id.offline_library_content);
        headerClock = findViewById(R.id.offline_header_clock);
        footerCount = findViewById(R.id.offline_footer_count);
        previewThumb = findViewById(R.id.offline_preview_thumb);
        previewTitle = findViewById(R.id.offline_preview_title);
        previewEpisode = findViewById(R.id.offline_preview_episode);
        previewMeta = findViewById(R.id.offline_preview_meta);
        previewAiredOn = findViewById(R.id.offline_preview_aired_on);
        previewOriginalAirDate = findViewById(R.id.offline_preview_original_air_date);
        previewCategory = findViewById(R.id.offline_preview_category);
        previewChannel = findViewById(R.id.offline_preview_channel);
        previewDescription = findViewById(R.id.offline_preview_description);

        adapter = new OfflineMediaAdapter(this, this);
        mediaList.setLayoutManager(new LinearLayoutManager(this));
        mediaList.setAdapter(adapter);

        View backButton = findViewById(R.id.offline_header_icon);
        if (backButton != null) {
            backButton.setOnClickListener(v -> finish());
        }

        overlay = new OfflineNavigationOverlay(this);
    }

    private OfflineNavigationOverlay overlay;

    @Override
    protected void onResume() {
        super.onResume();
        updateHeaderClock();
        refreshList();
        startAutoRefresh();
        if (overlay != null) overlay.onResume();
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

        if (displayList.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            if (libraryContent != null) libraryContent.setVisibility(View.GONE);
            if (footerCount != null) footerCount.setText("0 Items");
            activeMediaFileId = null;
            updatePreview(null);
        } else {
            emptyState.setVisibility(View.GONE);
            if (libraryContent != null) libraryContent.setVisibility(View.VISIBLE);
            if (footerCount != null) footerCount.setText(displayList.size() + " Items");

            int targetPos = -1;
            if (activeMediaFileId != null) {
                targetPos = adapter.findPositionByMediaFileId(activeMediaFileId);
            }
            if (targetPos < 0) {
                int focusPos = adapter.getFocusedPosition();
                if (focusPos >= 0 && focusPos < displayList.size()) {
                    targetPos = focusPos;
                }
            }
            if (targetPos < 0) {
                targetPos = 0;
            }

            DownloadMetadata selected = displayList.get(targetPos);
            activeMediaFileId = selected.getMediaFileID();
            updatePreview(selected);

            final int restorePos = targetPos;
            mediaList.post(() -> {
                RecyclerView.ViewHolder vh = mediaList.findViewHolderForAdapterPosition(restorePos);
                if (vh != null && vh.itemView != null && !vh.itemView.hasFocus()) {
                    vh.itemView.requestFocus();
                }
            });
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
            updateHeaderClock();
            refreshList();
            refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    private void updateHeaderClock() {
        if (headerClock == null) return;
        java.text.DateFormat fmt = new java.text.SimpleDateFormat("EEE h:mm a", Locale.US);
        headerClock.setText(fmt.format(new Date()));
    }

    // --- Item actions ---

    @Override
    public void onPlay(DownloadMetadata meta) {
        activeMediaFileId = meta != null ? meta.getMediaFileID() : activeMediaFileId;
        if (meta.getStatus() != DownloadMetadata.Status.COMPLETE) {
            return; // Can't play incomplete downloads
        }
        log.info("Playing offline media: {} ({})", meta.getTitle(), meta.getLocalUri());
        long resumeMs = meta.getPlaybackPositionMs();
        if (resumeMs >= 3000L) {
            new AlertDialog.Builder(this)
                    .setTitle("Resume Playback")
                    .setMessage("Resume from " + formatTime(resumeMs) + "?")
                    .setPositiveButton("Resume", (d, w) -> startOfflinePlayback(meta, resumeMs))
                    .setNegativeButton("Start at Beginning", (d, w) -> {
                        meta.setPlaybackPositionMs(0L);
                        DownloadManager.getInstance(this).getRepository().update(meta);
                        OfflinePlaybackStateSync.syncAsync(this, meta, "start_at_beginning");
                        startOfflinePlayback(meta, 0L);
                    })
                    .show();
            return;
        }
        startOfflinePlayback(meta, 0L);
    }

    private void startOfflinePlayback(DownloadMetadata meta, long startPositionMs) {
        Intent intent = new Intent(this, OfflinePlaybackActivity.class);
        intent.putExtra(OfflinePlaybackActivity.EXTRA_MEDIA_URI, meta.getLocalUri());
        intent.putExtra(OfflinePlaybackActivity.EXTRA_MEDIA_TITLE, meta.getTitle());
        intent.putExtra(OfflinePlaybackActivity.EXTRA_MEDIA_FILE_ID, meta.getMediaFileID());
        intent.putExtra(OfflinePlaybackActivity.EXTRA_START_POSITION_MS, startPositionMs);
        startActivity(intent);
    }

    private String formatTime(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }

    @Override
    public void onShowOptions(DownloadMetadata meta) {
        activeMediaFileId = meta != null ? meta.getMediaFileID() : activeMediaFileId;
        Intent intent = new Intent(this, OfflineRecordingOptionsActivity.class);
        intent.putExtra(OfflineRecordingOptionsActivity.EXTRA_MEDIA_FILE_ID, meta.getMediaFileID());
        startActivity(intent);
    }

    @Override
    public void onFocused(DownloadMetadata meta) {
        activeMediaFileId = meta != null ? meta.getMediaFileID() : activeMediaFileId;
        updatePreview(meta);
    }

    private void updatePreview(DownloadMetadata meta) {
        if (previewTitle == null) return;
        if (meta == null) {
            previewThumb.setImageDrawable(null);
            previewTitle.setText("");
            previewEpisode.setText("");
            previewEpisode.setVisibility(View.GONE);
            previewMeta.setText("");
            previewMeta.setVisibility(View.GONE);
            previewAiredOn.setText("");
            previewOriginalAirDate.setText("");
            previewCategory.setText("");
            previewChannel.setText("");
            previewDescription.setText("");
            return;
        }

        PreviewData data = buildPreview(meta);
        String title = nonEmpty(data.seriesTitle, meta.getTitle(), meta.getMediaFileID(), "Recording");

        previewTitle.setText(title);
        String episode = nonEmpty(data.episodeTitle, data.episodeName);
        if (episode != null && !episode.equals(title)) {
            previewEpisode.setText(episode);
            previewEpisode.setVisibility(View.VISIBLE);
        } else {
            previewEpisode.setText("");
            previewEpisode.setVisibility(View.GONE);
        }

        String metaLine = buildMetaLine(data);
        if (metaLine != null) {
            previewMeta.setText(metaLine);
            previewMeta.setVisibility(View.VISIBLE);
        } else {
            previewMeta.setText("");
            previewMeta.setVisibility(View.GONE);
        }

        previewAiredOn.setText("Aired On: " + nonEmpty(data.airedOn, "Unknown"));
        previewOriginalAirDate.setText("Original Airing Date: " + nonEmpty(data.originalAirDate, data.airedOn, "Unknown"));
        previewCategory.setText("Category: " + nonEmpty(data.category, "Unknown"));
        previewChannel.setText("Channel: " + nonEmpty(data.channel, "Unknown"));
        previewDescription.setText(nonEmpty(data.description, "No description available."));

        bindPreviewThumb(meta, data);
    }

    private void bindPreviewThumb(DownloadMetadata meta, PreviewData data) {
        File explicit = asFile(data.thumbnailPath);
        File companionPoster = null;
        if (meta.getCompanionDirPath() != null) {
            companionPoster = new File(meta.getCompanionDirPath(), "poster.jpg");
        }
        File candidate = firstExisting(explicit, companionPoster);
        if (candidate != null) {
            try {
                previewThumb.setImageBitmap(BitmapFactory.decodeFile(candidate.getAbsolutePath()));
                return;
            } catch (Exception e) {
                log.warn("preview thumb decode failed for {}: {}", candidate, e.toString());
            }
        }
        previewThumb.setImageDrawable(null);
    }

    private PreviewData buildPreview(DownloadMetadata meta) {
        PreviewData out = new PreviewData();
        out.airedOn = meta.getPreviewAiredOn();
        out.originalAirDate = meta.getPreviewAiredOn();
        out.category = meta.getPreviewCategory();
        out.channel = meta.getPreviewChannel();
        out.description = meta.getPreviewDescription();
        out.thumbnailPath = meta.getPreviewThumbnailPath();
        out.seriesTitle = meta.getTitle();

        String raw = meta.getOfflineMetadataJson();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        try {
            OfflineManifestV1 manifest = OfflineManifestV1.parse(raw);
            JSONObject m = manifest.getMetadata();
            out.seriesTitle = nonEmpty(out.seriesTitle, manifest.getTitle());
            out.episodeTitle = nonEmpty(out.episodeTitle, manifest.getSubtitle());
            out.seasonNumber = positive(m.optInt("season_number", 0));
            out.episodeNumber = positive(m.optInt("episode_number", 0));
            out.airedOn = nonEmpty(out.airedOn,
                    nullIfBlank(m.optString("aired_on", null)),
                    nullIfBlank(m.optString("air_date", null)),
                    nullIfBlank(m.optString("original_air_date", null)));
            out.originalAirDate = nonEmpty(out.originalAirDate,
                    nullIfBlank(m.optString("original_air_date", null)),
                    out.airedOn);
            out.channel = nonEmpty(out.channel,
                    nullIfBlank(m.optString("channel_name", null)),
                    nullIfBlank(m.optString("channel", null)),
                    nullIfBlank(m.optString("network", null)),
                    nullIfBlank(m.optString("station", null)));
            out.description = nonEmpty(out.description, manifest.getPrimaryDescription());
            List<String> cats = manifest.getCategories();
            if (out.category == null && !cats.isEmpty()) {
                out.category = String.join(" / ", cats);
            }
            OfflineManifestV1.ImageAsset hero = manifest.pickHeroImage();
            if (hero != null) {
                String path = OfflineManifestV1.imageFileName(hero);
                if (path != null && meta.getCompanionDirPath() != null) {
                    out.thumbnailPath = new File(meta.getCompanionDirPath(), path).getAbsolutePath();
                }
            }
        } catch (Exception e) {
            log.debug("Preview metadata parse failed for {}: {}", meta.getMediaFileID(), e.toString());
        }
        return out;
    }

    private static File asFile(String p) {
        if (p == null || p.trim().isEmpty()) return null;
        return new File(p);
    }

    private static File firstExisting(File... files) {
        if (files == null) return null;
        for (File f : files) {
            if (f != null && f.exists() && f.length() > 0) return f;
        }
        return null;
    }

    private static String nullIfBlank(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static String nonEmpty(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v == null) continue;
            String t = v.trim();
            if (!t.isEmpty()) return t;
        }
        return null;
    }

    private static String buildMetaLine(PreviewData data) {
        StringBuilder sb = new StringBuilder();
        if (data.seasonNumber > 0) {
            sb.append("Season ").append(data.seasonNumber);
        }
        if (data.episodeNumber > 0) {
            if (sb.length() > 0) sb.append("  Episode ");
            else sb.append("Episode ");
            sb.append(data.episodeNumber);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static JSONObject firstObject(JSONObject root, String... keys) {
        if (root == null || keys == null) return null;
        for (String key : keys) {
            if (key == null) continue;
            JSONObject obj = root.optJSONObject(key);
            if (obj != null) return obj;
        }
        return null;
    }

    private String formatEpoch(JSONObject obj, String... keys) {
        if (obj == null || keys == null) return null;
        for (String key : keys) {
            if (key == null || !obj.has(key)) continue;
            long value = obj.optLong(key, 0L);
            if (value <= 0L) continue;
            if (value < 100000000000L) {
                value *= 1000L;
            }
            java.text.DateFormat fmt = DateFormat.getMediumDateFormat(this);
            java.text.DateFormat tfmt = DateFormat.getTimeFormat(this);
            Date d = new Date(value);
            return fmt.format(d) + " " + tfmt.format(d);
        }
        return null;
    }

    private static int positive(int value) {
        return value > 0 ? value : 0;
    }

    private static final class PreviewData {
        String seriesTitle;
        String episodeTitle;
        String episodeName;
        int seasonNumber;
        int episodeNumber;
        String airedOn;
        String originalAirDate;
        String category;
        String channel;
        String description;
        String thumbnailPath;
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
        if (overlay != null && overlay.onKeyDown(keyCode, event)) return true;
        // BACK exits the offline library
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        // MENU key on focused item shows options
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            int pos = adapter.getFocusedPosition();
            if (pos >= 0 && pos < adapter.getItemCount()) {
                Intent intent = new Intent(this, OfflineRecordingOptionsActivity.class);
                intent.putExtra(OfflineRecordingOptionsActivity.EXTRA_MEDIA_FILE_ID, adapter.getItem(pos).getMediaFileID());
                startActivity(intent);
                return true;
            }
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
        return super.onKeyUp(keyCode, event);
    }
}
