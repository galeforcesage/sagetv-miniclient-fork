/*
 * SageTV Native Transport Layer
 *
 * Provides a JNI-backed ring buffer, trickplay state machine, time truth,
 * sparse PCR/PTS timestamp sniffing, and stream epoch tracking.
 *
 * Thread safety: all public functions are thread-safe via mutex.
 * getReportedTime() uses atomic reads for lock-free polling.
 */

#ifndef SAGE_TRANSPORT_H
#define SAGE_TRANSPORT_H

#include <stdint.h>
#include <stdbool.h>
#include <pthread.h>

#define SAGE_DEFAULT_BUFFER_CAPACITY (4 * 1024 * 1024)
#define SAGE_TIMESTAMP_SAMPLE_COUNT 8
#define SAGE_TS_PACKET_SIZE 188
#define SAGE_TS_SYNC_BYTE 0x47
#define SAGE_PES_START_CODE_PREFIX 0x000001
#define SAGE_SEEK_COALESCE_WINDOW_NS (200LL * 1000000LL)
#define SAGE_DISCONTINUITY_BACKWARD_MS 5000
#define SAGE_DISCONTINUITY_FORWARD_MS 30000
#define SAGE_READ_WAIT_MS 50

typedef enum {
    TRICKPLAY_STABLE_PLAYING  = 0,
    TRICKPLAY_STABLE_PAUSED   = 1,
    TRICKPLAY_SEEK_PENDING    = 2,
    TRICKPLAY_SEEK_COMMITTING = 3,
    TRICKPLAY_RECOVERING      = 4
} TrickplayState;

typedef struct {
    /* Ring buffer (push mode only) */
    uint8_t*  buffer;
    int32_t   capacity;
    int32_t   readPos;
    int32_t   writePos;
    int32_t   dataSize;

    /* Trickplay state machine */
    TrickplayState trickplayState;
    int32_t streamEpoch;
    int64_t   reportedTimeMs;
    int64_t   frozenTimeMs;
    int64_t   seekTargetMs;
    int64_t   observedPlayerPositionMs;
    int64_t   serverStartTimeMs;

    /* PULL mode: two-clock mapping (SMT = PTT + baseOffsetMs) */
    int64_t   baseOffsetMs;       /* SMT - PTT offset, computed at seek commit */
    int64_t   lastStablePttMs;    /* last non-glitch player timeline position */
    bool      mappingEstablished; /* true once baseOffsetMs is valid */

    /* Sparse timestamp sniffing */
    int64_t   lastSniffedPtsMs;
    int64_t   timestampSamples[SAGE_TIMESTAMP_SAMPLE_COUNT];
    int32_t   timestampSampleIndex;
    int32_t   timestampSampleCount;

    /* Transport state (packed bools at end to avoid padding holes) */
    bool      pushMode;
    bool      eos;
    bool      opened;
    bool      sniffingEnabled;

    /* Synchronization */
    pthread_mutex_t lock;
    pthread_cond_t  dataAvailable;
    pthread_cond_t  spaceAvailable;
} SageTransport;


/* Lifecycle */
SageTransport* sage_transport_create(int32_t bufferCapacity);
void           sage_transport_destroy(SageTransport* t);
void           sage_transport_open(SageTransport* t, bool pushMode);
void           sage_transport_close(SageTransport* t);

/* Data flow */
int32_t        sage_transport_push(SageTransport* t, const uint8_t* data, int32_t offset, int32_t len);
int32_t        sage_transport_read(SageTransport* t, uint8_t* dest, int32_t offset, int32_t len);
int32_t        sage_transport_buffer_available(SageTransport* t);
void           sage_transport_flush(SageTransport* t);
void           sage_transport_set_eos(SageTransport* t);
bool           sage_transport_is_eos(SageTransport* t);

/* Trickplay */
void           sage_transport_begin_seek(SageTransport* t, int64_t targetMs);
int64_t        sage_transport_commit_seek(SageTransport* t);
void           sage_transport_on_player_position(SageTransport* t, int64_t positionMs);
int64_t        sage_transport_get_reported_time(SageTransport* t);
TrickplayState sage_transport_get_state(SageTransport* t);
void           sage_transport_set_paused(SageTransport* t, bool paused);
void           sage_transport_set_playing(SageTransport* t);
void           sage_transport_notify_seek_complete(SageTransport* t);

/* Epoch */
int32_t        sage_transport_get_epoch(SageTransport* t);
void           sage_transport_increment_epoch(SageTransport* t);

/* Server time */
void           sage_transport_set_server_start_time(SageTransport* t, int64_t timeMs);
int64_t        sage_transport_get_server_start_time(SageTransport* t);

/* Timestamp sniffing */
void           sage_transport_enable_sniffing(SageTransport* t, bool enable);

#endif /* SAGE_TRANSPORT_H */
