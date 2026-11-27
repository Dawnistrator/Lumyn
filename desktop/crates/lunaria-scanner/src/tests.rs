//! Integration tests for parallel scanning, artwork BLAKE3 dedup + HCT seed extraction,
//! `.cue` virtual track generation, folder filtering, `.lrc`/embedded lyrics, and folder watcher.

use std::error::Error;
use std::fs;
use std::io::Cursor;
use std::path::Path;
use std::time::{Duration, Instant};

use image::{DynamicImage, ImageBuffer, ImageFormat, Rgb};
use lunaria_db::LibraryDb;
use lunaria_model::{FolderFilterConfig, TrackSortOrder};
use tempfile::tempdir;

use super::*;

type TestResult<T = ()> = Result<T, Box<dyn Error>>;

/// Synthesizes a valid minimal PCM 16-bit 44.1 kHz stereo WAV file on disk.
fn write_minimal_wav(path: &Path, duration_ms: u32) -> TestResult {
    let sample_rate = 44_100_u32;
    let channels = 2_u16;
    let bits_per_sample = 16_u16;
    let num_samples = (sample_rate * duration_ms / 1000) * u32::from(channels);
    let data_bytes = num_samples * 2;
    let riff_size = 36 + data_bytes;

    let mut buf = Vec::with_capacity((44 + data_bytes) as usize);
    buf.extend_from_slice(b"RIFF");
    buf.extend_from_slice(&riff_size.to_le_bytes());
    buf.extend_from_slice(b"WAVE");
    buf.extend_from_slice(b"fmt ");
    buf.extend_from_slice(&16_u32.to_le_bytes());
    buf.extend_from_slice(&1_u16.to_le_bytes());
    buf.extend_from_slice(&channels.to_le_bytes());
    buf.extend_from_slice(&sample_rate.to_le_bytes());
    let byte_rate = sample_rate * u32::from(channels) * u32::from(bits_per_sample) / 8;
    buf.extend_from_slice(&byte_rate.to_le_bytes());
    let block_align = channels * (bits_per_sample / 8);
    buf.extend_from_slice(&block_align.to_le_bytes());
    buf.extend_from_slice(&bits_per_sample.to_le_bytes());
    buf.extend_from_slice(b"data");
    buf.extend_from_slice(&data_bytes.to_le_bytes());
    buf.resize(44 + data_bytes as usize, 0);

    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(path, buf)?;
    Ok(())
}

fn encode_solid_png(r: u8, g: u8, b: u8, width: u32, height: u32) -> TestResult<Vec<u8>> {
    let img = ImageBuffer::from_pixel(width, height, Rgb([r, g, b]));
    let mut bytes = Vec::new();
    DynamicImage::ImageRgb8(img).write_to(&mut Cursor::new(&mut bytes), ImageFormat::Png)?;
    Ok(bytes)
}

#[test]
fn artwork_cache_deduplicates_blake3_and_applies_hct_brand_fallback_on_gray() -> TestResult {
    let dir = tempdir()?;
    let cache_dir = dir.path().join("art_cache");

    let crimson_png = encode_solid_png(220, 32, 48, 64, 64)?;
    let first = cache_artwork_bytes(&cache_dir, &crimson_png)?;
    let second = cache_artwork_bytes(&cache_dir, &crimson_png)?;

    assert_eq!(first.hash, second.hash);
    assert_eq!(first.file_path, second.file_path);
    assert!(first.file_path.is_file());
    assert_ne!(first.seed_color_argb, LUNARIA_BRAND_SEED_ARGB);

    // Monochrome grey artwork must trigger Material 3 fallback to Lunaria brand teal seed.
    let grey_png = encode_solid_png(128, 128, 128, 64, 64)?;
    let grey_seed = extract_seed_color_from_image_bytes(&grey_png);
    assert_eq!(grey_seed, LUNARIA_BRAND_SEED_ARGB);

    Ok(())
}

#[test]
fn incremental_parallel_scan_handles_cue_filters_skip_and_deletion() -> TestResult {
    let dir = tempdir()?;
    let root = dir.path().join("music");
    let album_dir = root.join("Symphony");
    let excluded_dir = root.join("Podcasts");
    let art_cache = dir.path().join("art");

    let wav_a = album_dir.join("concert.wav");
    let wav_b = album_dir.join("encore.wav");
    let wav_excluded = excluded_dir.join("talk.wav");
    write_minimal_wav(&wav_a, 4_000)?;
    write_minimal_wav(&wav_b, 2_000)?;
    write_minimal_wav(&wav_excluded, 1_500)?;

    // Place folder artwork + CUE sheet for concert.wav
    let cover_png = encode_solid_png(20, 140, 210, 32, 32)?;
    fs::write(album_dir.join("cover.png"), cover_png)?;
    let cue_content = "\
PERFORMER \"Berlin Phil\"\r\n\
TITLE \"Live Symphony\"\r\n\
FILE \"concert.wav\" WAVE\r\n\
  TRACK 01 AUDIO\r\n\
    TITLE \"Movement I\"\r\n\
    INDEX 01 00:00:00\r\n\
  TRACK 02 AUDIO\r\n\
    TITLE \"Movement II\"\r\n\
    INDEX 01 00:02:00\r\n";
    fs::write(album_dir.join("concert.cue"), cue_content)?;

    let mut filter = FolderFilterConfig::default();
    filter
        .excluded_folders
        .push(normalize_display_path(&excluded_dir));

    let config = ScanConfig {
        root_folders: vec![root],
        filter,
        artwork_cache_dir: Some(art_cache),
        parse_cue_sheets: true,
    };

    let mut db = LibraryDb::open_in_memory()?;
    let r1 = scan_library(&mut db, &config)?;
    assert_eq!(r1.discovered_files, 2);
    assert_eq!(r1.scanned_tracks, 2);
    assert_eq!(r1.skipped_unchanged, 0);
    assert_eq!(r1.cue_tracks_indexed, 2);
    assert_eq!(r1.removed_tracks, 0);
    assert_eq!(db.track_count()?, 4);

    // Second pass immediately after must short-circuit all unchanged audio files.
    let r2 = scan_library(&mut db, &config)?;
    assert_eq!(r2.discovered_files, 2);
    assert_eq!(r2.scanned_tracks, 0);
    assert_eq!(r2.skipped_unchanged, 2);
    assert_eq!(r2.cue_tracks_indexed, 2);

    // Deleting encore.wav prunes it cleanly from SQLite on the next scan.
    fs::remove_file(&wav_b)?;
    let r3 = scan_library(&mut db, &config)?;
    assert_eq!(r3.discovered_files, 1);
    assert_eq!(r3.removed_tracks, 1);
    assert_eq!(db.list_tracks(TrackSortOrder::TitleAsc)?.len(), 3);

    Ok(())
}

