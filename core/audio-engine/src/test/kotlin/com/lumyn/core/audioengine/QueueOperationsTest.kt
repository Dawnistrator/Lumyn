package com.lumyn.core.audioengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueOperationsTest {

    @Test
    fun playNextInsertionIndexHandlesEmptyAndActiveQueue() {
        assertEquals(0, QueueOperations.playNextInsertionIndex(-1, 0))
        assertEquals(0, QueueOperations.playNextInsertionIndex(-1, 3))
        assertEquals(2, QueueOperations.playNextInsertionIndex(1, 5))
        assertEquals(5, QueueOperations.playNextInsertionIndex(4, 5))
    }

    @Test
    fun moveAndRemoveValidatorsEnforceBounds() {
        assertTrue(QueueOperations.isValidMove(0, 2, 3))
        assertFalse(QueueOperations.isValidMove(1, 1, 3))
        assertFalse(QueueOperations.isValidMove(-1, 2, 3))
        assertFalse(QueueOperations.isValidMove(0, 3, 3))

        assertTrue(QueueOperations.isValidRemove(2, 3))
        assertFalse(QueueOperations.isValidRemove(3, 3))
    }

    @Test
    fun sleepTimerFormatsMinutesAndHours() {
        assertEquals("0:15", QueueOperations.formatSleepTimerRemaining(15_000L))
        assertEquals("14:59", QueueOperations.formatSleepTimerRemaining(899_000L))
        assertEquals("1:05:00", QueueOperations.formatSleepTimerRemaining(3_900_000L))
    }
}
