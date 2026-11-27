//! Zero-network local Discord Rich Presence client over Windows Named Pipes (`\\.\pipe\discord-ipc-0..9`).

use std::fmt;
use std::io::{self, Read, Write};
use std::sync::Arc;
use std::sync::atomic::{AtomicU8, Ordering};
use std::thread::{self, JoinHandle};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use crossbeam_channel::{Receiver, RecvTimeoutError, Sender, unbounded};
use serde_json::{Value, json};

const DISCORD_OP_HANDSHAKE: u32 = 0;
const DISCORD_OP_FRAME: u32 = 1;
const DISCORD_OP_CLOSE: u32 = 2;
const DISCORD_OP_PING: u32 = 3;
const DISCORD_OP_PONG: u32 = 4;

const MAX_IPC_FRAME_BYTES: usize = 65_536;
const RETRY_INTERVAL: Duration = Duration::from_secs(5);

/// Built-in public Lunaria Application ID registered on Discord Developer Portal.
pub const DEFAULT_DISCORD_CLIENT_ID: &str = "1575841247564791828";

/// Playback telemetry snapshot forwarded to Discord Rich Presence.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct DiscordActivitySnapshot {
    /// Whether any track is loaded.
    pub has_track: bool,
    /// Whether playback is actively running.
    pub is_playing: bool,
    /// Current track title.
    pub title: String,
    /// Current track artist.
    pub artist: String,
    /// Current track album.
    pub album: String,
    /// Technical audio summary (e.g. `FLAC • 24-bit / 96 kHz`).
    pub quality_badge: String,
    /// Active WASAPI output mode label (`DSP 32-bit Float` / `Purist Bit-Perfect`).
    pub output_mode_label: String,
    /// Current playback position in milliseconds.
    pub position_ms: u64,
    /// Total duration in milliseconds.
    pub duration_ms: u64,
}

/// Live local IPC status for Discord Rich Presence shown in Settings.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum DiscordRpcStatus {
    /// Integration disabled by user preference.
    Disabled = 0,
    /// Enabled, but no numeric Discord Application Client ID is configured.
    MissingClientId = 1,
    /// Enabled and waiting for Discord Desktop IPC pipe (`discord-ipc-0..9`).
    WaitingForDiscord = 2,
    /// Connected to local Discord Desktop IPC pipe.
    Connected = 3,
}

impl DiscordRpcStatus {
    /// Human-readable status string for Settings UI.
    #[must_use]
    pub const fn label(self) -> &'static str {
        match self {
            Self::Disabled => "Discord RPC: Off",
            Self::MissingClientId => "Discord: Set Client ID",
            Self::WaitingForDiscord => "Discord: Waiting for app",
            Self::Connected => "Discord: Connected",
        }
    }

    const fn from_u8(raw: u8) -> Self {
        match raw {
            1 => Self::MissingClientId,
            2 => Self::WaitingForDiscord,
            3 => Self::Connected,
            _ => Self::Disabled,
        }
    }
}

enum RpcCommand {
    Configure { enabled: bool, client_id: String },
    UpdateActivity(DiscordActivitySnapshot),
    Shutdown,
}

/// Non-blocking background host maintaining an optional local Discord IPC connection.
pub struct DiscordRpcHost {
    tx: Sender<RpcCommand>,
    status: Arc<AtomicU8>,
    worker: Option<JoinHandle<()>>,
}

impl fmt::Debug for DiscordRpcHost {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("DiscordRpcHost")
            .field("status", &self.status())
            .finish_non_exhaustive()
    }
}

impl DiscordRpcHost {
    /// Spawns the background Discord IPC worker in idle/disabled state (`0%` CPU).
    #[must_use]
    pub fn new() -> Self {
        let (tx, rx) = unbounded::<RpcCommand>();
        let status = Arc::new(AtomicU8::new(DiscordRpcStatus::Disabled as u8));
        let status_bg = Arc::clone(&status);

        let worker = thread::Builder::new()
            .name("lunaria-discord-rpc".to_owned())
            .spawn(move || run_rpc_worker(&rx, &status_bg))
            .ok();

        Self { tx, status, worker }
    }

    /// Updates enablement flag and Discord Application Client ID.
    pub fn configure(&self, enabled: bool, client_id: String) {
        let _ = self.tx.send(RpcCommand::Configure { enabled, client_id });
    }

