package sagex.miniclient.android.audio.eq;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Canonical audio processing settings model.
 * Mirrors PWA's AudioProcessingSettings from models.js.
 *
 * <p>10 fixed bands matching af_equalizer.c:
 * 31.25, 62.5, 125, 250, 500, 1000, 2000, 4000, 8000, 16000 Hz.
 * Gain range: -12 to +12 dB, step 0.5.</p>
 */
public final class EqSettings
{
    public static final int SCHEMA_VERSION = 1;
    public static final int BAND_COUNT = 10;
    public static final float GAIN_MIN = -12f;
    public static final float GAIN_MAX = 12f;
    public static final float GAIN_STEP = 0.5f;

    /** Canonical center frequencies (Hz). */
    public static final float[] FREQUENCIES = {
            31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
    };

    /** Short display labels for each band. */
    public static final String[] FREQUENCY_LABELS = {
            "31", "63", "125", "250", "500", "1K", "2K", "4K", "8K", "16K"
    };

    private boolean enabled;
    private boolean clientProcessing;
    private String presetName;
    private float preampDb;
    private final float[] bandGains;   // 10 elements, dB
    private final float[] bandQ;       // 10 elements, Q factor
    private NightMode nightMode;
    private int settingsVersion;
    private String settingsHash;

    public EqSettings()
    {
        this.enabled = false;
        this.clientProcessing = true;
        this.presetName = "Flat";
        this.preampDb = 0f;
        this.bandGains = new float[BAND_COUNT];
        this.bandQ = new float[BAND_COUNT];
        for (int i = 0; i < BAND_COUNT; i++) bandQ[i] = 1.0f;
        this.nightMode = new NightMode();
        this.settingsVersion = 0;
        this.settingsHash = computeHash();
    }

    /** Deep copy constructor. */
    public EqSettings(EqSettings other)
    {
        this.enabled = other.enabled;
        this.clientProcessing = other.clientProcessing;
        this.presetName = other.presetName;
        this.preampDb = other.preampDb;
        this.bandGains = other.bandGains.clone();
        this.bandQ = other.bandQ.clone();
        this.nightMode = new NightMode(other.nightMode);
        this.settingsVersion = other.settingsVersion;
        this.settingsHash = other.settingsHash;
    }

    // ── Getters/Setters ────────────────────────────────────────────

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isClientProcessing() { return clientProcessing; }
    public void setClientProcessing(boolean clientProcessing) { this.clientProcessing = clientProcessing; }

    public String getPresetName() { return presetName; }
    public void setPresetName(String presetName) { this.presetName = presetName; }

    public float getPreampDb() { return preampDb; }
    public void setPreampDb(float preampDb) { this.preampDb = clampGain(preampDb); }

    public float getBandGain(int band) { return bandGains[band]; }
    public void setBandGain(int band, float gain) { bandGains[band] = clampGain(gain); }

    public float getBandQ(int band) { return bandQ[band]; }
    public void setBandQ(int band, float q) { bandQ[band] = Math.max(0.1f, q); }

    public float[] getBandGains() { return bandGains.clone(); }

    public NightMode getNightMode() { return nightMode; }
    public void setNightMode(NightMode nightMode) { this.nightMode = nightMode; }

    public int getSettingsVersion() { return settingsVersion; }
    public void incrementVersion()
    {
        settingsVersion++;
        settingsHash = computeHash();
    }

    public String getSettingsHash() { return settingsHash; }

    // ── Validation ─────────────────────────────────────────────────

    public static float clampGain(float gain)
    {
        if (Float.isNaN(gain)) return 0f;
        return Math.max(GAIN_MIN, Math.min(GAIN_MAX, gain));
    }

    // ── Hash ───────────────────────────────────────────────────────

