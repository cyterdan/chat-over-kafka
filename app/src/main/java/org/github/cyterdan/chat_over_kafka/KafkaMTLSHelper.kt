package org.github.cyterdan.chat_over_kafka

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.FileOutputStream

object KafkaMTLSHelper {

    private fun copyAssetToInternalStorage(context: Context, assetName: String): String {
        val file = File(context.filesDir, assetName)
       //cdse if (file.exists()) return file.absolutePath

        context.assets.open(assetName).use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        return file.absolutePath
    }

    fun createProducerMTLSFromAssets(
        brokers: String,
        context: Context,
        caAssetName: String,
        clientCertAssetName: String,
        clientKeyAssetName: String
    ): Long {
        val caCertPath = copyAssetToInternalStorage(context, caAssetName)
        val clientCertPath = copyAssetToInternalStorage(context, clientCertAssetName)
        val clientKeyPath = copyAssetToInternalStorage(context, clientKeyAssetName)

        return RdKafka.createProducerMTLS(brokers, caCertPath, clientCertPath, clientKeyPath)
    }

    fun consumeFromMTLSFromAssets(
        context: Context,
        brokers: String,
        groupId: String,
        topic: String,
        caAssetName: String,
        clientCertAssetName: String,
        clientKeyAssetName: String,
        offsetStrategy: String = "latest"
    ): Flow<KafkaMessage> {
        val caCertPath = copyAssetToInternalStorage(context, caAssetName)
        val clientCertPath = copyAssetToInternalStorage(context, clientCertAssetName)
        val clientKeyPath = copyAssetToInternalStorage(context, clientKeyAssetName)

        return RdKafka.consumeFromMTLS(
            brokers = brokers,
            groupId = groupId,
            topic = topic,
            caCertPath = caCertPath,
            clientCertPath = clientCertPath,
            clientKeyPath = clientKeyPath,
            offsetStrategy = offsetStrategy
        )
    }

    fun consumeFromMTLSFromAssetsWithOffset(
        context: Context,
        brokers: String,
        groupId: String,
        topic: String,
        caAssetName: String,
        clientCertAssetName: String,
        clientKeyAssetName: String,
        partition: Int,
        offset: Long
    ): Flow<KafkaMessage> {
        val caCertPath = copyAssetToInternalStorage(context, caAssetName)
        val clientCertPath = copyAssetToInternalStorage(context, clientCertAssetName)
        val clientKeyPath = copyAssetToInternalStorage(context, clientKeyAssetName)

        return RdKafka.consumeFromMTLSWithOffset(
            brokers = brokers,
            groupId = groupId,
            topic = topic,
            caCertPath = caCertPath,
            clientCertPath = clientCertPath,
            clientKeyPath = clientKeyPath,
            partition = partition,
            offset = offset
        )
    }
}