# Exegese AI — Plataforma RAG de Rigor Exegético e Grounding Normativo

[![Java](https://img.shields.io/badge/Java-25%20Nativo%20%28Major%2069%29-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1%20GA-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.1-blue.svg)](https://spring.io/projects/spring-ai)
[![Reverse Proxy](https://img.shields.io/badge/Proxy-SWAG%20%28NGINX%20%2B%20Certbot%20%2B%20Fail2ban%29-success.svg)](https://docs.linuxserver.io/general/swag)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17%20%2B%20pgvector-blue.svg)](https://github.com/pgvector/pgvector)
[![Tests](https://img.shields.io/badge/Tests-181%20%28JUnit%20%2B%20Testcontainers%29-brightgreen.svg)](#7-suíte-de-testes-automatizados)
[![i18n](https://img.shields.io/badge/i18n-pt--BR%20%7C%20en--US%20%7C%20es--ES-blueviolet.svg)](#1-destaques-e-proposta-de-valor)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

> **Exegese AI** é uma plataforma corporativa e institucional de Recuperação Aumentada por Geração (**RAG — Retrieval-Augmented Generation**) projetada especificamente para cenários onde a precisão documental, o rigor exegético e a fundamentação normativa são requisitos intransigíveis.

---

## 1. Destaques e Proposta de Valor

- **Política de Tolerância Zero a Alucinações (*Zero Hallucination Policy*)**: A plataforma só responde com base em evidências recuperadas dos documentos anexados aos assuntos selecionados. Se a similaridade ponderada cair abaixo do limiar configurado (`0.65`) ou nenhuma fonte for localizada, o sistema emite prontamente a recusa canônica no idioma do usuário:
  - *Português*: *"Essa informação não consta nos documentos dos assuntos selecionados."*
  - *Inglês*: *"This information is not found in the documents of the selected subjects."*
  - *Espanhol*: *"Esta información no consta en los documentos de los temas seleccionados."*
- **Busca Híbrida de Alta Precisão (RRF)**: Combina indexação vetorial densa com índice HNSW (distância por cosseno) e busca lexical textual (*Full-Text Search* com `tsvector` e `tsquery` em português), fundindo os rankings através do algoritmo **Reciprocal Rank Fusion (RRF)** sem vetores nulos ou zerados (embeddings validados; sem chave Gemini a busca usa só texto completo):
  $$Score_{RRF} = \frac{1}{60 + rank_{vec}} + \frac{1}{60 + rank_{txt}}$$
- **Arquitetura Multi-Provedor Dinâmica (7 Ecossistemas de IA)**: Roteamento transparente e em tempo de execução via `LlmProviderRouter` entre 7 ecossistemas de ponta:
  - **Google Gemini** (`gemini-3.5-flash-lite` padrão)
  - **Anthropic Claude** (`claude-sonnet-5-5` padrão)
  - **OpenAI ChatGPT** (`gpt-5-mini` padrão)
  - **Cerebras Inference** (`gpt-oss-120b` — inferência de ultrabaixa latência em hardware Wafer-Scale Engine)
  - **NVIDIA Nemotron** (`nvidia/nemotron-4-340b-instruct`, `llama-3.1-nemotron-70b`)
  - **DeepSeek AI** (`deepseek-chat`, `deepseek-reasoner` / V3 / R1)
  - **Processamento Local (Ollama)** (`qwen2.5:7b`, `llama3.2`, `deepseek-r1`)
- **Internacionalização Multilíngue Completa (i18n)**:
  - Interface web totalmente traduzida em 3 idiomas: **Português (pt-BR)**, **Inglês (en)** e **Espanhol (es)**.
  - Alternância imediata via seletor de idiomas no cabeçalho ou parâmetro `?lang=`, persistida no cookie `EXEGESE_LOCALE`.
  - Prompt do sistema (`rag.specialist.system.prompt`) resolvido a cada pergunta no idioma de quem perguntou, sem estado global: a escolha de idioma de um usuário (ou de um visitante na tela de login) não afeta os demais.
- **Chat em Dois Passos (CSRF + SSE)**:
  - `POST /api/chat/messages` (com token CSRF) valida a sessão e a pergunta e devolve `{"streamUrl": ...}` com um ticket de uso único (2 min), vinculado ao usuário.
  - `GET /api/chat/stream/{streamId}` consome o ticket e transmite a resposta por SSE em uma virtual thread dedicada, sem transação aberta durante a geração do LLM.
  - Limites: 20 requisições/min por conta (ou por IP, quando anônimo) em `/api/**` e até 2 respostas simultâneas por usuário (`exegese.chat.max-concurrent-streams-per-user`). Fechar a aba cancela a geração no provedor.
  - A pergunta e os trechos recuperados vão ao modelo em seções delimitadas (`<user_question>`, `<official_context>`), tratadas pelo prompt do sistema estritamente como dados. Erros chegam ao cliente apenas como mensagem genérica com código de referência; o detalhe fica no log.
- **Gestão e Ingestão Dinâmica de Documentos (Upload Multipart)**:
  - Painel administrativo e operacional (`/admin/documents`) com upload direto de arquivos PDF de até 50MB.
  - Hashing criptográfico **SHA-256** (`file_hash_sha256`) garantindo deduplicação estrita e ingestão idempotente sem duplicação de vetores.
  - Associação N:N entre Documentos e Assuntos (`document_subject`).
  - Segmentação polimórfica configurável por tipo de conteúdo (`STRUCTURED_QA`, `LEGAL_ARTICLES`, `GENERAL_CHUNK`).
- **Proxy Reverso Hardened com Gestão Autônoma de Certificados (SWAG / NGINX)**:
  - Borda de segurança baseada na imagem `linuxserver/swag`, consolidando **NGINX**, **Certbot integrado**, **s6-overlay init system** e **Fail2ban**.
  - **Emissão no Startup**: O próprio proxy solicita o certificado Let's Encrypt na inicialização antes de expor os serviços.
  - **Renovação Automática via Cron**: O processo cron interno do contêiner verifica periodicamente (2x ao dia) e renova silenciosamente os certificados SSL/TLS com recarregamento suave (*reload*) do NGINX.
  - **Isolamento de Aplicação**: A porta 8080 do Spring Boot fica totalmente confinada na rede interna Docker (`exegese-net`).
  - **Otimização para SSE**: Rota `/api/chat/stream/{streamId}` com `proxy_buffering off;` e timeout de 3600s para streaming contínuo sem bufferização (a própria aplicação responde com `X-Accel-Buffering: no`).
  - **IP real do cliente**: o NGINX de borda **sobrescreve** `X-Forwarded-For` com `$remote_addr` (não concatena um valor enviado pelo cliente), e o Tomcat só aceita esse cabeçalho vindo dos proxies internos; o rate limit não pode ser burlado com um IP forjado.
  - **Portas e Host Customizáveis**: Permite expor portas alternativas (ex: 8080/8443) caso as portas padrão 80/443 estejam ocupadas no servidor host.
- **Criptografia Mestra AES-256**: Chaves de API podem ser configuradas no arquivo `.env` ou gerenciadas em tempo real via Painel Administrativo com criptografia simétrica AES-256 no banco de dados.
- **Configuração Customizada Externa (`app_config`)**: Suporte a injeção e sobrescrita de propriedades de `application.properties` através de arquivo externo `app_config`, mantendo credenciais locais e parâmetros específicos isolados do versionamento Git.
- **Migrações Versionadas do Banco (Flyway)**: o esquema é criado e evoluído exclusivamente pelas migrações `src/main/resources/db/migration/V*.sql` (Hibernate com `ddl-auto=none`). Bancos criados antes do Flyway (pelo antigo `init-schema.sql` ou pelo `ddl-auto=update`) recebem baseline na versão 1 e executam apenas as migrações idempotentes V2+ na primeira inicialização.
- **Autenticação Federada Google OAuth2 & RBAC**: Controle granular de acesso baseado em papéis (`ROLE_ADMIN`, `ROLE_OPERATOR`, `ROLE_USER`) com permissões específicas por assunto e bootstrap automático do primeiro administrador via `INITIAL_ADMIN_EMAIL`.
- **Interface Conversacional Reativa & Acessível**: Chat em tempo real via Server-Sent Events (SSE) com digitação suave e preservação de fronteiras de tokens, chips de citações canônicas clicáveis, modais com fundamentação jurídica completa e alternador de tema Claro / Escuro em conformidade com as diretrizes **WCAG 2.1 AA**.

---

## 2. Arquitetura da Solução

```
                              CLIENTES WEB / NAVEGADORES
                                         │
                                         ▼ Portas Customizadas (${HTTP_PORT} / ${HTTPS_PORT})
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        PROXY REVERSO HARDENED (SWAG)                                   │
│                        (Imagem: linuxserver/swag:5.8.0)                                │
│                         Domínio: ${SERVER_NAME} / ${URL}                               │
│                                                                                        │
│  - Gestão TLS/SSL Autônoma: Solicitação no Startup + Renovação Automática via Cron    │
│  - Proteção Ativa contra Intrusão: Fail2ban (cap_add: NET_ADMIN)                       │
│  - Ocultação de Assinatura: server_tokens off                                          │
│  - Otimização SSE: proxy_buffering off; proxy_read_timeout 3600s                       │
│  - Limites de Payload: 50MB (upload multipart de PDF) / 10MB (geral)                   │
│  - Cabeçalhos: X-Forwarded-For sobrescrito com o IP real, X-Forwarded-Proto/Port       │
│  - Volume Persistente Único: proxy_config:/config                                      │
└───────────────────────────────────────┬────────────────────────────────────────────────┘
                                        │ Rede Interna Docker (exegese-net)
                                        │ Porta 8080 (ISOLADA)
                                        ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        APLICAÇÃO EXEGESE AI (SPRING BOOT 4.1.1)                        │
│        Runtime: Java 25 (JRE, usuário 10001, rootfs somente leitura, perfil prod)      │
│                                                                                        │
│  - Spring AI 2.0.1 (RAG, ChatClient, RRF Search, Multi-Provider Router)                │
│  - 7 Provedores AI: Gemini, Claude, OpenAI, Cerebras, Nemotron, DeepSeek, Ollama       │
│  - i18n Resolver & Locale Context: pt-BR, en-US, es-ES (UI + Dynamic System Prompts)   │
│  - RateLimitFilter (Bucket4j - Limitação por conta ou IP real)                         │
│  - InputSanitizationFilter (Mitigação de Prompt Injection)                             │
│  - Gestão Multipart: Upload de documentos até 50MB e ingestão SHA-256 idempotente       │
│  - Google OAuth2 Client (spring.security.oauth2.client.registration.google.*)          │
│  - Actuator (/actuator/health)                                                         │
└───────────────────────────────────────┬────────────────────────────────────────────────┘
                                        │ Porta 5432 (Interna)
                                        ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                               POSTGRESQL 17 + PGVECTOR                                 │
│                                                                                        │
│  - Chunks Vetorizados (HNSW Cosine Distance) com proteção de vetor nulo                │
│  - Busca Textual em Português (GIN tsvector / tsquery)                                 │
│  - Particionamento N:N (Documentos <-> Assuntos)                                       │
│  - Chaves de API Criptografadas com AES-256                                            │
│  - Sessões e Histórico de Chat Persistentes                                            │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Início Rápido com Docker

### 3.1. Pré-Requisitos
- **Docker Engine** 24.0+ e **Docker Compose** v2+
- Utilitário `curl`
- Portas de entrada liberadas no firewall (`ufw` no Ubuntu)
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
6. **Chaves de Provedores AI**: Gemini, OpenAI, Claude, Cerebras, etc.

Após a inicialização do SWAG e o healthcheck da aplicação, acesse:
- **Painel Principal / Chat**: `https://${SERVER_NAME}:${HTTPS_PORT}` (ou `http://${SERVER_NAME}:${HTTP_PORT}`)
- **Página de Login**: `https://${SERVER_NAME}:${HTTPS_PORT}/login`
- **Gestão de Documentos (Upload)**: `https://${SERVER_NAME}:${HTTPS_PORT}/admin/documents`
- **Gestão de Assuntos**: `https://${SERVER_NAME}:${HTTPS_PORT}/admin/subjects`
- **Gestão de Usuários**: `https://${SERVER_NAME}:${HTTPS_PORT}/admin/users`
- **Gestão de Provedores AI**: `https://${SERVER_NAME}:${HTTPS_PORT}/admin/models`
- **Saúde e Diagnóstico**: `https://${SERVER_NAME}:${HTTPS_PORT}/actuator/health`

### 3.3. Configuração de Firewall no Host (Padrão Ubuntu - UFW)

Se o servidor utiliza **Ubuntu** com o firewall **UFW** (*Uncomplicated Firewall*) ativo, é obrigatório autorizar o tráfego de entrada nas portas HTTP e HTTPS configuradas para viabilizar o acesso web e a validação ACME de certificados Let's Encrypt:

> [!IMPORTANT]
> **Aviso de Firewall**: O instalador `install.sh` detecta automaticamente o UFW e aplica as liberações das portas informadas. Caso o servidor esteja hospedado em nuvem (Oracle Cloud OCI, AWS, GCP, Azure), lembre-se de autorizar as mesmas portas nas **Listas de Segurança / Security Groups / Ingress Rules** do painel da sua nuvem.

#### Comandos de Liberação no Ubuntu (UFW):
```bash
# 1. Liberar porta HTTP (80 ou personalizada)
sudo ufw allow 80/tcp comment 'Exegese AI HTTP'

# 2. Liberar porta HTTPS (443 ou personalizada, ex: 446 / 8443)
sudo ufw allow 443/tcp comment 'Exegese AI HTTPS'

# 3. (Recomendado) Garantir que a porta SSH continue liberada
sudo ufw allow OpenSSH

# 4. Habilitar o firewall e verificar status das regras
sudo ufw enable
sudo ufw status verbose
```

#### Para portas personalizadas (exemplo: HTTP 8080 e HTTPS 446):
```bash
sudo ufw allow 8080/tcp comment 'Exegese AI HTTP Alt'
sudo ufw allow 446/tcp comment 'Exegese AI HTTPS Alt'
sudo ufw status verbose
```

#### Portas publicadas pelo `docker-compose.yml`
Só o proxy SWAG publica portas no host: `HTTP_PORT`/`HTTPS_PORT` (padrão **80/443**). A aplicação (8080), o PostgreSQL (5432)
e o Ollama opcional (11434) ficam apenas na rede interna `exegese-net`, sem `ports:`.

> [!WARNING]
> Portas publicadas pelo Docker **ignoram o UFW** (as regras iptables do Docker são avaliadas antes). Não adicione `ports:`
> ao `postgres` nem ao `ollama`; se precisar acessar o banco, use `docker compose exec postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"`
> ou um túnel SSH. Confira com `sudo ss -tlnp` no host (e um `nmap` externo) que só 80/443 estão abertas, e feche 5432 e 11434
> também no *security group* / lista de segurança da nuvem.

### 3.4. Ollama Local (Opcional)
O Ollama não tem autenticação, por isso **não publica porta**: a aplicação o acessa como `http://ollama:11434` na rede interna.
Suba-o junto com os demais serviços usando um dos overrides:
```bash
# CPU
docker compose -f docker-compose.yml -f docker-compose.override.ai.yml up -d
# GPU NVIDIA (requer o NVIDIA Container Toolkit)
docker compose -f docker-compose.yml -f docker-compose.override.ai-with-gpu.yml up -d
```
O `ollama_init.sh` aguarda o servidor e baixa `OLLAMA_CHAT_MODEL` (padrão `llama3.2`). Depois, selecione o provedor
**Ollama Local** em `/admin/models`. Use sempre os mesmos `-f` nos comandos seguintes (`ps`, `logs`, `down`).

### 3.5. Hardening dos Contêineres
- **Imagens fixadas**: `pgvector/pgvector:0.8.7-pg17`, `linuxserver/swag:5.8.0`, `ollama/ollama:0.40.1`, `eclipse-temurin:25-jdk-noble`/`25-jre-noble`
  (o Dependabot propõe atualizações semanais dos Dockerfiles).
- **Aplicação**: imagem em camadas do Spring Boot (dependências separadas do código), usuário não-root `10001`, `ENTRYPOINT` sem shell
  e opções da JVM em `JAVA_TOOL_OPTIONS` (ZGC, `-XX:MaxRAMPercentage=75`, `-XX:+ExitOnOutOfMemoryError`). No compose: `read_only: true`
  com tmpfs em `/tmp` (256 MB), `cap_drop: [ALL]`, `no-new-privileges`, `mem_limit` (`APP_MEM_LIMIT`, padrão 2g), `cpus` (`APP_CPUS`:
  o `install.sh` grava o número de CPUs do host limitado a 2; vazio ou `0` = sem limite) e `pids_limit`. Só os volumes `upload_data` (`/app/uploads`) e `storage_data` (`/app/storage`) são graváveis.
- **Proxy**: `cap_add: NET_ADMIN` existe apenas para o **fail2ban** do SWAG (bloqueio por iptables); remova-o se não usar o fail2ban.
  A configuração do site é montada como `/config/nginx/site-confs/default.conf` (o SWAG só carrega `*.conf`): o `install.sh` gera
  `docker/proxy/config/site.conf` (não versionado) com as portas configuradas e grava `PROXY_SITE_CONFIG=site.conf` no `.env`; sem essa
  variável, é montado o modelo versionado `docker/proxy/config/default` (portas 80/443). O instalador nunca altera o arquivo versionado.
- **Keystore de desenvolvimento**: `src/main/resources/cert.p12` é só para rodar no Eclipse; fica fora do JAR (`maven-jar-plugin`) e do
  contexto Docker (`.dockerignore`). Em produção o TLS termina no SWAG.

### 3.6. Atualização de Instalações Existentes
Instalações feitas antes desta versão usavam uma chave AES fixa no código, uma senha pública do PostgreSQL e publicavam as portas
5432 (PostgreSQL) e 11434 (Ollama) em todas as interfaces. **Considere expostos os segredos dessas instalações** e siga, em ordem:

1. **Rotacione as credenciais externas**: gere novas chaves de API de todos os provedores de LLM usados (Gemini, OpenAI, Anthropic,
   Cerebras, NVIDIA, DeepSeek) e um novo *client secret* do Google OAuth2 no Google Cloud Console. Revogue as antigas.
2. **Feche as portas 5432 e 11434** no firewall do host e no *security group* / lista de segurança da nuvem. O novo `docker-compose.yml`
   já não as publica, mas a regra de nuvem antiga deve ser removida. Confira com `sudo ss -tlnp` e um `nmap` externo: só 80/443.
3. **Atualize o código e reaproveite o `.env`**:
   ```bash
   git checkout -- docker/proxy/config/default && git pull && ./install.sh
   ```
   O `git checkout` descarta apenas a cópia de `docker/proxy/config/default` que o instalador antigo reescrevia a cada execução (sem ele,
   o `git pull` aborta com *"Your local changes ... would be overwritten"*); o novo `install.sh` gera o site em
   `docker/proxy/config/site.conf`, fora do controle de versão, com as portas configuradas. Mantendo o `.env` atual ou reconfigurando-o, o instalador **preserva**
   `POSTGRES_USER`, `POSTGRES_PASSWORD` e `EXEGESE_AES_SECRET` já gravados (o volume `pgdata` não aceita novas credenciais),
   completa o que faltar e exige `INITIAL_ADMIN_EMAIL` (o antigo padrão `admin@exegese.ai` é recusado).
4. **Defina `EXEGESE_AES_SECRET`** (Base64 de 32 bytes: `openssl rand -base64 32`) se o `.env` ainda não tiver um valor válido. A aplicação
   não sobe sem ela. Na inicialização, as chaves de API cifradas com a antiga chave fixa são **migradas automaticamente** para a nova
   chave (formato `v1:`); o log lista apenas os provedores migrados. Guarde essa chave com backup: sem ela, as chaves de API salvas no
   banco ficam ilegíveis.
5. **Troque a senha do PostgreSQL** (o volume já inicializado mantém a senha antiga) e atualize o `.env`:
   ```bash
   NEW_PASS=$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 32)
   docker compose exec postgres psql -U "$POSTGRES_USER" -d "${POSTGRES_DB:-exegese_db}" \
     -c "ALTER USER \"$POSTGRES_USER\" WITH PASSWORD '$NEW_PASS';"
   sed -i "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=$NEW_PASS/" .env
   docker compose up -d    # recria a aplicação com a nova senha
   ```
   (carregue as variáveis antes com `set -a; . ./.env; set +a`).
6. **Cadastre as novas chaves de API** em `/admin/models` (ou no `.env`) e use **Ping** para validá-las.
7. **Reindexe os embeddings**: com a `GEMINI_API_KEY` configurada, abra `/admin/documents` e clique em **Reindexar embeddings**. Os documentos
   indexados antes desta versão têm vetores zerados ou nulos; a reindexação recalcula os vetores (`gemini-embedding-001`, 768 dimensões)
   a partir do texto já gravado, sem reenviar os PDFs.

O esquema do banco é migrado automaticamente pelo Flyway na primeira inicialização (baseline na versão 1 e migrações V2+ idempotentes).
O relatório completo da auditoria está em [`docs/SEGURANCA_AUDITORIA_2026-10.md`](docs/SEGURANCA_AUDITORIA_2026-10.md).

---

## 4. Comandos e Flags do Script `install.sh`

O instalador suporta execução tanto interativa quanto automatizada (CI/CD / Provisionamento) via argumentos de linha de comando:

| Flag / Parâmetro | Descrição | Padrão |
| :--- | :--- | :--- |
| `-h, --help` | Exibe a mensagem de ajuda e encerra. | - |
| `-H, --host <hostname>` | Nome de host ou FQDN (ex: `exegese.empresa.gov.br` ou `localhost`). | `exegese-ai.sytes.net` |
| `--http-port <porta>` | Porta pública HTTP exposta pelo SWAG NGINX. | `80` |
| `--https-port <porta>` | Porta pública HTTPS exposta pelo SWAG NGINX. | `443` |
| `--email-ssl <email>` | E-mail para emissão e avisos do certificado Let's Encrypt. | - |
| `--google-client-id <id>` | Google OAuth2 Client ID (`spring.security.oauth2.client.registration.google.client-id`). | - |
| `--google-client-secret <sec>` | Google OAuth2 Client Secret (`spring.security.oauth2.client.registration.google.client-secret`). | - |
| `--initial-admin <email>` | E-mail Google do administrador inicial: recebe `ROLE_ADMIN` no login enquanto ainda não existir nenhum administrador. **Obrigatório** (o instalador pergunta se faltar). | - |
| `--allowed-email-domains <lista>` | Domínios de e-mail Google autorizados a entrar, separados por vírgula. Vazio = qualquer conta Google com e-mail verificado. | - |
| `--gemini-key <key>` | Chave de API do Google Gemini. | - |
| `--openai-key <key>` | Chave de API da OpenAI. | - |
| `--anthropic-key <key>` | Chave de API da Anthropic Claude. | - |
| `--cerebras-key <key>` | Chave de API para o Cerebras Inference (`cerebras.api-key`). | - |
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

Para executar o projeto diretamente na máquina host com Java 25 e o Maven Wrapper (`./mvnw`, que baixa o Maven 3.9.11 e confere o SHA-256):

### 6.1. Subir apenas o Banco de Dados (PostgreSQL + pgvector)
O `postgres` do `docker-compose.yml` principal não publica a porta 5432. Em desenvolvimento, use o compose avulso do banco,
que publica a porta **somente em `127.0.0.1`**:
```bash
cd docker/postgres
printf 'POSTGRES_USER=exegese_dev\nPOSTGRES_PASSWORD=%s\n' "$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 32)" > .env
docker compose up -d --build
```

### 6.2. Perfis e Execução da Aplicação Spring Boot
- **`prod`**: perfil padrão no Docker (`SPRING_PROFILES_ACTIVE=prod`, definido no `Dockerfile` e no `docker-compose.yml`;
  arquivo `application-prod.properties`). O `application.properties` já tem padrões seguros para produção (cache do Thymeleaf,
  logs em INFO, cookie de sessão `Secure`/`HttpOnly`/`SameSite=Lax`, esquema só via Flyway).
- **`dev`**: perfil local, com um `src/main/resources/application-dev.properties` **não versionado** (crie o seu). Exemplo para o Eclipse,
  usando o keystore de desenvolvimento `src/main/resources/cert.p12` (só para dev: fica fora do JAR e da imagem Docker):
  ```properties
  server.ssl.enabled=true
  server.ssl.key-store=classpath:cert.p12
  server.ssl.key-store-type=PKCS12
  server.ssl.key-store-password=<senha do seu cert.p12>
  spring.thymeleaf.cache=false
  logging.level.br.org.rivelino.exegese_ai=DEBUG
  # Só se servir http://localhost sem TLS:
  # server.servlet.session.cookie.secure=false
  ```

Defina as variáveis obrigatórias no ambiente (ou na configuração de execução do Eclipse) e rode:
```bash
export POSTGRES_USER=exegese_dev POSTGRES_PASSWORD='<senha do docker/postgres/.env>'
export EXEGESE_AES_SECRET="$(openssl rand -base64 32)"   # guarde-a: ela cifra as chaves de API salvas no banco
export INITIAL_ADMIN_EMAIL=voce@seu-dominio.com.br GOOGLE_CLIENT_ID=... GOOGLE_CLIENT_SECRET=...
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
```

A aplicação subirá na porta `8080`.

### 6.3. Front-end (Tailwind CSS e scripts)
O CSS do Tailwind é gerado localmente (sem CDN) e o arquivo `src/main/resources/static/css/tailwind.css` fica versionado,
então o build Maven não precisa de Node.js. Os scripts ficam em `src/main/resources/static/js/`, sem `<script>` inline nem
atributos `onclick`, pois a Content-Security-Policy não permite `'unsafe-inline'`. Ao adicionar classes nos templates ou nos
scripts, gere o CSS de novo e faça commit do resultado:
```bash
cd src/main/frontend && npm ci && npm run build:css
```

---

## 7. Suíte de Testes Automatizados

A suíte roda sob **Spring Boot 4.1.1** e **Java 25** com H2 (perfil `test`) e, quando há Docker disponível, com
**Testcontainers** (`pgvector/pgvector:0.8.7-pg17`) para Flyway, pgvector e busca textual (FTS). Nenhum teste chama APIs externas:
o cliente de LLM e o modelo de embeddings são substituídos por dublês determinísticos.

```bash
./mvnw -B verify
```

Os testes Testcontainers (`FlywayMigrationContainerTest`, `PgVectorHybridSearchContainerTest`) usam
`@Testcontainers(disabledWithoutDocker = true)`: sem daemon Docker eles são ignorados; no CI eles rodam.

### Integração Contínua (GitHub Actions)
O workflow `.github/workflows/ci.yml` roda em todo push e pull request, com `permissions: contents: read`:
- **build**: `./mvnw -B verify` no JDK 25 (Temurin), incluindo os testes Testcontainers.
- **frontend**: `npm ci && npm run build:css` em `src/main/frontend` e falha se o `tailwind.css` versionado estiver desatualizado.
- **docker**: `docker compose config` dos arquivos compose (com variáveis fictícias), build da imagem da aplicação (sem push),
  verificação de usuário não-root e ausência de keystores na imagem, e build da imagem do proxy.

### Suítes de Teste Implementadas:
- `AppConfigIntegrationTest` (2 testes): Validação do carregamento de propriedades customizadas e sobrescrita de parâmetros a partir do arquivo externo `app_config`.
- `RagQualityEvaluationTest` (2 testes): Avaliação do **Golden Dataset** com 20 perguntas oficiais do Manual do IRPF 2026 e controle de recusa estrita fora de escopo.
- `SecurityHardeningIntegrationTest` (4 testes): Teste de limitação de taxa (HTTP 429 via Bucket4j), proteção contra prompt injection (HTTP 400) e validação de cabeçalhos de segurança (CSP, HSTS, X-Frame-Options).
- `SecurityIntegrationTest` (6 testes): Testes de autenticação Google OAuth2/OIDC e autorização por papéis RBAC.
- `RagOrchestrationIntegrationTest` (5 testes): Validação do fluxo completo de RAG, streaming SSE, prompts dinâmicos e formatação de citações canônicas.
- `MultiProviderModelIntegrationTest` (6 testes): Teste de alternância entre os **7 provedores de IA** (incluindo Cerebras Inference) e criptografia AES-256 de chaves de API.
- `I18nWebIntegrationTest` (4 testes): Validação de internacionalização web (PT-BR, EN, ES), persistência de cookies (`EXEGESE_LOCALE`) e adaptação dos prompts de sistema por locale.
- `DocumentIngestionIntegrationTest` (4 testes): Testes de extração PDF, segmentação polimórfica e deduplicação de chunks por SHA-256.
- `HybridSearchIntegrationTest` (2 testes): Teste de fusão de busca vetorial e textual via RRF com guarda contra vetores nulos.
- `SubjectAndDocumentCatalogIntegrationTest` (6 testes): Testes de catálogo de assuntos, vinculação N:N e upload/ingestão multipart de PDFs.
- `ChatInterfaceIntegrationTest` (9 testes): Testes dos endpoints web de chat, sessões e streaming SSE.
- `AdminUserManagementIntegrationTest` (4 testes): Testes de gestão administrativa de usuários e concessão granular de permissões por assunto.
- `EntityPersistenceIntegrationTest` (4 testes): Testes de integridade relacional JPA e persistência.
- `ExegeseAiApplicationTests` (1 teste): Teste de inicialização do contexto Spring Boot 4.

---

## 8. Variáveis de Ambiente (`.env`) e Configuração Externa

| Variável | Padrão | Descrição |
| :--- | :--- | :--- |
| `SERVER_NAME` | `exegese-ai.sytes.net` | FQDN ou nome de host do servidor (para SSL e cabeçalhos NGINX). |
| `HTTP_PORT` | `80` | Porta pública HTTP exposta pelo contêiner SWAG. |
| `HTTPS_PORT` | `443` | Porta pública HTTPS exposta pelo contêiner SWAG. |
| `LETSENCRYPT_EMAIL` | - | E-mail para cadastro e alertas de expiração do Let's Encrypt (o `install.sh` sugere `admin@<host>`). Vazio = o SWAG registra a conta sem e-mail. |
| `LETSENCRYPT_STAGING` | `false` | Se `true`, utiliza o ambiente de staging do Let's Encrypt (para testes). |
| `VALIDATION` | `http` | Método de validação ACME do Certbot no SWAG (`http`, `dns`, `duckdns`). |
| `POSTGRES_DB` | `exegese_db` | Nome do banco de dados relacional. |
| `POSTGRES_USER` | *(gerado: `exegese_<8 hex>`)* | Usuário do banco PostgreSQL. **Obrigatório**; gerado pelo `install.sh` e preservado na reconfiguração. |
| `POSTGRES_PASSWORD` | *(gerada: 32 caracteres alfanuméricos)* | Senha do banco PostgreSQL. **Obrigatória**; gerada pelo `install.sh` e preservada na reconfiguração. O banco não é publicado no host (só a rede interna `exegese-net`). |
| `PORT` | `8080` | Porta interna da aplicação web (isolada na rede Docker). |
| `EXEGESE_AES_SECRET` | *(gerada: `openssl rand -base64 32`)* | Chave mestra AES-256-GCM das chaves de API gravadas no banco: Base64 de exatamente 32 bytes. **Obrigatória** (a aplicação não sobe sem ela); mantenha-a estável e com backup. Para rodar no Eclipse/IDE, defina-a como variável de ambiente. |
| `INITIAL_ADMIN_EMAIL` | - | **Obrigatório na instalação** (o `install.sh` exige e recusa o antigo `admin@exegese.ai`). E-mail Google promovido a Administrador no login **somente enquanto não existir nenhum `ROLE_ADMIN`** e só com `email_verified=true`. Sem valor padrão: se ficar vazio, a aplicação registra um aviso e ninguém é promovido automaticamente. |
| `ALLOWED_EMAIL_DOMAINS` | - | Domínios de e-mail Google autorizados a entrar (`exegese.security.allowed-email-domains`), separados por vírgula. Vazio = qualquer conta Google com e-mail verificado. Contas desativadas e e-mails não verificados são sempre recusados. |
| `REQUIRE_HOSTED_DOMAIN` | `true` | Com `ALLOWED_EMAIL_DOMAINS` definido, exige que a conta Google seja gerenciada pelo próprio domínio no Google Workspace (claim `hd` igual ao domínio do e-mail), recusando contas Google pessoais que mantêm um endereço verificado da organização (`exegese.security.require-hosted-domain`). Use `false` apenas se o e-mail do domínio não estiver no Google Workspace. Independentemente disso, cada conta local fica vinculada ao identificador Google (`sub`) no primeiro login: outro `sub` com o mesmo e-mail é recusado (`account_identity_mismatch`). |
| `GOOGLE_CLIENT_ID` | - | Client ID OAuth2 configurado no Google Cloud Console. |
| `GOOGLE_CLIENT_SECRET` | - | Client Secret OAuth2 do Google Cloud Console. |
| `GEMINI_API_KEY` | - | Chave de API para o Google Gemini: chat e embeddings semânticos (`gemini-embedding-001`, 768 dimensões). Sem ela, a busca usa apenas texto completo e a ingestão de documentos falha. Após configurá-la, use **Reindexar embeddings** em `/admin/documents` para gerar os vetores dos documentos já indexados. |
| `LLM_ALLOWED_HOSTS` | - | Hosts extras aceitos como URL base dos provedores de LLM (`exegese.llm.allowed-hosts`), separados por vírgula: `host` (todos os provedores) ou `PROVEDOR=host`. Por padrão, só os hosts oficiais (https); o Ollama local também aceita http para o host de `OLLAMA_BASE_URL`. |
| `OPENAI_API_KEY` | - | Chave de API para a OpenAI. |
| `ANTHROPIC_API_KEY` | - | Chave de API para o Anthropic Claude. |
| `CEREBRAS_API_KEY` | - | Chave de API para o Cerebras Inference. |
| `NVIDIA_API_KEY` | - | Chave de API para NVIDIA Nemotron. |
| `DEEPSEEK_API_KEY` | - | Chave de API para DeepSeek AI. |
| `OLLAMA_BASE_URL` | `http://ollama:11434` | Endpoint do Ollama local (overrides `docker-compose.override.ai*.yml`), acessível só pela rede interna. |
| `OLLAMA_CHAT_MODEL` | `llama3.2` | Modelo de chat baixado pelo `ollama_init.sh` e usado como padrão do provedor Ollama Local. |
| `SPRING_PROFILES_ACTIVE` | `prod` (Docker) | Perfil Spring. No Docker é sempre `prod`; em desenvolvimento use `dev` (ver seção 6.2). |
| `APP_MEM_LIMIT` | `2g` | Limite de memória do contêiner `app` (`mem_limit`). O heap da JVM usa 75% dele (`-XX:MaxRAMPercentage=75`) e o tmpfs `/tmp` (256 MB) também conta nesse limite. |
| `APP_CPUS` | *(sem limite; o `install.sh` grava o nº de CPUs do host, no máximo 2)* | Limite de CPUs do contêiner `app` (`cpus`). Nunca acima do número de CPUs do host: o Docker recusa criar o contêiner. Vazio ou `0` = sem limite. |
| `PROXY_SITE_CONFIG` | `default` | Arquivo em `docker/proxy/config/` montado como site do SWAG. O `install.sh` grava `site.conf` (gerado com as portas configuradas, não versionado). |
| `UPLOAD_DIR` | `./uploads` | Diretório onde os PDFs originais enviados são guardados como `<sha256>.pdf` (no Docker: volume `upload_data` em `/app/uploads`). A indexação roda em segundo plano e o catálogo mostra o status (`PROCESSING`, `INDEXED`, `FAILED`). |

Propriedades de segurança relacionadas: `exegese.security.allowed-email-domains` (variável `ALLOWED_EMAIL_DOMAINS`),
`exegese.security.require-hosted-domain` (variável `REQUIRE_HOSTED_DOMAIN`),
`exegese.security.crypto-key` (variável `EXEGESE_AES_SECRET`) e `exegese.llm.allowed-hosts` (variável `LLM_ALLOWED_HOSTS`).

### 8.1. Arquivo de Configuração Customizada (`app_config`)

Além das variáveis de ambiente (`.env`), a aplicação suporta nativamente o arquivo externo `app_config` ou `app_config.properties` localizado no diretório raiz da aplicação ou em `./config/`:
- **Sobrescrita Automática**: Quaisquer propriedades definidas neste arquivo sobrescrevem as chaves padrão do `application.properties`.
- **Formato**: Formato padrão Java Properties (`chave=valor`).
- **Segurança e Versionamento**: s padrãos `app_config*` consta no `.gitignore` e `.dockerignore`, garantindo que parâmetros e credenciais locais jamais sejam versionados no Git ou empacotados em contêineres Docker.

---

## 9. Estratégias de Chunking Polimórfico e Pipeline de Ingestão

A plataforma aplica estratégias customizadas de segmentação dependendo da taxonomia e do formato dos documentos:

1. **`STRUCTURED_QA` (Perguntas e Respostas)**:
   - Identifica padrões de perguntas numeradas (ex: `Pergunta 001 — ...`).
   - Mantém cada pergunta e sua respectiva resposta em um chunk atômico independente, evitando corte arbitrário de raciocínio.
2. **`LEGAL_ARTICLES` (Artigos e Normas)**:
   - Segmenta leis, decretos e instruções normativas por Artigos (`Art. 1º`, `Art. 2º`, Parágrafos e Incisos).
   - Preserva o cabeçalho do artigo e referências cruzadas nos metadados.
3. **`GENERAL_CHUNK` (Textos e Manuais Gerais)**:
   - Segmentação por tamanho de janela (500 tokens) com sobreposição deslizante (*overlap* de 50 tokens) para continuidade semântica.

### Pipeline de Upload e Deduplicação:
```
[Arquivo PDF Upload] 
         │
         ▼
[Cálculo SHA-256] ───(Existe no Banco?)───► [Sim] ──► Reutiliza Registro (Sem re-indexar)
         │ [Não]
         ▼
[Armazenamento em Disco (uploads/)]
         │
         ▼
[Extração de Texto Estruturado]
         │
         ▼
[Segmentação Polimórfica (QA / Artigos / Geral)]
         │
         ▼
[Geração de Embeddings Vetoriais (gemini-embedding-001, 768 dimensões)]
         │
         ▼
[Indexação PostgreSQL + pgvector (HNSW) e Textual (GIN)]
```

---

## 10. Licença e Autoria

Este projeto é software livre licenciado sob os termos da [Licença MIT](LICENSE).

**Autor e Mantenedor**:
- **Rivelino Patrício**
