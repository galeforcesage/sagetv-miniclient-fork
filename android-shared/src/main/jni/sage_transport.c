/*
 * SageTV Native Transport — Implementation
 *
 * Ring buffer with mutex/condvar, trickplay state machine,
 * time truth computation, sparse TS PCR/PTS sniffing, epoch tracking.
 */

#include "sage_transport.h"
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <android/log.h>

#define TAG "SageTransport"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)

/* Set to 1 to enable verbose transport debugging */
#define DEBUG_TRANSPORT 0
#if DEBUG_TRANSPORT
#define LOGV(...) LOGD(__VA_ARGS__)
#else
#define LOGV(...) ((void)0)
#endif

/* ------------------------------------------------------------------ */
/*  Internal helpers                                                   */
/* ------------------------------------------------------------------ */

static void timespec_add_ms(struct timespec* ts, int ms) {
    clock_gettime(CLOCK_REALTIME, ts);
    ts->tv_nsec += (long)ms * 1000000L;
    if (ts->tv_nsec >= 1000000000L) {
        ts->tv_sec  += ts->tv_nsec / 1000000000L;
        ts->tv_nsec %= 1000000000L;
    }
}

static inline int32_t min_i32(int32_t a, int32_t b) { return a < b ? a : b; }

/* ------------------------------------------------------------------ */
/*  Ring buffer primitives (caller must hold lock)                     */
/* ------------------------------------------------------------------ */

static int32_t ring_space_left(SageTransport* t) {
    return t->capacity - t->dataSize;
}

static int32_t ring_write(SageTransport* t, const uint8_t* src, int32_t len) {
    int32_t toWrite = min_i32(len, ring_space_left(t));
    if (toWrite <= 0) return 0;

    int32_t firstChunk = min_i32(toWrite, t->capacity - t->writePos);
    memcpy(t->buffer + t->writePos, src, firstChunk);
    if (toWrite > firstChunk) {
        memcpy(t->buffer, src + firstChunk, toWrite - firstChunk);
    }
    t->writePos = (t->writePos + toWrite) % t->capacity;
    t->dataSize += toWrite;
    return toWrite;
}

static int32_t ring_read(SageTransport* t, uint8_t* dest, int32_t len) {
    int32_t toRead = min_i32(len, t->dataSize);
    if (toRead <= 0) return 0;

    int32_t firstChunk = min_i32(toRead, t->capacity - t->readPos);
    memcpy(dest, t->buffer + t->readPos, firstChunk);
    if (toRead > firstChunk) {
        memcpy(dest + firstChunk, t->buffer, toRead - firstChunk);
    }
    t->readPos = (t->readPos + toRead) % t->capacity;
    t->dataSize -= toRead;
    return toRead;
}

static void ring_clear(SageTransport* t) {
    t->readPos  = 0;
    t->writePos = 0;
    t->dataSize = 0;
}

/* ------------------------------------------------------------------ */
/*  Sparse TS timestamp sniffing (caller must hold lock)              */
/* ------------------------------------------------------------------ */

/* Extract 33-bit PTS from 5-byte PES timestamp field. */
static int64_t parse_pts(const uint8_t* p) {
    int64_t pts = 0;
    pts  = ((int64_t)(p[0] >> 1) & 0x07) << 30;
    pts |= ((int64_t) p[1])               << 22;
    pts |= ((int64_t)(p[2] >> 1))         << 15;
    pts |= ((int64_t) p[3])               << 7;
    pts |= ((int64_t)(p[4] >> 1));
    return pts / 90; /* 90 kHz → ms */
}

/* Scan push data for TS sync bytes and extract PCR or PTS.
 * Lightweight: only checks packet boundaries, no full demux. */
