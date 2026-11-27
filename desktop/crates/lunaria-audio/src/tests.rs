//! Unit tests for decoding, Rubato resampling, WSOLA speed, signal path honesty, PCM packing,
//! COM-safe WASAPI discovery, and Windows File-Lock Handover.

use std::f64::consts::PI;
use std::fs::{File, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

use lunaria_dsp::{DspConfigSnapshot, DspConfigStore};
use lunaria_model::{
    AudioDeviceInfo, AudioOutputCategory, AudioOutputMode, AudioSpec, EqualizerSettings,
    ReplayGainMode,
};

use crate::decoder::{AudioTrackDecoder, probe_audio_track};
use crate::engine::{PlaybackEngine, PlaybackRequest, PlaybackStatus};
use crate::resampler::AudioResampler;
use crate::signal_path::{SignalPathInput, build_preview_signal_path, build_signal_path};
use crate::time_stretch::WsolaTimeStretcher;
use crate::{WasapiEncoding, classify_device_category, pack_frames_to_pcm_bytes};

static NEXT_TEST_FILE_ID: AtomicU64 = AtomicU64::new(1);

struct TempWavGuard {
    path: PathBuf,
}

impl Drop for TempWavGuard {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.path);
    }
}

fn write_test_wav(
    name: &str,
    sample_rate_hz: u32,
    bits_per_sample: u16,
    channels: u16,
    duration_secs: f64,
    freq_hz: f64,
) -> TempWavGuard {
    let id = NEXT_TEST_FILE_ID.fetch_add(1, Ordering::Relaxed);
    let path = std::env::temp_dir().join(format!(
        "lunaria_audio_test_{name}_{}_{id}.wav",
        std::process::id()
    ));

    let total_frames = (f64::from(sample_rate_hz) * duration_secs).round() as usize;
    let bytes_per_sample = usize::from(bits_per_sample / 8);
    let data_bytes_len = total_frames * usize::from(channels) * bytes_per_sample;
    let riff_size = (36 + data_bytes_len) as u32;
    let block_align = channels * (bits_per_sample / 8);
    let byte_rate = sample_rate_hz * u32::from(block_align);

    let mut raw = Vec::with_capacity(44 + data_bytes_len);
    raw.extend_from_slice(b"RIFF");
    raw.extend_from_slice(&riff_size.to_le_bytes());
    raw.extend_from_slice(b"WAVE");
    raw.extend_from_slice(b"fmt ");
    raw.extend_from_slice(&16_u32.to_le_bytes());
    raw.extend_from_slice(&1_u16.to_le_bytes()); // PCM
    raw.extend_from_slice(&channels.to_le_bytes());
    raw.extend_from_slice(&sample_rate_hz.to_le_bytes());
    raw.extend_from_slice(&byte_rate.to_le_bytes());
    raw.extend_from_slice(&block_align.to_le_bytes());
    raw.extend_from_slice(&bits_per_sample.to_le_bytes());
    raw.extend_from_slice(b"data");
    raw.extend_from_slice(&(data_bytes_len as u32).to_le_bytes());

    for f in 0..total_frames {
        let t = (f as f64) / f64::from(sample_rate_hz);
        let sample = 0.35 * (2.0 * PI * freq_hz * t).sin();
        for _ in 0..channels {
            match bits_per_sample {
                16 => {
                    let q = (sample * 32_767.0).round() as i16;
                    raw.extend_from_slice(&q.to_le_bytes());
                }
                24 => {
                    let q = (sample * 8_388_607.0).round() as i32;
                    raw.extend_from_slice(&q.to_le_bytes()[..3]);
                }
                _ => {
                    let q = (sample * 2_147_483_647.0).round() as i32;
                    raw.extend_from_slice(&q.to_le_bytes());
                }
            }
        }
    }

    if let Ok(mut f) = File::create(&path) {
        let _ = f.write_all(&raw);
    }

    TempWavGuard { path }
}

#[test]
fn device_classifier_recognizes_dac_bluetooth_wired_and_speaker() {
    assert_eq!(
        classify_device_category("FiiO K7 USB DAC"),
        AudioOutputCategory::UsbDac
    );
    assert_eq!(
        classify_device_category("Sony WH-1000XM5 (Bluetooth A2DP)"),
        AudioOutputCategory::BluetoothA2dp
    );
    assert_eq!(
        classify_device_category("Headphones (Realtek High Definition Audio)"),
        AudioOutputCategory::Wired
    );
    assert_eq!(
        classify_device_category("Speakers (Realtek Audio)"),
        AudioOutputCategory::Speaker
    );
}

