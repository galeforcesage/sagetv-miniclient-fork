package sagex.miniclient.android.display;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 1 external-display output: detection of a genuine <b>extended</b> (not
 * mirrored) secondary display that the SageTV UI activity can be relocated to
 * via {@link android.app.ActivityOptions#setLaunchDisplayId(int)}.
 *
 * <p>The hard OS constraint (documented, not a bug): plain USB-C&rarr;HDMI on
 * most stock phones <em>mirrors</em> the panel — the framebuffer stays the phone
 * panel and no distinct display id is exposed. Only Samsung DeX / desktop-mode /
 * DisplayPort-extended output exposes a separate presentation display, which is
 * what {@link DisplayManager#DISPLAY_CATEGORY_PRESENTATION} enumerates. This
 * controller only reports a display when a real extended surface is present, so
 * mirror-only devices simply never trigger the feature.</p>
 *
 * <p>Read-only, no listeners held here; callers evaluate on demand (at connect
 * time and when the user toggles "Play on TV / bring back").</p>
 */
public final class ExternalDisplayController
{
    private static final Logger log = LoggerFactory.getLogger(ExternalDisplayController.class);

    private ExternalDisplayController() { }

    /**
     * @return the display id of a usable external/extended presentation display,
     *         or {@code -1} when none is present (including mirror-only setups).
     */
    public static int getExternalDisplayId(Context ctx)
    {
        try
        {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return -1;

            // DISPLAY_CATEGORY_PRESENTATION returns only real presentation
            // displays (extended surfaces), ordered by preference. This is the
            // honest "is there a genuine second screen?" query.
            Display[] pres = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
            if (pres != null)
            {
                for (Display d : pres)
                {
                    if (d == null) continue;
                    if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
                    if (d.getState() == Display.STATE_OFF) continue;
                    return d.getDisplayId();
                }
            }

            // Fallback: some devices expose an extended display without tagging
            // it as a presentation display. Accept any valid, ON, non-default
            // display that is NOT flagged as a mirror of the built-in panel.
            Display[] all = dm.getDisplays();
            if (all != null)
            {
                for (Display d : all)
                {
                    if (d == null) continue;
                    if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
                    if (d.getState() == Display.STATE_OFF) continue;
                    // FLAG_PRESENTATION marks a display suitable for showing
                    // distinct content (extended). Require it for the fallback so
                    // we never latch onto a mirror.
                    if ((d.getFlags() & Display.FLAG_PRESENTATION) != 0)
                    {
                        return d.getDisplayId();
                    }
                }
            }
        }
        catch (Throwable t)
        {
            log.warn("ExternalDisplayController: display query failed", t);
        }
        return -1;
    }

    /** Convenience: true when a usable external/extended display is present. */
    public static boolean hasUsableExternalDisplay(Context ctx)
    {
        return getExternalDisplayId(ctx) >= 0;
    }

    /**
     * @return the real pixel size of the given display id, or {@code null} if it
     *   cannot be resolved. Used to launch the UI maximized (full-bleed) on an
     *   external display, since desktop-mode / DeX otherwise opens apps in a
     *   freeform window.
     */
    public static android.graphics.Point getDisplaySize(Context ctx, int displayId)
    {
        try
        {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return null;
            Display d = dm.getDisplay(displayId);
            if (d == null) return null;
            android.graphics.Point p = new android.graphics.Point();
            d.getRealSize(p);
            if (p.x <= 0 || p.y <= 0)
            {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
                {
                    Display.Mode m = d.getMode();
                    if (m != null && m.getPhysicalWidth() > 0 && m.getPhysicalHeight() > 0)
                    {
                        p.set(m.getPhysicalWidth(), m.getPhysicalHeight());
                    }
                }
            }
            return (p.x > 0 && p.y > 0) ? p : null;
        }
        catch (Throwable t)
        {
            log.warn("getDisplaySize failed for id={}", displayId, t);
            return null;
        }
    }

    /**
     * Spike / diagnostic: dump every display the OS reports (id, name, flags,
     * state, size) so we can confirm on real hardware whether DeX / DP-extended
     * exposes a distinct display id versus plain HDMI mirroring.
     */
    public static void logDisplays(Context ctx, String where)
    {
        try
        {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null)
            {
                log.info("EXTDISPLAY[{}]: no DisplayManager", where);
                return;
            }
            Display[] all = dm.getDisplays();
            int count = (all == null) ? 0 : all.length;
            log.info("EXTDISPLAY[{}]: {} display(s) total; chosenExternalId={}",
                    where, count, getExternalDisplayId(ctx));
            if (all == null) return;
            for (Display d : all)
            {
                if (d == null) continue;
                android.graphics.Point size = new android.graphics.Point();
                try { d.getRealSize(size); } catch (Throwable ignore) { }
                int flags = d.getFlags();
                log.info("EXTDISPLAY[{}]:   id={} name='{}' state={} size={}x{} "
                                + "presentation={} private={} default={}",
                        where, d.getDisplayId(), d.getName(), d.getState(),
                        size.x, size.y,
                        ((flags & Display.FLAG_PRESENTATION) != 0),
                        ((flags & Display.FLAG_PRIVATE) != 0),
                        (d.getDisplayId() == Display.DEFAULT_DISPLAY));
                logModes(d, where);
            }
        }
        catch (Throwable t)
        {
            log.warn("EXTDISPLAY[{}]: logDisplays failed", where, t);
        }
    }

    /** Dump the current + all supported modes for a display (diagnosis of 4K availability). */
    private static void logModes(Display d, String where)
    {
        try
        {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return;
            Display.Mode cur = d.getMode();
            if (cur != null)
            {
                log.info("EXTDISPLAY[{}]:     current mode id={} {}x{} @ {}fps",
                        where, cur.getModeId(), cur.getPhysicalWidth(),
                        cur.getPhysicalHeight(), Math.round(cur.getRefreshRate()));
            }
            Display.Mode[] modes = d.getSupportedModes();
            if (modes == null) return;
            for (Display.Mode m : modes)
            {
                if (m == null) continue;
                log.info("EXTDISPLAY[{}]:     supported mode id={} {}x{} @ {}fps",
                        where, m.getModeId(), m.getPhysicalWidth(),
                        m.getPhysicalHeight(), Math.round(m.getRefreshRate()));
            }
        }
        catch (Throwable ignore) { }
    }

    /**
     * @return the {@code modeId} of the highest-resolution supported mode on the
     *   given display (ties broken by refresh rate), or {@code 0} when it cannot
     *   be resolved or is already the current mode. Used to ask the OS to drive
     *   an external panel at its full resolution (e.g. 4K) rather than the
     *   1080p that desktop-mode / DeX often negotiates by default.
     */
    public static int getHighestModeId(Context ctx, int displayId)
    {
        try
        {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return 0;
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return 0;
            Display d = dm.getDisplay(displayId);
            if (d == null) return 0;
            Display.Mode cur = d.getMode();
            Display.Mode best = cur;
            long bestScore = (cur == null) ? -1
                    : (long) cur.getPhysicalWidth() * cur.getPhysicalHeight();
            float bestRate = (cur == null) ? 0 : cur.getRefreshRate();
            Display.Mode[] modes = d.getSupportedModes();
            if (modes == null) return 0;
            for (Display.Mode m : modes)
            {
                if (m == null) continue;
                long area = (long) m.getPhysicalWidth() * m.getPhysicalHeight();
                if (area > bestScore || (area == bestScore && m.getRefreshRate() > bestRate))
                {
                    best = m;
                    bestScore = area;
                    bestRate = m.getRefreshRate();
                }
            }
            if (best == null || (cur != null && best.getModeId() == cur.getModeId())) return 0;
            log.info("EXTDISPLAY: highest mode on id={} is {}x{} @ {}fps (modeId={})",
                    displayId, best.getPhysicalWidth(), best.getPhysicalHeight(),
                    Math.round(best.getRefreshRate()), best.getModeId());
            return best.getModeId();
        }
        catch (Throwable t)
        {
            log.warn("getHighestModeId failed for id={}", displayId, t);
            return 0;
        }
    }
}