static void sniff_timestamps(SageTransport* t, const uint8_t* data, int32_t len) {
    if (!t->sniffingEnabled || len < SAGE_TS_PACKET_SIZE) return;

    int pos = 0;
    /* Find first sync byte */
    while (pos < len - SAGE_TS_PACKET_SIZE && data[pos] != SAGE_TS_SYNC_BYTE) {
        pos++;
    }

    int samplesFound = 0;
    while (pos + SAGE_TS_PACKET_SIZE <= len && samplesFound < 2) {
        if (data[pos] != SAGE_TS_SYNC_BYTE) {
            pos++;
            continue;
        }

        uint8_t adapt_ctrl = (data[pos + 3] >> 4) & 0x03;
        /* Check adaptation field for PCR */
        if (adapt_ctrl == 0x02 || adapt_ctrl == 0x03) {
            int adapt_len = data[pos + 4];
            if (adapt_len >= 7) {
                uint8_t flags = data[pos + 5];
                if (flags & 0x10) { /* PCR present */
                    const uint8_t* pcr = &data[pos + 6];
                    int64_t base = ((int64_t)pcr[0] << 25)
                                 | ((int64_t)pcr[1] << 17)
                                 | ((int64_t)pcr[2] << 9)
                                 | ((int64_t)pcr[3] << 1)
                                 | ((int64_t)pcr[4] >> 7);
                    int64_t pcrMs = base / 90; /* 90 kHz base → ms */

                    /* Discontinuity detection */
                    if (t->lastSniffedPtsMs > 0) {
                        int64_t delta = pcrMs - t->lastSniffedPtsMs;
                        if (delta < -SAGE_DISCONTINUITY_BACKWARD_MS ||
                            delta >  SAGE_DISCONTINUITY_FORWARD_MS) {
                            LOGW("PCR discontinuity: last=%lld new=%lld delta=%lld, incrementing epoch",
                                 (long long)t->lastSniffedPtsMs, (long long)pcrMs, (long long)delta);
                            t->streamEpoch++;
                        }
                    }

                    t->lastSniffedPtsMs = pcrMs;
                    t->timestampSamples[t->timestampSampleIndex % SAGE_TIMESTAMP_SAMPLE_COUNT] = pcrMs;
                    t->timestampSampleIndex++;
                    if (t->timestampSampleCount < SAGE_TIMESTAMP_SAMPLE_COUNT)
                        t->timestampSampleCount++;
                    samplesFound++;

                    /* Disable sniffing after collecting enough samples */
                    if (t->timestampSampleIndex >= 4) {
                        t->sniffingEnabled = false;
                    }
                }
            }
        }

        /* Check for PES start code with PTS (if no PCR found yet) */
        if (samplesFound == 0 && (adapt_ctrl == 0x01 || adapt_ctrl == 0x03)) {
            int payloadStart = pos + 4;
            if (adapt_ctrl == 0x03) {
                payloadStart += 1 + data[pos + 4]; /* skip adaptation field */
            }
            if (payloadStart + 14 <= pos + SAGE_TS_PACKET_SIZE) {
                bool pusi = (data[pos + 1] & 0x40) != 0;
                if (pusi &&
                    data[payloadStart]   == 0x00 &&
                    data[payloadStart+1] == 0x00 &&
                    data[payloadStart+2] == 0x01) {
                    uint8_t streamId = data[payloadStart + 3];
                    /* Video or audio PES */
                    if ((streamId >= 0xC0 && streamId <= 0xEF) ||
                         streamId == 0xBD) {
                        uint8_t ptsFlags = (data[payloadStart + 7] >> 6) & 0x03;
                        if (ptsFlags >= 2 && payloadStart + 14 <= pos + SAGE_TS_PACKET_SIZE) {
                            int64_t ptsMs = parse_pts(&data[payloadStart + 9]);

                            if (t->lastSniffedPtsMs > 0) {
                                int64_t delta = ptsMs - t->lastSniffedPtsMs;
                                if (delta < -SAGE_DISCONTINUITY_BACKWARD_MS ||
                                    delta >  SAGE_DISCONTINUITY_FORWARD_MS) {
                                    LOGW("PTS discontinuity: last=%lld new=%lld, incrementing epoch",
                                         (long long)t->lastSniffedPtsMs, (long long)ptsMs);
                                    t->streamEpoch++;
                                }
                            }

                            t->lastSniffedPtsMs = ptsMs;
                            t->timestampSamples[t->timestampSampleIndex % SAGE_TIMESTAMP_SAMPLE_COUNT] = ptsMs;
                            t->timestampSampleIndex++;
                            if (t->timestampSampleCount < SAGE_TIMESTAMP_SAMPLE_COUNT)
                                t->timestampSampleCount++;
                            samplesFound++;

                            if (t->timestampSampleIndex >= 4) {
                                t->sniffingEnabled = false;
                            }
                        }
                    }
                }
            }
        }

        pos += SAGE_TS_PACKET_SIZE;
    }
}

