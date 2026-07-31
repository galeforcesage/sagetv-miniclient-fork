package sagex.miniclient.android.audio.eq;

import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.os.Build;
import android.util.Log;

/**
 * Reports what EQ capabilities the current device/player combination supports.
 * Used to decide what the client handles vs. what goes to server transcode.
 */
public final class EqCapabilityReporter
{
    private static final String TAG = "EqCapability";

    private EqCapabilityReporter() { }

    /**
     * Check if DynamicsProcessing is available (API 28+).
     * This gives us 10-band EQ + MBC compressor (night mode).
     */
    public static boolean supportsDynamicsProcessing()
    {
        return Build.VERSION.SDK_INT >= 28;
    }

    /**
     * Probe the legacy Equalizer to determine how many bands the device offers.
     * Returns 0 if Equalizer is unavailable.
     */
    public static int probeLegacyBandCount()
    {
        try
        {
            // Session 0 = output mix (probe only, released immediately)
            Equalizer eq = new Equalizer(0, 0);
            int bands = eq.getNumberOfBands();
            eq.release();
            return bands;
        }
        catch (Throwable t)
        {
            Log.w(TAG, "Legacy Equalizer probe failed: " + t.getMessage());
            return 0;
        }
    }

    /**
     * Get the effective band count for the current device.
     * DynamicsProcessing: always 10 (we configure it with 10 bands).
     * Legacy Equalizer: whatever the hardware provides (typically 5).
     */
    public static int getEffectiveBandCount()
    {
        if (supportsDynamicsProcessing()) return 10;
        int legacy = probeLegacyBandCount();
        return legacy > 0 ? legacy : 0;
    }

    /**
     * Whether DRC / Night Mode is available on the client.
     * Only DynamicsProcessing (API 28+) supports the MBC compressor.
     */
    public static boolean supportsDrc()
    {
        return supportsDynamicsProcessing();
    }

    /**
     * Whether preamp (volume boost beyond 1.0) is available.
     * DynamicsProcessing has inputGain; legacy can use LoudnessEnhancer.
     */
    public static boolean supportsPreamp()
    {
        // Both paths support some form of gain boost
        return getEffectiveBandCount() > 0;
    }

    /**
     * Whether the client can handle EQ at all (any player, any API level).
     */
    public static boolean supportsClientEq()
    {
        return getEffectiveBandCount() > 0;
    }

    /**
     * Build a capability summary suitable for logging or server advertisement.
     */
    public static String getSummary()
    {
        return "bands=" + getEffectiveBandCount()
                + " drc=" + supportsDrc()
                + " preamp=" + supportsPreamp()
                + " engine=" + (supportsDynamicsProcessing() ? "DynamicsProcessing" : "LegacyEqualizer");
    }
}
