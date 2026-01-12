package org.github.cyterdan.chat_over_kafka.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.github.cyterdan.chat_over_kafka.data.SettingsRepository

@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModelFactory(SettingsRepository(context = androidx.compose.ui.platform.LocalContext.current))
    )
) {
    val kafkaHostname by settingsViewModel.kafkaHostname.collectAsState()
    val kafkaTopic by settingsViewModel.kafkaTopic.collectAsState()
    val clientCertUri by settingsViewModel.clientCertUri.collectAsState()
    val clientKeyUri by settingsViewModel.clientKeyUri.collectAsState()
    val caCertUri by settingsViewModel.caCertUri.collectAsState()
    val userId by settingsViewModel.userId.collectAsState()

    val clientCertLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri -> uri?.let { settingsViewModel.saveClientCertUri(it.toString()) } }
    )

    val clientKeyLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri -> uri?.let { settingsViewModel.saveClientKeyUri(it.toString()) } }
    )

    val caCertLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri -> uri?.let { settingsViewModel.saveCaCertUri(it.toString()) } }
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedTextField(
            value = userId,
            onValueChange = { settingsViewModel.saveUserId(it) },
            label = { Text("User ID") },
            placeholder = { Text("Enter your user ID") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = kafkaHostname,
            onValueChange = { settingsViewModel.saveKafkaHostname(it) },
            label = { Text("Kafka Hostname") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = kafkaTopic,
            onValueChange = { settingsViewModel.saveKafkaTopic(it) },
            label = { Text("Kafka Topic") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(onClick = { clientCertLauncher.launch("*/*") }) {
            Text("Select Client Certificate")
        }
        Text(text = clientCertUri)

        Button(onClick = { clientKeyLauncher.launch("*/*") }) {
            Text("Select Client Key")
        }
        Text(text = clientKeyUri)

        Button(onClick = { caCertLauncher.launch("*/*") }) {
            Text("Select CA Certificate")
        }
        Text(text = caCertUri)
    }
}
