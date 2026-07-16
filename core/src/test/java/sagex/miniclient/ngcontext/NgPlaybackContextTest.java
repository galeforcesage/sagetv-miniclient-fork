package sagex.miniclient.ngcontext;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class NgPlaybackContextTest {

    @Test
    public void testParseFullWireFormat() {
        String wire = "mediaFileId=12345|title=My+Show+S01E03|durationMs=3600000|contentType=recording"
                + "|isLive=false|isTimeshifted=false|seekableByClient=true"
                + "|chapterMarksMs=0,900000,1800000,2700000"
                + "|commercialBreaksMs=300000,600000,1500000,1800000"
                + "|scheduledStartMs=1700000000000|scheduledEndMs=1700003600000"
                + "|serverVersion=9.2.1|showId=ABC123";

        NgPlaybackContext ctx = NgPlaybackContextParser.parse(wire, "push:test");

        assertEquals("12345", ctx.getMediaFileId());
        assertEquals("My Show S01E03", ctx.getTitle());
        assertEquals(3600000L, ctx.getDurationMs());
        assertEquals("recording", ctx.getContentType());
        assertFalse(ctx.isLive());
        assertFalse(ctx.isTimeshifted());
        assertTrue(ctx.isSeekableByClient());
        assertEquals("push:test", ctx.getOpenUrl());

        long[] chapters = ctx.getChapterMarksMs();
        assertEquals(4, chapters.length);
        assertEquals(0L, chapters[0]);
        assertEquals(900000L, chapters[1]);
        assertEquals(1800000L, chapters[2]);
        assertEquals(2700000L, chapters[3]);

        long[] commercials = ctx.getCommercialBreaksMs();
        assertEquals(4, commercials.length);
        assertEquals(300000L, commercials[0]);
        assertEquals(600000L, commercials[1]);

        assertEquals(1700000000000L, ctx.getScheduledStartMs());
        assertEquals(1700003600000L, ctx.getScheduledEndMs());

        // Unknown keys go to extras
        assertEquals("9.2.1", ctx.getExtras().get("serverVersion"));
        assertEquals("ABC123", ctx.getExtras().get("showId"));
    }

    @Test
    public void testParseLiveTvContext() {
        String wire = "mediaFileId=99|title=CNN+Live|durationMs=-1|contentType=live"
                + "|isLive=true|isTimeshifted=true|seekableByClient=false"
                + "|channelName=CNN|channelNumber=202";

        NgPlaybackContext ctx = NgPlaybackContextParser.parse(wire, "push:live");

        assertEquals("99", ctx.getMediaFileId());
        assertEquals("CNN Live", ctx.getTitle());
        assertEquals(-1L, ctx.getDurationMs());
        assertEquals("live", ctx.getContentType());
        assertTrue(ctx.isLive());
        assertTrue(ctx.isTimeshifted());
        assertFalse(ctx.isSeekableByClient());
        assertEquals("CNN", ctx.getExtras().get("channelName"));
        assertEquals("202", ctx.getExtras().get("channelNumber"));
    }

    @Test
    public void testParseEmptyWire() {
        NgPlaybackContext ctx = NgPlaybackContextParser.parse("", null);

        assertNull(ctx.getMediaFileId());
        assertNull(ctx.getTitle());
        assertEquals(-1L, ctx.getDurationMs());
        assertNull(ctx.getContentType());
        assertFalse(ctx.isLive());
        assertFalse(ctx.isSeekableByClient());
        assertEquals(0, ctx.getChapterMarksMs().length);
        assertEquals(0, ctx.getCommercialBreaksMs().length);
        assertTrue(ctx.getExtras().isEmpty());
    }

    @Test
    public void testParseNullWire() {
        NgPlaybackContext ctx = NgPlaybackContextParser.parse(null, null);

        assertNull(ctx.getMediaFileId());
        assertEquals(-1L, ctx.getDurationMs());
    }

    @Test
    public void testFromMap() {
        Map<String, String> map = new HashMap<String, String>();
        map.put("mediaFileId", "777");
        map.put("title", "Test Title");
        map.put("durationMs", "120000");
        map.put("contentType", "import");
        map.put("isLive", "false");
        map.put("seekableByClient", "true");

        NgPlaybackContext ctx = NgPlaybackContextParser.fromMap(map, "stv://192.168.1.1/test.mp4");

        assertEquals("777", ctx.getMediaFileId());
        assertEquals("Test Title", ctx.getTitle());
        assertEquals(120000L, ctx.getDurationMs());
        assertEquals("import", ctx.getContentType());
        assertFalse(ctx.isLive());
        assertTrue(ctx.isSeekableByClient());
        assertEquals("stv://192.168.1.1/test.mp4", ctx.getOpenUrl());
    }

    @Test
    public void testUrlEncodedValues() {
        // title contains pipe and equals characters, URL-encoded
        String wire = "mediaFileId=1|title=Show+%7C+Episode+%3D+1|durationMs=60000|contentType=recording|isLive=false";

        NgPlaybackContext ctx = NgPlaybackContextParser.parse(wire, null);

        assertEquals("Show | Episode = 1", ctx.getTitle());
    }

    @Test
    public void testBuilderDefaults() {
        NgPlaybackContext ctx = new NgPlaybackContext.Builder().build();

        assertNull(ctx.getMediaFileId());
        assertNull(ctx.getTitle());
        assertEquals(-1L, ctx.getDurationMs());
        assertNull(ctx.getContentType());
        assertFalse(ctx.isLive());
        assertFalse(ctx.isTimeshifted());
        assertEquals(0L, ctx.getScheduledStartMs());
        assertEquals(0L, ctx.getScheduledEndMs());
        assertEquals(0, ctx.getChapterMarksMs().length);
        assertEquals(0, ctx.getCommercialBreaksMs().length);
        assertFalse(ctx.isSeekableByClient());
        assertTrue(ctx.getExtras().isEmpty());
        assertNull(ctx.getOpenUrl());
        assertTrue(ctx.getReceivedAtMs() > 0);
    }

    @Test
    public void testImmutability() {
        long[] chapters = {100, 200, 300};
        Map<String, String> extras = new HashMap<String, String>();
        extras.put("key", "val");

        NgPlaybackContext ctx = new NgPlaybackContext.Builder()
                .chapterMarksMs(chapters)
                .extras(extras)
                .build();

        // Mutate originals
        chapters[0] = 999;
        extras.put("key2", "val2");

        // Context should not be affected
        assertEquals(100L, ctx.getChapterMarksMs()[0]);
        assertFalse(ctx.getExtras().containsKey("key2"));
    }

    @Test
    public void testToString() {
        NgPlaybackContext ctx = new NgPlaybackContext.Builder()
                .mediaFileId("42")
                .title("Test")
                .build();

        String str = ctx.toString();
        assertTrue(str.contains("mediaFileId='42'"));
        assertTrue(str.contains("title='Test'"));
    }
}
