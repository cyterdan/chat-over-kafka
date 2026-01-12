package org.github.cyterdan.chat_over_kafka.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object PreferenceKeys {
        val KAFKA_HOSTNAME = stringPreferencesKey("kafka_hostname")
        val KAFKA_TOPIC = stringPreferencesKey("kafka_topic")
        val CLIENT_CERT_URI = stringPreferencesKey("client_cert_uri")
        val CLIENT_KEY_URI = stringPreferencesKey("client_key_uri")
        val CA_CERT_URI = stringPreferencesKey("ca_cert_uri")
        val USER_ID = stringPreferencesKey("user_id")
    }

    val kafkaHostname: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[PreferenceKeys.KAFKA_HOSTNAME] ?: ""
        }

    val kafkaTopic: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[PreferenceKeys.KAFKA_TOPIC] ?: ""
        }

    val clientCertUri: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[PreferenceKeys.CLIENT_CERT_URI] ?: ""
        }

    val clientKeyUri: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[PreferenceKeys.CLIENT_KEY_URI] ?: ""
        }

    val caCertUri: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[PreferenceKeys.CA_CERT_URI] ?: ""
        }

    val userId: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[PreferenceKeys.USER_ID] ?: "user-${System.currentTimeMillis()}"
        }

    suspend fun saveKafkaHostname(hostname: String) {
        context.dataStore.edit {
            it[PreferenceKeys.KAFKA_HOSTNAME] = hostname
        }
    }

    suspend fun saveKafkaTopic(topic: String) {
        context.dataStore.edit {
            it[PreferenceKeys.KAFKA_TOPIC] = topic
        }
    }

    suspend fun saveClientCertUri(uri: String) {
        context.dataStore.edit {
            it[PreferenceKeys.CLIENT_CERT_URI] = uri
        }
    }

    suspend fun saveClientKeyUri(uri: String) {
        context.dataStore.edit {
            it[PreferenceKeys.CLIENT_KEY_URI] = uri
        }
    }

    suspend fun saveCaCertUri(uri: String) {
        context.dataStore.edit {
            it[PreferenceKeys.CA_CERT_URI] = uri
        }
    }

    suspend fun saveUserId(userId: String) {
        context.dataStore.edit {
            it[PreferenceKeys.USER_ID] = userId
        }
    }
}