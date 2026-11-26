package com.lumyn.core.audioengine

import com.lumyn.core.model.EqualizerSettings
import com.lumyn.core.model.ParametricFilterType
import com.lumyn.core.model.ReplayGainCalculator
import com.lumyn.core.model.StandardEqFrequenciesHz
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Pure Kotlin 32/64-bit floating-point RBJ Audio EQ Cookbook biquad filter bank,
 * preamp + ReplayGain gain stage, L/R channel balance, mono downmix, and soft limiter.
 * Zero Android dependencies so acoustic math stays fully unit-testable on the JVM.
 */
class BiquadDspEngine {

    private data class BiquadCoeffs(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double,
    )

    private var sampleRateHz: Int = 48_000
    private var channelCount: Int = 2
    private var settings: EqualizerSettings = EqualizerSettings()
    private var bypassed: Boolean = false
    private var trackReplayGainDb: Float? = null
    private var albumReplayGainDb: Float? = null
    private var trackReplayGainPeak: Float? = null
    private var albumReplayGainPeak: Float? = null

    private var activeCoeffs: List<BiquadCoeffs> = emptyList()
    private var z1: DoubleArray = DoubleArray(0)
    private var z2: DoubleArray = DoubleArray(0)
    private var preampLinear: Double = 1.0
    private var activeFlag: Boolean = false

    fun configure(sampleRateHz: Int, channelCount: Int) {
        this.sampleRateHz = sampleRateHz.coerceAtLeast(8_000)
        this.channelCount = channelCount.coerceIn(1, 8)
        rebuildStages()
    }

    fun updateSettings(
        settings: EqualizerSettings,
        bypassed: Boolean,
        trackReplayGainDb: Float? = this.trackReplayGainDb,
        albumReplayGainDb: Float? = this.albumReplayGainDb,
        trackReplayGainPeak: Float? = this.trackReplayGainPeak,
        albumReplayGainPeak: Float? = this.albumReplayGainPeak,
    ) {
        this.settings = settings
        this.bypassed = bypassed
        this.trackReplayGainDb = trackReplayGainDb
        this.albumReplayGainDb = albumReplayGainDb
        this.trackReplayGainPeak = trackReplayGainPeak
        this.albumReplayGainPeak = albumReplayGainPeak
        rebuildStages()
    }

