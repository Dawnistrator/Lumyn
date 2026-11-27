//! 64-bit internal / 32-bit float RBJ Audio EQ Cookbook biquad bank matching `BiquadDspEngine.kt`.

use std::f64::consts::PI;

use lunaria_model::{
    EQ_MAX_GAIN_DB, EQ_MIN_GAIN_DB, ParametricFilter, ParametricFilterType, ReplayGainMode,
    STANDARD_EQ_FREQUENCIES_HZ, compute_effective_gain_db,
};

use crate::{DspConfigSnapshot, DspConfigStore, DspError};

/// Threshold above which [`soft_limit`] engages smooth `tanh` headroom compression.
pub const LIMITER_THRESHOLD: f64 = 0.95;

/// Default Q factor applied to each band of the 10-band graphic equalizer.
pub const GRAPHIC_BAND_Q: f64 = 1.41;

/// Minimum dB magnitude required to instantiate an active biquad stage.
pub const MIN_ACTIVE_GAIN_DB: f64 = 0.05;

#[derive(Debug, Clone, Copy, PartialEq)]
struct BiquadCoeffs {
    b0: f64,
    b1: f64,
    b2: f64,
    a1: f64,
    a2: f64,
}

/// Applies the parity soft limiter matching Android `BiquadDspEngine.softLimit`.
///
/// Non-finite inputs return `0.0` so NaN or infinity samples never reach WASAPI.
#[must_use]
pub fn soft_limit(sample: f64) -> f64 {
    if !sample.is_finite() {
        return 0.0;
    }
    let abs_x = sample.abs();
    if abs_x <= LIMITER_THRESHOLD {
        return sample;
    }
    let sign = if sample >= 0.0 { 1.0 } else { -1.0 };
    let excess = abs_x - LIMITER_THRESHOLD;
    let headroom = 1.0 - LIMITER_THRESHOLD;
    sign * (LIMITER_THRESHOLD + headroom * (excess / headroom).tanh())
}

/// Pure-Rust real-time biquad filter bank, ReplayGain stage, stereo balance, mono downmix,
/// and soft limiter matching Lunaria Android's `BiquadDspEngine`.
#[derive(Debug, Clone)]
pub struct BiquadDspEngine {
    sample_rate_hz: u32,
    channel_count: usize,
    enabled: bool,
    preamp_db: f32,
    band_gains_db: [f32; 10],
    parametric_filters: Vec<ParametricFilter>,
    replay_gain_mode: ReplayGainMode,
    replay_gain_preamp_db: f32,
    channel_balance: f32,
    mono_audio: bool,
    bypassed: bool,
    track_replay_gain_db: Option<f32>,
    album_replay_gain_db: Option<f32>,
    track_replay_gain_peak: Option<f32>,
    album_replay_gain_peak: Option<f32>,
    applied_store_id: u64,
    applied_version: u64,
    active_coeffs: Vec<BiquadCoeffs>,
    z1: Vec<f64>,
    z2: Vec<f64>,
    preamp_linear: f64,
    active_flag: bool,
}

impl Default for BiquadDspEngine {
    fn default() -> Self {
        Self::new()
    }
}

impl BiquadDspEngine {
    /// Constructs a flat DSP engine configured for 48 kHz stereo by default.
    #[must_use]
    pub fn new() -> Self {
        Self {
            sample_rate_hz: 48_000,
            channel_count: 2,
            enabled: false,
            preamp_db: 0.0,
            band_gains_db: [0.0; 10],
            parametric_filters: Vec::with_capacity(32),
            replay_gain_mode: ReplayGainMode::Off,
            replay_gain_preamp_db: 0.0,
            channel_balance: 0.0,
            mono_audio: false,
            bypassed: false,
            track_replay_gain_db: None,
            album_replay_gain_db: None,
            track_replay_gain_peak: None,
            album_replay_gain_peak: None,
            applied_store_id: 0,
            applied_version: 0,
            active_coeffs: Vec::with_capacity(42),
            z1: Vec::with_capacity(42 * 2),
            z2: Vec::with_capacity(42 * 2),
            preamp_linear: 1.0,
            active_flag: false,
        }
    }

