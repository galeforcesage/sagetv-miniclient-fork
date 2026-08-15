package sagex.miniclient.streaminfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses the MEDIACMD_STREAMINFO JSON payload (v1) into a {@link StreamInfo}.
 * <p>
 * Mirrors {@code NgPlaybackContextParser} style. Never throws to callers:
 * on malformed input returns {@code null} so the caller ACKs 0x00 and the
 * server proceeds with the legacy OPENURL path (STREAMINFO is advisory).
 */
public final class StreamInfoParser {

    private static final Logger log = LoggerFactory.getLogger(StreamInfoParser.class);

    private StreamInfoParser() { }

    /**
     * @param json the UTF-8 JSON payload from command 40
     * @return parsed StreamInfo, or null if the payload could not be parsed
     */
    public static StreamInfo parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(json);
            return fromJson(root);
        } catch (JSONException e) {
            log.warn("Failed to parse STREAMINFO JSON: {}", e.getMessage());
            return null;
        }
    }

    public static StreamInfo fromJson(JSONObject root) throws JSONException {
        StreamInfo.Builder b = new StreamInfo.Builder()
                .version(root.optInt("v", 1))
                .container(root.optString("container", "unknown"))
                .durationMs(root.optLong("duration_ms", 0))
                .live(root.optBoolean("live", false))
                .bitrate(root.optLong("bitrate", 0));

        JSONArray v = root.optJSONArray("video");
        if (v != null) {
            for (int i = 0; i < v.length(); i++) {
                JSONObject o = v.getJSONObject(i);
                String codec = o.optString("codec", null);
                String mime = o.has("mime") && !o.isNull("mime")
                        ? o.optString("mime", null)
                        : StreamInfo.mimeForCodec(codec);
                b.addVideo(new StreamInfo.VideoTrack(
                        codec,
                        emptyToNull(mime),
                        o.optInt("width", 0),
                        o.optInt("height", 0),
                        o.optDouble("fps", 0),
                        o.optBoolean("interlaced", false),
                        o.optString("id", null),
                        o.optBoolean("primary", i == 0)));
            }
        }

        JSONArray a = root.optJSONArray("audio");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String codec = o.optString("codec", null);
                String mime = o.has("mime") && !o.isNull("mime")
                        ? o.optString("mime", null)
                        : StreamInfo.mimeForCodec(codec);
                b.addAudio(new StreamInfo.AudioTrack(
                        codec,
                        emptyToNull(mime),
                        o.optInt("channels", 0),
                        o.optInt("sample_rate", 0),
                        o.optInt("bits_per_sample", 0),
                        o.optLong("bitrate", 0),
                        o.optString("language", null),
                        o.optBoolean("primary", i == 0),
                        o.optString("id", null)));
            }
        }

        JSONArray s = root.optJSONArray("subtitle");
        if (s != null) {
            for (int i = 0; i < s.length(); i++) {
                JSONObject o = s.getJSONObject(i);
                b.addSubtitle(new StreamInfo.SubtitleTrack(
                        o.optString("codec", null),
                        o.optString("language", null),
                        o.optString("id", null)));
            }
        }

        return b.build();
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
