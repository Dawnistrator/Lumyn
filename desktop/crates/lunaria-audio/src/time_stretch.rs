//! Pure-Rust pitch-preserving WSOLA playback speed processor (`0.5x..=2.0x`).

use std::f64::consts::PI;

use lunaria_model::clamp_playback_speed;

/// Waveform Similarity Overlap-Add (WSOLA) time-stretcher with zero-cost `1.0x` bypass.
#[derive(Debug, Clone)]
pub struct WsolaTimeStretcher {
    channels: usize,
    speed: f32,
    window_frames: usize,
    synthesis_hop: usize,
    search_radius: usize,
    hann: Vec<f64>,
    input_buf: Vec<f64>,
    input_offset: usize,
    frac_pos: f64,
    overlap_tail: Vec<f64>,
    expected_next_mono: Vec<f64>,
    has_prev_grain: bool,
}

impl WsolaTimeStretcher {
    /// Creates a WSOLA speed processor configured for `sample_rate_hz`, `channels`, and `speed`.
    #[must_use]
    pub fn new(sample_rate_hz: u32, channels: usize, speed: f32) -> Self {
        let clamped_ch = channels.clamp(1, 8);
        let clamped_speed = clamp_playback_speed(speed);
        let mut window_frames = ((sample_rate_hz as usize) / 25).clamp(256, 4096) & !1;
        if window_frames < 256 {
            window_frames = 256;
        }
        let synthesis_hop = window_frames / 2;
        let search_radius = (window_frames / 4).max(32);

        let mut hann = Vec::with_capacity(window_frames);
        for i in 0..window_frames {
            let w = 0.5 * (1.0 - (2.0 * PI * (i as f64) / (window_frames as f64)).cos());
            hann.push(w);
        }

        Self {
            channels: clamped_ch,
            speed: clamped_speed,
            window_frames,
            synthesis_hop,
            search_radius,
            hann,
            input_buf: Vec::with_capacity((window_frames + search_radius) * 2 * clamped_ch),
            input_offset: 0,
            frac_pos: 0.0,
            overlap_tail: vec![0.0; synthesis_hop * clamped_ch],
            expected_next_mono: vec![0.0; synthesis_hop],
            has_prev_grain: false,
        }
    }

    /// Returns `true` when speed differs noticeably from `1.0x`.
    #[must_use]
    pub fn is_active(&self) -> bool {
        (self.speed - 1.0).abs() >= 0.005
    }

    /// Returns the clamped active speed multiplier (`0.5..=2.0`).
    #[must_use]
    pub const fn speed(&self) -> f32 {
        self.speed
    }

    /// Updates playback speed dynamically (`0.5..=2.0`), resetting overlap state if switching to bypass.
    pub fn set_speed(&mut self, speed: f32) {
        let clamped = clamp_playback_speed(speed);
        let was_active = self.is_active();
        self.speed = clamped;
        if was_active && !self.is_active() {
            self.reset();
        }
    }

    /// Clears queued audio frames and overlap-add history (called on seek or track reset).
    pub fn reset(&mut self) {
        self.input_buf.clear();
        self.input_offset = 0;
        self.frac_pos = 0.0;
        self.overlap_tail.fill(0.0);
        self.expected_next_mono.fill(0.0);
        self.has_prev_grain = false;
    }

