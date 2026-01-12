package org.github.cyterdan.chat_over_kafka.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.github.cyterdan.chat_over_kafka.data.SettingsRepository

class SettingsViewModel(private val settingsRepository: SettingsRepository) : ViewModel() {

    val kafkaHostname: StateFlow<String> = settingsRepository.kafkaHostname.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ""
    )

    val kafkaTopic: StateFlow<String> = settingsRepository.kafkaTopic.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ""
    )

    val clientCertUri: StateFlow<String> = settingsRepository.clientCertUri.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ""
    )

    val clientKeyUri: StateFlow<String> = settingsRepository.clientKeyUri.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ""
    )

    val caCertUri: StateFlow<String> = settingsRepository.caCertUri.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ""
    )

    val userId: StateFlow<String> = settingsRepository.userId.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ""
    )

    fun saveKafkaHostname(hostname: String) {
        viewModelScope.launch {
            settingsRepository.saveKafkaHostname(hostname)
        }
    }

    fun saveKafkaTopic(topic: String) {
        viewModelScope.launch {
            settingsRepository.saveKafkaTopic(topic)
        }
    }

    fun saveClientCertUri(uri: String) {
        viewModelScope.launch {
            settingsRepository.saveClientCertUri(uri)
        }
    }

    fun saveClientKeyUri(uri: String) {
        viewModelScope.launch {
            settingsRepository.saveClientKeyUri(uri)
        }
    }

    fun saveCaCertUri(uri: String) {
        viewModelScope.launch {
            settingsRepository.saveCaCertUri(uri)
        }
    }

    fun saveUserId(userId: String) {
        viewModelScope.launch {
            settingsRepository.saveUserId(userId)
        }
    }
}