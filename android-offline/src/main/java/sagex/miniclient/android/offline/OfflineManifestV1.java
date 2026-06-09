package sagex.miniclient.android.offline;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Canonical parser/model for NG Download Manifest v1.
 *
 * <p>This is intentionally strict about the top-level contract: the client
 * only accepts manifest_version=1 and one canonical shape. Optional nested
 * fields remain presence-driven and unknown metadata keys are preserved for
 * generic rendering and diagnostics.
 */
final class OfflineManifestV1 {
    static final int MANIFEST_VERSION = 1;

    static final class ParseException extends Exception {
        ParseException(String message) {
            super(message);
        }

        ParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static final class Credit {
        final String personId;
        final String roleName;
        final String personName;
        final String imageUrl;

        Credit(String personId, String roleName, String personName, String imageUrl) {
            this.personId = blankToNull(personId);
            this.roleName = blankToNull(roleName);
            this.personName = blankToNull(personName);
            this.imageUrl = blankToNull(imageUrl);
        }
    }

    static final class ImageAsset {
        final String kind;
        final String url;
        final String personId;

        ImageAsset(String kind, String url, String personId) {
            this.kind = normalize(kind);
            this.url = blankToNull(url);
            this.personId = blankToNull(personId);
        }
    }

    static final class AssetRef {
        final String url;
        final JSONObject raw;

        AssetRef(String url, JSONObject raw) {
            this.url = blankToNull(url);
            this.raw = raw;
        }
    }

    static final class MetadataEntry {
        final String key;
        final String label;
        final String value;

        MetadataEntry(String key, String value) {
            this.key = key;
            this.label = prettifyKey(key);
            this.value = value;
        }
    }

    private static final List<String> PREFERRED_METADATA_ORDER = Arrays.asList(
            "categories",
            "original_air_date",
            "aired_on",
            "rated",
            "season_number",
            "episode_number",
            "show_id",
            "channel",
            "channel_name",
            "network",
            "station",
            "recording_file_size",
            "audio_format_summary",
            "first_run"
    );

    private final String rawJson;
    private final String snippetHash;
    private final JSONObject root;
    private final JSONObject metadata;
    private final JSONObject assets;
    private final String recordingId;
    private final String title;
    private final String subtitle;
    private final long runtimeMs;
    private final List<Credit> credits;
    private final List<ImageAsset> images;
    private final List<AssetRef> captions;
    private final List<AssetRef> comskip;
    private final List<AssetRef> transcript;

    private OfflineManifestV1(String rawJson,
                              JSONObject root,
                              JSONObject metadata,
                              JSONObject assets,
                              String recordingId,
                              String title,
                              String subtitle,
                              long runtimeMs,
                              List<Credit> credits,
                              List<ImageAsset> images,
                              List<AssetRef> captions,
                              List<AssetRef> comskip,
                              List<AssetRef> transcript) {
        this.rawJson = rawJson;
        this.snippetHash = sha256Hex(rawJson);
        this.root = root;
        this.metadata = metadata;
        this.assets = assets;
        this.recordingId = recordingId;
        this.title = title;
        this.subtitle = subtitle;
        this.runtimeMs = runtimeMs;
        this.credits = Collections.unmodifiableList(credits);
        this.images = Collections.unmodifiableList(images);
        this.captions = Collections.unmodifiableList(captions);
        this.comskip = Collections.unmodifiableList(comskip);
        this.transcript = Collections.unmodifiableList(transcript);
    }

