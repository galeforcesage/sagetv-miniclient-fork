package sagex.miniclient.android.media;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches the device's audio/display OUTPUT sink and fires a callback whenever
 * its playback-relevant capabilities change &mdash; e.g. an HDMI monitor is
 * plugged into a phone, a TV/AVR attached to a Shield is powered on/off or
 * swapped, or the Android TV "surround sound" setting flips. Those events change
 * what the client should advertise in the Playback Surface descriptors
 * ({@code AUDIO_MAX_CHANNELS}, the HDMI-passthrough audio codec set, and the
 * display sink resolution), so the server can re-evaluate the current stream.
 *
 * <p>This class does NOT compute or hold the capabilities itself; it is a pure
 * change detector. It samples a compact "signature" of the current sink and
 * invokes {@link Listener#onSinkCapabilitiesChanged()} only when that signature
 * actually changes, after a short debounce (an HDMI hotplug emits a burst of
 * add/remove callbacks that must be coalesced into one refresh).</p>
 *
 * <p>Signals subscribed (all best-effort, all guarded):</p>
 * <ul>
 *   <li>{@link AudioDeviceCallback} (API 23+) &mdash; output device add/remove.</li>
 *   <li>{@link DisplayManager.DisplayListener} (API 17+) &mdash; display add/
 *       remove/change (sink resolution / HDR).</li>
 *   <li>{@link AudioManager#ACTION_HDMI_AUDIO_PLUG} broadcast (all API levels)
 *       &mdash; the most direct "HDMI connected/disconnected" signal, and the
 *       only audio-sink signal available below API 23.</li>
 * </ul>
 *
 * <p>Everything runs on the main looper; the listener callback is therefore
 * delivered on the main thread and must hand off any socket work to the
 * appropriate thread (the connection layer does this).</p>
 */
public class SinkCapabilityMonitor
{
    private static final Logger log = LoggerFactory.getLogger(SinkCapabilityMonitor.class);

    /** Coalesce the hotplug callback burst into a single refresh. */
    private static final long DEBOUNCE_MS = 900L;

    public interface Listener
    {
        /** Invoked (on the main thread) after the sink signature changed and settled. */
        void onSinkCapabilitiesChanged();
    }

    private final Context appContext;
    private final Listener listener;
    private final Handler handler;

    private AudioManager audioManager;
    private DisplayManager displayManager;

    private AudioDeviceCallback audioDeviceCallback; // API 23+
    private DisplayManager.DisplayListener displayListener;
    private BroadcastReceiver hdmiReceiver;

    private volatile boolean started;
    private String lastSignature;

    private final Runnable debouncedCheck = new Runnable()
    {
        @Override public void run() { evaluate(); }
    };

    public SinkCapabilityMonitor(Context context, Listener listener)
    {
        this.appContext = context != null ? context.getApplicationContext() : null;
        this.listener = listener;
        this.handler = new Handler(Looper.getMainLooper());
    }

    public synchronized void start()
    {
        if (started || appContext == null) return;
        started = true;
        try
        {
            audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            displayManager = (DisplayManager) appContext.getSystemService(Context.DISPLAY_SERVICE);

            // Baseline signature so the first real change is detected as a change.
            lastSignature = computeSignature();

            if (Build.VERSION.SDK_INT >= 23 && audioManager != null)
            {
                audioDeviceCallback = new AudioDeviceCallback()
                {
                    @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { schedule(); }
                    @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { schedule(); }
                };
                audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler);
            }

            if (displayManager != null)
            {
                displayListener = new DisplayManager.DisplayListener()
                {
                    @Override public void onDisplayAdded(int displayId) { schedule(); }
                    @Override public void onDisplayRemoved(int displayId) { schedule(); }
                    @Override public void onDisplayChanged(int displayId) { schedule(); }
                };
                displayManager.registerDisplayListener(displayListener, handler);
            }

            hdmiReceiver = new BroadcastReceiver()
            {
                @Override public void onReceive(Context c, Intent i) { schedule(); }
            };
            appContext.registerReceiver(hdmiReceiver,
                    new IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG));

            log.debug("SinkCapabilityMonitor started (baseline signature='{}')", lastSignature);
        }
        catch (Throwable t)
        {
            log.warn("SinkCapabilityMonitor failed to start (sink-change detection disabled)", t);
        }
    }

    public synchronized void stop()
    {
        if (!started) return;
        started = false;
        handler.removeCallbacks(debouncedCheck);
        try
        {
            if (audioDeviceCallback != null && audioManager != null)
                audioManager.unregisterAudioDeviceCallback(audioDeviceCallback);
        }
        catch (Throwable ignored) { }
        try
        {
            if (displayListener != null && displayManager != null)
                displayManager.unregisterDisplayListener(displayListener);
        }
        catch (Throwable ignored) { }
        try
        {
            if (hdmiReceiver != null)
                appContext.unregisterReceiver(hdmiReceiver);
        }
        catch (Throwable ignored) { }
        audioDeviceCallback = null;
        displayListener = null;
        hdmiReceiver = null;
        log.debug("SinkCapabilityMonitor stopped");
    }

    private void schedule()
    {
        handler.removeCallbacks(debouncedCheck);
        handler.postDelayed(debouncedCheck, DEBOUNCE_MS);
    }

    private void evaluate()
    {
        if (!started) return;
        String sig = computeSignature();
        if (sig == null || sig.equals(lastSignature)) return;
        log.info("Sink capabilities changed: '{}' -> '{}'", lastSignature, sig);
        lastSignature = sig;
        try
        {
            if (listener != null) listener.onSinkCapabilitiesChanged();
        }
        catch (Throwable t)
        {
            log.warn("SinkCapabilityMonitor listener threw", t);
        }
    }

    /**
     * A compact, deterministic fingerprint of the current OUTPUT sink. Only the
     * fields that alter what we advertise are included, so unrelated churn (a
     * Bluetooth input mic connecting, a brightness change) does not trigger a
     * needless re-negotiation.
     */
    private String computeSignature()
    {
        StringBuilder sb = new StringBuilder();
        try
        {
            sb.append("ch=").append(CodecCapabilityDetector.getMaxOutputAudioChannels(appContext));
        }
        catch (Throwable t) { sb.append("ch=?"); }

        if (Build.VERSION.SDK_INT >= 23 && audioManager != null)
        {
            try
            {
                java.util.TreeSet<String> devs = new java.util.TreeSet<>();
                for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                {
                    // type + the encodings it accepts (passthrough set can change
                    // when an AVR that bitstreams EAC3/DTS is connected).
                    StringBuilder enc = new StringBuilder();
                    int[] encodings = d.getEncodings();
                    if (encodings != null)
                    {
                        int[] sorted = encodings.clone();
                        java.util.Arrays.sort(sorted);
                        for (int e : sorted) enc.append(e).append('.');
                    }
                    devs.add(d.getType() + ":" + enc);
                }
                sb.append(";dev=").append(devs);
            }
            catch (Throwable t) { sb.append(";dev=?"); }
        }
        return sb.toString();
    }
}