    /// Configures stream sample rate (`8_000..=768_000` Hz) and channel count (`1..=8`).
    pub fn configure(&mut self, sample_rate_hz: u32, channel_count: usize) -> Result<(), DspError> {
        if !(8_000..=768_000).contains(&sample_rate_hz) {
            return Err(DspError::InvalidSampleRate(sample_rate_hz));
        }
        if !(1..=8).contains(&channel_count) {
            return Err(DspError::InvalidChannelCount(channel_count));
        }
        self.sample_rate_hz = sample_rate_hz;
        self.channel_count = channel_count;
        self.rebuild_stages();
        Ok(())
    }

    /// Updates engine parameters directly from a [`DspConfigSnapshot`] without allocating strings.
    pub fn update_from_snapshot(&mut self, snap: &DspConfigSnapshot) {
        self.enabled = snap.settings.enabled;
        self.preamp_db = snap.settings.preamp_db;
        self.band_gains_db = snap.settings.band_gains_db;
        self.parametric_filters.clear();
        self.parametric_filters
            .extend_from_slice(&snap.settings.parametric_filters);
        self.replay_gain_mode = snap.settings.replay_gain_mode;
        self.replay_gain_preamp_db = snap.settings.replay_gain_preamp_db;
        self.channel_balance = snap.settings.channel_balance;
        self.mono_audio = snap.settings.mono_audio;
        self.bypassed = snap.bypassed;
        self.track_replay_gain_db = snap.track_replay_gain_db;
        self.album_replay_gain_db = snap.album_replay_gain_db;
        self.track_replay_gain_peak = snap.track_replay_gain_peak;
        self.album_replay_gain_peak = snap.album_replay_gain_peak;
        self.applied_store_id = 0;
        self.applied_version = snap.version;
        self.rebuild_stages();
    }

    /// Polls [`DspConfigStore`] lock-free via `Acquire` load and rebuilds coefficients only when changed.
    pub fn sync_from_store(&mut self, store: &DspConfigStore) {
        let store_id = store.store_id();
        let current_version = store.version();
        if store_id != self.applied_store_id || current_version != self.applied_version {
            let snap = store.snapshot();
            self.update_from_snapshot(&snap);
            self.applied_store_id = store_id;
            self.applied_version = snap.version;
        }
    }

    /// Clears all biquad filter delay lines (typically after seek or track switch).
    pub fn reset_state(&mut self) {
        self.z1.fill(0.0);
        self.z2.fill(0.0);
    }

    /// Returns `true` when at least one DSP stage is active and not bypassed.
    #[must_use]
    pub const fn is_processing_active(&self) -> bool {
        self.active_flag
    }

    /// Processes interleaved 32-bit float PCM frames in-place with zero heap allocations.
    pub fn process_interleaved_f32(&mut self, samples: &mut [f32]) {
        if !self.active_flag || self.channel_count == 0 {
            return;
        }
        let ch_count = self.channel_count;
        let frame_count = samples.len() / ch_count;
        if frame_count == 0 {
            return;
        }

        let apply_preamp = (self.preamp_linear - 1.0).abs() >= 1e-4;
        let apply_mono = self.mono_audio && ch_count == 2;
        let bal = f64::from(self.channel_balance).clamp(-1.0, 1.0);
        let apply_balance = ch_count == 2 && bal.abs() >= 0.01;
        let left_gain = if bal > 0.0 {
            (1.0 - bal).clamp(0.0, 1.0)
        } else {
            1.0
        };
        let right_gain = if bal < 0.0 {
            (1.0 + bal).clamp(0.0, 1.0)
        } else {
            1.0
        };

        for frame_slice in samples.chunks_exact_mut(ch_count) {
            if ch_count == 2 && (apply_mono || apply_balance) {
                let mut l = f64::from(frame_slice[0]);
                let mut r = f64::from(frame_slice[1]);
                if apply_mono {
                    let mid = (l + r) * 0.5;
                    l = mid;
                    r = mid;
                }
                if apply_balance {
                    l *= left_gain;
                    r *= right_gain;
                }
                frame_slice[0] = Self::process_single_sample(
                    l,
                    0,
                    ch_count,
                    apply_preamp,
                    self.preamp_linear,
                    &self.active_coeffs,
                    &mut self.z1,
                    &mut self.z2,
                );
                frame_slice[1] = Self::process_single_sample(
                    r,
                    1,
                    ch_count,
                    apply_preamp,
                    self.preamp_linear,
                    &self.active_coeffs,
                    &mut self.z1,
                    &mut self.z2,
                );
            } else {
                for (ch, sample_ref) in frame_slice.iter_mut().enumerate() {
                    *sample_ref = Self::process_single_sample(
                        f64::from(*sample_ref),
                        ch,
                        ch_count,
                        apply_preamp,
                        self.preamp_linear,
                        &self.active_coeffs,
                        &mut self.z1,
                        &mut self.z2,
                    );
                }
            }
        }
    }

