//! Windows WASAPI stream negotiation (Shared 32-bit float & Exclusive Bit-Perfect) and PCM packing.

use lunaria_dsp::soft_limit;
use lunaria_model::{AudioDeviceInfo, AudioOutputCategory, AudioOutputMode, AudioSpec};

#[cfg(windows)]
use crate::{AudioError, classify_device_category};

/// PCM sample memory representation expected by an active WASAPI endpoint buffer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WasapiEncoding {
    /// IEEE 754 32-bit floating point (`WAVE_FORMAT_IEEE_FLOAT`).
    Float32,
    /// Signed 16-bit integer little-endian PCM.
    Int16,
    /// Signed 24-bit integer tightly packed in 3 bytes little-endian.
    Int24Packed,
    /// Signed 24-bit integer left-justified in a 32-bit container (`(i24 << 8).to_le_bytes()`).
    Int24In32,
    /// Signed 32-bit integer little-endian PCM.
    Int32,
}

impl WasapiEncoding {
    /// Nominal audio bit depth per channel sample.
    #[must_use]
    pub const fn bit_depth(self) -> u16 {
        match self {
            Self::Float32 | Self::Int32 => 32,
            Self::Int24Packed | Self::Int24In32 => 24,
            Self::Int16 => 16,
        }
    }

    /// Container byte stride per sample in the WASAPI DMA buffer.
    #[must_use]
    pub const fn bytes_per_sample(self) -> usize {
        match self {
            Self::Float32 | Self::Int24In32 | Self::Int32 => 4,
            Self::Int24Packed => 3,
            Self::Int16 => 2,
        }
    }

    /// Human-readable encoding label for Audio Signal Path.
    #[must_use]
    pub const fn label(self) -> &'static str {
        match self {
            Self::Float32 => "32-bit Float PCM",
            Self::Int16 => "Native 16-bit PCM",
            Self::Int24Packed => "Native 24-bit Packed PCM",
            Self::Int24In32 => "Native 24-in-32 PCM",
            Self::Int32 => "Native 32-bit PCM",
        }
    }
}

/// Packs normalized interleaved `f64` frames (`src_channels`) into hardware endpoint layout
/// (`dst_channels`, `encoding`) without allocating.
///
/// Channel mapping rules:
/// - `1 -> N >= 2`: mono duplicated onto Front Left & Front Right; channels `2..N` zeroed.
/// - `2 -> N > 2`: stereo routed to Front Left & Front Right; channels `2..N` (e.g. 7.1 surround) zeroed.
/// - `N > 2 -> 2`: folds center/surround channels smoothly to stereo.
/// - Exact match: copies channel-for-channel.
pub(crate) fn pack_frames_to_pcm_bytes(
    src_f64: &[f64],
    src_channels: usize,
    dst_channels: usize,
    encoding: WasapiEncoding,
    linear_gain: f64,
    apply_soft_limit: bool,
    out_bytes: &mut [u8],
) {
    if src_channels == 0 || dst_channels == 0 {
        out_bytes.fill(0);
        return;
    }

    let bps = encoding.bytes_per_sample();
    let frame_count = src_f64.len() / src_channels;
    let dst_frame_bytes = dst_channels * bps;
    let total_needed_bytes = frame_count * dst_frame_bytes;
    let usable_frames = (out_bytes.len() / dst_frame_bytes).min(frame_count);

    let gain = if linear_gain.is_finite() {
        linear_gain.clamp(0.0, 1.0)
    } else {
        0.0
    };
    let unity_gain = (gain - 1.0).abs() < 1e-6;

    for f in 0..usable_frames {
        let src_base = f * src_channels;
        let dst_base = f * dst_frame_bytes;

        for ch in 0..dst_channels {
            let raw = map_channel_sample(
                &src_f64[src_base..src_base + src_channels],
                ch,
                dst_channels,
            );
            let scaled = if raw.is_finite() {
                if unity_gain { raw } else { raw * gain }
            } else {
                0.0
            };
            let normalized = if apply_soft_limit {
                soft_limit(scaled)
            } else {
                scaled.clamp(-1.0, 1.0)
            };

            let dst_off = dst_base + ch * bps;
            encode_sample(normalized, encoding, &mut out_bytes[dst_off..dst_off + bps]);
        }
    }

    if total_needed_bytes < out_bytes.len() {
        out_bytes[usable_frames * dst_frame_bytes..].fill(0);
    }
}

