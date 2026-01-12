package org.github.cyterdan.chat_over_kafka.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.random.Random

/**
 * An equalizer-style audio visualizer with animated bars that respond to audio amplitude.
 *
 * @param amplitude Current audio amplitude (0.0 to 1.0)
 * @param isActive Whether the equalizer should be animating
 * @param modifier Modifier for the component
 * @param barCount Number of equalizer bars to display
 * @param barWidth Width of each bar
 * @param barSpacing Spacing between bars
 * @param barColor Color of the bars
 * @param minBarHeight Minimum height of bars as a fraction (0.0 to 1.0)
 */
@Composable
fun EqualizerVisualizer(
    amplitude: Float,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 5,
    barWidth: Dp = 12.dp,
    barSpacing: Dp = 8.dp,
    barColor: Color = MaterialTheme.colorScheme.error,
    minBarHeight: Float = 0.1f
) {
    // Create individual bar states with slight variations for visual interest
    val barHeights = remember { List(barCount) { mutableFloatStateOf(minBarHeight) } }

    // Update bar heights based on amplitude with randomized variations
    LaunchedEffect(amplitude, isActive) {
        if (isActive) {
            // Add slight variation to each bar for a more natural look
            barHeights.forEachIndexed { index, state ->
                // Create variation based on bar position and some randomness
                val phaseOffset = sin(index * 0.8f) * 0.15f
                val randomVariation = Random.nextFloat() * 0.2f - 0.1f
                val targetHeight = (amplitude + phaseOffset + randomVariation)
                    .coerceIn(minBarHeight, 1f)
                state.floatValue = targetHeight
            }
        } else {
            // Reset to minimum when not active
            barHeights.forEach { it.floatValue = minBarHeight }
        }
    }

    // Animate bars even when amplitude is constant for a "breathing" effect
    LaunchedEffect(isActive) {
        while (isActive) {
            delay(100)
            barHeights.forEachIndexed { index, state ->
                val currentAmplitude = amplitude.coerceIn(0f, 1f)
                val phaseOffset = sin(System.currentTimeMillis() / 200.0 + index * 0.7).toFloat() * 0.1f
                val targetHeight = (currentAmplitude + phaseOffset)
                    .coerceIn(minBarHeight, 1f)
                state.floatValue = targetHeight
            }
        }
    }

    Row(
        modifier = modifier.height(120.dp),
        horizontalArrangement = Arrangement.spacedBy(barSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        barHeights.forEach { heightState ->
            val animatedHeight by animateFloatAsState(
                targetValue = heightState.floatValue,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "barHeight"
            )

            Box(
                modifier = Modifier
                    .width(barWidth)
                    .fillMaxHeight(animatedHeight)
                    .clip(RoundedCornerShape(barWidth / 2))
                    .background(barColor)
            )
        }
    }
}
