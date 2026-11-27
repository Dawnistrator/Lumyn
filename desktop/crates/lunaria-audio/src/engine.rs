//! Real-time two-thread WASAPI playback engine with gapless pre-open, crossfade, hot-plug
//! protection, and deterministic Windows File-Lock Handover for tag editing.

use std::collections::VecDeque;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::thread::JoinHandle;
use std::time::Duration;

use arc_swap::ArcSwap;
use crossbeam_channel::{Receiver, Sender, select};
use lunaria_dsp::{BiquadDspEngine, DspConfigStore, fade_in_volume, fade_out_volume};
use lunaria_model::{
    AudioDeviceInfo, AudioOutputMode, AudioSignalPath, AudioSpec, TrackId, clamp_playback_speed,
};
use rtrb::{Consumer, Producer, RingBuffer};

use crate::{
    AudioError, AudioResampler, AudioTrackDecoder, DecodedTrackInfo, DeviceHotPlugEvent,
    HotPlugWatcher, SignalPathInput, WsolaTimeStretcher, build_preview_signal_path_with_dsp,
    build_signal_path, enumerate_render_devices, pack_frames_to_pcm_bytes,
};

#[cfg(windows)]
use crate::{ActiveWasapiStream, ComMtaGuard, is_device_invalidated_error, open_wasapi_stream};

const RING_CAPACITY_FRAMES: usize = 16_384; // ~170 ms at 96 kHz, ~370 ms at 44.1 kHz
const MARKER_CAPACITY: usize = 512;

/// Playable file or `.cue` virtual clip request passed to [`PlaybackEngine`].
#[derive(Debug, Clone, PartialEq)]
pub struct PlaybackRequest {
    /// Local filesystem path to the audio file.
    pub file_path: PathBuf,
    /// Optional database track identifier (negative for CUE virtual tracks).
    pub track_id: Option<TrackId>,
    /// Display title.
    pub title: String,
    /// Performing artist.
    pub artist: String,
    /// Album title.
    pub album: String,
    /// Optional CUE clip start offset in milliseconds.
    pub clip_start_ms: Option<u64>,
    /// Optional CUE clip end offset in milliseconds.
    pub clip_end_ms: Option<u64>,
    /// Optional `REPLAYGAIN_TRACK_GAIN` in dB.
    pub replay_gain_track_db: Option<f32>,
    /// Optional `REPLAYGAIN_ALBUM_GAIN` in dB.
    pub replay_gain_album_db: Option<f32>,
    /// Optional `REPLAYGAIN_TRACK_PEAK` linear amplitude.
    pub replay_gain_track_peak: Option<f32>,
    /// Optional `REPLAYGAIN_ALBUM_PEAK` linear amplitude.
    pub replay_gain_album_peak: Option<f32>,
}

impl PlaybackRequest {
    /// Constructs a standalone file playback request with inferred display metadata.
    #[must_use]
    pub fn from_path(path: impl Into<PathBuf>) -> Self {
        let file_path = path.into();
        let title = file_path
            .file_stem()
            .and_then(|s| s.to_str())
            .filter(|s| !s.is_empty())
            .unwrap_or("Local Audio Track")
            .to_owned();
        Self {
            file_path,
            track_id: None,
            title,
            artist: "Local Audio File".to_owned(),
            album: String::new(),
            clip_start_ms: None,
            clip_end_ms: None,
            replay_gain_track_db: None,
            replay_gain_album_db: None,
            replay_gain_track_peak: None,
            replay_gain_album_peak: None,
        }
    }
}

/// Current lifecycle state of the playback engine.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PlaybackStatus {
    /// No track loaded.
    Idle,
    /// Actively decoding and rendering audio frames.
    Playing,
    /// Loaded and paused; position preserved.
    Paused,
    /// Stopped and reset to position zero.
    Stopped,
    /// Current track reached natural end of stream.
    Ended,
    /// Recoverable error halted playback.
    Error,
}

/// Snapshot published lock-free by the engine for adaptive UI frame pacing (`~30 fps` playing, `0 Hz` idle).
#[derive(Debug, Clone)]
pub struct PlaybackTelemetry {
    /// Current engine transport state.
    pub status: PlaybackStatus,
    /// Active track request if loaded.
    pub active_track: Option<PlaybackRequest>,
    /// Decoded technical audio parameters if loaded.
    pub source_info: Option<DecodedTrackInfo>,
    /// Clipped track duration in milliseconds.
    pub duration_ms: u64,
    /// Current playback cursor in milliseconds.
    pub position_ms: u64,
    /// Software master volume (`0.0..=1.0`).
    pub volume: f32,
    /// Pitch-preserving playback speed (`0.5..=2.0`).
    pub speed: f32,
    /// Requested processing/output mode.
    pub output_mode: AudioOutputMode,
    /// Resolved output endpoint.
    pub selected_device: Option<AudioDeviceInfo>,
    /// Honest real-time Audio Signal Path report.
    pub signal_path: AudioSignalPath,
    /// Count of ring-buffer underrun events detected by render thread.
    pub xrun_count: u64,
    /// Non-fatal playback error description, if any.
    pub error_message: Option<String>,
}

/// Token returned by [`PlaybackEngine::suspend_file_handle`] so tag editing can safely modify
/// and then seamlessly resume playback from the exact timestamp.
#[derive(Debug, Clone)]
pub struct FileHandleSuspension {
    /// Track request that was active when suspended, if any.
    pub request: Option<PlaybackRequest>,
    /// Exact position to seek back to upon resume.
    pub position_ms: u64,
    /// Whether audio was actively playing before suspension.
    pub was_playing: bool,
}

#[derive(Debug)]
enum EngineCommand {
    LoadAndPlay(Box<PlaybackRequest>),
    QueueNext(Option<Box<PlaybackRequest>>),
    Play,
    Pause,
    Stop,
    SeekMs(u64),
    SetVolume(f32),
    SetSpeed(f32),
    SetOutputMode(AudioOutputMode),
    SetDevice(Option<String>),
    RefreshSignalPath,
    SuspendFileHandle {
        target_path: Option<PathBuf>,
        ack_tx: Sender<FileHandleSuspension>,
    },
    ResumeFromSuspension(Box<FileHandleSuspension>),
    Shutdown,
}

#[derive(Debug)]
enum RenderCommand {
    Configure {
        device_id: Option<String>,
        mode: AudioOutputMode,
        source_spec: AudioSpec,
        src_channels: usize,
        dsp_bypassed: bool,
        ack_tx: Sender<Result<OpenedStreamMeta, AudioError>>,
    },
    SetPlaying(bool),
    PauseAndFlush {
        ack_tx: Sender<()>,
    },
    SwapRings {
        sample_rx: Consumer<f64>,
        marker_rx: Consumer<SegmentMarker>,
        src_channels: usize,
        dsp_bypassed: bool,
        ack_tx: Sender<()>,
    },
    SetDspBypassed(bool),
    SetVolume(f32),
    Shutdown,
}

#[derive(Debug, Clone)]
struct OpenedStreamMeta {
    sample_rate_hz: u32,
    bit_depth: u16,
    encoding_label: String,
    is_exclusive: bool,
    fallback_reason: Option<String>,
    device: AudioDeviceInfo,
}

#[derive(Debug, Clone)]
enum RenderNotice {
    TrackBecameAudible(Box<AudioTrackSwitchedMeta>),
    PlaybackEnded,
    DeviceInvalidated(String),
}

#[derive(Debug, Clone)]
struct AudioTrackSwitchedMeta {
    request: PlaybackRequest,
}

#[derive(Debug, Clone)]
struct SegmentMarker {
    frames: usize,
    start_ms: u64,
    end_ms: u64,
    is_eos: bool,
    switch_meta: Option<Box<AudioTrackSwitchedMeta>>,
}

struct SharedState {
    dsp_store: Arc<DspConfigStore>,
    telemetry: ArcSwap<PlaybackTelemetry>,
    position_ms: AtomicU64,
    is_playing: AtomicBool,
    volume_bits: AtomicU32,
    xrun_count: AtomicU64,
}

/// Real-time Windows WASAPI audio playback controller (`#![forbid(unsafe_code)]`).
pub struct PlaybackEngine {
    cmd_tx: Sender<EngineCommand>,
    shared: Arc<SharedState>,
    coordinator: Option<JoinHandle<()>>,
}

impl std::fmt::Debug for PlaybackEngine {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let snap = self.shared.telemetry.load();
        f.debug_struct("PlaybackEngine")
            .field("status", &snap.status)
            .field("position_ms", &self.position_ms())
            .field("output_mode", &snap.output_mode)
            .finish_non_exhaustive()
    }
}

