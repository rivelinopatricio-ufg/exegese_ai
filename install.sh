#!/usr/bin/env bash
# ==============================================================================
# Exegese AI - Production Orchestration and Installation Script
# Permissions: chmod +x install.sh
# Usage: ./install.sh [OPTIONS]
# ==============================================================================

set -euo pipefail

# ANSI Terminal Color Palette
readonly CLR_RESET="\033[0m"
readonly CLR_BOLD="\033[1m"
readonly CLR_GREEN="\033[32m"
readonly CLR_BLUE="\033[34m"
readonly CLR_YELLOW="\033[33m"
readonly CLR_RED="\033[31m"
readonly CLR_CYAN="\033[36m"

# Default Configuration Constants
readonly DEFAULT_HOST="exegese-ai.sytes.net"
readonly DEFAULT_HTTP_PORT=80
readonly DEFAULT_HTTPS_PORT=443
readonly DEFAULT_ADMIN_EMAIL="admin@exegese.ai"

# Execution Flags and Configuration Variables
FLAG_NO_INGEST=false
FLAG_UNINSTALL=false
HOST_NAME=""
HTTP_PORT=""
HTTPS_PORT=""
EMAIL_SSL=""
GOOGLE_CLIENT_ID=""
GOOGLE_CLIENT_SECRET=""
INITIAL_ADMIN_EMAIL=""
GEMINI_API_KEY=""
OPENAI_API_KEY=""
ANTHROPIC_API_KEY=""
CEREBRAS_API_KEY=""

log_info() {
    echo -e "${CLR_BLUE}[INFO]${CLR_RESET} $1"
}

log_success() {
    echo -e "${CLR_GREEN}${CLR_BOLD}[SUCCESS]${CLR_RESET} $1"
}

log_warn() {
    echo -e "${CLR_YELLOW}[WARN]${CLR_RESET} $1"
}

log_error() {
    echo -e "${CLR_RED}${CLR_BOLD}[ERROR]${CLR_RESET} $1" >&2
}

print_banner() {
    echo -e "${CLR_CYAN}${CLR_BOLD}"
    cat << "EOF"
==============================================================================
   ____                                   _     ___ 
  / __/_ _____ ___ ____ ___ ___   ___ _  (_)  / _ \
 / _/ \ \ / -_) _ `/ -_|_-</ -_) / _ `/ _/ /  / // /
/___//_\_\\__/\_, /\__/__/\__/  \_,_/ (_)___//____/ 
             /___/                                  
 Exegese AI - Normative Grounding & Rigorous RAG Platform
==============================================================================
EOF
    echo -e "${CLR_RESET}"
}

print_help() {
    echo -e "${CLR_BOLD}Usage:${CLR_RESET} ./install.sh [OPTIONS]"
    echo ""
    echo -e "${CLR_BOLD}Available Options:${CLR_RESET}"
    echo "  -h, --help                     Display this help message and exit."
    echo "  -H, --host <hostname>          Host name or FQDN (default: exegese-ai.sytes.net)."
    echo "  --http-port <port>             Public HTTP port exposed by NGINX (default: 80)."
    echo "  --https-port <port>            Public HTTPS port exposed by NGINX (default: 443)."
    echo "  --email-ssl <email>            Contact email for Let's Encrypt certificate issuance."
    echo "  --google-client-id <id>        Google OAuth2 Client ID"
    echo "                                 (spring.security.oauth2.client.registration.google.client-id)."
    echo "  --google-client-secret <sec>   Google OAuth2 Client Secret"
    echo "                                 (spring.security.oauth2.client.registration.google.client-secret)."
    echo "  --initial-admin <email>        Initial administrator email (default: admin@exegese.ai)."
    echo "  --gemini-key <key>             Google Gemini API Key."
    echo "  --openai-key <key>             OpenAI API Key."
    echo "  --anthropic-key <key>          Anthropic Claude API Key."
    echo "  --cerebras-key <key>           Cerebras Inference API Key (cerebras.api-key)."
    echo "  --no-ingest                    Start platform without triggering initial document ingestion."
    echo "  --uninstall                    Stop containers, remove persistent volumes, and delete .env."
    echo ""
    echo -e "${CLR_BOLD}Examples:${CLR_RESET}"
    echo "  ./install.sh"
    echo "  ./install.sh -H exegese-ai.sytes.net --http-port 80 --https-port 443 --email-ssl admin@exegese-ai.sytes.net"
    echo "  ./install.sh --google-client-id xxxx.apps.googleusercontent.com --google-client-secret GOCSPX-yyyy"
    echo "  ./install.sh --http-port 8080 --https-port 8443"
    echo "  ./install.sh --no-ingest"
    echo "  ./install.sh --uninstall"
}

