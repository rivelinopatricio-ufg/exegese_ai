#!/usr/bin/env bash
# ==============================================================================
# Exegese AI - Script de Instalação e Orquestração para Produção
# Permissões: chmod +x install.sh
# Uso: ./install.sh [--help] [--no-ingest] [--uninstall]
# ==============================================================================

set -euo pipefail

# Paleta de Cores ANSI para Terminal
readonly CLR_RESET="\033[0m"
readonly CLR_BOLD="\033[1m"
readonly CLR_GREEN="\033[32m"
readonly CLR_BLUE="\033[34m"
readonly CLR_YELLOW="\033[33m"
readonly CLR_RED="\033[31m"
readonly CLR_CYAN="\033[36m"

# Flags de Execução
FLAG_NO_INGEST=false
FLAG_UNINSTALL=false

log_info() {
    echo -e "${CLR_BLUE}[INFO]${CLR_RESET} $1"
}

log_success() {
    echo -e "${CLR_GREEN}${CLR_BOLD}[SUCESSO]${CLR_RESET} $1"
}

log_warn() {
    echo -e "${CLR_YELLOW}[AVISO]${CLR_RESET} $1"
}

log_error() {
    echo -e "${CLR_RED}${CLR_BOLD}[ERRO]${CLR_RESET} $1" >&2
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
 Plataforma RAG de Rigor Exegético e Grounding Normativo
==============================================================================
EOF
    echo -e "${CLR_RESET}"
}

print_help() {
    echo -e "${CLR_BOLD}Uso:${CLR_RESET} ./install.sh [OPÇÕES]"
    echo ""
    echo -e "${CLR_BOLD}Opções disponíveis:${CLR_RESET}"
    echo "  -h, --help        Exibe esta mensagem de ajuda e encerra."
    echo "  --no-ingest       Inicia a plataforma sem disparar a ingestão inicial de documentos."
    echo "  --uninstall       Para os contêineres, remove volumes persistentes e apaga o .env."
    echo ""
    echo -e "${CLR_BOLD}Exemplos:${CLR_RESET}"
    echo "  ./install.sh"
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
            *)
                log_error "Opção desconhecida: $1"
                print_help
                exit 1
                ;;
        esac
    done
}

uninstall_environment() {
    print_banner
    log_warn "Atenção: A desinstalação removerá todos os contêineres e volumes persistentes do Exegese AI."
    read -rp "Deseja prosseguir com a desinstalação? (s/N): " confirm
    if [[ "$confirm" =~ ^[sS]$ ]]; then
        log_info "Parando e removendo contêineres e volumes Docker..."
        docker compose down -v --remove-orphans || true
        if [[ -f .env ]]; then
            log_warn "Removendo arquivo de configurações .env..."
            rm -f .env
        fi
        log_success "Desinstalação concluída com sucesso."
    else
        log_info "Desinstalação cancelada pelo usuário."
    fi
    exit 0
}

check_prerequisites() {
    log_info "Verificando dependências do sistema operacional..."
    
    local missing=0

    if ! command -v docker &> /dev/null; then
        log_error "Docker não encontrado. Instale o Docker antes de continuar: https://docs.docker.com/engine/install/"
        missing=$((missing + 1))
    fi

    if ! docker compose version &> /dev/null; then
        log_error "Plugin Docker Compose não encontrado ou desatualizado."
        missing=$((missing + 1))
    fi

    if ! command -v curl &> /dev/null; then
        log_error "Utilitário 'curl' não encontrado. Instale o pacote curl."
        missing=$((missing + 1))
    fi

    if [[ $missing -gt 0 ]]; then
        log_error "Pré-requisitos ausentes. Abortando instalação."
        exit 1
    fi

    log_success "Todos os pré-requisitos (Docker, Docker Compose, curl) estão disponíveis."
}

generate_random_secret() {
    if command -v openssl &> /dev/null; then
        openssl rand -hex 16
    else
        cat /dev/urandom | tr -dc 'a-zA-Z0-9' | fold -w 32 | head -n 1
    fi
}

