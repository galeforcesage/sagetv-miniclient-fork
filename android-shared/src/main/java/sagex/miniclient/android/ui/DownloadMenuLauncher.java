/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package sagex.miniclient.android.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launches the offline downloads menu from inside the renderer-hosting
 * activity. Resolved by class name so this shared module does not gain a
 * compile-time dependency on android-offline; in the online-only flavor the
 * fragment/activity is absent and the launch is silently skipped.
 *
 * <p>Preferred path (when called from an Activity): show
 * {@code DownloadsFragment} as an in-process DialogFragment overlay so the
 * underlying GL surface is never destroyed and the SageTV server-side menu
 * is not corrupted. Falls back to launching the standalone
 * {@code DownloadsActivity} only when no Activity context is available
 * (e.g. invoked from a Service / Application context).
 */
public final class DownloadMenuLauncher {
    private static final Logger log = LoggerFactory.getLogger(DownloadMenuLauncher.class);
    private static final String DOWNLOADS_FRAGMENT_CLASS =
            "sagex.miniclient.android.offline.DownloadsFragment";
    private static final String DOWNLOADS_ACTIVITY_CLASS =
            "sagex.miniclient.android.offline.DownloadsActivity";

    private DownloadMenuLauncher() {}

    public static boolean launch(Context context) {
        if (context == null) return false;

        if (context instanceof Activity) {
            Activity activity = (Activity) context;
            try {
                Class<?> cls = Class.forName(DOWNLOADS_FRAGMENT_CLASS);
                cls.getMethod("showDialog", Activity.class).invoke(null, activity);
                return true;
            } catch (ClassNotFoundException notFound) {
                log.debug("DownloadsFragment not present; falling back to standalone activity.");
            } catch (Throwable t) {
                log.warn("Failed to show DownloadsFragment, falling back to activity: {}", t.toString());
            }
        }

        try {
            Intent i = new Intent();
            i.setClassName(context, DOWNLOADS_ACTIVITY_CLASS);
            if (!(context instanceof Activity)) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(i);
            return true;
        } catch (ActivityNotFoundException notFound) {
            log.debug("DownloadsActivity not present in this build; ignoring launch request.");
            return false;
        } catch (Throwable t) {
            log.warn("Failed to launch downloads menu: {}", t.toString());
            return false;
        }
    }
}
