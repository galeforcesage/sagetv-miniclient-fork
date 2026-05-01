package sagex.miniclient.android.middleware;

import java.io.IOException;

import tv.danmaku.ijk.media.player.misc.IMediaDataSource;

/**
 * IJK IMediaDataSource backed by the native transport ring buffer.
 * <p>
 * IJK expects sequential reads with a position parameter that we ignore
 * since we're streaming from the push ring buffer.
 */
public class TransportIjkMediaSource implements IMediaDataSource
{
    private final TrickplayController controller;
    private boolean closed;

    public TransportIjkMediaSource(TrickplayController controller)
    {
        this.controller = controller;
    }

    @Override
    public int readAt(long position, byte[] buffer, int offset, int size) throws IOException
    {
        if (closed) return -1;

        int bytesRead = controller.readData(buffer, offset, size);
        if (bytesRead < 0)
        {
            return -1; /* IJK interprets -1 as EOF */
        }
        return bytesRead;
    }

    @Override
    public long getSize() throws IOException
    {
        /* Streaming — unknown size */
        return -1;
    }

    @Override
    public void close() throws IOException
    {
        closed = true;
        controller.close();
    }

    /**
     * Push data into the native ring buffer.
     */
    public void pushBytes(byte[] data, int offset, int length) throws IOException
    {
        controller.pushData(data, offset, length);
    }

    /**
     * Returns available space in the ring buffer.
     */
    public int bufferAvailable()
    {
        return controller.bufferAvailable();
    }

    /**
     * Flush the underlying transport buffer.
     */
    public void flush()
    {
        controller.flush();
    }

    /**
     * Signal end-of-stream from server.
     */
    public void setEOS()
    {
        controller.setEOS();
    }

    public boolean isEOS()
    {
        return controller.isEOS();
    }
}
