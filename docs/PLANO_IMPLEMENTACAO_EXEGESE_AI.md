# PLANO DE IMPLEMENTAÇÃO TÉCNICA: EXEGESE AI
**Plataforma de Recuperação Aumentada por Geração (RAG) Especialista & Agnóstica a Acervos Documentais**  
*Fundamentação Factual Estrita, Citação Canônica de Fontes e Tolerância Zero a Alucinações*

---

## 1. RESUMO EXECUTIVO

**Exegese AI** é uma plataforma corporativa de Inteligência Artificial Generativa baseada em arquitetura **Retrieval-Augmented Generation (RAG)** desenvolvida em **Java 25 (LTS)** e **Spring Boot 4.x (Spring Framework 7)** sob o pacote base `br.org.rivelino.exegese_ai` (ArtifactId `exegese-ai`). O sistema foi concebido para ingerir, indexar e responder perguntas sobre **qualquer conjunto de documentos** (manuais técnicos, legislações, regulamentos, contratos, relatórios e normas operacionais), tendo como corpus de homologação e referência inicial o manual oficial **"Perguntas e Respostas do IRPF 2026" (Ano-Calendário 2025)** da Receita Federal do Brasil.

### 1.1 Significado e Identidade
O termo **Exegese** (do grego *exēgēsis*, "interpretação minuciosa e extração do sentido autêntico de um texto") sintetiza o compromisso central da solução: **nunca extrapolar ou inventar**. O modelo atua como um exegeta digital estrito, traduzindo as perguntas do usuário em buscas semânticas profundas e extraindo respostas amparadas 100% na letra dos documentos indexados.

### 1.2 Capacidades Fundamentais
1. **Multi-Assuntos e Relação N:N**: Documentos são agrupados em múltiplos **Assuntos/Temas** cadastrados (relação N:N), permitindo que um documento pertença a várias categorias e que o usuário filtre sua pesquisa selecionando os assuntos desejados.
2. **Módulo Administrativo Completo**:
   - Controle de usuários e permissões granulares por assunto documental via RBAC (`ROLE_ADMIN`, `ROLE_OPERATOR`, `ROLE_USER`).
   - Manutenção completa do banco de documentos (upload, reindexação, status e deleção).
   - Seleção dinâmica entre 6 ecossistemas de IA: **Google Gemini, Anthropic Claude, OpenAI ChatGPT, NVIDIA Nemotron, DeepSeek AI e Processamento Local (Ollama)**.
3. **Autenticação com Google (OAuth2 / OIDC)**: Login federado corporativo com bootstrap automático do primeiro administrador via variável `INITIAL_ADMIN_EMAIL`.
4. **Chunking Adaptativo**: Estratégias especializadas de chunking — semântico canônico por pergunta/tópico para manuais estruturados (ex.: IRPF), hierárquico por seções/artigos para legislações, e recursivo com overlap para relatórios gerais.
5. **Busca Híbrida com RRF**: Fusão de densidade vetorial (pgvector HNSW) com busca léxica esparsa em português (PostgreSQL FTS `tsvector` com BM25) através de *Reciprocal Rank Fusion*.
6. **Interface Reativa Server-Side**: Front-end leve e moderno com **Thymeleaf + HTMX + SSE**, com suporte a streaming de tokens em tempo real e visualização de citações canônicas em popover/modal.

---

## 2. INFERÊNCIA DA ARQUITETURA MULTI-COLEÇÃO & ADRs

### 2.1 Matriz de Decisão Arquitetural

| Dimensão | Escolha para o Exegese AI | Justificativa |
| :--- | :--- | :--- |
| **Identidade do Sistema** | **Exegese AI** (`exegese-ai`) | Reflete a precisão exegética, rigor interpretativo e seriedade corporativa. |
| **Pacote Base Java** | `br.org.rivelino.exegese_ai` | Nomenclatura corporativa padronizada, válida para a especificação da JVM. |
| **Agrupamento de Documentos** | **Assuntos com Relação N:N** | Flexibilidade para associar documentos a múltiplos temas temáticos (tags/assuntos) e filtros combinados na busca. |
| **Autenticação** | **Google OAuth2 / OIDC** | Autenticação federada confiável, sem necessidade de armazenamento de senhas locais. |
| **Gerenciamento de Modelos** | **Chaveamento Dinâmico (6 Provedores)** | Liberdade de alternar entre Gemini, Claude, OpenAI, Nemotron, DeepSeek e Ollama sem reiniciar a aplicação. |
| **Armazenamento Vetorial** | **PostgreSQL 17 + pgvector** | Permite consultas vetoriais com filtros relacionais compostos por assuntos permitidos e busca textual BM25. |
| **Framework RAG** | **Spring AI 1.0.x** | Padrão `ChatClient` com advisors fluentes e suporte a Virtual Threads do Java 25. |

---

## 3. MODELO DE DADOS & ESQUEMA MULTI-ASSUNTOS