parse_arguments() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            -h|--help)
                print_banner
                print_help
                exit 0
                ;;
            --no-ingest)
                FLAG_NO_INGEST=true
                shift
                ;;
            --uninstall)
                FLAG_UNINSTALL=true
                shift
                ;;
            -H|--host)
                HOST_NAME="$2"
                shift 2
                ;;
            --http-port)
                HTTP_PORT="$2"
                shift 2
                ;;
            --https-port)
                HTTPS_PORT="$2"
                shift 2
                ;;
            --email-ssl)
                EMAIL_SSL="$2"
                shift 2
                ;;
            --google-client-id)
                GOOGLE_CLIENT_ID="$2"
                shift 2
                ;;
            --google-client-secret)
                GOOGLE_CLIENT_SECRET="$2"
                shift 2
                ;;
            --initial-admin)
                INITIAL_ADMIN_EMAIL="$2"
                shift 2
                ;;
            --gemini-key)
                GEMINI_API_KEY="$2"
                shift 2
                ;;
            --openai-key)
                OPENAI_API_KEY="$2"
                shift 2
                ;;
            --anthropic-key)
                ANTHROPIC_API_KEY="$2"
                shift 2
                ;;
            --cerebras-key)
                CEREBRAS_API_KEY="$2"
                shift 2
                ;;
            *)
                log_error "Unknown option: $1"
                print_help
                exit 1
                ;;
        esac
    done
}

uninstall_environment() {
    print_banner
    log_warn "Warning: Uninstallation will remove all containers and persistent volumes of Exegese AI."
    read -rp "Do you want to proceed with uninstallation? (y/N): " confirm
    if [[ "$confirm" =~ ^[yY]$ ]]; then
        log_info "Stopping and removing Docker containers and volumes..."
        docker compose down -v --remove-orphans || true
        if [[ -f .env ]]; then
            log_warn "Removing .env configuration file..."
            rm -f .env
        fi
        log_success "Uninstallation completed successfully."
    else
        log_info "Uninstallation canceled by user."
    fi
    exit 0
}

check_prerequisites() {
    log_info "Checking operating system dependencies..."
    
    local missing=0

    if ! command -v docker &> /dev/null; then
        log_error "Docker not found. Install Docker before continuing: https://docs.docker.com/engine/install/"
        missing=$((missing + 1))
    fi

    if ! docker compose version &> /dev/null; then
        log_error "Docker Compose plugin not found or outdated."
        missing=$((missing + 1))
    fi

    if ! command -v curl &> /dev/null; then
        log_error "'curl' utility not found. Please install the curl package."
        missing=$((missing + 1))
    fi

    if [[ $missing -gt 0 ]]; then
        log_error "Missing prerequisites. Aborting installation."
        exit 1
    fi

    log_success "All prerequisites (Docker, Docker Compose, curl) are available."
}

generate_random_secret() {
    if command -v openssl &> /dev/null; then
        openssl rand -hex 16
    else
        cat /dev/urandom | tr -dc 'a-zA-Z0-9' | fold -w 32 | head -n 1
    fi
}

is_port_in_use() {
    local port="$1"
    if command -v ss &>/dev/null; then
        ss -tuln 2>/dev/null | grep -q ":${port} " && return 0
    elif command -v netstat &>/dev/null; then
        netstat -tuln 2>/dev/null | grep -q ":${port} " && return 0
    elif command -v lsof &>/dev/null; then
        lsof -i ":${port}" &>/dev/null && return 0
    fi
    return 1
}

