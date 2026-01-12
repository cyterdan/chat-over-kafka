package org.github.cyterdan.chat_over_kafka

data class RecordMetadata(
    val partition: Int,
    val offset: Long
)