/* ------------------------------------------------------------------ */
/*  Time truth computation (caller must hold lock)                    */
/* ------------------------------------------------------------------ */

static void update_reported_time(SageTransport* t) {
    switch (t->trickplayState) {
        case TRICKPLAY_STABLE_PLAYING:
        case TRICKPLAY_STABLE_PAUSED: {
            if (t->pushMode) {
                /* PUSH mode time mapping.
                 *
                 * Two paths:
                 *  (a) Initial channel-tune / no seek yet: mappingEstablished
                 *      is false. Use the legacy formula
                 *          reported = serverStartTimeMs + observedPlayerPos
                 *      with serverStartTimeMs supplied by the Java side
                 *      (set on PushBuffer with a fresh serverMuxTime).
                 *  (b) After a seek has converged (RECOVERING→STABLE): use
                 *      the same two-clock mapping pull mode uses
                 *          reported = observedPlayerPos + baseOffsetMs
                 *      where baseOffsetMs = seekTargetMs - posAtConvergence.
                 *      This is independent of serverStartTimeMs, which the
                 *      server only refreshes once per flush—meanwhile
                 *      ExoPlayer may rapidly advance currentPosition during
                 *      its post-prepare/retry buffering catch-up, which would
                 *      send (sst + opp) racing past real time.
                 *
                 * The legacy path also rejects backward jumps > 1.5 s to
                 * cover the brief window after a server-driven flush where
                 * the new serverMuxTime has not yet arrived. */
                int64_t opp = t->observedPlayerPositionMs;
                if (t->mappingEstablished && opp >= 0) {
                    int64_t newTime = opp + t->baseOffsetMs;
                    t->reportedTimeMs = newTime;
                    t->frozenTimeMs = newTime;
                } else {
                    int64_t sst = t->serverStartTimeMs;
                    if (sst >= 0 && opp >= 0) {
                        int64_t newTime = sst + opp;
                        if (t->reportedTimeMs > 0 && newTime < t->reportedTimeMs - 1500) {
                            LOGV("PUSH: rejecting backward SMT jump %lld → %lld (sst=%lld, opp=%lld)",
                                 (long long)t->reportedTimeMs, (long long)newTime,
                                 (long long)sst, (long long)opp);
                            break;
                        }
                        t->reportedTimeMs = newTime;
                        t->frozenTimeMs = newTime;
                    }
                }
            } else {
                /* PULL mode two-clock model: SMT = PTT + baseOffsetMs
                 *
                 * PTT = observedPlayerPositionMs (ExoPlayer's internal timeline)
                 * baseOffsetMs bridges PTT → SMT (Server Media Time)
                 */
                int64_t ptt = t->observedPlayerPositionMs;
                if (ptt < 0) break;

                /* Reject transient ptt=0 when we were previously stable */
                if (ptt == 0 && t->lastStablePttMs > 2000) {
                    LOGV("PULL: ignoring transient ptt=0 (lastStable=%lld)",
                         (long long)t->lastStablePttMs);
                    break;
                }

                if (!t->mappingEstablished) break;

                int64_t candidateSmt = ptt + t->baseOffsetMs;

                /* Reject large backward jumps in SMT (> 1.5s) */
                if (t->reportedTimeMs > 0 && candidateSmt < t->reportedTimeMs - 1500) {
                    LOGV("PULL: rejecting backward SMT jump %lld → %lld (ptt=%lld, offset=%lld)",
                         (long long)t->reportedTimeMs, (long long)candidateSmt,
                         (long long)ptt, (long long)t->baseOffsetMs);
                    break;
                }

                /* Reject large forward jumps in SMT (> 2s in one tick).
                 * Normal playback advances ~0.5s/tick (500ms interval);
                 * anything > 2s per tick indicates a position glitch. */
                if (t->reportedTimeMs > 0 && candidateSmt > t->reportedTimeMs + 2000) {
                    LOGV("PULL: rejecting forward SMT jump %lld → %lld (ptt=%lld, offset=%lld)",
                         (long long)t->reportedTimeMs, (long long)candidateSmt,
                         (long long)ptt, (long long)t->baseOffsetMs);
                    break;
                }

                t->lastStablePttMs = ptt;
                t->reportedTimeMs = candidateSmt;
                t->frozenTimeMs = candidateSmt;
            }
            break;
        }
        case TRICKPLAY_SEEK_PENDING:
        case TRICKPLAY_SEEK_COMMITTING:
        case TRICKPLAY_RECOVERING:
            /* Time stays frozen — do not update reportedTimeMs */
            break;
    }
}

