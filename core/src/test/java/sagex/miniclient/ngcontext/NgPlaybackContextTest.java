package sagex.miniclient.ngcontext;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for {@link NgPlaybackContext} record and {@link NgPlaybackContextParser}.
 * Validates JSON wire format matches the server's NgPlaybackContextSerializer output.
 */
public class NgPlaybackContextTest {

    /** Example JSON matching the server's NgPlaybackContextSerializer output. */
    private static final String FULL_CONTEXT_JSON = """
            {"version":1,"sessionId":"abc-123-uuid","mediaFileId":12345,"airingId":67890,\
            "mode":"push","container":"mpeg-ps","durationMs":3600000,"serverMediaTimeMs":120000,\
            "streamEpoch":1,\
            "live":{"isLive":true,"recordingStartMs":1700000000000,"safeSeekStartMs":0,\
            "safeSeekEndMs":3500000,"playableEndMs":3600000,"growthBytes":1048576,"lastSizeRefreshMs":1700003590000},\
            "seek":{"preferredGranularityMs":30000,"minSeekIntervalMs":250,"maxClientCoalesceMs":1500,\
            "requiresServerSeek":true,"clientMayPredictOsd":false},\
            "index":{"hasKeyframeIndex":true,"hasPtsByteMap":false,"ptsSamples":[\
            {"timeMs":0,"byteOffset":0,"keyframe":true},{"timeMs":30000,"byteOffset":5242880,"keyframe":true}]},\
            "skip":{"commercials":[{"startMs":300000,"endMs":600000,"type":"commercial","prerollMs":2000}],\
            "chapters":[{"startMs":0,"endMs":900000,"type":"chapter","prerollMs":0}],"bookmarks":[]},\
            "flow":{"preferredPrebufferBytes":262144,"lowWatermarkBytes":131072,"highWatermarkBytes":4194304}}""";

    @Test
    public void testParseFullServerJson() {
        var ctx = NgPlaybackContextParser.parse(FULL_CONTEXT_JSON, "push:test.mpg");

        assertEquals(1, ctx.version());
        assertEquals("abc-123-uuid", ctx.sessionId());
        assertEquals(12345L, ctx.mediaFileId());
        assertEquals(67890L, ctx.airingId());
        assertEquals("push", ctx.mode());
        assertEquals("mpeg-ps", ctx.container());
        assertEquals(3600000L, ctx.durationMs());
        assertEquals(120000L, ctx.serverMediaTimeMs());
        assertEquals(1, ctx.streamEpoch());
        assertEquals("push:test.mpg", ctx.openUrl());

        // Live context
        assertTrue(ctx.live().isLive());
        assertEquals(1700000000000L, ctx.live().recordingStartMs());
        assertEquals(0L, ctx.live().safeSeekStartMs());
        assertEquals(3500000L, ctx.live().safeSeekEndMs());
        assertEquals(3600000L, ctx.live().playableEndMs());
        assertEquals(1048576L, ctx.live().growthBytes());

        // Seek policy
        assertEquals(30000L, ctx.seek().preferredGranularityMs());
        assertEquals(250L, ctx.seek().minSeekIntervalMs());
        assertEquals(1500L, ctx.seek().maxClientCoalesceMs());
        assertTrue(ctx.seek().requiresServerSeek());
        assertFalse(ctx.seek().clientMayPredictOsd());

        // Index
        assertTrue(ctx.index().hasKeyframeIndex());
        assertFalse(ctx.index().hasPtsByteMap());
        assertEquals(2, ctx.index().ptsSamples().size());
        assertEquals(0L, ctx.index().ptsSamples().get(0).timeMs());
        assertEquals(5242880L, ctx.index().ptsSamples().get(1).byteOffset());
        assertTrue(ctx.index().ptsSamples().get(1).keyframe());

        // Skip
        assertEquals(1, ctx.skip().commercials().size());
        var commercial = ctx.skip().commercials().get(0);
        assertEquals(300000L, commercial.startMs());
        assertEquals(600000L, commercial.endMs());
        assertEquals("commercial", commercial.type());
        assertEquals(2000L, commercial.prerollMs());
        assertEquals(1, ctx.skip().chapters().size());
        assertTrue(ctx.skip().bookmarks().isEmpty());

        // Flow
        assertEquals(262144, ctx.flow().preferredPrebufferBytes());
        assertEquals(131072, ctx.flow().lowWatermarkBytes());
        assertEquals(4194304, ctx.flow().highWatermarkBytes());
    }

