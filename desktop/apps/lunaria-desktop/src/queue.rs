//! Playback queue, deterministic shuffle ordering, repeat modes, reorder, and persistence.

use lunaria_model::{MAX_PERSISTED_QUEUE_SIZE, PersistedQueueState, RepeatMode, Track, TrackId};

/// Manages ordered tracks, active cursor, shuffle permutation, repeat behavior, and persistence.
#[derive(Debug, Clone, Default)]
pub struct PlaybackQueue {
    tracks: Vec<Track>,
    current_index: Option<usize>,
    repeat_mode: RepeatMode,
    shuffle_enabled: bool,
    play_order: Vec<usize>,
    order_cursor: Option<usize>,
}

impl PlaybackQueue {
    /// Replaces the entire queue with `tracks` and positions playback at `start_index`.
    pub fn set_queue(&mut self, tracks: Vec<Track>, start_index: usize, shuffle_seed: u64) {
        self.tracks = tracks;
        if self.tracks.is_empty() {
            self.current_index = None;
            self.play_order.clear();
            self.order_cursor = None;
            return;
        }
        let clamped_start = start_index.min(self.tracks.len() - 1);
        self.current_index = Some(clamped_start);
        self.rebuild_order(shuffle_seed);
    }

    /// Returns all tracks currently in display queue order.
    #[must_use]
    pub fn tracks(&self) -> &[Track] {
        &self.tracks
    }

    /// Returns current active display index, if any.
    #[must_use]
    pub const fn current_index(&self) -> Option<usize> {
        self.current_index
    }

    /// Returns current active track reference, if any.
    #[must_use]
    pub fn current_track(&self) -> Option<&Track> {
        self.current_index.and_then(|idx| self.tracks.get(idx))
    }

    /// Returns current repeat policy.
    #[must_use]
    pub const fn repeat_mode(&self) -> RepeatMode {
        self.repeat_mode
    }

    /// Sets repeat mode explicitly.
    #[cfg(test)]
    pub fn set_repeat_mode(&mut self, mode: RepeatMode) {
        self.repeat_mode = mode;
    }

    /// Cycles repeat mode (`Off -> All -> One -> Off`) and returns the new mode.
    pub fn cycle_repeat(&mut self) -> RepeatMode {
        self.repeat_mode = self.repeat_mode.next();
        self.repeat_mode
    }

    /// Returns `true` when shuffle order is enabled.
    #[must_use]
    pub const fn shuffle_enabled(&self) -> bool {
        self.shuffle_enabled
    }

    /// Toggles shuffle while keeping the currently active track uninterrupted.
    pub fn set_shuffle(&mut self, enabled: bool, seed: u64) {
        if self.shuffle_enabled == enabled {
            return;
        }
        self.shuffle_enabled = enabled;
        self.rebuild_order(seed);
    }

    /// Peeks the next track that will play naturally (used for gapless pre-open / crossfade).
    #[must_use]
    pub fn peek_next_track(&self) -> Option<&Track> {
        if self.tracks.is_empty() {
            return None;
        }
        if self.repeat_mode == RepeatMode::One {
            return self.current_track();
        }
        let next_cursor = self.next_order_cursor()?;
        self.play_order
            .get(next_cursor)
            .and_then(|&idx| self.tracks.get(idx))
    }

    /// Advances to the next track according to repeat & shuffle rules.
    pub fn advance_next(&mut self, natural_completion: bool) -> Option<&Track> {
        if self.tracks.is_empty() {
            return None;
        }
        if natural_completion && self.repeat_mode == RepeatMode::One {
            return self.current_track();
        }
        let next_cursor = self.next_order_cursor_for_manual(!natural_completion)?;
        self.order_cursor = Some(next_cursor);
        self.current_index = self.play_order.get(next_cursor).copied();
        self.current_track()
    }

