//! Parallel incremental folder scanner, `.cue` virtual track indexer, and DB synchronization.

use std::collections::{HashMap, HashSet};
use std::fs;
use std::path::{Path, PathBuf};
use std::time::UNIX_EPOCH;

use lunaria_db::{LibraryDb, TrackFingerprint};
use lunaria_model::{FolderFilterConfig, Track, TrackId, build_virtual_tracks, parse_cue_sheet};
use rayon::prelude::*;
use walkdir::{DirEntry, WalkDir};

use crate::ScannerError;
use crate::metadata::{
    deterministic_track_id, is_supported_audio_file, normalize_display_path, normalize_path_key,
    scan_single_audio_file,
};

/// Configuration driving full incremental or root-folder scans.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct ScanConfig {
    /// Watched root folders to traverse recursively.
    pub root_folders: Vec<PathBuf>,
    /// Allowed / excluded folder filtering rules.
    pub filter: FolderFilterConfig,
    /// Optional cache directory where deduplicated JPEGs and `.seed` files are stored.
    pub artwork_cache_dir: Option<PathBuf>,
    /// Whether to parse `.cue` cue sheets alongside audio files into virtual tracks (`id < 0`).
    pub parse_cue_sheets: bool,
}

/// Summary statistics returned at completion of [`scan_library`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct ScanReport {
    /// Total supported audio files discovered under allowed folders.
    pub discovered_files: usize,
    /// Files newly parsed or re-parsed because size/mtime changed.
    pub scanned_tracks: usize,
    /// Unchanged files skipped via fingerprint short-circuit.
    pub skipped_unchanged: usize,
    /// Virtual `.cue` sub-tracks indexed (`id < 0`).
    pub cue_tracks_indexed: usize,
    /// Stale regular or `.cue` track rows removed from SQLite.
    pub removed_tracks: usize,
    /// Number of individual corrupted/unreadable files logged and skipped.
    pub failed_files: usize,
}

/// Incrementally scans configured folders in parallel with Rayon, updates SQLite tracks/FTS5,
/// generates `.cue` virtual tracks, and prunes deleted files.
pub fn scan_library(db: &mut LibraryDb, config: &ScanConfig) -> Result<ScanReport, ScannerError> {
    let fingerprints = db.list_file_fingerprints()?;
    let existing_cue_ids: HashSet<TrackId> = db.list_cue_track_ids()?.into_iter().collect();

    let (audio_paths, cue_paths) = discover_files(
        &config.root_folders,
        &config.filter,
        config.parse_cue_sheets,
    );

    let mut seen_regular_ids = HashSet::with_capacity(audio_paths.len());
    let mut to_scan: Vec<(PathBuf, Option<TrackFingerprint>)> = Vec::new();
    let mut unchanged_count = 0usize;

    for path in &audio_paths {
        let key = normalize_path_key(path);
        let fp = fingerprints.get(&key);
        let unchanged = fp.is_some_and(|existing| file_matches_fingerprint(path, existing));

        if let Some(existing) = fp {
            let _ = seen_regular_ids.insert(existing.id);
        } else {
            let _ = seen_regular_ids.insert(deterministic_track_id(&key));
        }

        if unchanged {
            unchanged_count += 1;
        } else {
            to_scan.push((path.clone(), fp.cloned()));
        }
    }

    let artwork_cache_ref = config.artwork_cache_dir.as_deref();
    let scan_results: Vec<Result<Track, (PathBuf, ScannerError)>> = to_scan
        .par_iter()
        .map(|(path, fp)| {
            scan_single_audio_file(path, artwork_cache_ref, fp.as_ref())
                .map_err(|err| (path.clone(), err))
        })
        .collect();

    let mut scanned_tracks = Vec::new();
    let mut failed_files = 0usize;
    let mut freshly_scanned_by_key: HashMap<String, Track> = HashMap::new();

    for res in scan_results {
        match res {
            Ok(track) => {
                let key = normalize_path_key(Path::new(&track.file_path));
                let _ = freshly_scanned_by_key.insert(key, track.clone());
                let _ = seen_regular_ids.insert(track.id);
                scanned_tracks.push(track);
            }
            Err((bad_path, err)) => {
                failed_files += 1;
                let _ = seen_regular_ids
                    .remove(&deterministic_track_id(&normalize_path_key(&bad_path)));
                tracing::warn!(
                    target: "lunaria_scanner",
                    path = %bad_path.display(),
                    error = %err,
                    "skipping unreadable audio file"
                );
            }
        }
    }

    let upserted_regular = db.upsert_tracks_batch(&scanned_tracks)?;

    let mut cue_tracks = Vec::new();
    let mut seen_cue_ids = HashSet::new();

    if config.parse_cue_sheets {
        for cue_path in &cue_paths {
            let Ok(raw_cue) = fs::read_to_string(cue_path) else {
                continue;
            };
            let Some(sheet) = parse_cue_sheet(&raw_cue) else {
                continue;
            };
            let Some(cue_dir) = cue_path.parent() else {
                continue;
            };

            let ref_name = sheet.file_name.trim();
            if ref_name.is_empty() {
                continue;
            }

            let target_path = cue_dir.join(ref_name);
            let target_key = normalize_path_key(&target_path);

            let parent_track = freshly_scanned_by_key
                .get(&target_key)
                .cloned()
                .or_else(|| {
                    fingerprints
                        .get(&target_key)
                        .and_then(|fp| db.get_track(fp.id).ok().flatten())
                });

            let Some(parent) = parent_track else {
                continue;
            };

            let cue_display_key = normalize_path_key(cue_path);
            let virtual_rows = build_virtual_tracks(&sheet, &parent, &cue_display_key);
            for vt in virtual_rows {
                let _ = seen_cue_ids.insert(vt.id);
                cue_tracks.push(vt);
            }
        }
    }

    let cue_tracks_indexed = db.upsert_tracks_batch(&cue_tracks)?;

    let mut stale_ids: Vec<TrackId> = fingerprints
        .values()
        .filter(|fp| !seen_regular_ids.contains(&fp.id))
        .map(|fp| fp.id)
        .collect();

    for old_cue_id in existing_cue_ids {
        if !seen_cue_ids.contains(&old_cue_id) {
            stale_ids.push(old_cue_id);
        }
    }

    let removed_tracks = db.delete_tracks_by_ids(&stale_ids)?;

    Ok(ScanReport {
        discovered_files: audio_paths.len(),
        scanned_tracks: upserted_regular,
        skipped_unchanged: unchanged_count,
        cue_tracks_indexed,
        removed_tracks,
        failed_files,
    })
}

