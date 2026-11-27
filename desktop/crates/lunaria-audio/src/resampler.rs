//! High-precision 64-bit Rubato sample-rate converter with automatic bypass on matching rates.

use std::fmt;

use rubato::audioadapter_buffers::direct::InterleavedSlice;
use rubato::{Async, Fft, FixedAsync, FixedSync, Indexing, Resampler, SincInterpolationParameters};

use crate::AudioError;

const RESAMPLER_CHUNK_FRAMES: usize = 1024;
const MAX_FFT_BLOCK_MULTIPLE: usize = 2048;

enum ResamplerBackend {
    Bypass,
    SyncFft(Box<Fft<f64>>),
    AsyncSinc(Box<Async<f64>>),
}

/// 64-bit floating-point resampler wrapping `rubato` with transparent pass-through when rates match.
pub struct AudioResampler {
    input_rate_hz: u32,
    output_rate_hz: u32,
    channels: usize,
    backend: ResamplerBackend,
    label: Option<String>,
    staging: Vec<f64>,
    staging_offset: usize,
    out_scratch: Vec<f64>,
    delay_frames_to_trim: usize,
}

impl fmt::Debug for AudioResampler {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("AudioResampler")
            .field("input_rate_hz", &self.input_rate_hz)
            .field("output_rate_hz", &self.output_rate_hz)
            .field("channels", &self.channels)
            .field("active", &self.is_active())
            .field("label", &self.label)
            .finish_non_exhaustive()
    }
}

const fn gcd_usize(mut a: usize, mut b: usize) -> usize {
    while b != 0 {
        let r = a % b;
        a = b;
        b = r;
    }
    if a == 0 { 1 } else { a }
}

impl AudioResampler {
    /// Creates a resampler converting `input_rate_hz` -> `output_rate_hz` across `channels`.
    ///
    /// When input and output sample rates match, enters zero-CPU passthrough mode.
    pub fn new(
        input_rate_hz: u32,
        output_rate_hz: u32,
        channels: usize,
    ) -> Result<Self, AudioError> {
        if input_rate_hz == 0 || output_rate_hz == 0 {
            return Err(AudioError::Resampler(format!(
                "invalid sample rates {input_rate_hz} -> {output_rate_hz} Hz"
            )));
        }
        if !(1..=8).contains(&channels) {
            return Err(AudioError::Resampler(format!(
                "unsupported channel count {channels}"
            )));
        }

        if input_rate_hz == output_rate_hz {
            return Ok(Self {
                input_rate_hz,
                output_rate_hz,
                channels,
                backend: ResamplerBackend::Bypass,
                label: None,
                staging: Vec::new(),
                staging_offset: 0,
                out_scratch: Vec::new(),
                delay_frames_to_trim: 0,
            });
        }

        let in_rate = input_rate_hz as usize;
        let out_rate = output_rate_hz as usize;
        let divisor = gcd_usize(in_rate, out_rate);
        let min_fft_block = in_rate / divisor;

        let (backend, label, max_out_frames, delay_frames_to_trim) =
            if min_fft_block <= MAX_FFT_BLOCK_MULTIPLE {
                let fft = Fft::<f64>::new(
                    in_rate,
                    out_rate,
                    RESAMPLER_CHUNK_FRAMES,
                    channels,
                    FixedSync::Input,
                )
                .map_err(|err| AudioError::Resampler(err.to_string()))?;
                let max_out = fft.output_frames_max();
                let delay = fft.output_delay();
                (
                    ResamplerBackend::SyncFft(Box::new(fft)),
                    Some(format!(
                        "Rubato 64-bit FFT ({} → {} Hz)",
                        input_rate_hz, output_rate_hz
                    )),
                    max_out,
                    delay,
                )
            } else {
                let ratio = f64::from(output_rate_hz) / f64::from(input_rate_hz);
                let params = SincInterpolationParameters::default();
                let sinc = Async::<f64>::new_sinc(
                    ratio,
                    1.05,
                    &params,
                    RESAMPLER_CHUNK_FRAMES,
                    channels,
                    FixedAsync::Input,
                )
                .map_err(|err| AudioError::Resampler(err.to_string()))?;
                let max_out = sinc.output_frames_max();
                let delay = sinc.output_delay();
                (
                    ResamplerBackend::AsyncSinc(Box::new(sinc)),
                    Some(format!(
                        "Rubato 64-bit Sinc ({} → {} Hz)",
                        input_rate_hz, output_rate_hz
                    )),
                    max_out,
                    delay,
                )
            };

        Ok(Self {
            input_rate_hz,
            output_rate_hz,
            channels,
            backend,
            label,
            staging: Vec::with_capacity(RESAMPLER_CHUNK_FRAMES * 4 * channels),
            staging_offset: 0,
            out_scratch: vec![0.0; max_out_frames * channels],
            delay_frames_to_trim,
        })
    }

