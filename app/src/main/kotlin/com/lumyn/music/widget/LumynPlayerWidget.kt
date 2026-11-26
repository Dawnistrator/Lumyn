package com.lumyn.music.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.lumyn.core.designsystem.icon.LumynIcons
import com.lumyn.core.designsystem.theme.LumynDarkColorScheme
import com.lumyn.core.designsystem.theme.LumynLightColorScheme
import com.lumyn.music.MainActivity
import com.lumyn.music.R

internal object WidgetPrefKeys {
    val TrackId = longPreferencesKey("widget_track_id")
    val Title = stringPreferencesKey("widget_title")
    val Artist = stringPreferencesKey("widget_artist")
    val Quality = stringPreferencesKey("widget_quality")
    val ArtworkUri = stringPreferencesKey("widget_artwork_uri")
    val IsPlaying = booleanPreferencesKey("widget_is_playing")
}

private val LumynGlanceColors = ColorProviders(
    light = LumynLightColorScheme,
    dark = LumynDarkColorScheme,
)

class LumynPlayerWidget : GlanceAppWidget(errorUiLayout = R.layout.widget_error_fallback) {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(SMALL_SIZE, MEDIUM_SIZE, LARGE_SIZE),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initialPrefs = runCatching {
            getAppWidgetState<Preferences>(context, PreferencesGlanceStateDefinition, id)
        }.getOrNull()
        val preDecodedUri = initialPrefs?.get(WidgetPrefKeys.ArtworkUri)
        val capBytes = WidgetBitmapUtils.calculateWidgetMemoryCapBytes(context)
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val targetArtPx = (96f * density).toInt().coerceIn(96, 320)
        val preDecodedBitmap = WidgetBitmapUtils.decodeDownsampledArtwork(
            context = context,
            uriString = preDecodedUri,
            targetSizePx = targetArtPx,
            maxBudgetBytes = capBytes,
        )

        provideContent {
            val prefs = currentState<Preferences>()
            val title = prefs[WidgetPrefKeys.Title]?.takeIf { it.isNotBlank() } ?: "Lumyn"
            val artist = prefs[WidgetPrefKeys.Artist]?.takeIf { it.isNotBlank() } ?: "Ready for offline Hi-Res playback"
            val quality = prefs[WidgetPrefKeys.Quality].orEmpty()
            val artworkUri = prefs[WidgetPrefKeys.ArtworkUri]
            val isPlaying = prefs[WidgetPrefKeys.IsPlaying] ?: false
            val artBitmap = if (artworkUri != null && artworkUri == preDecodedUri) preDecodedBitmap else null

            GlanceTheme(colors = LumynGlanceColors) {
                WidgetContent(
                    title = title,
                    artist = artist,
                    quality = quality,
                    artworkBitmap = artBitmap,
                    isPlaying = isPlaying,
                )
            }
        }
    }

    override fun onCompositionError(
        context: Context,
        glanceId: GlanceId,
        appWidgetId: Int,
        throwable: Throwable,
    ) {
        val fallbackViews = RemoteViews(context.packageName, R.layout.widget_error_fallback)
        AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, fallbackViews)
    }

    companion object {
        private val SMALL_SIZE = DpSize(180.dp, 64.dp)
        private val MEDIUM_SIZE = DpSize(250.dp, 96.dp)
        private val LARGE_SIZE = DpSize(300.dp, 172.dp)
    }
}

