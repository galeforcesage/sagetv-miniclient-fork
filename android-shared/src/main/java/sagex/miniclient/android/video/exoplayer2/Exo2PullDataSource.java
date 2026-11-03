package sagex.miniclient.android.video.exoplayer2;

import android.net.Uri;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.android.exoplayer2.extractor.ts.SagePsExtractor;

import sagex.miniclient.net.BufferedPullDataSource;
import sagex.miniclient.net.HasClose;


public class Exo2PullDataSource implements DataSource, HasClose
{
    static final Logger log = LoggerFactory.getLogger(Exo2PullDataSource.class);
    private String host = null;
    BufferedPullDataSource dataSource = null;
    private long startPos;
    private Uri uri;
    private final LiveModeDetector liveMode;
    private long bytesSinceSizeRefresh = 0;  // for periodic SIZE re-query in live mode
    // Refresh server SIZE roughly every 32MB of read for live recordings.
    // At ~5 Mbps that's ~50 seconds — keeps the seek map current without
    // flooding the server with control commands.
    private static final long SIZE_REFRESH_INTERVAL_BYTES = 32L * 1024 * 1024;

    public Exo2PullDataSource(String host)
    {
        this(host, false);
    }

    public Exo2PullDataSource(String host, boolean timeshifted)
    {
        this.host = host;
        this.liveMode = new LiveModeDetector(timeshifted);
    }
    
    
    @Override
    public void addTransferListener(TransferListener transferListener)
    {
    
    }
    
    @Override
    public long open(DataSpec dataSpec) throws IOException
    {
        dataSource = new BufferedPullDataSource(host);
        this.uri = dataSpec.uri;
        long size = dataSource.open(dataSpec.uri.toString());
        this.startPos = dataSpec.position;
        liveMode.onOpen(size);
        log.debug("Open #{}: Offset: {}, Requested Length: {}, Size: {}",
                liveMode.openCount(), startPos, dataSpec.length, size);

        return LiveSizeStrategy.computeBytesRemaining(liveMode, size, dataSpec.position, dataSpec.length);
    }

    @Override
    public void close() throws IOException
    {
        if (dataSource != null)
        {
            dataSource.close();
        }
    }

    /**
     * Returns the current file size as known by the underlying data source
     * after the most recent open. Used by {@link SagePsExtractor.LiveSizeProvider}
     * to keep the seek map accurate for growing live recordings.
     *
     * <p>This MUST NOT call {@code querySize()} from threads other than the
     * Loader thread: the underlying TCP socket is shared with the Loader's
     * data {@code READ} commands and is not externally synchronized. A
     * concurrent {@code SIZE} command from the Playback thread (e.g. via
     * {@code LinearPsSeekMap.getSeekPoints()}) interleaves with an in-flight
     * {@code READ} reply, causing the protocol to desync — observed in
     * practice as a corrupt {@code SIZE} response that the seek map
     * interprets as a multi-GB file, sending the player to an absurd offset
     * and triggering {@code ERROR_CODE_IO_UNSPECIFIED}. Therefore this
     * method only returns the cached size; the size is refreshed by the
     * Loader thread itself in {@code read()} (see byte-interval refresh and
     * {@code SimplePullDataSource.fetch()} re-query when {@code position > size}).
     *
     * <p>Public so that {@code Exo2MediaPlayerImpl} can wire it into the
     * per-instance {@link SageExtractorsFactory} as a method reference.
     */
    public long queryCurrentSize() {
        BufferedPullDataSource ds = dataSource;
        if (ds == null) return -1;
        try {
            return ds.size();
        } catch (Throwable t) {
            return -1;
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws IOException
    {

        //log.debug("Byte buffer length: {}, Offset {}, readLength {}", buffer.length, offset, readLength);

        try
        {
            if (dataSource == null)
            {
                //log.debug("DATA SOURCE IS NULL");
                return 0;
            }
            int bytes = dataSource.read(startPos, buffer, offset, readLength);

            if (bytes == -1)
            {
                //log.debug("DATA SOURCE RETURNED -1");
                return -1;
            }
            startPos += bytes;

            // For actually-live recordings, periodically refresh the file size
            // from the server so the seek map (LinearPsSeekMap) can compute
            // correct byte positions for FF/REW jumps to "future" content.
            // This is done on the read thread so it doesn't race with reads.
            if (liveMode.isActuallyLive()) {
                bytesSinceSizeRefresh += bytes;
                if (bytesSinceSizeRefresh >= SIZE_REFRESH_INTERVAL_BYTES) {
                    bytesSinceSizeRefresh = 0;
                    try {
                        dataSource.querySize();
                    } catch (Throwable t) {
                        // best-effort
                    }
                }
            }
            return bytes;
        }
        catch(Exception ex)
        {
            log.debug("Data source read error: " + ex.getMessage());
            throw ex;
        }
    }

    @Override
    public Uri getUri() {
        return uri;
    }
    
    @Override
    public Map<String, List<String>> getResponseHeaders()
    {
        return new HashMap<String, List<String>>();
    }
}