/* ------------------------------------------------------------------ */
/*  Public API — Lifecycle                                            */
/* ------------------------------------------------------------------ */

SageTransport* sage_transport_create(int32_t bufferCapacity) {
    if (bufferCapacity <= 0) bufferCapacity = SAGE_DEFAULT_BUFFER_CAPACITY;

    SageTransport* t = (SageTransport*)calloc(1, sizeof(SageTransport));
    if (!t) return NULL;

    t->buffer = (uint8_t*)malloc(bufferCapacity);
    if (!t->buffer) {
        free(t);
        return NULL;
    }
    t->capacity = bufferCapacity;

    pthread_mutex_init(&t->lock, NULL);
    pthread_cond_init(&t->dataAvailable, NULL);
    pthread_cond_init(&t->spaceAvailable, NULL);

    t->serverStartTimeMs      = -1;
    t->observedPlayerPositionMs = -1;
    t->reportedTimeMs         = 0;
    t->frozenTimeMs           = 0;
    t->seekTargetMs           = -1;
    t->baseOffsetMs           = 0;
    t->lastStablePttMs        = 0;
    t->mappingEstablished     = false;
    t->lastSniffedPtsMs       = -1;
    t->trickplayState         = TRICKPLAY_STABLE_PAUSED;

    LOGD("Created transport, buffer=%d bytes", bufferCapacity);
    return t;
}

void sage_transport_destroy(SageTransport* t) {
    if (!t) return;

    pthread_mutex_destroy(&t->lock);
    pthread_cond_destroy(&t->dataAvailable);
    pthread_cond_destroy(&t->spaceAvailable);

    free(t->buffer);
    free(t);
    LOGD("Destroyed transport");
}

void sage_transport_open(SageTransport* t, bool pushMode) {
    if (!t) return;
    pthread_mutex_lock(&t->lock);

    ring_clear(t);
    t->pushMode        = pushMode;
    t->eos             = false;
    t->opened          = true;
    t->serverStartTimeMs      = -1;
    t->observedPlayerPositionMs = -1;
    t->reportedTimeMs         = 0;
    t->frozenTimeMs           = 0;
    t->seekTargetMs           = -1;
    t->streamEpoch            = 0;
    t->lastSniffedPtsMs       = -1;
    t->sniffingEnabled        = true;
    t->timestampSampleIndex   = 0;
    t->timestampSampleCount   = 0;

    /* Two-clock state (PULL mode) */
    t->baseOffsetMs           = 0;
    t->lastStablePttMs        = 0;
    t->mappingEstablished     = false;

    if (!pushMode) {
        /* Treat initial playback as an implicit seek to time 0.
         * Start in RECOVERING so we don't report player time
         * until we've established the baseOffset mapping. */
        t->trickplayState = TRICKPLAY_RECOVERING;
        t->seekTargetMs   = 0;
        LOGD("Opened transport, pushMode=0 (pull), starting in RECOVERING");
    } else {
        t->trickplayState = TRICKPLAY_STABLE_PAUSED;
        LOGD("Opened transport, pushMode=1 (push)");
    }
    pthread_cond_broadcast(&t->spaceAvailable);
    pthread_mutex_unlock(&t->lock);
}

void sage_transport_close(SageTransport* t) {
    if (!t) return;
    pthread_mutex_lock(&t->lock);

    t->opened = false;
    t->eos    = true;
    ring_clear(t);

    /* Wake any blocked threads */
    pthread_cond_broadcast(&t->dataAvailable);
    pthread_cond_broadcast(&t->spaceAvailable);

    LOGD("Closed transport");
    pthread_mutex_unlock(&t->lock);
}

/* ------------------------------------------------------------------ */
/*  Public API — Data flow                                            */
/* ------------------------------------------------------------------ */

