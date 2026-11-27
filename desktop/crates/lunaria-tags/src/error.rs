//! Error types for atomic audio tag writing and Windows File-Lock Handover.

use thiserror::Error;

/// Unified error type returned by `lunaria-tags` operations.
#[derive(Debug, Error)]
pub enum TagError {
    /// Underlying filesystem copy/rename/metadata error.
    #[error("filesystem I/O error: {0}")]
    Io(#[from] std::io::Error),

    /// Tag parsing error from Lofty.
    #[error("audio tag read error: {0}")]
    LoftyRead(#[from] lofty::error::FileParseError),

    /// Tag encoding error from Lofty.
    #[error("audio tag write error: {0}")]
    LoftyWrite(#[from] lofty::error::FileEncodingError),

    /// Cover artwork image decode or transcode error.
    #[error("cover artwork processing error: {0}")]
    Image(#[from] image::ImageError),

    /// Failed suspending or resuming active playback around a Windows file lock.
    #[error("audio playback handover error: {0}")]
    Audio(#[from] lunaria_audio::AudioError),

    /// Failed fetching or updating SQLite track / FTS5 rows.
    #[error("database update error: {0}")]
    Db(#[from] lunaria_db::DbError),

    /// Artwork cache refresh error.
    #[error("scanner cache error: {0}")]
    Scanner(#[from] lunaria_scanner::ScannerError),

    /// Operation unsupported on this target (such as virtual `.cue` sub-tracks).
    #[error("unsupported tag edit operation: {0}")]
    UnsupportedOperation(String),

    /// Requested track ID does not exist in SQLite.
    #[error("track ID {0} not found in library")]
    TrackNotFound(i64),
}
