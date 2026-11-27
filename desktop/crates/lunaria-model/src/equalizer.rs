//! Equalizer models, ISO 10-band frequencies, and built-in presets matching Android.

use serde::{Deserialize, Serialize};

/// ISO standard 10-band graphic equalizer center frequencies (Hz).
pub const STANDARD_EQ_FREQUENCIES_HZ: [u32; 10] =
    [31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000];

/// Minimum allowed graphic EQ gain (dB).
pub const EQ_MIN_GAIN_DB: f32 = -12.0;

/// Maximum allowed graphic EQ gain (dB).
pub const EQ_MAX_GAIN_DB: f32 = 12.0;

/// Maximum crossfade overlap duration in seconds.
pub const MAX_CROSSFADE_SEC: u8 = 12;

/// ReplayGain loudness normalization mode.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default, Serialize, Deserialize)]
pub enum ReplayGainMode {
    /// Disable ReplayGain gain adjustment.
    #[default]
    Off,
    /// Prefer per-track gain & peak metadata.
    Track,
    /// Prefer album-wide gain & peak metadata.
    Album,
}

/// Supported RBJ biquad parametric filter shapes.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default, Serialize, Deserialize)]
pub enum ParametricFilterType {
    /// Parametric peaking bell filter (`PK`).
    #[default]
    Peaking,
    /// Low-shelving filter (`LSC` / `LS`).
    LowShelf,
    /// High-shelving filter (`HSC` / `HS`).
    HighShelf,
}

/// Single parametric EQ filter stage (typically imported from AutoEQ `.txt`).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ParametricFilter {
    /// Center or corner frequency in Hz.
    pub frequency_hz: f64,
    /// Boost or cut in dB.
    pub gain_db: f64,
    /// Quality factor `Q` (slope / bandwidth).
    pub q_factor: f64,
    /// Filter topology.
    pub filter_type: ParametricFilterType,
    /// Whether this stage is currently active.
    pub enabled: bool,
}

/// Named 10-band equalizer preset.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct EqualizerPreset {
    /// Preset name shown in the UI.
    pub name: &'static str,
    /// 10 band gain offsets (dB) aligned with [`STANDARD_EQ_FREQUENCIES_HZ`].
    pub band_gains_db: [f32; 10],
    /// Recommended preamp offset (dB) to prevent clipping.
    pub preamp_db: f32,
}

/// Built-in presets matching Lunaria Android `BuiltInEqPresets`.
pub const BUILT_IN_EQ_PRESETS: [EqualizerPreset; 6] = [
    EqualizerPreset {
        name: "Flat",
        band_gains_db: [0.0; 10],
        preamp_db: 0.0,
    },
    EqualizerPreset {
        name: "Bass Boost",
        band_gains_db: [5.0, 4.5, 3.0, 1.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
        preamp_db: -3.0,
    },
    EqualizerPreset {
        name: "Vocal",
        band_gains_db: [-2.0, -1.5, -0.5, 1.0, 2.5, 3.5, 3.0, 1.5, 0.5, -0.5],
        preamp_db: -2.0,
    },
    EqualizerPreset {
        name: "Acoustic",
        band_gains_db: [2.5, 2.0, 1.0, 0.5, 1.0, 1.5, 2.0, 2.5, 2.0, 1.5],
        preamp_db: -1.5,
    },
    EqualizerPreset {
        name: "Treble Lift",
        band_gains_db: [0.0, 0.0, 0.0, 0.0, 0.0, 0.5, 1.5, 3.0, 4.0, 4.5],
        preamp_db: -2.0,
    },
    EqualizerPreset {
        name: "Studio Reference",
        band_gains_db: [1.5, 1.0, 0.5, 0.0, -0.5, 0.0, 0.5, 1.0, 1.5, 1.0],
        preamp_db: -1.0,
    },
];

/// Complete DSP equalizer, ReplayGain, stereo, and crossfade settings.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct EqualizerSettings {
    /// Master EQ toggle.
    pub enabled: bool,
    /// Preamp adjustment in dB (`-12.0..=12.0`).
    pub preamp_db: f32,
    /// 10-band graphic EQ gains (dB).
    pub band_gains_db: [f32; 10],
    /// Optional AutoEQ parametric filter bank.
    pub parametric_filters: Vec<ParametricFilter>,
    /// Active preset or AutoEQ profile display name.
    pub active_preset_name: String,
    /// Active ReplayGain mode.
    pub replay_gain_mode: ReplayGainMode,
    /// Manual ReplayGain preamp offset in dB.
    pub replay_gain_preamp_db: f32,
    /// Left/Right balance in `-1.0..=1.0` (`-1.0` full left, `1.0` full right).
    pub channel_balance: f32,
    /// Sum stereo channels to mono when enabled.
    pub mono_audio: bool,
    /// Equal-power crossfade duration in seconds (`0..=12`).
    pub crossfade_duration_sec: u8,
}

impl Default for EqualizerSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            preamp_db: 0.0,
            band_gains_db: [0.0; 10],
            parametric_filters: Vec::new(),
            active_preset_name: "Flat".to_owned(),
            replay_gain_mode: ReplayGainMode::Off,
            replay_gain_preamp_db: 0.0,
            channel_balance: 0.0,
            mono_audio: false,
            crossfade_duration_sec: 0,
        }
    }
}

impl EqualizerSettings {
    /// Clamps all 10 graphic EQ bands into [`EQ_MIN_GAIN_DB`]..=[`EQ_MAX_GAIN_DB`].
    #[must_use]
    pub fn normalized_band_gains(&self) -> [f32; 10] {
        let mut out = [0.0_f32; 10];
        for (dst, &src) in out.iter_mut().zip(&self.band_gains_db) {
            *dst = if src.is_finite() {
                src.clamp(EQ_MIN_GAIN_DB, EQ_MAX_GAIN_DB)
            } else {
                0.0
            };
        }
        out
    }

    /// Returns `true` if any DSP stage modifies audio samples.
    #[must_use]
    pub fn has_active_effects(&self) -> bool {
        let eq_active = self.enabled
            && (self.preamp_db.abs() >= 0.05
                || self
                    .normalized_band_gains()
                    .iter()
                    .any(|&g| g.abs() >= 0.05)
                || self
                    .parametric_filters
                    .iter()
                    .any(|pf| pf.enabled && pf.gain_db.abs() >= 0.05));
        let replay_gain_active = self.replay_gain_mode != ReplayGainMode::Off;
        let balance_or_mono_active = self.channel_balance.abs() >= 0.01 || self.mono_audio;
        eq_active || replay_gain_active || balance_or_mono_active
    }
}