    /// Publishes latest playback state to Discord (or clears presence when paused/stopped).
    pub fn update_activity(&self, snapshot: DiscordActivitySnapshot) {
        let _ = self.tx.send(RpcCommand::UpdateActivity(snapshot));
    }

    /// Reads the current lock-free connection status.
    #[must_use]
    pub fn status(&self) -> DiscordRpcStatus {
        DiscordRpcStatus::from_u8(self.status.load(Ordering::Acquire))
    }
}

impl Default for DiscordRpcHost {
    fn default() -> Self {
        Self::new()
    }
}

impl Drop for DiscordRpcHost {
    fn drop(&mut self) {
        let _ = self.tx.send(RpcCommand::Shutdown);
        if let Some(handle) = self.worker.take() {
            let _ = handle.join();
        }
    }
}

/// Resolves a valid numeric Discord Application Client ID from explicit config, env override,
/// or Lunaria's built-in Application ID (`DEFAULT_DISCORD_CLIENT_ID`).
#[must_use]
pub fn resolve_discord_client_id(configured: &str, env_fallback: Option<&str>) -> Option<String> {
    let pick = |raw: &str| -> Option<String> {
        let trimmed = raw.trim();
        if !trimmed.is_empty() && trimmed.bytes().all(|b| b.is_ascii_digit()) {
            Some(trimmed.to_owned())
        } else {
            None
        }
    };
    pick(configured)
        .or_else(|| env_fallback.and_then(pick))
        .or_else(|| pick(DEFAULT_DISCORD_CLIENT_ID))
}

/// Clamps a Discord presence text field into Discord's required `2..=max_chars` range.
#[must_use]
pub fn clamp_discord_str(input: &str, fallback: &str, max_chars: usize) -> String {
    let trimmed = input.trim();
    let source = if trimmed.is_empty() {
        fallback
    } else {
        trimmed
    };
    let mut out = if source.chars().count() > max_chars {
        source.chars().take(max_chars).collect::<String>()
    } else {
        source.to_owned()
    };
    if out.chars().count() < 2 {
        out.push(' ');
        if out.chars().count() < 2 {
            out.push(' ');
        }
    }
    out
}

/// Builds the Discord IPC v1 handshake payload.
#[must_use]
pub fn build_handshake_payload(client_id: &str) -> Value {
    json!({
        "v": 1,
        "client_id": client_id,
    })
}

/// Builds a `SET_ACTIVITY` RPC command payload (Listening `type: 2` when playing, `null` otherwise).
#[must_use]
pub fn build_set_activity_payload(
    pid: u32,
    nonce: &str,
    snap: &DiscordActivitySnapshot,
    now_epoch_secs: u64,
) -> Value {
    if !snap.has_track || !snap.is_playing {
        return json!({
            "cmd": "SET_ACTIVITY",
            "args": {
                "pid": pid,
                "activity": Value::Null,
            },
            "nonce": nonce,
        });
    }

    let details = clamp_discord_str(&snap.title, "Playing Local Audio", 128);
    let state = clamp_discord_str(&snap.artist, "Lunaria", 128);

    let pos_secs = snap.position_ms / 1_000;
    let dur_secs = snap.duration_ms / 1_000;
    let start_epoch = now_epoch_secs.saturating_sub(pos_secs);
    let timestamps = if dur_secs > 0 {
        let end_epoch = start_epoch.saturating_add(dur_secs);
        json!({
            "start": start_epoch,
            "end": end_epoch,
        })
    } else {
        json!({
            "start": start_epoch,
        })
    };

    let large_text = clamp_discord_str(&snap.quality_badge, "Lunaria Audio", 128);
    let small_text = clamp_discord_str(&snap.output_mode_label, "Lunaria Desktop", 128);

    json!({
        "cmd": "SET_ACTIVITY",
        "args": {
            "pid": pid,
            "activity": {
                "type": 2,
                "details": details,
                "state": state,
                "timestamps": timestamps,
                "assets": {
                    "large_image": "lunaria_logo",
                    "large_text": large_text,
                    "small_image": "play",
                    "small_text": small_text,
                }
            }
        },
        "nonce": nonce,
    })
}