impl PlaybackEngine {
    /// Spawns the decode coordinator and COM-isolated WASAPI render thread.
    pub fn new(dsp_store: Arc<DspConfigStore>) -> Result<Self, AudioError> {
        let initial_devices = enumerate_render_devices().unwrap_or_default();
        let initial_device = initial_devices
            .iter()
            .find(|d| d.is_default)
            .or_else(|| initial_devices.first())
            .cloned();

        let initial_dsp = dsp_store.snapshot();
        let initial_signal = build_preview_signal_path_with_dsp(
            initial_device.as_ref(),
            AudioOutputMode::DspFloat32,
            &initial_dsp,
        );

        let initial_telemetry = PlaybackTelemetry {
            status: PlaybackStatus::Idle,
            active_track: None,
            source_info: None,
            duration_ms: 0,
            position_ms: 0,
            volume: 1.0,
            speed: 1.0,
            output_mode: AudioOutputMode::DspFloat32,
            selected_device: initial_device,
            signal_path: initial_signal,
            xrun_count: 0,
            error_message: None,
        };

        let shared = Arc::new(SharedState {
            dsp_store,
            telemetry: ArcSwap::from_pointee(initial_telemetry),
            position_ms: AtomicU64::new(0),
            is_playing: AtomicBool::new(false),
            volume_bits: AtomicU32::new(1.0_f32.to_bits()),
            xrun_count: AtomicU64::new(0),
        });

        let (cmd_tx, cmd_rx) = crossbeam_channel::bounded::<EngineCommand>(64);
        let shared_worker = Arc::clone(&shared);

        let coordinator = std::thread::Builder::new()
            .name("lunaria-audio-decode".to_owned())
            .spawn(move || run_coordinator_loop(&shared_worker, &cmd_rx))
            .map_err(|err| AudioError::Wasapi(format!("failed to spawn decode thread: {err}")))?;

        Ok(Self {
            cmd_tx,
            shared,
            coordinator: Some(coordinator),
        })
    }

    /// Returns reference to the lock-free [`DspConfigStore`] shared with the WASAPI render thread.
    #[must_use]
    pub fn dsp_store(&self) -> &Arc<DspConfigStore> {
        &self.shared.dsp_store
    }

    /// Atomically reads the latest playback and Signal Path telemetry without blocking.
    #[must_use]
    pub fn telemetry(&self) -> Arc<PlaybackTelemetry> {
        let mut snap = self.shared.telemetry.load_full();
        let live_pos = self.shared.position_ms.load(Ordering::Acquire);
        let live_playing = self.shared.is_playing.load(Ordering::Acquire);
        let live_xruns = self.shared.xrun_count.load(Ordering::Acquire);
        if snap.position_ms != live_pos
            || (live_playing && snap.status == PlaybackStatus::Paused)
            || snap.xrun_count != live_xruns
        {
            let cloned = Arc::make_mut(&mut snap);
            cloned.position_ms = if cloned.duration_ms > 0 {
                live_pos.min(cloned.duration_ms)
            } else {
                live_pos
            };
            cloned.xrun_count = live_xruns;
        }
        snap
    }

    /// Atomically reads the live playback position in milliseconds.
    #[must_use]
    pub fn position_ms(&self) -> u64 {
        self.shared.position_ms.load(Ordering::Acquire)
    }

    /// Returns `true` when the engine is actively rendering audio.
    #[must_use]
    pub fn is_playing(&self) -> bool {
        self.shared.is_playing.load(Ordering::Acquire)
    }

    /// Opens `request` and immediately begins playback.
    pub fn load_and_play(&self, request: PlaybackRequest) -> Result<(), AudioError> {
        self.send_command(EngineCommand::LoadAndPlay(Box::new(request)))
    }

    /// Pre-registers the next track in the queue for gapless transition or crossfade.
    pub fn queue_next(&self, request: Option<PlaybackRequest>) -> Result<(), AudioError> {
        self.send_command(EngineCommand::QueueNext(request.map(Box::new)))
    }

    /// Starts or resumes playback of the currently loaded track.
    pub fn play(&self) -> Result<(), AudioError> {
        self.send_command(EngineCommand::Play)
    }

    /// Pauses playback cleanly while keeping position intact.
    pub fn pause(&self) -> Result<(), AudioError> {
        self.send_command(EngineCommand::Pause)
    }

    /// Stops playback and resets playhead to zero.
    pub fn stop(&self) -> Result<(), AudioError> {
        self.send_command(EngineCommand::Stop)
    }

    /// Seeks cleanly to `position_ms` relative to the active track or `.cue` region.
    pub fn seek_ms(&self, position_ms: u64) -> Result<(), AudioError> {
        self.send_command(EngineCommand::SeekMs(position_ms))
    }

    /// Sets software master volume in `0.0..=1.0`.
    pub fn set_volume(&self, volume: f32) -> Result<(), AudioError> {
        let clamped = if volume.is_finite() {
            volume.clamp(0.0, 1.0)
        } else {
            1.0
        };
        self.shared
            .volume_bits
            .store(clamped.to_bits(), Ordering::Release);
        self.send_command(EngineCommand::SetVolume(clamped))
    }

    /// Sets pitch-preserving playback speed multiplier in `0.5..=2.0`.
    pub fn set_speed(&self, speed: f32) -> Result<(), AudioError> {
        self.send_command(EngineCommand::SetSpeed(clamp_playback_speed(speed)))
    }

    /// Switches between DSP 32-bit Float (Shared) and Purist Bit-Perfect (Exclusive).
    pub fn set_output_mode(&self, mode: AudioOutputMode) -> Result<(), AudioError> {
        self.send_command(EngineCommand::SetOutputMode(mode))
    }

    /// Selects the target WASAPI endpoint ID (`None` follows Windows default endpoint).
    pub fn set_device(&self, device_id: Option<String>) -> Result<(), AudioError> {
        self.send_command(EngineCommand::SetDevice(device_id))
    }

    /// Refreshes Signal Path telemetry after external DSP preset/parameter changes.
    pub fn refresh_signal_path(&self) -> Result<(), AudioError> {
        self.send_command(EngineCommand::RefreshSignalPath)
    }

    /// Stops stream & closes any open decoder file handle so `lunaria-tags` can rewrite metadata
    /// on Windows without `ERROR_SHARING_VIOLATION`.
    pub fn suspend_file_handle(
        &self,
        target_path: Option<&Path>,
    ) -> Result<FileHandleSuspension, AudioError> {
        let (ack_tx, ack_rx) = crossbeam_channel::bounded(1);
        self.send_command(EngineCommand::SuspendFileHandle {
            target_path: target_path.map(Path::to_path_buf),
            ack_tx,
        })?;
        ack_rx
            .recv_timeout(Duration::from_secs(3))
            .map_err(|_| AudioError::EngineClosed)
    }

    /// Re-opens the suspended track after tag writing completes and restores play position.
    pub fn resume_after_suspension(
        &self,
        suspension: FileHandleSuspension,
    ) -> Result<(), AudioError> {
        self.send_command(EngineCommand::ResumeFromSuspension(Box::new(suspension)))
    }

    /// Gracefully stops the engine worker threads.
    pub fn shutdown(&self) {
        let _ = self.cmd_tx.send(EngineCommand::Shutdown);
    }

    fn send_command(&self, cmd: EngineCommand) -> Result<(), AudioError> {
        self.cmd_tx.send(cmd).map_err(|_| AudioError::EngineClosed)
    }
}

impl Drop for PlaybackEngine {
    fn drop(&mut self) {
        self.shutdown();
        if let Some(handle) = self.coordinator.take() {
            let _ = handle.join();
        }
    }
}

struct ActiveStreamState {
    sample_rate_hz: u32,
    bit_depth: u16,
    encoding_label: String,
    is_exclusive: bool,
    fallback_reason: Option<String>,
    device: AudioDeviceInfo,
}

