package sagex.miniclient.android.audio.eq;

import android.content.Context;
import android.util.Log;

/**
 * Process-wide holder for the audio EQ engine and settings.
 *
 * <p>The players (ExoPlayer / IJK) call {@link #onAudioSessionChanged(int)} when
 * their audio session id becomes known, which attaches the {@link AndroidEqEngine}
 * and applies the persisted settings so client-side EQ is live immediately — even
 * before the user opens the EQ panel.</p>
 *
 * <p>The EQ UI reads/writes settings through this singleton so slider changes take
 * effect on the currently-playing audio session and are persisted.</p>
 */
public final class EqManager
{
    private static final String TAG = "EqManager";

    private static final EqManager INSTANCE = new EqManager();

    public static EqManager get() { return INSTANCE; }

    private final AndroidEqEngine engine = new AndroidEqEngine();
    private EqSettingsStore store;
    private EqSettings settings;
    private int currentSessionId = 0;
    private int currentChannelCount = 2;
    private boolean currentIsPcm = true;
    private boolean streamActive = false;

    private EqManager() { }

    /** Initialize persistence. Safe to call multiple times. */
    public synchronized void init(Context ctx)
    {
        if (store == null && ctx != null)
        {
            store = new EqSettingsStore(ctx.getApplicationContext());
            settings = store.load();
        }
        publishServerPayload();
    }

    public AndroidEqEngine getEngine() { return engine; }

    public synchronized EqSettings getSettings()
    {
        if (settings == null)
        {
            settings = (store != null) ? store.load() : new EqSettings();
        }
        return settings;
    }

    // ── Stream capability (set by the player) ──────────────────────

    /** Output channel count of the currently-playing audio (1..8). */
    public synchronized int getCurrentChannelCount() { return currentChannelCount; }

    /** True while media is playing. Connection status is only meaningful then. */
    public synchronized boolean isStreamActive() { return streamActive; }

    /** True when the current stream is decoded PCM the client could EQ on-device. */
    public synchronized boolean isCurrentStreamPcm() { return currentIsPcm; }

    /**
     * True when on-device (client) EQ can actually be applied to the current
     * stream. False for passthrough/bitstream audio (e.g. Dolby/DTS sent to a
     * receiver on a Shield) — in that case the server must do all EQ.
     */
    public synchronized boolean canClientProcessCurrentStream()
    {
        return streamActive && currentIsPcm;
    }

    /**
     * The processing mode actually in force right now. For passthrough audio this
     * is forced to server regardless of the user's "Process on this device" pref.
     */
    public synchronized boolean isEffectiveClientProcessing()
    {
        return getSettings().isClientProcessing() && (!streamActive || currentIsPcm);
    }

    /**
     * Called by the player when its audio session becomes known. The channel count
     * must match the decoded PCM output; {@code isPcm=false} means the audio is
     * passthrough/bitstream and the client cannot EQ it (server must).
     */
    public synchronized void onAudioSessionChanged(int sessionId, int channelCount, boolean isPcm)
    {
        if (sessionId <= 0) return;
        currentSessionId = sessionId;
        currentChannelCount = (channelCount >= 1 && channelCount <= 8) ? channelCount : 2;
        currentIsPcm = isPcm;
        streamActive = true;
        Log.i(TAG, "Audio session " + sessionId + " ch=" + currentChannelCount
                + " pcm=" + isPcm);
        reconcile();
        publishServerPayload();
    }

    /** Back-compat overload assuming stereo PCM. */
    public synchronized void onAudioSessionChanged(int sessionId)
    {
        onAudioSessionChanged(sessionId, 2, true);
    }

    /** Called by the player on release/close. Releases the audio effects. */
    public synchronized void onPlayerReleased()
    {
        currentSessionId = 0;
        streamActive = false;
        currentIsPcm = true;
        currentChannelCount = 2;
        try { engine.release(); }
        catch (Throwable ignored) { }
        publishServerPayload();
    }

    /** Persist and apply settings (invoked from the EQ UI on every change). */
    public synchronized void updateSettings(EqSettings s)
    {
        if (s == null) return;
        this.settings = s;
        if (store != null) store.save(s);
        reconcile();
        publishServerPayload();
    }

    /**
     * Attach + apply only when client EQ can be applied to the current stream:
     * EQ enabled, user wants on-device processing, AND the stream is decoded PCM.
     * Passthrough audio is left untouched so the server can EQ it instead.
     */
    private void reconcile()
    {
        EqSettings s = getSettings();
        boolean want = s.isEnabled() && s.isClientProcessing() && currentIsPcm;
        try
        {
            if (want && currentSessionId > 0)
            {
                if (engine.attach(currentSessionId, currentChannelCount))
                {
                    engine.apply(s);
                    Log.i(TAG, "EQ attached + applied to session " + currentSessionId
                            + " (" + currentChannelCount + " ch)");
                }
            }
            else
            {
                if (engine.isAttached())
                {
                    engine.release();
                    Log.i(TAG, "EQ detached (disabled, server-side, or passthrough)");
                }
            }
        }
        catch (Throwable t)
        {
            Log.e(TAG, "reconcile failed: " + t.getMessage());
        }
    }

    /**
     * Publish the server-facing EQ payload. The {@code clientProcessing} flag is
     * the EFFECTIVE mode: forced to false for passthrough streams so the server
     * knows it must apply the EQ. Adds canonical stream hints for the server.
     */
    private void publishServerPayload()
    {
        try
        {
            EqSettings s = getSettings();
            org.json.JSONObject payload = s.toServerPayload();
            payload.put("clientProcessing", isEffectiveClientProcessing());
            payload.put("passthrough", streamActive && !currentIsPcm);
            payload.put("outputChannelCount", currentChannelCount);
            payload.put("clientCanProcess", canClientProcessCurrentStream());
            sagex.miniclient.MiniClientConnection.audioProcessingSettings = payload.toString();
        }
        catch (Throwable t)
        {
            Log.e(TAG, "publishServerPayload failed: " + t.getMessage());
        }
    }

    public synchronized boolean isAttached() { return engine.isAttached(); }
}
