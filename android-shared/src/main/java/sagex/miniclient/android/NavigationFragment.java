package sagex.miniclient.android;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.squareup.otto.Subscribe;

import sagex.miniclient.MiniClient;
import sagex.miniclient.MiniPlayerPlugin;
import sagex.miniclient.SageCommand;
import sagex.miniclient.android.events.BackPressedEvent;
import sagex.miniclient.android.events.ChangePlayerOneTime;
import sagex.miniclient.android.events.CloseAppEvent;
import sagex.miniclient.android.events.HideNavigationEvent;
import sagex.miniclient.android.events.HideSystemUIEvent;
import sagex.miniclient.android.events.ToggleAspectRatioEvent;
import sagex.miniclient.android.preferences.MediaMappingPreferences;
import sagex.miniclient.android.ui.DownloadMenuLauncher;
import sagex.miniclient.android.video.NonLeanbackAspect;
import sagex.miniclient.android.video.OrientationController;
import sagex.miniclient.events.ShowKeyboardEvent;
import sagex.miniclient.events.PartyStateChanged;
import sagex.miniclient.events.VideoInfoShow;
import sagex.miniclient.media.SubtitleTrack;
import sagex.miniclient.prefs.PrefStore;
import sagex.miniclient.party.PartyClient;
import sagex.miniclient.party.PartyProtocolException;
import sagex.miniclient.uibridge.EventRouter;

/**
 * Created by seans on 05/12/15.
 */
public class NavigationFragment extends DialogFragment
{
    static final Logger log = LoggerFactory.getLogger(NavigationFragment.class);
    private final MiniClient client;

    private View navView;

    View navOptions = null;

    View navPause = null;

    ImageView navSmartRemote;
    Button navParty;
    String lastPartyError;
    MediaMappingPreferences prefs;

    public NavigationFragment()
    {
        this.client = MiniclientApplication.get().getClient();
    }

    @Override
    public void onResume()
    {
        super.onResume();
        AppUtil.hideSystemUI(getActivity());
    }


