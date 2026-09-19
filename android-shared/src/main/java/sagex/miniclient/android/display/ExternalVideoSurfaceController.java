package sagex.miniclient.android.display;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sagex.miniclient.MiniClient;
import sagex.miniclient.MiniPlayerPlugin;
import sagex.miniclient.SageCommand;
import sagex.miniclient.android.MiniclientApplication;
import sagex.miniclient.android.R;
import sagex.miniclient.android.events.HideNavigationEvent;
import sagex.miniclient.android.video.BaseMediaPlayerImpl;
import sagex.miniclient.android.video.OrientationController;
import sagex.miniclient.prefs.PrefStore;
import sagex.miniclient.uibridge.EventRouter;

/**
 * Path B (surface move) — mid-session external-display output <b>without any
 * session teardown</b>.
 *
 * <p>When a genuine extended external display (HDMI / DeX / DisplayPort) appears
 * while a session is running, this controller shows an
 * {@link ExternalVideoPresentation} on it and re-targets the running player's
 * output surface to that presentation via
 * {@link BaseMediaPlayerImpl#reattachVideoSurface(SurfaceHolder)}. The SageTV UI
 * activity, its {@link sagex.miniclient.MiniClientConnection} and the native
 * player all stay on the phone, so:
 * <ul>
 *   <li>the video decode / demux / network session is never torn down &mdash;
 *       playback position and all seek state are preserved (no reconnect, no
 *       {@code closeConnection()}, none of the activity-relaunch race);</li>
 *   <li>the SageTV OSD / menus / trick-play bar stay on the phone (B1: clean
 *       video on the TV, controls on the phone);</li>
 *   <li>while presenting, the external panel's resolution is advertised as
 *       {@code DISPLAY_SINK_RESOLUTION} (via
 *       {@link ExternalDisplayController#setSinkResolutionOverrideDisplayId(int)})
 *       and the server is nudged to re-negotiate so it upscales to the TV.</li>
 * </ul>
 *
 * <p>App-scoped singleton, all best-effort, fails closed on every gate: mobile
 * {@code feature_external_display} resource, the user's
 * {@link PrefStore.Keys#auto_move_to_tv} checkbox (default OFF — opt-in), API&ge;17
 * (Presentation), an active connection, and a hot-swappable player. Mirror-only
 * phones never expose an extended display, so the feature simply never triggers
 * there.</p>
 */
public final class ExternalVideoSurfaceController implements DisplayManager.DisplayListener
{
    private static final Logger log = LoggerFactory.getLogger(ExternalVideoSurfaceController.class);

    private static ExternalVideoSurfaceController INSTANCE;

    private final Application app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable evalTask = new Runnable() { @Override public void run() { evaluate(); } };

    /** The presentation currently shown on the external display, or null. */
    private ExternalVideoPresentation presentation;
    /** True between show() and dismiss()/loss. */
    private boolean presenting = false;
    /** Display id we are presenting on (>0 while presenting). */
    private int presentDisplayId = -1;
    /** True while we are intentionally dismissing (suppresses the surface-lost detach path). */
    private boolean intentionalDismiss = false;

    private ExternalVideoSurfaceController(Application app) { this.app = app; }

    public static synchronized ExternalVideoSurfaceController get() { return INSTANCE; }

    /** Registers the singleton and its display listener. Safe to call once at app init. */
    public static synchronized void install(Application app)
    {
        if (INSTANCE != null || app == null) return;
        try
        {
            ExternalVideoSurfaceController c = new ExternalVideoSurfaceController(app);
            DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return;
            dm.registerDisplayListener(c, c.main);
            INSTANCE = c;
            log.info("ExternalVideoSurfaceController installed");
        }
        catch (Throwable t)
        {
            log.warn("ExternalVideoSurfaceController install failed (non-fatal)", t);
        }
    }

