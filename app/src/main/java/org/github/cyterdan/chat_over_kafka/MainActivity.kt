package org.github.cyterdan.chat_over_kafka

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.github.cyterdan.chat_over_kafka.audio.AudioService
import org.github.cyterdan.chat_over_kafka.data.KafkaConfig
import org.github.cyterdan.chat_over_kafka.ui.EqualizerVisualizer
import org.github.cyterdan.chat_over_kafka.ui.WaveformVisualizer
import org.github.cyterdan.chat_over_kafka.ui.theme.ChatoverkafkaTheme
import org.github.cyterdan.chat_over_kafka.ui.theme.LCDBackground
import org.github.cyterdan.chat_over_kafka.ui.theme.LCDBackgroundAlt
import org.github.cyterdan.chat_over_kafka.ui.theme.NeonGreen
import org.github.cyterdan.chat_over_kafka.ui.theme.Amber

// Channel configuration data class (used throughout the app)
data class ChannelConfig(
    val channelNumber: Int,
    val channelName: String,
    val brokerUrl: String,
    val audioTopic: String,
    val audioPartition: Int,
    val metadataTopic: String,
    val metadataPartition: Int,
    val caAssetName: String,
    val clientKeyAssetName: String,
    val clientCertAssetName: String
)

// Available channels - loaded from config at runtime
private var _availableChannels: List<ChannelConfig>? = null
val availableChannels: List<ChannelConfig>
    get() = _availableChannels ?: error("Config not loaded. Call loadChannelConfig() first.")

fun isChannelConfigLoaded(): Boolean = _availableChannels != null

/**
 * Load channel configuration from assets.
 * Must be called before accessing availableChannels.
 */
