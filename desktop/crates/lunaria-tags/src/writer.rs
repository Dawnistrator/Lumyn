//! Atomic Lofty tag & cover writer with Windows File-Lock Handover (`PlaybackEngine` suspend/resume).

use std::fs;
use std::io::Cursor;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::UNIX_EPOCH;

use image::imageops::FilterType;
use image::{DynamicImage, GenericImageView, ImageFormat};
use lofty::config::WriteOptions;
use lofty::file::{AudioFile, TaggedFileExt};
use lofty::picture::{MimeType, Picture, PictureType};
use lofty::probe::Probe;
use lofty::tag::items::Timestamp;
use lofty::tag::{Accessor, ItemKey, Tag};
use lunaria_audio::PlaybackEngine;
use lunaria_db::{LibraryDb, TrackMetadataUpdate};
use lunaria_model::TrackId;
use lunaria_scanner::{
    LUNARIA_BRAND_SEED_ARGB, cache_artwork_bytes, deterministic_album_id,
    extract_seed_color_from_image_bytes, find_folder_cover_bytes, normalize_display_path,
};

use crate::TagError;

/// Maximum embedded artwork dimension (`1024 px`) enforced before embedding.
const MAX_EMBEDDED_COVER_DIM: u32 = 1_024;

/// Maximum embedded cover image payload size (`4 MiB`), matching Lunaria Android parity.
const MAX_EMBEDDED_COVER_BYTES: usize = 4 * 1024 * 1024;

static TMP_WRITE_SEQ: AtomicU64 = AtomicU64::new(0);

/// Cover image action requested alongside text tag updates.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub enum CoverArtEdit {
    /// Leave any existing embedded front cover untouched.
    #[default]
    KeepExisting,
    /// Embed replacement cover artwork (WebP/BMP/oversized images are normalized to JPEG `<= 1024 px`).
    Replace(Vec<u8>),
    /// Remove embedded `CoverFront` artwork from the container.
    Remove,
}

/// Editable metadata fields applied atomically to an audio file and synchronized into SQLite.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct TagEditRequest {
    /// Updated track title (empty removes title tag).
    pub title: String,
    /// Updated track artist (empty removes artist tag).
    pub artist: String,
    /// Updated album title (empty removes album tag).
    pub album: String,
    /// Updated album artist (empty removes album artist tag).
    pub album_artist: String,
    /// Updated track number (`None` or `Some(0)` removes tag).
    pub track_number: Option<u32>,
    /// Updated disc number (`None` or `Some(0)` removes tag).
    pub disc_number: Option<u32>,
    /// Updated release year (`None` or `Some(0)` removes tag).
    pub year: Option<u32>,
    /// Updated genre string (empty removes genre tag).
    pub genre: String,
    /// Cover artwork mutation policy.
    pub cover_art: CoverArtEdit,
}

/// Summary returned after an atomic tag edit and SQLite/FTS5 synchronization.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TagWriteOutcome {
    /// Edited track ID.
    pub track_id: TrackId,
    /// Updated cached JPEG artwork path (`None` if removed or absent).
    pub artwork_uri: Option<String>,
    /// Updated BLAKE3 hash of artwork payload.
    pub artwork_hash: Option<String>,
    /// Updated Material 3 HCT seed ARGB color.
    pub seed_color_argb: Option<u32>,
    /// Post-write file size in bytes.
    pub file_size: u64,
    /// Post-write file modification timestamp in nanoseconds.
    pub modified_ns: i64,
    /// Whether active `PlaybackEngine` decoding had to be suspended and resumed.
    pub handed_over_playback: bool,
}

/// Prepared cover bytes ready to embed into Lofty plus original normalized image bytes for cache.
struct PreparedDiskWrite {
    file_size: u64,
    modified_ns: i64,
    normalized_cover_bytes: Option<Vec<u8>>,
}

/// Atomically writes `edit` into `file_path` using a temporary sibling copy, read-back verification,
/// and backup-assisted atomic rename safe on Windows NTFS.
pub fn write_tags_to_file(
    file_path: &Path,
    edit: &TagEditRequest,
) -> Result<(u64, i64, Option<Vec<u8>>), TagError> {
    let prepared = write_tags_internal(file_path, edit)?;
    Ok((
        prepared.file_size,
        prepared.modified_ns,
        prepared.normalized_cover_bytes,
    ))
}