    /// Feeds interleaved `f64` input frames and appends time-stretched output frames to `output`.
    ///
    /// Returns the number of output frames appended.
    pub fn process(&mut self, input: &[f64], output: &mut Vec<f64>) -> usize {
        if input.is_empty() {
            return 0;
        }
        if !self.is_active() {
            output.extend_from_slice(input);
            return input.len() / self.channels;
        }

        if self.input_offset >= self.input_buf.len() {
            self.input_buf.clear();
            self.input_offset = 0;
        }
        self.input_buf.extend_from_slice(input);

        let ch = self.channels;
        let analysis_hop = (self.synthesis_hop as f64) * f64::from(self.speed);
        let mut produced_frames = 0usize;

        loop {
            let available_frames = (self.input_buf.len() - self.input_offset) / ch;
            let nom_abs = self.frac_pos.round() as usize;
            let rel_nom = nom_abs.saturating_sub(self.input_offset / ch);

            if rel_nom + self.search_radius + self.window_frames > available_frames {
                break;
            }

            let best_rel = if !self.has_prev_grain {
                rel_nom
            } else {
                self.find_best_offset(rel_nom)
            };

            let base_idx = self.input_offset + best_rel * ch;
            if !self.has_prev_grain {
                // First grain: emit unwindowed leading half so track start stays crisp.
                let emit_end = base_idx + self.synthesis_hop * ch;
                output.extend_from_slice(&self.input_buf[base_idx..emit_end]);
                self.has_prev_grain = true;
            } else {
                output.reserve(self.synthesis_hop * ch);
                for k in 0..self.synthesis_hop {
                    let w_in = self.hann[k];
                    let in_frame = base_idx + k * ch;
                    let tail_frame = k * ch;
                    for c in 0..ch {
                        let sample =
                            self.overlap_tail[tail_frame + c] + self.input_buf[in_frame + c] * w_in;
                        output.push(sample.clamp(-1.5, 1.5));
                    }
                }
            }

            for k in 0..self.synthesis_hop {
                let w_out = self.hann[self.synthesis_hop + k];
                let in_frame = base_idx + (self.synthesis_hop + k) * ch;
                let tail_frame = k * ch;
                let mut mono_sum = 0.0;
                for c in 0..ch {
                    let s = self.input_buf[in_frame + c];
                    mono_sum += s;
                    self.overlap_tail[tail_frame + c] = s * w_out;
                }
                self.expected_next_mono[k] = mono_sum / (ch as f64);
            }

            produced_frames += self.synthesis_hop;
            self.frac_pos += analysis_hop;
            self.compact_input_history();
        }

        produced_frames
    }

    /// Emits remaining tail samples cleanly when the end of stream is reached.
    pub fn flush(&mut self, output: &mut Vec<f64>) -> usize {
        if !self.is_active() {
            return 0;
        }
        let ch = self.channels;
        let mut flushed_frames = 0usize;

        if self.has_prev_grain {
            output.extend_from_slice(&self.overlap_tail);
            flushed_frames += self.synthesis_hop;
        }

        let start_frame = self.frac_pos.round() as usize;
        let buf_start_frame = self.input_offset / ch;
        let rel_frame = start_frame.saturating_sub(buf_start_frame);
        let available_frames = (self.input_buf.len() - self.input_offset) / ch;
        if rel_frame < available_frames {
            let tail_start = self.input_offset + rel_frame * ch;
            let tail_frames = available_frames - rel_frame;
            let fade_frames = tail_frames.min(128);
            for f in 0..tail_frames {
                let env = if tail_frames - f <= fade_frames {
                    (tail_frames - f) as f64 / fade_frames as f64
                } else {
                    1.0
                };
                for c in 0..ch {
                    output.push(self.input_buf[tail_start + f * ch + c] * env);
                }
            }
            flushed_frames += tail_frames;
        }

        self.reset();
        flushed_frames
    }

    fn find_best_offset(&self, rel_nom: usize) -> usize {
        let ch = self.channels;
        let min_off = rel_nom.saturating_sub(self.search_radius);
        let max_off = rel_nom + self.search_radius;

        let mut best_off = rel_nom;
        let mut best_score = f64::NEG_INFINITY;

        for cand in min_off..=max_off {
            let mut dot = 0.0_f64;
            let mut energy = 0.0_f64;
            let base = self.input_offset + cand * ch;
            for k in 0..self.synthesis_hop {
                let mut mono = 0.0_f64;
                for c in 0..ch {
                    mono += self.input_buf[base + k * ch + c];
                }
                mono /= ch as f64;
                dot += mono * self.expected_next_mono[k];
                energy += mono * mono;
            }
            let penalty = (cand.abs_diff(rel_nom) as f64) * 1e-6;
            let score = (dot / (energy.sqrt() + 1e-12)) - penalty;
            if score > best_score {
                best_score = score;
                best_off = cand;
            }
        }

        best_off
    }

    fn compact_input_history(&mut self) {
        let ch = self.channels;
        let nom_abs = self.frac_pos.floor() as usize;
        let keep_from = nom_abs.saturating_sub(self.search_radius + 16);
        let min_abs = self.input_offset / ch;
        if keep_from > min_abs + self.window_frames {
            let drop_frames = keep_from - min_abs;
            let drop_samples = drop_frames * ch;
            self.input_offset += drop_samples;
            if self.input_offset * 2 >= self.input_buf.len() {
                let remaining = self.input_buf.len() - self.input_offset;
                self.input_buf.copy_within(self.input_offset.., 0);
                self.input_buf.truncate(remaining);
                let dropped_frames_total = self.input_offset / ch;
                self.frac_pos = (self.frac_pos - dropped_frames_total as f64).max(0.0);
                self.input_offset = 0;
            }
        }
    }
}
