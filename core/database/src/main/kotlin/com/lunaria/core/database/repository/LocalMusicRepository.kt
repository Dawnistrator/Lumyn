package com.lunaria.core.database.repository

import androidx.room.withTransaction
import com.lunaria.core.database.LunariaDatabase
import com.lunaria.core.database.dao.PlaylistDao
import com.lunaria.core.database.dao.TrackDao
import com.lunaria.core.database.entity.PlaylistEntity
import com.lunaria.core.database.entity.PlaylistTrackCrossRef
import com.lunaria.core.database.entity.toEntity
import com.lunaria.core.database.entity.toModel
import com.lunaria.core.model.AlbumSummary
import com.lunaria.core.model.ArtistSummary
import com.lunaria.core.model.FolderSummary
import com.lunaria.core.model.ListeningStats
import com.lunaria.core.model.PlaylistSummary
import com.lunaria.core.model.SmartPlaylistEngine
import com.lunaria.core.model.SmartPlaylistSummary
import com.lunaria.core.model.Track
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

interface LocalMusicRepository {
    val tracks: Flow<List<Track>>
    val recentTracks: Flow<List<Track>>
    val albums: Flow<List<AlbumSummary>>
    val artists: Flow<List<ArtistSummary>>
    val folders: Flow<List<FolderSummary>>
    val playlists: Flow<List<PlaylistSummary>>
    val smartPlaylists: Flow<List<SmartPlaylistSummary>>
    val listeningStats: Flow<ListeningStats>

    suspend fun getTrackById(trackId: Long): Track?
    suspend fun getTracksByIds(ids: List<Long>): List<Track>
    fun observePlaylistTracks(playlistId: Long): Flow<List<Track>>
    suspend fun getPlaylistTracks(playlistId: Long): List<Track>
    suspend fun syncScannedTracks(scannedTracks: List<Track>)
    suspend fun upsertVirtualCueTracks(cueTracks: List<Track>)
    suspend fun toggleFavorite(trackId: Long, isFavorite: Boolean)
    suspend fun recordTrackPlay(trackId: Long, playedAtMs: Long, listenedMs: Long)
    suspend fun updateTrackMetadata(
        trackId: Long,
        title: String,
        artist: String,
        album: String,
        trackNumber: Int,
        year: Int,
        genre: String,
        artworkUri: String?,
    )
    suspend fun createPlaylist(name: String)
    suspend fun createPlaylistWithTracks(name: String, trackIds: List<Long>)
    suspend fun addTracksToPlaylist(playlistId: Long, trackIds: List<Long>): Int
    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long)
    suspend fun renamePlaylist(playlistId: Long, newName: String)
    suspend fun deletePlaylist(playlistId: Long)
}

