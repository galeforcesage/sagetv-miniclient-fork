package sagex.miniclient.android.video;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PlaybackTelemetryTest
{
    @Test
    public void reportsSignedSeekError()
    {
        PlaybackTelemetry telemetry = new PlaybackTelemetry.Builder()
                .latestSeek(10_000, 10_125)
                .build();

        assertEquals(125, telemetry.getLatestSeekErrorMs());
        assertEquals(-1, PlaybackTelemetry.UNAVAILABLE.getLatestSeekErrorMs());
    }

    @Test
    public void copiesAndFreezesTrackList()
    {
        List<PlaybackTelemetry.Track> tracks = new ArrayList<>();
        tracks.add(new PlaybackTelemetry.Track(
                "ijk:audio:0", "audio", "eng", "aac", 3, true));

        PlaybackTelemetry telemetry = new PlaybackTelemetry.Builder()
                .tracks(tracks)
                .build();
        tracks.clear();

        assertEquals(1, telemetry.tracks.size());
        assertTrue(telemetry.tracks.get(0).selected);
    }
}