int32_t sage_transport_push(SageTransport* t, const uint8_t* data, int32_t offset, int32_t len) {
    if (!t || len <= 0) return 0;

    const uint8_t* src = data + offset;
    int32_t totalWritten = 0;

    pthread_mutex_lock(&t->lock);

    /* If we get data after EOS, reset EOS (re-seek scenario) */
    if (t->eos) {
        t->eos = false;
    }

    while (totalWritten < len && t->opened) {
        int32_t space = ring_space_left(t);
        if (space <= 0) {
            /* Buffer full — block with timeout */
            struct timespec ts;
            timespec_add_ms(&ts, 500);
            pthread_cond_timedwait(&t->spaceAvailable, &t->lock, &ts);
            continue;
        }

        int32_t written = ring_write(t, src + totalWritten, len - totalWritten);
        totalWritten += written;
        pthread_cond_signal(&t->dataAvailable);
    }

    /* Sniff timestamps from the pushed data */
    if (t->sniffingEnabled) {
        sniff_timestamps(t, src, len);
    }

    pthread_mutex_unlock(&t->lock);
    return totalWritten;
}

int32_t sage_transport_read(SageTransport* t, uint8_t* dest, int32_t offset, int32_t len) {
    if (!t || len <= 0) return 0;

    pthread_mutex_lock(&t->lock);

    if (t->dataSize == 0) {
        if (t->eos) {
            pthread_mutex_unlock(&t->lock);
            return -1; /* End of stream */
        }
        /* Wait briefly for data */
        struct timespec ts;
        timespec_add_ms(&ts, SAGE_READ_WAIT_MS);
        pthread_cond_timedwait(&t->dataAvailable, &t->lock, &ts);

        if (t->dataSize == 0) {
            pthread_mutex_unlock(&t->lock);
            return t->eos ? -1 : 0;
        }
    }

    int32_t bytesRead = ring_read(t, dest + offset, len);
    pthread_cond_signal(&t->spaceAvailable);

    pthread_mutex_unlock(&t->lock);
    return bytesRead;
}

int32_t sage_transport_buffer_available(SageTransport* t) {
    if (!t) return 0;
    pthread_mutex_lock(&t->lock);
    int32_t space = ring_space_left(t);
    pthread_mutex_unlock(&t->lock);
    return space;
}

void sage_transport_flush(SageTransport* t) {
    if (!t) return;
    pthread_mutex_lock(&t->lock);

    /* Only clear ring buffer in push mode */
    if (t->pushMode) {
        ring_clear(t);
    }
    t->eos = false;

    /* Freeze time at current value */
    t->frozenTimeMs = t->reportedTimeMs;

    /* Transition to seek-pending-like state: time is frozen */
    if (t->trickplayState == TRICKPLAY_STABLE_PLAYING ||
        t->trickplayState == TRICKPLAY_STABLE_PAUSED) {
        /* Don't change state here — seek() will set SEEK_PENDING.
         * Just freeze the time. */
    }

    /* Re-enable timestamp sniffing for the new data */
    t->sniffingEnabled      = true;
    t->timestampSampleIndex = 0;
    t->timestampSampleCount = 0;
    t->lastSniffedPtsMs     = -1;

    /* Reset the two-clock mapping. A subsequent seek() will re-establish
     * it via beginSeek→commitSeek→RECOVERING→STABLE; a server-initiated
     * flush without a seek (e.g. channel change) will fall back to the
     * sst+opp path until a fresh serverMuxTime arrives. */
    t->mappingEstablished = false;

    /* Increment epoch on flush (new stream segment) */
    t->streamEpoch++;

    /* Wake blocked writers */
    pthread_cond_broadcast(&t->spaceAvailable);

    LOGV("Flushed transport, epoch=%d", t->streamEpoch);
    pthread_mutex_unlock(&t->lock);
}

void sage_transport_set_eos(SageTransport* t) {
    if (!t) return;
    pthread_mutex_lock(&t->lock);
    t->eos = true;
    pthread_cond_broadcast(&t->dataAvailable);
    pthread_mutex_unlock(&t->lock);
}

bool sage_transport_is_eos(SageTransport* t) {
    if (!t) return true;
    pthread_mutex_lock(&t->lock);
    bool val = t->eos;
    pthread_mutex_unlock(&t->lock);
    return val;
}

/* ------------------------------------------------------------------ */
/*  Public API — Trickplay                                            */
/* ------------------------------------------------------------------ */

