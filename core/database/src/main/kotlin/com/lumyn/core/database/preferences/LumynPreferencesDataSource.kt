package com.lumyn.core.database.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.lumyn.core.model.AudioOutputMode
import com.lumyn.core.model.DefaultExcludedFolders
import com.lumyn.core.model.EqualizerSettings
import com.lumyn.core.model.FolderFilterConfig
import com.lumyn.core.model.FolderFilterMode
import com.lumyn.core.model.ParametricFilter
import com.lumyn.core.model.ParametricFilterType
import com.lumyn.core.model.PersistedQueueState
import com.lumyn.core.model.RepeatMode
import com.lumyn.core.model.ReplayGainMode
import com.lumyn.core.model.StandardEqFrequenciesHz
import com.lumyn.core.model.ThemeMode
import com.lumyn.core.model.UserPreferences
import com.lumyn.core.model.clampPlaybackSpeed
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class LumynPreferencesDataSource @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val preferences: Flow<UserPreferences> = dataStore.data.map { it.toUserPreferences() }

    val persistedQueue: Flow<PersistedQueueState> = dataStore.data.map { it.toPersistedQueueState() }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.ThemeMode] = mode.name }
    }

    suspend fun setUseDynamicColor(enabled: Boolean) {
        dataStore.edit { it[Keys.UseDynamicColor] = enabled }
    }

    suspend fun setUseAlbumArtTheme(enabled: Boolean) {
        dataStore.edit { it[Keys.UseAlbumArtTheme] = enabled }
    }

    suspend fun setAudioOutputMode(mode: AudioOutputMode) {
        dataStore.edit { it[Keys.AudioOutputMode] = mode.name }
    }

    suspend fun setFolderFilterMode(mode: FolderFilterMode) {
        dataStore.edit { it[Keys.FolderFilterMode] = mode.name }
    }

    suspend fun toggleIncludedFolder(folderPath: String) {
        val normalized = FolderFilterConfig.normalizeFolderPath(folderPath)
        if (normalized.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[Keys.IncludedFolders] ?: emptySet()
            prefs[Keys.IncludedFolders] = toggleEntry(current, normalized)
        }
    }

    suspend fun toggleExcludedFolder(folderPath: String) {
        val normalized = FolderFilterConfig.normalizeFolderPath(folderPath)
        if (normalized.isEmpty()) return
        dataStore.edit { prefs ->
            val customized = prefs[Keys.FolderFilterCustomized] == true
            val current = if (customized) {
                prefs[Keys.ExcludedFolders] ?: emptySet()
            } else {
                DefaultExcludedFolders
            }
            prefs[Keys.FolderFilterCustomized] = true
            prefs[Keys.ExcludedFolders] = toggleEntry(current, normalized)
        }
    }

    suspend fun resetFolderFilter() {
        dataStore.edit { prefs ->
            prefs[Keys.FolderFilterMode] = FolderFilterMode.BLACKLIST.name
            prefs[Keys.IncludedFolders] = emptySet()
            prefs[Keys.ExcludedFolders] = DefaultExcludedFolders
            prefs[Keys.FolderFilterCustomized] = false
        }
    }

    suspend fun setEqualizer(settings: EqualizerSettings) {
        dataStore.edit { prefs ->
            writeEqualizerToPrefs(prefs, settings)
        }
    }

    suspend fun updateEqualizer(transform: (EqualizerSettings) -> EqualizerSettings) {
        dataStore.edit { prefs ->
            val current = readEqualizerFromPrefs(prefs)
            writeEqualizerToPrefs(prefs, transform(current))
        }
    }

    suspend fun savePersistedQueue(state: PersistedQueueState) {
        val maxSize = PersistedQueueState.MAX_PERSISTED_QUEUE_SIZE
        val boundedIds: List<Long>
        val boundedIndex: Int
        if (state.trackIds.isEmpty()) {
            boundedIds = emptyList()
            boundedIndex = 0
        } else if (state.trackIds.size > maxSize) {
            val safeIdx = state.currentIndex.coerceIn(0, state.trackIds.lastIndex)
            val start = (safeIdx - maxSize / 2).coerceIn(0, state.trackIds.size - maxSize)
            boundedIds = state.trackIds.subList(start, start + maxSize)
            boundedIndex = (safeIdx - start).coerceIn(0, boundedIds.lastIndex)
        } else {
            boundedIds = state.trackIds
            boundedIndex = state.currentIndex.coerceIn(0, boundedIds.lastIndex)
        }

        dataStore.edit { prefs ->
            prefs[Keys.QueueTrackIdsCsv] = boundedIds.joinToString(",")
            prefs[Keys.QueueCurrentIndex] = boundedIndex
            prefs[Keys.QueuePositionMs] = state.positionMs.coerceAtLeast(0L)
            prefs[Keys.QueueRepeatMode] = state.repeatMode.name
            prefs[Keys.QueueShuffleEnabled] = state.shuffleEnabled
            prefs[Keys.QueueSpeed] = clampPlaybackSpeed(state.speed)
        }
    }

    private fun writeEqualizerToPrefs(
        prefs: MutablePreferences,
        settings: EqualizerSettings,
    ) {
        prefs[Keys.EqEnabled] = settings.enabled
        prefs[Keys.EqPreampDb] = settings.preampDb.coerceIn(
            EqualizerSettings.MIN_GAIN_DB,
            EqualizerSettings.MAX_GAIN_DB,
        )
        prefs[Keys.EqBandsCsv] = settings.normalizedBandGains().joinToString(",") { it.toString() }
        prefs[Keys.EqParametricLines] = encodeParametricFilters(settings.parametricFilters)
        prefs[Keys.EqPresetName] = settings.activePresetName
        prefs[Keys.EqReplayGainMode] = settings.replayGainMode.name
        prefs[Keys.EqReplayGainPreampDb] = settings.replayGainPreampDb.coerceIn(-6f, 6f)
        prefs[Keys.EqChannelBalance] = settings.channelBalance.coerceIn(-1f, 1f)
        prefs[Keys.EqMonoAudio] = settings.monoAudio
        prefs[Keys.EqCrossfadeSec] = settings.crossfadeDurationSec.coerceIn(0, EqualizerSettings.MAX_CROSSFADE_SEC)
    }

    private fun readEqualizerFromPrefs(prefs: Preferences): EqualizerSettings {
        val defaults = EqualizerSettings()
        val bandsRaw = prefs[Keys.EqBandsCsv]
        val bands = if (bandsRaw != null) {
            val parsed = bandsRaw.split(',').map { it.toFloatOrNull() ?: 0f }
            List(StandardEqFrequenciesHz.size) { index ->
                parsed.getOrElse(index) { 0f }.coerceIn(
                    EqualizerSettings.MIN_GAIN_DB,
                    EqualizerSettings.MAX_GAIN_DB,
                )
            }
        } else {
            defaults.bandGainsDb
        }
        return EqualizerSettings(
            enabled = prefs[Keys.EqEnabled] ?: defaults.enabled,
            preampDb = (prefs[Keys.EqPreampDb] ?: defaults.preampDb).coerceIn(
                EqualizerSettings.MIN_GAIN_DB,
                EqualizerSettings.MAX_GAIN_DB,
            ),
            bandGainsDb = bands,
            parametricFilters = decodeParametricFilters(prefs[Keys.EqParametricLines]),
            activePresetName = prefs[Keys.EqPresetName] ?: defaults.activePresetName,
            replayGainMode = enumOrDefault(prefs[Keys.EqReplayGainMode], defaults.replayGainMode),
            replayGainPreampDb = (prefs[Keys.EqReplayGainPreampDb] ?: defaults.replayGainPreampDb).coerceIn(-6f, 6f),
            channelBalance = (prefs[Keys.EqChannelBalance] ?: defaults.channelBalance).coerceIn(-1f, 1f),
            monoAudio = prefs[Keys.EqMonoAudio] ?: defaults.monoAudio,
            crossfadeDurationSec = (prefs[Keys.EqCrossfadeSec] ?: defaults.crossfadeDurationSec)
                .coerceIn(0, EqualizerSettings.MAX_CROSSFADE_SEC),
        )
    }

    private fun encodeParametricFilters(filters: List<ParametricFilter>): String =
        filters.joinToString("\n") { filter ->
            "${filter.enabled}|${filter.type.name}|${filter.frequencyHz}|${filter.gainDb}|${filter.qFactor}"
        }

    private fun decodeParametricFilters(encoded: String?): List<ParametricFilter> {
        if (encoded.isNullOrBlank()) return emptyList()
        return encoded.lineSequence().mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size < 5) return@mapNotNull null
            val enabled = parts[0].toBooleanStrictOrNull() ?: true
            val type = enumOrDefault(parts[1], ParametricFilterType.PEAKING)
            val freq = parts[2].toDoubleOrNull() ?: return@mapNotNull null
            val gain = parts[3].toDoubleOrNull() ?: return@mapNotNull null
            val q = parts[4].toDoubleOrNull() ?: 0.707
            ParametricFilter(frequencyHz = freq, gainDb = gain, qFactor = q, type = type, enabled = enabled)
        }.toList()
    }

    private fun toggleEntry(entries: Set<String>, target: String): Set<String> {
        val existing = entries.firstOrNull { it.equals(target, ignoreCase = true) }
        return if (existing != null) entries - existing else entries + target
    }

    private object Keys {
        val ThemeMode = stringPreferencesKey("theme_mode")
        val UseDynamicColor = booleanPreferencesKey("use_dynamic_color")
        val UseAlbumArtTheme = booleanPreferencesKey("use_album_art_theme")
        val AudioOutputMode = stringPreferencesKey("audio_output_mode")
        val FolderFilterMode = stringPreferencesKey("folder_filter_mode")
        val IncludedFolders = stringSetPreferencesKey("included_folders")
        val ExcludedFolders = stringSetPreferencesKey("excluded_folders")
        val FolderFilterCustomized = booleanPreferencesKey("folder_filter_customized")

        val EqEnabled = booleanPreferencesKey("eq_enabled")
        val EqPreampDb = floatPreferencesKey("eq_preamp_db")
        val EqBandsCsv = stringPreferencesKey("eq_bands_csv")
        val EqParametricLines = stringPreferencesKey("eq_parametric_lines")
        val EqPresetName = stringPreferencesKey("eq_preset_name")
        val EqReplayGainMode = stringPreferencesKey("eq_replay_gain_mode")
        val EqReplayGainPreampDb = floatPreferencesKey("eq_replay_gain_preamp_db")
        val EqChannelBalance = floatPreferencesKey("eq_channel_balance")
        val EqMonoAudio = booleanPreferencesKey("eq_mono_audio")
        val EqCrossfadeSec = intPreferencesKey("eq_crossfade_sec")

        val QueueTrackIdsCsv = stringPreferencesKey("queue_track_ids_csv")
        val QueueCurrentIndex = intPreferencesKey("queue_current_index")
        val QueuePositionMs = longPreferencesKey("queue_position_ms")
        val QueueRepeatMode = stringPreferencesKey("queue_repeat_mode")
        val QueueShuffleEnabled = booleanPreferencesKey("queue_shuffle_enabled")
        val QueueSpeed = floatPreferencesKey("queue_speed")
    }

    private fun Preferences.toUserPreferences(): UserPreferences {
        val defaults = UserPreferences()
        val customized = this[Keys.FolderFilterCustomized] == true
        val excluded = if (customized) {
            this[Keys.ExcludedFolders] ?: emptySet()
        } else {
            DefaultExcludedFolders
        }
        return UserPreferences(
            themeMode = enumOrDefault(this[Keys.ThemeMode], defaults.themeMode),
            useDynamicColor = this[Keys.UseDynamicColor] ?: defaults.useDynamicColor,
            useAlbumArtTheme = this[Keys.UseAlbumArtTheme] ?: defaults.useAlbumArtTheme,
            audioOutputMode = enumOrDefault(this[Keys.AudioOutputMode], defaults.audioOutputMode),
            folderFilter = FolderFilterConfig(
                mode = enumOrDefault(this[Keys.FolderFilterMode], defaults.folderFilter.mode),
                includedFolders = this[Keys.IncludedFolders] ?: emptySet(),
                excludedFolders = excluded,
            ),
            equalizer = readEqualizerFromPrefs(this),
        )
    }

    private fun Preferences.toPersistedQueueState(): PersistedQueueState {
        val rawIds = this[Keys.QueueTrackIdsCsv].orEmpty()
        val ids = if (rawIds.isBlank()) {
            emptyList()
        } else {
            rawIds.split(',').mapNotNull { it.trim().toLongOrNull() }
        }
        val rawIndex = this[Keys.QueueCurrentIndex] ?: 0
        val safeIndex = if (ids.isEmpty()) 0 else rawIndex.coerceIn(0, ids.lastIndex)
        return PersistedQueueState(
            trackIds = ids,
            currentIndex = safeIndex,
            positionMs = (this[Keys.QueuePositionMs] ?: 0L).coerceAtLeast(0L),
            repeatMode = enumOrDefault(this[Keys.QueueRepeatMode], RepeatMode.OFF),
            shuffleEnabled = this[Keys.QueueShuffleEnabled] ?: false,
            speed = clampPlaybackSpeed(this[Keys.QueueSpeed] ?: 1.0f),
        )
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
