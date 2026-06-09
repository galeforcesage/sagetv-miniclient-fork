package sagex.miniclient.android.offline;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OfflineRecordingOptionsActivity extends Activity {
    private static final Logger log = LoggerFactory.getLogger(OfflineRecordingOptionsActivity.class);

    public static final String EXTRA_MEDIA_FILE_ID = "extra_media_file_id";

    private DownloadMetadata meta;
    private boolean watchedChecked;
    private boolean autoComskipChecked;
    private TextView watchedCheckbox;
    private TextView autoComskipCheckbox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_offline_options);

        String id = getIntent().getStringExtra(EXTRA_MEDIA_FILE_ID);
        if (id == null || id.isEmpty()) {
            log.warn("OfflineRecordingOptionsActivity launched without EXTRA_MEDIA_FILE_ID");
            finish();
            return;
        }

        meta = DownloadManager.getInstance(this).getRepository().getByMediaFileID(id);
        if (meta == null) {
            log.warn("No download metadata for id {}", id);
            finish();
            return;
        }

        watchedChecked = meta.isWatched();
        autoComskipChecked = meta.isAutoComskip();

        TextView title = findViewById(R.id.options_title);
        TextView subtitle = findViewById(R.id.options_subtitle);
        if (title != null) {
            title.setText("Options for " + buildRecordingLabel(true) + ":");
        }
        if (subtitle != null) {
            subtitle.setText(buildRecordingSubtitle());
        }

        LinearLayout leftColumn = findViewById(R.id.options_left_column);
        LinearLayout rightColumn = findViewById(R.id.options_right_column);
        TextView closeText = findViewById(R.id.options_close_text);

        if (leftColumn != null) {
            leftColumn.removeAllViews();
            addPrimaryAction(leftColumn, "Watch Now", R.drawable.offline_options_icon_play_bg,
                    R.drawable.ic_play_arrow_white_24dp, () -> watchNow());
            addPrimaryAction(leftColumn, "View Recording Detail", R.drawable.offline_options_icon_info_bg,
                    R.drawable.ic_info_outline_white_24dp, () -> openDetail());
            addPrimaryAction(leftColumn, "Delete this Recording", R.drawable.offline_options_icon_delete_bg,
                    R.drawable.ic_close_white_24dp, () -> confirmDelete());
        }

        if (rightColumn != null) {
            rightColumn.removeAllViews();
            addWatchedToggle(rightColumn);
            addAutoComskipToggle(rightColumn);
            addTextAction(rightColumn, "Update Recording Data...", () -> openRefreshMenu());
            addTextAction(rightColumn, "More by People/Teams in Show...", () -> showUnavailable("Related browsing is not available offline"));
        }

        if (closeText != null) {
            closeText.setOnClickListener(v -> finish());
            closeText.setOnFocusChangeListener((v, hasFocus) -> v.setSelected(hasFocus));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    private void addPrimaryAction(LinearLayout parent, String label, int iconBgRes, int iconRes, Runnable action) {
        LinearLayout row = buildRowContainer();

        TextView text = buildActionText(label);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(Color.WHITE);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setBackgroundResource(iconBgRes);
        int iconSize = dp(28);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        row.addView(icon, iconLp);

        row.setOnClickListener(v -> action.run());
        parent.addView(row, rowParams());
    }

    private void addTextAction(LinearLayout parent, String label, Runnable action) {
        LinearLayout row = buildRowContainer();

        TextView text = buildActionText(label);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(28)));
        row.addView(spacer);

        row.setOnClickListener(v -> action.run());
        parent.addView(row, rowParams());
    }

    private void addWatchedToggle(LinearLayout parent) {
        LinearLayout row = buildRowContainer();

        TextView text = buildActionText("Watched");
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        watchedCheckbox = new TextView(this);
        watchedCheckbox.setLayoutParams(new LinearLayout.LayoutParams(dp(22), dp(22)));
        watchedCheckbox.setGravity(Gravity.CENTER);
        watchedCheckbox.setTextSize(14f);
        watchedCheckbox.setTypeface(null, android.graphics.Typeface.BOLD);
        watchedCheckbox.setBackgroundResource(R.drawable.offline_options_checkbox_bg);
        row.addView(watchedCheckbox);
        updateWatchedCheckbox();

        row.setOnClickListener(v -> {
            watchedChecked = !watchedChecked;
            updateWatchedCheckbox();
            persistToggleState();
            Toast.makeText(this, watchedChecked ? "Marked watched" : "Marked unwatched", Toast.LENGTH_SHORT).show();
        });
        parent.addView(row, rowParams());
    }

    private void addAutoComskipToggle(LinearLayout parent) {
        LinearLayout row = buildRowContainer();

        TextView text = buildActionText("Auto-Comskip");
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        autoComskipCheckbox = new TextView(this);
        autoComskipCheckbox.setLayoutParams(new LinearLayout.LayoutParams(dp(22), dp(22)));
        autoComskipCheckbox.setGravity(Gravity.CENTER);
        autoComskipCheckbox.setTextSize(14f);
        autoComskipCheckbox.setTypeface(null, android.graphics.Typeface.BOLD);
        autoComskipCheckbox.setBackgroundResource(R.drawable.offline_options_checkbox_bg);
        row.addView(autoComskipCheckbox);
        updateAutoComskipCheckbox();

        row.setOnClickListener(v -> {
            autoComskipChecked = !autoComskipChecked;
            updateAutoComskipCheckbox();
            persistToggleState();
            Toast.makeText(this, autoComskipChecked ? "Auto-comskip on" : "Auto-comskip off", Toast.LENGTH_SHORT).show();
        });
        parent.addView(row, rowParams());
    }

    private void updateWatchedCheckbox() {
        if (watchedCheckbox == null) return;
        watchedCheckbox.setText(watchedChecked ? "✓" : "");
        watchedCheckbox.setTextColor(watchedChecked ? 0xFF4ED04E : 0xFFFFFFFF);
        watchedCheckbox.setAlpha(watchedChecked ? 1f : 0.85f);
    }

    private void updateAutoComskipCheckbox() {
        if (autoComskipCheckbox == null) return;
        autoComskipCheckbox.setText(autoComskipChecked ? "✓" : "");
        autoComskipCheckbox.setTextColor(autoComskipChecked ? 0xFF4ED04E : 0xFFFFFFFF);
        autoComskipCheckbox.setAlpha(autoComskipChecked ? 1f : 0.85f);
    }

    private void persistToggleState() {
        meta.setWatched(watchedChecked);
        meta.setAutoComskip(autoComskipChecked);
        DownloadManager.getInstance(this).getRepository().update(meta);
        OfflinePlaybackStateSync.syncAsync(this, meta, "watched_toggle");
    }

    private LinearLayout buildRowContainer() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(12), dp(18), dp(12));
        row.setBackgroundResource(R.drawable.offline_options_row_bg);
        row.setFocusable(true);
        row.setClickable(true);
        return row;
    }

    private TextView buildActionText(String label) {
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(Color.WHITE);
        text.setTextSize(22f);
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return text;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String buildRecordingLabel(boolean includeEpisode) {
        String title = nonEmpty(meta.getTitle(), meta.getMediaFileID(), "Recording");
        if (!includeEpisode) {
            return title;
        }
        String raw = meta.getOfflineMetadataJson();
        if (raw == null || raw.trim().isEmpty()) {
            return title;
        }
        try {
            OfflineManifestV1 manifest = OfflineManifestV1.parse(raw);
            String series = nonEmpty(manifest.getTitle(), title);
            String episode = nonEmpty(manifest.getSubtitle());
            if (episode != null && !episode.isEmpty()) {
                return series + " - \"" + episode + "\"";
            }
            return series;
        } catch (Exception e) {
            return title;
        }
    }

    private String buildRecordingSubtitle() {
        String raw = meta.getOfflineMetadataJson();
        if (raw == null || raw.trim().isEmpty()) {
            return nonEmpty(meta.getTitle(), meta.getMediaFileID(), "Recording");
        }
        try {
            OfflineManifestV1 manifest = OfflineManifestV1.parse(raw);
            String category = nonEmpty(meta.getPreviewCategory());
            String airedOn = nonEmpty(meta.getPreviewAiredOn());
            if (category != null && airedOn != null) {
                return category + " • " + airedOn;
            }
            if (category != null) return category;
            if (airedOn != null) return airedOn;
            return nonEmpty(manifest.getSubtitle(), manifest.getTitle(), meta.getMediaFileID(), "Recording");
        } catch (Exception e) {
            return nonEmpty(meta.getPreviewCategory(), meta.getPreviewAiredOn(), meta.getTitle(), meta.getMediaFileID(), "Recording");
        }
    }

    private void openRefreshMenu() {
        Intent intent = new Intent(this, OfflineRefreshMenuActivity.class);
        intent.putExtra(OfflineRefreshMenuActivity.EXTRA_MEDIA_FILE_ID, meta.getMediaFileID());
        startActivity(intent);
    }

    private void showUnavailable(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void watchNow() {
        if (meta.getStatus() != DownloadMetadata.Status.COMPLETE) {
            return;
        }
        long resumeMs = meta.getPlaybackPositionMs();
        if (resumeMs >= 3000L) {
            new AlertDialog.Builder(this)
                    .setTitle("Resume Playback")
                    .setMessage("Resume from " + formatTime(resumeMs) + "?")
                    .setPositiveButton("Resume", (d, w) -> startOfflinePlayback(resumeMs))
                    .setNegativeButton("Start at Beginning", (d, w) -> {
                        meta.setPlaybackPositionMs(0L);
                        DownloadManager.getInstance(this).getRepository().update(meta);
                        OfflinePlaybackStateSync.syncAsync(this, meta, "start_at_beginning");
                        startOfflinePlayback(0L);
                    })
                    .show();
            return;
        }
        startOfflinePlayback(0L);
    }

    private void startOfflinePlayback(long startPositionMs) {
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

    private void openDetail() {
        Intent intent = new Intent(this, OfflineDetailActivity.class);
        intent.putExtra(OfflineDetailActivity.EXTRA_MEDIA_FILE_ID, meta.getMediaFileID());
        startActivity(intent);
    }

    private void restartDownload() {
        DownloadManager.getInstance(this).restart(meta.getMediaFileID());
        Toast.makeText(this, "Restart requested", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void confirmDelete() {
        String title = nonEmpty(meta.getTitle(), meta.getMediaFileID(), "Recording");
        new AlertDialog.Builder(this, android.R.style.Theme_Holo_Dialog)
                .setTitle("Delete Recording")
                .setMessage("Delete \"" + title + "\" and its file?")
                .setPositiveButton("Delete", (d, w) -> {
                    DownloadManager.getInstance(this).cancel(meta.getMediaFileID());
                    finish();
                })
                .setNegativeButton("Keep", null)
                .show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private static String nonEmpty(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value == null) continue;
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) return trimmed;
        }
        return null;
    }
}