/// Executes the full Windows File-Lock Handover Protocol:
/// 1. Looks up `track_id` in `db` (rejecting `.cue` virtual rows).
/// 2. Calls [`PlaybackEngine::suspend_file_handle`] if the audio engine is active.
/// 3. Performs atomic copy -> Lofty tag write -> read-back verify -> Windows-safe swap.
/// 4. Always resumes the audio engine via [`PlaybackEngine::resume_after_suspension`].
/// 5. Updates artwork BLAKE3 cache / HCT seed color and propagates changes into SQLite + FTS5.
pub fn save_track_tags(
    db: &LibraryDb,
    engine: Option<&PlaybackEngine>,
    track_id: TrackId,
    edit: &TagEditRequest,
    artwork_cache_dir: Option<&Path>,
) -> Result<TagWriteOutcome, TagError> {
    let existing = db
        .get_track(track_id)?
        .ok_or(TagError::TrackNotFound(track_id.0))?;

    if existing.id.0 < 0 || existing.cue_source_path.is_some() {
        return Err(TagError::UnsupportedOperation(
            "virtual CUE tracks cannot modify embedded audio file tags".to_owned(),
        ));
    }

    let file_path = PathBuf::from(&existing.file_path);

    let suspension = match engine {
        Some(eng) => Some(eng.suspend_file_handle(Some(&file_path))?),
        None => None,
    };

    let write_result = write_tags_internal(&file_path, edit);

    let resume_result = match (engine, suspension) {
        (Some(eng), Some(susp)) => {
            let was_playing = susp.was_playing;
            eng.resume_after_suspension(susp).map(|()| was_playing)
        }
        _ => Ok(false),
    };

    let prepared = write_result?;
    let handed_over_playback = resume_result?;

    let art_update = resolve_artwork_updates(
        &existing,
        &file_path,
        &edit.cover_art,
        prepared.normalized_cover_bytes.as_deref(),
        artwork_cache_dir,
    )?;

    let clean_title = if edit.title.trim().is_empty() {
        existing.title.clone()
    } else {
        edit.title.trim().to_owned()
    };
    let clean_artist = if edit.artist.trim().is_empty() {
        "Unknown Artist".to_owned()
    } else {
        edit.artist.trim().to_owned()
    };
    let clean_album = if edit.album.trim().is_empty() {
        "Unknown Album".to_owned()
    } else {
        edit.album.trim().to_owned()
    };
    let clean_album_artist = if edit.album_artist.trim().is_empty() {
        clean_artist.clone()
    } else {
        edit.album_artist.trim().to_owned()
    };

    let album_id = deterministic_album_id(&clean_album, &clean_album_artist);
    let patch = TrackMetadataUpdate {
        title: clean_title,
        artist: clean_artist,
        album: clean_album,
        album_artist: clean_album_artist,
        album_id,
        track_number: edit.track_number.unwrap_or(0),
        disc_number: edit.disc_number.unwrap_or(1).max(1),
        year: edit.year.unwrap_or(0),
        genre: edit.genre.trim().to_owned(),
        artwork_uri: art_update.uri_patch,
        artwork_hash: art_update.hash_patch,
        seed_color_argb: art_update.seed_patch,
        file_size: Some(prepared.file_size),
        modified_ns: Some(prepared.modified_ns),
    };
    db.update_track_metadata(track_id, &patch)?;

    Ok(TagWriteOutcome {
        track_id,
        artwork_uri: art_update.out_uri,
        artwork_hash: art_update.out_hash,
        seed_color_argb: art_update.out_seed,
        file_size: prepared.file_size,
        modified_ns: prepared.modified_ns,
        handed_over_playback,
    })
}

fn write_tags_internal(
    file_path: &Path,
    edit: &TagEditRequest,
) -> Result<PreparedDiskWrite, TagError> {
    if !file_path.is_file() {
        return Err(TagError::UnsupportedOperation(format!(
            "target path is not an existing file: {}",
            file_path.display()
        )));
    }
    let parent_dir = file_path.parent().ok_or_else(|| {
        TagError::UnsupportedOperation("target file has no parent directory".to_owned())
    })?;

    let normalized_cover = match &edit.cover_art {
        CoverArtEdit::Replace(raw) => Some(normalize_cover_for_embedding(raw)?),
        CoverArtEdit::KeepExisting | CoverArtEdit::Remove => None,
    };

    let seq = TMP_WRITE_SEQ.fetch_add(1, Ordering::Relaxed);
    let pid = std::process::id();
    let ext = file_path
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("tmp");
    let stem = file_path
        .file_stem()
        .and_then(|s| s.to_str())
        .unwrap_or("track");

    let tmp_path = parent_dir.join(format!(".{stem}.{pid}.{seq}.lunariatmp.{ext}"));
    let bak_path = parent_dir.join(format!(".{stem}.{pid}.{seq}.lunariabak.{ext}"));

    if let Err(err) = fs::copy(file_path, &tmp_path) {
        let _ = fs::remove_file(&tmp_path);
        return Err(TagError::Io(err));
    }

    let apply_result = apply_lofty_edits_to_temp(&tmp_path, edit, normalized_cover.as_ref());
    if let Err(err) = apply_result {
        let _ = fs::remove_file(&tmp_path);
        return Err(err);
    }

    // Read-back verification prior to touching original file on disk.
    if let Err(err) = verify_readable_tags(&tmp_path) {
        let _ = fs::remove_file(&tmp_path);
        return Err(err);
    }

    // Windows atomic replace pattern: move original aside -> move temp in -> remove backup.
    let _ = fs::remove_file(&bak_path);
    if let Err(err) = fs::rename(file_path, &bak_path) {
        let _ = fs::remove_file(&tmp_path);
        return Err(TagError::Io(err));
    }
    if let Err(err) = fs::rename(&tmp_path, file_path) {
        let _ = fs::rename(&bak_path, file_path);
        let _ = fs::remove_file(&tmp_path);
        return Err(TagError::Io(err));
    }
    let _ = fs::remove_file(&bak_path);

    let meta = fs::metadata(file_path)?;
    let modified_ns = meta
        .modified()
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .and_then(|d| i64::try_from(d.as_nanos()).ok())
        .unwrap_or(0);

    Ok(PreparedDiskWrite {
        file_size: meta.len(),
        modified_ns,
        normalized_cover_bytes: normalized_cover.map(|(bytes, _)| bytes),
    })
}

