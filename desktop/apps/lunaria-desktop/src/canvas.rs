//! Pure-Rust offscreen Slint pixel buffers for the 64-bit EQ Bode Curve and
//! safe local album artwork image loading.

use std::path::Path;

use image::{DynamicImage, GenericImageView, imageops::FilterType};
use lunaria_dsp::{BiquadDspEngine, DspConfigSnapshot};
use lunaria_model::EqualizerSettings;
use slint::{Rgba8Pixel, SharedPixelBuffer};

use crate::theme::{LunariaMaterialPalette, argb_to_rgba};

/// Renders the cascaded 64-bit frequency response curve (`20 Hz..20 kHz`, `-15..+15 dB`)
/// into a crisp RGBA Slint [`slint::Image`].
#[must_use]
pub fn render_bode_curve_image(
    eq: &EqualizerSettings,
    sample_rate_hz: u32,
    width: u32,
    height: u32,
    palette: &LunariaMaterialPalette,
) -> slint::Image {
    let w = width.clamp(120, 1_400);
    let h = height.clamp(60, 480);
    let mut buf = SharedPixelBuffer::<Rgba8Pixel>::new(w, h);
    let pixels = buf.make_mut_slice();

    let bg = argb_to_rgba(palette.surface_container_lowest);
    let grid = argb_to_rgba(palette.outline_variant);
    let zero_line = argb_to_rgba(palette.outline);
    let stroke = if eq.enabled {
        argb_to_rgba(palette.primary)
    } else {
        argb_to_rgba(palette.outline)
    };
    let mut fill_col = stroke;
    fill_col[3] = if eq.enabled { 56 } else { 24 };

    let bg_px = Rgba8Pixel::new(bg[0], bg[1], bg[2], 255);
    pixels.fill(bg_px);

    let w_usize = w as usize;
    let h_usize = h as usize;

    // Horizontal reference grid lines (-12, -6, 0, +6, +12 dB)
    for db_mark in [-12.0_f64, -6.0, 0.0, 6.0, 12.0] {
        let y = db_to_y(db_mark, h);
        let col = if db_mark.abs() < 0.01 {
            zero_line
        } else {
            grid
        };
        let alpha = if db_mark.abs() < 0.01 { 210 } else { 110 };
        draw_hline(pixels, w_usize, h_usize, y, [col[0], col[1], col[2], alpha]);
    }

    // Vertical logarithmic frequency markers (100 Hz, 1 kHz, 10 kHz)
    for freq in [100.0_f64, 1_000.0, 10_000.0] {
        let x = freq_to_x(freq, w);
        draw_vline(
            pixels,
            w_usize,
            h_usize,
            x,
            [grid[0], grid[1], grid[2], 125],
        );
    }

    // Evaluate analytical DSP magnitude curve without bypass flag so user still previews shape
    let mut dsp = BiquadDspEngine::new();
    let effective_rate = sample_rate_hz.clamp(44_100, 384_000);
    let _ = dsp.configure(effective_rate, 2);
    let preview_settings = EqualizerSettings {
        enabled: true,
        ..eq.clone()
    };
    dsp.update_from_snapshot(&DspConfigSnapshot {
        version: 1,
        settings: preview_settings,
        bypassed: false,
        ..DspConfigSnapshot::default()
    });

    let zero_y = db_to_y(0.0, h) as i32;
    let mut curve_ys = Vec::with_capacity(w_usize);

    for x in 0..w {
        let t = f64::from(x) / f64::from((w - 1).max(1));
        let freq_hz = 20.0 * (1_000.0_f64).powf(t);
        let db = dsp
            .compute_magnitude_response_db(freq_hz)
            .clamp(-15.0, 15.0);
        let y = db_to_y(db, h) as i32;
        curve_ys.push(y);

        // Translucent tint between 0 dB centerline and curve
        let (y_min, y_max) = if y < zero_y { (y, zero_y) } else { (zero_y, y) };
        for py in y_min..=y_max {
            blend_pixel(pixels, w_usize, h_usize, x as i32, py, fill_col);
        }
    }

    // Connect adjacent sample columns with crisp anti-aliased thickness
    for x in 1..w_usize {
        let y0 = curve_ys[x - 1];
        let y1 = curve_ys[x];
        draw_thick_segment(
            pixels,
            w_usize,
            h_usize,
            (x - 1) as i32,
            y0,
            x as i32,
            y1,
            stroke,
        );
    }

    slint::Image::from_rgba8(buf)
}