    @Override
    public void onAttach(Context context)
    {
        super.onAttach(context);
        AppUtil.hideSystemUI(this.getActivity());
    }

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setStyle(DialogFragment.STYLE_NO_FRAME, R.style.Theme_Dialog_DoNotDim);
        setCancelable(false);
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
    {

        //getActivity().getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        navView = inflater.inflate(R.layout.navigation, container, false);

        prefs = new MediaMappingPreferences(this.client.properties());

        navOptions = navView.findViewById(R.id.nav_options);
        navPause = navView.findViewById(R.id.nav_media_pause);
        navSmartRemote = (ImageView) navView.findViewById(R.id.nav_remote_mode);
        navParty = (Button) navView.findViewById(R.id.nav_party);

        View.OnClickListener buttonClickListener = new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                buttonClick(v);
            }
        };

        for (int id : new int[]{R.id.nav_up, R.id.nav_down, R.id.nav_left, R.id.nav_right, R.id.nav_select, R.id.nav_pgdn, R.id.nav_pgup,
                R.id.nav_options, R.id.nav_home, R.id.nav_media_pause, R.id.nav_media_play, R.id.nav_media_skip_back, R.id.nav_media_skip_back_2,
                R.id.nav_media_skip_forward, R.id.nav_media_skip_forward_2,
                R.id.nav_media_stop, R.id.nav_back, R.id.nav_info, R.id.nav_video_info})
        {
            try
            {
                navView.findViewById(id).setOnClickListener(buttonClickListener);
            }
            catch (Throwable t)
            {
                t.printStackTrace();
            }
        }

        navView.findViewById(R.id.nav_switch_player).setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                onSwitchPlayer();
            }
        });

        navView.findViewById(R.id.nav_toggle_ar).setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                onToggleAspectRatio();
            }
        });

        // Orientation toggle: only present on phone/tablet navigation layouts; hidden on Leanback.
        View orientationBtn = navView.findViewById(R.id.nav_toggle_orientation);
        if (orientationBtn != null)
        {
            Context octx = getActivity();
            if (octx != null && OrientationController.isLeanback(octx))
            {
                orientationBtn.setVisibility(View.GONE);
            }
            else
            {
                orientationBtn.setOnClickListener(new View.OnClickListener()
                {
                    @Override
                    public void onClick(View v)
                    {
                        onToggleOrientation();
                    }
                });
            }
        }

        navView.findViewById(R.id.nav_video_info).setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                onVideoInfo();
            }

        });


        navView.findViewById(R.id.nav_closed_captions).setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                if(client.getPlayer() == null)
                {
                    dismiss();
                }
                else if(client.getPlayer().getSubtitleTrackCount() == 0)
                {
                    Toast.makeText(NavigationFragment.this.getActivity(), "No subtitle or closed caption tracks", Toast.LENGTH_LONG).show();
                    dismiss();
                }
                else if ((client.isVideoPlaying() || client.isVideoPaused()))
                {
                    SubtitleTrack[] tracks = client.getPlayer().getSubtitleTracks();
                    String[] items = new String[tracks.length + 1];
                    int selectedIndex;

                    items[0] = "Off";

                    for (int i = 0; i < tracks.length; i++)
                    {
                        items[i + 1] = tracks[i].toString();
                    }

                    if (client.getPlayer().getSelectedSubtitleTrack() == MiniPlayerPlugin.DISABLE_TRACK)
                    {
                        selectedIndex = 0;
                    }
                    else
                    {
                        selectedIndex = client.getPlayer().getSelectedSubtitleTrack() + 1;
                    }

                    AlertDialog.Builder builder = new AlertDialog.Builder(NavigationFragment.this.getActivity());
                    builder.setTitle("Select Subtitle/Closed Caption");
                    builder.setSingleChoiceItems(items, selectedIndex, new DialogInterface.OnClickListener()
                    {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i)
                        {
                            if (i > 0)
                            {
                                if (tracks[i - 1].isSupported())
                                {
                                    client.getPlayer().setSubtitleTrack(tracks[i - 1].getIndex());
                                }
                            }
                            else if (i == 0)
                            {
                                client.getPlayer().setSubtitleTrack(MiniPlayerPlugin.DISABLE_TRACK);
                            }
                            dialogInterface.cancel();
                        }

                    });
                    dismiss();
                    builder.show();
                }


            }
        });

        navView.findViewById(R.id.nav_equalizer).setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                onOpenEqualizer();
            }
        });

        navView.findViewById(R.id.nav_remote_mode).setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                onToggleSmartRemote();
            }
        });

        navView.findViewById(R.id.nav_help).setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                onHelp();
            }
        });

        if (navParty != null)
        {
            navParty.setOnClickListener(new View.OnClickListener()
            {
                @Override
                public void onClick(View v)
                {
                    showPartyMenu();
                }
            });
        }

        for (int id : new int[]{R.id.nav_keyboard, R.id.nav_close, R.id.nav_hide})
        {
            navView.findViewById(id).setOnClickListener(new OnClickListener()
            {
                @Override
                public void onClick(View v)
                {
                    buttonClickInternal(v);
                }
            });
        }

        // Optional downloads icon (present only in builds that ship the
        // android-offline module). Look up by tag so this fragment in
        // android-shared does not depend on a resource id that may not exist
        // in every flavor's R class. If the activity is not part of the build
        // (online-only flavor), the launcher is a silent no-op so the button
        // is harmless if the layout is updated independently.
        View navDownloads = navView.findViewWithTag("_downloads");
        if (navDownloads != null)
        {
            navDownloads.setOnClickListener(new OnClickListener()
            {
                @Override
                public void onClick(View v)
                {
                    buttonClickInternal(v);
                }
            });
        }

        if (client == null || client.getCurrentConnection() == null) return navView;

        if (client.getCurrentConnection().getMenuHint().isOSDMenuNoPopup() && (client.isVideoPlaying() || client.isVideoPaused()))
        {
            navPause.requestFocus();
        }
        else
        {
            navOptions.requestFocus();
        }

        updateSmartRemoteToggle();
        updatePartyButton();

        AppUtil.hideSystemUI(getActivity());

        return navView;
    }

    @Override
    public void onStop()
    {
        try
        {
            client.eventbus().unregister(this);
        }
        catch (Throwable ignored)
        {
        }
        super.onStop();
    }

    @Subscribe
    public void onPartyStateChanged(PartyStateChanged event)
    {
        if (navView != null)
        {
            final PartyClient.State state = event.state;
            navView.post(new Runnable()
            {
                @Override public void run()
                {
                    updatePartyButton();
                    updatePartyMediaControls(state);
                    if (state != null && "NO_CURRENT_MEDIA".equals(state.errorCode)
                            && !state.errorCode.equals(lastPartyError))
                    {
                        lastPartyError = state.errorCode;
                        Toast.makeText(getActivity(), R.string.party_no_media,
                                Toast.LENGTH_LONG).show();
                    }
                    else if (state != null
                            && ("ERROR".equals(state.status)
                                    || "PREPARATION_FAILED".equals(state.status))
                            && state.errorCode != null)
                    {
                        String detail = state.preparationWatchCode >= 0
                                ? state.errorCode + " (watchCode="
                                        + state.preparationWatchCode + ")"
                                : state.errorCode;
                        if (!detail.equals(lastPartyError))
                        {
                            lastPartyError = detail;
                            Toast.makeText(getActivity(),
                                    getString(R.string.party_error, detail),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                    else if (state == null || state.errorCode == null)
                    {
                        lastPartyError = null;
                    }
                }
            });
        }
    }

    private PartyClient partyClient()
    {
        return client == null || client.getCurrentConnection() == null
                ? null : client.getCurrentConnection().getPartyClient();
    }

    private void updatePartyButton()
    {
        if (navParty == null) return;
        PartyClient party = partyClient();
        PartyClient.State state = party == null ? null : party.getState();
        if (state == null || (state.availability != PartyClient.Availability.AVAILABLE
                && !state.joined))
        {
            navParty.setVisibility(View.GONE);
            return;
        }

        navParty.setVisibility(View.VISIBLE);
        if (state != null && state.joined)
        {
            navParty.setText(state.locallyStopped
                    ? "Party: Prepare again" : "Party: " + state.status);
        }
        else if (state != null && "INVITED".equals(state.status))
        {
            navParty.setText("Party invite");
        }
        else
        {
            navParty.setText(R.string.party_mode);
        }
    }

    private void updatePartyMediaControls(PartyClient.State state)
    {
        if (navView == null) return;
        boolean enabled = state == null || !state.joined || state.controlsEnabled;
        for (int id : new int[]{R.id.nav_media_pause, R.id.nav_media_play,
                R.id.nav_media_skip_back, R.id.nav_media_skip_back_2,
                R.id.nav_media_skip_forward, R.id.nav_media_skip_forward_2})
        {
            View control = navView.findViewById(id);
            if (control != null)
            {
                control.setEnabled(enabled);
                control.setAlpha(enabled ? 1.0f : 0.4f);
            }
        }
    }

    private void showPartyMenu()
    {
        final PartyClient party = partyClient();
        if (party == null) return;
        final PartyClient.State state = party.getState();
        if (state.availability != PartyClient.Availability.AVAILABLE)
        {
            Toast.makeText(getActivity(), R.string.party_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        if (!state.joined)
        {
            showPartyEntryMenu(party, state);
        }
        else
        {
            showJoinedPartyMenu(party, state);
        }
    }

    private void showPartyEntryMenu(final PartyClient party, final PartyClient.State state)
    {
        final java.util.ArrayList<String> actions = new java.util.ArrayList<String>();
        actions.add("Start Party at Beginning");
        if (state.currentPositionSupported) actions.add("Start Party at Current Position");
        actions.add("Join with code");
        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.party_mode)
                .setItems(actions.toArray(new String[actions.size()]),
                        new DialogInterface.OnClickListener()
                        {
                            @Override public void onClick(DialogInterface dialog, int which)
                            {
                                String action = actions.get(which);
                                if (action.startsWith("Start Party"))
                                {
                                    if (!party.createCurrent(
                                            action.endsWith("Current Position")
                                                    ? "CURRENT_POSITION"
                                                    : "BEGINNING"))
                                    {
                                        Toast.makeText(getActivity(), R.string.party_no_media,
                                                Toast.LENGTH_LONG).show();
                                    }
                                    else
                                    {
                                        showPartyCodeWhenAvailable(
                                                getActivity(), party,
                                                "Party created", state.joinCode, 0);
                                    }
                                }
                                else
                                {
                                    showJoinCodeDialog(party,
                                            state.invitedPartyId,
                                            state.invitedMediaFileId);
                                }
                            }
                        }).show();
    }

    private void showJoinCodeDialog(final PartyClient party,
                                    final String invitedPartyId,
                                    final Integer invitedMediaFileId)
    {
        final EditText input = new EditText(getActivity());
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setHint("000");
        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.party_join_code)
                .setView(input)
                .setPositiveButton("Join", new DialogInterface.OnClickListener()
                {
                    @Override public void onClick(DialogInterface dialog, int which)
                    {
                        try
                        {
                            party.join(input.getText().toString(),
                                    invitedPartyId, invitedMediaFileId);
                            dismiss();
                        }
                        catch (PartyProtocolException e)
                        {
                            Toast.makeText(getActivity(), e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showJoinedPartyMenu(final PartyClient party, final PartyClient.State state)
    {
        final java.util.ArrayList<String> actions = new java.util.ArrayList<String>();
        if (!state.host && !state.ready && !state.locallyStopped) actions.add("Join & Prepare");
        if (state.locallyStopped) actions.add("Prepare again");
        if (state.host)
        {
            actions.add("Show join code");
            actions.add("Invite connected client");
            actions.add("Play Together");
            actions.add("Commercial Skip");
            actions.add("End Party");
        }
        actions.add("Leave Party");
        new AlertDialog.Builder(getActivity())
                .setTitle("Party: " + state.status)
                .setItems(actions.toArray(new String[actions.size()]),
                        new DialogInterface.OnClickListener()
                        {
                            @Override public void onClick(DialogInterface dialog, int which)
                            {
                                String action = actions.get(which);
                                if ("Join & Prepare".equals(action)) confirmPrepare(party);
                                else if ("Prepare again".equals(action))
                                {
                                    party.prepare();
                                    dismiss();
                                }
                                else if ("Show join code".equals(action)) showJoinCode(party);
                                else if ("Invite connected client".equals(action)) showInviteMenu(party);
                                else if ("Play Together".equals(action))
                                {
                                    EventRouter.postCommand(client, SageCommand.PLAY);
                                    dismiss();
                                }
                                else if ("Commercial Skip".equals(action))
                                {
                                    party.comskip();
                                    dismiss();
                                }
                                else if ("End Party".equals(action)) confirmEnd(party);
                                else if ("Leave Party".equals(action)) party.leave();
                            }
                        }).show();
    }

    private void confirmPrepare(final PartyClient party)
    {
        new AlertDialog.Builder(getActivity())
                .setTitle("Join & Prepare")
                .setMessage("Replace current playback with this Party media and keep it paused?")
                .setPositiveButton("Prepare", new DialogInterface.OnClickListener()
                {
                    @Override public void onClick(DialogInterface dialog, int which)
                    {
                        party.prepare();
                        dismiss();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showJoinCode(final PartyClient party)
    {
        PartyClient.State state = party.getState();
        if (state.joinCode == null)
        {
            party.retrieveJoinCode(false);
            showPartyCodeWhenAvailable(
                    getActivity(), party, "Party code", null, 0);
            Toast.makeText(getActivity(), "Retrieving Party code", Toast.LENGTH_SHORT).show();
            return;
        }
        final String displayedCode = state.joinCode;
        new AlertDialog.Builder(getActivity())
                .setTitle("Party code")
                .setMessage(state.joinCodeExpired
                        ? displayedCode + "\nExpired - rotate to issue a new code."
                        : displayedCode)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton("Rotate", new DialogInterface.OnClickListener()
                {
                    @Override public void onClick(DialogInterface dialog, int which)
                    {
                        party.retrieveJoinCode(true);
                        showPartyCodeWhenAvailable(
                                getActivity(), party,
                                "Party code", displayedCode, 0);
                    }
                }).show();
    }

    private void showPartyCodeWhenAvailable(
            final Activity activity,
            final PartyClient party,
            final String title,
            final String previousJoinCode,
            final int attempt)
    {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        PartyClient.State state = party.getState();
        if (state.host && state.joined && state.joinCode != null
                && !state.joinCode.equals(previousJoinCode))
        {
            showPartyCode(activity, title, state.joinCode);
            return;
        }
        if (attempt >= 100
                || "ERROR".equals(state.status)
                || "PREPARATION_FAILED".equals(state.status))
        {
            Toast.makeText(activity, "Party code unavailable", Toast.LENGTH_LONG).show();
            return;
        }
        activity.getWindow().getDecorView().postDelayed(new Runnable()
        {
            @Override public void run()
            {
                showPartyCodeWhenAvailable(
                        activity, party, title, previousJoinCode, attempt + 1);
            }
        }, 100);
    }

    private void showPartyCode(Activity activity, String title, String joinCode)
    {
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage("Join code: " + joinCode)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showInviteMenu(final PartyClient party)
    {
        PartyClient.State state = party.getState();
        if (state.connectedSessionHandles.isEmpty())
        {
            party.listClients();
            Toast.makeText(getActivity(), "Loading connected clients", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] handles = state.connectedSessionHandles.toArray(
                new String[state.connectedSessionHandles.size()]);
        new AlertDialog.Builder(getActivity())
                .setTitle("Invite connected client")
                .setItems(handles, new DialogInterface.OnClickListener()
                {
                    @Override public void onClick(DialogInterface dialog, int which)
                    {
                        party.invite(handles[which]);
                    }
                }).show();
    }

    private void confirmEnd(final PartyClient party)
    {
        new AlertDialog.Builder(getActivity())
                .setTitle("End Party?")
                .setMessage("This ends Party playback for every participant.")
                .setPositiveButton("End Party", new DialogInterface.OnClickListener()
                {
                    @Override public void onClick(DialogInterface dialog, int which)
                    {
                        party.end();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void onVideoInfo()
    {
        log.debug("Showing Video Info View");
        client.eventbus().post(new VideoInfoShow());
    }

    public void buttonClick(View v)
    {
        try
        {
            log.debug("Clicked: {}", v.getTag());
            String key = v.getTag().toString().toLowerCase();
            boolean hide = key.startsWith("_");

            if (hide)
            {
                key = key.substring(1);
            }
            if (hide) dismiss();

            // unique case... if the video is paused, and this pause/play button is clicked,
            // the hide the navigation.
            if ("play_pause".equalsIgnoreCase(key) && client.isVideoPaused())
            {
                dismiss();
            }

            int sageCommand = SageCommand.parseByKey(key).getEventCode();

            if (sageCommand == -1)
            {
                log.warn("Invalid SageTV Command '{}'", key);
            }
            else
            {
                EventRouter.postCommand(client, sageCommand);
            }
        }
        catch (Throwable t)
        {
            log.error("Button Not Implemented for {} with ID {}", v.getTag(), v.getId(), t);
        }
    }

    // @OnClick(R.id.nav_switch_player)
    public void onSwitchPlayer()
    {
        dismiss();

        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.title_switch_player)
                .setMessage(getResources().getString(R.string.msg_switch_player, getPlayerName(), getOtherPlayerName()))
                .setNegativeButton(R.string.no, new DialogInterface.OnClickListener()
                {
                    @Override
                    public void onClick(DialogInterface dialog, int which)
                    {
                        dismiss();
                    }
                })
                .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener()
                {
                    @Override
                    public void onClick(DialogInterface dialog, int which)
                    {
                        if (isExoPlayer())
                        {
                            client.properties().setString(PrefStore.Keys.default_player, "ijkplayer");
                        }
                        else
                        {
                            client.properties().setString(PrefStore.Keys.default_player, "exoplayer");
                        }

                        AppUtil.message(MiniclientApplication.get().getString(R.string.msg_player_changed, getPlayerName()));
                        dismiss();
                    }
                })
                .setNeutralButton(R.string.yes_once, new DialogInterface.OnClickListener()
                {
                    @Override
                    public void onClick(DialogInterface dialog, int which)
                    {
                        client.eventbus().post(new ChangePlayerOneTime());
                        AppUtil.message(MiniclientApplication.get().getString(R.string.msg_player_changed_one_time, getOtherPlayerName(), getPlayerName()));
                        dismiss();
                    }
                }).show();
    }

    // @OnClick(R.id.nav_toggle_ar)
    public void onToggleAspectRatio()
    {
        Context ctx = getActivity();
        if (ctx != null && !NonLeanbackAspect.isLeanback(ctx))
        {
            // Phone/tablet/foldable: cycle client-side Fit/Fill/Zoom.
            NonLeanbackAspect.Mode next = NonLeanbackAspect.cycle(ctx);
            Toast.makeText(ctx, "Aspect: " + next.label(), Toast.LENGTH_SHORT).show();
            MiniPlayerPlugin p = client.getPlayer();
            if (p instanceof sagex.miniclient.android.video.BaseMediaPlayerImpl)
            {
                ((sagex.miniclient.android.video.BaseMediaPlayerImpl<?, ?>) p).updatePlayerView();
            }
            dismiss();
            return;
        }
        // Leanback (TV): defer to the SageTV server AR_TOGGLE cycle (Source/Stretch/Zoom).
        client.eventbus().post(ToggleAspectRatioEvent.INSTANCE);
    }

    public void onToggleOrientation()
    {
        Activity act = getActivity();
        if (act == null || OrientationController.isLeanback(act)) return;
        OrientationController.Mode next = OrientationController.cycleAndApply(act);
        Toast.makeText(act, "Orientation: " + next.label, Toast.LENGTH_SHORT).show();
        // Keep the OSD visible so the user can cycle through the three modes
        // and preview each one against the video playing behind it.
    }

    // @OnClick(R.id.nav_remote_mode)
    public void onToggleSmartRemote()
    {
        prefs.setSmartRemoteEnabled(!prefs.isSmartRemoteEnabled());
        updateSmartRemoteToggle();
    }

    private void updateSmartRemoteToggle()
    {
        if (prefs.isSmartRemoteEnabled())
        {
            navSmartRemote.setImageResource(R.drawable.ic_open_with_white_24dp);
        }
        else
        {
            navSmartRemote.setImageResource(R.drawable.ic_open_with_red_24dp);
        }
    }


    // @OnClick(R.id.nav_help)
    public void onHelp()
    {
        dismiss();
        HelpDialogFragment.showDialog(getActivity());
    }

    public void onOpenEqualizer()
    {
        final Activity activity = getActivity();
        dismiss();
        if (activity == null)
        {
            log.warn("onOpenEqualizer: activity is null, cannot open EQ");
            return;
        }
        // Defer the show until after this dialog's dismiss transaction settles,
        // otherwise the FragmentManager can be mid-transaction and swallow the show.
        activity.runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    log.debug("onOpenEqualizer: showing EqualizerFragment");
                    android.app.FragmentManager fm = activity.getFragmentManager();
                    android.app.FragmentTransaction ft = fm.beginTransaction();
                    Fragment prev = fm.findFragmentByTag("equalizer");
                    if (prev != null) ft.remove(prev);
                    sagex.miniclient.android.audio.eq.EqualizerFragment eqFrag =
                            new sagex.miniclient.android.audio.eq.EqualizerFragment();
                    eqFrag.show(ft, "equalizer");
                }
                catch (Throwable t)
                {
                    log.error("onOpenEqualizer: failed to show EQ", t);
                }
            }
        });
    }

    private boolean isExoPlayer()
    {
        return client.properties().getString(PrefStore.Keys.default_player, "exoplayer").equalsIgnoreCase("exoplayer");
    }

    private String getPlayerName()
    {
        return (isExoPlayer() ? "ExoPlayer" : "IJKPlayer");
    }

    private String getOtherPlayerName()
    {
        return (isExoPlayer() ? "IJKPlayer" : "ExoPlayer");
    }

    @Override
    public void dismiss()
    {
        super.dismiss();
        client.eventbus().post(HideSystemUIEvent.INSTANCE);
    }

    // @OnClick({R.id.nav_keyboard, R.id.nav_close, R.id.nav_hide})
    public void buttonClickInternal(View v)
    {
        String tag = v.getTag().toString().toLowerCase();

        if ("_keyboard".equalsIgnoreCase(tag))
        {
            dismiss();
            client.eventbus().post(ShowKeyboardEvent.INSTANCE);
        }
        else if ("_close".equalsIgnoreCase(tag))
        {
            client.eventbus().post(CloseAppEvent.INSTANCE);
        }
        else if ("_hide".equalsIgnoreCase(tag))
        {
            client.eventbus().post(HideNavigationEvent.INSTANCE);
        }
        else if ("_downloads".equalsIgnoreCase(tag))
        {
            // Launch the offline downloads manager without disconnecting from
            // SageTV. The renderer activity stays in the back stack; when the
            // user finishes/cancels DownloadsActivity, Android returns them to
            // the renderer whose onResume posts a repaint event so the server
            // restores the prior menu position.
            dismiss();
            DownloadMenuLauncher.launch(getActivity());
        }
        else
        {
            log.warn("Nothing Handled Internal Event: {}", tag);
        }
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState)
    {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        WindowManager.LayoutParams wmlp = new WindowManager.LayoutParams();
        wmlp.copyFrom(dialog.getWindow().getAttributes());

        wmlp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        wmlp.x = 0;   //x position
        wmlp.y = 0;   //y position
        wmlp.width = WindowManager.LayoutParams.MATCH_PARENT;
        wmlp.height = WindowManager.LayoutParams.MATCH_PARENT;
        dialog.getWindow().setAttributes(wmlp);

        dialog.setOnKeyListener(new DialogInterface.OnKeyListener()
        {
            @Override
            public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event)
            {
                if (keyCode == KeyEvent.KEYCODE_BACK)
                {
                    client.eventbus().post(BackPressedEvent.INSTANCE);
                    return true;
                }
                return false;
            }
        });

        return dialog;
    }

    @Override
    public void onStart()
    {
        super.onStart();
        client.eventbus().register(this);
        Dialog dialog = getDialog();

        if (dialog != null)
        {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            //dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    public static void showDialog(Activity activity)
    {
        log.debug("Showing Navigation");
        // DialogFragment.show() will take care of adding the fragment
        // in a transaction.  We also want to remove any currently showing
        // dialog, so make our own transaction and take care of that here.

        FragmentTransaction ft = activity.getFragmentManager().beginTransaction();
        Fragment prev = activity.getFragmentManager().findFragmentByTag("nav");

        if (prev != null)
        {
            ft.remove(prev);
        }

        ft.addToBackStack(null);

        // Create and show the dialog.
        DialogFragment newFragment = new NavigationFragment();
        newFragment.show(ft, "nav");

    }
}
