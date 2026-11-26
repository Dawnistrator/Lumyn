package com.lumyn.core.audioengine

/**
 * Tracks active playback time on a track and determines when a full local play should be recorded
 * (either 30 seconds or half the track duration, whichever is shorter).
 */
class PlayHistoryTracker {

    private var activeTrackId: Long? = null
    private var activeDurationMs: Long = 0L
    private var accumulatedPlayMs: Long = 0L
    private var alreadyRecorded: Boolean = false

    fun onTrackChanged(trackId: Long?, durationMs: Long) {
        activeTrackId = trackId
        activeDurationMs = durationMs.coerceAtLeast(0L)
        accumulatedPlayMs = 0L
        alreadyRecorded = false
    }

    /**
     * Advances active playback duration by [deltaMs]. Returns the accumulated listening duration
     * once when the threshold is crossed for the current play session, or `null` otherwise.
     */
    fun onPlayTick(deltaMs: Long): Long? {
        val trackId = activeTrackId ?: return null
        if (trackId == 0L || deltaMs <= 0L || alreadyRecorded) return null
        accumulatedPlayMs += deltaMs
        val thresholdMs = computeThresholdMs(activeDurationMs)
        return if (accumulatedPlayMs >= thresholdMs) {
            alreadyRecorded = true
            accumulatedPlayMs
        } else {
            null
        }
    }

    fun resetCurrentSession() {
        accumulatedPlayMs = 0L
        alreadyRecorded = false
    }

    fun currentTrackId(): Long? = activeTrackId

    companion object {
        fun computeThresholdMs(durationMs: Long): Long {
            val safeDuration = durationMs.coerceAtLeast(0L)
            if (safeDuration <= 0L) return DEFAULT_THRESHOLD_MS
            val half = (safeDuration / 2L).coerceAtLeast(1_000L)
            return if (safeDuration < 10_000L) {
                half
            } else {
                minOf(DEFAULT_THRESHOLD_MS, maxOf(MIN_NORMAL_THRESHOLD_MS, half))
            }
        }

        private const val MIN_NORMAL_THRESHOLD_MS = 5_000L
        private const val DEFAULT_THRESHOLD_MS = 30_000L
    }
}