/// Pre-filtered hero (`272px` logical) and mini transport (`48px` logical) artwork bitmaps.
#[derive(Debug, Default, Clone)]
pub struct LoadedArtwork {
    /// Sharply downsampled hero cover image for the Now Playing pane.
    pub hero: slint::Image,
    /// Sharply downsampled mini cover image for the transport bar thumbnail.
    pub mini: slint::Image,
}

fn downsample_for_target(decoded: &DynamicImage, target_px: u32) -> slint::Image {
    let limit = target_px.clamp(32, 1_024);
    let (w, h) = decoded.dimensions();
    let rgba = if w > limit || h > limit {
        decoded
            .resize(limit, limit, FilterType::Lanczos3)
            .to_rgba8()
    } else {
        decoded.to_rgba8()
    };
    let (out_w, out_h) = rgba.dimensions();
    let buf = SharedPixelBuffer::<Rgba8Pixel>::clone_from_slice(rgba.as_raw(), out_w, out_h);
    slint::Image::from_rgba8(buf)
}

/// Loads local artwork cleanly into pre-filtered hero and mini Slint [`slint::Image`] variants,
/// supporting JPEG, PNG, WebP, and BMP without GPU mipmap aliasing.
#[must_use]
pub fn load_artwork_variants(
    uri: Option<&str>,
    hero_target_px: u32,
    mini_target_px: u32,
) -> LoadedArtwork {
    let Some(raw) = uri.map(str::trim).filter(|s| !s.is_empty()) else {
        return LoadedArtwork::default();
    };
    let path = Path::new(raw);
    if !path.is_file() {
        return LoadedArtwork::default();
    }
    if let Ok(decoded) = image::open(path) {
        return LoadedArtwork {
            hero: downsample_for_target(&decoded, hero_target_px),
            mini: downsample_for_target(&decoded, mini_target_px),
        };
    }
    if let Ok(img) = slint::Image::load_from_path(path) {
        return LoadedArtwork {
            hero: img.clone(),
            mini: img,
        };
    }
    LoadedArtwork::default()
}

fn db_to_y(db: f64, height: u32) -> u32 {
    let clamped = db.clamp(-15.0, 15.0);
    let norm = (15.0 - clamped) / 30.0;
    let margin = 8.0;
    let span = (f64::from(height) - margin * 2.0).max(1.0);
    (margin + norm * span)
        .round()
        .clamp(0.0, f64::from(height - 1)) as u32
}

fn freq_to_x(freq_hz: f64, width: u32) -> u32 {
    let t = (freq_hz.clamp(20.0, 20_000.0) / 20.0).log(1_000.0);
    (t * f64::from((width - 1).max(1)))
        .round()
        .clamp(0.0, f64::from(width - 1)) as u32
}

fn draw_hline(pixels: &mut [Rgba8Pixel], w: usize, h: usize, y: u32, rgba: [u8; 4]) {
    let yi = y as i32;
    for x in 0..w as i32 {
        blend_pixel(pixels, w, h, x, yi, rgba);
    }
}

fn draw_vline(pixels: &mut [Rgba8Pixel], w: usize, h: usize, x: u32, rgba: [u8; 4]) {
    let xi = x as i32;
    for y in 0..h as i32 {
        blend_pixel(pixels, w, h, xi, y, rgba);
    }
}

#[expect(
    clippy::too_many_arguments,
    reason = "rasterizes single 2px curve segment without temporary vec allocations"
)]
fn draw_thick_segment(
    pixels: &mut [Rgba8Pixel],
    w: usize,
    h: usize,
    x0: i32,
    y0: i32,
    x1: i32,
    y1: i32,
    rgba: [u8; 4],
) {
    let steps = (x1 - x0).abs().max((y1 - y0).abs()).max(1);
    for step in 0..=steps {
        let t = (step as f32) / (steps as f32);
        let px = ((x0 as f32) + ((x1 - x0) as f32) * t).round() as i32;
        let py = ((y0 as f32) + ((y1 - y0) as f32) * t).round() as i32;
        for dy in -1..=1 {
            let alpha = if dy == 0 { 255 } else { 140 };
            blend_pixel(
                pixels,
                w,
                h,
                px,
                py + dy,
                [rgba[0], rgba[1], rgba[2], alpha],
            );
        }
    }
}

fn blend_pixel(pixels: &mut [Rgba8Pixel], w: usize, h: usize, x: i32, y: i32, src: [u8; 4]) {
    if x < 0 || y < 0 || (x as usize) >= w || (y as usize) >= h || src[3] == 0 {
        return;
    }
    let idx = (y as usize) * w + (x as usize);
    let dst = &mut pixels[idx];
    let a = u16::from(src[3]);
    if a == 255 {
        *dst = Rgba8Pixel::new(src[0], src[1], src[2], 255);
        return;
    }
    let inv = 255 - a;
    let r = ((u16::from(src[0]) * a + u16::from(dst.r) * inv) / 255) as u8;
    let g = ((u16::from(src[1]) * a + u16::from(dst.g) * inv) / 255) as u8;
    let b = ((u16::from(src[2]) * a + u16::from(dst.b) * inv) / 255) as u8;
    let out_a = dst.a.max(src[3]);
    *dst = Rgba8Pixel::new(r, g, b, out_a);
}