    /// Steps backward: restarts the active track if `position_ms > 3_000`, otherwise moves to previous.
    pub fn step_previous(&mut self, position_ms: u64) -> Option<(&Track, bool)> {
        if self.tracks.is_empty() {
            return None;
        }
        if position_ms > 3_000 {
            return self.current_track().map(|t| (t, true));
        }
        let prev_cursor = match self.order_cursor {
            Some(cur) if cur > 0 => cur - 1,
            Some(_) => {
                if self.repeat_mode == RepeatMode::All {
                    self.play_order.len().saturating_sub(1)
                } else {
                    return self.current_track().map(|t| (t, true));
                }
            }
            None => 0,
        };
        self.order_cursor = Some(prev_cursor);
        self.current_index = self.play_order.get(prev_cursor).copied();
        self.current_track().map(|t| (t, false))
    }

    /// Jumps directly to queue display index `queue_idx`.
    pub fn jump_to_index(&mut self, queue_idx: usize) -> Option<&Track> {
        if queue_idx >= self.tracks.len() {
            return None;
        }
        self.current_index = Some(queue_idx);
        self.sync_cursor_to_current();
        self.current_track()
    }

    /// Inserts `track` immediately after the active track (`Play Next`).
    pub fn enqueue_next(&mut self, track: Track, shuffle_seed: u64) {
        if self.tracks.is_empty() {
            self.set_queue(vec![track], 0, shuffle_seed);
            return;
        }
        let insert_at = self
            .current_index
            .map_or(self.tracks.len(), |i| (i + 1).min(self.tracks.len()));
        self.tracks.insert(insert_at, track);
        if let Some(cur) = self.current_index
            && insert_at <= cur
        {
            self.current_index = Some(cur + 1);
        }
        self.rebuild_order(shuffle_seed);
    }

    /// Moves a queue item from `from` to `to`, keeping the currently active track aligned.
    pub fn move_item(&mut self, from: usize, to: usize, shuffle_seed: u64) -> bool {
        if from >= self.tracks.len() || to >= self.tracks.len() || from == to {
            return false;
        }
        let active_id = self.current_track().map(|t| t.id);
        let item = self.tracks.remove(from);
        self.tracks.insert(to, item);

        if let Some(cur) = self.current_index {
            self.current_index = if cur == from {
                Some(to)
            } else if from < cur && to >= cur {
                Some(cur - 1)
            } else if from > cur && to <= cur {
                Some(cur + 1)
            } else {
                active_id.and_then(|id| self.tracks.iter().position(|t| t.id == id))
            };
        }
        self.rebuild_order(shuffle_seed);
        true
    }

    /// Removes item at `queue_idx`, shifting active index cleanly. Returns `true` if active track changed.
    pub fn remove_item(&mut self, queue_idx: usize, shuffle_seed: u64) -> Option<bool> {
        if queue_idx >= self.tracks.len() {
            return None;
        }
        let removed_active = self.current_index == Some(queue_idx);
        let _removed = self.tracks.remove(queue_idx);

        if self.tracks.is_empty() {
            self.current_index = None;
            self.play_order.clear();
            self.order_cursor = None;
            return Some(removed_active);
        }

        if let Some(cur) = self.current_index {
            if queue_idx < cur {
                self.current_index = Some(cur - 1);
            } else if removed_active {
                self.current_index = Some(queue_idx.min(self.tracks.len() - 1));
            }
        }
        self.rebuild_order(shuffle_seed);
        Some(removed_active)
    }

    /// Clears the entire queue.
    pub fn clear(&mut self) {
        self.tracks.clear();
        self.current_index = None;
        self.play_order.clear();
        self.order_cursor = None;
    }

    /// Updates matching tracks inside the queue in place after tag edits or favorite toggles.
    pub fn update_track_in_place(&mut self, updated: &Track) -> bool {
        let mut changed = false;
        for slot in &mut self.tracks {
            if slot.id == updated.id {
                slot.clone_from(updated);
                changed = true;
            }
        }
        changed
    }

    /// Synchronizes queue track favorite flag by ID.
    pub fn set_favorite_by_id(&mut self, track_id: TrackId, is_favorite: bool) {
        for slot in &mut self.tracks {
            if slot.id == track_id {
                slot.is_favorite = is_favorite;
            }
        }
    }

