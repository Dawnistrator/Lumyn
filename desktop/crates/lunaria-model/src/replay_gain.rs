//! Pure-Rust ReplayGain & RFC 7845 R128 parser and clipping-safe gain calculator.

use crate::ReplayGainMode;

/// Conversion offset from RFC 7845 `-23 LUFS` reference to ReplayGain `-18 LUFS` target (+5.0 dB).
pub const R128_TO_REPLAYGAIN_OFFSET_DB: f32 = 5.0;

/// Extracts the first signed decimal number from a tag value string.
fn parse_leading_number(raw: &str) -> Option<f64> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }

    let bytes = trimmed.as_bytes();
    let mut start = None;
    for (idx, &b) in bytes.iter().enumerate() {
        let is_digit = b.is_ascii_digit();
        let is_sign =
            (b == b'+' || b == b'-') && bytes.get(idx + 1).is_some_and(u8::is_ascii_digit);
        if is_digit || is_sign {
            start = Some(idx);
            break;
        }
    }

    let start_idx = start?;
    let mut end_idx = start_idx + 1;
    let mut seen_sep = false;
    while end_idx < bytes.len() {
        let b = bytes[end_idx];
        if b.is_ascii_digit() {
            end_idx += 1;
        } else if (b == b'.' || b == b',')
            && !seen_sep
            && bytes.get(end_idx + 1).is_some_and(u8::is_ascii_digit)
        {
            seen_sep = true;
            end_idx += 1;
        } else {
            break;
        }
    }

    let token = &trimmed[start_idx..end_idx];
    let normalized = token.replace(',', ".");
    normalized.parse::<f64>().ok().filter(|v| v.is_finite())
}

/// Parses a ReplayGain dB string (e.g. `"-6.42 dB"`, `"+1,25"`), clamped to `[-30.0, 30.0]`.
#[must_use]
pub fn parse_gain_db(raw: &str) -> Option<f32> {
    let val = parse_leading_number(raw)? as f32;
    Some(val.clamp(-30.0, 30.0))
}

/// Parses a ReplayGain linear peak value, valid strictly in `(0.0, 8.0]`.
#[must_use]
pub fn parse_peak(raw: &str) -> Option<f32> {
    let val = parse_leading_number(raw)? as f32;
    (val > 0.0 && val <= 8.0).then_some(val)
}

/// Parses RFC 7845 `R128_TRACK_GAIN` / `R128_ALBUM_GAIN` (Q7.8 fixed-point relative to `-23 LUFS`)
/// and converts it to the ReplayGain `-18 LUFS` target reference (`+5.0 dB`).
#[must_use]
pub fn parse_r128_gain_db(raw: &str) -> Option<f32> {
    let int_value = raw.trim().parse::<i32>().ok()?;
    let lufs_delta_db = int_value as f32 / 256.0;
    Some((lufs_delta_db + R128_TO_REPLAYGAIN_OFFSET_DB).clamp(-30.0, 30.0))
}

/// Computes effective ReplayGain adjustment (dB) including manual preamp and peak clipping guard.
///
/// Returns `None` when `mode` is [`ReplayGainMode::Off`].
#[must_use]
pub fn compute_effective_gain_db(
    mode: ReplayGainMode,
    track_gain_db: Option<f32>,
    album_gain_db: Option<f32>,
    track_peak: Option<f32>,
    album_peak: Option<f32>,
    preamp_db: f32,
) -> Option<f32> {
    let (raw_gain, active_peak) = match mode {
        ReplayGainMode::Off => return None,
        ReplayGainMode::Track => (
            track_gain_db.or(album_gain_db).unwrap_or(0.0),
            track_peak.or(album_peak),
        ),
        ReplayGainMode::Album => (
            album_gain_db.or(track_gain_db).unwrap_or(0.0),
            album_peak.or(track_peak),
        ),
    };

    let clean_preamp = if preamp_db.is_finite() {
        preamp_db
    } else {
        0.0
    };
    let clean_gain = if raw_gain.is_finite() { raw_gain } else { 0.0 };
    let desired_gain = clean_gain + clean_preamp;

    let guarded = match active_peak {
        Some(peak) if peak.is_finite() && peak > 0.0 => {
            let max_safe_gain_db = (-20.0 * f64::from(peak).log10()) as f32;
            desired_gain.min(max_safe_gain_db)
        }
        _ => desired_gain,
    };

    Some(guarded.clamp(-24.0, 24.0))
}

/// Returns `true` when usable ReplayGain tag data exists for the requested mode.
#[must_use]
pub const fn has_tag_for_mode(
    mode: ReplayGainMode,
    track_gain_db: Option<f32>,
    album_gain_db: Option<f32>,
) -> bool {
    match mode {
        ReplayGainMode::Off => false,
        ReplayGainMode::Track => track_gain_db.is_some() || album_gain_db.is_some(),
        ReplayGainMode::Album => album_gain_db.is_some() || track_gain_db.is_some(),
    }
}
