package sagex.miniclient.net;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Created by seans on 27/12/15.
 */
public class BufferedPullDataSourceTest {
    static final byte data[] = SimplePullDataSourceTest.loadData(100).getBytes();

    private static final class CountingBufferedPullDataSource extends BufferedPullDataSource {
        private final List<Long> fillPositions = new ArrayList<Long>();

        @Override
        int fillBuffer(long position) throws IOException {
            fillPositions.add(position);
            return super.fillBuffer(position);
        }
    }

    private CountingBufferedPullDataSource createDataSource(int size) {
        CountingBufferedPullDataSource pds = new CountingBufferedPullDataSource();
        pds.remoteReader = new DataInputStream(new MockInputStream(size));
        pds.remoteWriter = new ByteArrayOutputStream();
        pds._buffer = new byte[BufferedPullDataSource.MAX_BUFFER];
        pds.opened = true;
        pds.size = size;
        return pds;
    }

    @Test
    public void testRead() throws Exception {
        CountingBufferedPullDataSource pds =
                createDataSource(BufferedPullDataSource.MAX_BUFFER * 5);

        byte buffer[] = new byte[BufferedPullDataSource.MAX_BUFFER];

        // read a single byte, which should force the buffer to fill
        int size = 1;
        int read = pds.read(0, buffer, 0, 1);
        assertEquals(size, read);
        assertEquals(new String(data, 0, size), new String(buffer, 0, size));
        assertEquals(1, pds.fillPositions.size());
        assertEquals(Long.valueOf(0), pds.fillPositions.get(0));

        // now read a bunch of other single byte reads, which should NOT force a buffer re-fill
        read = pds.read(0, buffer, 1, 1);
        assertEquals(size, read);
        assertEquals(new String(data, 1, size), new String(buffer, 1, size));
        assertEquals(1, pds.fillPositions.size());

        // now read some data that is outside the buffer range, and it should force a re-read
        read = pds.read(BufferedPullDataSource.MAX_BUFFER * 2, buffer, 0, 1);
        assertEquals(size, read);
        //assertEquals(((BufferedPullDataSource.MAX_BUFFER*2)%10)+'0', buffer[0]);
        assertEquals(2, pds.fillPositions.size());
        assertEquals(Long.valueOf(BufferedPullDataSource.MAX_BUFFER * 2L), pds.fillPositions.get(1));
    }

    @Test
    public void testLargeBufferRead() throws Exception {
        CountingBufferedPullDataSource pds =
                createDataSource(BufferedPullDataSource.MAX_BUFFER * 5);

        byte buffer[] = new byte[BufferedPullDataSource.MAX_BUFFER * 2];

        // read a large buffer, which should force the buffer to have to fill twice
        int read = pds.read(0, buffer, 0, buffer.length);
        assertEquals(buffer.length, read);
        assertEquals(2, pds.fillPositions.size());
        assertEquals(Long.valueOf(0), pds.fillPositions.get(0));
        assertEquals(Long.valueOf(BufferedPullDataSource.MAX_BUFFER), pds.fillPositions.get(1));
    }


    @Test
    public void testLinearRead() throws Exception {
        CountingBufferedPullDataSource pds =
                createDataSource(BufferedPullDataSource.MAX_BUFFER * 3);

        byte buffer[] = new byte[1];

        int reads = 0;
        while (true) {
            int val = pds.read(reads++, buffer, 0, 1);
            if (val == -1) break;
        }

        // 3 fully buffer reads + another read that return a -1
        assertEquals(4, pds.fillPositions.size());

        // our total reads should be 3 full buffers + 1 extra read for the -1 return
        assertEquals(BufferedPullDataSource.MAX_BUFFER * 3 + 1, reads);
    }

    @Test
    public void testConfiguredBufferSize() throws Exception {
        int configuredSize = 1024 * 1024;
        BufferedPullDataSource pds = new BufferedPullDataSource(null, configuredSize);
        pds.remoteReader = new DataInputStream(new MockInputStream(configuredSize * 2));
        pds.remoteWriter = new ByteArrayOutputStream();
        pds._buffer = new byte[configuredSize];
        pds.opened = true;
        pds.size = configuredSize * 2L;

        byte[] buffer = new byte[configuredSize + 1];
        assertEquals(buffer.length, pds.read(0, buffer, 0, buffer.length));
    }

}