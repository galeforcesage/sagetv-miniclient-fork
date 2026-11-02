package sagex.miniclient.net;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

public final class PullServerProbe {
    public enum Result {
        SUPPORTED,
        UNSUPPORTED,
        INDETERMINATE
    }

    interface Connector {
        void connect(String host, int port, int timeoutMs) throws IOException;
    }

    private static final Connector DEFAULT_CONNECTOR = new Connector() {
        @Override
        public void connect(String host, int port, int timeoutMs) throws IOException {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(host, port), timeoutMs);
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    };

    private PullServerProbe() {
    }

    public static Result probe(String host) {
        return probe(host, DEFAULT_CONNECTOR, 2, 8000);
    }

    static Result probe(String host, Connector connector, int attempts, int timeoutMs) {
        boolean transientFailure = false;
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                connector.connect(host, 7818, timeoutMs);
                return Result.SUPPORTED;
            } catch (ConnectException e) {
                return Result.UNSUPPORTED;
            } catch (SocketTimeoutException e) {
                transientFailure = true;
            } catch (IOException e) {
                transientFailure = true;
            }
        }
        return transientFailure ? Result.INDETERMINATE : Result.UNSUPPORTED;
    }
}
