package org.github.cyterdan.chat_over_kafka.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.github.cyterdan.chat_over_kafka.Reactions
import org.github.cyterdan.chat_over_kafka.TimeRange
import org.github.cyterdan.chat_over_kafka.TimelineEntry
import org.github.cyterdan.chat_over_kafka.audio.WaveformData
import org.github.cyterdan.chat_over_kafka.ui.theme.NeonGreen
import kotlin.math.absoluteValue

/**
 * Generates a consistent color for a user based on their userId.
 * Each user will always get the same color.
 */
fun getUserColor(userId: String): Color {
    val colors = listOf(
        Color(0xFF00FF00),  // Neon Green
        Color(0xFFFFB000),  // Amber
        Color(0xFF00BFFF),  // Deep Sky Blue
        Color(0xFFFF6B6B),  // Coral Red
        Color(0xFFDA70D6),  // Orchid
        Color(0xFF40E0D0),  // Turquoise
        Color(0xFFFFD700),  // Gold
        Color(0xFFFF69B4),  // Hot Pink
        Color(0xFF7B68EE),  // Medium Slate Blue
        Color(0xFF98FB98),  // Pale Green
    )
    val index = userId.hashCode().absoluteValue % colors.size
    return colors[index]
}

/**
 * Get initials from userId (first 2 characters, uppercase)
 */
fun getUserInitials(userId: String): String {
    return userId.take(2).uppercase()
}

/**
 * Number of bars in the waveform visualization
 */
private const val WAVEFORM_BAR_COUNT = 20

/**
 * Row of emoji reaction buttons
 */
