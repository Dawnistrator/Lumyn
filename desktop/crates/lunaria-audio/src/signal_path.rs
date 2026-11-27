//! Honest Audio Signal Path builder matching Lunaria Android parity + Windows WASAPI states.

use lunaria_dsp::DspConfigSnapshot;
use lunaria_model::{
    AudioDeviceInfo, AudioOutputCategory, AudioOutputMode, AudioSignalPath, AudioSpec,
    ReplayGainMode, compute_effective_gain_db, has_tag_for_mode,
};

/// Dynamic inputs describing the active playback graph from file to Windows hardware endpoint.
#[derive(Debug, Clone)]
pub struct SignalPathInput<'a> {
    /// Decoded source technical specification.
    pub source_spec: &'a AudioSpec,
    /// Decoder name string (e.g. `"Symphonia Pure-Rust (FLAC)"`).
    pub decoder_name: &'a str,
    /// User-selected audio output mode.
    pub requested_mode: AudioOutputMode,
    /// Selected WASAPI render endpoint, if resolved.
    pub device: Option<&'a AudioDeviceInfo>,
    /// Actual negotiated output sample rate in Hz.
    pub actual_output_rate_hz: u32,
    /// Actual negotiated output bit depth.
    pub actual_output_bit_depth: u16,
    /// Active PCM sample layout label (e.g. `"32-bit Float PCM"` or `"Native 24-in-32 PCM"`).
    pub output_encoding_label: &'a str,
    /// Whether WASAPI Exclusive Event-Driven mode is genuinely active.
    pub is_exclusive_active: bool,
    /// Honest fallback explanation if Exclusive mode was requested but refused/unavailable.
    pub fallback_reason: Option<&'a str>,
    /// Optional active resampler stage description (`None` when bypassed).
    pub resampler_label: Option<&'a str>,
    /// Active WSOLA playback speed (`1.0` when bypassed).
    pub speed: f32,
    /// Current DSP snapshot including EQ, AutoEQ, ReplayGain, Mono, Balance, and Crossfade.
    pub dsp: &'a DspConfigSnapshot,
    /// Optional track ReplayGain metadata (`REPLAYGAIN_TRACK_GAIN`).
    pub track_replay_gain_db: Option<f32>,
    /// Optional album ReplayGain metadata (`REPLAYGAIN_ALBUM_GAIN`).
    pub album_replay_gain_db: Option<f32>,
    /// Optional track ReplayGain peak.
    pub track_replay_gain_peak: Option<f32>,
    /// Optional album ReplayGain peak.
    pub album_replay_gain_peak: Option<f32>,
}

/// Constructs an honest telemetry report of every active processing stage from file to DAC.
#[must_use]
pub fn build_signal_path(input: &SignalPathInput<'_>) -> AudioSignalPath {
    let summary = input.source_spec.format_quality_summary();
    let source_format_label = if input.source_spec.bitrate_kbps > 0 && !summary.contains("kbps") {
        format!("{summary} • {} kbps", input.source_spec.bitrate_kbps)
    } else {
        summary
    };

    let category = input
        .device
        .map_or(AudioOutputCategory::Other, |dev| dev.category);
    let device_name = input.device.map_or_else(
        || "No active WASAPI render endpoint".to_owned(),
        |dev| dev.name.clone(),
    );

    let is_bluetooth = category == AudioOutputCategory::BluetoothA2dp;
    let is_exclusive = !is_bluetooth && input.is_exclusive_active;
    let speed_active = (input.speed - 1.0).abs() >= 0.005;
    let is_bit_perfect = is_exclusive
        && input.resampler_label.is_none()
        && !speed_active
        && input.actual_output_rate_hz == input.source_spec.sample_rate_hz;

    let mut processing_chain = Vec::new();
    match (is_bluetooth, is_exclusive, input.requested_mode) {
        (true, _, AudioOutputMode::DspFloat32) => {
            if let Some(src_label) = input.resampler_label {
                processing_chain.push(src_label.to_owned());
            }
            if speed_active {
                processing_chain.push(format!("WSOLA Speed ({:.2}x)", input.speed));
            }
            processing_chain.push(describe_dsp_stages(input));
            processing_chain.push("WASAPI Shared Event-Driven".to_owned());
            processing_chain.push("Bluetooth A2DP • System-managed wireless link".to_owned());
        }
        (true, _, AudioOutputMode::PuristDirectBitPerfect) => {
            processing_chain.push("Purist Exclusive unavailable on Bluetooth A2DP".to_owned());
            if let Some(src_label) = input.resampler_label {
                processing_chain.push(src_label.to_owned());
            }
            processing_chain.push("Fallback: WASAPI Shared 32-bit Float (DSP bypassed)".to_owned());
            processing_chain.push("Bluetooth A2DP • System-managed wireless link".to_owned());
        }
        (false, true, _) => {
            processing_chain
                .push("WASAPI Exclusive Event-Driven (Mixer & APO bypassed)".to_owned());
            processing_chain.push(format!(
                "{} • EQ, ReplayGain & DSP bypassed",
                input.output_encoding_label
            ));
        }
        (false, false, AudioOutputMode::PuristDirectBitPerfect) => {
            processing_chain.push(
                input
                    .fallback_reason
                    .unwrap_or("Purist Exclusive unavailable on endpoint driver")
                    .to_owned(),
            );
            if let Some(src_label) = input.resampler_label {
                processing_chain.push(src_label.to_owned());
            }
            processing_chain.push("Fallback: WASAPI Shared 32-bit Float (DSP bypassed)".to_owned());
        }
        (false, false, AudioOutputMode::DspFloat32) => {
            if let Some(src_label) = input.resampler_label {
                processing_chain.push(src_label.to_owned());
            }
            if speed_active {
                processing_chain.push(format!("WSOLA Speed ({:.2}x)", input.speed));
            }
            processing_chain.push(describe_dsp_stages(input));
            processing_chain.push("WASAPI Shared Event-Driven".to_owned());
        }
    }

    AudioSignalPath {
        source_format_label,
        decoder_name: input.decoder_name.to_owned(),
        processing_chain,
        output_device_name: device_name,
        output_sample_rate_hz: input.actual_output_rate_hz,
        output_bit_depth: input.actual_output_bit_depth,
        is_direct_playback_active: is_exclusive,
        is_bit_perfect_active: is_bit_perfect,
        output_mode: input.requested_mode,
        output_category: category,
        output_encoding_label: input.output_encoding_label.to_owned(),
    }
}

