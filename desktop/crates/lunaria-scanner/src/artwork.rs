//! Album artwork BLAKE3 deduplicated disk caching and Material 3 HCT seed extraction.

use std::fs::{self, File};
use std::io::{Cursor, Read};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

use image::imageops::FilterType;
use image::{DynamicImage, GenericImageView, ImageFormat};
use mcu_material_color::quantize::QuantizerCelebi;
use mcu_material_color::score::{Score, ScoreOptions};

use crate::ScannerError;

/// Lunaria Lunar Indigo fallback Material 3 seed color (`#FF0E6178`), used for monochrome/missing art.
pub const LUNARIA_BRAND_SEED_ARGB: u32 = 0xFF32_428A;

/// Maximum pixel dimension (width or height) stored in the persistent JPEG artwork cache.
pub const MAX_ARTWORK_CACHE_DIMENSION: u32 = 1_024;

/// Downsampled square pixel grid size passed into Material Color Utilities Celebi quantizer.
const QUANTIZE_DIMENSION: u32 = 128;

/// Safety cap for folder cover images read from disk (`16 MiB`).
const MAX_FOLDER_COVER_BYTES: u64 = 16 * 1024 * 1024;

static TEMP_FILE_SEQ: AtomicU64 = AtomicU64::new(0);

/// Persistent cached artwork output alongside its deduplicated BLAKE3 digest and M3 seed color.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CachedArtwork {
    /// Absolute path to the cached resized JPEG file.
    pub file_path: PathBuf,
    /// Lowercase hex BLAKE3 hash of the source artwork payload.
    pub hash: String,
    /// Extracted Material 3 HCT seed ARGB color (falls back to [`LUNARIA_BRAND_SEED_ARGB`]).
    pub seed_color_argb: u32,
}

/// Extracts a dynamic Material 3 HCT seed color (`0xAARRGGBB`) from in-memory image bytes.
///
/// Mirrors Lunaria Android's `AlbumArtColorScheme`: downsamples artwork to a compact pixel grid,
/// quantizes via `QuantizerCelebi`, and selects the top scored chromatic swatch with
/// [`LUNARIA_BRAND_SEED_ARGB`] fallback when the cover is neutral grey, dark, or undecodable.
#[must_use]
pub fn extract_seed_color_from_image_bytes(raw_bytes: &[u8]) -> u32 {
    let Ok(img) = image::load_from_memory(raw_bytes) else {
        return LUNARIA_BRAND_SEED_ARGB;
    };
    extract_seed_color_from_dynamic_image(&img)
}

/// Extracts a Material 3 HCT seed color from an already-decoded [`DynamicImage`].
#[must_use]
pub fn extract_seed_color_from_dynamic_image(img: &DynamicImage) -> u32 {
    let (w, h) = img.dimensions();
    if w == 0 || h == 0 {
        return LUNARIA_BRAND_SEED_ARGB;
    }

    let thumb = if w > QUANTIZE_DIMENSION || h > QUANTIZE_DIMENSION {
        img.resize(QUANTIZE_DIMENSION, QUANTIZE_DIMENSION, FilterType::Triangle)
    } else {
        img.clone()
    };

    let rgba = thumb.to_rgba8();
    let mut argb_pixels = Vec::with_capacity((rgba.width() * rgba.height()) as usize);
    for px in rgba.pixels() {
        let [r, g, b, a] = px.0;
        if a >= 128 {
            let argb = 0xFF00_0000_u32 | (u32::from(r) << 16) | (u32::from(g) << 8) | u32::from(b);
            argb_pixels.push(argb);
        }
    }

    if argb_pixels.is_empty() {
        return LUNARIA_BRAND_SEED_ARGB;
    }

    let histogram = QuantizerCelebi::quantize(&argb_pixels, 128);
    let options = ScoreOptions::default()
        .with_desired(1)
        .with_fallback_color(LUNARIA_BRAND_SEED_ARGB)
        .with_filter(true);

    Score::score_options(&histogram, options)
        .into_iter()
        .next()
        .unwrap_or(LUNARIA_BRAND_SEED_ARGB)
}

