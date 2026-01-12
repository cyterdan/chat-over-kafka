# Chat over Kafka

This project is a human-scale walkie-talkie app for Android, meant for a family or a small group of friends.

You only need a (free) Kafka cluster to run your own instance — no servers, no backend services — just Kafka.
(see [installation](#installation))


## Demo

```
[ Demo video placeholder ]
```

## What the app does

Chat over Kafka behaves like a traditional walkie‑talkie:
- Hold the button to broadcast, release to send.
- Anyone tuned to the same channel hears the message.
- You can replay past recordings from the timeline.
- You can add reactions to past messages


## What does it have to do with kafka ?

Kafka is the sole backend for the application. There are no other servers, relays, or real-time gateways.
The app relies on Kafka for:
- one-to-many broadcast
- message history
- message reactions via compacted topics

## Why build this?

This started as an exploratory project to see how far you could go using Kafka alone as an application backend.
The choice of Kafka was inspired by the Aiven competition to leverage their recent free kafka offering.

## How it works


The app uses a direct connection to Kafka:

- **Audio encoding**: Opus codec wrapped with JNI ([see audio processing docs](docs/AUDIO_PROCESSING.md))
- **Kafka client**: librdkafka wrapped with JNI ([see kafka on android](docs/KAFKA_ON_ANDROID.md))
- **Authentication**: Per-user mTLS certificates from Aiven
- **Topics**: Separate audio and metadata topics per channel ([see kafka topic layout](docs/KAFKA_TOPICS.md))

```
+----------------+          +----------------+
|   Device A     |          |   Device B     |
|  (Producer)    |          |  (Consumer)    |
+-------+--------+          +--------+-------+
        |                            ^
        | Opus-encoded audio         |
        v                            |
+-------+----------------------------+-------+
|              Kafka Cluster                 |
|    (mTLS authenticated, Aiven hosted)      |
+--------------------------------------------+
```

## Run your own channels and connect your Android devices



### Prerequisites

- An [Aiven](https://aiven.io) account (no credit card needed)
- Python 3 with `qrcode[pil]` module (`pip install qrcode[pil]`)
- Terraform
- jq, curl (for calling the Aiven API)

### Quick Start

1. 
   - Get your API token from https://console.aiven.io/profile/tokens
   - configure your token in `terraform.tfvars`
   - choose a cloud region that is geographically close (e.g. do-sfo, see regions [here](https://aiven.io/docs/platform/reference/list_of_clouds#digitalocean) ) and configure it in `terraform.tfvars`

2. **Initialize infrastructure and create a user**
   ```bash
   cd provisioning
   ./provision.sh init
   ./provision.sh add-user alice
   ```

3. **Install on your device**
   ```bash
   ./provision.sh serve
   ```
   Scan the QR code on your Android device to download the APK.

4. Android may show various warnings because we are installing the APK directly from a link. The APK is built locally so you can just look at the code and decide for yourself if you trust what it's doing.

### Provisioning Commands

| Command | Description |
|---------|-------------|
| `./provision.sh init` | Initialize Terraform infrastructure |
| `./provision.sh add-user <name>` | Create a new user with unique certificates and build APK |
| `./provision.sh get-bundle <name>` | Get QR code/download link for existing user |
| `./provision.sh list-users` | List all provisioned users |
| `./provision.sh serve` | Start HTTP server for APK downloads |

### Tooling note

Parts of this project were developed with the help of Anthropic’s Claude Code.

## License

MIT