/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
 */
package sagex.miniclient.android.offline;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Local SQLite store for offline guide/schedule snapshots.
 *
 * Phase 1 policy: ingest full snapshots atomically (replace all rows).
 */
public class OfflineEpgRepository {
    private static final Logger log = LoggerFactory.getLogger(OfflineEpgRepository.class);
    private static final String DB_NAME = "offline_epg.db";
    private static final int DB_VERSION = 3;
    private static final String LEGACY_SERVER_ID = "legacy";

    private final DbHelper db;

    public OfflineEpgRepository(Context context) {
        this.db = new DbHelper(context.getApplicationContext());
    }

    public void replaceGuideSnapshot(String serverId, String serverName, String json) {
        if (json == null || json.isEmpty()) return;
        String sid = normalizeServerId(serverId);
        String sname = normalizeServerName(serverName, sid);
        try {
            JSONObject root = new JSONObject(json);
            JSONArray channels = root.optJSONArray("channels");
            JSONArray airings = root.optJSONArray("airings");

            SQLiteDatabase w = db.getWritableDatabase();
            w.beginTransaction();
            try {
                w.delete("epg_channels", "source_server_id=?", new String[]{sid});
                w.delete("epg_airings", "source_server_id=?", new String[]{sid});

                if (channels != null) {
                    for (int i = 0; i < channels.length(); i++) {
                        JSONObject c = channels.optJSONObject(i);
                        if (c == null) continue;
                        ContentValues cv = new ContentValues();
                        String id = c.optString("id", "");
                        if (id.isEmpty()) continue;
                        cv.put("source_server_id", sid);
                        cv.put("source_server_name", sname);
                        cv.put("id", id);
                        cv.put("number", c.optString("number", null));
                        cv.put("name", c.optString("name", null));
                        cv.put("callsign", c.optString("callsign", null));
                        cv.put("logo_url", c.optString("logo_url", null));
                        w.insert("epg_channels", null, cv);
                    }
                }

                if (airings != null) {
                    for (int i = 0; i < airings.length(); i++) {
                        JSONObject a = airings.optJSONObject(i);
                        if (a == null) continue;
                        String id = a.optString("id", "");
                        if (id.isEmpty()) continue;
                        ContentValues cv = new ContentValues();
                        cv.put("source_server_id", sid);
                        cv.put("source_server_name", sname);
                        cv.put("id", id);
                        cv.put("channel_id", a.optString("channel_id", null));
                        cv.put("start_ms", extractStartMs(a));
                        cv.put("duration_ms", a.optLong("duration_ms", 0L));
                        cv.put("title", a.optString("title", null));
                        cv.put("episode_title", a.optString("episode_title", null));
                        cv.put("season", a.optInt("season", 0));
                        cv.put("episode", a.optInt("episode", 0));
                        cv.put("data_json", a.toString());
                        w.insert("epg_airings", null, cv);
                    }
                }

                upsertMeta(w,
                        "guide",
                    sid,
                    sname,
                        root.optString("snapshot_id", null),
                        parseIsoMillis(root.optString("horizon_start", null)),
                        parseIsoMillis(root.optString("horizon_end", null)));

                w.setTransactionSuccessful();
            } finally {
                w.endTransaction();
            }
        } catch (Exception e) {
            log.warn("replaceGuideSnapshot parse/store failed: {}", e.toString());
        }
    }

    public void replaceScheduleSnapshot(String serverId, String serverName, String json) {
        if (json == null || json.isEmpty()) return;
        String sid = normalizeServerId(serverId);
        String sname = normalizeServerName(serverName, sid);
        try {
            JSONObject root = new JSONObject(json);
            JSONArray scheduled = root.optJSONArray("scheduled");

            SQLiteDatabase w = db.getWritableDatabase();
            w.beginTransaction();
            try {
                w.delete("epg_scheduled", "source_server_id=?", new String[]{sid});

                if (scheduled != null) {
                    for (int i = 0; i < scheduled.length(); i++) {
                        JSONObject s = scheduled.optJSONObject(i);
                        if (s == null) continue;
                        String airingId = s.optString("airing_id", "");
                        if (airingId.isEmpty()) continue;
                        ContentValues cv = new ContentValues();
                        cv.put("source_server_id", sid);
                        cv.put("source_server_name", sname);
                        cv.put("airing_id", airingId);
                        cv.put("channel_id", s.optString("channel_id", null));
                        cv.put("start_ms", extractStartMs(s));
                        cv.put("title", s.optString("title", null));
                        cv.put("data_json", s.toString());
                        w.insert("epg_scheduled", null, cv);
                    }
                }

                upsertMeta(w,
                        "sched",
                    sid,
                    sname,
                        root.optString("snapshot_id", null),
                        0L,
                        parseIsoMillis(root.optString("horizon_end", null)));

                w.setTransactionSuccessful();
            } finally {
                w.endTransaction();
            }
        } catch (Exception e) {
            log.warn("replaceScheduleSnapshot parse/store failed: {}", e.toString());
        }
    }

