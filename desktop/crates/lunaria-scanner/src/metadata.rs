//! Single-file Lofty metadata, audio properties, ReplayGain/R128, and artwork extraction.

use std::fs;
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

use lofty::file::{AudioFile, FileType, TaggedFileExt};
use lofty::picture::PictureType;
use lofty::probe::Probe;
use lofty::tag::{Accessor, ItemKey};
use lunaria_db::TrackFingerprint;
use lunaria_model::{
    AlbumId, AudioSpec, Track, TrackId, parse_gain_db, parse_peak, parse_r128_gain_db,
};

use crate::ScannerError;
use crate::artwork::{
    LUNARIA_BRAND_SEED_ARGB, cache_artwork_bytes, extract_seed_color_from_image_bytes,
    find_folder_cover_bytes,
};

/// Case-insensitive file extensions treated as playable local audio tracks.
pub const SUPPORTED_AUDIO_EXTENSIONS: &[&str] = &[
    "flac", "mp3", "wav", "ogg", "m4a", "aac", "alac", "aiff", "aif",
];

/// Returns `true` if `path` has a supported audio extension.
#[must_use]
pub fn is_supported_audio_file(path: &Path) -> bool {
    let Some(ext) = path.extension().and_then(|e| e.to_str()) else {
        return false;
    };
    SUPPORTED_AUDIO_EXTENSIONS
        .iter()
        .any(|candidate| ext.eq_ignore_ascii_case(candidate))
}

