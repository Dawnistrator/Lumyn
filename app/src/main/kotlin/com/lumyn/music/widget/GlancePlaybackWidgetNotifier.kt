package com.lumyn.music.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.lumyn.core.audioengine.PlaybackWidgetNotifier
import com.lumyn.core.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Singleton
class GlancePlaybackWidgetNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : PlaybackWidgetNotifier {

    private data class Snapshot(
        val trackId: Long,
        val title: String,
        val artist: String,
        val quality: String,
        val artworkUri: String,
        val isPlaying: Boolean,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastSnapshot: Snapshot? = null

    override fun onPlaybackStateChanged(track: Track?, isPlaying: Boolean) {
        val next = Snapshot(
            trackId = track?.id ?: -1L,
            title = track?.title.orEmpty(),
            artist = track?.artist.orEmpty(),
            quality = track?.audioSpec?.formatQualitySummary().orEmpty(),
            artworkUri = track?.artworkUri.orEmpty(),
            isPlaying = isPlaying,
        )
        if (next == lastSnapshot) return
        lastSnapshot = next

        scope.launch {
            runCatching {
                val manager = GlanceAppWidgetManager(context)
                val glanceIds = manager.getGlanceIds(LumynPlayerWidget::class.java)
                if (glanceIds.isEmpty()) return@launch

                glanceIds.forEach { glanceId ->
                    updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                        prefs.toMutablePreferences().apply {
                            this[WidgetPrefKeys.TrackId] = next.trackId
                            this[WidgetPrefKeys.Title] = next.title
                            this[WidgetPrefKeys.Artist] = next.artist
                            this[WidgetPrefKeys.Quality] = next.quality
                            this[WidgetPrefKeys.ArtworkUri] = next.artworkUri
                            this[WidgetPrefKeys.IsPlaying] = next.isPlaying
                        }
                    }
                }
                LumynPlayerWidget().updateAll(context)
            }
        }
    }
}
