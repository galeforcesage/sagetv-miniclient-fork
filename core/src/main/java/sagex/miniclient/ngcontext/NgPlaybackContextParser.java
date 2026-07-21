package sagex.miniclient.ngcontext;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Parses the canonical NG Playback Context wire format into an {@link NgPlaybackContext}.
 * <p>
 * Wire format: pipe-delimited key=value pairs. Arrays are comma-separated within the value.
 * Values containing {@code |} or {@code =} must be URL-encoded.
 * <pre>
 * mediaFileId=12345|title=My+Show|durationMs=3600000|contentType=recording|isLive=false
 * </pre>
 */
public final class NgPlaybackContextParser {

    private NgPlaybackContextParser() { }

    /**
     * Parse the wire-format string into an NgPlaybackContext.
     *
     * @param wireValue the property value received from the server
     * @param openUrl   the URL from MEDIACMD_OPENURL (may be null if not yet known)
     * @return parsed context, never null
     */
    public static NgPlaybackContext parse(String wireValue, String openUrl) {
        var map = parseToMap(wireValue);
        return fromMap(map, openUrl);
    }

    /**
     * Build an NgPlaybackContext from a pre-parsed map. Useful for testing.
     */
    public static NgPlaybackContext fromMap(Map<String, String> map, String openUrl) {
        var builder = new NgPlaybackContext.Builder().openUrl(openUrl);
        var extras = new HashMap<String, String>();

        for (var entry : map.entrySet()) {
            var key = entry.getKey();
            var val = entry.getValue();

            switch (key) {
                case "mediaFileId" -> builder.mediaFileId(val);
                case "title" -> builder.title(val);
                case "durationMs" -> builder.durationMs(parseLong(val, -1));
                case "contentType" -> builder.contentType(val);
                case "isLive" -> builder.isLive(parseBoolean(val));
                case "isTimeshifted" -> builder.isTimeshifted(parseBoolean(val));
                case "scheduledStartMs" -> builder.scheduledStartMs(parseLong(val, 0));
                case "scheduledEndMs" -> builder.scheduledEndMs(parseLong(val, 0));
                case "chapterMarksMs" -> builder.chapterMarksMs(parseLongArray(val));
                case "commercialBreaksMs" -> builder.commercialBreaksMs(parseLongArray(val));
                case "seekableByClient" -> builder.seekableByClient(parseBoolean(val));
                case "playableEndMs" -> builder.playableEndMs(parseLong(val, -1));
                case "safeSeekEndMs" -> builder.safeSeekEndMs(parseLong(val, -1));
                case "preferredGranularityMs" -> builder.preferredGranularityMs(parseLong(val, 0));
                case "maxClientCoalesceMs" -> builder.maxClientCoalesceMs(parseLong(val, 0));
                default -> extras.put(key, val);
            }
        }

        if (!extras.isEmpty()) {
            builder.extras(extras);
        }
        return builder.build();
    }

    /** Parse the wire string into a raw key-value map. */
    static Map<String, String> parseToMap(String wireValue) {
        if (wireValue == null || wireValue.isEmpty()) {
            return Map.of();
        }

        var map = new HashMap<String, String>();
        for (var pair : wireValue.split("\\|")) {
            int eqIdx = pair.indexOf('=');
            if (eqIdx <= 0) continue;
            var key = pair.substring(0, eqIdx).trim();
            var rawVal = pair.substring(eqIdx + 1);
            map.put(key, URLDecoder.decode(rawVal, StandardCharsets.UTF_8));
        }
        return map;
    }

    private static long parseLong(String val, long defaultVal) {
        if (val == null || val.isEmpty()) return defaultVal;
        try {
            return Long.parseLong(val.trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private static boolean parseBoolean(String val) {
        return "true".equalsIgnoreCase(val != null ? val.trim() : "");
    }

    private static long[] parseLongArray(String val) {
        if (val == null || val.isEmpty()) return new long[0];
        var parts = val.split(",");
        var result = new long[parts.length];
        int count = 0;
        for (var part : parts) {
            try {
                result[count++] = Long.parseLong(part.trim());
            } catch (NumberFormatException e) {
                // skip malformed entries
            }
        }
        return count < result.length ? java.util.Arrays.copyOf(result, count) : result;
    }
}
