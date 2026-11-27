package com.lunaria.core.audioengine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.lunaria.core.model.AudioCapabilitiesSnapshot
import com.lunaria.core.model.AudioOutputMode
import com.lunaria.core.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BitPerfectStatus(
    val isBitPerfectActive: Boolean = false,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
)

data class AudioRouteSnapshot(
    val capabilities: AudioCapabilitiesSnapshot,
    val bitPerfectStatus: BitPerfectStatus = BitPerfectStatus(),
)

/**
 * Monitors audio output device changes (USB DAC, Bluetooth, wired headphones) and manages
 * Android 14+ (API 34+) `MIXER_BEHAVIOR_BIT_PERFECT` preferences for connected USB DACs.
 */
@Singleton
class UsbBitPerfectManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilitiesInspector: AudioCapabilitiesInspector,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _routeSnapshot = MutableStateFlow(
        AudioRouteSnapshot(
            capabilities = AudioCapabilitiesSnapshot(
                apiLevel = Build.VERSION.SDK_INT,
                supportsFloatOutput = true,
                supportsDirectPlayback = false,
                supportsBitPerfectUsb = false,
                activeOutputSummary = "Detecting audio output…",
            ),
        ),
    )
    val routeSnapshot: StateFlow<AudioRouteSnapshot> = _routeSnapshot.asStateFlow()

    init {
        refreshSnapshot()
    }

    private val _usbDisconnectedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
    val usbDisconnectedEvents: SharedFlow<Unit> = _usbDisconnectedEvents.asSharedFlow()

    private var callbackRegistered = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshSnapshot()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            val usbRemoved = removedDevices?.any { capabilitiesInspector.isUsbDevice(it) } == true
            refreshSnapshot()
            if (usbRemoved) {
                _usbDisconnectedEvents.tryEmit(Unit)
            }
        }
    }

    fun startMonitoring() {
        if (callbackRegistered) return
        val manager = audioManager ?: return
        runCatching {
            manager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
            callbackRegistered = true
        }
        refreshSnapshot()
    }

    fun stopMonitoring() {
        if (!callbackRegistered) return
        runCatching { audioManager?.unregisterAudioDeviceCallback(deviceCallback) }
        callbackRegistered = false
    }

    fun refreshSnapshot() {
        scope.launch {
            val currentStatus = _routeSnapshot.value.bitPerfectStatus
            val freshCaps = capabilitiesInspector.inspect()
            _routeSnapshot.value = AudioRouteSnapshot(
                capabilities = freshCaps,
                bitPerfectStatus = if (freshCaps.supportsBitPerfectUsb) currentStatus else BitPerfectStatus(),
            )
        }
    }

    /**
     * Requests Bit-Perfect USB mixer attributes matching [track] on API 34+ when in Purist mode,
     * or clears them when switching back to DSP 32-bit Float.
     */
    suspend fun applyMixerForTrack(mode: AudioOutputMode, track: Track?): BitPerfectStatus =
        withContext(Dispatchers.IO) {
            val manager = audioManager
            if (manager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val inactive = BitPerfectStatus()
                _routeSnapshot.value = _routeSnapshot.value.copy(bitPerfectStatus = inactive)
                return@withContext inactive
            }

            val usbDevice = capabilitiesInspector.getOutputDevicesSafely(manager)
                .firstOrNull { capabilitiesInspector.isUsbDevice(it) }
            if (usbDevice == null) {
                val inactive = BitPerfectStatus()
                _routeSnapshot.value = AudioRouteSnapshot(capabilitiesInspector.inspect(), inactive)
                return@withContext inactive
            }

            val mediaAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            if (mode != AudioOutputMode.PURIST_DIRECT_BIT_PERFECT) {
                runCatching { manager.clearPreferredMixerAttributes(mediaAttributes, usbDevice) }
                val inactive = BitPerfectStatus()
                _routeSnapshot.value = AudioRouteSnapshot(capabilitiesInspector.inspect(), inactive)
                return@withContext inactive
            }

            val status = runCatching {
                val supported = manager.getSupportedMixerAttributes(usbDevice)
                val candidates = supported.map { attr ->
                    MixerCandidate(
                        encoding = attr.format.encoding,
                        sampleRateHz = attr.format.sampleRate,
                        channelMask = attr.format.channelMask,
                        isBitPerfect = attr.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT,
                    )
                }
                val targetRate = track?.audioSpec?.sampleRateHz ?: 48_000
                val targetBits = track?.audioSpec?.bitDepth ?: 24
                val chosen = BitPerfectMixerSelector.selectBestBitPerfectCandidate(
                    candidates = candidates,
                    targetSampleRateHz = targetRate,
                    targetBitDepth = targetBits,
                )
                val chosenAttr = chosen?.let { match ->
                    supported.firstOrNull { attr ->
                        attr.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                            attr.format.sampleRate == match.sampleRateHz &&
                            attr.format.encoding == match.encoding &&
                            attr.format.channelMask == match.channelMask
                    }
                }
                if (chosenAttr != null &&
                    manager.setPreferredMixerAttributes(mediaAttributes, usbDevice, chosenAttr)
                ) {
                    BitPerfectStatus(
                        isBitPerfectActive = true,
                        sampleRateHz = chosenAttr.format.sampleRate,
                        bitDepth = BitPerfectMixerSelector.encodingBitDepth(chosenAttr.format.encoding),
                    )
                } else {
                    BitPerfectStatus()
                }
            }.getOrDefault(BitPerfectStatus())

            _routeSnapshot.value = AudioRouteSnapshot(capabilitiesInspector.inspect(), status)
            status
        }
}
