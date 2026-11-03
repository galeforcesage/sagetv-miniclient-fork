package sagex.miniclient.party;

import org.json.JSONObject;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PartyProtocolTest {
    private static JSONObject golden() throws Exception {
        try (InputStream input = PartyProtocolTest.class.getResourceAsStream(
                "/party/v1/golden.json")) {
            if (input == null) throw new IllegalStateException("missing golden.json");
            byte[] bytes = new byte[64 * 1024];
            int count = input.read(bytes);
            return new JSONObject(new String(bytes, 0, count, StandardCharsets.UTF_8));
        }
    }

    @Test
    public void goldenHelloUsesCanonicalClientFrame() throws Exception {
        JSONObject hello = golden().getJSONObject("hello");
        byte[] frame = PartyProtocol.encodeClientFrame(hello, 0, 0);

        assertEquals(1, frame[0] & 0xFF);
        int payloadLength = ((frame[1] & 0xFF) << 16)
                | ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
        assertEquals(frame.length - 16, payloadLength);
        assertEquals(8, ((frame[16] & 0xFF) << 8) | (frame[17] & 0xFF));
        assertEquals("PARTY_V1",
                new String(frame, 20, 8, StandardCharsets.US_ASCII));
        assertEquals(hello.toString(),
                new JSONObject(new String(frame, 28, frame.length - 28,
                        StandardCharsets.UTF_8)).toString());
    }

    @Test
    public void serverFrameUsesAsymmetricFourByteHeader() throws Exception {
        JSONObject command = golden().getJSONObject("command");
        byte[] payload = PartyProtocol.encodePropertyPayload(command);
        byte[] frame = new byte[4 + payload.length];
        frame[0] = 1;
        frame[1] = (byte) ((payload.length >>> 16) & 0xFF);
        frame[2] = (byte) ((payload.length >>> 8) & 0xFF);
        frame[3] = (byte) (payload.length & 0xFF);
        System.arraycopy(payload, 0, frame, 4, payload.length);

        assertEquals(command.toString(), PartyProtocol.decodeServerFrame(frame).toString());
    }

    @Test
    public void exactRequestRetryReusesIdenticalFraming() throws Exception {
        JSONObject action = golden().getJSONObject("action");

        assertArrayEquals(
                PartyProtocol.encodeClientFrame(action, 0, 7),
                PartyProtocol.encodeClientFrame(action, 0, 7));
    }

    @Test
    public void utf8LimitCountsBytesNotCharacters() {
        JSONObject envelope = new JSONObject()
                .put("schemaVersion", 1)
                .put("type", "TEST")
                .put("value", "é".repeat(4096));

        expectProtocolFailure(() -> PartyProtocol.encodePropertyPayload(envelope));
    }

    @Test
    public void preservesLeadingZeroJoinCodes() throws Exception {
        PartyProtocol.requireJoinCode("042");
        expectProtocolFailure(() -> PartyProtocol.requireJoinCode("42"));
        assertEquals("042", golden().getJSONObject("join").getString("joinCode"));
    }

    @Test
    public void validatesOpaqueIdentifiersAndJoinCodes() {
        expectProtocolFailure(() -> PartyProtocol.validateEnvelope(
                new JSONObject()
                        .put("schemaVersion", 1)
                        .put("type", "JOIN")
                        .put("requestId", "join 1")
                        .put("requestSeq", 1)
                        .put("joinCode", "042")));
        expectProtocolFailure(() -> PartyProtocol.validateEnvelope(
                new JSONObject()
                        .put("schemaVersion", 1)
                        .put("type", "JOIN_CODE")
                        .put("joinCode", "42")));
    }

    @Test
    public void duplicateReadyIsAnIdempotentAckWithoutDeadline() throws Exception {
        JSONObject readyAck = golden().getJSONObject("readyAck");

        assertEquals("READY_ACK", readyAck.getString("type"));
        assertEquals(golden().getJSONObject("ready").getLong("stateVersion"),
                readyAck.getLong("stateVersion"));
        assertEquals(golden().getJSONObject("correction").getLong("commandSeq"),
                readyAck.getLong("commandSeq"));
        assertTrue(!readyAck.has("executeAtServerMonoMs"));
    }

    @Test
    public void requiredCapabilitiesExcludeUntestedGrowingMedia() {
        assertEquals(
                "PARTY_V1,PARTY_JOIN_CODE_V1,PARTY_MONOTONIC_V1,"
                        + "PARTY_SCHEDULED_V1,PARTY_HEARTBEAT_V1,"
                        + "PARTY_PLAYER_ADAPTER_V1,PARTY_LOCAL_STOP_V1",
                PartyProtocol.REQUIRED_CAPABILITIES);
        assertTrue(!PartyProtocol.REQUIRED_CAPABILITIES.contains(
                "PARTY_GROWING_MEDIA_V1"));
    }

    @Test
    public void localStopAndHostTransferMatchCanonicalGolden() throws Exception {
        JSONObject localStop = golden().getJSONObject("localStop");
        JSONObject hostChanged = golden().getJSONObject("hostChanged");

        assertEquals("HEARTBEAT", localStop.getString("type"));
        assertEquals("STOPPED", localStop.getString("state"));
        assertEquals("HOST_CHANGED", hostChanged.getString("type"));
        assertEquals("guest", hostChanged.getString("hostParticipantId"));
    }

    @Test
    public void rejectsInvalidSchemaAndExcessNesting() {
        expectProtocolFailure(
                () -> PartyProtocol.validateEnvelope(new JSONObject()
                        .put("schemaVersion", "1").put("type", "HELLO")));

        JSONObject nested = new JSONObject().put("leaf", true);
        for (int i = 0; i < 5; i++) nested = new JSONObject().put("nested", nested);
        JSONObject envelope = new JSONObject()
                .put("schemaVersion", 1).put("type", "TEST").put("value", nested);
        expectProtocolFailure(() -> PartyProtocol.validateEnvelope(envelope));
    }

    @Test
    public void acceptsDepthFiveJoinedSnapshotDelivery() throws Exception {
        JSONObject delivery = new JSONObject().put("mode", "push");
        JSONObject member = new JSONObject().put("delivery", delivery);
        org.json.JSONArray roster = new org.json.JSONArray().put(member);
        JSONObject snapshot = new JSONObject().put("roster", roster);
        JSONObject joined = new JSONObject()
                .put("schemaVersion", 1)
                .put("type", "JOINED")
                .put("snapshot", snapshot);

        PartyProtocol.validateEnvelope(joined);
    }

    @Test
    public void rejectsMalformedUtf8RatherThanReplacingIt() throws Exception {
        byte[] valid = PartyProtocol.encodePropertyPayload(golden().getJSONObject("hello"));
        valid[valid.length - 1] = (byte) 0xFF;
        expectProtocolFailure(() -> PartyProtocol.decodePropertyPayload(valid, 0, valid.length));
    }

    @Test
    public void clockRequiresFiveFreshConfidentSamples() {
        PartyClockModel four = new PartyClockModel(100, 5, 10, true, 4, 1_000);
        PartyClockModel five = new PartyClockModel(100, 5, 10, true, 5, 1_000);

        assertTrue(!four.isConfident(1_001));
        assertTrue(five.isConfident(1_001));
        assertEquals(100_650, five.clientDeadline(100_750));
        assertTrue(five.isConfident(60_999));
        assertTrue(!five.isConfident(61_001));
    }

    private static void expectProtocolFailure(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (PartyProtocolException expected) {
            return;
        } catch (Exception unexpected) {
            throw new AssertionError(unexpected);
        }
        throw new AssertionError("Expected PartyProtocolException");
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
