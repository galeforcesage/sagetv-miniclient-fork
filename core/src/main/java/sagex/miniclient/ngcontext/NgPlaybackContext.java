package sagex.miniclient.ngcontext;

import java.util.Arrays;
import java.util.Map;

/**
 * Immutable value object representing rich playback metadata sent by an NG-aware SageTV server.
 * When connected to a legacy server this object will never be created — consumers must always
 * null-check via {@link NgPlaybackContextStore#getCurrent()}.
 *
 * <p>Uses Java 17 record for immutability, compact accessors, and automatic equals/hashCode/toString.
 * Arrays are defensively copied; the extras map is an unmodifiable snapshot.
 */
public record NgPlaybackContext(
        // --- canonical fields (agreed with server) ---
        String mediaFileId,
        String title,
        long durationMs,
        String contentType,
        boolean isLive,
        boolean isTimeshifted,
        long scheduledStartMs,
        long scheduledEndMs,
        // --- trickplay hints ---
        long[] chapterMarksMs,
        long[] commercialBreaksMs,
        boolean seekableByClient,
        // --- extensibility ---
        Map<String, String> extras,
        // --- generated on client ---
        String openUrl,
        long receivedAtMs
) {
    /** Compact constructor — defensive copies and null-safety. */
    public NgPlaybackContext {
        chapterMarksMs = chapterMarksMs != null ? chapterMarksMs.clone() : new long[0];
        commercialBreaksMs = commercialBreaksMs != null ? commercialBreaksMs.clone() : new long[0];
        extras = extras != null ? Map.copyOf(extras) : Map.of();
        receivedAtMs = receivedAtMs > 0 ? receivedAtMs : System.currentTimeMillis();
    }

    /** Defensive copy on access. */
    @Override public long[] chapterMarksMs() { return chapterMarksMs.clone(); }
    @Override public long[] commercialBreaksMs() { return commercialBreaksMs.clone(); }

    @Override
    public String toString() {
        return "NgPlaybackContext[mediaFileId=%s, title=%s, durationMs=%d, contentType=%s, isLive=%b, seekableByClient=%b, openUrl=%s]"
                .formatted(mediaFileId, title, durationMs, contentType, isLive, seekableByClient, openUrl);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NgPlaybackContext other
                && durationMs == other.durationMs
                && isLive == other.isLive
                && isTimeshifted == other.isTimeshifted
                && scheduledStartMs == other.scheduledStartMs
                && scheduledEndMs == other.scheduledEndMs
                && seekableByClient == other.seekableByClient
                && receivedAtMs == other.receivedAtMs
                && java.util.Objects.equals(mediaFileId, other.mediaFileId)
                && java.util.Objects.equals(title, other.title)
                && java.util.Objects.equals(contentType, other.contentType)
                && Arrays.equals(chapterMarksMs, other.chapterMarksMs)
                && Arrays.equals(commercialBreaksMs, other.commercialBreaksMs)
                && java.util.Objects.equals(extras, other.extras)
                && java.util.Objects.equals(openUrl, other.openUrl);
    }

    @Override
    public int hashCode() {
        int h = java.util.Objects.hash(mediaFileId, title, durationMs, contentType,
                isLive, isTimeshifted, scheduledStartMs, scheduledEndMs,
                seekableByClient, extras, openUrl, receivedAtMs);
        h = 31 * h + Arrays.hashCode(chapterMarksMs);
        h = 31 * h + Arrays.hashCode(commercialBreaksMs);
        return h;
    }

    /** Fluent builder for parser and test code. */
    public static final class Builder {
        private String mediaFileId;
        private String title;
        private long durationMs = -1;
        private String contentType;
        private boolean isLive;
        private boolean isTimeshifted;
        private long scheduledStartMs;
        private long scheduledEndMs;
        private long[] chapterMarksMs;
        private long[] commercialBreaksMs;
        private boolean seekableByClient;
        private Map<String, String> extras;
        private String openUrl;
        private long receivedAtMs;

        public Builder mediaFileId(String val) { this.mediaFileId = val; return this; }
        public Builder title(String val) { this.title = val; return this; }
        public Builder durationMs(long val) { this.durationMs = val; return this; }
        public Builder contentType(String val) { this.contentType = val; return this; }
        public Builder isLive(boolean val) { this.isLive = val; return this; }
        public Builder isTimeshifted(boolean val) { this.isTimeshifted = val; return this; }
        public Builder scheduledStartMs(long val) { this.scheduledStartMs = val; return this; }
        public Builder scheduledEndMs(long val) { this.scheduledEndMs = val; return this; }
        public Builder chapterMarksMs(long[] val) { this.chapterMarksMs = val; return this; }
        public Builder commercialBreaksMs(long[] val) { this.commercialBreaksMs = val; return this; }
        public Builder seekableByClient(boolean val) { this.seekableByClient = val; return this; }
        public Builder extras(Map<String, String> val) { this.extras = val; return this; }
        public Builder openUrl(String val) { this.openUrl = val; return this; }
        public Builder receivedAtMs(long val) { this.receivedAtMs = val; return this; }

        public NgPlaybackContext build() {
            return new NgPlaybackContext(
                    mediaFileId, title, durationMs, contentType,
                    isLive, isTimeshifted, scheduledStartMs, scheduledEndMs,
                    chapterMarksMs, commercialBreaksMs, seekableByClient,
                    extras, openUrl, receivedAtMs
            );
        }
    }
}
