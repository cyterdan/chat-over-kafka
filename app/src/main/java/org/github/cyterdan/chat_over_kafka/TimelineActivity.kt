package org.github.cyterdan.chat_over_kafka

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.github.cyterdan.chat_over_kafka.audio.AudioService
import org.github.cyterdan.chat_over_kafka.ui.PlaybackState
import org.github.cyterdan.chat_over_kafka.ui.TimelineView
import org.github.cyterdan.chat_over_kafka.ui.theme.ChatoverkafkaTheme

class TimelineActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure config is loaded (in case activity is started directly)
        if (!isChannelConfigLoaded()) {
            loadChannelConfig(this)
        }

        // Get channel info from intent
        val channelNumber = intent.getIntExtra("channelNumber", 0)
        val currentChannel = availableChannels.getOrNull(channelNumber) ?: availableChannels[0]

        setContent {
            ChatoverkafkaTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text("Timeline - Ch ${currentChannel.channelNumber}: ${currentChannel.channelName}") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    TimelineScreen(
                        currentChannel = currentChannel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
fun TimelineScreen(
    currentChannel: ChannelConfig,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val audioService = remember { AudioService(context, coroutineScope) }

    // Get current user ID from BuildConfig (same source as recording)
    val currentUserId = BuildConfig.CHOK_USER_ID

    // Timeline state
    var timeline by remember { mutableStateOf<List<TimelineEntry>>(emptyList()) }
    var selectedTimeRange by remember { mutableStateOf(TimeRange.ONE_HOUR) }
    var timelineJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var isTimelineLoading by remember { mutableStateOf(true) }  // Start as true to show initial loading
    var consumerJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Playback state
    var playbackState by remember { mutableStateOf(PlaybackState()) }
    val waveformData by audioService.waveformData.collectAsState()
    val audioProgress by audioService.playbackProgress.collectAsState()

    // Producer for publishing reactions
    val producerHandle = remember(currentChannel) {
        Log.i("Timeline", "Creating producer for reactions...")
        KafkaMTLSHelper.createProducerMTLSFromAssets(
            brokers = currentChannel.brokerUrl,
            context = context,
            caAssetName = currentChannel.caAssetName,
            clientCertAssetName = currentChannel.clientCertAssetName,
            clientKeyAssetName = currentChannel.clientKeyAssetName
        )
    }

    // Load timeline for this channel
    LaunchedEffect(currentChannel, selectedTimeRange) {
        timelineJob?.cancel()
        timeline = emptyList()
        isTimelineLoading = true

        timelineJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                Log.i("Timeline", "Loading timeline for channel ${currentChannel.channelNumber}...")

                val metadataFlow = KafkaMTLSHelper.consumeFromMTLSFromAssets(
                    context = context,
                    brokers = currentChannel.brokerUrl,
                    caAssetName = currentChannel.caAssetName,
                    clientKeyAssetName = currentChannel.clientKeyAssetName,
                    clientCertAssetName = currentChannel.clientCertAssetName,
                    topic = currentChannel.metadataTopic,
                    groupId = "timeline-${System.currentTimeMillis()}",
                    offsetStrategy = "earliest"
                )

                var isFirstEmission = true
                TimelineManager.consumeTimeline(
                    metadataFlow = metadataFlow,
                    timeRangeHours = selectedTimeRange.hours,
                    metadataPartition = currentChannel.metadataPartition
                ).collect { updatedTimeline ->
                    timeline = updatedTimeline

                    // Skip the first emission (always empty), then stop loading if we have data or no data
                    if (!isFirstEmission) {
                        isTimelineLoading = false
                    }
                    isFirstEmission = false

                    Log.d("Timeline", "Timeline updated: ${updatedTimeline.size} entries")
                }
            } catch (e: Exception) {
                isTimelineLoading = false
                Log.e("Timeline", "Failed to load timeline: ${e.message}", e)
            }
        }
    }

    // Cleanup when leaving
    DisposableEffect(Unit) {
        onDispose {
            timelineJob?.cancel()
            consumerJob?.cancel()
            audioService.stopPlayback()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            TimelineView(
                timeline = timeline,
                selectedRange = selectedTimeRange,
                isLoading = isTimelineLoading,
                currentUserId = currentUserId,
                playbackState = playbackState.copy(progress = audioProgress, waveformData = waveformData),
                onRangeChange = { newRange ->
                    selectedTimeRange = newRange
                },
                onReact = { startOffset, emoji ->
                    Log.i("Timeline", "React: $emoji on message $startOffset")

                    // Find the entry and toggle reaction
                    val entry = timeline.find { it.metadata.startOffset == startOffset }
                    if (entry != null) {
                        val updatedMetadata = entry.metadata.toggleReaction(emoji, currentUserId)
                        val metadataJson = updatedMetadata.toJson()

                        Log.i("Timeline", "Publishing reaction update: $metadataJson")

                        // Update local state immediately for responsive UI
                        timeline = timeline.map { e ->
                            if (e.metadata.startOffset == startOffset) {
                                e.copy(metadata = updatedMetadata)
                            } else {
                                e
                            }
                        }

                        // Publish to Kafka
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                RdKafka.produceMessageBytesToPartition(
                                    producerPtr = producerHandle,
                                    topic = currentChannel.metadataTopic,
                                    partition = currentChannel.metadataPartition,
                                    key = updatedMetadata.messageKey().toByteArray(),
                                    value = metadataJson.toByteArray()
                                )
                                RdKafka.flushProducer(producerHandle, 1000)
                                Log.i("Timeline", "Reaction published successfully")
                            } catch (e: Exception) {
                                Log.e("Timeline", "Failed to publish reaction: ${e.message}", e)
                            }
                        }
                    }
                },
                onPlayFromOffset = { startOffset, endOffset, durationMs ->
                    Log.i("Timeline", "═══════════════════════════════════════")
                    Log.i("Timeline", "▶ PLAY FROM TIMELINE CLICKED")
                    Log.i("Timeline", "   Offset range: $startOffset - $endOffset")
                    Log.i("Timeline", "   Duration: ${durationMs}ms")
                    Log.i("Timeline", "   Channel: ${currentChannel.channelNumber}")
                    Log.i("Timeline", "   Topic: ${currentChannel.audioTopic}")
                    Log.i("Timeline", "═══════════════════════════════════════")

                    // Cancel any existing playback
                    consumerJob?.cancel()
                    audioService.stopPlayback()

                    // Set initial playback state
                    playbackState = PlaybackState(
                        playingEntryId = startOffset,
                        progress = 0f
                    )

                    // Set expected duration for progress calculation
                    audioService.setExpectedDuration(durationMs)

                    // Start playback from specific offset range
                    consumerJob = coroutineScope.launch(Dispatchers.IO) {
                        try {
                            Log.i("Timeline", "Creating Kafka consumer from offset $startOffset on partition ${currentChannel.audioPartition}...")
                            val audioFlow = KafkaMTLSHelper.consumeFromMTLSFromAssetsWithOffset(
                                context = context,
                                brokers = currentChannel.brokerUrl,
                                groupId = "timeline-playback-${System.currentTimeMillis()}",
                                topic = currentChannel.audioTopic,
                                caAssetName = currentChannel.caAssetName,
                                clientCertAssetName = currentChannel.clientCertAssetName,
                                clientKeyAssetName = currentChannel.clientKeyAssetName,
                                partition = currentChannel.audioPartition,
                                offset = startOffset
                            )

                            Log.i("Timeline", "Starting audio playback...")
                            audioService.startPlayback()

                            // Wait for decoder to initialize
                            delay(100)

                            var messageCount = 0
                            audioFlow.collect { message ->
                                if (!isActive) {
                                    Log.i("Timeline", "Consumer job cancelled")
                                    audioService.stopPlayback()
                                    playbackState = PlaybackState()
                                    return@collect
                                }

                                messageCount++
                                Log.d("Timeline", "Received message #$messageCount at offset ${message.offset}")

                                // Queue audio chunk for playback (do this BEFORE checking end offset)
                                message.value?.let { bytes ->
                                    Log.d("Timeline", "Playing audio chunk: ${bytes.size} bytes")
                                    audioService.onReceivedEncodedChunk(bytes)
                                }

                                // Stop when we reach the end offset (after queueing the last chunk)
                                if (message.offset >= endOffset) {
                                    Log.i("Timeline", "✓ Reached end offset (${message.offset} >= $endOffset), stopping playback after $messageCount messages")
                                    // Wait for all queued audio to be decoded and played
                                    audioService.stopPlaybackGracefully()
                                    playbackState = PlaybackState()  // Reset playback state
                                    cancel()
                                    return@collect
                                }
                            }
                            Log.i("Timeline", "Consumer flow completed")
                            playbackState = PlaybackState()  // Reset on completion
                        } catch (e: Exception) {
                            Log.e("Timeline", "Playback from offset failed: ${e.message}", e)
                            e.printStackTrace()
                            playbackState = PlaybackState()  // Reset on error
                        } finally {
                            audioService.stopPlayback()
                        }
                    }
                }
            )
        }
    }
}
