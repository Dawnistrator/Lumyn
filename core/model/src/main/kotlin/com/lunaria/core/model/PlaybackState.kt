package com.lunaria.core.model

enum class RepeatMode {
    OFF,
    ALL,
    ONE,
    ;

    fun next(): RepeatMode = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> OFF
    }
}

sealed interface SleepTimerState {
    data object Off : SleepTimerState
    data object EndOfTrack : SleepTimerState
    data class Countdown(val remainingMs: Long) : SleepTimerState
}

fun clampPlaybackSpeed(speed: Float): Float = speed.coerceIn(0.5f, 2.0f)

data class PlaybackState(
    val currentTrack: Track? = null,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
    val speed: Float = 1.0f,
    val sleepTimer: SleepTimerState = SleepTimerState.Off,
    val errorMessage: String? = null,
    val signalPath: AudioSignalPath? = null,
) {
    /** Progress in 0..1, safe against unknown duration. */
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    val hasNext: Boolean
        get() = queue.isNotEmpty() && (repeatMode == RepeatMode.ALL || currentIndex in 0 until queue.lastIndex)

    val hasPrevious: Boolean
        get() = queue.isNotEmpty() && (repeatMode == RepeatMode.ALL || currentIndex > 0 || positionMs > 0L)
}
