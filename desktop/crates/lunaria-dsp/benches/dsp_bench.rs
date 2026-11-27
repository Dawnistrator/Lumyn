//! Criterion benchmark targeting < 1% CPU (10 ms) for 1 second of 96 kHz stereo 15-filter DSP.

use std::f64::consts::PI;
use std::hint::black_box;

use criterion::{Criterion, criterion_group, criterion_main};
use lunaria_dsp::{BiquadDspEngine, DspConfigSnapshot};
use lunaria_model::{EqualizerSettings, ParametricFilter, ParametricFilterType, ReplayGainMode};

fn bench_96khz_stereo_15_filter_dsp(c: &mut Criterion) {
    let sample_rate = 96_000_u32;
    let frames = sample_rate as usize; // 1.0 second of audio
    let mut engine = BiquadDspEngine::new();
    if let Err(err) = engine.configure(sample_rate, 2) {
        eprintln!("failed to configure benchmark DSP engine: {err}");
        return;
    }

    let snap = DspConfigSnapshot {
        version: 2,
        settings: EqualizerSettings {
            enabled: true,
            preamp_db: -3.5,
            band_gains_db: [3.0, 2.5, 1.5, 0.5, -0.5, 1.0, 2.0, 2.5, 1.5, 1.0],
            parametric_filters: vec![
                ParametricFilter {
                    frequency_hz: 95.0,
                    gain_db: 2.2,
                    q_factor: 0.71,
                    filter_type: ParametricFilterType::LowShelf,
                    enabled: true,
                },
                ParametricFilter {
                    frequency_hz: 420.0,
                    gain_db: -1.4,
                    q_factor: 1.8,
                    filter_type: ParametricFilterType::Peaking,
                    enabled: true,
                },
                ParametricFilter {
                    frequency_hz: 2_300.0,
                    gain_db: 2.1,
                    q_factor: 2.1,
                    filter_type: ParametricFilterType::Peaking,
                    enabled: true,
                },
                ParametricFilter {
                    frequency_hz: 5_800.0,
                    gain_db: -2.6,
                    q_factor: 3.0,
                    filter_type: ParametricFilterType::Peaking,
                    enabled: true,
                },
                ParametricFilter {
                    frequency_hz: 11_000.0,
                    gain_db: 1.5,
                    q_factor: 0.71,
                    filter_type: ParametricFilterType::HighShelf,
                    enabled: true,
                },
            ],
            replay_gain_mode: ReplayGainMode::Track,
            ..EqualizerSettings::default()
        },
        bypassed: false,
        track_replay_gain_db: Some(-4.2),
        track_replay_gain_peak: Some(0.95),
        ..DspConfigSnapshot::default()
    };
    engine.update_from_snapshot(&snap);

    let template: Vec<f32> = (0..frames)
        .flat_map(|i| {
            let t = i as f64 / f64::from(sample_rate);
            let s = (0.25 * (2.0 * PI * 440.0 * t).sin()) as f32;
            [s, -s]
        })
        .collect();

    let mut buffer = template.clone();
    let _ = c.bench_function("lunaria_dsp_96khz_stereo_1s_15_biquads", |b| {
        b.iter(|| {
            buffer.copy_from_slice(&template);
            engine.process_interleaved_f32(black_box(&mut buffer));
        });
    });
}

criterion_group!(benches, bench_96khz_stereo_15_filter_dsp);
criterion_main!(benches);
