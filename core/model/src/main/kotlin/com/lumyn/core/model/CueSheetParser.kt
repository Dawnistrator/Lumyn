package com.lumyn.core.model

data class CueTrackEntry(
    val trackNumber: Int,
    val title: String,
    val performer: String,
    val startMs: Long,
    val endMs: Long?,
    val fileName: String,
)

data class ParsedCueSheet(
    val albumTitle: String,
    val albumPerformer: String,
    val fileName: String,
    val tracks: List<CueTrackEntry>,
)

/** Pure Kotlin `.cue` sheet parser supporting multi-track CD-frame timestamps (`mm:ss:ff` at 75 fps). */
object CueSheetParser {

    private val QuotedRegex = Regex(""""([^"]+)"""")
    private val TrackRegex = Regex("""(?i)^TRACK\s+(\d+)\s+AUDIO\b""")
    private val Index01Regex = Regex("""(?i)^INDEX\s+01\s+(\d+):(\d{1,2}):(\d{1,2})""")

    fun parse(rawText: String): ParsedCueSheet? {
        val cleaned = rawText.removePrefix("\uFEFF")
        if (cleaned.isBlank()) return null

        var albumTitle = ""
        var albumPerformer = ""
        var activeFileName = ""
        var firstFileName = ""

        data class MutableCueTrack(
            val trackNumber: Int,
            var title: String = "",
            var performer: String = "",
            var startMs: Long? = null,
            val fileName: String,
        )

        val rawTracks = mutableListOf<MutableCueTrack>()
        var currentTrack: MutableCueTrack? = null

        cleaned.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            when {
                line.startsWith("FILE ", ignoreCase = true) -> {
                    val file = extractValue(line.substring(4))
                    activeFileName = file
                    if (firstFileName.isEmpty()) firstFileName = file
                }

                TrackRegex.containsMatchIn(line) -> {
                    currentTrack?.let { rawTracks += it }
                    val num = TrackRegex.find(line)!!.groupValues[1].toIntOrNull() ?: (rawTracks.size + 1)
                    currentTrack = MutableCueTrack(trackNumber = num, fileName = activeFileName)
                }

                line.startsWith("TITLE ", ignoreCase = true) -> {
                    val title = extractValue(line.substring(5))
                    val active = currentTrack
                    if (active == null) albumTitle = title else active.title = title
                }

                line.startsWith("PERFORMER ", ignoreCase = true) -> {
                    val performer = extractValue(line.substring(9))
                    val active = currentTrack
                    if (active == null) albumPerformer = performer else active.performer = performer
                }

                Index01Regex.containsMatchIn(line) -> {
                    val match = Index01Regex.find(line)!!
                    val mm = match.groupValues[1].toLongOrNull() ?: 0L
                    val ss = match.groupValues[2].toLongOrNull() ?: 0L
                    val ff = match.groupValues[3].toLongOrNull() ?: 0L
                    val ms = (mm * 60L + ss) * 1_000L + (ff * 1_000L) / FRAMES_PER_SECOND
                    currentTrack?.let {
                        if (it.startMs == null) it.startMs = ms
                    }
                }
            }
        }
        currentTrack?.let { rawTracks += it }

        val validTracks = rawTracks.filter { it.startMs != null }
        if (validTracks.isEmpty()) return null

        val entries = validTracks.mapIndexed { index, track ->
            val next = validTracks.getOrNull(index + 1)
            val endMs = if (next != null && next.fileName == track.fileName) next.startMs else null
            CueTrackEntry(
                trackNumber = track.trackNumber,
                title = track.title.ifBlank { "Track ${track.trackNumber}" },
                performer = track.performer.ifBlank { albumPerformer.ifBlank { "Unknown Artist" } },
                startMs = track.startMs ?: 0L,
                endMs = endMs,
                fileName = track.fileName,
            )
        }

        return ParsedCueSheet(
            albumTitle = albumTitle.ifBlank { "Cue Sheet" },
            albumPerformer = albumPerformer.ifBlank { "Unknown Artist" },
            fileName = firstFileName,
            tracks = entries,
        )
    }

    private fun extractValue(rest: String): String {
        val quoted = QuotedRegex.find(rest)?.groupValues?.getOrNull(1)
        return (quoted ?: rest.trim().substringBeforeLast(' ')).trim()
    }

    private const val FRAMES_PER_SECOND = 75L
}
