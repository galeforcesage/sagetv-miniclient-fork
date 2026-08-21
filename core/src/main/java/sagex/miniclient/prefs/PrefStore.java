package sagex.miniclient.prefs;

import java.util.Set;

/**
 * Simple Abstract way of handling Preferences
 */
public interface PrefStore
{
    String getString(String key);

    String getString(String key, String defValue);

    void setString(String key, String value);

    long getLong(String key);

    long getLong(String key, long defValue);

    void setLong(String key, long value);

    int getInt(String key);

    int getInt(String key, int defValue);

    void setInt(String key, int value);

    double getDouble(String key);

    double getDouble(String key, double defValue);

    void setDouble(String key, double value);

    boolean getBoolean(String key);

    boolean getBoolean(String key, boolean defValue);

    void setBoolean(String key, boolean value);

    /**
     * Read a tri-state preference. Returns {@link TriState#AUTO} when the key is
     * unset or holds an unrecognised value.
     */
    default TriState getTriState(String key)
    {
        return TriState.fromPrefValue(getString(key, null));
    }

    /** Persist a tri-state preference using its stable string form. */
    default void setTriState(String key, TriState value)
    {
        setString(key, (value == null ? TriState.AUTO : value).toPrefValue());
    }

    Set<Object> keys();

    void remove(String key);

    boolean contains(String key);

    boolean canSet(String key);

    /**
     * Gets the streaming mode that SageTV uses to send content to the client
     *
     * @return fixed, dynamic, pull
     */
    String getStreamingMode();

    /**
     * Preference on when to transcode content.
     *
     * Always - Will tell SageTV that there are no supported pull formats
     * When Needed - Will give SageTV a list of supported formats
     *
     * @return Returns the preference for transcoding
     */
    String getFixedEncodingPreference();

    /**
     * Get the container format to be used for Fixed Encoding
     *
     * @return Container format
     */
    String getFixedEncodingContainerFormat();

    String getFixedEncodingAudioCodec();

    String getFixedEncodingAudioChannels();

    int getFixedEncodingVideoBitrateKBPS();

    int getFixedEncodingAudioBitrateKBPS();

    String getFixedEncodingFPS();

    int getFixedEncodingKeyFrameInterval();

    boolean getFixedEncodingUseBFrames();

    String getFixedEncodingVideoResolution();

    String getFixedRemuxingPreference();

    String getFixedRemuxingFormat();

    interface Keys
    {
    
        String image_cache_size_mb = "image_cache_size_mb";
        String disk_image_cache_size_mb = "disk_image_cache_size_mb";

        String cache_images_on_disk = "cache_images_on_disk";
        String use_bitmap_images = "use_bitmap_images";

        /**
         * values: high, med, low
         */
        String local_fs_security = "local_fs_security";
        String mplayer_extra_video_codecs = "mplayer/extra_video_codecs";
        String mplayer_extra_audio_codecs = "mplayer/extra_audio_codecs";

        /**
         * When true, advertise a fixed legacy capability profile equivalent to
         * the Windows Placeshifter to the server, instead of the device's
         * auto-detected codec/container lists. Use this for SageTV 9.2.x
         * servers, which expect a Placeshifter-style client and can mis-route
         * playback when the client advertises modern Android-only codecs
         * (e.g. HEVC) or container combos the server's profile resolver
         * doesn't have a path for. Default false (NG / auto-negotiation).
         */
        String legacy_server_compat = "legacy_server_compat";

        /**
         * values: dynamic, fixed, pull
         */
        //String streaming_mode = "streaming_mode";
    
        /**
         * Preference on when to transcode.
         * Always - Will tell SageTV that there are no supported pull formats
         * When Needed - Will give SageTV a list of supported formats
         */
        //String fixed_encoding_preference = "fixed_encoding/preference";
        
        /**
         * The container format that will be used for fixed transcoding
         */
        //String fixed_encoding_format = "fixed_encoding/format";
    
        /**
         * The audio codec to be use for fixed transcoding
         */
        //String fixed_encoding_audio_code = "fixed_encoding/audio_codec";
    
        /**
         * The number of audio channels for fixed transcoding
         */
        //String fixed_encoding_audio_channels = "fixed_encoding/audio_channels";
        
