# EXEGESE AI — ARQUITETURA DETALHADA E JUSTIFICATIVAS TÉCNICAS

**Documento de Arquitetura de Software (SAD) da Plataforma Exegese AI**  
*Java 25 (LTS) | Spring Boot 4.x | Spring AI | PostgreSQL 17 + pgvector | Thymeleaf + HTMX + SSE*

---

## 1. VISÃO GERAL ARQUITETURAL

A arquitetura do **Exegese AI** foi projetada para atender a três requisitos não-negociáveis:
1. **Fidelidade e Rigor Exegético**: Todas as inferências geradas pelo LLM devem ser restritas ao contexto recuperado da base documental, com citação explícita das fontes e recusa imediata de alucinação.
2. **Independência e Multi-Provedor de IA**: O sistema não pode ser refém de uma única nuvem ou fornecedor, permitindo ao administrador alternar em tempo de execução entre **Google Gemini, Anthropic Claude, OpenAI ChatGPT, NVIDIA Nemotron, DeepSeek AI e Processamento Local (Ollama)**.
3. **Eficiência e Baixo Acoplamento**: Construção sobre a plataforma Java moderna com Virtual Threads (Loom), renderização reativa server-side (sem a sobrecarga de SPAs pesadas) e banco de dados único combinando metadados relacionais, vetores HNSW e busca léxica em português.

---

## 2. JUSTIFICATIVAS TÉCNICAS DE CADA COMPONENTE

### 2.1 Runtime: Java 25 (LTS)
- **Virtual Threads (Project Loom)**: Em uma aplicação RAG, a maioria das operações é intensiva em I/O (consultas de banco de dados, requisições HTTP para endpoints de embedding e chamadas de streaming para LLMs). As Virtual Threads (`java.lang.Thread.ofVirtual()`) eliminam a necessidade de programação reativa excessivamente verbosa ou piscinas restritas de threads de SO, escalando milhares de conexões simultâneas com pegada de memória desprezível.
- **Records & Pattern Matching**: Utilizados para modelar DTOs imutáveis (`ChatRequestDTO`, `CitationDTO`, `QuestionChunk`) e despachar eventos de forma concisa e segura através de `switch` com pattern matching exhaustivo.
- **ZGC Geracional**: Garante tempos de pausa de Garbage Collection na casa dos microssegundos mesmo sob ingestão massiva de documentos grandes em memória.

### 2.2 Framework Base: Spring Boot 4.x / Spring Framework 7
- **Injeção de Dependências Estrita por Construtor**: Alinhamento com a diretriz mandatória de qualidade do projeto (`JAVA_CONSTRUCTOR_PARAMETER_INJECTION`). Campos declarados como `private final`, garantindo imutabilidade e testabilidade trivial com Mockito.
- **Adoção do Jakarta EE 11**: Compatibilidade nativa com Servlets 6.1 e Tomcat 11 embarcado, oferecendo suporte robusto a HTTP/2 e streaming de alta taxa.
- **Spring Boot Actuator & Micrometer**: Telemetria out-of-the-box para monitoramento de latência de busca vetorial, tempo de geração de tokens e contagem de recusas por anti-alucinação.

### 2.3 Framework de RAG: Spring AI 1.0.x
- **Abstração Fluente de Primeira Classe**: O `ChatClient` do Spring AI unifica a comunicação com múltiplos provedores via API declarativa.
- **Advisors Interceptores**: Permite plugar de forma limpa componentes de memória de conversa (`MessageChatMemoryAdvisor`), reescrita de perguntas e injeção contextual de grounding (`QuestionAnswerAdvisor`).
- **Eliminação de Dependências Externas (Python/Bridges)**: Evita a introdução de microsserviços intermediários em Python (como LangChain ou LlamaIndex), mantendo toda a lógica de negócio compilada em um fat-jar único de alta performance.

