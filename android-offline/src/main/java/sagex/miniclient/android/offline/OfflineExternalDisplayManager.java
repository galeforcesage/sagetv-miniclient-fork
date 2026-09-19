package sagex.miniclient.android.offline;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.SurfaceHolder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sagex.miniclient.android.display.ExternalDisplayController;
import sagex.miniclient.android.display.ExternalVideoPresentation;

/**
 * Offline-player counterpart to the server client's Path B surface move
 * ({@code ExternalVideoSurfaceController}). When a genuine <b>extended</b>
 * external display (HDMI / DisplayPort / DeX) is attached while an offline
 * recording is playing, the video is re-targeted onto a full-bleed
 * {@link ExternalVideoPresentation} on that display and the audio is pointed at
 * the HDMI sink; the phone keeps the {@code StyledPlayerView} transport
 * controls and simply becomes the remote. On detach the video returns to the
 * phone.
 *
 * <p>Unlike the server client, the offline player owns its own ExoPlayer /
 * IjkMediaPlayer instance with no server connection and <b>no server-drawn
 * OSD</b>, so the move is a pure surface + audio-route swap: no activity
 * relaunch, no teardown, no reconnect, and playback position is preserved. All
 * player-specific re-targeting is delegated back to the hosting activity via
 * {@link Host}, keeping this class concerned only with display detection and
 * the presentation lifecycle.</p>
 *
 * <p>Fails closed on every gate: the mobile-only
 * {@code feature_external_display} resource, API&ge;17 (Presentation), and a
 * real extended display actually being present. Mirror-only phones never expose
 * an extended display, so the feature simply never triggers there.</p>
 */
public final class OfflineExternalDisplayManager implements DisplayManager.DisplayListener {

    private static final Logger log = LoggerFactory.getLogger(OfflineExternalDisplayManager.class);

    /** Callback into the owning activity to re-target its active player. */
    public interface Host {
        /** @return the hosting activity, or {@code null} once it is gone. */
        Activity getActivity();

        /**
         * Re-target the active player's video output to the external TV surface
         * and route audio to {@code audioSink} (may be {@code null} if none was
         * found). Called on the main thread.
         */
        void onExternalDisplayReady(SurfaceHolder holder, AudioDeviceInfo audioSink);

        /**
         * Re-target the active player's video output back to the phone surface
         * and restore the default audio route. Called on the main thread.
         */
        void onExternalDisplayLost();
    }

    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable evalTask = new Runnable() { @Override public void run() { evaluate(); } };

    private DisplayManager dm;
    private ExternalVideoPresentation presentation;
    /** True between a surface becoming ready and it being dismissed / lost. */
    private boolean presenting;
    /** True while intentionally dismissing, to swallow the surface-lost path. */
    private boolean dismissing;

    public OfflineExternalDisplayManager(Host host) {
        this.host = host;
    }