        /**
         * 000 will be added to this value, so we only set, 64 to mean 64,000
         */
        //String fixed_encoding_video_bitrate_kbps = "fixed_encoding/video_bitrate_kbps";
        /**
         * 000 will be added to this value, so we only set, 64 to mean 64,000
         */
        //String fixed_encoding_audio_bitrate_kbps = "fixed_encoding/audio_bitrate_kbps";

        //String fixed_encoding_fps = "fixed_encoding/fps";
        //String fixed_encoding_key_frame_interval = "fixed_encoding/key_frame_interval";
        //String fixed_encoding_use_b_frames = "fixed_encoding/use_b_frames";
        //String fixed_encoding_video_resolution = "fixed_encoding/video_resolution";

        String video_buffer_size = "video_buffer_size";
        String audio_buffer_size = "audio_buffer_size";

        // auto connect settings
        String auto_connect_to_last_server = "auto_connect_to_last_server";
        String auto_connect_delay = "auto_connect_delay";
        String last_connected_server = "last_connected_server";

        /**
         * Log to file
         */
        String use_log_to_sdcard = "use_log_to_sdcard";

        /**
         * Use remote buttons change depending on the state of the player
         */
        //String use_stateful_remote = "use_stateful_remote";

        /**
         * values: debug, info, warn, error
         */
        String log_level = "log_level";

        /**
         * if true, then aspect ratio debugging is enabled.
         */
        String debug_ar = "debug_ar";


        /**
         * if enabled the long press select will bring up OSD
         */
        //String long_press_select_for_osd = "long_press_select_for_osd";

        /**
         * Debug Settings
         */
        String debug_log_unmapped_keypresses = "debug_log_unmapped_keypresses";

        /**
         * If set to true, then when the app pauses, it will tear down
         */
        String app_destroy_on_pause = "app_destroy_on_pause";

        /**
         * If set to true, then system sleep is disabled
         */
        String disable_sleep = "disable_sleep";

        /**
         * String: exoplayer and ijkplayer are the current possible values
         */
        String default_player = "default_player";

        /**
         * Boolean: Announce when Software decoder is being used
         */
        String announce_software_decoder = "announce_software_decoder";

        /**
         * Boolean: use native software decoders over ffmpeg software decoders
         */
        String prefer_android_software_decoders = "prefer_android_software_decoders";

        /**
         * Integer: Exoplayer ffmpeg extenstion setting.  (Off = 0, On = 1, Prefer = 2)
         *
         * <p>Legacy key. Phase 2 UX uses {@link #exoplayer_ffmpeg_extension_tri}
         * with {@link TriState} semantics; reads should go through
         * {@code AndroidPrefStore.getExoFfmpegExtensionMode()} which honours
         * the new key when present and falls back to this one.</p>
         */
        String exoplayer_ffmpeg_extension_setting = "exoplayer_ffmpeg_extension";

        /**
         * {@link TriState}: Exoplayer FFmpeg extension. AUTO = use if needed
         * (matches legacy "1"), ON = always prefer (legacy "2"), OFF = never
         * use (legacy "0").
         */
        String exoplayer_ffmpeg_extension_tri = "exoplayer_ffmpeg_extension_tri";

        /**
         * Boolean: if true, then only software decoders are used
         */
        String disable_hardware_decoders = "disable_hardware_decoders";

        /**
         * Boolean: if true, then only software decoders are used
         */
        String disable_audio_passthrough = "disable_audio_passthrough";

        /**
         * Boolean: default is true.  When enabled uses full screen resolution, when
         * disabled, it will report its screen size to be half native.
         */
        String use_native_resolution = "use_native_resolution";

        /**
         * Boolean: default is true.  When true, then when the SageTV exits, you go back to the
         * Android Launcher
         */
        String exit_to_home_screen = "exit_to_home_screen";

        /**
         * Boolean: default is false.  When true, then the Leanback Launcher will be used on a
         * Phone/Tablet
         */
        String use_tv_ui_on_tablet = "use_tv_ui_on_tablet";

        /**
         * How mas in MS the repeated keys will repeat during a key hold
         */
        String repeat_key_ms = "repeat_key_ms";

