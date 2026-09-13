package sagex.miniclient.android;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sagex.miniclient.MiniClient;
import sagex.miniclient.SageCommand;
import sagex.miniclient.ServerInfo;
import sagex.miniclient.android.util.ServerInfoUtil;
import sagex.miniclient.uibridge.EventRouter;

/**
 * Phase 1 external-display output: when the SageTV UI has been relocated onto a
 * genuine extended display (DeX / DisplayPort), this lightweight activity runs
 * on the phone panel and turns the handset into a touch remote. Every button
 * routes through {@link EventRouter#postCommand(MiniClient, SageCommand)} on the
 * app-scoped client, exactly like {@code NavigationFragment}, so it drives the
 * live session regardless of which display is rendering.
 *
 * <p>No rendering happens here and no player/connection state is owned — the
 * session lives in {@code MiniclientApplication}. "Bring playback back to phone"
 * simply relaunches the UI activity on the default display and finishes.</p>
 */
public class RemoteControlActivity extends Activity implements View.OnClickListener
{
    private static final Logger log = LoggerFactory.getLogger(RemoteControlActivity.class);

    private MiniClient client;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_remote_control);

        // Keep the phone awake while it is acting as the remote. If the phone
        // sleeps, desktop-mode / DeX tears down and the relocated UI collapses
        // back onto the handset — this prevents that whole failure mode.
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        client = MiniclientApplication.get().getClient();

        wire(R.id.remote_up);
        wire(R.id.remote_down);
        wire(R.id.remote_left);
        wire(R.id.remote_right);
        wire(R.id.remote_ok);
        wire(R.id.remote_back);
        wire(R.id.remote_home);
        wire(R.id.remote_options);
        wire(R.id.remote_play_pause);
        wire(R.id.remote_info);
        wire(R.id.remote_guide);
        wire(R.id.remote_rew);
        wire(R.id.remote_pause);
        wire(R.id.remote_ff);
        wire(R.id.remote_ch_up);
        wire(R.id.remote_ch_down);
        wire(R.id.nav_bring_back);
    }

    private void wire(int id)
    {
        View v = findViewById(id);
        if (v != null) v.setOnClickListener(this);
    }

    @Override
    public void onClick(View v)
    {
        int id = v.getId();
        if (id == R.id.nav_bring_back)
        {
            bringBackToPhone();
            return;
        }

        SageCommand cmd = commandFor(id);
        if (cmd == null) return;

        if (client == null)
        {
            client = MiniclientApplication.get().getClient();
        }
        if (client == null)
        {
            log.warn("RemoteControlActivity: no client; dropping command {}", cmd);
            return;
        }
        try
        {
            EventRouter.postCommand(client, cmd);
        }
        catch (Throwable t)
        {
            log.warn("RemoteControlActivity: failed to post {}", cmd, t);
        }
    }

    private SageCommand commandFor(int id)
    {
        if (id == R.id.remote_up) return SageCommand.UP;
        if (id == R.id.remote_down) return SageCommand.DOWN;
        if (id == R.id.remote_left) return SageCommand.LEFT;
        if (id == R.id.remote_right) return SageCommand.RIGHT;
        if (id == R.id.remote_ok) return SageCommand.SELECT;
        if (id == R.id.remote_back) return SageCommand.BACK;
        if (id == R.id.remote_home) return SageCommand.HOME;
        if (id == R.id.remote_options) return SageCommand.OPTIONS;
        if (id == R.id.remote_play_pause) return SageCommand.PLAY_PAUSE;
        if (id == R.id.remote_info) return SageCommand.INFO;
        if (id == R.id.remote_guide) return SageCommand.GUIDE;
        if (id == R.id.remote_rew) return SageCommand.REW;
        if (id == R.id.remote_pause) return SageCommand.PAUSE;
        if (id == R.id.remote_ff) return SageCommand.FF;
        if (id == R.id.remote_ch_up) return SageCommand.CHANNEL_UP;
        if (id == R.id.remote_ch_down) return SageCommand.CHANNEL_DOWN;
        return null;
    }

    private void bringBackToPhone()
    {
        try
        {
            ServerInfo si = null;
            if (client != null && client.getServers() != null)
            {
                si = client.getServers().getLastConnectedServer();
            }
            if (si == null)
            {
                Toast.makeText(this, "No recent server to reconnect", Toast.LENGTH_LONG).show();
                return;
            }
            // Relaunch the UI on the phone's default display (allowExternal=false
            // pins it back to the handset), then close the remote.
            ServerInfoUtil.connect(this, si, false);
        }
        catch (Throwable t)
        {
            log.warn("RemoteControlActivity: bringBackToPhone failed", t);
        }
        finally
        {
            finish();
        }
    }

    @Override
    public void onBackPressed()
    {
        // Route hardware/system Back to the session rather than closing the
        // remote, so Back behaves the same as the on-screen Back button.
        if (client != null)
        {
            try { EventRouter.postCommand(client, SageCommand.BACK); return; }
            catch (Throwable ignore) { }
        }
        super.onBackPressed();
    }
}
