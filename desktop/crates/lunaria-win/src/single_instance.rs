//! Single-instance Windows named mutex and named pipe IPC forwarder.
//!
//! Ensures clicking multiple audio files or playlists in Windows Explorer activates the running
//! Lunaria window and enqueues/plays the opened files instead of launching duplicate processes.

use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread::{self, JoinHandle};

use crossbeam_channel::Sender;

use crate::{ShellCommand, WinError};

/// Result of attempting to acquire the single-instance lock for this Windows session.
#[derive(Debug)]
pub enum SingleInstanceOutcome {
    /// This process is the primary instance; keep the guard alive until shutdown.
    Primary(SingleInstanceGuard),
    /// Another instance was already running and received our activation + file paths.
    ForwardedToExisting,
}

/// Lifetime guard owning the named mutex and IPC listener thread.
#[derive(Debug)]
pub struct SingleInstanceGuard {
    #[cfg(windows)]
    inner: Option<WinSingleInstance>,
}

#[cfg(windows)]
#[derive(Debug)]
struct WinSingleInstance {
    mutex_raw: isize,
    pipe_name: String,
    stop: Arc<AtomicBool>,
    finished: Arc<AtomicBool>,
    listener: Option<JoinHandle<()>>,
}

#[cfg(windows)]
impl Drop for SingleInstanceGuard {
    fn drop(&mut self) {
        if let Some(mut inst) = self.inner.take() {
            inst.stop.store(true, Ordering::Release);
            // Retry waking the pipe briefly while the server exits cleanly without blocking forever
            // if the server is cycling between accepting and binding.
            for _ in 0..10 {
                if inst.finished.load(Ordering::Acquire) {
                    break;
                }
                let _ = std::fs::OpenOptions::new()
                    .read(true)
                    .write(true)
                    .open(&inst.pipe_name);
                if inst.finished.load(Ordering::Acquire) {
                    break;
                }
                thread::sleep(std::time::Duration::from_millis(5));
            }
            if let Some(handle) = inst.listener.take() {
                let _ = handle.join();
            }
            #[expect(
                unsafe_code,
                reason = "closes OS mutex handle acquired at single-instance startup"
            )]
            // SAFETY: `inst.mutex_raw` is a valid non-null Win32 HANDLE owned exclusively by this guard.
            unsafe {
                let _ = windows::Win32::Foundation::CloseHandle(
                    windows::Win32::Foundation::HANDLE(inst.mutex_raw as *mut core::ffi::c_void),
                );
            }
        }
    }
}

/// Acquires the single-instance mutex for `app_key` or forwards `startup_paths` to the already
/// running instance via Windows Named Pipe (`\\.\pipe\<app_key>_Pipe`).
pub fn acquire_or_forward(
    app_key: &str,
    startup_paths: &[PathBuf],
    cmd_tx: Sender<ShellCommand>,
) -> Result<SingleInstanceOutcome, WinError> {
    #[cfg(windows)]
    {
        acquire_or_forward_win(app_key, startup_paths, cmd_tx)
    }
    #[cfg(not(windows))]
    {
        let _ = (app_key, startup_paths, cmd_tx);
        Ok(SingleInstanceOutcome::Primary(SingleInstanceGuard {}))
    }
}

#[cfg(windows)]
fn to_wide_null(text: &str) -> Vec<u16> {
    text.encode_utf16().chain(std::iter::once(0)).collect()
}

#[cfg(windows)]
fn acquire_or_forward_win(
    app_key: &str,
    startup_paths: &[PathBuf],
    cmd_tx: Sender<ShellCommand>,
) -> Result<SingleInstanceOutcome, WinError> {
    use std::io::Write;
    use windows::Win32::Foundation::{CloseHandle, ERROR_ALREADY_EXISTS, GetLastError};
    use windows::Win32::System::Threading::CreateMutexW;
    use windows::core::PCWSTR;

    let mutex_name = format!("Local\\{app_key}_Mutex");
    let mutex_wide = to_wide_null(&mutex_name);
    let pipe_name = format!(r"\\.\pipe\{app_key}_Pipe");

    #[expect(
        unsafe_code,
        reason = "creates named Win32 mutex for single-instance locking"
    )]
    // SAFETY: `mutex_wide` is a null-terminated UTF-16 buffer valid for the synchronous `CreateMutexW` call.
    let (mutex_handle, already_exists) = unsafe {
        let handle = CreateMutexW(None, false, PCWSTR::from_raw(mutex_wide.as_ptr()))
            .map_err(|e| WinError::Platform(format!("CreateMutexW failed: {e}")))?;
        let exists = GetLastError() == ERROR_ALREADY_EXISTS;
        (handle, exists)
    };

    if already_exists {
        #[expect(
            unsafe_code,
            reason = "closes duplicate mutex handle before forwarding IPC"
        )]
        // SAFETY: `mutex_handle` was successfully returned by `CreateMutexW`.
        unsafe {
            let _ = CloseHandle(mutex_handle);
        }

        let mut payload = String::from("ACTIVATE\n");
        for path in startup_paths {
            payload.push_str("OPEN:");
            payload.push_str(&path.to_string_lossy());
            payload.push('\n');
        }

        // Retry briefly if primary instance is cycling its named pipe between connections.
        for attempt in 0..5 {
            match std::fs::OpenOptions::new()
                .read(true)
                .write(true)
                .open(&pipe_name)
            {
                Ok(mut pipe_file) => {
                    pipe_file.write_all(payload.as_bytes())?;
                    pipe_file.flush()?;
                    return Ok(SingleInstanceOutcome::ForwardedToExisting);
                }
                Err(_) if attempt < 4 => {
                    thread::sleep(std::time::Duration::from_millis(40));
                }
                Err(err) => {
                    tracing::warn!(error = %err, "secondary instance could not connect to primary pipe");
                    return Ok(SingleInstanceOutcome::ForwardedToExisting);
                }
            }
        }
        return Ok(SingleInstanceOutcome::ForwardedToExisting);
    }

    let stop = Arc::new(AtomicBool::new(false));
    let finished = Arc::new(AtomicBool::new(false));
    let stop_bg = Arc::clone(&stop);
    let finished_bg = Arc::clone(&finished);
    let pipe_bg = pipe_name.clone();

    let listener = thread::Builder::new()
        .name("lunaria-win-ipc".to_owned())
        .spawn(move || run_pipe_server(&pipe_bg, &stop_bg, &finished_bg, &cmd_tx))
        .map_err(|e| WinError::Platform(format!("failed spawning IPC thread: {e}")))?;

    Ok(SingleInstanceOutcome::Primary(SingleInstanceGuard {
        inner: Some(WinSingleInstance {
            mutex_raw: mutex_handle.0 as isize,
            pipe_name,
            stop,
            finished,
            listener: Some(listener),
        }),
    }))
}