    /**
     * @return the ready external presentation surface holder when video should be
     *   rendered on the external display, or {@code null}. Consulted by the
     *   player impls at setup so a playback <em>started</em> while presenting
     *   binds directly to the TV instead of the phone.
     */
    public static SurfaceHolder getActiveExternalHolderIfReady()
    {
        try
        {
            ExternalVideoSurfaceController c = INSTANCE;
            if (c == null || !c.presenting || c.presentation == null) return null;
            SurfaceHolder h = c.presentation.getSurfaceHolder();
            return (h != null && h.getSurface() != null && h.getSurface().isValid()) ? h : null;
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    /**
     * Re-apply audio + caption routing to the <em>currently active</em> player
     * if a Path B external presentation is up.
     *
     * <p>Needed because {@link #onExternalReady} routes audio/captions once, when
     * the TV surface first becomes ready. A playback <em>started later</em> while
     * already presenting binds straight to the TV surface via
     * {@link #getActiveExternalHolderIfReady()} but is a brand-new player that
     * missed that routing pass — so without this its audio would stay on the
     * phone. Players call this once they are fully constructed and ready (Exo
     * {@code STATE_READY}).</p>
     */
    public static void reapplyExternalRoutingIfPresenting()
    {
        try
        {
            final ExternalVideoSurfaceController c = INSTANCE;
            if (c == null || !c.presenting) return;
            c.main.post(new Runnable()
            {
                @Override public void run()
                {
                    if (!c.presenting) return;
                    c.routeAudioToExternal();
                    c.routeCaptionsToExternal();
                }
            });
        }
        catch (Throwable ignore)
        {
        }
    }

    // ── DisplayListener ───────────────────────────────────────────────────
    @Override public void onDisplayAdded(int displayId)   { schedule(); }
    @Override public void onDisplayRemoved(int displayId) { schedule(); }
    @Override public void onDisplayChanged(int displayId) { schedule(); }

    private void schedule()
    {
        main.removeCallbacks(evalTask);
        main.postDelayed(evalTask, 700L); // debounce hotplug bursts
    }

    /** Re-evaluate when the video UI resumes (display may have changed while backgrounded). */
    public void onUiResumed(Activity uiActivity)
    {
        schedule();
    }

    /**
     * Called when the session is closing (activity pausing/destroying with the
     * connection). Moves output back to the phone and tears down the
     * presentation so nothing leaks and the sink override is cleared.
     */
    public void onSessionClosing()
    {
        try
        {
            if (!presenting) return;
            ExternalDisplayController.setSinkResolutionOverrideDisplayId(-1);
            dismissPresentation();
            presenting = false;
            presentDisplayId = -1;
        }
        catch (Throwable t)
        {
            log.warn("onSessionClosing cleanup failed", t);
        }
    }

    // ── core decision ─────────────────────────────────────────────────────
    private void evaluate()
    {
        try
        {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) return; // Presentation is API 17+
            if (!app.getResources().getBoolean(R.bool.feature_external_display)) return;

            MiniClient client = MiniclientApplication.get().getClient();
            if (client == null || client.properties() == null) return;
            // Master opt-in switch (General settings → "Automatically move to TV").
            if (!client.properties().getBoolean(PrefStore.Keys.auto_move_to_tv, false)) return;
            // Only while a session is actually up.
            if (client.getCurrentConnection() == null) return;

            int extId = ExternalDisplayController.getExternalDisplayId(app);

            if (extId > 0 && (!presenting || presentDisplayId != extId))
            {
                moveToExternal(extId, "extended display present (id=" + extId + ")");
            }
            else if (extId < 0 && presenting)
            {
                moveBackToPhone("extended display removed");
            }
        }
        catch (Throwable t)
        {
            log.warn("ExternalVideoSurfaceController.evaluate failed", t);
        }
    }

    private void moveToExternal(final int extId, String why)
    {
        try
        {
            // If we were presenting on a different display, tear that down first.
            if (presenting && presentDisplayId != extId)
            {
                dismissPresentation();
                presenting = false;
                presentDisplayId = -1;
            }
            if (presenting) return;

            Activity host = MiniclientApplication.get().getCurrentActivity();
            if (host == null)
            {
                log.info("moveToExternal deferred: no current activity yet");
                return;
            }
            DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
            Display display = (dm != null) ? dm.getDisplay(extId) : null;
            if (display == null)
            {
                log.info("moveToExternal aborted: display id={} not resolvable", extId);
                return;
            }

            log.info("Moving video to external display — {}", why);
            intentionalDismiss = false;

            ExternalVideoPresentation p = new ExternalVideoPresentation(host, display,
                    new ExternalVideoPresentation.Callback()
                    {
                        @Override
                        public void onExternalSurfaceReady(SurfaceHolder holder)
                        {
                            onExternalReady(extId, holder);
                        }

                        @Override
                        public void onExternalSurfaceLost(SurfaceHolder holder)
                        {
                            onExternalLost();
                        }
                    });
            p.show();
            presentation = p;
            presenting = true;
            presentDisplayId = extId;
        }
        catch (Throwable t)
        {
            log.warn("moveToExternal failed", t);
            presenting = false;
            presentDisplayId = -1;
            presentation = null;
        }
    }

    private void onExternalReady(int extId, SurfaceHolder holder)
    {
        try
        {
            // Re-target the running player onto the TV surface (no teardown).
            reattachPlayer(holder);
            // Advertise the TV's resolution and prompt the server to upscale.
            ExternalDisplayController.setSinkResolutionOverrideDisplayId(extId);
            nudgeServer();
            // Audio follows the video onto the TV (best-effort; Exo only).
            routeAudioToExternal();
            // Closed captions follow the video onto the TV overlay (Exo only).
            routeCaptionsToExternal();
            // While casting, keep the phone locked to landscape regardless of the
            // user's orientation mode or a fold — the TV presentation is separate.
            lockPhoneLandscape();
            // Phone would otherwise be black during playback — raise the on-phone
            // control OSD (Play/Pause/Stop/FF/REW/Skip/Ch±) so controls are visible.
            showPhoneOsd();
            log.info("Video now presenting on external display id={}", extId);
        }
        catch (Throwable t)
        {
            log.warn("onExternalReady failed", t);
        }
    }

    private void onExternalLost()
    {
        // Only handle an *unexpected* loss (display yanked). An intentional
        // dismiss already moved output back to the phone.
        if (intentionalDismiss) return;
        try
        {
            log.info("External surface lost unexpectedly — returning video to phone");
            reattachPlayer(getPhoneHolder());
            ExternalDisplayController.setSinkResolutionOverrideDisplayId(-1);
            nudgeServer();
            routeAudioToPhone();
            routeCaptionsToPhone();
            hidePhoneOsd();
            restorePhoneOrientation();
        }
        catch (Throwable t)
        {
            log.warn("onExternalLost handling failed", t);
        }
        finally
        {
            presenting = false;
            presentDisplayId = -1;
            presentation = null;
        }
    }

    private void moveBackToPhone(String why)
    {
        try
        {
            if (!presenting) return;
            log.info("Moving video back to phone — {}", why);
            // Re-target BEFORE dismissing so the decoder never hits a dead surface.
            reattachPlayer(getPhoneHolder());
            ExternalDisplayController.setSinkResolutionOverrideDisplayId(-1);
            nudgeServer();
            routeAudioToPhone();
            routeCaptionsToPhone();
            hidePhoneOsd();
            restorePhoneOrientation();
            dismissPresentation();
        }
        catch (Throwable t)
        {
            log.warn("moveBackToPhone failed", t);
        }
        finally
        {
            presenting = false;
            presentDisplayId = -1;
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────
    private void reattachPlayer(SurfaceHolder holder)
    {
        if (holder == null) return;
        try
        {
            BaseMediaPlayerImpl playa = getActivePlayer();
            if (playa != null)
            {
                playa.reattachVideoSurface(holder);
            }
        }
        catch (Throwable t)
        {
            log.warn("reattachPlayer failed", t);
        }
    }

    private BaseMediaPlayerImpl getActivePlayer()
    {
        try
        {
            MiniClient client = MiniclientApplication.get().getClient();
            if (client == null || client.getCurrentConnection() == null
                    || client.getCurrentConnection().getMediaCmd() == null) return null;
            MiniPlayerPlugin playa = client.getCurrentConnection().getMediaCmd().getPlaya();
            return (playa instanceof BaseMediaPlayerImpl) ? (BaseMediaPlayerImpl) playa : null;
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    // ── audio follow ──────────────────────────────────────────────────────
    private void routeAudioToExternal()
    {
        BaseMediaPlayerImpl playa = getActivePlayer();
        if (playa == null) return;
        AudioDeviceInfo dev = findExternalAudioDevice();
        if (dev != null)
        {
            log.info("Routing audio to external sink type={}", dev.getType());
            playa.setPreferredAudioOutput(dev);
        }
        else
        {
            log.info("No external audio sink found — audio left on the default route");
        }
    }

    private void routeAudioToPhone()
    {
        BaseMediaPlayerImpl playa = getActivePlayer();
        if (playa != null) playa.setPreferredAudioOutput(null);
    }

    // ── caption follow ────────────────────────────────────────────────────
    /**
     * Move <em>player-rendered</em> captions onto the TV overlay. This only
     * applies to the ExoPlayer path (file / recording playback), where CC is a
     * decoded subtitle track drawn into a client-side {@code SubtitleView};
     * {@link BaseMediaPlayerImpl#attachExternalSubtitleContainer} redirects that
     * view into the presentation's overlay.
     *
     * <p><b>Live-TV / IJK caveat:</b> for the live-TV MPEG-PS push path (which
     * runs on IJK, not Exo — see {@code PlayerSelectionUtil.isDeliveryProgramStream})
     * this is a no-op, because those captions are <em>not</em> player-rendered.
     * SageTV draws live-TV CC on the <em>server</em> as ordinary GFX commands
     * ({@code GFXCMD2} DRAWTEXT / textures) into the single GL OSD surface that
     * is {@code setZOrderOnTop(true)} over the video. Path B moves only the video
     * surface, so that whole server OSD layer — menus, trickplay bar, and
     * live-TV CC alike — intentionally stays on the phone. There is no separable
     * "CC channel" to peel off onto the TV; bringing it across would require
     * mirroring the entire GL OSD to the external display (a future OSD-mirror
     * mode), not this per-player subtitle hook.</p>
     */
    private void routeCaptionsToExternal()
    {
        BaseMediaPlayerImpl playa = getActivePlayer();
        if (playa == null || presentation == null) return;
        android.widget.FrameLayout container = presentation.getSubtitleContainer();
        if (container != null) playa.attachExternalSubtitleContainer(container);
    }

    private void routeCaptionsToPhone()
    {
        BaseMediaPlayerImpl playa = getActivePlayer();
        if (playa != null) playa.attachExternalSubtitleContainer(null);
    }

    /**
     * Pick the most TV-like output sink: HDMI / HDMI-ARC / HDMI-eARC first, then
     * a DeX dock, then USB. Returns {@code null} when nothing external is
     * present so audio stays on the phone.
     */
    private AudioDeviceInfo findExternalAudioDevice()
    {
        try
        {
            if (Build.VERSION.SDK_INT < 23) return null;
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return null;
            AudioDeviceInfo[] outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            if (outs == null) return null;
            AudioDeviceInfo best = null;
            int bestScore = 0;
            for (AudioDeviceInfo d : outs)
            {
                if (d == null || !d.isSink()) continue;
                int score = audioSinkScore(d.getType());
                if (score > bestScore)
                {
                    bestScore = score;
                    best = d;
                }
            }
            return best;
        }
        catch (Throwable t)
        {
            log.warn("findExternalAudioDevice failed", t);
            return null;
        }
    }

    private int audioSinkScore(int type)
    {
        if (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_HDMI_EARC) return 96;
        switch (type)
        {
            case AudioDeviceInfo.TYPE_HDMI:       return 100;
            case AudioDeviceInfo.TYPE_HDMI_ARC:   return 95;
            case AudioDeviceInfo.TYPE_DOCK:       return 60; // DeX / desktop dock
            case AudioDeviceInfo.TYPE_USB_DEVICE: return 55;
            case AudioDeviceInfo.TYPE_USB_HEADSET:return 50;
            default:                              return 0;
        }
    }

    // ── on-phone control OSD ──────────────────────────────────────────────
    private void showPhoneOsd()
    {
        try
        {
            MiniClient client = MiniclientApplication.get().getClient();
            if (client == null || client.getCurrentConnection() == null) return;
            // NAV_OSD -> ShowNavigationEvent -> the on-phone control panel
            // (Play/Pause/Stop/FF/REW/Skip/Ch±), same overlay a swipe raises.
            EventRouter.postCommand(client, SageCommand.NAV_OSD);
        }
        catch (Throwable t)
        {
            log.warn("showPhoneOsd failed", t);
        }
    }

    private void hidePhoneOsd()
    {
        try
        {
            MiniClient client = MiniclientApplication.get().getClient();
            if (client != null && client.eventbus() != null)
            {
                client.eventbus().post(HideNavigationEvent.INSTANCE);
            }
        }
        catch (Throwable ignore)
        {
        }
    }

    // ── phone orientation while casting ───────────────────────────────────
    private void lockPhoneLandscape()
    {
        try
        {
            final Activity host = MiniclientApplication.get().getCurrentActivity();
            if (host == null) return;
            host.runOnUiThread(new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        host.setRequestedOrientation(
                                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                    }
                    catch (Throwable t)
                    {
                        log.warn("lockPhoneLandscape failed", t);
                    }
                }
            });
        }
        catch (Throwable t)
        {
            log.warn("lockPhoneLandscape failed", t);
        }
    }

    private void restorePhoneOrientation()
    {
        try
        {
            final Activity host = MiniclientApplication.get().getCurrentActivity();
            if (host == null) return;
            host.runOnUiThread(new Runnable()
            {
                @Override
                public void run()
                {
                    // Re-apply the user's chosen orientation mode (default landscape).
                    OrientationController.apply(host);
                }
            });
        }
        catch (Throwable t)
        {
            log.warn("restorePhoneOrientation failed", t);
        }
    }

    private SurfaceHolder getPhoneHolder()
    {
        try
        {
            Activity host = MiniclientApplication.get().getCurrentActivity();
            if (host == null) return null;
            View v = host.findViewById(R.id.video_surface);
            return (v instanceof SurfaceView) ? ((SurfaceView) v).getHolder() : null;
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    private void nudgeServer()
    {
        try
        {
            MiniClient client = MiniclientApplication.get().getClient();
            if (client != null && client.getCurrentConnection() != null)
            {
                // Same low-impact primitive the sink monitor uses: the server
                // re-queries DISPLAY_SINK_RESOLUTION (now the external panel) and
                // re-decides enhancement. No-op / harmless against a legacy server.
                client.getCurrentConnection().onSinkCapabilitiesChanged();
            }
        }
        catch (Throwable t)
        {
            log.warn("nudgeServer failed", t);
        }
    }

    private void dismissPresentation()
    {
        try
        {
            intentionalDismiss = true;
            if (presentation != null)
            {
                presentation.dismiss();
            }
        }
        catch (Throwable ignore)
        {
        }
        finally
        {
            presentation = null;
        }
    }
}
