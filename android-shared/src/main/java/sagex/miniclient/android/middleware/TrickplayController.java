package sagex.miniclient.android.middleware;

import android.os.Handler;
import android.os.Looper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Java-side trickplay orchestrator.
 *
 * <p>This class owns ALL clock / seek state in pure Java. The native
 * transport handle is used only as a ring buffer for push-mode data
 * delivery to the player.
 *
 * <p>Time model (one source of truth):
 * <ul>
 *   <li><b>Pull mode</b>: ExoPlayer's {@code getCurrentPosition()} <em>is</em>
 *       the timeline time, so reported time = playerPos.</li>
 *   <li><b>Push mode, no seek yet</b>: reported time = serverStartTimeMs +
 *       playerPos. {@code serverStartTimeMs} is set by
 *       {@link sagex.miniclient.MediaCmd} from the latest PushBuffer frame.</li>
 *   <li><b>After any seek converges</b>: reported time = playerPos +
 *       baseOffsetMs, where baseOffsetMs = seekTargetMs - posAtConvergence.
 *       This applies to both push and pull. Independent of
 *       serverStartTimeMs, so server-driven catch-up doesn't drift the
 *       OSD.</li>
 *   <li><b>During an in-flight seek</b>: reported time is frozen at the
 *       seek target, so SageTV's GETMEDIATIME polling doesn't see the
 *       transient.</li>
 * </ul>
 *
 * <p>Thread safety: all public methods are safe to call from any thread.
 * Seek-commit Runnable runs on the UI thread (player APIs require that).
 */
public class TrickplayController
{
    private static final Logger log = LoggerFactory.getLogger(TrickplayController.class);
    private static final long PULL_SEEK_COALESCE_MS = 200;
    private static final int  DEFAULT_BUFFER_CAPACITY = 4 * 1024 * 1024;

    /* ------------ Native ring buffer handle (push mode only) ------------ */
    private long nativeHandle;

    /* ------------ Java clock state ------------ */
    private final Handler uiHandler;
    private PlayerSeekCallback seekCallback;
    private boolean pushMode;
    private boolean opened;

    // Latest player position observation (from onPlayerPosition).
    private long lastPlayerPosMs = 0;

    // Server-supplied start time (push mode only). -1 = unknown.
    private long serverStartTimeMs = -1;

    // Active seek bookkeeping.
    // -1 means "no seek in flight". When a beginSeek() arrives, the
    // target is recorded here and getReportedTime() freezes on it until
    // the player position converges (mappingValid becomes true).
    private long seekTargetMs = -1;
    private boolean seekArmed = false;     // beginSeek called, not yet sent to player
    private boolean seekCommitted = false; // sent to player, awaiting convergence

    // Two-clock mapping. Once set, reported = playerPos + baseOffsetMs.
    private boolean mappingValid = false;
    private long    baseOffsetMs = 0;

    // Paused indicator (used only by isStable / getState).
    private boolean paused = false;

    /* ------------ Pending pre-ready seek ------------ */
    // If beginSeek() arrives before the player has been prepared, the
    // commit Runnable will fail. We park the target here and replay it
    // on notifyPlayerReady().
    private boolean pendingPreReadySeek = false;

    public interface PlayerSeekCallback
    {
        void onSeekTo(long timeMs);
    }

    /** Retained for binary compatibility; no longer used. */
    public interface EpochChangeListener
    {
        void onEpochChanged(int newEpoch);
    }

