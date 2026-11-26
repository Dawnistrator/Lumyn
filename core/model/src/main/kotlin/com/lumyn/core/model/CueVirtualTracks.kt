package com.lumyn.core.model

/**
 * Converts a parsed [ParsedCueSheet] referencing a single local [backingTrack] into virtual clipped
 * [Track] items backed by Media3 `ClippingConfiguration`. Negative deterministic IDs keep CUE
 * virtual tracks completely isolated from positive MediaStore `_ID` rows.
 */
object CueVirtualTracks {

    fun buildVirtualTracks(
        cue: ParsedCueSheet,
        backingTrack: Track,
        cueSourceKey: String,
    ): List<Track> = cue.tracks.map { entry ->
        val rawEnd = entry.endMs
        val effectiveEnd = rawEnd?.takeIf { it > entry.startMs }
        val clipDurationMs = when {
            effectiveEnd != null -> effectiveEnd - entry.startMs
            backingTrack.durationMs > entry.startMs -> backingTrack.durationMs - entry.startMs
            else -> backingTrack.durationMs
        }.coerceAtLeast(1_000L)

        backingTrack.copy(
            id = deterministicNegativeId("$cueSourceKey|${backingTrack.id}|${entry.trackNumber}|${entry.startMs}"),
            title = entry.title,
            artist = entry.performer.ifBlank { backingTrack.artist },
            album = cue.albumTitle.ifBlank { backingTrack.album },
            albumArtist = cue.albumPerformer.ifBlank { backingTrack.albumArtist },
            durationMs = clipDurationMs,
            trackNumber = entry.trackNumber.coerceAtLeast(1),
            clipStartMs = entry.startMs.coerceAtLeast(0L),
            clipEndMs = effectiveEnd,
            cueSourcePath = cueSourceKey,
            isFavorite = false,
            playCount = 0,
            lastPlayedAtMs = 0L,
            totalPlayDurationMs = 0L,
        )
    }

    internal fun deterministicNegativeId(seed: String): Long {
        // 64-bit FNV-1a hashed into the negative non-zero range [-2^62 - 1, -1].
        var hash = -3750763034362895579L // 0xcbf29ce484222325UL as signed Long
        val prime = 1099511628211L
        for (i in seed.indices) {
            hash = hash xor seed[i].code.toLong()
            hash *= prime
        }
        val magnitude = (hash and 0x3FFF_FFFF_FFFF_FFFFL) + 1L
        return -magnitude
    }
}