fn apply_lofty_edits_to_temp(
    tmp_path: &Path,
    edit: &TagEditRequest,
    normalized_cover: Option<&(Vec<u8>, MimeType)>,
) -> Result<(), TagError> {
    let mut tagged = Probe::open(tmp_path)?.guess_file_type()?.read()?;

    if tagged.primary_tag().is_none() && tagged.first_tag().is_none() {
        let primary_type = tagged.primary_tag_type();
        let _ = tagged.insert_tag(Tag::new(primary_type));
    }

    let has_primary = tagged.primary_tag().is_some();
    let tag = if has_primary {
        tagged.primary_tag_mut()
    } else {
        tagged.first_tag_mut()
    }
    .ok_or_else(|| {
        TagError::UnsupportedOperation(
            "audio container does not expose a writable metadata tag".to_owned(),
        )
    })?;

    if edit.title.trim().is_empty() {
        tag.remove_title();
    } else {
        tag.set_title(edit.title.trim().to_owned());
    }

    if edit.artist.trim().is_empty() {
        tag.remove_artist();
    } else {
        tag.set_artist(edit.artist.trim().to_owned());
    }

    if edit.album.trim().is_empty() {
        tag.remove_album();
    } else {
        tag.set_album(edit.album.trim().to_owned());
    }

    if edit.genre.trim().is_empty() {
        tag.remove_genre();
    } else {
        tag.set_genre(edit.genre.trim().to_owned());
    }

    if edit.album_artist.trim().is_empty() {
        tag.remove_key(ItemKey::AlbumArtist);
    } else {
        let _ = tag.insert_text(ItemKey::AlbumArtist, edit.album_artist.trim().to_owned());
    }

    match edit.track_number {
        Some(n) if n > 0 => tag.set_track(n),
        _ => tag.remove_track(),
    }
    match edit.disc_number {
        Some(n) if n > 0 => tag.set_disk(n),
        _ => tag.remove_disk(),
    }
    match edit.year {
        Some(y) if (1..=9999).contains(&y) => tag.set_date(Timestamp {
            year: y as u16,
            ..Default::default()
        }),
        _ => tag.remove_date(),
    }

    match (&edit.cover_art, normalized_cover) {
        (CoverArtEdit::KeepExisting, _) => {}
        (CoverArtEdit::Remove, _) => {
            tag.remove_picture_type(PictureType::CoverFront);
        }
        (CoverArtEdit::Replace(_), Some((bytes, mime))) => {
            tag.remove_picture_type(PictureType::CoverFront);
            let pic = Picture::unchecked(bytes.clone())
                .pic_type(PictureType::CoverFront)
                .mime_type(mime.clone())
                .build();
            tag.push_picture(pic);
        }
        (CoverArtEdit::Replace(_), None) => {}
    }

    tagged.save_to_path(tmp_path, WriteOptions::default())?;
    Ok(())
}

fn verify_readable_tags(path: &Path) -> Result<(), TagError> {
    let _ = Probe::open(path)?.guess_file_type()?.read()?;
    Ok(())
}

