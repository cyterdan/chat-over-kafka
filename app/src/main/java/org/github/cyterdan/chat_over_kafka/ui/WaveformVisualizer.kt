package org.github.cyterdan.chat_over_kafka.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import org.github.cyterdan.chat_over_kafka.audio.WaveformData
import org.github.cyterdan.chat_over_kafka.ui.theme.LCDBackground

@Composable
fun WaveformVisualizer(
    waveformData: WaveformData,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    backgroundColor: Color = LCDBackground  // Use LCD background for retro look
) {
    val normalizedSamples = waveformData.normalized()

    Canvas(modifier = modifier.fillMaxWidth().height(200.dp)) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f

        // Draw background
        drawRect(color = backgroundColor)

        // Draw center line
        drawLine(
            color = color.copy(alpha = 0.3f),
            start = Offset(0f, centerY),
            end = Offset(width, centerY),
            strokeWidth = 1.dp.toPx()
        )

        if (normalizedSamples.isEmpty() || width <= 0f || height <= 0f) return@Canvas

        // Calculate spacing between samples
        val spacing = width / normalizedSamples.size.coerceAtLeast(1)
        if (spacing <= 0f || !spacing.isFinite()) return@Canvas

        // Draw waveform as vertical lines (bar style)
        normalizedSamples.forEachIndexed { index, amplitude ->
            // Validate amplitude value
            if (!amplitude.isFinite() || amplitude < 0f) return@forEachIndexed

            val x = index * spacing
            val barHeight = (amplitude * (height / 2f) * 0.4f).coerceIn(0f, height / 2f)

            // Validate coordinates before drawing
            if (!x.isFinite() || !barHeight.isFinite()) return@forEachIndexed

            // Draw symmetric bars from center
            drawLine(
                color = color,
                start = Offset(x, (centerY - barHeight).coerceIn(0f, height)),
                end = Offset(x, (centerY + barHeight).coerceIn(0f, height)),
                strokeWidth = (spacing * 0.7f).coerceAtLeast(1.5f).coerceAtMost(spacing),
                cap = StrokeCap.Round
            )
        }
    }
}
