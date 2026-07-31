package sagex.miniclient.android.audio.eq;

import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Builds the canonical AUDIO_PROCESSING_CAPABILITIES JSON
 * to advertise to the server at connection time.
 */
public final class EqCapabilityPayload
{
    private EqCapabilityPayload() { }

    /** Cached JSON string, computed once at app init. */
    private static volatile String cachedPayload;

    /** Compute and cache the capability payload. Call from Application.onCreate(). */
    public static void init()
    {
        int bands = EqCapabilityReporter.getEffectiveBandCount();
        boolean drc = EqCapabilityReporter.supportsDrc();
        boolean preamp = EqCapabilityReporter.supportsPreamp();

        try
        {
            JSONObject json = new JSONObject();
            json.put("schemaVersion", 1);
            json.put("clientEqAvailable", bands > 0);
            json.put("bandCount", bands);
            json.put("preampAvailable", preamp);
            json.put("drcAvailable", drc);
            json.put("nightModeAvailable", drc);
            json.put("engine", EqCapabilityReporter.supportsDynamicsProcessing()
                    ? "DynamicsProcessing" : "LegacyEqualizer");
            json.put("apiLevel", Build.VERSION.SDK_INT);
            cachedPayload = json.toString();
        }
        catch (JSONException e)
        {
            cachedPayload = "";
        }

        // Set the static field in MiniClientConnection for server property response
        sagex.miniclient.MiniClientConnection.audioProcessingCapabilities = cachedPayload;
    }

    /** Get the cached capability JSON for server property response. */
    public static String getPayload()
    {
        return cachedPayload != null ? cachedPayload : "";
    }
}