    public String computeHash()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%.1f", preampDb));
        for (int i = 0; i < BAND_COUNT; i++)
        {
            sb.append('|').append(String.format("%.0f:%.1f:%.1f",
                    FREQUENCIES[i], bandGains[i], bandQ[i]));
        }
        sb.append('|').append(nightMode.isEnabled() ? '1' : '0');
        sb.append('|').append(nightMode.getMode());
        sb.append('|').append(nightMode.getIntensity());
        return djb2(sb.toString());
    }

    private static String djb2(String str)
    {
        int hash = 5381;
        for (int i = 0; i < str.length(); i++)
        {
            hash = ((hash << 5) + hash + str.charAt(i));
        }
        return String.format("%08x", hash & 0xFFFFFFFFL);
    }

    // ── Server Payload ─────────────────────────────────────────────

    /** Build the server-facing JSON payload (matches PWA toServerPayload). */
    public JSONObject toServerPayload() throws JSONException
    {
        JSONObject json = new JSONObject();
        json.put("schemaVersion", SCHEMA_VERSION);
        json.put("enabled", enabled);
        json.put("clientProcessing", clientProcessing);
        json.put("presetName", presetName);
        json.put("preampDb", preampDb);

        JSONArray bands = new JSONArray();
        for (int i = 0; i < BAND_COUNT; i++)
        {
            JSONObject band = new JSONObject();
            band.put("frequency", FREQUENCIES[i]);
            band.put("gain", bandGains[i]);
            band.put("q", bandQ[i]);
            bands.put(band);
        }
        json.put("bands", bands);

        JSONObject nm = new JSONObject();
        nm.put("enabled", nightMode.isEnabled());
        nm.put("effectiveNow", nightMode.isEffectiveNow());
        nm.put("mode", nightMode.getMode());
        nm.put("intensity", nightMode.getIntensity());
        json.put("nightMode", nm);

        json.put("settingsVersion", settingsVersion);
        json.put("settingsHash", settingsHash);
        return json;
    }

    // ── Night Mode Inner Class ─────────────────────────────────────

    public static final class NightMode
    {
        public static final String MODE_DRC = "DYNAMIC_RANGE_COMPRESSION";
        public static final String MODE_LOUDNESS = "LOUDNESS_LEVELING";
        public static final String MODE_PLATFORM = "PLATFORM_NIGHT_MODE";

        public static final String INTENSITY_LOW = "LOW";
        public static final String INTENSITY_MEDIUM = "MEDIUM";
        public static final String INTENSITY_HIGH = "HIGH";

        private boolean enabled;
        private boolean effectiveNow;
        private String mode;
        private String intensity;
        private boolean scheduled;
        private String nightStartTime;
        private String nightEndTime;

        public NightMode()
        {
            this.enabled = false;
            this.effectiveNow = false;
            this.mode = MODE_DRC;
            this.intensity = INTENSITY_MEDIUM;
            this.scheduled = false;
            this.nightStartTime = "22:00";
            this.nightEndTime = "06:00";
        }

        public NightMode(NightMode other)
        {
            this.enabled = other.enabled;
            this.effectiveNow = other.effectiveNow;
            this.mode = other.mode;
            this.intensity = other.intensity;
            this.scheduled = other.scheduled;
            this.nightStartTime = other.nightStartTime;
            this.nightEndTime = other.nightEndTime;
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public boolean isEffectiveNow() { return effectiveNow; }
        public void setEffectiveNow(boolean effectiveNow) { this.effectiveNow = effectiveNow; }

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }

        public String getIntensity() { return intensity; }
        public void setIntensity(String intensity) { this.intensity = intensity; }

        public boolean isScheduled() { return scheduled; }
        public void setScheduled(boolean scheduled) { this.scheduled = scheduled; }

        public String getNightStartTime() { return nightStartTime; }
        public void setNightStartTime(String nightStartTime) { this.nightStartTime = nightStartTime; }

        public String getNightEndTime() { return nightEndTime; }
        public void setNightEndTime(String nightEndTime) { this.nightEndTime = nightEndTime; }

        /** Check if current time falls within the night window. */
        public boolean computeEffectiveNow()
        {
            if (!enabled) return false;
            if (!scheduled) return true;

            java.util.Calendar cal = java.util.Calendar.getInstance();
            int currentMinutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60
                    + cal.get(java.util.Calendar.MINUTE);
            int start = parseTime(nightStartTime);
            int end = parseTime(nightEndTime);

            if (start <= end)
            {
                return currentMinutes >= start && currentMinutes < end;
            }
            // Overnight window
            return currentMinutes >= start || currentMinutes < end;
        }

        private static int parseTime(String time)
        {
            if (time == null || !time.contains(":")) return 0;
            String[] parts = time.split(":");
            try
            {
                return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
            }
            catch (NumberFormatException e) { return 0; }
        }
    }
}
