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

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Foreground service that keeps the app alive during media file downloads.
 * Shows an ongoing notification with progress and pause/cancel actions.
 */
public class DownloadForegroundService extends Service {
    private static final Logger log = LoggerFactory.getLogger(DownloadForegroundService.class);

    public static final String ACTION_START = "sagex.miniclient.download.START";
    public static final String ACTION_STOP = "sagex.miniclient.download.STOP";
    public static final String ACTION_PAUSE = "sagex.miniclient.download.PAUSE";
    public static final String ACTION_CANCEL = "sagex.miniclient.download.CANCEL";
    public static final String ACTION_PROGRESS = "sagex.miniclient.download.PROGRESS";
    public static final String ACTION_COMPLETE = "sagex.miniclient.download.COMPLETE";
    public static final String ACTION_ERROR = "sagex.miniclient.download.ERROR";

    public static final String EXTRA_MEDIA_FILE_ID = "mediaFileID";
    public static final String EXTRA_DOWNLOADED_BYTES = "downloadedBytes";
    public static final String EXTRA_TOTAL_BYTES = "totalBytes";
    public static final String EXTRA_ERROR_MESSAGE = "errorMessage";

    private DownloadNotificationHelper notificationHelper;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationHelper = new DownloadNotificationHelper(this);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sagetv:download");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (action == null) action = ACTION_START;

        switch (action) {
            case ACTION_START:
                startForeground(DownloadNotificationHelper.getProgressNotificationId(),
                        notificationHelper.buildIdleNotification());
                wakeLock.acquire(4 * 60 * 60 * 1000L); // 4 hours max
                break;

            case ACTION_STOP:
                releaseWakeLock();
                stopForeground(true);
                stopSelf();
                break;

            case ACTION_PAUSE:
                handlePause();
                break;

            case ACTION_CANCEL:
                handleCancel();
                break;

            case ACTION_PROGRESS:
                handleProgress(intent);
                break;

            case ACTION_COMPLETE:
                handleComplete(intent);
                break;

            case ACTION_ERROR:
                handleError(intent);
                break;
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        super.onDestroy();
    }

    private void handlePause() {
        DownloadManager manager = DownloadManager.getInstance(this);
        java.util.List<DownloadMetadata> active = manager.getRepository()
                .getByStatus(DownloadMetadata.Status.DOWNLOADING);
        for (DownloadMetadata meta : active) {
            manager.pause(meta.getMediaFileID());
        }
        releaseWakeLock();
        stopForeground(true);
        stopSelf();
    }

    private void handleCancel() {
        DownloadManager manager = DownloadManager.getInstance(this);
        java.util.List<DownloadMetadata> active = manager.getRepository()
                .getByStatus(DownloadMetadata.Status.DOWNLOADING);
        for (DownloadMetadata meta : active) {
            manager.cancel(meta.getMediaFileID());
        }
        releaseWakeLock();
        stopForeground(true);
        stopSelf();
    }

    private void handleProgress(Intent intent) {
        String mediaFileID = intent.getStringExtra(EXTRA_MEDIA_FILE_ID);
        long downloadedBytes = intent.getLongExtra(EXTRA_DOWNLOADED_BYTES, 0);
        long totalBytes = intent.getLongExtra(EXTRA_TOTAL_BYTES, 0);

        DownloadMetadata meta = DownloadManager.getInstance(this).getRepository()
                .getByMediaFileID(mediaFileID);
        String title = meta != null ? meta.getTitle() : mediaFileID;
        int percent = totalBytes > 0 ? (int) ((downloadedBytes * 100) / totalBytes) : 0;

        Notification notification = notificationHelper.buildProgressNotification(
                title, percent, downloadedBytes, totalBytes);
        notificationHelper.updateProgressNotification(title, percent, downloadedBytes, totalBytes);
    }

    private void handleComplete(Intent intent) {
        String mediaFileID = intent.getStringExtra(EXTRA_MEDIA_FILE_ID);
        DownloadMetadata meta = DownloadManager.getInstance(this).getRepository()
                .getByMediaFileID(mediaFileID);
        String title = meta != null ? meta.getTitle() : mediaFileID;
        notificationHelper.showCompleteNotification(title, mediaFileID);

        // Check if more downloads are queued
        java.util.List<DownloadMetadata> queued = DownloadManager.getInstance(this)
                .getRepository().getByStatus(DownloadMetadata.Status.QUEUED);
        if (queued.isEmpty()) {
            releaseWakeLock();
            stopForeground(true);
            stopSelf();
        }
    }

    private void handleError(Intent intent) {
        String mediaFileID = intent.getStringExtra(EXTRA_MEDIA_FILE_ID);
        String errorMessage = intent.getStringExtra(EXTRA_ERROR_MESSAGE);
        DownloadMetadata meta = DownloadManager.getInstance(this).getRepository()
                .getByMediaFileID(mediaFileID);
        String title = meta != null ? meta.getTitle() : mediaFileID;
        notificationHelper.showErrorNotification(title, errorMessage);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    // Static helper methods for sending intents to this service

    public static void updateProgress(Context context, String mediaFileID, long downloadedBytes, long totalBytes) {
        Intent intent = new Intent(context, DownloadForegroundService.class);
        intent.setAction(ACTION_PROGRESS);
        intent.putExtra(EXTRA_MEDIA_FILE_ID, mediaFileID);
        intent.putExtra(EXTRA_DOWNLOADED_BYTES, downloadedBytes);
        intent.putExtra(EXTRA_TOTAL_BYTES, totalBytes);
        try {
            context.startService(intent);
        } catch (Exception ignored) {
            // App may be in background during long remux; progress update is non-critical.
        }
    }

    public static void notifyComplete(Context context, String mediaFileID) {
        Intent intent = new Intent(context, DownloadForegroundService.class);
        intent.setAction(ACTION_COMPLETE);
        intent.putExtra(EXTRA_MEDIA_FILE_ID, mediaFileID);
        try {
            context.startService(intent);
        } catch (Exception ignored) {
            // App may be in background; best-effort notification.
        }
    }

    public static void notifyError(Context context, String mediaFileID, String errorMessage) {
        Intent intent = new Intent(context, DownloadForegroundService.class);
        intent.setAction(ACTION_ERROR);
        intent.putExtra(EXTRA_MEDIA_FILE_ID, mediaFileID);
        intent.putExtra(EXTRA_ERROR_MESSAGE, errorMessage);
        try {
            context.startService(intent);
        } catch (Exception ignored) {
            // App may be in background; best-effort notification.
        }
    }
}
