package com.lumyn.core.audioengine

import com.lumyn.core.model.AudioOutputMode
import com.lumyn.core.model.AudioSignalPath
import com.lumyn.core.model.EqualizerSettings
import com.lumyn.core.model.Track
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shares live decoder/AudioTrack telemetry from [LumynPlaybackService] with [LumynPlaybackController]
 * and the UI layer without coupling UI composables directly to ExoPlayer internals.
 */
@Singleton
class AudioSignalPathTracker @Inject constructor(
    private val usbBitPerfectManager: UsbBitPerfectManager,
) {
    private val _signalPath = MutableStateFlow<AudioSignalPath?>(null)
    val signalPath: StateFlow<AudioSignalPath?> = _signalPath.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private var activeTrack: Track? = null
    private var activeMode: AudioOutputMode = AudioOutputMode.DSP_FLOAT_32
    private var activeEqualizer: EqualizerSettings = EqualizerSettings()
    private var telemetry = RuntimeAudioTelemetry()

    fun updateTrackAndMode(track: Track?, mode: AudioOutputMode) {
        activeTrack = track
        activeMode = mode
        recompute()
    }

    fun updateEqualizerSettings(settings: EqualizerSettings) {
        activeEqualizer = settings
        recompute()
    }

    fun updateDecoder(decoderName: String) {
        telemetry = telemetry.copy(decoderName = decoderName)
        recompute()
    }

    fun updateAudioTrack(encoding: Int, sampleRateHz: Int, offload: Boolean) {
        telemetry = telemetry.copy(
            trackEncoding = encoding,
            trackSampleRateHz = sampleRateHz,
            isOffload = offload,
        )
        recompute()
    }

    fun onRouteChanged() {
        recompute()
    }

    fun emitNotice(message: String) {
        _notices.tryEmit(message)
    }

    private fun recompute() {
        val route = usbBitPerfectManager.routeSnapshot.value
        _signalPath.value = AudioSignalPathBuilder.build(
            track = activeTrack,
            mode = activeMode,
            telemetry = telemetry,
            capabilities = route.capabilities,
            bitPerfectStatus = route.bitPerfectStatus,
            equalizerSettings = activeEqualizer,
        )
    }
}