configure_environment() {
    if [[ -f .env ]]; then
        log_info "Arquivo '.env' existente detectado."
        read -rp "Deseja reconfigurar as variáveis de ambiente? (s/N): " reconf
        if [[ ! "$reconf" =~ ^[sS]$ ]]; then
            log_info "Utilizando as configurações existentes do .env."
            return 0
        fi
    fi

    log_info "Iniciando assistente de configuração de credenciais (.env)..."

    echo ""
    echo -e "${CLR_BOLD}1. Autenticação Google OAuth2 / OIDC (Obrigatório para login no painel):${CLR_RESET}"
    read -rp "Google Client ID: " GOOGLE_CLIENT_ID
    read -rsp "Google Client Secret: " GOOGLE_CLIENT_SECRET
    echo ""
    read -rp "E-mail do Administrador Inicial (receberá ROLE_ADMIN no 1º login) [admin@exegese.ai]: " INITIAL_ADMIN_EMAIL
    INITIAL_ADMIN_EMAIL=${INITIAL_ADMIN_EMAIL:-admin@exegese.ai}

    echo ""
    echo -e "${CLR_BOLD}2. Provedores de Inteligência Artificial (Opcional - informe ao menos um ou cadastre depois no Admin):${CLR_RESET}"
    read -rsp "Google Gemini API Key (Enter para pular): " GEMINI_API_KEY
    echo ""
    read -rsp "OpenAI API Key (Enter para pular): " OPENAI_API_KEY
    echo ""
    read -rsp "Anthropic Claude API Key (Enter para pular): " ANTHROPIC_API_KEY
    echo ""

    local AES_SECRET
    AES_SECRET=$(generate_random_secret)

    cat > .env << EOF
# ==============================================================================
# Configurações de Ambiente - Exegese AI
# Gerado automaticamente pelo script install.sh em $(date -u +"%Y-%m-%dT%H:%M:%SZ")
# ==============================================================================

# Banco de Dados PostgreSQL 17 + pgvector
POSTGRES_DB=exegese_db
POSTGRES_USER=exegese_user
POSTGRES_PASSWORD=exegese_password
POSTGRES_PORT=5432

# Servidor Web
PORT=8080

# Chave Criptográfica Mestra AES-256 (Gerada aleatoriamente)
EXEGESE_AES_SECRET=${AES_SECRET}

# Administrador Inicial
INITIAL_ADMIN_EMAIL=${INITIAL_ADMIN_EMAIL}

# Google OAuth2 / OIDC
GOOGLE_CLIENT_ID=${GOOGLE_CLIENT_ID}
GOOGLE_CLIENT_SECRET=${GOOGLE_CLIENT_SECRET}

# Provedores de LLM
GEMINI_API_KEY=${GEMINI_API_KEY}
OPENAI_API_KEY=${OPENAI_API_KEY}
ANTHROPIC_API_KEY=${ANTHROPIC_API_KEY}
NVIDIA_API_KEY=
DEEPSEEK_API_KEY=
OLLAMA_BASE_URL=http://localhost:11434
EOF

    chmod 600 .env
    log_success "Arquivo '.env' gravado com permissões restritas (600)."
}

start_services() {
    log_info "Construindo e iniciando contêineres via Docker Compose..."
    docker compose up -d --build
    log_success "Contêineres instanciados com sucesso."
}

wait_for_health() {
    log_info "Aguardando inicialização da aplicação e banco de dados..."
    
    local retries=30
    local count=0
    local health_url="http://localhost:8080/actuator/health"

    while [[ $count -lt $retries ]]; do
        if curl -s -f "$health_url" | grep -q '"status":"UP"'; then
            log_success "Aplicação Exegese AI está ONLINE e respondendo com status UP!"
            return 0
        fi
        count=$((count + 1))
        echo -n "."
        sleep 3
    done

    echo ""
    log_error "Tempo limite excedido aguardando inicialização da aplicação."
    log_info "Verifique os logs detalhados com: docker compose logs app"
    exit 1
}

bootstrap_ingestion() {
    if [[ "$FLAG_NO_INGEST" == true ]]; then
        log_info "Flag '--no-ingest' ativada. Ingestão inicial de documentos pulada."
        return 0
    fi

    log_info "Acervo inicial pronto. Você pode gerenciar documentos via painel administrativo."
}

print_summary() {
    echo ""
    echo -e "${CLR_GREEN}${CLR_BOLD}==============================================================================${CLR_RESET}"
    echo -e "${CLR_GREEN}${CLR_BOLD}   EXEGESE AI — INSTALAÇÃO E INICIALIZAÇÃO CONCLUÍDAS COM SUCESSO!           ${CLR_RESET}"
    echo -e "${CLR_GREEN}${CLR_BOLD}==============================================================================${CLR_RESET}"
    echo ""
    echo -e "${CLR_BOLD}Painel de Acesso:${CLR_RESET}"
    echo -e "  URL Principal:            ${CLR_CYAN}http://localhost:8080${CLR_RESET}"
    echo -e "  Login Google OAuth2:      ${CLR_CYAN}http://localhost:8080/login${CLR_RESET}"
    echo -e "  Gestão Administrativa:    ${CLR_CYAN}http://localhost:8080/admin/users${CLR_RESET}"
    echo -e "  Catálogo de Modelos AI:   ${CLR_CYAN}http://localhost:8080/admin/models${CLR_RESET}"
    echo -e "  Healthcheck Actuator:     ${CLR_CYAN}http://localhost:8080/actuator/health${CLR_RESET}"
    echo ""
    echo -e "${CLR_BOLD}Comandos Úteis de Operação:${CLR_RESET}"
    echo "  Visualizar logs em tempo real:   docker compose logs -f app"
    echo "  Parar o ambiente:                docker compose down"
    echo "  Reiniciar o ambiente:            docker compose restart"
    echo "  Desinstalação completa:          ./install.sh --uninstall"
    echo ""
    echo -e "${CLR_YELLOW}Importante:${CLR_RESET} Ao fazer login pela primeira vez com o e-mail configurado,"
    echo "o perfil ROLE_ADMIN será atribuído automaticamente para você gerenciar o sistema."
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

main "$@"
