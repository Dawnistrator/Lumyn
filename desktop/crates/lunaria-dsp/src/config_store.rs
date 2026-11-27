//! Lock-free, real-time safe DSP configuration store (`ArcSwap` + `AtomicU64`).

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use arc_swap::ArcSwap;
use lunaria_model::EqualizerSettings;

static NEXT_STORE_ID: AtomicU64 = AtomicU64::new(1);

/// Immutable snapshot of active DSP parameters and current track ReplayGain metadata.
#[derive(Debug, Clone, PartialEq)]
pub struct DspConfigSnapshot {
    /// Monotonic version counter incremented on every update.
    pub version: u64,
    /// Active equalizer, ReplayGain, stereo balance, mono, and crossfade settings.
    pub settings: EqualizerSettings,
    /// When `true`, all DSP filters and gain stages are bypassed bit-accurately.
    pub bypassed: bool,
    /// Current track's `REPLAYGAIN_TRACK_GAIN` (dB).
    pub track_replay_gain_db: Option<f32>,
    /// Current track's `REPLAYGAIN_ALBUM_GAIN` (dB).
    pub album_replay_gain_db: Option<f32>,
    /// Current track's `REPLAYGAIN_TRACK_PEAK` linear peak.
    pub track_replay_gain_peak: Option<f32>,
    /// Current track's `REPLAYGAIN_ALBUM_PEAK` linear peak.
    pub album_replay_gain_peak: Option<f32>,
}

impl Default for DspConfigSnapshot {
    fn default() -> Self {
        Self {
            version: 1,
            settings: EqualizerSettings::default(),
            bypassed: false,
            track_replay_gain_db: None,
            album_replay_gain_db: None,
            track_replay_gain_peak: None,
            album_replay_gain_peak: None,
        }
    }
}

/// Application-scoped store read lock-free by the WASAPI audio render thread.
#[derive(Debug)]
pub struct DspConfigStore {
    store_id: u64,
    write_seq: Mutex<u64>,
    version_counter: AtomicU64,
    state: ArcSwap<DspConfigSnapshot>,
}

impl Default for DspConfigStore {
    fn default() -> Self {
        Self::new()
    }
}

impl DspConfigStore {
    /// Creates a new store initialized with flat default DSP settings (`version = 1`).
    #[must_use]
    pub fn new() -> Self {
        Self {
            store_id: NEXT_STORE_ID.fetch_add(1, Ordering::Relaxed),
            write_seq: Mutex::new(1),
            version_counter: AtomicU64::new(1),
            state: ArcSwap::from_pointee(DspConfigSnapshot::default()),
        }
    }

    /// Returns the unique instance identifier for this configuration store.
    #[must_use]
    pub const fn store_id(&self) -> u64 {
        self.store_id
    }

    /// Returns the current snapshot version using `Acquire` ordering for real-time polling.
    #[must_use]
    pub fn version(&self) -> u64 {
        self.version_counter.load(Ordering::Acquire)
    }

    /// Atomically loads the current [`DspConfigSnapshot`] without blocking.
    #[must_use]
    pub fn snapshot(&self) -> Arc<DspConfigSnapshot> {
        self.state.load_full()
    }

    /// Replaces the entire DSP configuration and track ReplayGain metadata.
    pub fn update_all(
        &self,
        settings: EqualizerSettings,
        bypassed: bool,
        track_replay_gain_db: Option<f32>,
        album_replay_gain_db: Option<f32>,
        track_replay_gain_peak: Option<f32>,
        album_replay_gain_peak: Option<f32>,
    ) {
        let Ok(mut seq) = self.write_seq.lock() else {
            return;
        };
        let next_version = *seq + 1;
        *seq = next_version;
        self.state.store(Arc::new(DspConfigSnapshot {
            version: next_version,
            settings,
            bypassed,
            track_replay_gain_db,
            album_replay_gain_db,
            track_replay_gain_peak,
            album_replay_gain_peak,
        }));
        self.version_counter.store(next_version, Ordering::Release);
    }

    /// Updates equalizer settings in-place from a closure while keeping track ReplayGain metadata.
    pub fn update_equalizer<F>(&self, mut transform: F)
    where
        F: FnMut(&EqualizerSettings) -> EqualizerSettings,
    {
        let Ok(mut seq) = self.write_seq.lock() else {
            return;
        };
        let prev = self.state.load_full();
        let next_version = *seq + 1;
        *seq = next_version;
        let updated_eq = transform(&prev.settings);
        self.state.store(Arc::new(DspConfigSnapshot {
            version: next_version,
            settings: updated_eq,
            bypassed: prev.bypassed,
            track_replay_gain_db: prev.track_replay_gain_db,
            album_replay_gain_db: prev.album_replay_gain_db,
            track_replay_gain_peak: prev.track_replay_gain_peak,
            album_replay_gain_peak: prev.album_replay_gain_peak,
        }));
        self.version_counter.store(next_version, Ordering::Release);
    }

    /// Updates the bypass flag without altering EQ or ReplayGain parameters.
    pub fn update_bypassed(&self, bypassed: bool) {
        let Ok(mut seq) = self.write_seq.lock() else {
            return;
        };
        let prev = self.state.load_full();
        if prev.bypassed == bypassed {
            return;
        }
        let next_version = *seq + 1;
        *seq = next_version;
        let mut next = (*prev).clone();
        next.version = next_version;
        next.bypassed = bypassed;
        self.state.store(Arc::new(next));
        self.version_counter.store(next_version, Ordering::Release);
    }
}