    private final Runnable seekCommitRunnable = new Runnable()
    {
        @Override
        public void run()
        {
            long target;
            PlayerSeekCallback cb;
            synchronized (TrickplayController.this)
            {
                if (seekTargetMs < 0)
                {
                    return;
                }
                target = seekTargetMs;
                seekArmed = false;
                seekCommitted = true;
                cb = seekCallback;
            }
            log.debug("Seek commit: target={}", target);
            if (cb != null)
            {
                cb.onSeekTo(target);
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
            log.warn("Native transport not available, push-mode disabled");
            nativeHandle = 0;
        }
    }

    public void setSeekCallback(PlayerSeekCallback callback)
    {
        synchronized (this)
        {
            this.seekCallback = callback;
        }
    }

    /** No-op. Retained for source compatibility. */
    public void setEpochListener(EpochChangeListener listener)
    {
        // intentionally empty
    }

    /**
     * @return true if the native ring buffer is available (push mode can
     *         use the JNI transport). The Java clock itself always works.
     */
    public boolean isNativeAvailable()
    {
        return nativeHandle != 0;
    }

    /* ---- Lifecycle ---- */

    public synchronized void open(boolean pushMode)
    {
        this.pushMode = pushMode;
        this.opened = true;

        // Reset clock state for the new playback session.
        lastPlayerPosMs = 0;
        serverStartTimeMs = -1;
        seekTargetMs = -1;
        seekArmed = false;
        seekCommitted = false;
        mappingValid = false;
        baseOffsetMs = 0;
        paused = false;
        pendingPreReadySeek = false;

        if (nativeHandle != 0)
        {
            NativeTransport.nOpen(nativeHandle, pushMode);
        }
        log.debug("open(pushMode={})", pushMode);
    }

    public void close()
    {
        uiHandler.removeCallbacks(seekCommitRunnable);
        synchronized (this)
        {
            opened = false;
        }
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

    /* ---- Data flow (push mode ring buffer) ---- */

    public int pushData(byte[] data, int offset, int length) throws IOException
    {
        if (nativeHandle == 0) return 0;
        return NativeTransport.nPushData(nativeHandle, data, offset, length);
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
        // A flush always invalidates the player-position → wallclock mapping.
        // The mapping will be re-established on the next seek convergence
        // (if a seek was in flight) or stay invalid until the player
        // produces a meaningful position.
        synchronized (this)
        {
            mappingValid = false;
            // Note: we do NOT clear seekTargetMs / seekArmed / seekCommitted.
            // A typical sequence is beginSeek() → flush() → push new data
            // → onPlayerPosition() converges → mapping established.
        }
        if (nativeHandle != 0)
        {
            NativeTransport.nFlush(nativeHandle);
        }
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
     * Begin a seek. Reported time freezes on {@code targetMs} immediately
     * so SageTV's GETMEDIATIME loop sees the intended target rather than
     * a transient value during the player rebuild.
     *
     * <p>Coalescing: rapid successive calls within the coalesce window
     * collapse to a single {@code seekCallback.onSeekTo(latestTarget)}.
     * Push mode commits immediately (no coalesce); pull mode waits
     * {@value #PULL_SEEK_COALESCE_MS} ms.
     */
    public void beginSeek(long targetMs)
    {
        synchronized (this)
        {
            seekTargetMs = targetMs;
            seekArmed = true;
            seekCommitted = false;
            mappingValid = false;
            pendingPreReadySeek = !opened; // park if open() hasn't fired
        }
        uiHandler.removeCallbacks(seekCommitRunnable);
        if (pushMode)
        {
            uiHandler.post(seekCommitRunnable);
        }
        else
        {
            uiHandler.postDelayed(seekCommitRunnable, PULL_SEEK_COALESCE_MS);
        }
        log.debug("beginSeek: target={}, push={}", targetMs, pushMode);
    }

    /**
     * Optional player hook. Today's logic doesn't gate on player readiness
     * (the coalesce delay handles cold-start), but kept as an injection
     * point for future deferred-seek logic.
     */
    public void notifyPlayerReady()
    {
        boolean replay;
        synchronized (this)
        {
            replay = pendingPreReadySeek && seekTargetMs >= 0;
            pendingPreReadySeek = false;
        }
        if (replay)
        {
            uiHandler.removeCallbacks(seekCommitRunnable);
            uiHandler.post(seekCommitRunnable);
        }
    }

    /**
     * Called by the player when its async seek operation finishes.
     * Today this is informational only; convergence is detected from
     * {@link #onPlayerPosition(long)}. Kept as a callback for future
     * tightening of the SEEK_COMMITTING window.
     */
    public void notifySeekComplete()
    {
        // intentionally empty — convergence is observed via player position.
    }

    /**
     * Called by the player to report its observed wallclock-independent
     * position. Drives mapping establishment after a seek.
     */
    public synchronized void onPlayerPosition(long positionMs)
    {
        lastPlayerPosMs = positionMs;

        if (seekCommitted && positionMs > 0)
        {
            // Establish the two-clock mapping: from now until the next
            // beginSeek/flush, reported time = playerPos + baseOffset.
            baseOffsetMs = seekTargetMs - positionMs;
            mappingValid = true;
            seekCommitted = false;
            log.debug("Mapping established: target={}, pos={}, baseOffset={}",
                      seekTargetMs, positionMs, baseOffsetMs);
            seekTargetMs = -1;
        }
    }

    /**
     * @return the time SageTV's GETMEDIATIME loop should see.
     *         Frozen during a seek; otherwise derived from player position
     *         via the active mapping (or push-mode server start time, if
     *         no mapping has been established yet).
     */
    public synchronized long getReportedTime()
    {
        // Frozen during in-flight seek so the OSD doesn't twitch.
        if (seekArmed || seekCommitted)
        {
            return seekTargetMs;
        }

        if (mappingValid)
        {
            return lastPlayerPosMs + baseOffsetMs;
        }

        if (pushMode && serverStartTimeMs >= 0)
        {
            return serverStartTimeMs + lastPlayerPosMs;
        }

        // Pull mode pre-mapping (initial load): playerPos IS timeline time.
        return lastPlayerPosMs;
    }

    /**
     * Coarse playback state for callers that need to know whether a seek
     * is pending. Only three values are produced now:
     * STABLE_PLAYING, STABLE_PAUSED, SEEK_PENDING.
     */
    public synchronized TrickplayState getState()
    {
        if (seekArmed || seekCommitted)
        {
            return TrickplayState.SEEK_PENDING;
        }
        return paused ? TrickplayState.STABLE_PAUSED : TrickplayState.STABLE_PLAYING;
    }

    public boolean isStable()
    {
        TrickplayState s = getState();
        return s != null && s.isStable();
    }

    public synchronized void setPaused(boolean paused)
    {
        this.paused = paused;
    }

    public void setPlaying()
    {
        setPaused(false);
    }

    /* ---- Server time (push mode startup baseline) ---- */

    public synchronized void setServerStartTime(long timeMs)
    {
        this.serverStartTimeMs = timeMs;
    }

    public synchronized long getServerStartTime()
    {
        return serverStartTimeMs;
    }

    /* ---- Epoch (deprecated; no consumer) ---- */

    public int getEpoch()
    {
        return 0;
    }

    public long getNativeHandle()
    {
        return nativeHandle;
    }
}
