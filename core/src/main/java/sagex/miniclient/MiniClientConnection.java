/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sagex.miniclient;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import sagex.miniclient.events.ConnectionLost;
import sagex.miniclient.events.DownloadRequestEvent;
import sagex.miniclient.events.DownloadTransferControlEvent;
import sagex.miniclient.events.DownloadTransferSessionErrorEvent;
import sagex.miniclient.events.OfflineGuideSnapshotEvent;
import sagex.miniclient.events.OfflineScheduleSnapshotEvent;
import sagex.miniclient.logging.ILogger;
import sagex.miniclient.media.Container;
import sagex.miniclient.media.VideoCodec;
import sagex.miniclient.prefs.PrefStore;
import sagex.miniclient.uibridge.Dimension;
import sagex.miniclient.uibridge.MouseEvent;
import sagex.miniclient.uibridge.UIRenderer;
import sagex.miniclient.util.Utils;
import sagex.miniclient.media.AudioCodec;
import sagex.miniclient.ngcontext.NgPlaybackContextStore;


public class MiniClientConnection implements SageTVInputCallback
{

    /*Containers*/
    public static final String MPEG2_PS = "MPEG2-PS";
    public static final String MPEG1_PS = "MPEG1-PS";
    public static final String MPEG2_TS = "MPEG2-TS";
    public static final String QUICKTIME = "Quicktime";
    public static final String FLASHVIDEO = "FLASHVIDEO";
    public static final String AVI = "AVI";
    public static final String MATROSKA = "MATROSKA";
    public static final String OGG = "Ogg";
    public static final String MP3 = "MP3";
    public static final String AAC = "AAC";
    public static final String ASF = "ASF";
    public static final String FLAC = "FLAC";
    public static final String AC3 = "AC3";
    public static final String WAV = "WAV";

    public static final String DEFAULT_PULL_FORMATS = AVI + "," + FLASHVIDEO + "," + QUICKTIME + "," + OGG + "," + MP3 + "," + AAC + "," + ASF + "," + FLAC + "," + MATROSKA + "," + WAV + "," + AC3;
    public static final String DEFAULT_PUSH_FORMATS =  MPEG2_PS + "," + MPEG1_PS + "," + MPEG2_TS;

    //public static final String DEFAULT_PULL_FORMATS = "AVI,FLASHVIDEO,Quicktime,Ogg,MP3,AAC,WMV,ASF,FLAC,MATROSKA,WAV,AC3";
    //public static final String DEFAULT_PUSH_FORMATS =  "MPEG2-PS,MPEG2-TS,MPEG1-PS";

    /*Video Codecs with Mime Types*/
    /*
    public static final String MPEG1_VIDEO = "MPEG1-VIDEO";
    public static final String MPEG1_VIDEO_MIME_TYPES = "video/mpeg";
    public static final String MPEG2_VIDEO_HL = "MPEG2-VIDEO@HL";
    public static final String MPEG2_VIDEO_HL_MIME_TYPES = "video/mpeg2";
    public static final String MPEG2_VIDEO = "MPEG2-VIDEO";
    public static final String MPEG2_VIDEO_MIME_TYPES = "video/mpeg2";
    public static final String H263_VIDEO = "H.263";
    public static final String H263_VIDEO_MIME_TYPES = "video/3gpp";
    public static final String MPEG4_VIDEO = "MPEG4-VIDEO";
    public static final String MPEG4_VIDEO_MIME_TYPES = "video/mp4v-es";
    public static final String MSMPEG4_VIDEO = "MSMPEG4-VIDEO";
    public static final String MSMPEG4_VIDEO_MIME_TYPES = "video/mp4v-es";
    public static final String H264_VIDEO = "H.264";
    public static final String H264_VIDEO_MIME_TYPES = "video/avc";
    public static final String VC1_VIDEO = "VC1";
    public static final String VC1_VIDEO_MIME_TYPES = "video/x-ms-wmv,video/wvc1";
    public static final String HEVC_VIDEO = "HEVC";
    public static final String HEVC_VIDEO_MIME_TYPES = "video/hevc";
    public static final String MJPEG_VIDEO = "MJPEG";
    public static final String MJPEG_VIDEO_MIME_TYPES = "video/mjpeg";
    public static final String VP8_VIDEO = "VP8";
    public static final String VP8_VIDEO_MIME_TYPES = "video/x-vnd.on2.vp8";
    public static final String VP9_VIDEO = "VP9";
    public static final String VP9_VIDEO_MIME_TYPES = "video/x-vnd.on2.vp9";

    // TODO: Not sure about supporting this.  Assume this is SageTV format
    public static final String MPEG4X = "MPEG4X"; // this is our private stream, format we put inside, MPEG2 PS for MPEG4/Divx, video on Windows
    */


    /*
    public static final String WMV9 = "WMV9";
    public static final String WMV8 = "WMV8";
    public static final String WMV7 = "WMV7";
    */


    /* Full list of possible video codecs that the client potentially knows about.  Additional support checks happen later */
    /*
    public static final String DEFAULT_VIDEO_CODECS = MPEG2_VIDEO + ',' + MPEG1_VIDEO + ',' + MPEG4_VIDEO + "," + MSMPEG4_VIDEO + "," + H263_VIDEO + "," + H264_VIDEO + "," + HEVC_VIDEO + "," + VP8_VIDEO + "," + VP9_VIDEO;
    */

    /*
    public static final String MPEG1_AUDIO = "MPEG1";
    public static final String MPEG1_AUDIO_MIME_TYPES = "";
    public static final String MPG1L2_AUDIO = "MP2,MPG1L2";
    public static final String MPG1L2_AUDIO_MIME_TYPES = "audio/mpeg-L2";
    //public static final String MP2_AUDIO = "MP2";
    //public static final String MP2_AUDIO_MIME_TYPES = "audio/mpeg-L2";
    public static final String MPG1L3_AUDIO = "MP3,MPG1L3";
    public static final String MPG1L3_AUDIO_MIME_TYPES = "audio/mpeg";
    //public static final String MP3_AUDIO = "MP3";
    //public static final String MP3_AUDIO_MIME_TYPES = "audio/mpeg";
    public static final String WMA_AUDIO = "WMA,WMA7,WMA8,WMAPRO,WMA9Lossless";
    public static final String WMA_AUDIO_MIME_TYPES = "audio/x-ms-wma";
    //public static final String WMA7_AUDIO = "WMA7";
    //public static final String WMA7_AUDIO_MIME_TYPES = "audio/x-ms-wma";
    //public static final String WMA8_AUDIO = "WMA8";
    //public static final String WMA8_AUDIO_MIME_TYPES = "audio/x-ms-wma";
    //public static final String WMAPRO_AUDIO = "WMAPRO";
    //public static final String WMAPRO_AUDIO_MIME_TYPE = "audio/x-ms-wma";
    //public static final String WMA9LOSSLESS_AUDIO = "WMA9Lossless";
    //public static final String WMA9LOSSLESS_AUDIO_MIME_TYPE = "audio/x-ms-wma";
    public static final String VORBIS_AUDIO = "Vorbis";
    public static final String VORBIS_AUDIO_MIME_TYPES = "audio/vorbis";
    public static final String AAC_AUDIO = "AAC,AAC-HE";
    public static final String AAC_AUDIO_MIME_TYPES = "audio/mp4a-latm";
    //public static final String AACHE_AUDIO = "AAC-HE";
    //public static final String AACHE_AUDIO_MIME_TYPES = "audio/mp4a-latm";
    public static final String FLAC_AUDIO = "FLAC";
    public static final String FLAC_AUDIO_MIME_TYPES = "";
    public static final String ALAC_AUDIO = "ALAC";
    public static final String ALAC_AUDIO_MIME_TYPES = "";
    public static final String PCM_AUDIO = "PCM,PCM_S16LE";
    public static final String PCM_AUDIO_MIME_TYPES = "audio/raw";
    //public static final String PCMS16LE_AUDIO = "PCM_S16LE";
    //public static final String PCMS16LE_AUDIO_MIME_TYPES = "audio/raw";
    public static final String DTS_AUDIO = "DTS,DCA";
    public static final String DTS_AUDIO_MIME_TYPES = "";
    //public static final String DCA_AUDIO = "DCA";
    //public static final String DCA_AUDIO_MIME_TYPES = "";
    public static final String DTSHD_AUDIO = "DTS-HD";
    public static final String DTSHD_AUDIO_MIME_TYPES = "";
    public static final String DTSMA_AUDIO = "DTS-MA";
    public static final String DTSMA_AUDIO_MIME_TYPES = "";
    public static final String AC3_AUDIO = "AC3";
    public static final String AC3_AUDIO_MIME_TYPES = "";
    public static final String AC4_AUDIO = "AC4";
    public static final String AC4_AUDIO_MIME_TYPES = "";
    public static final String EAC3_AUDIO = "EAC3,EC-3";
    public static final String EAC3_AUDIO_MIME_TYPES = "";
    //public static final String EC3_AUDIO = "EC-3";
    //public static final String EC3_AUDIO_MIME_TYPES = "";
    public static final String DOLBYTRUEHD_AUDIO = "DOLBYTRUEHD";
    public static final String DOLBYTRUEHD_AUDIO_MIME_TYPES = "";
    public static final String OPUS_AUDIO = "OPUS";
    public static final String OPUS_AUDIO_MIME_TYPES = "";

    public static final String DEFAULT_AUDIO_CODECS = MPG1L2_AUDIO + "," + MPG1L3_AUDIO + "," + AC3_AUDIO + "," + AC4_AUDIO
            + "," + AAC_AUDIO  + "," + WMA_AUDIO + "," + FLAC_AUDIO + "," + VORBIS_AUDIO + "," + PCM_AUDIO + "," + OPUS_AUDIO
            + "," + DTS_AUDIO  + "," + DTSHD_AUDIO + "," + DTSMA_AUDIO + "," + EAC3_AUDIO  + "," + DOLBYTRUEHD_AUDIO;
    */

    public static final String SMIL = "SMIL"; // for SMIL-XML files which represent sequences of content
    public static final String VP6F = "VP6F";
    public static final String JPEG = "JPEG";

    // -------------------------------------------------------------------------
    // SageTV-NG protocol version
    //
    // Independent of FIRMWARE_VERSION (which stays "9.0.0" for plugin back-compat
    // with anything that reads Global.GetMiniclientFirmwareVersion()). NG servers
    // read SAGETV_NG_VERSION to gate modern-profile selection (e.g. android_modern
    // with HEVC + AC-4 + audioonly transcode); legacy 9.x servers ignore it.
    //
    // BACK-OUT PATH (if NG is upstreamed as e.g. "SageTV 10.0.0"):
    //   1. Delete SAGETV_NG_VERSION constant + its property handler below.
    //   2. Bump FIRMWARE_VERSION handler from "9.0.0" → "10.0.0".
    //   NG's autoDetectProfile falls back to parsing firmwareVersion when the
    //   NG version property is absent, so this is a one-line client cutover.
    // -------------------------------------------------------------------------
    public static final String SAGETV_NG_VERSION = "1.0.1";
    public static final String CAP_PROFILE_ANDROID_MODERN = "android_modern";
    public static final String CAP_PROFILE_ANDROID_LEGACY = "android_legacy";
    public static final String GIF = "GIF";
    public static final String PNG = "PNG";
    public static final String BMP = "BMP";


    public static final int DRAWING_CMD_TYPE = 16;
    public static final int GET_PROPERTY_CMD_TYPE = 0;
    public static final int SET_PROPERTY_CMD_TYPE = 1;
    public static final int FS_CMD_TYPE = 2;
    public static final int IR_EVENT_REPLY_TYPE = 128;
    public static final int KB_EVENT_REPLY_TYPE = 129;
    public static final int MPRESS_EVENT_REPLY_TYPE = 130;
    public static final int MRELEASE_EVENT_REPLY_TYPE = 131;
    public static final int MCLICK_EVENT_REPLY_TYPE = 132;
    public static final int MMOVE_EVENT_REPLY_TYPE = 133;
    public static final int MDRAG_EVENT_REPLY_TYPE = 134;
    public static final int MWHEEL_EVENT_REPLY_TYPE = 135;
    public static final int SAGECOMMAND_EVENT_REPLY_TYPE = 136;
    public static final int UI_RESIZE_EVENT_REPLY_TYPE = 192;
    public static final int UI_REPAINT_EVENT_REPLY_TYPE = 193;
    public static final int MEDIA_PLAYER_UPDATE_EVENT_REPLY_TYPE = 201;
    public static final int REMOTE_FS_HOTPLUG_INSERT_EVENT_REPLY_TYPE = 202;
    public static final int REMOTE_FS_HOTPLUG_REMOVE_EVENT_REPLY_TYPE = 203;
    public static final int OUTPUT_MODES_CHANGED_REPLY_TYPE = 224;
    public static final int SUBTITLE_UPDATE_REPLY_TYPE = 225;
    public static final int IMAGE_UNLOAD_REPLY_TYPE = 226;
    public static final int OFFLINE_CACHE_CHANGE_REPLY_TYPE = 227;
    /**
     * Client-initiated event posted on the event channel asking the SageTV
     * server to re-issue a fresh TRANSFER_SESSION_ACK (download_url +
     * session_token) for a download whose token has expired. Payload is a
     * UTF-8 JSON document of the shape:
     *
     * <pre>
     * {"mediaFileID":"&lt;id&gt;","reason":"&lt;code&gt;","correlationId":"&lt;uuid&gt;"}
     * </pre>
     *
     * The wire framing is the same canonical 16-byte header used by every
     * other client→server event (1B opcode + 1B pad + 2B body length + 4B
     * timestamp + 4B replyCount + 4B pad), followed by the body bytes
     * (encrypted with {@code evtEncryptCipher} when {@code encryptEvents}
     * is true). The server responds asynchronously by pushing a fresh
     * CMD_DOWNLOAD_REQUEST back through the event channel.
     */
    public static final int DOWNLOAD_REFRESH_REQUEST_REPLY_TYPE = 228;
    // Tells the GFX channel to force the media channel to reconnect
    public static final int GFXCMD_MEDIA_RECONNECT = 131;
    public static final int FS_RV_SUCCESS = 0;
    public static final int FS_RV_PATH_EXISTS = 1;
    public static final int FS_RV_NO_PERMISSIONS = 2;
    public static final int FS_RV_PATH_DOES_NOT_EXIST = 3;
    public static final int FS_RV_NO_SPACE_ON_DISK = 4;
    public static final int FS_RV_ERROR_UNKNOWN = 5;
    public static final int FSCMD_CREATE_DIRECTORY = 64;
    public static final int FS_PATH_HIDDEN = 0x01;
    public static final int FS_PATH_DIRECTORY = 0x02;
    public static final int FS_PATH_FILE = 0x04;
    public static final int FSCMD_GET_PATH_ATTRIBUTES = 65;
    public static final int FSCMD_GET_FILE_SIZE = 66;
    public static final int FSCMD_GET_PATH_MODIFIED_TIME = 67;
    // pathlen, path
    public static final int FSCMD_DIR_LIST = 68;
    public static final int FSCMD_LIST_ROOTS = 69;
    public static final int FSCMD_DOWNLOAD_FILE = 70;
    public static final int FSCMD_UPLOAD_FILE = 71;
    // pathlen, path
    public static final int FSCMD_DELETE_FILE = 72;

    public ILogger log;
    // private static final int PUSH_BUFFER_LIMIT = 32 * 1024;
    // pathlen, path
    // 64-bit return value
    public static boolean detailedBufferStats = false;
    // pathlen, path
    // 64-bit return value
    public static String CONNECT_FAILURE_GENERAL_INTERNET = "The SageTV Placeshifter is having trouble connecting to the Internet. "
            + "Please make sure your Internet connection is established and properly configured. "
            + "If you have firewall software enabled (like ZoneAlarm or the Windows Firewall) be sure that the SageTV Placeshifter is allowed to have outgoing network access.";
    // pathlen, path
    // 16-bit numEntries, *(16-bit pathlen, path)
    public static String CONNECT_FAILURE_LOCATOR_SERVER = "The SageTV Placeshifter is unable to connect to the SageTV Locator server. "
            + "The SageTV Locator server may be temporarily down, or connection to it may be blocked by a firewall. "
            + "If you have firewall software enabled (like ZoneAlarm or the Windows Firewall) be sure that the SageTV Placeshifter is allowed to have outgoing network access on port 8018. "
            + "If you're on a network that has a firewall, please contact your network administrator and ask them if they can open the outbound port 8018 for you.";
    // pathlen, path
    // 16-bit numEntries, *(16-bit pathlen, path)
    public static String CONNECT_FAILURE_LOCATOR_REGISTRATION = "The SageTV Placeshifter is unable to connect to the specified SageTV Media Center Server because it is not registered with the SageTV Locator service. "
            + "You may have entered your Locator ID incorrectly. If your Locator ID is correct, please make sure that the Placeshifter is configured on your SageTV Media Center, and that there's no outbound firewall restrictions on port 8018. "
            + "This can be done by going through the Configuration Wizard and testing the Placeshifter connection.";
    // secureID[4], offset[8], size[8], pathlen, path
    public static String CONNECT_FAILURE_SEVER_SIDE = "The SageTV Placeshifter is unable to connect to the specified SageTV Media Center Server. "
            + "Please make sure that the Placeshifter is configured on your SageTV Media Center and that port forwarding is properly configured for your network. "
            + "This can be done by going through the Configuration Wizard on the SageTV Media Center and testing the Placeshifter connection.";
    public static String CONNECT_FAILURE_CLIENT_SIDE = "The SageTV Placeshifter is unable to connect to the specified SageTV Media Center Server. "
            + "Check to make sure you don't have any local firewall software running that may be blocking the connection. "
            + "The connection may also be blocked by a firewall on your network. If you're being blocked by a network firewall, "
            + "you should try reconfiguring the Placeshifter on the SageTV Server to use a common external port such as 80 or 443. "
            + "This can be done in the Configuration Wizard on the SageTV Media Center.";
    // pathlen, path
    public static int HIGH_SECURITY_FS = 3;
    public static int MED_SECURITY_FS = 2;
    public static int LOW_SECURITY_FS = 1;

    private MiniClient client;
    private UIRenderer<?> uiRenderer;
    private MediaCmd myMedia;
    private java.net.Socket gfxSocket;
    private java.net.Socket mediaSocket;

    private String myID;
    private java.io.DataInputStream gfxIs;

    // This is the secret symmetric key encrypted with the public key
    private byte[] encryptedSecretKeyBytes;
    private java.security.PublicKey serverPublicKey;
    private javax.crypto.Cipher evtEncryptCipher;
    private java.security.Key mySecretKey;
    private boolean encryptEvents;
    private java.io.DataOutputStream eventChannel;
    private int replyCount;
    private GFXCMD2 myGfx;
    private EventRouterThread eventRouterThread;
    private boolean alive;
    private String currentCrypto = null;
    private boolean fontServer;
    private java.util.Timer uiTimer;
    private java.io.File tempfile;
    private java.io.File tempfile2; // TODO: add function to get name for
    // mplayer
    private java.nio.ByteBuffer mappedVideo;
    private int serverfd = -1;
    private int socketfd = -1;
    private String mappedfname;
    private int videowidth = 0;
    private int videoheight = 0;
    private int videoformat = 0;
    private int videoframetype = 0;
    private int fsSecurity;
    private boolean subSupport = false;
    // We need this for being able to store the auth block in the properties
    // file correctly
    private ServerInfo msi;
    private boolean zipMode;
    private java.util.Map lruImageMap = new java.util.HashMap();
    private boolean usesAdvancedImageCaching;
    private boolean reconnectAllowed;
    private boolean firstFrameStarted;
    private boolean performingReconnect;

    private List<String> videoCodecs = new ArrayList<String>();
    private List<String> audioCodecs = new ArrayList<String>();
    private List<String> pushFormats = new ArrayList<String>();
    private List<String> pullFormats = new ArrayList<String>();
    private List<String> passthroughCodecs = new ArrayList<String>();
    /**
     * Phase 3: per-player honest capability lists keyed by SageTV property
     * name (e.g. {@code EXO_VIDEO_CODECS} / {@code IJK_VIDEO_CODECS}).
     * Populated alongside the merged lists by
     * {@code AndroidMiniClientOptions.preparePerPlayerCapabilities()}.
     * Legacy 9.2.x servers never query these keys; NG servers can opt in.
     */
    private final java.util.Map<String, List<String>> perPlayerCapabilities = new java.util.HashMap<>();
    private final java.util.Map<String, String> perPlayerConstraintProperties = new java.util.HashMap<>();
    /**
     * Server-provided session player hint from CAP_EFFECTIVE_PLAYER.
     * Advisory only: local runtime decoder safety remains authoritative.
     */
    private volatile String serverEffectivePlayerHint = "";

    private MenuHint menuHint = new MenuHint();
    private Properties profileProperties;
    private NgPlaybackContextStore playbackContextStore;