void sage_transport_begin_seek(SageTransport* t, int64_t targetMs) {
    if (!t) return;
    pthread_mutex_lock(&t->lock);

    t->seekTargetMs = targetMs;

    if (t->trickplayState != TRICKPLAY_SEEK_PENDING) {
        /* Freeze time at current reported value */
        t->frozenTimeMs = t->reportedTimeMs;
    }

    /* Reset stable PTT tracking for the new seek */
    if (!t->pushMode) {
        t->lastStablePttMs = 0;
    }

    /* Coalesce: stay in or enter SEEK_PENDING */
    t->trickplayState = TRICKPLAY_SEEK_PENDING;

    LOGD("beginSeek: target=%lld, frozen=%lld, pullMode=%d",
         (long long)targetMs, (long long)t->frozenTimeMs, !t->pushMode);
    pthread_mutex_unlock(&t->lock);
}

int64_t sage_transport_commit_seek(SageTransport* t) {
    if (!t) return -1;
    pthread_mutex_lock(&t->lock);

    int64_t target = t->seekTargetMs;
    t->trickplayState = TRICKPLAY_SEEK_COMMITTING;

    LOGD("commitSeek: target=%lld", (long long)target);
    pthread_mutex_unlock(&t->lock);
    return target;
}

void sage_transport_notify_seek_complete(SageTransport* t) {
    if (!t) return;
    pthread_mutex_lock(&t->lock);

    if (t->trickplayState == TRICKPLAY_SEEK_COMMITTING) {
        t->trickplayState = TRICKPLAY_RECOVERING;
        t->lastStablePttMs = 0;  /* Reset for convergence detection */
        LOGD("Seek complete → RECOVERING");
    }

    pthread_mutex_unlock(&t->lock);
}

void sage_transport_on_player_position(SageTransport* t, int64_t positionMs) {
    if (!t) return;

    pthread_mutex_lock(&t->lock);

    t->observedPlayerPositionMs = positionMs;

    if (t->trickplayState == TRICKPLAY_RECOVERING) {
        if (positionMs > 0) {
            bool accept = false;

            if (t->pushMode) {
                /* PUSH mode: accept immediately once position is positive */
                accept = true;
            } else {
                /* PULL mode: accept once we get a non-zero, stable position.
                 * First few ticks may be stale pre-seek values. Wait for
                 * position to stop changing rapidly (2 consecutive ticks). */
                if (t->lastStablePttMs > 0 && positionMs != t->lastStablePttMs) {
                    /* Position is progressing — good enough */
                    accept = true;
                }
                t->lastStablePttMs = positionMs;
            }

            if (accept) {
                if (!t->pushMode && t->seekTargetMs >= 0) {
                    /* Compute the two-clock mapping:
                     * baseOffsetMs = targetSMT - currentPTT
                     * This makes SMT = PTT + baseOffsetMs = targetSMT
                     * Even if Exo reports 400ms, we map it to 42980ms. */
                    t->baseOffsetMs = t->seekTargetMs - positionMs;
                    t->mappingEstablished = true;
                    t->lastStablePttMs = positionMs;
                    /* Reset reportedTimeMs to the new seek target so the
                     * glitch filters in update_reported_time don't block
                     * subsequent position updates. */
                    t->reportedTimeMs = t->seekTargetMs;
                    t->frozenTimeMs = t->seekTargetMs;
                    LOGD("RECOVERING → STABLE_PLAYING: ptt=%lld, target=%lld, baseOffset=%lld → smt=%lld",
                         (long long)positionMs, (long long)t->seekTargetMs,
                         (long long)t->baseOffsetMs, (long long)(positionMs + t->baseOffsetMs));
                } else if (t->pushMode && t->seekTargetMs >= 0) {
                    /* Establish the same two-clock mapping pull mode uses.
                     * From here until the next flush, reported time is
                     * derived from the player's monotonic position rather
                     * than serverStartTimeMs, which the server refreshes
                     * only on flush. */
                    t->baseOffsetMs = t->seekTargetMs - positionMs;
                    t->mappingEstablished = true;
                    t->reportedTimeMs = t->seekTargetMs;
                    t->frozenTimeMs = t->seekTargetMs;
                    LOGD("RECOVERING → STABLE_PLAYING (push): pos=%lld, target=%lld, baseOffset=%lld",
                         (long long)positionMs, (long long)t->seekTargetMs,
                         (long long)t->baseOffsetMs);
                } else {
                    LOGD("RECOVERING → STABLE_PLAYING, pos=%lld", (long long)positionMs);
                }
                t->trickplayState = TRICKPLAY_STABLE_PLAYING;
            }
        }
    }

    update_reported_time(t);
    pthread_mutex_unlock(&t->lock);
}

