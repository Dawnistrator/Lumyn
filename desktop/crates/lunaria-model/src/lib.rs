//! Pure domain types, parsers, and offline library intelligence for Lunaria Windows.
//!
//! Zero filesystem or network I/O lives in this crate; all parsing operates on `&str` slices
//! so core music logic remains deterministic, fuzz-safe, and platform-independent.

#![forbid(unsafe_code)]

pub mod audio;
pub mod autoeq;
pub mod cue;
pub mod equalizer;
pub mod folder_filter;
pub mod insights;
pub mod lrc;
pub mod m3u;
pub mod playback;
pub mod replay_gain;
pub mod track;

pub use audio::{
    AudioDeviceInfo, AudioOutputCategory, AudioOutputMode, AudioSignalPath, AudioSpec,
};
pub use autoeq::{AutoEqProfile, parse_autoeq};
pub use cue::{
    CueTrackEntry, ParsedCueSheet, build_virtual_tracks, deterministic_negative_id, parse_cue_sheet,
};
pub use equalizer::{
    BUILT_IN_EQ_PRESETS, EQ_MAX_GAIN_DB, EQ_MIN_GAIN_DB, EqualizerPreset, EqualizerSettings,
    MAX_CROSSFADE_SEC, ParametricFilter, ParametricFilterType, ReplayGainMode,
    STANDARD_EQ_FREQUENCIES_HZ,
};
pub use folder_filter::{DEFAULT_EXCLUDED_FOLDERS, FolderFilterConfig, FolderFilterMode};
pub use insights::{
    ArtistPlayStat, ListeningStats, SmartPlaylistSummary, SmartPlaylistType,
    compute_listening_stats, favorites, forgotten_gems, hi_res_collection, most_played,
    recently_played, summarize_smart_playlists, tracks_for_smart_playlist,
};
pub use lrc::{LyricLine, ParsedLyrics, active_line_index_at, parse_lyrics};
pub use m3u::{M3uEntry, export_m3u, match_m3u_tracks, parse_m3u};
pub use playback::{
    MAX_PERSISTED_QUEUE_SIZE, PersistedQueueState, PlaybackState, RepeatMode, SearchCriteria,
    SearchFilterChip, SleepTimerState, ThemeMode, UserPreferences, clamp_playback_speed,
    track_matches_search,
};
pub use replay_gain::{
    R128_TO_REPLAYGAIN_OFFSET_DB, compute_effective_gain_db, has_tag_for_mode, parse_gain_db,
    parse_peak, parse_r128_gain_db,
};
pub use track::{
    AlbumId, AlbumSummary, ArtistSummary, FolderSummary, PlaylistId, PlaylistSummary, Track,
    TrackId, TrackSortOrder,
};

#[cfg(test)]
mod tests {
    use proptest::prelude::*;

    use super::*;

    fn sample_track(id: i64, title: &str, artist: &str, hi_res: bool) -> Track {
        Track {
            id: TrackId(id),
            file_path: format!("C:/Music/{artist}/{title}.flac"),
            title: title.to_owned(),
            artist: artist.to_owned(),
            album: "Studio Album".to_owned(),
            album_artist: artist.to_owned(),
            album_id: AlbumId(10),
            duration_ms: 240_000,
            track_number: 1,
            disc_number: 1,
            year: 2024,
            genre: "Jazz".to_owned(),
            folder_path: format!("Music/{artist}"),
            file_name: format!("{title}.flac"),
            mime_type: "audio/flac".to_owned(),
            audio_spec: AudioSpec {
                codec: "FLAC".to_owned(),
                bit_depth: if hi_res { 24 } else { 16 },
                sample_rate_hz: if hi_res { 96_000 } else { 44_100 },
                bitrate_kbps: if hi_res { 3_200 } else { 900 },
                channel_count: 2,
                is_lossless: true,
            },
            artwork_uri: None,
            artwork_hash: None,
            seed_color_argb: None,
            file_size: 40_000_000,
            modified_ns: 1_000_000,
            is_favorite: false,
            date_added_seconds: 1_000,
            replay_gain_track_db: None,
            replay_gain_album_db: None,
            replay_gain_track_peak: None,
            replay_gain_album_peak: None,
            clip_start_ms: None,
            clip_end_ms: None,
            cue_source_path: None,
            play_count: 0,
            last_played_at_ms: 0,
            total_play_duration_ms: 0,
        }
    }