    /**
     * Resolves whether this connection should advertise the fixed Placeshifter
     * legacy capability profile to the server. Order of precedence:
     *
     * <ol>
     *   <li>Per-server {@link ServerInfo#legacyMode}: LEGACY → true; NG → false.
     *   <li>AUTO (or null/missing): defaults to <b>false (NG advertise)</b>.
     *       The OPENURL IO_UNSPECIFIED hook flips AUTO→LEGACY at runtime if
     *       the server actually rejects our NG caps, then re-saves the
     *       ServerInfo so future connections start in LEGACY. Defaulting
     *       AUTO to NG means we never silently degrade an NG server to the
     *       legacy Placeshifter ceiling just because the user hasn't pinned
     *       it yet — worst case is one failed playback that auto-recovers.
     * </ol>
     *
     * <p>The old behavior fell back to the deprecated global
     * {@code legacy_server_compat} pref on AUTO; that pref defaulted to true
     * on installs migrated from pre-per-server-mode builds, which silently
     * forced every NG server into legacy advertisement. The fallback is
     * removed; users on legacy-only deployments should long-press their
     * server tile and pin LEGACY explicitly.</p>
     *
     * <p><b>AUTO default is LEGACY</b> (as of 1.15.118). Rationale: stock
     * SageTV 9.2.x is the dominant server population, and its failure mode
     * is silent — it advertises no codec metadata and the legacy resolver
     * picks the 2014 "unknown placeshifter" default (MPEG-4 Part 2 ASP +
     * MP2 + MPEG-PS). NG servers self-declare via {@code SAGETV_NG_SERVER=1}
     * (SetProperty) during the initial post-auth property exchange; receipt
     * of that property promotes AUTO → NG mid-handshake (see the
     * {@code SAGETV_NG_SERVER} handler in the SetProperty switch below).
     * The handshake completes before any media playback request, so the
     * promotion always happens in time.</p>
     */
    public boolean isLegacyServerCompat()
    {
        if (msi != null && msi.legacyMode != null)
        {
            switch (msi.legacyMode)
            {
                case LEGACY: return true;
                case NG:     return false;
                case AUTO:
                default:     break;
            }
        }
        // AUTO (or unset): default to LEGACY advertisement.
        // Receipt of SAGETV_NG_SERVER=1 from the server promotes to NG.
        return true;
    }

    /**
     * When connected to a stock SageTV 9.2.x server ({@link #isLegacyServerCompat()}
     * is true), the server's profile resolver only consults the modern
     * {@code PUSH_AV_CONTAINERS} / {@code VIDEO_CODECS} / {@code AUDIO_CODECS}
     * announces partially — it falls back to its hardcoded "unknown
     * placeshifter" default (MPEG-4 Part 2 ASP + MP2 + MPEG-PS, the worst
     * supported combo) whenever the client does not send
     * {@code FIXED_PUSH_MEDIA_FORMAT}.
     *
     * <p>The user-facing pref {@code fixed_encoding/preference=needed} (the
     * default) is the right answer on the NG server but on legacy 9.2.x it
     * means "give me your 2014 default" = the bad combo above. To give
     * legacy-server users a usable picture/sound without forcing them to
     * flip every device pref to {@code "always"}, this method synthesizes
     * a device-aware {@code FIXED_PUSH_MEDIA_FORMAT} recipe based on
     * {@link MiniClientOptions#getDeviceClass()} that maps to the
     * recommended settings in {@code ClientSettings.md}.</p>
     *
     * <p>Returns {@code null} (no auto-promotion, keep existing behavior)
     * when any of:
     * <ul>
     *   <li>not connected to a legacy 9.2.x server</li>
     *   <li>user explicitly pinned {@code preference=always} (their recipe wins)</li>
     *   <li>device class is {@code UNKNOWN} (can't pick safe codec ceiling)</li>
     * </ul></p>
     *
     * <p>Note: we DO NOT gate on {@code effectiveStreamingMode}. The legacy
     * 9.2.x server can still emit a {@code push:} URL even when the negotiated
     * transport is PULL (its resolver picks per-stream based on the source).
     * Pull playback simply ignores this property — there is no downside to
     * advertising it in every mode.</p>
     *
     * <p>Side effect on the legacy server: sending {@code FIXED_PUSH_MEDIA_FORMAT}
     * disables the legacy {@code dynamicRateAdjust}. The recipe bitrates
     * below are conservative LAN-friendly numbers per device tier.</p>
     */
    public String buildLegacyDeviceAwarePushFormat(String effectiveStreamingMode)
    {
        if (!isLegacyServerCompat()) return null;
        if (client == null || client.properties() == null) return null;
        if ("always".equalsIgnoreCase(client.properties().getFixedEncodingPreference())) return null;
        String dc = (client.options() != null) ? client.options().getDeviceClass() : "UNKNOWN";
        if (dc == null || "UNKNOWN".equals(dc)) return null;

        final int videobitrateBps;
        final int audiobitrateBps;
        final String audiochannels;
        // True for device tiers whose MediaCodec stack natively decodes HEVC
        // 4K. For these devices we deliberately OMIT videocodec= and
        // videobitrate= from the recipe so we don't force the server (legacy
        // 9.2.x OR an NG server before the AUTO→NG promotion takes effect)
        // to transcode an HEVC source down to H.264 8 Mbps. Legacy 9.2.x with
        // no explicit videocodec= falls back to its own default (H.264 in
        // matroska), preserving prior behavior. NG servers see no override
        // and apply their own per-stream PlaybackDecisionEngine result —
        // which is the fix for Bug 3 (HEVC quality regression).
        final boolean preserveSourceVideo;
        switch (dc)
        {
            case "SHIELD":
            case "PREMIUM_PHONE":
            case "FOLDABLE":
            case "TABLET":
                videobitrateBps = 8000000;
                audiobitrateBps = 384000;
                audiochannels = "6";
                preserveSourceVideo = true;
                break;
            case "MID_PHONE":
            case "BUDGET_ATV":
                videobitrateBps = 6000000;
                audiobitrateBps = 192000;
                audiochannels = "2";
                preserveSourceVideo = false;
                break;
            case "BUDGET_PHONE":
                videobitrateBps = 4000000;
                audiobitrateBps = 192000;
                audiochannels = "2";
                preserveSourceVideo = false;
                break;
            default:
                return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("container=matroska;");
        if (!preserveSourceVideo)
        {
            sb.append("videocodec=h264;");
            sb.append("videobitrate=").append(videobitrateBps).append(";");
        }
        sb.append("fps=SOURCE;");
        sb.append("resolution=SOURCE;");
        sb.append("audiocodec=ac3;");
        sb.append("audiobitrate=").append(audiobitrateBps).append(";");
        sb.append("audiochannels=").append(audiochannels).append(";");
        String fmt = sb.toString();
        log.logInfo("LEGACY FIXED_PUSH_MEDIA_FORMAT auto-promote [DeviceClass=" + dc
                + " effectiveStreamingMode=" + effectiveStreamingMode
                + " preserveSourceVideo=" + preserveSourceVideo + "]: " + fmt);
        return fmt;
    }

    /**
     * Companion to {@link #buildLegacyDeviceAwarePushFormat(String)}: synthesize
     * a device-aware {@code FIXED_PUSH_REMUX_FORMAT} so HEVC / H.264 sources
     * that just need container fixing get rewrapped instead of re-transcoded
     * by the legacy server's default profile.
     *
     * <p>Container choice per ClientSettings.md table:
     * SHIELD + BUDGET_ATV → {@code mpegts} (their MediaCodec stacks are
     * TS-first); everyone else → {@code matroska}.</p>
     */
    public String buildLegacyDeviceAwareRemuxFormat(String effectiveStreamingMode)
    {
        if (!isLegacyServerCompat()) return null;
        if (client == null || client.properties() == null) return null;
        if ("always".equalsIgnoreCase(client.properties().getFixedRemuxingPreference())) return null;
        if ("off".equalsIgnoreCase(client.properties().getFixedRemuxingPreference())) return null;
        String dc = (client.options() != null) ? client.options().getDeviceClass() : "UNKNOWN";
        if (dc == null || "UNKNOWN".equals(dc)) return null;

        final String container;
        switch (dc)
        {
            case "SHIELD":
            case "BUDGET_ATV":
                container = "mpegts";
                break;
            default:
                container = "matroska";
        }
        String fmt = "container=" + container + ";videocodec=COPY;audiocodec=COPY;";
        log.logInfo("LEGACY FIXED_PUSH_REMUX_FORMAT auto-promote [DeviceClass=" + dc
                + " effectiveStreamingMode=" + effectiveStreamingMode + "]: " + fmt);
        return fmt;
    }

    /**
     * NG safety-net REMUX recipe. Sent to NG servers as a guard against the
     * server's PlaybackDecisionEngine misfiring and falling back to its legacy
     * DVD-default transcode (MPEG-2 720x480 + MP2). With this recipe in place,
     * the worst the server can do on PUSH is wrap the source codecs in a
     * container the device prefers — HEVC / H.264 / etc. remain COPY (no
     * re-encode, no resolution change).
     *
     * <p>NG servers that pick PULL_DIRECT_PLAY (preferred) simply ignore this
     * hint — it only takes effect on PUSH. So this is a strict safety net,
     * never a quality cap.</p>
     *
     * <p>Container choice mirrors {@link #buildLegacyDeviceAwareRemuxFormat(String)}:
     * SHIELD + BUDGET_ATV → {@code mpegts} (TS-first decoders);
     * everyone else → {@code matroska}.</p>
     *
     * <p>Returns null when: client is in legacy-server mode (use
     * {@link #buildLegacyDeviceAwareRemuxFormat(String)} instead); user pinned
     * remux preference {@code off}; device class is UNKNOWN.</p>
     */
    public String buildNgSafetyRemuxFormat(String effectiveStreamingMode)
    {
        if (isLegacyServerCompat()) return null;
        if (client == null || client.properties() == null) return null;
        if ("off".equalsIgnoreCase(client.properties().getFixedRemuxingPreference())) return null;
        String dc = (client.options() != null) ? client.options().getDeviceClass() : "UNKNOWN";
        if (dc == null || "UNKNOWN".equals(dc)) return null;

        final String container;
        switch (dc)
        {
            case "SHIELD":
            case "BUDGET_ATV":
                container = "mpegts";
                break;
            default:
                container = "matroska";
        }
        String fmt = "container=" + container + ";videocodec=COPY;audiocodec=COPY;";
        log.logInfo("NG FIXED_PUSH_REMUX_FORMAT safety-net [DeviceClass=" + dc
                + " effectiveStreamingMode=" + effectiveStreamingMode + "]: " + fmt);
        return fmt;
    }

    /**
     * Called by the active player when it sees an error that strongly
     * suggests the server doesn't understand our NG capability advertisement
     * (e.g. {@code ERROR_CODE_IO_UNSPECIFIED} on first OPENURL because the
     * server's profile resolver couldn't pick a transcode/remux path).
     *
     * <p>If the connected server is in {@link ServerInfo.LegacyMode#AUTO}
     * mode, flip it to {@link ServerInfo.LegacyMode#LEGACY} and persist so
     * the next reconnect advertises the Placeshifter baseline. Manual
     * pins (LEGACY or NG explicitly) are respected and not changed.</p>
     *
     * <p>This v1 just persists — the user must back out and reconnect for
     * the new caps to take effect (capabilities are exchanged at handshake
     * time, not per OPENURL). A future improvement could trigger an
     * automatic reconnect via the existing GFXCMD_MEDIA_RECONNECT path.</p>
     *
     * @return true if the auto-flip happened (caller may want to surface a
     *         user-visible message); false if no change.
     */
    public boolean notifyServerCompatibilityFailure()
    {
        if (msi == null || msi.legacyMode != ServerInfo.LegacyMode.AUTO)
        {
            return false;
        }
        log.logInfo("Server '" + msi.name + "' returned a negotiation-mismatch error in AUTO mode; "
                + "flipping legacyMode → LEGACY and persisting. Reconnect to apply.");
        msi.legacyMode = ServerInfo.LegacyMode.LEGACY;
        try
        {
            msi.save(client.properties());
        }
        catch (Throwable t)
        {
            log.logError("Failed to persist legacyMode flip for server '" + msi.name + "'", t);
        }
        return true;
    }

    public MiniClientConnection(MiniClient client, String myID, ServerInfo msi, ILogger log)
    {
        this.log = log;
        this.client = client;
        currentCrypto = client.getCryptoFormats();

        uiRenderer = client.getUIRenderer();
        if (uiRenderer == null)
        {
            throw new RuntimeException("client.setUIRenderer() needs to be set before creating the connection");
        }

        if (msi.port <= 0)
        {
            msi.port = 31099;
        }

        if (!Utils.isEmpty(msi.address))
        {
            if (msi.address.indexOf(":") != -1)
            {
                msi.address = msi.address.substring(0, msi.address.indexOf(":"));
                msi.port = 31099;
                try
                {
                    msi.port = Integer.parseInt(msi.address.substring(msi.address.indexOf(":") + 1));
                }
                catch (NumberFormatException e) { }
            }
        }

        this.myID = myID;

        if (msi.macAddress!=null && !msi.macAddress.trim().isEmpty()) {
            log.logInfo("Overriding CLIENT ID with Connection Specific ID: Old ID: " + myID + " New ID: " + msi.macAddress);
            this.myID = msi.macAddress;
        }

        this.msi = msi;
        usesAdvancedImageCaching = false;
        this.playbackContextStore = new NgPlaybackContextStore(client.eventbus());
    }

    public MenuHint getMenuHint() {
        return menuHint;
    }

    // Needed for local video images...
    private static int getInt(byte[] buf, int offset)
    {
        int value = (buf[offset] & 0xFF) << 24;
        value |= (buf[offset + 1] & 0xFF) << 16;
        value |= (buf[offset + 2] & 0xFF) << 8;
        value |= (buf[offset + 3] & 0xFF) << 0;
        return value;
    }

    private static void putInt(byte[] buf, int offset, int value)
    {
        buf[offset] = (byte) ((value >> 24) & 0xFF);
        buf[offset + 1] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 3] = (byte) ((value >> 0) & 0xFF);
    }

