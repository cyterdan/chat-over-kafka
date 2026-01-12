#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
# Chat Over Kafka - Provisioning Script
# ============================================================================
# This script manages infrastructure and per-user app bundles.
#
# Usage:
#   ./provision.sh init              - Initialize infrastructure (terraform)
#   ./provision.sh add-user <name>   - Create new user with unique certs
#   ./provision.sh get-bundle <name> - Get QR code/link for existing user
#   ./provision.sh list-users        - List all provisioned users
#   ./provision.sh serve             - Start local webserver for APK downloads
#   ./provision.sh help              - Show this help message
# ============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
USERS_DIR="$SCRIPT_DIR/users"
BUNDLES_DIR="$SCRIPT_DIR/bundles"
USERS_JSON="$SCRIPT_DIR/users.json"
SERVER_PORT="${CHOK_SERVER_PORT:-8080}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# ---------- Helpers ----------

log_info() {
    echo -e "${BLUE}▶${NC} $1"
}

log_success() {
    echo -e "${GREEN}✔${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}⚠${NC} $1"
}

log_error() {
    echo -e "${RED}✖${NC} $1" >&2
}

fail() {
    log_error "$1"
    exit 1
}

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"
}

check_dependencies() {
    log_info "Checking dependencies..."
    require_cmd terraform
    require_cmd python3
    require_cmd jq
    require_cmd curl

    # Check for qrcode module
    python3 -c "import qrcode" 2>/dev/null || fail "Python module 'qrcode' not installed (run: pip install qrcode[pil])"

    # Check for AIVEN_API_TOKEN
    [[ -z "${AIVEN_API_TOKEN:-}" ]] && fail "AIVEN_API_TOKEN environment variable is not set"

    log_success "All dependencies satisfied"
}

ensure_dirs() {
    mkdir -p "$USERS_DIR"
    mkdir -p "$BUNDLES_DIR"
    [[ -f "$USERS_JSON" ]] || echo '{"users":{}}' > "$USERS_JSON"
}

get_local_ip() {
    # Get the local IP address for QR code generation
    if command -v ip >/dev/null 2>&1; then
        ip route get 1 2>/dev/null | awk '{print $7; exit}' || echo "localhost"
    elif command -v ifconfig >/dev/null 2>&1; then
        ifconfig | grep 'inet ' | grep -v '127.0.0.1' | head -1 | awk '{print $2}' | sed 's/addr://'
    else
        echo "localhost"
    fi
}

# ---------- Aiven API Helpers ----------

AIVEN_PROJECT="${AIVEN_PROJECT:-chok-project-0-0-1}"
AIVEN_SERVICE="${AIVEN_SERVICE:-chok-free-kafka-service}"

aiven_api() {
    local method="$1"
    local endpoint="$2"
    local data="${3:-}"

    local url="https://api.aiven.io/v1${endpoint}"
    local args=(-s -X "$method" -H "Authorization: Bearer $AIVEN_API_TOKEN" -H "Content-Type: application/json")

    if [[ -n "$data" ]]; then
        args+=(-d "$data")
    fi

    curl "${args[@]}" "$url"
}

create_kafka_user() {
    local username="$1"
    log_info "Creating Kafka user: $username" >&2

    local response
    response=$(aiven_api POST "/project/$AIVEN_PROJECT/service/$AIVEN_SERVICE/user" \
        "{\"username\": \"$username\"}")

    # Check if response is valid JSON
    if ! echo "$response" | jq -e . >/dev/null 2>&1; then
        log_error "API returned invalid JSON response:"
        echo "$response" | head -c 500 >&2
        fail "API error - check your AIVEN_API_TOKEN, AIVEN_PROJECT, and AIVEN_SERVICE"
    fi

    if echo "$response" | jq -e '.user' >/dev/null 2>&1; then
        log_success "User created successfully" >&2
        echo "$response"  # Only JSON goes to stdout
    else
        local error
        error=$(echo "$response" | jq -r '.message // .error // "Unknown error"')
        fail "Failed to create user: $error"
    fi
}

get_kafka_user() {
    local username="$1"
    local response
    response=$(aiven_api GET "/project/$AIVEN_PROJECT/service/$AIVEN_SERVICE/user/$username")

    if ! echo "$response" | jq -e . >/dev/null 2>&1; then
        log_error "Failed to get user - invalid API response:"
        echo "$response" | head -c 500 >&2
        return 1
    fi
    echo "$response"  # Only JSON goes to stdout
}

