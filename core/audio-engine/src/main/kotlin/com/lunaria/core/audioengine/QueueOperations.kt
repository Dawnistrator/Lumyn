package com.lunaria.core.audioengine

/** Pure queue index calculations & sleep timer formatting, testable on the JVM. */
object QueueOperations {

    fun playNextInsertionIndex(currentIndex: Int, queueSize: Int): Int {
        if (queueSize <= 0 || currentIndex < 0) return 0
        return (currentIndex + 1).coerceAtMost(queueSize)
    }

    fun isValidMove(fromIndex: Int, toIndex: Int, size: Int): Boolean =
        fromIndex in 0 until size && toIndex in 0 until size && fromIndex != toIndex

    fun isValidRemove(index: Int, size: Int): Boolean = index in 0 until size

    fun formatSleepTimerRemaining(remainingMs: Long): String {
        val totalSeconds = ((remainingMs + 999L) / 1_000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }
}