#[inline]
fn map_channel_sample(frame: &[f64], dst_ch: usize, dst_channels: usize) -> f64 {
    let src_ch_count = frame.len();
    if src_ch_count == dst_channels {
        return frame[dst_ch];
    }
    if src_ch_count == 1 {
        return if dst_ch < 2 { frame[0] } else { 0.0 };
    }
    if src_ch_count == 2 {
        return if dst_channels == 1 {
            0.5 * (frame[0] + frame[1])
        } else if dst_ch == 0 {
            frame[0]
        } else if dst_ch == 1 {
            frame[1]
        } else {
            0.0
        };
    }
    if dst_channels == 2 && src_ch_count >= 3 {
        let center = std::f64::consts::FRAC_1_SQRT_2 * frame[2];
        let base = if dst_ch == 0 {
            frame[0] + center
        } else {
            frame[1] + center
        };
        return (base * std::f64::consts::FRAC_1_SQRT_2).clamp(-1.0, 1.0);
    }
    frame.get(dst_ch).copied().unwrap_or(0.0)
}

#[inline]
fn encode_sample(sample: f64, encoding: WasapiEncoding, dst: &mut [u8]) {
    let clamped = if sample.is_finite() {
        sample.clamp(-1.0, 1.0)
    } else {
        0.0
    };

    match encoding {
        WasapiEncoding::Float32 => {
            dst.copy_from_slice(&(clamped as f32).to_le_bytes());
        }
        WasapiEncoding::Int16 => {
            let scaled = if clamped >= 0.0 {
                clamped * 32_767.0
            } else {
                clamped * 32_768.0
            };
            let q = scaled.round().clamp(-32_768.0, 32_767.0) as i16;
            dst.copy_from_slice(&q.to_le_bytes());
        }
        WasapiEncoding::Int24Packed => {
            let scaled = if clamped >= 0.0 {
                clamped * 8_388_607.0
            } else {
                clamped * 8_388_608.0
            };
            let q = scaled.round().clamp(-8_388_608.0, 8_388_607.0) as i32;
            let bytes = q.to_le_bytes();
            dst.copy_from_slice(&bytes[..3]);
        }
        WasapiEncoding::Int24In32 => {
            let scaled = if clamped >= 0.0 {
                clamped * 8_388_607.0
            } else {
                clamped * 8_388_608.0
            };
            let q = scaled.round().clamp(-8_388_608.0, 8_388_607.0) as i32;
            dst.copy_from_slice(&(q << 8).to_le_bytes());
        }
        WasapiEncoding::Int32 => {
            let scaled = if clamped >= 0.0 {
                clamped * 2_147_483_647.0
            } else {
                clamped * 2_147_483_648.0
            };
            let q = scaled.round().clamp(-2_147_483_648.0, 2_147_483_647.0) as i32;
            dst.copy_from_slice(&q.to_le_bytes());
        }
    }
}

#[cfg(windows)]
const AUDCLNT_E_DEVICE_INVALIDATED: i32 = 0x8889_0004_u32 as i32;
#[cfg(windows)]
const AUDCLNT_E_SERVICE_NOT_RUNNING: i32 = 0x8889_0010_u32 as i32;
#[cfg(windows)]
const AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED: i32 = 0x8889_0019_u32 as i32;
#[cfg(windows)]
const AUDCLNT_E_DEVICE_IN_USE: i32 = 0x8889_000A_u32 as i32;
#[cfg(windows)]
const AUDCLNT_E_EXCLUSIVE_MODE_NOT_ALLOWED: i32 = 0x8889_000E_u32 as i32;

