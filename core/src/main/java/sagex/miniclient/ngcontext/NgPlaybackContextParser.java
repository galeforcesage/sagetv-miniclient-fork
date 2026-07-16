package sagex.miniclient.ngcontext;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
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

    private static final String CHARSET = "UTF-8";

    private NgPlaybackContextParser() { }

    /**
     * Parse the wire-format string into an NgPlaybackContext.
     *
     * @param wireValue the property value received from the server
     * @param openUrl   the URL from MEDIACMD_OPENURL (may be null if not yet known)
     * @return parsed context, never null
     */
    public static NgPlaybackContext parse(String wireValue, String openUrl) {
        Map<String, String> map = parseToMap(wireValue);
        return fromMap(map, openUrl);
    }

    /**
     * Build an NgPlaybackContext from a pre-parsed map. Useful for testing.
     */
    public static NgPlaybackContext fromMap(Map<String, String> map, String openUrl) {
        NgPlaybackContext.Builder builder = new NgPlaybackContext.Builder();
        builder.openUrl(openUrl);

        Map<String, String> extras = new HashMap<String, String>();

        for (Map.Entry<String, String> entry : map.entrySet()) {
            String key = entry.getKey();
            String val = entry.getValue();

            if ("mediaFileId".equals(key)) {
                builder.mediaFileId(val);
            } else if ("title".equals(key)) {
                builder.title(val);
            } else if ("durationMs".equals(key)) {
                builder.durationMs(parseLong(val, -1));
            } else if ("contentType".equals(key)) {
                builder.contentType(val);
            } else if ("isLive".equals(key)) {
                builder.isLive(parseBoolean(val));
            } else if ("isTimeshifted".equals(key)) {
                builder.isTimeshifted(parseBoolean(val));
            } else if ("scheduledStartMs".equals(key)) {
                builder.scheduledStartMs(parseLong(val, 0));
            } else if ("scheduledEndMs".equals(key)) {
                builder.scheduledEndMs(parseLong(val, 0));
            } else if ("chapterMarksMs".equals(key)) {
                builder.chapterMarksMs(parseLongArray(val));
            } else if ("commercialBreaksMs".equals(key)) {
                builder.commercialBreaksMs(parseLongArray(val));
            } else if ("seekableByClient".equals(key)) {
                builder.seekableByClient(parseBoolean(val));
            } else {
                extras.put(key, val);
            }
        }

        if (!extras.isEmpty()) {
            builder.extras(extras);
        }

        return builder.build();
    }

    /**
     * Parse the wire string into a raw key-value map.
     */
    static Map<String, String> parseToMap(String wireValue) {
        Map<String, String> map = new HashMap<String, String>();
        if (wireValue == null || wireValue.isEmpty()) {
            return map;
        }

        String[] pairs = wireValue.split("\\|");
        for (String pair : pairs) {
            int eqIdx = pair.indexOf('=');
            if (eqIdx <= 0) continue;
            String key = pair.substring(0, eqIdx).trim();
            String rawVal = pair.substring(eqIdx + 1);
            String val = urlDecode(rawVal);
            map.put(key, val);
        }
        return map;
    }

    private static String urlDecode(String val) {
        try {
            return URLDecoder.decode(val, CHARSET);
        } catch (UnsupportedEncodingException e) {
            return val;
        } catch (IllegalArgumentException e) {
            return val;
        }
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
        String[] parts = val.split(",");
        long[] result = new long[parts.length];
        int count = 0;
        for (String part : parts) {
            try {
                result[count++] = Long.parseLong(part.trim());
            } catch (NumberFormatException e) {
                // skip malformed entries
            }
        }
        if (count < result.length) {
            long[] trimmed = new long[count];
            System.arraycopy(result, 0, trimmed, 0, count);
            return trimmed;
        }
        return result;
    }
}
