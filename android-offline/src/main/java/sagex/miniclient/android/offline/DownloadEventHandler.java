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

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import com.squareup.otto.Subscribe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sagex.miniclient.DownloadRequest;
import sagex.miniclient.android.MiniclientApplication;
import sagex.miniclient.events.DownloadRequestEvent;

/**
 * EventBus subscriber that bridges DownloadRequestEvent (fired from core protocol layer)
 * to the Android DownloadManager. Registered with the bus in MiniclientApplication.
 */
public class DownloadEventHandler {
    private static final Logger log = LoggerFactory.getLogger(DownloadEventHandler.class);

    private final Context context;

    public DownloadEventHandler(Context context) {
        this.context = context.getApplicationContext();
    }

    @Subscribe
    public void onDownloadRequest(DownloadRequestEvent event) {
        DownloadRequest request = event.getRequest();
        if (request == null) {
            log.warn("Received null DownloadRequestEvent");
            return;
        }

        log.info("DownloadRequestEvent received: {}", request);

        // Request POST_NOTIFICATIONS permission on API 33+ if not yet granted
        requestNotificationPermissionIfNeeded();

        DownloadManager manager = DownloadManager.getInstance(context);

        // Check if storage location is configured
        if (!manager.getStorageHelper().hasStorageLocation()
                && !manager.getStorageHelper().shouldUseSAF()) {
            // For non-SAF devices (pre-Android 11), use fallback directory automatically
            log.info("Using fallback storage directory for download");
        }
        // For SAF devices without configured storage, the download will use fallback
        // until the user configures storage via settings. A future UI prompt will handle
        // the SAF picker flow interactively.

        boolean enqueued = manager.enqueue(request);
        if (enqueued) {
            log.info("Download successfully enqueued: {}", request.getMediaFileID());
        } else {
            log.warn("Failed to enqueue download: {}", request.getMediaFileID());
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return; // POST_NOTIFICATIONS only required on API 33+

        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return; // Already granted
        }

        // Find the current foreground activity to request permission
        try {
            Activity activity = MiniclientApplication.get().getCurrentActivity();
            if (activity != null) {
                activity.requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 9002);
                log.info("Requested POST_NOTIFICATIONS permission");
            } else {
                log.warn("No foreground activity to request notification permission; "
                        + "notifications may be suppressed until granted via settings");
            }
        } catch (Exception e) {
            log.warn("Failed to request notification permission: {}", e.getMessage());
        }
    }
}