#[test]
fn preview_signal_path_never_claims_bluetooth_is_bit_perfect() {
    let bt = AudioDeviceInfo {
        id: "bt-1".to_owned(),
        name: "AirPods Pro (Bluetooth)".to_owned(),
        is_default: true,
        category: AudioOutputCategory::BluetoothA2dp,
        default_sample_rate_hz: 48_000,
        default_bit_depth: 16,
        default_channels: 2,
    };
    let path = build_preview_signal_path(Some(&bt), AudioOutputMode::PuristDirectBitPerfect);
    assert!(!path.is_bit_perfect_active);
    assert!(!path.is_direct_playback_active);
    assert!(
        path.processing_chain
            .iter()
            .any(|step| step.contains("Bluetooth A2DP"))
    );

    let dac = AudioDeviceInfo {
        id: "dac-1".to_owned(),
        name: "Topping E30 USB DAC".to_owned(),
        is_default: true,
        category: AudioOutputCategory::UsbDac,
        default_sample_rate_hz: 96_000,
        default_bit_depth: 24,
        default_channels: 2,
    };
    let dac_path = build_preview_signal_path(Some(&dac), AudioOutputMode::PuristDirectBitPerfect);
    assert!(dac_path.is_bit_perfect_active);
    assert!(dac_path.is_direct_playback_active);
}

#[cfg(windows)]
#[test]
fn enumerate_devices_works_from_sta_ui_thread() {
    let handle = std::thread::spawn(|| {
        let hr = wasapi::initialize_sta();
        let devices = crate::enumerate_render_devices();
        if hr.is_ok() {
            wasapi::deinitialize();
        }
        assert!(
            devices.is_ok(),
            "enumerate failed on STA thread: {devices:?}"
        );
    });
    assert!(handle.join().is_ok());
}

#[test]
fn decoder_reads_16bit_and_24bit_wav_seeks_and_clips_cue_bounds() {
    let wav16 = write_test_wav("s16", 44_100, 16, 2, 1.0, 440.0);
    let info16 = probe_audio_track(&wav16.path);
    assert!(info16.is_ok(), "probe 16-bit wav failed: {info16:?}");
    if let Ok(info) = info16 {
        assert_eq!(info.sample_rate_hz, 44_100);
        assert_eq!(info.bit_depth(), 16);
        assert_eq!(info.channels, 2);
        assert!(info.spec.is_lossless);
        assert!((980..=1_020).contains(&info.duration_ms));
    }

    let wav24 = write_test_wav("s24", 96_000, 24, 2, 1.0, 1_000.0);
    let dec_res = AudioTrackDecoder::open(&wav24.path, Some(200), Some(600));
    assert!(
        dec_res.is_ok(),
        "open 24-bit clipped wav failed: {dec_res:?}"
    );
    if let Ok(mut dec) = dec_res {
        assert_eq!(dec.info().sample_rate_hz, 96_000);
        assert_eq!(dec.info().spec.bit_depth, 24);
        assert_eq!(dec.duration_ms(), 400);

        let mut buf = Vec::new();
        let mut total_frames = 0usize;
        let mut peak = 0.0_f64;
        loop {
            let n = dec.read_interleaved_f64(&mut buf).unwrap_or(0);
            if n == 0 {
                break;
            }
            total_frames += n;
            for &s in &buf {
                assert!(s.is_finite());
                peak = peak.max(s.abs());
            }
        }
        // 400 ms @ 96 kHz = 38,400 frames
        assert!(
            (37_000..=39_800).contains(&total_frames),
            "unexpected clipped frame count: {total_frames}"
        );
        assert!(peak > 0.25 && peak <= 0.5);

        let seek_res = dec.seek_ms(150);
        assert!(seek_res.is_ok(), "seek failed: {seek_res:?}");
        let mut after_seek_frames = 0usize;
        while let Ok(n) = dec.read_interleaved_f64(&mut buf) {
            if n == 0 {
                break;
            }
            after_seek_frames += n;
        }
        // Remaining 250 ms @ 96 kHz = 24,000 frames
        assert!(
            (22_000..=25_500).contains(&after_seek_frames),
            "unexpected post-seek frame count: {after_seek_frames}"
        );
    }
}

trait TrackInfoExt {
    fn bit_depth(&self) -> u16;
}

