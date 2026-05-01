package sagex.miniclient.android.middleware;

import android.net.Uri;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;

import java.io.IOException;

/**
 * ExoPlayer DataSource backed by the native transport ring buffer.
 * <p>
 * Reads are served from the JNI ring buffer. Large reads (up to 256KB)
 * minimize JNI call overhead. One JNI call per read — never per packet.
 */
public class TransportDataSource implements DataSource
{
    private final TrickplayController controller;
    private Uri uri;
    private boolean opened;

    public TransportDataSource(TrickplayController controller)
    {
        this.controller = controller;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException
    {
        this.uri = dataSpec.uri;
        this.opened = true;
        return C.LENGTH_UNSET;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException
    {
        if (!opened) return C.RESULT_END_OF_INPUT;

        int bytesRead = controller.readData(buffer, offset, length);

        if (bytesRead < 0)
        {
            return C.RESULT_END_OF_INPUT;
        }

        return bytesRead;
    }

    @Override
    public Uri getUri()
    {
        return uri;
    }

    @Override
    public void close() throws IOException
    {
        opened = false;
    }

    @Override
    public void addTransferListener(TransferListener transferListener)
    {
        // No-op for native transport
    }

    /**
     * Flush the underlying transport buffer.
     * Called during seek/pipeline reset.
     */
    public void flush()
    {
        controller.flush();
    }

    /**
     * Push data into the native ring buffer.
     * Called from BaseMediaPlayerImpl.pushData().
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
     * Signal end-of-stream from server.
     */
    public void setEOS()
    {
        controller.setEOS();
    }

    /**
     * Release resources.
     */
    public void release()
    {
        opened = false;
        controller.close();
    }

    public boolean isEOS()
    {
        return controller.isEOS();
    }
}
