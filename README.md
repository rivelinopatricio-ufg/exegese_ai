# Exegese AI — Plataforma RAG de Rigor Exegético e Grounding Normativo

[![Java](https://img.shields.io/badge/Java-25%20Nativo%20%28Major%2069%29-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1%20GA-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.1-blue.svg)](https://spring.io/projects/spring-ai)
[![Reverse Proxy](https://img.shields.io/badge/Proxy-SWAG%20%28NGINX%20%2B%20Certbot%20%2B%20Fail2ban%29-success.svg)](https://docs.linuxserver.io/general/swag)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17%20%2B%20pgvector-blue.svg)](https://github.com/pgvector/pgvector)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

> **Exegese AI** é uma plataforma corporativa e institucional de Recuperação Aumentada por Geração (**RAG — Retrieval-Augmented Generation**) projetada especificamente para cenários onde a precisão documental, o rigor exegético e a fundamentação normativa são requisitos intransigíveis.

---

## 1. Destaques e Proposta de Valor

- **Política de Tolerância Zero a Alucinações (*Zero Hallucination Policy*)**: A plataforma só responde com base em evidências recuperadas dos documentos anexados aos assuntos selecionados. Se a similaridade ponderada cair abaixo do limiar (0.65) ou nenhuma fonte for localizada, o sistema emite prontamente a recusa canônica:
  > *"Essa informação não consta nos documentos dos assuntos selecionados."*
- **Busca Híbrida de Alta Precisão (RRF)**: Combina indexação vetorial densa com índice HNSW (distância por cosseno) e busca lexical textual (*Full-Text Search* com `tsvector` e `tsquery` em português), fundindo os rankings através do algoritmo **Reciprocal Rank Fusion (RRF)**:
  $$Score_{RRF} = \frac{1}{60 + rank_{vec}} + \frac{1}{60 + rank_{txt}}$$
- **Proxy Reverso Hardened com Gestão Autônoma de Certificados (SWAG / NGINX)**:
  - Borda de segurança baseada na imagem `linuxserver/swag`, consolidando **NGINX**, **Certbot integrado**, **s6-overlay init system** e **Fail2ban**.
  - **Emissão no Startup**: O próprio proxy solicita o certificado Let's Encrypt na inicialização antes de expor os serviços.
  - **Renovação Automática via Cron**: O processo cron interno do contêiner verifica periodicamente (2x ao dia) e renova silenciosamente os certificados SSL/TLS com recarregamento suave (*reload*) do NGINX.
  - **Isolamento de Aplicação**: A porta 8080 do Spring Boot fica totalmente confinada na rede interna Docker (`exegese-net`).
  - **Otimização para SSE**: Rota `/api/chat/stream` com `proxy_buffering off;`, `X-Accel-Buffering no;` e timeout de 3600s para streaming contínuo sem bufferização.
  - **Portas e Host Customizáveis**: Permite expor portas alternativas (ex: 8080/8443) caso as portas padrão 80/443 estejam ocupadas no servidor host.
- **Arquitetura Multi-Provedor Dinâmica**: Roteamento transparente e em tempo de execução entre 6 ecossistemas de IA de ponta:
  - **Google Gemini** (Gemini 2.5 Flash / Pro)
  - **Anthropic Claude** (Claude 3.5 Sonnet)
  - **OpenAI ChatGPT** (GPT-4o)
  - **NVIDIA Nemotron** (Llama 3.1 Nemotron 70B)
  - **DeepSeek AI** (DeepSeek V3 / R1)
  - **Processamento Local (Ollama)** (Llama 3.2, Qwen 2.5, DeepSeek R1 Local)
- **Criptografia Mestra AES-256**: Chaves de API podem ser configuradas no arquivo `.env` ou gerenciadas em tempo real via Painel Administrativo com criptografia simétrica AES-256 no banco de dados.
- **Autenticação Federada Google OAuth2 & RBAC**: Controle granular de acesso baseado em papéis (`ROLE_ADMIN`, `ROLE_OPERATOR`, `ROLE_USER`) com permissões específicas por assunto e bootstrap automático do primeiro administrador via `INITIAL_ADMIN_EMAIL`.
- **Interface Conversacional Reativa & Acessível**: Chat em tempo real via Server-Sent Events (SSE), efeito de digitação suave, chips de citações canônicas clicáveis e modais com fundamentação jurídica completa em conformidade com as diretrizes **WCAG 2.1 AA**.

---

## 2. Arquitetura da Solução

```
                              CLIENTES WEB / NAVEGADORES
                                         │
                                         ▼ Portas Customizadas (${HTTP_PORT} / ${HTTPS_PORT})
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        PROXY REVERSO HARDENED (SWAG)                                   │
│                        (Imagem: linuxserver/swag:latest)                               │
│                         Domínio: ${SERVER_NAME} / ${URL}                               │
│                                                                                        │
│  - Gestão TLS/SSL Autônoma: Solicitação no Startup + Renovação Automática via Cron    │
│  - Proteção Ativa contra Intrusão: Fail2ban (cap_add: NET_ADMIN)                       │
│  - Ocultação de Assinatura: server_tokens off                                          │
│  - Otimização SSE: proxy_buffering off; proxy_read_timeout 3600s                       │
│  - Limites de Payload: 50MB (upload de PDF) / 10MB (geral)                             │
│  - Repasse de Cabeçalhos: X-Forwarded-For, X-Forwarded-Proto, X-Forwarded-Port         │
│  - Volume Persistente Único: proxy_config:/config                                      │
└───────────────────────────────────────┬────────────────────────────────────────────────┘
                                        │ Rede Interna Docker (exegese-net)
                                        │ Porta 8080 (ISOLADA)
                                        ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        APLICAÇÃO EXEGESE AI (SPRING BOOT 4.1.1)                        │
│                               Runtime: Java 25 Nativo                                  │
│                                                                                        │
│  - server.forward-headers-strategy: framework                                          │
│  - Spring AI 2.0.1 (RAG, ChatClient, RRF Search)                                       │
│  - RateLimitFilter (Bucket4j - 2ª camada de segurança)                                 │
│  - InputSanitizationFilter (Prompt Injection)                                          │
│  - Google OAuth2 Client (spring.security.oauth2.client.registration.google.*)          │
│  - Actuator (/actuator/health)                                                         │
└───────────────────────────────────────┬────────────────────────────────────────────────┘
                                        │ Porta 5432 (Interna)
                                        ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                               POSTGRESQL 17 + PGVECTOR                                 │
│                                                                                        │
│  - Chunks Vetorizados (HNSW Cosine Distance)                                           │
│  - Busca Textual em Português (GIN tsvector)                                           │
│  - Particionamento N:N (Documentos <-> Assuntos)                                       │
│  - Chaves de API Criptografadas com AES-256                                            │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Início Rápido com Docker

### 3.1. Pré-Requisitos
- **Docker Engine** 24.0+ e **Docker Compose** v2+
- Utilitário `curl`
- Navegador moderno com suporte a JavaScript

### 3.2. Instalação Automática com `install.sh`
Clone o repositório e execute o script de instalação:

```bash
git clone https://github.com/rivelinopatricio-ufg/exegese_ai.git
cd exegese_ai
chmod +x install.sh
./install.sh
```

O assistente interativo ou as flags de linha de comando permitem configurar:
1. **Domínio ou Host**: Nome de host ou FQDN (ex: `exegese.empresa.gov.br` ou `localhost`).
2. **Portas Públicas**: Portas HTTP e HTTPS personalizadas (padrão: 80 e 443; sugere 8080 e 8443 se estiverem ocupadas).
3. **E-mail Let's Encrypt**: Para notificações e emissão de certificados SSL válidos.
4. **Google OAuth2**: Chaves canônicas `Client ID` e `Client Secret`.
5. **E-mail do Administrador Inicial**: Recebe o perfil `ROLE_ADMIN` no primeiro login.
6. **Chaves de Provedores AI**: Gemini, OpenAI, Claude, etc.

Após a inicialização do SWAG e o healthcheck da aplicação, acesse:
- **Painel Principal**: `https://${SERVER_NAME}:${HTTPS_PORT}` (ou `http://${SERVER_NAME}:${HTTP_PORT}`)
- **Página de Login**: `https://${SERVER_NAME}:${HTTPS_PORT}/login`
- **Gestão de Usuários**: `https://${SERVER_NAME}:${HTTPS_PORT}/admin/users`
- **Gestão de Provedores AI**: `https://${SERVER_NAME}:${HTTPS_PORT}/admin/models`
- **Saúde e Diagnóstico**: `https://${SERVER_NAME}:${HTTPS_PORT}/actuator/health`

---

## 4. Comandos e Flags do Script `install.sh`

O instalador suporta execução tanto interativa quanto automatizada (CI/CD / Provisionamento) via argumentos de linha de comando:

| Flag / Parâmetro | Descrição | Padrão |
| :--- | :--- | :--- |
| `-h, --help` | Exibe a mensagem de ajuda e encerra. | - |
| `-H, --host <hostname>` | Nome de host ou FQDN (ex: `exegese.empresa.gov.br` ou `localhost`). | `localhost` |
| `--http-port <porta>` | Porta pública HTTP exposta pelo SWAG NGINX. | `80` |
| `--https-port <porta>` | Porta pública HTTPS exposta pelo SWAG NGINX. | `443` |
| `--email-ssl <email>` | E-mail para emissão e avisos do certificado Let's Encrypt. | - |
| `--google-client-id <id>` | Google OAuth2 Client ID (`spring.security.oauth2.client.registration.google.client-id`). | - |
| `--google-client-secret <sec>` | Google OAuth2 Client Secret (`spring.security.oauth2.client.registration.google.client-secret`). | - |
| `--initial-admin <email>` | E-mail do administrador inicial (recebe `ROLE_ADMIN`). | `admin@exegese.ai` |
| `--gemini-key <key>` | Chave de API do Google Gemini. | - |
| `--openai-key <key>` | Chave de API da OpenAI. | - |
| `--anthropic-key <key>` | Chave de API da Anthropic Claude. | - |
| `--no-ingest` | Inicia a plataforma sem disparar a ingestão inicial de documentos. | `false` |
| `--uninstall` | Para os contêineres, remove volumes persistentes e apaga o `.env`. | `false` |

### Exemplos de Uso:
```bash
# Execução interativa padrão
./install.sh

# Produção com domínio institucional e portas padrão
./install.sh -H exegese.empresa.gov.br --http-port 80 --https-port 443 --email-ssl admin@empresa.gov.br

# Servidor com portas alternativas (8080 HTTP / 8443 HTTPS) e credenciais Google
./install.sh -H exegese.empresa.gov.br --http-port 8080 --https-port 8443 \
  --google-client-id "xxxx.apps.googleusercontent.com" \
  --google-client-secret "GOCSPX-yyyy" \
  --initial-admin "admin@empresa.gov.br"

# Desinstalação completa e limpeza de dados
./install.sh --uninstall
```

---

## 5. Configuração do Google Cloud Console (OAuth2 / OIDC)

Para habilitar a autenticação com o Google Sign-In, registre uma credencial **ID do cliente OAuth 2.0** no [Google Cloud Console](https://console.cloud.google.com/apis/credentials):

1. **Tipo de Aplicativo**: Aplicativo Web (*Web application*).
2. **Origens JavaScript autorizadas**:
   - Produção: `https://${SERVER_NAME}:${HTTPS_PORT}` (ou `https://${SERVER_NAME}` na porta 443)
   - Desenvolvimento Local: `http://localhost:${HTTP_PORT}`
3. **URIs de redirecionamento autorizados**:
   - Produção: `https://${SERVER_NAME}:${HTTPS_PORT}/login/oauth2/code/google`
   - Desenvolvimento Local: `http://localhost:${HTTP_PORT}/login/oauth2/code/google`

O script `install.sh` calcula e exibe essa URL exata ao final da execução.

---

## 6. Execução em Desenvolvimento Local

Para executar o projeto diretamente na máquina host com Maven e Java 25 nativo:

### 6.1. Subir apenas o Banco de Dados (PostgreSQL + pgvector)
```bash
docker compose up -d postgres
```

### 6.2. Executar a Aplicação Spring Boot
```bash
mvn spring-boot:run
```

A aplicação subirá na porta `8080`.

---

## 7. Suíte de Testes Automatizados

O projeto conta com **41 testes automatizados** sob **Spring Boot 4.1.1** e **Java 25 Nativo** (`major version 69`), com 100% de taxa de aprovação:

```bash
mvn clean test
```

### Principais Suítes de Teste:
- `RagQualityEvaluationTest`: Avaliação do **Golden Dataset** com 20 perguntas oficiais do Manual do IRPF 2026 e controle de recusa estrita fora de escopo.
- `SecurityHardeningIntegrationTest`: Teste de limitação de taxa (HTTP 429 via Bucket4j), proteção contra prompt injection (HTTP 400) e validação de cabeçalhos de segurança (CSP, HSTS, X-Frame-Options).
- `SecurityIntegrationTest`: Testes de autenticação Google OAuth2/OIDC e autorização por papéis RBAC.
- `RagOrchestrationIntegrationTest`: Validação do fluxo completo de RAG, streaming SSE e formatação de citações canônicas.
- `MultiProviderModelIntegrationTest`: Teste de alternância entre os 6 provedores de IA e criptografia AES-256 de chaves de API.
- `DocumentIngestionIntegrationTest`: Testes de extração PDF, segmentação polimórfica e deduplicação de chunks por SHA-256.
- `HybridSearchIntegrationTest`: Teste de fusão de busca vetorial e textual via RRF.
- `AdminUserManagementIntegrationTest`: Testes de gestão administrativa de usuários e concessão granular de permissões.
- `SubjectAndDocumentCatalogIntegrationTest`: Testes de catálogo de assuntos e documentos.
- `ChatInterfaceIntegrationTest`: Testes dos endpoints web de chat e streaming SSE.
- `EntityPersistenceIntegrationTest`: Testes de integridade relacional JPA.
- `ExegeseAiApplicationTests`: Teste de inicialização do contexto Spring Boot 4.

---

## 8. Variáveis de Ambiente (`.env`)

| Variável | Padrão | Descrição |
| :--- | :--- | :--- |
| `SERVER_NAME` | `exegese-ai.sytes.net` | FQDN ou nome de host do servidor (para SSL e cabeçalhos NGINX). |
| `HTTP_PORT` | `80` | Porta pública HTTP exposta pelo contêiner SWAG. |
| `HTTPS_PORT` | `443` | Porta pública HTTPS exposta pelo contêiner SWAG. |
| `LETSENCRYPT_EMAIL` | `admin@exegese.ai` | E-mail para cadastro e alertas de expiração do Let's Encrypt. |
| `LETSENCRYPT_STAGING` | `false` | Se `true`, utiliza o ambiente de staging do Let's Encrypt (para testes). |
| `VALIDATION` | `http` | Método de validação ACME do Certbot no SWAG (`http`, `dns`, `duckdns`). |
| `POSTGRES_DB` | `exegese_db` | Nome do banco de dados relacional. |
| `POSTGRES_USER` | `exegese_user` | Usuário do banco PostgreSQL. |
| `POSTGRES_PASSWORD` | `exegese_password` | Senha do banco PostgreSQL. |
| `POSTGRES_PORT` | `5432` | Porta mapeada do PostgreSQL. |
| `PORT` | `8080` | Porta interna da aplicação web (isolada na rede Docker). |
| `EXEGESE_AES_SECRET` | *(Aleatório 32 chars)* | Chave mestra de criptografia simétrica AES-256. |
| `INITIAL_ADMIN_EMAIL` | `admin@exegese.ai` | E-mail que recebe privilégios de Administrador no 1º login. |
| `GOOGLE_CLIENT_ID` | - | Client ID OAuth2 configurado no Google Cloud Console. |
| `GOOGLE_CLIENT_SECRET` | - | Client Secret OAuth2 do Google Cloud Console. |
| `GEMINI_API_KEY` | - | Chave de API para o Google Gemini. |
| `OPENAI_API_KEY` | - | Chave de API para a OpenAI. |
| `ANTHROPIC_API_KEY` | - | Chave de API para o Anthropic Claude. |
| `NVIDIA_API_KEY` | - | Chave de API para NVIDIA Nemotron. |
| `DEEPSEEK_API_KEY` | - | Chave de API para DeepSeek. |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Endpoint da instância local do Ollama. |

---

## 9. Estratégias de Chunking Polimórfico

A plataforma aplica estratégias customizadas de segmentação dependendo da taxonomia e do formato dos documentos:

1. **`STRUCTURED_QA` (Perguntas e Respostas)**:
   - Identifica padrões de perguntas numeradas (ex: `Pergunta 001 — ...`).
   - Mantém cada pergunta e sua respectiva resposta em um chunk atômico independente, evitando corte arbitrário de raciocínio.
2. **`LEGAL_ARTICLES` (Artigos e Normas)**:
   - Segmenta leis, decretos e instruções normativas por Artigos (`Art. 1º`, `Art. 2º`, Parágrafos e Incisos).
   - Preserva o cabeçalho do artigo e referências cruzadas nos metadados.
3. **`GENERAL_CHUNK` (Textos e Manuais Gerais)**:
   - Segmentação por tamanho de janela (500 tokens) com sobreposição deslizante (*overlap* de 50 tokens) para continuidade semântica.

---

## 10. Licença e Autoria

Este projeto é software livre licenciado sob os termos da [Licença MIT](LICENSE).

**Autor e Mantenedor**:
- **Rivelino Patrício**
