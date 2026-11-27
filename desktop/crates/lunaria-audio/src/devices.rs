//! Windows WASAPI endpoint discovery, MTA COM isolation, hot-plug notifications, and test chime.

use std::fmt;

use crossbeam_channel::Sender;
use lunaria_model::{AudioDeviceInfo, AudioOutputCategory};

use crate::AudioError;

/// Hot-plug notification emitted when Windows audio endpoints change.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DeviceHotPlugEvent {
    /// An endpoint was physically unplugged or removed.
    DeviceRemoved(String),
    /// An endpoint changed active availability state.
    DeviceStateChanged {
        /// Endpoint ID.
        id: String,
        /// Whether the endpoint is currently active.
        is_active: bool,
    },
    /// Windows default multimedia/console render device changed.
    DefaultRenderDeviceChanged(Option<String>),
}

/// Classifies an audio endpoint's transport category from its Windows friendly name.
#[must_use]
pub fn classify_device_category(name: &str) -> AudioOutputCategory {
    let lower = name.to_ascii_lowercase();
    if lower.contains("bluetooth")
        || lower.contains("a2dp")
        || lower.contains("hands-free")
        || lower.contains("airpods")
        || lower.contains("buds")
        || lower.contains("wh-")
    {
        AudioOutputCategory::BluetoothA2dp
    } else if lower.contains("usb")
        || lower.contains("dac")
        || lower.contains("fiio")
        || lower.contains("topping")
        || lower.contains("ifi")
        || lower.contains("schiit")
        || lower.contains("scarlett")
        || lower.contains("asio")
    {
        AudioOutputCategory::UsbDac
    } else if lower.contains("headphone")
        || lower.contains("headset")
        || lower.contains("earphone")
        || lower.contains("line out")
        || lower.contains("spdif")
        || lower.contains("optical")
    {
        AudioOutputCategory::Wired
    } else if lower.contains("speaker") || lower.contains("realtek") || lower.contains("display") {
        AudioOutputCategory::Speaker
    } else {
        AudioOutputCategory::Other
    }
}

/// RAII guard balancing `CoInitializeEx(COINIT_MULTITHREADED)` on non-UI worker threads.
#[cfg(windows)]
pub(crate) struct ComMtaGuard {
    initialized: bool,
}

#[cfg(windows)]
impl ComMtaGuard {
    /// Initializes COM Multi-Threaded Apartment (MTA) on the current non-UI thread.
    pub(crate) fn enter() -> Self {
        let hr = wasapi::initialize_mta();
        Self {
            initialized: hr.is_ok(),
        }
    }
}

#[cfg(windows)]
impl Drop for ComMtaGuard {
    fn drop(&mut self) {
        if self.initialized {
            wasapi::deinitialize();
        }
    }
}

/// Executes a closure inside an isolated COM MTA worker thread so STA UI threads never hit
/// `RPC_E_CHANGED_MODE`.
#[cfg(windows)]
pub(crate) fn run_on_mta_thread<T, F>(task: F) -> Result<T, AudioError>
where
    T: Send + 'static,
    F: FnOnce() -> Result<T, AudioError> + Send + 'static,
{
    let handle = std::thread::Builder::new()
        .name("lunaria-wasapi-mta".to_owned())
        .spawn(move || {
            let _com_guard = ComMtaGuard::enter();
            task()
        })
        .map_err(|err| AudioError::Wasapi(format!("failed to spawn wasapi worker: {err}")))?;

    handle
        .join()
        .map_err(|_| AudioError::Wasapi("wasapi worker thread panicked".to_owned()))?
}

/// Keeps Windows endpoint hot-plug callbacks registered until dropped.
pub(crate) struct HotPlugWatcher {
    #[cfg(windows)]
    _registration: wasapi::DeviceEventRegistration,
}

impl fmt::Debug for HotPlugWatcher {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("HotPlugWatcher").finish_non_exhaustive()
    }
}

impl HotPlugWatcher {
    /// Registers a non-blocking OS notification watcher on an already-initialized MTA thread.
    #[cfg(windows)]
    pub(crate) fn register(tx: Sender<DeviceHotPlugEvent>) -> Result<Self, AudioError> {
        use wasapi::{DeviceEnumerator, DeviceState, Direction, Role};

        let enumerator =
            DeviceEnumerator::new().map_err(|err| AudioError::Wasapi(err.to_string()))?;
        let mut callbacks = wasapi::DeviceEventCallbacks::new();

        let tx_removed = tx.clone();
        callbacks.set_device_removed_callback(move |id| {
            let _ = tx_removed.try_send(DeviceHotPlugEvent::DeviceRemoved(id));
        });

        let tx_state = tx.clone();
        callbacks.set_device_state_callback(move |id, state| {
            let _ = tx_state.try_send(DeviceHotPlugEvent::DeviceStateChanged {
                id,
                is_active: matches!(state, DeviceState::Active),
            });
        });

        callbacks.set_default_device_callback(move |direction, role, id| {
            if direction == Direction::Render && role == Role::Console {
                let _ = tx.try_send(DeviceHotPlugEvent::DefaultRenderDeviceChanged(id));
            }
        });

        let registration = enumerator
            .register_notification_callback(callbacks)
            .map_err(|err| AudioError::Wasapi(err.to_string()))?;

        Ok(Self {
            _registration: registration,
        })
    }