    /// Evaluates analytical cascaded magnitude response (dB) at `frequency_hz` for EQ plotting & tests.
    #[must_use]
    pub fn compute_magnitude_response_db(&self, frequency_hz: f64) -> f64 {
        if self.bypassed || !self.active_flag || !frequency_hz.is_finite() || frequency_hz <= 0.0 {
            return 0.0;
        }
        let omega = (2.0 * PI * frequency_hz) / f64::from(self.sample_rate_hz);
        let cos_w = omega.cos();
        let sin_w = omega.sin();
        let cos_2w = (2.0 * omega).cos();
        let sin_2w = (2.0 * omega).sin();

        let mut mag_sq = self.preamp_linear * self.preamp_linear;
        for c in &self.active_coeffs {
            let num_re = c.b0 + c.b1 * cos_w + c.b2 * cos_2w;
            let num_im = -(c.b1 * sin_w + c.b2 * sin_2w);
            let den_re = 1.0 + c.a1 * cos_w + c.a2 * cos_2w;
            let den_im = -(c.a1 * sin_w + c.a2 * sin_2w);
            let den_mag_sq = (den_re * den_re + den_im * den_im).max(1e-24);
            mag_sq *= (num_re * num_re + num_im * num_im) / den_mag_sq;
        }

        if mag_sq <= 1e-24 {
            -120.0
        } else {
            10.0 * mag_sq.log10()
        }
    }

