package sagex.miniclient.android.middleware;

import android.os.Handler;
import android.os.Looper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Java-side trickplay orchestrator backed by native transport.
 * <p>
 * Owns the native transport handle. Provides seek coalescing via Handler,
 * delegates time truth and buffering to JNI, and bridges player position
 * observations back to native.
 * <p>
 * Thread safety: all public methods are safe to call from any thread.
 * Seek coalescing callbacks run on the UI thread.
 */
public class TrickplayController
{
    private static final Logger log = LoggerFactory.getLogger(TrickplayController.class);
    private static final long SEEK_COALESCE_WINDOW_MS = 200;
    private static final int DEFAULT_BUFFER_CAPACITY = 4 * 1024 * 1024;

    private long nativeHandle;
    private final Handler uiHandler;
    private PlayerSeekCallback seekCallback;
    private EpochChangeListener epochListener;
    private int lastKnownEpoch;
    private boolean pushMode;

    /**
     * Callback for the player to actually execute a seek.
     */
    public interface PlayerSeekCallback
    {
        void onSeekTo(long timeMs);
    }

    /**
     * Listener for stream epoch changes (discontinuities).
     */
    public interface EpochChangeListener
    {
        void onEpochChanged(int newEpoch);
    }

    private final Runnable seekCommitRunnable = new Runnable()
    {
        @Override
        public void run()
        {
            if (nativeHandle == 0) return;

            long target = NativeTransport.nCommitSeek(nativeHandle);
            log.debug("Seek coalesce committed: target={}", target);

            if (seekCallback != null && target >= 0)
            {
                seekCallback.onSeekTo(target);
            }
        }
    };

    public TrickplayController()
    {
        uiHandler = new Handler(Looper.getMainLooper());

        if (NativeTransport.isAvailable())
        {
            nativeHandle = NativeTransport.nCreate(DEFAULT_BUFFER_CAPACITY);
        }
        else
        {
            log.warn("Native transport not available, trickplay disabled");
            nativeHandle = 0;
        }
    }

    public void setSeekCallback(PlayerSeekCallback callback)
    {
        this.seekCallback = callback;
    }

    public void setEpochListener(EpochChangeListener listener)
    {
        this.epochListener = listener;
    }

    public boolean isNativeAvailable()
    {
        return nativeHandle != 0;
    }

    /* ---- Lifecycle ---- */

    public void open(boolean pushMode)
    {
        this.pushMode = pushMode;
        if (nativeHandle != 0)
        {
            NativeTransport.nOpen(nativeHandle, pushMode);
            lastKnownEpoch = 0;
        }
    }

    public void close()
    {
        uiHandler.removeCallbacks(seekCommitRunnable);
        if (nativeHandle != 0)
        {
            NativeTransport.nClose(nativeHandle);
        }
    }

    public void destroy()
    {
        uiHandler.removeCallbacks(seekCommitRunnable);
        if (nativeHandle != 0)
        {
            NativeTransport.nDestroy(nativeHandle);
            nativeHandle = 0;
        }
    }

    /* ---- Data flow ---- */

    public int pushData(byte[] data, int offset, int length) throws IOException
    {
        if (nativeHandle == 0) return 0;
        int written = NativeTransport.nPushData(nativeHandle, data, offset, length);
        checkEpoch();
        return written;
    }

    public int readData(byte[] buffer, int offset, int length)
    {
        if (nativeHandle == 0) return 0;
        return NativeTransport.nReadData(nativeHandle, buffer, offset, length);
    }

    public int bufferAvailable()
    {
        if (nativeHandle == 0) return DEFAULT_BUFFER_CAPACITY;
        return NativeTransport.nBufferAvailable(nativeHandle);
    }

    public void flush()
    {
        if (nativeHandle == 0) return;
        uiHandler.removeCallbacks(seekCommitRunnable);
        NativeTransport.nFlush(nativeHandle);
    }

    public void setEOS()
    {
        if (nativeHandle == 0) return;
        NativeTransport.nSetEOS(nativeHandle);
    }