configure_environment() {
    if [[ -f .env ]]; then
        log_info "Existing '.env' file detected."
        read -rp "Do you want to reconfigure environment variables? (y/N): " reconf
        if [[ ! "$reconf" =~ ^[yY]$ ]]; then
            log_info "Using existing configuration from .env."
            if [[ -z "$HTTPS_PORT" ]]; then
                HTTPS_PORT=$(grep -E '^HTTPS_PORT=' .env | cut -d '=' -f2- || echo "443")
            fi
            return 0
        fi
    fi

    log_info "Starting environment configuration wizard (.env)..."

    # 1. Network, Host and Exposed Ports Configuration (NGINX / Let's Encrypt)
    echo ""
    echo -e "${CLR_BOLD}1. Network, Host, and Exposed Ports Configuration (NGINX Reverse Proxy):${CLR_RESET}"
    if [[ -z "$HOST_NAME" ]]; then
        read -rp "Server Hostname or FQDN [${DEFAULT_HOST}]: " input_host
        HOST_NAME=${input_host:-$DEFAULT_HOST}
    else
        log_info "Host configured via CLI parameter: ${HOST_NAME}"
    fi

    if [[ -z "$HTTP_PORT" ]]; then
        local default_http=$DEFAULT_HTTP_PORT
        if is_port_in_use 80; then
            log_warn "Default port 80 is currently in use on the host. Suggesting alternative port 8080."
            default_http=8080
        fi
        read -rp "Public HTTP port for NGINX [${default_http}]: " input_http
        HTTP_PORT=${input_http:-$default_http}
    else
        log_info "HTTP port configured via CLI parameter: ${HTTP_PORT}"
    fi

    if [[ -z "$HTTPS_PORT" ]]; then
        local default_https=$DEFAULT_HTTPS_PORT
        if is_port_in_use 443; then
            log_warn "Default port 443 is currently in use on the host. Suggesting alternative port 8443."
            default_https=8443
        fi
        read -rp "Public HTTPS port for NGINX [${default_https}]: " input_https
        HTTPS_PORT=${input_https:-$default_https}
    else
        log_info "HTTPS port configured via CLI parameter: ${HTTPS_PORT}"
    fi

    if [[ "$HOST_NAME" != "localhost" && "$HOST_NAME" != "127.0.0.1" ]]; then
        if [[ -z "$EMAIL_SSL" ]]; then
            read -rp "Contact email for Let's Encrypt certificate issuance [admin@${HOST_NAME}]: " input_email_ssl
            EMAIL_SSL=${input_email_ssl:-admin@${HOST_NAME}}
        fi
    else
        EMAIL_SSL="admin@localhost"
    fi

    # 2. Google OAuth2 / OIDC Authentication Configuration
    echo ""
    echo -e "${CLR_BOLD}2. Google OAuth2 / OIDC Authentication:${CLR_RESET}"
    echo -e "   To enable institutional login and federated security, configure the following keys:"
    echo -e "   - ${CLR_CYAN}spring.security.oauth2.client.registration.google.client-id${CLR_RESET}"
    echo -e "   - ${CLR_CYAN}spring.security.oauth2.client.registration.google.client-secret${CLR_RESET}"
    echo ""

    if [[ -z "$GOOGLE_CLIENT_ID" ]]; then
        read -rp "Google Client ID (spring.security.oauth2.client.registration.google.client-id): " GOOGLE_CLIENT_ID
    else
        log_info "Google Client ID configured via CLI parameter."
    fi

    if [[ -z "$GOOGLE_CLIENT_SECRET" ]]; then
        read -rsp "Google Client Secret (spring.security.oauth2.client.registration.google.client-secret): " GOOGLE_CLIENT_SECRET
        echo ""
    else
        log_info "Google Client Secret configured via CLI parameter."
    fi

    if [[ -z "$INITIAL_ADMIN_EMAIL" ]]; then
        read -rp "Initial Administrator Email (receives ROLE_ADMIN on first login) [${DEFAULT_ADMIN_EMAIL}]: " input_admin
        INITIAL_ADMIN_EMAIL=${input_admin:-$DEFAULT_ADMIN_EMAIL}
    fi

    # Calculate Redirect URI for Google Cloud Console
    local proto="https"
    local port_part=""
    if [[ "$HOST_NAME" == "localhost" || "$HOST_NAME" == "127.0.0.1" ]]; then
        if [[ "$HTTPS_PORT" == "443" || -z "$HTTPS_PORT" ]]; then
            proto="http"
            if [[ "$HTTP_PORT" != "80" && -n "$HTTP_PORT" ]]; then
                port_part=":${HTTP_PORT}"
            fi
        else
            port_part=":${HTTPS_PORT}"
        fi
    else
        if [[ "$HTTPS_PORT" != "443" && -n "$HTTPS_PORT" ]]; then
            port_part=":${HTTPS_PORT}"
        fi
    fi

    local redirect_uri="${proto}://${HOST_NAME}${port_part}/login/oauth2/code/google"

    echo ""
    echo -e "${CLR_YELLOW}${CLR_BOLD}[OAUTH2 CONFIGURATION IN GOOGLE CLOUD CONSOLE]${CLR_RESET}"
    echo -e "In Google Cloud Console (APIs & Services > Credentials > OAuth 2.0 Client IDs):"
    echo -e "  Authorized JavaScript origins: ${CLR_CYAN}${proto}://${HOST_NAME}${port_part}${CLR_RESET}"
    echo -e "  Authorized redirect URIs:      ${CLR_CYAN}${CLR_BOLD}${redirect_uri}${CLR_RESET}"
    echo ""

    # 3. Artificial Intelligence Providers
    echo -e "${CLR_BOLD}3. Artificial Intelligence Providers (Optional - provide at least one or configure later in Admin UI):${CLR_RESET}"
    if [[ -z "$GEMINI_API_KEY" ]]; then
        read -rsp "Google Gemini API Key (Press Enter to skip): " GEMINI_API_KEY
        echo ""
    fi
    if [[ -z "$OPENAI_API_KEY" ]]; then
        read -rsp "OpenAI API Key (Press Enter to skip): " OPENAI_API_KEY
        echo ""
    fi
    if [[ -z "$ANTHROPIC_API_KEY" ]]; then
        read -rsp "Anthropic Claude API Key (Press Enter to skip): " ANTHROPIC_API_KEY
        echo ""
    fi
    if [[ -z "$CEREBRAS_API_KEY" ]]; then
        read -rsp "Cerebras Inference API Key (cerebras.api-key - Press Enter to skip): " CEREBRAS_API_KEY
        echo ""
    fi

    local AES_SECRET
    AES_SECRET=$(generate_random_secret)

    cat > .env << EOF
# ==============================================================================
# Environment Configuration - Exegese AI
# Automatically generated by install.sh on $(date -u +"%Y-%m-%dT%H:%M:%SZ")
# ==============================================================================

# PostgreSQL 17 + pgvector Database
POSTGRES_DB=exegese_db
POSTGRES_USER=exegese_user
POSTGRES_PASSWORD=exegese_password
POSTGRES_PORT=5432

# Web Server & NGINX Reverse Proxy (SWAG)
SERVER_NAME=${HOST_NAME}
URL=${HOST_NAME}
HTTP_PORT=${HTTP_PORT}
HTTPS_PORT=${HTTPS_PORT}
LETSENCRYPT_EMAIL=${EMAIL_SSL}
PORT=8080

# Master AES-256 Cryptographic Key (Randomly generated)
EXEGESE_AES_SECRET=${AES_SECRET}

# Initial Administrator
INITIAL_ADMIN_EMAIL=${INITIAL_ADMIN_EMAIL}

# ==============================================================================
# Google OAuth2 / OIDC Authentication
# spring.security.oauth2.client.registration.google.client-id
# spring.security.oauth2.client.registration.google.client-secret
# ==============================================================================
GOOGLE_CLIENT_ID=${GOOGLE_CLIENT_ID}
GOOGLE_CLIENT_SECRET=${GOOGLE_CLIENT_SECRET}
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID=${GOOGLE_CLIENT_ID}
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET=${GOOGLE_CLIENT_SECRET}

# LLM Providers
GEMINI_API_KEY=${GEMINI_API_KEY}
OPENAI_API_KEY=${OPENAI_API_KEY}
ANTHROPIC_API_KEY=${ANTHROPIC_API_KEY}
CEREBRAS_API_KEY=${CEREBRAS_API_KEY}
NVIDIA_API_KEY=
DEEPSEEK_API_KEY=
OLLAMA_BASE_URL=http://localhost:11434
EOF

    chmod 600 .env
    log_success "File '.env' written with restricted permissions (600)."
}

