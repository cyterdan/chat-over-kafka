package org.github.cyterdan.chat_over_kafka

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Available reaction emojis
 */
object Reactions {
    val EMOJIS = listOf("👍", "❤️", "🔥")
}

@Serializable
data class AudioMetadata(
    val userId: String,
    val channelId: Int,
    val startOffset: Long,
    val endOffset: Long,
    val timestamp: Long,
    val messageCount: Long,
    // Map of emoji -> list of userIds who reacted
    val reactions: Map<String, List<String>> = emptyMap()
) {
    /**
     * Generate unique message key for Kafka compaction
     */
    fun messageKey(): String = "msg-$channelId-$startOffset"

    /**
     * Toggle a reaction for a user. Returns new metadata with updated reactions.
     */
    fun toggleReaction(emoji: String, reactingUserId: String): AudioMetadata {
        val currentReactors = reactions[emoji] ?: emptyList()
        val newReactors = if (reactingUserId in currentReactors) {
            currentReactors - reactingUserId
        } else {
            currentReactors + reactingUserId
        }
        val newReactions = if (newReactors.isEmpty()) {
            reactions - emoji
        } else {
            reactions + (emoji to newReactors)
        }
        return copy(reactions = newReactions)
    }

    /**
     * Check if a user has reacted with a specific emoji
     */
    fun hasUserReacted(emoji: String, checkUserId: String): Boolean {
        return reactions[emoji]?.contains(checkUserId) == true
    }

    /**
     * Get count of reactions for an emoji
     */
    fun reactionCount(emoji: String): Int = reactions[emoji]?.size ?: 0

    fun toJson(): String = Json.encodeToString(this)

    companion object {
        private val jsonParser = Json { ignoreUnknownKeys = true }

        fun fromJson(json: String): AudioMetadata = jsonParser.decodeFromString(json)
    }
}