fn run_coordinator_loop(shared: &Arc<SharedState>, cmd_rx: &Receiver<EngineCommand>) {
    let (render_cmd_tx, render_cmd_rx) = crossbeam_channel::bounded::<RenderCommand>(32);
    let (render_notice_tx, render_notice_rx) = crossbeam_channel::bounded::<RenderNotice>(32);
    let (hotplug_tx, hotplug_rx) = crossbeam_channel::bounded::<DeviceHotPlugEvent>(32);

    let (sample_prod, sample_cons) = RingBuffer::<f64>::new(RING_CAPACITY_FRAMES * 2);
    let (marker_prod, marker_cons) = RingBuffer::<SegmentMarker>::new(MARKER_CAPACITY);

    let shared_render = Arc::clone(shared);
    let render_handle = match std::thread::Builder::new()
        .name("lunaria-wasapi-render".to_owned())
        .spawn(move || {
            run_render_loop(
                &shared_render,
                &render_cmd_rx,
                &render_notice_tx,
                &hotplug_tx,
                sample_cons,
                marker_cons,
            );
        }) {
        Ok(h) => h,
        Err(err) => {
            tracing::error!(error = %err, "failed to spawn wasapi render thread");
            return;
        }
    };

    let mut state = CoordinatorState {
        shared: Arc::clone(shared),
        render_cmd_tx,
        sample_prod,
        marker_prod,
        active_decoder: None,
        active_request: None,
        next_decoder: None,
        next_request: None,
        queued_next: None,
        resampler: None,
        stretcher: None,
        crossfade_resampler: None,
        crossfade_started_ms: None,
        active_stream: None,
        selected_device_id: None,
        output_mode: AudioOutputMode::DspFloat32,
        speed: 1.0,
        volume: 1.0,
        status: PlaybackStatus::Idle,
        error_message: None,
        decode_scratch: Vec::with_capacity(8192),
        xfade_scratch: Vec::with_capacity(8192),
        resampled_scratch: Vec::with_capacity(8192),
        pending_out: Vec::with_capacity(16384),
        pending_offset: 0,
        pending_seg_start_ms: 0,
        pending_seg_end_ms: 0,
        pending_is_eos: false,
        pending_switch_meta: None,
        active_channels: 2,
    };

    loop {
        if state.status == PlaybackStatus::Playing {
            while let Ok(cmd) = cmd_rx.try_recv() {
                if !state.handle_command(cmd) {
                    let _ = state.render_cmd_tx.send(RenderCommand::Shutdown);
                    let _ = render_handle.join();
                    return;
                }
            }
            while let Ok(notice) = render_notice_rx.try_recv() {
                state.handle_render_notice(notice);
            }
            while let Ok(hp) = hotplug_rx.try_recv() {
                state.handle_hotplug(hp);
            }

            if state.status == PlaybackStatus::Playing {
                let made_progress = state.pump_decode_pipeline();
                if made_progress {
                    continue;
                }
            }
        }

        let timeout = if state.status == PlaybackStatus::Playing {
            Duration::from_millis(6)
        } else {
            Duration::from_millis(120)
        };

        select! {
            recv(cmd_rx) -> res => match res {
                Ok(cmd) => {
                    if !state.handle_command(cmd) {
                        let _ = state.render_cmd_tx.send(RenderCommand::Shutdown);
                        let _ = render_handle.join();
                        return;
                    }
                }
                Err(_) => {
                    let _ = state.render_cmd_tx.send(RenderCommand::Shutdown);
                    let _ = render_handle.join();
                    return;
                }
            },
            recv(render_notice_rx) -> res => {
                if let Ok(notice) = res {
                    state.handle_render_notice(notice);
                }
            },
            recv(hotplug_rx) -> res => {
                if let Ok(hp) = res {
                    state.handle_hotplug(hp);
                }
            },
            default(timeout) => {}
        }
    }
}

struct CoordinatorState {
    shared: Arc<SharedState>,
    render_cmd_tx: Sender<RenderCommand>,
    sample_prod: Producer<f64>,
    marker_prod: Producer<SegmentMarker>,
    active_decoder: Option<AudioTrackDecoder>,
    active_request: Option<PlaybackRequest>,
    next_decoder: Option<AudioTrackDecoder>,
    next_request: Option<PlaybackRequest>,
    queued_next: Option<PlaybackRequest>,
    resampler: Option<AudioResampler>,
    stretcher: Option<WsolaTimeStretcher>,
    crossfade_resampler: Option<AudioResampler>,
    crossfade_started_ms: Option<u64>,
    active_stream: Option<ActiveStreamState>,
    selected_device_id: Option<String>,
    output_mode: AudioOutputMode,
    speed: f32,
    volume: f32,
    status: PlaybackStatus,
    error_message: Option<String>,
    decode_scratch: Vec<f64>,
    xfade_scratch: Vec<f64>,
    resampled_scratch: Vec<f64>,
    pending_out: Vec<f64>,
    pending_offset: usize,
    pending_seg_start_ms: u64,
    pending_seg_end_ms: u64,
    pending_is_eos: bool,
    pending_switch_meta: Option<Box<AudioTrackSwitchedMeta>>,
    active_channels: usize,
}

impl CoordinatorState {
    fn handle_command(&mut self, cmd: EngineCommand) -> bool {
        match cmd {
            EngineCommand::LoadAndPlay(req) => {
                self.load_track(*req, 0, true);
            }
            EngineCommand::QueueNext(req) => {
                self.queued_next = req.map(|b| *b);
                self.next_decoder = None;
                self.next_request = None;
                self.crossfade_resampler = None;
                self.crossfade_started_ms = None;
            }
            EngineCommand::Play => {
                if self.status == PlaybackStatus::Ended
                    && let Some(req) = self.active_request.clone()
                {
                    self.load_track(req, 0, true);
                } else if self.active_decoder.is_some() && self.status != PlaybackStatus::Playing {
                    self.status = PlaybackStatus::Playing;
                    self.shared.is_playing.store(true, Ordering::Release);
                    let _ = self.render_cmd_tx.send(RenderCommand::SetPlaying(true));
                    self.publish_telemetry();
                }
            }
            EngineCommand::Pause => {
                if self.status == PlaybackStatus::Playing {
                    self.pause_render_only();
                    self.status = PlaybackStatus::Paused;
                    self.shared.is_playing.store(false, Ordering::Release);
                    self.publish_telemetry();
                }
            }
            EngineCommand::Stop => {
                self.flush_render_and_reset();
                if let Some(dec) = &mut self.active_decoder {
                    let _ = dec.seek_ms(0);
                }
                self.shared.position_ms.store(0, Ordering::Release);
                self.status = if self.active_request.is_some() {
                    PlaybackStatus::Stopped
                } else {
                    PlaybackStatus::Idle
                };
                self.shared.is_playing.store(false, Ordering::Release);
                self.publish_telemetry();
            }
            EngineCommand::SeekMs(target_ms) => {
                if self.active_decoder.is_some() {
                    let was_playing = self.status == PlaybackStatus::Playing;
                    self.flush_render_and_reset();
                    let seek_res = self
                        .active_decoder
                        .as_mut()
                        .map(|dec| dec.seek_ms(target_ms));
                    match seek_res {
                        Some(Ok(actual_ms)) => {
                            self.shared.position_ms.store(actual_ms, Ordering::Release);
                            if was_playing {
                                self.status = PlaybackStatus::Playing;
                                self.shared.is_playing.store(true, Ordering::Release);
                                let _ = self.pump_decode_pipeline();
                                let _ = self.render_cmd_tx.send(RenderCommand::SetPlaying(true));
                            } else {
                                self.status = PlaybackStatus::Paused;
                            }
                            self.publish_telemetry();
                        }
                        Some(Err(err)) => {
                            self.error_message = Some(err.to_string());
                            self.status = PlaybackStatus::Error;
                            self.shared.is_playing.store(false, Ordering::Release);
                            self.publish_telemetry();
                        }
                        None => {}
                    }
                }
            }
            EngineCommand::SetVolume(vol) => {
                self.volume = vol;
                let _ = self.render_cmd_tx.send(RenderCommand::SetVolume(vol));
                self.publish_telemetry();
            }
            EngineCommand::SetSpeed(new_speed) => {
                self.speed = new_speed;
                if !self.is_exclusive_active()
                    && let Some(stretcher) = &mut self.stretcher
                {
                    stretcher.set_speed(new_speed);
                }
                self.publish_telemetry();
            }
            EngineCommand::SetOutputMode(mode) => {
                if self.output_mode != mode {
                    self.output_mode = mode;
                    self.reopen_stream_at_current_pos();
                } else {
                    self.publish_telemetry();
                }
            }
            EngineCommand::SetDevice(dev_id) => {
                if self.selected_device_id != dev_id {
                    self.selected_device_id = dev_id;
                    self.reopen_stream_at_current_pos();
                } else {
                    self.publish_telemetry();
                }
            }
            EngineCommand::RefreshSignalPath => {
                let effective_bypass =
                    self.shared.dsp_store.snapshot().bypassed || self.is_exclusive_active();
                let _ = self
                    .render_cmd_tx
                    .send(RenderCommand::SetDspBypassed(effective_bypass));
                self.publish_telemetry();
            }
            EngineCommand::SuspendFileHandle {
                target_path,
                ack_tx,
            } => {
                let should_drop = match (&self.active_request, &target_path) {
                    (Some(active), Some(path)) => same_file_path(&active.file_path, path),
                    (Some(_), None) => true,
                    (None, _) => false,
                };
                if should_drop {
                    let was_playing = self.status == PlaybackStatus::Playing;
                    let saved_pos = self.shared.position_ms.load(Ordering::Acquire);
                    let saved_req = self.active_request.clone();

                    self.pause_render_and_ack();
                    self.active_decoder = None;
                    self.next_decoder = None;
                    self.next_request = None;
                    self.crossfade_resampler = None;
                    self.crossfade_started_ms = None;
                    self.status = PlaybackStatus::Paused;
                    self.shared.is_playing.store(false, Ordering::Release);
                    self.publish_telemetry();

                    let _ = ack_tx.send(FileHandleSuspension {
                        request: saved_req,
                        position_ms: saved_pos,
                        was_playing,
                    });
                } else {
                    if let (Some(next), Some(path)) = (&self.next_request, &target_path)
                        && same_file_path(&next.file_path, path)
                    {
                        self.next_decoder = None;
                        self.next_request = None;
                        self.crossfade_started_ms = None;
                    }
                    let _ = ack_tx.send(FileHandleSuspension {
                        request: None,
                        position_ms: 0,
                        was_playing: false,
                    });
                }
            }
            EngineCommand::ResumeFromSuspension(susp) => {
                if let Some(req) = susp.request {
                    self.load_track(req, susp.position_ms, susp.was_playing);
                }
            }
            EngineCommand::Shutdown => return false,
        }
        true
    }

