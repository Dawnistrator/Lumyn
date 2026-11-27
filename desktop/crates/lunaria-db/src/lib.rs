//! Local SQLite WAL + FTS5 persistence layer for Lunaria Windows.

#![forbid(unsafe_code)]

pub mod error;
pub mod library_db;
pub mod paths;
pub mod schema;

pub use error::DbError;
pub use library_db::{
    LibraryDb, TrackFingerprint, TrackMetadataUpdate, build_fts5_prefix_query,
    normalize_path_key_from_path,
};
pub use paths::{AppStoragePaths, default_music_folder, resolve_storage_paths};

#[cfg(test)]
mod tests;
