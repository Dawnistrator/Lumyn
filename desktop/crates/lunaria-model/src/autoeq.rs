//! Pure-Rust AutoEQ `.txt` parser supporting ParametricEQ and GraphicEQ exports.

use crate::{
    EQ_MAX_GAIN_DB, EQ_MIN_GAIN_DB, ParametricFilter, ParametricFilterType,
    STANDARD_EQ_FREQUENCIES_HZ,
};

/// Parsed AutoEQ headphone/IEM correction profile.
#[derive(Debug, Clone, PartialEq)]
pub struct AutoEqProfile {
    /// Clean profile name derived from filename or fallback label.
    pub name: String,
    /// Preamp attenuation/boost in dB.
    pub preamp_db: f32,
    /// Interpolated 10-band gains when parsed from `GraphicEQ:` format.
    pub band_gains_db: [f32; 10],
    /// Full parametric filters when parsed from `Filter N:` format.
    pub parametric_filters: Vec<ParametricFilter>,
}

fn parse_clean_f64(raw: &str) -> Option<f64> {
    raw.trim()
        .replace(',', ".")
        .parse::<f64>()
        .ok()
        .filter(|v| v.is_finite())
}

fn strip_trailing_unit<'a>(token: &'a str, unit_ci: &str) -> &'a str {
    let trimmed = token.trim();
    if trimmed.len() >= unit_ci.len() {
        let split_at = trimmed.len() - unit_ci.len();
        if trimmed.is_char_boundary(split_at) && trimmed[split_at..].eq_ignore_ascii_case(unit_ci) {
            return trimmed[..split_at].trim();
        }
    }
    trimmed
}

fn parse_preamp_line(line: &str) -> Option<f32> {
    let rest = line.strip_prefix("Preamp:")?.trim();
    let num_token = strip_trailing_unit(rest.split_whitespace().next()?, "db");
    let val = parse_clean_f64(num_token)? as f32;
    Some(val.clamp(EQ_MIN_GAIN_DB, EQ_MAX_GAIN_DB))
}

fn parse_preamp_ci(line: &str) -> Option<f32> {
    let prefix = "Preamp:";
    if line.len() < prefix.len() || !line.get(..prefix.len())?.eq_ignore_ascii_case(prefix) {
        return None;
    }
    let normalized = format!("Preamp:{}", &line[prefix.len()..]);
    parse_preamp_line(&normalized)
}

fn parse_filter_line(line: &str) -> Option<ParametricFilter> {
    let tokens: Vec<&str> = line.split_whitespace().collect();
    if tokens.len() < 9 || !tokens[0].eq_ignore_ascii_case("Filter") {
        return None;
    }

    // tokens[1] is "N:"
    let num_part = tokens[1].strip_suffix(':')?;
    if num_part.is_empty() || !num_part.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }

    let enabled = if tokens[2].eq_ignore_ascii_case("ON") {
        true
    } else if tokens[2].eq_ignore_ascii_case("OFF") {
        false
    } else {
        return None;
    };

    let filter_type =
        if tokens[3].eq_ignore_ascii_case("LSC") || tokens[3].eq_ignore_ascii_case("LS") {
            ParametricFilterType::LowShelf
        } else if tokens[3].eq_ignore_ascii_case("HSC") || tokens[3].eq_ignore_ascii_case("HS") {
            ParametricFilterType::HighShelf
        } else if tokens[3].eq_ignore_ascii_case("PK") {
            ParametricFilterType::Peaking
        } else {
            return None;
        };

    let mut idx = 4;
    let mut freq_hz: Option<f64> = None;
    let mut gain_db: Option<f64> = None;
    let mut q_factor: Option<f64> = None;

    while idx + 1 < tokens.len() {
        if tokens[idx].eq_ignore_ascii_case("Fc") {
            let num_str = strip_trailing_unit(tokens[idx + 1], "hz");
            freq_hz = parse_clean_f64(num_str);
            idx += 2;
            if tokens
                .get(idx)
                .is_some_and(|t| t.eq_ignore_ascii_case("Hz"))
            {
                idx += 1;
            }
        } else if tokens[idx].eq_ignore_ascii_case("Gain") {
            let num_str = strip_trailing_unit(tokens[idx + 1], "db");
            gain_db = parse_clean_f64(num_str);
            idx += 2;
            if tokens
                .get(idx)
                .is_some_and(|t| t.eq_ignore_ascii_case("dB"))
            {
                idx += 1;
            }
        } else if tokens[idx].eq_ignore_ascii_case("Q") {
            q_factor = parse_clean_f64(tokens[idx + 1]);
            idx += 2;
        } else {
            idx += 1;
        }
    }

    let freq = freq_hz?;
    let gain = gain_db?;
    if !(10.0..=24_000.0).contains(&freq) {
        return None;
    }

    let q = q_factor.filter(|&v| v > 0.0).unwrap_or(0.707);
    Some(ParametricFilter {
        frequency_hz: freq,
        gain_db: gain.clamp(-24.0, 24.0),
        q_factor: q.clamp(0.1, 24.0),
        filter_type,
        enabled,
    })
}

