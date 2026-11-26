package com.lumyn.feature.player

import com.lumyn.core.model.AudioOutputMode
import com.lumyn.core.model.Track

/** Callbacks shared across Mini-Player, Full Now Playing, Queue Sheet and Supporting Pane. */
data class PlayerActions(
    val onPlayPause: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onSeek: (Long) -> Unit = {},
    val onToggleShuffle: () -> Unit = {},
    val onCycleRepeat: () -> Unit = {},
    val onToggleFavorite: (Track) -> Unit = {},
    val onSelectSpeed: (Float) -> Unit = {},
    val onSetSleepCountdown: (Long) -> Unit = {},
    val onSetSleepEndOfTrack: () -> Unit = {},
    val onCancelSleepTimer: () -> Unit = {},
    val onSkipToQueueIndex: (Int) -> Unit = {},
    val onMoveQueueItem: (Int, Int) -> Unit = { _, _ -> },
    val onRemoveQueueItem: (Int) -> Unit = {},
    val onSaveQueueAsPlaylist: (String) -> Unit = {},
    val onAddToPlaylist: (Track) -> Unit = {},
    val onSelectAudioOutputMode: (AudioOutputMode) -> Unit = {},
    val onOpenEqualizer: () -> Unit = {},
    val onEditTags: (Track) -> Unit = {},
    val onDismissError: () -> Unit = {},
)
