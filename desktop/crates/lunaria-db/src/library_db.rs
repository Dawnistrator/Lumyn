//! Local SQLite repository for tracks, FTS5 search, playlists, folders, queue, and settings.

use std::collections::HashMap;
use std::fmt;
use std::path::Path;

use lunaria_model::{
    AlbumId, AlbumSummary, ArtistSummary, AudioSpec, FolderSummary, MAX_PERSISTED_QUEUE_SIZE,
    PersistedQueueState, PlaylistId, PlaylistSummary, RepeatMode, SearchCriteria, SearchFilterChip,
    Track, TrackId, TrackSortOrder, UserPreferences,
};
use rusqlite::{Connection, OptionalExtension, Row, params, params_from_iter};

use crate::{DbError, schema};

const TRACK_COLUMNS: &str = "
    t.id, t.file_path, t.title, t.artist, t.album, t.album_artist, t.album_id,
    t.duration_ms, t.track_number, t.disc_number, t.year, t.genre,
    t.folder_path, t.file_name, t.mime_type, t.codec, t.bit_depth,
    t.sample_rate_hz, t.bitrate_kbps, t.channel_count, t.is_lossless,
    t.artwork_uri, t.artwork_hash, t.seed_color_argb, t.file_size, t.modified_ns,
    t.is_favorite, t.date_added_seconds, t.replay_gain_track_db, t.replay_gain_album_db,
    t.replay_gain_track_peak, t.replay_gain_album_peak, t.clip_start_ms,
    t.clip_end_ms, t.cue_source_path, t.play_count, t.last_played_at_ms,
    t.total_play_duration_ms
";

/// Cached file fingerprint and preserved user state used by incremental scanning.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrackFingerprint {
    /// Database track identifier.
    pub id: TrackId,
    /// Last indexed file size in bytes.
    pub file_size: u64,
    /// Last indexed modified timestamp in nanoseconds.
    pub modified_ns: i64,
    /// Last indexed duration in milliseconds.
    pub duration_ms: u64,
    /// Last indexed bit depth.
    pub bit_depth: u16,
    /// Last indexed bitrate in kbps.
    pub bitrate_kbps: u32,
    /// Whether the indexed track is classified as lossless.
    pub is_lossless: bool,
    /// Preserved favorite flag.
    pub is_favorite: bool,
    /// Preserved local play count.
    pub play_count: u32,
    /// Preserved last-played epoch ms.
    pub last_played_at_ms: i64,
    /// Preserved cumulative play duration in ms.
    pub total_play_duration_ms: u64,
    /// Existing cached artwork URI if already computed.
    pub artwork_uri: Option<String>,
    /// Existing artwork BLAKE3 hash.
    pub artwork_hash: Option<String>,
    /// Existing Material 3 seed ARGB color.
    pub seed_color_argb: Option<u32>,
}

/// Metadata patch applied to `tracks` (and propagated into `tracks_fts`) after tag editing.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrackMetadataUpdate {
    /// Updated display title.
    pub title: String,
    /// Updated artist name.
    pub artist: String,
    /// Updated album title.
    pub album: String,
    /// Updated album artist.
    pub album_artist: String,
    /// Updated album grouping identifier.
    pub album_id: AlbumId,
    /// Updated track number.
    pub track_number: u32,
    /// Updated disc number.
    pub disc_number: u32,
    /// Updated release year.
    pub year: u32,
    /// Updated genre string.
    pub genre: String,
    /// Optional updated artwork file path (`Some(None)` clears artwork, `None` leaves unchanged).
    pub artwork_uri: Option<Option<String>>,
    /// Optional updated artwork hash.
    pub artwork_hash: Option<Option<String>>,
    /// Optional updated M3 seed color.
    pub seed_color_argb: Option<Option<u32>>,
    /// Updated file size on disk after writing tags.
    pub file_size: Option<u64>,
    /// Updated modification timestamp on disk after writing tags.
    pub modified_ns: Option<i64>,
}

/// Embedded SQLite library handle running in WAL mode with FTS5 instant search.
pub struct LibraryDb {
    conn: Connection,
}

impl fmt::Debug for LibraryDb {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("LibraryDb").finish_non_exhaustive()
    }
}

impl LibraryDb {
    /// Opens (or creates) a persistent SQLite database at `path`, configuring WAL mode and schema.
    pub fn open(path: &Path) -> Result<Self, DbError> {
        if let Some(parent) = path.parent()
            && !parent.as_os_str().is_empty()
        {
            std::fs::create_dir_all(parent)?;
        }
        let mut conn = Connection::open(path)?;
        schema::apply_pragmas(&conn)?;
        schema::migrate(&mut conn)?;
        Ok(Self { conn })
    }

    /// Opens a private in-memory SQLite database (useful for tests & ephemeral verification).
    pub fn open_in_memory() -> Result<Self, DbError> {
        let mut conn = Connection::open_in_memory()?;
        schema::apply_pragmas(&conn)?;
        schema::migrate(&mut conn)?;
        Ok(Self { conn })
    }

