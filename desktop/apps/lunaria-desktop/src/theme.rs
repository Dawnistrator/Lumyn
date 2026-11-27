//! Material 3 HCT palette generator matching Lunaria Android's `AlbumArtColorScheme.kt`.

use mcu_material_color::{DynamicScheme, Hct, MaterialDynamicColors, SchemeTonalSpot};

/// Fallback seed color matching Android `LunariaBrandSeedArgb` (`#32428A`).
pub const LUNARIA_BRAND_SEED_ARGB: u32 = 0xFF32_428A;

/// Preset seed color swatch used for live dynamic M3 theme testing in Milestone 0.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SeedSwatch {
    /// Human-readable swatch label.
    pub name: &'static str,
    /// Seed ARGB integer (`0xAARRGGBB`).
    pub argb: u32,
}

/// Curated studio swatches including Lunaria's native Lunar Indigo seed.
pub const PRESET_SEED_SWATCHES: [SeedSwatch; 4] = [
    SeedSwatch {
        name: "Lunar Indigo",
        argb: LUNARIA_BRAND_SEED_ARGB,
    },
    SeedSwatch {
        name: "Obsidian Teal",
        argb: 0xFF0E_6178,
    },
    SeedSwatch {
        name: "Amber Vinyl",
        argb: 0xFFB8_5D19,
    },
    SeedSwatch {
        name: "Forest Studio",
        argb: 0xFF1B_5E20,
    },
];

/// Full Material 3 color role set generated from a single ARGB seed via `SchemeTonalSpot`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LunariaMaterialPalette {
    /// Primary interactive accent color.
    pub primary: u32,
    /// Foreground color over [`Self::primary`].
    pub on_primary: u32,
    /// Tonal container derived from primary.
    pub primary_container: u32,
    /// Foreground color over [`Self::primary_container`].
    pub on_primary_container: u32,
    /// Secondary supporting accent color.
    pub secondary: u32,
    /// Foreground color over [`Self::secondary`].
    pub on_secondary: u32,
    /// Tonal container for active navigation items and toggles.
    pub secondary_container: u32,
    /// Foreground color over [`Self::secondary_container`].
    pub on_secondary_container: u32,
    /// Tertiary accent color.
    pub tertiary: u32,
    /// Base window surface color.
    pub surface: u32,
    /// Lowest elevation surface container.
    pub surface_container_lowest: u32,
    /// Low elevation surface container.
    pub surface_container_low: u32,
    /// Default elevation surface container.
    pub surface_container: u32,
    /// High elevation surface container.
    pub surface_container_high: u32,
    /// Highest elevation surface container.
    pub surface_container_highest: u32,
    /// Primary body text and icon color on surfaces.
    pub on_surface: u32,
    /// Muted supporting text and caption color on surfaces.
    pub on_surface_variant: u32,
    /// Accessible error background or foreground accent color.
    pub error: u32,
    /// Foreground color over [`Self::error`].
    pub on_error: u32,
    /// Tonal error container fill color.
    pub error_container: u32,
    /// Foreground color over [`Self::error_container`].
    pub on_error_container: u32,
    /// Accessible interactive border color.
    pub outline: u32,
    /// Subtle hairline divider color.
    pub outline_variant: u32,
    /// Whether this palette targets a dark theme.
    pub is_dark: bool,
}

/// Generates a full Material 3 `ColorScheme` from `seed_argb` using `SchemeTonalSpot`.
#[must_use]
pub fn build_palette_from_seed(
    seed_argb: u32,
    dark_theme: bool,
    contrast_level: f64,
) -> LunariaMaterialPalette {
    let scheme_spot = SchemeTonalSpot::new(Hct::from_int(seed_argb), dark_theme, contrast_level);
    let scheme: &DynamicScheme = &scheme_spot;

    LunariaMaterialPalette {
        primary: MaterialDynamicColors::primary().get_argb(scheme),
        on_primary: MaterialDynamicColors::on_primary().get_argb(scheme),
        primary_container: MaterialDynamicColors::primary_container().get_argb(scheme),
        on_primary_container: MaterialDynamicColors::on_primary_container().get_argb(scheme),
        secondary: MaterialDynamicColors::secondary().get_argb(scheme),
        on_secondary: MaterialDynamicColors::on_secondary().get_argb(scheme),
        secondary_container: MaterialDynamicColors::secondary_container().get_argb(scheme),
        on_secondary_container: MaterialDynamicColors::on_secondary_container().get_argb(scheme),
        tertiary: MaterialDynamicColors::tertiary().get_argb(scheme),
        surface: MaterialDynamicColors::surface().get_argb(scheme),
        surface_container_lowest: MaterialDynamicColors::surface_container_lowest()
            .get_argb(scheme),
        surface_container_low: MaterialDynamicColors::surface_container_low().get_argb(scheme),
        surface_container: MaterialDynamicColors::surface_container().get_argb(scheme),
        surface_container_high: MaterialDynamicColors::surface_container_high().get_argb(scheme),
        surface_container_highest: MaterialDynamicColors::surface_container_highest()
            .get_argb(scheme),
        on_surface: MaterialDynamicColors::on_surface().get_argb(scheme),
        on_surface_variant: MaterialDynamicColors::on_surface_variant().get_argb(scheme),
        error: MaterialDynamicColors::error().get_argb(scheme),
        on_error: MaterialDynamicColors::on_error().get_argb(scheme),
        error_container: MaterialDynamicColors::error_container().get_argb(scheme),
        on_error_container: MaterialDynamicColors::on_error_container().get_argb(scheme),
        outline: MaterialDynamicColors::outline().get_argb(scheme),
        outline_variant: MaterialDynamicColors::outline_variant().get_argb(scheme),
        is_dark: dark_theme,
    }
}

