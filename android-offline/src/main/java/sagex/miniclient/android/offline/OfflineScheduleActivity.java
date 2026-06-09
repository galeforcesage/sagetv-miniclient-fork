/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 */
package sagex.miniclient.android.offline;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Offline schedule surface (M10 target). For now this acts as the menu node
 * in the offline shell so navigation parity is already in place.
 */
public class OfflineScheduleActivity extends Activity {
        private final SimpleDateFormat timeFmt = new SimpleDateFormat("EEE MMM d h:mm a", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(R.layout.activity_offline_snapshot_list);

        TextView title = findViewById(R.id.snapshot_title);
        TextView subtitle = findViewById(R.id.snapshot_subtitle);
        TextView body = findViewById(R.id.snapshot_body);
        title.setText("Schedule");
                OfflineEpgRepository repo = new OfflineEpgRepository(this);
                OfflineEpgRepository.SnapshotMeta meta = repo.getMeta("sched");
                if (meta != null && meta.fetchedAtMs > 0) {
                        subtitle.setText("Merged cache updated " + timeFmt.format(new Date(meta.fetchedAtMs)));
                } else {
                        subtitle.setText("No snapshot yet");
                }

                List<OfflineEpgRepository.ScheduledRecording> rows = repo.getScheduledRecordings();
                if (rows.isEmpty()) {
                        body.setText("No scheduled recordings are cached yet.\n\n"
                                        + "Connect to your SageTV server to fetch a schedule snapshot.");
                        return;
                }

                StringBuilder sb = new StringBuilder();
                for (OfflineEpgRepository.ScheduledRecording row : rows) {
                        if (sb.length() > 0) sb.append("\n\n");
                        String when = row.startMs > 0 ? timeFmt.format(new Date(row.startMs)) : "(time unknown)";
                        sb.append(when).append("\n");
                        sb.append(row.title == null ? "(untitled)" : row.title).append("\n");
                        if (row.channelNumber != null || row.channelName != null) {
                                sb.append("Channel: ");
                                if (row.channelNumber != null) sb.append(row.channelNumber).append(" ");
                                if (row.channelName != null) sb.append(row.channelName);
                        } else if (row.channelId != null) {
                                sb.append("Channel ID: ").append(row.channelId);
                        }
                        String serverName = row.sourceServerName;
                        if (serverName == null || serverName.trim().isEmpty()) {
                                serverName = row.sourceServerId == null ? "Unknown Server" : row.sourceServerId;
                        }
                        sb.append("\nServer: ").append(serverName);
                }
                body.setText(sb.toString());
    }

    private OfflineNavigationOverlay overlay;

    @Override
    protected void onResume() {
        super.onResume();
        if (overlay == null) {
            overlay = new OfflineNavigationOverlay(this);
        }
        overlay.onResume();
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (overlay != null && overlay.onKeyDown(keyCode, event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, android.view.KeyEvent event) {
        if (overlay != null && overlay.onKeyLongPress(keyCode, event)) return true;
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, android.view.KeyEvent event) {
        if (overlay != null && overlay.onKeyUp(keyCode, event)) return true;
        return super.onKeyUp(keyCode, event);
    }
}
