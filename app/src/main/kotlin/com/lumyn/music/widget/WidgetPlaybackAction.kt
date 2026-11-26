package com.lumyn.music.widget

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.lumyn.core.audioengine.LumynPlaybackService
import com.lumyn.core.audioengine.toMediaItem
import com.lumyn.core.database.preferences.LumynPreferencesDataSource
import com.lumyn.core.database.repository.LocalMusicRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Public zero-arg [ActionCallback] connecting safely to [LumynPlaybackService] via Media3 [MediaController]
 * within a strict 4-second timeout (no unsafe `startForegroundService()` shortcuts).
 */
class WidgetPlaybackAction : ActionCallback {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetRepositoryEntryPoint {
        fun localMusicRepository(): LocalMusicRepository
        fun preferencesDataSource(): LumynPreferencesDataSource
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val command = parameters[CommandParamKey] ?: CMD_PLAY_PAUSE
        val token = SessionToken(context, ComponentName(context, LumynPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()

        val controller = withTimeoutOrNull(4_000L) {
            future.awaitMain(context)
        } ?: run {
            MediaController.releaseFuture(future)
            return
        }

        try {
            withContext(Dispatchers.Main.immediate) {
                when (command) {
                    CMD_NEXT -> if (controller.hasNextMediaItem()) controller.seekToNext()
                    CMD_PREVIOUS -> controller.seekToPrevious()
                    CMD_PLAY_PAUSE -> handlePlayPause(context, controller)
                }
            }
        } finally {
            withContext(Dispatchers.Main.immediate) {
                controller.release()
            }
        }
    }

    private suspend fun handlePlayPause(context: Context, controller: MediaController) {
        if (controller.isPlaying) {
            controller.pause()
            return
        }
        if (controller.mediaItemCount > 0) {
            if (controller.playbackState == Player.STATE_IDLE) {
                controller.prepare()
            }
            controller.play()
            return
        }
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetRepositoryEntryPoint::class.java,
        )
        val restored = withContext(Dispatchers.IO) {
            runCatching {
                val repo = entryPoint.localMusicRepository()
                val saved = entryPoint.preferencesDataSource().persistedQueue.first()
                if (saved.trackIds.isNotEmpty()) {
                    val tracks = repo.getTracksByIds(saved.trackIds)
                    if (tracks.isNotEmpty()) {
                        val safeIdx = saved.currentIndex.coerceIn(0, tracks.lastIndex)
                        return@runCatching Triple(tracks, safeIdx, saved.positionMs)
                    }
                }
                val fallback = repo.tracks.first()
                if (fallback.isNotEmpty()) Triple(fallback, 0, C.TIME_UNSET) else null
            }.getOrNull()
        }
        if (restored != null) {
            val (tracks, startIndex, startPosMs) = restored
            controller.setMediaItems(tracks.map { it.toMediaItem() }, startIndex, startPosMs)
            controller.prepare()
            controller.play()
        }
    }

    private suspend fun <T> ListenableFuture<T>.awaitMain(context: Context): T =
        suspendCancellableCoroutine { cont ->
            addListener(
                {
                    try {
                        cont.resume(get())
                    } catch (t: Throwable) {
                        cont.resumeWithException(t)
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
            cont.invokeOnCancellation { cancel(false) }
        }

    companion object {
        val CommandParamKey = ActionParameters.Key<String>("lumyn_widget_command")
        const val CMD_PLAY_PAUSE = "PLAY_PAUSE"
        const val CMD_NEXT = "NEXT"
        const val CMD_PREVIOUS = "PREVIOUS"
    }
}
