package com.lumyn.core.audioengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeAndPlayHistoryTest {

    @Test
    fun cosineCrossfadeCurveHitsSmoothEndpointsAndMidpoint() {
        assertEquals(1f, CrossfadeCurve.computeFadeOutVolume(remainingMs = 4_000L, fadeDurationMs = 4_000L), 0.001f)
        assertEquals(0.5f, CrossfadeCurve.computeFadeOutVolume(remainingMs = 2_000L, fadeDurationMs = 4_000L), 0.001f)
        assertEquals(0f, CrossfadeCurve.computeFadeOutVolume(remainingMs = 0L, fadeDurationMs = 4_000L), 0.001f)

        assertEquals(0f, CrossfadeCurve.computeFadeInVolume(elapsedMs = 0L, fadeDurationMs = 4_000L), 0.001f)
        assertEquals(0.5f, CrossfadeCurve.computeFadeInVolume(elapsedMs = 2_000L, fadeDurationMs = 4_000L), 0.001f)
        assertEquals(1f, CrossfadeCurve.computeFadeInVolume(elapsedMs = 4_000L, fadeDurationMs = 4_000L), 0.001f)
    }

    @Test
    fun playHistoryRecordsOnceAfterThresholdAndResetsOnRepeat() {
        val tracker = PlayHistoryTracker()
        tracker.onTrackChanged(trackId = 7L, durationMs = 240_000L) // threshold = 30s

        assertNull(tracker.onPlayTick(15_000L))
        assertNull(tracker.onPlayTick(14_000L))
        val recorded = tracker.onPlayTick(2_000L)
        assertTrue(recorded != null && recorded >= 30_000L)
        // Does not double-record within the same play session
        assertNull(tracker.onPlayTick(10_000L))

        tracker.resetCurrentSession()
        assertNull(tracker.onPlayTick(20_000L))
        assertTrue(tracker.onPlayTick(11_000L) != null)
    }
}
