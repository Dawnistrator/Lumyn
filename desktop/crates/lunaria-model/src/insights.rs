//! 100% offline Smart Playlists and local Listening Stats matching Android `SmartPlaylistEngine`.

use std::collections::HashMap;

use crate::Track;

const DEFAULT_LIMIT: usize = 50;
const DEFAULT_STALE_DAYS: i64 = 30;
const MILLIS_PER_DAY: i64 = 86_400_000;

/// Built-in Smart Playlist categories computed directly from local playback history.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum SmartPlaylistType {
    /// Tracks spun most recently.
    RecentlyPlayed,
    /// Top local favorites ordered by play count.
    MostPlayed,
    /// Never played or untouched for 30+ days.
    ForgottenGems,
    /// 24-bit+ or >48 kHz lossless masters.
    HiResCollection,
    /// Hearted tracks in the local library.
    Favorites,
}

impl SmartPlaylistType {
    /// All 5 built-in smart playlist variants in display order.
    pub const ALL: [Self; 5] = [
        Self::RecentlyPlayed,
        Self::MostPlayed,
        Self::ForgottenGems,
        Self::HiResCollection,
        Self::Favorites,
    ];

    /// Display title matching Android.
    #[must_use]
    pub const fn title(self) -> &'static str {
        match self {
            Self::RecentlyPlayed => "Recently Played",
            Self::MostPlayed => "Most Played",
            Self::ForgottenGems => "Forgotten Gems",
            Self::HiResCollection => "Hi-Res Collection",
            Self::Favorites => "Favorites",
        }
    }

    /// Descriptive subtitle matching Android.
    #[must_use]
    pub const fn subtitle(self) -> &'static str {
        match self {
            Self::RecentlyPlayed => "Tracks you spun most recently",
            Self::MostPlayed => "Your top local favorites by play count",
            Self::ForgottenGems => "Never played or untouched for 30+ days",
            Self::HiResCollection => "24-bit+ or >48 kHz lossless masters",
            Self::Favorites => "Hearted tracks in your library",
        }
    }
}

/// Summary card payload for a Smart Playlist.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SmartPlaylistSummary {
    /// Smart playlist kind.
    pub kind: SmartPlaylistType,
    /// Display title.
    pub title: &'static str,
    /// Subtitle description.
    pub subtitle: &'static str,
    /// Matching track count.
    pub track_count: usize,
}

/// Aggregated listening metrics for a single artist.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ArtistPlayStat {
    /// Artist display name.
    pub artist_name: String,
    /// Total play count across all tracks by this artist.
    pub play_count: u64,
    /// Total local listening duration in milliseconds.
    pub total_listening_ms: u64,
    /// Representative artwork path/URI.
    pub artwork_uri: Option<String>,
}

/// Aggregated offline Listening Stats summary.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct ListeningStats {
    /// Sum of completed track plays.
    pub total_plays: u64,
    /// Cumulative local listening duration in milliseconds.
    pub total_listening_ms: u64,
    /// Number of distinct tracks played at least once.
    pub unique_tracks_played: usize,
    /// Total plays on Hi-Res lossless tracks.
    pub hi_res_plays: u64,
    /// Top artists sorted by play count then listening duration.
    pub top_artists: Vec<ArtistPlayStat>,
    /// Top played tracks.
    pub top_tracks: Vec<Track>,
}

/// Returns up to `limit` tracks played most recently (`last_played_at_ms > 0`).
#[must_use]
pub fn recently_played(tracks: &[Track], limit: usize) -> Vec<Track> {
    let mut filtered: Vec<Track> = tracks
        .iter()
        .filter(|t| t.last_played_at_ms > 0)
        .cloned()
        .collect();
    filtered.sort_by_key(|a| std::cmp::Reverse(a.last_played_at_ms));
    filtered.truncate(limit);
    filtered
}

/// Returns up to `limit` tracks with the highest play count.
#[must_use]
pub fn most_played(tracks: &[Track], limit: usize) -> Vec<Track> {
    let mut filtered: Vec<Track> = tracks
        .iter()
        .filter(|t| t.play_count > 0)
        .cloned()
        .collect();
    filtered.sort_by(|a, b| {
        b.play_count
            .cmp(&a.play_count)
            .then_with(|| b.last_played_at_ms.cmp(&a.last_played_at_ms))
    });
    filtered.truncate(limit);
    filtered
}

/// Returns Hi-Res-prioritized tracks never played or untouched for at least `stale_threshold_days`.
#[must_use]
pub fn forgotten_gems(
    tracks: &[Track],
    now_epoch_ms: i64,
    stale_threshold_days: i64,
    limit: usize,
) -> Vec<Track> {
    let cutoff_ms =
        now_epoch_ms.saturating_sub(stale_threshold_days.saturating_mul(MILLIS_PER_DAY));
    let mut filtered: Vec<Track> = tracks
        .iter()
        .filter(|t| t.play_count == 0 || t.last_played_at_ms < cutoff_ms)
        .cloned()
        .collect();
    filtered.sort_by(|a, b| {
        b.audio_spec
            .is_hi_res()
            .cmp(&a.audio_spec.is_hi_res())
            .then_with(|| a.play_count.cmp(&b.play_count))
            .then_with(|| a.date_added_seconds.cmp(&b.date_added_seconds))
    });
    filtered.truncate(limit);
    filtered
}

