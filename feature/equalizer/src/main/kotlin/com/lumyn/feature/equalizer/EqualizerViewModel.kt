package com.lumyn.feature.equalizer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
        viewModelScope.launch {
            preferencesDataSource.setAudioOutputMode(AudioOutputMode.DSP_FLOAT_32)
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesDataSource.updateEqualizer { it.copy(enabled = enabled) }
        }
    }

    fun selectPreset(preset: EqualizerPreset) {
        viewModelScope.launch {
            preferencesDataSource.updateEqualizer { current ->
                current.copy(
                    enabled = true,
                    preampDb = preset.preampDb,
                    bandGainsDb = preset.bandGainsDb,
                    parametricFilters = emptyList(),
                    activePresetName = preset.name,
                )
            }
        }
    }

    fun setPreampDb(preampDb: Float) {
        val snapped = snapHalfDb(preampDb).coerceIn(EqualizerSettings.MIN_GAIN_DB, EqualizerSettings.MAX_GAIN_DB)
        viewModelScope.launch {
            preferencesDataSource.updateEqualizer { it.copy(preampDb = snapped) }
        }
    }

    fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex !in 0..9) return
        val snapped = snapHalfDb(gainDb).coerceIn(EqualizerSettings.MIN_GAIN_DB, EqualizerSettings.MAX_GAIN_DB)
        viewModelScope.launch {
            preferencesDataSource.updateEqualizer { current ->
                val updated = current.normalizedBandGains().toMutableList()
                updated[bandIndex] = snapped
                current.copy(
                    enabled = true,
                    bandGainsDb = updated,
                    activePresetName = "Custom",
                )
            }
        }
    }

    fun importAutoEq(rawText: String, fileName: String) {
        val parsed = AutoEqParser.parse(rawText, fallbackName = fileName)
        if (parsed == null) {
            statusMessage.value = "Could not parse AutoEQ filters from \"$fileName\"."
            return
        }
        viewModelScope.launch {
            preferencesDataSource.updateEqualizer { current ->
                current.copy(
                    enabled = true,
                    preampDb = parsed.preampDb,
                    bandGainsDb = parsed.bandGainsDb,
                    parametricFilters = parsed.parametricFilters,
                    activePresetName = parsed.name,
                )
            }
            val filterSummary = if (parsed.parametricFilters.isNotEmpty()) {
                "${parsed.parametricFilters.size} parametric filters"
            } else {
                "10-band GraphicEQ mapping"
            }
            statusMessage.value = "Imported AutoEQ profile \"${parsed.name}\" ($filterSummary)."
        }
    }

    fun clearParametricFilters() {
        viewModelScope.launch {
            preferencesDataSource.updateEqualizer { current ->
                current.copy(
                    parametricFilters = emptyList(),
                    activePresetName = "Custom",
                )
            }
            statusMessage.value = "Cleared parametric AutoEQ filters."
        }
    }

    fun setReplayGainMode(mode: ReplayGainMode) {
        viewModelScope.launch {
            preferencesDataSource.updateEqualizer { it.copy(replayGainMode = mode) }
        }
    }

    fun setReplayGainPreampDb(preampDb: Float) {
        val snapped = snapHalfDb(preampDb).coerceIn(-6f, 6f)
        viewModelScope.launch {
            preferencesDataSource.updateEqualizer { it.copy(replayGainPreampDb = snapped) }
        }
    }

    fun setChannelBalance(balance: Float) {
        val snapped = ((balance * 20f).roundToInt() / 20f).coerceIn(-1f, 1f)
        viewModelScope.launch {
            preferencesDataSource.updateEqualizer { it.copy(channelBalance = snapped) }
        }
    }

    fun setMonoAudio(mono: Boolean) {
        viewModelScope.launch {
            preferencesDataSource.updateEqualizer { it.copy(monoAudio = mono) }
        }
    }

    fun setCrossfadeDurationSec(seconds: Int) {
        val clamped = seconds.coerceIn(0, EqualizerSettings.MAX_CROSSFADE_SEC)
        viewModelScope.launch {
            preferencesDataSource.updateEqualizer { it.copy(crossfadeDurationSec = clamped) }
        }
    }

    fun resetToFlat() {
        viewModelScope.launch {
            preferencesDataSource.updateEqualizer { current ->
                current.copy(
                    enabled = false,
                    preampDb = 0f,
                    bandGainsDb = List(10) { 0f },
                    parametricFilters = emptyList(),
                    activePresetName = "Flat",
                )
            }
            statusMessage.value = "Equalizer reset to Flat."
        }
    }

    fun clearStatusMessage() {
        statusMessage.value = null
    }

    private fun snapHalfDb(value: Float): Float = (value * 2f).roundToInt() / 2f
}