impl TrackInfoExt for crate::DecodedTrackInfo {
    fn bit_depth(&self) -> u16 {
        self.spec.bit_depth
    }
}

#[test]
fn resampler_bypasses_matching_rate_and_converts_44100_and_96000_to_48000() {
    let mut bypass = AudioResampler::new(48_000, 48_000, 2).ok();
    assert!(
        bypass
            .as_ref()
            .is_some_and(|r| !r.is_active() && r.label().is_none())
    );
    if let Some(r) = &mut bypass {
        let input = [0.1_f64, -0.2, 0.3, -0.4];
        let mut out = Vec::new();
        assert_eq!(r.process(&input, &mut out).unwrap_or(0), 2);
        assert_eq!(out, input);
    }

    for (in_rate, expected_frames) in [(44_100_u32, 48_000_usize), (96_000_u32, 48_000_usize)] {
        let mut resampler = AudioResampler::new(in_rate, 48_000, 2).ok();
        assert!(
            resampler
                .as_ref()
                .is_some_and(|r| r.is_active() && r.label().is_some())
        );
        if let Some(r) = &mut resampler {
            let in_frames = in_rate as usize;
            let mut input = Vec::with_capacity(in_frames * 2);
            for i in 0..in_frames {
                let s = 0.5 * (2.0 * PI * 1_000.0 * (i as f64) / f64::from(in_rate)).sin();
                input.push(s);
                input.push(s);
            }
            let mut out = Vec::new();
            for chunk in input.chunks(512 * 2) {
                assert!(r.process(chunk, &mut out).is_ok());
            }
            assert!(r.flush(&mut out).is_ok());

            let out_frames = out.len() / 2;
            let diff = out_frames.abs_diff(expected_frames);
            assert!(
                diff <= 1_024,
                "rate {in_rate}->48000 frame count {out_frames} deviated too far from {expected_frames}"
            );
            let mut peak = 0.0_f64;
            for &s in &out {
                assert!(s.is_finite());
                peak = peak.max(s.abs());
            }
            assert!(peak > 0.35 && peak < 0.7, "unexpected sine peak {peak}");
        }
    }
}

#[test]
fn wsola_time_stretcher_bypasses_unity_and_scales_duration() {
    let sample_rate = 48_000_u32;
    let in_frames = 48_000_usize;
    let mut sine = Vec::with_capacity(in_frames * 2);
    for i in 0..in_frames {
        let s = 0.4 * (2.0 * PI * 440.0 * (i as f64) / f64::from(sample_rate)).sin();
        sine.push(s);
        sine.push(s);
    }

    let mut unity = WsolaTimeStretcher::new(sample_rate, 2, 1.0);
    assert!(!unity.is_active());
    let mut unity_out = Vec::new();
    assert_eq!(unity.process(&sine, &mut unity_out), in_frames);
    assert_eq!(unity_out, sine);

    let mut half = WsolaTimeStretcher::new(sample_rate, 2, 0.5);
    assert!(half.is_active());
    let mut half_out = Vec::new();
    let _ = half.process(&sine, &mut half_out);
    let _ = half.flush(&mut half_out);
    let half_frames = half_out.len() / 2;
    assert!(
        (88_000..=104_000).contains(&half_frames),
        "0.5x output frames {half_frames} should be ~96,000"
    );
    assert!(half_out.iter().all(|s| s.is_finite() && s.abs() <= 1.5));

    let mut double = WsolaTimeStretcher::new(sample_rate, 2, 2.0);
    assert!(double.is_active());
    let mut double_out = Vec::new();
    let _ = double.process(&sine, &mut double_out);
    let _ = double.flush(&mut double_out);
    let double_frames = double_out.len() / 2;
    assert!(
        (20_000..=28_000).contains(&double_frames),
        "2.0x output frames {double_frames} should be ~24,000"
    );
    assert!(double_out.iter().all(|s| s.is_finite() && s.abs() <= 1.5));
}

