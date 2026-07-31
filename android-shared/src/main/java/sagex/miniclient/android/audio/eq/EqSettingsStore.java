package sagex.miniclient.android.audio.eq;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Persists EQ settings to SharedPreferences.
 * Key: "sagetvng.audioProcessing.settings.v1"
 */
public final class EqSettingsStore
{
    private static final String PREFS_NAME = "sagetv_eq";
    private static final String KEY_SETTINGS = "sagetvng.audioProcessing.settings.v1";

    private final SharedPreferences prefs;

    public EqSettingsStore(Context ctx)
    {
        this.prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Save settings to disk. Increments version and recomputes hash. */
    public void save(EqSettings settings)
    {
        settings.incrementVersion();
        try
        {
            prefs.edit().putString(KEY_SETTINGS, toJson(settings).toString()).apply();
        }
        catch (JSONException ignored) { }
    }

    /** Load settings from disk. Returns validated defaults if missing/corrupt. */
    public EqSettings load()
    {
        String json = prefs.getString(KEY_SETTINGS, null);
        if (json == null) return new EqSettings();
        try
        {
            return fromJson(new JSONObject(json));
        }
        catch (JSONException e)
        {
            return new EqSettings();
        }
    }

    // ── Serialization ──────────────────────────────────────────────

    private static JSONObject toJson(EqSettings s) throws JSONException
    {
        JSONObject json = new JSONObject();
        json.put("schemaVersion", EqSettings.SCHEMA_VERSION);
        json.put("enabled", s.isEnabled());
        json.put("clientProcessing", s.isClientProcessing());
        json.put("presetName", s.getPresetName());
        json.put("preampDb", s.getPreampDb());

        JSONArray bands = new JSONArray();
        for (int i = 0; i < EqSettings.BAND_COUNT; i++)
        {
            JSONObject band = new JSONObject();
            band.put("gain", s.getBandGain(i));
            band.put("q", s.getBandQ(i));
            bands.put(band);
        }
        json.put("bands", bands);

        EqSettings.NightMode nm = s.getNightMode();
        JSONObject nightJson = new JSONObject();
        nightJson.put("enabled", nm.isEnabled());
        nightJson.put("mode", nm.getMode());
        nightJson.put("intensity", nm.getIntensity());
        nightJson.put("scheduled", nm.isScheduled());
        nightJson.put("nightStartTime", nm.getNightStartTime());
        nightJson.put("nightEndTime", nm.getNightEndTime());
        json.put("nightMode", nightJson);

        json.put("settingsVersion", s.getSettingsVersion());
        json.put("settingsHash", s.getSettingsHash());
        return json;
    }

    private static EqSettings fromJson(JSONObject json) throws JSONException
    {
        EqSettings s = new EqSettings();
        s.setEnabled(json.optBoolean("enabled", false));
        s.setClientProcessing(json.optBoolean("clientProcessing", true));
        s.setPresetName(json.optString("presetName", "Flat"));
        s.setPreampDb((float) json.optDouble("preampDb", 0));

        JSONArray bands = json.optJSONArray("bands");
        if (bands != null && bands.length() == EqSettings.BAND_COUNT)
        {
            for (int i = 0; i < EqSettings.BAND_COUNT; i++)
            {
                JSONObject b = bands.optJSONObject(i);
                if (b != null)
                {
                    s.setBandGain(i, (float) b.optDouble("gain", 0));
                    s.setBandQ(i, (float) b.optDouble("q", 1.0));
                }
            }
        }

        JSONObject nightJson = json.optJSONObject("nightMode");
        if (nightJson != null)
        {
            EqSettings.NightMode nm = s.getNightMode();
            nm.setEnabled(nightJson.optBoolean("enabled", false));
            nm.setMode(nightJson.optString("mode", EqSettings.NightMode.MODE_DRC));
            nm.setIntensity(nightJson.optString("intensity", EqSettings.NightMode.INTENSITY_MEDIUM));
            nm.setScheduled(nightJson.optBoolean("scheduled", false));
            nm.setNightStartTime(nightJson.optString("nightStartTime", "22:00"));
            nm.setNightEndTime(nightJson.optString("nightEndTime", "06:00"));
        }

        return s;
    }
}
