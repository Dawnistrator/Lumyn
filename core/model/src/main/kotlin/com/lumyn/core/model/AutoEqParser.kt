package com.lumyn.core.model

import kotlin.math.abs
import kotlin.math.ln

data class AutoEqProfile(
    val name: String,
    val preampDb: Float,
    val bandGainsDb: List<Float>,
    val parametricFilters: List<ParametricFilter>,
)

/**
 * Pure Kotlin parser for AutoEQ `.txt` exports:
 * - ParametricEQ format (`Preamp:` + `Filter N: ON PK/LSC/HSC Fc ... Hz Gain ... dB Q ...`)
 * - GraphicEQ format (`GraphicEQ: freq1 gain1; freq2 gain2; ...`)
 */
object AutoEqParser {

    private val PreampRegex = Regex("""(?i)^Preamp:\s*([+-]?\d+(?:[.,]\d+)?)\s*dB""")
    private val FilterRegex = Regex(
        """(?i)^Filter\s+\d+:\s*(ON|OFF)\s+(PK|LSC|HSC|LS|HS)\s+Fc\s+(\d+(?:[.,]\d+)?)\s*Hz\s+Gain\s+([+-]?\d+(?:[.,]\d+)?)\s*dB(?:\s+Q\s+(\d+(?:[.,]\d+)?))?""",
    )
    private val GraphicPairRegex = Regex("""(\d+(?:[.,]\d+)?)\s+([+-]?\d+(?:[.,]\d+)?)""")

    fun parse(rawText: String, fallbackName: String = "AutoEQ"): AutoEqProfile? {
        val cleaned = rawText.removePrefix("\uFEFF")
        var preampDb: Float? = null
        val parametricFilters = mutableListOf<ParametricFilter>()
        val graphicPoints = mutableListOf<Pair<Double, Double>>()

        cleaned.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach

            PreampRegex.find(line)?.let { match ->
                preampDb = match.groupValues[1].toCleanDouble()?.toFloat()
                    ?.coerceIn(EqualizerSettings.MIN_GAIN_DB, EqualizerSettings.MAX_GAIN_DB)
                return@forEach
            }

            FilterRegex.find(line)?.let { match ->
                val enabled = match.groupValues[1].equals("ON", ignoreCase = true)
                val type = when (match.groupValues[2].uppercase()) {
                    "LSC", "LS" -> ParametricFilterType.LOW_SHELF
                    "HSC", "HS" -> ParametricFilterType.HIGH_SHELF
                    else -> ParametricFilterType.PEAKING
                }
                val freq = match.groupValues[3].toCleanDouble() ?: return@forEach
                val gain = match.groupValues[4].toCleanDouble() ?: return@forEach
                val q = match.groupValues[5].toCleanDouble()?.takeIf { it > 0.0 } ?: 0.707
                if (freq in 10.0..24_000.0) {
                    parametricFilters += ParametricFilter(
                        frequencyHz = freq,
                        gainDb = gain.coerceIn(-24.0, 24.0),
                        qFactor = q.coerceIn(0.1, 24.0),
                        type = type,
                        enabled = enabled,
                    )
                }
                return@forEach
            }

            if (line.startsWith("GraphicEQ:", ignoreCase = true)) {
                val payload = line.substringAfter(':')
                payload.split(';').forEach { token ->
                    GraphicPairRegex.find(token.trim())?.let { pair ->
                        val f = pair.groupValues[1].toCleanDouble()
                        val g = pair.groupValues[2].toCleanDouble()
                        if (f != null && g != null && f in 10.0..24_000.0) {
                            graphicPoints += f to g
                        }
                    }
                }
            }
        }

        if (parametricFilters.isEmpty() && graphicPoints.isEmpty()) return null

        val mappedBands = if (graphicPoints.isNotEmpty()) {
            interpolateGraphicTo10Bands(graphicPoints)
        } else {
            List(StandardEqFrequenciesHz.size) { 0f }
        }

        val resolvedPreamp = preampDb ?: run {
            val peakBoost = maxOf(
                parametricFilters.filter { it.enabled }.maxOfOrNull { it.gainDb.toFloat() } ?: 0f,
                mappedBands.maxOrNull() ?: 0f,
            )
            if (peakBoost > 0f) (-peakBoost).coerceIn(EqualizerSettings.MIN_GAIN_DB, 0f) else 0f
        }

        val cleanName = fallbackName
            .substringBeforeLast('.')
            .trim()
            .ifBlank { "AutoEQ" }

        return AutoEqProfile(
            name = cleanName,
            preampDb = resolvedPreamp,
            bandGainsDb = mappedBands,
            parametricFilters = parametricFilters,
        )
    }

    private fun interpolateGraphicTo10Bands(points: List<Pair<Double, Double>>): List<Float> {
        val sorted = points.sortedBy { it.first }
        return StandardEqFrequenciesHz.map { targetHz ->
            val targetLog = ln(targetHz.toDouble())
            val exact = sorted.firstOrNull { abs(it.first - targetHz) < 0.5 }
            val rawGain = when {
                exact != null -> exact.second
                targetHz <= sorted.first().first -> sorted.first().second
                targetHz >= sorted.last().first -> sorted.last().second
                else -> {
                    val rightIdx = sorted.indexOfFirst { it.first > targetHz }.coerceAtLeast(1)
                    val left = sorted[rightIdx - 1]
                    val right = sorted[rightIdx]
                    val span = ln(right.first) - ln(left.first)
                    if (span <= 1e-6) {
                        left.second
                    } else {
                        val t = (targetLog - ln(left.first)) / span
                        left.second + t * (right.second - left.second)
                    }
                }
            }
            rawGain.toFloat().coerceIn(EqualizerSettings.MIN_GAIN_DB, EqualizerSettings.MAX_GAIN_DB)
        }
    }

    private fun String.toCleanDouble(): Double? = trim().replace(',', '.').toDoubleOrNull()
}