    /**
     * Replaces one server's favorites snapshot atomically.
     *
     * Expected payload shape:
     * {
     *   "snapshot_id": "...",
     *   "favorites": [
     *     {
     *       "favorite_id": "FAV-123",
     *       "type": "recording|channel|keyword|person|custom",
     *       "title": "Display Name",
     *       "channel_id": "CH-...",
     *       "airing_id": "AIR-...",
     *       ... any additional fields ...
     *     }
     *   ]
     * }
     */
    public void replaceFavoritesSnapshot(String serverId, String serverName, String json) {
        if (json == null || json.isEmpty()) return;
        String sid = normalizeServerId(serverId);
        String sname = normalizeServerName(serverName, sid);
        try {
            JSONObject root = new JSONObject(json);
            JSONArray favorites = root.optJSONArray("favorites");

            SQLiteDatabase w = db.getWritableDatabase();
            w.beginTransaction();
            try {
                w.delete("epg_favorites", "source_server_id=?", new String[]{sid});

                if (favorites != null) {
                    for (int i = 0; i < favorites.length(); i++) {
                        JSONObject f = favorites.optJSONObject(i);
                        if (f == null) continue;

                        String favoriteId = f.optString("favorite_id", "");
                        if (favoriteId.isEmpty()) {
                            // Keep ingest resilient: synthesize a stable row id when the
                            // server has not yet emitted explicit favorite ids.
                            favoriteId = "auto-" + i + "-" + sid;
                        }

                        ContentValues cv = new ContentValues();
                        cv.put("source_server_id", sid);
                        cv.put("source_server_name", sname);
                        cv.put("favorite_id", favoriteId);
                        cv.put("type", f.optString("type", null));
                        cv.put("title", f.optString("title", null));
                        cv.put("channel_id", f.optString("channel_id", null));
                        cv.put("airing_id", f.optString("airing_id", null));
                        cv.put("enabled", f.optBoolean("enabled", true) ? 1 : 0);
                        cv.put("data_json", f.toString());
                        w.insert("epg_favorites", null, cv);
                    }
                }

                upsertMeta(w,
                        "favorites",
                        sid,
                        sname,
                        root.optString("snapshot_id", null),
                        0L,
                        0L);

                w.setTransactionSuccessful();
            } finally {
                w.endTransaction();
            }
        } catch (Exception e) {
            log.warn("replaceFavoritesSnapshot parse/store failed: {}", e.toString());
        }
    }

    public SnapshotMeta getMeta(String kind) {
        SQLiteDatabase r = db.getReadableDatabase();
        try (Cursor c = r.rawQuery(
                "SELECT source_server_id, source_server_name, snapshot_id, horizon_start_ms, horizon_end_ms, fetched_at_ms "
                        + "FROM epg_snapshot_meta WHERE kind=? ORDER BY fetched_at_ms DESC LIMIT 1",
                new String[]{kind})) {
            if (c.moveToFirst()) {
                SnapshotMeta m = new SnapshotMeta();
                m.sourceServerId = c.isNull(0) ? null : c.getString(0);
                m.sourceServerName = c.isNull(1) ? null : c.getString(1);
                m.snapshotId = c.isNull(2) ? null : c.getString(2);
                m.horizonStartMs = c.getLong(3);
                m.horizonEndMs = c.getLong(4);
                m.fetchedAtMs = c.getLong(5);
                return m;
            }
        }
        return null;
    }

    public List<ScheduledRecording> getScheduledRecordings() {
        List<ScheduledRecording> out = new ArrayList<>();
        SQLiteDatabase r = db.getReadableDatabase();
        String sql = "SELECT s.airing_id, s.channel_id, s.start_ms, s.title, c.number, c.name, "
            + "s.source_server_id, s.source_server_name "
                + "FROM epg_scheduled s "
            + "LEFT JOIN epg_channels c ON c.id=s.channel_id AND c.source_server_id=s.source_server_id "
            + "ORDER BY s.start_ms ASC, s.title ASC, s.source_server_name ASC";
        try (Cursor c = r.rawQuery(sql, null)) {
            while (c.moveToNext()) {
                ScheduledRecording sr = new ScheduledRecording();
                sr.airingId = c.isNull(0) ? null : c.getString(0);
                sr.channelId = c.isNull(1) ? null : c.getString(1);
                sr.startMs = c.getLong(2);
                sr.title = c.isNull(3) ? "(untitled)" : c.getString(3);
                sr.channelNumber = c.isNull(4) ? null : c.getString(4);
                sr.channelName = c.isNull(5) ? null : c.getString(5);
                sr.sourceServerId = c.isNull(6) ? null : c.getString(6);
                sr.sourceServerName = c.isNull(7) ? null : c.getString(7);
                out.add(sr);
            }
        }
        return out;
    }