    /// Returns a map from lowercase normalized path -> [`TrackFingerprint`] for all non-CUE tracks.
    pub fn list_file_fingerprints(&self) -> Result<HashMap<String, TrackFingerprint>, DbError> {
        let mut stmt = self.conn.prepare_cached(
            "SELECT normalized_key, id, file_size, modified_ns, duration_ms, bit_depth,
                    bitrate_kbps, is_lossless, is_favorite, play_count,
                    last_played_at_ms, total_play_duration_ms, artwork_uri, artwork_hash,
                    seed_color_argb
             FROM tracks
             WHERE id > 0",
        )?;
        let rows = stmt.query_map([], |row| {
            let key: String = row.get(0)?;
            let id: i64 = row.get(1)?;
            let size: i64 = row.get(2)?;
            let modified_ns: i64 = row.get(3)?;
            let duration_ms: i64 = row.get(4)?;
            let bit_depth: i64 = row.get(5)?;
            let bitrate_kbps: i64 = row.get(6)?;
            let is_lossless: i64 = row.get(7)?;
            let is_favorite: i64 = row.get(8)?;
            let play_count: i64 = row.get(9)?;
            let last_played_at_ms: i64 = row.get(10)?;
            let total_play_duration_ms: i64 = row.get(11)?;
            let artwork_uri: Option<String> = row.get(12)?;
            let artwork_hash: Option<String> = row.get(13)?;
            let seed_raw: Option<i64> = row.get(14)?;

            Ok((
                key,
                TrackFingerprint {
                    id: TrackId(id),
                    file_size: size.max(0) as u64,
                    modified_ns,
                    duration_ms: duration_ms.max(0) as u64,
                    bit_depth: bit_depth.clamp(0, i64::from(u16::MAX)) as u16,
                    bitrate_kbps: bitrate_kbps.clamp(0, i64::from(u32::MAX)) as u32,
                    is_lossless: is_lossless != 0,
                    is_favorite: is_favorite != 0,
                    play_count: play_count.clamp(0, i64::from(u32::MAX)) as u32,
                    last_played_at_ms,
                    total_play_duration_ms: total_play_duration_ms.max(0) as u64,
                    artwork_uri,
                    artwork_hash,
                    seed_color_argb: seed_raw.map(|v| (v as u64 & 0xFFFF_FFFF) as u32),
                },
            ))
        })?;

        let mut map = HashMap::new();
        for item in rows {
            let (k, fp) = item?;
            let _ = map.insert(k, fp);
        }
        Ok(map)
    }

    /// Returns all virtual `.cue` track IDs (`id < 0`) currently indexed.
    pub fn list_cue_track_ids(&self) -> Result<Vec<TrackId>, DbError> {
        let mut stmt = self
            .conn
            .prepare_cached("SELECT id FROM tracks WHERE id < 0 ORDER BY id ASC")?;
        let ids = stmt
            .query_map([], |row| row.get::<_, i64>(0).map(TrackId))?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(ids)
    }