/// Encodes a Discord IPC frame (`[opcode: u32 LE][length: u32 LE][UTF-8 JSON]`).
pub fn encode_ipc_frame(opcode: u32, payload: &Value) -> Result<Vec<u8>, serde_json::Error> {
    let body = serde_json::to_vec(payload)?;
    let len_u32 = u32::try_from(body.len()).unwrap_or(u32::MAX);
    let mut frame = Vec::with_capacity(8 + body.len());
    frame.extend_from_slice(&opcode.to_le_bytes());
    frame.extend_from_slice(&len_u32.to_le_bytes());
    frame.extend_from_slice(&body);
    Ok(frame)
}

/// Decodes one complete Discord IPC frame from a byte slice, returning `(opcode, json, consumed_bytes)`.
#[must_use]
pub fn decode_ipc_frame(bytes: &[u8]) -> Option<(u32, Value, usize)> {
    if bytes.len() < 8 {
        return None;
    }
    let opcode = u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]);
    let len = u32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]) as usize;
    if len > MAX_IPC_FRAME_BYTES || bytes.len() < 8 + len {
        return None;
    }
    let payload: Value = serde_json::from_slice(&bytes[8..8 + len]).ok()?;
    Some((opcode, payload, 8 + len))
}

fn read_ipc_frame<R: Read>(reader: &mut R) -> io::Result<(u32, Value)> {
    let mut header = [0_u8; 8];
    reader.read_exact(&mut header)?;
    let opcode = u32::from_le_bytes([header[0], header[1], header[2], header[3]]);
    let len = u32::from_le_bytes([header[4], header[5], header[6], header[7]]) as usize;
    if len > MAX_IPC_FRAME_BYTES {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "Discord IPC frame exceeded size limit",
        ));
    }
    let mut body = vec![0_u8; len];
    reader.read_exact(&mut body)?;
    let val: Value = serde_json::from_slice(&body)
        .map_err(|err| io::Error::new(io::ErrorKind::InvalidData, err))?;
    Ok((opcode, val))
}

fn send_ipc_frame<RW: Read + Write>(
    stream: &mut RW,
    opcode: u32,
    payload: &Value,
) -> io::Result<()> {
    let frame = encode_ipc_frame(opcode, payload)
        .map_err(|err| io::Error::new(io::ErrorKind::InvalidData, err))?;
    stream.write_all(&frame)?;
    stream.flush()?;

    // Drain synchronous response / ping frames produced by Discord Desktop's IPC server.
    for _ in 0..4 {
        let (reply_op, _) = read_ipc_frame(stream)?;
        match reply_op {
            DISCORD_OP_FRAME => return Ok(()),
            DISCORD_OP_PING => {
                let pong = encode_ipc_frame(DISCORD_OP_PONG, &json!({}))
                    .map_err(|err| io::Error::new(io::ErrorKind::InvalidData, err))?;
                stream.write_all(&pong)?;
                stream.flush()?;
            }
            DISCORD_OP_CLOSE => {
                return Err(io::Error::new(
                    io::ErrorKind::BrokenPipe,
                    "Discord IPC closed connection",
                ));
            }
            _ => return Ok(()),
        }
    }
    Ok(())
}

fn now_epoch_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_secs())
}

#[cfg(windows)]
fn connect_discord_pipe(client_id: &str) -> io::Result<std::fs::File> {
    use std::fs::OpenOptions;

    let mut last_err = io::Error::new(io::ErrorKind::NotFound, "Discord IPC pipe not available");
    for slot in 0..=9 {
        let pipe_path = format!(r"\\.\pipe\discord-ipc-{slot}");
        match OpenOptions::new().read(true).write(true).open(&pipe_path) {
            Ok(mut pipe) => {
                if let Err(err) = send_ipc_frame(
                    &mut pipe,
                    DISCORD_OP_HANDSHAKE,
                    &build_handshake_payload(client_id),
                ) {
                    last_err = err;
                    continue;
                }
                return Ok(pipe);
            }
            Err(err) => {
                last_err = err;
            }
        }
    }
    Err(last_err)
}

#[cfg(not(windows))]
fn connect_discord_pipe(_client_id: &str) -> io::Result<std::fs::File> {
    Err(io::Error::new(
        io::ErrorKind::Unsupported,
        "Discord IPC named pipes are Windows-only",
    ))
}

