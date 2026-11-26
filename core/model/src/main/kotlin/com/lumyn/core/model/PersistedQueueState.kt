package com.lumyn.core.model

/** Persisted playback queue and transport state restored cleanly on cold start or media resumption. */
data class PersistedQueueState(
    val trackIds: List<Long> = emptyList(),
    val currentIndex: Int = 0,
    val positionMs: Long = 0L,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
    val speed: Float = 1.0f,
) {
    companion object {
        const val MAX_PERSISTED_QUEUE_SIZE = 2_000
    }
}