    fn handle_render_notice(&mut self, notice: RenderNotice) {
        match notice {
            RenderNotice::TrackBecameAudible(meta) => {
                self.active_request = Some(meta.request);
                self.update_dsp_replay_gain();
                self.publish_telemetry();
            }
            RenderNotice::PlaybackEnded => {
                self.status = PlaybackStatus::Ended;
                self.shared.is_playing.store(false, Ordering::Release);
                if let Some(dur) = self
                    .active_decoder
                    .as_ref()
                    .map(AudioTrackDecoder::duration_ms)
                {
                    self.shared.position_ms.store(dur, Ordering::Release);
                }
                self.publish_telemetry();
            }
            RenderNotice::DeviceInvalidated(reason) => {
                if self.status == PlaybackStatus::Playing {
                    self.status = PlaybackStatus::Paused;
                    self.shared.is_playing.store(false, Ordering::Release);
                }
                self.error_message = Some(reason);
                self.publish_telemetry();
            }
        }
    }

    fn handle_hotplug(&mut self, event: DeviceHotPlugEvent) {
        let active_id = self.active_stream.as_ref().map(|s| s.device.id.as_str());
        match event {
            DeviceHotPlugEvent::DeviceRemoved(id)
            | DeviceHotPlugEvent::DeviceStateChanged {
                id,
                is_active: false,
            } => {
                if active_id == Some(id.as_str()) && self.status == PlaybackStatus::Playing {
                    self.pause_render_only();
                    self.status = PlaybackStatus::Paused;
                    self.shared.is_playing.store(false, Ordering::Release);
                    self.error_message =
                        Some("Playback paused: audio output device disconnected.".to_owned());
                    self.publish_telemetry();
                }
            }
            DeviceHotPlugEvent::DefaultRenderDeviceChanged(_)
                if self.selected_device_id.is_none() && self.active_decoder.is_some() =>
            {
                self.reopen_stream_at_current_pos();
            }
            _ => {}
        }
    }

    fn is_exclusive_active(&self) -> bool {
        self.active_stream.as_ref().is_some_and(|s| s.is_exclusive)
    }

    fn load_track(&mut self, req: PlaybackRequest, start_pos_ms: u64, auto_play: bool) {
        self.flush_render_and_reset();
        self.next_decoder = None;
        self.next_request = None;
        self.crossfade_resampler = None;
        self.crossfade_started_ms = None;
        self.error_message = None;

        let mut decoder =
            match AudioTrackDecoder::open(&req.file_path, req.clip_start_ms, req.clip_end_ms) {
                Ok(d) => d,
                Err(err) => {
                    self.active_decoder = None;
                    self.active_request = None;
                    self.status = PlaybackStatus::Error;
                    self.shared.is_playing.store(false, Ordering::Release);
                    self.error_message = Some(err.to_string());
                    self.publish_telemetry();
                    return;
                }
            };

        if start_pos_ms > 0
            && let Err(err) = decoder.seek_ms(start_pos_ms)
        {
            tracing::warn!(error = %err, "failed to restore position after resume, starting at 0");
        }

        let info = decoder.info().clone();
        self.active_channels = info.channels;

        let effective_bypass = self.shared.dsp_store.snapshot().bypassed
            || self.output_mode == AudioOutputMode::PuristDirectBitPerfect;

        if !self.configure_stream_for_track(&info.spec, effective_bypass) {
            return;
        }

        self.active_decoder = Some(decoder);
        self.active_request = Some(req);
        self.update_dsp_replay_gain();
        self.rebuild_pipeline_processors(info.sample_rate_hz);

        self.shared
            .position_ms
            .store(start_pos_ms, Ordering::Release);
        if auto_play {
            self.status = PlaybackStatus::Playing;
            self.shared.is_playing.store(true, Ordering::Release);
            let _ = self.pump_decode_pipeline();
            let _ = self.render_cmd_tx.send(RenderCommand::SetPlaying(true));
        } else {
            self.status = PlaybackStatus::Paused;
            self.shared.is_playing.store(false, Ordering::Release);
        }
        self.publish_telemetry();
    }

    fn reopen_stream_at_current_pos(&mut self) {
        if let Some(req) = self.active_request.clone() {
            let was_playing = self.status == PlaybackStatus::Playing;
            let pos = self.shared.position_ms.load(Ordering::Acquire);
            self.load_track(req, pos, was_playing);
        } else {
            // Preview signal path update only
            self.publish_telemetry();
        }
    }

    fn configure_stream_for_track(&mut self, source_spec: &AudioSpec, dsp_bypassed: bool) -> bool {
        let (ack_tx, ack_rx) = crossbeam_channel::bounded(1);
        let cmd = RenderCommand::Configure {
            device_id: self.selected_device_id.clone(),
            mode: self.output_mode,
            source_spec: source_spec.clone(),
            src_channels: self.active_channels,
            dsp_bypassed,
            ack_tx,
        };
        if self.render_cmd_tx.send(cmd).is_err() {
            self.status = PlaybackStatus::Error;
            self.error_message = Some("Render thread unavailable".to_owned());
            return false;
        }

        match ack_rx.recv_timeout(Duration::from_secs(3)) {
            Ok(Ok(meta)) => {
                self.active_stream = Some(ActiveStreamState {
                    sample_rate_hz: meta.sample_rate_hz,
                    bit_depth: meta.bit_depth,
                    encoding_label: meta.encoding_label,
                    is_exclusive: meta.is_exclusive,
                    fallback_reason: meta.fallback_reason,
                    device: meta.device,
                });
                true
            }
            Ok(Err(err)) => {
                self.status = PlaybackStatus::Error;
                self.shared.is_playing.store(false, Ordering::Release);
                self.error_message = Some(err.to_string());
                self.publish_telemetry();
                false
            }
            Err(_) => {
                self.status = PlaybackStatus::Error;
                self.shared.is_playing.store(false, Ordering::Release);
                self.error_message = Some("Timed out opening WASAPI stream".to_owned());
                self.publish_telemetry();
                false
            }
        }
    }

    fn rebuild_pipeline_processors(&mut self, source_rate_hz: u32) {
        let out_rate = self
            .active_stream
            .as_ref()
            .map_or(48_000, |s| s.sample_rate_hz);
        self.resampler = AudioResampler::new(source_rate_hz, out_rate, self.active_channels).ok();
        let speed = if self.is_exclusive_active() {
            1.0
        } else {
            self.speed
        };
        self.stretcher = Some(WsolaTimeStretcher::new(
            out_rate,
            self.active_channels,
            speed,
        ));
    }

    fn pause_render_only(&self) {
        let _ = self.render_cmd_tx.send(RenderCommand::SetPlaying(false));
    }

    fn pause_render_and_ack(&self) {
        let (ack_tx, ack_rx) = crossbeam_channel::bounded(1);
        let _ = self
            .render_cmd_tx
            .send(RenderCommand::PauseAndFlush { ack_tx });
        let _ = ack_rx.recv_timeout(Duration::from_secs(2));
    }

    fn flush_render_and_reset(&mut self) {
        let (sample_prod, sample_cons) =
            RingBuffer::<f64>::new(RING_CAPACITY_FRAMES * self.active_channels.max(1));
        let (marker_prod, marker_cons) = RingBuffer::<SegmentMarker>::new(MARKER_CAPACITY);
        self.sample_prod = sample_prod;
        self.marker_prod = marker_prod;

        self.pending_out.clear();
        self.pending_offset = 0;
        self.pending_is_eos = false;
        self.pending_switch_meta = None;

        if let Some(resampler) = &mut self.resampler {
            resampler.reset();
        }
        if let Some(stretcher) = &mut self.stretcher {
            stretcher.reset();
        }
        if let Some(xf_res) = &mut self.crossfade_resampler {
            xf_res.reset();
        }

        let effective_bypass =
            self.shared.dsp_store.snapshot().bypassed || self.is_exclusive_active();
        let (ack_tx, ack_rx) = crossbeam_channel::bounded(1);
        let _ = self.render_cmd_tx.send(RenderCommand::SwapRings {
            sample_rx: sample_cons,
            marker_rx: marker_cons,
            src_channels: self.active_channels,
            dsp_bypassed: effective_bypass,
            ack_tx,
        });
        let _ = ack_rx.recv_timeout(Duration::from_secs(2));
    }