    static OfflineManifestV1 parse(String rawJson) throws ParseException {
        if (rawJson == null || rawJson.trim().isEmpty()) {
            throw new ParseException("empty manifest");
        }
        try {
            JSONObject root = new JSONObject(rawJson);
            if (root.optInt("manifest_version", -1) != MANIFEST_VERSION) {
                throw new ParseException("unsupported manifest_version="
                        + root.opt("manifest_version"));
            }
            JSONObject core = root.optJSONObject("core");
            if (core == null) core = new JSONObject();

            String title = firstNonEmpty(
                root.optString("title", null),
                core.optString("title", null),
                core.optString("show_title", null));
            if (title == null) {
                throw new ParseException("missing required title");
            }
            JSONObject metadata = mergeMetadata(core, root.optJSONObject("metadata"));
            JSONObject assets = root.optJSONObject("assets");
            if (assets == null) assets = new JSONObject();
            if (!assets.has("images") && root.has("artwork")) {
            assets.put("images", root.optJSONArray("artwork"));
            }

            String subtitle = firstNonEmpty(
                    root.optString("subtitle", null),
                    root.optString("episode_title", null),
                core.optString("subtitle", null),
                core.optString("episode_title", null),
                    metadata.optString("subtitle", null),
                    metadata.optString("episode_title", null));
            long runtimeMs = firstPositiveLong(
                    root.optLong("runtime_ms", 0L),
                    root.optLong("runtime", 0L),
                    root.optLong("duration_ms", 0L),
                    root.optLong("duration", 0L),
                core.optLong("runtime_ms", 0L),
                    minutesToMs(metadata.optLong("run_time_minutes", 0L))
            );
            String recordingId = firstNonEmpty(
                    root.optString("recording_id", null),
                    root.optString("media_file_id", null),
                core.optString("recording_id", null),
                core.optString("media_file_id", null),
                    metadata.optString("recording_id", null),
                    metadata.optString("media_file_id", null));

            List<Credit> credits = parseCredits(root.optJSONArray("credits"));
            List<ImageAsset> images = parseImages(assets.optJSONArray("images"));
            List<AssetRef> captions = parseAssetRefs(assets.opt("captions"));
            List<AssetRef> comskip = parseAssetRefs(assets.opt("comskip"));
            List<AssetRef> transcript = parseAssetRefs(assets.opt("transcript"));

            return new OfflineManifestV1(rawJson, root, metadata, assets, recordingId,
                    title, subtitle, runtimeMs, credits, images, captions, comskip, transcript);
        } catch (ParseException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseException("invalid manifest json", e);
        }
    }

    String getRawJson() {
        return rawJson;
    }

    String getSnippetHash() {
        return snippetHash;
    }

    JSONObject getRoot() {
        return root;
    }

    JSONObject getMetadata() {
        return metadata;
    }

    JSONObject getAssets() {
        return assets;
    }

    String getRecordingId() {
        return recordingId;
    }

    String getTitle() {
        return title;
    }

    String getSubtitle() {
        return subtitle;
    }

    long getRuntimeMs() {
        return runtimeMs;
    }

    List<Credit> getCredits() {
        return credits;
    }

    List<ImageAsset> getImages() {
        return images;
    }

    List<AssetRef> getCaptions() {
        return captions;
    }

    List<AssetRef> getComskip() {
        return comskip;
    }

    List<AssetRef> getTranscript() {
        return transcript;
    }

    ImageAsset pickHeroImage() {
        for (String kind : Arrays.asList("thumbnail", "poster", "fanart")) {
            ImageAsset image = findImageByKind(kind);
            if (image != null) return image;
        }
        return null;
    }

    ImageAsset findImageByKind(String kind) {
        String normalized = normalize(kind);
        for (ImageAsset image : images) {
            if (normalized.equals(image.kind) && image.url != null) {
                return image;
            }
        }
        return null;
    }

    ImageAsset findPersonImage(String personId) {
        String wanted = blankToNull(personId);
        if (wanted == null) return null;
        for (ImageAsset image : images) {
            if (wanted.equals(image.personId) && image.url != null) {
                return image;
            }
        }
        return null;
    }

    Map<String, List<Credit>> groupCreditsByRole() {
        Map<String, List<Credit>> grouped = new LinkedHashMap<>();
        for (Credit credit : credits) {
            String role = credit.roleName != null ? credit.roleName : "Credits";
            List<Credit> bucket = grouped.get(role);
            if (bucket == null) {
                bucket = new ArrayList<>();
                grouped.put(role, bucket);
            }
            bucket.add(credit);
        }
        for (List<Credit> bucket : grouped.values()) {
            bucket.sort(Comparator.comparing(c -> safeLower(c.personName)));
        }
        return grouped;
    }

    List<String> getCategories() {
        Object value = metadata.opt("categories");
        List<String> out = stringValues(value);
        if (!out.isEmpty()) return out;
        out = stringValues(metadata.opt("category"));
        if (!out.isEmpty()) return out;
        return stringValues(metadata.opt("genre"));
    }