    #[test]
    fn hi_res_detection_matches_android_rule() {
        let cd = AudioSpec {
            codec: "FLAC".to_owned(),
            bit_depth: 16,
            sample_rate_hz: 44_100,
            bitrate_kbps: 900,
            channel_count: 2,
            is_lossless: true,
        };
        assert!(!cd.is_hi_res());
        assert_eq!(cd.format_quality_summary(), "FLAC • 16-bit / 44.1 kHz");

        let hi_res = AudioSpec {
            codec: "FLAC".to_owned(),
            bit_depth: 24,
            sample_rate_hz: 192_000,
            bitrate_kbps: 4_800,
            channel_count: 2,
            is_lossless: true,
        };
        assert!(hi_res.is_hi_res());
        assert_eq!(hi_res.format_quality_summary(), "FLAC • 24-bit / 192 kHz");
        assert_eq!(
            hi_res.format_discord_quality(),
            "FLAC • 4800 kbps • 192 kHz"
        );

        let mp3 = AudioSpec {
            codec: "MP3".to_owned(),
            bit_depth: 0,
            sample_rate_hz: 44_100,
            bitrate_kbps: 320,
            channel_count: 2,
            is_lossless: false,
        };
        assert!(!mp3.is_hi_res());
        assert_eq!(mp3.format_quality_summary(), "MP3 • 320 kbps / 44.1 kHz");
        assert_eq!(mp3.format_discord_quality(), "MP3 • 320 kbps • 44.1 kHz");

        let aac_unknown = AudioSpec {
            codec: "AAC".to_owned(),
            bit_depth: 0,
            sample_rate_hz: 48_000,
            bitrate_kbps: 0,
            channel_count: 2,
            is_lossless: false,
        };
        let aac_summary = aac_unknown.format_quality_summary();
        assert_eq!(aac_summary, "AAC • 48 kHz");
        assert_eq!(aac_unknown.format_discord_quality(), "AAC • 48 kHz");
        assert!(!aac_summary.contains("0-bit"));

        let flac_zero_bits = AudioSpec {
            codec: "FLAC".to_owned(),
            bit_depth: 0,
            sample_rate_hz: 44_100,
            bitrate_kbps: 1_000,
            channel_count: 2,
            is_lossless: true,
        };
        let flac_summary = flac_zero_bits.format_quality_summary();
        assert_eq!(flac_summary, "FLAC • 1000 kbps / 44.1 kHz");
        assert!(!flac_summary.contains("0-bit"));
    }

    #[test]
    fn replay_gain_parses_standard_r128_and_guards_peak_clipping() {
        assert!((parse_gain_db("-6.42 dB").unwrap_or(0.0) - (-6.42)).abs() < 0.001);
        assert!((parse_gain_db("+1,25").unwrap_or(0.0) - 1.25).abs() < 0.001);
        assert!(parse_gain_db("   ").is_none());

        assert!((parse_peak("0.950000").unwrap_or(0.0) - 0.95).abs() < 0.0001);
        assert!(parse_peak("0.0").is_none());
        assert!((parse_r128_gain_db("-512").unwrap_or(0.0) - 3.0).abs() < 0.01);

        assert!(
            compute_effective_gain_db(ReplayGainMode::Off, Some(-4.0), None, Some(0.8), None, 2.0)
                .is_none()
        );

        let guarded = compute_effective_gain_db(
            ReplayGainMode::Track,
            Some(-1.0),
            Some(-5.0),
            Some(0.8),
            Some(0.9),
            5.0,
        )
        .unwrap_or(0.0);
        assert!((guarded - 1.938).abs() < 0.02);

        let album = compute_effective_gain_db(
            ReplayGainMode::Album,
            Some(-2.0),
            Some(-5.5),
            None,
            None,
            1.0,
        )
        .unwrap_or(0.0);
        assert!((album - (-4.5)).abs() < 0.001);

        assert!(has_tag_for_mode(ReplayGainMode::Track, Some(-3.0), None));
        assert!(!has_tag_for_mode(ReplayGainMode::Album, None, None));
    }

