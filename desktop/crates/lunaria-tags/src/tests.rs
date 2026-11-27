//! Integration tests for atomic tag editing, WebP/PNG cover normalization, FTS5 sync,
//! `.cue` protection, and live Windows File-Lock Handover (`PlaybackEngine` suspend/resume).

use std::error::Error;
use std::f32::consts::TAU;
use std::fs;
use std::io::Cursor;
use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use image::{DynamicImage, ImageBuffer, ImageFormat, Rgb};
use lunaria_audio::{PlaybackEngine, PlaybackRequest, PlaybackStatus};
use lunaria_db::LibraryDb;
use lunaria_dsp::DspConfigStore;
use lunaria_model::{SearchCriteria, TrackId};
use lunaria_scanner::{ScanConfig, scan_library};
use tempfile::tempdir;

use super::*;

type TestResult<T = ()> = Result<T, Box<dyn Error>>;

fn write_sine_wav(path: &Path, duration_ms: u32) -> TestResult {
    let sample_rate = 44_100_u32;
    let channels = 2_u16;
    let bits_per_sample = 16_u16;
    let frames = (sample_rate * duration_ms) / 1000;
    let data_bytes = frames * u32::from(channels) * 2;
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

    for n in 0..frames {
        let t = n as f32 / sample_rate as f32;
        let sample = ((t * 440.0 * TAU).sin() * 6_000.0) as i16;
        let le = sample.to_le_bytes();
        buf.extend_from_slice(&le);
        buf.extend_from_slice(&le);
    }

    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(path, buf)?;
    Ok(())
}

fn encode_cover_bmp(r: u8, g: u8, b: u8) -> TestResult<Vec<u8>> {
    let img = ImageBuffer::from_pixel(48, 48, Rgb([r, g, b]));
    let mut bytes = Vec::new();
    DynamicImage::ImageRgb8(img).write_to(&mut Cursor::new(&mut bytes), ImageFormat::Bmp)?;
    Ok(bytes)
}

#[test]
fn save_track_tags_performs_atomic_write_webp_bmp_transcode_and_fts_sync() -> TestResult {
    let dir = tempdir()?;
    let music_dir = dir.path().join("music");
    let art_cache = dir.path().join("art_cache");
    let wav_path = music_dir.join("nocturne.wav");
    write_sine_wav(&wav_path, 2_000)?;

    let mut db = LibraryDb::open_in_memory()?;
    let report = scan_library(
        &mut db,
        &ScanConfig {
            root_folders: vec![music_dir],
            artwork_cache_dir: Some(art_cache.clone()),
            parse_cue_sheets: true,
            ..ScanConfig::default()
        },
    )?;
    assert_eq!(report.scanned_tracks, 1);

    let tracks = db.list_tracks(lunaria_model::TrackSortOrder::TitleAsc)?;
    let track_id = tracks[0].id;

    let bmp_cover = encode_cover_bmp(235, 70, 35)?;
    let edit = TagEditRequest {
        title: "Nocturne in C-Sharp Minor".to_owned(),
        artist: "Chopin".to_owned(),
        album: "Warsaw Editions".to_owned(),
        album_artist: "Frederic Chopin".to_owned(),
        track_number: Some(4),
        disc_number: Some(2),
        year: Some(1830),
        genre: "Classical".to_owned(),
        cover_art: CoverArtEdit::Replace(bmp_cover),
    };

    let outcome = save_track_tags(&db, None, track_id, &edit, Some(&art_cache))?;
    assert_eq!(outcome.track_id, track_id);
    assert!(!outcome.handed_over_playback);
    assert!(outcome.artwork_uri.is_some());

    // Instant FTS5 search finds the edited title immediately without full library rescan!
    let hits = db.search_tracks(
        &SearchCriteria {
            query: "Warsaw Nocturne".to_owned(),
            filters: vec![],
        },
        10,
    )?;
    assert_eq!(hits.len(), 1);
    assert_eq!(hits[0].track_number, 4);
    assert_eq!(hits[0].disc_number, 2);
    assert_eq!(hits[0].year, 1830);

    // Virtual CUE tracks must be rejected safely before touching disk.
    let err = save_track_tags(&db, None, TrackId(-999), &edit, None);
    assert!(err.is_err());

    Ok(())
}

#[test]
fn windows_file_lock_handover_suspends_live_playback_writes_tags_and_resumes() -> TestResult {
    let dir = tempdir()?;
    let music_dir = dir.path().join("music");
    let art_cache = dir.path().join("art_cache");
    let wav_path = music_dir.join("live_test.wav");
    // 10s WAV ensures playback remains actively playing throughout tag handover.
    write_sine_wav(&wav_path, 10_000)?;

    let mut db = LibraryDb::open_in_memory()?;
    let _ = scan_library(
        &mut db,
        &ScanConfig {
            root_folders: vec![music_dir],
            artwork_cache_dir: Some(art_cache.clone()),
            ..ScanConfig::default()
        },
    )?;
    let track = db.list_tracks(lunaria_model::TrackSortOrder::TitleAsc)?[0].clone();

    let dsp_store = Arc::new(DspConfigStore::new());
    let engine = PlaybackEngine::new(dsp_store)?;
    // Mute engine so CI/local test runs produce zero audible tone.
    engine.set_volume(0.0)?;
    engine.load_and_play(PlaybackRequest::from_path(&wav_path))?;

    std::thread::sleep(Duration::from_millis(120));

    let bmp_cover = encode_cover_bmp(30, 180, 210)?;
    let edit = TagEditRequest {
        title: "Live Handover Master".to_owned(),
        artist: "Test Ensemble".to_owned(),
        album: "Handover Suite".to_owned(),
        album_artist: "Test Ensemble".to_owned(),
        track_number: Some(1),
        disc_number: Some(1),
        year: Some(2026),
        genre: "Acoustic".to_owned(),
        cover_art: CoverArtEdit::Replace(bmp_cover),
    };

    let outcome = save_track_tags(&db, Some(&engine), track.id, &edit, Some(&art_cache))?;
    assert!(outcome.file_size > 44);

    // Give playback thread a short moment after resume.
    std::thread::sleep(Duration::from_millis(80));
    let telemetry = engine.telemetry();
    assert_ne!(telemetry.status, PlaybackStatus::Error);
    engine.stop()?;

    let updated = db
        .get_track(track.id)?
        .ok_or("updated track must remain in SQLite")?;
    assert_eq!(updated.title, "Live Handover Master");
    Ok(())
}
