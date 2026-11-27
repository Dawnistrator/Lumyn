package com.lunaria.core.audioengine

import android.content.Context
import android.os.Build
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.lunaria.core.model.AudioOutputMode
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import java.util.ArrayList

/**
 * Renderers for Lunaria's 3-Tier Hi-Res pipeline:
 * - DSP 32-bit Float: enables `ENCODING_PCM_FLOAT` output and prefers NextLib FFmpeg/FLAC
 *   for consistent software decoding across API 26+ (including FLAC, ALAC, APE, WavPack, WMA).
 * - Purist Direct / Bit-Perfect: disables forced float conversion so 24/32-bit integer PCM
 *   can reach Direct / Bit-Perfect output on API 33/34+; FFmpeg stays as fallback on API 27+
 *   and primary FLAC decoder on API 26 (where native MediaCodec FLAC is absent).
 */
@UnstableApi
class LunariaRenderersFactory(
    context: Context,
    private val audioOutputMode: AudioOutputMode = AudioOutputMode.DSP_FLOAT_32,
    private val dspConfigStore: LunariaDspConfigStore? = null,
) : DefaultRenderersFactory(context) {

    init {
        val extensionMode = if (audioOutputMode == AudioOutputMode.DSP_FLOAT_32 ||
            Build.VERSION.SDK_INT == Build.VERSION_CODES.O
        ) {
            EXTENSION_RENDERER_MODE_PREFER
        } else {
            EXTENSION_RENDERER_MODE_ON
        }
        setExtensionRendererMode(extensionMode)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val builder = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioOutputPlaybackParameters(false)
        if (audioOutputMode == AudioOutputMode.DSP_FLOAT_32 && dspConfigStore != null) {
            builder.setAudioProcessorChain(
                LunariaAudioProcessorChain(LunariaDspAudioProcessor(dspConfigStore)),
            )
        }
        return builder.build()
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )

        if (!isFfmpegAvailable()) return
        val ffmpegRenderer = FfmpegAudioRenderer(eventHandler, eventListener, audioSink)
        val preferExtension = audioOutputMode == AudioOutputMode.DSP_FLOAT_32 ||
            Build.VERSION.SDK_INT == Build.VERSION_CODES.O
        if (preferExtension) {
            out.add(0, ffmpegRenderer)
        } else {
            out.add(ffmpegRenderer)
        }
    }

    companion object {
        fun isFfmpegAvailable(): Boolean = runCatching { FfmpegLibrary.isAvailable() }.getOrDefault(false)

        fun ffmpegVersion(): String? = runCatching {
            if (FfmpegLibrary.isAvailable()) FfmpegLibrary.getVersion() else null
        }.getOrNull()
    }
}
