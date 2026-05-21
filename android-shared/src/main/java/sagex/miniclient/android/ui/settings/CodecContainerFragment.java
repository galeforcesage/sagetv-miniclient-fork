package sagex.miniclient.android.ui.settings;

import android.os.Bundle;

import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;

import sagex.miniclient.android.MiniclientApplication;
import sagex.miniclient.android.R;
import sagex.miniclient.android.media.CodecCapabilityDetector;
import sagex.miniclient.android.prefs.AndroidPrefStore;
import sagex.miniclient.media.AudioCodec;
import sagex.miniclient.media.Container;
import sagex.miniclient.media.VideoCodec;
import sagex.miniclient.prefs.TriState;

/**
 * Phase 2: codec / container support is presented as a flat list of
 * {@link TriStatePreference} rows, one per Container / VideoCodec / AudioCodec.
 * The auto-detected boolean for each row comes from
 * {@link CodecCapabilityDetector} so the user can see what the device actually
 * supports without ever needing to override.
 */
public class CodecContainerFragment extends PreferenceFragmentCompat
{
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey)
    {
        setPreferencesFromResource(R.xml.codec_container_prefs, rootKey);

        AndroidPrefStore prefs = (AndroidPrefStore) MiniclientApplication.get().getClient().properties();

        PreferenceCategory containers = findPreference("containers");
        for (Container c : Container.values())
        {
            TriStatePreference row = new TriStatePreference(getContext());
            row.setKey("container/" + c.getName() + "/support");
            row.setTitle(c.getDescription());
            row.setDefaultValue(TriState.AUTO.toPrefValue());
            row.setAutoValue(CodecCapabilityDetector.isContainerSupported(getContext(), prefs, c));
            row.setSummary(autoLabel(row.getAutoValue()));
            containers.addPreference(row);
        }

        PreferenceCategory video = findPreference("video_codecs");
        for (VideoCodec c : VideoCodec.values())
        {
            TriStatePreference row = new TriStatePreference(getContext());
            row.setKey("codec/video/" + c.getName() + "/support");
            row.setTitle(c.getDescription());
            row.setDefaultValue(TriState.AUTO.toPrefValue());
            row.setAutoValue(CodecCapabilityDetector.isVideoCodecSupported(getContext(), prefs, c));
            row.setSummary(autoLabel(row.getAutoValue()));
            video.addPreference(row);
        }

        PreferenceCategory audio = findPreference("audio_codecs");
        for (AudioCodec c : AudioCodec.values())
        {
            TriStatePreference row = new TriStatePreference(getContext());
            row.setKey("codec/audio/" + c.getName() + "/support");
            row.setTitle(c.getDescription());
            row.setDefaultValue(TriState.AUTO.toPrefValue());
            row.setAutoValue(CodecCapabilityDetector.isAudioCodecSupported(getContext(), prefs, c));
            row.setSummary(autoLabel(row.getAutoValue()));
            audio.addPreference(row);
        }

        // Phase 2: passthrough sub-section. Only codecs with at least one
        // Android audio encoding constant are eligible — others have no
        // way to be passed through and would be permanently "not supported".
        PreferenceCategory passthrough = findPreference("audio_passthrough");
        if (passthrough != null)
        {
            for (AudioCodec c : AudioCodec.values())
            {
                if (c.getAndroidAudioEncodings() == null || c.getAndroidAudioEncodings().length == 0)
                    continue;
                TriStatePreference row = new TriStatePreference(getContext());
                row.setKey("codec/audio_passthrough/" + c.getName() + "/support");
                row.setTitle(c.getDescription());
                row.setDefaultValue(TriState.AUTO.toPrefValue());
                row.setAutoValue(CodecCapabilityDetector.isAudioPassthroughSupported(getContext(), c));
                row.setSummary(autoLabel(row.getAutoValue()));
                passthrough.addPreference(row);
            }
        }
    }

    private static String autoLabel(boolean detected)
    {
        return detected
                ? "Auto: supported on this device"
                : "Auto: not supported on this device";
    }
}
