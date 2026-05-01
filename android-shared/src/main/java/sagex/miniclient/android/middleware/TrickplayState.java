package sagex.miniclient.android.middleware;

/**
 * Trickplay state machine states.
 * Maps 1:1 to the native TrickplayState enum in sage_transport.h.
 */
public enum TrickplayState
{
    STABLE_PLAYING(0),
    STABLE_PAUSED(1),
    SEEK_PENDING(2),
    SEEK_COMMITTING(3),
    RECOVERING(4);

    private final int nativeValue;

    TrickplayState(int nativeValue)
    {
        this.nativeValue = nativeValue;
    }

    public int getNativeValue()
    {
        return nativeValue;
    }

    public static TrickplayState fromNative(int value)
    {
        switch (value)
        {
            case 0: return STABLE_PLAYING;
            case 1: return STABLE_PAUSED;
            case 2: return SEEK_PENDING;
            case 3: return SEEK_COMMITTING;
            case 4: return RECOVERING;
            default: return STABLE_PAUSED;
        }
    }

    public boolean isStable()
    {
        return this == STABLE_PLAYING || this == STABLE_PAUSED;
    }

    public boolean isTimeFrozen()
    {
        return this == SEEK_PENDING || this == SEEK_COMMITTING || this == RECOVERING;
    }
}
