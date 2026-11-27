//! Unit tests for SQLite WAL + FTS5 search, CUE virtual rows, Portable Mode, playlists, and settings.

use std::error::Error;
use std::fs::File;
use std::time::Instant;

use lunaria_model::{
    AlbumId, AudioOutputMode, AudioSpec, EqualizerSettings, MAX_PERSISTED_QUEUE_SIZE,
    PersistedQueueState, RepeatMode, SearchCriteria, SearchFilterChip, ThemeMode, Track, TrackId,
    TrackSortOrder, UserPreferences,
};
use tempfile::tempdir;

use super::*;

type TestResult = Result<(), Box<dyn Error>>;

fn sample_track(id: i64, title: &str, artist: &str, hi_res: bool, cue: bool) -> Track {
    Track {
        id: TrackId(id),
        file_path: if cue {
            "D:/Music/Symphony/concert.flac".to_owned()
        } else {
            format!("D:/Music/{artist}/{title}.flac")
        },
        title: title.to_owned(),
        artist: artist.to_owned(),
        album: "Audiophile Sessions".to_owned(),
        album_artist: artist.to_owned(),
        album_id: AlbumId(if hi_res { 101 } else { 102 }),
        duration_ms: 210_000,
        track_number: if cue { (-id) as u32 } else { 1 },
        disc_number: 1,
        year: 2024,
        genre: "Jazz".to_owned(),
        folder_path: format!("D:/Music/{artist}"),
        file_name: format!("{title}.flac"),
        mime_type: "audio/flac".to_owned(),
        audio_spec: AudioSpec {
            codec: "FLAC".to_owned(),
            bit_depth: if hi_res { 24 } else { 16 },
            sample_rate_hz: if hi_res { 96_000 } else { 44_100 },
            bitrate_kbps: if hi_res { 3_100 } else { 950 },
            channel_count: 2,
            is_lossless: true,
        },
        artwork_uri: None,
        artwork_hash: None,
        seed_color_argb: Some(0xFF32_428A),
        file_size: 42_000_000,
        modified_ns: 1_700_000_000,
        is_favorite: false,
        date_added_seconds: 1_700_000,
        replay_gain_track_db: Some(-4.5),
        replay_gain_album_db: None,
        replay_gain_track_peak: Some(0.95),
        replay_gain_album_peak: None,
        clip_start_ms: if cue { Some(0) } else { None },
        clip_end_ms: if cue { Some(180_000) } else { None },
        cue_source_path: if cue {
            Some("d:/music/symphony/concert.cue".to_owned())
        } else {
            None
        },
        play_count: 0,
        last_played_at_ms: 0,
        total_play_duration_ms: 0,
    }
}

#[test]
fn storage_paths_detect_portable_flag_beside_executable() -> TestResult {
    let dir = tempdir()?;
    let std_paths = resolve_storage_paths(Some(dir.path()));
    assert!(!std_paths.is_portable);

    let flag_path = dir.path().join("portable.flag");
    drop(File::create(&flag_path)?);
    let portable_paths = resolve_storage_paths(Some(dir.path()));
    assert!(portable_paths.is_portable);
    assert_eq!(portable_paths.data_dir, dir.path().join("data"));
    assert_eq!(
        portable_paths.db_path,
        dir.path().join("data").join("lunaria.db")
    );
    portable_paths.ensure_dirs()?;
    assert!(portable_paths.artwork_cache_dir.is_dir());
    assert!(portable_paths.logs_dir.is_dir());
    Ok(())
}