    public boolean isEOS()
    {
        if (nativeHandle == 0) return false;
        return NativeTransport.nIsEOS(nativeHandle);
    }

    /* ---- Trickplay ---- */

    /**
     * Begin a seek. Freezes reported time and starts coalesce timer.
     * If another seek arrives within the coalesce window, the target
     * is updated without committing to the player.
     */
    public void beginSeek(long targetMs)
    {
        if (nativeHandle == 0) return;
        NativeTransport.nBeginSeek(nativeHandle, targetMs);

        uiHandler.removeCallbacks(seekCommitRunnable);

        if (pushMode)
        {
            /* In push mode, the server drives repositioning via flush+push.
             * The player just needs to reset its pipeline.
             * Commit immediately — no coalescing needed for push seeks
             * because the server already serializes flush→push sequences. */
            uiHandler.post(seekCommitRunnable);
        }
        else
        {
            /* In pull mode, coalesce rapid seeks (FF mashing) */
            uiHandler.postDelayed(seekCommitRunnable, SEEK_COALESCE_WINDOW_MS);
        }
    }

    /**
     * Called by the player when its seek operation completes.
     * Transitions native state from SEEK_COMMITTING to RECOVERING.
     */
    public void notifySeekComplete()
    {
        if (nativeHandle == 0) return;
        NativeTransport.nNotifySeekComplete(nativeHandle);
    }

    /**
     * Called periodically by the player to report its observed position.
     * In RECOVERING state, stable position → transitions to STABLE_PLAYING.
     */
    public void onPlayerPosition(long positionMs)
    {
        if (nativeHandle == 0) return;
        NativeTransport.nOnPlayerPosition(nativeHandle, positionMs);
    }

    /**
     * Returns the time truth for SageTV's GETMEDIATIME polling.
     * Frozen during seeks, real during stable playback.
     */
    public long getReportedTime()
    {
        if (nativeHandle == 0) return 0;
        return NativeTransport.nGetReportedTime(nativeHandle);
    }

    public TrickplayState getState()
    {
        if (nativeHandle == 0) return TrickplayState.STABLE_PAUSED;
        return TrickplayState.fromNative(NativeTransport.nGetState(nativeHandle));
    }

    /**
     * Convenience: returns true when the trickplay state machine considers
     * playback "settled" (STABLE_PLAYING or STABLE_PAUSED) — i.e., not in
     * the middle of a seek/recovery transition. Callers that previously
     * tracked their own {@code flushed} or {@code seekPending} booleans
     * should prefer this query because it reflects the single source of
     * truth in the native two-clock model.
     *
     * <p>If the native layer is unavailable, returns true (no state machine
     * to query, so callers should fall back to their own logic).
     */
    public boolean isStable()
    {
        if (nativeHandle == 0) return true;
        TrickplayState s = getState();
        return s != null && s.isStable();
    }

    public void setPaused(boolean paused)
    {
        if (nativeHandle == 0) return;
        NativeTransport.nSetPaused(nativeHandle, paused);
    }

    public void setPlaying()
    {
        if (nativeHandle == 0) return;
        NativeTransport.nSetPlaying(nativeHandle);
    }

    /* ---- Server time ---- */

    public void setServerStartTime(long timeMs)
    {
        if (nativeHandle == 0) return;
        NativeTransport.nSetServerStartTime(nativeHandle, timeMs);
    }

    public long getServerStartTime()
    {
        if (nativeHandle == 0) return -1;
        return NativeTransport.nGetServerStartTime(nativeHandle);
    }

    /* ---- Epoch ---- */

    public int getEpoch()
    {
        if (nativeHandle == 0) return 0;
        return NativeTransport.nGetEpoch(nativeHandle);
    }

    /**
     * Check if epoch has changed and notify listener.
     */
    private void checkEpoch()
    {
        if (epochListener == null) return;
        int currentEpoch = getEpoch();
        if (currentEpoch != lastKnownEpoch)
        {
            lastKnownEpoch = currentEpoch;
            epochListener.onEpochChanged(currentEpoch);
        }
    }

    public long getNativeHandle()
    {
        return nativeHandle;
    }
}