    public enum GuideSort {
        TIME,
        CHANNEL,
        TITLE
    }

    /**
     * Returns a merged guide list across all cached servers.
     *
     * Uniqueness is guaranteed by SQL PK (source_server_id + airing id), so
     * each row is a distinct entity even when two servers schedule/show the
     * same title.
     */
    public List<GuideEntry> getMergedGuideEntries(GuideSort sort, int limit) {
        List<GuideEntry> out = new ArrayList<>();
        SQLiteDatabase r = db.getReadableDatabase();
        String orderBy;
        if (sort == GuideSort.CHANNEL) {
            String channelPrefixExpr = "CASE WHEN c.number IS NULL OR c.number = '' THEN 2147483647 "
                    + "WHEN instr(c.number, '-') > 0 THEN CAST(substr(c.number, 1, instr(c.number, '-') - 1) AS INTEGER) "
                    + "ELSE CAST(c.number AS INTEGER) END";
            String channelSuffixExpr = "CASE WHEN c.number IS NULL OR c.number = '' THEN 2147483647 "
                    + "WHEN instr(c.number, '-') > 0 THEN CAST(substr(c.number, instr(c.number, '-') + 1) AS INTEGER) "
                    + "ELSE 0 END";
            orderBy = channelPrefixExpr + " ASC, " + channelSuffixExpr + " ASC, c.number ASC, a.start_ms ASC, a.title ASC";
        } else if (sort == GuideSort.TITLE) {
            orderBy = "a.title ASC, a.start_ms ASC, c.number ASC";
        } else {
            orderBy = "a.start_ms ASC, c.number ASC, a.title ASC";
        }
        String sql = "SELECT a.id, a.channel_id, a.start_ms, a.duration_ms, a.title, a.episode_title, "
                + "a.season, a.episode, c.number, c.name, a.source_server_id, a.source_server_name, "
                + "c.callsign, a.data_json "
                + "FROM epg_airings a "
                + "LEFT JOIN epg_channels c ON c.id=a.channel_id AND c.source_server_id=a.source_server_id "
                + "ORDER BY " + orderBy;
        String[] args = null;
        if (limit > 0) {
            sql += " LIMIT ?";
            args = new String[]{String.valueOf(limit)};
        }
        try (Cursor c = r.rawQuery(sql, args)) {
            while (c.moveToNext()) {
                GuideEntry ge = new GuideEntry();
                ge.airingId = c.isNull(0) ? null : c.getString(0);
                ge.channelId = c.isNull(1) ? null : c.getString(1);
                ge.startMs = c.getLong(2);
                ge.durationMs = c.getLong(3);
                ge.title = c.isNull(4) ? "(untitled)" : c.getString(4);
                ge.episodeTitle = c.isNull(5) ? null : c.getString(5);
                ge.season = c.getInt(6);
                ge.episode = c.getInt(7);
                ge.channelNumber = c.isNull(8) ? null : c.getString(8);
                ge.channelName = c.isNull(9) ? null : c.getString(9);
                ge.sourceServerId = c.isNull(10) ? null : c.getString(10);
                ge.sourceServerName = c.isNull(11) ? null : c.getString(11);
                ge.channelCallsign = c.isNull(12) ? null : c.getString(12);
                ge.dataJson = c.isNull(13) ? null : c.getString(13);
                out.add(ge);
            }
        }
        return out;
    }

    /**
     * Returns true when any offline snapshot-backed content exists for any server.
     * This includes guide airings, scheduled recordings, or favorites rows.
     */
    public boolean hasAnySnapshotContent() {
        SQLiteDatabase r = db.getReadableDatabase();
        return hasRows(r, "epg_airings") || hasRows(r, "epg_scheduled") || hasRows(r, "epg_favorites");
    }

    /**
     * Returns lightweight snapshot table counts across all servers.
     */
    public SnapshotCounts getSnapshotCounts() {
        SQLiteDatabase r = db.getReadableDatabase();
        SnapshotCounts counts = new SnapshotCounts();
        counts.channels = countRows(r, "epg_channels");
        counts.airings = countRows(r, "epg_airings");
        counts.scheduled = countRows(r, "epg_scheduled");
        counts.favorites = countRows(r, "epg_favorites");
        return counts;
    }

