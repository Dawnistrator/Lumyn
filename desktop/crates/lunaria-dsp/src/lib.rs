//! Real-time 64-bit internal / 32-bit float DSP engine matching Lunaria Android's acoustic math.

#![forbid(unsafe_code)]

pub mod biquad;
pub mod config_store;
pub mod crossfade;

use thiserror::Error;

pub use biquad::{
    BiquadDspEngine, GRAPHIC_BAND_Q, LIMITER_THRESHOLD, MIN_ACTIVE_GAIN_DB, soft_limit,
};
pub use config_store::{DspConfigSnapshot, DspConfigStore};
pub use crossfade::{fade_in_volume, fade_out_volume};

/// Errors returned by DSP configuration or validation routines.
#[derive(Debug, Error)]
pub enum DspError {
    /// Provided sample rate is outside the supported acoustic range.
    #[error("invalid sample rate: {0} Hz")]
    InvalidSampleRate(u32),

    /// Provided channel count is outside the supported range (`1..=8`).
    #[error("invalid channel count: {0}")]
    InvalidChannelCount(usize),
}

#[cfg(test)]
mod tests {
    use std::f64::consts::PI;

    use lunaria_model::{
        EqualizerSettings, ParametricFilter, ParametricFilterType, ReplayGainMode,
    };
    use proptest::prelude::*;

    use super::*;

    #[test]
    fn soft_limit_is_linear_within_threshold_and_bounded_above() {
        assert_eq!(soft_limit(f64::NAN), 0.0);
        assert_eq!(soft_limit(f64::INFINITY), 0.0);
        assert_eq!(soft_limit(0.5), 0.5);
        assert_eq!(soft_limit(-0.95), -0.95);

        let gentle_pos = soft_limit(0.98);
        assert!(gentle_pos > LIMITER_THRESHOLD && gentle_pos < 1.0);

        let limited_pos = soft_limit(2.5);
        assert!(limited_pos > LIMITER_THRESHOLD && limited_pos <= 1.0);

        let limited_neg = soft_limit(-2.5);
        assert!((-1.0..-LIMITER_THRESHOLD).contains(&limited_neg));
    }

    #[test]
    fn crossfade_curve_endpoints_and_midpoint_sum_to_one() {
        assert_eq!(fade_out_volume(1_000, 1_000), 1.0);
        assert_eq!(fade_out_volume(0, 1_000), 0.0);
        assert_eq!(fade_in_volume(0, 1_000), 0.0);
        assert_eq!(fade_in_volume(1_000, 1_000), 1.0);

        let out_mid = fade_out_volume(500, 1_000);
        let in_mid = fade_in_volume(500, 1_000);
        assert!((out_mid - 0.5).abs() < 1e-6);
        assert!((in_mid - 0.5).abs() < 1e-6);
    }

    #[test]
    fn purist_bypass_leaves_audio_samples_bit_exact() {
        let mut engine = BiquadDspEngine::new();
        assert!(engine.configure(48_000, 2).is_ok());

        let mut bands = [0.0_f32; 10];
        bands[5] = 9.0;
        let snap = DspConfigSnapshot {
            version: 2,
            settings: EqualizerSettings {
                enabled: true,
                preamp_db: -4.0,
                band_gains_db: bands,
                ..EqualizerSettings::default()
            },
            bypassed: true,
            ..DspConfigSnapshot::default()
        };
        engine.update_from_snapshot(&snap);
        assert!(!engine.is_processing_active());

        let original = [0.25_f32, -0.5, 0.75, -0.125];
        let mut samples = original;
        engine.process_interleaved_f32(&mut samples);
        assert_eq!(samples, original);
    }

    #[test]
    fn boost_at_1khz_matches_analytical_response_and_rms_gain() {
        let mut engine = BiquadDspEngine::new();
        let sample_rate = 48_000_u32;
        assert!(engine.configure(sample_rate, 2).is_ok());

        let mut bands = [0.0_f32; 10];
        bands[5] = 6.0; // 1 kHz
        let snap = DspConfigSnapshot {
            version: 2,
            settings: EqualizerSettings {
                enabled: true,
                band_gains_db: bands,
                ..EqualizerSettings::default()
            },
            bypassed: false,
            ..DspConfigSnapshot::default()
        };
        engine.update_from_snapshot(&snap);
        assert!(engine.is_processing_active());

        let resp_1k = engine.compute_magnitude_response_db(1_000.0);
        assert!(
            (resp_1k - 6.0).abs() < 1e-4,
            "expected +6.0 dB, got {resp_1k}"
        );

        let frames = 2_400;
        let mut samples = vec![0.0_f32; frames * 2];
        let mut input_sq = 0.0_f64;
        for i in 0..frames {
            let s =
                (0.15 * (2.0 * PI * 1_000.0 * (i as f64) / f64::from(sample_rate)).sin()) as f32;
            samples[i * 2] = s;
            samples[i * 2 + 1] = s;
            input_sq += f64::from(s) * f64::from(s);
        }

        engine.process_interleaved_f32(&mut samples);

        let mut output_sq = 0.0_f64;
        for i in (frames / 2)..frames {
            let s = f64::from(samples[i * 2]);
            output_sq += s * s;
        }
        let input_rms = (input_sq / frames as f64).sqrt();
        let output_rms = (output_sq / (frames / 2) as f64).sqrt();
        assert!(
            output_rms > input_rms * 1.8,
            "expected boosted RMS {output_rms} > 1.8x {input_rms}"
        );
    }

