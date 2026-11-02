package sagex.miniclient.android.video.ijkplayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;

import sagex.miniclient.net.BufferedPullDataSource;
import sagex.miniclient.net.CancellableDataSource;
import sagex.miniclient.net.HasClose;
import sagex.miniclient.net.ISageTVDataSource;
import sagex.miniclient.util.VerboseLogging;
import tv.danmaku.ijk.media.player.misc.IMediaDataSource;

/**
 * Created by seans on 20/12/15.
 */
public class IJKPullMediaSource implements IMediaDataSource, HasClose, CancellableDataSource {
    private static final Logger log = LoggerFactory.getLogger(IJKPullMediaSource.class);
    private static final int DEFAULT_RETRY_COUNT = 2;
    private static final int MIN_BUFFER_BYTES = 64 * 1024;
    private static final int MAX_BUFFER_BYTES = 2 * 1024 * 1024;
    private String host=null;

    private volatile ISageTVDataSource dataSource;
    private String url;
    private volatile boolean canceled;
    private final DisconnectListener disconnectListener;

    public interface DisconnectListener {
        void onNetworkDisconnected(IOException error);
    }

    public IJKPullMediaSource() {
        this(null, null);
    }

    public IJKPullMediaSource(String host) {
        this(host, null);
    }

    public IJKPullMediaSource(String host, DisconnectListener disconnectListener) {
        this.host=host;
        this.disconnectListener = disconnectListener;
    }

    public void open(String url) throws IOException {
        this.url = url;
        canceled = false;
    }

    private synchronized void _open() throws IOException {
        if (dataSource != null) return;
        if (canceled) throw canceledException();
        dataSource = new BufferedPullDataSource(host, getPullBufferBytes());
        dataSource.open(url);
    }

    @Override
    public int readAt(long position, byte[] bytes, int offset, int size) throws IOException {
        if (VerboseLogging.DATASOURCE_LOGGING)
            log.debug("readAt(): pos: {}, offset:{}, size: {}", position, offset, size);
        IOException lastFailure = null;
        for (int attempt = 0; attempt <= DEFAULT_RETRY_COUNT; attempt++) {
            if (canceled) throw canceledException();
            try {
                if (dataSource == null) _open();
                return dataSource.read(position, bytes, offset, size);
            } catch (IOException error) {
                lastFailure = error;
                closeCurrentDataSource();
                if (canceled) throw canceledException();
                if (attempt < DEFAULT_RETRY_COUNT) {
                    log.warn("Pull read failed at {} (attempt {}/{}); reopening: {}",
                            position, attempt + 1, DEFAULT_RETRY_COUNT + 1, error.toString());
                }
            }
        }
        if (disconnectListener != null) disconnectListener.onNetworkDisconnected(lastFailure);
        throw lastFailure;
    }

    @Override
    public long getSize() throws IOException {
        if (dataSource == null) _open();
        return dataSource.size();
    }

    @Override
    public void close() throws IOException {
        cancel();
    }

    @Override
    public void cancel() {
        canceled = true;
        closeCurrentDataSource();
    }

    @Override
    public boolean isCanceled() {
        return canceled;
    }

    private synchronized void closeCurrentDataSource() {
        ISageTVDataSource current = dataSource;
        dataSource = null;
        if (current != null) current.close();
    }

    private static int getPullBufferBytes() {
        int configured = Integer.getInteger(
                "sagetv.pull.buffer.bytes",
                BufferedPullDataSource.DEFAULT_BUFFER_SIZE);
        return Math.max(MIN_BUFFER_BYTES, Math.min(MAX_BUFFER_BYTES, configured));
    }

    private static InterruptedIOException canceledException() {
        return new InterruptedIOException("IJK pull data source canceled");
    }

    public boolean bindPartyAdmission(String handle) {
        ISageTVDataSource ds = dataSource;
        return ds instanceof BufferedPullDataSource
                && ((BufferedPullDataSource) ds).bindPartyAdmission(handle);
    }
}
