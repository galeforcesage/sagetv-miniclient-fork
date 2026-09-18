package sagex.miniclient.util;

/**
 * Created by seans on 22/12/15.
 */
public class VerboseLogging {
    public static boolean LOG_GL_ERRORS = false;
    public static boolean DETAILED_MEDIA_COMMAND_PUSHBUFFER = false;
    public static boolean DETAILED_MEDIA_COMMAND = false;
    public static boolean DETAILED_GFX_TEXTURES = false;
    public static boolean DETAILED_GFX = false;
    public static boolean DETAILED_PUSHBUFFER_LOGGING = false;
    public static boolean DETAILED_PLAYER_LOGGING = false;
    // FREEZEDIAG: emit media3 buffering/loading/dropped-frame telemetry plus a
    // periodic 2s buffer/ring sampler, so a periodic playback stall can be
    // classified as buffer starvation vs a renderer/HDMI stall vs server
    // under-delivery. Driven at runtime by the client log_level preference:
    // AppUtil.setLogLevel() sets this true only at DEBUG level. Default false.
    public static boolean FREEZE_DIAG = false;
    public static boolean DETAILED_IMAGE_CACHE = false;
    public static boolean DATASOURCE_LOGGING = false;
    public static boolean LOG_DATASOURCE_BYTES_TO_FILE = false;
    public static boolean LOG_KEYS = false;
}