/// Converts a 32-bit `0xAARRGGBB` integer to a [`slint::Color`].
#[must_use]
pub fn argb_to_slint_color(argb: u32) -> slint::Color {
    let a = ((argb >> 24) & 0xFF) as u8;
    let r = ((argb >> 16) & 0xFF) as u8;
    let g = ((argb >> 8) & 0xFF) as u8;
    let b = (argb & 0xFF) as u8;
    slint::Color::from_argb_u8(a, r, g, b)
}

/// Converts a 32-bit `0xAARRGGBB` integer into RGBA bytes `[r, g, b, a]` for pixel canvas drawing.
#[must_use]
pub const fn argb_to_rgba(argb: u32) -> [u8; 4] {
    let a = ((argb >> 24) & 0xFF) as u8;
    let r = ((argb >> 16) & 0xFF) as u8;
    let g = ((argb >> 8) & 0xFF) as u8;
    let b = (argb & 0xFF) as u8;
    [r, g, b, a]
}

/// Formats an ARGB integer as `#RRGGBB` for telemetry logs and UI swatches.
#[must_use]
pub fn format_rgb_hex(argb: u32) -> String {
    format!("#{:06X}", argb & 0x00FF_FFFF)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn relative_luminance_approx(argb: u32) -> u32 {
        let r = (argb >> 16) & 0xFF;
        let g = (argb >> 8) & 0xFF;
        let b = argb & 0xFF;
        (r * 2126 + g * 7152 + b * 722) / 10_000
    }

    #[test]
    fn dark_and_light_schemes_invert_surface_and_text_luminance() {
        let dark = build_palette_from_seed(LUNARIA_BRAND_SEED_ARGB, true, 0.0);
        let light = build_palette_from_seed(LUNARIA_BRAND_SEED_ARGB, false, 0.0);

        assert!(dark.is_dark);
        assert!(!light.is_dark);
        assert!(relative_luminance_approx(dark.surface) < 40);
        assert!(relative_luminance_approx(light.surface) > 210);
        assert!(
            relative_luminance_approx(dark.on_surface) > relative_luminance_approx(dark.surface)
        );
        assert!(
            relative_luminance_approx(light.on_surface) < relative_luminance_approx(light.surface)
        );
    }

    fn srgb_luminance(argb: u32) -> f64 {
        let to_linear = |ch: u32| {
            let s = f64::from(ch & 0xFF) / 255.0;
            if s <= 0.03928 {
                s / 12.92
            } else {
                ((s + 0.055) / 1.055).powf(2.4)
            }
        };
        0.2126 * to_linear(argb >> 16) + 0.7152 * to_linear(argb >> 8) + 0.0722 * to_linear(argb)
    }

    fn wcag_contrast_ratio(fg: u32, bg: u32) -> f64 {
        let l1 = srgb_luminance(fg);
        let l2 = srgb_luminance(bg);
        let (hi, lo) = if l1 >= l2 { (l1, l2) } else { (l2, l1) };
        (hi + 0.05) / (lo + 0.05)
    }

    #[test]
    fn distinct_seed_colors_produce_distinct_primary_roles() {
        let teal = build_palette_from_seed(PRESET_SEED_SWATCHES[0].argb, true, 0.0);
        let amber = build_palette_from_seed(PRESET_SEED_SWATCHES[1].argb, true, 0.0);

        assert_ne!(teal.primary, amber.primary);
        assert_ne!(teal.primary_container, amber.primary_container);
    }

    #[test]
    fn wcag_aa_contrast_holds_for_all_theme_roles_and_swatches() {
        for swatch in PRESET_SEED_SWATCHES {
            for dark in [true, false] {
                let p = build_palette_from_seed(swatch.argb, dark, 0.0);
                for (name, fg, bg, min_ratio) in [
                    ("on_surface / surface", p.on_surface, p.surface, 4.5),
                    (
                        "on_surface_variant / surface_container",
                        p.on_surface_variant,
                        p.surface_container,
                        4.5,
                    ),
                    (
                        "on_surface_variant / surface_container_low",
                        p.on_surface_variant,
                        p.surface_container_low,
                        4.5,
                    ),
                    (
                        "on_primary_container / primary_container",
                        p.on_primary_container,
                        p.primary_container,
                        4.5,
                    ),
                    (
                        "on_secondary_container / secondary_container",
                        p.on_secondary_container,
                        p.secondary_container,
                        4.5,
                    ),
                    ("on_error / error", p.on_error, p.error, 4.5),
                    (
                        "on_error_container / error_container",
                        p.on_error_container,
                        p.error_container,
                        4.5,
                    ),
                    (
                        "outline border / surface_container_low",
                        p.outline,
                        p.surface_container_low,
                        3.0,
                    ),
                    (
                        "outline border / surface_container_high",
                        p.outline,
                        p.surface_container_high,
                        3.0,
                    ),
                ] {
                    let ratio = wcag_contrast_ratio(fg, bg);
                    assert!(
                        ratio >= min_ratio,
                        "swatch={} dark={dark} pair={name} failed WCAG AA ({ratio:.2} < {min_ratio:.2})",
                        swatch.name
                    );
                }
            }
        }
    }
}
