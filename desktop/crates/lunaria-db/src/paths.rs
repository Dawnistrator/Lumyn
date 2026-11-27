//! Local storage path resolution supporting Standard AppData and Portable Mode (`portable.flag`).

use std::path::{Path, PathBuf};

use directories::{BaseDirs, UserDirs};

use crate::DbError;

const PORTABLE_FLAG_FILE: &str = "portable.flag";

/// Resolved filesystem locations for SQLite database, artwork cache, and local logs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AppStoragePaths {
    /// `true` when `portable.flag` sits beside the executable.
    pub is_portable: bool,
    /// Root data directory (containing `lunaria.db`).
    pub data_dir: PathBuf,
    /// Full path to `lunaria.db`.
    pub db_path: PathBuf,
    /// Content-addressed BLAKE3 artwork cache directory.
    pub artwork_cache_dir: PathBuf,
    /// Local diagnostics log directory.
    pub logs_dir: PathBuf,
}

impl AppStoragePaths {
    /// Ensures all required storage directories exist on disk without writing registry keys.
    pub fn ensure_dirs(&self) -> Result<(), DbError> {
        std::fs::create_dir_all(&self.data_dir)?;
        std::fs::create_dir_all(&self.artwork_cache_dir)?;
        std::fs::create_dir_all(&self.logs_dir)?;
        Ok(())
    }
}

/// Resolves storage paths checking `portable.flag` beside `exe_dir_override` (or current exe).
///
/// - **Portable Mode** (`portable.flag` present): stores everything under `<exe_dir>/data/`.
/// - **Standard Mode**: stores DB under `%APPDATA%\Lunaria` and cache/logs under `%LOCALAPPDATA%\Lunaria`.
#[must_use]
pub fn resolve_storage_paths(exe_dir_override: Option<&Path>) -> AppStoragePaths {
    let exe_dir = exe_dir_override.map(Path::to_path_buf).or_else(|| {
        std::env::current_exe()
            .ok()
            .and_then(|p| p.parent().map(Path::to_path_buf))
    });

    if let Some(dir) = &exe_dir
        && dir.join(PORTABLE_FLAG_FILE).is_file()
    {
        let root = dir.join("data");
        return AppStoragePaths {
            is_portable: true,
            db_path: root.join("lunaria.db"),
            artwork_cache_dir: root.join("artwork-cache"),
            logs_dir: root.join("logs"),
            data_dir: root,
        };
    }

    if let Some(base) = BaseDirs::new() {
        let data_dir = base.config_dir().join("Lunaria");
        let cache_root = base.cache_dir().join("Lunaria");
        return AppStoragePaths {
            is_portable: false,
            db_path: data_dir.join("lunaria.db"),
            artwork_cache_dir: cache_root.join("artwork-cache"),
            logs_dir: cache_root.join("logs"),
            data_dir,
        };
    }

    let fallback_root = exe_dir.unwrap_or_else(|| PathBuf::from(".")).join("data");
    AppStoragePaths {
        is_portable: false,
        db_path: fallback_root.join("lunaria.db"),
        artwork_cache_dir: fallback_root.join("artwork-cache"),
        logs_dir: fallback_root.join("logs"),
        data_dir: fallback_root,
    }
}

/// Returns the user's default Music folder (`%USERPROFILE%\Music` on Windows), if present.
#[must_use]
pub fn default_music_folder() -> Option<PathBuf> {
    UserDirs::new().and_then(|u| u.audio_dir().map(Path::to_path_buf))
}
