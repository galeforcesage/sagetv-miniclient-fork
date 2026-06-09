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
import android.app.Application;
import android.content.Context;
import android.content.Intent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sagex.miniclient.MiniClient;

/**
 * Public entry point of the android-offline module. The base (online-only)
 * code in android-shared looks this class up by name via reflection and
 * calls {@link #init(Application, MiniClient)} during app startup. If the
 * android-offline module is not in the APK (online flavor), the reflection
 * lookup quietly fails and no offline machinery is wired up.
 *
 * This is the ONLY symbol the base code knows about — everything else stays
 * encapsulated inside this module.
 */
public final class OfflineBootstrap {
    private static final Logger log = LoggerFactory.getLogger(OfflineBootstrap.class);

    private OfflineBootstrap() {}

    /**
     * Wire up the download notification channel, event handler, and status
     * provider. Called once during Application.onCreate from MiniclientApplication.
     */
    public static void init(Application app, MiniClient client) {
        try {
            new DownloadNotificationHelper(app); // creates the notification channel
            DownloadEventHandler downloadHandler = new DownloadEventHandler(app);
            client.eventbus().register(downloadHandler);
            DownloadManager dm = DownloadManager.getInstance(app);
            client.setDownloadStatusProvider(dm);
            DownloadWorkScheduler.schedule(app);
            dm.recoverInterruptedSessions();
            log.info("Offline/download module initialised");
        } catch (Throwable t) {
            log.error("Failed to initialise offline module", t);
        }
    }

    /** Launches the offline downloads-management settings screen. */
    public static void launchDownloadsActivity(Activity from) {
        from.startActivity(new Intent(from, DownloadsActivity.class));
    }

    /** Launches the offline media library (browse + play downloaded content). */
    public static void launchOfflineLibrary(Context from) {
        Intent i = new Intent(from, OfflineLibraryActivity.class);
        if (!(from instanceof Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        from.startActivity(i);
    }

    /** Launches the SageTV-style offline home shell (Library/Guide/Schedule/etc). */
    public static void launchOfflineHome(Context from) {
        Intent i = new Intent(from, OfflineHomeActivity.class);
        if (!(from instanceof Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        from.startActivity(i);
    }

    /**
     * Returns true if at least one download exists in the local store
     * (queued, in-progress, paused, failed, or complete). Used by the
     * UI to decide whether to show the "Offline Library" entry.
     */
    public static boolean hasAnyDownloads(Context ctx) {
        try {
            return !DownloadManager.getInstance(ctx).getQueue().isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Returns true if any offline content is present: downloaded recordings,
     * guide cache, scheduled recordings snapshot, or favorites snapshot.
     */
    public static boolean hasAnyOfflineContent(Context ctx) {
        try {
            if (hasAnyDownloads(ctx)) {
                return true;
            }
            return new OfflineEpgRepository(ctx).hasAnySnapshotContent();
        } catch (Throwable t) {
            return false;
        }
    }
}
