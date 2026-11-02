package sagex.miniclient.android.display;

import android.content.Context;
import android.os.Build;
import android.view.Display;
import android.view.WindowManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sagex.miniclient.android.ui.AndroidUIController;

/**
 * Shared NG display-measurement logic used by <em>both</em> Android UIRenderer
 * implementations ({@code MiniClientGDXRenderer} and {@code OpenGLRenderer}).
 *
 * <p>Historically this lived only in {@code MiniClientGDXRenderer}, so when the
 * OpenGL renderer became the default UI ({@code use_opengl_ui=true}) the NG 4K
 * sink / refresh-rate / HDR reports silently fell back to the empty
 * {@link sagex.miniclient.uibridge.UIRenderer} defaults and the server saw a
 * {@code 0x0} sink. Centralising the logic here keeps the two renderers from
 * ever drifting again: each renderer simply delegates its
 * {@code getDisplaySinkResolution()}, {@code getDisplayRefreshRates()},
 * {@code getDisplayHdrTypes()} and {@code isLocalOnlyMobilePanel()} overrides to
 * the matching static method below.</p>
 */
public final class DisplayMeasurement {
    private static final Logger log = LoggerFactory.getLogger(DisplayMeasurement.class);

    private DisplayMeasurement() {
    }

    /**
     * The {@link Display} the activity is actually rendering on. On an
     * activity-scoped WindowManager this returns the external display when the
     * activity has been relocated there, so the NG 4K sink report reflects the
     * real output surface.
     */
    public static Display getActiveDisplay(AndroidUIController activity) {
        Context appCtx = null;
        try { appCtx = sagex.miniclient.android.MiniclientApplication.get(); } catch (Throwable ignored) { }

        // A display can be non-null yet DEFUNCT (getMode()==null / zero physical
        // size) after an Activity teardown or a transient display handle. We must
        // reject such a display and fall through, otherwise the sink reports "".
        Display activityDisplay = null; // remembered as last-resort even if unusable

        // 1. Path B (surface move, mobile only): when video is presented on an
        // external display while the activity stays on the phone, the honest NG
        // 4K sink is that external panel. Resolve the override id through the
        // Application context (process-wide DisplayManager can resolve ANY display
        // id, including the external monitor) so it still works when the Activity
        // context has gone stale.
        try {
            Context ctx = (appCtx != null) ? appCtx
                    : ((activity != null) ? activity.getContext() : null);
            Display override = sagex.miniclient.android.display.ExternalDisplayController
                    .getSinkResolutionOverrideDisplay(ctx);
            if (override != null && hasUsableMode(override)) {
                log.info("getActiveDisplay: using external override display id={} {}",
                        override.getDisplayId(), describeDisplay(override));
                return override;
            }
        } catch (Throwable t) {
            log.info("getActiveDisplay: override path failed: {}", t.toString());
        }

        // 2. The activity's own display (follows the activity onto an external
        // display when relocated via Path A). Relies on a live Activity context,
        // which can hand back a defunct display after sleep/background.
        try {
            WindowManager wm = (activity != null)
                    ? (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE) : null;
            if (wm != null) {
                Display d = wm.getDefaultDisplay();
                if (d != null) {
                    activityDisplay = d;
                    if (hasUsableMode(d)) {
                        log.info("getActiveDisplay: using activity display id={} {}",
                                d.getDisplayId(), describeDisplay(d));
                        return d;
                    }
                    log.info("getActiveDisplay: activity display id={} is defunct ({}), falling through",
                            d.getDisplayId(), describeDisplay(d));
                }
            } else {
                log.info("getActiveDisplay: activity WindowManager unavailable");
            }
        } catch (Throwable t) {
            log.info("getActiveDisplay: activity path failed: {}", t.toString());
        }

        // 3. Fallback: process-wide default display via the Application context's
        // DisplayManager. Robust to a destroyed/stale Activity (the renderer is
        // connection-scoped and can outlive an Activity after sleep/background,
        // which otherwise leaves the sink unreadable until a full restart). On a
        // TV/online client DEFAULT_DISPLAY is exactly the HDMI output panel; on a
        // phone with no external output it is the phone panel (the honest sink).
        try {
            if (appCtx != null) {
                android.hardware.display.DisplayManager dm =
                        (android.hardware.display.DisplayManager) appCtx.getSystemService(Context.DISPLAY_SERVICE);
                if (dm != null) {
                    Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
                    if (d != null && hasUsableMode(d)) {
                        log.info("getActiveDisplay: recovered via Application DEFAULT_DISPLAY id={} {}",
                                d.getDisplayId(), describeDisplay(d));
                        return d;
                    }
                    log.info("getActiveDisplay: Application DEFAULT_DISPLAY unusable ({})",
                            describeDisplay(d));
                }
            } else {
                log.info("getActiveDisplay: no Application context for fallback");
            }
        } catch (Throwable t) {
            log.info("getActiveDisplay: application fallback failed: {}", t.toString());
        }

        // Last resort: the activity display even if it looked defunct (better than
        // null; SinkResolutionResolver will still fail-closed to "" if truly bad).
        if (activityDisplay != null) {
            log.info("getActiveDisplay: no usable display found, returning activity display id={} {}",
                    activityDisplay.getDisplayId(), describeDisplay(activityDisplay));
            return activityDisplay;
        }
        log.info("getActiveDisplay: no display available at all");
        return null;
    }

