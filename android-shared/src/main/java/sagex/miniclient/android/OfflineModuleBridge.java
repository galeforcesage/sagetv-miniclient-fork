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
package sagex.miniclient.android;

import android.app.Activity;
import android.app.Application;
import android.content.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

import sagex.miniclient.MiniClient;

/**
 * Reflection bridge to the optional android-offline module.
 *
 * The base (online-only) android-shared code does NOT have a compile-time
 * dependency on the offline module. This class probes for the offline
 * module's bootstrap class at runtime — when the APK was built with the
 * "mobile" flavor (android-offline included), the calls succeed and the
 * offline features light up. When the APK was built with the "online"
 * flavor (no android-offline), every call is a quiet no-op.
 */
public final class OfflineModuleBridge {
    private static final Logger log = LoggerFactory.getLogger(OfflineModuleBridge.class);
    private static final String BOOTSTRAP_CLASS = "sagex.miniclient.android.offline.OfflineBootstrap";

    private static volatile Class<?> bootstrap;
    private static volatile boolean probed;

    private OfflineModuleBridge() {}

    /** Returns true if the offline module is present in this APK. */
    public static boolean isAvailable() {
        if (!probed) {
            synchronized (OfflineModuleBridge.class) {
                if (!probed) {
                    try {
                        bootstrap = Class.forName(BOOTSTRAP_CLASS);
                        log.info("Offline module detected — download features enabled");
                    } catch (ClassNotFoundException e) {
                        bootstrap = null;
                        log.info("Offline module not present — running online-only");
                    }
                    probed = true;
                }
            }
        }
        return bootstrap != null;
    }

    public static void init(Application app, MiniClient client) {
        if (!isAvailable()) return;
        invoke("init", new Class<?>[]{Application.class, MiniClient.class},
                new Object[]{app, client});
    }

    public static void launchDownloadsActivity(Activity from) {
        if (!isAvailable()) return;
        invoke("launchDownloadsActivity", new Class<?>[]{Activity.class},
                new Object[]{from});
    }

    public static void launchOfflineLibrary(Context from) {
        if (!isAvailable()) return;
        invoke("launchOfflineLibrary", new Class<?>[]{Context.class},
                new Object[]{from});
    }

    public static void launchOfflineHome(Context from) {
        if (!isAvailable()) return;
        invoke("launchOfflineHome", new Class<?>[]{Context.class},
                new Object[]{from});
    }

    public static boolean hasAnyDownloads(Context ctx) {
        if (!isAvailable()) return false;
        Object result = invoke("hasAnyDownloads", new Class<?>[]{Context.class},
                new Object[]{ctx});
        return Boolean.TRUE.equals(result);
    }

    public static boolean hasAnyOfflineContent(Context ctx) {
        if (!isAvailable()) return false;
        Object result = invoke("hasAnyOfflineContent", new Class<?>[]{Context.class},
                new Object[]{ctx});
        return Boolean.TRUE.equals(result);
    }

    private static Object invoke(String name, Class<?>[] params, Object[] args) {
        try {
            Method m = bootstrap.getMethod(name, params);
            return m.invoke(null, args);
        } catch (Throwable t) {
            log.error("OfflineModuleBridge.{} failed", name, t);
            return null;
        }
    }
}
