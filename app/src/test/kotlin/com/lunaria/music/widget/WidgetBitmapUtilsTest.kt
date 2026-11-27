package com.lunaria.music.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetBitmapUtilsTest {

    @Test
    fun `square hi-res cover downsamples cleanly within budget`() {
        val sample = WidgetBitmapUtils.calculateInSampleSize(
            srcWidth = 2048,
            srcHeight = 2048,
            targetPx = 256,
            maxBudgetBytes = 1_500_000L,
        )
        assertEquals(8, sample)
    }

    @Test
    fun `non-square wide banner downsamples when width far exceeds target`() {
        val sample = WidgetBitmapUtils.calculateInSampleSize(
            srcWidth = 4000,
            srcHeight = 300,
            targetPx = 256,
            maxBudgetBytes = 4_000_000L,
        )
        assertTrue(sample > 1)
        assertEquals(8, sample)
    }

    @Test
    fun `small memory budget increases sample size until rgb565 footprint fits`() {
        val sample = WidgetBitmapUtils.calculateInSampleSize(
            srcWidth = 1024,
            srcHeight = 1024,
            targetPx = 512,
            maxBudgetBytes = 100_000L,
        )
        val w = 1024 / sample
        val h = 1024 / sample
        assertTrue((w.toLong() * h * 2L) <= 100_000L)
        assertEquals(8, sample)
    }

    @Test
    fun `zero or invalid source dimensions return sample size 1 without crashing`() {
        assertEquals(1, WidgetBitmapUtils.calculateInSampleSize(0, 500, 128, 1_500_000L))
        assertEquals(1, WidgetBitmapUtils.calculateInSampleSize(500, -10, 128, 1_500_000L))
        assertEquals(1, WidgetBitmapUtils.calculateInSampleSize(500, 500, 0, 1_500_000L))
    }

    @Test
    fun `tiny budget terminates cleanly at cap without looping forever`() {
        val sample = WidgetBitmapUtils.calculateInSampleSize(
            srcWidth = 4096,
            srcHeight = 4096,
            targetPx = 512,
            maxBudgetBytes = 1L,
        )
        assertTrue(sample in 1..1024)
    }
}
