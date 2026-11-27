package com.lunaria.core.audioengine

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi

/**
 * Custom [AudioProcessorChain] for Lunaria's 32-bit Float DSP pipeline.
 *
 * Uses [LunariaDspAudioProcessor] (which normalizes incoming PCM to `C.ENCODING_PCM_FLOAT`)
 * followed by [SonicAudioProcessor] (which supports `C.ENCODING_PCM_FLOAT` directly), while
 * omitting `SilenceSkippingAudioProcessor` since it rejects float PCM formats.
 */
@UnstableApi
class LunariaAudioProcessorChain(
    private val dspAudioProcessor: LunariaDspAudioProcessor,
    private val sonicAudioProcessor: SonicAudioProcessor = SonicAudioProcessor(),
) : AudioProcessorChain {

    private val processors: Array<AudioProcessor> = arrayOf(dspAudioProcessor, sonicAudioProcessor)

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        sonicAudioProcessor.setSpeed(playbackParameters.speed)
        sonicAudioProcessor.setPitch(playbackParameters.pitch)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = false

    override fun getMediaDuration(playoutDuration: Long): Long =
        if (sonicAudioProcessor.isActive()) {
            sonicAudioProcessor.getMediaDuration(playoutDuration)
        } else {
            playoutDuration
        }

    override fun getSkippedOutputFrameCount(): Long = 0L
}
