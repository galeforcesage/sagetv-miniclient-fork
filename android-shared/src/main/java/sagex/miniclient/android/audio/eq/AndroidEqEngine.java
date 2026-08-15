package sagex.miniclient.android.audio.eq;

import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;
import android.util.Log;

/**
 * Unified audio equalizer engine for Android.
 *
 * <p>Two backends:
 * <ul>
 *   <li>API 28+: DynamicsProcessing (10 configurable EQ bands + MBC compressor for night mode)</li>
 *   <li>API < 28 or IJK: Legacy Equalizer (hardware-provided bands, typically 5) + LoudnessEnhancer for preamp</li>
 * </ul>
 *
 * <p>Attach to an audio session ID (from ExoPlayer or IJKPlayer).
 * Call {@link #apply(EqSettings)} to update the DSP graph.
 * Call {@link #release()} when the player closes.</p>
 */
public final class AndroidEqEngine
{
    private static final String TAG = "AndroidEqEngine";

    // DynamicsProcessing backend (API 28+)
    private DynamicsProcessing dynamicsProcessing;

    // Legacy Equalizer backend
    private Equalizer legacyEqualizer;
    private LoudnessEnhancer loudnessEnhancer;

    private int boundSessionId = 0;
    private boolean useDynamicsProcessing;
    private int actualBandCount;
    private boolean attached;
    private int configuredChannelCount = 2;

    public AndroidEqEngine()
    {
        this.useDynamicsProcessing = EqCapabilityReporter.supportsDynamicsProcessing();
        this.actualBandCount = EqCapabilityReporter.getEffectiveBandCount();
    }

    /**
     * Attach the EQ engine to an audio session, assuming stereo output.
     * @return true if successfully attached
     */
    public boolean attach(int audioSessionId)
    {
        return attach(audioSessionId, 2);
    }

    /**
     * Attach the EQ engine to an audio session for a specific output channel count.
     * The channel count must match the decoded PCM output; a mismatch (e.g. building
     * a stereo processor for a 5.1 stream) garbles audio into chirps.
     * @return true if successfully attached
     */
    public boolean attach(int audioSessionId, int channelCount)
    {
        if (audioSessionId <= 0) return false;
        int chans = (channelCount >= 1 && channelCount <= 8) ? channelCount : 2;
        if (attached && boundSessionId == audioSessionId && configuredChannelCount == chans) return true;

        release();
        configuredChannelCount = chans;

        try
        {
            if (useDynamicsProcessing && Build.VERSION.SDK_INT >= 28)
            {
                attachDynamicsProcessing(audioSessionId);
            }
            else
            {
                attachLegacyEqualizer(audioSessionId);
            }
            boundSessionId = audioSessionId;
            attached = true;
            Log.i(TAG, "Attached to session " + audioSessionId
                    + " (" + (useDynamicsProcessing ? "DynamicsProcessing" : "LegacyEQ")
                    + ", " + actualBandCount + " bands, " + configuredChannelCount + " ch)");
            return true;
        }
        catch (Throwable t)
        {
            Log.e(TAG, "Failed to attach EQ engine: " + t.getMessage());
            release();
            return false;
        }
    }

    /** Apply EQ settings to the active audio graph. */
    public void apply(EqSettings settings)
    {
        if (!attached) return;

        if (!settings.isEnabled() || !settings.isClientProcessing())
        {
            setFlat();
            return;
        }

        if (useDynamicsProcessing && dynamicsProcessing != null && Build.VERSION.SDK_INT >= 28)
        {
            applyDynamicsProcessing(settings);
        }
        else if (legacyEqualizer != null)
        {
            applyLegacyEqualizer(settings);
        }
    }

    /** Set all gains to zero (transparent pass-through). */
    public void setFlat()
    {
        if (useDynamicsProcessing && dynamicsProcessing != null && Build.VERSION.SDK_INT >= 28)
        {
            for (int i = 0; i < 10; i++)
            {
                DynamicsProcessing.EqBand band = dynamicsProcessing.getPreEqBandByChannelIndex(0, i);
                band.setGain(0f);
                dynamicsProcessing.setPreEqBandAllChannelsTo(i, band);
            }
            dynamicsProcessing.setInputGainAllChannelsTo(0f);
        }
        else if (legacyEqualizer != null)
        {
            short bands = legacyEqualizer.getNumberOfBands();
            for (short i = 0; i < bands; i++)
            {
                legacyEqualizer.setBandLevel(i, (short) 0);
            }
        }
        if (loudnessEnhancer != null)
        {
            loudnessEnhancer.setTargetGain(0);
        }
    }

    /** Release all audio effects. */
    public void release()
    {
        if (dynamicsProcessing != null)
        {
            try { dynamicsProcessing.setEnabled(false); } catch (Throwable ignored) { }
            try { dynamicsProcessing.release(); } catch (Throwable ignored) { }
            dynamicsProcessing = null;
        }
        if (legacyEqualizer != null)
        {
            try { legacyEqualizer.setEnabled(false); } catch (Throwable ignored) { }
            try { legacyEqualizer.release(); } catch (Throwable ignored) { }
            legacyEqualizer = null;
        }
        if (loudnessEnhancer != null)
        {
            try { loudnessEnhancer.setEnabled(false); } catch (Throwable ignored) { }
            try { loudnessEnhancer.release(); } catch (Throwable ignored) { }
            loudnessEnhancer = null;
        }
        boundSessionId = 0;
        attached = false;
    }

    public boolean isAttached() { return attached; }
    public int getBoundSessionId() { return boundSessionId; }
    public int getActualBandCount() { return actualBandCount; }
    public boolean isDynamicsProcessingMode() { return useDynamicsProcessing; }