    #[expect(
        clippy::too_many_arguments,
        reason = "keeps hot inner sample path allocation-free and borrow-split"
    )]
    #[inline]
    fn process_single_sample(
        input: f64,
        channel: usize,
        channel_count: usize,
        apply_preamp: bool,
        preamp_linear: f64,
        coeffs: &[BiquadCoeffs],
        z1: &mut [f64],
        z2: &mut [f64],
    ) -> f32 {
        let mut x = if apply_preamp {
            input * preamp_linear
        } else {
            input
        };
        for (f, &c) in coeffs.iter().enumerate() {
            let state_idx = f * channel_count + channel;
            let y = c.b0 * x + z1[state_idx];
            z1[state_idx] = c.b1 * x - c.a1 * y + z2[state_idx];
            z2[state_idx] = c.b2 * x - c.a2 * y;
            x = y;
        }
        soft_limit(x) as f32
    }

    fn normalized_band_gains(&self) -> [f32; 10] {
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

    fn has_active_effects(&self) -> bool {
        let bands = self.normalized_band_gains();
        let eq_active = self.enabled
            && (self.preamp_db.abs() >= 0.05
                || bands.iter().any(|&g| g.abs() >= 0.05)
                || self
                    .parametric_filters
                    .iter()
                    .any(|pf| pf.enabled && pf.gain_db.abs() >= 0.05));
        let replay_gain_active = self.replay_gain_mode != ReplayGainMode::Off;
        let balance_or_mono_active = self.channel_balance.abs() >= 0.01 || self.mono_audio;
        eq_active || replay_gain_active || balance_or_mono_active
    }

    fn rebuild_stages(&mut self) {
        let should_process = !self.bypassed && self.has_active_effects();
        self.active_flag = should_process;
        if !should_process {
            self.active_coeffs.clear();
            self.preamp_linear = 1.0;
            self.z1.clear();
            self.z2.clear();
            return;
        }

        let eq_preamp = if self.enabled && self.preamp_db.is_finite() {
            f64::from(self.preamp_db)
        } else {
            0.0
        };

        let rg_gain = compute_effective_gain_db(
            self.replay_gain_mode,
            self.track_replay_gain_db,
            self.album_replay_gain_db,
            self.track_replay_gain_peak,
            self.album_replay_gain_peak,
            self.replay_gain_preamp_db,
        )
        .map_or(0.0, f64::from);

        self.preamp_linear = 10.0_f64.powf((eq_preamp + rg_gain).clamp(-24.0, 24.0) / 20.0);

        self.active_coeffs.clear();
        if self.enabled {
            let bands = self.normalized_band_gains();
            for (&freq_hz, &gain_f32) in STANDARD_EQ_FREQUENCIES_HZ.iter().zip(&bands) {
                let gain = f64::from(gain_f32);
                if gain.abs() >= MIN_ACTIVE_GAIN_DB {
                    self.active_coeffs.push(Self::compute_biquad(
                        self.sample_rate_hz,
                        ParametricFilterType::Peaking,
                        f64::from(freq_hz),
                        gain,
                        GRAPHIC_BAND_Q,
                    ));
                }
            }

            for pf in &self.parametric_filters {
                if pf.enabled && pf.gain_db.is_finite() && pf.gain_db.abs() >= MIN_ACTIVE_GAIN_DB {
                    self.active_coeffs.push(Self::compute_biquad(
                        self.sample_rate_hz,
                        pf.filter_type,
                        pf.frequency_hz,
                        pf.gain_db,
                        pf.q_factor,
                    ));
                }
            }
        }

        let state_size = self.active_coeffs.len() * self.channel_count;
        if self.z1.len() != state_size {
            self.z1.resize(state_size, 0.0);
            self.z2.resize(state_size, 0.0);
            self.z1.fill(0.0);
            self.z2.fill(0.0);
        }
    }

    fn compute_biquad(
        sample_rate_hz: u32,
        filter_type: ParametricFilterType,
        freq_hz: f64,
        gain_db: f64,
        q_factor: f64,
    ) -> BiquadCoeffs {
        let rate_f = f64::from(sample_rate_hz);
        let max_freq = (rate_f * 0.45).max(20.0);
        let safe_freq = if freq_hz.is_finite() {
            freq_hz
        } else {
            1_000.0
        };
        let clamped_freq = safe_freq.clamp(15.0, max_freq);
        let safe_gain = if gain_db.is_finite() { gain_db } else { 0.0 };
        let clamped_gain = safe_gain.clamp(-24.0, 24.0);
        let safe_q = if q_factor.is_finite() {
            q_factor
        } else {
            0.707
        };
        let q = safe_q.clamp(0.1, 24.0);

        let omega = (2.0 * PI * clamped_freq) / rate_f;
        let sin_w = omega.sin();
        let cos_w = omega.cos();
        let a = 10.0_f64.powf(clamped_gain / 40.0);
        let alpha = sin_w / (2.0 * q);

        let (b0, b1, b2, a0, a1, a2) = match filter_type {
            ParametricFilterType::Peaking => (
                1.0 + alpha * a,
                -2.0 * cos_w,
                1.0 - alpha * a,
                1.0 + alpha / a,
                -2.0 * cos_w,
                1.0 - alpha / a,
            ),
            ParametricFilterType::LowShelf => {
                let two_sqrt_a_alpha = 2.0 * a.sqrt() * alpha;
                (
                    a * ((a + 1.0) - (a - 1.0) * cos_w + two_sqrt_a_alpha),
                    2.0 * a * ((a - 1.0) - (a + 1.0) * cos_w),
                    a * ((a + 1.0) - (a - 1.0) * cos_w - two_sqrt_a_alpha),
                    (a + 1.0) + (a - 1.0) * cos_w + two_sqrt_a_alpha,
                    -2.0 * ((a - 1.0) + (a + 1.0) * cos_w),
                    (a + 1.0) + (a - 1.0) * cos_w - two_sqrt_a_alpha,
                )
            }
            ParametricFilterType::HighShelf => {
                let two_sqrt_a_alpha = 2.0 * a.sqrt() * alpha;
                (
                    a * ((a + 1.0) + (a - 1.0) * cos_w + two_sqrt_a_alpha),
                    -2.0 * a * ((a - 1.0) + (a + 1.0) * cos_w),
                    a * ((a + 1.0) + (a - 1.0) * cos_w - two_sqrt_a_alpha),
                    (a + 1.0) - (a - 1.0) * cos_w + two_sqrt_a_alpha,
                    2.0 * ((a - 1.0) - (a + 1.0) * cos_w),
                    (a + 1.0) - (a - 1.0) * cos_w - two_sqrt_a_alpha,
                )
            }
        };

        let inv_a0 = 1.0 / a0.max(1e-12);
        BiquadCoeffs {
            b0: b0 * inv_a0,
            b1: b1 * inv_a0,
            b2: b2 * inv_a0,
            a1: a1 * inv_a0,
            a2: a2 * inv_a0,
        }
    }
}
