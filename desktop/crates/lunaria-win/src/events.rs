//! Cross-platform event commands and metadata payloads exchanged with Windows shell controls.

use std::path::{Path, PathBuf};

/// Supported audio extensions accepted by Explorer double-click open and drag-and-drop.
const SUPPORTED_EXTENSIONS: &[&str] = &[
    "flac", "wav", "alac", "mp3", "m4a", "aac", "ogg", "aiff", "aif", "cue", "m3u", "m3u8",
];

/// Command emitted by SMTC hardware/media keys, System Tray menu, Taskbar toolbar,
/// Single-Instance IPC forwarder, or Drag-and-Drop handler toward the main UI controller.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ShellCommand {
    /// Resume or start playback.
    Play,
    /// Pause active playback.
    Pause,
    /// Toggle play / pause.
    TogglePlayPause,
    /// Stop playback cleanly.
    Stop,
    /// Skip to the next track in queue.
    Next,
    /// Step back or restart current track.
    Previous,
    /// Toggle window show / hide (left-click tray icon or tray menu item).
    ToggleWindow,
    /// Bring the main window to the foreground (second-instance launch activation).
    ShowWindow,
    /// Gracefully exit Lunaria from tray menu.
    QuitApp,
    /// Taskbar Explorer recreated (`TaskbarButtonCreated`), re-registering `ITaskbarList3` buttons.
    TaskbarCreated,
    /// Top-level window maximize/restore state changed (`WM_SIZE`).
    WindowStateChanged {
        /// `true` when the window is maximized, `false` when restored.
        maximized: bool,
    },
    /// Open audio file(s), `.cue` sheet(s), `.m3u8` playlist(s), or music folder(s).
    OpenPaths(Vec<PathBuf>),
}

/// Snapshot published by `lunaria-desktop` to refresh SMTC display metadata, Taskbar progress,
/// and tray tooltip without polling.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct MediaMetadataSnapshot {
    /// Track display title.
    pub title: String,
    /// Performing artist.
    pub artist: String,
    /// Album title.
    pub album: String,
    /// Optional local cached cover JPEG path.
    pub artwork_path: Option<PathBuf>,
    /// Whether playback is currently active (`Playing` vs `Paused`/`Stopped`).
    pub is_playing: bool,
    /// Whether any track is loaded in the transport.
    pub has_track: bool,
    /// Current playback head position in milliseconds.
    pub position_ms: u64,
    /// Current track duration in milliseconds.
    pub duration_ms: u64,
}

/// Returns `true` when `path` is a folder, supported local audio file, `.cue`, or `.m3u`/`.m3u8`.
#[must_use]
pub fn is_supported_drop_or_open_path(path: &Path) -> bool {
    if path.is_dir() {
        return true;
    }
    let Some(ext) = path.extension().and_then(|e| e.to_str()) else {
        return false;
    };
    SUPPORTED_EXTENSIONS
        .iter()
        .any(|candidate| ext.eq_ignore_ascii_case(candidate))
}
