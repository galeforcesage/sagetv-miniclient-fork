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

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * SQLite backing store for {@link DownloadRepository} — the on-device
 * "Wiz.bin equivalent" per PRD §6.1 / OQ-6 (decision: SQLite over native
 * binary or flat JSON).
 *
 * <p>Schema is intentionally hybrid: a small set of denormalized columns
 * that the cache uses for ordering and grouping ({@code status},
 * {@code added_timestamp}, {@code queue_priority}) plus a single
 * {@code data_json} TEXT blob that holds the full
 * {@link DownloadMetadata} state. This keeps forward-compat trivial — new
 * fields slot into the JSON without a schema migration — while still giving
 * us per-row atomic writes, row-level transactions, and an indexed status
 * column for future SQL-side filtering.
 */
final class DownloadDatabaseHelper extends SQLiteOpenHelper {
    static final String DB_NAME = "downloads.db";
    static final int DB_VERSION = 4;

    static final String TABLE = "downloads";
    static final String COL_MEDIA_FILE_ID    = "media_file_id";
    static final String COL_STATUS           = "status";
    static final String COL_ADDED_TIMESTAMP  = "added_timestamp";
    static final String COL_QUEUE_PRIORITY   = "queue_priority";
    static final String COL_WATCHED          = "watched";
    static final String COL_AUTO_COMSKIP     = "auto_comskip";
    static final String COL_DATA_JSON        = "data_json";
    static final String COL_HAS_METADATA     = "has_metadata";
    static final String COL_HAS_ARTWORK      = "has_artwork";
    static final String COL_HAS_CAPTIONS     = "has_captions";
    static final String COL_HAS_COMSKIP      = "has_comskip";
    static final String COL_HAS_TRANSCRIPT   = "has_transcript";

    static final String SEARCH_TABLE = "downloads_search";
    static final String SEARCH_FTS_TABLE = "downloads_search_fts";
    static final String COL_SEARCH_MEDIA_FILE_ID = "media_file_id";
    static final String COL_SEARCH_TITLE = "title";
    static final String COL_SEARCH_RECORDING = "recording";
    static final String COL_SEARCH_DESCRIPTION = "description";
    static final String COL_SEARCH_PEOPLE = "people";
    static final String COL_SEARCH_CATEGORY = "category";
    static final String COL_SEARCH_CHANNEL = "channel";
    static final String COL_SEARCH_AIR_DATE = "air_date";
    static final String COL_SEARCH_STATUS = "status";
    static final String COL_SEARCH_ALL_TEXT = "all_text";

    DownloadDatabaseHelper(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + COL_MEDIA_FILE_ID   + " TEXT PRIMARY KEY NOT NULL, "
                + COL_STATUS          + " TEXT NOT NULL, "
                + COL_ADDED_TIMESTAMP + " INTEGER NOT NULL DEFAULT 0, "
                + COL_QUEUE_PRIORITY  + " INTEGER NOT NULL DEFAULT 0, "
            + COL_WATCHED         + " INTEGER NOT NULL DEFAULT 0, "
            + COL_AUTO_COMSKIP    + " INTEGER NOT NULL DEFAULT 0, "
                + COL_HAS_METADATA    + " INTEGER NOT NULL DEFAULT 0, "
                + COL_HAS_ARTWORK     + " INTEGER NOT NULL DEFAULT 0, "
                + COL_HAS_CAPTIONS    + " INTEGER NOT NULL DEFAULT 0, "
                + COL_HAS_COMSKIP     + " INTEGER NOT NULL DEFAULT 0, "
                + COL_HAS_TRANSCRIPT  + " INTEGER NOT NULL DEFAULT 0, "
                + COL_DATA_JSON       + " TEXT NOT NULL"
                + ")");
        db.execSQL("CREATE INDEX idx_downloads_status ON " + TABLE
                + " (" + COL_STATUS + ")");
        createSearchTables(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createSearchTables(db);
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_WATCHED + " INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_AUTO_COMSKIP + " INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_HAS_METADATA + " INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_HAS_ARTWORK + " INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_HAS_CAPTIONS + " INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_HAS_COMSKIP + " INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_HAS_TRANSCRIPT + " INTEGER NOT NULL DEFAULT 0");
        }
    }

    private static void createSearchTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + SEARCH_TABLE + " ("
            + COL_SEARCH_MEDIA_FILE_ID + " TEXT PRIMARY KEY NOT NULL, "
            + COL_SEARCH_TITLE + " TEXT, "
            + COL_SEARCH_RECORDING + " TEXT, "
            + COL_SEARCH_DESCRIPTION + " TEXT, "
            + COL_SEARCH_PEOPLE + " TEXT, "
            + COL_SEARCH_CATEGORY + " TEXT, "
            + COL_SEARCH_CHANNEL + " TEXT, "
            + COL_SEARCH_AIR_DATE + " TEXT, "
            + COL_SEARCH_STATUS + " TEXT, "
            + COL_SEARCH_ALL_TEXT + " TEXT"
            + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_downloads_search_title ON "
            + SEARCH_TABLE + " (" + COL_SEARCH_TITLE + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_downloads_search_air_date ON "
            + SEARCH_TABLE + " (" + COL_SEARCH_AIR_DATE + ")");
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS " + SEARCH_FTS_TABLE
            + " USING fts4(media_file_id, title, recording, description, people, all_text)");
    }
}