```sql
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "vector";

-- 1. Usuários
CREATE TABLE IF NOT EXISTS exegese_user (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    avatar_url VARCHAR(500),
    role VARCHAR(50) NOT NULL DEFAULT 'ROLE_USER',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP WITH TIME ZONE
);

-- 2. Assuntos (Topics)
CREATE TABLE IF NOT EXISTS exegese_subject (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 3. Permissões de Usuários por Assunto
CREATE TABLE IF NOT EXISTS user_subject_permission (
    user_id UUID NOT NULL REFERENCES exegese_user(id) ON DELETE CASCADE,
    subject_id UUID NOT NULL REFERENCES exegese_subject(id) ON DELETE CASCADE,
    permission_level VARCHAR(50) NOT NULL DEFAULT 'READ',
    granted_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, subject_id)
);

-- 4. Documentos
CREATE TABLE IF NOT EXISTS exegese_document (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    title VARCHAR(255) NOT NULL,
    original_file_name VARCHAR(255) NOT NULL,
    storage_path VARCHAR(500) NOT NULL,
    file_hash_sha256 VARCHAR(64) NOT NULL UNIQUE,
    file_size BIGINT NOT NULL,
    file_type VARCHAR(50) NOT NULL,
    total_pages INT DEFAULT 1,
    segmentation_strategy VARCHAR(50) NOT NULL DEFAULT 'STRUCTURED_QA',
    status VARCHAR(50) NOT NULL DEFAULT 'UPLOADED',
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 5. Vínculo N:N entre Documentos e Assuntos
CREATE TABLE IF NOT EXISTS document_subject (
    document_id UUID NOT NULL REFERENCES exegese_document(id) ON DELETE CASCADE,
    subject_id UUID NOT NULL REFERENCES exegese_subject(id) ON DELETE CASCADE,
    tagged_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id, subject_id)
);

-- 6. Chunks Vetoriais e Textuais
CREATE TABLE IF NOT EXISTS exegese_chunk (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    document_id UUID NOT NULL REFERENCES exegese_document(id) ON DELETE CASCADE,
    chunk_hash_sha256 VARCHAR(64) NOT NULL UNIQUE,
    sequence_number INT NOT NULL,
    title VARCHAR(500),
    content TEXT NOT NULL,
    metadata JSONB NOT NULL,
    embedding VECTOR(768) NOT NULL,
    tsv TSVECTOR GENERATED ALWAYS AS (
        to_tsvector('portuguese', coalesce(title, '') || ' ' || content)
    ) STORED,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 7. Configuração de Modelos de IA
CREATE TABLE IF NOT EXISTS ai_model_config (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    provider VARCHAR(50) NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    base_url VARCHAR(255),
    api_key_encrypted VARCHAR(500),
    is_active BOOLEAN NOT NULL DEFAULT FALSE,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    temperature NUMERIC(3, 2) NOT NULL DEFAULT 0.10,
    max_tokens INT NOT NULL DEFAULT 1024,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Índices HNSW e GIN
CREATE INDEX IF NOT EXISTS idx_exegese_chunk_hnsw ON exegese_chunk USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);
CREATE INDEX IF NOT EXISTS idx_exegese_chunk_tsv ON exegese_chunk USING gin (tsv);
CREATE INDEX IF NOT EXISTS idx_exegese_chunk_metadata ON exegese_chunk USING gin (metadata jsonb_path_ops);
CREATE INDEX IF NOT EXISTS idx_doc_subject_subject ON document_subject (subject_id);
```

---

## 4. DIAGRAMAS ARQUITETURAIS

Consulte a pasta de diagramas dedicados em `diagrams/`:
- **Contexto (C4 Nível 1)**: [`diagrams/c4_context.mmd`](diagrams/c4_context.mmd)
- **Contêineres (C4 Nível 2)**: [`diagrams/c4_container.mmd`](diagrams/c4_container.mmd)
- **Componentes do Backend**: [`diagrams/c4_components.mmd`](diagrams/c4_components.mmd)
- **Modelo de Entidades (ERD)**: [`diagrams/erd_datamodel.mmd`](diagrams/erd_datamodel.mmd)
- **Fluxo de Ingestão**: [`diagrams/ingestion_flow.mmd`](diagrams/ingestion_flow.mmd)
- **Fluxo de Consulta & RRF**: [`diagrams/query_retrieval_flow.mmd`](diagrams/query_retrieval_flow.mmd)
- **Fluxo de Autenticação Google**: [`diagrams/auth_flow.mmd`](diagrams/auth_flow.mmd)
- **Seleção Dinâmica de Modelos**: [`diagrams/model_selection_flow.mmd`](diagrams/model_selection_flow.mmd)
- **Implantação Docker**: [`diagrams/deployment.mmd`](diagrams/deployment.mmd)

---

## 5. DOCUMENTOS DE REFERÊNCIA DO PROJETO

1. [`01_DEFINICAO_ESCOPO_EXEGESE_AI.md`](01_DEFINICAO_ESCOPO_EXEGESE_AI.md): Finalidade institucional e limites de escopo.
2. [`02_ARQUITETURA_DETALHADA_EXEGESE_AI.md`](02_ARQUITETURA_DETALHADA_EXEGESE_AI.md): Justificativas profundas de cada tecnologia e componente.
3. [`03_ESPECIFICACAO_TECNICA_EXEGESE_AI.md`](03_ESPECIFICACAO_TECNICA_EXEGESE_AI.md): Especificação de requisitos, endpoints, schemas e contratos.
4. [`04_PLANO_IMPLEMENTACAO_DETALHADO.md`](04_PLANO_IMPLEMENTACAO_DETALHADO.md): Roteiro de 12 etapas sequenciais de entrega e critérios de aceite.
5. [`PLANO_IMPLEMENTACAO_CHATBOT_RAG_IRPF.md`](PLANO_IMPLEMENTACAO_CHATBOT_RAG_IRPF.md): Especificação detalhada do acervo piloto e de referência (IRPF 2026).