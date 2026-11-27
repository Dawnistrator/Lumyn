//! Equal-power cosine crossfade volume calculator matching Android `CrossfadeCurve`.

use std::f64::consts::PI;

/// Computes equal-power cosine fade-out volume (`0.0..=1.0`) matching Android `CrossfadeCurve`.
#[must_use]
pub fn fade_out_volume(remaining_ms: u64, fade_duration_ms: u64) -> f32 {
    if fade_duration_ms == 0 || remaining_ms >= fade_duration_ms {
        return 1.0;
    }
    if remaining_ms == 0 {
        return 0.0;
    }
    let progress = 1.0 - (remaining_ms as f64 / fade_duration_ms as f64);
    (0.5 * (1.0 + (PI * progress).cos())).clamp(0.0, 1.0) as f32
}

/// Computes equal-power cosine fade-in volume (`0.0..=1.0`) matching Android `CrossfadeCurve`.
#[must_use]
pub fn fade_in_volume(elapsed_ms: u64, fade_duration_ms: u64) -> f32 {
    if fade_duration_ms == 0 || elapsed_ms >= fade_duration_ms {
        return 1.0;
    }
    if elapsed_ms == 0 {
        return 0.0;
    }
    let progress = elapsed_ms as f64 / fade_duration_ms as f64;
    (0.5 * (1.0 - (PI * progress).cos())).clamp(0.0, 1.0) as f32
}