### 2.4 Armazenamento e Recuperação: PostgreSQL 17 + pgvector 0.8.0
A opção pelo PostgreSQL com `pgvector` em detrimento de bancos vetoriais dedicados (como Qdrant, Milvus, Chroma ou Pinecone) baseia-se em critérios de engenharia pragmáticos:
1. **Transacionalidade ACID Unificada**: Usuários, permissões de acesso, assuntos (topics), metadados de documentos e vetores de embeddings coabitam o mesmo banco. Não há risco de descompasso de dados entre o banco relacional e o índice vetorial.
2. **Busca Híbrida Nativa no Mesmo Nó**: O PostgreSQL executa tanto a similaridade de cosseno (índice HNSW) quanto a busca léxica por relevância (índice GIN sobre `tsvector` com parser em português).
3. **Filtragem Relacional Eficiente**: Permite aplicar filtros complexos de controle de acesso e de assuntos selecionados pelo usuário diretamente na cláusula `WHERE` da consulta vetorial (`WHERE collection_id IN (...) AND subject_id IN (...)`).
4. **Baixa Complexidade Operacional**: Um único container de banco de dados para gerenciar, realizar backup (`pg_dump`) e replicar.

### 2.5 Algoritmo de Busca Híbrida: Reciprocal Rank Fusion (RRF)
Perguntas técnicas e fiscais apresentam dois desafios complementares:
- **Desafio Semântico**: Usuários usam linguagem leiga ("posso descontar escola de filho de 24 anos?"), onde a busca vetorial brilha por capturar sinônimos e paráfrases.
- **Desafio Léxico/Normativo**: Usuários citam identificadores exatos ("Lei 14.754", "Dabim", "DARF 0190", "art. 733", "Cosit 354"), onde a busca vetorial pura frequentemente erra por diluir tokens numéricos e siglas.

O **Exegese AI** combina ambas as abordagens através do **Reciprocal Rank Fusion**:
$$RRF(d) = \sum_{m \in M} \frac{1}{60 + r_m(d)}$$
Onde $r_m(d)$ representa a posição do documento no ranking do método $m$ (vetorial ou textual). O documento com maior score combinado emerge como topo do ranking.

### 2.6 Multi-Provedor Dinâmico de Modelos de Linguagem
A camada `LlmProviderRouter` implementa o padrão *Strategy/Adapter*, permitindo alternar entre 6 ecossistemas:
1. **Google Gemini**: Excelente custo-benefício, tier gratuito generoso (15 RPM / 1.500 RPD no Google AI Studio), janela de contexto superior a 1 milhão de tokens e excelente compreensão do português formal.
2. **Anthropic Claude**: Líder em exegese analítica, raciocínio contextual complexo e obediência a restrições negativas de prompt.
3. **OpenAI ChatGPT**: Padrão de interoperabilidade com modelos GPT-4o e GPT-4o-mini.
4. **NVIDIA Nemotron**: Inferência de alta velocidade com modelos open-source otimizados no ecossistema NVIDIA NIM.
5. **DeepSeek AI**: Excelente custo por milhão de tokens e forte capacidade de raciocínio lógico (DeepSeek-R1 e V3).
6. **Processamento Local (Ollama)**: Soberania total de dados, custo zero de tráfego e conformidade estrita com LGPD para documentos sigilosos, rodando on-premise com modelos como Qwen 2.5 7B ou Llama 3.2.

**Gestão Híbrida de Chaves**: As chaves podem ser injetadas de forma tradicional via variáveis de ambiente no `.env` OU configuradas diretamente pelo Administrador na interface web, sendo persistidas criptografadas com **AES-256-GCM** na tabela `ai_model_config`.

### 2.7 Autenticação e Autorização: Google OAuth2 / OIDC + RBAC
- **Google Sign-In**: Elimina armazenamento e gerenciamento arriscado de senhas de usuários no banco. Integração via `spring-boot-starter-oauth2-client`.
- **RBAC (Role-Based Access Control)**:
  - `ROLE_ADMIN`: Acesso irrestrito a todos os assuntos, gestão de usuários, concessão de permissões, manutenção de documentos e troca de modelo de IA ativo.
  - `ROLE_OPERATOR`: Permissão para upload e manutenção de documentos nos assuntos autorizados.
  - `ROLE_USER`: Pesquisa e consulta em linguagem natural nos assuntos permitidos.
