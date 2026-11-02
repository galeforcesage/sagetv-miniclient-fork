package sagex.miniclient.android.video.ijkplayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import sagex.miniclient.net.HasClose;
import sagex.miniclient.net.HasPushBuffer;
import sagex.miniclient.net.PushBufferDataSource;
import sagex.miniclient.net.CancellableDataSource;
import sagex.miniclient.util.VerboseLogging;
import tv.danmaku.ijk.media.player.misc.IMediaDataSource;

/**
 * Created by seans on 20/12/15.
 */
public class IJKPushMediaSource implements IMediaDataSource, HasPushBuffer, HasClose,
        CancellableDataSource
{
    private static final Logger log = LoggerFactory.getLogger(IJKPushMediaSource.class);

    private PushBufferDataSource dataSource;
    private String url;
    boolean released = false;

    public IJKPushMediaSource()
    {
    }

    public void open(String url) throws IOException
    {
        this.url = url;
    }

    private synchronized void _open() throws IOException
    {
        if (dataSource != null)
        {
            // we are already opened
            return;
        }
        dataSource = new PushBufferDataSource();
        dataSource.open(url);
    }

    @Override
    public int readAt(long position, byte[] bytes, int offset, int size) throws IOException
    {

        if (VerboseLogging.DATASOURCE_LOGGING)

        // ijkmediasource does a zero len read on a seek to see if it was successful
        // we'll return 0 (ok) so that the player buffers get cleaned up, and the the player
        // can start reading from the new location.
        if (size == 0) return 0;

        try
        {
            if (dataSource == null)
            {
                log.debug("Data source was null.  Reopening the datasource");
                _open();
            }

            if (dataSource.isServerEOS())
            {
                log.debug("Datasource thinks server is EOS; readAt pos={}, offset={}, size={}",
                        position, offset, size);
            }
            return dataSource.read(position, bytes, offset, size);
        }
        catch (IOException error)
        {
            log.warn("Push data source read failed", error);
            throw error;
        }
    }

    @Override
    public long getSize() throws IOException
    {
        if (dataSource == null)
        {
            log.debug("Get Size Called: Data source was null.  Reopening the datasource");
            _open();
        }
        return dataSource.size();
    }

    @Override
    public void close() throws IOException
    {
        if (dataSource != null)
        {
            log.debug("Datasource close called");
            dataSource.close();
        }
    }

    @Override
    public synchronized void release()
    {
        if (released) return;
        log.debug("Datasource release called");

        try
        {
            close();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        if (dataSource!=null)
        {
            try
            {
                dataSource.release();
            }
            catch (RuntimeException error)
            {
                log.warn("Push data source release failed", error);
            }
        }

        released = true;
        dataSource = null;
    }

    @Override
    public void cancel()
    {
        release();
    }

    @Override
    public boolean isCanceled()
    {
        return released;
    }

    @Override
    public void setEOS()
    {
        if (dataSource != null)
        {
            dataSource.setEOS();
        }
    }

    @Override
    public int bufferAvailable()
    {
        if (dataSource != null)
        {
            return dataSource.bufferAvailable();
        }

        return PushBufferDataSource.PIPE_SIZE;
    }

    @Override
    public void pushBytes(byte[] bytes, int offset, int len) throws IOException
    {
        if (released)
        {
            log.info("DataSource is released, so ignoring push.");
            return;
        }
        while (dataSource == null)
        {
            // wait for the datasource
            try
            {
                Thread.sleep(50);
                if (VerboseLogging.DATASOURCE_LOGGING) log.warn("Waiting for datasource...");
            }
            catch (InterruptedException e)
            {
                Thread.interrupted();
                return;
            }
        }
        dataSource.pushBytes(bytes, offset, len);
    }

    @Override
    public void flush()
    {
        if (dataSource != null)
        {
            dataSource.flush();
        }
    }

    @Override
    public long getBytesRead()
    {
        if (dataSource != null)
        {
            return dataSource.getBytesRead();
        }

        return 0;
    }
}
