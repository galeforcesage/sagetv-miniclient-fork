/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package sagex.miniclient.android.offline;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * M4 — SageTV-style detail screen for a downloaded recording. Reads the
 * companion content persisted in M2 (and the binary assets fetched in M3)
 * to render a layout matching the online detail screen the SageTV server
 * sends to placeshifter clients: fanart background, poster, show/episode
 * title, description, cast headshots, director/writer/EP, categories,
 * format and file paths.
 *
 * <p>Every panel degrades gracefully when its underlying data is absent —
 * the activity is safe to launch for downloads created against an older
 * server build that doesn't yet emit the {@code offline} block.
 */
public class OfflineDetailActivity extends Activity {
    private static final Logger log = LoggerFactory.getLogger(OfflineDetailActivity.class);

    /** Intent extra: the {@link DownloadMetadata#getMediaFileID()} to display. */
    public static final String EXTRA_MEDIA_FILE_ID = "extra_media_file_id";

    private DownloadMetadata meta;
    private boolean artworkRefetchRequested;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(R.layout.activity_offline_detail);

        // Set up back button in the header bar
        View backButton = findViewById(R.id.detail_back_button);
        if (backButton != null) {
            backButton.setOnClickListener(v -> finish());
        }

        String id = getIntent().getStringExtra(EXTRA_MEDIA_FILE_ID);
        if (id == null || id.isEmpty()) {
            log.warn("OfflineDetailActivity launched without EXTRA_MEDIA_FILE_ID");
            finish();
            return;
        }
        meta = DownloadManager.getInstance(this).getRepository().getByMediaFileID(id);
        if (meta == null) {
            log.warn("No download metadata for id {}", id);
            finish();
            return;
        }
        bind();