#[test]
fn local_lyrics_loader_reads_sibling_lrc() -> TestResult {
    let dir = tempdir()?;
    let wav = dir.path().join("ballad.wav");
    write_minimal_wav(&wav, 1_000)?;

    fs::write(
        dir.path().join("ballad.lrc"),
        "[00:01.50]First line\n[00:05.00]Second line\n",
    )?;

    let loaded = load_local_lyrics(&wav).ok_or("expected sibling LRC lyrics")?;
    assert!(loaded.is_synced);
    assert_eq!(loaded.lines.len(), 2);
    assert_eq!(loaded.lines[0].text, "First line");

    Ok(())
}

#[test]
fn debounced_folder_watcher_emits_created_file_batch() -> TestResult {
    let dir = tempdir()?;
    let mut watcher = LibraryWatcher::new(Duration::from_millis(80))?;
    watcher.watch_folder(dir.path())?;

    let created = dir.path().join("fresh_track.wav");
    write_minimal_wav(&created, 500)?;

    let deadline = Instant::now() + Duration::from_secs(4);
    let mut saw_file = false;
    while Instant::now() < deadline {
        if let Ok(batch) = watcher.receiver().recv_timeout(Duration::from_millis(250))
            && batch
                .upsert_paths
                .iter()
                .any(|p| p.file_name() == created.file_name())
        {
            saw_file = true;
            break;
        }
    }
    assert!(saw_file, "expected debounced watch batch for created file");

    Ok(())
}

#[test]
fn whitelist_scan_traverses_ancestor_root_to_included_subfolder() -> TestResult {
    let dir = tempdir()?;
    let root = dir.path().join("library");
    let flac_dir = root.join("FLAC");
    let mp3_dir = root.join("MP3");
    let flac_wav = flac_dir.join("master.wav");
    let mp3_wav = mp3_dir.join("lossy.wav");
    write_minimal_wav(&flac_wav, 600)?;
    write_minimal_wav(&mp3_wav, 600)?;

    let filter = FolderFilterConfig {
        mode: lunaria_model::FolderFilterMode::Whitelist,
        included_folders: vec![normalize_display_path(&flac_dir)],
        excluded_folders: Vec::new(),
    };
    let config = ScanConfig {
        root_folders: vec![root],
        filter,
        artwork_cache_dir: None,
        parse_cue_sheets: false,
    };
    let mut db = LibraryDb::open_in_memory()?;
    let report = scan_library(&mut db, &config)?;
    assert_eq!(report.discovered_files, 1);
    let tracks = db.list_tracks(TrackSortOrder::TitleAsc)?;
    assert_eq!(tracks.len(), 1);
    assert_eq!(tracks[0].file_name, "master.wav");
    Ok(())
}

#[test]
fn scan_populates_bitrate_and_rescans_legacy_zero_bitrate_rows() -> TestResult {
    let dir = tempdir()?;
    let root = dir.path().join("music");
    let wav = root.join("bitrate_check.wav");
    write_minimal_wav(&wav, 2_000)?;

    let mut scanned = scan_single_audio_file(&wav, None, None)?;
    assert!(
        scanned.audio_spec.bitrate_kbps > 0,
        "expected positive bitrate for synthesized wav, got {}",
        scanned.audio_spec.bitrate_kbps
    );

    let mut db = LibraryDb::open_in_memory()?;
    scanned.audio_spec.bitrate_kbps = 0;
    assert_eq!(db.upsert_tracks_batch(&[scanned])?, 1);

    let config = ScanConfig {
        root_folders: vec![root],
        filter: FolderFilterConfig::default(),
        artwork_cache_dir: None,
        parse_cue_sheets: false,
    };
    let report = scan_library(&mut db, &config)?;
    assert_eq!(report.scanned_tracks, 1);
    assert_eq!(report.skipped_unchanged, 0);

    let tracks = db.list_tracks(TrackSortOrder::TitleAsc)?;
    assert_eq!(tracks.len(), 1);
    assert!(tracks[0].audio_spec.bitrate_kbps > 0);

    Ok(())
}
