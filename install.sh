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
readonly DEFAULT_POSTGRES_DB="exegese_db"
readonly DEFAULT_OLLAMA_BASE_URL="http://ollama:11434"
readonly DEFAULT_OLLAMA_CHAT_MODEL="llama3.2"

# Values used by earlier versions of this script / docker-compose.yml (public, must not be kept)
readonly LEGACY_POSTGRES_USER="exegese_user"
readonly LEGACY_POSTGRES_PASSWORD="exegese_password"
readonly LEGACY_OLLAMA_BASE_URL="http://localhost:11434"

# Execution Flags and Configuration Variables
FLAG_NO_INGEST=false
FLAG_UNINSTALL=false
FLAG_SECRET_ON_CLI=false
DB_PASSWORD_IS_DEFAULT=false
HOST_NAME=""
HTTP_PORT=""
HTTPS_PORT=""
EMAIL_SSL=""

# Credentials may also be provided as environment variables (preferred over CLI flags, which
# are visible to other users in 'ps' and are stored in the shell history)
GOOGLE_CLIENT_ID="${GOOGLE_CLIENT_ID:-}"
GOOGLE_CLIENT_SECRET="${GOOGLE_CLIENT_SECRET:-}"
INITIAL_ADMIN_EMAIL="${INITIAL_ADMIN_EMAIL:-}"
GEMINI_API_KEY="${GEMINI_API_KEY:-}"
OPENAI_API_KEY="${OPENAI_API_KEY:-}"
ANTHROPIC_API_KEY="${ANTHROPIC_API_KEY:-}"
CEREBRAS_API_KEY="${CEREBRAS_API_KEY:-}"

# Database credentials and AES master key: preserved from an existing .env, otherwise taken from
# the environment, otherwise generated randomly on first install
POSTGRES_DB="${POSTGRES_DB:-}"
POSTGRES_USER="${POSTGRES_USER:-}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-}"
EXEGESE_AES_SECRET="${EXEGESE_AES_SECRET:-}"
OLLAMA_BASE_URL="${OLLAMA_BASE_URL:-}"
OLLAMA_CHAT_MODEL="${OLLAMA_CHAT_MODEL:-}"

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
    echo -e "${CLR_YELLOW}${CLR_BOLD}Security note:${CLR_RESET} secrets passed as command-line flags (--google-client-secret, --*-key)"
    echo "are visible to other local users in 'ps' and are saved in your shell history. Prefer environment"
    echo "variables (or the interactive prompts, which do not echo secrets):"
    echo "  GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, INITIAL_ADMIN_EMAIL,"
    echo "  GEMINI_API_KEY, OPENAI_API_KEY, ANTHROPIC_API_KEY, CEREBRAS_API_KEY"
    echo "Values typed inline before the command also reach the history: load them with 'read -rs VAR; export VAR'."
    echo ""
    echo -e "${CLR_BOLD}Generated secrets:${CLR_RESET}"
    echo "  On first install, POSTGRES_USER (exegese_<random hex>), POSTGRES_PASSWORD (32 random"
    echo "  alphanumeric characters) and EXEGESE_AES_SECRET (openssl rand -base64 32) are generated"
    echo "  into .env (chmod 600). When an existing .env is reconfigured, POSTGRES_DB, POSTGRES_USER,"
    echo "  POSTGRES_PASSWORD and EXEGESE_AES_SECRET are preserved: the database volume keeps the"
    echo "  credentials it was created with, and a new AES key would make stored API keys unreadable."
    echo "  To reuse an existing database without its .env, export POSTGRES_USER and POSTGRES_PASSWORD"
    echo "  (and EXEGESE_AES_SECRET, if known) before running the installer."
    echo ""
    echo -e "${CLR_BOLD}Examples:${CLR_RESET}"
    echo "  ./install.sh"
    echo "  ./install.sh -H exegese-ai.sytes.net --http-port 80 --https-port 443 --email-ssl admin@exegese-ai.sytes.net"
    echo "  read -rs GOOGLE_CLIENT_SECRET && export GOOGLE_CLIENT_SECRET && GOOGLE_CLIENT_ID=xxxx.apps.googleusercontent.com ./install.sh"
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
                FLAG_SECRET_ON_CLI=true
                shift 2
                ;;
            --initial-admin)
                INITIAL_ADMIN_EMAIL="$2"
                shift 2
                ;;
            --gemini-key)
                GEMINI_API_KEY="$2"
                FLAG_SECRET_ON_CLI=true
                shift 2
                ;;
            --openai-key)
                OPENAI_API_KEY="$2"
                FLAG_SECRET_ON_CLI=true
                shift 2
                ;;
            --anthropic-key)
                ANTHROPIC_API_KEY="$2"
                FLAG_SECRET_ON_CLI=true
                shift 2
                ;;
            --cerebras-key)
                CEREBRAS_API_KEY="$2"
                FLAG_SECRET_ON_CLI=true
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
        if command -v ufw &> /dev/null; then
            log_info "Tip: To remove firewall rules in Ubuntu UFW, run:"
            echo -e "  sudo ufw delete allow 80/tcp"
            echo -e "  sudo ufw delete allow 443/tcp"
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