        overlay = new OfflineNavigationOverlay(this);
    }

    private OfflineNavigationOverlay overlay;
    // PRD 5.8 validation #1: re-bind without restart when the full manifest
    // replaces the inline first-paint snapshot. DownloadManager invokes this
    // on the main thread.
    private final Runnable manifestUpdatedListener = this::onManifestUpdated;

    @Override
    protected void onResume() {
        super.onResume();
        if (overlay != null) overlay.onResume();
        if (meta != null && meta.getMediaFileID() != null) {
            DownloadManager.getInstance(this)
                    .addManifestUpdateListener(meta.getMediaFileID(), manifestUpdatedListener);
            // Re-pull metadata in case the manifest was replaced while we were
            // paused — otherwise we would have a stale snapshot until the next
            // server-driven update.
            DownloadMetadata refreshed = DownloadManager.getInstance(this)
                    .getRepository().getByMediaFileID(meta.getMediaFileID());
            if (refreshed != null) {
                meta = refreshed;
                bind();
            }
        }
    }

    @Override
    protected void onPause() {
        if (meta != null && meta.getMediaFileID() != null) {
            DownloadManager.getInstance(this)
                    .removeManifestUpdateListener(meta.getMediaFileID(), manifestUpdatedListener);
        }
        super.onPause();
    }

    private void onManifestUpdated() {
        if (isFinishing() || isDestroyed()) return;
        if (meta == null || meta.getMediaFileID() == null) return;
        DownloadMetadata refreshed = DownloadManager.getInstance(this)
                .getRepository().getByMediaFileID(meta.getMediaFileID());
        if (refreshed == null) return;
        meta = refreshed;
        bind();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (overlay != null && overlay.onKeyDown(keyCode, event)) return true;
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

    private void bind() {
        File companionDir = meta.getCompanionDirPath() != null
                ? new File(meta.getCompanionDirPath()) : null;

        bindArtwork(companionDir);
        JSONObject root = parseMetadataRoot();
        JSONObject airing  = section(root, "airing", "program", "episode");
        JSONObject mfile   = section(root, "media_file", "mediaFile", "file");
        JSONObject show    = section(root, "show", "series", "series_info");

        if (root == null) {
            log.warn("Offline detail has no metadata root for mediaFileID={} (server likely omitted offline.metadata)",
                meta.getMediaFileID());
        } else {
            log.info("Offline detail metadata for mediaFileID={} hasAiring={} hasShow={} hasMediaFile={}",
                meta.getMediaFileID(), airing != null, show != null, mfile != null);
        }

        bindHeader(airing);
        bindAiredOn(airing);
        bindMetadata(airing);
        bindHost(show);
        bindCategories(airing);
        bindCast(show, companionDir);
        bindCrew(show);
        bindFiles(mfile);

        Button play = findViewById(R.id.detail_play_button);
        if (meta.getStatus() == DownloadMetadata.Status.COMPLETE
                && meta.getLocalUri() != null && !meta.getLocalUri().isEmpty()) {
            play.setOnClickListener(v -> {
                playOffline(meta);
            });
        } else {
            play.setEnabled(false);
            play.setText(meta.getStatus() == null ? "Unavailable" : meta.getStatus().name());
        }
    }

    private void playOffline(DownloadMetadata meta) {
        long resumeMs = meta != null ? meta.getPlaybackPositionMs() : 0L;
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
        Intent i = new Intent(this, OfflinePlaybackActivity.class);
        i.putExtra(OfflinePlaybackActivity.EXTRA_MEDIA_URI, meta.getLocalUri());
        i.putExtra(OfflinePlaybackActivity.EXTRA_MEDIA_TITLE, meta.getTitle());
        i.putExtra(OfflinePlaybackActivity.EXTRA_MEDIA_FILE_ID, meta.getMediaFileID());
        i.putExtra(OfflinePlaybackActivity.EXTRA_START_POSITION_MS, startPositionMs);
        startActivity(i);
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

    private JSONObject parseMetadataRoot() {
        String json = meta.getOfflineMetadataJson();
        if (json == null || json.isEmpty()) return null;
        try {
            OfflineManifestV1 manifest = OfflineManifestV1.parse(json);
            JSONObject m = manifest.getMetadata();

            JSONObject airing = new JSONObject();
            airing.put("show_title", manifest.getTitle());
            airing.put("title", firstNonEmpty(manifest.getSubtitle(), manifest.getTitle()));
            copyIfPresent(m, airing,
                    "season_number", "episode_number", "original_air_date", "aired_on", "air_date",
                    "rated", "show_id", "first_run", "run_time_minutes", "description",
                    "recording_start_ms", "recording_end_ms",
                    "recording_start_utc", "recording_end_utc",
                    "recorded_start_ms", "recorded_end_ms", "start_ms", "end_ms",
                    "recorded_start_time", "recorded_end_time", "recorded_time",
                    "start_time", "end_time", "recording_start_time", "recording_end_time",
                    "channel_name", "channel", "network", "station", "categories", "genre", "category");

            JSONObject media = new JSONObject();
            copyIfPresent(m, media,
                    "recording_id", "media_file_id", "id", "format", "recording_files",
                    "recording_file_size", "audio_format_summary", "file_properties");

            JSONObject show = new JSONObject();
            JSONArray cast = new JSONArray();
            JSONArray director = new JSONArray();
            JSONArray writer = new JSONArray();
            JSONArray executiveProducer = new JSONArray();
            JSONArray correspondents = new JSONArray();

            Map<String, List<OfflineManifestV1.Credit>> grouped = manifest.groupCreditsByRole();
            for (Map.Entry<String, List<OfflineManifestV1.Credit>> entry : grouped.entrySet()) {
                String role = entry.getKey() == null ? "" : entry.getKey().toLowerCase();
                for (OfflineManifestV1.Credit credit : entry.getValue()) {
                    JSONObject person = new JSONObject();
                    if (credit.personId != null) person.put("person_id", credit.personId);
                    if (credit.personName != null) person.put("name", credit.personName);
                    if (credit.roleName != null) person.put("role", credit.roleName);
                    if (role.contains("director")) {
                        director.put(person);
                    } else if (role.contains("writer")) {
                        writer.put(person);
                    } else if (role.contains("executive producer")) {
                        executiveProducer.put(person);
                    } else if (role.contains("correspondent") || role.contains("anchor") || role.contains("host")) {
                        correspondents.put(person);
                        cast.put(person);
                    } else {
                        cast.put(person);
                    }
                }
            }
            show.put("cast", cast);
            show.put("director", director);
            show.put("writer", writer);
            show.put("executive_producer", executiveProducer);
            if (correspondents.length() > 0) {
                show.put("correspondents", correspondents);
                JSONObject first = correspondents.optJSONObject(0);
                if (first != null) {
                    show.put("correspondent", first.optString("name", ""));
                }
            }

            JSONObject root = new JSONObject();
            root.put("airing", airing);
            root.put("media_file", media);
            root.put("show", show);
            root.put("manifest", manifest.getRoot());
            return root;
        } catch (Exception e) {
            log.warn("manifest parse failed for {}: {}",
                    meta.getMediaFileID(), e.toString());
            return null;
        }
    }

    private static void copyIfPresent(JSONObject from, JSONObject to, String... keys) {
        if (from == null || to == null || keys == null) return;
        for (String key : keys) {
            if (key == null || key.isEmpty()) continue;
            Object value = from.opt(key);
            if (value == null || value == JSONObject.NULL) continue;
            try {
                to.put(key, value);
            } catch (Exception ignored) {
                // Ignore malformed field values and keep rendering best-effort.
            }
        }
    }

    private void bindArtwork(File companionDir) {
        ImageView fanart = findViewById(R.id.detail_fanart);
        ImageView poster = findViewById(R.id.detail_poster);
        boolean fanartBound = false;
        boolean posterBound = false;

        if (companionDir != null && companionDir.isDirectory()) {
            File fanartFile = firstExisting(
                    new File(companionDir, "fanart.jpg"),
                    new File(companionDir, "banner.jpg"),
                    firstImageMatching(companionDir, "fanart", "banner", "background"));
            fanartBound = setImageFromFile(fanart, fanartFile, "fanart");

            File posterFile = firstExisting(
                    new File(companionDir, "poster.jpg"),
                    new File(companionDir, "thumbnail.jpg"),
                    asFile(meta.getPreviewThumbnailPath()),
                    firstImageMatching(companionDir, "poster", "thumbnail", "cover"));
            if (posterFile == null) {
                posterFile = firstImageInDir(new File(companionDir, "cast"));
            }
            posterBound = setImageFromFile(poster, posterFile, "poster");
        } else {
            posterBound = setImageFromFile(poster, asFile(meta.getPreviewThumbnailPath()), "poster-preview");
        }

        if (!fanartBound) {
            fanart.setImageDrawable(null);
        }
        if (!posterBound) {
            poster.setImageDrawable(null);
        }

        if (!posterBound && !artworkRefetchRequested
                && meta.getArtworkManifestJson() != null
                && !meta.getArtworkManifestJson().trim().isEmpty()) {
            artworkRefetchRequested = true;
            log.info("detail_artwork_missing triggering artwork sidecar refresh for {}",
                    meta.getMediaFileID());
            DownloadManager.getInstance(this).executeAction(
                    meta.getMediaFileID(),
                    DownloadManager.ActionOptions.builder().refreshArtwork(true).build());
            fanart.postDelayed(this::rebindFromRepositoryIfAlive, 1500L);
            fanart.postDelayed(this::rebindFromRepositoryIfAlive, 4000L);
        }
    }

    private void rebindFromRepositoryIfAlive() {
        if (isFinishing() || isDestroyed() || meta == null || meta.getMediaFileID() == null) {
            return;
        }
        DownloadMetadata refreshed = DownloadManager.getInstance(this)
                .getRepository().getByMediaFileID(meta.getMediaFileID());
        if (refreshed != null) {
            meta = refreshed;
            bind();
        }
    }

    private static File asFile(String path) {
        if (path == null) return null;
        String p = path.trim();
        if (p.isEmpty()) return null;
        File f = new File(p);
        return f.exists() && f.length() > 0 ? f : null;
    }

    private static File firstExisting(File... candidates) {
        if (candidates == null) return null;
        for (File candidate : candidates) {
            if (candidate != null && candidate.exists() && candidate.length() > 0) {
                return candidate;
            }
        }
        return null;
    }

    private static File firstImageMatching(File dir, String... containsTokens) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (file == null || !file.isFile() || file.length() <= 0) continue;
            String name = file.getName();
            if (!isImageName(name)) continue;
            String lower = name.toLowerCase(Locale.US);
            if (containsTokens != null) {
                for (String token : containsTokens) {
                    if (token != null && !token.isEmpty() && lower.contains(token.toLowerCase(Locale.US))) {
                        return file;
                    }
                }
            }
        }
        for (File file : files) {
            if (file == null || !file.isFile() || file.length() <= 0) continue;
            if (isImageName(file.getName())) {
                return file;
            }
        }
        return null;
    }

    private static File firstImageInDir(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (file != null && file.isFile() && file.length() > 0 && isImageName(file.getName())) {
                return file;
            }
        }
        return null;
    }

    private static boolean isImageName(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.US);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp");
    }

    private boolean setImageFromFile(ImageView view, File file, String label) {
        if (view == null || file == null || !file.exists() || file.length() <= 0) return false;
        try {
            if (BitmapFactory.decodeFile(file.getAbsolutePath()) != null) {
                view.setImageBitmap(BitmapFactory.decodeFile(file.getAbsolutePath()));
                return true;
            }
        } catch (Exception e) {
            log.warn("{} decode failed for {}: {}", label, file.getAbsolutePath(), e.toString());
        }
        return false;
    }

    private void bindHeader(JSONObject airing) {
        TextView showTitle    = findViewById(R.id.detail_show_title);
        TextView episodeTitle = findViewById(R.id.detail_episode_title);
        TextView subline      = findViewById(R.id.detail_subline);
        TextView description  = findViewById(R.id.detail_description);

        String show = airing != null ? firstNonEmpty(
            airing.optString("show_title", null),
            airing.optString("series_title", null),
            airing.optString("series", null)) : null;
        if ((show == null || show.isEmpty())) {
            JSONObject root = parseMetadataRoot();
            JSONObject showObj = section(root, "show", "series", "series_info");
            if (showObj != null) {
            show = firstNonEmpty(
                showObj.optString("title", null),
                showObj.optString("name", null),
                showObj.optString("series_title", null));
            }
        }
        String title = airing != null ? firstNonEmpty(
            airing.optString("title", null),
            airing.optString("episode_title", null),
            airing.optString("episode", null)) : null;
        if ((title == null || title.isEmpty()) && meta.getTitle() != null) {
            title = meta.getTitle();
        }
        if (show != null && !show.isEmpty()) {
            showTitle.setText(show);
            showTitle.setVisibility(View.VISIBLE);
        } else {
            showTitle.setVisibility(View.GONE);
        }
        episodeTitle.setText(title != null ? title : meta.getMediaFileID());

        StringBuilder line = new StringBuilder();
        if (airing != null) {
            int s = airing.optInt("season_number", 0);
            int e = airing.optInt("episode_number", 0);
            if (s > 0 && e > 0) {
                line.append("S").append(String.format("%02d", s))
                    .append("E").append(String.format("%02d", e));
            }

                    long recordedOnMs = firstPositiveLong(
                        airing.optLong("recorded_start_ms", 0),
                        airing.optLong("recorded_end_ms", 0),
                        parseEpochMillis(airing.optString("recorded_start_time", null)),
                        parseEpochMillis(airing.optString("recorded_end_time", null)),
                        airing.optLong("start_ms", 0));
                String recordedOn = recordedOnMs > 0 ? formatEpochDate(recordedOnMs) : null;
                if (recordedOn != null && !recordedOn.isEmpty()) {
                if (line.length() > 0) line.append("  ·  ");
                    line.append(recordedOn);
            }
            String channel = firstNonEmpty(
                    airing.optString("channel_name", null),
                    airing.optString("channel", null),
                    airing.optString("network", null),
                    airing.optString("station", null));
            if ((channel == null || channel.isEmpty())) {
                JSONObject ch = airing.optJSONObject("channel");
                if (ch != null) {
                    channel = firstNonEmpty(ch.optString("name", null), ch.optString("title", null));
                }
            }
            if (channel != null && !channel.isEmpty()) {
                if (line.length() > 0) line.append("  ·  ");
                line.append(channel);
            }
            String rated = airing.optString("rated", null);
            if (rated != null && !rated.isEmpty()) {
                if (line.length() > 0) line.append("  ·  ");
                line.append(rated);
            }
            int runtime = airing.optInt("run_time_minutes", 0);
            if (runtime > 0) {
                if (line.length() > 0) line.append("  ·  ");
                line.append(runtime).append(" min");
            }
        }
        subline.setText(line.toString());
        subline.setVisibility(line.length() > 0 ? View.VISIBLE : View.GONE);

        String desc = airing != null ? firstNonEmpty(
                airing.optString("description", null),
                airing.optString("desc", null),
                airing.optString("summary", null)) : null;
        if ((desc == null || desc.isEmpty())) {
            JSONObject root = parseMetadataRoot();
            JSONObject showObj = root != null ? root.optJSONObject("show") : null;
            if (showObj != null) {
                desc = firstNonEmpty(
                        showObj.optString("description", null),
                        showObj.optString("desc", null),
                    showObj.optString("summary", null),
                    showObj.optString("overview", null));
            }
        }
        if (desc != null && !desc.isEmpty()) {
            description.setText(desc);
            description.setVisibility(View.VISIBLE);
        } else {
            description.setVisibility(View.GONE);
        }
    }

    private void bindAiredOn(JSONObject airing) {
        TextView v = findViewById(R.id.detail_aired_on);
        if (airing == null) {
            v.setVisibility(View.GONE);
            return;
        }

        StringBuilder sb = new StringBuilder();

        long startMs = firstPositiveLong(
                airing.optLong("start_ms", 0),
                parseEpochMillis(airing.optString("start_time", null)));
        long endMs = firstPositiveLong(
                airing.optLong("end_ms", 0),
                parseEpochMillis(airing.optString("end_time", null)));
        long recordedStartMs = firstPositiveLong(
                airing.optLong("recording_start_ms", 0),
                parseIso8601(airing.optString("recording_start_utc", null)),
                airing.optLong("recorded_start_ms", 0),
                parseEpochMillis(airing.optString("recorded_start_time", null)),
                parseEpochMillis(airing.optString("recording_start_time", null)),
                parseEpochMillis(airing.optString("recorded_time", null)));
        long recordedEndMs = firstPositiveLong(
                airing.optLong("recording_end_ms", 0),
                parseIso8601(airing.optString("recording_end_utc", null)),
            parseEpochMillis(airing.optString("recorded_end_time", null)),
            parseEpochMillis(airing.optString("recording_end_time", null)));

        // Aired On should show recorded date, then time range.
        long primaryDateMs = firstPositiveLong(recordedStartMs, startMs);
        String primaryDate = formatDisplayDate(primaryDateMs);
        if (primaryDate != null && !primaryDate.isEmpty()) {
            sb.append(primaryDate).append("\n");
        }

        long displayStartMs = firstPositiveLong(recordedStartMs, startMs);
        long displayEndMs = firstPositiveLong(recordedEndMs, endMs);
        int runtime = airing.optInt("run_time_minutes", 0);
        if (displayStartMs > 0 && displayEndMs <= 0 && runtime > 0) {
            displayEndMs = displayStartMs + (runtime * 60_000L);
        }

        String startTime = formatDisplayTime(displayStartMs);
        String endTime = formatDisplayTime(displayEndMs);
        if (startTime != null && endTime != null) {
            sb.append(startTime).append(" - ").append(endTime).append("\n");
        }

        String duration = null;
        if (displayStartMs > 0 && displayEndMs > displayStartMs) {
            duration = formatDurationMinutes((displayEndMs - displayStartMs) / 60000L);
        } else {
            if (runtime > 0) {
                duration = formatDurationMinutes(runtime);
            }
        }
        if (duration != null) {
            sb.append(duration).append("\n");
        }

        String channel = firstNonEmpty(
                airing.optString("channel_number", null),
                airing.optString("channel_name", null),
                airing.optString("channel", null),
                airing.optString("station", null));
        String rated = airing.optString("rated", null);
        if (rated != null && !rated.isEmpty()) {
            sb.append("- ").append(rated).append("\n");
        } else if (channel != null && !channel.isEmpty()) {
            sb.append(channel).append("\n");
        }

        if (airing.optBoolean("closed_captioned", false)) {
            sb.append("Closed Captioned");
        }

        if (sb.length() > 0) {
            v.setText(sb.toString().trim());
            v.setVisibility(View.VISIBLE);
        } else {
            v.setVisibility(View.GONE);
        }
    }
    private void bindHost(JSONObject show) {
        TextView v = findViewById(R.id.detail_host);
        if (show == null) {
            v.setVisibility(View.GONE);
            return;
        }
        
        String host = firstNonEmpty(
                show.optString("host", null),
                show.optString("host_name", null),
                show.optString("presenter", null),
                show.optString("presenters", null),
                show.optString("correspondent", null),
                show.optString("correspondents", null));
        
        if (host != null && !host.isEmpty()) {
            String label = (show.has("correspondent") || show.has("correspondents"))
                    ? "Correspondent: "
                    : "Host: ";
            v.setText(label + host);
            v.setVisibility(View.VISIBLE);
        } else {
            v.setVisibility(View.GONE);
        }
    }
    private void bindMetadata(JSONObject airing) {
        TextView v = findViewById(R.id.detail_metadata);
        if (airing == null) {
            v.setVisibility(View.GONE);
            return;
        }
        StringBuilder sb = new StringBuilder();

        // Category
        String category = firstNonEmpty(
            airing.optString("category", null),
            airing.optString("genre", null),
            joinArray(airing.optJSONArray("categories")),
            joinArray(airing.optJSONArray("genres")));
        if (airing.optBoolean("first_run", false)) {
            category = firstNonEmpty(category + " - First Run", "First Run");
        }
        if (category != null && !category.isEmpty()) {
            sb.append("Category: ").append(category).append("\n");
        }

        String originalAirDate = firstNonEmpty(
            airing.optString("original_air_date", null),
            airing.optString("originalAirDate", null));
        if (originalAirDate != null && !originalAirDate.isEmpty()) {
            sb.append("Original Air Date: ")
                    .append(formatDisplayDate(parseEpochMillisOrDate(originalAirDate), originalAirDate))
                    .append("\n");
        }

        int season = airing.optInt("season_number", 0);
        int episode = airing.optInt("episode_number", 0);
        if (season > 0 && episode > 0) {
            sb.append("Season ").append(season).append(", Episode ").append(episode).append("\n");
        }

        // Rated
        String rated = airing.optString("rated", null);
        if (rated != null && !rated.isEmpty()) {
            sb.append("Rated: ").append(rated).append("\n");
        }

        // ShowID
        String showId = firstNonEmpty(
                airing.optString("show_id", null),
                airing.optString("showid", null),
                airing.optString("id", null));
        if (showId != null && !showId.isEmpty()) {
            sb.append("ShowID: ").append(showId).append("\n");
        }

        JSONObject root = parseMetadataRoot();
        JSONObject manifestRoot = root != null ? root.optJSONObject("manifest") : null;
        if (manifestRoot != null) {
            try {
                OfflineManifestV1 manifest = OfflineManifestV1.parse(manifestRoot.toString());
                List<OfflineManifestV1.MetadataEntry> remaining = manifest.getRemainingMetadataEntries();
                if (!remaining.isEmpty()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append("More Details:\n");
                    for (OfflineManifestV1.MetadataEntry entry : remaining) {
                        String label = entry.label != null ? entry.label.trim().toLowerCase(java.util.Locale.US) : "";
                        if ("original air date".equals(label)
                                || "aired on".equals(label)
                                || "season".equals(label)
                                || "episode".equals(label)) {
                            continue;
                        }
                        sb.append(entry.label).append(": ").append(entry.value).append("\n");
                    }
                }
            } catch (Exception ignored) {
                // Keep existing best-effort panel when manifest parse fails.
            }
        }

        if (sb.length() > 0) {
            v.setText(sb.toString().trim());
            v.setVisibility(View.VISIBLE);
        } else {
            v.setVisibility(View.GONE);
        }
    }

    private String formatEpochDateTime(long ms) {
        if (ms <= 0) return null;
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("EEEE, MMMM d, yyyy");
        return sdf.format(new java.util.Date(ms));
    }

    private String formatEpochTime(long ms) {
        if (ms <= 0) return null;
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("h:mm  a");
        return sdf.format(new java.util.Date(ms));
    }

    private void bindCategories(JSONObject airing) {
        TextView v = findViewById(R.id.detail_categories);
        JSONArray arr = airing != null ? firstNonNullArray(
            airing.optJSONArray("categories"),
            airing.optJSONArray("genres")) : null;
        String scalar = airing != null ? firstNonEmpty(
                airing.optString("category", null),
            airing.optString("genre", null),
            airing.optString("genres", null)) : null;
        if ((arr == null || arr.length() == 0) && (scalar == null || scalar.isEmpty())) {
            v.setVisibility(View.GONE);
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String c = arr.optString(i, "");
                if (c == null || c.trim().isEmpty()) continue;
                if (sb.length() > 0) sb.append("  ·  ");
                sb.append(c);
            }
        }
        if (sb.length() == 0 && scalar != null && !scalar.isEmpty()) {
            sb.append(scalar);
        }
        if (sb.length() == 0) {
            v.setVisibility(View.GONE);
            return;
        }
        v.setText(sb.toString());
        v.setVisibility(View.VISIBLE);
    }

    private void bindCast(JSONObject show, File companionDir) {
        TextView header = findViewById(R.id.detail_cast_header);
        RecyclerView list = findViewById(R.id.detail_cast_list);
        JSONArray cast = show != null ? firstNonNullArray(
            show.optJSONArray("cast"),
            show.optJSONArray("actors"),
            show.optJSONArray("people")) : null;
        if (cast == null || cast.length() == 0) {
            header.setVisibility(View.GONE);
            list.setVisibility(View.GONE);
            return;
        }
        header.setVisibility(View.VISIBLE);
        list.setVisibility(View.VISIBLE);
        List<CastMember> members = new ArrayList<>();
        for (int i = 0; i < cast.length(); i++) {
            JSONObject m = cast.optJSONObject(i);
            if (m == null) {
                String asText = cast.optString(i, null);
                if (asText == null || asText.isEmpty()) continue;
                CastMember sm = new CastMember();
                sm.name = asText;
                sm.role = "";
                members.add(sm);
                continue;
            }
            CastMember cm = new CastMember();
            cm.personId = firstNonEmpty(m.optString("person_id", null), m.optString("id", null));
            cm.name     = firstNonEmpty(m.optString("name", null), m.optString("person", null));
                cm.role     = firstNonEmpty(
                    m.optString("role", null),
                    m.optString("character", null),
                    m.optString("job", null));
            if (cm.personId != null && companionDir != null) {
                File h = new File(new File(companionDir, "cast"), cm.personId + ".jpg");
                if (h.exists() && h.length() > 0) cm.headshotPath = h.getAbsolutePath();
            }
            members.add(cm);
        }
        list.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        list.setAdapter(new CastAdapter(this, members));
    }

    private void bindCrew(JSONObject show) {
        TextView v = findViewById(R.id.detail_crew);
        if (show == null) { v.setVisibility(View.GONE); return; }
        StringBuilder sb = new StringBuilder();
        appendCrewLine(sb, "Director", firstNonNullArray(
            show.optJSONArray("director"),
            show.optJSONArray("directors")));
        appendCrewLine(sb, "Writer", firstNonNullArray(
            show.optJSONArray("writer"),
            show.optJSONArray("writers")));
        appendCrewLine(sb, "Executive Producer", firstNonNullArray(
            show.optJSONArray("executive_producer"),
            show.optJSONArray("executiveProducer"),
            show.optJSONArray("executive_producers")));
        if (sb.length() == 0) {
            v.setVisibility(View.GONE);
        } else {
            v.setText(sb.toString());
            v.setVisibility(View.VISIBLE);
        }
    }

    private static void appendCrewLine(StringBuilder sb, String label, JSONArray arr) {
        if (arr == null || arr.length() == 0) return;
        
        // First pass: check if there's any valid data
        List<String> names = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            String name;
            if (p == null) {
                name = arr.optString(i, "");
            } else {
                name = p.optString("name", "");
            }
            if (name != null && !name.trim().isEmpty()) {
                names.add(name.trim());
            }
        }
        
        // Only append if there are valid names
        if (names.isEmpty()) return;
        
        if (sb.length() > 0) sb.append('\n');
        sb.append(label).append(": ");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(names.get(i));
        }
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v == null) continue;
            String t = v.trim();
            if (!t.isEmpty()) return t;
        }
        return null;
    }

    private static JSONObject section(JSONObject root, String... keys) {
        if (root == null || keys == null) return null;
        for (String key : keys) {
            if (key == null || key.isEmpty()) continue;
            JSONObject obj = root.optJSONObject(key);
            if (obj != null) return obj;
        }
        return null;
    }

    private static JSONArray firstNonNullArray(JSONArray... arrays) {
        if (arrays == null) return null;
        for (JSONArray arr : arrays) {
            if (arr != null) return arr;
        }
        return null;
    }

    private void bindFiles(JSONObject mfile) {
        TextView header = findViewById(R.id.detail_files_header);
        TextView v = findViewById(R.id.detail_files);
        StringBuilder sb = new StringBuilder();
        
        // Server file paths
        if (mfile != null) {
            JSONArray files = mfile.optJSONArray("recording_files");
            if (files != null && files.length() > 0) {
                sb.append("Files:\n");
                for (int i = 0; i < files.length(); i++) {
                    String path = files.optString(i, "");
                    if (path != null && !path.isEmpty()) {
                        sb.append("  [").append(toHumanReadablePath(path)).append("]\n");
                    }
                }
            }
        }

        // Local file path
        if (meta.getLocalUri() != null && !meta.getLocalUri().isEmpty()) {
            if (sb.length() == 0) {
                sb.append("Files:\n");
            }
            sb.append("  [Local: ").append(toHumanReadablePath(meta.getLocalUri())).append("]\n");
        }

        // Format information
        if (mfile != null) {
            String format = mfile.optString("format", null);
            if (format != null && !format.isEmpty()) {
                sb.append("\nFormat: ").append(format).append("\n");
            }
        }

        // Size info: prefer server recording file size from metadata when available.
        long recordingFileSize = mfile != null ? mfile.optLong("recording_file_size", 0L) : 0L;
        if (recordingFileSize > 0) {
            sb.append("\nRecording File Size: ").append(formatSize(recordingFileSize)).append("\n");
        }
        if (meta.getFileSize() > 0) {
            sb.append("Transfer File Size: ").append(formatSize(meta.getFileSize()));
            if (meta.getDownloadedBytes() != meta.getFileSize()) {
                sb.append(" (Downloaded: ").append(formatSize(meta.getDownloadedBytes())).append(")");
            }
            sb.append("\n");
        } else if (meta.getDownloadedBytes() > 0) {
            sb.append("Downloaded: ").append(formatSize(meta.getDownloadedBytes())).append("\n");
        }

        if (sb.length() == 0) {
            header.setVisibility(View.GONE);
            v.setVisibility(View.GONE);
        } else {
            header.setVisibility(View.VISIBLE);
            v.setText(sb.toString().trim());
            v.setVisibility(View.VISIBLE);
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes / 1024.0;
        if (v < 1024) return String.format("%.1f KB", v);
        v /= 1024;
        if (v < 1024) return String.format("%.1f MB", v);
        v /= 1024;
        return String.format("%.2f GB", v);
    }

    private static String formatEpochDate(long ms) {
        if (ms <= 0) return null;
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
        return sdf.format(new java.util.Date(ms));
    }

    private static long firstPositiveLong(long... values) {
        if (values == null) return 0L;
        for (long v : values) {
            if (v > 0) return v;
        }
        return 0L;
    }

    private static long parseEpochMillis(String raw) {
        if (raw == null) return 0L;
        String t = raw.trim();
        if (t.isEmpty()) return 0L;
        try {
            return Long.parseLong(t);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static long parseIso8601(String raw) {
        if (raw == null || raw.trim().isEmpty()) return 0L;
        String t = raw.trim();
        try {
            // Handle Z suffix — replace with +00:00 for SimpleDateFormat
            if (t.endsWith("Z")) {
                t = t.substring(0, t.length() - 1) + "+0000";
            }
            String[] patterns = {"yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd'T'HH:mm:ss.SSSZ"};
            for (String p : patterns) {
                try {
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(p, java.util.Locale.US);
                    sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                    java.util.Date d = sdf.parse(t);
                    if (d != null) return d.getTime();
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return 0L;
    }

    private static long parseEpochMillisOrDate(String raw) {
        long direct = parseEpochMillis(raw);
        if (direct > 0L) {
            // Convert epoch seconds to milliseconds when needed.
            return direct < 100000000000L ? direct * 1000L : direct;
        }
        if (raw == null) return 0L;
        String t = raw.trim();
        if (t.isEmpty()) return 0L;

        String[] patterns = new String[] {
                "EEEE, MMMM d, yyyy",
                "MMMM d, yyyy",
                "yyyy-MM-dd",
                "yyyy/MM/dd",
                "yyyy-MM-dd'T'HH:mm:ssX",
                "yyyy-MM-dd'T'HH:mm:ss.SSSX"
        };
        for (String pattern : patterns) {
            try {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(pattern, java.util.Locale.US);
                sdf.setLenient(true);
                java.util.Date d = sdf.parse(t);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {
                // Try next pattern.
            }
        }
        return 0L;
    }

    private static String formatDisplayDate(long ms) {
        if (ms <= 0L) return null;
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", java.util.Locale.US);
        return sdf.format(new java.util.Date(ms));
    }

    private static String formatDisplayDate(long ms, String fallbackRaw) {
        String formatted = formatDisplayDate(ms);
        if (formatted != null && !formatted.isEmpty()) {
            return formatted;
        }
        if (fallbackRaw == null) return "";
        return fallbackRaw.trim();
    }

    private static String formatDisplayTime(long ms) {
        if (ms <= 0L) return null;
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.US);
        return sdf.format(new java.util.Date(ms));
    }

    private static String formatDurationMinutes(long minutes) {
        if (minutes <= 0L) return null;
        if (minutes == 60L) return "1 hour";
        if (minutes % 60L == 0L) {
            long hours = minutes / 60L;
            return hours + (hours == 1L ? " hour" : " hours");
        }
        return minutes + " minutes";
    }

    private static String toHumanReadablePath(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        try {
            String decoded = raw;
            if (decoded.startsWith("file://") || decoded.startsWith("content://")) {
                Uri uri = Uri.parse(decoded);
                if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                    decoded = uri.getPath();
                } else {
                    decoded = Uri.decode(decoded);
                }
            } else {
                decoded = URLDecoder.decode(decoded, StandardCharsets.UTF_8.name());
            }
            return decodeAsciiEscapes(decoded);
        } catch (Exception e) {
            return decodeAsciiEscapes(raw);
        }
    }

    private static String decodeAsciiEscapes(String input) {
        if (input == null || input.isEmpty()) return input;
        StringBuilder out = new StringBuilder(input.length());
        for (int i = 0; i < input.length();) {
            char c = input.charAt(i);

            // Java-style \\uXXXX and \\xXX escapes.
            if (c == '\\' && i + 1 < input.length()) {
                char n = input.charAt(i + 1);
                if (n == 'u' && i + 5 < input.length()) {
                    int code = parseDigits(input, i + 2, 4, 16);
                    if (code >= 0) {
                        out.append((char) code);
                        i += 6;
                        continue;
                    }
                }
                if (n == 'x' && i + 3 < input.length()) {
                    int code = parseDigits(input, i + 2, 2, 16);
                    if (code >= 0) {
                        out.append((char) code);
                        i += 4;
                        continue;
                    }
                }
                // Octal escape: \141
                if (n >= '0' && n <= '7') {
                    int max = Math.min(i + 4, input.length());
                    int j = i + 1;
                    while (j < max && input.charAt(j) >= '0' && input.charAt(j) <= '7') {
                        j++;
                    }
                    if (j > i + 1) {
                        int code = parseDigits(input, i + 1, j - (i + 1), 8);
                        if (code >= 0) {
                            out.append((char) code);
                            i = j;
                            continue;
                        }
                    }
                }
            }

            // HTML numeric entities: &#65; or &#x41;
            if (c == '&' && i + 3 < input.length() && input.charAt(i + 1) == '#') {
                int semi = input.indexOf(';', i + 2);
                if (semi > 0) {
                    String token = input.substring(i + 2, semi);
                    int radix = 10;
                    if (token.startsWith("x") || token.startsWith("X")) {
                        token = token.substring(1);
                        radix = 16;
                    }
                    try {
                        int code = Integer.parseInt(token, radix);
                        if (code >= 0) {
                            out.append((char) code);
                            i = semi + 1;
                            continue;
                        }
                    } catch (NumberFormatException ignored) {
                        // Keep original text when parsing fails.
                    }
                }
            }

            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static int parseDigits(String s, int start, int len, int radix) {
        if (s == null || start < 0 || len <= 0 || start + len > s.length()) return -1;
        try {
            return Integer.parseInt(s.substring(start, start + len), radix);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String joinArray(JSONArray arr) {
        if (arr == null || arr.length() == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            String item = arr.optString(i, null);
            if (item == null) continue;
            String t = item.trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" / ");
            sb.append(t);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    // ---- cast adapter ----

    private static final class CastMember {
        String personId;
        String name;
        String role;
        String headshotPath;
    }

    private static final class CastAdapter extends RecyclerView.Adapter<CastVH> {
        private final Context ctx;
        private final List<CastMember> items;
        CastAdapter(Context ctx, List<CastMember> items) { this.ctx = ctx; this.items = items; }
        @Override
        public CastVH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(ctx).inflate(R.layout.item_offline_cast, parent, false);
            return new CastVH(v);
        }
        @Override
        public void onBindViewHolder(CastVH h, int pos) {
            CastMember m = items.get(pos);
            h.name.setText(m.name != null ? m.name : "");
            h.role.setText(m.role != null ? m.role : "");
            if (m.headshotPath != null) {
                try {
                    h.headshot.setImageBitmap(BitmapFactory.decodeFile(m.headshotPath));
                } catch (Exception ignored) { /* keep placeholder */ }
            } else {
                h.headshot.setImageDrawable(null);
            }
        }
        @Override public int getItemCount() { return items.size(); }
    }

    private static final class CastVH extends RecyclerView.ViewHolder {
        final ImageView headshot;
        final TextView name;
        final TextView role;
        CastVH(View v) {
            super(v);
            headshot = v.findViewById(R.id.cast_headshot);
            name     = v.findViewById(R.id.cast_name);
            role     = v.findViewById(R.id.cast_role);
        }
    }
}