    String getPrimaryDescription() {
        return firstNonEmpty(
                metadata.optString("description", null),
                metadata.optString("summary", null),
                metadata.optString("overview", null),
                metadata.optString("desc", null));
    }

    String getKnownMetadataValue(String key) {
        if (key == null || key.isEmpty()) return null;
        Object value = metadata.opt(key);
        return stringifyValue(key, value);
    }

    List<MetadataEntry> getPreferredMetadataEntries() {
        List<MetadataEntry> entries = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (String key : PREFERRED_METADATA_ORDER) {
            String value = getKnownMetadataValue(key);
            if (value == null) continue;
            entries.add(new MetadataEntry(key, value));
            used.add(key);
        }
        if (runtimeMs > 0 && !used.contains("runtime_ms")) {
            entries.add(new MetadataEntry("runtime_ms", formatRuntime(runtimeMs)));
            used.add("runtime_ms");
        }
        return entries;
    }

    List<MetadataEntry> getRemainingMetadataEntries() {
        List<MetadataEntry> entries = new ArrayList<>();
        JSONArray names = metadata.names();
        if (names == null) return entries;
        Set<String> preferred = new LinkedHashSet<>(PREFERRED_METADATA_ORDER);
        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i, null);
            if (key == null || preferred.contains(key)) continue;
            String value = stringifyValue(key, metadata.opt(key));
            if (value != null) {
                entries.add(new MetadataEntry(key, value));
            }
        }
        entries.sort(Comparator.comparing(e -> safeLower(e.label)));
        return entries;
    }

    int getArtworkCount() {
        return images.size();
    }

    private static List<Credit> parseCredits(JSONArray arr) {
        List<Credit> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;
            Credit credit = new Credit(
                    obj.optString("person_id", null),
                    firstNonEmpty(obj.optString("role_name", null), obj.optString("role", null)),
                    firstNonEmpty(obj.optString("person_name", null), obj.optString("name", null)),
                    obj.optString("image_url", null));
            if (credit.personName != null) {
                out.add(credit);
            }
        }
        return out;
    }

    private static List<ImageAsset> parseImages(JSONArray arr) {
        List<ImageAsset> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;
            ImageAsset asset = new ImageAsset(
                    obj.optString("kind", null),
                    obj.optString("url", null),
                    firstNonEmpty(obj.optString("person_id", null), obj.optString("subject_id", null)));
            if (asset.kind != null && asset.url != null) {
                out.add(asset);
            }
        }
        return out;
    }

    private static JSONObject mergeMetadata(JSONObject core, JSONObject metadata) {
        JSONObject merged = new JSONObject();
        copyAll(metadata, merged);
        copyIfMissing(core, merged, "original_air_date");
        copyIfMissing(core, merged, "show_id");
        copyIfMissing(core, merged, "season_number");
        copyIfMissing(core, merged, "episode_number");
        copyIfMissing(core, merged, "first_run");
        copyIfMissing(core, merged, "description");
        copyIfMissing(core, merged, "subtitle");
        copyIfMissing(core, merged, "recording_start_ms");
        copyIfMissing(core, merged, "recording_end_ms");
        copyIfMissing(core, merged, "recording_start_utc");
        copyIfMissing(core, merged, "recording_end_utc");
        if (!merged.has("run_time_minutes") && core != null) {
            long runtimeMs = core.optLong("runtime_ms", 0L);
            if (runtimeMs > 0L) {
                putSafely(merged, "run_time_minutes", Math.max(1L, runtimeMs / 60000L));
            }
        }
        return merged;
    }

    private static void copyAll(JSONObject from, JSONObject to) {
        if (from == null || to == null) return;
        JSONArray names = from.names();
        if (names == null) return;
        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i, null);
            if (key == null) continue;
            putSafely(to, key, from.opt(key));
        }
    }

    private static void copyIfMissing(JSONObject from, JSONObject to, String key) {
        if (from == null || to == null || key == null || to.has(key) || !from.has(key)) return;
        putSafely(to, key, from.opt(key));
    }

    private static void putSafely(JSONObject obj, String key, Object value) {
        if (obj == null || key == null) return;
        try {
            obj.put(key, value);
        } catch (Exception ignored) {
            // Best-effort merge for forward-compatible metadata shaping.
        }
    }

    private static List<AssetRef> parseAssetRefs(Object node) {
        List<AssetRef> out = new ArrayList<>();
        collectAssetRefs(node, out);
        return out;
    }

    private static void collectAssetRefs(Object node, List<AssetRef> out) {
        if (node == null || node == JSONObject.NULL) return;
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            String url = blankToNull(obj.optString("url", null));
            if (url != null) {
                out.add(new AssetRef(url, obj));
            }
            JSONArray names = obj.names();
            if (names == null) return;
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i, null);
                if (key == null || "url".equals(key)) continue;
                collectAssetRefs(obj.opt(key), out);
            }
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                collectAssetRefs(arr.opt(i), out);
            }
        }
    }

    static String prettifyKey(String key) {
        if (key == null || key.isEmpty()) return "";
        String[] parts = key.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            String lower = part.toLowerCase(Locale.US);
            sb.append(Character.toUpperCase(lower.charAt(0))).append(lower.substring(1));
        }
        return sb.toString();
    }

    static String formatRuntime(long runtimeMs) {
        if (runtimeMs <= 0) return null;
        long minutes = Math.max(1L, runtimeMs / 60000L);
        if (minutes >= 60 && minutes % 60 == 0) {
            long hours = minutes / 60;
            return hours + (hours == 1 ? " hour" : " hours");
        }
        return minutes + " minutes";
    }

    static String imageFileName(ImageAsset image) {
        if (image == null || image.kind == null) return null;
        switch (image.kind) {
            case "thumbnail":
                return "thumbnail.jpg";
            case "poster":
                return "poster.jpg";
            case "fanart":
                return "fanart.jpg";
            case "banner":
                return "banner.jpg";
            case "person":
            case "cast":
                return image.personId == null ? null : "cast/" + image.personId + ".jpg";
            default:
                return null;
        }
    }

    private static String stringifyValue(String key, Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof JSONArray || value instanceof String) {
            List<String> values = stringValues(value);
            if (!values.isEmpty()) {
                return String.join(" / ", values);
            }
        }
        if (value instanceof Boolean) {
            boolean flag = (Boolean) value;
            if ("first_run".equals(key)) return flag ? "First Run" : null;
            return flag ? "Yes" : "No";
        }
        if (value instanceof Number) {
            if ("recording_file_size".equals(key)) {
                return formatBytes(((Number) value).longValue());
            }
            if ("run_time_minutes".equals(key)) {
                return ((Number) value).longValue() + " minutes";
            }
            return String.valueOf(value);
        }
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            List<String> pairs = new ArrayList<>();
            JSONArray names = obj.names();
            if (names == null) return null;
            for (int i = 0; i < names.length(); i++) {
                String name = names.optString(i, null);
                String child = stringifyValue(name, obj.opt(name));
                if (name != null && child != null) {
                    pairs.add(prettifyKey(name) + ": " + child);
                }
            }
            return pairs.isEmpty() ? null : String.join(", ", pairs);
        }
        return blankToNull(String.valueOf(value));
    }

    private static List<String> stringValues(Object value) {
        if (value == null || value == JSONObject.NULL) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            for (int i = 0; i < arr.length(); i++) {
                String item = blankToNull(arr.optString(i, null));
                if (item != null) out.add(item);
            }
            return out;
        }
        String single = blankToNull(String.valueOf(value));
        return single == null ? Collections.emptyList() : Collections.singletonList(single);
    }

    private static long minutesToMs(long minutes) {
        return minutes > 0 ? minutes * 60000L : 0L;
    }

    private static long firstPositiveLong(long... values) {
        if (values == null) return 0L;
        for (long value : values) {
            if (value > 0) return value;
        }
        return 0L;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes / 1024.0;
        if (value < 1024) return String.format(Locale.US, "%.1f KB", value);
        value /= 1024.0;
        if (value < 1024) return String.format(Locale.US, "%.1f MB", value);
        value /= 1024.0;
        return String.format(Locale.US, "%.2f GB", value);
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.US);
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US);
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return null;
        for (String value : values) {
            String trimmed = blankToNull(value);
            if (trimmed != null) return trimmed;
        }
        return null;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}