#[cfg(test)]
mod tests {
    use image::{ImageBuffer, Rgba};
    use lunaria_model::{BUILT_IN_EQ_PRESETS, ParametricFilter, ParametricFilterType};
    use tempfile::tempdir;

    use crate::theme::{LUNARIA_BRAND_SEED_ARGB, build_palette_from_seed};

    use super::*;

    type TestResult<T = ()> = Result<T, Box<dyn std::error::Error>>;

    #[test]
    fn bode_curve_renders_expected_dimensions_and_responds_to_eq_and_autoeq() {
        let palette = build_palette_from_seed(LUNARIA_BRAND_SEED_ARGB, true, 0.0);

        let flat =
            render_bode_curve_image(&EqualizerSettings::default(), 48_000, 480, 128, &palette);
        assert_eq!(flat.size().width, 480);
        assert_eq!(flat.size().height, 128);

        let bass_preset = BUILT_IN_EQ_PRESETS[1];
        let boosted_settings = EqualizerSettings {
            enabled: true,
            preamp_db: bass_preset.preamp_db,
            band_gains_db: bass_preset.band_gains_db,
            parametric_filters: vec![ParametricFilter {
                frequency_hz: 1_500.0,
                gain_db: 5.5,
                q_factor: 1.4,
                filter_type: ParametricFilterType::Peaking,
                enabled: true,
            }],
            active_preset_name: "Bass + AutoEQ".to_owned(),
            ..EqualizerSettings::default()
        };
        let boosted = render_bode_curve_image(&boosted_settings, 96_000, 480, 128, &palette);
        assert_eq!(boosted.size().width, 480);

        let flat_bytes = flat.to_rgba8().map(|b| b.as_bytes().to_vec());
        let boosted_bytes = boosted.to_rgba8().map(|b| b.as_bytes().to_vec());
        assert!(flat_bytes.is_some());
        assert!(boosted_bytes.is_some());
        assert_ne!(flat_bytes, boosted_bytes);
    }

    #[test]
    fn artwork_variants_downsample_sharply_and_preserve_aspect_ratio() -> TestResult {
        let dir = tempdir()?;

        let square_path = dir.path().join("square_1024.png");
        ImageBuffer::<Rgba<u8>, Vec<u8>>::from_pixel(1_024, 1_024, Rgba([14, 97, 120, 255]))
            .save(&square_path)?;
        let sq = load_artwork_variants(square_path.to_str(), 272, 48);
        assert_eq!(sq.hero.size().width, 272);
        assert_eq!(sq.hero.size().height, 272);
        assert_eq!(sq.mini.size().width, 48);
        assert_eq!(sq.mini.size().height, 48);

        let wide_path = dir.path().join("wide_800x400.png");
        ImageBuffer::<Rgba<u8>, Vec<u8>>::from_pixel(800, 400, Rgba([90, 40, 140, 255]))
            .save(&wide_path)?;
        let wide = load_artwork_variants(wide_path.to_str(), 272, 48);
        assert_eq!(wide.hero.size().width, 272);
        assert_eq!(wide.hero.size().height, 136);
        assert_eq!(wide.mini.size().width, 48);
        assert_eq!(wide.mini.size().height, 24);

        let small_path = dir.path().join("small_128.png");
        ImageBuffer::<Rgba<u8>, Vec<u8>>::from_pixel(128, 128, Rgba([220, 180, 60, 255]))
            .save(&small_path)?;
        let small = load_artwork_variants(small_path.to_str(), 272, 48);
        assert_eq!(small.hero.size().width, 128);
        assert_eq!(small.hero.size().height, 128);
        assert_eq!(small.mini.size().width, 48);
        assert_eq!(small.mini.size().height, 48);

        let missing_path = dir.path().join("missing.png");
        let missing = load_artwork_variants(missing_path.to_str(), 272, 48);
        assert_eq!(missing.hero.size().width, 0);
        assert_eq!(missing.mini.size().width, 0);

        let none_art = load_artwork_variants(None, 272, 48);
        assert_eq!(none_art.hero.size().width, 0);
        assert_eq!(none_art.mini.size().width, 0);

        Ok(())
    }
}
