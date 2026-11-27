package com.lunaria.core.audioengine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import com.lunaria.core.model.AudioCapabilitiesSnapshot
import com.lunaria.core.model.AudioOutputCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Reads device output capabilities for Hi-Res audio. Safe across API 26-36. */
@Singleton
class AudioCapabilitiesInspector @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun inspect(): AudioCapabilitiesSnapshot {
        val sdk = Build.VERSION.SDK_INT
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val directFlag = if (sdk >= Build.VERSION_CODES.TIRAMISU) queryDirectPlayback() else 0

        val outputs = audioManager?.let(::getOutputDevicesSafely).orEmpty()
        val primaryDevice = selectPrimaryOutputDevice(outputs)
        val usbDevice = outputs.firstOrNull { isUsbDevice(it) }
        val deviceInfo = primaryDevice?.let {
            BitPerfectMixerSelector.describeAudioDeviceType(it.type, it.productName?.toString())
        } ?: OutputDeviceInfo("System Output", AudioOutputCategory.SPEAKER, isUsbDac = false)

        val bitPerfect = audioManager != null && usbDevice != null &&
            sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && queryBitPerfect(audioManager, usbDevice)

        return evaluateCapabilities(
            apiLevel = sdk,
            directSupportFlag = directFlag,
            hasBitPerfectUsbMixer = bitPerfect,
            connectedOutputLabel = deviceInfo.label,
            outputCategory = deviceInfo.category,
            usbDacName = usbDevice?.productName?.toString()?.takeIf { it.isNotBlank() },
            hasFfmpegExtension = LunariaRenderersFactory.isFfmpegAvailable(),
        )
    }

    internal fun selectPrimaryOutputDevice(devices: List<AudioDeviceInfo>): AudioDeviceInfo? =
        devices.firstOrNull { isUsbDevice(it) }
            ?: devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_LINE_ANALOG ||
                    it.type == AudioDeviceInfo.TYPE_LINE_DIGITAL
            }
            ?: devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
            }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: devices.firstOrNull()

    internal fun getOutputDevicesSafely(audioManager: AudioManager): List<AudioDeviceInfo> = try {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
    } catch (_: Exception) {
        emptyList()
    }

    internal fun isUsbDevice(device: AudioDeviceInfo): Boolean =
        device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET

    private fun queryDirectPlayback(): Int {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val encodings = listOf(
            AudioFormat.ENCODING_PCM_24BIT_PACKED,
            AudioFormat.ENCODING_PCM_32BIT,
        )
        return encodings.maxOf { encoding ->
            try {
                val format = AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(48_000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
                AudioManager.getDirectPlaybackSupport(format, attributes)
            } catch (_: Exception) {
                0
            }
        }
    }

    private fun queryBitPerfect(audioManager: AudioManager, device: AudioDeviceInfo): Boolean = try {
        audioManager.getSupportedMixerAttributes(device).any {
            it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
        }
    } catch (_: Exception) {
        false
    }

    companion object {
        /** Pure decision logic, unit-testable on the JVM. */
        fun evaluateCapabilities(
            apiLevel: Int,
            directSupportFlag: Int,
            hasBitPerfectUsbMixer: Boolean,
            connectedOutputLabel: String,
            outputCategory: AudioOutputCategory = AudioOutputCategory.SPEAKER,
            usbDacName: String? = null,
            hasFfmpegExtension: Boolean = false,
        ): AudioCapabilitiesSnapshot {
            val direct = apiLevel >= Build.VERSION_CODES.TIRAMISU && directSupportFlag != 0
            val bitPerfect = apiLevel >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasBitPerfectUsbMixer
            return AudioCapabilitiesSnapshot(
                apiLevel = apiLevel,
                supportsFloatOutput = true,
                supportsDirectPlayback = direct,
                supportsBitPerfectUsb = bitPerfect,
                activeOutputSummary = connectedOutputLabel,
                outputCategory = outputCategory,
                usbDacName = usbDacName,
                hasFfmpegExtension = hasFfmpegExtension,
            )
        }
    }
}
