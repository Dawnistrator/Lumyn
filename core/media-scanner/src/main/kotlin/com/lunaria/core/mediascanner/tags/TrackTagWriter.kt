package com.lunaria.core.mediascanner.tags

import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.lunaria.core.database.repository.LocalMusicRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TrackTagEdit(
    val trackId: Long,
    val contentUri: String,
    val title: String,
    val artist: String,
    val album: String,
    val trackNumber: Int,
    val year: Int,
    val genre: String,
    val artworkUri: String?,
    val isCueTrack: Boolean = false,
    val customArtworkChanged: Boolean = false,
)

sealed interface TagWriteResult {
    data object Updated : TagWriteResult
    data class RequiresWriteConsent(val intentSender: IntentSender) : TagWriteResult
    data class UpdatedLocallyOnly(val reason: String) : TagWriteResult
}

/**
 * Updates local audio metadata in Room, writes tags directly into FLAC/MP3/MP4-M4A headers when
 * write permission is available (with Android 11+ `MediaStore.createWriteRequest()` consent), and
 * syncs MediaStore metadata columns.
 */
@Singleton
class TrackTagWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localMusicRepository: LocalMusicRepository,
) {

    suspend fun persistPickedArtwork(trackId: Long, sourceUri: Uri): String? = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "custom_art").apply { mkdirs() }
        dir.listFiles { _, name -> name.startsWith("track_${trackId}_") }?.forEach { it.delete() }

        val decoded = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            val sampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, MAX_ARTWORK_PX, MAX_ARTWORK_PX)
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            } ?: return@withContext null
        } catch (_: SecurityException) {
            return@withContext null
        } catch (_: java.io.IOException) {
            return@withContext null
        }

        return@withContext try {
            val outFile = File(dir, "track_${trackId}_${System.currentTimeMillis()}.jpg")
            FileOutputStream(outFile).use { fos ->
                decoded.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            }
            Uri.fromFile(outFile).toString()
        } catch (_: SecurityException) {
            null
        } catch (_: java.io.IOException) {
            null
        } finally {
            decoded.recycle()
        }
    }

    suspend fun saveTags(
        edit: TrackTagEdit,
        writeConsentGranted: Boolean = false,
    ): TagWriteResult = withContext(Dispatchers.IO) {
        val cleanTitle = edit.title.trim().ifBlank { "Untitled Track" }
        val cleanArtist = edit.artist.trim().ifBlank { "Unknown Artist" }
        val cleanAlbum = edit.album.trim().ifBlank { "Unknown Album" }
        val cleanTrackNumber = edit.trackNumber.coerceAtLeast(0)
        val cleanYear = edit.year.coerceAtLeast(0)
        val cleanGenre = edit.genre.trim()

        localMusicRepository.updateTrackMetadata(
            trackId = edit.trackId,
            title = cleanTitle,
            artist = cleanArtist,
            album = cleanAlbum,
            trackNumber = cleanTrackNumber,
            year = cleanYear,
            genre = cleanGenre,
            artworkUri = edit.artworkUri,
        )

        if (edit.isCueTrack) {
            return@withContext TagWriteResult.UpdatedLocallyOnly("Updated CUE track metadata in Lunaria")
        }

        val uri = runCatching { Uri.parse(edit.contentUri) }.getOrNull()
            ?: return@withContext TagWriteResult.UpdatedLocallyOnly("Saved in Lunaria library")

        val coverBytes = if (edit.customArtworkChanged && edit.artworkUri != null) {
            readArtworkJpegBytes(edit.artworkUri)
        } else {
            null
        }
        val patch = FileTagPatch(
            title = cleanTitle,
            artist = cleanArtist,
            album = cleanAlbum,
            trackNumber = cleanTrackNumber,
            year = cleanYear,
            genre = cleanGenre,
            coverArtJpegBytes = coverBytes,
        )

        return@withContext when (writeIntoAudioContainer(uri, patch)) {
            ContainerWriteStatus.SECURITY_DENIED -> {
                if (!writeConsentGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val sender = runCatching {
                        MediaStore.createWriteRequest(context.contentResolver, listOf(uri)).intentSender
                    }.getOrNull()
                    if (sender != null) {
                        return@withContext TagWriteResult.RequiresWriteConsent(sender)
                    }
                }
                TagWriteResult.UpdatedLocallyOnly("Saved in Lunaria library (system file write denied)")
            }

            ContainerWriteStatus.WRITTEN -> {
                refreshMediaStoreEntry(uri, cleanTitle)
                TagWriteResult.Updated
            }

            ContainerWriteStatus.UNSUPPORTED_FORMAT -> {
                val consentSender = trySafeMediaStoreTitleUpdate(uri, cleanTitle, writeConsentGranted)
                if (consentSender != null) {
                    TagWriteResult.RequiresWriteConsent(consentSender)
                } else {
                    TagWriteResult.UpdatedLocallyOnly("Saved in Lunaria library (container format tags read-only)")
                }
            }

            ContainerWriteStatus.FAILED ->
                TagWriteResult.UpdatedLocallyOnly("Saved in Lunaria library (could not rewrite audio file)")
        }
    }

    private fun refreshMediaStoreEntry(uri: Uri, cleanTitle: String) {
        val filePath = resolveFilePath(uri)
        if (!filePath.isNullOrBlank()) {
            runCatching { MediaScannerConnection.scanFile(context, arrayOf(filePath), null, null) }
        }
        runCatching {
            val values = ContentValues().apply { put(MediaStore.Audio.Media.TITLE, cleanTitle) }
            context.contentResolver.update(uri, values, null, null)
        }
    }

    private fun trySafeMediaStoreTitleUpdate(
        uri: Uri,
        cleanTitle: String,
        writeConsentGranted: Boolean,
    ): IntentSender? {
        return try {
            val values = ContentValues().apply { put(MediaStore.Audio.Media.TITLE, cleanTitle) }
            context.contentResolver.update(uri, values, null, null)
            null
        } catch (_: SecurityException) {
            if (!writeConsentGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    MediaStore.createWriteRequest(context.contentResolver, listOf(uri)).intentSender
                }.getOrNull()
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveFilePath(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media.DATA),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun writeIntoAudioContainer(uri: Uri, patch: FileTagPatch): ContainerWriteStatus {
        val tempDir = File(context.cacheDir, "tag_write").apply { mkdirs() }
        val inTemp = File(tempDir, "in_${System.nanoTime()}.tmp")
        val outTemp = File(tempDir, "out_${System.nanoTime()}.tmp")
        return try {
            val copied = try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(inTemp).use { out ->
                        input.copyTo(out, bufferSize = 64 * 1024)
                    }
                    true
                } ?: false
            } catch (_: SecurityException) {
                return ContainerWriteStatus.SECURITY_DENIED
            }
            if (!copied || inTemp.length() < 12L) return ContainerWriteStatus.FAILED

            when (AudioFileTagWriter.write(inTemp, outTemp, patch)) {
                FileTagWriteOutcome.UNSUPPORTED_FORMAT -> return ContainerWriteStatus.UNSUPPORTED_FORMAT
                FileTagWriteOutcome.FAILED -> return ContainerWriteStatus.FAILED
                FileTagWriteOutcome.WRITTEN -> Unit
            }
            if (!outTemp.exists() || outTemp.length() < 12L) return ContainerWriteStatus.FAILED

            val outStream = try {
                context.contentResolver.openOutputStream(uri, "rwt")
                    ?: context.contentResolver.openOutputStream(uri, "wt")
                    ?: context.contentResolver.openOutputStream(uri, "w")
            } catch (_: SecurityException) {
                return ContainerWriteStatus.SECURITY_DENIED
            } ?: return ContainerWriteStatus.FAILED

            outStream.use { dest ->
                FileInputStream(outTemp).use { src ->
                    src.copyTo(dest, bufferSize = 64 * 1024)
                }
            }
            ContainerWriteStatus.WRITTEN
        } catch (_: SecurityException) {
            ContainerWriteStatus.SECURITY_DENIED
        } catch (_: Throwable) {
            ContainerWriteStatus.FAILED
        } finally {
            inTemp.delete()
            outTemp.delete()
        }
    }

    private enum class ContainerWriteStatus {
        WRITTEN,
        UNSUPPORTED_FORMAT,
        SECURITY_DENIED,
        FAILED,
    }

    private fun readArtworkJpegBytes(artworkUriString: String): ByteArray? = runCatching {
        val parsed = Uri.parse(artworkUriString)
        val bytes = if (parsed.scheme == "file" && !parsed.path.isNullOrBlank()) {
            File(parsed.path!!).takeIf { it.isFile && it.length() <= MAX_EMBEDDED_COVER_BYTES }?.readBytes()
        } else {
            context.contentResolver.openInputStream(parsed)?.use { it.readBytes() }
        }
        bytes?.takeIf { it.isNotEmpty() && it.size <= MAX_EMBEDDED_COVER_BYTES }
    }.getOrNull()

    private fun computeInSampleSize(srcW: Int, srcH: Int, maxW: Int, maxH: Int): Int {
        var sample = 1
        while ((srcW / sample) > maxW || (srcH / sample) > maxH) {
            sample *= 2
        }
        return sample
    }

    private companion object {
        const val MAX_ARTWORK_PX = 1024
        const val MAX_EMBEDDED_COVER_BYTES = 4 * 1024 * 1024
    }
}
