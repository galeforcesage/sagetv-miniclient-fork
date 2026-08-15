package sagex.miniclient.streaminfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Pre-stream metadata delivered by the SageTV-NG server via MEDIACMD_STREAMINFO
 * (command 40) BEFORE MEDIACMD_OPENURL. Lets the client pre-select the demuxer /
 * player and pre-configure decoders so it can start decoding the first pushed
 * bytes immediately, eliminating the sniff/probe delay (and the failed-open →
 * retry → landmine-swap cycles) that dominate cold-start latency.
 *
 * <p>Wire schema (JSON) and canonical codec names are defined by the server's
 * STREAMINFO specification (v1). Unknown fields are ignored (forward compatible);
 * unknown {@code version} values are parsed best-effort.</p>
 *
 * <p>Legacy servers never send command 40, so this type is simply absent for
 * them and no behavior changes.</p>
 */
public final class StreamInfo {

    /** SageTV canonical container names (wire {@code container} field). */
    public static final String CONTAINER_MPEG2_TS = "MPEG2-TS";
    public static final String CONTAINER_MPEG2_PS = "MPEG2-PS";
    public static final String CONTAINER_MPEG1_PS = "MPEG1-PS";
    public static final String CONTAINER_MATROSKA = "MATROSKA";
    public static final String CONTAINER_MP4 = "MP4";
    public static final String CONTAINER_AVI = "AVI";
    public static final String CONTAINER_FLV = "FLV";

    public final int version;
    public final String container;   // canonical SageTV name, e.g. "MPEG2-TS"
    public final long durationMs;    // 0 if absent / live
    public final boolean live;
    public final long bitrate;       // bits/sec, 0 if absent
    public final List<VideoTrack> video;
    public final List<AudioTrack> audio;
    public final List<SubtitleTrack> subtitle;

    private StreamInfo(Builder b) {
        this.version = b.version;
        this.container = b.container;
        this.durationMs = b.durationMs;
        this.live = b.live;
        this.bitrate = b.bitrate;
        this.video = Collections.unmodifiableList(new ArrayList<>(b.video));
        this.audio = Collections.unmodifiableList(new ArrayList<>(b.audio));
        this.subtitle = Collections.unmodifiableList(new ArrayList<>(b.subtitle));
    }

    /** @return the primary video track (first {@code primary}, else first), or null. */
    public VideoTrack primaryVideo() {
        VideoTrack first = null;
        for (VideoTrack v : video) {
            if (first == null) first = v;
            if (v.primary) return v;
        }
        return first;
    }

    /** @return the primary audio track (first {@code primary}, else first), or null. */
    public AudioTrack primaryAudio() {
        AudioTrack first = null;
        for (AudioTrack a : audio) {
            if (first == null) first = a;
            if (a.primary) return a;
        }
        return first;
    }

    /** @return canonical container name normalized for comparison (never null). */
    public String containerUpper() {
        return container == null ? "" : container.toUpperCase(Locale.ROOT);
    }

    public boolean isProgramStream() {
        String c = containerUpper();
        return CONTAINER_MPEG2_PS.equals(c) || CONTAINER_MPEG1_PS.equals(c);
    }

    @Override
    public String toString() {
        return "StreamInfo{v=" + version + ", container=" + container
                + ", live=" + live + ", durationMs=" + durationMs
                + ", video=" + video + ", audio=" + audio
                + ", subtitle=" + subtitle.size() + "}";
    }

    // ── Codec name → MIME mapping (STREAMINFO spec, v1) ──────────────────

