package sagex.miniclient.party;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.Iterator;
import java.util.regex.Pattern;

public final class PartyProtocol {
    public static final String PROPERTY_NAME = "PARTY_V1";
    public static final String REQUIRED_CAPABILITIES =
            "PARTY_V1,PARTY_JOIN_CODE_V1,PARTY_MONOTONIC_V1,"
                    + "PARTY_SCHEDULED_V1,PARTY_HEARTBEAT_V1,"
                    + "PARTY_PLAYER_ADAPTER_V1,PARTY_LOCAL_STOP_V1,"
                    + "PARTY_GROWING_MEDIA_V1";
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_JSON_BYTES = 8192;
    public static final int MAX_RECEIVE_DEPTH = 5;

    private static final byte[] PROPERTY_NAME_BYTES =
            PROPERTY_NAME.getBytes(StandardCharsets.US_ASCII);
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final Pattern JOIN_CODE = Pattern.compile("[0-9]{3}");
    private static final String[] IDENTIFIER_FIELDS = {
            "requestId", "serverEpoch", "sessionHandle", "sessionToken",
            "partyId", "participantId", "recoveryToken", "probeId",
            "preparationId", "originParticipantId", "hostParticipantId"
    };

    private PartyProtocol() {
    }

    public static byte[] encodePropertyPayload(JSONObject envelope)
            throws PartyProtocolException {
        validateEnvelope(envelope);
        byte[] value = envelope.toString().getBytes(StandardCharsets.UTF_8);
        if (value.length > MAX_JSON_BYTES) {
            throw new PartyProtocolException("Party JSON exceeds 8192 UTF-8 bytes");
        }
        ByteBuffer result = ByteBuffer.allocate(
                4 + PROPERTY_NAME_BYTES.length + value.length).order(ByteOrder.BIG_ENDIAN);
        result.putShort((short) PROPERTY_NAME_BYTES.length);
        result.putShort((short) value.length);
        result.put(PROPERTY_NAME_BYTES);
        result.put(value);
        return result.array();
    }

    public static byte[] encodeClientFrame(JSONObject envelope, int timestamp, int id)
            throws PartyProtocolException {
        byte[] payload = encodePropertyPayload(envelope);
        if (payload.length > 0xFFFFFF) {
            throw new PartyProtocolException("Party property payload is too large");
        }
        ByteBuffer result = ByteBuffer.allocate(16 + payload.length).order(ByteOrder.BIG_ENDIAN);
        result.put((byte) 1);
        putU24(result, payload.length);
        result.putInt(timestamp);
        result.putInt(id);
        result.putInt(0);
        result.put(payload);
        return result.array();
    }

    public static JSONObject decodeServerFrame(byte[] frame) throws PartyProtocolException {
        if (frame == null || frame.length < 4) {
            throw new PartyProtocolException("Party server frame is truncated");
        }
        if ((frame[0] & 0xFF) != 1) {
            throw new PartyProtocolException("Party server frame is not SET_PROPERTY");
        }
        int payloadLength = ((frame[1] & 0xFF) << 16)
                | ((frame[2] & 0xFF) << 8)
                | (frame[3] & 0xFF);
        if (payloadLength != frame.length - 4) {
            throw new PartyProtocolException("Party server payload length mismatch");
        }
        return decodePropertyPayload(frame, 4, payloadLength);
    }

