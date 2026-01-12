package org.github.cyterdan.chat_over_kafka

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.TimeUnit

data class TimelineEntry(
    val metadata: AudioMetadata,
    val displayTime: String,
    val durationMs: Long,  // Duration in milliseconds for precision
    val durationDisplay: String  // Formatted duration string
) {
    // Keep durationSeconds for backwards compatibility
    val durationSeconds: Long get() = durationMs / 1000

    companion object {
        fun fromMetadata(metadata: AudioMetadata): TimelineEntry {
            val now = System.currentTimeMillis()
            val ageMs = now - metadata.timestamp

            val displayTime = when {
                ageMs < TimeUnit.MINUTES.toMillis(1) -> "Just now"
                ageMs < TimeUnit.HOURS.toMillis(1) -> {
                    val mins = TimeUnit.MILLISECONDS.toMinutes(ageMs)
                    "${mins}m ago"
                }
                ageMs < TimeUnit.HOURS.toMillis(24) -> {
                    val hours = TimeUnit.MILLISECONDS.toHours(ageMs)
                    "${hours}h ago"
                }
                else -> {
                    val days = TimeUnit.MILLISECONDS.toDays(ageMs)
                    "${days}d ago"
                }
            }

            // Estimate duration from message count (60ms per frame)
            val durationMs = metadata.messageCount * 60L

            // Format duration display
            val durationDisplay = when {
                durationMs < 1000 -> "${durationMs}ms"
                durationMs < 60000 -> {
                    val seconds = durationMs / 1000.0
                    String.format("%.1fs", seconds)
                }
                else -> {
                    val minutes = durationMs / 60000
                    val seconds = (durationMs % 60000) / 1000
                    "${minutes}m ${seconds}s"
                }
            }

            return TimelineEntry(metadata, displayTime, durationMs, durationDisplay)
        }
    }
}

enum class TimeRange(val hours: Int, val displayName: String) {
    ONE_HOUR(1, "Last Hour"),
    SIX_HOURS(6, "Last 6 Hours"),
    TWENTY_FOUR_HOURS(24, "Last 24 Hours")
}

object TimelineManager {
    /**
     * Consume metadata from the past N hours and build timeline.
     * Uses startOffset as unique key to support reaction updates (compacted topic).
     */
    fun consumeTimeline(
        metadataFlow: Flow<KafkaMessage>,
        timeRangeHours: Int = 24,
        metadataPartition: Int = 0  // Default to partition 0 for metadata
    ): Flow<List<TimelineEntry>> = flow {
        val timelineMap = mutableMapOf<Long, TimelineEntry>()
        val cutoffTime = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(timeRangeHours.toLong())

        // Emit initial empty state
        emit(emptyList())

        metadataFlow.collect { message ->
            try {
                if (message.partition != metadataPartition) return@collect

                val json = message.value?.toString(Charsets.UTF_8) ?: return@collect
                val metadata = AudioMetadata.fromJson(json)

                if (metadata.timestamp >= cutoffTime) {
                    val entry = TimelineEntry.fromMetadata(metadata)
                    timelineMap[metadata.startOffset] = entry
                    emit(timelineMap.values.sortedByDescending { it.metadata.timestamp })
                }
            } catch (e: Exception) {
                Log.e("Timeline", "Failed to parse metadata: ${e.message}")
            }
        }
    }

    /**
     * Calculate message density for visualization
     * Returns list of (hourIndex, messageCount) pairs
     */
    fun calculateDensity(entries: List<TimelineEntry>, timeRangeHours: Int): List<Pair<Int, Int>> {
        val density = IntArray(timeRangeHours) { 0 }
        val now = System.currentTimeMillis()

        entries.forEach { entry ->
            val ageMs = now - entry.metadata.timestamp
            val hourIndex = TimeUnit.MILLISECONDS.toHours(ageMs).toInt()
            if (hourIndex in 0 until timeRangeHours) {
                density[hourIndex]++
            }
        }

        return density.mapIndexed { index, count -> index to count }
    }
}