    /// Inserts or updates a batch of tracks inside one transaction while preserving favorites and
    /// play statistics for existing tracks.
    pub fn upsert_tracks_batch(&mut self, tracks: &[Track]) -> Result<usize, DbError> {
        if tracks.is_empty() {
            return Ok(0);
        }
        let tx = self.conn.transaction()?;
        let sql = "
            INSERT INTO tracks (
                id, normalized_key, file_path, title, artist, album, album_artist, album_id,
                duration_ms, track_number, disc_number, year, genre,
                folder_path, file_name, mime_type, codec, bit_depth,
                sample_rate_hz, bitrate_kbps, channel_count, is_lossless,
                artwork_uri, artwork_hash, seed_color_argb, file_size, modified_ns,
                is_favorite, date_added_seconds, replay_gain_track_db, replay_gain_album_db,
                replay_gain_track_peak, replay_gain_album_peak, clip_start_ms,
                clip_end_ms, cue_source_path, play_count, last_played_at_ms,
                total_play_duration_ms
            ) VALUES (
                ?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8,
                ?9, ?10, ?11, ?12, ?13,
                ?14, ?15, ?16, ?17, ?18,
                ?19, ?20, ?21, ?22,
                ?23, ?24, ?25, ?26, ?27,
                ?28, ?29, ?30, ?31,
                ?32, ?33, ?34,
                ?35, ?36, ?37, ?38,
                ?39
            )
            ON CONFLICT(id) DO UPDATE SET
                normalized_key = excluded.normalized_key,
                file_path = excluded.file_path,
                title = excluded.title,
                artist = excluded.artist,
                album = excluded.album,
                album_artist = excluded.album_artist,
                album_id = excluded.album_id,
                duration_ms = excluded.duration_ms,
                track_number = excluded.track_number,
                disc_number = excluded.disc_number,
                year = excluded.year,
                genre = excluded.genre,
                folder_path = excluded.folder_path,
                file_name = excluded.file_name,
                mime_type = excluded.mime_type,
                codec = excluded.codec,
                bit_depth = excluded.bit_depth,
                sample_rate_hz = excluded.sample_rate_hz,
                bitrate_kbps = excluded.bitrate_kbps,
                channel_count = excluded.channel_count,
                is_lossless = excluded.is_lossless,
                artwork_uri = COALESCE(excluded.artwork_uri, tracks.artwork_uri),
                artwork_hash = COALESCE(excluded.artwork_hash, tracks.artwork_hash),
                seed_color_argb = COALESCE(excluded.seed_color_argb, tracks.seed_color_argb),
                file_size = excluded.file_size,
                modified_ns = excluded.modified_ns,
                is_favorite = CASE WHEN excluded.is_favorite != 0 THEN 1 ELSE tracks.is_favorite END,
                replay_gain_track_db = excluded.replay_gain_track_db,
                replay_gain_album_db = excluded.replay_gain_album_db,
                replay_gain_track_peak = excluded.replay_gain_track_peak,
                replay_gain_album_peak = excluded.replay_gain_album_peak,
                clip_start_ms = excluded.clip_start_ms,
                clip_end_ms = excluded.clip_end_ms,
                cue_source_path = excluded.cue_source_path,
                play_count = MAX(tracks.play_count, excluded.play_count),
                last_played_at_ms = MAX(tracks.last_played_at_ms, excluded.last_played_at_ms),
                total_play_duration_ms = MAX(tracks.total_play_duration_ms, excluded.total_play_duration_ms)
        ";

        let mut count = 0usize;
        {
            let mut stmt = tx.prepare_cached(sql)?;
            for track in tracks {
                let norm_key = compute_normalized_key(track);
                let seed_i64 = track.seed_color_argb.map(i64::from);
                let _ = stmt.execute(params![
                    track.id.0,
                    norm_key,
                    track.file_path,
                    track.title,
                    track.artist,
                    track.album,
                    track.album_artist,
                    track.album_id.0,
                    clamp_u64_i64(track.duration_ms),
                    i64::from(track.track_number),
                    i64::from(track.disc_number),
                    i64::from(track.year),
                    track.genre,
                    track.folder_path,
                    track.file_name,
                    track.mime_type,
                    track.audio_spec.codec,
                    i64::from(track.audio_spec.bit_depth),
                    i64::from(track.audio_spec.sample_rate_hz),
                    i64::from(track.audio_spec.bitrate_kbps),
                    i64::from(track.audio_spec.channel_count),
                    i64::from(track.audio_spec.is_lossless),
                    track.artwork_uri,
                    track.artwork_hash,
                    seed_i64,
                    clamp_u64_i64(track.file_size),
                    track.modified_ns,
                    i64::from(track.is_favorite),
                    track.date_added_seconds,
                    track.replay_gain_track_db.map(f64::from),
                    track.replay_gain_album_db.map(f64::from),
                    track.replay_gain_track_peak.map(f64::from),
                    track.replay_gain_album_peak.map(f64::from),
                    track.clip_start_ms.map(clamp_u64_i64),
                    track.clip_end_ms.map(clamp_u64_i64),
                    track.cue_source_path,
                    i64::from(track.play_count),
                    track.last_played_at_ms,
                    clamp_u64_i64(track.total_play_duration_ms),
                ])?;
                count += 1;
            }
        }
        tx.commit()?;
        Ok(count)
    }

    /// Deletes tracks by their IDs in bounded batches (cascades to playlist entries and FTS5).
    pub fn delete_tracks_by_ids(&mut self, ids: &[TrackId]) -> Result<usize, DbError> {
        if ids.is_empty() {
            return Ok(0);
        }
        let tx = self.conn.transaction()?;
        let mut deleted = 0usize;
        for chunk in ids.chunks(500) {
            let placeholders = std::iter::repeat_n("?", chunk.len())
                .collect::<Vec<_>>()
                .join(", ");
            let sql = format!("DELETE FROM tracks WHERE id IN ({placeholders})");
            deleted += tx.execute(&sql, params_from_iter(chunk.iter().map(|id| id.0)))?;
        }
        tx.commit()?;
        Ok(deleted)
    }

    /// Deletes a regular file track and any derived `.cue` tracks pointing to that path.
    pub fn delete_tracks_for_file_path(&mut self, path: &Path) -> Result<usize, DbError> {
        let key = normalize_path_key_from_path(path);
        let n = self.conn.execute(
            "DELETE FROM tracks WHERE normalized_key = ?1 OR LOWER(cue_source_path) = ?1",
            params![key],
        )?;
        Ok(n)
    }

    /// Fetches a single track by ID.
    pub fn get_track(&self, id: TrackId) -> Result<Option<Track>, DbError> {
        let sql = format!("SELECT {TRACK_COLUMNS} FROM tracks t WHERE t.id = ?1 LIMIT 1");
        self.conn
            .query_row(&sql, params![id.0], map_track_row)
            .optional()
            .map_err(DbError::from)
    }

    /// Fetches multiple tracks preserving caller-specified ID order.
    pub fn get_tracks_by_ids(&self, ids: &[TrackId]) -> Result<Vec<Track>, DbError> {
        if ids.is_empty() {
            return Ok(Vec::new());
        }
        let mut by_id = HashMap::with_capacity(ids.len());
        for chunk in ids.chunks(500) {
            let placeholders = std::iter::repeat_n("?", chunk.len())
                .collect::<Vec<_>>()
                .join(", ");
            let sql =
                format!("SELECT {TRACK_COLUMNS} FROM tracks t WHERE t.id IN ({placeholders})");
            let mut stmt = self.conn.prepare(&sql)?;
            let rows =
                stmt.query_map(params_from_iter(chunk.iter().map(|id| id.0)), map_track_row)?;
            for item in rows {
                let track = item?;
                let _ = by_id.insert(track.id, track);
            }
        }
        Ok(ids.iter().filter_map(|id| by_id.get(id).cloned()).collect())
    }