    private static void putString(byte[] buf, int offset, String str)
    {
        try
        {
            byte[] b = str.getBytes("ISO8859_1");
            System.arraycopy(b, 0, buf, offset, str.length());
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private static String getCmdString(byte[] data, int offset) {
        int length = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
        try {
            return new String(data, offset + 2, length, "UTF-8");
        } catch (java.io.UnsupportedEncodingException uee) {
            return new String(data, offset + 2, length);
        }
    }

    private static long getCmdLong(byte[] data, int offset) {
        long rv = 0;
        for (int i = 0; i < 8; i++)
            rv = (rv << 8) | ((long) (data[offset + i] & 0xFF));
        return rv;
    }

    private java.net.Socket EstablishServerConnection(int connType) throws java.io.IOException {
        int flag = 1;
        java.net.Socket sake = null;
        java.io.InputStream inStream = null;
        java.io.OutputStream outStream = null;
        try {
            log.logInfo("Establishing Server Connection " + msi + " for Connection Type: " + connType);
            sake = new java.net.Socket();
            sake.connect(new java.net.InetSocketAddress(msi.address, msi.port), 30000);
            sake.setSoTimeout(30000);
            sake.setTcpNoDelay(true);
            sake.setKeepAlive(true);
            // Tune OS keep-alive timing so a dead control connection is
            // detected in ~80 s instead of the default ~2 h. No-op on
            // platforms that don't expose jdk.net.ExtendedSocketOptions.
            sagex.miniclient.util.SocketKeepAlive.apply(sake);
            outStream = sake.getOutputStream();
            inStream = sake.getInputStream();
            byte[] msg = new byte[7];
            msg[0] = (byte) 1;

            if (myID == null) {
                myID = client.getMACAddress();
            }

            log.logInfo("Establishing Server Connection using Client ID: " + myID);

            if (myID != null) {
                int len = Math.min((msg.length - 1) * 3, myID.length());
                for (int i = 0; i < len; i += 3) {
                    msg[1 + i / 3] = (byte) (Integer.parseInt(myID.substring(i, i + 2), 16) & 0xFF);
                }
            }
            log.logInfo("Establishing Server Connection using Client ID: " + msg);
            outStream.write(msg);
            outStream.write(connType);
            int rez = inStream.read();
            if (rez != 2) {
                log.logError("Error with reply from server: " + rez);
                inStream.close();
                outStream.close();
                sake.close();
                return null;
            }
            log.logInfo("Connection accepted by server: " + msi);
            sake.setSoTimeout(0);
            return sake;
        } catch (java.io.IOException e) {
            log.logError("ERROR with socket connection", e);
            try {
                if (sake!=null)
                    sake.close();
            } catch (Exception e1) {
            }
            try {
                if (inStream!=null)
                    inStream.close();
            } catch (Exception e1) {
            }
            try {
                if(outStream!=null)
                    outStream.close();
            } catch (Exception e1) {
            }
            throw e;
        }
    }

    public void connect() throws java.io.IOException {
        discoverCodecSupport();

        if (client.getCurrentConnection() != null) {
            // TODO: We should check if the connection is the same as this one, if so, then just use this connection.
            if (client.getCurrentConnection() != this) {
                log.logWarning("We already have server connection.  Shutting it down before connecting with this one.");
                client.getCurrentConnection().close();
            }
        }

        log.logInfo("Connecting to media server at" + msi);
        while (mediaSocket == null) {
            mediaSocket = EstablishServerConnection(1);
            if (mediaSocket == null) {
                // System.out.println("couldn't connect to media server,
                // retrying in 1 secs.");
                // try{Thread.sleep(1000);}catch(InterruptedException e){}
                throw new java.net.ConnectException();
            }
        }
        log.logInfo("Connected to media server: " + msi);

        log.logInfo("Connecting to ui server at: " + msi);
        while (gfxSocket == null) {
            gfxSocket = EstablishServerConnection(0);
            if (gfxSocket == null) {
                // System.out.println("couldn't connect to gfx server, retrying
                // in 5 secs.");
                // try { Thread.sleep(5000);} catch (InterruptedException e){}
                throw new java.net.ConnectException();
            }
        }
        log.logInfo("Connected to gfx server: " + msi);

        client.setCurrentConnection(this);

        alive = true;
        Thread t = new Thread("Media-" + msi.address) {
            public void run() {
                MediaThread();
            }
        };
        t.start();
        try {
            Thread.sleep(100);
        } catch (Exception e) {
        }
        t = new Thread("GFX-" + msi.address) {
            public void run() {
                GFXThread();
            }
        };
        t.start();

        String str = client.properties().getString(PrefStore.Keys.local_fs_security, "high");
        if ("low".equals(str))
            fsSecurity = LOW_SECURITY_FS;
        else if ("med".equals(str))
            fsSecurity = MED_SECURITY_FS;
        else
            fsSecurity = HIGH_SECURITY_FS;
    }

    Properties loadProperties(String resourceName) {
        InputStream is = MiniClientConnection.class.getClassLoader().getResourceAsStream(resourceName);
        if (is==null)
        {
            log.logWarning("Didn't Resolve Profile Name for: " + resourceName);
            throw new RuntimeException("Missing resource " + resourceName);
        }
        Properties prop = new Properties();
        try {
            prop.load(is);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return prop;
    }

    private void discoverCodecSupport()
    {
        profileProperties = loadProperties("common.profile");

        audioCodecs = AudioCodec.getAllSageTVNames();
        videoCodecs = VideoCodec.getAllSageTVNames();
        pullFormats = Container.getAllSageTVNames();
        pushFormats = Container.getAllSageTVNames();

        client.prepareCodecs(videoCodecs, audioCodecs, pushFormats, pullFormats);
        client.prepareAudioPassthrough(passthroughCodecs);
        client.preparePerPlayerCapabilities(perPlayerCapabilities);
        preparePerPlayerConstraintProperties();
    }

    private void preparePerPlayerConstraintProperties()
    {
        perPlayerConstraintProperties.clear();
        if (!isCapSchemaV2Enabled())
        {
            return;
        }
        perPlayerConstraintProperties.put("CAP_SCHEMA_VERSION", "2");

        final String deviceClass = (client != null && client.options() != null)
            ? client.options().getDeviceClass()
            : "UNKNOWN";

        perPlayerConstraintProperties.put(
            "EXO_VIDEO_CONSTRAINTS",
            buildVideoConstraintCsv(perPlayerCapabilities.get("EXO_VIDEO_CODECS"), deviceClass, true));
        perPlayerConstraintProperties.put(
            "IJK_VIDEO_CONSTRAINTS",
            buildVideoConstraintCsv(perPlayerCapabilities.get("IJK_VIDEO_CODECS"), deviceClass, false));

        perPlayerConstraintProperties.put(
                "EXO_AUDIO_CONSTRAINTS",
                buildAudioConstraintCsv(perPlayerCapabilities.get("EXO_AUDIO_CODECS"), passthroughCodecs));
        perPlayerConstraintProperties.put(
                "IJK_AUDIO_CONSTRAINTS",
                buildAudioConstraintCsv(perPlayerCapabilities.get("IJK_AUDIO_CODECS"), java.util.Collections.<String>emptyList()));

        perPlayerConstraintProperties.put(
                "EXO_CONTAINER_CONSTRAINTS",
                buildContainerConstraintCsv(perPlayerCapabilities.get("EXO_PUSH_AV_CONTAINERS"),
                        perPlayerCapabilities.get("EXO_PULL_AV_CONTAINERS")));
        perPlayerConstraintProperties.put(
                "IJK_CONTAINER_CONSTRAINTS",
                buildContainerConstraintCsv(perPlayerCapabilities.get("IJK_PUSH_AV_CONTAINERS"),
                        perPlayerCapabilities.get("IJK_PULL_AV_CONTAINERS")));
    }

    private boolean isCapSchemaV2Enabled()
    {
        return !isLegacyServerCompat();
    }

    private String buildVideoConstraintCsv(List<String> codecs, String deviceClass, boolean exoPath)
    {
        if (codecs == null || codecs.isEmpty()) return "";
        final java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        final StringBuilder sb = new StringBuilder();
        final MiniClientOptions options = (client != null) ? client.options() : null;

        for (String raw : codecs)
        {
            if (raw == null) continue;
            final String token = raw.trim();
            if (token.isEmpty() || !seen.add(token)) continue;

            String scan = "any";
            String interlaced = "unknown";
            String decoder = exoPath ? "hw" : "sw_or_hw";
            final boolean interlacedSafe = (options == null)
                    || options.isInterlacedVideoSafe(token, exoPath);

            if (interlacedSafe)
            {
                scan = "interlaced+progressive";
                interlaced = "true";
            }
            else
            {
                scan = "progressive";
                interlaced = "false";
            }

            if (sb.length() > 0) sb.append(',');
                String extras = (options != null) ? options.getVideoConstraintExtras(token, exoPath) : "";
            sb.append(token)
                    .append(";scan=").append(scan)
                    .append(";interlaced=").append(interlaced)
                    .append(";decoder=").append(decoder);
                if (extras != null && !extras.isEmpty()) sb.append(extras);
        }
        return sb.toString();
    }

    private static String buildAudioConstraintCsv(List<String> codecs, List<String> passthrough)
    {
        if (codecs == null || codecs.isEmpty()) return "";
        final java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        final java.util.HashSet<String> pt = new java.util.HashSet<>();
        if (passthrough != null)
        {
            for (String p : passthrough)
            {
                if (p != null && !p.trim().isEmpty()) pt.add(p.trim().toUpperCase(java.util.Locale.ROOT));
            }
        }

        final StringBuilder sb = new StringBuilder();
        for (String raw : codecs)
        {
            if (raw == null) continue;
            final String token = raw.trim();
            if (token.isEmpty() || !seen.add(token)) continue;

            final boolean pass = pt.contains(token.toUpperCase(java.util.Locale.ROOT));
            if (sb.length() > 0) sb.append(',');
            sb.append(token)
                    .append(";decode=true")
                    .append(";passthrough=").append(pass ? "true" : "false");
        }
        return sb.toString();
    }

    private static String buildContainerConstraintCsv(List<String> push, List<String> pull)
    {
        final java.util.LinkedHashMap<String, StringBuilder> rows = new java.util.LinkedHashMap<>();

        if (push != null)
        {
            for (String raw : push)
            {
                if (raw == null) continue;
                final String token = raw.trim();
                if (token.isEmpty()) continue;
                rows.put(token, new StringBuilder(token).append(";push=true;pull=false"));
            }
        }
        if (pull != null)
        {
            for (String raw : pull)
            {
                if (raw == null) continue;
                final String token = raw.trim();
                if (token.isEmpty()) continue;
                StringBuilder row = rows.get(token);
                if (row == null)
                {
                    rows.put(token, new StringBuilder(token).append(";push=false;pull=true"));
                }
                else
                {
                    int idx = row.indexOf("pull=false");
                    if (idx >= 0) row.replace(idx, idx + "pull=false".length(), "pull=true");
                }
            }
        }

        final StringBuilder sb = new StringBuilder();
        for (StringBuilder row : rows.values())
        {
            if (sb.length() > 0) sb.append(',');
            sb.append(row);
        }
        return sb.toString();
    }

    private static final java.util.regex.Pattern COMMA_SPLIT = java.util.regex.Pattern.compile("\\s*,\\s*");

    private List<String> stringToList(String str) {
        ArrayList<String> list = new ArrayList<String>();
        if (str==null) return list;
        for (String s: COMMA_SPLIT.split(str)) {
            list.add(s.trim());
        }
        return list;
    }

    /**
     * Normalizes player tokens used by negotiation properties.
     * Contract: only "exoplayer" and "ijkplayer" are concrete values.
     */
    private String normalizePlayerToken(String raw)
    {
        if (raw == null) return "";
        String normalized = raw.toLowerCase(java.util.Locale.ROOT).trim();
        return ("exoplayer".equals(normalized) || "ijkplayer".equals(normalized)) ? normalized : "";
    }

    private static boolean containsToken(List<String> list, String token)
    {
        if (list == null || token == null) return false;
        for (String raw : list)
        {
            if (raw == null) continue;
            if (token.equalsIgnoreCase(raw.trim())) return true;
        }
        return false;
    }

    private String resolveCapProfileId()
    {
        if (!isCapSchemaV2Enabled()) return "";

        // Prefer modern when the detected codec stack includes newer Android-era
        // capabilities. Fall back to legacy for older/uncertain devices.
        boolean hevcCapable = containsToken(perPlayerCapabilities.get("EXO_VIDEO_CODECS"), "HEVC")
                || containsToken(perPlayerCapabilities.get("IJK_VIDEO_CODECS"), "HEVC");
        boolean ac4Capable = containsToken(perPlayerCapabilities.get("EXO_AUDIO_CODECS"), "AC4")
                || containsToken(perPlayerCapabilities.get("IJK_AUDIO_CODECS"), "AC4");

        return (hevcCapable || ac4Capable) ? CAP_PROFILE_ANDROID_MODERN : CAP_PROFILE_ANDROID_LEGACY;
    }

    public boolean isConnected() {
        return alive;
    }

    public void close() {
        alive = false;
        try {
            client.setCurrentConnection(null);
        } catch (Exception e) {
        }

        try {
            gfxSocket.close();
        } catch (Exception e) {
        }

        GFXCMD2 oldGfx = myGfx;
        myGfx = null;
        if (oldGfx != null)
            oldGfx.close();
        try {
            mediaSocket.close();
        } catch (Exception e) {
        }

        if (eventRouterThread!=null) {
            // shut down the event router thread
            eventRouterThread.interrupt();
        }

        client = null;
    }

    private void GFXThread()
    {

        myGfx = new GFXCMD2(client);

        detailedBufferStats = false;
        byte[] cmd = new byte[4];
        int command, len;
        int[] hasret = new int[1];
        int retval;
        byte[] retbuf = new byte[4];
        final java.util.Vector gfxSyncVector = new java.util.Vector();

        // Try to connect to the media server port to see if we can actually do
        // pull-mode streaming.
        boolean canDoPullStreaming = false;

        try
        {
            log.logInfo("Testing to see if server can do a pull mode streaming connection at: " + msi.address + ":7818...");
            java.net.Socket mediaTest = new java.net.Socket();
            mediaTest.connect(new java.net.InetSocketAddress(msi.address, 7818), 2000);
            mediaTest.close();
            canDoPullStreaming = true;
            log.logInfo("Server can do a pull-mode streaming connection at: " +  msi.address + ":7818");
        }
        catch (Exception e)
        {
            log.logWarning("Failed pull mode media test....only use push mode for server: " + msi.address);
        }

        // Smart streaming fallback: compute the effective streaming mode based on
        // what's actually available, to prevent black screen from broken transcoders.
        //
        // Priority chain (least server CPU first):
        //   1. Pull mode  - client decodes locally, zero server CPU, always works
        //   2. Dynamic push - server pushes raw if codecs match, no FFmpeg needed
        //   3. Fixed remux  - container change only, needs working FFmpeg on server
        //   4. Fixed transcode - full re-encode, needs working FFmpeg on server
        //
        // If pull is available (port 7818 reachable), always use it.
        // If push-only (remote/Placeshifter), use dynamic to avoid FFmpeg dependency.
        //
        // Per-server override: ServerInfo.streamingModeOverride wins over both
        // the global pref and the pull-prefer auto-override below. Lets the
        // user pin a stock SageTV 9.2.x server to FIXED (so the client's
        // FIXED_PUSH_MEDIA_FORMAT recipe is respected) while leaving an NG
        // server on AUTOMATIC/PULL. Existing users default to INHERIT so
        // single-server setups see no change.
        final String userStreamingMode;
        final ServerInfo overrideSi = (client != null) ? client.getConnectedServerInfo() : null;
        final ServerInfo.StreamingModeOverride perServer =
                (overrideSi != null) ? overrideSi.streamingModeOverride : ServerInfo.StreamingModeOverride.INHERIT;
        if (perServer != null && perServer != ServerInfo.StreamingModeOverride.INHERIT) {
            switch (perServer) {
                case AUTOMATIC: userStreamingMode = "automatic"; break;
                case PULL:      userStreamingMode = "pull";      break;
                case DYNAMIC:   userStreamingMode = "dynamic";   break;
                case FIXED:     userStreamingMode = "fixed";     break;
                default:        userStreamingMode = client.properties().getStreamingMode();
            }
            log.logInfo("STREAMING per-server override: '" + perServer + "' -> '" + userStreamingMode
                    + "' (server: " + (overrideSi != null ? overrideSi.name : "?") + ")");
        } else {
            userStreamingMode = client.properties().getStreamingMode();
        }
        final String effectiveStreamingMode;
        final boolean userIsAutomatic = "automatic".equalsIgnoreCase(userStreamingMode);
        final boolean perServerExplicit =
                perServer != null && perServer != ServerInfo.StreamingModeOverride.INHERIT
                        && perServer != ServerInfo.StreamingModeOverride.AUTOMATIC;

        if (userIsAutomatic) {
            // Automatic: pick the best mode based on connectivity.
            //   - pull if port 7818 is reachable (zero server CPU, most reliable)
            //   - dynamic otherwise (raw push first, then server falls back to remux/transcode)
            effectiveStreamingMode = canDoPullStreaming ? "pull" : "dynamic";
            log.logInfo("STREAMING AUTOMATIC: resolved to '" + effectiveStreamingMode
                    + "' (pull available: " + canDoPullStreaming + ")");
        } else if (perServerExplicit) {
            // User pinned a per-server explicit mode. Do NOT auto-override to pull
            // even if port 7818 is reachable -- that's the whole point of the
            // override (lets the user keep a stock SageTV 9.2.x tile on FIXED for
            // higher-quality transcode while NG tiles ride PULL).
            effectiveStreamingMode = userStreamingMode;
            log.logInfo("STREAMING per-server explicit: keeping '" + effectiveStreamingMode
                    + "' (pull available: " + canDoPullStreaming + ")");
        } else if (canDoPullStreaming) {
            // User picked an explicit mode but pull is reachable -- keep current behavior of
            // overriding to pull, since pull mode always works and uses zero server CPU.
            effectiveStreamingMode = "pull";
            if (!"pull".equalsIgnoreCase(userStreamingMode)) {
                log.logInfo("STREAMING OVERRIDE: '" + userStreamingMode + "' -> 'pull' "
                        + "(port 7818 reachable, using pull mode for reliability and zero server CPU)");
            }
        } else if ("fixed".equalsIgnoreCase(userStreamingMode)) {
            // Push-only: fixed mode requests transcode/remux which needs FFmpeg.
            // Fall back to dynamic so server can try raw push first.
            effectiveStreamingMode = "dynamic";
            log.logWarning("STREAMING OVERRIDE: 'fixed' -> 'dynamic' "
                    + "(port 7818 unreachable, falling back to dynamic push to avoid server FFmpeg dependency)");
        } else if ("pull".equalsIgnoreCase(userStreamingMode)) {
            // User wanted pull but port 7818 is unreachable
            effectiveStreamingMode = "dynamic";
            log.logWarning("STREAMING OVERRIDE: 'pull' -> 'dynamic' "
                    + "(port 7818 unreachable, falling back to dynamic push mode)");
        } else {
            // User chose dynamic, keep it
            effectiveStreamingMode = userStreamingMode;
        }

        log.logInfo("Effective streaming mode: '" + effectiveStreamingMode
                + "' (user configured: '" + userStreamingMode
                + "', pull available: " + canDoPullStreaming + ")");

        try
        {
            // create the event router thread to handle routing of event on a separate thread
            eventRouterThread = new EventRouterThread("EVTRouter");
            eventRouterThread.start();

            eventChannel = new java.io.DataOutputStream(new java.io.BufferedOutputStream(gfxSocket.getOutputStream()));
            gfxIs = new java.io.DataInputStream(gfxSocket.getInputStream());
            zipMode = false;

            // Create the parallel threads so we can sync video and UI rendering
            // appropriately
            Thread gfxReadThread = new Thread("GFXRead")
            {
                public void run()
                {
                    byte[] gfxCmds = new byte[4];
                    byte[] cmdbuffer = new byte[4096];
                    int len;
                    java.io.DataInputStream myStream = gfxIs;
                    boolean enabledzip = false;

                    while (alive)
                    {
                        synchronized (gfxSyncVector)
                        {
                            if (gfxSyncVector.contains(gfxCmds))
                            {
                                try
                                {
                                    gfxSyncVector.wait(5000);
                                }
                                catch (InterruptedException e) { }
                                continue;
                            }
                        }

                        try
                        {
                            if (zipMode && !enabledzip)
                            {
                                // Recreate stream wrappers with ZLIB
                                com.jcraft.jzlib.ZInputStream zs = new com.jcraft.jzlib.ZInputStream(gfxSocket.getInputStream(),true);
                                zs.setFlushMode(com.jcraft.jzlib.JZlib.Z_SYNC_FLUSH);
                                myStream = new java.io.DataInputStream(zs);
                                enabledzip = true;
                            }

                            // System.out.println("before gfxread readfully");
                            myStream.readFully(gfxCmds);
                            len = ((gfxCmds[1] & 0xFF) << 16) | ((gfxCmds[2] & 0xFF) << 8) | (gfxCmds[3] & 0xFF);

                            if (cmdbuffer.length < len)
                            {
                                cmdbuffer = new byte[len];
                            }

                            // Read from the tcp socket
                            myStream.readFully(cmdbuffer, 0, len);
                        }
                        catch (Exception e)
                        {
                            if (reconnectAllowed && alive && firstFrameStarted && !encryptEvents)
                            {
                                performingReconnect = true;
                                enabledzip = false;
                                log.logError("GFX channel detected a connection error and we're in a mode that allows reconnect...try to reconnect to the server now", e);
                                try
                                {
                                    myStream.close();
                                }
                                catch (Exception e1) { }

                                try
                                {
                                    eventChannel.close();
                                }
                                catch (Exception e1) { }

                                try
                                {
                                    gfxSocket.close();
                                }
                                catch (Exception e1) { }

                                try
                                {
                                    gfxSocket = EstablishServerConnection(5);
                                    if (gfxSocket == null) throw new Exception("Failed to reconnect to server.  Unable to establish Graphics Socket.");
                                    eventChannel = new java.io.DataOutputStream(new java.io.BufferedOutputStream(gfxSocket.getOutputStream()));
                                    myStream = gfxIs = new java.io.DataInputStream(gfxSocket.getInputStream());

                                    if (zipMode && !enabledzip)
                                    {
                                        // Recreate stream wrappers with ZLIB
                                        com.jcraft.jzlib.ZInputStream zs = new com.jcraft.jzlib.ZInputStream(gfxSocket.getInputStream(), true);
                                        zs.setFlushMode(com.jcraft.jzlib.JZlib.Z_SYNC_FLUSH);
                                        myStream = new java.io.DataInputStream(zs);
                                        enabledzip = true;
                                    }
                                    log.logInfo("Done doing server reconnect...continue on our merry way!");
                                }
                                catch (Exception e1)
                                {
                                    log.logError("Failure in reconnecting to server...abort the client", e1);
                                    performingReconnect = false;

                                    if (client!=null)
                                    {
                                        client.eventbus().post(new ConnectionLost(performingReconnect));
                                    }
                                    synchronized (gfxSyncVector)
                                    {
                                        gfxSyncVector.add(e);
                                        return;
                                    }
                                }
                                performingReconnect = false;
                            }
                            else
                            {
                                synchronized (gfxSyncVector)
                                {
                                    gfxSyncVector.add(e);
                                    return;
                                }
                            }
                        }
                        synchronized (gfxSyncVector)
                        {
                            gfxSyncVector.add(gfxCmds);
                            gfxSyncVector.add(cmdbuffer);
                            gfxSyncVector.notifyAll();
                        }
                    }
                }
            };
            gfxReadThread.setDaemon(true);
            gfxReadThread.start();

            while (alive)
            {
                byte[] cmdbuffer;

                synchronized (gfxSyncVector)
                {
                    if (!gfxSyncVector.isEmpty())
                    {
                        Object newData = gfxSyncVector.get(0);
                        if (newData instanceof Throwable)
                        {
                            throw (Throwable) newData;
                        }
                        else
                        {
                            cmd = (byte[]) newData;
                            cmdbuffer = (byte[]) gfxSyncVector.get(1);
                        }
                    }
                    else
                    {
                        try
                        {
                            gfxSyncVector.wait(5000);
                        }
                        catch (InterruptedException e) { }
                        continue;
                    }
                }

                command = (cmd[0] & 0xFF);
                len = ((cmd[1] & 0xFF) << 16) | ((cmd[2] & 0xFF) << 8) | (cmd[3] & 0xFF);

                if ((command & 0x80) != 0) // Local video update command
                {
                    byte[] data = cmdbuffer;
                    switch (cmd[0] & 0xFF)
                    {
                        case 0x80: // New video
                            log.logDebug("NOT IMPLEMENTED(0x80): Video cmd: " + (cmd[0] & 0xFF));
                            videowidth = getInt(data, 0);
                            videoheight = getInt(data, 4);
                            videoformat = getInt(data, 8);
                            myGfx.createVideo(videowidth, videoheight, videoformat);
                            putInt(data, 0, mappedfname.length());
                            putString(data, 4, mappedfname);
                            putInt(data, 4 + mappedfname.length() + 0, 0); // offsetY
                            putInt(data, 4 + mappedfname.length() + 4, videowidth); // pitchY
                            putInt(data, 4 + mappedfname.length() + 8, videowidth * videoheight); // offsetU
                            putInt(data, 4 + mappedfname.length() + 12, videowidth / 2); // pitchU
                            putInt(data, 4 + mappedfname.length() + 16, videowidth * videoheight + videowidth * videoheight / 4); // offsetV
                            putInt(data, 4 + mappedfname.length() + 20, videowidth / 2); // pitchV
                            break;
                        case 0x81: // New frame
                            log.logDebug("NOT IMPLEMENTED(0x81): New Frame command");
                            videoframetype = getInt(data, 0);
                            myGfx.updateVideo(videoframetype, mappedVideo);
                            putInt(data, 0, 0); // offsetY
                            putInt(data, 4, videowidth); // pitchY
                            putInt(data, 8, videowidth * videoheight); // offsetU
                            putInt(data, 12, videowidth / 2); // pitchU
                            putInt(data, 16, videowidth * videoheight + videowidth * videoheight / 4); // offsetV
                            putInt(data, 20, videowidth / 2); // pitchV
                            break;
                    }

                }
                if (command == DRAWING_CMD_TYPE) // GFX cmd
                {
                    // We need to let the opengl rendering thread do that...
                    command = (cmdbuffer[0] & 0xFF);

                    if (command == GFXCMD_MEDIA_RECONNECT)
                    {
                        // Just tell the MediaThread to kill its current
                        // connection and reconnect
                        try
                        {
                            mediaSocket.close();
                        }
                        catch (Exception e) { }
                    }
                    else
                    {
                        if (command == GFXCMD2.GFXCMD_STARTFRAME)
                        {
                            firstFrameStarted = true;
                        }
                        retval = myGfx.ExecuteGFXCommand(command, len, cmdbuffer, hasret);

                        if (hasret[0] != 0)
                        {
                            retbuf[0] = (byte) ((retval >> 24) & 0xFF);
                            retbuf[1] = (byte) ((retval >> 16) & 0xFF);
                            retbuf[2] = (byte) ((retval >> 8) & 0xFF);
                            retbuf[3] = (byte) ((retval >> 0) & 0xFF);

                            try
                            {
                                synchronized (eventChannel)
                                {
                                    eventChannel.write(DRAWING_CMD_TYPE); // GFX
                                    // reply
                                    eventChannel.writeShort(0);
                                    eventChannel.write(4);// 3 byte length of 4
                                    eventChannel.writeInt(0); // timestamp
                                    eventChannel.writeInt(replyCount++);
                                    eventChannel.writeInt(0); // pad
                                    if (encryptEvents && evtEncryptCipher != null)
                                    {
                                        eventChannel.write(evtEncryptCipher.doFinal(retbuf, 0, 4));
                                    }
                                    else
                                    {
                                        eventChannel.write(retbuf, 0, 4);
                                    }
                                    eventChannel.flush();
                                }
                            }
                            catch (Throwable e)
                            {
                                log.recordException(e);
                                eventChannelError();
                            }
                        }
                    }
                }
                else if (command == GET_PROPERTY_CMD_TYPE) // get property
                {
                    String propName = new String(cmdbuffer, 0, len);
                    String propVal = "";
                    byte[] propValBytes = null;
                    if ("GFX_TEXTMODE".equals(propName))
                    {

                        // NARFLEX - 1/17/10 - Just never allow text rendering
                        // directly because the new effects system has
                        // clipping issues associated with it and the
                        // Placeshifter never will connect to the localhost
                        // address automatically anyways. This way we'll have
                        // consistency across implementations.
                        // if (!isLocahostConnection())
                        propVal = "";
                    }
                    else if ("GFX_BLENDMODE".equals(propName))
                    {
                        propVal = "PREMULTIPLY"; // opengl using PRE
                        //propVal = "POSTMULTIPLY";
                    }
                    else if ("GFX_SCALING".equals(propName))
                    {
                        propVal = "HARDWARE"; // opengl uses hardware scaling
                    }
                    else if ("GFX_OFFLINE_IMAGE_CACHE".equals(propName))
                    {
                        if (client.properties().getBoolean(PrefStore.Keys.cache_images_on_disk, true))
                        {
                            propVal = "TRUE";
                        }
                        else
                        {
                            propVal = "FALSE";
                        }
                    }
                    else if ("OFFLINE_CACHE_CONTENTS".equals(propName))
                    {
                        propVal = client.getImageCache().getOfflineCacheList();
                    }
                    else if ("ADVANCED_IMAGE_CACHING".equals(propName))
                    {
                        propVal = "TRUE";
                        usesAdvancedImageCaching = true;
                    }
                    else if ("GFX_BITMAP_FORMAT".equals(propName))
                    {
                        if (!client.properties().getBoolean(PrefStore.Keys.use_bitmap_images, true))
                        {
                            propVal = "";
                        }
                        else
                        {
                            propVal = "PNG,JPG,GIF,BMP";
                        }
                    }
                    else if ("GFX_COMPOSITE".equals(propName))
                    {
                        propVal = "BLEND"; // opengl uses blend
                        //propVal = "COLORKEY";
                    }
                    else if ("GFX_SURFACES".equals(propName) || "GFX_HIRES_SURFACES".equals(propName))
                    {
                        propVal = "TRUE";
                    }
                    else if ("GFX_DIFFUSE_TEXTURES".equals(propName))
                    {
                        // if (myGfx instanceof DirectX9GFXCMD)
                        // propVal = "TRUE";
                        // else
                        propVal = "";
                    }
                    else if ("GFX_XFORMS".equals(propName))
                    {
                        // if (myGfx instanceof DirectX9GFXCMD)
                        // propVal = "TRUE";
                        // else
                        propVal = "";
                    }
                    else if ("GFX_TEXTURE_BATCH_LIMIT".equals(propName))
                    {
                        // We don't support this command yet
                        propVal = "";
                    }
                    else if ("GFX_COLORKEY".equals(propName))
                    {
                        propVal = "080010";
                    }
                    else if ("STREAMING_PROTOCOLS".equals(propName))
                    {
                        propVal = "file,stv";
                    }
                    else if ("INPUT_DEVICES".equals(propName))
                    {
                        // Phase B (stream-copy HD remux): when the user has pinned this
                        // legacy server to FIXED, advertise as a media-extender (no MOUSE)
                        // so the upstream MiniPlayer.load() takes the mediaExtender branch
                        // and emits 'mpeg2psremux' (pure stream copy, -vcodec copy -acodec copy)
                        // instead of the hardcoded 352x240 'dynamic' transcode.
                        // [google/SageTV@f55505f:java/sage/MiniClientSageRenderer.java#isMediaExtender]
                        // [google/SageTV@f55505f:java/sage/MiniPlayer.java#load mediaExtender branch]
                        if (isPhaseBExtenderRemuxActive(effectiveStreamingMode))
                        {
                            propVal = "IR,KEYBOARD";
                            log.logInfo("PHASE_B: INPUT_DEVICES -> 'IR,KEYBOARD' (extender posture for mpeg2psremux)");
                        }
                        else
                        {
                            propVal="IR,KEYBOARD";
                            if (client.options().isDesktopUI()) propVal+=",MOUSE";
                            if (client.options().isTouchUI()) propVal+=",TOUCH";
                            if (client.options().isTVUI()) propVal+=",TV";
                            // propVal = "IR,KEYBOARD,TOUCH"; // MOUSE,KEYBOARD,TOUCH,IR (mouse implies desktop)
                        }
                    }
                    else if ("DISPLAY_OVERSCAN".equals(propName))
                    {
                        propVal = "0;0;1.0;1.0";
                    }
                    else if ("FIRMWARE_VERSION".equals(propName))
                    {
                        // propVal = sage.Version.MAJOR_VERSION + "." +
                        // sage.Version.MINOR_VERSION + "." +
                        // sage.Version.MICRO_VERSION;
                        propVal = "9.0.0";
                    }
                    else if ("SAGETV_NG_VERSION".equals(propName))
                    {
                        // NG servers read this to engage modern profile (HEVC + AC-4 +
                        // audioonly transcode); legacy 9.x servers ignore unknown props.
                        // See constant declaration above for back-out path.
                        propVal = SAGETV_NG_VERSION;
                    }
                    else if ("CAP_PROFILE_ID".equals(propName))
                    {
                        // Under schema-v2 always send an explicit profile id so NG
                        // servers do not fall back to generic desktop profile selection.
                        propVal = resolveCapProfileId();
                    }
                    else if ("CAP_OVERRIDES".equals(propName))
                    {
                        // Optional schema-v2 JSON payload for explicit user/profile
                        // policy overrides only (server parses simple key/value map,
                        // e.g. allow_hevc / auto_remux).
                        //
                        // Default is empty: capability truth should come from the
                        // per-player codec/container/constraint payloads, and we only
                        // populate CAP_OVERRIDES when a user intentionally pins a
                        // profile-level policy override.
                        //
                        // Return empty string (not "null") so negotiation logs and
                        // parser behavior remain deterministic.
                        propVal = "";
                    }
                    else if ("SAGETV_NG_CAPABILITIES".equals(propName))
                    {
                        // NG capability advertisement. Server
                        // (MiniClientSageRenderer) parses this as tokenized
                        // text (comma/semicolon/pipe/space delimited) and
                        // uses it to gate features the client opts into.
                        //
                        // Token naming convention:
                        //   DOWNLOAD_*  - protocol verb the client can emit
                        //                 or accept on the control channel.
                        //   OFFLINE_*   - additional payload the client can
                        //                 receive alongside the downloaded
                        //                 media file and persist locally.
                        //
                        // RULE: only advertise tokens for which the client
                        // can actually CONSUME the corresponding server
                        // payload. Advertising a token without parser +
                        // persistence means the server may send data we
                        // then silently drop, which is worse than not
                        // asking. As features ship, add the token here in
                        // the same release as the parser/store code.
                        //
                        // Currently supported:
                        //   DOWNLOAD          - accept CMD_DOWNLOAD_REQUEST
                        //                       and run the queue (gated on
                        //                       DownloadStatusProvider being
                        //                       wired up by the host module).
                        //   DOWNLOAD_REFRESH  - emit opcode 228
                        //                       DOWNLOAD_REFRESH_REQUEST on
                        //                       stale-token detection and on
                        //                       user retry; expects a fresh
                        //                       CMD_DOWNLOAD_REQUEST back.
                        //
                        // Reserved tokens (NG roadmap; do NOT advertise
                        // until the corresponding client-side parser and
                        // local-store land):
                        //   OFFLINE_METADATA   - structured MediaFile +
                        //                        Airing + Show JSON blob in
                        //                        CMD_DOWNLOAD_REQUEST.
                        //                        Includes artwork URLs and
                        //                        any other Wiz.bin-derived
                        //                        descriptors; the artwork
                        //                        BYTES are fetched under
                        //                        OFFLINE_ARTWORK below.
                        //   OFFLINE_ARTWORK    - binary fetch of poster /
                        //                        fanart / banner image files
                        //                        referenced by the metadata
                        //                        JSON. One token covers all
                        //                        three variants; the server
                        //                        decides which ones to ship.
                        //   OFFLINE_CAPTIONS   - captions/subtitles delivered
                        //                        as a sidecar for containers
                        //                        that don't carry them
                        //                        inline. Covers EIA-608/708
                        //                        CC extracted from the source
                        //                        and any foreign-language
                        //                        track that the server
                        //                        externalizes. (For Sage
                        //                        recordings this is typically
                        //                        just CC; multi-language
                        //                        tracks normally remain inside
                        //                        the container and ride along
                        //                        with the media file.)
                        //   OFFLINE_COMSKIP    - commercial-skip cuts
                        //                        (.edl preferred; .txt
                        //                        comskip format accepted).
                        //                        Note: SageTV has no separate
                        //                        chapter concept; the seek-bar
                        //                        "chapter" marks some STVs
                        //                        render are derived from
                        //                        comskip cuts.
                        //   OFFLINE_TRANSCRIPT - speech-to-text transcript
                        //                        (.vtt or .json with word
                        //                        timestamps).
                        StringBuilder caps = new StringBuilder();
                        if (client.getDownloadStatusProvider() != null)
                        {
                            caps.append("DOWNLOAD,DOWNLOAD_REFRESH,OFFLINE_METADATA,OFFLINE_ARTWORK");
                            if (client.properties().getBoolean(PrefStore.Keys.offline_cap_captions, true))
                            {
                                caps.append(",OFFLINE_CAPTIONS");
                            }
                            if (client.properties().getBoolean(PrefStore.Keys.offline_cap_comskip, true))
                            {
                                caps.append(",OFFLINE_COMSKIP");
                            }
                            if (client.properties().getBoolean(PrefStore.Keys.offline_cap_transcript, true))
                            {
                                caps.append(",OFFLINE_TRANSCRIPT");
                            }
                        }
                        propVal = caps.toString();
                        log.logInfo("SAGETV_NG_CAPABILITIES -> '" + propVal + "'");
                    }
                    else if ("DETAILED_BUFFER_STATS".equals(propName))
                    {
                        propVal = "TRUE";
                        detailedBufferStats = true;
                    }
                    else if ("PUSH_BUFFER_SEEKING".equals(propName))
                    {
                        // We do NOT implement client-side seeking inside the push
                        // ring buffer. IJK/Exo only hold a few seconds of forward
                        // data, so a seek beyond that window can only be satisfied
                        // by the server flushing and re-pushing from the new
                        // position. Advertising TRUE made the legacy server send
                        // MEDIACMD_SEEK alone (no flush, no restream), which left
                        // OSD advancing while video kept playing the stale buffer
                        // (FF "did nothing" on one legacy server deployment).
                        //
                        // See IJKMediaPlayerImpl.seek() push-mode branch — its own
                        // comment says "The server handles seeking by flushing the
                        // old data and pushing new data from the seek position",
                        // i.e. server-driven flush+restream is the contract we
                        // actually honor. Answer FALSE so the server uses that
                        // path (MEDIACMD_FLUSH + new PUSHBUFFER stream).
                        propVal = "FALSE";
                    }
                    else if ("GFX_SUBTITLES".equals(propName))
                    {
                        propVal = "TRUE";
                    }
                    else if ("FORCED_MEDIA_RECONNECT".equals(propName))
                    {
                        propVal = "TRUE";
                    }
                    else if ("AUTH_CACHE".equals(propName))
                    {
                        propVal = (msi != null) ? "TRUE" : "";
                        log.logDebug("AUTH_CACHE Called: " + propVal);

                    }
                    else if ("GET_CACHED_AUTH".equals(propName))
                    {
                        // Make sure crypto is on before we send this back!!
                        if (encryptEvents && evtEncryptCipher != null && msi != null && msi.authBlock != null)
                        {
                            propVal = msi.authBlock;
                        }
                        else
                        {
                            propVal = "";
                        }
                        log.logDebug("GET_CACHED_AUTH Called: " + propVal);
                    }
                    else if ("REMOTE_FS".equals(propName))
                    {
                        if (fsSecurity <= MED_SECURITY_FS)
                        {
                            propVal = "TRUE";
                        }
                        else
                        {
                            propVal = "";
                        }
                    }
                    else if ("MEDIA_DOWNLOAD_SUPPORT".equals(propName))
                    {
                        propVal = "TRUE";
                    }
                    else if ("MEDIA_DOWNLOAD_QUEUE_SIZE".equals(propName))
                    {
                        propVal = "1";
                    }
                    else if (propName != null && propName.startsWith("DOWNLOAD_STATUS_"))
                    {
                        String mediaFileID = propName.substring("DOWNLOAD_STATUS_".length());
                        DownloadStatusProvider dsp = client.getDownloadStatusProvider();
                        if (dsp != null)
                        {
                            String status = dsp.getDownloadStatus(mediaFileID);
                            propVal = (status != null) ? status : "";
                        }
                        else
                        {
                            propVal = "";
                        }
                    }
                    else if ("GFX_VIDEO_UPDATE".equals(propName))
                    {
                        propVal = "TRUE";
                    }
                    else if ("ZLIB_COMM".equals(propName))
                    {
                        propVal = "TRUE";
                    }
                    else if ("VIDEO_CODECS".equals(propName))
                    {
                        if (isLegacyServerCompat())
                        {
                            // 9.2.x server: device-aware Placeshifter advertisement.
                            // See LEGACY_VIDEO_UNIVERSE comment block for rationale.
                            // Source of truth is runtime per-player capability detection
                            // (EXO/IJK union), with legacy flat list as fallback.
                            propVal = legacyAdvertise("VIDEO_CODECS", LEGACY_VIDEO_UNIVERSE,
                                    getLegacySourceTokens("EXO_VIDEO_CODECS", "IJK_VIDEO_CODECS", videoCodecs));
                            // Phase B: when FIXED override is active, the mediaExtender +
                            // mpeg2psremux selection in upstream MiniPlayer requires
                            // clientCanDoMPEGHD = isSupportedVideoCodec("MPEG2-VIDEO@HL").
                            // Force-include it (the recipe is pure stream copy, so the
                            // client must only RECEIVE the original HD MPEG-2 PS; actual
                            // decode happens in the player chosen at OPENURL — and the
                            // ExoPlayer PsExtractor landmine is already routed to IJK by
                            // PlayerSelectionUtil.isExoPsMpeg4Landmine).
                            if (isPhaseBExtenderRemuxActive(effectiveStreamingMode))
                            {
                                propVal = ensureCsvToken(propVal, "MPEG2-VIDEO@HL");
                                log.logInfo("PHASE_B: VIDEO_CODECS force-include MPEG2-VIDEO@HL -> " + propVal);
                            }
                        }
                        else if (client.properties().getFixedEncodingPreference().equalsIgnoreCase("always")
                                && effectiveStreamingMode.equalsIgnoreCase("fixed"))
                        {
                            propVal = "NONE";
                        }
                        else
                        {
                            String extra_codecs = client.properties().getString(PrefStore.Keys.mplayer_extra_video_codecs, null);
                            propVal = toStringList(videoCodecs);
                            if (extra_codecs != null)
                                propVal += "," + extra_codecs;
                        }
                    }
                    else if ("AUDIO_CODECS".equals(propName))
                    {
                        if (isLegacyServerCompat())
                        {
                            // 9.2.x server: device-aware Placeshifter advertisement.
                            // Drops decoder tokens the device can't actually handle
                            // (e.g. DTS-HD on devices without DTS HW) so the resolver
                            // picks an audio target the client can play.
                            propVal = legacyAdvertise("AUDIO_CODECS", LEGACY_AUDIO_UNIVERSE,
                                    getLegacySourceTokens("EXO_AUDIO_CODECS", "IJK_AUDIO_CODECS", audioCodecs));
                        }
                        else if (client.properties().getFixedEncodingPreference().equalsIgnoreCase("always")
                                && effectiveStreamingMode.equalsIgnoreCase("fixed"))
                        {
                            propVal = "NONE";
                        }
                        else
                        {
                            String extra_codecs = client.properties().getString(PrefStore.Keys.mplayer_extra_audio_codecs, null);
                            propVal = toStringList(audioCodecs);
                            if (extra_codecs != null)
                            {
                                propVal += "," + extra_codecs;
                            }
                        }
                    }
                    else if ("AUDIO_PASSTHROUGH".equals(propName))
                    {
                        // Phase 2: per-codec bitstream-to-sink passthrough advertisement.
                        // Resolved set is populated by client.prepareAudioPassthrough()
                        // from the AudioCapabilities.supportsEncoding() probe + per-codec
                        // tri-state overrides under codec/audio_passthrough/<NAME>/support.
                        // Legacy 9.2.x servers ignore this property; NG servers consult it
                        // to decide whether compressed surround can be pushed without
                        // transcoding to PCM.
                        propVal = toStringList(passthroughCodecs);
                        if (propVal == null || propVal.isEmpty()) propVal = "NONE";
                    }
                    else if ("PUSH_AV_CONTAINERS".equals(propName))
                    {
                        if (isLegacyServerCompat())
                        {
                            // 9.2.x server: device-aware Placeshifter push container set.
                            // Most modern Android devices handle MPEG2-TS; MPEG2-PS is
                            // the ExoPlayer-PsExtractor landmine container but the
                            // PlayerSelectionUtil swap routes around that at OPENURL time.
                            propVal = legacyAdvertise("PUSH_AV_CONTAINERS", LEGACY_PUSH_UNIVERSE,
                                    getLegacySourceTokens("EXO_PUSH_AV_CONTAINERS", "IJK_PUSH_AV_CONTAINERS", pushFormats));
                            // Phase B: clientDoesMPEG2Push = isSupportedPushContainerFormat("MPEG2-PS")
                            // is required for the mpeg2psremux branch in upstream MiniPlayer.
                            // Force-include MPEG2-PS when FIXED override is active so the
                            // server's "only container unsupported" path can fire.
                            if (isPhaseBExtenderRemuxActive(effectiveStreamingMode))
                            {
                                propVal = ensureCsvToken(propVal, "MPEG2-PS");
                                log.logInfo("PHASE_B: PUSH_AV_CONTAINERS force-include MPEG2-PS -> " + propVal);
                            }
                            else
                            {
                                // BUDGET_ATV bias (Chromecast w/ Google TV, Onn 4K, etc.):
                                // their MediaCodec pipelines are TS-first and stutter on
                                // MPEG-PS push. Drop MPEG2-PS so the upstream "container
                                // unsupported" branch picks MPEG2-TS remux instead. We
                                // keep MPEG2-TS in the advertisement (or fall back to
                                // MPEG1-PS) so the server still has a push target.
                                String dc = (client != null && client.options() != null) ? client.options().getDeviceClass() : "UNKNOWN";
                                if ("BUDGET_ATV".equals(dc) && propVal != null && propVal.toUpperCase(java.util.Locale.ROOT).contains("MPEG2-PS")
                                        && propVal.toUpperCase(java.util.Locale.ROOT).contains("MPEG2-TS"))
                                {
                                    String filtered = removeCsvToken(propVal, "MPEG2-PS");
                                    log.logInfo("LEGACY PUSH bias [BUDGET_ATV]: dropped MPEG2-PS -> " + filtered);
                                    propVal = filtered;
                                }
                            }
                        }
                        else if (((client.properties().getFixedEncodingPreference().equalsIgnoreCase("always")
                            || client.properties().getFixedRemuxingPreference().equalsIgnoreCase("always"))
                                && effectiveStreamingMode.equalsIgnoreCase("fixed")))
                        {
                            // If we are using fixed transcode always, do not allow transcode/remux to mpeg-ps/ts.
                            // pushing
                            propVal = "NONE";
                        }
                        // NOTE: previously we forced PUSH_AV_CONTAINERS=NONE whenever effectiveStreamingMode resolved
                        // to "pull" (i.e. server reachable on the pull port). That advertised a false capability:
                        // the client IS able to receive raw MPEG-PS/TS via the push transport, and the server's
                        // profile resolver uses PUSH_AV_CONTAINERS to decide whether DIRECT_PLAY (MPEG2 pusher) is
                        // available. Forcing NONE here pushed the server into transcoder paths even when push would
                        // have worked (e.g. HEVC + MPEG2-TS). The push vs. pull decision is made per-stream when the
                        // server returns a push:// or stv:// URL to OPENURL; capability advertisement should describe
                        // what we can accept regardless of the currently-preferred mode.
                        else
                        {
                            if(pushFormats.size() == 0)
                            {
                                propVal = "NONE";
                            }
                            else
                            {
                                propVal = toStringList(pushFormats);
                            }

                        }
                    }
                    else if ("PULL_AV_CONTAINERS".equals(propName))
                    {

                        /*
                        PULL - Containers we can read without transcoding.
                        Set this to empty if we are remote or if we are fixed and preference is to always transcode or always remux
                        */
                        if (isLegacyServerCompat())
                        {
                            // 9.2.x server: device-aware Placeshifter pull container set.
                            if (!canDoPullStreaming)
                                propVal = "";
                            else if (isPhaseBExtenderRemuxActive(effectiveStreamingMode))
                            {
                                // Phase B: pin PULL_AV_CONTAINERS empty so the upstream
                                // PULL-vs-PUSH decision in MiniPlayer.load() never picks
                                // PULL for the source's container. That's what arms the
                                // "only container unsupported" branch on the push side,
                                // which is the prerequisite for prefTranscodeMode=
                                // mpeg2psremux. [google/SageTV@f55505f:java/sage/MiniPlayer.java]
                                propVal = "";
                                log.logInfo("PHASE_B: PULL_AV_CONTAINERS -> '' (force PUSH path for extender remux)");
                            }
                            else
                                propVal = legacyAdvertise("PULL_AV_CONTAINERS", LEGACY_PULL_UNIVERSE,
                                    getLegacySourceTokens("EXO_PULL_AV_CONTAINERS", "IJK_PULL_AV_CONTAINERS", pullFormats));
                        }
                        else if (!canDoPullStreaming
                                || ((client.properties().getFixedEncodingPreference().equalsIgnoreCase("always")
                                || client.properties().getFixedRemuxingPreference().equalsIgnoreCase("always"))
                                && "fixed".equalsIgnoreCase(effectiveStreamingMode)))
                        {
                            propVal = "";
                        }
                        else
                        {
                            // if we are being forced into PULL mode, then add the push containers to our PULL containers
                            if ("pull".equalsIgnoreCase(effectiveStreamingMode))
                            {
                                propVal = toStringList(pushFormats) + "," + toStringList(pullFormats);
                            }
                            else
                            {
                                propVal = toStringList(pullFormats);
                            }
                        }
                    }
                    else if (propName != null
                            && perPlayerConstraintProperties.containsKey(propName))
                        {
                        propVal = perPlayerConstraintProperties.get(propName);
                        }
                        else if (isCapSchemaV2Enabled()
                            && propName != null
                            && (propName.startsWith("EXO_") || propName.startsWith("IJK_"))
                            && perPlayerCapabilities.containsKey(propName))
                    {
                        // Phase 3: per-player honest capability advertisement.
                        // Legacy 9.2.x servers never query these property names
                        // (they don't know about them), so this branch is dormant
                        // when isLegacyServerCompat() is true. NG servers can opt
                        // in to consume EXO_/IJK_ split lists for honest profile
                        // matching at OPENURL time.
                        //
                        // Per the NG server contract (post-e66136e4): return an
                        // EMPTY STRING (not "NONE") when the list is empty so
                        // the server falls back to the union VIDEO_CODECS /
                        // AUDIO_CODECS / PUSH_AV_CONTAINERS / PULL_AV_CONTAINERS
                        // properties for that key. Returning "NONE" would be
                        // parsed as a literal codec name and break fallback.
                        List<String> list = perPlayerCapabilities.get(propName);
                        propVal = (list == null || list.isEmpty()) ? "" : toStringList(list);
                    }
                    else if ("MINICLIENT_DEFAULT_PLAYER".equals(propName))
                    {
                        // Phase 3 hint for NG servers: user's preferred default
                        // player. This is advisory only; server must not treat it
                        // as a hard constraint, and client may still override at
                        // OPENURL time for runtime decode safety.
                        // Legacy servers don't query this property.
                        String dp = client.properties().getString(PrefStore.Keys.default_player, "exoplayer");
                        propVal = normalizePlayerToken(dp);
                    }
                    else if ("MEDIA_PLAYER_BUFFER_DELAY".equals(propName))
                    {
                        // MPlayer needs an extra 2 seconds of buffer before it
                        // can do playback because of it's single-threaded
                        // nature
                        // NOTE: If MPlayer is not being used, this should be
                        // changed...hopefully to a lower value like 0 :)
                        propVal = "0";
                    }
                    else if ("FIXED_PUSH_MEDIA_FORMAT".equals(propName))
                    {
                        // Legacy fail-safe: only honor explicit user pinning.
                        // Implicit/auto fixed recipes can cause some 9.2.x servers
                        // to emit an empty push URL (openURL0(push:)).
                        if (isLegacyServerCompat()
                                && !client.properties().getFixedEncodingPreference().equalsIgnoreCase("always"))
                        {
                            propVal = "";
                        }
                        else
                        {
                        // Legacy 9.2.x auto-promote: if no user-pinned recipe, synthesize
                        // a device-aware one so the legacy server doesn't fall back to
                        // its hardcoded MPEG-4 ASP + MP2 + MPEG-PS default. See
                        // buildLegacyDeviceAwarePushFormat() javadoc for the full rationale.
                        String legacyFmt = buildLegacyDeviceAwarePushFormat(effectiveStreamingMode);
                        if (legacyFmt != null)
                        {
                            propVal = legacyFmt;
                        }
                        else if ("fixed".equalsIgnoreCase(effectiveStreamingMode))
                        {
                            String format = client.properties().getFixedEncodingContainerFormat();
                            
                            /* Video properties */
                            int videobitrate = client.properties().getFixedEncodingVideoBitrateKBPS() * 1000;

                            String framerate = client.properties().getFixedEncodingFPS();

                            int keyFrameInt = client.properties().getFixedEncodingKeyFrameInterval();

                            boolean useBFrames = client.properties().getFixedEncodingUseBFrames();

                            //TODO: Investigate why this is set as 0.  Maybe set as a property.  Could possibly expose to end user at some point
                            int bframeInterval= 0;

                            String resolution = client.properties().getFixedEncodingVideoResolution();
    
                            /* Audio properties */
                            String audioCodec = client.properties().getFixedEncodingAudioCodec();

                            int audiobitrate = client.properties().getFixedEncodingAudioBitrateKBPS() * 1000;

                            String audiochannels = client.properties().getFixedEncodingAudioChannels();
                            
                            // Build the fixed media format string
                            propVal = "container=" + format + ";";
                            propVal += "videobitrate=" + videobitrate + ";";
                            
                            if(!framerate.equalsIgnoreCase("SOURCE"))
                            {
                                int fps = 30;
                                
                                try
                                {
                                    fps = Math.round(Float.parseFloat(framerate));
                                }
                                catch (Exception ex) {}
                                
                                propVal += "gop=" + (fps * keyFrameInt) + ";";
                                
                                propVal += "fps=" + framerate + ";";
                            }
                            else
                            {
                                propVal += "fps=" + framerate + ";";
                            }
    
                            if(useBFrames)
                            {
                                propVal += "bframes=" + bframeInterval + ";";
                            }
    
                            propVal += "resolution=" + resolution + ";";
                            

                            if(!audioCodec.equalsIgnoreCase(""))
                            {
                                propVal += "audiocodec=" + audioCodec + ";";
                                propVal += "audiobitrate=" + audiobitrate + ";";
                                
                                if(!audiochannels.equalsIgnoreCase(""))
                                {
                                    propVal += "audiochannels=" + audiochannels + ";";
                                }
                            }
                        }
                        else
                        {
                            propVal = "";
                        }
                        }
                    }
                    else if ("FIXED_PUSH_REMUX_FORMAT".equals(propName))
                    {
                        // Legacy fail-safe: only honor explicit user pinning.
                        // Keep remux hint empty unless user asked for fixed remuxing.
                        if (isLegacyServerCompat()
                                && !client.properties().getFixedRemuxingPreference().equalsIgnoreCase("always"))
                        {
                            propVal = "";
                        }
                        else
                        {
                        // Legacy 9.2.x auto-promote: device-aware remux container so
                        // HEVC / H.264 sources that just need a container wrap get
                        // remuxed instead of re-transcoded by the legacy server's default.
                        String legacyRemux = buildLegacyDeviceAwareRemuxFormat(effectiveStreamingMode);
                        if (legacyRemux != null)
                        {
                            propVal = legacyRemux;
                        }
                        //If we are using fixed streaming mode and
                        else if ("fixed".equalsIgnoreCase(effectiveStreamingMode))
                        {
                            if(!client.properties().getFixedRemuxingPreference().equalsIgnoreCase("off"))
                            {
                                propVal = "container=" + client.properties().getFixedRemuxingFormat() + ";videocodec=COPY;audiocodec=COPY;";
                            }
                        }
                        else
                        {
                            // NG path safety-net: prevent the server's PlaybackDecisionEngine
                            // from falling back to its legacy DVD-default transcode
                            // (MPEG-2 720x480 + MP2) when it misfires on an MP4/HEVC source.
                            // PULL_DIRECT_PLAY-preferring NG servers will simply ignore this
                            // hint; only the PUSH path consults it. See
                            // buildNgSafetyRemuxFormat() javadoc.
                            String ngRemux = buildNgSafetyRemuxFormat(effectiveStreamingMode);
                            if (ngRemux != null)
                            {
                                propVal = ngRemux;
                            }
                        }
                        }
                    }
                    else if ("CRYPTO_ALGORITHMS".equals(propName))
                    {
                        propVal = client.getCryptoFormats();
                    }
                    else if ("CRYPTO_SYMMETRIC_KEY".equals(propName))
                    {
                        if (serverPublicKey != null && encryptedSecretKeyBytes == null)
                        {
                            if (currentCrypto.indexOf("RSA") != -1)
                            {
                                // We have to generate our secret key and then
                                // encrypt it with the server's public key
                                javax.crypto.KeyGenerator keyGen = javax.crypto.KeyGenerator.getInstance("Blowfish");
                                mySecretKey = keyGen.generateKey();
                                evtEncryptCipher = javax.crypto.Cipher.getInstance("Blowfish");
                                evtEncryptCipher.init(javax.crypto.Cipher.ENCRYPT_MODE, mySecretKey);

                                byte[] rawSecretBytes = mySecretKey.getEncoded();
                                try
                                {
                                    javax.crypto.Cipher encryptCipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding");
                                    encryptCipher.init(javax.crypto.Cipher.ENCRYPT_MODE, serverPublicKey);
                                    encryptedSecretKeyBytes = encryptCipher.doFinal(rawSecretBytes);
                                }
                                catch (Exception e)
                                {
                                    log.logError("Error encrypting data to submit to server", e);
                                }
                            }
                            else
                            {
                                // We need to finish the DH key agreement and
                                // generate the shared secret key
                                /*
                                 * Bob gets the DH parameters associated with
								 * Alice's public key. He must use the same
								 * parameters when he generates his own key
								 * pair.
								 */
                                javax.crypto.spec.DHParameterSpec dhParamSpec = ((javax.crypto.interfaces.DHPublicKey) serverPublicKey).getParams();

                                // Bob creates his own DH key pair
                                log.logDebug("Generate DH keypair ...");
                                java.security.KeyPairGenerator bobKpairGen = java.security.KeyPairGenerator.getInstance("DH");
                                bobKpairGen.initialize(dhParamSpec);
                                java.security.KeyPair bobKpair = bobKpairGen.generateKeyPair();

                                // Bob creates and initializes his DH
                                // KeyAgreement object
                                javax.crypto.KeyAgreement bobKeyAgree = javax.crypto.KeyAgreement.getInstance("DH");
                                bobKeyAgree.init(bobKpair.getPrivate());

                                // Bob encodes his public key, and sends it over
                                // to Alice.
                                encryptedSecretKeyBytes = bobKpair.getPublic().getEncoded();

                                // We also have to generate the shared secret
                                // now
                                bobKeyAgree.doPhase(serverPublicKey, true);
                                mySecretKey = bobKeyAgree.generateSecret("DES");
                                evtEncryptCipher = javax.crypto.Cipher.getInstance("DES/ECB/PKCS5Padding");
                                evtEncryptCipher.init(javax.crypto.Cipher.ENCRYPT_MODE, mySecretKey);
                            }
                        }
                        propValBytes = encryptedSecretKeyBytes;
                    }
                    else if ("GFX_SUPPORTED_RESOLUTIONS".equals(propName))
                    {
                        Dimension winny = myGfx.getScreenSize();
                        if (winny != null)
                        {
                            propVal = Integer.toString(winny.width) + "x" + Integer.toString(winny.height) + ";windowed";
                        }
                    }
                    else if ("GFX_FIXED_PAR".equals(propName))
                    {
                        // note: tels sagetv to go into iphone mode which enables httpls
                        if (client.properties().getBoolean(PrefStore.Keys.use_httpls, false))
                        {
                            propVal = "0.0";
                        }
                        else
                        {
                            propVal = "";
                        }
                        // propVal = "";
                    }
                    else if ("GFX_RESOLUTION".equals(propName))
                    {
                        Dimension winny = myGfx.getScreenSize();
                        if (winny != null)
                        {
                            propVal = Integer.toString(winny.width) + "x" + Integer.toString(winny.height);
                        }
                    }
                    else if ("GFX_DRAWMODE".equals(propName))
                    {
                        propVal = profileProperties.getProperty("GFX_DRAWMODE","FULLSCREEN");
                    }
                    else if ("VIDEO_ADVANCED_ASPECT".equals(propName))
                    {
                        if (client.options().isUsingAdvancedAspectModes())
                        {
                            propVal=client.options().getDefaultAdvancedAspectMode();
                        }
                        else
                        {
                            propVal="";
                        }
                    }
                    else if ("VIDEO_ADVANCED_ASPECT_LIST".equals(propName))
                    {
                        if (client.options().isUsingAdvancedAspectModes())
                        {
                            propVal=client.options().getAdvancedApectModes();
                        }
                        else
                        {
                            propVal="";
                        }
                    }
                    else if ("NG_PLAYBACK_CONTEXT_SUPPORTED".equals(propName))
                    {
                        propVal = "TRUE";
                    }

                    if (propVal==null||propVal.isEmpty() && profileProperties!=null)
                    {
                        propVal = profileProperties.getProperty(propName, propVal);
                    }

                    log.logDebug("GetProperty: " + propName + "=" + propVal);

                    try
                    {
                        synchronized (eventChannel)
                        {
                            if (propValBytes == null)
                            {
                                propValBytes = propVal.getBytes(MiniClient.BYTE_CHARSET);
                            }

                            eventChannel.write(GET_PROPERTY_CMD_TYPE); // get
                            // property
                            // reply
                            eventChannel.write((propValBytes.length >> 16) & 0xFF);
                            eventChannel.write((propValBytes.length >> 8) & 0xFF);
                            eventChannel.write(propValBytes.length & 0xFF);// 3
                            // byte
                            // length
                            eventChannel.writeInt(0); // timestamp
                            eventChannel.writeInt(replyCount++);
                            eventChannel.writeInt(0); // pad
                            if (propValBytes.length > 0)
                            {
                                if (encryptEvents && evtEncryptCipher != null)
                                {
                                    eventChannel.write(evtEncryptCipher.doFinal(propValBytes));
                                }
                                else
                                {
                                    eventChannel.write(propValBytes);
                                }
                            }
                            eventChannel.flush();
                        }
                    }
                    catch (Exception e)
                    {
                        eventChannelError();
                    }
                }
                else if (command == SET_PROPERTY_CMD_TYPE) // set property
                {
                    short nameLen = (short) (((cmdbuffer[0] & 0xFF) << 8) | (cmdbuffer[1] & 0xFF));
                    short valLen = (short) (((cmdbuffer[2] & 0xFF) << 8) | (cmdbuffer[3] & 0xFF));
                    String propName = new String(cmdbuffer, 4, nameLen);
                    // String propVal = new String(cmdbuffer, 4 + nameLen,
                    // valLen);
                    String propVal = null;

                    synchronized (eventChannel)
                    {
                        boolean encryptThisReply = encryptEvents;

                        if ("CRYPTO_PUBLIC_KEY".equals(propName))
                        {
                            byte[] keyBytes = new byte[valLen];
                            System.arraycopy(cmdbuffer, 4 + nameLen, keyBytes, 0, valLen);
                            java.security.spec.X509EncodedKeySpec pubKeySpec = new java.security.spec.X509EncodedKeySpec(keyBytes);
                            java.security.KeyFactory keyFactory;
                            if (currentCrypto.indexOf("RSA") != -1)
                                keyFactory = java.security.KeyFactory.getInstance("RSA");
                            else
                                keyFactory = java.security.KeyFactory.getInstance("DH");
                            serverPublicKey = keyFactory.generatePublic(pubKeySpec);
                            retval = 0;
                        }
                        else if ("CRYPTO_ALGORITHMS".equals(propName))
                        {
                            currentCrypto = new String(cmdbuffer, 4 + nameLen, valLen);
                            propVal = currentCrypto;
                            retval = 0;
                        }
                        else if ("CRYPTO_EVENTS_ENABLE".equals(propName))
                        {
                            if ("TRUE".equalsIgnoreCase(new String(cmdbuffer, 4 + nameLen, valLen)))
                            {
                                if (evtEncryptCipher != null)
                                {
                                    encryptEvents = true;
                                    retval = 0;
                                }
                                else
                                {
                                    encryptEvents = false;
                                    retval = 1;
                                }
                            }
                            else
                            {
                                encryptEvents = false;
                                retval = 0;
                            }
                            log.logDebug("SageTVPlaceshifter event encryption is now: " + encryptEvents);
                        }
                        else if ("GFX_RESOLUTION".equals(propName))
                        {
                            propVal = new String(cmdbuffer, 4 + nameLen, valLen);
                            // NOTE: These resolution changes need to be done on
                            // the AWT thread because if we're disposing
                            // a window then that may invoke on AWT which could
                            // block against an event coming back in
                            if ("FULLSCREEN".equals(propVal))
                            {
                                uiRenderer.invokeLater(new Runnable()
                                {
                                    public void run() {
                                        myGfx.getWindow().setFullScreen(true);
                                    }
                                });
                            } else if ("WINDOW".equals(propVal))
                            {
                                uiRenderer.invokeLater(new Runnable()
                                {
                                    public void run() {
                                        myGfx.getWindow().setFullScreen(false);
                                    }
                                });
                            }
                            else
                            {
                                int xidx = propVal.indexOf('x');
                                if (xidx != -1)
                                {
                                    try
                                    {
                                        int w = Integer.parseInt(propVal.substring(0, xidx));
                                        int h = Integer.parseInt(propVal.substring(xidx + 1));
                                        myGfx.getWindow().setSize(w, h);
                                    }
                                    catch (Exception e)
                                    {
                                        e.printStackTrace();
                                    }
                                }
                            }
                            retval = 0;
                        }
                        else if ("GFX_FONTSERVER".equals(propName))
                        {
                            if ("TRUE".equalsIgnoreCase(new String(cmdbuffer, 4 + nameLen, valLen)))
                            {
                                fontServer = true;
                                retval = 0;
                            }
                            else
                            {
                                fontServer = false;
                                retval = 0;
                            }
                            propVal = String.valueOf(fontServer);
                        }
                        else if ("ZLIB_COMM_XFER".equals(propName))
                        {
                            zipMode = "TRUE".equalsIgnoreCase(new String(cmdbuffer, 4 + nameLen, valLen));
                            propVal = String.valueOf(zipMode);
                            retval = 0;
                        }
                        else if ("ADVANCED_IMAGE_CACHING".equals(propName))
                        {
                            usesAdvancedImageCaching = "TRUE".equalsIgnoreCase(new String(cmdbuffer, 4 + nameLen, valLen));
                            propVal = String.valueOf(usesAdvancedImageCaching);
                            retval = 0;
                        }
                        else if ("RECONNECT_SUPPORTED".equals(propName))
                        {
                            reconnectAllowed = "TRUE".equalsIgnoreCase(new String(cmdbuffer, 4 + nameLen, valLen));
                            propVal = String.valueOf(reconnectAllowed);
                            retval = 0;
                        }
                        else if ("SUBTITLES_CALLBACKS".equals(propName))
                        {
                            subSupport = "TRUE".equalsIgnoreCase(new String(cmdbuffer, 4 + nameLen, valLen));
                            propVal = String.valueOf(subSupport);
                            retval = 0;
                        }
                        else if ("MENU_HINT".equals(propName))
                        {
                            propVal = new String(cmdbuffer, 4 + nameLen, valLen);
                            menuHint.update(propVal);
                            log.logDebug("Setting MENU_HINT: " + menuHint);
                            if (getUiRenderer() != null)
                            {
                                getUiRenderer().onMenuHint(menuHint);
                            }
                            retval = 0;
                        }
                        else if ("GFX_ASPECT".equals(propName))
                        {
                            propVal = new String(cmdbuffer, 4 + nameLen, valLen);
                            try
                            {
                                getUiRenderer().setUIAspectRatio(Float.parseFloat(propVal));
                            }
                            catch (Throwable t)
                            {
                                log.logError("Failed to set UI ASPECT of " + propVal, t);
                            }
                            retval = 0;
                        }
                        else if ("VIDEO_ADVANCED_ASPECT".equals(propName))
                        {
                            propVal = new String(cmdbuffer, 4 + nameLen, valLen);
                            retval = 0;
                            if (uiRenderer!=null)
                            {
                                uiRenderer.setVideoAdvancedAspect(propVal);
                            }
                        }
                        else if ("NG_PLAYBACK_CONTEXT".equals(propName))
                        {
                            propVal = new String(cmdbuffer, 4 + nameLen, valLen);
                            log.logDebug("Received NG_PLAYBACK_CONTEXT: " + propVal);
                            if (playbackContextStore != null)
                            {
                                playbackContextStore.onPropertyReceived(propVal);
                            }
                            retval = 0;
                        }
                        else if ("SET_CACHED_AUTH".equals(propName))
                        {
                            // Save this authentication block in the properties
                            // file
                            // First we need to decrypt it with the symmetric
                            // key
                            if (evtEncryptCipher != null && msi != null)
                            {
                                javax.crypto.Cipher decryptCipher = javax.crypto.Cipher.getInstance(evtEncryptCipher.getAlgorithm());
                                decryptCipher.init(javax.crypto.Cipher.DECRYPT_MODE, mySecretKey);
                                String newAuth = new String(decryptCipher.doFinal(cmdbuffer, 4 + nameLen, valLen));

                                log.logDebug("SET_CACHED_AUTH: " + newAuth);

                                
                                if (msi != null)
                                {
                                    msi.setAuthBlock(newAuth);
                                    msi.save(client.properties());
                                }
                            }
                            retval = 0;
                        }
                        else if ("CAP_EFFECTIVE_PROFILE".equals(propName))
                        {
                            // NG server informs us which client profile its
                            // resolver picked (e.g. android_modern, hd_legacy_strict).
                            // We don't act on it today — the server already applied
                            // the profile to its codec/container decisions. Logged
                            // so we can confirm in field traces which profile a given
                            // session resolved to.
                            // TODO: remove (or gate behind BuildConfig.DEBUG) when
                            // shipping production / non-debug builds.
                            propVal = new String(cmdbuffer, 4 + nameLen, valLen);
                            log.logInfo("Server resolved client profile: CAP_EFFECTIVE_PROFILE=" + propVal);
                            retval = 0;
                        }
                        else if ("CAP_EFFECTIVE_PLAYER".equals(propName))
                        {
                            // Optional NG server hint for selected player path.
                            // Advisory only: we do not rewrite user preference and
                            // local runtime decoder checks remain authoritative.
                            propVal = new String(cmdbuffer, 4 + nameLen, valLen);
                            String normalized = normalizePlayerToken(propVal);
                            serverEffectivePlayerHint = normalized;
                            if (normalized.isEmpty())
                            {
                                log.logInfo("Server provided empty/unknown CAP_EFFECTIVE_PLAYER='" + propVal
                                        + "' (advisory hint cleared)");
                            }
                            else
                            {
                                log.logInfo("Server resolved player hint: CAP_EFFECTIVE_PLAYER=" + normalized
                                        + " (advisory only; runtime decode checks may override)");
                            }
                            retval = 0;
                        }
                        else if ("CMD_DOWNLOAD_REQUEST".equals(propName)
                                || "TRANSFER_SESSION_ACK".equals(propName))
                        {
                            propVal = new String(cmdbuffer, 4 + nameLen, valLen);
                            String type = extractJsonString(propVal, "type");
                            if ("TRANSFER_SESSION_ACK".equals(type)
                                    || "TRANSFER_SESSION_ACK".equals(propName))
                            {
                                DownloadRequest req = parseTransferSessionAck(propVal);
                                if (req != null)
                                {
                                    log.logInfo(propName + " TRANSFER_SESSION_ACK received: " + req);
                                    client.eventbus().post(new DownloadRequestEvent(req));
                                }
                                else
                                {
                                    log.logError(propName + ": failed to parse ACK payload", null);
                                }
                            }
                            else
                            {
                                String retriable = extractJsonRawValue(propVal, "retriable");
                                if ("TRANSFER_SESSION_ERROR".equals(type))
                                {
                                    String mediaFileID = extractJsonString(propVal, "recording_id");
                                    if (mediaFileID == null || mediaFileID.isEmpty()) {
                                        mediaFileID = extractJsonString(propVal, "mediaFileID");
                                    }
                                    String correlationId = extractJsonString(propVal, "correlationId");
                                    String errorCode = extractJsonString(propVal, "error_code");
                                    String message = extractJsonString(propVal, "message");
                                    boolean retriableBool = "true".equalsIgnoreCase(retriable);
                                    client.eventbus().post(new DownloadTransferSessionErrorEvent(
                                            mediaFileID,
                                            correlationId,
                                            errorCode,
                                            message,
                                            retriableBool));
                                    log.logWarning(propName + " TRANSFER_SESSION_ERROR code=" + errorCode
                                            + " retriable=" + retriable
                                            + " corr=" + correlationId
                                            + " mediaFileID=" + mediaFileID);
                                }
                                else
                                {
                                    log.logWarning(propName + " ignored type=" + type + " retriable=" + retriable);
                                }
                            }
                            retval = 0;
                        }
                        else if ("CMD_TRANSFER_PAUSE".equals(propName)
                                || "CMD_TRANSFER_RESUME".equals(propName)
                                || "CMD_TRANSFER_CANCEL".equals(propName))
                        {
                            propVal = new String(cmdbuffer, 4 + nameLen, valLen);
                            DownloadTransferControlEvent.Action action = "CMD_TRANSFER_PAUSE".equals(propName)
                                    ? DownloadTransferControlEvent.Action.PAUSE
                                    : ("CMD_TRANSFER_RESUME".equals(propName)
                                    ? DownloadTransferControlEvent.Action.RESUME
                                    : DownloadTransferControlEvent.Action.CANCEL);
                            String sessionToken = extractJsonString(propVal, "session_token");
                            String mediaFileID = extractJsonString(propVal, "recording_id");
                            String downloadUrl = extractJsonString(propVal, "download_url");
                            String sessionState = extractJsonString(propVal, "session_state");
                            long bytesTransferred = extractJsonLong(propVal, "bytes_transferred");
                            client.eventbus().post(new DownloadTransferControlEvent(
                                    action,
                                    sessionToken,
                                    mediaFileID,
                                    downloadUrl,
                                    bytesTransferred,
                                    sessionState));
                            log.logInfo("" + propName + " received sessionToken="
                                    + redactForLog(sessionToken)
                                    + " mediaFileID=" + (mediaFileID == null ? "" : mediaFileID)
                                    + " state=" + (sessionState == null ? "" : sessionState)
                                    + " bytesTransferred=" + bytesTransferred);
                            retval = 0;
                        }
                        else if ("CMD_OFFLINE_GUIDE_SNAPSHOT".equals(propName))
                        {
                            propVal = new String(cmdbuffer, 4 + nameLen, valLen);
                            client.eventbus().post(new OfflineGuideSnapshotEvent(propVal));
                            log.logInfo("CMD_OFFLINE_GUIDE_SNAPSHOT received (bytes=" + valLen + ")");
                            retval = 0;
                        }
                        else if ("CMD_OFFLINE_SCHED_SNAPSHOT".equals(propName))
                        {
                            propVal = new String(cmdbuffer, 4 + nameLen, valLen);
                            client.eventbus().post(new OfflineScheduleSnapshotEvent(propVal));
                            log.logInfo("CMD_OFFLINE_SCHED_SNAPSHOT received (bytes=" + valLen + ")");
                            retval = 0;
                        }
                        else if ("SAGETV_NG_SERVER".equals(propName))
                        {
                            // NG server self-declaration. Emitted by NG-aware
                            // server builds during the initial post-auth property
                            // exchange (well before any media OPENURL), so
                            // promoting AUTO → NG here takes effect for this
                            // session's first stream.
                            //
                            // Stock SageTV 9.2.x servers never emit this
                            // property; absence implies LEGACY (which is now the
                            // AUTO default — see isLegacyServerCompat()).
                            //
                            // Value semantics: "1" / "true" (case-insensitive)
                            // = NG. Anything else is ignored (treated as no
                            // declaration, AUTO default stays).
                            propVal = new String(cmdbuffer, 4 + nameLen, valLen);
                            boolean isNg = "1".equals(propVal) || "true".equalsIgnoreCase(propVal);
                            if (isNg && msi != null && msi.legacyMode == ServerInfo.LegacyMode.AUTO)
                            {
                                log.logInfo("Server '" + msi.name + "' self-declared SAGETV_NG_SERVER=" + propVal
                                        + "; promoting AUTO → NG and persisting.");
                                msi.legacyMode = ServerInfo.LegacyMode.NG;
                                try
                                {
                                    msi.save(client.properties());
                                }
                                catch (Throwable t)
                                {
                                    log.logError("Failed to persist SAGETV_NG_SERVER promotion for '" + msi.name + "'", t);
                                }
                            }
                            else
                            {
                                log.logInfo("Received SAGETV_NG_SERVER=" + propVal
                                        + " (msi.legacyMode=" + (msi != null ? msi.legacyMode : "null")
                                        + ") — no state change.");
                            }
                            retval = 0;
                        }
                        else
                        {
                            retval = 0; // or the error code if it failed the
                        }

                        // set
                        retbuf[0] = (byte) ((retval >> 24) & 0xFF);
                        retbuf[1] = (byte) ((retval >> 16) & 0xFF);
                        retbuf[2] = (byte) ((retval >> 8) & 0xFF);
                        retbuf[3] = (byte) ((retval >> 0) & 0xFF);
                        try
                        {
                            eventChannel.write(SET_PROPERTY_CMD_TYPE); // set
                            // property
                            // reply
                            eventChannel.write(0);
                            eventChannel.writeShort(4);// 3 byte length of 4
                            eventChannel.writeInt(0); // timestamp
                            eventChannel.writeInt(replyCount++);
                            eventChannel.writeInt(0); // pad
                            if (encryptThisReply)
                            {
                                eventChannel.write(evtEncryptCipher.doFinal(retbuf, 0, 4));
                            }
                            else
                            {
                                eventChannel.write(retbuf, 0, 4);
                            }
                            eventChannel.flush();
                        }
                        catch (Exception e)
                        {
                            eventChannelError();
                        }
                    }
                    log.logDebug("SetProperty " + propName + "=" + ((propVal == null) ? "(WAS_NULL)" : propVal));

                }
                else if (command == FS_CMD_TYPE)
                {
                    command = (cmdbuffer[0] & 0xFF);
                    processFSCmd(command, len, cmdbuffer);
                }

                // Remove whatever we just processed
                synchronized (gfxSyncVector)
                {
                    gfxSyncVector.remove(0);
                    gfxSyncVector.remove(0);
                    gfxSyncVector.notifyAll();
                }
            }
        }
        catch (Throwable e)
        {
            log.logError("Error w/ GFX Thread", e);
        }
        finally
        {
            try
            {
                gfxIs.close();
            }
            catch (Exception e) { }

            try
            {
                eventChannel.close();
            }
            catch (Exception e) { }

            try
            {
                gfxSocket.close();
            }
            catch (Exception e) { }

            if (alive)
            {
                connectionError();
            }
        }
    }

    private String toStringList(List<String> list)
    {
        StringBuilder sb = new StringBuilder();
        for (String s: list)
        {
            if (sb.length()>0)
            {
                sb.append(",");
            }
            sb.append(s);
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------
    // Device-aware LEGACY (SageTV 9.2.x Placeshifter) capability sets.
    //
    // Each *_UNIVERSE array is the verbatim original Placeshifter list as
    // sourced from the pre-codec-detection era of MiniClientConnection
    // (commit eec5131 etc.). The order matters: the 9.2.x dynamic-profile
    // resolver biases its codec/container picker based on the order our
    // client lists them. We preserve order strictly and only *remove*
    // tokens for codecs/containers the device cannot actually handle.
    //
    // Why this matters: on devices without an MPEG-2 hardware decoder
    // (Samsung Fold, Galaxy Tab, Pixel, etc.), advertising MPEG2-VIDEO
    // tells the 9.2.x server "send raw MPEG-2" — the client then has no
    // way to render it (silent video). Likewise advertising MPEG4-VIDEO
    // when the device can't decode MPEG-4 Part 2 lets the resolver pick
    // a transcode target it shouldn't, and even on devices that *can*
    // decode it, MPEG-4 Part 2 sits ahead of H.264 in the universe so
    // the resolver picks the lower-quality target. Dropping MPEG-4 from
    // the advertisement on devices where it isn't a true HW path bumps
    // the resolver onto H.264, which is dramatically higher quality at
    // the same bitrate.
    //
    // Devices WITH MPEG-2 HW (Shield TV etc.) keep MPEG-2 first → server
    // direct-plays raw OTA → best possible quality, no regression.
    //
    // For the NG case we send the full HW-detected list as before; this
    // helper is only consulted from the LEGACY branches.
    // -----------------------------------------------------------------

    // Order matters: SageTV's dynamic-profile resolver biases toward
    // earlier tokens in this list. We put MPEG-2 first so devices with
    // MPEG-2 HW (Shield) get raw direct play, then modern codecs
    // (H.264 / HEVC / VC1) so devices WITHOUT MPEG-2 HW (Fold/Tab) get
    // an H.264 transcode rather than MPEG-4 Part 2 ASP. MPEG4-VIDEO and
    // friends sit at the back as last-resort fallbacks.
    private static final String[] LEGACY_VIDEO_UNIVERSE = {
            "MPEG2-VIDEO", "MPEG2-VIDEO@HL", "MPEG1-VIDEO",
            "H.264", "HEVC", "VC1", "WMV9",
            "MPEG4-VIDEO", "DIVX3", "MSMPEG4", "FLASHVIDEO", "MJPEG"
    };

    private static final String[] LEGACY_AUDIO_UNIVERSE = {
            "MPG1L2", "MPG1L3", "AC3", "AAC", "AAC-HE",
            "WMA", "FLAC", "VORBIS", "PCM", "DTS", "DCA",
            "PCM_S16LE", "WMA8", "ALAC", "WMAPRO", "0X0162",
            "DolbyTrueHD", "DTS-HD", "DTS-MA", "EAC3", "EC-3"
    };

    private static final String[] LEGACY_PUSH_UNIVERSE = {
            "MPEG2-PS", "MPEG2-TS", "MPEG1-PS"
    };

    private static final String[] LEGACY_PULL_UNIVERSE = {
            "AVI", "FLASHVIDEO", "Quicktime", "Ogg", "MP3", "AAC",
            "WMV", "ASF", "FLAC", "MATROSKA", "WAV", "AC3"
    };

    /**
     * Intersects a Placeshifter universe with the device's HW-detected
     * token list, preserving the universe's ordering. Case-insensitive.
     *
     * <p>If {@code deviceTokens} is null/empty (codec detection didn't
     * run yet) or the intersection comes out empty (would brick the
     * server), we fall back to the full universe so the server has
     * <em>something</em> to try. Both paths log a warning so the
     * fallback is visible in diagnostics.</p>
     *
     * @param label         category name for log lines (e.g. "VIDEO_CODECS")
     * @param universe      original Placeshifter token list
     * @param deviceTokens  the matching field on this connection
     *                      ({@code videoCodecs}, {@code audioCodecs}, etc.)
     * @return comma-separated token string suitable for SageTV propVal
     */
    /**
     * Phase B (stream-copy HD remux) gate. True when the user has pinned
     * the connected server to {@code StreamingModeOverride.FIXED} AND the
     * server is on the legacy 9.2.x compat path. When true, four capability
     * advertisements are forced to satisfy the upstream prerequisites for
     * {@code prefTranscodeMode = "mpeg2psremux"} (pure stream copy):
     * <ol>
     *   <li>{@code INPUT_DEVICES} = "IR,KEYBOARD" (no MOUSE) -&gt; server's
     *       {@code isMediaExtender()} returns true</li>
     *   <li>{@code VIDEO_CODECS} force-includes "MPEG2-VIDEO@HL" -&gt;
     *       {@code clientCanDoMPEGHD} = true</li>
     *   <li>{@code PUSH_AV_CONTAINERS} force-includes "MPEG2-PS" -&gt;
     *       {@code clientDoesMPEG2Push} = true</li>
     *   <li>{@code PULL_AV_CONTAINERS} = "" -&gt; forces PUSH path so the
     *       "only container unsupported" branch fires and selects
     *       mpeg2psremux instead of dynamic 352x240 transcode.</li>
     * </ol>
     * See docs/hdhr-delivery-analysis-v3.md for full provenance.
     */
    private boolean isPhaseBExtenderRemuxActive(String effectiveStreamingMode)
    {
        return isLegacyServerCompat() && "fixed".equalsIgnoreCase(effectiveStreamingMode);
    }

    /**
     * Ensures {@code token} appears in a comma-separated value list.
     * Case-insensitive presence check; appends if missing. Handles
     * null/empty CSV by returning {@code token} alone.
     */
    private static String ensureCsvToken(String csv, String token)
    {
        if (token == null || token.isEmpty()) return csv;
        if (csv == null || csv.isEmpty()) return token;
        String lcToken = token.toLowerCase(java.util.Locale.ROOT);
        for (String part : csv.split(","))
        {
            if (part.trim().toLowerCase(java.util.Locale.ROOT).equals(lcToken)) return csv;
        }
        return csv + "," + token;
    }

    /**
     * Removes {@code token} (case-insensitive, exact match) from a CSV
     * list, preserving the order of remaining tokens. Returns the
     * original string unchanged if the token is absent.
     */
    private static String removeCsvToken(String csv, String token)
    {
        if (csv == null || csv.isEmpty() || token == null || token.isEmpty()) return csv;
        String lcToken = token.toLowerCase(java.util.Locale.ROOT);
        StringBuilder out = new StringBuilder();
        for (String part : csv.split(","))
        {
            String trimmed = part.trim();
            if (trimmed.toLowerCase(java.util.Locale.ROOT).equals(lcToken)) continue;
            if (out.length() > 0) out.append(",");
            out.append(trimmed);
        }
        return out.toString();
    }

    private List<String> getLegacySourceTokens(String exoKey, String ijkKey, List<String> fallback)
    {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<String>();

        List<String> exo = perPlayerCapabilities.get(exoKey);
        if (exo != null)
        {
            for (String token : exo)
            {
                if (token == null) continue;
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) merged.add(trimmed);
            }
        }

        List<String> ijk = perPlayerCapabilities.get(ijkKey);
        if (ijk != null)
        {
            for (String token : ijk)
            {
                if (token == null) continue;
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) merged.add(trimmed);
            }
        }

        if (merged.isEmpty() && fallback != null)
        {
            for (String token : fallback)
            {
                if (token == null) continue;
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) merged.add(trimmed);
            }
        }

        return new java.util.ArrayList<String>(merged);
    }

    private String legacyAdvertise(String label, String[] universe, List<String> deviceTokens)
    {
        if (deviceTokens == null || deviceTokens.isEmpty())
        {
            log.logWarning("LEGACY " + label + ": device codec list empty; advertising full Placeshifter universe");
            return joinTokens(universe);
        }

        java.util.Set<String> deviceSet = new java.util.HashSet<String>();
        for (String t : deviceTokens)
        {
            if (t != null) deviceSet.add(t.toLowerCase(java.util.Locale.ROOT));
        }

        StringBuilder kept = new StringBuilder();
        StringBuilder dropped = new StringBuilder();
        for (String token : universe)
        {
            String lc = token.toLowerCase(java.util.Locale.ROOT);
            if (deviceSet.contains(lc))
            {
                if (kept.length() > 0) kept.append(",");
                kept.append(token);
            }
            else
            {
                if (dropped.length() > 0) dropped.append(",");
                dropped.append(token);
            }
        }

        if (kept.length() == 0)
        {
            log.logWarning("LEGACY " + label + ": intersection empty (device list=" + deviceTokens
                    + "); advertising full Placeshifter universe as fallback");
            return joinTokens(universe);
        }

        log.logInfo("LEGACY " + label + " advertise: keep=[" + kept + "] drop=[" + dropped + "]");
        return kept.toString();
    }

    private static String joinTokens(String[] arr)
    {
        StringBuilder sb = new StringBuilder();
        for (String s : arr)
        {
            if (sb.length() > 0) sb.append(",");
            sb.append(s);
        }
        return sb.toString();
    }

    public void recvCommand(int sageCommandID)
    {
        postSageCommandEvent(sageCommandID);
    }

    public void recvCommand(int sageCommandID, String payload) {
        postSageCommandEvent(sageCommandID);
    }

    public void recvCommand(int sageCommandID, String[] payloads) {
        postSageCommandEvent(sageCommandID);
    }

    public void recvInfrared(byte[] irCode) {
        int coded = 0;
        for (int i = 0; i < irCode.length; i += 4) {
            int currCoded = (irCode[i] & 0xFF) << 24;
            if (i + 1 < irCode.length)
                currCoded |= (irCode[i + 1] & 0xFF) << 16;
            if (i + 2 < irCode.length)
                currCoded |= (irCode[i + 2] & 0xFF) << 8;
            if (i + 3 < irCode.length)
                currCoded |= (irCode[i + 3] & 0xFF);
            coded = coded ^ currCoded;
        }
        postIREvent(coded);
    }

    public void recvKeystroke(char keyChar, int keyCode, int keyModifiers) {
        postKeyEvent(keyCode, keyModifiers, keyChar);
    }

    public void postIREvent(final int IRCode) {
//        if (MiniClient.irKillCode != null && MiniClient.irKillCode.intValue() == IRCode) {
//            System.out.println("IR Exit Code received...terminating");
//            close();
//            return;
//        }
        // if (myGfx != null)
        // myGfx.setHidden(false, false);
        // MiniClientPowerManagement.getInstance().kick();
        if (performingReconnect)
            return;

        if (eventRouterThread == null || eventRouterThread.queue == null) {
            return;
        }

        eventRouterThread.queue.add(new Runnable() {
            @Override
            public void run() {
                synchronized (eventChannel) {
                    try {
                        eventChannel.write(IR_EVENT_REPLY_TYPE); // ir event code
                        eventChannel.write(0);
                        eventChannel.writeShort(4);// 3 byte length of 4
                        eventChannel.writeInt(0); // timestamp
                        eventChannel.writeInt(replyCount++);
                        eventChannel.writeInt(0); // pad
                        if (encryptEvents && evtEncryptCipher != null) {
                            byte[] data = new byte[4];
                            data[0] = (byte) ((IRCode >> 24) & 0xFF);
                            data[1] = (byte) ((IRCode >> 16) & 0xFF);
                            data[2] = (byte) ((IRCode >> 8) & 0xFF);
                            data[3] = (byte) (IRCode & 0xFF);
                            eventChannel.write(evtEncryptCipher.doFinal(data));
                        } else {
                            eventChannel.writeInt(IRCode);
                        }
                        eventChannel.flush();
                    } catch (Exception e) {
                        log.logError("Error w/ event thread", e);
                        eventChannelError();
                    }
                }
            }
        });
    }

    public void postSageCommandEvent(final int sageCommand) {
        // if (myGfx != null)
        // myGfx.setHidden(false, false);
        // MiniClientPowerManagement.getInstance().kick();
        if (performingReconnect)
            return;

        if (eventRouterThread == null || eventRouterThread.queue == null) {
            return;
        }

        eventRouterThread.queue.add(new Runnable() {
            @Override
            public void run() {
                log.logDebug("Begin Sending SageTV Command: " + sageCommand);
                synchronized (eventChannel) {
                    try {
                        eventChannel.write(136); // SageTV Command event code
                        eventChannel.write(0);
                        eventChannel.writeShort(4);// 3 byte length of 4
                        eventChannel.writeInt(0); // timestamp
                        eventChannel.writeInt(replyCount++);
                        eventChannel.writeInt(0); // pad
                        if (encryptEvents && evtEncryptCipher != null) {
                            byte[] data = new byte[4];
                            data[0] = (byte) ((sageCommand >> 24) & 0xFF);
                            data[1] = (byte) ((sageCommand >> 16) & 0xFF);
                            data[2] = (byte) ((sageCommand >> 8) & 0xFF);
                            data[3] = (byte) (sageCommand & 0xFF);
                            eventChannel.write(evtEncryptCipher.doFinal(data));
                        } else {
                            eventChannel.writeInt(sageCommand);
                        }
                        eventChannel.flush();
                    } catch (Exception e) {
                        log.logError("Error w/ event thread", e);
                        eventChannelError();
                    }
                }
            }
        });
    }

    public void postKeyEvent(final int keyCode, final int keyModifiers, final char keyChar) {
        // MiniClientPowerManagement.getInstance().kick();
        if (performingReconnect)
            return;

        if (eventRouterThread == null || eventRouterThread.queue == null) {
            return;
        }

        eventRouterThread.queue.add(new Runnable() {
            @Override
            public void run() {
                synchronized (eventChannel) {
                    try {
                        eventChannel.write(KB_EVENT_REPLY_TYPE); // kb event code
                        eventChannel.write(0);
                        eventChannel.writeShort(10);// 3 byte length of 10
                        eventChannel.writeInt(0); // timestamp
                        eventChannel.writeInt(replyCount++);
                        eventChannel.writeInt(0); // pad
                        if (encryptEvents && evtEncryptCipher != null) {
                            byte[] data = new byte[10];
                            data[0] = (byte) ((keyCode >> 24) & 0xFF);
                            data[1] = (byte) ((keyCode >> 16) & 0xFF);
                            data[2] = (byte) ((keyCode >> 8) & 0xFF);
                            data[3] = (byte) (keyCode & 0xFF);
                            data[4] = (byte) ((keyChar >> 8) & 0xFF);
                            data[5] = (byte) (keyChar & 0xFF);
                            data[6] = (byte) ((keyModifiers >> 24) & 0xFF);
                            data[7] = (byte) ((keyModifiers >> 16) & 0xFF);
                            data[8] = (byte) ((keyModifiers >> 8) & 0xFF);
                            data[9] = (byte) (keyModifiers & 0xFF);
                            eventChannel.write(evtEncryptCipher.doFinal(data));
                        } else {
                            eventChannel.writeInt(keyCode);
                            eventChannel.writeChar(keyChar);
                            eventChannel.writeInt(keyModifiers);
                        }
                        eventChannel.flush();
                    } catch (Throwable e) {
                        log.logError("Error w/ event thread", e);
                        eventChannelError();
                    }
                }
            }
        });
    }

    public boolean hasEventChannel() {
        return eventChannel != null;
    }

    public String getClientId() {
        return myID;
    }

    public void postResizeEvent(Dimension size) {
        if (performingReconnect)
            return;

        if (eventChannel == null) {
            return;
        }

        // NOTE: not sure this needs to use the eventRouterThread... resize events normally happen
        // in the background thread, so I think we are safe to leave this

        synchronized (eventChannel) {
            try {
                eventChannel.write(UI_RESIZE_EVENT_REPLY_TYPE); // resize event
                // code
                eventChannel.write(0);
                eventChannel.writeShort(8);// 3 byte length of 8
                eventChannel.writeInt(0); // timestamp
                eventChannel.writeInt(replyCount++);
                eventChannel.writeInt(0); // pad
                if (encryptEvents && evtEncryptCipher != null) {
                    byte[] data = new byte[8];
                    data[0] = (byte) ((size.width >> 24) & 0xFF);
                    data[1] = (byte) ((size.width >> 16) & 0xFF);
                    data[2] = (byte) ((size.width >> 8) & 0xFF);
                    data[3] = (byte) (size.width & 0xFF);
                    data[4] = (byte) ((size.height >> 24) & 0xFF);
                    data[5] = (byte) ((size.height >> 16) & 0xFF);
                    data[6] = (byte) ((size.height >> 8) & 0xFF);
                    data[7] = (byte) (size.height & 0xFF);
                    eventChannel.write(evtEncryptCipher.doFinal(data));
                } else {
                    eventChannel.writeInt(size.width);
                    eventChannel.writeInt(size.height);
                }
                eventChannel.flush();
            } catch (Exception e) {
                log.logError("Error w/ event thread", e);
                eventChannelError();
            }
        }
    }

    public void postRepaintEvent(int x, int y, int w, int h) {
        if (performingReconnect)
            return;

        if (eventChannel == null) {
            return;
        }

        // We should be good to NOT use the eventRouterThread, since repaints happen in the background
        // thread, usually.

        synchronized (eventChannel) {
            try {
                eventChannel.write(UI_REPAINT_EVENT_REPLY_TYPE); // repaint
                // event
                // code
                eventChannel.write(0);
                eventChannel.writeShort(16);// 3 byte length of 16
                eventChannel.writeInt(0); // timestamp
                eventChannel.writeInt(replyCount++);
                eventChannel.writeInt(0); // pad
                if (encryptEvents && evtEncryptCipher != null) {
                    byte[] data = new byte[16];
                    data[0] = (byte) ((x >> 24) & 0xFF);
                    data[1] = (byte) ((x >> 16) & 0xFF);
                    data[2] = (byte) ((x >> 8) & 0xFF);
                    data[3] = (byte) (x & 0xFF);
                    data[4] = (byte) ((y >> 24) & 0xFF);
                    data[5] = (byte) ((y >> 16) & 0xFF);
                    data[6] = (byte) ((y >> 8) & 0xFF);
                    data[7] = (byte) (y & 0xFF);
                    data[8] = (byte) ((w >> 24) & 0xFF);
                    data[9] = (byte) ((w >> 16) & 0xFF);
                    data[10] = (byte) ((w >> 8) & 0xFF);
                    data[11] = (byte) (w & 0xFF);
                    data[12] = (byte) ((h >> 24) & 0xFF);
                    data[13] = (byte) ((h >> 16) & 0xFF);
                    data[14] = (byte) ((h >> 8) & 0xFF);
                    data[15] = (byte) (h & 0xFF);
                    eventChannel.write(evtEncryptCipher.doFinal(data));
                } else {
                    eventChannel.writeInt(x);
                    eventChannel.writeInt(y);
                    eventChannel.writeInt(w);
                    eventChannel.writeInt(h);
                }
                eventChannel.flush();
            } catch (Throwable e) {
                log.logError("Error w/ event thread", e);
                eventChannelError();
            }
        }
    }

    public void postImageUnload(int handle) {
        if (performingReconnect)
            return;

        if (eventChannel==null) return;

        synchronized (eventChannel) {

            try {
                eventChannel.write(IMAGE_UNLOAD_REPLY_TYPE); // repaint event
                // code
                eventChannel.write(0);
                eventChannel.writeShort(4);// 3 byte length of 16
                eventChannel.writeInt(0); // timestamp
                eventChannel.writeInt(replyCount++);
                eventChannel.writeInt(0); // pad
                if (encryptEvents && evtEncryptCipher != null) {
                    byte[] data = new byte[4];
                    data[0] = (byte) ((handle >> 24) & 0xFF);
                    data[1] = (byte) ((handle >> 16) & 0xFF);
                    data[2] = (byte) ((handle >> 8) & 0xFF);
                    data[3] = (byte) (handle & 0xFF);
                    eventChannel.write(evtEncryptCipher.doFinal(data));
                } else {
                    eventChannel.writeInt(handle);
                }
                eventChannel.flush();
            } catch (Exception e) {
                log.logError("Error w/ event thread", e);
                eventChannelError();
            }
        }
    }

    public void postOfflineCacheChange(boolean addedToCache, String rezID) {
        if (performingReconnect)
            return;

        if (eventChannel==null) return;

        synchronized (eventChannel) {
            try {
                int strlen = rezID.length();
                eventChannel.write(OFFLINE_CACHE_CHANGE_REPLY_TYPE); // repaint
                // event
                // code
                eventChannel.write(0);
                eventChannel.writeShort(5 + strlen);// 3 byte length
                eventChannel.writeInt(0); // timestamp
                eventChannel.writeInt(replyCount++);
                eventChannel.writeInt(0); // pad
                byte[] strBytes = rezID.getBytes(MiniClient.BYTE_CHARSET);
                if (encryptEvents && evtEncryptCipher != null) {
                    byte[] data = new byte[5 + strlen];
                    data[0] = (byte) (addedToCache ? 1 : 0);
                    data[1] = (byte) ((strlen >> 24) & 0xFF);
                    data[2] = (byte) ((strlen >> 16) & 0xFF);
                    data[3] = (byte) ((strlen >> 8) & 0xFF);
                    data[4] = (byte) (strlen & 0xFF);
                    System.arraycopy(strBytes, 0, data, 5, strBytes.length);
                    eventChannel.write(evtEncryptCipher.doFinal(data));
                } else {
                    eventChannel.writeByte(addedToCache ? 1 : 0);
                    eventChannel.writeInt(strlen);
                    eventChannel.write(strBytes);
                }
                eventChannel.flush();
            } catch (Exception e) {
                log.logError("Error w/ event thread", e);
                eventChannelError();
            }
        }
    }

    /**
     * Client→server hint requesting a refreshed transfer session for a
     * download whose token has expired.
     *
     * <p><strong>Status:</strong> the SageTV server-side wire format for this
     * request has not yet been agreed between client and server. The previous
     * implementation in this method wrote a malformed SET_PROPERTY packet to
     * the event channel using opcode 1, but opcode 1 is a server→client
     * command — the server's event-reply parser only accepts client→server
     * opcodes in the 128–227 range (see *_REPLY_TYPE constants above). Sending
     * opcode 1 from the client corrupts the event stream and triggers a full
     * GFX/event reconnect, which in turn desyncs the renderer's menu state.
     *
     * <p>Until a dedicated outbound reply-type is added on both sides
     * (e.g. {@code DOWNLOAD_REFRESH_REQUEST_REPLY_TYPE = 228}) and the server
     * is updated to parse it, this method intentionally does NOT touch
     * {@code eventChannel}. Callers should treat the absence of a server
     * response as "refresh not supported" rather than racing a 20s timeout.
     *
     * <p>The {@code mediaFileID}/{@code reasonCode}/{@code correlationId}
     * arguments are logged for traceability and so that callers wiring this
     * up later don't need to change their call sites.
     */
    /**
     * Client→server hint requesting a refreshed transfer session for a
     * download whose token has expired. See
     * {@link #DOWNLOAD_REFRESH_REQUEST_REPLY_TYPE} for the wire format.
     *
     * <p>The server responds asynchronously by pushing a fresh
     * CMD_DOWNLOAD_REQUEST / TRANSFER_SESSION_ACK back through the event
     * channel (new download_url + session_token), which the existing inbound
     * handler routes to {@code DownloadEventHandler → DownloadManager.enqueue
     * → mergeWithExisting}, updating sessionToken/downloadUrl/
     * transferSessionState and resuming the queue.
     *
     * <p>Historical note: an earlier revision of this method used opcode
     * {@link #SET_PROPERTY_CMD_TYPE} (a server→client command code) for the
     * send, which the server's event-reply parser rejected and which
     * triggered a full GFX/event reconnect cycle on every call. The current
     * implementation uses {@link #DOWNLOAD_REFRESH_REQUEST_REPLY_TYPE} in
     * the valid client→server reply-type range (128–255) and the same
     * canonical 16-byte header layout as {@link #postOfflineCacheChange}.
     */
    public void postDownloadRefreshRequest(String mediaFileID, String reasonCode, String correlationId) {
        postDownloadRefreshRequest(mediaFileID, reasonCode, correlationId, null, -1);
    }

    public void postDownloadRefreshRequest(String mediaFileID,
                                           String reasonCode,
                                           String correlationId,
                                           String sessionToken,
                                           long bytesTransferred) {
        if (performingReconnect)
            return;
        if (eventChannel == null || mediaFileID == null || mediaFileID.isEmpty())
            return;

        String reason = reasonCode == null ? "" : reasonCode;
        String corr = correlationId == null ? "" : correlationId;
        String token = sessionToken == null ? "" : sessionToken;
        String clientId = getClientId();
        if (clientId == null) clientId = "";
        StringBuilder payload = new StringBuilder(192);
        payload.append("{\"mediaFileID\":\"").append(escapeJson(mediaFileID)).append("\"")
                .append(",\"reason\":\"").append(escapeJson(reason)).append("\"")
                .append(",\"correlationId\":\"").append(escapeJson(corr)).append("\"");
        if (!token.isEmpty()) {
            payload.append(",\"sessionToken\":\"").append(escapeJson(token)).append("\"");
        }
        if (!clientId.isEmpty()) {
            payload.append(",\"clientId\":\"").append(escapeJson(clientId)).append("\"");
        }
        if (bytesTransferred >= 0) {
            payload.append(",\"bytesTransferred\":").append(bytesTransferred);
        }
        payload.append("}");
        String json = payload.toString();

        byte[] jsonBytes;
        try {
            jsonBytes = json.getBytes(MiniClient.BYTE_CHARSET);
        } catch (Exception e) {
            log.logError("Failed to encode DOWNLOAD_REFRESH_REQUEST payload", e);
            return;
        }

        synchronized (eventChannel) {
            try {
                eventChannel.write(DOWNLOAD_REFRESH_REQUEST_REPLY_TYPE);
                eventChannel.write(0);
                eventChannel.writeShort(jsonBytes.length);
                eventChannel.writeInt(0); // timestamp
                eventChannel.writeInt(replyCount++);
                eventChannel.writeInt(0); // pad
                if (encryptEvents && evtEncryptCipher != null) {
                    eventChannel.write(evtEncryptCipher.doFinal(jsonBytes));
                } else {
                    eventChannel.write(jsonBytes);
                }
                eventChannel.flush();
                log.logInfo("Posted DOWNLOAD_REFRESH_REQUEST mediaFileID=" + mediaFileID
                    + " reason=" + reason + " corr=" + corr
                    + " hasToken=" + (!token.isEmpty())
                    + " bytesTransferred=" + bytesTransferred);
            } catch (Exception e) {
                log.logError("Failed to post DOWNLOAD_REFRESH_REQUEST: "
                        + e.getClass().getName() + ": " + e.getMessage(), e);
                eventChannelError();
            }
        }
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public void postMouseEvent(final MouseEvent evt) {
        // MiniClientPowerManagement.getInstance().kick();
        if (performingReconnect)
            return;

        if (eventRouterThread==null || eventRouterThread.queue == null) {
            // ignore this, since we haven't fully started up yet.
            return;
        }

        eventRouterThread.queue.add(new Runnable() {
            @Override
            public void run() {
                synchronized (eventChannel) {
                    try {
                        if (evt.getID() == MouseEvent.MOUSE_CLICKED)
                            eventChannel.write(MCLICK_EVENT_REPLY_TYPE); // mouse click
                            // event
                            // code
                        else if (evt.getID() == MouseEvent.MOUSE_PRESSED)
                            eventChannel.write(MPRESS_EVENT_REPLY_TYPE); // mouse press
                            // event
                            // code
                        else if (evt.getID() == MouseEvent.MOUSE_RELEASED)
                            eventChannel.write(MRELEASE_EVENT_REPLY_TYPE); // mouse
                            // release
                            // event
                            // code
                        else if (evt.getID() == MouseEvent.MOUSE_DRAGGED)
                            eventChannel.write(MDRAG_EVENT_REPLY_TYPE); // mouse drag
                            // event code
                        else if (evt.getID() == MouseEvent.MOUSE_MOVED)
                            eventChannel.write(MMOVE_EVENT_REPLY_TYPE); // mouse move
                            // event code
                        else if (evt.getID() == MouseEvent.MOUSE_WHEEL)
                            eventChannel.write(MWHEEL_EVENT_REPLY_TYPE); // mouse wheel
                            // event
                            // code
                        else
                            return;
                        eventChannel.write(0);
                        eventChannel.writeShort(14);// 3 byte length of 14
                        eventChannel.writeInt(0); // timestamp
                        eventChannel.writeInt(replyCount++);
                        eventChannel.writeInt(0); // pad
                        if (encryptEvents && evtEncryptCipher != null) {
                            byte[] data = new byte[14];
                            data[0] = (byte) ((evt.getX() >> 24) & 0xFF);
                            data[1] = (byte) ((evt.getX() >> 16) & 0xFF);
                            data[2] = (byte) ((evt.getX() >> 8) & 0xFF);
                            data[3] = (byte) (evt.getX() & 0xFF);
                            data[4] = (byte) ((evt.getY() >> 24) & 0xFF);
                            data[5] = (byte) ((evt.getY() >> 16) & 0xFF);
                            data[6] = (byte) ((evt.getY() >> 8) & 0xFF);
                            data[7] = (byte) (evt.getY() & 0xFF);
                            data[8] = (byte) ((evt.getModifiers() >> 24) & 0xFF);
                            data[9] = (byte) ((evt.getModifiers() >> 16) & 0xFF);
                            data[10] = (byte) ((evt.getModifiers() >> 8) & 0xFF);
                            data[11] = (byte) (evt.getModifiers() & 0xFF);
                            if (evt.getID() == MouseEvent.MOUSE_WHEEL)
                                data[12] = (byte) (evt.getWheelRotation());
                            else
                                data[12] = (byte) evt.getClickCount();
                            data[13] = (byte) evt.getButton();
                            eventChannel.write(evtEncryptCipher.doFinal(data));
                        } else {
                            eventChannel.writeInt(evt.getX());
                            eventChannel.writeInt(evt.getY());
                            eventChannel.writeInt(evt.getModifiers());
                            if (evt.getID() == MouseEvent.MOUSE_WHEEL)
                                eventChannel.write(evt.getWheelRotation());
                            else
                                eventChannel.write(evt.getClickCount());
                            eventChannel.write(evt.getButton());
                        }
                        eventChannel.flush();
                    } catch (Throwable e) {
                        log.logError("Error w/ event thread", e);
                        eventChannelError();
                    }
                }
            }
        });

    }

    public void postMediaPlayerUpdateEvent() {
        if (performingReconnect)
            return;
        synchronized (eventChannel) {
            try {
                eventChannel.write(MEDIA_PLAYER_UPDATE_EVENT_REPLY_TYPE); // media
                // player
                // update
                // event
                // code
                eventChannel.write(0);
                eventChannel.writeShort(0);// 3 byte length of 0
                eventChannel.writeInt(0); // timestamp
                eventChannel.writeInt(replyCount++);
                eventChannel.writeInt(0); // pad
                eventChannel.flush();
            } catch (Exception e) {
                log.logError("Error w/ event thread", e);
                eventChannelError();
            }
        }
    }

    public void postSubtitleInfo(long pts, long duration, byte[] data, int flags) {
        if (!subSupport)
            return; // don't send events if the other end doesn't support it
        if (performingReconnect)
            return;
        synchronized (eventChannel) {
            try {
                eventChannel.write(SUBTITLE_UPDATE_REPLY_TYPE); // subtitle
                // update event
                // code
                eventChannel.write(0);
                eventChannel.writeShort((short) (14 + ((data == null) ? 0 : data.length)));// 3
                // byte
                // length
                // of
                // 0
                eventChannel.writeInt(0); // timestamp
                eventChannel.writeInt(replyCount++);
                eventChannel.writeInt(0); // pad
                eventChannel.writeInt(flags);
                eventChannel.writeInt((int) pts);
                eventChannel.writeInt((int) duration);
                if (data != null) {
                    eventChannel.writeShort((short) data.length);
                    eventChannel.write(data);
                } else
                    eventChannel.writeShort(0);
                eventChannel.flush();
            } catch (Exception e) {
                log.logError("Error w/ event thread", e);
                eventChannelError();
            }
        }
    }

    public void postHotplugEvent(boolean insertion, String devPath, String devDesc) {
        if (eventChannel == null || fsSecurity == HIGH_SECURITY_FS)
            return;
        if (performingReconnect)
            return;
        synchronized (eventChannel) {
            if (encryptEvents && evtEncryptCipher != null) {
                // can't do this while encrypted'
                return;
            }
            try {
                eventChannel
                        .write(insertion ? REMOTE_FS_HOTPLUG_INSERT_EVENT_REPLY_TYPE : REMOTE_FS_HOTPLUG_REMOVE_EVENT_REPLY_TYPE);
                eventChannel.write(0);
                eventChannel.writeShort(4 + devPath.length() + devDesc.length());// 3
                // byte
                // length
                // of
                // the
                // 2
                // strings
                // +
                // count
                eventChannel.writeInt(0); // timestamp
                eventChannel.writeInt(replyCount++);
                eventChannel.writeInt(0); // pad
                eventChannel.writeShort(devPath.length());
                eventChannel.write(devPath.getBytes());
                eventChannel.writeShort(devDesc.length());
                eventChannel.write(devDesc.getBytes());
                eventChannel.flush();
            } catch (Exception e) {
                log.logError("Error w/ event thread", e);
                eventChannelError();
            }
        }
    }

    /**
     * Validates a filesystem path from the server to prevent path traversal attacks.
     * Rejects paths containing ".." sequences that could escape intended directories.
     */
    private static boolean isValidFsPath(String path) {
        if (path == null) return false;
        return !path.contains("..");
    }

    /**
     * Gets a filesystem path from a command, with path traversal validation.
     * Returns null if the path contains traversal sequences.
     */
    private String getSafeFsPath(byte[] cmdData, int offset) {
        String path = getCmdString(cmdData, offset);
        if (!isValidFsPath(path)) {
            log.logWarning("Rejected filesystem path with traversal sequence: " + path);
            return null;
        }
        return path;
    }

    private void processFSCmd(int cmdType, int len, byte[] cmdData) throws java.io.IOException {
        if (encryptEvents && evtEncryptCipher != null) {
            // can't do this while encrypted'
            return;
        }
        // Filesystem commands should not even be seen in this security mode
        if (fsSecurity == HIGH_SECURITY_FS)
            return;
        // System.out.println("MiniClient processing FS Command: " + cmdType);
        byte[][] strRv = null;
        long longRv = 0;
        int intRv = 0;
        boolean isLongRv = false;
        String pathName;
        java.io.File theFile;
        switch (cmdType) {
            case FSCMD_CREATE_DIRECTORY:
                pathName = getSafeFsPath(cmdData, 4);
                if (pathName == null) { intRv = FS_RV_NO_PERMISSIONS; break; }
                theFile = new java.io.File(pathName);
                // Check security
                if (fsSecurity == MED_SECURITY_FS && !theFile.isDirectory()) {
//				if (javax.swing.JOptionPane.showConfirmDialog(null,
//						"<html>Would you like to allow the server to create the local directory:<br>" + theFile + "</html>",
//						"File System Security", javax.swing.JOptionPane.YES_NO_OPTION,
//						javax.swing.JOptionPane.WARNING_MESSAGE) != javax.swing.JOptionPane.YES_OPTION) {
//					intRv = FS_RV_NO_PERMISSIONS;
//				}
                }
                if (intRv == 0)
                    intRv = (theFile.isDirectory() || theFile.mkdirs()) ? FS_RV_SUCCESS : FS_RV_ERROR_UNKNOWN;
                break;
            case FSCMD_GET_FILE_SIZE:
                isLongRv = true;
                pathName = getSafeFsPath(cmdData, 4);
                if (pathName != null) longRv = new java.io.File(pathName).length();
                break;
            case FSCMD_DELETE_FILE:
                pathName = getSafeFsPath(cmdData, 4);
                if (pathName == null) { intRv = FS_RV_NO_PERMISSIONS; break; }
                theFile = new java.io.File(pathName);
                if (!theFile.exists())
                    intRv = FS_RV_PATH_DOES_NOT_EXIST;
                else {
                    // Check security
                    if (fsSecurity == MED_SECURITY_FS) {
//					if (javax.swing.JOptionPane.showConfirmDialog(null,
//							"<html>Would you like to allow the server to delete the local file:<br>" + theFile + "</html>",
//							"File System Security", javax.swing.JOptionPane.YES_NO_OPTION,
//							javax.swing.JOptionPane.WARNING_MESSAGE) != javax.swing.JOptionPane.YES_OPTION) {
//						intRv = FS_RV_NO_PERMISSIONS;
//					}
                    }
                    if (intRv == 0 && !theFile.delete())
                        intRv = FS_RV_ERROR_UNKNOWN;
                }
                break;
            case FSCMD_GET_PATH_ATTRIBUTES:
                pathName = getSafeFsPath(cmdData, 4);
                if (pathName == null) { break; }
                theFile = new java.io.File(pathName);
                if (theFile.isHidden())
                    intRv = intRv | FS_PATH_HIDDEN;
                if (theFile.isFile())
                    intRv = intRv | FS_PATH_FILE;
                if (theFile.isDirectory())
                    intRv = intRv | FS_PATH_DIRECTORY;
                break;
            case FSCMD_GET_PATH_MODIFIED_TIME:
                isLongRv = true;
                pathName = getSafeFsPath(cmdData, 4);
                if (pathName != null) longRv = new java.io.File(pathName).lastModified();
                break;
            case FSCMD_DIR_LIST:
                pathName = getSafeFsPath(cmdData, 4);
                if (pathName == null) { intRv = FS_RV_NO_PERMISSIONS; break; }
                theFile = new java.io.File(pathName);
                String[] list = theFile.list();
                strRv = new byte[(list == null) ? 0 : list.length][];
                for (int i = 0; i < strRv.length; i++)
                    strRv[i] = list[i].getBytes("UTF-8");
                break;
            case FSCMD_LIST_ROOTS:
                java.io.File[] rootFiles = java.io.File.listRoots();
                strRv = new byte[(rootFiles == null) ? 0 : rootFiles.length][];
                for (int i = 0; i < strRv.length; i++)
                    strRv[i] = rootFiles[i].toString().getBytes("UTF-8");
                break;
            case FSCMD_DOWNLOAD_FILE:
            case FSCMD_UPLOAD_FILE:
                int secureID = ((cmdData[4] & 0xFF) << 24) | ((cmdData[5] & 0xFF) << 16) | ((cmdData[6] & 0xFF) << 8)
                        | (cmdData[7] & 0xFF);
                long fileOffset = getCmdLong(cmdData, 8);
                long fileSize = getCmdLong(cmdData, 16);
                pathName = getSafeFsPath(cmdData, 24);
                if (pathName == null) { intRv = FS_RV_NO_PERMISSIONS; break; }

                theFile = new java.io.File(pathName);
                if (cmdType == FSCMD_DOWNLOAD_FILE) {
                    // Make sure we're downloading to a valid file that we can write
                    // to
                    if (theFile.exists() && !theFile.canWrite())
                        intRv = FS_RV_NO_PERMISSIONS;
                    else if (theFile.getParentFile() != null && !theFile.getParentFile().isDirectory())
                        intRv = FS_RV_PATH_DOES_NOT_EXIST;
                    else {
                        // Check security
                        if (fsSecurity == MED_SECURITY_FS) {
//						if (javax.swing.JOptionPane.showConfirmDialog(null,
//								"<html>Would you like to allow the server to download to the local file:<br>" + theFile + "</html>",
//								"File System Security", javax.swing.JOptionPane.YES_NO_OPTION,
//								javax.swing.JOptionPane.WARNING_MESSAGE) != javax.swing.JOptionPane.YES_OPTION) {
//							intRv = FS_RV_NO_PERMISSIONS;
//						}
                        }
                        if (intRv == 0) {
                            // Try to create the pathname
                            try {
                                if (!theFile.createNewFile())
                                    intRv = FS_RV_NO_PERMISSIONS;
                            } catch (java.io.IOException e) {
                                intRv = FS_RV_NO_PERMISSIONS;
                            }
                        }
                    }
                } else {
                    // It's an upload; make sure the file is there and can be read
                    if (!theFile.exists())
                        intRv = FS_RV_PATH_DOES_NOT_EXIST;
                    else if (!theFile.canRead())
                        intRv = FS_RV_NO_PERMISSIONS;
                    else if (fsSecurity == MED_SECURITY_FS) {
//					if (javax.swing.JOptionPane.showConfirmDialog(null,
//							"<html>Would you like to allow the server to upload from the local file:<br>" + theFile + "</html>",
//							"File System Security", javax.swing.JOptionPane.YES_NO_OPTION,
//							javax.swing.JOptionPane.WARNING_MESSAGE) != javax.swing.JOptionPane.YES_OPTION) {
//						intRv = FS_RV_NO_PERMISSIONS;
//					}
                    }
                }
                if (intRv == 0) {
                    intRv = startAsyncFSOperation(cmdType == FSCMD_DOWNLOAD_FILE, secureID, fileOffset, fileSize, theFile);
                }
                break;
        }
        synchronized (eventChannel) {
            eventChannel.write(FS_CMD_TYPE);
            eventChannel.write(0);
            if (strRv != null) {
                // System.out.println("MiniClient returning file list of size "
                // + strRv.length);
                // find the total length
                int totalLen = 0;
                for (int i = 0; i < strRv.length; i++)
                    totalLen += strRv[i].length + 2;
                eventChannel.writeShort(totalLen + 2);// 3 byte length of 0
            } else if (isLongRv) {
                // System.out.println("MiniClient returning 64-bit FS RV of " +
                // longRv);
                eventChannel.writeShort(8);
            } else {
                // System.out.println("MiniClient returning 32-bit FS RV of " +
                // intRv);
                eventChannel.writeShort(4);
            }
            eventChannel.writeInt(0); // timestamp
            eventChannel.writeInt(replyCount++);
            eventChannel.writeInt(0); // pad
            if (strRv != null) {
                eventChannel.writeShort(strRv.length);
                for (int i = 0; i < strRv.length; i++) {
                    eventChannel.writeShort(strRv[i].length);
                    eventChannel.write(strRv[i]);
                }
            } else if (isLongRv)
                eventChannel.writeLong(longRv);
            else
                eventChannel.writeInt(intRv);
            eventChannel.flush();
        }
    }

    /**
     * Parse the transfer-session payload into a DownloadRequest.
     * Canonical payload is CMD_DOWNLOAD_REQUEST with type=TRANSFER_SESSION_ACK.
     * Uses minimal manual JSON parsing to avoid adding a library dependency to core.
     */
    private DownloadRequest parseTransferSessionAck(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            DownloadRequest req = new DownloadRequest();
            req.setMediaFileID(extractJsonString(json, "recording_id"));
            if (req.getMediaFileID() == null || req.getMediaFileID().isEmpty()) {
                req.setMediaFileID(extractJsonString(json, "mediaFileID"));
            }
            req.setTitle(extractJsonString(json, "title"));
            if (req.getTitle() == null || req.getTitle().isEmpty()) {
                req.setTitle(extractJsonString(json, "file_name"));
            }
            req.setServerPath(extractJsonString(json, "serverPath"));
            if (req.getServerPath() == null || req.getServerPath().isEmpty()) {
                req.setServerPath(extractJsonString(json, "download_path"));
            }
            req.setContainer(extractJsonString(json, "container"));
            req.setThumbnailUrl(extractJsonString(json, "thumbnailUrl"));
            req.setFileSize(extractJsonLong(json, "fileSize"));
            if (req.getFileSize() <= 0) {
                req.setFileSize(extractJsonLong(json, "total_bytes"));
            }
            req.setDuration(extractJsonLong(json, "duration"));
            req.setRecordingState(extractJsonString(json, "recording_state"));

            req.setSessionToken(extractJsonString(json, "session_token"));
            req.setDownloadUrl(extractJsonString(json, "download_url"));
            if (req.getDownloadUrl() == null || req.getDownloadUrl().isEmpty()) {
                req.setDownloadUrl(extractJsonString(json, "download_path"));
            }
            req.setSessionState(extractJsonString(json, "session_state"));
            req.setAccountFamily(extractJsonString(json, "app_family"));
            if (req.getAccountFamily() == null || req.getAccountFamily().isEmpty()) {
                req.setAccountFamily(extractJsonString(json, "account_family"));
            }
            req.setAccountUsername(extractJsonString(json, "username"));
            req.setAccountPassword(extractJsonString(json, "password"));
            req.setResumeFromOffset(extractJsonLong(json, "resume_from_offset"));
            req.setReconnectGraceSeconds(extractJsonLong(json, "reconnect_grace_seconds"));
            req.setExpiresInSeconds(extractJsonLong(json, "expires_in_seconds"));
            req.setEffectiveRateKbps(extractJsonLong(json, "effective_rate_kbps"));

            req.setRequestedPolicyJson(extractJsonObject(json, "requested_policy"));
            req.setAcceptedPolicyJson(extractJsonObject(json, "accepted_policy"));
            req.setPolicyAdjustmentsJson(extractJsonArray(json, "policy_adjustments"));
            req.setRecentReasonCodesJson(extractJsonArray(json, "recent_reason_codes"));
            req.setServerQueueItemId(extractJsonString(json, "server_queue_item_id"));
            if (req.getServerQueueItemId() == null || req.getServerQueueItemId().isEmpty()) {
                req.setServerQueueItemId(extractJsonString(json, "queue_item_id"));
            }
            req.setQueuePriority((int) extractJsonLong(json, "queue_priority"));
            if (req.getQueuePriority() == 0) {
                req.setQueuePriority((int) extractJsonLong(json, "priority"));
            }
            req.setRequestIntent(extractJsonString(json, "request_intent"));
            if (req.getRequestIntent() == null || req.getRequestIntent().isEmpty()) {
                req.setRequestIntent(extractJsonString(json, "intent"));
            }
            req.setSeriesSelectionMode(extractJsonString(json, "series_selection_mode"));
            req.setEstimatedSeriesBytes(extractJsonLong(json, "estimated_series_bytes"));
            req.setEstimatedItemCount((int) extractJsonLong(json, "estimated_item_count"));

            // Optional offline companion content (M2 — see
            // /memories/repo/offline-companion-spec.md). The "offline" object
            // contains rich metadata, artwork, captions, comskip and transcript
            // sidecar references. Stored verbatim so future server fields
            // survive without a client update.
            req.setOfflineCompanionJson(extractJsonObject(json, "offline"));
            req.setOfflineMetadataUrl(extractJsonString(json, "offline_metadata_url"));
            req.setOfflineMetadataPath(extractJsonString(json, "offline_metadata_path"));
            req.setOfflineInlineLevel(extractJsonString(json, "offline_inline_level"));

            if (req.getMediaFileID() == null || req.getMediaFileID().isEmpty()) return null;
            return req;
        } catch (Exception e) {
            log.logError("parsePendingDownload failed", e);
            return null;
        }
    }

    private static String extractJsonString(String json, String key) {
        String raw = extractJsonRawValue(json, key);
        if (raw == null || raw.length() < 2 || raw.charAt(0) != '"') return null;
        StringBuilder sb = new StringBuilder(raw.length() - 2);
        boolean escaped = false;
        for (int i = 1; i < raw.length() - 1; i++) {
            char c = raw.charAt(i);
            if (escaped) {
                if (c == 'n') sb.append('\n');
                else if (c == 'r') sb.append('\r');
                else if (c == 't') sb.append('\t');
                else sb.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static long extractJsonLong(String json, String key) {
        String raw = extractJsonRawValue(json, key);
        if (raw == null) return 0;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String extractJsonObject(String json, String key) {
        String raw = extractJsonRawValue(json, key);
        return raw != null && raw.startsWith("{") ? raw : null;
    }

    private static String extractJsonArray(String json, String key) {
        String raw = extractJsonRawValue(json, key);
        return raw != null && raw.startsWith("[") ? raw : null;
    }

    private static String redactForLog(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.length() <= 8) return "***";
        return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
    }

    private static String extractJsonRawValue(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + search.length());
        if (colonIdx < 0) return null;
        int start = colonIdx + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return null;

        char first = json.charAt(start);
        if (first == '"') {
            int i = start + 1;
            boolean escaped = false;
            while (i < json.length()) {
                char c = json.charAt(i);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    return json.substring(start, i + 1);
                }
                i++;
            }
            return null;
        }

        if (first == '{' || first == '[') {
            char open = first;
            char close = open == '{' ? '}' : ']';
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (inString) {
                    if (escaped) {
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (c == '"') {
                    inString = true;
                    continue;
                }
                if (c == open) depth++;
                if (c == close) {
                    depth--;
                    if (depth == 0) return json.substring(start, i + 1);
                }
            }
            return null;
        }

        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == ',' || c == '}' || c == ']') break;
            end++;
        }
        return json.substring(start, end).trim();
    }

    // Connects back to the server to initiate a remote FS operation; returns 0
    // if this starts up OK
    private int startAsyncFSOperation(boolean download, int secureID, long fileOffset, long fileSize, java.io.File theFile) {
        log.logDebug("Attempting to connect bak to server on FS channel");
        java.net.Socket sake = null;
        java.io.OutputStream fsOut = null;
        java.io.InputStream fsIn = null;
        java.io.RandomAccessFile raf = null;
        try {
            sake = EstablishServerConnection(2);
            sake.setSoTimeout(30000);
            fsOut = sake.getOutputStream();
            fsIn = sake.getInputStream();
            byte[] secureBytes = new byte[4];
            secureBytes[0] = (byte) ((secureID >> 24) & 0xFF);
            secureBytes[1] = (byte) ((secureID >> 16) & 0xFF);
            secureBytes[2] = (byte) ((secureID >> 8) & 0xFF);
            secureBytes[3] = (byte) (secureID & 0xFF);
            fsOut.write(secureBytes);
            raf = new java.io.RandomAccessFile(theFile, download ? "rw" : "r");
            if (fileOffset > 0)
                raf.seek(fileOffset);
            // Now start the real async operation
            asyncFSXfer(download, sake, fsOut, fsIn, fileOffset, fileSize, raf);
        } catch (java.io.IOException e) {
            if (raf != null)
                try {
                    raf.close();
                } catch (Exception e1) {
                }
            if (sake != null)
                try {
                    sake.close();
                } catch (Exception e1) {
                }
            if (fsOut != null)
                try {
                    fsOut.close();
                } catch (Exception e1) {
                }
            if (fsIn != null)
                try {
                    fsIn.close();
                } catch (Exception e1) {
                }
            return FS_RV_ERROR_UNKNOWN;
        }
        return FS_RV_SUCCESS;
    }

    private void asyncFSXfer(final boolean download, final java.net.Socket sake, final java.io.OutputStream fsOut,
                             final java.io.InputStream fsIn, final long fileOffset, final long fileSize, final java.io.RandomAccessFile localFile) {
        Thread t = new Thread() {
            public void run() {
                byte[] fsBuffer = new byte[16384];
                try {
                    long xferSize = fileSize;
                    while (xferSize > 0) {
                        int currSize = (int) Math.min(xferSize, fsBuffer.length);
                        if (!download) {
                            localFile.readFully(fsBuffer, 0, currSize);
                            fsOut.write(fsBuffer, 0, currSize);
                        } else {
                            currSize = fsIn.read(fsBuffer, 0, currSize);
                            if (currSize < 0)
                                throw new java.io.EOFException();
                            localFile.write(fsBuffer, 0, currSize);
                        }
                        xferSize -= currSize;
                        // System.out.println("xferSize rem " + xferSize);
                    }
                    if (!download)
                        fsOut.flush();
                    else {
                        localFile.close();
                        fsOut.write(0);
                        fsOut.write(0);
                        fsOut.write(0);
                        fsOut.write(0);
                    }
                    log.logDebug("Finished Remote FS operation!");
                } catch (Exception e) {
                    log.logError("ERROR w/ remote FS operation", e);
                } finally {
                    if (sake != null)
                        try {
                            sake.close();
                        } catch (Exception e) {
                        }
                    if (fsOut != null)
                        try {
                            fsOut.close();
                        } catch (Exception e) {
                        }
                    if (fsIn != null)
                        try {
                            fsIn.close();
                        } catch (Exception e) {
                        }
                    if (localFile != null)
                        try {
                            localFile.close();
                        } catch (Exception e) {
                        }
                }
            }
        };
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void MediaThread() {
        byte[] cmdbuffer = new byte[65536];

        java.io.OutputStream os = null;
        java.io.DataInputStream is = null;
        while (alive) {
            myMedia = new MediaCmd(client);
            try {
                os = mediaSocket.getOutputStream();
                is = new java.io.DataInputStream(mediaSocket.getInputStream());
                while (alive) {
                    byte[] cmd = new byte[4];
                    int command, len;
                    int retval;
                    byte[] retbuf = new byte[16];
                    is.readFully(cmd);

                    command = (cmd[0] & 0xFF);
                    len = ((cmd[1] & 0xFF) << 16) | ((cmd[2] & 0xFF) << 8) | (cmd[3] & 0xFF);
                    if (cmdbuffer.length < len) {
                        cmdbuffer = new byte[len];
                    }
                    is.readFully(cmdbuffer, 0, len);

                    retval = myMedia.ExecuteMediaCommand(command, len, cmdbuffer, retbuf);

                    if (retval > 0) {
                        os.write(retbuf, 0, retval);
                        os.flush();
                    }
                }
            } catch (Exception e) {
                log.logError("Error w/ Media Thread", e);
            } finally {
                try {
                    os.close();
                } catch (Exception e) {
                }
                os = null;
                try {
                    is.close();
                } catch (Exception e) {
                }
                is = null;
                try {
                    mediaSocket.close();
                } catch (Exception e) {
                }
                mediaSocket = null;
            }
            if (!alive)
                break;
            try {
                mediaSocket = EstablishServerConnection(1);
            } catch (Exception e) {
                connectionError();
            }
            if (mediaSocket == null) {
                // System.out.println("couldn't connect to media server,
                // retrying in 1 secs.");
                // try{Thread.sleep(1000);}catch(InterruptedException e){}
                connectionError();
            }
        }
    }

    private void connectionError() {
        close();
    }

    private void eventChannelError() {
        if (reconnectAllowed && alive && !encryptEvents && firstFrameStarted) {
            // close the gfx sockets; this'll cause an error in the GFX loop
            // which'll then cause it to do a reconnect
            log.logWarning("Event channel error occurred...closing other sockets to force reconnect...");
            try {
                gfxSocket.close();
            } catch (Exception e) {
            }
        } else
            close();
    }

    public String getServerName() {
        return msi.address;
    }

    public void addTimerTask(java.util.TimerTask addMe, long delay, long period) {
        if (uiTimer == null)
            uiTimer = new java.util.Timer(true);
        if (period == 0)
            uiTimer.schedule(addMe, delay);
        else
            uiTimer.schedule(addMe, delay, period);
    }

    public MediaCmd getMediaCmd() {
        return myMedia;
    }
    public GFXCMD2 getGfxCmd() {
        return myGfx;
    }

    public NgPlaybackContextStore getPlaybackContextStore() {
        return playbackContextStore;
    }

    public boolean hasFontServer() {
        return fontServer;
    }

    public boolean isLocahostConnection() {
        return ("127.0.0.1".equals(msi.address) || "localhost".equals(msi.address));
    }


    public UIRenderer<?> getUiRenderer() {
        return uiRenderer;
    }

    public MiniPlayerPlugin newPlayerPlugin( String urlString) {
        return uiRenderer.newPlayerPlugin(this, urlString);
    }

    public boolean doesUseAdvancedImageCaching() {
        return usesAdvancedImageCaching;
    }

    /**
     * Android requires that all network activity happen in a background thread.  The EventRouterThread
     * is a blocking queue of events that get processed on a separate thread when communicating to the server.
     */
    public class EventRouterThread extends Thread {
        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<Runnable>(100);
        Runnable event = null;

        public EventRouterThread(String evtRouter) {
            super(evtRouter);
        }

        @Override
        public void run() {
            while (true) {
                try {
                    // blocks until an event is ready
                    event = queue.take();
                    if (!performingReconnect) {
                        event.run();
                    }
                } catch (InterruptedException e) {
                    Thread.interrupted();
                    log.logWarning("EventRouterThread is shutting down");
                    return;
                } catch (Throwable t) {
                    log.logWarning("Event Processing Error", t);
                    // event likely caused an error, ignore it for now
                }
            }
        }
    }

    public boolean isVideoCodecSupported(String codecName)
    {
        return true;
    }
    
    public boolean isAudioCodecSupported(String codecName)
    {
        return true;
    }
    
}
