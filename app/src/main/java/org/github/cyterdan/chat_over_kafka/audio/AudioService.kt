package org.github.cyterdan.chat_over_kafka.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.core.app.ActivityCompat
import com.theeasiestway.opus.Constants
import com.theeasiestway.opus.Opus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles audio recording with Opus encoding and playback with Opus decoding.
 *
 * Audio format: 48kHz mono 16-bit PCM, encoded to Opus in 60ms frames.
 * Uses ByteArray for encoded Opus data (sent/received via Kafka).
 */
class AudioService(private val context: Context, private val coroutineScope: CoroutineScope) {

    companion object {
        private val SAMPLE_RATE = Constants.SampleRate._48000()
        private val CHANNELS = Constants.Channels.mono()
        private val APPLICATION = Constants.Application.audio()
        private val FRAME_SIZE = Constants.FrameSize._2880()
        private val FRAME_SIZE_SAMPLES = FRAME_SIZE.v
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_DURATION_MS = 60L
        private const val WAVEFORM_UPDATE_INTERVAL = 2
    }

    private val opusEncoder = Opus()
    private val opusDecoder = Opus()

    private var recorder: AudioRecord? = null
    private var recordingJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null

    private val audioDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @Volatile private var pendingDecodeCount = 0
    private val pendingDecodeLock = Object()
    @Volatile private var isShuttingDown = false
    @Volatile private var decoderInitialized = false
    private var waveformUpdateCounter = 0

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _waveformData = MutableStateFlow(WaveformData())
    val waveformData: StateFlow<WaveformData> = _waveformData

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress

    private var expectedTotalDurationMs = 0L

    fun setExpectedDuration(durationMs: Long) {
        expectedTotalDurationMs = durationMs
        _playbackProgress.value = 0f
    }

    fun startStreaming(onEncodedChunk: (ByteArray) -> Unit) {
        if (recordingJob?.isActive == true) return

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO permission not granted")
        }

