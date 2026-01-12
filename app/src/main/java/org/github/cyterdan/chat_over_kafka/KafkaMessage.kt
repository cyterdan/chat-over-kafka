package org.github.cyterdan.chat_over_kafka

data class KafkaMessage(
    val key: ByteArray?,
    val value: ByteArray?,
    val topic: String,
    val partition: Int,
    val offset: Long
) {
    fun keyAsString(): String? = key?.toString(Charsets.UTF_8)
    fun valueAsString(): String? = value?.toString(Charsets.UTF_8)
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as KafkaMessage

        if (key != null) {
            if (other.key == null) return false
            if (!key.contentEquals(other.key)) return false
        } else if (other.key != null) return false
        if (value != null) {
            if (other.value == null) return false
            if (!value.contentEquals(other.value)) return false
        } else if (other.value != null) return false
        if (topic != topic) return false
        if (partition != other.partition) return false
        if (offset != other.offset) return false

        return true
    }

    override fun hashCode(): Int {
        var result = key?.contentHashCode() ?: 0
        result = 31 * result + (value?.contentHashCode() ?: 0)
        result = 31 * result + topic.hashCode()
        result = 31 * result + partition
        result = 31 * result + offset.hashCode()
        return result
    }
}