    @Test
    public void testParseHttpResponseWrapper() {
        var wrapped = """
                {"type":"NG_PLAYBACK_CONTEXT","sessionId":"abc-123",\
                "context":{"version":1,"sessionId":"abc-123","mediaFileId":999,"airingId":0,\
                "mode":"pull","container":"mp4","durationMs":60000,"serverMediaTimeMs":0,"streamEpoch":0,\
                "live":{"isLive":false,"recordingStartMs":0,"safeSeekStartMs":0,"safeSeekEndMs":0,\
                "playableEndMs":0,"growthBytes":0,"lastSizeRefreshMs":0},\
                "seek":{"preferredGranularityMs":5000,"minSeekIntervalMs":250,"maxClientCoalesceMs":1500,\
                "requiresServerSeek":true,"clientMayPredictOsd":false},\
                "index":{"hasKeyframeIndex":false,"hasPtsByteMap":false,"ptsSamples":[]},\
                "skip":{"commercials":[],"chapters":[],"bookmarks":[]},\
                "flow":{"preferredPrebufferBytes":262144,"lowWatermarkBytes":131072,"highWatermarkBytes":4194304}}}""";

        var ctx = NgPlaybackContextParser.parse(wrapped, "stv://file.mp4");

        assertEquals(999L, ctx.mediaFileId());
        assertEquals("pull", ctx.mode());
        assertEquals("mp4", ctx.container());
        assertEquals(60000L, ctx.durationMs());
        assertFalse(ctx.live().isLive());
        assertEquals("stv://file.mp4", ctx.openUrl());
    }

    @Test
    public void testParseEmptyString() {
        var ctx = NgPlaybackContextParser.parse("", null);
        assertEquals(1, ctx.version());
        assertEquals("", ctx.sessionId());
        assertEquals(0L, ctx.mediaFileId());
        assertEquals("unknown", ctx.mode());
        assertFalse(ctx.live().isLive());
    }

    @Test
    public void testParseNull() {
        var ctx = NgPlaybackContextParser.parse(null, "push:x");
        assertEquals("push:x", ctx.openUrl());
        assertEquals(0L, ctx.mediaFileId());
    }

    @Test
    public void testParseMalformedJson() {
        var ctx = NgPlaybackContextParser.parse("not json at all!", null);
        // Should not throw, returns defaults
        assertEquals(0L, ctx.mediaFileId());
        assertEquals("unknown", ctx.mode());
    }

    @Test
    public void testBuilderDefaults() {
        var ctx = new NgPlaybackContext.Builder().build();

        assertEquals(1, ctx.version());
        assertEquals("", ctx.sessionId());
        assertEquals(0L, ctx.mediaFileId());
        assertEquals("unknown", ctx.mode());
        assertEquals("unknown", ctx.container());
        assertFalse(ctx.live().isLive());
        assertEquals(5000L, ctx.seek().preferredGranularityMs()); // SeekPolicy.DEFAULT
        assertTrue(ctx.skip().commercials().isEmpty());
        assertTrue(ctx.receivedAtMs() > 0);
    }

    @Test
    public void testMinimalJsonNoSubObjects() {
        var json = """
                {"version":1,"sessionId":"s1","mediaFileId":42,"airingId":0,\
                "mode":"push","container":"ts","durationMs":1000,"serverMediaTimeMs":0,"streamEpoch":0}""";

        var ctx = NgPlaybackContextParser.parse(json, null);

        assertEquals(42L, ctx.mediaFileId());
        assertEquals("push", ctx.mode());
        // Defaults for missing sub-objects
        assertFalse(ctx.live().isLive());
        assertEquals(5000L, ctx.seek().preferredGranularityMs());
        assertFalse(ctx.index().hasKeyframeIndex());
        assertTrue(ctx.skip().commercials().isEmpty());
        assertEquals(262144, ctx.flow().preferredPrebufferBytes());
    }
}
