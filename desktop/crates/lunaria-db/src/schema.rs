//! SQLite schema, WAL pragmas, and FTS5 triggers matching Lunaria Android Room v3 parity.

use rusqlite::Connection;

use crate::DbError;

const CURRENT_SCHEMA_VERSION: i64 = 2;

/// Applies WAL concurrency, foreign key enforcement, and fast synchronous pragmas.
pub(crate) fn apply_pragmas(conn: &Connection) -> Result<(), DbError> {
    conn.execute_batch(
        "
        PRAGMA journal_mode = WAL;
        PRAGMA synchronous = NORMAL;
        PRAGMA foreign_keys = ON;
        PRAGMA busy_timeout = 5000;
        PRAGMA temp_store = MEMORY;
        ",
    )?;
    Ok(())
}

/// Runs idempotent schema migrations guarded by `PRAGMA user_version`.
pub(crate) fn migrate(conn: &mut Connection) -> Result<(), DbError> {
    let version: i64 = conn.query_row("PRAGMA user_version", [], |row| row.get(0))?;
    if version >= CURRENT_SCHEMA_VERSION {
        return Ok(());
    }

    let tx = conn.transaction()?;
    if version < 1 {
        tx.execute_batch(
            "
        CREATE TABLE IF NOT EXISTS tracks (
            id INTEGER PRIMARY KEY,
            normalized_key TEXT NOT NULL UNIQUE,
            file_path TEXT NOT NULL,
            title TEXT NOT NULL,
            artist TEXT NOT NULL,
            album TEXT NOT NULL,
            album_artist TEXT NOT NULL,
            album_id INTEGER NOT NULL,
            duration_ms INTEGER NOT NULL,
            track_number INTEGER NOT NULL,
            disc_number INTEGER NOT NULL,
            year INTEGER NOT NULL,
            genre TEXT NOT NULL,
            folder_path TEXT NOT NULL,
            file_name TEXT NOT NULL,
            mime_type TEXT NOT NULL,
            codec TEXT NOT NULL,
            bit_depth INTEGER NOT NULL,
            sample_rate_hz INTEGER NOT NULL,
            bitrate_kbps INTEGER NOT NULL,
            channel_count INTEGER NOT NULL,
            is_lossless INTEGER NOT NULL,
            artwork_uri TEXT,
            artwork_hash TEXT,
            seed_color_argb INTEGER,
            file_size INTEGER NOT NULL,
            modified_ns INTEGER NOT NULL,
            is_favorite INTEGER NOT NULL DEFAULT 0,
            date_added_seconds INTEGER NOT NULL,
            replay_gain_track_db REAL,
            replay_gain_album_db REAL,
            replay_gain_track_peak REAL,
            replay_gain_album_peak REAL,
            clip_start_ms INTEGER,
            clip_end_ms INTEGER,
            cue_source_path TEXT,
            play_count INTEGER NOT NULL DEFAULT 0,
            last_played_at_ms INTEGER NOT NULL DEFAULT 0,
            total_play_duration_ms INTEGER NOT NULL DEFAULT 0
        );

        CREATE INDEX IF NOT EXISTS idx_tracks_album_id ON tracks(album_id);
        CREATE INDEX IF NOT EXISTS idx_tracks_artist_nocase ON tracks(artist COLLATE NOCASE);
        CREATE INDEX IF NOT EXISTS idx_tracks_folder_path ON tracks(folder_path COLLATE NOCASE);
        CREATE INDEX IF NOT EXISTS idx_tracks_date_added ON tracks(date_added_seconds DESC);
        CREATE INDEX IF NOT EXISTS idx_tracks_last_played ON tracks(last_played_at_ms DESC);
        CREATE INDEX IF NOT EXISTS idx_tracks_file_path_nocase ON tracks(file_path COLLATE NOCASE);

        CREATE VIRTUAL TABLE IF NOT EXISTS tracks_fts USING fts5(
            title,
            artist,
            album,
            album_artist,
            genre,
            folder_path,
            file_name,
            content='tracks',
            content_rowid='id',
            tokenize='unicode61 remove_diacritics 2'
        );

        CREATE TRIGGER IF NOT EXISTS tracks_ai AFTER INSERT ON tracks BEGIN
            INSERT INTO tracks_fts(
                rowid, title, artist, album, album_artist, genre, folder_path, file_name
            ) VALUES (
                new.id, new.title, new.artist, new.album, new.album_artist,
                new.genre, new.folder_path, new.file_name
            );
        END;

        CREATE TRIGGER IF NOT EXISTS tracks_ad AFTER DELETE ON tracks BEGIN
            INSERT INTO tracks_fts(
                tracks_fts, rowid, title, artist, album, album_artist, genre, folder_path, file_name
            ) VALUES (
                'delete', old.id, old.title, old.artist, old.album, old.album_artist,
                old.genre, old.folder_path, old.file_name
            );
        END;

        CREATE TRIGGER IF NOT EXISTS tracks_au AFTER UPDATE ON tracks BEGIN
            INSERT INTO tracks_fts(
                tracks_fts, rowid, title, artist, album, album_artist, genre, folder_path, file_name
            ) VALUES (
                'delete', old.id, old.title, old.artist, old.album, old.album_artist,
                old.genre, old.folder_path, old.file_name
            );
            INSERT INTO tracks_fts(
                rowid, title, artist, album, album_artist, genre, folder_path, file_name
            ) VALUES (
                new.id, new.title, new.artist, new.album, new.album_artist,
                new.genre, new.folder_path, new.file_name
            );
        END;

        CREATE TABLE IF NOT EXISTS playlists (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            created_at_epoch_seconds INTEGER NOT NULL
        );

        CREATE TABLE IF NOT EXISTS playlist_tracks (
            playlist_id INTEGER NOT NULL,
            track_id INTEGER NOT NULL,
            position INTEGER NOT NULL,
            PRIMARY KEY (playlist_id, track_id),
            FOREIGN KEY (playlist_id) REFERENCES playlists(id) ON DELETE CASCADE,
            FOREIGN KEY (track_id) REFERENCES tracks(id) ON DELETE CASCADE
        );

        CREATE INDEX IF NOT EXISTS idx_playlist_tracks_track_id ON playlist_tracks(track_id);

        CREATE TABLE IF NOT EXISTS watched_folders (
            path TEXT PRIMARY KEY,
            added_at_epoch_seconds INTEGER NOT NULL
        );

        CREATE TABLE IF NOT EXISTS persisted_queue (
            id INTEGER PRIMARY KEY CHECK (id = 1),
            track_ids_json TEXT NOT NULL,
            current_index INTEGER NOT NULL,
            position_ms INTEGER NOT NULL,
            repeat_mode_json TEXT NOT NULL,
            shuffle_enabled INTEGER NOT NULL,
            speed REAL NOT NULL
        );

        CREATE TABLE IF NOT EXISTS app_preferences (
            id INTEGER PRIMARY KEY CHECK (id = 1),
            preferences_json TEXT NOT NULL
        );
        ",
        )?;
    }

    if version < 2 {
        tx.execute_batch(
            "
        UPDATE tracks
        SET bitrate_kbps = CAST((file_size * 8) / duration_ms AS INTEGER)
        WHERE bitrate_kbps <= 0 AND file_size > 0 AND duration_ms > 0;

        UPDATE tracks
        SET bit_depth = 16
        WHERE is_lossless != 0 AND bit_depth <= 0;
        ",
        )?;
    }

    tx.pragma_update(None, "user_version", CURRENT_SCHEMA_VERSION)?;
    tx.commit()?;
    Ok(())
}