    #[test]
    fn autoeq_parses_parametric_and_graphic_formats() {
        let parametric = "\u{FEFF}Preamp: -6.2 dB\n\
            Filter 1: ON LSC Fc 105 Hz Gain 4.8 dB Q 0.70\n\
            Filter 2: ON PK Fc 1950 Hz Gain -3.4 dB Q 1.65\n\
            Filter 3: OFF PK Fc 5000 Hz Gain 2.0 dB Q 1.00\n\
            Filter 4: ON HSC Fc 10000 Hz Gain -1.5 dB Q 0.71";

        let parsed = parse_autoeq(parametric, "Sennheiser_HD600.txt");
        assert!(parsed.is_some());
        if let Some(profile) = parsed {
            assert_eq!(profile.name, "Sennheiser_HD600");
            assert!((profile.preamp_db - (-6.2)).abs() < 0.01);
            assert_eq!(profile.parametric_filters.len(), 4);
            assert_eq!(
                profile.parametric_filters[0].filter_type,
                ParametricFilterType::LowShelf
            );
            assert!(!profile.parametric_filters[2].enabled);
            assert_eq!(
                profile.parametric_filters[3].filter_type,
                ParametricFilterType::HighShelf
            );
        }

        let graphic = "GraphicEQ: 31 4.0; 125 2.0; 1000 -1.0; 8000 3.0; 16000 3.0";
        let g_parsed = parse_autoeq(graphic, "Warm_V");
        assert!(g_parsed.is_some());
        if let Some(profile) = g_parsed {
            assert!((profile.band_gains_db[0] - 4.0).abs() < 0.01);
            assert!((profile.band_gains_db[2] - 2.0).abs() < 0.01);
            assert!((profile.band_gains_db[5] - (-1.0)).abs() < 0.01);
            assert!(profile.preamp_db <= -3.9);
        }

        assert!(parse_autoeq("Just random notes", "Fallback").is_none());
    }

    #[test]
    fn cue_sheet_and_virtual_tracks_match_android_ids() {
        let cue = "PERFORMER \"Symphony Orchestra\"\n\
            TITLE \"Live Concert\"\n\
            FILE \"concert.flac\" WAVE\n\
              TRACK 01 AUDIO\n\
                TITLE \"Overture\"\n\
                INDEX 01 00:00:00\n\
              TRACK 02 AUDIO\n\
                TITLE \"Adagio\"\n\
                PERFORMER \"Guest Soloist\"\n\
                INDEX 01 04:10:00\n\
              TRACK 03 AUDIO\n\
                TITLE \"Finale\"\n\
                INDEX 01 13:32:15";

        let parsed = parse_cue_sheet(cue);
        assert!(parsed.is_some());
        if let Some(sheet) = parsed {
            assert_eq!(sheet.tracks.len(), 3);
            let expected_third = (13 * 60 + 32) * 1_000 + (15 * 1_000) / 75;
            assert_eq!(sheet.tracks[2].start_ms, expected_third);
            assert!(sheet.tracks[2].end_ms.is_none());

            let mut backing = sample_track(42, "concert", "Unknown Artist", true);
            backing.duration_ms = 1_200_000;
            let virtuals = build_virtual_tracks(&sheet, &backing, "concert.cue");
            assert_eq!(virtuals.len(), 3);
            assert!(virtuals.iter().all(|t| t.id.0 < 0 && t.is_cue_track()));
            assert_eq!(virtuals[0].clip_start_ms, Some(0));
            assert_eq!(virtuals[0].clip_end_ms, Some(250_000));
            assert_eq!(virtuals[1].artist, "Guest Soloist");
            assert_eq!(
                virtuals[0].id,
                deterministic_negative_id("concert.cue|42|1|0")
            );
        }
    }