    fn pump_decode_pipeline(&mut self) -> bool {
        let mut pushed_any = false;
        let ch = self.active_channels.max(1);

        loop {
            // Drain any leftover staged output samples first.
            if self.pending_offset < self.pending_out.len() {
                let slots = self.sample_prod.slots();
                let aligned_slots = (slots / ch) * ch;
                if aligned_slots == 0 || self.marker_prod.slots() == 0 {
                    break;
                }
                let remaining_samples = self.pending_out.len() - self.pending_offset;
                let push_samples = remaining_samples.min(aligned_slots);
                let push_frames = push_samples / ch;
                if push_frames == 0 {
                    break;
                }

                let (pushed, _) = self.sample_prod.push_partial_slice(
                    &self.pending_out[self.pending_offset..self.pending_offset + push_samples],
                );
                let pushed_frames = pushed.len() / ch;
                self.pending_offset += pushed.len();

                let is_last_slice = self.pending_offset >= self.pending_out.len();
                let seg_end = if is_last_slice {
                    self.pending_seg_end_ms
                } else {
                    self.pending_seg_start_ms
                };
                let _ = self.marker_prod.push(SegmentMarker {
                    frames: pushed_frames,
                    start_ms: self.pending_seg_start_ms,
                    end_ms: seg_end,
                    is_eos: is_last_slice && self.pending_is_eos,
                    switch_meta: self.pending_switch_meta.take(),
                });
                pushed_any = true;

                if self.pending_offset >= self.pending_out.len() {
                    self.pending_out.clear();
                    self.pending_offset = 0;
                    self.pending_is_eos = false;
                } else {
                    break;
                }
            }

            if self.sample_prod.slots() < 1024 * ch || self.marker_prod.slots() == 0 {
                break;
            }

            let Some(dec) = &mut self.active_decoder else {
                break;
            };

            let start_ms = dec.position_ms();
            let frames_read = match dec.read_interleaved_f64(&mut self.decode_scratch) {
                Ok(n) => n,
                Err(err) => {
                    self.error_message = Some(err.to_string());
                    self.status = PlaybackStatus::Error;
                    self.shared.is_playing.store(false, Ordering::Release);
                    self.publish_telemetry();
                    break;
                }
            };

            if frames_read == 0 {
                let dec_duration_ms = dec.duration_ms();
                // Flush resampler and WSOLA tails cleanly.
                self.resampled_scratch.clear();
                if let Some(resampler) = &mut self.resampler {
                    let _ = resampler.flush(&mut self.resampled_scratch);
                }
                self.pending_out.clear();
                let tail_src = &self.resampled_scratch;
                if let Some(stretcher) = &mut self.stretcher {
                    if !tail_src.is_empty() {
                        let _ = stretcher.process(tail_src, &mut self.pending_out);
                    }
                    let _ = stretcher.flush(&mut self.pending_out);
                } else {
                    self.pending_out.extend_from_slice(tail_src);
                }
                self.pending_offset = 0;
                self.pending_seg_start_ms = start_ms;
                self.pending_seg_end_ms = dec_duration_ms;

                if let Some(next_req) = self.next_request.take().or_else(|| self.queued_next.take())
                    && self.try_gapless_advance(next_req)
                {
                    if self.pending_out.is_empty() {
                        continue;
                    }
                    self.pending_is_eos = false;
                } else if self.pending_out.is_empty() {
                    let _ = self.marker_prod.push(SegmentMarker {
                        frames: 0,
                        start_ms: dec_duration_ms,
                        end_ms: dec_duration_ms,
                        is_eos: true,
                        switch_meta: None,
                    });
                    self.active_decoder = None;
                    break;
                } else {
                    self.pending_is_eos = true;
                    self.active_decoder = None;
                }
                continue;
            }

            let end_ms = dec.position_ms();
            let active_dur_ms = dec.duration_ms();
            let track_rate_hz = dec.info().sample_rate_hz;

            // Optional equal-power crossfade when nearing end of active track in DSP Shared mode.
            let xfade_sec = if self.is_exclusive_active() {
                0
            } else {
                self.shared
                    .dsp_store
                    .snapshot()
                    .settings
                    .crossfade_duration_sec
            };
            let xfade_ms = u64::from(xfade_sec.min(12)) * 1_000;
            let remaining_ms = active_dur_ms.saturating_sub(end_ms);
            if xfade_ms > 0
                && remaining_ms <= xfade_ms
                && self.queued_next.is_some()
                && self.next_decoder.is_none()
                && let Some(next_req) = self.queued_next.take()
                && let Ok(next_dec) = AudioTrackDecoder::open(
                    &next_req.file_path,
                    next_req.clip_start_ms,
                    next_req.clip_end_ms,
                )
            {
                if next_dec.info().channels == ch && next_dec.info().sample_rate_hz == track_rate_hz
                {
                    self.crossfade_started_ms = Some(0);
                    self.next_decoder = Some(next_dec);
                    self.next_request = Some(next_req);
                } else {
                    self.queued_next = Some(next_req);
                }
            }

            if let Some(next_dec) = &mut self.next_decoder
                && xfade_ms > 0
            {
                let fade_out = fade_out_volume(remaining_ms, xfade_ms);
                let elapsed_in = next_dec.position_ms();
                let fade_in = fade_in_volume(elapsed_in, xfade_ms);
                if let Ok(next_frames) = next_dec.read_interleaved_f64(&mut self.xfade_scratch)
                    && next_frames > 0
                {
                    let len = self.decode_scratch.len().min(self.xfade_scratch.len());
                    for i in 0..len {
                        self.decode_scratch[i] = self.decode_scratch[i] * f64::from(fade_out)
                            + self.xfade_scratch[i] * f64::from(fade_in);
                    }
                    if len < self.decode_scratch.len() {
                        for sample in &mut self.decode_scratch[len..] {
                            *sample *= f64::from(fade_out);
                        }
                    }
                }
            }

            // Resample -> WSOLA speed -> pending_out.
            self.resampled_scratch.clear();
            if let Some(resampler) = &mut self.resampler {
                if let Err(err) =
                    resampler.process(&self.decode_scratch, &mut self.resampled_scratch)
                {
                    self.error_message = Some(err.to_string());
                    self.status = PlaybackStatus::Error;
                    self.shared.is_playing.store(false, Ordering::Release);
                    self.publish_telemetry();
                    break;
                }
            } else {
                self.resampled_scratch
                    .extend_from_slice(&self.decode_scratch);
            }

            self.pending_out.clear();
            if let Some(stretcher) = &mut self.stretcher {
                let _ = stretcher.process(&self.resampled_scratch, &mut self.pending_out);
            } else {
                self.pending_out.extend_from_slice(&self.resampled_scratch);
            }
            self.pending_offset = 0;
            self.pending_seg_start_ms = start_ms;
            self.pending_seg_end_ms = end_ms;
            self.pending_is_eos = false;
        }

        pushed_any
    }

    fn try_gapless_advance(&mut self, next_req: PlaybackRequest) -> bool {
        self.crossfade_started_ms = None;
        let next_dec = if let Some(existing) = self.next_decoder.take() {
            existing
        } else {
            match AudioTrackDecoder::open(
                &next_req.file_path,
                next_req.clip_start_ms,
                next_req.clip_end_ms,
            ) {
                Ok(dec) => dec,
                Err(err) => {
                    tracing::warn!(error = %err, "gapless queued track failed to open");
                    return false;
                }
            }
        };

        let next_info = next_dec.info().clone();
        let can_seamless_continue = next_info.channels == self.active_channels
            && (!self.is_exclusive_active()
                || next_info.sample_rate_hz
                    == self.active_stream.as_ref().map_or(0, |s| s.sample_rate_hz));

        if !can_seamless_continue {
            self.load_track(next_req, 0, true);
            return true;
        }

        self.resampler = self.active_stream.as_ref().and_then(|s| {
            AudioResampler::new(
                next_info.sample_rate_hz,
                s.sample_rate_hz,
                next_info.channels,
            )
            .ok()
        });
        self.pending_switch_meta = Some(Box::new(AudioTrackSwitchedMeta { request: next_req }));
        self.active_decoder = Some(next_dec);
        true
    }

    fn update_dsp_replay_gain(&self) {
        let snap = self.shared.dsp_store.snapshot();
        let bypass = snap.bypassed;
        if let Some(req) = &self.active_request {
            self.shared.dsp_store.update_all(
                snap.settings.clone(),
                bypass,
                req.replay_gain_track_db,
                req.replay_gain_album_db,
                req.replay_gain_track_peak,
                req.replay_gain_album_peak,
            );
        }
    }

