package sagex.miniclient;

import java.io.Serializable;

import sagex.miniclient.prefs.PrefStore;
import sagex.miniclient.util.Utils;

/**
 * Created by seans on 20/09/15.
 */
public class ServerInfo implements Serializable, Comparable<ServerInfo>, Cloneable {
    public static final int LOCAL_SERVER = 1;
    public static final int DIRECT_CONNECT_SERVER = 2;
    public static final int LOCATABLE_SERVER = 3;

    /**
     * How this client should advertise its codec/container capabilities to
     * this particular server. Different SageTV server vintages parse the
     * advertisement differently:
     *
     * <ul>
     *   <li>{@link #AUTO} (default for new servers) — start in NG mode and
     *       fall back to LEGACY automatically if the server returns
     *       {@code IO_UNSPECIFIED} (or similar negotiation-mismatch error)
     *       on the first OPENURL. The fallback is sticky for that server.
     *   <li>{@link #LEGACY} — always advertise the fixed Placeshifter
     *       baseline. Use for SageTV 9.2.x and older servers whose profile
     *       resolver doesn't understand modern token names like "MP3" or
     *       "MPEG2-AUDIO".
     *   <li>{@link #NG} — always advertise the device's auto-detected
     *       capabilities. Use for SageTV-NG servers that negotiate richer
     *       codec sets (HEVC, AC-4, etc.).
     * </ul>
     */
    public enum LegacyMode {
        AUTO, LEGACY, NG;

        public static LegacyMode fromPrefValue(String s) {
            if (s == null) return AUTO;
            switch (s.toLowerCase()) {
                case "legacy": return LEGACY;
                case "ng":     return NG;
                case "auto":
                default:       return AUTO;
            }
        }

        public String toPrefValue() {
            return name().toLowerCase();
        }
    }

    /**
     * Per-server override of the global {@code streaming_mode} pref. The
     * global setting is a one-size-fits-all switch, but a mixed server
     * fleet often needs different choices: e.g. a stock SageTV 9.2.x
     * server defaults to 352x240 MPEG-4 when given an empty
     * {@code FIXED_PUSH_MEDIA_FORMAT}, so it benefits from being pinned
     * to FIXED, while an NG server is happier on PULL or DYNAMIC.
     *
     * <ul>
     *   <li>{@link #INHERIT} (default) — use whatever the global
     *       {@code streaming_mode} pref says.
     *   <li>{@link #AUTOMATIC} — pull if port 7818 reachable, else dynamic.
     *   <li>{@link #PULL} — force pull (zero server CPU).
     *   <li>{@link #DYNAMIC} — force dynamic push (server picks transcode
     *       at OPENURL time).
     *   <li>{@link #FIXED} — force fixed push using the client's
     *       Fixed Encoding / Fixed Remuxing settings.
     * </ul>
     *
     * When set to anything other than {@link #INHERIT} this value wins
     * over the global pref AND over the "pull is reachable, always pick
     * pull" auto-override in {@code MiniClientConnection}.
     */
    public enum StreamingModeOverride {
        INHERIT, AUTOMATIC, PULL, DYNAMIC, FIXED;

        public static StreamingModeOverride fromPrefValue(String s) {
            if (s == null) return INHERIT;
            switch (s.toLowerCase()) {
                case "automatic": return AUTOMATIC;
                case "pull":      return PULL;
                case "dynamic":   return DYNAMIC;
                case "fixed":     return FIXED;
                case "inherit":
                default:          return INHERIT;
            }
        }

        public String toPrefValue() {
            return name().toLowerCase();
        }
    }

    public String address;
    public int port = 31099;
    public String name;
    public String locatorID;
    public int serverType;
    public long lastConnectTime;
    public String authBlock;
    public boolean forceLocator = false;

    public String macAddress = null;
    public Boolean use_stateful_remote=null;

    /**
     * Per-server legacy/NG capability advertisement mode. See {@link LegacyMode}.
     * Defaults to {@link LegacyMode#AUTO}; the AUTO→LEGACY auto-flip on
     * IO_UNSPECIFIED is wired in {@code MiniClientConnection}.
     */
    public LegacyMode legacyMode = LegacyMode.AUTO;

    /**
     * Per-server streaming mode override. See {@link StreamingModeOverride}.
     * Defaults to {@link StreamingModeOverride#INHERIT} so existing
     * single-server users see no behavior change.
     */
    public StreamingModeOverride streamingModeOverride = StreamingModeOverride.INHERIT;