/// Returns `true` when Windows invalidated or unplugged the active endpoint stream.
#[cfg(windows)]
pub(crate) fn is_device_invalidated_error(err: &wasapi::WasapiError) -> bool {
    matches!(
        err,
        wasapi::WasapiError::Windows(werr)
            if werr.code().0 == AUDCLNT_E_DEVICE_INVALIDATED
                || werr.code().0 == AUDCLNT_E_SERVICE_NOT_RUNNING
    )
}

/// Active initialized Windows WASAPI render stream bound to an MTA render thread.
#[cfg(windows)]
pub(crate) struct ActiveWasapiStream {
    pub(crate) audio_client: wasapi::AudioClient,
    pub(crate) render_client: wasapi::AudioRenderClient,
    pub(crate) event_handle: wasapi::Handle,
    pub(crate) encoding: WasapiEncoding,
    pub(crate) is_exclusive: bool,
    pub(crate) fallback_reason: Option<String>,
    pub(crate) sample_rate_hz: u32,
    pub(crate) bit_depth: u16,
    pub(crate) channels: usize,
    pub(crate) block_align: usize,
    pub(crate) max_buffer_frames: usize,
    pub(crate) device_info: AudioDeviceInfo,
}

#[cfg(windows)]
pub(crate) fn open_wasapi_stream(
    device_id: Option<&str>,
    requested_mode: AudioOutputMode,
    source_spec: &AudioSpec,
) -> Result<ActiveWasapiStream, AudioError> {
    use wasapi::{DeviceEnumerator, Direction, SampleType, StreamMode, WaveFormat};

    let enumerator = DeviceEnumerator::new().map_err(|err| AudioError::Wasapi(err.to_string()))?;

    let (device, is_default) = match device_id.filter(|id| !id.is_empty()) {
        Some(id) => match enumerator.get_device(id) {
            Ok(dev) => {
                let def_id = enumerator
                    .get_default_device(Direction::Render)
                    .ok()
                    .and_then(|d| d.get_id().ok());
                let resolved_id = dev.get_id().unwrap_or_default();
                let is_def = def_id.as_deref().is_some_and(|d| d == resolved_id);
                (dev, is_def)
            }
            Err(_) => (
                enumerator
                    .get_default_device(Direction::Render)
                    .map_err(|err| AudioError::Wasapi(err.to_string()))?,
                true,
            ),
        },
        None => (
            enumerator
                .get_default_device(Direction::Render)
                .map_err(|err| AudioError::Wasapi(err.to_string()))?,
            true,
        ),
    };

    let resolved_id = device.get_id().unwrap_or_default();
    let resolved_name = device
        .get_friendlyname()
        .unwrap_or_else(|_| "Windows Audio Endpoint".to_owned());
    let category = classify_device_category(&resolved_name);

    let probe_client = device
        .get_iaudioclient()
        .map_err(|err| AudioError::Wasapi(err.to_string()))?;
    let mix_fmt = probe_client
        .get_mixformat()
        .map_err(|err| AudioError::Wasapi(err.to_string()))?;

    let mix_rate = mix_fmt.get_samplespersec().max(8_000);
    let mix_valid = mix_fmt.get_validbitspersample();
    let mix_bits = if mix_valid > 0 {
        mix_valid
    } else {
        mix_fmt.get_bitspersample()
    };
    let mix_channels = usize::from(mix_fmt.get_nchannels()).clamp(1, 8);
    let device_info = AudioDeviceInfo {
        id: resolved_id,
        name: resolved_name,
        is_default,
        category,
        default_sample_rate_hz: mix_rate,
        default_bit_depth: mix_bits,
        default_channels: mix_channels as u16,
    };

    let mut fallback_reason: Option<String> = None;

    if requested_mode == AudioOutputMode::PuristDirectBitPerfect {
        if category == AudioOutputCategory::BluetoothA2dp {
            fallback_reason = Some("Purist Exclusive unavailable on Bluetooth A2DP".to_owned());
        } else {
            match try_open_exclusive(&device, &probe_client, source_spec, &device_info) {
                Ok(stream) => return Ok(stream),
                Err(reason) => {
                    tracing::info!(
                        device = %device_info.name,
                        reason = %reason,
                        "exclusive mode unavailable, falling back to WASAPI Shared"
                    );
                    fallback_reason = Some(reason);
                }
            }
        }
    }

    // Shared Event-Driven stream running at endpoint native mix rate & channels so Windows SRC
    // is skipped and our 64-bit Rubato resampler performs all sample rate conversions.
    drop(probe_client);
    let mut shared_client = device
        .get_iaudioclient()
        .map_err(|err| AudioError::Wasapi(err.to_string()))?;
    let desired_shared = WaveFormat::new(
        32,
        32,
        SampleType::Float,
        mix_rate as usize,
        mix_channels,
        None,
    );
    let (default_period, _) = shared_client
        .get_device_period()
        .map_err(|err| AudioError::Wasapi(err.to_string()))?;
    let buffer_hns = default_period.max(200_000); // >= 20 ms jitter-resistant shared buffer

    let mut init_res = shared_client.initialize_client(
        &desired_shared,
        Direction::Render,
        StreamMode::EventsShared {
            autoconvert: false,
            buffer_duration_hns: buffer_hns,
        },
        None,
    );

    if init_res.is_err() {
        shared_client = device
            .get_iaudioclient()
            .map_err(|err| AudioError::Wasapi(err.to_string()))?;
        init_res = shared_client.initialize_client(
            &desired_shared,
            Direction::Render,
            StreamMode::EventsShared {
                autoconvert: true,
                buffer_duration_hns: buffer_hns,
            },
            None,
        );
    }

    init_res.map_err(|err| AudioError::Wasapi(err.to_string()))?;
    let event_handle = shared_client
        .set_get_eventhandle()
        .map_err(|err| AudioError::Wasapi(err.to_string()))?;
    let render_client = shared_client
        .get_audiorenderclient()
        .map_err(|err| AudioError::Wasapi(err.to_string()))?;
    let max_buffer_frames = shared_client
        .get_buffer_size()
        .map_err(|err| AudioError::Wasapi(err.to_string()))? as usize;

    Ok(ActiveWasapiStream {
        audio_client: shared_client,
        render_client,
        event_handle,
        encoding: WasapiEncoding::Float32,
        is_exclusive: false,
        fallback_reason,
        sample_rate_hz: mix_rate,
        bit_depth: 32,
        channels: mix_channels,
        block_align: desired_shared.get_blockalign() as usize,
        max_buffer_frames,
        device_info,
    })
}