fun loadChannelConfig(context: Context) {
    if (_availableChannels != null) return  // Already loaded

    val config = KafkaConfig.loadFromAssets(context) ?: KafkaConfig.getDefaultConfig()

    _availableChannels = config.channels.map { channel ->
        ChannelConfig(
            channelNumber = channel.channelNumber,
            channelName = channel.channelName,
            brokerUrl = config.brokerUrl,
            audioTopic = channel.audioTopic,
            audioPartition = channel.audioPartition,
            metadataTopic = channel.metadataTopic,
            metadataPartition = channel.metadataPartition,
            caAssetName = config.certificates.caAssetName,
            clientKeyAssetName = config.certificates.clientKeyAssetName,
            clientCertAssetName = config.certificates.clientCertAssetName
        )
    }

    Log.i("MainActivity", "Loaded ${availableChannels.size} channels from config, broker: ${config.brokerUrl}")
}

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load Kafka configuration from assets
        loadChannelConfig(this)

        setContent {
            ChatoverkafkaTheme {
                val navController = rememberNavController()
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text("Chat over Kafka") }
                        )
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "chat",
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("chat") { ChatScreen() }
                    }
                }
            }
        }
    }
}
@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val audioService = remember { AudioService(context, coroutineScope) }

    // User ID is baked in at build time from provisioning
    val userId = BuildConfig.CHOK_USER_ID

    var hasAudioPermission by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> hasAudioPermission = isGranted }
    )

    LaunchedEffect(key1 = true) {
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val isPlaying by audioService.isPlaying.collectAsState()
    val isRecording by audioService.isRecording.collectAsState()
    val waveformData by audioService.waveformData.collectAsState()
    var consumerJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Channel selection
    var selectedChannelIndex by remember { mutableStateOf(0) }
    val currentChannel = availableChannels[selectedChannelIndex]

    // Playback toggle (walkie-talkie mode: always listening unless disabled or transmitting)
    var isPlaybackEnabled by remember { mutableStateOf(true) }

    // Connection state tracking
    var isConnecting by remember { mutableStateOf(false) }

    // Network monitoring for fast reconnection
    var isNetworkAvailable by remember { mutableStateOf(true) }
    var networkReconnectTrigger by remember { mutableStateOf(0) }

    // Monitor network connectivity
    DisposableEffect(context) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i("Network", "Network available")
                isNetworkAvailable = true
                // Trigger reconnection by incrementing counter
                networkReconnectTrigger++
            }

            override fun onLost(network: Network) {
                Log.w("Network", "Network lost")
                isNetworkAvailable = false
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (hasInternet && !isNetworkAvailable) {
                    Log.i("Network", "Internet connectivity restored")
                    isNetworkAvailable = true
                    networkReconnectTrigger++
                }
            }
        }

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        onDispose {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
    }

    // Producer handle - recreate when channel OR network changes
    val producerHandle = remember(selectedChannelIndex, networkReconnectTrigger) {
        Log.i("Kafka", "✓ Creating producer: Channel ${currentChannel.channelNumber} (${currentChannel.channelName}), Broker: ${currentChannel.brokerUrl}")
        KafkaMTLSHelper.createProducerMTLSFromAssets(
            brokers = currentChannel.brokerUrl,
            context = context,
            caAssetName = currentChannel.caAssetName,
            clientCertAssetName = currentChannel.clientCertAssetName,
            clientKeyAssetName = currentChannel.clientKeyAssetName
        )
    }

    // Track the first and last offset from the current recording session
    var sessionStartOffset by remember { mutableStateOf<RecordMetadata?>(null) }
    var sessionEndOffset by remember { mutableStateOf<RecordMetadata?>(null) }

    // Log channel changes and immediately show connecting state
    LaunchedEffect(selectedChannelIndex) {
        Log.i("ChatScreen", "═══════════════════════════════════════")
        Log.i("ChatScreen", "📻 CHANNEL CHANGE → ${currentChannel.channelNumber}: ${currentChannel.channelName}")
        Log.i("ChatScreen", "   Topic: ${currentChannel.audioTopic}")
        Log.i("ChatScreen", "   Broker: ${currentChannel.brokerUrl}")
        Log.i("ChatScreen", "═══════════════════════════════════════")

        // Immediately show connecting state for better UX
        if (isPlaybackEnabled && !isRecording) {
            isConnecting = true
        }
    }

    // Automatic playback management (walkie-talkie mode)
    LaunchedEffect(isPlaybackEnabled, isRecording, selectedChannelIndex, networkReconnectTrigger) {
        // Stop playback when recording or when playback is disabled
        if (isRecording || !isPlaybackEnabled) {
            if (isPlaying) {
                Log.i("ChatScreen", "Stopping playback (recording=$isRecording, enabled=$isPlaybackEnabled)")
                consumerJob?.cancel()
                consumerJob?.join()
                consumerJob = null
                audioService.stopPlayback()
            }
        }
        // Start playback when enabled and not recording
        else if (isPlaybackEnabled && !isRecording) {
            // Always restart consumer (even if already playing) to handle reconnections/channel changes
            if (isPlaying) {
                Log.i("ChatScreen", "⟳ Restarting consumer - switching to Channel ${currentChannel.channelNumber} (${currentChannel.channelName})")
                consumerJob?.cancel()
                consumerJob?.join()
                consumerJob = null
                audioService.stopPlayback()
                delay(100) // Brief delay before reconnecting
            }

            Log.i("ChatScreen", "▶ Starting playback: Channel ${currentChannel.channelNumber}, Topic: ${currentChannel.audioTopic}")
            audioService.startPlayback()

            // Start consuming with retry logic
            consumerJob = coroutineScope.launch(Dispatchers.IO) {
                var retryCount = 0
                val maxRetries = 5
                var backoffDelay = 1000L

                while (retryCount < maxRetries) {
                    try {
                        Log.i("Kafka", "Consumer connecting (attempt ${retryCount + 1}/$maxRetries)")
                        val consumerFlow = KafkaMTLSHelper.consumeFromMTLSFromAssets(
                            context = context,
                            brokers = currentChannel.brokerUrl,
                            caAssetName = currentChannel.caAssetName,
                            clientKeyAssetName = currentChannel.clientKeyAssetName,
                            clientCertAssetName = currentChannel.clientCertAssetName,
                            topic = currentChannel.audioTopic,
                            groupId = "chat-group-${System.currentTimeMillis()}",
                            offsetStrategy = "latest"
                        )

                        // Connection established successfully, mark as connected
                        // even if no messages have arrived yet
                        delay(500) // Brief delay to ensure connection is stable
                        isConnecting = false
                        Log.i("Kafka", "✓ Consumer connected successfully, waiting for messages...")

                        consumerFlow.collect { receivedData ->
                            retryCount = 0 // Reset retry count on successful message
                            backoffDelay = 1000L
                            if (receivedData.value != null) {
                                Log.d("ChatScreen", "Received audio chunk: ${receivedData.value.size} bytes")
                                audioService.onReceivedEncodedChunk(receivedData.value)
                            }
                        }
                        // If flow completes normally, break the retry loop
                        break
                    } catch (e: Exception) {
                        retryCount++
                        Log.e("Kafka", "Consumer error (attempt $retryCount/$maxRetries): ${e.message}", e)
                        if (retryCount < maxRetries) {
                            Log.i("Kafka", "Retrying in ${backoffDelay}ms...")
                            delay(backoffDelay)
                            backoffDelay = (backoffDelay * 1.5).toLong().coerceAtMost(10000L) // Exponential backoff, max 10s
                        }
                    }
                }
                if (retryCount >= maxRetries) {
                    Log.e("Kafka", "Consumer failed after $maxRetries attempts")
                    isConnecting = false
                }
            }
        }
    }

    // Track recording start time for duration calculation
    var recordingStartTime by remember { mutableStateOf(0L) }

    LaunchedEffect(isPressed, hasAudioPermission) {
        if (isPressed && hasAudioPermission) {
            Log.d("ChatScreen", "Starting streaming")

            // Reset session offsets and start time when starting a new recording
            sessionStartOffset = null
            sessionEndOffset = null
            recordingStartTime = System.currentTimeMillis()

            // Note: Playback is automatically stopped by the playback management LaunchedEffect above

            audioService.startStreaming { encodedData ->
                // Launch Kafka send in a separate coroutine to avoid blocking recording
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val meta = RdKafka.produceMessageBytesToPartition(
                            producerPtr = producerHandle,
                            topic = currentChannel.audioTopic,
                            partition = currentChannel.audioPartition,
                            key = "user1".toByteArray(),
                            value = encodedData
                        )

                        synchronized(this) {
                            if (sessionStartOffset == null || meta.offset < sessionStartOffset!!.offset) {
                                sessionStartOffset = meta
                            }
                            if (sessionEndOffset == null || meta.offset > sessionEndOffset!!.offset) {
                                sessionEndOffset = meta
                            }
                        }
                    } catch (e: RuntimeException) {
                        Log.e("Kafka", "Produce failed: ${e.message}")
                    }
                }
            }
        } else {
            Log.d("ChatScreen", "Stopping streaming")
            audioService.stopStreaming()
            try {
                RdKafka.flushProducer(producerPtr = producerHandle, timeoutMs = 5000)

                // Publish metadata for the recording session
                if (sessionStartOffset != null && sessionEndOffset != null) {
                    val messageCount = sessionEndOffset!!.offset - sessionStartOffset!!.offset + 1
                    Log.i("ChatScreen", "Recording complete: $messageCount messages")

                    try {
                        val metadata = AudioMetadata(
                            userId = userId.ifEmpty { "anonymous" },
                            channelId = currentChannel.channelNumber,
                            startOffset = sessionStartOffset!!.offset,
                            endOffset = sessionEndOffset!!.offset,
                            timestamp = System.currentTimeMillis(),
                            messageCount = messageCount
                        )

                        RdKafka.produceMessageBytesToPartition(
                            producerPtr = producerHandle,
                            topic = currentChannel.metadataTopic,
                            partition = currentChannel.metadataPartition,
                            key = metadata.messageKey().toByteArray(),
                            value = metadata.toJson().toByteArray()
                        )
                    } catch (e: Exception) {
                        Log.e("ChatScreen", "Failed to publish metadata: ${e.message}", e)
                    }
                }

                // Small delay to ensure Kafka has fully propagated all messages
                delay(500)
            } catch (e: RuntimeException) {
                Log.e("Kafka", "Flush failed: ${e.message}")
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top section: LCD Display Panel (Winamp-style)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(24.dp)
                    .background(
                        color = when {
                            isRecording -> LCDBackgroundAlt  // Red tinted LCD when recording
                            else -> LCDBackground  // Green tinted LCD otherwise
                        },
                        shape = RoundedCornerShape(8.dp)  // Slightly sharper corners for retro look
                    )
                    .border(
                        width = 3.dp,
                        color = when {
                            isRecording -> MaterialTheme.colorScheme.error
                            isPlaying -> NeonGreen
                            else -> MaterialTheme.colorScheme.outline
                        },
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isRecording -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // Get current amplitude from the latest waveform sample
                            // RMS amplitude is typically 0.0-0.3 for speech, scale up for visual effect
                            val currentAmplitude = waveformData.samples.lastOrNull() ?: 0f
                            val scaledAmplitude = (currentAmplitude * 4f).coerceIn(0f, 1f)

                            EqualizerVisualizer(
                                amplitude = scaledAmplitude,
                                isActive = true,
                                barCount = 5,
                                barColor = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = ">>> RECORDING <<<",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    isPlaying -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            WaveformVisualizer(
                                waveformData = waveformData,
                                modifier = Modifier.fillMaxWidth(),
                                color = NeonGreen
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = ">>> PLAYING <<<",
                                style = MaterialTheme.typography.titleMedium,
                                color = NeonGreen
                            )
                        }
                    }
                    else -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "[ READY ]",
                                style = MaterialTheme.typography.headlineMedium,
                                color = NeonGreen
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Ch ${currentChannel.channelNumber}: ${currentChannel.channelName}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NeonGreen.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            // Middle section: Channel selector
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CHANNEL",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Network status indicator
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = if (isNetworkAvailable)
                                    androidx.compose.ui.graphics.Color(0xFF4CAF50) // Green
                                else
                                    androidx.compose.ui.graphics.Color(0xFFF44336), // Red
                                shape = CircleShape
                            )
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Channel down button
                    FilledTonalButton(
                        onClick = {
                            if (selectedChannelIndex > 0 && !isRecording && !isConnecting) {
                                selectedChannelIndex--
                            }
                        },
                        enabled = selectedChannelIndex > 0 && !isRecording && !isConnecting,
                        modifier = Modifier.size(48.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) {
                        Text("◀", style = MaterialTheme.typography.titleLarge)
                    }

                    // Channel display
                    Box(
                        modifier = Modifier
                            .size(width = 120.dp, height = 56.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = currentChannel.channelNumber.toString(),
                                style = MaterialTheme.typography.headlineLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = currentChannel.channelName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }

                    // Channel up button
                    FilledTonalButton(
                        onClick = {
                            if (selectedChannelIndex < availableChannels.size - 1 && !isRecording && !isConnecting) {
                                selectedChannelIndex++
                            }
                        },
                        enabled = selectedChannelIndex < availableChannels.size - 1 && !isRecording && !isConnecting,
                        modifier = Modifier.size(48.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) {
                        Text("▶", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }

            // Bottom section: Controls
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Push-to-Talk button (large circular button)
                Button(
                    onClick = {
                        Log.d("ChatScreen", "Record button clicked")
                    },
                    interactionSource = interactionSource,
                    modifier = Modifier
                        .size(120.dp)
                        .border(
                            width = 4.dp,
                            color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                            shape = CircleShape
                        ),
                    enabled = hasAudioPermission && !isConnecting,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording)
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = if (isRecording)
                            MaterialTheme.colorScheme.onErrorContainer
                        else
                            MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Text(
                        text = if (isRecording) "RELEASE" else "PUSH\nTO\nTALK",
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Timeline button
                FilledTonalButton(
                    onClick = {
                        val intent = Intent(context, TimelineActivity::class.java)
                        intent.putExtra("channelNumber", selectedChannelIndex)
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(0.6f),
                    enabled = !isRecording && !isConnecting
                ) {
                    Text("VIEW TIMELINE")
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Playback toggle (Walkie-talkie mode)
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .background(
                            color = if (isPlaybackEnabled)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "RECEIVE",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isPlaybackEnabled)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (isPlaybackEnabled) {
                                when {
                                    isConnecting -> "Connecting..."
                                    isRecording -> "Paused (Transmitting)"
                                    else -> "Listening"
                                }
                            } else "Off",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isPlaybackEnabled)
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = isPlaybackEnabled,
                        onCheckedChange = { isPlaybackEnabled = it },
                        enabled = !isRecording && !isConnecting  // Can't toggle while transmitting or connecting
                    )
                }
            }
        }
    }
}