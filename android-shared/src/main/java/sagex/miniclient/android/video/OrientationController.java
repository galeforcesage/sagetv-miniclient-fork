package sagex.miniclient.android.video;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;

import androidx.preference.PreferenceManager;

/**
 * Client-side screen orientation control for non-Leanback (phone/tablet/foldable)
 * devices. On Leanback (Android TV) this class is a no-op and the activity stays
 * locked to landscape (TVs don't rotate).
 *
 * <p>Three modes cycle in order:
 * <ol>
 *   <li>{@link Mode#LANDSCAPE} - locked landscape (default; matches legacy behavior)</li>
 *   <li>{@link Mode#PORTRAIT}  - locked portrait</li>
 *   <li>{@link Mode#AUTO}      - follow device sensor (auto-rotate)</li>
 * </ol>
 *
 * <p>The phone activities declare {@code configChanges="orientation|screenSize"}
 * in the manifest, so Android does NOT recreate the activity on rotation. The
 * GL/GDX surface, the video FrameLayout, and the SageTV UI all stay alive; only
 * their dimensions change. Server is told via the next Resize event and replays
 * its draw pipeline at the new size.
 */
public final class OrientationController
{
    public static final String PREF_KEY = "non_leanback_orientation_mode";

    public enum Mode
    {
        LANDSCAPE(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, "Landscape"),
        PORTRAIT(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "Portrait"),
        AUTO(ActivityInfo.SCREEN_ORIENTATION_SENSOR, "Auto-rotate");

        public final int activityInfoValue;
        public final String label;

        Mode(int activityInfoValue, String label)
        {
            this.activityInfoValue = activityInfoValue;
            this.label = label;
        }

        public Mode next()
        {
            switch (this)
            {
                case LANDSCAPE: return PORTRAIT;
                case PORTRAIT:  return AUTO;
                case AUTO:      return LANDSCAPE;
                default:        return LANDSCAPE;
            }
        }
    }

    private static Boolean cachedIsLeanback = null;

    private OrientationController() {}

    public static boolean isLeanback(Context ctx)
    {
        if (cachedIsLeanback != null) return cachedIsLeanback;
        if (ctx == null) return false;
        cachedIsLeanback = ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
        return cachedIsLeanback;
    }

    public static Mode getMode(Context ctx)
    {
        if (ctx == null) return Mode.LANDSCAPE;
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(ctx);
        String v = sp.getString(PREF_KEY, Mode.LANDSCAPE.name());
        try { return Mode.valueOf(v); } catch (Exception e) { return Mode.LANDSCAPE; }
    }

    public static void setMode(Context ctx, Mode mode)
    {
        if (ctx == null || mode == null) return;
        PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit().putString(PREF_KEY, mode.name()).apply();
    }

    /**
     * Apply the stored orientation mode to the given activity. No-op on Leanback.
     * Safe to call from {@code Activity.onCreate()} after {@code super.onCreate()}.
     */
    public static void apply(Activity activity)
    {
        if (activity == null || isLeanback(activity)) return;
        Mode mode = getMode(activity);
        try
        {
            activity.setRequestedOrientation(mode.activityInfoValue);
        }
        catch (Throwable t)
        {
            // some restricted contexts (e.g. embedded) reject this; ignore.
        }
    }

    /**
     * Advance to the next mode, persist it, and apply it to the given activity.
     * Returns the newly-active Mode (or the current mode unchanged on Leanback).
     */
    public static Mode cycleAndApply(Activity activity)
    {
        if (activity == null || isLeanback(activity)) return Mode.LANDSCAPE;
        Mode next = getMode(activity).next();
        setMode(activity, next);
        try
        {
            activity.setRequestedOrientation(next.activityInfoValue);
        }
        catch (Throwable t) { /* ignore */ }
        return next;
    }
}
