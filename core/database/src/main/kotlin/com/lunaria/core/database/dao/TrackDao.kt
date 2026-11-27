package com.lunaria.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lunaria.core.database.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

data class TrackPreservedStateRow(
    val id: Long,
    val isFavorite: Boolean,
    val artworkUri: String?,
    val playCount: Int,
    val lastPlayedAtMs: Long,
    val totalPlayDurationMs: Long,
)

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :trackId LIMIT 1")
    suspend fun getById(trackId: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<TrackEntity>

    @Query("SELECT * FROM tracks ORDER BY dateAddedSeconds DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<TrackEntity>>

    @Query("SELECT COUNT(*) FROM tracks")
    fun observeCount(): Flow<Int>

    @Query("SELECT id FROM tracks WHERE id > 0")
    suspend fun getAllMediaStoreIds(): List<Long>

    @Query("SELECT * FROM tracks WHERE id < 0")
    suspend fun getVirtualCueTracks(): List<TrackEntity>

    @Query("SELECT id, isFavorite, artworkUri, playCount, lastPlayedAtMs, totalPlayDurationMs FROM tracks")
    suspend fun getPreservedStates(): List<TrackPreservedStateRow>

    @Upsert
    suspend fun upsertAll(tracks: List<TrackEntity>)

    @Query("UPDATE tracks SET isFavorite = :favorite WHERE id = :trackId")
    suspend fun setFavorite(trackId: Long, favorite: Boolean)

    @Query(
        """
        UPDATE tracks
        SET playCount = playCount + 1,
            lastPlayedAtMs = MAX(lastPlayedAtMs, :playedAtMs),
            totalPlayDurationMs = totalPlayDurationMs + :listenedMs
        WHERE id = :trackId
        """,
    )
    suspend fun recordPlay(trackId: Long, playedAtMs: Long, listenedMs: Long)

    @Query(
        """
        UPDATE tracks
        SET title = :title,
            artist = :artist,
            album = :album,
            trackNumber = :trackNumber,
            year = :year,
            genre = :genre,
            artworkUri = :artworkUri
        WHERE id = :trackId
        """,
    )
    suspend fun updateMetadata(
        trackId: Long,
        title: String,
        artist: String,
        album: String,
        trackNumber: Int,
        year: Int,
        genre: String,
        artworkUri: String?,
    )

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM tracks")
    suspend fun clearAll()
}
