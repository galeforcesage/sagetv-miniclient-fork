/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 */
package sagex.miniclient.android.offline;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Offline guide surface (M9 target). M6/M7 reserve wire + storage; until
 * snapshot ingestion ships, this screen exposes a clear user-facing placeholder.
 */
public class OfflineGuideActivity extends Activity {
        private static final long SLOT_MS = 30L * 60L * 1000L;
        private static final long WINDOW_MS = 24L * 60L * 60L * 1000L;
        private static final int LABEL_WIDTH_DP = 210;
        private static final int SLOT_WIDTH_DP = 120;
        private static final int ROW_HEIGHT_DP = 72;

        private final SimpleDateFormat timeFmt = new SimpleDateFormat("EEE h:mm a", Locale.US);
        private final SimpleDateFormat windowFmt = new SimpleDateFormat("MMM d h:mm a", Locale.US);
        private final SimpleDateFormat detailFmt = new SimpleDateFormat("EEE M/d/yyyy h:mm a", Locale.US);

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
                setContentView(R.layout.activity_offline_guide);

                refreshView();

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

        private void refreshView() {
                TextView title = findViewById(R.id.guide_title);
                TextView subtitle = findViewById(R.id.guide_subtitle);
                TextView hint = findViewById(R.id.guide_hint);
                TextView detail = findViewById(R.id.guide_detail);
                LinearLayout matrixRoot = findViewById(R.id.guide_matrix_root);
                matrixRoot.removeAllViews();

                title.setText("Guide");

                OfflineEpgRepository repo = new OfflineEpgRepository(this);
                List<OfflineEpgRepository.GuideEntry> entries =
                                repo.getMergedGuideEntries(OfflineEpgRepository.GuideSort.CHANNEL, 0);
                if (entries.isEmpty()) {
                        subtitle.setText("No snapshot yet");
                        hint.setText("No guide data cached. Connect to one or more servers to fetch snapshots.");
                        detail.setText("No show selected");
                        return;
                }

                OfflineEpgRepository.GuideEntry selected = entries.get(0);
                detail.setText(buildDetailPanelText(selected));

                long minStart = Long.MAX_VALUE;
                long maxEnd = 0L;
                for (OfflineEpgRepository.GuideEntry e : entries) {
                        if (e.startMs <= 0) continue;
                        minStart = Math.min(minStart, e.startMs);
                        maxEnd = Math.max(maxEnd, e.startMs + Math.max(SLOT_MS, e.durationMs));
                }
                if (minStart == Long.MAX_VALUE) {
                        minStart = System.currentTimeMillis();
                        maxEnd = minStart + WINDOW_MS;
                }
                long windowStart = floorToSlot(minStart);
                long windowEnd = Math.min(ceilToSlot(maxEnd), windowStart + WINDOW_MS);

                subtitle.setText("Merged matrix");
                hint.setText("Window: " + windowFmt.format(new Date(windowStart))
                                + " - " + windowFmt.format(new Date(windowEnd))
                                + "  (channel-grouped, merged across servers)");

                List<Long> slots = new ArrayList<>();
                for (long t = windowStart; t < windowEnd; t += SLOT_MS) {
                        slots.add(t);
                }
                matrixRoot.addView(buildTimeHeaderRow(slots));

                Map<String, List<OfflineEpgRepository.GuideEntry>> grouped = groupByChannel(entries);
                for (Map.Entry<String, List<OfflineEpgRepository.GuideEntry>> row : grouped.entrySet()) {
                        matrixRoot.addView(buildChannelRow(row.getKey(), row.getValue(), windowStart, windowEnd));
                }

                scrollToNow(windowStart, windowEnd);
        }

        private void scrollToNow(long windowStart, long windowEnd) {
                final HorizontalScrollView hsv = findViewById(R.id.guide_horizontal_scroll);
                if (hsv == null) return;
                long now = System.currentTimeMillis();
                if (now <= windowStart || now >= windowEnd) return;
                // Anchor "now" just to the right of the channel-label column so
                // the labels stay visible and future shows fill the timeline.
                // Past airings remain reachable by swiping/scrolling left.
                final int targetX = (int) (((double) (now - windowStart) / SLOT_MS) * dp(SLOT_WIDTH_DP));
                hsv.post(() -> hsv.scrollTo(Math.max(0, targetX), 0));
        }