/// Applies an incremental list of modified/created/deleted file paths (such as from [`crate::LibraryWatcher`]).
pub fn apply_watch_paths(
    db: &mut LibraryDb,
    upsert_paths: &[PathBuf],
    remove_paths: &[PathBuf],
    filter: &FolderFilterConfig,
    artwork_cache_dir: Option<&Path>,
) -> Result<(usize, usize), ScannerError> {
    let mut removed = 0usize;
    for path in remove_paths {
        removed += db.delete_tracks_for_file_path(path)?;
    }

    let fingerprints = db.list_file_fingerprints()?;
    let valid_files: Vec<PathBuf> = upsert_paths
        .iter()
        .filter(|p| is_supported_audio_file(p))
        .filter(|p| {
            p.parent()
                .map(normalize_display_path)
                .is_none_or(|folder| filter.is_folder_allowed(&folder))
        })
        .cloned()
        .collect();

    let scanned: Vec<Track> = valid_files
        .par_iter()
        .filter_map(|path| {
            let key = normalize_path_key(path);
            let fp = fingerprints.get(&key);
            scan_single_audio_file(path, artwork_cache_dir, fp).ok()
        })
        .collect();

    let upserted = db.upsert_tracks_batch(&scanned)?;
    Ok((upserted, removed))
}

fn discover_files(
    roots: &[PathBuf],
    filter: &FolderFilterConfig,
    include_cue: bool,
) -> (Vec<PathBuf>, Vec<PathBuf>) {
    let mut audio_files = Vec::new();
    let mut cue_files = Vec::new();
    let mut seen_keys = HashSet::new();

    for root in roots {
        if !root.exists() {
            continue;
        }
        let walker = WalkDir::new(root)
            .follow_links(false)
            .into_iter()
            .filter_entry(|entry| allow_dir_entry(entry, filter));

        for entry_res in walker {
            let Ok(entry) = entry_res else {
                continue;
            };
            if !entry.file_type().is_file() {
                continue;
            }
            let path = entry.path();
            if let Some(parent) = path.parent() {
                let parent_display = normalize_display_path(parent);
                if !filter.is_folder_allowed(&parent_display) {
                    continue;
                }
            }
            let key = normalize_path_key(path);
            if is_supported_audio_file(path) {
                if seen_keys.insert(key) {
                    audio_files.push(path.to_path_buf());
                }
            } else if include_cue
                && path
                    .extension()
                    .and_then(|e| e.to_str())
                    .is_some_and(|e| e.eq_ignore_ascii_case("cue"))
                && seen_keys.insert(key)
            {
                cue_files.push(path.to_path_buf());
            }
        }
    }

    (audio_files, cue_files)
}

fn allow_dir_entry(entry: &DirEntry, filter: &FolderFilterConfig) -> bool {
    if !entry.file_type().is_dir() {
        return true;
    }
    let dir_display = normalize_display_path(entry.path());
    filter.should_descend_directory(&dir_display)
}

fn file_matches_fingerprint(path: &Path, fp: &TrackFingerprint) -> bool {
    let Ok(meta) = fs::metadata(path) else {
        return false;
    };
    let size = meta.len();
    let modified_ns = meta
        .modified()
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .and_then(|d| i64::try_from(d.as_nanos()).ok())
        .unwrap_or(0);

    if size != fp.file_size || modified_ns != fp.modified_ns {
        return false;
    }
    if (fp.bitrate_kbps == 0 && fp.duration_ms > 0 && size > 0)
        || (fp.is_lossless && fp.bit_depth == 0)
    {
        return false;
    }
    if let Some(art_path) = &fp.artwork_uri {
        return Path::new(art_path).is_file();
    }
    true
}