get_project_ca() {
    local response
    response=$(aiven_api GET "/project/$AIVEN_PROJECT/kms/ca")

    if ! echo "$response" | jq -e '.certificate' >/dev/null 2>&1; then
        log_error "Failed to get CA certificate - invalid API response:"
        echo "$response" | head -c 500 >&2
        fail "Could not retrieve CA certificate"
    fi
    echo "$response" | jq -r '.certificate'  # Only certificate goes to stdout
}

list_kafka_users() {
    aiven_api GET "/project/$AIVEN_PROJECT/service/$AIVEN_SERVICE/user" | jq -r '.users[].username'
}

get_broker_url() {
    local response
    response=$(aiven_api GET "/project/$AIVEN_PROJECT/service/$AIVEN_SERVICE")

    if ! echo "$response" | jq -e '.service' >/dev/null 2>&1; then
        log_error "Failed to get service info - invalid API response:" >&2
        echo "$response" | head -c 500 >&2
        fail "Could not retrieve Kafka service info"
    fi

    # Extract the service URI (broker URL)
    local broker_url
    broker_url=$(echo "$response" | jq -r '.service.service_uri // empty')

    if [[ -z "$broker_url" ]]; then
        # Fallback: construct from connection info
        local host port
        host=$(echo "$response" | jq -r '.service.connection_info.kafka[0] // .service.components[] | select(.component == "kafka") | .host' 2>/dev/null | head -1)
        port=$(echo "$response" | jq -r '.service.connection_info.kafka_port // .service.components[] | select(.component == "kafka") | .port' 2>/dev/null | head -1)
        if [[ -n "$host" ]] && [[ -n "$port" ]]; then
            broker_url="${host}:${port}"
        fi
    fi

    if [[ -z "$broker_url" ]]; then
        fail "Could not determine broker URL from service info"
    fi

    echo "$broker_url"
}

# ---------- User Management ----------

add_user() {
    local username="$1"
    [[ -z "$username" ]] && fail "Username is required"

    # Validate username (alphanumeric and hyphens only)
    if [[ ! "$username" =~ ^[a-zA-Z0-9_-]+$ ]]; then
        fail "Username must contain only letters, numbers, underscores, and hyphens"
    fi

    local user_dir="$USERS_DIR/$username"

    # Check if user already exists locally
    if [[ -d "$user_dir" ]]; then
        log_warn "User '$username' already exists locally"
        read -p "Regenerate certificates and rebuild? [y/N] " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            log_info "Use 'get-bundle $username' to get the existing bundle"
            return 0
        fi
    fi

    mkdir -p "$user_dir/certs"

    log_info "Creating Kafka user and certificates for: $username"

    # Create user in Aiven (will get new certs)
    local user_response
    user_response=$(create_kafka_user "chok-$username") || {
        # User might already exist, try to get existing
        log_warn "User may already exist, fetching existing credentials..."
        user_response=$(get_kafka_user "chok-$username")
    }

    # Extract certificates
    local cert key
    cert=$(echo "$user_response" | jq -r '.user.access_cert // empty')
    key=$(echo "$user_response" | jq -r '.user.access_key // empty')

    if [[ -z "$cert" ]] || [[ -z "$key" ]]; then
        fail "Could not extract certificates from response"
    fi

    # Get CA certificate
    local ca
    ca=$(get_project_ca)

    # Save certificates
    echo "$ca" > "$user_dir/certs/ca.pem"
    echo "$cert" > "$user_dir/certs/client.pem"
    echo "$key" > "$user_dir/certs/client.key"

    log_success "Certificates saved to $user_dir/certs/"

    # Update users.json
    local timestamp
    timestamp=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
    jq --arg user "$username" --arg ts "$timestamp" \
        '.users[$user] = {"created": $ts, "kafka_user": ("chok-" + $user)}' \
        "$USERS_JSON" > "${USERS_JSON}.tmp" && mv "${USERS_JSON}.tmp" "$USERS_JSON"

    # Build the APK for this user
    build_user_apk "$username"

    # Generate QR code
    generate_bundle_qr "$username"

    log_success "User '$username' provisioned successfully!"
    echo ""
    get_bundle "$username"
}