    /** Register the display listener and evaluate the current topology once. */
    public void start() {
        try {
            Activity a = host.getActivity();
            if (a == null) return;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) return; // Presentation is API 17+
            if (!isFeatureEnabled(a)) return;
            dm = (DisplayManager) a.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return;
            dm.registerDisplayListener(this, main);
            // Handle the cold-start case: HDMI already attached before playback.
            schedule();
            log.info("OfflineExternalDisplayManager started");
        } catch (Throwable t) {
            log.warn("OfflineExternalDisplayManager start failed (non-fatal)", t);
        }
    }

    /** Unregister and tear down the presentation. Safe to call multiple times. */
    public void stop() {
        try {
            main.removeCallbacks(evalTask);
            if (dm != null) {
                dm.unregisterDisplayListener(this);
                dm = null;
            }
        } catch (Throwable ignore) {
        }
        // The activity's players are being released by the caller; just drop the
        // presentation without asking the host to re-target a dead player.
        dismissPresentation();
        presenting = false;
    }

    private boolean isFeatureEnabled(Context ctx) {
        try {
            return ctx.getResources().getBoolean(sagex.miniclient.android.R.bool.feature_external_display);
        } catch (Throwable t) {
            return false;
        }
    }

    // ── DisplayListener ───────────────────────────────────────────────────
    @Override public void onDisplayAdded(int displayId)   { schedule(); }
    @Override public void onDisplayRemoved(int displayId) { schedule(); }
    @Override public void onDisplayChanged(int displayId) { schedule(); }

    private void schedule() {
        main.removeCallbacks(evalTask);
        main.postDelayed(evalTask, 500L); // debounce hotplug bursts
    }

    private void evaluate() {
        try {
            Activity a = host.getActivity();
            if (a == null) return;
            int extId = ExternalDisplayController.getExternalDisplayId(a);
            if (extId > 0 && !presenting) {
                moveToExternal(extId);
            } else if (extId < 0 && presenting) {
                moveToPhone("external display removed");
            }
        } catch (Throwable t) {
            log.warn("OfflineExternalDisplayManager.evaluate failed", t);
        }
    }

    private void moveToExternal(final int extId) {
        Activity a = host.getActivity();
        if (a == null) return;
        Display display = (dm != null) ? dm.getDisplay(extId) : null;
        if (display == null) {
            log.info("Offline: external display id={} no longer resolvable", extId);
            return;
        }
        try {
            dismissPresentation();
            final Activity host2 = a;
            ExternalVideoPresentation p = new ExternalVideoPresentation(a, display,
                    new ExternalVideoPresentation.Callback() {
                        @Override
                        public void onExternalSurfaceReady(SurfaceHolder holder) {
                            presenting = true;
                            host.onExternalDisplayReady(holder, findExternalAudioDevice(host2));
                        }

                        @Override
                        public void onExternalSurfaceLost(SurfaceHolder holder) {
                            // Ignore the teardown we triggered ourselves.
                            if (dismissing) return;
                            moveToPhone("external surface lost");
                        }
                    });
            presentation = p;
            p.show();
            log.info("Offline: presenting video on external display id={}", extId);
        } catch (Throwable t) {
            log.warn("Offline: moveToExternal failed", t);
            dismissPresentation();
        }
    }

    private void moveToPhone(String why) {
        log.info("Offline: returning video to phone — {}", why);
        // Re-target BEFORE dismissing so the decoder never renders into a dead
        // surface during the move.
        try {
            host.onExternalDisplayLost();
        } catch (Throwable t) {
            log.warn("Offline: onExternalDisplayLost failed", t);
        }
        dismissPresentation();
        presenting = false;
    }

    private void dismissPresentation() {
        if (presentation == null) return;
        dismissing = true;
        try {
            presentation.dismiss();
        } catch (Throwable ignore) {
        }
        presentation = null;
        dismissing = false;
    }

    // ── audio sink selection (mirrors ExternalVideoSurfaceController) ──────
    private AudioDeviceInfo findExternalAudioDevice(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT < 23) return null;
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return null;
            AudioDeviceInfo[] outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            if (outs == null) return null;
            AudioDeviceInfo best = null;
            int bestScore = 0;
            for (AudioDeviceInfo d : outs) {
                if (d == null || !d.isSink()) continue;
                int score = audioSinkScore(d.getType());
                if (score > bestScore) {
                    bestScore = score;
                    best = d;
                }
            }
            return best;
        } catch (Throwable t) {
            log.warn("Offline: findExternalAudioDevice failed", t);
            return null;
        }
    }

    private int audioSinkScore(int type) {
        if (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_HDMI_EARC) return 96;
        switch (type) {
            case AudioDeviceInfo.TYPE_HDMI:        return 100;
            case AudioDeviceInfo.TYPE_HDMI_ARC:    return 95;
            case AudioDeviceInfo.TYPE_DOCK:        return 60; // DeX / desktop dock
            case AudioDeviceInfo.TYPE_USB_DEVICE:  return 55;
            case AudioDeviceInfo.TYPE_USB_HEADSET: return 50;
            default:                               return 0;
        }
    }
}
