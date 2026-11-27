//! Symphonia pure-Rust local audio decoder with sample-accurate seeking and CUE clip bounds.

use std::fmt;
use std::fs::File;
use std::path::Path;

use lunaria_model::AudioSpec;
use symphonia::core::codecs::audio::{AudioDecoder, AudioDecoderOptions};
use symphonia::core::errors::Error as SymphoniaError;
use symphonia::core::formats::probe::Hint;
use symphonia::core::formats::{FormatOptions, FormatReader, SeekMode, SeekTo, TrackType};
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;
use symphonia::core::units::{Time, TimeBase};

use crate::AudioError;

/// Probed container and stream metadata produced without keeping an open file handle.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DecodedTrackInfo {
    /// Audio codec, sample rate, bit depth, channels, and lossless classification.
    pub spec: AudioSpec,
    /// Human-readable decoder description for Audio Signal Path.
    pub decoder_name: String,
    /// Total duration of the underlying stream in milliseconds.
    pub duration_ms: u64,
    /// Total playable PCM frames in the underlying stream, if known.
    pub total_frames: Option<u64>,
    /// Channel count clamped to `1..=8`.
    pub channels: usize,
    /// Source sample rate in Hz.
    pub sample_rate_hz: u32,
}

fn open_format_reader(path: &Path) -> Result<(Box<dyn FormatReader>, u64), AudioError> {
    let file = File::open(path)?;
    let file_size = file.metadata().map_or(0, |meta| meta.len());
    let mss = MediaSourceStream::new(Box::new(file), Default::default());

    let mut hint = Hint::new();
    if let Some(ext) = path.extension().and_then(|s| s.to_str()) {
        let _ = hint.with_extension(ext);
    }

    let format = symphonia::default::get_probe()
        .probe(
            &hint,
            mss,
            FormatOptions::default(),
            MetadataOptions::default(),
        )
        .map_err(|err| AudioError::Probe(err.to_string()))?;

    Ok((format, file_size))
}

fn extract_track_metadata(
    format: &dyn FormatReader,
    file_size: u64,
) -> Result<
    (
        u32,
        Option<TimeBase>,
        DecodedTrackInfo,
        symphonia::core::codecs::audio::AudioCodecParameters,
    ),
    AudioError,
> {
    let track = format
        .default_track(TrackType::Audio)
        .ok_or(AudioError::NoAudioTrack)?;

    let audio_params = track
        .codec_params
        .as_ref()
        .and_then(|params| params.audio())
        .ok_or(AudioError::NoAudioTrack)?
        .clone();

    let codec_registry = symphonia::default::get_codecs();
    let codec_name = codec_registry
        .get_audio_decoder(audio_params.codec)
        .map_or_else(
            || "AUDIO".to_owned(),
            |reg| reg.codec.info.short_name.to_ascii_uppercase(),
        );

    let is_lossless = matches!(
        codec_name.as_str(),
        "FLAC" | "ALAC" | "PCM" | "WAV" | "AIFF" | "PCM_S16LE" | "PCM_S24LE" | "PCM_S32LE"
    ) || codec_name.starts_with("PCM");

    let bit_depth = audio_params
        .bits_per_sample
        .or(audio_params.bits_per_coded_sample)
        .map_or(16, |bits| bits.min(u32::from(u16::MAX)) as u16);

    let raw_channels = audio_params
        .channels
        .as_ref()
        .map_or(2, symphonia::core::audio::Channels::count);
    let channel_count_clamped = raw_channels.clamp(1, 8);
    let sample_rate_hz = audio_params.sample_rate.unwrap_or(48_000).max(8_000);

    let duration_ms = if let (Some(tb), Some(dur)) = (track.time_base, track.duration)
        && let Some(time) = tb.calc_duration(dur)
    {
        time_to_ms(time)
    } else if let Some(frames) = track.num_frames {
        frames_to_ms(frames, sample_rate_hz)
    } else {
        0
    };

    let bitrate_kbps = if duration_ms > 0 && file_size > 0 {
        ((u128::from(file_size) * 8) / u128::from(duration_ms)).min(u128::from(u32::MAX)) as u32
    } else {
        0
    };

    let decoder_name = format!("Symphonia Pure-Rust ({codec_name})");
    let total_frames = track.num_frames.or_else(|| {
        if duration_ms > 0 {
            Some(ms_to_frames(duration_ms, sample_rate_hz))
        } else {
            None
        }
    });

    let info = DecodedTrackInfo {
        spec: AudioSpec {
            codec: codec_name,
            bit_depth,
            sample_rate_hz,
            bitrate_kbps,
            channel_count: channel_count_clamped as u16,
            is_lossless,
        },
        decoder_name,
        duration_ms,
        total_frames,
        channels: channel_count_clamped,
        sample_rate_hz,
    };

    Ok((track.id, track.time_base, info, audio_params))
}