        /**
         * How long a key is held before repeats will happen
         */
        String repeat_key_delay_ms = "repeat_key_delay_ms";

        /**
         * Client ID
         */
        String client_id = "clientid";
        String use_opengl_ui = "use_opengl_ui";

        String exit_on_standby = "exit_on_standby";

        /**
         * used for testing only.  Do not enable this.
         */
        String use_httpls = "use_httpls";

        /**
         * Send DebugSageCommandEvent before sending SageCommand to SageTV
         */
        String debug_sage_commands = "debug_sage_commands";

        /**
         * NG download companion capability toggles. These directly control
         * which OFFLINE_* capability tokens are advertised during handshake.
         */
        String offline_cap_captions = "offline_cap_captions";
        String offline_cap_comskip = "offline_cap_comskip";
        String offline_cap_transcript = "offline_cap_transcript";

        /**
         * NG Trick-Play &amp; Position Reporting Contract capability toggles.
         * Each gates advertisement of one independent, opt-in capability during
         * the NG handshake. All default true. A legacy server never queries
         * these property names, so toggling them has zero effect on legacy
         * sessions; they exist so the capability can be disabled for debugging.
         *   TRICKPLAY_POSITION_V1 - reported position == on-screen frame media-time
         *   SEEK_EPOCH_V1         - epoch tags so stale samples can't cross a reposition
         *   DVR_WINDOW_V1         - seekable-window / live-edge clamping
         */
        String cap_trickplay_position_v1 = "cap_trickplay_position_v1";
        String cap_seek_epoch_v1 = "cap_seek_epoch_v1";
        String cap_dvr_window_v1 = "cap_dvr_window_v1";

        /**
         * Boolean: default false. When true, seek/scrub intents are clamped to
         * the server-advertised DVR window and forward skips saturate at the
         * live edge (DVR_WINDOW_V1). Exposed as a runtime toggle so the trick-play
         * A/B test can flip clamp OFF/ON without a rebuild.
         */
        String push_seek_dvr_clamp = "miniplayer/push_seek_dvr_clamp";

        /**
         * NG Server Video Enhancement (4K upscale) contract, Phase 1 capability
         * toggles + policy scalars. All NG-gated: a legacy server never queries
         * the matching GetProperty names, so these have zero effect on legacy
         * sessions and keep the wire byte-identical.
         *
         *   cap_display_sink_v1     - master toggle for advertising the physical
         *                             sink / refresh / HDR / local-enhancement
         *                             capability set (default true).
         *   display_sink_override_mode - DEPRECATED (server contract §7.3). The
         *                             sink is a pure MEASUREMENT and is now always
         *                             reported when measurable, so there is no
         *                             report/suppress gate. The user's Auto /
         *                             Always / Never enhancement preference moved
         *                             to {@link #quality_hint_mode}. Key retained
         *                             only so any persisted value is ignored
         *                             cleanly; not read anywhere.
         */
        String cap_display_sink_v1 = "cap_display_sink_v1";
        @Deprecated
        String display_sink_override_mode = "display_sink_override_mode";

        /**
         * NG 4K contract: local-enhancement policy advertised via
         * {@code LOCAL_ENHANCEMENT}. DEPRECATED as a user pref: the single
         * Never/Auto/Always control ({@link #quality_hint_mode}) now drives the
         * advertised preference (Always -> {@code pref=server}, else
         * {@code pref=auto}), and status is always {@code none} because this fork
         * runs no upscaler. Key retained only so any persisted value is ignored
         * cleanly; not read anywhere.
         */
        @Deprecated
        String local_enhancement_mode = "local_enhancement_mode";

        /**
         * NG 4K contract §2.5 + §7.3: coarse quality/bandwidth hint advertised
         * via {@code QUALITY_HINT}, and the home of the user's Auto / Always /
         * Never enhancement preference (the sink itself is a measurement and no
         * longer carries intent). Values: {@code auto} (server decides),
         * {@code quality} (prefer enhancement), {@code savings} (prefer bandwidth,
         * e.g. metered/battery). Advisory today. Default {@code auto}.
         */
        String quality_hint_mode = "quality_hint_mode";
    }
}
