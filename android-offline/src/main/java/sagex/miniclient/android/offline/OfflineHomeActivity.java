/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package sagex.miniclient.android.offline;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

/**
 * SageTV-style offline shell hub.
 *
 * The UI intentionally mirrors the left-rail menu feel from SageTV while
 * removing online-only sections (Music/Photos/Online/Setup).
 */
public class OfflineHomeActivity extends Activity {

        private View tvPanel;
        private TextView tvPanelTitle;
        private TextView tvPanelItem1;
        private TextView tvPanelItem2;
        private TextView tvPanelItem3;

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

        setContentView(R.layout.activity_offline_home);

        TextView subtitle = findViewById(R.id.offline_home_subtitle);
        OfflineEpgRepository.SnapshotCounts counts = new OfflineEpgRepository(this).getSnapshotCounts();
        int queued = DownloadManager.getInstance(this).getQueue().size();
        subtitle.setText(queued + " downloads, " + counts.channels + " guide channels");

        tvPanel = findViewById(R.id.offline_tv_panel);
        tvPanelTitle = findViewById(R.id.offline_tv_panel_title);
        tvPanelItem1 = findViewById(R.id.offline_tv_recordings);
        tvPanelItem2 = findViewById(R.id.offline_tv_program_guide);
        tvPanelItem3 = findViewById(R.id.offline_tv_recording_schedule);

        View tvItem = findViewById(R.id.offline_nav_tv);
        tvItem.setOnClickListener(v -> showTvPanel());

        tvPanelItem1.setOnClickListener(v -> startActivity(new Intent(this, OfflineLibraryActivity.class)));
        tvPanelItem2.setOnClickListener(v -> startActivity(new Intent(this, OfflineGuideActivity.class)));
        tvPanelItem3.setOnClickListener(v -> startActivity(new Intent(this, OfflineScheduleActivity.class)));

        View videosItem = findViewById(R.id.offline_nav_videos);
        videosItem.setOnClickListener(v -> Toast.makeText(this, "Coming soon", Toast.LENGTH_SHORT).show());

        View searchItem = findViewById(R.id.offline_nav_search);
        searchItem.setOnClickListener(v -> showSearchPanel());

        findViewById(R.id.offline_nav_exit).setOnClickListener(v -> finish());

        // Start with no panel open — the right pane appears only after the
        // user explicitly clicks/selects a rail item.
        hideAllPanels();

        // Prime initial focus so a single DPAD_CENTER/ENTER on TV opens the
        // submenu instead of spending the first press on focus acquisition.
        tvItem.requestFocus();

        // DPAD navigation overlay: process-wide flag. If the user has already
        // activated it on a previous screen we re-show the focus highlight
        // here; otherwise the rail is painted but unfocused.
        overlay = new OfflineNavigationOverlay(this);
        }

        private OfflineNavigationOverlay overlay;

        @Override
        protected void onResume() {
                super.onResume();
                if (overlay != null) overlay.onResume();
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

        private void showTvPanel() {
                showPanel(
                                "Recordings",
                                "Recordings",
                                "Program Guide",
                                "Recording Schedule",
                                true);
        }

        private void showSearchPanel() {
                showPanel(
                                "Search",
                                "TV Airings",
                                "Videos",
                                null,
                                false);
        }

        private void hideAllPanels() {
                if (tvPanel != null) {
                        tvPanel.setVisibility(View.GONE);
                }
        }

        private void showPanel(String title, String item1, String item2, String item3, boolean showThird) {
                hideAllPanels();
                if (tvPanelTitle != null) {
                        tvPanelTitle.setText(title);
                }
                if (tvPanelItem1 != null) {
                        tvPanelItem1.setText(item1);
                        tvPanelItem1.setVisibility(View.VISIBLE);
                }
                if (tvPanelItem2 != null) {
                        tvPanelItem2.setText(item2);
                        tvPanelItem2.setVisibility(View.VISIBLE);
                }
                if (tvPanelItem3 != null) {
                        if (showThird && item3 != null) {
                                tvPanelItem3.setText(item3);
                                tvPanelItem3.setVisibility(View.VISIBLE);
                        } else {
                                tvPanelItem3.setVisibility(View.GONE);
                        }
                }
                if (tvPanel != null) {
                        tvPanel.setVisibility(View.VISIBLE);
                }
    }
}