#[cfg(windows)]
fn try_open_exclusive(
    device: &wasapi::Device,
    probe_client: &wasapi::AudioClient,
    source_spec: &AudioSpec,
    device_info: &AudioDeviceInfo,
) -> Result<ActiveWasapiStream, String> {
    use wasapi::{Direction, SampleType, StreamMode, WaveFormat, calculate_period_100ns};

    let format_candidates: &[(usize, usize, WasapiEncoding)] = if source_spec.bit_depth <= 16 {
        &[
            (16, 16, WasapiEncoding::Int16),
            (32, 24, WasapiEncoding::Int24In32),
            (24, 24, WasapiEncoding::Int24Packed),
            (32, 32, WasapiEncoding::Int32),
        ]
    } else if source_spec.bit_depth <= 24 {
        &[
            (32, 24, WasapiEncoding::Int24In32),
            (24, 24, WasapiEncoding::Int24Packed),
            (32, 32, WasapiEncoding::Int32),
            (16, 16, WasapiEncoding::Int16),
        ]
    } else {
        &[
            (32, 32, WasapiEncoding::Int32),
            (32, 24, WasapiEncoding::Int24In32),
            (24, 24, WasapiEncoding::Int24Packed),
            (16, 16, WasapiEncoding::Int16),
        ]
    };

    let src_ch = usize::from(source_spec.channel_count).clamp(1, 8);
    let dev_ch = usize::from(device_info.default_channels).clamp(1, 8);
    let ch_candidates = if src_ch == dev_ch {
        vec![src_ch]
    } else {
        vec![src_ch, dev_ch]
    };

    let mut last_failure = String::from("Exclusive format not accepted by endpoint");

    for &ch in &ch_candidates {
        for &(store_bits, valid_bits, encoding) in format_candidates {
            let requested = WaveFormat::new(
                store_bits,
                valid_bits,
                SampleType::Int,
                source_spec.sample_rate_hz as usize,
                ch,
                None,
            );
            let Ok(accepted_fmt) = probe_client.is_supported_exclusive_with_quirks(&requested)
            else {
                continue;
            };

            let mut client = match device.get_iaudioclient() {
                Ok(c) => c,
                Err(err) => {
                    last_failure = format!("Exclusive AudioClient failed: {err}");
                    continue;
                }
            };
            let Ok((def_period, min_period)) = client.get_device_period() else {
                continue;
            };
            let target_hns = (3 * min_period / 2).max(def_period).max(100_000);
            let aligned_hns = client
                .calculate_aligned_period_near(target_hns, Some(128), &accepted_fmt)
                .unwrap_or(target_hns);

            let init_attempt = client.initialize_client(
                &accepted_fmt,
                Direction::Render,
                StreamMode::EventsExclusive {
                    period_hns: aligned_hns,
                },
                None,
            );

            let final_client = match init_attempt {
                Ok(()) => Ok(client),
                Err(wasapi::WasapiError::Windows(werr))
                    if werr.code().0 == AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED =>
                {
                    let Ok(buf_frames) = client.get_buffer_size() else {
                        last_failure = "Exclusive buffer alignment query failed".to_owned();
                        continue;
                    };
                    let recovered_hns = calculate_period_100ns(
                        i64::from(buf_frames),
                        i64::from(accepted_fmt.get_samplespersec()),
                    );
                    drop(client);
                    let mut retry_client = match device.get_iaudioclient() {
                        Ok(c) => c,
                        Err(err) => {
                            last_failure = format!("Exclusive retry client failed: {err}");
                            continue;
                        }
                    };
                    retry_client
                        .initialize_client(
                            &accepted_fmt,
                            Direction::Render,
                            StreamMode::EventsExclusive {
                                period_hns: recovered_hns,
                            },
                            None,
                        )
                        .map(|()| retry_client)
                        .map_err(|err| format!("Exclusive aligned retry failed: {err}"))
                }
                Err(wasapi::WasapiError::Windows(werr))
                    if werr.code().0 == AUDCLNT_E_DEVICE_IN_USE =>
                {
                    return Err("Exclusive fallback: Endpoint in use by another app".to_owned());
                }
                Err(wasapi::WasapiError::Windows(werr))
                    if werr.code().0 == AUDCLNT_E_EXCLUSIVE_MODE_NOT_ALLOWED =>
                {
                    return Err("Exclusive fallback: Disabled in Windows Sound Settings".to_owned());
                }
                Err(err) => Err(format!("Exclusive fallback: {err}")),
            };

            match final_client {
                Ok(ready_client) => {
                    let Ok(event_handle) = ready_client.set_get_eventhandle() else {
                        last_failure = "Exclusive event handle creation failed".to_owned();
                        continue;
                    };
                    let Ok(render_client) = ready_client.get_audiorenderclient() else {
                        last_failure = "Exclusive render service failed".to_owned();
                        continue;
                    };
                    let max_buffer_frames = ready_client.get_buffer_size().unwrap_or(2048) as usize;

                    return Ok(ActiveWasapiStream {
                        audio_client: ready_client,
                        render_client,
                        event_handle,
                        encoding,
                        is_exclusive: true,
                        fallback_reason: None,
                        sample_rate_hz: accepted_fmt.get_samplespersec(),
                        bit_depth: encoding.bit_depth(),
                        channels: usize::from(accepted_fmt.get_nchannels()).clamp(1, 8),
                        block_align: accepted_fmt.get_blockalign() as usize,
                        max_buffer_frames,
                        device_info: device_info.clone(),
                    });
                }
                Err(reason) => {
                    last_failure = reason;
                }
            }
        }
    }

    Err(last_failure)
}
