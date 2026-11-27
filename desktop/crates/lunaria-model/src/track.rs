//! Track, library summary, and sorting domain types.

use serde::{Deserialize, Serialize};

use crate::AudioSpec;

/// Unique identifier for an indexed local track (negative for CUE virtual tracks).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
pub struct TrackId(pub i64);

/// Unique identifier for a local album group.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
pub struct AlbumId(pub i64);

/// Unique identifier for a user playlist.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
pub struct PlaylistId(pub i64);

/// Single local audio file (or clipped CUE virtual track) indexed in Lunaria.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Track {
    /// Stable database identifier (negative for CUE virtual clipped tracks).
    pub id: TrackId,
    /// Canonical normalized filesystem path or URI string.
    pub file_path: String,
    /// Display title.
    pub title: String,
    /// Performing artist.
    pub artist: String,
    /// Album title.
    pub album: String,
    /// Album artist for compilation grouping.
    pub album_artist: String,
    /// Deterministic album grouping identifier.
    pub album_id: AlbumId,
    /// Duration in milliseconds (clipped duration for CUE tracks).
    pub duration_ms: u64,
    /// Track index within disc.
    pub track_number: u32,
    /// Disc number (`1` when single-disc).
    pub disc_number: u32,
    /// Release year (`0` if absent).
    pub year: u32,
    /// Genre tag string.
    pub genre: String,
    /// Parent folder path normalized with forward slashes.
    pub folder_path: String,
    /// Base file name including extension.
    pub file_name: String,
    /// MIME type string (e.g. `audio/flac`).
    pub mime_type: String,
    /// Technical audio stream details.
    pub audio_spec: AudioSpec,
    /// Cached artwork file path or hash reference if present.
    pub artwork_uri: Option<String>,
    /// BLAKE3 hex digest of embedded/folder artwork bytes.
    pub artwork_hash: Option<String>,
    /// Precomputed Material 3 ARGB seed color extracted from album cover.
    pub seed_color_argb: Option<u32>,
    /// File size in bytes for fast incremental rescanning.
    pub file_size: u64,
    /// File modified timestamp in nanoseconds since Unix epoch.
    pub modified_ns: i64,
    /// Whether the user marked this track as favorite.
    pub is_favorite: bool,
    /// Unix epoch timestamp in seconds when first indexed.
    pub date_added_seconds: i64,
    /// Parsed `REPLAYGAIN_TRACK_GAIN` (dB).
    pub replay_gain_track_db: Option<f32>,
    /// Parsed `REPLAYGAIN_ALBUM_GAIN` (dB).
    pub replay_gain_album_db: Option<f32>,
    /// Parsed `REPLAYGAIN_TRACK_PEAK` linear amplitude.
    pub replay_gain_track_peak: Option<f32>,
    /// Parsed `REPLAYGAIN_ALBUM_PEAK` linear amplitude.
    pub replay_gain_album_peak: Option<f32>,
    /// Clip start offset in milliseconds for `.cue` virtual tracks.
    pub clip_start_ms: Option<u64>,
    /// Optional clip end offset in milliseconds for `.cue` virtual tracks.
    pub clip_end_ms: Option<u64>,
    /// Source `.cue` file path when derived from a cue sheet.
    pub cue_source_path: Option<String>,
    /// Total completed play count recorded locally.
    pub play_count: u32,
    /// Epoch milliseconds when this track was last played.
    pub last_played_at_ms: i64,
    /// Cumulative local playback duration in milliseconds.
    pub total_play_duration_ms: u64,
}

impl Track {
    /// Returns `true` when this track represents a virtual clipped region from a `.cue` sheet.
    #[must_use]
    pub const fn is_cue_track(&self) -> bool {
        self.clip_start_ms.is_some()
    }
}

/// Aggregated summary of a local album in Library views.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AlbumSummary {
    /// Album identifier.
    pub id: AlbumId,
    /// Album title.
    pub title: String,
    /// Primary artist or album artist.
    pub artist: String,
    /// Release year (`0` if unknown).
    pub year: u32,
    /// Number of tracks belonging to this album.
    pub track_count: usize,
    /// Representative artwork path/URI.
    pub artwork_uri: Option<String>,
    /// Whether any track in the album is Hi-Res.
    pub has_hi_res: bool,
}

/// Aggregated summary of a local artist in Library views.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ArtistSummary {
    /// Artist display name.
    pub name: String,
    /// Distinct album count.
    pub album_count: usize,
    /// Total track count.
    pub track_count: usize,
    /// Representative artwork path/URI.
    pub artwork_uri: Option<String>,
}

/// Aggregated summary of a music folder in Library views.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FolderSummary {
    /// Normalized folder path.
    pub path: String,
    /// Short folder display name.
    pub display_name: String,
    /// Number of tracks inside this folder.
    pub track_count: usize,
    /// Combined duration of tracks in milliseconds.
    pub total_duration_ms: u64,
}

/// User playlist header summary.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PlaylistSummary {
    /// Playlist database ID.
    pub id: PlaylistId,
    /// Playlist display name.
    pub name: String,
    /// Track count.
    pub track_count: usize,
    /// Unix epoch seconds when created.
    pub created_at_epoch_seconds: i64,
}

/// Supported sorting modes for track tables and lists.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default, Serialize, Deserialize)]
pub enum TrackSortOrder {
    /// Alphabetical by track title (A-Z).
    #[default]
    TitleAsc,
    /// Alphabetical by artist (A-Z).
    ArtistAsc,
    /// Alphabetical by album, disc, and track number.
    AlbumAsc,
    /// Most recently added tracks first.
    DateAddedDesc,
    /// Longest tracks first.
    DurationDesc,
}