    fun resetState() {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    fun isProcessingActive(): Boolean = activeFlag

    fun processInterleavedFloats(samples: FloatArray, frameCount: Int) {
        if (!activeFlag || frameCount <= 0) return

        val chCount = channelCount
        val filterCount = activeCoeffs.size
        val applyPreamp = abs(preampLinear - 1.0) >= 1e-4
        val applyMono = settings.monoAudio && chCount == 2
        val bal = settings.channelBalance.coerceIn(-1f, 1f).toDouble()
        val applyBalance = chCount == 2 && abs(bal) >= 0.01
        val leftGain = if (bal > 0.0) (1.0 - bal).coerceIn(0.0, 1.0) else 1.0
        val rightGain = if (bal < 0.0) (1.0 + bal).coerceIn(0.0, 1.0) else 1.0

        for (frame in 0 until frameCount) {
            val baseIdx = frame * chCount

            if (chCount == 2 && (applyMono || applyBalance)) {
                var l = samples[baseIdx].toDouble()
                var r = samples[baseIdx + 1].toDouble()
                if (applyMono) {
                    val mid = (l + r) * 0.5
                    l = mid
                    r = mid
                }
                if (applyBalance) {
                    l *= leftGain
                    r *= rightGain
                }
                samples[baseIdx] = processSingleSample(l, channel = 0, filterCount = filterCount, applyPreamp = applyPreamp)
                samples[baseIdx + 1] = processSingleSample(r, channel = 1, filterCount = filterCount, applyPreamp = applyPreamp)
            } else {
                for (ch in 0 until chCount) {
                    val idx = baseIdx + ch
                    samples[idx] = processSingleSample(
                        input = samples[idx].toDouble(),
                        channel = ch,
                        filterCount = filterCount,
                        applyPreamp = applyPreamp,
                    )
                }
            }
        }
    }

    /** Evaluates analytical cascaded magnitude response (dB) at [frequencyHz] for verification/plotting. */
    fun computeMagnitudeResponseDb(frequencyHz: Double): Double {
        if (bypassed || !activeFlag) return 0.0
        val omega = (2.0 * PI * frequencyHz) / sampleRateHz
        val cosW = cos(omega)
        val sinW = sin(omega)
        val cos2W = cos(2.0 * omega)
        val sin2W = sin(2.0 * omega)

        var magSq = preampLinear * preampLinear
        activeCoeffs.forEach { c ->
            val numRe = c.b0 + c.b1 * cosW + c.b2 * cos2W
            val numIm = -(c.b1 * sinW + c.b2 * sin2W)
            val denRe = 1.0 + c.a1 * cosW + c.a2 * cos2W
            val denIm = -(c.a1 * sinW + c.a2 * sin2W)
            val denMagSq = (denRe * denRe + denIm * denIm).coerceAtLeast(1e-24)
            magSq *= (numRe * numRe + numIm * numIm) / denMagSq
        }
        return if (magSq <= 1e-24) -120.0 else 10.0 * kotlin.math.log10(magSq)
    }

    private fun processSingleSample(
        input: Double,
        channel: Int,
        filterCount: Int,
        applyPreamp: Boolean,
    ): Float {
        var x = if (applyPreamp) input * preampLinear else input
        for (f in 0 until filterCount) {
            val c = activeCoeffs[f]
            val stateIdx = f * channelCount + channel
            val y = c.b0 * x + z1[stateIdx]
            z1[stateIdx] = c.b1 * x - c.a1 * y + z2[stateIdx]
            z2[stateIdx] = c.b2 * x - c.a2 * y
            x = y
        }
        return softLimit(x).toFloat()
    }

    private fun softLimit(x: Double): Double {
        if (!x.isFinite()) return 0.0
        val absX = abs(x)
        if (absX <= LIMITER_THRESHOLD) return x
        val sign = if (x >= 0.0) 1.0 else -1.0
        val excess = absX - LIMITER_THRESHOLD
        val headroom = 1.0 - LIMITER_THRESHOLD
        return sign * (LIMITER_THRESHOLD + headroom * tanh(excess / headroom))
    }

    private fun rebuildStages() {
        val shouldProcess = !bypassed && settings.hasActiveEffects()
        activeFlag = shouldProcess
        if (!shouldProcess) {
            activeCoeffs = emptyList()
            preampLinear = 1.0
            z1 = DoubleArray(0)
            z2 = DoubleArray(0)
            return
        }

        val eqPreamp = if (settings.enabled) settings.preampDb.toDouble() else 0.0
        val rgGain = ReplayGainCalculator.computeEffectiveGainDb(
            mode = settings.replayGainMode,
            trackGainDb = trackReplayGainDb,
            albumGainDb = albumReplayGainDb,
            trackPeak = trackReplayGainPeak,
            albumPeak = albumReplayGainPeak,
            preampDb = settings.replayGainPreampDb,
        )?.toDouble() ?: 0.0
        preampLinear = 10.0.pow((eqPreamp + rgGain).coerceIn(-24.0, 24.0) / 20.0)

        val coeffs = mutableListOf<BiquadCoeffs>()
        if (settings.enabled) {
            val bands = settings.normalizedBandGains()
            StandardEqFrequenciesHz.forEachIndexed { index, freqHz ->
                val gain = bands[index].toDouble()
                if (abs(gain) >= MIN_ACTIVE_GAIN_DB) {
                    coeffs += computeBiquad(
                        type = ParametricFilterType.PEAKING,
                        freqHz = freqHz.toDouble(),
                        gainDb = gain,
                        qFactor = GRAPHIC_BAND_Q,
                    )
                }
            }
            settings.parametricFilters.forEach { pf ->
                if (pf.enabled && abs(pf.gainDb) >= MIN_ACTIVE_GAIN_DB) {
                    coeffs += computeBiquad(
                        type = pf.type,
                        freqHz = pf.frequencyHz,
                        gainDb = pf.gainDb,
                        qFactor = pf.qFactor,
                    )
                }
            }
        }

        val stateSize = coeffs.size * channelCount
        if (z1.size != stateSize) {
            z1 = DoubleArray(stateSize)
            z2 = DoubleArray(stateSize)
        } else {
            // Preserve existing delay line size cleanly on parameter tweaks when filter count matches.
        }
        activeCoeffs = coeffs
    }

    private fun computeBiquad(
        type: ParametricFilterType,
        freqHz: Double,
        gainDb: Double,
        qFactor: Double,
    ): BiquadCoeffs {
        val maxFreq = sampleRateHz * 0.45
        val clampedFreq = freqHz.coerceIn(15.0, maxFreq.coerceAtLeast(20.0))
        val clampedGain = gainDb.coerceIn(-24.0, 24.0)
        val q = qFactor.coerceIn(0.1, 24.0)

        val omega = (2.0 * PI * clampedFreq) / sampleRateHz
        val sinW = sin(omega)
        val cosW = cos(omega)
        val a = 10.0.pow(clampedGain / 40.0)
        val alpha = sinW / (2.0 * q)

        val b0: Double
        val b1: Double
        val b2: Double
        val a0: Double
        val a1: Double
        val a2: Double

        when (type) {
            ParametricFilterType.PEAKING -> {
                b0 = 1.0 + alpha * a
                b1 = -2.0 * cosW
                b2 = 1.0 - alpha * a
                a0 = 1.0 + alpha / a
                a1 = -2.0 * cosW
                a2 = 1.0 - alpha / a
            }

            ParametricFilterType.LOW_SHELF -> {
                val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
                b0 = a * ((a + 1.0) - (a - 1.0) * cosW + twoSqrtAAlpha)
                b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW)
                b2 = a * ((a + 1.0) - (a - 1.0) * cosW - twoSqrtAAlpha)
                a0 = (a + 1.0) + (a - 1.0) * cosW + twoSqrtAAlpha
                a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW)
                a2 = (a + 1.0) + (a - 1.0) * cosW - twoSqrtAAlpha
            }

            ParametricFilterType.HIGH_SHELF -> {
                val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
                b0 = a * ((a + 1.0) + (a - 1.0) * cosW + twoSqrtAAlpha)
                b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW)
                b2 = a * ((a + 1.0) + (a - 1.0) * cosW - twoSqrtAAlpha)
                a0 = (a + 1.0) - (a - 1.0) * cosW + twoSqrtAAlpha
                a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW)
                a2 = (a + 1.0) - (a - 1.0) * cosW - twoSqrtAAlpha
            }
        }

        val invA0 = 1.0 / a0.coerceAtLeast(1e-12)
        return BiquadCoeffs(
            b0 = b0 * invA0,
            b1 = b1 * invA0,
            b2 = b2 * invA0,
            a1 = a1 * invA0,
            a2 = a2 * invA0,
        )
    }

    private companion object {
        const val GRAPHIC_BAND_Q = 1.41
        const val MIN_ACTIVE_GAIN_DB = 0.05
        const val LIMITER_THRESHOLD = 0.95
    }
}
