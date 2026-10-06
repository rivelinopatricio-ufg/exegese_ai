# Exegese AI — Plataforma RAG de Rigor Exegético e Grounding Normativo

[![Java](https://img.shields.io/badge/Java-25%20%28Target%2021%29-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.2%20LTS-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0.0--M5-blue.svg)](https://spring.io/projects/spring-ai)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17%20%2B%20pgvector-blue.svg)](https://github.com/pgvector/pgvector)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

> **Exegese AI** é uma plataforma corporativa e institucional de Recuperação Aumentada por Geração (**RAG — Retrieval-Augmented Generation**) projetada especificamente para cenários onde a precisão documental, o rigor exegético e a fundamentação normativa são requisitos intransigíveis.

---

## 1. Destaques e Proposta de Valor

- **Política de Tolerância Zero a Alucinações (*Zero Hallucination Policy*)**: A plataforma só responde com base em evidências recuperadas dos documentos anexados aos assuntos selecionados. Se a similaridade ponderada cair abaixo do limiar (0.65) ou nenhuma fonte for localizada, o sistema emite prontamente a recusa canônica:
  > *"Essa informação não consta nos documentos dos assuntos selecionados."*
- **Busca Híbrida de Alta Precisão (RRF)**: Combina indexação vetorial densa com índice HNSW (distância por cosseno) e busca lexical textual (*Full-Text Search* com `tsvector` e `tsquery` em português), fundindo os rankings através do algoritmo **Reciprocal Rank Fusion (RRF)**:
  $$Score_{RRF} = \frac{1}{60 + rank_{vec}} + \frac{1}{60 + rank_{txt}}$$
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
                    ┌──────────────────────────────────────────────┐
                    │               Navegador Web                  │
                    │   Thymeleaf + Tailwind CSS + HTMX + SSE      │
                    └──────────────────────┬───────────────────────┘
                                           │ HTTPS / SSE
                                           ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│                             Exegese AI Application                               │
│                                                                                  │
│  ┌───────────────────────┐  ┌────────────────────────┐  ┌─────────────────────┐ │
│  │   Security Pipeline   │  │   Input Sanitization   │  │  Rate Limiting      │ │
│  │ Google OAuth2 / OIDC  │  │ Anti-Prompt Injection   │  │ Bucket4j (20 r/min) │ │
│  └───────────────────────┘  └────────────────────────┘  └─────────────────────┘ │
│                                                                                  │
│  ┌─────────────────────────────────────────────────────────────────────────────┐ │
│  │                         RAG Orchestration Core                              │ │
│  │  Query Rewriting ──► Hybrid Search (RRF) ──► Anti-Hallucination Guard       │ │
│  └──────────────────────────────────────┬──────────────────────────────────────┘ │
│                                         │                                        │
│  ┌──────────────────────────────────────▼──────────────────────────────────────┐ │
│  │                     Dynamic Multi-Provider AI Router                        │ │
│  │   [Gemini]   [Claude]   [OpenAI]   [Nemotron]   [DeepSeek]   [Ollama Local] │ │
│  └─────────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────┬────────────────────────────────────────┘
                                          │
                                          ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│                         PostgreSQL 17 + pgvector                                 │
│                                                                                  │
│  - Chunks Vetorizados (HNSW Cosine Distance)                                     │
│  - Busca Textual em Português (GIN tsvector)                                     │
│  - Particionamento N:N (Documentos <-> Assuntos)                                 │
│  - Chaves de API Criptografadas com AES-256                                      │
└──────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Início Rápido com Docker

### 3.1. Pré-Requisitos
- **Docker Engine** 24.0+ e **Docker Compose** v2+
- Utilitário `curl`
- Navegador moderno com suporte a JavaScript

### 3.2. Instalação Automática (Recomendada)
Clone o repositório e execute o script de instalação interativo:

```bash
git clone https://github.com/rivelinopatricio-ufg/exegese_ai.git
cd exegese_ai
chmod +x install.sh
./install.sh
```

O assistente solicitará:
1. Credenciais do **Google OAuth2** (`Client ID` e `Client Secret`).
2. E-mail do Administrador Inicial (recebe `ROLE_ADMIN` no 1º login).
3. Chaves de API dos provedores de IA desejados (ex: Gemini, OpenAI ou Claude).
4. Em seguida, sobe os contêineres e aguarda o healthcheck retornar status `UP`.

Após a inicialização, acesse:
- **Painel Principal**: [http://localhost:8080](http://localhost:8080)
- **Página de Login**: [http://localhost:8080/login](http://localhost:8080/login)
- **Gestão de Usuários**: [http://localhost:8080/admin/users](http://localhost:8080/admin/users)
- **Gestão de Provedores AI**: [http://localhost:8080/admin/models](http://localhost:8080/admin/models)
- **Métricas e Saúde**: [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)

---

## 4. Comandos do Script `install.sh`

| Comando | Descrição |
| :--- | :--- |
| `./install.sh` | Executa o instalador completo e assistente de ambiente. |
| `./install.sh --no-ingest` | Inicia o ambiente pulando a ingestão inicial de documentos. |
| `./install.sh --uninstall` | Para os contêineres, remove volumes persistentes e apaga `.env`. |
| `./install.sh --help` | Exibe o manual de opções e encerra. |

---

## 5. Execução em Desenvolvimento Local

Para executar o projeto diretamente com Maven e Java 25 / 21:

### 5.1. Subir apenas o Banco de Dados (PostgreSQL + pgvector)
```bash
docker compose up -d postgres
```

### 5.2. Executar a Aplicação Spring Boot
```bash
mvn spring-boot:run
```

A aplicação subirá na porta `8080`.

---

## 6. Suíte de Testes Automatizados

O projeto conta com **41 testes de integração automatizados** organizados em 12 suítes, cobrindo todo o ciclo funcional da plataforma:

```bash
mvn test
```

### Principais Suítes de Teste:
- `RagQualityEvaluationTest`: Avaliação do **Golden Dataset** com 20 perguntas oficiais do Manual do IRPF 2026 e controle de recusa estrita fora de escopo.
- `SecurityHardeningIntegrationTest`: Teste de limitação de taxa (HTTP 429 via Bucket4j), proteção contra prompt injection (HTTP 400) e validação de cabeçalhos de segurança (CSP, HSTS, X-Frame-Options).
- `SecurityIntegrationTest`: Testes de autenticação Google OAuth2/OIDC e autorização por papéis RBAC.
- `RagOrchestrationIntegrationTest`: Validação do fluxo completo de RAG, streaming SSE e formatação de citações canônicas.
- `MultiProviderModelIntegrationTest`: Teste de alternância entre os 6 provedores de IA e criptografia AES-256 de chaves de API.
- `DocumentIngestionIntegrationTest`: Testes de extração PDF, segmentação polimórfica e deduplicação de chunks por SHA-256.
- `HybridSearchIntegrationTest`: Teste de fusão de busca vetorial e textual via RRF.

---

## 7. Variáveis de Ambiente (`.env`)

| Variável | Padrão | Descrição |
| :--- | :--- | :--- |
| `POSTGRES_DB` | `exegese_db` | Nome do banco de dados relacional. |
| `POSTGRES_USER` | `exegese_user` | Usuário do banco PostgreSQL. |
| `POSTGRES_PASSWORD` | `exegese_password` | Senha do banco PostgreSQL. |
| `POSTGRES_PORT` | `5432` | Porta mapeada do PostgreSQL. |
| `PORT` | `8080` | Porta HTTP da aplicação web. |
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

## 8. Estratégias de Chunking Polimórfico

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

## 9. Licença e Autoria

Este projeto é software livre licenciado sob os termos da [Licença MIT](LICENSE).

**Autor e Mantenedor**:
- **Rivelino Patrício**
