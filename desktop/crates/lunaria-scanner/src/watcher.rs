//! Debounced filesystem folder watcher built on `notify` (`ReadDirectoryChangesW` on Windows).

use std::collections::HashSet;
use std::fmt;
use std::path::{Path, PathBuf};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use crossbeam_channel::{Receiver, RecvTimeoutError, Sender, unbounded};
use notify::{Event, EventKind, RecommendedWatcher, RecursiveMode, Watcher};

use crate::ScannerError;

/// Default quiet period used to coalesce bursty Windows Explorer / tagger file modifications.
pub const DEFAULT_WATCH_DEBOUNCE: Duration = Duration::from_millis(350);

/// Maximum wait time before flushing a sustained continuous stream of folder events.
const MAX_COALESCE_WINDOW: Duration = Duration::from_millis(1_500);

/// Debounced batch of filesystem paths changed within watched music folders.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct WatchBatch {
    /// Created, modified, or renamed-to file/directory paths to incrementally scan.
    pub upsert_paths: Vec<PathBuf>,
    /// Deleted or renamed-from paths to remove from SQLite.
    pub remove_paths: Vec<PathBuf>,
    /// Changed or created `.cue` sheet paths requiring CUE virtual track re-indexing.
    pub cue_paths: Vec<PathBuf>,
}

impl WatchBatch {
    /// Returns `true` if no changed paths were collected.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.upsert_paths.is_empty() && self.remove_paths.is_empty() && self.cue_paths.is_empty()
    }
}

/// Live OS directory watcher producing coalesced [`WatchBatch`] notifications.
pub struct LibraryWatcher {
    watcher: Option<RecommendedWatcher>,
    watched_roots: HashSet<PathBuf>,
    events_rx: Receiver<WatchBatch>,
    debounce_thread: Option<JoinHandle<()>>,
}

impl fmt::Debug for LibraryWatcher {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("LibraryWatcher")
            .field("watched_roots_count", &self.watched_roots.len())
            .finish_non_exhaustive()
    }
}

impl LibraryWatcher {
    /// Starts a live folder watcher using the provided `debounce` window (e.g. `350 ms`).
    pub fn new(debounce: Duration) -> Result<Self, ScannerError> {
        let effective_debounce = debounce.max(Duration::from_millis(25));
        let (raw_tx, raw_rx) = unbounded::<notify::Result<Event>>();
        let (batch_tx, events_rx) = unbounded::<WatchBatch>();

        let watcher = notify::recommended_watcher(move |res| {
            let _ = raw_tx.send(res);
        })?;

        let debounce_thread = thread::Builder::new()
            .name("lunaria-folder-watcher".to_owned())
            .spawn(move || run_debounce_loop(&raw_rx, &batch_tx, effective_debounce))?;

        Ok(Self {
            watcher: Some(watcher),
            watched_roots: HashSet::new(),
            events_rx,
            debounce_thread: Some(debounce_thread),
        })
    }

    /// Starts recursive watching on `folder`. Repeated calls for the same path are no-ops.
    pub fn watch_folder(&mut self, folder: &Path) -> Result<(), ScannerError> {
        if !folder.exists() {
            return Err(ScannerError::InvalidInput(format!(
                "watched folder does not exist: {}",
                folder.display()
            )));
        }
        let canonical = folder.to_path_buf();
        if self.watched_roots.contains(&canonical) {
            return Ok(());
        }
        if let Some(watcher) = self.watcher.as_mut() {
            watcher.watch(&canonical, RecursiveMode::Recursive)?;
            let _ = self.watched_roots.insert(canonical);
        }
        Ok(())
    }

    /// Stops watching `folder` if previously registered.
    pub fn unwatch_folder(&mut self, folder: &Path) -> Result<(), ScannerError> {
        let key = folder.to_path_buf();
        if self.watched_roots.remove(&key)
            && let Some(watcher) = self.watcher.as_mut()
        {
            watcher.unwatch(&key)?;
        }
        Ok(())
    }

    /// Returns a cloneable receiver yielding debounced [`WatchBatch`] updates.
    #[must_use]
    pub fn receiver(&self) -> &Receiver<WatchBatch> {
        &self.events_rx
    }
}

impl Drop for LibraryWatcher {
    fn drop(&mut self) {
        // Dropping the OS watcher closes its event callback channel (`raw_tx`), cleanly ending
        // the debouncer thread before we join it.
        drop(self.watcher.take());
        if let Some(handle) = self.debounce_thread.take() {
            let _ = handle.join();
        }
    }
}

fn run_debounce_loop(
    raw_rx: &Receiver<notify::Result<Event>>,
    batch_tx: &Sender<WatchBatch>,
    debounce: Duration,
) {
    let mut pending = PendingSet::default();

    while let Ok(first_res) = raw_rx.recv() {
        if let Ok(event) = first_res {
            pending.ingest(&event);
        }

        let burst_start = Instant::now();
        while burst_start.elapsed() < MAX_COALESCE_WINDOW {
            match raw_rx.recv_timeout(debounce) {
                Ok(Ok(event)) => pending.ingest(&event),
                Ok(Err(_)) => {}
                Err(RecvTimeoutError::Timeout) => break,
                Err(RecvTimeoutError::Disconnected) => {
                    let batch = pending.take_batch();
                    if !batch.is_empty() {
                        let _ = batch_tx.send(batch);
                    }
                    return;
                }
            }
        }

        let batch = pending.take_batch();
        if !batch.is_empty() && batch_tx.send(batch).is_err() {
            break;
        }
    }
}

#[derive(Default)]
struct PendingSet {
    upserts: HashSet<PathBuf>,
    removes: HashSet<PathBuf>,
    cues: HashSet<PathBuf>,
}

impl PendingSet {
    fn ingest(&mut self, event: &Event) {
        let is_cue = |p: &Path| {
            p.extension()
                .and_then(|e| e.to_str())
                .is_some_and(|e| e.eq_ignore_ascii_case("cue"))
        };

        match event.kind {
            EventKind::Remove(_) => {
                for path in &event.paths {
                    let _ = self.upserts.remove(path);
                    let _ = self.cues.remove(path);
                    let _ = self.removes.insert(path.clone());
                }
            }
            EventKind::Create(_) | EventKind::Modify(_) => {
                for path in &event.paths {
                    if path.exists() {
                        let _ = self.removes.remove(path);
                        if is_cue(path) {
                            let _ = self.cues.insert(path.clone());
                        } else {
                            let _ = self.upserts.insert(path.clone());
                        }
                    } else {
                        let _ = self.upserts.remove(path);
                        let _ = self.cues.remove(path);
                        let _ = self.removes.insert(path.clone());
                    }
                }
            }
            _ => {}
        }
    }

    fn take_batch(&mut self) -> WatchBatch {
        let mut upsert_paths: Vec<PathBuf> = self.upserts.drain().collect();
        let mut remove_paths: Vec<PathBuf> = self.removes.drain().collect();
        let mut cue_paths: Vec<PathBuf> = self.cues.drain().collect();
        upsert_paths.sort();
        remove_paths.sort();
        cue_paths.sort();
        WatchBatch {
            upsert_paths,
            remove_paths,
            cue_paths,
        }
    }
}