    /// Lists all indexed tracks sorted according to `sort`.
    pub fn list_tracks(&self, sort: TrackSortOrder) -> Result<Vec<Track>, DbError> {
        let order_clause = match sort {
            TrackSortOrder::TitleAsc => "t.title COLLATE NOCASE ASC, t.artist COLLATE NOCASE ASC",
            TrackSortOrder::ArtistAsc => {
                "t.artist COLLATE NOCASE ASC, t.album COLLATE NOCASE ASC, t.track_number ASC"
            }
            TrackSortOrder::AlbumAsc => {
                "t.album COLLATE NOCASE ASC, t.disc_number ASC, t.track_number ASC, t.title COLLATE NOCASE ASC"
            }
            TrackSortOrder::DateAddedDesc => "t.date_added_seconds DESC, t.id ASC",
            TrackSortOrder::DurationDesc => "t.duration_ms DESC, t.title COLLATE NOCASE ASC",
        };
        let sql = format!("SELECT {TRACK_COLUMNS} FROM tracks t ORDER BY {order_clause}");
        let mut stmt = self.conn.prepare(&sql)?;
        let rows = stmt
            .query_map([], map_track_row)?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    /// Returns total number of indexed tracks.
    pub fn track_count(&self) -> Result<usize, DbError> {
        let count: i64 = self
            .conn
            .query_row("SELECT COUNT(*) FROM tracks", [], |r| r.get(0))?;
        Ok(count.max(0) as usize)
    }

    /// Updates a track's favorite star status.
    pub fn set_favorite(&self, id: TrackId, is_favorite: bool) -> Result<(), DbError> {
        let _ = self.conn.execute(
            "UPDATE tracks SET is_favorite = ?2 WHERE id = ?1",
            params![id.0, i64::from(is_favorite)],
        )?;
        Ok(())
    }

    /// Records a local playback completion/session event (100% private in SQLite).
    pub fn record_play(
        &self,
        id: TrackId,
        played_at_ms: i64,
        listened_ms: u64,
    ) -> Result<(), DbError> {
        let _ = self.conn.execute(
            "UPDATE tracks
             SET play_count = play_count + 1,
                 last_played_at_ms = MAX(last_played_at_ms, ?2),
                 total_play_duration_ms = total_play_duration_ms + ?3
             WHERE id = ?1",
            params![id.0, played_at_ms, clamp_u64_i64(listened_ms)],
        )?;
        Ok(())
    }

    /// Updates track metadata columns cleanly after in-file tag writing (auto-updates FTS5).
    pub fn update_track_metadata(
        &self,
        id: TrackId,
        patch: &TrackMetadataUpdate,
    ) -> Result<(), DbError> {
        let current = self.get_track(id)?;
        let Some(existing) = current else {
            return Err(DbError::InvalidData(format!("track {} not found", id.0)));
        };

        let next_art_uri = match &patch.artwork_uri {
            Some(v) => v.clone(),
            None => existing.artwork_uri,
        };
        let next_art_hash = match &patch.artwork_hash {
            Some(v) => v.clone(),
            None => existing.artwork_hash,
        };
        let next_seed = match patch.seed_color_argb {
            Some(v) => v.map(i64::from),
            None => existing.seed_color_argb.map(i64::from),
        };

        let _ = self.conn.execute(
            "UPDATE tracks SET
                title = ?2,
                artist = ?3,
                album = ?4,
                album_artist = ?5,
                album_id = ?6,
                track_number = ?7,
                disc_number = ?8,
                year = ?9,
                genre = ?10,
                artwork_uri = ?11,
                artwork_hash = ?12,
                seed_color_argb = ?13,
                file_size = COALESCE(?14, file_size),
                modified_ns = COALESCE(?15, modified_ns)
             WHERE id = ?1",
            params![
                id.0,
                patch.title,
                patch.artist,
                patch.album,
                patch.album_artist,
                patch.album_id.0,
                i64::from(patch.track_number),
                i64::from(patch.disc_number),
                i64::from(patch.year),
                patch.genre,
                next_art_uri,
                next_art_hash,
                next_seed,
                patch.file_size.map(clamp_u64_i64),
                patch.modified_ns,
            ],
        )?;
        Ok(())
    }

    /// Performs instant (`< 5 ms`) FTS5 + filter chip search across local tracks.
    pub fn search_tracks(
        &self,
        criteria: &SearchCriteria,
        limit: usize,
    ) -> Result<Vec<Track>, DbError> {
        let mut chip_clauses = Vec::new();
        for &chip in &criteria.filters {
            let clause = match chip {
                SearchFilterChip::HiRes => {
                    "(t.is_lossless = 1 AND (t.bit_depth >= 24 OR t.sample_rate_hz > 48000))"
                }
                SearchFilterChip::Lossless => "t.is_lossless = 1",
                SearchFilterChip::Flac => "LOWER(t.codec) = 'flac'",
                SearchFilterChip::Favorites => "t.is_favorite = 1",
            };
            chip_clauses.push(clause);
        }

        let effective_limit = clamp_u64_i64(limit.max(1) as u64);
        let fts_query = build_fts5_prefix_query(&criteria.query);

        if let Some(match_expr) = fts_query {
            let chip_sql = if chip_clauses.is_empty() {
                String::new()
            } else {
                format!(" AND {}", chip_clauses.join(" AND "))
            };
            let sql = format!(
                "SELECT {TRACK_COLUMNS}
                 FROM tracks t
                 INNER JOIN tracks_fts ON tracks_fts.rowid = t.id
                 WHERE tracks_fts MATCH ?1 {chip_sql}
                 ORDER BY bm25(tracks_fts), t.title COLLATE NOCASE ASC
                 LIMIT ?2"
            );
            let mut stmt = self.conn.prepare(&sql)?;
            let rows = stmt
                .query_map(params![match_expr, effective_limit], map_track_row)?
                .collect::<Result<Vec<_>, _>>()?;
            return Ok(rows);
        }

        let where_sql = if chip_clauses.is_empty() {
            String::new()
        } else {
            format!("WHERE {}", chip_clauses.join(" AND "))
        };
        let sql = format!(
            "SELECT {TRACK_COLUMNS} FROM tracks t {where_sql}
             ORDER BY t.title COLLATE NOCASE ASC LIMIT ?1"
        );
        let mut stmt = self.conn.prepare(&sql)?;
        let rows = stmt
            .query_map(params![effective_limit], map_track_row)?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    /// Aggregates local albums sorted alphabetically for the Albums Library tab.
    pub fn list_albums(&self) -> Result<Vec<AlbumSummary>, DbError> {
        let mut stmt = self.conn.prepare_cached(
            "SELECT
                t.album_id,
                COALESCE(NULLIF(MAX(t.album), ''), 'Unknown Album') AS title,
                COALESCE(
                    NULLIF(MAX(CASE WHEN TRIM(t.album_artist) != '' THEN t.album_artist END), ''),
                    NULLIF(MAX(t.artist), ''),
                    'Unknown Artist'
                ) AS artist,
                MAX(t.year) AS year,
                COUNT(*) AS track_count,
                MAX(t.artwork_uri) AS artwork_uri,
                MAX(CASE WHEN t.is_lossless = 1 AND (t.bit_depth >= 24 OR t.sample_rate_hz > 48000) THEN 1 ELSE 0 END) AS has_hi_res
             FROM tracks t
             GROUP BY t.album_id
             ORDER BY title COLLATE NOCASE ASC",
        )?;
        let rows = stmt
            .query_map([], |row| {
                Ok(AlbumSummary {
                    id: AlbumId(row.get(0)?),
                    title: row.get(1)?,
                    artist: row.get(2)?,
                    year: row.get::<_, i64>(3)?.clamp(0, i64::from(u32::MAX)) as u32,
                    track_count: row.get::<_, i64>(4)?.max(0) as usize,
                    artwork_uri: row.get(5)?,
                    has_hi_res: row.get::<_, i64>(6)? != 0,
                })
            })?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    /// Aggregates artists sorted alphabetically for the Artists Library tab.
    pub fn list_artists(&self) -> Result<Vec<ArtistSummary>, DbError> {
        let mut stmt = self.conn.prepare_cached(
            "SELECT
                t.artist,
                COUNT(DISTINCT t.album_id) AS album_count,
                COUNT(*) AS track_count,
                MAX(t.artwork_uri) AS artwork_uri
             FROM tracks t
             GROUP BY LOWER(t.artist)
             ORDER BY t.artist COLLATE NOCASE ASC",
        )?;
        let rows = stmt
            .query_map([], |row| {
                Ok(ArtistSummary {
                    name: row.get(0)?,
                    album_count: row.get::<_, i64>(1)?.max(0) as usize,
                    track_count: row.get::<_, i64>(2)?.max(0) as usize,
                    artwork_uri: row.get(3)?,
                })
            })?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    /// Aggregates folder summaries sorted by folder display path.
    pub fn list_folders(&self) -> Result<Vec<FolderSummary>, DbError> {
        let mut stmt = self.conn.prepare_cached(
            "SELECT
                t.folder_path,
                COUNT(*) AS track_count,
                COALESCE(SUM(t.duration_ms), 0) AS total_duration_ms
             FROM tracks t
             GROUP BY LOWER(t.folder_path)
             ORDER BY t.folder_path COLLATE NOCASE ASC",
        )?;
        let rows = stmt
            .query_map([], |row| {
                let path: String = row.get(0)?;
                let display_name = path
                    .rsplit('/')
                    .find(|seg| !seg.is_empty())
                    .unwrap_or(&path)
                    .to_owned();
                Ok(FolderSummary {
                    path,
                    display_name,
                    track_count: row.get::<_, i64>(1)?.max(0) as usize,
                    total_duration_ms: row.get::<_, i64>(2)?.max(0) as u64,
                })
            })?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    /// Creates a new user playlist and returns its persistent ID.
    pub fn create_playlist(
        &self,
        name: &str,
        created_at_epoch_seconds: i64,
    ) -> Result<PlaylistId, DbError> {
        let clean = name.trim();
        let effective = if clean.is_empty() { "Playlist" } else { clean };
        let _ = self.conn.execute(
            "INSERT INTO playlists (name, created_at_epoch_seconds) VALUES (?1, ?2)",
            params![effective, created_at_epoch_seconds],
        )?;
        Ok(PlaylistId(self.conn.last_insert_rowid()))
    }

    /// Renames an existing playlist.
    pub fn rename_playlist(&self, playlist_id: PlaylistId, new_name: &str) -> Result<(), DbError> {
        let clean = new_name.trim();
        if clean.is_empty() {
            return Err(DbError::InvalidData(
                "playlist name cannot be empty".to_owned(),
            ));
        }
        let _ = self.conn.execute(
            "UPDATE playlists SET name = ?2 WHERE id = ?1",
            params![playlist_id.0, clean],
        )?;
        Ok(())
    }

    /// Deletes a playlist (cross-references removed via `ON DELETE CASCADE`).
    pub fn delete_playlist(&self, playlist_id: PlaylistId) -> Result<(), DbError> {
        let _ = self.conn.execute(
            "DELETE FROM playlists WHERE id = ?1",
            params![playlist_id.0],
        )?;
        Ok(())
    }

    /// Lists all playlists with their current track counts.
    pub fn list_playlists(&self) -> Result<Vec<PlaylistSummary>, DbError> {
        let mut stmt = self.conn.prepare_cached(
            "SELECT p.id, p.name, COUNT(pt.track_id) AS track_count, p.created_at_epoch_seconds
             FROM playlists p
             LEFT JOIN playlist_tracks pt ON p.id = pt.playlist_id
             GROUP BY p.id
             ORDER BY p.name COLLATE NOCASE ASC",
        )?;
        let rows = stmt
            .query_map([], |row| {
                Ok(PlaylistSummary {
                    id: PlaylistId(row.get(0)?),
                    name: row.get(1)?,
                    track_count: row.get::<_, i64>(2)?.max(0) as usize,
                    created_at_epoch_seconds: row.get(3)?,
                })
            })?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    /// Returns ordered tracks belonging to `playlist_id`.
    pub fn get_playlist_tracks(&self, playlist_id: PlaylistId) -> Result<Vec<Track>, DbError> {
        let sql = format!(
            "SELECT {TRACK_COLUMNS}
             FROM tracks t
             INNER JOIN playlist_tracks pt ON t.id = pt.track_id
             WHERE pt.playlist_id = ?1
             ORDER BY pt.position ASC"
        );
        let mut stmt = self.conn.prepare(&sql)?;
        let rows = stmt
            .query_map(params![playlist_id.0], map_track_row)?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    /// Appends track IDs to a playlist cleanly without duplicate PK errors.
    pub fn add_tracks_to_playlist(
        &mut self,
        playlist_id: PlaylistId,
        track_ids: &[TrackId],
    ) -> Result<(), DbError> {
        if track_ids.is_empty() {
            return Ok(());
        }
        let tx = self.conn.transaction()?;
        let max_pos: Option<i64> = tx.query_row(
            "SELECT MAX(position) FROM playlist_tracks WHERE playlist_id = ?1",
            params![playlist_id.0],
            |row| row.get(0),
        )?;
        let mut next_pos = max_pos.map_or(0, |p| p + 1);
        {
            let mut stmt = tx.prepare_cached(
                "INSERT OR IGNORE INTO playlist_tracks (playlist_id, track_id, position)
                 VALUES (?1, ?2, ?3)",
            )?;
            for &track_id in track_ids {
                let changed = stmt.execute(params![playlist_id.0, track_id.0, next_pos])?;
                if changed > 0 {
                    next_pos += 1;
                }
            }
        }
        tx.commit()?;
        Ok(())
    }

    /// Removes one track from a playlist.
    pub fn remove_track_from_playlist(
        &self,
        playlist_id: PlaylistId,
        track_id: TrackId,
    ) -> Result<(), DbError> {
        let _ = self.conn.execute(
            "DELETE FROM playlist_tracks WHERE playlist_id = ?1 AND track_id = ?2",
            params![playlist_id.0, track_id.0],
        )?;
        Ok(())
    }

    /// Replaces playlist track ordering (typically after drag-reorder).
    pub fn reorder_playlist_tracks(
        &mut self,
        playlist_id: PlaylistId,
        ordered_track_ids: &[TrackId],
    ) -> Result<(), DbError> {
        let tx = self.conn.transaction()?;
        let _ = tx.execute(
            "DELETE FROM playlist_tracks WHERE playlist_id = ?1",
            params![playlist_id.0],
        )?;
        {
            let mut stmt = tx.prepare_cached(
                "INSERT OR IGNORE INTO playlist_tracks (playlist_id, track_id, position)
                 VALUES (?1, ?2, ?3)",
            )?;
            for (pos, &track_id) in ordered_track_ids.iter().enumerate() {
                let _ = stmt.execute(params![playlist_id.0, track_id.0, pos as i64])?;
            }
        }
        tx.commit()?;
        Ok(())
    }

    /// Lists persisted root library scan folders.
    pub fn list_watched_folders(&self) -> Result<Vec<String>, DbError> {
        let mut stmt = self
            .conn
            .prepare_cached("SELECT path FROM watched_folders ORDER BY path COLLATE NOCASE ASC")?;
        let rows = stmt
            .query_map([], |row| row.get(0))?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    /// Adds a watched folder root (`added_at_epoch_seconds` recorded locally).
    pub fn add_watched_folder(
        &self,
        folder_path: &str,
        added_at_epoch_seconds: i64,
    ) -> Result<(), DbError> {
        let clean = lunaria_model::FolderFilterConfig::normalize_folder_path(folder_path);
        if clean.is_empty() {
            return Err(DbError::InvalidData(
                "watched folder path cannot be empty".to_owned(),
            ));
        }
        let _ = self.conn.execute(
            "INSERT OR IGNORE INTO watched_folders (path, added_at_epoch_seconds) VALUES (?1, ?2)",
            params![clean, added_at_epoch_seconds],
        )?;
        Ok(())
    }

    /// Removes a watched folder root.
    pub fn remove_watched_folder(&self, folder_path: &str) -> Result<(), DbError> {
        let clean = lunaria_model::FolderFilterConfig::normalize_folder_path(folder_path);
        let _ = self.conn.execute(
            "DELETE FROM watched_folders WHERE LOWER(path) = LOWER(?1)",
            params![clean],
        )?;
        Ok(())
    }

    /// Saves queue order & transport position truncated at [`MAX_PERSISTED_QUEUE_SIZE`].
    pub fn save_persisted_queue(&self, state: &PersistedQueueState) -> Result<(), DbError> {
        let capped_ids: Vec<i64> = state
            .track_ids
            .iter()
            .take(MAX_PERSISTED_QUEUE_SIZE)
            .map(|id| id.0)
            .collect();
        let ids_json = serde_json::to_string(&capped_ids)?;
        let repeat_json = serde_json::to_string(&state.repeat_mode)?;
        let capped_idx = if capped_ids.is_empty() {
            0
        } else {
            state.current_index.min(capped_ids.len() - 1)
        };

        let _ = self.conn.execute(
            "INSERT INTO persisted_queue (
                id, track_ids_json, current_index, position_ms, repeat_mode_json,
                shuffle_enabled, speed
             ) VALUES (1, ?1, ?2, ?3, ?4, ?5, ?6)
             ON CONFLICT(id) DO UPDATE SET
                track_ids_json = excluded.track_ids_json,
                current_index = excluded.current_index,
                position_ms = excluded.position_ms,
                repeat_mode_json = excluded.repeat_mode_json,
                shuffle_enabled = excluded.shuffle_enabled,
                speed = excluded.speed",
            params![
                ids_json,
                capped_idx as i64,
                clamp_u64_i64(state.position_ms),
                repeat_json,
                i64::from(state.shuffle_enabled),
                f64::from(lunaria_model::clamp_playback_speed(state.speed)),
            ],
        )?;
        Ok(())
    }

    /// Loads previously saved queue state if available.
    pub fn load_persisted_queue(&self) -> Result<Option<PersistedQueueState>, DbError> {
        let row: Option<(String, i64, i64, String, i64, f64)> = self
            .conn
            .query_row(
                "SELECT track_ids_json, current_index, position_ms, repeat_mode_json,
                        shuffle_enabled, speed
                 FROM persisted_queue WHERE id = 1",
                [],
                |r| {
                    Ok((
                        r.get(0)?,
                        r.get(1)?,
                        r.get(2)?,
                        r.get(3)?,
                        r.get(4)?,
                        r.get(5)?,
                    ))
                },
            )
            .optional()?;

        let Some((ids_json, idx, pos_ms, repeat_json, shuffle_i, speed_f)) = row else {
            return Ok(None);
        };
        let raw_ids: Vec<i64> = serde_json::from_str(&ids_json)?;
        let track_ids: Vec<TrackId> = raw_ids.into_iter().map(TrackId).collect();
        let repeat_mode: RepeatMode = serde_json::from_str(&repeat_json)?;
        Ok(Some(PersistedQueueState {
            track_ids,
            current_index: idx.max(0) as usize,
            position_ms: pos_ms.max(0) as u64,
            repeat_mode,
            shuffle_enabled: shuffle_i != 0,
            speed: lunaria_model::clamp_playback_speed(speed_f as f32),
        }))
    }

    /// Persists complete [`UserPreferences`] (theme, output mode, device, folder filter, EQ/AutoEQ).
    pub fn save_preferences(&self, prefs: &UserPreferences) -> Result<(), DbError> {
        let json = serde_json::to_string(prefs)?;
        let _ = self.conn.execute(
            "INSERT INTO app_preferences (id, preferences_json) VALUES (1, ?1)
             ON CONFLICT(id) DO UPDATE SET preferences_json = excluded.preferences_json",
            params![json],
        )?;
        Ok(())
    }

    /// Loads saved [`UserPreferences`], returning [`UserPreferences::default()`] if unset.
    pub fn load_preferences(&self) -> Result<UserPreferences, DbError> {
        let json: Option<String> = self
            .conn
            .query_row(
                "SELECT preferences_json FROM app_preferences WHERE id = 1",
                [],
                |row| row.get(0),
            )
            .optional()?;
        match json {
            Some(raw) => serde_json::from_str(&raw).map_err(DbError::from),
            None => Ok(UserPreferences::default()),
        }
    }
}

fn map_track_row(row: &Row<'_>) -> rusqlite::Result<Track> {
    let id: i64 = row.get(0)?;
    let album_id: i64 = row.get(6)?;
    let duration_ms: i64 = row.get(7)?;
    let track_number: i64 = row.get(8)?;
    let disc_number: i64 = row.get(9)?;
    let year: i64 = row.get(10)?;
    let bit_depth: i64 = row.get(16)?;
    let sample_rate_hz: i64 = row.get(17)?;
    let bitrate_kbps: i64 = row.get(18)?;
    let channel_count: i64 = row.get(19)?;
    let is_lossless: i64 = row.get(20)?;
    let seed_raw: Option<i64> = row.get(23)?;
    let file_size: i64 = row.get(24)?;
    let modified_ns: i64 = row.get(25)?;
    let is_favorite: i64 = row.get(26)?;
    let date_added_seconds: i64 = row.get(27)?;
    let rg_track_db: Option<f64> = row.get(28)?;
    let rg_album_db: Option<f64> = row.get(29)?;
    let rg_track_peak: Option<f64> = row.get(30)?;
    let rg_album_peak: Option<f64> = row.get(31)?;
    let clip_start: Option<i64> = row.get(32)?;
    let clip_end: Option<i64> = row.get(33)?;
    let play_count: i64 = row.get(35)?;
    let last_played_at_ms: i64 = row.get(36)?;
    let total_play_duration_ms: i64 = row.get(37)?;

    Ok(Track {
        id: TrackId(id),
        file_path: row.get(1)?,
        title: row.get(2)?,
        artist: row.get(3)?,
        album: row.get(4)?,
        album_artist: row.get(5)?,
        album_id: AlbumId(album_id),
        duration_ms: duration_ms.max(0) as u64,
        track_number: track_number.clamp(0, i64::from(u32::MAX)) as u32,
        disc_number: disc_number.clamp(0, i64::from(u32::MAX)) as u32,
        year: year.clamp(0, i64::from(u32::MAX)) as u32,
        genre: row.get(11)?,
        folder_path: row.get(12)?,
        file_name: row.get(13)?,
        mime_type: row.get(14)?,
        audio_spec: AudioSpec {
            codec: row.get(15)?,
            bit_depth: bit_depth.clamp(0, i64::from(u16::MAX)) as u16,
            sample_rate_hz: sample_rate_hz.clamp(0, i64::from(u32::MAX)) as u32,
            bitrate_kbps: bitrate_kbps.clamp(0, i64::from(u32::MAX)) as u32,
            channel_count: channel_count.clamp(0, i64::from(u16::MAX)) as u16,
            is_lossless: is_lossless != 0,
        },
        artwork_uri: row.get(21)?,
        artwork_hash: row.get(22)?,
        seed_color_argb: seed_raw.map(|v| (v as u64 & 0xFFFF_FFFF) as u32),
        file_size: file_size.max(0) as u64,
        modified_ns,
        is_favorite: is_favorite != 0,
        date_added_seconds,
        replay_gain_track_db: rg_track_db.map(|v| v as f32),
        replay_gain_album_db: rg_album_db.map(|v| v as f32),
        replay_gain_track_peak: rg_track_peak.map(|v| v as f32),
        replay_gain_album_peak: rg_album_peak.map(|v| v as f32),
        clip_start_ms: clip_start.map(|v| v.max(0) as u64),
        clip_end_ms: clip_end.map(|v| v.max(0) as u64),
        cue_source_path: row.get(34)?,
        play_count: play_count.clamp(0, i64::from(u32::MAX)) as u32,
        last_played_at_ms,
        total_play_duration_ms: total_play_duration_ms.max(0) as u64,
    })
}

fn clamp_u64_i64(val: u64) -> i64 {
    val.min(i64::MAX as u64) as i64
}

/// Normalizes a filesystem path into a case-insensitive key matching Windows path semantics.
#[must_use]
pub fn normalize_path_key_from_path(path: &Path) -> String {
    let raw = path.to_string_lossy();
    lunaria_model::FolderFilterConfig::normalize_folder_path(&raw).to_lowercase()
}

fn compute_normalized_key(track: &Track) -> String {
    if track.id.0 < 0 {
        format!("cue:{}", track.id.0)
    } else {
        lunaria_model::FolderFilterConfig::normalize_folder_path(&track.file_path).to_lowercase()
    }
}

/// Sanitizes arbitrary user search input into safe quoted FTS5 prefix terms (`"tok"* AND ...`).
#[must_use]
pub fn build_fts5_prefix_query(raw_query: &str) -> Option<String> {
    let mut terms = Vec::new();
    let mut current = String::new();
    for ch in raw_query.chars() {
        if ch.is_alphanumeric() || ch == '_' {
            current.push(ch);
        } else if !current.is_empty() {
            terms.push(format!("\"{current}\"*"));
            current.clear();
        }
    }
    if !current.is_empty() {
        terms.push(format!("\"{current}\"*"));
    }

    if terms.is_empty() {
        None
    } else {
        Some(terms.join(" AND "))
    }
}
