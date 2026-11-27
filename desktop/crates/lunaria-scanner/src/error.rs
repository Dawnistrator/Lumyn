//! Error types produced by the parallel library scanner, artwork cache, and folder watcher.

use thiserror::Error;

/// Unified error type returned by `lunaria-scanner` operations.
#[derive(Debug, Error)]
pub enum ScannerError {
    /// Underlying filesystem I/O failure.
    #[error("filesystem I/O error: {0}")]
    Io(#[from] std::io::Error),

    /// SQLite repository failure during incremental scan or deletion.
    #[error("database error: {0}")]
    Db(#[from] lunaria_db::DbError),

    /// Metadata probe or tag parsing error from Lofty.
    #[error("metadata tag read error: {0}")]
    Tag(#[from] lofty::error::FileParseError),

    /// Album cover image decode or encode failure.
    #[error("artwork decode/encode error: {0}")]
    Image(#[from] image::ImageError),

    /// OS folder watch backend initialization or path registration error.
    #[error("folder watcher error: {0}")]
    Watch(#[from] notify::Error),

    /// Invalid or malformed input path / cue sheet reference.
    #[error("invalid scanner input: {0}")]
    InvalidInput(String),
}
