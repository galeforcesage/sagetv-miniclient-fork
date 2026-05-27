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
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

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
    private EditText accountUsernameInput;
    private EditText accountPasswordInput;
    private ImageButton passwordToggleButton;
    private boolean passwordVisible = false;
    private DownloadManager downloadManager;
    private DownloadCredentialVault credentialVault;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        downloadManager = DownloadManager.getInstance(this);
        credentialVault = new DownloadCredentialVault(this);

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
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
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
        if (meta.getErrorMessage() != null && meta.getStatus() == DownloadMetadata.Status.FAILED) {
            statusText += "\n" + meta.getErrorMessage();
        }
        statusView.setText(statusText);
        statusView.setTextSize(12);
        item.addView(statusView);

        // Progress bar (for active/queued downloads)
        if (meta.getStatus() == DownloadMetadata.Status.DOWNLOADING
            || meta.getStatus() == DownloadMetadata.Status.QUEUED
            || meta.getStatus() == DownloadMetadata.Status.PREPARING) {
            ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setMax(100);
            progressBar.setProgress(meta.getProgressPercent());
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
                addActionButton(actions, "Retry", v -> {
                    downloadManager.retry(meta.getMediaFileID());
                    refreshList();
                });
                addActionButton(actions, "Resume", v -> {
                    downloadManager.resume(meta.getMediaFileID());
                    refreshList();
                });
                addActionButton(actions, "Delete", v -> {
                    confirmDelete(meta);
                });
                break;
            case COMPLETE:
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

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String formatEta(long etaSeconds) {
        if (etaSeconds < 60) return etaSeconds + "s";
        long minutes = etaSeconds / 60;
        if (minutes < 60) return minutes + "m";
        long hours = minutes / 60;
        long remMinutes = minutes % 60;
        return hours + "h " + remMinutes + "m";
    }
}