    fn publish_telemetry(&self) {
        let dsp_snap = self.shared.dsp_store.snapshot();
        let live_pos = self.shared.position_ms.load(Ordering::Acquire);
        let live_xruns = self.shared.xrun_count.load(Ordering::Acquire);

        let selected_device = self
            .active_stream
            .as_ref()
            .map(|s| s.device.clone())
            .or_else(|| resolve_preview_device(self.selected_device_id.as_deref()));

        let signal_path =
            if let (Some(dec), Some(stream)) = (&self.active_decoder, &self.active_stream) {
                let resampler_label = if stream.is_exclusive {
                    None
                } else {
                    self.resampler.as_ref().and_then(AudioResampler::label)
                };
                let req_ref = self.active_request.as_ref();
                build_signal_path(&SignalPathInput {
                    source_spec: &dec.info().spec,
                    decoder_name: &dec.info().decoder_name,
                    requested_mode: self.output_mode,
                    device: Some(&stream.device),
                    actual_output_rate_hz: stream.sample_rate_hz,
                    actual_output_bit_depth: stream.bit_depth,
                    output_encoding_label: &stream.encoding_label,
                    is_exclusive_active: stream.is_exclusive,
                    fallback_reason: stream.fallback_reason.as_deref(),
                    resampler_label,
                    speed: if stream.is_exclusive { 1.0 } else { self.speed },
                    dsp: &dsp_snap,
                    track_replay_gain_db: req_ref.and_then(|r| r.replay_gain_track_db),
                    album_replay_gain_db: req_ref.and_then(|r| r.replay_gain_album_db),
                    track_replay_gain_peak: req_ref.and_then(|r| r.replay_gain_track_peak),
                    album_replay_gain_peak: req_ref.and_then(|r| r.replay_gain_album_peak),
                })
            } else {
                build_preview_signal_path_with_dsp(
                    selected_device.as_ref(),
                    self.output_mode,
                    &dsp_snap,
                )
            };

        let next = PlaybackTelemetry {
            status: self.status,
            active_track: self.active_request.clone(),
            source_info: self.active_decoder.as_ref().map(|d| d.info().clone()),
            duration_ms: self
                .active_decoder
                .as_ref()
                .map_or(0, AudioTrackDecoder::duration_ms),
            position_ms: live_pos,
            volume: self.volume,
            speed: self.speed,
            output_mode: self.output_mode,
            selected_device,
            signal_path,
            xrun_count: live_xruns,
            error_message: self.error_message.clone(),
        };

        self.shared.telemetry.store(Arc::new(next));
    }
}

