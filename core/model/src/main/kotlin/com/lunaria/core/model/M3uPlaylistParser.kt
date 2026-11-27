package com.lunaria.core.model

data class M3uEntry(
    val pathOrUri: String,
    val title: String?,
    val durationSeconds: Int?,
)

/** Pure Kotlin `.m3u` / `.m3u8` parser and exporter with safe library-track matching. */
object M3uPlaylistParser {

    private val ExtInfRegex = Regex("""^#EXTINF:\s*(-?\d+)\s*,(.*)$""")

    fun parse(content: String): List<M3uEntry> {
        val cleaned = content.removePrefix("\uFEFF")
        val entries = mutableListOf<M3uEntry>()
        var pendingDuration: Int? = null
        var pendingTitle: String? = null

        cleaned.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            if (line.startsWith("#")) {
                ExtInfRegex.matchEntire(line)?.let { match ->
                    pendingDuration = match.groupValues[1].toIntOrNull()?.takeIf { it >= 0 }
                    pendingTitle = match.groupValues[2].trim().takeIf { it.isNotEmpty() }
                }
                return@forEach
            }

            entries += M3uEntry(
                pathOrUri = line,
                title = pendingTitle,
                durationSeconds = pendingDuration,
            )
            pendingDuration = null
            pendingTitle = null
        }

        return entries
    }

    fun export(tracks: List<Track>): String = buildString {
        appendLine("#EXTM3U")
        tracks.forEach { track ->
            val seconds = (track.durationMs / 1_000L).coerceAtLeast(0L)
            appendLine("#EXTINF:$seconds,${track.artist} - ${track.title}")
            val location = when {
                track.folderPath.isNotBlank() && track.fileName.isNotBlank() ->
                    "${track.folderPath.trimEnd('/')}/${track.fileName}"
                track.fileName.isNotBlank() -> track.fileName
                else -> track.contentUri
            }
            appendLine(location)
        }
    }

    fun matchTracks(entries: List<M3uEntry>, library: List<Track>): List<Track> {
        if (entries.isEmpty() || library.isEmpty()) return emptyList()
        val matched = mutableListOf<Track>()

        entries.forEach { entry ->
            val normalizedPath = entry.pathOrUri.replace('\\', '/').trimStart('/')
            val entryFileName = normalizedPath.substringAfterLast('/')

            val track = library.firstOrNull { candidate ->
                candidate.contentUri == entry.pathOrUri
            } ?: library.firstOrNull { candidate ->
                val relPath = listOf(candidate.folderPath.trim('/'), candidate.fileName)
                    .filter { it.isNotEmpty() }
                    .joinToString("/")
                relPath.isNotEmpty() && normalizedPath.endsWith(relPath, ignoreCase = true)
            } ?: library.firstOrNull { candidate ->
                entryFileName.isNotEmpty() && candidate.fileName.equals(entryFileName, ignoreCase = true)
            } ?: entry.title?.let { extTitle ->
                library.firstOrNull { candidate ->
                    "${candidate.artist} - ${candidate.title}".equals(extTitle, ignoreCase = true) ||
                        candidate.title.equals(extTitle, ignoreCase = true)
                }
            }

            if (track != null) matched += track
        }

        return matched
    }
}
