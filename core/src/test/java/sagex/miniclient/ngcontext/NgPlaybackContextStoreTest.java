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
        assertNull(store.getCurrent());
    }

    @Test
    public void testOnPropertyReceivedSetsContext() {
        store.onMediaOpen("push:video.ts");
        store.onPropertyReceived("mediaFileId=123|title=Hello|durationMs=5000|contentType=recording|isLive=false");

        var ctx = store.getCurrent();
        assertNotNull(ctx);
        assertEquals("123", ctx.mediaFileId());
        assertEquals("Hello", ctx.title());
        assertEquals(5000L, ctx.durationMs());
        assertEquals("push:video.ts", ctx.openUrl());
    }

    @Test
    public void testOnPropertyReceivedPostsEvent() {
        store.onMediaOpen("push:x");
        store.onPropertyReceived("mediaFileId=1|title=T|durationMs=100|contentType=music|isLive=false");

        assertNotNull(testBus.lastEvent);
        assertTrue(testBus.lastEvent instanceof NgPlaybackContextEvent.Updated);
        var evt = (NgPlaybackContextEvent.Updated) testBus.lastEvent;
        assertNull(evt.previous());
        assertNotNull(evt.current());
        assertEquals("1", evt.current().mediaFileId());
    }

    @Test
    public void testOnMediaCloseClearsContext() {
        store.onMediaOpen("push:x");
        store.onPropertyReceived("mediaFileId=1|title=T|durationMs=100|contentType=music|isLive=false");
        assertNotNull(store.getCurrent());

        store.onMediaClose();
        assertNull(store.getCurrent());

        assertTrue(testBus.lastEvent instanceof NgPlaybackContextEvent.Cleared);
        var evt = (NgPlaybackContextEvent.Cleared) testBus.lastEvent;
        assertNotNull(evt.previous());
        assertEquals("1", evt.previous().mediaFileId());
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

        var ctx = store.getCurrent();
        assertEquals("2", ctx.mediaFileId());
        assertEquals("Second", ctx.title());
        assertTrue(ctx.isLive());

        var evt = (NgPlaybackContextEvent.Updated) testBus.lastEvent;
        assertNotNull(evt.previous());
        assertEquals("1", evt.previous().mediaFileId());
    }

    @Test
    public void testNullBusDoesNotCrash() {
        var nullBusStore = new NgPlaybackContextStore(null);
        nullBusStore.onMediaOpen("push:x");
        nullBusStore.onPropertyReceived("mediaFileId=1|title=T|durationMs=100|contentType=music|isLive=false");
        assertNotNull(nullBusStore.getCurrent());
        nullBusStore.onMediaClose();
        assertNull(nullBusStore.getCurrent());
    }

    @Test
    public void testSeekPolicyUpdatedOnPropertyReceived() {
        store.onMediaOpen("push:x");
        store.onPropertyReceived("mediaFileId=1|title=T|durationMs=100000|contentType=live|isLive=true|safeSeekEndMs=90000|preferredGranularityMs=30000");

        var policy = store.getSeekPolicy();
        assertNotNull(policy);
        assertNotNull(policy.getContext());
        assertTrue(policy.needsLivePoll());
        // Clamping works
        assertEquals(90_000L, policy.clampSeekTarget(120_000));
    }

    @Test
    public void testSeekPolicyClearedOnMediaClose() {
        store.onMediaOpen("push:x");
        store.onPropertyReceived("mediaFileId=1|title=T|durationMs=100000|contentType=live|isLive=true|safeSeekEndMs=90000|preferredGranularityMs=30000");
        assertNotNull(store.getSeekPolicy().getContext());

        store.onMediaClose();
        assertNull(store.getSeekPolicy().getContext());
    }

    @Test
    public void testShutdownCleansUp() {
        store.onMediaOpen("push:x");
        store.onPropertyReceived("mediaFileId=1|title=T|durationMs=100|contentType=music|isLive=false");
        store.shutdown();
        assertNull(store.getCurrent());
    }

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