    private static boolean hasRows(SQLiteDatabase r, String table) {
        try (Cursor c = r.rawQuery("SELECT 1 FROM " + table + " LIMIT 1", null)) {
            return c.moveToFirst();
        } catch (Throwable t) {
            return false;
        }
    }

    private static int countRows(SQLiteDatabase r, String table) {
        try (Cursor c = r.rawQuery("SELECT COUNT(1) FROM " + table, null)) {
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
        } catch (Throwable t) {
            return 0;
        }
        return 0;
    }

    private static void upsertMeta(SQLiteDatabase w, String kind, String serverId,
                                   String serverName, String snapshotId,
                                   long startMs, long endMs) {
        ContentValues cv = new ContentValues();
        cv.put("kind", kind);
        cv.put("source_server_id", serverId);
        cv.put("source_server_name", serverName);
        cv.put("snapshot_id", snapshotId);
        cv.put("horizon_start_ms", startMs);
        cv.put("horizon_end_ms", endMs);
        cv.put("fetched_at_ms", System.currentTimeMillis());
        w.insertWithOnConflict("epg_snapshot_meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static long extractStartMs(JSONObject item) {
        if (item.has("start_ms")) return item.optLong("start_ms", 0L);
        return parseIsoMillis(item.optString("start", null));
    }

    private static long parseIsoMillis(String v) {
        if (v == null || v.isEmpty()) return 0L;
        try {
            // 2026-05-27T20:00:00Z
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(v).getTime();
        } catch (Exception ignored) {
        }
        try {
            // 2026-05-27T20:00:00.123Z
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(v).getTime();
        } catch (Exception ignored) {
        }
        return 0L;
    }

    private static String normalizeServerId(String serverId) {
        if (serverId == null || serverId.trim().isEmpty()) return LEGACY_SERVER_ID;
        return serverId.trim();
    }

    private static String normalizeServerName(String serverName, String fallbackId) {
        if (serverName == null || serverName.trim().isEmpty()) return fallbackId;
        return serverName.trim();
    }

    public static final class SnapshotMeta {
        public String sourceServerId;
        public String sourceServerName;
        public String snapshotId;
        public long horizonStartMs;
        public long horizonEndMs;
        public long fetchedAtMs;
    }

    public static final class ScheduledRecording {
        public String airingId;
        public String channelId;
        public long startMs;
        public String title;
        public String channelNumber;
        public String channelName;
        public String sourceServerId;
        public String sourceServerName;
    }

    public static final class GuideEntry {
        public String airingId;
        public String channelId;
        public long startMs;
        public long durationMs;
        public String title;
        public String episodeTitle;
        public int season;
        public int episode;
        public String channelNumber;
        public String channelName;
        public String channelCallsign;
        public String sourceServerId;
        public String sourceServerName;
        public String dataJson;
    }

    public static final class SnapshotCounts {
        public int channels;
        public int airings;
        public int scheduled;
        public int favorites;
    }

    private static final class DbHelper extends SQLiteOpenHelper {
        DbHelper(Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS epg_channels ("
                + "source_server_id TEXT NOT NULL,"
                + "source_server_name TEXT,"
                + "id TEXT NOT NULL,"
                    + "number TEXT,"
                    + "name TEXT,"
                    + "callsign TEXT,"
                + "logo_url TEXT,"
                + "PRIMARY KEY(source_server_id, id))");

            db.execSQL("CREATE TABLE IF NOT EXISTS epg_airings ("
                + "source_server_id TEXT NOT NULL,"
                + "source_server_name TEXT,"
                + "id TEXT NOT NULL,"
                    + "channel_id TEXT,"
                    + "start_ms INTEGER,"
                    + "duration_ms INTEGER,"
                    + "title TEXT,"
                    + "episode_title TEXT,"
                    + "season INTEGER,"
                    + "episode INTEGER,"
                + "data_json TEXT NOT NULL,"
                + "PRIMARY KEY(source_server_id, id))");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_airings_channel_start ON epg_airings(source_server_id, channel_id, start_ms)");

            db.execSQL("CREATE TABLE IF NOT EXISTS epg_scheduled ("
                + "source_server_id TEXT NOT NULL,"
                + "source_server_name TEXT,"
                + "airing_id TEXT NOT NULL,"
                    + "channel_id TEXT,"
                    + "start_ms INTEGER,"
                    + "title TEXT,"
                + "data_json TEXT NOT NULL,"
                + "PRIMARY KEY(source_server_id, airing_id))");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_scheduled_start ON epg_scheduled(start_ms, source_server_name)");

            db.execSQL("CREATE TABLE IF NOT EXISTS epg_favorites ("
                + "source_server_id TEXT NOT NULL,"
                + "source_server_name TEXT,"
                + "favorite_id TEXT NOT NULL,"
                + "type TEXT,"
                + "title TEXT,"
                + "channel_id TEXT,"
                + "airing_id TEXT,"
                + "enabled INTEGER DEFAULT 1,"
                + "data_json TEXT NOT NULL,"
                + "PRIMARY KEY(source_server_id, favorite_id))");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_favorites_title ON epg_favorites(source_server_id, title)");

            db.execSQL("CREATE TABLE IF NOT EXISTS epg_snapshot_meta ("
                + "kind TEXT NOT NULL,"
                + "source_server_id TEXT NOT NULL,"
                + "source_server_name TEXT,"
                    + "snapshot_id TEXT,"
                    + "horizon_start_ms INTEGER,"
                    + "horizon_end_ms INTEGER,"
                + "fetched_at_ms INTEGER,"
                + "PRIMARY KEY(kind, source_server_id))");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion == newVersion) return;
            if (oldVersion < 2) {
            // v1 stored one global snapshot; migrate into the new per-server shape
            // under a legacy synthetic server id so existing cache survives.
            db.execSQL("ALTER TABLE epg_channels RENAME TO epg_channels_old");
            db.execSQL("ALTER TABLE epg_airings RENAME TO epg_airings_old");
            db.execSQL("ALTER TABLE epg_scheduled RENAME TO epg_scheduled_old");
            db.execSQL("ALTER TABLE epg_snapshot_meta RENAME TO epg_snapshot_meta_old");
            onCreate(db);

            db.execSQL("INSERT INTO epg_channels(source_server_id, source_server_name, id, number, name, callsign, logo_url) "
                + "SELECT '" + LEGACY_SERVER_ID + "', 'Legacy', id, number, name, callsign, logo_url FROM epg_channels_old");

            db.execSQL("INSERT INTO epg_airings(source_server_id, source_server_name, id, channel_id, start_ms, duration_ms, title, episode_title, season, episode, data_json) "
                + "SELECT '" + LEGACY_SERVER_ID + "', 'Legacy', id, channel_id, start_ms, duration_ms, title, episode_title, season, episode, data_json FROM epg_airings_old");

            db.execSQL("INSERT INTO epg_scheduled(source_server_id, source_server_name, airing_id, channel_id, start_ms, title, data_json) "
                + "SELECT '" + LEGACY_SERVER_ID + "', 'Legacy', airing_id, channel_id, start_ms, title, data_json FROM epg_scheduled_old");

            db.execSQL("INSERT INTO epg_snapshot_meta(kind, source_server_id, source_server_name, snapshot_id, horizon_start_ms, horizon_end_ms, fetched_at_ms) "
                + "SELECT kind, '" + LEGACY_SERVER_ID + "', 'Legacy', snapshot_id, horizon_start_ms, horizon_end_ms, fetched_at_ms FROM epg_snapshot_meta_old");

            db.execSQL("DROP TABLE IF EXISTS epg_channels_old");
            db.execSQL("DROP TABLE IF EXISTS epg_airings_old");
            db.execSQL("DROP TABLE IF EXISTS epg_scheduled_old");
            db.execSQL("DROP TABLE IF EXISTS epg_snapshot_meta_old");
            return;
            }
            if (oldVersion < 3) {
                db.execSQL("CREATE TABLE IF NOT EXISTS epg_favorites ("
                    + "source_server_id TEXT NOT NULL,"
                    + "source_server_name TEXT,"
                    + "favorite_id TEXT NOT NULL,"
                    + "type TEXT,"
                    + "title TEXT,"
                    + "channel_id TEXT,"
                    + "airing_id TEXT,"
                    + "enabled INTEGER DEFAULT 1,"
                    + "data_json TEXT NOT NULL,"
                    + "PRIMARY KEY(source_server_id, favorite_id))");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_favorites_title ON epg_favorites(source_server_id, title)");
                return;
            }
            db.execSQL("DROP TABLE IF EXISTS epg_snapshot_meta");
            db.execSQL("DROP TABLE IF EXISTS epg_favorites");
            db.execSQL("DROP TABLE IF EXISTS epg_scheduled");
            db.execSQL("DROP TABLE IF EXISTS epg_airings");
            db.execSQL("DROP TABLE IF EXISTS epg_channels");
            onCreate(db);
        }
    }
}