        recordingJob = coroutineScope.launch(Dispatchers.IO) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE.v, AudioFormat.CHANNEL_IN_MONO, AUDIO_FORMAT)
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE.v,
                AudioFormat.CHANNEL_IN_MONO,
                AUDIO_FORMAT,
                minBufferSize * 2
            )

            opusEncoder.encoderInit(SAMPLE_RATE, CHANNELS, APPLICATION)

            try {
                recorder?.startRecording()
                _isRecording.value = true

                val pcmBuffer = ShortArray(FRAME_SIZE_SAMPLES)
                var sentFrames = 0

                while (isActive) {
                    val samplesRead = recorder?.read(pcmBuffer, 0, pcmBuffer.size) ?: 0
                    if (samplesRead > 0) {
                        waveformUpdateCounter++
                        if (waveformUpdateCounter >= WAVEFORM_UPDATE_INTERVAL) {
                            _waveformData.value = _waveformData.value.addSample(calculateRMSAmplitude(pcmBuffer))
                            waveformUpdateCounter = 0
                        }

                        // Convert PCM shorts to bytes, encode, get bytes directly
                        val pcmBytes = pcmShortsToBytes(pcmBuffer)
                        val encoded = opusEncoder.encode(pcmBytes, FRAME_SIZE)
                        if (encoded != null && encoded.size >= 25) {
                            sentFrames++
                            onEncodedChunk(encoded)
                        } else {
                            // Send silence marker to maintain timing
                            onEncodedChunk(ByteArray(2))
                            sentFrames++
                        }
                    }
                }

                Log.i("AudioService", "Recording complete: $sentFrames frames sent")
            } finally {
                withContext(Dispatchers.Main) { stopStreaming() }
            }
        }
    }

    fun stopStreaming() {
        if (_isRecording.value) {
            _isRecording.value = false
            recordingJob?.cancel()
            recorder?.stop()
            recorder?.release()
            recorder = null
            _waveformData.value = WaveformData()
            waveformUpdateCounter = 0
            opusEncoder.encoderRelease()
        }
    }

    fun startPlayback() {
        if (playbackJob?.isActive == true) return

        playbackStartTime = 0L
        firstWriteTime = 0L
        isShuttingDown = false
        pendingDecodeCount = 0
        decoderInitialized = false

        playbackJob = coroutineScope.launch(Dispatchers.IO) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE.v, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE.v)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            // Initialize decoder on audioDispatcher to ensure thread safety
            withContext(audioDispatcher) {
                opusDecoder.decoderInit(SAMPLE_RATE, CHANNELS)
                decoderInitialized = true
            }

            try {
                audioTrack?.play()
                _isPlaying.value = true
                while (isActive) {
                    delay(1000)
                }
            } finally {
                stopPlaybackInternal()
            }
        }
    }

    private var playbackStartTime = 0L
    private var firstWriteTime = 0L
    private var totalFramesConsumed = 0L

    fun onReceivedEncodedChunk(encodedData: ByteArray) {
        if (playbackJob?.isActive != true || encodedData.isEmpty() || isShuttingDown) return
        if (!decoderInitialized) return  // Skip until decoder is ready

        synchronized(pendingDecodeLock) { pendingDecodeCount++ }

        coroutineScope.launch(audioDispatcher) {
            try {
                // Double-check decoder state on the audio thread
                if (!decoderInitialized || isShuttingDown) {
                    return@launch
                }

                if (playbackStartTime == 0L) {
                    playbackStartTime = System.currentTimeMillis()
                    totalFramesConsumed = 0
                }
                totalFramesConsumed++

                val decodedPcm = if (encodedData.size == 2 && encodedData[0] == 0.toByte() && encodedData[1] == 0.toByte()) {
                    ShortArray(FRAME_SIZE_SAMPLES)
                } else {
                    // Decode bytes directly, get PCM bytes, convert to shorts for AudioTrack
                    val decodedBytes = opusDecoder.decode(encodedData, FRAME_SIZE)
                    if (decodedBytes == null || decodedBytes.isEmpty()) {
                        Log.w("AudioService", "Decode failed, inserting silence")
                        ShortArray(FRAME_SIZE_SAMPLES)
                    } else {
                        pcmBytesToShorts(decodedBytes)
                    }
                }

                if (decodedPcm.isNotEmpty()) {
                    if (playbackJob?.isActive == true) {
                        waveformUpdateCounter++
                        if (waveformUpdateCounter >= WAVEFORM_UPDATE_INTERVAL) {
                            _waveformData.value = _waveformData.value.addSample(calculateRMSAmplitude(decodedPcm))
                            waveformUpdateCounter = 0
                        }
                    }

                    val writeTime = System.currentTimeMillis()
                    val result = audioTrack?.write(decodedPcm, 0, decodedPcm.size) ?: 0

                    if (firstWriteTime == 0L) firstWriteTime = writeTime

                    if (expectedTotalDurationMs > 0 && firstWriteTime > 0) {
                        val elapsed = System.currentTimeMillis() - firstWriteTime
                        _playbackProgress.value = (elapsed.toFloat() / expectedTotalDurationMs).coerceIn(0f, 1f)
                    }

                    if (result < 0) Log.e("AudioService", "AudioTrack write error: $result")
                }
            } catch (e: Exception) {
                Log.e("AudioService", "Error decoding audio: ${e.message}", e)
            } finally {
                synchronized(pendingDecodeLock) {
                    pendingDecodeCount--
                    pendingDecodeLock.notifyAll()
                }
            }
        }
    }

    suspend fun stopPlaybackGracefully(maxWaitMs: Long = 5000L) {
        if (!_isPlaying.value) return

        val chunksToProcess = pendingDecodeCount
        isShuttingDown = true

        val startTime = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            synchronized(pendingDecodeLock) {
                while (pendingDecodeCount > 0) {
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed >= maxWaitMs) break
                    (pendingDecodeLock as Object).wait((maxWaitMs - elapsed).coerceAtMost(100))
                }
            }
        }

        val audioPlayedSoFar = if (firstWriteTime > 0) System.currentTimeMillis() - firstWriteTime else 0
        val remainingPlayTime = (chunksToProcess * FRAME_DURATION_MS - audioPlayedSoFar).coerceAtLeast(0) + 100
        delay(remainingPlayTime)
        stopPlayback()
    }

    private fun stopPlaybackInternal() {
        if (!_isPlaying.value && !decoderInitialized) return

        isShuttingDown = true
        Log.i("AudioService", "Playback stopped: $totalFramesConsumed frames")
        _isPlaying.value = false
        _waveformData.value = WaveformData()
        _playbackProgress.value = 0f
        expectedTotalDurationMs = 0L
        waveformUpdateCounter = 0
        audioTrack?.pause()
        audioTrack?.flush()
        audioTrack?.release()
        audioTrack = null

        // Release decoder on audioDispatcher to ensure thread safety
        if (decoderInitialized) {
            coroutineScope.launch(audioDispatcher) {
                decoderInitialized = false
                opusDecoder.decoderRelease()
            }
        }
    }

    fun stopPlayback() {
        if (_isPlaying.value) {
            playbackJob?.cancel()
            stopPlaybackInternal()
        }
    }

    private fun pcmShortsToBytes(shorts: ShortArray): ByteArray {
        // Convert 16-bit PCM samples to bytes (little endian, 2 bytes per sample)
        val buffer = ByteBuffer.allocate(shorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        shorts.forEach { buffer.putShort(it) }
        return buffer.array()
    }

    private fun pcmBytesToShorts(bytes: ByteArray): ShortArray {
        // Convert bytes back to 16-bit PCM samples (little endian)
        val shortBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(shortBuffer.remaining()).also { shortBuffer.get(it) }
    }

    private fun calculateRMSAmplitude(pcmData: ShortArray): Float {
        if (pcmData.isEmpty()) return 0f
        val sum = pcmData.sumOf { (it.toDouble() / Short.MAX_VALUE).let { n -> n * n } }
        return kotlin.math.sqrt(sum / pcmData.size).toFloat()
    }
}