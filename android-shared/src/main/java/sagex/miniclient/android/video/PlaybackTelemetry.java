package sagex.miniclient.android.video;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PlaybackTelemetry
{
    public static final PlaybackTelemetry UNAVAILABLE = new Builder().build();

    public final String decoderName;
    public final boolean hardwareDecoder;
    public final long bufferedDurationMs;
    public final int rebufferCount;
    public final long stalledDurationMs;
    public final long decodedFrames;
    public final long droppedFrames;
    public final long decoderLatencyMs;
    public final double audioVideoClockDifferenceMs;
    public final long latestSeekTargetMs;
    public final long latestSeekPositionMs;
    public final boolean networkDisconnected;
    public final List<Track> tracks;

    private PlaybackTelemetry(Builder builder)
    {
        decoderName = builder.decoderName;
        hardwareDecoder = builder.hardwareDecoder;
        bufferedDurationMs = builder.bufferedDurationMs;
        rebufferCount = builder.rebufferCount;
        stalledDurationMs = builder.stalledDurationMs;
        decodedFrames = builder.decodedFrames;
        droppedFrames = builder.droppedFrames;
        decoderLatencyMs = builder.decoderLatencyMs;
        audioVideoClockDifferenceMs = builder.audioVideoClockDifferenceMs;
        latestSeekTargetMs = builder.latestSeekTargetMs;
        latestSeekPositionMs = builder.latestSeekPositionMs;
        networkDisconnected = builder.networkDisconnected;
        tracks = Collections.unmodifiableList(new ArrayList<>(builder.tracks));
    }

    public long getLatestSeekErrorMs()
    {
        if (latestSeekTargetMs < 0 || latestSeekPositionMs < 0) return -1;
        return latestSeekPositionMs - latestSeekTargetMs;
    }

    public static final class Track
    {
        public final String id;
        public final String type;
        public final String language;
        public final String codec;
        public final long channelLayout;
        public final boolean selected;

        public Track(String id, String type, String language, String codec,
                     long channelLayout, boolean selected)
        {
            this.id = id;
            this.type = type;
            this.language = language;
            this.codec = codec;
            this.channelLayout = channelLayout;
            this.selected = selected;
        }
    }

    public static final class Builder
    {
        private String decoderName = "";
        private boolean hardwareDecoder;
        private long bufferedDurationMs = -1;
        private int rebufferCount;
        private long stalledDurationMs;
        private long decodedFrames = -1;
        private long droppedFrames = -1;
        private long decoderLatencyMs = -1;
        private double audioVideoClockDifferenceMs = Double.NaN;
        private long latestSeekTargetMs = -1;
        private long latestSeekPositionMs = -1;
        private boolean networkDisconnected;
        private List<Track> tracks = Collections.emptyList();

        public Builder decoder(String name, boolean hardware)
        {
            decoderName = name == null ? "" : name;
            hardwareDecoder = hardware;
            return this;
        }

        public Builder bufferedDurationMs(long value) { bufferedDurationMs = value; return this; }
        public Builder rebuffer(int count, long durationMs) { rebufferCount = count; stalledDurationMs = durationMs; return this; }
        public Builder frameCounts(long decoded, long dropped) { decodedFrames = decoded; droppedFrames = dropped; return this; }
        public Builder decoderLatencyMs(long value) { decoderLatencyMs = value; return this; }
        public Builder audioVideoClockDifferenceMs(double value) { audioVideoClockDifferenceMs = value; return this; }
        public Builder latestSeek(long targetMs, long positionMs) { latestSeekTargetMs = targetMs; latestSeekPositionMs = positionMs; return this; }
        public Builder networkDisconnected(boolean value) { networkDisconnected = value; return this; }
        public Builder tracks(List<Track> value) { tracks = value == null ? Collections.<Track>emptyList() : value; return this; }
        public PlaybackTelemetry build() { return new PlaybackTelemetry(this); }
    }
}
