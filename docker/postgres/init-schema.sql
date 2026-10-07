-- ==============================================================================
-- EXEGESE AI - SCHEMA DEFINITION (PostgreSQL 17 + pgvector)
-- Auto-executed on first container initialization
-- ==============================================================================

-- Habilitação das extensões necessárias
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "vector";

-- 1. Tabela de Usuários (Sincronizada via Google OAuth2)
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

-- 2. Tabela de Assuntos / Temas (Agrupamento de documentos)
CREATE TABLE IF NOT EXISTS exegese_subject (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 3. Tabela de Permissões de Usuários por Assunto (RBAC Granular)
CREATE TABLE IF NOT EXISTS user_subject_permission (
    user_id UUID NOT NULL REFERENCES exegese_user(id) ON DELETE CASCADE,
    subject_id UUID NOT NULL REFERENCES exegese_subject(id) ON DELETE CASCADE,
    permission_level VARCHAR(50) NOT NULL DEFAULT 'READ',
    granted_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, subject_id)
);

-- 4. Tabela de Documentos
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

-- 5. Tabela de Associação N:N entre Documentos e Assuntos
CREATE TABLE IF NOT EXISTS document_subject (
    document_id UUID NOT NULL REFERENCES exegese_document(id) ON DELETE CASCADE,
    subject_id UUID NOT NULL REFERENCES exegese_subject(id) ON DELETE CASCADE,
    tagged_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id, subject_id)
);

-- 6. Tabela Principal de Vetores, Chunks e Busca Textual (pgvector)
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

-- 7. Tabela de Configuração Dinâmica dos Modelos de IA
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

-- 8. Tabelas de Sessão e Mensagens do Chat
CREATE TABLE IF NOT EXISTS chat_session (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES exegese_user(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL DEFAULT 'Nova Consulta',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS chat_message (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id UUID NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    applied_subject_ids JSONB,
    citations JSONB,
    model_used VARCHAR(100),
    execution_duration_ms INT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Índices Especializados
CREATE INDEX IF NOT EXISTS idx_exegese_chunk_hnsw 
ON exegese_chunk USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);

CREATE INDEX IF NOT EXISTS idx_exegese_chunk_tsv 
ON exegese_chunk USING gin (tsv);

CREATE INDEX IF NOT EXISTS idx_exegese_chunk_metadata 
ON exegese_chunk USING gin (metadata jsonb_path_ops);

CREATE INDEX IF NOT EXISTS idx_exegese_chunk_doc 
ON exegese_chunk (document_id);

CREATE INDEX IF NOT EXISTS idx_doc_subject_subject 
ON document_subject (subject_id);