fn clear_activity_before_close(
    pipe_opt: &mut Option<std::fs::File>,
    nonce_seq: &mut u64,
    pid: u32,
) {
    if let Some(mut pipe) = pipe_opt.take() {
        *nonce_seq = nonce_seq.saturating_add(1);
        let clear = build_set_activity_payload(
            pid,
            &format!("lunaria-clear-{nonce_seq}"),
            &DiscordActivitySnapshot::default(),
            0,
        );
        let _ = send_ipc_frame(&mut pipe, DISCORD_OP_FRAME, &clear);
    }
}

fn run_rpc_worker(rx: &Receiver<RpcCommand>, status_out: &AtomicU8) {
    let mut enabled = false;
    let mut configured_id = String::new();
    let mut latest_snapshot = DiscordActivitySnapshot::default();
    let mut dirty = false;
    let mut pipe: Option<std::fs::File> = None;
    let mut nonce_seq: u64 = 0;
    let pid = std::process::id();

    loop {
        let env_id = std::env::var("LUNARIA_DISCORD_CLIENT_ID").ok();
        let active_client_id = if enabled {
            resolve_discord_client_id(&configured_id, env_id.as_deref())
        } else {
            None
        };

        if !enabled {
            clear_activity_before_close(&mut pipe, &mut nonce_seq, pid);
            status_out.store(DiscordRpcStatus::Disabled as u8, Ordering::Release);
            dirty = false;
        } else if active_client_id.is_none() {
            clear_activity_before_close(&mut pipe, &mut nonce_seq, pid);
            status_out.store(DiscordRpcStatus::MissingClientId as u8, Ordering::Release);
            dirty = false;
        } else if let Some(client_id) = active_client_id.as_deref() {
            if pipe.is_none() {
                match connect_discord_pipe(client_id) {
                    Ok(opened) => {
                        pipe = Some(opened);
                        status_out.store(DiscordRpcStatus::Connected as u8, Ordering::Release);
                        dirty = true;
                        tracing::info!("connected to local Discord Desktop IPC pipe");
                    }
                    Err(_) => {
                        status_out
                            .store(DiscordRpcStatus::WaitingForDiscord as u8, Ordering::Release);
                    }
                }
            }

            if dirty && let Some(stream) = pipe.as_mut() {
                nonce_seq = nonce_seq.saturating_add(1);
                let payload = build_set_activity_payload(
                    pid,
                    &format!("lunaria-{nonce_seq}"),
                    &latest_snapshot,
                    now_epoch_secs(),
                );
                if let Err(err) = send_ipc_frame(stream, DISCORD_OP_FRAME, &payload) {
                    tracing::debug!(error = %err, "Discord IPC write failed; reconnecting");
                    pipe = None;
                    status_out.store(DiscordRpcStatus::WaitingForDiscord as u8, Ordering::Release);
                } else {
                    dirty = false;
                }
            }
        }

        let next_cmd = if enabled && active_client_id.is_some() && pipe.is_none() {
            match rx.recv_timeout(RETRY_INTERVAL) {
                Ok(cmd) => Some(cmd),
                Err(RecvTimeoutError::Timeout) => None,
                Err(RecvTimeoutError::Disconnected) => break,
            }
        } else {
            match rx.recv() {
                Ok(cmd) => Some(cmd),
                Err(_) => break,
            }
        };

        if let Some(mut cmd) = next_cmd {
            let mut should_shutdown = false;
            loop {
                match cmd {
                    RpcCommand::Configure {
                        enabled: next_en,
                        client_id: next_id,
                    } => {
                        let id_changed = next_id.trim() != configured_id.trim();
                        if !next_en || id_changed {
                            clear_activity_before_close(&mut pipe, &mut nonce_seq, pid);
                        }
                        enabled = next_en;
                        configured_id = next_id;
                        dirty = true;
                    }
                    RpcCommand::UpdateActivity(snap) => {
                        latest_snapshot = snap;
                        dirty = true;
                    }
                    RpcCommand::Shutdown => {
                        should_shutdown = true;
                        break;
                    }
                }
                match rx.try_recv() {
                    Ok(queued) => cmd = queued,
                    Err(_) => break,
                }
            }
            if should_shutdown {
                break;
            }
        }
    }

    clear_activity_before_close(&mut pipe, &mut nonce_seq, pid);
    status_out.store(DiscordRpcStatus::Disabled as u8, Ordering::Release);
}