@Composable
private fun WidgetContent(
    title: String,
    artist: String,
    quality: String,
    artworkBitmap: Bitmap?,
    isPlaying: Boolean,
) {
    val size = LocalSize.current
    val isCompactHeight = size.height < 130.dp
    val openAppAction = actionStartActivity<MainActivity>()

    val rootModifier = GlanceModifier
        .fillMaxSize()
        .appWidgetBackground()
        .background(GlanceTheme.colors.widgetBackground)
        .cornerRadius(24.dp)
        .padding(12.dp)
        .clickable(openAppAction)

    if (isCompactHeight) {
        Row(
            modifier = rootModifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WidgetArtworkThumb(artworkBitmap = artworkBitmap, size = 48.dp)
            Spacer(GlanceModifier.width(12.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = title,
                    maxLines = 1,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                Text(
                    text = artist,
                    maxLines = 1,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
            Spacer(GlanceModifier.width(8.dp))
            if (size.width >= 240.dp) {
                WidgetControlButton(
                    iconRes = LumynIcons.SkipPrevious,
                    contentDescription = "Previous track",
                    command = WidgetPlaybackAction.CMD_PREVIOUS,
                    prominent = false,
                )
                Spacer(GlanceModifier.width(6.dp))
            }
            WidgetControlButton(
                iconRes = if (isPlaying) LumynIcons.Pause else LumynIcons.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                command = WidgetPlaybackAction.CMD_PLAY_PAUSE,
                prominent = true,
            )
            Spacer(GlanceModifier.width(6.dp))
            WidgetControlButton(
                iconRes = LumynIcons.SkipNext,
                contentDescription = "Next track",
                command = WidgetPlaybackAction.CMD_NEXT,
                prominent = false,
            )
        }
    } else {
        Column(
            modifier = rootModifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WidgetArtworkThumb(artworkBitmap = artworkBitmap, size = 64.dp)
                Spacer(GlanceModifier.width(12.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = title,
                        maxLines = 1,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                    Text(
                        text = artist,
                        maxLines = 1,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                    if (quality.isNotBlank()) {
                        Spacer(GlanceModifier.height(2.dp))
                        Text(
                            text = quality,
                            maxLines = 1,
                            style = TextStyle(
                                color = GlanceTheme.colors.primary,
                                fontWeight = FontWeight.Medium,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                }
            }
            Spacer(GlanceModifier.height(12.dp))
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WidgetControlButton(
                    iconRes = LumynIcons.SkipPrevious,
                    contentDescription = "Previous track",
                    command = WidgetPlaybackAction.CMD_PREVIOUS,
                    prominent = false,
                )
                Spacer(GlanceModifier.width(16.dp))
                WidgetControlButton(
                    iconRes = if (isPlaying) LumynIcons.Pause else LumynIcons.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    command = WidgetPlaybackAction.CMD_PLAY_PAUSE,
                    prominent = true,
                )
                Spacer(GlanceModifier.width(16.dp))
                WidgetControlButton(
                    iconRes = LumynIcons.SkipNext,
                    contentDescription = "Next track",
                    command = WidgetPlaybackAction.CMD_NEXT,
                    prominent = false,
                )
            }
        }
    }
}

@Composable
private fun WidgetArtworkThumb(
    artworkBitmap: Bitmap?,
    size: Dp,
) {
    Box(
        modifier = GlanceModifier
            .size(size)
            .cornerRadius(12.dp)
            .background(GlanceTheme.colors.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (artworkBitmap != null) {
            Image(
                provider = ImageProvider(artworkBitmap),
                contentDescription = "Album artwork",
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.fillMaxSize().cornerRadius(12.dp),
            )
        } else {
            Image(
                provider = ImageProvider(LumynIcons.MusicNote),
                contentDescription = "Album artwork placeholder",
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer),
                modifier = GlanceModifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun WidgetControlButton(
    iconRes: Int,
    contentDescription: String,
    command: String,
    prominent: Boolean,
) {
    val bgColor = if (prominent) GlanceTheme.colors.primary else GlanceTheme.colors.secondaryContainer
    val fgColor = if (prominent) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSecondaryContainer
    val size = if (prominent) 44.dp else 38.dp

    Box(
        modifier = GlanceModifier
            .size(size)
            .cornerRadius(if (prominent) 14.dp else 19.dp)
            .background(bgColor)
            .clickable(
                actionRunCallback<WidgetPlaybackAction>(
                    actionParametersOf(WidgetPlaybackAction.CommandParamKey to command),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = contentDescription,
            colorFilter = ColorFilter.tint(fgColor),
            modifier = GlanceModifier.size(20.dp),
        )
    }
}
