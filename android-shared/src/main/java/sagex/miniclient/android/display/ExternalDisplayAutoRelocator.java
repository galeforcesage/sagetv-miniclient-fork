package sagex.miniclient.android.display;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sagex.miniclient.MiniClient;
import sagex.miniclient.SageCommand;
import sagex.miniclient.ServerInfo;
import sagex.miniclient.android.MiniclientApplication;
import sagex.miniclient.android.R;
import sagex.miniclient.android.util.ServerInfoUtil;
import sagex.miniclient.prefs.PrefStore;
import sagex.miniclient.uibridge.EventRouter;

/**
 * Phase 1 external-display output, mid-session half: watches for a genuine
 * <b>extended</b> external display appearing or disappearing <em>while a session
 * is already running</em>, and relocates the SageTV UI to follow it — the same
 * relocation {@link ServerInfoUtil#connect(Context, ServerInfo, boolean)}
 * performs at connect time, just triggered live off a
 * {@link DisplayManager.DisplayListener}.
 *
 * <p>Attach (a DeX / DisplayPort monitor is plugged into the phone during
 * playback): relocate the UI onto the monitor at its highest mode (see
 * {@link sagex.miniclient.android.UIActivityLifeCycleHandler}); the phone becomes
 * the {@link sagex.miniclient.android.RemoteControlActivity}. Because the video
 * activity now renders on the monitor, {@code getDisplaySinkResolution()} reports
 * the monitor's real size and the connection nudges the server to re-negotiate
 * (upscale). Detach: bring the UI back to the phone.</p>
 *
 * <p>To keep the decoder from running into a torn-down surface across the move
 * (notably for live TV), a best-effort {@code PAUSE} is sent before the relocate
 * and a {@code PLAY} once the new surface is up — gated by
 * {@link PrefStore.Keys#pause_during_display_move}.</p>
 *
 * <p>App-scoped singleton, all best-effort. Fails closed on every gate: mobile
 * {@code feature_external_display} resource, {@link PrefStore.Keys#play_on_external_display},
 * {@link PrefStore.Keys#auto_switch_display_on_hdmi}, API&ge;26, and an active
 * connection actually being present. Mirror-only phones never expose an extended
 * display, so the whole feature simply never triggers there.</p>
 */
public final class ExternalDisplayAutoRelocator implements DisplayManager.DisplayListener
{
    private static final Logger log = LoggerFactory.getLogger(ExternalDisplayAutoRelocator.class);

    private static ExternalDisplayAutoRelocator INSTANCE;

    private final Application app;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Display id the video UI activity was last observed on: -1 unknown, 0 phone, >0 external. */
    private int currentUiDisplayId = -1;
    /** True while a relocate is in flight, to swallow the display-callback storm. */
    private boolean relocating = false;
    /** True when a PLAY should be issued once the relocated UI settles. */
    private boolean pendingResume = false;
    /** Coalesce bursts of add/change/remove callbacks. */
    private final Runnable evalTask = new Runnable() { @Override public void run() { evaluate(); } };

    private ExternalDisplayAutoRelocator(Application app) { this.app = app; }

    public static synchronized ExternalDisplayAutoRelocator get() { return INSTANCE; }

    /** Registers the singleton and its display listener. Safe to call once at app init. */
    public static synchronized void install(Application app)
    {
        if (INSTANCE != null || app == null) return;
        try
        {
            ExternalDisplayAutoRelocator r = new ExternalDisplayAutoRelocator(app);
            DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return;
            dm.registerDisplayListener(r, r.main);
            INSTANCE = r;
            log.info("ExternalDisplayAutoRelocator installed");
        }
        catch (Throwable t)
        {
            log.warn("ExternalDisplayAutoRelocator install failed (non-fatal)", t);
        }
    }

    // ── DisplayListener ───────────────────────────────────────────────────
    @Override public void onDisplayAdded(int displayId)   { schedule(); }
    @Override public void onDisplayRemoved(int displayId) { schedule(); }
    @Override public void onDisplayChanged(int displayId) { schedule(); }

    private void schedule()
    {
        main.removeCallbacks(evalTask);
        main.postDelayed(evalTask, 700L);   // debounce hotplug bursts
    }

