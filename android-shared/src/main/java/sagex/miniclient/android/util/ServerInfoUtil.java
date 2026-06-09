package sagex.miniclient.android.util;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.view.MenuItem;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.PopupMenu.OnMenuItemClickListener;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sagex.miniclient.ServerInfo;
import sagex.miniclient.android.AppUtil;
import sagex.miniclient.android.MiniclientApplication;
import sagex.miniclient.android.R;
import sagex.miniclient.android.UIActivityLifeCycleHandler;
import sagex.miniclient.android.gdx.MiniClientGDXActivity;
import sagex.miniclient.android.opengl.MiniClientOpenGLActivity;
import sagex.miniclient.prefs.PrefStore;
import sagex.miniclient.util.ClientIDGenerator;
import sagex.miniclient.util.Utils;

/**
 * Created by seans on 05/03/16.
 */
public class ServerInfoUtil {
    static Logger log = LoggerFactory.getLogger(ServerInfoUtil.class);

    public static PopupMenu createContextMenu(Context context, View item, OnMenuItemClickListener clickListener) {
        PopupMenu menu = new PopupMenu(context, item);
        menu.inflate(R.menu.server_actions);
        menu.setOnMenuItemClickListener(clickListener);
        return menu;
    }

    public static boolean onMenuItemClick(Context context, MenuItem item, ServerInfo serverInfo, OnAfterCommands after) {
        if (item.getItemId() == R.id.menu_remove) {
            deleteServer(context, serverInfo, true, after);
        } else if (item.getItemId() == R.id.menu_change_name) {
            onChangeName(context, serverInfo, after);
        } else if (item.getItemId() == R.id.menu_change_client_id) {
            onChangeClientID(context, serverInfo, after);
        } else if (item.getItemId() == R.id.menu_duplicate) {
            onDuplicate(context, serverInfo, after);
        } else if (item.getItemId() == R.id.menu_legacy_mode) {
            onSetLegacyMode(context, serverInfo, after);
        } else if (item.getItemId() == R.id.menu_streaming_mode) {
            onSetStreamingMode(context, serverInfo, after);
        } else if (item.getItemId() == R.id.menu_connect) {
            connect(context, serverInfo);
        } else if (item.getItemId() == R.id.menu_connect_locator) {
            if (!Utils.isEmpty(serverInfo.locatorID)) {
                ServerInfo newSI = serverInfo.clone();
                newSI.forceLocator = true;
                connect(context, newSI);
            } else {
                Toast.makeText(context, context.getString(R.string.msg_no_locator), Toast.LENGTH_LONG).show();
            }
        }
        return true;
    }

