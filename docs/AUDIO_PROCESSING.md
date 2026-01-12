# Audio Processing Pipeline

This document explains how audio is captured, encoded, transmitted, and played back in Chat Over Kafka.

## Overview

```
[Microphone] → [AudioRecord] → [PCM 16-bit] → [Opus Encoder] → [Kafka] → [Opus Decoder] → [AudioTrack] → [Speaker]
```

## Audio Parameters

| Parameter | Value | Notes |
|-----------|-------|-------|
| Sample Rate | 48,000 Hz | Standard high-quality audio rate |
| Channels | Mono | Single channel for voice |
| Bit Depth | 16-bit PCM | Signed short (-32768 to 32767) |
| Frame Size | 2,880 samples | 60ms of audio at 48kHz |
| Frame Duration | 60ms | `2880 / 48000 = 0.06s` |

## Recording Pipeline

### 1. Audio Capture (`AudioRecord`)
- **Source**: `MediaRecorder.AudioSource.VOICE_COMMUNICATION` (optimized for voice, echo cancellation)
- **Format**: Mono, 16-bit PCM, 48kHz
- **Buffer**: 2x minimum buffer size for stability
- **Thread Priority**: `THREAD_PRIORITY_URGENT_AUDIO` for low latency

### 2. Opus Encoding
The Opus codec compresses raw PCM audio for efficient transmission:

```
PCM Frame (2880 shorts = 5,760 bytes) → Opus Encode → Compressed (~100-400 bytes)
```

- **Input**: `ShortArray[2880]` - one frame of 16-bit PCM samples
- **Output**: `ShortArray` (variable length) - compressed Opus data
- **Compression Ratio**: ~15-50x depending on audio content
- **Application Mode**: `Constants.Application.audio()` - general audio (not voice-optimized)

### 3. Serialization for Kafka
```kotlin
// ShortArray → ByteArray (Little Endian)
val byteArray = shortArrayToByteArray(encoded)  // 2 bytes per short
```

Each Kafka message contains one encoded Opus frame (~100-400 bytes).

**Special Cases**:
- **Silence frames**: When Opus DTX (Discontinuous Transmission) detects silence, a 2-byte marker `[0x00, 0x00]` is sent to maintain timing
- **Tiny frames** (<50 bytes): Skipped as likely startup noise

## Kafka Message Structure

### Audio Topic (`chok-audio-{channel}`)
```
Key:   "user1" (userId)
Value: [Opus-encoded frame bytes]
```
- One message per 60ms audio frame
- Messages are ordered by Kafka offset
- Typical recording: 17 messages/second

### Metadata Topic (`chok-metadata-{channel}`)
```json
{
  "userId": "dan",
  "channelId": 1,
  "startOffset": 25571,
  "endOffset": 25604,
  "timestamp": 1704825600000,
  "messageCount": 34,
  "reactions": {}
}
```
- Published once per recording session
- `startOffset`/`endOffset` reference audio topic offsets
- Duration estimated as `messageCount × 60ms`

## Playback Pipeline

### 1. Kafka Consumption
Messages are consumed from the audio topic, either:
- **Live**: Latest offset for real-time walkie-talkie mode
- **Timeline**: Specific offset range for playback of recorded messages

### 2. Opus Decoding
```
Compressed bytes → ByteArray → ShortArray → Opus Decode → PCM ShortArray[2880]
```

- **Input**: `ShortArray` - compressed Opus data
- **Output**: `ShortArray[2880]` - decompressed PCM samples
- **Error Handling**: If decode fails, a silence frame is inserted to maintain timing

### 3. Audio Output (`AudioTrack`)
- **Mode**: `MODE_STREAM` - continuous streaming playback
- **Buffer**: 4x minimum buffer size to prevent underruns
- **Attributes**: `USAGE_MEDIA`, `CONTENT_TYPE_SPEECH`

### 4. Serialized Processing
A single-threaded dispatcher ensures frames are processed in order:
```kotlin
coroutineScope.launch(audioDispatcher) {
    // Decode and write to AudioTrack sequentially
}
```

## Timing Considerations

| Metric | Value |
|--------|-------|
| Frame Duration | 60ms |
| Typical Latency | 100-300ms (network + buffering) |
| Waveform Update | Every 2 frames (~120ms) |


## Key Files

- `AudioService.kt` - Recording, encoding, decoding, playback
- `WaveformData.kt` - RMS amplitude visualization data
- `MainActivity.kt` - Kafka producer integration
- `TimelineActivity.kt` - Timeline playback from offsets
- `AudioMetadata.kt` - Recording session metadata
