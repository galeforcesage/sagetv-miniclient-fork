package sagex.miniclient.android.display;

import android.os.Build;
import android.view.Display;

import java.util.Locale;
import java.util.TreeSet;

/**
 * NG Server Video Enhancement (4K upscale) contract, Phase 1: reports the honest
 * physical sink the client is driving via {@code DISPLAY_SINK_RESOLUTION}, plus
 * the optional refresh-rate and HDR-type refinements.
 *
 * <h3>Why this is a pure measurement (server contract §2.1 + revision §7.3)</h3>
 * The sink resolution is a <b>measurement, not a preference</b>. The server now
 * treats an <em>absent</em> {@code DISPLAY_SINK_RESOLUTION} as an
 * <b>abstention</b> ("don't know"), NOT a refusal: it will still infer
 * enhancement eligibility from the per-codec decode ceilings (§2.7) and simply
 * <b>skip the panel clamp</b>. Withholding the sink therefore only removes the
 * ceiling that would have capped the target — strictly worse for the user than
 * sending the honest value.
 *
 * <p>So this resolver <b>always returns the true physical panel size</b> whenever
 * it can read it, on every device class. It never fabricates 4K, and it never
 * suppresses a small panel: reporting a phone's real {@code 2400x1080} is exactly
 * what makes the server clamp enhancement to that panel instead of wasting
 * bandwidth on a 4K stream the phone can only downscale.</p>
 *
 * <p>The user's Auto / Always / Never preference is expressed separately via
 * {@code QUALITY_HINT} (auto / quality / savings) — see the connection layer — so
 * it is not encoded in this measurement.</p>
 *
 * <p>Returns {@code ""} only when the size is genuinely unknown (API &lt; 23, no
 * active mode, or a zero size) — fail-closed.</p>
 */
public final class SinkResolutionResolver
{
    private SinkResolutionResolver() { }

    /**
     * @param display the display the app is actually rendering on (the activity's
     *                {@code WindowManager.getDefaultDisplay()}, which follows the
     *                activity onto an external/extended display when relocated)
     * @return {@code "WIDTHxHEIGHT"} of the honest physical panel (landscape-
     *         normalized), or {@code ""} when unknown.
     */
    public static String resolveSink(Display display)
    {
        int[] physical = physicalSize(display);
        return (physical == null) ? "" : formatLandscape(physical[0], physical[1]);
    }

    /** Comma-separated supported refresh rates, or "" when unknown. */
    public static String refreshRates(Display display)
    {
        if (display == null || Build.VERSION.SDK_INT < 23) return "";
        try
        {
            final TreeSet<String> rates = new TreeSet<>();
            for (Display.Mode m : display.getSupportedModes())
            {
                if (m == null) continue;
                float r = m.getRefreshRate();
                if (r > 0) rates.add(String.format(Locale.US, "%.2f", r));
            }
            return String.join(",", rates);
        }
        catch (Throwable ignored)
        {
            return "";
        }
    }

    /** Comma-separated HDR types the display advertises, "none", or "". */
    public static String hdrTypes(Display display)
    {
        if (display == null || Build.VERSION.SDK_INT < 24) return "";
        try
        {
            Display.HdrCapabilities caps = display.getHdrCapabilities();
            if (caps == null) return "";
            int[] types = caps.getSupportedHdrTypes();
            if (types == null || types.length == 0) return "none";
            final TreeSet<String> names = new TreeSet<>();
            for (int t : types)
            {
                switch (t)
                {
                    case Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION: names.add("DOLBY_VISION"); break;
                    case Display.HdrCapabilities.HDR_TYPE_HDR10:        names.add("HDR10"); break;
                    case Display.HdrCapabilities.HDR_TYPE_HLG:          names.add("HLG"); break;
                    default:
                        if (Build.VERSION.SDK_INT >= 29
                                && t == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS)
                        {
                            names.add("HDR10_PLUS");
                        }
                        break;
                }
            }
            return names.isEmpty() ? "none" : String.join(",", names);
        }
        catch (Throwable ignored)
        {
            return "";
        }
    }

    // ---- internals (package-visible for unit tests) ----

    /** Pure, Android-free landscape-normalized "WxH", or "" when unreadable. */
    static String formatLandscape(int w, int h)
    {
        if (w <= 0 || h <= 0) return "";
        if (h > w) { int t = w; w = h; h = t; }
        return w + "x" + h;
    }

    /** True physical pixels of the display's active mode, or null when unknown. */
    static int[] physicalSize(Display display)
    {
        if (display == null || Build.VERSION.SDK_INT < 23) return null;
        try
        {
            Display.Mode m = display.getMode();
            if (m == null) return null;
            int w = m.getPhysicalWidth();
            int h = m.getPhysicalHeight();
            if (w <= 0 || h <= 0) return null;
            return new int[] { w, h };
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }
}