fn same_file_path(a: &Path, b: &Path) -> bool {
    let norm = |p: &Path| {
        let s = p.to_string_lossy().replace('/', "\\");
        s.strip_prefix(r"\\?\")
            .unwrap_or(&s)
            .trim_end_matches('\\')
            .to_ascii_lowercase()
    };
    norm(a) == norm(b)
}

fn resolve_preview_device(device_id: Option<&str>) -> Option<AudioDeviceInfo> {
    let list = enumerate_render_devices().ok()?;
    match device_id.filter(|id| !id.is_empty()) {
        Some(id) => list.into_iter().find(|d| d.id == id),
        None => list.into_iter().find(|d| d.is_default),
    }
}

fn run_render_loop(
    shared: &Arc<SharedState>,
    cmd_rx: &Receiver<RenderCommand>,
    notice_tx: &Sender<RenderNotice>,
    hotplug_tx: &Sender<DeviceHotPlugEvent>,
    mut sample_rx: Consumer<f64>,
    mut marker_rx: Consumer<SegmentMarker>,
) {
    #[cfg(windows)]
    let _com_guard = ComMtaGuard::enter();

    let _hotplug_watcher = HotPlugWatcher::register(hotplug_tx.clone()).ok();

    #[cfg(windows)]
    let mut active_stream: Option<ActiveWasapiStream> = None;

    let mut stream_started = false;
    let mut playing = false;
    let mut src_channels = 2usize;
    let mut volume = 1.0_f64;
    let mut dsp_bypassed = false;
    let mut dsp_engine = BiquadDspEngine::new();

    let mut pull_f64: Vec<f64> = Vec::with_capacity(4096 * 2);
    let mut dsp_f32: Vec<f32> = Vec::with_capacity(4096 * 2);
    let mut pcm_bytes: Vec<u8> = Vec::with_capacity(4096 * 8 * 4);
    let mut pending_markers: VecDeque<SegmentMarker> = VecDeque::with_capacity(64);
    let mut current_marker: Option<SegmentMarker> = None;
    let mut current_marker_done = 0usize;
    let mut eos_pending = false;

    loop {
        while let Ok(cmd) = cmd_rx.try_recv() {
            match cmd {
                RenderCommand::Configure {
                    device_id,
                    mode,
                    source_spec,
                    src_channels: ch,
                    dsp_bypassed: bypass,
                    ack_tx,
                } => {
                    #[cfg(windows)]
                    if let Some(stream) = &active_stream
                        && stream_started
                    {
                        let _ = stream.audio_client.stop_stream();
                    }
                    stream_started = false;
                    playing = false;
                    src_channels = ch.clamp(1, 8);
                    dsp_bypassed = bypass;

                    #[cfg(windows)]
                    match open_wasapi_stream(device_id.as_deref(), mode, &source_spec) {
                        Ok(stream) => {
                            let _ = dsp_engine.configure(stream.sample_rate_hz, src_channels);
                            dsp_engine.sync_from_store(&shared.dsp_store);
                            let max_frames = stream.max_buffer_frames.max(2048);
                            pull_f64.resize(max_frames * src_channels, 0.0);
                            dsp_f32.resize(max_frames * src_channels, 0.0);
                            pcm_bytes.resize(max_frames * stream.block_align, 0);
                            let meta = OpenedStreamMeta {
                                sample_rate_hz: stream.sample_rate_hz,
                                bit_depth: stream.bit_depth,
                                encoding_label: stream.encoding.label().to_owned(),
                                is_exclusive: stream.is_exclusive,
                                fallback_reason: stream.fallback_reason.clone(),
                                device: stream.device_info.clone(),
                            };
                            active_stream = Some(stream);
                            let _ = ack_tx.send(Ok(meta));
                        }
                        Err(err) => {
                            active_stream = None;
                            let _ = ack_tx.send(Err(err));
                        }
                    }

                    #[cfg(not(windows))]
                    {
                        let _ = (&device_id, &mode, &source_spec);
                        let _ = dsp_engine.configure(48_000, src_channels);
                        pull_f64.resize(2048 * src_channels, 0.0);
                        dsp_f32.resize(2048 * src_channels, 0.0);
                        let _ = ack_tx.send(Ok(OpenedStreamMeta {
                            sample_rate_hz: 48_000,
                            bit_depth: 32,
                            encoding_label: "32-bit Float PCM".to_owned(),
                            is_exclusive: false,
                            fallback_reason: None,
                            device: AudioDeviceInfo {
                                id: "stub".to_owned(),
                                name: "Non-Windows Stub".to_owned(),
                                is_default: true,
                                category: lunaria_model::AudioOutputCategory::Other,
                                default_sample_rate_hz: 48_000,
                                default_bit_depth: 32,
                                default_channels: 2,
                            },
                        }));
                    }
                }
                RenderCommand::SetPlaying(next_play) => {
                    playing = next_play;
                    eos_pending = false;
                    #[cfg(windows)]
                    if let Some(stream) = &active_stream {
                        if playing && !stream_started {
                            if let Ok(avail) = stream.audio_client.get_available_space_in_frames() {
                                let n = (avail as usize).min(stream.max_buffer_frames);
                                if n > 0 {
                                    let _ = fill_and_write_wasapi(
                                        stream,
                                        &shared.dsp_store,
                                        &mut dsp_engine,
                                        dsp_bypassed,
                                        src_channels,
                                        volume,
                                        &mut sample_rx,
                                        &mut marker_rx,
                                        &mut pending_markers,
                                        &mut current_marker,
                                        &mut current_marker_done,
                                        &mut eos_pending,
                                        &mut pull_f64,
                                        &mut dsp_f32,
                                        &mut pcm_bytes,
                                        shared,
                                        notice_tx,
                                        n,
                                    );
                                }
                            }
                            if stream.audio_client.start_stream().is_ok() {
                                stream_started = true;
                            }
                        } else if !playing && stream_started {
                            let _ = stream.audio_client.stop_stream();
                            stream_started = false;
                        }
                    }
                }
                RenderCommand::PauseAndFlush { ack_tx } => {
                    playing = false;
                    #[cfg(windows)]
                    if let Some(stream) = &active_stream {
                        if stream_started {
                            let _ = stream.audio_client.stop_stream();
                            stream_started = false;
                        }
                        let _ = stream.audio_client.reset_stream();
                    }
                    drain_ring_buffers(
                        &mut sample_rx,
                        &mut marker_rx,
                        &mut pending_markers,
                        &mut pull_f64,
                    );
                    current_marker = None;
                    current_marker_done = 0;
                    eos_pending = false;
                    dsp_engine.reset_state();
                    let _ = ack_tx.send(());
                }
                RenderCommand::SwapRings {
                    sample_rx: new_s,
                    marker_rx: new_m,
                    src_channels: ch,
                    dsp_bypassed: bypass,
                    ack_tx,
                } => {
                    #[cfg(windows)]
                    if let Some(stream) = &active_stream {
                        if stream_started {
                            let _ = stream.audio_client.stop_stream();
                            stream_started = false;
                        }
                        let _ = stream.audio_client.reset_stream();
                    }
                    sample_rx = new_s;
                    marker_rx = new_m;
                    src_channels = ch.clamp(1, 8);
                    dsp_bypassed = bypass;
                    pending_markers.clear();
                    current_marker = None;
                    current_marker_done = 0;
                    eos_pending = false;
                    dsp_engine.reset_state();
                    let _ = ack_tx.send(());
                }
                RenderCommand::SetDspBypassed(bypass) => {
                    dsp_bypassed = bypass;
                }
                RenderCommand::SetVolume(vol) => {
                    volume = f64::from(vol.clamp(0.0, 1.0));
                }
                RenderCommand::Shutdown => {
                    #[cfg(windows)]
                    if let Some(stream) = &active_stream
                        && stream_started
                    {
                        let _ = stream.audio_client.stop_stream();
                    }
                    return;
                }
            }
        }

        if !playing {
            match cmd_rx.recv_timeout(Duration::from_millis(25)) {
                Ok(cmd) => {
                    // Re-inject by handling next iteration? Simply handle inline helper or push?
                    // Wait cleanly: break into match helper on next loop would lose `cmd`, so handle single cmd directly:
                    if handle_parked_render_cmd(
                        cmd,
                        #[cfg(windows)]
                        &mut active_stream,
                        &mut stream_started,
                        &mut playing,
                        &mut src_channels,
                        &mut volume,
                        &mut dsp_bypassed,
                        &mut dsp_engine,
                        &mut sample_rx,
                        &mut marker_rx,
                        &mut pending_markers,
                        &mut current_marker,
                        &mut current_marker_done,
                        &mut eos_pending,
                        &mut pull_f64,
                        &mut dsp_f32,
                        &mut pcm_bytes,
                        shared,
                        notice_tx,
                    ) {
                        return;
                    }
                }
                Err(_) => continue,
            }
            continue;
        }

        #[cfg(windows)]
        if let Some(stream) = &active_stream {
            if stream.event_handle.wait_for_event(60).is_ok() {
                match stream.audio_client.get_available_space_in_frames() {
                    Ok(avail) if avail > 0 => {
                        let n = (avail as usize).min(stream.max_buffer_frames);
                        if let Err(err) = fill_and_write_wasapi(
                            stream,
                            &shared.dsp_store,
                            &mut dsp_engine,
                            dsp_bypassed,
                            src_channels,
                            volume,
                            &mut sample_rx,
                            &mut marker_rx,
                            &mut pending_markers,
                            &mut current_marker,
                            &mut current_marker_done,
                            &mut eos_pending,
                            &mut pull_f64,
                            &mut dsp_f32,
                            &mut pcm_bytes,
                            shared,
                            notice_tx,
                            n,
                        ) {
                            playing = false;
                            stream_started = false;
                            let _ = notice_tx.try_send(RenderNotice::DeviceInvalidated(err));
                        }
                    }
                    Err(err) if is_device_invalidated_error(&err) => {
                        playing = false;
                        stream_started = false;
                        let _ = notice_tx.try_send(RenderNotice::DeviceInvalidated(
                            "Audio endpoint disconnected during playback.".to_owned(),
                        ));
                    }
                    _ => {}
                }
            }
            if eos_pending
                && sample_rx.is_empty()
                && pending_markers.is_empty()
                && current_marker.is_none()
            {
                let _ = stream.audio_client.stop_stream();
                stream_started = false;
                playing = false;
                let _ = notice_tx.try_send(RenderNotice::PlaybackEnded);
            }
        }

        #[cfg(not(windows))]
        {
            std::thread::sleep(Duration::from_millis(10));
            let n = 480usize;
            advance_markers_and_consume(
                &mut sample_rx,
                &mut marker_rx,
                &mut pending_markers,
                &mut current_marker,
                &mut current_marker_done,
                &mut eos_pending,
                src_channels,
                n,
                &mut pull_f64,
                shared,
                notice_tx,
            );
            if eos_pending
                && sample_rx.is_empty()
                && pending_markers.is_empty()
                && current_marker.is_none()
            {
                playing = false;
                let _ = notice_tx.try_send(RenderNotice::PlaybackEnded);
            }
        }
    }
}

#[expect(
    clippy::too_many_arguments,
    reason = "mirrors loop state cleanly so parked wait never drops a queued render command"
)]
fn handle_parked_render_cmd(
    cmd: RenderCommand,
    #[cfg(windows)] active_stream: &mut Option<ActiveWasapiStream>,
    stream_started: &mut bool,
    playing: &mut bool,
    src_channels: &mut usize,
    volume: &mut f64,
    dsp_bypassed: &mut bool,
    dsp_engine: &mut BiquadDspEngine,
    sample_rx: &mut Consumer<f64>,
    marker_rx: &mut Consumer<SegmentMarker>,
    pending_markers: &mut VecDeque<SegmentMarker>,
    current_marker: &mut Option<SegmentMarker>,
    current_marker_done: &mut usize,
    eos_pending: &mut bool,
    pull_f64: &mut Vec<f64>,
    dsp_f32: &mut Vec<f32>,
    pcm_bytes: &mut Vec<u8>,
    shared: &Arc<SharedState>,
    notice_tx: &Sender<RenderNotice>,
) -> bool {
    match cmd {
        RenderCommand::Configure {
            device_id,
            mode,
            source_spec,
            src_channels: ch,
            dsp_bypassed: bypass,
            ack_tx,
        } => {
            #[cfg(windows)]
            if let Some(stream) = active_stream.as_ref()
                && *stream_started
            {
                let _ = stream.audio_client.stop_stream();
            }
            *stream_started = false;
            *playing = false;
            *src_channels = ch.clamp(1, 8);
            *dsp_bypassed = bypass;

            #[cfg(windows)]
            match open_wasapi_stream(device_id.as_deref(), mode, &source_spec) {
                Ok(stream) => {
                    let _ = dsp_engine.configure(stream.sample_rate_hz, *src_channels);
                    dsp_engine.sync_from_store(&shared.dsp_store);
                    let max_frames = stream.max_buffer_frames.max(2048);
                    pull_f64.resize(max_frames * *src_channels, 0.0);
                    dsp_f32.resize(max_frames * *src_channels, 0.0);
                    pcm_bytes.resize(max_frames * stream.block_align, 0);
                    let meta = OpenedStreamMeta {
                        sample_rate_hz: stream.sample_rate_hz,
                        bit_depth: stream.bit_depth,
                        encoding_label: stream.encoding.label().to_owned(),
                        is_exclusive: stream.is_exclusive,
                        fallback_reason: stream.fallback_reason.clone(),
                        device: stream.device_info.clone(),
                    };
                    *active_stream = Some(stream);
                    let _ = ack_tx.send(Ok(meta));
                }
                Err(err) => {
                    *active_stream = None;
                    let _ = ack_tx.send(Err(err));
                }
            }

            #[cfg(not(windows))]
            {
                let _ = (&device_id, &mode, &source_spec, pcm_bytes);
                let _ = dsp_engine.configure(48_000, *src_channels);
                pull_f64.resize(2048 * *src_channels, 0.0);
                dsp_f32.resize(2048 * *src_channels, 0.0);
                let _ = ack_tx.send(Ok(OpenedStreamMeta {
                    sample_rate_hz: 48_000,
                    bit_depth: 32,
                    encoding_label: "32-bit Float PCM".to_owned(),
                    is_exclusive: false,
                    fallback_reason: None,
                    device: AudioDeviceInfo {
                        id: "stub".to_owned(),
                        name: "Non-Windows Stub".to_owned(),
                        is_default: true,
                        category: lunaria_model::AudioOutputCategory::Other,
                        default_sample_rate_hz: 48_000,
                        default_bit_depth: 32,
                        default_channels: 2,
                    },
                }));
            }
        }
        RenderCommand::SetPlaying(next_play) => {
            *playing = next_play;
            *eos_pending = false;
            #[cfg(windows)]
            if let Some(stream) = active_stream.as_ref()
                && *playing
                && !*stream_started
            {
                if let Ok(avail) = stream.audio_client.get_available_space_in_frames() {
                    let n = (avail as usize).min(stream.max_buffer_frames);
                    if n > 0 {
                        let _ = fill_and_write_wasapi(
                            stream,
                            &shared.dsp_store,
                            dsp_engine,
                            *dsp_bypassed,
                            *src_channels,
                            *volume,
                            sample_rx,
                            marker_rx,
                            pending_markers,
                            current_marker,
                            current_marker_done,
                            eos_pending,
                            pull_f64,
                            dsp_f32,
                            pcm_bytes,
                            shared,
                            notice_tx,
                            n,
                        );
                    }
                }
                if stream.audio_client.start_stream().is_ok() {
                    *stream_started = true;
                }
            }
        }
        RenderCommand::PauseAndFlush { ack_tx } => {
            *playing = false;
            #[cfg(windows)]
            if let Some(stream) = active_stream.as_ref() {
                if *stream_started {
                    let _ = stream.audio_client.stop_stream();
                    *stream_started = false;
                }
                let _ = stream.audio_client.reset_stream();
            }
            drain_ring_buffers(sample_rx, marker_rx, pending_markers, pull_f64);
            *current_marker = None;
            *current_marker_done = 0;
            *eos_pending = false;
            dsp_engine.reset_state();
            let _ = ack_tx.send(());
        }
        RenderCommand::SwapRings {
            sample_rx: new_s,
            marker_rx: new_m,
            src_channels: ch,
            dsp_bypassed: bypass,
            ack_tx,
        } => {
            #[cfg(windows)]
            if let Some(stream) = active_stream.as_ref() {
                if *stream_started {
                    let _ = stream.audio_client.stop_stream();
                    *stream_started = false;
                }
                let _ = stream.audio_client.reset_stream();
            }
            *sample_rx = new_s;
            *marker_rx = new_m;
            *src_channels = ch.clamp(1, 8);
            *dsp_bypassed = bypass;
            pending_markers.clear();
            *current_marker = None;
            *current_marker_done = 0;
            *eos_pending = false;
            dsp_engine.reset_state();
            let _ = ack_tx.send(());
        }
        RenderCommand::SetDspBypassed(bypass) => {
            *dsp_bypassed = bypass;
        }
        RenderCommand::SetVolume(vol) => {
            *volume = f64::from(vol.clamp(0.0, 1.0));
        }
        RenderCommand::Shutdown => {
            #[cfg(windows)]
            if let Some(stream) = active_stream.as_ref()
                && *stream_started
            {
                let _ = stream.audio_client.stop_stream();
            }
            return true;
        }
    }
    false
}

