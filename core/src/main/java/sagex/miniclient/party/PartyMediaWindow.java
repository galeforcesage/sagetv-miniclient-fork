package sagex.miniclient.party;

import org.json.JSONObject;

final class PartyMediaWindow
{
    private boolean present;
    private boolean mediaGrowing;
    private long availableEndMs;
    private long playableEndMs;
    private long liveEdgeDelayMs;
    private long stateVersion = -1;

    boolean update(JSONObject envelope)
    {
        if (envelope == null || !hasAnyWindowField(envelope)) return true;
        if (!hasAllWindowFields(envelope)) return false;

        Object growingValue = envelope.opt("mediaGrowing");
        Long available = exactNonNegativeLong(envelope.opt("availableEndMs"));
        Long playable = exactNonNegativeLong(envelope.opt("playableEndMs"));
        Long delay = exactNonNegativeLong(envelope.opt("liveEdgeDelayMs"));
        long incomingVersion = envelope.optLong("stateVersion", stateVersion);
        if (!(growingValue instanceof Boolean) || available == null
                || playable == null || delay == null || playable > available)
        {
            return false;
        }
        if (present && incomingVersion >= 0 && incomingVersion < stateVersion)
        {
            return true;
        }

        present = true;
        mediaGrowing = (Boolean) growingValue;
        availableEndMs = available;
        playableEndMs = playable;
        liveEdgeDelayMs = delay;
        stateVersion = incomingVersion;
        return true;
    }

    void clear()
    {
        present = false;
        mediaGrowing = false;
        availableEndMs = 0;
        playableEndMs = 0;
        liveEdgeDelayMs = 0;
        stateVersion = -1;
    }

    long clampTarget(long targetPositionMs)
    {
        long target = Math.max(0, targetPositionMs);
        return present ? Math.min(target, playableEndMs) : target;
    }

    long clampBufferAhead(long positionMs, long bufferAheadMs)
    {
        if (bufferAheadMs < 0) return -1;
        if (!present) return bufferAheadMs;
        long playableAheadMs = Math.max(0, playableEndMs - Math.max(0, positionMs));
        return Math.min(bufferAheadMs, playableAheadMs);
    }

    long remainingPlayableMs(long positionMs, long durationMs)
    {
        long endMs = present ? playableEndMs : Math.max(0, durationMs);
        return Math.max(0, endMs - Math.max(0, positionMs));
    }

    boolean isPresent()
    {
        return present;
    }

    boolean isMediaGrowing()
    {
        return mediaGrowing;
    }

    long getAvailableEndMs()
    {
        return availableEndMs;
    }

    long getPlayableEndMs()
    {
        return playableEndMs;
    }

    long getLiveEdgeDelayMs()
    {
        return liveEdgeDelayMs;
    }

    private static boolean hasAnyWindowField(JSONObject envelope)
    {
        return envelope.has("mediaGrowing") || envelope.has("availableEndMs")
                || envelope.has("playableEndMs") || envelope.has("liveEdgeDelayMs");
    }

    private static boolean hasAllWindowFields(JSONObject envelope)
    {
        return envelope.has("mediaGrowing") && envelope.has("availableEndMs")
                && envelope.has("playableEndMs") && envelope.has("liveEdgeDelayMs");
    }

    private static Long exactNonNegativeLong(Object value)
    {
        if (!(value instanceof Number)) return null;
        double asDouble = ((Number) value).doubleValue();
        long asLong = ((Number) value).longValue();
        if (!Double.isFinite(asDouble) || asDouble != asLong || asLong < 0
                || asLong > 9_007_199_254_740_991L)
        {
            return null;
        }
        return asLong;
    }
}
