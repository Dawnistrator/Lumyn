//! Parallel incremental music library scanner, BLAKE3 artwork cache, HCT color extractor,
//! local lyrics loader, `.cue` virtual track indexer, and debounced live folder watcher.

#![forbid(unsafe_code)]

mod artwork;
mod error;
mod lyrics;
mod metadata;
mod scanner;
#[cfg(test)]
mod tests;
mod watcher;

pub use artwork::{
    CachedArtwork, LUNARIA_BRAND_SEED_ARGB, MAX_ARTWORK_CACHE_DIMENSION, cache_artwork_bytes,
    extract_seed_color_from_dynamic_image, extract_seed_color_from_image_bytes,
    find_folder_cover_bytes,
};
pub use error::ScannerError;
pub use lyrics::{MAX_LYRICS_BYTES, load_local_lyrics};
pub use metadata::{
    SUPPORTED_AUDIO_EXTENSIONS, deterministic_album_id, deterministic_track_id,
    is_supported_audio_file, normalize_display_path, normalize_path_key, scan_single_audio_file,
};
pub use scanner::{ScanConfig, ScanReport, apply_watch_paths, scan_library};
pub use watcher::{DEFAULT_WATCH_DEBOUNCE, LibraryWatcher, WatchBatch};
