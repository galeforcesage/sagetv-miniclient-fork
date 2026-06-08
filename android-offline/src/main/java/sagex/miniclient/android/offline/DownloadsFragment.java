/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
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
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import sagex.miniclient.android.R;

/**
 * Overlay version of {@link DownloadsActivity}: same UI, hosted as a
 * DialogFragment on top of the renderer Activity so the underlying GL
 * surface is never destroyed/recreated and the SageTV server-side menu
 * doesn't suffer the texture-cache corruption that an Activity switch
 * caused. Mirrors the pattern used by {@code NavigationFragment} (the
 * FF/REW/DPAD overlay).
 */
public class DownloadsFragment extends DialogFragment {
    private static final Logger log = LoggerFactory.getLogger(DownloadsFragment.class);
    private static final String TAG = "downloads";
    private static final int REQUEST_CODE_SAF_PICKER = 4001;
    private static final long AUTO_REFRESH_INTERVAL_MS = 2500L;

    private LinearLayout downloadsList;
    private TextView emptyText;
    private Button storageButton;
    private CheckBox wifiOnlyCheckbox;
    private DownloadManager downloadManager;

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

    public static void showDialog(Activity activity) {
        if (activity == null) return;
        FragmentTransaction ft = activity.getFragmentManager().beginTransaction();
        Fragment prev = activity.getFragmentManager().findFragmentByTag(TAG);
        if (prev != null) ft.remove(prev);
        ft.addToBackStack(null);
        new DownloadsFragment().show(ft, TAG);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // IMPORTANT: stay on the floating Theme_Dialog_DoNotDim. A
        // non-floating / windowFullscreen theme causes Android to mark
        // the underlying activity as occluded → onPause → GLSurfaceView
        // EGL surface destroyed → client glyph/texture cache lost → on
        // dismiss the server-pushed draw commands reference texture slots
        // that no longer exist, producing "font gibberish" across the
        // SageTV menu. Opacity is enforced below by an explicit
        // MATCH_PARENT opaque FrameLayout in onCreateView, not by the
        // window background.
        setStyle(DialogFragment.STYLE_NO_FRAME, R.style.Theme_Dialog_DoNotDim);
        setCancelable(true);
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        Window window = dialog.getWindow();
        if (window != null) {
            window.requestFeature(Window.FEATURE_NO_TITLE);
            // Opaque window background so the underlying GL renderer surface
            // does not bleed through (STYLE_NO_FRAME otherwise leaves the
            // window background transparent).
            window.setBackgroundDrawable(new ColorDrawable(0xFF101418));
            WindowManager.LayoutParams wmlp = new WindowManager.LayoutParams();
            wmlp.copyFrom(window.getAttributes());
            wmlp.gravity = Gravity.CENTER;
            wmlp.width = WindowManager.LayoutParams.MATCH_PARENT;
            wmlp.height = WindowManager.LayoutParams.MATCH_PARENT;
            window.setAttributes(wmlp);
            // CRITICAL: prevent IME from resizing or panning anything when
            // editable widgets in this dialog gain focus. The dialog no
            // longer hosts credential fields (those live in the standalone
            // DownloadsActivity), but this guard is kept as defence in
            // depth in case any future widget brings the IME up: on many
            // Android versions the IME triggers a global window-insets
            // recomputation that resizes the host activity's GLSurfaceView
            // → OpenGLRenderer.resize() rebuilds the main surface → the
            // server-uploaded glyph/texture atlas is invalidated → every
            // subsequent server-pushed text draw references stale texture
            // slots, producing the "font gibberish" seen in the SageTV
            // menu after the overlay is dismissed.
            window.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                            | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK
                        && event.getAction() == KeyEvent.ACTION_UP) {
                    dismiss();
                    return true;
                }
                return false;
            }
        });
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Context ctx = getActivity();
        downloadManager = DownloadManager.getInstance(ctx);

        // Opaque MATCH_PARENT outer layer. With the floating dialog theme
        // the window background isn't reliably full-screen, so we paint
        // our own opaque backdrop here that always fills whatever area
        // the dialog window occupies. This is what blocks the GL renderer
        // surface from showing through.
        FrameLayout opaqueBackdrop = new FrameLayout(ctx);
        opaqueBackdrop.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        opaqueBackdrop.setBackgroundColor(0xFF101418);
        // Eat touches so they cannot leak through to the SageTV renderer
        // underneath (the GL view is in a separate window but defensive).
        opaqueBackdrop.setClickable(true);
        opaqueBackdrop.setFocusable(true);

        ScrollView scrollView = new ScrollView(ctx);
        scrollView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        scrollView.setBackgroundColor(0xFF101418);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dpToPx(16);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(ctx);
        title.setText("Downloads");
        title.setTextSize(24);
        title.setTextColor(0xFFFFFFFF);
        title.setPadding(0, 0, 0, dpToPx(8));
        root.addView(title);

        storageButton = new Button(ctx);
        updateStorageButtonText();
        storageButton.setOnClickListener(v -> openStoragePicker());
        root.addView(storageButton);

        // User-controlled network policy: when checked, downloads only run
        // on Wi-Fi / Ethernet and are queued (waiting_for_wifi) on cellular.
        // Auto-resume happens via DownloadManager's NetworkCallback.
        wifiOnlyCheckbox = new CheckBox(ctx);
        wifiOnlyCheckbox.setText("Download on Wi-Fi only (queue on cellular)");
        wifiOnlyCheckbox.setTextColor(0xFFFFFFFF);
        wifiOnlyCheckbox.setChecked(downloadManager.getStorageHelper().isWifiOnlyDownloads());
        wifiOnlyCheckbox.setOnCheckedChangeListener((btn, isChecked) -> {
            downloadManager.getStorageHelper().setWifiOnlyDownloads(isChecked);
            downloadManager.onWifiOnlyPrefChanged();
            refreshList();
        });
        root.addView(wifiOnlyCheckbox);

        // NOTE: download-account credentials (username/password) are
        // intentionally NOT editable from this overlay. Hosting EditText
        // fields here invites the IME, which on many Android versions
        // resizes the host activity's GLSurfaceView and invalidates the
        // server-uploaded glyph cache (see onCreateDialog comment for the
        // full chain). Credentials are managed only from the standalone
        // DownloadsActivity ("Manage Downloads" in Settings), which runs
        // as its own activity and can host the IME safely. A small note
        // here reminds the user where to go.
        TextView accountHint = new TextView(ctx);
        accountHint.setText("Download account is managed in Settings → Manage Downloads.");
        accountHint.setTextColor(0xFFCCCCCC);
        accountHint.setTextSize(12);
        accountHint.setPadding(0, dpToPx(8), 0, dpToPx(4));
        root.addView(accountHint);

        LinearLayout bulkActions = new LinearLayout(ctx);
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

        emptyText = new TextView(ctx);
        emptyText.setText("No downloads");
        emptyText.setTextColor(0xFFCCCCCC);
        emptyText.setPadding(0, dpToPx(16), 0, 0);
        emptyText.setTextSize(16);
        root.addView(emptyText);

        downloadsList = new LinearLayout(ctx);
        downloadsList.setOrientation(LinearLayout.VERTICAL);
        root.addView(downloadsList);

        // Close button at the bottom — placed after the downloads list so
        // it stays reachable after a long queue but isn't the first focused
        // control when the dialog opens.
        Button closeButton = new Button(ctx);
        closeButton.setText("Close");
        closeButton.setOnClickListener(v -> dismiss());
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.topMargin = dpToPx(16);
        closeButton.setLayoutParams(closeLp);
        root.addView(closeButton);

        scrollView.addView(root);
        opaqueBackdrop.addView(scrollView);
        return opaqueBackdrop;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshList();
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable);
        autoRefreshHandler.postDelayed(autoRefreshRunnable, AUTO_REFRESH_INTERVAL_MS);
    }

    @Override
    public void onPause() {
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable);
        super.onPause();
    }

    private void refreshList() {
        if (downloadsList == null) return;
        downloadsList.removeAllViews();
        List<DownloadMetadata> downloads = downloadManager.getQueue();
        emptyText.setVisibility(downloads.isEmpty() ? View.VISIBLE : View.GONE);
        for (DownloadMetadata meta : downloads) {
            downloadsList.addView(createDownloadItemView(meta));
        }
    }

    private View createDownloadItemView(DownloadMetadata meta) {
        Context ctx = getActivity();
        LinearLayout item = new LinearLayout(ctx);
        item.setOrientation(LinearLayout.VERTICAL);
        int padding = dpToPx(12);
        item.setPadding(padding, padding, padding, padding);

        TextView titleView = new TextView(ctx);
        titleView.setText(meta.getTitle() != null ? meta.getTitle() : meta.getMediaFileID());
        titleView.setTextSize(16);
        titleView.setTextColor(0xFFFFFFFF);
        item.addView(titleView);

        TextView statusView = new TextView(ctx);
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
        statusView.setTextColor(0xFFDDDDDD);
        item.addView(statusView);

        int remuxPct = parseRemuxPercent(meta.getEffectiveSessionState());
        if (meta.getStatus() == DownloadMetadata.Status.DOWNLOADING
                || meta.getStatus() == DownloadMetadata.Status.QUEUED
                || meta.getStatus() == DownloadMetadata.Status.PREPARING
                || remuxPct >= 0 || remuxPct == -2) {
            ProgressBar progressBar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
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

        item.setFocusable(true);
        item.setClickable(true);
        item.setOnClickListener(v -> showActionMenu(meta));

        View divider = new View(ctx);
        divider.setBackgroundColor(0x33888888);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        divLp.setMargins(0, dpToPx(8), 0, 0);
        divider.setLayoutParams(divLp);

        LinearLayout wrapper = new LinearLayout(ctx);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(item);
        wrapper.addView(divider);
        return wrapper;
    }

    private void showActionMenu(DownloadMetadata meta) {
        Activity activity = getActivity();
        if (activity == null || meta == null) return;
        String title = meta.getTitle() != null ? meta.getTitle() : meta.getMediaFileID();

        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<Runnable> actions = new java.util.ArrayList<>();

        switch (meta.getStatus()) {
            case DOWNLOADING:
            case QUEUED:
            case PREPARING:
                labels.add("Pause");
                actions.add(() -> { downloadManager.pause(meta.getMediaFileID()); refreshList(); });
                labels.add("Move Up");
                actions.add(() -> { downloadManager.moveUp(meta.getMediaFileID()); refreshList(); });
                labels.add("Move Down");
                actions.add(() -> { downloadManager.moveDown(meta.getMediaFileID()); refreshList(); });
                labels.add("Cancel");
                actions.add(() -> confirmCancel(meta));
                break;

            case PAUSED:
            case FAILED:
                labels.add("Resume");
                actions.add(() -> { downloadManager.resume(meta.getMediaFileID());
                    Toast.makeText(activity, "Resume requested", Toast.LENGTH_SHORT).show(); refreshList(); });
                labels.add("Restart");
                actions.add(() -> { downloadManager.executeAction(
                            meta.getMediaFileID(),
                            DownloadManager.ActionOptions.restartOnly());
                    Toast.makeText(activity, "Restart requested", Toast.LENGTH_SHORT).show(); refreshList(); });
                if (canRetryRemux(meta)) {
                    labels.add("Remux to MKV");
                    actions.add(() -> { downloadManager.executeAction(
                                meta.getMediaFileID(),
                                DownloadManager.ActionOptions.remux(DownloadManager.RemuxMode.MKV));
                        Toast.makeText(activity, "Remux to MKV queued", Toast.LENGTH_SHORT).show(); refreshList(); });
                    labels.add("Remux to MP4");
                    actions.add(() -> { downloadManager.executeAction(
                                meta.getMediaFileID(),
                                DownloadManager.ActionOptions.remux(DownloadManager.RemuxMode.MP4));
                        Toast.makeText(activity, "Remux to MP4 queued", Toast.LENGTH_SHORT).show(); refreshList(); });
                }
                labels.add("Delete");
                actions.add(() -> confirmDelete(meta));
                break;

            case COMPLETE:
                Intent intent = new Intent(activity, OfflineRefreshMenuActivity.class);
                intent.putExtra(OfflineRefreshMenuActivity.EXTRA_MEDIA_FILE_ID, meta.getMediaFileID());
                activity.startActivity(intent);
                return;
        }

        String[] items = labels.toArray(new String[0]);
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setItems(items, (d, which) -> actions.get(which).run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Deprecated
    private void addActionButton(LinearLayout parent, String text, View.OnClickListener listener) {
        Button btn = new Button(getActivity());
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
        new AlertDialog.Builder(getActivity())
                .setTitle("Cancel Download")
                .setMessage("Cancel download of "
                        + (meta.getTitle() != null ? meta.getTitle() : meta.getMediaFileID()) + "?")
                .setPositiveButton("Cancel Download", (d, w) -> {
                    downloadManager.cancel(meta.getMediaFileID());
                    refreshList();
                })
                .setNegativeButton("Keep", null)
                .show();
    }

    private void confirmDelete(DownloadMetadata meta) {
        new AlertDialog.Builder(getActivity())
                .setTitle("Delete Download")
                .setMessage("Delete "
                        + (meta.getTitle() != null ? meta.getTitle() : meta.getMediaFileID()) + " and its file?")
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
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SAF_PICKER && resultCode == Activity.RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                getActivity().getContentResolver().takePersistableUriPermission(treeUri, flags);
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
        // Account UI lives in DownloadsActivity now; method retained as a
        // no-op only to keep older R8 mappings stable. Should never be
        // called from this fragment.
    }

    private void saveDownloadAccount() {
        // See togglePasswordVisibility() — no-op stub.
    }

    private void preloadPreferredAccount() {
        // See togglePasswordVisibility() — no-op stub.
    }

    private int dpToPx(int dp) {
        return (int) (dp * getActivity().getResources().getDisplayMetrics().density);
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