/// Builds an idle/preview Signal Path before any local track is loaded.
#[must_use]
pub fn build_preview_signal_path(
    device: Option<&AudioDeviceInfo>,
    mode: AudioOutputMode,
) -> AudioSignalPath {
    build_preview_signal_path_with_dsp(device, mode, &DspConfigSnapshot::default())
}

/// Builds an idle/preview Signal Path reflecting live DSP preset changes before opening a file.
#[must_use]
pub fn build_preview_signal_path_with_dsp(
    device: Option<&AudioDeviceInfo>,
    mode: AudioOutputMode,
    dsp: &DspConfigSnapshot,
) -> AudioSignalPath {
    let preview_rate = device.map_or(48_000, |dev| dev.default_sample_rate_hz);
    let preview_bits = device.map_or(24, |dev| dev.default_bit_depth);
    let preview_ch = device.map_or(2, |dev| dev.default_channels);
    let is_bluetooth = device.is_some_and(|d| d.category == AudioOutputCategory::BluetoothA2dp);
    let can_exclusive =
        device.is_some() && !is_bluetooth && mode == AudioOutputMode::PuristDirectBitPerfect;

    let encoding_label = if can_exclusive {
        format!("Native PCM {preview_bits}-bit")
    } else {
        "32-bit Float PCM".to_owned()
    };

    let preview_spec = AudioSpec {
        codec: "Ready".to_owned(),
        bit_depth: preview_bits,
        sample_rate_hz: preview_rate,
        bitrate_kbps: 0,
        channel_count: preview_ch,
        is_lossless: true,
    };

    let input = SignalPathInput {
        source_spec: &preview_spec,
        decoder_name: "Symphonia Pure-Rust Decoder",
        requested_mode: mode,
        device,
        actual_output_rate_hz: preview_rate,
        actual_output_bit_depth: if can_exclusive { preview_bits } else { 32 },
        output_encoding_label: &encoding_label,
        is_exclusive_active: can_exclusive,
        fallback_reason: if can_exclusive {
            None
        } else if mode == AudioOutputMode::PuristDirectBitPerfect && !is_bluetooth {
            Some("No active WASAPI render endpoint")
        } else {
            None
        },
        resampler_label: None,
        speed: 1.0,
        dsp,
        track_replay_gain_db: dsp.track_replay_gain_db,
        album_replay_gain_db: dsp.album_replay_gain_db,
        track_replay_gain_peak: dsp.track_replay_gain_peak,
        album_replay_gain_peak: dsp.album_replay_gain_peak,
    };

    let mut path = build_signal_path(&input);
    path.source_format_label = "No track loaded (Open Audio to play)".to_owned();
    path
}

fn describe_dsp_stages(input: &SignalPathInput<'_>) -> String {
    if input.dsp.bypassed {
        return "32-bit Float DSP bypassed".to_owned();
    }

    let eq = &input.dsp.settings;
    let mut parts = Vec::new();

    if eq.enabled {
        let active_param = eq
            .parametric_filters
            .iter()
            .filter(|pf| pf.enabled && pf.gain_db.abs() >= 0.05)
            .count();
        if active_param > 0 {
            parts.push(format!(
                "AutoEQ ({}, {active_param} filters)",
                eq.active_preset_name
            ));
        } else {
            parts.push(format!("10-band EQ ({})", eq.active_preset_name));
        }
    }

    if eq.replay_gain_mode != ReplayGainMode::Off {
        let mode_name = match eq.replay_gain_mode {
            ReplayGainMode::Track => "Track",
            ReplayGainMode::Album => "Album",
            ReplayGainMode::Off => "",
        };
        let has_tag = has_tag_for_mode(
            eq.replay_gain_mode,
            input.track_replay_gain_db,
            input.album_replay_gain_db,
        );
        let eff_db = compute_effective_gain_db(
            eq.replay_gain_mode,
            input.track_replay_gain_db,
            input.album_replay_gain_db,
            input.track_replay_gain_peak,
            input.album_replay_gain_peak,
            eq.replay_gain_preamp_db,
        );
        let suffix = match (has_tag, eff_db) {
            (true, Some(db)) => format!(" {db:+.1} dB"),
            _ if eq.replay_gain_preamp_db.abs() >= 0.05 => {
                format!(" preamp {:+.1} dB", eq.replay_gain_preamp_db)
            }
            _ => " • no tag".to_owned(),
        };
        parts.push(format!("ReplayGain ({mode_name}{suffix})"));
    }

    if eq.mono_audio {
        parts.push("Mono".to_owned());
    }
    if eq.channel_balance.abs() >= 0.01 {
        parts.push("L/R Balance".to_owned());
    }
    if eq.crossfade_duration_sec > 0 {
        parts.push(format!("Crossfade ({}s)", eq.crossfade_duration_sec));
    }

    if parts.is_empty() {
        "Flat (no active DSP filters)".to_owned()
    } else {
        parts.join(" • ")
    }
}