    /**
     * Called by {@link sagex.miniclient.android.UIActivityLifeCycleHandler#onResume}
     * for the video UI activity. Records where the UI actually landed and, if a
     * relocation just completed, resumes playback.
     */
    public void onUiResumed(Activity uiActivity)
    {
        try
        {
            if (uiActivity == null) return;
            Display d = uiActivity.getWindowManager().getDefaultDisplay();
            int id = (d != null) ? d.getDisplayId() : -1;
            currentUiDisplayId = id;
            relocating = false;

            if (pendingResume)
            {
                pendingResume = false;
                // Let the new surface + mode switch settle, then resume.
                main.postDelayed(new Runnable()
                {
                    @Override public void run() { postCommand(SageCommand.PLAY, "resume-after-move"); }
                }, 1500L);
            }
            // A display may have changed while we were mid-relaunch; re-check.
            schedule();
        }
        catch (Throwable t)
        {
            log.warn("onUiResumed failed", t);
        }
    }

    // ── core decision ─────────────────────────────────────────────────────
    private void evaluate()
    {
        try
        {
            if (relocating) return;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return; // setLaunchDisplayId is API 26+
            if (!app.getResources().getBoolean(R.bool.feature_external_display)) return;

            MiniClient client = MiniclientApplication.get().getClient();
            if (client == null || client.properties() == null) return;
            if (!client.properties().getBoolean(PrefStore.Keys.play_on_external_display, true)) return;
            // Default OFF: mid-session activity relaunch onto a freshly-appeared
            // external display can wedge the app when the display is not actually
            // ready to host an activity (e.g. a Samsung DeX-hosting display that
            // reports present() but whose desktop mode is "ineligible"). Opt-in
            // only until a non-relaunch move mechanism lands.
            if (!client.properties().getBoolean(PrefStore.Keys.auto_switch_display_on_hdmi, false)) return;

            // Only while a session is actually up.
            if (client.getCurrentConnection() == null) return;
            // Wait until we've observed where the UI currently lives.
            if (currentUiDisplayId < 0) return;

            int extId = ExternalDisplayController.getExternalDisplayId(app);

            if (extId > 0 && currentUiDisplayId != extId)
            {
                relocate(true, "extended display attached (id=" + extId + ")");
            }
            else if (extId < 0 && currentUiDisplayId > 0)
            {
                relocate(false, "extended display removed");
            }
        }
        catch (Throwable t)
        {
            log.warn("ExternalDisplayAutoRelocator.evaluate failed", t);
        }
    }

    private void relocate(boolean toExternal, String why)
    {
        try
        {
            MiniClient client = MiniclientApplication.get().getClient();
            if (client == null || client.getServers() == null) return;
            ServerInfo si = client.getServers().getLastConnectedServer();
            if (si == null) return;

            Activity host = MiniclientApplication.get().getCurrentActivity();
            Context ctx = (host != null) ? host : app.getApplicationContext();

            log.info("Auto-relocating UI {} — {}", toExternal ? "to external display" : "back to phone", why);

            relocating = true;

            // Pause the server first so the decoder does not run into a
            // torn-down surface during the relaunch (best-effort, opt-out).
            boolean paused = false;
            if (client.properties().getBoolean(PrefStore.Keys.pause_during_display_move, true))
            {
                paused = postCommand(SageCommand.PAUSE, "pause-before-move");
            }
            pendingResume = paused;

            // Relaunch the UI on the target display; the app-scoped connection
            // persists, so the session continues on the new surface.
            ServerInfoUtil.connect(ctx, si, toExternal);
        }
        catch (Throwable t)
        {
            relocating = false;
            pendingResume = false;
            log.warn("relocate({}) failed", toExternal, t);
        }
    }

    private boolean postCommand(SageCommand cmd, String tag)
    {
        try
        {
            MiniClient client = MiniclientApplication.get().getClient();
            if (client == null || client.getCurrentConnection() == null) return false;
            EventRouter.postCommand(client, cmd);
            log.info("Sent {} to server ({})", cmd, tag);
            return true;
        }
        catch (Throwable t)
        {
            log.warn("postCommand {} ({}) failed", cmd, tag, t);
            return false;
        }
    }
}