configure_nginx_proxy() {
    local target_https_port="${HTTPS_PORT:-443}"
    log_info "Configuring NGINX site definition with public HTTPS port (${target_https_port})..."

    local https_redirect_target="https://\$host\$request_uri"
    local forwarded_port="443"

    if [[ "$target_https_port" != "443" && -n "$target_https_port" ]]; then
        https_redirect_target="https://\$host:${target_https_port}\$request_uri"
        forwarded_port="${target_https_port}"
    fi

    mkdir -p docker/proxy/config
    cat > docker/proxy/config/default << EOF
# ==============================================================================
# Exegese AI - NGINX Site Configuration (Generated dynamically by install.sh)
# ==============================================================================

# HTTP Block: Automatic redirect to HTTPS
server {
    listen 80 default_server;
    listen [::]:80 default_server;

    server_name _;

    return 301 ${https_redirect_target};
}

# HTTPS Block: SWAG-managed TLS termination and Spring Boot upstream
server {
    listen 443 ssl http2 default_server;
    listen [::]:443 ssl http2 default_server;

    server_name _;

    # Standardized and secure TLS directives managed by SWAG (ciphers, protocols, certs)
    include /config/nginx/ssl.conf;

    client_max_body_size 10M;

    # 1. SSE Streaming Route for RAG (Mandatory buffering deactivation)
    location /api/chat/stream {
        proxy_pass http://app:8080;
        proxy_http_version 1.1;
        proxy_set_header Connection "";

        proxy_buffering off;
        proxy_cache off;
        chunked_transfer_encoding off;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_set_header X-Accel-Buffering no;

        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header X-Forwarded-Port ${forwarded_port};
    }

    # 2. Manuals and PDF Documents Upload Route (Expanded 50MB limit)
    location /admin/documents/upload {
        client_max_body_size 50M;
        proxy_pass http://app:8080;

        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header X-Forwarded-Port ${forwarded_port};
    }

    # 3. General Application Route (Spring Boot Web UI and APIs)
    location / {
        proxy_pass http://app:8080;

        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header X-Forwarded-Port ${forwarded_port};
    }
}
EOF
    log_success "NGINX configuration dynamically generated for HTTPS port ${target_https_port}."
}