/// Returns a display-safe normalized path string (`\` -> `/`, stripping Windows `\\?\` prefix).
#[must_use]
pub fn normalize_display_path(path: &Path) -> String {
    let raw = path.to_string_lossy();
    let stripped = raw.strip_prefix(r"\\?\").unwrap_or(&raw);
    stripped.replace('\\', "/")
}

/// Returns the canonical lowercase lookup key used in SQLite (`normalized_key`).
#[must_use]
pub fn normalize_path_key(path: &Path) -> String {
    let display = normalize_display_path(path);
    let trimmed = if display.len() > 3 && display.ends_with('/') {
        display.trim_end_matches('/')
    } else {
        &display
    };
    trimmed.to_lowercase()
}

/// Computes a deterministic positive `i64` [`TrackId`] (`> 0`) for a real audio file path.
///
/// Negative track IDs remain reserved for `.cue` virtual sub-tracks.
#[must_use]
pub fn deterministic_track_id(path_key: &str) -> TrackId {
    let digest = blake3::hash(path_key.as_bytes());
    let mut bytes = [0u8; 8];
    bytes.copy_from_slice(&digest.as_bytes()[..8]);
    let positive = (u64::from_le_bytes(bytes) & 0x7FFF_FFFF_FFFF_FFFF) as i64;
    TrackId(positive.max(1))
}

/// Computes a deterministic positive [`AlbumId`] grouping tracks sharing album + album artist.
#[must_use]
pub fn deterministic_album_id(album: &str, album_artist: &str) -> AlbumId {
    let key = format!(
        "{}::{}",
        album.trim().to_lowercase(),
        album_artist.trim().to_lowercase()
    );
    let digest = blake3::hash(key.as_bytes());
    let mut bytes = [0u8; 8];
    bytes.copy_from_slice(&digest.as_bytes()[..8]);
    let positive = (u64::from_le_bytes(bytes) & 0x7FFF_FFFF_FFFF_FFFF) as i64;
    AlbumId(positive.max(1))
}

/// Reads filesystem timestamps, Lofty tags/audio properties, ReplayGain/R128, and cover art
/// from `path` into a domain [`Track`].
pub fn scan_single_audio_file(
    path: &Path,
    artwork_cache_dir: Option<&Path>,
    existing_fingerprint: Option<&TrackFingerprint>,
) -> Result<Track, ScannerError> {
    let meta = fs::metadata(path)?;
    let file_size = meta.len();
    let modified_ns = meta
        .modified()
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .and_then(|d| i64::try_from(d.as_nanos()).ok())
        .unwrap_or(0);

    let file_path = normalize_display_path(path);
    let norm_key = normalize_path_key(path);
    let id = existing_fingerprint
        .map(|fp| fp.id)
        .unwrap_or_else(|| deterministic_track_id(&norm_key));

    let folder_path = path
        .parent()
        .map(normalize_display_path)
        .unwrap_or_default();
    let file_name = path
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("unknown")
        .to_owned();
    let fallback_title = path
        .file_stem()
        .and_then(|s| s.to_str())
        .filter(|s| !s.trim().is_empty())
        .unwrap_or("Untitled Track")
        .to_owned();

    let tagged = Probe::open(path)?.guess_file_type()?.read()?;
    let props = tagged.properties();
    let tag = tagged.primary_tag().or_else(|| tagged.first_tag());
    let embedded_art_bytes: u64 = tag.map_or(0, |t| {
        t.pictures().iter().map(|p| p.data().len() as u64).sum()
    });
    let ext_lower = path
        .extension()
        .and_then(|e| e.to_str())
        .map(str::to_lowercase)
        .unwrap_or_default();

    let duration_ms = u64::try_from(props.duration().as_millis()).unwrap_or(u64::MAX);
    let sample_rate_hz = props.sample_rate().filter(|&sr| sr > 0).unwrap_or(44_100);
    let channel_count = props.channels().filter(|&ch| ch > 0).unwrap_or(2);
    let raw_bit_depth = props.bit_depth().filter(|&b| b > 0);
    let audio_bytes = file_size.saturating_sub(embedded_art_bytes);
    let basis_bytes = if audio_bytes > 0 {
        audio_bytes
    } else {
        file_size
    };
    let estimated_bitrate_kbps = if duration_ms > 0 && basis_bytes > 0 {
        ((u128::from(basis_bytes) * 8) / u128::from(duration_ms)).min(u128::from(u32::MAX)) as u32
    } else {
        0
    };
    let bitrate_kbps = props
        .audio_bitrate()
        .filter(|&b| b > 0)
        .or_else(|| props.overall_bitrate().filter(|&b| b > 0))
        .unwrap_or(estimated_bitrate_kbps);

    let (codec, mime_type, is_lossless) =
        classify_file_type(tagged.file_type(), &ext_lower, raw_bit_depth, bitrate_kbps);
    let bit_depth = u16::from(raw_bit_depth.unwrap_or(if is_lossless { 16 } else { 0 }));
    let channel_count = u16::from(channel_count);

    let title = tag
        .and_then(Accessor::title)
        .map(|s| s.trim().to_owned())
        .filter(|s| !s.is_empty())
        .unwrap_or(fallback_title);
    let artist = tag
        .and_then(Accessor::artist)
        .map(|s| s.trim().to_owned())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "Unknown Artist".to_owned());
    let album = tag
        .and_then(Accessor::album)
        .map(|s| s.trim().to_owned())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "Unknown Album".to_owned());
    let album_artist = tag
        .and_then(|t| t.get_string(ItemKey::AlbumArtist))
        .map(|s| s.trim().to_owned())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| artist.clone());
    let genre = tag
        .and_then(Accessor::genre)
        .map(|s| s.trim().to_owned())
        .unwrap_or_default();
    let track_number = tag.and_then(Accessor::track).unwrap_or(0);
    let disc_number = tag.and_then(Accessor::disk).unwrap_or(1).max(1);
    let year = tag
        .and_then(Accessor::date)
        .map_or(0, |ts| u32::from(ts.year));

    let rg_track_db = tag
        .and_then(|t| t.get_string(ItemKey::ReplayGainTrackGain))
        .and_then(parse_gain_db)
        .or_else(|| {
            tag.and_then(|t| t.get_string(ItemKey::R128TrackGain))
                .and_then(parse_r128_gain_db)
        });
    let rg_album_db = tag
        .and_then(|t| t.get_string(ItemKey::ReplayGainAlbumGain))
        .and_then(parse_gain_db)
        .or_else(|| {
            tag.and_then(|t| t.get_string(ItemKey::R128AlbumGain))
                .and_then(parse_r128_gain_db)
        });
    let rg_track_peak = tag
        .and_then(|t| t.get_string(ItemKey::ReplayGainTrackPeak))
        .and_then(parse_peak);
    let rg_album_peak = tag
        .and_then(|t| t.get_string(ItemKey::ReplayGainAlbumPeak))
        .and_then(parse_peak);

    let embedded_cover = tag.and_then(|t| {
        t.pictures()
            .iter()
            .find(|p| p.pic_type() == PictureType::CoverFront)
            .or_else(|| t.pictures().first())
            .map(|p| p.data().to_vec())
    });
    let artwork_bytes = embedded_cover.or_else(|| find_folder_cover_bytes(path));

    let (artwork_uri, artwork_hash, seed_color_argb) = match (artwork_bytes, artwork_cache_dir) {
        (Some(bytes), Some(cache_dir)) => match cache_artwork_bytes(cache_dir, &bytes) {
            Ok(cached) => (
                Some(normalize_display_path(&cached.file_path)),
                Some(cached.hash),
                Some(cached.seed_color_argb),
            ),
            Err(err) => {
                tracing::debug!(
                    target: "lunaria_scanner",
                    path = %path.display(),
                    error = %err,
                    "failed caching artwork; using brand seed"
                );
                (None, None, Some(LUNARIA_BRAND_SEED_ARGB))
            }
        },
        (Some(bytes), None) => (
            None,
            Some(blake3::hash(&bytes).to_hex().to_string()),
            Some(extract_seed_color_from_image_bytes(&bytes)),
        ),
        (None, _) => (None, None, Some(LUNARIA_BRAND_SEED_ARGB)),
    };

    let now_secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);

    let album_id = deterministic_album_id(&album, &album_artist);

    Ok(Track {
        id,
        file_path,
        title,
        artist,
        album,
        album_artist,
        album_id,
        duration_ms,
        track_number,
        disc_number,
        year,
        genre,
        folder_path,
        file_name,
        mime_type,
        audio_spec: AudioSpec {
            codec,
            bit_depth,
            sample_rate_hz,
            bitrate_kbps,
            channel_count,
            is_lossless,
        },
        artwork_uri,
        artwork_hash,
        seed_color_argb,
        file_size,
        modified_ns,
        is_favorite: existing_fingerprint.is_some_and(|fp| fp.is_favorite),
        date_added_seconds: now_secs,
        replay_gain_track_db: rg_track_db,
        replay_gain_album_db: rg_album_db,
        replay_gain_track_peak: rg_track_peak,
        replay_gain_album_peak: rg_album_peak,
        clip_start_ms: None,
        clip_end_ms: None,
        cue_source_path: None,
        play_count: existing_fingerprint.map_or(0, |fp| fp.play_count),
        last_played_at_ms: existing_fingerprint.map_or(0, |fp| fp.last_played_at_ms),
        total_play_duration_ms: existing_fingerprint.map_or(0, |fp| fp.total_play_duration_ms),
    })
}