/// Deduplicates `raw_bytes` by BLAKE3 hash inside `cache_dir`, writing a resized JPEG (`<= 1024px`)
/// and a companion `.seed` file atomically for lock-free parallel Rayon workers.
pub fn cache_artwork_bytes(
    cache_dir: &Path,
    raw_bytes: &[u8],
) -> Result<CachedArtwork, ScannerError> {
    if raw_bytes.is_empty() {
        return Err(ScannerError::InvalidInput(
            "artwork payload is empty".to_owned(),
        ));
    }
    fs::create_dir_all(cache_dir)?;

    let hash = blake3::hash(raw_bytes).to_hex().to_string();
    let jpg_path = cache_dir.join(format!("{hash}.jpg"));
    let seed_path = cache_dir.join(format!("{hash}.seed"));

    // Fast deduplicated hit: skip decode, resize, and Celebi quantization across album tracks.
    if jpg_path.is_file()
        && let Ok(seed_text) = fs::read_to_string(&seed_path)
        && let Ok(seed) = u32::from_str_radix(seed_text.trim(), 16)
    {
        return Ok(CachedArtwork {
            file_path: jpg_path,
            hash,
            seed_color_argb: seed,
        });
    }

    let decoded = image::load_from_memory(raw_bytes)?;
    let seed_color_argb = extract_seed_color_from_dynamic_image(&decoded);

    let (w, h) = decoded.dimensions();
    let resized = if w > MAX_ARTWORK_CACHE_DIMENSION || h > MAX_ARTWORK_CACHE_DIMENSION {
        decoded.resize(
            MAX_ARTWORK_CACHE_DIMENSION,
            MAX_ARTWORK_CACHE_DIMENSION,
            FilterType::Lanczos3,
        )
    } else {
        decoded
    };

    let rgb = resized.to_rgb8();
    let mut jpeg_buf = Vec::new();
    DynamicImage::ImageRgb8(rgb).write_to(&mut Cursor::new(&mut jpeg_buf), ImageFormat::Jpeg)?;

    write_file_atomic_if_missing(cache_dir, &jpg_path, &jpeg_buf)?;
    let seed_hex = format!("{seed_color_argb:08X}");
    write_file_atomic_if_missing(cache_dir, &seed_path, seed_hex.as_bytes())?;

    Ok(CachedArtwork {
        file_path: jpg_path,
        hash,
        seed_color_argb,
    })
}

/// Searches the audio track's parent directory for external cover files (`cover.*`, `folder.*`,
/// `front.*`, `albumart.*`, `album.*`).
#[must_use]
pub fn find_folder_cover_bytes(audio_path: &Path) -> Option<Vec<u8>> {
    const STEMS: &[&str] = &["cover", "folder", "front", "albumart", "album"];
    const EXTS: &[&str] = &["jpg", "jpeg", "png", "webp", "bmp"];

    let dir = audio_path.parent()?;
    for stem in STEMS {
        for ext in EXTS {
            let candidate = dir.join(format!("{stem}.{ext}"));
            if candidate.is_file()
                && let Ok(meta) = fs::metadata(&candidate)
                && meta.len() > 0
                && meta.len() <= MAX_FOLDER_COVER_BYTES
                && let Ok(mut file) = File::open(&candidate)
            {
                let mut buf = Vec::with_capacity(meta.len() as usize);
                if file.read_to_end(&mut buf).is_ok() {
                    return Some(buf);
                }
            }
        }
    }
    None
}

fn write_file_atomic_if_missing(dir: &Path, dest: &Path, bytes: &[u8]) -> Result<(), ScannerError> {
    if dest.is_file() {
        return Ok(());
    }
    let seq = TEMP_FILE_SEQ.fetch_add(1, Ordering::Relaxed);
    let pid = std::process::id();
    let tmp_name = format!(".tmp_{pid}_{seq}.part");
    let tmp_path = dir.join(tmp_name);

    fs::write(&tmp_path, bytes)?;
    if let Err(err) = fs::rename(&tmp_path, dest) {
        let _ = fs::remove_file(&tmp_path);
        // Another parallel Rayon worker may have won the atomic rename race for identical BLAKE3.
        if dest.is_file() {
            return Ok(());
        }
        return Err(ScannerError::Io(err));
    }
    Ok(())
}