#[must_use]
const fn ms_to_frames(ms: u64, sample_rate_hz: u32) -> u64 {
    ((ms as u128 * sample_rate_hz as u128) / 1_000) as u64
}

#[must_use]
const fn frames_to_ms(frames: u64, sample_rate_hz: u32) -> u64 {
    if sample_rate_hz == 0 {
        return 0;
    }
    ((frames as u128 * 1_000) / sample_rate_hz as u128) as u64
}

#[must_use]
fn time_to_ms(time: Time) -> u64 {
    let millis = time.as_millis();
    if millis <= 0 {
        0
    } else {
        millis.min(i128::from(u64::MAX)) as u64
    }
}

/// Probes a local audio file with Symphonia and extracts its technical [`AudioSpec`].
pub fn probe_audio_file(path: &Path) -> Result<AudioSpec, AudioError> {
    probe_audio_track(path).map(|track| track.spec)
}

/// Probes a local audio file and returns full stream technical metadata and duration.
pub fn probe_audio_track(path: &Path) -> Result<DecodedTrackInfo, AudioError> {
    let (format, file_size) = open_format_reader(path)?;
    let (_, _, info, _) = extract_track_metadata(&*format, file_size)?;
    Ok(info)
}

/// Streaming Symphonia decoder producing normalized `f64` interleaved PCM frames.
pub struct AudioTrackDecoder {
    format: Box<dyn FormatReader>,
    decoder: Box<dyn AudioDecoder>,
    track_id: u32,
    time_base: Option<TimeBase>,
    info: DecodedTrackInfo,
    clip_start_ms: u64,
    clip_start_frame: u64,
    clip_end_frame: Option<u64>,
    clipped_duration_ms: u64,
    current_frame: u64,
    skip_frames_after_seek: u64,
    eof: bool,
    scratch: Vec<f64>,
}

impl fmt::Debug for AudioTrackDecoder {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("AudioTrackDecoder")
            .field("track_id", &self.track_id)
            .field("info", &self.info)
            .field("clip_start_ms", &self.clip_start_ms)
            .field("clip_end_frame", &self.clip_end_frame)
            .field("current_frame", &self.current_frame)
            .field("eof", &self.eof)
            .finish_non_exhaustive()
    }
}

impl AudioTrackDecoder {
    /// Opens `path` for streaming decode, honoring optional `.cue` virtual clip bounds.
    pub fn open(
        path: &Path,
        clip_start_ms: Option<u64>,
        clip_end_ms: Option<u64>,
    ) -> Result<Self, AudioError> {
        let (format, file_size) = open_format_reader(path)?;
        let (track_id, time_base, info, audio_params) =
            extract_track_metadata(&*format, file_size)?;

        let decoder = symphonia::default::get_codecs()
            .make_audio_decoder(&audio_params, &AudioDecoderOptions::default())
            .map_err(|err| AudioError::Decode(err.to_string()))?;

        let start_ms = clip_start_ms.unwrap_or(0);
        let clip_start_frame = ms_to_frames(start_ms, info.sample_rate_hz);
        let clip_end_frame = clip_end_ms
            .map(|end_ms| ms_to_frames(end_ms, info.sample_rate_hz).max(clip_start_frame));

        let clipped_duration_ms = match clip_end_ms {
            Some(end_ms) if end_ms > start_ms => {
                let raw = end_ms - start_ms;
                if info.duration_ms > 0 {
                    raw.min(info.duration_ms.saturating_sub(start_ms))
                } else {
                    raw
                }
            }
            _ => info.duration_ms.saturating_sub(start_ms),
        };

        let mut instance = Self {
            format,
            decoder,
            track_id,
            time_base,
            info,
            clip_start_ms: start_ms,
            clip_start_frame,
            clip_end_frame,
            clipped_duration_ms,
            current_frame: 0,
            skip_frames_after_seek: 0,
            eof: false,
            scratch: Vec::with_capacity(8192),
        };

        if start_ms > 0 {
            let _ = instance.seek_ms(0)?;
        }

        Ok(instance)
    }

    /// Returns static track metadata (codec, sample rate, bit depth, channels, decoder name).
    #[must_use]
    pub const fn info(&self) -> &DecodedTrackInfo {
        &self.info
    }

    /// Returns effective playable duration in milliseconds (clipped if this is a `.cue` track).
    #[must_use]
    pub const fn duration_ms(&self) -> u64 {
        self.clipped_duration_ms
    }

    /// Returns current decode cursor relative to clip start in milliseconds.
    #[must_use]
    pub const fn position_ms(&self) -> u64 {
        frames_to_ms(
            self.current_frame.saturating_sub(self.clip_start_frame),
            self.info.sample_rate_hz,
        )
    }

