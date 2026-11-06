package sagex.miniclient.android.party;

import android.os.SystemClock;
import android.os.Build;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import sagex.miniclient.MiniClient;
import sagex.miniclient.MiniPlayerPlugin;
import sagex.miniclient.android.video.BaseMediaPlayerImpl;
import sagex.miniclient.party.PartyPlaybackAdapter;

public final class AndroidPartyPlaybackAdapter implements PartyPlaybackAdapter
{
    private final MiniClient client;
    private final Context context;
    private volatile LifecycleListener listener;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private String lastNetworkSignature;
    private boolean networkSnapshotInitialized;

    public AndroidPartyPlaybackAdapter(MiniClient client, Context context)
    {
        this.client = client;
        this.context = context.getApplicationContext();
    }

    @Override
    public boolean supportsRequiredGuarantees()
    {
        return true;
    }

    @Override
    public long elapsedRealtimeMs()
    {
        return SystemClock.elapsedRealtime();
    }

    @Override
    public Observation observe()
    {
        BaseMediaPlayerImpl<?, ?> player = player();
        return player == null
                ? new Observation(elapsedRealtimeMs(), 0, -1, 0,
                        PlaybackState.UNAVAILABLE, false, false, 1000)
                : player.getPartyObservation();
    }

    @Override
    public void setPartyHold(boolean hold)
    {
        BaseMediaPlayerImpl<?, ?> player = player();
        if (player != null) player.setPartyHold(hold);
    }

    @Override
    public void execute(ScheduledCommand command, ExecutionCallback callback)
    {
        BaseMediaPlayerImpl<?, ?> player = player();
        if (player == null)
        {
            if (callback != null) callback.onComplete(false);
            return;
        }
        player.executePartyCommand(command, callback);
    }

    @Override
    public void cancelScheduledAction()
    {
        BaseMediaPlayerImpl<?, ?> player = player();
        if (player != null) player.cancelPartyScheduledAction();
    }

    @Override
    public void reconnectControlTransport()
    {
        if (client.getCurrentConnection() != null)
        {
            client.getCurrentConnection().requestPartyControlReconnect();
        }
    }

    @Override
    public void localStop()
    {
        BaseMediaPlayerImpl<?, ?> player = player();
        if (player != null) player.localPartyStop();
    }

    @Override
    public synchronized void start(LifecycleListener listener)
    {
        this.listener = listener;
        connectivityManager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback()
        {
            @Override
            public void onAvailable(Network network)
            {
                notifyNetworkBoundary(NetworkBoundary.AVAILABLE, network, false);
            }

            @Override
            public void onLost(Network network)
            {
                notifyNetworkBoundary(NetworkBoundary.LOST, network, true);
            }

            @Override
            public void onCapabilitiesChanged(Network network,
                                              NetworkCapabilities capabilities)
            {
                notifyNetworkBoundary(NetworkBoundary.CAPABILITIES_CHANGED,
                        network, false);
            }

            @Override
            public void onLinkPropertiesChanged(Network network,
                                                LinkProperties linkProperties)
            {
                notifyNetworkBoundary(NetworkBoundary.LINK_PROPERTIES_CHANGED,
                        network, false);
            }
        };
        snapshotCurrentNetwork(null, false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
        {
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        }
        else
        {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            connectivityManager.registerNetworkCallback(request, networkCallback);
        }
    }

    @Override
    public synchronized void stop()
    {
        if (connectivityManager != null && networkCallback != null)
        {
            try
            {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            }
            catch (IllegalArgumentException ignored)
            {
            }
        }
        networkCallback = null;
        connectivityManager = null;
        lastNetworkSignature = null;
        networkSnapshotInitialized = false;
        listener = null;
    }

    public void foregroundConfidenceLost()
    {
        LifecycleListener current = listener;
        if (current != null) current.onForegroundConfidenceLost();
    }

    private synchronized void notifyNetworkBoundary(NetworkBoundary hint,
                                                    Network callbackNetwork,
                                                    boolean lost)
    {
        String newSignature = snapshotCurrentNetwork(callbackNetwork, lost);
        NetworkBoundary boundary = classifyNetworkBoundary(
                lastNetworkSignature, newSignature, hint);
        if (boundary == null) return;
        lastNetworkSignature = newSignature;
        LifecycleListener current = listener;
        if (current != null) current.onNetworkBoundary(boundary);
    }

    private String snapshotCurrentNetwork(Network callbackNetwork, boolean lost)
    {
        Network network = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? connectivityManager.getActiveNetwork() : callbackNetwork;
        if (lost && callbackNetwork != null && callbackNetwork.equals(network))
        {
            network = null;
        }
        NetworkCapabilities capabilities = network == null ? null
                : connectivityManager.getNetworkCapabilities(network);
        LinkProperties linkProperties = network == null ? null
                : connectivityManager.getLinkProperties(network);
        String signature = network == null ? "" : network.toString() + ":"
                + networkTransportSignature(capabilities) + ":"
                + (linkProperties == null ? "" : linkProperties.toString());
        if (!networkSnapshotInitialized)
        {
            networkSnapshotInitialized = true;
            lastNetworkSignature = signature;
        }
        return signature;
    }

    static NetworkBoundary classifyNetworkBoundary(String previous,
                                                   String current,
                                                   NetworkBoundary hint)
    {
        String before = previous == null ? "" : previous;
        String after = current == null ? "" : current;
        if (before.equals(after)) return null;
        if (before.isEmpty()) return NetworkBoundary.AVAILABLE;
        if (after.isEmpty()) return NetworkBoundary.LOST;
        return hint == NetworkBoundary.LINK_PROPERTIES_CHANGED
                ? NetworkBoundary.LINK_PROPERTIES_CHANGED
                : NetworkBoundary.CAPABILITIES_CHANGED;
    }

    public void playbackStateChanged()
    {
        LifecycleListener current = listener;
        if (current != null) current.onPlaybackStateChanged();
    }

    private static String networkTransportSignature(NetworkCapabilities capabilities)
    {
        if (capabilities == null) return "";
        StringBuilder value = new StringBuilder();
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) value.append("vpn,");
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) value.append("wifi,");
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) value.append("cell,");
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) value.append("ethernet,");
        value.append(capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? "validated" : "unvalidated");
        return value.toString();
    }

    private BaseMediaPlayerImpl<?, ?> player()
    {
        MiniPlayerPlugin current;
        try
        {
            current = client.getPlayer();
        }
        catch (Throwable t)
        {
            return null;
        }
        return current instanceof BaseMediaPlayerImpl
                ? (BaseMediaPlayerImpl<?, ?>) current : null;
    }
}