    public static JSONObject decodePropertyPayload(byte[] payload, int offset, int length)
            throws PartyProtocolException {
        if (payload == null || offset < 0 || length < 4
                || offset + length > payload.length) {
            throw new PartyProtocolException("Party property payload is truncated");
        }
        int nameLength = ((payload[offset] & 0xFF) << 8) | (payload[offset + 1] & 0xFF);
        int valueLength = ((payload[offset + 2] & 0xFF) << 8) | (payload[offset + 3] & 0xFF);
        if (length != 4 + nameLength + valueLength) {
            throw new PartyProtocolException("Party property length mismatch");
        }
        String name = new String(payload, offset + 4, nameLength, StandardCharsets.US_ASCII);
        if (!PROPERTY_NAME.equals(name)) {
            throw new PartyProtocolException("Unexpected Party property name");
        }
        if (valueLength > MAX_JSON_BYTES) {
            throw new PartyProtocolException("Party JSON exceeds 8192 UTF-8 bytes");
        }
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload, offset + 4 + nameLength, valueLength))
                    .toString();
            JSONObject envelope = new JSONObject(json);
            validateEnvelope(envelope);
            return envelope;
        } catch (JSONException | CharacterCodingException e) {
            throw new PartyProtocolException("Invalid Party JSON", e);
        }
    }

    public static JSONObject parseEnvelope(String json) throws PartyProtocolException {
        if (json == null) {
            throw new PartyProtocolException("Party JSON is missing");
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_JSON_BYTES) {
            throw new PartyProtocolException("Party JSON exceeds 8192 UTF-8 bytes");
        }
        try {
            JSONObject envelope = new JSONObject(json);
            validateEnvelope(envelope);
            return envelope;
        } catch (JSONException e) {
            throw new PartyProtocolException("Invalid Party JSON", e);
        }
    }

    public static void validateEnvelope(JSONObject envelope) throws PartyProtocolException {
        if (envelope == null) {
            throw new PartyProtocolException("Party envelope is missing");
        }
        Object schema = envelope.opt("schemaVersion");
        if (!(schema instanceof Number)
                || ((Number) schema).longValue() != SCHEMA_VERSION
                || ((Number) schema).doubleValue() != SCHEMA_VERSION) {
            throw new PartyProtocolException("Party schemaVersion must be numeric 1");
        }
        Object type = envelope.opt("type");
        if (!(type instanceof String) || ((String) type).isEmpty()) {
            throw new PartyProtocolException("Party type is missing");
        }
        if (jsonDepth(envelope, 1) > MAX_RECEIVE_DEPTH) {
            throw new PartyProtocolException("Party JSON nesting exceeds depth 5");
        }
        if (envelope.toString().getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new PartyProtocolException("Party JSON exceeds 8192 UTF-8 bytes");
        }
        if (envelope.has("requestSeq")) requirePositiveSequence(envelope);
        for (String field : IDENTIFIER_FIELDS) {
            if (envelope.has(field) && !envelope.isNull(field)) {
                requireIdentifier(envelope.optString(field, null), field);
            }
        }
        if (envelope.has("joinCode") && !envelope.isNull("joinCode")) {
            requireJoinCode(envelope.optString("joinCode", null));
        }
    }

    public static void requireIdentifier(String value, String field)
            throws PartyProtocolException {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new PartyProtocolException("Invalid Party identifier: " + field);
        }
    }

    public static void requireJoinCode(String value) throws PartyProtocolException {
        if (value == null || !JOIN_CODE.matcher(value).matches()) {
            throw new PartyProtocolException("Join code must contain exactly three digits");
        }
    }

    public static long requirePositiveSequence(JSONObject envelope)
            throws PartyProtocolException {
        Object raw = envelope.opt("requestSeq");
        if (!(raw instanceof Number)) {
            throw new PartyProtocolException("requestSeq must be numeric");
        }
        double asDouble = ((Number) raw).doubleValue();
        long value = ((Number) raw).longValue();
        if (!Double.isFinite(asDouble) || asDouble != value || value <= 0
                || value > 9_007_199_254_740_991L) {
            throw new PartyProtocolException("requestSeq must be a positive safe integer");
        }
        return value;
    }

    private static int jsonDepth(Object value, int depth) throws PartyProtocolException {
        if (depth > MAX_RECEIVE_DEPTH) {
            return depth;
        }
        try {
            if (value instanceof JSONObject) {
                int max = depth;
                Iterator<String> keys = ((JSONObject) value).keys();
                while (keys.hasNext()) {
                    Object child = ((JSONObject) value).get(keys.next());
                    max = Math.max(max,
                            jsonDepth(child, isContainer(child) ? depth + 1 : depth));
                }
                return max;
            }
            if (value instanceof JSONArray) {
                int max = depth;
                JSONArray array = (JSONArray) value;
                for (int i = 0; i < array.length(); i++) {
                    Object child = array.get(i);
                    max = Math.max(max,
                            jsonDepth(child, isContainer(child) ? depth + 1 : depth));
                }
                return max;
            }

            return depth;
        } catch (JSONException e) {
            throw new PartyProtocolException("Invalid Party JSON value", e);
        }
    }

    private static boolean isContainer(Object value) {
        return value instanceof JSONObject || value instanceof JSONArray;
    }

    private static void putU24(ByteBuffer buffer, int value) {
        buffer.put((byte) ((value >>> 16) & 0xFF));
        buffer.put((byte) ((value >>> 8) & 0xFF));
        buffer.put((byte) (value & 0xFF));
    }
}
