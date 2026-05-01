/*
 * JNI bindings for SageTV Native Transport.
 *
 * Maps Java methods in sagex.miniclient.android.middleware.NativeTransport
 * to sage_transport C functions.
 */

#include <jni.h>
#include "sage_transport.h"

#define _JNI_FN2(prefix, name) prefix ## name
#define _JNI_FN(prefix, name) _JNI_FN2(prefix, name)
#define JNI_PREFIX Java_sagex_miniclient_android_middleware_NativeTransport_
#define JNI_FN(name) _JNI_FN(JNI_PREFIX, name)

/* Helper to cast jlong handle to SageTransport* */
static inline SageTransport* toTransport(jlong handle) {
    return (SageTransport*)(intptr_t)handle;
}

/* ---- Lifecycle ---- */

JNIEXPORT jlong JNICALL
JNI_FN(nCreate)(JNIEnv* env, jclass clazz, jint bufferCapacity) {
    SageTransport* t = sage_transport_create(bufferCapacity);
    return (jlong)(intptr_t)t;
}

JNIEXPORT void JNICALL
JNI_FN(nDestroy)(JNIEnv* env, jclass clazz, jlong handle) {
    sage_transport_destroy(toTransport(handle));
}

JNIEXPORT void JNICALL
JNI_FN(nOpen)(JNIEnv* env, jclass clazz, jlong handle, jboolean pushMode) {
    sage_transport_open(toTransport(handle), pushMode);
}

JNIEXPORT void JNICALL
JNI_FN(nClose)(JNIEnv* env, jclass clazz, jlong handle) {
    sage_transport_close(toTransport(handle));
}

/* ---- Data flow ---- */

JNIEXPORT jint JNICALL
JNI_FN(nPushData)(JNIEnv* env, jclass clazz, jlong handle,
                      jbyteArray data, jint offset, jint length) {
    SageTransport* t = toTransport(handle);
    if (!t || !data) return 0;

    /* GetPrimitiveArrayCritical pins the array in-place (no copy on most VMs).
     * Safe here: we do not call back into Java or allocate Java objects
     * while the pin is held. The mutex wait inside push uses
     * pthread_cond_timedwait which is pure native. */
    jbyte* bytes = (jbyte*)(*env)->GetPrimitiveArrayCritical(env, data, NULL);
    if (!bytes) return 0;

    int32_t written = sage_transport_push(t, (const uint8_t*)bytes, offset, length);

    (*env)->ReleasePrimitiveArrayCritical(env, data, bytes, JNI_ABORT);
    return written;
}

JNIEXPORT jint JNICALL
JNI_FN(nReadData)(JNIEnv* env, jclass clazz, jlong handle,
                      jbyteArray buffer, jint offset, jint length) {
    SageTransport* t = toTransport(handle);
    if (!t || !buffer) return 0;

    jbyte* bytes = (jbyte*)(*env)->GetPrimitiveArrayCritical(env, buffer, NULL);
    if (!bytes) return 0;

    int32_t bytesRead = sage_transport_read(t, (uint8_t*)bytes, offset, length);

    (*env)->ReleasePrimitiveArrayCritical(env, buffer, bytes, 0); /* copy back */
    return bytesRead;
}

JNIEXPORT jint JNICALL
JNI_FN(nBufferAvailable)(JNIEnv* env, jclass clazz, jlong handle) {
    return sage_transport_buffer_available(toTransport(handle));
}

JNIEXPORT void JNICALL
JNI_FN(nFlush)(JNIEnv* env, jclass clazz, jlong handle) {
    sage_transport_flush(toTransport(handle));
}

JNIEXPORT void JNICALL
JNI_FN(nSetEOS)(JNIEnv* env, jclass clazz, jlong handle) {
    sage_transport_set_eos(toTransport(handle));
}

JNIEXPORT jboolean JNICALL
JNI_FN(nIsEOS)(JNIEnv* env, jclass clazz, jlong handle) {
    return sage_transport_is_eos(toTransport(handle));
}

/* ---- Trickplay ---- */

JNIEXPORT void JNICALL
JNI_FN(nBeginSeek)(JNIEnv* env, jclass clazz, jlong handle, jlong targetMs) {
    sage_transport_begin_seek(toTransport(handle), targetMs);
}

JNIEXPORT jlong JNICALL
JNI_FN(nCommitSeek)(JNIEnv* env, jclass clazz, jlong handle) {
    return sage_transport_commit_seek(toTransport(handle));
}

JNIEXPORT void JNICALL
JNI_FN(nNotifySeekComplete)(JNIEnv* env, jclass clazz, jlong handle) {
    sage_transport_notify_seek_complete(toTransport(handle));
}

JNIEXPORT void JNICALL
JNI_FN(nOnPlayerPosition)(JNIEnv* env, jclass clazz, jlong handle, jlong positionMs) {
    sage_transport_on_player_position(toTransport(handle), positionMs);
}

JNIEXPORT jlong JNICALL
JNI_FN(nGetReportedTime)(JNIEnv* env, jclass clazz, jlong handle) {
    return sage_transport_get_reported_time(toTransport(handle));
}

JNIEXPORT jint JNICALL
JNI_FN(nGetState)(JNIEnv* env, jclass clazz, jlong handle) {
    return (jint)sage_transport_get_state(toTransport(handle));
}

JNIEXPORT void JNICALL
JNI_FN(nSetPaused)(JNIEnv* env, jclass clazz, jlong handle, jboolean paused) {
    sage_transport_set_paused(toTransport(handle), paused);
}

JNIEXPORT void JNICALL
JNI_FN(nSetPlaying)(JNIEnv* env, jclass clazz, jlong handle) {
    sage_transport_set_playing(toTransport(handle));
}

/* ---- Epoch ---- */

JNIEXPORT jint JNICALL
JNI_FN(nGetEpoch)(JNIEnv* env, jclass clazz, jlong handle) {
    return sage_transport_get_epoch(toTransport(handle));
}

JNIEXPORT void JNICALL
JNI_FN(nIncrementEpoch)(JNIEnv* env, jclass clazz, jlong handle) {
    sage_transport_increment_epoch(toTransport(handle));
}

/* ---- Server time ---- */

JNIEXPORT void JNICALL
JNI_FN(nSetServerStartTime)(JNIEnv* env, jclass clazz, jlong handle, jlong timeMs) {
    sage_transport_set_server_start_time(toTransport(handle), timeMs);
}

JNIEXPORT jlong JNICALL
JNI_FN(nGetServerStartTime)(JNIEnv* env, jclass clazz, jlong handle) {
    return sage_transport_get_server_start_time(toTransport(handle));
}

/* ---- Sniffing ---- */

JNIEXPORT void JNICALL
JNI_FN(nEnableSniffing)(JNIEnv* env, jclass clazz, jlong handle, jboolean enable) {
    sage_transport_enable_sniffing(toTransport(handle), enable);
}