    /// Builds a [`PersistedQueueState`] capped at [`MAX_PERSISTED_QUEUE_SIZE`] for SQLite persistence.
    #[must_use]
    pub fn to_persisted(&self, position_ms: u64, speed: f32) -> PersistedQueueState {
        let track_ids: Vec<TrackId> = self
            .tracks
            .iter()
            .take(MAX_PERSISTED_QUEUE_SIZE)
            .map(|t| t.id)
            .collect();
        let current_index = if track_ids.is_empty() {
            0
        } else {
            self.current_index
                .unwrap_or(0)
                .min(track_ids.len().saturating_sub(1))
        };
        PersistedQueueState {
            track_ids,
            current_index,
            position_ms,
            repeat_mode: self.repeat_mode,
            shuffle_enabled: self.shuffle_enabled,
            speed,
        }
    }

    /// Restores queue tracks resolved from SQLite alongside repeat & shuffle mode.
    pub fn restore_from_persisted(
        &mut self,
        state: &PersistedQueueState,
        resolved_tracks: Vec<Track>,
        shuffle_seed: u64,
    ) {
        self.repeat_mode = state.repeat_mode;
        self.shuffle_enabled = state.shuffle_enabled;
        if resolved_tracks.is_empty() {
            self.clear();
            return;
        }
        let start_idx = state.current_index.min(resolved_tracks.len() - 1);
        self.tracks = resolved_tracks;
        self.current_index = Some(start_idx);
        self.rebuild_order(shuffle_seed);
    }

    fn next_order_cursor(&self) -> Option<usize> {
        self.next_order_cursor_for_manual(false)
    }

    fn next_order_cursor_for_manual(&self, allow_loop_single: bool) -> Option<usize> {
        let len = self.play_order.len();
        if len == 0 {
            return None;
        }
        let cur = self.order_cursor.unwrap_or(0);
        if cur + 1 < len {
            Some(cur + 1)
        } else if self.repeat_mode == RepeatMode::All
            || (allow_loop_single && self.repeat_mode == RepeatMode::One)
        {
            Some(0)
        } else {
            None
        }
    }

    fn rebuild_order(&mut self, seed: u64) {
        let len = self.tracks.len();
        if len == 0 {
            self.play_order.clear();
            self.order_cursor = None;
            return;
        }

        if !self.shuffle_enabled {
            self.play_order = (0..len).collect();
            self.order_cursor = self.current_index.map(|i| i.min(len - 1));
            return;
        }

        let active = self.current_index.unwrap_or(0).min(len - 1);
        let mut remaining: Vec<usize> = (0..len).filter(|&i| i != active).collect();
        // Deterministic SplitMix64 Fisher-Yates shuffle without external RNG dependencies.
        let mut rng_state = seed ^ (len as u64).wrapping_mul(0x9E37_79B9_7F4A_7C15);
        for i in (1..remaining.len()).rev() {
            rng_state = splitmix64(rng_state);
            let j = (rng_state as usize) % (i + 1);
            remaining.swap(i, j);
        }

        let mut order = Vec::with_capacity(len);
        order.push(active);
        order.extend(remaining);
        self.play_order = order;
        self.order_cursor = Some(0);
    }

    fn sync_cursor_to_current(&mut self) {
        if let Some(cur) = self.current_index {
            self.order_cursor = self.play_order.iter().position(|&idx| idx == cur);
            if self.order_cursor.is_none() && !self.play_order.is_empty() {
                self.order_cursor = Some(0);
            }
        } else {
            self.order_cursor = None;
        }
    }
}

const fn splitmix64(mut x: u64) -> u64 {
    x = x.wrapping_add(0x9E37_79B9_7F4A_7C15);
    let mut z = x;
    z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
    z ^ (z >> 31)
}

#[cfg(test)]
mod tests {
    use std::error::Error;

    use lunaria_model::{AlbumId, AudioSpec};

    use super::*;

    type TestResult<T = ()> = Result<T, Box<dyn Error>>;

