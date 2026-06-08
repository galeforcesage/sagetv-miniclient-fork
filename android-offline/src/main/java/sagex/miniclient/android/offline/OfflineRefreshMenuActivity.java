package sagex.miniclient.android.offline;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sagex.miniclient.MiniClient;
import sagex.miniclient.android.MiniclientApplication;
import sagex.miniclient.android.prefs.AndroidPrefStore;
import sagex.miniclient.prefs.PrefStore;

/**
 * Full-screen submenu with two sections:
 * 1) Download sidecar refresh selections + Refresh button
 * 2) Remux format selections + Remux button
 */
public class OfflineRefreshMenuActivity extends Activity {
    private static final Logger log = LoggerFactory.getLogger(OfflineRefreshMenuActivity.class);

    public static final String EXTRA_MEDIA_FILE_ID = "extra_media_file_id";

    private DownloadMetadata meta;

    // Download checkbox state
    private boolean checkMetadata = true;
    private boolean checkArtwork = true;
    private boolean checkCaptions = false;
    private boolean checkComskip = false;
    private boolean checkTranscript = false;

    // Download checkbox indicators
    private TextView cbMetadata;
    private TextView cbArtwork;
    private TextView cbCaptions;
    private TextView cbComskip;
    private TextView cbTranscript;

    // Remux selection: null = none
    private String selectedRemuxFormat = null;

    // Remux radio indicators
    private TextView rbNone;
    private TextView rbMkv;
    private TextView rbDvd;
    private TextView rbMpegTs;

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

        String id = getIntent().getStringExtra(EXTRA_MEDIA_FILE_ID);
        if (id == null || id.isEmpty()) {
            finish();
            return;
        }

        meta = DownloadManager.getInstance(this).getRepository().getByMediaFileID(id);
        if (meta == null) {
            finish();
            return;
        }

