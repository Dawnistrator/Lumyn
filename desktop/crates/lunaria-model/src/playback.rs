//! Playback transport state, queue persistence, user preferences, and search filtering.

use serde::{Deserialize, Serialize};

use crate::{
    AudioOutputMode, AudioSignalPath, EqualizerSettings, FolderFilterConfig, Track, TrackId,
};

/// Maximum number of queued track IDs persisted across app restarts.
pub const MAX_PERSISTED_QUEUE_SIZE: usize = 2_000;

/// Application theme preference.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default, Serialize, Deserialize)]
pub enum ThemeMode {
    /// Follow the Windows system dark/light preference.
    #[default]
    System,
    /// Always use the light studio slate theme.
    Light,
    /// Always use the dark obsidian teal theme.
    Dark,
}

/// Queue repeat behavior.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default, Serialize, Deserialize)]
pub enum RepeatMode {
    /// Stop when queue reaches the end.
    #[default]
    Off,
    /// Loop entire queue.
    All,
    /// Loop active track continuously.
    One,
}

impl RepeatMode {
    /// Cycles to the next repeat mode (`Off -> All -> One -> Off`).
    #[must_use]
    pub const fn next(self) -> Self {
        match self {
            Self::Off => Self::All,
            Self::All => Self::One,
            Self::One => Self::Off,
        }
    }
}

/// Sleep timer state controlling automatic playback pause.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default, Serialize, Deserialize)]
pub enum SleepTimerState {
    /// Timer disabled.
    #[default]
    Off,
    /// Pause automatically when the active track finishes.
    EndOfTrack,
    /// Pause after `remaining_ms` milliseconds have elapsed.
    Countdown {
        /// Remaining countdown duration in milliseconds.
        remaining_ms: u64,
    },
}

/// Clamps playback speed into Lunaria's supported range `[0.5, 2.0]`.
#[must_use]
pub const fn clamp_playback_speed(speed: f32) -> f32 {
    if !speed.is_finite() {
        return 1.0;
    }
    speed.clamp(0.5, 2.0)
}

/// Live UI-facing transport and signal-path snapshot.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PlaybackState {
    /// Currently loaded track, if any.
    pub current_track: Option<Track>,
    /// Active playback queue.
    pub queue: Vec<Track>,
    /// Index of `current_track` inside `queue` (`None` when empty).
    pub current_index: Option<usize>,
    /// Whether audio is actively playing.
    pub is_playing: bool,
    /// Current playback head position in milliseconds.
    pub position_ms: u64,
    /// Total duration of current track in milliseconds.
    pub duration_ms: u64,
    /// Active repeat mode.
    pub repeat_mode: RepeatMode,
    /// Whether shuffle order is active.
    pub shuffle_enabled: bool,
    /// Playback speed multiplier (`0.5..=2.0`).
    pub speed: f32,
    /// Active sleep timer mode.
    pub sleep_timer: SleepTimerState,
    /// Non-fatal playback error banner message if any.
    pub error_message: Option<String>,
    /// Honest real-time Audio Signal Path telemetry.
    pub signal_path: Option<AudioSignalPath>,
}

impl Default for PlaybackState {
    fn default() -> Self {
        Self {
            current_track: None,
            queue: Vec::new(),
            current_index: None,
            is_playing: false,
            position_ms: 0,
            duration_ms: 0,
            repeat_mode: RepeatMode::Off,
            shuffle_enabled: false,
            speed: 1.0,
            sleep_timer: SleepTimerState::Off,
            error_message: None,
            signal_path: None,
        }
    }
}

impl PlaybackState {
    /// Normalized playback progress in `[0.0, 1.0]`, safe against zero duration.
    #[must_use]
    pub fn progress(&self) -> f32 {
        if self.duration_ms > 0 {
            ((self.position_ms as f64) / (self.duration_ms as f64)).clamp(0.0, 1.0) as f32
        } else {
            0.0
        }
    }

    /// Returns `true` when skipping forward will select another track.
    #[must_use]
    pub const fn has_next(&self) -> bool {
        if self.queue.is_empty() {
            return false;
        }
        if matches!(self.repeat_mode, RepeatMode::All) {
            return true;
        }
        match self.current_index {
            Some(idx) => idx + 1 < self.queue.len(),
            None => false,
        }
    }

    /// Returns `true` when skipping backward will restart or move to an earlier track.
    #[must_use]
    pub const fn has_previous(&self) -> bool {
        if self.queue.is_empty() {
            return false;
        }
        if matches!(self.repeat_mode, RepeatMode::All) || self.position_ms > 0 {
            return true;
        }
        matches!(self.current_index, Some(idx) if idx > 0)
    }
}