- **Bootstrap do Primeiro Administrador**: Configuração da variável `INITIAL_ADMIN_EMAIL` no `.env`. Quando o usuário correspondente realizar o primeiro login via Google, o sistema automaticamente lhe confere a credencial `ROLE_ADMIN`.

### 2.8 Interface do Usuário: Thymeleaf + HTMX + Server-Sent Events (SSE)
- **Simplicidade Operacional**: Elimina a necessidade de manter um pipeline separado em Node.js / React / Angular. O binário da aplicação entrega o HTML já renderizado no servidor.
- **Reatividade sem SPA**: O **HTMX** permite substituições parciais de elementos do DOM sem recarregamento da página.
- **Streaming Fluido de Respostas**: O endpoint de chat emite eventos `text/event-stream` (SSE), permitindo que o usuário veja a resposta ser digitada token-a-token em tempo real.
- **Chips Interativos de Citação**: Cada citação é um componente clicável que abre um modal ou popover detalhando o trecho oficial original do documento, eliminando qualquer dúvida sobre a autenticidade da resposta.

---

## 3. DIAGRAMAS ARQUITETURAIS DETALHADOS

### 3.1 Diagrama de Containers Docker

```mermaid
C4Container
    title "Diagrama de Containers - Exegese AI"

    Person(user, "Usuário Autenticado", "Cidadão ou colaborador autorizado")
    Person(admin, "Administrador do Sistema", "Gestor de permissões e infraestrutura de IA")

    Container_Boundary(docker_env, "Ambiente Docker Compose - Exegese AI") {
        Container(app, "Exegese AI Web App", "Spring Boot 4.x / Java 25", "Controladores MVC, Spring Security OAuth2, Spring AI, Serviços de Ingestão e RAG")
        ContainerDb(db, "PostgreSQL 17 + pgvector", "pgvector/pgvector:pg17", "Armazena usuários, permissões, assuntos, documentos, vetores HNSW e FTS")
        Container(ollama, "Serviço Ollama (Opcional)", "ollama/ollama:latest", "Servidor de modelos locais para execução offline")
        ContainerDb(vol_pgdata, "Volume Docker: pgdata", "Volume Persistente", "Dados relacionais e índices vetoriais")
        ContainerDb(vol_docs, "Volume Docker: doc_storage", "Volume Persistente", "Armazenamento dos PDFs e arquivos originais")
        ContainerDb(vol_ollama, "Volume Docker: ollama_models", "Volume Persistente", "Pesos dos modelos locais")
    }

    System_Ext(google_idp, "Google OAuth2", "Provedor de Identidade")
    System_Ext(ai_apis, "Provedores de IA em Nuvem", "Gemini, Anthropic, OpenAI, NVIDIA, DeepSeek")

    Rel(user, app, "Navegação, busca por assunto e chat com streaming", "HTTPS / Porta 8080")
    Rel(admin, app, "Gestão de usuários, acervo e seleção de modelos", "HTTPS / Porta 8080")
    Rel(app, google_idp, "Autenticação via Google Sign-In", "HTTPS / 443")
    Rel(app, db, "Consultas relacionais, busca vetorial e FTS", "JDBC / Porta 5432")
    Rel(app, ai_apis, "Chamadas a LLMs e embeddings na nuvem", "HTTPS / 443")
    Rel(app, ollama, "Chamadas a LLMs e embeddings locais", "HTTP / Porta 11434")
    Rel(db, vol_pgdata, "Persistência em disco", "Filesystem")
    Rel(app, vol_docs, "Persistência de arquivos", "Filesystem")
    Rel(ollama, vol_ollama, "Cache de modelos", "Filesystem")
```
*Arquivo Mermaid independente*: [`diagrams/c4_container.mmd`](diagrams/c4_container.mmd)