fn drain_ring_buffers(
    sample_rx: &mut Consumer<f64>,
    marker_rx: &mut Consumer<SegmentMarker>,
    pending_markers: &mut VecDeque<SegmentMarker>,
    scratch: &mut [f64],
) {
    if !scratch.is_empty() {
        while !sample_rx.is_empty() {
            let (popped, _) = sample_rx.pop_partial_slice(scratch);
            if popped.is_empty() {
                break;
            }
        }
    } else {
        while sample_rx.pop().is_ok() {}
    }
    while marker_rx.pop().is_ok() {}
    pending_markers.clear();
}

#[expect(
    clippy::too_many_arguments,
    reason = "real-time zero-alloc audio callback path uses caller pre-allocated scratch buffers"
)]
fn advance_markers_and_consume(
    sample_rx: &mut Consumer<f64>,
    marker_rx: &mut Consumer<SegmentMarker>,
    pending_markers: &mut VecDeque<SegmentMarker>,
    current_marker: &mut Option<SegmentMarker>,
    current_marker_done: &mut usize,
    eos_pending: &mut bool,
    src_channels: usize,
    frames_wanted: usize,
    pull_f64: &mut [f64],
    shared: &Arc<SharedState>,
    notice_tx: &Sender<RenderNotice>,
) -> usize {
    while pending_markers.len() < 64
        && let Ok(marker) = marker_rx.pop()
    {
        if pending_markers.back().is_none() || pending_markers.len() < 64 {
            pending_markers.push_back(marker);
        }
    }

    let wanted_samples = (frames_wanted * src_channels).min(pull_f64.len());
    let (popped, remainder) = sample_rx.pop_partial_slice(&mut pull_f64[..wanted_samples]);
    let popped_frames = popped.len() / src_channels;

    if popped_frames < frames_wanted && !*eos_pending {
        let _ = shared.xrun_count.fetch_add(1, Ordering::Relaxed);
    }
    if !remainder.is_empty() {
        remainder.fill(0.0);
    }

    let mut remaining_frames = popped_frames;
    while remaining_frames > 0 || current_marker.as_ref().is_some_and(|m| m.frames == 0) {
        if current_marker.is_none() {
            *current_marker = pending_markers.pop_front();
            *current_marker_done = 0;
            if let Some(m) = current_marker.as_mut() {
                if let Some(meta) = m.switch_meta.take() {
                    let _ = notice_tx.try_send(RenderNotice::TrackBecameAudible(meta));
                }
                if m.is_eos && m.frames == 0 {
                    *eos_pending = true;
                    *current_marker = None;
                    break;
                }
            } else {
                break;
            }
        }

        let Some(marker) = current_marker.as_ref() else {
            break;
        };
        if marker.frames == 0 {
            if marker.is_eos {
                *eos_pending = true;
            }
            *current_marker = None;
            continue;
        }

        let left_in_marker = marker.frames.saturating_sub(*current_marker_done);
        let consume_here = remaining_frames.min(left_in_marker);
        *current_marker_done += consume_here;
        remaining_frames -= consume_here;

        let span = marker.end_ms.saturating_sub(marker.start_ms);
        let pos_ms = marker.start_ms
            + ((u128::from(span) * (*current_marker_done as u128)) / (marker.frames as u128))
                as u64;
        shared.position_ms.store(pos_ms, Ordering::Release);

        if *current_marker_done >= marker.frames {
            if marker.is_eos {
                *eos_pending = true;
            }
            *current_marker = None;
            *current_marker_done = 0;
        }
    }

    // Check trailing zero-frame EOS marker right after consuming final audio frames.
    if current_marker.is_none()
        && let Some(front) = pending_markers.front()
        && front.frames == 0
        && front.is_eos
        && let Some(eos_marker) = pending_markers.pop_front()
    {
        if let Some(meta) = eos_marker.switch_meta {
            let _ = notice_tx.try_send(RenderNotice::TrackBecameAudible(meta));
        }
        *eos_pending = true;
    }

    popped_frames
}

#[cfg(windows)]
#[expect(
    clippy::too_many_arguments,
    reason = "real-time zero-alloc WASAPI render loop operates strictly on caller pre-allocated buffers"
)]
fn fill_and_write_wasapi(
    stream: &ActiveWasapiStream,
    dsp_store: &DspConfigStore,
    dsp_engine: &mut BiquadDspEngine,
    dsp_bypassed: bool,
    src_channels: usize,
    volume: f64,
    sample_rx: &mut Consumer<f64>,
    marker_rx: &mut Consumer<SegmentMarker>,
    pending_markers: &mut VecDeque<SegmentMarker>,
    current_marker: &mut Option<SegmentMarker>,
    current_marker_done: &mut usize,
    eos_pending: &mut bool,
    pull_f64: &mut [f64],
    dsp_f32: &mut [f32],
    pcm_bytes: &mut [u8],
    shared: &Arc<SharedState>,
    notice_tx: &Sender<RenderNotice>,
    frames_to_write: usize,
) -> Result<(), String> {
    let n_frames = frames_to_write.min(stream.max_buffer_frames);
    if n_frames == 0 {
        return Ok(());
    }

    let _ = advance_markers_and_consume(
        sample_rx,
        marker_rx,
        pending_markers,
        current_marker,
        current_marker_done,
        eos_pending,
        src_channels,
        n_frames,
        pull_f64,
        shared,
        notice_tx,
    );

    let active_samples = n_frames * src_channels;
    if !stream.is_exclusive && !dsp_bypassed {
        dsp_engine.sync_from_store(dsp_store);
        if dsp_engine.is_processing_active() {
            for (dst, &src) in dsp_f32[..active_samples]
                .iter_mut()
                .zip(&pull_f64[..active_samples])
            {
                *dst = src as f32;
            }
            dsp_engine.process_interleaved_f32(&mut dsp_f32[..active_samples]);
            for (dst, &src) in pull_f64[..active_samples]
                .iter_mut()
                .zip(&dsp_f32[..active_samples])
            {
                *dst = f64::from(src);
            }
        }
    }

    // Perceptual volume curve below unity; exact identity 1.0 when max volume to stay bit-exact.
    let linear_gain = if volume >= 0.999 {
        1.0
    } else {
        volume * volume
    };

    let byte_len = n_frames * stream.block_align;
    pack_frames_to_pcm_bytes(
        &pull_f64[..active_samples],
        src_channels,
        stream.channels,
        stream.encoding,
        linear_gain,
        !stream.is_exclusive,
        &mut pcm_bytes[..byte_len],
    );

    stream
        .render_client
        .write_to_device(&pcm_bytes[..byte_len], None)
        .map_err(|err| err.to_string())
}