    /// Returns `true` when sample rate conversion is actively running.
    #[must_use]
    pub const fn is_active(&self) -> bool {
        !matches!(self.backend, ResamplerBackend::Bypass)
    }

    /// Human-readable resampler description for Audio Signal Path (`None` when bypassed).
    #[must_use]
    pub fn label(&self) -> Option<&str> {
        self.label.as_deref()
    }

    /// Clears staged samples and resets filter delay state after a seek or stream restart.
    pub fn reset(&mut self) {
        self.staging.clear();
        self.staging_offset = 0;
        self.delay_frames_to_trim = match &mut self.backend {
            ResamplerBackend::Bypass => 0,
            ResamplerBackend::SyncFft(r) => {
                r.reset();
                r.output_delay()
            }
            ResamplerBackend::AsyncSinc(r) => {
                r.reset();
                r.output_delay()
            }
        };
    }

    /// Processes interleaved input frames and appends resampled frames to `output`.
    ///
    /// Returns the number of output frames appended.
    pub fn process(&mut self, input: &[f64], output: &mut Vec<f64>) -> Result<usize, AudioError> {
        if input.is_empty() {
            return Ok(0);
        }
        if matches!(self.backend, ResamplerBackend::Bypass) {
            output.extend_from_slice(input);
            return Ok(input.len() / self.channels);
        }

        if self.staging_offset >= self.staging.len() {
            self.staging.clear();
            self.staging_offset = 0;
        }
        self.staging.extend_from_slice(input);

        let mut total_out_frames = 0usize;
        let ch = self.channels;

        loop {
            let needed_in = self.input_frames_next();
            let avail_frames = (self.staging.len() - self.staging_offset) / ch;
            if avail_frames < needed_in {
                break;
            }

            let in_slice = &self.staging[self.staging_offset..self.staging_offset + needed_in * ch];
            let produced = Self::process_chunk(
                &mut self.backend,
                in_slice,
                needed_in,
                ch,
                None,
                &mut self.out_scratch,
            )?;

            self.staging_offset += needed_in * ch;
            total_out_frames += self.append_trimmed_output(produced, output);
        }

        if self.staging_offset > 0 && self.staging_offset * 2 >= self.staging.len() {
            let remaining = self.staging.len() - self.staging_offset;
            self.staging.copy_within(self.staging_offset.., 0);
            self.staging.truncate(remaining);
            self.staging_offset = 0;
        }

        Ok(total_out_frames)
    }