---

### 3.2 Diagrama de Componentes Internos do Backend

```mermaid
classDiagram
    direction TB

    class AuthModule {
        +SecurityConfig
        +GoogleOAuth2SuccessHandler
        +CustomOidcUserService
        +SecurityContextFacade
    }

    class AdminModule {
        +UserManagementController
        +DocumentManagementController
        +SubjectManagementController
        +ModelConfigurationController
        +UserService
        +ModelProviderRegistryService
    }

    class QueryModule {
        +ChatViewController
        +ChatApiController
        +RagOrchestrationService
        +QueryRewritingService
        +AntiHallucinationGuard
    }

    class RetrievalModule {
        +HybridSearchService
        +SubjectFilteredVectorSearch
        +ReciprocalRankFusionCalculator
    }

    class IngestionModule {
        +DocumentIngestionService
        +SegmentationStrategyFactory
        +PdfTextExtractor
        +ChunkChecksumService
    }

    class AIProviderModule {
        +LlmProviderRouter
        +GeminiModelAdapter
        +ClaudeModelAdapter
        +OpenAiModelAdapter
        +NemotronModelAdapter
        +DeepSeekModelAdapter
        +OllamaLocalAdapter
    }

    AuthModule --> AdminModule : protege rotas
    AuthModule --> QueryModule : identifica sessao
    QueryModule --> RetrievalModule : solicita chunks por assunto
    QueryModule --> AIProviderModule : gera resposta via LLM ativo
    AdminModule --> AIProviderModule : configura modelo ativo
    AdminModule --> IngestionModule : comanda ingestao
    IngestionModule --> RetrievalModule : popula vetores e FTS
```
*Arquivo Mermaid independente*: [`diagrams/c4_components.mmd`](diagrams/c4_components.mmd)

---

### 3.3 Diagrama Entidade-Relacionamento (ERD)

```mermaid
erDiagram
    EXEGESE_USER ||--o{ USER_SUBJECT_PERMISSION : possui
    EXEGESE_USER {
        uuid id PK
        string email UK
        string name
        string avatar_url
        string role
        boolean active
        timestamp created_at
        timestamp last_login_at
    }

    EXEGESE_SUBJECT ||--o{ USER_SUBJECT_PERMISSION : concedido_a
    EXEGESE_SUBJECT ||--o{ DOCUMENT_SUBJECT : categoriza
    EXEGESE_SUBJECT {
        uuid id PK
        string code UK
        string name
        string description
        boolean active
        timestamp created_at
    }

    USER_SUBJECT_PERMISSION {
        uuid user_id PK, FK
        uuid subject_id PK, FK
        string permission_level
        timestamp granted_at
    }

    EXEGESE_DOCUMENT ||--o{ DOCUMENT_SUBJECT : associado_a
    EXEGESE_DOCUMENT ||--o{ EXEGESE_CHUNK : contem
    EXEGESE_DOCUMENT {
        uuid id PK
        string title
        string original_file_name
        string storage_path
        string file_hash_sha256 UK
        bigint file_size
        string file_type
        int total_pages
        string segmentation_strategy
        string status
        timestamp created_at
        timestamp updated_at
    }

    DOCUMENT_SUBJECT {
        uuid document_id PK, FK
        uuid subject_id PK, FK
        timestamp tagged_at
    }

    EXEGESE_CHUNK {
        uuid id PK
        uuid document_id FK
        string chunk_hash_sha256 UK
        int sequence_number
        string title
        text content
        jsonb metadata
        vector embedding_768
        tsvector tsv_portuguese
        timestamp created_at
    }

    AI_MODEL_CONFIG {
        uuid id PK
        string provider UK
        string display_name
        string model_name
        string base_url
        string api_key_encrypted
        boolean is_active
        boolean is_default
        float temperature
        int max_tokens
        timestamp updated_at
    }

    CHAT_SESSION ||--o{ CHAT_MESSAGE : possui
    CHAT_SESSION {
        uuid id PK
        uuid user_id FK
        string title
        timestamp created_at
        timestamp updated_at
    }

    CHAT_MESSAGE {
        uuid id PK
        uuid session_id FK
        string role
        text content
        jsonb applied_subject_ids
        jsonb citations
        string model_used
        int execution_duration_ms
        timestamp created_at
    }
```
*Arquivo Mermaid independente*: [`diagrams/erd_datamodel.mmd`](diagrams/erd_datamodel.mmd)