    // ── DynamicsProcessing (API 28+) ───────────────────────────────

    private void attachDynamicsProcessing(int sessionId)
    {
        if (Build.VERSION.SDK_INT < 28) return;

        // Configure: 10-band PreEQ + 1-band MBC (for DRC) + no PostEQ + Limiter
        DynamicsProcessing.Config.Builder builder = new DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                /* channelCount */ configuredChannelCount,
                /* preEqInUse */ true,
                /* preEqBandCount */ 10,
                /* mbcInUse */ true,
                /* mbcBandCount */ 1,
                /* postEqInUse */ false,
                /* postEqBandCount */ 0,
                /* limiterInUse */ true
        );

        DynamicsProcessing.Config config = builder.build();
        dynamicsProcessing = new DynamicsProcessing(0, sessionId, config);

        // Set canonical frequencies for the 10 PreEQ bands
        for (int i = 0; i < 10; i++)
        {
            DynamicsProcessing.EqBand band = dynamicsProcessing.getPreEqBandByChannelIndex(0, i);
            band.setEnabled(true);
            band.setCutoffFrequency(EqSettings.FREQUENCIES[i]);
            band.setGain(0f);
            dynamicsProcessing.setPreEqBandAllChannelsTo(i, band);
        }

        dynamicsProcessing.setEnabled(true);
        actualBandCount = 10;
    }

    private void applyDynamicsProcessing(EqSettings settings)
    {
        if (Build.VERSION.SDK_INT < 28 || dynamicsProcessing == null) return;

        // PreEQ bands
        for (int i = 0; i < 10; i++)
        {
            DynamicsProcessing.EqBand band = dynamicsProcessing.getPreEqBandByChannelIndex(0, i);
            band.setGain(settings.getBandGain(i));
            dynamicsProcessing.setPreEqBandAllChannelsTo(i, band);
        }

        // Preamp via inputGain
        dynamicsProcessing.setInputGainAllChannelsTo(settings.getPreampDb());

        // Night mode (MBC compressor)
        applyNightModeDynamicsProcessing(settings.getNightMode());
    }

    private void applyNightModeDynamicsProcessing(EqSettings.NightMode nightMode)
    {
        if (Build.VERSION.SDK_INT < 28 || dynamicsProcessing == null) return;

        DynamicsProcessing.MbcBand mbc = dynamicsProcessing.getMbcBandByChannelIndex(0, 0);
        if (nightMode.isEnabled() && nightMode.computeEffectiveNow())
        {
            mbc.setEnabled(true);
            switch (nightMode.getIntensity())
            {
                case EqSettings.NightMode.INTENSITY_LOW:
                    mbc.setThreshold(-20f);
                    mbc.setRatio(2f);
                    mbc.setAttackTime(20f);
                    mbc.setReleaseTime(200f);
                    break;
                case EqSettings.NightMode.INTENSITY_HIGH:
                    mbc.setThreshold(-35f);
                    mbc.setRatio(6f);
                    mbc.setAttackTime(5f);
                    mbc.setReleaseTime(100f);
                    break;
                default: // MEDIUM
                    mbc.setThreshold(-25f);
                    mbc.setRatio(4f);
                    mbc.setAttackTime(10f);
                    mbc.setReleaseTime(150f);
                    break;
            }
        }
        else
        {
            mbc.setEnabled(false);
        }
        dynamicsProcessing.setMbcBandAllChannelsTo(0, mbc);
    }

    // ── Legacy Equalizer ───────────────────────────────────────────

    private void attachLegacyEqualizer(int sessionId)
    {
        legacyEqualizer = new Equalizer(0, sessionId);
        legacyEqualizer.setEnabled(true);
        actualBandCount = legacyEqualizer.getNumberOfBands();

        // LoudnessEnhancer for preamp (API 19+)
        if (Build.VERSION.SDK_INT >= 19)
        {
            try
            {
                loudnessEnhancer = new LoudnessEnhancer(sessionId);
                loudnessEnhancer.setEnabled(true);
            }
            catch (Throwable t)
            {
                Log.w(TAG, "LoudnessEnhancer unavailable: " + t.getMessage());
                loudnessEnhancer = null;
            }
        }
    }

    private void applyLegacyEqualizer(EqSettings settings)
    {
        if (legacyEqualizer == null) return;

        short bands = legacyEqualizer.getNumberOfBands();
        short[] levelRange = legacyEqualizer.getBandLevelRange();
        short minLevel = levelRange[0];
        short maxLevel = levelRange[1];

        // Map 10-band canonical gains to device's actual band count
        float[] mapped;
        if (bands == 5)
        {
            mapped = EqPresets.mapTo5Bands(settings.getBandGains());
        }
        else
        {
            // For other band counts, use first N bands directly
            mapped = new float[bands];
            float[] gains = settings.getBandGains();
            for (int i = 0; i < bands && i < gains.length; i++)
            {
                mapped[i] = gains[i];
            }
        }

        for (short i = 0; i < bands; i++)
        {
            // Convert dB to millibels (1 dB = 100 mB)
            short millibel = (short) (mapped[i] * 100);
            millibel = (short) Math.max(minLevel, Math.min(maxLevel, millibel));
            legacyEqualizer.setBandLevel(i, millibel);
        }

        // Preamp via LoudnessEnhancer
        if (loudnessEnhancer != null && Build.VERSION.SDK_INT >= 19)
        {
            // LoudnessEnhancer takes millibels (positive only = boost)
            int targetGain = (int) (settings.getPreampDb() * 100);
            loudnessEnhancer.setTargetGain(Math.max(0, targetGain));
        }
    }
}