    /// Flushes any remaining staged input frames and filter tail at end of stream.
    ///
    /// Returns the number of output frames appended.
    pub fn flush(&mut self, output: &mut Vec<f64>) -> Result<usize, AudioError> {
        if matches!(self.backend, ResamplerBackend::Bypass) {
            return Ok(0);
        }

        let ch = self.channels;
        let mut total_out_frames = 0usize;

        while self.staging.len() > self.staging_offset {
            let avail_frames = (self.staging.len() - self.staging_offset) / ch;
            if avail_frames == 0 {
                break;
            }
            let needed_in = self.input_frames_next();
            if avail_frames >= needed_in {
                let in_slice =
                    &self.staging[self.staging_offset..self.staging_offset + needed_in * ch];
                let produced = Self::process_chunk(
                    &mut self.backend,
                    in_slice,
                    needed_in,
                    ch,
                    None,
                    &mut self.out_scratch,
                )?;
                self.staging_offset += needed_in * ch;
                total_out_frames += self.append_trimmed_output(produced, output);
            } else {
                let target_end = self.staging_offset + needed_in * ch;
                self.staging.resize(target_end, 0.0);
                let in_slice = &self.staging[self.staging_offset..target_end];
                let indexing = Indexing::new().partial_len(avail_frames);
                let produced = Self::process_chunk(
                    &mut self.backend,
                    in_slice,
                    needed_in,
                    ch,
                    Some(&indexing),
                    &mut self.out_scratch,
                )?;
                self.staging_offset = target_end;

                let ratio = f64::from(self.output_rate_hz) / f64::from(self.input_rate_hz);
                let expected_out = ((avail_frames as f64) * ratio).ceil() as usize;

                // Drain startup/filter delay if any frames remain queued in the resampler.
                if self.delay_frames_to_trim > 0 {
                    total_out_frames += self.append_trimmed_output(produced, output);
                    let zero_in_frames = self.input_frames_next();
                    let zero_len = zero_in_frames * ch;
                    self.staging.clear();
                    self.staging_offset = 0;
                    self.staging.resize(zero_len, 0.0);
                    let tail_indexing = Indexing::new().partial_len(0);
                    let tail_produced = Self::process_chunk(
                        &mut self.backend,
                        &self.staging,
                        zero_in_frames,
                        ch,
                        Some(&tail_indexing),
                        &mut self.out_scratch,
                    )?;
                    let capped = tail_produced.min(expected_out);
                    total_out_frames += self.append_trimmed_output(capped, output);
                } else {
                    let capped = produced.min(expected_out);
                    total_out_frames += self.append_trimmed_output(capped, output);
                }
            }
        }

        self.staging.clear();
        self.staging_offset = 0;
        Ok(total_out_frames)
    }

    fn input_frames_next(&self) -> usize {
        match &self.backend {
            ResamplerBackend::Bypass => 0,
            ResamplerBackend::SyncFft(r) => r.input_frames_next(),
            ResamplerBackend::AsyncSinc(r) => r.input_frames_next(),
        }
    }

    fn process_chunk(
        backend: &mut ResamplerBackend,
        in_slice: &[f64],
        in_frames: usize,
        channels: usize,
        indexing: Option<&Indexing>,
        out_scratch: &mut [f64],
    ) -> Result<usize, AudioError> {
        let out_cap = out_scratch.len() / channels;
        let in_adapter = InterleavedSlice::new(in_slice, channels, in_frames)
            .map_err(|err| AudioError::Resampler(err.to_string()))?;
        let mut out_adapter = InterleavedSlice::new_mut(out_scratch, channels, out_cap)
            .map_err(|err| AudioError::Resampler(err.to_string()))?;

        let (_, produced) = match backend {
            ResamplerBackend::Bypass => return Ok(0),
            ResamplerBackend::SyncFft(r) => r
                .process_into_buffer(&in_adapter, &mut out_adapter, indexing)
                .map_err(|err| AudioError::Resampler(err.to_string()))?,
            ResamplerBackend::AsyncSinc(r) => r
                .process_into_buffer(&in_adapter, &mut out_adapter, indexing)
                .map_err(|err| AudioError::Resampler(err.to_string()))?,
        };

        Ok(produced)
    }

    fn append_trimmed_output(&mut self, produced_frames: usize, output: &mut Vec<f64>) -> usize {
        if produced_frames == 0 {
            return 0;
        }
        let trim = self.delay_frames_to_trim.min(produced_frames);
        self.delay_frames_to_trim -= trim;
        let keep = produced_frames - trim;
        if keep == 0 {
            return 0;
        }
        let ch = self.channels;
        output.extend_from_slice(&self.out_scratch[trim * ch..(trim + keep) * ch]);
        keep
    }
}