start_services() {
    configure_nginx_proxy
    log_info "Building and starting containers via Docker Compose..."
    docker compose up -d --build
    log_success "Containers instantiated successfully."
}

wait_for_health() {
    log_info "Waiting for application and database startup..."
    
    local retries=30
    local count=0
    local health_url="http://localhost:${HTTP_PORT:-80}/actuator/health"
    if [[ "${HOST_NAME:-$DEFAULT_HOST}" != "localhost" && "${HOST_NAME:-$DEFAULT_HOST}" != "127.0.0.1" ]]; then
        health_url="https://localhost:${HTTPS_PORT:-443}/actuator/health"
    fi

    while [[ $count -lt $retries ]]; do
        if curl -s -k -L -f "$health_url" 2>/dev/null | grep -q '"status":"UP"' || \
           curl -s -k -f "https://localhost:${HTTPS_PORT:-443}/actuator/health" 2>/dev/null | grep -q '"status":"UP"' || \
           docker compose exec -T app curl -s -f http://localhost:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
            echo ""
            log_success "Application Exegese AI is ONLINE with UP status!"
            return 0
        fi
        count=$((count + 1))
        echo -n "."
        sleep 3
    done

    echo ""
    log_error "Timeout exceeded waiting for application startup."
    log_info "Check detailed logs with: docker compose logs app"
    exit 1
}