/// Normalizes WebP/BMP/oversized images into JPEG `<= 1024 px` and enforces the `4 MiB` ceiling.
fn normalize_cover_for_embedding(raw: &[u8]) -> Result<(Vec<u8>, MimeType), TagError> {
    if raw.is_empty() {
        return Err(TagError::UnsupportedOperation(
            "cover image payload is empty".to_owned(),
        ));
    }

    let decoded = image::load_from_memory(raw)?;
    let (w, h) = decoded.dimensions();
    let format = image::guess_format(raw).ok();

    let needs_transcode = match format {
        Some(ImageFormat::Jpeg | ImageFormat::Png) => {
            w > MAX_EMBEDDED_COVER_DIM
                || h > MAX_EMBEDDED_COVER_DIM
                || raw.len() > MAX_EMBEDDED_COVER_BYTES
        }
        _ => true,
    };

    if !needs_transcode {
        let mime = match format {
            Some(ImageFormat::Png) => MimeType::Png,
            _ => MimeType::Jpeg,
        };
        return Ok((raw.to_vec(), mime));
    }

    let resized = if w > MAX_EMBEDDED_COVER_DIM || h > MAX_EMBEDDED_COVER_DIM {
        decoded.resize(
            MAX_EMBEDDED_COVER_DIM,
            MAX_EMBEDDED_COVER_DIM,
            FilterType::Lanczos3,
        )
    } else {
        decoded
    };

    let mut jpeg_buf = Vec::new();
    DynamicImage::ImageRgb8(resized.to_rgb8())
        .write_to(&mut Cursor::new(&mut jpeg_buf), ImageFormat::Jpeg)?;

    if jpeg_buf.len() > MAX_EMBEDDED_COVER_BYTES {
        return Err(TagError::UnsupportedOperation(format!(
            "encoded cover artwork exceeds 4 MiB limit ({} bytes)",
            jpeg_buf.len()
        )));
    }

    Ok((jpeg_buf, MimeType::Jpeg))
}

struct ArtworkUpdateResolution {
    uri_patch: Option<Option<String>>,
    hash_patch: Option<Option<String>>,
    seed_patch: Option<Option<u32>>,
    out_uri: Option<String>,
    out_hash: Option<String>,
    out_seed: Option<u32>,
}

fn resolve_artwork_updates(
    existing: &lunaria_model::Track,
    file_path: &Path,
    cover_edit: &CoverArtEdit,
    new_cover_bytes: Option<&[u8]>,
    artwork_cache_dir: Option<&Path>,
) -> Result<ArtworkUpdateResolution, TagError> {
    match cover_edit {
        CoverArtEdit::KeepExisting => Ok(ArtworkUpdateResolution {
            uri_patch: None,
            hash_patch: None,
            seed_patch: None,
            out_uri: existing.artwork_uri.clone(),
            out_hash: existing.artwork_hash.clone(),
            out_seed: existing.seed_color_argb,
        }),
        CoverArtEdit::Remove => {
            if let Some(folder_bytes) = find_folder_cover_bytes(file_path) {
                cache_or_hash_bytes(&folder_bytes, artwork_cache_dir)
            } else {
                Ok(ArtworkUpdateResolution {
                    uri_patch: Some(None),
                    hash_patch: Some(None),
                    seed_patch: Some(Some(LUNARIA_BRAND_SEED_ARGB)),
                    out_uri: None,
                    out_hash: None,
                    out_seed: Some(LUNARIA_BRAND_SEED_ARGB),
                })
            }
        }
        CoverArtEdit::Replace(_) => {
            if let Some(bytes) = new_cover_bytes {
                cache_or_hash_bytes(bytes, artwork_cache_dir)
            } else {
                Ok(ArtworkUpdateResolution {
                    uri_patch: None,
                    hash_patch: None,
                    seed_patch: None,
                    out_uri: existing.artwork_uri.clone(),
                    out_hash: existing.artwork_hash.clone(),
                    out_seed: existing.seed_color_argb,
                })
            }
        }
    }
}

fn cache_or_hash_bytes(
    bytes: &[u8],
    artwork_cache_dir: Option<&Path>,
) -> Result<ArtworkUpdateResolution, TagError> {
    if let Some(cache_dir) = artwork_cache_dir {
        let cached = cache_artwork_bytes(cache_dir, bytes)?;
        let uri = normalize_display_path(&cached.file_path);
        Ok(ArtworkUpdateResolution {
            uri_patch: Some(Some(uri.clone())),
            hash_patch: Some(Some(cached.hash.clone())),
            seed_patch: Some(Some(cached.seed_color_argb)),
            out_uri: Some(uri),
            out_hash: Some(cached.hash),
            out_seed: Some(cached.seed_color_argb),
        })
    } else {
        let hash = blake3::hash(bytes).to_hex().to_string();
        let seed = extract_seed_color_from_image_bytes(bytes);
        Ok(ArtworkUpdateResolution {
            uri_patch: Some(None),
            hash_patch: Some(Some(hash.clone())),
            seed_patch: Some(Some(seed)),
            out_uri: None,
            out_hash: Some(hash),
            out_seed: Some(seed),
        })
    }
}
