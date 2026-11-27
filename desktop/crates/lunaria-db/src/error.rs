//! Errors returned by SQLite database setup, storage path resolution, and queries.

use thiserror::Error;

/// Unified error type returned by [`crate::LibraryDb`] and storage path helpers.
#[derive(Debug, Error)]
pub enum DbError {
    /// SQLite engine error surfaced from `rusqlite`.
    #[error("sqlite database error: {0}")]
    Sqlite(#[from] rusqlite::Error),

    /// Filesystem I/O error creating or inspecting data directories.
    #[error("storage directory I/O error: {0}")]
    Io(#[from] std::io::Error),

    /// JSON serialization/deserialization error for persisted preferences or queue.
    #[error("json serialization error: {0}")]
    Serialization(#[from] serde_json::Error),

    /// Corrupted or invalid parameter/row state encountered in storage.
    #[error("invalid database state: {0}")]
    InvalidData(String),
}
