package sagex.miniclient.android.middleware;

/**
 * JNI bridge to the native SageTV transport layer.
 * <p>
 * Provides ring buffer, trickplay state machine, time truth,
 * sparse PCR/PTS timestamp sniffing, and stream epoch tracking.
 * <p>
 * All methods are static and operate on an opaque native handle.
 */
public final class NativeTransport
{
    private static boolean nativeAvailable;

    static
    {
        try
        {
            System.loadLibrary("sagetransport");
            nativeAvailable = true;
        }
        catch (UnsatisfiedLinkError e)
        {
            nativeAvailable = false;
        }
    }

    public static boolean isAvailable()
    {
        return nativeAvailable;
    }

    private NativeTransport() {}

    /* ---- Lifecycle ---- */
    public static native long nCreate(int bufferCapacity);
    public static native void nDestroy(long handle);
    public static native void nOpen(long handle, boolean pushMode);
    public static native void nClose(long handle);

    /* ---- Data flow ---- */
    public static native int  nPushData(long handle, byte[] data, int offset, int length);
    public static native int  nReadData(long handle, byte[] buffer, int offset, int length);
    public static native int  nBufferAvailable(long handle);
    public static native void nFlush(long handle);
    public static native void nSetEOS(long handle);
    public static native boolean nIsEOS(long handle);

    /* ---- Trickplay ---- */
    public static native void nBeginSeek(long handle, long targetMs);
    public static native long nCommitSeek(long handle);
    public static native void nNotifySeekComplete(long handle);
    public static native void nOnPlayerPosition(long handle, long positionMs);
    public static native long nGetReportedTime(long handle);
    public static native int  nGetState(long handle);
    public static native void nSetPaused(long handle, boolean paused);
    public static native void nSetPlaying(long handle);

    /* ---- Epoch ---- */
    public static native int  nGetEpoch(long handle);
    public static native void nIncrementEpoch(long handle);

    /* ---- Server time ---- */
    public static native void nSetServerStartTime(long handle, long timeMs);
    public static native long nGetServerStartTime(long handle);

    /* ---- Sniffing ---- */
    public static native void nEnableSniffing(long handle, boolean enable);
}
