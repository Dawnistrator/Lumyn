package com.lunaria.core.model

data class LyricLine(
    val timestampMs: Long,
    val text: String,
)

data class ParsedLyrics(
    val lines: List<LyricLine>,
    val isSynced: Boolean,
    val sourceLabel: String = "",
)

/**
 * Pure Kotlin `.lrc` and unsynced lyrics parser.
 * Supports `[mm:ss.xx]`, `[mm:ss.xxx]`, multi-timestamp lines, `[offset:+/-ms]`, and metadata stripping.
 */
object LrcParser {

    private val TimestampRegex = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val OffsetRegex = Regex("""(?i)^\[offset:\s*([+-]?\d+)\s*]$""")
    private val MetadataTagRegex = Regex("""(?i)^\[(ar|ti|al|au|by|re|ve|length):.*]$""")

    fun parse(rawText: String, sourceLabel: String = ""): ParsedLyrics? {
        val cleaned = rawText.removePrefix("\uFEFF")
        if (cleaned.isBlank()) return null

        var offsetMs = 0L
        val syncedEntries = mutableListOf<LyricLine>()
        val unsyncedLines = mutableListOf<String>()

        cleaned.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@forEach

            OffsetRegex.matchEntire(trimmed)?.let { match ->
                offsetMs = match.groupValues[1].toLongOrNull() ?: 0L
                return@forEach
            }

            if (MetadataTagRegex.matches(trimmed)) return@forEach

            val matches = TimestampRegex.findAll(trimmed).toList()
            if (matches.isNotEmpty()) {
                val lyricText = TimestampRegex.replace(trimmed, "").trim()
                if (lyricText.isNotEmpty()) {
                    matches.forEach { match ->
                        val timestamp = parseTimestampMs(
                            minutesText = match.groupValues[1],
                            secondsText = match.groupValues[2],
                            fractionText = match.groupValues[3],
                        )
                        if (timestamp != null) {
                            syncedEntries += LyricLine(
                                timestampMs = (timestamp + offsetMs).coerceAtLeast(0L),
                                text = lyricText,
                            )
                        }
                    }
                }
            } else {
                unsyncedLines += trimmed
            }
        }

        if (syncedEntries.isNotEmpty()) {
            val sorted = syncedEntries.sortedBy { it.timestampMs }
            return ParsedLyrics(lines = sorted, isSynced = true, sourceLabel = sourceLabel)
        }

        if (unsyncedLines.isEmpty()) return null
        return ParsedLyrics(
            lines = unsyncedLines.map { LyricLine(timestampMs = 0L, text = it) },
            isSynced = false,
            sourceLabel = sourceLabel,
        )
    }

    fun activeLineIndexAt(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var active = -1
        for (i in lines.indices) {
            if (lines[i].timestampMs <= positionMs) {
                active = i
            } else {
                break
            }
        }
        return active
    }

    private fun parseTimestampMs(
        minutesText: String,
        secondsText: String,
        fractionText: String,
    ): Long? {
        val minutes = minutesText.toLongOrNull() ?: return null
        val seconds = secondsText.toLongOrNull() ?: return null
        val millis = when (fractionText.length) {
            0 -> 0L
            1 -> (fractionText.toLongOrNull() ?: 0L) * 100L
            2 -> (fractionText.toLongOrNull() ?: 0L) * 10L
            else -> fractionText.take(3).toLongOrNull() ?: 0L
        }
        return (minutes * 60_000L) + (seconds * 1_000L) + millis
    }
}
