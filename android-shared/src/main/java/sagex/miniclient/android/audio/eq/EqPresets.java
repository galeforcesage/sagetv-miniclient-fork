package sagex.miniclient.android.audio.eq;

/**
 * Built-in equalizer presets matching the PWA implementation.
 * Each preset is a 10-element gain array (dB) for the canonical frequency set.
 */
public final class EqPresets
{
    private EqPresets() { }

    public static final String[] PRESET_NAMES = {
            "Flat", "Rock", "Pop", "Jazz", "Classical",
            "Bass Boost", "Treble Boost", "Vocal", "Loudness", "Custom"
    };

    /** 10-band gain arrays for each preset (matches PWA presets.js). */
    private static final float[][] PRESET_GAINS = {
            // Flat
            { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 },
            // Rock
            { 5, 4, 3, -1, -2, -1, 2, 3, 4, 4 },
            // Pop: warm low-mids + forward vocals + airy top
            { -1, 0, 2, 3, 2, 0, 1, 3, 4, 3 },
            // Jazz
            { 3, 2, 1, 2, 3, 2, 1, 0, -1, -2 },
            // Classical: natural balance, smooth high extension
            { 3, 2, 1, 0, 0, 0, 1, 2, 3, 3 },
            // Bass Boost
            { 8, 6, 4, 2, 0, 0, 0, 0, 0, 0 },
            // Treble Boost
            { 0, 0, 0, 0, 0, 1, 2, 4, 6, 8 },
            // Vocal: cut sub-bass, add body at 250, peak presence 1-4kHz
            { -4, -3, -1, 1, 2, 3, 4, 3, 1, -2 },
            // Loudness
            { 6, 4, 1, -1, -2, -1, 1, 3, 5, 6 },
            // Custom (user-defined, starts flat)
            { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 },
    };

    /** Get preset gains by name (case-insensitive). Returns null if not found. */
    public static float[] getPresetGains(String name)
    {
        if (name == null) return null;
        for (int i = 0; i < PRESET_NAMES.length; i++)
        {
            if (PRESET_NAMES[i].equalsIgnoreCase(name))
            {
                return PRESET_GAINS[i].clone();
            }
        }
        return null;
    }

    /** Apply a preset to settings (sets band gains and preset name). */
    public static void applyPreset(EqSettings settings, String presetName)
    {
        float[] gains = getPresetGains(presetName);
        if (gains == null) return;
        for (int i = 0; i < EqSettings.BAND_COUNT; i++)
        {
            settings.setBandGain(i, gains[i]);
        }
        settings.setPresetName(presetName);
    }

    /**
     * Detect which preset matches the current band gains.
     * Returns "Custom" if no built-in preset matches.
     */
    public static String detectPreset(EqSettings settings)
    {
        float[] current = settings.getBandGains();
        for (int i = 0; i < PRESET_NAMES.length; i++)
        {
            if ("Custom".equals(PRESET_NAMES[i])) continue;
            boolean match = true;
            for (int j = 0; j < EqSettings.BAND_COUNT; j++)
            {
                if (Math.abs(current[j] - PRESET_GAINS[i][j]) > 0.01f)
                {
                    match = false;
                    break;
                }
            }
            if (match) return PRESET_NAMES[i];
        }
        return "Custom";
    }

    /**
     * Map 10-band preset gains to a 5-band subset for legacy Equalizer.
     * Maps: band0→31Hz, band1→63Hz, band2→250Hz, band3→1kHz, band4→8kHz
     * Uses nearest canonical band or averages adjacent bands.
     */
    public static float[] mapTo5Bands(float[] tenBandGains)
    {
        if (tenBandGains == null || tenBandGains.length != 10) return new float[5];
        return new float[]{
                tenBandGains[0],                           // ~60Hz  ← 31Hz band
                (tenBandGains[1] + tenBandGains[2]) / 2f,  // ~230Hz ← avg(63, 125)
                (tenBandGains[3] + tenBandGains[4]) / 2f,  // ~910Hz ← avg(250, 500)
                (tenBandGains[5] + tenBandGains[6]) / 2f,  // ~3.6kHz ← avg(1k, 2k)
                (tenBandGains[8] + tenBandGains[9]) / 2f,  // ~14kHz ← avg(8k, 16k)
        };
    }
}
