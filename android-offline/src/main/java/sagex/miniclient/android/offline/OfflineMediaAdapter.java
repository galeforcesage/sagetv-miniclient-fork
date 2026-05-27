/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sagex.miniclient.android.offline;

import android.content.Context;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import sagex.miniclient.android.offline.R;

/**
 * RecyclerView adapter for the offline media library grid/list.
 * Each row shows a media card matching the SageTV7 video browser style:
 * landscape thumbnail, title, status text, and a progress indicator
 * for in-progress downloads.
 */
public class OfflineMediaAdapter extends RecyclerView.Adapter<OfflineMediaAdapter.ViewHolder> {

    public interface OnItemActionListener {
        void onPlay(DownloadMetadata meta);
        void onShowOptions(DownloadMetadata meta);
    }

    private final Context context;
    private final OnItemActionListener listener;
    private final List<DownloadMetadata> items = new ArrayList<>();
    private int focusedPosition = -1;

    public OfflineMediaAdapter(Context context, OnItemActionListener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void setItems(List<DownloadMetadata> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    public DownloadMetadata getItem(int position) {
        return items.get(position);
    }

    public int getFocusedPosition() {
        return focusedPosition;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_offline_media, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DownloadMetadata meta = items.get(position);
        holder.bind(meta);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView status;
        final TextView duration;
        final ImageView thumbnail;
        final ImageView statusIcon;
        final ProgressBar progress;

        ViewHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.offline_item_title);
            status = itemView.findViewById(R.id.offline_item_status);
            duration = itemView.findViewById(R.id.offline_item_duration);
            thumbnail = itemView.findViewById(R.id.offline_item_thumbnail);
            statusIcon = itemView.findViewById(R.id.offline_item_status_icon);
            progress = itemView.findViewById(R.id.offline_item_progress);

            // Focus tracking
            itemView.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    focusedPosition = getAdapterPosition();
                }
            });

            // SELECT (Enter/DPad Center) → play completed, show options for others
            itemView.setOnClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos < 0 || pos >= items.size()) return;
                DownloadMetadata m = items.get(pos);
                if (m.getStatus() == DownloadMetadata.Status.COMPLETE) {
                    listener.onPlay(m);
                } else {
                    listener.onShowOptions(m);
                }
            });

            // Long-press or MENU → show options dialog
            itemView.setOnLongClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos < 0 || pos >= items.size()) return false;
                listener.onShowOptions(items.get(pos));
                return true;
            });

            itemView.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && keyCode == KeyEvent.KEYCODE_MENU) {
                    int pos = getAdapterPosition();
                    if (pos >= 0 && pos < items.size()) {
                        listener.onShowOptions(items.get(pos));
                        return true;
                    }
                }
                return false;
            });
        }

        void bind(DownloadMetadata meta) {
            String sessionState = meta.getEffectiveSessionState();
            // Title
            String displayTitle = meta.getTitle();
            if (displayTitle == null || displayTitle.isEmpty()) {
                displayTitle = meta.getMediaFileID();
            }
            title.setText(displayTitle);

            // Duration badge
            if (meta.getDuration() > 0) {
                long totalSec = meta.getDuration() / 1000;
                long hours = totalSec / 3600;
                long mins = (totalSec % 3600) / 60;
                if (hours > 0) {
                    duration.setText(String.format(Locale.US, "%d:%02d:%02d", hours, mins, totalSec % 60));
                } else {
                    duration.setText(String.format(Locale.US, "%d:%02d", mins, totalSec % 60));
                }
                duration.setVisibility(View.VISIBLE);
            } else {
                duration.setVisibility(View.GONE);
            }

            // Status text and icon
            switch (meta.getStatus()) {
                case COMPLETE:
                    status.setText(formatBytes(meta.getFileSize()));
                    status.setTextColor(context.getResources().getColor(R.color.offline_status_complete));
                    statusIcon.setImageResource(sagex.miniclient.android.R.drawable.ic_play_arrow_white_24dp);
                    statusIcon.setColorFilter(context.getResources().getColor(R.color.offline_status_complete));
                    progress.setVisibility(View.GONE);
                    break;
                case DOWNLOADING:
                    status.setText("Downloading — " + meta.getProgressPercent() + "% of " + formatBytes(meta.getFileSize()));
                    status.setTextColor(context.getResources().getColor(R.color.offline_status_downloading));
                    statusIcon.setImageResource(R.drawable.ic_file_download);
                    statusIcon.setColorFilter(context.getResources().getColor(R.color.offline_status_downloading));
                    progress.setVisibility(View.VISIBLE);
                    progress.setProgress(meta.getProgressPercent());
                    break;
                case QUEUED:
                    status.setText("Queued — " + formatBytes(meta.getFileSize()));
                    status.setTextColor(context.getResources().getColor(R.color.offline_text_secondary));
                    statusIcon.setImageResource(R.drawable.ic_file_download);
                    statusIcon.setColorFilter(context.getResources().getColor(R.color.offline_text_secondary));
                    progress.setVisibility(View.GONE);
                    break;
                case PAUSED:
                    if ("paused_by_server".equals(sessionState)) {
                        status.setText("Paused by server — " + meta.getProgressPercent() + "% of " + formatBytes(meta.getFileSize()));
                    } else {
                        status.setText("Paused — " + meta.getProgressPercent() + "% of " + formatBytes(meta.getFileSize()));
                    }
                    status.setTextColor(context.getResources().getColor(R.color.offline_status_paused));
                    statusIcon.setImageResource(sagex.miniclient.android.R.drawable.ic_pause_white_24dp);
                    statusIcon.setColorFilter(context.getResources().getColor(R.color.offline_status_paused));
                    progress.setVisibility(View.VISIBLE);
                    progress.setProgress(meta.getProgressPercent());
                    break;
                case FAILED:
                    String errMsg = meta.getErrorMessage() != null ? meta.getErrorMessage() : "Download failed";
                    status.setText(errMsg);
                    status.setTextColor(context.getResources().getColor(R.color.offline_status_failed));
                    statusIcon.setImageResource(sagex.miniclient.android.R.drawable.ic_info_outline_white_24dp);
                    statusIcon.setColorFilter(context.getResources().getColor(R.color.offline_status_failed));
                    progress.setVisibility(View.GONE);
                    break;
            }

            // Thumbnail placeholder — dark with a play icon overlay for completed
            thumbnail.setImageResource(android.R.color.transparent);
        }

        private String formatBytes(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
            if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
            return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}
