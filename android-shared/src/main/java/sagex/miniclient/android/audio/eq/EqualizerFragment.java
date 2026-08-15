package sagex.miniclient.android.audio.eq;

import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import sagex.miniclient.android.R;

/**
 * Fullscreen-overlay EQ panel. Shows 10 or 5 band sliders dynamically,
 * preamp, presets, night mode (when DRC available), enable/disable toggles.
 * D-pad navigable for TV + touch for phone.
 */
public class EqualizerFragment extends DialogFragment
{
    private static final String TAG = "EqualizerFragment";

    private EqSettings settings;
    private EqSettingsStore store;
    private AndroidEqEngine engine;
    private int bandCount;

    // UI references
    private CheckBox enabledCheckbox;
    private CheckBox clientProcessingCheckbox;
    private Spinner presetSpinner;
    private SeekBar preampSeekBar;
    private TextView preampValue;
    private LinearLayout bandsContainer;
    private TextView offIndicator;
    private TextView statusText;
    private TextView recommendText;
    private View nightModeSection;
    private CheckBox nightModeCheckbox;
    private Spinner nightIntensitySpinner;

    private SeekBar[] bandSeekBars;
    private TextView[] bandValueLabels;
    private boolean suppressListeners;

    private final android.os.Handler statusHandler = new android.os.Handler();
    private final Runnable statusTick = new Runnable()
    {
        @Override
        public void run()
        {
            updateStatus();
            statusHandler.postDelayed(this, 1000);
        }
    };

    /** Listener interface for settings changes (server notification). */
    public interface OnEqSettingsChangedListener
    {
        void onEqSettingsChanged(EqSettings settings);
    }

    private OnEqSettingsChangedListener listener;

    public void setOnEqSettingsChangedListener(OnEqSettingsChangedListener l)
    {
        this.listener = l;
    }

