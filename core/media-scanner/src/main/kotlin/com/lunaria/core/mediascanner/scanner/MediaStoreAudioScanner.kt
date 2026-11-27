package com.lunaria.core.mediascanner.scanner

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.lunaria.core.database.preferences.LunariaPreferencesDataSource
import com.lunaria.core.database.repository.LocalMusicRepository
import com.lunaria.core.mediascanner.metadata.MediaExtractorAudioInspector
import com.lunaria.core.mediascanner.permission.hasAudioReadPermission
import com.lunaria.core.model.FolderFilterConfig
import com.lunaria.core.model.Track
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class MediaStoreAudioScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesDataSource: LunariaPreferencesDataSource,
    private val localMusicRepository: LocalMusicRepository,
    private val audioInspector: MediaExtractorAudioInspector,
) : AudioLibraryScanner {

    private val scanMutex = Mutex()

    private val _scanState = MutableStateFlow<ScanStatus>(ScanStatus.Idle)
    override val scanState: StateFlow<ScanStatus> = _scanState.asStateFlow()

    private val _discoveredFolders = MutableStateFlow<List<String>>(emptyList())
    override val discoveredFolders: StateFlow<List<String>> = _discoveredFolders.asStateFlow()

    override suspend fun triggerScan() {
        scanMutex.withLock {
            if (!hasAudioReadPermission(context)) {
                _scanState.value = ScanStatus.PermissionRequired
                return
            }

            val folderFilter = preferencesDataSource.preferences.first().folderFilter
            withContext(Dispatchers.IO) {
                try {
                    runScanLocked(folderFilter)
                } catch (_: SecurityException) {
                    _scanState.value = ScanStatus.PermissionRequired
                } catch (throwable: Throwable) {
                    _scanState.value = ScanStatus.Error(
                        throwable.localizedMessage ?: "Failed to scan audio library",
                    )
                }
            }
        }
    }

    private suspend fun runScanLocked(folderFilter: FolderFilterConfig) {
        val projection = buildProjection()
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        val foldersSeen = sortedSetOf(String.CASE_INSENSITIVE_ORDER)
        val scannedTracks = mutableListOf<Track>()
        var filteredOut = 0

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder,
        )?.use { cursor ->
            val totalRows = cursor.count
            _scanState.value = ScanStatus.Scanning(indexedCount = 0, totalCount = totalRows)

            val cols = ColumnIndices.from(cursor)
            var processed = 0
            while (cursor.moveToNext()) {
                val durationMs = cursor.getLongOrDefault(cols.duration)
                val fileName = cursor.getStringOrDefault(cols.displayName)
                if (durationMs < MIN_AUDIO_DURATION_MS || fileName.isBlank()) continue

                val folderPath = extractFolderPath(cursor, cols)
                if (folderPath.isNotEmpty()) foldersSeen += folderPath

                if (!folderFilter.isFolderAllowed(folderPath)) {
                    filteredOut++
                    continue
                }

                val id = cursor.getLongOrDefault(cols.id)
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id,
                )
                val albumId = cursor.getLongOrDefault(cols.albumId)
                val mimeType = cursor.getStringOrNull(cols.mimeType)
                val bitrateBps = cols.bitrate?.let { cursor.getIntOrDefault(it) } ?: 0
                val sizeBytes = cursor.getLongOrDefault(cols.size)

                val inspected = audioInspector.inspectWithTags(
                    uri = contentUri,
                    mimeType = mimeType,
                    fileName = fileName,
                    mediaStoreBitrateBps = bitrateBps,
                    fileSizeBytes = sizeBytes,
                    durationMs = durationMs,
                )

                val artist = cursor.getStringOrDefault(cols.artist).cleanUnknown("Unknown Artist")
                val album = cursor.getStringOrDefault(cols.album).cleanUnknown("Unknown Album")
                val albumArtist = cols.albumArtist
                    ?.let { cursor.getStringOrNull(it) }
                    ?.takeIf { it.isNotBlank() }
                    ?: artist
                val genre = cols.genre?.let { cursor.getStringOrNull(it) }.orEmpty()
                val title = cursor.getStringOrDefault(cols.title)
                    .ifBlank { fileName.substringBeforeLast('.') }
                val rawTrack = cursor.getIntOrDefault(cols.track)
                val discNumber = if (rawTrack >= 1000) rawTrack / 1000 else 1
                val trackNumber = if (rawTrack >= 1000) rawTrack % 1000 else rawTrack
                val artworkUri = if (albumId > 0L) {
                    ContentUris.withAppendedId(ALBUM_ART_URI, albumId).toString()
                } else {
                    null
                }

                scannedTracks += Track(
                    id = id,
                    contentUri = contentUri.toString(),
                    title = title,
                    artist = artist,
                    album = album,
                    albumArtist = albumArtist,
                    albumId = albumId,
                    durationMs = durationMs,
                    trackNumber = trackNumber.coerceAtLeast(0),
                    discNumber = discNumber.coerceAtLeast(1),
                    year = cursor.getIntOrDefault(cols.year).coerceAtLeast(0),
                    genre = genre,
                    folderPath = folderPath,
                    fileName = fileName,
                    mimeType = mimeType.orEmpty(),
                    audioSpec = inspected.audioSpec,
                    artworkUri = artworkUri,
                    isFavorite = false,
                    dateAddedSeconds = cursor.getLongOrDefault(cols.dateAdded),
                    replayGainTrackDb = inspected.replayGain.trackGainDb,
                    replayGainAlbumDb = inspected.replayGain.albumGainDb,
                    replayGainTrackPeak = inspected.replayGain.trackPeak,
                    replayGainAlbumPeak = inspected.replayGain.albumPeak,
                )

                processed++
                if (processed % PROGRESS_EMIT_INTERVAL == 0) {
                    _scanState.value = ScanStatus.Scanning(indexedCount = processed, totalCount = totalRows)
                }
            }
        }

        _discoveredFolders.value = foldersSeen.toList()
        localMusicRepository.syncScannedTracks(scannedTracks)
        _scanState.value = ScanStatus.Completed(
            indexedCount = scannedTracks.size,
            filteredOutCount = filteredOut,
        )
    }

    private fun buildProjection(): Array<String> = buildList {
        add(MediaStore.Audio.Media._ID)
        add(MediaStore.Audio.Media.TITLE)
        add(MediaStore.Audio.Media.ARTIST)
        add(MediaStore.Audio.Media.ALBUM)
        add(MediaStore.Audio.Media.ALBUM_ID)
        add(MediaStore.Audio.Media.DURATION)
        add(MediaStore.Audio.Media.TRACK)
        add(MediaStore.Audio.Media.YEAR)
        add(MediaStore.Audio.Media.MIME_TYPE)
        add(MediaStore.Audio.Media.DISPLAY_NAME)
        add(MediaStore.Audio.Media.SIZE)
        add(MediaStore.Audio.Media.DATE_ADDED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(MediaStore.Audio.Media.RELATIVE_PATH)
        } else {
            @Suppress("DEPRECATION")
            add(MediaStore.Audio.Media.DATA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(MediaStore.Audio.Media.ALBUM_ARTIST)
            add(MediaStore.Audio.Media.GENRE)
            add(MediaStore.Audio.Media.BITRATE)
        }
    }.toTypedArray()

    private fun extractFolderPath(cursor: Cursor, cols: ColumnIndices): String {
        val raw = if (cols.relativePath != null) {
            cursor.getStringOrNull(cols.relativePath).orEmpty()
        } else if (cols.data != null) {
            val fullPath = cursor.getStringOrNull(cols.data).orEmpty()
            fullPath.substringBeforeLast('/', "")
        } else {
            ""
        }
        return FolderFilterConfig.normalizeFolderPath(raw)
    }

    private data class ColumnIndices(
        val id: Int,
        val title: Int,
        val artist: Int,
        val album: Int,
        val albumId: Int,
        val duration: Int,
        val track: Int,
        val year: Int,
        val mimeType: Int,
        val displayName: Int,
        val size: Int,
        val dateAdded: Int,
        val relativePath: Int?,
        val data: Int?,
        val albumArtist: Int?,
        val genre: Int?,
        val bitrate: Int?,
    ) {
        companion object {
            fun from(cursor: Cursor): ColumnIndices {
                fun opt(name: String): Int? = cursor.getColumnIndex(name).takeIf { it >= 0 }
                return ColumnIndices(
                    id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID),
                    title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE),
                    artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST),
                    album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM),
                    albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID),
                    duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION),
                    track = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK),
                    year = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR),
                    mimeType = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE),
                    displayName = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME),
                    size = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE),
                    dateAdded = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED),
                    relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        opt(MediaStore.Audio.Media.RELATIVE_PATH)
                    } else {
                        null
                    },
                    data = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        @Suppress("DEPRECATION")
                        opt(MediaStore.Audio.Media.DATA)
                    } else {
                        null
                    },
                    albumArtist = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        opt(MediaStore.Audio.Media.ALBUM_ARTIST)
                    } else {
                        null
                    },
                    genre = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        opt(MediaStore.Audio.Media.GENRE)
                    } else {
                        null
                    },
                    bitrate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        opt(MediaStore.Audio.Media.BITRATE)
                    } else {
                        null
                    },
                )
            }
        }
    }

    private fun Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun Cursor.getStringOrDefault(index: Int): String = getStringOrNull(index).orEmpty()

    private fun Cursor.getLongOrDefault(index: Int): Long =
        if (isNull(index)) 0L else getLong(index)

    private fun Cursor.getIntOrDefault(index: Int): Int =
        if (isNull(index)) 0 else getInt(index)

    private fun String.cleanUnknown(fallback: String): String =
        if (isBlank() || equals("<unknown>", ignoreCase = true)) fallback else this

    private companion object {
        const val MIN_AUDIO_DURATION_MS = 1_000L
        const val PROGRESS_EMIT_INTERVAL = 10
        val ALBUM_ART_URI: Uri = Uri.parse("content://media/external/audio/albumart")
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaScannerModule {
    @Binds
    abstract fun bindScanner(impl: MediaStoreAudioScanner): AudioLibraryScanner
}