build_user_apk() {
    local username="$1"
    local user_dir="$USERS_DIR/$username"

    log_info "Building APK for user: $username"

    # Copy user's certificates to app assets
    local assets_dir="$PROJECT_ROOT/app/src/main/assets"
    mkdir -p "$assets_dir"

    cp "$user_dir/certs/ca.pem" "$assets_dir/"
    cp "$user_dir/certs/client.pem" "$assets_dir/"
    cp "$user_dir/certs/client.key" "$assets_dir/"

    log_info "Certificates copied to app assets"

    # Get the broker URL from Aiven service info
    local broker_url
    broker_url=$(get_broker_url)
    log_info "Broker URL: $broker_url"

    # Generate kafka_config.json
    cat > "$assets_dir/kafka_config.json" <<CONFIGEOF
{
    "brokerUrl": "$broker_url",
    "channels": [
        {
            "channelNumber": 1,
            "channelName": "Main",
            "audioTopic": "chok-audio-1",
            "audioPartition": 0,
            "metadataTopic": "chok-metadata-1",
            "metadataPartition": 0
        },
        {
            "channelNumber": 2,
            "channelName": "Secondary",
            "audioTopic": "chok-audio-2",
            "audioPartition": 0,
            "metadataTopic": "chok-metadata-2",
            "metadataPartition": 0
        }
    ],
    "certificates": {
        "caAssetName": "ca.pem",
        "clientKeyAssetName": "client.key",
        "clientCertAssetName": "client.pem"
    }
}
CONFIGEOF

    log_info "Generated kafka_config.json"

    # Build the APK with the user ID baked in
    cd "$PROJECT_ROOT"

    log_info "Running Gradle build with CHOK_USER_ID=$username..."

    # Set JAVA_HOME for Android Studio's bundled JDK if available
    local java_home="${JAVA_HOME:-}"
    if [[ -z "$java_home" ]] && [[ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]]; then
        java_home="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    fi

    if [[ -n "$java_home" ]]; then
        JAVA_HOME="$java_home" ./gradlew assembleDebug -PCHOK_USER_ID="$username" --quiet || {
            fail "Gradle build failed"
        }
    else
        ./gradlew assembleDebug -PCHOK_USER_ID="$username" --quiet || {
            fail "Gradle build failed"
        }
    fi

    # Copy APK to bundles directory
    local apk_source="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
    local apk_dest="$BUNDLES_DIR/${username}.apk"

    if [[ -f "$apk_source" ]]; then
        cp "$apk_source" "$apk_dest"
        log_success "APK built: $apk_dest"
    else
        fail "APK not found at $apk_source"
    fi

    cd "$SCRIPT_DIR"
}

generate_bundle_qr() {
    local username="$1"
    local apk_path="$BUNDLES_DIR/${username}.apk"
    local qr_path="$BUNDLES_DIR/${username}_qr.png"

    [[ -f "$apk_path" ]] || fail "APK not found for user: $username"

    local local_ip
    local_ip=$(get_local_ip)
    local download_url="http://${local_ip}:${SERVER_PORT}/${username}.apk"

    log_info "Generating QR code for: $download_url"

    python3 - "$download_url" "$qr_path" <<'PYEOF'
import sys
import qrcode
from qrcode.constants import ERROR_CORRECT_M

url = sys.argv[1]
output = sys.argv[2]

qr = qrcode.QRCode(
    version=1,
    error_correction=ERROR_CORRECT_M,
    box_size=10,
    border=4,
)
qr.add_data(url)
qr.make(fit=True)

img = qr.make_image(fill_color="black", back_color="white")
img.save(output)
PYEOF

    log_success "QR code saved: $qr_path"
}

get_bundle() {
    local username="$1"
    [[ -z "$username" ]] && fail "Username is required"

    local apk_path="$BUNDLES_DIR/${username}.apk"
    local qr_path="$BUNDLES_DIR/${username}_qr.png"

    if [[ ! -f "$apk_path" ]]; then
        fail "No bundle found for user: $username. Run 'add-user $username' first."
    fi

    local local_ip
    local_ip=$(get_local_ip)
    local download_url="http://${local_ip}:${SERVER_PORT}/${username}.apk"

    echo ""
    echo -e "${CYAN}╔══════════════════════════════════════════════════════════╗${NC}"
    echo -e "${CYAN}║${NC}  ${GREEN}Bundle Ready for: $username${NC}"
    echo -e "${CYAN}╠══════════════════════════════════════════════════════════╣${NC}"
    echo -e "${CYAN}║${NC}  APK: $apk_path"
    echo -e "${CYAN}║${NC}  QR:  $qr_path"
    echo -e "${CYAN}║${NC}"
    echo -e "${CYAN}║${NC}  ${YELLOW}Download URL:${NC} $download_url"
    echo -e "${CYAN}║${NC}"
    echo -e "${CYAN}║${NC}  ${YELLOW}To download:${NC}"
    echo -e "${CYAN}║${NC}    1. Run: ${GREEN}./provision.sh serve${NC}"
    echo -e "${CYAN}║${NC}    2. Scan QR code or visit URL on your device"
    echo -e "${CYAN}╚══════════════════════════════════════════════════════════╝${NC}"
    echo ""

    # Show QR code in terminal
    python3 - "$download_url" <<'PYEOF'
import sys
import qrcode
url = sys.argv[1]
print("\nScan this QR code to download:\n")
qr = qrcode.QRCode(box_size=1, border=2)
qr.add_data(url)
qr.make(fit=True)
qr.print_ascii(invert=True)
PYEOF
}

