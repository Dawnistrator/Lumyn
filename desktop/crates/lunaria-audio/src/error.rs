//! Error types for Lunaria audio decoding, resampling, WASAPI output, and engine control.

use thiserror::Error;

/// Errors returned by audio device discovery, decoding, resampling, or WASAPI playback.
#[derive(Debug, Error)]
pub enum AudioError {
    /// Failed to open or read a local audio file.
    #[error("failed to read local audio file: {0}")]
    Io(#[from] std::io::Error),

    /// Symphonia could not probe or parse the container format.
    #[error("failed to probe audio container: {0}")]
    Probe(String),

    /// Container contains no playable audio stream.
    #[error("no playable audio stream found in file")]
    NoAudioTrack,

    /// Codec decoder initialization or packet decoding failed.
    #[error("audio decode error: {0}")]
    Decode(String),

    /// Seeking inside the media container failed.
    #[error("audio seek error: {0}")]
    Seek(String),

    /// Resampler setup or chunk conversion failed.
    #[error("audio resampler error: {0}")]
    Resampler(String),

    /// Windows WASAPI endpoint discovery or stream rendering failed.
    #[error("wasapi audio error: {0}")]
    Wasapi(String),

    /// Playback engine worker thread has shut down.
    #[error("playback engine has been closed")]
    EngineClosed,

    /// Invalid caller request or unsupported parameter.
    #[error("invalid playback request: {0}")]
    InvalidRequest(String),
}
