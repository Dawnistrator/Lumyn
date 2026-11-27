package com.lunaria.core.audioengine

import com.lunaria.core.model.AudioOutputCategory

data class MixerCandidate(
    val encoding: Int,
    val sampleRateHz: Int,
    val channelMask: Int,
    val isBitPerfect: Boolean,
)

data class OutputDeviceInfo(
    val label: String,
    val category: AudioOutputCategory,
    val isUsbDac: Boolean,
)

/**
 * Pure Kotlin selection & classification rules for Hi-Res mixer attributes and audio output types.
 * Uses integer constants matching `android.media.AudioFormat` and `android.media.AudioDeviceInfo`
 * so all selection logic stays unit-testable on a plain JVM without Robolectric.
 */
object BitPerfectMixerSelector {

    const val ENCODING_PCM_16BIT = 2
    const val ENCODING_PCM_8BIT = 3
    const val ENCODING_PCM_FLOAT = 4
    const val ENCODING_PCM_24BIT_PACKED = 21
    const val ENCODING_PCM_32BIT = 22

    private const val DEVICE_TYPE_BUILTIN_EARPIECE = 1
    private const val DEVICE_TYPE_BUILTIN_SPEAKER = 2
    private const val DEVICE_TYPE_WIRED_HEADSET = 3
    private const val DEVICE_TYPE_WIRED_HEADPHONES = 4
    private const val DEVICE_TYPE_LINE_ANALOG = 5
    private const val DEVICE_TYPE_LINE_DIGITAL = 6
    private const val DEVICE_TYPE_BLUETOOTH_A2DP = 8
    private const val DEVICE_TYPE_USB_DEVICE = 11
    private const val DEVICE_TYPE_USB_HEADSET = 22
    private const val DEVICE_TYPE_BLE_HEADSET = 26
    private const val DEVICE_TYPE_BLE_SPEAKER = 27

    fun selectBestBitPerfectCandidate(
        candidates: List<MixerCandidate>,
        targetSampleRateHz: Int,
        targetBitDepth: Int,
    ): MixerCandidate? {
        val bitPerfect = candidates.filter { it.isBitPerfect }
        if (bitPerfect.isEmpty()) return null

        val chosenRate = pickClosestSampleRate(
            availableRates = bitPerfect.map { it.sampleRateHz }.distinct(),
            targetSampleRateHz = targetSampleRateHz,
        ) ?: return null

        val rateMatches = bitPerfect.filter { it.sampleRateHz == chosenRate }
        return rateMatches.maxByOrNull { candidate ->
            bitDepthPreferenceScore(encodingBitDepth(candidate.encoding), targetBitDepth)
        }
    }

    private fun pickClosestSampleRate(availableRates: List<Int>, targetSampleRateHz: Int): Int? {
        if (availableRates.isEmpty()) return null
        if (targetSampleRateHz in availableRates) return targetSampleRateHz
        val higher = availableRates.filter { it > targetSampleRateHz }.minOrNull()
        return higher ?: availableRates.maxOrNull()
    }

    private fun bitDepthPreferenceScore(candidateBits: Int, targetBitDepth: Int): Int {
        if (targetBitDepth <= 16) {
            return when (candidateBits) {
                16 -> 100
                24 -> 90
                32 -> 80
                else -> candidateBits
            }
        }
        return when (candidateBits) {
            24 -> if (targetBitDepth == 24) 100 else 90
            32 -> if (targetBitDepth >= 32) 100 else 95
            16 -> 50
            else -> candidateBits
        }
    }

    fun encodingBitDepth(encoding: Int): Int = when (encoding) {
        ENCODING_PCM_8BIT -> 8
        ENCODING_PCM_16BIT -> 16
        ENCODING_PCM_24BIT_PACKED -> 24
        ENCODING_PCM_32BIT, ENCODING_PCM_FLOAT -> 32
        else -> 16
    }

    fun encodingLabel(encoding: Int): String = when (encoding) {
        ENCODING_PCM_FLOAT -> "PCM 32-bit Float"
        ENCODING_PCM_24BIT_PACKED -> "PCM 24-bit"
        ENCODING_PCM_32BIT -> "PCM 32-bit"
        ENCODING_PCM_16BIT -> "PCM 16-bit"
        ENCODING_PCM_8BIT -> "PCM 8-bit"
        else -> "System PCM"
    }

    fun describeAudioDeviceType(deviceType: Int, productName: String?): OutputDeviceInfo {
        val cleanName = productName?.trim()?.takeIf { it.isNotEmpty() }
        return when (deviceType) {
            DEVICE_TYPE_USB_DEVICE, DEVICE_TYPE_USB_HEADSET -> OutputDeviceInfo(
                label = cleanName?.let { "USB DAC ($it)" } ?: "USB DAC",
                category = AudioOutputCategory.USB_DAC,
                isUsbDac = true,
            )

            DEVICE_TYPE_BLUETOOTH_A2DP, DEVICE_TYPE_BLE_HEADSET, DEVICE_TYPE_BLE_SPEAKER -> OutputDeviceInfo(
                label = cleanName?.let { "Bluetooth ($it)" } ?: "Bluetooth A2DP",
                category = AudioOutputCategory.BLUETOOTH_A2DP,
                isUsbDac = false,
            )

            DEVICE_TYPE_WIRED_HEADSET, DEVICE_TYPE_WIRED_HEADPHONES,
            DEVICE_TYPE_LINE_ANALOG, DEVICE_TYPE_LINE_DIGITAL,
            -> OutputDeviceInfo(
                label = cleanName ?: "Headphones / Line Out",
                category = AudioOutputCategory.WIRED,
                isUsbDac = false,
            )

            DEVICE_TYPE_BUILTIN_SPEAKER, DEVICE_TYPE_BUILTIN_EARPIECE -> OutputDeviceInfo(
                label = "Device Speaker",
                category = AudioOutputCategory.SPEAKER,
                isUsbDac = false,
            )

            else -> OutputDeviceInfo(
                label = cleanName ?: "System Output",
                category = AudioOutputCategory.OTHER,
                isUsbDac = false,
            )
        }
    }
}
