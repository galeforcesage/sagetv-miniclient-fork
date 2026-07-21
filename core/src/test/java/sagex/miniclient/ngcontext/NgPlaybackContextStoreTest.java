package sagex.miniclient.ngcontext;

import org.junit.Before;
import org.junit.Test;

import sagex.miniclient.IBus;

import static org.junit.Assert.*;

public class NgPlaybackContextStoreTest {

    private NgPlaybackContextStore store;
    private TestBus testBus;

    private static final String SAMPLE_JSON = """
            {"version":1,"sessionId":"sess-1","mediaFileId":123,"airingId":0,\
            "mode":"push","container":"ts","durationMs":5000,"serverMediaTimeMs":0,"streamEpoch":0,\
            "live":{"isLive":false,"recordingStartMs":0,"safeSeekStartMs":0,"safeSeekEndMs":0,\
            "playableEndMs":0,"growthBytes":0,"lastSizeRefreshMs":0},\
            "seek":{"preferredGranularityMs":5000,"minSeekIntervalMs":250,"maxClientCoalesceMs":1500,\
            "requiresServerSeek":true,"clientMayPredictOsd":false},\
            "index":{"hasKeyframeIndex":false,"hasPtsByteMap":false,"ptsSamples":[]},\
            "skip":{"commercials":[],"chapters":[],"bookmarks":[]},\
            "flow":{"preferredPrebufferBytes":262144,"lowWatermarkBytes":131072,"highWatermarkBytes":4194304}}""";

    private static final String LIVE_JSON = """
            {"version":1,"sessionId":"sess-2","mediaFileId":456,"airingId":0,\
            "mode":"push","container":"ts","durationMs":0,"serverMediaTimeMs":0,"streamEpoch":0,\
            "live":{"isLive":true,"recordingStartMs":0,"safeSeekStartMs":0,"safeSeekEndMs":90000,\
            "playableEndMs":100000,"growthBytes":0,"lastSizeRefreshMs":0},\
            "seek":{"preferredGranularityMs":30000,"minSeekIntervalMs":250,"maxClientCoalesceMs":1500,\
            "requiresServerSeek":true,"clientMayPredictOsd":false},\
            "index":{"hasKeyframeIndex":false,"hasPtsByteMap":false,"ptsSamples":[]},\
            "skip":{"commercials":[],"chapters":[],"bookmarks":[]},\
            "flow":{"preferredPrebufferBytes":262144,"lowWatermarkBytes":131072,"highWatermarkBytes":4194304}}""";

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
        store.onPropertyReceived(SAMPLE_JSON);

        var ctx = store.getCurrent();
        assertNotNull(ctx);
        assertEquals(123L, ctx.mediaFileId());
        assertEquals("push", ctx.mode());
        assertEquals("push:video.ts", ctx.openUrl());
    }

    @Test
    public void testOnPropertyReceivedPostsEvent() {
        store.onMediaOpen("push:x");
        store.onPropertyReceived(SAMPLE_JSON);

        assertNotNull(testBus.lastEvent);
        assertTrue(testBus.lastEvent instanceof NgPlaybackContextEvent.Updated);
        var evt = (NgPlaybackContextEvent.Updated) testBus.lastEvent;
        assertNull(evt.previous());
        assertNotNull(evt.current());
        assertEquals(123L, evt.current().mediaFileId());
    }

    @Test
    public void testOnMediaCloseClearsContext() {
        store.onMediaOpen("push:x");
        store.onPropertyReceived(SAMPLE_JSON);
        assertNotNull(store.getCurrent());

        store.onMediaClose();
        assertNull(store.getCurrent());

        assertTrue(testBus.lastEvent instanceof NgPlaybackContextEvent.Cleared);
    }

    @Test
    public void testEmptyPropertyClearsContext() {
        store.onMediaOpen("push:x");
        store.onPropertyReceived(SAMPLE_JSON);
        assertNotNull(store.getCurrent());

        store.onPropertyReceived("");
        assertNull(store.getCurrent());
    }

    @Test
    public void testSeekPolicyUpdatedOnPropertyReceived() {
        store.onMediaOpen("push:x");
        store.onPropertyReceived(LIVE_JSON);

        var policy = store.getSeekPolicy();
        assertNotNull(policy);
        assertNotNull(policy.getContext());
        assertTrue(policy.needsLivePoll());
        // Clamping works — safeSeekEndMs=90000
        assertEquals(90_000L, policy.clampSeekTarget(120_000));
    }

    @Test
    public void testSeekPolicyClearedOnMediaClose() {
        store.onMediaOpen("push:x");
        store.onPropertyReceived(LIVE_JSON);
        assertNotNull(store.getSeekPolicy().getContext());

        store.onMediaClose();
        assertNull(store.getSeekPolicy().getContext());
    }

    @Test
    public void testShutdownCleansUp() {
        store.onMediaOpen("push:x");
        store.onPropertyReceived(SAMPLE_JSON);
        store.shutdown();
        assertNull(store.getCurrent());
    }

    @Test
    public void testNullBusDoesNotCrash() {
        var nullBusStore = new NgPlaybackContextStore(null);
        nullBusStore.onMediaOpen("push:x");
        nullBusStore.onPropertyReceived(SAMPLE_JSON);
        assertNotNull(nullBusStore.getCurrent());
        nullBusStore.onMediaClose();
        assertNull(nullBusStore.getCurrent());
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
