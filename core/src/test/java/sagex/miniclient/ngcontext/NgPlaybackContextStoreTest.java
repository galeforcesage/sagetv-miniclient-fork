package sagex.miniclient.ngcontext;

import org.junit.Before;
import org.junit.Test;

import sagex.miniclient.IBus;

import static org.junit.Assert.*;

public class NgPlaybackContextStoreTest {

    private NgPlaybackContextStore store;
    private TestBus testBus;

    @Before
    public void setUp() {
        testBus = new TestBus();
        store = new NgPlaybackContextStore(testBus);
    }

    @Test
    public void testInitialStateIsNull() {
        assertNull(store.getCurrent());
    }

    @Test
    public void testOnMediaOpenSetsUrl() {
        store.onMediaOpen("push:test.mpg");
        // Context is still null until server sends property
        assertNull(store.getCurrent());
    }

    @Test
    public void testOnPropertyReceivedSetsContext() {
        store.onMediaOpen("push:video.ts");
        store.onPropertyReceived("mediaFileId=123|title=Hello|durationMs=5000|contentType=recording|isLive=false");

        NgPlaybackContext ctx = store.getCurrent();
        assertNotNull(ctx);
        assertEquals("123", ctx.getMediaFileId());
        assertEquals("Hello", ctx.getTitle());
        assertEquals(5000L, ctx.getDurationMs());
        assertEquals("push:video.ts", ctx.getOpenUrl());
    }

    @Test
    public void testOnPropertyReceivedPostsEvent() {
        store.onMediaOpen("push:x");
        store.onPropertyReceived("mediaFileId=1|title=T|durationMs=100|contentType=music|isLive=false");

        assertNotNull(testBus.lastEvent);
        assertTrue(testBus.lastEvent instanceof NgPlaybackContextEvent);
        NgPlaybackContextEvent evt = (NgPlaybackContextEvent) testBus.lastEvent;
        assertNull(evt.previous);
        assertNotNull(evt.current);
        assertEquals("1", evt.current.getMediaFileId());
    }

    @Test
    public void testOnMediaCloseClearsContext() {
        store.onMediaOpen("push:x");
        store.onPropertyReceived("mediaFileId=1|title=T|durationMs=100|contentType=music|isLive=false");
        assertNotNull(store.getCurrent());

        store.onMediaClose();
        assertNull(store.getCurrent());

        // Should have posted an event with null current
        NgPlaybackContextEvent evt = (NgPlaybackContextEvent) testBus.lastEvent;
        assertNotNull(evt.previous);
        assertNull(evt.current);
    }

    @Test
    public void testEmptyPropertyClearsContext() {
        store.onMediaOpen("push:x");
        store.onPropertyReceived("mediaFileId=1|title=T|durationMs=100|contentType=music|isLive=false");
        assertNotNull(store.getCurrent());

        store.onPropertyReceived("");
        assertNull(store.getCurrent());
    }

    @Test
    public void testContextUpdateReplacesOld() {
        store.onMediaOpen("push:x");
        store.onPropertyReceived("mediaFileId=1|title=First|durationMs=100|contentType=recording|isLive=false");
        store.onPropertyReceived("mediaFileId=2|title=Second|durationMs=200|contentType=live|isLive=true");

        NgPlaybackContext ctx = store.getCurrent();
        assertEquals("2", ctx.getMediaFileId());
        assertEquals("Second", ctx.getTitle());
        assertTrue(ctx.isLive());

        // Event should have previous
        NgPlaybackContextEvent evt = (NgPlaybackContextEvent) testBus.lastEvent;
        assertNotNull(evt.previous);
        assertEquals("1", evt.previous.getMediaFileId());
    }

    @Test
    public void testNullBusDoesNotCrash() {
        NgPlaybackContextStore nullBusStore = new NgPlaybackContextStore(null);
        nullBusStore.onMediaOpen("push:x");
        nullBusStore.onPropertyReceived("mediaFileId=1|title=T|durationMs=100|contentType=music|isLive=false");
        assertNotNull(nullBusStore.getCurrent());
        nullBusStore.onMediaClose();
        assertNull(nullBusStore.getCurrent());
    }

    /**
     * Simple test bus implementation that captures the last posted event.
     */
    private static class TestBus implements IBus {
        Object lastEvent;

        @Override
        public void post(Object event) {
            lastEvent = event;
        }

        @Override
        public void register(Object handler) { }

        @Override
        public void unregister(Object handler) { }
    }
}