    public static void connect(Context ctx, ServerInfo si)
    {
        try
        {
            si.lastConnectTime = System.currentTimeMillis();
            si.save(MiniclientApplication.get().getClient().properties());
            MiniclientApplication.get().getClient().getServers().setLastConnectedServer(si);

            // connect to server
            Class start = MiniClientGDXActivity.class;

            if (MiniclientApplication.get().getClient().properties().getBoolean(PrefStore.Keys.use_opengl_ui, true))
            {
                start = MiniClientOpenGLActivity.class;
            }
            Intent i = new Intent(ctx, start);
            i.putExtra(UIActivityLifeCycleHandler.ARG_SERVER_INFO, si);
            if (!(ctx instanceof Activity)) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }

            /*
            Removed to make sure the code passes the Amazon App Store testing.  They do not approve of this functionallity for some reason
            if (MiniclientApplication.get().getClient().properties().getBoolean(PrefStore.Keys.exit_to_home_screen, true)) {
                log.debug("Starting SageTV with Exit TO Home Screen option");
                //i.setFlags(Intent.FLAG_ACTIVITY_TASK_ON_HOME | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                // http://stackoverflow.com/questions/3473168/clear-the-entire-history-stack-and-start-a-new-activity-on-android
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            }
            */


            ctx.startActivity(i);

            /*
            Removed to make sure the code passes the Amazon App Store testing
            if (MiniclientApplication.get().getClient().properties().getBoolean(PrefStore.Keys.exit_to_home_screen, true))
            {
                if (ctx instanceof Activity)
                {
                    ((Activity) ctx).finish();
                }
            }
            */


        }
        catch (Throwable t)
        {
            log.error("Unabled to launch MiniClient Connection to Server {}", si, t);
            Toast.makeText(ctx, "Failed to connect to server: " + t, Toast.LENGTH_LONG).show();
        }

    }

    public interface OnAfterCommands {
        void onAfterDelete(ServerInfo serverInfo);

        void onAfterAdd(ServerInfo serverInfo);
    }

    public static void deleteServer(Context context, final ServerInfo serverInfo, boolean prompt, final OnAfterCommands afterDelete) {
        if (prompt) {
            AppUtil.confirmAction(context, context.getString(R.string.title_remove_server), context.getString(R.string.msg_remove_server), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    log.info("Removed Server: {}", serverInfo);
                    MiniclientApplication.get().getClient().getServers().deleteServer(serverInfo.name);
                    afterDelete.onAfterDelete(serverInfo);
                }
            });
        } else {
            MiniclientApplication.get().getClient().getServers().deleteServer(serverInfo.name);
            afterDelete.onAfterDelete(serverInfo);
        }
    }

    private static void onChangeClientID(final Context context, final ServerInfo serverInfo, final OnAfterCommands after) {
        AppUtil.prompt(context, "Change Client ID", "Enter New Client ID", serverInfo.macAddress, new AppUtil.OnValueChangeListener() {
            @Override
            public void onValueChanged(String oldValue, String newValue) {
                if (newValue==null||newValue.isEmpty()) {
                    serverInfo.macAddress="";
                    serverInfo.save(MiniclientApplication.get().getClient().properties());
                    return;
                }

                ClientIDGenerator gen = new ClientIDGenerator();
                if (newValue.indexOf(':')<0) {
                    newValue = gen.generateId(newValue);
                    serverInfo.macAddress = newValue;
                } else {
                    serverInfo.macAddress = newValue;
                }

                serverInfo.save(MiniclientApplication.get().getClient().properties());
            }
        });
    }



    public static void onChangeName(final Context context, final ServerInfo serverInfo, final OnAfterCommands after) {
        AppUtil.prompt(context, "Change Server Name", "Enter new Server Name", serverInfo.name, new AppUtil.OnValueChangeListener() {
            @Override
            public void onValueChanged(String oldValue, String newValue) {
                ServerInfo newSI = serverInfo.clone();
                newSI.name = newValue;

                // remove the old server
                ServerInfoUtil.deleteServer(context, serverInfo, false, after);

                // save the new server
                newSI.save(MiniclientApplication.get().getClient().properties());

                after.onAfterAdd(newSI);
            }
        });
    }

    public static void onDuplicate(final Context context, final ServerInfo serverInfo, final OnAfterCommands after) {
        AppUtil.prompt(context, "Duplicate", "Enter new Server Name", serverInfo.name, new AppUtil.OnValueChangeListener() {
            @Override
            public void onValueChanged(String oldValue, String newValue) {
                ServerInfo newSI = serverInfo.clone();
                newSI.name = newValue;

                // we need to create a new macAddress on the copy
                ClientIDGenerator gen = new ClientIDGenerator();
                newSI.macAddress = gen.generateId();

                // save the new server
                newSI.save(MiniclientApplication.get().getClient().properties());

                after.onAfterAdd(newSI);

                log.info("New Server Duplicated: {}; {}", newValue, newSI);
            }
        });
    }

    /**
     * Per-server compatibility-mode picker. AUTO is the default and lets the
     * client advertise the full NG capability set; if the server fails to
     * negotiate (IO_UNSPECIFIED on first OPENURL) it auto-flips to LEGACY.
     * LEGACY pins the Placeshifter baseline (good for SageTV 9.2.x). NG pins
     * the full NG set and disables auto-flip (useful when an NG server has
     * a transient hiccup you don't want misclassified).
     */
    public static void onSetLegacyMode(final Context context, final ServerInfo serverInfo, final OnAfterCommands after) {
        final ServerInfo.LegacyMode[] modes = ServerInfo.LegacyMode.values();
        final String[] labels = new String[modes.length];
        int selected = 0;
        for (int i = 0; i < modes.length; i++) {
            switch (modes[i]) {
                case AUTO:
                    labels[i] = "Auto (NG, auto-fallback to Legacy)";
                    break;
                case LEGACY:
                    labels[i] = "Legacy (SageTV 9.2.x \u2014 Placeshifter caps)";
                    break;
                case NG:
                    labels[i] = "NG (full capability set, no auto-fallback)";
                    break;
                default:
                    labels[i] = modes[i].name();
            }
            if (modes[i] == serverInfo.legacyMode) {
                selected = i;
            }
        }

        new AlertDialog.Builder(context)
                .setTitle("Server Mode \u2014 " + serverInfo.name)
                .setSingleChoiceItems(labels, selected, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ServerInfo.LegacyMode chosen = modes[which];
                        if (chosen != serverInfo.legacyMode) {
                            serverInfo.legacyMode = chosen;
                            serverInfo.save(MiniclientApplication.get().getClient().properties());
                            log.info("Server '{}' legacyMode set to {}", serverInfo.name, chosen);
                            Toast.makeText(context,
                                    "Mode saved: " + chosen.name() + ". Reconnect to apply.",
                                    Toast.LENGTH_LONG).show();
                            if (after != null) {
                                after.onAfterAdd(serverInfo);
                            }
                        }
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Per-server Streaming Mode override. Lets the user pin one server tile
     * to FIXED (stock SageTV 9.2.x with no FIXED_PUSH_MEDIA_FORMAT defaults
     * to 352x240 MPEG-4 — pinning to FIXED makes the client send its
     * higher-quality recipe) while another tile inherits the global pref
     * or rides PULL/AUTOMATIC.
     */
    public static void onSetStreamingMode(final Context context, final ServerInfo serverInfo, final OnAfterCommands after) {
        final ServerInfo.StreamingModeOverride[] modes = ServerInfo.StreamingModeOverride.values();
        final String[] labels = new String[modes.length];
        int selected = 0;
        for (int i = 0; i < modes.length; i++) {
            switch (modes[i]) {
                case INHERIT:
                    labels[i] = "Inherit global pref (default)";
                    break;
                case AUTOMATIC:
                    labels[i] = "Automatic (pull if reachable, else dynamic)";
                    break;
                case PULL:
                    labels[i] = "Pull (zero server CPU; needs port 7818)";
                    break;
                case DYNAMIC:
                    labels[i] = "Dynamic push (server picks transcode)";
                    break;
                case FIXED:
                    labels[i] = "Fixed push (use client Fixed Encoding/Remux)";
                    break;
                default:
                    labels[i] = modes[i].name();
            }
            if (modes[i] == serverInfo.streamingModeOverride) {
                selected = i;
            }
        }

        new AlertDialog.Builder(context)
                .setTitle("Streaming Mode — " + serverInfo.name)
                .setSingleChoiceItems(labels, selected, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ServerInfo.StreamingModeOverride chosen = modes[which];
                        if (chosen != serverInfo.streamingModeOverride) {
                            serverInfo.streamingModeOverride = chosen;
                            serverInfo.save(MiniclientApplication.get().getClient().properties());
                            log.info("Server '{}' streamingModeOverride set to {}", serverInfo.name, chosen);
                            Toast.makeText(context,
                                    "Streaming mode saved: " + chosen.name() + ". Reconnect to apply.",
                                    Toast.LENGTH_LONG).show();
                            if (after != null) {
                                after.onAfterAdd(serverInfo);
                            }
                        }
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

}