# Random password from [A-Za-z0-9] only: safe in .env files, JDBC URLs and SQL literals without escaping
generate_password() {
    local length="${1:-32}"
    local pool=""
    while [[ ${#pool} -lt $length ]]; do
        if command -v openssl &> /dev/null; then
            pool+=$(openssl rand -base64 48 | LC_ALL=C tr -dc 'A-Za-z0-9')
        else
            pool+=$(head -c 256 /dev/urandom | LC_ALL=C tr -dc 'A-Za-z0-9')
        fi
    done
    printf '%s' "${pool:0:length}"
}

generate_hex() {
    local bytes="${1:-4}"
    if command -v openssl &> /dev/null; then
        openssl rand -hex "$bytes"
    else
        head -c "$bytes" /dev/urandom | od -An -tx1 | tr -d ' \n'
    fi
}

# AES-256 master key: Base64 of exactly 32 random bytes (format required by exegese.security.crypto-key)
generate_aes_key() {
    if command -v openssl &> /dev/null; then
        openssl rand -base64 32
    else
        head -c 32 /dev/urandom | base64 | tr -d '\n'
    fi
}

is_valid_aes_key() {
    [[ "${1:-}" =~ ^[A-Za-z0-9+/]{43}=$ ]]
}

# Prints the value of KEY in an env file (last occurrence, surrounding quotes and CR removed)
read_env_value() {
    local key="$1"
    local file="${2:-.env}"
    local value=""
    [[ -f "$file" ]] || return 0
    value=$(grep -E "^${key}=" "$file" | tail -n 1 | cut -d '=' -f2- || true)
    value="${value%$'\r'}"
    if [[ "$value" =~ ^\"(.*)\"$ || "$value" =~ ^\'(.*)\'$ ]]; then
        value="${BASH_REMATCH[1]}"
    fi
    printf '%s' "$value"
}

# Sets KEY=VALUE in an env file in place (replacing or appending), keeping its permissions
set_env_value() {
    local key="$1"
    local value="$2"
    local file="${3:-.env}"
    local tmp
    tmp=$(mktemp "${file}.XXXXXX")
    if grep -qE "^${key}=" "$file"; then
        KEY="$key" VALUE="$value" awk 'BEGIN { k = ENVIRON["KEY"]; v = ENVIRON["VALUE"] }
            index($0, k "=") == 1 { print k "=" v; next } { print }' "$file" > "$tmp"
    else
        cat "$file" > "$tmp"
        if [[ -s "$file" && -n "$(tail -c 1 "$file")" ]]; then
            echo "" >> "$tmp"
        fi
        printf '%s=%s\n' "$key" "$value" >> "$tmp"
    fi
    cat "$tmp" > "$file"
    rm -f "$tmp"
}

find_existing_pgdata_volumes() {
    docker volume ls --format '{{.Name}}' 2>/dev/null | grep -E 'pgdata$' || true
}

warn_default_db_password() {
    local user="$1"
    local db="$2"
    echo ""
    echo -e "${CLR_RED}${CLR_BOLD}==============================================================================${CLR_RESET}"
    echo -e "${CLR_RED}${CLR_BOLD} SECURITY WARNING: POSTGRES_PASSWORD is still the public default '${LEGACY_POSTGRES_PASSWORD}'${CLR_RESET}"
    echo -e "${CLR_RED}${CLR_BOLD}==============================================================================${CLR_RESET}"
    echo "This password is published in the project repository. Earlier versions also published port 5432"
    echo "on all interfaces, so consider the database contents and the stored API keys exposed: rotate"
    echo "the provider API keys and the Google client secret as well."
    echo ""
    echo "Rotate the database password once the containers are running:"
    cat << EOF
  1. NEW_PASS=\$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 32)
  2. docker compose exec postgres psql -U ${user} -d ${db} -c "ALTER USER ${user} WITH PASSWORD '\$NEW_PASS';"
  3. sed -i "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=\$NEW_PASS/" .env
  4. docker compose up -d    (recreates the app container with the new password)
EOF
    echo ""
}

# Database volumes survive 'docker compose down' and a deleted .env: PostgreSQL applies
# POSTGRES_USER/POSTGRES_PASSWORD only when the volume is initialized for the first time.
warn_existing_pgdata_without_env() {
    local volumes
    volumes=$(find_existing_pgdata_volumes)
    [[ -z "$volumes" ]] && return 0

    echo ""
    echo -e "${CLR_YELLOW}${CLR_BOLD}[DATABASE VOLUME FOUND WITHOUT .env]${CLR_RESET}"
    echo "Existing PostgreSQL data volume(s):"
    while IFS= read -r volume; do
        echo -e "  ${CLR_CYAN}${volume}${CLR_RESET}"
    done <<< "$volumes"
    echo "The existing database keeps its ORIGINAL credentials (installations made by earlier versions"
    echo "of this script used ${LEGACY_POSTGRES_USER} / ${LEGACY_POSTGRES_PASSWORD}). Newly generated credentials will be"
    echo "rejected by that database and the application will not start. Options:"
    echo "  - restore the previous .env and run ./install.sh again;"
    echo "  - run again with the existing credentials, e.g.:"
    echo "      POSTGRES_USER=${LEGACY_POSTGRES_USER} POSTGRES_PASSWORD='<current password>' ./install.sh"
    echo "    (then rotate a default password as the installer explains);"
    echo "  - or delete the old database (ALL DATA LOST): docker volume rm <volume>"

    if [[ -n "$POSTGRES_USER" && -n "$POSTGRES_PASSWORD" ]]; then
        log_info "Using POSTGRES_USER/POSTGRES_PASSWORD provided through the environment."
        return 0
    fi
    read -rp "Continue with newly generated database credentials anyway? (y/N): " pg_continue
    if [[ ! "$pg_continue" =~ ^[yY]$ ]]; then
        log_info "Installation aborted. No files were changed."
        exit 1
    fi
}

# Fills POSTGRES_USER/POSTGRES_PASSWORD missing from an old .env: earlier docker-compose.yml
# defaults applied to an existing volume, otherwise fresh random values
fill_missing_db_credentials() {
    local has_volume=false
    if [[ -n "$(find_existing_pgdata_volumes)" ]]; then
        has_volume=true
    fi
    if [[ -z "$POSTGRES_USER" ]]; then
        if [[ "$has_volume" == true ]]; then
            POSTGRES_USER="$LEGACY_POSTGRES_USER"
        else
            POSTGRES_USER="exegese_$(generate_hex 4)"
        fi
    fi
    if [[ -z "$POSTGRES_PASSWORD" ]]; then
        if [[ "$has_volume" == true ]]; then
            POSTGRES_PASSWORD="$LEGACY_POSTGRES_PASSWORD"
        else
            POSTGRES_PASSWORD=$(generate_password 32)
        fi
    fi
}

# Reads the values that must survive a reconfiguration from the existing .env
load_preserved_settings() {
    local key value
    for key in POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD EXEGESE_AES_SECRET OLLAMA_BASE_URL OLLAMA_CHAT_MODEL; do
        value=$(read_env_value "$key")
        if [[ -n "$value" ]]; then
            printf -v "$key" '%s' "$value"
        fi
    done
    fill_missing_db_credentials
    log_info "Preserving existing database credentials (POSTGRES_USER=${POSTGRES_USER}) and AES master key from .env."
    if [[ "$POSTGRES_PASSWORD" == "$LEGACY_POSTGRES_PASSWORD" ]]; then
        DB_PASSWORD_IS_DEFAULT=true
        warn_default_db_password "$POSTGRES_USER" "${POSTGRES_DB:-$DEFAULT_POSTGRES_DB}"
    fi
}

# Completes the database credentials, AES key and Ollama settings (generating what is missing)
resolve_generated_settings() {
    POSTGRES_DB="${POSTGRES_DB:-$DEFAULT_POSTGRES_DB}"
    if [[ -z "$POSTGRES_USER" ]]; then
        POSTGRES_USER="exegese_$(generate_hex 4)"
        log_info "Generated database user: ${POSTGRES_USER}"
    fi
    if [[ -z "$POSTGRES_PASSWORD" ]]; then
        POSTGRES_PASSWORD=$(generate_password 32)
        log_info "Generated a random 32-character database password."
    fi
    if ! is_valid_aes_key "$EXEGESE_AES_SECRET"; then
        if [[ -n "$EXEGESE_AES_SECRET" ]]; then
            log_warn "EXEGESE_AES_SECRET is not the Base64 encoding of 32 bytes (earlier installers generated a hex"
            log_warn "value that the application never used); generating a new key. Stored API keys are migrated automatically."
        fi
        EXEGESE_AES_SECRET=$(generate_aes_key)
    fi
    if [[ -z "$OLLAMA_BASE_URL" || "$OLLAMA_BASE_URL" == "$LEGACY_OLLAMA_BASE_URL" ]]; then
        OLLAMA_BASE_URL="$DEFAULT_OLLAMA_BASE_URL"
    fi
    OLLAMA_CHAT_MODEL="${OLLAMA_CHAT_MODEL:-$DEFAULT_OLLAMA_CHAT_MODEL}"
}

# Brings an existing .env (kept without reconfiguration) up to the current requirements
upgrade_existing_env() {
    local env_user env_password env_db env_aes env_ollama
    env_user=$(read_env_value POSTGRES_USER)
    env_password=$(read_env_value POSTGRES_PASSWORD)
    env_db=$(read_env_value POSTGRES_DB)
    env_aes=$(read_env_value EXEGESE_AES_SECRET)
    env_ollama=$(read_env_value OLLAMA_BASE_URL)

    if [[ -z "$env_user" || -z "$env_password" ]]; then
        POSTGRES_USER="$env_user"
        POSTGRES_PASSWORD="$env_password"
        fill_missing_db_credentials
        [[ -z "$env_user" ]] && set_env_value POSTGRES_USER "$POSTGRES_USER"
        [[ -z "$env_password" ]] && set_env_value POSTGRES_PASSWORD "$POSTGRES_PASSWORD"
        env_user="$POSTGRES_USER"
        env_password="$POSTGRES_PASSWORD"
        log_info "Added the missing database credentials to .env (now required by docker-compose.yml)."
    fi
    if [[ -z "$env_db" ]]; then
        env_db="$DEFAULT_POSTGRES_DB"
        set_env_value POSTGRES_DB "$env_db"
    fi
    if ! is_valid_aes_key "$env_aes"; then
        set_env_value EXEGESE_AES_SECRET "$(generate_aes_key)"
        log_warn "EXEGESE_AES_SECRET in .env was missing or not the Base64 encoding of 32 bytes; a new key was generated."
        log_info "The previous value was never read by the application. Stored API keys are re-encrypted automatically on startup."
    fi
    if [[ -z "$env_ollama" || "$env_ollama" == "$LEGACY_OLLAMA_BASE_URL" ]]; then
        set_env_value OLLAMA_BASE_URL "$DEFAULT_OLLAMA_BASE_URL"
    fi
    if [[ "$env_password" == "$LEGACY_POSTGRES_PASSWORD" ]]; then
        DB_PASSWORD_IS_DEFAULT=true
        POSTGRES_USER="$env_user"
        POSTGRES_DB="$env_db"
        warn_default_db_password "$env_user" "$env_db"
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
            if [[ -z "$HOST_NAME" ]]; then
                HOST_NAME=$(grep -E '^SERVER_NAME=' .env | cut -d '=' -f2- || true)
                if [[ -z "$HOST_NAME" ]]; then
                    HOST_NAME=$(grep -E '^URL=' .env | cut -d '=' -f2- || echo "$DEFAULT_HOST")
                fi
            fi
            if [[ -z "$HTTPS_PORT" ]]; then
                HTTPS_PORT=$(grep -E '^HTTPS_PORT=' .env | cut -d '=' -f2- || echo "443")
            fi
            if [[ -z "$HTTP_PORT" ]]; then
                HTTP_PORT=$(grep -E '^HTTP_PORT=' .env | cut -d '=' -f2- || echo "80")
            fi
            if [[ -z "$EMAIL_SSL" ]]; then
                EMAIL_SSL=$(grep -E '^LETSENCRYPT_EMAIL=' .env | cut -d '=' -f2- || echo "admin@${HOST_NAME}")
            fi
            if [[ -z "$GOOGLE_CLIENT_ID" ]]; then
                GOOGLE_CLIENT_ID=$(grep -E '^GOOGLE_CLIENT_ID=' .env | cut -d '=' -f2- || true)
            fi
            if [[ -z "$GOOGLE_CLIENT_SECRET" ]]; then
                GOOGLE_CLIENT_SECRET=$(grep -E '^GOOGLE_CLIENT_SECRET=' .env | cut -d '=' -f2- || true)
            fi
            if [[ -z "$INITIAL_ADMIN_EMAIL" ]]; then
                INITIAL_ADMIN_EMAIL=$(grep -E '^INITIAL_ADMIN_EMAIL=' .env | cut -d '=' -f2- || echo "$DEFAULT_ADMIN_EMAIL")
            fi
            if [[ -z "$GEMINI_API_KEY" ]]; then
                GEMINI_API_KEY=$(grep -E '^GEMINI_API_KEY=' .env | cut -d '=' -f2- || true)
            fi
            if [[ -z "$OPENAI_API_KEY" ]]; then
                OPENAI_API_KEY=$(grep -E '^OPENAI_API_KEY=' .env | cut -d '=' -f2- || true)
            fi
            if [[ -z "$ANTHROPIC_API_KEY" ]]; then
                ANTHROPIC_API_KEY=$(grep -E '^ANTHROPIC_API_KEY=' .env | cut -d '=' -f2- || true)
            fi
            if [[ -z "$CEREBRAS_API_KEY" ]]; then
                CEREBRAS_API_KEY=$(grep -E '^CEREBRAS_API_KEY=' .env | cut -d '=' -f2- || true)
            fi
            upgrade_existing_env
            return 0
        fi
        # Reconfiguration: the database volume and the stored API keys depend on these values
        load_preserved_settings
    else
        warn_existing_pgdata_without_env
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

    resolve_generated_settings

    # Create the file with restricted permissions before any secret is written to it
    ( umask 077 && : > .env )
    chmod 600 .env
    cat > .env << EOF
# ==============================================================================
# Environment Configuration - Exegese AI
# Automatically generated by install.sh on $(date -u +"%Y-%m-%dT%H:%M:%SZ")
# ==============================================================================

# PostgreSQL 17 + pgvector Database (not published on the host; reachable only on exegese-net)
# Generated on first install and preserved on reconfiguration: the data volume keeps the
# credentials it was initialized with (change them with ALTER USER, then update this file)
POSTGRES_DB=${POSTGRES_DB}
POSTGRES_USER=${POSTGRES_USER}
POSTGRES_PASSWORD=${POSTGRES_PASSWORD}

# Web Server & NGINX Reverse Proxy (SWAG)
SERVER_NAME=${HOST_NAME}
URL=${HOST_NAME}
HTTP_PORT=${HTTP_PORT}
HTTPS_PORT=${HTTPS_PORT}
LETSENCRYPT_EMAIL=${EMAIL_SSL}
PORT=8080

# Master AES-256 key for API keys stored in the database: Base64 of 32 random bytes.
# Back it up and keep it stable: changing or losing it makes the stored API keys unreadable.
EXEGESE_AES_SECRET=${EXEGESE_AES_SECRET}

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

# Optional local Ollama (docker-compose.override.ai*.yml), reached on the internal network
OLLAMA_BASE_URL=${OLLAMA_BASE_URL}
OLLAMA_CHAT_MODEL=${OLLAMA_CHAT_MODEL}
EOF

    chmod 600 .env
    log_success "File '.env' written with restricted permissions (600)."
}

configure_nginx_proxy() {
    local target_https_port="${HTTPS_PORT:-443}"
    local target_http_port="${HTTP_PORT:-80}"
    log_info "Configuring NGINX site definition with HTTP port (${target_http_port}) and HTTPS port (${target_https_port})..."

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
    listen ${target_http_port} default_server;
    listen [::]:${target_http_port} default_server;

    server_name _;

    return 301 ${https_redirect_target};
}

# HTTPS Block: SWAG-managed TLS termination and Spring Boot upstream
server {
    listen ${target_https_port} ssl default_server;
    listen [::]:${target_https_port} ssl default_server;
    http2 on;

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

# enable subdomain method reverse proxy confs
include /config/nginx/proxy-confs/*.subdomain.conf;
# enable proxy cache for auth
proxy_cache_path cache/ keys_zone=auth_cache:10m;
EOF
    log_success "NGINX configuration dynamically generated for HTTP port ${target_http_port} and HTTPS port ${target_https_port}."
}

configure_firewall() {
    local target_http_port="${HTTP_PORT:-80}"
    local target_https_port="${HTTPS_PORT:-443}"

    echo ""
    log_info "Verifying Ubuntu firewall (UFW) configuration..."

    if command -v ufw &> /dev/null; then
        local ufw_status
        ufw_status=$(ufw status 2>/dev/null || sudo ufw status 2>/dev/null || true)

        if echo "$ufw_status" | grep -qi "Status: active"; then
            log_warn "Ubuntu UFW firewall is ACTIVE. Releasing incoming traffic for configured ports..."

            local cmd_prefix=""
            if [[ $EUID -ne 0 ]]; then
                if command -v sudo &> /dev/null; then
                    cmd_prefix="sudo "
                fi
            fi

            echo -e "${CLR_YELLOW}${CLR_BOLD}[FIREWALL NOTICE]${CLR_RESET} Configuring UFW ingress rules for ports ${target_http_port}/tcp (HTTP) and ${target_https_port}/tcp (HTTPS)..."

            # shellcheck disable=SC2086 # cmd_prefix is intentionally empty or "sudo "
            if ${cmd_prefix}ufw allow "${target_http_port}/tcp" comment 'Exegese AI HTTP' &> /dev/null && \
               ${cmd_prefix}ufw allow "${target_https_port}/tcp" comment 'Exegese AI HTTPS' &> /dev/null; then
                log_success "Ubuntu UFW rules successfully applied: ${target_http_port}/tcp and ${target_https_port}/tcp are open."
            else
                log_warn "Unable to execute 'ufw allow' automatically. Please execute manually with sudo privileges:"
                echo -e "  ${CLR_CYAN}${cmd_prefix}ufw allow ${target_http_port}/tcp comment 'Exegese AI HTTP'${CLR_RESET}"
                echo -e "  ${CLR_CYAN}${cmd_prefix}ufw allow ${target_https_port}/tcp comment 'Exegese AI HTTPS'${CLR_RESET}"
            fi
        else
            log_info "Ubuntu UFW firewall is installed but currently INACTIVE (disabled)."
            echo -e "${CLR_YELLOW}${CLR_BOLD}[FIREWALL NOTICE]${CLR_RESET} If you enable UFW or manage an external cloud firewall (AWS Security Group, Oracle Cloud VCN, etc.), release incoming TCP traffic on ports:"
            echo -e "  ${CLR_CYAN}sudo ufw allow ${target_http_port}/tcp comment 'Exegese AI HTTP'${CLR_RESET}"
            echo -e "  ${CLR_CYAN}sudo ufw allow ${target_https_port}/tcp comment 'Exegese AI HTTPS'${CLR_RESET}"
        fi
    else
        log_info "Ubuntu UFW utility not found on host."
        echo -e "${CLR_YELLOW}${CLR_BOLD}[FIREWALL NOTICE]${CLR_RESET} Please ensure your operating system or cloud security group allows incoming TCP connections on ports ${target_http_port} and ${target_https_port}."
    fi
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
    local host="${HOST_NAME:-$DEFAULT_HOST}"
    local proto="https"
    local port_part=""
    if [[ "$host" == "localhost" || "$host" == "127.0.0.1" ]]; then
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
    local base_url="${proto}://${host}${port_part}"
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
    echo -e "${CLR_BOLD}Secrets:${CLR_RESET}"
    echo "  Database credentials and the AES master key (EXEGESE_AES_SECRET) are stored only in .env (chmod 600)."
    echo "  Back up .env securely: without EXEGESE_AES_SECRET the API keys saved in the admin panel cannot be decrypted."
    echo ""
    echo -e "${CLR_BOLD}Firewall Configuration (Ubuntu UFW):${CLR_RESET}"
    echo -e "  Open HTTP Port:              ${CLR_CYAN}sudo ufw allow ${HTTP_PORT:-80}/tcp comment 'Exegese AI HTTP'${CLR_RESET}"
    echo -e "  Open HTTPS Port:             ${CLR_CYAN}sudo ufw allow ${HTTPS_PORT:-443}/tcp comment 'Exegese AI HTTPS'${CLR_RESET}"
    echo -e "  Inspect Active Status:       ${CLR_CYAN}sudo ufw status verbose${CLR_RESET}"
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
    if [[ "$DB_PASSWORD_IS_DEFAULT" == true ]]; then
        warn_default_db_password "${POSTGRES_USER:-$LEGACY_POSTGRES_USER}" "${POSTGRES_DB:-$DEFAULT_POSTGRES_DB}"
    fi
}

main() {
    parse_arguments "$@"

    if [[ "$FLAG_SECRET_ON_CLI" == true ]]; then
        log_warn "Secrets were passed as command-line flags: they are visible in 'ps' and stored in your shell history."
        log_warn "Prefer environment variables (see ./install.sh --help) and consider clearing the history entry."
    fi

    if [[ "$FLAG_UNINSTALL" == true ]]; then
        uninstall_environment
    fi

    print_banner
    check_prerequisites
    configure_environment
    configure_firewall
    start_services
    wait_for_health
    bootstrap_ingestion
    print_summary
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    main "$@"
fi