    /** True when the display exposes a mode with a positive physical size. */
    private static boolean hasUsableMode(Display d) {
        if (d == null || Build.VERSION.SDK_INT < 23) return false;
        try {
            Display.Mode m = d.getMode();
            return m != null && m.getPhysicalWidth() > 0 && m.getPhysicalHeight() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Compact diagnostic string: "state/validMode/physWxH". */
    private static String describeDisplay(Display d) {
        if (d == null) return "null";
        try {
            int state = -1;
            try { state = d.getState(); } catch (Throwable ignored) { }
            if (Build.VERSION.SDK_INT >= 23) {
                Display.Mode m = d.getMode();
                if (m != null) {
                    return "state=" + state + " phys=" + m.getPhysicalWidth() + "x" + m.getPhysicalHeight();
                }
                return "state=" + state + " mode=null";
            }
            return "state=" + state + " api<23";
        } catch (Throwable t) {
            return "describe-failed:" + t;
        }
    }

    public static String getDisplaySinkResolution(AndroidUIController activity) {
        try {
            return SinkResolutionResolver.resolveSink(getActiveDisplay(activity));
        } catch (Throwable t) {
            return "";
        }
    }

    public static String getDisplayRefreshRates(AndroidUIController activity) {
        try {
            return SinkResolutionResolver.refreshRates(getActiveDisplay(activity));
        } catch (Throwable t) {
            return "";
        }
    }

    public static String getDisplayHdrTypes(AndroidUIController activity) {
        try {
            return SinkResolutionResolver.hdrTypes(getActiveDisplay(activity));
        } catch (Throwable t) {
            return "";
        }
    }

    public static boolean isLocalOnlyMobilePanel(AndroidUIController activity) {
        try {
            Context appCtx = null;
            try { appCtx = sagex.miniclient.android.MiniclientApplication.get(); } catch (Throwable ignored) { }
            if (appCtx == null && activity != null) appCtx = activity.getContext();
            if (appCtx == null) return false;

            // Mobile/phone flavor only. Online TV/box builds set this false, so
            // Auto/Always/Never all stay honest there (always a large HDMI sink).
            if (!appCtx.getResources().getBoolean(sagex.miniclient.android.R.bool.feature_external_display)) {
                return false;
            }

            // Safety net for a mismatched install: if the mobile/phone APK was
            // sideloaded onto an actual TV/box (leanback / UI_MODE TELEVISION),
            // the device always drives a real HDMI sink and has no separate
            // "presentation" display, so the external-display probe below would
            // wrongly report local-only and collapse Auto -> Never on a 4K TV.
            // Treat such a device like the online flavor (never local-only).
            // Only strong positive TV signals are used so this can never
            // misfire on a genuine phone/tablet.
            if (isTelevisionDevice(appCtx)) {
                return false;
            }

            // Is an external HDMI/cast/presentation display currently attached?
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) appCtx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) {
                // Can't tell: treat as local-only (phone-safe: Auto -> Never).
                return true;
            }
            android.view.Display[] pres = dm.getDisplays(
                    android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
            boolean externalActive = (pres != null && pres.length > 0);
            if (!externalActive) {
                // Fallback: any non-default display is an external output surface.
                android.view.Display[] all = dm.getDisplays();
                if (all != null) {
                    for (android.view.Display d : all) {
                        if (d != null && d.getDisplayId() != android.view.Display.DEFAULT_DISPLAY) {
                            externalActive = true;
                            break;
                        }
                    }
                }
            }
            return !externalActive;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Conservative "is this an Android TV / set-top box?" heuristic used only as
     * a safety net for the 4K upscale gate. Returns true only on strong,
     * TV-exclusive signals that never appear on a genuine phone/tablet:
     * {@code UiModeManager} reporting {@code UI_MODE_TYPE_TELEVISION}, or the
     * {@code android.software.leanback} / {@code android.hardware.type.television}
     * system features. Weak signals (e.g. absence of a touchscreen) are
     * intentionally not used, so a real phone is never misclassified as a TV.
     */
    private static boolean isTelevisionDevice(Context ctx) {
        try {
            android.app.UiModeManager uiMode =
                    (android.app.UiModeManager) ctx.getSystemService(Context.UI_MODE_SERVICE);
            if (uiMode != null
                    && uiMode.getCurrentModeType()
                        == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) {
                return true;
            }
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            if (pm != null) {
                if (pm.hasSystemFeature("android.software.leanback")) return true;
                if (pm.hasSystemFeature("android.hardware.type.television")) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
