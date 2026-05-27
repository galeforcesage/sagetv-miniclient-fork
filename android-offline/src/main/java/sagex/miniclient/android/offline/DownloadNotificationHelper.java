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
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/**
 * Builds download notifications (progress, complete, error) and manages
 * the notification channel.
 */
public class DownloadNotificationHelper {
    public static final String CHANNEL_ID = "sagetv_downloads";
    private static final String CHANNEL_NAME = "Downloads";
    private static final int NOTIFICATION_ID_PROGRESS = 9001;
    private static final int NOTIFICATION_ID_COMPLETE_BASE = 9100;
    private static final int NOTIFICATION_ID_ERROR_BASE = 9200;

    private final Context context;
    private final NotificationManager notificationManager;

    public DownloadNotificationHelper(Context context) {
        this.context = context.getApplicationContext();
        this.notificationManager = (NotificationManager) this.context.getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("SageTV media file downloads");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    public Notification buildProgressNotification(String title, int progressPercent, long downloadedBytes, long totalBytes) {
        String progressText = formatBytes(downloadedBytes) + " / " + formatBytes(totalBytes);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title != null ? title : "Downloading...")
                .setContentText(progressText)
                .setProgress(100, progressPercent, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS);

        // Pause action
        Intent pauseIntent = new Intent(context, DownloadForegroundService.class);
        pauseIntent.setAction(DownloadForegroundService.ACTION_PAUSE);
        PendingIntent pausePending = PendingIntent.getService(context, 0, pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.addAction(android.R.drawable.ic_media_pause, "Pause", pausePending);

        // Cancel action
        Intent cancelIntent = new Intent(context, DownloadForegroundService.class);
        cancelIntent.setAction(DownloadForegroundService.ACTION_CANCEL);
        PendingIntent cancelPending = PendingIntent.getService(context, 1, cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.addAction(android.R.drawable.ic_delete, "Cancel", cancelPending);

        return builder.build();
    }

    public Notification buildIdleNotification() {
        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("SageTV Downloads")
                .setContentText("Preparing download...")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    public void showCompleteNotification(String title, String mediaFileID) {
        int notifId = NOTIFICATION_ID_COMPLETE_BASE + (mediaFileID.hashCode() & 0xFF);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Download Complete")
                .setContentText(title != null ? title : mediaFileID)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        notificationManager.notify(notifId, builder.build());
    }

    public void showErrorNotification(String title, String errorMessage) {
        int notifId = NOTIFICATION_ID_ERROR_BASE + (title != null ? title.hashCode() & 0xFF : 0);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Download Failed")
                .setContentText(title != null ? title : "Unknown error")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(errorMessage))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        notificationManager.notify(notifId, builder.build());
    }

    public void updateProgressNotification(String title, int progressPercent, long downloadedBytes, long totalBytes) {
        Notification notification = buildProgressNotification(title, progressPercent, downloadedBytes, totalBytes);
        notificationManager.notify(NOTIFICATION_ID_PROGRESS, notification);
    }

    public void cancelProgressNotification() {
        notificationManager.cancel(NOTIFICATION_ID_PROGRESS);
    }

    public static int getProgressNotificationId() {
        return NOTIFICATION_ID_PROGRESS;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
