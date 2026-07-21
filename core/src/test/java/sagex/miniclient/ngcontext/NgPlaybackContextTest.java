package sagex.miniclient.ngcontext;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class NgPlaybackContextTest {

    @Test
    public void testParseFullWireFormat() {
        var wire = "mediaFileId=12345|title=My+Show+S01E03|durationMs=3600000|contentType=recording"
                + "|isLive=false|isTimeshifted=false|seekableByClient=true"
                + "|chapterMarksMs=0,900000,1800000,2700000"
                + "|commercialBreaksMs=300000,600000,1500000,1800000"
                + "|scheduledStartMs=1700000000000|scheduledEndMs=1700003600000"
                + "|serverVersion=9.2.1|showId=ABC123";

        var ctx = NgPlaybackContextParser.parse(wire, "push:test");

        assertEquals("12345", ctx.mediaFileId());
        assertEquals("My Show S01E03", ctx.title());
        assertEquals(3600000L, ctx.durationMs());
        assertEquals("recording", ctx.contentType());
        assertFalse(ctx.isLive());
        assertFalse(ctx.isTimeshifted());
        assertTrue(ctx.seekableByClient());
        assertEquals("push:test", ctx.openUrl());

        var chapters = ctx.chapterMarksMs();
        assertEquals(4, chapters.length);
        assertEquals(0L, chapters[0]);
        assertEquals(900000L, chapters[1]);
        assertEquals(1800000L, chapters[2]);
        assertEquals(2700000L, chapters[3]);

        var commercials = ctx.commercialBreaksMs();
        assertEquals(4, commercials.length);
        assertEquals(300000L, commercials[0]);
        assertEquals(600000L, commercials[1]);

        assertEquals(1700000000000L, ctx.scheduledStartMs());
        assertEquals(1700003600000L, ctx.scheduledEndMs());

        // Unknown keys go to extras
        assertEquals("9.2.1", ctx.extras().get("serverVersion"));
        assertEquals("ABC123", ctx.extras().get("showId"));
    }

    @Test
    public void testParseLiveTvContext() {
        var wire = "mediaFileId=99|title=CNN+Live|durationMs=-1|contentType=live"
                + "|isLive=true|isTimeshifted=true|seekableByClient=false"
                + "|channelName=CNN|channelNumber=202";

        var ctx = NgPlaybackContextParser.parse(wire, "push:live");

        assertEquals("99", ctx.mediaFileId());
        assertEquals("CNN Live", ctx.title());
        assertEquals(-1L, ctx.durationMs());
        assertEquals("live", ctx.contentType());
        assertTrue(ctx.isLive());
        assertTrue(ctx.isTimeshifted());
        assertFalse(ctx.seekableByClient());
        assertEquals("CNN", ctx.extras().get("channelName"));
        assertEquals("202", ctx.extras().get("channelNumber"));
    }

    @Test
    public void testParseEmptyWire() {
        var ctx = NgPlaybackContextParser.parse("", null);

        assertNull(ctx.mediaFileId());
        assertNull(ctx.title());
        assertEquals(-1L, ctx.durationMs());
        assertNull(ctx.contentType());
        assertFalse(ctx.isLive());
        assertFalse(ctx.seekableByClient());
        assertEquals(0, ctx.chapterMarksMs().length);
        assertEquals(0, ctx.commercialBreaksMs().length);
        assertTrue(ctx.extras().isEmpty());
    }

    @Test
    public void testParseNullWire() {
        var ctx = NgPlaybackContextParser.parse(null, null);

        assertNull(ctx.mediaFileId());
        assertEquals(-1L, ctx.durationMs());
    }

    @Test
    public void testFromMap() {
        var map = new HashMap<String, String>();
        map.put("mediaFileId", "777");
        map.put("title", "Test Title");
        map.put("durationMs", "120000");
        map.put("contentType", "import");
        map.put("isLive", "false");
        map.put("seekableByClient", "true");

        var ctx = NgPlaybackContextParser.fromMap(map, "stv://192.168.1.1/test.mp4");

        assertEquals("777", ctx.mediaFileId());
        assertEquals("Test Title", ctx.title());
        assertEquals(120000L, ctx.durationMs());
        assertEquals("import", ctx.contentType());
        assertFalse(ctx.isLive());
        assertTrue(ctx.seekableByClient());
        assertEquals("stv://192.168.1.1/test.mp4", ctx.openUrl());
    }

    @Test
    public void testUrlEncodedValues() {
        var wire = "mediaFileId=1|title=Show+%7C+Episode+%3D+1|durationMs=60000|contentType=recording|isLive=false";

        var ctx = NgPlaybackContextParser.parse(wire, null);

        assertEquals("Show | Episode = 1", ctx.title());
    }

    @Test
    public void testBuilderDefaults() {
        var ctx = new NgPlaybackContext.Builder().build();

        assertNull(ctx.mediaFileId());
        assertNull(ctx.title());
        assertEquals(-1L, ctx.durationMs());
        assertNull(ctx.contentType());
        assertFalse(ctx.isLive());
        assertFalse(ctx.isTimeshifted());
        assertEquals(0L, ctx.scheduledStartMs());
        assertEquals(0L, ctx.scheduledEndMs());
        assertEquals(0, ctx.chapterMarksMs().length);
        assertEquals(0, ctx.commercialBreaksMs().length);
        assertFalse(ctx.seekableByClient());
        assertTrue(ctx.extras().isEmpty());
        assertNull(ctx.openUrl());
        assertTrue(ctx.receivedAtMs() > 0);
    }

    @Test
    public void testImmutability() {
        var chapters = new long[]{100, 200, 300};
        var extras = new HashMap<String, String>();
        extras.put("key", "val");

        var ctx = new NgPlaybackContext.Builder()
                .chapterMarksMs(chapters)
                .extras(extras)
                .build();

        // Mutate originals
        chapters[0] = 999;
        extras.put("key2", "val2");

        // Context should not be affected
        assertEquals(100L, ctx.chapterMarksMs()[0]);
        assertFalse(ctx.extras().containsKey("key2"));
    }

    @Test
    public void testToString() {
        var ctx = new NgPlaybackContext.Builder()
                .mediaFileId("42")
                .title("Test")
                .build();

        var str = ctx.toString();
        assertTrue(str.contains("mediaFileId=42"));
        assertTrue(str.contains("title=Test"));
    }

    @Test
    public void testPhase2FieldsParsing() {
        var wire = "mediaFileId=1|title=Live+News|durationMs=-1|contentType=live|isLive=true"
                + "|playableEndMs=300000|safeSeekEndMs=280000"
                + "|preferredGranularityMs=30000|maxClientCoalesceMs=500";

        var ctx = NgPlaybackContextParser.parse(wire, "push:live");

        assertEquals(300_000L, ctx.playableEndMs());
        assertEquals(280_000L, ctx.safeSeekEndMs());
        assertEquals(30_000L, ctx.preferredGranularityMs());
        assertEquals(500L, ctx.maxClientCoalesceMs());
    }

    @Test
    public void testPhase2FieldsDefaultWhenAbsent() {
        var wire = "mediaFileId=1|title=T|durationMs=100|contentType=recording|isLive=false";
        var ctx = NgPlaybackContextParser.parse(wire, null);

        assertEquals(-1L, ctx.playableEndMs());
        assertEquals(-1L, ctx.safeSeekEndMs());
        assertEquals(0L, ctx.preferredGranularityMs());
        assertEquals(0L, ctx.maxClientCoalesceMs());
    }
}