@Composable
fun ReactionRow(
    reactions: Map<String, List<String>>,  // emoji -> list of userIds
    currentUserId: String,
    onReact: (emoji: String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Reactions.EMOJIS.forEach { emoji ->
            val reactors = reactions[emoji] ?: emptyList()
            val count = reactors.size
            val hasUserReacted = currentUserId in reactors

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (hasUserReacted)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                    .clickable { onReact(emoji) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = emoji,
                        style = MaterialTheme.typography.labelMedium
                    )
                    if (count > 0) {
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hasUserReacted)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Audio message chat bubble component with animated waveform and progress cursor
 */
@Composable
fun AudioChatBubble(
    entry: TimelineEntry,
    isOwnMessage: Boolean,
    userColor: Color,
    currentUserId: String,
    isPlaying: Boolean,
    playbackProgress: Float,  // 0.0 to 1.0
    waveformData: WaveformData,  // Live amplitude data
    onPlay: () -> Unit,
    onReact: (emoji: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val bubbleColor = if (isOwnMessage) {
        userColor.copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val bubbleShape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (isOwnMessage) 16.dp else 4.dp,
        bottomEnd = if (isOwnMessage) 4.dp else 16.dp
    )

    // Animate progress smoothly
    val animatedProgress by animateFloatAsState(
        targetValue = playbackProgress,
        animationSpec = tween(durationMillis = 100, easing = LinearEasing),
        label = "progress"
    )

    // Generate waveform bar heights - use actual amplitude when playing, static pattern otherwise
    val barHeights = remember(isPlaying, waveformData.samples) {
        if (isPlaying && waveformData.samples.isNotEmpty()) {
            // Use actual waveform data, sample it to fit our bar count
            val samples = waveformData.normalized()
            List(WAVEFORM_BAR_COUNT) { i ->
                val sampleIndex = (i * samples.size / WAVEFORM_BAR_COUNT).coerceIn(0, samples.size - 1)
                (samples.getOrElse(sampleIndex) { 0.3f } * 0.8f + 0.2f).coerceIn(0.15f, 1f)
            }
        } else {
            // Static waveform pattern when not playing
            listOf(0.3f, 0.5f, 0.4f, 0.7f, 0.5f, 0.6f, 0.35f, 0.8f, 0.55f, 0.45f,
                   0.6f, 0.4f, 0.75f, 0.5f, 0.65f, 0.4f, 0.55f, 0.7f, 0.45f, 0.35f)
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isOwnMessage) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        // Avatar for other users (left side)
        if (!isOwnMessage) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(userColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = getUserInitials(entry.metadata.userId),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        // Message bubble
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(bubbleColor, bubbleShape)
                .clickable(onClick = onPlay)
                .padding(12.dp),
            horizontalAlignment = if (isOwnMessage) Alignment.End else Alignment.Start
        ) {
            // Username
            Text(
                text = if (isOwnMessage) "You" else entry.metadata.userId,
                style = MaterialTheme.typography.labelSmall,
                color = userColor,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))

            // Audio player row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Play button
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isPlaying) userColor else userColor.copy(alpha = 0.5f))
                        .clickable(onClick = onPlay),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isPlaying) "||" else "▶",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                // Waveform with progress cursor
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                ) {
                    // Waveform bars
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        barHeights.forEachIndexed { index, heightFraction ->
                            val barProgress = index.toFloat() / WAVEFORM_BAR_COUNT
                            val isPastCursor = isPlaying && barProgress <= animatedProgress

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(heightFraction)
                                    .background(
                                        color = when {
                                            isPastCursor -> userColor
                                            isPlaying -> userColor.copy(alpha = 0.3f)
                                            else -> userColor.copy(alpha = 0.4f)
                                        },
                                        shape = RoundedCornerShape(2.dp)
                                    )
                            )
                        }
                    }

                    // Progress cursor line (vertical line showing current position)
                    if (isPlaying && animatedProgress > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(2.dp)
                                .align(Alignment.CenterStart)
                                .padding(start = (animatedProgress * 200).dp.coerceAtMost(198.dp))
                                .background(Color.White, RoundedCornerShape(1.dp))
                        )
                    }
                }

                // Duration / Elapsed time during playback
                val displayTime = if (isPlaying) {
                    val elapsedMs = (animatedProgress * entry.durationMs).toLong()
                    val elapsedSec = elapsedMs / 1000.0
                    val totalSec = entry.durationMs / 1000.0
                    String.format("%.1f/%.1f", elapsedSec, totalSec)
                } else {
                    entry.durationDisplay
                }

                Text(
                    text = displayTime,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isPlaying) userColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(56.dp)  // Wide enough for "0.5/1.3" format
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Timestamp
            Text(
                text = entry.displayTime,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Reactions row
            ReactionRow(
                reactions = entry.metadata.reactions,
                currentUserId = currentUserId,
                onReact = onReact
            )
        }

        // Avatar for own messages (right side)
        if (isOwnMessage) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(userColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = getUserInitials(entry.metadata.userId),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Playback state for a specific message
 */
data class PlaybackState(
    val playingEntryId: Long? = null,
    val progress: Float = 0f,  // 0.0 to 1.0
    val waveformData: WaveformData = WaveformData()
)

/**
 * Main chat-style timeline view
 */
@Composable
fun TimelineView(
    timeline: List<TimelineEntry>,
    selectedRange: TimeRange,
    isLoading: Boolean,
    currentUserId: String,
    playbackState: PlaybackState,
    onRangeChange: (TimeRange) -> Unit,
    onPlayFromOffset: (startOffset: Long, endOffset: Long, durationMs: Long) -> Unit,
    onReact: (startOffset: Long, emoji: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Reverse the timeline so oldest messages are at the top (chat style)
    val chatMessages = remember(timeline) { timeline.reversed() }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // Header with time range selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "[ MESSAGES ]",
                style = MaterialTheme.typography.titleMedium,
                color = NeonGreen,
                fontWeight = FontWeight.Bold
            )

            // Time range chips
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TimeRange.entries.forEach { range ->
                    FilterChip(
                        selected = selectedRange == range,
                        onClick = { onRangeChange(range) },
                        label = {
                            Text(
                                text = "${range.hours}h",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.height(28.dp)
                    )
                }
            }
        }

        // Chat messages area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
        ) {
            when {
                isLoading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = NeonGreen
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Loading messages...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NeonGreen.copy(alpha = 0.7f)
                        )
                    }
                }

                chatMessages.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "[ NO MESSAGES ]",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No audio in ${selectedRange.displayName.lowercase()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(
                            items = chatMessages,
                            key = { it.metadata.startOffset }
                        ) { entry ->
                            val isOwnMessage = entry.metadata.userId == currentUserId ||
                                    (currentUserId.isEmpty() && entry.metadata.userId == "anonymous")
                            val userColor = getUserColor(entry.metadata.userId)
                            val isPlaying = playbackState.playingEntryId == entry.metadata.startOffset

                            AudioChatBubble(
                                entry = entry,
                                isOwnMessage = isOwnMessage,
                                userColor = userColor,
                                currentUserId = currentUserId,
                                isPlaying = isPlaying,
                                playbackProgress = if (isPlaying) playbackState.progress else 0f,
                                waveformData = if (isPlaying) playbackState.waveformData else WaveformData(),
                                onPlay = {
                                    onPlayFromOffset(
                                        entry.metadata.startOffset,
                                        entry.metadata.endOffset,
                                        entry.durationMs
                                    )
                                },
                                onReact = { emoji ->
                                    onReact(entry.metadata.startOffset, emoji)
                                }
                            )
                        }
                    }
                }
            }
        }

        // Message count footer
        if (chatMessages.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${chatMessages.size} messages",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}
