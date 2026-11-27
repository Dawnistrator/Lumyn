//! Error types returned by native Windows shell integration (`lunaria-win`).

use thiserror::Error;

/// Unified error type for SMTC, tray, taskbar, single-instance IPC, and drop hooks.
#[derive(Debug, Error)]
pub enum WinError {
    /// Platform shell API unavailable or failed in the active desktop session.
    #[error("windows shell integration failed: {0}")]
    Platform(String),

    /// Underlying OS I/O error during named-pipe IPC.
    #[error("windows IPC error: {0}")]
    Io(#[from] std::io::Error),
}