@Singleton
class DefaultLocalMusicRepository @Inject constructor(
    private val database: LunariaDatabase,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
) : LocalMusicRepository {

    override val tracks: Flow<List<Track>> =
        trackDao.observeAll().map { entities -> entities.map { it.toModel() } }
            .flowOn(Dispatchers.Default)

    override val recentTracks: Flow<List<Track>> =
        trackDao.observeRecent(limit = 20).map { entities -> entities.map { it.toModel() } }
            .flowOn(Dispatchers.Default)

    override val albums: Flow<List<AlbumSummary>> = tracks.map { list ->
        list.groupBy { it.albumId }
            .map { (albumId, items) ->
                val representative = items.first()
                val primaryArtist = representative.albumArtist.ifBlank { representative.artist }
                AlbumSummary(
                    id = albumId,
                    title = representative.album,
                    artist = primaryArtist,
                    year = items.maxOfOrNull { it.year } ?: 0,
                    trackCount = items.size,
                    artworkUri = items.firstNotNullOfOrNull { it.artworkUri },
                    hasHiRes = items.any { it.audioSpec.isHiRes },
                )
            }
            .sortedBy { it.title.lowercase() }
    }.flowOn(Dispatchers.Default)

    override val artists: Flow<List<ArtistSummary>> = tracks.map { list ->
        list.groupBy { it.albumArtist.ifBlank { it.artist }.trim().ifEmpty { "Unknown Artist" } }
            .map { (artistName, items) ->
                ArtistSummary(
                    name = artistName,
                    albumCount = items.map { it.albumId }.distinct().size,
                    trackCount = items.size,
                    artworkUri = items.firstNotNullOfOrNull { it.artworkUri },
                )
            }
            .sortedBy { it.name.lowercase() }
    }.flowOn(Dispatchers.Default)

    override val folders: Flow<List<FolderSummary>> = tracks.map { list ->
        list.groupBy { it.folderPath.ifBlank { "Root" } }
            .map { (path, items) ->
                val displayName = path.substringAfterLast('/').ifBlank { path }
                FolderSummary(
                    path = path,
                    displayName = displayName,
                    trackCount = items.size,
                    totalDurationMs = items.sumOf { it.durationMs },
                )
            }
            .sortedBy { it.path.lowercase() }
    }.flowOn(Dispatchers.Default)

    override val playlists: Flow<List<PlaylistSummary>> =
        playlistDao.observePlaylistsWithCount().map { rows ->
            rows.map { row ->
                PlaylistSummary(
                    id = row.id,
                    name = row.name,
                    trackCount = row.trackCount,
                    createdAtEpochSeconds = row.createdAtEpochSeconds,
                )
            }
        }.flowOn(Dispatchers.Default)

    override val smartPlaylists: Flow<List<SmartPlaylistSummary>> = tracks.map { list ->
        SmartPlaylistEngine.summarizeSmartPlaylists(list)
    }.flowOn(Dispatchers.Default)

    override val listeningStats: Flow<ListeningStats> = tracks.map { list ->
        SmartPlaylistEngine.computeStats(list)
    }.flowOn(Dispatchers.Default)

    override suspend fun getTrackById(trackId: Long): Track? =
        trackDao.getById(trackId)?.toModel()

    override suspend fun getTracksByIds(ids: List<Long>): List<Track> {
        if (ids.isEmpty()) return emptyList()
        val found = HashMap<Long, Track>(ids.size)
        ids.distinct().chunked(SQLITE_BIND_CHUNK_SIZE).forEach { chunk ->
            trackDao.getByIds(chunk).forEach { entity ->
                found[entity.id] = entity.toModel()
            }
        }
        return ids.mapNotNull { found[it] }
    }

    override fun observePlaylistTracks(playlistId: Long): Flow<List<Track>> =
        playlistDao.observeTracksForPlaylist(playlistId).map { list -> list.map { it.toModel() } }
            .flowOn(Dispatchers.Default)

    override suspend fun getPlaylistTracks(playlistId: Long): List<Track> =
        playlistDao.getTracksForPlaylist(playlistId).map { it.toModel() }

    override suspend fun syncScannedTracks(scannedTracks: List<Track>) {
        database.withTransaction {
            val preservedById = trackDao.getPreservedStates().associateBy { it.id }
            val existingMediaStoreIds = trackDao.getAllMediaStoreIds().toHashSet()
            val scannedIds = scannedTracks.mapTo(HashSet(scannedTracks.size)) { it.id }
            val activeUris = scannedTracks.mapTo(HashSet(scannedTracks.size)) { it.contentUri }

            if (scannedTracks.isNotEmpty()) {
                val entities = scannedTracks.map { track ->
                    val prev = preservedById[track.id]
                    val preservedArtwork = prev?.artworkUri?.takeIf { it.startsWith("file:") } ?: track.artworkUri
                    track.copy(
                        isFavorite = prev?.isFavorite ?: false,
                        artworkUri = preservedArtwork,
                        playCount = prev?.playCount ?: 0,
                        lastPlayedAtMs = prev?.lastPlayedAtMs ?: 0L,
                        totalPlayDurationMs = prev?.totalPlayDurationMs ?: 0L,
                    ).toEntity()
                }
                trackDao.upsertAll(entities)
            }

            val orphanedCueIds = trackDao.getVirtualCueTracks()
                .filter { it.contentUri !in activeUris }
                .map { it.id }

            val staleMediaIds = (existingMediaStoreIds - scannedIds).toList() + orphanedCueIds
            staleMediaIds.chunked(SQLITE_BIND_CHUNK_SIZE).forEach { chunk ->
                trackDao.deleteByIds(chunk)
            }
        }
    }

    override suspend fun upsertVirtualCueTracks(cueTracks: List<Track>) {
        if (cueTracks.isEmpty()) return
        database.withTransaction {
            val preservedById = trackDao.getPreservedStates().associateBy { it.id }
            val entities = cueTracks.map { track ->
                val prev = preservedById[track.id]
                val preservedArtwork = prev?.artworkUri?.takeIf { it.startsWith("file:") } ?: track.artworkUri
                track.copy(
                    isFavorite = prev?.isFavorite ?: track.isFavorite,
                    artworkUri = preservedArtwork,
                    playCount = prev?.playCount ?: track.playCount,
                    lastPlayedAtMs = prev?.lastPlayedAtMs ?: track.lastPlayedAtMs,
                    totalPlayDurationMs = prev?.totalPlayDurationMs ?: track.totalPlayDurationMs,
                ).toEntity()
            }
            trackDao.upsertAll(entities)
        }
    }

    override suspend fun toggleFavorite(trackId: Long, isFavorite: Boolean) {
        trackDao.setFavorite(trackId, isFavorite)
    }

    override suspend fun recordTrackPlay(trackId: Long, playedAtMs: Long, listenedMs: Long) {
        trackDao.recordPlay(trackId = trackId, playedAtMs = playedAtMs, listenedMs = listenedMs.coerceAtLeast(0L))
    }

    override suspend fun updateTrackMetadata(
        trackId: Long,
        title: String,
        artist: String,
        album: String,
        trackNumber: Int,
        year: Int,
        genre: String,
        artworkUri: String?,
    ) {
        trackDao.updateMetadata(
            trackId = trackId,
            title = title,
            artist = artist,
            album = album,
            trackNumber = trackNumber,
            year = year,
            genre = genre,
            artworkUri = artworkUri,
        )
    }

    override suspend fun createPlaylist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        playlistDao.insert(
            PlaylistEntity(
                name = trimmed,
                createdAtEpochSeconds = System.currentTimeMillis() / 1_000L,
            ),
        )
    }

    override suspend fun createPlaylistWithTracks(name: String, trackIds: List<Long>) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        database.withTransaction {
            val playlistId = playlistDao.insert(
                PlaylistEntity(
                    name = trimmed,
                    createdAtEpochSeconds = System.currentTimeMillis() / 1_000L,
                ),
            )
            if (trackIds.isNotEmpty()) {
                val refs = trackIds.distinct().mapIndexed { index, trackId ->
                    PlaylistTrackCrossRef(playlistId = playlistId, trackId = trackId, position = index)
                }
                playlistDao.insertTracks(refs)
            }
        }
    }

    override suspend fun addTracksToPlaylist(playlistId: Long, trackIds: List<Long>): Int {
        if (trackIds.isEmpty()) return 0
        return database.withTransaction {
            val existing = playlistDao.getTrackIdsForPlaylist(playlistId).toHashSet()
            val toInsert = trackIds.distinct().filter { it !in existing }
            if (toInsert.isEmpty()) return@withTransaction 0
            val startPos = (playlistDao.getMaxPosition(playlistId) ?: -1) + 1
            val refs = toInsert.mapIndexed { offset, trackId ->
                PlaylistTrackCrossRef(
                    playlistId = playlistId,
                    trackId = trackId,
                    position = startPos + offset,
                )
            }
            playlistDao.insertTracks(refs)
            toInsert.size
        }
    }

    override suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) {
        playlistDao.removeTrackFromPlaylist(playlistId, trackId)
    }

    override suspend fun renamePlaylist(playlistId: Long, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isNotEmpty()) {
            playlistDao.renamePlaylist(playlistId, trimmed)
        }
    }

    override suspend fun deletePlaylist(playlistId: Long) {
        playlistDao.deleteById(playlistId)
    }

    private companion object {
        const val SQLITE_BIND_CHUNK_SIZE = 500
    }
}