    public ServerInfo() {
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("ServerInfo{");
        sb.append("address='").append(address).append('\'');
        sb.append(", port=").append(port);
        sb.append(", name='").append(name).append('\'');
        sb.append(", locatorID='").append(locatorID).append('\'');
        sb.append(", macAddress='").append(macAddress).append('\'');
        sb.append(", use_stateful_remote='").append(use_stateful_remote).append('\'');
        sb.append('}');
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ServerInfo that = (ServerInfo) o;

        // host:port and client makes a connection unique
        // this allows us to rename a connection that is discovered
        // and allows us to copy a connection to a new name, since clientid will change

        if (port != that.port) return false;
        if (address==null && that.address!=null) return false;
        if (address!=null && !address.equals(that.address)) return false;

        // for mac address null and empty are the same
        if (macAddress == null || macAddress.trim().length() == 0) {
            if (that.macAddress == null || that.macAddress.trim().length() == 0) {
                return true;
            }
        }
        if (that.macAddress == null || that.macAddress.trim().length() == 0) {
            if (macAddress == null || macAddress.trim().length() == 0) {
                return true;
            }
        }
        if (macAddress != null) {
            return macAddress.equals(that.macAddress);
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = address.hashCode();
        result = 31 * result + port;
        result = 31 * result + (macAddress != null ? macAddress.hashCode() : 0);
        return result;
    }

    @Override
    public int compareTo(ServerInfo o) {
        if (locatorID != null) {
            return locatorID.compareTo(o.locatorID);
        }

        if (address == null && o.address == null) return 0;
        if (o.address == null) return -1;
        if (address == null) return 1;
        int compare = address.compareTo(o.address);
        if (compare == 0) {
            if (port < o.port) return -1;
            if (port > o.port) return 1;
        }
        return compare;
    }

    public void setAuthBlock(String authBlock) {
        this.authBlock = authBlock;
    }

    public void save(PrefStore store) {
        if (name == null) {
            System.out.println("Can't save ServerInfo without a name: " + this);
        }
        store.setLong("servers/" + name + "/type", serverType);
        if (Utils.isGUID(address)) {
            locatorID = address;
            address = null;
        }
        if (address != null) {
            store.setString("servers/" + name + "/address", address);
            store.setInt("servers/" + name + "/port", port);
        }
        if (locatorID != null)
            store.setString("servers/" + name + "/locator_id", locatorID);
        store.setLong("servers/" + name + "/last_connect_time", lastConnectTime);
        if (authBlock != null)
            store.setString("servers/" + name + "/auth_block", authBlock);
        if (macAddress!=null) {
            store.setString("servers/" + name + "/mac", macAddress);
        }
        if (use_stateful_remote!=null) {
            store.setBoolean("servers/" + name + "/use_stateful_remote", use_stateful_remote);
        }
        // Always persist legacy mode so explicit user overrides survive even
        // when the value is the AUTO default (matches what UI expects).
        store.setString("servers/" + name + "/legacy_mode", legacyMode.toPrefValue());
        // Same rationale for streaming mode override.
        store.setString("servers/" + name + "/streaming_mode", streamingModeOverride.toPrefValue());
    }

    public void load(String name, PrefStore store) {
        this.name = name;
        serverType = (int) store.getLong("servers/" + name + "/type", 0);
        address = store.getString("servers/" + name + "/address", "");
        locatorID = store.getString("servers/" + name + "/locator_id", "");
        lastConnectTime = store.getLong("servers/" + name + "/last_connect_time", 0);
        authBlock = store.getString("servers/" + name + "/auth_block", "");
        port = store.getInt("servers/" + name + "/port", 31099);
        macAddress = store.getString("servers/" + name + "/mac", "");
        if (store.contains("servers/" + name + "/use_stateful_remote")) {
            use_stateful_remote = store.getBoolean("servers/" + name + "/use_stateful_remote", true);
        }
        legacyMode = LegacyMode.fromPrefValue(
                store.getString("servers/" + name + "/legacy_mode", LegacyMode.AUTO.toPrefValue()));
        streamingModeOverride = StreamingModeOverride.fromPrefValue(
                store.getString("servers/" + name + "/streaming_mode",
                        StreamingModeOverride.INHERIT.toPrefValue()));
    }

    public boolean isLocatorOnly() {
        return (!Utils.isEmpty(locatorID) && Utils.isEmpty(address))
                || (Utils.isEmpty(locatorID) && !Utils.isEmpty(address) && Utils.isGUID(address));
    }

    public static String getPrefKey(String name, String id) {
        return "servers/" + name + "/" + id;
    }

    @Override
    public ServerInfo clone() {
        try {
            return (ServerInfo) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }
}