#[test]
fn signal_path_builder_reports_bluetooth_exclusive_resampler_and_dsp_honestly() {
    let source_spec = AudioSpec {
        codec: "FLAC".to_owned(),
        bit_depth: 24,
        sample_rate_hz: 96_000,
        bitrate_kbps: 2_800,
        channel_count: 2,
        is_lossless: true,
    };

    let bt_dev = AudioDeviceInfo {
        id: "bt".to_owned(),
        name: "Bluetooth A2DP Headphones".to_owned(),
        is_default: true,
        category: AudioOutputCategory::BluetoothA2dp,
        default_sample_rate_hz: 48_000,
        default_bit_depth: 16,
        default_channels: 2,
    };
    let flat_dsp = DspConfigSnapshot::default();
    let bt_path = build_signal_path(&SignalPathInput {
        source_spec: &source_spec,
        decoder_name: "Symphonia Pure-Rust (FLAC)",
        requested_mode: AudioOutputMode::PuristDirectBitPerfect,
        device: Some(&bt_dev),
        actual_output_rate_hz: 48_000,
        actual_output_bit_depth: 32,
        output_encoding_label: "32-bit Float PCM",
        is_exclusive_active: false,
        fallback_reason: None,
        resampler_label: Some("Rubato 64-bit FFT (96000 → 48000 Hz)"),
        speed: 1.0,
        dsp: &flat_dsp,
        track_replay_gain_db: None,
        album_replay_gain_db: None,
        track_replay_gain_peak: None,
        album_replay_gain_peak: None,
    });
    assert!(!bt_path.is_bit_perfect_active);
    assert!(
        bt_path
            .processing_chain
            .iter()
            .any(|s| s.contains("Bluetooth A2DP"))
    );

    let dac = AudioDeviceInfo {
        id: "dac".to_owned(),
        name: "Topping DX3 USB DAC".to_owned(),
        is_default: true,
        category: AudioOutputCategory::UsbDac,
        default_sample_rate_hz: 96_000,
        default_bit_depth: 24,
        default_channels: 2,
    };
    let excl_path = build_signal_path(&SignalPathInput {
        source_spec: &source_spec,
        decoder_name: "Symphonia Pure-Rust (FLAC)",
        requested_mode: AudioOutputMode::PuristDirectBitPerfect,
        device: Some(&dac),
        actual_output_rate_hz: 96_000,
        actual_output_bit_depth: 24,
        output_encoding_label: "Native 24-in-32 PCM",
        is_exclusive_active: true,
        fallback_reason: None,
        resampler_label: None,
        speed: 1.0,
        dsp: &flat_dsp,
        track_replay_gain_db: None,
        album_replay_gain_db: None,
        track_replay_gain_peak: None,
        album_replay_gain_peak: None,
    });
    assert!(excl_path.is_bit_perfect_active);
    assert!(excl_path.is_direct_playback_active);

    let active_dsp = DspConfigSnapshot {
        version: 2,
        settings: EqualizerSettings {
            enabled: true,
            active_preset_name: "Bass Boost".to_owned(),
            replay_gain_mode: ReplayGainMode::Track,
            crossfade_duration_sec: 4,
            ..EqualizerSettings::default()
        },
        bypassed: false,
        ..DspConfigSnapshot::default()
    };
    let dsp_path = build_signal_path(&SignalPathInput {
        source_spec: &source_spec,
        decoder_name: "Symphonia Pure-Rust (FLAC)",
        requested_mode: AudioOutputMode::DspFloat32,
        device: Some(&dac),
        actual_output_rate_hz: 48_000,
        actual_output_bit_depth: 32,
        output_encoding_label: "32-bit Float PCM",
        is_exclusive_active: false,
        fallback_reason: None,
        resampler_label: Some("Rubato 64-bit FFT (96000 → 48000 Hz)"),
        speed: 1.25,
        dsp: &active_dsp,
        track_replay_gain_db: Some(-4.5),
        album_replay_gain_db: None,
        track_replay_gain_peak: Some(0.9),
        album_replay_gain_peak: None,
    });
    assert!(!dsp_path.is_bit_perfect_active);
    let joined = dsp_path.processing_chain.join(" | ");
    assert!(joined.contains("Rubato 64-bit FFT"));
    assert!(joined.contains("WSOLA Speed (1.25x)"));
    assert!(joined.contains("10-band EQ (Bass Boost)"));
    assert!(joined.contains("ReplayGain (Track -4.5 dB)"));
    assert!(joined.contains("Crossfade (4s)"));
}

