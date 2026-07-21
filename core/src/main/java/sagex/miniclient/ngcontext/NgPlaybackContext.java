package sagex.miniclient.ngcontext;

import java.util.List;

/**
 * Immutable value object representing rich playback metadata sent by an NG-aware SageTV server.
 * When connected to a legacy server this object will never be created — consumers must always
 * null-check via {@link NgPlaybackContextStore#getCurrent()}.
 *
 * <p>Structure mirrors the server's {@code sage.ng.NgPlaybackContext} JSON wire format exactly:
 * <pre>
 * {
 *   "version": 1,
 *   "sessionId": "...",
 *   "mediaFileId": 12345,
 *   "airingId": 67890,
 *   "mode": "push",
 *   "container": "mpeg-ps",
 *   "durationMs": 3600000,
 *   "serverMediaTimeMs": 1234567,
 *   "streamEpoch": 1,
 *   "live": { ... },
 *   "seek": { ... },
 *   "index": { ... },
 *   "skip": { ... },
 *   "flow": { ... }
 * }
 * </pre>
 */
public record NgPlaybackContext(
        int version,
        String sessionId,
        long mediaFileId,
        long airingId,
        String mode,
        String container,
        long durationMs,
        long serverMediaTimeMs,
        int streamEpoch,
        LiveContext live,
        SeekPolicy seek,
        IndexContext index,
        SkipContext skip,
        FlowPolicy flow,
        // --- client-side metadata (not from wire) ---
        String openUrl,
        long receivedAtMs
) {
    public NgPlaybackContext {
        sessionId = sessionId != null ? sessionId : "";
        mode = mode != null ? mode : "unknown";
        container = container != null ? container : "unknown";
        live = live != null ? live : LiveContext.EMPTY;
        seek = seek != null ? seek : SeekPolicy.DEFAULT;
        index = index != null ? index : IndexContext.EMPTY;
        skip = skip != null ? skip : SkipContext.EMPTY;
        flow = flow != null ? flow : FlowPolicy.DEFAULT;
        receivedAtMs = receivedAtMs > 0 ? receivedAtMs : System.currentTimeMillis();
    }

    /** Live/timeshift window state. Mirrors server's NgLiveContext. */
    public record LiveContext(
            boolean isLive,
            long recordingStartMs,
            long safeSeekStartMs,
            long safeSeekEndMs,
            long playableEndMs,
            long growthBytes,
            long lastSizeRefreshMs
    ) {
        public static final LiveContext EMPTY = new LiveContext(false, 0, 0, 0, 0, 0, 0);
    }

    /** Server-advisory seek policy. Mirrors server's NgSeekPolicy. */
    public record SeekPolicy(
            long preferredGranularityMs,
            long minSeekIntervalMs,
            long maxClientCoalesceMs,
            boolean requiresServerSeek,
            boolean clientMayPredictOsd
    ) {
        public static final SeekPolicy DEFAULT = new SeekPolicy(5000, 250, 1500, true, false);
    }

    /** Index availability for client-assisted seeking. Mirrors server's NgIndexContext. */
    public record IndexContext(
            boolean hasKeyframeIndex,
            boolean hasPtsByteMap,
            List<PtsSample> ptsSamples
    ) {
        public IndexContext {
            ptsSamples = ptsSamples != null ? List.copyOf(ptsSamples) : List.of();
        }
        public static final IndexContext EMPTY = new IndexContext(false, false, List.of());
    }

    /** A single PTS-to-byte sample. Mirrors server's NgPtsSample. */
    public record PtsSample(long timeMs, long byteOffset, boolean keyframe) {}

    /** Skip/chapter/bookmark data. Mirrors server's NgSkipContext. */
    public record SkipContext(
            List<SkipSegment> commercials,
            List<SkipSegment> chapters,
            List<SkipSegment> bookmarks
    ) {
        public SkipContext {
            commercials = commercials != null ? List.copyOf(commercials) : List.of();
            chapters = chapters != null ? List.copyOf(chapters) : List.of();
            bookmarks = bookmarks != null ? List.copyOf(bookmarks) : List.of();
        }
        public static final SkipContext EMPTY = new SkipContext(List.of(), List.of(), List.of());
    }

    /** A single skip segment. Mirrors server's NgSkipSegment. */
    public record SkipSegment(long startMs, long endMs, String type, long prerollMs) {
        public SkipSegment {
            type = type != null ? type : "unknown";
        }
    }

    /** Buffer flow policy. Mirrors server's NgFlowPolicy. */
    public record FlowPolicy(
            int preferredPrebufferBytes,
            int lowWatermarkBytes,
            int highWatermarkBytes
    ) {
        public static final FlowPolicy DEFAULT = new FlowPolicy(262144, 131072, 4194304);
    }

    @Override
    public String toString() {
        return "NgPlaybackContext[v=%d, session=%s, mediaFile=%d, mode=%s, container=%s, durationMs=%d, live=%s]"
                .formatted(version, sessionId, mediaFileId, mode, container, durationMs, live.isLive());
    }

    /** Convenience builder for parser and test code. */
    public static final class Builder {
        private int version = 1;
        private String sessionId;
        private long mediaFileId;
        private long airingId;
        private String mode;
        private String container;
        private long durationMs;
        private long serverMediaTimeMs;
        private int streamEpoch;
        private LiveContext live;
        private SeekPolicy seek;
        private IndexContext index;
        private SkipContext skip;
        private FlowPolicy flow;
        private String openUrl;
        private long receivedAtMs;

        public Builder version(int val) { this.version = val; return this; }
        public Builder sessionId(String val) { this.sessionId = val; return this; }
        public Builder mediaFileId(long val) { this.mediaFileId = val; return this; }
        public Builder airingId(long val) { this.airingId = val; return this; }
        public Builder mode(String val) { this.mode = val; return this; }
        public Builder container(String val) { this.container = val; return this; }
        public Builder durationMs(long val) { this.durationMs = val; return this; }
        public Builder serverMediaTimeMs(long val) { this.serverMediaTimeMs = val; return this; }
        public Builder streamEpoch(int val) { this.streamEpoch = val; return this; }
        public Builder live(LiveContext val) { this.live = val; return this; }
        public Builder seek(SeekPolicy val) { this.seek = val; return this; }
        public Builder index(IndexContext val) { this.index = val; return this; }
        public Builder skip(SkipContext val) { this.skip = val; return this; }
        public Builder flow(FlowPolicy val) { this.flow = val; return this; }
        public Builder openUrl(String val) { this.openUrl = val; return this; }
        public Builder receivedAtMs(long val) { this.receivedAtMs = val; return this; }

        public NgPlaybackContext build() {
            return new NgPlaybackContext(
                    version, sessionId, mediaFileId, airingId,
                    mode, container, durationMs, serverMediaTimeMs, streamEpoch,
                    live, seek, index, skip, flow,
                    openUrl, receivedAtMs
            );
        }
    }
}
