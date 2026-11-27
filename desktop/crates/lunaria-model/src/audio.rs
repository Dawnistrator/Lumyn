//! Audio output categories, technical stream specs, and signal path models.

use serde::{Deserialize, Serialize};

/// Audio processing mode exposed in Settings and Audio Signal Path.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default, Serialize, Deserialize)]
pub enum AudioOutputMode {
    /// End-to-end 32-bit float DSP chain via WASAPI Shared mode.
    #[default]
    DspFloat32,
    /// Mixer-bypassing native PCM path via WASAPI Exclusive mode where supported.
    PuristDirectBitPerfect,
}

impl AudioOutputMode {
    /// Human-readable short label for UI badges.
    #[must_use]
    pub const fn label(self) -> &'static str {
        match self {
            Self::DspFloat32 => "DSP 32-bit Float",
            Self::PuristDirectBitPerfect => "Purist Bit-Perfect",
        }
    }
}

/// Hardware category of an output audio endpoint.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default, Serialize, Deserialize)]
pub enum AudioOutputCategory {
    /// External USB DAC or dongle interface.
    UsbDac,
    /// Bluetooth A2DP wireless sink (never bit-perfect due to lossy/system stack encoding).
    BluetoothA2dp,
    /// Wired headphones or line-out jack.
    Wired,
    /// Built-in speakers.
    #[default]
    Speaker,
    /// Other virtual or unrecognized endpoint.
    Other,
}

impl AudioOutputCategory {
    /// Display label used in device lists and Signal Path headers.
    #[must_use]
    pub const fn label(self) -> &'static str {
        match self {
            Self::UsbDac => "USB DAC",
            Self::BluetoothA2dp => "Bluetooth A2DP",
            Self::Wired => "Wired / Line Out",
            Self::Speaker => "Speakers",
            Self::Other => "Audio Output",
        }
    }
}

/// Technical description of an audio stream or file container.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AudioSpec {
    /// Short uppercase codec label (e.g. `FLAC`, `WAV`, `ALAC`, `MP3`).
    pub codec: String,
    /// Bit depth per PCM sample (e.g. `16`, `24`, `32`).
    pub bit_depth: u16,
    /// Sample rate in Hz (e.g. `44_100`, `96_000`, `192_000`).
    pub sample_rate_hz: u32,
    /// Average bitrate in kbps (`0` if unknown or uncompressed stream).
    pub bitrate_kbps: u32,
    /// Channel count (e.g. `2` for stereo).
    pub channel_count: u16,
    /// Whether the underlying codec preserves PCM samples losslessly.
    pub is_lossless: bool,
}

fn format_sample_rate_khz(sample_rate_hz: u32) -> String {
    if sample_rate_hz.is_multiple_of(1000) {
        format!("{}", sample_rate_hz / 1000)
    } else {
        let khz = f64::from(sample_rate_hz) / 1000.0;
        format!("{khz:.1}")
    }
}

impl AudioSpec {
    /// Returns `true` when lossless and exceeding CD quality (`>= 24-bit` or `> 48 kHz`).
    #[must_use]
    pub const fn is_hi_res(&self) -> bool {
        self.is_lossless && (self.bit_depth >= 24 || self.sample_rate_hz > 48_000)
    }

    /// Formats a compact studio summary such as `FLAC • 24-bit / 192 kHz` or `MP3 • 320 kbps / 44.1 kHz`.
    #[must_use]
    pub fn format_quality_summary(&self) -> String {
        let khz_text = format_sample_rate_khz(self.sample_rate_hz);
        if self.is_lossless && self.bit_depth > 0 {
            format!("{} • {}-bit / {khz_text} kHz", self.codec, self.bit_depth)
        } else if self.bitrate_kbps > 0 {
            format!(
                "{} • {} kbps / {khz_text} kHz",
                self.codec, self.bitrate_kbps
            )
        } else if self.bit_depth > 0 {
            format!("{} • {}-bit / {khz_text} kHz", self.codec, self.bit_depth)
        } else {
            format!("{} • {khz_text} kHz", self.codec)
        }
    }

    /// Formats codec, bitrate, and sample rate for Discord Rich Presence (e.g. `FLAC • 1682 kbps • 44.1 kHz`).
    #[must_use]
    pub fn format_discord_quality(&self) -> String {
        let khz_text = format_sample_rate_khz(self.sample_rate_hz);
        if self.bitrate_kbps > 0 {
            format!(
                "{} • {} kbps • {khz_text} kHz",
                self.codec, self.bitrate_kbps
            )
        } else if self.bit_depth > 0 {
            format!("{} • {}-bit • {khz_text} kHz", self.codec, self.bit_depth)
        } else {
            format!("{} • {khz_text} kHz", self.codec)
        }
    }
}

/// Discovered Windows WASAPI render device metadata.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AudioDeviceInfo {
    /// Stable WASAPI endpoint ID string.
    pub id: String,
    /// Friendly Windows endpoint name.
    pub name: String,
    /// Whether this device is currently the Windows default multimedia render endpoint.
    pub is_default: bool,
    /// Classified physical/transport category.
    pub category: AudioOutputCategory,
    /// Current shared mix sample rate in Hz.
    pub default_sample_rate_hz: u32,
    /// Current shared mix bit depth.
    pub default_bit_depth: u16,
    /// Current shared mix channel count.
    pub default_channels: u16,
}

impl AudioDeviceInfo {
    /// Concise monospace summary of the endpoint's active mix format.
    #[must_use]
    pub fn mix_format_summary(&self) -> String {
        let khz_text = format_sample_rate_khz(self.default_sample_rate_hz);
        format!(
            "{}-bit / {khz_text} kHz • {} ch",
            self.default_bit_depth, self.default_channels
        )
    }
}

/// Transparent representation of every active stage from source file to hardware endpoint.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AudioSignalPath {
    /// Summary of source format and quality.
    pub source_format_label: String,
    /// Active decoder implementation.
    pub decoder_name: String,
    /// Ordered processing stages between decoder and hardware output.
    pub processing_chain: Vec<String>,
    /// Friendly name of the active output endpoint.
    pub output_device_name: String,
    /// Output sample rate in Hz.
    pub output_sample_rate_hz: u32,
    /// Output bit depth.
    pub output_bit_depth: u16,
    /// Whether Windows mixer bypass is active.
    pub is_direct_playback_active: bool,
    /// Whether bit-perfect unresampled native output is active.
    pub is_bit_perfect_active: bool,
    /// Selected user output mode.
    pub output_mode: AudioOutputMode,
    /// Endpoint transport category.
    pub output_category: AudioOutputCategory,
    /// Active PCM sample representation label.
    pub output_encoding_label: String,
}
