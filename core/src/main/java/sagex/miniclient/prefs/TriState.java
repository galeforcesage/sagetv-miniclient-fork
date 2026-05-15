package sagex.miniclient.prefs;

/**
 * Three-state user preference: AUTO (let the system decide), ON (force on), OFF (force off).
 *
 * <p>This is the storage representation used by every "Automatic-capable" setting
 * in the Phase 2 UX redo. The persisted string form is {@code "auto"} / {@code "on"}
 * / {@code "off"} (the result of {@link #toPrefValue()}). The default for any
 * setting backed by {@code TriState} is {@link #AUTO}.</p>
 *
 * <p>Use {@link #resolve(boolean)} when consuming a {@code TriState} to obtain a
 * boolean: in {@link #AUTO} mode the auto-detected value wins; in {@link #ON} or
 * {@link #OFF} the user's explicit override is honoured.</p>
 */
public enum TriState
{
    AUTO,
    ON,
    OFF;

    /** Persisted string form. Stable across app versions. */
    public String toPrefValue()
    {
        switch (this)
        {
            case ON:  return "on";
            case OFF: return "off";
            default:  return "auto";
        }
    }

    /** Inverse of {@link #toPrefValue()}. Unknown / null values map to {@link #AUTO}. */
    public static TriState fromPrefValue(String s)
    {
        if (s == null) return AUTO;
        switch (s.toLowerCase())
        {
            case "on":      // explicit override on
            case "true":    // legacy boolean form
            case "enabled": // legacy ListPreference label
                return ON;
            case "off":
            case "false":
            case "disabled":
                return OFF;
            default:
                return AUTO;
        }
    }

    /**
     * Resolve to a boolean. {@link #AUTO} returns {@code autoValue}; explicit
     * states return their pinned value regardless of detection.
     */
    public boolean resolve(boolean autoValue)
    {
        switch (this)
        {
            case ON:  return true;
            case OFF: return false;
            default:  return autoValue;
        }
    }

    /** True when the current state came from auto-detection rather than a user override. */
    public boolean isAuto() { return this == AUTO; }

    /** True when the user has pinned an explicit value. */
    public boolean isOverride() { return this != AUTO; }

    /** Cycle order used by the tri-state UI widget: AUTO → ON → OFF → AUTO. */
    public TriState next()
    {
        switch (this)
        {
            case AUTO: return ON;
            case ON:   return OFF;
            default:   return AUTO;
        }
    }
}
