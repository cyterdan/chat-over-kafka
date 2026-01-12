package org.github.cyterdan.chat_over_kafka
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

object RdKafka {
    init { System.loadLibrary("native-lib") }

    external fun version(): String

    external fun createConsumerMTLS(
        brokers: String,
        groupId: String,
        caCertPath: String,
        clientCertPath: String,
        clientKeyPath: String,
        offsetStrategy: String
    ): Long

    external fun createProducerMTLS(
        brokers: String,
        caCertPath: String,
        clientCertPath: String,
        clientKeyPath: String
    ): Long

    external fun produceMessageBytes(
        producerPtr: Long,
        topic: String?,
        key: ByteArray?,  // can be null
        value: ByteArray?
    ): RecordMetadata

    external fun produceMessageBytesToPartition(
        producerPtr: Long,
        topic: String?,
        partition: Int,
        key: ByteArray?,  // can be null
        value: ByteArray?
    ): RecordMetadata

    external fun produceMessage(
        producerPtr: Long,
        topic: String,
        key: String?,
        value: String
    ): RecordMetadata


    external fun flushProducer(
        producerPtr: Long,
        timeoutMs: Int
    )

    external fun destroyProducer(
        producerPtr: Long
    )
    private external fun subscribe(consumerPtr: Long, topic: String, offsetStrategy: String)
    private external fun subscribeWithOffset(consumerPtr: Long, topic: String, partition: Int, offset: Long)
    private external fun pollMessage(consumerPtr: Long, timeoutMs: Int): KafkaMessage?
    private external fun closeConsumer(consumerPtr: Long)
    /**
     * Create a consumer with mTLS and return a Flow that emits messages from the earliest offset
     */
    fun consumeFromMTLS(
        brokers: String,
        groupId: String,
        topic: String,
        caCertPath: String,
        clientCertPath: String,
        clientKeyPath: String,
        offsetStrategy: String,
        pollTimeoutMs: Int = 1000
    ): Flow<KafkaMessage> = flow {
        android.util.Log.i("Kafka", "Creating consumer: topic=$topic, groupId=$groupId, offsetStrategy=$offsetStrategy")
        val consumerPtr = createConsumerMTLS(brokers, groupId, caCertPath, clientCertPath, clientKeyPath, offsetStrategy)
        try {
            subscribe(consumerPtr, topic, offsetStrategy)
            android.util.Log.i("Kafka", "Subscribed to topic=$topic with offsetStrategy=$offsetStrategy")

            var pollCount = 0
            var messageCount = 0
            while (coroutineContext.isActive) {
                val message = pollMessage(consumerPtr, pollTimeoutMs)
                pollCount++
                if (message != null) {
                    messageCount++
                    android.util.Log.d("Kafka", "Polled message #$messageCount: topic=${message.topic}, partition=${message.partition}, offset=${message.offset}")
                    emit(message)
                } else if (pollCount % 10 == 0) {
                    android.util.Log.d("Kafka", "Polled $pollCount times, received $messageCount messages (no message in last poll)")
                }
            }
            android.util.Log.i("Kafka", "Consumer stopped: polled $pollCount times, received $messageCount messages")
        } finally {
            closeConsumer(consumerPtr)
        }
    }

    /**
     * Create a consumer with mTLS that starts from a specific partition and offset
     */
    fun consumeFromMTLSWithOffset(
        brokers: String,
        groupId: String,
        topic: String,
        caCertPath: String,
        clientCertPath: String,
        clientKeyPath: String,
        partition: Int,
        offset: Long,
        pollTimeoutMs: Int = 1000
    ): Flow<KafkaMessage> = flow {
        // For assign mode, offsetStrategy doesn't matter since we're seeking to a specific offset
        val consumerPtr = createConsumerMTLS(brokers, groupId, caCertPath, clientCertPath, clientKeyPath, "earliest")
        try {
            subscribeWithOffset(consumerPtr, topic, partition, offset)

            while (coroutineContext.isActive) {
                val message = pollMessage(consumerPtr, pollTimeoutMs)
                if (message != null) {
                    emit(message)
                }
            }
        } finally {
            closeConsumer(consumerPtr)
        }
    }


}