package sagex.miniclient.ngcontext;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable value object representing rich playback metadata sent by an NG-aware SageTV server.
 * When connected to a legacy server this object will never be created — consumers must always
 * null-check via {@link NgPlaybackContextStore#getCurrent()}.
 */
public final class NgPlaybackContext {

    // --- canonical fields (agreed with server) ---
    private final String mediaFileId;
    private final String title;
    private final long durationMs;
    private final String contentType;
    private final boolean isLive;
    private final boolean isTimeshifted;
    private final long scheduledStartMs;
    private final long scheduledEndMs;

    // --- trickplay hints ---
    private final long[] chapterMarksMs;
    private final long[] commercialBreaksMs;
    private final boolean seekableByClient;

    // --- extensibility ---
    private final Map<String, String> extras;

    // --- generated on client ---
    private final String openUrl;
    private final long receivedAtMs;

    private NgPlaybackContext(Builder builder) {
        this.mediaFileId = builder.mediaFileId;
        this.title = builder.title;
        this.durationMs = builder.durationMs;
        this.contentType = builder.contentType;
        this.isLive = builder.isLive;
        this.isTimeshifted = builder.isTimeshifted;
        this.scheduledStartMs = builder.scheduledStartMs;
        this.scheduledEndMs = builder.scheduledEndMs;
        this.chapterMarksMs = builder.chapterMarksMs != null ? builder.chapterMarksMs.clone() : new long[0];
        this.commercialBreaksMs = builder.commercialBreaksMs != null ? builder.commercialBreaksMs.clone() : new long[0];
        this.seekableByClient = builder.seekableByClient;
        this.extras = builder.extras != null
                ? Collections.unmodifiableMap(new HashMap<String, String>(builder.extras))
                : Collections.<String, String>emptyMap();
        this.openUrl = builder.openUrl;
        this.receivedAtMs = builder.receivedAtMs > 0 ? builder.receivedAtMs : System.currentTimeMillis();
    }

    public String getMediaFileId() { return mediaFileId; }
    public String getTitle() { return title; }
    public long getDurationMs() { return durationMs; }
    public String getContentType() { return contentType; }
    public boolean isLive() { return isLive; }
    public boolean isTimeshifted() { return isTimeshifted; }
    public long getScheduledStartMs() { return scheduledStartMs; }
    public long getScheduledEndMs() { return scheduledEndMs; }
    public long[] getChapterMarksMs() { return chapterMarksMs.clone(); }
    public long[] getCommercialBreaksMs() { return commercialBreaksMs.clone(); }
    public boolean isSeekableByClient() { return seekableByClient; }
    public Map<String, String> getExtras() { return extras; }
    public String getOpenUrl() { return openUrl; }
    public long getReceivedAtMs() { return receivedAtMs; }

    @Override
    public String toString() {
        return "NgPlaybackContext{" +
                "mediaFileId='" + mediaFileId + '\'' +
                ", title='" + title + '\'' +
                ", durationMs=" + durationMs +
                ", contentType='" + contentType + '\'' +
                ", isLive=" + isLive +
                ", seekableByClient=" + seekableByClient +
                ", openUrl='" + openUrl + '\'' +
                '}';
    }

    public static class Builder {
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
            return new NgPlaybackContext(this);
        }
    }
}