int64_t sage_transport_get_reported_time(SageTransport* t) {
    if (!t) return 0;
    pthread_mutex_lock(&t->lock);

    int64_t result;
    if (t->trickplayState == TRICKPLAY_SEEK_PENDING ||
        t->trickplayState == TRICKPLAY_SEEK_COMMITTING ||
        t->trickplayState == TRICKPLAY_RECOVERING) {
        /* During seeks: in pull mode, report the seek target so server
         * doesn't think we're at 0 and re-seek.
         * In push mode, report the frozen pre-seek time. */
        if (!t->pushMode && t->seekTargetMs >= 0) {
            result = t->seekTargetMs;
        } else {
            result = t->frozenTimeMs;
        }
    } else if (t->pushMode && t->serverStartTimeMs < 0) {
        result = 0;
    } else {
        result = t->reportedTimeMs;
    }

    pthread_mutex_unlock(&t->lock);
    return result;
}

TrickplayState sage_transport_get_state(SageTransport* t) {
    if (!t) return TRICKPLAY_STABLE_PAUSED;
    pthread_mutex_lock(&t->lock);
    TrickplayState s = t->trickplayState;
    pthread_mutex_unlock(&t->lock);
    return s;
}

void sage_transport_set_paused(SageTransport* t, bool paused) {
    if (!t) return;
    pthread_mutex_lock(&t->lock);

    if (t->trickplayState == TRICKPLAY_STABLE_PLAYING && paused) {
        t->trickplayState = TRICKPLAY_STABLE_PAUSED;
        update_reported_time(t);
        LOGD("STABLE_PLAYING → STABLE_PAUSED");
    } else if (t->trickplayState == TRICKPLAY_STABLE_PAUSED && !paused) {
        t->trickplayState = TRICKPLAY_STABLE_PLAYING;
        LOGD("STABLE_PAUSED → STABLE_PLAYING");
    }

    pthread_mutex_unlock(&t->lock);
}

void sage_transport_set_playing(SageTransport* t) {
    sage_transport_set_paused(t, false);
}

/* ------------------------------------------------------------------ */
/*  Public API — Epoch                                                */
/* ------------------------------------------------------------------ */

int32_t sage_transport_get_epoch(SageTransport* t) {
    if (!t) return 0;
    pthread_mutex_lock(&t->lock);
    int32_t e = t->streamEpoch;
    pthread_mutex_unlock(&t->lock);
    return e;
}

void sage_transport_increment_epoch(SageTransport* t) {
    if (!t) return;
    pthread_mutex_lock(&t->lock);
    t->streamEpoch++;
    LOGV("Epoch incremented to %d", t->streamEpoch);
    pthread_mutex_unlock(&t->lock);
}

/* ------------------------------------------------------------------ */
/*  Public API — Server time                                          */
/* ------------------------------------------------------------------ */

void sage_transport_set_server_start_time(SageTransport* t, int64_t timeMs) {
    if (!t) return;
    pthread_mutex_lock(&t->lock);
    t->serverStartTimeMs = timeMs;
    pthread_mutex_unlock(&t->lock);
}

int64_t sage_transport_get_server_start_time(SageTransport* t) {
    if (!t) return -1;
    pthread_mutex_lock(&t->lock);
    int64_t v = t->serverStartTimeMs;
    pthread_mutex_unlock(&t->lock);
    return v;
}

/* ------------------------------------------------------------------ */
/*  Public API — Timestamp sniffing control                           */
/* ------------------------------------------------------------------ */

void sage_transport_enable_sniffing(SageTransport* t, bool enable) {
    if (!t) return;
    pthread_mutex_lock(&t->lock);
    t->sniffingEnabled = enable;
    if (enable) {
        t->timestampSampleIndex = 0;
        t->timestampSampleCount = 0;
    }
    pthread_mutex_unlock(&t->lock);
}