    #[test]
    fn shelving_and_autoeq_filters_shape_response_accurately() {
        let mut engine = BiquadDspEngine::new();
        assert!(engine.configure(48_000, 2).is_ok());

        let snap = DspConfigSnapshot {
            version: 3,
            settings: EqualizerSettings {
                enabled: true,
                parametric_filters: vec![
                    ParametricFilter {
                        frequency_hz: 100.0,
                        gain_db: 5.0,
                        q_factor: 0.707,
                        filter_type: ParametricFilterType::LowShelf,
                        enabled: true,
                    },
                    ParametricFilter {
                        frequency_hz: 8_000.0,
                        gain_db: -4.0,
                        q_factor: 0.707,
                        filter_type: ParametricFilterType::HighShelf,
                        enabled: true,
                    },
                ],
                ..EqualizerSettings::default()
            },
            bypassed: false,
            ..DspConfigSnapshot::default()
        };
        engine.update_from_snapshot(&snap);

        assert!(engine.compute_magnitude_response_db(20.0) > 4.5);
        assert!(engine.compute_magnitude_response_db(18_000.0) < -3.5);
    }

    #[test]
    fn channel_balance_mono_and_soft_limiter_prevent_clipping() {
        let mut engine = BiquadDspEngine::new();
        assert!(engine.configure(48_000, 2).is_ok());

        let snap = DspConfigSnapshot {
            version: 2,
            settings: EqualizerSettings {
                enabled: true,
                preamp_db: 12.0,
                channel_balance: 1.0,
                ..EqualizerSettings::default()
            },
            bypassed: false,
            ..DspConfigSnapshot::default()
        };
        engine.update_from_snapshot(&snap);

        let mut samples = [0.8_f32, 0.8, -0.9, -0.9];
        engine.process_interleaved_f32(&mut samples);
        assert!(samples[0].abs() < 1e-5);
        assert!(samples[2].abs() < 1e-5);
        assert!(samples[1].abs() <= 1.0);
        assert!(samples[3].abs() <= 1.0);

        // Mono downmix + ReplayGain clipping guard
        let store = DspConfigStore::new();
        store.update_all(
            EqualizerSettings {
                mono_audio: true,
                replay_gain_mode: ReplayGainMode::Track,
                ..EqualizerSettings::default()
            },
            false,
            Some(-6.0),
            None,
            Some(0.95),
            None,
        );
        engine.sync_from_store(&store);
        let mut stereo = [0.8_f32, 0.2];
        engine.process_interleaved_f32(&mut stereo);
        assert!((stereo[0] - stereo[1]).abs() < 1e-6);
        assert!(stereo[0] < 0.35);
    }

    #[test]
    fn dsp_config_store_syncs_lock_free_across_threads() {
        let store = std::sync::Arc::new(DspConfigStore::new());
        let writer_store = std::sync::Arc::clone(&store);
        let handle = std::thread::spawn(move || {
            writer_store.update_equalizer(|eq| EqualizerSettings {
                enabled: true,
                preamp_db: -3.0,
                ..eq.clone()
            });
            writer_store.update_bypassed(true);
            writer_store.update_bypassed(false);
        });
        assert!(handle.join().is_ok());

        let mut engine = BiquadDspEngine::new();
        engine.sync_from_store(&store);
        assert!(engine.is_processing_active());
        assert!((engine.compute_magnitude_response_db(1_000.0) - (-3.0)).abs() < 0.05);
    }

    proptest! {
        #[test]
        fn biquad_output_stays_finite_and_bounded_across_arbitrary_inputs(
            gain_low in -12.0f32..12.0f32,
            gain_mid in -12.0f32..12.0f32,
            gain_high in -12.0f32..12.0f32,
            preamp in -12.0f32..12.0f32,
            balance in -1.0f32..1.0f32,
            mono in any::<bool>(),
            sample_a in -2.0f32..2.0f32,
            sample_b in -2.0f32..2.0f32,
        ) {
            let mut engine = BiquadDspEngine::new();
            let _ = engine.configure(96_000, 2);

            let mut bands = [0.0_f32; 10];
            bands[1] = gain_low;
            bands[5] = gain_mid;
            bands[8] = gain_high;

            let snap = DspConfigSnapshot {
                version: 2,
                settings: EqualizerSettings {
                    enabled: true,
                    preamp_db: preamp,
                    band_gains_db: bands,
                    channel_balance: balance,
                    mono_audio: mono,
                    ..EqualizerSettings::default()
                },
                bypassed: false,
                ..DspConfigSnapshot::default()
            };
            engine.update_from_snapshot(&snap);

            let mut buf = [sample_a, sample_b, sample_a * 0.5, sample_b * 0.5];
            engine.process_interleaved_f32(&mut buf);

            for &out in &buf {
                prop_assert!(out.is_finite());
                prop_assert!((-1.0..=1.0).contains(&out));
            }
        }
    }
}