/// Persisted queue and transport state restored cleanly on startup.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PersistedQueueState {
    /// Ordered track IDs (truncated at [`MAX_PERSISTED_QUEUE_SIZE`]).
    pub track_ids: Vec<TrackId>,
    /// Active queue index.
    pub current_index: usize,
    /// Saved track position in milliseconds.
    pub position_ms: u64,
    /// Saved repeat mode.
    pub repeat_mode: RepeatMode,
    /// Saved shuffle state.
    pub shuffle_enabled: bool,
    /// Saved playback speed.
    pub speed: f32,
}

impl Default for PersistedQueueState {
    fn default() -> Self {
        Self {
            track_ids: Vec::new(),
            current_index: 0,
            position_ms: 0,
            repeat_mode: RepeatMode::Off,
            shuffle_enabled: false,
            speed: 1.0,
        }
    }
}

const fn default_true() -> bool {
    true
}

/// Persisted user settings shared across Windows sessions.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct UserPreferences {
    /// Dark/Light/System theme selection.
    pub theme_mode: ThemeMode,
    /// Extract Material 3 palette from active album artwork.
    pub use_album_art_theme: bool,
    /// Selected Windows audio pipeline mode.
    pub audio_output_mode: AudioOutputMode,
    /// Preferred WASAPI endpoint ID (`None` = follow Windows default).
    pub preferred_device_id: Option<String>,
    /// Force Slint CPU software renderer (0 VRAM) on next launch.
    pub use_software_renderer: bool,
    /// Minimize window to system tray instead of exiting.
    pub minimize_to_tray: bool,
    /// Enable local Windows Named Pipe Discord Rich Presence (`\\.\pipe\discord-ipc-0..9`).
    #[serde(default = "default_true")]
    pub discord_rpc_enabled: bool,
    /// Optional Discord Application Client ID override (defaults to built-in Lunaria App ID or `LUNARIA_DISCORD_CLIENT_ID`).
    #[serde(default)]
    pub discord_client_id: String,
    /// Folder whitelist/blacklist configuration.
    pub folder_filter: FolderFilterConfig,
    /// Equalizer, ReplayGain, stereo, and crossfade settings.
    pub equalizer: EqualizerSettings,
}

impl Default for UserPreferences {
    fn default() -> Self {
        Self {
            theme_mode: ThemeMode::System,
            use_album_art_theme: true,
            audio_output_mode: AudioOutputMode::DspFloat32,
            preferred_device_id: None,
            use_software_renderer: false,
            minimize_to_tray: true,
            discord_rpc_enabled: true,
            discord_client_id: String::new(),
            folder_filter: FolderFilterConfig::default(),
            equalizer: EqualizerSettings::default(),
        }
    }
}

/// Quick search filter chips matching Android `SearchFilterChip`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum SearchFilterChip {
    /// Match lossless Hi-Res masters (`>= 24-bit` or `> 48 kHz`).
    HiRes,
    /// Match any lossless codec.
    Lossless,
    /// Match FLAC tracks specifically.
    Flac,
    /// Match favorited tracks.
    Favorites,
}

/// Offline library search query and active filter chips.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SearchCriteria {
    /// Free-text query matched case-insensitively across track fields.
    pub query: String,
    /// Required filter chips.
    pub filters: Vec<SearchFilterChip>,
}

fn contains_ignore_case(haystack: &str, needle_lower: &str) -> bool {
    if haystack.is_ascii() && needle_lower.is_ascii() {
        let h = haystack.as_bytes();
        let n = needle_lower.as_bytes();
        if n.is_empty() {
            return true;
        }
        return h.windows(n.len()).any(|w| w.eq_ignore_ascii_case(n));
    }
    haystack.to_lowercase().contains(needle_lower)
}

/// Evaluates whether `track` matches all filter chips and text query in `criteria`.
#[must_use]
pub fn track_matches_search(track: &Track, criteria: &SearchCriteria) -> bool {
    for &chip in &criteria.filters {
        let ok = match chip {
            SearchFilterChip::HiRes => track.audio_spec.is_hi_res(),
            SearchFilterChip::Lossless => track.audio_spec.is_lossless,
            SearchFilterChip::Flac => track.audio_spec.codec.eq_ignore_ascii_case("FLAC"),
            SearchFilterChip::Favorites => track.is_favorite,
        };
        if !ok {
            return false;
        }
    }

    let needle = criteria.query.trim();
    if needle.is_empty() {
        return true;
    }
    let needle_lower = needle.to_lowercase();

    contains_ignore_case(&track.title, &needle_lower)
        || contains_ignore_case(&track.artist, &needle_lower)
        || contains_ignore_case(&track.album, &needle_lower)
        || contains_ignore_case(&track.album_artist, &needle_lower)
        || contains_ignore_case(&track.folder_path, &needle_lower)
        || contains_ignore_case(&track.file_name, &needle_lower)
}