#[test]
fn pcm_packer_encodes_all_formats_and_maps_stereo_to_8ch() {
    // Stereo to 8-channel Float32 (Front Left & Front Right active, surround zeroed)
    let src = [0.5_f64, -0.25];
    let mut out_8ch = [0_u8; 8 * 4];
    pack_frames_to_pcm_bytes(
        &src,
        2,
        8,
        WasapiEncoding::Float32,
        1.0,
        false,
        &mut out_8ch,
    );
    let ch0 = f32::from_le_bytes([out_8ch[0], out_8ch[1], out_8ch[2], out_8ch[3]]);
    let ch1 = f32::from_le_bytes([out_8ch[4], out_8ch[5], out_8ch[6], out_8ch[7]]);
    let ch2 = f32::from_le_bytes([out_8ch[8], out_8ch[9], out_8ch[10], out_8ch[11]]);
    let ch7 = f32::from_le_bytes([out_8ch[28], out_8ch[29], out_8ch[30], out_8ch[31]]);
    assert!((ch0 - 0.5).abs() < 1e-6);
    assert!((ch1 - (-0.25)).abs() < 1e-6);
    assert_eq!(ch2, 0.0);
    assert_eq!(ch7, 0.0);

    // Int16
    let mut out_16 = [0_u8; 4];
    pack_frames_to_pcm_bytes(&src, 2, 2, WasapiEncoding::Int16, 1.0, false, &mut out_16);
    assert_eq!(i16::from_le_bytes([out_16[0], out_16[1]]), 16_384);
    assert_eq!(i16::from_le_bytes([out_16[2], out_16[3]]), -8_192);

    // Int24In32 (left-justified in 32-bit word -> LSB byte must be 0)
    let mut out_24in32 = [0_u8; 8];
    pack_frames_to_pcm_bytes(
        &src,
        2,
        2,
        WasapiEncoding::Int24In32,
        1.0,
        false,
        &mut out_24in32,
    );
    assert_eq!(out_24in32[0], 0);
    assert_eq!(out_24in32[4], 0);
    let left_i32 = i32::from_le_bytes([out_24in32[0], out_24in32[1], out_24in32[2], out_24in32[3]]);
    assert_eq!(left_i32 >> 8, 4_194_304);

    // Int24Packed (3 bytes LE)
    let mut out_24p = [0_u8; 6];
    pack_frames_to_pcm_bytes(
        &src,
        2,
        2,
        WasapiEncoding::Int24Packed,
        1.0,
        false,
        &mut out_24p,
    );
    let p_val = i32::from_le_bytes([0, out_24p[0], out_24p[1], out_24p[2]]) >> 8;
    assert_eq!(p_val, 4_194_304);

    // Stereo to Mono downmix must average (L + R) * 0.5 cleanly instead of dropping right channel.
    let mono_src = [1.0_f64, 0.0_f64];
    let mut out_mono = [0_u8; 4];
    pack_frames_to_pcm_bytes(
        &mono_src,
        2,
        1,
        WasapiEncoding::Float32,
        1.0,
        false,
        &mut out_mono,
    );
    let mono_val = f32::from_le_bytes(out_mono);
    assert!(
        (mono_val - 0.5).abs() < 1e-6,
        "expected 0.5 mono mix, got {mono_val}"
    );
}

#[test]
fn playback_engine_advances_queued_next_when_crossfade_preopened_before_eof() {
    let first = write_test_wav("xfade_a", 44_100, 16, 2, 0.9, 440.0);
    let second = write_test_wav("xfade_b", 44_100, 16, 2, 0.8, 880.0);

    let dsp_store = Arc::new(DspConfigStore::new());
    dsp_store.update_equalizer(|cur| {
        let mut next = cur.clone();
        next.crossfade_duration_sec = 2;
        next
    });

    let Ok(engine) = PlaybackEngine::new(dsp_store) else {
        return;
    };
    let _ = engine.set_volume(0.0);

    let mut req_a = PlaybackRequest::from_path(Path::new(&first.path));
    req_a.title = "Track A".to_owned();
    let mut req_b = PlaybackRequest::from_path(Path::new(&second.path));
    req_b.title = "Track B".to_owned();

    assert!(engine.load_and_play(req_a).is_ok());
    assert!(engine.queue_next(Some(req_b)).is_ok());

    let deadline = Instant::now() + Duration::from_secs(6);
    let mut switched_to_b = false;
    while Instant::now() < deadline {
        let tel = engine.telemetry();
        if tel
            .active_track
            .as_ref()
            .is_some_and(|t| t.title == "Track B")
        {
            switched_to_b = true;
            break;
        }
        std::thread::sleep(Duration::from_millis(25));
    }
    let _ = engine.stop();
    assert!(
        switched_to_b,
        "expected Track A crossfade into EOF to advance cleanly to Track B"
    );
}