        private View buildTimeHeaderRow(List<Long> slots) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, 0, 0, dp(8));

                TextView left = new TextView(this);
                left.setText("Channel");
                left.setTextColor(getResources().getColor(R.color.offline_text_accent));
                left.setTypeface(Typeface.DEFAULT_BOLD);
                left.setGravity(Gravity.CENTER_VERTICAL);
                left.setBackgroundColor(0xAA355C8A);
                left.setPadding(dp(8), 0, dp(8), 0);
                row.addView(left, new LinearLayout.LayoutParams(dp(LABEL_WIDTH_DP), dp(36)));

                for (Long slot : slots) {
                        TextView s = new TextView(this);
                        s.setText(timeFmt.format(new Date(slot)));
                        s.setTextColor(getResources().getColor(R.color.offline_text_primary));
                        s.setTextSize(12);
                        s.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                        s.setPadding(dp(4), 0, dp(4), 0);
                        s.setBackgroundColor(0xAA355C8A);
                        row.addView(s, new LinearLayout.LayoutParams(dp(SLOT_WIDTH_DP), dp(36)));
                }
                return row;
        }

        private View buildChannelRow(String label, List<OfflineEpgRepository.GuideEntry> items,
                                                                 long windowStart, long windowEnd) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, 0, 0, dp(8));

                TextView left = new TextView(this);
                left.setText(label);
                left.setTextColor(getResources().getColor(R.color.offline_text_primary));
                left.setGravity(Gravity.CENTER_VERTICAL);
                left.setPadding(dp(8), 0, dp(8), 0);
                left.setBackgroundColor(0xAA2F587E);
                row.addView(left, new LinearLayout.LayoutParams(dp(LABEL_WIDTH_DP), dp(ROW_HEIGHT_DP)));

                int totalWidth = (int) (((windowEnd - windowStart) / SLOT_MS) * dp(SLOT_WIDTH_DP));
                FrameLayout timeline = new FrameLayout(this);
                timeline.setBackgroundColor(0x55333A4A);
                row.addView(timeline, new LinearLayout.LayoutParams(totalWidth, dp(ROW_HEIGHT_DP)));

                for (int i = 0; i < items.size(); i++) {
                        OfflineEpgRepository.GuideEntry e = items.get(i);
                        long start = Math.max(windowStart, e.startMs);
                        long end = Math.min(windowEnd, e.startMs + Math.max(SLOT_MS / 2, e.durationMs));
                        if (end <= windowStart || start >= windowEnd || end <= start) continue;

                        int leftPx = (int) (((double) (start - windowStart) / SLOT_MS) * dp(SLOT_WIDTH_DP));
                        int widthPx = Math.max(dp(48), (int) (((double) (end - start) / SLOT_MS) * dp(SLOT_WIDTH_DP)));

                        TextView block = new TextView(this);
                        block.setText(buildAiringBlockText(e));
                        block.setTextColor(getResources().getColor(R.color.offline_text_primary));
                        block.setTextSize(12);
                        block.setMaxLines(2);
                        block.setPadding(dp(6), dp(6), dp(6), dp(6));
                            // Use a state-list background so Shield TV / DPAD focus
                            // is visibly highlighted. The first item in each row is
                            // tinted to mimic SageTV's "now playing" indicator.
                            if (i == 0) {
                                    block.setBackgroundColor(0xCC8B8D4A);
                            } else {
                                    block.setBackgroundResource(R.drawable.offline_epg_cell_bg);
                            }
                        final OfflineEpgRepository.GuideEntry clicked = e;
                        block.setFocusable(true);
                        block.setFocusableInTouchMode(false);
                        block.setClickable(true);
                        block.setOnClickListener(v -> showProgramInformationDialog(clicked));

                        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(widthPx, dp(ROW_HEIGHT_DP) - dp(8));
                        lp.leftMargin = leftPx;
                        lp.topMargin = dp(4);
                        timeline.addView(block, lp);
                }
                return row;
        }

        private void showProgramInformationDialog(OfflineEpgRepository.GuideEntry e) {
                if (e == null) return;
                String message = buildProgramInformationText(e);
                String title = (e.title == null || e.title.isEmpty()) ? "Program Information" : e.title;
                new AlertDialog.Builder(this)
                        .setTitle(title)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
        }

        private String buildProgramInformationText(OfflineEpgRepository.GuideEntry e) {
                JSONObject d = null;
                if (e.dataJson != null && !e.dataJson.isEmpty()) {
                        try { d = new JSONObject(e.dataJson); } catch (Exception ignored) {}
                }
                StringBuilder sb = new StringBuilder();
                String subtitle = e.episodeTitle;
                if ((subtitle == null || subtitle.isEmpty()) && d != null) {
                        subtitle = optStringNonEmpty(d, "episode_title", "subtitle");
                }
                String description = d == null ? null : optStringNonEmpty(d,
                        "description", "summary", "overview", "desc");
                if (subtitle != null && !subtitle.isEmpty()) {
                        sb.append('"').append(subtitle).append('"');
                        if (description != null && !description.isEmpty()) {
                                sb.append(" - ").append(description);
                        }
                        sb.append("\n\n");
                } else if (description != null && !description.isEmpty()) {
                        sb.append(description).append("\n\n");
                }

                if (e.startMs > 0) {
                        sb.append("Airs On:\n  ")
                                .append(detailFmt.format(new Date(e.startMs)));
                        if (e.durationMs > 0) {
                                long endMs = e.startMs + e.durationMs;
                                sb.append(" - ").append(new SimpleDateFormat("h:mm a", Locale.US).format(new Date(endMs)));
                                sb.append("\n  ").append(formatDuration(e.durationMs));
                        }
                        sb.append('\n');
                }

                if (e.channelNumber != null || e.channelName != null || e.channelCallsign != null) {
                        sb.append("  ");
                        if (e.channelNumber != null) sb.append(e.channelNumber).append(' ');
                        if (e.channelCallsign != null && !e.channelCallsign.isEmpty()) {
                                sb.append(e.channelCallsign);
                        } else if (e.channelName != null) {
                                sb.append(e.channelName);
                        }
                        String rated = d == null ? null : optStringNonEmpty(d, "rated", "rating", "tv_rating");
                        if (rated != null) sb.append(" - ").append(rated);
                        sb.append('\n');
                }

                if (e.durationMs > 0) {
                        sb.append("  Run Time: ").append(formatDuration(e.durationMs)).append('\n');
                }

                if (d != null) {
                        List<String> flags = new ArrayList<>();
                        if (d.optBoolean("cc", false)) flags.add("Closed Captioned");
                        if (d.optBoolean("stereo", false)) flags.add("Stereo");
                        if (d.optBoolean("surround", false)) flags.add("Surround");
                        if (d.optBoolean("hdtv", false)) flags.add("HDTV");
                        if (d.optBoolean("first_run", false)) flags.add("First Run");
                        if (!flags.isEmpty()) {
                                sb.append("  ").append(joinComma(flags)).append('\n');
                        }

                        String category = joinCategories(d.opt("categories"));
                        if (category == null) category = optStringNonEmpty(d, "category", "genre");
                        if (category != null) sb.append("Category: ").append(category).append('\n');

                        String oad = optStringNonEmpty(d, "original_air_date", "aired_on", "air_date");
                        if (oad != null) sb.append("Original Air Date: ").append(oad).append('\n');
                }

                if (e.season > 0 || e.episode > 0) {
                        sb.append("Season ").append(e.season)
                                .append(", Episode ").append(e.episode).append('\n');
                }

                sb.append("Server: ").append(safeServer(e));
                return sb.toString().trim();
        }

        private static String optStringNonEmpty(JSONObject obj, String... keys) {
                if (obj == null) return null;
                for (String k : keys) {
                        String v = obj.optString(k, null);
                        if (v != null && !v.trim().isEmpty()) return v;
                }
                return null;
        }

        private static String joinCategories(Object value) {
                if (value instanceof JSONArray) {
                        JSONArray arr = (JSONArray) value;
                        List<String> out = new ArrayList<>();
                        for (int i = 0; i < arr.length(); i++) {
                                String s = arr.optString(i, null);
                                if (s != null && !s.trim().isEmpty()) out.add(s);
                        }
                        return out.isEmpty() ? null : joinComma(out);
                }
                if (value instanceof String) {
                        String s = ((String) value).trim();
                        return s.isEmpty() ? null : s;
                }
                return null;
        }

        private static String joinComma(List<String> values) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < values.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(values.get(i));
                }
                return sb.toString();
        }

        private static String formatDuration(long ms) {
                long totalMin = Math.max(1L, ms / 60000L);
                long h = totalMin / 60;
                long m = totalMin % 60;
                if (h <= 0) return m + (m == 1 ? " minute" : " minutes");
                if (m == 0) return h + (h == 1 ? " hour" : " hours");
                return h + (h == 1 ? " hour " : " hours ") + m + (m == 1 ? " minute" : " minutes");
        }

        private String buildDetailPanelText(OfflineEpgRepository.GuideEntry e) {
                String title = e.title == null || e.title.isEmpty() ? "(untitled)" : e.title;
                StringBuilder sb = new StringBuilder();
                sb.append(title).append('\n');
                if (e.startMs > 0) {
                        sb.append("Airs On: ").append(detailFmt.format(new Date(e.startMs))).append('\n');
                }
                if (e.season > 0 || e.episode > 0) {
                        sb.append("Season ").append(e.season).append("  Episode ").append(e.episode).append('\n');
                }
                if (e.channelNumber != null || e.channelName != null) {
                        sb.append("Channel: ");
                        if (e.channelNumber != null) sb.append(e.channelNumber).append(" ");
                        if (e.channelName != null) sb.append(e.channelName);
                        sb.append('\n');
                }
                sb.append("Server: ").append(safeServer(e));
                return sb.toString();
        }

        private Map<String, List<OfflineEpgRepository.GuideEntry>> groupByChannel(
                        List<OfflineEpgRepository.GuideEntry> entries) {
                Collections.sort(entries, (a, b) -> {
                        int c = compareChannel(a.channelNumber, b.channelNumber);
                        if (c != 0) return c;
                        String an = a.channelName == null ? "" : a.channelName;
                        String bn = b.channelName == null ? "" : b.channelName;
                        c = an.compareToIgnoreCase(bn);
                        if (c != 0) return c;
                        String as = safeServer(a);
                        String bs = safeServer(b);
                        c = as.compareToIgnoreCase(bs);
                        if (c != 0) return c;
                        return Long.compare(a.startMs, b.startMs);
                });

                LinkedHashMap<String, List<OfflineEpgRepository.GuideEntry>> map = new LinkedHashMap<>();
                for (OfflineEpgRepository.GuideEntry e : entries) {
                        String key = buildChannelLabel(e);
                        List<OfflineEpgRepository.GuideEntry> list = map.get(key);
                        if (list == null) {
                                list = new ArrayList<>();
                                map.put(key, list);
                        }
                        list.add(e);
                }
                return map;
        }

        private String buildChannelLabel(OfflineEpgRepository.GuideEntry e) {
                StringBuilder sb = new StringBuilder();
                if (e.channelNumber != null && !e.channelNumber.isEmpty()) {
                        sb.append(e.channelNumber).append(" ");
                }
                if (e.channelName != null && !e.channelName.isEmpty()) {
                        sb.append(e.channelName);
                } else if (e.channelId != null && !e.channelId.isEmpty()) {
                        sb.append("Channel ").append(e.channelId);
                } else {
                        sb.append("Unknown Channel");
                }
                sb.append("\n").append(safeServer(e));
                return sb.toString();
        }

        private String buildAiringBlockText(OfflineEpgRepository.GuideEntry e) {
                String title = e.title == null || e.title.isEmpty() ? "(untitled)" : e.title;
                if (e.episodeTitle != null && !e.episodeTitle.isEmpty()) {
                        return title + "\n" + e.episodeTitle;
                }
                return title;
        }

        private String safeServer(OfflineEpgRepository.GuideEntry e) {
                if (e.sourceServerName != null && !e.sourceServerName.trim().isEmpty()) return e.sourceServerName;
                if (e.sourceServerId != null && !e.sourceServerId.trim().isEmpty()) return e.sourceServerId;
                return "Unknown Server";
        }

        private static int compareChannel(String a, String b) {
                int[] pa = parseChannelParts(a);
                int[] pb = parseChannelParts(b);
                int cmp = Integer.compare(pa[0], pb[0]);
                if (cmp != 0) return cmp;
                cmp = Integer.compare(pa[1], pb[1]);
                if (cmp != 0) return cmp;
                String as = a == null ? "" : a;
                String bs = b == null ? "" : b;
                return as.compareToIgnoreCase(bs);
        }

        private static int[] parseChannelParts(String raw) {
                if (raw == null || raw.trim().isEmpty()) {
                        return new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE};
                }
                String value = raw.trim();
                int dash = value.indexOf('-');
                try {
                        if (dash > 0) {
                                int major = Integer.parseInt(value.substring(0, dash).trim());
                                int minor = Integer.parseInt(value.substring(dash + 1).trim());
                                return new int[]{major, minor};
                        }
                        return new int[]{Integer.parseInt(value), 0};
                } catch (Exception ignored) {
                        return new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE};
                }
        }

        private static long floorToSlot(long ts) {
                return (ts / SLOT_MS) * SLOT_MS;
        }

        private static long ceilToSlot(long ts) {
                return ((ts + SLOT_MS - 1) / SLOT_MS) * SLOT_MS;
        }

        private int dp(int value) {
                return (int) (value * getResources().getDisplayMetrics().density);
    }
}
