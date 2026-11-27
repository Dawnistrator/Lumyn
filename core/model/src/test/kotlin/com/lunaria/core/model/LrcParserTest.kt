package com.lunaria.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun parsesSyncedTimestampsMultiTagsAndOffsetSortedChronologically() {
        val raw = """
            [ar:Artist Name]
            [ti:Song Title]
            [offset:500]
            [00:10.00][00:45.25]Chorus line
            [00:04.50]Opening verse
            [00:22.125]Middle bridge
        """.trimIndent()

        val parsed = LrcParser.parse(raw, sourceLabel = "Local .lrc file")
        assertNotNull(parsed)
        assertTrue(parsed!!.isSynced)
        assertEquals(4, parsed.lines.size)

        // +500 ms offset applied and sorted chronologically
        assertEquals(5_000L, parsed.lines[0].timestampMs)
        assertEquals("Opening verse", parsed.lines[0].text)
        assertEquals(10_500L, parsed.lines[1].timestampMs)
        assertEquals("Chorus line", parsed.lines[1].text)
        assertEquals(22_625L, parsed.lines[2].timestampMs)
        assertEquals(45_750L, parsed.lines[3].timestampMs)

        assertEquals(-1, LrcParser.activeLineIndexAt(parsed.lines, 2_000L))
        assertEquals(0, LrcParser.activeLineIndexAt(parsed.lines, 5_000L))
        assertEquals(1, LrcParser.activeLineIndexAt(parsed.lines, 15_000L))
        assertEquals(3, LrcParser.activeLineIndexAt(parsed.lines, 60_000L))
    }

    @Test
    fun fallsBackToUnsyncedLinesWhenNoTimestampsPresent() {
        val raw = "First line\nSecond line\nThird line"
        val parsed = LrcParser.parse(raw, sourceLabel = "Embedded tags")
        assertNotNull(parsed)
        assertFalse(parsed!!.isSynced)
        assertEquals(3, parsed.lines.size)
        assertEquals("Second line", parsed.lines[1].text)
    }

    @Test
    fun returnsNullOnBlankInput() {
        assertNull(LrcParser.parse("   \n  "))
    }
}