fn interpolate_graphic_to_10_bands(points: &[(f64, f64)]) -> [f32; 10] {
    let mut sorted = points.to_vec();
    sorted.sort_by(|a, b| a.0.total_cmp(&b.0));

    let mut out = [0.0_f32; 10];
    let Some(first) = sorted.first() else {
        return out;
    };
    let Some(last) = sorted.last() else {
        return out;
    };

    for (band_idx, &target_hz) in STANDARD_EQ_FREQUENCIES_HZ.iter().enumerate() {
        let target_f = f64::from(target_hz);
        let raw_gain = if let Some(exact) = sorted.iter().find(|p| (p.0 - target_f).abs() < 0.5) {
            exact.1
        } else if target_f <= first.0 {
            first.1
        } else if target_f >= last.0 {
            last.1
        } else {
            let right_idx = sorted
                .iter()
                .position(|p| p.0 > target_f)
                .unwrap_or(sorted.len() - 1)
                .max(1);
            let left = sorted[right_idx - 1];
            let right = sorted[right_idx];
            let span = right.0.ln() - left.0.ln();
            if span <= 1e-6 {
                left.1
            } else {
                let t = (target_f.ln() - left.0.ln()) / span;
                left.1 + t * (right.1 - left.1)
            }
        };

        out[band_idx] = (raw_gain as f32).clamp(EQ_MIN_GAIN_DB, EQ_MAX_GAIN_DB);
    }
    out
}

/// Parses AutoEQ `ParametricEQ` or `GraphicEQ` text content into an [`AutoEqProfile`].
#[must_use]
pub fn parse_autoeq(raw_text: &str, fallback_name: &str) -> Option<AutoEqProfile> {
    let cleaned = raw_text.strip_prefix('\u{FEFF}').unwrap_or(raw_text);
    let mut preamp_db: Option<f32> = None;
    let mut parametric_filters = Vec::new();
    let mut graphic_points = Vec::new();

    for raw_line in cleaned.lines() {
        let line = raw_line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }

        if let Some(p) = parse_preamp_ci(line) {
            preamp_db = Some(p);
            continue;
        }

        if let Some(pf) = parse_filter_line(line) {
            parametric_filters.push(pf);
            continue;
        }

        let graphic_prefix = "GraphicEQ:";
        if line.len() >= graphic_prefix.len()
            && line
                .get(..graphic_prefix.len())
                .is_some_and(|head| head.eq_ignore_ascii_case(graphic_prefix))
        {
            let payload = &line[graphic_prefix.len()..];
            for token in payload.split(';') {
                let mut parts = token.split_whitespace();
                if let (Some(f_str), Some(g_str)) = (parts.next(), parts.next())
                    && let (Some(f), Some(g)) = (parse_clean_f64(f_str), parse_clean_f64(g_str))
                    && (10.0..=24_000.0).contains(&f)
                {
                    graphic_points.push((f, g));
                }
            }
        }
    }

    if parametric_filters.is_empty() && graphic_points.is_empty() {
        return None;
    }

    let mapped_bands = if graphic_points.is_empty() {
        [0.0_f32; 10]
    } else {
        interpolate_graphic_to_10_bands(&graphic_points)
    };

    let resolved_preamp = preamp_db.unwrap_or_else(|| {
        let max_param = parametric_filters
            .iter()
            .filter(|pf| pf.enabled)
            .map(|pf| pf.gain_db as f32)
            .fold(0.0_f32, f32::max);
        let max_band = mapped_bands.iter().copied().fold(0.0_f32, f32::max);
        let peak_boost = max_param.max(max_band);
        if peak_boost > 0.0 {
            (-peak_boost).clamp(EQ_MIN_GAIN_DB, 0.0)
        } else {
            0.0
        }
    });

    let stem = fallback_name
        .rsplit_once('.')
        .map_or(fallback_name, |(base, _)| base)
        .trim();
    let clean_name = if stem.is_empty() {
        "AutoEQ".to_owned()
    } else {
        stem.to_owned()
    };

    Some(AutoEqProfile {
        name: clean_name,
        preamp_db: resolved_preamp,
        band_gains_db: mapped_bands,
        parametric_filters,
    })
}