    /// Non-Windows stub returning an inactive watcher.
    #[cfg(not(windows))]
    pub(crate) fn register(_tx: Sender<DeviceHotPlugEvent>) -> Result<Self, AudioError> {
        Ok(Self {})
    }
}

/// Enumerates active Windows WASAPI render endpoints and identifies the default output device.
#[cfg(windows)]
pub fn enumerate_render_devices() -> Result<Vec<AudioDeviceInfo>, AudioError> {
    run_on_mta_thread(|| {
        use wasapi::{DeviceEnumerator, DeviceState, Direction};

        let enumerator =
            DeviceEnumerator::new().map_err(|err| AudioError::Wasapi(err.to_string()))?;
        let default_id = enumerator
            .get_default_device(Direction::Render)
            .ok()
            .and_then(|dev| dev.get_id().ok());

        let collection = enumerator
            .get_device_collection(Direction::Render)
            .map_err(|err| AudioError::Wasapi(err.to_string()))?;

        let mut devices = Vec::new();
        for entry in &collection {
            let dev = match entry {
                Ok(dev) => dev,
                Err(err) => {
                    tracing::warn!(error = %err, "skipping unreadable wasapi device entry");
                    continue;
                }
            };

            if !matches!(dev.get_state(), Ok(DeviceState::Active)) {
                continue;
            }

            let id = dev.get_id().unwrap_or_default();
            let name = dev
                .get_friendlyname()
                .unwrap_or_else(|_| "Windows Audio Endpoint".to_owned());
            let category = classify_device_category(&name);

            let (sample_rate, bit_depth, channels) = dev
                .get_iaudioclient()
                .and_then(|client| client.get_mixformat())
                .map_or((48_000, 24, 2), |fmt| {
                    let valid = fmt.get_validbitspersample();
                    let bits = if valid > 0 {
                        valid
                    } else {
                        fmt.get_bitspersample()
                    };
                    (fmt.get_samplespersec(), bits, fmt.get_nchannels())
                });

            let is_default = default_id.as_deref().is_some_and(|def| def == id);
            devices.push(AudioDeviceInfo {
                id,
                name,
                is_default,
                category,
                default_sample_rate_hz: sample_rate,
                default_bit_depth: bit_depth,
                default_channels: channels,
            });
        }

        devices.sort_by(|a, b| {
            b.is_default
                .cmp(&a.is_default)
                .then_with(|| a.name.cmp(&b.name))
        });
        Ok(devices)
    })
}

/// Non-Windows stub returning an empty endpoint list for cross-platform compilation checks.
#[cfg(not(windows))]
pub fn enumerate_render_devices() -> Result<Vec<AudioDeviceInfo>, AudioError> {
    Ok(Vec::new())
}

