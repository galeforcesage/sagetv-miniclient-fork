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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

import sagex.miniclient.ServerInfo;

/**
 * Activity displaying the download queue with status, progress, and management actions.
 * Accessible from the Settings screen.
 */
public class DownloadsActivity extends Activity {
    private static final Logger log = LoggerFactory.getLogger(DownloadsActivity.class);
    private static final int REQUEST_CODE_SAF_PICKER = 4001;

    private LinearLayout downloadsList;
    private TextView emptyText;
    private Button storageButton;
    private CheckBox wifiOnlyCheckbox;
    private EditText accountUsernameInput;
    private EditText accountPasswordInput;
    private ImageButton passwordToggleButton;
    private boolean passwordVisible = false;
    private DownloadManager downloadManager;
    private DownloadCredentialVault credentialVault;
    private OfflineEpgRepository epgRepository;
    private SnapshotSyncManager snapshotSyncManager;
    private Button syncGuideButton;
    private Button syncScheduleButton;
    private Button syncFavoritesButton;
    private TextView snapshotStatusText;
    private TextView guideStatsText;
    private TextView scheduleStatsText;
    private TextView favoritesStatsText;
    private boolean snapshotSyncInFlight;

    /**
     * Polling auto-refresh: action buttons like Resume/Retry trigger async work
     * on the DownloadManager executor; without a periodic refresh the user sees
     * no UI change after tapping (the failure or progress arrives milliseconds
     * later, after our single refreshList() call). 1.5s tick is plenty for
     * status text without burning battery.
     */
    private static final long AUTO_REFRESH_INTERVAL_MS = 2500L;
    private final Handler autoRefreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                refreshList();
            } catch (Throwable t) {
                log.warn("Auto-refresh failed: {}", t.toString());
            }
            autoRefreshHandler.postDelayed(this, AUTO_REFRESH_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        downloadManager = DownloadManager.getInstance(this);
        credentialVault = new DownloadCredentialVault(this);
        epgRepository = new OfflineEpgRepository(this);
        snapshotSyncManager = new SnapshotSyncManager(this);

        // Build UI programmatically to avoid adding layout resources
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dpToPx(16);
        root.setPadding(padding, padding, padding, padding);

        // Title
        TextView title = new TextView(this);
        title.setText("Downloads");
        title.setTextSize(24);
        title.setPadding(0, 0, 0, dpToPx(8));
        root.addView(title);

        // Storage location button
        storageButton = new Button(this);
        updateStorageButtonText();
        storageButton.setOnClickListener(v -> openStoragePicker());
        root.addView(storageButton);

        // User-controlled network policy: when checked, downloads only run
        // on Wi-Fi / Ethernet and are queued (waiting_for_wifi) on cellular.
        // Auto-resume happens via DownloadManager's NetworkCallback. Mirrors
        // the checkbox in DownloadsFragment so both surfaces share state.
        wifiOnlyCheckbox = new CheckBox(this);
        wifiOnlyCheckbox.setText("Download on Wi-Fi only (queue on cellular)");
        wifiOnlyCheckbox.setChecked(downloadManager.getStorageHelper().isWifiOnlyDownloads());
        wifiOnlyCheckbox.setOnCheckedChangeListener((btn, isChecked) -> {
            downloadManager.getStorageHelper().setWifiOnlyDownloads(isChecked);
            downloadManager.onWifiOnlyPrefChanged();
            refreshList();
        });
        root.addView(wifiOnlyCheckbox);

        TextView accountTitle = new TextView(this);
        accountTitle.setText("Download Account");
        accountTitle.setTextSize(18);
        accountTitle.setPadding(0, dpToPx(12), 0, dpToPx(4));
        root.addView(accountTitle);

        accountUsernameInput = new EditText(this);
        accountUsernameInput.setHint("Username");
        root.addView(accountUsernameInput);

        LinearLayout passwordRow = new LinearLayout(this);
        passwordRow.setOrientation(LinearLayout.HORIZONTAL);
        accountPasswordInput = new EditText(this);
        accountPasswordInput.setHint("Password");
        accountPasswordInput.setInputType(
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams passLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        accountPasswordInput.setLayoutParams(passLp);
        passwordRow.addView(accountPasswordInput);

        passwordToggleButton = new ImageButton(this);
        passwordToggleButton.setImageResource(android.R.drawable.ic_secure);
        passwordToggleButton.setContentDescription("Show password");
        passwordToggleButton.setOnClickListener(v -> togglePasswordVisibility());
        passwordRow.addView(passwordToggleButton);
        root.addView(passwordRow);

        Button saveAccountButton = new Button(this);
        saveAccountButton.setText("Save Download Account");
        saveAccountButton.setOnClickListener(v -> saveDownloadAccount());
        root.addView(saveAccountButton);

        LinearLayout bulkActions = new LinearLayout(this);
        bulkActions.setOrientation(LinearLayout.HORIZONTAL);
        bulkActions.setPadding(0, dpToPx(12), 0, dpToPx(4));
        addActionButton(bulkActions, "Pause All", v -> {
            downloadManager.pauseAll();
            refreshList();
        });
        addActionButton(bulkActions, "Resume All", v -> {
            downloadManager.resumeAll();
            refreshList();
        });
        addActionButton(bulkActions, "Clear Completed", v -> {
            downloadManager.clearCompleted();
            refreshList();
        });
        root.addView(bulkActions);

        TextView snapshotTitle = new TextView(this);
        snapshotTitle.setText("Offline Snapshot Sync");
        snapshotTitle.setTextSize(18);
        snapshotTitle.setPadding(0, dpToPx(12), 0, dpToPx(4));
        root.addView(snapshotTitle);

        snapshotStatusText = new TextView(this);
        snapshotStatusText.setText("Tap a snapshot button to refresh Guide, Schedule, or Favorites.");
        snapshotStatusText.setTextSize(12);
        snapshotStatusText.setPadding(0, 0, 0, dpToPx(8));
        root.addView(snapshotStatusText);

        syncGuideButton = new Button(this);
        syncGuideButton.setText("Download Guide Snapshot");
        syncGuideButton.setOnClickListener(v -> startSnapshotSync(SnapshotSyncManager.SnapshotKind.GUIDE));
        root.addView(syncGuideButton);

        guideStatsText = new TextView(this);
        guideStatsText.setTextSize(12);
        guideStatsText.setPadding(dpToPx(8), dpToPx(2), 0, dpToPx(8));
        root.addView(guideStatsText);

        syncScheduleButton = new Button(this);
        syncScheduleButton.setText("Download Schedule Snapshot");
        syncScheduleButton.setOnClickListener(v -> startSnapshotSync(SnapshotSyncManager.SnapshotKind.SCHEDULE));
        root.addView(syncScheduleButton);

        scheduleStatsText = new TextView(this);
        scheduleStatsText.setTextSize(12);
        scheduleStatsText.setPadding(dpToPx(8), dpToPx(2), 0, dpToPx(8));
        root.addView(scheduleStatsText);

        syncFavoritesButton = new Button(this);
        syncFavoritesButton.setText("Download Favorites Snapshot");
        syncFavoritesButton.setOnClickListener(v -> startSnapshotSync(SnapshotSyncManager.SnapshotKind.FAVORITES));
        root.addView(syncFavoritesButton);

        favoritesStatsText = new TextView(this);
        favoritesStatsText.setTextSize(12);
        favoritesStatsText.setPadding(dpToPx(8), dpToPx(2), 0, dpToPx(10));
        root.addView(favoritesStatsText);

        // Empty text
        emptyText = new TextView(this);
        emptyText.setText("No downloads");
        emptyText.setPadding(0, dpToPx(16), 0, 0);
        emptyText.setTextSize(16);
        root.addView(emptyText);

        // Downloads list container
        downloadsList = new LinearLayout(this);
        downloadsList.setOrientation(LinearLayout.VERTICAL);
        root.addView(downloadsList);

        scrollView.addView(root);
        setContentView(scrollView);
        refreshSnapshotStats();

        overlay = new OfflineNavigationOverlay(this);
    }

    private OfflineNavigationOverlay overlay;

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
        refreshSnapshotStats();
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable);
        autoRefreshHandler.postDelayed(autoRefreshRunnable, AUTO_REFRESH_INTERVAL_MS);
        if (overlay != null) overlay.onResume();
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (overlay != null && overlay.onKeyDown(keyCode, event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, android.view.KeyEvent event) {
        if (overlay != null && overlay.onKeyLongPress(keyCode, event)) return true;
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, android.view.KeyEvent event) {
        if (overlay != null && overlay.onKeyUp(keyCode, event)) return true;
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onPause() {
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable);
        super.onPause();
    }

    private void refreshList() {
        downloadsList.removeAllViews();
        List<DownloadMetadata> downloads = downloadManager.getQueue();

        emptyText.setVisibility(downloads.isEmpty() ? View.VISIBLE : View.GONE);

        for (DownloadMetadata meta : downloads) {
            downloadsList.addView(createDownloadItemView(meta));
        }
    }

    private View createDownloadItemView(DownloadMetadata meta) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        int padding = dpToPx(12);
        item.setPadding(padding, padding, padding, padding);
        item.setClickable(true);
        item.setFocusable(true);
        item.setOnClickListener(v -> showRowActionMenu(meta));
        item.setOnLongClickListener(v -> {
            showRowActionMenu(meta);
            return true;
        });

        // Title
        TextView titleView = new TextView(this);
        titleView.setText(meta.getTitle() != null ? meta.getTitle() : meta.getMediaFileID());
        titleView.setTextSize(16);
        item.addView(titleView);

        // Status + size
        TextView statusView = new TextView(this);
        String statusText = meta.getStatus().name();
        String sessionState = meta.getEffectiveSessionState();
        if (sessionState != null && !sessionState.isEmpty()) {
            statusText += " [" + sessionState + "]";
        }
        if (meta.getFileSize() > 0) {
            statusText += " - " + formatBytes(meta.getDownloadedBytes()) + " / " + formatBytes(meta.getFileSize());
        }
        if (meta.getQueuePriority() != 0) {
            statusText += "\nPriority: " + meta.getQueuePriority();
        }
        if (meta.getMergedRequestCount() > 1) {
            statusText += "\nMerged requests: " + meta.getMergedRequestCount();
        }
        if (meta.getDownloadSpeedBytesPerSec() > 0) {
            statusText += "\nSpeed: " + formatBytes(meta.getDownloadSpeedBytesPerSec()) + "/s";
        }
        if (meta.getEtaSeconds() > 0) {
            statusText += " ETA: " + formatEta(meta.getEtaSeconds());
        }
        if (meta.getAcceptedPolicyJson() != null && !meta.getAcceptedPolicyJson().isEmpty()) {
            statusText += "\nAccepted policy: " + meta.getAcceptedPolicyJson();
        }
        if (meta.getRecentReasonCodesJson() != null && !meta.getRecentReasonCodesJson().isEmpty()) {
            statusText += "\nReason codes: " + meta.getRecentReasonCodesJson();
        }
        if (meta.getPolicyAdjustmentsJson() != null && !meta.getPolicyAdjustmentsJson().isEmpty()) {
            statusText += "\nPolicy adjustments: " + meta.getPolicyAdjustmentsJson();
        }
        if (meta.getErrorMessage() != null && !meta.getErrorMessage().isEmpty()) {
            if (meta.getStatus() == DownloadMetadata.Status.FAILED) {
                statusText += "\nError: " + meta.getErrorMessage();
            } else {
                statusText += "\nDetails: " + meta.getErrorMessage();
            }
        }
        statusView.setText(statusText);
        statusView.setTextSize(12);
        item.addView(statusView);

        // Progress bar (for active/queued downloads, and while remuxing)
        int remuxPct = parseRemuxPercent(meta.getEffectiveSessionState());
        if (meta.getStatus() == DownloadMetadata.Status.DOWNLOADING
            || meta.getStatus() == DownloadMetadata.Status.QUEUED
            || meta.getStatus() == DownloadMetadata.Status.PREPARING
            || remuxPct >= 0 || remuxPct == -2) {
            ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            if (remuxPct == -2) {
                progressBar.setIndeterminate(true);
            } else {
                progressBar.setMax(100);
                progressBar.setProgress(remuxPct >= 0 ? remuxPct : meta.getProgressPercent());
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(4));
            lp.setMargins(0, dpToPx(4), 0, 0);
            progressBar.setLayoutParams(lp);
            item.addView(progressBar);
        }

        // Action buttons
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dpToPx(4), 0, 0);

        switch (meta.getStatus()) {
            case DOWNLOADING:
                addActionButton(actions, "Pause", v -> {
                    downloadManager.pause(meta.getMediaFileID());
                    refreshList();
                });
                addActionButton(actions, "Move Up", v -> {
                    downloadManager.moveUp(meta.getMediaFileID());
                    refreshList();
                });
                addActionButton(actions, "Move Down", v -> {
                    downloadManager.moveDown(meta.getMediaFileID());
                    refreshList();
                });
                addActionButton(actions, "Cancel", v -> {
                    confirmCancel(meta);
                });
                break;
            case QUEUED:
            case PREPARING:
                addActionButton(actions, "Pause", v -> {
                    downloadManager.pause(meta.getMediaFileID());
                    refreshList();
                });
                addActionButton(actions, "Move Up", v -> {
                    downloadManager.moveUp(meta.getMediaFileID());
                    refreshList();
                });
                addActionButton(actions, "Move Down", v -> {
                    downloadManager.moveDown(meta.getMediaFileID());
                    refreshList();
                });
                addActionButton(actions, "Priority +", v -> {
                    downloadManager.setPriority(meta.getMediaFileID(), meta.getQueuePriority() + 1);
                    refreshList();
                });
                addActionButton(actions, "Priority -", v -> {
                    downloadManager.setPriority(meta.getMediaFileID(), meta.getQueuePriority() - 1);
                    refreshList();
                });
                addActionButton(actions, "Cancel", v -> {
                    confirmCancel(meta);
                });
                break;
            case PAUSED:
            case FAILED:
                if (canRetryRemux(meta)) {
                    addActionButton(actions, "Remux", v -> {
                        downloadManager.retryRemux(meta.getMediaFileID());
                        Toast.makeText(this, "Remux queued", Toast.LENGTH_SHORT).show();
                        refreshList();
                    });
                }
                addActionButton(actions, "Restart", v -> {
                    downloadManager.restart(meta.getMediaFileID());
                    Toast.makeText(this, "Restart requested", Toast.LENGTH_SHORT).show();
                    refreshList();
                });
                addActionButton(actions, "Resume", v -> {
                    downloadManager.resume(meta.getMediaFileID());
                    Toast.makeText(this, "Resume requested", Toast.LENGTH_SHORT).show();
                    refreshList();
                });
                addActionButton(actions, "Delete", v -> {
                    confirmDelete(meta);
                });
                break;
            case COMPLETE:
                if (canRetryRemux(meta)) {
                    addActionButton(actions, "Remux", v -> {
                        downloadManager.retryRemux(meta.getMediaFileID());
                        Toast.makeText(this, "Remux queued", Toast.LENGTH_SHORT).show();
                        refreshList();
                    });
                }
                addActionButton(actions, "Delete", v -> {
                    confirmDelete(meta);
                });
                break;
        }

        item.addView(actions);

        // Separator line
        View divider = new View(this);
        divider.setBackgroundColor(0x33888888);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        divLp.setMargins(0, dpToPx(8), 0, 0);
        divider.setLayoutParams(divLp);

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(item);
        wrapper.addView(divider);
        return wrapper;
    }

    private void showRowActionMenu(DownloadMetadata meta) {
        if (meta == null) return;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        switch (meta.getStatus()) {
            case DOWNLOADING:
                labels.add("Pause");
                actions.add(() -> {
                    downloadManager.pause(meta.getMediaFileID());
                    refreshList();
                });
                labels.add("Move Up");
                actions.add(() -> {
                    downloadManager.moveUp(meta.getMediaFileID());
                    refreshList();
                });
                labels.add("Move Down");
                actions.add(() -> {
                    downloadManager.moveDown(meta.getMediaFileID());
                    refreshList();
                });
                labels.add("Cancel");
                actions.add(() -> confirmCancel(meta));
                break;
            case QUEUED:
            case PREPARING:
                labels.add("Pause");
                actions.add(() -> {
                    downloadManager.pause(meta.getMediaFileID());
                    refreshList();
                });
                labels.add("Move Up");
                actions.add(() -> {
                    downloadManager.moveUp(meta.getMediaFileID());
                    refreshList();
                });
                labels.add("Move Down");
                actions.add(() -> {
                    downloadManager.moveDown(meta.getMediaFileID());
                    refreshList();
                });
                labels.add("Priority +");
                actions.add(() -> {
                    downloadManager.setPriority(meta.getMediaFileID(), meta.getQueuePriority() + 1);
                    refreshList();
                });
                labels.add("Priority -");
                actions.add(() -> {
                    downloadManager.setPriority(meta.getMediaFileID(), meta.getQueuePriority() - 1);
                    refreshList();
                });
                labels.add("Cancel");
                actions.add(() -> confirmCancel(meta));
                break;
            case PAUSED:
            case FAILED:
                if (canRetryRemux(meta)) {
                    labels.add("Remux");
                    actions.add(() -> {
                        downloadManager.retryRemux(meta.getMediaFileID());
                        Toast.makeText(this, "Remux queued", Toast.LENGTH_SHORT).show();
                        refreshList();
                    });
                }
                labels.add("Restart");
                actions.add(() -> {
                    downloadManager.restart(meta.getMediaFileID());
                    Toast.makeText(this, "Restart requested", Toast.LENGTH_SHORT).show();
                    refreshList();
                });
                labels.add("Resume");
                actions.add(() -> {
                    downloadManager.resume(meta.getMediaFileID());
                    Toast.makeText(this, "Resume requested", Toast.LENGTH_SHORT).show();
                    refreshList();
                });
                labels.add("Delete");
                actions.add(() -> confirmDelete(meta));
                break;
            case COMPLETE:
                Intent intent = new Intent(this, OfflineRefreshMenuActivity.class);
                intent.putExtra(OfflineRefreshMenuActivity.EXTRA_MEDIA_FILE_ID, meta.getMediaFileID());
                startActivity(intent);
                return;
        }

        if (labels.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle(meta.getTitle() != null ? meta.getTitle() : meta.getMediaFileID())
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which >= 0 && which < actions.size()) {
                        actions.get(which).run();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void addActionButton(LinearLayout parent, String text, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(12);
        btn.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dpToPx(8), 0);
        btn.setLayoutParams(lp);
        parent.addView(btn);
    }

    private void confirmCancel(DownloadMetadata meta) {
        new AlertDialog.Builder(this)
                .setTitle("Cancel Download")
                .setMessage("Cancel download of " + (meta.getTitle() != null ? meta.getTitle() : meta.getMediaFileID()) + "?")
                .setPositiveButton("Cancel Download", (d, w) -> {
                    downloadManager.cancel(meta.getMediaFileID());
                    refreshList();
                })
                .setNegativeButton("Keep", null)
                .show();
    }

    private void confirmDelete(DownloadMetadata meta) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Download")
                .setMessage("Delete " + (meta.getTitle() != null ? meta.getTitle() : meta.getMediaFileID()) + " and its file?")
                .setPositiveButton("Delete", (d, w) -> {
                    downloadManager.cancel(meta.getMediaFileID());
                    refreshList();
                })
                .setNegativeButton("Keep", null)
                .show();
    }

    private void openStoragePicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_CODE_SAF_PICKER);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SAF_PICKER && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                // Persist permission
                int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                getContentResolver().takePersistableUriPermission(treeUri, flags);
                downloadManager.getStorageHelper().setStorageUri(treeUri);
                updateStorageButtonText();
                log.info("Download storage set to: {}", treeUri);
            }
        }
    }

    private void updateStorageButtonText() {
        StorageHelper helper = downloadManager.getStorageHelper();
        Uri uri = helper.getStorageUri();
        if (uri != null) {
            storageButton.setText("Storage: " + uri.getLastPathSegment() + " (tap to change)");
        } else {
            storageButton.setText("Choose Download Location");
        }
    }

    private void togglePasswordVisibility() {
        passwordVisible = !passwordVisible;
        if (passwordVisible) {
            accountPasswordInput.setInputType(
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            passwordToggleButton.setImageResource(android.R.drawable.ic_menu_view);
            passwordToggleButton.setContentDescription("Hide password");
        } else {
            accountPasswordInput.setInputType(
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            passwordToggleButton.setImageResource(android.R.drawable.ic_secure);
            passwordToggleButton.setContentDescription("Show password");
        }
        accountPasswordInput.setSelection(accountPasswordInput.getText().length());
    }

    private void saveDownloadAccount() {
        String username = accountUsernameInput.getText().toString().trim();
        String password = accountPasswordInput.getText().toString();

        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Username and password are required", Toast.LENGTH_SHORT).show();
            return;
        }

        // Code note: app family is not a client setting. The server/job metadata
        // determines family when selecting account candidates at transfer time.
        // We mirror credentials into legacy compatibility buckets here so either
        // family can reuse the same user-entered credentials.
        credentialVault.upsertAccount("sage", username, password);
        credentialVault.upsertAccount("frey", username, password);
        Toast.makeText(this, "Download account saved", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onStart() {
        super.onStart();
        preloadPreferredAccount();
    }

    private void preloadPreferredAccount() {
        List<DownloadCredentialVault.AccountSnapshot> candidates =
                credentialVault.getCandidates("sage", "");
        if (candidates.isEmpty()) {
            candidates = credentialVault.getCandidates("frey", "");
        }
        if (!candidates.isEmpty()) {
            accountUsernameInput.setText(candidates.get(0).username);
            accountPasswordInput.setText(candidates.get(0).password);
            if (!passwordVisible) {
                accountPasswordInput.setInputType(
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            }
        }
    }

    private void startSnapshotSync(SnapshotSyncManager.SnapshotKind kind) {
        if (snapshotSyncInFlight) {
            Toast.makeText(this, "Snapshot sync already in progress", Toast.LENGTH_SHORT).show();
            return;
        }

        List<ServerInfo> servers = snapshotSyncManager.getSavedServers();
        if (servers.isEmpty()) {
            Toast.makeText(this,
                    "No servers saved. Add or connect to a server first.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        if (servers.size() == 1) {
            doSnapshotSync(kind, servers.get(0));
            return;
        }

        String[] labels = new String[servers.size()];
        for (int i = 0; i < servers.size(); i++) {
            ServerInfo si = servers.get(i);
            labels[i] = (si.name != null && !si.name.isEmpty())
                    ? si.name
                    : si.address + ":" + si.port;
        }

        String title;
        if (kind == SnapshotSyncManager.SnapshotKind.GUIDE) {
            title = "Download Guide Snapshot from...";
        } else if (kind == SnapshotSyncManager.SnapshotKind.SCHEDULE) {
            title = "Download Schedule Snapshot from...";
        } else {
            title = "Download Favorites Snapshot from...";
        }

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels, (dialog, which) -> doSnapshotSync(kind, servers.get(which)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doSnapshotSync(SnapshotSyncManager.SnapshotKind kind, ServerInfo server) {
        String kindLabel;
        if (kind == SnapshotSyncManager.SnapshotKind.GUIDE) {
            kindLabel = "Guide";
        } else if (kind == SnapshotSyncManager.SnapshotKind.SCHEDULE) {
            kindLabel = "Schedule";
        } else {
            kindLabel = "Favorites";
        }

        String serverLabel = (server.name != null && !server.name.isEmpty())
                ? server.name : server.address + ":" + server.port;

        setSnapshotSyncInFlight(true,
                "Please wait... downloading " + kindLabel + " snapshot from " + serverLabel);

        snapshotSyncManager.syncServer(kind, server, new SnapshotSyncManager.SyncCallback() {
            @Override
            public void onSuccess(SnapshotSyncManager.SnapshotKind k, String name) {
                runOnUiThread(() -> {
                    setSnapshotSyncInFlight(false,
                            kindLabel + " snapshot updated from " + name);
                    refreshSnapshotStats();
                    Toast.makeText(DownloadsActivity.this,
                            kindLabel + " snapshot updated",
                            Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onFailure(SnapshotSyncManager.SnapshotKind k, String name, String message) {
                runOnUiThread(() -> {
                    setSnapshotSyncInFlight(false,
                            kindLabel + " snapshot failed: " + message);
                    refreshSnapshotStats();
                    new AlertDialog.Builder(DownloadsActivity.this)
                            .setTitle("Snapshot sync failed")
                            .setMessage(message)
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        });
    }

    private void setSnapshotSyncInFlight(boolean inFlight, String status) {
        snapshotSyncInFlight = inFlight;
        if (syncGuideButton != null) syncGuideButton.setEnabled(!inFlight);
        if (syncScheduleButton != null) syncScheduleButton.setEnabled(!inFlight);
        if (syncFavoritesButton != null) syncFavoritesButton.setEnabled(!inFlight);
        if (snapshotStatusText != null) snapshotStatusText.setText(status == null ? "" : status);
    }

    private void refreshSnapshotStats() {
        OfflineEpgRepository.SnapshotCounts c = epgRepository.getSnapshotCounts();
        OfflineEpgRepository.SnapshotMeta guideMeta = epgRepository.getMeta("guide");
        OfflineEpgRepository.SnapshotMeta schedMeta = epgRepository.getMeta("sched");
        OfflineEpgRepository.SnapshotMeta favMeta = epgRepository.getMeta("favorites");

        if (guideStatsText != null) {
            guideStatsText.setText(
                    "Guide cache: " + c.channels + " channels / " + c.airings + " airings\n"
                            + "Last updated: " + formatMetaTime(guideMeta));
        }
        if (scheduleStatsText != null) {
            scheduleStatsText.setText(
                    "Scheduled cache: " + c.scheduled + " shows\n"
                            + "Last updated: " + formatMetaTime(schedMeta));
        }
        if (favoritesStatsText != null) {
            favoritesStatsText.setText(
                    "Favorites cache: " + c.favorites + " favorites\n"
                            + "Last updated: " + formatMetaTime(favMeta));
        }
    }

    private static String formatMetaTime(OfflineEpgRepository.SnapshotMeta meta) {
        if (meta == null || meta.fetchedAtMs <= 0L) {
            return "never";
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd h:mm:ss a", Locale.US);
        String server = (meta.sourceServerName == null || meta.sourceServerName.isEmpty())
                ? "unknown"
                : meta.sourceServerName;
        return fmt.format(meta.fetchedAtMs) + " (" + server + ")";
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * Returns the percent encoded in a "remuxing NN%" session state string,
     * or -1 when the state is not a remux state. Used to drive the progress
     * bar after the download phase is complete.
     */
    private static int parseRemuxPercent(String sessionState) {
        if (sessionState == null || !sessionState.startsWith("remuxing")) return -1;
        if (!sessionState.contains("%")) return -2;
        int pct = 0;
        int p = sessionState.indexOf('%');
        int s = sessionState.indexOf(' ');
        if (s > 0 && p > s) {
            try { pct = Integer.parseInt(sessionState.substring(s + 1, p).trim()); }
            catch (NumberFormatException ignored) {}
        }
        return Math.max(0, Math.min(100, pct));
    }

    private static String formatEta(long etaSeconds) {
        if (etaSeconds < 60) return etaSeconds + "s";
        long minutes = etaSeconds / 60;
        if (minutes < 60) return minutes + "m";
        long hours = minutes / 60;
        long remMinutes = minutes % 60;
        return hours + "h " + remMinutes + "m";
    }

    private static boolean canRetryRemux(DownloadMetadata meta) {
        if (meta == null) return false;
        long total = meta.getFileSize();
        boolean primaryComplete = meta.getStatus() == DownloadMetadata.Status.COMPLETE
                || (total > 0 && (meta.getDownloadedBytes() >= total
                || meta.getResumeFromOffset() >= total));
        return primaryComplete && PostDownloadRemux.shouldRemux(meta);
    }
}
