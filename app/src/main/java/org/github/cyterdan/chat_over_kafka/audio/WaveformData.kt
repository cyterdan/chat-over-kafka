package org.github.cyterdan.chat_over_kafka.audio

import androidx.compose.runtime.Immutable

@Immutable
data class WaveformData(
    val samples: List<Float> = emptyList(),
    val maxSamples: Int = 50  // ~6 seconds of history with 120ms updates
) {
    fun addSample(amplitude: Float): WaveformData {
        val newSamples = if (samples.size >= maxSamples) {
            samples.drop(1) + amplitude  // Remove oldest, add newest
        } else {
            samples + amplitude
        }
        return copy(samples = newSamples)
    }

    fun clear(): WaveformData = copy(samples = emptyList())

    // Normalize samples to 0.0-1.0 range for drawing
    fun normalized(): List<Float> {
        if (samples.isEmpty()) return emptyList()
        val max = samples.maxOrNull() ?: 1f
        // Use a minimum threshold to avoid division by very small numbers
        val normalizer = if (max > 0.001f) max else 1f
        return samples.map { value ->
            val normalized = value / normalizer
            // Clamp to 0.0-1.0 range and filter out NaN/Infinity
            when {
                normalized.isNaN() || normalized.isInfinite() -> 0f
                normalized < 0f -> 0f
                normalized > 1f -> 1f
                else -> normalized
            }
        }
    }
}
