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
        void onFocused(DownloadMetadata meta);
    }

    private final Context context;
    private final OnItemActionListener listener;
    private final List<DownloadMetadata> items = new ArrayList<>();
    private int focusedPosition = -1;

    public OfflineMediaAdapter(Context context, OnItemActionListener listener) {
        this.context = context;
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setItems(List<DownloadMetadata> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    public DownloadMetadata getItem(int position) {
        return items.get(position);
    }

    public int findPositionByMediaFileId(String mediaFileId) {
        if (mediaFileId == null || mediaFileId.isEmpty()) return -1;
        for (int i = 0; i < items.size(); i++) {
            DownloadMetadata m = items.get(i);
            if (m != null && mediaFileId.equals(m.getMediaFileID())) {
                return i;
            }
        }
        return -1;
    }

    public int getFocusedPosition() {
        return focusedPosition;
    }

    @Override
    public long getItemId(int position) {
        DownloadMetadata m = items.get(position);
        String id = m != null ? m.getMediaFileID() : null;
        return id == null ? position : id.hashCode();
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
                    if (focusedPosition >= 0 && focusedPosition < items.size()) {
                        listener.onFocused(items.get(focusedPosition));
                    }
                }
            });

            // SELECT (Enter/DPad Center) → show options menu (Play is an option in the menu)
            itemView.setOnClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos < 0 || pos >= items.size()) return;
                DownloadMetadata m = items.get(pos);
                focusedPosition = pos;
                listener.onFocused(m);
                listener.onShowOptions(m);
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
            String seriesTitle = firstNonEmpty(meta.getTitle(), meta.getMediaFileID(), "Recording");
            title.setText(seriesTitle);
            title.setTextColor(context.getResources().getColor(R.color.offline_text_primary));

            String episodeTitle = extractEpisodeTitle(meta);
            if (episodeTitle != null && !episodeTitle.isEmpty()) {
                status.setText("\"" + episodeTitle + "\"");
            } else {
                status.setText("");
            }
            status.setTextColor(context.getResources().getColor(R.color.offline_text_primary));

            duration.setVisibility(View.GONE);

            switch (meta.getStatus()) {
                case COMPLETE:
                    statusIcon.setImageResource(sagex.miniclient.android.R.drawable.ic_play_arrow_white_24dp);
                    statusIcon.setColorFilter(context.getResources().getColor(R.color.offline_status_complete));
                    progress.setVisibility(View.VISIBLE);
                    progress.setProgress(100);
                    break;
                case DOWNLOADING:
                    statusIcon.setImageResource(R.drawable.ic_file_download);
                    statusIcon.setColorFilter(context.getResources().getColor(R.color.offline_status_downloading));
                    progress.setVisibility(View.VISIBLE);
                    progress.setProgress(meta.getProgressPercent());
                    break;
                case QUEUED:
                    statusIcon.setImageResource(R.drawable.ic_file_download);
                    statusIcon.setColorFilter(context.getResources().getColor(R.color.offline_text_secondary));
                    progress.setVisibility(View.GONE);
                    break;
                case PAUSED:
                    statusIcon.setImageResource(sagex.miniclient.android.R.drawable.ic_pause_white_24dp);
                    statusIcon.setColorFilter(context.getResources().getColor(R.color.offline_status_paused));
                    progress.setVisibility(View.VISIBLE);
                    progress.setProgress(meta.getProgressPercent());
                    break;
                case FAILED:
                    statusIcon.setImageResource(sagex.miniclient.android.R.drawable.ic_info_outline_white_24dp);
                    statusIcon.setColorFilter(context.getResources().getColor(R.color.offline_status_failed));
                    progress.setVisibility(View.GONE);
                    break;
            }

            // Row-leading icon remains static in compact list mode.
            thumbnail.setImageResource(android.R.drawable.ic_menu_agenda);
        }

        private String extractEpisodeTitle(DownloadMetadata meta) {
            String raw = meta.getOfflineMetadataJson();
            if (raw == null || raw.trim().isEmpty()) return null;
            try {
                OfflineManifestV1 manifest = OfflineManifestV1.parse(raw);
                return firstNonEmpty(manifest.getSubtitle());
            } catch (Exception ignored) {
                return null;
            }
        }

        private String firstNonEmpty(String... values) {
            if (values == null) return null;
            for (String value : values) {
                if (value == null) continue;
                String t = value.trim();
                if (!t.isEmpty()) return t;
            }
            return null;
        }
    }
}