#[cfg(windows)]
fn run_pipe_server(
    pipe_name: &str,
    stop: &AtomicBool,
    finished: &AtomicBool,
    cmd_tx: &Sender<ShellCommand>,
) {
    use windows::Win32::Foundation::{
        CloseHandle, ERROR_PIPE_CONNECTED, GetLastError, INVALID_HANDLE_VALUE,
    };
    use windows::Win32::Storage::FileSystem::{PIPE_ACCESS_DUPLEX, ReadFile};
    use windows::Win32::System::Pipes::{
        ConnectNamedPipe, CreateNamedPipeW, DisconnectNamedPipe, PIPE_READMODE_BYTE,
        PIPE_TYPE_BYTE, PIPE_UNLIMITED_INSTANCES, PIPE_WAIT,
    };
    use windows::core::PCWSTR;

    let pipe_wide = to_wide_null(pipe_name);

    while !stop.load(Ordering::Acquire) {
        #[expect(
            unsafe_code,
            reason = "creates inbound Win32 named pipe for secondary instance IPC"
        )]
        // SAFETY: `pipe_wide` is a null-terminated UTF-16 pipe path alive for this call.
        let pipe = unsafe {
            CreateNamedPipeW(
                PCWSTR::from_raw(pipe_wide.as_ptr()),
                PIPE_ACCESS_DUPLEX,
                PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT,
                PIPE_UNLIMITED_INSTANCES,
                4_096,
                16_384,
                0,
                None,
            )
        };

        if pipe == INVALID_HANDLE_VALUE {
            break;
        }

        if stop.load(Ordering::Acquire) {
            #[expect(unsafe_code, reason = "closes unused pipe handle on shutdown")]
            // SAFETY: `pipe` is a valid non-invalid handle owned by this thread.
            unsafe {
                let _ = CloseHandle(pipe);
            }
            break;
        }

        #[expect(
            unsafe_code,
            reason = "waits for next client connection on owned named pipe handle"
        )]
        // SAFETY: `pipe` is a valid server-side named pipe handle with no overlapped struct.
        let connected = unsafe {
            let res = ConnectNamedPipe(pipe, None);
            res.is_ok() || GetLastError() == ERROR_PIPE_CONNECTED
        };

        if connected && !stop.load(Ordering::Acquire) {
            let mut accumulated = Vec::new();
            let mut chunk = [0u8; 2_048];
            loop {
                let mut bytes_read = 0u32;
                #[expect(
                    unsafe_code,
                    reason = "reads UTF-8 command lines from connected pipe client"
                )]
                // SAFETY: `chunk` is a valid stack buffer and `pipe` is connected.
                let ok = unsafe {
                    ReadFile(pipe, Some(&mut chunk), Some(&mut bytes_read), None).is_ok()
                };
                if !ok || bytes_read == 0 {
                    break;
                }
                accumulated.extend_from_slice(&chunk[..bytes_read as usize]);
                if accumulated.len() >= 65_536 {
                    break;
                }
            }

            if !accumulated.is_empty() {
                let text = String::from_utf8_lossy(&accumulated);
                let mut opened_paths = Vec::new();
                let mut activate = false;
                for raw_line in text.lines() {
                    let line = raw_line.trim();
                    if line == "ACTIVATE" {
                        activate = true;
                    } else if let Some(rest) = line.strip_prefix("OPEN:") {
                        let clean = rest.trim();
                        if !clean.is_empty() {
                            opened_paths.push(PathBuf::from(clean));
                        }
                    }
                }
                if !opened_paths.is_empty() {
                    let _ = cmd_tx.send(ShellCommand::OpenPaths(opened_paths));
                } else if activate {
                    let _ = cmd_tx.send(ShellCommand::ShowWindow);
                }
            }
        }

        #[expect(
            unsafe_code,
            reason = "disconnects and closes completed named pipe handle"
        )]
        // SAFETY: `pipe` is a valid non-invalid `CreateNamedPipeW` handle.
        unsafe {
            let _ = DisconnectNamedPipe(pipe);
            let _ = CloseHandle(pipe);
        }
    }
    finished.store(true, Ordering::Release);
}
