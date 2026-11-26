package com.lumyn.feature.equalizer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumyn.core.audioengine.LumynDspConfigStore
import com.lumyn.core.database.preferences.LumynPreferencesDataSource
import com.lumyn.core.model.AudioOutputMode
import com.lumyn.core.model.AutoEqParser
import com.lumyn.core.model.EqualizerPreset
import com.lumyn.core.model.EqualizerSettings
import com.lumyn.core.model.ReplayGainMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EqualizerUiState(
    val outputMode: AudioOutputMode = AudioOutputMode.DSP_FLOAT_32,
    val settings: EqualizerSettings = EqualizerSettings(),
    val statusMessage: String? = null,
)

@HiltViewModel
class EqualizerViewModel @Inject constructor(
    private val preferencesDataSource: LumynPreferencesDataSource,
    private val dspConfigStore: LumynDspConfigStore,
) : ViewModel() {

    private val statusMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<EqualizerUiState> = combine(
        preferencesDataSource.preferences,
        statusMessage,
    ) { prefs, msg ->
        EqualizerUiState(
            outputMode = prefs.audioOutputMode,
            settings = prefs.equalizer,
            statusMessage = msg,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = EqualizerUiState(),
    )

    fun switchToDspMode() {
        dspConfigStore.updateBypassed(false)
        viewModelScope.launch {
            preferencesDataSource.setAudioOutputMode(AudioOutputMode.DSP_FLOAT_32)
        }
    }

    fun setEnabled(enabled: Boolean) {
        applyEqualizerChange { it.copy(enabled = enabled) }
    }

    fun selectPreset(preset: EqualizerPreset) {
        applyEqualizerChange { current ->
            current.copy(
                enabled = true,
                preampDb = preset.preampDb,
                bandGainsDb = preset.bandGainsDb,
                parametricFilters = emptyList(),
                activePresetName = preset.name,
            )
        }
    }

    fun previewPreampDb(preampDb: Float) {
        val snapped = snapHalfDb(preampDb).coerceIn(EqualizerSettings.MIN_GAIN_DB, EqualizerSettings.MAX_GAIN_DB)
        dspConfigStore.updateEqualizerSettings { it.copy(preampDb = snapped) }
    }

    fun setPreampDb(preampDb: Float) {
        val snapped = snapHalfDb(preampDb).coerceIn(EqualizerSettings.MIN_GAIN_DB, EqualizerSettings.MAX_GAIN_DB)
        applyEqualizerChange { it.copy(preampDb = snapped) }
    }

    fun previewBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex !in 0..9) return
        val snapped = snapHalfDb(gainDb).coerceIn(EqualizerSettings.MIN_GAIN_DB, EqualizerSettings.MAX_GAIN_DB)
        dspConfigStore.updateEqualizerSettings(bandGainTransform(bandIndex, snapped))
    }

    fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex !in 0..9) return
        val snapped = snapHalfDb(gainDb).coerceIn(EqualizerSettings.MIN_GAIN_DB, EqualizerSettings.MAX_GAIN_DB)
        applyEqualizerChange(transform = bandGainTransform(bandIndex, snapped))
    }

    fun importAutoEq(rawText: String, fileName: String) {
        val parsed = AutoEqParser.parse(rawText, fallbackName = fileName)
        if (parsed == null) {
            statusMessage.value = "Could not parse AutoEQ filters from \"$fileName\"."
            return
        }
        val filterSummary = if (parsed.parametricFilters.isNotEmpty()) {
            "${parsed.parametricFilters.size} parametric filters"
        } else {
            "10-band GraphicEQ mapping"
        }
        applyEqualizerChange(
            onPersisted = {
                statusMessage.value = "Imported AutoEQ profile \"${parsed.name}\" ($filterSummary)."
            },
        ) { current ->
            current.copy(
                enabled = true,
                preampDb = parsed.preampDb,
                bandGainsDb = parsed.bandGainsDb,
                parametricFilters = parsed.parametricFilters,
                activePresetName = parsed.name,
            )
        }
    }

    fun clearParametricFilters() {
        applyEqualizerChange(
            onPersisted = { statusMessage.value = "Cleared parametric AutoEQ filters." },
        ) { current ->
            current.copy(
                parametricFilters = emptyList(),
                activePresetName = "Custom",
            )
        }
    }

    fun setReplayGainMode(mode: ReplayGainMode) {
        applyEqualizerChange { it.copy(replayGainMode = mode) }
    }

    fun previewReplayGainPreampDb(preampDb: Float) {
        val snapped = snapHalfDb(preampDb).coerceIn(-6f, 6f)
        dspConfigStore.updateEqualizerSettings { it.copy(replayGainPreampDb = snapped) }
    }

    fun setReplayGainPreampDb(preampDb: Float) {
        val snapped = snapHalfDb(preampDb).coerceIn(-6f, 6f)
        applyEqualizerChange { it.copy(replayGainPreampDb = snapped) }
    }

    fun previewChannelBalance(balance: Float) {
        val snapped = snapBalance(balance)
        dspConfigStore.updateEqualizerSettings { it.copy(channelBalance = snapped) }
    }

    fun setChannelBalance(balance: Float) {
        val snapped = snapBalance(balance)
        applyEqualizerChange { it.copy(channelBalance = snapped) }
    }

    fun setMonoAudio(mono: Boolean) {
        applyEqualizerChange { it.copy(monoAudio = mono) }
    }

    fun setCrossfadeDurationSec(seconds: Int) {
        val clamped = seconds.coerceIn(0, EqualizerSettings.MAX_CROSSFADE_SEC)
        applyEqualizerChange { it.copy(crossfadeDurationSec = clamped) }
    }

    fun resetToFlat() {
        applyEqualizerChange(
            onPersisted = { statusMessage.value = "Equalizer reset to Flat." },
        ) { current ->
            current.copy(
                enabled = false,
                preampDb = 0f,
                bandGainsDb = List(10) { 0f },
                parametricFilters = emptyList(),
                activePresetName = "Flat",
            )
        }
    }

    fun clearStatusMessage() {
        statusMessage.value = null
    }

    private fun applyEqualizerChange(
        onPersisted: (() -> Unit)? = null,
        transform: (EqualizerSettings) -> EqualizerSettings,
    ) {
        dspConfigStore.updateEqualizerSettings(transform)
        viewModelScope.launch {
            preferencesDataSource.updateEqualizer(transform)
            onPersisted?.invoke()
        }
    }

    private fun bandGainTransform(bandIndex: Int, snappedDb: Float): (EqualizerSettings) -> EqualizerSettings =
        { current ->
            val updated = current.normalizedBandGains().toMutableList()
            updated[bandIndex] = snappedDb
            current.copy(
                enabled = true,
                bandGainsDb = updated,
                activePresetName = "Custom",
            )
        }

    private fun snapHalfDb(value: Float): Float = (value * 2f).roundToInt() / 2f

    private fun snapBalance(balance: Float): Float = ((balance * 20f).roundToInt() / 20f).coerceIn(-1f, 1f)
}
