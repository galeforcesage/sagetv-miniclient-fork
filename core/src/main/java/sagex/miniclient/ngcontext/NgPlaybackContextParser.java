package sagex.miniclient.ngcontext;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the NG Playback Context JSON wire format into an {@link NgPlaybackContext}.
 * <p>
 * The server sends JSON via two paths:
 * <ol>
 *   <li>SET_PROPERTY "NG_PLAYBACK_CONTEXT" → raw context JSON object</li>
 *   <li>HTTP GET /ng/playback-context/{sessionId} → wrapped: {@code {"type":"NG_PLAYBACK_CONTEXT","sessionId":"...","context":{...}}}</li>
 * </ol>
 * This parser handles both forms.
 */
public final class NgPlaybackContextParser {

    private static final Logger log = LoggerFactory.getLogger(NgPlaybackContextParser.class);

    private NgPlaybackContextParser() { }

    /**
     * Parse a wire-format string into an NgPlaybackContext.
     * Accepts both the raw context JSON and the HTTP response wrapper.
     *
     * @param wireValue the JSON string received from the server
     * @param openUrl   the URL from MEDIACMD_OPENURL (may be null)
     * @return parsed context, never null (returns empty defaults on parse failure)
     */
    public static NgPlaybackContext parse(String wireValue, String openUrl) {
        if (wireValue == null || wireValue.isBlank()) {
            return new NgPlaybackContext.Builder().openUrl(openUrl).build();
        }

        try {
            var root = new JSONObject(wireValue);

            // Handle HTTP response wrapper: {"type":"NG_PLAYBACK_CONTEXT","context":{...}}
            JSONObject ctx;
            if (root.has("context")) {
                ctx = root.getJSONObject("context");
            } else {
                ctx = root;
            }

            return fromJson(ctx, openUrl);
        } catch (JSONException e) {
            log.warn("Failed to parse NG Playback Context JSON: {}", e.getMessage());
            return new NgPlaybackContext.Builder().openUrl(openUrl).build();
        }
    }

    /**
     * Parse from a pre-parsed JSONObject. Useful for testing.
     */
    public static NgPlaybackContext fromJson(JSONObject ctx, String openUrl) throws JSONException {
        var builder = new NgPlaybackContext.Builder()
                .openUrl(openUrl)
                .version(ctx.optInt("version", 1))
                .sessionId(ctx.optString("sessionId", ""))
                .mediaFileId(ctx.optLong("mediaFileId", 0))
                .airingId(ctx.optLong("airingId", 0))
                .mode(ctx.optString("mode", "unknown"))
                .container(ctx.optString("container", "unknown"))
                .durationMs(ctx.optLong("durationMs", 0))
                .serverMediaTimeMs(ctx.optLong("serverMediaTimeMs", 0))
                .streamEpoch(ctx.optInt("streamEpoch", 0));

        if (ctx.has("live")) {
            builder.live(parseLive(ctx.getJSONObject("live")));
        }
        if (ctx.has("seek")) {
            builder.seek(parseSeek(ctx.getJSONObject("seek")));
        }
        if (ctx.has("index")) {
            builder.index(parseIndex(ctx.getJSONObject("index")));
        }
        if (ctx.has("skip")) {
            builder.skip(parseSkip(ctx.getJSONObject("skip")));
        }
        if (ctx.has("flow")) {
            builder.flow(parseFlow(ctx.getJSONObject("flow")));
        }

        return builder.build();
    }

    private static NgPlaybackContext.LiveContext parseLive(JSONObject obj) {
        return new NgPlaybackContext.LiveContext(
                obj.optBoolean("isLive", false),
                obj.optLong("recordingStartMs", 0),
                obj.optLong("safeSeekStartMs", 0),
                obj.optLong("safeSeekEndMs", 0),
                obj.optLong("playableEndMs", 0),
                obj.optLong("growthBytes", 0),
                obj.optLong("lastSizeRefreshMs", 0)
        );
    }

    private static NgPlaybackContext.SeekPolicy parseSeek(JSONObject obj) {
        return new NgPlaybackContext.SeekPolicy(
                obj.optLong("preferredGranularityMs", 5000),
                obj.optLong("minSeekIntervalMs", 250),
                obj.optLong("maxClientCoalesceMs", 1500),
                obj.optBoolean("requiresServerSeek", true),
                obj.optBoolean("clientMayPredictOsd", false)
        );
    }

    private static NgPlaybackContext.IndexContext parseIndex(JSONObject obj) {
        List<NgPlaybackContext.PtsSample> samples = List.of();
        if (obj.has("ptsSamples")) {
            JSONArray arr = obj.getJSONArray("ptsSamples");
            var list = new ArrayList<NgPlaybackContext.PtsSample>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.getJSONObject(i);
                list.add(new NgPlaybackContext.PtsSample(
                        s.optLong("timeMs", 0),
                        s.optLong("byteOffset", 0),
                        s.optBoolean("keyframe", false)
                ));
            }
            samples = list;
        }
        return new NgPlaybackContext.IndexContext(
                obj.optBoolean("hasKeyframeIndex", false),
                obj.optBoolean("hasPtsByteMap", false),
                samples
        );
    }

    private static NgPlaybackContext.SkipContext parseSkip(JSONObject obj) {
        return new NgPlaybackContext.SkipContext(
                parseSegmentList(obj.optJSONArray("commercials")),
                parseSegmentList(obj.optJSONArray("chapters")),
                parseSegmentList(obj.optJSONArray("bookmarks"))
        );
    }

    private static List<NgPlaybackContext.SkipSegment> parseSegmentList(JSONArray arr) {
        if (arr == null || arr.length() == 0) return List.of();
        var list = new ArrayList<NgPlaybackContext.SkipSegment>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject seg = arr.getJSONObject(i);
            list.add(new NgPlaybackContext.SkipSegment(
                    seg.optLong("startMs", 0),
                    seg.optLong("endMs", 0),
                    seg.optString("type", "unknown"),
                    seg.optLong("prerollMs", 0)
            ));
        }
        return list;
    }

    private static NgPlaybackContext.FlowPolicy parseFlow(JSONObject obj) {
        return new NgPlaybackContext.FlowPolicy(
                obj.optInt("preferredPrebufferBytes", 262144),
                obj.optInt("lowWatermarkBytes", 131072),
                obj.optInt("highWatermarkBytes", 4194304)
        );
    }
}