    /// Returns `true` when EOF or `.cue` clip end has been reached.
    #[must_use]
    pub const fn is_eof(&self) -> bool {
        self.eof
    }

    /// Performs sample-accurate seek to `relative_ms` within this track/clip and returns the target position.
    pub fn seek_ms(&mut self, relative_ms: u64) -> Result<u64, AudioError> {
        let clamped_rel = if self.clipped_duration_ms > 20 {
            relative_ms.min(self.clipped_duration_ms - 10)
        } else {
            0
        };
        let target_abs_ms = self.clip_start_ms.saturating_add(clamped_rel);
        let seek_res = self.format.seek(
            SeekMode::Accurate,
            SeekTo::Time {
                time: Time::from_millis_u64(target_abs_ms),
                track_id: Some(self.track_id),
            },
        );

        let seeked = seek_res.map_err(|err| AudioError::Seek(err.to_string()))?;
        self.decoder.reset();
        self.eof = false;

        let target_frame = ms_to_frames(target_abs_ms, self.info.sample_rate_hz);
        let actual_frame = self
            .time_base
            .and_then(|tb| tb.calc_time(seeked.actual_ts))
            .map_or(target_frame, |time| {
                let nanos = time.as_nanos().max(0);
                ((nanos * i128::from(self.info.sample_rate_hz)) / 1_000_000_000) as u64
            });

        self.skip_frames_after_seek = target_frame.saturating_sub(actual_frame);
        self.current_frame = actual_frame;

        Ok(clamped_rel)
    }

    /// Decodes the next non-empty packet of interleaved `f64` frames into `out`.
    ///
    /// Returns the number of decoded frames written (`0` indicates end of stream / clip end).
    pub fn read_interleaved_f64(&mut self, out: &mut Vec<f64>) -> Result<usize, AudioError> {
        out.clear();
        if self.eof {
            return Ok(0);
        }

        let target_channels = self.info.channels;

        loop {
            if let Some(end_frame) = self.clip_end_frame
                && self.current_frame >= end_frame
            {
                self.eof = true;
                return Ok(0);
            }

            let packet = match self.format.next_packet() {
                Ok(Some(packet)) => packet,
                Ok(None) => {
                    self.eof = true;
                    return Ok(0);
                }
                Err(SymphoniaError::ResetRequired) => {
                    self.decoder.reset();
                    continue;
                }
                Err(SymphoniaError::IoError(ref io_err))
                    if io_err.kind() == std::io::ErrorKind::UnexpectedEof =>
                {
                    self.eof = true;
                    return Ok(0);
                }
                Err(err) => return Err(AudioError::Decode(err.to_string())),
            };

            if packet.track_id != self.track_id {
                continue;
            }

            let audio_buf = match self.decoder.decode(&packet) {
                Ok(buf) => buf,
                Err(SymphoniaError::DecodeError(msg)) => {
                    tracing::debug!(reason = msg, "skipping malformed audio packet");
                    continue;
                }
                Err(SymphoniaError::ResetRequired) => {
                    self.decoder.reset();
                    continue;
                }
                Err(err) => return Err(AudioError::Decode(err.to_string())),
            };

            let frames = audio_buf.frames();
            let buf_channels = audio_buf.num_planes();
            if frames == 0 || buf_channels == 0 {
                continue;
            }

            audio_buf.copy_to_vec_interleaved(&mut self.scratch);

            let skip = (self.skip_frames_after_seek as usize).min(frames);
            self.skip_frames_after_seek -= skip as u64;
            self.current_frame = self.current_frame.saturating_add(skip as u64);

            let available_frames = frames - skip;
            if available_frames == 0 {
                continue;
            }

            let take_frames = if let Some(end_frame) = self.clip_end_frame {
                let remaining = end_frame.saturating_sub(self.current_frame) as usize;
                if remaining == 0 {
                    self.eof = true;
                    return Ok(0);
                }
                available_frames.min(remaining)
            } else {
                available_frames
            };

            if buf_channels == target_channels {
                let start_idx = skip * buf_channels;
                let end_idx = (skip + take_frames) * buf_channels;
                out.extend_from_slice(&self.scratch[start_idx..end_idx]);
            } else {
                out.reserve(take_frames * target_channels);
                for f in skip..(skip + take_frames) {
                    let base = f * buf_channels;
                    for c in 0..target_channels {
                        out.push(self.scratch[base + c.min(buf_channels - 1)]);
                    }
                }
            }

            self.current_frame = self.current_frame.saturating_add(take_frames as u64);
            if let Some(end_frame) = self.clip_end_frame
                && self.current_frame >= end_frame
            {
                self.eof = true;
            }

            return Ok(take_frames);
        }
    }
}