        // Use current app setting as initial remux selection.
        selectedRemuxFormat = readInitialRemuxSelection();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D1117);

        root.addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        View topDivider = divider();
        root.addView(topDivider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(16), dp(24), dp(16));

        buildDownloadsSection(content);
        content.addView(sectionSpacer());
        buildRemuxSection(content);

        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f));

        View bottomDivider = divider();
        root.addView(bottomDivider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1));
        root.addView(buildFooter(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(0xFF161B22);
        header.setPadding(dp(24), dp(20), dp(24), dp(20));

        TextView titleView = new TextView(this);
        titleView.setText("Update Recording Data");
        titleView.setTextColor(0xFF58A6FF);
        titleView.setTextSize(22f);
        titleView.setTypeface(null, Typeface.BOLD);
        header.addView(titleView);

        TextView subtitleView = new TextView(this);
        String label = meta.getTitle() != null ? meta.getTitle() : meta.getMediaFileID();
        subtitleView.setText(label);
        subtitleView.setTextColor(0xFFCDD9E5);
        subtitleView.setTextSize(14f);
        subtitleView.setPadding(0, dp(4), 0, 0);
        header.addView(subtitleView);

        return header;
    }

    private View buildFooter() {
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setBackgroundColor(0xFF161B22);
        footer.setPadding(dp(24), dp(16), dp(24), dp(16));
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

        Button cancelBtn = new Button(this);
        cancelBtn.setText("Close");
        cancelBtn.setTextColor(0xFFCDD9E5);
        cancelBtn.setBackgroundColor(0xFF21262D);
        cancelBtn.setOnClickListener(v -> finish());
        footer.addView(cancelBtn);

        return footer;
    }

    private void buildDownloadsSection(LinearLayout parent) {
        parent.addView(sectionLabel("Downloads"));
        parent.addView(sectionHint("Choose sidecar items, then refresh this recording."));

        cbMetadata = addCheckRow(parent, "Recording Metadata",
                "Title, description, season/episode, cast, categories",
                checkMetadata, v -> {
                    checkMetadata = !checkMetadata;
                    updateCb(cbMetadata, checkMetadata);
                });

        cbArtwork = addCheckRow(parent, "Artwork",
                "Poster, fanart, thumbnails, cast headshots",
                checkArtwork, v -> {
                    checkArtwork = !checkArtwork;
                    updateCb(cbArtwork, checkArtwork);
                });

        cbCaptions = addCheckRow(parent, "Captions",
                "Closed caption / subtitle files",
                checkCaptions, v -> {
                    checkCaptions = !checkCaptions;
                    updateCb(cbCaptions, checkCaptions);
                });

        cbComskip = addCheckRow(parent, "Comskip / Commercial Skip",
                "Commercial skip EDL data",
                checkComskip, v -> {
                    checkComskip = !checkComskip;
                    updateCb(cbComskip, checkComskip);
                });

        cbTranscript = addCheckRow(parent, "Transcript",
                "Speech-to-text transcript",
                checkTranscript, v -> {
                    checkTranscript = !checkTranscript;
                    updateCb(cbTranscript, checkTranscript);
                });

        Button refreshBtn = primarySectionButton("Refresh Downloads");
        refreshBtn.setOnClickListener(v -> runRefresh());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        parent.addView(refreshBtn, lp);
    }

    private void buildRemuxSection(LinearLayout parent) {
        parent.addView(sectionLabel("Muxing"));
        parent.addView(sectionHint("Choose remux output format, then apply."));

        rbNone = addRadioRow(parent, "None",
                "Disable fixed remuxing",
                null, v -> selectRemuxFormat(null));

        rbMkv = addRadioRow(parent, "Matroska (MKV)",
                "Use Matroska container format",
                "matroska", v -> selectRemuxFormat("matroska"));

        rbDvd = addRadioRow(parent, "DVD (MPEG-PS)",
                "Use MPEG Program Stream (DVD-compatible)",
                "dvd", v -> selectRemuxFormat("dvd"));

        rbMpegTs = addRadioRow(parent, "MPEG-TS",
                "Use MPEG Transport Stream",
                "mpegts", v -> selectRemuxFormat("mpegts"));

        selectRemuxFormat(selectedRemuxFormat);

        Button remuxBtn = primarySectionButton("Apply Remux Setting");
        remuxBtn.setOnClickListener(v -> applyRemuxSelection());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        parent.addView(remuxBtn, lp);
    }

    private String readInitialRemuxSelection() {
        try {
            MiniClient client = MiniclientApplication.get().getClient();
            if (client == null) return null;
            PrefStore prefs = client.properties();
            if (prefs == null) return null;
            String remuxPref = prefs.getString(AndroidPrefStore.FIXED_REMUXING_PREFERENCE,
                    AndroidPrefStore.FIXED_REMUXING_PREFERENCE_DEFAULT);
            if ("off".equalsIgnoreCase(remuxPref)) {
                return null;
            }
            return prefs.getString(AndroidPrefStore.FIXED_REMUXING_FORMAT,
                    AndroidPrefStore.FIXED_REMUXING_FORMAT_DEFAULT);
        } catch (Exception e) {
            log.warn("Failed to read initial remux setting", e);
            return null;
        }
    }

    private void applyRemuxSelection() {
        try {
            MiniClient client = MiniclientApplication.get().getClient();
            if (client == null || client.properties() == null) {
                Toast.makeText(this, "Remux setting unavailable right now", Toast.LENGTH_SHORT).show();
                return;
            }

            PrefStore prefs = client.properties();
            if (selectedRemuxFormat == null) {
                prefs.setString(AndroidPrefStore.FIXED_REMUXING_PREFERENCE, "off");
                Toast.makeText(this, "Remux set to None", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                prefs.setString(AndroidPrefStore.FIXED_REMUXING_PREFERENCE, "always");
                prefs.setString(AndroidPrefStore.FIXED_REMUXING_FORMAT, selectedRemuxFormat);
                String label = "matroska".equals(selectedRemuxFormat) ? "MKV"
                        : "dvd".equals(selectedRemuxFormat) ? "DVD (MPEG-PS)"
                        : "MPEG-TS";
                Toast.makeText(this, "Remux set to " + label, Toast.LENGTH_SHORT).show();
                finish();
            }
        } catch (Exception e) {
            log.error("Failed to apply remux setting", e);
            Toast.makeText(this, "Failed to save remux setting", Toast.LENGTH_SHORT).show();
        }
    }

    private TextView sectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(0xFF58A6FF);
        label.setTextSize(18f);
        label.setTypeface(null, Typeface.BOLD);
        label.setPadding(0, 0, 0, dp(6));
        return label;
    }

    private TextView sectionHint(String text) {
        TextView hint = new TextView(this);
        hint.setText(text);
        hint.setTextColor(0xFF8B949E);
        hint.setTextSize(12f);
        hint.setPadding(0, 0, 0, dp(10));
        return hint;
    }

    private Button primarySectionButton(String label) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setBackgroundColor(0xFF1F6FEB);
        btn.setTypeface(null, Typeface.BOLD);
        return btn;
    }

    private View sectionSpacer() {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(18));
        v.setLayoutParams(lp);
        return v;
    }

    private TextView addCheckRow(LinearLayout parent, String label, String description,
                                 boolean initialState, View.OnClickListener toggle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackgroundColor(0xFF21262D);
        row.setClickable(true);
        row.setFocusable(true);

        TextView cb = new TextView(this);
        cb.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
        cb.setGravity(Gravity.CENTER);
        cb.setTextSize(14f);
        cb.setTypeface(null, Typeface.BOLD);
        cb.setBackgroundColor(0xFF0D1117);
        updateCb(cb, initialState);

        LinearLayout textBlock = new LinearLayout(this);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMarginStart(dp(16));
        textBlock.setLayoutParams(textLp);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(0xFFCDD9E5);
        labelView.setTextSize(16f);

        TextView descView = new TextView(this);
        descView.setText(description);
        descView.setTextColor(0xFF8B949E);
        descView.setTextSize(12f);
        descView.setPadding(0, dp(2), 0, 0);

        textBlock.addView(labelView);
        textBlock.addView(descView);

        row.addView(cb);
        row.addView(textBlock);
        row.setOnClickListener(toggle);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(8);
        parent.addView(row, rowLp);

        return cb;
    }

    private TextView addRadioRow(LinearLayout parent, String label, String description,
                                 String formatCode, View.OnClickListener toggle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackgroundColor(0xFF21262D);
        row.setClickable(true);
        row.setFocusable(true);

        TextView rb = new TextView(this);
        rb.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
        rb.setGravity(Gravity.CENTER);
        rb.setTextSize(14f);
        rb.setTypeface(null, Typeface.BOLD);
        rb.setBackgroundColor(0xFF0D1117);
        updateRb(rb, eq(formatCode, selectedRemuxFormat));

        LinearLayout textBlock = new LinearLayout(this);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMarginStart(dp(16));
        textBlock.setLayoutParams(textLp);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(0xFFCDD9E5);
        labelView.setTextSize(16f);

        TextView descView = new TextView(this);
        descView.setText(description);
        descView.setTextColor(0xFF8B949E);
        descView.setTextSize(12f);
        descView.setPadding(0, dp(2), 0, 0);

        textBlock.addView(labelView);
        textBlock.addView(descView);

        row.addView(rb);
        row.addView(textBlock);
        row.setOnClickListener(toggle);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(8);
        parent.addView(row, rowLp);

        return rb;
    }

    private void updateCb(TextView cb, boolean checked) {
        cb.setText(checked ? "✓" : "");
        cb.setTextColor(checked ? 0xFF3FB950 : 0xFF8B949E);
        cb.setBackgroundColor(checked ? 0xFF1A3A24 : 0xFF0D1117);
    }

    private void updateRb(TextView rb, boolean selected) {
        rb.setText(selected ? "●" : "○");
        rb.setTextColor(selected ? 0xFF58A6FF : 0xFF8B949E);
        rb.setBackgroundColor(selected ? 0xFF0D3D66 : 0xFF0D1117);
    }

    private void selectRemuxFormat(String formatCode) {
        selectedRemuxFormat = formatCode;
        if (rbNone != null) updateRb(rbNone, selectedRemuxFormat == null);
        if (rbMkv != null) updateRb(rbMkv, "matroska".equals(selectedRemuxFormat));
        if (rbDvd != null) updateRb(rbDvd, "dvd".equals(selectedRemuxFormat));
        if (rbMpegTs != null) updateRb(rbMpegTs, "mpegts".equals(selectedRemuxFormat));
    }

    private boolean eq(String a, String b) {
        return (a == null && b == null) || (a != null && a.equals(b));
    }

    private void runRefresh() {
        if (!checkMetadata && !checkArtwork && !checkCaptions && !checkComskip && !checkTranscript) {
            Toast.makeText(this, "Nothing selected", Toast.LENGTH_SHORT).show();
            return;
        }

        DownloadMetadata.SidecarFlags flags = new DownloadMetadata.SidecarFlags();
        flags.refreshMetadata = checkMetadata;
        flags.refreshArtwork = checkArtwork;
        flags.refreshCaptions = checkCaptions;
        flags.refreshComskip = checkComskip;
        flags.refreshTranscript = checkTranscript;

        boolean requested = DownloadManager.getInstance(this)
                .refreshWithFlags(meta.getMediaFileID(), flags);

        if (!requested) {
            Toast.makeText(this, "No metadata pointer - connect to server first", Toast.LENGTH_LONG).show();
            return;
        }

        int count = (checkMetadata ? 1 : 0)
                + (checkArtwork ? 1 : 0)
                + (checkCaptions ? 1 : 0)
                + (checkComskip ? 1 : 0)
                + (checkTranscript ? 1 : 0);

        Toast.makeText(this,
                "Refresh requested (" + count + " item" + (count == 1 ? "" : "s") + ")",
                Toast.LENGTH_SHORT).show();

        Runnable refreshCompleteListener = new Runnable() {
            @Override
            public void run() {
                DownloadManager.getInstance(OfflineRefreshMenuActivity.this)
                        .removeManifestUpdateListener(meta.getMediaFileID(), this);

                DownloadMetadata updated = DownloadManager.getInstance(OfflineRefreshMenuActivity.this)
                        .getRepository().getByMediaFileID(meta.getMediaFileID());
                if (updated != null) {
                    if (flags.refreshMetadata && updated.hasMetadata()) updated.setHasMetadata(true);
                    if (flags.refreshArtwork && updated.hasArtwork()) updated.setHasArtwork(true);
                    if (flags.refreshCaptions && updated.hasCaptions()) updated.setHasCaptions(true);
                    if (flags.refreshComskip && updated.hasComskip()) updated.setHasComskip(true);
                    if (flags.refreshTranscript && updated.hasTranscript()) updated.setHasTranscript(true);
                    DownloadManager.getInstance(OfflineRefreshMenuActivity.this).getRepository().update(updated);
                }

                runOnUiThread(() -> Toast.makeText(OfflineRefreshMenuActivity.this,
                        "Refresh complete", Toast.LENGTH_SHORT).show());
            }
        };

        DownloadManager.getInstance(this)
                .addManifestUpdateListener(meta.getMediaFileID(), refreshCompleteListener);

        finish();
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0xFF30363D);
        return v;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
