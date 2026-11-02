package sagex.miniclient.android.video;

import org.junit.Test;

import sagex.miniclient.streaminfo.StreamInfo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlayerSelectionUtilTest {

    private static StreamInfo stream(String container, String codec, boolean interlaced) {
        return new StreamInfo.Builder()
                .container(container)
                .addVideo(new StreamInfo.VideoTrack(
                        codec,
                        StreamInfo.mimeForCodec(codec),
                        1920,
                        1080,
                        30000.0 / 1001.0,
                        interlaced,
                        "video-0",
                        true))
                .build();
    }

    @Test
    public void routesInterlacedMpeg2MatroskaToIjk() {
        PlayerSelectionUtil.PlayerDecision decision = PlayerSelectionUtil.decide(
                null,
                "push:f=MATROSKA;[bf=vid;f=MPEG2-VIDEO;]",
                true,
                false,
                stream(StreamInfo.CONTAINER_MATROSKA, "MPEG2-VIDEO", true),
                true,
                true,
                "");

        assertFalse(decision.useExoPlayer);
        assertTrue(decision.swapReason.contains("interlaced MPEG2-in-Matroska"));
    }

    @Test
    public void keepsProgressiveMpeg2MatroskaOnExo() {
        PlayerSelectionUtil.PlayerDecision decision = PlayerSelectionUtil.decide(
                null,
                "push:f=MATROSKA;[bf=vid;f=MPEG2-VIDEO;]",
                true,
                false,
                stream(StreamInfo.CONTAINER_MATROSKA, "MPEG2-VIDEO", false),
                true,
                true,
                "");

        assertTrue(decision.useExoPlayer);
    }

    @Test
    public void keepsInterlacedMpeg2TransportStreamOnExoWhenDecoderExists() {
        PlayerSelectionUtil.PlayerDecision decision = PlayerSelectionUtil.decide(
                null,
                "push:f=MPEG2-TS;[bf=vid;f=MPEG2-VIDEO;]",
                true,
                false,
                stream(StreamInfo.CONTAINER_MPEG2_TS, "MPEG2-VIDEO", true),
                true,
                true,
                "");

        assertTrue(decision.useExoPlayer);
    }
}
