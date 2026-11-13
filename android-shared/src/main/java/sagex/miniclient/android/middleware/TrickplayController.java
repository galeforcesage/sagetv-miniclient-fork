package sagex.miniclient.android.middleware;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

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
 *   <li><b>Pull mode (direct)</b>: ExoPlayer's {@code getCurrentPosition()}
 *       <em>is</em> the timeline time, so reported time = playerPos.</li>
 *   <li><b>Push mode, no seek yet</b>: reported time = serverStartTimeMs +
 *       playerPos. {@code serverStartTimeMs} is set by
 *       {@link sagex.miniclient.MediaCmd} from the latest PushBuffer frame.</li>
 *   <li><b>Pull mode, server-conditioned (pull-xcode), no seek yet</b>: same
 *       formula as push -- reported time = serverStartTimeMs + playerPos.
 *       A server-conditioned pull reopen (/msproxy/XCODE_SETUP) restarts its
 *       local player timeline near zero, so {@code MediaCmd#classifyPullTimeline}
 *       seeds serverStartTimeMs with the absolute seek-target origin, driven by
 *       the server's CAP_EFFECTIVE_DELIVERY hint. A no-op (0) for direct pull.</li>
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

    /**
     * Quiet-window coalesce. Each beginSeek() resets this timer; when it
     * elapses with no further seeks, the latest target is committed.
     * Sized to absorb a typical SageTV smooth-FF/REW burst (≈150 ms
     * spacing) while staying under human "feels instant" threshold.
     */
    private static final long SEEK_QUIET_WINDOW_MS = 180;

    /**
     * Outgoing-SEEK coalesce window for push mode. Originally push used
     * zero coalesce on the assumption that "the server does the seek so
     * there is nothing to debounce." Field traces (Fold, May 2026) prove
     * that wrong: each MEDIACMD_SEEK forces the server transcoder to
     * reposition + emit a MEDIACMD_FLUSH; a 13-SEEK FF burst over 2.8 s
     * produced 12 sequential FLUSHes and 5.5 s of stalled video while
     * the OSD timeline ran ahead.
     *
     * <p>Coalescing outgoing SEEKs to the latest target collapses that
     * to one FLUSH and one transcoder reposition per burst. The window
     * needs to be short enough to feel responsive (well under the
     * human "feels instant" threshold) but long enough to absorb a
     * normal SageTV remote FF cadence (≈200-300 ms between events).</p>
     */
    private static final long PUSH_SEEK_QUIET_WINDOW_MS = 250;

    /**
     * Hard ceiling on how long beginSeek() may defer a held-button burst.
     * If the user holds REW indefinitely we still need the video to
     * advance every so often, even if seeks keep arriving.
     */
    private static final long SEEK_MAX_DEFER_MS = 1500;

    /**
     * Safety ceiling on how long we wait for player convergence before
     * letting another seek fire. Convergence is normally seen in well
     * under a second; this only triggers on a stalled / failed pipeline
     * (sniff failure, EOS race, etc) so a held button can't deadlock.
     */
    private static final long CONVERGENCE_TIMEOUT_MS = 1500;

    /**
     * The first rendered sample after a reprime should be close to the server's
     * seek origin when IJK exposes source-relative time. A zero-based player
     * sample is instead much closer to zero and needs the origin added once.
     */
    private static final long PLAYER_TIMELINE_MATCH_WINDOW_MS = 30_000;

    /**
     * A Party correction installs an authoritative mapping before the server
     * flush arrives. Keep accepting a player-position reset for the lifetime
     * of the correction observer, but do not let an unrelated later regression
     * replace a mapping that already landed.
     */
    /**
     * Pause-resume scrubber monotonicity guard.
     *
     * <p>Symptom: pausing then resuming playback can cause the underlying
     * player to briefly re-report an earlier wallclock-independent position
     * (re-anchor to the last decoded keyframe, internal pipeline restart,
     * etc.) before catching up. Because {@link #getReportedTime()} feeds
     * SageTV's GETMEDIATIME loop straight from {@link #lastPlayerPosMs}
     * once a mapping is valid, that backward blip can show up as the OSD
     * scrubber visibly jumping back ~½–2 seconds on resume.</p>
     *
     * <p>The guard drops player-position samples whose backward delta from
     * the previous sample falls inside the
     * {@code [MIN, MAX]} regression window <em>only when no seek is in
     * flight or pending</em>. A seek legitimately rewinds the underlying
     * clock and the existing seek-mapping path handles it.</p>
     *
     * <p>Boundaries:
     * <ul>
     *   <li>Anything &lt;= {@code MIN} is normal jitter — accept.</li>
     *   <li>Anything &gt;= {@code MAX} is a real reload / loop / file
     *       change — accept (and resync from there).</li>
     *   <li>Anything in between is the pause-resume blip — drop.</li>
     * </ul>
     */
    private static final long MONOTONIC_GUARD_MIN_REGRESSION_MS = 250;
    private static final long MONOTONIC_GUARD_MAX_REGRESSION_MS = 30_000;
    private static final long FORWARD_DISCONTINUITY_MIN_JUMP_MS = 30_000;
    private static final long FORWARD_DISCONTINUITY_MIN_EXCESS_MS = 10_000;

    /**
     * Native push-mode ring buffer capacity, in bytes.
     *
     * <p>Wider buffer = more tolerance for upstream jitter (Wi-Fi, VPN,
     * shared uplinks, transient server stalls) before the ring underruns
     * and playback stutters. Bumped from the original 4 MB to 8 MB to
     * roughly double the jitter window without imposing meaningful memory
     * pressure on lower-RAM clients (phones).
     *
     * <p>Override at runtime with the {@code sagetv.push.ring.bytes}
     * system property if you want to test a larger / smaller window.
     */
    private static final int DEFAULT_BUFFER_CAPACITY = Integer.getInteger(
            "sagetv.push.ring.bytes", 8 * 1024 * 1024);

    /* ------------ Native ring buffer handle (push mode only) ------------ */
    private long nativeHandle;

    /* ------------ Java clock state ------------ */
    private final Handler uiHandler;
    private PlayerSeekCallback seekCallback;
    private boolean pushMode;
    private boolean opened;

    // Latest player position observation (from onPlayerPosition).
    private long lastPlayerPosMs = 0;
    private long lastPlayerSampleMonoMs;

    // Server-supplied start time (push mode only). -1 = unknown.
    private long serverStartTimeMs = -1;

    // Active seek bookkeeping.
    // -1 means "no seek in flight". When a beginSeek() arrives, the
    // target is recorded here and getReportedTime() freezes on it until
    // the player position converges (mappingValid becomes true).
    private long seekTargetMs = -1;
    private boolean seekArmed = false;     // beginSeek called, not yet sent to player
    private boolean seekCommitted = false; // sent to player, awaiting convergence
    // The target value actually sent to the player at the most recent
    // commit. Used by onPlayerPosition() to compute the mapping, which
    // must reference the committed target (not seekTargetMs, which may
    // have been overwritten by a later beginSeek that's still pending).
    private long committedTargetMs = -1;

    // Two-clock mapping. Once set, reported = playerPos + baseOffsetMs.
    private boolean mappingValid = false;
    private long    baseOffsetMs = 0;
    private long postFlushTimelineClassificationDeadlineMs;
    private boolean authoritativeResetPending;
    private boolean authoritativeSamplePending;
    private boolean authoritativeTimelineActive;
    private long authoritativePositionMs;

    // Paused indicator (used only by isStable / getState).
    private boolean paused = false;
    private boolean buffering;
    private long bufferingReportedTimeMs = -1;

    // ---- Seek epoch (SEEK_EPOCH_V1) ----
    // Monotonically increasing id of the current contiguous run of stream
    // content. Every reposition (flush) starts a new epoch. Position reports
    // are tagged with the epoch they render so the server (and the client's
    // own stale-sample guard) can reject a sample sampled before a reposition
    // from being applied after it. Bumped in flush(); can also be aligned to
    // the server's streamEpoch via setServerEpoch().
    private int epoch = 0;

    /* ------------ Pending pre-ready seek ------------ */
    // If beginSeek() arrives before the player has been prepared, the
    // commit Runnable will fail. We park the target here and replay it
    // on notifyPlayerReady().
    private boolean pendingPreReadySeek = false;

    // Monotonic time of the first beginSeek() in the current burst, used to
    // enforce SEEK_MAX_DEFER_MS. Reset to 0 when a commit fires.
    private long burstStartedAtMs = 0;

    // Monotonic time of the last commit, used to enforce CONVERGENCE_TIMEOUT_MS
    // when a new burst arrives while seekCommitted is still true.
    private long lastCommitAtMs = 0;

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
                if (seekTargetMs < 0 || !seekArmed)
                {
                    return;
                }

                // In-flight gate: a previous seek is still converging.
                // Defer this commit unless the safety ceiling has fired.
                long now = SystemClock.elapsedRealtime();
                if (seekCommitted && (now - lastCommitAtMs) < CONVERGENCE_TIMEOUT_MS)
                {
                    long remaining = CONVERGENCE_TIMEOUT_MS - (now - lastCommitAtMs);
                    log.debug("Seek deferred {}ms waiting for convergence (target={})", remaining, seekTargetMs);
                    uiHandler.postDelayed(this, Math.max(40, remaining));
                    return;
                }

                target = seekTargetMs;
                seekArmed = false;
                seekCommitted = true;
                committedTargetMs = target;
                burstStartedAtMs = 0;
                lastCommitAtMs = now;
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
        lastPlayerSampleMonoMs = 0;
        serverStartTimeMs = -1;
        seekTargetMs = -1;
        seekArmed = false;
        seekCommitted = false;
        committedTargetMs = -1;
        mappingValid = false;
        baseOffsetMs = 0;
        postFlushTimelineClassificationDeadlineMs = 0;
        authoritativeResetPending = false;
        authoritativeTimelineActive = false;
        authoritativePositionMs = 0;
        paused = false;
        buffering = false;
        bufferingReportedTimeMs = -1;
        pendingPreReadySeek = false;
        burstStartedAtMs = 0;
        lastCommitAtMs = 0;

        if (nativeHandle != 0)
        {
            NativeTransport.nOpen(nativeHandle, pushMode);
        }
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

    public void cancelPendingAction()
    {
        uiHandler.removeCallbacks(seekCommitRunnable);
        synchronized (this)
        {
            seekTargetMs = -1;
            seekArmed = false;
            seekCommitted = false;
            committedTargetMs = -1;
            pendingPreReadySeek = false;
            burstStartedAtMs = 0;
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
        if (nativeHandle == 0)
        {
            return 0;
        }
        return NativeTransport.nPushData(nativeHandle, data, offset, length);
    }

    public int readData(byte[] buffer, int offset, int length)
    {
        if (nativeHandle == 0) return 0;
        return NativeTransport.nReadData(nativeHandle, buffer, offset, length);
    }

    public int bufferAvailable()
    {
        if (nativeHandle == 0) return 0;
        return NativeTransport.nBufferAvailable(nativeHandle);
    }

    /**
     * @return number of bytes currently filled in the ring buffer (capacity
     *         minus available space). Used by the player to gate prepare()
     *         until a minimum prebuffer has arrived from the server,
     *         avoiding sniff retries on push-mode startup.
     */
    public int bufferFilledBytes()
    {
        if (nativeHandle == 0) return 0;
        int avail = NativeTransport.nBufferAvailable(nativeHandle);
        return Math.max(0, DEFAULT_BUFFER_CAPACITY - avail);
    }

    public void flush()
    {
        // Ordinary flushes invalidate the player-position → wallclock mapping.
        // A Party correction is different: its authoritative mapping must span
        // the server flush because some push players restart near zero while
        // others retain their current position. onPlayerPosition() rebases the
        // mapping if a reset is actually observed.
        synchronized (this)
        {
            if (shouldArmAuthoritativeResetOnFlush(
                    authoritativeTimelineActive, authoritativeSamplePending))
            {
                authoritativeResetPending = true;
            }
            if (invalidatesMappingOnFlush(authoritativeTimelineActive))
            {
                mappingValid = false;
            }
            postFlushTimelineClassificationDeadlineMs =
                    SystemClock.elapsedRealtime() + 10_000;
            // A flush is a reposition boundary: start a new epoch so any
            // in-flight position sample tagged with the prior epoch is
            // discarded rather than applied to the new content.
            epoch++;
            lastPlayerSampleMonoMs = 0;
            buffering = true;
            bufferingReportedTimeMs = currentReportedTime();
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
     * <p>Coalescing model (push and pull, identical):
     * <ul>
     *   <li><b>Quiet window</b> ({@value #SEEK_QUIET_WINDOW_MS} ms): each
     *       beginSeek resets the timer. Bursts collapse to a single
     *       {@code onSeekTo(latestTarget)} after the user stops.</li>
     *   <li><b>Max defer</b> ({@value #SEEK_MAX_DEFER_MS} ms): if a burst
     *       lasts longer (held REW), force a commit so the video
     *       advances; the next chunk of the burst will batch into the
     *       following commit.</li>
     *   <li><b>In-flight gate</b>: while the player is still converging
     *       on a prior seek (mappingValid==false &amp;&amp; seekCommitted),
     *       new commits are deferred until convergence or the safety
     *       ceiling ({@value #CONVERGENCE_TIMEOUT_MS} ms).</li>
     * </ul>
     *
     * <p>OSD responsiveness is independent of commit cadence: reported
     * time freezes at {@code targetMs} the instant this method returns.
     */
    public void beginSeek(long targetMs)
    {
        long delay;
        boolean isPush;
        synchronized (this)
        {
            authoritativeTimelineActive = false;
            if (preservesAuthoritativePositionOnPushSeek(
                    pushMode, authoritativeResetPending))
            {
                log.debug("Ignoring push transport seek target {} while Party target {} is authoritative",
                        targetMs, authoritativePositionMs);
                return;
            }
            seekTargetMs = targetMs;
            seekArmed = true;
            // Note: we do NOT clear seekCommitted here. The in-flight gate
            // in the runnable needs to know a prior commit hasn't converged.
            mappingValid = false;
            pendingPreReadySeek = !opened;
            isPush = pushMode;

            long now = SystemClock.elapsedRealtime();
            if (burstStartedAtMs == 0) burstStartedAtMs = now;

            long window = isPush ? PUSH_SEEK_QUIET_WINDOW_MS : SEEK_QUIET_WINDOW_MS;
            delay = SeekCoalescePolicy.computeCommitDelayMs(now, burstStartedAtMs, window, SEEK_MAX_DEFER_MS);
        }

        // Push-mode preflush: drop the stale ring contents NOW so IJK's
        // demuxer thread doesn't continue draining pre-seek bytes while
        // we wait for the server's MEDIACMD_FLUSH (which can lag the
        // SEEK by several seconds during transcoder restart). The next
        // bytes pushed into the ring are post-seek by definition, so
        // dropping is always safe.
        if (isPush && nativeHandle != 0)
        {
            NativeTransport.nFlush(nativeHandle);
        }

        uiHandler.removeCallbacks(seekCommitRunnable);
        uiHandler.postDelayed(seekCommitRunnable, delay);
        log.debug("beginSeek: target={}, push={}, delay={}ms", targetMs, isPush, delay);
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
    public void onPlayerPosition(long positionMs)
    {
        boolean wakeup = false;
        synchronized (this)
        {
            if (buffering)
            {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (postFlushTimelineClassificationDeadlineMs > 0
                    && now > postFlushTimelineClassificationDeadlineMs)
            {
                postFlushTimelineClassificationDeadlineMs = 0;
            }
            if (pushMode && !authoritativeTimelineActive
                    && postFlushTimelineClassificationDeadlineMs > 0
                    && isSourceRelativePlayerTimeline(
                    serverStartTimeMs, positionMs))
            {
                postFlushTimelineClassificationDeadlineMs = 0;
                baseOffsetMs = 0;
                mappingValid = true;
                seekArmed = false;
                seekCommitted = false;
                committedTargetMs = -1;
                seekTargetMs = -1;
                log.warn("Player timeline is already source-relative after flush: serverStart={}, playerPos={}; baseline not added",
                        serverStartTimeMs, positionMs);
            }
            if (shouldAnchorAuthoritativePosition(authoritativeResetPending,
                    authoritativeSamplePending, positionMs, lastPlayerPosMs))
            {
                lastPlayerPosMs = positionMs;
                baseOffsetMs = authoritativeBaseOffset(
                        authoritativePositionMs, positionMs);
                mappingValid = true;
                authoritativeResetPending = false;
                authoritativeSamplePending = false;
                lastPlayerSampleMonoMs = now;
                log.debug("Authoritative Party clock anchored: target={}, rawPos={}, baseOffset={}",
                        authoritativePositionMs, positionMs, baseOffsetMs);
                return;
            }

            // Pause-resume monotonicity guard. See class-level constants.
            // Only applies when no seek is armed/committed — seeks are
            // expected to rewind the clock and are handled by the mapping
            // path below.
            if (SeekCoalescePolicy.shouldDropBackwardSample(lastPlayerPosMs, positionMs,
                    seekArmed, seekCommitted,
                    MONOTONIC_GUARD_MIN_REGRESSION_MS, MONOTONIC_GUARD_MAX_REGRESSION_MS))
            {
                long regression = lastPlayerPosMs - positionMs;
                log.debug("Monotonicity guard: dropping backward player pos {} (last {}, delta -{}ms, paused={})",
                          positionMs, lastPlayerPosMs, regression, paused);
                return;
            }

            long elapsedSinceLastSampleMs = lastPlayerSampleMonoMs <= 0
                    ? 0 : now - lastPlayerSampleMonoMs;
            if (pushMode && lastPlayerSampleMonoMs > 0
                    && SeekCoalescePolicy.isForwardTimestampDiscontinuity(
                    lastPlayerPosMs, positionMs, elapsedSinceLastSampleMs,
                    seekArmed, seekCommitted,
                    FORWARD_DISCONTINUITY_MIN_JUMP_MS,
                    FORWARD_DISCONTINUITY_MIN_EXCESS_MS))
            {
                long logicalPositionMs = mappedPosition(lastPlayerPosMs);
                baseOffsetMs = logicalPositionMs - positionMs;
                mappingValid = true;
                log.warn("Player timestamp discontinuity rebased: previous={}, current={}, logical={}, baseOffset={}",
                        lastPlayerPosMs, positionMs, logicalPositionMs, baseOffsetMs);
            }

            lastPlayerPosMs = positionMs;
            lastPlayerSampleMonoMs = now;

            if (seekCommitted && positionMs > 0)
            {
                // Establish the two-clock mapping using the COMMITTED target
                // (not seekTargetMs, which may have been overwritten by a
                // later beginSeek waiting in the in-flight gate).
                baseOffsetMs = committedTargetMs - positionMs;
                mappingValid = true;
                seekCommitted = false;
                log.debug("Mapping established: target={}, pos={}, baseOffset={}",
                          committedTargetMs, positionMs, baseOffsetMs);
                committedTargetMs = -1;
                if (!seekArmed)
                {
                    // No follow-on seek pending; clear the freeze target.
                    seekTargetMs = -1;
                }
                // If a follow-on seek was deferred by the in-flight gate,
                // poke the runnable so it can fire now.
                wakeup = seekArmed;
            }
        }
        if (wakeup)
        {
            uiHandler.removeCallbacks(seekCommitRunnable);
            uiHandler.post(seekCommitRunnable);
        }
    }

    public synchronized void installAuthoritativePosition(long positionMs)
    {
        if (positionMs < 0) return;
        seekTargetMs = -1;
        seekArmed = false;
        seekCommitted = false;
        committedTargetMs = -1;
        pendingPreReadySeek = false;
        authoritativePositionMs = positionMs;
        authoritativeTimelineActive = true;
        authoritativeResetPending = false;
        authoritativeSamplePending = true;
        mappingValid = true;
        log.debug("Authoritative Party position staged: target={}, playerPos={}",
                positionMs, lastPlayerPosMs);
    }

    static boolean invalidatesMappingOnFlush(boolean authoritativeResetPending)
    {
        return !authoritativeResetPending;
    }

    static boolean shouldArmAuthoritativeResetOnFlush(
            boolean authoritativeTimelineActive,
            boolean authoritativeSamplePending)
    {
        return authoritativeTimelineActive && authoritativeSamplePending;
    }

    static long authoritativeBaseOffset(long targetPositionMs, long rawPlayerPositionMs)
    {
        return targetPositionMs - rawPlayerPositionMs;
    }

    static boolean shouldAnchorAuthoritativePosition(boolean resetPending,
            boolean samplePending, long playerPositionMs, long lastPlayerPositionMs)
    {
        return resetPending
                && (samplePending || playerPositionMs < lastPlayerPositionMs);
    }

    public synchronized void prepareAuthoritativeTransportReposition()
    {
        if (!authoritativeTimelineActive) return;
        log.debug("Preparing transport reposition for Party target {}",
                authoritativePositionMs);
    }

    public synchronized void armAuthoritativeTransportReposition()
    {
        if (!authoritativeTimelineActive) return;
        authoritativeResetPending = true;
        authoritativeSamplePending = true;
        log.debug("Awaiting post-reposition sample for Party target {}",
                authoritativePositionMs);
    }

    static boolean preservesAuthoritativePositionOnPushSeek(
            boolean pushMode, boolean authoritativeResetPending)
    {
        return pushMode && authoritativeResetPending;
    }

    /**
     * @return the time SageTV's GETMEDIATIME loop should see.
     *         Frozen during a seek; otherwise derived from player position
     *         via the active mapping (or the server-start-time baseline, for
     *         push or server-conditioned pull-xcode, if no mapping has been
     *         established yet).
     */
    public synchronized long getReportedTime()
    {
        return reportedTimeWhileBuffering(
                buffering, bufferingReportedTimeMs, currentReportedTime());
    }

    static long reportedTimeWhileBuffering(
            boolean buffering, long heldPositionMs, long livePositionMs)
    {
        return buffering && heldPositionMs >= 0
                ? heldPositionMs : livePositionMs;
    }

    private long currentReportedTime()
    {
        // Frozen during in-flight seek so the OSD doesn't twitch.
        return reportedTimeForPlayerPosition(
                lastPlayerPosMs, seekArmed || seekCommitted, seekTargetMs,
                authoritativeSamplePending, authoritativePositionMs,
                mappingValid, baseOffsetMs, serverStartTimeMs);
    }

    /**
     * Maps a separately sampled player position without installing it as the
     * controller's latest live clock sample.
     */
    public synchronized long getReportedTimeForPlayerPosition(long playerPositionMs)
    {
        return reportedTimeForPlayerPosition(
                playerPositionMs, seekArmed || seekCommitted, seekTargetMs,
                authoritativeSamplePending, authoritativePositionMs,
                mappingValid, baseOffsetMs, serverStartTimeMs);
    }

    static long reportedTimeForPlayerPosition(
            long playerPositionMs,
            boolean seekPending,
            long seekTargetMs,
            boolean authoritativeSamplePending,
            long authoritativePositionMs,
            boolean mappingValid,
            long baseOffsetMs,
            long serverStartTimeMs)
    {
        if (seekPending) return seekTargetMs;
        if (authoritativeSamplePending) return authoritativePositionMs;
        if (mappingValid) return playerPositionMs + baseOffsetMs;
        if (serverStartTimeMs >= 0)
        {
            return playerPositionMs + initialPlayerTimelineOffset(
                    serverStartTimeMs, playerPositionMs);
        }
        return playerPositionMs;
    }

    public static long initialPlayerTimelineOffset(
            long serverStartTimeMs, long playerPositionMs)
    {
        if (serverStartTimeMs < 500 || playerPositionMs < 0) return 0;
        long distanceFromServerTimeline =
                absoluteDifference(playerPositionMs, serverStartTimeMs);
        long distanceFromZeroTimeline = playerPositionMs;
        return distanceFromServerTimeline <= PLAYER_TIMELINE_MATCH_WINDOW_MS
                && distanceFromServerTimeline < distanceFromZeroTimeline
                ? 0
                : serverStartTimeMs;
    }

    static boolean isSourceRelativePlayerTimeline(
            long serverStartTimeMs, long playerPositionMs)
    {
        return serverStartTimeMs >= 500
                && initialPlayerTimelineOffset(
                serverStartTimeMs, playerPositionMs) == 0;
    }


    private static long absoluteDifference(long left, long right)
    {
        return left >= right ? left - right : right - left;
    }

    private long mappedPosition(long playerPositionMs)
    {
        if (mappingValid) return playerPositionMs + baseOffsetMs;
        if (serverStartTimeMs >= 0)
        {
            return playerPositionMs + initialPlayerTimelineOffset(
                    serverStartTimeMs, playerPositionMs);
        }
        return playerPositionMs;
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

    public synchronized void setBuffering(boolean buffering)
    {
        if (buffering && !this.buffering)
        {
            bufferingReportedTimeMs = currentReportedTime();
        }
        this.buffering = buffering;
        if (!buffering)
        {
            bufferingReportedTimeMs = -1;
        }
    }

    public void setPlaying()
    {
        setPaused(false);
    }

    /* ---- Server time (pre-mapping startup baseline: push, or pull-xcode reopen origin) ---- */

    public synchronized void setServerStartTime(long timeMs)
    {
        if (authoritativeTimelineActive) return;
        this.serverStartTimeMs = timeMs;
    }

    public synchronized long getServerStartTime()
    {
        return serverStartTimeMs;
    }

    /* ---- Seek epoch (SEEK_EPOCH_V1) ---- */

    /**
     * Current client-side epoch. Bumped on every {@link #flush()} (reposition
     * boundary). Consumers tag position reports with this so stale samples are
     * dropped across a reposition.
     */
    public synchronized int getEpoch()
    {
        return epoch;
    }

    /**
     * Align the client epoch to the server's {@code streamEpoch} (from the NG
     * playback context). If the server reports a newer epoch than we've locally
     * observed, advance to it so subsequent reports carry the server's id.
     * No-op when SEEK_EPOCH_V1 was not negotiated (caller-gated).
     */
    public synchronized void setServerEpoch(int serverEpoch)
    {
        if (serverEpoch > epoch)
        {
            epoch = serverEpoch;
        }
    }

    public long getNativeHandle()
    {
        return nativeHandle;
    }
}