    #[test]
    fn lrc_and_m3u_parsers_round_trip_cleanly() {
        let lrc = "[ar:Artist]\n[offset:500]\n[00:10.00][00:45.25]Chorus\n[00:04.50]Verse\n[00:22.125]Bridge";
        let lyrics = parse_lyrics(lrc, "Local .lrc");
        assert!(lyrics.is_some());
        if let Some(doc) = lyrics {
            assert!(doc.is_synced);
            assert_eq!(doc.lines.len(), 4);
            assert_eq!(doc.lines[0].timestamp_ms, 5_000);
            assert_eq!(doc.lines[2].timestamp_ms, 22_625);
            assert_eq!(active_line_index_at(&doc.lines, 2_000), None);
            assert_eq!(active_line_index_at(&doc.lines, 5_000), Some(0));
            assert_eq!(active_line_index_at(&doc.lines, 60_000), Some(3));
        }

        let track = sample_track(1, "Blue in Green", "Miles Davis", true);
        let exported = export_m3u(std::slice::from_ref(&track));
        let entries = parse_m3u(&exported);
        assert_eq!(entries.len(), 1);
        let matched = match_m3u_tracks(&entries, &[track]);
        assert_eq!(matched.len(), 1);
        assert_eq!(matched[0].title, "Blue in Green");
    }

    #[test]
    fn folder_filter_insights_and_search_cover_windows_paths() {
        let cfg = FolderFilterConfig::default();
        assert!(cfg.is_folder_allowed(r"\\?\D:\Music\FLAC\Jazz"));
        assert!(!cfg.is_folder_allowed(r"C:\Users\Dawn\WhatsApp Audio\Voice"));

        let wl = FolderFilterConfig {
            mode: FolderFilterMode::Whitelist,
            included_folders: vec![r"D:\Music\FLAC".to_owned()],
            excluded_folders: Vec::new(),
        };
        assert!(wl.should_descend_directory(r"D:\Music"));
        assert!(wl.should_descend_directory(r"d:/music/flac"));
        assert!(!wl.is_folder_allowed(r"D:\Music"));
        assert!(wl.is_folder_allowed(r"d:\music\flac\Albums"));
        assert!(!wl.should_descend_directory(r"D:\Music\MP3"));

        let empty_wl = FolderFilterConfig {
            mode: FolderFilterMode::Whitelist,
            included_folders: Vec::new(),
            excluded_folders: Vec::new(),
        };
        assert!(empty_wl.should_descend_directory(r"D:\Music"));
        assert!(empty_wl.is_folder_allowed(r"D:\Music\FLAC"));

        let mut t1 = sample_track(1, "Blue", "Miles", true);
        t1.play_count = 5;
        t1.last_played_at_ms = 5_000_000_000;
        t1.is_favorite = true;

        let mut t2 = sample_track(2, "Forgotten", "Bill", true);
        t2.play_count = 0;

        let stats = compute_listening_stats(&[t1.clone(), t2.clone()], 5);
        assert_eq!(stats.total_plays, 5);
        assert_eq!(stats.hi_res_plays, 5);
        assert_eq!(stats.top_artists.len(), 1);

        let summaries = summarize_smart_playlists(&[t1.clone(), t2], 5_100_000_000);
        assert_eq!(summaries.len(), 5);

        let criteria = SearchCriteria {
            query: "blue".to_owned(),
            filters: vec![SearchFilterChip::HiRes, SearchFilterChip::Favorites],
        };
        assert!(track_matches_search(&t1, &criteria));
    }

    proptest! {
        #[test]
        fn parsers_never_panic_on_arbitrary_utf8(input in ".*") {
            let _ = parse_gain_db(&input);
            let _ = parse_peak(&input);
            let _ = parse_r128_gain_db(&input);
            let _ = parse_autoeq(&input, &input);
            let _ = parse_cue_sheet(&input);
            let _ = parse_lyrics(&input, "fuzz");
            let _ = parse_m3u(&input);
            let _ = FolderFilterConfig::normalize_folder_path(&input);
        }

        #[test]
        fn replay_gain_effective_output_stays_within_safe_bounds(
            track_db in -30.0f32..30.0f32,
            album_db in -30.0f32..30.0f32,
            peak in 0.01f32..8.0f32,
            preamp in -24.0f32..24.0f32,
        ) {
            if let Some(gain) = compute_effective_gain_db(
                ReplayGainMode::Track,
                Some(track_db),
                Some(album_db),
                Some(peak),
                None,
                preamp,
            ) {
                prop_assert!(gain.is_finite());
                prop_assert!((-24.0..=24.0).contains(&gain));
                let max_safe = (-20.0 * f64::from(peak).log10()) as f32;
                prop_assert!(gain <= max_safe + 1e-4);
            }
        }
    }
}
