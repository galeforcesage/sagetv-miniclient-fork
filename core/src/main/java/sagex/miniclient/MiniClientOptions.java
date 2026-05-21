package sagex.miniclient;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import sagex.miniclient.prefs.PrefStore;

/**
 * Created by seans on 08/11/15.
 */
public interface MiniClientOptions {
    /**
     * Application level preferences
     * @return
     */
    PrefStore getPrefs();

    File getConfigDir();

    File getCacheDir();

    IBus getBus();

    /**
     * Allows the Hardware device to add/remove codec support based on the hardware supported codecs/containers
     *  @param videoCodecs
     * @param audioCodecs
     * @param pushFormats
     * @param pullFormats
     */
    void prepareCodecs(List<String> videoCodecs, List<String> audioCodecs, List<String> pushFormats, List<String> pullFormats);

    /**
     * Populate the per-codec audio passthrough (bitstream-to-sink) list.
     * Default no-op so non-Android platforms don't have to opt in. Android
     * impl reads tri-state {@code codec/audio_passthrough/<NAME>/support}
     * with auto = {@code AudioCapabilities.supportsEncoding(...)}.
     *
     * @param passthroughCodecs caller-supplied list to fill with SageTV
     *                          codec name tokens (cleared first).
     */
    default void prepareAudioPassthrough(List<String> passthroughCodecs)
    {
        passthroughCodecs.clear();
    }

    /**
     * Phase 3: per-player honest codec / container advertisement.
     *
     * <p>The merged lists fed to {@link #prepareCodecs} are still emitted
     * under the existing SageTV property names ({@code VIDEO_CODECS},
     * {@code AUDIO_CODECS}, {@code PUSH_AV_CONTAINERS},
     * {@code PULL_AV_CONTAINERS}) for legacy 9.2.x compatibility. NG
     * servers can additionally query per-player properties under the keys
     * populated here so the profile resolver can pick a codec/container
     * combination that matches whichever player will actually decode the
     * stream:
     * <ul>
     *   <li>{@code EXO_VIDEO_CODECS}, {@code EXO_AUDIO_CODECS},
     *       {@code EXO_PUSH_AV_CONTAINERS}, {@code EXO_PULL_AV_CONTAINERS}</li>
     *   <li>{@code IJK_VIDEO_CODECS}, {@code IJK_AUDIO_CODECS},
     *       {@code IJK_PUSH_AV_CONTAINERS}, {@code IJK_PULL_AV_CONTAINERS}</li>
     * </ul>
     *
     * <p>Default no-op so non-Android platforms don't have to opt in. The
     * caller-supplied map is cleared first.</p>
     */
    default void preparePerPlayerCapabilities(Map<String, List<String>> perPlayerCaps)
    {
        perPlayerCaps.clear();
    }

    public boolean isTouchUI();
    public boolean isTVUI();
    public boolean isDesktopUI();
    public boolean isUsingAdvancedAspectModes();
    public String getAdvancedApectModes();
    public String getDefaultAdvancedAspectMode();

    /**
     * Coarse device classification used by per-server LEGACY capability
     * tuning in {@code MiniClientConnection}. Lets the advertisement layer
     * pick different defaults for "cheap AndroidTV stick" vs. "premium STB"
     * vs. "phone" without baking a model list into the connection code.
     *
     * <p>Values: {@code SHIELD}, {@code BUDGET_ATV}, {@code PREMIUM_PHONE},
     * {@code FOLDABLE}, {@code MID_PHONE}, {@code BUDGET_PHONE},
     * {@code TABLET}, {@code DESKTOP}, {@code UNKNOWN}. Strings (not enums)
     * are returned so this contract can be extended in the Android module
     * without forcing every implementor to ship a new enum value.</p>
     *
     * <p>Default implementation returns {@code "UNKNOWN"}.</p>
     */
    default String getDeviceClass() { return "UNKNOWN"; }
}
