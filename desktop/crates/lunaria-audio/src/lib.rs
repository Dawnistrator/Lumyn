//! Lunaria for Windows audio decoder, sample-rate converter, WSOLA speed processor, and WASAPI
//! playback engine (`#![forbid(unsafe_code)]`).

#![forbid(unsafe_code)]

pub mod decoder;
pub mod devices;
pub mod engine;
pub mod error;
pub mod resampler;
pub mod signal_path;
pub mod time_stretch;
pub mod wasapi_out;

pub use decoder::{AudioTrackDecoder, DecodedTrackInfo, probe_audio_file, probe_audio_track};
pub use devices::{
    DeviceHotPlugEvent, classify_device_category, enumerate_render_devices, play_test_chime,
};
pub use engine::{
    FileHandleSuspension, PlaybackEngine, PlaybackRequest, PlaybackStatus, PlaybackTelemetry,
};
pub use error::AudioError;
pub use resampler::AudioResampler;
pub use signal_path::{
    SignalPathInput, build_preview_signal_path, build_preview_signal_path_with_dsp,
    build_signal_path,
};
pub use time_stretch::WsolaTimeStretcher;
pub use wasapi_out::WasapiEncoding;

#[cfg(windows)]
pub(crate) use devices::ComMtaGuard;
pub(crate) use devices::HotPlugWatcher;
pub(crate) use wasapi_out::pack_frames_to_pcm_bytes;
#[cfg(windows)]
pub(crate) use wasapi_out::{ActiveWasapiStream, is_device_invalidated_error, open_wasapi_stream};

#[cfg(test)]
mod tests;