    /**
     * Map a SageTV canonical codec name to an Android/ExoPlayer MIME type.
     * Case-insensitive. Returns null for unknown codecs so callers can fall
     * back to sniffing rather than mis-configuring a decoder.
     */
    public static String mimeForCodec(String sageCodec) {
        if (sageCodec == null) return null;
        switch (sageCodec.trim().toUpperCase(Locale.ROOT)) {
            // Video
            case "H.264":
            case "H264":         return "video/avc";
            case "HEVC":
            case "H.265":
            case "H265":         return "video/hevc";
            case "MPEG2-VIDEO":
            case "MPEG2VIDEO":   return "video/mpeg2";
            case "MPEG4-VIDEO":
            case "MPEG4":        return "video/mp4v-es";
            case "AV1":          return "video/av01";
            // Audio
            case "AC3":          return "audio/ac3";
            case "EAC3":         return "audio/eac3";
            case "AC4":          return "audio/ac4";
            case "AAC":          return "audio/mp4a-latm";
            case "MP2":          return "audio/mpeg-L2";
            case "MP3":          return "audio/mpeg";
            case "DTS":          return "audio/vnd.dts";
            case "FLAC":         return "audio/flac";
            case "PCM":          return "audio/raw";
            default:             return null;
        }
    }

    // ── Track types ─────────────────────────────────────────────────────

    public static final class VideoTrack {
        public final String codec;   // canonical SageTV name
        public final String mime;    // mapped MIME (may be null if unknown)
        public final int width;
        public final int height;
        public final double fps;
        public final boolean interlaced;
        public final String id;
        public final boolean primary;

        public VideoTrack(String codec, String mime, int width, int height,
                          double fps, boolean interlaced, String id, boolean primary) {
            this.codec = codec;
            this.mime = mime;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.interlaced = interlaced;
            this.id = id;
            this.primary = primary;
        }

        public boolean isMpeg4Part2() {
            return "video/mp4v-es".equals(mime);
        }

        public boolean isHevc() {
            return "video/hevc".equals(mime);
        }

        public boolean isMpeg2Video() {
            return "video/mpeg2".equals(mime);
        }

        @Override
        public String toString() {
            return codec + "(" + mime + "," + width + "x" + height
                    + (interlaced ? "i" : "p") + ")";
        }
    }

    public static final class AudioTrack {
        public final String codec;
        public final String mime;
        public final int channels;
        public final int sampleRate;
        public final int bitsPerSample;
        public final long bitrate;
        public final String language;
        public final boolean primary;
        public final String id;

        public AudioTrack(String codec, String mime, int channels, int sampleRate,
                          int bitsPerSample, long bitrate, String language,
                          boolean primary, String id) {
            this.codec = codec;
            this.mime = mime;
            this.channels = channels;
            this.sampleRate = sampleRate;
            this.bitsPerSample = bitsPerSample;
            this.bitrate = bitrate;
            this.language = language;
            this.primary = primary;
            this.id = id;
        }

        @Override
        public String toString() {
            return codec + "(" + mime + "," + channels + "ch,"
                    + language + (primary ? ",primary" : "") + ")";
        }
    }

    public static final class SubtitleTrack {
        public final String codec;
        public final String language;
        public final String id;

        public SubtitleTrack(String codec, String language, String id) {
            this.codec = codec;
            this.language = language;
            this.id = id;
        }

        @Override
        public String toString() {
            return codec + "(" + language + ")";
        }
    }

    // ── Builder ─────────────────────────────────────────────────────────

    public static final class Builder {
        private int version = 1;
        private String container = "unknown";
        private long durationMs = 0;
        private boolean live = false;
        private long bitrate = 0;
        private final List<VideoTrack> video = new ArrayList<>();
        private final List<AudioTrack> audio = new ArrayList<>();
        private final List<SubtitleTrack> subtitle = new ArrayList<>();

        public Builder version(int v) { this.version = v; return this; }
        public Builder container(String c) { this.container = c; return this; }
        public Builder durationMs(long d) { this.durationMs = d; return this; }
        public Builder live(boolean l) { this.live = l; return this; }
        public Builder bitrate(long b) { this.bitrate = b; return this; }
        public Builder addVideo(VideoTrack v) { this.video.add(v); return this; }
        public Builder addAudio(AudioTrack a) { this.audio.add(a); return this; }
        public Builder addSubtitle(SubtitleTrack s) { this.subtitle.add(s); return this; }

        public StreamInfo build() { return new StreamInfo(this); }
    }
}