    fn dummy_track(id: i64, title: &str) -> Track {
        Track {
            id: TrackId(id),
            file_path: format!("D:/Music/{title}.flac"),
            title: title.to_owned(),
            artist: "Artist".to_owned(),
            album: "Album".to_owned(),
            album_artist: "Artist".to_owned(),
            album_id: AlbumId(1),
            duration_ms: 180_000,
            track_number: id.max(1) as u32,
            disc_number: 1,
            year: 2025,
            genre: "Jazz".to_owned(),
            folder_path: "D:/Music".to_owned(),
            file_name: format!("{title}.flac"),
            mime_type: "audio/flac".to_owned(),
            audio_spec: AudioSpec {
                codec: "FLAC".to_owned(),
                bit_depth: 24,
                sample_rate_hz: 96_000,
                bitrate_kbps: 3_000,
                channel_count: 2,
                is_lossless: true,
            },
            artwork_uri: None,
            artwork_hash: None,
            seed_color_argb: None,
            file_size: 10_000,
            modified_ns: 1,
            is_favorite: false,
            date_added_seconds: 1,
            replay_gain_track_db: None,
            replay_gain_album_db: None,
            replay_gain_track_peak: None,
            replay_gain_album_peak: None,
            clip_start_ms: None,
            clip_end_ms: None,
            cue_source_path: None,
            play_count: 0,
            last_played_at_ms: 0,
            total_play_duration_ms: 0,
        }
    }

    #[test]
    fn queue_next_prev_repeat_and_reorder_behave_accurately() -> TestResult {
        let mut q = PlaybackQueue::default();
        q.set_queue(
            vec![
                dummy_track(1, "One"),
                dummy_track(2, "Two"),
                dummy_track(3, "Three"),
            ],
            0,
            42,
        );
        assert_eq!(q.current_track().map(|t| t.id), Some(TrackId(1)));
        assert_eq!(q.peek_next_track().map(|t| t.id), Some(TrackId(2)));

        let next = q.advance_next(true).ok_or("expected track 2")?;
        assert_eq!(next.id, TrackId(2));

        // > 3s restarts active track instead of jumping back
        let (restarted, restart_only) = q.step_previous(4_500).ok_or("expected restart")?;
        assert!(restart_only);
        assert_eq!(restarted.id, TrackId(2));

        let (prev, restart_flag) = q.step_previous(500).ok_or("expected track 1")?;
        assert!(!restart_flag);
        assert_eq!(prev.id, TrackId(1));

        // Reorder moving active index 0 down to index 2 tracks active ID cleanly
        assert!(q.move_item(0, 2, 42));
        assert_eq!(q.current_index(), Some(2));
        assert_eq!(q.current_track().map(|t| t.id), Some(TrackId(1)));

        // RepeatMode::One loops current track on natural completion
        q.set_repeat_mode(RepeatMode::One);
        assert_eq!(q.peek_next_track().map(|t| t.id), Some(TrackId(1)));
        let repeated = q.advance_next(true).ok_or("expected loop track")?;
        assert_eq!(repeated.id, TrackId(1));

        Ok(())
    }

    #[test]
    fn shuffle_preserves_active_track_and_persists_round_trip() -> TestResult {
        let mut q = PlaybackQueue::default();
        let tracks: Vec<Track> = (1..=8).map(|i| dummy_track(i, &format!("T{i}"))).collect();
        q.set_queue(tracks.clone(), 4, 99);
        assert_eq!(q.current_track().map(|t| t.id), Some(TrackId(5)));

        q.set_shuffle(true, 123_456);
        assert!(q.shuffle_enabled());
        assert_eq!(q.current_track().map(|t| t.id), Some(TrackId(5)));

        let persisted = q.to_persisted(12_345, 1.25);
        assert_eq!(persisted.current_index, 4);
        assert!(persisted.shuffle_enabled);

        let mut restored = PlaybackQueue::default();
        restored.restore_from_persisted(&persisted, tracks, 123_456);
        assert_eq!(restored.current_track().map(|t| t.id), Some(TrackId(5)));
        assert!(restored.shuffle_enabled());

        Ok(())
    }
}
