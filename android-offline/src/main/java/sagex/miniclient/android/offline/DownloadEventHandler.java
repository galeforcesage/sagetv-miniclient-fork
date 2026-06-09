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
import sagex.miniclient.MiniClient;
import sagex.miniclient.ServerInfo;
import sagex.miniclient.android.MiniclientApplication;
import sagex.miniclient.events.ConnectedEvent;
import sagex.miniclient.events.DownloadRequestEvent;
import sagex.miniclient.events.DownloadTransferControlEvent;
import sagex.miniclient.events.DownloadTransferSessionErrorEvent;
import sagex.miniclient.events.OfflineGuideSnapshotEvent;
import sagex.miniclient.events.OfflineScheduleSnapshotEvent;

/**
 * EventBus subscriber that bridges DownloadRequestEvent (fired from core protocol layer)
 * to the Android DownloadManager. Registered with the bus in MiniclientApplication.
 */
public class DownloadEventHandler {
    private static final Logger log = LoggerFactory.getLogger(DownloadEventHandler.class);

    private final Context context;
    private final OfflineEpgRepository epgRepository;

    public DownloadEventHandler(Context context) {
        this.context = context.getApplicationContext();
        this.epgRepository = new OfflineEpgRepository(this.context);
    }

    @Subscribe
    public void onConnected(ConnectedEvent event) {
        OfflinePlaybackStateSync.syncAllCompleteAsync(context, "ng_connect");
    }

    @Subscribe
    public void onDownloadRequest(DownloadRequestEvent event) {
        DownloadRequest request = event.getRequest();
        if (request == null) {
            log.warn("Received null DownloadRequestEvent");
            return;
        }

        log.info("DownloadRequestEvent received: {}", request);

        // Keep download starts non-intrusive while user is browsing SageTV menus.
        // On Android 13+, notification permission may be missing; we log that state
        // but do not trigger a runtime prompt from this background event path.
        logNotificationPermissionState();

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

    @Subscribe
    public void onTransferControl(DownloadTransferControlEvent event) {
        if (event == null || event.getAction() == null) {
            return;
        }
        DownloadManager manager = DownloadManager.getInstance(context);
        switch (event.getAction()) {
            case PAUSE:
                manager.onServerPause(event.getSessionToken(), event.getMediaFileID(), event.getBytesTransferred());
                break;
            case RESUME:
                manager.onServerResume(event.getSessionToken(), event.getMediaFileID(), event.getDownloadUrl(),
                        event.getBytesTransferred(), event.getSessionState());
                break;
            case CANCEL:
                manager.onServerCancel(event.getSessionToken(), event.getMediaFileID());
                break;
            default:
                break;
        }
    }

    @Subscribe
    public void onTransferSessionError(DownloadTransferSessionErrorEvent event) {
        if (event == null) {
            return;
        }
        DownloadManager manager = DownloadManager.getInstance(context);
        manager.onTransferSessionError(
                event.getMediaFileID(),
                event.getCorrelationId(),
                event.getErrorCode(),
                event.getMessage(),
                event.isRetriable());
    }

    @Subscribe
    public void onOfflineGuideSnapshot(OfflineGuideSnapshotEvent event) {
        if (event == null || event.getJson() == null || event.getJson().isEmpty()) return;
        String[] server = currentServerIdentity();
        epgRepository.replaceGuideSnapshot(server[0], server[1], event.getJson());
        log.info("Offline guide snapshot persisted");
    }

    @Subscribe
    public void onOfflineScheduleSnapshot(OfflineScheduleSnapshotEvent event) {
        if (event == null || event.getJson() == null || event.getJson().isEmpty()) return;
        String[] server = currentServerIdentity();
        epgRepository.replaceScheduleSnapshot(server[0], server[1], event.getJson());
        log.info("Offline schedule snapshot persisted");
    }

    private static String[] currentServerIdentity() {
        MiniClient client = MiniclientApplication.get().getClient();
        ServerInfo si = client != null ? client.getConnectedServerInfo() : null;
        if (si == null) return new String[]{"legacy", "Unknown Server"};
        String address = si.address == null ? "" : si.address;
        String id = address + ":" + si.port;
        String name = si.name == null || si.name.trim().isEmpty() ? id : si.name.trim();
        return new String[]{id, name};
    }

    private void logNotificationPermissionState() {
        if (Build.VERSION.SDK_INT < 33) return; // POST_NOTIFICATIONS only required on API 33+

        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Activity activity = MiniclientApplication.get().getCurrentActivity();
        String activityName = activity != null ? activity.getClass().getSimpleName() : "none";
        log.info("POST_NOTIFICATIONS not granted; continuing without prompt (activity={}). "
                + "Grant via system settings if foreground notifications are desired.", activityName);
    }
}