#[test]
fn fts5_search_filters_and_cue_negative_ids_work_fast_and_preserve_history() -> TestResult {
    let dir = tempdir()?;
    let mut db = LibraryDb::open(&dir.path().join("lunaria.db"))?;

    let regular = sample_track(10, "Kind of Blue (Remaster)", "Miles Davis", true, false);
    let mut regular_cd = sample_track(11, "Midnight Sonata", "Chopin", false, false);
    regular_cd.audio_spec.codec = "MP3".to_owned();
    regular_cd.audio_spec.is_lossless = false;
    let cue_1 = sample_track(-100, "Movement I: Allegro", "Berlin Phil", true, true);
    let cue_2 = sample_track(-101, "Movement II: Adagio", "Berlin Phil", true, true);

    assert_eq!(
        db.upsert_tracks_batch(&[regular.clone(), regular_cd, cue_1, cue_2])?,
        4
    );
    assert_eq!(db.track_count()?, 4);

    db.set_favorite(TrackId(10), true)?;
    db.record_play(TrackId(10), 123_456_789, 180_000)?;

    // Re-upserting scanned metadata must preserve favorite & play history!
    let mut rescanned = regular;
    rescanned.title = "Kind of Blue (24-bit Master)".to_owned();
    assert_eq!(db.upsert_tracks_batch(&[rescanned])?, 1);

    let reloaded = db
        .get_track(TrackId(10))?
        .ok_or("reloaded track 10 must exist")?;
    assert_eq!(reloaded.title, "Kind of Blue (24-bit Master)");
    assert!(reloaded.is_favorite);
    assert_eq!(reloaded.play_count, 1);
    assert_eq!(reloaded.total_play_duration_ms, 180_000);

    // Search FTS5 with punctuation/quotes & negative CUE ID matching
    let start = Instant::now();
    // `miles` matches `artist` and `remaster` still matches `file_name` (`Kind of Blue (Remaster).flac`),
    // verifying FTS5 punctuation stripping never triggers a syntax error.
    let fts_hits = db.search_tracks(
        &SearchCriteria {
            query: "miles: (remaster\"? )".to_owned(),
            filters: vec![],
        },
        50,
    )?;
    assert_eq!(fts_hits.len(), 1);
    assert_eq!(fts_hits[0].id, TrackId(10));

    let empty_hits = db.search_tracks(
        &SearchCriteria {
            query: "nonexistent: (\"token? )".to_owned(),
            filters: vec![],
        },
        50,
    )?;
    assert!(empty_hits.is_empty());

    let blue_hits = db.search_tracks(
        &SearchCriteria {
            query: "Kind 24-bit!".to_owned(),
            filters: vec![SearchFilterChip::HiRes, SearchFilterChip::Favorites],
        },
        50,
    )?;
    assert_eq!(blue_hits.len(), 1);
    assert_eq!(blue_hits[0].id, TrackId(10));

    let cue_hits = db.search_tracks(
        &SearchCriteria {
            query: "Adagio".to_owned(),
            filters: vec![SearchFilterChip::Flac],
        },
        50,
    )?;
    assert_eq!(cue_hits.len(), 1);
    assert_eq!(cue_hits[0].id, TrackId(-101));
    assert!(start.elapsed().as_millis() < 250);

    assert_eq!(db.list_albums()?.len(), 2);
    assert_eq!(db.list_artists()?.len(), 3);
    assert_eq!(db.list_folders()?.len(), 3);
    assert_eq!(db.list_cue_track_ids()?.len(), 2);
    Ok(())
}