    public void setEngine(AndroidEqEngine engine) { this.engine = engine; }

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setStyle(DialogFragment.STYLE_NO_FRAME, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState)
    {
        // STYLE_NO_FRAME (set in onCreate) already removes the title bar.
        // Avoid requestFeature() here, which throws if content is set first.
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        if (dialog.getWindow() != null)
        {
            dialog.getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        return dialog;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState)
    {
        return inflater.inflate(R.layout.fragment_equalizer, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState)
    {
        super.onViewCreated(view, savedInstanceState);

        Context ctx = getActivity();
        if (ctx == null) { dismiss(); return; }

        EqManager mgr = EqManager.get();
        mgr.init(ctx);
        store = new EqSettingsStore(ctx);
        settings = mgr.getSettings();
        engine = mgr.getEngine();
        bandCount = EqCapabilityReporter.getEffectiveBandCount();

        bindViews(view);
        buildBandSliders(view);
        setupPresetSpinner();
        setupNightMode();
        syncUiFromSettings();
        setupListeners();
    }

    private void bindViews(View view)
    {
        enabledCheckbox = view.findViewById(R.id.eq_enabled_checkbox);
        clientProcessingCheckbox = view.findViewById(R.id.eq_client_processing_checkbox);
        presetSpinner = view.findViewById(R.id.eq_preset_spinner);
        preampSeekBar = view.findViewById(R.id.eq_preamp_seekbar);
        preampValue = view.findViewById(R.id.eq_preamp_value);
        bandsContainer = view.findViewById(R.id.eq_bands_container);
        offIndicator = view.findViewById(R.id.eq_off_indicator);
        statusText = view.findViewById(R.id.eq_status_text);
        recommendText = view.findViewById(R.id.eq_recommend_text);
        nightModeSection = view.findViewById(R.id.eq_night_mode_section);
        nightModeCheckbox = view.findViewById(R.id.eq_night_mode_checkbox);
        nightIntensitySpinner = view.findViewById(R.id.eq_night_intensity_spinner);

        Button closeBtn = view.findViewById(R.id.eq_close_button);
        closeBtn.setOnClickListener(v -> dismiss());

        Button resetBtn = view.findViewById(R.id.eq_reset_button);
        resetBtn.setOnClickListener(v -> resetToFlat());
    }

    private void buildBandSliders(View view)
    {
        LayoutInflater inflater = LayoutInflater.from(getActivity());
        int displayBands = Math.min(bandCount, EqSettings.BAND_COUNT);
        if (displayBands <= 0) displayBands = 5; // fallback

        bandSeekBars = new SeekBar[displayBands];
        bandValueLabels = new TextView[displayBands];

        String[] labels;
        if (displayBands == 10)
        {
            labels = EqSettings.FREQUENCY_LABELS;
        }
        else
        {
            labels = new String[]{"60", "230", "910", "3.6K", "14K"};
        }

        for (int i = 0; i < displayBands; i++)
        {
            View item = inflater.inflate(R.layout.eq_band_item, bandsContainer, false);
            SeekBar sb = item.findViewById(R.id.eq_band_seekbar);
            TextView valueLabel = item.findViewById(R.id.eq_band_value);
            TextView freqLabel = item.findViewById(R.id.eq_band_freq);

            freqLabel.setText(labels[i]);
            bandSeekBars[i] = sb;
            bandValueLabels[i] = valueLabel;

            final int bandIdx = i;
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
            {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
                {
                    if (suppressListeners) return;
                    float gain = progressToDb(progress);
                    if (bandCount == 5)
                    {
                        // Map 5-band slider to canonical 10-band model
                        applyGainToCanonical5Band(bandIdx, gain);
                    }
                    else
                    {
                        settings.setBandGain(bandIdx, gain);
                    }
                    valueLabel.setText(formatDb(gain));
                    detectPreset();
                    applyAndNotify();
                }

                @Override public void onStartTrackingTouch(SeekBar seekBar) { }
                @Override public void onStopTrackingTouch(SeekBar seekBar) { }
            });

            bandsContainer.addView(item);
        }
    }

    private void setupPresetSpinner()
    {
        String[] presetNames = EqPresets.PRESET_NAMES;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getActivity(), android.R.layout.simple_spinner_item, presetNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        presetSpinner.setAdapter(adapter);

        presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener()
        {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id)
            {
                if (suppressListeners) return;
                String name = presetNames[position];
                float[] gains = EqPresets.getPresetGains(name);
                if (gains != null)
                {
                    for (int i = 0; i < EqSettings.BAND_COUNT; i++)
                    {
                        settings.setBandGain(i, gains[i]);
                    }
                    settings.setPresetName(name);
                    syncBandSlidersFromSettings();
                    applyAndNotify();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void setupNightMode()
    {
        if (EqCapabilityReporter.supportsDrc())
        {
            nightModeSection.setVisibility(View.VISIBLE);
            String[] intensities = {"Low", "Medium", "High"};
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    getActivity(), android.R.layout.simple_spinner_item, intensities);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            nightIntensitySpinner.setAdapter(adapter);
        }
        else
        {
            nightModeSection.setVisibility(View.GONE);
        }
    }

    private void setupListeners()
    {
        enabledCheckbox.setOnCheckedChangeListener((btn, checked) -> {
            if (suppressListeners) return;
            settings.setEnabled(checked);
            updateOffIndicator();
            applyAndNotify();
        });

        clientProcessingCheckbox.setOnCheckedChangeListener((btn, checked) -> {
            if (suppressListeners) return;
            settings.setClientProcessing(checked);
            applyAndNotify();
        });

        preampSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                if (suppressListeners) return;
                float db = progressToDb(progress);
                settings.setPreampDb(db);
                preampValue.setText(formatDb(db));
                applyAndNotify();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        nightModeCheckbox.setOnCheckedChangeListener((btn, checked) -> {
            if (suppressListeners) return;
            settings.getNightMode().setEnabled(checked);
            applyAndNotify();
        });

        nightIntensitySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener()
        {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id)
            {
                if (suppressListeners) return;
                String[] vals = {EqSettings.NightMode.INTENSITY_LOW,
                        EqSettings.NightMode.INTENSITY_MEDIUM,
                        EqSettings.NightMode.INTENSITY_HIGH};
                settings.getNightMode().setIntensity(vals[position]);
                applyAndNotify();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    // ── Sync UI ↔ Model ────────────────────────────────────────────

    private void syncUiFromSettings()
    {
        suppressListeners = true;
        try
        {
            enabledCheckbox.setChecked(settings.isEnabled());
            clientProcessingCheckbox.setChecked(settings.isClientProcessing());
            preampSeekBar.setProgress(dbToProgress(settings.getPreampDb()));
            preampValue.setText(formatDb(settings.getPreampDb()));
            syncBandSlidersFromSettings();
            updateOffIndicator();

            // Preset spinner
            String[] names = EqPresets.PRESET_NAMES;
            for (int i = 0; i < names.length; i++)
            {
                if (names[i].equals(settings.getPresetName()))
                {
                    presetSpinner.setSelection(i);
                    break;
                }
            }

            // Night mode
            nightModeCheckbox.setChecked(settings.getNightMode().isEnabled());
            String intensity = settings.getNightMode().getIntensity();
            int idx = intensity.equals(EqSettings.NightMode.INTENSITY_LOW) ? 0
                    : intensity.equals(EqSettings.NightMode.INTENSITY_HIGH) ? 2 : 1;
            nightIntensitySpinner.setSelection(idx);
        }
        finally
        {
            suppressListeners = false;
        }
    }

    private void syncBandSlidersFromSettings()
    {
        if (bandSeekBars == null) return;

        float[] gains;
        if (bandSeekBars.length == 5)
        {
            gains = EqPresets.mapTo5Bands(settings.getBandGains());
        }
        else
        {
            gains = settings.getBandGains();
        }

        for (int i = 0; i < bandSeekBars.length && i < gains.length; i++)
        {
            bandSeekBars[i].setProgress(dbToProgress(gains[i]));
            bandValueLabels[i].setText(formatDb(gains[i]));
        }
    }

    private void updateOffIndicator()
    {
        offIndicator.setVisibility(settings.isEnabled() ? View.GONE : View.VISIBLE);
    }

    /**
     * Reflects whether the EQ engine is actually attached to a live audio session.
     * Mirrors the PWA client's indicator: connection state is only meaningful
     * while something is playing.
     */
    private void updateStatus()
    {
        if (statusText == null) return;

        EqManager mgr = EqManager.get();
        boolean attached = mgr.isAttached();
        boolean streamActive = mgr.isStreamActive();
        boolean passthrough = streamActive && !mgr.isCurrentStreamPcm();

        // Passthrough audio (e.g. Dolby/DTS to a receiver) can't be EQ'd on-device;
        // the server must do it, so reflect that the on-device toggle is overridden.
        if (clientProcessingCheckbox != null)
        {
            clientProcessingCheckbox.setEnabled(!passthrough);
        }

        if (passthrough)
        {
            statusText.setText("\u25CF Passthrough audio ("
                    + mgr.getCurrentChannelCount() + "ch) \u2014 EQ handled by server");
            statusText.setTextColor(0xFF4FC3F7); // blue
        }
        else if (attached)
        {
            statusText.setText("\u25CF Connected \u2014 EQ applied on this device ("
                    + mgr.getCurrentChannelCount() + "ch)");
            statusText.setTextColor(0xFF66BB6A); // green
        }
        else if (streamActive && settings.isEnabled() && !settings.isClientProcessing())
        {
            statusText.setText("\u25CF Connected \u2014 EQ handled by server");
            statusText.setTextColor(0xFF4FC3F7); // blue
        }
        else if (streamActive)
        {
            statusText.setText("\u25CF Connected \u2014 EQ off");
            statusText.setTextColor(0xFF888888); // gray
        }
        else
        {
            statusText.setText("Not connected \u2014 status shows while media is playing");
            statusText.setTextColor(0xFF888888); // gray
        }

        updateRecommendation(passthrough);
    }

    /**
     * When the device can fully reproduce the current EQ settings but the server
     * is currently doing the processing, recommend switching to on-device EQ so
     * the server doesn't need to transcode. Suppressed for passthrough audio,
     * where the server MUST do the EQ.
     */
    private void updateRecommendation(boolean passthrough)
    {
        if (recommendText == null) return;

        if (passthrough)
        {
            recommendText.setText("Surround passthrough \u2014 on-device EQ isn't possible; "
                    + "server EQ is used automatically.");
            recommendText.setVisibility(View.VISIBLE);
            return;
        }

        boolean serverDoingEq = settings.isEnabled() && !settings.isClientProcessing();
        if (EqManager.get().isStreamActive() && serverDoingEq && clientCanFullyHandle())
        {
            recommendText.setText("Tip: this device can handle these settings \u2014 "
                    + "check \u201CProcess on this device\u201D to turn off server EQ.");
            recommendText.setVisibility(View.VISIBLE);
        }
        else
        {
            recommendText.setVisibility(View.GONE);
        }
    }

    /** True when the device engine can fully reproduce the current settings. */
    private boolean clientCanFullyHandle()
    {
        // Night mode needs DRC (DynamicsProcessing); legacy devices can't do it.
        if (settings.getNightMode().isEnabled() && !EqCapabilityReporter.supportsDrc())
        {
            return false;
        }
        // Full canonical EQ needs the device to expose all bands; 5-band hardware
        // only approximates the 10-band model, so leave that to the server.
        return EqCapabilityReporter.getEffectiveBandCount() >= EqSettings.BAND_COUNT;
    }

    @Override
    public void onResume()
    {
        super.onResume();
        statusHandler.post(statusTick);
    }

    @Override
    public void onPause()
    {
        statusHandler.removeCallbacks(statusTick);
        super.onPause();
    }

    // ── Apply + Notify ─────────────────────────────────────────────

    private void applyAndNotify()
    {
        // Apply to the live audio session + persist + publish the server payload
        // (EqManager owns the payload so it can force server EQ on passthrough).
        EqManager.get().updateSettings(settings);

        // Notify server wiring
        if (listener != null)
        {
            listener.onEqSettingsChanged(settings);
        }
    }

    private void resetToFlat()
    {
        for (int i = 0; i < EqSettings.BAND_COUNT; i++)
        {
            settings.setBandGain(i, 0f);
        }
        settings.setPreampDb(0f);
        settings.setPresetName("Flat");
        syncUiFromSettings();
        applyAndNotify();
    }

    private void detectPreset()
    {
        String detected = EqPresets.detectPreset(settings);
        if (detected != null && !detected.equals(settings.getPresetName()))
        {
            settings.setPresetName(detected);
            suppressListeners = true;
            String[] names = EqPresets.PRESET_NAMES;
            for (int i = 0; i < names.length; i++)
            {
                if (names[i].equals(detected))
                {
                    presetSpinner.setSelection(i);
                    break;
                }
            }
            suppressListeners = false;
        }
    }

    /** Map 5-band slider index back to canonical 10-band gains. */
    private void applyGainToCanonical5Band(int band5Idx, float gain)
    {
        // 5-band mapping: 0→{0}, 1→{1,2}, 2→{3,4}, 3→{5,6}, 4→{7,8,9}
        switch (band5Idx)
        {
            case 0:
                settings.setBandGain(0, gain);
                break;
            case 1:
                settings.setBandGain(1, gain);
                settings.setBandGain(2, gain);
                break;
            case 2:
                settings.setBandGain(3, gain);
                settings.setBandGain(4, gain);
                break;
            case 3:
                settings.setBandGain(5, gain);
                settings.setBandGain(6, gain);
                break;
            case 4:
                settings.setBandGain(7, gain);
                settings.setBandGain(8, gain);
                settings.setBandGain(9, gain);
                break;
        }
    }

    // ── Conversion helpers ─────────────────────────────────────────

    /** SeekBar progress (0..48) → dB (-12..+12) */
    private static float progressToDb(int progress)
    {
        return (progress - 24) * 0.5f;
    }

    /** dB (-12..+12) → SeekBar progress (0..48) */
    private static int dbToProgress(float db)
    {
        return Math.round((db + 12f) / 0.5f);
    }

    private static String formatDb(float db)
    {
        if (db == 0f) return "0";
        return String.format("%+.1f", db);
    }
}
