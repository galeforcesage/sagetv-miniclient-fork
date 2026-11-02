package sagex.miniclient.net;

public interface CancellableDataSource
{
    void cancel();

    boolean isCanceled();
}