#[test]
fn playlists_watched_folders_queue_and_preferences_round_trip() -> TestResult {
    let mut db = LibraryDb::open_in_memory()?;
    let t1 = sample_track(1, "Track One", "Artist A", true, false);
    let t2 = sample_track(2, "Track Two", "Artist B", false, false);
    assert_eq!(db.upsert_tracks_batch(&[t1, t2])?, 2);

    let pid = db.create_playlist(" Late Night ", 1_000)?;
    db.add_tracks_to_playlist(pid, &[TrackId(1), TrackId(2)])?;
    let pl_tracks = db.get_playlist_tracks(pid)?;
    assert_eq!(pl_tracks.len(), 2);
    assert_eq!(pl_tracks[0].id, TrackId(1));

    db.reorder_playlist_tracks(pid, &[TrackId(2), TrackId(1)])?;
    let reordered = db.get_playlist_tracks(pid)?;
    assert_eq!(reordered[0].id, TrackId(2));
    assert_eq!(reordered[1].id, TrackId(1));

    db.rename_playlist(pid, "Morning FLAC")?;
    let summaries = db.list_playlists()?;
    assert_eq!(summaries[0].name, "Morning FLAC");
    assert_eq!(summaries[0].track_count, 2);

    // Deleting a track cascades cleanly from playlist_tracks
    assert_eq!(db.delete_tracks_by_ids(&[TrackId(2)])?, 1);
    assert_eq!(db.get_playlist_tracks(pid)?.len(), 1);

    db.add_watched_folder(r"\\?\D:\Music\Lossless", 50)?;
    assert_eq!(db.list_watched_folders()?, vec!["D:/Music/Lossless"]);
    db.remove_watched_folder(r"d:\music\lossless")?;
    assert!(db.list_watched_folders()?.is_empty());

    let oversized_ids: Vec<TrackId> = (1_i64..=2_500).map(TrackId).collect();
    let queue_state = PersistedQueueState {
        track_ids: oversized_ids,
        current_index: 2_300,
        position_ms: 42_500,
        repeat_mode: RepeatMode::All,
        shuffle_enabled: true,
        speed: 1.25,
    };
    db.save_persisted_queue(&queue_state)?;
    let loaded_q = db
        .load_persisted_queue()?
        .ok_or("persisted queue should exist")?;
    assert_eq!(loaded_q.track_ids.len(), MAX_PERSISTED_QUEUE_SIZE);
    assert_eq!(loaded_q.current_index, MAX_PERSISTED_QUEUE_SIZE - 1);
    assert_eq!(loaded_q.repeat_mode, RepeatMode::All);

    let prefs = UserPreferences {
        theme_mode: ThemeMode::Dark,
        use_album_art_theme: false,
        audio_output_mode: AudioOutputMode::PuristDirectBitPerfect,
        preferred_device_id: Some("dac-xyz".to_owned()),
        use_software_renderer: true,
        minimize_to_tray: false,
        equalizer: EqualizerSettings {
            enabled: true,
            preamp_db: -2.5,
            active_preset_name: "Custom".to_owned(),
            ..EqualizerSettings::default()
        },
        ..UserPreferences::default()
    };
    db.save_preferences(&prefs)?;
    let loaded_prefs = db.load_preferences()?;
    assert_eq!(loaded_prefs, prefs);

    assert!(!db.list_tracks(TrackSortOrder::AlbumAsc)?.is_empty());
    Ok(())
}

#[test]
fn schema_v2_migration_backfills_zero_bitrate_and_lossless_bit_depth() -> TestResult {
    let dir = tempdir()?;
    let db_path = dir.path().join("legacy_v1.db");

    {
        let mut db = LibraryDb::open(&db_path)?;
        let mut mp3 = sample_track(10, "Legacy Lossy", "Artist X", false, false);
        mp3.audio_spec.codec = "MP3".to_owned();
        mp3.audio_spec.bit_depth = 0;
        mp3.audio_spec.bitrate_kbps = 0;
        mp3.audio_spec.is_lossless = false;
        mp3.file_size = 4_000_000;
        mp3.duration_ms = 100_000;

        let mut flac = sample_track(11, "Legacy Lossless", "Artist Y", false, false);
        flac.audio_spec.bit_depth = 0;
        flac.audio_spec.bitrate_kbps = 0;
        flac.audio_spec.is_lossless = true;
        flac.file_size = 12_500_000;
        flac.duration_ms = 100_000;

        assert_eq!(db.upsert_tracks_batch(&[mp3, flac])?, 2);
    }

    {
        let conn = rusqlite::Connection::open(&db_path)?;
        conn.pragma_update(None, "user_version", 1_i64)?;
    }

    let reopened = LibraryDb::open(&db_path)?;
    let mp3_loaded = reopened
        .get_track(TrackId(10))?
        .ok_or("migrated mp3 track missing")?;
    assert_eq!(mp3_loaded.audio_spec.bitrate_kbps, 320);
    assert_eq!(mp3_loaded.audio_spec.bit_depth, 0);
    assert_eq!(
        mp3_loaded.audio_spec.format_quality_summary(),
        "MP3 • 320 kbps / 44.1 kHz"
    );

    let flac_loaded = reopened
        .get_track(TrackId(11))?
        .ok_or("migrated flac track missing")?;
    assert_eq!(flac_loaded.audio_spec.bitrate_kbps, 1_000);
    assert_eq!(flac_loaded.audio_spec.bit_depth, 16);
    assert_eq!(
        flac_loaded.audio_spec.format_quality_summary(),
        "FLAC • 16-bit / 44.1 kHz"
    );

    Ok(())
}