---

### 3.4 Fluxo de Chaveamento Dinâmico de Modelos pelo Administrador

```mermaid
flowchart TD
    Admin["Administrador no Painel Web (/admin/models)"] --> Selecao["Seleciona Provedor Ativo"]
    Selecao --> P1["Google Gemini (Flash / Pro)"]
    Selecao --> P2["Anthropic Claude (3.5 / 3.7 Sonnet)"]
    Selecao --> P3["OpenAI ChatGPT (GPT-4o / GPT-4o-mini)"]
    Selecao --> P4["NVIDIA Nemotron (Llama-3.1-Nemotron via NIM)"]
    Selecao --> P5["DeepSeek AI (DeepSeek-V3 / R1)"]
    Selecao --> P6["Processamento Local (Ollama)"]

    P1 & P2 & P3 & P4 & P5 & P6 --> Chave{"Origem da Chave de API"}
    Chave -- "Definida no .env / Variável de Ambiente" --> SalvarConfig["Atualiza flag is_default no banco de dados"]
    Chave -- "Informada na tela do Painel" --> Cripto["Criptografia AES-256 no banco de dados"]
    Cripto --> SalvarConfig
    
    SalvarConfig --> Evento["Publicação de ApplicationEvent: ActiveModelChangedEvent"]
    Evento --> Router["LlmProviderRouter recarrega ChatClient em tempo de execução"]
    Router --> Pronto["Próxima pergunta dos usuários já utiliza o novo modelo sem reiniciar"]
```
*Arquivo Mermaid independente*: [`diagrams/model_selection_flow.mmd`](diagrams/model_selection_flow.mmd)

---

### 3.5 Diagrama de Implantação e Redes

```mermaid
graph TB
    subgraph HostServer["Servidor Host (Docker Engine / Linux)"]
        subgraph DockerNetwork["Rede Docker: exegese-net (Bridge)"]
            subgraph AppContainer["Container: exegese-ai-app"]
                App["Spring Boot 4.x (Java 25 LTS)<br/>Virtual Threads Habilitadas<br/>Porta Interna: 8080<br/>Healthcheck: /actuator/health"]
            end

            subgraph DbContainer["Container: exegese-ai-db"]
                Postgres["PostgreSQL 17.2 + pgvector 0.8.0<br/>Porta Interna: 5432<br/>Base: exegese_ai_db<br/>Healthcheck: pg_isready"]
            end

            subgraph OllamaContainer["Container: exegese-ai-ollama (Profile local)"]
                Ollama["Ollama Engine (Local LLM)<br/>Porta Interna: 11434"]
            end
        end

        VolDB[("Volume Docker: exegese_pgdata")]
        VolDocs[("Volume Docker: exegese_docs")]
        VolOllama[("Volume Docker: exegese_ollama_models")]

        Postgres --- VolDB
        App --- VolDocs
        Ollama --- VolOllama

        Port8080["Porta Host: 8080"] --> App
        Port5432["Porta Host: 5432 (Debug)"] -.-> Postgres
    end

    CloudGoogle["Google Identity (OAuth2 OIDC)"]
    CloudLLMs["APIs Externas de LLMs (Gemini, Anthropic, OpenAI, NVIDIA, DeepSeek)"]

    App -->|OAuth2 HTTPS:443| CloudGoogle
    App -->|REST HTTPS:443| CloudLLMs
```
*Arquivo Mermaid independente*: [`diagrams/deployment.mmd`](diagrams/deployment.mmd)