bootstrap_ingestion() {
    if [[ "$FLAG_NO_INGEST" == true ]]; then
        log_info "Flag '--no-ingest' enabled. Initial document ingestion skipped."
        return 0
    fi

    log_info "Initial document collection ready. You can manage documents via the administrative panel."
}

print_summary() {
    local proto="https"
    local port_part=""
    if [[ "$HOST_NAME" == "localhost" || "$HOST_NAME" == "127.0.0.1" ]]; then
        if [[ "$HTTPS_PORT" == "443" || -z "$HTTPS_PORT" ]]; then
            proto="http"
            if [[ "$HTTP_PORT" != "80" && -n "$HTTP_PORT" ]]; then
                port_part=":${HTTP_PORT}"
            fi
        else
            port_part=":${HTTPS_PORT}"
        fi
    else
        if [[ "$HTTPS_PORT" != "443" && -n "$HTTPS_PORT" ]]; then
            port_part=":${HTTPS_PORT}"
        fi
    fi
    local base_url="${proto}://${HOST_NAME}${port_part}"
    local redirect_uri="${base_url}/login/oauth2/code/google"

    echo ""
    echo -e "${CLR_GREEN}${CLR_BOLD}==============================================================================${CLR_RESET}"
    echo -e "${CLR_GREEN}${CLR_BOLD}   EXEGESE AI — INSTALLATION AND STARTUP COMPLETED SUCCESSFULLY!             ${CLR_RESET}"
    echo -e "${CLR_GREEN}${CLR_BOLD}==============================================================================${CLR_RESET}"
    echo ""
    echo -e "${CLR_BOLD}Access Dashboard:${CLR_RESET}"
    echo -e "  Main URL:                    ${CLR_CYAN}${base_url}${CLR_RESET}"
    echo -e "  Google OAuth2 Login:         ${CLR_CYAN}${base_url}/login${CLR_RESET}"
    echo -e "  Registered Redirect URI:     ${CLR_CYAN}${redirect_uri}${CLR_RESET}"
    echo -e "  Administrative Management:   ${CLR_CYAN}${base_url}/admin/users${CLR_RESET}"
    echo -e "  AI Models Catalog:           ${CLR_CYAN}${base_url}/admin/models${CLR_RESET}"
    echo -e "  Actuator Healthcheck:        ${CLR_CYAN}${base_url}/actuator/health${CLR_RESET}"
    echo ""
    echo -e "${CLR_BOLD}Configured Google OAuth2 Credentials:${CLR_RESET}"
    echo -e "  spring.security.oauth2.client.registration.google.client-id:     ${CLR_CYAN}${GOOGLE_CLIENT_ID:-(not provided)}${CLR_RESET}"
    echo -e "  spring.security.oauth2.client.registration.google.client-secret: ${CLR_CYAN}${GOOGLE_CLIENT_SECRET:+[CONFIGURED]}${CLR_RESET}"
    echo ""
    echo -e "${CLR_BOLD}Useful Operational Commands:${CLR_RESET}"
    echo "  View real-time logs:     docker compose logs -f"
    echo "  Stop the environment:    docker compose down"
    echo "  Restart the environment: docker compose restart"
    echo "  Full uninstallation:     ./install.sh --uninstall"
    echo ""
    echo -e "${CLR_YELLOW}Important:${CLR_RESET} Upon logging in for the first time with the configured email (${INITIAL_ADMIN_EMAIL}),"
    echo "the ROLE_ADMIN authority will be automatically assigned to you to manage the system."
    echo ""
}

main() {
    parse_arguments "$@"

    if [[ "$FLAG_UNINSTALL" == true ]]; then
        uninstall_environment
    fi

    print_banner
    check_prerequisites
    configure_environment
    start_services
    wait_for_health
    bootstrap_ingestion
    print_summary
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    main "$@"
fi