list_users() {
    echo ""
    echo -e "${CYAN}Provisioned Users:${NC}"
    echo -e "${CYAN}──────────────────${NC}"

    if [[ ! -f "$USERS_JSON" ]] || [[ $(jq '.users | length' "$USERS_JSON") -eq 0 ]]; then
        echo "  (none)"
    else
        jq -r '.users | to_entries[] | "  \(.key) - created: \(.value.created)"' "$USERS_JSON"
    fi

    echo ""
    echo -e "${CYAN}Bundles available:${NC}"
    echo -e "${CYAN}──────────────────${NC}"

    local found=false
    shopt -s nullglob
    for apk in "$BUNDLES_DIR"/*.apk; do
        local name size
        name=$(basename "$apk" .apk)
        size=$(du -h "$apk" | cut -f1)
        echo "  $name ($size)"
        found=true
    done
    shopt -u nullglob
    [[ "$found" == "false" ]] && echo "  (none)"
    echo ""
}

serve_bundles() {
    local local_ip
    local_ip=$(get_local_ip)

    echo ""
    echo -e "${GREEN}╔══════════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║${NC}  ${CYAN}Chat Over Kafka - Bundle Server${NC}"
    echo -e "${GREEN}╠══════════════════════════════════════════════════════════╣${NC}"
    echo -e "${GREEN}║${NC}  Serving bundles from: $BUNDLES_DIR"
    echo -e "${GREEN}║${NC}  Server URL: ${YELLOW}http://${local_ip}:${SERVER_PORT}/${NC}"
    echo -e "${GREEN}║${NC}"
    echo -e "${GREEN}║${NC}  Available bundles:"

    shopt -s nullglob
    for apk in "$BUNDLES_DIR"/*.apk; do
        local name
        name=$(basename "$apk")
        echo -e "${GREEN}║${NC}    - http://${local_ip}:${SERVER_PORT}/${name}"
    done
    shopt -u nullglob

    echo -e "${GREEN}║${NC}"
    echo -e "${GREEN}║${NC}  Press Ctrl+C to stop"
    echo -e "${GREEN}╚══════════════════════════════════════════════════════════╝${NC}"
    echo ""

    cd "$BUNDLES_DIR"
    python3 -m http.server "$SERVER_PORT"
}

init_infrastructure() {
    check_dependencies

    log_info "Initializing Terraform..."
    cd "$SCRIPT_DIR"

    terraform init -upgrade

    log_info "Applying infrastructure..."
    terraform apply

    log_success "Infrastructure initialized!"
    echo ""
    echo "Next steps:"
    echo "  1. Add a user:    ./provision.sh add-user <username>"
    echo "  2. Start server:  ./provision.sh serve"
    echo "  3. Scan QR code on your Android device"
    echo ""
}

show_help() {
    echo ""
    echo -e "${CYAN}Chat Over Kafka - Provisioning Tool${NC}"
    echo ""
    echo "Usage: ./provision.sh <command> [args]"
    echo ""
    echo "Commands:"
    echo "  init              Initialize infrastructure with Terraform"
    echo "  add-user <name>   Create new user with unique certs and build APK"
    echo "  get-bundle <name> Show QR code and download link for existing user"
    echo "  list-users        List all provisioned users"
    echo "  serve             Start local HTTP server for APK downloads"
    echo "  help              Show this help message"
    echo ""
    echo "Environment variables:"
    echo "  AIVEN_API_TOKEN   (required) Aiven API token"
    echo "  AIVEN_PROJECT     Project name (default: chat-over-kafka)"
    echo "  AIVEN_SERVICE     Kafka service name (default: chok-free-kafka-service)"
    echo "  CHOK_SERVER_PORT  HTTP server port (default: 8080)"
    echo ""
    echo "Examples:"
    echo "  ./provision.sh init"
    echo "  ./provision.sh add-user alice"
    echo "  ./provision.sh add-user bob"
    echo "  ./provision.sh serve"
    echo ""
}

# ---------- Main ----------

main() {
    ensure_dirs

    local command="${1:-help}"

    case "$command" in
        init)
            init_infrastructure
            ;;
        add-user)
            check_dependencies
            add_user "${2:-}"
            ;;
        get-bundle)
            get_bundle "${2:-}"
            ;;
        list-users|list)
            list_users
            ;;
        serve)
            serve_bundles
            ;;
        help|--help|-h)
            show_help
            ;;
        *)
            log_error "Unknown command: $command"
            show_help
            exit 1
            ;;
    esac
}

main "$@"