fn classify_file_type(
    ft: FileType,
    ext_lower: &str,
    bit_depth: Option<u8>,
    bitrate_kbps: u32,
) -> (String, String, bool) {
    match ft {
        FileType::Flac => ("FLAC".to_owned(), "audio/flac".to_owned(), true),
        FileType::Wav => ("WAV".to_owned(), "audio/wav".to_owned(), true),
        FileType::Aiff => ("AIFF".to_owned(), "audio/aiff".to_owned(), true),
        FileType::WavPack => ("WV".to_owned(), "audio/wavpack".to_owned(), true),
        FileType::Ape => ("APE".to_owned(), "audio/ape".to_owned(), true),
        FileType::Mpeg => ("MP3".to_owned(), "audio/mpeg".to_owned(), false),
        FileType::Vorbis => ("OGG".to_owned(), "audio/ogg".to_owned(), false),
        FileType::Opus => ("OPUS".to_owned(), "audio/opus".to_owned(), false),
        FileType::Aac => ("AAC".to_owned(), "audio/aac".to_owned(), false),
        FileType::Mpc => ("MPC".to_owned(), "audio/musepack".to_owned(), false),
        FileType::Speex => ("SPX".to_owned(), "audio/speex".to_owned(), false),
        FileType::Mp4 => {
            let looks_alac =
                ext_lower == "alac" || (bit_depth.is_some_and(|b| b >= 16) && bitrate_kbps >= 450);
            if looks_alac {
                ("ALAC".to_owned(), "audio/mp4".to_owned(), true)
            } else {
                ("AAC".to_owned(), "audio/mp4".to_owned(), false)
            }
        }
        _ => (
            ext_lower.to_uppercase(),
            format!("audio/{ext_lower}"),
            false,
        ),
    }
}