#[test]
fn playback_engine_lifecycle_and_file_lock_handover() {
    let wav = write_test_wav("engine_handover", 48_000, 16, 2, 1.5, 440.0);
    let dsp_store = Arc::new(DspConfigStore::new());
    let engine = PlaybackEngine::new(dsp_store);
    assert!(engine.is_ok(), "engine startup failed: {engine:?}");
    let Ok(engine) = engine else {
        return;
    };

    // Keep test run completely silent on developer workstations.
    assert!(engine.set_volume(0.0).is_ok());

    let req = PlaybackRequest::from_path(Path::new(&wav.path));
    assert!(engine.load_and_play(req).is_ok());

    let deadline = Instant::now() + Duration::from_secs(2);
    while Instant::now() < deadline {
        let tel = engine.telemetry();
        if tel.status == PlaybackStatus::Playing && tel.duration_ms >= 1_400 {
            break;
        }
        std::thread::sleep(Duration::from_millis(15));
    }

    let tel = engine.telemetry();
    assert_eq!(
        tel.status,
        PlaybackStatus::Playing,
        "engine did not enter Playing: {:?}",
        tel.error_message
    );
    assert!(tel.duration_ms >= 1_400);

    assert!(engine.seek_ms(350).is_ok());
    std::thread::sleep(Duration::from_millis(30));

    // Windows File-Lock Handover: suspend must close OS file handle cleanly so write open succeeds.
    let susp = engine.suspend_file_handle(Some(&wav.path));
    assert!(susp.is_ok(), "suspend_file_handle failed: {susp:?}");
    if let Ok(token) = susp {
        assert!(token.request.is_some());
        let write_check = OpenOptions::new().write(true).open(&wav.path);
        assert!(
            write_check.is_ok(),
            "OS file handle remained locked after suspend_file_handle: {write_check:?}"
        );
        drop(write_check);
        assert!(engine.resume_after_suspension(token).is_ok());
        std::thread::sleep(Duration::from_millis(30));
    }

    assert!(engine.stop().is_ok());
    std::thread::sleep(Duration::from_millis(20));
    assert_eq!(engine.telemetry().status, PlaybackStatus::Stopped);
}

#[test]
fn signal_path_builder_avoids_duplicate_kbps_for_lossy_streams() {
    let mp3_spec = AudioSpec {
        codec: "MP3".to_owned(),
        bit_depth: 0,
        sample_rate_hz: 44_100,
        bitrate_kbps: 320,
        channel_count: 2,
        is_lossless: false,
    };
    let flat_dsp = DspConfigSnapshot::default();
    let mp3_path = build_signal_path(&SignalPathInput {
        source_spec: &mp3_spec,
        decoder_name: "Symphonia Pure-Rust (MP3)",
        requested_mode: AudioOutputMode::DspFloat32,
        device: None,
        actual_output_rate_hz: 48_000,
        actual_output_bit_depth: 32,
        output_encoding_label: "32-bit Float PCM",
        is_exclusive_active: false,
        fallback_reason: None,
        resampler_label: Some("Rubato 64-bit FFT (44100 → 48000 Hz)"),
        speed: 1.0,
        dsp: &flat_dsp,
        track_replay_gain_db: None,
        album_replay_gain_db: None,
        track_replay_gain_peak: None,
        album_replay_gain_peak: None,
    });
    assert_eq!(mp3_path.source_format_label, "MP3 • 320 kbps / 44.1 kHz");
    assert_eq!(mp3_path.source_format_label.matches("kbps").count(), 1);

    let flac_spec = AudioSpec {
        codec: "FLAC".to_owned(),
        bit_depth: 24,
        sample_rate_hz: 96_000,
        bitrate_kbps: 2_800,
        channel_count: 2,
        is_lossless: true,
    };
    let flac_path = build_signal_path(&SignalPathInput {
        source_spec: &flac_spec,
        decoder_name: "Symphonia Pure-Rust (FLAC)",
        requested_mode: AudioOutputMode::DspFloat32,
        device: None,
        actual_output_rate_hz: 96_000,
        actual_output_bit_depth: 32,
        output_encoding_label: "32-bit Float PCM",
        is_exclusive_active: false,
        fallback_reason: None,
        resampler_label: None,
        speed: 1.0,
        dsp: &flat_dsp,
        track_replay_gain_db: None,
        album_replay_gain_db: None,
        track_replay_gain_peak: None,
        album_replay_gain_peak: None,
    });
    assert_eq!(
        flac_path.source_format_label,
        "FLAC • 24-bit / 96 kHz • 2800 kbps"
    );
}