/// Plays a gentle, click-free 360 ms stereo studio chime via Windows WASAPI Shared event-driven mode.
#[cfg(windows)]
pub fn play_test_chime(device_id: Option<&str>) -> Result<AudioDeviceInfo, AudioError> {
    let owned_id = device_id.map(str::to_owned);
    run_on_mta_thread(move || {
        use std::f64::consts::PI;
        use wasapi::{DeviceEnumerator, Direction, SampleType, StreamMode, WaveFormat};

        let enumerator =
            DeviceEnumerator::new().map_err(|err| AudioError::Wasapi(err.to_string()))?;
        let device = match owned_id.as_deref().filter(|id| !id.is_empty()) {
            Some(id) => enumerator
                .get_device(id)
                .map_err(|err| AudioError::Wasapi(err.to_string()))?,
            None => enumerator
                .get_default_device(Direction::Render)
                .map_err(|err| AudioError::Wasapi(err.to_string()))?,
        };

        let resolved_id = device.get_id().unwrap_or_default();
        let resolved_name = device
            .get_friendlyname()
            .unwrap_or_else(|_| "Windows Audio Endpoint".to_owned());

        let mut audio_client = device
            .get_iaudioclient()
            .map_err(|err| AudioError::Wasapi(err.to_string()))?;
        let mix_fmt = audio_client
            .get_mixformat()
            .map_err(|err| AudioError::Wasapi(err.to_string()))?;
        let sample_rate = mix_fmt.get_samplespersec().max(44_100);
        let channels = mix_fmt.get_nchannels().max(1);

        let desired_format = WaveFormat::new(
            32,
            32,
            SampleType::Float,
            sample_rate as usize,
            channels as usize,
            None,
        );
        let block_align = desired_format.get_blockalign() as usize;

        let (default_period, _) = audio_client
            .get_device_period()
            .map_err(|err| AudioError::Wasapi(err.to_string()))?;
        let stream_mode = StreamMode::EventsShared {
            autoconvert: true,
            buffer_duration_hns: default_period.max(100_000),
        };

        audio_client
            .initialize_client(&desired_format, Direction::Render, stream_mode, None)
            .map_err(|err| AudioError::Wasapi(err.to_string()))?;

        let event_handle = audio_client
            .set_get_eventhandle()
            .map_err(|err| AudioError::Wasapi(err.to_string()))?;
        let render_client = audio_client
            .get_audiorenderclient()
            .map_err(|err| AudioError::Wasapi(err.to_string()))?;

        let total_frames = ((f64::from(sample_rate)) * 0.36) as usize;
        let fade_frames = ((f64::from(sample_rate)) * 0.03) as usize;
        let mut rendered_frames = 0usize;
        let mut scratch = Vec::new();

        let mut fill_and_write = |start_frame: usize,
                                  frames_to_write: usize|
         -> Result<usize, AudioError> {
            if frames_to_write == 0 {
                return Ok(start_frame);
            }
            let byte_len = frames_to_write * block_align;
            scratch.resize(byte_len, 0u8);

            for (frame_offset, frame_bytes) in scratch.chunks_exact_mut(block_align).enumerate() {
                let idx = start_frame + frame_offset;
                let sample = if idx < total_frames {
                    let t = idx as f64 / f64::from(sample_rate);
                    let env_in = if idx < fade_frames {
                        idx as f64 / fade_frames.max(1) as f64
                    } else {
                        1.0
                    };
                    let rem = total_frames.saturating_sub(idx);
                    let env_out = if rem < fade_frames {
                        rem as f64 / fade_frames.max(1) as f64
                    } else {
                        1.0
                    };
                    let tone =
                        0.7 * (2.0 * PI * 440.0 * t).sin() + 0.3 * (2.0 * PI * 659.25 * t).sin();
                    lunaria_dsp::soft_limit(tone * 0.16 * env_in * env_out) as f32
                } else {
                    0.0_f32
                };

                let le = sample.to_le_bytes();
                let (chunks, _) = frame_bytes.as_chunks_mut::<4>();
                for ch_bytes in chunks {
                    ch_bytes.copy_from_slice(&le);
                }
            }

            render_client
                .write_to_device(&scratch, None)
                .map_err(|err| AudioError::Wasapi(err.to_string()))?;
            Ok(start_frame.saturating_add(frames_to_write))
        };

        let initial_frames = audio_client
            .get_available_space_in_frames()
            .map_err(|err| AudioError::Wasapi(err.to_string()))?
            as usize;
        rendered_frames = fill_and_write(rendered_frames, initial_frames)?;

        audio_client
            .start_stream()
            .map_err(|err| AudioError::Wasapi(err.to_string()))?;

        while rendered_frames < total_frames {
            event_handle
                .wait_for_event(500)
                .map_err(|err| AudioError::Wasapi(err.to_string()))?;
            let avail = audio_client
                .get_available_space_in_frames()
                .map_err(|err| AudioError::Wasapi(err.to_string()))?
                as usize;
            rendered_frames = fill_and_write(rendered_frames, avail)?;
        }

        std::thread::sleep(std::time::Duration::from_millis(45));
        audio_client
            .stop_stream()
            .map_err(|err| AudioError::Wasapi(err.to_string()))?;

        Ok(AudioDeviceInfo {
            id: resolved_id,
            category: classify_device_category(&resolved_name),
            name: resolved_name,
            is_default: owned_id.is_none(),
            default_sample_rate_hz: sample_rate,
            default_bit_depth: 32,
            default_channels: channels,
        })
    })
}

/// Non-Windows stub returning an unsupported error.
#[cfg(not(windows))]
pub fn play_test_chime(_device_id: Option<&str>) -> Result<AudioDeviceInfo, AudioError> {
    Err(AudioError::Wasapi(
        "wasapi playback requires windows".to_owned(),
    ))
}