/// Returns all Hi-Res lossless tracks sorted by sample rate, bit depth, and title.
#[must_use]
pub fn hi_res_collection(tracks: &[Track]) -> Vec<Track> {
    let mut filtered: Vec<Track> = tracks
        .iter()
        .filter(|t| t.audio_spec.is_hi_res())
        .cloned()
        .collect();
    filtered.sort_by(|a, b| {
        b.audio_spec
            .sample_rate_hz
            .cmp(&a.audio_spec.sample_rate_hz)
            .then_with(|| b.audio_spec.bit_depth.cmp(&a.audio_spec.bit_depth))
            .then_with(|| a.title.to_lowercase().cmp(&b.title.to_lowercase()))
    });
    filtered
}

/// Returns all favorited tracks sorted alphabetically by title.
#[must_use]
pub fn favorites(tracks: &[Track]) -> Vec<Track> {
    let mut filtered: Vec<Track> = tracks.iter().filter(|t| t.is_favorite).cloned().collect();
    filtered.sort_by_key(|a| a.title.to_lowercase());
    filtered
}

/// Resolves the track list for a given [`SmartPlaylistType`] using default limits.
#[must_use]
pub fn tracks_for_smart_playlist(
    kind: SmartPlaylistType,
    tracks: &[Track],
    now_epoch_ms: i64,
) -> Vec<Track> {
    match kind {
        SmartPlaylistType::RecentlyPlayed => recently_played(tracks, DEFAULT_LIMIT),
        SmartPlaylistType::MostPlayed => most_played(tracks, DEFAULT_LIMIT),
        SmartPlaylistType::ForgottenGems => {
            forgotten_gems(tracks, now_epoch_ms, DEFAULT_STALE_DAYS, DEFAULT_LIMIT)
        }
        SmartPlaylistType::HiResCollection => hi_res_collection(tracks),
        SmartPlaylistType::Favorites => favorites(tracks),
    }
}

/// Computes summary metadata for all 5 Smart Playlists.
#[must_use]
pub fn summarize_smart_playlists(tracks: &[Track], now_epoch_ms: i64) -> Vec<SmartPlaylistSummary> {
    SmartPlaylistType::ALL
        .iter()
        .map(|&kind| {
            let count = tracks_for_smart_playlist(kind, tracks, now_epoch_ms).len();
            SmartPlaylistSummary {
                kind,
                title: kind.title(),
                subtitle: kind.subtitle(),
                track_count: count,
            }
        })
        .collect()
}

/// Computes aggregate local Listening Stats across the library.
#[must_use]
pub fn compute_listening_stats(tracks: &[Track], top_limit: usize) -> ListeningStats {
    let played_tracks: Vec<Track> = tracks
        .iter()
        .filter(|t| t.play_count > 0)
        .cloned()
        .collect();
    if played_tracks.is_empty() {
        return ListeningStats::default();
    }

    let mut total_plays: u64 = 0;
    let mut total_listening_ms: u64 = 0;
    let mut hi_res_plays: u64 = 0;
    let mut artist_acc: HashMap<String, (u64, u64, Option<String>)> = HashMap::new();

    for track in &played_tracks {
        let plays = u64::from(track.play_count);
        let listen_ms = if track.total_play_duration_ms > 0 {
            track.total_play_duration_ms
        } else {
            plays.saturating_mul(track.duration_ms)
        };

        total_plays = total_plays.saturating_add(plays);
        total_listening_ms = total_listening_ms.saturating_add(listen_ms);
        if track.audio_spec.is_hi_res() {
            hi_res_plays = hi_res_plays.saturating_add(plays);
        }

        let artist_key = if !track.album_artist.trim().is_empty() {
            track.album_artist.clone()
        } else if !track.artist.trim().is_empty() {
            track.artist.clone()
        } else {
            "Unknown Artist".to_owned()
        };

        let entry = artist_acc.entry(artist_key).or_insert((0, 0, None));
        entry.0 = entry.0.saturating_add(plays);
        entry.1 = entry.1.saturating_add(listen_ms);
        if entry.2.is_none() {
            entry.2.clone_from(&track.artwork_uri);
        }
    }

    let mut top_artists: Vec<ArtistPlayStat> = artist_acc
        .into_iter()
        .map(
            |(artist_name, (play_count, total_listening_ms, artwork_uri))| ArtistPlayStat {
                artist_name,
                play_count,
                total_listening_ms,
                artwork_uri,
            },
        )
        .collect();

    top_artists.sort_by(|a, b| {
        b.play_count
            .cmp(&a.play_count)
            .then_with(|| b.total_listening_ms.cmp(&a.total_listening_ms))
    });
    top_artists.truncate(top_limit);

    let top_tracks = most_played(&played_tracks, top_limit);

    ListeningStats {
        total_plays,
        total_listening_ms,
        unique_tracks_played: played_tracks.len(),
        hi_res_plays,
        top_artists,
        top_tracks,
    }
}
