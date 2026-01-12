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
Compressed bytes → ByteArray → Opus Decode → PCM ShortArray[2880]
```

- **Error Handling**: If decode fails, a silence frame is inserted to maintain timing

### 3. Audio Output (`AudioTrack`)
- **Mode**: `MODE_STREAM` - continuous streaming playback
- **Buffer**: 4x minimum buffer size to prevent underruns
- **Attributes**: `USAGE_MEDIA`, `CONTENT_TYPE_SPEECH`

## Timing Considerations

| Metric | Value |
|--------|-------|
| Frame Duration | 60ms |
| Typical Latency | 100-300ms (network + buffering) |
| Waveform Update | Every 2 frames (~120ms) |


## Throughput Estimation (Aiven Free Tier)

Aiven's free tier limits Kafka throughput to:
- **Data IN (produce)**: 250 KB/s
- **Data OUT (consume)**: 250 KB/s

These are independent limits, so producing and consuming don't compete for the same bandwidth.

### Per-User Bandwidth

| Activity | Calculation | Bandwidth |
|----------|-------------|-----------|
| Transmitting | 17 frames/s × ~250 bytes/frame | ~4.25 KB/s |
| Receiving (per sender) | Same as above | ~4.25 KB/s |

Using 250 bytes as the average Opus frame size (middle of 100-400 byte range).

### Practical Limits

The **bottleneck is typically consume (OUT)** since each listener multiplies outbound traffic:

- **Max simultaneous senders**: ~58 users (250 ÷ 4.25)
- **Max listeners per sender**: ~58 users (250 ÷ 4.25)
- **Walkie-talkie mode** (1 speaker at a time): Up to ~58 concurrent listeners
- **Group conversation** (5 simultaneous speakers, everyone listens): ~10-12 total participants
- **Broadcast mode** (1 sender, many listeners): ~58 listeners

### Note on Overhead

These calculations assume raw audio data only. Actual bandwidth includes:
- Kafka protocol overhead (~50-100 bytes/message)
- Metadata topic messages (minimal, ~1 message per recording session)
- TLS encryption overhead

In practice, expect 10-20% additional overhead, reducing effective capacity accordingly.

## Key Files

- `AudioService.kt` - Recording, encoding, decoding, playback
- `WaveformData.kt` - RMS amplitude visualization data
- `MainActivity.kt` - Kafka producer integration
- `TimelineActivity.kt` - Timeline playback from offsets
- `AudioMetadata.kt` - Recording session metadata
